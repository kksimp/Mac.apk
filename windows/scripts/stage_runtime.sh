#!/usr/bin/env bash
# stage_runtime.sh -- assembles a runnable Win.apk runtime folder:
#
#   <out>/Win.apk.exe                 the app library (launcher/)
#   <out>/bin/winapk-runtime.exe
#   <out>/bin/macapk-translator.exe, winapk-guest-stub.dll (app native libraries)
#   <out>/bin/libEGL.dll, libGLESv2.dll, ... (ANGLE, for GL; fetch_angle.sh)
#   <out>/bin/WebView2Loader.dll      (WebView2, for web content; fetch_webview2.sh)
#   <out>/bin/tools/framework-res.apk
#   <out>/lib/*.jar, java_base_patch/, jdk_unsupported_patch/
#
#   windows/scripts/stage_runtime.sh <cmake build dir> [out]
#
# The JDK is not copied: the runtime uses <out>/jre if present, otherwise
# WINAPK_JAVA_HOME or JAVA_HOME.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
BUILD="${1:?cmake build dir}"
source "$HERE/arch.sh"
OUT="${2:-$HERE/../build/runtime}"
mkdir -p "$OUT"
OUT="$(cd -- "$OUT" && pwd)"

[[ -f "$BUILD/winapk-runtime.exe" ]] || { echo "[stage] no winapk-runtime.exe in $BUILD" >&2; exit 1; }
[[ "$(winapk_pe_arch "$BUILD/winapk-runtime.exe")" == arm64 ]] || { echo "[stage] winapk-runtime.exe is not an ARM64 program" >&2; exit 1; }
"$HERE/build_java.sh" "$OUT"
"$HERE/build_framework_res.sh" "$OUT/bin/tools/framework-res.apk"
cp -f "$BUILD/winapk-runtime.exe" "$BUILD/macapk-translator.exe" "$BUILD/winapk-guest-stub.dll" "$OUT/bin/"
cp -f "$BUILD/Win.apk.exe" "$OUT/"
# The tile an app without an icon gets in the library.
cp -f "$HERE/../launcher/winapk.png" "$OUT/bin/"
"$HERE/fetch_angle.sh" "$OUT/bin"
"$HERE/fetch_webview2.sh" "$OUT/bin"
rm -rf "$OUT/.build"  # build_java.sh's scratch, not part of the runtime
echo "[stage] runtime staged in $OUT"
