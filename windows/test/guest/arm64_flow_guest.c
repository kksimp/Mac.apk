// arm64_flow_guest.c -- control flow and edge cases of arm64 code running natively,
// above all returns that are not returns.
// test/arm64_test.cpp runs every function here and checks each result against what
// an arm64 CPU gives (the values are worked out by hand, not by the host's C compiler:
// several cases are undefined behaviour in C).
//
// Built like arm64_bridge_guest.c: plain clang for aarch64-linux-android, no
// NDK, libc imports declared by hand and bound to the Bionic shim.
//
//   * returns that are not returns: a context switch written in guest code
//     (as libunwind's jumpto, boost.context and coroutine libraries do) that
//     "returns" into an ancestor frame with RET or BR, and coroutines that
//     resume each other at the same call site on different stacks;
//   * the pointer-authenticated return RETAA, a function that returns with
//     BR x30, tail calls through a function pointer;
//   * recursion as deep as an Android thread's 1 MB stack allows, on the
//     calling thread and on a guest thread;
//   * floating point edge cases (saturating conversions, rounding, FRECPS
//     and FRSQRTS on 0 and infinity, signed zeros);
//   * an exclusive-monitor counter shared by a thread in a library and a
//     copy of the same code in memory the guest mapped,
//     and the store-buffer litmus test for STLR/LDAR.

typedef unsigned long size_t;
typedef unsigned long uint64_t;
typedef unsigned int uint32_t;
typedef int int32_t;
typedef long int64_t;
typedef unsigned long uintptr_t;

void* malloc(size_t);
void free(void*);
void* memcpy(void*, const void*, size_t);
typedef long pthread_t;
int pthread_create(pthread_t*, const void*, void* (*)(void*), void*);
int pthread_join(pthread_t, void**);
int mprotect(void*, size_t, int);
void* mmap(void*, size_t, int, int, int, long);
int munmap(void*, size_t);

#define EXPORT __attribute__((visibility("default")))
#define NOINLINE __attribute__((noinline))

// An opaque value: keeps the compiler from folding or reordering around it.
#define OPAQUE(v) __asm__ volatile("" : "+r"(v))

