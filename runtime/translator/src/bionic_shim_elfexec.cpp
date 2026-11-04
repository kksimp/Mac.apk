// bionic_shim_elfexec.cpp -- the Bionic program-startup ABI, for APK-shipped
// native EXECUTABLES.
//
// WHY THIS FILE EXISTS
//
// Android's PackageManager extracts, and marks executable, only the files in
// an APK matching `lib/<abi>/lib*.so`. An app that wants to ship a helper
// BINARY it can fork() therefore has to name that binary `lib<x>.so`, even
// though it is an ET_DYN PIE executable rather than a shared library. This is
// a legitimate, long-standing Android idiom, not an abuse: Simon Tatham's
// Puzzles ships its puzzle generator as `libpuzzlesgen.so` and launches it
// with `ProcessBuilder`, reading the generated puzzle off the child's stdout
// and requiring exit status 0.
//
// macOS cannot exec a Linux ELF, so `macapk-elfexec` (elfexec_main.cpp) is a
// real host child process that loads the guest ELF with the SAME Stage-6
// loader used for every other guest arm64 image and jumps to its ELF entry
// point. Everything in THIS file is the startup ABI that entry point expects
// -- the part the kernel and /system/bin/linker64 would normally provide.
//
// WHAT BIONIC'S _start ACTUALLY DOES  (measured + cited)
//
// bionic/libc/arch-common/bionic/crtbegin.c, aosp-mirror/platform_bionic@main:
//
//     __used static void _start_main(void* raw_args) {
//       structors_array_t array = {};
//       ...
//       __libc_init(raw_args, NULL, &main, &array);
//     }
//     __asm__(... "_start:\n" "mov x0, sp\n" "mov x29, xzr\n"
//                 "mov x30, xzr\n" "b _start_main\n" ...);
//
// Cross-checked against the actual guest binary (Sgt Puzzles v149,
// lib/arm64-v8a/libpuzzlesgen.so, e_entry = 0xbc0), llvm-objdump:
//
//     bc0: bti  j
//     bc4: mov  x29, #0x0
//     bc8: mov  x30, #0x0
//     bcc: mov  x0, sp          <- raw_args IS the stack pointer
//     bd0: b    0xbd4           <- b _start_main
//     ...
//     c1c: mov  x3, sp          <- &structors (built in _start_main's frame)
//     c20: mov  x1, xzr         <- onexit = NULL
//     c24: ldr  x2, [x2,#0x150] <- &main
//     c28: bl   0xea0 <__libc_init@plt>
//
// So the entry contract is: `sp` must point at a Linux-style initial process
// stack, AND there must be usable stack space BELOW it (`_start_main` pushes a
// 0x40-byte frame). x29/x30 are cleared, so the entry never returns.
//
// bionic/libc/private/KernelArgumentBlock.h fixes the block's layout:
//
//     argc = *args;  argv = args + 1;  envp = argv + argc + 1;
//     char** p = envp; while (*p) ++p; ++p;  auxv = (ElfW(auxv_t)*)p;
//
//   -> [argc][argv0..argvN][NULL][envp0..envpM][NULL][auxv pairs][AT_NULL,0]
//
// WHAT __libc_init DOES, AND WHAT WE MUST NOT DUPLICATE
//
// bionic/libc/bionic/libc_init_dynamic.cpp:
//
//     // This function is called from the executable's _start entry point
//     // (see arch-$ARCH/bionic/crtbegin.c), which is itself called by the
//     // dynamic linker after it has loaded all shared libraries the
//     // executable depends on.
//     //
//     // Note that the dynamic linker has also run all constructors in the
//     // executable at this point.
//     __noreturn void __libc_init(void* raw_args, void (*onexit)(void),
//                                 int (*slingshot)(int, char**, char**),
//                                 structors_array_t const* const structors) {
//       KernelArgumentBlock args(raw_args);
//       // Several Linux ABIs don't pass the onexit pointer, and the ones
//       // that do never use it.  Therefore, we ignore it.
//       if (structors->fini_array) {
//         __cxa_atexit(__libc_fini, structors->fini_array, nullptr);
//       }
//       ...
//       exit(slingshot(args.argc - ..., args.argv + ..., args.envp));
//     }
//
// Two load-bearing consequences:
//
//  1. The DYNAMIC __libc_init does NOT run the executable's .init_array --
//     the linker already did. Our loader runs .init_array for every image it
//     loads, which is exactly the linker's role, so there is no double-run
//     and nothing to suppress. (libc_init_STATIC.cpp is the variant that runs
//     preinit_array/init_array itself; a statically linked guest executable
//     would carry its own copy of it and never call ours.)
//
//  2. We deliberately IGNORE `structors`. Its shape is NOT stable across NDK
//     releases -- current AOSP's structors_array_t is 3 pointers plus 3
//     counts, while the NDK that built the corpus binary emitted the older
//     3-pointer form (proved by the guest storing fini_array at +0x10 of a
//     zeroed 0x30-byte local, which is slot 2 of 3 pointers, not slot 4 of
//     6 fields). The callee cannot tell which one it was handed, and reading
//     the wrong field would hand atexit() a garbage function pointer. The
//     linker-side view of the same functions -- DT_FINI_ARRAY, which our
//     loader already parses for every image -- is version-independent, so
//     elfexec_main.cpp registers __macapk_arm64_native_run_fini() instead.
//     Same observable behaviour, no struct-versioning hazard.
//
// NOT gated on any app identity: the trigger is a property of the ELF header
// (PT_INTERP / DF_1_PIE / ET_EXEC), and this file names no package, no
// library and no address. See docs/apk-native-executables_2026-07-29.md.

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <climits>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>

