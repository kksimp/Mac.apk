// setjmp.cpp -- setjmp and longjmp for native arm64 guest code.
//
// libpng, libjpeg, FreeType's validators and Skia's codecs (in Flutter's
// engine) report errors with longjmp. Without these the stub returned 0 for
// setjmp and did nothing for longjmp, so an error path ran on into freed or
// half-built state: Pixel Wheels (libGDX, FreeType) and LocalSend (Flutter)
// died with access violations.
//
// Only the registers AAPCS64 keeps across a call need saving (x19-x28, x29, x30,
// sp and d8-d15); the rest are the caller's to lose. The slots used are the
// runtime's own, since only these functions read them. sigsetjmp(buf, 1)
// also saves the thread's signal mask and siglongjmp restores it, as on
// Linux: a handler that longjmps out of a fault would otherwise leave the
// signal blocked for good (signal.cpp).

#include "winapk/arch.h"

#include "bionic_internal.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>

namespace winapk::bionic {

namespace {

// Helpers the naked functions call. They are ordinary Windows ARM64 functions: x19-x28 survive the call, and the
// caller's own registers are not saved yet (setjmp) or not restored yet (longjmp) when it happens.
extern "C" uint64_t winapk_sig_mask_get() { return signal_mask_of_current_thread(); }
extern "C" void winapk_sig_mask_set(uint64_t mask) { signal_set_mask_of_current_thread(mask); }
extern "C" void winapk_sig_stack_fixup(uint64_t sp);  // signal.cpp
extern "C" void winapk_longjmp_bad(int64_t* buf) {
    std::fprintf(stderr, "[win.apk] guest called longjmp on a jmp_buf (%p) that setjmp did not fill\n",
                 static_cast<void*>(buf));
    std::fflush(stderr);
    std::abort();
}

// Bionic's arm64 jmp_buf is 32 longs; used here, in bytes: x19-x28 at 0..79,
// x29 at 80, x30 at 88, sp at 96, the magic at 104, the signal mask at 112,
// 1 at 120 if the mask was saved, d8-d15 at 128..191. x18 (the TEB) is not saved.
#define WINAPK_A64_SAVE_REGS                                                                       \
    "stp x19, x20, [x0, #0]\n"                                                                      \
    "stp x21, x22, [x0, #16]\n"                                                                     \
    "stp x23, x24, [x0, #32]\n"                                                                     \
    "stp x25, x26, [x0, #48]\n"                                                                     \
    "stp x27, x28, [x0, #64]\n"                                                                     \
    "stp x29, x30, [x0, #80]\n"                                                                     \
    "mov x2, sp\n"                                                                                  \
    "str x2, [x0, #96]\n"                                                                           \
    "movz x2, #0x4a69\n"                                                                            \
    "movk x2, #0x0f2d, lsl #16\n"                                                                   \
    "movk x2, #0x3b1c, lsl #32\n"                                                                   \
    "movk x2, #0x5e7a, lsl #48\n"                                                                   \
    "str x2, [x0, #104]\n"                                                                          \
    "stp d8, d9, [x0, #128]\n"                                                                      \
    "stp d10, d11, [x0, #144]\n"                                                                    \
    "stp d12, d13, [x0, #160]\n"                                                                    \
    "stp d14, d15, [x0, #176]\n"

extern "C" __attribute__((naked)) int g_setjmp(int64_t*) {
    __asm__ volatile(WINAPK_A64_SAVE_REGS
                     "str xzr, [x0, #120]\n"
                     "mov w0, #0\n"
                     "ret\n" ::: "memory");
}

// sigsetjmp(buf, savesigs): the mask first, then the registers, then the flag.
extern "C" __attribute__((naked)) int g_sigsetjmp(int64_t*, int) {
    __asm__ volatile(
        "cbnz w1, 1f\n"
        WINAPK_A64_SAVE_REGS
        "str xzr, [x0, #120]\n"
        "mov w0, #0\n"
        "ret\n"
        "1:\n"
        "stp x0, x30, [sp, #-16]!\n"
        "bl winapk_sig_mask_get\n"
        "mov x3, x0\n"
        "ldp x0, x30, [sp], #16\n"  // sp is the caller's again
        "str x3, [x0, #112]\n"
        WINAPK_A64_SAVE_REGS
        "mov x3, #1\n"
        "str x3, [x0, #120]\n"
        "mov w0, #0\n"
        "ret\n" ::: "memory");
}

extern "C" __attribute__((naked)) void g_longjmp(int64_t*, int) {
    __asm__ volatile(
        "movz x2, #0x4a69\n"
        "movk x2, #0x0f2d, lsl #16\n"
        "movk x2, #0x3b1c, lsl #32\n"
        "movk x2, #0x5e7a, lsl #48\n"
        "ldr x3, [x0, #104]\n"
        "cmp x2, x3\n"
        "b.ne 1f\n"
        // Out of a signal handler's alternate stack: the TEB's stack bounds back first (signal.cpp).
        "stp x0, x1, [sp, #-16]!\n"
        "ldr x0, [x0, #96]\n"
        "bl winapk_sig_stack_fixup\n"
        "ldp x0, x1, [sp], #16\n"
        "ldr x3, [x0, #120]\n"  // siglongjmp after sigsetjmp(buf, 1): the mask back first
        "cbz x3, 3f\n"
        "stp x0, x1, [sp, #-16]!\n"
        "ldr x0, [x0, #112]\n"
        "bl winapk_sig_mask_set\n"
        "ldp x0, x1, [sp], #16\n"
        "3:\n"
        "cmp w1, #0\n"
        "mov w9, #1\n"
        "csel w9, w9, w1, eq\n"  // longjmp(buf, 0) returns 1
        "ldp d8, d9, [x0, #128]\n"
        "ldp d10, d11, [x0, #144]\n"
        "ldp d12, d13, [x0, #160]\n"
        "ldp d14, d15, [x0, #176]\n"
        "ldp x19, x20, [x0, #0]\n"
        "ldp x21, x22, [x0, #16]\n"
        "ldp x23, x24, [x0, #32]\n"
        "ldp x25, x26, [x0, #48]\n"
        "ldp x27, x28, [x0, #64]\n"
        "ldp x29, x30, [x0, #80]\n"
        "ldr x2, [x0, #96]\n"
        "mov sp, x2\n"
        "mov w0, w9\n"
        "ret\n"
        "1:\n"
        "bl winapk_longjmp_bad\n"
        "brk #0\n" ::: "memory");
}
#undef WINAPK_A64_SAVE_REGS

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("setjmp", g_setjmp),       WINAPK_SHIM("_setjmp", g_setjmp),
    WINAPK_SHIM("sigsetjmp", g_sigsetjmp), WINAPK_SHIM("longjmp", g_longjmp),
    WINAPK_SHIM("_longjmp", g_longjmp),    WINAPK_SHIM("siglongjmp", g_longjmp),
    WINAPK_SHIM("__longjmp_chk", g_longjmp),
};

}  // namespace

ShimTable setjmp_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
