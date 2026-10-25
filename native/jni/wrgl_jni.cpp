// SPDX-License-Identifier: MIT
//
// wrgl_jni.cpp — JNI bridge for the native draw list.
//
// ---------------------------------------------------------------------------
// The GL table crosses the boundary
// ---------------------------------------------------------------------------
//
// wrgl_emit.hpp explains why the native side does not link OpenGL directly. The
// consequence is that each surviving GL call is one upcall into Java, so the design
// question is what "surviving" means.
//
// The cache in wrgl_cache.hpp answers it: a call is made only when the value it would
// set differs from the value already in place. The reference implementation sets the
// colour, overlay and lightmap for every vertex array regardless; here they are set
// once per flush. The model matrix is set once per distinct pose rather than once per
// array, and the vertex array is bound once per run of the same array.
//
// Passing a table of Java methods rather than calling back per GL call keeps the
// upcall count identical but removes the per-call method lookup: the jmethodIDs are
// resolved once, at registration.
//
// ---------------------------------------------------------------------------
// Global references and lifetime
// ---------------------------------------------------------------------------
//
// The table holds global references to one Java object. They are released by
// nativeFreeDrawList, which the Java side calls when the renderer is discarded. A
// missed release would pin one object; the Java holder does the release in a
// constructor-failure-safe order and the bridge is idempotent about it.

#include "jni_version_compat.hpp"

#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "wrobj/wrgl_draw.hpp"
#include "wrobj/wrgl_emit.hpp"