// AT_* keys. <elf.h> is not available with these names on macOS, and the
// values are the Linux/aarch64 ABI ones the guest's getauxval() expects
// (linux/include/uapi/linux/auxvec.h + arch/arm64/include/uapi/asm/auxvec.h).
enum {
    kAT_NULL = 0, kAT_IGNORE = 1, kAT_EXECFD = 2, kAT_PHDR = 3,
    kAT_PHENT = 4, kAT_PHNUM = 5, kAT_PAGESZ = 6, kAT_BASE = 7,
    kAT_FLAGS = 8, kAT_ENTRY = 9, kAT_NOTELF = 10, kAT_UID = 11,
    kAT_EUID = 12, kAT_GID = 13, kAT_EGID = 14, kAT_PLATFORM = 15,
    kAT_HWCAP = 16, kAT_CLKTCK = 17, kAT_SECURE = 23, kAT_RANDOM = 25,
    kAT_HWCAP2 = 26,
};

// HWCAP bits every arm64 Android device sets (arch/arm64/include/uapi/asm/
// hwcap.h). Apple Silicon implements all of these, so advertising them is
// truthful rather than optimistic. Anything a guest probes for beyond this
// set it must also feature-test, because these are the only bits guaranteed
// on the arm64 baseline.
enum : unsigned long {
    kHWCAP_FP = 1UL << 0, kHWCAP_ASIMD = 1UL << 1, kHWCAP_AES = 1UL << 3,
    kHWCAP_PMULL = 1UL << 4, kHWCAP_SHA1 = 1UL << 5, kHWCAP_SHA2 = 1UL << 6,
    kHWCAP_CRC32 = 1UL << 7, kHWCAP_ATOMICS = 1UL << 8,
};

extern "C" void* __macapk_arm64_native_entry(void* handle);
extern "C" void __macapk_guest_env_exec(char** envp);   // bionic_shim.cpp
extern "C" void __macapk_arm64_native_auxv_phdr(void* handle,
                                                unsigned long* phdr,
                                                unsigned long* phnum,
                                                unsigned long* phent);

namespace {

// The synthesized initial process stack. Held at file scope only so the
// trampoline below can hand its top to the guest without the mapping being a
// candidate for reuse; nothing ever unmaps it (the process exits instead,
// exactly as a real exec'd program's stack is reclaimed by the kernel).
constexpr size_t kGuestStackSize = 8u * 1024 * 1024;   // matches Linux 8 MB
void*  g_guest_stack      = nullptr;

// Word-pusher over the synthesized block. Linux lays the strings out ABOVE
// the pointer arrays, so we fill strings downward from the stack top first,
// then place the [argc][argv][envp][auxv] vector below them and hand its base
// to the guest as `sp`.
struct StackBuilder {
    uint8_t* top = nullptr;     // one past the highest usable byte
    uint8_t* strs = nullptr;    // string-area write cursor (grows DOWN)

    char* push_str(const char* s) {
        size_t n = std::strlen(s) + 1;
        strs -= n;
        std::memcpy(strs, s, n);
        return reinterpret_cast<char*>(strs);
    }
};

}  // namespace

// Jump to a guest ELF entry point with `sp` pointing at a synthesized Linux
// initial process stack.
//
// A plain C call cannot do this: bionic's `_start` reads `raw_args` out of the
// live `sp` register (`mov x0, sp`) and then pushes a real frame below it, so
// the argument block must BE the stack top, not a heap object. Six
// instructions, no frame of its own, never returns.
__attribute__((naked, noreturn))
static void macapk_elfexec_enter(void* /*x0: entry*/, void* /*x1: sp*/) {
    __asm__ volatile(
        "mov  x16, x0\n"      // entry (x16 is IP0, always call-clobbered)
        "mov  sp,  x1\n"      // sp = &argc, per KernelArgumentBlock
        "mov  x29, xzr\n"     // crtbegin's _start clears the frame pointer
        "mov  x30, xzr\n"     // ... and the return address
        "mov  x0,  xzr\n"     // no register state is promised to _start
        "br   x16\n");
}

