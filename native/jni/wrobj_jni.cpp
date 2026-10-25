// SPDX-License-Identifier: MIT
//
// wrobj_jni.cpp — JNI bridge exposing the native OBJ pipeline to the mod.
//
// Design notes:
//
//  * The bridge is deliberately thin. All geometry logic lives in the core
//    library, which is also linked by the native tests, so anything verified there
//    is verified for the mod as well.
//
//  * Strings cross the boundary as UTF-8 byte arrays rather than jstring. OBJ and
//    MTL content is megabytes of text on a hot load path, and a byte array avoids
//    the UTF-16 conversion GetStringUTFChars would force. The Java side reads
//    resources as bytes already.
//
//  * Result buffers cross as direct ByteBuffers over the native allocation rather
//    than copies. A finished model can be tens of megabytes, and copying it into a
//    Java array just to hand it to glBufferData would defeat the point of the
//    rewrite. The Java side calls nativeFreeResult once the GL upload happened.
//
//  * Class, method and field lookups happen once in JNI_OnLoad and are cached as
//    globals, so nothing on the hot path performs a name lookup.
//
//  * A pending Java exception from the texture resolver short-circuits the work:
//    the exception is left set and the native call reports failure.

#include "jni_version_compat.hpp"

#include <cstring>
#include <string>
#include <vector>

#include "wrobj/wrobj.hpp"

// Implemented in wrgl_jni.cpp, which needs the JavaVM for the draw list's GL callbacks
// but cannot export its own JNI_OnLoad.
extern "C" void wrgl_jni_set_vm(JavaVM* vm);
extern "C" void wrgl_jni_clear_vm();

namespace {

/// Cached handles, populated in JNI_OnLoad.
struct Handles {
    jclass keyClass = nullptr;
    jmethodID keyConstructor = nullptr;

    jclass drawClass = nullptr;
    jmethodID drawConstructor = nullptr;

    jclass resultClass = nullptr;
    jmethodID resultConstructor = nullptr;

    jclass resolverClass = nullptr;
    jmethodID resolveMethod = nullptr;
};

Handles gHandles;
bool gInitialised = false;

jclass findGlobalClass(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    if (local == nullptr) {
        return nullptr;
    }
    auto* global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
}

jobject makeDirectBuffer(JNIEnv* env, void* data, jint length) {
    if (data == nullptr || length <= 0) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(data, length);
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

const char* byteArrayData(JNIEnv* env, jbyteArray array, jbyte** outElements, jsize* outLength) {
    *outElements = nullptr;
    *outLength = 0;
    if (array == nullptr) {
        return nullptr;
    }
    *outLength = env->GetArrayLength(array);
    if (*outLength == 0) {
        return nullptr;
    }
    *outElements = env->GetByteArrayElements(array, nullptr);
    return reinterpret_cast<const char*>(*outElements);
}

void releaseByteArray(JNIEnv* env, jbyteArray array, jbyte* elements) {
    if (array != nullptr && elements != nullptr) {
        // JNI_ABORT: the bridge never writes back, so skip the copy.
        env->ReleaseByteArrayElements(array, elements, JNI_ABORT);
    }
}

std::vector<int32_t> readIntArray(JNIEnv* env, jintArray array) {
    std::vector<int32_t> values;
    if (array == nullptr) {
        return values;
    }
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) {
        return values;
    }
    values.resize(static_cast<size_t>(length));
    env->GetIntArrayRegion(array, 0, length, reinterpret_cast<jint*>(values.data()));
    return values;
}

/// Read a boolean array as 0/1 ints, so the core API stays free of jboolean.
std::vector<int32_t> readBooleanArray(JNIEnv* env, jbooleanArray array) {
    std::vector<int32_t> values;
    if (array == nullptr) {
        return values;
    }
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) {
        return values;
    }
    std::vector<jboolean> raw(static_cast<size_t>(length));
    env->GetBooleanArrayRegion(array, 0, length, raw.data());
    values.resize(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        values[static_cast<size_t>(i)] = raw[static_cast<size_t>(i)] == JNI_TRUE ? 1 : 0;
    }
    return values;
}

