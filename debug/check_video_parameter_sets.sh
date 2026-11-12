#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${MACAPK_TEST_JDK:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
TMP="$(mktemp -d /tmp/macapk-video-csd.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
# Original local test content; no downloaded media or account data.
ffmpeg -v error -f lavfi -i color=c=black:s=64x64:d=0.04 \
    -frames:v 1 -c:v libx264 -f h264 -y "$TMP/frame.h264"
clang++ -std=c++20 -O1 -I"$JDK/include" -I"$JDK/include/darwin" \
    "$ROOT/debug/VideoParameterSetsSmoke.mm" \
    -framework Foundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox \
    -o "$TMP/check"
"$TMP/check" "$TMP/frame.h264"