extern "C" {

// Build the Linux initial process stack for `handle`'s entry point and jump to
// it. Never returns: the guest's __libc_init tail-calls exit().
//
// Returns only on FAILURE to set the stack up (bad handle / no e_entry / mmap
// failure), so the caller can report a diagnostic instead of dying silently.
int __macapk_arm64_elfexec_enter(void* handle, int argc, char** argv,
                                 char** envp) {
    void* entry = __macapk_arm64_native_entry(handle);
    if (!entry) {
        std::fprintf(stderr,
            "[elfexec] image has no ELF entry point (e_entry == 0) -- it is a "
            "shared library, not an executable\n");
        return -1;
    }

    // MAP_STACK is advisory on Darwin but documents intent; the guard page
    // below the mapping is what actually catches a runaway guest recursion.
    g_guest_stack = ::mmap(nullptr, kGuestStackSize, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANON, -1, 0);
    if (g_guest_stack == MAP_FAILED) {
        std::fprintf(stderr, "[elfexec] guest stack mmap failed: %s\n",
                     std::strerror(errno));
        return -1;
    }
    ::mprotect(g_guest_stack, size_t(sysconf(_SC_PAGESIZE)), PROT_NONE);

    StackBuilder sb;
    sb.top  = static_cast<uint8_t*>(g_guest_stack) + kGuestStackSize;
    sb.strs = sb.top;

    // ---- strings, highest first (Linux order: argv strings, then envp) ----
    int envc = 0;
    while (envp && envp[envc]) ++envc;

    // AT_RANDOM must point at 16 readable bytes: bionic derives the stack
    // guard AND the pointer-mangling cookie from it
    // (libc/bionic/libc_init_common.cpp __libc_init_globals ->
    // __libc_safe_arc4random_buf / __libc_init_global_stack_chk_guard). Zero
    // bytes are legal but predictable, so fill from the host CSPRNG.
    sb.strs -= 16;
    uint8_t* at_random = sb.strs;
    ::arc4random_buf(at_random, 16);

    const char* kPlatform = "aarch64";
    char* plat_str = sb.push_str(kPlatform);

    // Strings are laid out in reverse so the resulting pointer array ends up
    // ascending, matching what a kernel-built stack looks like to anything
    // that (wrongly, but observably) assumes contiguity.
    std::vector<char*> env_strs(size_t(envc), nullptr);
    for (int i = envc; i-- > 0; ) env_strs[size_t(i)] = sb.push_str(envp[i]);
    std::vector<char*> arg_strs(size_t(argc < 0 ? 0 : argc), nullptr);
    for (int i = argc; i-- > 0; ) arg_strs[size_t(i)] = sb.push_str(argv[i]);

    // ---- the pointer vector, below the strings, 16-byte aligned ----
    unsigned long at_phdr = 0, at_phnum = 0, at_phent = 0;
    __macapk_arm64_native_auxv_phdr(handle, &at_phdr, &at_phnum, &at_phent);

    struct Aux { unsigned long k, v; };
    const Aux auxv[] = {
        { kAT_PHDR,     at_phdr  },
        { kAT_PHENT,    at_phent },
        { kAT_PHNUM,    at_phnum },
        { kAT_PAGESZ,   (unsigned long)sysconf(_SC_PAGESIZE) },
        { kAT_BASE,     0 },   // no separate interpreter is mapped
        { kAT_FLAGS,    0 },
        { kAT_ENTRY,    (unsigned long)(uintptr_t)entry },
        { kAT_UID,      (unsigned long)getuid()  },
        { kAT_EUID,     (unsigned long)geteuid() },
        { kAT_GID,      (unsigned long)getgid()  },
        { kAT_EGID,     (unsigned long)getegid() },
        { kAT_SECURE,   0 },
        { kAT_CLKTCK,   100 },
        { kAT_HWCAP,    kHWCAP_FP | kHWCAP_ASIMD | kHWCAP_AES | kHWCAP_PMULL
                        | kHWCAP_SHA1 | kHWCAP_SHA2 | kHWCAP_CRC32
                        | kHWCAP_ATOMICS },
        { kAT_HWCAP2,   0 },
        { kAT_RANDOM,   (unsigned long)(uintptr_t)at_random },
        { kAT_PLATFORM, (unsigned long)(uintptr_t)plat_str },
        { kAT_NULL,     0 },
    };
    const size_t n_aux = sizeof(auxv) / sizeof(auxv[0]);

    // 1 (argc) + argc + 1 + envc + 1 + 2*n_aux words.
    size_t words = 1 + size_t(argc) + 1 + size_t(envc) + 1 + 2 * n_aux;
    uint8_t* vec = sb.strs - words * sizeof(uintptr_t);
    vec = reinterpret_cast<uint8_t*>(uintptr_t(vec) & ~uintptr_t(15));

    // Everything above must fit in the top half of the mapping, or the guest
    // has no stack left to run on. A pathological environment is the only way
    // to get here, and silently handing the guest a 1-page stack would present
    // as an unexplained SIGSEGV somewhere inside its main().
    auto* lo = static_cast<uint8_t*>(g_guest_stack);
    if (vec < lo + kGuestStackSize / 2) {
        std::fprintf(stderr,
            "[elfexec] argv+envp (%zu bytes) do not leave a usable guest stack "
            "in %zu bytes\n", size_t(sb.top - vec), kGuestStackSize);
        return -1;
    }

    auto* w = reinterpret_cast<uintptr_t*>(vec);
    size_t k = 0;
    w[k++] = uintptr_t(argc);
    for (int i = 0; i < argc; ++i) w[k++] = uintptr_t(arg_strs[size_t(i)]);
    w[k++] = 0;
    for (int i = 0; i < envc; ++i) w[k++] = uintptr_t(env_strs[size_t(i)]);
    w[k++] = 0;
    for (size_t i = 0; i < n_aux; ++i) {
        w[k++] = auxv[i].k;
        w[k++] = auxv[i].v;
    }

    // SILENT on the success path. By the time we are called, elfexec_main.cpp
    // has restored fd 2 to the pipe the app reads, and an app is entitled to
    // treat a non-empty stderr as an error (Sgt Puzzles does, via
    // `require(stderr.isEmpty())`). Opt in with MACAPK_ELFEXEC_VERBOSE=1.
    if (const char* v = ::getenv("MACAPK_ELFEXEC_VERBOSE"); v && v[0] == '1') {
        std::fprintf(stderr,
            "[elfexec] entering guest: entry=%p sp=%p argc=%d envc=%d auxv=%zu\n",
            entry, (void*)vec, argc, envc, n_aux);
        std::fflush(stderr);
    }

    macapk_elfexec_enter(entry, vec);
    return 0;   // unreachable
}

// __libc_init -- bionic's program launch function, as called by the guest
// executable's crtbegin `_start`. See this file's banner for the AOSP source
// and for why `structors` is deliberately unused.
//
// This symbol is invisible to every other guest image: only an EXECUTABLE's
// crtbegin imports __libc_init (crtbegin_so.o, which shared libraries link,
// exports __on_dlclose and imports nothing of the sort), so adding it here
// cannot change how any existing app resolves anything.
__attribute__((noreturn))
void __macapk_arm64___libc_init(void* raw_args, void (*/*onexit*/)(void),
                                int (*slingshot)(int, char**, char**),
                                void const* const /*structors*/) {
    // KernelArgumentBlock, bionic/libc/private/KernelArgumentBlock.h.
    auto* args = reinterpret_cast<uintptr_t*>(raw_args);
    int argc = int(args[0]);
    char** argv = reinterpret_cast<char**>(args + 1);
    char** envp = argv + argc + 1;

    if (!slingshot) {
        std::fprintf(stderr,
            "[elfexec] __libc_init: guest passed a NULL main -- cannot start\n");
        std::exit(70);   // EX_SOFTWARE
    }
    // Bionic subtracts initial_linker_arg_count here, which is non-zero only
    // when the program was launched as `linker64 <prog> ...`. We never do
    // that, so it is 0 and argc/argv pass through unchanged.
    //
    // `environ` points into the kernel's block, as __libc_init_common leaves
    // it (elfexec_main.cpp already published main()'s envp before the guest
    // images were loaded; this moves it onto the synthesized stack copy).
    __macapk_guest_env_exec(envp);
    int rc = slingshot(argc, argv, envp);
    // See run_guest_fini() in elfexec_main.cpp: exit() runs atexit handlers
    // (including the guest's DT_FINI_ARRAY) before stdio cleanup, so get the
    // guest's output into the pipe while nothing can still go wrong.
    std::fflush(nullptr);
    std::exit(rc);
}

}  // extern "C"

