// SPDX-License-Identifier: MIT
//
// wrgl_tests.cpp — tests for the native draw list, its attribute cache, and the GL
// emission it performs.
//
// The whole point of routing GL through a function table (wrgl_emit.hpp) is that the
// batching logic becomes testable with no GL context and no driver: the table is filled
// with a recording stub, and the tests assert on the exact sequence of calls the renderer
// would have made. That is a stronger check than "it rendered correctly on my machine",
// because it pins the *number* of calls as well as their values - and the call count is
// the thing this rewrite exists to change.
//
// The reference behaviour being reproduced is VertexAttributeState.apply() plus
// VertexArray.bind()/draw() from MTR's optimized renderer, which together cost one
// glVertexAttrib4f per matrix row, one glVertexAttribI2i for the overlay, one for the
// lightmap, one glVertexAttrib4f for the colour, one glBindVertexArray and one
// glDrawElements - per vertex array, per frame.

#include "wrobj/wrgl_emit.hpp"
#include "wrobj/wrgl_cache.hpp"
#include "wrobj/wrgl_draw.hpp"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

int gChecks = 0;
int gFailures = 0;

void check(bool condition, const std::string& what) {
    gChecks++;
    if (!condition) {
        gFailures++;
        std::printf("  FAIL: %s\n", what.c_str());
    }
}

template <typename T>
void checkEqual(const T& actual, const T& expected, const std::string& what) {
    gChecks++;
    if (!(actual == expected)) {
        gFailures++;
        std::printf("  FAIL: %s (expected %lld, got %lld)\n", what.c_str(),
                static_cast<long long>(expected), static_cast<long long>(actual));
    }
}

void checkFloat(float actual, float expected, const std::string& what) {
    gChecks++;
    if (actual != expected) {
        gFailures++;
        std::printf("  FAIL: %s (expected %g, got %g)\n", what.c_str(),
                static_cast<double>(expected), static_cast<double>(actual));
    }
}

// ---------------------------------------------------------------------------
// The recording stub
// ---------------------------------------------------------------------------

/// One GL call, in the order it was made.
struct RecordedCall {
    enum class Kind { Attrib4f, AttribI2i, Attrib2f, BindVertexArray, DrawElements };
    Kind kind;
    int32_t a = 0;
    float f[4] = {0, 0, 0, 0};
};

/// Records every call rather than performing it.
struct Recorder {
    std::vector<RecordedCall> calls;

    void clear() { calls.clear(); }

    int64_t countOf(RecordedCall::Kind kind) const {
        int64_t total = 0;
        for (const RecordedCall& call : calls) {
            if (call.kind == kind) {
                total++;
            }
        }
        return total;
    }

    /// The last value written to an attribute, or false when nothing was.
    bool lastAttrib4f(int32_t location, float out[4]) const {
        for (auto it = calls.rbegin(); it != calls.rend(); ++it) {
            if (it->kind == RecordedCall::Kind::Attrib4f && it->a == location) {
                std::memcpy(out, it->f, sizeof(it->f));
                return true;
            }
        }
        return false;
    }
};

Recorder* gRecorder = nullptr;

void recordAttrib4f(void*, int32_t location, float x, float y, float z, float w) {
    RecordedCall call;
    call.kind = RecordedCall::Kind::Attrib4f;
    call.a = location;
    call.f[0] = x;
    call.f[1] = y;
    call.f[2] = z;
    call.f[3] = w;
    gRecorder->calls.push_back(call);
}

void recordAttribI2i(void*, int32_t location, int32_t x, int32_t y) {
    RecordedCall call;
    call.kind = RecordedCall::Kind::AttribI2i;
    call.a = location;
    call.f[0] = static_cast<float>(x);
    call.f[1] = static_cast<float>(y);
    gRecorder->calls.push_back(call);
}

void recordAttrib2f(void*, int32_t location, float x, float y) {
    RecordedCall call;
    call.kind = RecordedCall::Kind::Attrib2f;
    call.a = location;
    call.f[0] = x;
    call.f[1] = y;
    gRecorder->calls.push_back(call);
}

