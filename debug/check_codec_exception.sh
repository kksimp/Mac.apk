#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
JAVA="${MACAPK_TEST_JAVA:-$JDK/bin/java}"
TMP="$(mktemp -d /tmp/macapk-codec-exception.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JDK/bin/javac" --release 21 -sourcepath '' -cp "$LIB/*" -d "$TMP/classes" \
    "$ROOT/runtime/java/android/graphics/SurfaceTexture.java" \
    "$ROOT/runtime/java/android/media/MediaCodec.java" "$ROOT/debug/CodecExceptionSmoke.java"
"$JAVA" -cp "$TMP/classes:$LIB/*" CodecExceptionSmoke