// ---------------------------------------------------------------------------
// RUNTIME dlopen() OF A LINUX ELF INSIDE AN EXEC'D GUEST PROGRAM (2026-10-12)
//
// docs/apk-native-executables_2026-07-29.md section 9 left this open: elfexec_main.cpp
// loads an executable's DT_NEEDED tree through the Stage-6 loader, but a guest dlopen()
// made AFTER startup reached __macapk_arm64_dlopen, which hands any path it does not
// recognise to HOST dyld -- and dyld cannot load a Linux ELF ("slice is not valid mach-o
// file"). In the runtime process that path is covered by AppClassLoader's translated
// wrappers; in the exec'd child there are none. MEASURED 2026-10-12 on ytdlnis 1.9.0
// (youtubedl-android): its bundled CPython 3.14 imports every C extension module (math,
// _socket, _ssl, _json, select, zlib, ...) with dlopen(<lib-dynload>/<mod>.so, RTLD_NOW)
// + dlsym(handle, "PyInit_<mod>"), so `import math` failed and yt-dlp could not start.
//
// What bionic's linker does for a dlopen in a plain exec'd process (the default namespace;
// bionic/linker/linker.cpp do_dlopen -> find_libraries -> load_library, fetched 2026-10-12):
//   * a name with a '/' is opened as-is; a bare name is searched in LD_LIBRARY_PATH, then
//     the library's DT_RUNPATH ($ORIGIN expanded), then the default library paths;
//   * a library already loaded is returned again, not mapped twice: a PATH request matches
//     the same file (find_loaded_library_by_inode), a bare name or a DT_NEEDED entry the
//     same soname; RTLD_NOLOAD returns it only if it is already loaded;
//   * DT_NEEDED children are loaded first (BFS there; depth-first is equivalent for
//     symbol resolution here, because our loader resolves an import against every image
//     already registered, exactly as elfexec_main.cpp's startup walk relies on);
//   * dlsym(handle, sym) searches the library, then its DT_NEEDED tree.
// A DT_NEEDED we cannot find on those paths is a platform library (libc/libm/libdl/liblog),
// which the shim provides -- the same rule elfexec_main.cpp's load_deps applies.
//
// ONLY in a macapk-elfexec process (elfexec_main.cpp calls __macapk_elfexec_process_init
// before any guest image is loaded); the runtime process keeps its existing dlopen
// routing byte for byte. MACAPK_ELFEXEC_DLOPEN=0 restores the pre-change behaviour in the
// child (host dlopen, which fails for an ELF).
extern "C" void* __macapk_arm64_native_load_abs(const char* abs_path, const char* dbg_soname);
extern "C" void* __macapk_arm64_native_dlsym(void* handle, const char* name);
extern "C" void* __macapk_arm64_native_find_loaded(const char* name);
extern "C" void* __macapk_arm64_native_find_loaded_file(const char* path);
extern "C" void __macapk_guest_env_exec(char** envp);   // bionic_shim.cpp

