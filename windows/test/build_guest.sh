#!/usr/bin/env bash
# build_guest.sh -- builds the arm64 guest test libraries with plain clang + lld.
#
# No NDK needed: the guests declare their libc imports by hand and link with
# -nostdlib, so their undefined symbols are left for the loader.
#
#   windows/test/build_guest.sh [out_dir]      (default: windows/build/guest)
#
# aarch64/: the linker test's libraries (guest/link_guest.c) and the Bionic
# test's guest. arm64/: winapk-arm64-test's libraries.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
OUT="${1:-$HERE/../build/guest}"
CC="${CC_ANDROID:-clang}"
mkdir -p "$OUT"

COMMON=(
    --target=aarch64-linux-android24
    -fPIC -O2 -fstack-protector-all -ffreestanding
    -nostdlib -shared -fuse-ld=lld
    -fvisibility=hidden
    -Wl,-z,relro -Wl,-z,now
)

# Stub system libraries give the DT_NEEDED entries an NDK build would have;
# they are only link inputs and never ship.
host_libs() {  # host_libs <out dir> <compiler flags...>
    local out="$1"
    shift
    local -a flags=("$@")
    local LINK="$out/link" STUBS="$out/link-stubs"
    mkdir -p "$LINK" "$STUBS"
    for lib in libc libdl liblog libz; do
        "$CC" "${flags[@]}" -Wl,-soname,$lib.so -o "$STUBS/$lib.so" -x c /dev/null
    done
    # Real ELF TLS (the plugin has a __thread variable dlclose must free).
    link_lib() {  # link_lib <part> <name> [linker args...]
        local part="$1" name="$2"
        shift 2
        "$CC" "${flags[@]}" -fno-emulated-tls -DWINAPK_LINK_$part -Wl,-soname,$name.so -Wl,--no-as-needed \
            -o "$LINK/$name.so" "$HERE/guest/link_guest.c" "$@"
    }
    # base and mid need each other: link base against a placeholder that only
    # carries mid's soname, then link the real mid against base.
    "$CC" "${flags[@]}" -Wl,-soname,libwinapk_link_mid.so -o "$STUBS/libwinapk_link_mid.so" -x c /dev/null
    link_lib PLUGIN libwinapk_link_plugin
    link_lib BASE libwinapk_link_base -L"$STUBS" -lwinapk_link_mid
    link_lib MID libwinapk_link_mid -L"$LINK" -L"$STUBS" -lwinapk_link_base -llog
    link_lib ROOT libwinapk_link_root -L"$LINK" -L"$STUBS" -lwinapk_link_mid -lc -ldl
    # Lookup order: DT_NEEDED order is the order of the -l flags, and it is what
    # the test is about (user: interpose before libc; late: libc before interpose).
    link_lib INTERPOSE libwinapk_link_interpose
    link_lib USER libwinapk_link_user -L"$LINK" -L"$STUBS" -lwinapk_link_interpose -lc -ldl
    link_lib LATE libwinapk_link_late -L"$LINK" -L"$STUBS" -lc -lwinapk_link_interpose
    link_lib PLAIN libwinapk_link_plain -L"$STUBS" -lc

    # The Bionic shim's guest-side test (winapk-bionic-test). Real ELF TLS, as an
    # NDK build for minSdk 29 or later uses.
    "$CC" "${flags[@]}" -fno-emulated-tls -Wl,-soname,libwinapk_bionic_guest.so \
        -o "$out/libwinapk_bionic_guest.so" "$HERE/guest/bionic_guest.c" -L"$STUBS" -Wl,--no-as-needed -lz
}
# Atomics inlined: the outline helpers live in libgcc, which -nostdlib leaves out.
host_libs "$OUT/aarch64" "${COMMON[@]}" -mno-outline-atomics

