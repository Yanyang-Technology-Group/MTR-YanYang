// SPDX-License-Identifier: MIT
//
// wrobj_pipeline.cpp — group extraction, renderable conversion and RawMesh
// construction, i.e. everything ObjSplitting / ObjUtils / ObjModelLoader do
// before the first RawMesh is handed to RawModel.
//
// The vertex ordering produced here is what the whole equivalence argument rests
// on. It is not an incidental detail: convertToRenderable decides the order in
// which vertices are appended, RawMesh::distinct then reorders them by first use
// in face order, and the resulting vertex buffer is uploaded verbatim. Any
// reordering here changes the GPU buffer even when the rendered image is
// identical, and the equivalence harness compares buffers byte for byte.

#include "wrobj/wrobj_model.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace wrobj {

// ---------------------------------------------------------------------------
// ObjUtils.groupToObj / ObjSplitting
// ---------------------------------------------------------------------------

void groupToFragment(const ObjDocument& document, const ObjGroup& group, ObjFragment& out) {
    out = ObjFragment();

    std::vector<int32_t> vertexMap(document.vertices.size(), -1);
    std::vector<int32_t> texCoordMap(document.texCoords.size(), -1);
    std::vector<int32_t> normalMap(document.normals.size(), -1);

    for (const int32_t faceIndex : group.faceIndices) {
        if (faceIndex < 0 || static_cast<size_t>(faceIndex) >= document.faces.size()) {
            continue;
        }
        const ParsedFace& sourceFace = document.faces[static_cast<size_t>(faceIndex)];
        ParsedFace resultFace;
        // The material group has to travel with the face: ObjSplitting splits the
        // extracted fragment by material group next, and javagl recovers that from
        // the per-face "activated material group" map rather than from a field.
        resultFace.materialKey = sourceFace.materialKey;
        resultFace.v.resize(sourceFace.v.size());

        // Vertices first, then texcoords, then normals — the reference
        // implementation uses three independent counters and appends in that
        // order, so the compacted lists must be built in that order too.
        for (size_t i = 0; i < sourceFace.v.size(); i++) {
            const int32_t vertexIndex = sourceFace.v[i];
            if (vertexIndex < 0 || static_cast<size_t>(vertexIndex) >= document.vertices.size()) {
                continue;
            }
            if (vertexMap[static_cast<size_t>(vertexIndex)] == -1) {
                vertexMap[static_cast<size_t>(vertexIndex)] = static_cast<int32_t>(out.vertices.size());
                out.vertices.push_back(document.vertices[static_cast<size_t>(vertexIndex)]);
            }
            resultFace.v[i] = vertexMap[static_cast<size_t>(vertexIndex)];
        }

        if (!sourceFace.vt.empty()) {
            resultFace.vt.resize(sourceFace.vt.size());
            for (size_t i = 0; i < sourceFace.vt.size(); i++) {
                const int32_t texCoordIndex = sourceFace.vt[i];
                if (texCoordIndex < 0 || static_cast<size_t>(texCoordIndex) >= document.texCoords.size()) {
                    continue;
                }
                if (texCoordMap[static_cast<size_t>(texCoordIndex)] == -1) {
                    texCoordMap[static_cast<size_t>(texCoordIndex)] = static_cast<int32_t>(out.texCoords.size());
                    out.texCoords.push_back(document.texCoords[static_cast<size_t>(texCoordIndex)]);
                }
                resultFace.vt[i] = texCoordMap[static_cast<size_t>(texCoordIndex)];
            }
        }

        if (!sourceFace.vn.empty()) {
            resultFace.vn.resize(sourceFace.vn.size());
            for (size_t i = 0; i < sourceFace.vn.size(); i++) {
                const int32_t normalIndex = sourceFace.vn[i];
                if (normalIndex < 0 || static_cast<size_t>(normalIndex) >= document.normals.size()) {
                    continue;
                }
                if (normalMap[static_cast<size_t>(normalIndex)] == -1) {
                    normalMap[static_cast<size_t>(normalIndex)] = static_cast<int32_t>(out.normals.size());
                    out.normals.push_back(document.normals[static_cast<size_t>(normalIndex)]);
                }
                resultFace.vn[i] = normalMap[static_cast<size_t>(normalIndex)];
            }
        }

        out.faces.push_back(std::move(resultFace));
    }
}

// ---------------------------------------------------------------------------
// ObjUtils.convertToRenderable
// ---------------------------------------------------------------------------