namespace {

bool g_elfexec_process = false;
// Set once the RUNTIME process has loaded a guest ELF through __macapk_runtime_guest_dlopen,
// so dlsym/dlclose only take dl_mu() for a handle that could be one of ours.
std::atomic<bool> g_runtime_dl_used{false};

// MACAPK_RUNTIME_ELF_DLOPEN (default ON; =0 restores the pre-change path, where the request
// reaches host dyld and fails): see __macapk_runtime_guest_dlopen.
bool runtime_elf_dlopen_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_RUNTIME_ELF_DLOPEN");
        return !(e && e[0] == '0');
    }();
    return on;
}

bool elfexec_dlopen_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ELFEXEC_DLOPEN");
        return !(e && e[0] == '0');
    }();
    return on;
}

extern "C" int __macapk_arm64_ns_resolve_dep(const char* need, const char* requester, char* path, size_t path_n);   // bionic_shim.cpp

extern "C" int __macapk_arm64_ns_enforcing(void);   // bionic_shim.cpp
static bool ns_dep_failure_fatal() { return __macapk_arm64_ns_enforcing() != 0; }

struct GuestLib {
    std::vector<void*> deps;   // DT_NEEDED images, in DT_NEEDED order (dlsym's search order)
};
std::mutex& dl_mu() { static std::mutex* m = new std::mutex(); return *m; }   // leaked: see bionic_shim.cpp's env mutex
std::unordered_map<void*, GuestLib>& dl_libs() {
    static auto* m = new std::unordered_map<void*, GuestLib>(); return *m;
}
std::unordered_map<std::string, void*>& dl_by_path() {
    static auto* m = new std::unordered_map<std::string, void*>(); return *m;
}

