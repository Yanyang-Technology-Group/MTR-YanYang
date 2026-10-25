// SPDX-License-Identifier: MIT
//
// wrgl_draw.cpp — the native draw list.
//
// The per-frame loop is deliberately flat. Every decision it makes is one of:
//
//   * is this value already in place?        (the attribute cache answers)
//   * is this the same vertex array as last? (the cache answers)
//   * otherwise, gl one call.
//
// There is no per-array allocation, no map lookup and no polymorphic dispatch beyond the
// GL table's function pointers, which is what makes the Java-side cost disappear rather
// than merely shrink.

#include "wrobj/wrgl_draw.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <new>

namespace wrobj {
namespace {

constexpr int32_t kInitialBatchCapacity = 256;
constexpr int32_t kInitialQueueCapacity = 512;

/// The overlay MTR's VertexAttributeState supplies when constructed with explicit
/// values: OverlayTexture.getDefaultUvMapped(), which is 10 << 16 | 10.
constexpr int32_t kDefaultOverlay = (10 << 16) | 10;

} // namespace

DrawList::DrawList() {
    growBatches(kInitialBatchCapacity);
    growQueue(kInitialQueueCapacity);
    cache_.invalidate();
}

DrawList::~DrawList() {
    std::free(batches_);
    std::free(queue_);
}

bool DrawList::growBatches(int32_t required) {
    if (required <= batchCapacity_) {
        return true;
    }
    int32_t capacity = batchCapacity_ > 0 ? batchCapacity_ : kInitialBatchCapacity;
    while (capacity < required) {
        capacity *= 2;
    }
    auto* grown = static_cast<DrawBatch*>(std::realloc(batches_, sizeof(DrawBatch) * static_cast<size_t>(capacity)));
    if (grown == nullptr) {
        return false;
    }
    // Zero the new region so a live flag from a previous allocation cannot be inherited.
    std::memset(grown + batchCapacity_, 0, sizeof(DrawBatch) * static_cast<size_t>(capacity - batchCapacity_));
    batches_ = grown;
    batchCapacity_ = capacity;
    return true;
}

bool DrawList::growQueue(int32_t required) {
    if (required <= queueCapacity_) {
        return true;
    }
    int32_t capacity = queueCapacity_ > 0 ? queueCapacity_ : kInitialQueueCapacity;
    while (capacity < required) {
        capacity *= 2;
    }
    auto* grown = static_cast<DrawItem*>(std::realloc(queue_, sizeof(DrawItem) * static_cast<size_t>(capacity)));
    if (grown == nullptr) {
        return false;
    }
    queue_ = grown;
    queueCapacity_ = capacity;
    return true;
}

void DrawList::setGlTable(const GlEmit& gl) {
    gl_ = gl;
    // A new host means the previous cache describes a different GL context.
    cache_.invalidate();
    hasPreviousMatrix_ = false;
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------

int32_t DrawList::registerBatch(uint32_t vao, int32_t indexCount, int32_t shaderType) {
    if (vao == 0 || indexCount <= 0) {
        return -1;
    }

    // Reuse a freed slot when one is available, so a pack that is reloaded repeatedly does
    // not grow the registry forever.
    for (int32_t i = 0; i < batchCount_; i++) {
        if (!batches_[i].live) {
            batches_[i].vao = vao;
            batches_[i].indexCount = indexCount;
            batches_[i].shaderType = shaderType;
            batches_[i].live = true;
            return i;
        }
    }

    if (!growBatches(batchCount_ + 1)) {
        return -1;
    }
    const int32_t handle = batchCount_;
    batches_[handle].vao = vao;
    batches_[handle].indexCount = indexCount;
    batches_[handle].shaderType = shaderType;
    batches_[handle].live = true;
    batchCount_++;
    if (batchCount_ > batchHighWater_) {
        batchHighWater_ = batchCount_;
    }
    return handle;
}

void DrawList::unregisterBatch(int32_t handle) {
    if (handle < 0 || handle >= batchCount_) {
        return;
    }
    batches_[handle].live = false;
    batches_[handle].vao = 0;
    batches_[handle].indexCount = 0;
}

int32_t DrawList::liveBatchCount() const {
    int32_t count = 0;
    for (int32_t i = 0; i < batchCount_; i++) {
        if (batches_[i].live) {
            count++;
        }
    }
    return count;
}

// ---------------------------------------------------------------------------
// Per frame
// ---------------------------------------------------------------------------

void DrawList::beginFrame() {
    queueSize_ = 0;
    // The previous frame's attributes were restored by GlStateTracker, so nothing the cache
    // remembers is still true.
    cache_.invalidate();
    hasPreviousMatrix_ = false;
}

void DrawList::invalidateState() {
    cache_.invalidate();
    hasPreviousMatrix_ = false;
}

void DrawList::queue(int32_t handle, const float matrix[16]) {
    if (handle < 0 || handle >= batchCount_ || !batches_[handle].live) {
        return;
    }
    if (!growQueue(queueSize_ + 1)) {
        return;
    }
    DrawItem& item = queue_[queueSize_];
    item.batch = handle;
    // Lower order draws first. Grouping by shader type keeps consecutive entries on the
    // same program, which is the one reordering that is unambiguously safe here: every
    // material on this path is opaque or alpha-cutout with depth testing enabled, so the
    // drawn order cannot change the result.
    item.order = batches_[handle].shaderType;
    std::memcpy(item.matrix, matrix, sizeof(item.matrix));
    queueSize_++;
}

void DrawList::applyGlobals(const DrawGlobals& globals) {
    if (globals.hasColor) {
        // VertexAttributeState.apply reads the four packed bytes most-significant first and
        // divides each by 255. The value is shifted as unsigned so the arithmetic shift that
        // a signed right shift of a negative int would perform cannot leak sign bits into
        // the channel.
        const uint32_t packed = static_cast<uint32_t>(globals.color);
        cache_.setFloat4(gl_, GL_ATTR_COLOR,
                static_cast<float>((packed >> 24) & 0xFFU) / 255.0F,
                static_cast<float>((packed >> 16) & 0xFFU) / 255.0F,
                static_cast<float>((packed >> 8) & 0xFFU) / 255.0F,
                static_cast<float>(packed & 0xFFU) / 255.0F);
    }
    // The reference stores the overlay and the lightmap through
    // Utilities.exchangeLightmapUVBits - (value >> 16) | ((short) value << 16) - and
    // VertexAttributeState.apply() then writes the exchanged value's high half first and
    // its low half second, which composed is the original packed value's LOW half first.
    // The overlay default is (10 << 16) | 10, symmetric under the exchange, so only the
    // lightmap's half order is observable in practice; both are written the reference's
    // way here.
    if (globals.hasOverlay) {
        const uint32_t packed = static_cast<uint32_t>(globals.overlay);
        cache_.setInt2(gl_, GL_ATTR_UV_OVERLAY,
                static_cast<int16_t>(packed),
                static_cast<int16_t>(packed >> 16));
    }
    if (globals.hasLightmap) {
        const uint32_t packed = static_cast<uint32_t>(globals.lightmap);
        cache_.setInt2(gl_, GL_ATTR_UV_LIGHTMAP,
                static_cast<int16_t>(packed),
                static_cast<int16_t>(packed >> 16));
    }
}

void DrawList::applyMatrix(const float matrix[16]) {
    if (hasPreviousMatrix_ && std::memcmp(previousMatrix_, matrix, sizeof(previousMatrix_)) == 0) {
        // Consecutive parts of one vehicle often share a pose, and a vehicle whose parts
        // all share one pose costs nothing here at all.
        stats_.callsSkipped += GL_ATTR_MATRIX_SPAN;
        return;
    }

    for (int32_t row = 0; row < GL_ATTR_MATRIX_SPAN; row++) {
        cache_.setFloat4(gl_, GL_ATTR_MATRIX_MODEL + row,
                matrix[row * 4 + 0], matrix[row * 4 + 1],
                matrix[row * 4 + 2], matrix[row * 4 + 3]);
    }
    std::memcpy(previousMatrix_, matrix, sizeof(previousMatrix_));
    hasPreviousMatrix_ = true;
}

void DrawList::drawItem(const DrawItem& item) {
    const DrawBatch& batch = batches_[item.batch];
    cache_.bindVertexArray(gl_, batch.vao);
    gl_.drawElements(gl_.context, batch.indexCount);
    stats_.drawCalls++;
}

void DrawList::flush(const DrawGlobals& globals) {
    if (queueSize_ == 0) {
        return;
    }

    stats_.flushes++;

    // The cache is invalidated here rather than only at beginFrame, because binding a
    // shader program resets every generic vertex attribute to its default value. The host
    // binds a shader immediately before each flush - once per material, which is the point
    // of batching by material - so the values the cache remembered from the previous
    // material are stale by the time this flush runs. Nothing else changes these attributes
    // inside a flush, so re-establishing them once per material is both correct and cheap.
    invalidateState();

    if (!isReady()) {
        // No GL entry points: count what would have been drawn and drop the queue, so the
        // host can run headless and still report.
        stats_.arraysDrawn += queueSize_;
        queueSize_ = 0;
        return;
    }

    // Group by shader type so consecutive entries stay on one program. std::stable_sort
    // because two entries with the same order must keep their relative sequence.
    std::stable_sort(queue_, queue_ + queueSize_,
            [](const DrawItem& left, const DrawItem& right) { return left.order < right.order; });

    // The globals are per flush, which is what removes the largest part of the per-array
    // cost: the reference sets colour, overlay and lightmap for every vertex array.
    applyGlobals(globals);

    for (int32_t i = 0; i < queueSize_; i++) {
        applyMatrix(queue_[i].matrix);
        drawItem(queue_[i]);
    }

    // Take the counters rather than read them, so the reported cost belongs to this flush
    // and the next one starts from zero. Reading them cumulatively would double count
    // across frames and would also miss the matrix rows skipped without touching the cache.
    int64_t attributeWrites = 0;
    int64_t attributeSkips = 0;
    int64_t vertexArrayBinds = 0;
    int64_t vertexArraySkips = 0;
    cache_.takeCounters(attributeWrites, attributeSkips, vertexArrayBinds, vertexArraySkips);

    stats_.arraysDrawn += queueSize_;
    stats_.callsIssued += attributeWrites + vertexArrayBinds;
    stats_.callsSkipped += attributeSkips + vertexArraySkips;

    queueSize_ = 0;
}

} // namespace wrobj
