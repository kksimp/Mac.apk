// signal.cpp -- signals for guest code: the calls that set them up, and their
// delivery.
//
// Windows has no POSIX signals, but Android code depends on them in two ways:
//
//   * Faults. Crash reporters (Breakpad, Crashlytics, Sentry, Unity's) install
//     SIGSEGV/SIGABRT/... handlers with sigaction and expect to run when the
//     app crashes; managed runtimes (Mono, .NET, Dart) take SIGSEGV for their
//     null checks and stack guards and *recover*: the handler moves pc, or
//     fixes a mapping, and returns. Those arrive here as Windows exceptions:
//     a vectored exception handler (fault_handler) maps the exception to the
//     signal, builds a Bionic-layout siginfo_t and ucontext_t from the
//     CONTEXT, calls the recorded handler (on the alternate stack if the
//     action asks for it) and writes the register file back, so what the
//     handler changed is what the thread resumes with.
//   * Self-sent signals: raise(), kill(getpid(), sig), pthread_kill, tgkill,
//     and abort(), which is raise(SIGABRT). To the calling thread they are
//     delivered right away, as a call; to another thread by suspending it,
//     parking its registers on its own stack and resuming it in
//     winapk_sig_async_entry, which runs the handler there and restores the
//     registers (RtlRestoreContext). A signal blocked by the target's mask is
//     left pending and delivered when sigprocmask unblocks it.
//
// Semantics follow Linux and ART's libsigchain (what a device does, upstream's
// bionic_shim_sigchain.cpp being the reference):
//   - the handler runs with uc_sigmask | sa_mask | the signal (unless
//     SA_NODEFER) blocked, SA_RESETHAND resets the action first, SA_ONSTACK
//     switches to the sigaltstack unless already on it;
//   - a fault with no handler, an ignored one, or one that arrives while the
//     signal is blocked, is the crash: the handler returns
//     EXCEPTION_CONTINUE_SEARCH and the runtime's crash report (and HotSpot's
//     own handling, for its faults) take over;
//   - a handler that returns without changing anything (pc, or the mapping
//     it faulted on) gets the same fault again; after kMaxNoProgress times it
//     is treated as having declined, so a reporter that returns ends in the
//     crash report and not in an endless loop.
// What is not delivered: HotSpot's own faults (JIT code, jvm.dll), which it
// handles in its structured exception handler after every vectored one has
// passed.
//
// Layouts (arm64, LP64): stack_t {sp, flags, size}, sigset_t and sigset64_t one
// 64-bit word, siginfo_t 128 bytes, ucontext_t the kernel's (GuestUcontextA64).
// POSIX timers are recorded only: they do not fire.

#include "bionic_internal.h"

#include "winapk/arch.h"
#include "winapk/context.h"
#include "winapk/elf_loader.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <atomic>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

#include <windows.h>

