// aapcs_call.h -- calls a function as an arm64 guest does, from a test that runs on an arm64 host.
//
// AAPCS64 passes variadic FP arguments in v0-v7; the host compiler's variadic call (Windows ARM64) puts
// them in integer registers. So the registers are loaded by hand. No stack arguments.

#pragma once

#include <cstdint>

#include "winapk/arch.h"


inline void aapcs_call(const void* fn, const uint64_t (&x)[8], const double (&d)[8], uint64_t* xr, double* dr) {
    register const void* f __asm__("x16") = fn;
    register const uint64_t* px __asm__("x9") = x;
    register const double* pd __asm__("x10") = d;
    uint64_t rx;
    double rd;
    __asm__ volatile(
        "ldp x0, x1, [x9]\n"
        "ldp x2, x3, [x9, #16]\n"
        "ldp x4, x5, [x9, #32]\n"
        "ldp x6, x7, [x9, #48]\n"
        "ldp d0, d1, [x10]\n"
        "ldp d2, d3, [x10, #16]\n"
        "ldp d4, d5, [x10, #32]\n"
        "ldp d6, d7, [x10, #48]\n"
        "blr x16\n"
        "mov %0, x0\n"
        "fmov %d1, d0\n"
        : "=&r"(rx), "=&w"(rd)
        : "r"(f), "r"(px), "r"(pd)
        : "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x11", "x12", "x13", "x14", "x15", "x17", "x30",
          "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v16", "v17", "v18", "v19", "v20", "v21", "v22", "v23",
          "v24", "v25", "v26", "v27", "v28", "v29", "v30", "v31", "cc", "memory");
    *xr = rx;
    *dr = rd;
}

