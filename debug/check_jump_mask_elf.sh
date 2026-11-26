#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="${1:?Usage: check_jump_mask_elf.sh <zig-executable> <runtime-root>}"
RUNTIME="${2:?A built or staged Mac.apk runtime directory}"
WORK="$(mktemp -d /tmp/macapk-jump-mask-elf.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
export ZIG_GLOBAL_CACHE_DIR="$WORK/zig-global"
export ZIG_LOCAL_CACHE_DIR="$WORK/zig-local"
"$ZIG" cc -target aarch64-linux-android -shared -fPIC -nostdlib -O2 \
    -fno-builtin -Wl,--hash-style=sysv -Wl,-soname,libsigmask.so \
    "$ROOT/debug/JumpMaskElfFixture.c" -o "$WORK/libsigmask.so"
"$RUNTIME/bin/macapk-translator" --in "$WORK/libsigmask.so" \
    --out "$WORK/libsigmask.dylib" --shim-dir "$RUNTIME/bin"
clang++ -std=c++17 -O2 -Wall -Wextra "$ROOT/debug/JumpMaskElfSmoke.cpp" \
    -Wl,-rpath,"$RUNTIME/bin" -o "$WORK/check"
codesign --force --options runtime --sign - \
    --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$WORK/check"
"$WORK/check" "$WORK/libsigmask.dylib"
