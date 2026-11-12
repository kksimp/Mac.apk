#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANGLE="$ROOT/thirdparty/angle-prebuilt"
ANGLE_LIB="${MACAPK_TEST_ANGLE_LIB:-$ANGLE/lib}"
TMP="$(mktemp -d /tmp/macapk-external-texture.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 -I"$ROOT/runtime/include" -I"$ANGLE/include" \
    "$ROOT/debug/ExternalTextureSmoke.cpp" -L"$ANGLE_LIB" -lEGL -lGLESv2 \
    -Wl,-rpath,"$ANGLE_LIB" -o "$TMP/check"
# The committed ANGLE binaries carry relative install names.
(cd "$ANGLE_LIB" && "$TMP/check" "$@")
