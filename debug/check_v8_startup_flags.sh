#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIM="${MACAPK_TEST_SHIM:-$ROOT/runtime/translator/build/libmacapk-bionic-shim.dylib}"
TMP="$(mktemp -d /tmp/macapk-v8-flags.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_v8_flags_fixture.py" "$TMP"
clang++ -std=c++17 "$ROOT/debug/V8StartupFlagsSmoke.cpp" -o "$TMP/check"
codesign --force --sign - --options runtime \
    --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$TMP/check"
for owner in internal public; do
    for length in i m; do
        MACAPK_V8_FLAGS=--jitless "$TMP/check" "$SHIM" "$TMP/libv8_${owner}_${length}.so"
    done
done
