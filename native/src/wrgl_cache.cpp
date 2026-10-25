// SPDX-License-Identifier: MIT
//
// wrgl_cache.cpp — the redundancy-eliminating attribute and vertex array cache.
//
// The comparisons are on the raw encoded values rather than on the semantic ones, which
// is deliberate. glVertexAttrib4f uploads the bits it is given, so two floats that differ
// only in their last mantissa bit are two different attribute values even though they may
// compare equal after a float round trip through a double. Comparing bits is both cheaper
// and correct.
//
// NaN is handled the same way: a NaN payload written twice is one GL call, because the
// bits match. Nothing in MTR's model data produces NaN, but the cache should not depend on
// that.

#include "wrobj/wrgl_cache.hpp"

#include <cstring>

namespace wrobj {
namespace {

int32_t floatBits(float value) {
    int32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

} // namespace

void GlAttrCache::invalidate() {
    for (int32_t i = 0; i < kSlotCount; i++) {
        slots_[i].valid = false;
    }
    // A value that no glGenVertexArrays result can take, so the first bind always goes
    // through even if the host had zero bound before.
    boundVao_ = kNoVertexArray;
}

void GlAttrCache::takeCounters(int64_t& attributeWrites, int64_t& attributeSkips,
        int64_t& vertexArrayBinds, int64_t& vertexArraySkips) {
    attributeWrites = attributeWrites_;
    attributeSkips = attributeSkips_;
    vertexArrayBinds = vertexArrayBinds_;
    vertexArraySkips = vertexArraySkips_;
    attributeWrites_ = 0;
    attributeSkips_ = 0;
    vertexArrayBinds_ = 0;
    vertexArraySkips_ = 0;
}

bool GlAttrCache::setFloat4(const GlEmit& gl, int32_t location, float x, float y, float z, float w) {
    if (location < 0 || location >= kSlotCount) {
        return false;
    }
    const int32_t encoded[4] = {floatBits(x), floatBits(y), floatBits(z), floatBits(w)};
    AttrSlot& slot = slots_[location];
    if (slot.valid && !slot.integer && slot.count == 4
            && slot.value[0] == encoded[0] && slot.value[1] == encoded[1]
            && slot.value[2] == encoded[2] && slot.value[3] == encoded[3]) {
        attributeSkips_++;
        return false;
    }

    gl.attrib4f(gl.context, location, x, y, z, w);
    slot.valid = true;
    slot.integer = false;
    slot.count = 4;
    slot.value[0] = encoded[0];
    slot.value[1] = encoded[1];
    slot.value[2] = encoded[2];
    slot.value[3] = encoded[3];
    attributeWrites_++;
    return true;
}

bool GlAttrCache::setInt2(const GlEmit& gl, int32_t location, int32_t x, int32_t y) {
    if (location < 0 || location >= kSlotCount) {
        return false;
    }
    AttrSlot& slot = slots_[location];
    if (slot.valid && slot.integer && slot.count == 2 && slot.value[0] == x && slot.value[1] == y) {
        attributeSkips_++;
        return false;
    }

    if (gl.useFloatAttributeFallback) {
        // gl4ES and similar translation layers do not implement integer vertex
        // attributes, so MTR falls back to the float entry point with the same numbers.
        // GlStateTracker.isGl4ES() is what decides this on the Java side and the host
        // passes the answer in.
        gl.attrib2f(gl.context, location, static_cast<float>(x), static_cast<float>(y));
    } else {
        gl.attribI2i(gl.context, location, x, y);
    }

    slot.valid = true;
    slot.integer = true;
    slot.count = 2;
    slot.value[0] = x;
    slot.value[1] = y;
    slot.value[2] = 0;
    slot.value[3] = 0;
    attributeWrites_++;
    return true;
}

bool GlAttrCache::bindVertexArray(const GlEmit& gl, uint32_t vao) {
    if (vao == boundVao_) {
        vertexArraySkips_++;
        return false;
    }
    gl.bindVertexArray(gl.context, vao);
    boundVao_ = vao;
    vertexArrayBinds_++;
    return true;
}

} // namespace wrobj
