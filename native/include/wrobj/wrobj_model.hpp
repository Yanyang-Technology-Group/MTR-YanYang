// SPDX-License-Identifier: MIT
//
// wrobj_model.hpp — data model mirroring the Java classes that MTR's OBJ
// pipeline is built from.
//
// Direct correspondence:
//
//   de.javagl.obj                       org.mtr.mapping.render.*
//   ----------------------------------  ------------------------------------
//   Obj (vertices/texCoords/normals)    RawMesh::Vertex list
//   ObjFace (vertex/texCoord/normal ix) RawMesh::Face list
//   ObjGroup (by group / by material)   RawModel::materialPropertiesToRawMesh
//   ObjUtils.convertToRenderable        ObjModelLoader::loadModel inner body
//
// The point of this file is not to be a nicer object model — it is to be the
// *same* object model, so that the vertex order, the face order, the
// de-duplication order and therefore the exact bytes handed to OpenGL are
// reproducible. See docs in native/README.md for the equivalence proof.

#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "wrobj/wrobj_math.hpp"

namespace wrobj {

// ---------------------------------------------------------------------------
// MTL
// ---------------------------------------------------------------------------

/// Subset of de.javagl.obj.Mtl that ObjModelLoader actually reads.
/// `dissolve` defaults to 1.0 because DefaultMtl.getD() returns 1.0 when unset.
struct Mtl {
    std::string name;
    bool hasKd = false;
    float kdR = 1.0F;
    float kdG = 1.0F;
    float kdB = 1.0F;
    bool hasDissolve = false;
    float dissolve = 1.0F;
    std::string mapKd;
};

// ---------------------------------------------------------------------------
// Faces and meshes
// ---------------------------------------------------------------------------

/// Mirrors org.mtr.mapping.render.model.Face (an ordered vertex index list).
struct Face {
    std::vector<int32_t> vertices;
};

/// Mirrors org.mtr.mapping.render.vertex.Vertex.
///
/// Equality deliberately ignores `color` and `light`, exactly like the Java
/// equals(): two vertices are the same when position, normal and UV agree.
struct Vertex {
    Vec3f position;
    Vec3f normal;
    float u = 0.0F;
    float v = 0.0F;
    int32_t color = 0;
    int32_t light = 0;
};

/// True when two vertices compare equal under the Java equals() contract.
bool vertexEquals(const Vertex& a, const Vertex& b);

/// 64-bit hash matching the Java hashCode() input set (position, normal, u, v).
uint64_t vertexHash(const Vertex& vertex);

/// Mirrors org.mtr.mapping.render.batch.MaterialProperties.
///
/// The Java class folds shaderType/texture/color into flags that decide which
/// of the three batch buckets a mesh lands in. Only those flags and the identity
/// key matter for building geometry, so the texture is carried as an opaque
/// resolved handle supplied by the host.
struct MaterialProps {
    int32_t shaderType = 0;
    int32_t textureId = 0;
    bool hasColor = false;
    int32_t color = 0;
    bool translucent = false;
    bool writeDepthBuf = true;
    bool cutoutHack = false;

    bool operator==(const MaterialProps& other) const {
        return shaderType == other.shaderType
                && textureId == other.textureId
                && hasColor == other.hasColor
                && color == other.color;
    }
};

/// Mirrors org.mtr.mapping.render.model.RawMesh: one material's vertex+face soup.
struct RawMesh {
    MaterialProps material;
    std::vector<Vertex> vertices;
    std::vector<Face> faces;

    void clear();
    void append(const RawMesh& next);
    void triangulate();
    void distinct();
    void generateNormals();
    void applyTranslation(float x, float y, float z);
    void applyRotationAxis(float angle, const Vec3f& axis);
    void applyRotationX(float degrees);
    void applyRotationY(float degrees);
    void applyRotationZ(float degrees);
    void applyScale(float x, float y, float z);
    void applyMirror(bool vx, bool vy, bool vz, bool nx, bool ny, bool nz);
    void applyUVMirror(bool u, bool v);
    void applyShear(const Vec3f& dir, const Vec3f& shear, float ratio);
};

/// Mirrors org.mtr.mapping.render.model.RawModel: a material-keyed ordered map of
/// RawMesh. Insertion order is preserved because it decides draw order.
struct RawModel {
    std::vector<MaterialProps> keys;
    std::vector<RawMesh> meshes;

