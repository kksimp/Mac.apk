// arm64_guest.h -- running Android arm64-v8a code in the Windows ARM64 host
// (arm64_native.cpp, arm64_abi.cpp).
//
// Guest code runs as it is. Fixed arguments follow the same convention on
// both sides, so host functions bind directly to the guest's imports; what
// differs is bridged:
//
//   * TPIDR_EL0 must point at Bionic's TLS slots (the stack guard is slot 5).
//     Windows leaves the register to user code, so each thread gets its block
//     on its first entry into guest code (call(), host_callable(), or
//     enter_thread()).
//   * Structs, flags and system call numbers arm64 Linux lays out differently
//     from the shim's: abi_override() binds such imports to wrappers.
//   * Raw system calls (`svc #0`) are patched to a trap the shim answers.
//   * x18 holds the TEB on Windows on Arm: libraries that write it are refused
//     at load (arm64_x18.h).
//
// Not supported: arguments or results passed by value as structs larger than
// 16 bytes or as floating-point aggregates (libc and JNI have none).

#pragma once

#include <cstddef>
#include <cstdint>

namespace winapk::arm64 {

// Called by the loader before the first arm64 image runs; safe to call more than once.
void init();

// The executable segments of a loaded arm64 image.
void register_code(const void* start, size_t size);
void unregister_code(const void* start);
bool is_guest_code(const void* p);

// Calls the guest function at `fn` on the calling thread with up to eight
// integer or pointer arguments, and returns x0.
uint64_t call(const void* fn, const uint64_t* args = nullptr, size_t nargs = 0);

// An entry point for the guest function at `fn` that a host thread may call
// directly (cached per address), or `fn` itself if it is not guest code.
void* host_callable(void* fn);

// Gives the calling thread its guest TLS block (TPIDR_EL0). call() and
// host_callable() do this; code that calls a guest function pointer directly
// calls it first.
void enter_thread();

// Rewrites each `svc #0` in [code, code + len) to a trap answered as the Linux
// system call. Returns the count; other `svc` immediates are counted in
// *unsupported.
size_t patch_svc(uint8_t* code, size_t len, size_t* unsupported);

// What an arm64 library's import of `name` binds to when no host function can
// stand in for it (the TLSDESC resolver); nullptr for every other name. The
// loader asks this before the linker's resolver.
void* guest_intrinsic(const char* name);

// What an arm64 library's import of `name` binds to instead of the Bionic
// shim's function, when arm64 lays out one of its structs, flags or syscall
// numbers differently (arm64_abi.cpp); nullptr for every other name.
void* abi_override(const char* name);

// A raw Linux system call (SVC #0): number `nr` (x8), arguments x0-x5. False
// if the shim cannot answer it; otherwise *result is what the kernel would
// return (-errno on failure).
bool linux_syscall(uint64_t nr, const uint64_t args[6], uint64_t* result);

}  // namespace winapk::arm64
