#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
TMP="$(mktemp -d /tmp/macapk-memory-path.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_memory_dex_fixture.py" "$TMP/fixture.apk"
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP/classes" "$ROOT/debug/InMemoryDexPathSmoke.java"
MACAPK_QUIET_CLASSLOAD=1 "$JDK/bin/java" -Djava.io.tmpdir="$TMP" -cp "$TMP/classes:$LIB/*" InMemoryDexPathSmoke "$TMP/fixture.apk"