// ---- a context switch in guest code ---------------------------------------------------
// flow_ctx_save(buf) saves x19-x30, sp and d8-d15 and returns 0;
// flow_ctx_resume_ret(buf, v) restores them and "returns" v from that
// flow_ctx_save with RET, flow_ctx_resume_br(buf, v) with BR. flow_swap(from,
// to) saves into `from` and resumes `to`: a coroutine switch.
__asm__(
    ".text\n"
    ".p2align 2\n"
    ".globl flow_ctx_save\n"
    ".type flow_ctx_save, %function\n"
    "flow_ctx_save:\n"
    "    stp x19, x20, [x0, #0]\n"
    "    stp x21, x22, [x0, #16]\n"
    "    stp x23, x24, [x0, #32]\n"
    "    stp x25, x26, [x0, #48]\n"
    "    stp x27, x28, [x0, #64]\n"
    "    stp x29, x30, [x0, #80]\n"
    "    mov x16, sp\n"
    "    str x16, [x0, #96]\n"
    "    stp d8, d9, [x0, #104]\n"
    "    stp d10, d11, [x0, #120]\n"
    "    stp d12, d13, [x0, #136]\n"
    "    stp d14, d15, [x0, #152]\n"
    "    mov x0, #0\n"
    "    ret\n"
    ".size flow_ctx_save, .-flow_ctx_save\n"

    ".p2align 2\n"
    ".type flow_ctx_load, %function\n"
    "flow_ctx_load:\n"  // x0: the buffer; leaves the saved pc in x30
    "    ldp x19, x20, [x0, #0]\n"
    "    ldp x21, x22, [x0, #16]\n"
    "    ldp x23, x24, [x0, #32]\n"
    "    ldp x25, x26, [x0, #48]\n"
    "    ldp x27, x28, [x0, #64]\n"
    "    ldp d8, d9, [x0, #104]\n"
    "    ldp d10, d11, [x0, #120]\n"
    "    ldp d12, d13, [x0, #136]\n"
    "    ldp d14, d15, [x0, #152]\n"
    "    ldr x16, [x0, #96]\n"
    "    mov sp, x16\n"
    "    ldp x29, x30, [x0, #80]\n"
    "    ret x17\n"
    ".size flow_ctx_load, .-flow_ctx_load\n"

    ".p2align 2\n"
    ".globl flow_ctx_resume_ret\n"
    ".type flow_ctx_resume_ret, %function\n"
    "flow_ctx_resume_ret:\n"
    "    mov x9, x1\n"
    "    adr x17, 1f\n"
    "    b flow_ctx_load\n"
    "1:  mov x0, x9\n"
    "    ret\n"
    ".size flow_ctx_resume_ret, .-flow_ctx_resume_ret\n"

    ".p2align 2\n"
    ".globl flow_ctx_resume_br\n"
    ".type flow_ctx_resume_br, %function\n"
    "flow_ctx_resume_br:\n"
    "    mov x9, x1\n"
    "    adr x17, 1f\n"
    "    b flow_ctx_load\n"
    "1:  mov x0, x9\n"
    "    mov x16, x30\n"
    "    br x16\n"
    ".size flow_ctx_resume_br, .-flow_ctx_resume_br\n"

    ".p2align 2\n"
    ".globl flow_swap\n"
    ".type flow_swap, %function\n"
    "flow_swap:\n"
    "    stp x19, x20, [x0, #0]\n"
    "    stp x21, x22, [x0, #16]\n"
    "    stp x23, x24, [x0, #32]\n"
    "    stp x25, x26, [x0, #48]\n"
    "    stp x27, x28, [x0, #64]\n"
    "    stp x29, x30, [x0, #80]\n"
    "    mov x16, sp\n"
    "    str x16, [x0, #96]\n"
    "    stp d8, d9, [x0, #104]\n"
    "    stp d10, d11, [x0, #120]\n"
    "    stp d12, d13, [x0, #136]\n"
    "    stp d14, d15, [x0, #152]\n"
    // and loads `to` itself, ending in a plain RET to its saved x30
    "    ldp x19, x20, [x1, #0]\n"
    "    ldp x21, x22, [x1, #16]\n"
    "    ldp x23, x24, [x1, #32]\n"
    "    ldp x25, x26, [x1, #48]\n"
    "    ldp x27, x28, [x1, #64]\n"
    "    ldp d8, d9, [x1, #104]\n"
    "    ldp d10, d11, [x1, #120]\n"
    "    ldp d12, d13, [x1, #136]\n"
    "    ldp d14, d15, [x1, #152]\n"
    "    ldr x16, [x1, #96]\n"
    "    mov sp, x16\n"
    "    ldp x29, x30, [x1, #80]\n"
    "    ret\n"
    ".size flow_swap, .-flow_swap\n"

    // A new coroutine's first switch lands here: x19 is its body, x20 its argument.
    ".p2align 2\n"
    ".type flow_coro_start, %function\n"
    "flow_coro_start:\n"
    "    mov x0, x20\n"
    "    blr x19\n"
    "    brk #1\n"
    ".size flow_coro_start, .-flow_coro_start\n");

typedef struct {
    uint64_t r[21];
} Ctx;

int flow_ctx_save(Ctx*) __attribute__((returns_twice));
void flow_ctx_resume_ret(Ctx*, uint64_t) __attribute__((noreturn));
void flow_ctx_resume_br(Ctx*, uint64_t) __attribute__((noreturn));
void flow_swap(Ctx* from, Ctx* to);
void flow_coro_start(void);

static Ctx g_ctx;
static volatile int g_use_br;

static NOINLINE uint64_t unwind_deep(uint64_t depth, uint64_t acc) {
    acc = acc * 7 + depth;
    OPAQUE(acc);
    if (depth == 0) {
        if (g_use_br) flow_ctx_resume_br(&g_ctx, acc);
        flow_ctx_resume_ret(&g_ctx, acc);
    }
    const uint64_t r = unwind_deep(depth - 1, acc);
    OPAQUE(acc);
    return r + acc;  // never reached
}

// As a C++ throw from six frames down to a catch: the frames in between never
// return. Returns the value the innermost frame "threw", with callee-saved
// state from before the jump.
static NOINLINE uint64_t unwind_run(int use_br) {
    g_use_br = use_br;
    volatile uint64_t kept = 5;  // in memory across the jump
    register uint64_t callee_saved __asm__("x19") = 1000;
    OPAQUE(callee_saved);
    uint64_t got = (uint64_t)flow_ctx_save(&g_ctx);
    if (got == 0) {
        kept = 6;
        callee_saved = 2000;  // flow_ctx_save saved 1000
        OPAQUE(callee_saved);
        unwind_deep(5, 1);
        return 1;  // never reached
    }
    OPAQUE(callee_saved);
    return got * 1000000 + callee_saved * 10 + kept;
}

