#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIM="${MACAPK_TEST_SHIM:-$ROOT/runtime/translator/build/libmacapk-bionic-shim.dylib}"
TMP="$(mktemp -d /tmp/macapk-arm64-strdup.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 "$ROOT/debug/Arm64StrdupSmoke.cpp" -o "$TMP/check"
"$TMP/check" "$SHIM"
