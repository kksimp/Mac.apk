#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
JAVA="${MACAPK_TEST_JAVA:-$JDK/bin/java}"
TMP="$(mktemp -d /tmp/macapk-unsafe-null.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JDK/bin/javac" --release 21 -d "$TMP/classes" \
    "$ROOT/runtime/java/com/macapk/loader/NativeLog.java" \
    "$ROOT/runtime/java/com/macapk/loader/MacApkUnsafe.java" \
    "$ROOT/debug/UnsafeNullAddressSmoke.java" 2>"$TMP/javac.log" || { cat "$TMP/javac.log"; exit 1; }
# Run the real bridge in a separate JVM; a regression must fail this command
# even when HotSpot turns an absolute null read into a fatal native crash.
MACAPK_LOG_LEVEL=6 "$JAVA" -XX:ErrorFile="$TMP/hs_err_pid%p.log" \
    -cp "$TMP/classes" UnsafeNullAddressSmoke >"$TMP/run.log" 2>&1 || {
    tail -22 "$TMP/run.log"
    exit 1
}
cat "$TMP/run.log"
