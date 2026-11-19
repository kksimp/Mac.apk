#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
TMP="$(mktemp -d /tmp/macapk-descriptor-fallback.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JDK/bin/javac" --release 21 -sourcepath '' -cp "$LIB/*" -d "$TMP/classes" \
    "$ROOT/runtime/java/android/content/ContentResolver.java" "$ROOT/debug/ContentDescriptorFallbackSmoke.java"
"$JDK/bin/java" -cp "$TMP/classes:$LIB/*" ContentDescriptorFallbackSmoke
