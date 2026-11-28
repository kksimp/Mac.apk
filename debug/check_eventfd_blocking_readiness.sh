#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="${1:?Usage: check_eventfd_blocking_readiness.sh <zig-executable> <runtime-root>}"
RT="${2:?A built or staged Mac.apk runtime root}"
WORK="$(mktemp -d /tmp/macapk-eventfd-blocking.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
export ZIG_GLOBAL_CACHE_DIR="$WORK/zig-global" ZIG_LOCAL_CACHE_DIR="$WORK/zig-local"
"$ZIG" cc -target aarch64-linux-android -c -fPIC -ffreestanding -fno-stack-protector -O2 "$ROOT/debug/EventfdBlockingReadinessFixture.c" -o "$WORK/fixture.o"
"$ZIG" ld.lld -shared --hash-style=sysv -soname=libeventfdblocking.so "$WORK/fixture.o" -o "$WORK/libeventfdblocking.so"
"$RT/bin/macapk-translator" --in "$WORK/libeventfdblocking.so" --out "$WORK/libeventfdblocking.dylib" --shim-dir "$RT/bin"
clang++ -std=c++17 -Wall -Wextra -O2 "$ROOT/debug/EventfdBlockingReadinessSmoke.cpp" -Wl,-rpath,"$RT/bin" -o "$WORK/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$WORK/check" >/dev/null
"$WORK/check" "$WORK/libeventfdblocking.dylib"
