// guest_abi.h -- the calling convention between Android arm64 code and the Windows host.
//
// Windows ARM64 and AAPCS64 agree for fixed arguments, so host functions guest code calls and guest
// function pointers the host calls need no attribute. WINAPK_GUEST_ABI marks both, to keep that boundary
// visible. va_list differs and is bridged in abi_thunk.h.

#pragma once

#include "winapk/arch.h"

#ifndef __clang__
#error "Win.apk needs clang."
#endif
#define WINAPK_GUEST_ABI

namespace winapk {

using GuestVoidFn = void(WINAPK_GUEST_ABI*)();
// Bionic calls DT_INIT_ARRAY entries as fn(argc, argv, envp).
using GuestInitFn = void(WINAPK_GUEST_ABI*)(int, char**, char**);
using GuestIRelativeResolver = void*(WINAPK_GUEST_ABI*)();

}  // namespace winapk
