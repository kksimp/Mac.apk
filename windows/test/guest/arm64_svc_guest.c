// arm64_svc_guest.c -- raw Linux system calls (svc #0) from arm64 code.
//
// x8 = the number, x0-x5 = the arguments, the result (or -errno) in x0.

#define EXPORT __attribute__((visibility("default")))

static long sc3(long nr, long a, long b, long c) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

EXPORT long arm64_svc_getpid(void) { return sc3(172, 0, 0, 0); }
EXPORT long arm64_svc_gettid(void) { return sc3(178, 0, 0, 0); }
EXPORT long arm64_svc_unknown(void) { return sc3(9999, 0, 0, 0); }  // -ENOSYS

// write(fd, buf, len) to a file descriptor the test passes: the byte count back.
EXPORT long arm64_svc_write(long fd, const char* buf, long len) { return sc3(64, fd, (long)buf, len); }

// Registers live across the trap are kept: a loop that makes a call each round.
EXPORT long arm64_svc_loop(long n) {
    long sum = 0;
    for (long i = 0; i < n; ++i) sum += i * 3 + (sc3(172, 0, 0, 0) != 0);
    return sum;
}