EXPORT uint64_t flow_unwind_ret(void) { return unwind_run(0); }
EXPORT uint64_t flow_unwind_br(void) { return unwind_run(1); }

// Two coroutines switch to each other through one helper, so each switch
// "returns" from the same call site in another coroutine, on its own stack.
static Ctx g_main_ctx, g_co_ctx[2];
static volatile uint64_t g_co_out;

static NOINLINE void yield_to(Ctx* from, Ctx* to) {
    flow_swap(from, to);
    __asm__ volatile("" ::: "memory");
}

static void co_body(uint64_t id) {
    uint64_t local = id * 100;
    for (uint64_t i = 0;; ++i) {
        local = local * 3 + i;
        g_co_out = local;
        yield_to(&g_co_ctx[id], id == 0 ? &g_co_ctx[1] : &g_main_ctx);
    }
}

EXPORT uint64_t flow_coroutines(uint64_t rounds) {
    const size_t size = 64 * 1024;
    char* stacks[2];
    for (int id = 0; id < 2; ++id) {
        stacks[id] = (char*)malloc(size);
        if (!stacks[id]) return 0;
        Ctx* c = &g_co_ctx[id];
        for (int i = 0; i < 21; ++i) c->r[i] = 0;
        c->r[0] = (uint64_t)&co_body;                         // x19
        c->r[1] = (uint64_t)id;                               // x20
        c->r[11] = (uint64_t)&flow_coro_start;                // x30
        c->r[12] = ((uint64_t)(stacks[id] + size)) & ~15ul;  // sp
    }
    uint64_t acc = 0;
    // main -> 0 -> 1 -> main, each round; each coroutine's own count goes on.
    for (uint64_t r = 0; r < rounds; ++r) {
        yield_to(&g_main_ctx, &g_co_ctx[0]);
        acc = acc * 31 + g_co_out;
    }
    free(stacks[0]);
    free(stacks[1]);
    return acc;
}

// ---- returns and jumps -----------------------------------------------------------------
__asm__(
    ".text\n"
    ".p2align 2\n"
    ".arch armv8.3-a\n"
    // RETAA returns to x30 (authenticated with sp): its Rn field is 11111.
    ".globl flow_retaa\n"
    ".type flow_retaa, %function\n"
    "flow_retaa:\n"
    "    paciasp\n"
    "    stp x29, x30, [sp, #-16]!\n"
    "    mov x29, sp\n"
    "    add x0, x0, #1\n"
    "    ldp x29, x30, [sp], #16\n"
    "    retaa\n"
    ".size flow_retaa, .-flow_retaa\n"
    ".arch armv8-a\n"

    // Returns through BR, as hand-written code may: from the host's call
    // that is a jump to the bridge's return address.
    ".p2align 2\n"
    ".globl flow_br_lr\n"
    ".type flow_br_lr, %function\n"
    "flow_br_lr:\n"
    "    add x0, x0, #2\n"
    "    mov x16, x30\n"
    "    br x16\n"
    ".size flow_br_lr, .-flow_br_lr\n");

__asm__(
    ".text\n"
    // Two entries into one body: flow_multi falls through into
    // flow_multi_mid, which is also a function of its own (as a symbol, an
    // .eh_frame_hdr entry or a BL target inside another function can be).
    ".p2align 2\n"
    ".globl flow_multi\n"
    ".type flow_multi, %function\n"
    "flow_multi:\n"
    "    add x0, x0, #1\n"
    "    cbz x1, flow_multi_mid\n"
    "    add x0, x0, #100\n"
    ".globl flow_multi_mid\n"
    ".type flow_multi_mid, %function\n"
    "flow_multi_mid:\n"
    "    add x0, x0, #10\n"
    "    ret\n"
    ".size flow_multi_mid, .-flow_multi_mid\n"
    ".size flow_multi, .-flow_multi\n");

uint64_t flow_multi(uint64_t, uint64_t);
uint64_t flow_multi_mid(uint64_t);

// Each entry, called directly.
EXPORT uint64_t flow_entries(uint64_t x) {
    uint64_t r = flow_multi(x, 0);
    OPAQUE(r);
    r = r * 1000 + flow_multi(x, 1);
    OPAQUE(r);
    return r * 1000 + flow_multi_mid(x);
}

