// SPDX-License-Identifier: MIT
//
// wrobj.hpp — public C++ API of the native OBJ model pipeline.
//
// This header is the contract between the native library and its hosts. The JNI
// bridge (native/jni/wrobj_jni.cpp) is one host; the equivalence harness
// (native/tests/) is another. Keeping the pipeline reachable without a JVM is
// deliberate: it is what makes byte-exact verification against the Java reference
// possible in CI and during development.
//
// ---------------------------------------------------------------------------
// What replaces what
// ---------------------------------------------------------------------------
//
// The Java pipeline being replaced is:
//
//   ModelResourceLoader.loadModel
//     OptimizedModel.ObjModel.loadModel
//       ObjModelLoader.loadModel          -> ObjReader / MtlReader / ObjSplitting
//         ObjUtils.convertToRenderable    -> triangulate + make unique + index
//         RawMesh (vertices, faces)
//       per RawMesh: applyRotation X 180, then per ObjModel
//       ObjModel.addTransformation  -> per-placement copy + translate [+ rotate 180]
//       RawModel.append             -> merge by MaterialProperties
//       RawModel.generateNormals
//       RawModel.distinct
//       RawModel.upload             -> interleaved vertex buffer + index buffer
//
// WrObjBuilder walks exactly that sequence. The caller supplies the placements
// (translation, mirror, optional flip) up front instead of calling
// addTransformation repeatedly, because the native side can then merge, generate
// normals and de-duplicate in one traversal per material instead of one traversal
// per placement per material. The bytes produced are identical.
//
// ---------------------------------------------------------------------------
// Division of responsibility
// ---------------------------------------------------------------------------
//
// The native side owns OBJ/MTL parsing and all geometry. The host owns texture
// identity, because in the mod that identity is a Minecraft Identifier that only
// the host can construct. The handshake is:
//
//   1. wr_obj_keys_load      -> the material group keys, in the order the OBJ
//                               produces them, plus each key's shader type, raw
//                               texture string and colour.
//   2. host resolves each raw texture string into its own texture handle.
//   3. wr_obj_builder_create -> feed the keys and handles back in.
//   4. wr_obj_builder_add_transform / apply_* for every vehicle part placement.
//   5. wr_obj_builder_build  -> interleaved vertices, indices, and one draw
//                               record per material bucket.
//
// ---------------------------------------------------------------------------
// Vertex layout
// ---------------------------------------------------------------------------
//
// The interleaved vertex buffer uses the layout OptimizedModel.DEFAULT_MAPPING
// derives from VertexAttributeType, which for the OBJ path is:
//
//   offset  0  POSITION    3 x float32
//   offset 12  UV_TEXTURE  2 x float32
//   offset 20  NORMAL      3 x int8 (normalized), then 1 pad byte
//   stride    24
//
// COLOR, UV_OVERLAY, UV_LIGHTMAP and MATRIX_MODEL are GLOBAL attributes in that
// mapping, so they are not stored per vertex.

#pragma once

#include <cstddef>
#include <cstdint>

/// Version of the host-facing contract. Bumped whenever the semantics of an
/// existing entry point change, so a stale library is detected instead of
/// silently producing wrong geometry.
#define WROBJ_ABI_VERSION 1

/// Matches OptimizedModel.ShaderType's ordinal order.
enum WrObjShaderType {
    WROBJ_SHADER_CUTOUT = 0,
    WROBJ_SHADER_TRANSLUCENT = 1,
    WROBJ_SHADER_CUTOUT_BRIGHT = 2,
    WROBJ_SHADER_TRANSLUCENT_BRIGHT = 3,
    WROBJ_SHADER_CUTOUT_GLOWING = 4,
    WROBJ_SHADER_TRANSLUCENT_GLOWING = 5
};

/// Byte size of one interleaved vertex. Must equal
/// VertexAttributeMapping.strideVertex for DEFAULT_MAPPING.
#define WROBJ_VERTEX_STRIDE 24

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Stage 1 — key discovery
// ---------------------------------------------------------------------------

