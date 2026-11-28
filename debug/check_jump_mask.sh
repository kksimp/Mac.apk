#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}"
TMP="$(mktemp -d /tmp/macapk-jump-mask.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 -O2 -Wall -Wextra "$ROOT/debug/JumpMaskSmoke.cpp" \
    -L"$NATIVE" -lmacapk-bionic-shim -Wl,-rpath,"$NATIVE" \
    -Wl,-rpath,"$ROOT/thirdparty/angle-prebuilt/lib" -o "$TMP/check"
"$TMP/check"
