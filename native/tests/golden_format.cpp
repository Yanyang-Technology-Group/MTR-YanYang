// SPDX-License-Identifier: MIT
//
// golden_format.cpp — reader, writer and differ for the golden interchange
// format described in golden_format.hpp.

#include "golden_format.hpp"

#include <cstdio>
#include <cstring>
#include <sstream>

namespace golden {
namespace {

constexpr char kMagic[8] = {'W', 'R', 'O', 'B', 'J', 'G', 'L', 'D'};
constexpr int32_t kVersion = 3;

template <typename T>
bool readPod(std::FILE* file, T& out) {
    return std::fread(&out, sizeof(T), 1, file) == 1;
}

template <typename T>
bool writePod(std::FILE* file, const T& value) {
    return std::fwrite(&value, sizeof(T), 1, file) == 1;
}

bool readString(std::FILE* file, std::string& out) {
    int32_t length = 0;
    if (!readPod(file, length) || length < 0 || length > (1 << 20)) {
        return false;
    }
    out.resize(static_cast<size_t>(length));
    if (length == 0) {
        return true;
    }
    return std::fread(out.data(), 1, static_cast<size_t>(length), file) == static_cast<size_t>(length);
}

bool writeString(std::FILE* file, const std::string& value) {
    const int32_t length = static_cast<int32_t>(value.size());
    return writePod(file, length)
            && (length == 0 || std::fwrite(value.data(), 1, value.size(), file) == value.size());
}

std::string formatFloatBits(uint32_t bits) {
    float value = 0.0F;
    std::memcpy(&value, &bits, sizeof(value));
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%.9g", static_cast<double>(value));
    return std::string(buffer);
}

} // namespace

uint64_t computeChecksum(const std::vector<uint8_t>& vertexData, const std::vector<uint8_t>& indexData) {
    uint64_t hash = 0xCBF29CE484222325ULL;
    for (const uint8_t byte : vertexData) {
        hash ^= byte;
        hash *= 0x100000001B3ULL;
    }
    for (const uint8_t byte : indexData) {
        hash ^= byte;
        hash *= 0x100000001B3ULL;
    }
    return hash;
}

bool read(const std::string& path, File& out, std::string& error) {
    std::FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) {
        error = "cannot open " + path;
        return false;
    }

    char magic[8] = {};
    if (std::fread(magic, 1, sizeof(magic), file) != sizeof(magic)
            || std::memcmp(magic, kMagic, sizeof(kMagic)) != 0) {
        error = path + ": not a golden file";
        std::fclose(file);
        return false;
    }

    int32_t version = 0;
    if (!readPod(file, version) || version != kVersion) {
        error = path + ": unsupported golden version";
        std::fclose(file);
        return false;
    }

    int32_t keyCount = 0;
    int32_t drawCount = 0;
    int64_t vertexBytes = 0;
    int64_t indexBytes = 0;
    int32_t reserved = 0;
    if (!readPod(file, keyCount) || !readPod(file, drawCount)
            || !readPod(file, vertexBytes) || !readPod(file, indexBytes)
            || !readPod(file, out.flags) || !readPod(file, reserved)
            || keyCount < 0 || drawCount < 0 || vertexBytes < 0 || indexBytes < 0) {
        error = path + ": truncated header";
        std::fclose(file);
        return false;
    }

    out.keys.clear();
    for (int32_t i = 0; i < keyCount; i++) {
        Key key;
        if (!readPod(file, key.shaderType) || !readPod(file, key.color)
                || !readString(file, key.texture)) {
            error = path + ": truncated key table";
            std::fclose(file);
            return false;
        }
        out.keys.push_back(std::move(key));
    }

    out.draws.clear();
    for (int32_t i = 0; i < drawCount; i++) {
        Draw draw;
        if (!readPod(file, draw.shaderType) || !readPod(file, draw.color)
                || !readPod(file, draw.keyIndex) || !readPod(file, draw.vertexOffset)
                || !readPod(file, draw.vertexCount) || !readPod(file, draw.indexOffset)
                || !readPod(file, draw.indexCount)) {
            error = path + ": truncated draw table";
            std::fclose(file);
            return false;
        }
        out.draws.push_back(draw);
    }

