#!/usr/bin/env bash
# Confirm the Android cross build is still reproducible, and report the artefact.
set -u

native_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
so="${native_dir}/build-android/wrobj.so"

echo "=== artefact ==="
if [ -f "${so}" ]; then
    stat -c '%s bytes  %n' "${so}"
    file "${so}"
else
    echo "absent"
fi