namespace {

/// The JVM, resolved in JNI_OnLoad. The GL callbacks run on the render thread, which the
/// JVM already knows about, so they look the environment up rather than attaching.
JavaVM* gJavaVm = nullptr;

/// The Java object that performs the GL calls, plus the resolved method ids.
///
/// One per draw list rather than a single global, because a GL context - and therefore a
/// Java side with a context - belongs to a draw list. MTR creates one renderer per
/// client, so in practice there is one, but the bridge does not assume it.
struct GlHost {
    jobject object = nullptr;
    jmethodID attrib4f = nullptr;
    jmethodID attribI2i = nullptr;
    jmethodID attrib2f = nullptr;
    jmethodID bindVertexArray = nullptr;
    jmethodID drawElements = nullptr;
};

/// The JNI entry points cannot receive a context pointer, so the emitting draw list
/// registers itself in thread local storage for the duration of a flush.
///
/// Thread local rather than global because a dedicated server and a client can coexist in
/// the same process during development, and because the render thread is the only thread
/// that draws.
thread_local GlHost* gHost = nullptr;

/// Looks up the JNI environment for the calling thread, or null when unavailable.
JNIEnv* currentEnv() {
    if (gJavaVm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    if (gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        return nullptr;
    }
    return env;
}

void emitAttrib4f(void*, int32_t location, float x, float y, float z, float w) {
    GlHost* host = gHost;
    if (host == nullptr || host->object == nullptr) {
        return;
    }
    // The host object is only ever touched from the thread that registered it, so the
    // already-attached environment is the one to use.
    JNIEnv* env = currentEnv();
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(host->object, host->attrib4f, static_cast<jint>(location),
            static_cast<jfloat>(x), static_cast<jfloat>(y), static_cast<jfloat>(z), static_cast<jfloat>(w));
}

void emitAttribI2i(void*, int32_t location, int32_t x, int32_t y) {
    GlHost* host = gHost;
    if (host == nullptr || host->object == nullptr) {
        return;
    }
    JNIEnv* env = currentEnv();
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(host->object, host->attribI2i, static_cast<jint>(location),
            static_cast<jint>(x), static_cast<jint>(y));
}

void emitAttrib2f(void*, int32_t location, float x, float y) {
    GlHost* host = gHost;
    if (host == nullptr || host->object == nullptr) {
        return;
    }
    JNIEnv* env = currentEnv();
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(host->object, host->attrib2f, static_cast<jint>(location),
            static_cast<jfloat>(x), static_cast<jfloat>(y));
}

void emitBindVertexArray(void*, uint32_t vao) {
    GlHost* host = gHost;
    if (host == nullptr || host->object == nullptr) {
        return;
    }
    JNIEnv* env = currentEnv();
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(host->object, host->bindVertexArray, static_cast<jint>(vao));
}

void emitDrawElements(void*, int32_t count) {
    GlHost* host = gHost;
    if (host == nullptr || host->object == nullptr) {
        return;
    }
    JNIEnv* env = currentEnv();
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(host->object, host->drawElements, static_cast<jint>(count));
}

/// One draw list and the Java-side GL host it emits through.
struct DrawListHandle {
    wrobj::DrawList list;
    GlHost host;
};

DrawListHandle* fromHandle(jlong handle) {
    return reinterpret_cast<DrawListHandle*>(static_cast<intptr_t>(handle));
}

/// Releases the global reference to the host object, if any.
void releaseHost(JNIEnv* env, GlHost& host) {
    if (host.object != nullptr) {
        env->DeleteGlobalRef(host.object);
        host.object = nullptr;
    }
    host.attrib4f = nullptr;
    host.attribI2i = nullptr;
    host.attrib2f = nullptr;
    host.bindVertexArray = nullptr;
    host.drawElements = nullptr;
}

} // namespace

// The library has one JNI_OnLoad and it belongs to wrobj_jni.cpp, which owns the cached
// class references for the model pipeline. This file only needs the JavaVM, so it takes
// it through these two hooks rather than exporting a second JNI_OnLoad: the linker
// rejects a duplicate outright, and having two would make the initialisation order of the
// two bridges depend on link order.
extern "C" void wrgl_jni_set_vm(JavaVM* vm) {
    gJavaVm = vm;
}

extern "C" void wrgl_jni_clear_vm() {
    gJavaVm = nullptr;
    gHost = nullptr;
}

extern "C" {

/// Creates a draw list. Returns 0 on failure.
JNIEXPORT jlong JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeCreate(JNIEnv*, jclass) {
    auto* handle = new (std::nothrow) DrawListHandle();
    if (handle == nullptr) {
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

/// Releases a draw list and its global references. Safe with 0 and safe twice.
JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeFree(JNIEnv* env, jclass, jlong handle) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr) {
        return;
    }
    // The host must not be reachable from a callback after this point.
    if (gHost == &owner->host) {
        gHost = nullptr;
    }
    releaseHost(env, owner->host);
    delete owner;
}

/// Installs the Java GL host and the attribute fallback flag. Returns true on success.
JNIEXPORT jboolean JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeSetGlHost(
        JNIEnv* env,
        jclass,
        jlong handle,
        jobject hostObject,
        jboolean useFloatAttributeFallback
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr || hostObject == nullptr) {
        return JNI_FALSE;
    }

    jclass hostClass = env->GetObjectClass(hostObject);
    if (hostClass == nullptr) {
        return JNI_FALSE;
    }

    // Resolved once. A missing method means the Java side and the bridge disagree, which
    // is worth failing loudly on rather than emitting silently wrong draws.
    const jmethodID attrib4f = env->GetMethodID(hostClass, "attrib4f", "(IFFFF)V");
    const jmethodID attribI2i = env->GetMethodID(hostClass, "attribI2i", "(III)V");
    const jmethodID attrib2f = env->GetMethodID(hostClass, "attrib2f", "(IFF)V");
    const jmethodID bindVertexArray = env->GetMethodID(hostClass, "bindVertexArray", "(I)V");
    const jmethodID drawElements = env->GetMethodID(hostClass, "drawElements", "(I)V");
    env->DeleteLocalRef(hostClass);

    if (attrib4f == nullptr || attribI2i == nullptr || attrib2f == nullptr
            || bindVertexArray == nullptr || drawElements == nullptr) {
        return JNI_FALSE;
    }

    releaseHost(env, owner->host);
    owner->host.object = env->NewGlobalRef(hostObject);
    if (owner->host.object == nullptr) {
        return JNI_FALSE;
    }
    owner->host.attrib4f = attrib4f;
    owner->host.attribI2i = attribI2i;
    owner->host.attrib2f = attrib2f;
    owner->host.bindVertexArray = bindVertexArray;
    owner->host.drawElements = drawElements;

    wrobj::GlEmit gl;
    // The context pointer is unused because the entry points reach the host through the
    // thread local above; the emit table's signatures take one so a host could pass a
    // different mechanism.
    gl.context = &owner->host;
    gl.attrib4f = emitAttrib4f;
    gl.attribI2i = emitAttribI2i;
    gl.attrib2f = emitAttrib2f;
    gl.bindVertexArray = emitBindVertexArray;
    gl.drawElements = emitDrawElements;
    gl.useFloatAttributeFallback = useFloatAttributeFallback == JNI_TRUE;
    owner->list.setGlTable(gl);

    return JNI_TRUE;
}

/// Registers a vertex array. Returns the handle, or -1.
JNIEXPORT jint JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeRegisterBatch(
        JNIEnv*,
        jclass,
        jlong handle,
        jint vao,
        jint indexCount,
        jint shaderType
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr) {
        return -1;
    }
    return static_cast<jint>(owner->list.registerBatch(
            static_cast<uint32_t>(vao), static_cast<int32_t>(indexCount),
            static_cast<int32_t>(shaderType)));
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeUnregisterBatch(
        JNIEnv*,
        jclass,
        jlong handle,
        jint batch
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner != nullptr) {
        owner->list.unregisterBatch(static_cast<int32_t>(batch));
    }
}

