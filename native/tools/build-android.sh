#!/usr/bin/env bash
# Fetch the Android NDK and cross build the library for arm64-v8a.
#
# Android is the one target with no way to run its tests here: there is no device and no
# emulator, and the tests would need one. What this can establish is that the cross build
# configures and compiles, which is the part that has never been exercised and where the
# three Android-specific settings live.
#
# On this machine the download below does not complete: dl.google.com answers a HEAD request
# with 200 but the transfer stalls, so the NDK could not be fetched and the cross build is
# still unverified. The script is kept because it is correct and only the network is not, and
# because writing it down is what makes the next attempt one command instead of an
# investigation.
set -euo pipefail

ndk_version="r26d"
ndk_dir="$HOME/android-ndk-${ndk_version}"
zip_path="$HOME/android-ndk-${ndk_version}-linux.zip"
url="https://dl.google.com/android/repository/android-ndk-${ndk_version}-linux.zip"

if [ ! -d "$ndk_dir" ]; then
    if [ ! -f "$zip_path" ]; then
        echo "=== downloading the NDK (${ndk_version}) ==="
        curl -L --fail --progress-bar -o "$zip_path" "$url"
    fi
    echo "=== extracting ==="
    unzip -q -o "$zip_path" -d "$HOME"
fi

echo
echo "=== NDK present ==="
ls -d "$ndk_dir"
"$ndk_dir"/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version | head -n 1

echo
echo "=== jni.h in the NDK sysroot ==="
find "$ndk_dir" -name jni.h -print 2>/dev/null | head -n 3

echo
echo "=== configure for arm64-v8a ==="
native_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cmake -S "$native_dir" -B "$native_dir/build-android" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DWROBJ_BUILD_TESTS=OFF 2>&1 | grep -E "wrobj:|Android|Configuring|error|Error" || true

echo
echo "=== build ==="
cmake --build "$native_dir/build-android" --parallel 2>&1 | tail -n 6

echo
echo "=== artefacts ==="
find "$native_dir/build-android" -maxdepth 1 -name 'wrobj*' -printf '%f  %s bytes\n' | sort

echo
echo "=== is it an aarch64 shared object? ==="
so="$native_dir/build-android/wrobj.so"
if [ -f "$so" ]; then
    file "$so"
    echo "--- undefined symbols (should be libc/libm/libc++ only) ---"
    "$ndk_dir"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -d "$so" | grep NEEDED || true
fi
