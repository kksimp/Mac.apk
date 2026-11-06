#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIM="${MACAPK_TEST_SHIM:-$ROOT/runtime/translator/build/libmacapk-bionic-shim.dylib}"
TMP="$(mktemp -d /tmp/macapk-wrapper-dlsym.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/lib_native"
clang++ -std=c++17 -dynamiclib "$ROOT/debug/WrapperDlsymFixture.cpp" -o "$TMP/lib_native/libwrapperfixture.dylib"
clang++ -std=c++17 "$ROOT/debug/WrapperDlsymSmoke.cpp" -o "$TMP/check"
"$TMP/check" "$SHIM" "$TMP/lib_native/libwrapperfixture.so"
