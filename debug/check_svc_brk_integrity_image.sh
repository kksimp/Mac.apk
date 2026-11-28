#!/usr/bin/env bash
# check_svc_brk_integrity_image.sh [native-build-dir]
#
# Regression for the `svc #0` -> `brk` rewrite leaving the code of an image
# alone once the image proves it checks its own bytes (MACAPK_SVC_BRK_SKIP_
# INTEGRITY, default on; see Image::apply_svc_brk_rewrite).
#
# Builds two small AArch64 ELFs with debug/make_integrity_image_fixture.py,
# translates them with the build's macapk-translator, loads them through the
# build's bionic shim and reads the `svc #0` words back from the loaded code,
# once per setting of the switch.
#
#   native-build-dir  where macapk-translator and libmacapk-bionic-shim.dylib
#                     are; default runtime/translator/build
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${1:-${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}}"
NATIVE="$(cd "$NATIVE" && pwd)"
TMP="$(mktemp -d /tmp/macapk-svc-brk-integrity.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

python3 "$ROOT/debug/make_integrity_image_fixture.py" "$TMP"
for image in integrityimage plainimage; do
    "$NATIVE/macapk-translator" --in "$TMP/lib$image.so" --out "$TMP/lib$image.dylib" --shim-dir "$NATIVE"
done
# The shim is found through the translated image's @rpath (the translator wrote
# --shim-dir into it); ANGLE it links against through the prebuilt tree.
clang++ -std=c++17 -Wall -Wextra "$ROOT/debug/IntegrityImageSvcSmoke.cpp" \
    -Wl,-rpath,"$NATIVE" -Wl,-rpath,"$ROOT/thirdparty/angle-prebuilt/lib" -o "$TMP/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$TMP/check"

rc=0
for setting in "" regions 0; do
    MACAPK_GUEST_RWX=1 MACAPK_SVC_BRK_SKIP_INTEGRITY="$setting" "$TMP/check" \
        "$TMP/libintegrityimage.dylib" "$TMP/libintegrityimage.so" "$TMP/libplainimage.dylib" || rc=1
done
exit $rc