uint64_t flow_retaa(uint64_t);
EXPORT uint64_t flow_br_lr(uint64_t);

EXPORT uint64_t flow_call_retaa(uint64_t x) {
    uint64_t r = flow_retaa(x);
    OPAQUE(r);
    return r * 2;
}

static NOINLINE uint64_t add_three(uint64_t x) { return x + 3; }
static NOINLINE uint64_t times_five(uint64_t x) { return x * 5; }
static uint64_t (*volatile g_fns[2])(uint64_t) = {add_three, times_five};

// A tail call through a function pointer (BR) and a call through one (BLR).
static NOINLINE uint64_t tail_call(uint64_t i, uint64_t x) { return g_fns[i & 1](x); }

EXPORT uint64_t flow_calls(uint64_t n) {
    uint64_t acc = 1;
    for (uint64_t i = 0; i < n; ++i) acc = tail_call(i, acc) + flow_br_lr(i) + flow_retaa(i);
    return acc;
}

// ---- deep recursion --------------------------------------------------------------------
// A small frame (32 bytes) and no tail call: n frames of guest stack.
EXPORT NOINLINE uint64_t flow_deep(uint64_t n) {
    if (n == 0) return 1;
    uint64_t r = flow_deep(n - 1);
    OPAQUE(r);
    return r * 3 + n;
}

static uint64_t (*volatile g_deep_ptr)(uint64_t);

// The same through a function pointer each time.
static NOINLINE uint64_t deep_indirect(uint64_t n) {
    if (n == 0) return 1;
    uint64_t r = g_deep_ptr(n - 1);
    OPAQUE(r);
    return r * 3 + n;
}

EXPORT uint64_t flow_deep_indirect(uint64_t n) {
    g_deep_ptr = deep_indirect;
    return deep_indirect(n);
}

static void* deep_thread(void* arg) {
    return (void*)(flow_deep((uint64_t)arg) * 3 + flow_deep_indirect((uint64_t)arg));
}

// On a guest thread with the default attributes: a 1 MB stack, as on Android.
EXPORT uint64_t flow_deep_thread(uint64_t n) {
    pthread_t t;
    if (pthread_create(&t, 0, deep_thread, (void*)n) != 0) return 0;
    void* r = 0;
    if (pthread_join(t, &r) != 0) return 0;
    return (uint64_t)r;
}

// ---- variadic functions in guest code ----------------------------------------------------
// The prologue spills x1-x7 and q0-q7 to the va_list save areas; va_arg
// walks them, then the stack. (An open case: a variadic function of Waze's
// libcrashlytics-common.so.)
typedef __builtin_va_list va_list;

static NOINLINE double vsum(int n, va_list ap) {
    double s = 0;
    for (int i = 0; i < n; ++i) {
        s = s * 3 + (i % 3 == 2 ? __builtin_va_arg(ap, double) : (double)__builtin_va_arg(ap, long));
    }
    return s;
}

static NOINLINE double sum_va(int n, ...) {
    va_list ap;
    __builtin_va_start(ap, n);
    const double s = vsum(n, ap);
    __builtin_va_end(ap);
    return s;
}

// 12 values: 8 integers (7 in registers, then the stack) and 4 doubles.
EXPORT long flow_variadic(void) {
    const double s = sum_va(12, 1L, 2L, 3.5, 4L, 5L, 6.25, 7L, 8L, 9.75, 10L, 11L, 12.5);
    return (long)(s * 4);
}

// ---- floating point edge cases -----------------------------------------------------------
// Each writes its results to out[] and returns how many.
static inline uint32_t bits_f(float f) {
    uint32_t u;
    __builtin_memcpy(&u, &f, 4);
    return u;
}

