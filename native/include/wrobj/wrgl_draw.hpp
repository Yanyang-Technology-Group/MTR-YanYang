// SPDX-License-Identifier: MIT
//
// wrgl_draw.hpp — the native draw list for MTR's optimized OBJ renderer.
//
// ---------------------------------------------------------------------------
// The model this follows
// ---------------------------------------------------------------------------
//
// Blender's GPU module separates three concerns that this design keeps separate too:
//
//   gpu::Batch      (GPU_batch.hh)  pure geometry: vertex buffers, an index buffer,
//                                   a primitive type, an ownership flag, and a
//                                   GPU_BATCH_DIRTY flag for rebuilding cached
//                                   binding state. "It is meant to be used for
//                                   drawing large (> 1000 vertices) reusable (drawn
//                                   multiple times) model with complex data layout."
//   GPU_batch_draw  the draw call itself, taking counts from the batch.
//   shader state    explicitly deprecated on the batch in favour of the caller
//                   owning it, per the TODO in that header.
//
// MTR's Java BatchManager is the same three-way split but pays Java costs for each:
// a HashMap keyed on boxed material properties, an ArrayList of RenderCall objects
// allocated per frame, and a VertexAttributeState applied per vertex array - nine GL
// calls per array whether or not the value changed.
//
// This file replaces the middle layer. A "batch" here is one MTR VertexArray, registered
// once at model build time and identified afterwards by a small integer. Per frame the
// caller queues integers, not objects. At flush the list is walked once and every GL
// value is sent only when it actually differs from the previous one.
//
// ---------------------------------------------------------------------------
// What is deliberately left on the Java side
// ---------------------------------------------------------------------------
//
// Shader and texture binding, and the sampler and matrix uniforms that go with them.
// Those need a live client - RenderSystem, RenderLayer, ShaderProgram - and the call
// count is per material per frame, not per array, so moving them across the boundary
// would cost more than it saves. The Java side binds a shader for a material and then
// hands the whole group to one flush call.

#pragma once

#include <cstdint>

#include "wrgl_cache.hpp"
#include "wrgl_emit.hpp"

namespace wrobj {

/// The state that is uniform for a whole flush: every array queued between one
/// GlStateTracker capture and restore is drawn with the same colour, lightmap and
/// overlay, because those come from Minecraft's global render state and MTR's queue
/// calls pass their arguments straight through from it.
struct DrawGlobals {
    int32_t color = 0;
    int32_t lightmap = 0;
    int32_t overlay = 0;
    /// When false the colour, lightmap and overlay attributes are left untouched, which
    /// is what VertexAttributeState(java.lang.Integer, java.lang.Integer) does when it is
    /// constructed with nulls.
    bool hasColor = false;
    bool hasLightmap = false;
    bool hasOverlay = false;
};

/// One registered vertex array.
struct DrawBatch {
    /// The GL vertex array object MTR created for this array.
    uint32_t vao = 0;
    /// Number of indices to draw.
    int32_t indexCount = 0;
    /// The material's shader type ordinal, used both for reporting and for grouping.
    int32_t shaderType = 0;
    /// True while the entry is in use. Unregistering clears it rather than compacting,
    /// so registered indices stay valid.
    bool live = false;
};

/// One queued draw.
struct DrawItem {
    int32_t batch = -1;
    /// The pose matrix, in the column-major order GL expects for four vec4 attributes.
    float matrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    /// Sort key within a flush. Lower draws first.
    int32_t order = 0;
};

/// Counters for the whole draw list lifetime, so the saving can be reported.
struct DrawStats {
    int64_t flushes = 0;
    int64_t arraysDrawn = 0;
    /// Attribute and vertex array calls that were skipped because the value was already
    /// in place. This is the number the rewrite exists to move.
    int64_t callsSkipped = 0;
    /// Attribute and vertex array calls that actually reached GL.
    int64_t callsIssued = 0;
    /// Draw calls issued. One per array, because each array owns its own index buffer.
    int64_t drawCalls = 0;
};

/// The native draw list.
///
/// Not thread safe, by design: MTR queues and flushes on the render thread, and the
/// Java side's own batch manager had the same constraint.
class DrawList {
public:
    DrawList();
    ~DrawList();

    DrawList(const DrawList&) = delete;
    DrawList& operator=(const DrawList&) = delete;

    /// Installs the GL entry points. Until this is called, and until it reports
    /// isComplete(), every draw method is a no-op that only counts.
    void setGlTable(const GlEmit& gl);

    /// True when the entry points are installed and usable.
    bool isReady() const { return gl_.isComplete(); }

    // -----------------------------------------------------------------------
    // Registration, once per model build
    // -----------------------------------------------------------------------

    /// Registers a vertex array and returns its handle. Returns -1 when the list is
    /// full or not ready.
    int32_t registerBatch(uint32_t vao, int32_t indexCount, int32_t shaderType);

    /// Marks a handle free. Its GL objects belong to the caller, which deletes them.
    void unregisterBatch(int32_t handle);

    /// Number of live registrations, for diagnostics.
    int32_t liveBatchCount() const;

    // -----------------------------------------------------------------------
    // Per frame
    // -----------------------------------------------------------------------

    /// Empties the queue. Does not touch registrations.
    void beginFrame();

    /// Forgets every cached GL value, forcing the next flush to re-establish them.
    ///
    /// Called automatically at the start of every flush, because the host binds a shader
    /// immediately beforehand and a shader bind resets every generic vertex attribute to its
    /// default, which makes every remembered value stale. Exposed for a host that changes
    /// state in some other way.
    void invalidateState();

    /// Queues one draw of a registered array with the given pose matrix.
    void queue(int32_t handle, const float matrix[16]);

    /// Draws everything queued, honouring the given globals, and empties the queue.
    ///
    /// The globals are applied once rather than per array, which is correct because they
    /// come from Minecraft's global render state and do not vary within a flush. See the
    /// note in DrawGlobals.
    void flush(const DrawGlobals& globals);

    /// Queued draws not yet flushed.
    int32_t queuedCount() const { return queueSize_; }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    const DrawStats& stats() const { return stats_; }
    void resetStats() { stats_ = DrawStats(); }

    /// The cache, exposed so tests and the host can invalidate it.
    GlAttrCache& cache() { return cache_; }

private:
    GlEmit gl_;
    GlAttrCache cache_;
    DrawStats stats_;

    DrawBatch* batches_ = nullptr;
    int32_t batchCapacity_ = 0;
    int32_t batchCount_ = 0;
    /// Highest handle ever issued plus one. Handles are not reused until unregister,
    /// which keeps stale references detectable in the tests.
    int32_t batchHighWater_ = 0;

    DrawItem* queue_ = nullptr;
    int32_t queueCapacity_ = 0;
    int32_t queueSize_ = 0;

    /// The previous entry's matrix, so an unchanged pose costs no attribute writes at all.
    /// Consecutive parts of one vehicle frequently share a pose, and the first entry after
    /// a flush has nothing to compare against.
    float previousMatrix_[16] = {0};
    bool hasPreviousMatrix_ = false;

    bool growBatches(int32_t required);
    bool growQueue(int32_t required);
    void applyGlobals(const DrawGlobals& globals);
    void applyMatrix(const float matrix[16]);
    void drawItem(const DrawItem& item);
};

} // namespace wrobj