/// Resolve every key's texture through the host, reporting failure on exception.
///
/// The host is given the shader type alongside the texture string because that pair
/// is what MaterialProperties' identity is built from: two keys can share a texture
/// string and still be different materials.
bool resolveTextures(
        JNIEnv* env,
        const WrObjKeyList* keys,
        jobject resolver,
        std::vector<int32_t>& handles,
        std::vector<int32_t>& colorSet
) {
    handles.assign(static_cast<size_t>(keys->count), -1);
    colorSet.assign(static_cast<size_t>(keys->count), 0);

    for (int32_t i = 0; i < keys->count; i++) {
        const WrObjKeyInfo& info = keys->entries[i];
        colorSet[static_cast<size_t>(i)] = info.hasColor ? 1 : 0;

        if (resolver == nullptr) {
            continue;
        }
        jstring texture = env->NewStringUTF(info.textureString == nullptr ? "" : info.textureString);
        if (texture == nullptr) {
            continue;
        }
        const jint handle = env->CallIntMethod(resolver, gHandles.resolveMethod, texture,
                static_cast<jint>(info.shaderType));
        env->DeleteLocalRef(texture);
        if (env->ExceptionCheck()) {
            return false;
        }
        handles[static_cast<size_t>(i)] = handle;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    // The draw list bridge needs the JavaVM for its GL callbacks.
    wrgl_jni_set_vm(vm);
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    gHandles.keyClass = findGlobalClass(env, "org/mtr/mod/resource/nativeobj/NativeObjKey");
    gHandles.drawClass = findGlobalClass(env, "org/mtr/mod/resource/nativeobj/NativeObjDraw");
    gHandles.resultClass = findGlobalClass(env, "org/mtr/mod/resource/nativeobj/NativeObjResult");
    gHandles.resolverClass = findGlobalClass(env, "org/mtr/mod/resource/nativeobj/NativeTextureResolver");

    if (gHandles.keyClass == nullptr || gHandles.drawClass == nullptr
            || gHandles.resultClass == nullptr || gHandles.resolverClass == nullptr) {
        return JNI_ERR;
    }

    gHandles.keyConstructor = env->GetMethodID(gHandles.keyClass, "<init>",
            "(ILjava/lang/String;Ljava/lang/String;IZZ)V");
    gHandles.drawConstructor = env->GetMethodID(gHandles.drawClass, "<init>", "(IIIZIIIII)V");
    gHandles.resultConstructor = env->GetMethodID(gHandles.resultClass, "<init>",
            "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;"
            "[Lorg/mtr/mod/resource/nativeobj/NativeObjDraw;JJ)V");
    gHandles.resolveMethod = env->GetMethodID(gHandles.resolverClass, "resolve",
            "(Ljava/lang/String;I)I");

    if (gHandles.keyConstructor == nullptr || gHandles.drawConstructor == nullptr
            || gHandles.resultConstructor == nullptr || gHandles.resolveMethod == nullptr) {
        return JNI_ERR;
    }

    gInitialised = true;
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    wrgl_jni_clear_vm();
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) {
        return;
    }
    if (gHandles.keyClass != nullptr) {
        env->DeleteGlobalRef(gHandles.keyClass);
    }
    if (gHandles.drawClass != nullptr) {
        env->DeleteGlobalRef(gHandles.drawClass);
    }
    if (gHandles.resultClass != nullptr) {
        env->DeleteGlobalRef(gHandles.resultClass);
    }
    if (gHandles.resolverClass != nullptr) {
        env->DeleteGlobalRef(gHandles.resolverClass);
    }
    gHandles = Handles();
    gInitialised = false;
}

JNIEXPORT jint JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeAbiVersion(
        JNIEnv*, jclass) {
    return wr_obj_abi_version();
}

JNIEXPORT jstring JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeVersionString(
        JNIEnv* env, jclass) {
    return env->NewStringUTF(wr_obj_version_string());
}