# winapk-arm64-test's libraries: arm64_isa.c three ways (the test compares each
# against the same code built into the test), and the bridge library.
ARM64_OUT="$OUT/arm64"
mkdir -p "$ARM64_OUT"
ARM64=(
    --target=aarch64-linux-android24
    -fPIC -ffreestanding -nostdlib -shared -fuse-ld=lld
    -fvisibility=hidden -Wl,-z,relro -Wl,-z,now
)
"$CC" "${ARM64[@]}" -O2 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_isa_O2.so \
    -o "$ARM64_OUT/libwinapk_arm64_isa_O2.so" "$HERE/guest/arm64_isa.c"
"$CC" "${ARM64[@]}" -O3 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_isa_O3.so \
    -o "$ARM64_OUT/libwinapk_arm64_isa_O3.so" "$HERE/guest/arm64_isa.c"
"$CC" "${ARM64[@]}" -O3 -march=armv8.1-a -Wl,-soname,libwinapk_arm64_isa_lse.so \
    -o "$ARM64_OUT/libwinapk_arm64_isa_lse.so" "$HERE/guest/arm64_isa.c"
"$CC" "${ARM64[@]}" -O2 -fstack-protector-all -mno-outline-atomics \
    -Wl,--pack-dyn-relocs=android+relr -Wl,--use-android-relr-tags \
    -Wl,-soname,libwinapk_arm64_bridge.so \
    -o "$ARM64_OUT/libwinapk_arm64_bridge.so" "$HERE/guest/arm64_bridge_guest.c"
# ELF TLS through TLSDESC (arm64_tls_guest.c): API 29, real ELF TLS.
"$CC" "${ARM64[@]/aarch64-linux-android24/aarch64-linux-android29}" -O2 -fno-emulated-tls \
    -Wl,-soname,libwinapk_arm64_tls.so -o "$ARM64_OUT/libwinapk_arm64_tls.so" "$HERE/guest/arm64_tls_guest.c"
# Raw Linux system calls (svc #0, arm64_svc_guest.c).
"$CC" "${ARM64[@]}" -O2 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_svc.so \
    -o "$ARM64_OUT/libwinapk_arm64_svc.so" "$HERE/guest/arm64_svc_guest.c"
# Signals delivered to guest handlers (arm64_signal_guest.c).
"$CC" "${ARM64[@]}" -O2 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_signal.so \
    -o "$ARM64_OUT/libwinapk_arm64_signal.so" "$HERE/guest/arm64_signal_guest.c"
# Control flow and edge cases (arm64_flow_guest.c), and a library the test
# unloads with dlclose (arm64_isa.c again).
"$CC" "${ARM64[@]}" -O2 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_flow.so \
    -o "$ARM64_OUT/libwinapk_arm64_flow.so" "$HERE/guest/arm64_flow_guest.c"
"$CC" "${ARM64[@]}" -O2 -mno-outline-atomics -Wl,-soname,libwinapk_arm64_unload.so \
    -o "$ARM64_OUT/libwinapk_arm64_unload.so" "$HERE/guest/arm64_isa.c"
# C++ exceptions (arm64_eh_guest.cpp), with an NDK's libc++ and unwinder when
# there is one (GitHub's Ubuntu runners have it): the test skips it otherwise.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
NDK_CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
if [ -n "$NDK" ] && [ -x "$NDK_CXX" ]; then
    "$NDK_CXX" --target=aarch64-linux-android24 -O2 -fPIC -shared -static-libstdc++ -fvisibility=hidden \
        -Wl,-soname,libwinapk_arm64_eh.so -o "$ARM64_OUT/libwinapk_arm64_eh.so" "$HERE/guest/arm64_eh_guest.cpp"
else
    echo "[build_guest] no Android NDK (ANDROID_NDK_HOME): libwinapk_arm64_eh.so not built"
fi

echo "[build_guest] $OUT/aarch64/link/libwinapk_link_{root,mid,base,plugin,interpose,user,late,plain}.so"
echo "[build_guest] $OUT/aarch64/libwinapk_bionic_guest.so"
echo "[build_guest] $ARM64_OUT/libwinapk_arm64_{isa_O2,isa_O3,isa_lse,bridge,tls,svc,signal,flow,unload,eh}.so"
