# SPDX-License-Identifier: MIT
#
# Native OBJ model pipeline for Minecraft Transit Railway.
#
# Two targets, with names chosen so the Windows import libraries do not collide:
#
#   wrobj_core   static core library, no JVM dependency. Built and tested on its
#                own so the equivalence harness can link it without a JVM.
#   wrobj        shared library loaded by the mod. Its OUTPUT_NAME is "wrobj"
#                because that is the name the Java side passes to System.load.
#
# Nothing here depends on JNI headers except the bridge, and the bridge is only
# built when a JDK is found. That keeps `cmake --build` usable on a machine with
# no JDK, which is how the pure-native tests run in CI.

cmake_minimum_required(VERSION 3.16)

project(wrobj
        VERSION 1.0.0
        DESCRIPTION "Native OBJ model pipeline for Minecraft Transit Railway"
        LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# ---------------------------------------------------------------------------
# Apple mobile
# ---------------------------------------------------------------------------
#
# iOS and iPadOS are one target. CMAKE_SYSTEM_NAME is "iOS" for both, because Apple compiles iPad
# apps against the iOS platform: there is no iPadOS SDK and no separate platform identifier in the
# Mach-O, so a single iphoneos arm64 binary is what an iPhone and an iPad both load. The two
# directories in the jar exist so that a device labelled either way finds the library, not because
# the bytes differ.
#
# Configure with:
#   cmake -S native -B native/build-ios \
#     -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64 \
#     -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 \
#     -DCMAKE_OSX_SYSROOT=iphoneos \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/... (not used - this is the Apple toolchain)
#
# CMAKE_SYSTEM_NAME=iOS needs CMake 3.14 or newer, which is why ios-cmake-style toolchains are not
# required here. The framework is the platform's own.
set(WROBJ_APPLE_MOBILE FALSE)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(WROBJ_APPLE_MOBILE TRUE)
    if(NOT CMAKE_OSX_DEPLOYMENT_TARGET)
        set(CMAKE_OSX_DEPLOYMENT_TARGET "13.0")
    endif()
    set(WROBJ_APPLE_MOBILE_MIN "${CMAKE_OSX_DEPLOYMENT_TARGET}")
    message(STATUS "wrobj: building for iOS/iPadOS, minimum ${WROBJ_APPLE_MOBILE_MIN}")
endif()

# Both artefacts land directly in the build root whatever the generator.
#
# This matters more than it looks. CMake chooses a generator per platform: Ninja on Linux
# and macOS, and on the GitHub Windows runners the default is a multi-config generator,
# which appends a per-configuration directory to the output path. The CI upload step and
# fabric/build.gradle both look the library up by name, so an implicit subdirectory turns a
# successful build into "the artefact was not produced".
#
# These are set as variables rather than with set_target_properties on purpose: CMake
# initialises each target's output directory properties from these variables *after* the
# target is created, so setting the variable before the targets is what actually takes
# effect, and setting them afterwards for every configuration is what covers the
# multi-config generators.
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
foreach(_config IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set("CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_LIBRARY_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_RUNTIME_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
endforeach()

# The internal headers are private; only include/wrobj/wrobj.hpp is public.
set(WROBJ_PUBLIC_HEADERS
        include/wrobj/wrobj.hpp)

set(WROBJ_PRIVATE_HEADERS
        include/wrobj/wrobj_model.hpp
        include/wrobj/wrobj_math.hpp
        include/wrobj/wrgl_emit.hpp
        include/wrobj/wrgl_cache.hpp
        include/wrobj/wrgl_draw.hpp)

set(WROBJ_SOURCES
        src/wrobj.cpp
        src/wrobj_mesh.cpp
        src/wrobj_parse.cpp
        src/wrobj_pipeline.cpp
        src/wrgl_cache.cpp
        src/wrgl_draw.cpp)

add_library(wrobj_core STATIC ${WROBJ_SOURCES} ${WROBJ_PUBLIC_HEADERS} ${WROBJ_PRIVATE_HEADERS})

target_include_directories(wrobj_core
        PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

set_target_properties(wrobj_core PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

# A stable alias so dependents do not need to know the target was renamed to keep
# the JNI import library clear of it.
add_library(wrobj::core ALIAS wrobj_core)

if(MSVC)
    # /fp:precise is the default and is required: the whole point of this library
    # is bit-identical floating point results against the JVM. Never enable
    # /fp:fast here.
    target_compile_options(wrobj_core PRIVATE /W4 /permissive- /utf-8 /fp:precise)
    target_compile_definitions(wrobj_core PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
else()
    target_compile_options(wrobj_core PRIVATE -Wall -Wextra -Wpedantic)
    # Contracting a*b+c into a fused multiply-add changes the last bit of the result, and this
    # library exists to reproduce the JVM's arithmetic bit for bit, so contraction has to be off
    # everywhere.
    #
    # This was guarded to non-Clang compilers, which is exactly backwards: gcc defaults to
    # -ffp-contract=fast and needs the flag, while clang defaults to -ffp-contract=on and needs
    # it just as much. Both accept it. macOS is built with AppleClang, so the guard meant the one
    # platform that could not be tested locally was also the one where every rotation, scale and
    # normal would have differed from the JVM in the last bit. Windows (MSVC, /fp:precise) and
    # Linux (gcc) both had it.
    target_compile_options(wrobj_core PRIVATE -ffp-contract=off)
endif()

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
#
# find_package(JNI) honours JAVA_HOME, but its own discovery is unreliable: it misses a JDK
# bundle on macOS and does not look in include/darwin. A configure that silently builds no
# shared library is the failure mode this guards against, because the static core and the
# tests still build, so nothing looks wrong until the artefact is missing.
#
# Building the bridge without the JNI headers is impossible, so a missing JDK still only
# disables the shared library rather than failing the configure step - that is what lets a
# contributor without a JDK build and run the native tests.

find_package(JNI QUIET)

# When FindJNI comes up empty, the JDK is located by other means and its headers probed
# directly.
#
# Three independent sources are tried, because the failure this guards against is silent: a
# configure that finds no JDK still builds the static core and the tests, so both suites pass
# and the only symptom is a missing shared library - which is exactly how the Linux and macOS
# CI jobs failed while Windows passed.
#
#   1. JAVA_HOME, which is what a developer machine and actions/setup-java set.
#   2. CMake's own Java module, whose detection is separate from FindJNI's and which reports
#      the JDK root through Java_JAVA_EXECUTABLE even when FindJNI failed.
#   3. The conventional installation roots, as a last resort for a machine that sets neither.
if(NOT JNI_FOUND)
    set(_wrobj_jdk_roots "")
    if(DEFINED ENV{JAVA_HOME} AND NOT "$ENV{JAVA_HOME}" STREQUAL "")
        list(APPEND _wrobj_jdk_roots "$ENV{JAVA_HOME}")
    endif()

    find_package(Java QUIET)
    if(Java_JAVA_EXECUTABLE)
        # <jdk>/bin/java -> <jdk>
        get_filename_component(_wrobj_java_bin "${Java_JAVA_EXECUTABLE}" DIRECTORY)
        get_filename_component(_wrobj_java_home "${_wrobj_java_bin}" DIRECTORY)
        list(APPEND _wrobj_jdk_roots "${_wrobj_java_home}")
    endif()

    file(GLOB _wrobj_jvm_globbed /usr/lib/jvm/* /usr/local/opt/openjdk* /opt/homebrew/opt/openjdk*)
    list(APPEND _wrobj_jdk_roots ${_wrobj_jvm_globbed})

    set(_wrobj_jni_dirs "")
    foreach(_root IN LISTS _wrobj_jdk_roots)
        foreach(_sub IN ITEMS "include" "include/darwin" "include/linux" "include/win32")
            if(EXISTS "${_root}/${_sub}/jni.h" AND EXISTS "${_root}/${_sub}/jni_md.h")
                list(APPEND _wrobj_jni_dirs "${_root}/${_sub}")
            endif()
        endforeach()
        # A JDK on Linux and macOS keeps jni.h directly under include and jni_md.h one level
        # below it, under a per-platform directory. The probe above only matches when both
        # headers sit in the same directory, so this catches the split layout.
        #
        # IS_DIRECTORY, not DIRECTORY: if() has no DIRECTORY keyword, and the invalid form is a
        # configure-time error rather than a false condition. That error only fired on the
        # platforms where find_package(JNI) had already failed - Linux and macOS - so Windows
        # configured and built normally while the other two aborted before creating the shared
        # library target at all. That is the whole reason the Linux and macOS jobs reported no
        # native library while passing every test: the tests build without JNI.
        if(EXISTS "${_root}/include/jni.h" AND IS_DIRECTORY "${_root}/include")
            file(GLOB _wrobj_jni_md "${_root}/include/*/jni_md.h")
            foreach(_md IN LISTS _wrobj_jni_md)
                get_filename_component(_md_dir "${_md}" DIRECTORY)
                list(APPEND _wrobj_jni_dirs "${_root}/include" "${_md_dir}")
            endforeach()
        endif()
    endforeach()

    if(_wrobj_jni_dirs)
        list(REMOVE_DUPLICATES _wrobj_jni_dirs)
        set(JNI_FOUND TRUE)
        set(JNI_INCLUDE_DIRS "${_wrobj_jni_dirs}")
        message(STATUS "wrobj: located the JNI headers directly: ${_wrobj_jni_dirs}")
    else()
        message(STATUS "wrobj: no JNI headers found; searched roots: ${_wrobj_jdk_roots}")
    endif()
endif()

# The Android NDK does not register with CMake's JNI find module, but it ships the headers
# in its sysroot. Without this the cross build would silently produce no shared library,
# which the packaging step would then report as a missing artefact.
#
# The sysroot is located by globbing rather than by interpolating ANDROID_HOST_TAG: that
# variable is set by some NDK toolchain files and not others, and when it is unset the
# interpolation silently yields a path containing an empty component, which then simply does
# not exist. Globbing for jni.h under the NDK finds the headers whichever host tag is in use.
if(ANDROID AND NOT JNI_FOUND)
    set(_wrobj_android_roots "")
    if(CMAKE_SYSROOT)
        list(APPEND _wrobj_android_roots "${CMAKE_SYSROOT}/usr/include")
    endif()
    if(ANDROID_NDK AND ANDROID_HOST_TAG)
        list(APPEND _wrobj_android_roots
                "${ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot/usr/include")
    endif()
    if(ANDROID_NDK)
        file(GLOB _wrobj_android_globbed
                "${ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/include")
        list(APPEND _wrobj_android_roots ${_wrobj_android_globbed})
    endif()

    set(_wrobj_android_jni_include "")
    foreach(_root IN LISTS _wrobj_android_roots)
        if(EXISTS "${_root}/jni.h")
            set(_wrobj_android_jni_include "${_root}")
            break()
        endif()
    endforeach()

    if(_wrobj_android_jni_include)
        set(JNI_FOUND TRUE)
        set(JNI_INCLUDE_DIRS "${_wrobj_android_jni_include}")
        message(STATUS "wrobj: using the NDK's JNI headers at ${_wrobj_android_jni_include}")
    else()
        message(STATUS "wrobj: no JNI headers in the NDK; searched: ${_wrobj_android_roots}")
    endif()
endif()

if(JNI_FOUND)
    add_library(wrobj SHARED
            jni/wrobj_jni.cpp
            jni/jni_version_compat.hpp
            jni/wrgl_jni.cpp)
    target_link_libraries(wrobj PRIVATE wrobj::core)
    target_include_directories(wrobj PRIVATE
            ${JNI_INCLUDE_DIRS}
            ${CMAKE_CURRENT_SOURCE_DIR}/include)

    if(ANDROID)
        # Android has no libstdc++ shared object to link against: the platform ships only the
        # C runtime, so the C++ runtime has to be inside the library or the loader refuses it
        # with an unresolved symbol. The runtime is selected with -DANDROID_STL=c++_static at
        # configure time, which is the NDK's own mechanism. -static-libstdc++ is deliberately
        # NOT passed as well: the NDK toolchain already decides how to link the runtime, and a
        # second instruction can conflict with that choice.
        #
        # -Wl,-z,max-page-size=16384 is required for Android 15 and later on 16 KB page
        # devices, and is harmless on older ones.
        target_link_options(wrobj PRIVATE "-Wl,-z,max-page-size=16384")
        # The GL calls still go through the host - see wrgl_emit.hpp - so nothing here links
        # GLES. The launcher's JVM already has a binding, and on a translation layer such as
        # gl4ES that binding is what routes to the driver.
        target_compile_definitions(wrobj PRIVATE WROBJ_ANDROID=1)
    endif()

    if(WROBJ_APPLE_MOBILE)
        # iOS and iPadOS. CMAKE_SYSTEM_NAME is iOS for both: Apple compiles iPad apps against the
        # iOS platform, so one iphoneos binary is what an iPhone and an iPad both load. There is no
        # separate iPadOS SDK and no separate platform identifier in the Mach-O.
        #
        # The load path matters here more than on desktop. A mod cannot be a system library, so the
        # .dylib is extracted from the jar into the app container and opened by absolute path. It
        # therefore needs an install name that does not point at a build directory, or the loader
        # resolves the library against a path that exists only on the build machine.
        set_target_properties(wrobj PROPERTIES
                INSTALL_NAME_DIR "@rpath"
                BUILD_WITH_INSTALL_RPATH OFF)
        # iPhone and iPad in one binary. 1,2 is the device family; leaving it at the default (1)
        # would still run on an iPad but would be described as iPhone-only in the Mach-O.
        target_compile_options(wrobj PRIVATE "-mios-version-min=${WROBJ_APPLE_MOBILE_MIN}")
    endif()

    if(MSVC)
        target_compile_options(wrobj PRIVATE /W4 /permissive- /utf-8 /fp:precise)
        target_compile_definitions(wrobj PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
    else()
        target_compile_options(wrobj PRIVATE -Wall -Wextra)
        # The core's flag, applied here too. The bridge marshals arrays rather than doing geometry,
        # but it moves floats across the boundary and there is no reason for the two translation
        # sets to disagree about contraction.
        target_compile_options(wrobj PRIVATE -ffp-contract=off)
        if(NOT ANDROID AND NOT APPLE)
            # -lm is required on Linux for the sqrt and cos the model pipeline uses. Neither macOS
            # nor iOS needs it: libm is part of libSystem and there is no separate archive to link.
            target_link_libraries(wrobj PRIVATE m)
        endif()
    endif()

    set_target_properties(wrobj PROPERTIES
            PREFIX ""
            OUTPUT_NAME "wrobj")

    message(STATUS "wrobj: JNI bridge enabled")
else()
    message(STATUS "wrobj: JNI headers not found, building the core library only")
endif()

# ---------------------------------------------------------------------------
# Tests and benchmarks
# ---------------------------------------------------------------------------

option(WROBJ_BUILD_TESTS "Build the native OBJ pipeline tests" ON)

if(WROBJ_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------

include(GNUInstallDirs)
install(TARGETS wrobj_core ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR})
install(FILES ${WROBJ_PUBLIC_HEADERS} DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/wrobj)
if(JNI_FOUND)
    install(TARGETS wrobj LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})
endif()
