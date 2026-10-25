// SPDX-License-Identifier: MIT
//
// wrobj.cpp — implementation of the public C++ API.
//
// Structure of a build:
//
//   wr_obj_keys_load()      parse OBJ + MTL once and report the material group
//                           keys with their shader type, texture string and
//                           colour, so the host can resolve texture identity.
//   wr_obj_builder_create() materialise the source RawMeshes for every
//                           (model group, material group) pair, applying the
//                           ObjModel.loadModel rotation (X 180) a single time.
//   add_transform()         append a placed copy of every source mesh, with the
//                           shader override and merge-by-MaterialProperties that
//                           ObjModel.addTransformation + RawModel.append perform.
//   build()                 generateNormals, distinct, and pack the interleaved
//                           vertex buffer plus the index buffer.
//
// The reference implementation does the same work with a Java object per vertex,
// per normal, per placement copy and per de-duplication probe. For a busy
// resource pack that is tens of millions of short-lived objects; here it is a
// handful of flat arrays, which is where the allocation and GC pressure go away.

#include "wrobj/wrobj.hpp"

#include "wrobj/wrobj_model.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

using wrobj::Face;
using wrobj::Mtl;
using wrobj::ObjDocument;
using wrobj::ObjFragment;
using wrobj::ObjGroup;
using wrobj::ParsedFace;
using wrobj::RawMesh;
using wrobj::Vertex;

constexpr int32_t kDefaultColor = 0xFFFFFFFF;
constexpr int32_t kShaderTypeCount = 6;

/// Pack four channels the way MTR's ObjModelLoader.mergeColor does:
///     r << 24 | g << 16 | b << 8 | a
///
/// Note the channel order: red lands in the most significant byte and alpha in
/// the least significant one, which is *not* the usual ARGB layout. The native
/// tests and native/tools/ColorReference.java both pin this, because getting it
/// backwards produces a colour that looks plausible but is wrong.
///
/// The shift is done in unsigned arithmetic. Shifting a signed value into the
/// sign bit is undefined behaviour before C++20, and red above 127 does exactly
/// that.
inline int32_t packArgb(int32_t r, int32_t g, int32_t b, int32_t a) {
    const uint32_t packed = (static_cast<uint32_t>(r) << 24)
            | (static_cast<uint32_t>(g) << 16)
            | (static_cast<uint32_t>(b) << 8)
            | static_cast<uint32_t>(a);
    return static_cast<int32_t>(packed);
}

/// Source mesh plus the key index it belongs to, so add_transform can recover the
/// per-shader texture handle without re-deriving the material key.
struct SourceMeshEntry {
    RawMesh mesh;
    int32_t keyIndex = 0;
};

/// One material group key, resolved far enough to build geometry.
struct KeyEntry {
    std::string key;
    int32_t shaderType = WROBJ_SHADER_CUTOUT;
    bool flipTextureV = false;
    std::string textureString;
    int32_t color = kDefaultColor;
    /// True only when a material was found and it carried a Kd.
    bool hasColor = false;
};

void freeKeyEntries(std::vector<KeyEntry>& entries) {
    entries.clear();
}

char* duplicateString(const std::string& value) {
    char* copy = static_cast<char*>(std::malloc(value.size() + 1));
    if (copy == nullptr) {
        return nullptr;
    }
    std::memcpy(copy, value.c_str(), value.size() + 1);
    return copy;
}

} // namespace

struct WrObjBuilder {
    std::vector<SourceMeshEntry> source;
    wrobj::RawModel accumulated;
    /// keyIndex * kShaderTypeCount + shaderType -> host texture handle.
    std::vector<int32_t> textureHandleByKeyAndShader;
    std::vector<int32_t> colorByKey;
    /// True when the key's material carried a Kd, i.e. when the host should force
    /// the colour rather than let the global shader colour apply.
    std::vector<uint8_t> colorSetByKey;
    /// RawModel bucket -> key index, so draw records can name their material key.
    std::vector<int32_t> bucketKeyIndex;
};