void recordBindVertexArray(void*, uint32_t vao) {
    RecordedCall call;
    call.kind = RecordedCall::Kind::BindVertexArray;
    call.f[0] = static_cast<float>(vao);
    gRecorder->calls.push_back(call);
}

void recordDrawElements(void*, int32_t count) {
    RecordedCall call;
    call.kind = RecordedCall::Kind::DrawElements;
    call.a = count;
    gRecorder->calls.push_back(call);
}

wrobj::GlEmit makeGlTable(Recorder& recorder, bool floatFallback = false) {
    wrobj::GlEmit gl;
    gl.context = nullptr;
    gl.attrib4f = recordAttrib4f;
    gl.attribI2i = recordAttribI2i;
    gl.attrib2f = recordAttrib2f;
    gl.bindVertexArray = recordBindVertexArray;
    gl.drawElements = recordDrawElements;
    gl.useFloatAttributeFallback = floatFallback;
    gRecorder = &recorder;
    return gl;
}

/// A pose matrix that is distinct per index, so a test can tell two poses apart.
void makeMatrix(float out[16], float seed) {
    for (int i = 0; i < 16; i++) {
        out[i] = seed + static_cast<float>(i) * 0.125F;
    }
}

wrobj::DrawGlobals makeGlobals() {
    wrobj::DrawGlobals globals;
    globals.color = 0xFFFFFFFF;
    globals.lightmap = 0x00F000F0;
    globals.overlay = (10 << 16) | 10;
    globals.hasColor = true;
    globals.hasLightmap = true;
    globals.hasOverlay = true;
    return globals;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// The cost the rewrite exists to remove: the reference sets the colour, overlay and
/// lightmap for every array, this sets them once per flush.
void testGlobalsAreAppliedOncePerFlush() {
    std::printf("draw list: globals are applied once per flush, not per array\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t batch = list.registerBatch(1, 36, 0);
    check(batch >= 0, "a batch registers");

    list.beginFrame();
    float matrix[16];
    for (int32_t i = 0; i < 50; i++) {
        makeMatrix(matrix, static_cast<float>(i));
        list.queue(batch, matrix);
    }
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::DrawElements), int64_t(50),
            "every queued array is drawn");
    checkEqual(recorder.countOf(RecordedCall::Kind::AttribI2i), int64_t(2),
            "the overlay and lightmap are set once each, not once per array");
    checkEqual(recorder.countOf(RecordedCall::Kind::Attrib4f), int64_t(1 + 50 * 4),
            "the colour once plus four matrix rows per distinct pose");
}

/// Fifty arrays that share a pose must cost one matrix upload, not fifty.
void testRepeatedPoseCostsNothing() {
    std::printf("draw list: a repeated pose costs no attribute writes\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t first = list.registerBatch(1, 36, 0);
    const int32_t second = list.registerBatch(2, 36, 0);

    float matrix[16];
    makeMatrix(matrix, 3.0F);

    list.beginFrame();
    for (int32_t i = 0; i < 25; i++) {
        list.queue(first, matrix);
        list.queue(second, matrix);
    }
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::DrawElements), int64_t(50),
            "all fifty arrays are drawn");
    checkEqual(recorder.countOf(RecordedCall::Kind::Attrib4f), int64_t(1 + 4),
            "the colour once and the matrix rows once, because the pose never changed");
    checkEqual(list.stats().callsSkipped >= 46 * 4, true,
            "the skipped matrix writes are reported");
}

/// Two poses in sequence upload two matrices.
void testDistinctPosesUploadTwice() {
    std::printf("draw list: two distinct poses upload two matrices\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t batch = list.registerBatch(7, 12, 0);

    float first[16];
    float second[16];
    makeMatrix(first, 0.0F);
    makeMatrix(second, 100.0F);

    list.beginFrame();
    list.queue(batch, first);
    list.queue(batch, second);
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::Attrib4f), int64_t(1 + 8),
            "the colour once and four matrix rows per pose");
    checkEqual(recorder.countOf(RecordedCall::Kind::BindVertexArray), int64_t(1),
            "one vertex array, bound once");
}

