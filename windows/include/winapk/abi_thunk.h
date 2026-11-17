// abi_thunk.h -- machine-code thunks for calls whose shape is only known at
// run time (abi_thunk_arm64.cpp).
//
//   * HotSpot calling a guest JNI native method. Its argument list comes from
//     a Java method descriptor discovered at run time, and the JNIEnv* HotSpot
//     passes has to be swapped for the guest's view of it. A thunk is
//     generated per (guest function, descriptor).
//
//   * Guest code calling a C-variadic function (CallIntMethod(env, obj, mid,
//     ...), printf). Windows ARM64 passes variadic floating-point arguments in
//     integer registers, AAPCS64 in v0-v7, so an entry stub spills both under
//     an AAPCS64 va_list and hands it to a C++ decoder.
//
// Thunks live in a RWX pool.

#pragma once

#include "winapk/arch.h"

#include <cstddef>
#include <cstdint>

namespace winapk {

// How one JNI argument travels. The small integer kinds are widened to 32
// bits by the thunk: clang-compiled guest code assumes its caller already
// sign/zero-extended them (the signext/zeroext convention), and HotSpot's
// native wrapper does not promise that.
enum class ArgKind : uint8_t {
    Word,    // pointers, jobject, jint, jlong: copied as 64 bits
    Bool,    // jboolean: zero-extend 8 -> 32
    Byte,    // jbyte: sign-extend 8 -> 32
    Char,    // jchar: zero-extend 16 -> 32
    Short,   // jshort: sign-extend 16 -> 32
    Float,   // jfloat
    Double,  // jdouble
};

// A host callable that forwards to the guest function `target`. args[0] must
// be the JNIEnv*; it is replaced by env_xlate(args[0]) before the call. With
// `skip` 2 the JNIEnv* and the jclass are not passed on at all: ART's
// @CriticalNative methods take only their Java arguments. Returns nullptr on
// allocation failure.
void* make_jni_native_thunk(void* target, const ArgKind* args, size_t nargs, void* env_xlate, size_t skip = 0);

// A guest-callable entry for a C-variadic function with `named` integer-class
// fixed arguments (3 for CallIntMethod(env, obj, mid, ...), 4 for the
// Nonvirtual variants). It calls `handler` with the same fixed arguments plus
// a pointer to a SysVVaList over the variadic part.
// (The sysv* names here and in SysVVaList are historical: the convention is AAPCS64.)
void* make_sysv_variadic_entry(int named, void* handler);

// A callable that calls `handler(first_arg, tag)` and then returns 0 / 0.0 /
// nullptr, whatever its caller expects. The host uses it for native methods
// that have no Windows implementation yet: each one gets a stub tagged with
// its own name, so the first call can be reported by name instead of failing
// as UnsatisfiedLinkError.
void* make_tagged_zero_stub(void* handler, const void* tag);

// Jumps to `target`, first calling `enter()` (preserving the argument
// registers) when this thread's TPIDR_EL0 is still 0 (arm64_guest.h,
// enter_thread).
void* make_arm64_entry_thunk(void* target, void* enter);

// AAPCS64's va_list. The name is historical; the readers below take it.
struct SysVVaList {
    uint8_t* stack;    // next stack-passed argument
    uint8_t* gr_top;   // end of the saved x0-x7 area
    uint8_t* vr_top;   // end of the saved q0-q7 area
    int32_t gr_offs;   // offset from gr_top of the next general register; >= 0 once all are used
    int32_t vr_offs;   // offset from vr_top of the next FP/SIMD register; >= 0 once all are used
};

uint64_t sysv_va_next_gp(SysVVaList* va);
double sysv_va_next_fp(SysVVaList* va);

// The next long double argument: binary128, rounded to the host's double.
long double guest_va_next_long_double(SysVVaList* va);

}  // namespace winapk