EXPORT int flow_fp_convert(const double* in, int n, int64_t* out) {
    int k = 0;
    for (int i = 0; i < n; ++i) {
        const double d = in[i];
        const float f = (float)d;
        int64_t x;
        int32_t w;
        uint64_t ux;
        uint32_t uw;
        __asm__("fcvtzs %0, %d1" : "=r"(x) : "w"(d));
        out[k++] = x;
        __asm__("fcvtzs %w0, %d1" : "=r"(w) : "w"(d));
        out[k++] = w;
        __asm__("fcvtzu %0, %d1" : "=r"(ux) : "w"(d));
        out[k++] = (int64_t)ux;
        __asm__("fcvtzu %w0, %s1" : "=r"(uw) : "w"(f));
        out[k++] = uw;
        __asm__("fcvtns %0, %d1" : "=r"(x) : "w"(d));
        out[k++] = x;
        __asm__("fcvtas %0, %d1" : "=r"(x) : "w"(d));
        out[k++] = x;
        __asm__("fcvtms %w0, %s1" : "=r"(w) : "w"(f));
        out[k++] = w;
        __asm__("fcvtps %w0, %s1" : "=r"(w) : "w"(f));
        out[k++] = w;
        double r;
        __asm__("frintn %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
        __asm__("frinta %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
        __asm__("frintm %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
        __asm__("frintp %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
        __asm__("frintz %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
        __asm__("frintx %d0, %d1" : "=w"(r) : "w"(d));
        out[k++] = (int64_t)(r * 4);
    }
    return k;
}

typedef float v4f __attribute__((vector_size(16)));
typedef int v4i __attribute__((vector_size(16)));
typedef unsigned v4u __attribute__((vector_size(16)));

// Vector forms on four lanes at once: in[0..3] -> 4 results each.
EXPORT int flow_fp_vector(const float* in, uint32_t* out) {
    v4f a;
    __builtin_memcpy(&a, in, 16);
    v4i s;
    v4u u;
    v4f r;
    int k = 0;
    __asm__("fcvtzs %0.4s, %1.4s" : "=w"(s) : "w"(a));
    for (int i = 0; i < 4; ++i) out[k++] = (uint32_t)s[i];
    __asm__("fcvtzu %0.4s, %1.4s" : "=w"(u) : "w"(a));
    for (int i = 0; i < 4; ++i) out[k++] = u[i];
    __asm__("fcvtns %0.4s, %1.4s" : "=w"(s) : "w"(a));
    for (int i = 0; i < 4; ++i) out[k++] = (uint32_t)s[i];
    __asm__("frinta %0.4s, %1.4s" : "=w"(r) : "w"(a));
    for (int i = 0; i < 4; ++i) out[k++] = bits_f(r[i]);
    __asm__("frintn %0.4s, %1.4s" : "=w"(r) : "w"(a));
    for (int i = 0; i < 4; ++i) out[k++] = bits_f(r[i]);
    return k;
}

// The Newton-Raphson steps: FRECPS(a, b) = 2 - a*b and FRSQRTS(a, b) =
// (3 - a*b) / 2, fused, and 2.0 and 1.5 for 0 times infinity, which is what
// keeps a reciprocal of 0 at infinity instead of NaN.
EXPORT int flow_fp_steps(const float* a_in, const float* b_in, uint32_t* out) {
    v4f a, b, r;
    __builtin_memcpy(&a, a_in, 16);
    __builtin_memcpy(&b, b_in, 16);
    int k = 0;
    __asm__("frecps %0.4s, %1.4s, %2.4s" : "=w"(r) : "w"(a), "w"(b));
    for (int i = 0; i < 4; ++i) out[k++] = bits_f(r[i]);
    __asm__("frsqrts %0.4s, %1.4s, %2.4s" : "=w"(r) : "w"(a), "w"(b));
    for (int i = 0; i < 4; ++i) out[k++] = bits_f(r[i]);
    float s;
    __asm__("frecps %s0, %s1, %s2" : "=w"(s) : "w"(a[0]), "w"(b[0]));
    out[k++] = bits_f(s);
    __asm__("frsqrts %s0, %s1, %s2" : "=w"(s) : "w"(a[0]), "w"(b[0]));
    out[k++] = bits_f(s);
    // FMAX/FMIN/FMAXNM/FMINNM on signed zeros and a NaN
    const float pz = 0.0f, nz = -0.0f, one = 1.0f;
    float nan;
    const uint32_t nan_bits = 0x7fc00000;
    __builtin_memcpy(&nan, &nan_bits, 4);
    __asm__("fmax %s0, %s1, %s2" : "=w"(s) : "w"(nz), "w"(pz));
    out[k++] = bits_f(s);
    __asm__("fmin %s0, %s1, %s2" : "=w"(s) : "w"(pz), "w"(nz));
    out[k++] = bits_f(s);
    __asm__("fmaxnm %s0, %s1, %s2" : "=w"(s) : "w"(nan), "w"(one));
    out[k++] = bits_f(s);
    __asm__("fminnm %s0, %s1, %s2" : "=w"(s) : "w"(one), "w"(nan));
    out[k++] = bits_f(s);
    return k;
}

// ---- threads: an exclusive-monitor counter, and code the library itself maps executable ----
// flow_atomic_add(p, n): n increments of *p with LDAXR/STLXR. flow_mixed
// copies its code into memory it maps executable (as a library's own JIT does)
// and runs that copy on a second thread while this thread runs the library's.
__asm__(
    ".text\n"
    ".p2align 2\n"
    ".globl flow_atomic_add\n"
    ".type flow_atomic_add, %function\n"
    "flow_atomic_add:\n"
    "    cbz x1, 3f\n"
    "1:  ldaxr x2, [x0]\n"
    "    add x2, x2, #1\n"
    "    stlxr w3, x2, [x0]\n"
    "    cbnz w3, 1b\n"
    "    subs x1, x1, #1\n"
    "    b.ne 1b\n"
    "3:  ret\n"
    ".size flow_atomic_add, .-flow_atomic_add\n"
    ".globl flow_atomic_add_end\n"
    "flow_atomic_add_end:\n");

void flow_atomic_add(volatile uint64_t* p, uint64_t n);
extern const char flow_atomic_add_end[];

typedef struct {
    void (*fn)(volatile uint64_t*, uint64_t);
    volatile uint64_t* counter;
    uint64_t n;
} AddJob;

static void* add_thread(void* arg) {
    AddJob* job = (AddJob*)arg;
    job->fn(job->counter, job->n);
    return 0;
}

EXPORT uint64_t flow_mixed(uint64_t n) {
    const size_t len = (size_t)(flow_atomic_add_end - (const char*)&flow_atomic_add);
    void* code = mmap(0, 4096, 3 /* PROT_READ | PROT_WRITE */, 0x22 /* MAP_PRIVATE | MAP_ANONYMOUS */, -1, 0);
    if (code == (void*)-1) return 1;
    memcpy(code, (const void*)&flow_atomic_add, len);
    if (mprotect(code, 4096, 5 /* PROT_READ | PROT_EXEC */) != 0) return 2;
    static volatile uint64_t counter;
    counter = 0;
    AddJob job = {(void (*)(volatile uint64_t*, uint64_t))code, &counter, n};
    pthread_t t;
    if (pthread_create(&t, 0, add_thread, &job) != 0) return 3;
    flow_atomic_add(&counter, n);
    pthread_join(t, 0);
    munmap(code, 4096);
    return counter;
}

// The store-buffer litmus test: each thread stores 1 to its variable with
// STLR, then reads the other's with LDAR. On arm64 they cannot both read 0
// (a release followed by an acquire is not reordered); x86 reorders a plain
// store with a later load. Returns how many rounds both read 0 (must be 0).
static volatile uint64_t g_x, g_y, g_epoch, g_done_b, g_rb;

static void* litmus_b(void* arg) {
    const uint64_t rounds = (uint64_t)arg;
    for (uint64_t e = 1; e <= rounds; ++e) {
        while (__atomic_load_n(&g_epoch, __ATOMIC_ACQUIRE) != e) {
        }
        __atomic_store_n(&g_y, 1, __ATOMIC_SEQ_CST);
        g_rb = __atomic_load_n(&g_x, __ATOMIC_SEQ_CST);
        __atomic_store_n(&g_done_b, e, __ATOMIC_RELEASE);
    }
    return 0;
}

EXPORT uint64_t flow_litmus(uint64_t rounds) {
    g_epoch = 0;
    g_done_b = 0;
    pthread_t t;
    if (pthread_create(&t, 0, litmus_b, (void*)rounds) != 0) return ~0ul;
    uint64_t both_zero = 0;
    for (uint64_t e = 1; e <= rounds; ++e) {
        g_x = 0;
        g_y = 0;
        __atomic_store_n(&g_epoch, e, __ATOMIC_RELEASE);
        __atomic_store_n(&g_x, 1, __ATOMIC_SEQ_CST);
        const uint64_t ra = __atomic_load_n(&g_y, __ATOMIC_SEQ_CST);
        while (__atomic_load_n(&g_done_b, __ATOMIC_ACQUIRE) != e) {
        }
        if (ra == 0 && g_rb == 0) ++both_zero;
    }
    pthread_join(t, 0);
    return both_zero;
}