/// Alternating two vertex arrays must bind twice, not once per draw.
void testVertexArrayBindingIsCached() {
    std::printf("draw list: vertex array binding is skipped when unchanged\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t alpha = list.registerBatch(100, 6, 0);
    const int32_t beta = list.registerBatch(200, 6, 0);

    float matrix[16];
    makeMatrix(matrix, 0.0F);

    list.beginFrame();
    for (int32_t i = 0; i < 10; i++) {
        list.queue(alpha, matrix);
        list.queue(alpha, matrix);
        list.queue(beta, matrix);
    }
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::DrawElements), int64_t(30),
            "all thirty arrays are drawn");
    // alpha, alpha, beta per group: alpha binds, the second alpha reuses it, beta binds.
    checkEqual(recorder.countOf(RecordedCall::Kind::BindVertexArray), int64_t(20),
            "one bind per run of the same vertex array");
}

/// Entries are grouped by shader type so consecutive draws stay on one program.
void testEntriesAreGroupedByShaderType() {
    std::printf("draw list: entries are grouped by shader type\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    // Register in an order that does not match the shader type order.
    const int32_t translucent = list.registerBatch(11, 3, 1);
    const int32_t cutout = list.registerBatch(22, 3, 0);
    const int32_t glowing = list.registerBatch(33, 3, 4);

    float matrix[16];
    makeMatrix(matrix, 1.0F);

    list.beginFrame();
    list.queue(translucent, matrix);
    list.queue(cutout, matrix);
    list.queue(glowing, matrix);
    recorder.clear();
    list.flush(makeGlobals());

    // The vertex arrays should be visited in shader type order: 22, 11, 33.
    std::vector<float> boundVaos;
    for (const RecordedCall& call : recorder.calls) {
        if (call.kind == RecordedCall::Kind::BindVertexArray) {
            boundVaos.push_back(call.f[0]);
        }
    }
    checkEqual(boundVaos.size(), size_t(3), "three binds");
    if (boundVaos.size() == 3) {
        checkFloat(boundVaos[0], 22.0F, "CUTOUT (type 0) draws first");
        checkFloat(boundVaos[1], 11.0F, "TRANSLUCENT (type 1) draws second");
        checkFloat(boundVaos[2], 33.0F, "CUTOUT_GLOWING (type 4) draws last");
    }
}

/// The gl4ES fallback must be taken when the host reports integer attributes unusable.
void testIntegerAttributeFallback() {
    std::printf("draw list: the gl4ES float fallback is used when requested\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder, true));

    const int32_t batch = list.registerBatch(5, 3, 0);
    float matrix[16];
    makeMatrix(matrix, 0.0F);

    list.beginFrame();
    list.queue(batch, matrix);
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::AttribI2i), int64_t(0),
            "no integer attribute calls are made");
    checkEqual(recorder.countOf(RecordedCall::Kind::Attrib2f), int64_t(2),
            "the overlay and lightmap go through the float entry point");
}

