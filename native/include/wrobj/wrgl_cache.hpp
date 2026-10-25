// SPDX-License-Identifier: MIT
//
// wrgl_cache.hpp — redundancy-eliminating cache for the vertex and buffer state that
// MTR's optimized renderer sets once per vertex array.
//
// ---------------------------------------------------------------------------
// Why this exists
// ---------------------------------------------------------------------------
//
// MTR's draw path applies a VertexAttributeState for *every* vertex array, every
// frame. Each application issues glVertexAttrib4f for the model matrix row by row,
// then glVertexAttribI2i for the overlay and lightmap and glVertexAttrib4f for the
// colour - nine GL calls per array, whether or not any of those values changed since
// the previous array. On a busy line that is tens of thousands of calls per frame
// carrying a handful of distinct values.
//
// Blender's GPU module solves the same problem differently: GPU_batch.hh documents
// "Shader Binding & Uniforms" as deprecated with a TODO to remove per-batch shader
// state entirely, leaving a batch as pure geometry and letting the caller own state.
// Following that intent, this cache owns the "is the value already there?" question
// so the caller can simply state what it wants.
//
// ---------------------------------------------------------------------------
// Safety
// ---------------------------------------------------------------------------
//
// The cache assumes nothing else changes these attributes, which holds because MTR
// brackets the whole optimized render pass in GlStateTracker.capture()/restore():
// the tracker saves the vertex array and buffer bindings and the shader program
// before the pass and puts them back afterwards. Nothing inside the pass touches
// these attributes except this renderer.
//
// The cache is still explicit about its own lifetime: invalidate() must be called
// before any code that could have changed the attributes behind its back, and the
// draw list does that at the start of every flush.

#pragma once

#include <cstdint>

#include "wrgl_emit.hpp"

namespace wrobj {

/// One attribute slot's last written value, in the encoded form the renderer uses.
///
/// Integer attributes (the overlay and lightmap UVs) and floating attributes (colour,
/// position, normal, texture coordinates) live in separate spaces, so they are tracked
/// separately and never compare equal to each other.
struct AttrSlot {
    /// False until something has been written in this cache epoch.
    bool valid = false;
    /// True when the slot holds an integer attribute, false for a float one.
    bool integer = false;
    /// Number of components: 1 to 4.
    int32_t count = 0;
    /// Encoded value. Float attributes store raw IEEE-754 bits; integer attributes
    /// store the value itself.
    int32_t value[4] = {0, 0, 0, 0};
};

/// Caches the per-array vertex attribute state and the vertex array binding.
///
/// The two concerns are together because they share one invalidation point: any code
/// that could bind a different vertex array could also have changed the attributes on
/// it, and MTR's tracker restores both.
class GlAttrCache {
public:
    /// Must be a superset of the attribute locations the renderer writes. MTR uses
    /// 0 for position, 1 for colour, 2 for texture UV, 3 for overlay, 4 for lightmap,
    /// 5 for the normal and 6..9 for the model matrix.
    static constexpr int32_t kSlotCount = 16;

    /// Forgets every remembered value, forcing the next write of each attribute to go
    /// through. Call whenever foreign code may have changed them.
    void invalidate();

    /// Writes four floats to an attribute, skipping the call when the value is unchanged.
    /// Returns true when a GL call was made.
    bool setFloat4(const GlEmit& gl, int32_t location, float x, float y, float z, float w);

    /// Writes two integers to an attribute, skipping the call when unchanged.
    bool setInt2(const GlEmit& gl, int32_t location, int32_t x, int32_t y);

    /// Binds a vertex array, skipping the call when it is already bound.
    bool bindVertexArray(const GlEmit& gl, uint32_t vao);

    /// Forces the next bind to be emitted even if it repeats the last one.
    void forgetVertexArray() { boundVao_ = kNoVertexArray; }

    /// The vertex array currently believed to be bound; kNoVertexArray means none.
    uint32_t boundVertexArray() const { return boundVao_; }

    /// Takes the counters and resets them, so a caller can report the cost of one epoch
    /// rather than of the cache's whole lifetime.
    void takeCounters(int64_t& attributeWrites, int64_t& attributeSkips,
            int64_t& vertexArrayBinds, int64_t& vertexArraySkips);

    /// Statistics, so the saving can be reported rather than asserted.
    int64_t attributeWrites() const { return attributeWrites_; }
    int64_t attributeSkips() const { return attributeSkips_; }
    int64_t vertexArrayBinds() const { return vertexArrayBinds_; }
    int64_t vertexArraySkips() const { return vertexArraySkips_; }

    /// A value no glGenVertexArrays result can take, so it never compares equal to a real
    /// binding. Zero is a legitimate binding - it means "no vertex array" - so a sentinel
    /// of zero would wrongly skip the first bind whenever the driver left zero bound.
    static constexpr uint32_t kNoVertexArray = 0xFFFFFFFFU;

private:
    AttrSlot slots_[kSlotCount];
    uint32_t boundVao_ = kNoVertexArray;

    int64_t attributeWrites_ = 0;
    int64_t attributeSkips_ = 0;
    int64_t vertexArrayBinds_ = 0;
    int64_t vertexArraySkips_ = 0;
};

} // namespace wrobj
