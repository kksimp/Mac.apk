// arm64_native.cpp -- Native arm64 Android ELF loader for Mac.apk.
//
// Apple Silicon macOS is arm64. An Android arm64 .so already contains
// the same machine code that Apple's CPUs execute, so there's no need
// to "translate" it. The only work needed is what Android's dynamic
// linker would do at app launch:
//
//   1. mmap PT_LOAD segments at a contiguous base.
//   2. Walk .rela.dyn + .rela.plt; for each relocation:
//      - R_AARCH64_RELATIVE: add the load base to a value already
//        sitting in memory (was a file-relative offset, becomes a
//        runtime address).
//      - R_AARCH64_GLOB_DAT / JUMP_SLOT: resolve the named symbol to
//        a host address and write it into the GOT slot. We resolve
//        via dlsym(RTLD_DEFAULT) which sees libmacapk-bionic-shim
//        plus libSystem -- the shim exports Bionic and Android-libc
//        names directly so unprefixed lookups work.
//      - R_AARCH64_ABS64: write (symbol_value + r_addend) at the
//        target offset.
//   3. mprotect the executable PT_LOADs to RX.
//   4. Run .init_array constructors.
//
// Public API (linked into libmacapk-bionic-shim.dylib so every
// translator-output dylib has access via the shim):
//
//   void* __macapk_arm64_native_load(const void* blob, size_t size,
//                                       const char* dbg_soname);
//   void* __macapk_arm64_native_load_from_path(const char* so_path_rel,
//                                       const char* dbg_soname);
//   void* __macapk_arm64_native_dlsym(void* handle, const char* name);
//
// t3-mmap (2026-07-24): __macapk_arm64_native_load_from_path is "Lever 3"
// -- it loads straight off the on-disk source .so (mmap/pread from an fd),
// with NO embedded blob at all. arm64_pack.cpp's wrapper generator calls
// this instead of __macapk_arm64_native_load whenever the source .so lives
// under the app's own _apkt/lib/<abi>/ tree (true for every real APK), so
// the wrapper .dylib no longer duplicates its source .so's bytes on disk
// via .incbin. __macapk_arm64_native_load (the embedded-blob path) is kept
// unchanged and fully working: it is still what the in-process Mach-O
// emitter (arm64_macho_emit.cpp, the shipped-.app default) produces, and
// what any wrapper already cached from before this change calls -- the two
// entry points coexist permanently, selected per-wrapper by which one its
// own constructor was compiled to call, so an old self-contained wrapper
// can never cross-wire into the new path-expecting loader or vice versa.
//
// This replaces the obsolete arm64 instruction-lifting path (lift
// arm64 -> LLVM IR -> arm64 again), which was generating buggy IR
// for every `ldr w<n>` and similar idioms. The point of Mac.apk is
// to run Android code on macOS natively -- for arm64 inputs, that
// means executing the original instructions, not re-emitting them.

#include <algorithm>
#include <array>
#include <atomic>
#include <capstone/capstone.h>  // generic x18 platform-register rewriter
#include <cerrno>
#include <cstdint>
#include <utility>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>                   // open (lever-2 file-backed segment mapping)
#include <dirent.h>                  // opendir/readdir (pairip decrypted-code patch dir)
#include <libkern/OSCacheControl.h>  // sys_icache_invalidate
#include <malloc/malloc.h>           // malloc_zone_pressure_relief (t3-syms RAM-measurement hook only)
#include <sys/stat.h>                // mkdir (x18 plan-cache dir)
#include <mach/mach.h>               // mach_task_self (guest_safe_read)
#include <mach/mach_vm.h>            // mach_vm_read_overwrite (guest_safe_read)
#include <mach-o/dyld.h>             // _dyld_image_count/_header/_name (VM census)
#include <mach-o/loader.h>           // mach_header_64 / segment_command_64 (VM census)
#include <sys/types.h>               // major()/minor() for the maps dev field
#include <map>
#include <mutex>
#include <pthread.h>                 // pthread_self (main-thread-id probe)
#include <string>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include <unordered_map>
#include <set>
// syscall guard-page: the pages whose X we strip AFTER apply_segment_protections (deferred, so
// the protection pass does not re-flip them R-X). File scope so both macapk_setup_syscall_guard_page
// and the loader's post-protections step see them. An array because one image can hold more than
// one wrapper page. See docs/pairip-text-selfhash-root-cause.
static uintptr_t g_pending_guard_strip_lo[8] = {0};
static size_t    g_pending_guard_strip_sz[8] = {0};
static int       g_pending_guard_strip_n = 0;
#include <unordered_set>
#include <vector>

// PR_SET_VMA anonymous-mapping label lookup (defined in bionic_shim.cpp).
// Returns the name the GUEST itself gave a mapping via prctl(PR_SET_VMA), or
// nullptr. Used only to echo that name back in maps/smaps -- we never invent
// one.
extern "C" const char* __macapk_vma_name_lookup(uint64_t addr, uint64_t len);
// Main-thread stack bounds, captured at load. The maps writer labels the VMA containing them
// "[stack]", which every Linux process has and we previously had zero of. It MUST be the main
// thread's stack -- Linux labels only that one; other threads' stacks are plain anonymous
// VMAs -- and the maps writer can run on any thread, so reading pthread_self() there would
// label a different region on every capture.
static uintptr_t g_main_stack_lo = 0, g_main_stack_hi = 0;
__attribute__((constructor)) static void macapk_capture_main_stack(void) {
    if (!pthread_main_np()) return;                      // not the main thread; leave 0/0
    pthread_t self = pthread_self();
    void*  top = pthread_get_stackaddr_np(self);         // Darwin's stackaddr is the HIGH end
    size_t sz  = pthread_get_stacksize_np(self);
    g_main_stack_hi = (uintptr_t)top;
    g_main_stack_lo = (uintptr_t)top - (uintptr_t)sz;
}
static inline uintptr_t macapk_main_stack_lo() { return g_main_stack_lo; }
static inline uintptr_t macapk_main_stack_hi() { return g_main_stack_hi; }
// Diagnostic guest-code tracepoint installer (defined in bionic_shim.cpp).
extern "C" void __macapk_install_guest_tracepoint(void*);
// pairip JNI-value capture: publish the traced image's base so the SIGILL
// handler can map an absolute tracepoint address to a static file offset ->
// JNI slot (defined in bionic_shim.cpp).
extern "C" void __macapk_set_pairip_jnicap_base(uintptr_t);
// Engine-gate for the pairip frontier surfaces (defined in bionic_shim.cpp):
// true only when the env flag is set AND this is a pairip app (MACAPK_PAIRIP_APP).
extern "C" bool __macapk_pairip_surface_on(const char* flag);
// MCPE Burst busy-poll throttle installer (defined in bionic_shim.cpp).
extern "C" void __macapk_install_guest_throttle(void*, unsigned);
// STEP 1 signaling-BackgroundTask state watch installer (defined in bionic_shim.cpp).
extern "C" void __macapk_install_bgtask_watch(void*);
// STEP 1 pt.2 BackgroundTask state-machine transition logger (defined in bionic_shim.cpp).
extern "C" void __macapk_install_sm_hook(void*);
// rtc::Event::Wait(kForever) infinite-path liveness net (defined in bionic_shim.cpp).
extern "C" void __macapk_set_rtc_evwait_cap(void*, unsigned);
// Persistent libHttpClient completion census hook (defined in bionic_shim.cpp).
extern "C" void __macapk_install_hc_census(void*, const char*, int);
extern "C" void __macapk_install_tgq_probe(void*);
extern "C" void __macapk_install_connstart_probe(void*);
extern "C" void __macapk_install_tokpost_probe(void*, void*);
extern "C" void __macapk_install_notifyb_probe(void*, void*);
extern "C" void __macapk_install_completer_delay(void*, unsigned);
extern "C" void __macapk_install_forceret(void*, int64_t);
extern "C" void __macapk_install_xsubmit_trace(void*, int);
extern "C" void __macapk_install_dtask_complete(void*);
extern "C" void __macapk_install_xac_capture(void*);
extern "C" void __macapk_install_submit_pool_drain(void*);
extern "C" void __macapk_install_force_gs_handler(void*, void*, void*, unsigned);
extern "C" void __macapk_install_xtqcreate_mode(void*, int, int);
extern "C" void __macapk_install_pump_capture(void*);
extern "C" void __macapk_install_coro_step_trace(void*);
extern "C" void __macapk_install_dispatch_trace(void*);
extern "C" void __macapk_enable_completion_pump();
extern "C" void __macapk_install_submit_capture(void*);
extern "C" void __macapk_install_forwarder_trace(void*);
extern "C" void __macapk_install_maindrain_capture(void*);
extern "C" void __macapk_install_maindrain_watchdog(void*);
extern "C" void __macapk_install_maindrain_wprun(void*);
extern "C" void __macapk_start_wake_signin(unsigned ms);
extern "C" void __macapk_install_bgpark_capture(void*);
extern "C" void __macapk_install_signin_wait(void*);
// Bedrock WorkerPool level-trigger drain FIX (defined in bionic_shim.cpp).
extern "C" void __macapk_install_wp_drain(void*, void*, unsigned);
extern "C" void __macapk_install_signaling_redrive(void*, void*, void*, void*, unsigned);

// ---- MACAPK_MCPE_SIGNIN_PROBES_RETIRED (default ON, added 2026-09-27) ----
// Retires the MCPE "54%" sign-in-stall probe/pump family: the ~35 env-armed hooks this
// file installs at compile-time vaddrs of libminecraftpe.so (md5 6c199c or older) and
// libHttpClient.Android.so (md5 1f5784f1), in the block marked "MCPE sign-in probe
// family" in load_common(), plus their bionic_shim.cpp consumers: the trap handlers, the
// rtc::Event::Wait cap in __macapk_arm64_pthread_cond_wait, the OnRequestCompleted delay
// thunk in the NativeMethodBind callback, and the label-keyed MCPE detail inside the
// (kept, generic) MACAPK_CENSUS_SITES handler.
//
// Why retired: real Android runs the app's own code on this path and adds nothing. Bionic
// pthread_cond_wait with no timeout never returns ETIMEDOUT (pthread_cond.cpp:
// "if (status == -ETIMEDOUT) return ETIMEDOUT; return 0;" and a null abstime never times
// out); webrtc rtc::Event keeps a persistent flag, so a Wait(kForever) that never returns
// means nobody called Set (the v630 catalog note already said so); ART binds
// HttpClientRequest.OnRequestCompleted and calls it with no interposer or delay; and an
// XTaskQueue in Manual mode is drained by the APP (XTaskQueue.h). Every member was an
// experiment against a hypothesis the campaign later dropped, and the current Play build
// (1.26.45.1, lib 7f4737c9) signs in on our layer with every member absent.
//
// Default (retired): every family env var reads as UNSET here, so nothing arms. That is
// also exactly what ships today (every member was already default-OFF), so the default
// path is byte-identical. A family var that IS set is ignored with one loud line, so an
// A/B that sets one cannot silently measure nothing.
// =0 restores the old path exactly: macapk_mcpe_probe_env() is then std::getenv().
// KEPT, not in the family: MACAPK_CENSUS_SITES (minus its MCPE label branches),
// MACAPK_TRACE_GUEST_LIB/OFFS, MACAPK_WATCH_GUEST_* -- all generic, env-only, no per-app
// string or vaddr in source.
// REMOVAL CONDITION: delete the whole family, this gate, and the =0 arm once the owner has
// answered whether the mod-menus 1.26.40.27 repack (lib 6c199c, the only build 17 of these
// vaddrs still arm on) will be re-obtained for the v625 bisect, and one protected sweep plus
// one installed-stub Minecraft cold launch have run on this gate. Deletion map (moves
// first: g_pairip_disp_ctr, macapk_cd_rd64/macapk_cd_read_stdstring, macapk_thread_is_host):
// docs/mcpe-signin-probe-family-retired_2026-09-27.md. Offline gate:
// debug/geh_mcpe-signaling-probe-and-pump-family_gate.sh.
// Defined here (not in bionic_shim.cpp) so this file builds without the shim-side change.
extern "C" bool __macapk_mcpe_signin_probes_retired() {
    // Plain atomic, no magic static: bionic_shim.cpp reads this from a JVMTI callback,
    // and a racing first read just parses the same env twice.
    static std::atomic<int> s_retired{-1};
    int v = s_retired.load(std::memory_order_relaxed);
    if (v < 0) {
        const char* e = std::getenv("MACAPK_MCPE_SIGNIN_PROBES_RETIRED");
        v = (e && *e == '0') ? 0 : 1;
        s_retired.store(v, std::memory_order_relaxed);
    }
    return v != 0;
}
// std::getenv() for a family member: NULL while the family is retired (with one line per
// variable per process when it was set), the real value under =0.
static const char* macapk_mcpe_probe_env(const char* name) {
    const char* val = std::getenv(name);
    if (!val || !__macapk_mcpe_signin_probes_retired()) return val;
    static std::mutex s_mu;
    static std::set<std::string>* s_warned = new std::set<std::string>();
    std::lock_guard<std::mutex> g(s_mu);
    if (s_warned->insert(name).second) {
        std::fprintf(stderr,
            "[mcpe-probes] %s=%s IGNORED: the MCPE sign-in probe family is retired "
            "(MACAPK_MCPE_SIGNIN_PROBES_RETIRED=0 re-arms it; "
            "docs/mcpe-signin-probe-family-retired_2026-09-27.md)\n", name, val);
        std::fflush(stderr);
    }
    return nullptr;
}

// OR-in TPIDR_EL0 bit 48 (MACHDEP_TPIDR_FLAG_PRESERVE_X18) so XNU restores
// x18 across every EL0 exception return for this thread. Requires the
// com.apple.security.custom-x18-abi-toggle entitlement (else XNU wipes the
// bit on the next context switch -- proven). Guarded so re-entry is free.
// OR (never overwrite) preserves TPIDR's low cpu/cluster bits that XNU
// manages. This is the non-deprecated macOS-26.4 successor to the sdk<13
// LC_BUILD_VERSION gate; see docs/x18-custom-abi-toggle-entitlement_2026-07-14.md.
#if defined(__APPLE__) && defined(__aarch64__)
static inline void macapk_enable_x18_preservation(void) {
    uint64_t t; __asm__ volatile("mrs %0, TPIDR_EL0" : "=r"(t));
    if (!(t & 0x0001000000000000ULL)) {
        t |= 0x0001000000000000ULL;
        __asm__ volatile("msr TPIDR_EL0, %0" :: "r"(t));
    }
}
#else
static inline void macapk_enable_x18_preservation(void) {}
#endif

// ---------------------------------------------------------------------------
// x18 CAPABILITY GATE (v1.0.1021). Owner set macOS 26.4 as the minimum.
//
// Why a runtime probe and not just LSMinimumSystemVersion=26.4: that plist key
// gates LaunchServices ONLY, and NEITHER real path goes through LaunchServices
// -- the launcher spawns the runtime with Process(), the per-app stub execv()s
// it, and every debug/ harness runs the bare binary. The plist is honesty; this
// is the enforcement.
//
// And a version compare alone would not be enough even so, because the
// entitlement can be MISSING ON A HOST ABOVE THE FLOOR: build.sh's codesign
// falls back to an unentitled ad-hoc sign on failure, and a resign with the
// wrong plist has already shipped once (v1.0.1017). A capability probe catches
// every one of those; a version check catches none of them.
//
// What "not active" costs if we let it through: XNU zeroes x18 on EVERY return
// to EL0 -- syscalls AND async preemption -- so guest Bionic code, which uses
// x18 as its TLS base, reads a null TLS pointer at an unpredictable point.
// That is silent memory corruption, not a clean crash.
#if defined(__APPLE__) && defined(__aarch64__)
// Naked so no prologue can touch x18. clang never allocates x18 (it is
// platform-reserved), and these leaf bodies need no frame. Technique ported
// verbatim from debug/x18_entitlement_probe.c, which is the proven measurement.
__attribute__((naked)) static void macapk_x18_probe_set(uint64_t v) {
    __asm__ volatile("mov x18, x0\n\tret\n");
}
__attribute__((naked)) static uint64_t macapk_x18_probe_get(void) {
    __asm__ volatile("mov x0, x18\n\tret\n");
}

// ---- Guest Bionic TLS block in x18 (MACAPK_GUEST_TLS) ---------------------
//
// Android's arm64 ABI reserves x18 as BIONIC'S TLS BASE: on a real device it holds a
// pointer to the thread's TLS slot array. We never set it. Worse, the capability probe
// above leaves its 0xdeadbeefcafebabe SENTINEL in x18 and nothing clears it, so guest
// code that reads x18 DIRECTLY (rather than through a stack-guard load the neutralization
// pass rewrites) sees poison where a device has a thread pointer.
//
// MEASURED 2026-09-11: pairip's outer VM reads x18. Dumping all 29 registers at outer
// state o=25970 on a device and on us, 26 of 29 MATCH; the only non-stack difference is
// x18 (ours 0xdeadbeefcafebabe, device 0x7772774000), and the very next outer state is
// where the cursor pc diverges (ours 0x3d130, device 0x3c5f8) -- the first divergence in
// the whole outer stream. So a poisoned x18 is a live platform-fidelity defect, not a
// cosmetic one.
//
// Give each guest thread a real, Bionic-shaped TLS block. Slot numbering per
// bionic/libc/platform/bionic/tls_defines.h: 1 = TLS_SLOT_THREAD_ID,
// 5 = TLS_SLOT_STACK_GUARD (which is the x18+0x28 the neutralization pass already cites).
// This is a faithful-platform-surface fix, app-agnostic: every Bionic guest expects a real
// x18. Gated while it is measured; the sentinel is the status quo it replaces.
extern "C" unsigned long __stack_chk_guard;
static void macapk_x18_install_guest_tls() {
    static const bool s_on = [] {
        const char* e = std::getenv("MACAPK_GUEST_TLS");
        return !(e && e[0] == '0');          // default ON; MACAPK_GUEST_TLS=0 restores the sentinel
    }();
    if (!s_on) return;
    static thread_local void** s_tls = nullptr;
    if (!s_tls) {
        void** blk = static_cast<void**>(std::calloc(64, sizeof(void*)));
        if (!blk) return;
        blk[1] = reinterpret_cast<void*>(pthread_self());        // TLS_SLOT_THREAD_ID
        blk[5] = reinterpret_cast<void*>(__stack_chk_guard);     // TLS_SLOT_STACK_GUARD (x18+0x28)
        s_tls = blk;
    }
    macapk_x18_probe_set(reinterpret_cast<uint64_t>(s_tls));     // set x18 LAST
}

// true iff guest x18 will actually survive on this host. Memoized: the probe
// costs a few ms and the answer cannot change within a process.
static bool macapk_x18_capability_ok() {
    static int memo = -1;
    if (memo >= 0) return memo != 0;

    // (a) Is the kernel even capable? os_set_custom_x18_abi_enabled is
    //     API_AVAILABLE(macos(26.4)); its absence is a definitive "OS too old"
    //     with no timing sensitivity and no false-fail risk. We only test for
    //     the SYMBOL -- we NEVER call it, and that is deliberate rather than
    //     cautious. Apple's API puts the thread into a mode where x18 is a
    //     plain GPR and macOS is OFF-LIMITS: "code running after
    //     os_set_custom_x18_abi_enabled(true) must not call, directly or in the
    //     form of callbacks, into any macOS library/framework code in the
    //     current thread". Our Bionic shim calls libSystem on nearly every
    //     guest operation, so we could never stay in that mode. It also aborts
    //     on a non-toggling call, and requires signal handlers to bracket their
    //     calls -- we install a SIGILL handler for `mrs Sys, Xt`.
    //     The TPIDR_EL0 bit-48 route asks the kernel to PRESERVE x18 across
    //     context switches while we keep calling libSystem freely. The
    //     entitlement gates both; only the bit suits a compatibility layer.
    //     Do not "modernise" this onto the API.
    const bool have_api =
        dlsym(RTLD_DEFAULT, "os_set_custom_x18_abi_enabled") != nullptr;

    // (b) Does it actually work HERE? Catches a stripped/absent entitlement on
    //     an otherwise new-enough host, which (a) cannot see. usleep() blocks,
    //     so the thread is genuinely descheduled and really does take the EL0
    //     return path we care about; a few rounds is plenty to catch a wipe.
    bool survives = false;
    if (have_api) {
        macapk_enable_x18_preservation();
        static const uint64_t kSentinel = 0xdeadbeefcafebabeULL;
        survives = true;
        for (int i = 0; i < 8 && survives; ++i) {
            macapk_x18_probe_set(kSentinel);
            usleep(1200);
            if (macapk_x18_probe_get() != kSentinel) survives = false;
        }
    }

    const bool ok = have_api && survives;
    if (!ok) {
        std::fprintf(stderr,
            "[x18] FATAL: x18 preservation is NOT ACTIVE on this host "
            "(os_set_custom_x18_abi_enabled=%s, probe=%s).\n"
            "[x18]   arm64 guest code would silently corrupt memory: XNU zeroes "
            "x18 on every return to userspace,\n"
            "[x18]   and Bionic uses it as the TLS base.\n"
            "[x18]   Requires macOS 26.4+ AND the "
            "com.apple.security.custom-x18-abi-toggle entitlement.\n"
            "[x18]   Refusing to load arm64 guest code. Override at your own "
            "risk with MACAPK_ALLOW_UNSAFE_X18=1.\n",
            have_api ? "present" : "ABSENT (macOS < 26.4)",
            have_api ? (survives ? "pass" : "CLOBBERED") : "not run");
    }
    memo = ok ? 1 : 0;
    return ok;
}
#else
static bool macapk_x18_capability_ok() { return true; }
#endif

namespace {

// --- ELF64 type definitions (AArch64) -----------------------------------
// Spec: System V Application Binary Interface, AArch64 Processor
// Supplement (https://github.com/ARM-software/abi-aa).

struct Elf64_Ehdr {
    uint8_t  e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint64_t e_entry;
    uint64_t e_phoff;
    uint64_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
};

struct Elf64_Phdr {
    uint32_t p_type;
    uint32_t p_flags;
    uint64_t p_offset;
    uint64_t p_vaddr;
    uint64_t p_paddr;
    uint64_t p_filesz;
    uint64_t p_memsz;
    uint64_t p_align;
};

struct Elf64_Dyn {
    int64_t  d_tag;
    uint64_t d_val;   // also d_ptr; union in the spec
};

// Section header (System V gABI, "Sections", Elf64_Shdr). Read ONLY to learn
// which byte ranges of the PF_X segments are SHF_EXECINSTR sections and which
// are non-executable SHF_ALLOC ones, so the .text rewrite passes can tell an
// instruction from data that happens to live in the same R+X PT_LOAD (see
// Image::read_exec_sections, MACAPK_SVC_BRK_EXEC_ONLY). The loader itself
// still maps by program headers, exactly as Bionic's linker does.
struct Elf64_Shdr {
    uint32_t sh_name;
    uint32_t sh_type;
    uint64_t sh_flags;
    uint64_t sh_addr;
    uint64_t sh_offset;
    uint64_t sh_size;
    uint32_t sh_link;
    uint32_t sh_info;
    uint64_t sh_addralign;
    uint64_t sh_entsize;
};
static_assert(sizeof(Elf64_Shdr) == 64, "gABI Elf64_Shdr is 64 bytes");
constexpr uint32_t kShtNobits     = 8;     // SHT_NOBITS: occupies no file bytes
constexpr uint64_t kShfAlloc      = 0x2;   // SHF_ALLOC
constexpr uint64_t kShfExecinstr  = 0x4;   // SHF_EXECINSTR

struct Elf64_Sym {
    uint32_t st_name;
    uint8_t  st_info;
    uint8_t  st_other;
    uint16_t st_shndx;
    uint64_t st_value;
    uint64_t st_size;
};

struct Elf64_Rela {
    uint64_t r_offset;
    uint64_t r_info;
    int64_t  r_addend;
};

// Packed relative-relocation entry. Bionic libc/include/elf.h spells it
// `typedef Elf64_Xword Elf64_Relr;` -- a bare 64-bit word that is either an
// address or a bitmap, discriminated by its low bit. See relocate_relr() in
// apply_relocations() for the decoding.
typedef uint64_t Elf64_Relr;

constexpr uint16_t ET_DYN     = 3;
constexpr uint16_t EM_AARCH64 = 183;

constexpr uint32_t PT_LOAD       = 1;
constexpr uint32_t PT_DYNAMIC    = 2;
// elfexec: PT_PHDR describes where the program-header table lands inside the
// image's own mapping -- the value Linux reports as AT_PHDR. (PT_INTERP is
// deliberately NOT consulted anywhere: pre-r10 NDK toolchains emitted it into
// shared libraries too, so it does not identify an executable. See
// runtime/src/apk_native_exec.cpp for the measured evidence.)
constexpr uint32_t PT_PHDR       = 6;
constexpr uint32_t PT_TLS        = 7;
constexpr uint32_t PT_GNU_RELRO  = 0x6474e552;

constexpr uint32_t PF_X = 1;
constexpr uint32_t PF_W = 2;
constexpr uint32_t PF_R = 4;

constexpr int64_t DT_NULL          = 0;
constexpr int64_t DT_NEEDED        = 1;
constexpr int64_t DT_HASH          = 4;
constexpr int64_t DT_PLTRELSZ      = 2;
constexpr int64_t DT_STRTAB        = 5;
constexpr int64_t DT_SYMTAB        = 6;
constexpr int64_t DT_RELA          = 7;
constexpr int64_t DT_RELASZ        = 8;
constexpr int64_t DT_STRSZ         = 10;
constexpr int64_t DT_JMPREL        = 23;
// t3-syms (2026-07-24): DT_VERSYM, the per-symbol version-index array
// (parallel to .dynsym; one Elf64_Half per symbol). Needed so the
// on-demand GNU_HASH lookup can mirror bionic's unversioned-dlsym
// behaviour: skip a symbol whose versym entry has the "hidden" bit set
// (0x8000). Value confirmed against glibc/LLVM's elf.h DT_VERSYM
// (0x6ffffff0) and bionic linker/linker_relocate.h kVersymHiddenBit.
constexpr int64_t DT_VERSYM        = 0x6ffffff0;
constexpr int64_t DT_INIT_ARRAY    = 25;
constexpr int64_t DT_FINI_ARRAY    = 26;
constexpr int64_t DT_INIT_ARRAYSZ  = 27;
constexpr int64_t DT_FINI_ARRAYSZ  = 28;
// DT_INIT (12) / DT_FINI (13): the legacy single .init / .fini functions.
// Bionic's soinfo::call_constructors() runs DT_INIT *before* the
// DT_INIT_ARRAY constructors, and call_destructors() runs DT_FINI *after*
// the DT_FINI_ARRAY destructors (bionic/linker/linker_soinfo.cpp). This
// loader used to parse only the *_ARRAY tags and silently drop DT_INIT/
// DT_FINI, which is fatal for any image whose .init does work the array
// constructors depend on -- e.g. libmaesdk.so (Play Minecraft's Microsoft
// SDK), whose .init is a GOT-fixup routine that resolves ~189 scattered
// .got.plt slots left pointing at .plt[0]; skipping it makes the first
// constructor that calls one of those PLT stubs branch to the un-based
// .plt[0] and SIGSEGV.
constexpr int64_t DT_INIT           = 12;
constexpr int64_t DT_FINI           = 13;
constexpr int64_t DT_GNU_HASH      = 0x6ffffef5;
// Packed relative relocations. The standard generic-ABI tag numbers are
// confirmed from LLVM llvm/include/llvm/BinaryFormat/DynamicTags.def:
//   DYNAMIC_TAG(RELRSZ, 35) / DYNAMIC_TAG(RELR, 36) / DYNAMIC_TAG(RELRENT, 37)
// The 0x6fffe00x spellings are Android's pre-standard version of the same
// table, emitted by `-Wl,--pack-dyn-relocs=android+relr`; bionic's
// libc/include/elf.h defines DT_ANDROID_RELR 0x6fffe000 / DT_ANDROID_RELRSZ
// 0x6fffe001 / DT_ANDROID_RELRENT 0x6fffe003 and bionic's linker decodes both
// spellings with the same routine, so we accept both here too.
constexpr int64_t DT_RELRSZ          = 35;
constexpr int64_t DT_RELR            = 36;
constexpr int64_t DT_RELRENT         = 37;
constexpr int64_t DT_ANDROID_RELR    = 0x6fffe000;
constexpr int64_t DT_ANDROID_RELRSZ  = 0x6fffe001;
constexpr int64_t DT_ANDROID_RELRENT = 0x6fffe003;

// DT_ANDROID_REL/RELA/RELSZ/RELASZ: the OLDER "APS2" SLEB128-packed
// relocation format (predates DT_RELR above; some link configurations still
// emit it). DT_LOOS-relative tags per bionic's libc/include/elf.h (fetched
// 2026-07-31, https://android.googlesource.com/platform/bionic/+/main/
// libc/include/elf.h): `#define DT_ANDROID_REL 0x6000000f // DT_LOOS + 2`,
// `DT_ANDROID_RELSZ 0x60000010`, `DT_ANDROID_RELA 0x60000011 // DT_LOOS + 4`,
// `DT_ANDROID_RELASZ 0x60000012`. Only the RELA pair is decoded below --
// Stage-6 is arm64-only (always USE_RELA, per AOSP's own linker.cpp
// #if defined(USE_RELA) split in linker_relocate.cpp), so DT_ANDROID_REL/
// RELSZ (the non-addend REL variant) can never legally appear in a Stage-6
// input and are intentionally not handled -- see the DT_ANDROID_REL/RELSZ
// AOSP linker.cpp cases, which themselves DL_ERR "unsupported" on an arm64
// (USE_RELA) linker for exactly this reason.
constexpr int64_t DT_ANDROID_RELA    = 0x60000011;
constexpr int64_t DT_ANDROID_RELASZ  = 0x60000012;

constexpr uint32_t R_AARCH64_NONE      = 0;
constexpr uint32_t R_AARCH64_ABS64     = 257;
constexpr uint32_t R_AARCH64_GLOB_DAT  = 1025;
constexpr uint32_t R_AARCH64_JUMP_SLOT = 1026;
constexpr uint32_t R_AARCH64_RELATIVE  = 1027;
// STT_GNU_IFUNC indirect relocation. Value confirmed = 1032 from
// glibc elf.h and the AAELF64 dynamic-reloc range (dynamic codes
// begin at 1024). The addend is the image-relative offset of a
// resolver function; the linker calls resolver() and stores its
// return value at the reloc offset. See apply_one()'s case below.
constexpr uint32_t R_AARCH64_IRELATIVE = 1032;

// TLS + COPY dynamic relocations. Values verified against LLVM
// llvm/include/llvm/BinaryFormat/ELFRelocs/AArch64.def (COPY=0x400,
// DTPMOD64=0x404, DTPREL64=0x405, TPREL64=0x406, TLSDESC=0x407) and the
// value formulas against Bionic linker/linker_relocate.cpp (R_GENERIC_TLS_*
// cases). See docs/arm64-tls-relocs-contract_2026-06-21.md. General-dynamic
// (DTPMOD/DTPREL + __tls_get_addr) is thread-pointer-INDEPENDENT and is
// served here; initial-exec (TPREL) and TLSDESC are thread-pointer-relative
// and unsupported on macOS (XNU overwrites TPIDR_EL0 every context switch),
// pending the gated mrs/tp-add rewrite follow-up.
constexpr uint32_t R_AARCH64_COPY         = 1024;  // 0x400
constexpr uint32_t R_AARCH64_TLS_DTPMOD64 = 1028;  // 0x404
constexpr uint32_t R_AARCH64_TLS_DTPREL64 = 1029;  // 0x405
constexpr uint32_t R_AARCH64_TLS_TPREL64  = 1030;  // 0x406
constexpr uint32_t R_AARCH64_TLSDESC      = 1031;  // 0x407

// Defined in bionic_shim.cpp (same dylib). Registers an image's PT_TLS
// template with the per-thread TLS allocator and returns a 1-based module
// id consumed by the DTPMOD64 reloc. See the TLS block in bionic_shim.cpp.
extern "C" size_t __macapk_arm64_tls_register_module(
    const void* init_ptr, size_t init_size, size_t mem_size, size_t align);

// TLSDESC resolver (bionic_shim.cpp, naked asm); installed as the descriptor
// word0 by the gated R_AARCH64_TLSDESC reloc. Returns the ABSOLUTE per-thread
// TLS address; the gated mrs->mov#0 rewrite forces the guest's tp to 0 so
// `tp + x0` == that address. See docs/arm64-tls-relocs-contract_2026-06-21.md.
extern "C" void __macapk_arm64_tlsdesc_resolver(void);

// TLSDESC support (the descriptor reloc + the mrs->mov#0 tp-zeroing rewrite).
// DEFAULT ON since v1.0.962; MACAPK_TLS_DESC=0 is the bisection escape hatch.
//
// It was default OFF from v1.0.479 with the stated condition "flip once a real
// TLSDESC app is reachable AT RUNTIME to validate" (duolingo's rive/rlottie
// libs existed but duolingo never got far enough to load them). That condition
// is now discharged: duolingo reaches them, and the OFF path is NOT the
// "graceful stub fallback" the old comment claimed. Measured 2026-08-12 on
// com.duolingo 6.82.3:
//
//   OFF: Rive.init -> System.loadLibrary("rive-android") -> the R_AARCH64_TLSDESC
//        relocs are skipped, so descriptor word0 (the resolver function pointer)
//        stays 0 -> the guest's `blr` branches to 0 -> SIGSEGV pc=0x0
//        (si_code SEGV_ACCERR, si_addr 0x0) inside JNI_OnLoad. Process DEAD,
//        never reaches onResume. This is the app's whole intro animation path.
//   ON:  librive-android.so loads, librlottie.so "tp-zero rewrote 185 mrs
//        TPIDR_EL0 site(s)", LaunchActivity RESUMES, window focus delivered,
//        1800+ frames presented, process alive.
//
// Blast radius is narrow by construction: the reloc case only fires for images
// that actually carry R_AARCH64_TLSDESC, and the guest .text rewrite is further
// guarded by `image_has_tlsdesc_ && !image_has_tprel_`. An image without
// TLSDESC is byte-for-byte unaffected either way, and an image WITH it cannot
// have been relying on the OFF behaviour, because OFF fails its load.
static const bool MACAPK_TLS_DESC_ENABLED = [] {
    const char* v = std::getenv("MACAPK_TLS_DESC");
    if (!v) return true;
    return !(std::strcmp(v, "0") == 0 || std::strcmp(v, "false") == 0);
}();

// st_info layout: high 4 bits = binding, low 4 bits = type.
constexpr uint8_t STB_WEAK   = 2;

// AArch64 reloc r_info layout: high 32 bits = symbol index,
// low 32 bits = reloc type.
inline uint32_t reloc_sym(uint64_t r_info)  { return uint32_t(r_info >> 32); }
inline uint32_t reloc_type(uint64_t r_info) { return uint32_t(r_info & 0xffffffff); }
inline uint8_t  sym_bind(uint8_t st_info)   { return uint8_t(st_info >> 4); }

// --- Loaded image -------------------------------------------------------

class Image {
public:
    bool load(const uint8_t* blob, size_t blob_size, const char* dbg_name);
    // t3-mmap (2026-07-24): "Lever 3" -- load directly from the on-disk
    // source .so, no embedded blob at all. `so_path_rel` is a path
    // fragment relative to the app's INSTALL dir (v1.0.1588:
    // "lib/arm64/libminecraftpe.so"; a pre-1588 wrapper carries
    // "_apkt/lib/arm64-v8a/libminecraftpe.so"), exactly as baked into the
    // wrapper by arm64_pack.cpp at translation time; resolved to an
    // absolute path here via android_root + the package name (both shapes
    // land on the installed file, see macapk_resolve_relative_so_path), so
    // moving --android-root between launches does not break a cached
    // wrapper. See load_common() for the (unchanged) shared tail this and
    // load() both run.
    bool load_from_path(const char* so_path_rel, const char* dbg_name);
    void* lookup(const char* name) const;
    ~Image();

private:
    const char* dbg_name_ = "";

    // The PT_LOAD bytes live in this contiguous mapping.
    uint8_t* base_ = nullptr;
    size_t   mapped_size_ = 0;

    // The mmap we actually own, which can be LARGER than the image span:
    // choose_rx_rw_bias_shift() may push the image forward by up to one host
    // page so the RX|RW permission boundary lands on a host page boundary
    // (see the comment there). base_ + mapped_size_ must keep meaning "one
    // past the last image byte" -- a dozen call sites use it as an mmap hint
    // and as an is-this-address-in-the-image range check -- so the shift is
    // NOT folded into mapped_size_; it is recorded here purely so the
    // destructor unmaps everything it reserved instead of leaking the pad.
    void*  map_start_ = nullptr;
    size_t map_len_   = 0;

public:
    // Range accessors for callers (the SEGV handler) that need to
    // distinguish arm64-native loaded ELF addresses from JVM JIT
    // code or other anonymous mmaps -- dladdr returns 0 for ALL of
    // those equally, so it can't be used alone to gate recovery.
    const uint8_t* base() const { return base_; }
    size_t mapped_size() const  { return mapped_size_; }

    // Program-header view for dl_iterate_phdr(). The loader copies the
    // full e_phnum-entry table out of the source blob (PT_LOAD AND
    // PT_GNU_EH_FRAME / PT_DYNAMIC / etc.), so the guest's statically-
    // linked libunwind can recover .eh_frame via the PT_GNU_EH_FRAME
    // entry. base_ is the unwinder's dlpi_addr: base_ + p_vaddr is the
    // runtime address of any segment, which is exactly what
    // findUnwindSectionsByPhdr computes (image_base = dlpi_addr).
    const Elf64_Phdr* phdrs_data() const { return phdrs_.data(); }
    size_t phdr_count() const   { return phdrs_.size(); }
    const char* name() const    { return dbg_name_; }
    const std::vector<std::string>& needed() const { return needed_; }
    // Guest dladdr (MACAPK_GUEST_DLADDR): bionic soinfo::find_symbol_by_address.
    // Returns the defined dynsym CONTAINING `addr` (not the nearest one below
    // it), or nullptr; `*name_out` gets its .dynstr name. See the definition.
    const Elf64_Sym* find_symbol_by_address(uintptr_t addr,
                                            const char** name_out) const;
    // Guest dladdr's dli_fname for this image (the maps-name realpath), interned
    // when the image registers and refreshed when the Java side pushes a new
    // native-library dir, so __macapk_arm64_native_dladdr only LOADS a pointer.
    // It runs inside crash reporters' SIGSEGV/SIGABRT handlers (Crashpad,
    // applovin-native-crash-reporter, sentry, Unity), where building a string,
    // taking a mutex or calling malloc can deadlock against the very thread the
    // signal interrupted. Bionic's do_dladdr does not allocate either.
    std::atomic<const char*> dladdr_fname{nullptr};
    // True if this image is a pairip import-protected library (its imports' GOT
    // slots are stripped of relocs and resolved at load by the pairip VM, which is
    // unfillable on our non-ART process -- so its guest init + JNI_OnLoad would
    // SIGSEGV branching through an un-based .plt[0]). STRUCTURAL predicate (count of
    // reloc-less .got.plt holes), never a library name. Gated on
    // MACAPK_PAIRIP_GATE_EMU. Result cached in pairip_protected_. Public because
    // __macapk_arm64_native_dlsym (a free function) consults it to neuter JNI_OnLoad.
    bool is_pairip_import_protected() const;

    // Fill this pairip-import-protected lib's reloc-less .got.plt holes from a harvested
    // import table (MACAPK_PAIRIP_IMPORT_TABLE=<path>) instead of neutering the lib. The
    // import NAMES were harvested from a live device/emulator run (the pairip VM resolves
    // them there; they are STATICALLY undecodable -- 181/189 VM-only). We resolve each the
    // SAME way as any other import (resolve_external) and write it into the guest slot, so
    // the lib's init + JNI_OnLoad can run for real. Gated; unset table => current neutering
    // behaviour is unchanged. Returns the count filled. Per-image DATA (this lib build),
    // general MECHANISM; retire when the generic VM interpreter lands.
    int fill_pairip_import_holes();
    bool pairip_imports_filled() const { return pairip_imports_filled_ == 1; }

    // Overwrite pairip-VM-ENCRYPTED .text segments of this lib with their DECRYPTED bytes,
    // harvested from a live device/emulator run (the pairip VM decrypts them there; the
    // on-disk bytes are ciphertext -- executing them SIGILLs on garbage). Files live in
    // MACAPK_PAIRIP_CODE_PATCH_DIR, named "<soname>__0x<vaddr>.bin"; each is memcpy'd to
    // base_+vaddr. Run after fill_pairip_import_holes() and BEFORE apply_segment_protections()
    // (the .text is still writable then). Gated; unset dir => no-op. Per-image DATA (this lib
    // build), general MECHANISM; retire when the generic VM interpreter decrypts code itself.
    int apply_pairip_code_patches();

    // elfexec (2026-07-29): the image's ELF entry point, biased into the
    // mapping (nullptr when e_entry is 0, i.e. a plain shared library).
    // Only the APK-shipped-EXECUTABLE path reads this: an app that forks a
    // helper binary out of lib/<abi>/ (Sgt Puzzles' libpuzzlesgen.so) has no
    // exported symbol to call, so the ONLY way in is e_entry -- bionic's
    // crtbegin `_start`, which then tail-calls __libc_init with x0 = the
    // initial stack pointer. See docs/apk-native-executables_2026-07-29.md.
    const uint8_t* entry() const {
        return e_entry_ ? base_ + e_entry_ : nullptr;
    }
    // DT_FINI_ARRAY, for the exec path's atexit hook. On real Android the
    // executable's .fini_array is registered by __libc_init out of the
    // structors_array_t that crtbegin passes -- but that struct's shape has
    // changed across NDK releases (3 pointers in r2x, 3 pointers + 3 counts
    // in current AOSP) and the callee cannot tell which one it was handed.
    // The linker-side view (DT_FINI_ARRAY / DT_FINI_ARRAYSZ, which we already
    // parse for every image) is version-independent and identifies the same
    // functions, so the exec path uses it instead of trusting the argument.
    // Reverse order, matching bionic's call_fini_array() ("Call fini functions
    // in reverse order", bionic/libc/arch-common/bionic/crtbegin.c).
    void run_fini_array() {
        if (fini_array_) {
            for (size_t i = fini_array_count_; i-- > 0; ) {
                // ALREADY ABSOLUTE runtime addresses, exactly as in
                // run_init_array(): the static linker emits them into the file
                // image and R_AARCH64_RELATIVE has rewritten them by now.
                // Adding base_ here would double-bias and branch into nowhere.
                uint64_t addr = fini_array_[i];
                if (!addr || addr == uint64_t(-1)) continue;   // bionic sentinels
                reinterpret_cast<void (*)()>(addr)();
            }
        }
        fini_array_count_ = 0;    // idempotent: never run twice
        // DT_FINI runs AFTER the fini_array, mirroring bionic's
        // call_destructors(): call_array("DT_FINI_ARRAY", ..., reverse) then
        // call_function("DT_FINI", fini_func_). Unlike the array contents,
        // fini_func_ is a load-biased link-time offset (see the member decl).
        if (fini_func_ && fini_func_ != ~uint64_t(0)) {
            reinterpret_cast<void (*)(void)>(fini_func_)();
            fini_func_ = 0;       // idempotent: never run twice
        }
    }
private:
    // Raw (unbiased) e_entry as recorded in the ELF header. Captured by both
    // load() and load_from_path() right after macapk_validate_ehdr().
    uint64_t e_entry_ = 0;

    // Page geometry.
    size_t   page_size_ = 16384;  // Apple Silicon default

    // ELF tables (pointers into the mapped image).
    const Elf64_Sym* symtab_     = nullptr;
    const char*      strtab_     = nullptr;
    size_t           strtab_size_ = 0;
    // DT_NEEDED basenames, in table order. Only the weak-import scope in
    // resolve_external reads them (see weak_scope_lookup).
    std::vector<std::string> needed_;
    // v1.0.555: DT_GNU_HASH base, used to compute the EXACT dynsym count.
    // The strtab-proximity heuristic in build_export_map mis-terminates on
    // libs whose symtab is not immediately followed by the strtab and whose
    // symbol names cluster near the strtab end (Minecraft's libminecraftpe.so:
    // symtab at vaddr 0x13f980c0, strtab far earlier at 0x2f3eb0, names at
    // ~8.3MB offsets) -> 0 exports indexed -> every export trampoline (incl.
    // JNI_OnLoad) branches through a 0 slot -> br 0 -> SIGSEGV pc=0 the moment
    // the JVM calls JNI_OnLoad. GNU_HASH gives the real symbol count.
    const uint32_t*  gnu_hash_   = nullptr;
    // t3-syms (2026-07-24): DT_HASH (classic SysV hash table), parsed so
    // Image::sysv_hash_lookup() has a real on-demand path for any guest
    // .so that ships ONLY a SysV hash and no DT_GNU_HASH (bionic's own
    // soinfo::find_symbol_by_name dispatches the same way: GNU_HASH wins
    // when present, DT_HASH is the fallback). Empirically, every Stage-6
    // guest .so surveyed for this mission (43 libs across MCPE/Subway/
    // Crossy/Among Us/OpenGD) DOES carry DT_GNU_HASH, so this field is a
    // defensive completeness path, not something exercised by current
    // apps -- see docs/efficiency-native-runtime-memory_2026-07-24.md
    // follow-up notes.
    const uint32_t*  sysv_hash_  = nullptr;
    // t3-syms: DT_VERSYM, parallel per-symbol version-index array (see
    // the DT_VERSYM constant comment for the citation). nullptr when the
    // image carries no symbol-versioning section.
    const uint16_t*  versym_     = nullptr;

    // Per-image trampoline page. mmap'd RWX after main mapping, used
    // to host code that gets `b`-branched to from inline patches when
    // the original instruction sequence can't be replaced in place.
    uint8_t*         trampoline_   = nullptr;
    size_t           trampoline_used_ = 0;

    // Per-image x18 detour-thunk pool. The generic x18 rewrite (below) lowers
    // the "nofree" webs -- functions where x18 is live simultaneously with
    // every other GPR, so no register-rename target exists -- by replacing each
    // x18 instruction in place with a 4-byte `b <thunk>`; the thunk homes x18's
    // value in an unused callee-saved SIMD register (XNU never zeroes SIMD, and
    // d8..d15 survive calls) and runs the original op with a stack-free scratch
    // GPR (saved in a second SIMD reg). Made RX in apply_segment_protections,
    // munmap'd in ~Image.
    uint8_t*         x18_thunks_       = nullptr;
    size_t           x18_thunks_used_  = 0;
    size_t           x18_thunks_cap_   = 0;

    // Go c-shared runtime TLS detour thunks (apply_go_tls_rewrite). Made RX by
    // that pass itself, munmap'd in ~Image.
    uint8_t*         go_tls_thunks_     = nullptr;
    size_t           go_tls_thunks_cap_ = 0;

    const Elf64_Rela* rela_       = nullptr;
    size_t            rela_count_ = 0;

    const Elf64_Rela* pltrel_     = nullptr;
    size_t            pltrel_count_ = 0;

    // Tri-state cache for is_pairip_import_protected() (-1 unknown, 0 no, 1 yes).
    // Computed once (the reloc-less .got.plt hole count is a load-time invariant of
    // the image), consumed by BOTH run_init_array() (skip the lib's guest init) and
    // native_dlsym() (neuter the lib's JNI_OnLoad, which the JVM calls AFTER the
    // loader returns). See is_pairip_import_protected().
    mutable int       pairip_protected_ = -1;

    // The lib's link-time .plt[0] (== the value EVERY reloc-less pairip hole holds before we
    // fill it). Computed as the dominant <4GB 16-aligned value bucket in is_pairip_import_
    // protected(); reused by fill_pairip_import_holes()'s trap-all so it traps ONLY genuine
    // holes (value == plt0) and never a .data.rel.ro/.data CONSTANT that merely happens to be
    // a <4GB 16-aligned word (libmaesdk has data words 0x7fff0000/0x40000000/0x20000/0x40000
    // in the pltrel span -- trapping those corrupts real data and SIGSEGVs).
    mutable uint64_t  pairip_plt0_ = 0;

    // Tri-state cache for fill_pairip_import_holes() (-1 unfilled, 0 table-absent/failed,
    // 1 holes filled from the harvested table). When 1, run_init_array() and native_dlsym()
    // let the lib's init + JNI_OnLoad RUN instead of neutering.
    mutable int       pairip_imports_filled_ = -1;
    // True when the harvested table filled EVERY hole (0 unresolved, 0 trapped): the
    // lib's pairip resolver program then has no GOT slot left to contribute.
    mutable bool      pairip_imports_complete_ = false;
public:
    bool pairip_imports_complete() const { return pairip_imports_complete_; }
private:

    // DT_RELR / DT_ANDROID_RELR: the packed (bitmap-encoded) form of the same
    // R_AARCH64_RELATIVE fixups that would otherwise sit in .rela.dyn. Carries
    // no symbols and no explicit addends. Decoded in apply_relocations().
    const Elf64_Relr* relr_       = nullptr;
    size_t            relr_count_ = 0;

    // DT_ANDROID_RELA / DT_ANDROID_RELASZ: Android's older "APS2" packed
    // relocation format (SLEB128-encoded groups of Elf64_Rela; predates
    // DT_RELR, still emitted by some NDK/AGP link configurations, e.g. an
    // explicit `--pack-dyn-relocs=android` rather than `android+relr`, or an
    // older lld). Verified 2026-07-31 against
    // org.thoughtcrime.securesms's libsignal_jni.so: DT_ANDROID_RELA present
    // (0x60000011), classic DT_RELA absent -- so a loader that only
    // understands DT_RELA silently applies ZERO of the file's 14,578
    // R_AARCH64_RELATIVE relocations, leaving every .data.rel.ro-resident
    // relocated pointer (Rust static vtables included) as literal NULL. See
    // docs/android-packed-relocations-aps2_2026-07-31.md for the full AOSP
    // citations (bionic linker.cpp / linker_relocate.cpp /
    // linker_reloc_iterators.h / linker_sleb128.h) this port is taken from.
    //
    // Two-phase capture mirrors AOSP linker.cpp/linker_relocate.cpp exactly:
    // parse_dynamic() stores the RAW dynamic-tag pointer/size (pre magic-
    // check); apply_relocations() validates the "APS2" 4-byte magic and (on
    // success) points android_packed_relocs_/_size_ at the stream with the
    // magic stripped -- the same split AOSP uses (android_relocs_ /
    // android_relocs_size_ captured in the DT_ scan, validated later in
    // soinfo::relocate()).
    const uint8_t*    android_relocs_raw_         = nullptr;
    uint64_t          android_relocs_raw_size_    = 0;
    const uint8_t*    android_packed_relocs_      = nullptr;
    size_t            android_packed_relocs_size_ = 0;

    const uint64_t*  init_array_  = nullptr;
    size_t           init_array_count_ = 0;
    const uint64_t*  fini_array_  = nullptr;
    size_t           fini_array_count_ = 0;

    // DT_INIT / DT_FINI: the legacy single .init / .fini functions, run
    // before the init_array and after the fini_array respectively (bionic
    // soinfo::call_constructors / call_destructors). Unlike the array
    // *contents* (already rewritten to runtime addresses by
    // R_AARCH64_RELATIVE), the DT_INIT/DT_FINI d_ptr is a link-time offset
    // the loader biases by the load base (bionic: init_func_ = load_bias +
    // d_un.d_ptr). 0 = the image declares no such function.
    uint64_t         init_func_ = 0;
    uint64_t         fini_func_ = 0;

    // t3-syms (2026-07-24): RETIRED as the primary lookup() backing store.
    // This used to be an eager, permanently-resident name->address map
    // built once by build_export_map() for EVERY defined dynamic symbol --
    // for libminecraftpe.so (~90.5k symbols, avg name 91 bytes) that was
    // ~15-20 MB of pure duplication of data already sitting in the mapped
    // .dynstr/.dynsym, held for the whole app session (see
    // docs/efficiency-native-runtime-memory_2026-07-24.md Finding 1).
    // lookup() now hashes the QUERY on demand against the mapped
    // .gnu.hash/.dynsym/.dynstr (gnu_hash_lookup(), mirroring bionic's
    // soinfo::gnu_lookup()) or the mapped DT_HASH table (sysv_hash_lookup(),
    // mirroring soinfo::elf_lookup()), copying no names. This map now backs
    // ONLY the last-resort case of a guest image with NEITHER DT_GNU_HASH
    // NOR DT_HASH -- something no real Android/NDK linker output produces
    // (AOSP's own linker.cpp hard-fails such an image: "empty/missing
    // DT_HASH/DT_GNU_HASH"), kept purely so a malformed/exotic guest still
    // loads instead of hard-failing. See build_export_map()'s call site in
    // load() and the "no_hash_table_" flag below.
    std::unordered_map<std::string, void*> exports_fallback_;
    // True only when parse_dynamic() found neither DT_GNU_HASH nor
    // DT_HASH; gates lookup() onto the exports_fallback_ map above.
    bool no_hash_table_ = false;

    // Per-PT_LOAD record of (vaddr range, requested PROT_ bits). Used
    // by apply_segment_protections() to compute host-page-granularity
    // protections via a union pass -- necessary because Android .so
    // files are aligned to 4KB pages while Apple Silicon is 16KB, so a
    // single host page can contain pieces of two ELF segments with
    // different protection bits.
    struct Seg {
        uintptr_t vaddr;   // raw segment start in mapping address space
        size_t    memsz;   // segment length in bytes (un-aligned)
        int       prot;    // ELF-requested PROT_ flags
    };
    std::vector<Seg> segments_;
public:
    const std::vector<Seg>& segments() const { return segments_; }
private:

    // Deferred R_AARCH64_IRELATIVE relocations. Bionic runs ifunc
    // resolvers DURING relocation because it maps .text executable from
    // the start; our loader keeps the whole span PROT_READ|PROT_WRITE
    // until apply_segment_protections() flips the executable PT_LOADs to
    // RX at the very end of load(). A resolver lives in the guest .text,
    // so calling it before that flip would branch into a non-executable
    // page (SIGSEGV/SIGBUS). We therefore RECORD each IRELATIVE's
    // (resolver_addr, slot) during apply_relocations() and run them in
    // apply_ifunc_relocations() AFTER protections are applied. Each pair
    // is (address-of-resolver-function, GOT-slot-to-write). See the
    // R_AARCH64_IRELATIVE case in apply_relocations() for the citation.
    std::vector<std::pair<uint64_t, uint64_t*>> deferred_ifunc_;

    // .eh_frame FDEs registered with the host macOS libunwind via
    // __register_frame (so guest C++ exceptions unwind across our host/shim
    // frames instead of aborting). Deregistered in ~Image. See
    // register_eh_frames() + docs/arm64-cross-frame-eh-unwind_2026-06-21.md.
    std::vector<void*> registered_fdes_;

    // Program-header table copied out of the source blob during load(),
    // used by map_segments and parse_dynamic. Stored in-class so both
    // passes share the same view without re-reading file offsets.
    std::vector<Elf64_Phdr> phdrs_;

    // Section ranges [lo, hi) in FILE vaddrs, sorted and merged, read from the
    // section header table while the source bytes are still reachable
    // (read when MACAPK_SVC_BRK_EXEC_ONLY or MACAPK_X18_EXEC_ONLY is on). A PF_X
    // PT_LOAD also carries non-code sections (libminecraftpe.so: 268 of its 401
    // 0xD4000001 words are .gcc_except_table LSDA data), so the svc->BRK, MIDR and
    // x18 text passes SPARE a word only when it lies POSITIVELY inside a
    // non-executable SHF_ALLOC section (data_ranges_) and in no SHF_EXECINSTR one
    // (exec_ranges_); a word in no section at all is treated as code. Each pass
    // tests its OWN gate as well as the state. exec_ranges_state_: 0 = both gates
    // off / not read, 1 = usable, -1 = unusable (no or inconsistent section
    // headers), in which case the passes scan the whole segment as before and say
    // so.
    std::vector<std::pair<uint64_t, uint64_t>> exec_ranges_;
    std::vector<std::pair<uint64_t, uint64_t>> data_ranges_;
    int exec_ranges_state_ = 0;
    const char* exec_ranges_why_ = "not read";
    void read_exec_sections(const Elf64_Ehdr& eh, const uint8_t* blob,
                            uint64_t file_size, int fd);
    // 1 = spare (data), 0 = rewrite (code), and *nosec set when the word lies
    // in no section (rewritten, but counted and named in the load log).
    bool text_word_is_data(uint64_t file_vaddr, bool* nosec) const;
    // First FILE vaddr in [lo, hi) that text_word_is_data() calls data (a byte
    // positively inside a non-executable SHF_ALLOC section and in no executable
    // one), or `hi` when there is none. Used by the x18 rewrite to stop a
    // function window at the end of code (MACAPK_X18_EXEC_ONLY).
    uint64_t first_data_vaddr_in(uint64_t lo, uint64_t hi) const;

    // General-dynamic TLS: this image's registered TLS module id (1-based;
    // 0 = no PT_TLS). Set by parse_and_register_tls(); read by the
    // DTPMOD64 reloc case. See docs/arm64-tls-relocs-contract_2026-06-21.md.
    size_t tls_module_id_ = 0;

    // Set during apply_relocations when this image carries TLSDESC / TPREL
    // relocs; read by apply_stack_canary_neutralization to gate the TLSDESC
    // mrs->mov#0 (tp-zeroing) rewrite (only for a pure-TLSDESC image, i.e.
    // TLSDESC present and TPREL absent).
    bool image_has_tlsdesc_ = false;
    bool image_has_tprel_   = false;

    // FMOD auto-routing (2026-07-31, lane FMOD; see resolve_external's FMOD
    // block and docs/fmod-faithfulness_2026-07-31.md). Set once by
    // scan_fmod_coverage(), called at the top of apply_relocations() so the
    // FIRST FMOD symbol this image resolves (typically FMOD_System_Create,
    // the handle-minting call) sees the same answer as the last. True iff
    // this image imports at least one _ZN4FMOD/FMOD_-prefixed external
    // symbol that the __macapk_arm64_<name> stub table (bionic_shim.cpp)
    // does not cover -- i.e. this image needs more of FMOD's surface than
    // our stub was built for, so a fake Create handle would otherwise be fed
    // to real (uncovered, already-falling-through) FMOD calls. Per-image,
    // computed from the image's own relocation table: no package name, no
    // vaddr, no engine sniff.
    // MACAPK_FMOD_BIND_BY_NAME=0 ONLY: under the default this stays false and
    // nothing reads it (see macapk_fmod_bind_by_name).
    bool fmod_needs_real_ = false;

    // so_fd: an O_RDONLY fd on the on-disk source .so for lever-2 file-backed
    // segment mapping, or -1 to force the memcpy path (file absent/mismatch/
    // gate off). load() opens + validates it and closes it after this returns.
    // t3-mmap (2026-07-24): `blob` may be nullptr -- load_from_path() has no
    // embedded blob at all, only `so_fd` (which must then be >= 0: every
    // segment byte, filebacked or not, is sourced via pread from the fd
    // instead of memcpy from blob). See the copy_region() helper inside the
    // definition for the single place this fork lives.
    bool map_segments(const uint8_t* blob, size_t blob_size, int so_fd);
    bool parse_dynamic();
    // Shared tail of load() / load_from_path(): parse_dynamic() through the
    // final `return true`. Neither entry point's own code (header parse,
    // map_segments call, blob reclaim vs. from-fd read) is touched by
    // anything in here -- confirmed by grepping every use of `blob`/
    // `blob_size` inside the old single load() body: map_segments() was
    // provably the last read of either (see the blob-reclaim comment at its
    // call site). Extracted t3-mmap (2026-07-24) so load_from_path() can
    // reuse the entire relocate/patch/init_array pipeline byte-for-byte
    // instead of duplicating ~850 lines of it.
    bool load_common();
    // PT_TLS -> register the image's TLS template with the per-thread
    // allocator (bionic_shim.cpp), capturing tls_module_id_ for the
    // DTPMOD/DTPREL reloc cases. Must run after map_segments (base_ known)
    // and before apply_relocations.
    void parse_and_register_tls();
    bool apply_relocations();
    // FMOD auto-routing pre-pass (fmod_needs_real_ above). Must run before
    // the relocation-application loop; see the field comment and the
    // definition just above apply_relocations() for the full rationale.
    // Called only under MACAPK_FMOD_BIND_BY_NAME=0.
    void scan_fmod_coverage();
    void* resolve_external(const std::string& name, uint8_t bind);
    // Same as resolve_external but returns nullptr without logging
    // when the symbol isn't found. Used by the weak-symbol
    // interposition path where missing is expected (most weak
    // symbols stay at their local definition).
    void* resolve_external_silent(const std::string& name, uint8_t bind);
    // The loaded-image step for a WEAK undefined import: only this image's
    // DT_NEEDED tree is searched (bionic's local group). See its definition.
    void* weak_scope_lookup(const std::string& name) const;
    // Last-resort eager map builder -- see exports_fallback_'s comment.
    // Only called from load() when neither hash table is present.
    void build_export_map();
    size_t gnu_hash_symbol_count() const;
    // t3-syms (2026-07-24) on-demand symbol resolution. See the citations
    // on gnu_hash_lookup()/sysv_hash_lookup()'s definitions.
    void* gnu_hash_lookup(const char* name) const;
    // Correctness-preserving fallback for gnu_hash_lookup(): GNU_HASH's
    // bucket/chain only spans the "hashed" region [symoffset, nsyms); a
    // defined+named symbol placed below symoffset (real Android/bionic
    // can never find these by name either -- see the definition's
    // citation) is unreachable by any hash query. The OLD eager
    // build_export_map() walked the WHOLE table with no such boundary, so
    // this scans just that (normally tiny, empirically always-empty-of-
    // defined-symbols) low slice to guarantee zero behavioural regression.
    void* gnu_hash_low_region_scan(const char* name) const;
    void* sysv_hash_lookup(const char* name) const;
    // Shared match predicate for both hash paths: mirrors bionic's
    // is_symbol_global_and_defined() + the unversioned check_symbol_version()
    // case. See gnu_hash_lookup()'s definition for citations.
    bool symbol_name_and_bind_match(const Elf64_Sym& s, const char* name,
                                     uint32_t sym_idx) const;
    // Diagnostic-log-only: exact defined+named symbol count via a single
    // allocation-free counting pass (no names copied/retained), so the
    // "%zu exports indexed" log line keeps reporting the same number as
    // the old eager map (several docs/*.md + debug/diffruns/STALL_ref.log
    // quote this line verbatim for human ad hoc diffing).
    size_t count_defined_named_hashed() const;
    // TEMPORARY verification mode, MACAPK_VERIFY_SYMBOL_HASH=1 (default
    // OFF): rebuilds the OLD eager-map algorithm into a scope-local (never
    // retained) map and asserts the NEW hash-walk lookup() returns
    // identical addresses for every entry, plus identical NOT-FOUND for a
    // batch of known-absent probe names. See the t3-syms mission report
    // for the exhaustive per-app results this produced. Kept env-gated
    // (not deleted) as a reusable self-check if the hash walk is ever
    // touched again.
    void verify_hash_lookup_matches_legacy() const;
    void run_init_array();
    // Register this image's .eh_frame FDEs with the host libunwind so guest
    // C++ exceptions can unwind across host frames. Runs after segment
    // protections (eh_frame final) and before run_init_array (constructors may
    // throw). MACAPK_NO_EH_REGISTER=1 disables.
    void register_eh_frames();
    bool apply_segment_protections();
    // 16 KiB-host-page W^X reconciliation for 4 KiB-aligned Android ELFs.
    // has_wx_conflict() models apply_segment_protections()'s page union for a
    // candidate bias shift; choose_rx_rw_bias_shift() returns how far to push
    // the image so no page needs both W and X (0 = no shift needed/possible).
    bool has_wx_conflict(const std::vector<const Elf64_Phdr*>& loads,
                         size_t shift, uintptr_t* first_bad) const;
    size_t choose_rx_rw_bias_shift(
        const std::vector<const Elf64_Phdr*>& loads) const;
    // Runs deferred R_AARCH64_IRELATIVE ifunc resolvers AFTER the
    // executable PT_LOADs have been mprotect'd to RX (see
    // deferred_ifunc_ above for why this can't happen inside
    // apply_relocations()).
    void apply_ifunc_relocations();
    void install_cocos2dx_searchpath();
    void apply_libfmod_jni_audio_args_patch();
    size_t apply_stack_canary_neutralization();          // replay cache in front of _live
    size_t apply_stack_canary_neutralization_live(size_t* tp_zeroed_out);
    size_t apply_svc_brk_rewrite();
    size_t apply_raw_syscall_wrapper_redirect();
    size_t apply_midr_el1_neutralization();
    size_t apply_go_tls_rewrite();
    void apply_zlib_inflate_tracepoint();
    void apply_zlib_inflate_table_tracepoint();
    void apply_libcocos_zlib_redirect();
    size_t apply_cocos2dx_v2_addchild_null_safety();
    void install_il2cpp_exc_logger(); // F3: generic managed-exception logger (exported syms)
    void install_il2cpp_time_probe(); // MACAPK_IL2CPP_TIME_PROBE: Time.* snapshots (exported syms)
    void record_unity_director_base(); // libunity base for the DirectorUpdate driver
    void install_x18_generic_rewrite();    // GENERIC x18->free-reg rewrite over all functions
    void apply_unity_path_noabs();   // env-gated render-fix test; see definition
    void apply_unity_swappy_present_fix();  // RETIRED by default (MACAPK_UNITY_SWAPPY_PRESENT_RETIRED); see def
    void apply_v8_flag_override();   // set V8 startup flags (jitless/serial) on W^X macOS; see def
};

// MACAPK_UNITY_SWAPPY_PRESENT_RETIRED (default ON): libunity's present dispatch
// runs unmodified. Defined with Image::apply_unity_swappy_present_fix, whose
// comment carries the evidence.
static bool macapk_unity_swappy_present_retired();

// Forward declaration so Image::resolve_external can walk the global
// registry of previously-loaded Images. Body lives near the public-
// ABI block where the registry actually gets appended to.
std::vector<Image*>& loaded_images();
// Guest dladdr's dli_fname for an image (defined with __macapk_arm64_native_dladdr);
// load_common() interns it into Image::dladdr_fname before registering the image.
static const char* macapk_guest_image_realpath(const Image* img);

// Unresolved-import diagnostics (defined just below the resolve_*
// methods). resolve_external() records every hard-miss here; PLT
// (JUMP_SLOT) slots for non-weak misses are pointed at the trap so a
// CALL to an unimplemented native symbol lands in a named handler
// instead of crashing at pc=0. See the definition block for the full
// rationale (CLAUDE.md "Missing shims are silent killers").
void record_unresolved(const char* img, const std::string& sym);
long macapk_unresolved_trap(void);
uint64_t trap_if_unresolved_func(uint64_t value, uint8_t st_info,
                                 uint32_t rtype);

// Forward-declarations of helpers from libmacapk-bionic-shim.cpp,
// where the runtime-supplied android_root and package-name are kept.
extern "C" const char* __macapk_get_android_root();
extern "C" const char* __macapk_get_package_name();
extern "C" const char* __macapk_get_data_subdir();
// <root>/data/app/~~<nonce>/<pkg>-<nonce> -- the installed package (bionic_shim.cpp).
extern "C" const char* __macapk_install_dir();
static std::string macapk_get_package_name() {
    const char* p = __macapk_get_package_name();
    return p ? std::string(p) : std::string();
}

// macOS libunwind dynamic FDE registration (dlsym'd once, lazy). Apple's
// __register_frame takes a SINGLE FDE pointer (NOT a whole .eh_frame section,
// unlike the libgcc variant) -- verified vs apple-oss-distributions/libunwind
// UnwindLevel1-gcc-ext.c. Used by Image::register_eh_frames so guest C++
// exceptions unwind through host frames instead of std::terminate -> SIGABRT.
typedef void (*macapk_frame_fn)(const void*);
static macapk_frame_fn macapk_register_frame_fn() {
    static macapk_frame_fn f =
        reinterpret_cast<macapk_frame_fn>(dlsym(RTLD_DEFAULT, "__register_frame"));
    return f;
}
static macapk_frame_fn macapk_deregister_frame_fn() {
    static macapk_frame_fn f =
        reinterpret_cast<macapk_frame_fn>(dlsym(RTLD_DEFAULT, "__deregister_frame"));
    return f;
}

Image::~Image() {
    // Deregister this image's FDEs from the host unwinder BEFORE unmapping the
    // memory they point into (a stale FDE in libunwind's DwarfFDECache pointing
    // at freed memory would corrupt a later unwind).
    if (!registered_fdes_.empty()) {
        if (macapk_frame_fn dereg = macapk_deregister_frame_fn())
            for (void* fde : registered_fdes_) dereg(fde);
    }
    if (map_start_ && map_len_) {
        munmap(map_start_, map_len_);
    }
    if (trampoline_) {
        munmap(trampoline_, 4096);
    }
    if (x18_thunks_) {
        munmap(x18_thunks_, x18_thunks_cap_);
    }
    if (go_tls_thunks_) {
        munmap(go_tls_thunks_, go_tls_thunks_cap_);
    }
}

// Align up to the next multiple of `align` (which must be a power of two).
static inline uintptr_t align_up(uintptr_t v, size_t align) {
    return (v + align - 1) & ~(uintptr_t)(align - 1);
}
static inline uintptr_t align_down(uintptr_t v, size_t align) {
    return v & ~(uintptr_t)(align - 1);
}

// ===== Lever 2: file-backed Stage-6 segment mapping =====
//
// Instead of memcpy'ing every PT_LOAD out of the .incbin-embedded blob into
// anonymous (dirty) RAM, map the ORIGINAL on-disk .so file-backed (MAP_PRIVATE)
// for the non-writable (R-X / R--) congruent segments -- exactly like Android's
// bionic linker maps a .so. The clean file-backed pages don't count against the
// process footprint (and evict/re-fault like Android's), so the ~336 MB anon
// copy that libminecraftpe.so cost the 8 GB host shrinks by the clean portion.
// Load-time patch passes (canary/x18/reloc) COW-dirty only the pages they
// touch; RW segments stay anon (RELRO + x18-wire mlock semantics unchanged).
// Fully ELF-shape-predicated (no package names). Design + gate-test evidence:
// docs/stage6-filebacked-segments_2026-07-12.md (was scratchpad wedge design).
//
// Gate is DEFAULT-ON (v617): file-backed segment mapping IS the Android-faithful
// behavior -- bionic's linker maps each .so's non-writable PT_LOADs MAP_PRIVATE
// file-backed (clean, demand-paged, evictable), NOT memcpy'd into anon dirty RAM.
// The clean pages re-faulting from disk under memory pressure is exactly how
// Android runs .so text on a 4GB phone, so it is a fidelity FEATURE, not a risk.
// Flipped after the full Stage-6 sweep passed gate-on (GD, HCR arm64+v7a, Subway,
// Crossy, MCPE; DumbWays' crash is pre-existing + gate-independent). MACAPK_FILEBACKED_SEGMENTS=0
// is the opt-out. Read once (env wins over the compiled default).
static bool macapk_filebacked_env_on() {
    static int v = -1;
    if (v < 0) {
        constexpr bool kFilebackedDefault = true;   // v617: Android-faithful default-ON
        const char* e = std::getenv("MACAPK_FILEBACKED_SEGMENTS");
        if (e) v = (e[0] != '0') ? 1 : 0;
        else   v = kFilebackedDefault ? 1 : 0;
    }
    return v != 0;
}

// Derive the on-disk path of the source .so for `soname`, mirroring the x18
// plan-cache path helper (macapk_x18_plan_path) and AppClassLoader.locateApkSo:
// try each known ABI dir under <root>/data/data/<sub>/_apkt/lib/. The content
// validation below makes a wrong-dir hit impossible to act on (blob is ground
// truth), so ABI priority order is not safety-critical here.
// The two roots an extraction artifact can live under, most-current first.
//
// `_apkt/` is a CODE artifact (regenerable from the APK), so the code/data
// split moved it to "<root>/.macapk/code/<sub>/". It used to live beside the
// app's saves in "<root>/data/data/<sub>/", and a wrapper translated before
// that move -- or an app dir that has not been re-extracted since -- still
// has it there.
//
// ⚠ Stage 6 is the path that CARES. arm32rc emits a self-contained Mach-O, so
// an ARM32 app never reads its source .so again; a Stage-6 wrapper loads the
// ORIGINAL ELF at run time and is dead without it. That asymmetry is exactly
// what the sweep saw: every ARM32 row passed while all six Stage-6 rows
// aborted with SIGSEGV pc=0 in a JNI native (the export trampolines never got
// resolved), reported one line earlier as "source .so MISSING".
static std::string macapk_app_subroot(const char* root, const char* sub, int which) {
    switch (which) {
        case 0:  return std::string(root) + "/.macapk/code/" + sub;  // current
        default: return std::string(root) + "/data/data/" + sub;     // legacy
    }
}

// v1.0.1588: the installed package's own lib/<isa>/ (Phase 4 of
// docs/disk-footprint-and-space-efficiency_2026-09-19.md) is where the
// libraries live now, and the only place a current build writes them. The
// apktool-tree roots below are kept for one reason: a wrapper cached by an
// earlier build names its source as "_apkt/lib/<abi>/lib<name>.so", and an
// app that has not relaunched since still has that tree. Returns the first
// path that EXISTS, install dir first.
static std::string macapk_installed_lib_path(const char* base) {
    const char* inst = __macapk_install_dir();
    if (!inst || !base || !*base) return std::string();
    for (const char* isa : {"arm64", "arm"}) {
        std::string p = std::string(inst) + "/lib/" + isa + "/" + base;
        struct stat st;
        if (::stat(p.c_str(), &st) == 0) return p;
    }
    return std::string();
}

static std::string macapk_source_so_path(const char* soname) {
    if (!soname) return std::string();
    const char* base = std::strrchr(soname, '/');
    base = base ? base + 1 : soname;
    std::string installed = macapk_installed_lib_path(base);
    if (!installed.empty()) return installed;
    const char* root = __macapk_get_android_root();
    const char* sub  = __macapk_get_data_subdir();
    if (!root || !sub) return std::string();
    for (int which = 0; which < 2; ++which) {
        for (const char* abi : {"arm64-v8a", "armeabi-v7a", "armeabi"}) {
            std::string p = macapk_app_subroot(root, sub, which)
                          + "/_apkt/lib/" + abi + "/" + base;
            struct stat st;
            if (::stat(p.c_str(), &st) == 0) return p;
        }
    }
    return std::string();
}

// pread `len` bytes at file offset `off` from fd and memcmp against blob+off.
// Returns true only on an exact byte match (and an in-range, complete read).
static bool macapk_file_region_matches(int fd, const uint8_t* blob,
                                       size_t blob_size, uint64_t off, size_t len) {
    if (len == 0) return true;
    if (off > blob_size || off + len > blob_size) return false;
    std::vector<uint8_t> buf(len);
    ssize_t n = ::pread(fd, buf.data(), len, (off_t)off);
    if (n != (ssize_t)len) return false;
    return std::memcmp(buf.data(), blob + off, len) == 0;
}

// Validate that the on-disk .so matches the embedded blob well enough to
// file-back its non-writable segments. NOT a full memcmp by default: a full
// compare would transiently fault in 2x336 MB on the 8 GB host. We sample the
// ELF header (64 B), the whole phdr table, and ~4 KB at 3 points of each
// candidate (non-writable) PT_LOAD -- enough to catch the realistic
// divergences (version swap without a cache wipe, a modded/truncated .so).
// MACAPK_FILEBACKED_VERIFY=full forces a chunked full memcmp for diagnosis.
static bool macapk_validate_so_file(int fd, const uint8_t* blob, size_t blob_size,
                                    const Elf64_Ehdr& eh,
                                    const std::vector<Elf64_Phdr>& phdrs,
                                    const char* dbg) {
    struct stat st;
    if (::fstat(fd, &st) != 0) return false;
    if ((uint64_t)st.st_size != (uint64_t)blob_size) {
        std::fprintf(stderr,
            "[arm64-native] %s: file-back validation: size %llu != blob %zu\n",
            dbg, (unsigned long long)st.st_size, blob_size);
        return false;
    }
    const char* verify = std::getenv("MACAPK_FILEBACKED_VERIFY");
    if (verify && std::strcmp(verify, "full") == 0) {
        // Chunked full compare (diagnostic only -- faults in the whole file).
        const size_t kChunk = 1u << 20;
        std::vector<uint8_t> buf(kChunk);
        for (uint64_t off = 0; off < blob_size; off += kChunk) {
            size_t len = (blob_size - off < kChunk) ? size_t(blob_size - off) : kChunk;
            if (::pread(fd, buf.data(), len, (off_t)off) != (ssize_t)len
                || std::memcmp(buf.data(), blob + off, len) != 0) {
                std::fprintf(stderr,
                    "[arm64-native] %s: file-back FULL verify mismatch @%#llx\n",
                    dbg, (unsigned long long)off);
                return false;
            }
        }
        return true;
    }
    if (!macapk_file_region_matches(fd, blob, blob_size, 0, sizeof(Elf64_Ehdr)))
        return false;
    size_t phsz = size_t(eh.e_phnum) * sizeof(Elf64_Phdr);
    if (!macapk_file_region_matches(fd, blob, blob_size, eh.e_phoff, phsz))
        return false;
    for (const auto& ph : phdrs) {
        if (ph.p_type != PT_LOAD) continue;
        if (ph.p_flags & PF_W) continue;         // only the segments we'd file-back
        if (ph.p_filesz == 0) continue;
        const size_t S = 4096;
        uint64_t seg_end = ph.p_offset + ph.p_filesz;
        uint64_t pts[3] = {
            ph.p_offset,
            align_down(ph.p_offset + ph.p_filesz / 2, 8),
            (ph.p_filesz >= S) ? (seg_end - S) : ph.p_offset,
        };
        for (uint64_t pt : pts) {
            size_t len = S;
            if (pt + len > seg_end) len = size_t(seg_end - pt);
            if (!macapk_file_region_matches(fd, blob, blob_size, pt, len)) {
                std::fprintf(stderr,
                    "[arm64-native] %s: file-back validation: sample mismatch "
                    "@%#llx (seg off %#llx)\n",
                    dbg, (unsigned long long)pt, (unsigned long long)ph.p_offset);
                return false;
            }
        }
    }
    return true;
}

// ===== Lever 3: no embedded blob at all -- load straight from the fd =====
//
// Lever 2 (above) still needs the wrapper's .incbin-embedded ELF for the
// ELF header, the phdr table, and (when file-backing is off, or for a
// segment's non-page-aligned head/tail slivers) the segment bodies
// themselves. That embedded copy is why every translated arm64 wrapper
// .dylib on disk duplicates its ENTIRE source .so (arm64_pack.cpp's
// .incbin) and why the wrapper's __TEXT,__elfblob section sits resident
// for the whole load() duration (docs/efficiency-translator-stage6_2026-07-24.md
// Finding #1's "double residency" bug is a symptom of this design, not a
// standalone defect -- there is no double-residency window to mistime once
// there is no second copy to begin with).
//
// Lever 3 removes the embed at the root: the wrapper stores only a path
// fragment + soname (a few dozen bytes), and load_from_path() below reads
// the header, the phdr table, AND every segment body straight off the
// on-disk .so via pread/mmap-from-fd -- mirroring bionic's own
// ElfReader::ReadElfHeader/ReadProgramHeaders/LoadSegments (confirmed by
// fetching linker_phdr.cpp: those pread the header + phdr table from the
// fd, and LoadSegments mmaps every PT_LOAD MAP_PRIVATE from the SAME fd;
// bionic never holds a second in-memory copy of the file it just mapped).

// pread exactly `len` bytes at file offset `off` into `dst`, loop on short
// reads (a plain file practically never short-reads, but pread's contract
// allows it and the cost of handling it is one loop). Returns false on any
// error or short-to-EOF read.
static bool macapk_pread_full(int fd, void* dst, size_t len, uint64_t off) {
    uint8_t* p = static_cast<uint8_t*>(dst);
    size_t got = 0;
    while (got < len) {
        ssize_t n = ::pread(fd, p + got, len - got, (off_t)(off + got));
        if (n <= 0) return false;
        got += size_t(n);
    }
    return true;
}

// Structural validation shared by load() (blob-sourced header) and
// load_from_path() (pread-sourced header): magic, class, endianness,
// machine, ET_DYN, and that the phdr table fits inside `total_size`. This
// is exactly the set of checks load() ran inline before t3-mmap factored
// them out so both entry points enforce the identical contract -- a
// foreign/corrupt file fails load_from_path() the same way a foreign/
// corrupt blob always failed load().
static bool macapk_validate_ehdr(const Elf64_Ehdr& eh, uint64_t total_size,
                                 const char* dbg_name) {
    if (!(eh.e_ident[0] == 0x7f && eh.e_ident[1] == 'E'
          && eh.e_ident[2] == 'L' && eh.e_ident[3] == 'F')) {
        std::fprintf(stderr,
            "[arm64-native] %s: bad ELF magic\n", dbg_name);
        return false;
    }
    if (eh.e_ident[4] != 2 /* ELFCLASS64 */
        || eh.e_ident[5] != 1 /* ELFDATA2LSB */
        || eh.e_machine != EM_AARCH64) {
        std::fprintf(stderr,
            "[arm64-native] %s: not AArch64 little-endian ELF64\n", dbg_name);
        return false;
    }
    if (eh.e_type != ET_DYN) {
        std::fprintf(stderr,
            "[arm64-native] %s: not ET_DYN (got %u)\n",
            dbg_name, unsigned(eh.e_type));
        return false;
    }
    if (eh.e_phentsize != sizeof(Elf64_Phdr)) {
        std::fprintf(stderr,
            "[arm64-native] %s: unexpected e_phentsize %u\n",
            dbg_name, unsigned(eh.e_phentsize));
        return false;
    }
    if (eh.e_phoff + uint64_t(eh.e_phnum) * sizeof(Elf64_Phdr) > total_size) {
        std::fprintf(stderr,
            "[arm64-native] %s: program-header table runs past EOF\n",
            dbg_name);
        return false;
    }
    return true;
}

// Join the android_root + data_subdir the same way macapk_source_so_path()
// does, but against a CALLER-SUPPLIED relative fragment instead of
// re-deriving + guessing the ABI directory from a bare soname. The
// translator (arm64_pack.cpp) already knows exactly which ABI directory it
// read `in_path` from at translation time -- there is no ambiguity to
// resolve, so this is strictly more precise than a 3-way ABI-priority
// guess, not just a rename of macapk_source_so_path(). Relative-not-
// absolute so a wrapper built under one --android-root still resolves
// correctly if the user later points the launcher at a different
// --android-root (the launcher's own root+dir picker documents this as
// supported; baking an absolute path would silently break that).
static std::string macapk_resolve_relative_so_path(const char* so_path_rel) {
    if (!so_path_rel || !*so_path_rel) return std::string();
    // elfexec (2026-07-29): an ALREADY-absolute path passes through unchanged.
    // The generated Stage-6 wrappers always bake a data-dir-relative fragment
    // (see the paragraph above for why), but the elfexec helper runs as a
    // separate process that learns its guest ELF's location from its own
    // argv[0] and never has android_root/data_subdir set -- so it names the
    // file outright. Prepending root+subdir to an absolute path would build
    // "<root>/data/data/<sub>//abs/path"; recognising the leading '/' keeps
    // ONE loader entry point for both callers instead of forking the pipeline.
    if (so_path_rel[0] == '/') return std::string(so_path_rel);
    // v1.0.1588: a current wrapper bakes "lib/<isa>/lib<name>.so", relative
    // to the INSTALL directory (arm64_pack.cpp). Resolve it there; an older
    // wrapper's "_apkt/lib/<abi>/lib<name>.so" fragment resolves to the same
    // installed file by basename, so a cached wrapper survives the layout
    // move without a re-translation.
    {
        const char* inst = __macapk_install_dir();
        if (inst && std::strncmp(so_path_rel, "lib/", 4) == 0) {
            std::string p = std::string(inst) + "/" + so_path_rel;
            struct stat st;
            if (::stat(p.c_str(), &st) == 0) return p;
        }
        const char* base = std::strrchr(so_path_rel, '/');
        std::string installed = macapk_installed_lib_path(base ? base + 1 : so_path_rel);
        if (!installed.empty()) return installed;
    }
    const char* root = __macapk_get_android_root();
    const char* sub  = __macapk_get_data_subdir();
    if (!root || !sub) {
        const char* inst = __macapk_install_dir();
        return inst ? std::string(inst) + "/" + so_path_rel : std::string();
    }
    // Same two roots, same order, same reason as macapk_source_so_path():
    // `_apkt` is a CODE artifact and now lives under .macapk/code, but a
    // wrapper baked before that move (or an app dir not re-extracted since)
    // still resolves under data/data. Return the first that EXISTS rather
    // than the first that is merely well-formed -- returning a plausible
    // path that is not there is what produced "source .so MISSING" and a
    // SIGSEGV pc=0 three frames later.
    for (int which = 0; which < 2; ++which) {
        std::string p = macapk_app_subroot(root, sub, which) + "/" + so_path_rel;
        struct stat st;
        if (::stat(p.c_str(), &st) == 0) return p;
    }
    // Nothing on disk: hand back the CURRENT-layout path so the failure
    // message names where the file was expected to be today.
    if (const char* inst = __macapk_install_dir())
        return std::string(inst) + "/" + so_path_rel;
    return macapk_app_subroot(root, sub, 0) + "/" + so_path_rel;
}

bool Image::map_segments(const uint8_t* blob, size_t blob_size, int so_fd) {
    // t3-mmap (2026-07-24): load_from_path() (Lever 3) passes blob=nullptr
    // and relies entirely on so_fd for every segment byte -- this is the one
    // invariant the whole function assumes below, so check it once, loudly,
    // up front rather than letting a null `blob` dereference explain itself
    // via a SIGSEGV three passes down.
    if (!blob && so_fd < 0) {
        std::fprintf(stderr,
            "[arm64-native] %s: map_segments internal error: neither blob "
            "nor so_fd supplied\n", dbg_name_);
        return false;
    }
    // Pass 1: find min/max vaddr across PT_LOADs.
    uint64_t min_va = UINT64_MAX, max_va = 0;
    std::vector<const Elf64_Phdr*> loads;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_LOAD) continue;
        loads.push_back(&ph);
        if (ph.p_vaddr < min_va) min_va = ph.p_vaddr;
        uint64_t end = ph.p_vaddr + ph.p_memsz;
        if (end > max_va) max_va = end;
    }
    if (loads.empty()) {
        std::fprintf(stderr, "[arm64-native] %s: no PT_LOAD segments\n",
                     dbg_name_);
        return false;
    }

    // Span: from min_va (page-down) to max_va (page-up). The image is
    // loaded at base = mapping_addr - min_va, so segment.p_vaddr is
    // added to base for the runtime address.
    uintptr_t span_lo = align_down(uintptr_t(min_va), page_size_);
    uintptr_t span_hi = align_up(uintptr_t(max_va), page_size_);
    mapped_size_ = span_hi - span_lo;

    // Android .so files are linked with max-page-size 4096, but Apple Silicon
    // runs a 16384-byte VM page and mprotect is enforced at exactly that
    // granularity. So a segment boundary that is page-aligned on Android can
    // land mid-page here, putting an executable segment's tail and a writable
    // segment's head on ONE host page. That page would need RWX, which arm64
    // macOS refuses (EACCES) without a JIT mapping -- and the load fails.
    // Measured on the corpus: 12 libraries across Dumb Ways / Super Jetpack /
    // HCR 1.68.1 (every libcrashlytics*, libFirebaseCpp*), which is why those
    // three apps died at load with
    //   mprotect(0x...,16384,0x7) failed: Permission denied
    //
    // The fix is the one AOSP itself uses for 4 KiB ELFs on 16 KiB devices
    // ("16KiB app compat mode"): shift the WHOLE image forward by up to one
    // host page so the single RX|RW permission boundary lands on a host page
    // boundary. Shifting the load bias moves every segment by the same
    // constant, so all the baked-in PC-relative `bl` offsets and adrp/add
    // global references are preserved exactly -- it is only the image's
    // absolute placement that changes, which ASLR varies anyway. Nothing
    // loses a permission it needs.
    //   bionic/linker/linker_phdr_16kib_compat.cpp, Setup16KiBAppCompat():
    //     // Adjust the load_bias to position the RX|RW boundary on a page boundary
    //     load_bias_ += perm_boundary_offset(rx_rw_boundary);
    //   and IsEligibleFor16KiBAppCompat(), which picks that boundary as
    //     align_up(relro->p_vaddr + relro->p_memsz, kCompatPageSize)  // RELRO present
    //     align_down(first_rw->p_vaddr,             kCompatPageSize)  // -z norelro
    //
    // Note this is NOT only a `-z norelro` bug, though that is how it was
    // first seen (Crashlytics/Crashpad link with -z norelro, so there is no
    // RELRO region to drop W from at all). libFirebaseCppMessaging.so HAS a
    // PT_GNU_RELRO and still collides, because RELRO ends at 0xe000 while the
    // host page runs 0xc000-0x10000 and .data/.bss sit at 0xe000. Any
    // 4 KiB-aligned PT_LOAD pair can collide once the page is 16 KiB.
    const size_t bias_shift = choose_rx_rw_bias_shift(loads);

    // Anonymous RW mapping for the whole span; we'll mprotect per
    // segment later. Reserve the shift pad up front so the image still fits.
    map_len_ = mapped_size_ + bias_shift;
    // MACAPK_PAIRIP_HIVA (DIAGNOSTIC, default OFF, 2026-09-12): bias guest ELF loads to the
    // Android high-VA range (~0x7x_________) instead of the macOS-default low VA (~0x1________).
    // Tests the reframe's residual in-scope candidate (docs/pairip-accumulator-oracle-disp6742):
    // pairip's own libpairipcore is at ~0x7x on a device (stable top-nibble across ASLR boots) vs
    // ~0x1_ for us; if pairip range-checks the high-VA region, our low load detonates +0x440c4 and
    // loading high passes. Advisory hint (no MAP_FIXED) so a taken range just falls back; we log the
    // actual base. NOTE: only biases ELF loads, NOT the guest malloc heap / the .IAP buffer, so a
    // full LOW-POINTER fix would also need heap placement. Binary metric: does MC pass +0x440c4.
    void* hint = nullptr;
    if (std::getenv("MACAPK_PAIRIP_HIVA")) {
        // MACAPK_PAIRIP_HIVA_EXACT (default OFF): place libpairipcore.so at its EXACT device base
        // from the read-point maps (0x79f5b4f000) so it sorts into the device's position (right
        // AFTER base.apk @ 0x79f047c000) AND pairip's maps self-check finds its own PC there --
        // fixing both the fold ORDER and the self-address check the generic HIVA bump cannot. This
        // is the §4f exact-device-addressing cascade for the guest ELF. Advisory hint (no MAP_FIXED)
        // so a taken range just falls back; the census reports the actual base. Other guest ELFs get
        // the generic high-VA bump. docs/pairip-maps-coherence-breakthrough_2026-09-14.md §4f
        if (std::getenv("MACAPK_PAIRIP_HIVA_EXACT") && dbg_name_ &&
            std::strstr(dbg_name_, "libpairipcore")) {
            // Device libpairipcore load base. Default = MC 1.26.52.3 gt523 capture
            // (0x79e3165000), matching the regenerated pairip_fake_modules.h so our real
            // libpairipcore sorts into the device's exact position. Override per build/capture
            // with MACAPK_PAIRIP_HIVA_EXACT_BASE=<hex>. (45.1 maps_r10256 was 0x79f5b4f000.)
            uint64_t b = 0x79e3165000ULL;
            if (const char* e = std::getenv("MACAPK_PAIRIP_HIVA_EXACT_BASE")) {
                uint64_t v = std::strtoull(e, nullptr, 16); if (v) b = v;
            }
            hint = (void*)b;
        } else {
            static std::atomic<uint64_t> s_hiva{0x7000000000ULL};
            uint64_t want = s_hiva.fetch_add((uint64_t)((map_len_ + 0x3FFFFFFFULL) & ~0x3FFFFFFFULL) + 0x40000000ULL);
            hint = (void*)want;
        }
    }
    void* p = mmap(hint, map_len_,
                   PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANON, -1, 0);
    if (hint && p != MAP_FAILED)
        std::fprintf(stderr, "[hiva] %s: hint=%p -> mapped=%p\n", dbg_name_, hint, p);
    if (p == MAP_FAILED) {
        std::fprintf(stderr,
            "[arm64-native] %s: mmap(%zu) failed: %s\n",
            dbg_name_, map_len_, std::strerror(errno));
        mapped_size_ = 0;
        map_len_ = 0;
        return false;
    }
    map_start_ = p;

    // base_ is chosen so that "base_ + p_vaddr" yields the runtime
    // address of any virtual address inside the image. Since the
    // mapping starts at span_lo (in image coordinates), and we want
    // base_ + span_lo == mapping_addr + bias_shift, set
    // base_ = mapping_addr + bias_shift - span_lo. mapped_size_ stays the
    // IMAGE span (not the reservation), so base_ + mapped_size_ still means
    // "one past the last image byte" for every caller that relies on it.
    base_ = static_cast<uint8_t*>(p) + bias_shift - span_lo;

    // Populate `len` bytes at file offset `file_off` into `dst`: memcpy out
    // of `blob` when we have one (the classic Lever-1/2 embedded-blob path,
    // byte-for-byte unchanged from before t3-mmap), or pread straight from
    // `so_fd` when we don't (Lever 3 -- load_from_path() never allocates a
    // blob at all, not even transiently, so there's nothing to reclaim after
    // the fact the way the old blob-madvise-discard block had to). Same
    // source, same destination, same bytes either way -- this is the one
    // place the two loading strategies fork; everything else in this
    // function (the file-backed-mmap decision, the protection computation,
    // the Seg bookkeeping) is identical regardless of which branch ran.
    auto copy_region = [&](uint8_t* dst, uint64_t file_off, size_t len) -> bool {
        if (blob) {
            std::memcpy(dst, blob + file_off, len);
            return true;
        }
        if (!macapk_pread_full(so_fd, dst, len, file_off)) {
            std::fprintf(stderr,
                "[arm64-native] %s: pread(%zu @ %#llx) failed: %s\n",
                dbg_name_, len, (unsigned long long)file_off,
                std::strerror(errno));
            return false;
        }
        return true;
    };

    // Pass 2: populate each PT_LOAD's filesz bytes. Non-writable congruent
    // segments are mapped FILE-BACKED (MAP_PRIVATE) from the on-disk .so when
    // so_fd is valid + the gate is on (lever 2); everything else copies out
    // of the blob (or, Lever 3, pread's straight off so_fd) into the anon
    // span, leaving the BSS tail zero (MAP_ANON).
    const bool fb_enabled = (so_fd >= 0) && macapk_filebacked_env_on();
    size_t fb_segments = 0, fb_bytes = 0;
    for (const auto* ph : loads) {
        if (ph->p_offset + ph->p_filesz > blob_size) {
            std::fprintf(stderr,
                "[arm64-native] %s: PT_LOAD body out of range\n", dbg_name_);
            return false;
        }

        // ---- Lever 2 file-back decision (per segment) ----
        // Only NON-writable (R-X / R--) segments are file-backed (Phase A):
        // RW segments are reloc/RELRO-written wholesale and keeping them anon
        // leaves the x18-wire mlock + tail-zero semantics byte-identical.
        // Congruence (p_offset ≡ runtime address mod page) is required so a
        // single page-aligned mmap file offset lands the file bytes at the
        // right in-page position; non-congruent segments (e.g. DumbWays
        // p_align 0x1000) fall back to memcpy. We map only the INTERIOR whole
        // pages [align_up(addr), align_down(addr+filesz)); the partial
        // head/tail pages stay anon + memcpy'd, which (a) removes the
        // shared-boundary clobber hazard between two congruent segments
        // sharing a 16 KB host page, and (b) keeps sub-page BSS tails
        // zero-filled on the anon page.
        //
        // Everything here is computed from the RUNTIME address, not p_vaddr.
        // They differ whenever choose_rx_rw_bias_shift() moved the image off a
        // host page boundary: p_vaddr's alignment then says nothing about the
        // mapping's, so aligning p_vaddr would hand MAP_FIXED an unaligned
        // address (EINVAL) and would test congruence against the wrong
        // residue. Shifted images simply stop qualifying, which is correct --
        // they fall through to the memcpy path.
        bool filebacked = false;
        const uintptr_t seg_addr = uintptr_t(base_ + ph->p_vaddr);
        if (fb_enabled
            && !(ph->p_flags & PF_W)
            && ((ph->p_offset % page_size_) == (seg_addr % page_size_))) {
            uintptr_t va_lo = align_up(seg_addr, page_size_);
            uintptr_t va_hi = align_down(seg_addr + ph->p_filesz, page_size_);
            if (va_hi > va_lo) {
                uint64_t off = ph->p_offset + (va_lo - seg_addr);  // page-aligned by congruence
                void* want = reinterpret_cast<void*>(va_lo);
                size_t len = size_t(va_hi - va_lo);
                // PROT_READ|PROT_WRITE like the anon span: reloc/canary/x18/
                // engine patches still write (COW) before apply_segment_
                // protections() flips protections (mprotect-only, backing-
                // agnostic). MAP_PRIVATE => writes never reach the file.
                void* got = mmap(want, len, PROT_READ | PROT_WRITE,
                                 MAP_PRIVATE | MAP_FIXED, so_fd, (off_t)off);
                if (got == want) {
                    filebacked = true;
                    ++fb_segments;
                    fb_bytes += len;
                    // Head [seg_addr, va_lo) and tail [va_hi, seg_addr+filesz)
                    // partial pages come from the blob/fd (anon-backed).
                    if (!copy_region(base_ + ph->p_vaddr, ph->p_offset,
                                     size_t(va_lo - seg_addr)))
                        return false;
                    if (!copy_region(reinterpret_cast<uint8_t*>(va_hi),
                                     ph->p_offset + (va_hi - seg_addr),
                                     size_t((seg_addr + ph->p_filesz) - va_hi)))
                        return false;
                } else {
                    // A failed MAP_FIXED may have torn down the anon pages in
                    // [va_lo, va_hi); re-establish anon backing so the full
                    // memcpy below is safe, then fall through to memcpy.
                    mmap(want, len, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON | MAP_FIXED, -1, 0);
                    std::fprintf(stderr,
                        "[arm64-native] %s: seg@%#llx file-back mmap failed "
                        "(%s) -- memcpy fallback\n",
                        dbg_name_, (unsigned long long)ph->p_vaddr,
                        std::strerror(errno));
                }
            }
        }
        if (!filebacked) {
            if (!copy_region(base_ + ph->p_vaddr, ph->p_offset, ph->p_filesz))
                return false;
        }

        // Compute the final protection for this segment. We don't apply
        // it yet -- relocations still need to write to executable
        // segments, and apply_segment_protections() does that pass at
        // the end.
        int prot = 0;
        if (ph->p_flags & PF_R) prot |= PROT_READ;
        if (ph->p_flags & PF_W) prot |= PROT_WRITE;
        if (ph->p_flags & PF_X) prot |= PROT_EXEC;

        Seg s;
        s.vaddr = uintptr_t(base_) + ph->p_vaddr;
        s.memsz = size_t(ph->p_memsz);
        s.prot  = prot;
        segments_.push_back(s);
    }

    if (fb_enabled) {
        // t3-mmap: "rest copied" not "rest memcpy'd" -- with blob==nullptr
        // (Lever 3 / load_from_path) the non-file-backed remainder comes
        // from copy_region()'s pread branch, not a memcpy.
        std::fprintf(stderr,
            "[arm64-native] %s: file-backed %zu segment(s), %#zx bytes "
            "(%zu MB); rest copied from %s\n",
            dbg_name_, fb_segments, fb_bytes, fb_bytes >> 20,
            blob ? "blob" : "fd (pread)");
    }

    return true;
}

bool Image::parse_dynamic() {
    // Find PT_DYNAMIC in the cached program-header table.
    const Elf64_Dyn* dyn = nullptr;
    size_t dyn_count = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn*>(base_ + ph.p_vaddr);
            dyn_count = ph.p_filesz / sizeof(Elf64_Dyn);
            break;
        }
    }
    if (!dyn) {
        std::fprintf(stderr,
            "[arm64-native] %s: no PT_DYNAMIC\n", dbg_name_);
        return false;
    }

    // Walk the dynamic table, snarfing the entries we care about.
    uint64_t pltrelsz = 0;
    uint64_t init_array_sz = 0, fini_array_sz = 0;
    uint64_t relrsz = 0, relrent = 0;
    std::vector<uint64_t> needed_off;
    for (size_t i = 0; i < dyn_count; ++i) {
        const auto& d = dyn[i];
        if (d.d_tag == DT_NULL) break;
        switch (d.d_tag) {
            case DT_NEEDED:
                // A strtab offset; DT_STRTAB may come later in the table.
                needed_off.push_back(d.d_val);
                break;
            case DT_SYMTAB:
                symtab_ = reinterpret_cast<const Elf64_Sym*>(
                    base_ + d.d_val);
                break;
            case DT_STRTAB:
                strtab_ = reinterpret_cast<const char*>(base_ + d.d_val);
                break;
            case DT_STRSZ:
                strtab_size_ = d.d_val;
                break;
            case DT_GNU_HASH:   // 0x6ffffef5
                gnu_hash_ = reinterpret_cast<const uint32_t*>(base_ + d.d_val);
                break;
            case DT_HASH:       // 4 -- classic SysV hash; see sysv_hash_'s comment
                sysv_hash_ = reinterpret_cast<const uint32_t*>(base_ + d.d_val);
                break;
            case DT_VERSYM:     // 0x6ffffff0 -- see versym_'s comment
                versym_ = reinterpret_cast<const uint16_t*>(base_ + d.d_val);
                break;
            case DT_RELA:
                rela_ = reinterpret_cast<const Elf64_Rela*>(base_ + d.d_val);
                break;
            case DT_RELASZ:
                rela_count_ = d.d_val / sizeof(Elf64_Rela);
                break;
            case DT_ANDROID_RELA:
                // Raw pointer only -- the "APS2" magic is validated (and the
                // 4-byte magic stripped) in apply_relocations(), mirroring
                // AOSP linker.cpp's own capture-now/validate-later split.
                android_relocs_raw_ = reinterpret_cast<const uint8_t*>(
                    base_ + d.d_val);
                break;
            case DT_ANDROID_RELASZ:
                android_relocs_raw_size_ = d.d_val;
                break;
            case DT_JMPREL:
                pltrel_ = reinterpret_cast<const Elf64_Rela*>(base_ + d.d_val);
                break;
            case DT_PLTRELSZ:
                pltrelsz = d.d_val;
                break;
            case DT_RELR:
            case DT_ANDROID_RELR:
                relr_ = reinterpret_cast<const Elf64_Relr*>(base_ + d.d_val);
                break;
            case DT_RELRSZ:
            case DT_ANDROID_RELRSZ:
                relrsz = d.d_val;
                break;
            case DT_RELRENT:
            case DT_ANDROID_RELRENT:
                relrent = d.d_val;
                break;
            case DT_INIT_ARRAY:
                init_array_ = reinterpret_cast<const uint64_t*>(base_ + d.d_val);
                break;
            case DT_INIT_ARRAYSZ:
                init_array_sz = d.d_val;
                break;
            case DT_FINI_ARRAY:
                fini_array_ = reinterpret_cast<const uint64_t*>(base_ + d.d_val);
                break;
            case DT_FINI_ARRAYSZ:
                fini_array_sz = d.d_val;
                break;
            case DT_INIT:
                // Link-time offset, biased by the load base (bionic
                // linker_soinfo.cpp: init_func_ = load_bias + d_un.d_ptr).
                init_func_ = reinterpret_cast<uint64_t>(base_ + d.d_val);
                break;
            case DT_FINI:
                fini_func_ = reinterpret_cast<uint64_t>(base_ + d.d_val);
                break;
            default:
                break;
        }
    }

    if (!symtab_ || !strtab_) {
        std::fprintf(stderr,
            "[arm64-native] %s: missing DT_SYMTAB/DT_STRTAB\n", dbg_name_);
        return false;
    }
    for (uint64_t off : needed_off) {
        if (strtab_size_ && off >= strtab_size_) continue;
        const char* n = strtab_ + off;
        if (const char* sl = std::strrchr(n, '/')) n = sl + 1;
        needed_.emplace_back(n);
    }
    pltrel_count_     = pltrelsz / sizeof(Elf64_Rela);
    init_array_count_ = init_array_sz / sizeof(uint64_t);
    fini_array_count_ = fini_array_sz / sizeof(uint64_t);
    // DT_RELRENT is the size of ONE packed entry and must be a 64-bit word for
    // the bitmap decode to line up. A different value would mean a table shape
    // we do not understand, and quietly mis-decoding it would scribble
    // load-bias-sized values over unrelated words -- fail the load loudly
    // instead. Absent (0) means "not stated", which is the common case.
    if (relrent != 0 && relrent != sizeof(Elf64_Relr)) {
        std::fprintf(stderr,
            "[arm64-native] %s: DT_RELRENT is %llu, expected %zu\n",
            dbg_name_, (unsigned long long)relrent, sizeof(Elf64_Relr));
        return false;
    }
    relr_count_ = relrsz / sizeof(Elf64_Relr);
    if (relr_ != nullptr && relr_count_ == 0) {
        // A DT_RELR with no DT_RELRSZ is malformed; applying nothing would
        // leave every packed RELATIVE fixup at its link-time value.
        std::fprintf(stderr,
            "[arm64-native] %s: DT_RELR present but DT_RELRSZ is 0\n",
            dbg_name_);
        return false;
    }
    // DT_ANDROID_RELA: validate the "APS2" 4-byte magic and strip it, exactly
    // matching AOSP's bionic/linker/linker_relocate.cpp soinfo::relocate():
    //   if (android_relocs_size_ > 3 && android_relocs_[0] == 'A' && ...[1]
    //       == 'P' && ...[2] == 'S' && ...[3] == '2') {
    //     const uint8_t* packed_relocs = android_relocs_ + 4;
    //     const size_t packed_relocs_size = android_relocs_size_ - 4;
    //     ...
    //   } else {
    //     DL_ERR("bad android relocation header.");
    //     return false;
    //   }
    // A present-but-malformed header is a hard load failure on real Android
    // too (DL_ERR + return false), so failing the load here rather than
    // silently skipping the relocations is AOSP-faithful, not a regression:
    // real Android would refuse to run this library either.
    if (android_relocs_raw_ != nullptr) {
        if (android_relocs_raw_size_ > 3 &&
            android_relocs_raw_[0] == 'A' && android_relocs_raw_[1] == 'P' &&
            android_relocs_raw_[2] == 'S' && android_relocs_raw_[3] == '2') {
            android_packed_relocs_ = android_relocs_raw_ + 4;
            android_packed_relocs_size_ =
                static_cast<size_t>(android_relocs_raw_size_ - 4);
        } else {
            std::fprintf(stderr,
                "[arm64-native] %s: bad android relocation header "
                "(DT_ANDROID_RELA present, \"APS2\" magic missing/short)\n",
                dbg_name_);
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// FMOD boundary trace (MACAPK_FMOD_TRACE) -- a generic DIAGNOSTIC, not per-APK.
// Under MACAPK_FMOD_REAL every FMOD symbol resolves DIRECTLY to real libfmod,
// so there is no shim seam left to observe FMOD's own return codes / channel
// state. This wraps the handful of decisive FMOD entry points with a
// transparent forwarder that logs args + result and tail-calls the REAL
// libfmod function (resolved from the loaded libfmod Image -- dlsym cannot see
// ELF-image exports). It answers the "silent menu music" bisect: is FMOD
// producing no sound (createSound/createStream/playSound fails) or playing at
// zero volume (setVolume 0 / setPaused true)? Zero effect unless the env is
// set; only the traced names are wrapped, everything else resolves straight to
// real libfmod. Applies to ANY FMOD app (gated on the FMOD symbol shape, not a
// package name), so it is a permanent debugging tool like MACAPK_AUDIO_RMS.
// (2026-09-27) Installed only under MACAPK_FMOD_BIND_BY_NAME=0: the default
// path has no FMOD test at all, so these are unreachable there. See
// macapk_fmod_bind_by_name just below this block.
namespace {
typedef int (*fmod_create_fn)(void*, const char*, unsigned int, void*, void**);
typedef int (*fmod_play_fn)(void*, void*, void*, bool, void**);
typedef int (*fmod_setvol_fn)(void*, float);
typedef int (*fmod_setpaused_fn)(void*, bool);
typedef int (*fmod_isplaying_fn)(void*, bool*);
static fmod_create_fn    g_real_createSound  = nullptr;
static fmod_create_fn    g_real_createStream = nullptr;
static fmod_play_fn      g_real_playSound    = nullptr;
static fmod_setvol_fn    g_real_setVolume    = nullptr;
static fmod_setpaused_fn g_real_setPaused    = nullptr;
static fmod_isplaying_fn g_real_isPlaying    = nullptr;

// Per-function log cap so a high-frequency poller (isPlaying, called every
// frame) cannot starve visibility of the rare-but-decisive calls (setVolume).
static void fmod_trace_log(const char* fn, std::atomic<long>* ctr, long cap,
                           const char* fmt, ...) {
    if (ctr->fetch_add(1) >= cap) return;
    char buf[256];
    va_list ap; va_start(ap, fmt); std::vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    std::fprintf(stderr, "[fmod-trace] %s %s\n", fn, buf);
    std::fflush(stderr);
}
extern "C" int macapk_fmodtrace_createSound(void* self, const char* name,
        unsigned int mode, void* ex, void** sound) {
    static std::atomic<long> c{0};
    int r = g_real_createSound ? g_real_createSound(self, name, mode, ex, sound) : -1;
    fmod_trace_log("createSound", &c, 200, "name=%s mode=0x%x -> r=%d sound=%p",
        name ? name : "(mem/null)", mode, r, sound ? *sound : nullptr);
    return r;
}
extern "C" int macapk_fmodtrace_createStream(void* self, const char* name,
        unsigned int mode, void* ex, void** sound) {
    static std::atomic<long> c{0};
    int r = g_real_createStream ? g_real_createStream(self, name, mode, ex, sound) : -1;
    fmod_trace_log("createStream", &c, 200, "name=%s mode=0x%x -> r=%d sound=%p",
        name ? name : "(mem/null)", mode, r, sound ? *sound : nullptr);
    return r;
}
extern "C" int macapk_fmodtrace_playSound(void* self, void* snd, void* grp,
        bool paused, void** chan) {
    static std::atomic<long> c{0};
    int r = g_real_playSound ? g_real_playSound(self, snd, grp, paused, chan) : -1;
    fmod_trace_log("playSound", &c, 200, "snd=%p grp=%p paused=%d -> r=%d chan=%p",
        snd, grp, (int)paused, r, chan ? *chan : nullptr);
    return r;
}
extern "C" int macapk_fmodtrace_setVolume(void* self, float v) {
    static std::atomic<long> c{0};   // decisive: keep visible for the whole run
    int r = g_real_setVolume ? g_real_setVolume(self, v) : -1;
    fmod_trace_log("setVolume", &c, 100000, "self=%p vol=%.3f -> r=%d", self, v, r);
    return r;
}
extern "C" int macapk_fmodtrace_setPaused(void* self, bool p) {
    static std::atomic<long> c{0};
    int r = g_real_setPaused ? g_real_setPaused(self, p) : -1;
    fmod_trace_log("setPaused", &c, 1000, "self=%p paused=%d -> r=%d", self, (int)p, r);
    return r;
}
extern "C" int macapk_fmodtrace_isPlaying(void* self, bool* out) {
    static std::atomic<long> c{0};
    int r = g_real_isPlaying ? g_real_isPlaying(self, out) : -1;
    fmod_trace_log("isPlaying", &c, 8, "self=%p -> r=%d playing=%d", self, r,
        out ? (int)*out : -1);
    return r;
}
}  // namespace

// ---------------------------------------------------------------------------
// MACAPK_FMOD_BIND_BY_NAME (default ON, v1.0.1654): an FMOD-named import binds
// like every other import, with no FMOD test anywhere in the loader.
//
// A device has no FMOD layer. The Bionic linker binds each UND import BY NAME
// against one SymbolLookupList, the namespace's global group followed by the
// local group (the DT_NEEDED tree walked from the root): bionic
// linker/linker.cpp `SymbolLookupList lookup_list(global_group, local_group);`
// and linker_relocate.cpp lookup_symbol() -> soinfo_do_lookup(), where a
// non-weak miss is DL_ERR("cannot locate symbol ..."). So libcocos2dcpp's and
// libminecraftpe's FMOD imports land in the app's OWN bundled libfmod.so.
//
// Ours now do the same by the ordinary route. The FMOD stub table in
// bionic_shim.cpp is exported as __macapk_fmodstub_<name>, outside the
// __macapk_arm64_ namespace resolve_external / resolve_external_silent probe,
// so an FMOD name misses the shim, misses macapk_bionic_adapter, misses the
// host dlsym (no host image exports an FMOD name) and is found by the
// loaded-image lookup in libfmod, which is loaded before its dependant binds
// (the app's own System.loadLibrary order, or AppClassLoader's DT_NEEDED
// preload when the app loads the dependant first). That is the binding the
// default has produced since v1.0.1111 by a longer route (scan_fmod_coverage forcing
// fmod_needs_real_ true for every image, then skip_fmod_shim hiding the stubs
// from their own probe); this gate drops the route, not the result. It also
// closes the one place the old route leaked: resolve_external_silent (weak-
// definition interposition) never looked at skip_fmod_shim, so a WEAK FMOD
// definition would have been stubbed even on the "real" path. Measured
// 2026-09-27: no corpus libfmod has one (GD Lite, GD Meltdown and MCPE define
// 744-786 FMOD symbols each, all GLOBAL).
//
// Under the default: no scan_fmod_coverage, no skip_fmod_shim, no
// MACAPK_FMOD_TRACE forwarders, no reloc-memo FMOD bypass. MACAPK_FMOD_STUB,
// MACAPK_FMOD_REAL and MACAPK_FMOD_TRACE are read only under =0 (a note line
// says so if one is set). =0 restores the old routing exactly, with the stubs
// probed under their new name at every site that used to find them.
// The stub's premise (a libfmod 2.0.0.x mixer UAF behind the inflate_table
// SIGBUS) was refuted on 2026-05-17: the watchpoint run saw zero
// non-main-thread writes, the corruption was intra-thread in cocos's static
// zlib, and the zlib redirect fixed it.
// Gate: debug/geh_fmod-real-vs-stub-routing_gate.sh. Evidence:
// docs/fmod-stub-routing-retired_2026-09-27.md.
// REMOVAL CONDITION (STEP 2): about 3 clean sweeps plus the audio falsifiers in
// that note, then delete the stub table, scan_fmod_coverage, fmod_needs_real_,
// skip_fmod_shim, the forwarders above, this gate, the reloc-memo bypass and
// the MACAPK_FMOD_{REAL,STUB,TRACE} reads.
static bool macapk_fmod_bind_by_name() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_FMOD_BIND_BY_NAME");
        return !(v && v[0] == '0');
    }();
    return on;
}

// ---------------------------------------------------------------------------
// Loader-installed Bionic ABI adapters
//
// resolve_external's second stage is dlsym(RTLD_DEFAULT, <bare name>), which
// hands the guest whatever libSystem exports under that name. For most of libc
// that is exactly right (memcpy is memcpy). For a handful of names it is
// actively harmful, because Darwin exports a HOMONYM with a different
// signature: the guest lays its arguments out per Bionic's prototype and the
// Darwin callee reads those same registers as a different parameter list.
//
// A homonym mismatch can only be fixed HERE. Once apply_relocations has written
// Darwin's address into the GOT slot there is no seam left -- the guest just
// calls the wrong function forever. So the loader, which is the component that
// decides what a name binds to, keeps the small table below and consults it
// before the bare-name fallback.
//
// These are internal-linkage fallbacks by design: stage 1
// (`__macapk_arm64_<name>` in libmacapk-bionic-shim) is still tried first, so a
// richer implementation added there transparently wins and this table goes
// quiet, with no duplicate-symbol collision either way.
//
// Per the struct-offset rule, each adapter's comment pins BOTH prototypes with
// its citation before the code.

// Bionic  (libc/include/sys/sendfile.h, read this turn):
//   ssize_t sendfile(int __out_fd, int __in_fd, off_t* __offset, size_t __count);
//   ssize_t sendfile64(int __out_fd, int __in_fd, off64_t* __offset, size_t __count);
// Darwin  (xnu bsd/man/man2/sendfile.2 -- the homonym we must NOT bind to):
//   int sendfile(int fd, int s, off_t offset, off_t* len,
//                struct sf_hdtr* hdtr, int flags);
// Three separate mismatches: the fd roles are REVERSED (Darwin fd=source,
// s=destination socket; Linux out_fd=destination, in_fd=source), Darwin's third
// argument is an off_t VALUE where Linux passes a pointer, and Darwin's fourth
// is an OUT pointer where Linux passes a plain byte count -- so the kernel
// would write 8 bytes through the guest's count (e.g. 4096) as if it were an
// address. Darwin also requires `s` to be a stream socket while Linux accepts
// any writable out_fd, so forwarding is not an option even with the arguments
// untangled; a portable copy loop is what glibc does on platforms with no
// zero-copy path. On arm64 off_t and off64_t are both 64-bit, so one
// implementation serves both names.
//
// Linux semantics implemented here (sendfile(2)):
//  - offset != NULL: copying starts at *offset, in_fd's file position is NOT
//    changed, and *offset is left just past the last byte transferred.
//  - offset == NULL: copying starts at in_fd's current position, which is then
//    advanced by the number of bytes actually SENT.
//  - in_fd must support mmap()-like operations; a pipe or socket is EINVAL.
//  - the return value is the byte count written and may be short.
static ssize_t macapk_bionic_sendfile(int out_fd, int in_fd,
                                      off_t* offset, size_t count) {
    // Linux caps a single call at 0x7ffff000 bytes.
    constexpr size_t kMaxCount = 0x7ffff000u;
    if (count > kMaxCount) count = kMaxCount;

    off_t pos = offset ? *offset : ::lseek(in_fd, 0, SEEK_CUR);
    if (pos < 0) {
        // Non-seekable in_fd. Linux answers EINVAL rather than ESPIPE.
        if (errno == ESPIPE) errno = EINVAL;
        return -1;
    }

    char   buf[16384];
    size_t sent   = 0;
    bool   failed = false;
    while (sent < count) {
        size_t want = count - sent;
        if (want > sizeof(buf)) want = sizeof(buf);
        // pread, never read: the source position is tracked by hand so the
        // offset==NULL case can be left exactly at the last byte SENT even
        // when the destination write comes up short.
        ssize_t n = ::pread(in_fd, buf, want, pos);
        if (n < 0) {
            if (errno == EINTR) continue;
            failed = true;
            break;
        }
        if (n == 0) break;                       // EOF: a short return is legal
        size_t done = 0;
        while (done < size_t(n)) {
            ssize_t w = ::write(out_fd, buf + done, size_t(n) - done);
            if (w > 0) { done += size_t(w); continue; }
            // A zero return for a non-zero count is not a legal write(2)
            // result; treat it like an error rather than spinning on it.
            if (w < 0 && errno == EINTR) continue;
            failed = true;
            break;
        }
        pos  += off_t(done);
        sent += done;
        if (failed || done < size_t(n)) break;
    }

    if (offset) {
        *offset = pos;
    } else if (::lseek(in_fd, pos, SEEK_SET) < 0) {
        return -1;
    }
    // Linux reports the partial count when anything was transferred and only
    // fails outright when nothing was.
    if (sent == 0 && failed) return -1;          // errno set by pread/write
    return ssize_t(sent);
}

// Bionic  (libc/include/pthread.h + libc/bionic/pthread_setname_np.cpp, read
//          this turn):
//   int pthread_setname_np(pthread_t t, const char* name);     // ANY thread
//   int pthread_getname_np(pthread_t t, char* buf, size_t n);  // ANY thread
//   MAX_TASK_COMM_LEN == 16. setname returns ERANGE when
//   thread_name_len >= MAX_TASK_COMM_LEN; getname returns ERANGE when
//   buf_size < MAX_TASK_COMM_LEN. Both return an errno VALUE, never -1.
// Darwin  (apple-oss-distributions/libpthread include/pthread/pthread.h):
//   int pthread_setname_np(const char* name);                  // SELF ONLY
//   int pthread_getname_np(pthread_t t, char* buf, size_t n);  // ANY thread
// getname is ABI-compatible and only needs Bionic's ERANGE contract. setname
// is the dangerous one: the guest puts the pthread_t in x0 and the name in x1
// per AAPCS64, and Darwin's one-argument homonym reads x0 AS the name string,
// so it copies the raw bytes of a thread handle into the CALLING thread's name
// and drops the requested name entirely.
//
// Darwin has no API that renames another thread by design -- the kernel applies
// __proc_info(PROC_SELFSET_THREADNAME) to whichever thread issued it -- so the
// cross-thread case cannot reach the host kernel. It is still recorded here so
// the guest's own set/get round-trip stays coherent, which is the part of
// Bionic's contract guest code can observe; what is lost is only the host-side
// name in lldb / Instruments. Self-naming, which is what nearly every real call
// site does right after spawning, is served in full.
//
// __macapk_arm64_pthread_create stores the REAL macOS pthread_t into the
// guest's pthread_t slot (bionic_shim.cpp), so the handle round-trips back to
// libSystem unchanged and pthread_equal against pthread_self() is meaningful.
// The handle is only ever compared and used as a map key here, never
// dereferenced, so a sentinel handle from a per-image pthread_create variant is
// harmless.
constexpr size_t kBionicMaxTaskCommLen = 16;   // bionic MAX_TASK_COMM_LEN

std::mutex& guest_thread_name_mutex() {
    static std::mutex m;
    return m;
}
std::unordered_map<uintptr_t, std::string>& guest_thread_names() {
    static std::unordered_map<uintptr_t, std::string> m;
    return m;
}

static int macapk_bionic_pthread_setname_np(unsigned long thread,
                                            const char* name) {
    if (!name) return EFAULT;
    if (std::strlen(name) >= kBionicMaxTaskCommLen) return ERANGE;
    {
        std::lock_guard<std::mutex> lk(guest_thread_name_mutex());
        auto& table = guest_thread_names();
        // Bounded: thread handles are recycled and there is no thread-exit hook
        // at this layer, so cap the table instead of growing it for the life of
        // the process. Dropping a record only costs a later getname falling
        // back to the host name.
        if (table.size() < 4096u || table.count(uintptr_t(thread)) != 0)
            table[uintptr_t(thread)] = name;
    }
    if (pthread_equal(reinterpret_cast<pthread_t>(thread), pthread_self())) {
        int rc = ::pthread_setname_np(name);
        // Darwin returns __proc_info's result: -1 with errno set. Bionic's
        // callers expect the errno VALUE as the return.
        if (rc < 0) return errno ? errno : EINVAL;
        if (rc > 0) return rc;
    }
    return 0;
}

static int macapk_bionic_pthread_getname_np(unsigned long thread,
                                            char* buf, size_t buf_size) {
    if (!buf) return EFAULT;
    if (buf_size < kBionicMaxTaskCommLen) return ERANGE;
    {
        std::lock_guard<std::mutex> lk(guest_thread_name_mutex());
        auto& table = guest_thread_names();
        auto it = table.find(uintptr_t(thread));
        if (it != table.end()) {
            std::snprintf(buf, buf_size, "%s", it->second.c_str());
            return 0;
        }
    }
    // Never named through the Bionic entry point: Darwin's getname IS
    // any-thread and ABI-compatible, so ask the host for the real name.
    int rc = ::pthread_getname_np(reinterpret_cast<pthread_t>(thread),
                                  buf, buf_size);
    if (rc < 0) return errno ? errno : ESRCH;
    return rc;
}

// Bionic  (libc/include/sys/ptrace.h, read this turn): `long ptrace(int __op, ...)`
// with the Linux op numbers from <linux/ptrace.h> (PTRACE_TRACEME 0,
// PTRACE_PEEKTEXT 1, ... PTRACE_ATTACH 16, PTRACE_DETACH 17).
// Darwin  (xnu bsd/sys/ptrace.h): `int ptrace(int request, pid_t pid,
// caddr_t addr, int data)`, whose numbering only coincides with Linux for the
// low BSD-heritage values -- PT_ATTACH is 10 and PT_DETACH 11 there, and there
// is no GETREGS/SETREGS at all (Darwin reaches thread state through
// task_for_pid + Mach).
//
// Op 0 collides EXACTLY: Linux PTRACE_TRACEME == Darwin PT_TRACE_ME == 0, and
// that is the one real apps actually issue (the standard native anti-tamper
// self-check). Today it reaches the real macOS kernel and marks MAC.APK'S OWN
// process traced -- Stage-6 guest code runs in the same process as the JVM, so
// there is no forked child holding the parent/child trace relationship
// PT_TRACE_ME assumes. A traced process stops on signal delivery waiting for a
// debugger parent that will never continue it, which can wedge the whole app,
// and it collides with the host ptrace facility our own lldb/sample diagnostics
// use.
//
// The faithful answer to the probe is the one an ordinary undebugged device
// gives: PTRACE_TRACEME succeeds. Every other op is answered ESRCH, which is
// precisely what Linux reports when the target "is not currently being traced
// by the caller" -- true of every process here, since this adapter traces
// nothing.
//
// Fixed named parameters, NOT `(int, ...)`: on Apple arm64 a variadic callee
// reads its arguments off the STACK while the Bionic-compiled caller left them
// in x1..x3 per AAPCS64. That is the same trap already fixed for open / fcntl /
// syscall / prctl in bionic_shim.cpp.
static long macapk_bionic_ptrace(int op, pid_t /*pid*/, void* /*addr*/,
                                 void* /*data*/) {
    constexpr int kPtraceTraceMe = 0;   // uapi/linux/ptrace.h PTRACE_TRACEME
    if (op == kPtraceTraceMe) return 0;
    errno = ESRCH;
    return -1;
}

// Bionic declares `int cacheflush(long __addr, long __nbytes, long __cache)`
// in libc/include/unistd.h under `#if defined(__arm__)`, but the implementation
// is a bare wrapper around the ARM-private syscall __ARM_NR_cacheflush, whose
// kernel prototype is sys_cacheflush(unsigned long START, unsigned long END,
// unsigned long flags). CONFLICT, surfaced per the sources-of-truth rule: the
// header's `__nbytes` parameter NAME says length, the kernel contract says end
// address. The kernel wins, because Bionic's wrapper passes the three registers
// straight through and never inspects them, so the header name has no effect on
// the ABI any caller was compiled against.
//
// Darwin exports no `cacheflush` at all, so unlike the entries above this one
// is a pure addition: the symbol is unresolved today and the loader's trap
// aborts on first call. The primitive is the one this file already uses at
// every site where it writes new native code.
static int macapk_bionic_cacheflush(long start, long end, long /*flags*/) {
    if (end < start) { errno = EINVAL; return -1; }
    sys_icache_invalidate(reinterpret_cast<void*>(start),
                          size_t(end - start));
    return 0;
}

// The table itself. Consulted by resolve_external between the
// `__macapk_arm64_<name>` shim lookup and the bare-name dlsym fallback.
void* macapk_bionic_adapter(const std::string& name) {
    struct Entry { const char* name; void* fn; };
    static const Entry kTable[] = {
        { "sendfile",   reinterpret_cast<void*>(&macapk_bionic_sendfile) },
        { "sendfile64", reinterpret_cast<void*>(&macapk_bionic_sendfile) },
        { "pthread_setname_np",
          reinterpret_cast<void*>(&macapk_bionic_pthread_setname_np) },
        { "pthread_getname_np",
          reinterpret_cast<void*>(&macapk_bionic_pthread_getname_np) },
        { "ptrace",     reinterpret_cast<void*>(&macapk_bionic_ptrace) },
        { "cacheflush", reinterpret_cast<void*>(&macapk_bionic_cacheflush) },
    };
    for (const auto& e : kTable) {
        if (name == e.name) return e.fn;
    }
    return nullptr;
}

// True when a host symbol pointer (as returned by dlsym(RTLD_DEFAULT)) lives in
// one of Apple's private crypto dylibs. macOS ships these in the dyld shared
// cache -- /usr/lib/libboringssl.dylib, /usr/lib/libcrypto.NN.dylib (OpenSSL),
// /usr/lib/libssl.NN.dylib -- and they export the standard OpenSSL/BoringSSL C
// API (EVP_*, SSL_*, X509_*, RSA_*, ...). They export ONLY crypto/SSL, so
// matching on the owning dylib is precise and complete (no libc/pthread name
// can hit this) -- see resolve_external's call site for why we refuse them.
// dladdr on an arm64_native-mmap'd guest ELF returns 0, so this only ever
// classifies genuine host dylibs.
static bool host_dylib_is_private_crypto(void* addr) {
    Dl_info di;
    if (!addr || !dladdr(addr, &di) || !di.dli_fname) return false;
    const char* slash = std::strrchr(di.dli_fname, '/');
    const char* base = slash ? slash + 1 : di.dli_fname;
    return std::strncmp(base, "libboringssl", 12) == 0
        || std::strncmp(base, "libcrypto", 9) == 0
        || std::strncmp(base, "libssl", 6) == 0;
}

// True when `addr` is owned by a Cocoa framework bundle (.../Foo.framework/...).
// A framework export can never satisfy an Android ELF import: those are
// ObjC/Swift app-facing implementations, not a Bionic substitute, and dlsym
// (RTLD_DEFAULT) walks EVERY image in the process. dladdr on a guest ELF
// mmap'd by arm64_native returns 0, so this only classifies host dylibs.
static bool host_addr_in_framework(void* addr) {
    Dl_info di;
    if (!addr || !dladdr(addr, &di) || !di.dli_fname) return false;
    return std::strstr(di.dli_fname, ".framework/") != nullptr;
}

// Defined in bionic_shim.cpp (same dylib). Returns a dl_iterate_phdr trampoline
// that lives INSIDE the libdl.so both /proc/self/maps and dl_iterate_phdr enumerate,
// so pairip's anti-hook "which module owns dl_iterate_phdr" check passes honestly;
// returns nullptr when MACAPK_PAIRIP_FAKE_LIBDL is unset (the default) or install failed.
// See docs/pairip-remaining-gate_2026-09-13.md.
extern "C" void* __macapk_pairip_fake_libdl_dlip();

// Look up one of OUR `__macapk_arm64_*` shim symbols.
//
// WHY NOT dlsym(RTLD_DEFAULT, ...). RTLD_DEFAULT walks the export trie of EVERY
// image in the process -- the JVM, AppKit, Metal, the whole shared-cache closure,
// several hundred images -- and for the names that MISS (almost all of them: the
// weak-local override probe below asks about every weak C++ definition an image
// has, and only a few dozen have a shim) it has to walk every one. Measured
// 2026-09-22 on libminecraftpe: ~1.5 s of every launch was spent in
// dlsym -> hasExportedSymbol -> trieWalk from these probes alone.
//
// Only this dylib defines the prefix (checked with `nm -gU`: 699 exports here, 0 in
// macapk-runtime, 0 in every translated app dylib), and this code is linked into
// it, so a handle opened RTLD_FIRST on OUR image answers exactly what RTLD_DEFAULT
// would, from one trie. MACAPK_SHIM_DLSYM_VERIFY=1 re-asks RTLD_DEFAULT on every
// lookup and reports any disagreement; if the handle cannot be obtained, this
// falls back to the old global lookup.
static void* macapk_shim_self_handle() {
    static void* h = []() -> void* {
        Dl_info di;
        if (!dladdr(reinterpret_cast<const void*>(&macapk_shim_self_handle), &di)
            || !di.dli_fname)
            return nullptr;
        return dlopen(di.dli_fname, RTLD_NOLOAD | RTLD_LAZY | RTLD_FIRST);
    }();
    return h;
}

// bionic_shim_vulkan.cpp: the guest libvulkan.so's symbol for `name`, or null
// when this app gets no Vulkan.
extern "C" void* __macapk_guest_vulkan_symbol(const char* name);

static void* macapk_shim_dlsym(const char* name) {
    void* h = macapk_shim_self_handle();
    void* p = h ? dlsym(h, name) : dlsym(RTLD_DEFAULT, name);
    static const bool verify = std::getenv("MACAPK_SHIM_DLSYM_VERIFY") != nullptr;
    if (verify) {
        void* g = dlsym(RTLD_DEFAULT, name);
        static std::atomic<long> n_checked{0}, n_bad{0};
        long c = n_checked.fetch_add(1) + 1;
        if (g != p) {
            long b = n_bad.fetch_add(1) + 1;
            if (b <= 20)
                std::fprintf(stderr, "[shim-dlsym] MISMATCH %s: self=%p global=%p\n",
                             name, p, g);
        }
        if ((c & 0xFFFF) == 0)
            std::fprintf(stderr, "[shim-dlsym] verify: %ld lookups, %ld mismatches\n",
                         c, n_bad.load());
    }
    return p;
}

// A libfmod image's imports resolve like every other image's. On a device there
// is one global lookup list (bionic linker/linker_soinfo.cpp SymbolLookupList:
// the global group, then the local group), so libfmod's `malloc` is the same
// process-wide libc malloc (bionic libc/bionic/malloc_common.cpp) and its
// `pthread_create` honours the attr FMOD built (pthread_create.cpp:163
// DETACHED, :282 the requested stack). FMOD's threads get the x18 bit / tid
// registry / t_is_guest from the trampoline, and the host-JVM attach room from
// MACAPK_GUEST_STACK_JVM_RESERVE in bionic_shim.cpp, which must stay ON (reserve=0
// hangs FMOD init: gate row L3).
//
// RETIRED 2026-10-02 (v1.0.1684): the "libfmod" image tag that bound five imports
// to __macapk_arm64_libfmod_* (a malloc family that had become plain std::malloc,
// and a pthread_create that dropped the attr and skipped the trampoline) is
// DELETED, with MACAPK_FMOD_GENERIC_ROUTING=0, which restored it. Its premise, a
// libfmod mixer UAF, was refuted on 2026-05-17. Removal condition met: clean
// protected sweeps on the default since v1.0.1657 and live audio init on the
// installed GD Lite / GD Meltdown / Minecraft stubs (bionic_shim.cpp tombstone
// where __macapk_arm64_libfmod_pthread_create was). A set knob is ignored and
// named once. Gate: debug/geh_fmod-image-tag-alloc-zone_gate.sh; evidence:
// docs/fmod-image-tag-retired_2026-09-27.md, docs/sound-retirements_2026-10-02.md.
static void macapk_note_fmod_tag_retired() {
    static std::atomic<bool> noted{false};
    if (noted.exchange(true)) return;
    if (std::getenv("MACAPK_FMOD_GENERIC_ROUTING"))
        std::fprintf(stderr,
            "[arm64-native] MACAPK_FMOD_GENERIC_ROUTING RETIRED and IGNORED: libfmod "
            "resolves like every other image (the \"libfmod\" image tag is deleted)\n");
}

// ---------------------------------------------------------------------------
// MACAPK_WEAK_LOCAL_SCOPE (default ON): a WEAK undefined import binds only to a
// definition in the importer's own DT_NEEDED tree, never to whatever unrelated
// app library happens to export the name.
//
// Bionic binds every UND import against one SymbolLookupList: the namespace's
// global group (DF_1_GLOBAL libraries; System.loadLibrary never loads an app
// library that way) followed by the importer's local group, the BFS of its
// DT_NEEDED tree (bionic linker/linker.cpp `SymbolLookupList
// lookup_list(global_group, local_group)`, linker_relocate.cpp lookup_symbol()).
// A weak miss there is 0 with no error. The flat walk over every loaded image
// below is wider than that, and for a weak import the difference is fatal:
// BoringSSL (bundled Conscrypt, libconscrypt_jni.so) imports `sdallocx` weak and
// calls it only when non-null; folly, linked into React Native's libjsi.so and
// libreactnative.so, EXPORTS a data object called `sdallocx` (a function-pointer
// variable). On a device conscrypt cannot see libjsi, so the slot is 0 and
// OPENSSL_free uses free(). Here the flat walk bound conscrypt's PLT slot to
// libjsi's .bss variable, and the first OPENSSL_free jumped into it: SIGBUS
// BUS_ADRALN in NativeCrypto.<clinit> on the Xbox app's first HTTPS request
// (x16 = the sdallocx GOT slot, pc = libjsi+0x676d0).
//
// Strong imports keep the flat walk: a strong miss is a load failure on a device,
// so widening there can only rescue a load, never change a working binding.
// The host steps above (shims, adapters, dlsym) are untouched, so a weak libc
// import still binds to our Bionic implementation exactly as before.
// =0 restores the flat walk for weak imports too.
static bool macapk_weak_local_scope() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEAK_LOCAL_SCOPE");
        return !(v && v[0] == '0');
    }();
    return on;
}

static const char* macapk_image_basename(const char* n) {
    if (!n) return "";
    const char* sl = std::strrchr(n, '/');
    return sl ? sl + 1 : n;
}

void* Image::weak_scope_lookup(const std::string& name) const {
    // BFS over the DT_NEEDED tree, rooted at this image (bionic's local group
    // order). A needed library that is not a loaded guest image is one we
    // answer from the host (libc, liblog, libGLESv2, ...) and has no guest
    // exports to search.
    std::vector<const Image*> group;
    std::vector<std::string> queue(needed_.begin(), needed_.end());
    std::unordered_set<std::string> seen(queue.begin(), queue.end());
    for (size_t qi = 0; qi < queue.size(); ++qi) {
        for (Image* img : loaded_images()) {
            if (img == this || queue[qi] != macapk_image_basename(img->name()))
                continue;
            group.push_back(img);
            for (const auto& n : img->needed())
                if (seen.insert(n).second) queue.push_back(n);
            break;
        }
    }
    for (const Image* img : group)
        if (void* p = img->lookup(name.c_str())) return p;
    // Say so when the flat walk would have bound it elsewhere, so an app that
    // somehow relied on the old binding is one grep away.
    for (Image* img : loaded_images()) {
        if (img == this || !img->lookup(name.c_str())) continue;
        static std::atomic<int> n_logged{0};
        if (n_logged.fetch_add(1) < 64)
            std::fprintf(stderr,
                "[arm64-native] %s: weak '%s' left 0 -- defined only in %s, "
                "outside this library's DT_NEEDED tree (MACAPK_WEAK_LOCAL_SCOPE)\n",
                dbg_name_, name.c_str(), img->name());
        break;
    }
    return nullptr;
}

void* Image::resolve_external(const std::string& name, uint8_t bind) {
    // MACAPK_PAIRIP_FAKE_LIBDL (default OFF): hand pairip a dl_iterate_phdr that
    // resolves to the fake libdl.so region (a genuine trampoline at libdl+0x100a4)
    // so its owner-check finds the pointer inside libdl instead of a raw macOS dylib
    // address. Gated on a static env read so the OFF path costs only a predicted
    // branch per symbol (resolve_external is on the relocation hot path).
    {
        static const bool s_fake_libdl = __macapk_pairip_surface_on("MACAPK_PAIRIP_FAKE_LIBDL");
        if (s_fake_libdl && name == "dl_iterate_phdr") {
            if (void* t = __macapk_pairip_fake_libdl_dlip()) return t;
        }
    }
    // Per-image override (currently used for libfmod's malloc family).
    // The loader's image-specific symbol form is
    //   `__macapk_arm64_<image_tag>_<name>`
    // where <image_tag> is a short stable identifier derived from the
    // image's soname (e.g. "libunity"). If that symbol exists in the
    // shim, it wins over both `__macapk_arm64_<name>` and the
    // libSystem fallback. We use this to route libfmod's
    // malloc/calloc/realloc/free into a dedicated allocator that
    // doesn't share chunks with libcocos / QuartzCore / libobjc, so
    // libfmod's documented use-after-free dangling writes corrupt
    // libfmod-only memory instead of the macOS allocator's free-list
    // metadata.
    //
    // The image_tag is set for SPECIFIC images that route their
    // libc/libstdc++ calls through dedicated shim variants:
    //   - ("libfmod" was one until 2026-10-02; deleted, see the note above
    //                this function.)
    //   - "libcocos2dcpp": dedicated malloc_zone_t for libcocos's
    //                allocations, with a NEVER-RECYCLE free policy.
    //                The 14th-pass libfmod UAF investigation showed
    //                the dangling write goes into memory libfmod was
    //                HANDED (cocos-allocated), not libfmod's own
    //                freed chunks -- so routing libcocos to a
    //                never-recycle zone is the structural fix: cocos's
    //                old freed memory is held forever instead of
    //                being recycled into new inflate_state allocations
    //                that libfmod's stale pointers then scribble.
    const char* image_tag = nullptr;
    if (dbg_name_) {
        if (std::strstr(dbg_name_, "libfmod") != nullptr) {
            macapk_note_fmod_tag_retired();
        } else if (std::strstr(dbg_name_, "libcocos2dcpp") != nullptr) {
            image_tag = "libcocos2dcpp";
        } else if (std::strstr(dbg_name_, "libunity") != nullptr) {
            // v1.0.156: Unity's DynamicHeapAllocator packs metadata in
            // pointer upper bits and assumes addresses fit in 16 GiB
            // (= 0x4_0000_0000). This is well-documented as the cause
            // of "Using memory addresses from more than 16GB of memory"
            // crashes on Android 11 (Scudo allocator gave high addrs).
            // macOS arm64 ASLR routinely returns addresses in the same
            // range, breaking Unity. Route libunity's heap calls
            // through dedicated shims that keep returns < 16 GiB.
            image_tag = "libunity";
        }
    }
    if (image_tag) {
        // v1.0.162: the first time we resolve a libunity symbol, tell the
        // Cocoa bridge a Unity app is running so drawInMTKView allocates
        // the dedicated Unity render layer (on the main thread, before the
        // surfaceCreated Semaphore wait). Engine-level signal, once per
        // process, no per-APK code.
        if (std::strcmp(image_tag, "libunity") == 0) {
            static std::atomic<bool> unity_noted{false};
            if (!unity_noted.exchange(true)) {
                if (void* f = dlsym(RTLD_DEFAULT, "__macapk_note_unity_loaded"))
                    reinterpret_cast<void(*)()>(f)();
                // v1.0.173: tell the bionic shim a Unity app is running so its
                // sigaction shim retains Mac.apk's SIGSEGV recovery handler
                // (Unity's own crash reporter would otherwise abort on a
                // recoverable fault ~50s into rendering).
                if (void* g = dlsym(RTLD_DEFAULT, "__macapk_arm64_mark_unity"))
                    reinterpret_cast<void(*)()>(g)();
            }
        }
        std::string per_image =
            "__macapk_arm64_" + std::string(image_tag) + "_" + name;
        if (void* p = macapk_shim_dlsym(per_image.c_str())) {
            // v1.0.156 diagnostic: log when per-image override picks
            // up libunity-routed allocator shims so we can confirm the
            // route is wired during libunity load.
            if (std::strcmp(image_tag, "libunity") == 0) {
                static std::atomic<int> n{0};
                int c = n.fetch_add(1);
                if (c < 32) {
                    std::fprintf(stderr,
                        "[arm64-native] libunity per-image resolve: %s -> %s @ %p\n",
                        name.c_str(), per_image.c_str(), p);
                }
            }
            return p;
        }
    }
    // First try libmacapk-bionic-shim's *arm64-native* Bionic-ABI
    // shim under the `__macapk_arm64_<name>` prefix. We deliberately
    // do NOT fall through to the arm32 lifter's `__macapk_shim_`
    // prefix here: those shims use a 4-i32 cross-call ABI plus a
    // guest_base address-space mapping, neither of which apply when
    // the call comes from natively-executing arm64 code. A future
    // expansion will populate `__macapk_arm64_<bionic name>` shims
    // for each Bionic API with a layout that differs from libSystem
    // (pthread_once_t, pthread_mutex_t, etc.).
    std::string a64 = "__macapk_arm64_" + name;
    // MACAPK_FMOD_BIND_BY_NAME (default ON, see macapk_fmod_bind_by_name): no
    // FMOD test at all, so fmod_name is false, skip_fmod_shim is false, and an
    // FMOD import takes the same ladder as every other name. Everything from
    // here to the __cxa_throw probe below is the =0 routing, kept verbatim.
    const bool fmod_legacy_routing = !macapk_fmod_bind_by_name();
    // FMOD real-vs-stub routing (MACAPK_FMOD_BIND_BY_NAME=0 only). Mac.apk
    // ships a partial FMOD *stub* layer (bionic_shim.cpp
    // `__macapk_fmodstub_<FMOD_name>`, named `__macapk_arm64_<FMOD_name>`
    // until 2026-09-27 -> fake FMOD_OK + fake handles) built for GD/cocos,
    // whose bundled FMOD 2.0.0.x was believed to have a mixer-thread UAF
    // (refuted 2026-05-17). That stub set is INCOMPLETE: it stubs
    // FMOD_System_Create/init/
    // createSound/... but NOT getNumDrivers/getDriverInfo/getMasterChannelGroup/
    // createChannelGroup. An engine (MCPE) that calls the un-stubbed methods gets
    // a FAKE system handle from the stubbed Create, then the un-stubbed calls fall
    // through to REAL libfmod operating on that fake handle -> its audio-backend
    // bring-up fails -> a std::function backend delegate is left empty -> crash.
    //
    // Two ways to force every FMOD name on this image to real, consistently:
    // (1) MACAPK_FMOD_REAL, a manual env override (forces EVERY loaded image,
    // still useful for MACAPK_FMOD_TRACE diagnostics); (2) fmod_needs_real_,
    // measured automatically per image by scan_fmod_coverage() before any
    // relocation is applied (see its banner above Image::apply_relocations)
    // -- true iff THIS image's own FMOD imports include at least one name
    // our stub table does not cover, which is exactly the condition under
    // which the fake-handle mix above would otherwise occur. Measured
    // 2026-07-31: GD Lite needs exactly the stubbed 28 (flag computes
    // false, byte-identical to the historical default); GD Meltdown needs
    // 66 (41 uncovered, flag true, verified 150 s/60 fps/real audio/0
    // crashes); MCPE needs 60 (39 uncovered, flag true, matches its
    // extensively-verified MACAPK_FMOD_REAL=1 behavior). No package name,
    // no vaddr: a future app nobody has looked at gets the same answer.
    const bool fmod_name = fmod_legacy_routing &&
                           (name.rfind("_ZN4FMOD", 0) == 0 ||
                            name.rfind("FMOD_System", 0) == 0 ||
                            name.rfind("FMOD_", 0) == 0);
    const bool skip_fmod_shim = fmod_name &&
        (std::getenv("MACAPK_FMOD_REAL") || fmod_needs_real_);
    // FMOD boundary trace (see the forwarder block above resolve_external).
    // Only under MACAPK_FMOD_TRACE, and only for the real-FMOD path: wrap the
    // decisive entry points with a logging forwarder that tail-calls the REAL
    // libfmod symbol (resolved via the loaded-image lookup, step-4 semantics).
    if (skip_fmod_shim && std::getenv("MACAPK_FMOD_TRACE")) {
        auto real_of = [&](const std::string& n) -> void* {
            for (Image* img : loaded_images()) {
                if (img == this) continue;
                if (void* p = img->lookup(n.c_str())) return p;
            }
            return nullptr;
        };
        if (name == "_ZN4FMOD6System11createSoundEPKcjP22FMOD_CREATESOUNDEXINFOPPNS_5SoundE") {
            g_real_createSound = (fmod_create_fn)real_of(name);
            if (g_real_createSound) return (void*)&macapk_fmodtrace_createSound;
        } else if (name == "_ZN4FMOD6System12createStreamEPKcjP22FMOD_CREATESOUNDEXINFOPPNS_5SoundE") {
            g_real_createStream = (fmod_create_fn)real_of(name);
            if (g_real_createStream) return (void*)&macapk_fmodtrace_createStream;
        } else if (name == "_ZN4FMOD6System9playSoundEPNS_5SoundEPNS_12ChannelGroupEbPPNS_7ChannelE") {
            g_real_playSound = (fmod_play_fn)real_of(name);
            if (g_real_playSound) return (void*)&macapk_fmodtrace_playSound;
        } else if (name == "_ZN4FMOD14ChannelControl9setVolumeEf") {
            g_real_setVolume = (fmod_setvol_fn)real_of(name);
            if (g_real_setVolume) return (void*)&macapk_fmodtrace_setVolume;
        } else if (name == "_ZN4FMOD14ChannelControl9setPausedEb") {
            g_real_setPaused = (fmod_setpaused_fn)real_of(name);
            if (g_real_setPaused) return (void*)&macapk_fmodtrace_setPaused;
        } else if (name == "_ZN4FMOD14ChannelControl9isPlayingEPb") {
            g_real_isPlaying = (fmod_isplaying_fn)real_of(name);
            if (g_real_isPlaying) return (void*)&macapk_fmodtrace_isPlaying;
        }
    }
    // =0 only. The stub table is exported as __macapk_fmodstub_<name> now
    // (bionic_shim.cpp), outside the __macapk_arm64_ probe below, so look it up
    // here under the condition the old a64 probe found it (an FMOD name that is
    // not being routed to real). A miss falls through exactly as that probe's
    // miss did: no __macapk_arm64_<FMOD name> is exported any more, so the
    // a64 probe below misses for it too.
    if (fmod_name && !skip_fmod_shim) {
        std::string stub = "__macapk_fmodstub_" + name;
        if (void* p = macapk_shim_dlsym(stub.c_str())) return p;
    }
    // __cxa_throw normally resolves DIRECTLY to host libc++abi (register_eh_frames
    // makes cross-frame unwinding work). Route it through our transparent logging
    // shim ONLY when the throw-trace diagnostic is explicitly enabled, so
    // production exception handling stays byte-identical (the shim just forwards).
    if (!skip_fmod_shim &&
        !(name == "__cxa_throw" && !std::getenv("MACAPK_CXA_THROW_TRACE"))) {
        if (void* p = macapk_shim_dlsym(a64.c_str())) return p;
    }

    // Then the loader's own Bionic ABI adapters, for the names where libSystem
    // exports a HOMONYM with a different signature (sendfile, ptrace,
    // pthread_setname_np) or exports nothing at all (cacheflush). This has to
    // come before the bare-name dlsym below: once that fallback writes Darwin's
    // address into the GOT slot, the guest calls the wrong function forever.
    // See the adapter block above resolve_external for the paired prototypes.
    if (void* p = macapk_bionic_adapter(name)) return p;

    // Then a vk* import of a library linked against libvulkan.so: the guest's
    // libvulkan is MoltenVK plus the VK_KHR_android_surface translation
    // (bionic_shim_vulkan.cpp), the same resolver dlsym(<libvulkan.so>, name)
    // uses, so a direct call and a dlsym'd pointer reach the same function.
    // GitHub #17: MapLibre's renderer links -lvulkan and calls
    // vkCreateInstance directly; before this every vk* import fell to the
    // RTLD_DEFAULT lookup below, which cannot see MoltenVK (opened RTLD_LOCAL,
    // and only on the first libvulkan dlopen), so the binder trapped them all.
    // This must come BEFORE that lookup: a globally visible Khronos loader
    // (ANGLE's, under MACAPK_ANGLE_BACKEND=vulkan) also exports vkCreateInstance,
    // and binding the guest to it would skip the surface translation. Keyed on
    // the importer's DT_NEEDED, as bionic resolves an import from the library's
    // own dependencies; a vk* name in a library that does not link libvulkan
    // (volk-style function-pointer globals) is left to the usual lookups. When
    // this app gets no Vulkan the resolver answers null and the lookups below
    // run exactly as before.
    if (name.size() > 2 && name[0] == 'v' && name[1] == 'k' &&
        std::find(needed_.begin(), needed_.end(), "libvulkan.so") != needed_.end()) {
        if (void* p = __macapk_guest_vulkan_symbol(name.c_str())) return p;
    }

    // Then any host-process symbol (libSystem libc/libm/libpthread,
    // libc++, ANGLE, the shim's directly-exported Bionic names like
    // AAssetManager_*). dlsym(RTLD_DEFAULT) walks every loaded dylib.
    //
    // EXCEPTION -- guest-provided crypto wins over Apple's private crypto
    // dylibs. Once Network.framework / CFNetwork is loaded (the runtime pulls
    // them in), dlsym(RTLD_DEFAULT) resolves EVP_*/SSL_*/X509_*/... into
    // /usr/lib/libboringssl.dylib and /usr/lib/libcrypto.NN.dylib -- and a
    // guest that ships its OWN crypto must bind to that, not to Apple's. The
    // host's struct layout and vintage differ, and the host surface is itself
    // split across two providers of different vintage
    // (EVP_CIPHER_CTX_cleanup / _set_padding / _ctrl -> libboringssl, but
    // EVP_CIPHER_CTX_new / EVP_CipherInit_ex / EVP_CipherUpdate ->
    // libcrypto.46 = OpenSSL 3.x), so a context allocated by one provider and
    // torn down by another corrupts even between two host libs. Measured on
    // TikTok: a libttcrypto.so-shaped EVP_CIPHER_CTX bound to the host's
    // EVP_CIPHER_CTX_cleanup SEGVs (EXC_BAD_ACCESS, KERN_INVALID_ADDRESS at a
    // mis-offset field) ~90 s into init, before any Activity. TikTok bundles a
    // COMPLETE libttcrypto.so (defines the whole EVP_CIPHER_CTX lifecycle) and
    // it is a direct DT_NEEDED of every crypto importer, so preloadDependencies
    // (AppClassLoader) loads it BEFORE those importers bind -- the guest lookup
    // below finds it. So: when a host crypto symbol is about to win, first
    // prefer a loaded guest ELF image's definition. If none exists (an app that
    // ships no crypto and expects Android's system BoringSSL, which we do not
    // reimplement) we keep the historical host fallback rather than break the
    // load -- zero regression for anything that works today. Generic: keyed on
    // the owning dylib, no package name; the game fleet imports zero crypto
    // symbols, so it is unaffected either way.
    if (void* p = dlsym(RTLD_DEFAULT, name.c_str())) {
        // A framework-owned hit is an accident of the global dlsym walk, not a
        // resolution. Prefer any loaded guest ELF definition instead; without
        // this, an import whose name collides with a framework export executes
        // Apple code under the Android ABI. Observed with an obfuscated
        // single-letter import: the framework happened to export the same
        // name, so the first call jumped into it and died on SIGILL. /usr/lib
        // hits keep their priority deliberately -- __cxa_throw and _Unwind_*
        // must stay on the host libc++abi for cross-frame unwinding.
        if (host_addr_in_framework(p)) {
            for (Image* img : loaded_images()) {
                if (img == this) continue;
                if (void* g = img->lookup(name.c_str())) {
                    static std::atomic<int> n_fw{0};
                    if (n_fw.fetch_add(1) < 64) {
                        Dl_info di;
                        std::fprintf(stderr,
                            "[arm64-native] %s: '%s' -> guest %s "
                            "(refused host framework %s)\n",
                            dbg_name_, name.c_str(), img->name(),
                            (dladdr(p, &di) && di.dli_fname) ? di.dli_fname
                                                             : "?framework?");
                    }
                    return g;
                }
            }
        }
        if (host_dylib_is_private_crypto(p)) {
            for (Image* img : loaded_images()) {
                if (img == this) continue;
                if (void* g = img->lookup(name.c_str())) {
                    static std::atomic<int> n_guest{0};
                    if (n_guest.fetch_add(1) < 128)
                        std::fprintf(stderr,
                            "[arm64-native] %s: '%s' -> guest %s (preferred "
                            "over Apple's private crypto dylib)\n",
                            dbg_name_, name.c_str(), img->name());
                    return g;
                }
            }
            // No guest provider: fall back to the host symbol (unchanged legacy
            // behaviour). Log once so the vintage/layout risk is visible if such
            // an app later misbehaves in a crypto path.
            static std::atomic<int> n_hostfb{0};
            if (n_hostfb.fetch_add(1) < 64) {
                Dl_info di;
                std::fprintf(stderr,
                    "[arm64-native] %s: '%s' has no guest provider; falling back "
                    "to host %s (ABI/vintage risk)\n",
                    dbg_name_, name.c_str(),
                    (dladdr(p, &di) && di.dli_fname) ? di.dli_fname : "?host?");
            }
        }
        if (std::getenv("MACAPK_AUDIO_TRACE")
            && name.find("IID") != std::string::npos)
            std::fprintf(stderr, "[arm64-native] resolved IID '%s' -> %p\n",
                         name.c_str(), p);
        return p;
    }

    // A WEAK import binds only inside this image's own DT_NEEDED tree, the
    // way bionic scopes it (weak_scope_lookup). A miss there is a 0 slot.
    if (bind == STB_WEAK && macapk_weak_local_scope())
        return weak_scope_lookup(name);

    // Then any symbol defined by a previously-loaded ELF Image. This
    // is the Android linker's "DT_NEEDED chain" behaviour: a dependent
    // library (libopenal, libOpenGD) can reach symbols defined in
    // libc++_shared. We can't use dlsym(RTLD_DEFAULT) for these
    // because the Mach-O wrappers only export a small set of
    // trampolines, not the full ELF symbol table. Skip ourselves to
    // avoid an infinite recursion if a symbol is genuinely missing.
    for (Image* img : loaded_images()) {
        if (img == this) continue;
        if (void* p = img->lookup(name.c_str())) return p;
    }

    // Weak undefined: zero is a valid resolution.
    if (bind == STB_WEAK) return nullptr;

    // LFS alias fallback: macOS libc is already 64-bit-offset, so a Bionic `*64` variant we
    // do not stub (open64/lseek64/pread64/mmap64/...) is equivalent to its base name. Retry
    // once (the bare name never re-ends in "64", so this bottoms out immediately).
    if (name.size() > 2 && name[name.size()-2] == '6' && name[name.size()-1] == '4') {
        std::string bare = name.substr(0, name.size() - 2);
        if (!(bare.size() > 2 && bare[bare.size()-2] == '6' && bare[bare.size()-1] == '4'))
            if (void* a = resolve_external(bare, bind)) return a;
    }

    // Hard miss. Record it so macapk_unresolved_trap() can name the
    // image's missing imports if one of them is ever CALLED, then log.
    // A non-weak PLT (JUMP_SLOT) slot for this name will be redirected
    // to the trap by trap_if_unresolved_func() (called from
    // apply_relocations), so the first call lands in a named diagnostic
    // instead of crashing at pc=0. Data globals stay NULL here.
    record_unresolved(dbg_name_, name);
    std::fprintf(stderr,
        "[arm64-native] %s: unresolved external '%s' (bind=%u)\n",
        dbg_name_, name.c_str(), unsigned(bind));
    return nullptr;
}

void* Image::resolve_external_silent(const std::string& name,
                                       uint8_t /*bind*/) {
    // RESTRICTED interposition: only honour our `__macapk_arm64_`
    // prefixed shims here. Do NOT fall through to dlsym or
    // cross-Image lookup: those would match unrelated symbols (e.g.
    // libSystem's `__cxa_guard_acquire`) and replace the local
    // library's weak C++ runtime support with the wrong ABI.
    //
    // We only want to interpose when WE explicitly provide a
    // replacement, signalled by the presence of a shim symbol with
    // our prefix.
    const char* image_tag = nullptr;
    if (dbg_name_) {
        if (std::strstr(dbg_name_, "libcocos2dcpp") != nullptr) {
            image_tag = "libcocos2dcpp";
        } else if (std::strstr(dbg_name_, "libunity") != nullptr) {
            // v1.0.156: Unity's DynamicHeapAllocator packs metadata in
            // pointer upper bits and assumes addresses fit in 16 GiB
            // (= 0x4_0000_0000). This is well-documented as the cause
            // of "Using memory addresses from more than 16GB of memory"
            // crashes on Android 11 (Scudo allocator gave high addrs).
            // macOS arm64 ASLR routinely returns addresses in the same
            // range, breaking Unity. Route libunity's heap calls
            // through dedicated shims that keep returns < 16 GiB.
            image_tag = "libunity";
        }
    }
    if (image_tag) {
        std::string per_image =
            "__macapk_arm64_" + std::string(image_tag) + "_" + name;
        if (void* p = macapk_shim_dlsym(per_image.c_str())) return p;
    }
    std::string a64 = "__macapk_arm64_" + name;
    if (void* p = macapk_shim_dlsym(a64.c_str())) return p;
    // MACAPK_FMOD_BIND_BY_NAME=0 only. The FMOD stub table used to sit in the
    // __macapk_arm64_ namespace probed just above, so a WEAK FMOD definition
    // was interposed by a stub whatever resolve_external's routing said (this
    // path never looked at skip_fmod_shim). Restored here for the =0 A/B; under
    // the default the renamed table (__macapk_fmodstub_) is out of reach, and a
    // weak FMOD definition keeps its own body, as on a device.
    if (!macapk_fmod_bind_by_name() &&
        (name.rfind("_ZN4FMOD", 0) == 0 || name.rfind("FMOD_", 0) == 0)) {
        std::string stub = "__macapk_fmodstub_" + name;
        if (void* p = macapk_shim_dlsym(stub.c_str())) return p;
    }
    return nullptr;
}

// --- Unresolved-import trap (replaces the old NULL-slot pc=0 crash) ------
//
// Per CLAUDE.md "Missing shims are silent killers": when an Android .so
// imports a Bionic/NDK symbol we have not shimmed, the old loader left
// the GOT slot NULL, so the first CALL branched to address 0 and the
// process died at pc=0 with NO indication of WHICH symbol -- you had to
// post-mortem an .ips to recover the name. We now point unresolved
// non-weak PLT (JUMP_SLOT) slots at this trap, so the call lands here
// with the caller's return address intact. We localize the call site to
// a loaded image + offset (which disassembles to `bl <name>@plt`, naming
// the symbol) and print the image's recorded unresolved-import list,
// then abort with a clear message.
//
// This is faithful to Android: a real device fails at dlopen()/link time
// with "cannot locate symbol ... referenced by ...". Under our lazy GOT
// binding the honest equivalent is a clean, named abort on first use --
// NOT a silent no-op (which would feed the engine garbage and break the
// "apps must think they're on Android" contract).
//
// Scope is deliberately JUMP_SLOT-only: a PLT slot is reached ONLY by
// `blr` through the PLT, never read as data or null-checked, so swapping
// NULL -> trap cannot regress any currently-working path (a NULL PLT slot
// only ever crashed at pc=0 anyway). Unresolved DATA globals
// (GLOB_DAT/ABS64) keep honest NULL.
//
// MACAPK_UNRESOLVED_CONTINUE=1 (default OFF) downgrades the abort to
// "log once, return 0" so a debugging session can walk past one missing
// symbol to discover the next. Generic diagnostic toggle, never default.

std::mutex& unresolved_registry_mutex() {
    static std::mutex m;
    return m;
}
std::vector<std::pair<std::string, std::string>>& unresolved_registry() {
    // (image soname, missing symbol name)
    static std::vector<std::pair<std::string, std::string>> v;
    return v;
}
void record_unresolved(const char* img, const std::string& sym) {
    std::lock_guard<std::mutex> lk(unresolved_registry_mutex());
    unresolved_registry().emplace_back(img ? img : "<null>", sym);
}

__attribute__((noinline)) long macapk_unresolved_trap(void) {
    void* ra = __builtin_return_address(0);
    const char* img_name = "<unknown-image>";
    uintptr_t img_off = 0;
    for (Image* img : loaded_images()) {
        const uint8_t* b = img->base();
        if (b && reinterpret_cast<const uint8_t*>(ra) >= b
              && reinterpret_cast<const uint8_t*>(ra) < b + img->mapped_size()) {
            img_name = img->name();
            img_off  = reinterpret_cast<uintptr_t>(ra)
                     - reinterpret_cast<uintptr_t>(b);
            break;
        }
    }

    static std::atomic<bool> continue_inited{false};
    static bool continue_mode = false;
    if (!continue_inited.exchange(true)) {
        continue_mode = (std::getenv("MACAPK_UNRESOLVED_CONTINUE") != nullptr);
    }

    static std::atomic<int> n_logged{0};
    if (n_logged.fetch_add(1) < 64) {
        std::fprintf(stderr,
            "[arm64-native] *** UNRESOLVED NATIVE SYMBOL CALLED ***\n"
            "    call site: %s+0x%llx  (disassemble this offset: the "
            "instruction at lr-4 is a `bl <symbol>@plt`)\n",
            img_name,
            (unsigned long long)(img_off >= 4 ? img_off - 4 : img_off));
        // The called symbol is one of the unresolved imports we recorded
        // for this image at load time -- print them so it can be named
        // without an .ips post-mortem.
        std::lock_guard<std::mutex> lk(unresolved_registry_mutex());
        int shown = 0;
        for (auto& e : unresolved_registry()) {
            if (e.first == img_name) {
                std::fprintf(stderr, "      unresolved in %s: %s\n",
                             img_name, e.second.c_str());
                if (++shown >= 40) {
                    std::fprintf(stderr, "      ... (more)\n");
                    break;
                }
            }
        }
    }

    if (continue_mode) return 0;
    std::fprintf(stderr,
        "[arm64-native] aborting: the app called a native symbol Mac.apk "
        "does not implement. Implement it as a __macapk_arm64_<name> shim "
        "(see CLAUDE.md \"Missing shims are silent killers\"). Set "
        "MACAPK_UNRESOLVED_CONTINUE=1 to return 0 and continue for "
        "diagnosis.\n");
    std::abort();
}

uint64_t trap_if_unresolved_func(uint64_t value, uint8_t st_info,
                                 uint32_t rtype) {
    if (value != 0) return value;                 // resolved -- unchanged
    if (sym_bind(st_info) == STB_WEAK) return 0;  // weak undef == 0 (ELF)
    // JUMP_SLOT only: a PLT slot is only ever reached by `blr`, so a trap
    // here cannot regress a null-checked data read. GLOB_DAT/ABS64 data
    // (and function-address-taken) globals keep honest NULL.
    if (rtype != R_AARCH64_JUMP_SLOT) return 0;
    return reinterpret_cast<uint64_t>(&macapk_unresolved_trap);
}

// PT_TLS -> register this image's TLS template with the per-thread TLS
// allocator (bionic_shim.cpp), capturing the 1-based module id used by the
// DTPMOD64/DTPREL64 relocs. Bionic: each ELF TLS module gets a 1-based id;
// the template is the PT_TLS segment (p_filesz = .tdata init image,
// p_memsz = tdata+tbss, p_align). Per-thread blocks are lazily allocated by
// __macapk_arm64___tls_get_addr. macOS has no usable Bionic thread pointer
// (XNU overwrites TPIDR_EL0 with per-CPU info every context switch), so
// general-dynamic (function-call) TLS is the only thread-pointer-independent
// model we can serve. See docs/arm64-tls-relocs-contract_2026-06-21.md.
void Image::parse_and_register_tls() {
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_TLS) continue;
        const void* init_ptr = (ph.p_filesz > 0)
            ? reinterpret_cast<const void*>(base_ + ph.p_vaddr) : nullptr;
        tls_module_id_ = __macapk_arm64_tls_register_module(
            init_ptr, ph.p_filesz, ph.p_memsz,
            ph.p_align ? ph.p_align : 1);
        std::fprintf(stderr,
            "[arm64-native] %s: PT_TLS module id=%zu (filesz=%llu memsz=%llu "
            "align=%llu)\n",
            dbg_name_, tls_module_id_,
            (unsigned long long)ph.p_filesz,
            (unsigned long long)ph.p_memsz,
            (unsigned long long)ph.p_align);
        return;  // at most one PT_TLS per image
    }
}

// ---------------------------------------------------------------------------
// FMOD auto-routing pre-pass (2026-07-31, lane FMOD investigation).
//
// (2026-09-27) RUNS ONLY UNDER MACAPK_FMOD_BIND_BY_NAME=0: apply_relocations
// skips it by default, because an FMOD import binds by name like any other
// (see macapk_fmod_bind_by_name). The stub table it measures coverage against
// is now exported as __macapk_fmodstub_<name>; read "__macapk_arm64_<name>"
// below as that. Kept verbatim for the =0 A/B except for the probe name and
// two log lines that used to be false: "FMOD -> REAL by default" and "FMOD
// auto-routing -> REAL (this image's FMOD imports exceed ... coverage)" both
// printed for EVERY Stage-6 image, including ones importing no FMOD name at
// all (libunity, libil2cpp, libgdx-box2d, libgame, libfmod itself, ...), and the
// second claimed an exceeded coverage that had not been measured. Each now
// prints only when it is true.
//
// See the FMOD-routing block inside resolve_external() below for the
// mechanism this feeds, and docs/fmod-faithfulness_2026-07-31.md for the
// measured numbers that motivated it. One-line version: the
// __macapk_arm64_<name> FMOD stub table (bionic_shim.cpp) was reverse-
// engineered from ONE app's exact import list (Geometry Dash Lite, 28
// symbols) to dodge a documented libfmod 2.0.0.x mixer-thread UAF. Measured
// this session: GD Lite needs exactly those 28 (0 missing); Geometry Dash
// Meltdown needs 66 (41 missing, incl. createChannelGroup/getCPUUsage/...);
// Minecraft Bedrock needs 60 (39 missing, incl. getNumDrivers/
// getMasterChannelGroup/getDriverInfo/createChannelGroup -- exactly the
// names the original bad_function_call diagnosis named). resolve_external's
// own fallthrough (stub dlsym miss -> host dlsym miss -> loaded-image
// lookup) ALREADY sends every uncovered name straight to the real, loaded
// libfmod.so on the plain default path, with no flag involved at all -- so
// an app whose needs exceed the stub table gets a SILENT split state today:
// the handle-minting calls (FMOD_System_Create / System::init, both stub-
// covered) hand back a fake sentinel, while everything the stub doesn't
// cover already runs for-real against that fake handle. That mix, not
// "real FMOD" per se, is the actual bad_function_call mechanism.
// MACAPK_FMOD_REAL is the existing escape hatch: force EVERY FMOD name on
// EVERY image to real, uniformly, so the handle and the calls on it agree.
// It works (extensively verified for MCPE across many sessions), but it is
// a human-curated env var -- exactly the "gated for one app" shape the
// project is trying to eliminate, since a future app nobody has looked at
// yet would silently inherit the same fake-handle mix with no flag set for
// it.
//
// This pass replaces the human curation with a per-image measurement: scan
// THIS image's own relocation table once, before any entry is applied, for
// external (st_shndx==0) symbols whose name matches the FMOD shape; look
// each one up against the stub table (a plain dlsym on
// "__macapk_arm64_"+name, the exact check resolve_external already does per
// call, just run once up front here). If every FMOD name this image needs
// is stub-covered (GD Lite, exactly: 28/28), the flag stays false and
// behavior is byte-identical to today's default -- still all-stub, still
// dodging the libfmod UAF the stub exists for. If any name is NOT covered
// (GD Meltdown 25/66 covered; MCPE 21/60 covered), the flag goes true and
// EVERY FMOD name on this image routes to real, so the handle the app holds
// matches the calls it makes on it. Verified directly this session: GD Lite
// unaffected (flag computes false, matches its measured 28/28); GD Meltdown
// under forced-real ran 150 s at 60 fps with real createSound/createStream/
// playSound succeeding (menuLoop.mp3 streaming, unpaused, vol 1.0) and zero
// crashes -- a strict improvement over today's silent fake/real mix. MCPE's
// need for real FMOD is the pre-existing, extensively-verified case this
// pass just stops requiring a human to remember to ask for.
//
// No package name, no vaddr, no engine sniff: purely a function of this
// image's own DT_SYMTAB/relocation contents, so a future app neither GD nor
// MCPE gets the same correct answer automatically. MACAPK_FMOD_REAL is left
// in place as a manual override (still forces every image to real,
// including one this pass would have left on stubs -- useful for
// MACAPK_FMOD_TRACE diagnostics); this pass only changes the ABSENT-flag
// default.
void Image::scan_fmod_coverage() {
    auto is_fmod_name = [](const char* n) -> bool {
        return std::strncmp(n, "_ZN4FMOD", 8) == 0 ||
               std::strncmp(n, "FMOD_System", 11) == 0 ||
               std::strncmp(n, "FMOD_", 5) == 0;
    };
    // Per-name memo: the same import recurs across many PLT/GOT slots
    // referencing the same strtab string. This pass runs once per image
    // whether or not it touches FMOD at all (the overwhelming majority of
    // Stage-6 images do not), so the fast path is the is_fmod_name() ==
    // false check on every entry; dlsym is only paid for the handful of
    // DISTINCT FMOD-shaped names an FMOD-using image actually carries.
    std::unordered_map<const char*, bool> memo;   // name -> covered
    bool any_fmod_import = false;   // for the log lines only
    auto found_uncovered = [&](const Elf64_Rela& r) -> bool {
        const auto& sym = symtab_[reloc_sym(r.r_info)];
        if (sym.st_shndx != 0) return false;   // locally defined, not an import
        const char* name = strtab_ + sym.st_name;
        if (!is_fmod_name(name)) return false;
        any_fmod_import = true;
        auto it = memo.find(name);
        if (it != memo.end()) return !it->second;
        std::string stub = "__macapk_fmodstub_" + std::string(name);
        bool covered = macapk_shim_dlsym(stub.c_str()) != nullptr;
        memo.emplace(name, covered);
        return !covered;
    };
    for (size_t i = 0; i < rela_count_; ++i) {
        if (found_uncovered(rela_[i])) { fmod_needs_real_ = true; break; }
    }
    if (!fmod_needs_real_) {
        for (size_t i = 0; i < pltrel_count_; ++i) {
            if (found_uncovered(pltrel_[i])) { fmod_needs_real_ = true; break; }
        }
    }
    // True only when the coverage test itself set the flag (an uncovered FMOD
    // import was found); the default-real flip below is a different reason.
    const bool coverage_exceeded = fmod_needs_real_;
    // ⚠⚠ DEFAULT FLIPPED 2026-08-30 (v1.0.1111): every FMOD-importing image now
    // routes to REAL libfmod, not just the ones whose imports exceed the stub
    // table.
    //
    // The coverage test asked "does our 28-entry stub table cover every FMOD
    // name this image imports". That is the WRONG QUESTION for audio: a COMPLETE
    // set of SILENT stubs passes it perfectly. GD Lite imports exactly the
    // stubbed 28 -- the table was literally built from its import list -- so it
    // computed false and was silent for months while the measurement reported
    // success. Coverage is not audio.
    //
    // Real Android has no FMOD stub layer, so real libfmod IS the faithful
    // answer. MACAPK_FMOD_STUB=1 restores the old coverage-gated behaviour for
    // an A/B.
    // The flag is still set for every image, as before (it is only ever
    // consulted for an FMOD-named import, so on an image without one it changes
    // nothing); only the log lines are now limited to the images they describe.
    if (!fmod_needs_real_ && !std::getenv("MACAPK_FMOD_STUB")) {
        fmod_needs_real_ = true;
        if (any_fmod_import)
            std::fprintf(stderr,
                "[arm64-native] %s: FMOD -> REAL by default (the stub table is "
                "silent by construction; MACAPK_FMOD_STUB=1 restores coverage-gating)\n",
                dbg_name_);
    }
    if (coverage_exceeded) {
        std::fprintf(stderr,
            "[arm64-native] %s: FMOD auto-routing -> REAL (this image's FMOD "
            "imports exceed the __macapk_fmodstub_ stub table's coverage; see "
            "docs/fmod-faithfulness_2026-07-31.md)\n", dbg_name_);
    }
}

bool Image::apply_relocations() {
    // FMOD auto-routing decision (see scan_fmod_coverage()'s banner above).
    // Must run before any relocation below is applied so the first FMOD
    // symbol this image resolves gets the same answer as the last.
    // MACAPK_FMOD_BIND_BY_NAME (default ON): there is no decision to make, an
    // FMOD import binds by name like any other; say so once per process, which
    // is also the positive sign in a run log that this path ran.
    if (macapk_fmod_bind_by_name()) {
        static std::atomic<bool> noted{false};
        if (!noted.exchange(true)) {
            const bool legacy_env = std::getenv("MACAPK_FMOD_STUB") ||
                                    std::getenv("MACAPK_FMOD_REAL") ||
                                    std::getenv("MACAPK_FMOD_TRACE");
            std::fprintf(stderr,
                "[arm64-native] FMOD-named imports bind by name like any other "
                "import, no stub routing (MACAPK_FMOD_BIND_BY_NAME=0 restores "
                "it%s)\n",
                legacy_env ? "; MACAPK_FMOD_STUB / _REAL / _TRACE are set but "
                             "are read only under =0, so they are IGNORED"
                           : "");
        }
    } else {
        scan_fmod_coverage();
    }
    // Thread-pointer-relative TLS (initial-exec TPREL / TLSDESC) cannot be
    // served on macOS the naive way: XNU overwrites TPIDR_EL0 with per-CPU info
    // on every context switch (osfmk/arm64/cswitch.s), so there is no Bionic
    // thread pointer to compute against. TLSDESC now has a real implementation
    // (default ON, see MACAPK_TLS_DESC_ENABLED); TPREL initial-exec still does
    // not and lands here. General-dynamic TLS IS supported.
    //
    // CORRECTED 2026-08-12: this comment used to claim the failure was graceful
    // because "the lib falls back to the stub". MEASURED FALSE. Returning false
    // here only SKIPS the relocation; nothing stubs the slot, so a TLSDESC
    // descriptor keeps word0 == 0 and the guest's `blr` on it branches to
    // address 0. On com.duolingo that is a hard SIGSEGV at pc=0x0 inside
    // JNI_OnLoad, which killed the process. Treat reaching this lambda as a
    // LOAD-FATAL condition for the image, not a soft degrade.
    auto tls_reloc_unsupported = [&](const char* what) -> bool {
        static int n = 0;
        if (n++ < 8)
            std::fprintf(stderr,
                "[arm64-native] %s: TLS reloc unsupported: %s. macOS "
                "TPIDR_EL0 is kernel-managed (XNU rewrites it per context "
                "switch); thread-pointer-relative TLS needs the gated "
                "mrs/tp-add rewrite follow-up -- general-dynamic TLS works. "
                "See docs/arm64-tls-relocs-contract_2026-06-21.md\n",
                dbg_name_, what);
        return false;
    };
    // v1.0.609 (MACAPK_RELOC_MEMO, default-on): per-pass symbol-resolution memo.
    // A big Stage-6 image (MCPE libminecraftpe.so: ~907K relocations) invokes a
    // full-process dlsym MISS for every external / weak-defined symbol -- ~133K
    // calls at ~135us each == ~18s of boot. Those reference only ~23K DISTINCT
    // names (typeinfo / __cxa_pure_virtual / etc. repeat thousands of times), so
    // caching the resolver result by name FOR THE DURATION OF THIS ONE PASS
    // collapses the dlsym traffic ~6x with byte-identical output: the host dylib
    // set + loaded-image set are fixed within a single load, so resolution is
    // deterministic, and caching a NULL miss is safe (trap_if_unresolved_func
    // still runs per reloc). Keyed on the strtab string POINTER (stable: a given
    // symbol index -> same st_name -> same pointer). resolve_external and _silent
    // keep SEPARATE maps (they differ only in miss-logging, but never cross-serve
    // one's result for the other). FMOD-prefixed names BYPASS the memo entirely --
    // resolve_external installs stateful per-name forwarders for them
    // (g_real_createSound) under MACAPK_FMOD_TRACE, so they must not be cached.
    // (2026-09-27) The bypass applies only under MACAPK_FMOD_BIND_BY_NAME=0, the
    // one path that can install those forwarders. By default an FMOD name
    // resolves statelessly like any other, so it is memoised like any other.
    const char* memo_env = std::getenv("MACAPK_RELOC_MEMO");
    const bool reloc_memo_on = !(memo_env && memo_env[0] == '0');
    std::unordered_map<const char*, void*> reloc_memo_ext, reloc_memo_weak;
    const bool memo_fmod_bypass = !macapk_fmod_bind_by_name();
    auto memo_is_fmod = [memo_fmod_bypass](const char* n) -> bool {
        if (!memo_fmod_bypass) return false;
        return std::strncmp(n, "FMOD", 4) == 0 || std::strstr(n, "FMOD") != nullptr;
    };
    auto memo_resolve = [&](const char* name, int bind) -> void* {
        if (!reloc_memo_on || memo_is_fmod(name))
            return resolve_external(name, bind);
        auto it = reloc_memo_ext.find(name);
        if (it != reloc_memo_ext.end()) return it->second;
        void* v = resolve_external(name, bind);
        reloc_memo_ext.emplace(name, v);
        return v;
    };
    auto memo_resolve_silent = [&](const char* name, int bind) -> void* {
        if (!reloc_memo_on || memo_is_fmod(name))
            return resolve_external_silent(name, bind);
        auto it = reloc_memo_weak.find(name);
        if (it != reloc_memo_weak.end()) return it->second;
        void* v = resolve_external_silent(name, bind);
        reloc_memo_weak.emplace(name, v);
        return v;
    };
    auto apply_one = [&](const Elf64_Rela& r) -> bool {
        uint32_t type = reloc_type(r.r_info);
        uint32_t sidx = reloc_sym(r.r_info);
        // BOUND r_offset before it becomes a write target. Every reloc type
        // below except R_AARCH64_NONE stores through `slot`, and r_offset is a
        // 64-bit field read verbatim out of an ELF inside a user-supplied APK
        // -- so an out-of-range value is an arbitrary 8-byte write anywhere in
        // this process's address space, using bytes the app chose. mapped_size_
        // is the image span and the class already bounds other reads against
        // it, so the check costs one comparison and no new state. Subtract
        // rather than add: `r_offset + 8 > mapped_size_` would wrap for a
        // near-2^64 offset and admit exactly the value it is meant to reject.
        if (type != R_AARCH64_NONE
            && (r.r_offset > mapped_size_
                || sizeof(uint64_t) > mapped_size_ - r.r_offset)) {
            std::fprintf(stderr,
                "[arm64] %s: relocation r_offset 0x%llx is outside the %zu-byte "
                "image; refusing the write\n",
                dbg_name_, (unsigned long long)r.r_offset, mapped_size_);
            return false;
        }
        uint64_t* slot = reinterpret_cast<uint64_t*>(base_ + r.r_offset);

        switch (type) {
            case R_AARCH64_NONE:
                return true;

            case R_AARCH64_RELATIVE:
                // S + A, where S == load base. The original value at
                // r_offset is irrelevant for this reloc type on ELF;
                // the spec puts the addend in r_addend.
                *slot = uint64_t(base_) + uint64_t(r.r_addend);
                return true;

            case R_AARCH64_GLOB_DAT:
            case R_AARCH64_JUMP_SLOT: {
                const auto& sym = symtab_[sidx];
                // ELF allows GLOB_DAT/JUMP_SLOT against symbols that
                // are *defined* in this image -- the relocation still
                // exists so other modules can interpose. We don't
                // implement interposition, so locally-defined symbols
                // resolve to their own image address; only true UND
                // (st_shndx == 0) symbols go to dlsym. Without this,
                // FMOD's internal data globals (gFMODClass, every
                // MainPhase*Coeffs/Vars, etc.) were being looked up
                // via dlsym, which has no chance of finding them,
                // leaving the GOT slot NULL and the first read taking
                // a SIGBUS.
                uint64_t value;
                if (sym.st_shndx != 0) {
                    // Locally-defined symbol. ELF semantics say a
                    // *strong* external definition can interpose a
                    // *weak* local one. axmol / cocos2d-x v4 emit
                    // every `_JNIEnv::Call*Method` C++ inline as a
                    // weak local definition (the body lives in
                    // libOpenGD itself). Our shim provides strong
                    // overrides so libOpenGD's PLT lands in our
                    // AAPCS64-Apple variadic ABI bridge instead of
                    // libOpenGD's own copy. For STB_GLOBAL local
                    // defs we keep the prior behaviour (use the
                    // local address) since interposition for strong-
                    // strong collisions would break FMOD globals.
                    if (sym_bind(sym.st_info) == STB_WEAK) {
                        const char* name = strtab_ + sym.st_name;
                        void* ext = memo_resolve_silent(
                            name, sym_bind(sym.st_info));
                        if (ext) {
                            value = uint64_t(ext);
                            static int n_overrides = 0;
                            if (n_overrides++ < 20) {
                                std::fprintf(stderr,
                                    "[arm64-native] %s: weak override "
                                    "%s -> %p (local was 0x%llx)\n",
                                    dbg_name_, name, ext,
                                    (unsigned long long)sym.st_value);
                            }
                        } else {
                            value = uint64_t(base_) + sym.st_value;
                        }
                    } else {
                        value = uint64_t(base_) + sym.st_value;
                    }
                } else {
                    const char* name = strtab_ + sym.st_name;
                    value = uint64_t(memo_resolve(
                        name, sym_bind(sym.st_info)));
                    value = trap_if_unresolved_func(value, sym.st_info, type);
                }
                *slot = value + uint64_t(r.r_addend);
                return true;
            }

            case R_AARCH64_ABS64: {
                const auto& sym = symtab_[sidx];
                uint64_t value;
                if (sym.st_shndx == 0) {
                    // External.
                    const char* name = strtab_ + sym.st_name;
                    value = uint64_t(memo_resolve(
                        name, sym_bind(sym.st_info)));
                    value = trap_if_unresolved_func(value, sym.st_info, type);
                } else {
                    // Internal: vaddr in our image becomes runtime
                    // base_ + sym.st_value.
                    value = uint64_t(base_) + sym.st_value;
                }
                *slot = value + uint64_t(r.r_addend);
                return true;
            }

            case R_AARCH64_IRELATIVE: {
                // STT_GNU_IFUNC. Bionic's linker_relocate.cpp
                // (R_GENERIC_IRELATIVE case) computes:
                //   ifunc_addr = si->load_bias + get_addend_rel();
                //   result     = call_ifunc_resolver(ifunc_addr);
                //   *rel_target = result;
                // The addend is the image-relative offset of a resolver
                // function defined inside this same image. load_bias is
                // our base_, so the resolver address is computed exactly
                // like R_AARCH64_RELATIVE (base_ + r_addend) -- except the
                // linker CALLS the resolver and stores its return value
                // (the address of the implementation to use, e.g. an
                // arch-tuned memcpy), instead of storing the address
                // itself. The resolver takes no arguments. The original
                // slot value is irrelevant for IRELATIVE (the addend
                // carries the resolver offset), same as RELATIVE.
                //
                // Sources:
                //   - Bionic linker_relocate.cpp R_GENERIC_IRELATIVE
                //     (aosp-mirror/platform_bionic, main):
                //       ifunc_addr = si->load_bias + get_addend_rel();
                //       result     = call_ifunc_resolver(ifunc_addr);
                //       *rel_target = result;
                //   - glibc elf.h: R_AARCH64_IRELATIVE = 1032 (AAELF64
                //     dynamic-reloc range begins at 1024).
                //
                // DEFERRED: unlike Bionic (which maps .text executable
                // from the start), our loader keeps the whole span RW
                // until apply_segment_protections() flips the executable
                // PT_LOADs to RX at the END of load(). The resolver lives
                // in the guest .text, so calling it here -- during
                // apply_relocations(), before the RX flip -- would branch
                // into a non-executable page. Record (resolver, slot) now
                // and run it in apply_ifunc_relocations() after the flip.
                uint64_t resolver_addr =
                    uint64_t(base_) + uint64_t(r.r_addend);
                deferred_ifunc_.emplace_back(resolver_addr, slot);
                return true;
            }

            case R_AARCH64_COPY: {
                // Copy sym->st_size bytes from the external definition into
                // this slot. Bionic linker_relocate.cpp R_GENERIC_COPY:
                // memcpy(rel_target, (void*)sym_addr, sym->st_size). Rare in
                // PIC .so (it is an executable-side reloc) but handled for
                // completeness so a COPY no longer aborts the whole load.
                const auto& sym = symtab_[sidx];
                const char* name = strtab_ + sym.st_name;
                void* src = memo_resolve(name, sym_bind(sym.st_info));
                if (!src) {
                    std::fprintf(stderr,
                        "[arm64-native] %s: R_AARCH64_COPY of '%s' unresolved\n",
                        dbg_name_, name);
                    return false;
                }
                std::memcpy(slot, src, sym.st_size);
                return true;
            }

            case R_AARCH64_TLS_DTPMOD64: {
                // General-dynamic module id (Bionic: *target = defining
                // module id). We serve the common case: the TLS symbol is
                // defined in THIS image, or local-dynamic (sidx==0).
                // External/cross-module TLS symbols need a global
                // TLS-symbol->module registry (follow-up).
                if (sidx != 0 && symtab_[sidx].st_shndx == 0)
                    return tls_reloc_unsupported("DTPMOD64 (external TLS symbol)");
                if (tls_module_id_ == 0)
                    return tls_reloc_unsupported("DTPMOD64 (no PT_TLS in image)");
                *slot = tls_module_id_;
                return true;
            }
            case R_AARCH64_TLS_DTPREL64: {
                // General-dynamic offset-in-module (Bionic: *target =
                // sym_value + addend - TLS_DTV_OFFSET; TLS_DTV_OFFSET == 0 on
                // arm64 and cancels with __macapk_arm64___tls_get_addr).
                if (sidx != 0 && symtab_[sidx].st_shndx == 0)
                    return tls_reloc_unsupported("DTPREL64 (external TLS symbol)");
                *slot = symtab_[sidx].st_value + uint64_t(r.r_addend);
                return true;
            }
            case R_AARCH64_TLS_TPREL64:
                // Initial-exec: a GOT'd tp-relative offset added to `mrs
                // tpidr_el0`. Our tp->0 rewrite makes that an absolute-as-offset
                // mismatch (the offset is per-thread but the GOT is shared), so
                // IE is NOT covered by the TLSDESC path; fail cleanly (IE is
                // rarer than TLSDESC in a .so). image_has_tprel_ also disables
                // the tp-zero rewrite if this image somehow loaded.
                image_has_tprel_ = true;
                return tls_reloc_unsupported("TPREL64 (initial-exec)");
            case R_AARCH64_TLSDESC: {
                image_has_tlsdesc_ = true;
                if (!MACAPK_TLS_DESC_ENABLED)
                    return tls_reloc_unsupported(
                        "TLSDESC (set MACAPK_TLS_DESC=1 to enable)");
                // Gated: install our resolver as the descriptor's func (word0)
                // and pack (module_id, offset) as its arg (word1). The guest's
                // `blr resolver` returns the ABSOLUTE per-thread address; the
                // mrs->mov#0 rewrite forces tp=0 so `tp + x0` == that address.
                // Common case only: TLS symbol defined in THIS image, or
                // local-dynamic (sidx==0).
                if (sidx != 0 && symtab_[sidx].st_shndx == 0)
                    return tls_reloc_unsupported("TLSDESC (external TLS symbol)");
                if (tls_module_id_ == 0)
                    return tls_reloc_unsupported("TLSDESC (no PT_TLS in image)");
                uint64_t off = symtab_[sidx].st_value + uint64_t(r.r_addend);
                if (off > 0xffffffffull) {
                    std::fprintf(stderr,
                        "[arm64-native] %s: TLSDESC offset 0x%llx exceeds 32 "
                        "bits (cannot pack)\n",
                        dbg_name_, (unsigned long long)off);
                    return false;
                }
                // slot points at the 16-byte descriptor (word0 = func, word1 = arg).
                slot[0] = reinterpret_cast<uint64_t>(
                    reinterpret_cast<void*>(&__macapk_arm64_tlsdesc_resolver));
                slot[1] = (uint64_t(tls_module_id_) << 32) | (off & 0xffffffffull);
                return true;
            }

            default:
                std::fprintf(stderr,
                    "[arm64-native] %s: unsupported reloc type %u at "
                    "offset 0x%llx\n",
                    dbg_name_, type,
                    (unsigned long long)r.r_offset);
                return false;
        }
    };

    // DT_RELR / DT_ANDROID_RELR -- packed R_AARCH64_RELATIVE fixups.
    //
    // A RELR entry carries NO symbol and NO explicit addend, so each covered
    // word is relocated IN PLACE: the value already stored there is a
    // link-time address and the load bias is ADDED to it. That is the REL
    // (implicit-addend) form, deliberately different from the RELA
    // R_AARCH64_RELATIVE case above which writes base_ + r_addend. Bionic does
    // exactly this in soinfo::apply_relr_reloc:
    //     ElfW(Addr) address = offset + load_bias;
    //     *reinterpret_cast<ElfW(Addr)*>(address) += load_bias;
    //
    // Encoding (generic ABI ch.6 "Relocation", SHT_RELR; reference decoder in
    // llvm/lib/Object/ELF.cpp ELFFile<ELFT>::decode_relrs):
    //   [ ADDR bitmap bitmap ... ADDR bitmap ... ]
    // An EVEN entry is an address: it relocates that one word and sets the
    // base for the bitmaps that follow (base = addr + one word). An ODD entry
    // is a bitmap; every set bit ABOVE the low bit relocates one further
    // consecutive word, the second-least-significant bit meaning the word at
    // base. One 64-bit bitmap therefore covers 63 words, and the base advances
    // by 63 words after it.
    //
    // Not decoding this table is not a graceful degradation: an unapplied
    // RELATIVE leaves a link-time pointer in a vtable / GOT / .data.rel.ro
    // slot, which after ASLR is a wild pointer that faults somewhere with no
    // symbol name attached to it. Modern NDK/AGP link lines emit RELR purely
    // to shrink the relocation section, so this can appear in any .so.
    if (relr_count_ != 0) {
        constexpr uint64_t kWord = sizeof(Elf64_Relr);   // 8 on arm64
        constexpr uint64_t kBitsPerEntry = kWord * 8;    // 8 bits per byte
        auto apply_relr = [&](uint64_t offset) {
            *reinterpret_cast<uint64_t*>(base_ + offset) += uint64_t(base_);
        };
        uint64_t relr_base = 0;
        for (size_t i = 0; i < relr_count_; ++i) {
            Elf64_Relr entry = relr_[i];
            if ((entry & 1) == 0) {
                // Even: an address entry. Encodes one relocation, and rebases
                // the bitmap entries that follow it.
                apply_relr(entry);
                relr_base = entry + kWord;
                continue;
            }
            // Odd: a bitmap over the kBitsPerEntry-1 words starting at base.
            uint64_t offset = relr_base;
            while ((entry >>= 1) != 0) {
                if ((entry & 1) != 0) apply_relr(offset);
                offset += kWord;
            }
            relr_base += (kBitsPerEntry - 1) * kWord;
        }
        std::fprintf(stderr,
            "[arm64-native] %s: applied %zu DT_RELR packed relative "
            "relocation entries\n", dbg_name_, relr_count_);
    }
    // DT_ANDROID_RELA ("APS2" packed relocations). Ported byte-for-byte from
    // AOSP bionic (fetched 2026-07-31; see docs/android-packed-relocations-
    // aps2_2026-07-31.md for full citations):
    //   - SLEB128 decode: bionic/linker/linker_sleb128.h, class
    //     sleb128_decoder (verbatim below, same shift/sign-extend logic).
    //   - Group format: bionic/linker/linker_reloc_iterators.h,
    //     for_all_packed_relocs() -- num_relocs, then a sequence of groups
    //     of (group_size, group_flags, [offset-delta], [r_info],
    //     [addend]), decoded here in the SAME field order the AOSP source
    //     reads them in (reordering any pop_front() call changes what every
    //     later value means, since the stream carries no field tags).
    //   - Only the USE_RELA branch is ported: Stage-6 is arm64-only, and
    //     AOSP's own linker.cpp DL_ERRs "unsupported DT_ANDROID_REL" on an
    //     arm64 (USE_RELA) linker for exactly the same reason -- see the
    //     DT_ANDROID_RELA constant's comment above.
    // Each decoded entry is a real Elf64_Rela (r_offset/r_info/r_addend);
    // apply_one() -- the SAME lambda that applies classic .rela.dyn and
    // .rela.plt entries just below -- dispatches it by r_info's type field,
    // so RELATIVE/GLOB_DAT/ABS64/TLS handling is not duplicated here.
    if (android_packed_relocs_ != nullptr) {
        struct Sleb128Decoder {
            const uint8_t* cur;
            const uint8_t* end;
            bool overrun = false;
            // size_t pop_front(): verbatim port of sleb128_decoder::pop_front
            // (linker_sleb128.h). "size" = bit-width of size_t (64 on arm64);
            // sign-extends when the terminating byte's bit 6 is set and the
            // accumulated shift hasn't already consumed every bit.
            size_t pop_front() {
                size_t value = 0;
                constexpr size_t kBits = 8 * sizeof(size_t);
                size_t shift = 0;
                uint8_t byte;
                do {
                    if (cur >= end) { overrun = true; return 0; }
                    byte = *cur++;
                    value |= (static_cast<size_t>(byte & 127) << shift);
                    shift += 7;
                } while (byte & 128);
                if (shift < kBits && (byte & 64)) {
                    value |= (~static_cast<size_t>(0) << shift);
                }
                return value;
            }
        };
        constexpr size_t kGroupedByInfoFlag       = 1;  // RELOCATION_GROUPED_BY_INFO_FLAG
        constexpr size_t kGroupedByOffsetDeltaFlag = 2; // RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG
        constexpr size_t kGroupedByAddendFlag     = 4;  // RELOCATION_GROUPED_BY_ADDEND_FLAG
        constexpr size_t kGroupHasAddendFlag      = 8;  // RELOCATION_GROUP_HAS_ADDEND_FLAG
        Sleb128Decoder dec{android_packed_relocs_,
                           android_packed_relocs_ + android_packed_relocs_size_};
        const size_t num_relocs = dec.pop_front();
        Elf64_Rela reloc{};
        reloc.r_offset = dec.pop_front();
        size_t applied = 0;
        for (size_t idx = 0; idx < num_relocs && !dec.overrun; ) {
            const size_t group_size  = dec.pop_front();
            const size_t group_flags = dec.pop_front();
            size_t group_r_offset_delta = 0;
            if (group_flags & kGroupedByOffsetDeltaFlag) {
                group_r_offset_delta = dec.pop_front();
            }
            if (group_flags & kGroupedByInfoFlag) {
                reloc.r_info = dec.pop_front();
            }
            // AOSP's group_flags_reloc tri-state (RELA branch only, see the
            // comment above): HAS_ADDEND alone = per-relocation addend
            // (lld's default today); HAS_ADDEND|GROUPED_BY_ADDEND = one
            // shared addend for the whole group; ANYTHING ELSE = zero addend.
            const size_t addend_bits =
                group_flags & (kGroupHasAddendFlag | kGroupedByAddendFlag);
            const bool per_reloc_addend = (addend_bits == kGroupHasAddendFlag);
            if (addend_bits == kGroupHasAddendFlag) {
                // HAS_ADDEND alone: per-relocation addend, popped inside the
                // group loop below. Nothing to do to the running addend here.
            } else if (addend_bits == (kGroupHasAddendFlag | kGroupedByAddendFlag)) {
                reloc.r_addend += static_cast<int64_t>(dec.pop_front());
            } else {
                // The AOSP `else` (linker_reloc_iterators.h for_all_packed_relocs,
                // quoted in docs/android-packed-relocations-aps2_2026-07-31.md
                // lines 173-174): resets the running addend to 0. This covers
                // BOTH addend_bits == 0 AND addend_bits == 4 (GROUPED_BY_ADDEND
                // set but HAS_ADDEND clear). The prior `else if (addend_bits == 0)`
                // missed the flags==4 case, leaving the STALE carried addend on
                // those relocs -> base_ + wrong_addend (an ASLR-tracking wild
                // pointer). lld never emits flags==4 (it sets HAS_ADDEND on every
                // RELA group), so this is latent for lld-linked .so, but the older
                // Android relocation_packer does emit GROUPED_BY_ADDEND alone.
                reloc.r_addend = 0;
            }
            for (size_t i = 0; i < group_size; ++i) {
                if (group_flags & kGroupedByOffsetDeltaFlag) {
                    reloc.r_offset += group_r_offset_delta;
                } else {
                    reloc.r_offset += dec.pop_front();
                }
                if ((group_flags & kGroupedByInfoFlag) == 0) {
                    reloc.r_info = dec.pop_front();
                }
                if (per_reloc_addend) {
                    reloc.r_addend += static_cast<int64_t>(dec.pop_front());
                }
                if (dec.overrun) break;
                if (!apply_one(reloc)) return false;
                ++applied;
            }
            idx += group_size;
        }
        if (dec.overrun) {
            std::fprintf(stderr,
                "[arm64-native] %s: DT_ANDROID_RELA packed stream ran out "
                "of bounds after %zu/%zu entries\n",
                dbg_name_, applied, num_relocs);
            return false;
        }
        std::fprintf(stderr,
            "[arm64-native] %s: applied %zu DT_ANDROID_RELA packed "
            "relocation entries (APS2)\n", dbg_name_, applied);
    }
    for (size_t i = 0; i < rela_count_; ++i) {
        if (!apply_one(rela_[i])) return false;
    }
    for (size_t i = 0; i < pltrel_count_; ++i) {
        if (!apply_one(pltrel_[i])) return false;
    }
    return true;
}

// v1.0.555: exact dynamic-symbol count from DT_GNU_HASH. The dynamic tags
// carry no explicit .dynsym count; the ELF convention is that the hash table
// (DT_GNU_HASH / DT_HASH) bounds it. The old build_export_map heuristic
// (walk until an st_name runs off the strtab / an all-zero entry) is fragile:
// libminecraftpe.so places its .dynsym (vaddr 0x13f980c0) far AFTER the strtab
// (0x2f3eb0) with symbol names clustered near the strtab end, so the very
// first symbol tripped the strtab-bound / all-zero break and ZERO exports were
// indexed -> every export trampoline (JNI_OnLoad included) branched through a
// null slot -> `br 0` -> SIGSEGV pc=0 as soon as the JVM called JNI_OnLoad.
// GNU_HASH's chain array is indexed by (symidx - symoffset); the highest set
// index is found by taking the max bucket head and following its chain to the
// entry whose low bit (the chain terminator) is set. That index + 1 is the
// exact symbol count. Returns 0 when unavailable so the caller falls back to
// the heuristic walk.
size_t Image::gnu_hash_symbol_count() const {
    if (!gnu_hash_) return 0;
    const uint32_t nbuckets   = gnu_hash_[0];
    const uint32_t symoffset  = gnu_hash_[1];
    const uint32_t bloom_size = gnu_hash_[2];
    if (nbuckets == 0) return symoffset;   // no hashed symbols
    // Layout: [4 u32 header][bloom_size u64 bloom][nbuckets u32 buckets]
    //         [chain u32 ...] (chain indexed by symidx - symoffset).
    const uint32_t* buckets = gnu_hash_ + 4 + bloom_size * 2;  // *2: u64 bloom in u32 units
    const uint32_t* chain   = buckets + nbuckets;
    uint32_t last = 0;
    for (uint32_t i = 0; i < nbuckets; ++i)
        if (buckets[i] > last) last = buckets[i];
    if (last < symoffset) return symoffset;   // all buckets below the hashed range
    // Follow the chain from `last` until the terminator bit is set.
    uint32_t idx = last;
    while (!(chain[idx - symoffset] & 1u)) {
        ++idx;
        if (idx - symoffset > (1u << 22)) break;   // runaway guard
    }
    return idx + 1;   // count = highest index + 1
}

// t3-syms (2026-07-24): LAST-RESORT ONLY. Real Android/NDK linker output
// always carries DT_GNU_HASH and/or DT_HASH (AOSP's own linker.cpp hard-
// fails an image with neither: "empty/missing DT_HASH/DT_GNU_HASH in ...").
// This eager, name-copying map is therefore only ever populated for a
// malformed/exotic guest that somehow has neither -- see load()'s call
// site (gated on `no_hash_table_`). Every real Stage-6 guest .so this
// mission surveyed (43 libs: MCPE's libminecraftpe.so + libHttpClient.
// Android.so, every arm64-v8a lib under Crossy Road / Subway Surfers /
// Among Us / OpenGD) has DT_GNU_HASH, so this function is DEAD CODE on
// every app currently in the compat matrix -- kept only so a future guest
// that truly lacks both hash tables still loads instead of hard-failing.
void Image::build_export_map() {
    // For our usage (name -> runtime address for the wrapper's dlsym), we only
    // need DEFINED dynamic symbols. Prefer the EXACT count from GNU_HASH; fall
    // back to the strtab-proximity heuristic when the image has no GNU_HASH.
    // (In practice this path only runs when gnu_hash_ is ALSO null -- see the
    // no_hash_table_ gate at the call site -- so `exact` is always false here
    // today; the GNU_HASH-exact branch is retained for the case a future
    // caller invokes this directly with gnu_hash_ set.)
    constexpr size_t kMaxSymbols = 1u << 21;  // 2,097,152 (Minecraft has ~91k)
    size_t nsyms = gnu_hash_symbol_count();
    const bool exact = (nsyms != 0);
    if (!exact) nsyms = kMaxSymbols;    // heuristic-bounded walk
    if (nsyms > kMaxSymbols) nsyms = kMaxSymbols;
    for (size_t walked = 0; walked < nsyms; ++walked) {
        const Elf64_Sym& s = symtab_[walked];
        if (!exact) {
            // Heuristic termination (only when GNU_HASH is absent): stop when a
            // symbol's name runs off the strtab, or an all-zero padding entry
            // appears past index 0.
            if (strtab_size_ && s.st_name >= strtab_size_) break;
            if (walked > 0 && s.st_name == 0 && s.st_value == 0
                && s.st_size == 0 && s.st_info == 0 && s.st_shndx == 0) break;
        }
        if (s.st_shndx != 0 && s.st_name != 0
            && (!strtab_size_ || s.st_name < strtab_size_)) {
            // Defined (non-UND) named symbol.
            const char* name = strtab_ + s.st_name;
            if (*name) exports_fallback_.emplace(name, base_ + s.st_value);
        }
    }
}

// --- t3-syms (2026-07-24): on-demand GNU_HASH / DT_HASH symbol lookup ---
//
// Replaces the eager exports_ map above (see its comment) with a
// zero-copy hash query mirroring bionic's own linker, so RAM cost per
// loaded guest .so drops from O(total dynsym name bytes) to O(1). Sources
// fetched and quoted verbatim before writing this (per CLAUDE.md's
// struct-offset rule -- never guess a Bionic/ELF layout):
//   - aosp-mirror/platform_bionic main:linker/linker_soinfo.cpp
//     soinfo::gnu_lookup() (bloom filter + bucket/chain walk),
//     soinfo::elf_lookup() (SysV DT_HASH fallback), calculate_elf_hash().
//   - aosp-mirror/platform_bionic main:linker/linker_gnu_hash.h
//     calculate_gnu_hash_simple() (h=5381; h += (h<<5)+c per byte).
//   - aosp-mirror/platform_bionic main:linker/linker.cpp lines ~2909-2936
//     (soinfo::prelink_image): the EXACT field extraction from the raw
//     DT_HASH/DT_GNU_HASH pointer (nbucket_/nchain_/bucket_/chain_ for
//     DT_HASH; gnu_nbucket_/gnu_maskwords_/gnu_shift2_/gnu_bloom_filter_/
//     gnu_bucket_/gnu_chain_ for DT_GNU_HASH, including gnu_maskwords_
//     being POST-DECREMENT, i.e. bloom_size-1, and bloom words being
//     ElfW(Addr)-sized = 8 bytes on arm64). This loader's existing
//     gnu_hash_symbol_count() (above) already independently derived the
//     identical header layout for its own purpose (counting), which
//     cross-validates the field offsets used below.
//   - aosp-mirror/platform_bionic main:linker/linker_relocate.h
//     is_symbol_global_and_defined() (bind must be GLOBAL or WEAK, symbol
//     must be defined i.e. st_shndx != SHN_UNDEF) and
//     kVersymHiddenBit = 0x8000.
//   - aosp-mirror/platform_bionic main:linker/linker_soinfo.cpp
//     check_symbol_version(): when no specific version is requested
//     (this loader never threads a version_info* through lookup() --
//     grep confirms zero versym/verneed/verdef handling existed anywhere
//     in this file before this mission), the check reduces to "symbol is
//     not flagged hidden" -- exactly what symbol_name_and_bind_match()
//     implements below.
//
// Deliberately NOT implemented (confirmed unnecessary for this loader's
// actual call shape, not merely skipped):
//   - DT_VERNEED/DT_VERDEF resolution. Those exist in bionic to answer
//     "does THIS specific requested version match a hidden definition".
//     Nothing in this codebase ever requests a specific symbol version
//     (no R_AARCH64 relocation here carries one, and __macapk_arm64_
//     native_dlsym's signature is a bare name) -- every lookup is
//     bionic's own "vi == nullptr" case, which needs only the hidden-bit
//     check against DT_VERSYM (versym_), not the full verneed/verdef
//     graph. Implementing the unused graph would be speculative
//     generality, not correctness.
//   - Explicit STT_GNU_IFUNC resolver-calling. Bionic's OWN gnu_lookup()/
//     elf_lookup() do not call ifunc resolvers either -- they return the
//     raw Elf64_Sym (whose st_value for an ifunc is the RESOLVER's
//     address, not the resolved target); bionic's callers (dlsym(),
//     the relocator) special-case STT_GNU_IFUNC AFTER getting the symbol
//     back. This loader's OLD exports_ map had the identical raw-value
//     behaviour (build_export_map() above never checks st_info's type),
//     so matching bionic's raw-return here is simultaneously bionic-
//     faithful AND a zero-behaviour-change vs. the code being replaced.
//     (Moot in practice: the t3-syms survey found zero STT_GNU_IFUNC
//     symbols across all 43 real guest libs checked.)

// Standard GNU-hash (used by --hash-style=gnu / DT_GNU_HASH). h=5381;
// h = h*33 + c per byte, written as h += (h<<5)+c. Verbatim algorithm
// from bionic linker/linker_gnu_hash.h calculate_gnu_hash_simple().
static inline uint32_t macapk_gnu_hash(const char* name) {
    uint32_t h = 5381;
    for (const uint8_t* p = reinterpret_cast<const uint8_t*>(name); *p; ++p)
        h += (h << 5) + *p;
    return h;
}

// Classic SysV ELF hash (DT_HASH). Verbatim algorithm from bionic
// linker/linker_soinfo.cpp calculate_elf_hash().
static inline uint32_t macapk_elf_hash(const char* name) {
    uint32_t h = 0, g;
    for (const uint8_t* p = reinterpret_cast<const uint8_t*>(name); *p; ++p) {
        h = (h << 4) + *p;
        g = h & 0xf0000000u;
        h ^= g;
        h ^= g >> 24;
    }
    return h;
}

bool Image::symbol_name_and_bind_match(const Elf64_Sym& s, const char* name,
                                        uint32_t sym_idx) const {
    // bionic linker_relocate.h is_symbol_global_and_defined(): only a
    // GLOBAL- or WEAK-bound, defined (non-SHN_UNDEF) symbol is a valid
    // name-lookup result.
    const uint8_t bind = s.st_info >> 4;   // ELF_ST_BIND
    if ((bind != /*STB_GLOBAL*/1 && bind != STB_WEAK) || s.st_shndx == 0)
        return false;
    // bionic linker_soinfo.cpp check_symbol_version(), unversioned-request
    // case (this loader never requests a specific version -- see the block
    // comment above): skip a symbol flagged hidden in DT_VERSYM.
    if (versym_ && (versym_[sym_idx] & 0x8000u /* kVersymHiddenBit */))
        return false;
    if (s.st_name == 0 || (strtab_size_ && s.st_name >= strtab_size_))
        return false;
    return std::strcmp(strtab_ + s.st_name, name) == 0;
}

void* Image::gnu_hash_lookup(const char* name) const {
    const uint32_t nbuckets    = gnu_hash_[0];
    const uint32_t symoffset   = gnu_hash_[1];
    const uint32_t bloom_size  = gnu_hash_[2];   // == gnu_maskwords_ RAW count (pre-decrement)
    const uint32_t bloom_shift = gnu_hash_[3];   // == gnu_shift2_
    if (nbuckets == 0 || bloom_size == 0) return nullptr;
    // Layout matches gnu_hash_symbol_count() above (independently derived,
    // cross-validated against bionic linker.cpp's field extraction):
    // [4 u32 header][bloom_size u64 bloom words][nbuckets u32 buckets]
    // [chain u32 ...] (chain conceptually indexed by symidx - symoffset).
    const uint64_t* bloom   = reinterpret_cast<const uint64_t*>(gnu_hash_ + 4);
    const uint32_t* buckets = gnu_hash_ + 4 + bloom_size * 2;  // *2: u64 bloom in u32 units
    const uint32_t* chain   = buckets + nbuckets;

    const uint32_t hash = macapk_gnu_hash(name);
    constexpr uint32_t kBloomMaskBits = 64;   // ElfW(Addr) is 8 bytes on arm64
    const uint32_t maskwords = bloom_size - 1;   // bionic: --gnu_maskwords_ after the power-of-2 check
    const uint32_t word_num = (hash / kBloomMaskBits) & maskwords;
    const uint64_t bloom_word = bloom[word_num];
    const uint32_t h1 = hash % kBloomMaskBits;
    const uint32_t h2 = (hash >> bloom_shift) % kBloomMaskBits;
    if ((1ull & (bloom_word >> h1) & (bloom_word >> h2)) == 0)
        return nullptr;   // bloom filter says definitely-absent

    uint32_t n = buckets[hash % nbuckets];
    if (n == 0 || n < symoffset) return nullptr;   // empty bucket / malformed table

    for (;;) {
        const uint32_t chain_val = chain[n - symoffset];
        if (((chain_val ^ hash) >> 1) == 0) {
            const Elf64_Sym& s = symtab_[n];
            if (symbol_name_and_bind_match(s, name, n))
                return base_ + s.st_value;
        }
        if (chain_val & 1u) break;   // last entry in this bucket's chain
        ++n;
    }
    return nullptr;
}

void* Image::gnu_hash_low_region_scan(const char* name) const {
    // See the declaration's comment: GNU_HASH's hashed region only covers
    // [symoffset, nsyms); scan the (normally tiny) low region [0,symoffset)
    // directly, applying the SAME permissive filter the old build_export_map
    // used (defined + named; no bind/version check), so lookup() can never
    // return a different answer than the map it replaced. Empirically zero
    // hits across all 43 real guest libs surveyed for this mission (t3-syms,
    // 2026-07-24) -- every low-region entry in every one of them is an
    // undefined import -- but this is correctness-by-construction, not
    // correctness-by-empirical-luck.
    const uint32_t symoffset = gnu_hash_[1];
    for (uint32_t i = 0; i < symoffset; ++i) {
        const Elf64_Sym& s = symtab_[i];
        if (s.st_shndx != 0 && s.st_name != 0
            && (!strtab_size_ || s.st_name < strtab_size_)
            && std::strcmp(strtab_ + s.st_name, name) == 0) {
            return base_ + s.st_value;
        }
    }
    return nullptr;
}

void* Image::sysv_hash_lookup(const char* name) const {
    // bionic linker/linker.cpp DT_HASH extraction: [nbucket][nchain]
    // [bucket[nbucket]][chain[nchain]], chain directly indexed by the
    // absolute symtab index (no symoffset-style adjustment -- DT_HASH
    // predates that GNU_HASH concept and hashes every symbol).
    const uint32_t nbucket = sysv_hash_[0];
    const uint32_t nchain  = sysv_hash_[1];
    if (nbucket == 0) return nullptr;
    const uint32_t* bucket = sysv_hash_ + 2;
    const uint32_t* chain  = bucket + nbucket;
    const uint32_t hash = macapk_elf_hash(name);
    for (uint32_t n = bucket[hash % nbucket]; n != 0; n = chain[n]) {
        if (n >= nchain) break;   // malformed-table guard
        const Elf64_Sym& s = symtab_[n];
        if (symbol_name_and_bind_match(s, name, n))
            return base_ + s.st_value;
    }
    return nullptr;
}

size_t Image::count_defined_named_hashed() const {
    size_t nsyms = gnu_hash_ ? gnu_hash_symbol_count()
                 : sysv_hash_ ? sysv_hash_[1]   // DT_HASH: nchain == total dynsym count
                 : 0;
    size_t n = 0;
    for (size_t i = 0; i < nsyms; ++i) {
        const Elf64_Sym& s = symtab_[i];
        if (s.st_shndx != 0 && s.st_name != 0
            && (!strtab_size_ || s.st_name < strtab_size_))
            ++n;
    }
    return n;
}

// Guest dladdr's symbol half (MACAPK_GUEST_DLADDR), mirroring bionic android14
// linker/linker_soinfo.cpp exactly:
//   ElfW(Sym)* soinfo::find_symbol_by_address(const void* addr) {
//     return is_gnu_hash() ? gnu_addr_lookup(addr) : elf_addr_lookup(addr); }
//   static bool symbol_matches_soaddr(const ElfW(Sym)* sym, ElfW(Addr) soaddr) {
//     return sym->st_shndx != SHN_UNDEF && ELF_ST_TYPE(sym->st_info) != STT_TLS &&
//         soaddr >= sym->st_value && soaddr < sym->st_value + sym->st_size; }
// gnu_addr_lookup walks every bucket and its chain (`while ((gnu_chain_[n++] & 1)
// == 0)`, gnu_chain_ being pre-offset by -symndx); elf_addr_lookup walks indices
// [0, nchain). So the answer is the FIRST symbol in hash order whose extent
// CONTAINS the address -- a zero-size symbol never matches, and an address
// between symbols gets no dli_sname at all. That is Bionic's contract, and it is
// deliberately NOT glibc's or dyld's "nearest preceding symbol".
// An image with neither hash table cannot exist on Android (bionic's
// prelink_image hard-fails it), so that case answers nullptr.
const Elf64_Sym* Image::find_symbol_by_address(uintptr_t addr,
                                               const char** name_out) const {
    if (name_out) *name_out = nullptr;
    if (!symtab_ || !base_) return nullptr;
    const uint64_t soaddr = uint64_t(addr) - reinterpret_cast<uint64_t>(base_);
    auto matches = [&](const Elf64_Sym& s) {
        return s.st_shndx != 0 /*SHN_UNDEF*/
            && (s.st_info & 0xf) != 6 /*ELF_ST_TYPE == STT_TLS*/
            && soaddr >= s.st_value && soaddr < s.st_value + s.st_size;
    };
    auto named = [&](const Elf64_Sym& s) -> const Elf64_Sym* {
        if (name_out && strtab_ && (!strtab_size_ || s.st_name < strtab_size_))
            *name_out = strtab_ + s.st_name;
        return &s;
    };
    if (gnu_hash_) {
        const uint32_t nbuckets   = gnu_hash_[0];
        const uint32_t symoffset  = gnu_hash_[1];
        const uint32_t bloom_size = gnu_hash_[2];
        const uint32_t* buckets = gnu_hash_ + 4 + bloom_size * 2;   // u64 bloom in u32 units
        const uint32_t* chain   = buckets + nbuckets;               // indexed by n - symoffset
        for (uint32_t i = 0; i < nbuckets; ++i) {
            uint32_t n = buckets[i];
            if (n == 0 || n < symoffset) continue;
            for (uint32_t guard = 0; guard < (1u << 22); ++guard) {
                const Elf64_Sym& s = symtab_[n];
                if (matches(s)) return named(s);
                if (chain[n - symoffset] & 1u) break;               // chain terminator
                ++n;
            }
        }
        return nullptr;
    }
    if (sysv_hash_) {
        const uint32_t nchain = sysv_hash_[1];
        for (uint32_t i = 0; i < nchain; ++i)
            if (matches(symtab_[i])) return named(symtab_[i]);
    }
    return nullptr;
}

void Image::verify_hash_lookup_matches_legacy() const {
    if (!std::getenv("MACAPK_VERIFY_SYMBOL_HASH")) return;
    if (no_hash_table_) return;   // exports_fallback_ IS the legacy map here; nothing to compare

    // Rebuild the OLD (pre-t3-syms) eager-map algorithm verbatim (see
    // build_export_map() above) into a SCOPE-LOCAL map -- never retained,
    // this is a diagnostic proof, not a steady-state structure.
    std::unordered_map<std::string, void*> legacy;
    size_t nsyms = gnu_hash_ ? gnu_hash_symbol_count() : sysv_hash_[1];
    for (size_t i = 0; i < nsyms; ++i) {
        const Elf64_Sym& s = symtab_[i];
        if (s.st_shndx != 0 && s.st_name != 0
            && (!strtab_size_ || s.st_name < strtab_size_)) {
            const char* name = strtab_ + s.st_name;
            if (*name) legacy.emplace(name, base_ + s.st_value);
        }
    }

    size_t mismatches = 0;
    for (const auto& kv : legacy) {
        void* newp = lookup(kv.first.c_str());
        if (newp != kv.second) {
            ++mismatches;
            if (mismatches <= 20) {
                std::fprintf(stderr,
                    "[verify-symbol-hash] %s: MISMATCH '%s' legacy=%p new=%p\n",
                    dbg_name_, kv.first.c_str(), kv.second, newp);
            }
        }
    }

    // Known-absent probe: names that cannot legitimately collide with a
    // real exported symbol, so both the legacy map and the new hash walk
    // must agree on NOT-FOUND.
    static const char* const kAbsentProbe[] = {
        "__macapk_verify_absent_probe_zzz_not_a_real_symbol_1",
        "__macapk_verify_absent_probe_zzz_not_a_real_symbol_2",
        "this_symbol_almost_certainly_does_not_exist_in_any_guest_so",
    };
    size_t absent_mismatches = 0;
    for (const char* probe : kAbsentProbe) {
        const bool legacy_found = legacy.count(probe) != 0;
        const bool new_found = lookup(probe) != nullptr;
        if (legacy_found != new_found) {
            ++absent_mismatches;
            std::fprintf(stderr,
                "[verify-symbol-hash] %s: ABSENT-PROBE MISMATCH '%s' "
                "legacy_found=%d new_found=%d\n",
                dbg_name_, probe, legacy_found, new_found);
        }
    }

    std::fprintf(stderr,
        "[verify-symbol-hash] %s: %zu/%zu identical, %zu absent-probe "
        "mismatches%s\n",
        dbg_name_, legacy.size() - mismatches, legacy.size(),
        absent_mismatches,
        (mismatches == 0 && absent_mismatches == 0) ? " -- OK" : " -- FAIL");

    // t3-syms RAM-measurement hook (MACAPK_MEASURE_SYMBOL_RAM_MS=<ms>,
    // default off, only for a large-enough map to be worth an external
    // sample -- skips the flood of tiny libs so this only fires for the
    // headline case, e.g. libminecraftpe.so's ~90.5k): the correctness
    // check above is already done, so both holds below bracket a QUIET
    // window on this SAME thread where nothing else in Image::load() can
    // proceed and confound the sample (no dlsym trampoline-population loop,
    // no further boot work) -- HOLD A samples with `legacy` still fully
    // populated (an exact stand-in for the OLD permanently-resident map's
    // footprint), then `legacy` is explicitly torn down (clear() + swap-
    // with-a-temporary to also release the bucket array, not just the
    // nodes) and HOLD B samples immediately after, so the delta between
    // the two external footprint/vmmap reads isolates JUST this one map's
    // resident cost from the rest of the process's own boot-time growth.
    // TEMPORARY diagnostic; see the mission report for the paired numbers
    // this produced.
    if (const char* ms_str = std::getenv("MACAPK_MEASURE_SYMBOL_RAM_MS")) {
        long ms = std::atol(ms_str);
        if (ms > 0 && legacy.size() >= 5000) {
            struct timespec ts;
            ts.tv_sec = ms / 1000;
            ts.tv_nsec = (ms % 1000) * 1000000;
            std::fprintf(stderr,
                "[measure-symbol-ram] %s: HOLD-A legacy map ALIVE (%zu "
                "entries) -- pid=%d, sample now, %ldms\n",
                dbg_name_, legacy.size(), (int)getpid(), ms);
            nanosleep(&ts, nullptr);
            legacy.clear();
            std::unordered_map<std::string, void*>().swap(legacy);
            // Ask libmalloc to actually give freed pages back to the OS
            // instead of retaining them in its own free lists (macOS's
            // small/tiny zone allocators normally keep recently-freed
            // regions "dirty but unused" for reuse speed, which would
            // otherwise make phys_footprint understate the real delta
            // right after a burst free like this one).
            malloc_zone_pressure_relief(malloc_default_zone(), 0);
            std::fprintf(stderr,
                "[measure-symbol-ram] %s: HOLD-B legacy map FREED + "
                "pressure-relieved -- pid=%d, sample now, %ldms\n",
                dbg_name_, (int)getpid(), ms);
            nanosleep(&ts, nullptr);
            std::fprintf(stderr,
                "[measure-symbol-ram] %s: measurement window done, "
                "resuming load()\n", dbg_name_);
        }
    }
}

// Model the per-host-page protection union that apply_segment_protections()
// will compute, for an image placed at bias `shift`, and report whether any
// page ends up demanding both W and X (the combination macOS refuses).
//
// This deliberately MIRRORS apply_segment_protections()'s contribution()
// logic rather than approximating it: same page granularity, same
// segment-contribution-level RELRO strip. If the two ever drift, the shift
// chosen here stops matching the protections actually applied, and the load
// fails at mprotect exactly as it did before -- loudly, not silently.
//
// Addresses are modeled as (shift + p_vaddr). That is exact, not an
// approximation: base_ = mmap_addr + shift - span_lo, mmap returns a
// host-page-aligned address, and span_lo is align_down(min_va, page_size_),
// so the runtime address of p_vaddr is congruent to (shift + p_vaddr) modulo
// the host page size.
bool Image::has_wx_conflict(const std::vector<const Elf64_Phdr*>& loads,
                            size_t shift, uintptr_t* first_bad) const {
    uintptr_t relro_lo = 0, relro_hi = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_GNU_RELRO) continue;
        relro_lo = uintptr_t(ph.p_vaddr) + shift;
        relro_hi = relro_lo + ph.p_memsz;
        break;
    }
    std::map<uintptr_t, int> page_flags;
    for (const auto* ph : loads) {
        uintptr_t seg_lo = uintptr_t(ph->p_vaddr) + shift;
        uintptr_t seg_hi = seg_lo + ph->p_memsz;
        for (uintptr_t pg = align_down(seg_lo, page_size_);
             pg < align_up(seg_hi, page_size_); pg += page_size_) {
            uintptr_t lo = std::max<uintptr_t>(pg, seg_lo);
            uintptr_t hi = std::min<uintptr_t>(pg + page_size_, seg_hi);
            if (lo >= hi) continue;
            uint32_t f = ph->p_flags;
            if (relro_hi > 0 && lo >= relro_lo && hi <= relro_hi) f &= ~PF_W;
            page_flags[pg] |= int(f);
        }
    }
    for (const auto& kv : page_flags) {
        if ((kv.second & PF_W) && (kv.second & PF_X)) {
            if (first_bad) *first_bad = kv.first - shift;   // report in file vaddr
            return true;
        }
    }
    return false;
}

// Pick the load-bias shift that lands the image's RX|RW permission boundary
// on a host page boundary, so no host page has to carry both W and X. See the
// long comment at the call site in map_segments() for the rationale and the
// AOSP citation. Returns 0 when the image is already conflict-free (the
// overwhelmingly common case: every properly 16 KiB-aligned .so, and every
// 4 KiB one whose boundary happens to land right) or when no shift can fix it.
size_t Image::choose_rx_rw_bias_shift(
        const std::vector<const Elf64_Phdr*>& loads) const {
    uintptr_t bad_at = 0;
    if (!has_wx_conflict(loads, 0, &bad_at)) return 0;

    // Bionic requires at most ONE PT_GNU_RELRO and refuses compat loading
    // otherwise (HasAtMostOneRelroSegment). More than one means there is no
    // single RX|RW boundary to align.
    const Elf64_Phdr* relro = nullptr;
    size_t relro_count = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_GNU_RELRO) { continue; }
        ++relro_count;
        if (!relro) relro = &ph;
    }

    uint64_t first_rw = UINT64_MAX;
    for (const auto* ph : loads)
        if ((ph->p_flags & PF_W) && ph->p_vaddr < first_rw) first_rw = ph->p_vaddr;
    if (first_rw == UINT64_MAX) return 0;   // no writable segment: cannot conflict

    // The boundary, per bionic IsEligibleFor16KiBAppCompat(). RELRO only
    // counts when it is the PREFIX of the first RW segment (same p_vaddr);
    // that is the layout the RELRO strip in apply_segment_protections()
    // already handles, and it puts the boundary at the END of RELRO. With no
    // usable RELRO the boundary is the start of the first RW segment.
    // kCompatPageSize (4096) is the ELF's own alignment, NOT the host page.
    constexpr uint64_t kCompatPageSize = 4096;
    uint64_t boundary;
    if (relro_count == 1 && relro->p_vaddr == first_rw)
        boundary = align_up(uintptr_t(relro->p_vaddr + relro->p_memsz), kCompatPageSize);
    else
        boundary = align_down(uintptr_t(first_rw), kCompatPageSize);

    const size_t shift = size_t((page_size_ - (boundary % page_size_)) % page_size_);

    // VERIFY rather than assume. An exotic layout (interleaved RW/RX segments,
    // an exec segment whose tail shares a 4 KiB page with the first RW
    // segment) can still conflict after the shift; in that case keep the
    // shift at 0 so the W-dropping fallback in apply_segment_protections()
    // handles it, and say so instead of silently mis-mapping.
    uintptr_t still_bad = 0;
    if (shift == 0 || has_wx_conflict(loads, shift, &still_bad)) {
        std::fprintf(stderr,
            "[arm64-native] %s: host page +0x%llx needs both W and X and no "
            "bias shift resolves it (boundary +0x%llx, tried shift %zu); "
            "falling back to dropping W.\n",
            dbg_name_, (unsigned long long)bad_at,
            (unsigned long long)boundary, shift);
        return 0;
    }

    std::fprintf(stderr,
        "[arm64-native] %s: 4KB-aligned PT_LOADs collide on the %zuKB host "
        "page at +0x%llx; shifting the load bias by %zu B to put the RX|RW "
        "boundary (+0x%llx) on a host page edge.\n",
        dbg_name_, page_size_ / 1024, (unsigned long long)bad_at, shift,
        (unsigned long long)boundary);
    return shift;
}

bool Image::apply_segment_protections() {
    // Find PT_GNU_RELRO so we can drop W from segment 2 (.data.rel.ro,
    // .init_array, .got, etc.) post-relocation. The Android .so layout
    // typically places the RELRO region right after .text/.plt; without
    // dropping W here, the host page where seg1's .plt ends and seg2's
    // .data.rel.ro begins would require both X (from seg1) AND W (from
    // seg2's PT_LOAD p_flags), which macOS rejects with EPERM under
    // W^X. Treating RELRO bytes as R-only after relocation eliminates
    // the conflict on every well-formed ELF.
    uintptr_t relro_lo = 0, relro_hi = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_GNU_RELRO) continue;
        relro_lo = uintptr_t(base_) + ph.p_vaddr;
        relro_hi = relro_lo + ph.p_memsz;
        break;
    }
    // Compute the per-host-page protection by OR-ing each segment's
    // *contribution* to that page. The contribution is the segment's
    // p_flags, with W stripped if the segment's bytes inside this
    // page are entirely covered by GNU_RELRO. Doing the strip at the
    // segment-contribution level (not the whole-page level) is what
    // makes the boundary page work: seg1's RX contribution stays
    // intact while seg2's W-stripped RELRO contribution adds only R,
    // so the union is RX (not RWX, which macOS rejects under W^X).
    auto contribution = [&](const Seg& s, uintptr_t page_lo) -> int {
        uintptr_t seg_lo = std::max<uintptr_t>(page_lo, s.vaddr);
        uintptr_t seg_hi = std::min<uintptr_t>(
            page_lo + page_size_, s.vaddr + s.memsz);
        int prot = s.prot;
        if (relro_hi > 0
            && seg_lo >= relro_lo && seg_hi <= relro_hi) {
            prot &= ~PROT_WRITE;
        }
        return prot;
    };

    std::map<uintptr_t, int> page_prot;
    for (const auto& s : segments_) {
        uintptr_t lo = align_down(s.vaddr, page_size_);
        uintptr_t hi = align_up(s.vaddr + s.memsz, page_size_);
        for (uintptr_t p = lo; p < hi; p += page_size_) {
            page_prot[p] |= contribution(s, p);
        }
    }

    // ------------------------------------------------------------------
    // LAST-RESORT W^X conflict resolution.
    //
    // The RELRO strip above only helps when a PT_GNU_RELRO segment exists AND
    // it covers every writable byte sharing a host page with executable bytes.
    // Two real-world layouts break that assumption:
    //  (a) NO PT_GNU_RELRO at all -- Google's Crashlytics/Crashpad NDK
    //      libraries are linked `-z norelro`, so relro_hi stays 0 and the RW
    //      PT_LOAD contributes W to every page it touches.
    //  (b) PT_GNU_RELRO PRESENT but ending mid-host-page, e.g.
    //      libFirebaseCppMessaging.so: RELRO 0xd9e0-0xe000, but .data (0xe000)
    //      and .bss (0xe0d0) still land in host page 0xc000 alongside exec
    //      0xc000-0xc3e4. So this is not purely a norelro bug: any 4 KB-aligned
    //      PT_LOAD pair can collide once the host page is 16 KB.
    //
    // NORMALLY NEITHER REACHES HERE. choose_rx_rw_bias_shift() has already run
    // at map time and shifted the whole image so the RX|RW boundary sits on a
    // host page edge, which resolves both layouts with nothing losing a
    // permission (that is what AOSP's own 16 KiB app-compat mode does; see the
    // citation at the call site). This block only runs for an image whose
    // layout no shift can fix -- interleaved RW/RX PT_LOADs, or an exec
    // segment whose tail shares a 4 KiB page with the first RW segment. The
    // shift function logs loudly when it gives up, so reaching here is visible.
    //
    // For those, a virtual page carries exactly one protection and the two
    // segments' claims are genuinely irreconcilable. Measured on this host
    // (macOS 26, Apple Silicon):
    //   mprotect(p+4096, 4096, PROT_READ|PROT_EXEC) -> EINVAL (not 4 KB
    //     granular; XNU enforces VM_MAP_PAGE_MASK alignment), and
    //   mprotect(p, 16384, PROT_READ|PROT_WRITE|PROT_EXEC) -> EACCES
    //     (arm64 W^X; RWX is unavailable without a JIT mapping).
    //
    // Resolution: drop W, never X. Dropping X is a GUARANTEED failure -- the
    // exec bytes on a straddling page are the `.text` tail plus the whole
    // `.plt`, so the next intra-image call faults immediately. Dropping W is
    // what RELRO already does, one page wider, and the writable sections on
    // these pages are dominated by linker-written-then-read-only data
    // (.data.rel.ro/.got/.dynamic/.init_array/.fini_array) whose writing --
    // our relocation pass -- has already finished by the time this runs.
    // It is NOT free, though: any `.data`/`.bss` sharing the page also freezes,
    // and a guest store there will fault. That residual is reported below
    // rather than hidden, so such a fault is immediately attributable.
    //
    // MACAPK_RWX_PAGE_POLICY=x flips the sacrifice to X for diagnosis;
    // default (unset or "w") drops W.
    const char* rwx_policy = std::getenv("MACAPK_RWX_PAGE_POLICY");
    const bool rwx_drop_exec = rwx_policy && rwx_policy[0] == 'x';
    size_t rwx_pages = 0, rwx_bytes = 0;
    uintptr_t rwx_first = 0, rwx_last = 0;
    for (auto& kv : page_prot) {
        int& prot = kv.second;
        if ((prot & PROT_WRITE) == 0 || (prot & PROT_EXEC) == 0) continue;
        const uintptr_t page_lo = kv.first;
        // Measure the writable byte range actually losing W on this page,
        // so the log names real bytes rather than a whole 16 KB page.
        uintptr_t w_lo = 0, w_hi = 0;
        for (const auto& s : segments_) {
            if ((s.prot & PROT_WRITE) == 0) continue;
            uintptr_t lo = std::max<uintptr_t>(page_lo, s.vaddr);
            uintptr_t hi = std::min<uintptr_t>(
                page_lo + page_size_, s.vaddr + s.memsz);
            if (lo >= hi) continue;
            if (relro_hi > 0 && lo >= relro_lo && hi <= relro_hi) continue;
            if (w_hi == 0) { w_lo = lo; w_hi = hi; }
            else { w_lo = std::min(w_lo, lo); w_hi = std::max(w_hi, hi); }
        }
        ++rwx_pages;
        rwx_bytes += (w_hi > w_lo) ? (w_hi - w_lo) : 0;
        if (rwx_first == 0) rwx_first = w_lo;
        rwx_last = w_hi;
        std::fprintf(stderr,
            "[arm64-native] %s: host page %p would need RWX (4KB-aligned "
            "PT_LOADs on a %zuKB host page); dropping %s. Writable bytes "
            "affected: +0x%llx..+0x%llx (%llu B, file vaddr).\n",
            dbg_name_, (void*)page_lo, page_size_ / 1024,
            rwx_drop_exec ? "X" : "W",
            (unsigned long long)(w_lo - uintptr_t(base_)),
            (unsigned long long)(w_hi - uintptr_t(base_)),
            (unsigned long long)(w_hi > w_lo ? w_hi - w_lo : 0));
        // An R_AARCH64_IRELATIVE slot is written by
        // apply_ifunc_relocations() AFTER this pass, so a deferred slot
        // inside a page we are about to freeze would fault on store. None
        // of the known-affected libraries emit IRELATIVE (verified: 0 in
        // every libcrashlytics*/libFirebaseCpp* in the corpus), but name
        // the hazard rather than let it surface as a mystery SIGBUS.
        for (const auto& d : deferred_ifunc_) {
            uintptr_t slot = reinterpret_cast<uintptr_t>(d.second);
            if (slot >= page_lo && slot < page_lo + page_size_
                && !rwx_drop_exec) {
                std::fprintf(stderr,
                    "[arm64-native] %s: WARNING deferred IRELATIVE slot %p "
                    "lies in a page losing W; its resolver store will "
                    "fault.\n", dbg_name_, (void*)slot);
            }
        }
        if (rwx_drop_exec) prot &= ~PROT_EXEC;
        else               prot &= ~PROT_WRITE;
    }
    if (rwx_pages > 1) {
        std::fprintf(stderr,
            "[arm64-native] %s: resolved %zu RWX host page(s), %zu writable "
            "bytes total now read-only (+0x%llx..+0x%llx).\n",
            dbg_name_, rwx_pages, rwx_bytes,
            (unsigned long long)(rwx_first - uintptr_t(base_)),
            (unsigned long long)(rwx_last - uintptr_t(base_)));
    }

    // Coalesce adjacent same-prot pages into single mprotect calls
    // and apply.
    auto it = page_prot.begin();
    while (it != page_prot.end()) {
        uintptr_t start = it->first;
        int prot = it->second;
        uintptr_t end = start + page_size_;
        auto next = std::next(it);
        while (next != page_prot.end()
               && next->first == end
               && next->second == prot) {
            end += page_size_;
            ++next;
        }
        if (mprotect(reinterpret_cast<void*>(start), end - start,
                     prot) != 0) {
            std::fprintf(stderr,
                "[arm64-native] %s: mprotect(%p,%zu,0x%x) failed: %s\n",
                dbg_name_, (void*)start, end - start, prot,
                std::strerror(errno));
            return false;
        }
        it = next;
    }
    return true;
}

void Image::apply_ifunc_relocations() {
    // Run the R_AARCH64_IRELATIVE resolvers recorded during
    // apply_relocations(). MUST be called after
    // apply_segment_protections() has flipped the executable PT_LOADs to
    // RX: the resolver is guest .text and is non-executable until then.
    //
    // Semantics (Bionic linker_relocate.cpp R_GENERIC_IRELATIVE):
    //   result = resolver();   // resolver() takes no args, returns addr
    //   *slot  = result;
    // The resolver picks the best implementation (e.g. an arch-tuned
    // memcpy / strcmp) and returns its address; we store that in the GOT
    // slot. Bionic notes "ifuncs are called as soon as possible so that
    // string functions work" -- we honour that by running them here,
    // before run_init_array() (constructors may already call ifunc-backed
    // libc routines), which load() invokes after this pass.
    using ifunc_resolver_t = uint64_t (*)(void);
    for (const auto& d : deferred_ifunc_) {
        ifunc_resolver_t resolver =
            reinterpret_cast<ifunc_resolver_t>(d.first);
        *d.second = resolver();
    }
    if (!deferred_ifunc_.empty()) {
        std::fprintf(stderr,
            "[arm64-native] %s: resolved %zu ifunc reloc(s)\n",
            dbg_name_, deferred_ifunc_.size());
    }
}

// A pairip import-protected library (libmaesdk = MS 1DS telemetry,
// libPlayFabMultiplayer = multiplayer -- both NON-engine) STRIPS its imports'
// relocs: they are resolved at load by the pairip VM (ExecuteProgram), which
// self-verifies and DETONATES on our non-ART HotSpot-on-macOS process
// (verify-then-resolve -> the holes are UNFILLABLE, proven; the import names are
// 96% VM-only, not statically decodable). So its hundreds of reloc-less .got.plt
// slots stay at the un-based link-time .plt[0], and ANY of its guest code that
// branches through one SIGSEGVs. We detect that structurally -- a large count of
// reloc-less holes in the pltrel span -- so BOTH the constructors (run_init_array)
// and JNI_OnLoad (native_dlsym) can be neutered, letting the boot reach the ENGINE
// (libminecraftpe has ZERO such holes). STRUCTURAL predicate, never a library
// name; general to any pairip-import-protected lib. Gated on MACAPK_PAIRIP_GATE_EMU.
bool Image::is_pairip_import_protected() const {
    if (pairip_protected_ >= 0) return pairip_protected_ == 1;
    pairip_protected_ = 0;
    // Default-ON (v1.0.1573): the pairip handling is STRUCTURAL (the holes>20 test below
    // is what actually classifies a lib), so running it for every image is safe -- a normal
    // lib reads ~0 holes and is not flagged. Kill switch: MACAPK_PAIRIP_GATE_OFF=1.
    if (std::getenv("MACAPK_PAIRIP_GATE_OFF") || !pltrel_ || pltrel_count_ < 8 || !base_)
        return false;
    // Count the reloc-less holes ACCURATELY by VALUE, not by a span-vs-count
    // estimate. A pairip-stripped import carries no JUMP_SLOT reloc, so after our
    // eager (BIND_NOW) relocation its .got.plt slot still holds the un-based
    // link-time .plt[0] -- a FILE vaddr, always < 4GB. Every RESOLVED slot (guest
    // base_+sym, an intra-lib address, or a host libSystem/shim address) holds a
    // real MAPPED address, which on macOS arm64 is ALWAYS >= 4GB because __PAGEZERO
    // reserves 0-4GB. So "0 < slot < 4GB" == "reloc-less hole", and it stays correct
    // even when a host shim (e.g. libmacapk-bionic-shim ~0x102000000) maps BELOW the
    // guest base_ -- that is still >= 4GB. This reads ~0 for a normal engine binary
    // (libminecraftpe: every slot relocated, however large/sparse its .got.plt) and
    // hundreds for a pairip import-protected lib. The earlier span-vs-count estimate
    // false-positived the engine -- its .got.plt span is >> its reloc count -- and so
    // wrongly skipped libminecraftpe's 2275 C++ constructors, crashing it right after
    // android_main on uninitialized globals.
    // Two facts force this shape:
    //  (1) A true pairip hole has NO JUMP_SLOT reloc, whereas an ordinary UNRESOLVED
    //      import DOES have one (it just failed to resolve) -- and BOTH end up holding
    //      the un-based link-time .plt[0] (< 4GB). So we must first exclude reloc'd
    //      slots (libminecraftpe has >20 unresolved imports, which a value-only check
    //      wrongly counted as holes).
    //  (2) libminecraftpe's JUMP_SLOT r_offsets span ~9x its .got.plt (the runtime
    //      span is 427457 slots vs a 45823-slot .got.plt), so scanning [lo,hi] also
    //      reads unrelated GOT/data slots that happen to hold small values.
    // The discriminator that survives both: a pairip-protected lib's holes ALL hold
    // ONE value -- the lib's own link-time .plt[0] -- so the DOMINANT value-bucket
    // among reloc-less <4GB slots IS the true hole count (maesdk 189 @0x6808a0,
    // PlayFab 135 @0x404910). The engine's stray hits are scattered across the
    // polluted span and never cluster, so its dominant bucket stays tiny (< the
    // threshold). macOS __PAGEZERO reserves 0-4GB, so any RESOLVED slot is >= 4GB.
    constexpr uint64_t kPagezeroTop = 0x100000000ULL; // 4GB
    constexpr uint64_t kMinPltAddr  = 0x1000;         // skip tiny ints/flags/sizes
    constexpr size_t   kScanCap     = 4u << 20;        // bound load cost on a huge/sparse span
    std::unordered_set<uint64_t> relocd;
    relocd.reserve(pltrel_count_ * 2);
    uint64_t lo = ~uint64_t(0), hi = 0;
    for (size_t i = 0; i < pltrel_count_; ++i) {
        uint64_t off = pltrel_[i].r_offset;
        relocd.insert(off);
        if (off < lo) lo = off;
        if (off > hi) hi = off;
    }
    // Only .plt[0]-shaped values are eligible: a .plt on arm64 is 16-byte-entry code,
    // so its start (== the value every hole holds) is 16-aligned and >= 0x1000. This
    // rejects data sentinels that otherwise dominate the polluted span -- libminecraftpe
    // has 350 GOT/data slots holding 0xffffffff, which is not 16-aligned. (Verified:
    // the real .plt[0] of all three libs -- 0x6808a0, 0x404910, 0x11b90240 -- is
    // 16-aligned.)
    std::unordered_map<uint64_t, uint32_t> hist;
    size_t scanned = 0;
    for (uint64_t off = lo; off <= hi && scanned < kScanCap; off += sizeof(uint64_t), ++scanned) {
        if (relocd.count(off)) continue;
        uint64_t v = *reinterpret_cast<const uint64_t*>(base_ + off);
        if (v >= kMinPltAddr && v < kPagezeroTop && (v & 0xF) == 0) ++hist[v];
    }
    uint64_t plt0 = 0; uint32_t holes = 0;
    for (const auto& kv : hist) if (kv.second > holes) { holes = kv.second; plt0 = kv.first; }
    if (std::getenv("MACAPK_PAIRIP_HOLE_DUMP"))
        std::fprintf(stderr, "[pairip-holes] %s: pltrel=%zu span=%llu holes=%u @plt0=0x%llx\n",
            dbg_name_, pltrel_count_, (unsigned long long)((hi - lo) / 8 + 1),
            holes, (unsigned long long)plt0);
    pairip_plt0_ = plt0;   // persist for fill_pairip_import_holes()'s value==plt0 trap filter
    pairip_protected_ = (holes > 20) ? 1 : 0;
    return pairip_protected_ == 1;
}

// See the declaration. Reads MACAPK_PAIRIP_IMPORT_TABLE (lines of
// "<soname> 0x<got.plt slot vaddr> <symbol> [<src_dso>]", '#' comments), and for every hole
// slot of THIS lib whose vaddr appears in the table, resolves <symbol> via resolve_external
// and writes the address into base_+vaddr. Only slots that still hold the un-based <4GB plt0
// (i.e. genuine reloc-less holes) are touched; a normally-reloc'd slot (>=4GB) is left alone.
int Image::fill_pairip_import_holes() {
    if (pairip_imports_filled_ >= 0) return 0;   // computed once
    pairip_imports_filled_ = 0;
    const char* tbl = std::getenv("MACAPK_PAIRIP_IMPORT_TABLE");
    if (!tbl || !base_ || !is_pairip_import_protected()) return 0;
    const char* soname = dbg_name_ ? dbg_name_ : "";
    if (const char* s = std::strrchr(soname, '/')) soname = s + 1;
    FILE* f = std::fopen(tbl, "r");
    if (!f) { std::fprintf(stderr, "[pairip-gate] import-table %s: cannot open\n", tbl); return 0; }
    char line[512];
    int filled = 0, unresolved = 0, skipped = 0;
    while (std::fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char lib[256], sym[256];
        unsigned long long off = 0;
        if (std::sscanf(line, "%255s 0x%llx %255s", lib, &off, sym) != 3) continue;
        if (std::strcmp(lib, soname) != 0) continue;
        uint64_t* slot = reinterpret_cast<uint64_t*>(base_ + off);
        if (*slot >= 0x100000000ULL) { ++skipped; continue; }   // already resolved (reloc'd) -> leave
        uint64_t resolved = 0;
        if (sym[0] == '@') {                                    // intra-lib hole: base_ + <offset>
            unsigned long long ioff = std::strtoull(sym + 1, nullptr, 16);
            resolved = reinterpret_cast<uint64_t>(base_) + ioff;
        } else {                                                // external import: resolve by name
            resolved = reinterpret_cast<uint64_t>(resolve_external(sym, 1 /*STB_GLOBAL*/));
            // LFS alias fallback: on macOS `open`/`lseek`/`stat`/... are already 64-bit-offset,
            // so a Bionic `*64` variant our shim lacks (e.g. open64) resolves to the base name.
            size_t sl = std::strlen(sym);
            if (!resolved && sl > 2 && sym[sl-2] == '6' && sym[sl-1] == '4') {
                std::string bare(sym, sl - 2);
                resolved = reinterpret_cast<uint64_t>(resolve_external(bare, 1));
            }
        }
        if (resolved) { *slot = resolved; ++filled; }
        else          { ++unresolved;
                        // Point the hole at the named unresolved-trap instead of leaving the
                        // un-based plt0 (<4GB) -- so a CALL to a still-missing import lands in a
                        // graceful diagnostic instead of SIGSEGV'ing in __PAGEZERO at 0x6808a0.
                        *slot = reinterpret_cast<uint64_t>(&macapk_unresolved_trap);
                        std::fprintf(stderr, "[pairip-gate]   UNRESOLVED import: %s (slot 0x%llx) -> trap\n",
                                     sym, (unsigned long long)off); }
    }
    std::fclose(f);
    // GENERIC safety net: any reloc-less .got.plt hole NOT covered by the table still holds the
    // un-based plt0 (<4GB) and would SIGSEGV in __PAGEZERO the moment guest code branches through
    // it. Point every such remaining hole at the named unresolved-trap, so a missed/uncaptured
    // import degrades to a graceful diagnostic instead of a hard crash. Mirrors is_pairip_import_
    // protected()'s hole shape (reloc-less, 0x1000 <= v < 4GB, 16-aligned).
    int trapped = 0;
    // Run the safety net whenever this lib is pairip-import-protected (pairip_plt0_ is set by
    // is_pairip_import_protected(), which fill returns early without above), NOT only when the
    // table filled something. An EMPTY or incomplete table (filled==0) otherwise left every
    // reloc-less plt0 hole live, and the first guest branch through one SIGSEGV'd in __PAGEZERO
    // at the un-based plt0 -- measured 2026-10-07: MC 52.3 with a header-only import table
    // crashed at pc=0x6808a0 (= libmaesdk's own plt0). Trapping stays value-gated on
    // == pairip_plt0_ below, so it still never overwrites a <4GB data constant.
    if (pairip_plt0_ && pltrel_ && pltrel_count_ >= 8) {
        std::unordered_set<uint64_t> relocd; relocd.reserve(pltrel_count_ * 2);
        uint64_t lo = ~uint64_t(0), hi = 0;
        for (size_t i = 0; i < pltrel_count_; ++i) {
            uint64_t o = pltrel_[i].r_offset; relocd.insert(o);
            if (o < lo) lo = o; if (o > hi) hi = o;
        }
        // A genuine reloc-less hole holds EXACTLY the lib's link-time .plt[0] (pairip_plt0_,
        // the dominant value bucket). Match on that, NOT on "any <4GB 16-aligned word": the
        // pltrel r_offset span [lo,hi] can run past .got.plt into .data.rel.ro/.data, where
        // legitimate CONSTANTS (libmaesdk: 0x7fff0000, 0x40000000, 0x20000, 0x40000) are also
        // <4GB and 16-aligned -- trapping those overwrites real data with a code pointer and
        // SIGSEGVs the moment the lib reads/derefs the constant.
        if (pairip_plt0_) {
            for (uint64_t o = lo; o <= hi; o += sizeof(uint64_t)) {
                if (relocd.count(o)) continue;
                uint64_t* s = reinterpret_cast<uint64_t*>(base_ + o);
                if (*s == pairip_plt0_) {
                    *s = reinterpret_cast<uint64_t>(&macapk_unresolved_trap); ++trapped;
                    if (std::getenv("MACAPK_PAIRIP_HOLE_DUMP"))
                        std::fprintf(stderr, "[pairip-gate]   TRAP uncaptured hole slot 0x%llx\n",
                                     (unsigned long long)o);
                }
            }
        }
    }
    std::fprintf(stderr,
        "[pairip-gate] %s: filled %d import holes from table (%d unresolved, %d already-reloc'd), "
        "trapped %d remaining hole(s)\n",
        soname, filled, unresolved, skipped, trapped);
    pairip_imports_filled_ = (filled > 20) ? 1 : 0;
    pairip_imports_complete_ = (pairip_imports_filled_ == 1 && unresolved == 0 && trapped == 0);
    return filled;
}

// FNV-1a-64 of a byte range -- the per-build positive-ID hash for a code patch (below).
static uint64_t macapk_pairip_fnv1a64(const uint8_t* p, size_t n) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 0x100000001b3ULL; }
    return h;
}

// See the declaration. Globs MACAPK_PAIRIP_CODE_PATCH_DIR for "<soname>__0x<vaddr>.bin" and memcpy's
// each into base_+vaddr, overwriting the pairip-encrypted .text with its decrypted bytes -- but ONLY
// after a POSITIVE-ID byte-check that the loaded image is the exact build the patch was harvested from
// (the per-APK rule: byte-check before writing guest memory at a hardcoded vaddr). Each patch carries a
// sidecar "<name>.fnv" holding the FNV-1a-64 of the PRISTINE (pre-patch) bytes at [vaddr, vaddr+sz); we
// verify the currently-mapped bytes match before writing, and SKIP (never corrupt) on any mismatch or a
// missing sidecar. base_+vaddr is pristine here because a pairip-protected lib is neutered (init_array
// skipped) so its VM never runs to decrypt in place.
int Image::apply_pairip_code_patches() {
    const char* dir = std::getenv("MACAPK_PAIRIP_CODE_PATCH_DIR");
    if (!dir || !base_ || !is_pairip_import_protected()) return 0;
    const char* soname = dbg_name_ ? dbg_name_ : "";
    if (const char* s = std::strrchr(soname, '/')) soname = s + 1;
    DIR* d = ::opendir(dir);
    if (!d) return 0;
    char prefix[300];
    std::snprintf(prefix, sizeof prefix, "%s__0x", soname);
    size_t plen = std::strlen(prefix);
    int patched = 0;
    for (struct dirent* e; (e = ::readdir(d)); ) {
        if (std::strncmp(e->d_name, prefix, plen) != 0) continue;
        size_t nlen = std::strlen(e->d_name);
        if (nlen < 5 || std::strcmp(e->d_name + nlen - 4, ".bin") != 0) continue;  // patches (.bin) only
        unsigned long long vaddr = std::strtoull(e->d_name + plen, nullptr, 16);
        char path[1024];
        std::snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        FILE* f = std::fopen(path, "rb");
        if (!f) continue;
        std::fseek(f, 0, SEEK_END); long sz = std::ftell(f); std::fseek(f, 0, SEEK_SET);
        if (sz > 0) {
            std::vector<uint8_t> buf((size_t)sz);
            if (std::fread(buf.data(), 1, (size_t)sz, f) == (size_t)sz) {
                // Read the per-build positive-ID sidecar "<name-without-.bin>.fnv".
                uint64_t want = 0;
                char fnvpath[1024];
                std::snprintf(fnvpath, sizeof fnvpath, "%s/%.*s.fnv",
                              dir, (int)(nlen - 4), e->d_name);
                if (FILE* mf = std::fopen(fnvpath, "r")) {
                    if (std::fscanf(mf, "%llx", &want) != 1) want = 0;
                    std::fclose(mf);
                }
                uint64_t got = macapk_pairip_fnv1a64(
                    reinterpret_cast<const uint8_t*>(base_) + vaddr, (size_t)sz);
                if (want && got == want) {
                    std::memcpy(base_ + vaddr, buf.data(), (size_t)sz);
                    ++patched;
                    std::fprintf(stderr,
                        "[pairip-gate] %s: patched %ld decrypted code bytes @0x%llx (build-id ok)\n",
                        soname, sz, vaddr);
                } else {
                    // Exact-FNV gate did not match. pairip re-encrypts .text per build, so the
                    // pristine bytes' FNV is per-build even though the DECRYPTED blob is build-
                    // invariant (docs/pairip-build-invariance_2026-10-08.md). Fall back to a BUILD-
                    // INVARIANT positive ID: the protected lib's .rela.plt is byte-identical across
                    // builds (measured 45.1/51.1/52.3), and the loader holds it as pltrel_. If an FNV
                    // of this image's .rela.plt matches the canonical value in "<name>.relfp", this IS
                    // the canonical maesdk import surface, so the invariant decrypted blob applies; a
                    // build whose import machinery changed does NOT match and is still skipped (never
                    // corrupting a different lib's .text).
                    uint64_t relfp_want = 0;
                    char relfppath[1024];
                    std::snprintf(relfppath, sizeof relfppath, "%s/%.*s.relfp",
                                  dir, (int)(nlen - 4), e->d_name);
                    if (FILE* rf = std::fopen(relfppath, "r")) {
                        if (std::fscanf(rf, "%llx", &relfp_want) != 1) relfp_want = 0;
                        std::fclose(rf);
                    }
                    uint64_t relfp_got = (pltrel_ && pltrel_count_)
                        ? macapk_pairip_fnv1a64(reinterpret_cast<const uint8_t*>(pltrel_),
                                                pltrel_count_ * sizeof(Elf64_Rela))
                        : 0;
                    if (relfp_want && relfp_got && relfp_got == relfp_want) {
                        std::memcpy(base_ + vaddr, buf.data(), (size_t)sz);
                        ++patched;
                        std::fprintf(stderr,
                            "[pairip-gate] %s: patched %ld decrypted code bytes @0x%llx "
                            "(invariant .rela.plt match 0x%016llx; pristine fnv 0x%016llx != per-build "
                            "0x%016llx)\n", soname, sz, vaddr, (unsigned long long)relfp_got,
                            (unsigned long long)got, (unsigned long long)want);
                    } else if (!want && !relfp_want) {
                        std::fprintf(stderr, "[pairip-gate] %s: SKIP code patch @0x%llx -- no .fnv or "
                            ".relfp positive-ID sidecar; refusing to write guest .text unverified\n",
                            soname, vaddr);
                    } else {
                        std::fprintf(stderr, "[pairip-gate] %s: SKIP code patch @0x%llx -- build mismatch "
                            "(pristine fnv 0x%016llx != expected 0x%016llx; .rela.plt fp 0x%016llx != "
                            "canonical 0x%016llx)\n", soname, vaddr, (unsigned long long)got,
                            (unsigned long long)want, (unsigned long long)relfp_got,
                            (unsigned long long)relfp_want);
                    }
                }
            }
        }
        std::fclose(f);
    }
    ::closedir(d);
    return patched;
}

// JNI_OnLoad replacement for a pairip import-protected lib (see
// is_pairip_import_protected + native_dlsym). Returns JNI_VERSION_1_6 so the JVM's
// NativeLibraries.load treats the library as loaded, WITHOUT running the guest's
// real JNI_OnLoad (which would branch through an un-based import hole -> SIGSEGV,
// as PlayFab's did at pc=0x404910). The library is left mapped and inert; if the
// engine later needs one of its natives it gets a clean UnsatisfiedLinkError rather
// than a native crash. This is a diagnostic bridge to reach android_main past the
// non-engine telemetry/multiplayer libs -- NOT a claim those libs function.
extern "C" int macapk_pairip_inert_jni_onload(void* /*vm*/, void* /*reserved*/) {
    return 0x00010006; // JNI_VERSION_1_6
}

void Image::run_init_array() {
    // Guest-entry boundary: this thread is about to run guest .init_array
    // C++ constructors (and, shortly after on the same loader thread,
    // JNI_OnLoad), so enable x18 preservation before any guest arm64 code
    // executes. Guarded by the helper's if(!bit), so this is free after the
    // first call on this thread. See docs/x18-custom-abi-toggle-entitlement_2026-07-14.md.
    macapk_enable_x18_preservation();
    // ...and give this thread a real Bionic TLS block in x18 before any guest code runs,
    // so a guest reading x18 sees a thread pointer instead of the probe's sentinel.
    macapk_x18_install_guest_tls();
    // Escape hatch: MACAPK_ARM64_SKIP_INIT=1 skips C++ static
    // initializers entirely. Useful while bringing up the Bionic-ABI
    // shim coverage -- a hanging init_array entry would otherwise
    // halt loader bring-up before we can see what else is missing.
    // For real runs this should stay unset; many engines rely on
    // static-init for vtable installation and global singletons.
    const char* skip_env = std::getenv("MACAPK_ARM64_SKIP_INIT");
    if (skip_env && (skip_env[0] == '1' || skip_env[0] == 't'
                     || skip_env[0] == 'T')) {
        std::fprintf(stderr,
            "[arm64-native] %s: SKIPPING %zu init_array entries "
            "(MACAPK_ARM64_SKIP_INIT)\n",
            dbg_name_, init_array_count_);
        return;
    }
    // DIAGNOSTIC (env-gated, lib name comes from the env -- not hardcoded):
    // MACAPK_ARM64_SKIP_INIT_LIB=<substr> skips init_array ONLY for loaded
    // images whose soname contains <substr>, while keeping the library MAPPED
    // and its symbols RESOLVABLE. This isolates "does this library's static
    // init (e.g. an inline-hook mod's KittyMemory hook installation) break the
    // host app?" from "is the library needed at all?" -- MACAPK_ARM64_SKIP_LIBS
    // can't answer that because it unloads the lib entirely, breaking apps that
    // call the lib's injected methods (e.g. an AN1-modded MCPE). Use to confirm
    // the libNotlib-hooks-block-the-boot hypothesis: MACAPK_ARM64_SKIP_INIT_LIB=Notlib.
    if (const char* only = std::getenv("MACAPK_ARM64_SKIP_INIT_LIB")) {
        if (only[0] && dbg_name_ && std::strstr(dbg_name_, only)) {
            std::fprintf(stderr,
                "[arm64-native] %s: SKIPPING %zu init_array entries "
                "(MACAPK_ARM64_SKIP_INIT_LIB=%s match)\n",
                dbg_name_, init_array_count_, only);
            return;
        }
    }
    // pairip import-protected lib -> skip its init entirely. A pairip-VM-packed lib
    // (e.g. libmaesdk = MS 1DS telemetry, libPlayFabMultiplayer) STRIPS its imports'
    // relocs: they are resolved at load by the pairip VM (ExecuteProgram), which
    // self-verifies and DETONATES on our non-ART HotSpot-on-macOS process
    // (verify-then-resolve, so the imports are UNFILLABLE -- proven, docs/pairip-gate-
    // intercept-and-maesdk-reloc_2026-09-17.md, and the import names are 96% VM-only,
    // not statically decodable). Its hundreds of reloc-less .got.plt holes therefore
    // stay at the un-based link-time .plt[0], and its first constructor SIGSEGVs
    // calling one. Skip DT_INIT + init_array (the lib stays MAPPED and symbol-
    // resolvable); the ENGINE (libminecraftpe has ZERO such holes) is untouched, and
    // its own integrity DT_INIT is handled by the in-layer gate. STRUCTURAL predicate
    // (reloc-less-hole COUNT), never a package/lib name -- general to any pairip-
    // import-protected lib. Only under MACAPK_PAIRIP_GATE_EMU. NOTE this skips the
    // guest CONSTRUCTORS (DT_INIT + init_array); the lib's JNI_OnLoad is a SEPARATE
    // entry the JVM calls after the loader returns, neutered in native_dlsym().
    if (is_pairip_import_protected() && !pairip_imports_filled()) {
        std::fprintf(stderr,
            "[pairip-gate] %s: import-protected; SKIPPING DT_INIT + "
            "%zu init_array entries (lib stays mapped; engine untouched)\n",
            dbg_name_, init_array_count_);
        return;
    }
    // (Earlier sessions special-cased libc++_shared.so to skip its
    // init_array because DoIOSInit crashed at `ldr x8, [x23, #0x28]`.
    // That load is the NDK stack-canary cookie, not a runtime-globals
    // pointer; with apply_stack_canary_neutralization() rewriting the
    // pattern at load time the init function now runs cleanly. If a
    // future regression returns to that crash signature, the right
    // first move is to log which segment didn't get canary-patched,
    // not to add back a per-library init_array skip.)
    bool verbose = std::getenv("MACAPK_ARM64_INIT_VERBOSE") != nullptr;
    // DIAGNOSTIC (MACAPK_PAIRIP_CFG_PROBE, default-OFF; remove once the pairip
    // import-resolver runs or is replaced): a pairip-import lib's DT_INIT calls
    // libpairipcore's ExecuteProgram, which EARLY-RETURNS (leaving its GOT holes
    // at the un-based .plt[0]) unless three .bss config-guard globals are set by
    // libpairipcore's JNI_OnLoad. Offsets from the 2026-09-17 ExecuteProgram
    // decode (docs/pairip-gate-intercept-and-maesdk-reloc_2026-09-17.md). Read
    // them here (right before this lib's DT_INIT) to confirm whether the resolver
    // will run. READ-only; no app-name branch (matches any pairipcore image).
    if (init_func_ && std::getenv("MACAPK_PAIRIP_CFG_PROBE")) {
        for (Image* im : loaded_images()) {
            if (!im || !im->name() || !std::strstr(im->name(), "pairipcore")) continue;
            const uint8_t* pb = im->base();
            uint32_t flag = *reinterpret_cast<const uint32_t*>(pb + 0x9ce50);
            uint64_t p1   = *reinterpret_cast<const uint64_t*>(pb + 0x9d048);
            uint64_t p2   = *reinterpret_cast<const uint64_t*>(pb + 0x9d050);
            std::fprintf(stderr,
                "[pairip-cfg] %s DT_INIT: pairipcore@%p flag@0x9ce50=%u "
                "ptr@0x9d048=0x%llx ptr@0x9d050=0x%llx (all-nonzero => resolver runs)\n",
                dbg_name_, (const void*)pb, flag,
                (unsigned long long)p1, (unsigned long long)p2);
            std::fflush(stderr);
            break;
        }
    }
    // DT_INIT runs FIRST, before the array constructors -- bionic
    // soinfo::call_constructors() does call_function("DT_INIT", init_func_)
    // then call_array("DT_INIT_ARRAY", ...). Dropping it (as this loader did
    // until v1.0.12xx) is fatal for any image whose .init the array
    // constructors depend on: libmaesdk.so's .init is a GOT-fixup routine
    // that resolves ~189 scattered .got.plt slots still pointing at .plt[0];
    // without it the first constructor to call one of those stubs branches to
    // the un-based .plt[0] (0x6808a0) and SIGSEGVs. The x18/TLS setup at the
    // top of this function already covers the guest code DT_INIT runs.
    if (init_func_ && init_func_ != ~uint64_t(0)) {
        if (verbose) {
            std::fprintf(stderr,
                "[arm64-native] %s: DT_INIT -> %p (off=0x%llx)\n",
                dbg_name_, (void*)init_func_,
                (unsigned long long)(init_func_ - uint64_t(base_)));
            std::fflush(stderr);
        }
        reinterpret_cast<void (*)(void)>(init_func_)();
    }
    for (size_t i = 0; i < init_array_count_; ++i) {
        uint64_t addr = init_array_[i];
        if (addr == 0 || addr == ~uint64_t(0)) continue;
        if (verbose) {
            std::fprintf(stderr,
                "[arm64-native] %s: init_array[%zu] -> %p (off=0x%llx)\n",
                dbg_name_, i, (void*)addr,
                (unsigned long long)(addr - uint64_t(base_)));
            // Flush every entry; if the next call crashes, this trace
            // line is otherwise lost in stderr's full-buffered mode
            // (which it is whenever the runtime captures stderr to a
            // file via the launcher).
            std::fflush(stderr);
        }
        // .init_array entries on AArch64 are absolute addresses --
        // they're produced by the static linker as part of the file
        // image, and R_AARCH64_RELATIVE relocations have already
        // converted them to runtime addresses by the time we get here.
        auto fn = reinterpret_cast<void (*)(void)>(addr);
        fn();
    }
    std::fprintf(stderr,
        "[arm64-native] %s: ran %zu init_array entries\n",
        dbg_name_, init_array_count_);
}

bool Image::load(const uint8_t* blob, size_t blob_size, const char* dbg) {
    dbg_name_ = dbg ? dbg : "?";
    page_size_ = sysconf(_SC_PAGESIZE);

    if (blob_size < sizeof(Elf64_Ehdr)) {
        std::fprintf(stderr,
            "[arm64-native] %s: blob too small for ELF header\n", dbg_name_);
        return false;
    }
    Elf64_Ehdr eh;
    std::memcpy(&eh, blob, sizeof(eh));
    // t3-mmap (2026-07-24): magic/class/endian/machine/ET_DYN/phentsize/
    // phdr-bounds checks factored out to macapk_validate_ehdr() so
    // load_from_path() (Lever 3, below) enforces the identical contract
    // against a pread-sourced header instead of duplicating this block.
    if (!macapk_validate_ehdr(eh, blob_size, dbg_name_)) return false;
    e_entry_ = eh.e_entry;

    // Read the program-header table into phdrs_ from the source blob.
    // Both map_segments and parse_dynamic walk this table; doing it
    // once up front avoids re-keying through file offsets / vaddrs.
    phdrs_.resize(eh.e_phnum);
    std::memcpy(phdrs_.data(), blob + eh.e_phoff,
                size_t(eh.e_phnum) * sizeof(Elf64_Phdr));
    // The section header table sits in no PT_LOAD, so read it now, while the
    // blob is still resident (it is reclaimed right after map_segments()).
    read_exec_sections(eh, blob, blob_size, -1);

    // ---- Lever 2: try to open + validate the on-disk source .so so
    // map_segments() can map non-writable congruent segments file-backed
    // instead of memcpy'ing them into anon (dirty) RAM. so_fd = -1 whenever
    // the gate is off, the file is absent, or it doesn't match the embedded
    // blob (size + header + phdr table + per-segment samples); in every such
    // case map_segments() falls back to the identical-to-today memcpy path.
    int so_fd = -1;
    if (macapk_filebacked_env_on()) {
        std::string sopath = macapk_source_so_path(dbg_name_);
        if (!sopath.empty()) {
            int fd = ::open(sopath.c_str(), O_RDONLY);
            if (fd >= 0) {
                if (macapk_validate_so_file(fd, blob, blob_size, eh, phdrs_,
                                            dbg_name_)) {
                    so_fd = fd;   // map_segments closes/uses; keep open until then
                } else {
                    ::close(fd);
                    std::fprintf(stderr,
                        "[arm64-native] %s: source .so validation mismatch "
                        "(%s) -- memcpy path\n", dbg_name_, sopath.c_str());
                }
            }
        }
    }

    bool ms_ok = map_segments(blob, blob_size, so_fd);
    if (so_fd >= 0) ::close(so_fd);
    if (!ms_ok)                               return false;

    // ===== RAM: reclaim the embedded ELF blob's resident pages =====
    // (efficiency audit 2026-07-24: moved here, right after map_segments(),
    // from the very end of load() -- grepped every use of the `blob`/
    // `blob_size` identifiers across the whole function: map_segments()
    // above is PROVABLY the last read of either. Nothing between here and
    // the old position (parse_dynamic, apply_relocations, the x18 scan,
    // run_init_array, every patch pass) touches blob, so reclaiming
    // immediately after the copy that makes it redundant is behavior-
    // preserving and shrinks the double-residency window from the full
    // load duration (up to ~58s on a cold MCPE launch: relocations +
    // the x18 scan) down to milliseconds. See
    // docs/efficiency-translator-stage6_2026-07-24.md Finding #1.)
    //
    // The wrapper .dylib embeds the ORIGINAL arm64 ELF via .incbin in
    // its __TEXT (arm64_pack.cpp). map_segments() above memcpy'd every
    // PT_LOAD body OUT of this blob into the anonymous span at base_,
    // faulting in the full ELF (for a big guest like libminecraftpe that
    // is ~326 MB). After that memcpy the blob is never read again: dlsym,
    // relocations, the x18 rewrite, every patch pass and run_init_array
    // all operate on base_ (the anon copy) and on phdrs_ (copied at
    // load() entry, line ~1626). So the ELF sat in physical RAM TWICE --
    // the clean file-backed blob AND the dirty anon copy -- which on an
    // 8 GB host is real memory pressure (it drove MCPE's swap-out).
    //
    // NOTE on the primitive. madvise(MADV_DONTNEED/MADV_FREE) does NOT
    // reclaim CLEAN file-backed pages on Darwin -- it only DEACTIVATES
    // them (measured: resident unchanged, mincore still INCORE;
    // MADV_FREE_REUSABLE returns EPERM on a file-backed r-x map). The one
    // primitive that immediately returns the physical pages is replacing
    // the blob's interior with a fresh zero-fill anonymous mapping via
    // mmap(MAP_FIXED|MAP_ANON): that unmaps the file-backed pages (300 MB
    // -> 0 resident, verified standalone) and the anon replacement is
    // zero-fill-on-demand, so it costs no physical until touched -- and
    // nothing touches it. Net: ~320 MB of clean resident returned to the
    // 8 GB host, sparing dirty JVM/malloc pages from the swap file.
    //
    // Safety: the range is aligned INWARD (align_up start, align_down
    // end) so we replace ONLY pages fully inside the blob and never a
    // boundary page it shares with the wrapper's Mach header, export
    // trampolines, constructor code, or __eh_frame -- those stay live and
    // file-backed (the trampolines ARE how native_dlsym resolves symbols;
    // the header/unwind info is what crash symbolication reads). The
    // interior blob pages hold pure .incbin ELF data: no code, no symbol,
    // no unwind record targets them, so no page-in code-signature
    // validation ever fires on the anon replacement. PROT_READ only (data,
    // never executed). Generic: fires for every Stage-6 image; a tiny
    // blob has no fully-covered interior page and this no-ops. Opt out
    // MACAPK_BLOB_MADVISE=0 (falls back to MADV_DONTNEED deactivation).
    if (blob) {
        const char* e = std::getenv("MACAPK_BLOB_MADVISE");
        bool discard = !(e && e[0] == '0');
        uintptr_t lo = align_up(uintptr_t(blob), page_size_);
        uintptr_t hi = align_down(uintptr_t(blob) + blob_size, page_size_);
        if (hi > lo) {
            size_t len = size_t(hi - lo);
            if (discard) {
                void* r = mmap(reinterpret_cast<void*>(lo), len, PROT_READ,
                               MAP_PRIVATE | MAP_ANON | MAP_FIXED, -1, 0);
                if (r == MAP_FAILED) {
                    std::fprintf(stderr,
                        "[arm64-native] %s: blob anon-discard(%#zx @ %#lx) "
                        "failed: %s -- falling back to MADV_DONTNEED\n",
                        dbg_name_, len, (unsigned long)lo,
                        std::strerror(errno));
                    madvise(reinterpret_cast<void*>(lo), len, MADV_DONTNEED);
                } else {
                    std::fprintf(stderr,
                        "[arm64-native] %s: reclaimed %#zx bytes of embedded "
                        "ELF blob (anon-discard)\n", dbg_name_, len);
                }
            } else {
                madvise(reinterpret_cast<void*>(lo), len, MADV_DONTNEED);
                std::fprintf(stderr,
                    "[arm64-native] %s: deactivated %#zx bytes of embedded "
                    "ELF blob (MADV_DONTNEED)\n", dbg_name_, len);
            }
        }
    }

    return load_common();
}

// t3-mmap (2026-07-24): Lever 3 -- open the on-disk .so directly and load
// it with NO embedded blob at any point (not even transiently): the header
// and phdr table come from two small pread()s, every segment byte comes
// from map_segments()'s copy_region() (mmap-from-fd for the file-backed
// interior, pread-from-fd for RW segments and head/tail slivers), and
// there is nothing to reclaim afterward because nothing extra was ever
// resident. Mirrors bionic's ElfReader::ReadElfHeader / ReadProgramHeaders
// (both pread the fd directly, no whole-file buffer) -- confirmed against
// aosp-mirror/platform_bionic linker/linker_phdr.cpp before writing this.
bool Image::load_from_path(const char* so_path_rel, const char* dbg) {
    dbg_name_ = dbg ? dbg : "?";
    page_size_ = sysconf(_SC_PAGESIZE);

    std::string abspath = macapk_resolve_relative_so_path(so_path_rel);
    if (abspath.empty()) {
        std::fprintf(stderr,
            "[arm64-native] %s: load_from_path: cannot resolve android_root/"
            "data_subdir for relative path '%s'\n",
            dbg_name_, so_path_rel ? so_path_rel : "(null)");
        return false;
    }
    int fd = ::open(abspath.c_str(), O_RDONLY);
    if (fd < 0) {
        // THE failure case the cache contract has to get right (see
        // arm64_pack.cpp's header comment + AppClassLoader.findLibrary()):
        // a cached wrapper whose source .so vanished (data-dir tampering, a
        // forced partial re-extract) must fail LOUDLY here, not silently
        // mmap garbage. AppClassLoader.findLibrary() is the primary net
        // (treats a cached-wrapper-without-its-.so as a cache MISS and
        // re-translates, degrading to the stub dylib if the source is truly
        // gone); this log line + clean `return false` is the defense in
        // depth for any path that reaches native code anyway. The caller
        // (__macapk_arm64_native_load_from_path) returns nullptr on false,
        // which the wrapper's constructor already checks before touching
        // any trampoline slot -- so this fails the ONE library's load, not
        // the process.
        std::fprintf(stderr,
            "[arm64-native] %s: source .so MISSING at %s (%s) -- this app's "
            "data directory needs re-extraction; this library will not load\n",
            dbg_name_, abspath.c_str(), std::strerror(errno));
        return false;
    }
    struct stat st;
    if (::fstat(fd, &st) != 0 || st.st_size < 0) {
        std::fprintf(stderr,
            "[arm64-native] %s: fstat(%s) failed: %s\n",
            dbg_name_, abspath.c_str(), std::strerror(errno));
        ::close(fd);
        return false;
    }
    uint64_t file_size = uint64_t(st.st_size);
    if (file_size < sizeof(Elf64_Ehdr)) {
        std::fprintf(stderr,
            "[arm64-native] %s: %s too small for an ELF header (%llu bytes)\n",
            dbg_name_, abspath.c_str(), (unsigned long long)file_size);
        ::close(fd);
        return false;
    }
    Elf64_Ehdr eh;
    if (!macapk_pread_full(fd, &eh, sizeof(eh), 0)) {
        std::fprintf(stderr,
            "[arm64-native] %s: failed reading ELF header from %s: %s\n",
            dbg_name_, abspath.c_str(), std::strerror(errno));
        ::close(fd);
        return false;
    }
    if (!macapk_validate_ehdr(eh, file_size, dbg_name_)) {
        ::close(fd);
        return false;
    }
    e_entry_ = eh.e_entry;
    phdrs_.resize(eh.e_phnum);
    if (!macapk_pread_full(fd, phdrs_.data(),
                           size_t(eh.e_phnum) * sizeof(Elf64_Phdr),
                           eh.e_phoff)) {
        std::fprintf(stderr,
            "[arm64-native] %s: failed reading program-header table from "
            "%s: %s\n", dbg_name_, abspath.c_str(), std::strerror(errno));
        ::close(fd);
        return false;
    }
    // The section header table sits in no PT_LOAD; pread it while fd is open.
    read_exec_sections(eh, nullptr, file_size, fd);

    // No lever-2 open/validate dance here: there is no second (embedded)
    // copy to validate against -- so_fd IS the one and only source, exactly
    // like bionic. map_segments() always file-backs the non-writable
    // segments when the gate is on (macapk_filebacked_env_on(), unaffected
    // by this call path) and pread's everything else straight from fd
    // (blob=nullptr routes copy_region() to the pread branch).
    bool ms_ok = map_segments(nullptr, file_size, fd);
    ::close(fd);
    if (!ms_ok) return false;

    std::fprintf(stderr,
        "[arm64-native] %s: loaded file-backed from %s (no embedded blob, "
        "no double residency)\n", dbg_name_, abspath.c_str());
    return load_common();
}

// The il2cpp / Unity diagnostic kit's knobs (Crossy Road 7.11.1 bring-up; the kit
// was DELETED 2026-09-27, see the tombstone in Image::load_common), plus
// MACAPK_SPLASH_SKIP, the Crossy SplashIntro .text patch deleted the same day by
// its own lane (docs/unity-il2cpp-splash-skip-retired_2026-09-27.md), which
// otherwise nothing would name. None of them is read any more, so a run that sets
// one says so ONCE rather than succeeding inertly. MACAPK_IL2CPP_EXC_LOG and
// MACAPK_IL2CPP_TIME_PROBE are not listed: the build-independent exception logger
// and Time probe they arm are kept. docs/il2cpp-diag-kit-retired_2026-09-27.md.
static void macapk_il2cpp_diag_kit_retired_note() {
    static std::atomic<bool> done{false};
    if (done.exchange(true, std::memory_order_relaxed)) return;
    // time_probe: the knob fed the kit's [time-probe], whose export-based successor
    // the line then names.
    static const struct { const char* name; bool time_probe; } kRetired[] = {
        {"MACAPK_UHOOK", true}, {"MACAPK_INTEG_COUNT", false}, {"MACAPK_BOOTCTRL_COUNT", false},
        {"MACAPK_CLASSINIT_LOG", false}, {"MACAPK_GC_APPEND_WATCH", false},
        {"MACAPK_IL2CPP_TRACE", true}, {"MACAPK_WAITSITE_LOG", false},
        {"MACAPK_SPLASH_TRACE", false}, {"MACAPK_SPLASH_FORCE", false},
        {"MACAPK_FIND_CORO", false}, {"MACAPK_CORO_TRACE", false}, {"MACAPK_RM_HOOK", false},
        {"MACAPK_RM_TRACE", false}, {"MACAPK_RM_PUMP", false}, {"MACAPK_OP_TRACE", false},
        {"MACAPK_FIX_MAINTHREAD", false}, {"MACAPK_FORCE_JOBSYNC", false},
        {"MACAPK_BOOT_ACTIVATE", false}, {"MACAPK_BOOT_ENABLE", false},
        {"MACAPK_BOOT_NATIVE_SCO", false}, {"MACAPK_BOOT_STARTCO", false},
        {"MACAPK_SPLASH_SKIP", false},
    };
    char set[640]; set[0] = '\0';
    bool probe_hint = false;
    for (const auto& k : kRetired) {
        if (!std::getenv(k.name)) continue;
        const size_t used = std::strlen(set);
        std::snprintf(set + used, sizeof set - used, "%s%s", used ? " " : "", k.name);
        probe_hint = probe_hint || k.time_probe;
    }
    if (set[0])
        std::fprintf(stderr,
            "[il2cpp-diag] %s: RETIRED and IGNORED (the Crossy Road 7.11.1 il2cpp / "
            "Unity diagnostic code that read them was deleted 2026-09-27; "
            "docs/il2cpp-diag-kit-retired_2026-09-27.md)%s\n", set,
            probe_hint ? "; the Time.* [time-probe] is now MACAPK_IL2CPP_TIME_PROBE=<N>" : "");
}

// MACAPK_OPENAL_STUB was the escape hatch of the libopenal alcOpenDevice NULL
// patch, DELETED 2026-09-27 (see the tombstone in Image::load_common). Nothing
// reads it any more, so a run that sets it says so ONCE instead of succeeding
// inertly: an A/B that sets it measures the app's own alcOpenDevice on both
// arms. Keyed on the variable alone, never on a library name.
// REMOVAL CONDITION: delete this note and its call once no doc, script or
// changelog-era A/B recipe still sets MACAPK_OPENAL_STUB (grep -a), no later
// than the next per-app-code audit.
// docs/libopenal-alcopendevice-retired_2026-09-27.md.
static void macapk_openal_stub_retired_note() {
    static std::atomic<bool> done{false};
    if (done.exchange(true, std::memory_order_relaxed)) return;
    if (const char* v = std::getenv("MACAPK_OPENAL_STUB"))
        std::fprintf(stderr,
            "[arm64-native] MACAPK_OPENAL_STUB=%s: RETIRED and IGNORED (the libopenal "
            "alcOpenDevice NULL patch it armed was deleted 2026-09-27; OpenAL Soft's own "
            "alcOpenDevice runs, as on a device; "
            "docs/libopenal-alcopendevice-retired_2026-09-27.md)\n", v);
}

bool Image::load_common() {
    // MACAPK_LOAD_TIMING=1: wall time of every pass in this load, printed as
    // `[load-timing] <lib>: <pass> <ms>` for passes >= 2 ms. A boot-time
    // silence INSIDE one image load is invisible to bootphases.py (it sees one
    // long gap); this attributes it. Measured 2026-09-22: libminecraftpe
    // spends ~7 s in this function on every WARM launch. Off by default; the
    // disabled cost is one branch per pass.
    static const bool lt_on = std::getenv("MACAPK_LOAD_TIMING") != nullptr;
    uint64_t lt_mark = lt_on ? clock_gettime_nsec_np(CLOCK_UPTIME_RAW) : 0;
    auto lap = [&](const char* what) {
        if (!lt_on) return;
        const uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        const double ms = (double)(now - lt_mark) / 1e6;
        if (ms >= 2.0)
            std::fprintf(stderr, "[load-timing] %s: %-44s %9.1f ms\n", dbg_name_, what, ms);
        lt_mark = now;
    };
    std::fprintf(stderr,
        "[arm64-native] %s: mapped, base=%p span=%zu\n",
        dbg_name_, (void*)base_, mapped_size_);
    if (!parse_dynamic())                     return false;
    std::fprintf(stderr,
        "[arm64-native] %s: parsed dyn (rela=%zu pltrel=%zu init=%zu)\n",
        dbg_name_, rela_count_, pltrel_count_, init_array_count_);
    parse_and_register_tls();   // PT_TLS -> tls_module_id_ (before relocs)
    lap("parse_dynamic + tls");
    if (!apply_relocations())                 return false;
    std::fprintf(stderr,
        "[arm64-native] %s: relocations applied\n", dbg_name_);
    // pairip: fill this import-protected lib's reloc-less .got.plt holes by name from the
    // harvested table, HERE -- after relocation (holes are detectable + hold the un-based
    // plt0) and BEFORE apply_segment_protections() RELRO-freezes the .got.plt read-only.
    // No-op unless MACAPK_PAIRIP_IMPORT_TABLE is set and this lib is import-protected.
    lap("apply_relocations");
    fill_pairip_import_holes();
    // pairip: overwrite this lib's pairip-encrypted .text segment(s) with their decrypted bytes
    // from MACAPK_PAIRIP_CODE_PATCH_DIR (harvested from a live run). Also here -- .text is still
    // writable before apply_segment_protections(). No-op unless the dir is set.
    lap("fill_pairip_import_holes");
    apply_pairip_code_patches();
    // t3-syms (2026-07-24): lookup() now resolves on demand by hashing the
    // query against the mapped GNU_HASH/DT_HASH tables (see the block
    // comment above gnu_hash_lookup()) instead of eagerly copying every
    // exported symbol's name into a permanently-resident map. The eager
    // build_export_map() only runs in the (empirically never-hit-by-any-
    // real-guest-so) case where an image has NEITHER hash table.
    lap("apply_pairip_code_patches");
    no_hash_table_ = !gnu_hash_ && !sysv_hash_;
    if (no_hash_table_) {
        build_export_map();
        std::fprintf(stderr,
            "[arm64-native] %s: NO hash table (DT_GNU_HASH/DT_HASH both "
            "absent) -- falling back to full-table export map (%zu "
            "entries); this should not happen for any real Android linker "
            "output\n",
            dbg_name_, exports_fallback_.size());
    } else {
        std::fprintf(stderr,
            "[arm64-native] %s: %zu exports indexed\n",
            dbg_name_, count_defined_named_hashed());
    }
    verify_hash_lookup_matches_legacy();   // no-op unless MACAPK_VERIFY_SYMBOL_HASH=1
    // t3-mmap (2026-07-24) load-identity verification tool. MACAPK_DUMP_SYMS=
    // <path> writes "<name>\t<file-vaddr-hex>\n" (sorted by name) for every
    // defined+named dynsym to "<path>.<soname>" -- one file per loaded
    // image, so a multi-lib app doesn't clobber itself. st_value is the
    // file vaddr baked into the ELF at build time: independent of base_
    // (ASLR), and -- the property this exists to check -- independent of
    // WHICH loader populated symtab_'s backing bytes (Image::load()'s
    // embedded-blob memcpy vs Image::load_from_path()'s Lever-3 pread/
    // mmap-from-fd). Translate the SAME .so once with each path
    // (MACAPK_ARM64_EMBED_BLOB=1 forces the old embed in arm64_pack.cpp)
    // and diff the two dumps: identical output proves the two loading
    // strategies produced byte-identical mapped images at every symbol's
    // address, not just "both ran without crashing." Diagnostic-only,
    // default off, same filter count_defined_named_hashed()/
    // verify_hash_lookup_matches_legacy() already use above.
    if (const char* dp = std::getenv("MACAPK_DUMP_SYMS")) {
        size_t nsyms = gnu_hash_ ? gnu_hash_symbol_count()
                     : sysv_hash_ ? sysv_hash_[1] : 0;
        std::vector<std::pair<std::string, uint64_t>> rows;
        rows.reserve(nsyms);
        for (size_t i = 0; i < nsyms; ++i) {
            const Elf64_Sym& s = symtab_[i];
            if (s.st_shndx != 0 && s.st_name != 0
                && (!strtab_size_ || s.st_name < strtab_size_)) {
                const char* name = strtab_ + s.st_name;
                if (*name) rows.emplace_back(name, s.st_value);
            }
        }
        std::sort(rows.begin(), rows.end());
        std::string path = std::string(dp) + "." + dbg_name_;
        FILE* f = std::fopen(path.c_str(), "w");
        if (f) {
            for (const auto& r : rows)
                std::fprintf(f, "%s\t%#llx\n", r.first.c_str(),
                             (unsigned long long)r.second);
            std::fclose(f);
            std::fprintf(stderr,
                "[arm64-native] %s: MACAPK_DUMP_SYMS wrote %zu symbols to "
                "%s\n", dbg_name_, rows.size(), path.c_str());
        } else {
            std::fprintf(stderr,
                "[arm64-native] %s: MACAPK_DUMP_SYMS: cannot open %s\n",
                dbg_name_, path.c_str());
        }
    }
    lap("export index (+ MACAPK_DUMP_SYMS)");
    apply_libfmod_jni_audio_args_patch();
    lap("apply_libfmod_jni_audio_args_patch");
    // (Tombstone: apply_libopenal_alcopendevice_patch, which overwrote the
    // entry of libopenal.so's exported alcOpenDevice with `mov x0, #0 ; ret`
    // so OpenAL Soft reported "no device", is DELETED. It ran here, before the
    // canary pass, because that pass rewrites the `ldr [x22,#0x28]` its 36-byte
    // signature included. The signature matched OpenGD v0.1.0-beta's
    // libopenal.so and no other build. It was default-OFF (MACAPK_OPENAL_STUB=1)
    // since v1.0.268, when the real AAudio and OpenSL ES shims replaced the
    // no-op stubs whose fake success it papered over. On a device the app's own
    // alcOpenDevice runs, and a backend that fails to open is OpenAL Soft's own
    // NULL. docs/libopenal-alcopendevice-retired_2026-09-27.md.)
    macapk_openal_stub_retired_note();  // one stderr line iff MACAPK_OPENAL_STUB is set
    // DIAGNOSTIC (MACAPK_NO_GUEST_TEXT_PATCH, default OFF): skip the scattered
    // .text rewrites (167 canary sites + MIDR reads) so the guest's own code is
    // left BYTE-UNMODIFIED. Purpose: test whether pairip's integrity self-hashes
    // its own libpairipcore .text and detects our patches as tamper (a native
    // device patches NOTHING; the on-device 1-byte BRK hook ALSO crashed).
    // Relies on the x18 entitlement so the original canary reads work unpatched.
    // docs/pairip-vm-oracle-fork-ptrace-is-the-lever_2026-09-07.md.
    bool no_text_patch = std::getenv("MACAPK_NO_GUEST_TEXT_PATCH") != nullptr;
    if (!no_text_patch) {
    if (size_t n = apply_stack_canary_neutralization()) {
        std::fprintf(stderr,
            "[arm64-native] %s: neutralised %zu stack-canary site(s)\n",
            dbg_name_, n);
    }
    lap("apply_stack_canary_neutralization");
    if (size_t n = apply_svc_brk_rewrite()) {
        std::fprintf(stderr,
            "[arm64-native] %s: rewrote %zu inline `svc #0` -> BRK (x16-hazard fix)\n",
            dbg_name_, n);
    }
    lap("apply_svc_brk_rewrite");
    if (size_t n = apply_midr_el1_neutralization()) {
        std::fprintf(stderr,
            "[arm64-native] %s: neutralised %zu MIDR_EL1 read(s)\n",
            dbg_name_, n);
    }
    } else {
        std::fprintf(stderr, "[arm64-native] %s: MACAPK_NO_GUEST_TEXT_PATCH -- "
            "skipping canary+MIDR .text rewrites (guest .text left unmodified)\n",
            dbg_name_);
    }
    // Runs with the other .text rewrites, while the pages are still writable
    // and before protections are applied. Order against the canary pass does
    // not matter: that pass keys on `mrs TPIDR_EL0` / `ldr [Xn,#0x28]` pairs,
    // neither of which appears in the eight-word syscall-wrapper signature, so
    // the two cannot see or destroy each other's markers.
    lap("apply_midr_el1_neutralization");
    if (size_t n = apply_raw_syscall_wrapper_redirect()) {
        std::fprintf(stderr,
            "[arm64-native] %s: redirected %zu raw syscall wrapper(s) to the "
            "syscall router\n", dbg_name_, n);
    }
    lap("apply_raw_syscall_wrapper_redirect");
    apply_zlib_inflate_tracepoint();
    lap("apply_zlib_inflate_tracepoint");
    apply_zlib_inflate_table_tracepoint();
    lap("apply_zlib_inflate_table_tracepoint");
    // (Tombstone: apply_libfmod_createSound_disable, which overwrote the
    // entries of libfmod's exported FMOD::System::createSound / createStream
    // with `mov w0, #18 ; ret` (FMOD_ERR_FILE_NOTFOUND) so FMOD never opened a
    // sound, is DELETED. It ran twice per image here. It was default-OFF
    // (MACAPK_FMOD_MUTE=1) since v1.0.1111, its crash theory was refuted three
    // times, and a mute is the forbidden fourth rung (it reports a failure for
    // a file that exists). On a device the app's own createSound runs and
    // reads through AAssetManager_open.
    // docs/fmod-createsound-mute-retired_2026-09-27.md.)
    // Run the libcocos-zlib redirect AFTER the inflate tracepoint so
    // the tracepoint's `b trampoline` first-instruction patch on inflate
    // gets OVERWRITTEN by the libz redirect (we no longer need the
    // diagnostic once the underlying bug is bypassed). The redirect
    // sends cocos's inflate / inflateInit2_ / inflateEnd / etc. straight
    // to macOS's libz.dylib, which doesn't have the Apple-Silicon-
    // specific zlib 1.2.8 bit-reverse edge case.
    apply_libcocos_zlib_redirect();
    lap("apply_libcocos_zlib_redirect #1");
    // Run the libcocos-zlib redirect AFTER the inflate tracepoint so
    // the tracepoint's `b trampoline` first-instruction patch on inflate
    // gets OVERWRITTEN by the libz redirect (we no longer need the
    // diagnostic once the underlying bug is bypassed). The redirect
    // sends cocos's inflate / inflateInit2_ / inflateEnd / etc. straight
    // to macOS's libz.dylib, which doesn't have the Apple-Silicon-
    // specific zlib 1.2.8 bit-reverse edge case.
    apply_libcocos_zlib_redirect();
    // Engine-generic cocos2d-x v2 fix: rewrite addChild's
    // cbnz-skip-assert into cbz-early-return, so NULL children are
    // silently dropped instead of crashing the assert-fall-through
    // path. Applies to any image that contains the addChild assert
    // pattern; HCR 1.43 has it, the GD family does too.
    lap("apply_libcocos_zlib_redirect #2");
    apply_cocos2dx_v2_addchild_null_safety();
    // (Tombstone: apply_cocos2dx_force_fopen_path, the cocos2d-x
    // CCFileUtils::getFileData byte patch that rewrote the '/'-test b.eq into
    // b so every asset read took fopen onto an EXTRACTED tree, is DELETED. It
    // was default-OFF since v1.0.1586 and its signature matched one binary in
    // the corpus. The app's own minizip read of base.apk is the only path, as
    // on a device. docs/cocos-force-fopen-retired_2026-09-27.md.)
    // (Tombstone: the il2cpp / Unity diagnostic kit built for the Crossy Road
    // 7.11.1 bring-up is DELETED: install_unity_diag_hook (MACAPK_UHOOK),
    // install_integ_counters (MACAPK_INTEG_COUNT), install_bootctrl_counters
    // (MACAPK_BOOTCTRL_COUNT), install_il2cpp_diag_hook (MACAPK_UHOOK) and
    // install_gc_append_watch (MACAPK_GC_APPEND_WATCH). Each rewrote the app's
    // .text at hardcoded vaddrs that match that one build and no other APK in
    // the corpus, and every bug it localized is fixed or refuted. Android never
    // rewrites an app's code. docs/il2cpp-diag-kit-retired_2026-09-27.md.)
    lap("apply_cocos2dx_v2_addchild_null_safety");
    macapk_il2cpp_diag_kit_retired_note();   // one stderr line iff a retired knob is set
    // Record the libunity load base and, under MACAPK_REGISTERNATIVES_VERBATIM=0
    // only, scan for the Director node cluster the retired per-frame
    // DirectorManager driver used. No-op on any image that isn't libunity.
    record_unity_director_base();
    // GENERIC x18 platform-register rewrite (DEFAULT-ON, opt out with
    // MACAPK_X18_REWRITE_GENERIC=0; a v588 default-off experiment was reverted --
    // see the function body). Runs on the pristine .text and renames x18
    // out of every function that has a free caller-saved register. This is the
    // app-agnostic fix for the arm64 x18 trap (XNU zeroes x18 on every EL0
    // return; Android guest code uses it as a scratch GPR). It SUPERSEDES the old
    // per-build geometry-fill + FMOD-FFT prefault/rewrite hooks -- those keyed off
    // Crossy-libunity-build-specific vaddrs; the generic pass covers the same
    // functions on any build. The mlock-resident block below
    // + the null-recovery handler remain as the residual safety nets. Writes into
    // .text while it is still RW (before apply_segment_protections). App-agnostic:
    // boundaries + register choice are derived from the binary at load time.
    lap("record_unity_director_base");
    install_x18_generic_rewrite();
    lap("install_x18_generic_rewrite");
    // After the x18 pass, so its plan-cache key (the exec bytes) never sees the
    // per-launch thunk branches this writes.
    if (size_t n = apply_go_tls_rewrite()) {
        std::fprintf(stderr,
            "[go-tls] %s: Go runtime thread pointer served from a macOS TSD slot "
            "(%zu `mrs TPIDR_EL0` site(s) detoured)\n", dbg_name_, n);
    }
    lap("apply_go_tls_rewrite");
    // il2cpp Time probe (MACAPK_IL2CPP_TIME_PROBE=<N>, default off): entry-hooks
    // vm::Runtime::Invoke (found via the exported il2cpp_runtime_invoke) and every
    // Nth call prints UnityEngine.Time's getters, read through il2cpp's exported
    // API. The instrument for the notated unscaledTime regression. Writes .text +
    // tramp (both still RW here). No-op unless the env is set and this is libil2cpp.
    install_il2cpp_time_probe();
    lap("install_il2cpp_time_probe");
    // F3 generic managed-exception logger (MACAPK_IL2CPP_EXC_LOG, default off):
    // entry-hooks vm::Exception::Raise (found via the exported il2cpp_raise_
    // exception, zero RVAs) to name every managed throw. Writes .text + tramp
    // (both still RW here). No-op unless the env is set and this is libil2cpp.
    install_il2cpp_exc_logger();
    lap("install_il2cpp_exc_logger");
    // (Tombstone: MACAPK_SPLASH_SKIP / apply_splash_skip, the v1.0.234 Crossy
    // libil2cpp SplashIntro .text patch, is DELETED;
    // docs/unity-il2cpp-splash-skip-retired_2026-09-27.md.)
    apply_unity_path_noabs();
    lap("apply_unity_path_noabs");
    // RETIRED by default (MACAPK_UNITY_SWAPPY_PRESENT_RETIRED; the evidence is
    // at the definition): libunity's own Swappy-or-eglSwapBuffers dispatch runs
    // unmodified, as on a device. =0 re-applies the legacy b.ne -> b rewrite
    // byte for byte (MACAPK_UNITY_SWAPPY_PRESENT_OFF still suppresses it there).
    if (!macapk_unity_swappy_present_retired()) {
        apply_unity_swappy_present_fix();
        lap("apply_unity_swappy_present_fix (=0 only)");
    }
    // Finalize the trampoline page (RW -> RX + flush icache) AFTER
    // all patch functions have written into it. Each patch function
    // (libfmod siteB, inflate tracer, inflate_table tracer) writes
    // its trampoline bytes assuming the page is still RW; if any
    // earlier function mprotects it RX, subsequent writes SIGBUS
    // (BUS_ADRALN on Apple Silicon's W^X enforcement).
    if (trampoline_ && trampoline_used_) {
        sys_icache_invalidate(trampoline_, trampoline_used_);
        if (mprotect(trampoline_, 4096, PROT_READ | PROT_EXEC) != 0) {
            std::fprintf(stderr,
                "[arm64-native] %s: trampoline mprotect RX failed: %s\n",
                dbg_name_, std::strerror(errno));
        }
    }
    // Same finalize for the x18 detour-thunk pool (RW -> RX + icache flush).
    // Written during install_x18_generic_rewrite (which ran before this).
    if (x18_thunks_ && x18_thunks_used_) {
        sys_icache_invalidate(x18_thunks_, x18_thunks_used_);
        if (mprotect(x18_thunks_, x18_thunks_cap_, PROT_READ | PROT_EXEC) != 0) {
            std::fprintf(stderr,
                "[arm64-native] %s: x18-thunk mprotect RX failed: %s\n",
                dbg_name_, std::strerror(errno));
        }
    }
    lap("trampoline/x18 finalize + icache");
    if (!apply_segment_protections())         return false;
    // Strip X from each syscall-wrapper guard page NOW -- after protections, so they are not
    // re-flipped to R-X. Bytes stay pristine (any .text self-hash passes); exec-faults route
    // through macapk_syscall_guard_fault. No-op unless macapk_setup_syscall_guard_page armed one
    // (default ON; MACAPK_GUARD_SYSCALL=0 forces the in-place .text patch instead).
    for (int i = 0; i < g_pending_guard_strip_n; ++i) {
        if (mprotect(reinterpret_cast<void*>(g_pending_guard_strip_lo[i]),
                     g_pending_guard_strip_sz[i], PROT_READ) != 0)
            std::fprintf(stderr, "[guard] deferred strip-X FAILED page %#lx errno=%d\n",
                         (unsigned long)g_pending_guard_strip_lo[i], errno);
        else
            std::fprintf(stderr, "[guard] deferred strip-X applied: page %#lx R-- (guard live)\n",
                         (unsigned long)g_pending_guard_strip_lo[i]);
    }
    std::fflush(stderr);
    g_pending_guard_strip_n = 0;
    std::fprintf(stderr,
        "[arm64-native] %s: protections applied\n", dbg_name_);

    // Diagnostic GUEST-GLOBAL WATCHER (MACAPK_WATCH_GUEST_LIB=<substring of dbg_name_>,
    // MACAPK_WATCH_GUEST_ADDRS=<comma-separated hex guest vaddrs>,
    // MACAPK_WATCH_GUEST_MS=<sample period, default 1000>,
    // MACAPK_WATCH_GUEST_N=<max samples, default 400>).
    //
    // WHY (2026-09-03, MCPE 54% sign-in stall): the whole investigation now turns on whether ONE
    // lazily-initialised guest global is ever populated. Every instrument we ship traps CODE, and
    // a global that is never written has no code site to trap -- "the trap never fired" cannot
    // distinguish "never initialised" from "I armed the wrong address". Sampling the WORD itself
    // answers it directly, and a time series says more than a single read: it shows whether the
    // value ever becomes non-null, and at what second, so a stalling run and a crossing run can be
    // compared on the same axis.
    //
    // Read-only, log-only, default OFF (both vars unset = no thread, no cost). Bounded by
    // MACAPK_WATCH_GUEST_N so it cannot flood a long run. It only ever READS, so it cannot
    // perturb guest state; the one cost it does carry is a detached thread, which is why the
    // sample count is capped rather than looping forever.
    // REMOVE when the 54% stall is root-caused.
    if (const char* wl = std::getenv("MACAPK_WATCH_GUEST_LIB")) {
        const char* wa = std::getenv("MACAPK_WATCH_GUEST_ADDRS");
        if (wa && *wa && dbg_name_ && std::strstr(dbg_name_, wl)) {
            struct WatchArgs { uintptr_t base; std::vector<uintptr_t> addrs; int ms; int n; };
            auto* wargs = new WatchArgs();
            wargs->base = reinterpret_cast<uintptr_t>(base_);
            const char* q = wa;
            while (*q && wargs->addrs.size() < 16) {
                char* e = nullptr;
                unsigned long long v = std::strtoull(q, &e, 16);
                if (e == q) break;
                wargs->addrs.push_back(static_cast<uintptr_t>(v));
                q = (*e == ',') ? e + 1 : e;
            }
            const char* wm = std::getenv("MACAPK_WATCH_GUEST_MS");
            const char* wn = std::getenv("MACAPK_WATCH_GUEST_N");
            wargs->ms = wm ? std::atoi(wm) : 1000;
            wargs->n  = wn ? std::atoi(wn)  : 400;
            if (wargs->ms < 50) wargs->ms = 50;
            std::fprintf(stderr, "[watch-guest] armed on %s base=%p: %zu addr(s), every %dms x%d\n",
                         dbg_name_, base_, wargs->addrs.size(), wargs->ms, wargs->n);
            std::fflush(stderr);
            pthread_t th;
            if (pthread_create(&th, nullptr, [](void* p) -> void* {
                    pthread_setname_np("macapk-watch-guest");
                    auto* a = static_cast<WatchArgs*>(p);
                    for (int i = 0; i < a->n; i++) {
                        std::string line = "[watch-guest]";
                        char buf[128];
                        for (uintptr_t off : a->addrs) {
                            uint64_t w = *reinterpret_cast<volatile uint64_t*>(a->base + off);
                            std::snprintf(buf, sizeof buf, " [0x%llx]=%#llx",
                                          (unsigned long long)off, (unsigned long long)w);
                            line += buf;
                            // A non-null pointer into the guest image or heap is usually a
                            // control block whose FIRST BYTE is the interesting flag; print it
                            // too, guarded, because that byte is the second half of every
                            // NonOwnerPointer validity test.
                            if (w > 0x1000 && (w & 7) == 0) {
                                std::snprintf(buf, sizeof buf, "(b0=%u)",
                                              (unsigned)*reinterpret_cast<volatile uint8_t*>(w));
                                line += buf;
                            }
                        }
                        std::fprintf(stderr, "%s\n", line.c_str());
                        std::fflush(stderr);
                        usleep(static_cast<useconds_t>(a->ms) * 1000);
                    }
                    delete a;
                    return nullptr;
                }, wargs) == 0) {
                pthread_detach(th);
            } else {
                delete wargs;
                std::fprintf(stderr, "[watch-guest] pthread_create FAILED -- not watching\n");
            }
        }
    }
    // MCPE sign-in probe family member (RETIRED by default, MACAPK_MCPE_SIGNIN_PROBES_RETIRED;
    // superseded by the generic MACAPK_TRACE_GUEST_LIB=minecraftpe + MACAPK_TRACE_GUEST_OFFS).
    // Diagnostic guest-code tracepoint (MACAPK_TRACE_GUEST_ADDR=<hex file-offset>,
    // libminecraftpe only). Patch the instruction at base_+offset to a trapping mrs
    // that macapk_sigill_handler intercepts (reads x19..x22 + [this+0xb0]/[x21+0x20],
    // one-shot). Used to read MCPE's connect-starter [this+0xb0] on the stuck side
    // (lldb can't -- it steals the guest's Mach exceptions). Runs AFTER all .text
    // rewrite passes + protections so nothing overwrites the patch. Zero cost unset.
    if (const char* tpo = macapk_mcpe_probe_env("MACAPK_TRACE_GUEST_ADDR")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t off = static_cast<uintptr_t>(std::strtoull(tpo, nullptr, 16));
            __macapk_install_guest_tracepoint(
                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + off));
        }
    }
    // Diagnostic guest-code tracepoint, GENERIC over image + MULTIPLE sites
    // (added 2026-07-29 for the HCR 1.68.1 Stage-6 "stuck after asset loading"
    // investigation -- docs/hcr168-arm64-eula-scene-*.md). The block above is
    // pinned to "minecraftpe" in source (an MCPE-investigation artifact); rather
    // than editing that literal (risking the existing MCPE tooling contract),
    // this is purely additive and driven ENTIRELY by env vars, so it carries no
    // per-APK string in source and is a no-op for every app unless BOTH vars are
    // set:
    //   MACAPK_TRACE_GUEST_LIB=<substring of dbg_name_, e.g. "libgame.so">
    //   MACAPK_TRACE_GUEST_OFFS=<comma-separated hex file-offsets, e.g. "2f0480,2f0664">
    // Each offset gets its own __macapk_install_guest_tracepoint call (same
    // one-shot trap-log-restore mechanism as above). Zero cost when unset;
    // no removal condition needed -- generically useful for the next guest
    // tracepoint investigation on any binary.
    if (const char* lib_substr = std::getenv("MACAPK_TRACE_GUEST_LIB")) {
        if (dbg_name_ && std::strstr(dbg_name_, lib_substr)) {
            // pairip JNI-value capture: this matched image is the one being
            // traced (libpairipcore for the JNICAP run); publish its base so the
            // handler can map tracepoint address -> file offset -> JNI slot.
            if (std::getenv("MACAPK_PAIRIP_JNICAP") || std::getenv("MACAPK_PAIRIP_FNVGATE") || std::getenv("MACAPK_PAIRIP_SSTEP"))
                __macapk_set_pairip_jnicap_base(reinterpret_cast<uintptr_t>(base_));
            if (const char* offs = std::getenv("MACAPK_TRACE_GUEST_OFFS")) {
                std::string s(offs);
                size_t pos = 0;
                while (pos <= s.size()) {
                    size_t comma = s.find(',', pos);
                    std::string tok = s.substr(
                        pos, comma == std::string::npos ? std::string::npos : comma - pos);
                    if (!tok.empty()) {
                        uintptr_t off = static_cast<uintptr_t>(std::strtoull(tok.c_str(), nullptr, 16));
                        if (off) {
                            std::fprintf(stderr,
                                "[guest-tp] %s: arming generic tracepoint at file-off 0x%llx "
                                "(base=%p -> runtime addr %p)\n",
                                dbg_name_, (unsigned long long)off, (void*)base_,
                                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + off));
                            __macapk_install_guest_tracepoint(
                                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + off));
                        }
                    }
                    if (comma == std::string::npos) break;
                    pos = comma + 1;
                }
            }
        }
    }
    // GENERIC census sites, driven ENTIRELY by env (diagnostic, DEFAULT-OFF, no per-app string
    // in source). The MACAPK_TRACE_HC block further down is pinned to "HttpClient" and to a fixed
    // site list, so censusing an arbitrary function of an arbitrary guest image meant editing and
    // rebuilding the shim every time. This does the same install for any (lib, offset) pair named
    // at runtime:
    //   MACAPK_CENSUS_SITES="<libsubstr>:<hexoff>:<label>[:<logreg>],..."
    // logreg defaults to -1 (log no register); >=0 logs xN; -2 additionally prints x0 as a C
    // string (assert-message sites); -3 prints x0 AND decodes the libc++ std::string at [x0+0x50]
    // as ` task='...'` (Bedrock task-run sites, where x0 is the task object and +0x50 is the same
    // name field MACAPK_COMPLETER_DELAY_OBJSTR=50 prints on the enqueue side -- that is what lets
    // a task that RAN be matched to the enqueue that queued it).
    // MACAPK_CENSUS_EXCLUDE="<substr>,..." suppresses PRINTING (never counting) for hits whose
    // thread name contains a listed substring, which is what makes a site that is hot on MAIN or
    // the Rendering Pools usable for a question about REST/IO/Streaming.
    // The installer itself is the correctness gate: it REFUSES to
    // arm unless the first instruction is `sub sp,sp,#imm` or `stp Xt,Xt2,[sp,#-imm]!`, which is
    // the positive identification that the offsets came from the same build that is loaded.
    // Zero cost when unset; nothing to retire (generic tooling, like MACAPK_TRACE_GUEST_OFFS).
    if (const char* cs = std::getenv("MACAPK_CENSUS_SITES")) {
        int armed = 0, matched = 0;
        std::string s(cs);
        size_t pos = 0;
        while (pos <= s.size() && !s.empty()) {
            size_t comma = s.find(',', pos);
            std::string ent = s.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
            if (!ent.empty()) {
                // <libsubstr>:<hexoff>:<label>[:<logreg>]
                size_t c1 = ent.find(':');
                size_t c2 = (c1 == std::string::npos) ? std::string::npos : ent.find(':', c1 + 1);
                if (c1 != std::string::npos && c2 != std::string::npos) {
                    std::string libs  = ent.substr(0, c1);
                    std::string offs  = ent.substr(c1 + 1, c2 - c1 - 1);
                    std::string rest  = ent.substr(c2 + 1);
                    size_t c3 = rest.find(':');
                    std::string label = (c3 == std::string::npos) ? rest : rest.substr(0, c3);
                    int logreg = (c3 == std::string::npos) ? -1 : std::atoi(rest.c_str() + c3 + 1);
                    uintptr_t off = static_cast<uintptr_t>(std::strtoull(offs.c_str(), nullptr, 16));
                    if (off && dbg_name_ && std::strstr(dbg_name_, libs.c_str())) {
                        matched++;
                        __macapk_install_hc_census(
                            reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + off),
                            label.c_str(), logreg);
                        armed++;
                    }
                }
            }
            if (comma == std::string::npos) break;
            pos = comma + 1;
        }
        // Print ONLY for an image some entry actually named, otherwise every one of the dozens of
        // loaded guest libraries emits an n=0 line. `n` counts install ATTEMPTS on this image; a
        // site the installer refused says so in its own `[hc-census] NOT installing` line, so the
        // two together separate "never armed" from "armed and never fired" -- opposite conclusions.
        if (matched) {
            std::fprintf(stderr, "[census-sites] ARMED n=%d lib=%s\n", armed, dbg_name_ ? dbg_name_ : "?");
            std::fflush(stderr);
        }
    }
    // ==== MCPE sign-in probe family (RETIRED by default: MACAPK_MCPE_SIGNIN_PROBES_RETIRED) ====
    // Every block from here to the matching "end" marker is pinned to libminecraftpe.so or
    // libHttpClient.Android.so vaddrs from the 2026-07 "54%" stall campaign and reads its env
    // through macapk_mcpe_probe_env(), which answers NULL while the family is retired (the
    // default) and std::getenv() under =0. The comments below are the historical record of
    // each experiment and describe what it did when armed; several call themselves "THE FIX",
    // and none was: see the gate's header comment and
    // docs/mcpe-signin-probe-family-retired_2026-09-27.md.
    // MCPE Burst busy-poll throttle (the 54% signaling-stall fix). The Bedrock
    // Burst poll body at libminecraftpe+0x11e976bc self-reposts with deadline<=now
    // ~140k/s, flooding the WorkerPool intake so the signaling BackgroundTask stays
    // Queued -> no websocket. Android's CFS fairly time-slices the equal-priority
    // workers so the task still runs; macOS's scheduler lets the flood monopolise
    // the pool ~65%. This gives the pathological zero-delay poll a real interval
    // (usleep N per re-entry), draining the intake so the task gets a worker turn.
    // MACAPK_MCPE_BURST_THROTTLE_US = microseconds (0/unset = off). Gated to the ONE
    // Burst fn (unlike the refuted global sched_yield/clock levers, which the pure
    // spin -- no yield, no clock read -- never reached).
    if (const char* bt = macapk_mcpe_probe_env("MACAPK_MCPE_BURST_THROTTLE_US")) {
        unsigned us = (unsigned)std::strtoul(bt, nullptr, 10);
        if (us && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            __macapk_install_guest_throttle(
                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + 0x11e976bc), us);
        }
    }

    // STEP 1 read for the 54% signaling stall (MACAPK_MCPE_BGTASK_WATCH, libminecraftpe only).
    // Patch the signaling BackgroundTask submit `blr x8` @+0xd3d1ce4 to capture the task obj +
    // scheduler and watchdog state@task+0x148 over the run (Queued=1 forever on stall, ->2 on
    // cross). Decides (A) READY-not-popped vs (B) QUEUED-never-runnable before any guest surgery.
    // TaskGroup::queue admission probe -- decides SUBMIT-path vs SILENT-DROP for the
    // host-originated cl-completion post (the 54% stall). Default-OFF, byte-ID gated,
    // refuses to arm on any binary whose six words do not match.
    if (macapk_mcpe_probe_env("MACAPK_MCPE_TGQ_PROBE")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            __macapk_install_tgq_probe(reinterpret_cast<void*>(base_));
        }
    }
    if (macapk_mcpe_probe_env("MACAPK_MCPE_BGTASK_WATCH")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            __macapk_install_bgtask_watch(
                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + 0xd3d1ce4));
            // pt.2: transition logger on the state machine mc+0xd40ee5c (op=1 Queued / op=2 Running).
            __macapk_install_sm_hook(
                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + 0xd40ee5c));
        }
    }
    // rtc::Event::Wait(kForever) INFINITE-path liveness net for the NullSocketServer rtc::Thread
    // (the missing analog of the v585 epoll cap). Register the guest return-address of the infinite
    // cond_wait site (mc+0x7b14d24, right after `bl cond_wait` @mc+0x7b14d20) so the cond_wait shim
    // caps ONLY that site + returns nonzero -> rtc::Event::Wait returns -> ProcessMessages re-drains.
    // MACAPK_RTC_EVENT_WAIT_CAP_MS = milliseconds (0/unset = off). libminecraftpe-gated.
    if (const char* rc = macapk_mcpe_probe_env("MACAPK_RTC_EVENT_WAIT_CAP_MS")) {
        unsigned ms = (unsigned)std::strtoul(rc, nullptr, 10);
        if (ms && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            __macapk_set_rtc_evwait_cap(
                reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + 0x7b14d24), ms);
        }
    }
    // libHttpClient completion census (MACAPK_TRACE_HC): find WHERE the auth-completion drops.
    // OnRequestCompleted(+0xaa4b8)=okhttp response reached native; XTaskQueueSubmitCallback(+0x89c6c,
    // x1=port 0=Work/1=Completion)=completion submitted to a queue port; XAsyncComplete(+0x7e5a0).
    if (macapk_mcpe_probe_env("MACAPK_TRACE_HC") && dbg_name_ && std::strstr(dbg_name_, "HttpClient")) {
        auto A = [&](uintptr_t off){ return reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(base_) + off); };
        __macapk_install_hc_census(A(0xaa4b8), "OnRequestCompleted", -1);
        __macapk_install_hc_census(A(0xaa6e4), "OnRequestFailed",    -1);
        __macapk_install_hc_census(A(0x89c6c), "SubmitCallback",      1);
        __macapk_install_hc_census(A(0x7e5a0), "XAsyncComplete",     -1);
        __macapk_install_hc_census(A(0xa2dd8), "HCPerformAsync",     -1);  // HTTP request ISSUE (sub sp,#0x90) [_15 vaddr]
        __macapk_install_hc_census(A(0xb4090), "DispPop",           -1);  // Completion-port dispatcher pops an item (loop 0xb3eac)
        __macapk_install_hc_census(A(0xb4154), "DispInvoke",        -1);  // Completion-port dispatcher INVOKES the callback
    }
    // MACAPK_XAC_CAPTURE (v660): trap XAsyncComplete (hc 0x2bac8) to capture the GS completion's ACTUAL
    // queue (asyncBlock->queue = [x0+0x00]) into the completion-pump dispatch set -- the robust,
    // no-offset-guess version of DRAIN_BASEQ. HttpClient-gated; default-OFF.
    if (macapk_mcpe_probe_env("MACAPK_XAC_CAPTURE") && dbg_name_ && std::strstr(dbg_name_, "HttpClient")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_xac_capture(reinterpret_cast<void*>(b + 0x2bac8));
    }
    // Diagnostic: census the Bedrock WorkerPool submit (does the connect chain post to the pool on a
    // stall?) + the connect continuation. MACAPK_TRACE_MC, libminecraftpe-gated.
    if (macapk_mcpe_probe_env("MACAPK_TRACE_MC") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        // MACAPK_TRACE_MC_MIN=1 installs ONLY the 6 key chain sites (lower census latency, so MCPE can
        // still CROSS ~50% instead of ~100%-stalling under the full ~15-site census -- needed to capture
        // a CROSS's Builder-detail [+0xa0] for the stall/cross diff).
        bool mc_min = macapk_mcpe_probe_env("MACAPK_TRACE_MC_MIN") != nullptr;
        bool mc_micro_pre = macapk_mcpe_probe_env("MACAPK_TRACE_MC_MICRO") != nullptr;
        if (!mc_min && !mc_micro_pre) {   // MICRO installs ONLY the rare block below (truly minimal)
            // The connect chain's two worker tasks (emulator crossing backtrace): TaskA ONCOMPL
            // "prereqs ready" (run-fn 0x11be5188), TaskB CONNECT fn (entry 0x11be51a0, logs
            // "Connecting to websocket"). Censusing these on a stall pins whether TaskA runs.
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x11be5188), "ONCOMPL",  0);   // stp x29,x30,[sp,#-0x50]!
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x11be51a0), "CONNECTfn", 0);  // sub sp,#0x1b0
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xb1ed338),  "TokDispatch", 0); // signaling BgTask RUN (Fable decisive)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x131612ec), "McpeHttpDone", 0); // MCPE HTTP provider completion (calls XAsyncComplete +0x108)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x11bf5cd0), "McpeHttpIssue", 0); // MCPE HTTP transport (issues HCHttpCallPerformAsync)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x13189f5c), "CompletionCB", 0);  // XAsyncBlock completion cb entry
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x13192e58), "CompleterCore", 0); // reached only if weak-lock(A) OK
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xbd26c94), "Connect", 0);     // SharedState::connect (non-twin)
        }
        // MACAPK_TRACE_MC_MICRO installs ONLY the rare signaling sites (RespH/McpeHttpIssue/TokenRUN)
        // -> minimal latency -> MCPE still CROSSES ~50%, so we can read RespH-detail (W's state) on a
        // CROSS and diff vs a stall. The high-freq sites (CompleterCore/NotifyB/Transition/ConnectTwin)
        // fire per-completion and push the stall to ~100%.
        bool mc_micro = macapk_mcpe_probe_env("MACAPK_TRACE_MC_MICRO") != nullptr;
        // Rare sites (always installed): the round-trip response handler + pump.
        __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x11bff13c), "RespH", 1);       // response handler; detail: P-state + W-state (posts RT#2 iff transition(W,op1) true)
        __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x11bf5cd0), "McpeHttpIssue", 0); // round-trip pump (1x stall / 22x cross)
        __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xd40f720),  "TokenRUN", 0);    // AsyncResult<Token>::RUN
        __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x80ee608), "Qrun", 0);         // coroutine RUN (worker threads); log tid to compare vs RespH's completion tid (affinity test)
        if (!mc_micro) {
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x13192e58), "CompleterCore", 0); // successor-list detail (high-freq)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xd40fba8),  "NotifyB", 0);        // .then/notify (high-freq)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xd410538), "Schedule", 20);   // setValueAndSchedule
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xd40ee5c), "Transition", 1);  // _transition (high-freq)
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0xd3d1848), "Builder", 0);     // connect-continuation
            __macapk_install_hc_census(reinterpret_cast<void*>(b + 0x13164698), "ConnectTwin", 0); // connect twin (high-freq)
        }
    }
    // Diagnostic: connect-starter divergence probe (does it take DIRECT-sync or ASYNC-provider?).
    // MACAPK_TRACE_CONNSTART, libminecraftpe-gated. Reads [this+0xb0] at mc+0xd3cd9b8 entry.
    if (macapk_mcpe_probe_env("MACAPK_TRACE_CONNSTART") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_connstart_probe(reinterpret_cast<void*>(b + 0xd3cd9b8));
    }
    // Token-RUN post detector (MACAPK_TRACE_TOKPOST): is the Token's terminal-transition task
    // (AsyncResult<Token>::RUN mc+0xd40f720) ever POSTED to the WorkerPool on a stall?
    if (macapk_mcpe_probe_env("MACAPK_TRACE_TOKPOST") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_tokpost_probe(reinterpret_cast<void*>(b + 0x13136038), reinterpret_cast<void*>(b + 0xd40f720));
    }
    if (macapk_mcpe_probe_env("MACAPK_TRACE_NOTIFYB") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_notifyb_probe(reinterpret_cast<void*>(b + 0xd40fba8), reinterpret_cast<void*>(b));
    }
    if (const char* cd = macapk_mcpe_probe_env("MACAPK_COMPLETER_DELAY_US")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            // v1.0.647: default to the CORRECT completion-resume hook on 6c199c
            // (GatheringService `deliver/resume` mc+0xbcbabc8 -- Fable RE); overridable via
            // MACAPK_COMPLETER_DELAY_ADDR=<hex file-off> (the old 0x13192e58 was the wrong,
            // prior-binary site). __macapk_install_completer_delay positive-IDs the
            // `sub sp,sp,#imm` prologue, so a wrong addr just refuses to arm.
            uintptr_t off = 0xbcbabc8;
            if (const char* ao = macapk_mcpe_probe_env("MACAPK_COMPLETER_DELAY_ADDR"))
                off = (uintptr_t)strtoull(ao, nullptr, 0);
            __macapk_install_completer_delay(reinterpret_cast<void*>(b + off), (unsigned)std::atoi(cd));
        }
    }
    // DIAGNOSTIC (MACAPK_FORCE_RET_ADDR=<hex file-off> [+ MACAPK_FORCE_RET_VAL=<n>, default 1]): force a
    // guest function to immediately return a fixed value. Used to confirm the 54% stall is the GS-issue
    // readiness gate -- force the ServicesManager/user-session predicate mc+0xc19f284 to return 1 so the
    // gate 0xbcb3958 passes and MCPE issues the GS request. libminecraftpe-gated; default-OFF.
    if (const char* fa = macapk_mcpe_probe_env("MACAPK_FORCE_RET_ADDR")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            uintptr_t off = (uintptr_t)strtoull(fa, nullptr, 0);
            int64_t val = 1;
            if (const char* fv = macapk_mcpe_probe_env("MACAPK_FORCE_RET_VAL")) val = (int64_t)strtoll(fv, nullptr, 0);
            __macapk_install_forceret(reinterpret_cast<void*>(b + off), val);
        }
    }
    // MACAPK_XSUBMIT_TRACE=1 = the XAL submit-choke tracepoint (log-only). MACAPK_XSUBMIT_PUMP=1 = the
    // v655 54%-stall FIX: same trap, but it ALSO captures each auth XTaskQueue and a dedicated host
    // thread keeps dispatching both ports of every captured queue, so the sign-in continuations run
    // off-MAIN while MINECRAFT MAIN is parked in the sign-in wait (the strand my live trace proved:
    // the post-HTTP continuations land on the MAIN auth queue's Work port, dispatched only by the
    // per-frame driver 0xad773ac on blocked MAIN). Positive-IDs `bl 0xf11ec34` (0x9584c613).
    // libminecraftpe-gated; both default-OFF (default MCPE path unchanged).
    if ((macapk_mcpe_probe_env("MACAPK_XSUBMIT_TRACE") || macapk_mcpe_probe_env("MACAPK_XSUBMIT_PUMP"))
        && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        int pump = macapk_mcpe_probe_env("MACAPK_XSUBMIT_PUMP") ? 1 : 0;
        __macapk_install_xsubmit_trace(reinterpret_cast<void*>(b + 0x8fed3e8), pump);
    }
    // MACAPK_DTASK_EXEMPT=1 = the v659 54%-stall FIX (24-agent workflow wf_18cb8132-107, 2026-07-20):
    // hook Core::DeferredTask::complete/execute mc+0x13236104 to register each task's own mutex M
    // (task+0x34) as EXEMPT from the MUTEX_FAIR boot tax. MAIN's per-frame GraphicsTasks wait and the
    // sign-in step share this DeferredTask complete/wait CAS handshake; our 50ms-per-contended-unlock
    // tax firing on M throttles the completer (~10-20/s) and stretches MAIN's normally-<50ms wait into
    // a multi-minute stall. Exempting M restores Android-speed completion while keeping the barging on
    // every other mutex (the co-location the coroutine needs). Positive-IDs the `stp x29,x30,[sp,#-0x20]!`
    // prologue (0xa9bd7bfd). libminecraftpe-gated; default-OFF (default MCPE path byte-identical).
    if (macapk_mcpe_probe_env("MACAPK_DTASK_EXEMPT")
        && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_dtask_complete(reinterpret_cast<void*>(b + 0x13236104));
    }
    // MACAPK_SUBMIT_POOL_DRAIN=1 = the v655 54%-stall FIX at the WorkerPool layer (Fable a9303af2):
    // capture the pool at WorkerPool::submit mc+0x132350b0 and keep draining every captured pool off-MAIN
    // via the cooperative drain-loop 0x13235554, so the auth coroutine's WorkerPool steps advance even
    // when MAIN is parked in the sign-in wait and the pool has no live drainer. Positive-IDs the
    // `sub sp,sp,#0x50` prologue (0xd10143ff). libminecraftpe-gated; default-OFF.
    if ((macapk_mcpe_probe_env("MACAPK_SUBMIT_POOL_DRAIN") || macapk_mcpe_probe_env("MACAPK_AUTH_POOL_DRAIN"))
        && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_install_submit_pool_drain(reinterpret_cast<void*>(b + 0x132350b0));
    }
    // DIAGNOSTIC (MACAPK_FORCE_GS_HANDLER=<ms>): THE 54% root confirmation (Fable aab2 v653). Capture the
    // GatheringManager at isReady 0xbcb390c, force the GS gate 0xbcb3958->1, and invoke the GS-request
    // handler 0xbcb34a4(gm, servicesManager=[0x13d214e8]) off a host thread -- reproducing the emulator's
    // terminal-signed-out listener fire so the /api/v1.0/access request is built+issued. libminecraftpe-gated.
    if (const char* gh = macapk_mcpe_probe_env("MACAPK_FORCE_GS_HANDLER")) {
        if (dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            __macapk_install_forceret(reinterpret_cast<void*>(b + 0xbcb3958), 1);   // gate -> 1
            __macapk_install_force_gs_handler(reinterpret_cast<void*>(b + 0xbcb390c),  // isReady (capture gm)
                                              reinterpret_cast<void*>(b + 0xbcb34a4),  // GS-access handler
                                              reinterpret_cast<void*>(b + 0x13d214e8), // ServicesManager global
                                              (unsigned)std::atoi(gh));
        }
    }
    // STEP 1 of the 54%-stall RE-POST fix (docs/mcpe-signaling-foreign-wake-defer_2026-07-17.md
    // "v648 FINAL VERIFIED BUILD SPEC"): capture the GS coroutine PUMP (pthread + TaskGroup) at
    // TaskGroup::queue's OWNER branch mc+0x13203b28. LOG-ONLY (identify the pump on Mac.apk before
    // wiring the deliver-trap PC-redirect). __macapk_install_pump_capture positive-IDs `mov w8,wzr`
    // so a wrong addr / non-MCPE binary just refuses to arm. Overridable via MACAPK_PUMP_CAPTURE_ADDR.
    if (macapk_mcpe_probe_env("MACAPK_PUMP_CAPTURE") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        uintptr_t off = 0x13203b28;
        if (const char* ao = macapk_mcpe_probe_env("MACAPK_PUMP_CAPTURE_ADDR"))
            off = (uintptr_t)strtoull(ao, nullptr, 0);
        __macapk_install_pump_capture(reinterpret_cast<void*>(b + off));
    }
    // DIAGNOSTIC (MACAPK_TRACE_CORO_STEP): trace the GS access-coroutine step mc+0xbcb9c38
    // (state->vt+0x20) — logs thread + HTTP status per hit. Reveals, on a Mac.apk stall, which
    // thread(s) run the coroutine and how far it gets before orphaning (vs emulator: all on MAIN).
    if (macapk_mcpe_probe_env("MACAPK_TRACE_CORO_STEP") && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        uintptr_t off = 0xbcb9c38;
        if (const char* ao = macapk_mcpe_probe_env("MACAPK_TRACE_CORO_STEP_ADDR"))
            off = (uintptr_t)strtoull(ao, nullptr, 0);
        __macapk_install_coro_step_trace(reinterpret_cast<void*>(b + off));
    }
    // THE decisive 54%-stall diagnostic (MACAPK_TRACE_DISPATCH): trace MCPE's XTaskQueue
    // dispatch-one-port driver mc+0xad773ac. Shows on a Mac.apk stall whether the Completion
    // port (port==1) is ever dispatched (it never is = the strand) vs the emulator (Thread-9
    // dispatches both ports -> deliver fires -> cross).
    // MACAPK_COMPLETION_PUMP = THE 54%-stall FIX (see bionic_shim.cpp completion-port pump):
    // installs the dispatch trap purely to CAPTURE the Completion queue + XTaskQueueDispatch
    // veneer from the guest's own per-frame pump, then a dedicated guest-context thread keeps
    // the Completion port dispatched so late HTTP completions aren't stranded when MAIN blocks.
    // MACAPK_TRACE_DISPATCH = the diagnostic (log-only). Either one installs the trap.
    // MACAPK_MAIN_DRAIN_PUMP (the v652 last-mile fix) ALSO installs this trap: it needs the driver's
    // per-frame runs (pre-block) to CAPTURE the Completion queue handle + XTaskQueueDispatch veneer so
    // MINECRAFT MAIN (the watchdog inject) can dispatch the ports itself once MAIN blocks. It reuses the
    // capture but drives the dispatch from MAIN, not a host thread (macapk_completion_pump_maybe_start
    // suppresses the host thread under _PUMP).
    if ((macapk_mcpe_probe_env("MACAPK_TRACE_DISPATCH") || macapk_mcpe_probe_env("MACAPK_COMPLETION_PUMP")
         || macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_PUMP") || macapk_mcpe_probe_env("MACAPK_PUMP_DRAIN_HOST")
         || macapk_mcpe_probe_env("MACAPK_AUTH_POOL_DRAIN"))
        && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        uintptr_t off = 0xad773ac;
        if (const char* ao = macapk_mcpe_probe_env("MACAPK_TRACE_DISPATCH_ADDR"))
            off = (uintptr_t)strtoull(ao, nullptr, 0);
        if (macapk_mcpe_probe_env("MACAPK_COMPLETION_PUMP") || macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_PUMP")
            || macapk_mcpe_probe_env("MACAPK_PUMP_DRAIN_HOST") || macapk_mcpe_probe_env("MACAPK_AUTH_POOL_DRAIN"))
            __macapk_enable_completion_pump();
        __macapk_install_dispatch_trace(reinterpret_cast<void*>(b + off));
    }
    // MACAPK_COMPLETION_PUMP part 2: capture the HTTP completion queue at SUBMIT time in
    // libHttpClient.Android.so (XTaskQueueSubmitCallback +0x30b48). This is the reliable queue
    // source (the guest per-frame driver stops before dispatching it). Gated on the HttpClient lib.
    if ((macapk_mcpe_probe_env("MACAPK_COMPLETION_PUMP") || macapk_mcpe_probe_env("MACAPK_AUTH_POOL_DRAIN"))
        && dbg_name_ && std::strstr(dbg_name_, "HttpClient")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        __macapk_enable_completion_pump();
        __macapk_install_submit_capture(reinterpret_cast<void*>(b + 0x30b48));
    }
    // THE 54%-race FIX candidate (MACAPK_XTQ_SERIALIZE=<mode>, v654, Fable a80b97-confirmed): XAL's
    // Future-continuation chain requires its AsyncQueue::Stream to be a single serialized thread; MCPE
    // creates the backing XTaskQueue as (Manual,Manual) + pumps it unreliably when MAIN blocks -> the
    // continuation mis-threads on the multi-threaded Bedrock pool -> SISU never completes. Override
    // libHttpClient XTaskQueueCreate (hc+0x30360) to force the completion port (or both) to
    // SerializedThreadPool (=2) so callbacks auto-dispatch one-at-a-time. MACAPK_XTQ_SERIALIZE_PORTS=both
    // also serializes the work port. Gated on the HttpClient lib; default-OFF.
    if (const char* xm = macapk_mcpe_probe_env("MACAPK_XTQ_SERIALIZE")) {
        if (dbg_name_ && std::strstr(dbg_name_, "HttpClient")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            int mode = std::atoi(xm); if (mode < 0 || mode > 3) mode = 2;
            const char* pp = macapk_mcpe_probe_env("MACAPK_XTQ_SERIALIZE_PORTS");
            int both = (pp && std::strcmp(pp, "both") == 0) ? 1 : 0;
            __macapk_install_xtqcreate_mode(reinterpret_cast<void*>(b + 0x30360), mode, both);
        }
    }
    // THE MCPE 54%-STALL FIX (MACAPK_MAIN_DRAIN, v650): the GS sign-in completion is a WorkerPool
    // task on a pool P that is thread-affine to MINECRAFT MAIN (only MAIN may drain it); MAIN blocks
    // in the sign-in waitAndDrain (a pure cond_wait that drains nothing) -> P never drains -> deliver
    // never runs -> permanent stall. FIX: capture P at the affine cooperative-drain entry mc+0x132353fc
    // (transparent), then trap MAIN's wait-loop watchdog mc+0x11ee6c10 and, ONLY for the GS wait,
    // redirect MAIN to run the drain 0x132353fc(P) itself -> runs the stranded GS task -> deliver ->
    // result ready -> MAIN crosses. See bionic_shim.cpp macapk_maindrain_*; libminecraftpe-gated.
    // MACAPK_MAIN_DRAIN_PUMP implies MACAPK_MAIN_DRAIN (installs the same capture+watchdog traps); the
    // watchdog inject then ALSO dispatches the captured XTaskQueue ports on MAIN (v652, Fable-confirmed).
    // MACAPK_PUMP_DRAIN_HOST / MACAPK_WAKE_SIGNIN_MS install ONLY the capture trap (to snapshot P for
    // the off-MAIN worker-wake / host drain), NOT the watchdog (they never redirect MAIN).
    if ((macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN") || macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_PUMP")
         || macapk_mcpe_probe_env("MACAPK_PUMP_DRAIN_HOST") || macapk_mcpe_probe_env("MACAPK_WAKE_SIGNIN_MS")
         || macapk_mcpe_probe_env("MACAPK_WAKE_BGPOOLS") || macapk_mcpe_probe_env("MACAPK_WP_QUEUE_PROBE"))
        && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        uintptr_t cap = 0x132353fc, wd = 0x11ee6c10;
        if (const char* c = macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_CAP")) cap = (uintptr_t)strtoull(c, nullptr, 0);
        if (const char* c = macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_WD"))  wd  = (uintptr_t)strtoull(c, nullptr, 0);
        __macapk_install_maindrain_capture(reinterpret_cast<void*>(b + cap));
        if (macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN") || macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_PUMP"))
            __macapk_install_maindrain_watchdog(reinterpret_cast<void*>(b + wd));
        // THE 54% sign-in FIX (v652, Fable a80df): wake the sign-in pool P's parked workers every N ms
        // so a worker drains the stranded `deliver` off-MAIN (running it on MAIN deadlocks on M).
        if (const char* wms = macapk_mcpe_probe_env("MACAPK_WAKE_SIGNIN_MS"))
            __macapk_start_wake_signin((unsigned)std::strtoul(wms, nullptr, 10));
        // MACAPK_PUMP_DRAIN_HOST: enumerate the background WorkerPools at the worker park site 0x1325cfe4
        // so the host pump can drain each off-MAIN (the auth/GS pool is a background pool, not game-loop P).
        if (macapk_mcpe_probe_env("MACAPK_PUMP_DRAIN_HOST") || macapk_mcpe_probe_env("MACAPK_WAKE_BGPOOLS") || macapk_mcpe_probe_env("MACAPK_WP_QUEUE_PROBE")) {
            uintptr_t park = 0x1325cfe4;
            if (const char* c = macapk_mcpe_probe_env("MACAPK_BGPARK_ADDR")) park = (uintptr_t)strtoull(c, nullptr, 0);
            __macapk_install_bgpark_capture(reinterpret_cast<void*>(b + park));   // captures g_bg_pools + (WAKE_BGPOOLS/WP_QUEUE_PROBE) starts the thread
        }
        // Self-arm the sign-in-wait beat (mc+0x11ee74fc) that gates the wake thread. Without this the
        // beat only advances when the diagnostic MACAPK_XSUBMIT_TRACE installs the same hook, so
        // WAKE_BGPOOLS depended on a diagnostic env to fire. Installing it here makes the fix
        // self-contained. Idempotent + positive-ID'd in bionic_shim, so double-install (both envs on)
        // is a no-op. The signin-wait beat address is fixed relative to the watchdog (wd + 0x8ec).
        if (macapk_mcpe_probe_env("MACAPK_WAKE_BGPOOLS") || macapk_mcpe_probe_env("MACAPK_WP_QUEUE_PROBE"))
            __macapk_install_signin_wait(reinterpret_cast<void*>(b + 0x11ee74fc));
        // MACAPK_MAIN_DRAIN_AFFINE enforces P's MAIN-affinity LIGHTLY in the capture trap above
        // (per-drain). The heavy per-task WorkerPool::run 0x132356fc trap below (MACAPK_MAIN_DRAIN_WPRUN_TRAP)
        // is a SEPARATE opt-in — it fires for every pool task and WEDGES boot, so it is default-OFF.
        if (macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_WPRUN_TRAP")) {
            uintptr_t wprun = 0x132356fc;
            if (const char* c = macapk_mcpe_probe_env("MACAPK_MAIN_DRAIN_WPRUN")) wprun = (uintptr_t)strtoull(c, nullptr, 0);
            __macapk_install_maindrain_wprun(reinterpret_cast<void*>(b + wprun));
        }
    }
    // §4 forwarder-liveness discriminator (MACAPK_TRACE_FORWARDER): trap the libHttpClient
    // ThreadPool worker loop head (hc+0x3d07c, `ldr w8,[x20,#0x5c]`) to log WHEN the forwarder runs
    // + capture the pool object T for the pump's task-count report. HttpClient-gated, default-OFF.
    if (macapk_mcpe_probe_env("MACAPK_TRACE_FORWARDER") && dbg_name_ && std::strstr(dbg_name_, "HttpClient")) {
        uintptr_t b = reinterpret_cast<uintptr_t>(base_);
        uintptr_t off = 0x3d07c;
        if (const char* ao = macapk_mcpe_probe_env("MACAPK_TRACE_FORWARDER_ADDR"))
            off = (uintptr_t)strtoull(ao, nullptr, 0);
        __macapk_install_forwarder_trace(reinterpret_cast<void*>(b + off));
    }
    // THE 54%-signaling-stall FIX: Bedrock WorkerPool level-trigger drain (MACAPK_WP_DRAIN_MS, ms).
    // Capture each WorkerPool object T at its worker park site mc+0x1318a50c (`add x0,x19,#0x60`), then a
    // watchdog sets flag T+0xb8=1 + broadcasts cv T+0x60 every N ms so a worker re-runs dispatch and
    // drains a stranded cross-thread post (the edge-triggered lost-wakeup). libminecraftpe-gated.
    if (const char* wd = macapk_mcpe_probe_env("MACAPK_WP_DRAIN_MS")) {
        unsigned ms = (unsigned)std::strtoul(wd, nullptr, 10);
        if (ms && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            // v649 (6c199c): the Streaming Pool worker park is mc+0x1325cfe4 (add x0,x19,#0x60;
            // cond [pool+0x60], wake flag [pool+0xb8], running-state [pool+0x58]==2 -- verified by
            // disasm). The old 0x1318a50c was a prior-binary site (positive-ID fails on 6c199c, so
            // WP_DRAIN never captured a pool). Overridable via MACAPK_WP_DRAIN_SITE=<hex file-off>.
            uintptr_t untimed = 0x1325cfe4;
            if (const char* so = macapk_mcpe_probe_env("MACAPK_WP_DRAIN_SITE"))
                untimed = (uintptr_t)strtoull(so, nullptr, 0);
            __macapk_install_wp_drain(
                reinterpret_cast<void*>(b + untimed),      // untimed inbound wait (cv+0x60 flag+0xb8)
                reinterpret_cast<void*>(b + 0x13135404),   // timed wait (cond+0 flag+0x58)
                ms);
        }
    }
    // THE 54%-signaling-stall FIX (candidate, v640): re-drive the stuck Token async W whose arming
    // POST (setValueAndSchedule 0xd410538) is lost to a macOS scheduling race. Traps NotifyB 0xd40fba8
    // to capture each W deferred at state==0; a watchdog replays _transition(W,op1)@0xd40ee5c + the
    // executor submit after MACAPK_SIGNALING_REDRIVE_MS. See docs/mcpe-signaling-listrace-…_2026-07-16.
    // Default OFF; positive-ID gated. libminecraftpe-only. Do NOT combine with MACAPK_TRACE_MC/NOTIFYB
    // (they trap the same NotifyB site).
    if (const char* rd = macapk_mcpe_probe_env("MACAPK_SIGNALING_REDRIVE_MS")) {
        unsigned ms = (unsigned)std::strtoul(rd, nullptr, 10);
        if (ms && dbg_name_ && std::strstr(dbg_name_, "minecraftpe")) {
            uintptr_t b = reinterpret_cast<uintptr_t>(base_);
            __macapk_install_signaling_redrive(
                reinterpret_cast<void*>(b + 0xd40fba8),    // NotifyB (captures W deferred at state==0, mints P's Token)
                reinterpret_cast<void*>(b + 0xd40ee5c),    // _transition(W, op=1)
                reinterpret_cast<void*>(b + 0x13192e58),   // completer core (captures the predecessor P per-thread)
                base_, ms);
        }
    }
    // ==== end of the MCPE sign-in probe family ====

    // Register THIS image in loaded_images() NOW -- before any guest code
    // executes (ifunc resolvers just below, then .init_array). Bionic does the
    // same: a library is in the linker's soinfo list while its own
    // constructors run, so dladdr / dl_iterate_phdr / /proc/self/maps all see
    // it from inside its own init. Three consumers depend on that here:
    //   1. The SEGV handler's fault recovery
    //      (bionic_shim.cpp try_recover_arm64_native_null_deref) gates on
    //      __macapk_arm64_native_addr_in_image(pc). Registration used to
    //      happen in the extern-C entry points AFTER load returned, so for a
    //      fault INSIDE the still-loading image's own .init_array the gate
    //      said "not ours" and a recoverable fault became fatal. That is
    //      exactly the 2026-08-20 TikTok crash: libstarship_sdk.so's init hit
    //      a machine-outliner-factored stack-canary read the neutralizer
    //      cannot see (the `ldr Xt,[Xn,#0x28]` lives INSIDE a shared outlined
    //      compare helper at +0x81be4, and Xn's TPIDR-ness is established
    //      only in its callers), the bit-48 fault (si_addr
    //      0x000100000000102e) was declined, and the JVM aborted. Reproduced
    //      deterministically with debug/stage6_load_one.c.
    //   2. __macapk_arm64_native_iterate_phdr: a guest constructor that
    //      throws AND catches a C++ exception needs its own image's
    //      PT_GNU_EH_FRAME findable by its statically-linked unwinder.
    //   3. The /proc/self/maps synthesizer: libs that locate themselves by
    //      parsing maps during init (metasec-class) see themselves, as on
    //      real Android.
    // Safe: every load-failure `return false` in load_common() precedes this
    // line, and nothing after it can fail, so the entry points' failure
    // paths never see a registered half-loaded image (they also
    // defensively unregister -- see unregister_image()). Mutation is
    // serialized by dyld's dlopen lock, same as the old push site.
    lap("apply_segment_protections");
    // dladdr's name first, so no reader can see this image without one.
    dladdr_fname.store(macapk_guest_image_realpath(this), std::memory_order_release);
    loaded_images().push_back(this);

    // Register .eh_frame FDEs with the host unwinder so guest C++ exceptions
    // unwind across host frames (item 4). After protections (eh_frame final),
    // before ifunc resolvers + run_init_array (constructors may throw).
    lap("(protections -> eh_frames)");
    register_eh_frames();

    // R_AARCH64_IRELATIVE resolvers run now -- after the RX flip above
    // (resolver is guest .text, non-executable until apply_segment_
    // protections()) and before run_init_array() (constructors may call
    // ifunc-backed libc routines). See deferred_ifunc_ for the rationale.
    lap("register_eh_frames");
    apply_ifunc_relocations();

    // ===== arm64 x18-trap mitigation: keep writable segments resident =====
    // (page-out variant of the geomfill/arena prefault family; shipped,
    //  default-on, MACAPK_ARENA_PREFAULT=0 opt-out, libunity-gated)
    //
    // macOS XNU zeroes the reserved arm64 register x18 on EVERY return to EL0,
    // INCLUDING the minor page fault taken when a previously-resident page is
    // re-faulted after the memory compressor reclaimed it under pressure.
    // libunity uses x18 as a LIVE scratch pointer inside long math loops (e.g.
    // the FMOD FFT butterfly). When such a loop READS a libunity static
    // table (a twiddle table in .data, a base pointer in .got) whose cold page
    // was compressed during the memory-heavy il2cpp boot, the re-fault zeroes
    // the live x18 and the NEXT x18 deref SIGSEGVs at NULL. This was the
    // post-Crashlytics Crossy crash: deterministic across runs (`ldr s3,[x18]`,
    // x18=0, si_addr=0 -- the fault that zeroed x18 is the preceding `ldp [x3]`
    // read of the .data twiddle table). The generic x18 rewriter now fixes the
    // root cause; this mlock-resident pass + the null-recovery handler remain as
    // belt-and-suspenders for any residual no-free-register loop.
    // The arena/geomfill prefault only commits Unity's heap arena, never
    // libunity's OWN .data/.got, so it never covered this. Wiring (mlock) the
    // writable LOAD segments faults them in AND keeps them resident for the
    // process lifetime, so an x18-live read of a libunity static never faults.
    // Bounded + cheap: libunity's writable span is ~1.8 MB; cap at 16 MB so a
    // pathologically large image is skipped (logged) rather than wired. Engine-
    // gated to libunity (the only loaded image known to use x18 as a GPR), like
    // the geomfill hook; cocos/OpenGD never match -> completely unaffected.
    {
        bool prefault_on = true;
        if (const char* e = std::getenv("MACAPK_ARENA_PREFAULT"))
            prefault_on = (e[0] != '0');
        const bool is_unity = dbg_name_ && std::strstr(dbg_name_, "libunity");
        if (prefault_on && is_unity) {
            size_t wired = 0;
            const size_t kCap = 16ull << 20;   // 16 MB per image
            for (const auto& ph : phdrs_) {
                if (ph.p_type != PT_LOAD) continue;
                if (!(ph.p_flags & PF_W)) continue;
                uintptr_t lo = align_down(uintptr_t(base_ + ph.p_vaddr), page_size_);
                uintptr_t hi = align_up(
                    uintptr_t(base_ + ph.p_vaddr + ph.p_memsz), page_size_);
                size_t len = hi - lo;
                if (wired + len > kCap) continue;   // skip pathologically large
                if (mlock(reinterpret_cast<void*>(lo), len) == 0) {
                    wired += len;
                } else {
                    std::fprintf(stderr,
                        "[x18-wire] %s: mlock(%#zx @ %#lx) failed: %s\n",
                        dbg_name_, len, (unsigned long)lo, std::strerror(errno));
                }
            }
            if (wired)
                std::fprintf(stderr,
                    "[x18-wire] %s: wired %#zx bytes of writable segments "
                    "resident (x18-trap page-out mitigation)\n",
                    dbg_name_, wired);
        }
    }

    // The Bionic .so we just loaded is about to start running real
    // code -- its init_array entries do C++ static initializers, some
    // of which call JNI (e.g. JniHelper::setClassLoaderFrom from
    // cocos2d-x). Install the Bionic-va_list -> Apple-jvalue JNI
    // bridge NOW, between protection-apply and init-array-run, so the
    // first Bionic JNI call goes through our translator instead of
    // hitting HotSpot's V variant with a Bionic va_list (which crashes
    // SIGBUS on jstring arg deref). We defer to here rather than at
    // shim-dlopen time because patching the JVM JNINativeInterface
    // vtable while the JVM is still in early class-loading (before
    // Boot.run starts) crashes JfrTraceId::assign -- the JVM's JFR
    // subsystem caches vtable function pointers during boot and gets
    // confused when they flip mid-boot. Patching after the JVM has
    // settled (i.e. after Boot.run has loaded the manifest and is
    // about to fire onCreate) keeps JFR happy.
    {
        // Use void* for the JavaVM handle here so we don't have to pull
        // in jni.h just for the forward type. Both sides (bionic_shim's
        // getter and the install function) treat it as opaque.
        typedef void (*install_fn_t)(void*);
        typedef void* (*get_vm_fn_t)();
        static std::atomic<bool> bridge_installed{false};
        if (!bridge_installed.exchange(true)) {
            install_fn_t install = reinterpret_cast<install_fn_t>(
                dlsym(RTLD_DEFAULT,
                      "__macapk_install_jni_bionic_variadic_bridge_vm"));
            get_vm_fn_t get_vm = reinterpret_cast<get_vm_fn_t>(
                dlsym(RTLD_DEFAULT, "__macapk_get_javavm"));
            if (install && get_vm) {
                void* vm = get_vm();
                if (vm) install(vm);
            }
        }
    }

    // Arm the SIGSEGV recovery handler now. On an arm32-lifter run the
    // handler gets installed lazily by allocate_guest_memory(); on a
    // pure arm64-native run there's no guest-memory allocation, so the
    // handler would never fire, and the first cocos2d-x NULL deref
    // (very common: CCObject::retain on a stale pointer, addChild
    // downstream NULLs) would kill the JVM with no chance to recover.
    // Installing the handler here ensures both code paths get the
    // same NULL-recovery semantics.
    {
        typedef void (*install_handler_t)();
        static std::atomic<bool> handler_installed{false};
        if (!handler_installed.exchange(true)) {
            install_handler_t install_h = reinterpret_cast<install_handler_t>(
                dlsym(RTLD_DEFAULT, "__macapk_install_segv_recovery"));
            if (install_h) install_h();
        }
    }

    // Init array runs AFTER protections are applied because the
    // constructors are executable code in (now-RX) .text. The shim
    // functions they call are resolved through already-patched GOT
    // entries.
    lap("apply_ifunc_relocations + hooks");
    run_init_array();
    std::fprintf(stderr,
        "[arm64-native] %s: init_array done\n", dbg_name_);

    // OneDS/1DS telemetry (github.com/microsoft/cpp_client_telemetry, statically archived into e.g.
    // Minecraft's libmaesdk.so) crashes in LogManagerImpl if its Android HTTP client is never
    // registered: HttpClientFactory::Create() returns the empty static HttpClient_Android::s_client
    // and m_httpClient->ApplySettings() then derefs null. On real Android the Java class
    // com.microsoft.applications.events.HttpClient's ctor registers s_client via native
    // createClientInstance; a compat layer that runs the native SDK without that Java construction
    // never does. Generic (gated on THIS image exporting the SDK's own registration entry point, not
    // on package name) and only for a LIVE image (a pairip-protected lib must have its imports filled
    // first, or its code is not runnable): resolve the export and register a minimal stub HttpClient
    // so telemetry gets a non-null offline-no-op client instead of a crash. Registers once.
    // See docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
    if (!is_pairip_import_protected() || pairip_imports_filled()) {
        // The OneDS-bearing image also statically archives sqlite3 (its OfflineStorage). On our
        // runtime the bundled sqlite3's allocator (sqlite3GlobalConfig.m.xMalloc) is left NULL and
        // sqlite3_open then SIGSEGVs calling it (x0 = a rounded alloc size) / fails SQLITE_NOMEM --
        // sqlite3MallocInit's default-allocator install never completes on our init path. Pre-run
        // the bundled sqlite3_initialize() here, ONCE and cleanly, before OneDS's OfflineStorage
        // uses it: sqlite3MallocInit installs sqlite3MemSetDefault's real libc-malloc methods, and
        // the isInit guard makes OneDS's later sqlite3_initialize a no-op. Plain C call (no JNIEnv);
        // resolved from THIS guest image so we get its own bundled sqlite3, not macOS libsqlite3.
        // See the sqlite3-init research in docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
        // EXPERIMENTAL (gated MACAPK_ONEDS_SQLITE_FIX, default OFF): the bundled sqlite3's allocator
        // ends up with a partial sqlite3_mem_methods (xMalloc set, xRoundup null) on our runtime, which
        // sqlite3's single-field `if(m.xMalloc==0)` sentinel does not repair -> mallocWithAlarm calls
        // null xRoundup -> SIGSEGV. Install a COMPLETE allocator via sqlite3_config(SQLITE_CONFIG_MALLOC)
        // then sqlite3_initialize. Still WIP (calling the guest's Bionic-variadic sqlite3_config from the
        // host has an ABI edge). See docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
        // A bundled sqlite3 compiled SQLITE_OS_OTHER (e.g. inside OneDS/libmaesdk) has its whole OS layer
        // (allocator, mutex, VFS) supplied at runtime, and on our runtime those come out with null method
        // pointers -> LogManagerImpl SIGSEGVs deep in sqlite3. Supply a complete OS layer ourselves: derive
        // sqlite3GlobalConfig.m GENERICALLY from sqlite3_config's disasm (no per-build offset), write our
        // allocator+mutex methods into the config, register a POSIX VFS, then sqlite3_initialize. sqlite3's
        // own error handling then runs and OneDS's OfflineStorage degrades gracefully instead of crashing.
        // DEFAULT-ON (v1.0.1573) for a pairip-LIVE image only (is_pairip_import_protected() &&
        // pairip_imports_filled()) -- that excludes every non-pairip app, so a normal app's own
        // (working) sqlite3 is never touched. The lookup() below is image-local, so it further no-ops
        // unless THIS guest lib statically bundles sqlite3 (only libmaesdk does). Validated sound +
        // generic v1.0.1571. Kill switch: MACAPK_ONEDS_SQLITE_FIX_OFF=1.
        if (!std::getenv("MACAPK_ONEDS_SQLITE_FIX_OFF")
            && is_pairip_import_protected() && pairip_imports_filled()) {
            void* sqinit = lookup("sqlite3_initialize");
            void* sqcfg  = lookup("sqlite3_config");
            if (sqinit && sqcfg) {
                using DeriveFn = void*(*)(void*);
                void* m_addr = nullptr;
                if (DeriveFn drv = reinterpret_cast<DeriveFn>(dlsym(RTLD_DEFAULT, "macapk_derive_sqlite3_config_m")))
                    m_addr = drv(sqcfg);
                std::fprintf(stderr, "[oneds] sqlite3 fix: %s config.m derived=%p\n", dbg_name_, m_addr);
                if (m_addr) {
                    using InstFn = int(*)(void*);
                    if (InstFn inst = reinterpret_cast<InstFn>(dlsym(RTLD_DEFAULT, "macapk_install_sqlite3_allocator")))
                        inst(m_addr);
                    if (void* vreg = lookup("sqlite3_vfs_register")) {
                        using VfsFn = int(*)(void*);
                        if (VfsFn vf = reinterpret_cast<VfsFn>(dlsym(RTLD_DEFAULT, "macapk_register_sqlite3_vfs")))
                            vf(vreg);
                    }
                    int rc = reinterpret_cast<int(*)()>(sqinit)();
                    std::fprintf(stderr, "[oneds] pre-initialized bundled sqlite3: rc=%d\n", rc);
                }
            }
        }
        if (void* cci = lookup("Java_com_microsoft_applications_events_HttpClient_createClientInstance")) {
            // Cross-binary call (the registrar lives in macapk-runtime, this loader in the shim), so
            // resolve it the same way the loader resolves its other runtime helpers.
            using RegFn = void(*)(void*);
            if (RegFn reg = reinterpret_cast<RegFn>(dlsym(RTLD_DEFAULT, "macapk_register_oneds_httpclient")))
                reg(cci);
        }
    }
    // V8 startup-flag override (W^X macOS fix). If this image embeds V8 (cohtml
    // /Coherent Gameface for MCPE's Ore-UI menu JS), its Android build defaults
    // to write_code_using_rwx=true + concurrent TurboFan, which cannot run under
    // macOS Apple Silicon's per-thread W^X (persistent RWX is hardware-forbidden;
    // the concurrent compiler threads race our RWX->RW protection toggle). V8
    // ships a runtime answer for exactly this constraint (it is how iOS, which
    // also forbids JIT, runs V8): parse startup flags via
    // v8::V8::SetFlagsFromString BEFORE V8::Initialize freezes them. We call it
    // here -- after init_array has run V8's flag static-initializers (defaults are
    // set) and long before cohtml calls V8::Initialize. Gated on the image
    // actually EXPORTING SetFlagsFromString, so it is a no-op for every non-V8
    // guest (generic, no per-APK branch). run_init_array is the right site: the
    // C++ runtime + our shims are up, so V8's flag parser (std::string / malloc)
    // works. See docs/mcpe-v8-jit-macos-wx_2026-07-05.md.
    apply_v8_flag_override();
    // cocos2d-x Cocos2dxActivity_nativeSetPaths: by default this does NOT
    // call anything (MACAPK_COCOS_SETPATHS_FROM_APP, default ON), because on
    // a device the app's own setPackageName() delivers the path after
    // JNI_OnLoad, and ours measurably does too. Under =0 it restores the old
    // load-time call, which has to run AFTER init_array (the export builds a
    // std::string; before init_array that SIGSEGVs in the operator-new
    // chain). The addSearchPath half was retired in v1.0.1587. Removal
    // condition and evidence: the header of install_cocos2dx_searchpath.
    // (A comment here used to say this ran BEFORE run_init_array and that
    // it initialised "s_pZipFile"; neither was true.)
    install_cocos2dx_searchpath();

    // (RAM: the embedded-ELF-blob reclaim that used to run here was moved
    // to right after map_segments(), near the top of this function --
    // see the "===== RAM: reclaim..." comment up there for the full
    // rationale. This is just the join point; nothing else changed.)
    lap("run_init_array + post-init");
    return true;
}

// In-place binary patch for libfmod's AudioDevice.init JNI call sites.
//
// On Android, libfmod queries Android-specific audio config sources
// (AAudio, system properties, AudioManager getProperty, /proc/sys
// files, etc.) to fill a struct that holds {channels, sampleRate,
// framesPerBuffer, bufferCount}. It then JNI-calls into
// org.fmod.AudioDevice.init(IIII)Z with those four values, which
// constructs an android.media.AudioTrack of the right format.
//
// On macOS those config queries all return NULL / fail / read
// uninitialized memory. The four ints arriving at our AudioDevice
// override come out as sequential garbage values like (1838512608,
// 1838512528, 1838512480, -40) -- a clear 4-int read from an
// uninitialized struct. The garbage propagates into libfmod's
// downstream audio-buffer allocation, which is where the
// inflate_state heap corruption originates that crashes the PNG
// decode.
//
// Two call sites in libfmod's JNI bootstrap invoke AudioDevice.init:
// the "fresh init" site (call site A, in-place patchable) and the
// "reinit on headphones change" site (call site B, requires a
// trampoline because the original 3-instruction sequence can't fit
// 4 register-constant loads in place). Both get sanitized to use
// Android-typical defaults: stereo, 44.1 kHz, 1024 frames per
// buffer, 4 buffers. Same category of fix as the synthetic
// /proc/cpuinfo -- libfmod sees a sane audio environment instead
// of the macOS-equivalent black hole.
//
// Detection is by code signature, not fixed offset, so any libfmod
// build (or any other library using the same JNI call sequence)
// gets the patch automatically. Both signatures are long enough
// (20+ bytes of specific ARM64 encodings) that false matches are
// essentially impossible.

namespace {
inline uint32_t encode_b(uintptr_t from, uintptr_t to) {
    int64_t off = int64_t(to) - int64_t(from);
    uint32_t imm26 = uint32_t((off >> 2) & 0x03ffffffu);
    return 0x14000000u | imm26;
}
inline uint32_t encode_bl(uintptr_t from, uintptr_t to) {
    int64_t off = int64_t(to) - int64_t(from);
    uint32_t imm26 = uint32_t((off >> 2) & 0x03ffffffu);
    return 0x94000000u | imm26;
}
}  // namespace

// Copy up to n bytes from a possibly-bad guest pointer without ever faulting.
// Read by the retired-by-default Unity drivers below (the Director tick and
// the render-state probe run only under MACAPK_REGISTERNATIVES_VERBATIM=0).
// It was the safe-read helper of the il2cpp / Unity diagnostic kit deleted
// 2026-09-27 (docs/il2cpp-diag-kit-retired_2026-09-27.md).
namespace {
size_t guest_safe_read(uint64_t src, void* dst, size_t n) {
    mach_vm_size_t out = 0;
    kern_return_t kr = mach_vm_read_overwrite(
        mach_task_self(), (mach_vm_address_t)src, n,
        (mach_vm_address_t)(uintptr_t)dst, &out);
    return (kr == KERN_SUCCESS) ? (size_t)out : 0;
}
}  // namespace

// C linkage for the Unity driver entry points below, which bionic_shim.cpp
// looks up with dlsym. The block closes after the render-state probe.
extern "C" {
// ===================================================================
// Unity per-frame DirectorManager update driver (ENGINE FIX, shipped)
// ===================================================================
//
// ⚠ RETIRED BY DEFAULT 2026-09-27 (MACAPK_REGISTERNATIVES_VERBATIM, bionic_shim.cpp).
// The premise below ("that streamlined path ... OMITS the player-loop's Director
// nodes") is REFUTED by measurement on the same binaries: frida on real-Android
// Crossy (changelog v1.0.251) reads RunOneFrame / UpdatePreloading = 0 per frame
// there too, and the player-loop census (changelog v1.0.322) measured the ENGINE
// driving the Director node every frame (Crossy all 10 captured nodes; Among Us /
// Subway 1918 / 4005 calls). DirectorManager::Update (Crossy 0x7fd31c) has no
// per-frame idempotence check, so this driver advanced every registered graph a
// second time per frame. Everything from here to the end of
// __macapk_arm64_unity_render_state runs only under MACAPK_REGISTERNATIVES_VERBATIM=0,
// and is deleted after one clean fleet cycle. The original text follows.
//
// WHAT THIS FIXES. Mac.apk drives Unity's per-frame work by posting the
// APK's `nativeRender` (libunity export, body 0x8ac244) to UnityMain each
// frame; that streamlined path runs the MonoBehaviour script-update +
// render phases but OMITS the player-loop's Director nodes
// (`Update.DirectorUpdate`, `PreLateUpdate.DirectorUpdateAnimationBegin/End`,
// `PreLateUpdate.DirectorDeferredEvaluate`). Those nodes are the ONLY thing
// that evaluates an active PlayableGraph / Timeline each frame: the native
// DirectorManager iterates every playing PlayableDirector, advances its
// graph by deltaTime, evaluates it, and fires the AnimationEvents / signals
// the graph reaches back into managed code. With the nodes omitted, any
// Unity app that plays a Timeline as a raw PlayableGraph (e.g. Crossy Road's
// HipsterWhale.SplashIntroAnimator, which has no managed Update -- it relies
// entirely on the engine auto-evaluating the graph) freezes: the Timeline
// never advances to its keyframes, so its AnimationEvent (Crossy's
// `SplashIntro::AllowAnimationToStart`) never fires and the splash never
// advances to its menu. Confirmed v1.0.232 (lldb on the live frozen splash):
// PlayableGraph::Evaluate = 0 hits/frame, AllowAnimationToStart = 0 hits;
// forcing the gate flag advances the splash, proving the missing evaluation
// is the only blocker. The DirectorManager nodes ARE present in libunity but
// are reached only via the runtime-built indirect player-loop fn-ptr
// dispatch (no static caller), which Mac.apk's partial loop never walks.
//
// WHY THIS IS THE ENGINE-CORRECT FIX (not a per-APK hack). It replicates
// EXACTLY the work the omitted player-loop Director nodes perform, for ANY
// Unity app, by calling the engine's own DirectorManager per-phase update on
// the live global DirectorManager singleton once per frame -- the same function
// the player loop's three Director node thunks tail-call. It is gated on the
// engine (libunity present + a non-null, initialized manager), never on a
// package name or asset. An app with no PlayableDirectors has an empty manager
// and the update is a cheap no-op (the manager's pending-changes processor and
// per-phase iterator self-guard on empty director buckets). The phases match
// the engine's own node ordering; we run the same set every frame.
//
// HOW WE FIND THE TARGET GENERICALLY (no build-specific vaddrs). Unity's player
// loop registers the Director update for three phases, each as a tiny thunk the
// engine emits in this exact, version-stable idiom (verified on Unity 6 / Crossy
// and unchanged for years):
//     adrp xR, <page>          ; phase 0 thunk:  mov w1, wzr
//     ldr  x0, [xR, #OFF]       ; phase 1 thunk:  mov w1, #1
//     b    DirectorManager::Update   ; phase 2 thunk:  mov w1, #2
// i.e. three CONSECUTIVE 4-instruction thunks that all load the SAME manager
// global ([page+OFF]) and tail-call the SAME target with phases 0/1/2. At load
// time we Capstone-free-decode libunity's executable segment for that triple
// (find_director_cluster below) and extract two image offsets: the manager-
// singleton global and DirectorManager::Update. On Crossy this resolves to
// exactly the values we previously hardcoded (manager global + update fn),
// uniquely (one cluster, zero false matches), so the behavior is byte-identical
// -- but it now works on ANY Unity build that ships the same node idiom, with
// no absolute address baked in. A build without the cluster (e.g. an app that
// plays no Timeline at boot) yields zero hits -> the driver stays a safe no-op.
//
// SAFETY. If the cluster is not found, g_director_layout_ok stays 0 and the
// driver never fires (no wild call). The manager pointer is read once and
// cached; a NULL manager (engine not yet far enough into init) skips the frame.
// Run on UnityMain only (the render thread the wrap already guards), matching
// where the player loop runs these nodes.
//
// GENERIC -- applies to OpenGD/cocos? No: those guests never load libunity,
// so g_director_unity_base stays 0 and the whole driver is inert. The
// MACAPK_DIRECTOR_OFF env opts out for A/B isolation; default is ON.
uint64_t g_director_unity_base = 0;     // libunity base (unconditional)
static std::atomic<uint64_t> g_director_manager{0};  // cached *(manager-singleton global)
static std::atomic<int> g_director_layout_ok{-1};    // -1 unknown, 0 not-found, 1 found
// Image offsets discovered structurally at load time (see find_director_cluster):
static uint64_t g_director_manager_off = 0;  // base-relative addr of the manager-singleton global
static uint64_t g_director_update_off  = 0;  // base-relative addr of DirectorManager::Update
static uint64_t g_director_node0_off   = 0;  // base-relative addr of the Director phase-0 node thunk
// libunity writable LOAD-segment span (base-relative), captured at load time so
// the runtime player-loop-table scan (MACAPK_PLOOP_DUMP diagnostic) knows where
// to look for the relocated/runtime-written node function-pointer table.
static uint64_t g_unity_rw_lo = 0, g_unity_rw_hi = 0;
static uint64_t g_unity_mapped_size = 0;  // libunity mapped image size (for ptr-range checks)

// Decode helpers for the Director-node-cluster scan (raw arm64, no Capstone).
static inline bool dn_is_adrp(uint32_t w) { return (w & 0x9f000000u) == 0x90000000u; }
static inline uint64_t dn_adrp_page(uint64_t pc, uint32_t w) {
    int64_t imm = (int64_t)(((w >> 5) & 0x7ffffu) << 2 | ((w >> 29) & 3u));
    if (imm & (1ll << 20)) imm -= (1ll << 21);   // sign-extend 21-bit
    return (pc & ~0xfffull) + (uint64_t)(imm << 12);
}
static inline bool dn_is_ldr_x_uimm(uint32_t w) { return (w & 0xffc00000u) == 0xf9400000u; }
static inline bool dn_is_b(uint32_t w) { return (w & 0xfc000000u) == 0x14000000u; }
static inline uint64_t dn_b_target(uint64_t pc, uint32_t w) {
    int64_t imm = (int64_t)(w & 0x3ffffffu);
    if (imm & (1ll << 25)) imm -= (1ll << 26);
    return pc + (uint64_t)(imm << 2);
}
// `mov w1, #imm` (the phase constant). Two encodings: orr w1,wzr,wzr (phase 0)
// or movz w1,#imm16 (no shift). Returns the immediate, or -1 if not a mov-to-w1.
static inline int dn_mov_w1_imm(uint32_t w) {
    if (w == 0x2a1f03e1u) return 0;                          // mov w1, wzr
    if ((w & 0xffe0001fu) == 0x52800001u) return (int)((w >> 5) & 0xffffu); // movz w1,#imm
    return -1;
}
// A Director player-loop node thunk loads the manager global and tail-calls the
// per-phase DirectorManager::Update:  `adrp xR,P ; ldr x0,[xR,#OFF] ; mov w1,#phase ; b TARGET`.
// The four instructions appear in a build-dependent ORDER (Unity 6 emits the
// mov before the ldr; Unity 2022 after), so we match them ORDER-AGNOSTICALLY
// inside the 4-word window: find exactly one adrp(xR), one ldr x0,[xR,#OFF],
// one mov w1,#phase, one b TARGET, with the ldr base == the adrp dest. Fills
// phase/global (= page(adrp)+OFF) / target (= b destination).
static bool dn_node(const uint32_t* w, uint64_t pc, int* phase,
                    uint64_t* global, uint64_t* target) {
    int adrp_rd = -1, ldr_rn = -1, ph = -1;
    uint64_t page = 0, off = 0, tgt = 0;
    bool have_adrp = false, have_ldr = false, have_mov = false, have_b = false;
    for (int j = 0; j < 4; ++j) {
        uint32_t insn = w[j];
        uint64_t ipc = pc + (uint64_t)j * 4;
        if (dn_is_adrp(insn)) {
            if (have_adrp) return false;                 // exactly one
            have_adrp = true; adrp_rd = (int)(insn & 0x1f); page = dn_adrp_page(ipc, insn);
        } else if (dn_is_ldr_x_uimm(insn) && (insn & 0x1f) == 0) {
            if (have_ldr) return false;
            have_ldr = true; ldr_rn = (int)((insn >> 5) & 0x1f);
            off = (uint64_t)((insn >> 10) & 0xfff) * 8;
        } else if (dn_mov_w1_imm(insn) >= 0) {
            if (have_mov) return false;
            have_mov = true; ph = dn_mov_w1_imm(insn);
        } else if (dn_is_b(insn)) {
            if (have_b) return false;
            have_b = true; tgt = dn_b_target(ipc, insn);
        } else {
            return false;                                // any other insn -> not a node
        }
    }
    if (!(have_adrp && have_ldr && have_mov && have_b)) return false;
    if (ldr_rn != adrp_rd) return false;                 // ldr must use the adrp dest as base
    *phase  = ph;
    *global = page + off;
    *target = tgt;
    return true;
}

// V8 startup-flag override for the macOS W^X JIT constraint.
//
// macOS Apple Silicon under ad-hoc / allow-jit signing cannot give V8 what its
// Android build wants: persistent RWX code pages (hardware-forbidden -- APRR
// write-protect is per-thread, so W and X are never simultaneously live for a
// page), and it cannot run V8's CONCURRENT TurboFan safely on top of our
// RWX->RW protection-toggle workaround (background compiler threads race the
// mutable per-page protection state -> torn/half-published code pointers ->
// SIGSEGV in RepresentationSelector::VisitNode). See
// docs/mcpe-v8-jit-macos-wx_2026-07-05.md.
//
// V8 has a runtime answer to a JIT-forbidden host: startup flags. This is how
// V8 runs on iOS (which also forbids JIT for non-Safari apps). We set them here
// via the guest's own exported v8::V8::SetFlagsFromString(const char*, size_t),
// BEFORE cohtml calls V8::Initialize (which FreezeFlags()-locks them). The
// symbol is a GLOBAL export in libminecraftpe.so (verified: 24306 _ZN2v8 dynsyms
// incl. _ZN2v82V818SetFlagsFromStringEPKcm), so lookup() resolves it directly.
//
// Gated on the image EXPORTING SetFlagsFromString -> a no-op for every non-V8
// guest (generic, no per-APK branch). MACAPK_V8_FLAGS overrides the flag string;
// MACAPK_V8_FLAGS="" (empty) disables the override entirely (for A/B).
void Image::apply_v8_flag_override() {
    // Resolve the guest's flag-setter. The `(const char*, size_t)` overload is
    // preferred: it takes an explicit length so it never calls back into a guest
    // strlen, and is the exact entry v8::V8::SetFlagsFromString(const char*)
    // forwards to.
    typedef void (*set_flags_t)(const char*, size_t);
    set_flags_t set_flags = reinterpret_cast<set_flags_t>(
        lookup("_ZN2v88internal8FlagList18SetFlagsFromStringEPKcm"));
    if (!set_flags) {
        set_flags = reinterpret_cast<set_flags_t>(
            lookup("_ZN2v82V818SetFlagsFromStringEPKcm"));
    }
    if (!set_flags) return;   // not a V8-embedding image -> nothing to do

    // Only fire once per process (the first V8-embedding image wins; there is one
    // V8 per process).
    static std::atomic<bool> s_done{false};
    if (s_done.exchange(true)) return;

    // Default flag set. `--jitless` forces V8 to run entirely in the Ignition
    // bytecode interpreter with NO machine-code generation -- which removes the
    // W^X requirement at the root (no RWX mprotect, no code-page protection
    // toggling, no I-cache flush hazard). It is a fully supported V8 mode (the iOS
    // path -- iOS also forbids JIT for non-Safari apps, so this is exactly how V8
    // runs there). Jitless is REQUIRED (not merely convenient): with JIT on, MCPE
    // SIGSEGVs in V8's TurboFan `RepresentationSelector::VisitNode` reading a torn
    // heap pointer. That crash is NOT concurrency (proven: `--single-threaded
    // --no-concurrent-recompilation` crashes at the identical instruction) and NOT
    // the x18 pass (proven: JIT-on with the x18 rewrite disabled STILL crashes at
    // the identical instruction) -- it is a JIT-codegen-path corruption (the
    // RWX->RW protection toggle vs V8's own code-write/I-cache expectations). The
    // interpreter path has no generated code, so it is unaffected. jitless +
    // x18-rewrite-off is the confirmed-stable config (MCPE runs 60fps, uploads menu
    // textures). Full RE: docs/mcpe-v8-x18-rewrite-torn-pointer_2026-07-06.md. If a
    // future session wants JIT perf, the open path is to root-cause the RWX->RW /
    // I-cache codegen corruption, not to re-enable concurrency.
    const char* env = std::getenv("MACAPK_V8_FLAGS");
    const char* flags = env ? env : "--jitless";
    if (flags[0] == '\0') {
        std::fprintf(stderr,
            "[v8-flags] %s: MACAPK_V8_FLAGS empty -> override DISABLED\n",
            dbg_name_);
        return;
    }

    std::fprintf(stderr,
        "[v8-flags] %s: exports V8 SetFlagsFromString; applying \"%s\" "
        "(setter=%p)\n", dbg_name_, flags, (void*)set_flags);
    set_flags(flags, std::strlen(flags));
    std::fprintf(stderr, "[v8-flags] %s: applied\n", dbg_name_);
}

// MACAPK_REGISTERNATIVES_VERBATIM (bionic_shim.cpp, default ON): natives are
// registered as the app gave them, so the nativeRender wrap that called the
// Director / coroutine / census drivers below is not installed.
extern "C" int __macapk_registernatives_verbatim(void);

void Image::record_unity_director_base() {
    if (!dbg_name_ || !std::strstr(dbg_name_, "libunity")) return;
    // The base, the mapped size and the RW span below are published in BOTH arms,
    // as before. (The il2cpp boot-state diagnostics that also read
    // g_director_unity_base went with the il2cpp diag kit, 2026-09-27.)
    g_director_unity_base = (uint64_t)(uintptr_t)base_;
    g_unity_mapped_size = mapped_size_;

    // Find the executable LOAD segment range (offsets from base_).
    uint64_t exec_lo = 0, exec_hi = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type == 1 /*PT_LOAD*/ && (ph.p_flags & 1 /*PF_X*/)) {
            exec_lo = ph.p_vaddr;
            exec_hi = ph.p_vaddr + ph.p_memsz;
            break;
        }
    }
    // Scan for three CONSECUTIVE Director node thunks (phases 0,1,2) that load
    // the same manager global and tail-call the same DirectorManager::Update.
    // base_ + p_vaddr is where the loader mapped the segment, so an ELF vaddr V
    // equals the base-relative offset V; we use `off` as the PC for adrp/b math.
    // Capture the writable LOAD-segment span (for the runtime player-loop scan).
    for (const auto& ph : phdrs_) {
        if (ph.p_type == 1 /*PT_LOAD*/ && (ph.p_flags & 2 /*PF_W*/)) {
            if (g_unity_rw_lo == 0 || ph.p_vaddr < g_unity_rw_lo) g_unity_rw_lo = ph.p_vaddr;
            if (ph.p_vaddr + ph.p_memsz > g_unity_rw_hi) g_unity_rw_hi = ph.p_vaddr + ph.p_memsz;
        }
    }
    // Under MACAPK_REGISTERNATIVES_VERBATIM=1 nothing consumes the Director
    // cluster: only the drivers the retired nativeRender wrap called read it. So
    // skip the scan and leave g_director_layout_ok at -1 (unknown). =0 scans
    // exactly as before.
    if (__macapk_registernatives_verbatim()) {
        std::fprintf(stderr,
            "[director] libunity base=%#llx: Director-cluster scan skipped, no "
            "Director/coroutine driver (MACAPK_REGISTERNATIVES_VERBATIM=1; "
            "libunity's own player loop runs DirectorManager::Update)\n",
            (unsigned long long)g_director_unity_base);
        return;
    }
    int hits = 0;
    uint64_t found_global = 0, found_update = 0, found_node0 = 0;
    if (exec_hi > exec_lo && exec_hi <= mapped_size_) {
        for (uint64_t off = exec_lo; off + 48 <= exec_hi; off += 4) {
            const uint32_t* w = reinterpret_cast<const uint32_t*>(base_ + off);
            int p0, p1, p2; uint64_t g0, g1, g2, t0, t1, t2;
            if (!dn_node(w,     off,      &p0, &g0, &t0) || p0 != 0) continue;
            if (!dn_node(w + 4, off + 16, &p1, &g1, &t1) || p1 != 1) continue;
            if (!dn_node(w + 8, off + 32, &p2, &g2, &t2) || p2 != 2) continue;
            if (g0 != g1 || g1 != g2) continue;
            if (t0 != t1 || t1 != t2) continue;
            if (++hits == 1) { found_global = g0; found_update = t0; found_node0 = off; }
        }
    }
    bool ok = (hits >= 1) && found_global < mapped_size_ && found_update < mapped_size_;
    if (ok) {
        g_director_manager_off = found_global;
        g_director_update_off  = found_update;
        g_director_node0_off   = found_node0;
    }
    g_director_layout_ok.store(ok ? 1 : 0, std::memory_order_release);
    std::fprintf(stderr,
        "[director] libunity base=%#llx director-update driver %s "
        "(clusters=%d manager_global=+%#llx update=+%#llx)\n",
        (unsigned long long)g_director_unity_base,
        ok ? "ARMED" : "DISABLED (no Director node cluster found)",
        hits, (unsigned long long)found_global, (unsigned long long)found_update);
}

// (Tombstone, 2026-09-27: g_macapk_fmod_fft_x18_patched is DELETED. It was set
// at the end of install_x18_generic_rewrite when dbg_name_ contained "libunity",
// as a proxy for "FMOD's FFT x18 trap is fixed in this image", and it chose the
// AAudio default in bionic_shim.cpp. v1.0.1116 made real AAudio the default for
// every app, after which nothing read it. The trap it stood for is covered
// without it: the AAudio feeder turns on x18 preservation before it runs the
// guest callback (custom-x18-abi-toggle, macOS 26.4 floor). See bionic_shim.cpp
// "libaaudio.so has ONE implementation" and docs/fmod-aaudio-one-path_2026-09-27.md.)

// ===================================================================
// GENERIC x18 platform-register rewrite (the real, app-agnostic fix)
// ===================================================================
// THE PROBLEM. Apple's XNU zeroes the reserved register x18 on EVERY return
// to EL0 (page fault, syscall, AND async timer preemption). Android arm64
// guest code (Stage 6 runs it verbatim) was built WITHOUT -ffixed-x18, so
// clang freely allocated x18 as an ordinary scratch/base GPR. Any x18 value
// live across a kernel entry is silently lost -> the next x18-relative access
// derefs a tiny address -> SIGSEGV/SIGTRAP. This blocks every Unity title with
// such a loop (Temple Run 2, Among Us, the Crossy FMOD-FFT + geometry-fill).
// Prefault only covers page-fault loss (never preemption); per-instruction
// signal recovery cannot make a strided loop terminate. The only mechanisms
// that fix it generically are (a) the deprecated sdk<13 kernel lever (rejected:
// Apple flags it temporary) and (b) THIS: rewrite x18 out of the guest's .text.
//
// THE FIX. For each function, rename x18 -> a register the kernel preserves.
// Any GPR except x18 is preserved across EL0 returns, so a free register that
// matches x18's caller-saved nature (x0..x17, so we never need prologue/epilogue
// surgery to honour the callee-saved contract) is a drop-in replacement.
//
//   1. Function boundaries come from .eh_frame_hdr's sorted function-start
//      table (reliable without a symbol table; consecutive starts delimit each
//      function and inter-function padding is harmless / x18-free).
//   2. Per function: Capstone-disassemble, accumulate the set of GP registers
//      actually used, and if x18 is among them, pick R = the first free
//      caller-saved register (x9..x15 preferred -> pure temps).
//   3. For each x18 instruction, build the candidate (replace the 5-bit
//      register field(s) holding 18 with R), then RE-DISASSEMBLE and accept the
//      rewrite ONLY if the result is byte-identical to the original with x18->R
//      and nothing else changed. Any failure (ambiguous encoding, no free reg)
//      -> skip the whole function (the existing prefault/recovery cover the
//      residual). The re-decode makes a corrupting rewrite impossible.
//
// No per-APK / per-build data: boundaries + register choice are derived from
// the binary at load time, so it works on any arm64 guest, any Unity build.
// Default-OFF (MACAPK_X18_REWRITE_GENERIC=1) until validated across the
// protected apps, then flipped default-on (the per-build fft/geomfill hooks
// then no-op or retire).

// Capstone reg-name -> GP index (0..30), 31 for SP, -1 for zr/other.
static int macapk_name_to_gp(const char* nm) {
    if (!nm) return -1;
    if (nm[0] == 'x' || nm[0] == 'w') {
        const char* d = nm + 1;
        if (*d >= '0' && *d <= '9') {
            int v = 0;
            for (; *d >= '0' && *d <= '9'; ++d) v = v * 10 + (*d - '0');
            if (*d == 0 && v >= 0 && v <= 30) return v;
            return -1;
        }
    }
    if (!std::strcmp(nm, "sp") || !std::strcmp(nm, "wsp")) return 31;
    if (!std::strcmp(nm, "fp")) return 29;
    if (!std::strcmp(nm, "lr")) return 30;
    return -1;  // xzr/wzr/vector/system -> not a relevant GPR
}

// Replace whole-token x<from>/w<from> with x<to>/w<to> in a Capstone op_str.
// Whole-token only: never touches "0x18" immediates or other registers.
static void macapk_reg_subst(const char* in, int from, int to,
                             char* out, size_t outsz) {
    auto is_an = [](char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') ||
               (ch >= 'A' && ch <= 'Z') || ch == '_';
    };
    char fromdig[8];
    int fl = std::snprintf(fromdig, sizeof fromdig, "%d", from);
    size_t oi = 0;
    for (size_t i = 0; in[i] && oi + 1 < outsz;) {
        char c = in[i];
        bool boundary = (i == 0) || !is_an(in[i - 1]);
        if ((c == 'x' || c == 'w') && boundary
            && std::strncmp(in + i + 1, fromdig, (size_t)fl) == 0
            && !(in[i + 1 + fl] >= '0' && in[i + 1 + fl] <= '9')) {
            int wn = std::snprintf(out + oi, outsz - oi, "%c%d", c, to);
            if (wn < 0) break;
            oi += (size_t)wn;
            i += 1 + fl;
        } else {
            out[oi++] = c;
            i++;
        }
    }
    out[oi] = 0;
}

// Read one DW_EH_PE-encoded value from p (advancing p). ehf = .eh_frame_hdr
// base (for DW_EH_PE_datarel). Handles the encodings clang emits.
static uint64_t macapk_dw_read(const uint8_t*& p, uint8_t enc,
                               const uint8_t* ehf) {
    if (enc == 0xff) return 0;  // DW_EH_PE_omit
    uint64_t appbase = 0;
    switch (enc & 0x70) {
        case 0x10: appbase = reinterpret_cast<uint64_t>(p); break;  // pcrel
        case 0x30: appbase = reinterpret_cast<uint64_t>(ehf); break;  // datarel
        default:   appbase = 0; break;                               // absolute
    }
    int64_t v = 0;
    switch (enc & 0x0f) {
        case 0x00: { uint64_t x; std::memcpy(&x, p, 8); p += 8; v = (int64_t)x; break; }
        case 0x02: { uint16_t x; std::memcpy(&x, p, 2); p += 2; v = x; break; }
        case 0x03: { uint32_t x; std::memcpy(&x, p, 4); p += 4; v = x; break; }
        case 0x04: { uint64_t x; std::memcpy(&x, p, 8); p += 8; v = (int64_t)x; break; }
        case 0x0a: { int16_t x;  std::memcpy(&x, p, 2); p += 2; v = x; break; }
        case 0x0b: { int32_t x;  std::memcpy(&x, p, 4); p += 4; v = x; break; }
        case 0x0c: { int64_t x;  std::memcpy(&x, p, 8); p += 8; v = x; break; }
        case 0x01: { uint64_t r = 0; int s = 0; uint8_t b;
                     do { b = *p++; r |= (uint64_t)(b & 0x7f) << s; s += 7; }
                     while (b & 0x80); v = (int64_t)r; break; }
        case 0x09: { uint64_t r = 0; int s = 0; uint8_t b;
                     do { b = *p++; r |= (uint64_t)(b & 0x7f) << s; s += 7; }
                     while (b & 0x80);
                     if (s < 64 && (b & 0x40)) r |= -((uint64_t)1 << s);
                     v = (int64_t)r; break; }
        default: return 0;
    }
    return appbase + (uint64_t)v;
}

// Register this image's .eh_frame FDEs with the host macOS libunwind so a C++
// exception thrown by guest code can unwind across our shim/host frames (and
// reach the guest catch) instead of aborting. Without this, a guest
// __cxa_throw resolves (via dlsym) to macOS libc++abi, whose libunwind finds
// FDEs ONLY via _dyld_find_unwind_sections() -- which cannot see our mmap'd
// guest images -> the unwinder hits END_OF_STACK -> std::terminate -> SIGABRT
// before the catch. We walk the .eh_frame_hdr (PT_GNU_EH_FRAME) binary-search
// table, whose entries are (initial_location, FDE_ptr) pairs, and
// __register_frame() each FDE (Apple's __register_frame takes a single FDE).
// Generic (gated on "has PT_GNU_EH_FRAME"), complements the Case-A path (a
// guest that bundles its OWN libunwind self-discovers via dl_iterate_phdr).
// Cannot regress host/Case-A unwinds: the FDEs cover only guest PC ranges, so
// libunwind's cache miss for any non-guest PC still falls back to dyld.
// Escape hatch: MACAPK_NO_EH_REGISTER=1. Deregistered in ~Image.
// See docs/arm64-cross-frame-eh-unwind_2026-06-21.md.
void Image::register_eh_frames() {
    if (std::getenv("MACAPK_NO_EH_REGISTER")) return;
    macapk_frame_fn reg = macapk_register_frame_fn();
    if (!reg) return;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != 0x6474e550u) continue;  // PT_GNU_EH_FRAME
        const uint8_t* ehf = base_ + ph.p_vaddr;
        const uint8_t* p = ehf;
        uint8_t version    = *p++;
        uint8_t ehptr_enc  = *p++;
        uint8_t fdecnt_enc = *p++;
        uint8_t table_enc  = *p++;
        if (version != 1 || table_enc == 0xff) break;
        (void)macapk_dw_read(p, ehptr_enc, ehf);            // eh_frame_ptr (skip)
        uint64_t fde_count = macapk_dw_read(p, fdecnt_enc, ehf);
        if (fde_count == 0 || fde_count > (1u << 24)) break;
        const uint64_t img_lo = reinterpret_cast<uint64_t>(base_);
        const uint64_t img_hi = img_lo + mapped_size_;
        size_t registered = 0;
        for (uint64_t i = 0; i < fde_count; ++i) {
            (void)macapk_dw_read(p, table_enc, ehf);          // initial_location (skip)
            uint64_t fde = macapk_dw_read(p, table_enc, ehf); // FDE runtime ptr
            // Bounds-check: a malformed table must never hand __register_frame
            // a wild pointer -- the FDE must live inside this mapped image.
            if (fde < img_lo || fde >= img_hi) continue;
            reg(reinterpret_cast<const void*>(fde));
            registered_fdes_.push_back(reinterpret_cast<void*>(fde));
            ++registered;
        }
        std::fprintf(stderr,
            "[arm64-native] %s: registered %zu/%llu .eh_frame FDE(s) with host "
            "libunwind\n", dbg_name_, registered,
            (unsigned long long)fde_count);
        break;  // one PT_GNU_EH_FRAME per image
    }
}

// Extract the GP registers named in a Capstone op_str (x0..x30, w0..w30, sp,
// fp, lr) as a bitmask. Used as a SAFE fallback when cs_regs_access can't
// analyze an instruction (some NEON structured loads): we then treat those
// registers as conservatively live, rather than poisoning the whole function's
// liveness with an all-registers marker.
static uint32_t macapk_gp_mask_from_opstr(const char* s) {
    auto is_an = [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') || c == '_';
    };
    uint32_t m = 0;
    for (size_t i = 0; s[i];) {
        char c = s[i];
        bool b = (i == 0) || !is_an(s[i - 1]);
        if (b && (c == 'x' || c == 'w') && s[i + 1] >= '0' && s[i + 1] <= '9') {
            int v = 0; size_t j = i + 1;
            for (; s[j] >= '0' && s[j] <= '9'; ++j) v = v * 10 + (s[j] - '0');
            if (v >= 0 && v <= 30 && !is_an(s[j])) m |= (1u << v);
            i = j;
        } else if (b && c == 's' && s[i + 1] == 'p' && !is_an(s[i + 2])) {
            m |= (1u << 31); i += 2;
        } else if (b && c == 'f' && s[i + 1] == 'p' && !is_an(s[i + 2])) {
            m |= (1u << 29); i += 2;
        } else if (b && c == 'l' && s[i + 1] == 'r' && !is_an(s[i + 2])) {
            m |= (1u << 30); i += 2;
        } else {
            ++i;
        }
    }
    return m;
}

// Capstone reg-name -> SIMD/FP register index (0..31) for v/q/d/s/h/b regs,
// else -1. Used to compute which SIMD registers a function touches, so the x18
// detour-thunk lowering can pick an UNUSED callee-saved SIMD reg as x18's home.
static int macapk_name_to_vec(const char* nm) {
    if (!nm) return -1;
    char c = nm[0];
    if (c == 'v' || c == 'q' || c == 'd' || c == 's' || c == 'h' || c == 'b') {
        const char* p = nm + 1;
        if (*p >= '0' && *p <= '9') {
            int v = 0;
            for (; *p >= '0' && *p <= '9'; ++p) v = v * 10 + (*p - '0');
            // stop at the first non-digit (a '.' arrangement suffix like v3.4s
            // is fine); the numeric prefix is the register index.
            if (v >= 0 && v <= 31) return v;
        }
    }
    return -1;
}

// ---- x18 detour-thunk lowering ------------------------------------------
// For a "nofree" web (x18 co-live with every GPR, so no rename target),
// evict x18 to an unused callee-saved SIMD register. Each x18 instruction is
// replaced in place by a 4-byte `b <thunk>`; the thunk loads x18's value from
// its SIMD home into a scratch GPR, runs the original op (with x18 -> scratch),
// writes any x18 result back to the home, and `b`s back to the next guest
// instruction. The scratch GPR is saved/restored in a SECOND unused SIMD reg,
// so the thunk touches NEITHER the stack NOR sp -- sp-relative operands in the
// original op stay valid, and there is no red-zone/signal hazard. SIMD state is
// preserved by XNU across preemption and by the callee across a bl (d8..d15),
// so x18's homed value survives everything the raw x18 could not.
//
// Encoders emit one AArch64 word each; verified by re-disassembly in debug.
static inline uint32_t enc_b(uint64_t from, uint64_t to) {   // b <to>
    int64_t d = (int64_t)to - (int64_t)from;
    return 0x14000000u | ((uint32_t)((d >> 2)) & 0x03FFFFFFu);
}
static inline uint32_t enc_movz(int xd, uint16_t imm, int hw) {  // movz Xd,#imm,lsl hw*16
    return 0xD2800000u | ((uint32_t)(hw & 3) << 21) | ((uint32_t)imm << 5) | (uint32_t)(xd & 31);
}
static inline uint32_t enc_movk(int xd, uint16_t imm, int hw) {  // movk Xd,#imm,lsl hw*16
    return 0xF2800000u | ((uint32_t)(hw & 3) << 21) | ((uint32_t)imm << 5) | (uint32_t)(xd & 31);
}
// cbz/cbnz Rt, <to>  (sf picks x vs w). off must fit signed imm19*4 (+/-1MB).
// Base opcode is the 32-bit (sf=0) form 0x34/0x35; the sf bit (0x80000000)
// selects the 64-bit form.
static inline uint32_t enc_cbz(int rt, bool is64, bool nz, uint64_t from, uint64_t to) {
    int64_t d = ((int64_t)to - (int64_t)from) >> 2;
    return (is64 ? 0x80000000u : 0u) | (nz ? 0x35000000u : 0x34000000u)
         | (((uint32_t)d & 0x7FFFFu) << 5) | (uint32_t)(rt & 31);
}
// tbz/tbnz Rt,#bit,<to>. off must fit signed imm14*4 (+/-32KB).
static inline uint32_t enc_tbz(int rt, int bit, bool nz, uint64_t from, uint64_t to) {
    int64_t d = ((int64_t)to - (int64_t)from) >> 2;
    return (((uint32_t)(bit >> 5) & 1u) << 31) | (nz ? 0x37000000u : 0x36000000u)
         | (((uint32_t)bit & 31u) << 19) | (((uint32_t)d & 0x3FFFu) << 5) | (uint32_t)(rt & 31);
}
// ins Vd.d[lane], Xn  (GPR -> one 64-bit SIMD lane; the OTHER lane is preserved).
static inline uint32_t enc_ins_d(int vd, int lane, int xn) {
    return 0x4E001C00u | ((lane ? 0x18u : 0x08u) << 16) | ((uint32_t)(xn & 31) << 5) | (uint32_t)(vd & 31);
}
// umov Xd, Vn.d[lane]  (one 64-bit SIMD lane -> GPR).
static inline uint32_t enc_umov_d(int xd, int vn, int lane) {
    return 0x4E003C00u | ((lane ? 0x18u : 0x08u) << 16) | ((uint32_t)(vn & 31) << 5) | (uint32_t)(xd & 31);
}

// x0..x8: what a call (or a tail call, or svc) reads -- AAPCS64 arguments in
// x0..x7, the indirect-result address in x8. See the classification loop in
// macapk_x18_rewrite_one.
static constexpr uint32_t kX18CallArgRegs = 0x1FFu;
// MACAPK_X18_CALL_ARGS=0 restores the pre-2026-10-06 liveness (calls and tail
// calls read nothing), for bisecting and as debug/x18callargs_gate.sh's control.
// Pair it with MACAPK_X18_CACHE=0: a plan the default wrote can skip a window that
// the =0 analysis WOULD rewrite (less liveness, more rename targets).
static uint32_t macapk_x18_call_arg_regs() {
    static const uint32_t m = [] {
        const char* e = std::getenv("MACAPK_X18_CALL_ARGS");
        return (e && e[0] == '0') ? 0u : kX18CallArgRegs;
    }();
    return m;
}

// Per-function x18 rewrite. status: 0 no-x18, 1 rewrote, 2 no free reg,
// 3 verify/encoding refusal.
struct MacapkX18Res { int status; int insns_rewritten; int thunked; };

// Per-instruction GP/SIMD read-write masks for the x18 pass. ONE implementation,
// called by both the full per-function decode and the prefilter's confirm step
// below, so the two can never disagree about whether an instruction touches x18.
static void macapk_x18_classify(csh h, cs_insn* insn, uint32_t* pu, uint32_t* pd,
                                uint32_t* pvu, uint32_t* pvd) {
    uint32_t u = 0, d = 0;
    uint32_t vu = 0, vd = 0;   // SIMD read/write this insn
    cs_detail* dt = insn->detail;
    // Classify read/write PER OPERAND via the .access field, NOT via
    // cs_regs_access. cs_regs_access misclassifies the compare aliases
    // (`cmp Xn,Xm` = `subs xzr,Xn,Xm`) as WRITING Xn -- a false def
    // that wrongly kills Xn's liveness and lets us pick a live base
    // register as a replacement (the x16-clobber bug). A false USE is
    // harmless (over-conservative); a false DEF corrupts. Operand
    // .access has no false defs, and memory base/index are reads
    // (with base also written on a writeback addressing mode).
    if (dt) {
        for (int o = 0; o < dt->arm64.op_count; ++o) {
            const cs_arm64_op* op = &dt->arm64.operands[o];
            if (op->type == ARM64_OP_REG) {
                const char* rn = cs_reg_name(h, op->reg);
                int g = macapk_name_to_gp(rn);
                if (g >= 0 && g <= 31) {
                    if (op->access & CS_AC_WRITE) d |= (1u << g);
                    if ((op->access & CS_AC_READ) || op->access == 0)
                        u |= (1u << g);
                } else {
                    int vv = macapk_name_to_vec(rn);
                    if (vv >= 0) {
                        if (op->access & CS_AC_WRITE) vd |= (1u << vv);
                        if ((op->access & CS_AC_READ) || op->access == 0)
                            vu |= (1u << vv);
                    }
                }
            } else if (op->type == ARM64_OP_MEM) {
                int gb = op->mem.base ? macapk_name_to_gp(cs_reg_name(h, op->mem.base)) : -1;
                int gi = op->mem.index ? macapk_name_to_gp(cs_reg_name(h, op->mem.index)) : -1;
                if (gb >= 0 && gb <= 31) {
                    u |= (1u << gb);
                    if (dt->arm64.writeback) d |= (1u << gb);
                }
                if (gi >= 0 && gi <= 31) u |= (1u << gi);
            }
        }
    } else {
        u = macapk_gp_mask_from_opstr(insn->op_str);
    }
    // Capstone bug: the flag-setting compare aliases (cmp/cmn/tst =
    // subs/adds/ands to xzr, and ccmp/ccmn) mark their FIRST operand
    // as WRITE, but it is only READ -- they write no GP register, only
    // flags. Reclassify the false def as a use so liveness is correct
    // (this false def was killing live base registers -> clobber).
    {
        const char* mn = insn->mnemonic;
        if (!std::strcmp(mn, "cmp") || !std::strcmp(mn, "cmn") ||
            !std::strcmp(mn, "tst") || !std::strcmp(mn, "ccmp") ||
            !std::strcmp(mn, "ccmn")) { u |= d; d = 0; }
    }
    *pu = u; *pd = d; *pvu = vu; *pvd = vd;
}

// ---- x18 PREFILTER: skip Capstone for functions that CANNOT reference x18 ----
//
// MEASURED 2026-09-22 (sample + a de-blinded boottime timeline): on a COLD
// launch of Among Us, 12.3 s of a 19 s time-to-first-frame is this pass --
// libil2cpp 7.1 s (276,278 functions, 11.2 M instructions), libunity 2.7 s,
// libEOSSDK 2.5 s -- and `sample` put 95% of the loader's time inside
// cs_disasm_iter, most of it Capstone's TEXT printer (AArch64_printInst ->
// AArch64_map_insn -> strcmp) producing mnemonics nobody reads. Yet all of it is
// thrown away by `if (!any18) return` for ~99% of functions (libunity: 366 of
// 52,576 touch x18; libil2cpp: 1 of 276,278). The plan cache hides this on a
// warm launch; every first launch, and every launch after anything invalidates
// the plan (it is keyed on the post-canary-patch code bytes), pays it in full.
//
// THE FILTER IS EXACT, not a heuristic. `any18` is set only from Capstone's
// operand list (REG operands and MEM base/index), and every AArch64 GP-register
// operand is a 5-bit field at [4:0] Rd/Rt, [9:5] Rn, [14:10] Ra/Rt2 or
// [20:16] Rm/Rs. So an instruction whose four fields are all != 18 cannot report
// x18 or w18 -- except the FEAT_LS64 family (LD64B/ST64B/ST64BV/ST64BV0), which
// transfer EIGHT consecutive registers from Rt and can reach x18 with Rt=12..16;
// those always go to Capstone. Register PAIRS (CASP, SYSP, MRRS/MSRR, RCWCASP)
// start on an EVEN register by architectural rule, so a pair covers x18 only
// when its base field is 18 -- caught. No AArch64 instruction reads or writes
// x18 implicitly (the implicit-register forms use x15-x17 and x30).
//
// This is the SECOND stage. The scan loop already skipped any function with no
// raw candidate word at all, but at 1.9-2.7% of words being candidates almost
// every non-trivial function has one, so that stage alone saved little: the
// cost was the full decode that followed. Here a candidate word is decoded
// ALONE with Capstone and classified by the same macapk_x18_classify the full
// scan uses. AArch64 decoding is per-word and context-free, so one word decoded
// in isolation yields the operands it yields in the stream. If no candidate
// confirms, the function returns the zero result the full scan would have
// returned -- the scan has no side effects on that path -- so the pass's output
// is unchanged by construction. Checked on Among Us's four libraries
// (libil2cpp, libunity, libEOSSDK, libmain): MACAPK_X18_PLAN_DUMP output
// byte-identical, and every `[x18-generic] ... funcs=` summary line identical
// (funcs, x18-funcs, rewritten, insns, nofree, thunk webs/insns/bytes), with
// the prefilter on and off, twice each.
//
// So an x18-free function now costs ~40x fewer Capstone decodes.
// MACAPK_X18_NO_PREFILTER=1 restores the full decode of every function.
static inline bool macapk_word_may_ref_x18(uint32_t w) {
    if ((w & 0x1Fu) == 18u) return true;            // Rd / Rt
    if (((w >> 5) & 0x1Fu) == 18u) return true;     // Rn
    if (((w >> 10) & 0x1Fu) == 18u) return true;    // Ra / Rt2
    if (((w >> 16) & 0x1Fu) == 18u) return true;    // Rm / Rs
    if ((w & 0xFFFFFC00u) == 0xF83FD000u) return true;   // LD64B
    if ((w & 0xFFFFFC00u) == 0xF83F9000u) return true;   // ST64B
    if ((w & 0xFFE0FC00u) == 0xF820B000u) return true;   // ST64BV
    if ((w & 0xFFE0FC00u) == 0xF820A000u) return true;   // ST64BV0
    return false;
}

static bool macapk_x18_prefilter_enabled() {
    static const bool on = !std::getenv("MACAPK_X18_NO_PREFILTER");
    return on;
}

// True when some instruction in [fstart, fstart+flen) provably references x18
// (or when the answer cannot be ruled out cheaply). False means the full scan
// would find any18 == false and return the zero result.
static bool macapk_x18_window_may_use_x18(csh h, uint64_t fstart, size_t flen) {
    const uint8_t* base = reinterpret_cast<const uint8_t*>(fstart);
    const size_t nw = flen / 4;
    cs_insn* one = nullptr;
    bool hit = false;
    for (size_t k = 0; k < nw && !hit; ++k) {
        uint32_t w;
        std::memcpy(&w, base + 4 * k, 4);
        if (!macapk_word_may_ref_x18(w)) continue;
        if (!one) one = cs_malloc(h);
        const uint8_t* code = base + 4 * k;
        size_t sz = 4;
        uint64_t ad = fstart + 4 * k;
        if (!cs_disasm_iter(h, &code, &sz, &ad, one)) continue;   // data word
        uint32_t u = 0, d = 0, vu = 0, vd = 0;
        macapk_x18_classify(h, one, &u, &d, &vu, &vd);
        if (((u | d) >> 18) & 1u) hit = true;
    }
    if (one) cs_free(one, 1);
    return hit;
}

// Thunk pool cursor handed to macapk_x18_rewrite_one so it can lower nofree webs.
struct MacapkX18ThunkPool {
    uint8_t* base;
    size_t*  used;
    size_t   cap;
    uint64_t img_lo, img_hi;   // for `b` range checks (thunk<->guest within +/-128MB)
};

static MacapkX18Res macapk_x18_rewrite_one(csh h, uint64_t fstart, size_t flen,
                                           MacapkX18ThunkPool* tpool) {
    MacapkX18Res res{0, 0, 0};
    const bool dbg = (std::getenv("MACAPK_X18_DEBUG") != nullptr);
    // Cheap exact prefilter (see macapk_word_may_ref_x18): a function that
    // cannot reference x18 gets the zero result without a full Capstone decode.
    if (macapk_x18_prefilter_enabled() && !macapk_x18_window_may_use_x18(h, fstart, flen))
        return res;

    auto has_grp = [](cs_insn* ins, uint8_t g) {
        if (!ins->detail) return false;
        for (int gi = 0; gi < ins->detail->groups_count; ++gi)
            if (ins->detail->groups[gi] == g) return true;
        return false;
    };

    // Streaming RESYNC disassembly. A large eh_frame-gap window (FMOD/asm code
    // built without unwind tables -> no FDEs -> many functions merged into one
    // window) contains embedded DATA (literal pools, jump tables) that cs_disasm
    // cannot decode and would STOP at -- hiding every instruction past it,
    // including real x18 loops. So decode one instruction at a time and, on an
    // undecodable word, skip 4 bytes and resync. We extract everything we need
    // into parallel vectors and never retain the cs_insn array.
    std::vector<uint64_t> addr;
    std::vector<uint32_t> use, def;
    std::vector<char> fulllive;          // indirect-branch site: live-out = all regs
    std::vector<uint8_t> fkind;          // 0 normal, 1 ret, 2 call, 3 jump
    std::vector<uint64_t> ftgt;
    std::vector<char> fhas, funcond;
    std::vector<uint32_t> iword;         // raw instruction word (for thunk lowering)
    std::vector<uint32_t> vuse, vdef;    // SIMD (v0..v31) read/write masks per insn
    bool any18 = false;
    bool had_gap = false;  // an undecodable word forced a resync -> not a clean
                           // single-function window (merged asm / no-FDE region)
    {
        cs_insn* insn = cs_malloc(h);
        const uint8_t* code = reinterpret_cast<const uint8_t*>(fstart);
        size_t sz = flen;
        uint64_t ad = fstart;
        while (sz >= 4) {
            if (cs_disasm_iter(h, &code, &sz, &ad, insn)) {
                uint32_t u = 0, d = 0;
                uint32_t vu = 0, vd = 0;   // SIMD read/write this insn
                cs_detail* dt = insn->detail;
                macapk_x18_classify(h, insn, &u, &d, &vu, &vd);
                uint64_t tg = 0; bool has = false;
                if (dt)
                    for (int o = dt->arm64.op_count - 1; o >= 0; --o)
                        if (dt->arm64.operands[o].type == ARM64_OP_IMM) {
                            tg = (uint64_t)dt->arm64.operands[o].imm; has = true; break;
                        }
                uint8_t kind = has_grp(insn, CS_GRP_RET) ? 1
                             : has_grp(insn, CS_GRP_CALL) ? 2
                             : has_grp(insn, CS_GRP_JUMP) ? 3 : 0;
                bool uncond = (insn->id == ARM64_INS_B && dt
                    && (dt->arm64.cc == ARM64_CC_INVALID || dt->arm64.cc == ARM64_CC_AL));
                // A call READS its arguments: AAPCS64 passes them in x0..x7, and
                // x8 carries the indirect-result address. Capstone lists only the
                // branch operand (`blr x8` -> x8), so without this an argument that
                // the function receives and hands on untouched looked dead from
                // entry to the call, and x18 could be renamed onto it. Measured:
                // Temple Run 2's libunity 0xf64e30 forwards its x2 (PhysX's scratch
                // allocator) to BroadPhase::update through `blr x8`; x18 became x2,
                // the call got 1 and PhysX ran on a garbage allocator.
                // svc reads x0..x5 + x8 the same way, and the svc->BRK pass turns it
                // into a brk that our emulator reads the same registers at.
                if (kind == 2 || insn->id == ARM64_INS_SVC || insn->id == ARM64_INS_BRK)
                    u |= macapk_x18_call_arg_regs();
                addr.push_back(insn->address);
                use.push_back(u); def.push_back(d);
                fkind.push_back(kind); ftgt.push_back(tg);
                fhas.push_back(has ? 1 : 0); funcond.push_back(uncond ? 1 : 0);
                fulllive.push_back((kind == 3 && !has) ? 1 : 0);
                { uint32_t rw; std::memcpy(&rw, insn->bytes, 4); iword.push_back(rw); }
                vuse.push_back(vu); vdef.push_back(vd);
                if (((u | d) >> 18) & 1u) any18 = true;
            } else {
                code += 4; ad += 4; sz -= 4; had_gap = true;  // undecodable -> resync
            }
        }
        cs_free(insn, 1);
    }
    size_t n = addr.size();
    if (n == 0 || !any18) return res;

    std::unordered_map<uint64_t, size_t> idx;
    idx.reserve(n * 2);
    for (size_t k = 0; k < n; ++k) idx[addr[k]] = k;

    // Successors. Fall-through ONLY between address-contiguous instructions (a
    // resync gap / embedded data is not a fall-through).
    std::vector<std::vector<size_t>> succ(n);
    std::vector<char> exitedge(n, 0);
    auto cnext = [&](size_t k) -> long {
        return (k + 1 < n && addr[k + 1] == addr[k] + 4) ? (long)(k + 1) : -1;
    };
    // exitedge: 1 = `ret`, 2 = any other way out of the window. The commonest
    // "other" is a TAIL CALL (`b` to a target outside the window), which hands
    // x0..x8 on to its target exactly as a `bl` does; the rest (a call with no
    // fall-through, falling off the end) get the same conservative set.
    for (size_t k = 0; k < n; ++k) {
        long nx = cnext(k);
        if (fkind[k] == 1) { exitedge[k] = 1; }                       // ret
        else if (fkind[k] == 2) { if (nx >= 0) succ[k].push_back(nx); else exitedge[k] = 2; }  // call
        else if (fkind[k] == 3) {                                     // jump
            if (fhas[k]) {
                if (!funcond[k] && nx >= 0) succ[k].push_back(nx);
                auto it = idx.find(ftgt[k]);
                if (it != idx.end()) succ[k].push_back(it->second);
                else exitedge[k] = 2;                                 // tail call
            }                                                         // else indirect (fulllive)
        } else { if (nx >= 0) succ[k].push_back(nx); else exitedge[k] = 2; }  // normal
    }

    // REACHABILITY from real entry points (window start + every direct branch/
    // call target). A large eh_frame-gap window (FMOD / libunwind / static libs
    // without FDEs) contains embedded DATA -- string literals, constant pools,
    // jump tables -- that code jumps OVER, so it is NEVER reachable. Such data
    // bytes can coincidentally decode as an "x18 instruction" (e.g. Firebase's
    // "AcquireExecutor" symbol string); rewriting them corrupts the data. We only
    // rewrite REACHABLE instructions, so embedded data is left untouched.
    std::vector<char> reach(n, 0);
    {
        std::vector<size_t> stk;
        auto seed = [&](size_t i) { if (i < n && !reach[i]) { reach[i] = 1; stk.push_back(i); } };
        seed(0);  // window start is a function entry (it has an FDE)
        for (size_t k = 0; k < n; ++k)
            if (fhas[k]) {  // every DIRECT branch/call target is real code
                auto it = idx.find(ftgt[k]);
                if (it != idx.end()) seed(it->second);
            }
        while (!stk.empty()) {
            size_t k = stk.back(); stk.pop_back();
            for (size_t s : succ[k]) seed(s);
        }
    }

    // Live at a normal return = callee-saved x19..x28 + fp/lr + sp ONLY. The
    // epilogue reloads them (Capstone sees the load as a def), so they are killed
    // inside the body and stay free there. We do NOT force any caller-saved reg
    // live at exit: the ONLY GP return-value registers in AAPCS64 are x0/x1
    // (anything larger is returned via the x8 memory pointer), and we exclude
    // x0/x1 from the candidate set (kCaller below). So no register we ever pick
    // as a replacement can be a return value -- no exit protection is needed, and
    // unused caller-saved spares (x2..x7) stay available without phantom liveness.
    // That holds at a `ret` ONLY. At a call, and at a tail call or any other exit
    // (exitedge == 2), x0..x8 are the next function's arguments and are live
    // (kX18CallArgRegs, 2026-10-06; the x2 clobber in Temple Run 2's PhysX).
    //
    // No plan-version bump for that: it only ADDS liveness, so it can only take a
    // rename target away. A window the cached plan proved writes nothing stays
    // that way, the plan's set of effect windows is still a superset, and the
    // replay re-runs the (fixed) analysis live on every window in it. The two
    // counters a replay cross-checks (windows, x18 windows) are unchanged.
    const uint32_t EXIT_SET = (0x3FFu << 19) | (1u << 29) | (1u << 30) | (1u << 31);
    std::vector<uint32_t> livein(n, 0), liveout(n, 0);

    // backward liveness fixpoint
    bool changed = true; int guard = 0;
    while (changed && guard++ < (int)n + 6) {
        changed = false;
        for (size_t kk = n; kk-- > 0;) {
            uint32_t lo = fulllive[kk] ? 0xFFFFFFFFu
                        : exitedge[kk] == 1 ? EXIT_SET
                        : exitedge[kk] == 2 ? (EXIT_SET | macapk_x18_call_arg_regs()) : 0u;
            for (size_t s : succ[kk]) lo |= livein[s];
            uint32_t li = use[kk] | (lo & ~def[kk]);
            if (lo != liveout[kk]) { liveout[kk] = lo; changed = true; }
            if (li != livein[kk]) { livein[kk] = li; changed = true; }
        }
    }

    // SIMD (v0..v31) liveness -- same backward fixpoint, used to pick an x18
    // detour-thunk HOME register that is DEAD across the web (a function may
    // TOUCH all 32 SIMD regs yet have several dead across any short x18 range).
    // Exit live-out = callee-saved v8..v15 (their low 64 bits; the epilogue
    // reloads them so they're killed in-body and free there) -- mirrors the GPR
    // EXIT_SET rationale so a home reg is never a live return/callee value.
    // CRUCIALLY, an indirect branch (fulllive) is treated as an EXIT here, NOT
    // as "all 32 SIMD live-out" (which the GPR liveness uses to keep renaming
    // safe). We only thunk webs where x18 is DEAD before any indirect branch
    // (x18-reaches-indirect => webBad => never thunked), so the home reg is
    // never written at/after the branch; propagating all-SIMD-live back from a
    // jump table into x18's range would falsely mark every SIMD reg busy
    // (observed: a pure-integer UTF-8 decoder with a jump table reported
    // vint=0xffffffff and was wrongly abandoned).
    const uint32_t VEXIT_SET = 0xFF00u;  // v8..v15
    std::vector<uint32_t> vlivein(n, 0), vliveout(n, 0);
    changed = true; guard = 0;
    while (changed && guard++ < (int)n + 6) {
        changed = false;
        for (size_t kk = n; kk-- > 0;) {
            uint32_t lo = (exitedge[kk] || fulllive[kk]) ? VEXIT_SET : 0u;
            for (size_t s : succ[kk]) lo |= vlivein[s];
            uint32_t li = vuse[kk] | (lo & ~vdef[kk]);
            if (lo != vliveout[kk]) { vliveout[kk] = lo; changed = true; }
            if (li != vlivein[kk]) { vlivein[kk] = li; changed = true; }
        }
    }

    if (const char* dw = std::getenv("MACAPK_X18_DUMPWOFF")) {
        uint64_t c = strtoull(dw, nullptr, 0);
        for (size_t k = 0; k < n; ++k) {
            uint64_t wo = addr[k] - fstart;
            if (wo + 0x40 >= c && wo <= c + 0x40) {
                std::fprintf(stderr, "[x18-dump] wo=%#llx use=%08x def=%08x "
                    "livein=%08x liveout=%08x nsucc=%zu full=%d exit=%d\n",
                    (unsigned long long)wo, use[k], def[k], livein[k], liveout[k],
                    succ[k].size(), (int)fulllive[k], (int)exitedge[k]);
                for (size_t s : succ[k])
                    std::fprintf(stderr, "      -> wo=%#llx\n",
                                 (unsigned long long)(addr[s] - fstart));
            }
        }
    }

    std::vector<int> uf(n);
    for (size_t i = 0; i < n; ++i) uf[i] = (int)i;
    auto find = [&uf](int x) {
        while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x]; }
        return x;
    };
    auto uni = [&](int a, int b) { uf[find(a)] = find(b); };

    // ---- Build webs via REACHING DEFINITIONS for x18 (forward dataflow) ----
    // Liveness-based grouping would FUSE unrelated x18 values: at an indirect
    // branch we set live-out to all-registers (conservative for picking a free
    // reg), which injects fake x18-liveness that, in a large eh_frame-gap window
    // (FMOD compiled without unwind tables -> no FDEs -> hundreds of functions
    // merged), collapses every x18 value into one un-renameable mega-web.
    // Reaching-defs are immune: an indirect branch DEFINES nothing, so it can
    // never fuse two x18 values. A "web" = one x18 def-site plus every def/use
    // its value reaches.
    std::vector<int> defsite;
    std::vector<int> defbit(n, -1);
    for (size_t k = 0; k < n; ++k)
        if ((def[k] >> 18) & 1u) { defbit[k] = (int)defsite.size(); defsite.push_back((int)k); }
    size_t ND = defsite.size();
    size_t WW = (ND + 63) / 64; if (WW == 0) WW = 1;
    std::vector<uint64_t> rin(n * WW, 0), rout(n * WW, 0), tmp(WW, 0);
    std::vector<std::vector<int>> preds(n);
    for (size_t k = 0; k < n; ++k) for (int s : succ[k]) preds[s].push_back((int)k);
    {
        bool ch = true; int gd = 0;
        while (ch && gd++ < (int)n + 6) {
            ch = false;
            for (size_t k = 0; k < n; ++k) {
                for (size_t w = 0; w < WW; ++w) tmp[w] = 0;
                for (int p : preds[k]) for (size_t w = 0; w < WW; ++w) tmp[w] |= rout[p * WW + w];
                for (size_t w = 0; w < WW; ++w) rin[k * WW + w] = tmp[w];
                if (defbit[k] >= 0) {
                    for (size_t w = 0; w < WW; ++w) {
                        uint64_t v = (w == (size_t)(defbit[k] / 64))
                                   ? (1ull << (defbit[k] % 64)) : 0ull;
                        if (rout[k * WW + w] != v) { rout[k * WW + w] = v; ch = true; }
                    }
                } else {
                    for (size_t w = 0; w < WW; ++w)
                        if (rout[k * WW + w] != rin[k * WW + w]) {
                            rout[k * WW + w] = rin[k * WW + w]; ch = true;
                        }
                }
            }
        }
    }
    auto for_each_def_in = [&](size_t k, std::vector<uint64_t>& arr, auto fn) {
        for (size_t w = 0; w < WW; ++w) {
            uint64_t bits = arr[k * WW + w];
            while (bits) { int b = __builtin_ctzll(bits); bits &= bits - 1; fn(defsite[w * 64 + b]); }
        }
    };
    // Union each x18 USE with its reaching defs; a use with NO reaching def is an
    // x18 value defined OUTSIDE this window (live-in) -> unsafe to rename.
    std::unordered_map<int, bool> webBad;
    for (size_t k = 0; k < n; ++k) {
        if (!((use[k] >> 18) & 1u)) continue;
        bool hasdef = false;
        for_each_def_in(k, rin, [&](int d) { uni((int)k, d); hasdef = true; });
        if (!hasdef) webBad[find((int)k)] = true;
    }
    // x18 that REACHES an indirect branch flows to targets we can't see -> the
    // web is incomplete; disable it (leave as residual x18).
    for (size_t k = 0; k < n; ++k)
        if (fulllive[k])
            for_each_def_in(k, rin, [&](int d) { webBad[find(d)] = true; });
    // A web with ANY unreachable x18 member is unsafe to transform. Our static
    // reachability seeds from the window start + DIRECT branch/call targets, so
    // a x18 def reached ONLY via an indirect branch (a jump-table / Duff's-device
    // dispatch: `br xN` into a `mov x18,xM` chain) is marked !reach and would be
    // SKIPPED, while a fall-through-reachable USE of the same x18 value is
    // transformed -> the rename reg / thunk home is never initialized -> it reads
    // stale garbage -> crash (the Crossy libunity `str w0,[x18]` after a
    // computed-goto mov-x18 chain). Mark the whole web bad so BOTH the rename and
    // thunk paths leave every one of its members as residual x18 (self-consistent).
    for (size_t k = 0; k < n; ++k)
        if ((((use[k] | def[k]) >> 18) & 1u) && !reach[k])
            webBad[find((int)k)] = true;

    // Per-web interference (registers co-live with each web's x18), from liveness.
    std::unordered_map<int, uint32_t> webINT;
    for (size_t k = 0; k < n; ++k) {
        uint32_t around = use[k] | def[k] | livein[k] | liveout[k];
        if (!((around >> 18) & 1u)) continue;
        for_each_def_in(k, rin, [&](int d) { webINT[find(d)] |= around; });
        if (defbit[k] >= 0) webINT[find((int)k)] |= around;
    }

    // Per-web SIMD interference (webVINT) + call-crossing flag (webVCALL), used by
    // the detour-thunk lowering to pick a SIMD home dead across the web. Computed
    // over EVERY instruction where THIS web's x18 value is live (livein/liveout
    // bit 18, attributed via reaching defs), NOT the raw [lo,hi] address span --
    // so a non-contiguous web (x18 flowing across a branch over unrelated FP code)
    // is not falsely blocked by SIMD regs that live only in the skipped-over gap.
    // A home dead at every x18-live point is safe: between two x18 sites x18 is
    // live (bit 18 in livein), so those pass-through instructions are included and
    // any SIMD they touch is in webVINT.
    std::unordered_map<int, uint32_t> webVINT;
    std::unordered_set<int> webVCALL;
    for (size_t k = 0; k < n; ++k) {
        bool x18live = (((livein[k] | liveout[k]) >> 18) & 1u) != 0;
        bool x18here = (((use[k] | def[k]) >> 18) & 1u) != 0;
        if (!x18live && !x18here) continue;
        uint32_t varound = vuse[k] | vdef[k] | vlivein[k] | vliveout[k];
        bool iscall = (fkind[k] == 2);
        auto attribute = [&](int web) { webVINT[web] |= varound; if (iscall) webVCALL.insert(web); };
        for_each_def_in(k, rin, [&](int d) { attribute(find(d)); });
        if (defbit[k] >= 0) attribute(find((int)k));   // k itself starts a web
    }
    // Candidates: caller-saved temps x9..x17 first, then x8, then x2..x7. We
    // EXCLUDE x0/x1 (the only GP return-value registers) so a rename can never
    // clobber a return value -- no exit protection needed. Any candidate NOT in
    // the interference set is neither live nor used during x18's live range, so
    // it is safe. (Callee-saved regs aren't used: a window-merged function set
    // makes "does this function save xN" unreliable, and 16 caller-saved
    // candidates cover essentially every hot loop; the rare miss stays residual.)
    static const int kCaller[] = {9, 10, 11, 12, 13, 14, 15, 16, 17,
                                  8, 2, 3, 4, 5, 6, 7};
    // NOFREE FALLBACK (v1.0.564). When all 16 caller-saved candidates interfere
    // with x18's web (the ~50 libunity webs the old code left as residual x18 ->
    // XNU zeroes the reserved x18 -> a live pointer is corrupted -> SIGBUS: the
    // Subway PlayerLoop crash the v563 Swappy fix unmasked), fall back to a
    // callee-saved reg (x19..x28) that is SAFE to reuse. Safe iff (a) the window
    // is a CLEAN single compiler-emitted function (no resync gap: a merged
    // asm/no-FDE window can hold an inner function that uses a callee-saved reg
    // without itself saving it, so reuse there could clobber a live caller value),
    // (b) the function WRITES that reg somewhere in the window -- so per the AAPCS
    // its own prologue/epilogue already spill+restore the caller's value -- and
    // (c) the reg is DEAD across x18's web (not in the interference set), so a
    // value that neither feeds nor escapes the web can host x18 without disturbing
    // the function's own use of it. All three together make the substitution
    // invisible to both the caller and the function. saved_cs = callee-saved regs
    // the (clean) window writes.
    static const int kCalleeSaved[] = {19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
    uint32_t saved_cs = 0;
    if (!had_gap) for (size_t k = 0; k < n; ++k) saved_cs |= def[k];
    saved_cs &= 0x1ff80000u;  // bits 19..28
    std::unordered_map<int, int> webR;
    for (auto& e : webINT) {
        if (webBad.count(e.first) && webBad[e.first]) { webR[e.first] = -1; continue; }
        uint32_t inter = e.second & ~(1u << 18);
        int R = -1;
        for (int c : kCaller) if (!((inter >> c) & 1u)) { R = c; break; }
        if (R < 0 && !had_gap)
            for (int c : kCalleeSaved)
                if (((saved_cs >> c) & 1u) && !((inter >> c) & 1u)) { R = c; break; }
        webR[e.first] = R;  // -1 = no free register for this web
    }

    // Field-substitute x18->R in one instruction word and VERIFY via re-disasm
    // (the substituted word must decode to exactly the original op with x18->R and
    // nothing else). Returns true + the chosen word on success. Shared by the
    // GPR-rename path and the thunk-lowering path below.
    auto subst_verify = [&](uint32_t w, int R, uint32_t& chosen) -> bool {
        int flds[4]; int m = 0;
        for (int fld : {0, 5, 10, 16})
            if (((w >> fld) & 0x1fu) == 18u) flds[m++] = fld;
        if (m == 0) return false;
        cs_insn* a = nullptr; size_t na = cs_disasm(h, (const uint8_t*)&w, 4, 0, 0, &a);
        bool ok = false;
        if (na == 1) {
            char expect[192];
            macapk_reg_subst(a[0].op_str, 18, R, expect, sizeof expect);
            for (unsigned mask = 1; mask < (1u << m) && !ok; ++mask) {
                uint32_t cand = w;
                for (int j = 0; j < m; ++j)
                    if (mask & (1u << j))
                        cand = (cand & ~(0x1fu << flds[j])) | ((uint32_t)R << flds[j]);
                cs_insn* b = nullptr; size_t nb = cs_disasm(h, (const uint8_t*)&cand, 4, 0, 0, &b);
                if (nb == 1 && std::strcmp(a[0].mnemonic, b[0].mnemonic) == 0
                            && std::strcmp(b[0].op_str, expect) == 0) { chosen = cand; ok = true; }
                if (nb) cs_free(b, nb);
            }
        }
        if (na) cs_free(a, na);
        return ok;
    };

    // ---- THUNK-LOWER the nofree webs (webR==-1, not webBad) -------------------
    // These webs have x18 co-live with EVERY GPR, so there is no rename target.
    // Evict x18 to an unused callee-saved SIMD register (home H); a second unused
    // callee-saved SIMD reg (H2) saves the scratch GPR. Each x18 instruction is
    // replaced in place with `b <thunk>`; the thunk homes/uses x18 via the SIMD
    // pair and returns. All-or-nothing per web: if any instruction can't be
    // lowered (exclusive/indirect-branch/etc), the web stays residual so its
    // instructions remain a self-consistent (if x18-fragile) unit.
    std::unordered_map<int, std::vector<std::pair<uint8_t*, uint32_t>>> webPatches;
    std::vector<std::pair<uint8_t*, uint32_t>> thunkPatches;
    std::unordered_set<int> thunkedWebs;
    bool any_nofree = false;
    static const bool s_thunk_on = []{
        const char* e = std::getenv("MACAPK_X18_THUNK"); return !e || e[0] != '0';
    }();
    if (tpool && s_thunk_on) {
        // Collect the reachable x18 instruction indices for each nofree web.
        std::unordered_map<int, std::vector<size_t>> nofreeWeb;
        for (size_t k = 0; k < n; ++k) {
            if (!(((use[k] | def[k]) >> 18) & 1u) || !reach[k]) continue;
            int web = find((int)k);
            if (webBad.count(web) && webBad[web]) continue;   // incomplete web
            auto wit = webR.find(web);
            if (wit != webR.end() && wit->second >= 0) continue;  // GPR-renameable
            nofreeWeb[web].push_back(k);
        }
        for (auto& we : nofreeWeb) {
            int web = we.first;
            // ONE SIMD home reg H, DEAD across the web (webVINT bit clear --
            // computed over exactly the instructions where this web's x18 is
            // live). x18's value lives in H's LANE 0 (persists between the web's
            // thunks); the per-thunk scratch GPR is stashed in H's LANE 1 (INS/
            // UMOV touch only the named lane, so the two never interfere). XNU
            // never zeroes SIMD, so H survives preemption/faults. Prefer
            // callee-saved d8..d15 (also survive a bl -- though a live x18 web
            // never spans one); use caller-saved (v0..v7,v16..v31) only when the
            // web does not cross a call. One dead SIMD reg (vs two) clears the
            // maximally-register-pressured NEON webs that still keep a lane free.
            uint32_t vint = webVINT.count(web) ? webVINT[web] : 0xFFFFFFFFu;
            bool spans_call = webVCALL.count(web) != 0;
            int H = -1;
            for (int v = 8; v <= 15 && H < 0; ++v) if (!((vint >> v) & 1u)) H = v;
            if (H < 0 && !spans_call) {
                static const int kCallerV[] = {16,17,18,19,20,21,22,23,24,25,26,27,
                    28,29,30,31,0,1,2,3,4,5,6,7};
                for (int v : kCallerV) if (!((vint >> v) & 1u)) { H = v; break; }
            }
            if (H < 0) {   // no SIMD reg dead across the web
                any_nofree = true;
                if (dbg) std::fprintf(stderr, "[x18-thunk] web@%#llx ABANDON: no dead SIMD home "
                    "(vint=%08x spans_call=%d)\n",
                    (unsigned long long)addr[we.second[0]], vint, (int)spans_call);
                continue;
            }
            std::vector<std::pair<uint8_t*, uint32_t>> pending;
            size_t pool_save = *tpool->used;
            bool web_ok = true;
            const char* fail_reason = "?"; uint64_t fail_at = 0;
            for (size_t k : we.second) {
                uint32_t w = iword[k];
                uint64_t A = addr[k];
                bool rd18 = (use[k] >> 18) & 1u;
                bool wr18 = (def[k] >> 18) & 1u;
                // scratch GPR S: any x0..x17 not otherwise named by this insn.
                uint32_t other = (use[k] | def[k]) & ~(1u << 18);
                int S = -1;
                for (int c = 0; c <= 17; ++c) if (c != 18 && !((other >> c) & 1u)) { S = c; break; }
                if (S < 0) { web_ok = false; fail_reason = "no-scratch"; fail_at = A; break; }
                // classify the instruction
                cs_insn* ci = nullptr; size_t nci = cs_disasm(h, (const uint8_t*)&w, 4, A, 0, &ci);
                if (nci != 1) { web_ok = false; fail_reason = "decode"; fail_at = A; if (ci) cs_free(ci, nci); break; }
                unsigned id = ci[0].id;
                const char* mn = ci[0].mnemonic;
                cs_detail* d2 = ci[0].detail;
                // REFUSALS (leave the whole web residual):
                //  - atomics/exclusives (moving one half breaks the monitor pair)
                //  - x18 as an indirect branch/call target (br/blr x18)
                //  - anything writing sp (our thunk assumes sp is stable)
                bool refuse = false;
                if (mn[0]=='l' && (!std::strncmp(mn,"ldxr",4)||!std::strncmp(mn,"ldaxr",5)||
                                   !std::strncmp(mn,"ldxp",4)||!std::strncmp(mn,"ldaxp",5))) refuse = true;
                if (mn[0]=='s' && (!std::strncmp(mn,"stxr",4)||!std::strncmp(mn,"stlxr",5)||
                                   !std::strncmp(mn,"stxp",4)||!std::strncmp(mn,"stlxp",5)||
                                   !std::strncmp(mn,"stlxrb",6)||!std::strncmp(mn,"stxrb",5)||
                                   !std::strncmp(mn,"stlxrh",6)||!std::strncmp(mn,"stxrh",5))) refuse = true;
                if (id==ARM64_INS_BR || id==ARM64_INS_BLR) refuse = true;
                if ((def[k] >> 31) & 1u) refuse = true;   // writes sp
                bool is_adr  = (id==ARM64_INS_ADR || id==ARM64_INS_ADRP);
                bool is_cbz  = (id==ARM64_INS_CBZ || id==ARM64_INS_CBNZ);
                bool is_tbz  = (id==ARM64_INS_TBZ || id==ARM64_INS_TBNZ);
                // ldr-literal (x18 = literal load, no base register) can't relocate
                if (id==ARM64_INS_LDR && d2 && d2->arm64.op_count>=2 &&
                    d2->arm64.operands[1].type==ARM64_OP_IMM) refuse = true;
                uint64_t brT = 0; int tbBit = 0; uint64_t adrV = 0;
                if (!refuse && (is_cbz || is_tbz)) {
                    int ti = is_tbz ? 2 : 1;
                    if (d2 && d2->arm64.op_count > ti &&
                        d2->arm64.operands[ti].type==ARM64_OP_IMM)
                        brT = (uint64_t)d2->arm64.operands[ti].imm;
                    else refuse = true;
                    if (is_tbz) tbBit = (d2 && d2->arm64.op_count>1) ? (int)d2->arm64.operands[1].imm : 0;
                }
                if (!refuse && is_adr) {
                    if (d2 && d2->arm64.op_count>=2 && d2->arm64.operands[1].type==ARM64_OP_IMM)
                        adrV = (uint64_t)d2->arm64.operands[1].imm;
                    else refuse = true;
                }
                bool w32 = (ci[0].op_str[0]=='w');   // x18 operand width (w vs x)
                static char s_failmn[24];
                if (refuse) { std::snprintf(s_failmn, sizeof s_failmn, "refuse:%s", mn); }
                if (nci) cs_free(ci, nci);
                if (refuse) { web_ok = false; fail_reason = s_failmn; fail_at = A; break; }

                // Emit the thunk into the pool. x18's value is homed in H.d[0]
                // (persists between the web's thunks); the scratch GPR S is
                // stashed in H.d[1] for the duration of THIS thunk. INS/UMOV
                // touch only the named lane, so lane 0 (x18) is never disturbed
                // by the lane-1 save/restore. Layout depends on the class.
                size_t off = *tpool->used;
                uint64_t thunk_pc = (uint64_t)(tpool->base + off);
                std::vector<uint32_t> body;
                body.push_back(enc_ins_d(H, 1, S));               // save scratch S -> H.d[1]
                if (is_cbz || is_tbz) {
                    body.push_back(enc_umov_d(S, H, 0));          // S = x18 value (H.d[0])
                    // cond-branch on S: taken -> restore+b T, else restore+b A+4.
                    // words: [cond S, TAKEN][restore][b A+4][TAKEN: restore][b T]
                    size_t condIdx = body.size();
                    body.push_back(0);                            // cond (fill below)
                    body.push_back(enc_umov_d(S, H, 1));          // not-taken restore
                    body.push_back(0);                            // b A+4 (fill)
                    size_t takenIdx = body.size();
                    body.push_back(enc_umov_d(S, H, 1));          // taken restore
                    body.push_back(0);                            // b T (fill)
                    uint64_t condPC = thunk_pc + condIdx*4;
                    uint64_t takenPC = thunk_pc + takenIdx*4;
                    bool nz = (id==ARM64_INS_CBNZ || id==ARM64_INS_TBNZ);
                    if (is_cbz) body[condIdx] = enc_cbz(S, !w32, nz, condPC, takenPC);
                    else        body[condIdx] = enc_tbz(S, tbBit, nz, condPC, takenPC);
                    body[condIdx+2] = enc_b(thunk_pc + (condIdx+2)*4, A + 4);
                    body[takenIdx+1] = enc_b(thunk_pc + (takenIdx+1)*4, brT);
                } else if (is_adr) {
                    // adrV is an ABSOLUTE runtime address (base_ + file offset),
                    // so which halfwords are zero changes with ASLR from launch
                    // to launch. Emitting movk only for the nonzero ones would
                    // therefore make the thunk body's LENGTH vary per launch,
                    // which shifts every later thunk's pool offset and makes the
                    // pool layout non-reproducible. Always emit the full
                    // movz+movk*3 so the layout is a pure function of the guest
                    // bytes -- that is what lets the v2 plan cache replay and be
                    // verifiable as byte-identical (debug/x18_plan_verify.sh).
                    // Cost is at most 3 extra words on the ADR thunks only.
                    uint16_t p0=adrV, p1=adrV>>16, p2=adrV>>32, p3=adrV>>48;
                    body.push_back(enc_movz(S, p0, 0));
                    body.push_back(enc_movk(S, p1, 1));
                    body.push_back(enc_movk(S, p2, 2));
                    body.push_back(enc_movk(S, p3, 3));
                    body.push_back(enc_ins_d(H, 0, S));           // H.d[0] = target
                    body.push_back(enc_umov_d(S, H, 1));          // restore S
                    body.push_back(enc_b(0, 0));                  // b A+4 (fill)
                    body.back() = enc_b(thunk_pc + (body.size()-1)*4, A + 4);
                } else {
                    // plain / writeback op
                    uint32_t chosen = 0;
                    if (!subst_verify(w, S, chosen)) { web_ok = false; fail_reason = "no-subst"; fail_at = A; break; }
                    if (rd18) body.push_back(enc_umov_d(S, H, 0)); // S = x18 value (H.d[0])
                    body.push_back(chosen);                        // the op, x18->S
                    if (wr18) body.push_back(enc_ins_d(H, 0, S));  // H.d[0] = result
                    body.push_back(enc_umov_d(S, H, 1));           // restore S (H.d[1])
                    body.push_back(enc_b(0,0));
                    body.back() = enc_b(thunk_pc + (body.size()-1)*4, A + 4);
                }
                // capacity + branch-range checks
                size_t need = body.size()*4;
                int64_t bdist = (int64_t)thunk_pc - (int64_t)A;
                if (off + need > tpool->cap) { web_ok = false; fail_reason = "pool-full"; fail_at = A; break; }
                if (bdist < -(120<<20) || bdist > (120<<20)) { web_ok = false; fail_reason = "b-range"; fail_at = A; break; }
                std::memcpy(tpool->base + off, body.data(), need);
                *tpool->used = off + need;
                pending.emplace_back(reinterpret_cast<uint8_t*>(A), enc_b(A, thunk_pc));
            }
            if (web_ok && !pending.empty()) {
                for (auto& pr : pending) thunkPatches.push_back(pr);
                thunkedWebs.insert(web);
                if (dbg) std::fprintf(stderr,
                    "[x18-thunk] web@%#llx lowered %zu insn(s) home=v%d (x18=d[0] scratch=d[1])\n",
                    (unsigned long long)addr[we.second[0]], pending.size(), H);
            } else {
                *tpool->used = pool_save;   // roll back this web's thunks
                any_nofree = true;
                if (dbg) std::fprintf(stderr, "[x18-thunk] web@%#llx ABANDON: %s @%#llx (%zu insns)\n",
                    (unsigned long long)addr[we.second[0]], fail_reason,
                    (unsigned long long)fail_at, we.second.size());
            }
        }
    }

    // Verify + build every x18 instruction, grouped by web. A verify failure or
    for (size_t k = 0; k < n; ++k) {
        if (!(((use[k] | def[k]) >> 18) & 1u)) continue;
        if (!reach[k]) continue;  // embedded data, not executed code -> never rewrite
        int web = find((int)k);
        if (thunkedWebs.count(web)) continue;  // lowered via detour thunks above
        auto wit = webR.find(web);
        int R = (wit != webR.end()) ? wit->second : -1;  // -1 = orphan/disabled web
        if (R < 0) {
            any_nofree = true;
            if (dbg)
                std::fprintf(stderr, "[x18-dbg] no-reg-web @%#llx INT=%08x\n",
                    (unsigned long long)addr[k], webINT[web] & ~(1u << 18));
            continue;
        }
        uint8_t* loc = reinterpret_cast<uint8_t*>(addr[k]);
        uint32_t w; std::memcpy(&w, loc, 4);
        uint32_t chosen = 0;
        bool ok = subst_verify(w, R, chosen);
        if (!ok) { webR[web] = -1; any_nofree = true;   // disable this web
            if (dbg) std::fprintf(stderr, "[x18-dbg] no-subset @%#llx x18->x%d\n",
                                  (unsigned long long)addr[k], R);
            continue; }
        if (dbg)
            std::fprintf(stderr, "[x18-dbg] rewrote @%#llx x18->x%d INT=%08x\n",
                         (unsigned long long)addr[k], R, webINT[web] & ~(1u << 18));
        webPatches[web].emplace_back(loc, chosen);
    }

    // Gather all rewrites, sorted by address (stable order for the bisect knobs
    // and a clean commit). Thunk `b`-patches join the same list.
    std::vector<std::pair<uint8_t*, uint32_t>> all;
    for (auto& wp : webPatches) {
        if (webR[wp.first] < 0) continue;  // web disabled after some collection
        for (const auto& pr : wp.second) all.push_back(pr);
    }
    res.thunked = (int)thunkPatches.size();
    for (const auto& pr : thunkPatches) all.push_back(pr);
    if (all.empty()) { res.status = any_nofree ? 2 : 0; return res; }
    std::sort(all.begin(), all.end());

    // Bisect knobs (diagnostic; never set in production): MACAPK_X18_LIMIT=N
    // rewrites only the first N committing windows; MACAPK_X18_LIMIT_INSN=M, on
    // the boundary window (#N), commits only its first M instructions (by addr).
    static long s_lim = -2, s_cnt = 0, s_minsn = -2;
    if (s_lim == -2) { const char* e = std::getenv("MACAPK_X18_LIMIT"); s_lim = e ? atol(e) : -1; }
    if (s_minsn == -2) { const char* e = std::getenv("MACAPK_X18_LIMIT_INSN"); s_minsn = e ? atol(e) : -1; }
    if (s_lim >= 0) {
        if (s_cnt > s_lim) { res.status = 0; return res; }
        if (s_cnt == s_lim && s_minsn < 0) { res.status = 0; return res; }
    }
    long icap = (s_lim >= 0 && s_cnt == s_lim) ? s_minsn : -1;

    int committed = 0;
    for (const auto& pr : all) {
        if (icap >= 0 && committed >= icap) break;
        std::memcpy(pr.first, &pr.second, 4);
        ++committed;
    }
    if (committed == 0) { res.status = any_nofree ? 2 : 0; return res; }
    ++s_cnt;
    if (dbg)
        std::fprintf(stderr, "[x18-commit] window #%ld first=%#llx committed=%d "
                     "lastaddr=%#llx\n", s_cnt, (unsigned long long)addr[0],
                     committed, (unsigned long long)all[committed - 1].first);
    sys_icache_invalidate(reinterpret_cast<void*>(fstart), flen);
    res.status = 1; res.insns_rewritten = committed;
    return res;
}

// v1.0.609: x18-scan "clean decision" cache. install_x18_generic_rewrite below
// disassembles ~all 652506 functions of a 322MB lib (Capstone detail mode) every
// launch -- ~40s -- and for MCPE emits ZERO rewrites. The rewrite decision is a
// pure function of the .so's exec bytes, so we cache the decision keyed on a
// fast content hash of the exec segment. We ONLY cache the CLEAN case (the pass
// produced zero .text patches AND zero thunks); on a clean cache hit we SKIP the
// scan entirely, which is byte-identical to running it (it would write nothing).
//
// v1.0.762: the DIRTY plan is cached too. The CLEAN-only cache left every lib
// that actually rewrites re-scanning from scratch on EVERY launch, cold or warm
// (libunity 366 x18-funcs of 52576 -> 2.6s; libcocos2dcpp 202 of 53k -> 1.7s),
// which measured at 57-61% of warm boot-to-first-frame for Unity / cocos2d-x
// apps. The DIRTY plan does NOT cache emitted CODE BYTES -- caching executable
// patches would mean a stale replay silently corrupts the guest, and the thunk
// pool's `b` encodings are position-dependent (both the pool and the image move
// under ASLR every launch). Instead it caches the ONE thing the 2.6s actually
// buys: the SET OF FUNCTION WINDOWS that produce any effect at all. On a dirty
// hit the loop still runs, and macapk_x18_rewrite_one still runs LIVE on every
// window in that set (identical analysis, identical encoders, identical commit
// order, identical thunk-pool layout) -- we only skip the windows the previous
// scan proved write NOTHING.
//
// Why skipping those is byte-identical, not "probably fine": a window is put in
// the set iff the pass reported status==1 (it committed .text patches) or
// thunked>0 (it committed pool bytes). Every other outcome (status 0 = no real
// x18 use, status 2 = x18 present but no rename target and no lowerable web)
// returns WITHOUT writing a single byte -- abandoned webs roll the pool cursor
// back to pool_save, so they leave no residue either. So re-running a non-set
// window can only reproduce "no writes"; skipping it produces the same memory
// state in less time. Proven empirically by debug/x18_plan_verify.sh, which
// diffs the canonicalized post-pass .text windows AND thunk pool of a fresh
// full scan against a cache replay.
//
// Libs whose pass is CLEAN still take the v1 path (skip the scan entirely), so
// MCPE is unaffected. The pinned Subway all-SIMD-live residual is untouched
// either way.
// Bump kX18PlanVersion on ANY change to the pass logic so a stale verdict can
// never skip a scan that now WOULD rewrite.
static constexpr uint32_t kX18PlanMagic   = 0x58313850u;  // 'X18P'
static constexpr uint32_t kX18PlanVersion = 2;
// 3 (2026-09-29): a plan written by the pass with MACAPK_X18_EXEC_ONLY ACTIVE on a
// library where it CHANGES the pass -- some byte in [first FDE start, end of the
// PF_X segment) is positively inside a non-executable section, so a window is cut
// or skipped. Per LIBRARY, never global: a library with no data there runs the
// pass byte for byte as version 2 did, so it reads and writes version 2 and keeps
// its existing plan (libminecraftpe.so has no data in its windows and must not
// pay its ~40 s cold scan again just because Jetpack 2's library changed). A v2
// plan on an affected library is rejected (its counts and its data-only effect
// window describe the old whole-window pass; a replay was correct but warned
// "replay disagrees with plan" on every launch). MACAPK_X18_EXEC_ONLY=0 is the old
// pass, so it uses version 2 on every library and never sees a v3 plan.
static constexpr uint32_t kX18PlanVersionExecOnly = 3;
// Serialized counters, in file order. Kept as a named list so the summary log
// line can be reproduced verbatim from a cache hit (log-grepping tools in
// debug/ key on it).
enum {
    kX18CntTotal = 0, kX18CntX18, kX18CntRewritten, kX18CntInsn,
    kX18CntNofree, kX18CntVerify, kX18CntThunkFns, kX18CntThunkInsns,
    kX18CntThunkBytes, kX18CntN
};
// ---- BEGIN plan-key hash block (debug/w7exechash_gate.sh compiles this verbatim) ----
// The content hash behind BOTH plan caches' keys: the x18 plan (hashes the first
// PF_X PT_LOAD as install_x18_generic_rewrite finds it) and the canary plan
// (hashes every PROT_EXEC segment as apply_stack_canary_neutralization finds
// it). The gate extracts every line from BEGIN to END and compiles it into its
// harness, so what it tests IS this source. Keep the block self-contained:
// <cstdint>, <cstddef>, <cstdlib> and <cstring> only.
//
// MACAPK_PLAN_KEY_FASTHASH (default ON, 2026-09-26). The legacy hash below is ONE
// serial chain: every 8-byte word waits on the previous word's multiply and
// shift-xor, about 6 cycles per word whatever the memory system can deliver.
// Both keys are computed on EVERY launch, cold or warm, over the whole exec
// segment, so on libminecraftpe (284 MB of exec) it cost 70.6 ms per key, 141 ms
// per launch, before either cache could answer (M2, pages resident, min of 7
// reps; memory floor for one pass over those bytes: 6.2 ms; the gate re-times
// every installed library, docs/plan-key-exec-hash_2026-09-26.md section 2).
// macapk_exec_hash_w8 runs the SAME per-word step on eight independent lanes,
// so eight multiplies are in flight at once: 13.2 ms per key on the same bytes.
//
// Why a faster key is exactly as safe, which is the only property that matters
// here (a plan replayed on different .text is silent memory corruption):
//   * the per-word step h' = xorshift29((h ^ w) * P) is a BIJECTION in w for a
//     fixed h and in h for a fixed w (xor, multiply by the odd FNV prime, and
//     x ^ (x >> 29) are each invertible mod 2^64). Legacy and w8 share it.
//   * so a change confined to ONE aligned 8-byte word (every one-byte change,
//     every single 4-byte instruction) CANNOT collide, in either hash: in w8 it
//     changes exactly one lane's state, every later step on that lane and the
//     fold into h are bijections of the differing value, and fmix64 is a
//     bijection. That is a proof, not a probability, and the gate exercises it
//     at every byte offset of the boundary lengths plus real libraries.
//   * for wider differences it is a 64-bit key like the legacy one, which the
//     plan header ALSO pins to the exact byte length (and the canary key to
//     every segment's offset and length, the PLT relocations, the knobs and the
//     shim's LC_UUID), under a per-app, per-version, per-soname path. Nothing
//     is keyed on file metadata: the input is the bytes the pass will read.
//   * w8 ends in MurmurHash3's fmix64 avalanche (public domain, smhasher), which
//     the legacy chain lacks, so it is not weaker there either.
//
// ONE-TIME COST of turning it on: the on-disk version word of both plan formats
// carries kPlanKeyW8Tag, so no legacy plan can ever match a w8 key, whatever the
// hash values. Canary plans are rewritten after every shim rebuild anyway (their
// key includes the shim's LC_UUID), so they cost nothing extra; every x18 plan
// misses ONCE, i.e. one full x18 scan per Stage-6 library on each app's next
// launch, then hits again. MACAPK_PLAN_KEY_FASTHASH=0 restores the exact
// pre-change keys (legacy hash, untagged versions), so plans written by the
// previous build hit again.
//
// NOT DONE, and why: memoizing one hash for both keys within a load. The canary
// key hashes .text BEFORE the canary pass; the x18 key hashes it after the
// canary, svc->BRK, MIDR, raw-syscall and engine passes in load_common have
// written it (504,768 words on libminecraftpe). Same bytes on neither side, so a
// shared value would key the x18 plan on the wrong input. The gate proves this
// on the installed apps' own plans (docs/plan-key-exec-hash_2026-09-26.md).
static uint64_t macapk_x18_exec_hash(const uint8_t* p, size_t n) {
    // fast FNV-1a-style word-wise hash (not cryptographic; paired with the byte
    // length + version constant, collisions on real builds are negligible).
    uint64_t h = 1469598103934665603ull;
    size_t nw = n / 8;
    const uint64_t* w = reinterpret_cast<const uint64_t*>(p);
    for (size_t i = 0; i < nw; ++i) { h ^= w[i]; h *= 1099511628211ull; h ^= h >> 29; }
    for (size_t i = nw * 8; i < n; ++i) { h ^= p[i]; h *= 1099511628211ull; }
    return h;
}
static inline uint64_t macapk_plan_key_step(uint64_t h, uint64_t w) {
    h ^= w; h *= 1099511628211ull; h ^= h >> 29;   // the legacy per-word step
    return h;
}
static uint64_t macapk_exec_hash_w8(const uint8_t* p, size_t n) {
    // Lane k takes words k, k+8, k+16, ... of the range. Seeds differ per lane so
    // the lanes are not interchangeable. Loads go through memcpy: the canary key
    // passes base_ + p_vaddr, which ELF only promises is 4-byte aligned.
    uint64_t a[8];
    for (int k = 0; k < 8; ++k)
        a[k] = 1469598103934665603ull ^ (uint64_t(k + 1) * 0x9E3779B97F4A7C15ull);
    size_t i = 0;
    for (; i + 64 <= n; i += 64) {
        for (int k = 0; k < 8; ++k) {
            uint64_t w;
            std::memcpy(&w, p + i + 8 * k, 8);   // host is little-endian, as legacy
            a[k] = macapk_plan_key_step(a[k], w);
        }
    }
    // Up to seven whole words left: word j still goes to lane (j mod 8), because
    // i is a multiple of 64 here, so the one-word argument above holds for them.
    for (int k = 0; i + 8 <= n; i += 8, ++k) {
        uint64_t w;
        std::memcpy(&w, p + i, 8);
        a[k] = macapk_plan_key_step(a[k], w);
    }
    uint64_t h = 1469598103934665603ull;
    for (; i < n; ++i) { h ^= p[i]; h *= 1099511628211ull; }   // byte tail, as legacy
    for (int k = 0; k < 8; ++k) h = macapk_plan_key_step(h, a[k]);
    h = macapk_plan_key_step(h, uint64_t(n));
    h ^= h >> 33; h *= 0xff51afd7ed558ccdull;                   // MurmurHash3 fmix64
    h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ull;
    h ^= h >> 33;
    return h;
}
// On-disk version bit for a plan whose key was hashed with macapk_exec_hash_w8.
// It sits above both formats' own version numbers, so bumping kX18PlanVersion or
// kCanaryPlanVersion later still works in either mode.
static constexpr uint32_t kPlanKeyW8Tag = 0x00010000u;
static bool macapk_plan_key_fasthash() {
    // Read once per process: one load must key its read and its write the same way.
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_PLAN_KEY_FASTHASH");
        return !(e && e[0] == '0');
    }();
    return on;
}
static uint64_t macapk_plan_exec_hash(const uint8_t* p, size_t n) {
    return macapk_plan_key_fasthash() ? macapk_exec_hash_w8(p, n)
                                      : macapk_x18_exec_hash(p, n);
}
static uint32_t macapk_plan_disk_version(uint32_t v) {
    return macapk_plan_key_fasthash() ? (v | kPlanKeyW8Tag) : v;
}
// ---- END plan-key hash block ----
static std::string macapk_x18_plan_path(const char* soname) {
    const char* root = __macapk_get_android_root();
    const char* sub  = __macapk_get_data_subdir();
    if (!root || !sub || !soname) return std::string();
    const char* base = std::strrchr(soname, '/');
    base = base ? base + 1 : soname;
    return std::string(root) + "/data/data/" + sub + "/.x18cache/" + base + ".x18plan";
}

// ---- MACAPK_X18_PLAN_DUMP: base-independent evidence for the differential -----
// The harness (debug/x18_plan_verify.sh) has to compare the post-pass memory of
// two SEPARATE processes, and the image base + thunk-pool base differ between
// them (ASLR). A raw byte dump would therefore differ for legitimate reasons, so
// every address-bearing word is canonicalized to an offset from the image base
// or the pool base. The DECODED target of a branch is the semantically correct
// address in either run, so target-minus-base is stable even when the pool lands
// at a different distance from the image.
//
// Canonicalized forms:
//   B>P+<off>  unconditional b whose target is inside the thunk pool
//   B>I<+/-off> unconditional b whose target is outside the pool (image-relative)
//   MOV><delta> a movz+movk* absolute-address materialization (pool only, from
//              the ADR/ADRP thunk), printed image-relative
// Everything else prints as its raw 32-bit word. Rename patches only change a
// 5-bit register field, so they are base-independent already.
static void macapk_x18_canon_word(std::string& out, uint32_t w, uint64_t pc,
                                  uint64_t img_lo, uint64_t pool_lo, uint64_t pool_hi) {
    char buf[64];
    if ((w & 0xFC000000u) == 0x14000000u) {         // b <label>
        int64_t imm = (int64_t)(int32_t)(w << 6) >> 6;   // sign-extend imm26
        uint64_t tgt = pc + (uint64_t)(imm * 4);
        if (pool_hi > pool_lo && tgt >= pool_lo && tgt < pool_hi)
            std::snprintf(buf, sizeof buf, "B>P+%llx",
                          (unsigned long long)(tgt - pool_lo));
        else
            std::snprintf(buf, sizeof buf, "B>I%+lld", (long long)(tgt - img_lo));
        out += buf;
        return;
    }
    std::snprintf(buf, sizeof buf, "%08x", w);
    out += buf;
}
// Returns the number of words consumed (>=1). Only used for the pool, where a
// movz/movk run is always the ADR materialization.
static size_t macapk_x18_canon_pool_at(std::string& out, const uint32_t* p, size_t n,
                                       size_t i, uint64_t pool_base, uint64_t img_lo,
                                       uint64_t pool_hi) {
    uint32_t w = p[i];
    if ((w & 0xFF800000u) == 0xD2800000u) {          // movz Xd,#imm,lsl #hw*16
        int rd = (int)(w & 0x1Fu);
        uint64_t val = (uint64_t)((w >> 5) & 0xFFFFu) << (((w >> 21) & 3u) * 16);
        size_t j = i + 1;
        while (j < n && (p[j] & 0xFF800000u) == 0xF2800000u && (int)(p[j] & 0x1Fu) == rd) {
            val |= (uint64_t)((p[j] >> 5) & 0xFFFFu) << (((p[j] >> 21) & 3u) * 16);
            ++j;
        }
        // A LONE movz is a rewritten guest `movz x18,#imm` carried into a thunk
        // body -- its immediate is a constant, not an address, so canonicalizing
        // it image-relative would inject a false per-launch difference. Only the
        // ADR/ADRP materialization (movz + movk*3, always emitted in full) is an
        // address, and it always has at least one movk.
        if (j > i + 1) {
            char buf[64];
            std::snprintf(buf, sizeof buf, "MOV>x%d,I%+lld", rd, (long long)(val - img_lo));
            out += buf;
            return j - i;
        }
    }
    macapk_x18_canon_word(out, w, pool_base + i * 4, img_lo, pool_base, pool_hi);
    return 1;
}
// Write the canonicalized post-pass state: every function window the pass
// touched (so every .text patch is covered by construction) plus the whole live
// thunk pool. Two runs that agree here wrote byte-identical code.
static void macapk_x18_write_dump(const char* dir, const char* soname,
                                  uint64_t exec_lo, uint64_t img_lo,
                                  const std::vector<std::pair<uint64_t, size_t>>& wins,
                                  const uint8_t* pool, size_t pool_used,
                                  const int64_t* counts) {
    if (!dir || !*dir) return;
    const char* base = soname ? std::strrchr(soname, '/') : nullptr;
    base = base ? base + 1 : (soname ? soname : "image");
    mkdir(dir, 0755);
    std::string path = std::string(dir) + "/" + base + ".x18dump";
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    std::fprintf(f, "x18dump v2 so=%s\n", base);
    std::fprintf(f, "counts total=%lld x18=%lld rewritten=%lld insns=%lld "
                    "nofree=%lld verify=%lld thunkfns=%lld thunkinsns=%lld pool=%lld\n",
                 (long long)counts[kX18CntTotal], (long long)counts[kX18CntX18],
                 (long long)counts[kX18CntRewritten], (long long)counts[kX18CntInsn],
                 (long long)counts[kX18CntNofree], (long long)counts[kX18CntVerify],
                 (long long)counts[kX18CntThunkFns], (long long)counts[kX18CntThunkInsns],
                 (long long)counts[kX18CntThunkBytes]);
    std::fprintf(f, "windows %zu\n", wins.size());
    const uint64_t pool_lo = reinterpret_cast<uint64_t>(pool);
    const uint64_t pool_hi = pool_lo + pool_used;
    std::string line;
    for (const auto& wn : wins) {
        const uint32_t* p = reinterpret_cast<const uint32_t*>(exec_lo + wn.first);
        size_t nw = wn.second / 4;
        std::fprintf(f, "W %llx %zu\n", (unsigned long long)wn.first, nw);
        for (size_t i = 0; i < nw; ++i) {
            line.clear();
            macapk_x18_canon_word(line, p[i], exec_lo + wn.first + i * 4,
                                  img_lo, pool_lo, pool_hi);
            std::fprintf(f, " %s\n", line.c_str());
        }
    }
    std::fprintf(f, "pool %zu\n", pool_used);
    if (pool && pool_used >= 4) {
        const uint32_t* p = reinterpret_cast<const uint32_t*>(pool);
        size_t nw = pool_used / 4;
        for (size_t i = 0; i < nw; ) {
            line.clear();
            i += macapk_x18_canon_pool_at(line, p, nw, i, pool_lo, img_lo, pool_hi);
            std::fprintf(f, " %s\n", line.c_str());
        }
    }
    std::fclose(f);
    std::fprintf(stderr, "[x18-generic] %s: wrote plan dump %s\n", base, path.c_str());
}

static bool macapk_x18_exec_only();   // MACAPK_X18_EXEC_ONLY, defined next to macapk_svc_brk_exec_only

void Image::install_x18_generic_rewrite() {
    // DEFAULT-ON (opt out with MACAPK_X18_REWRITE_GENERIC=0). This is the
    // app-agnostic fix for the arm64 x18 platform-register trap (XNU zeroes x18
    // on every EL0 return; Android guest code uses it as a scratch GPR). Verified
    // to fix Temple Run 2 (renders, 0 traps) with ZERO regression on Crossy
    // (renders its grass-green menu) / OpenGD / the protected set.
    //
    // ⚠ CORRECTED v1.0.1020: DO NOT DEFAULT THIS OFF. The note below argued the
    // pass was "arguably REDUNDANT" because the kernel sdk<13 LC_BUILD_VERSION
    // gate handled x18 process-wide. THAT GATE WAS DELETED AT v1.0.1018, so the
    // premise is gone -- and on a host below macOS 26.4 the entitlement path does
    // not exist either (XNU does not read TPIDR_EL0 bit 48 before 26.4), which
    // makes THIS PASS THE ONLY x18 PROTECTION THERE. Reading the stale note and
    // acting on it would remove the last line of defence on exactly the hosts
    // that need it most. Historical reasoning follows, kept because (a) and (b)
    // are still valid on their own terms.
    //
    // NOTE (v1.0.588, PREMISE NOW FALSE): this pass was called arguably REDUNDANT
    // because kernel preserve_x18 (LC_BUILD_VERSION sdk<13.0, v581, DELETED
    // v1.0.1018) handled x18 process-wide -- and a v588
    // experiment defaulting it OFF passed the protected sweep 7/7 (incl.
    // Crossy/Unity). BUT default-off was NOT shipped: (a) it does NOT fix MCPE
    // (the renoir glyph crash at file 0x7c1f2bc reproduces with the rewrite OFF,
    // so the v588 "x18 corrupts MCPE" hypothesis was WRONG -- the earlier
    // "stable" x18-off runs merely STALLED before reaching the renoir stage), and
    // (b) the pinned Subway all-SIMD-live residual (un-swept) makes a blanket
    // default-off risky without re-testing sustained Unity gameplay on kernel
    // preserve_x18 alone. Deferred as a future cleanup. See
    // docs/mcpe-v8-x18-rewrite-torn-pointer_2026-07-06.md + the v588 Current_State
    // entry.
    const char* g = std::getenv("MACAPK_X18_REWRITE_GENERIC");
    if (g && g[0] == '0') return;            // explicit opt-out only
    if (!base_) return;

    // (1) executable segment range.
    uint64_t exec_lo = 0, exec_hi = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type == 1 /*PT_LOAD*/ && (ph.p_flags & 1 /*PF_X*/)) {
            exec_lo = reinterpret_cast<uint64_t>(base_) + ph.p_vaddr;
            exec_hi = exec_lo + ph.p_memsz;
            break;
        }
    }
    if (!exec_lo) return;

    // (2) function starts from .eh_frame_hdr (PT_GNU_EH_FRAME). Parsed BEFORE the
    // plan lookup (1b): whether MACAPK_X18_EXEC_ONLY changes this library's pass,
    // hence which plan version it may read and write, is a function of the starts.
    std::vector<uint64_t> starts;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != 0x6474e550u) continue;  // PT_GNU_EH_FRAME
        const uint8_t* ehf = base_ + ph.p_vaddr;
        const uint8_t* p = ehf;
        uint8_t version    = *p++;
        uint8_t ehptr_enc  = *p++;
        uint8_t fdecnt_enc = *p++;
        uint8_t table_enc  = *p++;
        if (version != 1 || table_enc == 0xff) break;
        (void)macapk_dw_read(p, ehptr_enc, ehf);            // eh_frame_ptr (skip)
        uint64_t fde_count = macapk_dw_read(p, fdecnt_enc, ehf);
        if (fde_count == 0 || fde_count > (1u << 24)) break;
        for (uint64_t i = 0; i < fde_count; ++i) {
            uint64_t loc = macapk_dw_read(p, table_enc, ehf);  // initial_location
            (void)macapk_dw_read(p, table_enc, ehf);           // FDE ptr (skip)
            if (loc >= exec_lo && loc < exec_hi) starts.push_back(loc);
        }
        break;
    }
    if (starts.size() < 2) {
        std::fprintf(stderr,
            "[x18-generic] %s: no usable .eh_frame_hdr function table -- "
            "skipped (falling back to existing mitigations)\n", dbg_name_);
        return;
    }
    std::sort(starts.begin(), starts.end());
    starts.erase(std::unique(starts.begin(), starts.end()), starts.end());

    // MACAPK_X18_EXEC_ONLY (default ON), see the window loop below. `x18_exec_only`
    // means the gate is on AND the section table was usable (exec_ranges_state_ 1;
    // it is also read for MACAPK_SVC_BRK_EXEC_ONLY, so the state alone does not
    // imply this gate). It CHANGES the pass on this library iff some byte of
    // [first FDE start, end of the PF_X segment) is data: that is exactly when a
    // window is cut or skipped. A superset test on purpose (a window whose data
    // starts beyond its 1 MiB cap is byte-identical in both passes, and still gets
    // version 3 -- one re-scan, never a wrong replay).
    const bool x18_exec_only = macapk_x18_exec_only() && exec_ranges_state_ == 1;
    bool x18_exec_only_changes_pass = false;
    if (x18_exec_only) {
        const uint64_t b = reinterpret_cast<uint64_t>(base_);
        x18_exec_only_changes_pass =
            first_data_vaddr_in(starts.front() - b, exec_hi - b) < exec_hi - b;
    }
    const uint32_t x18_plan_version =
        x18_exec_only_changes_pass ? kX18PlanVersionExecOnly : kX18PlanVersion;

    // (1b) x18 plan cache: on a CLEAN cache hit, skip the ~40s scan entirely; on
    // a DIRTY hit, run the live pass on ONLY the windows the previous scan proved
    // write something (see the kX18PlanVersion comment block for why that is
    // byte-identical). Key = {version, exec byte length, fast hash of the
    // ORIGINAL exec bytes}. The exec segment is pure code (PIC .text is not
    // relocated), so the pre-scan bytes are the same original bytes every launch
    // -> a stable key.
    // (Precisely: the bytes as the EARLIER passes in load_common leave them --
    // the canary pass alone rewrites 504,768 words of libminecraftpe before this
    // point. Those passes are deterministic, so the key is still stable per
    // build; it is just not the ELF's own bytes, and not the canary key's input.
    // The hash and the version tag come from the plan-key hash block above.)
    //
    // The bisect knobs change what the pass emits but are NOT part of the key, so
    // any of them present disables the cache in BOTH directions -- otherwise a
    // truncated diagnostic run could write a plan that a later production run
    // replays (or, on the pre-existing clean path, mark a rewriting lib "clean").
    // MACAPK_X18_EXEC_ONLY=0 is NOT a bisect knob: it is the old whole-window pass
    // and reads/writes plan version 2 (x18_plan_version), which a default-arm pass
    // on an affected library refuses, and which is exactly what a default-arm pass
    // on an unaffected library writes.
    const bool x18_diag_knob = std::getenv("MACAPK_X18_LIMIT") ||
                               std::getenv("MACAPK_X18_LIMIT_INSN") ||
                               std::getenv("MACAPK_X18_THUNK");
    const bool x18_cache_on = !(std::getenv("MACAPK_X18_CACHE") &&
                                std::getenv("MACAPK_X18_CACHE")[0] == '0')
                              && !x18_diag_knob;
    // MACAPK_X18_PLAN_VERIFY=1: read the cached plan but run the FULL scan anyway,
    // then assert the freshly-derived effect-window set matches the cached one.
    // This is the in-process half of debug/x18_plan_verify.sh's proof.
    const bool x18_plan_verify = std::getenv("MACAPK_X18_PLAN_VERIFY") &&
                                 std::getenv("MACAPK_X18_PLAN_VERIFY")[0] != '0';
    const char* x18_dump_dir = std::getenv("MACAPK_X18_PLAN_DUMP");
    const size_t   exec_len  = (size_t)(exec_hi - exec_lo);
    const uint64_t exec_hash = x18_cache_on
        ? macapk_plan_exec_hash(reinterpret_cast<const uint8_t*>(exec_lo), exec_len) : 0;
    std::string plan_path = x18_cache_on ? macapk_x18_plan_path(dbg_name_) : std::string();
    bool replay = false;                       // dirty plan loaded -> skip no-op windows
    std::unordered_set<uint64_t> eff_cached;   // exec-relative window starts that WRITE
    int64_t cached_counts[kX18CntN] = {0};
    if (!plan_path.empty()) {
        FILE* cf = std::fopen(plan_path.c_str(), "rb");
        if (cf) {
            uint32_t magic = 0, ver = 0; uint64_t hlen = 0, hh = 0; uint8_t clean = 0;
            uint64_t nwin = 0;
            bool ok = std::fread(&magic, 4, 1, cf) == 1 && magic == kX18PlanMagic
                   && std::fread(&ver,   4, 1, cf) == 1
                   && ver == macapk_plan_disk_version(x18_plan_version)
                   && std::fread(&hlen,  8, 1, cf) == 1 && hlen == exec_len
                   && std::fread(&hh,    8, 1, cf) == 1 && hh == exec_hash
                   && std::fread(&clean, 1, 1, cf) == 1
                   && std::fread(cached_counts, sizeof(int64_t), kX18CntN, cf) == kX18CntN
                   && std::fread(&nwin, 8, 1, cf) == 1;
            if (ok && !clean) {
                // Sanity-bound the window count against the function count the
                // plan itself recorded, so a truncated/garbage file can never
                // make us allocate wildly or trust a partial set.
                ok = nwin <= (uint64_t)cached_counts[kX18CntTotal] && nwin > 0;
                std::vector<uint64_t> wv(ok ? (size_t)nwin : 0);
                if (ok) ok = std::fread(wv.data(), 8, (size_t)nwin, cf) == (size_t)nwin;
                if (ok) {
                    eff_cached.reserve((size_t)nwin * 2);
                    for (uint64_t o : wv) {
                        if (o >= exec_len) { ok = false; break; }
                        eff_cached.insert(o);
                    }
                }
                if (!ok) eff_cached.clear();
            }
            std::fclose(cf);
            if (ok && clean) {
                std::fprintf(stderr,
                    "[x18-generic] %s: CACHE HIT clean (funcs=%lld x18-funcs=%lld "
                    "rewritten=%lld insns=%lld) -- skipped scan\n",
                    dbg_name_, (long long)cached_counts[kX18CntTotal],
                    (long long)cached_counts[kX18CntX18],
                    (long long)cached_counts[kX18CntRewritten],
                    (long long)cached_counts[kX18CntInsn]);
                return;
            }
            if (ok && !clean && !eff_cached.empty()) {
                replay = !x18_plan_verify;
                std::fprintf(stderr,
                    "[x18-generic] %s: CACHE HIT dirty (%llu effect window(s) of "
                    "%lld) -- %s\n", dbg_name_, (unsigned long long)eff_cached.size(),
                    (long long)cached_counts[kX18CntTotal],
                    replay ? "replaying live pass on those only"
                           : "VERIFY mode: full scan + compare");
            }
        }
    }

    // (3) Capstone (detail mode for register access + control flow).
    csh h;
    if (cs_open(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, &h) != CS_ERR_OK) return;
    cs_option(h, CS_OPT_DETAIL, CS_OPT_ON);

    // (3b) x18 detour-thunk pool for nofree webs (see macapk_x18_rewrite_one).
    // Sized to comfortably hold the tail-web thunks of the largest guest
    // (Subway libunity: ~1100 nofree x18 insns * ~7 words * 4B ~= 31 KB); 1 MB
    // is generous. Placed just past the image so a `b` from any .text site
    // reaches it (well within +/-128 MB). Left RW here; made RX in
    // apply_segment_protections. No pool -> thunking simply doesn't run (the
    // residual mitigations still cover, as before).
    MacapkX18ThunkPool tpool_storage;
    MacapkX18ThunkPool* tpool = nullptr;
    {
        const char* toff = std::getenv("MACAPK_X18_THUNK");
        if (!(toff && toff[0] == '0')) {
            const size_t cap = 1u << 20;
            // v1.0.762: PLACE the pool, do not just hint at it. A bare mmap hint
            // is advisory -- when the page after the image is already taken the
            // kernel silently picks its own address, which under ASLR can land
            // hundreds of MB away. Every thunk is reached by a 4-byte `b` (+/-128
            // MB), so an out-of-reach pool makes macapk_x18_rewrite_one abandon
            // EVERY nofree web with "b-range" and the whole image loses its thunk
            // lowering for that launch. Measured directly with this file's plan
            // dump on Subway: three consecutive launches of the SAME libunity.so
            // produced 65 thunked webs / 14964B, then 0 / 0B, then 41 / 12064B.
            // That is a per-launch coin flip on whether ~1100 nofree x18
            // instructions get protected, and it matches the long-standing
            // "Subway residual x18 ~1-in-6, non-deterministic" note in
            // debug/baselines.tsv.
            //
            // So: try candidate addresses closest-first and keep the first one
            // the kernel actually places within reach of the executable segment.
            // Being CLOSE also makes the per-web range test a pure function of
            // the guest bytes (every .text site is comfortably inside +/-120 MB),
            // which is what lets the v2 plan cache be verifiably reproducible.
            //
            // MACAPK_X18_POOL_BAREHINT=1 reproduces the PRE-v1.0.762 behaviour
            // (a single mmap with the bare advisory hint, no reachability
            // check). It exists so the claim above -- that this placement bug
            // is the long-standing "Subway residual x18 ~1-in-6" -- can be
            // A/B'd rather than asserted, and re-tested by a later session.
            // Diagnostic only; never set it on a normal run.
            const char* barehint = std::getenv("MACAPK_X18_POOL_BAREHINT");
            const bool pool_barehint = barehint && barehint[0] == '1';
            const uint64_t kNear = 64ull << 20;   // keep the pool this close to .text
            void* tp = MAP_FAILED;
            void* prev = nullptr; int repeats = 0;
            if (pool_barehint) {
                void* hint = static_cast<void*>(base_ + mapped_size_);
                void* got = mmap(hint, cap, PROT_READ | PROT_WRITE,
                                 MAP_PRIVATE | MAP_ANON, -1, 0);
                if (got != MAP_FAILED) tp = got;
                std::fprintf(stderr,
                    "[x18-generic] %s: POOL_BAREHINT diagnostic: pool at %p "
                    "(hint %p), %+lldKB from exec_hi\n", dbg_name_, tp, hint,
                    tp == MAP_FAILED ? 0ll
                        : (long long)(((int64_t)reinterpret_cast<uint64_t>(tp)
                                       - (int64_t)exec_hi) / 1024));
            }
            for (int attempt = 0; !pool_barehint && attempt < 24 && tp == MAP_FAILED; ++attempt) {
                uint64_t hint;
                if (attempt == 0) {
                    hint = reinterpret_cast<uint64_t>(base_) + mapped_size_;
                } else if (attempt <= 12) {       // march forward from exec_hi
                    hint = exec_hi + (uint64_t)attempt * (4ull << 20);
                } else {                          // then backward from exec_lo
                    uint64_t back = (uint64_t)(attempt - 12) * (4ull << 20) + cap;
                    if (back >= exec_lo) continue;
                    hint = exec_lo - back;
                }
                hint &= ~(uint64_t)(page_size_ - 1);
                void* got = mmap(reinterpret_cast<void*>(hint), cap,
                                 PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
                if (got == MAP_FAILED) continue;
                uint64_t p_lo = reinterpret_cast<uint64_t>(got), p_hi = p_lo + cap;
                bool near = (p_lo >= exec_hi && p_lo - exec_hi < kNear)
                         || (p_hi <= exec_lo && exec_lo - p_hi < kNear);
                if (near) { tp = got; break; }
                munmap(got, cap);               // too far to `b` to -- try again
                // If the kernel hands back the same address regardless of the
                // hint it is ignoring hints entirely for this process layout;
                // more attempts cannot help.
                if (got == prev && ++repeats >= 2) break;
                if (got != prev) { prev = got; repeats = 0; }
            }
            if (tp != MAP_FAILED) {
                x18_thunks_ = static_cast<uint8_t*>(tp);
                x18_thunks_used_ = 0;
                x18_thunks_cap_ = cap;
                tpool_storage.base   = x18_thunks_;
                tpool_storage.used   = &x18_thunks_used_;
                tpool_storage.cap    = cap;
                tpool_storage.img_lo = reinterpret_cast<uint64_t>(base_);
                tpool_storage.img_hi = reinterpret_cast<uint64_t>(base_) + mapped_size_;
                tpool = &tpool_storage;
            }
            // Always report the outcome: "thunk lowering silently did not run"
            // used to be invisible, and it is the difference between a protected
            // and an unprotected image.
            if (tpool)
                std::fprintf(stderr, "[x18-generic] %s: thunk pool placed "
                    "%+lldKB from exec_hi\n", dbg_name_,
                    (long long)(((int64_t)reinterpret_cast<uint64_t>(tp)
                                 - (int64_t)exec_hi) / 1024));
            // The "could not place it" case is only worth a warning if the pass
            // then actually finds nofree webs -- see the fn_nofree check after
            // the scan. Most images never need a pool at all.
        }
    }

    long fn_total = 0, fn_x18 = 0, fn_rewritten = 0;
    long fn_nofree = 0, fn_verify = 0, insn_rewritten = 0, insn_thunked = 0, fn_thunked = 0;
    // The effect windows THIS run produced: (exec-relative start, length). This
    // is both what gets serialized as the dirty plan and what the dump covers --
    // every .text patch lives inside one of these by construction.
    std::vector<std::pair<uint64_t, size_t>> eff_live;

    uint64_t traceoff = 0;
    if (const char* t = std::getenv("MACAPK_X18_FN")) traceoff = strtoull(t, nullptr, 0);

    // MACAPK_X18_EXEC_ONLY (default ON): a window runs from one FDE start to the
    // next, and the LAST one used to run to the end of the PF_X segment. When the
    // linker put .rodata / .eh_frame / .gcc_except_table in that same r-x segment
    // (BFD/gold layouts; Jetpack 2's libmortargame.so: .text ends at 0xc76d38,
    // the segment at 0xe3c324), up to 1 MiB of DATA was disassembled as code and
    // any word that decoded as an x18 write was "renamed". Measured: the string
    // "com/google/firebase/auth/internal/cpp/JniIdTokenListener" (starts at file
    // 0xccf31f) holds, at the aligned file offset 0xccf344, the word 0x696E4A2F
    // ("/Jni"), which decodes as `ldpsw x15, x18, [x17, #-0x90]`; the pass rewrote Rt2 x18->x9 (0x696E262F) and the class
    // name became "&niIdTokenListener", so Firebase C++ auth init threw
    // ClassNotFoundException out of native_SystemInit. Android runs these bytes
    // as data and never touches them. So, with a usable section table, a window
    // stops at the first byte positively inside a non-executable section (the
    // same rule the svc->BRK and MIDR passes already use, read_exec_sections), and
    // a window whose start is itself in data is skipped. Bytes in no section stay
    // code, as there. MACAPK_X18_EXEC_ONLY=0: the whole-window scan, byte for byte,
    // with plan version 2 (see kX18PlanVersionExecOnly).
    long x18_win_cut = 0, x18_win_skipped = 0;
    uint64_t x18_bytes_spared = 0;
    for (size_t fi = 0; fi < starts.size(); ++fi) {
        uint64_t fstart = starts[fi];
        uint64_t fend = (fi + 1 < starts.size()) ? starts[fi + 1] : exec_hi;
        if (fend > exec_hi) fend = exec_hi;
        if (fend <= fstart) continue;
        if (x18_exec_only) {
            const uint64_t b = reinterpret_cast<uint64_t>(base_);
            bool nosec = false;
            if (text_word_is_data(fstart - b, &nosec)) {
                ++x18_win_skipped;
                x18_bytes_spared += fend - fstart;
                continue;
            }
            uint64_t cut = b + first_data_vaddr_in(fstart - b, fend - b);
            if (cut < fend) {
                ++x18_win_cut;
                // What the old window actually scanned: it was capped at 1 MiB.
                const uint64_t old_end = std::min<uint64_t>(fend, fstart + (1u << 20));
                if (old_end > cut) x18_bytes_spared += old_end - cut;
                fend = cut;
            }
        }
        size_t flen = (size_t)(fend - fstart);
        if (flen > (1u << 20)) flen = (1u << 20);
        fn_total++;

        // Dirty-plan replay: the previous scan proved this window writes nothing
        // (no .text patch, no thunk-pool byte), so re-deriving that costs a full
        // Capstone detail-mode disassembly to reach the identical conclusion.
        // Skipping it leaves memory in exactly the state the full scan would.
        if (replay && !eff_cached.count(fstart - exec_lo)) continue;

        // Cheap raw pre-filter: x18 must be encoded in a 5-bit register field
        // (bits 0/5/10/16), or be one of the eight registers a FEAT_LS64
        // transfer names. If no word in the function can, it cannot reference
        // x18 -> skip without invoking Capstone. (False positives -- a
        // coincidental immediate -- go on to macapk_x18_rewrite_one, whose own
        // per-candidate check returns the zero result.) This used to test only
        // the four fields, which let an LD64B/ST64B* with Rt=12..16 through
        // unanalysed; it now shares macapk_word_may_ref_x18 so both stages
        // apply the same exact rule.
        const uint32_t* words = reinterpret_cast<const uint32_t*>(fstart);
        size_t wcount = flen / 4;
        bool maybe = false;
        for (size_t w = 0; w < wcount; ++w) {
            if (macapk_word_may_ref_x18(words[w])) { maybe = true; break; }
        }
        bool trace = traceoff && (fstart - exec_lo) <= traceoff
                              && traceoff < (fend - exec_lo);
        if (traceoff && (fstart - exec_lo) <= traceoff && traceoff < (fstart - exec_lo + flen) && !maybe)
            std::fprintf(stderr, "[x18-fn] %s window +%#llx..+%#llx contains %#llx "
                "but prefilter says NO x18\n", dbg_name_,
                (unsigned long long)(fstart - exec_lo),
                (unsigned long long)(fend - exec_lo), (unsigned long long)traceoff);
        if (!maybe) continue;

        MacapkX18Res r = macapk_x18_rewrite_one(h, fstart, flen, tpool);
        if (trace)
            std::fprintf(stderr, "[x18-fn] %s window +%#llx..+%#llx (len %zu) "
                "contains %#llx -> status=%d insns=%d thunked=%d\n", dbg_name_,
                (unsigned long long)(fstart - exec_lo),
                (unsigned long long)(fend - exec_lo), flen,
                (unsigned long long)traceoff, r.status, r.insns_rewritten, r.thunked);
        if (r.thunked > 0) { fn_thunked++; insn_thunked += r.thunked; }
        switch (r.status) {
            case 1: fn_x18++; fn_rewritten++; insn_rewritten += r.insns_rewritten; break;
            case 2: fn_x18++; fn_nofree++; break;
            case 3: fn_x18++; fn_verify++; break;
            default: break;  // 0 = no real x18 use
        }
        // The plan records every window where the analysis found REAL x18 use --
        // status 1 (committed .text patches), 2 (x18 present, no rename target),
        // 3 (x18 present, encoding refused) or any thunk. Only status 0 is
        // excluded, and status 0 is precisely "no x18 instruction survived the
        // analysis", which returns before touching a single byte.
        //
        // The criterion deliberately is NOT "wrote something". Whether a nofree
        // web can be LOWERED depends on the thunk pool being reachable, which
        // depends on where the kernel placed it -- so a "wrote something" set
        // would be a per-launch quantity, and a replay keyed on a colder run's
        // set could skip a window that this launch would have thunked, leaving
        // live x18 in it. Whether a window CONTAINS real x18 use is a pure
        // function of the guest bytes, so this set is stable. It costs almost
        // nothing: on Subway's libunity it is 366 windows of 53398 (a 146x
        // reduction) versus 349 for the narrower rule.
        if (r.status != 0 || r.thunked > 0)
            eff_live.emplace_back(fstart - exec_lo, flen);
    }
    if (x18_win_cut || x18_win_skipped)
        std::fprintf(stderr,
            "[x18-generic] %s: exec-only: %ld window(s) stopped at a non-executable "
            "section, %ld skipped (start in data); %llu data byte(s) of the PF_X "
            "segment no longer scanned as code (MACAPK_X18_EXEC_ONLY)\n",
            dbg_name_, x18_win_cut, x18_win_skipped,
            (unsigned long long)x18_bytes_spared);
    else if (macapk_x18_exec_only() && exec_ranges_state_ == -1)
        std::fprintf(stderr,
            "[x18-generic] %s: exec-only: NO usable section headers (%s) -- windows "
            "run to the next FDE / end of the PF_X segment as before\n",
            dbg_name_, exec_ranges_why_);
    // Debug: post-pass scan for x18 instructions that SURVIVED the rewrite (the
    // residual the prefault/recovery must still cover). Logs the first few sites.
    if (std::getenv("MACAPK_X18_DEBUG")) {
        long remain = 0;
        for (uint64_t a = exec_lo; a + 4 <= exec_hi; a += 4) {
            uint32_t x = *reinterpret_cast<const uint32_t*>(a);
            if (!((x & 0x1fu) == 18u || ((x >> 5) & 0x1fu) == 18u ||
                  ((x >> 10) & 0x1fu) == 18u || ((x >> 16) & 0x1fu) == 18u))
                continue;
            cs_insn* one = nullptr;
            size_t no = cs_disasm(h, (const uint8_t*)a, 4, a, 0, &one);
            if (no == 1) {
                cs_regs rr, rw; uint8_t nr = 0, nw = 0;
                bool t = false;
                if (cs_regs_access(h, &one[0], rr, &nr, rw, &nw) == CS_ERR_OK) {
                    for (uint8_t r = 0; r < nr && !t; ++r)
                        if (macapk_name_to_gp(cs_reg_name(h, rr[r])) == 18) t = true;
                    for (uint8_t r = 0; r < nw && !t; ++r)
                        if (macapk_name_to_gp(cs_reg_name(h, rw[r])) == 18) t = true;
                }
                if (t) {
                    if (++remain <= 30)
                        std::fprintf(stderr, "[x18-residual] %s+%#llx: %s %s\n",
                            dbg_name_, (unsigned long long)(a - exec_lo),
                            one[0].mnemonic, one[0].op_str);
                }
            }
            if (no) cs_free(one, no);
        }
        std::fprintf(stderr, "[x18-generic] %s: RESIDUAL x18 sites = %ld\n",
                     dbg_name_, remain);
    }
    cs_close(&h);
    // Invalidate the icache over the freshly written thunk pool (it holds live
    // code the guest will `b` into). The pool is still RW here; the RX flip +
    // final barrier happen in apply_segment_protections.
    if (x18_thunks_ && x18_thunks_used_)
        sys_icache_invalidate(x18_thunks_, x18_thunks_used_);
    // Cross-check the replay against the plan on the two counters that are pure
    // functions of the guest bytes: how many windows exist, and how many of them
    // contain real x18 use. Both are recomputed in full by a replay (every x18
    // window is re-run), so a difference means the plan and the code disagree --
    // a hash collision, or a change to the pass without a kX18PlanVersion bump.
    // Say so loudly rather than silently running on a stale plan.
    //
    // The thunk counters are deliberately NOT cross-checked: whether a nofree web
    // gets lowered depends on the pool being reachable, which is a property of
    // this launch's address space, not of the guest.
    if (replay) {
        if (cached_counts[kX18CntTotal] != (int64_t)fn_total ||
            cached_counts[kX18CntX18]   != (int64_t)fn_x18)
            std::fprintf(stderr,
                "[x18-generic] %s: WARNING replay disagrees with plan "
                "(funcs %lld/%ld x18-funcs %lld/%ld) -- delete .x18cache "
                "and re-scan\n", dbg_name_,
                (long long)cached_counts[kX18CntTotal], fn_total,
                (long long)cached_counts[kX18CntX18],   fn_x18);
    }
    std::fprintf(stderr,
        "[x18-generic] %s: funcs=%ld x18-funcs=%ld rewritten=%ld insns=%ld "
        "skipped(nofree=%ld verify=%ld) thunked(webs=%ld insns=%ld used=%zuB)%s\n",
        dbg_name_, fn_total, fn_x18, fn_rewritten, insn_rewritten,
        fn_nofree, fn_verify, fn_thunked, insn_thunked, x18_thunks_used_,
        replay ? " [plan-replay]" : "");
    // Thunk lowering could not run because the pool was unplaceable, AND this
    // image has webs that needed it. Those instructions keep a live x18 that XNU
    // will zero on the next EL0 return, so this is worth seeing in a crash log.
    if (!tpool && fn_nofree > 0)
        std::fprintf(stderr,
            "[x18-generic] %s: WARNING no thunk pool within b-range -- %ld "
            "function(s) keep residual x18\n", dbg_name_, fn_nofree);

    // MACAPK_X18_PLAN_VERIFY: a full scan ran despite a cached dirty plan being
    // present, so the two effect-window sets can be compared directly. Equality
    // is the whole safety argument for the replay -- if the sets match, the
    // windows the replay would have skipped are exactly the windows this full
    // scan proved write nothing.
    if (x18_plan_verify && !eff_cached.empty()) {
        size_t missing = 0, extra = 0;
        std::unordered_set<uint64_t> live_set;
        live_set.reserve(eff_live.size() * 2);
        for (const auto& wn : eff_live) live_set.insert(wn.first);
        for (uint64_t o : eff_cached) if (!live_set.count(o)) ++extra;
        for (uint64_t o : live_set)   if (!eff_cached.count(o)) ++missing;
        std::fprintf(stderr,
            "[x18-plan-verify] %s: %s cached=%zu live=%zu "
            "cached-only=%zu live-only=%zu\n", dbg_name_,
            (missing == 0 && extra == 0) ? "PASS" : "FAIL",
            eff_cached.size(), live_set.size(), extra, missing);
    }

    // MACAPK_X18_PLAN_DUMP=<dir>: base-independent evidence of the exact code the
    // pass emitted, for debug/x18_plan_verify.sh to diff fresh-scan vs replay.
    if (x18_dump_dir && *x18_dump_dir) {
        std::vector<std::pair<uint64_t, size_t>> wins = eff_live;
        std::sort(wins.begin(), wins.end());
        int64_t dc[kX18CntN] = {fn_total, fn_x18, fn_rewritten, insn_rewritten,
                                fn_nofree, fn_verify, fn_thunked, insn_thunked,
                                (int64_t)x18_thunks_used_};
        macapk_x18_write_dump(x18_dump_dir, dbg_name_, exec_lo,
                              reinterpret_cast<uint64_t>(base_), wins,
                              x18_thunks_, x18_thunks_used_, dc);
    }

    // (5) x18 plan cache write. A CLEAN verdict (the pass emitted NOTHING) lets
    // the next launch skip the whole scan; a DIRTY plan records which function
    // windows emitted something so the next launch runs the live pass on only
    // those. Only a FULL scan may write a plan -- a replay already ran a subset
    // and would record a set it never re-derived.
    if (!plan_path.empty() && !replay) {
        bool clean = (fn_rewritten == 0 && insn_rewritten == 0 &&
                      fn_thunked == 0 && insn_thunked == 0 && x18_thunks_used_ == 0);
        // ... but never conclude "clean" from a launch that COULDN'T have emitted
        // anything. With no reachable thunk pool every nofree web is abandoned,
        // so an image that normally thunks looks identical to one that has no
        // work to do -- and a clean marker is permanent, so the next launch would
        // skip the scan and lose the lowering forever. The dirty plan is immune
        // (its window set is pool-independent), so only the clean verdict needs
        // this guard.
        if (clean && !tpool && fn_nofree > 0) {
            std::fprintf(stderr, "[x18-generic] %s: not recording a clean verdict"
                " -- %ld nofree function(s) went unlowered because no thunk pool"
                " was placed this launch\n", dbg_name_, fn_nofree);
            clean = false;
        }
        // A dirty plan is only worth writing when the effect set is genuinely
        // sparse; if nearly every window emits something there is nothing to skip
        // and the plan would just be a large file for no gain.
        bool worth = !eff_live.empty() &&
                     (int64_t)eff_live.size() * 4 < (int64_t)fn_total;
        if (clean || worth) {
            std::string dir = plan_path.substr(0, plan_path.rfind('/'));
            mkdir(dir.c_str(), 0755);   // parent app-data dir already exists
            std::string tmp = plan_path + ".tmp";
            FILE* wf = std::fopen(tmp.c_str(), "wb");
            if (wf) {
                uint32_t magic = kX18PlanMagic;
                uint32_t ver = macapk_plan_disk_version(x18_plan_version);
                uint64_t hlen = exec_len, hh = exec_hash;
                uint8_t cflag = clean ? 1 : 0;
                int64_t counts[kX18CntN] = {fn_total, fn_x18, fn_rewritten,
                    insn_rewritten, fn_nofree, fn_verify, fn_thunked,
                    insn_thunked, (int64_t)x18_thunks_used_};
                std::vector<uint64_t> wv;
                if (!clean) {
                    wv.reserve(eff_live.size());
                    for (const auto& wn : eff_live) wv.push_back(wn.first);
                    std::sort(wv.begin(), wv.end());
                }
                uint64_t nwin = wv.size();
                bool wok = std::fwrite(&magic, 4, 1, wf) == 1
                        && std::fwrite(&ver,   4, 1, wf) == 1
                        && std::fwrite(&hlen,  8, 1, wf) == 1
                        && std::fwrite(&hh,    8, 1, wf) == 1
                        && std::fwrite(&cflag, 1, 1, wf) == 1
                        && std::fwrite(counts, sizeof(int64_t), kX18CntN, wf) == kX18CntN
                        && std::fwrite(&nwin,  8, 1, wf) == 1
                        && (nwin == 0 || std::fwrite(wv.data(), 8, (size_t)nwin, wf) == nwin);
                wok = (std::fclose(wf) == 0) && wok;
                if (wok) {
                    std::rename(tmp.c_str(), plan_path.c_str());
                    std::fprintf(stderr, "[x18-generic] %s: wrote %s plan cache"
                        " (%llu window(s))\n", dbg_name_, clean ? "clean" : "dirty",
                        (unsigned long long)nwin);
                } else {
                    std::remove(tmp.c_str());   // never leave a half-written plan
                }
            }
        }
    }
}

// ===================================================================
// il2cpp managed-exception logger, "F3" (MACAPK_IL2CPP_EXC_LOG, default off)
// ===================================================================
// A DEBUGGER, not a platform surface: installed by
// Image::install_il2cpp_exc_logger from libil2cpp's EXPORTED symbols. The
// Crossy-7.11.1 boot-stage counters that used to share this section
// (MACAPK_INTEG_COUNT / MACAPK_BOOTCTRL_COUNT / MACAPK_CLASSINIT_LOG) were
// deleted 2026-09-27 with the rest of the il2cpp diag kit.
// vm::Exception::Raise hook handler. ex (x0 at Raise entry) is the
// Il2CppException* about to be thrown. Its type name, "Type: message" text and
// managed stack come from il2cpp's own EXPORTED API (il2cpp_class_get_name,
// il2cpp_format_exception, il2cpp_current_thread_get_frame_at, ...), resolved by
// NAME when the hook is installed. A build without il2cpp_raise_exception,
// il2cpp_class_get_name or il2cpp_format_exception is not hooked at all
// (install_il2cpp_exc_logger logs and disables), and the optional stack exports
// are checked where they are used; nothing is ever called at base + a hardcoded
// offset. (Until 2026-09-27 a missing export fell back to Crossy Road 7.11.1's
// RVAs, which on any other build is arbitrary code.) Gated on
// MACAPK_IL2CPP_EXC_LOG, reentrancy-guarded (formatting can itself throw on a
// malformed exception), capped to bound a throw flood. Default-off.
// libil2cpp's load base and mapped span, both set by install_il2cpp_exc_logger.
uint64_t g_il2cpp_base = 0;          // libil2cpp load base
uint64_t g_il2cpp_mapped_size = 0;   // libil2cpp total mapped span (for fp-walk range)
// Total vm::Exception::Raise hits this run (exposed in the render-state probe).
// If this stays 0, NO managed exception goes through the hooked Raise -> the hook
// site is wrong (or the throw path differs). If >0, the hook fires and any missing
// [il2cpp-exc] lines are a logging (format/gate) problem, not a hook problem.
std::atomic<uint64_t> g_exc_raise_count{0};
// F3 generic exc logger: il2cpp API helper addresses resolved from libil2cpp's
// EXPORTED dynsym (by install_il2cpp_exc_logger -- zero hardcoded RVAs) + the
// continuation stub the entry-hook returns through. 0 until resolved (see the
// handler comment for which ones are required).
extern "C" uint64_t g_il2cpp_exc_clsname = 0;  // il2cpp_class_get_name
extern "C" uint64_t g_il2cpp_exc_fmtexc  = 0;  // il2cpp_format_exception
extern "C" uint64_t g_il2cpp_exc_fmtstk  = 0;  // il2cpp_format_stack_trace
// T1 (plan 4): live managed-stack capture. il2cpp_format_stack_trace formats the
// EXCEPTION's stored trace, which release-build il2cpp leaves EMPTY (no trace IPs
// recorded into caught exceptions), so we instead walk the CURRENT thread's frames
// at the throw via these exported APIs. Signatures verified against il2cpp-api-
// functions.h: int32_t il2cpp_current_thread_get_stack_depth(void);
// bool il2cpp_current_thread_get_frame_at(int32_t offset, Il2CppStackFrameInfo*).
extern "C" uint64_t g_il2cpp_exc_stkdepth = 0; // il2cpp_current_thread_get_stack_depth
extern "C" uint64_t g_il2cpp_exc_frameat  = 0; // il2cpp_current_thread_get_frame_at
extern "C" uint64_t g_il2cpp_exc_methname = 0; // il2cpp_method_get_name
extern "C" uint64_t g_il2cpp_exc_methcls  = 0; // il2cpp_method_get_class
extern "C" uint64_t g_exc_raise_cont     = 0;  // runtime [displaced insn][b Raise+4] stub
extern "C" void macapk_il2cpp_exc_tramp_gen();
extern "C" void macapk_il2cpp_exc_log(void* ex, void* raise_ret) {
    g_exc_raise_count.fetch_add(1, std::memory_order_relaxed);   // ALWAYS, before any gate
    static const bool on = std::getenv("MACAPK_IL2CPP_EXC_LOG") != nullptr;
    if (!on || !ex || !g_il2cpp_base) return;
    static std::atomic<int> reentry{0};
    static std::atomic<int> count{0};
    if (count.load(std::memory_order_relaxed) > 5000) return;
    int expected = 0;
    if (!reentry.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) return;
    int n = count.fetch_add(1, std::memory_order_relaxed) + 1;
    // ROBUST first: name the exception TYPE via the exported il2cpp_class_get_name
    // on the exception object's class ([ex+0]), logged + flushed BEFORE the heavier
    // il2cpp_format_exception (which can hang/empty on a malformed trace), so the
    // type survives even if the message call stalls.
    typedef const char* (*gn_t)(void*);
    gn_t gcn = (gn_t)(uintptr_t)g_il2cpp_exc_clsname;   // il2cpp_class_get_name (required)
    void* klass = *reinterpret_cast<void**>(ex);
    const char* cname = klass ? gcn(klass) : nullptr;
    std::fprintf(stderr, "[il2cpp-exc #%d] type=%s\n", n, cname ? cname : "?");
    std::fflush(stderr);
    // Then the "Type: message" text (best-effort; skip the stack-trace formatter,
    // the most likely to hang).
    typedef void (*fmt_t)(void*, char*, int);
    fmt_t fmt_exc = (fmt_t)(uintptr_t)g_il2cpp_exc_fmtexc;   // il2cpp_format_exception (required)
    char buf[400]; buf[0] = 0;
    fmt_exc(ex, buf, (int)sizeof(buf));
    buf[sizeof(buf) - 1] = 0;
    std::fprintf(stderr, "[il2cpp-exc #%d] msg=%s\n", n, buf);
    std::fflush(stderr);
    // Scan for the inner exception (System.Exception._innerException; the offset
    // varies by il2cpp version) so a TypeInitializationException names its real
    // cause. A field is the inner if it points to an object whose class name
    // contains Exception/Error.
    for (int off = 0x10; off <= 0x50; off += 8) {
        void* inner = *reinterpret_cast<void**>(static_cast<char*>(ex) + off);
        if (!inner || inner == ex) continue;
        uintptr_t iv = (uintptr_t)inner;
        if (iv < 0x100000000ull || iv >= 0x900000000ull) continue;
        void* ik = *reinterpret_cast<void**>(inner);
        uintptr_t kv = (uintptr_t)ik;
        if (kv < 0x100000000ull || kv >= 0x900000000ull) continue;
        const char* in = gcn(ik);
        if (in && (std::strstr(in, "Exception") || std::strstr(in, "Error"))) {
            char ibuf[400]; ibuf[0] = 0;
            fmt_exc(inner, ibuf, (int)sizeof(ibuf)); ibuf[sizeof(ibuf) - 1] = 0;
            std::fprintf(stderr, "[il2cpp-exc #%d]   inner[+0x%x] type=%s msg=%s\n", n, off, in, ibuf);
            std::fflush(stderr);
            break;
        }
    }
    // Native fp-chain backtrace (MACAPK_EXC_NATIVE_STACK) -- works even when
    // il2cpp's managed-stack API is unavailable (this release build keeps
    // stacktrace tracking OFF, so il2cpp_current_thread_get_stack_depth returns
    // 0 and the managed walk below is empty). il2cpp-compiled arm64 methods use
    // standard AAPCS frames (x29 chain), and our exc trampoline passes x29
    // through unchanged, so __builtin_frame_address(0) -> [fp] reaches the
    // throwing method's caller chain. We print each return address that falls
    // inside libil2cpp's mapped span as `libil2cpp+0xRVA`; map those RVAs back
    // to C# methods with Il2CppDumper to localize the throw (e.g. the Among Us
    // online spawn-deserialization NullReference). Capped + deduped so the
    // pervasive-NRE case does not flood the log.
    static const bool native_stk =
        std::getenv("MACAPK_EXC_NATIVE_STACK") != nullptr;
    static std::atomic<int> nstk{0};
    if (native_stk && cname && g_il2cpp_base &&
        nstk.load(std::memory_order_relaxed) < 40 &&
        (std::strstr(cname, "NullReference") || std::strstr(cname, "Argument") ||
         std::strstr(cname, "IndexOutOfRange") || std::strstr(cname, "InvalidCast") ||
         std::strstr(cname, "Invalid") || std::strstr(cname, "Format"))) {
        nstk.fetch_add(1, std::memory_order_relaxed);
        // The image's own mapped span, published with the base by the installer.
        const uint64_t span = g_il2cpp_mapped_size;
        const uint64_t lo = g_il2cpp_base, hi = g_il2cpp_base + span;
        // THE reliable frame: x30 at the Raise hook = the return address into
        // the method that threw (or il2cpp's null-check helper, whose caller is
        // the method). Frame-pointer omission makes the fp-walk below flaky, but
        // this single throw-site address is always exact. Map it with
        // Il2CppDumper to name the C# method.
        uint64_t rr = reinterpret_cast<uint64_t>(raise_ret);
        if (rr >= lo && rr < hi)
            std::fprintf(stderr, "    THROW-SITE libil2cpp+0x%llx\n",
                         (unsigned long long)(rr - g_il2cpp_base));
        else
            std::fprintf(stderr, "    THROW-SITE <non-il2cpp 0x%llx>\n",
                         (unsigned long long)rr);
        std::fprintf(stderr, "[il2cpp-exc #%d] native-stack (libil2cpp RVAs, throw-order)=\n", n);
        // Walk up to 64 frames; the AAPCS frame record is {saved x29, saved x30}.
        uintptr_t fp = reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
        int printed = 0;
        for (int depth = 0; depth < 64 && fp; ++depth) {
            // Bounds: fp must be a plausible stack pointer (16-aligned, readable).
            if (fp & 0xf) break;
            uint64_t next = *reinterpret_cast<uint64_t*>(fp);
            uint64_t ret  = *reinterpret_cast<uint64_t*>(fp + 8);
            if (ret >= lo && ret < hi) {
                std::fprintf(stderr, "    libil2cpp+0x%llx\n",
                             (unsigned long long)(ret - g_il2cpp_base));
                if (++printed >= 40) break;
            }
            if (next <= fp || next - fp > 0x100000) break;  // chain must climb sanely
            fp = static_cast<uintptr_t>(next);
        }
        if (!printed)
            std::fprintf(stderr, "    (no libil2cpp frames on the chain)\n");
        // Stack scan (fp-chain-independent): il2cpp's runtime C++ (the throw
        // path) omits frame pointers, so the fp-walk above dies before reaching
        // the MANAGED method that triggered the NRE. Scan the raw stack for any
        // 8-byte value inside libil2cpp's span -- managed AOT methods compiled
        // into libil2cpp will have left return addresses there. We print them
        // in stack order (innermost first); map each with script.json: runtime
        // helpers resolve to nothing, MANAGED methods (the il2cpp-compiled C#,
        // which sits above the runtime's own code in the image) name the C# call
        // chain to the throw. Dedup + cap.
        std::fprintf(stderr, "    -- stack scan (libil2cpp RVAs) --\n");
        uintptr_t sp = reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
        uint64_t last = 0; int sc = 0;
        for (uintptr_t a = sp; a < sp + 0x4000 && sc < 60; a += 8) {
            uint64_t v = *reinterpret_cast<uint64_t*>(a);
            if (v >= lo && v < hi && v != last) {
                std::fprintf(stderr, "    [scan] libil2cpp+0x%llx\n",
                             (unsigned long long)(v - g_il2cpp_base));
                last = v; ++sc;
            }
        }
        std::fflush(stderr);
    }

    // Optional managed stack trace (MACAPK_EXC_STACK) for the SERVICE-LEVEL
    // exceptions only (Uri/Format/NullRef/Argument/Web/Http/Aggregate/bare
    // Exception) -- names the il2cpp method that built the bad value (e.g. which
    // online-services / Addressables call built the empty URL or the invalid op
    // handle that stalls the menu). Skipped for the spammy Socket/FileNotFound.
    //
    // T1 (plan 4): walk the CURRENT thread's LIVE frames instead of
    // il2cpp_format_stack_trace(ex,...), which reads the exception's STORED trace
    // -- empty on release builds, which is why every [...] stack= line was blank
    // in the v1.0.326 run. We are on the throwing thread inside Exception::Raise,
    // so the live frame stack is exactly the throw call stack. get_frame_at fills
    // an Il2CppStackFrameInfo whose FIRST field is `const MethodInfo* method`
    // (debugger builds append fields), so we pass an oversized zeroed buffer and
    // read only [0]; name each via il2cpp_method_get_class -> class_get_name +
    // il2cpp_method_get_name. Falls back to the stored-trace formatter
    // (il2cpp_format_stack_trace) if the live API is not exported, and says so if
    // neither is.
    static const bool stack_on = std::getenv("MACAPK_EXC_STACK") != nullptr;
    static std::atomic<int> stk{0};
    if (stack_on && cname && stk.load(std::memory_order_relaxed) < 16 &&
        (std::strstr(cname, "Uri") || std::strstr(cname, "Format") ||
         std::strstr(cname, "NullReference") || std::strstr(cname, "Argument") ||
         std::strstr(cname, "Web") || std::strstr(cname, "Http") ||
         std::strstr(cname, "Aggregate") || std::strcmp(cname, "Exception") == 0)) {
        stk.fetch_add(1, std::memory_order_relaxed);
        typedef int32_t (*depth_t)(void);
        typedef int32_t (*frameat_t)(int32_t, void*);
        typedef void*   (*mcls_t)(void*);
        typedef const char* (*mname_t)(void*);
        depth_t   f_depth = (depth_t)(uintptr_t)g_il2cpp_exc_stkdepth;
        frameat_t f_frame = (frameat_t)(uintptr_t)g_il2cpp_exc_frameat;
        mcls_t    f_mcls  = (mcls_t)(uintptr_t)g_il2cpp_exc_methcls;
        mname_t   f_mname = (mname_t)(uintptr_t)g_il2cpp_exc_methname;
        if (f_depth && f_frame && f_mname) {
            int32_t depth = f_depth();
            std::fprintf(stderr, "[il2cpp-exc #%d] stack(live, depth=%d, top-down)=\n", n, depth);
            // Frame 0 is the outermost (entry); depth-1 is the innermost (throw
            // site). Print top-down (innermost first), capped to the nearest 40.
            int32_t lo = depth > 40 ? depth - 40 : 0;
            for (int32_t i = depth - 1; i >= lo; --i) {
                alignas(8) unsigned char fb[128];
                std::memset(fb, 0, sizeof(fb));
                if (!f_frame(i, fb)) continue;
                void* method = *reinterpret_cast<void**>(fb);  // Il2CppStackFrameInfo.method
                if (!method) continue;
                const char* mn = f_mname(method);
                const char* cn = nullptr;
                if (f_mcls) { void* mk = f_mcls(method); if (mk) cn = gcn(mk); }
                std::fprintf(stderr, "    at %s::%s\n", cn ? cn : "?", mn ? mn : "?");
            }
        } else if (g_il2cpp_exc_fmtstk) {
            fmt_t fmt_stk = (fmt_t)(uintptr_t)g_il2cpp_exc_fmtstk;
            static char sbuf[4096]; sbuf[0] = 0;
            fmt_stk(ex, sbuf, (int)sizeof(sbuf)); sbuf[sizeof(sbuf) - 1] = 0;
            std::fprintf(stderr, "[il2cpp-exc #%d] stack(stored)=\n%s\n", n, sbuf);
        } else {
            std::fprintf(stderr, "[il2cpp-exc #%d] stack: this libil2cpp exports neither "
                         "the live-frame API nor il2cpp_format_stack_trace\n", n);
        }
        std::fflush(stderr);
    }
    reentry.store(0, std::memory_order_release);
}

__asm__(
"   .text\n   .p2align 2\n"
// F3 GENERIC exc hook: same save/log/restore, but NO hardcoded displaced insn --
// returns through g_exc_raise_cont (a runtime [displaced insn][b Raise+4] stub),
// so it works on ANY libil2cpp build (Exception::Raise found via the EXPORTED
// il2cpp_raise_exception). x0 = the Il2CppException* (Raise's arg), preserved.
"   .globl _macapk_il2cpp_exc_tramp_gen\n"
"_macapk_il2cpp_exc_tramp_gen:\n"
"   sub  sp, sp, #160\n"
"   stp  x0,x1,[sp,#0]\n   stp x2,x3,[sp,#16]\n   stp x4,x5,[sp,#32]\n"
"   stp  x6,x7,[sp,#48]\n  stp x8,x9,[sp,#64]\n   stp x10,x11,[sp,#80]\n"
"   stp  x12,x13,[sp,#96]\n stp x14,x15,[sp,#112]\n stp x16,x17,[sp,#128]\n"
"   stp  x18,x30,[sp,#144]\n"
"   ldr  x1, [sp, #152]\n"                      // x1 = saved x30 = Raise's return = throw site
"   bl _macapk_il2cpp_exc_log\n"                // x0 = the Il2CppException*
"   ldp  x0,x1,[sp,#0]\n   ldp x2,x3,[sp,#16]\n   ldp x4,x5,[sp,#32]\n"
"   ldp  x6,x7,[sp,#48]\n  ldp x8,x9,[sp,#64]\n   ldp x10,x11,[sp,#80]\n"
"   ldp  x12,x13,[sp,#96]\n ldp x14,x15,[sp,#112]\n ldp x16,x17,[sp,#128]\n"
"   ldp  x18,x30,[sp,#144]\n"
"   add  sp, sp, #160\n"
"   adrp x16, _g_exc_raise_cont@PAGE\n"
"   ldr  x16, [x16, _g_exc_raise_cont@PAGEOFF]\n   br x16\n"
);

// Called from the nativeRender wrap (bionic_shim.cpp) once per frame on
// UnityMain, AFTER the real nativeRender returns (the frame boundary). dt is
// the wall-clock delta the wrap measures; the engine's per-director time
// advance uses the graph's own timing, so dt is informational for logging.
extern "C" void __macapk_arm64_unity_director_tick(double dt) {
    static std::atomic<bool> checked_env{false};
    static std::atomic<bool> disabled{false};
    if (!checked_env.exchange(true)) {
        if (std::getenv("MACAPK_DIRECTOR_OFF")) {
            disabled.store(true, std::memory_order_release);
            std::fprintf(stderr,
                "[director] MACAPK_DIRECTOR_OFF set -- per-frame "
                "DirectorManager update DISABLED\n");
        }
    }
    if (disabled.load(std::memory_order_acquire)) return;

    uint64_t base = g_director_unity_base;
    if (!base) return;  // not a Unity guest (e.g. cocos/OpenGD) -> inert
    if (g_director_layout_ok.load(std::memory_order_acquire) != 1) return;

    // RE DIAGNOSTIC (MACAPK_PLOOP_DUMP, default off): the player-loop node table
    // holds no-arg node wrapper fn-ptrs (the Director phase thunks are among
    // them). Once the engine has built the loop (~a few seconds in), scan the
    // writable LOAD span for a qword == base+director_node0 (a Director thunk we
    // located structurally) to find the table, then dump the surrounding qwords
    // so we can RE the entry stride + locate the coroutine/scene node wrappers
    // (which have no static caller / no relocation pointing at them). Annotates
    // any .text-pointer qword as +<off> so known fns are recognizable.
    {
        static std::atomic<int> ploop_done{0};
        if (std::getenv("MACAPK_PLOOP_DUMP") && g_director_node0_off &&
            g_unity_rw_hi > g_unity_rw_lo) {
            static std::atomic<uint64_t> pc{0};
            uint64_t k = pc.fetch_add(1);
            if (k >= 180 && !ploop_done.exchange(1)) {
                uint64_t want0 = base + g_director_node0_off;
                uint64_t want1 = base + g_director_node0_off + 16;
                uint64_t want2 = base + g_director_node0_off + 32;
                uint64_t exec_end = base + g_unity_mapped_size;
                std::fprintf(stderr, "[ploop] scanning RW [+%#llx,+%#llx) for director thunks "
                    "(+%#llx/+16/+32)\n", (unsigned long long)g_unity_rw_lo,
                    (unsigned long long)g_unity_rw_hi, (unsigned long long)g_director_node0_off);
                int found = 0;
                for (uint64_t off = g_unity_rw_lo; off + 8 <= g_unity_rw_hi && found < 6; off += 8) {
                    uint64_t v = 0;
                    if (guest_safe_read(base + off, &v, 8) != 8) continue;
                    if (v != want0 && v != want1 && v != want2) continue;
                    ++found;
                    std::fprintf(stderr, "[ploop] HIT @RW+%#llx = director thunk +%#llx ; entry region:\n",
                        (unsigned long long)off, (unsigned long long)(v - base));
                    for (int64_t j = -8; j <= 16; ++j) {
                        uint64_t a = base + off + (uint64_t)(j * 8), q = 0;
                        if (guest_safe_read(a, &q, 8) != 8) continue;
                        char ann[48]; ann[0] = 0;
                        if (q >= base && q < exec_end)
                            std::snprintf(ann, sizeof(ann), "  -> .text+%#llx", (unsigned long long)(q - base));
                        else if (q >= base && q < base + g_unity_mapped_size + 0x40000000ull)
                            std::snprintf(ann, sizeof(ann), "  (img/heap)");
                        std::fprintf(stderr, "[ploop]   +%#llx [%+lld*8] = %#llx%s\n",
                            (unsigned long long)(off + (uint64_t)(j * 8)), (long long)j,
                            (unsigned long long)q, ann);
                    }
                }
                if (!found) std::fprintf(stderr, "[ploop] no director-thunk pointer found in RW span\n");
                std::fflush(stderr);
            }
        }
    }

    // Resolve + cache the global DirectorManager singleton. The node thunks
    // load it directly from the global at base+g_director_manager_off (the
    // [page+OFF] the structural scan extracted), so we read the pointer the same
    // way. It may be NULL until the engine finishes scene-manager init; skip
    // those early frames.
    uint64_t mgr = g_director_manager.load(std::memory_order_relaxed);
    if (!mgr) {
        guest_safe_read(base + g_director_manager_off, &mgr, 8);
        if (!mgr) return;
        g_director_manager.store(mgr, std::memory_order_relaxed);
        std::fprintf(stderr,
            "[director] DirectorManager singleton = %#llx (libunity+%#llx)"
            " -- driving per-frame Director update\n",
            (unsigned long long)mgr, (unsigned long long)g_director_manager_off);
    }

    // Run the DirectorManager per-phase update for the Director player-loop
    // nodes the streamlined loop omits. DirectorManager::Update(manager, phase),
    // discovered structurally (base+g_director_update_off). Phases:
    //   0 = Update.DirectorUpdate (the main per-frame Timeline/graph eval)
    //   1 = PreLateUpdate.DirectorUpdateAnimation{Begin,End}
    //   2 = PreLateUpdate.DirectorDeferredEvaluate
    // Run the same set the engine's nodes run, in order, every frame.
    typedef void (*dm_update_t)(uint64_t mgr, uint32_t phase);
    dm_update_t dm_update = (dm_update_t)(uintptr_t)(base + g_director_update_off);
    static std::atomic<uint64_t> n{0};
    uint64_t c = n.fetch_add(1);
    if (c < 3 || (c % 600) == 0)
        std::fprintf(stderr,
            "[director] tick #%llu dt=%.4f mgr=%#llx -> DirectorUpdate "
            "phases 0,1,2\n",
            (unsigned long long)c, dt, (unsigned long long)mgr);
    // DIAGNOSTIC (MACAPK_DIRECTOR_DUMP, default off): periodically dump the
    // DirectorManager's registered-graph bucket heads + the deferred list head
    // so we can see whether any PlayableGraph is actually tracked (the manager
    // tracks all played graphs via intrusive lists at mgr+0xa0/0xc0/0xe0 [the
    // phase buckets the 0x7fcc8c iterator walks] and mgr+0x368/0x370 [the
    // pending-change list head/tail]). count nonzero => graphs are being
    // evaluated; all-zero => the splash's graph was never registered with this
    // manager (a registration gap, not an evaluation gap).
    if (std::getenv("MACAPK_DIRECTOR_DUMP") && (c < 3 || (c % 600) == 0)) {
        uint64_t b_a0 = 0, b_c0 = 0, b_e0 = 0, l_head = 0, l_tail = 0;
        guest_safe_read(mgr + 0xa0, &b_a0, 8);
        guest_safe_read(mgr + 0xc0, &b_c0, 8);
        guest_safe_read(mgr + 0xe0, &b_e0, 8);
        guest_safe_read(mgr + 0x370, &l_head, 8);
        guest_safe_read(mgr + 0x368, &l_tail, 8);
        std::fprintf(stderr,
            "[director-dump] #%llu bucket[0xa0]=%#llx [0xc0]=%#llx [0xe0]=%#llx "
            "list head[0x370]=%#llx self+0x368=%#llx (head==self+0x368 => empty)\n",
            (unsigned long long)c, (unsigned long long)b_a0,
            (unsigned long long)b_c0, (unsigned long long)b_e0,
            (unsigned long long)l_head, (unsigned long long)(mgr + 0x368));
    }
    dm_update(mgr, 0);
    dm_update(mgr, 1);
    dm_update(mgr, 2);
}

// ⚠ Only under MACAPK_REGISTERNATIVES_VERBATIM=0. The "omits" premise below is
// REFUTED (changelog v1.0.322 census: the engine drives this slot itself); the
// node it calls (Crossy 0x712364) self-gates on the same "player running" flag
// as the engine's own nodes, so it could only do work in states where the
// engine's own executor runs it too.
// Called from the nativeRender wrap once per frame on UnityMain (right after
// the DirectorManager tick). Drives Unity's NATIVE COROUTINE PUMP --
// DelayedCallManager::Update -- which Mac.apk's streamlined nativeRender loop
// omits. Mac.apk drives nativeRender = a SUBSET of player-loop nodes (verified:
// RunOneFrame=0 / UpdatePreloading=0 calls per frame); the native
// ScriptRunDelayedDynamicFrameRate node, which IS DelayedCallManager::Update, is
// not among them. Without it, NO Unity coroutine ever resumes past a yield, so
// BootController.Start()'s boot coroutine (the emulator-confirmed chain
// <Start>d__14 -> Init*Async d__19/d__21 -> <LoadGameScene>d__17 -> the menu)
// stalls at its first yield. Each coroutine step is the managed
// SetupCoroutine.InvokeMoveNext, called by the native Coroutine::Run that
// DelayedCallManager::Update fires for each due entry. Inert on non-Unity guests
// (cocos/OpenGD never set the libunity base).
//
// HOW WE FIND IT GENERICALLY (no build-specific vaddrs). Unity's flattened
// player-loop update-function table is a runtime-built array of no-arg node
// thunks; each thunk self-gates ("is the player running?"), loads its manager
// singleton, and tail-calls manager->Update. The Director phase-0 thunk we
// located structurally (g_director_node0_off) is one entry in that table; in
// Unity's stable default Update-phase ordering the ScriptRunDelayedDynamicFrameRate
// (coroutine) node sits immediately before Update.DirectorUpdate. So: scan the
// writable LOAD span for the qword == base+director_node0 to find the table slot,
// take the preceding node-thunk entry (validated: a .text pointer whose first
// instruction is a thunk prologue), and CALL it (no-arg) each frame. The thunk
// itself loads its manager + self-gates, so we pass nothing and never deref a
// manager ourselves. Generic across Unity builds; inert on non-Unity guests and
// on any build where the table/thunk can't be validated. Default-ON, opt out
// with MACAPK_CORO_PUMP=0. Verified on Crossy: resolves to the same coroutine
// node the engine drives (the entry before DirectorUpdate).
static std::atomic<uint64_t> g_coro_node_fn{0};   // resolved coroutine node thunk (abs addr); 1 = "not found"
extern "C" void __macapk_arm64_unity_coroutine_pump(double dt) {
    (void)dt;
    static std::atomic<int> mode{-1};  // -1 unchecked, 0 off, 1 on
    int m = mode.load(std::memory_order_acquire);
    if (m < 0) {
        const char* e = std::getenv("MACAPK_CORO_PUMP");
        int v = (e && e[0] == '0') ? 0 : 1;   // default ON
        mode.store(v, std::memory_order_release);
        m = v;
        std::fprintf(stderr, "[coro-pump] native coroutine pump %s\n",
                     v ? "ENABLED (player-loop coroutine node, generic)"
                       : "disabled (MACAPK_CORO_PUMP=0)");
    }
    if (m != 1) return;

    uint64_t base = g_director_unity_base;
    if (!base) return;                                   // non-Unity guest -> inert
    if (g_director_layout_ok.load(std::memory_order_acquire) != 1) return;
    if (!g_director_node0_off || g_unity_rw_hi <= g_unity_rw_lo) return;

    uint64_t fn = g_coro_node_fn.load(std::memory_order_relaxed);
    if (fn == 0) {
        // One-time discovery: locate the Director thunk in the runtime table, take
        // the preceding self-gated node thunk (the coroutine node).
        uint64_t resolved = 1;   // 1 = "searched, not found" sentinel
        uint64_t want = base + g_director_node0_off;
        uint64_t img_lo = base, img_hi = base + g_unity_mapped_size;
        for (uint64_t off = g_unity_rw_lo; off + 8 <= g_unity_rw_hi; off += 8) {
            uint64_t v = 0;
            if (guest_safe_read(base + off, &v, 8) != 8 || v != want) continue;
            // Found the Director slot; walk back over padding (0) to the previous
            // node-thunk pointer and validate it is a thunk (prologue check).
            for (int back = 1; back <= 6; ++back) {
                uint64_t a = base + off - (uint64_t)back * 8, p = 0;
                if (a < base + g_unity_rw_lo) break;
                if (guest_safe_read(a, &p, 8) != 8) break;
                if (p == 0) continue;                    // padding
                if (p < img_lo || p >= img_hi) break;    // not an image pointer -> stop
                uint32_t first = 0;
                if (guest_safe_read(p, &first, 4) != 4) break;
                // Node-thunk prologues seen across builds: `str x30,[sp,#-0x10]!`
                // (0xf81f0ffe) or `stp x30,x19,[sp,#-0x10]!` (0xa9bf4ffe).
                if (first == 0xf81f0ffeu || first == 0xa9bf4ffeu) { resolved = p; }
                break;                                   // only the immediate predecessor
            }
            break;
        }
        g_coro_node_fn.store(resolved, std::memory_order_release);
        std::fprintf(stderr, resolved > 1
            ? "[coro-pump] coroutine node thunk = libunity+%#llx (table-anchored) -- driving\n"
            : "[coro-pump] coroutine node thunk NOT found (table/prologue unvalidated) -- inert\n",
            (unsigned long long)(resolved > 1 ? resolved - base : 0));
        fn = resolved;
    }
    if (fn <= 1) return;   // not found -> inert

    typedef void (*node_fn_t)(void);
    node_fn_t node = (node_fn_t)(uintptr_t)fn;
    static std::atomic<uint64_t> n{0};
    uint64_t c = n.fetch_add(1);
    if (c < 3 || (c % 600) == 0)
        std::fprintf(stderr, "[coro-pump] tick #%llu -> coroutine node libunity+%#llx\n",
            (unsigned long long)c, (unsigned long long)(fn - base));
    node();
}

// ===================================================================
// D0 -- player-loop node census (MACAPK_PLOOP_CENSUS, diagnostic)
// ===================================================================
// The keystone measurement: which player-loop Update nodes does the reduced
// nativeRender actually drive on THIS build/app, and which must Mac.apk drive?
// (Per-build: changelog v1.0.254 showed Crossy's engine ran BehaviourUpdate +
// scene integration natively; do NOT extrapolate to 2022.3 builds.) Anchored on
// the structurally-found Director node thunk, we map the whole flattened
// update-function table (Unity's executed player loop = an array of no-arg node
// thunks), pointer-swap every slot to a static-pool counting wrapper, and log
// per-slot ENGINE-invocation counts. The g_pl_engine_seen latch also becomes
// F1's double-execution guard ("engine ran this recently -> never drive it").
// Zero hardcodes, no runtime codegen (the wrappers are compile-time template
// instantiations, each a distinct void(void) address). Default-off.
namespace {
constexpr int kPloopMax = 256;
std::atomic<uint64_t> g_pl_count[kPloopMax];
std::atomic<uint64_t> g_pl_engine_seen[kPloopMax];   // last frame the engine invoked slot i
void*    g_pl_orig[kPloopMax]      = {};             // original node fn ptr per slot
uint64_t g_pl_slot_addr[kPloopMax] = {};            // RW address of slot i
uint64_t g_pl_node_off[kPloopMax]  = {};            // libunity offset of the node fn
int      g_pl_n = 0;                                 // instrumented slot count
std::atomic<uint64_t> g_pl_frame{0};
int      g_pl_director_idx = -1;                     // index of the Director slot
std::atomic<int> g_pl_rebuilds{0};

// Forward the original node's x0 return UNCHANGED. Unity's flat player-loop
// array interleaves no-arg update functions (void) with FixedUpdate LOOP-
// CONDITION functions (bool in x0); a void wrapper that dropped the return made
// the executor read a garbage condition -> SIGBUS. Returning the callee's x0
// (uint64_t) is transparent for both: a void node's caller ignores x0, a
// condition node's caller reads the real bool. Nodes are no-arg, so we pass
// none. x8 (indirect-result) and FP returns are not used by these void/bool
// nodes, so x0-forwarding is sufficient.
inline uint64_t ploop_hit(int i) {
    g_pl_count[i].fetch_add(1, std::memory_order_relaxed);
    g_pl_engine_seen[i].store(g_pl_frame.load(std::memory_order_relaxed),
                              std::memory_order_relaxed);
    void* o = g_pl_orig[i];
    if (!o) return 0;
    return ((uint64_t(*)(void))o)();
}
// 256 distinct no-arg counting wrappers (macro-generated so each has a unique
// address; no runtime codegen, and unlike a template pool this is valid inside
// the surrounding extern "C" linkage block). plw_0xIJ -> ploop_hit(0xIJ); the
// g_pl_wraps[] table is filled in the same 0x00..0xff order so g_pl_wraps[i]
// counts slot i. Wrappers return uint64_t to forward the node's x0 (see above).
#define PLW(n)  static uint64_t plw_##n(void) { return ploop_hit(n); }
#define PLW_ROW(h) \
    PLW(0x##h##0) PLW(0x##h##1) PLW(0x##h##2) PLW(0x##h##3) \
    PLW(0x##h##4) PLW(0x##h##5) PLW(0x##h##6) PLW(0x##h##7) \
    PLW(0x##h##8) PLW(0x##h##9) PLW(0x##h##a) PLW(0x##h##b) \
    PLW(0x##h##c) PLW(0x##h##d) PLW(0x##h##e) PLW(0x##h##f)
PLW_ROW(0) PLW_ROW(1) PLW_ROW(2) PLW_ROW(3) PLW_ROW(4) PLW_ROW(5) PLW_ROW(6) PLW_ROW(7)
PLW_ROW(8) PLW_ROW(9) PLW_ROW(a) PLW_ROW(b) PLW_ROW(c) PLW_ROW(d) PLW_ROW(e) PLW_ROW(f)
#define PLA(n)  plw_##n,
#define PLA_ROW(h) \
    PLA(0x##h##0) PLA(0x##h##1) PLA(0x##h##2) PLA(0x##h##3) \
    PLA(0x##h##4) PLA(0x##h##5) PLA(0x##h##6) PLA(0x##h##7) \
    PLA(0x##h##8) PLA(0x##h##9) PLA(0x##h##a) PLA(0x##h##b) \
    PLA(0x##h##c) PLA(0x##h##d) PLA(0x##h##e) PLA(0x##h##f)
uint64_t (*const g_pl_wraps[kPloopMax])(void) = {
    PLA_ROW(0) PLA_ROW(1) PLA_ROW(2) PLA_ROW(3) PLA_ROW(4) PLA_ROW(5) PLA_ROW(6) PLA_ROW(7)
    PLA_ROW(8) PLA_ROW(9) PLA_ROW(a) PLA_ROW(b) PLA_ROW(c) PLA_ROW(d) PLA_ROW(e) PLA_ROW(f)
};
#undef PLW
#undef PLW_ROW
#undef PLA
#undef PLA_ROW

inline bool pl_is_bl(uint32_t w) { return (w & 0xfc000000u) == 0x94000000u; }
// A genuine player-loop node thunk is SMALL and self-contained, and either:
//   (a) gated style -- within its first 8 insns it `bl`s the shared "is the
//       player running?" gate (a 3-insn flag reader every node calls), or
//   (b) Director style -- it tail-`b`s DirectorManager::Update.
// Crucially this EXCLUDES arg-taking helpers (e.g. a 2-arg struct-copy fn at
// libunity+0x710xxx that sits in adjacent fn-ptr data): swapping their slot to
// our no-arg wrapper clobbered x0/x1 and SIGBUS'd. gate_off / dir_update_off
// are base-relative; p is absolute. 0 disables that arm of the check.
bool pl_thunk_ok(uint64_t base, uint64_t p, uint64_t gate_off, uint64_t dir_update_off) {
    for (int k = 0; k < 8; ++k) {
        uint32_t w = 0;
        if (guest_safe_read(p + (uint64_t)k * 4, &w, 4) != 4) return false;
        uint64_t pc = p + (uint64_t)k * 4;
        if (pl_is_bl(w)) {
            if (gate_off && dn_b_target(pc, w) == base + gate_off) return true;
        } else if (dn_is_b(w)) {
            if (dir_update_off && dn_b_target(pc, w) == base + dir_update_off) return true;
            return false;          // tail-branch elsewhere -> not a node we trust
        } else if (w == 0xd65f03c0u) {
            return false;          // ret before gate/dir-update -> not a node
        }
    }
    return false;
}
// The shared gate fn (base-relative) = the first `bl` target of a gated node.
uint64_t pl_find_gate(uint64_t base, uint64_t img_size, uint64_t node_fn) {
    if (node_fn < base || node_fn >= base + img_size) return 0;
    for (int k = 0; k < 8; ++k) {
        uint32_t w = 0;
        if (guest_safe_read(node_fn + (uint64_t)k * 4, &w, 4) != 4) break;
        if (pl_is_bl(w)) return dn_b_target(node_fn + (uint64_t)k * 4, w) - base;
        if (w == 0xd65f03c0u) break;
    }
    return 0;
}

// Discover the table (Director slot + walk both directions over node-fn slots)
// and pointer-swap each slot to its counting wrapper. Idempotent until rebuild.
void ploop_census_install(uint64_t base) {
    uint64_t img_size = g_unity_mapped_size;
    uint64_t want = base + g_director_node0_off;
    uint64_t dslot = 0;
    for (uint64_t off = g_unity_rw_lo; off + 8 <= g_unity_rw_hi; off += 8) {
        uint64_t v = 0;
        if (guest_safe_read(base + off, &v, 8) != 8) continue;
        if (v == want) { dslot = base + off; break; }
    }
    if (!dslot) { std::fprintf(stderr, "[ploop-census] director slot not found\n"); return; }

    // Find the shared "is-the-player-running?" gate fn from a gated node thunk
    // near the Director slot (str x30 / stp x30,x19 prologue). The gate is the
    // discriminator that keeps us from instrumenting arg-taking helpers.
    uint64_t gate_off = 0;
    for (int d = -8; d <= 8 && !gate_off; ++d) {
        if (d == 0) continue;
        uint64_t sv = 0;
        if (guest_safe_read(dslot + (int64_t)d * 8, &sv, 8) != 8) continue;
        if (sv < base || sv >= base + img_size) continue;
        uint32_t f0 = 0; guest_safe_read(sv, &f0, 4);
        if (f0 == 0xf81f0ffeu || f0 == 0xa9bf4ffeu) gate_off = pl_find_gate(base, img_size, sv);
    }
    uint64_t dir_update = g_director_update_off;

    std::vector<uint64_t> slots;   // RW slot addresses, will end in increasing order
    auto node_ok = [&](uint64_t v) {
        if (v < base || v >= base + img_size) return false;
        return pl_thunk_ok(base, v, gate_off, dir_update);
    };
    auto collect = [&](int dir) {
        int gap = 0;
        for (uint64_t a = dslot + (uint64_t)(dir * 8); ; a += (uint64_t)(dir * 8)) {
            if (a < base + g_unity_rw_lo || a + 8 > base + g_unity_rw_hi) break;
            if ((int)slots.size() >= kPloopMax) break;
            uint64_t v = 0;
            if (guest_safe_read(a, &v, 8) != 8) break;
            if (v == 0) { if (++gap > 4) break; continue; }
            if (node_ok(v)) { gap = 0; slots.push_back(a); }
            else { if (++gap > 4) break; }
        }
    };
    collect(-1);
    std::reverse(slots.begin(), slots.end());
    int dpos = (int)slots.size();
    slots.push_back(dslot);
    collect(+1);

    int n = (int)slots.size(); if (n > kPloopMax) n = kPloopMax;
    for (int i = 0; i < n; ++i) {
        uint64_t sa = slots[i];
        uint64_t orig = 0; guest_safe_read(sa, &orig, 8);
        g_pl_slot_addr[i] = sa;
        g_pl_orig[i]      = (void*)(uintptr_t)orig;
        g_pl_node_off[i]  = orig - base;
        g_pl_count[i].store(0); g_pl_engine_seen[i].store(0);
        *(void* volatile*)(uintptr_t)sa = (void*)g_pl_wraps[i];   // swap to wrapper
    }
    g_pl_director_idx = dpos < n ? dpos : -1;
    g_pl_n = n;
    std::fprintf(stderr,
        "[ploop-census] instrumented %d slots, director at idx %d (libunity+%#llx); "
        "table RW [%#llx..%#llx]\n",
        n, g_pl_director_idx, (unsigned long long)g_director_node0_off,
        (unsigned long long)(n ? g_pl_slot_addr[0] - base : 0),
        (unsigned long long)(n ? g_pl_slot_addr[n - 1] - base : 0));
}
}  // namespace

extern "C" void __macapk_arm64_unity_ploop_census(double dt) {
    (void)dt;
    static std::atomic<int> mode{-1};
    int m = mode.load(std::memory_order_acquire);
    if (m < 0) { m = std::getenv("MACAPK_PLOOP_CENSUS") ? 1 : 0; mode.store(m, std::memory_order_release);
                 if (m) std::fprintf(stderr, "[ploop-census] ENABLED\n"); }
    if (m != 1) return;
    uint64_t base = g_director_unity_base;
    if (!base || g_director_layout_ok.load(std::memory_order_acquire) != 1 || !g_director_node0_off) return;
    if (g_pl_n == 0) {
        static std::atomic<uint64_t> warm{0};
        if (warm.fetch_add(1) < 120) return;   // let the engine build its loop first
        ploop_census_install(base);
        if (g_pl_n == 0) { mode.store(0, std::memory_order_release); }  // give up if not found
        return;
    }
    uint64_t f = g_pl_frame.fetch_add(1) + 1;
    // Rebuild detection: if the Director slot no longer holds our wrapper, the
    // app rebuilt the player loop (PlayerLoop.SetPlayerLoop / UniTask) -> rescan.
    if (g_pl_director_idx >= 0) {
        uint64_t cur = 0; guest_safe_read(g_pl_slot_addr[g_pl_director_idx], &cur, 8);
        if (cur != (uint64_t)(uintptr_t)g_pl_wraps[g_pl_director_idx]) {
            g_pl_rebuilds.fetch_add(1);
            g_pl_n = 0;   // re-install next frame
            std::fprintf(stderr, "[ploop-census] player loop rebuilt (#%d) -- re-instrumenting\n",
                         g_pl_rebuilds.load());
            return;
        }
    }
    if (f == 120 || (f % 600) == 0) {
        std::fprintf(stderr, "[ploop-census] frame %llu, %d slots, %d rebuilds:\n",
                     (unsigned long long)f, g_pl_n, g_pl_rebuilds.load());
        for (int i = 0; i < g_pl_n; ++i)
            std::fprintf(stderr, "[ploop-census]   slot %2d libunity+%#-9llx engine_calls=%-8llu last_seen=%llu%s\n",
                         i, (unsigned long long)g_pl_node_off[i],
                         (unsigned long long)g_pl_count[i].load(),
                         (unsigned long long)g_pl_engine_seen[i].load(),
                         i == g_pl_director_idx ? "  <-- Director" : "");
        std::fflush(stderr);
    }
}

// SCENE INTEGRATION DRIVER (MACAPK_SCENE_INTEGRATE). Ground truth (v1.0.253):
// the scene-dump shows a scene sitting at state 2 (Loaded, NOT Integrated) on
// Mac.apk -- the async scene-load COMPLETED but the per-frame integration that
// flips it 2->3 (IntegrateMainThread -> SceneActivationPass -> AwakeFromLoad,
// reached from nativeRender's worker on real Android) never runs, so its objects
// never activate -> no boot -> no menu. This drives the engine's own
// SceneActivationPass (0x6f8ad4, NO args: promotes every Loaded scene 2->3 + runs
// AwakeFromLoad on its object graph) ONLY WHEN a state-2 scene is actually present
// -- so it self-limits (after promotion the scene is state 3, has_s2 goes false,
// it stops) and runs ~once per loaded scene, matching real Android's ~3 calls.
// The v1.0.246 every-frame-unconditional version black-screened by re-touching
// already-integrated scenes; gating on a live state-2 scene avoids that. Capped
// for safety. Byte-checked, libunity-only, default-off.
extern "C" void __macapk_arm64_unity_scene_integrate(double dt) {
    (void)dt;
    static std::atomic<int> mode{-1};
    int m = mode.load(std::memory_order_acquire);
    if (m < 0) {
        m = std::getenv("MACAPK_SCENE_INTEGRATE") ? 1 : 0;
        mode.store(m, std::memory_order_release);
        std::fprintf(stderr, "[scene-int] state-2 scene integration driver %s\n",
                     m ? "ENABLED" : "disabled (MACAPK_SCENE_INTEGRATE=1)");
    }
    if (m != 1) return;
    uint64_t base = g_director_unity_base;
    if (!base) return;
    static std::atomic<int> vf{-1};
    if (vf.load(std::memory_order_acquire) < 0) {
        uint32_t insn = 0; guest_safe_read(base + 0x720134, &insn, 4);
        vf.store(insn == 0xd101c3ff ? 1 : 0, std::memory_order_release);
        if (insn != 0xd101c3ff)
            std::fprintf(stderr, "[scene-int] 0x720134 prologue %#x != 0xd101c3ff -- DISABLED\n", insn);
    }
    if (vf.load(std::memory_order_acquire) != 1) return;
    // ENGINE-CORRECT driver: PreloadManager::UpdatePreloadingSingleStep (0x720134)
    // -- the real per-frame preload/integration step the engine reaches from
    // nativeRender (0x711164 -> 0x710e88 -> 0x72a00c -> 0x720134 -> stepper 0x71f810
    // -> IntegrateMainThread 0x71ec9c -> SceneActivationPass), which Mac.apk's
    // streamlined nativeRender omits. It drains the load queues and integrates any
    // COMPLETED (state-2/Loaded) scene IN-CONTEXT (unlike the raw SceneActivationPass
    // which corrupted the manager). Signature: x0 = PreloadManager* = GetInstance()
    // (0x71dfb8). GUARD: if pending-jobs [PM+0x260] > 0 a load is still in flight and
    // 0x720134 would FUTEX-WAIT on the worker (0x7202e4) and block UnityMain -- skip
    // those frames so we only integrate already-completed loads, never block.
    typedef void* (*gi_t)(void);
    gi_t gi = (gi_t)(uintptr_t)(base + 0x71dfb8);
    void* pm = gi();
    if (!pm) return;
    uint32_t pend = 0; guest_safe_read((uint64_t)pm + 0x260, &pend, 4);
    if (pend != 0) return;   // a load is in flight; let the worker finish (no block)
    typedef void (*step_t)(void*);
    step_t step = (step_t)(uintptr_t)(base + 0x720134);
    static std::atomic<uint64_t> n{0};
    uint64_t c = n.fetch_add(1);
    if (c < 5 || (c % 300) == 0) {
        // PreloadManager queue + op-state dump: pending [PM+0x328]/[PM+0x338]cnt,
        // active/loading [PM+0x348]/[PM+0x358]cnt; op state [op+0x8] (>1=Loaded/ready),
        // [op+0x40] (IO-done flag), [op+0x425] (complete-latch). If active=0 the op was
        // dequeued (load done but never integrated); if op[+0x8]<=1 the op never
        // finished (worker stall); if op[+0x8]>1 but not integrated, the stepper is
        // not advancing it.
        uint32_t pcnt = 0, acnt = 0; uint64_t parr = 0, aarr = 0;
        guest_safe_read((uint64_t)pm + 0x328, &parr, 8);
        guest_safe_read((uint64_t)pm + 0x338, &pcnt, 4);
        guest_safe_read((uint64_t)pm + 0x348, &aarr, 8);
        guest_safe_read((uint64_t)pm + 0x358, &acnt, 4);
        uint64_t op0 = 0;
        if (aarr && acnt) guest_safe_read(aarr, &op0, 8);
        else if (parr && pcnt) guest_safe_read(parr, &op0, 8);
        uint32_t ost = 0xffffffff; uint8_t o40 = 0, o425 = 0;
        if (op0) { guest_safe_read(op0 + 0x8, &ost, 4);
                   guest_safe_read(op0 + 0x40, &o40, 1);
                   guest_safe_read(op0 + 0x425, &o425, 1); }
        std::fprintf(stderr,
            "[scene-int] #%llu pm=%p pend[+0x260]=%u | pending=%u active=%u op0=%#llx "
            "op[+0x8]=%u op[+0x40]=%u op[+0x425]=%u -> step(0x720134)\n",
            (unsigned long long)c, pm, pend, pcnt, acnt,
            (unsigned long long)op0, ost, o40, o425);
        // VTABLE RELOCATION CHECK (hypothesis 2): LoadSceneOperation's vtable slot
        // for IntegrateMainThread is at .data.rel.ro 0x164e900; after R_AARCH64_RELATIVE
        // it MUST equal base+0x71ec9c. If not, the stepper's blr[vtbl+0x70] calls the
        // wrong fn -> op dequeued WITHOUT integrating. Also dump the vtable base 0x164e7e0.
        uint64_t vt900 = 0, vt7e0 = 0;
        guest_safe_read(base + 0x164e900, &vt900, 8);
        guest_safe_read(base + 0x164e7e0, &vt7e0, 8);
        std::fprintf(stderr,
            "[scene-int] op-vtable[0x164e900]=%#llx want base+0x71ec9c=%#llx %s | "
            "vtable[0x164e7e0]=%#llx\n",
            (unsigned long long)vt900, (unsigned long long)(base + 0x71ec9c),
            (vt900 == base + 0x71ec9c) ? "CORRECT" : "MISRELOCATED??",
            (unsigned long long)vt7e0);
        std::fflush(stderr);
    }
    step(pm);
}

// Called from the nativeRender wrap once per frame on UnityMain. READ-ONLY
// engine render-state probe (MACAPK_RENDER_STATE, default off). The
// engine-correct question (per the nativeRender RE): does the per-frame
// UnityPlayer.nativeRender (worker 0x8ac244) take the FULL-frame path (runs the
// whole player loop -> scene integration + GameObject activation + coroutines)
// or short-circuit? That is gated by these libunity globals:
//   [0x1728000+0xa18] = player started/running (master gate; 0 => nativeRender
//                       bails immediately, nothing runs)
//   [0x1728000+0xa00] = first-frame-done / steady-state branch flag (tbz at
//                       0x8ac29c: bit0 CLEAR => full path; SET => reduced path)
//   [0x1728000+0xa24] = quit latch (1 => early-return)
//   [0x1728000+0xa28] = pending surface-changed event ptr
//   [0x1728000+0xa35] = pause/resume request
//   [0x1713000+0xba8] = GfxDevice* (NULL => full path short-circuits to
//                       present-only; needs nativeRecreateGfxState + a Surface)
// Reading these confirms WHICH lifecycle/state gap (player not started, no
// GfxDevice, stuck-paused) makes Mac.apk skip the full player loop -- the
// engine-correct root, vs hand-calling internal passes. Pure read, no mutation,
// libunity-only (inert on cocos).
extern "C" void __macapk_arm64_unity_render_state(double dt) {
    (void)dt;
    static std::atomic<int> mode{-1};
    int m = mode.load(std::memory_order_acquire);
    if (m < 0) {
        m = std::getenv("MACAPK_RENDER_STATE") ? 1 : 0;
        mode.store(m, std::memory_order_release);
    }
    if (m != 1) return;

    uint64_t base = g_director_unity_base;
    if (!base) return;  // non-Unity guest -> inert

    // Log every ~300 frames so we can see the flags evolve across the boot
    // (player-start, surface-create, first-frame, etc.).
    static std::atomic<uint64_t> n{0};
    uint64_t c = n.fetch_add(1);
    if (c >= 60 && (c < 65 || (c % 120) == 0)) {
        uint8_t a18 = 0, a00 = 0, a24 = 0, a35 = 0;
        uint64_t a28 = 0, gfx = 0;
        guest_safe_read(base + 0x1728a18, &a18, 1);
        guest_safe_read(base + 0x1728a00, &a00, 1);
        guest_safe_read(base + 0x1728a24, &a24, 1);
        guest_safe_read(base + 0x1728a35, &a35, 1);
        guest_safe_read(base + 0x1728a28, &a28, 8);
        guest_safe_read(base + 0x1713ba8, &gfx, 8);
        // THE INTEGRATION HANDSHAKE (engine-correct root candidate): scene
        // integration/activation runs only when nativeRender takes its INTEGRATION
        // branch (0x8ac4c8), gated by the "integration pending" flag
        // [GfxState(*(base+0x16a2b60)) + 0x14c], which the player-loop frame fn
        // 0x710800 RAISES (strh #0x101) as part of its present/render-thread
        // handshake. If Mac.apk's render interception prevents that handshake, the
        // flag never flips to 0x101 -> the integration branch never runs ->
        // [go+0x46] stays 0 -> no activation -> no boot. Watch whether intPend ever
        // becomes nonzero (0x101). gfxstate==0 or intPend always 0 => the root.
        uint64_t gfxstate = 0; uint16_t intpend = 0; uint8_t pausegate = 0;
        guest_safe_read(base + 0x16a2b60, &gfxstate, 8);
        if (gfxstate) {
            guest_safe_read(gfxstate + 0x14c, &intpend, 2);
            guest_safe_read(gfxstate + 0x14d, &pausegate, 1);
        }
        // GATE A (the ONLY guard on the flag-raise strh in FrameFn 0x71084c):
        // GetManager(index 0) = *(base+0x16bcb08) must be non-null, else FrameFn
        // early-returns and [GfxState+0x14c] is never raised. Read the manager
        // table: index 0 (the guard), index 1 (GfxState slot), index 8
        // (DelayedCallManager, known non-null) for sanity. If mgr0==0 => M1 root.
        uint64_t mgr0 = 0, mgr1 = 0, mgr8 = 0;
        guest_safe_read(base + 0x16bcb08, &mgr0, 8);
        guest_safe_read(base + 0x16bcb10, &mgr1, 8);
        guest_safe_read(base + 0x16bcb48, &mgr8, 8);
        std::fprintf(stderr,
            "[render-state] #%llu playerUp[+0xa18]=%d steady[+0xa00]=%d "
            "quit[+0xa24]=%d pauseReq[+0xa35]=%d pendingSurface[+0xa28]=%#llx "
            "GfxDevice=%#llx | GfxState=%#llx intPend[+0x14c]=%#x [+0x14d]=%#x "
            "| mgr[0]=%#llx mgr[1]=%#llx mgr[8]=%#llx (mgr[0]==0 => FrameFn GATE A "
            "early-out, flag never raised)\n",
            (unsigned long long)c, a18, a00, a24, a35,
            (unsigned long long)a28, (unsigned long long)gfx,
            (unsigned long long)gfxstate, intpend, pausegate,
            (unsigned long long)mgr0, (unsigned long long)mgr1,
            (unsigned long long)mgr8);
        // (The [integ-count] / [pm-count] / [bc-count] / [bm-update] / [awk-*] lines
        // printed the il2cpp diag kit's counters; deleted with it 2026-09-27.)
        extern std::atomic<uint64_t> g_exc_raise_count;
        std::fprintf(stderr, "[exc-raise] #%llu vm::Exception::Raise hits=%llu "
            "(0 => the Raise hook never fires; >0 => exceptions ARE raised)\n",
            (unsigned long long)c,
            (unsigned long long)g_exc_raise_count.load(std::memory_order_relaxed));
        // SCENE-LEVEL DUMP: SceneManager (*(base+0x1717b88)), scene array [mgr+0x8],
        // count [mgr+0x18]; per scene, state at [scene+0x9c] (2=Loaded, 3=Integrated).
        // A scene at state 2 = loaded but NOT integrated (integration bug); few
        // scenes total or no pending = the next load never started (upstream bug).
        // Array element layout unknown -> try BOTH: as Scene* (8B ptr) and dump raw.
        {
            uint64_t smgr = 0; guest_safe_read(base + 0x1717b88, &smgr, 8);
            uint64_t arr = 0; uint32_t scnt = 0;
            if (smgr) { guest_safe_read(smgr + 0x8, &arr, 8);
                        guest_safe_read(smgr + 0x18, &scnt, 4); }
            char sb[400]; int sblen = std::snprintf(sb, sizeof sb,
                "[scene-dump] #%llu SceneMgr=%#llx arr=%#llx count=%u scenes(ptr@0x9c)=[",
                (unsigned long long)c, (unsigned long long)smgr,
                (unsigned long long)arr, scnt);
            for (uint32_t i = 0; i < scnt && i < 10 && sblen < (int)sizeof(sb) - 40; i++) {
                uint64_t sc = 0; guest_safe_read(arr + (uint64_t)i * 8, &sc, 8);
                int32_t st = -1; if (sc) guest_safe_read(sc + 0x9c, &st, 4);
                sblen += std::snprintf(sb + sblen, sizeof(sb) - sblen,
                                       "%#llx:s%d ", (unsigned long long)sc, st);
            }
            std::fprintf(stderr, "%s]\n", sb);
            // raw bytes of arr[0] as a scene candidate (verify the layout once)
            if (arr) {
                uint64_t w[6] = {0};
                for (int k = 0; k < 6; k++) guest_safe_read(arr + (uint64_t)k * 8, &w[k], 8);
                std::fprintf(stderr,
                    "[scene-dump] arr raw qwords: %#llx %#llx %#llx %#llx %#llx %#llx\n",
                    (unsigned long long)w[0], (unsigned long long)w[1],
                    (unsigned long long)w[2], (unsigned long long)w[3],
                    (unsigned long long)w[4], (unsigned long long)w[5]);
            }
        }
        // PRELOADMANAGER OP-LIFECYCLE DUMP (read-only; correct offsets from the
        // v1.0.254 op-completion RE workflow). The async scene-load handshake:
        // dedicated loading pthread (started flag [PM+0x48]) pops PENDING
        // ([PM+0x328]/cnt[PM+0x338]) -> LOADING/active ([PM+0x348]/cnt[PM+0x358]),
        // deserializes (scene -> state 2), sets the integrate-ready latch
        // [op+0x40]=1, then the MAIN-thread driver (0x720134 -> stepper 0x71f810)
        // sees [op+0x40]==1 and dequeues+IntegrateMainThread atomically. Localizes
        // the orphan: op in LOADING with [op+0x40]==0 => worker never finished;
        // [op+0x40]==1 but no integrate => main driver never re-stepped; both queues
        // empty => op already consumed without integrating (impossible on async ->
        // means worker spawn / handback gap). [PM+0x48]==0 => loading thread never
        // spawned. Singleton read directly (no GetInstance call -> no side effects).
        {
            uint64_t pm = 0; guest_safe_read(base + 0x1716b30, &pm, 8);
            if (pm) {
                uint8_t started = 0, stopf = 0;
                uint32_t pcnt = 0, acnt = 0, semw = 0, pjobs = 0, pjob2 = 0, hb = 0;
                uint64_t parr = 0, aarr = 0;
                guest_safe_read(pm + 0x48, &started, 1);
                guest_safe_read(pm + 0x49, &stopf, 1);
                guest_safe_read(pm + 0x328, &parr, 8);
                guest_safe_read(pm + 0x338, &pcnt, 4);
                guest_safe_read(pm + 0x348, &aarr, 8);
                guest_safe_read(pm + 0x358, &acnt, 4);
                guest_safe_read(pm + 0xc0,  &semw, 4);
                guest_safe_read(pm + 0x260, &pjobs, 4);
                guest_safe_read(pm + 0x264, &pjob2, 4);
                guest_safe_read(pm + 0x220, &hb, 4);
                uint64_t aop = 0, pop = 0;
                if (aarr && acnt) guest_safe_read(aarr, &aop, 8);
                if (parr && pcnt) guest_safe_read(parr, &pop, 8);
                uint64_t op = aop ? aop : pop;
                uint8_t o40 = 0, o425 = 0; uint32_t o8 = 0, omode = 0; float oprog = 0;
                uint64_t osc = 0; int32_t oscst = -1;
                if (op) {
                    guest_safe_read(op + 0x40, &o40, 1);
                    guest_safe_read(op + 0x425, &o425, 1);
                    guest_safe_read(op + 0x8, &o8, 4);
                    guest_safe_read(op + 0x420, &omode, 4);
                    guest_safe_read(op + 0x64, &oprog, 4);
                    guest_safe_read(op + 0xc8, &osc, 8);
                    if (osc) guest_safe_read(osc + 0x9c, &oscst, 4);
                }
                std::fprintf(stderr,
                    "[pm-op] #%llu pm=%#llx started[+0x48]=%u stop[+0x49]=%u | "
                    "PENDING cnt=%u ACTIVE cnt=%u | sem[+0xc0]=%u pjobs[+0x260]=%u/%u hb[+0x220]=%u | "
                    "op=%#llx(%s) [+0x40]=%u [+0x425]=%u [+0x8]=%u mode[+0x420]=%u prog=%.2f "
                    "scene[+0xc8]=%#llx st=%d\n",
                    (unsigned long long)c, (unsigned long long)pm, started, stopf,
                    pcnt, acnt, semw, pjobs, pjob2, hb,
                    (unsigned long long)op, aop ? "ACTIVE" : (pop ? "PENDING" : "none"),
                    o40, o425, o8, omode, (double)oprog,
                    (unsigned long long)osc, oscst);
            } else {
                std::fprintf(stderr, "[pm-op] #%llu PreloadManager singleton NULL\n",
                             (unsigned long long)c);
            }
        }
        std::fflush(stderr);
    }
}

}  // extern "C"

// True for an A64 instruction whose effect depends on its own address, which a
// copy relocated into a trampoline would get wrong. ARM ARM (DDI 0487) C4.1
// encoding groups: PC-rel addressing ADR / ADRP (bits 28:24 = 10000);
// unconditional branch B / BL (bits 30:26 = 00101); conditional branch B.cond /
// BC.cond (bits 31:24 = 01010100); compare-and-branch CBZ / CBNZ (bits 30:25 =
// 011010); test-and-branch TBZ / TBNZ (bits 30:25 = 011011); load register
// (literal) LDR / LDRSW / PRFM, GP and SIMD (bits 29:27 = 011, 25:24 = 00).
static bool a64_is_pc_relative(uint32_t w) {
    if ((w & 0x1f000000u) == 0x10000000u) return true;   // ADR / ADRP
    if ((w & 0x7c000000u) == 0x14000000u) return true;   // B / BL
    if ((w & 0xff000000u) == 0x54000000u) return true;   // B.cond / BC.cond
    if ((w & 0x7e000000u) == 0x34000000u) return true;   // CBZ / CBNZ
    if ((w & 0x7e000000u) == 0x36000000u) return true;   // TBZ / TBNZ
    if ((w & 0x3b000000u) == 0x18000000u) return true;   // LDR / LDRSW / PRFM (literal)
    return false;
}

// F3 -- GENERIC il2cpp managed-exception logger (MACAPK_IL2CPP_EXC_LOG, default
// off). Re-anchors the proven exc logger on libil2cpp's EXPORTED symbols (zero
// hardcoded RVAs): resolves il2cpp_raise_exception + the format/name helpers from
// the dynsym, decodes vm::Exception::Raise as the `bl` target of
// il2cpp_raise_exception's 3rd instruction (stable across builds:
// `str x30,[sp,#-0x10]!; mov x1,xzr; bl <Exception::Raise>`), and entry-hooks
// Raise. The hook returns through a runtime [displaced-insn][b Raise+4] stub so
// it works on any build. x0 at Raise entry = the Il2CppException* -> the logger
// names its type+message. The single highest-value "Unity app silently stuck"
// visibility tool. (The per-build MACAPK_BOOTCTRL_COUNT exc site it replaced was
// deleted with the il2cpp diag kit, 2026-09-27.)
void Image::install_il2cpp_exc_logger() {
    if (!std::getenv("MACAPK_IL2CPP_EXC_LOG")) return;
    if (!dbg_name_ || !std::strstr(dbg_name_, "libil2cpp")) return;
    g_il2cpp_base = (uint64_t)(uintptr_t)base_;
    g_il2cpp_mapped_size = (uint64_t)mapped_size_;   // the native-stack walk's range
    auto exp = [&](const char* nm) -> uint64_t {
        // t3-syms: exports_ (eager map) is retired; lookup() now resolves
        // by hashing on demand -- see its citation above gnu_hash_lookup().
        return (uint64_t)(uintptr_t)lookup(nm);
    };
    uint64_t raise = exp("il2cpp_raise_exception");
    g_il2cpp_exc_clsname = exp("il2cpp_class_get_name");
    g_il2cpp_exc_fmtexc  = exp("il2cpp_format_exception");
    g_il2cpp_exc_fmtstk  = exp("il2cpp_format_stack_trace");
    // T1 live-stack-walk exports (present on Unity 2022.3 and 6000.x libil2cpp;
    // if any is absent the logger falls back to the stored-trace formatter).
    g_il2cpp_exc_stkdepth = exp("il2cpp_current_thread_get_stack_depth");
    g_il2cpp_exc_frameat  = exp("il2cpp_current_thread_get_frame_at");
    g_il2cpp_exc_methname = exp("il2cpp_method_get_name");
    g_il2cpp_exc_methcls  = exp("il2cpp_method_get_class");
    if (!raise || !g_il2cpp_exc_clsname || !g_il2cpp_exc_fmtexc) {
        std::fprintf(stderr, "[il2cpp-exc] exports missing (raise=%#llx clsname=%#llx "
                     "fmtexc=%#llx) -- DISABLED\n",
                     (unsigned long long)raise, (unsigned long long)g_il2cpp_exc_clsname,
                     (unsigned long long)g_il2cpp_exc_fmtexc);
        return;
    }
    // vm::Exception::Raise = the `bl` target of il2cpp_raise_exception's 3rd insn.
    uint32_t w3 = 0; std::memcpy(&w3, (const void*)(uintptr_t)(raise + 8), 4);
    uint64_t target = raise;   // fallback: hook the public wrapper directly
    if ((w3 & 0xfc000000u) == 0x94000000u) {  // bl
        int64_t imm = (int64_t)(w3 & 0x3ffffffu);
        if (imm & (1ll << 25)) imm -= (1ll << 26);
        target = (raise + 8) + (uint64_t)(imm << 2);
    }
    if (target < (uint64_t)(uintptr_t)base_ ||
        target >= (uint64_t)(uintptr_t)base_ + mapped_size_) {
        std::fprintf(stderr, "[il2cpp-exc] Raise target %#llx out of image -- DISABLED\n",
                     (unsigned long long)target);
        return;
    }
    uint8_t* rfn = (uint8_t*)(uintptr_t)target;
    if (!trampoline_) {
        void* hint = static_cast<void*>(base_ + mapped_size_);
        void* tp = mmap(hint, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (tp == MAP_FAILED) { std::fprintf(stderr, "[il2cpp-exc] tramp mmap failed\n"); return; }
        trampoline_ = static_cast<uint8_t*>(tp); trampoline_used_ = 0;
    }
    if (trampoline_used_ + 24 > 4096) { std::fprintf(stderr, "[il2cpp-exc] tramp full\n"); return; }
    // Continuation stub: [displaced first insn][b Raise+4]. Relocating the first
    // instruction is only right if it does not read its own address, so check
    // rather than assume (Raise opens with an stp / sub sp on the four corpus
    // builds, but that is a property of a build, not of il2cpp).
    uint32_t disp = 0; std::memcpy(&disp, rfn, 4);
    if (a64_is_pc_relative(disp)) {
        std::fprintf(stderr, "[il2cpp-exc] Raise's first insn %#x is PC-relative -- DISABLED\n",
                     disp);
        return;
    }
    uint8_t* stub = trampoline_ + trampoline_used_;
    std::memcpy(stub, &disp, 4);
    uint32_t bcont = encode_b((uintptr_t)(stub + 4), (uintptr_t)(rfn + 4));
    std::memcpy(stub + 4, &bcont, 4);
    g_exc_raise_cont = (uint64_t)(uintptr_t)stub;
    trampoline_used_ += 8;
    // Near-hop to the generic tramp (the static tramp may be > +/-128MB away).
    uint8_t* nh = trampoline_ + trampoline_used_;
    uint8_t nhc[16] = { 0x50,0x00,0x00,0x58, 0x00,0x02,0x1f,0xd6, 0,0,0,0,0,0,0,0 };
    uint64_t tg = (uint64_t)(uintptr_t)&macapk_il2cpp_exc_tramp_gen;
    std::memcpy(nhc + 8, &tg, 8);
    std::memcpy(nh, nhc, sizeof(nhc));
    trampoline_used_ += sizeof(nhc);
    int64_t d = (int64_t)(uintptr_t)nh - (int64_t)(uintptr_t)rfn;
    if (d < -(int64_t)(128 << 20) || d >= (int64_t)(128 << 20)) {
        std::fprintf(stderr, "[il2cpp-exc] near-hop out of range -- DISABLED\n"); return;
    }
    uint32_t bw = encode_b((uintptr_t)rfn, (uintptr_t)nh);
    std::memcpy(rfn, &bw, 4);
    sys_icache_invalidate(rfn, 4);
    std::fprintf(stderr,
        "[il2cpp-exc] ARMED @ Exception::Raise=libil2cpp+%#llx (via exported "
        "il2cpp_raise_exception+%#llx; clsname+%#llx fmtexc+%#llx)\n",
        (unsigned long long)(target - (uint64_t)(uintptr_t)base_),
        (unsigned long long)(raise - (uint64_t)(uintptr_t)base_),
        (unsigned long long)(g_il2cpp_exc_clsname - (uint64_t)(uintptr_t)base_),
        (unsigned long long)(g_il2cpp_exc_fmtexc ? g_il2cpp_exc_fmtexc - (uint64_t)(uintptr_t)base_ : 0));
}

// ===================================================================
// il2cpp Time probe (MACAPK_IL2CPP_TIME_PROBE=<N>, default off)
// ===================================================================
// A DEBUGGER, like F3 above, and the instrument for the notated
// Time.unscaledTime regression (docs/unity-choreographer-vsync-nanotime-contract_2026-09-27.md
// section 4a; its falsifier is "unscaledTime < 1e4"). It replaces the [time-probe]
// that rode inside the deleted il2cpp diag kit (MACAPK_UHOOK + MACAPK_IL2CPP_TRACE,
// byte-gated to Crossy Road 7.11.1 and calling base + 0x2234298 / 0x22342c0 /
// 0x2234b80): this one uses NO address that did not come from the image's own
// dynsym, so it runs on any il2cpp build (Crossy 6000.3 and Subway 2022.3 alike).
//
// Hook: vm::Runtime::Invoke, found from the EXPORTED il2cpp_runtime_invoke. On
// every corpus build that export is a single `b <Runtime::Invoke>` (il2cpp-api.cpp
// forwards straight to it), so the installer follows up to three such
// unconditional branches instead of assuming the shape, then entry-hooks the
// target exactly as F3 hooks Exception::Raise: a runtime [displaced insn][b fn+4]
// stub, refused when the displaced instruction reads its own address
// (a64_is_pc_relative). Every Nth call the handler asks il2cpp ITSELF, through
// its exported API resolved by NAME at install:
//   il2cpp_domain_get -> il2cpp_domain_assembly_open("UnityEngine.CoreModule",
//   falling back to "UnityEngine", Time's pre-2017.2 home) ->
//   il2cpp_assembly_get_image -> il2cpp_class_from_name(img, "UnityEngine", "Time")
//   -> il2cpp_class_get_method_from_name(klass, getter, 0) -> il2cpp_runtime_invoke
// and prints each getter's boxed value (an Il2CppObject is {klass, monitor}, so
// the value sits at +0x10) as `[time-probe] Time.<getter> = <value>`, the line
// format the batch W measurement parsed. Those nested il2cpp_runtime_invoke calls
// re-enter this hook; a thread-local guard returns at once for them. A snapshot
// is single-flight across threads (a busy flag), so two threads never share the
// method cache. The getters run on whatever thread made the Nth call; a getter
// that throws there prints <EXC> rather than a value, and the header names the
// thread. Digest a run with debug/il2cpp_timeprobe.py.
extern "C" uint64_t g_il2cpp_tp_cont = 0;   // runtime [displaced insn][b Invoke+4] stub
static uint64_t g_tp_every = 0;             // N: snapshot every N hooked calls
static uint64_t g_tp_api[6] = {};           // the six exports, by name, at install
static uint64_t g_tp_t0 = 0;                // CLOCK_UPTIME_RAW ns at install
static std::atomic<uint64_t> g_tp_calls{0};
static std::atomic<bool> g_tp_busy{false};
enum TpKind { TP_F32, TP_F64, TP_I32 };
struct TpGetter { const char* name; TpKind kind; };
// The old probe's eleven getters in its order, then three it could not read: the
// frame counter and the double-precision clocks Unity added in 2020.2 (the f32
// unscaledTime steps 0.125 s at 2e6 s, which hides everything but its magnitude).
static const TpGetter kTpGetters[] = {
    {"get_deltaTime", TP_F32}, {"get_unscaledDeltaTime", TP_F32},
    {"get_smoothDeltaTime", TP_F32}, {"get_fixedDeltaTime", TP_F32},
    {"get_fixedUnscaledDeltaTime", TP_F32}, {"get_maximumDeltaTime", TP_F32},
    {"get_captureDeltaTime", TP_F32}, {"get_timeScale", TP_F32},
    {"get_time", TP_F32}, {"get_unscaledTime", TP_F32},
    {"get_realtimeSinceStartup", TP_F32}, {"get_frameCount", TP_I32},
    {"get_unscaledTimeAsDouble", TP_F64}, {"get_realtimeSinceStartupAsDouble", TP_F64},
};
constexpr size_t kTpN = sizeof(kTpGetters) / sizeof(kTpGetters[0]);
static void* g_tp_klass = nullptr;          // UnityEngine.Time, once found
static void* g_tp_method[kTpN] = {};        // cached MethodInfo* (null = absent)
static int g_tp_miss_said = 0;              // "no Time class yet" lines printed

static void macapk_il2cpp_time_probe_snapshot(uint64_t calls) {
    typedef void* (*dom_t)();
    typedef void* (*asm_t)(void*, const char*);
    typedef void* (*img_t)(void*);
    typedef void* (*cls_t)(void*, const char*, const char*);
    typedef void* (*mth_t)(void*, const char*, int);
    typedef void* (*inv_t)(void*, void*, void**, void**);
    const inv_t inv = (inv_t)(uintptr_t)g_tp_api[5];
    static int snap = 0;
    ++snap;
    char tn[64]; tn[0] = '\0';
    pthread_getname_np(pthread_self(), tn, sizeof tn);
    const double t = (double)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - g_tp_t0) / 1e9;
    if (!g_tp_klass) {
        void* dom = ((dom_t)(uintptr_t)g_tp_api[0])();
        void* as = nullptr;
        const char* asname = nullptr;
        for (const char* nm : {"UnityEngine.CoreModule", "UnityEngine"}) {
            if (dom && (as = ((asm_t)(uintptr_t)g_tp_api[1])(dom, nm)) != nullptr) { asname = nm; break; }
        }
        void* img = as ? ((img_t)(uintptr_t)g_tp_api[2])(as) : nullptr;
        void* k = img ? ((cls_t)(uintptr_t)g_tp_api[3])(img, "UnityEngine", "Time") : nullptr;
        if (!k) {
            if (g_tp_miss_said++ < 3)
                std::fprintf(stderr,
                    "[time-probe] snapshot #%d @%llu calls t=%.1fs: no UnityEngine.Time yet "
                    "(domain=%p assembly=%s image=%p)%s\n",
                    snap, (unsigned long long)calls, t, dom, asname ? asname : "<none>", img,
                    g_tp_miss_said == 3 ? " -- saying so no more, still trying" : "");
            std::fflush(stderr);
            return;
        }
        for (size_t i = 0; i < kTpN; ++i)
            g_tp_method[i] = ((mth_t)(uintptr_t)g_tp_api[4])(k, kTpGetters[i].name, 0);
        g_tp_klass = k;
        std::fprintf(stderr, "[time-probe] UnityEngine.Time = %p (assembly %s)\n", k, asname);
    }
    std::fprintf(stderr, "[time-probe] snapshot #%d @%llu calls t=%.1fs thread=%s\n",
                 snap, (unsigned long long)calls, t, tn[0] ? tn : "?");
    for (size_t i = 0; i < kTpN; ++i) {
        const char* pn = kTpGetters[i].name;
        if (!g_tp_method[i]) {
            std::fprintf(stderr, "[time-probe] Time.%s = <no method>\n", pn);
            continue;
        }
        void* exc = nullptr;
        void* bx = inv(g_tp_method[i], nullptr, nullptr, &exc);
        char val[48] = "?";
        if (bx) {
            switch (kTpGetters[i].kind) {
            case TP_F32: { float v = 0; guest_safe_read((uint64_t)(uintptr_t)bx + 0x10, &v, 4);
                           std::snprintf(val, sizeof val, "%g", (double)v); break; }
            case TP_F64: { double v = 0; guest_safe_read((uint64_t)(uintptr_t)bx + 0x10, &v, 8);
                           std::snprintf(val, sizeof val, "%.9g", v); break; }
            case TP_I32: { int32_t v = 0; guest_safe_read((uint64_t)(uintptr_t)bx + 0x10, &v, 4);
                           std::snprintf(val, sizeof val, "%d", (int)v); break; }
            }
        }
        std::fprintf(stderr, "[time-probe] Time.%s = %s%s%s\n", pn, bx ? val : "0",
                     exc ? "  <EXC>" : "", bx ? "" : "  <null-box>");
    }
    std::fflush(stderr);
}

extern "C" void macapk_il2cpp_time_probe_tramp();
extern "C" void macapk_il2cpp_time_probe_hook() {
    // Our own nested il2cpp_runtime_invoke calls come back through the hook.
    static thread_local bool in_probe = false;
    if (in_probe) return;
    const uint64_t c = g_tp_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (c % g_tp_every) return;
    bool idle = false;
    if (!g_tp_busy.compare_exchange_strong(idle, true, std::memory_order_acq_rel)) return;
    in_probe = true;
    macapk_il2cpp_time_probe_snapshot(c);
    in_probe = false;
    g_tp_busy.store(false, std::memory_order_release);
}

__asm__(
"   .text\n   .p2align 2\n"
// Same save / call / restore / continue shape as _macapk_il2cpp_exc_tramp_gen. The
// hooked function's own arguments (x0-x7) and x8-x18 are saved around the call and
// restored before the displaced instruction runs, so Runtime::Invoke sees exactly
// what its caller passed.
"   .globl _macapk_il2cpp_time_probe_tramp\n"
"_macapk_il2cpp_time_probe_tramp:\n"
"   sub  sp, sp, #160\n"
"   stp  x0,x1,[sp,#0]\n   stp x2,x3,[sp,#16]\n   stp x4,x5,[sp,#32]\n"
"   stp  x6,x7,[sp,#48]\n  stp x8,x9,[sp,#64]\n   stp x10,x11,[sp,#80]\n"
"   stp  x12,x13,[sp,#96]\n stp x14,x15,[sp,#112]\n stp x16,x17,[sp,#128]\n"
"   stp  x18,x30,[sp,#144]\n"
"   bl _macapk_il2cpp_time_probe_hook\n"
"   ldp  x0,x1,[sp,#0]\n   ldp x2,x3,[sp,#16]\n   ldp x4,x5,[sp,#32]\n"
"   ldp  x6,x7,[sp,#48]\n  ldp x8,x9,[sp,#64]\n   ldp x10,x11,[sp,#80]\n"
"   ldp  x12,x13,[sp,#96]\n ldp x14,x15,[sp,#112]\n ldp x16,x17,[sp,#128]\n"
"   ldp  x18,x30,[sp,#144]\n"
"   add  sp, sp, #160\n"
"   adrp x16, _g_il2cpp_tp_cont@PAGE\n"
"   ldr  x16, [x16, _g_il2cpp_tp_cont@PAGEOFF]\n   br x16\n"
);

void Image::install_il2cpp_time_probe() {
    const char* e = std::getenv("MACAPK_IL2CPP_TIME_PROBE");
    if (!e || !*e) return;
    if (!dbg_name_ || !std::strstr(dbg_name_, "libil2cpp")) return;
    const long n = std::strtol(e, nullptr, 10);
    // 6000 = the MACAPK_IL2CPP_TRACE interval batch W measured with.
    g_tp_every = n > 0 ? (uint64_t)n : 6000;
    const uint64_t lo = (uint64_t)(uintptr_t)base_, hi = lo + mapped_size_;
    static const char* const kApi[6] = {
        "il2cpp_domain_get", "il2cpp_domain_assembly_open", "il2cpp_assembly_get_image",
        "il2cpp_class_from_name", "il2cpp_class_get_method_from_name", "il2cpp_runtime_invoke",
    };
    for (int i = 0; i < 6; ++i) {
        const uint64_t a = (uint64_t)(uintptr_t)lookup(kApi[i]);
        if (a < lo || a >= hi) {
            std::fprintf(stderr, "[time-probe] export %s %s -- DISABLED\n", kApi[i],
                         a ? "resolves outside libil2cpp" : "missing");
            return;
        }
        g_tp_api[i] = a;
    }
    // Follow the export's unconditional `b`s to the function that does the work.
    uint64_t target = g_tp_api[5];
    int hops = 0;
    for (;;) {
        uint32_t w = 0; std::memcpy(&w, (const void*)(uintptr_t)target, 4);
        if ((w & 0xfc000000u) != 0x14000000u) break;   // not a plain B
        if (++hops > 3) {
            std::fprintf(stderr, "[time-probe] il2cpp_runtime_invoke: more than 3 b hops -- DISABLED\n");
            return;
        }
        int64_t imm = (int64_t)(w & 0x3ffffffu);
        if (imm & (1ll << 25)) imm -= (1ll << 26);
        target += (uint64_t)(imm << 2);
        if (target < lo || target >= hi) {
            std::fprintf(stderr, "[time-probe] il2cpp_runtime_invoke branches out of the image "
                         "(%#llx) -- DISABLED\n", (unsigned long long)target);
            return;
        }
    }
    uint8_t* fn = (uint8_t*)(uintptr_t)target;
    uint32_t disp = 0; std::memcpy(&disp, fn, 4);
    if (a64_is_pc_relative(disp)) {
        std::fprintf(stderr, "[time-probe] Runtime::Invoke's first insn %#x is PC-relative -- DISABLED\n",
                     disp);
        return;
    }
    if (!trampoline_) {
        void* hint = static_cast<void*>(base_ + mapped_size_);
        void* tp = mmap(hint, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (tp == MAP_FAILED) { std::fprintf(stderr, "[time-probe] tramp mmap failed -- DISABLED\n"); return; }
        trampoline_ = static_cast<uint8_t*>(tp); trampoline_used_ = 0;
    }
    if (trampoline_used_ + 24 > 4096) { std::fprintf(stderr, "[time-probe] tramp full -- DISABLED\n"); return; }
    uint8_t* nh = trampoline_ + trampoline_used_ + 8;   // near-hop after the 8-byte stub
    const int64_t d = (int64_t)(uintptr_t)nh - (int64_t)(uintptr_t)fn;
    if (d < -(int64_t)(128 << 20) || d >= (int64_t)(128 << 20)) {
        std::fprintf(stderr, "[time-probe] near-hop out of range -- DISABLED\n"); return;
    }
    uint8_t* stub = trampoline_ + trampoline_used_;
    std::memcpy(stub, &disp, 4);
    const uint32_t bcont = encode_b((uintptr_t)(stub + 4), (uintptr_t)(fn + 4));
    std::memcpy(stub + 4, &bcont, 4);
    g_il2cpp_tp_cont = (uint64_t)(uintptr_t)stub;
    uint8_t nhc[16] = { 0x50,0x00,0x00,0x58, 0x00,0x02,0x1f,0xd6, 0,0,0,0,0,0,0,0 };  // ldr x16,#8; br x16
    const uint64_t tg = (uint64_t)(uintptr_t)&macapk_il2cpp_time_probe_tramp;
    std::memcpy(nhc + 8, &tg, 8);
    std::memcpy(nh, nhc, sizeof(nhc));
    trampoline_used_ += 8 + sizeof(nhc);
    g_tp_t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    const uint32_t bw = encode_b((uintptr_t)fn, (uintptr_t)nh);
    std::memcpy(fn, &bw, 4);
    sys_icache_invalidate(fn, 4);
    std::fprintf(stderr,
        "[time-probe] ARMED @ Runtime::Invoke=libil2cpp+%#llx (via exported "
        "il2cpp_runtime_invoke+%#llx, %d b hop(s)); a Time.* snapshot every %llu calls\n",
        (unsigned long long)(target - lo), (unsigned long long)(g_tp_api[5] - lo), hops,
        (unsigned long long)g_tp_every);
}

// ===================================================================
// Unity render-gate fix TEST (env-gated MACAPK_UNITY_NOABS)
// ===================================================================
//
// Crossy Road / Unity render gate (fully root-caused, v1.0.172): the
// data.unity3d archive's VFS device is mounted under the RELATIVE prefix
// "assets/bin/Data" (Mac.apk's relative-asset path remap lets Unity's
// relative open of data.unity3d succeed), and its node map is keyed by
// BARE node names ("globalgamemanagers"). But Unity's path resolver
// (GetResolvedPath, fn 0x8b51a4) ABSOLUTIZES the per-node load request --
// prepending the .apk sourceDir base global [0x171c850] -- BEFORE the VFS
// match, turning "assets/bin/Data/globalgamemanagers" into
// "<apk>/assets/bin/Data/globalgamemanagers". The archive's prefix-match
// (strncmp request vs "assets/bin/Data") then fails, the load falls to the
// real-FS device, stats a non-existent loose file, and returns false ->
// fatal "Unable to initialize the Unity Engine". On real Android the
// archive mount and the node load share ONE apk-anchored base so they
// never split; Mac.apk split them by mounting relative.
//
// FIX UNDER TEST: NOP the conditional branch at 0x8b521c in 0x8b51a4
//   0x8b521c: tbz w0,#0x0, 0x8b5240   ; (path not absolute) -> prepend base
// so the resolver always falls through to its "already absolute / return
// as-is" path and the request stays RELATIVE
// ("assets/bin/Data/globalgamemanagers"). The archive then matches the
// relative prefix, strips it to the bare key "globalgamemanagers", and the
// node-map lookup hits -> the node is served from the mounted archive.
// Mac.apk's path remap already resolves relative asset paths, so other
// (non-archive) relative paths still resolve.
//
// STATUS: env-gated validation (default OFF), libunity-only, hardcoded
// offset. If it renders Crossy with no F-Droid regression, promote to a
// signature-anchored default (the bytes 0x36000120 = `tbz w0,#0,+0x24`
// after a `blr` to the is-absolute predicate are a stable enough anchor).
void Image::apply_unity_path_noabs() {
    if (std::getenv("MACAPK_UNITY_NOABS_OFF")) return;   // kill-switch
    if (!dbg_name_ || !std::strstr(dbg_name_, "libunity")) return;
    // Signature-anchored (v1.0.173, was a hardcoded 0x8b521c offset). The
    // absolutize branch in libunity's GetResolvedPath is the 16-byte idiom:
    //   ldr x10,[x12,#0x70]  (8a 39 40 f9)  ; is-absolute predicate slot
    //   stp x9,x8,[sp]       (e9 23 00 a9)  ; stage the path {ptr,len}
    //   blr x10              (40 01 3f d6)  ; call is-absolute(path)
    //   tbz w0,#0, +0x24     (20 01 00 36)  ; if NOT absolute -> prepend base
    // NOP the tbz (the 4th word) so the resolver always falls through to its
    // "already absolute / return as-is" path: the per-node load request stays
    // RELATIVE ("assets/bin/Data/<node>") and matches the relative prefix the
    // data.unity3d archive VFS device is mounted under, so the archive claims
    // the load (engine-init no longer fails with "Unable to initialize the
    // Unity Engine"). Mac.apk's path remap already resolves relative asset
    // paths, so non-archive paths still resolve. Engine-level (any libunity),
    // no per-APK code. Kill-switch: MACAPK_UNITY_NOABS_OFF.
    static const uint8_t kSig[16] = {
        0x8a, 0x39, 0x40, 0xf9, 0xe9, 0x23, 0x00, 0xa9,
        0x40, 0x01, 0x3f, 0xd6, 0x20, 0x01, 0x00, 0x36,
    };
    const uint32_t kNop = 0xd503201fu;
    size_t patched = 0;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < sizeof(kSig)) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - sizeof(kSig);
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            if (std::memcmp(p, kSig, sizeof(kSig)) == 0) {
                std::memcpy(const_cast<uint8_t*>(p + 12), &kNop, 4);
                sys_icache_invalidate(const_cast<uint8_t*>(p + 12), 4);
                ++patched;
            }
        }
    }
    std::fprintf(stderr,
        "[unity-noabs] %s: NOP'd %zu absolutize branch(es) via signature scan\n",
        dbg_name_, patched);
}

// Unity SwappyWrapper present guard (v1.0.196) -- RETIRED by default
// (MACAPK_UNITY_SWAPPY_PRESENT_RETIRED, default ON, 2026-09-27).
//
// WHAT IT DOES under =0. libunity (Unity 6000.x; Crossy 7.11.1, libunity md5
// 03f78e94, file vaddr 0x8856bc) presents through
//
//     adrp x8, 0x1723000 ; ldrb w8, [x8, #0x94]   ; flag byte 0x1723094 (.bss)
//     cmp  w8, #1
//     b.ne 0x8856d8                               ; -> bl eglSwapBuffers@plt
//     adrp x8, 0x1723000 ; ldr x2, [x8, #0xc0]    ; fn slot 0x17230c0 (.bss)
//     br   x2                                     ; SwappyWrapper's swap
//
// and the function below rewrites that b.ne into an unconditional b, so the
// engine's own eglSwapBuffers branch is taken whatever the flag says.
//
// WHY ANDROID NEVER NEEDS IT. The flag is set to 1 in exactly two places,
// SwappyGL init 0x885374 (store 0x88549c) and its sibling 0x885774 (store
// 0x885848), and both run only after InitSwappyWrapper (0x885588) returns true.
// That requires dlopen("libswappywrapper...") (0x92b050, a dlopen(path,
// RTLD_NOW) wrapper) to return a handle, dlsym("UnitySwappyWrapperInit") to
// find the entry, and that entry to fill the fn table it is handed (0x17230a0,
// so the slot at +0x20 is 0x17230c0). The flag is cleared at 0x885398, 0x88579c
// and 0x885bb8. No APK in the corpus ships libswappywrapper.so, so on a device
// Bionic's linker answers "library not found" (bionic/linker/linker.cpp
// load_library -> DL_OPEN_ERR, do_dlopen returns nullptr), Unity logs
// "InitSwappyWrapper() - Failed to load libswappywrapper.so", the flag stays 0
// and every frame takes eglSwapBuffers. __macapk_arm64_dlopen gives the same
// answer: a bare, literal or nativeLibraryDir path for an unshipped library
// stat()s absent under lib_native and falls through to a host dlopen that
// returns NULL. It had no catch-all fake handle at v1.0.196 either.
//
// WHY IT WAS ADDED. "flag == 1 with a NULL slot" is unreachable in a correctly
// running process. v1.0.196/197 observed it only downstream of a NULL deref at
// libunity 0x8a6284 (ALooper_forThread returned NULL, so the vsync singleton
// was never built) that null-recovery masked; the zeros it propagated produced
// the impossible state and a render thread parked at PC 0. The same version
// fixed the cause (ALooper_forThread binds a native looper,
// bionic_shim.cpp macapk_looper_ensure) and an lldb read then showed the flag
// at 0. That session's own entry calls this guard "REDUNDANT after fix #2 (flag
// is naturally 0) but kept as defense-in-depth". The earlier text here ("Swappy
// HALF-initialises ... the engine sees enough Android to SET the flag") was a
// later re-narration of that corrupted state, not a measured mechanism.
//
// WHY IT IS A PER-IMAGE FIT. Of the eight arm64 libunity builds in the corpus
// (Crossy, Subway, Among Us, Fruit Ninja, Temple Run 2, Dumb Ways, Alto's,
// Duolingo) the matcher hits exactly one site, in Crossy. Crossy has two more
// SwappyWrapper dispatches on the same flag (b.ne 0x885760 via slot 0x17230b8,
// b.ne 0x885a60 via 0x17231a8) that it never touched, and Duolingo 6.82.3
// (libunity md5 9c7df49e) has the same present at 0x725ff4 on flag 0x13e1f54
// with slot 0x13e1f80 (`ldr x2,[x8,#0xf80]`), which the hardcoded #0xc0 misses.
// So it was fitted to one crash site in one build. On that build today the
// rewritten b.ne is always taken, so the default skips a behavioural no-op and
// stops writing the app's .text.
//
// If Crossy ever reaches `br x2` with a NULL slot after this, the flag became 1
// without a SwappyWrapper: a fake dlopen/dlsym success or upstream control-flow
// corruption (null-recovery masking a first fault). That is the bug to chase;
// failing loudly at PC 0 is the "never succeed inertly" outcome, not a reason to
// restore the rewrite. A future APK that really ships libswappywrapper.so loads
// it through the lib_native redirect and runs real Swappy, as a device would;
// watch its interaction with the Swappy pacing bridge (bionic_shim.cpp) then.
//
// =0 re-applies the rewrite byte for byte (the body below is unchanged), and
// MACAPK_UNITY_SWAPPY_PRESENT_OFF still suppresses it on that path. The default
// prints nothing, so a Crossy log with the default has no "[unity-swappy]" line.
// REMOVAL CONDITION: after one clean sweep cycle with the default plus an
// interleaved crossy/subway A/B (=0 vs default), delete this function, its
// declaration, the call in load_common, both env names and the helper below.
// Gate: debug/geh_unity-swappy-present-null-guard-patch_gate.sh. Evidence:
// docs/unity-swappywrapper-present-dispatch_2026-09-27.md.
static bool macapk_unity_swappy_present_retired() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_UNITY_SWAPPY_PRESENT_RETIRED");
        return !(v && v[0] == '0');
    }();
    return on;
}

void Image::apply_unity_swappy_present_fix() {
    if (std::getenv("MACAPK_UNITY_SWAPPY_PRESENT_OFF")) return;  // kill-switch
    if (!dbg_name_ || !std::strstr(dbg_name_, "libunity")) return;
    size_t patched = 0;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < 20) continue;
        uint8_t* lo = reinterpret_cast<uint8_t*>(s.vaddr);
        uint8_t* hi = lo + s.memsz - 4;
        for (uint8_t* p = lo + 16; p <= hi; p += 4) {
            uint32_t brx2;  std::memcpy(&brx2,  p,      4);
            if (brx2 != 0xd61f0040u) continue;                  // br x2
            uint32_t ldr;   std::memcpy(&ldr,   p - 4,  4);
            if ((ldr  & 0xFFFFFC1Fu) != 0xF9406002u) continue;  // ldr x2,[xN,#0xc0]
            uint32_t adrp;  std::memcpy(&adrp,  p - 8,  4);
            if ((adrp & 0x9F000000u) != 0x90000000u) continue;  // adrp xN, page
            uint32_t bne;   std::memcpy(&bne,   p - 12, 4);
            if ((bne  & 0xFF00001Fu) != 0x54000001u) continue;  // b.ne <fwd>
            uint32_t cmp;   std::memcpy(&cmp,   p - 16, 4);
            if ((cmp  & 0xFFFFFC1Fu) != 0x7100041Fu) continue;  // cmp wN,#1
            // Re-encode the b.ne (imm19, signed *4) as an unconditional b
            // (imm26, signed *4) to the SAME target.
            int32_t imm19 = (int32_t)((bne >> 5) & 0x7FFFFu);
            if (imm19 & 0x40000) imm19 |= ~0x7FFFF;             // sign-extend 19b
            uint32_t b_uncond = 0x14000000u | ((uint32_t)imm19 & 0x03FFFFFFu);
            std::memcpy(p - 12, &b_uncond, 4);
            sys_icache_invalidate(p - 12, 4);
            ++patched;
        }
    }
    std::fprintf(stderr,
        "[unity-swappy] %s: forced %zu present dispatch(es) to the engine's "
        "eglSwapBuffers fallback (NULL Swappy fn-ptr guard)\n",
        dbg_name_, patched);
}

// MACAPK_FMOD_OWN_JNI_ARGS (default ON, v1.0.1654): libfmod's own
// org.fmod.AudioDevice.init arguments reach Java unmodified, as on a device.
//
// Android patches nothing here. The callee of sites A and B (file 0xe6490 in
// GD Lite 2.2.11's arm64 libfmod) is jni.h's inline C++ wrapper,
// CALL_TYPE_METHOD: "va_start(args, methodID); result =
// functions->Call##_jname##MethodV(this, obj, methodID, args);" (NDK sysroot
// jni.h:592-602). It loads JNINativeInterface slot 38, CallBooleanMethodV
// (+0x130), and passes an AAPCS64 va_list (aapcs64.rst App. B: { __stack,
// __gr_top, __vr_top, __gr_offs, __vr_offs }, "__gr_offs: set to
// 0 - ((8 - named_gr) * 8)", so -40 for the three named env/obj/mid). ART
// reads it per shorty (jni_internal.cc CallBooleanMethodV ->
// InvokeVirtualOrInterfaceWithVarArgs; reflection.cc BuildArgArrayFromVarArgs
// "case 'I': Append(va_arg(ap, jint));"), so AudioDevice.init(IIII)Z receives
// FMOD's OWN output format: its channel count, the rate the game gave
// setSoftwareFormat (GD Lite: 24000, or 44100 with its "0022" option), and its
// DSP buffer size. No Android audio-config query feeds these four values; the
// AudioTrack output init (file 0xe617c) reads *outputrate and never writes it,
// and its only JNI query is Build.VERSION.SDK_INT.
//
// On Mac.apk the Bionic JNI variadic bridge (bionic_shim.cpp
// macapk_jni_CallBooleanMethodV_shim -> bionic_pack_typed_args, installed for
// every Stage-6 load) reads that va_list exactly the way ART's va_arg does.
// Sites A and B PREDATE the bridge (c20c9434, 2026-05-16). They were written
// for garbage args, (~1.8e9, ~1.8e9-80, ~1.8e9-128, -40), and blamed that on
// FMOD's config queries; it was really HotSpot reading the Bionic va_list
// struct as an Apple char* at 8-byte stride (low 32 bits of __stack, __gr_top,
// __vr_top, then __gr_offs = -40), which the bridge fixes generically. With
// the bridge in place the constants only OVERRIDE FMOD's correct values, and
// they are (2, 22050, 256, 4), not the 44100/1024 their old comments claimed:
// Java then opens a 22050 Hz AudioTrack for FMOD's 24000 Hz mix, which plays
// about 8% slow and 1.47 semitones flat (an octave down with "0022"). Only GD
// Lite 2.2.11's arm64 libfmod matches A/B anywhere in the corpus, so the
// rewrite was also a per-image fit.
//
// The default leaves both sites untouched and prints one line per image whose
// bytes MATCH (nothing for any other image). =0 restores the constant-forcing
// A/B rewrite byte for byte, including the old siteC SKIPPED line that printed
// for every Stage-6 image. Site D is a separate question and is unaffected.
// Gate: debug/geh_fmod-jni-audio-args-patch_gate.sh; evidence:
// docs/fmod-jni-audio-args-retire_2026-09-27.md.
static bool macapk_fmod_own_jni_args() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_FMOD_OWN_JNI_ARGS");
        return !(v && v[0] == '0');
    }();
    return on;
}

void Image::apply_libfmod_jni_audio_args_patch() {
    // ---- Site A: "fresh AudioDevice.init" -- in-place patch ----
    //   ldr w3, [x22] ; ldr w4, [x23] ; ldr w6, [x8] ;
    //   mov x1, x24   ; mov w5, w21   ; bl CallBooleanMethod_wrapper
    // i.e. AudioDevice.init(*speakermodechannels, *outputrate,
    // dspbufferlength, *dspnumbuffers) from FMOD's AudioTrack output init.
    // 24 bytes total, same length replacement: load the constants
    // (channels 2, rate 22050, bufferCount 4, frames 256) and keep the
    // mov x1/bl. RETIRED by default (MACAPK_FMOD_OWN_JNI_ARGS above).
    static const uint8_t kSigA[24] = {
        0xc3, 0x02, 0x40, 0xb9,
        0xe4, 0x02, 0x40, 0xb9,
        0x06, 0x01, 0x40, 0xb9,
        0xe1, 0x03, 0x18, 0xaa,
        0xe5, 0x03, 0x15, 0x2a,
        0x54, 0x00, 0x00, 0x94,
    };
    // The encodings, decoded (the old comments said 0xac44 = 44100 and
    // 0x400 = 1024; the WORDS below say otherwise, and the words are what
    // ran): MOVZ Wd imm16 = bits [20:5].
    static const uint8_t kRepA[24] = {
        0x43, 0x00, 0x80, 0x52,   // movz w3, #2      (channels = 2)
        0x44, 0xc4, 0x8a, 0x52,   // movz w4, #0x5622 (sampleRate = 22050)
        0x86, 0x00, 0x80, 0x52,   // movz w6, #4      (bufferCount = 4)
        0xe1, 0x03, 0x18, 0xaa,   // mov x1, x24 (instance ptr; unchanged)
        0x05, 0x20, 0x80, 0x52,   // movz w5, #0x100  (framesPerBuffer = 256)
        0x54, 0x00, 0x00, 0x94,   // bl <unchanged>
    };

    // ---- Site B: "reinit on event" -- trampoline-based patch ----
    //   ldr  x0, [sp, #0x8]      ; env       (keep, identity)
    //   ldr  x1, [x19, #0x418]   ; instance  (keep)
    //   ldr  x2, [x19, #0x430]   ; method_id (keep)
    //   ldr  w3, [x19, #0x58]    ; channels  (overridden by trampoline)
    //   ldp  w6, w5, [sp]        ; bufs,frames (overridden by trampoline)
    //   mov  w4, w20             ; sampleRate (overridden by trampoline)
    //   bl   CallBooleanMethod_wrapper  ; (keep)
    // i.e. AudioDevice.init(channels [this+0x58], the System's rate
    // [[this+0x48]+0x6d0], then the two getDSPBufferSize outputs).
    //
    // We can't replace the 3-instruction (12-byte) arg-prep block
    // with 4 constant loads in place. Instead, replace the LDP
    // (the middle instruction) with a `b trampoline`; the trampoline
    // sets all 4 ints to constants then `bl`s the original JNI
    // wrapper, then `b`s back to the bl-successor in libfmod. The
    // function's epilogue restores LR from the stack, so the
    // trampoline freely clobbers LR. RETIRED by default
    // (MACAPK_FMOD_OWN_JNI_ARGS above).
    static const uint8_t kSigB[24] = {
        0xe0, 0x07, 0x40, 0xf9,   // ldr x0, [sp, #0x8]
        0x61, 0x0e, 0x42, 0xf9,   // ldr x1, [x19, #0x418]
        0x62, 0x1a, 0x42, 0xf9,   // ldr x2, [x19, #0x430]
        0x63, 0x5a, 0x40, 0xb9,   // ldr w3, [x19, #0x58]
        0xe6, 0x17, 0x40, 0x29,   // ldp w6, w5, [sp]
        0xe4, 0x03, 0x14, 0x2a,   // mov w4, w20
    };

    // ---- Site C: the varargs wrapper itself. ----
    // libfmod's wrapper packs the 4 saved register args into a
    // Bionic-shaped va_list (`{__stack, __gr_top, __vr_top, __gr_offs,
    // __vr_offs}`, 32 bytes) and passes its pointer to JNI's
    // CallBooleanMethodV. macOS's OpenJDK reads variadic args at
    // 8-byte stride starting from the pointer, so:
    //   arg0 = *(int*)(va_list+0)  = low 32 of __stack pointer
    //   arg1 = *(int*)(va_list+8)  = low 32 of __gr_top pointer
    //   arg2 = *(int*)(va_list+16) = low 32 of __vr_top pointer
    //   arg3 = *(int*)(va_list+24) = __gr_offs
    // Which yields the (1.8B, 1.8B-80, 1.8B-128, -40) garbage we
    // observe at the Java side -- the stride matches the address
    // offsets of `__stack` vs `__gr_top` vs `__vr_top` set by the
    // wrapper, and -40 is libfmod's hardcoded __gr_offs value.
    //
    // The wrapper already saves x3, x4, x5, x6, x7 at 8-byte stride
    // at [x29-0x68 .. x29-0x40] (Bionic regsave layout). That layout
    // is ALREADY Apple-compatible -- each saved register sits in an
    // 8-byte slot, and reading the low 4 bytes as an int recovers
    // the original int arg. The bug is purely that the wrapper hands
    // CallBooleanMethodV a pointer to the va_list struct instead of
    // a pointer to the regsave area.
    //
    // Fix: rewrite the one instruction at +0x54 in the wrapper
    // from `sub x3, x29, #0x40`  (va_list struct addr) to
    //      `sub x3, x29, #0x68`  (regsave area start).
    // Same instruction format, just one byte changes (#0x40 -> #0x68).
    //
    // Anchor by signature: search for the wrapper's distinctive
    // 6-instruction prologue + the `sub x3, x29, #0x40` we want to
    // rewrite + the bl `ldr x8, [x8, #0x130]` we want to keep.
    static const uint8_t kSigC[28] = {
        0x08, 0x00, 0x40, 0xf9,   // ldr x8, [x0]
        0xa1, 0x03, 0x7f, 0xad,   // ldp q1, q0, [x29, #-0x20]
        0xa3, 0x03, 0x01, 0xd1,   // sub x3, x29, #0x40 (PATCH THIS)
        0x08, 0x99, 0x40, 0xf9,   // ldr x8, [x8, #0x130] (CallBooleanMethodV)
        0xa1, 0x03, 0x3e, 0xad,   // stp q1, q0, [x29, #-0x40]
        0x00, 0x01, 0x3f, 0xd6,   // blr x8
        0xfd, 0x7b, 0x4f, 0xa9,   // ldp x29, x30, [sp, #0xf0]
    };

    // ⚠⚠ SITE C IS NOW OFF BY DEFAULT (2026-08-30, v1.0.1111). It is SUPERSEDED
    // and actively HARMFUL: it conflicts with the Bionic JNI bridge that landed
    // AFTER it, and the two together are the SIGSEGV that has made real FMOD
    // unusable on the AudioDevice output path ever since.
    //
    // The history is two fixes for ONE ABI bug, layered:
    //   * 2026-05-16 (this patch): HotSpot read Apple-ABI varargs, so libfmod's
    //     Bionic va_list arrived as garbage (the famous "-40 buffer count" is
    //     libfmod's hardcoded __gr_offs). Rewriting x3 to the regsave area made
    //     the ints readable. Correct FOR ITS ERA.
    //   * by 2026-06-06: `macapk_jni_CallBooleanMethodV_shim` /
    //     `bionic_repack_args` / `bionic_pack_typed_args` (bionic_shim.cpp)
    //     started interpreting that argument AS A BionicVaListJNI STRUCT and
    //     repacking it properly -- the generic, correct fix.
    //
    // So the bridge now receives a regsave pointer where it expects a va_list.
    // It reads offset 0 as `__stack`, which for
    // `org.fmod.AudioDevice.init(IIII)Z` is the FIRST INT ARG -- channels = 2 --
    // and dereferences address 0x2.
    //
    // MEASURED 2026-08-30 on GD Lite under MACAPK_FMOD_REAL=1
    // (logs/hs_err_pid4686.log): SIGSEGV, si_addr=0x0000000000000002, in
    // `bionic_pack_typed_args+0x94` under `macapk_jni_CallBooleanMethodV_shim`,
    // guest frame 0x138cfe4f4 -- which is exactly this signature's site
    // (file offset 0xe64dc in GD Lite's own libfmod.so, +0x18 past the blr,
    // at load base 0x138c18000). Every register agrees.
    //
    // Why it hid for months: GD Lite's DEFAULT path uses the 28-symbol stub
    // table, so the patched wrapper never runs; MCPE and GD Meltdown run real
    // FMOD but take the AAudio output, so their wrappers are patched and never
    // called. The conflict needs real FMOD *and* the AudioDevice output at once.
    // That is why the v615 "spurious crash" and the 2026-07-31 reproduction were
    // never explained -- both were read as a latent generic JNI bug rather than
    // as our own binary patch.
    //
    // The UNPATCHED wrapper is what the bridge wants: it builds a well-formed
    // Bionic va_list (gr_top = fp-0x40, gr_offs = -40 over the x3-x7 regsave).
    // Set MACAPK_FMOD_SITEC=1 to restore the old rewrite for an A/B.
    // Gate ONLY this loop -- sites A and B live further down the SAME function
    // and must keep running, so this must not be an early return.
    const bool site_c_on = (std::getenv("MACAPK_FMOD_SITEC") != nullptr);
    // The SKIPPED line used to print for EVERY Stage-6 image (libcocos2dcpp,
    // libminecraftpe, ...), which read as if each of them carried the wrapper.
    // By default it now prints only for an image whose bytes match kSigC, from
    // the one detection pass below; MACAPK_FMOD_OWN_JNI_ARGS=0 keeps the old
    // per-image line so a =0 log diffs clean against a pre-change one.
    const bool own_jni_args = macapk_fmod_own_jni_args();
    if (!site_c_on && !own_jni_args) {
        std::fprintf(stderr,
            "[arm64-native] %s: libfmod siteC SKIPPED (superseded by the Bionic "
            "JNI bridge; MACAPK_FMOD_SITEC=1 restores it)\n", dbg_name_);
    }

    int patches = 0;
    for (const auto& s : segments_) {
        if (!site_c_on) break;
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < sizeof(kSigC)) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - sizeof(kSigC);
        uint32_t c0; std::memcpy(&c0, kSigC, 4);
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            uint32_t w0; std::memcpy(&w0, p, 4);
            if (w0 != c0) continue;   // first-word prefilter; memcmp decides
            if (std::memcmp(p, kSigC, sizeof(kSigC)) == 0) {
                // Patch the `sub x3, x29, #0x40` at p+8 to
                // `sub x3, x29, #0x68`.
                static const uint8_t kRepC[4] = { 0xa3, 0xa3, 0x01, 0xd1 };
                std::memcpy(const_cast<uint8_t*>(p + 8), kRepC, sizeof(kRepC));
                std::fprintf(stderr,
                    "[arm64-native] %s: libfmod siteC patched at %p\n",
                    dbg_name_, p + 8);
                ++patches;
            }
        }
    }

    // ---- Sites A and B: the original direct call site patches. ----
    // RUNS ONLY UNDER MACAPK_FMOD_OWN_JNI_ARGS=0. The reasoning it was
    // written on (2026-05-16) is kept below for the record, and it is WRONG:
    //   "Now that the wrapper passes a correct va_list, the JVM correctly
    //    reads arg0..arg3 from the regsave area. Sites A and B set x3..x6 to
    //    the values libfmod's audio-config-query produced (which we know to
    //    be garbage on macOS). So even with the wrapper fix, the JVM gets
    //    garbage. We additionally need sites A and B to set sane constants."
    // x3..x6 at these sites are FMOD's own software format (see the
    // MACAPK_FMOD_OWN_JNI_ARGS banner above the function); no config query
    // feeds them, and the only garbage ever measured was the va_list ABI that
    // the Bionic JNI bridge now repacks. "Sane args" logged since then
    // (sampleRate=22050, framesPerBuffer=256) were these constants echoed
    // back, never FMOD's values.
    for (const auto& s : segments_) {
        if (own_jni_args) break;   // the default: see the detection pass below
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < sizeof(kSigA)) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - sizeof(kSigA);
        // FIRST-WORD PREFILTER (v1.0.1635). This loop ran std::memcmp at every
        // 4-byte step of every executable segment of EVERY image -- ~50M calls on
        // libminecraftpe, which has no FMOD code at all, for 195 ms of each launch.
        // A position whose first word matches neither signature is one memcmp
        // rejects anyway, so the match set (and every patch) is unchanged.
        uint32_t a0, b0; std::memcpy(&a0, kSigA, 4); std::memcpy(&b0, kSigB, 4);
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            uint32_t w0; std::memcpy(&w0, p, 4);
            if (w0 != a0 && w0 != b0) continue;
            // Try site A first (longer signature, lower false-match risk).
            if (std::memcmp(p, kSigA, sizeof(kSigA)) == 0) {
                std::memcpy(const_cast<uint8_t*>(p), kRepA, sizeof(kRepA));
                std::fprintf(stderr,
                    "[arm64-native] %s: libfmod siteA patched at %p\n",
                    dbg_name_, p);
                ++patches;
                p += sizeof(kRepA) - 4;
                continue;
            }
            // Try site B (24 bytes, but only patch the LDP within).
            if (p + sizeof(kSigB) <= reinterpret_cast<const uint8_t*>(
                    s.vaddr + s.memsz)
                && std::memcmp(p, kSigB, sizeof(kSigB)) == 0) {
                // The bl immediately after kSigB (at p+24) calls the
                // JNI varargs wrapper. We need its absolute address
                // for the trampoline.
                const uint32_t* bl = reinterpret_cast<const uint32_t*>(p + 24);
                uint32_t bl_word = *bl;
                if ((bl_word & 0xfc000000u) != 0x94000000u) {
                    // Not a bl as expected; skip this match.
                    continue;
                }
                int32_t bl_off = int32_t(bl_word & 0x03ffffff) << 6 >> 4;
                uintptr_t bl_pc = uintptr_t(bl);
                uintptr_t wrapper = bl_pc + bl_off;
                uintptr_t bl_next = bl_pc + 4;  // where to return after wrapper

                // Allocate trampoline page if not yet done.
                if (!trampoline_) {
                    // Try to mmap immediately after the loaded image so
                    // b/bl from the patch site can reach (±128 MB).
                    void* hint = static_cast<void*>(base_ + mapped_size_);
                    void* tp = mmap(hint, 4096,
                                     PROT_READ | PROT_WRITE,
                                     MAP_PRIVATE | MAP_ANON, -1, 0);
                    if (tp == MAP_FAILED) {
                        std::fprintf(stderr,
                            "[arm64-native] %s: trampoline mmap failed; "
                            "siteB skipped\n", dbg_name_);
                        continue;
                    }
                    trampoline_ = static_cast<uint8_t*>(tp);
                    trampoline_used_ = 0;
                }

                uintptr_t trampoline_pc = uintptr_t(trampoline_)
                                          + trampoline_used_;
                // Verify trampoline_pc is within ±128 MB of the patch
                // site (so the inline `b` can reach it) and within
                // ±128 MB of `wrapper` and `bl_next` (so the
                // trampoline's bl/b can reach those).
                auto reachable = [](uintptr_t a, uintptr_t b) -> bool {
                    int64_t d = int64_t(b) - int64_t(a);
                    return d >= -(int64_t)(128 * 1024 * 1024)
                        && d <  (int64_t)(128 * 1024 * 1024);
                };
                uintptr_t ldp_pc = uintptr_t(p) + 16;  // address of the LDP
                if (!reachable(ldp_pc, trampoline_pc)
                    || !reachable(trampoline_pc, wrapper)
                    || !reachable(trampoline_pc + 20, bl_next)) {
                    std::fprintf(stderr,
                        "[arm64-native] %s: trampoline out of range; "
                        "siteB skipped\n", dbg_name_);
                    continue;
                }

                // Build the trampoline (decoded from the words, which the
                // old comments misread as 44100 / 1024):
                //   mov w3, #2
                //   mov w4, #22050
                //   mov w5, #256
                //   mov w6, #4
                //   bl  wrapper
                //   b   bl_next
                uint32_t code[6] = {
                    0x52800043u,  // mov w3, #2      (channels = 2)
                    0x528ac444u,  // mov w4, #0x5622 (sampleRate = 22050)
                    0x52802005u,  // mov w5, #0x100  (framesPerBuffer = 256)
                    0x52800086u,  // mov w6, #4      (bufferCount = 4)
                    encode_bl(trampoline_pc + 16, wrapper),
                    encode_b (trampoline_pc + 20, bl_next),
                };
                std::memcpy(trampoline_ + trampoline_used_,
                            code, sizeof(code));
                trampoline_used_ += sizeof(code);

                // Patch the LDP instruction (offset +16 from p) to
                // branch into the trampoline.
                uint32_t bword = encode_b(ldp_pc, trampoline_pc);
                std::memcpy(const_cast<uint8_t*>(p + 16), &bword,
                            sizeof(bword));

                std::fprintf(stderr,
                    "[arm64-native] %s: libfmod siteB patched at %p "
                    "(trampoline at %p)\n",
                    dbg_name_, p + 16, (void*)trampoline_pc);
                ++patches;
                p += sizeof(kSigB) - 4;
                continue;
            }
        }
    }

    // ---- The default (MACAPK_FMOD_OWN_JNI_ARGS): detect, never write. ----
    // One pass over the executable segments, same first-word prefilter and
    // the same match rules as the rewrite above (site B also needs the `bl`
    // right after its 24 bytes), so the log names exactly the images the
    // rewrite WOULD have touched and says nothing about any other image. Site
    // C rides the same pass while it is off, so its SKIPPED line is per match
    // too instead of per image. Offsets are file vaddrs (p - base_), the form
    // docs/binary-atlas.md keys on.
    if (own_jni_args) {
        uint32_t a0, b0, c0;
        std::memcpy(&a0, kSigA, 4);
        std::memcpy(&b0, kSigB, 4);
        std::memcpy(&c0, kSigC, 4);
        const bool want_c = !site_c_on;   // when on, its own loop patched it
        int a_hits = 0, b_hits = 0, c_hits = 0;
        unsigned long long a_at = 0, b_at = 0, c_at = 0;
        for (const auto& s : segments_) {
            if (!(s.prot & PROT_EXEC)) continue;
            if (s.memsz < sizeof(kSigA)) continue;   // A and B are 24 bytes
            const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
            const uint8_t* end = lo + s.memsz;
            for (const uint8_t* p = lo; p + sizeof(kSigA) <= end; p += 4) {
                uint32_t w0; std::memcpy(&w0, p, 4);
                if (w0 == a0) {
                    if (std::memcmp(p, kSigA, sizeof(kSigA)) != 0) continue;
                    if (a_hits++ == 0)
                        a_at = (unsigned long long)(p - base_);
                    p += sizeof(kSigA) - 4;
                } else if (w0 == b0) {
                    if (p + sizeof(kSigB) + 4 > end) continue;
                    if (std::memcmp(p, kSigB, sizeof(kSigB)) != 0) continue;
                    uint32_t bl_word; std::memcpy(&bl_word, p + 24, 4);
                    if ((bl_word & 0xfc000000u) != 0x94000000u) continue;
                    if (b_hits++ == 0)
                        b_at = (unsigned long long)(p - base_);
                    p += sizeof(kSigB) - 4;
                } else if (want_c && w0 == c0) {
                    if (p + sizeof(kSigC) > end) continue;
                    if (std::memcmp(p, kSigC, sizeof(kSigC)) != 0) continue;
                    if (c_hits++ == 0)
                        c_at = (unsigned long long)(p - base_);
                }
            }
        }
        if (c_hits) {
            std::fprintf(stderr,
                "[arm64-native] %s: libfmod siteC SKIPPED (superseded by the "
                "Bionic JNI bridge; MACAPK_FMOD_SITEC=1 restores it) "
                "[match x%d, first at file 0x%llx]\n", dbg_name_, c_hits, c_at);
        }
        if (a_hits || b_hits) {
            std::fprintf(stderr,
                "[arm64-native] %s: libfmod siteA/B RETIRED (FMOD's own args "
                "reach AudioDevice.init via the Bionic JNI bridge; "
                "MACAPK_FMOD_OWN_JNI_ARGS=0 restores) [siteA x%d at file 0x%llx, "
                "siteB x%d at file 0x%llx]\n",
                dbg_name_, a_hits, a_at, b_hits, b_at);
        }
    }

    // ---- Site D: `orr w8, w8, #0x20` in FMOD::getGlobals ----
    //
    // libfmod's `FMOD::getGlobals` runs an atomic-OR lock acquire on
    // `FMOD::System+0x58`:
    //   ldaxr  w8, [x24]
    //   orr    w8, w8, #0x20      ; set bit 5 to mark "in critical sec"
    //   stlxr  w9, w8, [x24]
    //   cbnz   w9, retry
    // followed by a `tbnz w8, #5, busy-path` check 0x34 bytes earlier.
    //
    // The bug we are catching: an older libfmod (in this APK's vintage
    // of FMOD) sometimes calls getGlobals with `this` = a freed-and-
    // recycled FMOD::System pointer. The atomic OR fires regardless,
    // planting value 0x20 (= 32) into whatever heap allocation now
    // owns that memory. When the recycler is libcocos's zlib
    // `inflate_state` (a same-allocation-class fit), the OR scribbles
    // into `state->lens[]` (each entry uint16, so 0x20 = lens[i]=32),
    // and the next PNG decode's inflate_table sees a len out-of-spec
    // and SIGBUSes with huff=0x80000002. We could not catch this from
    // the pthread shim layer because the OR is hardware-inline LL/SC,
    // not a libc call.
    //
    // Patch: replace `orr w8, w8, #0x20` (4 bytes) with `nop` so the
    // CAS-loop's stlxr stores back the unchanged w8 (i.e. the loaded
    // value), the cbnz retry-loop exits on the first iteration, and
    // no bit is set. The trade-off is FMOD's reentrancy-lock now
    // never actually marks the System as "in critical section", so
    // recursive getGlobals callers don't take the busy-path. Concurrent
    // getGlobals callers will all enter the critical section; for GD's
    // single-mixer / occasional-main-thread call pattern this is
    // tolerable in practice and far less harmful than the use-after-
    // free corruption it replaces.
    //
    // Signature: the 16 bytes at libfmod+0x66de4 are unique in the
    // image (verified with `xxd -p libfmod.so | grep
    // 08ff5f8808011b3208ff0988a9ffff35 | wc -l == 1`), so we anchor
    // on them and only the `orr` itself (4 bytes at +4) gets rewritten
    // to NOP. Same `arm64_native` constraints as siteA/B/C: the loader
    // walks all exec segments before mprotect locks them in, so this
    // runs while the segment is still writable.
    //
    // ⚠ OPEN (2026-09-27, not acted on here): the label above is wrong. In GD
    // Lite 2.2.11's arm64 libfmod, FMOD::getGlobals is the 16-byte function
    // at 0x5e1d8; 0x66de4 sits in a SEPARATE function at 0x66d6c that tests
    // bit 5 of [this+0x58] (`tbnz w8, #5` -> busy return) and then sets it,
    // i.e. a release/busy flag. "getGlobals+0x8c0c" is only llvm-objdump's
    // nearest-preceding-symbol annotation. The UAF premise was refuted on
    // 2026-08-30 (the zlib corruption was cocos's own, now routed to macOS
    // libz). Site D needs its own review row; see
    // docs/fmod-jni-audio-args-retire_2026-09-27.md.
    static const uint8_t kSigD[16] = {
        0x08, 0xff, 0x5f, 0x88,   // ldaxr w8, [x24]
        0x08, 0x01, 0x1b, 0x32,   // orr   w8, w8, #0x20
        0x08, 0xff, 0x09, 0x88,   // stlxr w9, w8, [x24]
        0xa9, 0xff, 0xff, 0x35,   // cbnz  w9, .-12 (CAS retry)
    };
    static const uint8_t kRepD_orr[4] = {
        0x1f, 0x20, 0x03, 0xd5,   // nop
    };
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < sizeof(kSigD)) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - sizeof(kSigD);
        uint32_t d0; std::memcpy(&d0, kSigD, 4);
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            uint32_t w0; std::memcpy(&w0, p, 4);
            if (w0 != d0) continue;   // first-word prefilter; memcmp decides
            if (std::memcmp(p, kSigD, sizeof(kSigD)) == 0) {
                std::memcpy(const_cast<uint8_t*>(p + 4),
                            kRepD_orr, sizeof(kRepD_orr));
                std::fprintf(stderr,
                    "[arm64-native] %s: libfmod siteD (getGlobals OR-32) "
                    "patched at %p\n",
                    dbg_name_, p + 4);
                ++patches;
                break;
            }
        }
    }

    // Trampoline mprotect + icache invalidate happens centrally in
    // Image::load() after all patch functions have written. This
    // avoids RX-while-still-writing races between patch functions
    // that share trampoline_ (libfmod siteB, zlib tracers, ...).
    //
    // .text icache invalidation still belongs to whichever patch
    // function modified .text. Each function patches its specific
    // call sites in-place, so we invalidate the full text segments
    // once here -- cheap and unambiguous.
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        sys_icache_invalidate(reinterpret_cast<void*>(s.vaddr), s.memsz);
    }
    (void)patches;
}

// --- Stack-canary neutralization (NDK -fstack-protector-strong) --------
//
// PURPOSE. Every function in an NDK-built shared library that contains
// stack-allocated buffers (which is most of them) is compiled with
// -fstack-protector-strong, which emits this two-instruction pair at
// function prologue (and again at epilogue) to load the per-thread
// stack canary cookie:
//
//   mrs Xn, TPIDR_EL0          ; thread pointer register
//   ldr Xt, [Xn, #0x28]        ; load __stack_chk_guard at Bionic
//                              ; TLS slot 5 (TLS_SLOT_STACK_GUARD)
//
// Bionic places `__stack_chk_guard` (an 8-byte random cookie set at
// process start) at offset 0x28 from TPIDR_EL0 (TLS slot 5). On macOS
// arm64 the real per-thread pointer lives in TPIDRRO_EL0; an EL0
// `mrs Xn, TPIDR_EL0` returns a small system magic value (measured
// ~0x1004-0x1007 -- likely the commpage CPU id), which macOS RESTORES
// on every context switch (so it cannot be repurposed via msr). Any
// `ldr [Xn, #0x28]` off that magic value dereferences ~0x102c and takes
// a SIGSEGV before any C++ runtime function can run.
//
// EFFECT WITHOUT PATCH. Every entry into a stack-protected NDK
// function would SIGSEGV at the canary load. ~50-100 patch sites per
// library; impossible to use libc++_shared, libopenal, libOpenGD, or
// any modern NDK library on the affected threads.
//
// SOLUTION. Replace the `ldr Xt, [Xn, #0x28]` with `mov Xt, #0` (zero
// canary). The store-and-check semantics still work end-to-end:
// prologue stores 0 to the stack canary slot, epilogue compares
// against 0, check passes. Same single-instruction replacement (no
// size change, no register reshuffle needed) at every patch site.
//
// We leave the `mrs Xn, TPIDR_EL0` alone -- it can never fault, and
// the loaded Xn is overwritten by code that's still useful for the
// (unrelated) FP regsave path some functions take.
//
// SECURITY NOTE. This disables stack-overflow detection for patched
// functions. We accept that trade because:
//   (a) the alternative is the library doesn't run at all,
//   (b) Mac.apk runs only APKs the user has explicitly opened, not
//       arbitrary untrusted code,
//   (c) macOS itself has stack guard pages independent of canaries.
//
// PER-APK STATUS. Per-LIBRARY (matched at the instruction-pattern
// level, applied to every loaded ELF). Generic across all NDK-built
// arm64 .so files. Not retired -- this is the steady-state fix for
// the Bionic-vs-Apple-TSD-layout mismatch.
//
// Reference: https://github.com/aosp-mirror/platform_bionic/blob/
// main/libc/platform/bionic/tls_defines.h (TLS_SLOT_STACK_GUARD = 5)
// and https://github.com/apple-oss-distributions/libpthread/blob/
// main/private/pthread/tsd_private.h (__TSD_RETURN_TO_KERNEL = 5).
// Replace every `mrs Xn, MIDR_EL1` with `mov Xn, #0`.
//
// `MIDR_EL1` is the AArch64 Main ID Register (CPU identification). It
// can only be read at exception level 1 or above; user-mode reads
// fault with SIGILL. Linux/Android trap-and-emulate the read in the
// kernel so userland sees a CPU-ID value. macOS does not -- reading
// MIDR_EL1 from user mode always SIGILLs.
//
// OpenSSL's `OPENSSL_cpuid_setup` (called from CRYPTO_init's static
// init in any library that links openssl/crypto -- libOpenGD, many
// cocos2d-x v4 builds, networking libs) does a sigsetjmp + SIGILL
// handler + probe-instruction sequence to feature-detect ARMv8
// extensions, with the MIDR_EL1 read as one of the probes. Bionic's
// sigsetjmp / signal-handler ABI differs from macOS's, so OpenSSL's
// safety net doesn't catch the SIGILL on our process -- the trap
// reaches the JVM signal handler and the process dies.
//
// We replace the read with `mov Xn, #0` so the probe sees CPU ID 0
// (= "unknown vendor"). OpenSSL falls through to its no-acceleration
// path for CPU-ID-dependent code, but the v8 extension probes (sha,
// pmull, etc.) still run on the four-instruction extensions which
// Apple Silicon supports natively. End result: openssl works,
// possibly with less specific tuning, but no crash.
//
// PER-APK STATUS. Per-LIBRARY (matched at the instruction level,
// applies to every loaded ELF). Generic across all NDK libraries
// linking openssl/crypto.
//
// RETIRED BY DEFAULT (MACAPK_MIDR_EMULATE, default ON, 2026-09-27). The
// reason above (c20c9434, 2026-05-16) predates the v1.0.153 SIGILL MRS
// emulator, and the read never needed OpenSSL's safety net at all: on a
// device the KERNEL answers it (arch/arm64/kernel/cpufeature.c
// emulate_id_reg: MIDR_EL1 -> read_cpuid_id(), MPIDR_EL1 ->
// SYS_MPIDR_SAFE_VAL = BIT(31), REVIDR_EL1 -> 0), advertised to userspace as
// HWCAP_CPUID, which our AT_HWCAP (0xffb3ffff) also sets. macapk_sigill_handler
// (bionic_shim.cpp) now does the same, with MIDR taken from the ONE constant
// our arm64 /proc/cpuinfo synth prints, so the app's own instruction runs
// unmodified and gets the CPU id the rest of our platform surface claims
// instead of 0. The corpus has exactly one such word (OpenGD .text), and 0 of
// the 102 installed arm64 libraries have any. MACAPK_MIDR_EMULATE=0 restores
// this rewrite (and turns the handler's MIDR/MPIDR/REVIDR arm off); that is
// byte for byte the old path only TOGETHER with MACAPK_SVC_BRK_EXEC_ONLY=0,
// because with EXEC_ONLY at its default the restored rewrite, like the
// svc->BRK pass, spares a MIDR-shaped word positively inside a non-executable
// section of the PF_X segment.
// REMOVAL CONDITION: one clean sweep cycle plus the OpenGD falsifier
// (docs/sigchain-libsigchain-parity_2026-09-27.md) -> delete this function.
// Redirect a guest's own RAW SYSCALL WRAPPER to our syscall router.
//
// THE PROBLEM THIS SOLVES. A guest that issues a Linux syscall through an
// inline `svc #0` cannot be serviced reliably on macOS. XNU does not read the
// syscall number from x8 at all; it reads x16, and it routes on the SIGN of
// x16 (osfmk/arm64/sleh.c handle_svc). At a guest svc site x16 holds whatever
// the compiler last left in IP0, so the branch taken is JUNK-DEPENDENT:
//   x16 negative      -> mach branch, every register preserved
//   x16 non-negative  -> unix branch, XNU OVERWRITES x0 with Darwin ENOSYS
//                        (78) and x1 with 0 before raising SIGSYS
// MEASURED on this machine with debug/trapprobe.c (macOS 26.5.2): both branch
// outcomes reproduce exactly as above, and the carry flag is SET in BOTH, so
// carry is NOT a usable discriminator. The consequence is that a SIGSYS
// handler cannot recover arg0/arg1: it gets them sometimes and garbage other
// times, which is worse than failing consistently. That is why
// macapk_forward_inline_syscall in bionic_shim.cpp answers -ENOSYS for every
// arg-reading syscall instead of guessing. See
// docs/inline-syscall-svc-trap-x0-clobber_2026-08-19.md.
//
// THE OBSERVATION THAT MAKES THIS FIXABLE. Most guests do not scatter raw svc
// instructions through their code. They compile or statically link ONE syscall
// wrapper and call it, which is exactly what Bionic's own syscall.S is:
//
//     ENTRY(syscall)
//         mov     x8, x0        // number
//         mov     x0, x1        // shift the six arguments down
//         mov     x1, x2
//         mov     x2, x3
//         mov     x3, x4
//         mov     x4, x5
//         mov     x5, x6
//         svc     #0
//         ...
//
// That eight-instruction prefix is a byte-exact signature, and every call to
// it arrives with the arguments still in registers, BEFORE any svc has had a
// chance to be mangled. So we replace the wrapper's first instruction with an
// absolute branch into our own syscall router and the whole family is serviced
// with full argument fidelity, no signal handler involved, and no dependence
// on what x16 happened to contain.
//
// This is engine-generic pattern matching, not per-app code: the signature is
// a libc's syscall() implementation, not any application's. It is the same
// category as the cocos2d-x byte-signature patches below
// (apply_libcocos_zlib_redirect, apply_cocos2dx_v2_addchild_null_safety).
//
// WHICH ROUTER, AND WHY IT MATTERS. The instruction FOLLOWING the svc says
// which calling convention the wrapper presents to its own callers, and
// getting this wrong would corrupt every error return:
//   `ret` (0xd65f03c0)              -- the wrapper hands the kernel's raw
//                                     result straight back, so its callers
//                                     decode a negated errno themselves
//                                     (`cmn x0,#0xfff` at the call site).
//                                     Route to __macapk_arm64_raw_syscall,
//                                     which returns -errno in Linux numbering.
//   `cmn x0,#0xfff` (0xb13ffc1f)   -- Bionic's own errno adapter follows, so
//                                     the wrapper is the libc face and its
//                                     callers expect -1 with errno set. Route
//                                     to __macapk_arm64_syscall and let the
//                                     guest's own adapter run on the result.
// Any other trailing instruction is a shape we have not verified, so it is
// LOGGED AND LEFT ALONE rather than guessed at.
//
// Kill switch: MACAPK_RAW_SYSCALL_REDIRECT=0.
// svc #0 -> BRK #kSvcBrkTag rewrite (gated MACAPK_SVC_BRK=1). See the SIGTRAP
// emulator macapk_svc_brk_sigtrap in bionic_shim.cpp for WHY: a guest inline
// `svc #0` lets XNU read the syscall number from x16 (residual, ASLR-dependent
// junk) and can SILENTLY execute a live Darwin syscall (e.g. exit) -- the
// Instagram Profilo-init death. BRK modifies no registers and dispatches no
// syscall, so the emulator receives x8 (real Linux nr) + x0..x5 intact. Runs in
// the canary-pass phase while the executable PT_LOADs are still writable (before
// apply_segment_protections).
//
// CODE, NOT DATA (MACAPK_SVC_BRK_EXEC_ONLY, default ON, 2026-09-27). This comment
// used to say "arm64 does not use inline literal pools, so a 0xD4000001 word in
// an executable segment is an SVC instruction, not data". The first half is
// true and the conclusion does not follow: a PF_X PT_LOAD is not only .text.
// With the classic linker layout (or lld --no-rosegment) the R+X segment also
// holds .rodata, .eh_frame and .gcc_except_table, and an LSDA call-site table
// is a stream of ULEB128 offsets, so 0x01 0x00 0x00 0xD4 turns up as data.
// MEASURED (debug/geh_guest_crash_handler_verify/svcwords.py): 268 of
// libminecraftpe.so's 401 0xD4000001 words are .gcc_except_table, and across
// the 102 installed arm64 libraries 304 LSDA words sat in rewritable bytes
// (IG libc++_shared/libbreakpad/libart_tooling, AppLovin's crash reporter,
// Efteling's libreactnative x10 ...). Rewriting one silently corrupts C++
// unwinding through that function: nothing fails until an exception is thrown
// through it. hard-fixes-catalog.md had already warned that a whole-segment
// scan is unsafe and that SHF_EXECINSTR filtering had to be built first; the
// v1.0.1622 default-ON flip went ahead without it. So the pass now reads the
// section header table at load (read_exec_sections) and SPARES a word only when
// it lies positively inside a non-executable SHF_ALLOC section of the PF_X
// segment (and in no SHF_EXECINSTR one); a word in an executable section, or
// in no section at all, is rewritten as before (the no-section ones are counted
// in the load log). No usable section headers -> the old whole-segment scan,
// with a loud log line naming why. MACAPK_SVC_BRK_EXEC_ONLY=0 restores the
// whole-segment scan (for this pass and the MIDR pass) byte for byte.

// MACAPK_SVC_BRK_EXEC_ONLY (default ON). See the comment above.
static bool macapk_svc_brk_exec_only() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_SVC_BRK_EXEC_ONLY");
        return !(e && e[0] == '0');
    }();
    return on;
}

// MACAPK_X18_EXEC_ONLY (default ON): the x18 platform-register rewrite stops each
// function window at the first non-executable section of the PF_X segment. See
// install_x18_generic_rewrite. It reads the same section table, so
// read_exec_sections runs when EITHER gate is on, and each pass checks its own.
static bool macapk_x18_exec_only() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_X18_EXEC_ONLY");
        return !(e && e[0] == '0');
    }();
    return on;
}

// Learn which FILE-vaddr ranges of the PF_X segments are SHF_EXECINSTR sections
// and which are non-executable SHF_ALLOC sections (data: .rodata, .eh_frame,
// .gcc_except_table, .dynsym ...). The section header table is in no PT_LOAD, so
// this runs from load() / load_from_path() while the source bytes are still
// reachable (the embedded blob, or the open fd), before map_segments(). Each
// executable, allocated section must lie inside the file-backed bytes of some
// PF_X PT_LOAD; one that does not means the table cannot be trusted (packers are
// known to forge section headers), so the whole table is rejected and the passes
// fall back to the whole segment, the pre-2026-09-27 behaviour.
//
// What a usable table can and cannot do. The passes spare a word only when it is
// POSITIVELY inside a non-executable section here and inside no executable one,
// and they rewrite a word that lies in NO section (counting it in the load log).
// So a table whose SHF_EXECINSTR sections simply fail to cover some code does
// not make us skip that code: uncovered bytes are treated as code. What a table
// CAN still do is label real code as a non-executable section; nothing in the
// table alone can refute that, and such a word would stay `svc #0` and reach the
// SIGSYS/x16 hazard path it had before the BRK rewrite existed. That residual is
// the price of reading sections at all (docs/sigchain-libsigchain-parity_
// 2026-09-27.md). Measured corpus impact of the no-section rule: zero; no word
// of the 102 installed arm64 libraries lies in no section. Stdout-quiet: the log
// lines are printed by the pass that uses the result.
void Image::read_exec_sections(const Elf64_Ehdr& eh, const uint8_t* blob,
                               uint64_t file_size, int fd) {
    exec_ranges_.clear();
    data_ranges_.clear();
    exec_ranges_state_ = 0;
    exec_ranges_why_ = "MACAPK_SVC_BRK_EXEC_ONLY=0 and MACAPK_X18_EXEC_ONLY=0";
    if (!macapk_svc_brk_exec_only() && !macapk_x18_exec_only()) return;
    exec_ranges_state_ = -1;
    if (eh.e_shoff == 0) { exec_ranges_why_ = "no section header table (e_shoff=0)"; return; }
    if (eh.e_shentsize != sizeof(Elf64_Shdr)) {
        exec_ranges_why_ = "unexpected e_shentsize"; return;
    }
    auto read_at = [&](void* dst, uint64_t len, uint64_t off) -> bool {
        if (off > file_size || len > file_size - off) return false;
        if (blob) { std::memcpy(dst, blob + off, size_t(len)); return true; }
        return fd >= 0 && macapk_pread_full(fd, dst, size_t(len), off);
    };
    uint64_t shnum = eh.e_shnum;
    if (shnum == 0) {
        // gABI extended numbering: e_shnum == 0 with a table present means the
        // real count is in section 0's sh_size.
        Elf64_Shdr s0{};
        if (!read_at(&s0, sizeof(s0), eh.e_shoff)) {
            exec_ranges_why_ = "section header 0 unreadable"; return;
        }
        shnum = s0.sh_size;
    }
    if (shnum == 0 || shnum > (1u << 20)) { exec_ranges_why_ = "implausible section count"; return; }
    std::vector<Elf64_Shdr> sh(static_cast<size_t>(shnum));
    if (!read_at(sh.data(), shnum * sizeof(Elf64_Shdr), eh.e_shoff)) {
        exec_ranges_why_ = "section header table runs past EOF"; return;
    }
    std::vector<std::pair<uint64_t, uint64_t>> r, d;
    for (const Elf64_Shdr& s : sh) {
        if (!(s.sh_flags & kShfAlloc)) continue;
        if (s.sh_type == kShtNobits || s.sh_size == 0) continue;
        const uint64_t lo = s.sh_addr;
        const uint64_t hi = lo + s.sh_size;
        if (!(s.sh_flags & kShfExecinstr)) {
            // Data. Only the part of it inside a PF_X PT_LOAD's file bytes is
            // ever scanned, so keep exactly that intersection (a wrapped or
            // outside section contributes nothing and is never trusted to spare).
            if (hi < lo) continue;
            for (const auto& ph : phdrs_) {
                if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_X)) continue;
                const uint64_t a = std::max(lo, uint64_t(ph.p_vaddr));
                const uint64_t b = std::min(hi, uint64_t(ph.p_vaddr + ph.p_filesz));
                if (a < b) d.emplace_back(a, b);
            }
            continue;
        }
        if (hi < lo) { exec_ranges_why_ = "an SHF_EXECINSTR section wraps"; return; }
        bool inside = false;
        for (const auto& ph : phdrs_) {
            if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_X)) continue;
            if (lo >= ph.p_vaddr && hi <= ph.p_vaddr + ph.p_filesz) { inside = true; break; }
        }
        if (!inside) {
            exec_ranges_why_ = "an SHF_EXECINSTR section lies outside every PF_X PT_LOAD";
            return;
        }
        r.emplace_back(lo, hi);
    }
    if (r.empty()) { exec_ranges_why_ = "no SHF_EXECINSTR section"; return; }
    auto merge_into = [](std::vector<std::pair<uint64_t, uint64_t>>& in,
                         std::vector<std::pair<uint64_t, uint64_t>>& out) {
        std::sort(in.begin(), in.end());
        for (const auto& x : in) {
            if (!out.empty() && x.first <= out.back().second)
                out.back().second = std::max(out.back().second, x.second);
            else
                out.push_back(x);
        }
    };
    merge_into(r, exec_ranges_);
    merge_into(d, data_ranges_);
    exec_ranges_state_ = 1;
    exec_ranges_why_ = "ok";
}

namespace {
bool vaddr_in_ranges(const std::vector<std::pair<uint64_t, uint64_t>>& v, uint64_t fv) {
    auto it = std::upper_bound(v.begin(), v.end(), std::make_pair(fv, ~uint64_t(0)));
    if (it == v.begin()) return false;
    --it;
    return fv >= it->first && fv < it->second;
}
}  // namespace

bool Image::text_word_is_data(uint64_t file_vaddr, bool* nosec) const {
    *nosec = false;
    if (vaddr_in_ranges(exec_ranges_, file_vaddr)) return false;     // code
    if (vaddr_in_ranges(data_ranges_, file_vaddr)) return true;      // positively data
    *nosec = true;                                                   // no section: code
    return false;
}

uint64_t Image::first_data_vaddr_in(uint64_t lo, uint64_t hi) const {
    for (const auto& d : data_ranges_) {          // sorted, merged
        if (d.second <= lo) continue;
        if (d.first >= hi) break;
        uint64_t a = std::max(d.first, lo);
        // A byte in both an executable and a non-executable section is code, as
        // in text_word_is_data; walk past any executable overlap.
        while (a < d.second && a < hi && vaddr_in_ranges(exec_ranges_, a)) {
            auto it = std::upper_bound(exec_ranges_.begin(), exec_ranges_.end(),
                                       std::make_pair(a, ~uint64_t(0)));
            a = (--it)->second;
        }
        if (a < d.second && a < hi) return a;
    }
    return hi;
}

size_t Image::apply_svc_brk_rewrite() {
    // DEFAULT-ON (opt out MACAPK_SVC_BRK=0). Validated on IG (boots to the main
    // feed, 5/5) and the protected sweep (12/12 alive, Stage-6 arm64 + ARM32).
    // Must move in lockstep with the SIGTRAP emulator install in bionic_shim.cpp
    // (rewriting svc->BRK without the handler = unhandled SIGTRAP = crash).
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_SVC_BRK");
        return !e || e[0] != '0';
    }();
    if (!on || !base_) return 0;
    constexpr uint32_t kSvc0 = 0xD4000001u;                    // svc #0
    constexpr uint32_t kBrk  = 0xD4200000u | (0x4D41u << 5);   // BRK #0x4D41
    // exec_only needs MACAPK_SVC_BRK_EXEC_ONLY on AND a usable section table
    // (exec_ranges_state_ 1; the table is also read for MACAPK_X18_EXEC_ONLY, so
    // the state alone no longer implies this gate); otherwise the whole-segment
    // scan, exactly as before.
    const bool exec_only = macapk_svc_brk_exec_only() && exec_ranges_state_ == 1;
    size_t n = 0, spared = 0, nosec_n = 0;
    for (const auto& ph : phdrs_) {
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_X)) continue;
        uint8_t* lo = base_ + ph.p_vaddr;
        uint8_t* hi = lo + ph.p_filesz;
        size_t seg_n = 0;
        for (uint32_t* p = reinterpret_cast<uint32_t*>(lo);
             reinterpret_cast<uint8_t*>(p + 1) <= hi; ++p) {
            if (*p != kSvc0) continue;
            if (exec_only) {
                bool nosec = false;
                if (text_word_is_data(uint64_t(reinterpret_cast<uint8_t*>(p) - base_), &nosec)) {
                    ++spared;              // data (LSDA/rodata) that looks like svc #0
                    continue;
                }
                if (nosec) ++nosec_n;      // in no section: treated as code
            }
            *p = kBrk; ++seg_n;
        }
        if (seg_n) { sys_icache_invalidate(lo, ph.p_filesz); n += seg_n; }
    }
    if (macapk_svc_brk_exec_only() && exec_ranges_state_ == -1 && n) {
        std::fprintf(stderr,
            "[arm64-native] %s: svc->BRK: NO usable section headers (%s) -- scanned "
            "the WHOLE PF_X segment, so any 0xD4000001 DATA word in it (C++ LSDA, "
            "rodata) was rewritten too (MACAPK_SVC_BRK_EXEC_ONLY fallback)\n",
            dbg_name_, exec_ranges_why_);
    }
    if (spared) {
        std::fprintf(stderr,
            "[arm64-native] %s: svc->BRK left %zu 0xD4000001 word(s) in NON-executable "
            "sections of the PF_X segment untouched (data, not `svc #0`; "
            "MACAPK_SVC_BRK_EXEC_ONLY)\n", dbg_name_, spared);
    }
    if (nosec_n) {
        std::fprintf(stderr,
            "[arm64-native] %s: svc->BRK rewrote %zu 0xD4000001 word(s) that lie in NO "
            "section of the PF_X segment (treated as code: only a word positively inside "
            "a non-executable section is spared; MACAPK_SVC_BRK_EXEC_ONLY)\n",
            dbg_name_, nosec_n);
    }
    return n;
}

size_t Image::apply_raw_syscall_wrapper_redirect() {
    static const bool enabled = []{
        const char* e = std::getenv("MACAPK_RAW_SYSCALL_REDIRECT");
        return !(e && e[0] == '0');
    }();
    if (!enabled) return 0;

    // The eight-word prefix, exactly as Bionic's syscall.S emits it.
    static const uint32_t kPrefix[8] = {
        0xaa0003e8u,  // mov x8, x0
        0xaa0103e0u,  // mov x0, x1
        0xaa0203e1u,  // mov x1, x2
        0xaa0303e2u,  // mov x2, x3
        0xaa0403e3u,  // mov x3, x4
        0xaa0503e4u,  // mov x4, x5
        0xaa0603e5u,  // mov x5, x6
        0xd4000001u,  // svc #0
    };
    static const uint32_t kRet       = 0xd65f03c0u;  // ret
    static const uint32_t kCmnErrno  = 0xb13ffc1fu;  // cmn x0, #0xfff

    void* raw_fn  = dlsym(RTLD_DEFAULT, "__macapk_arm64_raw_syscall");
    void* libc_fn = dlsym(RTLD_DEFAULT, "__macapk_arm64_syscall");
    if (!raw_fn || !libc_fn) {
        std::fprintf(stderr,
            "[arm64-native] %s: raw-syscall redirect skipped (router symbols "
            "not found: raw=%p libc=%p)\n", dbg_name_, raw_fn, libc_fn);
        return 0;
    }

    size_t patched = 0;
    for (const auto& sg : segments_) {
        if (!(sg.prot & PROT_EXEC)) continue;
        if (sg.memsz < sizeof(kPrefix) + 8) continue;
        uint32_t* lo  = reinterpret_cast<uint32_t*>(sg.vaddr);
        // -9 so the trailing-instruction read below stays in bounds.
        uint32_t* end = lo + (sg.memsz / 4) - 9;
        for (uint32_t* p = lo; p < end; ++p) {
            if (*p != kPrefix[0]) continue;              // cheap reject
            if (std::memcmp(p, kPrefix, sizeof(kPrefix)) != 0) continue;
            const uint32_t tail = p[8];
            void* target = nullptr;
            const char* why = nullptr;
            if (tail == kRet)           { target = raw_fn;  why = "raw kernel convention (svc; ret)"; }
            else if (tail == kCmnErrno) { target = libc_fn; why = "libc convention (Bionic errno adapter follows)"; }
            else {
                std::fprintf(stderr,
                    "[arm64-native] %s: raw-syscall wrapper at +%#llx has an "
                    "UNVERIFIED trailing insn %#010x -- left alone (its return "
                    "convention is unknown and guessing would corrupt every "
                    "error return)\n",
                    dbg_name_,
                    (unsigned long long)(reinterpret_cast<uintptr_t>(p)
                     - reinterpret_cast<uintptr_t>(base_)),
                    tail);
                continue;
            }
            // .text-patch-FREE by default (owner directive, 2026-09-16): overwriting the
            // wrapper's bytes is detected by any .text self-hash (pairip's is the motivating
            // case, but it is not the only integrity scheme that hashes its own code), so for
            // EVERY image we instead strip PROT_EXEC from the wrapper's 16 KB page (bytes stay
            // UNCHANGED) and service the exec-fault via the guard below. The in-place .text
            // patch that follows is the automatic fallback for when the guard cannot be placed
            // (no free page within +/-1MB, guard table full). Force the old in-place patch with
            // MACAPK_GUARD_SYSCALL=0.
            {
                extern bool macapk_setup_syscall_guard_page(uintptr_t, uintptr_t, const char*);
                static const bool s_guard = []{
                    const char* e = std::getenv("MACAPK_GUARD_SYSCALL");
                    return !(e && e[0] == '0');
                }();
                if (s_guard) {
                    if (macapk_setup_syscall_guard_page(
                            reinterpret_cast<uintptr_t>(p),
                            reinterpret_cast<uintptr_t>(base_), dbg_name_)) {
                        ++patched; p += 3; continue;   // handled; .text UNCHANGED
                    }
                    // guard setup failed -> fall through to the in-place .text patch
                }
            }
            // 16-byte absolute redirect, the same idiom the libz redirect uses:
            //   ldr x16, [pc, #8] ; br x16 ; .quad target
            // A `b` would not reach: the shim dylib is far outside the guest
            // image's +/-128MB range. The wrapper is at least nine words long,
            // so sixteen bytes never runs past its own body. The guest arrived
            // by `bl`, so lr already holds its return address and the router
            // returns straight to the caller.
            uint32_t code[2] = { 0x58000050u, 0xd61f0200u };
            uint64_t addr = reinterpret_cast<uint64_t>(target);
            std::memcpy(p, code, sizeof(code));
            std::memcpy(p + 2, &addr, sizeof(addr));
            sys_icache_invalidate(p, 16);
            std::fprintf(stderr,
                "[arm64-native] %s: raw-syscall wrapper at +%#llx -> %s "
                "(every raw syscall this image makes now reaches the router "
                "with its arguments intact)\n",
                dbg_name_,
                (unsigned long long)(reinterpret_cast<uintptr_t>(p)
                     - reinterpret_cast<uintptr_t>(base_)),
                why);
            ++patched;
            p += 3;   // skip the bytes we just wrote
        }
    }
    return patched;
}

// ===========================================================================
// Guard-page syscall redirect -- the .text-patch-FREE replacement for the wrapper redirect
// above. DEFAULT ON for every image (owner directive 2026-09-16); MACAPK_GUARD_SYSCALL=0
// forces the old in-place .text patch.
//
// An integrity scheme that self-hashes its own .text (pairip's libpairipcore is the motivating
// case: ANY byte change to the raw-syscall wrapper at +0x16990 detonates the +0x440c4 check --
// device-proven, textpoke: docs/pairip-text-selfhash-root-cause_2026-09-16) rejects an in-place
// redirect. So instead of overwriting the wrapper we strip PROT_EXEC from its 16 KB page (bytes
// UNCHANGED -> the self-hash passes) and service the resulting exec-fault in macapk_segv_handler
// (bionic_shim.cpp macapk_syscall_guard_fault):
//   fault PC == wrapper   -> __macapk_arm64_raw_syscall(x0,x1..x6); x0 = result; PC = x30
//   fault PC == neighbour -> resume in a RELOCATED EXECUTABLE COPY of the page
// The page holds other hot Bionic-libc functions (~23 / 247 call sites in libpairipcore), so the
// copy is required. It is a byte-copy whose PC-relative CODE refs (adrp/adr/unconditional b/bl,
// and off-page conditional branches) are re-encoded to the ORIGINAL absolute targets and whose
// literal loads are re-pointed to the original page, so mis-decoded literal-pool words in the
// copy are never read or executed. The maps synthesiser reports the guarded page r-x (permission
// is a detection vector). Several images can each guard a page (see the g_sc_guards table).
// ===========================================================================
extern "C" void __macapk_set_syscall_guard_page(uintptr_t page_lo, uintptr_t page_hi,
                                                uintptr_t shadow_base, uintptr_t wrapper_va);

// Re-encode one CODE PC-relative instruction (word at shadow addr `sa`, originally at `oa`)
// so it still references the same ABSOLUTE target. Only adrp/adr/unconditional-b/bl are
// touched; ldr-literal, b.cond, cbz/tbz and data are left alone (self-relative + byte-copy).
static void macapk_guard_reencode(uint32_t* w, uint64_t oa, uint64_t sa) {
    uint32_t insn = *w;
    // ADRP/ADR: [28:24]=10000; [31]=op(1=adrp), [30:29]=immlo, [23:5]=immhi, [4:0]=Rd
    if ((insn & 0x1f000000u) == 0x10000000u) {
        uint32_t immlo = (insn >> 29) & 3u;
        int64_t  immhi = (int32_t)((insn >> 5) & 0x7ffffu);
        int64_t  imm   = (immhi << 2) | immlo;
        if (imm & (1LL << 20)) imm |= ~((int64_t)0x1fffff);      // sign-extend 21 bits
        bool adrp = (insn >> 31) & 1u;
        uint64_t target = adrp ? ((oa & ~(uint64_t)0xfff) + ((uint64_t)imm << 12))
                               : (oa + (uint64_t)imm);
        int64_t nimm = adrp ? (int64_t)((target >> 12) - (sa >> 12))
                            : (int64_t)(target - sa);
        if (nimm < -(1LL<<20) || nimm >= (1LL<<20)) return;      // out of range: leave as-is
        uint32_t u = (uint32_t)(nimm & 0x1fffff);
        *w = (insn & 0x9f00001fu) | ((u & 3u) << 29) | (((u >> 2) & 0x7ffffu) << 5);
        return;
    }
    // Unconditional B (0x14000000) / BL (0x94000000): [30:26]=00101, [25:0]=imm26
    if ((insn & 0x7c000000u) == 0x14000000u) {
        int64_t imm26 = insn & 0x03ffffffu;
        if (imm26 & (1LL << 25)) imm26 |= ~((int64_t)0x03ffffff);
        uint64_t target = oa + ((uint64_t)imm26 << 2);
        int64_t nrel = ((int64_t)(target - sa)) >> 2;
        if (nrel < -(1LL<<25) || nrel >= (1LL<<25)) return;
        *w = (insn & 0xfc000000u) | ((uint32_t)nrel & 0x03ffffffu);
        return;
    }
    // LDR/LDRSW/PRFM/SIMD-LDR (literal): [31:24] in {18,58,98,d8, 1c,5c,9c}, [23:5]=imm19.
    // Re-point to the ORIGINAL literal on page R so the copy's own literal-pool bytes are
    // NEVER read -- this removes the entire mis-relocated-literal class. Needs the shadow
    // within +/-1MB of R (macapk_setup_syscall_guard_page places it there).
    uint32_t hi8 = insn >> 24;
    if (hi8==0x18u||hi8==0x58u||hi8==0x98u||hi8==0xd8u||hi8==0x1cu||hi8==0x5cu||hi8==0x9cu) {
        int64_t imm19 = (int32_t)((insn >> 5) & 0x7ffffu);
        if (imm19 & (1LL<<18)) imm19 |= ~((int64_t)0x7ffff);
        uint64_t target = oa + ((uint64_t)imm19 << 2);          // original literal address
        int64_t nrel = ((int64_t)(target - sa)) >> 2;
        if (nrel < -(1LL<<18) || nrel >= (1LL<<18)) return;     // out of +/-1MB: leave self-rel
        *w = (insn & 0xff00001fu) | (((uint32_t)nrel & 0x7ffffu) << 5);
        return;
    }
    // Conditional branches (b.cond / cbz/cbnz / tbz/tbnz): intra-function, and a rigid byte-copy
    // keeps them correct WHILE their target stays on the copied page (target moves with the copy).
    // But a function STRADDLING the 16 KB page boundary can carry one whose target lies OFF the
    // guarded page; a byte-copy would then land it `delta` away from the real target. So if the
    // target is off-page, re-point it to the ORIGINAL absolute target (like b/bl); if on-page,
    // leave it self-relative. page_lo = the containing 16 KB page of the ORIGINAL word.
    {
        const uint64_t page_lo = oa & ~(uint64_t)0x3fff;
        const uint64_t page_hi = page_lo + 0x4000;
        const bool bcond = (insn & 0xff000010u) == 0x54000000u;   // b.cond
        const bool cbz   = (insn & 0x7e000000u) == 0x34000000u;   // cbz/cbnz
        const bool tbz   = (insn & 0x7e000000u) == 0x36000000u;   // tbz/tbnz
        if (bcond || cbz || tbz) {
            int64_t imm;
            if (tbz) { imm = (int32_t)((insn >> 5) & 0x3fffu);    // signed imm14<<2 (+/-32KB)
                       if (imm & (1LL<<13)) imm |= ~((int64_t)0x3fff); }
            else     { imm = (int32_t)((insn >> 5) & 0x7ffffu);   // signed imm19<<2 (+/-1MB)
                       if (imm & (1LL<<18)) imm |= ~((int64_t)0x7ffff); }
            uint64_t target = oa + ((uint64_t)imm << 2);
            if (target >= page_lo && target < page_hi) return;    // on-page: byte-copy is correct
            int64_t nrel = ((int64_t)(target - sa)) >> 2;         // off-page: reach original target
            if (tbz) {
                if (nrel < -(1LL<<13) || nrel >= (1LL<<13)) return;
                *w = (insn & 0xfff8001fu) | (((uint32_t)nrel & 0x3fffu) << 5);
            } else {
                if (nrel < -(1LL<<18) || nrel >= (1LL<<18)) return;
                *w = (insn & 0xff00001fu) | (((uint32_t)nrel & 0x7ffffu) << 5);
            }
            return;
        }
    }
    // else: data / an unhandled PC-relative form -- unchanged
}

// Build the relocated executable shadow of the wrapper's 16 KB page + strip X from the
// original. Returns true on success (or if the page is already guarded). A blind per-word
// re-encode keeps literal pools harmless, so no disassembler is needed.
bool macapk_setup_syscall_guard_page(uintptr_t wrapper_va, uintptr_t base,
                                     const char* dbg_name) {
    const uintptr_t page_lo = wrapper_va & ~(uintptr_t)0x3fff;    // 16 KB page
    const size_t    pgsz    = 0x4000;
    const uintptr_t page_hi = page_lo + pgsz;

    // 0. dedup: a second wrapper on an already-guarded page (same image re-scanned, or two
    //    wrappers sharing one 16 KB page) is already serviced by the existing guard. Report
    //    success WITHOUT a second shadow/strip so the caller counts it and skips the .text patch.
    static std::set<uintptr_t> s_guarded_pages;
    if (s_guarded_pages.count(page_lo)) return true;

    // 1. shadow: place within +/-1MB of the page so re-pointed ldr-literals reach the
    //    originals (well within b/bl +/-128MB + adrp +/-4GB). The image occupies [base,..];
    //    free space is BELOW it, so try a few hints there and require a close landing.
    void* shadow = MAP_FAILED;
    for (int64_t off : { -(int64_t)0x40000, -(int64_t)0x80000, -(int64_t)0xC0000,
                         -(int64_t)0xE0000 }) {
        void* m = mmap(reinterpret_cast<void*>((intptr_t)page_lo + off), pgsz,
                       PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (m == MAP_FAILED) continue;
        int64_t d = (int64_t)((intptr_t)m - (intptr_t)page_lo);
        if (d > -(int64_t)0xF0000 && d < (int64_t)0xF0000) { shadow = m; break; }
        munmap(m, pgsz);
    }
    if (shadow == MAP_FAILED) {
        std::fprintf(stderr, "[guard] %s: could not place shadow within 1MB of %#lx -- "
                     "falling back to .text patch\n", dbg_name, (unsigned long)page_lo);
        return false;
    }
    (void)base;
    std::memcpy(shadow, reinterpret_cast<void*>(page_lo), pgsz);

    // 2. BLIND per-word re-encode: every adrp/adr/unconditional-b/bl/ldr-literal is re-encoded
    //    to reference the ORIGINAL page. Because ALL data reads then resolve to R (adrp -> R
    //    page, ldr-literal -> R literal), a mis-decoded literal-pool word in the copy is harmless
    //    (never read, never executed), so no code/data separation is needed. Conditional
    //    branches (b.cond/cbz/tbz) stay self-relative (intra-function, they move with the copy).
    size_t relocs = 0, ncount = pgsz / 4;
    for (uintptr_t off = 0; off < pgsz; off += 4) {
        uint32_t* sw = reinterpret_cast<uint32_t*>((uintptr_t)shadow + off);
        uint32_t before = *sw;
        macapk_guard_reencode(sw, page_lo + off, (uintptr_t)shadow + off);
        if (*sw != before) relocs++;
    }

    // 3. shadow -> RX ; original page -> R-- (guard). Bytes on the original are UNCHANGED.
    sys_icache_invalidate(shadow, pgsz);
    if (mprotect(shadow, pgsz, PROT_READ | PROT_EXEC) != 0) {
        std::fprintf(stderr, "[guard] %s: shadow mprotect RX failed (errno=%d)\n", dbg_name, errno);
        return false;
    }
    // DEFER the strip-X (apply_segment_protections would otherwise re-flip this page R-X).
    if (g_pending_guard_strip_n < (int)(sizeof(g_pending_guard_strip_lo)/sizeof(uintptr_t))) {
        g_pending_guard_strip_lo[g_pending_guard_strip_n] = page_lo;
        g_pending_guard_strip_sz[g_pending_guard_strip_n] = pgsz;
        ++g_pending_guard_strip_n;
    }
    s_guarded_pages.insert(page_lo);
    __macapk_set_syscall_guard_page(page_lo, page_hi, (uintptr_t)shadow, wrapper_va);
    std::fprintf(stderr,
        "[guard] %s: syscall wrapper +%#llx serviced via GUARD-PAGE (page %#lx R--, shadow %p, "
        "%zu reloc/%zu insn) -- .text UNCHANGED so the self-hash passes\n",
        dbg_name, (unsigned long long)(wrapper_va - base), (unsigned long)page_lo, shadow,
        relocs, ncount);
    return true;
}

// ---------------------------------------------------------------------------
// Go c-shared libraries: the runtime's `g` lives at TPIDR_EL0 + 16.
//
// A Go library built with -buildmode=c-shared for android/arm64 (react-native-
// fast-rsa's librsa_bridge.so in the Burger King app; Go 1.24.2) keeps the
// current goroutine pointer `g` in Bionic's TLS_SLOT_APP. Go's own source, at
// the build's tag (go1.24.2):
//   src/runtime/tls_arm64.h   GOOS_android: TLS_linux + TLSG_IS_VARIABLE,
//                             MRS_TPIDR_R0 = WORD $0xd53bd040 (MRS TPIDR_EL0, R0)
//   src/runtime/tls_arm64.s   load_g: MRS_TPIDR_R0; MOVD runtime.tls_g, R27;
//                             MOVD (R0)(R27), g   -- save_g stores the same way;
//                             DATA runtime.tls_g/8, $16  ("Use the free
//                             TLS_SLOT_APP slot #2 on Android Q")
//   src/runtime/cgo/gcc_android.c inittls: when libc has
//                             android_get_device_api_level, VERIFY tls_g == 16
//                             (fatalf "tlsg offset wrong" otherwise)
// Bionic: libc/private/bionic_asm_tls.h, TLS_SLOT_APP = 2 on arm64.
//
// On macOS TPIDR_EL0 is not a thread pointer: XNU rewrites it with per-CPU data
// on every context switch (see the TLSDESC notes above), so Go's `str g,[tp,#16]`
// faults, every `ldr g,[tp,#16]` reads nothing, and the runtime throws "fatal
// error" in a loop on its first goroutine switch -- the app never gets past its
// splash. The per-thread pointer macOS DOES give user code is TPIDRRO_EL0, the
// pthread TSD base: libsyscall os/tsd.h _os_tsd_get_base(), and
// pthread_getspecific(k) is exactly tsd[k]. That is how Go itself finds `g` on
// darwin/arm64 (tls_arm64.h TLS_darwin: MRS TPIDRRO_EL0, tls_g = a pthread key's
// slot offset). So each `mrs Xt, TPIDR_EL0` in a Go image is detoured to
//     mrs Xt, TPIDRRO_EL0 ; and Xt, Xt, #~7 ; add Xt, Xt, #(8*K - 16) ; b back
// with K one pthread key reserved here: Go's unchanged `[Xt + tls_g(16)]` is then
// this thread's tsd[K], a zero-initialised per-thread word, which is what
// TLS_SLOT_APP is to it on a device (zero on a thread Go has not entered, so its
// needm path for foreign-thread callbacks works as designed). Only Xt changes, as
// with the original instruction, so no other register or flag is disturbed.
//
// Gate: the image is a Go cgo runtime (it exports crosscall2 and
// _cgo_topofstack, which every cgo-built Go shared library does). That is an
// engine test, like the il2cpp/cocos ones, never an app test. Nothing else
// reads TPIDR_EL0 through this pass. MACAPK_GO_TLS=0 turns it off.
// ---------------------------------------------------------------------------
static pthread_key_t g_go_tls_key;
static bool g_go_tls_key_ok = false;
static pthread_once_t g_go_tls_once = PTHREAD_ONCE_INIT;
static void go_tls_key_init() {
    g_go_tls_key_ok = pthread_key_create(&g_go_tls_key, nullptr) == 0;
}

size_t Image::apply_go_tls_rewrite() {
    if (const char* e = std::getenv("MACAPK_GO_TLS"); e && e[0] == '0') return 0;
    if (!lookup("crosscall2") || !lookup("_cgo_topofstack")) return 0;

    // Count the sites first so the pool is sized once.
    std::vector<uint32_t*> sites;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC) || s.memsz < 4) continue;
        uint32_t* lo = reinterpret_cast<uint32_t*>(s.vaddr);
        uint32_t* end = lo + (s.memsz / 4);
        for (uint32_t* p = lo; p < end; ++p) {
            uint32_t insn = *p;
            // mrs Xt, TPIDR_EL0 (op0=3 op1=3 CRn=13 CRm=0 op2=2); Rt=31 is a
            // discard and needs nothing.
            if ((insn & 0xFFFFFFE0u) == 0xD53BD040u && (insn & 0x1Fu) != 31) {
                if (exec_ranges_state_ == 1) {
                    bool nosec = false;
                    if (text_word_is_data(uint64_t(reinterpret_cast<uint8_t*>(p) - base_), &nosec))
                        continue;
                }
                sites.push_back(p);
            }
        }
    }
    if (sites.empty()) return 0;

    pthread_once(&g_go_tls_once, go_tls_key_init);
    const uint64_t off = g_go_tls_key_ok ? uint64_t(g_go_tls_key) * 8 : 0;
    if (!g_go_tls_key_ok || off < 16 || off - 16 >= (1u << 24)) {
        std::fprintf(stderr, "[go-tls] %s: no usable pthread key (ok=%d key=%lu) -- "
                     "Go runtime left unpatched\n", dbg_name_, int(g_go_tls_key_ok),
                     (unsigned long)(g_go_tls_key_ok ? g_go_tls_key : 0));
        return 0;
    }
    const uint32_t delta = uint32_t(off - 16);

    // Place the pool within `b` range of the whole image, closest first (the
    // x18 pool's placement, for the same reason: a bare mmap hint is advisory).
    uint64_t img_lo = reinterpret_cast<uint64_t>(base_);
    uint64_t img_hi = img_lo + mapped_size_;
    const size_t kWords = 5;
    size_t cap = (sites.size() * kWords * 4 + page_size_ - 1) & ~(size_t)(page_size_ - 1);
    void* pool = MAP_FAILED;
    for (int attempt = 0; attempt < 24 && pool == MAP_FAILED; ++attempt) {
        uint64_t hint;
        if (attempt == 0)       hint = img_hi;
        else if (attempt <= 12) hint = img_hi + (uint64_t)attempt * (4ull << 20);
        else {
            uint64_t back = (uint64_t)(attempt - 12) * (4ull << 20) + cap;
            if (back >= img_lo) continue;
            hint = img_lo - back;
        }
        hint &= ~(uint64_t)(page_size_ - 1);
        void* got = mmap(reinterpret_cast<void*>(hint), cap, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
        if (got == MAP_FAILED) continue;
        uint64_t p_lo = reinterpret_cast<uint64_t>(got), p_hi = p_lo + cap;
        const uint64_t kNear = 64ull << 20;
        if ((p_lo >= img_hi && p_lo - img_hi < kNear) ||
            (p_hi <= img_lo && img_lo - p_hi < kNear)) { pool = got; break; }
        munmap(got, cap);
    }
    if (pool == MAP_FAILED) {
        std::fprintf(stderr, "[go-tls] %s: no thunk pool within branch range -- "
                     "Go runtime left unpatched\n", dbg_name_);
        return 0;
    }
    go_tls_thunks_ = static_cast<uint8_t*>(pool);
    go_tls_thunks_cap_ = cap;

    auto in_range = [](const void* a, const void* b) {
        int64_t d = int64_t(reinterpret_cast<uintptr_t>(b)) - int64_t(reinterpret_cast<uintptr_t>(a));
        return d >= -(int64_t(128) << 20) && d < (int64_t(128) << 20);
    };
    uint32_t* t = reinterpret_cast<uint32_t*>(pool);
    size_t done = 0;
    for (uint32_t* p : sites) {
        if (!in_range(p, t) || !in_range(t + 4, p + 1)) continue;
        ++done;
        const uint32_t rt = *p & 0x1Fu;
        uint32_t* th = t;
        *t++ = 0xD53BD060u | rt;                          // mrs Xt, TPIDRRO_EL0
        *t++ = 0x927DF000u | (rt << 5) | rt;              // and Xt, Xt, #~7
        *t++ = 0x91000000u | ((delta & 0xFFFu) << 10) | (rt << 5) | rt;  // add #lo
        *t++ = 0x91400000u | (((delta >> 12) & 0xFFFu) << 10) | (rt << 5) | rt; // add #hi,lsl12
        *t = encode_b(reinterpret_cast<uintptr_t>(t), reinterpret_cast<uintptr_t>(p + 1));
        ++t;
        *p = encode_b(reinterpret_cast<uintptr_t>(p), reinterpret_cast<uintptr_t>(th));
    }
    sys_icache_invalidate(pool, cap);
    if (mprotect(pool, cap, PROT_READ | PROT_EXEC) != 0) {
        std::fprintf(stderr, "[go-tls] %s: thunk pool mprotect RX failed: %s\n",
                     dbg_name_, std::strerror(errno));
    }
    if (done != sites.size())
        std::fprintf(stderr, "[go-tls] %s: %zu site(s) out of branch range left as is\n",
                     dbg_name_, sites.size() - done);
    return done;
}

// MACAPK_MIDR_EMULATE lives in bionic_shim.cpp next to the SIGILL handler that
// answers the read, so the two can never disagree about whether it is on.
extern "C" int __macapk_midr_emulate_on(void);

size_t Image::apply_midr_el1_neutralization() {
    // RETIRED by default: the read is answered at run time by
    // macapk_sigill_handler, as the Linux kernel answers it (see "Replace every
    // `mrs Xn, MIDR_EL1`" further up this file, the block that ends in RETIRED BY
    // DEFAULT). =0 restores the rewrite.
    if (__macapk_midr_emulate_on()) return 0;
    // `mrs Xt, MIDR_EL1` encoding: bits 31..5 = 0xD5380000 / 0x20
    // shifted; mask = 0xFFFFFFE0, match = 0xD5380000.
    size_t patched = 0, spared = 0;
    // MACAPK_SVC_BRK_EXEC_ONLY (state 1): the same rule as the svc->BRK pass --
    // a word positively inside a non-executable section is data and is left
    // alone; everything else (an SHF_EXECINSTR section, or no section) is
    // rewritten as before. State 0/-1: the whole segment, exactly as before.
    const bool exec_only = macapk_svc_brk_exec_only() && exec_ranges_state_ == 1;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < 4) continue;
        uint32_t* lo = reinterpret_cast<uint32_t*>(s.vaddr);
        uint32_t* end = lo + (s.memsz / 4);
        for (uint32_t* p = lo; p < end; ++p) {
            uint32_t insn = *p;
            if ((insn & 0xFFFFFFE0u) != 0xD5380000u) continue;
            if (exec_only) {
                bool nosec = false;
                if (text_word_is_data(uint64_t(reinterpret_cast<uint8_t*>(p) - base_), &nosec)) {
                    ++spared;
                    continue;
                }
            }
            uint32_t rt = insn & 0x1Fu;
            *p = 0xD2800000u | rt;          // mov Xt, #0
            ++patched;
        }
    }
    if (spared) {
        std::fprintf(stderr,
            "[arm64-native] %s: MIDR_EL1 rewrite left %zu MIDR-shaped word(s) in NON-executable "
            "sections of the PF_X segment untouched (MACAPK_SVC_BRK_EXEC_ONLY)\n",
            dbg_name_, spared);
    }
    return patched;
}

// ---------------------------------------------------------------------------
// The canary pass's REPLAY cache (v1.0.1635).
//
// WHY. apply_stack_canary_neutralization_live below is the most expensive thing
// the loader does on EVERY launch, cold or warm: 1234 ms of libminecraftpe's
// load (MACAPK_LOAD_TIMING, 2026-09-22: loop pre-scan 439 ms over 1.28M back
// edges, then the dataflow, for 504,768 rewritten sites), 175 ms for libil2cpp,
// ~80 ms each for libunity and libEOSSDK. Its output is a pure function of its
// inputs, and its inputs do not change between launches of the same build.
//
// WHAT IT ANSWERS WITH. The pass's only effect on memory is replacing single
// 32-bit .text words (`mov Xt,#0`/`#mark`, and the TLSDESC `mrs`->`mov #0`); it
// writes no global state, no pool, nothing outside the image's exec segments,
// and every word it writes is POSITION-INDEPENDENT (a movz immediate, never a
// branch or an address). So the whole effect is exactly a list of
// (image-relative offset, new word) pairs. The first launch runs the pass live,
// diffs the exec segments against a snapshot taken just before it, and saves
// the list; later launches write the list back. That is byte-identical to
// running the pass whenever the key matches -- not "probably fine": the key is
// every input the pass reads.
//
// THE KEY, i.e. what the pass reads:
//   * the exec segments' bytes as they stand when the pass starts (a content
//     hash, so the earlier passes' patches and the ELF itself are covered;
//     which hash, and the version tag that names it, is the plan-key hash
//     block's MACAPK_PLAN_KEY_FASTHASH);
//   * the PLT relocations it uses to find the __stack_chk_fail stub;
//   * the five env knobs that change what it writes (WIDE_RELOAD, MARKER_FIX,
//     MARK, NO_CANARY_LOOP_CLEAR, OUTLINED_MRS), verbatim;
//   * THE PASS'S OWN CODE: this dylib's LC_UUID, which ld64 derives from the
//     output bytes, so any rebuild that changes the shim invalidates every plan
//     by itself -- nobody has to remember to bump a version (kX18PlanVersion
//     relies on exactly that memory; this one does not). A no-op rebuild keeps
//     the UUID, and the plans with it.
// MACAPK_CANARY_TRACE (a diagnostic that must see the live pass) bypasses the
// cache, as does MACAPK_CANARY_CACHE=0. MACAPK_CANARY_CACHE_VERIFY=1 runs the
// live pass on a hit and compares its diff with the plan, site by site.
//
// The plan lives next to the x18 plans (.x18cache/<soname>.canaryplan), so every
// tool that clears those (boottime --force-cold, ab_clear_code) clears it too.
static constexpr uint32_t kCanaryPlanMagic   = 0x31504E43u;  // 'CNP1'
static constexpr uint32_t kCanaryPlanVersion = 1;

// This dylib's LC_UUID -- the identity of the pass's own code.
static bool macapk_shim_uuid(uint8_t out[16]) {
    Dl_info di;
    if (!dladdr(reinterpret_cast<const void*>(&macapk_shim_uuid), &di) || !di.dli_fbase)
        return false;
    const auto* mh = static_cast<const mach_header_64*>(di.dli_fbase);
    if (mh->magic != MH_MAGIC_64) return false;
    const uint8_t* lc = reinterpret_cast<const uint8_t*>(mh + 1);
    for (uint32_t i = 0; i < mh->ncmds; ++i) {
        const auto* c = reinterpret_cast<const load_command*>(lc);
        if (c->cmd == LC_UUID) {
            std::memcpy(out, reinterpret_cast<const uuid_command*>(c)->uuid, 16);
            return true;
        }
        lc += c->cmdsize;
    }
    return false;
}

static void macapk_put_uleb(std::vector<uint8_t>& o, uint64_t v) {
    do { uint8_t b = v & 0x7f; v >>= 7; o.push_back(b | (v ? 0x80 : 0)); } while (v);
}
static bool macapk_get_uleb(const uint8_t*& p, const uint8_t* e, uint64_t& v) {
    v = 0;
    for (int sh = 0; sh < 64 && p < e; sh += 7) {
        uint8_t b = *p++;
        v |= (uint64_t)(b & 0x7f) << sh;
        if (!(b & 0x80)) return true;
    }
    return false;
}

size_t Image::apply_stack_canary_neutralization() {
    static const bool ct_on = std::getenv("MACAPK_LOAD_TIMING") != nullptr;
    const char* ce = std::getenv("MACAPK_CANARY_CACHE");
    const bool verify = std::getenv("MACAPK_CANARY_CACHE_VERIFY") &&
                        std::getenv("MACAPK_CANARY_CACHE_VERIFY")[0] == '1';
    std::string plan_path;
    if (!(ce && ce[0] == '0') && !std::getenv("MACAPK_CANARY_TRACE"))
        plan_path = macapk_x18_plan_path(dbg_name_);
    uint8_t uuid[16];
    if (!plan_path.empty() && !macapk_shim_uuid(uuid)) plan_path.clear();
    if (!plan_path.empty())
        plan_path = plan_path.substr(0, plan_path.size() - std::strlen(".x18plan")) + ".canaryplan";

    // The exec segments, as image-relative [off, off+len).
    struct XS { uint64_t off; size_t len; };
    std::vector<XS> xs;
    uint64_t xtotal = 0;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC) || s.memsz < 4) continue;
        xs.push_back({ (uint64_t)(s.vaddr - reinterpret_cast<uintptr_t>(base_)), s.memsz & ~(size_t)3 });
        xtotal += xs.back().len;
    }
    if (plan_path.empty() || xs.empty()) return apply_stack_canary_neutralization_live(nullptr);

    // ---- the key ----------------------------------------------------------
    std::string knobs;
    for (const char* k : { "MACAPK_CANARY_WIDE_RELOAD", "MACAPK_CANARY_MARKER_FIX",
                           "MACAPK_CANARY_MARK", "MACAPK_NO_CANARY_LOOP_CLEAR",
                           "MACAPK_CANARY_OUTLINED_MRS" }) {
        const char* v = std::getenv(k);
        knobs += k; knobs += '='; knobs += v ? v : "\x01"; knobs += ';';
    }
    uint64_t xhash = 1469598103934665603ull;
    for (const auto& x : xs) {
        xhash ^= x.off; xhash *= 1099511628211ull;
        xhash ^= x.len; xhash *= 1099511628211ull;
        xhash ^= macapk_plan_exec_hash(base_ + x.off, x.len); xhash *= 1099511628211ull;
    }
    uint64_t phash = 1469598103934665603ull;
    for (size_t i = 0; pltrel_ && i < pltrel_count_; ++i) {
        phash ^= pltrel_[i].r_offset; phash *= 1099511628211ull;
        phash ^= pltrel_[i].r_info;   phash *= 1099511628211ull;
    }
    auto in_exec = [&](uint64_t off) {
        for (const auto& x : xs) if (off >= x.off && off + 4 <= x.off + x.len) return true;
        return false;
    };

    // ---- try the plan -------------------------------------------------------
    std::vector<std::pair<uint64_t, uint32_t>> cached;
    uint64_t c_patched = 0, c_tpz = 0;
    bool hit = false;
    if (FILE* f = std::fopen(plan_path.c_str(), "rb")) {
        // Sized from fstat into a HEAP buffer. This runs on whatever thread
        // loads the library (a JNI System.loadLibrary caller, a dlopen from a
        // guest thread), so it must not put a large array on that thread's
        // stack: a frame bigger than the guard page can land in a NEIGHBOUR's
        // stack instead of faulting.
        std::vector<uint8_t> buf;
        struct stat st;
        if (fstat(fileno(f), &st) == 0 && st.st_size > 0 && st.st_size < (1ll << 30)) {
            buf.resize((size_t)st.st_size);
            if (std::fread(buf.data(), 1, buf.size(), f) != buf.size()) buf.clear();
        }
        std::fclose(f);
        const uint8_t* p = buf.data();
        const uint8_t* e = p + buf.size();
        auto rd = [&](void* d, size_t k) {
            if ((size_t)(e - p) < k) return false;
            std::memcpy(d, p, k); p += k; return true;
        };
        uint32_t magic = 0, ver = 0, klen = 0;
        uint8_t fu[16];
        uint64_t ftotal = 0, fx = 0, fp = 0, nsites = 0;
        bool ok = rd(&magic, 4) && magic == kCanaryPlanMagic
               && rd(&ver, 4) && ver == macapk_plan_disk_version(kCanaryPlanVersion)
               && rd(fu, 16) && std::memcmp(fu, uuid, 16) == 0
               && rd(&klen, 4) && klen == knobs.size()
               && (size_t)(e - p) >= klen && std::memcmp(p, knobs.data(), klen) == 0;
        if (ok) p += klen;
        ok = ok && rd(&ftotal, 8) && ftotal == xtotal
                && rd(&fx, 8) && fx == xhash
                && rd(&fp, 8) && fp == phash
                && rd(&c_patched, 8) && rd(&c_tpz, 8)
                && rd(&nsites, 8) && nsites <= xtotal / 4;
        if (ok) {
            // Decode AND bound-check every site before writing any of them: a
            // plan is applied whole or not at all.
            cached.reserve((size_t)nsites);
            uint64_t prev = 0;
            for (uint64_t i = 0; ok && i < nsites; ++i) {
                uint64_t d = 0, w = 0;
                ok = macapk_get_uleb(p, e, d) && macapk_get_uleb(p, e, w) && w <= 0xffffffffull;
                if (!ok) break;
                prev += d * 4;
                ok = in_exec(prev);
                cached.emplace_back(prev, (uint32_t)w ^ 0xD2800000u);
            }
            ok = ok && p == e;
        }
        hit = ok;
        if (!ok) cached.clear();
    }

    if (hit && !verify) {
        for (const auto& c : cached)
            *reinterpret_cast<uint32_t*>(base_ + c.first) = c.second;
        if (c_tpz)
            std::fprintf(stderr,
                "[arm64-native] %s: TLSDESC tp-zero rewrote %llu mrs TPIDR_EL0 "
                "site(s) to mov #0\n", dbg_name_, (unsigned long long)c_tpz);
        std::fprintf(stderr, "[arm64-native] %s: canary plan CACHE HIT (%zu word(s)) -- "
                     "replayed, pass skipped\n", dbg_name_, cached.size());
        if (ct_on) {
            uint64_t h = 1469598103934665603ull;     // the live pass's digest, same form
            for (const auto& s : segments_) {
                if (!(s.prot & PROT_EXEC)) continue;
                const uint64_t* w = reinterpret_cast<const uint64_t*>(s.vaddr);
                for (size_t i = 0, nw = s.memsz / 8; i < nw; ++i) { h ^= w[i]; h *= 1099511628211ull; }
            }
            std::fprintf(stderr, "[load-timing] %s:   post-canary .text digest %016llx (%llu sites)\n",
                         dbg_name_, (unsigned long long)h, (unsigned long long)c_patched);
        }
        return (size_t)c_patched;
    }

    // ---- miss (or verify): run the pass live against a snapshot ---------------
    std::vector<uint8_t> snap((size_t)xtotal);
    {
        size_t at = 0;
        for (const auto& x : xs) { std::memcpy(snap.data() + at, base_ + x.off, x.len); at += x.len; }
    }
    size_t tpz = 0;
    const size_t patched = apply_stack_canary_neutralization_live(&tpz);
    std::vector<std::pair<uint64_t, uint32_t>> live;
    {
        size_t at = 0;
        for (const auto& x : xs) {
            const uint32_t* now = reinterpret_cast<const uint32_t*>(base_ + x.off);
            const uint32_t* was = reinterpret_cast<const uint32_t*>(snap.data() + at);
            for (size_t i = 0, nw = x.len / 4; i < nw; ++i)
                if (now[i] != was[i]) live.emplace_back(x.off + i * 4, now[i]);
            at += x.len;
        }
    }
    std::vector<uint8_t>().swap(snap);
    // The plan's delta encoding needs ascending offsets. PT_LOADs are sorted by
    // p_vaddr per the ELF spec, so this is already true; sorting makes it a
    // property of this code rather than of the input.
    std::sort(live.begin(), live.end());

    if (hit && verify) {
        std::fprintf(stderr, "[arm64-native] %s: canary plan VERIFY %s (plan %zu word(s), "
                     "live %zu, sites %llu/%zu)\n", dbg_name_,
                     (live == cached && c_patched == patched && c_tpz == tpz) ? "OK" : "MISMATCH",
                     cached.size(), live.size(), (unsigned long long)c_patched, patched);
        return patched;
    }

    std::vector<uint8_t> out;
    auto put = [&](const void* d, size_t k) {
        const uint8_t* b = static_cast<const uint8_t*>(d); out.insert(out.end(), b, b + k);
    };
    const uint32_t magic = kCanaryPlanMagic;
    const uint32_t ver = macapk_plan_disk_version(kCanaryPlanVersion);
    const uint32_t klen = (uint32_t)knobs.size();
    const uint64_t p64 = patched, t64 = tpz, n64 = live.size();
    put(&magic, 4); put(&ver, 4); put(uuid, 16); put(&klen, 4); put(knobs.data(), klen);
    put(&xtotal, 8); put(&xhash, 8); put(&phash, 8); put(&p64, 8); put(&t64, 8); put(&n64, 8);
    uint64_t prev = 0;
    for (const auto& l : live) {      // ascending by construction
        macapk_put_uleb(out, (l.first - prev) / 4);
        macapk_put_uleb(out, l.second ^ 0xD2800000u);   // `mov Xt,#imm` -> 1-3 bytes
        prev = l.first;
    }
    std::string dir = plan_path.substr(0, plan_path.rfind('/'));
    mkdir(dir.c_str(), 0755);
    std::string tmp = plan_path + ".tmp." + std::to_string((long)getpid());
    if (FILE* wf = std::fopen(tmp.c_str(), "wb")) {
        bool wok = std::fwrite(out.data(), 1, out.size(), wf) == out.size();
        wok = (std::fclose(wf) == 0) && wok;
        if (wok && std::rename(tmp.c_str(), plan_path.c_str()) == 0)
            std::fprintf(stderr, "[arm64-native] %s: wrote canary plan (%zu word(s), %zu bytes)\n",
                         dbg_name_, live.size(), out.size());
        else
            std::remove(tmp.c_str());   // never leave a half-written plan
    }
    return patched;
}

size_t Image::apply_stack_canary_neutralization_live(size_t* tp_zeroed_out) {
    // MACAPK_LOAD_TIMING=1 sub-phase timing for this pass (see load_common):
    // on libminecraftpe it is ~6 s of every launch, so which PART matters.
    static const bool ct_on = std::getenv("MACAPK_LOAD_TIMING") != nullptr;
    uint64_t ct_mark = ct_on ? clock_gettime_nsec_np(CLOCK_UPTIME_RAW) : 0;
    uint64_t ct_prescan_ns = 0, ct_backedges = 0, ct_body_words = 0, ct_words = 0;
    auto ct_lap = [&](const char* what) {
        if (!ct_on) return;
        const uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        std::fprintf(stderr, "[load-timing] %s:   %-42s %9.1f ms\n", dbg_name_, what,
                     (double)(now - ct_mark) / 1e6);
        ct_mark = now;
    };
    // AArch64 instruction encodings we care about:
    //
    //   mrs Xt, TPIDR_EL0     = 0xD53BD040 | Rt
    //   ldr Xt, [Xn, #0x28]   = 0xF9401400 | (Rn<<5) | Rt   (imm12=5)
    //   mov Xt, #0  (movz)    = 0xD2800000 | Rt
    //
    // The canary cookie is loaded twice per protected function -- once
    // in the prologue and again at the epilogue check. The compiler
    // typically reserves one callee-saved register (x19..x28) for the
    // thread pointer across the whole function body, so a single
    // `mrs Xn, TPIDR_EL0` near the top is followed thousands of
    // instructions later by `ldr Xt, [Xn, #0x28]` at the epilogue --
    // far apart but using the same Xn.
    //
    // We track which registers currently hold TPIDR_EL0 with a small
    // dataflow scan. Set the flag on `mrs Xn, TPIDR_EL0`; clear it
    // when the register is written by any other instruction; and on
    // `bl`/`blr` clear only the caller-saved registers (x0..x18, x30),
    // because callee-saved registers (x19..x28, x29) are preserved
    // across function calls by the AAPCS64 calling convention -- and
    // the canary register is always callee-saved.
    //
    // Determining whether an instruction "writes" Rd at bits 0..4 is
    // an approximation: we treat *any* instruction except recognised
    // branches and stores as writing Rd. This is conservative on the
    // false-negative side: in the rare case where an instruction we
    // don't recognise reads Rt without writing it, we may clear the
    // flag prematurely and miss the corresponding canary patch (which
    // would then SIGSEGV at runtime, surfacing the bug clearly so we
    // can add the missing recognition).
    //
    // STP with two destinations (the LDP loads-pair) clears both
    // registers via the Rt and Rt2 slots; we handle that case
    // explicitly. STR / STRB / STRH / STP variants don't write Rt
    // (they read it as a source) and are recognised by their opcode
    // prefixes so we don't accidentally clear a still-live TPIDR
    // register.
    bool is_tpidr[32] = {};
    // The one stack slot (sp-relative byte offset, -1 = none) currently holding
    // a spilled TPIDR_EL0 value. Compilers spill the thread pointer to the stack
    // in the prologue and RELOAD it for the epilogue's second canary read; the
    // reload looks like a plain GP-register write, so without tracking the spill
    // the epilogue guard load `ldr Xt,[Xt,#0x28]` goes unpatched and faults
    // (TPIDR_EL0 is not the Bionic TLS base on macOS; reloaded TPIDR can be junk
    // on a non-Mac.apk-spawned thread -- e.g. UnityMain is a JVM thread). One
    // slot covers the common case (the compiler spills the guard register once).
    int tpidr_slot_off = -1;
    size_t patched = 0;

    // v1.0.838: addresses the FIRST pass (dataflow + backstop, below) rewrote to
    // `mov Xd,#0`. The SECOND pass (the pair/multi-step matcher further down)
    // uses "another `ldr Xu,[Xm,#0x28]` intervenes" as a STOP MARKER meaning
    // "some OTHER canary read, not this load, is the one feeding the compare
    // ahead". Pass 1 having already neutralised that intervening read ERASES
    // the marker -- the bytes are now `mov Xd,#0`, which pass 2 walks straight
    // over -- so pass 2 then attributes the compare to the WRONG load and
    // zeroes it.
    //
    // MEASURED (2026-07-30, libcandycrushsaga.so, the only false positive in a
    // 139-library fleet A/B):
    //     0x4cd136c  ldr  x8, [sp, #0x28]      <- an ordinary stack local
    //     0x4cd1370  ldr  x9, [sp, #0x38]      <- another stack local
    //     0x4cd1374  sub  w23, w8, w9          <- CONSUMED, not compared
    //     0x4cd1378  ldr  x19, [sp, #8]        <- spilled thread pointer
    //     0x4cd137c  ldr  x8, [x19, #0x28]     <- the REAL canary read
    //     0x4cd1380  ldur x9, [x29, #-0x10]    <- the saved canary
    //     0x4cd1384  cmp  x8, x9
    //     0x4cd1388  b.ne -> bl __stack_chk_fail
    //     ...
    //     0x4cd138c  mov  w0, w23              <- w23 is the RETURN VALUE
    // With the widened reload predicate, pass 1's backstop patches 0x4cd137c,
    // so pass 2 no longer breaks there, walks on to the 0x4cd1384 compare, and
    // zeroes 0x4cd136c -- making the function return `0 - [sp+0x38]`. Nothing
    // about the widened predicate is wrong here; the bug is that one pass
    // destroys evidence the other depends on.
    //
    // Recording the addresses restores the marker exactly, with none of the
    // collateral of breaking on any `mov Xd,#0` (a very common instruction --
    // that would make pass 2 miss genuine canaries whose window contains one).
    //
    // What the restoration is: pass 2's stop markers are BYTE PATTERNS in the
    // guest .text (`ldr Xu,[Xm,#0x28]`, `mrs _,TPIDR_EL0`). Pass 1 runs to
    // completion over every executable segment BEFORE pass 2 starts, so every
    // rewrite pass 1 made is already in place when pass 2 reads those bytes.
    // Breaking on a recorded address is therefore exactly equivalent to pass 2
    // having read the PRE-pass-1 bytes -- i.e. the long-tested semantics that
    // predate the erasure, not a new heuristic. (The reverse direction cannot
    // happen: pass 2 only rewrites at `p` and only scans FORWARD from p+1, and
    // p only increases, so no pass-2 rewrite can erase a pass-2 marker.)
    //
    // A rewrite is recorded whether it came from the canary matcher or from
    // the TLSDESC tp-zeroing (`mrs Xt,TPIDR_EL0` -> `mov Xt,#0`), because BOTH
    // destroy a pass-2 stop marker and both leave the same `mov Xd,#0` bytes
    // behind. The tp-zeroing arm is reachable only under MACAPK_TLS_DESC=1, so
    // it is inert on the default path, but leaving it out would make this fix
    // complete only by accident of another flag's default.
    //
    // Gated on kMarkerFix below, DEFAULT ON since 2026-09-30 (it used to follow
    // kWideGuardReload so the shipped default stayed byte-identical until swept).
    // A SECOND measured default-path corruption, and this one blanked an app:
    //     libreactnative.so (React Native, Efteling 5.24.0),
    //     facebook::react::JReactInstance::initHybrid epilogue:
    //     0x4e0588  ldr  x8, [sp, #0x28]     <- the HybridData local_ref RESULT
    //     0x4e058c  ldr  x9, [sp, #0x10]     <- the saved x8 (sret) pointer
    //     0x4e0590  str  x8, [x9]            <- return the ref through sret
    //     0x4e0594  ldr  x8, [sp, #0x18]     <- spilled thread pointer
    //     0x4e0598  ldr  x8, [x8, #0x28]     <- the REAL guard read (pass 1)
    //     0x4e059c  ldur x9, [x29, #-0x8]
    //     0x4e05a0  cmp  x8, x9 ; b.ne -> bl __stack_chk_fail
    // Pass 2's pair matcher took 0x4e0588+0x4e058c as (current, saved) and the
    // 0x4e05a0 compare as theirs, so it rewrote the RESULT load to `mov x8,#0`:
    // initHybrid returned a NULL HybridData with no pending exception, Java
    // stored null into ReactInstance.mHybridData, and the very next native
    // (getJavaScriptContext -> fbjni cthis()) threw "java.lang.NullPointerException"
    // -- React Native never started, the app sat on its splash. Found with jdb
    // (mHybridData == null right after the putfield) and an lldb stop at
    // 0x4e0590 (x8 == 0 while [sp+0x28] held the valid handle). See
    // docs/react-native-hybriddata-null-canary-false-patch_2026-09-30.md.
    std::unordered_set<uintptr_t> pass1_neutralized;

    // FUNCTION-SCOPED "ever held TPIDR" set, for the epilogue-check backstop
    // below. The linear is_tpidr[] dataflow above is CONTROL-FLOW INSENSITIVE:
    // in a large stack-protected function the compiler keeps the thread pointer
    // in a callee-saved register (typically x29 when frame-pointer-elision is
    // on) across the whole body, but ALSO reuses that same register as a scratch
    // in some basic blocks (`add x29,x20,#imm`) or restores it on an EARLY-RETURN
    // epilogue (`ldp x29,x30,[sp],#N ; ret`) that the linear scan walks through
    // even though the main-body path never executes it. Either event clears
    // is_tpidr[x29], and since the main body has no fresh `mrs`, its epilogue
    // guard load `ldr Xt,[x29,#0x28]` goes UNPATCHED and faults at runtime
    // (mrs TPIDR_EL0 on macOS returns a small magic ~0x1004-0x1007, so
    // [that+0x28] is unmapped). Real case: cohtml::css::InitializeCSSPropertyParsers
    // (Minecraft Bedrock Ore-UI) -- a ~135 KB function whose first-call re-entry
    // guard has an early return; the linear scan lost x29 at the early-return
    // `ldp x29,x30` and missed the main-body canary check. `func_has_tpidr_mrs`
    // records that SOME register was set by `mrs _,TPIDR_EL0` anywhere in THIS
    // function (reset at each frame-push prologue) -- i.e. the function is
    // stack-protected. The backstop patches an otherwise-missed `ldr Xt,[Xn,#0x28]`
    // when the function is stack-protected AND the load feeds the stack-canary
    // CHECK idiom (`ldr Xu,[sp,#M] ; cmp Xt,Xu ; b.cond`), which is specific enough
    // that a legitimate object-field load at offset 0x28 is not matched (that
    // field is not compared against a stack-spilled value + branch). Gating on
    // the whole-function flag (not a per-register set) is deliberate: the guard
    // register is often a COPY of the mrs register (`mrs x25,TPIDR ; mov x29,x25 ;
    // ... ; ldr x8,[x29,#0x28]`, as in cohtml::StyleSolver::ResolveBackground and
    // cohtml::ViewBinder::ExecuteJSCallbacks), so keying on the exact mrs register
    // misses the epilogue load done through the copy.
    bool func_has_tpidr_mrs = false;

    // Recognise the stack-canary epilogue CHECK reachable from a `ldr Xt,[Xn,#0x28]`
    // at `p`: within a short window, a reload of the spilled guard
    // `ldr Xu,[sp,#M]` (Xu != Xt) followed by `cmp Xt,Xu` (64-bit SUBS, Rd=XZR)
    // and a conditional branch. Returns true only for that exact shape.
    //
    // BRANCH-FOLLOWING (2026-07-06, the MCPE renoir black-screen fix): the compiler
    // sometimes emits a SHARED canary epilogue reached from SEVERAL predecessor
    // blocks by an unconditional `b`, so a guard load looks like
    // `ldr Xt,[Xn,#0x28] ; b <shared_compare>` with the reload+cmp+b.ne living at the
    // branch TARGET, not inline. Real case: renoir::TypefaceImpl::GetOrCreateGlyphImages
    // (Minecraft Bedrock, file vaddr 0x7c1f2bc) has THREE guard loads feeding one
    // compare at 0x7c1f850; two of them (0x7c1f2bc, 0x7c1f898) branch to it. The old
    // straight-line window returned false at the `b`, leaving those loads unpatched
    // -> `ldr [TPIDR_magic+0x28]` faults (macOS `mrs TPIDR_EL0` returns a small magic
    // ~0x1004-0x1007, and in our sdk<13 preserve_x18 build it is 0x0001000000001005;
    // [that+0x28] is unmapped -> SEGV_ACCERR). So when the scan hits a LONE
    // unconditional `b`, follow it ONCE to its target and keep matching there.
    // `follow_budget` bounds the hop to a single branch so a `b`->`b` chain or a
    // self-loop cannot spin. Everything else (idiom shape, the stack-protected gate at
    // the call site) is unchanged, so a legitimate object-field load at +0x28 -- which
    // is never followed by a spill-reload + self-compare + branch -- still cannot match.
    // A FOURTH backstop-miss mode, MEASURED 2026-07-29 on Plague Inc
    // (com.miniclip.plagueinc 1.24.4) -- see
    // docs/candycrush-plagueinc_2026-07-29.md §8. The spilled-guard RELOAD was
    // recognised only as `ldr Xu,[sp,#imm]` (unsigned-offset, Rn==31). clang
    // also emits it UNSCALED and off the FRAME POINTER, and libnms.so does
    // exactly that, so the backstop declined and the epilogue guard load went
    // unpatched -> SIGSEGV. The site (file vaddr, verified with capstone):
    //
    //   0x1f2c8  stp  x29, x30, [sp, #-0x60]!     <- function start
    //   0x1f2e8  mrs  x24, tpidr_el0              <- stack-protected
    //   0x1f2f0  ldr  x8,  [x24, #0x28]           <- prologue guard (WAS patched)
    //   0x1f3a4  mov  x24, x26                    <- clears is_tpidr[x24] on
    //   0x1f3d0  mov  x24, x19                       a DIFFERENT cfg path
    //   0x1f750  ldr  x8,  [x24, #0x28]           <- epilogue guard (MISSED -> SEGV)
    //   0x1f758  ldur x9,  [x29, #-8]             <- the reload we could not see
    //   0x1f760  cmp  x8,  x9
    //   0x1f764  b.ne 0x1f788
    //   0x1f788  bl   0x3e9b0                     <- __stack_chk_fail
    //
    // Crash signature it produces (hs_err_pid41744): SIGSEGV si_code=2
    // SEGV_ACCERR, si_addr=0x000100000000102c, x24=0x0001000000001004,
    // faulting insn f9401708 = `ldr x8,[x24,#0x28]` -- i.e. exactly the
    // "mrs TPIDR_EL0 returns commpage magic ~0x1004-0x1007" shape this whole
    // pass exists to prevent.
    //
    // GATED DEFAULT-OFF. Broadening the reload predicate makes the backstop
    // patch MORE sites, and this pass runs for EVERY Stage-6 arm64 guest
    // (MCPE, Unity, ...), so it must not go default-ON without the protected
    // sweep. Enable with MACAPK_CANARY_WIDE_RELOAD=1; flip the default only
    // after a clean sweep. (Referenced from a non-capturing lambda below,
    // which is legal because it has static storage duration.)
    static const bool kWideGuardReload = [] {
        const char* e = std::getenv("MACAPK_CANARY_WIDE_RELOAD");
        return e && *e == '1';
    }();

    // MACAPK_CANARY_MARKER_FIX: the pass1_neutralized marker restoration (see
    // that set's declaration) as its OWN gate. It has to be separable from
    // kWideGuardReload because the two answer different questions: the wide
    // reload predicate ADDS patch sites (a behaviour change that needs its own
    // sweep), while the marker restoration only REMOVES spurious ones and is
    // reachable on the DEFAULT path -- pass 1 patches thousands of sites per
    // image with the flag off, so the erasure corrupts default-path guests
    // today. Sharing one flag meant the corruption could only be fixed by
    // shipping the unrelated widening at the same time.
    //
    // DEFAULT ON (2026-09-30; docs/canary-marker_2026-07-30.md section 7 had
    // recommended exactly this flip, and the React Native initHybrid corruption
    // above is the case that made it user-visible). Default-ON convention (as
    // MACAPK_X18_EXEC_ONLY / MACAPK_SVC_BRK_EXEC_ONLY / MACAPK_CANARY_OUTLINED_MRS):
    // only a leading '0' turns it off, so an empty / "true" / "on" value keeps the
    // fix instead of silently disabling it. =0 = the previous shipped default,
    // byte-identical: the old unset value was kWideGuardReload, which is false
    // unless MACAPK_CANARY_WIDE_RELOAD=1, and an explicit =0 already returned
    // false before this change.
    static const bool kMarkerFix = [] {
        const char* e = std::getenv("MACAPK_CANARY_MARKER_FIX");
        return !(e && e[0] == '0');
    }();

    // MACAPK_CANARY_TRACE=1: name every site this pass rewrites, as a FILE
    // vaddr (runtime addr - base_), so two runs are directly diffable. The
    // aggregate "neutralised N site(s)" line proves a count changed but not
    // WHICH sites, and the count is the only thing a protected sweep can
    // observe -- which is not enough to argue a widened predicate is safe.
    // With this on, `MACAPK_CANARY_WIDE_RELOAD=0 vs =1` yields two address
    // lists whose set-difference is exactly the newly-patched sites, each of
    // which can then be disassembled and checked against the canary idiom by
    // hand. Diagnostic only, default OFF, no effect on what gets patched.
    static const bool kCanaryTrace = [] {
        const char* e = std::getenv("MACAPK_CANARY_TRACE");
        return e && *e == '1';
    }();

    // MACAPK_CANARY_MARK: DIAGNOSTIC, default 0. Neutralise a stack-guard LOAD
    // to `mov Xt,#<mark>` instead of `mov Xt,#0`. Both the prologue and epilogue
    // guard loads get the SAME mark, so the canary compare still passes -- but
    // if a guest reads the guard and USES it as DATA (rather than only comparing
    // it), the VM sees the mark. Used to test whether pairip's +0x440c4 garbage
    // length is fed by the neutralised guard value: if `N` moves when the mark
    // changes, the guard flows into VM data (an in-scope fidelity gap: give the
    // guest a real TLS guard); if `N` is unchanged, the guard is only compared
    // and the neutraliser is not the cause. NOT applied to the mrs->0 tp-zeroing
    // or the TLSDESC path, only to the three `ldr [Xn,#0x28]` guard-load sites.
    static const uint32_t kCanaryMark = [] {
        const char* e = std::getenv("MACAPK_CANARY_MARK");
        return e ? (uint32_t)(strtoul(e, nullptr, 0) & 0xffffu) : 0u;
    }();
    const uint32_t guard_mov_imm = (kCanaryMark << 5);   // OR into 0xD2800000|rt

    // MACAPK_NO_CANARY_LOOP_CLEAR: DEFAULT-ON control-flow fixup (set to disable
    // for an A/B). The dataflow scan below is control-flow-INSENSITIVE; it cannot
    // see that a loop back-edge re-enters a block with a register holding a value
    // DIFFERENT from what the fall-through path left. When a function loads
    // TPIDR_EL0 into a callee-saved register in its prologue (a genuine stack-
    // guard base) and then REUSES that same register as a loop element/induction
    // pointer, the prologue `mrs` sets is_tpidr[Xr], the register is rewritten
    // INSIDE the loop, and any `ldr Xt,[Xr,#0x28]` in the loop body that sits at a
    // LOWER address than the rewrite gets falsely neutralised to `mov Xt,#0`. The
    // loop-redef pre-scan (see below) clears is_tpidr[] at each loop head for the
    // registers the body rewrites, killing exactly that false positive while
    // leaving genuine "guard held across a loop, checked at the epilogue" reads
    // patched by the primary arm. Root case: Instagram libstartup.so
    // pando::...setTreeUpdaterList (mrs x22 @+0x335f24; x22 reused as a list
    // element; `ldr x9,[x22,#0x28]` @+0x336088 is a Pando relative-vtable field,
    // not a canary) -- neutralised to `mov x9,#0`, so `ldrsw x8,[x9,#0x18]`
    // faulted at 0x18 and crashed the feed.
    const bool loop_clear = !std::getenv("MACAPK_NO_CANARY_LOOP_CLEAR");

    // MACAPK_CANARY_OUTLINED_MRS: DEFAULT-ON. Propagate is_tpidr[] across a
    // `bl` into an LLVM-machine-outliner "outlined prologue" helper.
    //
    // The whole dataflow pass above assumes a function's `mrs Xn,TPIDR_EL0`
    // precedes -- at a LOWER address -- the `ldr Xt,[Xn,#0x28]` guard reads it
    // feeds, because the linear scan walks .text in increasing address order.
    // The LLVM machine outliner INVERTS that: it hoists the common canary-setup
    // prologue (`mrs Xn,TPIDR_EL0 ; ldr X,[Xn,#0x28] ; str X,[sp,#M] ; ...`)
    // into a shared helper placed elsewhere in .text -- typically at a HIGHER
    // address than the body that consumes Xn -- and the body reaches it with a
    // plain `bl`. Xn is a callee-saved register (x19..x28), so it survives the
    // call and holds TPIDR_EL0 throughout the body; the epilogue guard read
    // `ldr Xt,[Xn,#0x28]` then executes with is_tpidr[Xn] still false and goes
    // UNPATCHED -> SIGSEGV (macOS TPIDR_EL0 is commpage magic ~0x1004, not the
    // Bionic TLS base). MEASURED 2026-08-19 on TikTok's
    // libpns_ttmachine_adapter.so: function 0xb330 does `bl 0x3325c`, the helper
    // at 0x3325c begins `mrs x23,TPIDR_EL0 ; ldr x8,[x23,#0x28] ; ... ; ret`,
    // and the body's guard read at 0xb4c0 (`ldr x8,[x23,#0x28]`) faulted in the
    // guest's .init_array during dlopen. The existing backstop cannot rescue it
    // either: 0xb330 has no `mrs` of its own (so func_has_tpidr_mrs is false)
    // AND the compare is ALSO outlined (`bl 0x33250`), which
    // epilogue_guard_check treats as end-of-window.
    //
    // WHY THIS CANNOT FALSE-POSITIVE (the candy-crush class of bug lives in
    // pass 2 / the wide-reload backstop, neither of which this touches): the
    // fix only makes the PRIMARY dataflow arm -- `if (is_tpidr[rn]) patch;` --
    // see one more register as holding TPIDR. That arm's safety is exact: it
    // patches `ldr Xt,[Xn,#0x28]` only where Xn PROVABLY still holds TPIDR_EL0,
    // and TPIDR_EL0+0x28 is ALWAYS the canary slot, never a live object field
    // (no real object is allocated at the raw thread-pointer value). We set
    // is_tpidr[Xn]=true after a `bl` ONLY when the callee's straight-line path
    // to its `ret` contains `mrs Xn,TPIDR_EL0` (Xn callee-saved) and NEVER
    // rewrites Xn before returning -- so on return Xn provably == TPIDR_EL0. A
    // normal AAPCS64 function that used x23 for its OWN canary would SAVE and
    // RESTORE x23 (the restore writes x23), which clears the candidate, so it
    // is never propagated; only an outlined fragment leaves a callee-saved
    // register clobbered-with-TPIDR on return, and that is exactly the shape we
    // want. The linear scan then clears is_tpidr[Xn] the instant the caller
    // reassigns Xn, so any surviving `ldr Xt,[Xn,#0x28]` is a genuine canary
    // read. One-level, non-transitive, straight-line-to-ret only.
    //
    // DEFAULT-ON so it fixes the blocker out of the box; set
    // MACAPK_CANARY_OUTLINED_MRS=0 to revert to the pre-fix behaviour and A/B.
    static const bool kOutlinedMrs = [] {
        const char* e = std::getenv("MACAPK_CANARY_OUTLINED_MRS");
        return !e || *e != '0';   // default ON
    }();

    auto epilogue_guard_check = [](uint32_t* p, uint32_t* lo, uint32_t* end,
                                   uint32_t xt) -> bool {
        uint32_t* q = p + 1;              // first instruction after the guard ldr
        int follow_budget = 1;            // hops across at most one unconditional `b`
        for (;;) {
            int sp_loaded = -1;           // Xu from the spilled-guard reload, or -1
            bool hopped = false;
            for (int k = 0; k < 8 && (q + k) < end; ++k) {
                uint32_t j = *(q + k);
                // Reload of the spilled guard cookie. Forms:
                //   ldr  Xu,[sp,#imm]    unsigned-offset, 64-bit   (always)
                //   ldr  Xu,[x29,#imm]   same, off the frame ptr   (wide gate)
                //   ldur Xu,[Xn,#simm9]  UNSCALED, Xn in {sp,x29}  (wide gate)
                // The LDUR mask isolates size=11,V=0,[25:24]=00,opc=01,[21]=0,
                // [11:10]=00, which is LDUR-64 exactly: LDR(register) has
                // [21]=1/[11:10]=10 and LDR(pre/post-index) has [11:10]=11/01,
                // so neither can match.
                bool is_guard_reload = false;
                if ((j & 0xFFC00000u) == 0xF9400000u) {
                    uint32_t rn = (j >> 5) & 0x1Fu;
                    is_guard_reload = (rn == 31u)
                                   || (kWideGuardReload && rn == 29u);
                } else if (kWideGuardReload
                           && (j & 0xFFE00C00u) == 0xF8400000u) {
                    uint32_t rn = (j >> 5) & 0x1Fu;
                    is_guard_reload = (rn == 31u || rn == 29u);
                }
                if (is_guard_reload) {
                    uint32_t u = j & 0x1Fu;
                    if (u == xt) return false;    // reloads Xt -> guard value gone
                    sp_loaded = (int)u;
                    continue;
                }
                // cmp Xt,Xu : SUBS (shifted register, 64-bit), Rd=XZR
                if ((j & 0xFFE0FC1Fu) == 0xEB00001Fu) {
                    uint32_t rn = (j >> 5) & 0x1Fu, rm = (j >> 16) & 0x1Fu;
                    if (sp_loaded >= 0
                        && (rn == xt || rm == xt)
                        && (rn == (uint32_t)sp_loaded || rm == (uint32_t)sp_loaded)) {
                        for (int m = 1; m <= 2 && (q + k + m) < end; ++m)
                            if ((*(q + k + m) & 0xFF000010u) == 0x54000000u) return true;  // b.cond
                        return false;
                    }
                    continue;   // a DIFFERENT compare (e.g. the return-value cmp that
                                // clang schedules between the guard reload and check,
                                // as in cohtml::ViewBinder::ExecuteJSCallbacks) --
                                // keep scanning for the real canary compare.
                }
                // Lone unconditional `b`: the shared canary compare may live at the
                // target. Follow it once; then restart the scan there.
                if ((j & 0xFC000000u) == 0x14000000u) {
                    if (follow_budget <= 0) return false;
                    int32_t imm26 = (int32_t)(j & 0x03FFFFFFu);
                    if (imm26 & 0x02000000) imm26 -= 0x04000000;   // sign-extend bit 25
                    uint32_t* tgt = (q + k) + imm26;               // imm26 in insn units
                    if (tgt <= lo || tgt >= end) return false;
                    q = tgt;
                    --follow_budget;
                    hopped = true;
                    break;
                }
                // Any other branch/call ends the straight-line canary-check window.
                if ((j & 0xFC000000u) == 0x94000000u     // bl
                    || (j & 0xFF000010u) == 0x54000000u  // b.cond
                    || (j & 0xFFFFFC1Fu) == 0xD61F0000u  // br
                    || (j & 0xFFFFFC1Fu) == 0xD63F0000u  // blr
                    || j == 0xD65F03C0u)                 // ret
                    return false;
                // If a non-store, non-FP/SIMD instruction writes Xt, the guard value
                // is clobbered before its compare -> not a guard load. (Stores read
                // Rt as a source; FP/SIMD target a V register, not the GP Xt.)
                bool is_store =
                       (j & 0xFFC00000u) == 0xF9000000u || (j & 0xFFE00000u) == 0xF8000000u
                    || (j & 0xFFC00000u) == 0xB9000000u || (j & 0xFFE00000u) == 0xB8000000u
                    || (j & 0xFFC00000u) == 0x39000000u || (j & 0xFFC00000u) == 0x79000000u
                    || (j & 0xFFE00000u) == 0x38000000u || (j & 0xFFE00000u) == 0x78000000u
                    || (j & 0xFFC00000u) == 0xA9000000u || (j & 0xFFC00000u) == 0xA9800000u
                    || (j & 0xFFC00000u) == 0xA8800000u;
                bool is_fp = ((j >> 25) & 0x7u) == 0x7u;
                if (!is_store && !is_fp && (j & 0x1Fu) == xt) return false;
            }
            if (!hopped) return false;    // window exhausted, no compare, no hop
        }
    };

    // Gated TLSDESC tp-zeroing: in a pure-TLSDESC image, rewrite every
    // `mrs Xt, TPIDR_EL0` to `mov Xt, #0` so the guest's `tp + x0` yields our
    // resolver's ABSOLUTE per-thread address (the resolver returns the absolute
    // addr; tp must therefore be 0). The stack-guard `ldr [Xt,#0x28]` is still
    // neutralized below because is_tpidr[Xt] stays set. Off by default.
    const bool zero_tp = MACAPK_TLS_DESC_ENABLED
        && image_has_tlsdesc_ && !image_has_tprel_;
    size_t tp_zeroed = 0;

    auto clear_caller_saved = [&]() {
        // x0..x18 plus x30 (LR) are caller-saved under AAPCS64.
        for (int i = 0; i <= 18; ++i) is_tpidr[i] = false;
        is_tpidr[30] = false;
    };
    auto clear_all = [&]() {
        for (int i = 0; i < 32; ++i) is_tpidr[i] = false;
        tpidr_slot_off = -1;
    };

    // Opcode-prefix helpers. The masks pick out enough bits to
    // unambiguously identify the instruction class without depending
    // on register selectors.
    auto is_store_64 = [](uint32_t i) {
        // STR (unsigned-offset, 64-bit): 11111001 00..
        // STR (pre-index, 64-bit):       11111000 000 imm9 1 1 Rn Rt
        // STR (post-index, 64-bit):      11111000 000 imm9 0 1 Rn Rt
        // STR (unscaled, STUR, 64-bit):  11111000 000 imm9 0 0 Rn Rt
        return ((i & 0xFFC00000u) == 0xF9000000u)
            || ((i & 0xFFE00000u) == 0xF8000000u);
    };
    auto is_store_32 = [](uint32_t i) {
        return ((i & 0xFFC00000u) == 0xB9000000u)
            || ((i & 0xFFE00000u) == 0xB8000000u);
    };
    auto is_store_byte_or_half = [](uint32_t i) {
        // STRB (39000000-class) and STRH (79000000-class) plus their
        // unscaled / pre/post variants.
        return ((i & 0xFFC00000u) == 0x39000000u)
            || ((i & 0xFFC00000u) == 0x79000000u)
            || ((i & 0xFFE00000u) == 0x38000000u)
            || ((i & 0xFFE00000u) == 0x78000000u);
    };
    auto is_stp_64 = [](uint32_t i) {
        // STP signed offset 64-bit: 1010100100 .. Rt2 Rn Rt -> 0xA9000000
        // STP pre-index:            1010100110 ..                0xA9800000
        // STP post-index:           1010100010 ..                0xA8800000
        // (We DON'T return true for LDP; that one writes both regs.)
        return ((i & 0xFFC00000u) == 0xA9000000u)
            || ((i & 0xFFC00000u) == 0xA9800000u)
            || ((i & 0xFFC00000u) == 0xA8800000u);
    };
    auto is_ldp_64 = [](uint32_t i) {
        // LDP signed offset 64-bit: 1010100101 ..                0xA9400000
        // LDP pre-index:            1010100111 ..                0xA9C00000
        // LDP post-index:           1010100011 ..                0xA8C00000
        return ((i & 0xFFC00000u) == 0xA9400000u)
            || ((i & 0xFFC00000u) == 0xA9C00000u)
            || ((i & 0xFFC00000u) == 0xA8C00000u);
    };
    auto is_no_dest = [](uint32_t i) {
        // Unconditional branch B (top 6 bits 000101)
        if ((i & 0xFC000000u) == 0x14000000u) return true;
        // Conditional branch B.cond (top 8 bits 01010100)
        if ((i & 0xFF000010u) == 0x54000000u) return true;
        // CBZ/CBNZ (top 7 bits 0b0110100 or 0b1110100 -> mask 0x7F)
        if ((i & 0x7E000000u) == 0x34000000u) return true;
        // TBZ/TBNZ (top 7 bits 0b0110110 or 0b1110110)
        if ((i & 0x7E000000u) == 0x36000000u) return true;
        // CMP/CMN/TST: aliases of SUBS/ADDS/ANDS with Rd=XZR.
        // SUBS xzr/wzr: bits 31..24 = 01101011 (32) or 11101011 (64)
        // and Rd field is 31. We'll let the Rd clear correctly set
        // is_tpidr[31] which is harmless (X31 is XZR, never a real reg).
        return false;
    };

    // Registers an instruction WRITES, under the SAME model the scan below uses
    // to clear is_tpidr[] (Rd at bits 0..4 by default; both regs for LDP; nothing
    // for stores / branches / FP-SIMD / ret / br; Rt for mrs; caller-saved for a
    // call). Used only by the loop-redef pre-scan. Over-approximation is safe (an
    // EXTRA clear at a loop head at worst misses a canary patch, which the runtime
    // TPIDR-magic fault recovery re-supplies); under-approximation is not (it
    // would let a false canary patch survive). Mirroring the scan keeps the two
    // consistent.
    auto regs_written = [&](uint32_t i) -> uint32_t {
        if (is_store_64(i) || is_store_32(i) || is_store_byte_or_half(i)
            || is_stp_64(i)) return 0u;                     // no GP destination
        if (is_no_dest(i)) return 0u;                       // b/b.cond/cbz/cbnz/tbz
        if (i == 0xD65F03C0u) return 0u;                    // ret
        if ((i & 0xFFFFFC1Fu) == 0xD61F0000u) return 0u;    // br Xn
        if ((i & 0xFFF00000u) == 0xD5300000u)               // mrs Xt,<sysreg>
            return 1u << (i & 0x1Fu);
        if ((i & 0xFC000000u) == 0x94000000u                // bl <label>
            || (i & 0xFFFFFC1Fu) == 0xD63F0000u)            // blr Xn
            return 0x0007FFFFu | (1u << 30);                // x0..x18 + x30
        // FP / Advanced-SIMD: Rd selects a V register, not a GP one.
        if (((i >> 25) & 0x7u) == 0x7u
            || (((i >> 27) & 1u) == 1u && ((i >> 26) & 1u) == 1u
                && ((i >> 25) & 1u) == 0u)) return 0u;
        if (is_ldp_64(i))                                    // LDP writes Rt and Rt2
            return (1u << (i & 0x1Fu)) | (1u << ((i >> 10) & 0x1Fu));
        return 1u << (i & 0x1Fu);                            // default: Rd at 0..4
    };

    // Outlined-prologue propagation (see kOutlinedMrs above). On a `bl` at
    // `bl_ip`, peek at the callee; if its straight-line path to `ret` sets a
    // callee-saved register from TPIDR_EL0 and never rewrites it, that register
    // holds TPIDR_EL0 when control returns -- mark is_tpidr[] for it so the
    // primary patch arm neutralises the epilogue guard read in the caller.
    // Straight-line-to-ret only, one level deep, no branch-following: any
    // control flow other than bl/blr (which preserve callee-saved x19..x28)
    // aborts without propagating, keeping the "still holds TPIDR on return"
    // guarantee airtight.
    auto propagate_outlined_tpidr = [&](uint32_t* bl_ip,
                                        uint32_t* seg_lo, uint32_t* seg_end) {
        if (!kOutlinedMrs) return;
        // Decode the bl target (imm26, in instruction units, sign-extended).
        int64_t off = int64_t(int32_t(uint32_t(*bl_ip) << 6) >> 6) * 4;
        uint32_t* tgt = reinterpret_cast<uint32_t*>(
            reinterpret_cast<uintptr_t>(bl_ip) + off);
        if (tgt < seg_lo || tgt >= seg_end) return;   // not in this .text segment
        bool cand[32] = {};       // callee-saved reg currently == TPIDR in callee
        bool found = false;       // saw at least one mrs TPIDR into x19..x28
        constexpr int kMaxScan = 96;
        for (int i = 0; i < kMaxScan && (tgt + i) < seg_end; ++i) {
            uint32_t j = *(tgt + i);
            if (j == 0xD65F03C0u) {               // ret -- clean return path
                if (found)
                    for (int r = 19; r <= 28; ++r)
                        if (cand[r]) is_tpidr[r] = true;
                return;
            }
            // mrs Xn, TPIDR_EL0 -- Xn holds the thread pointer.
            if ((j & 0xFFFFFFE0u) == 0xD53BD040u) {
                uint32_t rn = j & 0x1Fu;
                if (rn >= 19 && rn <= 28) { cand[rn] = true; found = true; }
                continue;
            }
            // bl / blr: AAPCS64 preserves x19..x28, so a candidate survives.
            if ((j & 0xFC000000u) == 0x94000000u) continue;       // bl
            if ((j & 0xFFFFFC1Fu) == 0xD63F0000u) continue;       // blr
            // Any other branch (b / b.cond / cbz / tbz / br) makes the path
            // non-straight-line: abort without propagating (conservative).
            if ((j & 0xFFFFFC1Fu) == 0xD61F0000u) return;         // br
            if (is_no_dest(j)) return;                            // b/b.cond/cbz/tbz
            // Stores never write a GP destination -- skip (Rt is a source).
            if (is_store_64(j) || is_store_32(j)
                || is_store_byte_or_half(j) || is_stp_64(j))
                continue;
            // LDP writes both Rt and Rt2.
            if (is_ldp_64(j)) {
                uint32_t rt  = j & 0x1Fu;
                uint32_t rt2 = (j >> 10) & 0x1Fu;
                if (rt  >= 19 && rt  <= 28) cand[rt]  = false;
                if (rt2 >= 19 && rt2 <= 28) cand[rt2] = false;
                continue;
            }
            // FP/SIMD: the low field selects a V register, not a GP dest.
            if (((j >> 25) & 0x7u) == 0x7u
                || (((j >> 27) & 1u) == 1u && ((j >> 26) & 1u) == 1u
                    && ((j >> 25) & 1u) == 0u))
                continue;
            // Default: assume the instruction writes Rd at bits 0..4; a write to
            // a candidate means it no longer holds TPIDR on return.
            uint32_t rd = j & 0x1Fu;
            if (rd >= 19 && rd <= 28) cand[rd] = false;
        }
        // Scan cap or segment end reached without a clean ret: do not propagate.
    };

    // Resolve `__stack_chk_fail`'s PLT stub ONCE, up front, from the PLT
    // relocations. Both pass 1's outlined-compare backstop (below) and pass 2
    // anchor on it: a conditional branch that reaches `bl __stack_chk_fail` is
    // the DEFINITIVE proof that a preceding `ldr Xt,[Xn,#0x28]` was a canary
    // read. (Moved above pass 1 in v1.0.999 so the outlined-compare backstop can
    // use it; previously resolved only before pass 2.)
    uintptr_t stkchk_stub = 0;
    if (pltrel_ && symtab_ && strtab_) {
        uintptr_t got_slot = 0;
        for (size_t i = 0; i < pltrel_count_; ++i) {
            uint32_t sym = uint32_t(pltrel_[i].r_info >> 32);
            const char* nm = strtab_ + symtab_[sym].st_name;
            if (nm && std::strcmp(nm, "__stack_chk_fail") == 0) {
                got_slot = reinterpret_cast<uintptr_t>(base_)
                         + pltrel_[i].r_offset;
                break;
            }
        }
        // Stub form: `adrp x16,P ; ldr x17,[x16,#o] ; ...`. Match the
        // entry whose P+o equals __stack_chk_fail's GOT slot.
        if (got_slot) {
            for (const auto& s : segments_) {
                if (!(s.prot & PROT_EXEC)) continue;
                uint32_t* slo = reinterpret_cast<uint32_t*>(s.vaddr);
                uint32_t* shi = slo + (s.memsz / 4) - 1;
                for (uint32_t* p = slo; p < shi && !stkchk_stub; ++p) {
                    if ((p[0] & 0x9F00001Fu) != 0x90000010u) continue; // adrp x16
                    if ((p[1] & 0xFFC003FFu) != 0xF9400211u) continue; // ldr x17,[x16,#o]
                    int64_t immhi = (p[0] >> 5) & 0x7FFFFu;
                    int64_t immlo = (p[0] >> 29) & 0x3u;
                    int64_t imm = (immhi << 2) | immlo;
                    if (imm & (int64_t(1) << 20)) imm |= ~int64_t(0x1FFFFF);
                    uintptr_t page = (reinterpret_cast<uintptr_t>(p)
                                      & ~uintptr_t(0xFFF))
                                     + (uintptr_t(imm) << 12);
                    uintptr_t off = uintptr_t((p[1] >> 10) & 0xFFFu) * 8u;
                    if (page + off == got_slot)
                        stkchk_stub = reinterpret_cast<uintptr_t>(p);
                }
                if (stkchk_stub) break;
            }
        }
    }
    // True iff *ip is `bl <target>`.
    auto is_bl_to = [](const uint32_t* ip, uintptr_t target) -> bool {
        if ((*ip & 0xFC000000u) != 0x94000000u) return false;
        int64_t off = int64_t(int32_t(uint32_t(*ip) << 6) >> 6) * 4;
        return reinterpret_cast<uintptr_t>(ip) + off == target;
    };

    // OUTLINED-COMPARE backstop (see kOutlinedMrs). The LLVM machine outliner
    // can factor BOTH the canary compare AND the mrs into helpers, and can
    // ENTER a function body via a tail `b` from an mrs helper. Then:
    //   * the dataflow never saw the `mrs Xn,TPIDR_EL0` (it lives in a helper at
    //     an unrelated address, reached by a tail branch, not a bl+ret), and
    //   * epilogue_guard_check stops at the `bl <compare>` (the compare is
    //     outlined too), so the func_has_tpidr_mrs backstop can't fire either.
    // But the shape `ldr Xt,[Xn,#0x28] ; bl <compare> ; b.cond <L>` where <L>
    // (the mismatch target) or the fall-through reaches `bl __stack_chk_fail` is
    // DEFINITIVE: a legitimate object-field load at +0x28 is never immediately
    // followed by a bl+conditional-branch into __stack_chk_fail (that branch is
    // only ever the canary check, since __stack_chk_fail is reached from nowhere
    // else). So patch the read regardless of how Xn got TPIDR. The prologue read
    // that stored the saved cookie is the adjacent `mrs Xn ; ldr X,[Xn,#0x28] ;
    // str X,[sp,#M]` form, which the dataflow already zeroes, so zeroing this
    // epilogue read keeps the compare 0-vs-0 == equal (no spurious fail).
    // MEASURED 2026-08-19 on TikTok's libpns_ttmachine_adapter.so: +0xb4c0 (x23)
    // and +0x1862c (x22), both `bl 0x33250` (the one outlined compare) then
    // `b.ne` to `bl __stack_chk_fail`. Requires stkchk_stub; if we could not
    // resolve it, this backstop is inert (safe).
    auto outlined_compare_canary = [&](uint32_t* p, uint32_t* seg_lo,
                                       uint32_t* seg_end) -> bool {
        if (!kOutlinedMrs || !stkchk_stub) return false;
        if (p + 2 >= seg_end) return false;
        uint32_t bl = *(p + 1);
        if ((bl & 0xFC000000u) != 0x94000000u) return false;   // P+1 must be bl
        // The bl target must itself be a plausible OUTLINED CANARY COMPARE: a
        // short straight-line fragment ending in `ret` that contains a `cmp`
        // (SUBS Rd=XZR) and no store/branch that would make it a real callee.
        // This rejects an ordinary `ldr obj.field ; bl real_fn ; b.cond` whose
        // b.cond coincidentally routed to __stack_chk_fail.
        int64_t coff = int64_t(int32_t(uint32_t(bl) << 6) >> 6) * 4;
        uint32_t* ct = reinterpret_cast<uint32_t*>(
            reinterpret_cast<uintptr_t>(p + 1) + coff);
        if (ct <= seg_lo || ct >= seg_end) return false;
        bool cmp_seen = false, ret_seen = false;
        for (int i = 0; i < 8 && (ct + i) < seg_end; ++i) {
            uint32_t j = *(ct + i);
            if (j == 0xD65F03C0u) { ret_seen = true; break; }   // ret
            if ((j & 0xFFE0FC1Fu) == 0xEB00001Fu) { cmp_seen = true; continue; } // cmp (subs xzr)
            // A call/branch/return other than the trailing ret means this is not
            // a tiny leaf compare helper.
            if ((j & 0xFC000000u) == 0x94000000u) return false;   // bl
            if ((j & 0xFFFFFC1Fu) == 0xD61F0000u) return false;   // br
            if ((j & 0xFFFFFC1Fu) == 0xD63F0000u) return false;   // blr
            if ((j & 0xFC000000u) == 0x14000000u) return false;   // b
        }
        if (!cmp_seen || !ret_seen) return false;
        // P+2 must be a conditional branch, and either its taken target or the
        // fall-through must reach `bl __stack_chk_fail` within a few insns.
        uint32_t* bc = p + 2;
        if ((*bc & 0xFF000010u) != 0x54000000u) return false;   // b.cond
        int32_t imm19 = int32_t(uint32_t(((*bc >> 5) & 0x7FFFFu)) << 13) >> 13;
        uint32_t* tgt = bc + imm19;
        for (uint32_t* k = tgt; k >= seg_lo && k < seg_end && k < tgt + 3; ++k)
            if (is_bl_to(k, stkchk_stub)) return true;
        for (uint32_t* k = bc + 1; k < seg_end && k < bc + 3; ++k)
            if (is_bl_to(k, stkchk_stub)) return true;
        return false;
    };

    ct_lap("canary setup (stub resolve, lambdas)");
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < 4) continue;
        uint32_t* lo = reinterpret_cast<uint32_t*>(s.vaddr);
        uint32_t* end = lo + (s.memsz / 4);

        clear_all();
        func_has_tpidr_mrs = false;

        // Loop-redef pre-scan (see MACAPK_NO_CANARY_LOOP_CLEAR above): for every
        // backward branch S->T (loop head T, back-edge at S), record which GP
        // registers the loop body [T,S) rewrites, keyed by the loop head T. The
        // main scan then drops is_tpidr[] for those registers when it reaches T,
        // so a register REUSED inside the loop is no longer wrongly treated as
        // still-TPIDR by the address-ordered walk. Only b/b.cond/cbz/cbnz/tbz
        // count as back-edges; `bl` is a call, not a loop. A body larger than the
        // cap clears all regs (safe over-approx). Offsets are in instruction
        // words, so target = p + sext(imm).
        //
        // Kept as a SORTED (loop head, regmask) vector, walked by a cursor in the
        // main scan below, because that scan visits every word in address order.
        // It used to be a std::unordered_map probed with find(p) on EVERY word:
        // measured 2026-09-22 on libminecraftpe (~72 M words, every launch) the
        // main scan took ~5 s, most of it those probes. Same heads, same masks
        // (duplicate heads merge by OR, as the map's |= did), so the output is
        // identical -- checked by the post-canary .text digest below.
        const uint64_t ct_pre_t0 = ct_on ? clock_gettime_nsec_np(CLOCK_UPTIME_RAW) : 0;
        if (ct_on) ct_words += (uint64_t)(end - lo);
        std::vector<std::pair<uint32_t*, uint32_t>> loop_redef;
        if (loop_clear) {
            constexpr ptrdiff_t kMaxBody = 4096;
            // regs_written() of the last kRing words, each word decoded ONCE.
            // Loop bodies overlap heavily -- libminecraftpe has 1.28 M back-edges
            // whose bodies total 199 M words over 74.5 M words of code, so
            // decoding every body word per back-edge cost ~0.8 s of every launch.
            // regs_written is a pure function of the word, so ORing cached
            // values is identical to re-decoding them.
            constexpr size_t kRing = 4096;
            static_assert(kRing >= (size_t)kMaxBody && (kRing & (kRing - 1)) == 0,
                          "the ring must hold a whole max-size loop body");
            std::vector<uint32_t> ring(kRing, 0u);
            for (uint32_t* p = lo; p < end; ++p) {
                const size_t idx = (size_t)(p - lo);
                uint32_t insn = *p;
                int64_t off = 0;
                bool is_br = false;
                if ((insn & 0xFC000000u) == 0x14000000u) {            // b
                    off = (int64_t)((int32_t)(insn << 6) >> 6);       // sext imm26
                    is_br = true;
                } else if ((insn & 0xFF000010u) == 0x54000000u        // b.cond
                        || (insn & 0x7E000000u) == 0x34000000u) {     // cbz/cbnz
                    off = (int64_t)((int32_t)(insn << 8) >> 13);      // sext imm19 (23:5)
                    is_br = true;
                } else if ((insn & 0x7E000000u) == 0x36000000u) {     // tbz/tbnz
                    off = (int64_t)((int32_t)(insn << 13) >> 18);     // sext imm14 (18:5)
                    is_br = true;
                }
                if (is_br && off < 0) {                               // back-edges only
                    uint32_t* tgt = p + off;
                    if (tgt >= lo && tgt < p) {
                        uint32_t mask;
                        const ptrdiff_t body = p - tgt;               // words [tgt, p)
                        if (body > kMaxBody) {
                            mask = 0xFFFFFFFFu;
                        } else {
                            mask = 0u;
                            const size_t n = (size_t)body;
                            const size_t a = (idx - n) & (kRing - 1);
                            const size_t first = std::min(n, kRing - a);
                            for (size_t k = 0; k < first; ++k) mask |= ring[a + k];
                            for (size_t k = 0; k < n - first; ++k) mask |= ring[k];
                            if (ct_on) ct_body_words += (uint64_t)n;
                        }
                        if (ct_on) ++ct_backedges;
                        loop_redef.emplace_back(tgt, mask);
                    }
                }
                // After the back-edge test: a body is [tgt, p), never p itself.
                ring[idx & (kRing - 1)] = regs_written(insn);
            }
            std::sort(loop_redef.begin(), loop_redef.end(),
                      [](const std::pair<uint32_t*, uint32_t>& a,
                         const std::pair<uint32_t*, uint32_t>& b) { return a.first < b.first; });
            size_t w = 0;
            for (size_t r = 0; r < loop_redef.size(); ++r) {
                if (w && loop_redef[w - 1].first == loop_redef[r].first)
                    loop_redef[w - 1].second |= loop_redef[r].second;
                else
                    loop_redef[w++] = loop_redef[r];
            }
            loop_redef.resize(w);
        }
        size_t loop_redef_i = 0;   // cursor: first head not below the current p

        if (ct_on) ct_prescan_ns += clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - ct_pre_t0;
        for (uint32_t* p = lo; p < end; ++p) {
            uint32_t insn = *p;

            // Control-flow-aware fixup: at a loop head, drop the thread-pointer
            // flag for every register the loop body rewrites (see the pre-scan
            // above), so a register reused inside the loop is not mistaken for a
            // live stack-guard base by the address-ordered scan.
            if (loop_clear && loop_redef_i < loop_redef.size()) {
                // p only ever advances (every exit from an iteration is ++p), so
                // the cursor never has to move back.
                while (loop_redef_i < loop_redef.size() && loop_redef[loop_redef_i].first < p)
                    ++loop_redef_i;
                if (loop_redef_i < loop_redef.size() && loop_redef[loop_redef_i].first == p) {
                    uint32_t m = loop_redef[loop_redef_i].second;
                    for (int r = 0; r < 32; ++r)
                        if (m & (1u << r)) is_tpidr[r] = false;
                    if (kCanaryTrace && m)
                        std::fprintf(stderr,
                            "[canary-trace] %s loop-head 0x%llx clears regmask "
                            "0x%08x\n", dbg_name_,
                            (unsigned long long)(reinterpret_cast<uint8_t*>(p)
                                                 - base_),
                            m);
                }
            }

            // Frame-push prologue `stp x29,x30,[sp,#-N]!` (pre-index) marks a new
            // function scope: reset the stack-protected flag used by the
            // epilogue-check backstop so a previous function's TPIDR state cannot
            // leak in. (It is also a store; falls through to the store handling
            // below, which `continue`s.)
            if ((insn & 0xFFC00000u) == 0xA9800000u
                && (insn & 0x1Fu) == 29u && ((insn >> 10) & 0x1Fu) == 30u
                && ((insn >> 5) & 0x1Fu) == 31u) {
                func_has_tpidr_mrs = false;
            }

            // mrs Xt, TPIDR_EL0
            if ((insn & 0xFFFFFFE0u) == 0xD53BD040u) {
                uint32_t rt = insn & 0x1Fu;
                is_tpidr[rt] = true;
                func_has_tpidr_mrs = true;   // this function is stack-protected
                if (zero_tp) {
                    *p = 0xD2800000u | rt;   // mov Xt, #0 -- force tp=0 (TLSDESC)
                    // Erases pass 2's `mrs _,TPIDR_EL0` stop marker exactly as
                    // the canary rewrites erase the `ldr [_,#0x28]` one; record
                    // it so pass 2 still sees a region boundary here.
                    pass1_neutralized.insert(reinterpret_cast<uintptr_t>(p));
                    ++tp_zeroed;
                }
                continue;
            }

            // sub/add sp, sp, #imm -- sp-relative slot offsets become invalid;
            // drop the tracked TPIDR spill slot. (Writes sp = x31, harmless.)
            if (((insn >> 24) == 0x91u || (insn >> 24) == 0xD1u)
                && (insn & 0x1Fu) == 31u && ((insn >> 5) & 0x1Fu) == 31u) {
                tpidr_slot_off = -1;
                continue;
            }
            // ldr Xt, [sp, #imm] (unsigned-offset, 64-bit): a reload from the
            // tracked TPIDR spill slot re-establishes Xt as the thread pointer
            // (so the following epilogue guard load gets patched). Any other sp
            // load just clears Xt. Must precede the [Xn,#0x28] canary match so
            // a guard slot that happens to sit at sp+0x28 is handled here.
            if ((insn & 0xFFC00000u) == 0xF9400000u
                && ((insn >> 5) & 0x1Fu) == 31u) {
                int off = (int)(((insn >> 10) & 0xFFFu) * 8u);
                uint32_t rt = insn & 0x1Fu;
                is_tpidr[rt] = (tpidr_slot_off >= 0 && off == tpidr_slot_off);
                continue;
            }
            // str Xt, [sp, #imm] (unsigned-offset, 64-bit): record the slot when
            // spilling TPIDR; invalidate it when a non-TPIDR value overwrites the
            // same slot. (A store does not write Rt.)
            if ((insn & 0xFFC00000u) == 0xF9000000u
                && ((insn >> 5) & 0x1Fu) == 31u) {
                int off = (int)(((insn >> 10) & 0xFFFu) * 8u);
                uint32_t rt = insn & 0x1Fu;
                if (is_tpidr[rt]) tpidr_slot_off = off;
                else if (off == tpidr_slot_off) tpidr_slot_off = -1;
                continue;
            }
            // stp Xt, Xt2, [sp, #imm] (signed-offset, 64-bit): a pair spill. The
            // compiler also spills the guard pointer as one half of an STP rather
            // than a lone STR -- e.g. firebase::analytics::LogEvent does
            // `stp x19,x1,[sp]` (x19 = TPIDR) in its prologue and reloads it with
            // `ldr x8,[sp] ; ldr x8,[x8,#0x28]` at the epilogue. Without tracking
            // the STP slot the reload looks like a fresh GP write, the guard read
            // stays unpatched, and [TPIDR_EL0+0x28] faults (mrs TPIDR_EL0 on macOS
            // returns a small magic value ~0x1004, not the Bionic TLS base). Record
            // whichever half holds TPIDR; invalidate when a non-TPIDR pair clobbers
            // the tracked slot. (Pre/post-index STP that also moves sp is left to
            // the generic store path -- rare for a guard spill.)
            if ((insn & 0xFFC00000u) == 0xA9000000u
                && ((insn >> 5) & 0x1Fu) == 31u) {
                int imm7 = (int)((insn >> 15) & 0x7Fu);
                if (imm7 & 0x40) imm7 -= 0x80;          // sign-extend 7-bit
                int base = imm7 * 8;
                uint32_t rt  = insn & 0x1Fu;
                uint32_t rt2 = (insn >> 10) & 0x1Fu;
                if (is_tpidr[rt])       tpidr_slot_off = base;
                else if (is_tpidr[rt2]) tpidr_slot_off = base + 8;
                else if (tpidr_slot_off == base || tpidr_slot_off == base + 8)
                    tpidr_slot_off = -1;
                continue;
            }

            // ldr Xt, [Xn, #0x28] -- patch if Xn is known-TPIDR.
            if ((insn & 0xFFFFFC00u) == 0xF9401400u) {
                uint32_t rn = (insn >> 5) & 0x1Fu;
                uint32_t rt = insn & 0x1Fu;
                if (is_tpidr[rn]) {
                    if (kCanaryTrace)
                        std::fprintf(stderr,
                            "[canary-trace] %s dataflow 0x%llx (x%u<-[x%u,#0x28])\n",
                            dbg_name_,
                            (unsigned long long)(reinterpret_cast<uint8_t*>(p) - base_),
                            rt, rn);
                    *p = 0xD2800000u | rt | guard_mov_imm;  // mov Xt,#0 (or MACAPK_CANARY_MARK)
                    pass1_neutralized.insert(reinterpret_cast<uintptr_t>(p));
                    is_tpidr[rt] = false;
                    ++patched;
                    continue;
                }
                // BACKSTOP: the linear is_tpidr[] tracking above lost Xn (a
                // reused / early-return-restored / mov-copied thread-pointer
                // register), but this function is stack-protected (has an
                // `mrs _,TPIDR_EL0`) AND this load feeds the stack-canary CHECK
                // idiom. That combination is specific to a guard load, so patch
                // it too (a legitimate object-field load at +0x28 is not compared
                // against a stack-spilled value + branch). See func_has_tpidr_mrs.
                // epilogue_guard_check follows one unconditional `b` so a guard load
                // that branches to a SHARED compare block (renoir 0x7c1f2bc) matches.
                if (func_has_tpidr_mrs && epilogue_guard_check(p, lo, end, rt)) {
                    if (kCanaryTrace)
                        std::fprintf(stderr,
                            "[canary-trace] %s backstop 0x%llx (x%u<-[x%u,#0x28])\n",
                            dbg_name_,
                            (unsigned long long)(reinterpret_cast<uint8_t*>(p) - base_),
                            rt, rn);
                    *p = 0xD2800000u | rt | guard_mov_imm;  // mov Xt,#0 (or MACAPK_CANARY_MARK)
                    pass1_neutralized.insert(reinterpret_cast<uintptr_t>(p));
                    is_tpidr[rt] = false;
                    ++patched;
                    continue;
                }
                // OUTLINED-COMPARE backstop (see outlined_compare_canary): the
                // mrs AND the compare are both outlined, so neither the dataflow
                // nor epilogue_guard_check saw this. The `bl <compare> ; b.cond
                // -> __stack_chk_fail` shape is the definitive canary signature.
                if (outlined_compare_canary(p, lo, end)) {
                    if (kCanaryTrace)
                        std::fprintf(stderr,
                            "[canary-trace] %s outlined-cmp 0x%llx (x%u<-[x%u,#0x28])\n",
                            dbg_name_,
                            (unsigned long long)(reinterpret_cast<uint8_t*>(p) - base_),
                            rt, rn);
                    *p = 0xD2800000u | rt | guard_mov_imm;  // mov Xt,#0 (or MACAPK_CANARY_MARK)
                    pass1_neutralized.insert(reinterpret_cast<uintptr_t>(p));
                    is_tpidr[rt] = false;
                    ++patched;
                    continue;
                }
                // Not a canary load -- ldr still writes Rt.
                is_tpidr[rt] = false;
                continue;
            }

            // RET (canonical encoding) -- end of function path; clear
            // the table so the next function starts fresh.
            if (insn == 0xD65F03C0u) {
                clear_all();
                continue;
            }
            // BR Xn -- indirect jump; lose track of all regs.
            if ((insn & 0xFFFFFC1Fu) == 0xD61F0000u) {
                clear_all();
                continue;
            }

            // BL <label> / BLR Xn: clears caller-saved registers, but
            // callee-saved (x19..x28, x29) are preserved by AAPCS64
            // across the call.
            if ((insn & 0xFC000000u) == 0x94000000u) {
                clear_caller_saved();
                // If the target is an LLVM-outlined prologue that hands a
                // callee-saved register back holding TPIDR_EL0, propagate that
                // (see kOutlinedMrs). Runs AFTER clear_caller_saved so the
                // propagated x19..x28 flags survive (that clears only x0..x18,x30).
                propagate_outlined_tpidr(p, lo, end);
                continue;
            }
            if ((insn & 0xFFFFFC1Fu) == 0xD63F0000u) {
                clear_caller_saved();
                continue;
            }

            // Branches with no destination register -- leave state.
            if (is_no_dest(insn)) continue;

            // Stores: don't clear Rt (it's a source, not a dest).
            if (is_store_64(insn) || is_store_32(insn)
                || is_store_byte_or_half(insn)
                || is_stp_64(insn)) {
                continue;
            }

            // LDP: clears both Rt and Rt2.
            if (is_ldp_64(insn)) {
                uint32_t rt  = insn & 0x1Fu;
                uint32_t rt2 = (insn >> 10) & 0x1Fu;
                is_tpidr[rt]  = false;
                is_tpidr[rt2] = false;
                continue;
            }

            // FP / Advanced-SIMD instructions: their Rd/Rt field (bits
            // 4:0) selects a V (FP/SIMD) register or an opcode subfield,
            // NOT a GP register. The default Rd-clear below would mis-read
            // it as a GP destination. This was a real miss: Unity's
            // stack-protected prologues interleave FP setup between the
            // guard fetch and its use, e.g.
            //     mrs x8, TPIDR_EL0
            //     fmov s8, s1            ; FP reg 8 -- NOT x8
            //     ldr x8, [x8, #0x28]    ; Bionic stack-guard load
            // The `fmov s8` cleared is_tpidr[x8], so the guard load went
            // unpatched and faulted at runtime (TPIDR_EL0 is not the
            // Bionic TLS base on macOS; [TPIDR_EL0+0x28] is garbage).
            // Skipping FP/SIMD ops is correct, not just pragmatic: they
            // cannot write the GP register that holds the thread pointer,
            // so the flag stays valid across them. (The lone FP->GP
            // transfer that does write a GP reg, `fmov Xd,Dn`, only
            // matters in the sequence `mrs Xn,TPIDR; fmov Xn,Dm; ldr
            // Xn,[Xn,#0x28]`, which no compiler emits.)
            //   - FP/SIMD data processing + scalar: bits[27:25] == 0b111
            //   - FP/SIMD load/store (V=1): bit27==1 && bit26==1 && bit25==0
            if (((insn >> 25) & 0x7u) == 0x7u
                || (((insn >> 27) & 1u) == 1u
                    && ((insn >> 26) & 1u) == 1u
                    && ((insn >> 25) & 1u) == 0u)) {
                continue;
            }

            // Default: assume the instruction writes Rd at bits 0..4.
            // (Covers ADD/SUB/MOV/most arithmetic and load-class
            // instructions; safe to over-clear -- worst case we miss
            // a later canary patch and surface a clean SIGSEGV at the
            // exact instruction that needs special handling.)
            is_tpidr[insn & 0x1Fu] = false;
        }
    }

    // Second pass: epilogue-signature match.
    //
    // The dataflow pass loses track of the thread-pointer register
    // when the compiler spills it to the stack (`str Xn, [sp, #N]`)
    // and reloads it later. After the reload, is_tpidr[Xn] is false
    // because our default rule clears Rd on any non-recognised
    // instruction.
    //
    // The compiler-generated canary CHECK has a stable pair of loads:
    //
    //     ldr  Xt, [Xn, #0x28]            ; (1) current canary
    //     <ldur Xs, [x29, #imm9]> | OR    ; (2a) saved, frame-rel
    //     <ldr  Xs, [sp,  #imm12]>        ; (2b) saved, sp-rel
    //
    // followed by `cmp Xt, Xs; b.ne __stack_chk_fail` -- but those
    // last two instructions are NOT always at positions p+2 / p+3.
    // The compiler interleaves them with surrounding work; for
    // example, `ax::Mat4::inverse` at libOpenGD+0x71fffc emits
    // (1)+(2) and then four float/cmp/cset instructions before the
    // canary `cmp` and `b.ne`. So we cannot anchor on (3)+(4) at
    // fixed offsets and must filter another way.
    //
    // EARLY-MATCH BUG (2026-05-16 GD regression): an earlier version
    // of this signature stopped at instructions (1)+(2) on the
    // assumption that "no other compiler output emits `ldr [_, #0x28]`
    // immediately followed by a frame/stack-relative load." That
    // assumption is wrong. cocos2d-x v2's `CCString::CCString` at
    // libcocos2dcpp+0x816018 uses exactly that pair to reload its
    // `const std::string&` argument from the saved-arg slot followed
    // by restoring the caller's x19 from the prologue spill:
    //
    //     ldr  x1,  [x29, #0x28]   ; reload saved std::string&
    //     ldr  x19, [sp,  #0x10]   ; restore caller's x19
    //
    // Patching the first instruction to `mov x1, #0` then propagated
    // a NULL std::string& into the tail-called pre-C++11 GNU
    // libstdc++ copy constructor, which crashed on `ldr x1, [x1]`
    // inside `CCConfiguration::init` -> `CCString::create("2.2.3")`
    // and made every cold start of GD Lite SIGSEGV in nativeInit
    // before any rendering work.
    //
    // The distinguishing property of REAL canary checks vs the
    // CCString::CCString-class false positives is the SOURCE
    // register Xn in instruction (1):
    //
    //   * Real canary epilogue: Xn is a callee-saved general
    //     register (x19..x28) that holds the TPIDR_EL0-derived
    //     pointer. The compiler reserves one such register for the
    //     canary across the function body OR reloads it from a
    //     stack spill into a callee-saved slot before the check.
    //
    //   * The frame pointer (x29) and stack pointer (x31==sp) are
    //     NEVER the source register for a canary load -- the
    //     canary value lives at TPIDR_EL0+0x28, not at a stack
    //     offset of 0x28 from either FP or SP. Any `ldr Xt,
    //     [x29, #0x28]` or `ldr Xt, [sp, #0x28]` is something
    //     else (typically a stack-slot reload for a saved argument
    //     or local).
    //
    // The historical policy rejected Xn == 29 (frame) and Xn == 31 (sp)
    // outright: the canary lives at TPIDR_EL0+0x28, never at a stack
    // offset, so `ldr Xt,[x29,#0x28]` was assumed to be a frame-relative
    // local. That assumption breaks on libunity. Some functions spill
    // the TPIDR_EL0-derived pointer to the stack in the prologue and
    // RELOAD it into x29 before the epilogue check, so there
    // `ldr Xt,[x29,#0x28]` IS a real canary load. Crossy Road crashed at
    // exactly such a site (libunity+0xa458c8, x29 holding the macOS
    // TPIDR_EL0 value, which is not the Bionic TLS base -> [x29+0x28]
    // faults). We now ALLOW Xn==29/31, but only with the single
    // definitive canary signature: the mismatch branch reaches
    // __stack_chk_fail. We resolve that PLT stub once, up front, from
    // the PLT relocations; if it can't be found we fall back to the old
    // safe skip.
    // stkchk_stub + is_bl_to are resolved above pass 1 (moved in v1.0.999).
    ct_lap("canary pass 1 (loop pre-scan + dataflow)");
    if (ct_on) std::fprintf(stderr, "[load-timing] %s:   of which loop pre-scan %9.1f ms "
                            "(%llu back-edges, %llu body words re-scanned, %llu words)\n",
                            dbg_name_, (double)ct_prescan_ns / 1e6,
                            (unsigned long long)ct_backedges, (unsigned long long)ct_body_words,
                            (unsigned long long)ct_words);
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < 8) continue;
        uint32_t* lo = reinterpret_cast<uint32_t*>(s.vaddr);
        uint32_t* end = lo + (s.memsz / 4) - 1;
        for (uint32_t* p = lo; p < end; ++p) {
            uint32_t insn = *p;
            // (1) ldr Xt, [Xn, #0x28]
            if ((insn & 0xFFFFFC00u) != 0xF9401400u) continue;
            uint32_t rn = (insn >> 5) & 0x1Fu;
            // x29/sp-based loads are usually frame-relative locals, not
            // canaries -- but can be a real canary when the compiler
            // reloaded a spilled thread pointer into x29 (see above).
            // Permit them only when we can anchor on __stack_chk_fail
            // (checked after the cmp/branch gate); otherwise keep the
            // safe skip.
            bool base_is_fp_or_sp = (rn == 29u || rn == 31u);
            if (base_is_fp_or_sp && !stkchk_stub) continue;

            uint32_t next = *(p + 1);
            // (2) ldur Xs, [x29, #imm9]   mask 0xFFE00FE0, match 0xF84003A0
            //     ldr  Xs, [sp,  #imm12]  mask 0xFFC003E0, match 0xF94003E0
            bool is_ldur_x29 = ((next & 0xFFE00FE0u) == 0xF84003A0u);
            bool is_ldr_sp   = ((next & 0xFFC003E0u) == 0xF94003E0u);
            if (!is_ldur_x29 && !is_ldr_sp) {
                // MULTI-STEP saved canary (libunity huge-frame, >32 KB). The saved
                // value is computed across several insns (`add Xtmp,sp,#imm ; [add
                // Xtmp,Xtmp,#imm] ; ldr Xs,[Xtmp,#imm]`) because the frame offset
                // overflows a single `ldr` imm12, so the saved load is NOT the simple
                // sp/x29 `ldr` at p+1 the strict gate above wants (e.g. libunity
                // +0x637cd0: `ldr x8,[x22,#0x28] ; add x9,x9,#0x830 ;
                // ldr x9,[x9,#0x7ff8] ; cmp x8,x9 ; b.ne`).
                //
                // Recognise it PRECISELY. A MACAPK_CANARY_TRACE pass over Crossy
                // showed four false-positive shapes in libil2cpp that any loose match
                // patched (-> NULLed a live pointer -> pc=0 in JNI_OnLoad): (a)
                // `ldr Xt,[Xt,#0x28]` pointer-chase (Xn==Xt); (b) Xt overwritten
                // before the cmp (`mov Xt,#0`); (c) Xt consumed before the cmp
                // (`cbz Xt` / `str Xt,...`); (d) the first cmp on Xt uses a non-loaded
                // reg and a LATER coincidental `cmp Xt,<ldr>` matched. The genuine
                // canary read feeds Xt UNTOUCHED into the FIRST `cmp Xt,Xs` whose
                // other operand is a just-loaded value, then b.cond->__stack_chk_fail.
                // Require exactly that: Xn!=Xt; Xt pristine (not in any Rd/Rn/Rm field)
                // until the first cmp; that cmp pairs Xt with the last-loaded reg; the
                // __stack_chk_fail anchor holds. The pristine check is conservative
                // (an intervening immediate field aliasing Xt's number forces a safe
                // SKIP, never a false patch).
                const uint32_t rt = insn & 0x1Fu;
                // A canary read normally loads INTO Xt FROM a different (thread-
                // pointer) register, so Xn!=Xt. But a huge-frame function may SPILL
                // the thread pointer and RELOAD it into Xt right before the guard
                // read, giving `ldr Xt,[sp,#imm] ; ldr Xt,[Xt,#0x28]` (Xn==Xt) -- a
                // GENUINE canary (libunity+0x6c926c: `ldr x8,[sp,#0x28] ; ... ;
                // ldr x8,[x8,#0x28] ; cmp x8,x9 ; b.ne __stack_chk_fail`). Admit the
                // Xn==Xt form ONLY when the immediately-preceding insn loads Xt from
                // a stack slot (the thread-pointer reload), which a real `ldr Xt,[Xt,
                // #0x28]` field pointer-chase (libil2cpp+0x4062dc4) never has. (The
                // chase is also rejected later because its first cmp on Xt pairs with
                // a non-loaded reg, but this keeps the gate tight.)
                bool ms_gate = (rn != rt);
                if (rn == rt) {
                    // Look BACK up to 4 insns for the thread-pointer reload
                    // `ldr Xt,[sp/x29,#imm]`. The multi-step saved-canary address
                    // computation (`add Xtmp,sp,#imm ; add Xtmp,Xtmp,#imm`) sits
                    // between the reload and the guard read, so the reload is NOT at
                    // p-1 (at libunity+0x6c926c it is p-3). Xt must not be rewritten
                    // by any other insn in between (else it is not the spilled ptr).
                    for (uint32_t* b = p - 1; b >= lo && b >= p - 4; --b) {
                        uint32_t bm = *b;
                        if (((bm & 0x1Fu) == rt) &&
                            (((bm & 0xFFC003E0u) == 0xF94003E0u) ||   // ldr  Xt,[sp,#imm]
                             ((bm & 0xFFE00FE0u) == 0xF84003A0u))) {   // ldur Xt,[x29,#imm]
                            ms_gate = true; break;
                        }
                        if ((bm & 0x1Fu) == rt) break;   // Xt set by a non-spill insn
                    }
                }
                if (stkchk_stub && ms_gate) {
                    int rs_ldr = -1; uint32_t* mbc = nullptr; bool ok = false;
                    uint32_t rs_ldr_base = 0;   // Rn of the OTHER cmp operand's load
                    for (uint32_t* z = p + 1; z < end && z < p + 8; ++z) {
                        uint32_t zi = *z;
                        if (zi == 0xD65F03C0u) break;                  // ret
                        if ((zi & 0xFFFFFC1Fu) == 0xD61F0000u) break;  // br Xn
                        if ((zi & 0xFFFFFFE0u) == 0xD53BD040u) break;  // mrs _,TPIDR
                        if ((zi & 0xFFFFFC00u) == 0xF9401400u) break;  // another ldr[_,#0x28]
                        // ...or one pass 1 rewrote to `mov Xd,#0` (same marker
                        // erasure; see pass1_neutralized's declaration).
                        if (kMarkerFix &&
                            pass1_neutralized.count(
                                reinterpret_cast<uintptr_t>(z))) break;
                        if ((zi & 0xFFE0FC1Fu) == 0xEB00001Fu) {       // cmp Xa,Xb
                            uint32_t ra = (zi >> 5) & 0x1Fu, rb = (zi >> 16) & 0x1Fu;
                            if (ra == rt || rb == rt) {   // FIRST cmp on Xt decides
                                uint32_t other = (ra == rt) ? rb : ra;
                                if (rs_ldr >= 0 && other == uint32_t(rs_ldr)) {
                                    for (uint32_t* r = z + 1; r < end && r < z + 4; ++r)
                                        if ((*r & 0xFF000010u) == 0x54000000u) { mbc = r; break; }
                                    ok = (mbc != nullptr);
                                }
                                break;
                            }
                            continue;   // cmp of unrelated regs: does not touch Xt
                        }
                        // Xt must stay pristine until the canary cmp.
                        if ((zi & 0x1Fu) == rt || ((zi >> 5) & 0x1Fu) == rt ||
                            ((zi >> 16) & 0x1Fu) == rt) break;
                        if ((zi & 0xFFC00000u) == 0xF9400000u) {       // ldr Xd,[_,#imm]
                            rs_ldr = int(zi & 0x1Fu);
                            rs_ldr_base = (zi >> 5) & 0x1Fu;
                        }
                    }
                    // GLOBAL-__stack_chk_guard guard (v1.0.278, GD Lite libcocos2dcpp,
                    // statically-linked pugixml etc. compiled WITHOUT Bionic's
                    // -mstack-protector-guard=tls): the epilogue is
                    //   ldr Xt,[x29/sp,#0x28]  (SAVED canary, from the stack slot)
                    //   ldr Xo,[Xg]            (CURRENT = *__stack_chk_guard)
                    //   cmp Xt,Xo ; b.ne __stack_chk_fail
                    // Here Xt is the SAVED reload (base = fp/sp), NOT the canary
                    // source -- the source is the global-guard deref Xo. The guard
                    // is a real, resolved, mapped global, so this check PASSES
                    // natively if left alone. NULLing the fp/sp saved reload (which
                    // the gate above otherwise does) makes it compare 0 against the
                    // real *guard -> spurious __stack_chk_fail (GD Lite render crash).
                    // A GENUINE multi-step canary reads from the THREAD POINTER (a
                    // GPR) or a spilled copy, never directly from fp/sp here, and its
                    // OTHER operand is the saved value loaded from the STACK. So:
                    // when the matched read is fp/sp-based AND the other operand is
                    // loaded via a register deref (rs_ldr_base not fp/sp = the global
                    // guard), SKIP -- leave the native global-guard check intact.
                    bool global_guard_saved_reload =
                        (rn == 29u || rn == 31u) &&
                        rs_ldr_base != 29u && rs_ldr_base != 31u;
                    if (ok && mbc && !global_guard_saved_reload) {
                        bool anch = false;
                        int32_t imm19 = int32_t(uint32_t(((*mbc >> 5) & 0x7FFFFu)) << 13) >> 13;
                        uint32_t* tgt = mbc + imm19;
                        for (uint32_t* k = tgt; k >= lo && k < end && k < tgt + 3; ++k)
                            if (is_bl_to(k, stkchk_stub)) { anch = true; break; }
                        for (uint32_t* k = mbc + 1; !anch && k < end && k < mbc + 3; ++k)
                            if (is_bl_to(k, stkchk_stub)) { anch = true; break; }
                        if (anch) {
                            if (kCanaryTrace)
                                std::fprintf(stderr,
                                    "[canary-trace] %s multistep 0x%llx (x%u<-[x%u,#0x28])\n",
                                    dbg_name_,
                                    (unsigned long long)(reinterpret_cast<uint8_t*>(p) - base_),
                                    rt, rn);
                            *p = 0xD2800000u | rt;   // mov Xt, #0
                            ++patched;
                            ++p;
                            continue;
                        }
                    }
                }
                continue;
            }

            uint32_t rt = insn & 0x1Fu;     // Xt  (current-canary dest)
            uint32_t rs = next & 0x1Fu;      // Xs  (saved-value dest)

            // POSITIVE-EVIDENCE GATE (v1.0.177). The (1)+(2) byte pattern
            // above is NECESSARY but not SUFFICIENT for a canary check: it
            // also matches the ubiquitous "ldr this->field@0x28 ; reload a
            // spilled local" idiom (Xn is any callee-saved register holding
            // an object, not the thread pointer). Rewriting THAT to
            // `mov Xt,#0` corrupts libunity's .text -- exactly the Crossy
            // Road crash: libunity+0x100746c `ldr x10,[x19,#0x28]` (the
            // SerializedFile runtime-type-table pointer) followed by
            // `ldr x9,[sp]` was being turned into `mov x10,#0`, so the next
            // `ldr x10,[x10]` dereferenced NULL.
            //
            // The distinguishing property of a REAL canary epilogue is
            // semantic, not positional: the current canary Xt is COMPARED
            // against the saved canary Xs (`cmp Xt,Xs`, i.e. subs xzr) and a
            // conditional branch follows (to __stack_chk_fail). A field
            // pointer is DEREFERENCED or consumed, never compared against the
            // adjacent stack reload. So require, within a short forward
            // window (not crossing a ret/br/new-canary-region), a
            // `cmp {Xt,Xs}` of exactly the two loaded registers, followed by
            // a B.cond. This keeps every genuine canary (caught here only
            // when the first dataflow pass missed it, e.g. a spilled/reloaded
            // thread-pointer register) while leaving legitimate field loads
            // untouched. Verified against the real libunity canary at
            // 0x77de60 (ldr x8,[x24,#0x28]; ldr x9,[sp,#0xa8]; cmp x8,x9;
            // b.ne ->__stack_chk_fail) and the false positive at 0x100746c.
            bool canary = false;
            uint32_t* bcond = nullptr;       // the canary's B.cond
            for (uint32_t* q = p + 2; q < end && q < p + 12; ++q) {
                uint32_t qi = *q;
                if (qi == 0xD65F03C0u) break;                  // ret
                if ((qi & 0xFFFFFC1Fu) == 0xD61F0000u) break;  // br Xn
                if ((qi & 0xFFFFFFE0u) == 0xD53BD040u) break;  // mrs _,TPIDR_EL0
                if ((qi & 0xFFFFFC00u) == 0xF9401400u) break;  // another ldr [_,#0x28]
                // ...including one PASS 1 already rewrote to `mov Xd,#0`, which
                // erases the byte pattern the line above matches. See
                // pass1_neutralized's declaration for the measured case.
                if (kMarkerFix
                    && pass1_neutralized.count(reinterpret_cast<uintptr_t>(q)))
                    break;
                // cmp Xa, Xb  ==  subs xzr, Xa, Xb, LSL #0
                if ((qi & 0xFFE0FC1Fu) == 0xEB00001Fu) {
                    uint32_t ra = (qi >> 5)  & 0x1Fu;          // Rn
                    uint32_t rb = (qi >> 16) & 0x1Fu;          // Rm
                    if ((ra == rt && rb == rs) || (ra == rs && rb == rt)) {
                        for (uint32_t* r = q + 1; r < end && r < q + 4; ++r) {
                            // B.cond (any condition)
                            if ((*r & 0xFF000010u) == 0x54000000u) {
                                canary = true;
                                bcond = r;
                                break;
                            }
                        }
                        break;   // the canary compare decided it
                    }
                    // a cmp of unrelated registers -- keep scanning
                }
            }
            if (!canary) continue;           // legitimate field load: leave it

            // x29/sp base: demand the definitive signature -- the
            // mismatch branch (taken target or fall-through) reaches
            // __stack_chk_fail. This admits the spilled-thread-pointer
            // canary while still rejecting any ordinary two-local
            // compare that happens to read [x29,#0x28].
            if (base_is_fp_or_sp) {
                bool anchored = false;
                if (bcond) {
                    int32_t imm19 = int32_t(
                        uint32_t(((*bcond >> 5) & 0x7FFFFu)) << 13) >> 13;
                    uint32_t* tgt = bcond + imm19;
                    for (uint32_t* k = tgt;
                         k >= lo && k < end && k < tgt + 3; ++k)
                        if (is_bl_to(k, stkchk_stub)) { anchored = true; break; }
                    for (uint32_t* k = bcond + 1;
                         !anchored && k < end && k < bcond + 3; ++k)
                        if (is_bl_to(k, stkchk_stub)) { anchored = true; break; }
                }
                if (!anchored) continue;     // not a canary: leave it
            }

            if (kCanaryTrace)
                std::fprintf(stderr,
                    "[canary-trace] %s pair 0x%llx (x%u<-[x%u,#0x28])\n",
                    dbg_name_,
                    (unsigned long long)(reinterpret_cast<uint8_t*>(p) - base_),
                    rt, rn);
            *p = 0xD2800000u | rt;           // mov Xt, #0
            ++patched;
            ++p;                             // skip past pair
        }
    }
    // DIAGNOSTIC (MACAPK_CANARY_TRACE=1): after neutralisation, report any
    // canary read `ldr Xt,[Xn,#0x28]` we did NOT rewrite that still sits inside
    // a function calling __stack_chk_fail -- those are MISSED sites that fault
    // at runtime (the epilogue compares a real reloaded canary against a
    // sibling read we DID rewrite to 0). Dump the offset + the hex window up to
    // the bl so the matcher gate can be extended for the shape. Off by default.
    ct_lap("canary pass 2");
    if (stkchk_stub && std::getenv("MACAPK_CANARY_TRACE")) {
        for (const auto& s : segments_) {
            if (!(s.prot & PROT_EXEC) || s.memsz < 8) continue;
            uint32_t* lo = reinterpret_cast<uint32_t*>(s.vaddr);
            uint32_t* end = lo + (s.memsz / 4);
            for (uint32_t* p = lo; p < end; ++p) {
                if ((*p & 0xFFFFFC00u) != 0xF9401400u) continue;  // ldr Xt,[Xn,#0x28]
                uint32_t* bl = nullptr;
                for (uint32_t* q = p + 1; q < end && q < p + 48; ++q) {
                    if (*q == 0xD65F03C0u) break;                  // ret
                    if ((*q & 0xFFFFFC1Fu) == 0xD61F0000u) break;  // br Xn
                    if (is_bl_to(q, stkchk_stub)) { bl = q; break; }
                }
                if (!bl) continue;
                unsigned long roff = (unsigned long)(reinterpret_cast<uintptr_t>(p)
                                       - reinterpret_cast<uintptr_t>(base_));
                unsigned long boff = (unsigned long)(reinterpret_cast<uintptr_t>(bl)
                                       - reinterpret_cast<uintptr_t>(base_));
                std::fprintf(stderr,
                    "[canary-trace] MISSED ldr@+0x%lx (bl __stack_chk_fail @+0x%lx) insns:",
                    roff, boff);
                for (uint32_t* d = p; d <= bl; ++d)
                    std::fprintf(stderr, " %08x", *d);
                std::fprintf(stderr, "\n");
            }
        }
    }
    if (tp_zeroed)
        std::fprintf(stderr,
            "[arm64-native] %s: TLSDESC tp-zero rewrote %zu mrs TPIDR_EL0 "
            "site(s) to mov #0\n", dbg_name_, tp_zeroed);
    // MACAPK_LOAD_TIMING=1 also prints a digest of every executable segment as
    // this pass leaves it, so an optimisation of the pass can be proven
    // output-identical on a real image (compare the line across two builds).
    if (ct_on) {
        uint64_t h = 1469598103934665603ull;        // FNV-1a 64, over 8-byte words
        for (const auto& s : segments_) {
            if (!(s.prot & PROT_EXEC)) continue;
            const uint64_t* w = reinterpret_cast<const uint64_t*>(s.vaddr);
            const size_t nw = s.memsz / 8;
            for (size_t i = 0; i < nw; ++i) { h ^= w[i]; h *= 1099511628211ull; }
        }
        std::fprintf(stderr, "[load-timing] %s:   post-canary .text digest %016llx (%zu sites)\n",
                     dbg_name_, (unsigned long long)h, patched);
    }
    if (tp_zeroed_out) *tp_zeroed_out = tp_zeroed;
    return patched;
}

// ---------------------------------------------------------------------------
// zlib `inflate()` entry tracepoint
//
// Diagnostic patch for the libfmod-UAF -> zlib-state-corruption crash class
// that has dominated GD Lite failures across multiple sessions. The crash
// manifests inside zlib's inflate decode loop (sometimes the bit-reverse
// scatter in `inflate_table`, sometimes the distance-copy unroll in
// `inflate_fast`, sometimes the output-buffer overflow in `inflate`'s main
// switch). All are downstream symptoms of an inflate_state struct whose
// fields (lens[], avail_out, wsize, etc.) get clobbered by a concurrent
// libfmod stale-pointer write.
//
// The 14th-pass investigation narrowed the corruption source to a libfmod
// dangling pointer pointing into memory that cocos2d-x HANDED libfmod and
// then reused for other allocations (per project_gd_zlib_crash_diagnosis).
// What we still need: WHEN the corruption hits state. Adding a tracepoint
// at every `inflate()` call that dumps key state fields tells us whether
// state is already corrupt on entry (so the scribble happened between
// inflateInit2_ and the first inflate call) or whether state starts clean
// and degrades mid-decode (so the scribble is concurrent with zlib).
//
// Method: replace inflate's first instruction with `b trampoline`. The
// trampoline saves caller-saved regs, calls the shim's tracer, restores
// regs, executes the displaced first instruction, and branches back to
// inflate+4.
//
// Field offsets verified against zlib 1.2.8 inflate.h / zlib.h (the
// version Jean-loup Gailly + Mark Adler stamp embedded in libcocos):
//   z_stream:        +8 avail_in (u32), +32 avail_out (u32), +56 state (ptr)
//   inflate_state:   +0 mode (inflate_mode enum, valid 16180..16210),
//                    +52 wsize, +56 whave, +60 wnext (all unsigned)
//
// We gate by bytewise signature match of inflate's expected prologue
// (`stp x29, x30, [sp, #-0xc0]!` = 0xa9b47bfd). Different zlib versions
// or differently-compiled inflate bodies skip the patch -- no false
// installs that would corrupt unrelated code.
void Image::apply_zlib_inflate_tracepoint() {
    // t3-syms: exports_ (eager map) is retired; lookup() now resolves by
    // hashing on demand -- see its citation above gnu_hash_lookup().
    void* inflate_sym = lookup("inflate");
    if (!inflate_sym) return;
    uintptr_t inflate_pc = uintptr_t(inflate_sym);
    uint32_t* inflate_instr = reinterpret_cast<uint32_t*>(inflate_pc);
    if (*inflate_instr != 0xa9b47bfd) return;   // not zlib 1.2.8 prologue

    void* tracer = dlsym(RTLD_DEFAULT,
                          "__macapk_arm64_inflate_tracepoint");
    if (!tracer) {
        std::fprintf(stderr,
            "[arm64-native] %s: inflate tracepoint shim missing\n",
            dbg_name_);
        return;
    }

    if (!trampoline_) {
        void* hint = static_cast<void*>(base_ + mapped_size_);
        void* tp = mmap(hint, 4096, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
        if (tp == MAP_FAILED) {
            std::fprintf(stderr,
                "[arm64-native] %s: inflate tracepoint mmap failed\n",
                dbg_name_);
            return;
        }
        trampoline_ = static_cast<uint8_t*>(tp);
        trampoline_used_ = 0;
    }

    uintptr_t trampoline_pc = uintptr_t(trampoline_) + trampoline_used_;

    // Reachability: `b imm26` covers ±128 MB. We need both ends to
    // reach: the patch site jumping into the trampoline, and the
    // trampoline's final `b inflate+4` jumping back.
    auto reachable = [](uintptr_t a, uintptr_t b) -> bool {
        int64_t d = int64_t(b) - int64_t(a);
        return d >= -(int64_t)(128 * 1024 * 1024)
            && d <  (int64_t)(128 * 1024 * 1024);
    };
    if (!reachable(inflate_pc, trampoline_pc)
        || !reachable(trampoline_pc + 100, inflate_pc + 4)) {
        std::fprintf(stderr,
            "[arm64-native] %s: inflate tracepoint out of branch range\n",
            dbg_name_);
        return;
    }

    // Trampoline layout (28 × 4 bytes code + 8 bytes addr = 120 B):
    //   +000  stp  x29, x30, [sp, #-0x100]!   ; alloc 256-B frame
    //   +004  mov  x29, sp
    //   +008  stp  x0,  x1,  [sp, #0x10]      ; save caller args
    //   +00c  stp  x2,  x3,  [sp, #0x20]      ; save caller-saved
    //   +010  stp  x4,  x5,  [sp, #0x30]
    //   +014  stp  x6,  x7,  [sp, #0x40]
    //   +018  stp  x8,  x9,  [sp, #0x50]
    //   +01c  stp  x10, x11, [sp, #0x60]
    //   +020  stp  x12, x13, [sp, #0x70]
    //   +024  stp  x14, x15, [sp, #0x80]
    //   +028  stp  x16, x17, [sp, #0x90]
    //   +02c  ldr  x0,  [sp, #0x10]           ; reload x0 = strm
    //   +030  ldr  x16, .Ltracer              ; PC-rel literal at +0x68
    //   +034  blr  x16                         ; call tracer (clobbers lr/x16/x17/x0)
    //   +038  ldp  x16, x17, [sp, #0x90]      ; restore
    //   +03c  ldp  x14, x15, [sp, #0x80]
    //   +040  ldp  x12, x13, [sp, #0x70]
    //   +044  ldp  x10, x11, [sp, #0x60]
    //   +048  ldp  x8,  x9,  [sp, #0x50]
    //   +04c  ldp  x6,  x7,  [sp, #0x40]
    //   +050  ldp  x4,  x5,  [sp, #0x30]
    //   +054  ldp  x2,  x3,  [sp, #0x20]
    //   +058  ldp  x0,  x1,  [sp, #0x10]
    //   +05c  ldp  x29, x30, [sp], #0x100     ; restore + dealloc
    //   +060  stp  x29, x30, [sp, #-0xc0]!    ; DISPLACED inflate's 1st
    //   +064  b    inflate+4                    ; resume inflate body
    //   +068  .quad <tracer>                    ; literal pool
    //
    // The `ldr x16, .Ltracer` literal-load offset is (0x68 - 0x30)/4 = 14
    // instructions past the LDR's PC → imm19 = 14. Encoding 0x580001d0.
    //
    // The `b inflate+4` displacement is computed at emit time via encode_b
    // because the trampoline's address depends on where mmap placed it.
    uint32_t code[28];
    code[0]  = 0xa9b07bfd;  // stp  x29, x30, [sp, #-0x100]!
    code[1]  = 0x910003fd;  // mov  x29, sp
    code[2]  = 0xa90107e0;  // stp  x0,  x1,  [sp, #0x10]
    code[3]  = 0xa9020fe2;  // stp  x2,  x3,  [sp, #0x20]
    code[4]  = 0xa90317e4;  // stp  x4,  x5,  [sp, #0x30]
    code[5]  = 0xa9041fe6;  // stp  x6,  x7,  [sp, #0x40]
    code[6]  = 0xa90527e8;  // stp  x8,  x9,  [sp, #0x50]
    code[7]  = 0xa9062fea;  // stp  x10, x11, [sp, #0x60]
    code[8]  = 0xa90737ec;  // stp  x12, x13, [sp, #0x70]
    code[9]  = 0xa9083fee;  // stp  x14, x15, [sp, #0x80]
    code[10] = 0xa90947f0;  // stp  x16, x17, [sp, #0x90]
    code[11] = 0xf9400be0;  // ldr  x0,  [sp, #0x10]  (imm12=2, scaled *8)
    code[12] = 0x580001d0;  // ldr  x16, .Ltracer  (PC+0x38)
    code[13] = 0xd63f0200;  // blr  x16
    code[14] = 0xa94947f0;  // ldp  x16, x17, [sp, #0x90]
    code[15] = 0xa9483fee;  // ldp  x14, x15, [sp, #0x80]
    code[16] = 0xa94737ec;  // ldp  x12, x13, [sp, #0x70]
    code[17] = 0xa9462fea;  // ldp  x10, x11, [sp, #0x60]
    code[18] = 0xa94527e8;  // ldp  x8,  x9,  [sp, #0x50]
    code[19] = 0xa9441fe6;  // ldp  x6,  x7,  [sp, #0x40]
    code[20] = 0xa94317e4;  // ldp  x4,  x5,  [sp, #0x30]
    code[21] = 0xa9420fe2;  // ldp  x2,  x3,  [sp, #0x20]
    code[22] = 0xa94107e0;  // ldp  x0,  x1,  [sp, #0x10]
    code[23] = 0xa8d07bfd;  // ldp  x29, x30, [sp], #0x100
    code[24] = 0xa9b47bfd;  // stp  x29, x30, [sp, #-0xc0]!  (displaced)
    code[25] = encode_b(trampoline_pc + 25*4, inflate_pc + 4);
    code[26] = uint32_t(uintptr_t(tracer));         // .Ltracer low
    code[27] = uint32_t(uintptr_t(tracer) >> 32);   // .Ltracer high

    std::memcpy(trampoline_ + trampoline_used_, code, sizeof(code));
    trampoline_used_ += sizeof(code);

    // Patch inflate's first instruction with `b trampoline`.
    uint32_t bword = encode_b(inflate_pc, trampoline_pc);
    std::memcpy(reinterpret_cast<void*>(inflate_pc), &bword, sizeof(bword));

    // Trampoline mprotect happens centrally in Image::load() after
    // all patch functions have written into it. Don't lock it RX
    // here -- a subsequent patch function (e.g. the inflate_table
    // tracer) needs to keep writing.
    std::fprintf(stderr,
        "[arm64-native] %s: zlib inflate() tracepoint installed at %p "
        "(trampoline at %p)\n",
        dbg_name_, (void*)inflate_pc, (void*)trampoline_pc);
}

// ---------------------------------------------------------------------------
// zlib `inflate_table()` entry tracepoint
//
// Companion to apply_zlib_inflate_tracepoint(). The inflate() entry trace
// proved state is clean at every inflate() boundary, so the libfmod-UAF
// scribble must hit during inflate's body. inflate_table is the static
// helper inside inflate.c that builds the Huffman code tables; its
// bit-reverse scatter loop at libcocos+0xa0584c is one of the dominant
// crash sites (canonical "huff=0x80000002 → lens[i]>=16" SIGBUS pattern).
//
// inflate_table takes individual pointers, NOT a struct inflate_state*:
//   int inflate_table(codetype type, unsigned short *lens, unsigned codes,
//                     code **table, unsigned *bits, unsigned short *work)
// AAPCS64: x0=type, x1=lens, x2=codes, x3=table, x4=bits, x5=work.
//
// Distinctive entry signature (verified against this libcocos build):
//   a05318:  sub  sp, sp, #0xa0       ; 0xd10283ff
//   a0531c:  mov  x6, #0               ; 0xd2800006
//   a05320:  add  x9, sp, #0x60        ; 0x910183e9
//   a05324:  stp  x19, x20, [sp]       ; 0xa90053f3
//   a05328:  stp  x21, x22, [sp, #0x10]; 0xa9015bf5
// 5-instruction (20-byte) signature anchor.
//
// The tracer scans `lens[0..codes-1]` for any value >= 16 (deflate
// code lengths must fit in 4 bits, so 0..15 is the legal range).
// Any >= 16 means corruption arrived BEFORE inflate_table -- the
// scribble happened between inflate's call to inflate_table and the
// bit-reverse loop in inflate_table's body where the crash surfaces.
// If lens looks legal here, the scribble races inflate_table's own
// tight body and the racing thread's working set must overlap with
// the lens[] or codes[] arrays during the bit-reverse pass.
void Image::apply_zlib_inflate_table_tracepoint() {
    static const uint8_t kSig[20] = {
        0xff, 0x83, 0x02, 0xd1,   // sub sp, sp, #0xa0
        0x06, 0x00, 0x80, 0xd2,   // mov x6, #0
        0xe9, 0x83, 0x01, 0x91,   // add x9, sp, #0x60
        0xf3, 0x53, 0x00, 0xa9,   // stp x19, x20, [sp]
        0xf5, 0x5b, 0x01, 0xa9,   // stp x21, x22, [sp, #0x10]
    };
    uintptr_t inflate_table_pc = 0;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < sizeof(kSig)) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - sizeof(kSig);
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            if (std::memcmp(p, kSig, sizeof(kSig)) == 0) {
                inflate_table_pc = uintptr_t(p);
                break;
            }
        }
        if (inflate_table_pc) break;
    }
    if (!inflate_table_pc) return;

    void* tracer = dlsym(RTLD_DEFAULT,
                          "__macapk_arm64_inflate_table_tracepoint");
    if (!tracer) {
        std::fprintf(stderr,
            "[arm64-native] %s: inflate_table tracepoint shim missing\n",
            dbg_name_);
        return;
    }

    if (!trampoline_) {
        void* hint = static_cast<void*>(base_ + mapped_size_);
        void* tp = mmap(hint, 4096, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
        if (tp == MAP_FAILED) {
            std::fprintf(stderr,
                "[arm64-native] %s: inflate_table tracepoint mmap failed\n",
                dbg_name_);
            return;
        }
        trampoline_ = static_cast<uint8_t*>(tp);
        trampoline_used_ = 0;
    }

    uintptr_t trampoline_pc = uintptr_t(trampoline_) + trampoline_used_;

    auto reachable = [](uintptr_t a, uintptr_t b) -> bool {
        int64_t d = int64_t(b) - int64_t(a);
        return d >= -(int64_t)(128 * 1024 * 1024)
            && d <  (int64_t)(128 * 1024 * 1024);
    };
    if (!reachable(inflate_table_pc, trampoline_pc)
        || !reachable(trampoline_pc + 100, inflate_table_pc + 4)) {
        std::fprintf(stderr,
            "[arm64-native] %s: inflate_table tracepoint out of range\n",
            dbg_name_);
        return;
    }

    // Same trampoline structure as the inflate() tracer, only the
    // displaced first instruction differs (sub sp, sp, #0xa0 here vs
    // stp x29, x30, [sp, #-0xc0]! for inflate).
    uint32_t code[28];
    code[0]  = 0xa9b07bfd;  // stp x29, x30, [sp, #-0x100]!
    code[1]  = 0x910003fd;  // mov x29, sp
    code[2]  = 0xa90107e0;  // stp x0, x1, [sp, #0x10]
    code[3]  = 0xa9020fe2;  // stp x2, x3, [sp, #0x20]
    code[4]  = 0xa90317e4;  // stp x4, x5, [sp, #0x30]
    code[5]  = 0xa9041fe6;  // stp x6, x7, [sp, #0x40]
    code[6]  = 0xa90527e8;  // stp x8, x9, [sp, #0x50]
    code[7]  = 0xa9062fea;  // stp x10, x11, [sp, #0x60]
    code[8]  = 0xa90737ec;  // stp x12, x13, [sp, #0x70]
    code[9]  = 0xa9083fee;  // stp x14, x15, [sp, #0x80]
    code[10] = 0xa90947f0;  // stp x16, x17, [sp, #0x90]
    // Reload x0..x5 (the 6 inflate_table args) for the tracer call.
    // The tracer has the same C signature so AAPCS64 puts them in
    // x0..x5 directly.
    code[11] = 0xa94107e0;  // ldp x0, x1, [sp, #0x10]
    code[12] = 0x580001d0;  // ldr x16, [pc, #56]  (.Ltracer)
    code[13] = 0xa9420fe2;  // ldp x2, x3, [sp, #0x20]
    code[14] = 0xa94317e4;  // ldp x4, x5, [sp, #0x30]
    code[15] = 0xd63f0200;  // blr x16
    // Restore (note: x0..x5 are restored by the ldp pairs below).
    code[16] = 0xa94947f0;  // ldp x16, x17, [sp, #0x90]
    code[17] = 0xa9483fee;  // ldp x14, x15, [sp, #0x80]
    code[18] = 0xa94737ec;  // ldp x12, x13, [sp, #0x70]
    code[19] = 0xa9462fea;  // ldp x10, x11, [sp, #0x60]
    code[20] = 0xa94527e8;  // ldp x8, x9, [sp, #0x50]
    code[21] = 0xa9441fe6;  // ldp x6, x7, [sp, #0x40]
    code[22] = 0xa94317e4;  // ldp x4, x5, [sp, #0x30]
    code[23] = 0xa9420fe2;  // ldp x2, x3, [sp, #0x20]
    // Final pre-jump: restore x0/x1 and pop x29/x30 in two adjacent
    // instrs so the next two instructions (displaced + b back) execute
    // with the original caller's x29/x30 and the right x0..x5.
    code[24] = 0xa94107e0;  // ldp x0, x1, [sp, #0x10]
    code[25] = 0xa8d07bfd;  // ldp x29, x30, [sp], #0x100
    // Displaced inflate_table prologue + branch back.
    code[26] = 0xd10283ff;  // sub sp, sp, #0xa0  (displaced)
    code[27] = encode_b(trampoline_pc + 27*4, inflate_table_pc + 4);
    // NOTE: no literal pool stored as 2 uint32 here; we instead reuse
    // the same layout as inflate() trampoline but put the .Ltracer
    // address WHERE the existing one expects -- at offset 0x68 from
    // the LDR literal (instr at offset 0x30, literal at 0x68 = 0x30 +
    // 56 = 14 instrs ahead).
    //
    // For this trampoline the LDR literal is at code[12] (offset 0x30),
    // and we have only 28 instrs (code[0..27]). The literal pool slot
    // must go AT code[26] + (56/4) = code[26 + 14] = code[40] which is
    // OUT OF RANGE. Need to either:
    //   (a) recompute the LDR literal offset to point to a later slot
    //   (b) shrink the trampoline so the literal lands within range
    //
    // The simplest fix: move the literal pool to immediately AFTER
    // code[27], and recompute the LDR literal offset.
    // After code[27], we need two 32-bit slots for the 64-bit address.
    // Total layout: 28 instr (112 B) + 8 B literal = 120 B.
    // LDR literal at offset 0x30 (= 48). Literal at offset 112 (= 0x70).
    // Distance = 112 - 48 = 64 = 16 instructions. imm19 = 16.
    // Encoding: 0x58000000 | (16 << 5) | 16 = 0x58000000 | 0x200 | 16
    //         = 0x58000210.
    code[12] = 0x58000210;  // re-encode ldr x16, [pc, #64] (= .Ltracer at +0x70)
    uint32_t tracer_lo = uint32_t(uintptr_t(tracer));
    uint32_t tracer_hi = uint32_t(uintptr_t(tracer) >> 32);

    // 30 words total (28 instrs + 2 for literal).
    uint32_t full[30];
    std::memcpy(full, code, sizeof(code));
    full[28] = tracer_lo;
    full[29] = tracer_hi;

    std::memcpy(trampoline_ + trampoline_used_, full, sizeof(full));
    trampoline_used_ += sizeof(full);

    uint32_t bword = encode_b(inflate_table_pc, trampoline_pc);
    std::memcpy(reinterpret_cast<void*>(inflate_table_pc), &bword,
                sizeof(bword));

    // Trampoline mprotect happens centrally in Image::load() after
    // all patch functions have written.
    std::fprintf(stderr,
        "[arm64-native] %s: zlib inflate_table() tracepoint installed at %p "
        "(trampoline at %p)\n",
        dbg_name_, (void*)inflate_table_pc, (void*)trampoline_pc);
}

// ---------------------------------------------------------------------------
// libcocos's statically-linked zlib 1.2.8 -> macOS libz.dylib redirect
//
// 2026-05-17 hardware-watchpoint experiment proved the inflate-state
// corruption is INTRA-THREAD -- no other thread writes to the most-
// corruption-relevant inflate_state fields before the crash. The
// crashes (libcocos+0xa0584c inflate_table bit-reverse SIGBUS,
// libcocos+0xa019e8 inflate_fast distance-copy overflow) happen on
// the main thread itself inside zlib's static helper bodies. State
// is clean at every inflate() and inflate_table() entry (zero
// CORRUPT_MODE / CORRUPT_LENS hits across the session).
//
// Combined with prior research findings (no public reports of
// inflate_fast crash on macOS arm64; Apple M1 has the same weak
// memory model as Android arm64; FMOD's known UAFs ruled out),
// the most likely remaining cause is a subtle compiler-emitted
// arithmetic edge case in cocos's specific build of zlib 1.2.8 that
// triggers under Apple Silicon register-allocation / overflow
// semantics.
//
// The cleanest structural fix: bypass cocos's zlib entirely and use
// macOS's libz.dylib (a well-tested zlib 1.2.x build that's known
// to work on Apple Silicon). zlib's z_stream ABI has been stable
// for 20+ years, so cocos can use macOS's inflate / inflateInit2_ /
// inflateEnd transparently as long as ALL three are redirected
// consistently (the state pointer macOS allocates uses macOS's
// internal inflate_state layout; cocos never touches state directly
// so the size mismatch doesn't matter).
//
// We redirect the 7 inflate-family entry points. Each gets a
// 12-byte in-place patch that loads the macOS libz function address
// via a PC-relative literal and branches to it:
//
//   +00  ldr  x16, [pc, #4]    ; load 8-byte address that follows
//   +04  br   x16              ; tail-jump
//   +08  .quad <macos_libz_fn> ; address of macOS's libz equivalent
//
// 12 bytes total. x16 is intra-procedure-call scratch (caller-saved,
// AAPCS64), so clobbering it is safe even from a function entry.
//
// We deliberately do NOT redirect deflate / crc32 / adler32 / etc.
// Those have inline assembly optimizations in cocos's build and
// haven't been implicated in the crash. Minimal-blast-radius rule.
void Image::apply_libcocos_zlib_redirect() {
    // Open macOS's libz. It's in the dyld shared cache but
    // dlopen("libz.dylib") resolves through the dyld lookup paths.
    void* libz = dlopen("libz.dylib", RTLD_NOW | RTLD_GLOBAL);
    if (!libz) libz = dlopen("/usr/lib/libz.1.dylib", RTLD_NOW | RTLD_GLOBAL);
    if (!libz) {
        std::fprintf(stderr,
            "[arm64-native] %s: libz.dylib not found; "
            "skipping cocos zlib redirect\n", dbg_name_);
        return;
    }

    static const char* kSyms[] = {
        "inflate",
        "inflateInit_",
        "inflateInit2_",
        "inflateEnd",
        "inflateReset",
        "inflateReset2",
        "inflateResetKeep",
    };
    int patched = 0;
    for (const char* sym : kSyms) {
        void* cocos_fn = lookup(sym);  // libcocos's exported impl
        void* libz_fn  = dlsym(libz, sym);  // macOS libz's impl
        if (!cocos_fn) continue;       // not in this image; skip
        if (!libz_fn) {
            std::fprintf(stderr,
                "[arm64-native] %s: libz missing '%s'; skipping\n",
                dbg_name_, sym);
            continue;
        }
        // 12-byte redirect: ldr x16, [pc, #4] ; br x16 ; .quad libz_fn
        // ldr x16, [pc, #4]   imm19=2 (8 bytes ahead), Rt=16
        //   = 0x58000000 | (2 << 5) | 16 = 0x58000050
        // br x16              = 0xd61f0200
        uint32_t code[3];
        code[0] = 0x58000050u;
        code[1] = 0xd61f0200u;
        // bytes 8..15: 64-bit address (split as 2 uint32)
        uint64_t addr = reinterpret_cast<uint64_t>(libz_fn);
        code[2] = uint32_t(addr & 0xffffffffu);
        // We need a 4th 32-bit word for the high half; copy via byte
        // memcpy at offset 12.
        std::memcpy(cocos_fn, code, sizeof(code));
        uint32_t hi = uint32_t(addr >> 32);
        std::memcpy(static_cast<char*>(cocos_fn) + 12, &hi, sizeof(hi));
        std::fprintf(stderr,
            "[arm64-native] %s: zlib %s -> macOS libz %p (patched at %p)\n",
            dbg_name_, sym, libz_fn, cocos_fn);
        ++patched;
    }
    if (patched) {
        std::fprintf(stderr,
            "[arm64-native] %s: redirected %d zlib symbol(s) to macOS libz\n",
            dbg_name_, patched);
    }
}


// Engine-generic NULL-safety patch for cocos2d-x v2 CCNode::addChild(child, z).
//
// Cocos2d-x v2's `void CCNode::addChild(CCNode* child, int zOrder)` is
// (per its release-build behavior with CCAssert reduced to ((void)0)):
//
//   void CCNode::addChild(CCNode* child, int zOrder) {
//       CCAssert(child != NULL, "Argument must be non-nil");
//       this->addChild(child, zOrder, child->m_iTag);
//   }
//
// In RELEASE builds CCAssert is a no-op, but the macro-expanded asm
// STILL falls through to `child->m_iTag` -- a NULL deref. HCR's
// `fsassert` variant just logs and returns from the assert macro
// without aborting, so the same fall-through to NULL deref happens.
//
// Real symptom we hit (HCR 1.43 arm64, frame 2-3 of nativeRender):
// some setup chain hands a NULL CCNode* to addChild (likely a
// downstream miss in our JNI bridge's FP-arg handling, or a JSON
// asset that didn't validate). The assert prints, then the very
// next instruction LDRs from offset 0xec of NULL and SIGSEGVs.
//
// Engine-generic fix: at function entry, replace the cocos2d-x
// `cbnz x1, +0x30` (which skips the assert when child != NULL) with
// a `cbz x1, +0x4c` (early-return when child == NULL). Net effect:
// NULL children silently get dropped instead of crashing the process.
// This is what cocos2d-x v3+ does explicitly (`if (!child) return;`).
//
// Identification anchor (compiled by AOSP NDK clang against the
// cocos2d-x v2 source -- stable across HCR / GD-class / any v2 game):
//
//   +0x00:  cbnz  x1, +0x30          -- 81 01 00 b5
//   +0x14:  mov   w0, #0x6           -- c0 00 80 52 (= ANDROID_LOG_FATAL)
//   +0x2c:  bl    <__android_log_print>
//                                    -- ?? ?? ?? 97 (BL with positive imm26)
//   +0x30:  ldr   x0, [x20]          -- 80 02 40 f9
//
// Patch: rewrite the cbnz to cbz x1, +0x4c (= 0xb4000261, bytes
// 61 02 00 b4). +0x4c lands on the function's stp/ldp epilogue
// (the post-blr at +0x48 + the standard ldp x21,x30 unwind).
//
// Idempotent: writing the same patch twice is a no-op (we compare
// before writing). Safe across multiple image loads.
size_t Image::apply_cocos2dx_v2_addchild_null_safety() {
    static const uint8_t kCbnzX1_30[4]  = { 0x81, 0x01, 0x00, 0xb5 };
    static const uint8_t kCbzX1_4c[4]   = { 0x61, 0x02, 0x00, 0xb4 };
    // Anchors expected at fixed offsets from the cbnz.
    static const uint8_t kMovW0_6[4]    = { 0xc0, 0x00, 0x80, 0x52 };
    static const uint8_t kLdrX0_X20[4]  = { 0x80, 0x02, 0x40, 0xf9 };

    size_t patches = 0;
    for (const auto& s : segments_) {
        if (!(s.prot & PROT_EXEC)) continue;
        if (s.memsz < 0x40) continue;
        const uint8_t* lo = reinterpret_cast<const uint8_t*>(s.vaddr);
        const uint8_t* hi = lo + s.memsz - 0x34;  // need +0x30+4 ahead
        for (const uint8_t* p = lo; p <= hi; p += 4) {
            if (std::memcmp(p, kCbnzX1_30, 4) != 0) continue;
            if (std::memcmp(p + 0x14, kMovW0_6, 4) != 0) continue;
            // The bl at +0x2c has variable target (positive imm26).
            // Verify bits 31:26 == 0b100101 (BL opcode, sign bit 0).
            if ((p[0x2c + 3] & 0xfc) != 0x94) continue;
            if (std::memcmp(p + 0x30, kLdrX0_X20, 4) != 0) continue;
            // Match. Patch the cbnz.
            std::memcpy(const_cast<uint8_t*>(p), kCbzX1_4c, 4);
            std::fprintf(stderr,
                "[arm64-native] %s: cocos2d-x addChild NULL-safety patch at %p\n",
                dbg_name_, p);
            ++patches;
        }
    }
    if (patches) {
        std::fprintf(stderr,
            "[arm64-native] %s: patched %zu cocos2d-x addChild site(s) for NULL safety\n",
            dbg_name_, patches);
    }
    return patches;
}

void Image::install_cocos2dx_searchpath() {
    using SharedFn  = void* (*)();
    using AddPathFn = void  (*)(void*, const char*);

    // Two load-time hooks used to live here. BOTH are now retired, because
    // a device runs neither: when a library loads, ART only dlopens it (the
    // Bionic linker runs .init_array) and then calls JNI_OnLoad. It never
    // calls any other JNI export on its own (art runtime/jni/java_vm_ext.cc
    // LoadNativeLibrary, the FindSymbol("JNI_OnLoad") block). Everything else
    // is the app's own Java calling in.
    //
    // (1) Cocos2dxActivity_nativeSetPaths -- RETIRED behind
    //     MACAPK_COCOS_SETPATHS_FROM_APP (default ON = not called here).
    //
    //     What it was for: e1aecbf2 (2026-05-17) called the JNI export
    //     directly with the runtime's APK path, on the premise that "HCR's
    //     MainActivity doesn't reliably reach setPackageName, so s_pZipFile
    //     stays NULL". The premise is gone, and it was partly wrong:
    //       * There is no s_pZipFile in these builds. In HCR 1.43 arm64
    //         (libgame.so md5 ce61d021...) nativeSetPaths @0x29aa14 is
    //         GetStringUTFChars (env+0x548) -> sharedFileUtils @0x298644
    //         (lazily news a 1-byte object into .bss 0x50da98) ->
    //         setResourcePath @0x2986a0 (keeps the string only if it
    //         contains ".apk", assigning std::string s_strResourcePath at
    //         .bss 0x50da90) -> ReleaseStringUTFChars (env+0x550).
    //         getFileData @0x298868 reads [0x50da90] on EVERY call. Nothing
    //         opens a zip eagerly, so the only requirement is that the string
    //         is set before the first relative asset read. That is upstream
    //         cocos2d-x 2.0 CCFileUtils.cpp's setResourcePath/getFileData too.
    //       * The app delivers the path itself, as on a device. In HCR 1.43
    //         and 1.68.1 (converted classes.jar, javap) MainActivity.onCreate
    //         calls Cocos2dxActivity.setPackageName after detectOpenGLES20()
    //         (bci 215 / 164), and setPackageName does
    //         getApplicationInfo(pkg,0).sourceDir -> Log.w("apk path", ..)
    //         -> nativeSetPaths(sourceDir) (bci 74 / 86 / 91). Measured on
    //         the current layer (sweep logs of 2026-09-26/27, both apps):
    //         synthetic call, then the app's own "[W] apk path: <same
    //         base.apk>", then "[D] cocos2d-x: nativeInit(", with no asset
    //         read in between. So the synthetic value was overwritten
    //         byte-identically before its first consumer and had no effect.
    //       * It was unfaithful on its own terms: on HCR 1.68.1 it called a
    //         JNI export BEFORE that library's JNI_OnLoad, which ART never
    //         does. And the diagnostic readback that followed it dereferenced
    //         the HCR-1.43-only vaddr base_+0x50da90/0x50da98 on ANY image
    //         exporting the symbol, with no identification gate. On 1.68.1
    //         that address is inside .text (0x2cdc30..0x78fd40): the logged
    //         "globals" were the instruction words `b #8; ldr x1,[x0,#0x48];
    //         add x0,sp,#0x20; bl ..`. That readback is DELETED on both
    //         paths (it only printed; it changed nothing in the guest).
    //     MACAPK_COCOS_SETPATHS_FROM_APP=0 restores the synthesized call
    //     (same JNIEnv minting, same argument) for a bisect.
    //     Falsifier: with the default, each HCR run log shows exactly one
    //     "[W] apk path: ..base.apk" BEFORE "[D] cocos2d-x: nativeInit(", and
    //     zero parseConfigFile / CCTextureCache addImage fsasserts (the
    //     signature of an empty s_strResourcePath). If the app's own line is
    //     missing or late, that is a FRAMEWORK gap in MainActivity.onCreate
    //     to fix there, not a reason to re-enable this.
    //     REMOVAL CONDITION: after one clean sweep cycle with the default,
    //     delete the =0 path and this whole function, its declaration, and
    //     its call in load_common (after init_array). Gate:
    //     debug/geh_cocos-nativesetpaths-synth-call_gate.sh. Evidence:
    //     docs/cocos-nativesetpaths-retired_2026-09-27.md.
    //
    // (2) CCFileUtils::addSearchPath -- RETIRED in v1.0.1587, see below.

    // ---- (1) nativeSetPaths: RETIRED behind MACAPK_COCOS_SETPATHS_FROM_APP ----
    static const bool setpaths_from_app = [] {
        const char* v = std::getenv("MACAPK_COCOS_SETPATHS_FROM_APP");
        return !(v && v[0] == '0');
    }();
    using NativeSetPathsFn =
        void (*)(void* /*JNIEnv**/, void* /*jclass*/, void* /*jstring*/);
    // cocos2d-x 2.1 exports Cocos2dxActivity_nativeSetPaths (HCR 1.43,
    // 1.68.1) and uses only the JNIEnv it is handed, which is why the =0 path
    // could call it from here without crashing -- NOT a reason to call it: a
    // device leaves it to setPackageName(), see (1). cocos2d-x 2.2.x exports
    // Cocos2dxHelper_nativeSetApkPath (Geometry Dash Lite) instead -- do NOT
    // call that one here: it reads the JavaVM cached by the library's own
    // JNI_OnLoad, which has not run yet at load time (measured 2026-09-19:
    // a NULL JavaVM -> null-recovery cascade -> std::logic_error abort). The
    // app's own Cocos2dxHelper.init calls it after JNI_OnLoad, exactly as on
    // a device, and nothing here opens the engine's zip before that any more
    // (see (2) below).
    auto* native_set_paths = reinterpret_cast<NativeSetPathsFn>(
        lookup("Java_org_cocos2dx_lib_Cocos2dxActivity_nativeSetPaths"));
    if (native_set_paths && setpaths_from_app) {
        // The default. One line, so a run log says which path it took. The
        // wording deliberately does NOT contain "nativeSetPaths(", which is
        // what the falsifier greps for to prove the synthetic call is gone.
        std::fprintf(stderr,
            "[arm64-native] %s: cocos2d-x Cocos2dxActivity path setter left "
            "to the app's own setPackageName (MACAPK_COCOS_SETPATHS_FROM_APP=0 "
            "restores the load-time call)\n", dbg_name_);
    } else if (native_set_paths) {
        // Need an APK path and a JNIEnv* to call the JNI entry. The
        // runtime captures the real APK path on boot; we read it back
        // via dlsym so the link order doesn't matter.
        typedef const char* (*get_apk_fn)();
        get_apk_fn get_apk = reinterpret_cast<get_apk_fn>(
            dlsym(RTLD_DEFAULT, "__macapk_get_runtime_apk_path"));
        const char* apk = get_apk ? get_apk() : nullptr;
        if (apk && apk[0]) {
            // Need a JNIEnv (any thread's; we use the main one).
            typedef void* (*get_vm_fn_t)();
            get_vm_fn_t get_vm = reinterpret_cast<get_vm_fn_t>(
                dlsym(RTLD_DEFAULT, "__macapk_get_javavm"));
            void* vm_raw = get_vm ? get_vm() : nullptr;
            if (vm_raw) {
                // GetEnv via vm's vtable. JavaVM* is JavaVM_*, first
                // field is const JNIInvokeInterface_*. We use minimal
                // function pointer types so we don't have to include
                // jni.h here.
                using GetEnvFn = int(*)(void* /*vm*/, void** /*env*/,
                                         int /*version*/);
                void** vm_vtab = *reinterpret_cast<void***>(vm_raw);
                // GetEnv is slot 6 in JNIInvokeInterface_ (slots 0..2
                // reserved, 3=DestroyJavaVM, 4=AttachCurrentThread,
                // 5=DetachCurrentThread, 6=GetEnv,
                // 7=AttachCurrentThreadAsDaemon).
                auto get_env = reinterpret_cast<GetEnvFn>(vm_vtab[6]);
                void* env_raw = nullptr;
                if (get_env(vm_raw, &env_raw, 0x00010006) == 0 && env_raw) {
                    // Build a jstring from the apk path via env's
                    // NewStringUTF -- slot 167 in JNINativeInterface_
                    // (offset 167*8 = 0x538).
                    using NewStringUTFFn = void*(*)(void*, const char*);
                    void** env_vtab = *reinterpret_cast<void***>(env_raw);
                    // Field index reference:
                    //   NewStringUTF       = slot 167 (offset 0x538)
                    //   DeleteLocalRef     = slot 23  (offset 0xb8)
                    auto new_str = reinterpret_cast<NewStringUTFFn>(
                        env_vtab[167]);
                    using DeleteLocalRefFn = void(*)(void*, void*);
                    auto del_ref = reinterpret_cast<DeleteLocalRefFn>(
                        env_vtab[23]);
                    void* jstr = new_str(env_raw, apk);
                    if (jstr) {
                        native_set_paths(env_raw, nullptr, jstr);
                        // The old "-- s_pZipFile initialized" text was
                        // false (the export assigns a std::string; nothing
                        // opens a zip), and the per-image readback of
                        // base_+0x50da90/0x50da98 that followed is gone:
                        // see (1) above.
                        std::fprintf(stderr,
                            "[arm64-native] %s: nativeSetPaths(\"%s\") "
                            "called -- resource path set "
                            "(MACAPK_COCOS_SETPATHS_FROM_APP=0)\n",
                            dbg_name_, apk);
                        del_ref(env_raw, jstr);
                    }
                }
            }
        }
    }

    // ---- (2) addSearchPath: RETIRED (v1.0.1587) ----
    // This used to call CCFileUtils::sharedFileUtils()->addSearchPath(
    // "<root>/data/data/<pkg>/") right here, during the load. Two things a
    // device never does: it CONSTRUCTED the engine's file utils before the
    // app's Java had set the apk path (2.2.x opens its ZipFile in that
    // constructor -> an empty-path open, dead zip for the whole run), and it
    // put our data dir into the engine's asset search list, so every asset
    // the zip could not serve was probed as a host-absolute path under
    // data/data (GD Lite's GJ_LaunchSheet-hd.png x147). Save files never
    // needed it: cocos opens them by absolute path via getWritablePath(),
    // which the shim remaps. Measured 2026-09-19 (docs/assets-from-apk-per-
    // engine, Current_State v1.0.1587). Search paths are the engine's own.
    (void)SharedFn{}; (void)AddPathFn{};
}

void* Image::lookup(const char* name) const {
    // t3-syms (2026-07-24): on-demand hash resolution -- see the block
    // comment above gnu_hash_lookup() for the full citation trail. This is
    // the SOLE symbol-resolution path for every Stage-6 arm64 app: the
    // per-export trampoline population loop in the wrapper's constructor
    // (arm64_pack.cpp / arm64_macho_emit.cpp), the cross-image DT_NEEDED-
    // style walk in resolve_external(), and the two direct by-name lookups
    // (apply_zlib_inflate_tracepoint, install_il2cpp_exc_logger) all call
    // through here.
    if (gnu_hash_) {
        if (void* p = gnu_hash_lookup(name)) return p;
        return gnu_hash_low_region_scan(name);
    }
    if (sysv_hash_) return sysv_hash_lookup(name);
    // Neither DT_GNU_HASH nor DT_HASH: see exports_fallback_'s comment --
    // not producible by any real Android/NDK linker, kept only so a
    // malformed/exotic guest still loads instead of hard-failing.
    auto it = exports_fallback_.find(name);
    return it == exports_fallback_.end() ? nullptr : it->second;
}

// Global registry of every Image we've successfully loaded so far.
// Used by Image::resolve_external to look up symbols defined in
// previously-loaded native libraries (the Android linker's behaviour
// for DT_NEEDED dependencies, but we can't use dlsym(RTLD_DEFAULT)
// for these because the Mach-O wrappers only export a small set of
// trampolines, not the underlying ELF's full export table). The
// registry append order matches the dlopen order, which AppClassLoader
// pre-arranges to match the DT_NEEDED dependency order via
// ElfReader.readDtNeeded().
//
// Lookups are done linearly (typically <10 entries) so std::vector
// is plenty.
std::vector<Image*>& loaded_images() {
    static std::vector<Image*> v;
    return v;
}

// Is any arm64 GUEST image loaded? The only honest ABI discriminator available to
// bionic_shim.cpp, which needs it to emit /proc/self/auxv at the right entry WIDTH: an arm64
// guest parses 64-bit (type,val) pairs, an ARM32 one parses 32-bit. Returns 0 before the first
// guest ELF is mapped, which is also the right answer for a pure-Java app.
extern "C" int __macapk_arm64_guest_present(void) {
    return loaded_images().empty() ? 0 : 1;
}

// Which Stage-6 guest image holds `pc`, and at what image offset? Guest images are
// mapped by this loader, not dyld, so dladdr() cannot name them. NOT for signal
// context (it walks a std::vector); bionic_shim.cpp's fault classifiers call it
// after unwinding. Returns the image's soname, or nullptr.
// The Stage-6 image mapped at exactly `base` (a pairip resolver program names its
// target image this way), or nullptr.
static Image* macapk_image_at_base(uint64_t base) {
    for (Image* im : loaded_images())
        if (im && reinterpret_cast<uint64_t>(im->base()) == base) return im;
    return nullptr;
}
// Did the harvested import table fill every pairip hole of the image at `base`?
// -1 = no such image, 0 = no, 1 = yes.
extern "C" int __macapk_pairip_image_imports_complete(uint64_t base) {
    Image* im = macapk_image_at_base(base);
    return im ? (im->pairip_imports_complete() ? 1 : 0) : -1;
}
// Does a pairip RESOLVER program aimed at the image at `base` still have anything to
// do? 0 = no: the image has no stripped pairip imports at all, or the harvested import
// table already filled every one. 1 = yes, or unknown (no image mapped at `base`).
// See the resolver skip in native_methods.cpp's pairip gate for why this matters.
extern "C" int __macapk_pairip_image_resolver_needed(uint64_t base) {
    Image* im = macapk_image_at_base(base);
    if (!im) return 1;
    if (!im->is_pairip_import_protected()) return 0;
    return im->pairip_imports_complete() ? 0 : 1;
}
// The WRITABLE (non-exec) segments of the image at `base`, for diagnostics.
extern "C" int __macapk_arm64_image_rw_ranges(uint64_t base, uint64_t* lo, uint64_t* hi, int max) {
    Image* im = macapk_image_at_base(base);
    if (!im) return -1;
    int n = 0;
    for (const auto& sg : im->segments()) {
        if ((sg.prot & PROT_EXEC) || !(sg.prot & PROT_WRITE) || n >= max) continue;
        lo[n] = sg.vaddr; hi[n] = sg.vaddr + sg.memsz; ++n;
    }
    return n;
}
extern "C" const char* __macapk_arm64_guest_image_for_pc(uintptr_t pc, uintptr_t* off_out) {
    for (Image* im : loaded_images()) {
        if (!im || !im->base()) continue;
        const uintptr_t lo = reinterpret_cast<uintptr_t>(im->base());
        if (pc >= lo && pc < lo + im->mapped_size()) {
            if (off_out) *off_out = pc - lo;
            return im->name();
        }
    }
    return nullptr;
}

// Shared guest-entry-boundary prelude for both public load entry points
// (embedded-blob and Lever-3 from-path). Factored out t3-mmap (2026-07-24)
// so the two public functions below stay a thin img->load(...)/
// img->load_from_path(...) wrapper each, instead of duplicating this setup.
// Returns false when arm64 guest code must NOT be loaded on this host.
bool macapk_arm64_load_prelude() {
    // The loader thread will run the guest's ifunc resolvers + init_array
    // constructors during load(); enable x18 preservation up front (guarded,
    // so cheap on re-entry) so no guest arm64 code runs on this thread with
    // x18 unprotected. See docs/x18-custom-abi-toggle-entitlement_2026-07-14.md.
    macapk_enable_x18_preservation();
    // v1.0.153: re-install SIGILL handler. The JVM re-installs its own
    // SIGILL handler during JNI_CreateJavaVM, displacing the bionic-shim's
    // constructor-installed handler. Re-install here so our `mrs Sys, Xt`
    // emulator is front-of-chain by the time the first APK native lib's
    // static init runs CPU-feature probe.
    typedef void (*reinstall_fn)(void);
    static reinstall_fn s_reinstall = nullptr;
    if (!s_reinstall) {
        s_reinstall = reinterpret_cast<reinstall_fn>(
            dlsym(RTLD_DEFAULT, "__macapk_reinstall_sigill_handler"));
        std::fprintf(stderr, "[arm64-native] sigill reinstall fn=%p\n",
                     (void*)s_reinstall);
    }
    if (s_reinstall) s_reinstall();

    // The gate. Deliberately AFTER macapk_enable_x18_preservation() above, so
    // the probe measures the same configuration the guest will actually run in.
    if (!macapk_x18_capability_ok()) {
        if (std::getenv("MACAPK_ALLOW_UNSAFE_X18") != nullptr) {
            std::fprintf(stderr, "[x18] MACAPK_ALLOW_UNSAFE_X18=1 set; "
                                 "continuing anyway. Expect corruption.\n");
            return true;
        }
        return false;
    }
    return true;
}

// --- Truthful host VM census (backs /proc/self/maps and /proc/self/smaps) ---
//
// SCOPE (owner ruling 2026-08-19): report our REAL state. Every number below
// is read out of a real kernel field; nothing here fabricates a mapping,
// hides one of our libraries, or pretends this is a stock Android device. A
// consumer that reads it gets the truth and does whatever it does with it.
//
// Why the whole census and not just the guest ELFs: before this,
// __macapk_arm64_native_write_maps listed only each loaded guest ELF's
// PT_LOADs -- roughly 30 lines -- so a memory monitor summing /proc/self/maps
// saw none of the JVM heap, none of ANGLE/Metal, and none of the ~350 host
// dylibs, and computed a total that was false by more than an order of
// magnitude. A map that omits most of the address space is not a smaller
// truth, it is a wrong one.
//
// API choices, all MEASURED on this host
// (debug/procprobe/measured-macos26-arm64.txt):
//   - mach_vm_region_recurse() is the walker: it is the only one that
//     descends into submaps (is_submap -> ++depth, re-query the same address).
//   - proc_regionfilename() is NOT the name source: measured, it named 6 of
//     84 regions (the main executable and dyld) and is blind to every
//     dyld-shared-cache image. Host image names therefore come from the dyld
//     image table (_dyld_image_count / _dyld_get_image_header /
//     _dyld_get_image_vmaddr_slide / _dyld_get_image_name), which listed all
//     347 images in a trivial process.
//   - Guest image names keep coming from loaded_images(), exactly as before.
//
// Cost, measured: ~40 ms and ~3.9 MB of smaps text over 6,079 regions. A bare
// OpenJDK 21 process measures 2,880 regions (vmmap -summary), so the real
// runtime sits in the 3k-6k band. The caller-side TTL in
// macapk_arm64_live_path() bounds a polling consumer.

// One address range we can honestly put a name to.
struct MacApkNamedRange {
    uint64_t    lo = 0, hi = 0;
    uint64_t    file_off = 0;   // offset of `lo` within the backing file
    uint32_t    dev_major = 0, dev_minor = 0;
    uint64_t    ino = 0;
    std::string path;
};

// The kernel's own view of one region.
struct MacApkVmRegion {
    uint64_t lo = 0, hi = 0;
    vm_prot_t prot = 0, max_prot = 0;
    unsigned char share_mode = 0;
    unsigned char external_pager = 0;
    uint64_t offset = 0;
    uint32_t ref_count = 0;
    uint32_t pages_resident = 0;
    uint32_t pages_dirtied = 0;
    uint32_t pages_swapped_out = 0;
};

struct MacApkNameIndex {
    std::vector<MacApkNamedRange> ranges;  // sorted by lo
    uint32_t dyld_count = 0;
    size_t   guest_count = 0;
    bool     built = false;
};

// Never-destroyed singletons, for the same teardown reason as
// macapk_arm64_maps_path() in bionic_shim.cpp: a late /proc/self/maps open
// during clean shutdown must not touch storage that __cxa_atexit already
// destroyed (that path already caused a std::terminate once, v1.0.573).
// The directory guest libraries are reported under in /proc/self/maps.
//
// WHY THIS IS SETTABLE RATHER THAN A LITERAL. It used to be the fixed string
// "/data/app/~~macapk/lib/arm64/", which is Android-SHAPED but is not a path that
// exists, and more importantly is not the path the JAVA half of this same platform
// reports: ApplicationInfo.nativeLibraryDir resolves to the install-shaped symlink
// MacApkPackageManager.publishNativeLibDir() creates. On a real device those two are
// literally the same directory, so an app that cross-references them (a crash
// symbolicator matching a maps line against its own library directory, for instance)
// saw a contradiction here that it could never see on a device.
//
// Java PUSHES the value it publishes, rather than the loader re-deriving it, for the
// same reason the property store does: two derivations of one fact is exactly the
// incoherence this is meant to end. The literal remains the fallback for a headless
// run that never gets as far as building an ApplicationInfo.
static std::mutex& macapk_guest_lib_dir_mu() { static std::mutex m; return m; }
static std::string& macapk_guest_lib_dir_storage() {
    static std::string d = "/data/app/~~macapk/lib/arm64/";
    return d;
}
static std::string macapk_guest_lib_dir() {
    // DIAGNOSTIC (MACAPK_TEST_LIB_DIR, default OFF) -- present the guest native
    // libraries in /proc/self/maps (and, transitively, the map_files/ symlink
    // targets that are parsed out of that same text) under a caller-supplied
    // path, overriding whatever the Java side pushed. Purpose: test whether
    // pairip's integrity VM branches on the readlinked path of its OWN mapping.
    // GROUND TRUTH (2026-09-07): the Play Minecraft's libpairipcore reads
    // /proc/self/maps, enumerates /proc/self/map_files/, then readlinks exactly
    // ONE entry -- its own mapping -- and receives the macOS bundle path
    // "/Applications/Minecraft.app/.../_apkt/lib/arm64-v8a/libpairipcore.so",
    // a path no real Android device ever produces (a device returns
    // "/data/app/~~<h>/<pkg>-<h>/lib/arm64/libpairipcore.so"). Honest: the lib
    // genuinely exists; this only translates its path into the Android form the
    // guest expects, exactly as the maps text already Android-shapes the dir.
    if (const char* t = std::getenv("MACAPK_TEST_LIB_DIR")) {
        if (*t) {
            std::string d(t);
            if (d.back() != '/') d.push_back('/');
            return d;
        }
    }
    std::lock_guard<std::mutex> g(macapk_guest_lib_dir_mu());
    return macapk_guest_lib_dir_storage();
}
extern "C" void __macapk_set_guest_lib_dir(const char* dir) {
    if (!dir || !*dir) return;
    std::string d(dir);
    if (d.back() != '/') d.push_back('/');
    {
        std::lock_guard<std::mutex> g(macapk_guest_lib_dir_mu());
        if (macapk_guest_lib_dir_storage() == d) return;
        macapk_guest_lib_dir_storage() = d;
    }
    // Re-intern every registered image's dladdr name under the new dir, here and
    // not on the dladdr path (see Image::dladdr_fname). The old strings stay valid:
    // the intern table never frees, so a pointer a guest already holds keeps working.
    for (Image* im : loaded_images())
        if (im) im->dladdr_fname.store(macapk_guest_image_realpath(im),
                                       std::memory_order_release);
    std::fprintf(stderr,
        "[arm64-native] guest libraries will be reported in /proc/self/maps under "
        "%s (pushed by the Java side, so maps and ApplicationInfo."
        "nativeLibraryDir agree)\n", d.c_str());
    std::fflush(stderr);
}
extern "C" const char* __macapk_arm64_guest_lib_dir_copy(char* buf, size_t cap) {
    // For bionic_shim.cpp's guest dlopen (MACAPK_DLOPEN_NATIVE_LIB_DIR): the SAME
    // directory the maps text and guest dladdr name, so a path dladdr hands out
    // is a path dlopen recognises. Copied out because the storage is mutable.
    if (!buf || cap == 0) return nullptr;
    const std::string d = macapk_guest_lib_dir();
    std::snprintf(buf, cap, "%s", d.c_str());
    return buf;
}

// ---------------------------------------------------------------------------
// Guest dladdr for Stage-6 images (MACAPK_GUEST_DLADDR, default ON; the gate
// itself lives in bionic_shim.cpp's __macapk_arm64_dladdr).
//
// WHY. A Stage-6 guest's `dladdr` import used to bind to libSystem's, and dyld
// cannot name memory it did not map, so every guest "which library am I in?"
// answered 0. MEASURED (docs/fleet-games-smashhit-gd-meltdown-jumplite_2026-09-26.md
// section 4): libcrashlytics 3.2.0 does exactly that at file vaddr 0x14270
// (adr x0,self -> bl dladdr -> cbz w0,fail), so native crash handling failed in 7
// corpus apps ("Could not find self when loading libcrashlytics-common.so").
//
// CONTRACT, bionic android14-release linker/linker.cpp:
//   int do_dladdr(const void* addr, Dl_info* info) {
//     soinfo* si = find_containing_library(addr);
//     if (si == nullptr) return 0;
//     memset(info, 0, sizeof(Dl_info));
//     info->dli_fname = si->get_realpath();
//     info->dli_fbase = reinterpret_cast<void*>(si->base);
//     ElfW(Sym)* sym = si->find_symbol_by_address(addr);
//     if (sym != nullptr) {
//       info->dli_sname = si->get_string(sym->st_name);
//       info->dli_saddr = reinterpret_cast<void*>(si->resolve_symbol_address(sym)); }
//     return 1; }
// and find_containing_library: untag the address, then it must lie inside
// [si->base, si->base + si->size) AND inside one PT_LOAD's [p_vaddr, p_vaddr+p_memsz)
// measured from load_bias. si->base is the reservation start, i.e.
// load_bias + page_start(min PT_LOAD p_vaddr) -- equal to load_bias for every
// library whose first PT_LOAD is at 0. resolve_symbol_address is load_bias +
// st_value except for STT_GNU_IFUNC, where Bionic CALLS the resolver; we do not
// run guest code from inside dladdr, so an ifunc answers its resolver address.
//
// dli_fname: Bionic's realpath is what /proc/self/maps shows for the mapping, so
// ours is exactly the string macapk_name_index() writes for this image's PT_LOAD
// lines (macapk_guest_lib_dir() + soname, .dylib -> .so). One name, three
// surfaces (maps, dladdr, ApplicationInfo.nativeLibraryDir), as on a device.
// Interned and never freed: Bionic's pointer lives as long as the library, and
// ours outlives it, which is the safe direction. This builder allocates and
// locks, so it runs at image registration and on a native-lib-dir push, never
// on the dladdr path, which only loads Image::dladdr_fname.
//
// Readers do not lock loaded_images(), same as every other lookup here; its
// mutation is serialised by dyld's dlopen lock (see the push_back site).
static const char* macapk_guest_image_realpath(const Image* img) {
    std::string nm = (img && img->name()) ? img->name() : "lib.so";
    size_t slash = nm.find_last_of('/');
    std::string bn = (slash == std::string::npos) ? nm : nm.substr(slash + 1);
    if (bn.size() > 6 && bn.compare(bn.size() - 6, 6, ".dylib") == 0)
        bn = bn.substr(0, bn.size() - 6) + ".so";
    const std::string path = macapk_guest_lib_dir() + bn;
    static std::mutex* mu = new std::mutex();
    static std::set<std::string>* interned = new std::set<std::string>();
    std::lock_guard<std::mutex> g(*mu);
    return interned->insert(path).first->c_str();
}
extern "C" int __macapk_arm64_native_dladdr(const void* addr, Dl_info* info) {
    if (!info) return 0;
    const uint64_t a = reinterpret_cast<uint64_t>(addr) & ((1ULL << 56) - 1);  // untag_address
    for (Image* im : loaded_images()) {
        if (!im || !im->base()) continue;
        const uint64_t bias = reinterpret_cast<uint64_t>(im->base());
        if (a < bias || a - bias >= im->mapped_size()) continue;
        const uint64_t vaddr = a - bias;
        const Elf64_Phdr* ph = im->phdrs_data();
        bool in_load = false;
        uint64_t min_vaddr = ~uint64_t(0);
        for (size_t i = 0; i < im->phdr_count(); ++i) {
            if (ph[i].p_type != PT_LOAD) continue;
            if (ph[i].p_vaddr < min_vaddr) min_vaddr = ph[i].p_vaddr;
            if (vaddr >= ph[i].p_vaddr && vaddr < ph[i].p_vaddr + ph[i].p_memsz)
                in_load = true;
        }
        if (!in_load) continue;   // inside the span but between segments: not "contained"
        std::memset(info, 0, sizeof *info);
        // Interned at registration (Image::dladdr_fname): no allocation, no lock
        // here, because crash reporters call dladdr from their signal handlers.
        // The fallback only covers an image that somehow registered without one.
        const char* fname = im->dladdr_fname.load(std::memory_order_acquire);
        info->dli_fname = fname ? fname : macapk_guest_image_realpath(im);
        // page_start(min_vaddr) at Android's 4 KiB page; 0 for virtually every .so.
        info->dli_fbase = reinterpret_cast<void*>(bias + (min_vaddr & ~uint64_t(0xfff)));
        const char* sname = nullptr;
        if (const Elf64_Sym* s = im->find_symbol_by_address(uintptr_t(a), &sname)) {
            info->dli_sname = sname;
            info->dli_saddr = reinterpret_cast<void*>(bias + s->st_value);
        }
        return 1;
    }
    return 0;
}

std::mutex& macapk_census_mutex() {
    static std::mutex* m = new std::mutex();
    return *m;
}

// st_dev/st_ino for a host path, cached: the census can run every few hundred
// ms and there are ~350 image paths. A path that does not stat (every
// dyld-shared-cache image) contributes 00:00 0, which is exactly what Linux
// prints for a mapping with no backing inode -- not a guess, an absence.
bool macapk_path_devino(const std::string& p, uint32_t* maj, uint32_t* min_,
                        uint64_t* ino) {
    static std::unordered_map<std::string, std::array<uint64_t, 3>>* cache =
        new std::unordered_map<std::string, std::array<uint64_t, 3>>();
    auto it = cache->find(p);
    if (it == cache->end()) {
        std::array<uint64_t, 3> v{0, 0, 0};
        struct stat st;
        if (::stat(p.c_str(), &st) == 0) {
            v[0] = (uint64_t)major(st.st_dev);
            v[1] = (uint64_t)minor(st.st_dev);
            v[2] = (uint64_t)st.st_ino;
        }
        it = cache->emplace(p, v).first;
    }
    *maj = (uint32_t)it->second[0];
    *min_ = (uint32_t)it->second[1];
    *ino = it->second[2];
    return *ino != 0;
}

// Build (or reuse) the address -> name index. Rebuilt only when the dyld image
// count or the guest image count moves, which is what actually changes it.
const MacApkNameIndex& macapk_name_index() {
    static MacApkNameIndex* idx = new MacApkNameIndex();
    const uint32_t dc = _dyld_image_count();
    const size_t   gc = loaded_images().size();
    if (idx->built && idx->dyld_count == dc && idx->guest_count == gc)
        return *idx;
    idx->ranges.clear();
    idx->dyld_count = dc;
    idx->guest_count = gc;
    idx->built = true;

    // (a) Guest ELFs. Reported under an Android-style lib path with a .so
    // extension. This is the ONE place the string is not our literal on-disk
    // name (our images are .dylib): it is the app's own library under the
    // soname the app itself asked to load, which is what every base-finder and
    // crash symbolicator in an APK greps for. Nothing is concealed -- the
    // library is listed, at its real address, with its real size.
    for (auto* img : loaded_images()) {
        if (!img || !img->base()) continue;
        uintptr_t b = reinterpret_cast<uintptr_t>(img->base());
        const Elf64_Phdr* ph = img->phdrs_data();
        size_t n = img->phdr_count();
        std::string nm = img->name() ? img->name() : "lib.so";
        size_t slash = nm.find_last_of('/');
        std::string bn = (slash == std::string::npos) ? nm : nm.substr(slash + 1);
        if (bn.size() > 6 && bn.compare(bn.size() - 6, 6, ".dylib") == 0)
            bn = bn.substr(0, bn.size() - 6) + ".so";
        std::string path = macapk_guest_lib_dir() + bn;
        // dev/inode for the guest's own ELF, exactly as branch (b) does for host dylibs.
        // Without this the guest-ELF rows defaulted to 00:00 0 (MacApkNamedRange zero-inits
        // them) while host rows carried real values -- and a FILE-BACKED mapping with a zero
        // device and inode is a state Linux cannot produce, so the split was a tell in its
        // own right. `nm` is the real on-disk path we loaded from; `path` is its Android
        // rendering. ⚠ `nm` is img->name(), which is a SONAME ("libpairipcore.so"), not a
        // path -- statting it fails and silently leaves 0, which is how the first version of
        // this fix changed nothing. Stat the file we actually mapped: `path` is currently a
        // real on-disk location (macapk_guest_lib_dir() points at _apkt/lib/<abi>/), and `nm`
        // is tried only in case it happens to be absolute. If neither resolves we leave 0
        // rather than inventing an inode -- a wrong number is worse than a missing one, and
        // this is exactly the case that will need revisiting when the lib dir starts
        // reporting /data/app (see docs/platform-surface-audit_2026-09-11.md item 7).
        uint32_t gmaj = 0, gmin = 0; uint64_t gino = 0;
        if (!macapk_path_devino(path, &gmaj, &gmin, &gino) && !nm.empty() && nm[0] == '/')
            macapk_path_devino(nm, &gmaj, &gmin, &gino);
        for (size_t i = 0; i < n; ++i) {
            if (ph[i].p_type != PT_LOAD) continue;
            MacApkNamedRange r;
            r.lo = b + ph[i].p_vaddr;
            r.hi = (r.lo + ph[i].p_memsz + 0xffful) & ~uint64_t(0xffful);
            r.file_off = ph[i].p_offset;
            r.dev_major = gmaj; r.dev_minor = gmin; r.ino = gino;
            r.path = path;
            idx->ranges.push_back(std::move(r));
        }
    }

    // (b) Host dyld images, under their REAL paths. We do not disguise or omit
    // libSystem/ANGLE/Metal/libjvm: a census that hid our own runtime would be
    // exactly the fabrication this lane refuses to do.
    for (uint32_t i = 0; i < dc; ++i) {
        const struct mach_header_64* mh =
            reinterpret_cast<const struct mach_header_64*>(_dyld_get_image_header(i));
        const char* nm = _dyld_get_image_name(i);
        if (!mh || !nm || mh->magic != MH_MAGIC_64) continue;
        const intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        std::string path = nm;
        uint32_t maj = 0, min_ = 0; uint64_t ino = 0;
        macapk_path_devino(path, &maj, &min_, &ino);
        const struct load_command* lc =
            reinterpret_cast<const struct load_command*>(mh + 1);
        for (uint32_t c = 0; c < mh->ncmds; ++c) {
            if (lc->cmdsize == 0) break;   // malformed; never spin
            if (lc->cmd == LC_SEGMENT_64) {
                const struct segment_command_64* sc =
                    reinterpret_cast<const struct segment_command_64*>(lc);
                // __PAGEZERO is a 4 GB unreadable reservation at 0 with no
                // pages; naming it would swallow every low region.
                if (sc->vmsize != 0 &&
                    std::strncmp(sc->segname, "__PAGEZERO", 16) != 0) {
                    MacApkNamedRange r;
                    r.lo = (uint64_t)((intptr_t)sc->vmaddr + slide);
                    r.hi = r.lo + sc->vmsize;
                    r.file_off = sc->fileoff;
                    r.dev_major = maj; r.dev_minor = min_; r.ino = ino;
                    r.path = path;
                    idx->ranges.push_back(std::move(r));
                }
            }
            lc = reinterpret_cast<const struct load_command*>(
                    reinterpret_cast<const char*>(lc) + lc->cmdsize);
        }
    }
    std::sort(idx->ranges.begin(), idx->ranges.end(),
              [](const MacApkNamedRange& a, const MacApkNamedRange& b) {
                  return a.lo < b.lo;
              });
    return *idx;
}

// What we can honestly call one kernel region.
struct MacApkRegionName {
    std::string name;                     // empty == unnamed
    uint64_t    file_off = 0;
    uint32_t    dev_major = 0, dev_minor = 0;
    uint64_t    ino = 0;
};

// Resolve a region's name WITHOUT ever misattributing it.
//
// The trap this exists to avoid, found by measuring our own first cut: macOS
// COALESCES adjacent mappings that share protection and share mode, so the
// dyld shared cache appears as a handful of ~1.6 GB regions each spanning
// hundreds of images. Naming such a region after whichever image happened to
// contain its first byte produced lines like "573.0MB ... /usr/lib/
// libsasl2.2.dylib" -- a flatly false statement about 572 MB of that range.
// Linux never has this problem because a Linux VMA is per backing file.
//
// So: gather EVERY named range overlapping the region.
//   - none              -> unnamed (the caller may still have a PR_SET_VMA label)
//   - all the same path -> that path. True even when the region spans several
//                          segments of one image (which happens when two
//                          PT_LOADs end up with identical protection), and it
//                          is what keeps a guest .so findable by name.
//   - different paths   -> the region really does span several images. Name it
//                          "[dyld shared cache]" when those images live in the
//                          cache (_dyld_shared_cache_contains_path, public
//                          since macOS 11), which is a true and useful fact,
//                          and otherwise leave it unnamed. NEVER pick one.
MacApkRegionName macapk_resolve_region_name(const MacApkNameIndex& idx,
                                            uint64_t lo, uint64_t hi) {
    MacApkRegionName out;
    if (idx.ranges.empty()) return out;
    size_t first = size_t(std::upper_bound(
        idx.ranges.begin(), idx.ranges.end(), lo,
        [](uint64_t v, const MacApkNamedRange& r) { return v < r.lo; })
        - idx.ranges.begin());
    // Ranges are sorted by lo but may nest, so start the scan a bounded
    // distance back. 64 is far more than any real nesting depth here (a
    // segment would have to be preceded by 64 shorter segments that all start
    // before it and end after `lo`).
    size_t start = first > 64 ? first - 64 : 0;
    const MacApkNamedRange* anchor = nullptr;   // the range containing `lo`
    const MacApkNamedRange* firstov = nullptr;  // first range overlapping at all
    bool multi = false;
    for (size_t i = start; i < idx.ranges.size(); ++i) {
        const MacApkNamedRange& rg = idx.ranges[i];
        if (rg.lo >= hi) break;
        if (rg.hi <= lo) continue;
        if (!firstov) firstov = &rg;
        else if (firstov->path != rg.path) multi = true;
        if (!anchor && rg.lo <= lo && lo < rg.hi) anchor = &rg;
    }
    if (!firstov) return out;
    if (!multi) {
        const MacApkNamedRange& rg = anchor ? *anchor : *firstov;
        out.name = rg.path;
        out.file_off = anchor ? rg.file_off + (lo - rg.lo) : rg.file_off;
        out.dev_major = rg.dev_major;
        out.dev_minor = rg.dev_minor;
        out.ino = rg.ino;
        return out;
    }
    if (_dyld_shared_cache_contains_path(firstov->path.c_str()))
        out.name = "[dyld shared cache]";
    return out;   // multi-image and not the cache -> unnamed, never a guess
}

// Walk the kernel's region list. Returns the number of regions recorded.
size_t macapk_enumerate_regions(std::vector<MacApkVmRegion>& out) {
    out.clear();
    const mach_port_t task = mach_task_self();
    mach_vm_address_t addr = 0;
    natural_t depth = 0;
    const uint64_t pgsz = (uint64_t)(sysconf(_SC_PAGESIZE) > 0
                                     ? sysconf(_SC_PAGESIZE) : 16384);
    // Hard iteration bound: a racing or corrupted map must never spin here,
    // and 200k regions is ~30x the largest census measured on this host.
    for (int guard = 0; guard < 200000; ++guard) {
        mach_vm_size_t size = 0;
        vm_region_submap_info_data_64_t info;
        mach_msg_type_number_t cnt = VM_REGION_SUBMAP_INFO_COUNT_64;
        kern_return_t kr = mach_vm_region_recurse(
            task, &addr, &size, &depth,
            reinterpret_cast<vm_region_recurse_info_t>(&info), &cnt);
        if (kr != KERN_SUCCESS) break;          // end of the address space
        if (info.is_submap) { ++depth; continue; }  // descend, same address
        MacApkVmRegion r;
        r.lo = (uint64_t)addr;
        r.hi = (uint64_t)addr + (uint64_t)size;
        r.prot = info.protection;
        r.max_prot = info.max_protection;
        r.share_mode = info.share_mode;
        r.external_pager = info.external_pager;
        r.offset = (uint64_t)info.offset;
        r.ref_count = info.ref_count;
        r.pages_resident = info.pages_resident;
        r.pages_dirtied = info.pages_dirtied;
        r.pages_swapped_out = info.pages_swapped_out;
        out.push_back(r);
        addr += size ? size : pgsz;   // size==0 would never advance
    }
    return out.size();
}

bool macapk_region_shared(const MacApkVmRegion& r) {
    return r.share_mode == SM_SHARED || r.share_mode == SM_TRUESHARED ||
           r.share_mode == SM_SHARED_ALIASED;
}

// Emit the kernel's maps header line for one region.
//
// GEOMETRY, from linux v5.4 fs/proc/task_mmu.c show_map_vma_header_prefix():
//     seq_setwidth(m, 25 + sizeof(void *) * 6 - 1);      // == 72
//     <start>-<end> <perms> <pgoff> <maj>:<min> <ino>
//     seq_putc(m, ' ');
// then show_map_vma(): if there is a name, seq_pad(m, ' ') pads the line out
// to 72 and appends one more space, so the pathname begins at 0-based column
// 73; if there is NO name the line simply ends after that single space. Both
// verified byte-exact against /proc/<pid>/smaps on an Android-11 arm64 AVD
// (pathname index measured at exactly 73).
void macapk_write_map_line(FILE* f, const MacApkVmRegion& r,
                           const char* name, uint64_t file_off,
                           uint32_t maj, uint32_t min_, uint64_t ino) {
    // TWO KERNEL INVARIANTS, enforced HERE because this is the one place the default
    // emitter renders a line -- fixing them at the call sites would leave the next caller
    // free to reintroduce them.
    //
    // fs/proc/task_mmu.c show_map_vma(), identical at v5.4 / v6.1 / v6.6:
    //     unsigned long long pgoff = 0;
    //     if (file) { ... pgoff = ((loff_t)vma->vm_pgoff) << PAGE_SHIFT; }
    //     show_vma_header_prefix(m, start, end, flags, pgoff, dev, ino);   // UNCONDITIONAL
    //
    //   1. A VMA WITH NO BACKING FILE prints pgoff/dev/inode as 0 -- the print is
    //      unconditional, only the assignment is guarded by `file`. A bracketed pseudo-name
    //      ([stack], [heap], [anon:...]) is file-less by definition.
    //   2. pgoff IS `vm_pgoff << PAGE_SHIFT`, so it is STRUCTURALLY a multiple of the page
    //      size. We were emitting the raw ELF p_offset / Mach-O fileoff, which measured
    //      non-page-aligned on two lines of a real capture. Android's page size is 4096
    //      regardless of this host's 16 KB pages, because these numbers describe the Android
    //      surface we are presenting.
    const bool file_backed = name && *name && name[0] != '[';
    if (!file_backed) { file_off = 0; maj = 0; min_ = 0; ino = 0; }
    else              { file_off &= ~uint64_t(0xfff); }
    char perms[5];
    perms[0] = (r.prot & VM_PROT_READ)    ? 'r' : '-';
    perms[1] = (r.prot & VM_PROT_WRITE)   ? 'w' : '-';
    perms[2] = (r.prot & VM_PROT_EXECUTE) ? 'x' : '-';
    perms[3] = macapk_region_shared(r)    ? 's' : 'p';
    perms[4] = 0;
    int n = std::fprintf(f, "%08llx-%08llx %s %08llx %02x:%02x %llu",
                         (unsigned long long)r.lo, (unsigned long long)r.hi,
                         perms, (unsigned long long)file_off,
                         (unsigned)maj, (unsigned)min_,
                         (unsigned long long)ino);
    if (n < 0) n = 0;
    std::fputc(' ', f); ++n;
    if (name && *name) {
        while (n < 72) { std::fputc(' ', f); ++n; }
        std::fputc(' ', f);
        std::fputs(name, f);
    }
    std::fputc('\n', f);
}

// MACAPK_PROC_FULL_MAPS (default ON). =0 restores the pre-v1.0.1000
// guest-PT_LOAD-only /proc/self/maps. High blast radius: the map goes from
// ~30 lines to a 3k-6k-region census, and MCPE's KittyMemory hooks,
// libsysoptimizer's base-finder and every crash symbolicator read it. A
// name-searching parser tolerates extra lines; a fixed-buffer or line-capped
// one might not, so the gate exists to A/B that in ONE run.
bool macapk_proc_full_maps() {
    static const bool on = []{
        const char* e = std::getenv("MACAPK_PROC_FULL_MAPS");
        return !(e && e[0] == '0');
    }();
    return on;
}

// The pre-v1.0.1000 output: one line per PT_LOAD of every loaded guest ELF,
// and nothing else. Kept verbatim as the MACAPK_PROC_FULL_MAPS=0 bisect arm.
void macapk_write_guest_only_maps(FILE* f) {
    for (auto* img : loaded_images()) {
        if (!img || !img->base()) continue;
        uintptr_t b = reinterpret_cast<uintptr_t>(img->base());
        const Elf64_Phdr* ph = img->phdrs_data();
        size_t n = img->phdr_count();
        std::string nm = img->name() ? img->name() : "lib.so";
        size_t slash = nm.find_last_of('/');
        std::string bn = (slash == std::string::npos) ? nm : nm.substr(slash + 1);
        if (bn.size() > 6 && bn.compare(bn.size() - 6, 6, ".dylib") == 0)
            bn = bn.substr(0, bn.size() - 6) + ".so";
        std::string path = macapk_guest_lib_dir() + bn;
        for (size_t i = 0; i < n; ++i) {
            if (ph[i].p_type != PT_LOAD) continue;
            uintptr_t lo = b + ph[i].p_vaddr;
            uintptr_t hi = (lo + ph[i].p_memsz + 0xffful) & ~uintptr_t(0xffful);
            char perms[5];
            perms[0] = (ph[i].p_flags & PF_R) ? 'r' : '-';
            perms[1] = (ph[i].p_flags & PF_W) ? 'w' : '-';
            perms[2] = (ph[i].p_flags & PF_X) ? 'x' : '-';
            perms[3] = 'p';
            perms[4] = 0;
            std::fprintf(f, "%012lx-%012lx %s %08lx 00:00 0          %s\n",
                         (unsigned long)lo, (unsigned long)hi, perms,
                         (unsigned long)ph[i].p_offset, path.c_str());
        }
    }
}

// Remove an Image from loaded_images(). Registration happens inside
// load_common() BEFORE the image's ifunc resolvers / .init_array run (see the
// comment there); today every load-failure return precedes that point, so on
// the entry points' failure paths this finds nothing -- it exists so a future
// failure path added AFTER guest code first runs cannot leave the registry
// holding a dangling pointer that the SEGV handler would then walk.
static void unregister_image(Image* img) {
    auto& v = loaded_images();
    for (auto it = v.begin(); it != v.end(); ++it) {
        if (*it == img) { v.erase(it); break; }
    }
}

}  // namespace

// --- Public ABI -- linked into libmacapk-bionic-shim.dylib ------------

extern "C" {

// Load an arm64 Android ELF blob into memory. Returns an opaque handle
// on success, nullptr on failure (with stderr diagnostic).
void* __macapk_arm64_native_load(const void* blob, size_t blob_size,
                                    const char* dbg_soname) {
    if (!macapk_arm64_load_prelude()) return nullptr;
    auto img = new Image;
    if (!img->load(static_cast<const uint8_t*>(blob), blob_size,
                   dbg_soname)) {
        unregister_image(img);
        delete img;
        return nullptr;
    }
    // Registered by load_common() itself, BEFORE the image's ifunc
    // resolvers and .init_array ran -- constructor-time faults must be
    // attributable to the image (see the registration comment there).
    return img;
}

// t3-mmap (2026-07-24): Lever 3. Load an arm64 Android ELF straight off
// disk, no embedded blob -- `so_path_rel` is a path fragment relative to
// this app's data dir (arm64_pack.cpp bakes in exactly
// "_apkt/lib/<abi>/lib<name>.so", the same layout locateApkSo()/
// macapk_source_so_path() already use), resolved here via android_root +
// data_subdir. Returns an opaque handle on success, nullptr on failure
// (with a loud stderr diagnostic -- see Image::load_from_path()'s missing-
// .so comment for the full cache-contract rationale). Same handle type,
// same loaded_images() registry, same lookup()/dlsym() contract as the
// embedded-blob entry point above -- callers (the wrapper's per-export
// trampolines, resolve_external's cross-image walk) cannot tell which
// loader produced a given Image*.
void* __macapk_arm64_native_load_from_path(const char* so_path,
                                           const char* dbg_soname) {
    if (!macapk_arm64_load_prelude()) return nullptr;
    auto img = new Image;
    if (!img->load_from_path(so_path, dbg_soname)) {
        unregister_image(img);
        delete img;
        return nullptr;
    }
    // Registered by load_common() itself, BEFORE guest code first ran.
    return img;
}

// Look up a defined dynamic symbol's runtime address.
extern "C" int macapk_pairip_inert_jni_onload(void* vm, void* reserved);

void* __macapk_arm64_native_dlsym(void* handle, const char* name) {
    if (!handle || !name) return nullptr;
    Image* img = static_cast<Image*>(handle);
    // The wrapper's constructor fills one export slot per symbol by calling this,
    // and each export trampoline (`ldr x16,[slot]; br x16`) jumps to whatever the
    // slot holds (arm64_pack.cpp). The JVM's NativeLibraries.load then calls the
    // library's JNI_OnLoad through that trampoline. For a pairip import-protected
    // lib the guest JNI_OnLoad would branch through an un-based import hole and
    // SIGSEGV (PlayFab did, at pc=0x404910, right after we skipped its init_array),
    // so hand the trampoline our inert JNI_OnLoad instead -> the JVM sees a clean
    // load and the non-engine lib stays mapped-but-inert. Structural predicate,
    // never a lib name; gated on MACAPK_PAIRIP_GATE_EMU inside the predicate.
    if (name[0] == 'J' && std::strcmp(name, "JNI_OnLoad") == 0 &&
        img->is_pairip_import_protected() && !img->pairip_imports_filled()) {
        std::fprintf(stderr,
            "[pairip-gate] %s: neutering JNI_OnLoad (import-protected, guest "
            "JNI_OnLoad would branch through an unresolved import hole)\n",
            img->name() ? img->name() : "?");
        return reinterpret_cast<void*>(&macapk_pairip_inert_jni_onload);
    }
    return img->lookup(name);
}

// --- elfexec (2026-07-29): APK-shipped native EXECUTABLES ------------
//
// Android's PackageManager extracts (and chmods 0755) only files matching
// `lib/<abi>/lib*.so`, so an app that ships a helper BINARY it intends to
// fork has to name it `lib<something>.so`. It is still an ET_DYN PIE
// executable: PT_INTERP names /system/bin/linker64, DT_FLAGS_1 carries
// DF_1_PIE, and it exports nothing, so there is no symbol for the existing
// per-export trampoline path to call. The ONLY entry is e_entry.
//
// macOS cannot exec a Linux ELF (`posix_spawn` -> ENOEXEC -> shell/JVM
// status 126), so `macapk-elfexec` -- a real host child process -- loads the
// ELF with the SAME loader used for every other guest arm64 image and jumps
// to its entry point. These three entry points are what that helper needs
// beyond the existing load/dlsym pair. See
// docs/apk-native-executables_2026-07-29.md.

// Load an arm64 Android ELF from an ABSOLUTE host path.
//
// __macapk_arm64_native_load_from_path takes a path RELATIVE to the app's
// data dir because its caller is a generated wrapper that baked in
// "_apkt/lib/<abi>/lib<name>.so" and runs inside the runtime process, where
// __macapk_set_android_root()/__macapk_set_data_subdir() have already been
// called from Java. The elfexec helper is a separate process that learns the
// ELF's location from its own argv[0] and has no data-dir context at all, so
// it needs to name the file directly. Everything after the path resolution is
// byte-for-byte the same pipeline (map/relocate/protect/init_array), and the
// resulting Image goes into the same loaded_images() registry, so a later
// image's resolve_external() finds this one's exports (that is how the
// executable's DT_NEEDED guest library gets satisfied).
}  // extern "C"
// (dev, ino) -> Image* for every image loaded by ABSOLUTE path (the elfexec process's own
// loads). Leaked, like the other process-lifetime registries here.
static std::mutex& macapk_abs_inode_mu() { static auto* m = new std::mutex(); return *m; }
static std::map<std::pair<uint64_t, uint64_t>, void*>& macapk_abs_inode_map() {
    static auto* m = new std::map<std::pair<uint64_t, uint64_t>, void*>(); return *m;
}
extern "C" {
void* __macapk_arm64_native_load_abs(const char* abs_path,
                                     const char* dbg_soname) {
    if (!abs_path || !*abs_path || abs_path[0] != '/') return nullptr;
    if (!macapk_arm64_load_prelude()) return nullptr;
    // The Image keeps the name POINTER (dbg_name_), and this entry point's callers name an
    // image from a std::string that dies with their loop (elfexec_main.cpp's DT_NEEDED walk,
    // bionic_shim_elfexec.cpp's dlopen). The wrapper entry points pass string literals, so
    // only this one copies. Leaked with the Image: images are never unloaded.
    if (dbg_soname) dbg_soname = ::strdup(dbg_soname);
    auto img = new Image;
    // Reuses load_from_path() verbatim: macapk_resolve_relative_so_path()
    // returns an absolute fragment unchanged (see its definition), so this is
    // the identical map/relocate/protect/init_array pipeline with a different
    // way of naming the file -- not a second loader.
    if (!img->load_from_path(abs_path, dbg_soname)) {
        unregister_image(img);
        delete img;
        return nullptr;
    }
    // Registered by load_common() itself, BEFORE guest code first ran.
    // Remember WHICH FILE it is, so a later dlopen of the same file (any spelling) finds it.
    struct stat st;
    if (::stat(abs_path, &st) == 0) {
        std::lock_guard<std::mutex> lk(macapk_abs_inode_mu());
        macapk_abs_inode_map()[{uint64_t(st.st_dev), uint64_t(st.st_ino)}] = img;
    }
    return img;
}

// The image __macapk_arm64_native_load_abs loaded from the file at `path` (matched by
// device + inode, as bionic's find_loaded_library_by_inode matches a dlopen BY PATH), or
// nullptr. A path request never matches by soname on a device: a different file that
// happens to carry the same DT_SONAME is a different library.
void* __macapk_arm64_native_find_loaded_file(const char* path) {
    struct stat st;
    if (!path || ::stat(path, &st) != 0) return nullptr;
    std::lock_guard<std::mutex> lk(macapk_abs_inode_mu());
    auto it = macapk_abs_inode_map().find({uint64_t(st.st_dev), uint64_t(st.st_ino)});
    return it == macapk_abs_inode_map().end() ? nullptr : it->second;
}

// The registered image whose soname is `name` (the dbg_soname it was loaded under: the
// DT_NEEDED string for elfexec_main.cpp's startup walk, the DT_SONAME or file name for a
// runtime dlopen in that process), or nullptr. Lets a guest dlopen in an exec'd program
// return an already-loaded library instead of mapping it twice, as bionic's linker does.
void* __macapk_arm64_native_find_loaded(const char* name) {
    if (!name || !*name) return nullptr;
    for (auto* img : loaded_images())
        if (img && img->name() && std::strcmp(img->name(), name) == 0) return img;
    return nullptr;
}

// Any DEFINED symbol of the most recently registered image named `soname`, data
// included, or nullptr. Guest dlsym's fallback for a handle that dlopen gave out
// for a translated wrapper (bionic_shim.cpp macapk_wrapper_elf_dlsym): the
// wrapper's Mach-O exports are function trampolines only (arm64_pack.cpp
// is_exportable), so a named DATA export is invisible to host dlsym. The newest
// image wins because a dlclose'd wrapper re-maps its ELF on the next dlopen.
void* __macapk_arm64_native_lookup_latest(const char* soname, const char* sym) {
    if (!soname || !*soname || !sym) return nullptr;
    auto& v = loaded_images();
    for (auto it = v.rbegin(); it != v.rend(); ++it) {
        Image* img = *it;
        if (img && img->name() && std::strcmp(img->name(), soname) == 0)
            return img->lookup(sym);
    }
    return nullptr;
}

// Runtime address of the image's ELF entry point (bionic crtbegin `_start`),
// or nullptr for a plain shared library (e_entry == 0).
void* __macapk_arm64_native_entry(void* handle) {
    if (!handle) return nullptr;
    return const_cast<uint8_t*>(static_cast<Image*>(handle)->entry());
}

// AT_PHDR / AT_PHNUM / AT_PHENT for the exec path's synthesized auxv.
//
// On Linux the kernel puts the MAPPED program-header table's address in
// AT_PHDR, which is only available when the image carries a PT_PHDR entry
// describing where in its own mapping the table lives (an executable always
// does; a bare .so often does not). Falls back to the loader's own copy of
// the table -- still a valid, readable Elf64_Phdr array, just not inside the
// guest mapping -- so a guest that only ever reads through it works either
// way. phdr is set to 0 and phnum to 0 if there is nothing to report.
void __macapk_arm64_native_auxv_phdr(void* handle, unsigned long* phdr,
                                     unsigned long* phnum,
                                     unsigned long* phent) {
    if (phdr)  *phdr  = 0;
    if (phnum) *phnum = 0;
    if (phent) *phent = sizeof(Elf64_Phdr);
    if (!handle) return;
    auto* img = static_cast<Image*>(handle);
    const Elf64_Phdr* tab = img->phdrs_data();
    size_t n = img->phdr_count();
    if (!tab || !n) return;
    if (phnum) *phnum = (unsigned long)n;
    if (!phdr) return;
    for (size_t i = 0; i < n; ++i) {
        if (tab[i].p_type == PT_PHDR) {
            *phdr = (unsigned long)(uintptr_t)(img->base() + tab[i].p_vaddr);
            return;
        }
    }
    *phdr = (unsigned long)(uintptr_t)tab;
}

// Run the image's DT_FINI_ARRAY (reverse order, bionic sentinels skipped).
// The exec path registers this with atexit() so a guest executable's
// destructors run on normal termination, which is what __libc_init's
// __cxa_atexit(__libc_fini, structors->fini_array) achieves on Android.
void __macapk_arm64_native_run_fini(void* handle) {
    if (!handle) return;
    static_cast<Image*>(handle)->run_fini_array();
}

// Returns 1 if `addr` lies inside any loaded arm64-native ELF's
// mapped span. The bionic_shim's SEGV handler calls this to
// distinguish addresses in our anonymously-mmap'd ELFs from JVM
// JIT-compiled code (which also lives in anonymous mappings and
// returns 0 from dladdr) -- without this check, generic NULL-deref
// recovery would also fire on JIT'd Java code, corrupting register
// state across a JVM-managed call.
int __macapk_arm64_native_addr_in_image(const void* addr) {
    if (!addr) return 0;
    auto a = reinterpret_cast<uintptr_t>(addr);
    for (auto* img : loaded_images()) {
        if (!img) continue;
        auto lo = reinterpret_cast<uintptr_t>(img->base());
        if (!lo) continue;
        auto hi = lo + img->mapped_size();
        if (a >= lo && a < hi) return 1;
    }
    return 0;
}

// The load base of the guest image containing `addr`, or nullptr if none. Lets a
// diagnostic (bionic_shim's jni-str-probe caller dump) turn a host backtrace
// frame into a resolvable `<soname>+0xoff`. Companion to addr_in_image.
extern "C" const void* __macapk_arm64_native_image_base_for(const void* addr) {
    if (!addr) return nullptr;
    auto a = reinterpret_cast<uintptr_t>(addr);
    for (auto* img : loaded_images()) {
        if (!img) continue;
        auto lo = reinterpret_cast<uintptr_t>(img->base());
        if (!lo) continue;
        auto hi = lo + img->mapped_size();
        if (a >= lo && a < hi) return img->base();
    }
    return nullptr;
}

// Is this process running the Stage-6 arm64 path at all?
//
// Mac.apk extracts exactly ONE ABI per app (v758), so a given run is either
// all-arm64 (Stage-6: the guest's original Android arm64 bytes execute) or
// all-ARM32 (the lifter emits arm64-darwin code via clang). The two never mix.
//
// The JNI re-marshal in bionic_shim.cpp needs exactly this distinction. It
// corrects Apple-arm64-vs-AAPCS64 STACK packing, a divergence that exists only
// when the callee is Android-compiled arm64. ARM32-lifted natives were built by
// clang FOR arm64-darwin, so they already use DarwinPCS and re-marshalling them
// would corrupt their arguments. Address-range tests alone cannot tell the two
// apart for name-bound natives, because in both cases the bound address is a
// trampoline inside a dylib under lib_native/ rather than in a mapped ELF.
int __macapk_arm64_native_image_count(void) {
    int n = 0;
    for (auto* img : loaded_images())
        if (img && img->base()) ++n;
    return n;
}

// Enumerate every loaded guest ELF for the dl_iterate_phdr() shim in
// bionic_shim.cpp. The guest's statically-linked C++ unwinder
// (libunwind, bundled inside libil2cpp) finds an unwind-info section
// ONLY via dl_iterate_phdr -- it imports no __register_frame/_Unwind_*
// table-registration symbols. Returning 0 from the shim (the old
// behavior) starved it of all .eh_frame, so the FIRST C++ exception
// il2cpp throws to propagate a managed exception unwound to the top of
// the stack with no handler found -> std::terminate -> SIGABRT. This
// hands the shim each image's load bias + raw program-header table so
// the unwinder can locate PT_GNU_EH_FRAME and unwind normally.
//
// The callback receives (base, phdrs, phnum, soname, user). A non-zero
// return stops the walk early, matching dl_iterate_phdr's contract
// (the unwinder returns 1 from the image that owns the target PC).
// Defined later in this file; used here to Android-shape guest-ELF dlpi_name.
static bool macapk_android_maps_paths_on();
static std::string macapk_maps_android_path(const std::string& host);

int __macapk_arm64_native_iterate_phdr(
        int (*cb)(const void* base, const void* phdrs, uint16_t phnum,
                  const char* name, void* user),
        void* user) {
    // dl_iterate_phdr's dlpi_name is a SEPARATE surface from /proc/self/maps. pairip reads its
    // own module's dlpi_name here and strips it to the app code-dir, then memcmp's that against
    // "/data/app" (+0x2ef64 -> buf[0x437] -> the o=25971 wall). img->name() is the raw macOS load
    // path (".macapk/code/<pkg>_<ver>/_apkt/lib/arm64-v8a/lib*.so"), so without this relabel the
    // dl_iterate_phdr surface leaks the host path even when the maps text is Android-shaped.
    // Apply the SAME device-faithful /data/app rewrite the maps writer uses (gated identically).
    const bool relabel = macapk_android_maps_paths_on();
    for (auto* img : loaded_images()) {
        if (!img || !img->base()) continue;
        const char* nm = img->name();
        std::string anm;
        if (relabel && nm && *nm) { anm = macapk_maps_android_path(nm); nm = anm.c_str(); }
        int r = cb(img->base(), img->phdrs_data(),
                   static_cast<uint16_t>(img->phdr_count()),
                   nm, user);
        if (r != 0) return r;
    }
    return 0;
}

// Synthesize /proc/self/maps content into the given FILE* (passed as void* to
// avoid a <cstdio> dependency in the header). Real Android has
// /proc/self/maps; native libs that parse it (crash reporters, JIT/AOT base
// finders, and memory-hooking libs such as KittyMemory used by mod menus) need
// each library's segment ranges plus a pathname to locate its base and its
// r-xp region. Without it those spin re-opening the file forever (KittyMemory
// does), stalling init. The shim regenerates this per open (uncached) because
// the map grows as ELFs load. App-agnostic.
//
// v1.0.1000: this now reports the FULL, REAL region census (host dylibs, the
// JVM heap, ANGLE/Metal, guest ELFs -- everything the kernel has), not just
// the guest ELF PT_LOADs. See the census block above for why and how.
// MACAPK_PROC_FULL_MAPS=0 restores the old guest-PT_LOAD-only output so a
// regression in any maps consumer bisects in a single run.
// DIAGNOSTIC (MACAPK_MAP_APK_SPEC): map the app's APK/split files into the process
// and report them in /proc/self/maps under /data/app-style paths, the way real
// Android maps the installed package. pairip's integrity VM iterates the mapped
// APK/split regions (docs/pairip-vm-oracle-fork-ptrace-is-the-lever_2026-09-07.md
// UPDATE 5: device maps 5 .apk files; its loop runs 5x; our maps has 0 -> loop
// skipped -> crash epilogue). This presents the GENUINE APK bytes in Android's
// faithful mapped form -- a tampered APK still hashes wrong, so detection is
// preserved. Spec: "hostpath|guestpath;hostpath|guestpath;..." (guest = full
// /data/app/.../name.apk). Default OFF. If it CONFIRMS the loop, promote to the
// real install-path-derived mapping and remove the env parse.
struct MacapkMappedApk { uint64_t addr; uint64_t size; std::string guest; };
static const std::vector<MacapkMappedApk>& macapk_mapped_apks() {
    static std::vector<MacapkMappedApk> v = []{
        std::vector<MacapkMappedApk> out;
        const char* spec = std::getenv("MACAPK_MAP_APK_SPEC");
        if (!spec || !*spec) return out;
        std::string s(spec); size_t pos = 0;
        while (pos < s.size()) {
            size_t semi = s.find(';', pos);
            std::string tok = s.substr(pos, semi==std::string::npos?std::string::npos:semi-pos);
            pos = (semi==std::string::npos)?s.size():semi+1;
            size_t bar = tok.find('|'); if (bar==std::string::npos) continue;
            std::string host = tok.substr(0,bar), guest = tok.substr(bar+1);
            if (host.empty()||guest.empty()) continue;
            int fd = ::open(host.c_str(), O_RDONLY); if (fd<0) continue;
            struct stat st; if (::fstat(fd,&st)!=0 || st.st_size<=0){ ::close(fd); continue; }
            void* p = ::mmap(nullptr,(size_t)st.st_size,PROT_READ,MAP_PRIVATE,fd,0);
            ::close(fd);
            if (p==MAP_FAILED) continue;
            out.push_back({(uint64_t)(uintptr_t)p,(uint64_t)st.st_size,guest});
            std::fprintf(stderr,"[map-apk] mapped %s (0x%llx bytes) as %s @%p\n",
                         host.c_str(),(unsigned long long)st.st_size,guest.c_str(),p);
        }
        std::fflush(stderr);
        return out;
    }();
    return v;
}

#include "pairip_fake_modules.h"
// String form of macapk_write_map_line (so we can merge+sort real regions with the
// faithful fake module set for MACAPK_PAIRIP_FAKE_MODULES).

// MACAPK_ANDROID_MAPS_PATHS (default OFF): present GUEST LIBRARIES in /proc/self/maps under
// the Android path a device would show, instead of the macOS host path they are loaded from.
//
// MEASURED 2026-09-10: our maps text contained 79 host-path lines and ZERO /data/app lines,
// and libpairipcore appeared SEVEN times under
//   /Applications/Minecraft.app/.../_apkt/lib/arm64-v8a/libpairipcore.so   and
//   /Applications/Minecraft.app/.../lib_native/libpairipcore.dylib
// where a device shows exactly one path shape, /data/app/~~<h>/<pkg>-<h>/lib/arm64/<name>.so.
// A guest that looks itself up in maps -- pairip enumerates map_files and readlinks its own
// mapping -- therefore sees a path no Android device ever produces. The guest ELF's own
// PT_LOAD lines were already Android-shaped via macapk_guest_lib_dir(); these are the HOST's
// mappings of the same files, emitted by the census with e.name verbatim.
//
// Honest: the library genuinely exists and is genuinely mapped. This only translates its
// PATH into the Android form, exactly as the PT_LOAD emitter already does -- it does not
// invent a mapping or hide a real one.
static bool macapk_android_maps_paths_on() {
    static const bool on = []{
        bool v = __macapk_pairip_surface_on("MACAPK_ANDROID_MAPS_PATHS");
        std::fprintf(stderr, "[maps-paths] android guest-lib path translation %s\n",
                     v ? "ENABLED" : "off");
        std::fflush(stderr);
        return v;
    }();
    return on;
}
static std::string macapk_maps_android_path(const std::string& host) {
    if (host.empty()) return host;
    // v1.0.1588: the libraries are INSTALLED under <root>/data/app/~~<nonce>/
    // <pkg>-<nonce>/lib/<isa>/ (Phase 4 of docs/disk-footprint-and-space-
    // efficiency_2026-09-19.md), so the device path is the host path with the
    // android root removed -- no relabelling of a code-dir shape needed. The
    // MACAPK_PAIRIP_INSTALL_NONCE measurement knob still applies (below).
    {
        size_t da = host.find("/data/app/~~");
        if (da != std::string::npos) {
            std::string out = host.substr(da);
            if (const char* nv = std::getenv("MACAPK_PAIRIP_INSTALL_NONCE")) {
                std::string sv(nv); size_t colon = sv.find(':');
                if (colon != std::string::npos) {
                    const std::string ndir0 = "m4c4pk0Zx9AbC1dEfGhIjw==", nsuf0 = "Qm4c4pk0Zx9AbC1dEfGh8Q==";
                    size_t a = out.find(ndir0);
                    if (a != std::string::npos) out.replace(a, ndir0.size(), sv.substr(0, colon));
                    size_t b = out.find(nsuf0);
                    if (b != std::string::npos) out.replace(b, nsuf0.size(), sv.substr(colon + 1));
                }
            }
            if (out.size() > 6 && out.compare(out.size() - 6, 6, ".dylib") == 0)
                out = out.substr(0, out.size() - 6) + ".so";
            return out;
        }
    }
    // DEVICE-FAITHFUL /data/app path (2026-09-11): pairip walks /proc/self/maps (and readlinks
    // /proc/self/map_files/<addr>) and memcmp's each app-mapping path against "/data/app"
    // (+0x2ef64); the result becomes buf[0x437], which cascades 0x437->0x205c0->0x4b6f6->the
    // outer-pc hash and is the o=25971 frontier root (CMPDUMP proof, docs/pairip-outer-vm-true-roots).
    // Real Android maps the whole installed package under /data/app/~~<h>/<pkg>-<h>/, with libs
    // at .../lib/arm64/. Our app lives under "<root>/.macapk/code/<pkg>_<ver>/", with libs under
    // "_apkt/lib/arm64-v8a/". Rewrite ANY app-code-dir path to the device shape (not just libs):
    // the code-dir root itself is what pairip checks. The bytes genuinely exist at the host path
    // (dlopen/mmap are unaffected -- this only shapes the PATH STRING pairip reads); a tampered
    // file still hashes wrong, so detection is preserved.
    size_t code = host.find(".macapk/code/");
    if (code == std::string::npos) return host;      // host frameworks: MACAPK_PAIRIP_HIDE_HOST
    size_t p0 = code + 13;                            // start of "<pkg>_<ver>"
    size_t segEnd = host.find('/', p0);               // end of the "<pkg>_<ver>" segment
    if (segEnd == std::string::npos) segEnd = host.size();
    std::string pkgver = host.substr(p0, segEnd - p0);
    std::string pkg = pkgver;                          // strip "_<ver>" -> package name
    size_t us = pkgver.find('_'); if (us != std::string::npos) pkg = pkgver.substr(0, us);
    // The device install dir. The ~~<h> / -<h> hashes are opaque install nonces on a device.
    // MEASURED 2026-09-16: pairip FNV-1-64-hashes this FULL nonce-path (dir + each split*.apk)
    // and stores it to buf[0x36053] (a frontier-diff root candidate) -- so our SYNTHETIC nonce
    // makes buf[0x36053] differ from a device's. Since Android generates a random per-install
    // nonce and pairip passes on every install (all different nonces -> all different buf[0x36053]),
    // the value should be BENIGN; MACAPK_PAIRIP_INSTALL_NONCE="<dir>:<suffix>" lets us present a
    // captured device's EXACT nonce to MEASURE whether matching buf[0x36053] moves the frontier
    // (proves benign if it does not). Default keeps the synthetic nonce.
    std::string ndir = "m4c4pk0Zx9AbC1dEfGhIjw==", nsuf = "Qm4c4pk0Zx9AbC1dEfGh8Q==";
    if (const char* nv = std::getenv("MACAPK_PAIRIP_INSTALL_NONCE")) {
        std::string s(nv); size_t colon = s.find(':');
        if (colon != std::string::npos) { ndir = s.substr(0, colon); nsuf = s.substr(colon + 1); }
    }
    std::string dinst = "/data/app/~~" + ndir + "/" + pkg + "-" + nsuf;
    std::string rest = host.substr(segEnd);            // everything after "<pkg>_<ver>", incl leading '/'
    // Collapse our extraction layout to Android's: "_apkt/lib/arm64-v8a" -> "lib/arm64".
    size_t apktlib = rest.find("/_apkt/lib/arm64-v8a");
    if (apktlib != std::string::npos)
        rest = rest.substr(0, apktlib) + "/lib/arm64" + rest.substr(apktlib + 20);
    std::string out = dinst + rest;
    // Our Mach-O wrapper name (.dylib) -> the guest's own .so name.
    if (out.size() > 6 && out.compare(out.size() - 6, 6, ".dylib") == 0)
        out = out.substr(0, out.size() - 6) + ".so";
    static int announced = 0;
    if (announced < 8) { ++announced;
        std::fprintf(stderr, "[maps-paths] %s -> %s\n", host.c_str(), out.c_str());
        std::fflush(stderr); }
    return out;
}

static std::string macapk_format_map_line(const MacApkVmRegion& r, const char* name,
                                          uint64_t file_off, uint32_t maj, uint32_t min_, uint64_t ino) {
    char perms[5];
    perms[0] = (r.prot & VM_PROT_READ)    ? 'r' : '-';
    perms[1] = (r.prot & VM_PROT_WRITE)   ? 'w' : '-';
    perms[2] = (r.prot & VM_PROT_EXECUTE) ? 'x' : '-';
    perms[3] = macapk_region_shared(r)    ? 's' : 'p';
    perms[4] = 0;
    char head[128];
    int n = std::snprintf(head, sizeof head, "%08llx-%08llx %s %08llx %02x:%02x %llu",
                          (unsigned long long)r.lo, (unsigned long long)r.hi, perms,
                          (unsigned long long)file_off, (unsigned)maj, (unsigned)min_,
                          (unsigned long long)ino);
    std::string line(head, n > 0 ? n : 0);
    line += ' '; int col = n + 1;
    if (name && *name) { while (col < 72) { line += ' '; ++col; } line += ' '; line += name; }
    line += '\n';
    return line;
}
// pairip dl_iterate_phdr COHERENCE (MACAPK_PAIRIP_FAKE_MODULES): report the SAME
// faithful module set that FAKE_MODULES puts in /proc/self/maps so pairip's
// integrity VM sees a COHERENT enumeration across both surfaces. A real Android
// process's dl_iterate_phdr and /proc/self/maps agree; by default ours did not
// (dl_iterate_phdr emitted only libart + guest ELFs while maps emitted the 1253-
// line device set), so the FNV hash pairip folds over the phdr enumeration into
// its own T program buffer did not match the device-computed expected value ->
// wrong branch -> poison record -> +0x440c4 (docs/pairip-jnienv-table-fingerprint-
// wall_2026-09-08.md, 2026-09-09 T-buffer finding). dl_iterate_phdr reports LINK-
// MAP objects (dlopen'd/NEEDED shared objects), NOT every mmap, so emit only the
// .so entries (+ app_process64/linker64); a module's consecutive segments become
// ONE entry with PT_LOAD phdrs. Apply the SAME libart->JNIEnv-region rebasing the
// maps writer does, so the two surfaces agree on where libart is. The app's own
// libs are NOT in the device set (only system libs), so the guest ELFs the caller
// appends do not duplicate these. Presenting the genuine Android module view is
// the product thesis; the real anti-debug is untouched and a tampered process
// still hashes wrong. 2026-09-09.
extern "C" int __macapk_pairip_iterate_fake_modules(
        int (*cb)(const void* base, const void* phdrs, uint16_t phnum,
                  const char* name, void* user),
        void* user) {
    typedef uintptr_t (*fl_fn)(uintptr_t*);
    static fl_fn s_fl = reinterpret_cast<fl_fn>(dlsym(RTLD_DEFAULT, "__macapk_pairip_fake_libart"));
    uintptr_t jni_sz = 0, jni_base = s_fl ? s_fl(&jni_sz) : 0;
    uint64_t libart_dev_base = 0;
    if (jni_base)
        for (int i = 0; i < g_pairip_fake_modules_n; ++i)
            if (std::strstr(g_pairip_fake_modules[i].path, "libart.so"))
                if (!libart_dev_base || g_pairip_fake_modules[i].lo < libart_dev_base)
                    libart_dev_base = g_pairip_fake_modules[i].lo;
    // Elf64_Phdr: p_type@0,p_flags@4,p_offset@8,p_vaddr@0x10,p_paddr@0x18,
    // p_filesz@0x20,p_memsz@0x28,p_align@0x30 (size 0x38).
    struct Phdr { uint32_t type, flags; uint64_t offset, vaddr, paddr, filesz, memsz, align; };
    auto is_so = [](const char* p)->bool {
        size_t n = std::strlen(p);
        if (n >= 3 && std::strcmp(p + n - 3, ".so") == 0) return true;
        if (std::strstr(p, "/app_process64")) return true;
        if (std::strstr(p, "/linker64"))      return true;
        return false;
    };
    // Emit ONE object per UNIQUE path: gather ALL of that path's segment lines into a
    // single dl_iterate_phdr entry with its segments as PT_LOAD phdrs. A real
    // dl_iterate_phdr emits exactly one dl_phdr_info per LOADED OBJECT (link_map entry),
    // never the same object twice and never a single object's segments across multiple
    // callbacks. The fake-module array is ADDRESS-sorted, so a library whose segments are
    // interrupted by another module's segment (or an anon gap) used to be split into 2-3
    // separate objects by the old consecutive-run grouping (libmaesdk was forwarded 3x,
    // total ~300+ objects vs a device's 225). Gathering globally by path fixes that:
    // exactly one object per soname, all its PT_LOADs together. 2026-09-12.
    // MACAPK_PAIRIP_DLORDER_VERBATIM (default OFF, 2026-10-12 Wave-2 fleet): make the dl_iterate
    // 0x6c548 basename-FNV fold device-verbatim. (a) emit_path emits a synthetic object for a
    // solist name missing from the maps/fake_modules table (libMediaDecoders: its solist path
    // carries the device REAL nonce, fake_modules the SYNTHETIC nonce -> exact-strcmp miss ->
    // historically a silent drop from an ORDER-SENSITIVE fold). (b) the solist path then skips
    // emit_real_applibs()/emit_extras() -- the device folds ONLY its 316 solist entries at
    // 0x6c548, never the app's own loaded libs (dl_order_devfold line 311 is the ONLY /data/app
    // lib). See docs/pairip-523-genuine-vm-frontier_2026-10-09.md cont.135.
    static const bool s_dlverbatim = std::getenv("MACAPK_PAIRIP_DLORDER_VERBATIM") != nullptr;
    auto emit_path = [&](const char* path) -> int {
        bool is_art = std::strstr(path, "libart.so") != nullptr;
        uint64_t base = ~0ULL;
        for (int k = 0; k < g_pairip_fake_modules_n; ++k)
            if (std::strcmp(g_pairip_fake_modules[k].path, path) == 0 &&
                g_pairip_fake_modules[k].lo < base)
                base = g_pairip_fake_modules[k].lo;
        if (base == ~0ULL) {
            if (!s_dlverbatim) return 0;
            // The device folds this name into the order-sensitive 0x6c548 basename-FNV even
            // though we have no maps entry for it; emit a synthetic minimal object so the
            // basename still folds (the fold uses only the basename -- base/phdr are irrelevant
            // to it; this object is not pairip's own image, so the containment test ignores it).
            Phdr phs[1]; phs[0].type = 1; phs[0].flags = 5; phs[0].offset = 0; phs[0].vaddr = 0;
            phs[0].paddr = 0; phs[0].filesz = 0x1000; phs[0].memsz = 0x1000; phs[0].align = 0x1000;
            static uint64_t s_synbase = 0x7b00000000ull; s_synbase += 0x200000ull;
            std::fprintf(stderr, "[dlverbatim] synthesized missing solist object: %s\n", path);
            return cb(reinterpret_cast<const void*>(s_synbase), phs, 1, path, user);
        }
        if (is_art && jni_base && libart_dev_base)
            base = (uint64_t)jni_base + (base - libart_dev_base);
        Phdr ph[48]; int np = 0;
        for (int k = 0; k < g_pairip_fake_modules_n && np < 48; ++k) {
            const MacApkFakeModLine& m = g_pairip_fake_modules[k];
            if (std::strcmp(m.path, path) != 0) continue;
            uint64_t lo = m.lo;
            if (is_art && jni_base && libart_dev_base)
                lo = (uint64_t)jni_base + (m.lo - libart_dev_base);
            uint32_t pf = ((m.prot & VM_PROT_READ)    ? 4u : 0u)
                        | ((m.prot & VM_PROT_WRITE)   ? 2u : 0u)
                        | ((m.prot & VM_PROT_EXECUTE) ? 1u : 0u);
            ph[np].type = 1; ph[np].flags = pf; ph[np].offset = m.foff;
            ph[np].vaddr = lo - base; ph[np].paddr = 0;
            ph[np].filesz = m.hi - m.lo; ph[np].memsz = m.hi - m.lo; ph[np].align = 0x1000;
            ++np;
        }
        // CAUSALITY PROBE (MACAPK_PAIRIP_PHDR_PAD_PAGES=<N>, default OFF): add N*4K to the LAST
        // PT_LOAD memsz of every emitted .so object. If buf[0x10f9c] then increases by N*(#objects)
        // pages, buf[0x10f9c] IS the Sum-of-PT_LOAD-memsz-pages fold (2026-09-12 hypothesis test).
        {
            static const long s_pad = []{ const char* e = std::getenv("MACAPK_PAIRIP_PHDR_PAD_PAGES");
                return e ? std::strtol(e, nullptr, 0) : 0; }();
            if (s_pad > 0 && np > 0) ph[np-1].memsz += (uint64_t)s_pad * 0x1000ull;
        }
        // PROBE (MACAPK_PAIRIP_PHDR_REALBASE, default OFF): real readable base for every module.
        // REFUTED 2026-09-12 (buf[0x1a810] stayed 1) -- pairip's fold count does not gate on base
        // readability; kept as a disabled diagnostic.
        if (jni_base && !is_art) {
            static const bool s_realbase = std::getenv("MACAPK_PAIRIP_PHDR_REALBASE") != nullptr;
            if (s_realbase) {
                base = (uint64_t)jni_base;
                ph[0].type = 1; ph[0].flags = 5; ph[0].offset = 0; ph[0].vaddr = 0;
                ph[0].paddr = 0; ph[0].filesz = 0x1000; ph[0].memsz = 0x1000; ph[0].align = 0x1000;
                np = 1;
            }
        }
        return cb(reinterpret_cast<const void*>(base), ph, (uint16_t)np, path, user);
    };
    // MACAPK_PAIRIP_PHDR_SYSFIRST (default OFF): emit system/apex/framework objects BEFORE the
    // app's /data/app libs, matching a device link-map's LOAD order (linker64 + bionic + system
    // libs first, app libs last). pairip's dl_iterate_phdr fold (buf[0x1a810]) is order-sensitive
    // -- our address-sorted set puts app libs early, which appears to cap the fold count at ~1
    // (device counts ~121). A/B lever; flip default-ON if it advances the frontier past rec 16438.
    static const bool s_sysfirst = std::getenv("MACAPK_PAIRIP_PHDR_SYSFIRST") != nullptr;
    auto run_pass = [&](int mode) -> int {          // 0 = non-app, 1 = app-only, 2 = all
        // Iterate UNIQUE paths in first-appearance order; emit_path() gathers all of a
        // path's segments globally, so a path split across non-adjacent array entries is
        // still emitted exactly once (no per-segment duplicate objects).
        for (int i = 0; i < g_pairip_fake_modules_n; ++i) {
            const char* path = g_pairip_fake_modules[i].path;
            bool first = true;
            for (int k = 0; k < i; ++k)
                if (std::strcmp(g_pairip_fake_modules[k].path, path) == 0) { first = false; break; }
            if (!first) continue;
            bool is_app = std::strncmp(path, "/data/app", 9) == 0;
            bool want = (mode == 2) || (mode == 0 && !is_app) || (mode == 1 && is_app);
            if (is_so(path) && want) { int r = emit_path(path); if (r != 0) return r; }
        }
        return 0;
    };
    // MACAPK_PAIRIP_PHDR_EXTRA=<N> (DIAGNOSTIC, default OFF): after the real modules, forward N
    // synthetic device-shaped /system/lib64 .so objects. Tests whether buf[0x10f9c] (the writer-RE-
    // attributed dl_iterate fold) tracks the enumerated OBJECT COUNT -- if buf[0x10f9c]@rec10500
    // moves by ~N (or ~N*bytes), it is a live module fold (pursue the exact device set); if it stays
    // 28575, buf[0x10f9c] is INVARIANT to the module set (the count attribution is a FAKE_MODULES-
    // unaware misread, like buf[0x1a810]) -> pivot. 2026-09-12.
    auto emit_extras = [&]() -> int {
        static const long nx = []{ const char* e = std::getenv("MACAPK_PAIRIP_PHDR_EXTRA");
                                   return e ? std::strtol(e, nullptr, 10) : 0L; }();
        for (long k = 0; k < nx; ++k) {
            char nm[80]; std::snprintf(nm, sizeof nm, "/system/lib64/libmacapkx%03ld.so", k);
            Phdr ph[1]; ph[0].type = 1; ph[0].flags = 5; ph[0].offset = 0; ph[0].vaddr = 0;
            ph[0].paddr = 0; ph[0].filesz = 0x1000; ph[0].memsz = 0x1000; ph[0].align = 0x1000;
            uint64_t base = 0x7a00000000ull + (uint64_t)k * 0x200000ull;
            int r = cb(reinterpret_cast<const void*>(base), ph, 1, nm, user);
            if (r != 0) return r;
        }
        return 0;
    };
    // Emit the app's OWN loaded native libs (libpairipcore, libminecraftpe) with their REAL
    // Stage-6 base+phdrs, under a device-shaped /data/app path. The FAKE_MODULES generator
    // EXCLUDES them (they are the real loaded guest libs, not device-address fakes) -- but that
    // left dl_iterate_phdr INCOHERENT with /proc/self/maps (which DOES include them), and left
    // pairip unable to find its OWN image (libpairipcore) via dl_iterate, a classic anti-tamper
    // self-verification surface. Emitting them with REAL derefable bases lets pairip's
    // address-in-module / own-segment checks resolve. Gate MACAPK_PAIRIP_NO_REALLIBS for A/B.
    auto emit_real_applibs = [&]() -> int {
        if (std::getenv("MACAPK_PAIRIP_NO_REALLIBS")) return 0;
        // v1.0.1588: the running package's own install dir (was a Minecraft
        // literal), device-shaped by the same rewrite the maps writer uses.
        const char* inst = __macapk_install_dir();
        if (!inst) return 0;
        const std::string prefix = macapk_maps_android_path(std::string(inst) + "/lib/arm64/");
        const char* const kPrefix = prefix.c_str();
        // Every guest image the app itself loaded (its own lib/<isa> set),
        // not a name list: the fake-module table excludes the app's libs
        // because they are REAL loaded images, so this is where they enter
        // dl_iterate_phdr, and it has to be every one of them for the
        // callback to be coherent with /proc/self/maps (2026-09-20 review:
        // a libpairipcore/libminecraftpe name filter left every other pairip
        // app's own libs out of the phdr walk).
        for (Image* im : loaded_images()) {
            if (!im) continue;
            const char* nm = im->name(); if (!nm) continue;
            const char* leaf = std::strrchr(nm, '/'); leaf = leaf ? leaf + 1 : nm;
            if (std::strncmp(leaf, "lib", 3) != 0) continue;
            char full[256]; std::snprintf(full, sizeof full, "%s%s", kPrefix, leaf);
            int r = cb(reinterpret_cast<const void*>(im->base()),
                       im->phdrs_data(), (uint16_t)im->phdr_count(), full, user);
            if (r) return r;
        }
        return 0;
    };
    // FAITHFUL dl_iterate_phdr (default under FAKE_MODULES): a real dl_iterate_phdr reports the
    // caller namespace's SOLIST (loaded objects) in LINKER LOAD ORDER -- a SUBSET of /proc/self/maps
    // (which shows every mapping across every linker namespace, address-sorted) and WITH [vdso].
    // Our old address-sorted full-maps-.so walk emitted ~303 objects (incl. ~153 other-namespace
    // HAL/hidl libs the app's dl_iterate never sees) and DROPPED [vdso] (the is_so filter), in the
    // wrong order -- measured against the device capture dev_dl_iterate_order.txt (139 objects, load
    // order, with [vdso]). Emit that captured solist order instead, resolving each name's base+phdrs
    // from the maps table (emit_path); "/system/bin/linker64" is not a file-backed maps .so row so
    // synthesize a minimal R-X object to lead the list the way every Android process does; then the
    // app's own libs (emit_real_applibs) + extras. The maps writer is unchanged (maps stays the full
    // set). Kill switch MACAPK_PAIRIP_PHDR_ADDRORDER reverts to the old address-sorted walk for A/B.
    static const bool s_addrorder = std::getenv("MACAPK_PAIRIP_PHDR_ADDRORDER") != nullptr;
    if (!s_addrorder && g_pairip_solist_order_n > 0) {
        for (int i = 0; i < g_pairip_solist_order_n; ++i) {
            const char* nm = g_pairip_solist_order[i];
            int r;
            if (std::strcmp(nm, "/system/bin/linker64") == 0) {
                static uint64_t s_lk[7] = {0};
                uint32_t* lp = reinterpret_cast<uint32_t*>(&s_lk[0]);
                lp[0] = 1; lp[1] = 5;                 // p_type = PT_LOAD, p_flags = R+X
                s_lk[2] = 0; s_lk[5] = 0x40000;       // p_vaddr = 0 (base is the arg), p_memsz
                r = cb(reinterpret_cast<const void*>(0x7000000000ull), s_lk, 1, nm, user);
            } else {
                r = emit_path(nm);                    // [vdso] + the system/apex solist (table lookup)
            }
            if (r) return r;
        }
        // VERBATIM: the device folds ONLY the 316 solist entries at 0x6c548, never the app's own
        // loaded libs (libpairipcore/libminecraftpe absent from dl_order_devfold); appending them
        // corrupts the order-sensitive fold. Default OFF keeps the historical append.
        if (s_dlverbatim) { std::fprintf(stderr, "[dlverbatim] solist emit complete; skipped emit_real_applibs + emit_extras\n"); return 0; }
        int r = emit_real_applibs(); if (r) return r;
        return emit_extras();
    }
    if (s_sysfirst) { int r = run_pass(0); if (r) return r; r = run_pass(1); if (r) return r;
                      r = emit_real_applibs(); if (r) return r; return emit_extras(); }
    { int r = run_pass(2); if (r) return r; r = emit_real_applibs(); if (r) return r; return emit_extras(); }
}
extern "C" uintptr_t __macapk_pairip_fake_libart(uintptr_t* out_size);  // defined in bionic_shim.cpp
void __macapk_arm64_native_write_maps(void* filep) {
    FILE* f = static_cast<FILE*>(filep);
    if (!f) return;
    // MACAPK_PAIRIP_DEVMAPS_FILE (DIAGNOSTIC, default OFF, 2026-09-12): emit a captured DEVICE
    // /proc/self/maps VERBATIM instead of our synthesized one. Tests whether the maps CONTENT is
    // causal for +0x440c4 (our synthesized maps is structurally INCOMPLETE vs a real running MC --
    // missing MC's app libs libminecraftpe/libfmod/..., base.odex/base.art, and ART's named-anon
    // scudo/dalvik regions -- since presenting a faithful ART-shaped MAPS is in-scope, unlike the
    // JNIEnv which needs real ART functions). Binary metric: does MC pass +0x440c4? CAVEAT: the file
    // carries the DEVICE's addresses, so pairip's libart-LOCATE deref may break (crash earlier) --
    // reaching +0x440c4 unchanged means the maps didn't matter; passing it means the maps was causal.
    {
        // Resolve the device-maps file: the explicit flag wins, else the
        // MACAPK_PAIRIP_DEVPROC_DIR umbrella's <dir>/maps.txt (cont.52-53), so a
        // single flag can serve device-faithful /proc/self/{maps,map_files,fd}.
        static const std::string s_devmaps_str = []{
            if (const char* e = (__macapk_pairip_surface_on("MACAPK_PAIRIP_DEVMAPS_FILE") ? std::getenv("MACAPK_PAIRIP_DEVMAPS_FILE") : nullptr)) { if (*e) return std::string(e); }
            if (const char* d = std::getenv("MACAPK_PAIRIP_DEVPROC_DIR")) {
                if (*d) { std::string p = d; if (p.back() != '/') p += '/'; return p + "maps.txt"; }
            }
            return std::string();
        }();
        const char* s_devmaps = s_devmaps_str.empty() ? nullptr : s_devmaps_str.c_str();
        if (s_devmaps && *s_devmaps) {
            // Read the whole captured device maps.
            FILE* d = std::fopen(s_devmaps, "r");
            if (d) {
                std::string all; char rb[8192]; size_t n;
                while ((n = std::fread(rb, 1, sizeof rb, d)) > 0) all.append(rb, n);
                std::fclose(d);
                // MACAPK_PAIRIP_DEVMAPS_REBASE_LIBART: rebase every libart.so line onto our REAL
                // fake-libart region so pairip's libart-LOCATE (JNIEnv resolve) derefs a real region,
                // while keeping the device's COMPLETE structure. Two passes: find the device libart
                // base (lowest libart line start), then substitute addr -> our_base + (addr - dev_base).
                uintptr_t our_libart_sz = 0;
                uintptr_t our_libart = std::getenv("MACAPK_PAIRIP_DEVMAPS_REBASE_LIBART")
                                       ? __macapk_pairip_fake_libart(&our_libart_sz) : 0;
                if (our_libart) {
                    uint64_t dev_libart_base = ~0ULL;
                    { size_t p = 0; while (p < all.size()) {
                        size_t e = all.find('\n', p); if (e == std::string::npos) e = all.size();
                        std::string ln = all.substr(p, e - p);
                        if (ln.find("libart.so") != std::string::npos) {
                            uint64_t lo = std::strtoull(ln.c_str(), nullptr, 16);
                            if (lo < dev_libart_base) dev_libart_base = lo;
                        }
                        p = e + 1; } }
                    size_t p = 0; int reb = 0;
                    while (p < all.size()) {
                        size_t e = all.find('\n', p); if (e == std::string::npos) e = all.size();
                        std::string ln = all.substr(p, e - p);
                        if (dev_libart_base != ~0ULL && ln.find("libart.so") != std::string::npos) {
                            char* dash = nullptr;
                            uint64_t lo = std::strtoull(ln.c_str(), &dash, 16);
                            uint64_t hi = (dash && *dash == '-') ? std::strtoull(dash + 1, nullptr, 16) : lo;
                            const char* rest = std::strchr(ln.c_str(), ' ');
                            uint64_t nlo = our_libart + (lo - dev_libart_base);
                            uint64_t nhi = our_libart + (hi - dev_libart_base);
                            std::fprintf(f, "%lx-%lx%s\n", (unsigned long)nlo, (unsigned long)nhi,
                                         rest ? rest : "");
                            ++reb;
                        } else {
                            std::fwrite(ln.c_str(), 1, ln.size(), f); std::fputc('\n', f);
                        }
                        p = e + 1;
                    }
                    std::fprintf(stderr, "[devmaps] emitted from %s, rebased %d libart lines onto %#lx\n",
                                 s_devmaps, reb, (unsigned long)our_libart);
                } else {
                    std::fwrite(all.data(), 1, all.size(), f);
                    std::fprintf(stderr, "[devmaps] emitted verbatim from %s (%zu bytes)\n", s_devmaps, all.size());
                }
                return;
            }
            std::fprintf(stderr, "[devmaps] FAILED to open %s\n", s_devmaps);
        }
    }
    if (!macapk_proc_full_maps()) { macapk_write_guest_only_maps(f); return; }
    // MACAPK_PAIRIP_FAKE_MODULES: emit a FAITHFUL genuine-Android module set (transcribed
    // from a passing device) merged with our real regions, sorted by address, so pairip's
    // environment loop (which reads the /proc/self/maps module set + libart 4-segment
    // structure) runs as on a device instead of navigating to the poison record.
    if (__macapk_pairip_surface_on("MACAPK_PAIRIP_FAKE_MODULES")) {
        std::lock_guard<std::mutex> g(macapk_census_mutex());
        const MacApkNameIndex& idx = macapk_name_index();
        std::vector<MacApkVmRegion> regions;
        macapk_enumerate_regions(regions);
        std::vector<std::pair<uint64_t, std::string>> lines;
        // MACAPK_PAIRIP_HIDE_HOST: a real Android process has NO macOS host regions.
        // Our real census includes the runtime binary, the JVM (/opt/homebrew
        // openjdk*.dylib x37), ANGLE/Metal .dylibs, capstone, libpng, /usr/lib --
        // ~79 lines with /opt,/usr,/System,/Library,/private paths or a .dylib suffix
        // that pairip reads out of /proc/self/maps and folds into its integrity
        // accumulator (a wrong byte tips its mod-186 branch bucket -> poison). Drop
        // them so maps shows only Android-shaped entries (fake module set + the guest
        // app's own .so + anon/stack/heap), matching a device. Faithful: we are not
        // hiding tampering, only omitting host artifacts a device never has.
        static const bool s_hide_host = __macapk_pairip_surface_on("MACAPK_PAIRIP_HIDE_HOST");
        // MACAPK_PAIRIP_HIDE_MIDVA (default OFF, A/B lever, use WITH MACAPK_PAIRIP_HIVA):
        // a real ART process's /proc/self/maps has regions only at LOW device VAs (dalvik heap
        // 0x12c.., boot.art/system libs <4GB) and HIGH device VAs (app_process64 0x62e3.., base.apk
        // 0x79f0.., libpairipcore 0x79f5.. -- all >384GB); it has NOTHING in the 4GB..384GB band.
        // Our census, however, packs macOS host allocations (JVM/Metal/GC heaps, JIT code caches,
        // reserved ---p) into that band, and (without HIVA) our guest ELFs too -- which sort into the
        // gap and break pairip's line-by-line maps perm-fold order (measured: shipped synth diverges
        // at the 0x1xx host cluster, honest-oracle rec 16783). MACAPK_PAIRIP_HIVA relocates the guest
        // ELFs to >384GB (~0x70..), so with HIVA the [4GB,384GB) band is PURE host junk a device never
        // has -- drop the whole band. Faithful: the device's app heap/anon are provided device-shaped
        // by the fake module set; we are only omitting macOS host artifacts. docs/pairip-maps-coherence-breakthrough_2026-09-14.md §4e
        static const bool s_hide_midva = std::getenv("MACAPK_PAIRIP_HIDE_MIDVA") != nullptr;
        // MACAPK_PAIRIP_HIDE_ALLVA (default OFF, §4h drop-all-census leap): drop EVERY census region
        // at a high VA (>=4GB) -- not just the [4GB,384GB) band -- so the maps becomes purely
        // FAKE_MODULES (the device platform) + the synthesized guest ELFs, leaping the per-region tail
        // of our-process regions the device lacks (the 1MB rw-p after libpairipcore, >384GB host junk).
        // Checked AFTER the guest-ELF skips (fake-libart, PAIRIP_SEGS) so those survive. CAVEAT: the
        // device's plain-anon/[stack]/[heap] (which FAKE_MODULES excludes) are then MISSING -- an A/B
        // lever to measure whether that trade advances or regresses. docs §4h.
        static const bool s_hide_allva = std::getenv("MACAPK_PAIRIP_HIDE_ALLVA") != nullptr;
        // MACAPK_PAIRIP_MAPS_COALESCE (default OFF): the kernel shows ONE /proc/maps
        // line per VMA, merging contiguous same-perm same-file same-offset-run mappings
        // (vma_merge). Our mach census splits libpairipcore's .text into TWO adjacent
        // r-x lines ([0..0x90000]+[0x90000..0x94000]) where a device shows a single
        // r-x [0..0x94000]. A self-integrity check that reads libpairipcore's first r-x
        // line size from maps then hashes [base,size] would hash 0x90000 bytes on us vs
        // 0x94000 on a device -> a wrong hash -> the mod-186 branch tips -> poison. Merge
        // adjacent regions like the kernel does. Faithful (not hiding anything; matching
        // the kernel's own line-merging). A/B lever until shown to advance the oracle.
        static const bool s_coalesce = std::getenv("MACAPK_PAIRIP_MAPS_COALESCE") != nullptr;
        auto is_macos_host = [](const std::string& p)->bool {
            if (p.empty()) return false;
            if (p.size() > 6 && p.compare(p.size()-6, 6, ".dylib") == 0) return true;
            if (p.rfind("/opt/", 0) == 0 || p.rfind("/usr/", 0) == 0 ||
                p.rfind("/System/", 0) == 0 || p.rfind("/Library/", 0) == 0 ||
                p.rfind("/private/", 0) == 0) return true;
            if (p.find("/Macapk/Macapk/runtime/build/") != std::string::npos) return true;
            // The macOS dyld shared cache appears as "[dyld shared cache]" (no path
            // prefix, no .dylib suffix) -- a device never has it. Drop it too.
            if (p.find("dyld shared cache") != std::string::npos) return true;
            return false;
        };
        // Collect resolved census entries first so contiguous same-perm same-file
        // segments can be merged (kernel vma_merge) before formatting.
        struct CE { MacApkVmRegion r; std::string name; uint64_t off; uint32_t maj; uint32_t min_; uint64_t ino; };
        std::vector<CE> ce;
        // Skip the census's own fake-libart region (named "libart.so"): the FAKE_MODULES loop below
        // emits the device's faithful 4-segment libart at that same base, so keeping the census copy
        // would duplicate it. (Under LIBART_DEVVA the region moves to the device VA >384GB where
        // HIDE_MIDVA does not reach, so this explicit skip is required.)
        typedef uintptr_t (*flb_fn)(uintptr_t*);
        static flb_fn s_flb = reinterpret_cast<flb_fn>(dlsym(RTLD_DEFAULT, "__macapk_pairip_fake_libart"));
        const uintptr_t fake_libart_lo = s_flb ? s_flb(nullptr) : 0;
        // MACAPK_PAIRIP_PAIRIP_SEGS (default OFF, §4g): our Stage-6 loader maps libpairipcore with
        // Apple 16KB pages + a per-host-page perm union, so its census segment lines (perms +
        // boundaries) differ from the device's 4KB-granular layout -- the residual maps divergence at
        // rec 18074. Skip the census's own libpairipcore lines (capturing our load base) and emit
        // device-faithful segment lines rebased to it (below). pairip folds the maps TEXT, and our PC
        // stays inside the reported r-xp range so the self-check still passes. docs §4g.
        static const bool s_pairip_segs = std::getenv("MACAPK_PAIRIP_PAIRIP_SEGS") != nullptr;
        uintptr_t our_pairip_base = 0; std::string our_pairip_name;
        for (const MacApkVmRegion& r : regions) {
            MacApkRegionName nm = macapk_resolve_region_name(idx, r.lo, r.hi);
            if (nm.name.empty()) {
                const char* vma = __macapk_vma_name_lookup(r.lo, r.hi - r.lo);
                if (vma && *vma) nm.name = std::string("[anon:") + vma + "]";
                nm.file_off = r.offset;
            }
            if (s_hide_host && is_macos_host(nm.name)) continue;
            if (s_hide_midva && r.lo >= 0x100000000ULL && r.lo < 0x6000000000ULL) continue;  // host-only band under HIVA
            if (fake_libart_lo && r.lo == fake_libart_lo) continue;   // FAKE_MODULES provides libart (no census dup)
            if (s_pairip_segs) {
                if (nm.name.find("libpairipcore.so") != std::string::npos) {
                    if (!our_pairip_base || r.lo < our_pairip_base) { our_pairip_base = r.lo; our_pairip_name = nm.name; }
                    continue;   // device-faithful libpairipcore segments emitted below
                }
                // Also drop any census region WITHIN the libpairipcore extent -- our loader also
                // maps a [anon:.bss] and adjacent anon/guard regions there that the device shapes
                // differently; the device-faithful table below provides the whole [base,+0xa0000).
                if (our_pairip_base && r.lo >= our_pairip_base && r.lo < our_pairip_base + 0xa0000ULL) continue;
            }
            if (s_hide_allva && r.lo >= 0x100000000ULL) continue;   // drop-all-census leap (guest ELFs already handled)
            ce.push_back(CE{r, nm.name, nm.file_off, nm.dev_major, nm.dev_minor, nm.ino});
        }
        if (s_coalesce && ce.size() > 1) {
            std::sort(ce.begin(), ce.end(), [](const CE& a, const CE& b){ return a.r.lo < b.r.lo; });
            std::vector<CE> merged;
            for (const CE& e : ce) {
                if (!merged.empty()) {
                    CE& p = merged.back();
                    bool same_perm = (p.r.prot == e.r.prot) &&
                                     (macapk_region_shared(p.r) == macapk_region_shared(e.r));
                    bool contig    = (p.r.hi == e.r.lo);
                    bool same_name = (!p.name.empty() && p.name == e.name);
                    bool contig_off= (e.off == p.off + (p.r.hi - p.r.lo));
                    if (same_perm && contig && same_name && contig_off) {
                        p.r.hi = e.r.hi;   // extend the prior VMA over this one
                        continue;
                    }
                }
                merged.push_back(e);
            }
            ce.swap(merged);
        }
        for (const CE& e : ce) {
            std::string nm = e.name;
            if (macapk_android_maps_paths_on() && !nm.empty()) nm = macapk_maps_android_path(nm);
            lines.emplace_back(e.r.lo, macapk_format_map_line(e.r, nm.empty() ? nullptr : nm.c_str(),
                                                              e.off, e.maj, e.min_, e.ino));
        }
        // §4g: emit device-faithful libpairipcore segment lines (the device's 4KB-granular perm/
        // boundary layout from dev_maps_r10256_2026-09-14.txt) rebased onto our actual load base,
        // replacing our 16KB-unioned census lines. dev fe:27 ino 41588 (the device's).
        if (s_pairip_segs && our_pairip_base) {
            // Device libpairipcore layout from dev_maps_r10256 (nametype: 0=libpairipcore.so path
            // dev fe:27 ino 41588; 1=anon ---p guard dev 00:00 ino 0; 2=[anon:.bss] dev 00:00 ino 0).
            // Device libpairipcore layout from dev_maps_r10256 (nametype: 0=libpairipcore.so path dev
            // fe:27 ino 41588; 1=anon ---p guard dev 00:00; 2=[anon:.bss] dev 00:00). The [anon:.bss]
            // and ---p guards are all emitted here -- measured: removing the .bss REGRESSED 19231->19187,
            // so the fake-module table does NOT already provide it at this position.
            static const struct { uint64_t off, len; unsigned prot; uint64_t foff; int nt; } kPPSeg[] = {
                {0x0,     0x33000, 5, 0x00000, 0},   {0x33000, 0x2000,  5, 0x33000, 0},
                {0x35000, 0x5c000, 5, 0x35000, 0},   {0x91000, 0x3000,  0, 0x00000, 1},
                {0x94000, 0x4000,  1, 0x94000, 0},   {0x98000, 0x3000,  0, 0x00000, 1},
                {0x9b000, 0x2000,  3, 0x97000, 0},   {0x9d000, 0x1000,  3, 0x00000, 2},
            };
            std::string nm = our_pairip_name;
            if (macapk_android_maps_paths_on() && !nm.empty()) nm = macapk_maps_android_path(nm);
            for (const auto& s : kPPSeg) {
                // OPTION-B (docs 4i b): when the fake-module table is the FULL maps_r10256 (plain-anon
                // included) and HIDE_ALLVA drops all census, the table already provides libpairipcore's
                // ---p guards (plain-anon) and [anon:.bss], so emit ONLY the file-backed segments here.
                if (s_hide_allva && s.nt != 0) continue;
                MacApkVmRegion r2; r2.lo = our_pairip_base + s.off; r2.hi = r2.lo + s.len;
                r2.prot = (vm_prot_t)s.prot; r2.max_prot = (vm_prot_t)s.prot; r2.offset = s.foff;
                r2.share_mode = 0;
                const char* path = nullptr; uint32_t maj = 0, min_ = 0; uint64_t ino = 0;
                if (s.nt == 0) { path = nm.empty() ? nullptr : nm.c_str(); maj = 0xfe; min_ = 0x27; ino = 41588ULL; }
                else if (s.nt == 2) { path = "[anon:.bss]"; }
                lines.emplace_back(r2.lo, macapk_format_map_line(r2, path, s.foff, maj, min_, ino));
            }
        }
        // Unify libart with the JNIEnv-fake region: MACAPK_PAIRIP_FAKE_JNIENV places the
        // REAL bridge JNIEnv fps in an mmap'd region and publishes its base. pairip's
        // JNIEnv check needs those fps to sit INSIDE the libart it locates via maps, so
        // rebase the transcribed libart segments onto that region (preserving the 4-segment
        // relative layout) instead of the header's device address -- one coherent libart.
        typedef uintptr_t (*fl_fn)(uintptr_t*);
        static fl_fn s_fl = reinterpret_cast<fl_fn>(dlsym(RTLD_DEFAULT, "__macapk_pairip_fake_libart"));
        uintptr_t jni_sz = 0, jni_base = s_fl ? s_fl(&jni_sz) : 0;
        uint64_t libart_dev_base = 0;
        if (jni_base) {
            for (int i = 0; i < g_pairip_fake_modules_n; ++i)
                if (std::strstr(g_pairip_fake_modules[i].path, "libart.so")) {
                    if (!libart_dev_base || g_pairip_fake_modules[i].lo < libart_dev_base)
                        libart_dev_base = g_pairip_fake_modules[i].lo;
                }
        }
        // MACAPK_PAIRIP_PPGUARD (default OFF, 2026-09-15): rebase libpairipcore's transcribed
        // interstitial guards + .bss onto its REAL loaded base. HIVA_EXACT hints libpairipcore to
        // 0x79f5b4f000 but mmap lands it +N (advisory, no MAP_FIXED -> +0x1000 observed); emit_real_applibs
        // emits the real NAMED segments at the real base, but the header's unnamed guards/.bss (in the
        // libpairipcore span) stayed at the 0x79f5b4f000-relative addresses and OVERLAPPED the real
        // segments -- a /proc/self/maps invariant violation (no real device has overlapping VMAs) that
        // corrupts pairip's tail region-fold (structure built 66 vs the device's 144). Shift those span
        // regions by the real delta so they track the real segments (no overlap; guards preserved).
        // docs/pairip-mapfiles-fold-80vs5_2026-09-15.md.
        const uint64_t pp_hdr_base = 0x79f5b4f000ULL, pp_span_hi = 0x79f5c00000ULL;
        uint64_t pp_real_base = 0; int64_t pp_delta = 0;
        if (std::getenv("MACAPK_PAIRIP_PPGUARD")) {
            for (Image* im : loaded_images())
                if (im && im->name() && std::strstr(im->name(), "libpairipcore")) {
                    pp_real_base = (uint64_t)im->base(); break;
                }
            if (pp_real_base && pp_real_base != pp_hdr_base) pp_delta = (int64_t)(pp_real_base - pp_hdr_base);
        }
        for (int i = 0; i < g_pairip_fake_modules_n; ++i) {
            const MacApkFakeModLine& m = g_pairip_fake_modules[i];
            // OPTION-B (docs 4i b): pairip_fake_modules.h now also carries maps_r10256's PLAIN-ANON
            // (empty path) so the FULL device maps can be reproduced. Without HIDE_ALLVA the live
            // census still provides our real anon, so SKIP the header's plain-anon then (they would
            // duplicate); with HIDE_ALLVA (census dropped) emit them -- that leap advanced 19231->133738.
            if (!s_hide_allva && (!m.path || m.path[0] == '\0')) continue;
            // v1.0.1439 (docs 4o): [vdso]/[vvar] are in the header now (every Android process has them),
            // but emit them only under HIDE_ALLVA so the shipped default frontier stays unchanged.
            if (!s_hide_allva && m.path &&
                (std::strcmp(m.path, "[vdso]") == 0 || std::strcmp(m.path, "[vvar]") == 0)) continue;
            uint64_t lo = m.lo, hi = m.hi;
            if (pp_delta && m.lo >= pp_hdr_base && m.lo < pp_span_hi) {
                lo = (uint64_t)((int64_t)m.lo + pp_delta);   // track the real libpairipcore base
                hi = (uint64_t)((int64_t)m.hi + pp_delta);
            }
            if (jni_base && libart_dev_base && std::strstr(m.path, "libart.so")) {
                lo = (uint64_t)jni_base + (m.lo - libart_dev_base);
                hi = (uint64_t)jni_base + (m.hi - libart_dev_base);
            }
            MacApkVmRegion r; r.lo = lo; r.hi = hi;
            r.prot = (vm_prot_t)m.prot; r.max_prot = (vm_prot_t)m.prot; r.offset = m.foff;
            // Preserve the device's shared/private (s/p) bit: ~209 device regions are 's'
            // (/dev/__properties__, /system/framework .jar/.oat, boot .art). SM_SHARED makes
            // macapk_region_shared() emit 's'; default share_mode 0 emits 'p'. Byte-faithful
            // maps content (pairip hashes it). 2026-09-12 review finding.
            r.share_mode = m.shared ? SM_SHARED : 0;
            lines.emplace_back(lo, macapk_format_map_line(r, m.path, m.foff, m.maj, m.min_, m.ino));
        }
        std::sort(lines.begin(), lines.end(),
                  [](const std::pair<uint64_t,std::string>& a, const std::pair<uint64_t,std::string>& b){ return a.first < b.first; });
        static std::atomic<int> s_fm{0};
        if (s_fm.fetch_add(1) < 2)
            std::fprintf(stderr, "[fake-modules] emitted %zu maps lines (%d fake modules merged)\n",
                         lines.size(), g_pairip_fake_modules_n);
        if (std::getenv("MACAPK_PAIRIP_DUMPMAPS")) {
            FILE* d = std::fopen("/tmp/our_maps.txt", "w");
            if (d) { for (const auto& pr : lines) std::fputs(pr.second.c_str(), d); std::fclose(d); }
            // Per-read dump: our FAKE_MODULES maps re-enumerates the LIVE census on every
            // /proc/self/maps read, so HotSpot/JIT allocations between reads can make our maps
            // NON-DETERMINISTIC across reads where a device's is stable -- a candidate for the
            // rec-16438 fold divergence. Number each read so we can diff read#1 vs read#N.
            static std::atomic<int> s_rd{0};
            int rd = s_rd.fetch_add(1);
            char pth[64]; std::snprintf(pth, sizeof pth, "/tmp/our_maps_rd%02d.txt", rd);
            FILE* dn = std::fopen(pth, "w");
            if (dn) { for (const auto& pr : lines) std::fputs(pr.second.c_str(), dn); std::fclose(dn); }
        }
        for (const auto& pr : lines) std::fputs(pr.second.c_str(), f);
        return;
    }
    std::lock_guard<std::mutex> g(macapk_census_mutex());
    const MacApkNameIndex& idx = macapk_name_index();
    std::vector<MacApkVmRegion> regions;
    macapk_enumerate_regions(regions);
    // MACAPK_PAIRIP_FAKE_JNIENV: name our synthetic ART-offset trampoline region
    // "libart.so" so pairip (which reads /proc/self/maps to locate libart)
    // resolves our JNIEnv fps to their genuine-ART offsets within it.
    uintptr_t fl_sz = 0, fl_base = 0;
    {
        typedef uintptr_t (*fl_fn)(uintptr_t*);
        static fl_fn s_fl = reinterpret_cast<fl_fn>(dlsym(RTLD_DEFAULT, "__macapk_pairip_fake_libart"));
        if (s_fl) fl_base = s_fl(&fl_sz);
    }
    for (const MacApkVmRegion& r : regions) {
        if (fl_base && r.lo >= fl_base && r.lo < fl_base + fl_sz) {
            static std::atomic<int> s_ll{0};
            if (s_ll.fetch_add(1) < 3)
                std::fprintf(stderr, "[fake-libart-maps] emitting libart.so line for [%llx-%llx] prot=%x\n",
                             (unsigned long long)r.lo, (unsigned long long)r.hi, r.prot);
            macapk_write_map_line(f, r, "/apex/com.android.art/lib64/libart.so",
                                  r.lo - fl_base, 0xfe, 0x0, 0xa27a);
            continue;
        }
        MacApkRegionName nm = macapk_resolve_region_name(idx, r.lo, r.hi);
        // A guest-supplied PR_SET_VMA label names an otherwise-anonymous
        // region, the same way an Android kernel prints [anon:<name>].
        if (nm.name.empty()) {
            const char* vma = __macapk_vma_name_lookup(r.lo, r.hi - r.lo);
            if (vma && *vma) nm.name = std::string("[anon:") + vma + "]";
            nm.file_off = r.offset;
        }
        // [stack]: EVERY Linux process has one, and we had none -- measured 0 occurrences in a
        // 688-line capture, with no emitter for it anywhere in the tree. The kernel labels the
        // VMA containing the main thread's stack pointer (fs/proc/task_mmu.c show_map_vma ->
        // is_stack(vma), which tests vm_start <= mm->start_stack < vm_end). We do the same
        // test against the MAIN thread's stack bounds, so the label lands on the real region
        // rather than being asserted about an arbitrary one.
        //
        // [heap], [vdso] and [vvar] are deliberately NOT emitted. Linux's [heap] is the brk
        // region and macOS has no brk heap to point at; [vdso]/[vvar] are kernel-provided
        // pages that genuinely do not exist here. Labelling something else with those names
        // would be asserting a mapping we do not have -- the opposite of what this pass is
        // for. Their ABSENCE is a real remaining divergence, recorded in
        // docs/platform-surface-audit_2026-09-11.md rather than papered over.
        if (nm.name.empty()) {
            // ⚠ It must be the MAIN thread's stack. Linux labels only that one [stack];
            // other threads' stacks are plain anonymous VMAs. This function can run on any
            // thread, so pthread_self() here would label whichever thread happened to write
            // the maps -- a different region on every capture. The main thread's bounds are
            // captured once, from a constructor, which runs on the main thread at load.
            // Linux's test is is_stack(): `vm_start <= mm->start_stack < vm_end` -- so exactly
            // ONE VMA is labelled, the one CONTAINING the initial stack pointer. An overlap
            // test instead labels the guard page too (measured: it produced two [stack] lines,
            // one of them the `---p` guard), which no kernel does.
            const uintptr_t sp0 = macapk_main_stack_hi() ? macapk_main_stack_hi() - 1 : 0;
            if (sp0 && r.lo <= sp0 && r.hi > sp0)
                nm.name = "[stack]";
        }
        // Android-shape a guest library's path (MACAPK_ANDROID_MAPS_PATHS). THIS is the
        // DEFAULT emitter -- the census loop above runs only under MACAPK_PAIRIP_FAKE_MODULES,
        // which is why translating there alone changed nothing and the gate never even
        // announced itself.
        std::string anm = nm.name;
        if (macapk_android_maps_paths_on() && !anm.empty()) anm = macapk_maps_android_path(anm);
        macapk_write_map_line(f, r, anm.empty() ? nullptr : anm.c_str(),
                              nm.file_off, nm.dev_major, nm.dev_minor, nm.ino);
    }
    // DIAGNOSTIC: append the mapped APK/split regions under /data/app paths
    // (MACAPK_MAP_APK_SPEC). See macapk_mapped_apks() above.
    uint64_t apk_ino = 41496;
    for (const auto& a : macapk_mapped_apks()) {
        MacApkVmRegion r; r.lo = a.addr; r.hi = a.addr + a.size;
        r.prot = VM_PROT_READ; r.max_prot = VM_PROT_READ;
        macapk_write_map_line(f, r, a.guest.c_str(), 0, 0xfe, 0x27, apk_ino++);
    }
}

// Synthesize /proc/self/smaps: the per-mapping detail form of maps. Every
// value is derived from a real vm_region_submap_info_data_64_t field; the ones
// Darwin genuinely does not expose are 0, which is what Linux itself reports
// on a device with no hugepages and no mlock. Nothing here is invented -- a
// malformed or fabricated smaps is WORSE than no file at all, because a parser
// may accept it and compute nonsense from it.
//
// FORMAT, pinned from linux v5.4 fs/proc/task_mmu.c show_smap()/__show_smap()
// and verified byte-exact against /proc/<pid>/smaps on an Android-11 arm64 AVD
// (debug/nativeprobe/measured-android11-arm64.txt). The label strings below
// are the kernel's own literals, trailing spaces included -- note that
// "FilePmdMapped: " really is one space short of the others (a kernel
// off-by-one, present in v5.4 source AND on the device: that line measures 26
// bytes where every other measures 27) and that Private_Hugetlb uses a 17-char
// label with width 7 instead of 16/8. Both are reproduced deliberately.
// A cheap value that changes whenever the set of loaded images changes --
// a new guest ELF via our loader, or a new host dylib via dlopen. The shim
// uses it to invalidate its smaps census cache, so a consumer polling for
// "has my library appeared yet" is never answered from a stale file.
// Is the full VM census enabled (MACAPK_PROC_FULL_MAPS, default ON)? Exposed
// so the shim's remap table can gate the /proc/self/smaps rule on the SAME
// switch -- =0 must revert the whole census in one run, and smaps has no
// pre-census behaviour to fall back to other than not being served.
int __macapk_arm64_native_full_maps_enabled(void) {
    return macapk_proc_full_maps() ? 1 : 0;
}

uint64_t __macapk_arm64_native_image_epoch(void) {
    return ((uint64_t)_dyld_image_count() << 32) ^
           (uint64_t)loaded_images().size();
}

void __macapk_arm64_native_write_smaps(void* filep) {
    FILE* f = static_cast<FILE*>(filep);
    if (!f) return;
    std::lock_guard<std::mutex> g(macapk_census_mutex());
    const MacApkNameIndex& idx = macapk_name_index();
    std::vector<MacApkVmRegion> regions;
    macapk_enumerate_regions(regions);
    const unsigned long long pgsz =
        (unsigned long long)(sysconf(_SC_PAGESIZE) > 0
                             ? sysconf(_SC_PAGESIZE) : 16384);
    auto kb = [](FILE* fp, const char* label, unsigned long long v, int w) {
        std::fprintf(fp, "%s%*llu kB\n", label, w, v);
    };
    for (const MacApkVmRegion& r : regions) {
        MacApkRegionName nm = macapk_resolve_region_name(idx, r.lo, r.hi);
        std::string anon;
        if (nm.name.empty()) {
            const char* vma = __macapk_vma_name_lookup(r.lo, r.hi - r.lo);
            if (vma && *vma) anon = std::string("[anon:") + vma + "]";
            nm.name = anon;
            nm.file_off = r.offset;
        }
        macapk_write_map_line(f, r, nm.name.empty() ? nullptr : nm.name.c_str(),
                              nm.file_off, nm.dev_major, nm.dev_minor, nm.ino);
        // Android's kernel prints a Name: line for a PR_SET_VMA-named region.
        // We print it only when the guest itself told us the name -- echoing
        // back a fact the guest supplied, never inventing one.
        if (!anon.empty())
            std::fprintf(f, "%-16s%s\n", "Name:", anon.c_str());

        const unsigned long long size_kb = (r.hi - r.lo) >> 10;
        // Host page size is 16384 on Apple Silicon, so this honestly reads
        // 16 kB where every Android smaps shows 4 kB. Printing 4 to "look
        // like Android" would be exactly the fabrication this lane refuses;
        // Android 15+ supports 16 kB pages, so 16 is a legal value.
        const unsigned long long pg_kb  = pgsz >> 10;
        const unsigned long long rss_kb = ((unsigned long long)r.pages_resident * pgsz) >> 10;
        const unsigned long long swap_kb =
            ((unsigned long long)r.pages_swapped_out * pgsz) >> 10;
        unsigned long long dirty_kb =
            ((unsigned long long)r.pages_dirtied * pgsz) >> 10;
        if (dirty_kb > rss_kb) dirty_kb = rss_kb;
        const unsigned long long clean_kb = rss_kb - dirty_kb;
        const bool shared = macapk_region_shared(r) || r.share_mode == SM_COW;
        // Pss IS Rss divided by the number of processes mapping the region.
        // Darwin's nearest quantity is vm_region_submap_info_64::ref_count,
        // whose own header comment reads "obj/map mappers, etc" -- so this is
        // a derivation from a real kernel field, not a guess, but the "etc"
        // is real too: for a shared-cache region ref_count runs to several
        // hundred, which is plausible as a system-wide mapper count and has
        // NOT been cross-checked against vmmap. A private region divides by
        // 1 and degenerates to Rss exactly.
        const unsigned long long mappers =
            (shared && r.ref_count > 1) ? r.ref_count : 1ull;
        const unsigned long long pss_kb  = rss_kb / mappers;
        const unsigned long long swappss_kb = swap_kb / mappers;
        // external_pager is the kernel's own "backed by a file/external pager"
        // flag; a region without one is anonymous (swap-backed), which is the
        // same split Linux reports in Anonymous:.
        const unsigned long long anon_kb = r.external_pager ? 0ull : rss_kb;

        kb(f, "Size:           ", size_kb, 8);
        kb(f, "KernelPageSize: ", pg_kb, 8);
        kb(f, "MMUPageSize:    ", pg_kb, 8);
        kb(f, "Rss:            ", rss_kb, 8);
        kb(f, "Pss:            ", pss_kb, 8);
        kb(f, "Shared_Clean:   ", shared ? clean_kb : 0ull, 8);
        kb(f, "Shared_Dirty:   ", shared ? dirty_kb : 0ull, 8);
        kb(f, "Private_Clean:  ", shared ? 0ull : clean_kb, 8);
        kb(f, "Private_Dirty:  ", shared ? 0ull : dirty_kb, 8);
        // Darwin exposes no per-page young/referenced bit. Rss is a TRUE
        // lower bound (a page had to be referenced to become resident);
        // printing 0 would assert nothing has been touched, which is false.
        kb(f, "Referenced:     ", rss_kb, 8);
        kb(f, "Anonymous:      ", anon_kb, 8);
        // No Darwin source for any of these, and no hugepages / mlock
        // accounting to report -- 0 is what Linux prints for them too on a
        // device without those features, so it is an honest absence.
        kb(f, "LazyFree:       ", 0ull, 8);
        kb(f, "AnonHugePages:  ", 0ull, 8);
        kb(f, "ShmemPmdMapped: ", 0ull, 8);
        kb(f, "FilePmdMapped: ",  0ull, 8);   // kernel's own off-by-one label
        kb(f, "Shared_Hugetlb: ", 0ull, 8);
        kb(f, "Private_Hugetlb: ", 0ull, 7);  // 17-char label, width 7
        kb(f, "Swap:           ", swap_kb, 8);
        kb(f, "SwapPss:        ", swappss_kb, 8);
        kb(f, "Locked:         ", 0ull, 8);
        // macOS has no transparent hugepages, so no region is THP-eligible.
        // Two tabs: the Android-11 device measured "THPeligible:\t\t0" and
        // mainline v5.4's seq_printf literal is likewise two tabs.
        std::fprintf(f, "THPeligible:\t\t0\n");
        // VmFlags mnemonics in the kernel's bit order: rd wr ex sh mr mw me
        // ms. rd/wr/ex come from the CURRENT protection, mr/mw/me from
        // max_protection (which is exactly what VM_MAY* mean), sh/ms from the
        // share mode. Flags with no Darwin source (ac, nr, dc, ...) are
        // omitted rather than guessed. Trailing space after the last mnemonic
        // matches the kernel (it emits "<xx> " per flag).
        std::fprintf(f, "VmFlags:");
        if (r.prot & VM_PROT_READ)         std::fprintf(f, " rd");
        if (r.prot & VM_PROT_WRITE)        std::fprintf(f, " wr");
        if (r.prot & VM_PROT_EXECUTE)      std::fprintf(f, " ex");
        if (macapk_region_shared(r))       std::fprintf(f, " sh");
        if (r.max_prot & VM_PROT_READ)     std::fprintf(f, " mr");
        if (r.max_prot & VM_PROT_WRITE)    std::fprintf(f, " mw");
        if (r.max_prot & VM_PROT_EXECUTE)  std::fprintf(f, " me");
        if (macapk_region_shared(r))       std::fprintf(f, " ms");
        std::fprintf(f, " \n");
    }
}

}  // extern "C"
