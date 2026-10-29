// macapk-elfexec -- run an APK-shipped native EXECUTABLE as a real host child
// process.
//
// THE PROBLEM
//
// Android's PackageManager extracts, and marks 0755, only APK entries matching
// `lib/<abi>/lib*.so`. An app that ships a helper BINARY it forks therefore
// names it `lib<x>.so` even though it is an ET_DYN PIE executable. Simon
// Tatham's Puzzles is the corpus case: `GameGenerator.kt` does
//
//     ProcessBuilder().apply {
//       command(listOf(executablePath.absolutePath) + args)
//       directory(libPuzDir)
//       environment()["LD_LIBRARY_PATH"] = libPuzDir.absolutePath
//     }.start()
//
// then reads the generated puzzle off `process.inputStream`, requires
// `process.waitFor() == 0`, and requires stderr to be empty.
//
// macOS cannot exec a Linux ELF: `posix_spawn` fails ENOEXEC, which surfaces as
// wait status 126 (measured: both from zsh and from OpenJDK 21's
// ProcessBuilder, which spawns via jspawnhelper). So the app's generator always
// "fails" and the puzzle board stays empty.
//
// WHY A CHILD PROCESS AND NOT IN-PROCESS EXECUTION
//
// The alternative was to intercept the launch and run the ELF's main() on a
// thread inside the runtime. Rejected, for four reasons that are properties of
// the observable Android contract rather than of convenience:
//
//   * `exit()`. A guest executable ends by calling exit(); that is the ONLY
//     way bionic's __libc_init returns control. In a child process exit() is
//     already correct and needs no emulation. In-process it would tear down
//     the whole runtime, so it would have to be faked -- and a faked exit
//     leaves the guest's allocations and atexit handlers in an invented state.
//   * stdout. The generated puzzle is written with fwrite(..., stdout) /
//     write(1, ...). In a child, fd 1 IS the pipe the JVM created, so the
//     bytes land where the app reads them with no redirection at all.
//     In-process we would have to dup2() fd 1 process-wide, which would
//     capture every other thread's logging and race with it.
//   * concurrency. GameGenerator uses `Executors.newCachedThreadPool()`, so
//     several generations can be in flight at once. Each needs its own copy
//     of the guest's `.data`/`.bss`; one address space per generation is what
//     a process gives you for free and what a thread cannot.
//   * destroyForcibly(). The app calls it in a `finally`, and cancels futures.
//     SIGKILL to a child is exact; there is no honest way to kill a thread
//     that is running arbitrary guest native code holding locks.
//
// A child process also keeps `waitpid`, exit status, `LD_LIBRARY_PATH`, the
// working directory and pipe EOF semantics as the real kernel ones, so the app
// cannot tell the difference. That IS the product contract.
//
// HOW THE HELPER IS REACHED
//
// apk_native_exec.cpp (runtime side) rewrites the extracted lib dir so that
//
//     _apkt/lib/<abi>/libpuzzlesgen.so                   <- a copy of THIS
//                                                           binary, mode 0755
//     _apkt/lib/.macapk-elfexec/<abi>/libpuzzlesgen.so   <- the guest ELF
//
// Nothing in the app's path changes: the file it stats, `canExecute()`s and
// execs is at exactly the path Android would use, and it really is executable.
// This helper recovers the guest ELF purely from its own executable path --
// same basename, under `../.macapk-elfexec/<abi>/` -- so it needs
// no env var, no data-dir knowledge and no per-app configuration.
//
// ZERO per-APK code: the trigger is an ELF-header property (PT_INTERP /
// DF_1_PIE / ET_EXEC), and neither this file nor apk_native_exec.cpp names any
// package, library or address. See docs/apk-native-executables_2026-07-29.md.

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <mach-o/dyld.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include "forkwatch_watcher.h"