namespace {

/// ObjUtils.makePropertiesUnique for one property (texcoords or normals).
///
/// The reference implementation keeps a single int[] slot per *vertex*, so the
/// first face that touches a vertex pins its property index and every later face
/// with a different index for that vertex forks a copy of the vertex. Getting
/// this wrong changes both the vertex count and the vertex order.
void makePropertiesUnique(ObjFragment& fragment, bool useTexCoords) {
    std::vector<int32_t> usedPropertyIndices(fragment.vertices.size(), -1);

    for (ParsedFace& face : fragment.faces) {
        const std::vector<int32_t>& propertyIndices = useTexCoords ? face.vt : face.vn;
        if (propertyIndices.empty()) {
            continue;
        }

        bool extended = false;
        std::vector<int32_t> extendedVertices = face.v;
        for (size_t j = 0; j < extendedVertices.size() && j < propertyIndices.size(); j++) {
            const int32_t vertexIndex = extendedVertices[j];
            if (vertexIndex < 0 || static_cast<size_t>(vertexIndex) >= usedPropertyIndices.size()) {
                continue;
            }
            const int32_t propertyIndex = propertyIndices[j];
            const int32_t previous = usedPropertyIndices[static_cast<size_t>(vertexIndex)];
            if (previous != -1 && previous != propertyIndex) {
                // Fork the vertex. javagl appends the *same* FloatTuple, and the
                // new index is input.getNumVertices() + extendedVertices.size(),
                // i.e. the running total length of the vertex list.
                const int32_t extendedVertexIndex = static_cast<int32_t>(fragment.vertices.size());
                fragment.vertices.push_back(fragment.vertices[static_cast<size_t>(vertexIndex)]);
                usedPropertyIndices.push_back(propertyIndex);
                extendedVertices[j] = extendedVertexIndex;
                extended = true;
            } else {
                usedPropertyIndices[static_cast<size_t>(vertexIndex)] = propertyIndex;
            }
        }
        if (extended) {
            face.v = std::move(extendedVertices);
        }
    }
}

} // namespace

