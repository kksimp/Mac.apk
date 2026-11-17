// arm64_signal_guest.c -- signal delivery to arm64 guest handlers: faults, raise, other threads.
//
// Bionic arm64's siginfo_t and ucontext_t. What the handler leaves in the ucontext is what the thread
// resumes with (Mono, Dart and Breakpad redirect pc this way).

#define EXPORT __attribute__((visibility("default")))
#define NOINLINE __attribute__((noinline, no_stack_protector))

typedef unsigned long u64;
struct sa {  // Bionic LP64: sa_flags first
    int flags, pad;
    void* handler;
    u64 mask;
    void* restorer;
};
struct stk {
    void* sp;
    int flags;
    u64 size;
};
typedef struct {
    int signo, err, code, pad;
    union {
        struct {
            int pid;
            unsigned uid;
        } kill;
        void* addr;
    } u;
    char fill[104];
} siginfo;
typedef struct {
    u64 flags;
    void* link;
    struct stk stack;
    u64 sigmask;
    char unused[120];
    struct {
        u64 fault_address;
        u64 regs[31];
        u64 sp, pc, pstate;
        __attribute__((aligned(16))) unsigned char reserved[4096];
    } mc __attribute__((aligned(16)));
} uctx;

int sigaction(int, const struct sa*, struct sa*);
int sigaltstack(const struct stk*, struct stk*);
int sigprocmask(int, const u64*, u64*);
int raise(int);
void* mmap(void*, u64, int, int, int, long);
int mprotect(void*, u64, int);
int munmap(void*, u64);
typedef u64 pthread_t;
int pthread_create(pthread_t*, const void*, void* (*)(void*), void*);
int pthread_join(pthread_t, void**);
int pthread_kill(pthread_t, int);
int sigsetjmp(long*, int);
void siglongjmp(long*, int);
int gettid(void);
int sched_yield(void);

#define SIGTRAP 5
#define SIGSEGV 11
#define SIGUSR1 10
#define SA_SIGINFO 4
#define SA_ONSTACK 0x08000000

static volatile int g_count, g_code, g_signo, g_tid, g_on_alt, g_ss_flags, g_fp_ok, g_mask_blocked, g_pc_ok;
static void* volatile g_addr;

NOINLINE static int recovered_leaf(void) { return 77; }
NOINLINE static int bad_leaf(void) { return *(volatile int*)16; }

static void install(int sig, void* handler, int flags) {
    struct sa a = {flags, 0, handler, 0, 0};
    sigaction(sig, &a, 0);
}
static void uninstall(int sig) { install(sig, 0, 0); }

// ---- a fault, the handler redirects pc ------------------------------------------------------

static void on_segv_redirect(int sig, siginfo* info, void* ucp) {
    uctx* uc = ucp;
    (void)sig;
    ++g_count;
    g_code = info->code;
    g_addr = info->u.addr;
    g_pc_ok = uc->mc.pc != 0 && uc->mc.sp != 0 && uc->mc.fault_address == (u64)info->u.addr;
    g_fp_ok = *(unsigned*)uc->mc.reserved == 0x46508001u;
    uc->mc.pc = (u64)&recovered_leaf;
}
EXPORT int sig_redirect(void) {
    g_count = 0;
    install(SIGSEGV, on_segv_redirect, SA_SIGINFO);
    int r = bad_leaf();
    uninstall(SIGSEGV);
    return r;
}
EXPORT int sig_code(void) { return g_code; }
EXPORT void* sig_addr(void) { return g_addr; }
EXPORT int sig_count(void) { return g_count; }
EXPORT int sig_ucontext_ok(void) { return g_pc_ok && g_fp_ok; }

// ---- the handler fixes the mapping, the access is retried -------------------------------------------

static void* g_page;
static void on_segv_fix(int sig, siginfo* info, void* uc) {
    (void)sig;
    (void)uc;
    ++g_count;
    g_code = info->code;
    if (info->u.addr == g_page) mprotect(g_page, 4096, 3);  // PROT_READ | PROT_WRITE
}
EXPORT int sig_fix_mapping(void) {
    g_count = 0;
    g_page = mmap(0, 4096, 0, 0x22, -1, 0);  // PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS
    if (g_page == (void*)-1) return -1;
    install(SIGSEGV, on_segv_fix, SA_SIGINFO);
    int v = *(volatile int*)g_page;
    uninstall(SIGSEGV);
    munmap(g_page, 4096);
    return v == 0 && g_count == 1 ? 1 : -2;
}

// ---- the handler changes a register and skips the instruction ---------------------------------------

__asm__(
    ".text\n"
    ".global arm64_fault_ret\n"
    ".type arm64_fault_ret,%function\n"
    "arm64_fault_ret:\n"
    "  mov x1, #16\n"
    "  ldr x0, [x1]\n"
    "  ret\n");
