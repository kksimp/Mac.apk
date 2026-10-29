#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d /tmp/macapk-helper-layout.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 -Iruntime/include debug/ApkNativeExecLayoutSmoke.cpp runtime/src/apk_native_exec.cpp -o "$TMP/helper-layout"
"$TMP/helper-layout"
