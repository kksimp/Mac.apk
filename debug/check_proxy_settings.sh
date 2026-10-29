#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d /tmp/macapk-proxy-settings.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
clang++ -std=c++17 -fobjc-arc -I"$ROOT/runtime/include" \
  "$ROOT/runtime/src/proxy_settings.mm" "$ROOT/debug/ProxySettingsSmoke.cpp" \
  -framework AppKit -o "$TMP/proxy-settings"
"$TMP/proxy-settings" "$TMP/app-root"
