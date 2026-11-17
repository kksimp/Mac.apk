// arm64_tls_guest.c -- ELF TLS through TLSDESC on arm64 (winapk-arm64-test).
//
// Built for API 29 with -fno-emulated-tls, as an NDK build for minSdk 29 or
// later is: every access below is a TLSDESC call (adrp/ldr/add/blr, then
// mrs tpidr_el0 + add), relocated by R_AARCH64_TLSDESC. Such a call may change
// only x0, x1, lr and the flags, so the compiler keeps other values, a double
// in a vector register too, live across it: tls_mix checks they survive.

#define EXPORT __attribute__((visibility("default")))

static __thread long t_counter = 5;  // .tdata
static __thread long t_zero;         // .tbss
EXPORT __thread long tls_global = 7; // through its symbol

EXPORT long tls_bump(void) {
    t_zero += 2;
    return ++t_counter + t_zero;
}

EXPORT long tls_addr(void) { return (long)&t_counter; }

EXPORT long tls_mix(long a, long b, long c, long d) {
    double f = (double)a * 1.5;
    long r = a + t_counter;
    r += b * t_zero;
    r += c - tls_global;
    tls_global += 1;
    return r + d + (long)(f * (double)t_counter);
}