// The dynamic-section facts dlopen needs: DT_SONAME, DT_NEEDED, DT_RUNPATH. Reads the file
// the way elfexec_main.cpp's needed_of() does (PT_DYNAMIC + DT_STRTAB mapped through the
// PT_LOADs), with every offset bounds-checked: the input is untrusted guest data.
struct ElfDyn {
    std::string soname, runpath;
    std::vector<std::string> needed;
};
bool read_file_all(const std::string& p, std::vector<uint8_t>& out) {
    const int fd = ::open(p.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    if (::fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size > (off_t(1) << 31)) { ::close(fd); return false; }
    out.resize(size_t(st.st_size));
    size_t got = 0;
    while (got < out.size()) {
        const ssize_t n = ::read(fd, out.data() + got, out.size() - got);
        if (n <= 0) break;
        got += size_t(n);
    }
    ::close(fd);
    return got == out.size();
}
// ELFCLASS64, little-endian, EM_AARCH64 (183): the only guest a Stage-6 process runs.
bool is_arm64_elf(const std::string& p) {
    uint8_t h[20];
    const int fd = ::open(p.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    const ssize_t n = ::read(fd, h, sizeof h);
    ::close(fd);
    return n == ssize_t(sizeof h) && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
        && h[4] == 2 && h[5] == 1 && (h[18] | (h[19] << 8)) == 183;
}
template <class T> bool rd(const std::vector<uint8_t>& d, uint64_t off, T& v) {
    if (off > d.size() || d.size() - off < sizeof(T)) return false;
    std::memcpy(&v, d.data() + off, sizeof(T));
    return true;
}
bool parse_dyn(const std::string& p, ElfDyn& out) {
    std::vector<uint8_t> d;
    if (!read_file_all(p, d)) return false;
    uint64_t phoff = 0; uint16_t phentsize = 0, phnum = 0;
    if (!rd(d, 0x20, phoff) || !rd(d, 0x36, phentsize) || !rd(d, 0x38, phnum) || phentsize < 56) return false;
    struct Load { uint64_t vaddr, off, filesz; };
    std::vector<Load> loads;
    uint64_t dyn_off = 0, dyn_sz = 0;
    for (uint16_t i = 0; i < phnum; ++i) {
        const uint64_t ph = phoff + uint64_t(i) * phentsize;
        uint32_t type = 0; uint64_t off = 0, vaddr = 0, filesz = 0;
        if (!rd(d, ph, type) || !rd(d, ph + 8, off) || !rd(d, ph + 16, vaddr) || !rd(d, ph + 32, filesz)) return false;
        if (type == 1) loads.push_back({vaddr, off, filesz});            // PT_LOAD
        else if (type == 2) { dyn_off = off; dyn_sz = filesz; }          // PT_DYNAMIC
    }
    if (!dyn_sz) return true;   // static: no dependencies
    auto v2o = [&](uint64_t v, uint64_t& o) {
        for (const Load& l : loads)
            if (v >= l.vaddr && v - l.vaddr < l.filesz) { o = v - l.vaddr + l.off; return true; }
        return false;
    };
    uint64_t strtab = 0;
    std::vector<std::pair<int64_t, uint64_t>> ents;
    for (uint64_t o = dyn_off; o + 16 <= dyn_off + dyn_sz; o += 16) {
        int64_t tag = 0; uint64_t val = 0;
        if (!rd(d, o, tag) || !rd(d, o + 8, val)) return false;
        if (tag == 0) break;                                              // DT_NULL
        if (tag == 5) strtab = val;                                       // DT_STRTAB
        ents.emplace_back(tag, val);
    }
    uint64_t str_off = 0;
    if (!strtab || !v2o(strtab, str_off)) return false;
    auto str_at = [&](uint64_t idx, std::string& s) {
        const uint64_t o = str_off + idx;
        if (o >= d.size()) return false;
        const void* nul = std::memchr(d.data() + o, 0, d.size() - o);
        if (!nul) return false;
        s.assign(reinterpret_cast<const char*>(d.data() + o));
        return true;
    };
    for (const auto& e : ents) {
        std::string s;
        if (e.first == 1  && str_at(e.second, s)) out.needed.push_back(s);           // DT_NEEDED
        if (e.first == 14 && str_at(e.second, s)) out.soname = s;                    // DT_SONAME
        if ((e.first == 29 || (e.first == 15 && out.runpath.empty())) && str_at(e.second, s))
            out.runpath = s;                                                          // DT_RUNPATH / DT_RPATH
    }
    return true;
}
void split_path_list(const std::string& list, const std::string& origin, std::vector<std::string>& out) {
    size_t start = 0;
    while (start <= list.size()) {
        const size_t c = list.find(':', start);
        std::string one = list.substr(start, c == std::string::npos ? std::string::npos : c - start);
        for (const char* tok : {"${ORIGIN}", "$ORIGIN"}) {
            const size_t at = one.find(tok);
            if (at != std::string::npos) one.replace(at, std::strlen(tok), origin);
        }
        if (!one.empty()) out.push_back(one);
        if (c == std::string::npos) break;
        start = c + 1;
    }
}
bool is_regular_file(const std::string& p) {
    struct stat st;
    return ::stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}
// LD_LIBRARY_PATH (read once at startup by bionic's linker; our host environment IS the
// exec envp in this process), then the requesting library's DT_RUNPATH.
std::string find_on_paths(const std::string& name, const std::string& runpath, const std::string& origin) {
    std::vector<std::string> dirs;
    static const std::string llp = [] { const char* e = std::getenv("LD_LIBRARY_PATH"); return std::string(e ? e : ""); }();
    split_path_list(llp, origin, dirs);
    split_path_list(runpath, origin, dirs);
    for (const std::string& d : dirs) {
        const std::string c = d + "/" + name;
        if (is_regular_file(c)) return c;
    }
    return std::string();
}
std::string dir_of(const std::string& p) {
    const size_t s = p.rfind('/');
    return s == std::string::npos ? std::string(".") : (s == 0 ? std::string("/") : p.substr(0, s));
}
std::string base_of(const std::string& p) {
    const size_t s = p.rfind('/');
    return s == std::string::npos ? p : p.substr(s + 1);
}

// Load `file` (and, first, its DT_NEEDED tree) unless an image of the same name is already
// in the loader's registry. Returns the image, or nullptr. Caller holds dl_mu().
void* load_tree(const std::string& file, int depth) {
    if (depth > 16) return nullptr;            // a malformed / cyclic graph
    char rp[PATH_MAX];
    const std::string key = ::realpath(file.c_str(), rp) ? std::string(rp) : file;
    ElfDyn dyn;
    auto hit = dl_by_path().find(key);
    void* already = hit != dl_by_path().end() ? hit->second : nullptr;
    if (!already) already = __macapk_arm64_native_find_loaded_file(key.c_str());   // loaded at startup
    if (already) {
        // One image per file: a second namespace shares it, but the library's dependencies
        // must still be reachable from THIS namespace (bionic would load them there).
        if (__macapk_arm64_ns_enforcing() && parse_dyn(file, dyn)) {
            char nspath[PATH_MAX];
            for (const std::string& need : dyn.needed)
                if (__macapk_arm64_ns_resolve_dep(need.c_str(), key.c_str(), nspath, sizeof nspath) == 4)
                    return nullptr;
        }
        if (hit == dl_by_path().end()) { dl_libs().emplace(already, GuestLib{}); dl_by_path()[key] = already; }
        return already;
    }
    if (!parse_dyn(file, dyn)) return nullptr;
    const std::string name = dyn.soname.empty() ? base_of(file) : dyn.soname;
    GuestLib lib;
    const std::string origin = dir_of(key);
    for (const std::string& need : dyn.needed) {
        // Inside a guest-created linker namespace the namespace decides where (and whether) a
        // dependency is found; otherwise the loaded-soname check and LD_LIBRARY_PATH/RUNPATH.
        char nspath[PATH_MAX];
        const int nsr = __macapk_arm64_ns_resolve_dep(need.c_str(), key.c_str(), nspath, sizeof nspath);
        if (nsr == 4 && ns_dep_failure_fatal()) return nullptr;   // bionic fails the whole dlopen
        if (nsr == 3 || nsr == 4) continue;                    // platform library / unresolved
        if (nsr == 0 || nsr == 2)
            if (void* h = __macapk_arm64_native_find_loaded(need.c_str())) { lib.deps.push_back(h); continue; }
        if (nsr == 2) continue;
        const std::string found = nsr == 1 ? std::string(nspath) : find_on_paths(need, dyn.runpath, origin);
        if (found.empty() || !is_arm64_elf(found)) continue;   // platform library -> the shim
        if (void* h = load_tree(found, depth + 1)) lib.deps.push_back(h);
        else return nullptr;                                  // bionic fails the whole dlopen
    }
    void* h = __macapk_arm64_native_load_abs(key.c_str(), name.c_str());
    if (!h) return nullptr;
    dl_libs()[h] = std::move(lib);
    dl_by_path()[key] = h;
    return h;
}

}  // namespace

extern "C" {

// Called once by macapk-elfexec before it loads the guest: marks this process as an exec'd
// guest program and publishes its exec envp as the guest environment.
void __macapk_elfexec_process_init(char** envp) {
    g_elfexec_process = true;
    __macapk_guest_env_exec(envp);
}

// __macapk_arm64_dlopen's child-process route. Returns 1 when it HANDLED the request (the
// path is an arm64 ELF, so host dyld must not see it) with the result in *out; 0 to let the
// caller fall through unchanged (not an exec'd process, gate off, or not an ELF).
int __macapk_elfexec_guest_dlopen(const char* path, int bionic_flag, void** out) {
    *out = nullptr;
    if (!g_elfexec_process || !path || !*path || !elfexec_dlopen_on()) return 0;
    std::string file(path);
    if (file.find('/') == std::string::npos) {
        // A bare name is first matched against the loaded sonames (bionic
        // find_loaded_library_by_soname), then searched for.
        if (void* h = __macapk_arm64_native_find_loaded(path)) {
            std::lock_guard<std::mutex> lk(dl_mu());
            dl_libs().emplace(h, GuestLib{});
            *out = h;
            return 1;
        }
        file = find_on_paths(file, std::string(), std::string());
        if (file.empty()) return 0;
    }
    if (!is_arm64_elf(file)) return 0;
    std::lock_guard<std::mutex> lk(dl_mu());
    if (bionic_flag & 0x4) {                                   // RTLD_NOLOAD
        char rp[PATH_MAX];
        const std::string key = ::realpath(file.c_str(), rp) ? std::string(rp) : file;
        auto hit = dl_by_path().find(key);
        if (hit != dl_by_path().end()) { *out = hit->second; return 1; }
        // By FILE (inode), as bionic matches a request that names a path; a bare name the
        // guest asked for was also a loaded soname candidate.
        *out = __macapk_arm64_native_find_loaded_file(key.c_str());
        if (!*out && std::strchr(path, '/') == nullptr) *out = __macapk_arm64_native_find_loaded(path);
        if (*out) dl_libs().emplace(*out, GuestLib{});
        return 1;
    }
    *out = load_tree(file, 0);
    if (!*out) {
        std::fprintf(stderr, "[elfexec] dlopen(\"%s\") failed: could not load the ELF or one of "
                     "its DT_NEEDED libraries through the Stage-6 loader\n", path);
    }
    return 1;
}

// The same route in the RUNTIME process (2026-10-19), for a dlopen that NAMES A FILE.
// __macapk_arm64_dlopen tries its own cases first (platform-library markers, the app's
// translated lib_native wrappers for a bare name or a nativeLibraryDir path) and calls this
// only for what is left, with the path already remapped from its Android spelling. Without
// it such a request reached host dyld, which cannot load a Linux ELF. On a device the app's
// classloader namespace dlopens any readable library by path, including one the app wrote
// itself: bionic linker.cpp open_library opens a name containing '/' as-is, and Android 10's
// W^X rule restricts execve() from app data, not dlopen(). MEASURED need: Lemuroid 1.17.0
// downloads a libretro core into files/cores/<ver>/ and LibretroDroid dlopen()s it by
// absolute path, so every game closed with "Cannot dlopen library".
// A bare name is NOT handled here: in the runtime process that stays the lib_native lookup.
// Returns 1 when handled (the result in *out), 0 to fall through to host dlopen.
int __macapk_runtime_guest_dlopen(const char* path, int bionic_flag, void** out) {
    *out = nullptr;
    if (g_elfexec_process || !runtime_elf_dlopen_on() || !path || path[0] != '/') return 0;
    const std::string file(path);
    if (!is_arm64_elf(file)) return 0;
    std::lock_guard<std::mutex> lk(dl_mu());
    g_runtime_dl_used.store(true, std::memory_order_release);
    char rp[PATH_MAX];
    const std::string key = ::realpath(file.c_str(), rp) ? std::string(rp) : file;
    if (bionic_flag & 0x4) {                                   // RTLD_NOLOAD
        auto hit = dl_by_path().find(key);
        if (hit != dl_by_path().end()) { *out = hit->second; return 1; }
        *out = __macapk_arm64_native_find_loaded_file(key.c_str());
        if (*out) dl_libs().emplace(*out, GuestLib{});
        return 1;
    }
    *out = load_tree(file, 0);
    std::fprintf(stderr, "[arm64-shim] dlopen(\"%s\") -> %s through the Stage-6 loader\n",
                 path, *out ? "guest ELF loaded" : "FAILED to load the ELF or a DT_NEEDED library");
    std::fflush(stderr);
    return 1;
}

// dlsym on a handle __macapk_elfexec_guest_dlopen returned: the library, then its DT_NEEDED
// tree (bionic dlsym_handle_lookup's local-group walk). Returns 1 if `handle` is one of ours.
int __macapk_elfexec_guest_dlsym(void* handle, const char* name, void** out) {
    *out = nullptr;
    if ((!g_elfexec_process && !g_runtime_dl_used.load(std::memory_order_acquire)) || !handle) return 0;
    std::lock_guard<std::mutex> lk(dl_mu());
    auto it = dl_libs().find(handle);
    if (it == dl_libs().end()) return 0;
    std::vector<void*> queue{handle};
    for (size_t i = 0; i < queue.size() && i < 256; ++i) {
        if (void* p = __macapk_arm64_native_dlsym(queue[i], name)) { *out = p; return 1; }
        auto d = dl_libs().find(queue[i]);
        if (d != dl_libs().end())
            for (void* dep : d->second.deps) queue.push_back(dep);
    }
    return 1;
}

// dlclose: bionic keeps a library mapped while anything references it, and nothing here
// tracks references, so a guest image is never unmapped (Python never dlcloses its
// extension modules either). 1 == the handle is ours (the caller returns 0, success).
int __macapk_elfexec_guest_dlclose(void* handle) {
    if ((!g_elfexec_process && !g_runtime_dl_used.load(std::memory_order_acquire)) || !handle) return 0;
    std::lock_guard<std::mutex> lk(dl_mu());
    return dl_libs().count(handle) ? 1 : 0;
}

}  // extern "C"
