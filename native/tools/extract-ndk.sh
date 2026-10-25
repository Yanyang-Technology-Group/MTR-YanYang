#!/usr/bin/env bash
# Extract the NDK archive without unzip, which this image does not have.
set -euo pipefail

zip_path="$HOME/android-ndk-r26d-linux.zip"
ndk_dir="$HOME/android-ndk-r26d"

echo "=== archive ==="
ls -la "$zip_path"

echo
echo "=== extracting with python zipfile ==="
python3 - "$zip_path" "$HOME" <<'PY'
import sys, zipfile, os
archive, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(archive) as z:
    bad = z.testzip()
    if bad is not None:
        print("corrupt entry:", bad)
        sys.exit(1)
    print("archive integrity OK,", len(z.namelist()), "entries")
    z.extractall(dest)
print("extracted to", dest)
PY

echo
echo "=== NDK present? ==="
ls -d "$ndk_dir"
"$ndk_dir"/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version | head -n 1

echo
echo "=== jni.h in the NDK sysroot ==="
find "$ndk_dir" -name jni.h -print 2>/dev/null | head -n 3