    out.vertexData.assign(static_cast<size_t>(vertexBytes), 0);
    if (vertexBytes > 0
            && std::fread(out.vertexData.data(), 1, static_cast<size_t>(vertexBytes), file)
                    != static_cast<size_t>(vertexBytes)) {
        error = path + ": truncated vertex buffer";
        std::fclose(file);
        return false;
    }

    out.indexData.assign(static_cast<size_t>(indexBytes), 0);
    if (indexBytes > 0
            && std::fread(out.indexData.data(), 1, static_cast<size_t>(indexBytes), file)
                    != static_cast<size_t>(indexBytes)) {
        error = path + ": truncated index buffer";
        std::fclose(file);
        return false;
    }

    if (!readPod(file, out.checksum)) {
        error = path + ": missing checksum";
        std::fclose(file);
        return false;
    }

    std::fclose(file);
    return true;
}

bool write(const std::string& path, const File& file, std::string& error) {
    std::FILE* handle = std::fopen(path.c_str(), "wb");
    if (handle == nullptr) {
        error = "cannot create " + path;
        return false;
    }

    bool ok = std::fwrite(kMagic, 1, sizeof(kMagic), handle) == sizeof(kMagic);
    ok = ok && writePod(handle, kVersion);
    ok = ok && writePod(handle, static_cast<int32_t>(file.keys.size()));
    ok = ok && writePod(handle, static_cast<int32_t>(file.draws.size()));
    ok = ok && writePod(handle, static_cast<int64_t>(file.vertexData.size()));
    ok = ok && writePod(handle, static_cast<int64_t>(file.indexData.size()));
    ok = ok && writePod(handle, file.flags);
    ok = ok && writePod(handle, static_cast<int32_t>(0));

    for (const Key& key : file.keys) {
        ok = ok && writePod(handle, key.shaderType);
        ok = ok && writePod(handle, key.color);
        ok = ok && writeString(handle, key.texture);
    }
    for (const Draw& draw : file.draws) {
        ok = ok && writePod(handle, draw.shaderType);
        ok = ok && writePod(handle, draw.color);
        ok = ok && writePod(handle, draw.keyIndex);
        ok = ok && writePod(handle, draw.vertexOffset);
        ok = ok && writePod(handle, draw.vertexCount);
        ok = ok && writePod(handle, draw.indexOffset);
        ok = ok && writePod(handle, draw.indexCount);
    }
    if (!file.vertexData.empty()) {
        ok = ok && std::fwrite(file.vertexData.data(), 1, file.vertexData.size(), handle)
                == file.vertexData.size();
    }
    if (!file.indexData.empty()) {
        ok = ok && std::fwrite(file.indexData.data(), 1, file.indexData.size(), handle)
                == file.indexData.size();
    }
    ok = ok && writePod(handle, file.checksum);

    std::fclose(handle);
    if (!ok) {
        error = "failed writing " + path;
    }
    return ok;
}

std::string describeVertex(const std::vector<uint8_t>& data, int32_t vertexIndex) {
    const size_t offset = static_cast<size_t>(vertexIndex) * 24;
    if (offset + 24 > data.size()) {
        return "<out of range>";
    }
    uint32_t position[3] = {};
    uint32_t uv[2] = {};
    std::memcpy(position, data.data() + offset, 12);
    std::memcpy(uv, data.data() + offset + 12, 8);
    const int8_t nx = static_cast<int8_t>(data[offset + 20]);
    const int8_t ny = static_cast<int8_t>(data[offset + 21]);
    const int8_t nz = static_cast<int8_t>(data[offset + 22]);

    char buffer[256];
    std::snprintf(buffer, sizeof(buffer),
            "pos=(%s, %s, %s) uv=(%s, %s) normal=(%d, %d, %d)",
            formatFloatBits(position[0]).c_str(), formatFloatBits(position[1]).c_str(),
            formatFloatBits(position[2]).c_str(), formatFloatBits(uv[0]).c_str(),
            formatFloatBits(uv[1]).c_str(), static_cast<int>(nx), static_cast<int>(ny),
            static_cast<int>(nz));
    return std::string(buffer);
}