/// Everything the host needs about one material group key.
typedef struct WrObjKeyInfo {
    /// Material group name, exactly as it appears in the OBJ. Owned by the list.
    const char* key;
    /// Shader type the loader derives from the key's `#` options.
    int32_t shaderType;
    /// True when the key's options requested a V flip.
    int32_t flipTextureV;
    /// Texture string to resolve, already selected by the loader's three-way rule
    /// and normalised (lower case, backslashes to forward slashes). An empty
    /// string means the host should substitute its fallback texture. Owned by the
    /// list.
    const char* textureString;
    /// Pre-truncated packed colour from the MTL's Kd/d.
    ///
    /// Note the channel order, which is MTR's rather than the usual ARGB one: red
    /// occupies the most significant byte and alpha the least significant, so
    /// opaque red is 0xFF0000FF. Forcing the value is immediate, so the host must
    /// gate on hasColor before using it.
    int32_t color;
    /// True when a material was found for this key *and* it carried a Kd. When
    /// false the reference loader passes null to MaterialProperties and the colour
    /// comes from the global shader state instead, so the host must not force it.
    int32_t hasColor;
} WrObjKeyInfo;

typedef struct WrObjKeyList {
    const WrObjKeyInfo* entries;
    int32_t count;
} WrObjKeyList;

/// Parse an OBJ document plus its MTL and report the material group keys.
///
/// `mtlContent` may be null when the OBJ references no MTL; when null or empty
/// the loader takes its "no materials" branch and texture strings come from the
/// material group names.
///
/// Returns null when the document is structurally unreadable, which mirrors the
/// Java implementation aborting the load and logging.
WrObjKeyList* wr_obj_keys_load(
        const char* objContent,
        int32_t objLength,
        const char* mtlContent,
        int32_t mtlLength,
        int32_t flipTextureV);

void wr_obj_keys_free(WrObjKeyList* list);

// ---------------------------------------------------------------------------
// Stage 2 — placement capture
// ---------------------------------------------------------------------------

/// Builder accumulating placements for one OBJ model *instance*.
///
/// The reference implementation keeps one ObjModel per resource and deep-copies
/// it per vehicle part, so each part needs its own builder; sharing one
/// accumulates every part's geometry into a single buffer and reproduces the
/// cross-condition rendering bug the YanYang branch already had to fix. The
/// `objModel.copy()` call in ModelPropertiesPart is the place that distinction
/// lives in MTR.
typedef struct WrObjBuilder WrObjBuilder;

/// Create a builder.
///
/// `keyCount` entries of `keyTextureHandles` correspond one-to-one, in order, to
/// the entries of the list returned by wr_obj_keys_load. A handle of -1 means the
/// key had no usable texture and the host should use its fallback; such a key still
/// participates, because its geometry is real.
///
/// The handle is opaque and is only ever compared for equality. It joins the shader
/// type and the colour in the merge key, which is exactly MaterialProperties'
/// contract, so two keys whose materials resolved differently cannot be merged.
///
/// `keyColorSet` mirrors the key's own `hasColor`: when zero, the host is saying the
/// colour is not authoritative and the reference loader would have passed null to
/// MaterialProperties, leaving the global shader colour in charge. Keeping that
/// distinction in the merge key matters, because MaterialProperties compares its
/// vertex attribute state.
///
/// `materialGroupTextures` selects the loader's material branch: pass non-zero when
/// the MTL was present and parsed, zero to take the group-name-as-texture branch.
///
/// Returns null on a structurally unreadable document.
WrObjBuilder* wr_obj_builder_create(
        const char* objContent,
        int32_t objLength,
        const char* mtlContent,
        int32_t mtlLength,
        const int32_t* keyTextureHandles,
        const int32_t* keyColorSet,
        int32_t keyCount,
        int32_t materialGroupTextures,
        int32_t flipTextureV);

void wr_obj_builder_free(WrObjBuilder* builder);