namespace {

// Sibling directory holding the real guest ELFs. Must match
// apk_native_exec.cpp's kSidecarDir.
constexpr const char* kSidecarDir = ".macapk-elfexec";
// Written by apk_native_exec.cpp as the env-independent fallback for locating
// libmacapk-bionic-shim.dylib (see find_shim()).
constexpr const char* kShimDirFile = ".shimdir";
// Our own diagnostics land here, NOT on the inherited stderr. See divert_stderr().
constexpr const char* kLogFile = "elfexec.log";

// ---- the little bit of ELF we read ourselves -------------------------------
// macOS has no <elf.h>, and the real parsing happens in the loader inside
// libmacapk-bionic-shim.dylib. All we need here is the DT_NEEDED list, so we
// can load the guest executable's own dependencies before it.
struct Ehdr64 {
    unsigned char e_ident[16];
    uint16_t e_type, e_machine;
    uint32_t e_version;
    uint64_t e_entry, e_phoff, e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx;
};
struct Phdr64 {
    uint32_t p_type, p_flags;
    uint64_t p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align;
};
struct Dyn64 { int64_t d_tag; uint64_t d_val; };

constexpr uint32_t kPT_LOAD = 1, kPT_DYNAMIC = 2;
constexpr int64_t  kDT_NULL = 0, kDT_NEEDED = 1, kDT_STRTAB = 5;

[[noreturn]] void die(const std::string& msg) {
    // Goes to the DIVERTED stderr (the log file), not the app's pipe -- see
    // divert_stderr(). 126 is what a shell reports for "found but cannot
    // execute", which is exactly what happened, so the app sees the same shape
    // of failure it would see on a broken install.
    std::fprintf(stderr, "[elfexec] %s\n", msg.c_str());
    std::fflush(stderr);
    std::exit(126);
}

// Point fd 2 at a log file for the LOADING phase, and hand back the original so
// it can be restored before the guest runs.
//
// WHY THIS IS NOT OPTIONAL. Two independent reasons:
//
//  1. DEADLOCK. Sgt Puzzles calls `process.waitFor()` and only THEN
//     `readAllOf(process.errorStream)`. A pipe holds ~64 KB; a child that
//     writes more than that to stderr blocks in write() while the parent
//     blocks in waitFor(). Our Stage-6 loader is chatty by design (per-image
//     "mapped/parsed dyn/relocations applied/exports indexed/ran N init_array"
//     plus a line per unresolved symbol and per x18 rewrite), so leaving it
//     pointed at the pipe is an intermittent hang waiting to happen.
//  2. CONTRACT. On Android the dynamic linker's own logging goes to logcat,
//     never into the child's stderr. An app is entitled to treat a non-empty
//     stderr as an error -- this one does, via `require(stderr.isEmpty())`.
//     So the pipe must carry ONLY what the guest itself writes.
//
// The guest's own fprintf(stderr, ...) still reaches the app, because fd 2 is
// restored before its entry point runs.
int g_log_fd = -1;   // CLOEXEC copy of the diverted log (divert_stderr)

// The sidecar log is opened O_APPEND and nothing else ever removes it while the
// feature is on, and the shim now appends one exit backtrace per guest exit
// (Puzzles: one per new game), so it is emptied at open once it passes this.
constexpr off_t kLogCapBytes = 256 * 1024;

// Where the diagnostic fd is parked. F_DUPFD_CLOEXEC takes the LOWEST free
// descriptor at or above its argument, so with 3 it is usually fd 3 -- and a
// guest that closes its inherited descriptors and then open()s its own output
// file is handed exactly that number, which would put the shim's exit
// backtrace INSIDE the guest's file. A number just under the soft limit is one
// no guest reaches by opening files.
int diag_fd_floor() {
    rlim_t cur = 10240;                         // OPEN_MAX
    struct rlimit rl;
    if (::getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY && rl.rlim_cur < cur)
        cur = rl.rlim_cur;
    return cur > 64 ? static_cast<int>(cur) - 16 : 3;
}

int divert_stderr(const std::string& sidecar_dir) {
    int saved = ::dup(2);
    std::string path;
    bool overridden = false;
    if (const char* e = ::getenv("MACAPK_ELFEXEC_LOG")) { path = e; overridden = true; }
    else path = sidecar_dir + "/" + kLogFile;
    int flags = O_WRONLY | O_CREAT | O_APPEND;
    struct stat st;
    // Only our own default log is capped; a path someone named is theirs.
    if (!overridden && ::stat(path.c_str(), &st) == 0 && st.st_size > kLogCapBytes) flags |= O_TRUNC;
    int fd = ::open(path.c_str(), flags, 0644);
    if (fd >= 0) {
        ::dup2(fd, 2);
        // Keep one CLOEXEC copy for the shim's own diagnostics once the guest
        // runs (see hand_diag_fd_to_shim). CLOEXEC so a grandchild the guest
        // execs never inherits our log.
        g_log_fd = ::fcntl(fd, F_DUPFD_CLOEXEC, diag_fd_floor());
        if (g_log_fd < 0) g_log_fd = ::fcntl(fd, F_DUPFD_CLOEXEC, 3);
        ::close(fd);
    }
    return saved;
}

// The shim prints a backtrace when guest code calls exit()/_exit() -- useful in
// the runtime process, where fd 2 is the run log. Here fd 2 is handed back to
// the app before the guest runs (restore_stderr), so that diagnostic would land
// in the app's Process.getErrorStream(), where a device writes nothing and
// where apps look for the child's OWN error text. Point it at our log instead.
// An older shim without the setter keeps today's behaviour.
void hand_diag_fd_to_shim(void* shim) {
    using set_fd_fn = void (*)(int);
    auto set_fd = reinterpret_cast<set_fd_fn>(::dlsym(shim, "__macapk_set_diag_fd"));
    if (set_fd && g_log_fd >= 0) {
        set_fd(g_log_fd);
        // fd 2 is still the log here (restore_stderr comes next). The gate reads this.
        std::fprintf(stderr, "[elfexec] shim diag fd=%d\n", g_log_fd);
        std::fflush(stderr);
    }
}

void restore_stderr(int saved) {
    std::fflush(stderr);
    if (saved >= 0) {
        ::dup2(saved, 2);
        ::close(saved);
    }
}

std::string self_path() {
    // _NSGetExecutablePath is authoritative: argv[0] is caller-controlled and
    // need not be a path at all. realpath() resolves symlinks so the sibling
    // sidecar lookup is relative to the real location.
    char buf[4096];
    uint32_t n = uint32_t(sizeof(buf));
    if (_NSGetExecutablePath(buf, &n) != 0) return std::string();
    char real[4096];
    if (::realpath(buf, real)) return std::string(real);
    return std::string(buf);
}

std::string dir_of(const std::string& p) {
    size_t s = p.find_last_of('/');
    return (s == std::string::npos) ? std::string(".") : p.substr(0, s);
}
std::string base_of(const std::string& p) {
    size_t s = p.find_last_of('/');
    return (s == std::string::npos) ? p : p.substr(s + 1);
}
bool is_file(const std::string& p) {
    struct stat st;
    return ::stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::vector<uint8_t> read_file(const std::string& p) {
    std::vector<uint8_t> out;
    FILE* f = std::fopen(p.c_str(), "rb");
    if (!f) return out;
    if (std::fseek(f, 0, SEEK_END) == 0) {
        long n = std::ftell(f);
        if (n > 0 && std::fseek(f, 0, SEEK_SET) == 0) {
            out.resize(size_t(n));
            if (std::fread(out.data(), 1, size_t(n), f) != size_t(n))
                out.clear();
        }
    }
    std::fclose(f);
    return out;
}

// DT_NEEDED names of an arm64 ELF, in link order.
std::vector<std::string> needed_of(const std::vector<uint8_t>& d) {
    std::vector<std::string> out;
    if (d.size() < sizeof(Ehdr64) || std::memcmp(d.data(), "\177ELF", 4) != 0)
        return out;
    Ehdr64 eh{};
    std::memcpy(&eh, d.data(), sizeof(eh));
    if (eh.e_phentsize != sizeof(Phdr64)) return out;
    if (eh.e_phoff > d.size() ||
        eh.e_phoff + size_t(eh.e_phnum) * sizeof(Phdr64) > d.size())
        return out;

    std::vector<Phdr64> ph(eh.e_phnum);
    std::memcpy(ph.data(), d.data() + eh.e_phoff,
                size_t(eh.e_phnum) * sizeof(Phdr64));

    // vaddr -> file offset, via the PT_LOAD that contains it.
    auto v2o = [&ph](uint64_t v, uint64_t* off) -> bool {
        for (const auto& p : ph) {
            if (p.p_type != kPT_LOAD) continue;
            if (v >= p.p_vaddr && v < p.p_vaddr + p.p_filesz) {
                *off = p.p_offset + (v - p.p_vaddr);
                return true;
            }
        }
        return false;
    };

    const Phdr64* dynp = nullptr;
    for (const auto& p : ph) if (p.p_type == kPT_DYNAMIC) { dynp = &p; break; }
    if (!dynp) return out;
    if (dynp->p_offset > d.size() ||
        dynp->p_offset + dynp->p_filesz > d.size())
        return out;

    uint64_t strtab_off = 0;
    std::vector<uint64_t> needed_idx;
    size_t n_ent = size_t(dynp->p_filesz) / sizeof(Dyn64);
    for (size_t i = 0; i < n_ent; ++i) {
        Dyn64 dy{};
        std::memcpy(&dy, d.data() + dynp->p_offset + i * sizeof(Dyn64),
                    sizeof(dy));
        if (dy.d_tag == kDT_NULL) break;
        if (dy.d_tag == kDT_NEEDED) needed_idx.push_back(dy.d_val);
        else if (dy.d_tag == kDT_STRTAB && !v2o(dy.d_val, &strtab_off))
            return out;
    }
    if (!strtab_off) return out;
    for (uint64_t ix : needed_idx) {
        uint64_t at = strtab_off + ix;
        if (at >= d.size()) continue;
        const char* s = reinterpret_cast<const char*>(d.data() + at);
        size_t maxn = d.size() - size_t(at);
        size_t len = ::strnlen(s, maxn);
        if (len && len < maxn) out.emplace_back(s, len);
    }
    return out;
}

// Where to look for a DT_NEEDED name, in bionic's order: LD_LIBRARY_PATH first
// (the app sets it deliberately), then the directory the executable came from.
// Anything NOT found in these is by definition a platform library, which on
// Android comes from /system/lib64 and here comes from our shim -- so we simply
// do not load it and let resolve_external() bind its symbols. That is the same
// division of labour, not a special case.
std::vector<std::string> search_dirs(const std::string& elf_dir) {
    std::vector<std::string> dirs;
    if (const char* llp = ::getenv("LD_LIBRARY_PATH")) {
        std::string s(llp);
        size_t start = 0;
        while (start <= s.size()) {
            size_t c = s.find(':', start);
            std::string one = s.substr(start, c == std::string::npos
                                              ? std::string::npos : c - start);
            if (!one.empty()) dirs.push_back(one);
            if (c == std::string::npos) break;
            start = c + 1;
        }
    }
    dirs.push_back(elf_dir);
    return dirs;
}

using load_abs_fn   = void* (*)(const char*, const char*);
using enter_fn      = int   (*)(void*, int, char**, char**);
using run_fini_fn   = void  (*)(void*);
using set_root_fn   = void  (*)(const char*);

void*        g_exe_handle = nullptr;
run_fini_fn  g_run_fini   = nullptr;

void run_guest_fini() {
    // FLUSH FIRST. macOS exit() runs atexit handlers BEFORE its stdio cleanup,
    // so a guest destructor that crashes here would lose output the guest had
    // already produced -- and the app's only signal is "stdout was empty".
    // Getting the bytes into the pipe before running any guest destructor makes
    // a fini-time crash cost the exit status but not the result.
    std::fflush(nullptr);
    if (g_run_fini && g_exe_handle) g_run_fini(g_exe_handle);
}

// Depth-first DT_NEEDED load, so an image is always in the loader's registry
// before anything that imports from it is relocated. `depth` bounds a malformed
// or cyclic graph; bionic tracks visited sonames instead, and `loaded` here
// plays the same role.
void load_deps(const std::string& elf_path, const std::string& elf_dir,
               load_abs_fn load_abs, std::vector<std::string>& loaded,
               int depth) {
    if (depth > 8) return;
    std::vector<uint8_t> blob = read_file(elf_path);
    if (blob.empty()) return;
    for (const std::string& soname : needed_of(blob)) {
        bool seen = false;
        for (const auto& l : loaded) if (l == soname) { seen = true; break; }
        if (seen) continue;
        std::string found;
        for (const std::string& d : search_dirs(elf_dir)) {
            std::string cand = d + "/" + soname;
            if (is_file(cand)) { found = cand; break; }
        }
        if (found.empty()) continue;    // platform library -> the shim provides it
        loaded.push_back(soname);
        load_deps(found, elf_dir, load_abs, loaded, depth + 1);
        if (!load_abs(found.c_str(), soname.c_str())) {
            die("failed to load dependency " + found);
        }
    }
}

// libmacapk-bionic-shim.dylib holds the Stage-6 loader and every Bionic ABI
// shim the guest will bind against. Three ways to find it, in order:
//   1. MACAPK_ELFEXEC_SHIM_DIR -- set by the runtime, inherited through
//      ProcessBuilder's copy of System.getenv().
//   2. <sidecar>/.shimdir -- a one-line file written next to the guest ELF, so
//      the helper still works if an app clears its child's environment.
//   3. MACAPK_SHIM_DIR, for a hand-run helper.
void* find_shim(const std::string& sidecar_dir) {
    std::vector<std::string> cands;
    if (const char* e = ::getenv("MACAPK_ELFEXEC_SHIM_DIR")) cands.emplace_back(e);
    std::vector<uint8_t> f = read_file(sidecar_dir + "/" + kShimDirFile);
    if (!f.empty()) {
        std::string s(reinterpret_cast<const char*>(f.data()), f.size());
        while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
        if (!s.empty()) cands.push_back(s);
    }
    if (const char* e = ::getenv("MACAPK_SHIM_DIR")) cands.emplace_back(e);

    std::string last_err;
    for (const std::string& d : cands) {
        std::string p = d + "/libmacapk-bionic-shim.dylib";
        if (!is_file(p)) { last_err = p + " (absent)"; continue; }
        // RTLD_LAZY|RTLD_GLOBAL, matching runtime/src/main.cpp's own dlopen of
        // this dylib. GLOBAL is required (the loader resolves guest imports via
        // dlsym(RTLD_DEFAULT, ...)); LAZY keeps the helper's startup cheap --
        // the shim pulls in ANGLE/MoltenVK as hard dependencies and this
        // process spawns once per generated puzzle.
        void* h = ::dlopen(p.c_str(), RTLD_LAZY | RTLD_GLOBAL);
        if (h) return h;
        const char* e = ::dlerror();
        last_err = p + ": " + (e ? e : "dlopen failed");
    }
    die("cannot load libmacapk-bionic-shim.dylib" +
        (last_err.empty() ? std::string(" (no candidate directory: set "
                                        "MACAPK_ELFEXEC_SHIM_DIR)")
                          : " -- " + last_err));
}

}  // namespace

int main(int argc, char** argv, char** envp) {
    std::string self = self_path();
    if (self.empty()) die("cannot determine my own path");
    const std::string dir     = dir_of(self);
    std::string sidecar = dir_of(dir) + "/" + kSidecarDir + "/" + base_of(dir);
    if (!is_file(sidecar + "/" + base_of(self))) sidecar = dir + "/" + kSidecarDir;
    const std::string elf = sidecar + "/" + base_of(self);

    if (!is_file(elf)) {
        // Hand-run diagnostic: dump what needed_of() sees in an ELF, so the
        // DT_NEEDED walk can be checked against `readelf -d` without a runtime
        // or an installed app. Deliberately gated on the sidecar ELF being
        // ABSENT -- when this binary IS a stand-in, argv belongs to the app and
        // must never be interpreted, whatever it happens to contain.
        // The app-fork watcher (libmacapk-bionic-shim's bionic_shim_forkwatch.cpp
        // posix_spawns this binary from the runtime's bin/ directory, where there
        // is never a sidecar ELF): `--macapk-forkwatch <app pid>` with the
        // registration pipe as fd 3. Fork the watcher (so it is a grandchild of
        // the app, never a child the app could waitpid) and exit 0 -- the status
        // the shim reads as "running". A fresh, libSystem-only image: a fork of
        // the app would pin its rewritten pages for the app's whole life
        // (forkwatch_watcher.h).
        if (argc == 3 && std::strcmp(argv[1], "--macapk-forkwatch") == 0) {
            char* end = nullptr;
            const long app = std::strtol(argv[2], &end, 10);
            if (!end || *end || app <= 1) ::_exit(2);
            const pid_t w = ::fork();
            if (w == 0) macapk_forkwatch::watcher_main(3, (pid_t) app);
            ::_exit(w > 0 ? 0 : 1);
        }
        if (argc >= 3 && std::strcmp(argv[1], "--macapk-needed") == 0) {
            for (int i = 2; i < argc; ++i) {
                std::vector<uint8_t> b = read_file(argv[i]);
                std::printf("%s: %zu bytes\n", argv[i], b.size());
                for (const std::string& s : needed_of(b))
                    std::printf("  DT_NEEDED %s\n", s.c_str());
            }
            return 0;
        }
        die("no guest ELF at " + elf + " -- this binary is a stand-in for an "
            "APK-shipped native executable and cannot run on its own");
    }

    // From here until just before the guest runs, fd 2 is the log file.
    const int saved_err = divert_stderr(sidecar);

    void* shim = find_shim(sidecar);
    auto load_abs = reinterpret_cast<load_abs_fn>(
        ::dlsym(shim, "__macapk_arm64_native_load_abs"));
    auto enter = reinterpret_cast<enter_fn>(
        ::dlsym(shim, "__macapk_arm64_elfexec_enter"));
    g_run_fini = reinterpret_cast<run_fini_fn>(
        ::dlsym(shim, "__macapk_arm64_native_run_fini"));
    if (!load_abs || !enter) {
        die("shim is missing the elfexec entry points "
            "(__macapk_arm64_native_load_abs / __macapk_arm64_elfexec_enter) "
            "-- stale libmacapk-bionic-shim.dylib?");
    }

    // The guest's path-remapping shims (fopen/open/stat on /data/data/... and
    // /sdcard/...) read these two globals, which in the runtime process are set
    // from Java. A child process has to set them itself or every remapped path
    // resolves to nothing. Absent env vars leave them unset, which is exactly
    // today's behaviour for a guest that never touches an Android path.
    auto set_root = reinterpret_cast<set_root_fn>(
        ::dlsym(shim, "__macapk_set_android_root"));
    auto set_sub = reinterpret_cast<set_root_fn>(
        ::dlsym(shim, "__macapk_set_data_subdir"));
    if (set_root) if (const char* e = ::getenv("MACAPK_ANDROID_ROOT")) set_root(e);
    if (set_sub)  if (const char* e = ::getenv("MACAPK_DATA_SUBDIR"))  set_sub(e);

    // Mark this process as an exec'd guest program (its runtime dlopen of a
    // Linux ELF then goes through the Stage-6 loader) and make the guest's
    // `environ` the envp it was exec'd with, before any guest constructor runs
    // (bionic __libc_init_common). Absent in an older shim, which keeps the
    // zygote array and host-dyld dlopen.
    using process_init_fn = void (*)(char**);
    if (auto process_init = reinterpret_cast<process_init_fn>(
            ::dlsym(shim, "__macapk_elfexec_process_init")))
        process_init(envp);

    // Dependencies first (depth-first), then the executable: the loader
    // resolves an image's undefined symbols against everything already in its
    // registry, which is the ordering bionic's linker establishes too.
    std::vector<std::string> loaded;
    load_deps(elf, dir, load_abs, loaded, 0);

    g_exe_handle = load_abs(elf.c_str(), base_of(self).c_str());
    if (!g_exe_handle) die("failed to load guest executable " + elf);

    // DT_FINI_ARRAY on normal termination. See bionic_shim_elfexec.cpp's
    // banner for why we use the linker-side view instead of the structors
    // argument __libc_init is handed.
    std::atexit(run_guest_fini);

    // Hand the real stderr back BEFORE any guest code runs: from here on, fd 2
    // belongs to the app, exactly as it would on Android.
    hand_diag_fd_to_shim(shim);
    restore_stderr(saved_err);

    // Never returns: the guest's _start tail-calls our __libc_init shim, which
    // calls main() and then exit(). A return here means the stack could not be
    // set up at all.
    enter(g_exe_handle, argc, argv, envp);
    die("guest entry did not run");
}