namespace winapk::bionic {

namespace {

constexpr int kSigCount = 64;  // signals 1..64
// Linux signal numbers (include/uapi/asm-generic/signal.h).
constexpr int kSigQuit = 3, kSigIll = 4, kSigTrap = 5, kSigAbrt = 6, kSigBus = 7, kSigFpe = 8, kSigKill = 9,
              kSigSegv = 11, kSigChld = 17, kSigCont = 18, kSigStop = 19, kSigTstp = 20, kSigTtin = 21, kSigTtou = 22,
              kSigUrg = 23, kSigXcpu = 24, kSigXfsz = 25, kSigWinch = 28, kSigSys = 31;
constexpr uint64_t bit(int sig) { return uint64_t{1} << (sig - 1); }
constexpr uint64_t kUnblockable = bit(kSigKill) | bit(kSigStop);
// sa_flags (include/uapi/asm-generic/signal-defs.h).
constexpr uint32_t kSaSiginfo = 0x4, kSaOnstack = 0x08000000, kSaRestart = 0x10000000, kSaNodefer = 0x40000000,
                   kSaResethand = 0x80000000;
// si_code values (include/uapi/asm-generic/siginfo.h).
constexpr int kSiTkill = -6;  // raise() and kill() are tgkill() in Bionic
constexpr int kSegvMaperr = 1, kSegvAccerr = 2, kBusAdraln = 1, kBusAdrerr = 2, kIllIllopc = 1, kIllPrvopc = 5,
              kFpeIntdiv = 1, kFpeIntovf = 2, kFpeFltdiv = 3, kFpeFltovf = 4, kFpeFltund = 5, kFpeFltres = 6,
              kFpeFltinv = 7, kFpeFltsub = 8, kTrapBrkpt = 1, kTrapTrace = 2;
constexpr int kSigBlock = 0, kSigUnblock = 1, kSigSetmask = 2;
constexpr int kSsOnstack = 1, kSsDisable = 2;
constexpr uint64_t kMinSigStkSz = 2048;
constexpr uintptr_t kSigDfl = 0, kSigIgn = 1;
constexpr int kMaxNoProgress = 3;
constexpr uint32_t kAppUid = 10100;  // misc.cpp's getuid

bool valid(int sig) { return sig >= 1 && sig <= kSigCount; }

const char* signal_name(int sig) {
    static const char* const kNames[] = {"", "SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGABRT", "SIGBUS",
                                         "SIGFPE", "SIGKILL", "SIGUSR1", "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM",
                                         "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP", "SIGTSTP", "SIGTTIN",
                                         "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH",
                                         "SIGIO", "SIGPWR", "SIGSYS"};
    return sig >= 1 && sig <= 31 ? kNames[sig] : "SIGRT";
}

// ---- Bionic's layouts -------------------------------------------------------------
struct GuestSigaction {
    int32_t flags;
    int32_t pad;
    void* handler;  // sa_handler and sa_sigaction share the slot
    uint64_t mask;
    void* restorer;
};
static_assert(sizeof(GuestSigaction) == 32 && offsetof(GuestSigaction, handler) == 8 &&
                  offsetof(GuestSigaction, mask) == 16,
              "Bionic LP64 struct sigaction");

struct GuestStack {
    void* sp;
    int32_t flags;
    int32_t pad;
    uint64_t size;
};
static_assert(sizeof(GuestStack) == 24, "Linux LP64 stack_t");

struct GuestSiginfo {
    int32_t signo, errno_, code, pad;
    union {
        struct {
            int32_t pid;
            uint32_t uid;
            uint64_t value;  // _rt._sigval
        } kill;
        struct {
            void* addr;
        } fault;
        uint8_t fill[112];
    };
};
static_assert(sizeof(GuestSiginfo) == 128 && offsetof(GuestSiginfo, fault) == 16, "Linux siginfo_t");

// Bionic's arm64 ucontext_t is the kernel's: sigcontext (fault address, x0-x30, sp, pc, pstate) and, in its
// reserved area, an fpsimd_context (fpsr, fpcr, v0-v31) that ends the list with a zero header.
struct GuestSigcontextA64 {
    uint64_t fault_address;
    uint64_t regs[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
    alignas(16) uint8_t reserved[4096];
};
struct GuestFpsimdA64 {
    uint32_t magic;  // 0x46508001
    uint32_t size;   // 528
    uint32_t fpsr;
    uint32_t fpcr;
    uint8_t vregs[32][16];
};
static_assert(sizeof(GuestFpsimdA64) == 528, "struct fpsimd_context");
struct GuestUcontextA64 {
    uint64_t flags;
    GuestUcontextA64* link;
    GuestStack stack;
    uint64_t sigmask;
    uint8_t unused[128 - sizeof(uint64_t)];  // the kernel's sigset_t is 1024 bits
    alignas(16) GuestSigcontextA64 mcontext;
};
static_assert(offsetof(GuestUcontextA64, mcontext) == 176 && offsetof(GuestSigcontextA64, reserved) == 288 &&
                  sizeof(GuestUcontextA64) == 4560,
              "Bionic arm64 ucontext_t");

using Handler1 = void(WINAPK_GUEST_ABI*)(int);
using Handler3 = void(WINAPK_GUEST_ABI*)(int, GuestSiginfo*, GuestUcontextA64*);

// ---- sigset_t -------------------------------------------------------------------------
WINAPK_GUEST_ABI int g_sigemptyset(uint64_t* set) {
    if (!set) return *guest_errno() = kEINVAL, -1;
    *set = 0;
    return 0;
}
WINAPK_GUEST_ABI int g_sigfillset(uint64_t* set) {
    if (!set) return *guest_errno() = kEINVAL, -1;
    *set = ~uint64_t{0};
    return 0;
}
WINAPK_GUEST_ABI int g_sigaddset(uint64_t* set, int sig) {
    if (!set || !valid(sig)) return *guest_errno() = kEINVAL, -1;
    *set |= bit(sig);
    return 0;
}
WINAPK_GUEST_ABI int g_sigdelset(uint64_t* set, int sig) {
    if (!set || !valid(sig)) return *guest_errno() = kEINVAL, -1;
    *set &= ~bit(sig);
    return 0;
}
WINAPK_GUEST_ABI int g_sigismember(const uint64_t* set, int sig) {
    if (!set || !valid(sig)) return *guest_errno() = kEINVAL, -1;
    return (*set & bit(sig)) ? 1 : 0;
}

// ---- the actions ----------------------------------------------------------------------
// One action per signal. The lock is a spin lock that knows its owner: a
// delivery injected into a thread that was suspended inside sigaction reads
// without it (a torn read of a record being replaced is the only harm, and the
// alternative is a thread waiting on itself).
class ActionLock {
public:
    void lock() {
        const uint32_t me = GetCurrentThreadId();
        uint32_t none = 0;
        while (!owner_.compare_exchange_weak(none, me, std::memory_order_acquire)) {
            none = 0;
            SwitchToThread();
        }
    }
    void unlock() { owner_.store(0, std::memory_order_release); }
    bool held_by_me() const { return owner_.load(std::memory_order_relaxed) == GetCurrentThreadId(); }

private:
    std::atomic<uint32_t> owner_{0};
};
ActionLock g_actions_lock;
GuestSigaction g_actions[kSigCount + 1];  // zero: SIG_DFL

GuestSigaction read_action(int sig) {
    if (g_actions_lock.held_by_me()) return g_actions[sig];
    g_actions_lock.lock();
    const GuestSigaction a = g_actions[sig];
    g_actions_lock.unlock();
    return a;
}

void reset_action(int sig) {
    if (g_actions_lock.held_by_me()) {
        g_actions[sig] = GuestSigaction{};
        return;
    }
    g_actions_lock.lock();
    g_actions[sig] = GuestSigaction{};
    g_actions_lock.unlock();
}

WINAPK_GUEST_ABI int g_sigaction(int sig, const GuestSigaction* act, GuestSigaction* old) {
    if (!valid(sig) || ((sig == kSigKill || sig == kSigStop) && act)) return *guest_errno() = kEINVAL, -1;
    // The guest's pointers are touched outside the lock: a bad one faults
    // there, where a delivery can still take the lock.
    GuestSigaction incoming{};
    if (act) incoming = *act;
    g_actions_lock.lock();
    const GuestSigaction previous = g_actions[sig];
    if (act) g_actions[sig] = incoming;
    g_actions_lock.unlock();
    if (old) *old = previous;
    return 0;
}

// Bionic's signal(): sigaction with SA_RESTART | SA_ONSTACK and an empty mask.
WINAPK_GUEST_ABI void* g_signal(int sig, void* handler) {
    if (!valid(sig) || sig == kSigKill || sig == kSigStop) {
        *guest_errno() = kEINVAL;
        return reinterpret_cast<void*>(-1);  // SIG_ERR
    }
    GuestSigaction act{};
    act.flags = static_cast<int32_t>(kSaRestart | kSaOnstack);
    act.handler = handler;
    GuestSigaction old{};
    g_sigaction(sig, &act, &old);
    return old.handler;
}

// ---- per-thread state -----------------------------------------------------------------
struct ThreadSignals {
    uint64_t mask = 0;     // blocked
    uint64_t pending = 0;  // sent to this thread while blocked
    GuestStack altstack{nullptr, kSsDisable, 0, 0};
    // The last fault delivered and how many times the handler returned
    // without moving past it (see kMaxNoProgress).
    int np_sig = 0;
    uint64_t np_pc = 0;
    uint64_t np_addr = 0;
    int np_count = 0;
    uint64_t handlers_run = 0;  // what pause and sigsuspend wait on
};
thread_local ThreadSignals t_sig{};

bool altstack_enabled(const ThreadSignals& ts) { return ts.altstack.sp && !(ts.altstack.flags & kSsDisable); }

// Bionic gives every thread an alternate signal stack at its start
// (pthread_create's __init_alternate_signal_stack: SIGNAL_STACK_SIZE_WITHOUT_GUARD,
// 16 KiB, under a guard page), so a SA_ONSTACK handler, a crash reporter's
// above all, runs even when the fault is a stack overflow. Here each thread
// that runs guest code gets one the first time it does, unless the guest set
// its own: reserved only, and committed when a handler first runs on it, so a
// thread that never takes a signal costs address space and no memory.
// Released when the thread ends. A reservation takes 64 KiB of address space
// whatever its size, and the host functions a handler calls (the CRT's
// formatting) want more stack than Bionic's, so the stack is all of it but the
// guard page, 60 KiB, and sigaltstack reports that size.
constexpr size_t kOwnAltStackGuard = 4096;
constexpr size_t kOwnAltStackSize = 64 * 1024 - kOwnAltStackGuard;
struct OwnAltStack {
    char* region = nullptr;  // guard page, then the stack
    bool tried = false;
    bool committed = false;
    ~OwnAltStack() {
        if (region) VirtualFree(region, 0, MEM_RELEASE);
    }
};
thread_local OwnAltStack t_own_alt;

// The thread's own alternate stack, committed: true if usable.
bool commit_own_altstack(const ThreadSignals& ts) {
    OwnAltStack& o = t_own_alt;
    if (!o.region || ts.altstack.sp != o.region + kOwnAltStackGuard) return true;  // the guest's own
    if (o.committed) return true;
    o.committed = VirtualAlloc(o.region + kOwnAltStackGuard, kOwnAltStackSize, MEM_COMMIT, PAGE_READWRITE) != nullptr;
    return o.committed;
}

bool on_altstack(const ThreadSignals& ts, const void* sp) {
    if (!altstack_enabled(ts)) return false;
    const auto lo = reinterpret_cast<uintptr_t>(ts.altstack.sp);
    const auto p = reinterpret_cast<uintptr_t>(sp);
    return p >= lo && p < lo + ts.altstack.size;
}

WINAPK_GUEST_ABI int g_sigaltstack(const GuestStack* ss, GuestStack* old) {
    ThreadSignals& ts = t_sig;
    const bool running_on_it = on_altstack(ts, __builtin_frame_address(0));
    if (old) {
        *old = ts.altstack;
        old->flags = altstack_enabled(ts) ? (running_on_it ? kSsOnstack : 0) : kSsDisable;
    }
    if (ss) {
        if (running_on_it) return *guest_errno() = kEPERM, -1;
        if (ss->flags & kSsDisable) {
            ts.altstack = GuestStack{nullptr, kSsDisable, 0, 0};
        } else {
            if (ss->size < kMinSigStkSz) return *guest_errno() = kENOMEM, -1;
            ts.altstack = GuestStack{ss->sp, 0, 0, ss->size};
        }
    }
    return 0;
}

int raise_self(int sig);

// Delivers what was sent to this thread while blocked and is unblocked now.
void deliver_pending() {
    ThreadSignals& ts = t_sig;
    while (uint64_t ready = ts.pending & ~ts.mask) {
        int sig = 1;
        while (!(ready & bit(sig))) ++sig;
        ts.pending &= ~bit(sig);
        raise_self(sig);
    }
}

int apply_mask(int how, const uint64_t* set, uint64_t* old) {
    ThreadSignals& ts = t_sig;
    const uint64_t previous = ts.mask;
    if (set) {
        switch (how) {
            case kSigBlock: ts.mask |= *set; break;
            case kSigUnblock: ts.mask &= ~*set; break;
            case kSigSetmask: ts.mask = *set; break;
            default: return kEINVAL;
        }
        ts.mask &= ~kUnblockable;
    }
    if (old) *old = previous;
    if (set) deliver_pending();
    return 0;
}
WINAPK_GUEST_ABI int g_sigprocmask(int how, const uint64_t* set, uint64_t* old) {
    const int err = apply_mask(how, set, old);
    if (err) return *guest_errno() = err, -1;
    return 0;
}
WINAPK_GUEST_ABI int g_pthread_sigmask(int how, const uint64_t* set, uint64_t* old) {
    return apply_mask(how, set, old);  // an errno value, not -1
}
WINAPK_GUEST_ABI int g_sigpending(uint64_t* set) {
    if (set) *set = t_sig.pending;
    return 0;
}

// ---- running a handler ----------------------------------------------------------------

// The thread's own stack bounds while a handler runs on the alternate stack
// (run_handler), so a longjmp out of the handler can put them back.
struct AltStackSwap {
    bool active = false;
    void* base = nullptr;
    void* limit = nullptr;
    uintptr_t lo = 0, hi = 0;  // the alternate stack
};
thread_local AltStackSwap t_alt_swap;

}  // namespace

// longjmp (setjmp.cpp) to `sp`: when that leaves the alternate stack a
// handler runs on, the thread's own stack bounds go back into the TEB.
extern "C" void winapk_sig_stack_fixup(uint64_t sp) {
    AltStackSwap& swap = t_alt_swap;
    if (!swap.active || (sp >= swap.lo && sp < swap.hi)) return;
    auto* tib = reinterpret_cast<NT_TIB*>(NtCurrentTeb());
    tib->StackBase = swap.base;
    tib->StackLimit = swap.limit;
    swap = AltStackSwap{};
}

namespace {

// Calls `fn(a, b, c)` with sp moved to `sp` (the top of the alternate stack) for the duration of the call.
// x29 keeps the old sp; the callee preserves it.
extern "C" __attribute__((naked)) void winapk_sig_call_on_stack(void* /*sp*/, void* /*fn*/, int64_t /*a*/,
                                                                 void* /*b*/, void* /*c*/) {
    __asm__ volatile(
        "stp x29, x30, [sp, #-16]!\n"
        "mov x29, sp\n"
        "and x9, x0, #-16\n"
        "mov sp, x9\n"
        "mov x9, x1\n"
        "mov x0, x2\n"
        "mov x1, x3\n"
        "mov x2, x4\n"
        "blr x9\n"
        "mov sp, x29\n"
        "ldp x29, x30, [sp], #16\n"
        "ret\n" ::: "memory");
}

constexpr uint32_t kFpsimdMagic = 0x46508001;

void context_to_ucontext(const CONTEXT& c, GuestUcontextA64* uc) {
    GuestSigcontextA64& m = uc->mcontext;
    for (int i = 0; i < 31; ++i) m.regs[i] = c.X[i];  // x29 is Fp, x30 Lr
    m.sp = c.Sp;
    m.pc = c.Pc;
    m.pstate = c.Cpsr;
    auto* fp = reinterpret_cast<GuestFpsimdA64*>(m.reserved);
    fp->magic = kFpsimdMagic;
    fp->size = sizeof(GuestFpsimdA64);
    fp->fpsr = c.Fpsr;
    fp->fpcr = c.Fpcr;
    for (int i = 0; i < 32; ++i) std::memcpy(fp->vregs[i], &c.V[i], 16);
    // m.reserved after the record is zero: the terminating header.
}

// What the handler may have changed: the registers, pstate and the FP/SIMD state. x18 is the TEB here
// (arm64_x18.h): the guest sees it and cannot move it.
void ucontext_to_context(const GuestUcontextA64& uc, CONTEXT* c) {
    const GuestSigcontextA64& m = uc.mcontext;
    for (int i = 0; i < 31; ++i) {
        if (i != 18) c->X[i] = m.regs[i];
    }
    c->Sp = m.sp;
    c->Pc = m.pc;
    c->Cpsr = static_cast<ULONG>(m.pstate);
    const auto* fp = reinterpret_cast<const GuestFpsimdA64*>(m.reserved);
    if (fp->magic == kFpsimdMagic && fp->size == sizeof(GuestFpsimdA64)) {
        c->Fpsr = fp->fpsr;
        c->Fpcr = fp->fpcr;
        for (int i = 0; i < 32; ++i) std::memcpy(&c->V[i], fp->vregs[i], 16);
    }
}


// Runs `act` for `sig` with Linux's handler mask; what the handler leaves in the ucontext goes back to `ctx`.
void run_handler(int sig, const GuestSigaction& act, GuestSiginfo* info, CONTEXT* ctx, uint64_t = 0, uint64_t = 0) {
    ThreadSignals& ts = t_sig;
    ++ts.handlers_run;
    alignas(16) GuestUcontextA64 uc;
    std::memset(&uc, 0, sizeof(uc));
    context_to_ucontext(*ctx, &uc);
    uc.sigmask = ts.mask;
    const bool already_on_alt = on_altstack(ts, __builtin_frame_address(0));
    uc.stack = ts.altstack;
    uc.stack.flags = altstack_enabled(ts) ? (already_on_alt ? kSsOnstack : 0) : kSsDisable;
    if (info && (sig == kSigSegv || sig == kSigBus)) uc.mcontext.fault_address = reinterpret_cast<uint64_t>(info->fault.addr);

    uint64_t during = ts.mask | act.mask;
    if (!(static_cast<uint32_t>(act.flags) & kSaNodefer)) during |= bit(sig);
    ts.mask = during & ~kUnblockable;

    const bool use_alt = (static_cast<uint32_t>(act.flags) & kSaOnstack) && altstack_enabled(ts) && !already_on_alt &&
                         commit_own_altstack(ts);
    const bool with_info = static_cast<uint32_t>(act.flags) & kSaSiginfo;
    if (use_alt) {
        void* top = static_cast<char*>(ts.altstack.sp) + ts.altstack.size;
        // The alternate stack's bounds are the thread's for the call.
        auto* tib = reinterpret_cast<NT_TIB*>(NtCurrentTeb());
        AltStackSwap& swap = t_alt_swap;
        const AltStackSwap outer = swap;
        swap = AltStackSwap{true, tib->StackBase, tib->StackLimit, reinterpret_cast<uintptr_t>(ts.altstack.sp),
                            reinterpret_cast<uintptr_t>(top)};
        tib->StackBase = top;
        tib->StackLimit = ts.altstack.sp;
        winapk_sig_call_on_stack(top, act.handler, sig, with_info ? info : nullptr, with_info ? &uc : nullptr);
        tib->StackBase = swap.base;
        tib->StackLimit = swap.limit;
        swap = outer;
    } else if (with_info) {
        reinterpret_cast<void (*)(int, GuestSiginfo*, GuestUcontextA64*)>(act.handler)(sig, info, &uc);
    } else {
        reinterpret_cast<Handler1>(act.handler)(sig);
    }

    ucontext_to_context(uc, ctx);
    // rt_sigreturn restores the frame's uc_sigmask, which the handler may have edited.
    ts.mask = uc.sigmask & ~kUnblockable;
    deliver_pending();
}

// ---- faults -------------------------------------------------------------------------

bool committed(const void* addr) {
    MEMORY_BASIC_INFORMATION mbi{};
    return VirtualQuery(addr, &mbi, sizeof(mbi)) && mbi.State == MEM_COMMIT;
}

bool guest_pc_is_brk(uint64_t pc) {
    uint32_t insn;
    std::memcpy(&insn, reinterpret_cast<const void*>(pc), sizeof insn);
    return (insn & 0xffe0001f) == 0xd4200000;
}

// The signal and si_code a Windows exception stands for; false for one that
// is no signal (C++ exceptions, debugger notifications, ...).
bool classify(const EXCEPTION_RECORD& r, uint64_t pc, int* sig, int* code, void** addr) {
    const void* at = r.NumberParameters >= 2 ? reinterpret_cast<const void*>(r.ExceptionInformation[1]) : nullptr;
    *addr = reinterpret_cast<void*>(pc);
    switch (r.ExceptionCode) {
        case EXCEPTION_ACCESS_VIOLATION:
            *sig = kSigSegv;
            *code = committed(at) ? kSegvAccerr : kSegvMaperr;
            *addr = const_cast<void*>(at);
            return true;
        case EXCEPTION_STACK_OVERFLOW:
            *sig = kSigSegv;
            *code = kSegvMaperr;
            *addr = const_cast<void*>(at);
            return true;
        case EXCEPTION_IN_PAGE_ERROR:
            *sig = kSigBus;
            *code = kBusAdrerr;
            *addr = const_cast<void*>(at);
            return true;
        case EXCEPTION_DATATYPE_MISALIGNMENT: *sig = kSigBus; *code = kBusAdraln; return true;
        case EXCEPTION_ILLEGAL_INSTRUCTION:
            // A brk other than #0xf000 (__builtin_trap is brk #1) arrives as an illegal instruction; Linux says SIGTRAP.
            if (guest_pc_is_brk(pc)) {
                *sig = kSigTrap;
                *code = kTrapBrkpt;
                return true;
            }
            *sig = kSigIll;
            *code = kIllIllopc;
            return true;
        case EXCEPTION_PRIV_INSTRUCTION: *sig = kSigIll; *code = kIllPrvopc; return true;
        case EXCEPTION_INT_DIVIDE_BY_ZERO: *sig = kSigFpe; *code = kFpeIntdiv; return true;
        case EXCEPTION_INT_OVERFLOW: *sig = kSigFpe; *code = kFpeIntovf; return true;
        case EXCEPTION_FLT_DIVIDE_BY_ZERO: *sig = kSigFpe; *code = kFpeFltdiv; return true;
        case EXCEPTION_FLT_OVERFLOW: *sig = kSigFpe; *code = kFpeFltovf; return true;
        case EXCEPTION_FLT_UNDERFLOW: *sig = kSigFpe; *code = kFpeFltund; return true;
        case EXCEPTION_FLT_INEXACT_RESULT: *sig = kSigFpe; *code = kFpeFltres; return true;
        case EXCEPTION_FLT_INVALID_OPERATION:
        case EXCEPTION_FLT_DENORMAL_OPERAND: *sig = kSigFpe; *code = kFpeFltinv; return true;
        case EXCEPTION_FLT_STACK_CHECK: *sig = kSigFpe; *code = kFpeFltsub; return true;
        case EXCEPTION_BREAKPOINT: *sig = kSigTrap; *code = kTrapBrkpt; return true;
        case EXCEPTION_SINGLE_STEP: *sig = kSigTrap; *code = kTrapTrace; return true;
        default: return false;
    }
}


bool guest_code(const void* p) {
    GuestLinker* linker = GuestLinker::process();
    return linker && linker->image_containing(p);
}

// A return address into a guest library among the words above sp: the
// guest called a host function that faulted on its behalf (memcpy on a bad
// pointer). Guest code has no unwind tables, so the stack is scanned.
bool stack_has_guest_frame(uint64_t sp, uint64_t lr) {
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<const void*>(sp), &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT) return false;
    if (lr && guest_code(reinterpret_cast<const void*>(lr))) return true;  // a leaf: its caller is in lr
    const auto* top = reinterpret_cast<const uint64_t*>(sp);
    const auto* end = reinterpret_cast<const uint64_t*>(static_cast<const char*>(mbi.BaseAddress) + mbi.RegionSize);
    for (const uint64_t* p = top; p < end && p < top + 4096; ++p) {
        if (*p && guest_code(reinterpret_cast<const void*>(*p))) return true;
    }
    return false;
}

// Whether a fault at `pc` is the guest's to handle. Not: a fault in code no
// module owns (HotSpot's JIT code, its implicit null checks and safepoint
// polls, which its own handler takes after every vectored one declined), in
// the JVM's DLLs, or in a Windows DLL with no guest frame on the stack (the
// host's own). A fault in guest code is the guest's.
bool fault_for_guest(uint64_t pc, uint64_t sp, uint64_t lr) {
    if (guest_code(reinterpret_cast<const void*>(pc))) return true;
    HMODULE mod = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(pc), &mod) ||
        !mod) {
        return false;
    }
    wchar_t path[MAX_PATH];
    const DWORD len = GetModuleFileNameW(mod, path, MAX_PATH);
    if (!len || len >= MAX_PATH) return false;
    for (DWORD i = 0; i < len; ++i) path[i] = static_cast<wchar_t>(towlower(path[i]));
    if (std::wcsstr(path, L"\\jvm.dll") || std::wcsstr(path, L"\\java.dll")) return false;
    wchar_t windir[MAX_PATH];
    const UINT wn = GetSystemWindowsDirectoryW(windir, MAX_PATH);
    if (wn && wn < MAX_PATH) {
        for (UINT i = 0; i < wn; ++i) windir[i] = static_cast<wchar_t>(towlower(windir[i]));
        if (std::wcsncmp(path, windir, wn) == 0) return stack_has_guest_frame(sp, lr);
    }
    return true;
}

