#!/usr/bin/env bash
# build_e2e.sh -- build the real-JVM end-to-end test's inputs: the Android
# arm64 guest library and the Java test class.
#
#   build_e2e.sh <jni-include-dir> <javac> <out-dir>
#
# <jni-include-dir>  a JDK include dir containing jni.h (any OS; only the
#                    headers are read, to compile the Android guest against).
# <javac>            a javac to compile WinApkJniTest.java (JDK 21+).
# <out-dir>          receives arm64/libwinapk_jni_guest.so and classes/.

set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
JNI_INC="${1:?jni include dir}"
JAVAC="${2:?javac}"
OUT="${3:?out dir}"
CC="${CC_ANDROID:-clang}"

mkdir -p "$OUT/classes" "$OUT/guestinc"

"$JAVAC" -d "$OUT/classes" "$HERE/java/WinApkJniTest.java"

# Compile the guest against a real jni.h using the Android jni_md.h. jni.h pulls
# in <stdio.h> (for FILE) which the NDK-less Android target cannot satisfy, so a
# minimal stub supplies just the FILE typedef it needs.
cp "$JNI_INC/jni.h" "$OUT/guestinc/jni.h"
cp "$HERE/guest/android_jni_md.h" "$OUT/guestinc/jni_md.h"
printf '#ifndef _WINAPK_STUB_STDIO_H\n#define _WINAPK_STUB_STDIO_H\ntypedef struct __winapk_FILE FILE;\n#endif\n' \
    > "$OUT/guestinc/stdio.h"

mkdir -p "$OUT/arm64"
"$CC" --target=aarch64-linux-android24 -fPIC -O2 -fvisibility=hidden -nostdlib -shared -fuse-ld=lld \
    -I "$OUT/guestinc" -Wl,-soname,libwinapk_jni_guest.so \
    -o "$OUT/arm64/libwinapk_jni_guest.so" "$HERE/guest/jni_guest.c"

echo "[build_e2e] $OUT/arm64/libwinapk_jni_guest.so"
echo "[build_e2e] $OUT/classes/WinApkJniTest.class"
