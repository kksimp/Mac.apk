#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
TMP="$(mktemp -d /tmp/macapk-live-memory.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_live_memory_dex_fixture.py" "$TMP/live.dex" "$TMP/offsets.txt"
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP/classes" "$ROOT/debug/LiveMemoryDexSmoke.java" "$ROOT/debug/CrossMemoryDexSmoke.java" "$ROOT/debug/DuplicateMemoryDexSmoke.java"
"$JDK/bin/java" -Xverify:all -Djava.io.tmpdir="$TMP" -cp "$TMP/classes:$LIB/*" LiveMemoryDexSmoke "$TMP/live.dex" "$TMP/offsets.txt"
python3 "$ROOT/debug/make_cross_memory_dex_fixture.py" "$TMP/caller.dex" "$TMP/provider.dex"
"$JDK/bin/java" -Xverify:all -Djava.io.tmpdir="$TMP" -cp "$TMP/classes:$LIB/*" CrossMemoryDexSmoke "$TMP/caller.dex" "$TMP/provider.dex"
"$JDK/bin/java" -Xverify:all -Djava.io.tmpdir="$TMP" -cp "$TMP/classes:$LIB/*" DuplicateMemoryDexSmoke "$TMP/live.dex" "$TMP/offsets.txt"
