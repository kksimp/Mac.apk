#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}"
SHIM="$NATIVE/libmacapk-bionic-shim.dylib"
TMP="$(mktemp -d /tmp/macapk-sigaltstack-disable.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 -Wall -Wextra "$ROOT/debug/SigaltstackDisableSmoke.cpp" \
    -Wl,-rpath,"$NATIVE" -Wl,-rpath,"$ROOT/thirdparty/angle-prebuilt/lib" -o "$TMP/check"
"$TMP/check" "$SHIM"
