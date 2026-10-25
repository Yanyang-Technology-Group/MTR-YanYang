#!/usr/bin/env bash
# Install a JDK if one is missing, then confirm the JNI bridge builds.
#
# The earlier run found no JNI headers and therefore produced only the static core. That is
# correct behaviour, but it leaves the shared library and the JDK search itself unverified
# on this platform - and the JDK search is exactly what was broken here by the invalid
# DIRECTORY keyword in an if() condition.
set -euo pipefail

if ! command -v javac >/dev/null 2>&1; then
    echo "=== installing a JDK ==="
    if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        sudo apt-get update -qq 2>&1 | tail -n 2 || true
        sudo apt-get install -y -qq default-jdk-headless 2>&1 | tail -n 3 || true
    fi
fi

echo
echo "=== java present? ==="
command -v javac || echo "javac MISSING"
command -v java || echo "java MISSING"

if command -v javac >/dev/null 2>&1; then
    echo
    echo "=== JAVA_HOME and jni.h layout ==="
    java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    echo "JAVA_HOME=${java_home}"
    for candidate in "${java_home}/include" "${java_home}/include/linux"; do
        printf '%s/jni.h: ' "${candidate}"
        [ -f "${candidate}/jni.h" ] && echo present || echo absent
        printf '%s/jni_md.h: ' "${candidate}"
        [ -f "${candidate}/jni_md.h" ] && echo present || echo absent
    done

    echo
    echo "=== reconfigure with JAVA_HOME set ==="
    native_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    JAVA_HOME="${java_home}" cmake -S "${native_dir}" -B "${native_dir}/build-linux" \
        -DCMAKE_BUILD_TYPE=Release -DWROBJ_BUILD_TESTS=ON 2>&1 | grep -E "wrobj:|JNI" || true

    echo
    echo "=== rebuild ==="
    cmake --build "${native_dir}/build-linux" --parallel 2>&1 | tail -n 4

    echo
    echo "=== shared library produced? ==="
    ls -la "${native_dir}/build-linux"/libwrobj.so 2>/dev/null || echo "libwrobj.so MISSING"
fi
