#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/debug/jdk25_select.sh"
JDK="${MACAPK_TEST_JDK:-$(macapk_jdk_home)}"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
TMP="$(mktemp -d /tmp/macapk-application-context.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP" \
    "$ROOT/debug/WrappedApplicationInfoSmoke.java" \
    "$ROOT/debug/ActivityPackageRoutingSmoke.java" \
    "$ROOT/debug/SharedMemoryProtectionSmoke.java"
for fixture in WrappedApplicationInfoSmoke ActivityPackageRoutingSmoke SharedMemoryProtectionSmoke; do
    MACAPK_LOG_LEVEL=99 MACAPK_EXTERNAL_URLS=0 "$JDK/bin/java" -Xverify:all -cp "$TMP:$LIB/*" "$fixture"
done
