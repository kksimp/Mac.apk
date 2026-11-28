#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="${1:?Usage: check_epoll_event_identity.sh <zig-executable> <runtime-root>}"
RT="${2:?A built or staged Mac.apk runtime root}"
WORK="$(mktemp -d /tmp/macapk-epoll-identity.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
export ZIG_GLOBAL_CACHE_DIR="$WORK/zig-global" ZIG_LOCAL_CACHE_DIR="$WORK/zig-local"
"$ZIG" cc -target aarch64-linux-android -c -fPIC -ffreestanding -fno-stack-protector -O2 \
    "$ROOT/debug/EpollEventIdentityFixture.c" -o "$WORK/fixture.o"
"$ZIG" ld.lld -shared --hash-style=sysv -soname=libepollidentity.so "$WORK/fixture.o" -o "$WORK/libepollidentity.so"
"$RT/bin/macapk-translator" --in "$WORK/libepollidentity.so" --out "$WORK/libepollidentity.dylib" --shim-dir "$RT/bin"
clang++ -std=c++17 -Wall -Wextra -O2 "$ROOT/debug/EpollEventIdentitySmoke.cpp" -Wl,-rpath,"$RT/bin" -o "$WORK/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$WORK/check" >/dev/null
"$WORK/check" "$WORK/libepollidentity.dylib"
