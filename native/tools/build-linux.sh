#!/usr/bin/env bash
# Build and test the native library on Linux.
#
# This exists because the Linux CI job has been stuck in GitHub's runner queue, so the
# platform had never been compiled. WSL is a real Linux userspace with a real glibc and
# libstdc++, which is enough to catch the whole class of defect that only appears off MSVC
# - M_PI, and translation units relying on another header to include <cmath>.
#
# The JNI bridge is skipped when no JDK is present, which is the normal case in a bare
# WSL install. That is not a gap in what this verifies: the static core and both test
# suites are the whole of the portable code, and the bridge's only platform-dependent part
# is the JNI include discovery.
set -euo pipefail

native_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${native_dir}/build-linux"

echo "=== configure ==="
cmake -S "${native_dir}" -B "${build_dir}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DWROBJ_BUILD_TESTS=ON 2>&1 | grep -E "wrobj:|JNI|Configuring|error" || true

echo
echo "=== build ==="
cmake --build "${build_dir}" --parallel 2>&1 | tail -n 5

echo
echo "=== artefacts ==="
ls -la "${build_dir}" | grep -E "wrobj|\.a$" || true

echo
echo "=== ctest ==="
ctest --test-dir "${build_dir}" --output-on-failure 2>&1 | tail -n 8

echo
echo "=== suites, with assertion counts ==="
for binary in "${build_dir}/wrobj_tests" "${build_dir}/wrgl_tests"; do
    if [ -x "${binary}" ]; then
        echo "--- $(basename "${binary}") ---"
        "${binary}" 2>&1 | tail -n 3
    fi
done