namespace {

/// Normalise a texture string the way ObjModelLoader and CustomResourceTools do, in the
/// order they do it.
///
/// ObjModelLoader passes the raw texture string through
///     texture.replace("\\\\", "/").replace("\\", "/")
/// and CustomResourceTools.formatIdentifierString then lower-cases it and replaces every
/// character outside `[a-z0-9/._-]` with an underscore. The host applies the namespace and
/// path resolution on top of the result.
///
/// The doubled-backslash pass is easy to get wrong: a single replace() does not collapse
/// `\\` to one slash unless the two-character sequence is replaced by one character, and
/// emitting two slashes instead would change the resolved path. That matters more than it
/// looks, because the resolved path is part of the material identity that decides which
/// meshes merge. native/tools/TexturePathReference.java prints the JVM's own results for
/// exactly this reason:
///
///     "textures\\body.png"    -> "textures/body.png"
///     "textures\\\\body.png"  -> "textures/body.png"
///     "textures\\\\\\body.png" -> "textures//body.png"
std::string normaliseTextureString(const std::string& value) {
    // Pass one: every "\\" becomes "/".
    std::string passOne;
    passOne.reserve(value.size());
    size_t index = 0;
    while (index < value.size()) {
        if (value[index] == '\\' && index + 1 < value.size() && value[index + 1] == '\\') {
            passOne.push_back('/');
            index += 2;
        } else {
            passOne.push_back(value[index]);
            index++;
        }
    }

    // Pass two: every remaining "\" becomes "/", and upper-case letters fold down.
    std::string out;
    out.reserve(passOne.size());
    for (const char raw : passOne) {
        if (raw == '\\') {
            out.push_back('/');
        } else if (raw >= 'A' && raw <= 'Z') {
            out.push_back(static_cast<char>(raw - 'A' + 'a'));
        } else {
            out.push_back(raw);
        }
    }
    return out;
}

/// Index MTL entries by name.
///
/// Built from a settled vector: taking `&mtl` while the vector is still being
/// filled would dangle the moment it reallocates.
std::unordered_map<std::string, size_t> indexMtlsByName(const std::vector<Mtl>& mtls) {
    std::unordered_map<std::string, size_t> byName;
    byName.reserve(mtls.size() * 2);
    for (size_t i = 0; i < mtls.size(); i++) {
        // javagl overwrites on a repeated newmtl name, so the last one wins.
        byName[mtls[i].name] = i;
    }
    return byName;
}

bool parseDocument(
        const char* objContent,
        int32_t objLength,
        const char* mtlContent,
        int32_t mtlLength,
        ObjDocument& document,
        std::vector<Mtl>& mtls
) {
    if (objContent == nullptr || objLength <= 0) {
        return false;
    }
    const std::string objText(objContent, static_cast<size_t>(objLength));
    if (!wrobj::parseObj(objText, document)) {
        return false;
    }

    if (mtlContent != nullptr && mtlLength > 0) {
        const std::string mtlText(mtlContent, static_cast<size_t>(mtlLength));
        if (!wrobj::parseMtl(mtlText, mtls)) {
            // ObjModelLoader catches per-file MTL failures and leaves the material
            // map empty. Mirror that instead of failing the whole load.
            mtls.clear();
        }
    }
    return true;
}

/// Build the per-key metadata table.
///
/// The key order is the order ObjSplitting produces: model groups in first
/// activation order, and within each group the material groups in first-use
/// order. Both wr_obj_keys_load and wr_obj_builder_create walk the document the
/// same way, so the host's handle array lines up with the builder's key indices.
void collectKeys(
        const ObjDocument& document,
        const std::vector<Mtl>& mtls,
        bool flipTextureV,
        std::vector<KeyEntry>& keys
) {
    keys.clear();

    const std::unordered_map<std::string, size_t> mtlIndexByName = indexMtlsByName(mtls);
    // ObjModelLoader branches on `materials.isEmpty()`, where `materials` is the
    // map built from every MTL file the OBJ declared.
    const bool materialsPresent = !mtls.empty();

    for (const ObjGroup& group : document.groups) {
        if (group.faceIndices.empty()) {
            continue;
        }
        ObjFragment fragment;
        wrobj::groupToFragment(document, group, fragment);
        ObjFragment renderable;
        wrobj::convertToRenderable(fragment, renderable);

        for (const ParsedFace& face : renderable.faces) {
            // javagl only creates a material group when the document carried a `usemtl`
            // statement: ObjSplitting.splitByMaterialGroups iterates
            // obj.getNumMaterialGroups(), and ObjReader never creates that group when no
            // usemtl was seen. So a face whose material key is empty belongs to no material
            // group at all and produces no key - an OBJ with no usemtl yields an empty model,
            // not a model with one unnamed bucket.
            //
            // This is not a theoretical difference: ObjModelLoader.loadModel iterates the
            // result of splitByMaterialGroups, so an empty key here would add a bucket the
            // Java renderer never produces, and the two would disagree on bucket count and on
            // the vertex buffer layout.
            if (face.materialKey.empty()) {
                continue;
            }

            bool alreadySeen = false;
            for (const KeyEntry& entry : keys) {
                if (entry.key == face.materialKey) {
                    alreadySeen = true;
                    break;
                }
            }
            if (alreadySeen) {
                continue;
            }

            const wrobj::MaterialOptions options = wrobj::splitMaterialOptions(face.materialKey);
            KeyEntry entry;
            entry.key = face.materialKey;
            entry.shaderType = wrobj::legacyShaderType(options.shaderTypeToken);
            entry.flipTextureV = options.flipTextureV || flipTextureV;
            entry.color = kDefaultColor;

            // ObjModelLoader's three-way texture/colour choice.
            const auto mtlIterator = mtlIndexByName.find(face.materialKey);
            if (!materialsPresent) {
                entry.textureString = options.materialGroupName == "_"
                        ? std::string()
                        : normaliseTextureString(options.materialGroupName);
            } else if (mtlIterator == mtlIndexByName.end()) {
                entry.textureString.clear();
            } else {
                const Mtl& mtl = mtls[mtlIterator->second];
                entry.textureString = normaliseTextureString(mtl.mapKd);
                if (mtl.hasKd) {
                    entry.color = packArgb(
                            static_cast<int32_t>(mtl.kdR * 255.0F),
                            static_cast<int32_t>(mtl.kdG * 255.0F),
                            static_cast<int32_t>(mtl.kdB * 255.0F),
                            static_cast<int32_t>(mtl.dissolve * 255.0F));
                    entry.hasColor = true;
                }
            }
            keys.push_back(std::move(entry));
        }
    }
}

/// ObjModelLoader.legacyMapping resolves the shader type from the material group
/// name, and OptimizedModel.ObjModel.loadModel then rotates every mesh by 180
/// degrees around X. Doing it once here removes one full vertex traversal per
/// placement copy.
void prepareSourceMeshes(std::vector<SourceMeshEntry>& meshes) {
    for (SourceMeshEntry& entry : meshes) {
        entry.mesh.applyRotationX(180.0F);
    }
}

/// Materialise the source meshes for one model group.
///
/// Reproduces ObjModelLoader.loadModel: split the group by material group, then
/// build one RawMesh per surviving material group, reading normal and texcoord by
/// the vertex's own index and falling back to zero.
bool buildSourceMeshes(
        const ObjDocument& document,
        const ObjGroup& group,
        const std::vector<KeyEntry>& keys,
        bool useKeyFlipForGroup,
        std::vector<SourceMeshEntry>& out
) {
    ObjFragment fragment;
    wrobj::groupToFragment(document, group, fragment);

    ObjFragment renderable;
    wrobj::convertToRenderable(fragment, renderable);

    // splitByMaterialGroups: distinct material keys in first-use order.
    //
    // A face with an empty material key is skipped for the same reason as in collectKeys:
    // javagl creates no material group without a `usemtl` statement, so there is no bucket to
    // put those faces in and the Java renderer drops them. Both walks have to agree on the
    // resulting key set and its order, because the key metadata below is matched to these
    // faces by name and the host's handle array is indexed by that order.
    std::vector<std::string> materialKeys;
    std::vector<std::vector<ParsedFace>> facesByMaterial;
    for (const ParsedFace& face : renderable.faces) {
        if (face.materialKey.empty()) {
            continue;
        }

        size_t index = 0;
        for (; index < materialKeys.size(); index++) {
            if (materialKeys[index] == face.materialKey) {
                break;
            }
        }
        if (index == materialKeys.size()) {
            materialKeys.push_back(face.materialKey);
            facesByMaterial.emplace_back();
        }
        facesByMaterial[index].push_back(face);
    }

    for (size_t materialIndex = 0; materialIndex < materialKeys.size(); materialIndex++) {
        const std::string& rawKey = materialKeys[materialIndex];
        const std::vector<ParsedFace>& faces = facesByMaterial[materialIndex];
        if (faces.empty()) {
            continue;
        }

        // Locate the key metadata. wr_obj_keys_load produced the same key set in
        // the same order, so this lookup always succeeds for a well-formed model.
        int32_t keyIndex = -1;
        for (size_t i = 0; i < keys.size(); i++) {
            if (keys[i].key == rawKey) {
                keyIndex = static_cast<int32_t>(i);
                break;
            }
        }
        if (keyIndex < 0) {
            continue;
        }
        const KeyEntry& key = keys[static_cast<size_t>(keyIndex)];

        RawMesh mesh;
        mesh.material.shaderType = key.shaderType;
        mesh.material.hasColor = true;
        mesh.material.color = key.color;

        const bool flipV = key.flipTextureV || useKeyFlipForGroup;
        mesh.vertices.reserve(renderable.vertices.size());
        for (size_t i = 0; i < renderable.vertices.size(); i++) {
            Vertex vertex;
            vertex.position = renderable.vertices[i];
            vertex.normal = i < renderable.normals.size() ? renderable.normals[i] : wrobj::Vec3f();
            const wrobj::Vec3f texCoord =
                    i < renderable.texCoords.size() ? renderable.texCoords[i] : wrobj::Vec3f();
            vertex.u = texCoord.x;
            vertex.v = flipV ? 1.0F - texCoord.y : texCoord.y;
            mesh.vertices.push_back(vertex);
        }

        for (const ParsedFace& face : faces) {
            if (face.v.size() < 3) {
                continue;
            }
            Face triangle;
            triangle.vertices = {face.v[0], face.v[1], face.v[2]};
            mesh.faces.push_back(std::move(triangle));
        }

        // ObjModelLoader calls validateVertexIndex() here; a bad index aborts the
        // entire model load in the reference implementation.
        for (const Face& face : mesh.faces) {
            for (const int32_t index : face.vertices) {
                if (index < 0 || static_cast<size_t>(index) >= mesh.vertices.size()) {
                    return false;
                }
            }
        }

        SourceMeshEntry entry;
        entry.mesh = std::move(mesh);
        entry.keyIndex = keyIndex;
        out.push_back(std::move(entry));
    }

    return true;
}

template <typename T>
T* allocateArray(size_t count) {
    if (count == 0) {
        return nullptr;
    }
    return static_cast<T*>(std::malloc(count * sizeof(T)));
}

/// Pack the interleaved vertex buffer for one mesh.
///
/// Field order and normal handling reproduce RawMesh.upload exactly: position and
/// UV as float32, then the normal normalized, scaled by 127 and truncated to
/// int8, then a padding byte because the stride is 23 before padding.
void packVertices(const std::vector<Vertex>& vertices, std::vector<uint8_t>& out) {
    out.resize(vertices.size() * WROBJ_VERTEX_STRIDE);
    uint8_t* cursor = out.data();
    for (const Vertex& vertex : vertices) {
        std::memcpy(cursor, &vertex.position.x, sizeof(float));
        std::memcpy(cursor + 4, &vertex.position.y, sizeof(float));
        std::memcpy(cursor + 8, &vertex.position.z, sizeof(float));
        std::memcpy(cursor + 12, &vertex.u, sizeof(float));
        std::memcpy(cursor + 16, &vertex.v, sizeof(float));

        wrobj::Vec3f normal = vertex.normal;
        wrobj::normalize(normal);
        cursor[20] = static_cast<uint8_t>(wrobj::floatToByte(normal.x * 127.0F));
        cursor[21] = static_cast<uint8_t>(wrobj::floatToByte(normal.y * 127.0F));
        cursor[22] = static_cast<uint8_t>(wrobj::floatToByte(normal.z * 127.0F));
        cursor[23] = 0;
        cursor += WROBJ_VERTEX_STRIDE;
    }
}

} // namespace