/// RawMesh.applyTranslation / applyRotation / applyScale / applyMirror applied to
/// the source meshes. These are the ObjectResource-level transforms and affect
/// every placement added afterwards, exactly like the Java calls do. The rotation
/// runs X, then Y, then Z, each in its own pass.
void wr_obj_builder_apply_translation(WrObjBuilder* builder, double x, double y, double z);
void wr_obj_builder_apply_rotation(WrObjBuilder* builder, double xDegrees, double yDegrees, double zDegrees);
void wr_obj_builder_apply_scale(WrObjBuilder* builder, double x, double y, double z);
void wr_obj_builder_apply_mirror(WrObjBuilder* builder, int32_t x, int32_t y, int32_t z);

/// The four operations above behind one entry point, for hosts that carry the transform as
/// data rather than as a call: 0 translation, 1 rotation in degrees, 2 scale, 3 mirror flags.
/// An unknown operation is ignored rather than treated as an error.
void wr_obj_builder_apply_transform(
        WrObjBuilder* builder,
        int32_t operation,
        double a,
        double b,
        double c);

/// ObjModel.addTransformation(shaderType, x, y, z, flipped).
///
/// Appends a placed copy of every source mesh into the accumulated model. The
/// shader type overrides CUTOUT sources only; RawMesh's copy constructor keeps
/// the original MaterialProperties for every other shader type. This is called
/// once per vehicle part position, so it is the highest-frequency entry point in
/// the pipeline.
void wr_obj_builder_add_transform(
        WrObjBuilder* builder,
        int32_t shaderType,
        double x,
        double y,
        double z,
        int32_t flipped);

/// Bounding box of the source meshes, reflecting every apply_* call so far.
/// MTR builds its part boxes from these, so they must track the transforms.
void wr_obj_builder_get_bounds(
        const WrObjBuilder* builder,
        float* minX, float* minY, float* minZ,
        float* maxX, float* maxY, float* maxZ);

// ---------------------------------------------------------------------------
// Stage 3 — geometry upload
// ---------------------------------------------------------------------------

/// One material bucket of the result.
typedef struct WrObjResultDraw {
    int32_t shaderType;
    int32_t color;
    /// True when `color` is authoritative. When false the reference host passes null
    /// to MaterialProperties and the colour comes from the global shader state.
    int32_t hasColor;
    /// Texture handle the host passed in for this bucket's key.
    int32_t textureHandle;
    /// Index into the key list returned by wr_obj_keys_load, so a host can recover
    /// the material group name and texture string for this bucket.
    int32_t keyIndex;
    /// Offset into the vertex buffer, in vertices.
    int32_t vertexOffset;
    int32_t vertexCount;
    /// Offset into the index buffer, in indices.
    int32_t indexOffset;
    int32_t indexCount;
} WrObjResultDraw;

typedef struct WrObjResult {
    /// Interleaved vertex data, `vertexDataLength == totalVertexCount * 24` bytes.
    void* vertexData;
    int32_t vertexDataLength;
    /// uint32 triangle indices, `indexDataLength == totalIndexOfVertexCount * 4`.
    void* indexData;
    int32_t indexDataLength;
    /// One WrObjResultDraw per material bucket, in accumulated draw order.
    void* drawData;
    int32_t drawCount;
    int64_t totalVertexCount;
    int64_t totalFaceCount;
    /// Estimated Java heap bytes the reference pipeline would have allocated to
    /// reach the same buffers. Reported so the saving is measurable, not asserted.
    int64_t estimatedJavaHeapBytes;
} WrObjResult;

/// Run generateNormals -> distinct -> upload over the accumulated model.
/// Returns null when the model produced no geometry.
WrObjResult* wr_obj_builder_build(WrObjBuilder* builder);

void wr_obj_result_free(WrObjResult* result);

/// ABI guard. The JNI bridge refuses to load a native library whose value does
/// not match the Java side's expectation.
int32_t wr_obj_abi_version(void);

/// Human-readable library version, for logs.
const char* wr_obj_version_string(void);

#ifdef __cplusplus
} // extern "C"
#endif
