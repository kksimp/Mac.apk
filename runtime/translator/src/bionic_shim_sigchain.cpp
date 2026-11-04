// bionic_shim_sigchain.cpp -- libsigchain semantics for Stage-6 (arm64) guests.
//
// WHAT ANDROID DOES. An app process on Android links ART's libsigchain
// (art/sigchainlib/sigchain.cc), which interposes sigaction(), signal() and
// sigprocmask(). ART CLAIMS the signals its fault manager needs (SIGSEGV, and
// SIGBUS when it needs it; runtime/fault_handler.cc AddSpecialSignalHandlerFn).
// For a claimed signal:
//   * sigaction/signal "record the user's action but don't pass it on to the
//     kernel", and hand back the previously recorded action as old_action
//     (sigchain.cc __sigaction: `*old_action = saved_action; return 0;`);
//   * SignalChain::Handler runs "the special handlers first" (ART's own
//     implicit null checks, stack overflow checks...), then, if none claimed
//     the fault, "Forward[s] to the user's signal handler" with the mask
//     uc_sigmask | sa_mask, plus the signal itself unless SA_NODEFER;
//   * a user action of SIG_DFL makes it log "reverting to SIG_DFL handler",
//     remove itself and return, so the fault happens again and the process dies;
//   * sigprocmask removes claimed signals from any SIG_BLOCK/SIG_SETMASK set:
//     "If a signal chain has been claimed we can't allow the user to block that
//     signal."
// Net effect on a device: an app's crash reporter (Crashlytics, Breakpad,
// Unity's, AppLovin's, King's) DOES run on every genuine crash; it only never
// sees ART's own recoverable faults. Quotes are from the LineageOS android_art
// lineage-22.2 mirror of sigchain.cc, fetched 2026-09-27.
//
// WHAT WE DID BEFORE (v1.0.173 / v1.0.348, still the MACAPK_SIGCHAIN=0 path).
// __macapk_arm64_sigaction REFUSED every guest install for the runtime-owned
// fatal set, zeroed oldact and returned 0: the reporter believed it was armed
// and never ran. Two measured failures motivated that, and both are handled
// here structurally instead:
//   * Crossy (v1.0.173): Unity's reporter received a DARWIN siginfo/ucontext,
//     read it with Linux offsets (an all-0x3ff register dump) and aborted. Here
//     the guest gets a synthesized LINUX siginfo_t and BIONIC ucontext_t.
//   * Candy Crush (v1.0.348): King's handler, installed at kernel level, ran
//     ahead of HotSpot and intercepted HotSpot's own implicit faults. Here the
//     guest action is never installed on the host, and HotSpot is asked first
//     through JVM_handle_bsd_signal(..., abort_if_unrecognized=0), exported by
//     libjvm exactly for this ("This routine may be used by user applications
//     as a hook to catch signals ... If this routine returns false, it is OK to
//     call it again", jdk21u src/hotspot/os/posix/signals_posix.cpp). That is
//     the slot ART's special handlers occupy on a device.
//
// ORDER IN EVERY FRONT HANDLER (bionic_shim.cpp) -- unchanged up to the point
// where it used to hand the fault to HotSpot's saved handler:
//   1. kernel-equivalent emulators (the MRS SIGILL emulator, the svc->BRK
//      SIGTRAP emulator),
//   2. the runtime's own recoveries (null-deref recovery, guard pages, ...),
//   3. __macapk_sigchain_deliver(): HotSpot pre-check, then the guest action,
//   4. whatever ran before (HotSpot's handler -> hs_err), unchanged.
//
// SIGNAL NUMBERS. The guest speaks LINUX numbers; this TU compiles against
// Darwin's. The chained set is stored in Linux numbering, which also closes the
// BUS half of the RECONCILE block at __macapk_arm64_sigaction: a guest SIGBUS
// handler (Linux 7) used to be forwarded to Darwin slot 7 (SIGEMT) and never
// fire; it is now recorded here and delivered for a real host SIGBUS (10).
// Linux 10 (SIGUSR1) is NOT claimed, and bionic_shim.cpp keeps refusing it,
// because passing it through would install it on Darwin slot 10, which is
// SIGBUS, our own front handler. Everything outside the chained set keeps the
// old raw, self-consistent pass-through (docs/arm64-signal-shim-gaps_2026-08-31.md).
// And the signal a guest is DELIVERED is re-derived from the fault, because XNU
// numbers faults differently from Linux: a read of a PROT_NONE page or a write
// to a read-only one is SIGBUS on macOS and SIGSEGV/SEGV_ACCERR on Android (see
// classify_memory_fault, measured with the gate's PROT_NONE and past-EOF rows).
//
// ARM32. bionic_shim_signal.cpp keeps its own record-only registry; real
// delivery into arm32rc code needs safe-point signal delivery, a separate lane.
//
// GATES. MACAPK_SIGCHAIN (default ON; =0 restores the refusal path exactly),
// MACAPK_KEEP_APP_CRASHHANDLER / MACAPK_UNITY_KEEP_CRASHHANDLER (the old
// "install the guest handler raw" debug escape, still honoured and still
// taking precedence), MACAPK_INLINE_SIGSYSCALLS_REAL (default "virt": real
// except sigaltstack, which is recorded but never installed; =1 real, =0 the
// old fake-0; see __macapk_sigchain_inline_syscall). Full write-up:
// docs/sigchain-libsigchain-parity_2026-09-27.md.
//
// RESIDUALS (see the doc's list). (1) A reporter now RUNS on a genuine,
// unrecovered fault before hs_err, as on a device; one that blocks there (the
// Candy Crush __macapk_arm64_fprintf stdio-lock class) turns that crash into a
// HANG, and the loop guard cannot see a hang. (2) The first touch of this TU's
// thread_locals on a thread (t_dlv, t_np_*) goes through dyld's TLV bootstrap,
// which calls malloc; on a thread that never took a delivery before, a signal
// that interrupted malloc and reaches a recorded guest action could deadlock
// there. Both apply only when a guest action is recorded for that signal.
//
// C++17. No em dashes (project convention).

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <dlfcn.h>
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <pthread.h>
#include <signal.h>
#include <sys/ucontext.h>
#include <unistd.h>

// ---- entry points implemented in bionic_shim.cpp ---------------------------
// Declared with this TU's own (layout-identical) types; C linkage binds by
// name. Each is the ordinary libc-facing shim, so a raw inline syscall routed
// here gets exactly the translation a libc call gets.
extern "C" int __macapk_arm64_sigaltstack(const void* ss_in, void* oss_out);
extern "C" int __macapk_arm64_tgkill(int tgid, int tid, int sig);
extern "C" int __macapk_arm64_sigprocmask(int how, const uint64_t* set, uint64_t* oldset);
extern "C" int __macapk_arm64_sigsuspend(const uint64_t* mask);
extern "C" int __macapk_arm64_sigaction(int sig, const void* act, void* oldact);
extern "C" int __macapk_errno_mac2lin(int e);

