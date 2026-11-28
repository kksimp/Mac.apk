/* Valid ordinary nonlocal return and mask restoration, not a fault handler. */
typedef unsigned long signal_bits;
typedef long jmp_buffer[32];
typedef long sigjmp_buffer[33];
extern int pthread_sigmask(int how, const signal_bits*, signal_bits*);
extern int sigsetjmp(sigjmp_buffer, int) __attribute__((returns_twice));
extern void siglongjmp(sigjmp_buffer, int) __attribute__((noreturn));
extern int setjmp(jmp_buffer) __attribute__((returns_twice));
extern void longjmp(jmp_buffer, int) __attribute__((noreturn));
extern int _setjmp(jmp_buffer) __attribute__((returns_twice));
extern void _longjmp(jmp_buffer, int) __attribute__((noreturn));

static const signal_bits ordinary_signal = 1UL << (28 - 1); /* SIGWINCH */
#define BLOCK 0
#define UNBLOCK 1
#define SETMASK 2

__attribute__((visibility("default"))) int fixture_sigmask(int mode) {
    signal_bits before = 0, after = 0;
    if (pthread_sigmask(UNBLOCK, &ordinary_signal, &before)) return -1;
    volatile int marker = 0;
    int value;
    if (mode == 0 || mode == 1) {
        sigjmp_buffer saved;
        value = sigsetjmp(saved, mode);
        if (!value) {
            if (pthread_sigmask(BLOCK, &ordinary_signal, 0)) return -2;
            marker = 17;
            siglongjmp(saved, 29);
        }
    } else if (mode == 2) {
        jmp_buffer saved;
        value = setjmp(saved);
        if (!value) {
            if (pthread_sigmask(BLOCK, &ordinary_signal, 0)) return -2;
            marker = 17;
            longjmp(saved, 29);
        }
    } else if (mode == 3) {
        jmp_buffer saved;
        value = _setjmp(saved);
        if (!value) {
            if (pthread_sigmask(BLOCK, &ordinary_signal, 0)) return -2;
            marker = 17;
            _longjmp(saved, 29);
        }
    } else {
        return -3;
    }
    if (pthread_sigmask(SETMASK, 0, &after)) return -4;
    if (pthread_sigmask(SETMASK, &before, 0)) return -5;
    if (value != 29 || marker != 17) return -6;
    const int should_remain_blocked = mode == 0 || mode == 3;
    return ((after & ordinary_signal) != 0) == should_remain_blocked ? 1 : -7;
}