void convertToRenderable(const ObjFragment& input, ObjFragment& out) {
    out = ObjFragment();
    out.faces.reserve(input.faces.size());

    // --- ObjUtils.triangulate ------------------------------------------------
    // Faces with exactly three vertices pass through; everything else is fanned
    // from vertex 0 using the reference implementation's index pattern.
    for (const ParsedFace& face : input.faces) {
        const size_t count = face.v.size();
        if (count == 3) {
            out.faces.push_back(face);
        } else {
            for (size_t j = 0; j + 2 < count; j++) {
                ParsedFace triangle;
                triangle.materialKey = face.materialKey;
                triangle.v = {face.v[0], face.v[j + 1], face.v[j + 2]};
                if (!face.vt.empty()) {
                    triangle.vt = {face.vt[0], face.vt[j + 1], face.vt[j + 2]};
                }
                if (!face.vn.empty()) {
                    triangle.vn = {face.vn[0], face.vn[j + 1], face.vn[j + 2]};
                }
                out.faces.push_back(std::move(triangle));
            }
        }
    }

    out.vertices = input.vertices;
    out.texCoords = input.texCoords;
    out.normals = input.normals;

    // --- makeTexCoordsUnique then makeNormalsUnique --------------------------
    makePropertiesUnique(out, true);
    makePropertiesUnique(out, false);

    // --- ObjUtils.makeVertexIndexed -----------------------------------------
    // Collapse the per-vertex property arrays into per-vertex valued lists. The
    // per-vertex slot arrays are only assigned for vertices a face actually
    // references, and unassigned slots read back as index 0, which is what Java's
    // zero-initialised int[] does.
    const size_t vertexCount = out.vertices.size();
    std::vector<int32_t> texCoordIndicesForVertices(vertexCount, 0);
    std::vector<int32_t> normalIndicesForVertices(vertexCount, 0);
    bool foundTexCoords = false;
    bool foundNormals = false;

    for (const ParsedFace& face : out.faces) {
        for (size_t j = 0; j < face.v.size(); j++) {
            const int32_t vertexIndex = face.v[j];
            if (vertexIndex < 0 || static_cast<size_t>(vertexIndex) >= vertexCount) {
                continue;
            }
            if (!face.vt.empty() && j < face.vt.size()) {
                texCoordIndicesForVertices[static_cast<size_t>(vertexIndex)] = face.vt[j];
                foundTexCoords = true;
            }
            if (!face.vn.empty() && j < face.vn.size()) {
                normalIndicesForVertices[static_cast<size_t>(vertexIndex)] = face.vn[j];
                foundNormals = true;
            }
        }
    }

    if (foundTexCoords) {
        std::vector<Vec3f> perVertexTexCoords;
        perVertexTexCoords.reserve(vertexCount);
        for (size_t i = 0; i < vertexCount; i++) {
            const int32_t index = texCoordIndicesForVertices[i];
            if (index >= 0 && static_cast<size_t>(index) < out.texCoords.size()) {
                perVertexTexCoords.push_back(out.texCoords[static_cast<size_t>(index)]);
            } else {
                perVertexTexCoords.emplace_back();
            }
        }
        out.texCoords = std::move(perVertexTexCoords);
    } else {
        out.texCoords.clear();
    }

    if (foundNormals) {
        std::vector<Vec3f> perVertexNormals;
        perVertexNormals.reserve(vertexCount);
        for (size_t i = 0; i < vertexCount; i++) {
            const int32_t index = normalIndicesForVertices[i];
            if (index >= 0 && static_cast<size_t>(index) < out.normals.size()) {
                perVertexNormals.push_back(out.normals[static_cast<size_t>(index)]);
            } else {
                perVertexNormals.emplace_back();
            }
        }
        out.normals = std::move(perVertexNormals);
    } else {
        out.normals.clear();
    }

    // Every face is now vertex-indexed: the texcoord and normal index of each
    // corner equals its vertex index.
    for (ParsedFace& face : out.faces) {
        if (!face.vt.empty()) {
            face.vt.resize(face.v.size());
            for (size_t j = 0; j < face.v.size(); j++) {
                face.vt[j] = face.v[j];
            }
        }
        if (!face.vn.empty()) {
            face.vn.resize(face.v.size());
            for (size_t j = 0; j < face.v.size(); j++) {
                face.vn[j] = face.v[j];
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ObjModelLoader helpers
// ---------------------------------------------------------------------------

MaterialOptions splitMaterialOptions(const std::string& source) {
    MaterialOptions options;
    const size_t hashIndex = source.find('#');
    options.materialGroupName = hashIndex == std::string::npos ? source : source.substr(0, hashIndex);
    if (hashIndex == std::string::npos) {
        return options;
    }

    const std::string minor = source.substr(hashIndex + 1);
    size_t cursor = 0;
    bool hashTokenTaken = false;
    while (cursor <= minor.size()) {
        const size_t comma = minor.find(',', cursor);
        const std::string token = comma == std::string::npos
                ? minor.substr(cursor)
                : minor.substr(cursor, comma - cursor);
        const size_t equals = token.find('=');
        if (equals != std::string::npos && equals > 0) {
            const std::string key = token.substr(0, equals);
            const std::string value = token.substr(equals + 1);
            if (key == "#") {
                options.shaderTypeToken = value;
                hashTokenTaken = true;
            } else if (key.size() == 5 && std::equal(key.begin(), key.end(), "flipv",
                    [](char a, char b) { return std::tolower(static_cast<unsigned char>(a)) == b; })) {
                options.flipTextureV = value == "1";
            }
        } else if (!hashTokenTaken) {
            // Java uses result.containsKey("#") to decide whether the bare token
            // is the shader type or an implicit "1" flag.
            options.shaderTypeToken = token;
            hashTokenTaken = true;
        }
        if (comma == std::string::npos) {
            break;
        }
        cursor = comma + 1;
    }

    return options;
}

int32_t legacyShaderType(const std::string& token) {
    std::string lowered(token);
    std::transform(lowered.begin(), lowered.end(), lowered.begin(),
            [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

    if (lowered == "exteriortranslucent") {
        return 1; // TRANSLUCENT
    }
    if (lowered == "light") {
        return 4; // CUTOUT_GLOWING
    }
    if (lowered == "always_on_light" || lowered == "lighttranslucent") {
        return 5; // TRANSLUCENT_GLOWING
    }
    if (lowered == "interior") {
        return 2; // CUTOUT_BRIGHT
    }
    if (lowered == "interior_translucent" || lowered == "interiortranslucent") {
        return 3; // TRANSLUCENT_BRIGHT
    }
    return 0; // CUTOUT
}

} // namespace wrobj