// ---------------------------------------------------------------------------
// Stage 1 — key discovery
// ---------------------------------------------------------------------------

extern "C" WrObjKeyList* wr_obj_keys_load(
        const char* objContent,
        int32_t objLength,
        const char* mtlContent,
        int32_t mtlLength,
        int32_t flipTextureV
) {
    ObjDocument document;
    std::vector<Mtl> mtls;
    if (!parseDocument(objContent, objLength, mtlContent, mtlLength, document, mtls)) {
        return nullptr;
    }

    std::vector<KeyEntry> keys;
    collectKeys(document, mtls, flipTextureV != 0, keys);
    if (keys.empty()) {
        return nullptr;
    }

    auto* list = static_cast<WrObjKeyList*>(std::calloc(1, sizeof(WrObjKeyList)));
    if (list == nullptr) {
        return nullptr;
    }
    auto* entries = static_cast<WrObjKeyInfo*>(std::calloc(keys.size(), sizeof(WrObjKeyInfo)));
    if (entries == nullptr) {
        std::free(list);
        return nullptr;
    }
    for (size_t i = 0; i < keys.size(); i++) {
        entries[i].key = duplicateString(keys[i].key);
        entries[i].shaderType = keys[i].shaderType;
        entries[i].flipTextureV = keys[i].flipTextureV ? 1 : 0;
        entries[i].textureString = duplicateString(keys[i].textureString);
        entries[i].color = keys[i].color;
        entries[i].hasColor = keys[i].hasColor ? 1 : 0;
    }
    list->entries = entries;
    list->count = static_cast<int32_t>(keys.size());
    return list;
}

