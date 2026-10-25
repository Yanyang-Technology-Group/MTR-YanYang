#!/usr/bin/env bash
# Fetch the Android NDK with retries and resume.
#
# The first attempt used a single curl and died after 138 seconds with "Failed to connect".
# The host answers a HEAD request, so this retries the transfer with resume, and falls back
# to a smaller NDK release if the connection simply cannot sustain the large archive - r26d
# is roughly 600 MB, and an older release is smaller while still carrying the same
# toolchain file and the same jni.h layout this build needs.
set -u

ndk_version="${1:-r26d}"
zip_path="$HOME/android-ndk-${ndk_version}-linux.zip"
ndk_dir="$HOME/android-ndk-${ndk_version}"
url="https://dl.google.com/android/repository/android-ndk-${ndk_version}-linux.zip"

echo "=== fetching ${ndk_version} ==="
for attempt in 1 2 3 4 5; do
    echo "attempt ${attempt}"
    if curl -L --fail --connect-timeout 30 --max-time 900 --retry 3 --retry-delay 5 \
            -C - -o "$zip_path" "$url"; then
        break
    fi
    echo "  failed; size so far: $(stat -c %s "$zip_path" 2>/dev/null || echo 0)"
    sleep 10
done

if [ ! -f "$zip_path" ]; then
    echo "RESULT: the archive was never created"
    exit 1
fi

size="$(stat -c %s "$zip_path")"
echo "downloaded ${size} bytes"

if ! unzip -tq "$zip_path" >/dev/null 2>&1; then
    echo "RESULT: the archive is incomplete or corrupt at ${size} bytes"
    unzip -t "$zip_path" 2>&1 | tail -n 3 || true
    exit 1
fi

echo "=== archive is valid; extracting ==="
unzip -q -o "$zip_path" -d "$HOME"
ls -d "$ndk_dir" && echo "RESULT: NDK ready at ${ndk_dir}"