// After a handler returned without moving pc: did it make the access legal
// (a mapping the handler committed or unprotected)?
bool access_now_permitted(const EXCEPTION_RECORD& r) {
    if (r.ExceptionCode != EXCEPTION_ACCESS_VIOLATION || r.NumberParameters < 2) return false;
    MEMORY_BASIC_INFORMATION mbi{};
    const auto* at = reinterpret_cast<const void*>(r.ExceptionInformation[1]);
    if (!VirtualQuery(at, &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT || (mbi.Protect & PAGE_GUARD)) return false;
    const DWORD p = mbi.Protect & 0xff;
    switch (r.ExceptionInformation[0]) {
        case 0: return p != PAGE_NOACCESS;
        case 1: return p == PAGE_READWRITE || p == PAGE_WRITECOPY || p == PAGE_EXECUTE_READWRITE || p == PAGE_EXECUTE_WRITECOPY;
        default: return p == PAGE_EXECUTE || p == PAGE_EXECUTE_READ || p == PAGE_EXECUTE_READWRITE || p == PAGE_EXECUTE_WRITECOPY;
    }
}

void note(const char* text) {
    std::fputs(text, stderr);
    std::fflush(stderr);
}

LONG CALLBACK fault_handler(EXCEPTION_POINTERS* e) {
    const EXCEPTION_RECORD& rec = *e->ExceptionRecord;
    CONTEXT* ctx = e->ContextRecord;
    const uint64_t pc = context_pc(*ctx);
    int sig = 0, code = 0;
    void* addr = nullptr;
    if (!classify(rec, pc, &sig, &code, &addr)) return EXCEPTION_CONTINUE_SEARCH;
    const GuestSigaction act = read_action(sig);
    const auto h = reinterpret_cast<uintptr_t>(act.handler);
    if (h == kSigDfl || h == kSigIgn) return EXCEPTION_CONTINUE_SEARCH;  // the default action: a crash
    const uint64_t lr = ctx->Lr;
    if (!fault_for_guest(pc, context_sp(*ctx), lr)) return EXCEPTION_CONTINUE_SEARCH;

    ThreadSignals& ts = t_sig;
    char line[256];
    if (ts.mask & bit(sig)) {
        std::snprintf(line, sizeof line, "[win.apk] %s at 0x%llx while the thread blocks it: not delivered to the guest\n",
                      signal_name(sig), static_cast<unsigned long long>(pc));
        note(line);
        return EXCEPTION_CONTINUE_SEARCH;
    }
    const bool same = ts.np_sig == sig && ts.np_pc == pc && ts.np_addr == reinterpret_cast<uint64_t>(addr);
    if (same && ts.np_count >= kMaxNoProgress) {
        std::snprintf(line, sizeof line,
                      "[win.apk] the guest's %s handler returned %d times without moving past pc 0x%llx (address 0x%llx):"
                      " treating the fault as unhandled\n",
                      signal_name(sig), ts.np_count, static_cast<unsigned long long>(pc),
                      static_cast<unsigned long long>(reinterpret_cast<uint64_t>(addr)));
        note(line);
        // The count stays: the crash path never resumes the thread, and a
        // second dispatch of the same fault (a debugger's second chance) must
        // not start the handler over.
        return EXCEPTION_CONTINUE_SEARCH;
    }
    if (!same) {
        ts.np_sig = sig;
        ts.np_pc = pc;
        ts.np_addr = reinterpret_cast<uint64_t>(addr);
        ts.np_count = 0;
    }
    if (static_cast<uint32_t>(act.flags) & kSaResethand) reset_action(sig);

    alignas(16) GuestSiginfo info;
    std::memset(&info, 0, sizeof(info));
    info.signo = sig;
    info.code = code;
    info.fault.addr = addr;
    uint64_t trapno = 0, err = 0;
    run_handler(sig, act, &info, ctx, trapno, err);

    if (context_pc(*ctx) != pc) {
        ts.np_count = 0;  // moved past it
    } else if (access_now_permitted(rec)) {
        ts.np_count = 0;  // fixed the cause; the retry succeeds
    } else {
        ++ts.np_count;    // the same fault is coming right back
    }
    return EXCEPTION_CONTINUE_EXECUTION;
}

// ---- self-sent signals ------------------------------------------------------------------

// What Linux does with a signal nobody handles: nothing for the ones whose
// default is to ignore (or to stop the process, which a windowed app has no
// use for), death with the exit code the shell would show for the rest. The
// core-dumping set goes through abort(), so the runtime's crash report and
// minidump cover it as they cover a fault.
void default_action(int sig) {
    switch (sig) {
        case kSigChld:
        case kSigCont:
        case kSigUrg:
        case kSigWinch:
        case kSigStop:
        case kSigTstp:
        case kSigTtin:
        case kSigTtou:
            return;
        default:
            break;
    }
    char line[160];
    std::snprintf(line, sizeof line, "[win.apk] guest sent itself %s (%d) with no handler: the default action ends the app\n",
                  signal_name(sig), sig);
    note(line);
    switch (sig) {
        case kSigQuit:
        case kSigIll:
        case kSigTrap:
        case kSigAbrt:
        case kSigBus:
        case kSigFpe:
        case kSigSegv:
        case kSigXcpu:
        case kSigXfsz:
        case kSigSys:
            std::abort();
        default:
            TerminateProcess(GetCurrentProcess(), 128 + sig);
    }
}

// A signal sent to the calling thread: delivered now, as a call, unless
// blocked (then it waits in the pending set). Returns 0.
int raise_self(int sig) {
    ThreadSignals& ts = t_sig;
    if (ts.mask & bit(sig)) {
        ts.pending |= bit(sig);
        return 0;
    }
    const GuestSigaction act = read_action(sig);
    const auto h = reinterpret_cast<uintptr_t>(act.handler);
    if (h == kSigIgn) return 0;
    if (h == kSigDfl) {
        default_action(sig);
        return 0;
    }
    if (static_cast<uint32_t>(act.flags) & kSaResethand) reset_action(sig);
    alignas(16) CONTEXT ctx;
    std::memset(&ctx, 0, sizeof(ctx));
    RtlCaptureContext(&ctx);
    alignas(16) GuestSiginfo info;
    std::memset(&info, 0, sizeof(info));
    info.signo = sig;
    info.code = kSiTkill;  // raise() is tgkill() in Bionic
    info.kill.pid = static_cast<int32_t>(GetCurrentProcessId());
    info.kill.uid = kAppUid;
    run_handler(sig, act, &info, &ctx);
    return 0;
}

// The injected call's entry (the target's new Pc). A thread suspended in a system call comes back with the
// call's result in x0, so the arguments come on the stack and the live x0 replaces the parked one.
extern "C" [[noreturn]] void winapk_sig_async_entry(int sig, CONTEXT* saved);
extern "C" __attribute__((naked)) void winapk_sig_async_stub() {
    __asm__ volatile(
        "ldr x9, [sp, #8]\n"
        "str x0, [x9, %0]\n"
        "ldr w0, [sp]\n"
        "mov x1, x9\n"
        "b winapk_sig_async_entry\n" ::"i"(offsetof(CONTEXT, X0)));
}

extern "C" void winapk_sig_async_entry(int sig, CONTEXT* saved) {
    ThreadSignals& ts = t_sig;
    if (ts.mask & bit(sig)) {
        ts.pending |= bit(sig);
    } else {
        const GuestSigaction act = read_action(sig);
        const auto h = reinterpret_cast<uintptr_t>(act.handler);
        if (h == kSigDfl) {
            default_action(sig);
        } else if (h != kSigIgn) {
            if (static_cast<uint32_t>(act.flags) & kSaResethand) reset_action(sig);
            alignas(16) GuestSiginfo info;
            std::memset(&info, 0, sizeof(info));
            info.signo = sig;
            info.code = kSiTkill;
            info.kill.pid = static_cast<int32_t>(GetCurrentProcessId());
            info.kill.uid = kAppUid;
            run_handler(sig, act, &info, saved);
        }
    }
    RtlRestoreContext(saved, nullptr);
    __builtin_unreachable();
}

// The target's registers are parked below its stack pointer and it resumes in the stub.
// AAPCS64 has no red zone, so the parked copy sits right under sp.
int inject(uint32_t tid, int sig) {
    HANDLE h = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_SET_CONTEXT | THREAD_QUERY_INFORMATION,
                          FALSE, tid);
    if (!h) return kESRCH;
    // A tid is only a number: Windows reuses it, and a caller can name any. Checked on the open handle, so
    // the thread cannot change owner between the check and the suspend; another process's thread is not ours
    // to redirect.
    if (GetProcessIdOfThread(h) != GetCurrentProcessId() || SuspendThread(h) == static_cast<DWORD>(-1)) {
        CloseHandle(h);
        return kESRCH;
    }
    int result = 0;
    alignas(16) CONTEXT ctx;
    std::memset(&ctx, 0, sizeof(ctx));
    ctx.ContextFlags = CONTEXT_FULL;
    if (!GetThreadContext(h, &ctx)) {
        result = kEAGAIN;
    } else {
        const uint64_t slot = (ctx.Sp - sizeof(CONTEXT)) & ~uint64_t{15};
        const uint64_t new_sp = slot - 16;  // two words: the signal, and the parked CONTEXT's address
        MEMORY_BASIC_INFORMATION mbi{};
        if (!VirtualQuery(reinterpret_cast<const void*>(new_sp), &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT ||
            (mbi.Protect & PAGE_GUARD) ||
            reinterpret_cast<uint64_t>(mbi.BaseAddress) + mbi.RegionSize < ctx.Sp) {
            result = kEAGAIN;  // the target's stack is not where a call can be parked
        } else {
            CONTEXT resumed = ctx;
            std::memcpy(reinterpret_cast<void*>(slot), &ctx, sizeof(CONTEXT));
            auto* frame = reinterpret_cast<uint64_t*>(new_sp);
            frame[0] = static_cast<uint64_t>(sig);
            frame[1] = slot;
            resumed.Sp = new_sp;
            resumed.Pc = reinterpret_cast<uint64_t>(&winapk_sig_async_stub);
            resumed.ContextFlags = CONTEXT_CONTROL | CONTEXT_INTEGER;
            if (!SetThreadContext(h, &resumed)) result = kEAGAIN;
        }
    }
    ResumeThread(h);
    CloseHandle(h);
    return result;
}

WINAPK_GUEST_ABI int g_raise(int sig) {
    const int err = signal_send(0, sig);
    if (err) return *guest_errno() = err, -1;
    return 0;
}

// ---- waiting for a signal ---------------------------------------------------------------
// sigwait, sigwaitinfo, sigtimedwait, pause and sigsuspend. Missing, each answered 0 at once through
// the loader's fallback, and a thread whose whole loop is one of them (a signal thread, `for (;;)
// pause();`) took a processor for good. A signal reaches a thread here only as a call on that thread
// (raise_self, the injected entry), so the wait is a sleep in short slices that looks at what those
// calls left: the pending set, the count of handlers run. Ten milliseconds is well under what a
// signal-driven shutdown or reload notices.
// TODO(process-directed signals): kill(getpid(), sig) lands on its caller (misc.cpp), not on a thread
// that waits for `sig` in sigwait, so that thread sleeps on; Linux would pick it.
constexpr DWORD kSignalWaitSliceMs = 10;

// Takes the lowest pending signal of `set` (0 when `timeout_ms`, < 0 for none, runs out first).
int take_pending(uint64_t set, int64_t timeout_ms) {
    ThreadSignals& ts = t_sig;
    const ULONGLONG start = GetTickCount64();
    for (;;) {
        if (const uint64_t ready = ts.pending & set) {
            int sig = 1;
            while (!(ready & bit(sig))) ++sig;
            ts.pending &= ~bit(sig);
            return sig;
        }
        if (timeout_ms >= 0 && GetTickCount64() - start >= static_cast<ULONGLONG>(timeout_ms)) return 0;
        Sleep(kSignalWaitSliceMs);
    }
}
void fill_waited(GuestSiginfo* info, int sig) {
    if (!info) return;
    std::memset(info, 0, sizeof(*info));
    info->signo = sig;
    info->code = kSiTkill;
    info->kill.pid = static_cast<int32_t>(GetCurrentProcessId());
    info->kill.uid = kAppUid;
}
WINAPK_GUEST_ABI int g_sigwait(const uint64_t* set, int* sig) {
    if (!set || !sig) return kEINVAL;  // an errno value, not -1
    *sig = take_pending(*set, -1);
    return 0;
}
WINAPK_GUEST_ABI int g_sigwaitinfo(const uint64_t* set, GuestSiginfo* info) {
    if (!set) return *guest_errno() = kEINVAL, -1;
    const int sig = take_pending(*set, -1);
    fill_waited(info, sig);
    return sig;
}
struct GuestTimespec {
    int64_t tv_sec, tv_nsec;
};
WINAPK_GUEST_ABI int g_sigtimedwait(const uint64_t* set, GuestSiginfo* info, const GuestTimespec* timeout) {
    if (!set || (timeout && (timeout->tv_sec < 0 || timeout->tv_nsec < 0 || timeout->tv_nsec >= 1000000000))) {
        return *guest_errno() = kEINVAL, -1;
    }
    const int sig = take_pending(*set, timeout ? timeout->tv_sec * 1000 + timeout->tv_nsec / 1000000 : -1);
    if (!sig) return *guest_errno() = kEAGAIN, -1;
    fill_waited(info, sig);
    return sig;
}
// pause and sigsuspend return once a handler has run on the thread: -1 with EINTR, always.
int wait_for_handler() {
    const uint64_t before = t_sig.handlers_run;
    while (t_sig.handlers_run == before) Sleep(kSignalWaitSliceMs);
    return *guest_errno() = kEINTR, -1;
}
WINAPK_GUEST_ABI int g_pause() { return wait_for_handler(); }
WINAPK_GUEST_ABI int g_sigsuspend(const uint64_t* mask) {
    if (!mask) return *guest_errno() = kEFAULT, -1;
    ThreadSignals& ts = t_sig;
    const uint64_t saved = ts.mask;
    const uint64_t before = ts.handlers_run;
    ts.mask = *mask & ~kUnblockable;
    deliver_pending();  // what the temporary mask lets through
    if (ts.handlers_run == before) wait_for_handler();
    ts.mask = saved;
    deliver_pending();
    return *guest_errno() = kEINTR, -1;
}

// ---- alarm, setitimer -------------------------------------------------------------------
// Accepted, and SIGALRM never comes: said once. alarm cannot fail (it answers the seconds left of
// the alarm before, 0), and a timer that is only cancelled has nothing to say.
// TODO(interval timers): a timer-queue timer that sends the signal to the thread that armed it
// (signal_send), for alarm, setitimer and the POSIX timers below.
void note_no_alarm(const char* call) {
    static std::atomic<bool> said{false};
    say_once(said, call, "the app set an alarm timer; its signal (SIGALRM) is not delivered yet");
}
WINAPK_GUEST_ABI unsigned g_alarm(unsigned seconds) {
    if (seconds) note_no_alarm("alarm()");
    return 0;
}
WINAPK_GUEST_ABI int g_setitimer(int, const int64_t* value, void* old) {
    if (value && (value[2] || value[3])) note_no_alarm("setitimer()");  // it_value: the third and fourth words
    if (old) std::memset(old, 0, 32);  // struct itimerval: two timevals
    return 0;
}
WINAPK_GUEST_ABI int g_getitimer(int, void* value) {
    if (value) std::memset(value, 0, 32);
    return 0;
}

// ---- POSIX timers ---------------------------------------------------------------------
// Created and armed, but they do not fire (that would need a delivered
// signal or SIGEV_THREAD callback); said once in the log.
// TODO(posix timers): see the interval timers above; SIGEV_THREAD is a call on a pool thread.
std::atomic<uintptr_t> g_next_timer{1};
std::once_flag g_timer_note;

WINAPK_GUEST_ABI int g_timer_create(int, const void*, void** id) {
    std::call_once(g_timer_note, [] {
        std::fprintf(stderr, "[win.apk] guest created a POSIX timer; timers do not fire yet\n");
        std::fflush(stderr);
    });
    if (!id) return *guest_errno() = kEINVAL, -1;
    *id = reinterpret_cast<void*>(g_next_timer.fetch_add(1));
    return 0;
}
WINAPK_GUEST_ABI int g_timer_settime(void*, int, const void*, void* old) {
    if (old) std::memset(old, 0, 32);  // struct itimerspec: two timespecs
    return 0;
}
WINAPK_GUEST_ABI int g_timer_gettime(void*, void* value) {
    if (value) std::memset(value, 0, 32);
    return 0;
}
WINAPK_GUEST_ABI int g_timer_delete(void*) { return 0; }
WINAPK_GUEST_ABI int g_timer_getoverrun(void*) { return 0; }

// ---- scheduling priority ----------------------------------------------------------------
WINAPK_GUEST_ABI int g_setpriority(int, int, int) { return 0; }
WINAPK_GUEST_ABI int g_getpriority(int, int) { return 0; }

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("sigemptyset", g_sigemptyset),
    WINAPK_SHIM("sigemptyset64", g_sigemptyset),
    WINAPK_SHIM("sigfillset", g_sigfillset),
    WINAPK_SHIM("sigfillset64", g_sigfillset),
    WINAPK_SHIM("sigaddset", g_sigaddset),
    WINAPK_SHIM("sigaddset64", g_sigaddset),
    WINAPK_SHIM("sigdelset", g_sigdelset),
    WINAPK_SHIM("sigdelset64", g_sigdelset),
    WINAPK_SHIM("sigismember", g_sigismember),
    WINAPK_SHIM("sigismember64", g_sigismember),
    WINAPK_SHIM("sigaction", g_sigaction),
    WINAPK_SHIM("sigaction64", g_sigaction),
    WINAPK_SHIM("signal", g_signal),
    WINAPK_SHIM("bsd_signal", g_signal),
    WINAPK_SHIM("sigaltstack", g_sigaltstack),
    WINAPK_SHIM("sigprocmask", g_sigprocmask),
    WINAPK_SHIM("sigprocmask64", g_sigprocmask),
    WINAPK_SHIM("pthread_sigmask", g_pthread_sigmask),
    WINAPK_SHIM("pthread_sigmask64", g_pthread_sigmask),
    WINAPK_SHIM("sigpending", g_sigpending),
    WINAPK_SHIM("sigpending64", g_sigpending),
    WINAPK_SHIM("raise", g_raise),
    WINAPK_SHIM("sigwait", g_sigwait),
    WINAPK_SHIM("sigwait64", g_sigwait),
    WINAPK_SHIM("sigwaitinfo", g_sigwaitinfo),
    WINAPK_SHIM("sigwaitinfo64", g_sigwaitinfo),
    WINAPK_SHIM("sigtimedwait", g_sigtimedwait),
    WINAPK_SHIM("sigtimedwait64", g_sigtimedwait),
    WINAPK_SHIM("pause", g_pause),
    WINAPK_SHIM("sigsuspend", g_sigsuspend),
    WINAPK_SHIM("sigsuspend64", g_sigsuspend),
    WINAPK_SHIM("alarm", g_alarm),
    WINAPK_SHIM("setitimer", g_setitimer),
    WINAPK_SHIM("getitimer", g_getitimer),
    WINAPK_SHIM("timer_create", g_timer_create),
    WINAPK_SHIM("timer_settime", g_timer_settime),
    WINAPK_SHIM("timer_gettime", g_timer_gettime),
    WINAPK_SHIM("timer_delete", g_timer_delete),
    WINAPK_SHIM("timer_getoverrun", g_timer_getoverrun),
    WINAPK_SHIM("setpriority", g_setpriority),
    WINAPK_SHIM("getpriority", g_getpriority),
};

}  // namespace

