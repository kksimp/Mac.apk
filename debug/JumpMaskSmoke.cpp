// Standard valid Bionic non-local return and signal-mask behaviour.
// No fault handlers, invalid buffers or guest application code are involved.
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <signal.h>

extern "C" {
int __macapk_arm64_pthread_sigmask(int, const uint64_t*, uint64_t*);
int __macapk_arm64_sigsetjmp(void*, int) __attribute__((returns_twice));
void __macapk_arm64_siglongjmp(void*, int) __attribute__((noreturn));
int __macapk_arm64_setjmp(void*) __attribute__((returns_twice));
void __macapk_arm64_longjmp(void*, int) __attribute__((noreturn));
int __macapk_arm64__setjmp(void*) __attribute__((returns_twice));
void __macapk_arm64__longjmp(void*, int) __attribute__((noreturn));
}

constexpr uint64_t kOrdinarySignal = UINT64_C(1) << (SIGWINCH - 1);
constexpr int kBlock = 0, kUnblock = 1, kSetMask = 2;

static bool blocked() {
    uint64_t current = 0;
    if (__macapk_arm64_pthread_sigmask(kSetMask, nullptr, &current) != 0) std::exit(2);
    return (current & kOrdinarySignal) != 0;
}

static int check(int mode) {
    uint64_t before = 0;
    if (__macapk_arm64_pthread_sigmask(kUnblock, &kOrdinarySignal, &before) != 0) return 2;
    volatile int marker = 0;
    int value;
    if (mode == 0 || mode == 1) {
        alignas(16) unsigned char saved[264];
        value = __macapk_arm64_sigsetjmp(saved, mode);
        if (!value) {
            if (__macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
            marker = 17;
            __macapk_arm64_siglongjmp(saved, 29);
        }
    } else if (mode == 2) {
        alignas(16) unsigned char saved[256];
        value = __macapk_arm64_setjmp(saved);
        if (!value) {
            if (__macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
            marker = 17;
            __macapk_arm64_longjmp(saved, 29);
        }
    } else {
        alignas(16) unsigned char saved[256];
        value = __macapk_arm64__setjmp(saved);
        if (!value) {
            if (__macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
            marker = 17;
            __macapk_arm64__longjmp(saved, 29);
        }
    }
    const bool ok = value == 29 && marker == 17 && blocked() == (mode == 0 || mode == 3);
    if (__macapk_arm64_pthread_sigmask(kSetMask, &before, nullptr) != 0) return 2;
    return ok ? 0 : 1;
}

static int check_nested() {
    uint64_t before = 0;
    if (__macapk_arm64_pthread_sigmask(kUnblock, &kOrdinarySignal, &before) != 0) return 2;
    alignas(16) unsigned char outer[264];
    const int step = __macapk_arm64_sigsetjmp(outer, 1);
    if (step == 0) {
        if (__macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
        alignas(16) unsigned char inner[264];
        const int inner_step = __macapk_arm64_sigsetjmp(inner, 1);
        if (inner_step == 0) {
            if (__macapk_arm64_pthread_sigmask(kUnblock, &kOrdinarySignal, nullptr) != 0) return 2;
            __macapk_arm64_siglongjmp(inner, 1);
        }
        if (inner_step != 1 || !blocked()) {
            __macapk_arm64_pthread_sigmask(kSetMask, &before, nullptr);
            return 1;
        }
        __macapk_arm64_siglongjmp(outer, 2);
    }
    const bool ok = step == 2 && !blocked();
    if (__macapk_arm64_pthread_sigmask(kSetMask, &before, nullptr) != 0) return 2;
    return ok ? 0 : 1;
}

// Re-saving into an existing buffer must replace the saved-mask mode.
// A zero longjmp value must still be reported as one.
static int check_reuse() {
    uint64_t before = 0;
    if (__macapk_arm64_pthread_sigmask(kUnblock, &kOrdinarySignal, &before) != 0) return 2;
    alignas(16) unsigned char saved[264];
    errno = EAGAIN;
    int first = __macapk_arm64_sigsetjmp(saved, 1);
    if (!first) {
        if (errno != EAGAIN ||
            __macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
        errno = EDOM;
        __macapk_arm64_siglongjmp(saved, 0);
    }
    if (first != 1 || errno != EDOM || blocked()) {
        __macapk_arm64_pthread_sigmask(kSetMask, &before, nullptr);
        return 1;
    }

    int second = __macapk_arm64_sigsetjmp(saved, 0);
    if (!second) {
        if (__macapk_arm64_pthread_sigmask(kBlock, &kOrdinarySignal, nullptr) != 0) return 2;
        __macapk_arm64_siglongjmp(saved, 0);
    }
    const bool ok = second == 1 && blocked();
    if (__macapk_arm64_pthread_sigmask(kSetMask, &before, nullptr) != 0) return 2;
    return ok ? 0 : 1;
}

int main() {
    const char* names[] = {"sigsetjmp(0)", "sigsetjmp(1)", "setjmp", "_setjmp"};
    int failed = 0;
    for (int mode = 0; mode < 4; ++mode) {
        int rc = check(mode);
        std::printf("%s %s\n", rc == 0 ? "PASS" : "FAIL", names[mode]);
        if (rc) failed = 1;
    }
    int rc = check_nested();
    std::printf("%s independent nested saved masks\n", rc == 0 ? "PASS" : "FAIL");
    if (rc) failed = 1;
    rc = check_reuse();
    std::printf("%s reused buffer, zero return and errno preservation\n", rc == 0 ? "PASS" : "FAIL");
    return failed || rc ? 1 : 0;
}
