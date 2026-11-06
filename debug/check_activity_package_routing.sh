#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
TMP="$(mktemp -d /tmp/macapk-activity-route.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP" "$ROOT/debug/ActivityPackageRoutingSmoke.java"
MACAPK_LOG_LEVEL=99 MACAPK_EXTERNAL_URLS=0 "$JDK/bin/java" -Xverify:all \
    -cp "$TMP:$LIB/*" ActivityPackageRoutingSmoke