std::string diff(const File& expected, const File& actual) {
    std::ostringstream out;

    if (expected.flags != actual.flags) {
        out << "flags differ: expected " << expected.flags << ", got " << actual.flags << "\n";
    }
    if (expected.keys.size() != actual.keys.size()) {
        out << "key count differs: expected " << expected.keys.size()
            << ", got " << actual.keys.size() << "\n";
    }
    const size_t keyCount = std::min(expected.keys.size(), actual.keys.size());
    for (size_t i = 0; i < keyCount; i++) {
        const Key& a = expected.keys[i];
        const Key& b = actual.keys[i];
        if (a.shaderType != b.shaderType || a.color != b.color || a.texture != b.texture) {
            out << "key " << i << " differs: expected shaderType=" << a.shaderType
                << " color=0x" << std::hex << a.color << std::dec << " texture=\"" << a.texture
                << "\", got shaderType=" << b.shaderType << " color=0x" << std::hex << b.color
                << std::dec << " texture=\"" << b.texture << "\"\n";
        }
    }

    if (expected.draws.size() != actual.draws.size()) {
        out << "draw bucket count differs: expected " << expected.draws.size()
            << ", got " << actual.draws.size() << "\n";
    }
    const size_t drawCount = std::min(expected.draws.size(), actual.draws.size());
    for (size_t i = 0; i < drawCount; i++) {
        const Draw& a = expected.draws[i];
        const Draw& b = actual.draws[i];
        if (a.shaderType == b.shaderType && a.color == b.color && a.keyIndex == b.keyIndex
                && a.vertexCount == b.vertexCount && a.indexCount == b.indexCount) {
            continue;
        }
        out << "bucket " << i << " differs: expected key=" << a.keyIndex
            << " shaderType=" << a.shaderType << " color=0x" << std::hex << a.color << std::dec
            << " vertices=" << a.vertexCount << " indices=" << a.indexCount
            << ", got key=" << b.keyIndex << " shaderType=" << b.shaderType << " color=0x"
            << std::hex << b.color << std::dec << " vertices=" << b.vertexCount
            << " indices=" << b.indexCount << "\n";
    }

    if (expected.vertexData.size() != actual.vertexData.size()) {
        out << "vertex buffer size differs: expected " << expected.vertexData.size()
            << " bytes (" << expected.vertexData.size() / 24 << " vertices), got "
            << actual.vertexData.size() << " bytes (" << actual.vertexData.size() / 24
            << " vertices)\n";
    }
    const size_t vertexBytes = std::min(expected.vertexData.size(), actual.vertexData.size());
    for (size_t i = 0; i < vertexBytes; i++) {
        if (expected.vertexData[i] != actual.vertexData[i]) {
            const int32_t vertexIndex = static_cast<int32_t>(i / 24);
            out << "first vertex byte difference at vertex " << vertexIndex
                << ", byte " << (i % 24) << " (0x" << std::hex << i << std::dec << ")\n"
                << "  expected " << describeVertex(expected.vertexData, vertexIndex) << "\n"
                << "  got      " << describeVertex(actual.vertexData, vertexIndex) << "\n";
            break;
        }
    }

    if (expected.indexData.size() != actual.indexData.size()) {
        out << "index buffer size differs: expected " << expected.indexData.size()
            << " bytes (" << expected.indexData.size() / 4 << " indices), got "
            << actual.indexData.size() << " bytes (" << actual.indexData.size() / 4
            << " indices)\n";
    }
    const size_t indexBytes = std::min(expected.indexData.size(), actual.indexData.size());
    for (size_t i = 0; i < indexBytes; i += 4) {
        uint32_t a = 0;
        uint32_t b = 0;
        if (i + 4 <= expected.indexData.size()) {
            std::memcpy(&a, expected.indexData.data() + i, 4);
        }
        if (i + 4 <= actual.indexData.size()) {
            std::memcpy(&b, actual.indexData.data() + i, 4);
        }
        if (a != b) {
            out << "first index difference at index " << (i / 4) << ": expected " << a
                << ", got " << b << "\n";
            break;
        }
    }

    if (expected.checksum != actual.checksum && out.str().empty()) {
        out << "buffers compare equal but checksums differ: expected 0x" << std::hex
            << expected.checksum << ", got 0x" << actual.checksum << std::dec << "\n";
    }

    return out.str();
}

} // namespace golden