extern "C" void wr_obj_keys_free(WrObjKeyList* list) {
    if (list == nullptr) {
        return;
    }
    if (list->entries != nullptr) {
        for (int32_t i = 0; i < list->count; i++) {
            std::free(const_cast<char*>(list->entries[i].key));
            std::free(const_cast<char*>(list->entries[i].textureString));
        }
        std::free(const_cast<WrObjKeyInfo*>(list->entries));
    }
    std::free(list);
}

// ---------------------------------------------------------------------------
// Stage 2 — placement capture
// ---------------------------------------------------------------------------

extern "C" WrObjBuilder* wr_obj_builder_create(
        const char* objContent,
        int32_t objLength,
        const char* mtlContent,
        int32_t mtlLength,
        const int32_t* keyTextureHandles,
        const int32_t* keyColorSet,
        int32_t keyCount,
        int32_t materialGroupTextures,
        int32_t flipTextureV
) {
    ObjDocument document;
    std::vector<Mtl> mtls;
    if (!parseDocument(objContent, objLength, mtlContent, mtlLength, document, mtls)) {
        return nullptr;
    }    if (materialGroupTextures == 0) {
        // The host asked for the "no materials" branch even though an MTL was
        // supplied; drop it so collectKeys takes the group-name path.
        mtls.clear();
    }

    std::vector<KeyEntry> keys;
    collectKeys(document, mtls, flipTextureV != 0, keys);
    if (keys.empty()) {
        return nullptr;
    }

    auto* builder = new (std::nothrow) WrObjBuilder();
    if (builder == nullptr) {
        return nullptr;
    }

    builder->textureHandleByKeyAndShader.assign(
            keys.size() * kShaderTypeCount, -1);
    builder->colorByKey.reserve(keys.size());
    builder->colorSetByKey.reserve(keys.size());
    for (size_t i = 0; i < keys.size(); i++) {
        builder->colorByKey.push_back(keys[i].color);
        // The host reports whether the colour is authoritative. Falling back to the
        // key's own state keeps a host that omits the array correct.
        const bool hostSaysSet = keyColorSet != nullptr && i < static_cast<size_t>(keyCount)
                ? keyColorSet[i] != 0
                : keys[i].hasColor;
        builder->colorSetByKey.push_back(hostSaysSet || keys[i].hasColor ? 1 : 0);

        const int32_t handle = i < static_cast<size_t>(keyCount) && keyTextureHandles != nullptr
                ? keyTextureHandles[i]
                : -1;
        for (int32_t shader = 0; shader < kShaderTypeCount; shader++) {
            builder->textureHandleByKeyAndShader[i * kShaderTypeCount + static_cast<size_t>(shader)] = handle;
        }
    }

    for (const ObjGroup& group : document.groups) {
        if (group.faceIndices.empty()) {
            continue;
        }
        std::vector<SourceMeshEntry> meshes;
        if (!buildSourceMeshes(document, group, keys, false, meshes)) {
            delete builder;
            return nullptr;
        }
        for (SourceMeshEntry& entry : meshes) {
            builder->source.push_back(std::move(entry));
        }
    }

    prepareSourceMeshes(builder->source);
    return builder;
}

