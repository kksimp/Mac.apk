#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}"
NATIVE="$(cd "$NATIVE" && pwd)"
TMP="$(mktemp -d /tmp/macapk-inline-file.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_inline_file_fixture.py" "$TMP/libinlineaccess.so"
clang++ -std=c++17 "$ROOT/debug/InlineFileSyscallSmoke.cpp" -o "$TMP/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$TMP/check"
"$NATIVE/macapk-translator" --in "$TMP/libinlineaccess.so" --out "$TMP/libinlineaccess.dylib" --shim-dir "$NATIVE"
MACAPK_GUEST_RWX=1 "$TMP/check" "$TMP/libinlineaccess.dylib"