extern long arm64_fault_ret(void);
static void on_segv_regs(int sig, siginfo* info, void* ucp) {
    uctx* uc = ucp;
    (void)sig;
    (void)info;
    uc->mc.regs[0] = 1234;
    uc->mc.pc += 4;  // past the load
}
EXPORT long sig_regs(void) {
    install(SIGSEGV, on_segv_regs, SA_SIGINFO);
    long r = arm64_fault_ret();
    uninstall(SIGSEGV);
    return r;
}

// ---- brk is SIGTRAP ----------------------------------------------------------------------------------

static void on_trap(int sig, siginfo* info, void* ucp) {
    uctx* uc = ucp;
    ++g_count;
    g_signo = sig;
    g_code = info->code;
    uc->mc.pc += 4;
}
EXPORT int sig_trap(void) {
    g_count = 0;
    install(SIGTRAP, on_trap, SA_SIGINFO);
    __asm__ volatile("brk #1");
    uninstall(SIGTRAP);
    return g_count == 1 && g_signo == SIGTRAP ? 1 : -1;
}

// ---- raise, with siginfo and ucontext --------------------------------------------------------------

static void on_usr1(int sig, siginfo* info, void* ucp) {
    uctx* uc = ucp;
    ++g_count;
    g_signo = sig;
    g_code = info->code;
    g_pc_ok = uc->mc.pc != 0 && uc->mc.sp != 0;
    g_fp_ok = *(unsigned*)uc->mc.reserved == 0x46508001u;
    u64 cur = 0;
    sigprocmask(0, 0, &cur);
    g_mask_blocked = (cur >> (sig - 1)) & 1;  // blocked while its handler runs
    g_tid = gettid();
}
EXPORT int sig_raise(void) {
    g_count = 0;
    install(SIGUSR1, on_usr1, SA_SIGINFO);
    raise(SIGUSR1);
    uninstall(SIGUSR1);
    return g_count == 1 && g_signo == SIGUSR1 && g_pc_ok && g_fp_ok ? 1 : -1;
}
EXPORT int sig_raise_code(void) { return g_code; }
EXPORT int sig_mask_blocked(void) { return g_mask_blocked; }

// ---- the alternate stack -----------------------------------------------------------------------------

static char g_alt[65536] __attribute__((aligned(16)));
static void on_alt(int sig, siginfo* info, void* ucp) {
    uctx* uc = ucp;
    (void)sig;
    (void)info;
    char here;
    g_on_alt = &here >= g_alt && &here < g_alt + sizeof g_alt;
    g_ss_flags = uc->stack.flags;
    uc->mc.pc = (u64)&recovered_leaf;
}
EXPORT int sig_altstack(void) {
    struct stk ss = {g_alt, 0, sizeof g_alt};
    if (sigaltstack(&ss, 0) != 0) return -1;
    install(SIGSEGV, on_alt, SA_SIGINFO | SA_ONSTACK);
    int r = bad_leaf();
    uninstall(SIGSEGV);
    struct stk off = {0, 2, 0};  // SS_DISABLE
    sigaltstack(&off, 0);
    // uc_stack describes the stack the signal interrupted (SS_ONSTACK only when that was the alternate one)
    return r == 77 && g_on_alt && !(g_ss_flags & 1) ? 1 : -2;
}

// ---- siglongjmp out of the handler -------------------------------------------------------------------

static long g_jmp[32];
static void on_segv_jump(int sig, siginfo* info, void* uc) {
    (void)sig;
    (void)info;
    (void)uc;
    siglongjmp(g_jmp, 1);
}
EXPORT int sig_longjmp(void) {
    install(SIGSEGV, on_segv_jump, SA_SIGINFO);
    int hits = 0;
    for (int i = 0; i < 3; ++i) {
        if (sigsetjmp(g_jmp, 1) == 0) {
            (void)bad_leaf();
            return -1;  // not reached
        }
        ++hits;  // the mask came back with the jump, or the next fault would find SIGSEGV blocked
    }
    uninstall(SIGSEGV);
    return hits == 3 ? 1 : -2;
}

// ---- a signal to another thread ----------------------------------------------------------------------

static volatile int g_go, g_hit, g_worker_tid, g_handler_tid;
static void on_usr1_thread(int sig) {
    (void)sig;
    g_handler_tid = gettid();
    g_hit = 1;
}
static void* worker(void* arg) {
    (void)arg;
    g_worker_tid = gettid();
    g_go = 1;
    for (int i = 0; i < 20000000 && !g_hit; ++i) sched_yield();
    return 0;
}
EXPORT int sig_thread(void) {
    g_go = g_hit = g_handler_tid = 0;
    install(SIGUSR1, on_usr1_thread, 0);
    pthread_t t;
    if (pthread_create(&t, 0, worker, 0) != 0) return -1;
    for (int i = 0; i < 20000000 && !g_go; ++i) sched_yield();
    pthread_kill(t, SIGUSR1);
    pthread_join(t, 0);
    uninstall(SIGUSR1);
    return g_hit && g_handler_tid == g_worker_tid && g_handler_tid != gettid() ? 1 : -2;
}