namespace {

// ---- Bionic / Linux ABI constants (every one cited) -------------------------
// Signal numbers, include/uapi/asm-generic/signal.h (arm64 uses the generic
// table): SIGILL 4, SIGTRAP 5, SIGABRT 6, SIGBUS 7, SIGFPE 8, SIGSEGV 11.
constexpr int kLinuxSIGILL  = 4;
constexpr int kLinuxSIGTRAP = 5;
constexpr int kLinuxSIGABRT = 6;
constexpr int kLinuxSIGBUS  = 7;
constexpr int kLinuxSIGFPE  = 8;
constexpr int kLinuxSIGUSR1 = 10;   // collides with Darwin SIGBUS (10): never pass through
constexpr int kLinuxSIGSEGV = 11;
constexpr int kLinuxSIGUSR2 = 12;   // collides with Darwin SIGSYS (12): never pass through
constexpr int kLinuxNSIG    = 65;   // _NSIG on arm64: 64 signals, index 0 unused

// sa_flags, include/uapi/asm-generic/signal-defs.h + arch/arm64 SA_RESTORER.
constexpr uint32_t kLinuxSA_NOCLDSTOP = 0x00000001u;
constexpr uint32_t kLinuxSA_NOCLDWAIT = 0x00000002u;
constexpr uint32_t kLinuxSA_SIGINFO   = 0x00000004u;
constexpr uint32_t kLinuxSA_RESTORER  = 0x04000000u;
constexpr uint32_t kLinuxSA_ONSTACK   = 0x08000000u;
constexpr uint32_t kLinuxSA_RESTART   = 0x10000000u;
constexpr uint32_t kLinuxSA_NODEFER   = 0x40000000u;
constexpr uint32_t kLinuxSA_RESETHAND = 0x80000000u;
// libsigchain Register(): kernel_supported_flags_ = NOCLDSTOP | NOCLDWAIT |
// SIGINFO | ONSTACK | RESTART | NODEFER | RESETHAND (| RESTORER on Bionic), and
// SetAction() masks every recorded action with it. SA_EXPOSE_TAGBITS is added
// only when the kernel proves it supports it; we claim no MTE, so it is not.
constexpr uint32_t kSigchainSupportedFlags =
    kLinuxSA_NOCLDSTOP | kLinuxSA_NOCLDWAIT | kLinuxSA_SIGINFO | kLinuxSA_ONSTACK |
    kLinuxSA_RESTART | kLinuxSA_NODEFER | kLinuxSA_RESETHAND | kLinuxSA_RESTORER;

// si_code values, include/uapi/asm-generic/siginfo.h.
constexpr int kLinuxSI_USER    = 0;
constexpr int kLinuxSI_QUEUE   = -1;
constexpr int kLinuxSI_TIMER   = -2;
constexpr int kLinuxSI_MESGQ   = -3;
constexpr int kLinuxSI_ASYNCIO = -4;

// stack_t flags, include/uapi/linux/signal.h: SS_ONSTACK 1, SS_DISABLE 2.
constexpr int32_t kLinuxSS_ONSTACK = 1;
constexpr int32_t kLinuxSS_DISABLE = 2;

// ---- Bionic LP64 struct sigaction (bionic/libc/include/bits/signal_types.h)
//   #define __SIGACTION_BODY int sa_flags; union { sighandler_t sa_handler;
//       void (*sa_sigaction)(int, struct siginfo*, void*); }; sigset_t sa_mask;
//       void (*sa_restorer)(void);
// sigset_t is `unsigned long` (8 bytes) on LP64 Bionic.
struct BionicSigactionSC {
    int32_t  sa_flags;
    int32_t  pad;
    void*    handler;
    uint64_t mask;
    void*    restorer;
};
static_assert(sizeof(BionicSigactionSC) == 32, "Bionic LP64 struct sigaction is 32 bytes");
static_assert(offsetof(BionicSigactionSC, handler) == 8, "sa_handler at +8");
static_assert(offsetof(BionicSigactionSC, mask) == 16, "sa_mask at +16");
static_assert(offsetof(BionicSigactionSC, restorer) == 24, "sa_restorer at +24");

// ---- The KERNEL's struct sigaction for rt_sigaction (nr 134), which is NOT
// the libc one (include/uapi/asm-generic/signal.h, arm64 defines SA_RESTORER):
//   struct sigaction { __sighandler_t sa_handler; unsigned long sa_flags;
//                      __sigrestore_t sa_restorer; sigset_t sa_mask; };
struct KernelSigaction {
    void*    handler;
    uint64_t flags;
    void*    restorer;
    uint64_t mask;
};
static_assert(sizeof(KernelSigaction) == 32, "kernel arm64 struct sigaction is 32 bytes");

// ---- Linux siginfo_t (include/uapi/asm-generic/siginfo.h): si_signo +0,
// si_errno +4, si_code +8, then union __sifields, which holds pointers and so
// is 8-aligned: +16 on LP64. _sigfault._addr is at +16; _kill._pid +16,
// _kill._uid +20; _rt._sigval +24. SI_MAX_SIZE 128.
constexpr size_t kLinuxSiginfoSize = 128;
constexpr size_t kLinuxSiSignoOff  = 0;
constexpr size_t kLinuxSiErrnoOff  = 4;
constexpr size_t kLinuxSiCodeOff   = 8;
constexpr size_t kLinuxSiAddrOff   = 16;
constexpr size_t kLinuxSiPidOff    = 16;
constexpr size_t kLinuxSiUidOff    = 20;
constexpr size_t kLinuxSiValueOff  = 24;

// ---- Bionic arm64 ucontext_t (bionic/libc/include/sys/ucontext.h):
//   unsigned long uc_flags; struct ucontext* uc_link; stack_t uc_stack;
//   union { sigset_t uc_sigmask; sigset64_t uc_sigmask64; };
//   char __padding[128 - sizeof(sigset_t)]; mcontext_t uc_mcontext;
// mcontext_t is the kernel's struct sigcontext (arch/arm64/include/uapi/asm/
// sigcontext.h): __u64 fault_address; __u64 regs[31]; __u64 sp, pc, pstate;
// __u8 __reserved[4096] __attribute__((__aligned__(16))). The 16-byte alignment
// of __reserved makes the whole sigcontext 16-aligned, so uc_mcontext moves from
// 40 + 128 = 168 up to 176. __reserved holds fpsimd_context first
// (FPSIMD_MAGIC 0x46508001, size 0x210) and, for faults, esr_context
// (ESR_MAGIC 0x45535201, size 0x10), then a null terminator.
struct LinuxStackT { uint64_t ss_sp; int32_t ss_flags; int32_t pad; uint64_t ss_size; };
static_assert(sizeof(LinuxStackT) == 24, "Linux LP64 stack_t is 24 bytes");
struct alignas(16) LinuxSigcontext {
    uint64_t fault_address;
    uint64_t regs[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
    alignas(16) uint8_t reserved[4096];
};
struct alignas(16) BionicUcontext {
    uint64_t        uc_flags;
    uint64_t        uc_link;
    LinuxStackT     uc_stack;
    uint64_t        uc_sigmask;
    uint8_t         padding[128 - 8];
    LinuxSigcontext uc_mcontext;
};
static_assert(offsetof(BionicUcontext, uc_stack) == 16, "uc_stack at +16");
static_assert(offsetof(BionicUcontext, uc_sigmask) == 40, "uc_sigmask at +40");
static_assert(offsetof(BionicUcontext, uc_mcontext) == 176, "uc_mcontext at +176");
static_assert(offsetof(LinuxSigcontext, regs) == 8, "regs at mcontext+8");
static_assert(offsetof(LinuxSigcontext, sp) == 256, "sp at mcontext+256");
static_assert(offsetof(LinuxSigcontext, pc) == 264, "pc at mcontext+264");
static_assert(offsetof(LinuxSigcontext, pstate) == 272, "pstate at mcontext+272");
static_assert(offsetof(LinuxSigcontext, reserved) == 288, "__reserved at mcontext+288");
static_assert(sizeof(BionicUcontext) == 4560, "Bionic arm64 ucontext_t is 4560 bytes");
constexpr uint32_t kFpsimdMagic = 0x46508001u;
constexpr uint32_t kFpsimdSize  = 0x210u;   // 8 head + fpsr + fpcr + 32 x 16
constexpr uint32_t kEsrMagic    = 0x45535201u;
constexpr uint32_t kEsrSize     = 0x10u;

// OR-in TPIDR_EL0 bit 48 (MACHDEP_TPIDR_FLAG_PRESERVE_X18) so XNU restores x18
// across every EL0 exception return on this thread. The same two instructions
// as macapk_enable_x18_preservation (bionic_shim.cpp / arm64_native.cpp; tiny
// and duplicated per TU on purpose). A guest handler runs on whichever thread
// took the signal, which may never have run guest code before (HotSpot's VM
// thread in os::abort, the Cocoa main thread), and Bionic code uses x18 as its
// TLS/shadow-call-stack register: without the bit, the first syscall inside the
// handler returns with x18 zeroed. mrs/msr only: async-signal-safe.
inline void enable_x18_preservation() {
#if defined(__APPLE__) && defined(__aarch64__)
    uint64_t t;
    __asm__ volatile("mrs %0, TPIDR_EL0" : "=r"(t));
    if (!(t & 0x0001000000000000ULL)) {
        t |= 0x0001000000000000ULL;
        __asm__ volatile("msr TPIDR_EL0, %0" :: "r"(t));
    }
#endif
}

// ---- gates -------------------------------------------------------------------
bool env_off(const char* name) {
    const char* e = std::getenv(name);
    return e && e[0] == '0';
}
bool sigchain_on() {
    static const bool on = !env_off("MACAPK_SIGCHAIN");
    return on;
}
bool keep_app_handler() {
    // Same escape bionic_shim.cpp's macapk_arm64_signal_keep_app_handler reads:
    // the guest handler is installed RAW on the host (debugging only).
    static const bool keep = std::getenv("MACAPK_KEEP_APP_CRASHHANDLER")
                          || std::getenv("MACAPK_UNITY_KEEP_CRASHHANDLER");
    return keep;
}
bool active() { return sigchain_on() && !keep_app_handler(); }

bool chained_linux(int s) {
    return s == kLinuxSIGILL || s == kLinuxSIGTRAP || s == kLinuxSIGABRT
        || s == kLinuxSIGBUS || s == kLinuxSIGFPE || s == kLinuxSIGSEGV;
}
// Host (Darwin) signal -> the Linux number the guest registered it under.
int host_to_linux(int host_sig) {
    switch (host_sig) {
        case SIGILL:  return kLinuxSIGILL;
        case SIGTRAP: return kLinuxSIGTRAP;
        case SIGABRT: return kLinuxSIGABRT;
        case SIGBUS:  return kLinuxSIGBUS;    // Darwin 10 -> Linux 7
        case SIGFPE:  return kLinuxSIGFPE;
        case SIGSEGV: return kLinuxSIGSEGV;
        default:      return 0;
    }
}

// ---- the registry ------------------------------------------------------------
// libsigchain keeps one struct sigaction per signal and reads it from the
// handler with no lock at all. We add a sequence counter so a handler never
// reads a half-written action, and a bounded writer spin (not a mutex) so a
// guest handler that calls sigaction() while this thread was mid-write cannot
// deadlock: after the bound it proceeds unlocked, as libsigchain always does.
struct ChainSlot {
    BionicSigactionSC act;
    bool              set;
};
ChainSlot             g_slot[kLinuxNSIG];
std::atomic<uint32_t> g_seq{0};
std::atomic_flag      g_wlock = ATOMIC_FLAG_INIT;

void write_slot(int s, const BionicSigactionSC& a) {
    bool locked = false;
    for (int i = 0; i < (1 << 20); ++i) {
        if (!g_wlock.test_and_set(std::memory_order_acquire)) { locked = true; break; }
    }
    g_seq.fetch_add(1, std::memory_order_acq_rel);          // odd: writing
    std::atomic_signal_fence(std::memory_order_seq_cst);
    g_slot[s].act = a;
    g_slot[s].set = true;
    std::atomic_signal_fence(std::memory_order_seq_cst);
    g_seq.fetch_add(1, std::memory_order_release);          // even: stable
    if (locked) g_wlock.clear(std::memory_order_release);
}
bool read_slot(int s, BionicSigactionSC* out) {
    for (int tries = 0; tries < 4096; ++tries) {
        const uint32_t v0 = g_seq.load(std::memory_order_acquire);
        if (v0 & 1) continue;
        ChainSlot copy;
        std::memcpy(&copy, const_cast<const ChainSlot*>(&g_slot[s]), sizeof(copy));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (g_seq.load(std::memory_order_relaxed) == v0) {
            *out = copy.act;
            return copy.set;
        }
    }
    // A writer on THIS thread was interrupted mid-write: take what is there.
    *out = g_slot[s].act;
    return g_slot[s].set;
}

// HotSpot's documented signal hook (see the header). Resolved outside signal
// context: from a constructor and again on the first recorded action, because
// dlsym is not async-signal-safe.
using JvmHandleFn = int (*)(int, siginfo_t*, void*, int);
std::atomic<JvmHandleFn> g_jvm_handle{nullptr};
void resolve_jvm_handle() {
    if (g_jvm_handle.load(std::memory_order_relaxed)) return;
    void* p = dlsym(RTLD_DEFAULT, "JVM_handle_bsd_signal");
    if (p) g_jvm_handle.store(reinterpret_cast<JvmHandleFn>(p), std::memory_order_release);
}
__attribute__((constructor)) void sigchain_ctor() { resolve_jvm_handle(); }

// ---- delivery state (per thread) ------------------------------------------------
// A delivery is "in progress" while the guest handler is below us on the stack.
// A guest handler may siglongjmp out instead of returning (OpenSSL's SIGILL
// probe does, once per probed instruction), which leaves no chance to clear a
// flag, so "is this fault nested inside it?" is judged from the INTERRUPTED
// context's sp: code running inside the handler has its sp below our
// delivering frame, on the same stack (thread stack or alt stack). After a
// siglongjmp the next fault interrupts code above that frame, or on the other
// stack, and the stale record is dropped. Comparing our OWN frame addresses
// instead would misfire: two unrelated deliveries through handlers of
// different depth land at different addresses.
// A guest handler may also leave with longjmp/siglongjmp through OUR Bionic
// shims, which call __macapk_sigchain_note_longjmp first: a jump to a target
// above the delivering frame (or off the alt stack it ran on) ends the delivery
// on the spot. Without that, the stale record made a LATER legitimate fault from
// a deeper frame on the same stack (within 64 MiB) look nested, and it went to
// the crash path instead of the guest action (an init-time SIGILL probe, then a
// GC write-barrier SIGSEGV 32 KiB deeper: gate row L1).
struct Delivery {
    bool      active;
    bool      on_alt;      // `frame` lies inside the alt stack
    uintptr_t frame;       // our frame while the guest handler runs
};
thread_local Delivery t_dlv = {false, false, 0};
// How many threads have t_dlv.active set. Only a fast-path filter for
// __macapk_sigchain_note_longjmp, which every guest longjmp calls: while it is
// 0 (the norm: no delivery in flight anywhere) that call never touches this
// TU's thread_locals, so it cannot trigger a TLV allocation on a thread that
// never took a delivery. A thread always sees its own increment.
std::atomic<int> g_dlv_live{0};
void dlv_begin(uintptr_t frame, bool on_alt) {
    if (!t_dlv.active) g_dlv_live.fetch_add(1, std::memory_order_relaxed);
    t_dlv.frame  = frame;
    t_dlv.on_alt = on_alt;
    t_dlv.active = true;
}
void dlv_end() {
    if (t_dlv.active) {
        t_dlv.active = false;
        g_dlv_live.fetch_sub(1, std::memory_order_relaxed);
    }
}
// Loop guard: a handler that RETURNS having changed nothing re-runs the
// faulting instruction, which faults again. On Linux that spins forever; here,
// after kMaxNoProgress such returns for the same (signal, pc, address) the fault
// goes to the runtime's crash path instead, so a hang becomes a report.
// "Changed nothing" is judged precisely, not by pc alone: a handler that fixes
// the CAUSE and returns without moving pc is legitimate -- a mprotect-based GC
// write barrier (Boehm MPROTECT_VDB) unprotects the page and lets the store
// retry -- so a return after which the faulting access is now PERMITTED by the
// VM counts as progress (see access_now_permitted), however soon the same store
// faults again after the next re-protect.
constexpr int kMaxNoProgress = 3;
thread_local int       t_np_sig   = 0;
thread_local uintptr_t t_np_pc    = 0;
thread_local uintptr_t t_np_addr  = 0;
thread_local int       t_np_count = 0;

// Is `p` inside this thread's registered alternate signal stack?
bool in_altstack(uintptr_t p) {
    stack_t cur;
    std::memset(&cur, 0, sizeof(cur));
    if (::sigaltstack(nullptr, &cur) != 0 || (cur.ss_flags & SS_DISABLE) || !cur.ss_sp)
        return false;
    const uintptr_t lo = uintptr_t(cur.ss_sp);
    return p >= lo && p < lo + cur.ss_size;
}

std::atomic<int> g_log_budget{48};
bool log_ok() { return g_log_budget.fetch_sub(1, std::memory_order_relaxed) > 0; }

// Darwin si_code -> Linux si_code. XNU's sendsig (bsd/dev/arm/unix_signal.c)
// assigns si_code by SIGNAL NUMBER, not by cause: every SIGILL gets
// ILL_ILLTRP, every SIGBUS BUS_ADRALN, every SIGSEGV SEGV_ACCERR, SIGFPE the
// EXC_ARM_FP_* mapping or FPE_NOOP (0), and SIGTRAP / SIGABRT / everything else
// the process's recorded p->si_code (SI_USER for kill(2), 0 otherwise). So:
//   SIGILL  -> ILL_ILLOPC: what the arm64 Linux kernel raises for an undefined
//              instruction (arch/arm64/kernel/traps.c do_el0_undef ->
//              force_signal_inject(SIGILL, ILL_ILLOPC, ...)).
//   SIGTRAP -> TRAP_BRKPT for 0 (a BRK exception; arm64 Linux:
//              send_user_sigtrap(TRAP_BRKPT), arch/arm64/kernel/debug-monitors.c).
//   SIGSEGV / SIGBUS -> placeholders; the caller classifies the fault
//              (classify_memory_fault), since the XNU code says nothing.
//   SIGFPE  -> the FPE_* value renumbered; FPE_NOOP (0) -> SI_USER, because
//              arm64 hardware raises no SIGFPE with FP traps disabled (the
//              default), so an FPE_NOOP one was sent, not faulted.
//   SIGABRT -> SI_TKILL for 0: abort() is thread-directed (Bionic abort.cpp
//              inline_raise -> tgkill; macOS abort -> pthread_kill).
// XNU cannot tell a raise(SIGSEGV) from a real fault by si_code; a handler that
// is sent SIGSEGV/SIGBUS/SIGILL by raise() is therefore described as a fault.
int linux_si_code(int host_sig, int code) {
    switch (code) {
        case SI_USER:    return kLinuxSI_USER;
        case SI_QUEUE:   return kLinuxSI_QUEUE;
        case SI_TIMER:   return kLinuxSI_TIMER;
        case SI_MESGQ:   return kLinuxSI_MESGQ;
        case SI_ASYNCIO: return kLinuxSI_ASYNCIO;
        default: break;
    }
    switch (host_sig) {
        case SIGILL:  return 1;                          // ILL_ILLOPC
        case SIGTRAP: return code == 0 ? 1 : code;       // TRAP_BRKPT
        case SIGSEGV: return 1;                          // classified by the caller
        case SIGBUS:  return 2;                          // classified by the caller
        case SIGABRT: return code == 0 ? -6 : code;      // SI_TKILL
        case SIGFPE:
            // Linux: INTDIV 1 INTOVF 2 FLTDIV 3 FLTOVF 4 FLTUND 5 FLTRES 6 FLTINV 7 FLTSUB 8.
            switch (code) {
                case FPE_INTDIV: return 1;
                case FPE_INTOVF: return 2;
                case FPE_FLTDIV: return 3;
                case FPE_FLTOVF: return 4;
                case FPE_FLTUND: return 5;
                case FPE_FLTRES: return 6;
                case FPE_FLTINV: return 7;
                case FPE_FLTSUB: return 8;
                default:         return kLinuxSI_USER;   // FPE_NOOP
            }
        default:
            return code == 0 ? kLinuxSI_USER : code;
    }
}

// ---- which LINUX signal is this memory fault? --------------------------------
// XNU and Linux disagree about the signal NUMBER for the commonest fault a crash
// reporter sees. XNU (bsd/uxkern/ux_exception.c) turns EXC_BAD_ACCESS into
// SIGSEGV only for KERN_INVALID_ADDRESS and into SIGBUS for everything else, so
// a read of a PROT_NONE page or a write to a read-only one arrives as SIGBUS;
// and its sendsig (bsd/dev/arm/unix_signal.c) then fills si_code with a
// constant (BUS_ADRALN for every SIGBUS, SEGV_ACCERR for every SIGSEGV), so
// si_code carries no information. Linux sends SIGSEGV for both mapping and
// permission faults (SEGV_MAPERR / SEGV_ACCERR) and keeps SIGBUS for alignment
// (BUS_ADRALN) and for a mapping whose backing store is gone, e.g. a file page
// past EOF (BUS_ADRERR). So the guest's signal is derived from the fault itself:
//   * ESR data-abort DFSC 0b100001 (alignment fault)     -> SIGBUS / BUS_ADRALN
//   * no VM region contains the address                  -> SIGSEGV / SEGV_MAPERR
//   * a region whose protection lacks the access (the     -> SIGSEGV / SEGV_ACCERR
//     ESR's WnR bit for data, EXECUTE for an instruction
//     abort, else READ)
//   * a region that allows it (so the pager failed)       -> SIGBUS / BUS_ADRERR
// ESR fields per the Arm ARM (ESR_ELx): EC [31:26] 0x20/0x21 instruction abort,
// 0x24/0x25 data abort; for data aborts ISS WnR is bit 6; DFSC is [5:0].
// True when pc lies in the dyld shared cache, i.e. in a macOS system library
// (libsqlite3, libsystem, WebKit...). Guest code never runs there: it lives in
// the translator's own mappings. _dyld_get_shared_cache_range is a plain
// pointer read, safe in a signal handler.
extern "C" const void* _dyld_get_shared_cache_range(size_t* length);
static bool pc_in_dyld_shared_cache(uintptr_t pc) {
    size_t len = 0;
    const void* base = _dyld_get_shared_cache_range(&len);
    if (!base || !len) return false;
    const uintptr_t b = uintptr_t(base);
    return pc >= b && pc < b + len;
}

struct LinuxFault { int sig; int code; };
LinuxFault classify_memory_fault(uintptr_t far, uint32_t esr) {
    const uint32_t ec = esr >> 26;
    const uint32_t fsc = esr & 0x3fu;
    const bool data_abort = (ec == 0x24 || ec == 0x25);
    const bool insn_abort = (ec == 0x20 || ec == 0x21);
    if (data_abort && fsc == 0x21) return {kLinuxSIGBUS, 1 /*BUS_ADRALN*/};
    mach_vm_address_t a = far;
    mach_vm_size_t sz = 0;
    vm_region_basic_info_data_64_t bi;
    mach_msg_type_number_t cnt = VM_REGION_BASIC_INFO_COUNT_64;
    mach_port_t obj = MACH_PORT_NULL;
    const kern_return_t kr = mach_vm_region(mach_task_self(), &a, &sz, VM_REGION_BASIC_INFO_64,
                                            reinterpret_cast<vm_region_info_t>(&bi), &cnt, &obj);
    if (kr != KERN_SUCCESS || far < a || far >= a + sz)
        return {kLinuxSIGSEGV, 1 /*SEGV_MAPERR*/};
    vm_prot_t need = VM_PROT_READ;
    if (data_abort && (esr & (1u << 6))) need = VM_PROT_WRITE;
    else if (insn_abort)                 need = VM_PROT_EXECUTE;
    if ((bi.protection & need) != need) return {kLinuxSIGSEGV, 2 /*SEGV_ACCERR*/};
    return {kLinuxSIGBUS, 2 /*BUS_ADRERR*/};
}

// After a handler returned without moving pc: would the faulting access now
// succeed? The CALLER only asks for a fault that was classified, before the
// handler ran, as a mapping/permission fault (SIGSEGV MAPERR/ACCERR): alignment
// faults and pager failures (SIGBUS) re-fault whatever the protection is. True
// when the address is now mapped with the access the ESR says was attempted.
bool access_now_permitted(uintptr_t far, uint32_t esr) {
    mach_vm_address_t a = far;
    mach_vm_size_t sz = 0;
    vm_region_basic_info_data_64_t bi;
    mach_msg_type_number_t cnt = VM_REGION_BASIC_INFO_COUNT_64;
    mach_port_t obj = MACH_PORT_NULL;
    if (mach_vm_region(mach_task_self(), &a, &sz, VM_REGION_BASIC_INFO_64,
                       reinterpret_cast<vm_region_info_t>(&bi), &cnt, &obj) != KERN_SUCCESS
        || far < a || far >= a + sz)
        return false;
    const uint32_t ec = esr >> 26;
    vm_prot_t need = VM_PROT_READ;
    if ((ec == 0x24 || ec == 0x25) && (esr & (1u << 6))) need = VM_PROT_WRITE;
    else if (ec == 0x20 || ec == 0x21)                    need = VM_PROT_EXECUTE;
    return (bi.protection & need) == need;
}

inline void put32(uint8_t* b, size_t off, uint32_t v) { std::memcpy(b + off, &v, 4); }
inline void put64(uint8_t* b, size_t off, uint64_t v) { std::memcpy(b + off, &v, 8); }

// The fault kinds whose ucontext carries an esr_context on Linux (any
// synchronous exception the kernel attributes a fault code to).
bool is_sync_fault(int host_sig, int linux_code) {
    return linux_code > 0 && (host_sig == SIGSEGV || host_sig == SIGBUS
        || host_sig == SIGILL || host_sig == SIGTRAP || host_sig == SIGFPE);
}

// Host signals the runtime must always be able to take on this thread, even
// while a guest handler runs: they are all synchronous faults or traps routed
// through front handlers (MRS emulation, svc->BRK emulation, null recovery,
// HotSpot). A nested fault inside a guest handler is then detected and sent to
// the crash path rather than killing the process with a blocked-signal fault.
sigset_t runtime_sync_set() {
    sigset_t s;
    sigemptyset(&s);
    sigaddset(&s, SIGILL);
    sigaddset(&s, SIGTRAP);
    sigaddset(&s, SIGFPE);
    sigaddset(&s, SIGBUS);
    sigaddset(&s, SIGSEGV);
    sigaddset(&s, SIGSYS);
    return s;
}

}  // namespace

// ============================================================================
// Exported to bionic_shim.cpp
// ============================================================================

// 1 when guest actions for the chained signals are recorded here (the gate is
// on and the raw-install debug escape is not).
extern "C" int __macapk_sigchain_active(void) { return active() ? 1 : 0; }

// 1 when `linux_sig` is recorded by this registry instead of reaching the host.
extern "C" int __macapk_sigchain_claims(int linux_sig) {
    return (active() && chained_linux(linux_sig)) ? 1 : 0;
}

// sigaction() for a chained signal: libsigchain's __sigaction, verbatim in
// behaviour. `act`/`oldact` are Bionic LP64 struct sigaction.
extern "C" int __macapk_sigchain_sigaction(int linux_sig, const void* act_v, void* oldact_v) {
    if (linux_sig <= 0 || linux_sig >= kLinuxNSIG) { errno = EINVAL; return -1; }
    resolve_jvm_handle();
    const BionicSigactionSC* act = static_cast<const BionicSigactionSC*>(act_v);
    BionicSigactionSC saved{};
    read_slot(linux_sig, &saved);            // never set -> zeros == SIG_DFL
    if (act) {
        BionicSigactionSC a = *act;
        a.pad = 0;
        a.sa_flags = int32_t(uint32_t(a.sa_flags) & kSigchainSupportedFlags);
        write_slot(linux_sig, a);
        static std::atomic<int> n{0};
        if (n.fetch_add(1, std::memory_order_relaxed) < 24) {
            std::fprintf(stderr,
                "[sigchain] recorded guest sigaction(sig=%d handler=%p flags=%#x) -- "
                "not installed on the host; runs only for a fault neither the runtime "
                "nor HotSpot claims (libsigchain semantics)\n",
                linux_sig, a.handler, unsigned(a.sa_flags));
            std::fflush(stderr);
        }
    }
    if (oldact_v) std::memcpy(oldact_v, &saved, sizeof(saved));
    return 0;
}

// signal()/bsd_signal() for a chained signal: libsigchain's signal() records
// {handler, SA_RESTART | SA_ONSTACK, empty mask} and returns the old handler.
extern "C" void* __macapk_sigchain_signal(int linux_sig, void* handler) {
    if (linux_sig <= 0 || linux_sig >= kLinuxNSIG) {
        errno = EINVAL;
        return reinterpret_cast<void*>(SIG_ERR);
    }
    BionicSigactionSC a{};
    a.sa_flags = int32_t(kLinuxSA_RESTART | kLinuxSA_ONSTACK);
    a.handler  = handler;
    BionicSigactionSC old{};
    __macapk_sigchain_sigaction(linux_sig, &a, &old);
    return old.handler;
}

// sigprocmask()/pthread_sigmask() filter for SIG_BLOCK / SIG_SETMASK sets
// (Bionic bit n-1 for signal n; the host sees the same bit positions under the
// raw scheme). Removes the claimed signals, as libsigchain does ("we can't allow
// the user to block that signal"), plus the two Linux numbers whose raw host
// slot is one of OUR synchronous handlers: bit 9 (Linux 10, SIGUSR1 -> Darwin
// SIGBUS, the bus-error front handler) and bit 11 (Linux 12, SIGUSR2 -> Darwin
// SIGSYS, the inline-syscall forwarder). Blocking either would turn our own
// fault/trap on that thread into a kernel kill.
extern "C" uint64_t __macapk_sigchain_filter_mask(uint64_t bionic_set) {
    if (!active()) return bionic_set;
    const uint64_t claimed =
        (1ull << (kLinuxSIGILL - 1)) | (1ull << (kLinuxSIGTRAP - 1)) |
        (1ull << (kLinuxSIGBUS - 1)) | (1ull << (kLinuxSIGSEGV - 1)) |
        (1ull << (kLinuxSIGUSR1 - 1)) | (1ull << (kLinuxSIGUSR2 - 1));
    return bionic_set & ~claimed;
}

// 1 when a guest install of `linux_sig` must be REFUSED rather than passed to
// the host raw: Linux 12 (SIGUSR2), whose raw host slot is Darwin 12 = SIGSYS,
// the inline-syscall forwarder (macapk_sigsys_handler). Before 2026-09-27 the
// libc path forwarded such an install straight onto SIGSYS, displacing the
// forwarder until the next image load re-installed it; the P3 inline
// rt_sigaction route would now reach the same place. Linux 10 (SIGUSR1 ->
// Darwin SIGBUS) is refused by bionic_shim.cpp's runtime-owned check in both
// gate arms and needs nothing here. Gated with MACAPK_SIGCHAIN (=0 keeps the
// old raw pass-through for 12). The honest limit: a guest that SENDS itself
// SIGUSR2 still reaches Darwin SIGSYS (the raw-numbering scheme,
// docs/arm64-signal-shim-gaps_2026-08-31.md), where no guest handler waits.
extern "C" int __macapk_sigchain_refuse_raw(int linux_sig) {
    return (active() && linux_sig == kLinuxSIGUSR2) ? 1 : 0;
}

// Called by the arm64 Bionic longjmp / siglongjmp / _longjmp shims
// (bionic_shim.cpp) BEFORE they restore any register, with the sp they are
// about to install. A jump out of a guest handler we delivered to ends that
// delivery; a jump that stays inside the handler (its own setjmp/longjmp, whose
// target sp is below our delivering frame, on the same stack) does not.
extern "C" void __macapk_sigchain_note_longjmp(uintptr_t target_sp) {
    if (g_dlv_live.load(std::memory_order_relaxed) == 0) return;
    if (!t_dlv.active) return;
    if (target_sp > t_dlv.frame || (t_dlv.on_alt && !in_altstack(target_sp))) dlv_end();
}

// The front-handler hook. Returns 1 when the signal was fully handled here (a
// guest action ran and returned, the guest action is SIG_IGN, or HotSpot
// recognised it), in which case the caller simply returns from its handler.
// Returns 0 to let the caller continue down its existing chain unchanged: the
// gate is off, nothing was ever recorded for this signal, the guest action is
// SIG_DFL, the fault happened inside a guest handler we delivered to, or a
// guest handler keeps returning without making progress.
extern "C" int __macapk_sigchain_deliver(int host_sig, siginfo_t* info, void* ucv) {
    if (!active()) return 0;
    int ls = host_to_linux(host_sig);
    if (!ls || !info || !ucv) return 0;
    ucontext_t* uc = static_cast<ucontext_t*>(ucv);
    if (!uc->uc_mcontext) return 0;
    int lcode = linux_si_code(host_sig, info->si_code);
    const bool memfault = (host_sig == SIGSEGV || host_sig == SIGBUS) && lcode > 0;

    // Cheapest exit first: no guest action recorded for any signal this fault
    // could become -> exactly today's path. (A kernel memory fault may be either
    // SIGSEGV or SIGBUS for the guest, whatever XNU called it.)
    BionicSigactionSC a{};
    if (memfault) {
        BionicSigactionSC aseg{}, abus{};
        const bool hs = read_slot(kLinuxSIGSEGV, &aseg);
        const bool hb = read_slot(kLinuxSIGBUS, &abus);
        if (!hs && !hb) return 0;
    } else if (!read_slot(ls, &a)) {
        return 0;
    }

    // Special handler slot: HotSpot first. Only for the signals it documents
    // recognising (SIGBUS, SIGSEGV, SIGILL, SIGFPE among them). Its implicit
    // null checks and safepoint polls end here, before any classification.
    if (host_sig == SIGSEGV || host_sig == SIGBUS || host_sig == SIGILL || host_sig == SIGFPE) {
        if (JvmHandleFn jh = g_jvm_handle.load(std::memory_order_acquire)) {
            if (jh(host_sig, info, ucv, /*abort_if_unrecognized=*/0)) return 1;
        }
    }

    // A SIGILL raised by macOS's OWN code (a trap inside a dyld-shared-cache
    // library: pointer-authentication failure, an assert `brk`) is not an event
    // of the Android program, so no guest handler may run for it. On a device
    // the guest's SIGILL action is reached only by a fault in the app's own
    // code or Bionic; here the host's system libraries execute on host threads
    // too (WebKit's SQLite thread opening a store, CoreFoundation, Metal...),
    // and handing their traps to an app's crash handler (Breakpad re-raising,
    // OpenSSL's cpuid probe siglongjmp'ing across a foreign thread) turns a
    // host fault into a second, misleading one. Memory faults keep the existing
    // path: a guest pointer that faults inside the memcpy/strlen shims IS the
    // guest's SIGSEGV, as on Android.
    if (host_sig == SIGILL) {
        const uintptr_t hpc = uintptr_t(uc->uc_mcontext->__ss.__pc);
        if (pc_in_dyld_shared_cache(hpc)) {
            static std::atomic<int> nh{0};
            if (nh.fetch_add(1, std::memory_order_relaxed) < 8) {
                std::fprintf(stderr,
                    "[sigchain] SIGILL at pc=%p is inside a macOS system library (host "
                    "fault, not the guest's) -- not delivered to a guest handler; to the "
                    "runtime's crash path\n", reinterpret_cast<void*>(hpc));
                std::fflush(stderr);
            }
            return 0;
        }
    }

    // A kernel-generated memory fault: the guest's signal and si_code come from
    // the fault itself, not from XNU's numbering (see classify_memory_fault).
    if (memfault) {
        const LinuxFault lf = classify_memory_fault(uintptr_t(uc->uc_mcontext->__es.__far),
                                                    uint32_t(uc->uc_mcontext->__es.__esr));
        ls = lf.sig;
        lcode = lf.code;
        if (!read_slot(ls, &a)) return 0;          // the guest has no action for THAT signal
    }

    const uintptr_t pc   = uintptr_t(uc->uc_mcontext->__ss.__pc);
    const uintptr_t addr = uintptr_t(info->si_addr);
    if (a.handler == reinterpret_cast<void*>(SIG_DFL)) {
        static std::atomic<int> n{0};
        if (n.fetch_add(1, std::memory_order_relaxed) < 8) {
            std::fprintf(stderr,
                "[sigchain] guest action for sig %d is SIG_DFL (pc=%p si_addr=%p) -- "
                "to the runtime's crash path, as libsigchain reverts to SIG_DFL\n",
                ls, reinterpret_cast<void*>(pc), reinterpret_cast<void*>(addr));
            std::fflush(stderr);
        }
        return 0;
    }

    // Nested: this fault happened while a guest handler we called is still
    // running on this thread (below us on the same stack).
    if (t_dlv.active) {
        const uintptr_t isp = uintptr_t(uc->uc_mcontext->__ss.__sp);
        if (in_altstack(isp) == t_dlv.on_alt && isp < t_dlv.frame
            && t_dlv.frame - isp < (uintptr_t(64) << 20)) {
            if (log_ok()) {
                std::fprintf(stderr,
                    "[sigchain] sig %d at pc=%p INSIDE a guest signal handler -- not "
                    "re-delivered; to the runtime's crash path\n",
                    ls, reinterpret_cast<void*>(pc));
                std::fflush(stderr);
            }
            return 0;
        }
        dlv_end();                                 // it left without our longjmp shim
    }

    // A handler that keeps returning without moving past the fault.
    const bool same = (t_np_sig == ls && t_np_pc == pc && t_np_addr == addr);
    if (same && t_np_count >= kMaxNoProgress) {
        if (log_ok()) {
            std::fprintf(stderr,
                "[sigchain] guest handler for sig %d returned %d times without moving "
                "past pc=%p si_addr=%p -- to the runtime's crash path\n",
                ls, t_np_count, reinterpret_cast<void*>(pc), reinterpret_cast<void*>(addr));
            std::fflush(stderr);
        }
        t_np_count = 0;
        return 0;
    }
    if (!same) { t_np_sig = ls; t_np_pc = pc; t_np_addr = addr; t_np_count = 0; }

    if (a.handler == reinterpret_cast<void*>(SIG_IGN)) {
        ++t_np_count;                               // returning never makes progress
        return 1;
    }

    // ---- synthesize the Linux siginfo_t --------------------------------------
    alignas(16) uint8_t lsi[kLinuxSiginfoSize];
    std::memset(lsi, 0, sizeof(lsi));
    put32(lsi, kLinuxSiSignoOff, uint32_t(ls));
    put32(lsi, kLinuxSiErrnoOff, uint32_t(info->si_errno));
    put32(lsi, kLinuxSiCodeOff, uint32_t(lcode));
    if (lcode <= 0) {
        // _kill / _rt: the sender. XNU leaves si_pid 0 for a thread-directed
        // send (pthread_kill), which is always this process.
        put32(lsi, kLinuxSiPidOff, uint32_t(info->si_pid ? info->si_pid : getpid()));
        put32(lsi, kLinuxSiUidOff, uint32_t(info->si_pid ? info->si_uid : getuid()));
        put64(lsi, kLinuxSiValueOff, uint64_t(uintptr_t(info->si_value.sival_ptr)));
    } else {
        put64(lsi, kLinuxSiAddrOff, uint64_t(addr));
    }

    // ---- synthesize the Bionic ucontext_t -------------------------------------
    alignas(16) BionicUcontext luc;
    std::memset(&luc, 0, sizeof(luc));
    {
        stack_t ss;
        std::memset(&ss, 0, sizeof(ss));
        if (::sigaltstack(nullptr, &ss) == 0) {
            luc.uc_stack.ss_sp    = uint64_t(uintptr_t(ss.ss_sp));
            luc.uc_stack.ss_size  = uint64_t(ss.ss_size);
            luc.uc_stack.ss_flags = ((ss.ss_flags & SS_ONSTACK) ? kLinuxSS_ONSTACK : 0)
                                  | ((ss.ss_flags & SS_DISABLE) ? kLinuxSS_DISABLE : 0);
        }
    }
    luc.uc_sigmask = uint64_t(uint32_t(uc->uc_sigmask));   // raw bit positions (see header)
    const auto& ss = uc->uc_mcontext->__ss;
    const auto& ns = uc->uc_mcontext->__ns;
    LinuxSigcontext& mc = luc.uc_mcontext;
    mc.fault_address = (host_sig == SIGSEGV || host_sig == SIGBUS)
                     ? uint64_t(uc->uc_mcontext->__es.__far) : 0;
    for (int i = 0; i < 29; ++i) mc.regs[i] = ss.__x[i];
    mc.regs[29] = ss.__fp;
    mc.regs[30] = ss.__lr;
    mc.sp       = ss.__sp;
    mc.pc       = ss.__pc;
    mc.pstate   = uint64_t(ss.__cpsr);
    put32(mc.reserved, 0, kFpsimdMagic);
    put32(mc.reserved, 4, kFpsimdSize);
    put32(mc.reserved, 8, ns.__fpsr);
    put32(mc.reserved, 12, ns.__fpcr);
    std::memcpy(mc.reserved + 16, &ns.__v[0], 32 * 16);
    if (is_sync_fault(host_sig, lcode)) {
        put32(mc.reserved, kFpsimdSize, kEsrMagic);
        put32(mc.reserved, kFpsimdSize + 4, kEsrSize);
        put64(mc.reserved, kFpsimdSize + 8, uint64_t(uc->uc_mcontext->__es.__esr));
    }
    // (the null terminator record that follows is already zero)

    // ---- libsigchain's mask: uc_sigmask | sa_mask | the signal itself unless
    // SA_NODEFER, minus what the runtime must keep catchable (see
    // runtime_sync_set).
    sigset_t m = uc->uc_sigmask;
    const uint32_t amask = uint32_t(a.mask & 0xffffffffu);   // raw bit positions
    for (int s = 1; s < NSIG; ++s)
        if (amask & (1u << (s - 1))) sigaddset(&m, s);
    if (!(uint32_t(a.sa_flags) & kLinuxSA_NODEFER)) sigaddset(&m, host_sig);
    const sigset_t keep = runtime_sync_set();
    for (int s = 1; s < NSIG; ++s)
        if (sigismember(&keep, s)) sigdelset(&m, s);
    sigset_t saved_mask;
    pthread_sigmask(SIG_SETMASK, &m, &saved_mask);

    if (log_ok()) {
        std::fprintf(stderr,
            "[sigchain] delivered sig=%d (si_code=%d si_addr=%p pc=%p) to guest handler "
            "%p%s -- a signal neither the runtime nor HotSpot claimed\n",
            ls, lcode, reinterpret_cast<void*>(addr), reinterpret_cast<void*>(pc),
            a.handler, (uint32_t(a.sa_flags) & kLinuxSA_SIGINFO) ? " (SA_SIGINFO)" : "");
        std::fflush(stderr);
    }

    // (Here t_dlv is never active: a live record either made this fault
    // "nested" above, which returned, or was dropped as stale.)
    const uintptr_t frame = uintptr_t(__builtin_frame_address(0));
    const bool frame_on_alt = in_altstack(frame);
    enable_x18_preservation();
    dlv_begin(frame, frame_on_alt);
    if (uint32_t(a.sa_flags) & kLinuxSA_SIGINFO) {
        reinterpret_cast<void (*)(int, void*, void*)>(a.handler)(ls, lsi, &luc);
    } else {
        reinterpret_cast<void (*)(int)>(a.handler)(ls);
    }
    dlv_end();

    // ---- the handler returned: write its register file back (a Mono / V8 /
    // OpenJ9-style handler moves pc or fixes a register and returns).
    auto& wss = uc->uc_mcontext->__ss;
    for (int i = 0; i < 29; ++i) wss.__x[i] = mc.regs[i];
    wss.__fp = mc.regs[29];
    wss.__lr = mc.regs[30];
    wss.__sp = mc.sp;
    wss.__pc = mc.pc;
    // Only the condition flags are the guest's to change (Linux restore_sigframe
    // also refuses anything but a valid user pstate).
    wss.__cpsr = (wss.__cpsr & 0x0fffffffu) | (uint32_t(mc.pstate) & 0xf0000000u);
    uint32_t magic = 0;
    std::memcpy(&magic, mc.reserved, 4);
    if (magic == kFpsimdMagic) {
        auto& wns = uc->uc_mcontext->__ns;
        uint32_t fpsr = 0, fpcr = 0;
        std::memcpy(&fpsr, mc.reserved + 8, 4);
        std::memcpy(&fpcr, mc.reserved + 12, 4);
        wns.__fpsr = fpsr;
        wns.__fpcr = fpcr;
        std::memcpy(&wns.__v[0], mc.reserved + 16, 32 * 16);
    }
    // Linux's rt_sigreturn restores the FRAME's uc_sigmask, which a handler may
    // edit; carry an edit back into the Darwin frame (minus the claimed set, as
    // libsigchain's sigprocmask would filter it). Untouched -> untouched.
    const uint64_t delivered_mask = uint64_t(uint32_t(uc->uc_sigmask));
    if (luc.uc_sigmask != delivered_mask) {
        sigset_t nm = sigset_t(uint32_t(__macapk_sigchain_filter_mask(luc.uc_sigmask)));
        sigdelset(&nm, SIGKILL);
        sigdelset(&nm, SIGSTOP);
        uc->uc_sigmask = nm;
    }
    pthread_sigmask(SIG_SETMASK, &saved_mask, nullptr);

    if (uintptr_t(mc.pc) != pc) {
        t_np_count = 0;                             // moved past it
    } else if (memfault && ls == kLinuxSIGSEGV
               && access_now_permitted(uintptr_t(uc->uc_mcontext->__es.__far),
                                       uint32_t(uc->uc_mcontext->__es.__esr))) {
        t_np_count = 0;                             // fixed the cause; the retry succeeds
    } else {
        ++t_np_count;                               // the same fault is coming right back
    }
    return 1;
}

// ============================================================================
// The inline-syscall routes (P3): MACAPK_INLINE_SIGSYSCALLS_REAL
// ============================================================================
//
// A guest `svc #0` is rewritten to BRK by the loader and emulated by
// macapk_svc_brk_sigtrap (bionic_shim.cpp), where x8 and x0..x5 arrive intact.
// Until 2026-09-27 that emulator answered the signal-family numbers with a fake
// 0 and did nothing ("neutralize"), copied from the SIGSYS path where x0 really
// is unrecoverable. On the BRK path the arguments are real, so these are now
// serviced by the same shims a libc call reaches, in the KERNEL convention
// (value, or -errno in Linux numbering; errno untouched):
//   130 tkill, 131 tgkill           -> __macapk_arm64_tgkill
//   132 sigaltstack                 -> __macapk_arm64_sigaltstack (translating
//                                      stack_t; substitutes a host-sized stack
//                                      for a Bionic-sized request)
//   133 rt_sigsuspend               -> __macapk_arm64_sigsuspend
//   134 rt_sigaction                -> __macapk_arm64_sigaction (kernel layout
//                                      converted; chained signals land in the
//                                      registry above, the rest as today)
//   135 rt_sigprocmask              -> the interrupted context's uc_sigmask
//                                      (we run inside the SIGTRAP handler, and
//                                      sigreturn would undo a live-mask change),
//                                      with the libsigchain filter; the libc
//                                      shim when no ucontext is available
// The kernel rejects a sigsetsize other than sizeof(sigset_t) (8 on arm64) with
// EINVAL for 133/134/135, and so do we.
//
// MACAPK_INLINE_SIGSYSCALLS_REAL:
//   unset / "virt" / "2" = DEFAULT: real for everything except 132, which is
//       RECORDED per thread and reported back but never installed on the host;
//   "1" = real for all six, sigaltstack included;
//   "0" = the old fake-0 neutralize (restores the pre-2026-09-27 path).
// Why sigaltstack is NOT real by default: routing a guest's sigaltstack to the
// host crashed HotSpot's C2 compiler (Arena::grow SEGV) on Instagram 5 of 5
// times (docs/instagram-clinit-hotspot-art-init-parity_2026-09-22.md:417-419).
// That was measured on a different (raw) route than this one, which translates
// stack_t and substitutes a host-sized stack, but "different route" is an
// argument, not a measurement. "1" becomes the default only after Instagram
// reaches its main feed 5/5 and Minecraft its sign-in screen on the installed
// stub with it. tkill/tgkill (130/131) ARE real under the default: a sampling
// or re-raise signal the guest sends itself is now actually delivered.
namespace {
int inline_mode() {
    static const int mode = [] {
        const char* e = std::getenv("MACAPK_INLINE_SIGSYSCALLS_REAL");
        if (!e || !*e) return 2;
        if (e[0] == '0') return 0;       // the project's usual "=0 is off"
        if (e[0] == '1') return 1;
        return 2;                        // "virt", "2", anything else: the default
    }();
    return mode;
}
long kret(int rc) {
    if (rc != -1) return rc;
    const int e = errno;
    return -long(__macapk_errno_mac2lin(e ? e : EINVAL));
}
thread_local LinuxStackT t_virt_altstack = {0, kLinuxSS_DISABLE, 0, 0};
}  // namespace

extern "C" int __macapk_sigchain_inline_mode(void) { return inline_mode(); }

extern "C" long __macapk_sigchain_inline_syscall(long nr, long a0, long a1, long a2,
                                                 long a3, long /*a4*/, long /*a5*/,
                                                 void* ucv, int* handled) {
    *handled = 0;
    const int mode = inline_mode();
    if (!mode) return 0;
    const int saved_errno = errno;
    long r = 0;
    switch (nr) {
        case 130:   // tkill(tid, sig)
            r = kret(__macapk_arm64_tgkill(int(getpid()), int(a0), int(a1)));
            break;
        case 131:   // tgkill(tgid, tid, sig)
            r = kret(__macapk_arm64_tgkill(int(a0), int(a1), int(a2)));
            break;
        case 132: { // sigaltstack(ss, oss)
            if (mode == 2) {
                // Linux kernel/signal.c do_sigaltstack, minus the install:
                // mode = flags & ~SS_AUTODISARM (1u << 31) must be 0,
                // SS_ONSTACK or SS_DISABLE (else -EINVAL); a non-disabling
                // request below MINSIGSTKSZ (5120 on arm64,
                // arch/arm64/include/uapi/asm/signal.h) is -ENOMEM; SS_DISABLE
                // records {NULL, 0}. Reported back with SS_DISABLE when size is
                // 0, and never SS_ONSTACK (nothing is ever installed).
                LinuxStackT prev = t_virt_altstack;
                prev.ss_flags = prev.ss_size ? 0 : kLinuxSS_DISABLE;
                if (a0) {
                    LinuxStackT ns;
                    std::memcpy(&ns, reinterpret_cast<const void*>(a0), sizeof(ns));
                    const uint32_t mode_bits = uint32_t(ns.ss_flags) & ~0x80000000u;
                    if (mode_bits != 0 && mode_bits != uint32_t(kLinuxSS_DISABLE)
                        && mode_bits != uint32_t(kLinuxSS_ONSTACK)) {
                        r = -22;    // -EINVAL
                        break;
                    }
                    if (mode_bits == uint32_t(kLinuxSS_DISABLE)) {
                        ns.ss_sp = 0;
                        ns.ss_size = 0;
                    } else if (ns.ss_size < 5120) {
                        r = -12;    // -ENOMEM
                        break;
                    }
                    t_virt_altstack = ns;
                }
                if (a1) std::memcpy(reinterpret_cast<void*>(a1), &prev, sizeof(prev));
                r = 0;
            } else {
                r = kret(__macapk_arm64_sigaltstack(reinterpret_cast<const void*>(a0),
                                                    reinterpret_cast<void*>(a1)));
            }
            break;
        }
        case 133:   // rt_sigsuspend(mask, sigsetsize)
            if (a1 != 8) { r = -22; break; }
            r = kret(__macapk_arm64_sigsuspend(reinterpret_cast<const uint64_t*>(a0)));
            break;
        case 134: { // rt_sigaction(sig, act, oact, sigsetsize)
            if (a3 != 8) { r = -22; break; }
            BionicSigactionSC in{}, out{};
            const KernelSigaction* ka = reinterpret_cast<const KernelSigaction*>(a1);
            if (ka) {
                in.sa_flags = int32_t(uint32_t(ka->flags));
                in.handler  = ka->handler;
                in.mask     = ka->mask;
                in.restorer = ka->restorer;
            }
            r = kret(__macapk_arm64_sigaction(int(a0), ka ? &in : nullptr, a2 ? &out : nullptr));
            if (r == 0 && a2) {
                KernelSigaction ko{};
                ko.handler  = out.handler;
                ko.flags    = uint64_t(uint32_t(out.sa_flags));
                ko.restorer = out.restorer;
                ko.mask     = out.mask;
                std::memcpy(reinterpret_cast<void*>(a2), &ko, sizeof(ko));
            }
            break;
        }
        case 135: { // rt_sigprocmask(how, set, oset, sigsetsize)
            if (a3 != 8) { r = -22; break; }
            ucontext_t* u = static_cast<ucontext_t*>(ucv);
            if (!u) {
                r = kret(__macapk_arm64_sigprocmask(int(a0), reinterpret_cast<const uint64_t*>(a1),
                                                    reinterpret_cast<uint64_t*>(a2)));
                break;
            }
            // We are INSIDE the SIGTRAP handler that emulates the svc, and the
            // kernel's sigreturn will reinstall uc_sigmask when it returns, so a
            // change made to the live mask here would be undone. The guest's mask
            // is the interrupted context's: read and write THAT (measured: the
            // live-mask route left a SIG_BLOCK with no effect).
            const uint64_t old = uint64_t(uint32_t(u->uc_sigmask));   // raw bit positions
            if (a1) {
                uint64_t set = 0;
                std::memcpy(&set, reinterpret_cast<const void*>(a1), sizeof(set));
                if (a0 == 0 || a0 == 2) set = __macapk_sigchain_filter_mask(set);
                uint64_t nw;
                if (a0 == 0)      nw = old | set;          // SIG_BLOCK
                else if (a0 == 1) nw = old & ~set;         // SIG_UNBLOCK
                else if (a0 == 2) nw = set;                // SIG_SETMASK
                else { r = -22; break; }                   // -EINVAL
                sigset_t m = sigset_t(uint32_t(nw));
                sigdelset(&m, SIGKILL);                    // unblockable, as the kernel
                sigdelset(&m, SIGSTOP);                    // silently drops them
                u->uc_sigmask = m;
            }
            if (a2) std::memcpy(reinterpret_cast<void*>(a2), &old, sizeof(old));
            r = 0;
            break;
        }
        default:
            return 0;                               // not ours: caller's switch
    }
    errno = saved_errno;                            // the kernel face never touches errno
    *handled = 1;
    static std::atomic<int> n{0};
    if (n.fetch_add(1, std::memory_order_relaxed) < 24) {
        std::fprintf(stderr,
            "[svc-brk] inline syscall nr=%ld serviced for real (%s) -> %ld\n",
            nr, (mode == 2 && nr == 132) ? "virtualized sigaltstack" : "libc shim", r);
        std::fflush(stderr);
    }
    return r;
}