/// A null lightmap must leave the attribute alone, which is what the reference does when
/// it is constructed with a null lightmap UV.
void testNullGlobalsAreLeftAlone() {
    std::printf("draw list: absent globals are not written\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t batch = list.registerBatch(9, 3, 0);
    float matrix[16];
    makeMatrix(matrix, 0.0F);

    wrobj::DrawGlobals globals;
    globals.hasColor = true;
    globals.color = 0x80FF0000;
    globals.hasLightmap = false;
    globals.hasOverlay = false;

    list.beginFrame();
    list.queue(batch, matrix);
    recorder.clear();
    list.flush(globals);

    checkEqual(recorder.countOf(RecordedCall::Kind::AttribI2i), int64_t(0),
            "neither the overlay nor the lightmap is written");
    checkEqual(recorder.countOf(RecordedCall::Kind::Attrib4f), int64_t(1 + 4),
            "the colour and the matrix are still written");
}

/// The colour packing must be unpacked the way the reference does it.
void testColourIsUnpackedLikeTheReference() {
    std::printf("draw list: the colour is unpacked with the reference's byte order\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t batch = list.registerBatch(3, 3, 0);
    float matrix[16];
    makeMatrix(matrix, 0.0F);

    wrobj::DrawGlobals globals;
    globals.hasColor = true;
    // 0x80 means each channel over 255, so the expected floats are exact.
    globals.color = 0x80402010;
    globals.hasLightmap = false;
    globals.hasOverlay = false;

    list.beginFrame();
    list.queue(batch, matrix);
    recorder.clear();
    list.flush(globals);

    float written[4] = {0, 0, 0, 0};
    const bool found = recorder.lastAttrib4f(wrobj::GL_ATTR_COLOR, written);
    check(found, "the colour attribute was written");
    if (found) {
        // VertexAttributeState.apply reads (color >>> 24), (>>> 16), (>>> 8) and the low
        // byte, in that order, and divides each by 255.
        checkFloat(written[0], 128.0F / 255.0F, "red comes from the most significant byte");
        checkFloat(written[1], 64.0F / 255.0F, "green from the second byte");
        checkFloat(written[2], 32.0F / 255.0F, "blue from the third byte");
        checkFloat(written[3], 16.0F / 255.0F, "alpha from the least significant byte");
    }
}

/// The overlay and lightmap are written as the two halves of the packed value. The
/// reference stores both through exchangeLightmapUVBits - (value >> 16) | ((short)
/// value << 16) - and VertexAttributeState.apply then writes the exchanged value's
/// high half first, which composed is the original value's LOW half first. The
/// overlay default is symmetric under the exchange, so only the lightmap's half
/// order is observable in practice, but both are written the reference's way.
void testOverlayAndLightmapHalves() {
    std::printf("draw list: the overlay and lightmap halves are extracted like the reference\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t batch = list.registerBatch(4, 3, 0);
    float matrix[16];
    makeMatrix(matrix, 0.0F);

    wrobj::DrawGlobals globals;
    globals.hasColor = false;
    globals.hasOverlay = true;
    globals.overlay = 0x000A000B;
    globals.hasLightmap = true;
    globals.lightmap = 0x00F00080;

    list.beginFrame();
    list.queue(batch, matrix);
    recorder.clear();
    list.flush(globals);

    bool sawOverlay = false;
    bool sawLightmap = false;
    for (const RecordedCall& call : recorder.calls) {
        if (call.kind != RecordedCall::Kind::AttribI2i) {
            continue;
        }
        if (call.a == wrobj::GL_ATTR_UV_OVERLAY) {
            sawOverlay = true;
            checkFloat(call.f[0], 11.0F, "the overlay's low half is the first component, after the reference's bit exchange");
            checkFloat(call.f[1], 10.0F, "the overlay's high half is the second");
        } else if (call.a == wrobj::GL_ATTR_UV_LIGHTMAP) {
            sawLightmap = true;
            checkFloat(call.f[0], 128.0F, "the lightmap's low half is the first component, after the reference's bit exchange");
            checkFloat(call.f[1], 240.0F, "the lightmap's high half is the second");
        }
    }
    check(sawOverlay, "the overlay was written");
    check(sawLightmap, "the lightmap was written");
}

/// An unregistered or freed handle must not draw, and must not corrupt the registry.
void testRegistrationLifetime() {
    std::printf("draw list: registrations are validated and reusable\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    checkEqual(list.registerBatch(0, 3, 0), int32_t(-1), "a zero vertex array is rejected");
    checkEqual(list.registerBatch(5, 0, 0), int32_t(-1), "a zero index count is rejected");

    const int32_t first = list.registerBatch(5, 3, 0);
    const int32_t second = list.registerBatch(6, 3, 0);
    check(first >= 0 && second >= 0, "two batches register");
    checkEqual(list.liveBatchCount(), int32_t(2), "two are live");

    float matrix[16];
    makeMatrix(matrix, 0.0F);

    list.unregisterBatch(first);
    checkEqual(list.liveBatchCount(), int32_t(1), "one is live after unregistering");

    list.beginFrame();
    list.queue(first, matrix);
    list.queue(second, matrix);
    list.queue(9999, matrix);
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.countOf(RecordedCall::Kind::DrawElements), int64_t(1),
            "only the live batch draws");

    // A freed slot is reused rather than growing the registry.
    const int32_t reused = list.registerBatch(7, 9, 0);
    checkEqual(reused, first, "the freed slot is reused");
    checkEqual(list.liveBatchCount(), int32_t(2), "two are live again");
}

/// An empty flush must do nothing at all, which matters because MTR calls render()
/// every frame whether or not anything was queued.
void testEmptyFlushIsFree() {
    std::printf("draw list: an empty flush emits nothing\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    list.registerBatch(1, 3, 0);
    list.beginFrame();
    recorder.clear();
    list.flush(makeGlobals());

    checkEqual(recorder.calls.size(), size_t(0), "no GL calls");
    checkEqual(list.stats().flushes, int64_t(0), "the flush is not counted");

    // A flush that never had beginFrame called still works and draws nothing.
    list.flush(makeGlobals());
    checkEqual(recorder.calls.size(), size_t(0), "still no GL calls");
}

/// Without GL entry points the list must count rather than crash, so the host can run
/// headless and still report what it would have drawn.
void testHeadlessCounting() {
    std::printf("draw list: a list without GL entry points only counts\n");

    wrobj::DrawList list;
    const int32_t batch = list.registerBatch(1, 36, 0);
    list.setGlTable(wrobj::GlEmit());
    check(!list.isReady(), "an empty table is not ready");

    float matrix[16];
    makeMatrix(matrix, 0.0F);

    list.beginFrame();
    for (int32_t i = 0; i < 5; i++) {
        list.queue(batch, matrix);
    }
    list.flush(makeGlobals());

    checkEqual(list.stats().arraysDrawn, int64_t(5), "the queued arrays are counted");
    checkEqual(list.stats().drawCalls, int64_t(0), "no draw calls are issued");
    checkEqual(list.queuedCount(), int32_t(0), "the queue is emptied");
}

/// The cache itself: values, integer and float spaces, and invalidation.
void testAttributeCache() {
    std::printf("cache: values are compared per slot and per kind\n");

    Recorder recorder;
    wrobj::GlAttrCache cache;
    const wrobj::GlEmit gl = makeGlTable(recorder);

    cache.invalidate();
    check(cache.setFloat4(gl, 1, 1.0F, 0.0F, 0.0F, 1.0F), "the first write goes through");
    check(!cache.setFloat4(gl, 1, 1.0F, 0.0F, 0.0F, 1.0F), "an identical write is skipped");
    check(cache.setFloat4(gl, 1, 1.0F, 0.0F, 0.0F, 0.5F), "a changed write goes through");

    // Different slots are independent.
    check(cache.setFloat4(gl, 2, 1.0F, 0.0F, 0.0F, 1.0F), "a different slot is separate");

    // The integer and float spaces do not alias.
    cache.invalidate();
    check(cache.setInt2(gl, 3, 7, 8), "an integer write goes through");
    check(cache.setFloat4(gl, 3, 7.0F, 8.0F, 0.0F, 0.0F), "a float write to the same slot is not skipped");
    check(cache.setInt2(gl, 3, 7, 8), "the integer write is not skipped by the float one");

    // Invalidation forces everything through.
    cache.invalidate();
    check(cache.setInt2(gl, 3, 7, 8), "an invalidated slot writes again");
    check(cache.setFloat4(gl, 1, 1.0F, 0.0F, 0.0F, 0.5F), "an invalidated float slot writes again");

    // Out-of-range locations are ignored rather than writing out of bounds.
    check(!cache.setFloat4(gl, -1, 0, 0, 0, 0), "a negative location is ignored");
    check(!cache.setFloat4(gl, wrobj::GlAttrCache::kSlotCount, 0, 0, 0, 0),
            "a location past the table is ignored");

    check(cache.attributeWrites() > 0, "writes are counted");
    check(cache.attributeSkips() > 0, "skips are counted");
}

/// The vertex array cache, including the "nothing bound yet" case.
void testVertexArrayCache() {
    std::printf("cache: vertex array binding is remembered across a flush\n");

    Recorder recorder;
    wrobj::GlAttrCache cache;
    const wrobj::GlEmit gl = makeGlTable(recorder);

    cache.invalidate();
    // Zero is a legal binding, so the first bind of zero must still be emitted: the
    // invalidated sentinel is not zero.
    check(cache.bindVertexArray(gl, 0), "the first bind of zero is emitted");
    check(!cache.bindVertexArray(gl, 0), "the second bind of zero is skipped");
    check(cache.bindVertexArray(gl, 4), "a different array binds");
    check(cache.bindVertexArray(gl, 0), "switching back binds again");
    checkEqual(cache.vertexArrayBinds(), int64_t(3), "three binds");
    checkEqual(cache.vertexArraySkips(), int64_t(1), "one skip");

    cache.forgetVertexArray();
    check(cache.bindVertexArray(gl, 0), "forgetting forces the next bind");
}

/// The reported saving must be internally consistent: every call is either issued or
/// skipped, and the totals must add up across flushes.
void testStatisticsAddUp() {
    std::printf("draw list: the statistics are consistent\n");

    Recorder recorder;
    wrobj::DrawList list;
    list.setGlTable(makeGlTable(recorder));

    const int32_t a = list.registerBatch(1, 36, 0);
    const int32_t b = list.registerBatch(2, 24, 0);

    float matrix[16];
    for (int32_t frame = 0; frame < 4; frame++) {
        list.beginFrame();
        for (int32_t i = 0; i < 8; i++) {
            makeMatrix(matrix, static_cast<float>(i));
            list.queue(a, matrix);
            list.queue(b, matrix);
        }
        list.flush(makeGlobals());
    }

    const wrobj::DrawStats& stats = list.stats();
    checkEqual(stats.flushes, int64_t(4), "four flushes");
    checkEqual(stats.arraysDrawn, int64_t(64), "sixty four arrays drawn");
    checkEqual(stats.drawCalls, int64_t(64), "one draw call per array");
    check(stats.callsIssued > 0, "calls were issued");
    check(stats.callsSkipped > 0, "calls were skipped");

    // Every GL call the recorder saw must be a draw, a bind, or an attribute write, and
    // the attribute writes plus the bind must equal what the statistics report as issued.
    const int64_t recordedAttributes = recorder.countOf(RecordedCall::Kind::Attrib4f)
            + recorder.countOf(RecordedCall::Kind::AttribI2i)
            + recorder.countOf(RecordedCall::Kind::Attrib2f);
    const int64_t recordedBinds = recorder.countOf(RecordedCall::Kind::BindVertexArray);
    checkEqual(recordedAttributes + recordedBinds, stats.callsIssued,
            "the issued count equals the calls the recorder saw");
}

} // namespace

int main() {
    std::printf("wrobj native draw list tests\n\n");

    testGlobalsAreAppliedOncePerFlush();
    testRepeatedPoseCostsNothing();
    testDistinctPosesUploadTwice();
    testVertexArrayBindingIsCached();
    testEntriesAreGroupedByShaderType();
    testIntegerAttributeFallback();
    testNullGlobalsAreLeftAlone();
    testColourIsUnpackedLikeTheReference();
    testOverlayAndLightmapHalves();
    testRegistrationLifetime();
    testEmptyFlushIsFree();
    testHeadlessCounting();
    testAttributeCache();
    testVertexArrayCache();
    testStatisticsAddUp();

    std::printf("\n%d checks, %d failure(s)\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