    void append(const RawMesh& next);
    void triangulate();
    void distinct();
    void generateNormals();
    void applyTranslation(float x, float y, float z);
    void applyRotationX(float degrees);
    void applyRotationY(float degrees);
    void applyRotationZ(float degrees);
    void applyScale(float x, float y, float z);
    void applyMirror(bool vx, bool vy, bool vz, bool nx, bool ny, bool nz);
    void applyUVMirror(bool u, bool v);

private:
    RawMesh& getOrCreate(const MaterialProps& material);
};

// ---------------------------------------------------------------------------
// OBJ document, mirroring de.javagl.obj.Obj after ObjReader.read()
// ---------------------------------------------------------------------------

/// One face as parsed from an `f` statement, before index normalisation.
///
/// `materialKey` carries the active material group name across
/// convertToRenderable, which is what lets the mesh builder split by material
/// group without re-walking the document. Empty means "no usemtl was active",
/// which is the DefaultObj "default" group.
struct ParsedFace {
    std::vector<int32_t> v;
    std::vector<int32_t> vt;
    std::vector<int32_t> vn;
    std::string materialKey;
};

struct ObjGroup {
    std::string name;
    std::vector<int32_t> faceIndices;
};

/// The full granularity of group/material-group splitting that MTR relies on.
struct ObjDocument {
    std::vector<Vec3f> vertices;
    /// Texture coordinates. javagl keeps up to three components and so does this,
    /// because a face that never sets a texcoord index must still see "absent".
    std::vector<Vec3f> texCoords;
    std::vector<Vec3f> normals;
    std::vector<ParsedFace> faces;

    /// Groups in first-activation order; index 0 is always "default".
    /// `groupCreatedAtFace` records the face ordinal at which the group first
    /// became active, which is how DefaultObj's "active group" bookkeeping is
    /// reconstructed without storing a face list per group.
    std::vector<ObjGroup> groups;
    std::vector<int32_t> groupCreatedAtFace;
    /// Material groups in first-activation order; index 0 is always "default".
    std::vector<ObjGroup> materialGroups;
    /// For each face: the material group it started (empty when it did not).
    std::vector<std::string> faceStartedMaterialGroup;
    /// True when the document contained an `mtllib` statement.
    bool hasMtlLib = false;

    int32_t findGroup(const std::string& name) const;
    int32_t findMaterialGroup(const std::string& name) const;
};

/// Parse an OBJ document. Input is decoded as US-ASCII because ObjReader uses
/// StandardCharsets.US_ASCII: bytes >= 0x80 become U+FFFD, and that matters for
/// group names produced by non-English model editors.
/// Returns false only on structural failure, which mirrors the Java behaviour of
/// aborting the whole load.
bool parseObj(const std::string& content, ObjDocument& out);

/// Parse an MTL document. Returns false on structural failure.
bool parseMtl(const std::string& content, std::vector<Mtl>& out);

// ---------------------------------------------------------------------------
// Group extraction (ObjUtils.groupToObj / ObjSplitting)
// ---------------------------------------------------------------------------

/// Standalone OBJ fragment produced by group extraction. This is the same shape
/// as ObjDocument but with compacted, per-group vertices.
struct ObjFragment {
    std::vector<Vec3f> vertices;
    std::vector<Vec3f> texCoords;
    std::vector<Vec3f> normals;
    std::vector<ParsedFace> faces; // already vertex-indexed (vt/vn mirror v)
};

/// ObjUtils.groupToObj: remap only the vertices/texcoords/normals the group's
/// faces touch, in first-touch order.
void groupToFragment(const ObjDocument& document, const ObjGroup& group, ObjFragment& out);

/// ObjUtils.convertToRenderable: triangulate, then make texcoords and normals
/// unique per vertex, then collapse everything onto a single vertex index.
/// This is where the reference implementation's vertex ordering is decided.
void convertToRenderable(const ObjFragment& input, ObjFragment& out);

// ---------------------------------------------------------------------------
// Mesh construction (ObjModelLoader.loadModel body)
// ---------------------------------------------------------------------------

/// Material group options parsed from the `g` name, mirroring
/// ObjModelLoader.splitMaterialOptions.
struct MaterialOptions {
    std::string materialGroupName;
    std::string shaderTypeToken;
    bool flipTextureV = false;
};

MaterialOptions splitMaterialOptions(const std::string& source);

/// Legacy shader type mapping (ObjModelLoader.legacyMapping).
int32_t legacyShaderType(const std::string& token);

/// A material group's resolved appearance.
///
/// This mirrors what ObjModelLoader reads out of a parsed MTL. It is exposed so
/// hosts can share one vocabulary with the library when they describe materials;
/// the geometry builder itself receives already-resolved values through the
/// public C API.
struct ResolvedMaterial {
    /// True when the MTL held an entry under this material group's name.
    bool materialFound = false;
    /// True when that entry had a non-empty map_Kd. When false the loader falls
    /// back to using the material group name as the texture string.
    bool hasMap = false;
    std::string mapKd;
    /// Pre-computed ARGB colour, already truncated the way Java does.
    int32_t color = 0;
};

} // namespace wrobj