JNIEXPORT jobjectArray JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeLoadKeys(
        JNIEnv* env,
        jclass,
        jbyteArray objContent,
        jbyteArray mtlContent,
        jboolean flipTextureV
) {
    if (!gInitialised) {
        throwIllegalState(env, "the wrobj bridge was not initialised");
        return nullptr;
    }

    jbyte* objElements = nullptr;
    jbyte* mtlElements = nullptr;
    jsize objLength = 0;
    jsize mtlLength = 0;
    const char* objData = byteArrayData(env, objContent, &objElements, &objLength);
    const char* mtlData = byteArrayData(env, mtlContent, &mtlElements, &mtlLength);

    WrObjKeyList* keys = wr_obj_keys_load(objData, objLength, mtlData, mtlLength,
            flipTextureV ? 1 : 0);

    releaseByteArray(env, mtlContent, mtlElements);
    releaseByteArray(env, objContent, objElements);

    if (keys == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(keys->count, gHandles.keyClass, nullptr);
    if (result == nullptr) {
        wr_obj_keys_free(keys);
        return nullptr;
    }

    for (int32_t i = 0; i < keys->count; i++) {
        const WrObjKeyInfo& info = keys->entries[i];
        jstring key = env->NewStringUTF(info.key == nullptr ? "" : info.key);
        jstring texture = env->NewStringUTF(info.textureString == nullptr ? "" : info.textureString);
        jobject entry = env->NewObject(gHandles.keyClass, gHandles.keyConstructor,
                static_cast<jint>(info.shaderType),
                key,
                texture,
                static_cast<jint>(info.color),
                info.hasColor != 0 ? JNI_TRUE : JNI_FALSE,
                info.flipTextureV != 0 ? JNI_TRUE : JNI_FALSE);
        if (key != nullptr) {
            env->DeleteLocalRef(key);
        }
        if (texture != nullptr) {
            env->DeleteLocalRef(texture);
        }
        if (entry == nullptr) {
            wr_obj_keys_free(keys);
            return nullptr;
        }
        env->SetObjectArrayElement(result, i, entry);
        env->DeleteLocalRef(entry);
    }

    wr_obj_keys_free(keys);
    return result;
}

JNIEXPORT jlong JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeCreateBuilder(
        JNIEnv* env,
        jclass,
        jbyteArray objContent,
        jbyteArray mtlContent,
        jboolean materialGroupTextures,
        jboolean flipTextureV,
        jobject textureResolver
) {
    if (!gInitialised) {
        throwIllegalState(env, "the wrobj bridge was not initialised");
        return 0;
    }

    jbyte* objElements = nullptr;
    jbyte* mtlElements = nullptr;
    jsize objLength = 0;
    jsize mtlLength = 0;
    const char* objData = byteArrayData(env, objContent, &objElements, &objLength);
    const char* mtlData = byteArrayData(env, mtlContent, &mtlElements, &mtlLength);

    if (objData == nullptr) {
        releaseByteArray(env, mtlContent, mtlElements);
        releaseByteArray(env, objContent, objElements);
        return 0;
    }

    // Keys are discovered first so the host can resolve texture identity per key;
    // the builder needs the resolved handles up front because add_transform merges
    // by MaterialProperties, exactly as RawModel.append does.
    WrObjKeyList* keys = wr_obj_keys_load(objData, objLength, mtlData, mtlLength,
            flipTextureV ? 1 : 0);
    if (keys == nullptr) {
        releaseByteArray(env, mtlContent, mtlElements);
        releaseByteArray(env, objContent, objElements);
        return 0;
    }

    std::vector<int32_t> handles;
    std::vector<int32_t> colorSet;
    const bool resolved = resolveTextures(env, keys, textureResolver, handles, colorSet);
    if (!resolved) {
        wr_obj_keys_free(keys);
        releaseByteArray(env, mtlContent, mtlElements);
        releaseByteArray(env, objContent, objElements);
        return 0;
    }

    WrObjBuilder* builder = wr_obj_builder_create(
            objData, objLength, mtlData, mtlLength,
            handles.data(), colorSet.data(), keys->count,
            materialGroupTextures ? 1 : 0,
            flipTextureV ? 1 : 0);

    wr_obj_keys_free(keys);
    releaseByteArray(env, mtlContent, mtlElements);
    releaseByteArray(env, objContent, objElements);

    return reinterpret_cast<jlong>(builder);
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeFreeBuilder(
        JNIEnv*,
        jclass,
        jlong handle
) {
    wr_obj_builder_free(reinterpret_cast<WrObjBuilder*>(handle));
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeApplyTransform(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint operation,
        jdouble a,
        jdouble b,
        jdouble c
) {
    auto* builder = reinterpret_cast<WrObjBuilder*>(handle);
    if (builder == nullptr) {
        throwIllegalState(env, "the wrobj builder handle is null");
        return;
    }

    switch (operation) {
        case 0: // translation
            wr_obj_builder_apply_translation(builder, a, b, c);
            break;
        case 1: // rotation, degrees per axis
            wr_obj_builder_apply_rotation(builder, a, b, c);
            break;
        case 2: // scale
            wr_obj_builder_apply_scale(builder, a, b, c);
            break;
        case 3: // mirror, one flag per axis
            wr_obj_builder_apply_mirror(builder,
                    a != 0.0 ? 1 : 0, b != 0.0 ? 1 : 0, c != 0.0 ? 1 : 0);
            break;
        default:
            throwIllegalState(env, "unknown wrobj transform operation");
            break;
    }
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeAddTransform(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint shaderType,
        jdouble x,
        jdouble y,
        jdouble z,
        jboolean flipped
) {
    auto* builder = reinterpret_cast<WrObjBuilder*>(handle);
    if (builder == nullptr) {
        throwIllegalState(env, "the wrobj builder handle is null");
        return;
    }
    wr_obj_builder_add_transform(builder, shaderType, x, y, z, flipped ? 1 : 0);
}

JNIEXPORT jfloatArray JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeGetBounds(
        JNIEnv* env,
        jclass,
        jlong handle
) {
    auto* builder = reinterpret_cast<WrObjBuilder*>(handle);
    if (builder == nullptr) {
        return nullptr;
    }

    float bounds[6] = {};
    wr_obj_builder_get_bounds(builder, &bounds[0], &bounds[1], &bounds[2],
            &bounds[3], &bounds[4], &bounds[5]);

    jfloatArray result = env->NewFloatArray(6);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, 6, bounds);
    }
    return result;
}

JNIEXPORT jobject JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeBuild(
        JNIEnv* env,
        jclass,
        jlong handle
) {
    if (!gInitialised) {
        throwIllegalState(env, "the wrobj bridge was not initialised");
        return nullptr;
    }

    auto* builder = reinterpret_cast<WrObjBuilder*>(handle);
    if (builder == nullptr) {
        throwIllegalState(env, "the wrobj builder handle is null");
        return nullptr;
    }

    WrObjResult* result = wr_obj_builder_build(builder);
    if (result == nullptr) {
        return nullptr;
    }

    jobjectArray draws = env->NewObjectArray(result->drawCount, gHandles.drawClass, nullptr);
    if (draws == nullptr) {
        wr_obj_result_free(result);
        return nullptr;
    }

    const auto* drawData = static_cast<const WrObjResultDraw*>(result->drawData);
    for (int32_t i = 0; i < result->drawCount; i++) {
        const WrObjResultDraw& draw = drawData[i];
        jobject entry = env->NewObject(gHandles.drawClass, gHandles.drawConstructor,
                static_cast<jint>(draw.shaderType),
                static_cast<jint>(draw.color),
                static_cast<jint>(draw.textureHandle),
                draw.hasColor != 0 ? JNI_TRUE : JNI_FALSE,
                static_cast<jint>(draw.keyIndex),
                static_cast<jint>(draw.vertexOffset),
                static_cast<jint>(draw.vertexCount),
                static_cast<jint>(draw.indexOffset),
                static_cast<jint>(draw.indexCount));
        if (entry == nullptr) {
            wr_obj_result_free(result);
            return nullptr;
        }
        env->SetObjectArrayElement(draws, i, entry);
        env->DeleteLocalRef(entry);
    }

    jobject vertexBuffer = makeDirectBuffer(env, result->vertexData, result->vertexDataLength);
    jobject indexBuffer = makeDirectBuffer(env, result->indexData, result->indexDataLength);

    jobject output = env->NewObject(gHandles.resultClass, gHandles.resultConstructor,
            vertexBuffer, indexBuffer, draws,
            static_cast<jlong>(reinterpret_cast<intptr_t>(result)),
            static_cast<jlong>(result->estimatedJavaHeapBytes));

    if (vertexBuffer != nullptr) {
        env->DeleteLocalRef(vertexBuffer);
    }
    if (indexBuffer != nullptr) {
        env->DeleteLocalRef(indexBuffer);
    }
    env->DeleteLocalRef(draws);

    return output;
}

JNIEXPORT void JNICALL Java_org_mtr_mod_resource_nativeobj_NativeObjPipeline_nativeFreeResult(
        JNIEnv*,
        jclass,
        jlong handle
) {
    wr_obj_result_free(reinterpret_cast<WrObjResult*>(handle));
}

} // extern "C"
