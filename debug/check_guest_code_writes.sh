#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d /tmp/macapk-code-writes.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++20 -O2 debug/GuestRwxSmoke.cpp -o "$TMP/guest-code-writes"
"$TMP/guest-code-writes"
