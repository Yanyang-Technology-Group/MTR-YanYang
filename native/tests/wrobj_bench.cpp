// SPDX-License-Identifier: MIT
//
// wrobj_bench.cpp — native micro-benchmark for the OBJ model pipeline.
//
// This measures the native path only. Its job is to report the shape of the work
// (vertices, triangles, bytes, throughput) and to make regressions visible; the
// comparison against the Java implementation is produced by the mod-side harness
// in tools/, which is the only place that can time both paths on equal terms.
//
// Usage:
//   wrobj_bench <model.obj> [<model.mtl>] [iterations] [placements]

#include "wrobj/wrobj.hpp"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string readFile(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        return std::string();
    }
    std::ostringstream buffer;
    buffer << stream.rdbuf();
    return buffer.str();
}

double secondsSince(const std::chrono::steady_clock::time_point& start) {
    const auto now = std::chrono::steady_clock::now();
    return std::chrono::duration<double>(now - start).count();
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cout << "usage: wrobj_bench <model.obj> [<model.mtl>] [iterations] [placements]\n";
        return 1;
    }

    const std::string objPath = argv[1];

    // The optional arguments are positional, so a numeric second argument is treated as
    // the iteration count rather than as an MTL path. Guessing wrong there previously
    // meant a model with no materials at all, which silently changes what is measured.
    std::string mtlPath;
    int iterations = 20;
    int placements = 8;

    int numericIndex = 0;
    for (int i = 2; i < argc; i++) {
        const std::string argument = argv[i];
        const bool isNumeric = !argument.empty()
                && argument.find_first_not_of("0123456789") == std::string::npos;
        if (isNumeric) {
            if (numericIndex == 0) {
                iterations = std::atoi(argument.c_str());
            } else {
                placements = std::atoi(argument.c_str());
            }
            numericIndex++;
        } else {
            mtlPath = argument;
        }
    }

    if (iterations < 1) {
        iterations = 1;
    }
    if (placements < 1) {
        placements = 1;
    }

    const std::string objContent = readFile(objPath);
    if (objContent.empty()) {
        std::cerr << "cannot read " << objPath << "\n";
        return 1;
    }
    const std::string mtlContent = mtlPath.empty() ? std::string() : readFile(mtlPath);
    const int32_t mtlLength = static_cast<int32_t>(mtlContent.size());

    std::cout << wr_obj_version_string() << "\n";
    std::cout << "model:        " << objPath << " (" << objContent.size() << " bytes)\n";
    if (!mtlPath.empty()) {
        std::cout << "material:     " << mtlPath << " (" << mtlContent.size() << " bytes)\n";
    } else {
        std::cout << "material:     (none)\n";
    }
    std::cout << "iterations:   " << iterations << "\n";
    std::cout << "placements:   " << placements << "\n\n";

    // Key discovery, measured on its own because the mod calls it once per model
    // resource and it is pure parsing.
    std::vector<int32_t> handles;
    std::vector<int32_t> colorSet;
    {
        WrObjKeyList* keys = wr_obj_keys_load(objContent.data(), static_cast<int32_t>(objContent.size()),
                mtlContent.empty() ? nullptr : mtlContent.data(), mtlLength, 1);
        if (keys == nullptr) {
            std::cerr << "the document could not be parsed\n";
            return 1;
        }
        std::cout << "material keys: " << keys->count << "\n";
        for (int32_t i = 0; i < keys->count; i++) {
            std::cout << "  [" << i << "] \"" << keys->entries[i].key << "\""
                      << " shader=" << keys->entries[i].shaderType
                      << " texture=\"" << keys->entries[i].textureString << "\""
                      << " color=0x" << std::hex << keys->entries[i].color << std::dec
                      << " hasColor=" << keys->entries[i].hasColor << "\n";
        }
        std::cout << "\n";
        handles.assign(static_cast<size_t>(keys->count), 0);
        // The host reports whether each key's colour is authoritative, which decides
        // whether it reaches MaterialProperties at all.
        colorSet.assign(static_cast<size_t>(keys->count), 0);
        for (int32_t i = 0; i < keys->count; i++) {
            handles[static_cast<size_t>(i)] = i;
            colorSet[static_cast<size_t>(i)] = keys->entries[i].hasColor;
        }
        wr_obj_keys_free(keys);
    }

    double parseSeconds = 0.0;
    double buildSeconds = 0.0;
    int64_t lastVertexCount = 0;
    int64_t lastFaceCount = 0;
    int64_t lastVertexBytes = 0;
    int64_t lastIndexBytes = 0;
    int64_t lastHeapEstimate = 0;
    int lastDrawCount = 0;
    uint64_t checksum = 0;

    for (int iteration = 0; iteration < iterations; iteration++) {
        const auto start = std::chrono::steady_clock::now();
        WrObjBuilder* builder = wr_obj_builder_create(
                objContent.data(), static_cast<int32_t>(objContent.size()),
                mtlContent.empty() ? nullptr : mtlContent.data(), mtlLength,
                handles.data(), colorSet.data(), static_cast<int32_t>(handles.size()), 1, 1);
        parseSeconds += secondsSince(start);
        if (builder == nullptr) {
            std::cerr << "build rejected the document\n";
            return 1;
        }

        for (int placement = 0; placement < placements; placement++) {
            // Mirror what MTR does: one placement per part position, alternating
            // the flipped variant so both branches are exercised.
            wr_obj_builder_add_transform(builder,
                    placement % 3 == 0 ? WROBJ_SHADER_CUTOUT_BRIGHT : WROBJ_SHADER_CUTOUT,
                    placement * 0.0625, 0.0, placement * -0.125,
                    placement % 2);
        }

        const auto buildStart = std::chrono::steady_clock::now();
        WrObjResult* result = wr_obj_builder_build(builder);
        buildSeconds += secondsSince(buildStart);
        if (result == nullptr) {
            std::cerr << "build produced no geometry\n";
            wr_obj_builder_free(builder);
            return 1;
        }

        lastVertexCount = result->totalVertexCount;
        lastFaceCount = result->totalFaceCount;
        lastVertexBytes = result->vertexDataLength;
        lastIndexBytes = result->indexDataLength;
        lastHeapEstimate = result->estimatedJavaHeapBytes;
        lastDrawCount = result->drawCount;

        // Fold the buffers into a checksum so a run-to-run change is visible even
        // when the timings are noisy.
        checksum = 0xCBF29CE484222325ULL;
        const auto* vertices = static_cast<const uint8_t*>(result->vertexData);
        for (int32_t i = 0; i < result->vertexDataLength; i++) {
            checksum ^= vertices[i];
            checksum *= 0x100000001B3ULL;
        }
        const auto* indices = static_cast<const uint8_t*>(result->indexData);
        for (int32_t i = 0; i < result->indexDataLength; i++) {
            checksum ^= indices[i];
            checksum *= 0x100000001B3ULL;
        }

        wr_obj_result_free(result);
        wr_obj_builder_free(builder);
    }

    const double parseMs = parseSeconds * 1000.0 / iterations;
    const double buildMs = buildSeconds * 1000.0 / iterations;

    std::cout << "result (per iteration, " << placements << " placements)\n";
    std::cout << "  vertices:      " << lastVertexCount << "\n";
    std::cout << "  triangles:     " << lastFaceCount << "\n";
    std::cout << "  draw buckets:  " << lastDrawCount << "\n";
    std::cout << "  vertex bytes:  " << lastVertexBytes << "\n";
    std::cout << "  index bytes:   " << lastIndexBytes << "\n";
    std::cout << "  checksum:      0x" << std::hex << checksum << std::dec << "\n\n";

    std::cout << "timings\n";
    std::cout << "  parse + source meshes: " << parseMs << " ms\n";
    std::cout << "  normals + distinct + pack: " << buildMs << " ms\n";
    std::cout << "  total:                 " << (parseMs + buildMs) << " ms\n\n";

    if (parseMs + buildMs > 0.0) {
        std::cout << "throughput\n";
        std::cout << "  " << (static_cast<double>(lastVertexCount) / ((parseMs + buildMs) / 1000.0) / 1.0e6)
                  << " M vertices/s\n";
    }
    std::cout << "Java heap the reference pipeline would have churned through: "
              << (lastHeapEstimate / 1024 / 1024) << " MiB per build\n";

    return 0;
}