extern "C" void wr_obj_builder_free(WrObjBuilder* builder) {
    delete builder;
}

extern "C" void wr_obj_builder_apply_translation(WrObjBuilder* builder, double x, double y, double z) {
    if (builder == nullptr) {
        return;
    }
    for (SourceMeshEntry& entry : builder->source) {
        entry.mesh.applyTranslation(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
    }
}

extern "C" void wr_obj_builder_apply_rotation(WrObjBuilder* builder, double xDegrees, double yDegrees, double zDegrees) {
    if (builder == nullptr) {
        return;
    }
    // ObjModel.applyRotation runs X, then Y, then Z, each in its own vertex pass.
    for (SourceMeshEntry& entry : builder->source) {
        entry.mesh.applyRotationX(static_cast<float>(xDegrees));
        entry.mesh.applyRotationY(static_cast<float>(yDegrees));
        entry.mesh.applyRotationZ(static_cast<float>(zDegrees));
    }
}

extern "C" void wr_obj_builder_apply_scale(WrObjBuilder* builder, double x, double y, double z) {
    if (builder == nullptr) {
        return;
    }
    for (SourceMeshEntry& entry : builder->source) {
        entry.mesh.applyScale(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
    }
}

extern "C" void wr_obj_builder_apply_mirror(WrObjBuilder* builder, int32_t x, int32_t y, int32_t z) {
    if (builder == nullptr) {
        return;
    }
    for (SourceMeshEntry& entry : builder->source) {
        entry.mesh.applyMirror(x != 0, y != 0, z != 0, x != 0, y != 0, z != 0);
    }
}

extern "C" void wr_obj_builder_apply_transform(
        WrObjBuilder* builder,
        int32_t operation,
        double a,
        double b,
        double c
) {
    switch (operation) {
        case 0:
            wr_obj_builder_apply_translation(builder, a, b, c);
            break;
        case 1:
            wr_obj_builder_apply_rotation(builder, a, b, c);
            break;
        case 2:
            wr_obj_builder_apply_scale(builder, a, b, c);
            break;
        case 3:
            wr_obj_builder_apply_mirror(builder, a != 0.0 ? 1 : 0, b != 0.0 ? 1 : 0, c != 0.0 ? 1 : 0);
            break;
        default:
            break;
    }
}

extern "C" void wr_obj_builder_get_bounds(
        const WrObjBuilder* builder,
        float* minX, float* minY, float* minZ,
        float* maxX, float* maxY, float* maxZ
) {
    if (builder == nullptr) {
        return;
    }
    float bounds[6] = {
        std::numeric_limits<float>::max(), std::numeric_limits<float>::max(),
        std::numeric_limits<float>::max(), -std::numeric_limits<float>::max(),
        -std::numeric_limits<float>::max(), -std::numeric_limits<float>::max()
    };
    for (const SourceMeshEntry& entry : builder->source) {
        for (const Vertex& vertex : entry.mesh.vertices) {
            bounds[0] = std::min(bounds[0], vertex.position.x);
            bounds[1] = std::min(bounds[1], vertex.position.y);
            bounds[2] = std::min(bounds[2], vertex.position.z);
            bounds[3] = std::max(bounds[3], vertex.position.x);
            bounds[4] = std::max(bounds[4], vertex.position.y);
            bounds[5] = std::max(bounds[5], vertex.position.z);
        }
    }
    if (minX != nullptr) { *minX = bounds[0]; }
    if (minY != nullptr) { *minY = bounds[1]; }
    if (minZ != nullptr) { *minZ = bounds[2]; }
    if (maxX != nullptr) { *maxX = bounds[3]; }
    if (maxY != nullptr) { *maxY = bounds[4]; }
    if (maxZ != nullptr) { *maxZ = bounds[5]; }
}

extern "C" void wr_obj_builder_add_transform(
        WrObjBuilder* builder,
        int32_t shaderType,
        double x,
        double y,
        double z,
        int32_t flipped
) {
    if (builder == nullptr) {
        return;
    }

    const float fx = static_cast<float>(x);
    const float fy = static_cast<float>(y);
    const float fz = static_cast<float>(z);

    for (const SourceMeshEntry& entry : builder->source) {
        // RawMesh's copy constructor: a CUTOUT source adopts the requested shader
        // type, every other source keeps its own MaterialProperties untouched.
        RawMesh placed = entry.mesh;
        if (placed.material.shaderType == WROBJ_SHADER_CUTOUT) {
            placed.material.shaderType = shaderType;
        }

        // MaterialProperties equality includes the texture, and the texture can
        // differ per shader type, so carry the resolved handle into the merge key.
        const int32_t effectiveShader = placed.material.shaderType;
        const size_t index = static_cast<size_t>(entry.keyIndex) * kShaderTypeCount
                + static_cast<size_t>(effectiveShader);
        const int32_t handle = index < builder->textureHandleByKeyAndShader.size()
                ? builder->textureHandleByKeyAndShader[index]
                : -1;
        placed.material.textureId = handle;
        // MaterialProperties equals() includes the vertex attribute state, whose
        // colour is null when the MTL carried no Kd. Keeping that distinction in the
        // merge key is what stops two keys with different colour sources collapsing.
        const bool colorIsSet = builder->colorSetByKey[static_cast<size_t>(entry.keyIndex)] != 0;
        placed.material.hasColor = colorIsSet;
        placed.material.color = colorIsSet ? builder->colorByKey[static_cast<size_t>(entry.keyIndex)] : 0;
        placed.material.translucent = effectiveShader == WROBJ_SHADER_TRANSLUCENT
                || effectiveShader == WROBJ_SHADER_TRANSLUCENT_BRIGHT
                || effectiveShader == WROBJ_SHADER_TRANSLUCENT_GLOWING;
        placed.material.cutoutHack = effectiveShader == WROBJ_SHADER_CUTOUT_GLOWING;
        placed.material.writeDepthBuf = effectiveShader != WROBJ_SHADER_TRANSLUCENT_GLOWING;

        placed.applyTranslation(fx, fy, fz);
        // ObjModel.addTransformation rotates after translating, so the copy spins
        // about its own origin at the placed position.
        if (flipped != 0) {
            placed.applyRotationY(180.0F);
        }

        builder->accumulated.append(placed);

        // RawModel.append creates a bucket on first sight of a material. Record
        // which key each bucket came from so draw records can name their material.
        while (builder->bucketKeyIndex.size() < builder->accumulated.meshes.size()) {
            builder->bucketKeyIndex.push_back(entry.keyIndex);
        }
    }
}

// ---------------------------------------------------------------------------
// Stage 3 — geometry upload
// ---------------------------------------------------------------------------

extern "C" WrObjResult* wr_obj_builder_build(WrObjBuilder* builder) {
    if (builder == nullptr) {
        return nullptr;
    }

    // ObjModel.fromObjModels: generateNormals then distinct, per accumulated
    // material bucket. RawMesh.upload calls distinct() once more; it is a no-op
    // here because the mesh was just de-duplicated.
    //
    // generateNormals and distinct rewrite the mesh in place, so they run over a
    // snapshot of the accumulated model rather than over the builder's own state.
    // That is what makes a builder re-buildable, and it has to be: MTR builds the
    // same wrapper list several times - the doors-open model, the doors-closed
    // model, and the per-position door models - and each build must see the same
    // accumulated state. The Java path has the same property, because
    // OptimizedModel.fromObjModels reads the ObjModels' rawModels without
    // consuming them. Running the stages in place made a second build throw on
    // the Java side, which took every vehicle's doors-closed model down with it.
    wrobj::RawModel snapshot;
    snapshot.meshes.reserve(builder->accumulated.meshes.size());
    for (const wrobj::RawMesh& mesh : builder->accumulated.meshes) {
        snapshot.meshes.push_back(mesh);
    }
    snapshot.generateNormals();
    snapshot.distinct();
    std::vector<uint8_t> vertexBytes;
    std::vector<uint32_t> indexBytes;
    std::vector<WrObjResultDraw> draws;

    for (size_t bucket = 0; bucket < snapshot.meshes.size(); bucket++) {
        const RawMesh& mesh = snapshot.meshes[bucket];
        if (mesh.faces.empty() || mesh.vertices.empty()) {
            continue;
        }

        WrObjResultDraw draw{};
        draw.shaderType = mesh.material.shaderType;
        draw.color = mesh.material.color;
        draw.hasColor = mesh.material.hasColor ? 1 : 0;
        draw.textureHandle = mesh.material.textureId;
        draw.keyIndex = bucket < builder->bucketKeyIndex.size()
                ? builder->bucketKeyIndex[bucket]
                : -1;
        draw.vertexOffset = static_cast<int32_t>(vertexBytes.size() / WROBJ_VERTEX_STRIDE);
        draw.vertexCount = static_cast<int32_t>(mesh.vertices.size());
        draw.indexOffset = static_cast<int32_t>(indexBytes.size());
        draw.indexCount = static_cast<int32_t>(mesh.faces.size() * 3);
        draws.push_back(draw);

        std::vector<uint8_t> packed;
        packVertices(mesh.vertices, packed);
        vertexBytes.insert(vertexBytes.end(), packed.begin(), packed.end());

        for (const Face& face : mesh.faces) {
            for (size_t j = 0; j < 3; j++) {
                const int32_t index = j < face.vertices.size() ? face.vertices[j] : 0;
                indexBytes.push_back(static_cast<uint32_t>(index + draw.vertexOffset));
            }
        }
    }

    if (draws.empty()) {
        return nullptr;
    }

    auto* result = static_cast<WrObjResult*>(std::calloc(1, sizeof(WrObjResult)));
    if (result == nullptr) {
        return nullptr;
    }

    result->vertexDataLength = static_cast<int32_t>(vertexBytes.size());
    result->vertexData = allocateArray<uint8_t>(vertexBytes.size());
    if (result->vertexData != nullptr && !vertexBytes.empty()) {
        std::memcpy(result->vertexData, vertexBytes.data(), vertexBytes.size());
    }

    result->indexDataLength = static_cast<int32_t>(indexBytes.size() * sizeof(uint32_t));
    result->indexData = allocateArray<uint32_t>(indexBytes.size());
    if (result->indexData != nullptr && !indexBytes.empty()) {
        std::memcpy(result->indexData, indexBytes.data(), indexBytes.size() * sizeof(uint32_t));
    }

    result->drawCount = static_cast<int32_t>(draws.size());
    result->drawData = allocateArray<WrObjResultDraw>(draws.size());
    if (result->drawData != nullptr) {
        std::memcpy(result->drawData, draws.data(), draws.size() * sizeof(WrObjResultDraw));
    }

    for (const RawMesh& mesh : snapshot.meshes) {
        result->totalVertexCount += static_cast<int64_t>(mesh.vertices.size());
        result->totalFaceCount += static_cast<int64_t>(mesh.faces.size());
    }

    // Order-of-magnitude estimate of the Java heap the reference pipeline would
    // have churned through to reach the same buffers: one Vertex plus a pair of
    // Vector3f per final vertex, one Face plus its int[] per final triangle, and a
    // map entry per de-duplication probe. Reported so the saving is measurable
    // rather than asserted.
    result->estimatedJavaHeapBytes = result->totalVertexCount * 96 + result->totalFaceCount * 48;

    return result;
}

extern "C" void wr_obj_result_free(WrObjResult* result) {
    if (result == nullptr) {
        return;
    }
    std::free(result->vertexData);
    std::free(result->indexData);
    std::free(result->drawData);
    std::free(result);
}

extern "C" int32_t wr_obj_abi_version(void) {
    return WROBJ_ABI_VERSION;
}

extern "C" const char* wr_obj_version_string(void) {
    return "wrobj 1.0.0 (MTR OBJ pipeline, native)";
}
