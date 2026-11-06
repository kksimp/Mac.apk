#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}"
TMP="$(mktemp -d /tmp/macapk-deferred-jni.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_jni_elf_fixture.py" "$TMP"
clang++ -std=c++17 "$ROOT/debug/DeferredJniOnLoadSmoke.cpp" -o "$TMP/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$TMP/check"
for mode in deferred normal absent; do
  "$NATIVE/macapk-translator" --in "$TMP/lib$mode.so" --out "$TMP/lib$mode.dylib" --shim-dir "$NATIVE"
  "$TMP/check" "$TMP/lib$mode.dylib" "$mode"
done
