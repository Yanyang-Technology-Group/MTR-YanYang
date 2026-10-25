// SPDX-License-Identifier: MIT
//
// golden_format.hpp — the interchange format the Java reference produces and the
// native pipeline is verified against.
//
// A golden file is a self-describing binary dump of one model instance build:
//
//   magic          "WROBJGLD"      8 bytes
//   version        int32           3 (little endian throughout)
//   keyCount       int32
//   drawCount      int32
//   vertexBytes    int64
//   indexBytes     int64
//   flags          int32           bit 0 flipTextureV, bit 1 materialGroupTextures
//   reserved       int32
//   keys           keyCount records:
//                    shaderType  int32
//                    color       int32
//                    textureLen  int32, textureUtf8 textureLen bytes
//   draws          drawCount records:
//                    shaderType  int32
//                    color       int32
//                    keyIndex    int32
//                    vertexOffset int32
//                    vertexCount  int32
//                    indexOffset  int32
//                    indexCount   int32
//   vertexData     vertexBytes bytes   interleaved, stride 24
//   indexData      indexBytes bytes    uint32
//   checksum       uint64              FNV-1a of vertexData then indexData
//
// The layout exists so a mismatch can be reported as "bucket 4, vertex 271,
// byte 20" rather than "the buffers differ".

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace golden {

struct Key {
    int32_t shaderType = 0;
    int32_t color = 0;
    std::string texture;
};

struct Draw {
    int32_t shaderType = 0;
    int32_t color = 0;
    int32_t keyIndex = 0;
    int32_t vertexOffset = 0;
    int32_t vertexCount = 0;
    int32_t indexOffset = 0;
    int32_t indexCount = 0;
};

struct File {
    int32_t flags = 0;
    std::vector<Key> keys;
    std::vector<Draw> draws;
    std::vector<uint8_t> vertexData;
    std::vector<uint8_t> indexData;
    uint64_t checksum = 0;
};

/// Read a golden file. Returns false and fills `error` when the file is missing,
/// truncated, or does not start with the expected magic and version.
bool read(const std::string& path, File& out, std::string& error);

/// Write a golden file. Used by the native self-test to round-trip the format.
bool write(const std::string& path, const File& file, std::string& error);

/// FNV-1a over the vertex buffer followed by the index buffer, which is how the
/// mod's own benchmark reports model identity.
uint64_t computeChecksum(const std::vector<uint8_t>& vertexData, const std::vector<uint8_t>& indexData);

/// Compare two golden files and describe the first difference precisely.
/// Returns an empty string when they are equivalent.
std::string diff(const File& expected, const File& actual);

/// Describe one interleaved vertex as a human-readable triple of fields, used in
/// diff output.
std::string describeVertex(const std::vector<uint8_t>& data, int32_t vertexIndex);

} // namespace golden
