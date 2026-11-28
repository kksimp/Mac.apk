#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="${1:?Usage: check_timerfd_state.sh <zig-executable> <runtime-root>}"
RT="${2:?A built or staged Mac.apk runtime root}"
WORK="$(mktemp -d /tmp/macapk-timerfd-state.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
export ZIG_GLOBAL_CACHE_DIR="$WORK/zig-global" ZIG_LOCAL_CACHE_DIR="$WORK/zig-local"
"$ZIG" cc -target aarch64-linux-android -c -fPIC -ffreestanding -fno-stack-protector -O2 "$ROOT/debug/TimerfdStateFixture.c" -o "$WORK/fixture.o"
"$ZIG" ld.lld -shared --hash-style=sysv -soname=libtimerfdstate.so "$WORK/fixture.o" -o "$WORK/libtimerfdstate.so"
"$RT/bin/macapk-translator" --in "$WORK/libtimerfdstate.so" --out "$WORK/libtimerfdstate.dylib" --shim-dir "$RT/bin"
clang++ -std=c++17 -Wall -Wextra -O2 "$ROOT/debug/TimerfdStateSmoke.cpp" -Wl,-rpath,"$RT/bin" -o "$WORK/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$WORK/check" >/dev/null
"$WORK/check" "$WORK/libtimerfdstate.dylib"