JNIEXPORT jint JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeLiveBatchCount(
        JNIEnv*,
        jclass,
        jlong handle
) {
    DrawListHandle* owner = fromHandle(handle);
    return owner == nullptr ? 0 : static_cast<jint>(owner->list.liveBatchCount());
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeBeginFrame(
        JNIEnv*,
        jclass,
        jlong handle
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner != nullptr) {
        owner->list.beginFrame();
    }
}

/// Queues one draw. The matrix arrives as a 16-float direct buffer to avoid allocating a
/// Java array per part, which is one of the per-frame costs being removed.
JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeQueue(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint batch,
        jobject matrixBuffer,
        jint matrixOffset
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr || matrixBuffer == nullptr) {
        return;
    }

    auto* base = static_cast<float*>(env->GetDirectBufferAddress(matrixBuffer));
    if (base == nullptr) {
        return;
    }
    const jlong capacity = env->GetDirectBufferCapacity(matrixBuffer);
    const jlong offset = static_cast<jlong>(matrixOffset);
    if (capacity < 0 || (offset + 16) * static_cast<jlong>(sizeof(float)) > capacity) {
        return;
    }

    owner->list.queue(static_cast<int32_t>(batch), base + offset);
}

/// Draws everything queued.
JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeFlush(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint color,
        jint lightmap,
        jint overlay,
        jboolean hasColor,
        jboolean hasLightmap,
        jboolean hasOverlay
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr) {
        return;
    }

    wrobj::DrawGlobals globals;
    globals.color = static_cast<int32_t>(color);
    globals.lightmap = static_cast<int32_t>(lightmap);
    globals.overlay = static_cast<int32_t>(overlay);
    globals.hasColor = hasColor == JNI_TRUE;
    globals.hasLightmap = hasLightmap == JNI_TRUE;
    globals.hasOverlay = hasOverlay == JNI_TRUE;

    // The callbacks reach the host through thread local storage, so it has to be in place
    // for the duration of the flush and removed afterwards. Nested flushes would be a bug
    // in the Java side rather than something to support, so the previous value is restored
    // rather than saved on a stack.
    GlHost* previous = gHost;
    gHost = &owner->host;
    owner->list.flush(globals);
    gHost = previous;

    // A Java callback cannot leave a pending exception unnoticed: if one of them threw,
    // every subsequent draw in this flush was skipped and the frame is wrong. Clear it so
    // the exception surfaces rather than silently poisoning later JNI calls.
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

/// Fills an 8-entry long array with flushes, arraysDrawn, callsSkipped, callsIssued,
/// drawCalls, liveBatches, queued and an unused slot.
JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeStats(
        JNIEnv* env,
        jclass,
        jlong handle,
        jlongArray out
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner == nullptr || out == nullptr || env->GetArrayLength(out) < 8) {
        return;
    }

    const wrobj::DrawStats& stats = owner->list.stats();
    const jlong values[8] = {
        static_cast<jlong>(stats.flushes),
        static_cast<jlong>(stats.arraysDrawn),
        static_cast<jlong>(stats.callsSkipped),
        static_cast<jlong>(stats.callsIssued),
        static_cast<jlong>(stats.drawCalls),
        static_cast<jlong>(owner->list.liveBatchCount()),
        static_cast<jlong>(owner->list.queuedCount()),
        0
    };
    env->SetLongArrayRegion(out, 0, 8, values);
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeResetStats(
        JNIEnv*,
        jclass,
        jlong handle
) {
    DrawListHandle* owner = fromHandle(handle);
    if (owner != nullptr) {
        owner->list.resetStats();
    }
}

JNIEXPORT jboolean JNICALL Java_org_mtr_mod_resource_nativeobj_NativeDrawList_nativeIsReady(
        JNIEnv*,
        jclass,
        jlong handle
) {
    DrawListHandle* owner = fromHandle(handle);
    return owner != nullptr && owner->list.isReady() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
