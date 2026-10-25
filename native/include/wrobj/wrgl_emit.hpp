// SPDX-License-Identifier: MIT
//
// wrgl_emit.hpp — the GL calls the native draw path needs, expressed as a table of
// function pointers.
//
// ---------------------------------------------------------------------------
// Why a table rather than linking against OpenGL
// ---------------------------------------------------------------------------
//
// A native library that links libGL directly would be simpler, but it cannot be
// portable here:
//
//   * On Windows, opengl32.dll only exports OpenGL 1.1. Everything past that has to
//     be fetched with wglGetProcAddress, which needs a current GL context to answer;
//     the native library is loaded before any context exists.
//   * On macOS, OpenGL is deprecated and only reachable through the system framework,
//     whose symbols differ again.
//   * On Android the host is GLES through a translation layer, and LWJGL is not the
//     only possible binding.
//
// So the calls are routed through the host, which already has a working binding for
// its platform. The table exists to make that cost one indirect call per *state
// change* rather than one per array: the cache in wrgl_cache.hpp decides what to send,
// and only the survivors reach the host.
//
// The signatures are the ordinary C ones, so a host is free to fill the table with
// LWJGL's native entry points, its own dlopen-ed symbols, or - in the test build - a
// recording stub. That last case is what makes the batching logic testable with no GL
// context at all.

#pragma once

#include <cstdint>

namespace wrobj {

/// The GL operations the draw path performs.
///
/// Deliberately minimal. Anything the Java side already owns - shader binding, texture
/// binding, blend and depth state, the sampler uniforms - is *not* here, because moving
/// it across the boundary would cost more than it saves and would duplicate logic that
/// depends on a live client.
struct GlEmit {
    /// Opaque host pointer, passed back to every callback.
    void* context = nullptr;

    /// glVertexAttrib4f(location, x, y, z, w)
    void (*attrib4f)(void* context, int32_t location, float x, float y, float z, float w) = nullptr;

    /// glVertexAttribI2i(location, x, y), with a glVertexAttrib2f fallback for gl4ES.
    void (*attribI2i)(void* context, int32_t location, int32_t x, int32_t y) = nullptr;

    /// The gl4ES fallback for attribI2i, used when integer attributes are unsupported.
    void (*attrib2f)(void* context, int32_t location, float x, float y) = nullptr;

    /// glBindVertexArray(vao)
    void (*bindVertexArray)(void* context, uint32_t vao) = nullptr;

    /// glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0)
    void (*drawElements)(void* context, int32_t count) = nullptr;

    /// True when the host reports that integer vertex attributes are unusable and the
    /// float fallback must be taken. Set once, at registration.
    bool useFloatAttributeFallback = false;

    /// True when every entry point above is present. Checked before any draw.
    bool isComplete() const {
        return attrib4f != nullptr
                && attribI2i != nullptr
                && attrib2f != nullptr
                && bindVertexArray != nullptr
                && drawElements != nullptr;
    }
};

/// Attribute locations, matching VertexAttributeType's declarations in the mapping.
enum GlAttributeLocation {
    GL_ATTR_POSITION = 0,
    GL_ATTR_COLOR = 1,
    GL_ATTR_UV_TEXTURE = 2,
    GL_ATTR_UV_OVERLAY = 3,
    GL_ATTR_UV_LIGHTMAP = 4,
    GL_ATTR_NORMAL = 5,
    /// The model matrix occupies four consecutive locations, 6 through 9.
    GL_ATTR_MATRIX_MODEL = 6
};

/// The number of attribute slots the model matrix spans.
constexpr int32_t GL_ATTR_MATRIX_SPAN = 4;

} // namespace wrobj
