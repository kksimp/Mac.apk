#!/usr/bin/env bash
# build_framework_res.sh -- builds Android's platform resource table
# (framework-res.apk) for the Windows runtime, on Windows (MSYS2) or Linux.
#
#   windows/scripts/build_framework_res.sh [out.apk]
#
# The work is done by upstream's scripts/build_framework_res.sh, unchanged: it
# fetches frameworks/base/core/res from AOSP and compiles it with aapt2. What
# upstream gets from its Mac setup, this script provides: Google's standalone
# aapt2 for the host OS, from Google's Maven repository. aapt2 older than 9.x
# rejects the --feature-flags option the Android 16 sources need.
#
# The default output is runtime's bin/tools/framework-res.apk under
# windows/build/runtime, where the runtime looks for it.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT="$(cd -- "$HERE/../.." >/dev/null 2>&1 && pwd)"
OUT="${1:-$HERE/../build/runtime/bin/tools/framework-res.apk}"
AAPT2_VERSION="9.4.1-15978811"
API=36
TAG=android-16.0.0_r2

case "$(uname -s)" in
    MINGW*|MSYS*|CLANG*|UCRT*|CYGWIN*) platform=windows; exe=aapt2.exe ;;
    Linux) platform=linux; exe=aapt2 ;;
    *) echo "[framework-res] unsupported host $(uname -s)" >&2; exit 1 ;;
esac

TOOLS="$ROOT/thirdparty/framework-res/aapt2-$AAPT2_VERSION-$platform"
if [[ ! -x "$TOOLS/$exe" ]]; then
    echo "[framework-res] fetching aapt2 $AAPT2_VERSION ($platform)..."
    mkdir -p "$TOOLS"
    url="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/$AAPT2_VERSION/aapt2-$AAPT2_VERSION-$platform.jar"
    curl -sSfL -o "$TOOLS/aapt2.jar" "$url"
    unzip -o -q "$TOOLS/aapt2.jar" "$exe" -d "$TOOLS"
    chmod +x "$TOOLS/$exe"
fi

APK="$ROOT/thirdparty/framework-res/framework-res-api$API.apk"
if [[ ! -f "$APK" ]]; then
    AAPT2="$TOOLS/$exe" "$ROOT/scripts/build_framework_res.sh" --api "$API" --tag "$TAG" --version-name 16
fi
mkdir -p "$(dirname -- "$OUT")"
cp -f "$APK" "$OUT"
echo "[framework-res] $OUT"