ShimTable signal_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

int signal_send(uint32_t tid, int sig) {
    if (sig == 0) {  // kill(pid, 0) / pthread_kill(t, 0): does the target exist?
        if (tid == 0 || tid == GetCurrentThreadId()) return 0;
        HANDLE h = OpenThread(THREAD_QUERY_INFORMATION, FALSE, tid);
        if (!h) return kESRCH;
        const bool own = GetProcessIdOfThread(h) == GetCurrentProcessId();
        CloseHandle(h);
        return own ? 0 : kESRCH;
    }
    if (!valid(sig)) return kEINVAL;
    if (sig == kSigKill) {
        note("[win.apk] guest sent itself SIGKILL\n");
        TerminateProcess(GetCurrentProcess(), 128 + sig);
    }
    if (sig == kSigStop) return 0;  // nothing to stop a windowed app for
    if (tid == 0 || tid == GetCurrentThreadId()) return raise_self(sig);
    return inject(tid, sig);
}

void signal_altstack_for_current_thread() {
    OwnAltStack& o = t_own_alt;
    if (o.tried) return;
    o.tried = true;
    ThreadSignals& ts = t_sig;
    if (ts.altstack.sp) return;  // the guest's own
    void* r = VirtualAlloc(nullptr, kOwnAltStackGuard + kOwnAltStackSize, MEM_RESERVE, PAGE_NOACCESS);
    if (!r) return;
    o.region = static_cast<char*>(r);
    ts.altstack = GuestStack{o.region + kOwnAltStackGuard, 0, 0, kOwnAltStackSize};
}
uint64_t signal_mask_of_current_thread() { return t_sig.mask; }
void signal_set_mask_of_current_thread(uint64_t mask) {
    t_sig.mask = mask & ~kUnblockable;
    deliver_pending();
}

}  // namespace winapk::bionic

namespace winapk {

void guest_signals_install() {
    static std::once_flag once;
    std::call_once(once, [] { AddVectoredExceptionHandler(0, bionic::fault_handler); });
}
void guest_signal_altstack_for_current_thread() { bionic::signal_altstack_for_current_thread(); }

}  // namespace winapk
