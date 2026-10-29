// main.cpp -- v2 launcher entry point.
//
// Sequence (kept short on purpose -- the JVM does most of the work):
//   1. Parse CLI: --apk <path> [--verbose] [--width W --height H]
//   2. Locate ourselves on disk so we can resolve relative jar paths.
//   3. Convert the APK's DEX to a JAR (com.macapk.dex) if not cached.
//   4. Boot HotSpot with classpath = framework jar + loader jar + APK jar.
//   5. Register JNI natives.
//   6. Call com.macapk.loader.Boot.main(apkPath) on the main thread.
//   7. Hand control to Cocoa runloop (the Java code's onResume etc. fires
//      via JNI as windows come and go).
//
// We do NOT detach the main thread from the JVM. The Cocoa main thread
// IS the Java "main thread" -- single-threaded model for now, mirrors v1.

#include "macapk/apk_native_exec.h"
#include "macapk/apk_unpacker.h"
#include "macapk/cocoa_bridge.h"
#include "macapk/jvm_loader.h"
#include "macapk/native_methods.h"
#include "macapk/config.h"
#include "macapk/window_policy.h"
#include "macapk/window_traits.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cmath>        // std::lround: window-policy budget points -> pixels
#include <ctime>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <atomic>
#include <set>
#include <sstream>
#include <thread>
#include <string>
#include <vector>

#include <cerrno>       // ESRCH for the install-lock pid-liveness probe
#include <csignal>      // kill(pid, 0) liveness probe
#include <dlfcn.h>
#include <execinfo.h>   // MACAPK_EXIT_BT: backtrace() at the exit-family interpose
#include <mach-o/dyld.h>
#include <mach/mach.h>      // MACAPK_VMSPACE_PROBE: mach_vm_region_recurse address-space walk
#include <mach/mach_vm.h>
#include <sys/stat.h>   // stat/lstat: data/app lib-link inode check after extraction
#include <fcntl.h>      // open/O_CREAT: JVM device-path remap interpose (macapk_int_open)
#include <cstdarg>      // va_list: open(...) variadic mode in macapk_int_open
#include "../thirdparty/fishhook.h"  // rebind libjava's open/stat to the device-path remap (gated)
#include <sys/sysctl.h> // BOOTSPEED boot clock: kinfo_proc p_starttime
#include <sys/time.h>   // BOOTSPEED boot clock: gettimeofday
#include <sys/syscall.h> // MACAPK_EXIT_BT: SYS_exit fallback in the interpose
#include <sys/wait.h>
#include <unistd.h>     // getpid/usleep for the per-package install lock

// ---------------------------------------------------------------------------
// DIAGNOSTIC (default OFF; set MACAPK_EXIT_BT=1): process-wide interpose of the
// exit family, so ANY exit path -- HotSpot's os::exit, a guest native lib that
// reached libSystem's exit (bypassing our Bionic shim), our own std::exit --
// prints a backtrace naming the caller. Added while chasing Instagram's silent
// exit(27) at Profilo init, which evades both MacApkProcessExit (Java exits are
// logged there) and __macapk_arm64_exit (guest-shim exits are logged there), so
// the caller must be host or a libSystem-resolved native exit. The interpose is
// STATIC (always in __DATA,__interpose) but INERT unless the env is set: the
// replacement just tail-calls the real function. Gated so it never perturbs a
// normal run (a clean shutdown returns 0 from main -> exit(0), which we would
// otherwise log every launch).
// ---------------------------------------------------------------------------
namespace {
bool macapk_exit_bt_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_EXIT_BT");
        return e && e[0] == '1';
    }();
    return on;
}
void macapk_exit_bt(const char* who, int code) {
    if (!macapk_exit_bt_on()) return;
    void* bt[96];
    int n = ::backtrace(bt, 96);
    std::fprintf(stderr, "\n[MACAPK-EXIT] %s(%d) on tid=%p -- backtrace (%d frames):\n",
                 who, code, (void*)pthread_self(), n);
    std::fflush(stderr);
    ::backtrace_symbols_fd(bt, n, STDERR_FILENO);
    std::fprintf(stderr, "[MACAPK-EXIT] end backtrace for %s(%d)\n", who, code);
    std::fflush(stderr);
}
}  // namespace
extern "C" {
static void macapk_int_exit(int code) {
    macapk_exit_bt("exit", code);
    auto real = reinterpret_cast<void (*)(int)>(dlsym(RTLD_NEXT, "exit"));
    if (real) real(code);
    ::syscall(SYS_exit, code);
    __builtin_unreachable();
}
static void macapk_int__exit(int code) {
    macapk_exit_bt("_exit", code);
    auto real = reinterpret_cast<void (*)(int)>(dlsym(RTLD_NEXT, "_exit"));
    if (real) real(code);
    ::syscall(SYS_exit, code);
    __builtin_unreachable();
}
static void macapk_int__Exit(int code) {
    macapk_exit_bt("_Exit", code);
    auto real = reinterpret_cast<void (*)(int)>(dlsym(RTLD_NEXT, "_Exit"));
    if (real) real(code);
    ::syscall(SYS_exit, code);
    __builtin_unreachable();
}
static void macapk_int_abort(void) {
    macapk_exit_bt("abort", -1);
    auto real = reinterpret_cast<void (*)(void)>(dlsym(RTLD_NEXT, "abort"));
    if (real) real();
    ::syscall(SYS_exit, 134);
    __builtin_unreachable();
}
// __exit: the raw libsystem_kernel syscall wrapper that exit/_exit/_Exit and
// HotSpot's os::exit all funnel through. Interposing it is the widest net for a
// host-code process exit that skips atexit. Declared here since it has no header.
extern void __exit(int);
static void macapk_int___exit(int code) {
    macapk_exit_bt("__exit", code);
    ::syscall(SYS_exit, code);
    __builtin_unreachable();
}
}  // extern "C"
#define MACAPK_DYLD_INTERPOSE(_r, _e)                                          \
    __attribute__((used)) static struct {                                     \
        const void* replacement;                                              \
        const void* replacee;                                                 \
    } _macapk_interpose_##_e __attribute__((section("__DATA,__interpose"))) = { \
        reinterpret_cast<const void*>(&_r), reinterpret_cast<const void*>(&_e)}
MACAPK_DYLD_INTERPOSE(macapk_int_exit, exit);
MACAPK_DYLD_INTERPOSE(macapk_int__exit, _exit);
MACAPK_DYLD_INTERPOSE(macapk_int__Exit, _Exit);
MACAPK_DYLD_INTERPOSE(macapk_int___exit, __exit);
MACAPK_DYLD_INTERPOSE(macapk_int_abort, abort);

// ---- JVM device-path filesystem remap (MACAPK_ANDROID_MAPS_PATHS; cont.73) ----
// pairip's VMRunner.openBaseApk does `new ZipFile(path)` with the DEVICE /data/app spelling (so its
// integrity VM folds a device path, not our host prefix). macOS has no /data, so libjava's open/stat of
// that path fails ("Could not open base apk"). Remap Android device prefixes to the host android root for
// HotSpot's file ops -- the same translation the guest bionic shim does for arm64 code. Installed via
// __interpose (the exit interposes above reach HotSpot's os::exit, so this should reach libjava's file
// ops; the [jvmfs] log confirms it, else we move to a fishhook rebind). Only remaps when the gate is set
// AND the path is a device prefix AND the remapped host path EXISTS -- so the shipped default and every
// host-path open are byte-identical (fleet-safe).
namespace {
const char* g_jvmfs_root = nullptr;    // set in main() from --android-root
bool macapk_jvmfs_on() {
    static const bool on = (std::getenv("MACAPK_ANDROID_MAPS_PATHS") != nullptr);
    return on;
}
std::string macapk_jvmfs_remap(const char* path) {
    if (!path || path[0] != '/' || !g_jvmfs_root || !macapk_jvmfs_on()) return {};
    static const char* const kPfx[] = {"/data/", "/sdcard", "/storage/", "/mnt/", nullptr};
    bool dev = false;
    for (int i = 0; kPfx[i]; ++i) if (std::strncmp(path, kPfx[i], std::strlen(kPfx[i])) == 0) { dev = true; break; }
    if (!dev) return {};
    // already under the root? (avoid double-prefix)
    if (std::strncmp(path, g_jvmfs_root, std::strlen(g_jvmfs_root)) == 0) return {};
    std::string cand = std::string(g_jvmfs_root) + path;
    static int(*rstat)(const char*, struct stat*) =
        reinterpret_cast<int(*)(const char*, struct stat*)>(dlsym(RTLD_NEXT, "stat"));
    struct stat st;
    if (rstat && rstat(cand.c_str(), &st) == 0) {
        static std::atomic<int> lg{0};
        if (lg.fetch_add(1) < 10) { std::fprintf(stderr, "[jvmfs] %s -> %s\n", path, cand.c_str()); std::fflush(stderr); }
        return cand;
    }
    return {};
}
}  // namespace
extern "C" {
static int macapk_int_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap); }
    std::string r = macapk_jvmfs_remap(path);
    static int(*real)(const char*, int, ...) =
        reinterpret_cast<int(*)(const char*, int, ...)>(dlsym(RTLD_NEXT, "open"));
    return real(r.empty() ? path : r.c_str(), flags, mode);
}
static int macapk_int_stat(const char* path, struct stat* buf) {
    std::string r = macapk_jvmfs_remap(path);
    static int(*real)(const char*, struct stat*) =
        reinterpret_cast<int(*)(const char*, struct stat*)>(dlsym(RTLD_NEXT, "stat"));
    return real(r.empty() ? path : r.c_str(), buf);
}
static int macapk_int_lstat(const char* path, struct stat* buf) {
    std::string r = macapk_jvmfs_remap(path);
    static int(*real)(const char*, struct stat*) =
        reinterpret_cast<int(*)(const char*, struct stat*)>(dlsym(RTLD_NEXT, "lstat"));
    return real(r.empty() ? path : r.c_str(), buf);
}
static int macapk_int_access(const char* path, int amode) {
    std::string r = macapk_jvmfs_remap(path);
    static int(*real)(const char*, int) =
        reinterpret_cast<int(*)(const char*, int)>(dlsym(RTLD_NEXT, "access"));
    return real(r.empty() ? path : r.c_str(), amode);
}
static int macapk_int_fstatat(int fd, const char* path, struct stat* buf, int flag) {
    // Only an absolute device path is remappable here (a relative path is resolved against `fd`).
    std::string r = macapk_jvmfs_remap(path);
    static int(*real)(int, const char*, struct stat*, int) =
        reinterpret_cast<int(*)(int, const char*, struct stat*, int)>(dlsym(RTLD_NEXT, "fstatat"));
    return real(fd, r.empty() ? path : r.c_str(), buf, flag);
}
}  // extern "C"
// Deliberately NO __interpose of open/stat here: a main-exe __interpose does not reach dlopen'd libjava's
// file ops (measured, cont.74 -- no [jvmfs] fired; FileReader banner), so it would only cover the runtime's
// own + early-dylib calls, which the fishhook rebind below ALSO covers -- an always-on __interpose would be
// pure gate-off overhead. The fishhook rebind is installed ONLY when gated, so gate off = zero wrappers
// anywhere = byte-identical shipped behaviour (fleet-safe). It rebinds EVERY image's __got (runtime +
// libjava/libjvm/libnio) and registers for future images, so a later-loaded libjava is covered. cont.74-76.
static void macapk_install_jvmfs_rebind() {
    if (!macapk_jvmfs_on()) return;
    static struct rebinding rb[] = {
        {"open",            reinterpret_cast<void*>(&macapk_int_open),    nullptr},
        {"open$NOCANCEL",   reinterpret_cast<void*>(&macapk_int_open),    nullptr},
        {"stat",            reinterpret_cast<void*>(&macapk_int_stat),    nullptr},
        {"stat$INODE64",    reinterpret_cast<void*>(&macapk_int_stat),    nullptr},
        {"lstat",           reinterpret_cast<void*>(&macapk_int_lstat),   nullptr},
        {"lstat$INODE64",   reinterpret_cast<void*>(&macapk_int_lstat),   nullptr},
        {"access",          reinterpret_cast<void*>(&macapk_int_access),  nullptr},
        {"fstatat",         reinterpret_cast<void*>(&macapk_int_fstatat), nullptr},
        {"fstatat$INODE64", reinterpret_cast<void*>(&macapk_int_fstatat), nullptr},
    };
    rebind_symbols(rb, sizeof(rb) / sizeof(rb[0]));
    std::fprintf(stderr, "[jvmfs] fishhook rebind installed (%zu symbols) root=%s\n",
                 sizeof(rb) / sizeof(rb[0]), g_jvmfs_root ? g_jvmfs_root : "(null)");
    std::fflush(stderr);
}

// atexit backtrace (gated MACAPK_EXIT_BT=1). Any `exit()` -- including HotSpot's
// os::exit -> ::exit -- runs C++ static destructors + atexit handlers before the
// process leaves; this one prints a backtrace naming the caller of exit(). It
// does NOT fire for `_exit`/`_Exit`/a raw exit syscall (which skip atexit). Zero
// cost until the process actually exits, so no perturbation of the boot race.
namespace {
void macapk_atexit_bt() { macapk_exit_bt("atexit (exit-family)", 0); }
__attribute__((constructor)) void macapk_register_exit_atexit() {
    if (macapk_exit_bt_on()) std::atexit(macapk_atexit_bt);
}
}  // namespace

// ---------------------------------------------------------------------------
// BOOTSPEED boot clock (v1.0.1650; default ON, MACAPK_BOOTSPEED_CLOCK=0 prints
// nothing). One `[boot-clock] +<ms> ms <phase> (native)` line per boot phase,
// in milliseconds since the kernel CREATED this process (kinfo_proc
// p_starttime). Boot.java prints the Java phases on the SAME epoch (it reads
// p_starttime through ProcessHandle.info().startInstant(), which on macOS is
// that field truncated to ms), so one log carries one timeline from process
// creation to the Cocoa runloop, with no harness and no poll cadence.
// p_starttime survives exec, so for an installed app the clock starts when the
// per-app stub was launched and the first line (`main() entered`) is the stub's
// own pre-exec work plus dyld. debug/bootspeed_clock.py digests the lines.
// Observational only: it reads two clocks and writes to stderr.
// ---------------------------------------------------------------------------
namespace {
bool BootClockOn() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_BOOTSPEED_CLOCK");
        return !(e && std::strcmp(e, "0") == 0);   // Java's !"0".equals(...)
    }();
    return on;
}
// Only a LAUNCH prints: --peek-manifest / --gms / --gles-scan / --extract-only
// callers parse this process's output and have no boot to time. main() sets it
// once the launch path is certain.
bool g_boot_clock_launch = false;
long long NowEpochMs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<long long>(tv.tv_sec) * 1000LL + tv.tv_usec / 1000;
}
long long ProcessStartEpochMs() {
    static const long long t0 = [] {
        struct kinfo_proc kp;
        std::memset(&kp, 0, sizeof kp);
        size_t len = sizeof kp;
        int mib[4] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, static_cast<int>(getpid())};
        if (sysctl(mib, 4, &kp, &len, nullptr, 0) != 0 || len == 0) return -1LL;
        return static_cast<long long>(kp.kp_proc.p_starttime.tv_sec) * 1000LL
             + static_cast<long long>(kp.kp_proc.p_starttime.tv_usec) / 1000LL;
    }();
    return t0;
}
void BootClockAt(const char* phase, long long now) {
    if (!g_boot_clock_launch || !BootClockOn()) return;
    const long long t0 = ProcessStartEpochMs();
    std::fprintf(stderr, "[boot-clock] +%lld ms %s (native)\n", t0 > 0 ? now - t0 : -1LL, phase);
}
void BootClock(const char* phase) { BootClockAt(phase, NowEpochMs()); }
}  // namespace

namespace fs = std::filesystem;

static void ReplaceCompatLink(const fs::path& data_dir);   // v1.0.1641, defined below
using namespace macapk;

namespace {

// Orientation request. AUTO defers to the manifest's screenOrientation
// (defaults to PORTRAIT when the manifest doesn't say). PORTRAIT/
// LANDSCAPE override unconditionally.
enum class Orient { AUTO, PORTRAIT, LANDSCAPE };

struct CliArgs {
    std::string apk;
    int    width  = 0;
    int    height = 0;
    Orient orient = Orient::AUTO;
    bool   verbose = false;
    // v1.0.80: --extract-only is a non-launching mode the launcher's
    // Installed-Apps panel uses to backfill metadata (manifest, icon,
    // meta.json) for APKs the user wants to see in the panel without
    // actually running them. The runtime runs apk_unpacker + the
    // android-root asset extract, then exits 0 -- no JVM, no Cocoa
    // window, no native libs. Reuses the full unpacker path so
    // backfilled dirs look identical to a real launch's dir.
    bool   extract_only = false;
    // --gms <subcommand> [args]: drive the GMS layer (accounts, sign-in,
    // tokens, licensing) with NO APK. It is how the launcher's Accounts panel
    // adds a Google account, and it is the ONLY path that can put Google's
    // sign-in page on screen -- an installed app launched from the Dock has no
    // launcher process to delegate to, so the window has to live in this binary.
    // Empty means "not a GMS run".
    std::vector<std::string> gms_args;
    // User-visible "android" filesystem root. The runtime creates a
    // mirror of Android's /data/data/<pkg>/ tree (and /sdcard, etc.)
    // under here, populated from the APK's apkt-extracted assets.
    // Default: $HOME/Library/Caches/MacApk/scratch (moved out of
    // $HOME/Documents/android 2026-08-12 -- see ResolveAndroidRootDir in
    // apk_unpacker.cpp for why). Override via --android-root or the
    // MACAPK_ANDROID_ROOT env var.
    std::string android_root;
    // --peek-manifest <apk>: print one JSON line with the package /
    // versionCode / versionName / label read from the BINARY manifest,
    // then exit. No extraction, no JVM, no window -- the install
    // confirm dialog needs the identity BEFORE paying for any of that.
    std::string peek_manifest;
    // --gles-scan <apk>: run the two UNCACHED GLES scans (the ES3.1 backend
    // choice and the ES1 context choice) and print {"es31":0|1,"es1":0|1},
    // then exit. A diagnostic: debug/glesscan_verify.sh diffs it against the
    // grep pipelines it replaced, over the whole corpus.
    std::string gles_scan;
    // --peek-window-policy <apk>: print the AOSP window-policy decision for
    // this APK on the current main screen (JSON on stdout), then exit. No JVM,
    // no window, no extraction.
    std::string peek_window_policy;
};

void Usage(const char* exe) {
    std::fprintf(stderr,
        "usage: %s --apk <path>\n"
        "          [--orientation auto|portrait|landscape]   (default: auto)\n"
        "          [--width N --height N]                    (overrides orientation defaults)\n"
        "          [--android-root <dir>]                    (default: $HOME/Library/Caches/MacApk/scratch)\n"
        "          [--verbose]\n"
        "          [--extract-only]                          (unpack manifest/icon/assets, then exit; no JVM/window)\n"
        "          [--peek-manifest <apk>]                   (print {package,versionCode,versionName,label} JSON, then exit)\n"
        "          [--gles-scan <apk>]                       (print the uncached {es31,es1} GLES scan verdicts, then exit)\n"
        "          [--peek-window-policy <apk>]              (print the AOSP window-policy decision JSON for this screen, then exit)\n"
        "\n"
        "  --orientation auto   read android:screenOrientation from the\n"
        "                       launchable activity, fall back to portrait.\n"
        "  --orientation portrait   force a portrait window.\n"
        "  --orientation landscape  force a landscape window.\n"
        "                       Without --width/--height the window is the\n"
        "                       biggest canvas the display supports for the\n"
        "                       resolved orientation.\n"
        "  --width/--height     explicit pixel size; takes precedence over\n"
        "                       any orientation default. Useful for\n"
        "                       phone-sized windows or custom aspect ratios.\n"
        "  --android-root <dir> User-visible directory where the runtime\n"
        "                       mirrors Android's filesystem layout. The\n"
        "                       app's data/data/<pkg>/ tree is populated\n"
        "                       there from the APK's assets (symlinked, so\n"
        "                       no duplication). Save files and runtime\n"
        "                       writes also land there. Default chosen for\n"
        "                       easy access from Finder.\n",
        exe);
}

CliArgs ParseArgs(int argc, char** argv) {
    CliArgs a;
    for (int i = 1; i < argc; ++i) {
        std::string s = argv[i];
        auto need = [&](int more) {
            if (i + more >= argc) {
                Usage(argv[0]);
                std::exit(2);
            }
        };
        if (s == "--apk")          {
            need(1);
            // v1.0.938: ABSOLUTIZE. ApplicationInfo.sourceDir / publicSourceDir
            // and Context.getPackageCodePath()/getPackageResourcePath() are
            // absolute on a device -- they name /data/app/<pkg>/base.apk -- and
            // apps do File(path).getParentFile() or compare against a prefix on
            // them. Launched with a relative --apk (which is how the probe and
            // most of debug/ invoke us) the value propagated verbatim and those
            // four answers came back relative. Probe rows
            // CTX.id.{codePath,resourcePath}.absolute.
            //
            // `absolute`, NOT `weakly_canonical`: canonicalizing would resolve
            // symlinks, and the resolved name is what the version-guessing path
            // downstream reads to derive <pkg>_<version>. Making the path
            // absolute is the contract; rewriting it is not.
            {
                std::error_code ec;
                std::filesystem::path p = std::filesystem::absolute(argv[++i], ec);
                a.apk = ec ? argv[i] : p.string();
            }
            // Export the APK path so the Java side can read the BINARY
            // manifest before the framework initialises. PresentedSdk needs
            // targetSdkVersion at Build.<clinit> time, which runs before any
            // loader class could hand it over, so an env var is the only
            // channel that exists that early. The binary manifest is the only
            // reliable source: apktool STRIPS <uses-sdk> from the decoded
            // text tree for every app (measured fleet-wide, 0 elements), so
            // anything reading the decoded manifest sees no target at all.
            setenv("MACAPK_APK_PATH", a.apk.c_str(), 1);
        }
        else if (s == "--width")   { need(1); a.width  = std::atoi(argv[++i]); }
        else if (s == "--height")  { need(1); a.height = std::atoi(argv[++i]); }
        else if (s == "--orientation") {
            need(1);
            std::string v = argv[++i];
            if      (v == "auto"      || v == "AUTO")      a.orient = Orient::AUTO;
            else if (v == "portrait"  || v == "PORTRAIT")  a.orient = Orient::PORTRAIT;
            else if (v == "landscape" || v == "LANDSCAPE") a.orient = Orient::LANDSCAPE;
            else {
                std::fprintf(stderr,
                    "[macapk] unknown orientation '%s'; expected "
                    "auto|portrait|landscape\n", v.c_str());
                Usage(argv[0]);
                std::exit(2);
            }
        }
        // Convenience aliases so the common cases don't need the
        // --orientation prefix.
        else if (s == "--portrait")  { a.orient = Orient::PORTRAIT; }
        else if (s == "--landscape") { a.orient = Orient::LANDSCAPE; }
        // v1.0.128: --fullscreen + --hide-dock for launcher use.
        // Both honor a matching MACAPK_* env var so the Swift
        // launcher (which spawns the runtime as a child process)
        // can set them without touching argv. Putting them on argv
        // also makes the CLI form first-class for direct testing.
        else if (s == "--fullscreen") { setenv("MACAPK_FULLSCREEN", "1", 1); }
        else if (s == "--hide-dock-icon") {
            setenv("MACAPK_HIDE_DOCK_ICON", "1", 1);
        }
        else if (s == "--android-root") {
            need(1);
            a.android_root = argv[++i];
            // Export so every INDEPENDENT root resolver downstream (the
            // unpacker's no-env cache fallback, Boot.java's env chain,
            // the GLES31 verdict cache) agrees with the CLI value.
            // Without this, a --android-root pointed inside a per-app
            // .app bundle would leave the env-only resolvers on the
            // ~/Documents default and split the app across two trees.
            setenv("MACAPK_ANDROID_ROOT", a.android_root.c_str(), 1);
            // Publish the root (no trailing slash) to the JVM device-path remap interposes above.
            { static std::string s_jvmfs_root = a.android_root;
              while (s_jvmfs_root.size() > 1 && s_jvmfs_root.back() == '/') s_jvmfs_root.pop_back();
              g_jvmfs_root = s_jvmfs_root.c_str(); }
        }
        else if (s == "--verbose") { a.verbose = true; }
        else if (s == "--extract-only") { a.extract_only = true; }
        else if (s == "--gms") {
            // Everything after --gms belongs to the subcommand, verbatim. A
            // token can legitimately contain characters this parser would
            // otherwise try to interpret, so we stop parsing here rather than
            // whitelisting shapes.
            need(1);
            for (int j = i + 1; j < argc; ++j) a.gms_args.push_back(argv[j]);
            i = argc;
        }
        else if (s == "--peek-manifest") { need(1); a.peek_manifest = argv[++i]; }
        else if (s == "--gles-scan") { need(1); a.gles_scan = argv[++i]; }
        else if (s == "--peek-window-policy") { need(1); a.peek_window_policy = argv[++i]; }
        // Verbose dev trace: enables ALL diagnostic instrumentation
        // in the bionic shim + lifter trace points. Sets
        // MACAPK_DEV_TRACE=1 plus the per-component env vars so
        // every gate downstream fires. Mirrors the Swift launcher's
        // "Verbose Trace" toggle next to Force ARM32 under the dev
        // menu. Headless equivalent for CI / scripted repros.
        else if (s == "--dev-trace" || s == "--verbose-trace") {
            // setenv with overwrite=1 so a parent's value doesn't
            // shadow the explicit CLI request.
            setenv("MACAPK_DEV_TRACE",         "1", 1);
            setenv("MACAPK_TRACE_BL",          "1", 1);
            setenv("MACAPK_TRACE_JNI_HANDLES", "1", 1);
            std::fprintf(stderr,
                "[macapk] --dev-trace: all diagnostic tracing "
                "enabled (expect 1 fps on HCR-class games)\n");
        }
        // ABI override: equivalent of MACAPK_FORCE_ABI env. Wired
        // here so a headless run doesn't need a shell-env layer
        // (matches the Swift launcher's Force ARM32 toggle).
        else if (s == "--force-abi") {
            need(1);
            setenv("MACAPK_FORCE_ABI", argv[++i], 1);
        }
        else if (s == "-h" || s == "--help") { Usage(argv[0]); std::exit(0); }
        else { Usage(argv[0]); std::exit(2); }
    }
    if (a.apk.empty() && a.peek_manifest.empty() && a.gles_scan.empty() && a.gms_args.empty()) { Usage(argv[0]); std::exit(2); }
    // CLI flag wins; env var is the next fallback; the default is
    // expanded later (we need $HOME).
    if (a.android_root.empty()) {
        const char* env = std::getenv("MACAPK_ANDROID_ROOT");
        if (env && *env) a.android_root = env;
    }
    return a;
}

// Per-version data subdirs ("<pkg>_<version>") keep two versions of the
// same APK from overwriting each other's save files; the version is the
// binary manifest's android:versionName (DeriveAppVersion below).
// Strip any character that isn't [A-Za-z0-9._-] so a version like
// "1.43.0 (beta)" is safe to use as a path component.
std::string SanitizeVersionChunk(const std::string& raw) {
    std::string safe;
    safe.reserve(raw.size());
    for (char c : raw) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
               || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        if (ok) safe.push_back(c);
    }
    return safe;
}

// (v1.0.1590: the text-manifest readers that used to live here --
// ReadManifestPackage / ReadManifestAttr over apktool's decode -- are gone;
// the binary manifest inside the apk answers every question. The doctrine
// survives in DeriveAppVersion: versionName first, platformBuildVersionName
// only as the last resort, because the latter is the Android PLATFORM build
// version, not the app's.)

// Last-resort version extraction from the APK filename when the
// manifest carries no versionName at all (very old APKs / hand-built
// ones). Handles both apkmirror conventions:
//   * "<pkg>_<ver>-<build>_minAPI<N>...apk"  (HCR family, GD family)
//   * "<pkg>-<ver>-<build>-minAPI<N>...apk"  (Flappy Bird, older dotgears)
//
// Algorithm: strip the directory + ".apk" extension. The leading
// package-name prefix is known (`pkg` arg). What follows is either
// `_<ver>...` or `-<ver>...`; in both cases the version itself ends
// at the NEXT '-' or '_' separator. Return that substring with the
// same [A-Za-z0-9._-] sanitization the manifest path uses.
//
// Returns empty when pkg isn't a prefix of the filename (the user
// renamed the APK to something unrelated) -- caller falls back to
// the bare package name in that case.
std::string GuessVersionFromApkFilename(const std::string& apk_path,
                                          const std::string& pkg) {
    if (pkg.empty()) return {};
    std::string stem = fs::path(apk_path).stem().string();
    if (stem.size() <= pkg.size() + 1
        || stem.compare(0, pkg.size(), pkg) != 0) {
        return {};
    }
    // Character immediately after the package name should be a
    // separator ('_' or '-').
    char sep = stem[pkg.size()];
    if (sep != '_' && sep != '-') return {};
    // Version chunk is from pkg.size()+1 up to the NEXT '_' or '-'.
    size_t v_start = pkg.size() + 1;
    size_t v_end = stem.size();
    for (size_t i = v_start; i < stem.size(); ++i) {
        if (stem[i] == '_' || stem[i] == '-') {
            v_end = i;
            break;
        }
    }
    std::string raw = stem.substr(v_start, v_end - v_start);
    std::string safe;
    safe.reserve(raw.size());
    for (char c : raw) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
               || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        if (ok) safe.push_back(c);
    }
    // v1.0.94: require the inferred version to LOOK like a version
    // (start with a digit). For "F-Droid.apk" with pkg="F" (the
    // CheapPackageName chops at first dash), this used to return
    // "Droid" -- then SetupAndroidRoot built data dir "F_Droid" and
    // boot tried to read "F_Droid/AndroidManifest.xml" which doesn't
    // exist. A real version chunk in our naming conventions always
    // begins with a digit ("1.43.0", "2.2.11", "54", etc.). If the
    // chunk doesn't, treat it as not-a-version and let the caller
    // fall back to the bare package name.
    if (safe.empty() || !(safe[0] >= '0' && safe[0] <= '9')) return {};
    return safe;
}

// Derive the per-app data-dir version suffix CONSISTENTLY across the
// unpack cache dir (apk_unpacker's CheapPackageName, filename-derived)
// and the runtime data dir (manifest-derived). A mismatch makes the two
// land in different dirs, which (a) re-runs the full unpack every launch
// (the cache pre-scan keys on CheapPackageName and never finds the
// persistent dir) and (b) can leave the persistent dir without freshly
// translated native libs, so findLibrary() falls back to the no-op stub
// and a Unity title boots black.
//
// Priority:
//   1. The BINARY manifest's android:versionName. This must come first:
//      apktool's text decode drops versionName for every app (measured
//      2026-08-10: 0 of 64 decoded manifests carry it), so the old
//      text-first chain never fired and 8 of 64 live dirs got suffixed
//      with the ANDROID platform version ("_15", "_16") instead of the
//      app's own. Note the derivation only names NEW dirs: warm launches
//      and the data-dir choice key on basename(app_dir), so existing
//      dirs keep their (mis)names until a cold re-install of that app.
//   2. android:versionName from the apktool text manifest (kept as a
//      belt-and-suspenders fallback for an unreadable binary manifest).
//   3. The version already embedded in `app_pkg` (CheapPackageName, i.e.
//      "<pkg>_<ver>" parsed from the APK / bundle filename). Matching it
//      keeps the unpack dir and the runtime dir identical -> cache reuse.
//   4. GuessVersionFromApkFilename(apk_path) -- when app_pkg carries no
//      embedded version (a plain "<pkg>.apk" input).
//   5. platformBuildVersionName -- LAST resort. The Android PLATFORM
//      build version; correct as a suffix only for older APKs where it
//      coincides with the real version. For Crossy Road (no
//      android:versionName; platformBuildVersionName "15" = Android 15;
//      filename version "7.11.1") preferring the filename keeps the dir
//      stable at "<pkg>_7.11.1" so the unpack + translate cache stays warm.
std::string DeriveAppVersion(const std::string& manifest_path,
                             const std::string& pkg,
                             const std::string& app_pkg,
                             const std::string& apk_path) {
    (void)manifest_path;  // the text manifest is not read any more (v1.0.1590)
    std::string v = SanitizeVersionChunk(BinaryManifestVersionName(apk_path));
    if (!v.empty()) return v;
    // Version embedded in the filename-derived package name ("<pkg>_<ver>").
    if (!pkg.empty() && app_pkg.size() > pkg.size() + 1
        && app_pkg.compare(0, pkg.size(), pkg) == 0
        && app_pkg[pkg.size()] == '_') {
        std::string emb = SanitizeVersionChunk(app_pkg.substr(pkg.size() + 1));
        if (!emb.empty() && emb[0] >= '0' && emb[0] <= '9') return emb;
    }
    v = GuessVersionFromApkFilename(apk_path, pkg);
    if (!v.empty()) return v;
    return SanitizeVersionChunk(BinaryManifestPlatformBuildVersionName(apk_path));
}

// Quote a string for safe interpolation into a /bin/sh command line:
// wrap in single quotes, embedded single quotes spliced as '\''. The
// android root can live inside a per-app .app bundle whose label (and
// so path) is user-visible text -- the registry on this machine already
// contains a label with an apostrophe -- so raw '...' wrapping is not
// enough.
std::string ShQuote(const std::string& s) {
    std::string out = "'";
    for (char c : s) {
        if (c == '\'') out.append("'\\''");
        else out.push_back(c);
    }
    out.push_back('\'');
    return out;
}

// Serialize same-package install finalization across PROCESSES. The
// launcher installs 4-wide; without this, two installs of one package
// and version can interleave "replace the code dir / strip the data
// dir's stale artifacts / extract assets" and leave a state no single
// install produced. mkdir is the atomic primitive; a pid file inside
// lets a crashed holder be stolen (same ESRCH pattern as the v1.0.848
// staging sweep). Failure to acquire degrades to proceeding unlocked --
// the pre-lock behavior -- because wedging every install behind a stuck
// sibling is worse than the narrow race.
struct PkgFinalizeLock {
    fs::path dir;
    bool held = false;
    bool contended = false;  // true when another live holder made us wait
};

PkgFinalizeLock AcquirePkgFinalizeLock(const fs::path& apps_root,
                                       const std::string& pkg) {
    PkgFinalizeLock lk;
    lk.dir = apps_root / (".macapk_pkglock_" + pkg);
    for (int attempt = 0; attempt < 1200; ++attempt) {  // ~2 min at 100 ms
        std::error_code ec;
        if (fs::create_directory(lk.dir, ec) && !ec) {
            std::ofstream pf(lk.dir / "pid");
            pf << getpid() << '\n';
            lk.held = true;
            return lk;
        }
        long pid = 0;
        {
            std::ifstream pf(lk.dir / "pid");
            pf >> pid;
        }
        if (pid > 0 && kill(static_cast<pid_t>(pid), 0) == -1
            && errno == ESRCH) {
            // Holder died mid-install; its lock is a leak, not a claim.
            std::error_code rec;
            fs::remove_all(lk.dir, rec);
            continue;
        }
        lk.contended = true;
        usleep(100 * 1000);
    }
    std::fprintf(stderr,
        "[macapk] WARN: could not acquire install lock %s after 2 min; "
        "proceeding unlocked\n", lk.dir.c_str());
    return lk;
}

void ReleasePkgFinalizeLock(PkgFinalizeLock& lk) {
    if (!lk.held) return;
    std::error_code ec;
    fs::remove_all(lk.dir, ec);
    lk.held = false;
}

// Resolve the user-visible android root and populate `${root}/data/data
// /<package>/` with symlinks back into the apkt-extracted assets. We
// symlink rather than copy so (a) we don't waste disk on a per-launch
// duplicate of the APK contents, and (b) updates to the APK (replacing
// `Apks/foo.apk` with a newer build) are picked up on the next launch
// without the user having to clear anything.
//
// Remove the copies of assets/* that an EARLIER launch extracted into the
// data dir, now that assets are read from the APK in place (Phase 3 of
// docs/disk-footprint-and-space-efficiency_2026-09-19.md). Only what WE put
// there goes: a file is deleted iff the installed apk set has the entry
// `assets/<rel>` with the same byte size AND the same mtime to the minute --
// `tar` wrote each file with the zip entry's DOS timestamp (1979-1981 dates
// in every corpus apk), which no file an app writes itself carries. A file the
// app edited in place (Rocks 'n' Diamonds writes user levels into extracted
// `levels/`) differs in size or mtime and is kept; app data (files/, cache/,
// shared_prefs/, databases/, saves at the root) is never an apk entry at all.
// Empty directories left behind are pruned. One pass per data dir: the
// `.macapk_assets_fingerprint` stamp that recorded the extraction is removed
// afterwards, so a dir never re-walks its apks on every launch.
static void ReclaimExtractedAssets(const fs::path& data_dir,
                                   const std::vector<std::string>& members) {
    std::error_code ec;
    // Once per data dir: the marker says a reclaim already ran (the extraction
    // path removes it when it extracts, so a MACAPK_EXTRACT_ASSETS=1 bisect
    // followed by a default run reclaims again). The extraction STAMP is left
    // alone -- it still gates the native-lib unzip below.
    fs::path done = data_dir / ".macapk_assets_reclaimed";
    if (fs::exists(done, ec)) return;
    if (!fs::exists(data_dir, ec)) return;
    size_t removed = 0, kept = 0;
    unsigned long long bytes = 0;
    std::set<fs::path> touched_dirs;
    for (const auto& m : members) {
        std::string out;
        if (!macapk::RunCaptureCmd("unzip -l " + ShQuote(m) + " 'assets/*' 2>/dev/null", &out))
            continue;
        std::istringstream ls(out);
        std::string line;
        while (std::getline(ls, line)) {
            // "  Length      Date    Time    Name" rows: size, MM-DD-YYYY, HH:MM, name
            std::istringstream row(line);
            std::string size_tok, date_tok, time_tok;
            if (!(row >> size_tok >> date_tok >> time_tok)) continue;
            if (size_tok.find_first_not_of("0123456789") != std::string::npos) continue;
            if (date_tok.size() != 10 || time_tok.size() != 5) continue;
            std::string name;
            std::getline(row, name);
            size_t st = name.find_first_not_of(' ');
            if (st == std::string::npos) continue;
            name = name.substr(st);
            if (name.compare(0, 7, "assets/") != 0 || name.size() <= 7 || name.back() == '/') continue;
            fs::path f = data_dir / name.substr(7);
            struct stat sb;
            if (::lstat(f.c_str(), &sb) != 0 || !S_ISREG(sb.st_mode)) continue;
            if (static_cast<unsigned long long>(sb.st_size) != std::stoull(size_tok)) { ++kept; continue; }
            // The listed local time, to the minute, vs the file's mtime.
            struct tm t{};
            t.tm_mon  = std::atoi(date_tok.substr(0, 2).c_str()) - 1;
            t.tm_mday = std::atoi(date_tok.substr(3, 2).c_str());
            t.tm_year = std::atoi(date_tok.substr(6, 4).c_str()) - 1900;
            t.tm_hour = std::atoi(time_tok.substr(0, 2).c_str());
            t.tm_min  = std::atoi(time_tok.substr(3, 2).c_str());
            t.tm_isdst = -1;
            time_t listed = ::mktime(&t);
            long long delta = static_cast<long long>(sb.st_mtimespec.tv_sec) - static_cast<long long>(listed);
            if (delta < -120 || delta > 120) { ++kept; continue; }
            if (::unlink(f.c_str()) == 0) {
                ++removed;
                bytes += static_cast<unsigned long long>(sb.st_size);
                touched_dirs.insert(f.parent_path());
            }
        }
    }
    // Prune directories emptied by the removal, deepest first, never data_dir.
    std::vector<fs::path> dirs(touched_dirs.begin(), touched_dirs.end());
    std::sort(dirs.begin(), dirs.end(), [](const fs::path& a, const fs::path& b) {
        return a.string().size() > b.string().size();
    });
    for (auto d : dirs) {
        while (d != data_dir && d.string().size() > data_dir.string().size()) {
            if (!fs::is_empty(d, ec) || ec) break;
            fs::remove(d, ec);
            d = d.parent_path();
        }
    }
    { std::ofstream m(done); m << removed << ' ' << bytes << '\n'; }
    if (removed || kept)
        std::fprintf(stderr,
            "[macapk] android-root: reclaimed %zu extracted asset file(s), %.1f MB (kept %zu the app changed); "
            "assets are read from the APK from now on\n", removed, bytes / 1e6, kept);
}

// Returns the resolved (absolute, ~-expanded) android root.
std::string SetupAndroidRoot(const std::string& cli_root,
                              const UnpackedApp& app) {
    // CLI > MACAPK_ANDROID_ROOT env > $HOME default, tilde-expanded,
    // trailing "android" component forced (keeps the user's chosen
    // location tidy and matches the launcher UI promise). Shared
    // resolver: three copies of this chain used to live in this file
    // and any divergence splits the cache from the data tree.
    std::string root = ResolveAndroidRootDir(cli_root);

    // The data dir's NAME is basename(app_dir), by construction. That is
    // the one rule that cannot diverge from the Java side: Boot.java
    // derives its persistent-data dir lexically as
    // `new File(appDir).getName()` (Boot.java ~:760), so re-deriving a
    // version here (the old code) could disagree with Boot whenever the
    // derivation changed -- exactly the split-brain Boot.java's own
    // comment block documents for Crossy Road. app_dir is final by the
    // time this runs (finalize on cold paths, the cache pre-scan on warm
    // ones), and its basename is version-qualified per the coexistence
    // model ("<pkg>_<ver>", or bare "<pkg>" for versionless APKs).
    std::string pkg = fs::path(app.app_dir).filename().string();
    if (pkg.empty()) {
        pkg = BinaryManifestPackage(app.apk_path);
        if (pkg.empty()) pkg = app.package_name;
    }

    // APK identity fingerprint ("<size>:<mtime>"). Until v1.0.943 a
    // fingerprint MISMATCH rotated the whole dir aside ("<name>-2") so
    // the new APK started with fresh data -- but AOSP treats a
    // same-version reinstall as an ordinary update that PRESERVES
    // /data/data/<pkg>, and on a machine that rebuilds test APKs
    // constantly the mismatch is the COMMON case, so rotation was
    // destroying saves as routine (the rotated `-2` dirs on this
    // machine are its fossil record). The mismatch now simply means
    // the freshness gates below miss and the code-side artifacts
    // regenerate in place; app data is not touched. The fingerprint
    // file remains as the assets-extraction identity input and as a
    // diagnostic.
    auto compute_apk_fp = [](const fs::path& apk) -> std::string {
        std::error_code lec;
        std::uintmax_t sz = fs::file_size(apk, lec);
        if (lec) return {};
        auto mt = fs::last_write_time(apk, lec);
        if (lec) return {};
        long long mt_ticks = static_cast<long long>(
            mt.time_since_epoch().count());
        return std::to_string(static_cast<unsigned long long>(sz)) + ":"
             + std::to_string(mt_ticks);
    };
    fs::path data_dir_candidate = fs::path(root) / "data" / "data" / pkg;
    fs::path fp_file = data_dir_candidate / ".macapk_apk_fingerprint";
    // v1.0.1586: app.apk_path is ALWAYS the installed base.apk now (Phase 2 --
    // no synthetic _merged.apk), a stable file whose mtime the unpacker carries
    // over from the container it came from, so a split set fingerprints
    // exactly like a plain apk. The v1.0.152 "bundle inputs have no
    // fingerprint" special case is gone with the merge; a cached dir that still
    // holds one is handled by the unpacker.
    const bool is_bundle_merged = false;
    std::string new_fp = compute_apk_fp(app.apk_path);

    std::error_code ec;
    // Defensive cleanup: macOS Finder and iCloud Drive will sometimes
    // create "name 2", "name 3" siblings when a directory write
    // conflicts with another process or with the sync daemon. The
    // duplicates are usually empty (the actual content stays in the
    // canonical name) but they accumulate visibly in Finder. We sweep
    // them at startup so each launch begins from a clean tree.
    //
    // Scope: just empty " 2"/" 3"/.../" 9" siblings of the three names
    // we own under android_root: <root>, <root>/data, <root>/data/data.
    // We never touch a non-empty duplicate -- if the user actually
    // saved content there, it stays.
    auto sweep_conflict_siblings = [](const fs::path& canonical) {
        if (canonical.empty()) return;
        fs::path parent = canonical.parent_path();
        std::string name = canonical.filename().string();
        if (parent.empty() || name.empty()) return;
        std::error_code lec;
        for (int i = 2; i <= 9; ++i) {
            fs::path dup = parent / (name + " " + std::to_string(i));
            if (!fs::exists(dup, lec)) continue;
            if (!fs::is_directory(dup, lec)) continue;
            if (!fs::is_empty(dup, lec)) continue;  // keep user data
            fs::remove(dup, lec);
            if (!lec) {
                std::fprintf(stderr,
                    "[macapk] android-root: removed empty conflict "
                    "duplicate %s\n", dup.c_str());
            }
        }
    };
    fs::path data_dir = fs::path(root) / "data" / "data" / pkg;
    sweep_conflict_siblings(fs::path(root));
    sweep_conflict_siblings(fs::path(root) / "data");
    sweep_conflict_siblings(fs::path(root) / "data" / "data");
    ReplaceCompatLink(data_dir);
    fs::create_directories(data_dir, ec);
    if (ec) {
        std::fprintf(stderr,
            "[macapk] android-root: cannot create %s: %s\n",
            data_dir.c_str(), ec.message().c_str());
        return root;
    }
    // Extract the APK's assets/ tree directly into data/data/<pkg>/.
    // apktool only extracts a subset of asset files (it filters out
    // a lot of binary content even with no flags), so we can't rely
    // on `_apkt/assets/` as a complete source. The APK is just a zip;
    // shell out to unzip to populate the dir.
    //
    // The naive command `unzip -j 'assets/*'` (junk paths) flattens
    // EVERYTHING -- assets/icons/ship_01.png becomes ship_01.png in
    // the root, breaking apps that hardcode subdir paths like
    // `/data/data/<pkg>/icons/ship_01.png`. GD Lite fails exactly
    // this way: it looks for icons/ship_01.png, doesn't find it,
    // and SIGSEGVs in CCSpriteFrame::getTexture on a NULL sprite.
    //
    // Correct behavior: strip the LEADING "assets/" component only,
    // preserve everything else (subdirectories and all). bsdtar
    // (the default `tar` on macOS) reads zip natively and supports
    // `--strip-components=1` which does exactly this -- extracts
    // `assets/icons/ship_01.png` as `icons/ship_01.png` under the
    // target dir.
    //
    // Idempotent: a re-run replaces stale assets with whatever's in
    // the current APK -- UNLESS the freshness gate right below finds a
    // fingerprint match, in which case extraction is skipped entirely
    // (the on-disk copy from the last successful extraction already
    // matches). Save files (CCGameManager.dat, etc.) live alongside and
    // are not touched because they have different names.
    fs::path apk_for_extract;
    if (!app.apk_path.empty() && fs::exists(app.apk_path)) {
        apk_for_extract = app.apk_path;
    }
    // The installed split set: base.apk plus its split_*.apk siblings (Phase 2,
    // v1.0.1586). On a device every member's assets/ are visible through the
    // one AssetManager and the ABI split is where the libs live, so both
    // extraction steps below run over the whole set, base first.
    std::vector<std::string> apk_members;
    if (!apk_for_extract.empty()) {
        apk_members.push_back(apk_for_extract.string());
        for (const auto& sp : app.split_paths)
            if (fs::exists(sp)) apk_members.push_back(sp);
    }
    fs::path apkt_dir = fs::path(app.app_dir) / "_apkt";
    if (apk_for_extract.empty()) {
        std::fprintf(stderr,
            "[macapk] android-root: no APK to extract; data/data/%s "
            "will only contain whatever was in apkt/assets/\n",
            pkg.c_str());
    } else {
        // ---- Freshness gate (efficiency audit 2026-07-24) ----
        // Both extraction steps below used to run UNCONDITIONALLY on
        // every launch, even when the exact same bytes were already
        // sitting on disk from the previous launch of the identical
        // APK -- measured ~7-11s / ~1-1.4 GB rewritten per relaunch for
        // a big asset-heavy APK (MCPE), scaling down proportionally for
        // smaller ones. Real Android never pays this cost even once:
        // AssetManager mmaps the installed APK's assets on demand and
        // native-lib extraction (when it happens at all -- AGP 3.6+
        // defaults extractNativeLibs=false) is a one-time install-time
        // step, not a per-launch one. Gate on the SAME kind of
        // "<size>:<mtime>" fingerprint + stamp-file pattern
        // apk_unpacker.cpp's CacheIsFresh/WriteStamp already uses 250
        // lines away in the same install pipeline
        // (apk_unpacker.cpp:307-338) and Boot.java's extractMetaInfServices
        // uses for a different subset of this same APK's zip
        // (Boot.java:937-945) -- this is reapplying an already-reviewed,
        // already-in-production pattern one layer up, not inventing a
        // new one. See docs/efficiency-assets-datadir_2026-07-24.md
        // Finding 1.
        //
        // Identity value: new_fp (computed above for the ROTATION check)
        // already means exactly "did the APK's bytes change since we last
        // touched this data dir" -- reuse it rather than recomputing. Since
        // v1.0.1586 that holds for a split set too: the installed base.apk
        // is a stable file (its mtime carries the container's), and a new
        // version of the package is a new base.apk. (The bundle-only
        // `.source-mtime` fallback that keyed off the container went with
        // the merge; a bundle app installed before this change re-extracts
        // its assets/libs ONCE when its identity string changes shape.)
        std::string assets_identity = new_fp;
        (void)is_bundle_merged;
        // v1.0.833: the extraction THIS stamp guards is ABI-PRUNED -- it writes
        // only `_apkt/lib/<chosen abi>` -- so the ABI SELECTOR is part of what
        // the cache holds and has to be part of its key.
        //
        // v1.0.827 made exactly this fix, but for apk_unpacker.cpp's
        // ".source-mtime". This is a DIFFERENT stamp guarding a DIFFERENT tree,
        // and it was never keyed, so the hole survived: after a
        // MACAPK_FORCE_ABI=armeabi-v7a run, a later DEFAULT run matched on APK
        // identity alone, skipped the unzip, and ran on the ARM32 tree. Observed
        // directly on 2026-07-29 -- an arm64 run logging
        // "preloadAllApkLibs: 2 .so file(s) under _apkt/lib/armeabi-v7a", with
        // ".source-mtime" reading selector '-' while the content was v7a, so the
        // v827 check could not fire. It reads as an app regression, and it has
        // now cost three false REGRESS calls across two sessions.
        //
        // Appended only when NON-EMPTY on purpose: an empty identity disables
        // the freshness check altogether (see `!assets_identity.empty()`
        // below), and making it non-empty here would silently switch that check
        // on for inputs that deliberately opt out.
        //
        // One-time cost: every already-installed app has a stamp without this
        // suffix, so each re-extracts once on next launch. Self-healing, and the
        // alternative is continuing to run apps on the wrong ABI.
        if (!assets_identity.empty()) {
            const char* forced_abi = std::getenv("MACAPK_FORCE_ABI");
            assets_identity += "|abi=";
            assets_identity += (forced_abi && *forced_abi) ? forced_abi : "-";
        }
        // ---- Assets (legacy, MACAPK_EXTRACT_ASSETS=1 only) --------------
        // Dedicated marker file, deliberately SEPARATE from
        // .macapk_apk_fingerprint (fp_file) above: that file's job is
        // rotation (plain apks only, never written for bundles); this
        // one's job is "did the last asset extraction for this identity
        // fully complete". Phase 3 (v1.0.1587): assets are read from the
        // APK. A tree that an earlier build extracted is reclaimed here, on
        // ANY launch without extraction. MACAPK_EXTRACT_ASSETS=1 restores
        // the extraction for a bisect.
        fs::path assets_fp_file = data_dir_candidate / ".macapk_assets_fingerprint";
        bool force_reextract = false;
        if (const char* f = std::getenv("MACAPK_FORCE_REEXTRACT"))
            force_reextract = (f[0] == '1');
        {
            const char* xa = std::getenv("MACAPK_EXTRACT_ASSETS");
            const bool extract_assets = xa && xa[0] == '1';
            if (!extract_assets) {
                ReclaimExtractedAssets(data_dir, apk_members);
            } else {
                bool fresh = false;
                if (!force_reextract && !assets_identity.empty()) {
                    std::error_code fresh_ec;
                    if (fs::exists(assets_fp_file, fresh_ec)) {
                        std::ifstream sf(assets_fp_file);
                        std::string stamped;
                        std::getline(sf, stamped);
                        bool data_dir_populated = false;
                        for (auto& de : fs::directory_iterator(data_dir, fresh_ec)) {
                            (void)de; data_dir_populated = true; break;
                        }
                        fresh = !stamped.empty() && stamped == assets_identity
                                && data_dir_populated;
                    }
                }
                if (fresh) {
                    std::fprintf(stderr,
                        "[macapk] android-root: assets already extracted and match "
                        "the current APK -- skipping tar (MACAPK_FORCE_REEXTRACT=1 "
                        "to force)\n");
                } else {
                    { std::error_code rmec; fs::remove(data_dir / ".macapk_assets_reclaimed", rmec); }
                    // Every member of the set, base first: a later split's file
                    // of the same name overwrites (tar -x overwrites), which
                    // matches the AssetManager's "last mounted wins" order. A
                    // member with no assets/ makes tar return non-zero for "no
                    // files"; that is benign and must not mask a real failure
                    // from another member, so the rc that is kept is the
                    // BASE's (a split without assets is the common case).
                    int rc = 0;
                    for (size_t mi = 0; mi < apk_members.size(); ++mi) {
                        std::string ucmd = "tar -xf " + ShQuote(apk_members[mi])
                            + " -C " + ShQuote(data_dir.string())
                            + " --strip-components=1 'assets/*' 2>&1";
                        int mrc = std::system(ucmd.c_str());
                        if (mi == 0) rc = mrc;
                        if (mrc != 0) {
                            std::fprintf(stderr,
                                "[macapk] tar 'assets/*' from %s to %s returned rc=%d "
                                "(non-fatal -- a split without assets/ does this)\n",
                                fs::path(apk_members[mi]).filename().c_str(),
                                data_dir.c_str(), mrc);
                        }
                    }
                    if (rc == 0 && !assets_identity.empty()) {
                        std::error_code wec2;
                        fs::create_directories(data_dir_candidate, wec2);
                        std::ofstream sf(assets_fp_file);
                        if (sf) sf << assets_identity;
                    }
                }
            }
        }

        // ---- Native libraries: the INSTALL directory's lib/<isa>/ --------
        // Phase 4 (v1.0.1588) of docs/disk-footprint-and-space-efficiency_
        // 2026-09-19.md. On a device the installer copies the one supported
        // ABI's libraries out of the apk set into the package's own install
        // directory, and that is the ONLY place they live:
        //   frameworks/base/core/java/com/android/internal/content/
        //   NativeLibraryHelper.java:334-337,361 (android11-release)
        //     final String instructionSet = VMRuntime.getInstructionSet(supportedAbi);
        //     subDir = new File(libraryRoot, instructionSet);
        //     copyNativeBinaries(handle, subDir, supportedAbi);
        //   services/core/java/com/android/server/pm/PackageAbiHelperImpl.java:189-193
        //     nativeLibraryRootDir = new File(codeFile, LIB_DIR_NAME)   // "lib"
        //     nativeLibraryDir = new File(nativeLibraryRootDir, getPrimaryInstructionSet(abis))
        //   core/jni/com_android_internal_content_NativeLibraryHelper.cpp:270-278
        //     utimes(<zip entry mtime>); chmod(0755)
        // So: <root>/data/app/~~<nonce>/<pkg>-<nonce>/lib/<isa>/lib<name>.so,
        // where <isa> is the INSTRUCTION SET ("arm64" for arm64-v8a, "arm"
        // for armeabi-v7a/armeabi), file names and zip mtimes kept, 0755.
        // ApplicationInfo.nativeLibraryDir names this directory, the
        // translator reads its input here, a Stage-6 wrapper re-opens its
        // source ELF here, and /proc/self/maps names the same path -- one
        // directory, as on a device. Before this the libraries were
        // extracted under the apktool tree (<code>/_apkt/lib/<abi>/), with
        // a symlink tree (.macapk/app/<sub>/lib/<isa>) published as the
        // nativeLibraryDir and HARDLINKS materialised into data/app on
        // demand -- three names for one file, and after every re-extraction
        // two copies of it.
        //
        // The freshness stamp lives WITH the libraries (lib/.macapk-native),
        // not in the data dir: two versions of one package can share an
        // install dir under a harness root (the sweep's hcr143/hcr1681 rows
        // alternate the same scratch root), and a stamp in the per-version
        // data dir would call the OTHER version's libraries fresh. Keyed on
        // the installed base.apk's identity plus the ABI selector: a
        // MACAPK_FORCE_ABI run re-extracts, and the default run after it
        // re-extracts again (measured cost: one unzip). Only ONE isa dir
        // exists at a time -- the previous tree is removed before the new
        // one is written -- because that is what an install looks like.
        //
        // The unzip lands in a staging dir inside lib/ and is renamed into
        // place, so a killed run leaves no half-written isa dir behind (the
        // stamp is written last and only on success).
        fs::path install_dir;
        if (!app.installed_apk.empty()) {
            // Belt and braces on top of apk_unpacker's own check: the ONLY
            // directory this block may remove_all under is a real install
            // dir, <root>/data/app/~~<nonce>/<pkg>-<nonce>. Anything else
            // (a source apk's folder) keeps the legacy target.
            fs::path cand = fs::path(app.installed_apk).parent_path();
            fs::path nonce_dir = cand.parent_path();
            bool shape_ok = !cand.empty()
                && nonce_dir.filename().string().rfind("~~", 0) == 0
                && nonce_dir.parent_path().filename() == "app"
                && nonce_dir.parent_path().parent_path().filename() == "data";
            if (shape_ok) install_dir = cand;
            else std::fprintf(stderr,
                "[macapk] install dir: %s does not have the /data/app shape; "
                "native libs go to the legacy _apkt/lib tree\n", cand.c_str());
        }
        // Legacy target only when there is no install dir at all (the root
        // could not be written, in which case EnsureInstalledPackage already
        // reported that it is running from the source apk).
        fs::path lib_root = install_dir.empty() ? (apkt_dir / "lib") : (install_dir / "lib");
        fs::path lib_stamp = lib_root / ".macapk-native";
        macapk::ApkSetAbiChoice set_abi = macapk::SelectApkSetAbi(apk_members);
        macapk::ApkAbiChoice abi = set_abi.choice;
        const std::string isa = macapk::InstructionSetOf(abi.chosen);
        std::string lib_identity = assets_identity;   // apk identity + "|abi=<selector>"
        if (!lib_identity.empty()) lib_identity += "|isa=" + (isa.empty() ? std::string("-") : isa);
        bool libs_fresh = false;
        if (!force_reextract && !lib_identity.empty()) {
            std::error_code fresh_ec;
            if (fs::exists(lib_stamp, fresh_ec)) {
                std::ifstream sf(lib_stamp);
                std::string stamped;
                std::getline(sf, stamped);
                libs_fresh = !stamped.empty() && stamped == lib_identity
                             && (isa.empty() || fs::is_directory(lib_root / isa, fresh_ec));
            }
        }
        if (libs_fresh) {
            std::fprintf(stderr,
                "[macapk] install dir: lib/%s already holds this APK's native "
                "libraries -- skipping unzip (MACAPK_FORCE_REEXTRACT=1 to force)\n",
                isa.empty() ? "-" : isa.c_str());
        } else {
            std::error_code lec;
            fs::remove_all(lib_root, lec);
            fs::create_directories(lib_root, lec);
            int lrc = 0;
            size_t chmodded = 0;
            if (abi.present.empty()) {
                std::fprintf(stderr,
                    "[macapk] APK has no lib/ tree (pure Java/Kotlin app)\n");
            } else if (abi.chosen.empty()) {
                // Every ABI present is one we cannot run (x86-only builds are
                // the realistic case). Say so LOUDLY: the app will still boot
                // but its native code is gone, and a silent stub fallback is
                // exactly the kind of thing that burns an hour to re-diagnose.
                std::string names;
                for (const auto& a : abi.present)
                    names += (names.empty() ? "" : ", ") + a;
                std::fprintf(stderr,
                    "[macapk] APK ships NO usable ABI (has: %s) -- Mac.apk "
                    "runs arm64-v8a/armeabi-v7a/armeabi only. Native code "
                    "will fall back to a stub dylib.\n", names.c_str());
            } else {
                fs::path staging = lib_root / ".extracting";
                fs::remove_all(staging, lec);
                fs::create_directories(staging, lec);
                // From every member that carries the chosen ABI (the ABI split
                // on a bundle; base.apk on a plain apk; occasionally both).
                for (const auto& carrier : set_abi.carriers) {
                    std::string libcmd = "unzip -oq " + ShQuote(carrier)
                        + " 'lib/" + abi.chosen + "/*' -d "
                        + ShQuote(staging.string()) + " 2>&1";
                    int crc = std::system(libcmd.c_str());
                    if (crc != 0) lrc = crc;
                }
                fs::path extracted = staging / "lib" / abi.chosen;
                // Info-ZIP rc 1 = "completed with warnings" (extra bytes,
                // mismatched local names): the files are there. Keep them;
                // fail only on rc >= 2 or an empty result.
                if (lrc == 1 && fs::is_directory(extracted, lec)
                    && !fs::is_empty(extracted, lec)) lrc = 0;
                if (lrc == 0 && !fs::is_directory(extracted, lec)) lrc = -1;
                if (lrc == 0) {
                    fs::rename(extracted, lib_root / isa, lec);
                    if (lec) lrc = -2;
                }
                fs::remove_all(staging, lec);
                if (lrc != 0) {
                    std::fprintf(stderr,
                        "[macapk] unzip 'lib/%s/*' into %s returned rc=%d -- the "
                        "translator will fall back to a stub dylib and the "
                        "app's native code will be unavailable\n",
                        abi.chosen.c_str(), lib_root.c_str(), lrc);
                } else {
                    // Android's PackageManager extracts native libs 0755, and
                    // some apps SHIP AN EXECUTABLE in lib/ and fork it as a
                    // subprocess -- the .so name is just how you get a file
                    // into nativeLibraryDir with the exec bit on a real device.
                    // `unzip` reproduces the archive's stored mode instead
                    // (0644 in every APK checked), so those apps see a
                    // non-executable file and take their "broken install" path.
                    //
                    // Simon Tatham's Puzzles is the concrete case: it forks
                    // lib/<abi>/libpuzzlesgen.so, and on a 0644 extraction it
                    // toasts "Puzzles is missing a required file! Try
                    // reinstalling from Google Play", then returns from
                    // onCreate WITHOUT calling setContentView -- which is why
                    // it renders a blank window and why that looked for a long
                    // time like a renderer bug rather than a file-mode bug.
                    std::error_code pec;
                    for (fs::directory_iterator it(lib_root / isa, pec), end; it != end;
                         it.increment(pec)) {
                        if (pec) break;
                        if (!it->is_regular_file(pec)) continue;
                        fs::permissions(it->path(),
                                        fs::perms::owner_all
                                        | fs::perms::group_read | fs::perms::group_exec
                                        | fs::perms::others_read | fs::perms::others_exec,
                                        fs::perm_options::replace, pec);
                        if (!pec) ++chmodded;
                    }
                    std::fprintf(stderr,
                        "[macapk] installed lib/%s from lib/%s (%zu ABI(s) in the APK,"
                        " skipped %.1f MB of unusable/duplicate native libs;"
                        " %zu file(s) set 0755 as PackageManager does) at %s\n",
                        isa.c_str(), abi.chosen.c_str(), abi.present.size(),
                        double(abi.skipped_bytes) / 1e6, chmodded, lib_root.c_str());
                }
            }
            // Stamp success only when the extraction fully completed -- a
            // partial/failed one must never be cached as "fresh".
            if (lrc == 0 && !lib_identity.empty()) {
                std::ofstream sf(lib_stamp);
                if (sf) sf << lib_identity;
            }
            // Migration from the pre-v1.0.1588 layout, once the install dir
            // holds the libraries: the old primary copy under the apktool
            // tree and the published symlink tree are reclaimed. Both are
            // regenerable and neither is read by this build.
            if (lrc == 0 && !install_dir.empty()) {
                std::error_code mec;
                size_t old_bytes = 0;
                fs::path old_libs = apkt_dir / "lib";
                if (fs::is_directory(old_libs, mec)) {
                    for (fs::recursive_directory_iterator it(old_libs, mec), end;
                         !mec && it != end; it.increment(mec))
                        if (it->is_regular_file(mec)) old_bytes += fs::file_size(it->path(), mec);
                    fs::remove_all(old_libs, mec);
                    std::fprintf(stderr,
                        "[macapk] install dir: reclaimed the pre-v1.0.1588 _apkt/lib "
                        "tree (%.1f MB)\n", double(old_bytes) / 1e6);
                }
                fs::path published = fs::path(root) / ".macapk" / "app"
                                   / fs::path(app.app_dir).filename();
                if (fs::is_directory(published, mec)) {
                    // Only symlinks and the directories that held them; a
                    // regular file here is not ours and stays.
                    bool only_links = true;
                    for (fs::recursive_directory_iterator it(published, mec), end;
                         !mec && it != end; it.increment(mec))
                        if (!it->is_symlink() && !it->is_directory()) { only_links = false; break; }
                    if (only_links) fs::remove_all(published, mec);
                }
            }
        }
    }
    // Stamp the fingerprint we computed earlier into the data dir,
    // so the next launch's collision-detect-and-rotate can tell
    // whether the APK at the same path is the same content or a
    // different one. Idempotent: same APK on re-run -> same write,
    // no rotation. Best-effort: failure to write doesn't abort.
    if (!new_fp.empty()) {
        std::error_code wec;
        bool exists_now = fs::exists(data_dir_candidate, wec);
        (void)wec;
        if (exists_now) {
            std::ofstream ff(fp_file);
            if (ff) ff << new_fp;
        }
    }
    // Finished-stage marker for InstallCore.extract()'s sub-stage progress
    // (see `stage merged` in apk_unpacker.cpp). "Extracted" includes the
    // fresh-skip branch: either way the assets are on disk when this prints.
    std::fprintf(stderr, "[macapk] stage assets-extracted\n");
    std::fprintf(stderr,
        "[macapk] android root = %s (data/data/%s populated)\n",
        root.c_str(), pkg.c_str());
    return root;
}

// As above but also returns the resolved package name so the caller
// can pass the SAME name to the shim's __macapk_set_package_name --
// otherwise the shim's bare-relative-name resolver disagrees with the
// directory we just created.
struct AndroidRootResult {
    std::string root;
    std::string pkg;
};
AndroidRootResult SetupAndroidRootEx(const std::string& cli_root,
                                       const UnpackedApp& app) {
    AndroidRootResult r;
    // Same chain as the finalize-time naming (see the staged_fresh block):
    // the binary manifest, then the filename heuristic. Keeping the two
    // identical is what guarantees the shim's package (bare-relative-name
    // resolver) agrees with the dir the finalize created.
    r.pkg = BinaryManifestPackage(app.apk_path);
    if (r.pkg.empty()) r.pkg = app.package_name;
    r.root = SetupAndroidRoot(cli_root, app);
    return r;
}

// Map an android:screenOrientation attribute value to the window aspect we
// can represent. AOSP orientation families (frameworks/base ActivityInfo
// SCREEN_ORIENTATION_*): the *Landscape/*Portrait families pin a side; the
// rest (unspecified / sensor / fullSensor / user / fullUser / nosensor /
// behind / locked) do NOT -- they follow the device's rotation, and a macOS
// desktop window has no rotation sensor, so those collapse to the phone
// default (portrait) rather than a forced landscape. Returning AUTO for the
// non-pinning families lets the caller apply that default.
static Orient MapOrientationFamily(std::string v) {
    for (auto& c : v) c = static_cast<char>(std::tolower(c));
    if (v == "landscape" || v == "sensorlandscape"
        || v == "reverselandscape" || v == "userlandscape")
        return Orient::LANDSCAPE;
    if (v == "portrait" || v == "sensorportrait"
        || v == "reverseportrait" || v == "userportrait")
        return Orient::PORTRAIT;
    return Orient::AUTO;  // unspecified/sensor/user/nosensor/behind/locked/...
}

// Resolve the LAUNCHER activity's android:screenOrientation from the
// binary AndroidManifest.xml, AOSP-faithfully. Android starts the
// activity whose <intent-filter> declares BOTH
// android.intent.action.MAIN and android.intent.category.LAUNCHER, in THAT
// activity's requested orientation; every other activity's screenOrientation
// is irrelevant until that activity is actually started. So we read only the
// launcher's value -- NOT "any activity, prefer landscape", which forced the
// whole app landscape whenever a secondary/ad-SDK activity happened to declare
// landscape. An app whose launcher doesn't pin a side (unspecified / sensor /
// absent -- e.g. Hill Climb 1.8.1's MainActivity, which has configChanges but
// no screenOrientation) returns AUTO, so the caller defaults to portrait
// instead of forcing landscape. The SwiftUI launcher's --orientation override
// still wins over whatever this returns (handled by the caller).
//
// Runs BEFORE the JVM exists (Manifest.java re-parses post-boot) to size the
// first Cocoa window. Returns Orient::AUTO when no launcher activity is found
// or its orientation is in a non-pinning family.
// The compiled attribute is an INT enum (ActivityInfo.SCREEN_ORIENTATION_*,
// android11-release core/java/android/content/pm/ActivityInfo.java): the
// names below are the attrs.xml symbols for those values, handed to
// MapOrientationFamily (which the Java side, Manifest.java, shares).
static const char* ScreenOrientationName(int v) {
    switch (v) {
        case 0:  return "landscape";
        case 1:  return "portrait";
        case 2:  return "user";
        case 3:  return "behind";
        case 4:  return "sensor";
        case 5:  return "nosensor";
        case 6:  return "sensorLandscape";
        case 7:  return "sensorPortrait";
        case 8:  return "reverseLandscape";
        case 9:  return "reversePortrait";
        case 10: return "fullSensor";
        case 11: return "userLandscape";
        case 12: return "userPortrait";
        case 13: return "fullUser";
        case 14: return "locked";
        default: return "unspecified";
    }
}

// v1.0.1590: read from the BINARY manifest (Phase 5 step 8/9 -- no text
// decode exists). manifest_path is accepted for signature stability only.
Orient ScanManifestOrientation(const std::string& manifest_path,
                               const std::string& apk_path) {
    (void)manifest_path;
    if (apk_path.empty()) return Orient::AUTO;
    int o = BinaryManifestLauncherOrientation(apk_path);
    if (o == -2) return Orient::AUTO;
    return MapOrientationFamily(ScreenOrientationName(o));
}

// Resolve the directory the running binary lives in. Used so the build
// layout (binary in build/bin/, jars in build/lib/) is robust to the
// caller's cwd.
std::string BinaryDir() {
    char buf[4096];
    uint32_t size = sizeof(buf);
    if (_NSGetExecutablePath(buf, &size) != 0) {
        std::fprintf(stderr, "[macapk] _NSGetExecutablePath failed\n");
        std::abort();
    }
    fs::path p = fs::weakly_canonical(fs::path(buf)).parent_path();
    return p.string();
}

}  // namespace

// Run `cmd` (a /bin/sh pipeline writing raw bytes to stdout) and report whether
// any of `needles` occurs anywhere in its output. It replaces the
// `| grep -a -m1 -E 'a|b|...'` stage both GLES scans used to end in.
//
// WHY (measured 2026-09-22, v1.0.1634): macOS's BSD grep runs at ~17 MB/s over
// binary input. On Candy Crush the pipeline took 5.5 s, of which `unzip -p` of
// the 87 MB of native libs was 0.4 s -- the other 5.1 s was grep. Across the six
// slowest cold apps the ES3.1 scan cost 3.6-7.6 s, grep being most of every one
// (LC_ALL=C and -F barely helped: 4.9 s on the same input). This scan is a table
// test per byte, so it runs at memory speed and the pipeline is left paying only
// for the decompression.
//
// SAME ANSWER BY CONSTRUCTION: grep -E over an alternation of literals is a
// substring test over the bytes it reads (none of the needles contains a
// newline, so grep's line splitting cannot change a match), and so is this;
// debug/glesscan_verify.sh re-checks it against grep on the whole corpus. Both
// read the concatenated stream, so a match straddling two files counts in both.
// It stops at the first hit, and closing the pipe ends the producers with
// SIGPIPE exactly as grep -m1 exiting did: both scans run before the JVM starts
// and nothing in the runtime touches SIGPIPE before then, so the children
// inherit whatever disposition the grep pipeline's children did.
// Fails SAFE to false on any error, as the grep pipeline did.
static bool PipelineOutputContainsAny(const std::string& cmd,
                                      const std::vector<std::string>& needles) {
    size_t maxlen = 0;
    bool first[256] = {};
    for (const auto& n : needles) {
        if (n.empty()) continue;
        maxlen = std::max(maxlen, n.size());
        first[static_cast<unsigned char>(n[0])] = true;
    }
    if (maxlen == 0) return false;
    FILE* f = popen(cmd.c_str(), "r");
    if (!f) return false;
    const size_t kChunk = size_t(1) << 20;
    std::vector<unsigned char> buf(kChunk + maxlen);
    size_t have = 0;        // carried-over tail from the previous read
    bool hit = false;
    for (;;) {
        size_t r = std::fread(buf.data() + have, 1, kChunk, f);
        size_t valid = have + r;
        const bool eof = (r == 0);
        // A start position is decided only when every needle would fit after
        // it, or at EOF; the undecided tail moves to the front and is scanned
        // as the head of the next read, so no position is skipped or repeated.
        size_t limit = eof ? valid : (valid >= maxlen ? valid - (maxlen - 1) : 0);
        for (size_t i = 0; i < limit && !hit; ++i) {
            if (!first[buf[i]]) continue;
            for (const auto& n : needles) {
                if (!n.empty() && n.size() <= valid - i &&
                    std::memcmp(buf.data() + i, n.data(), n.size()) == 0) {
                    hit = true;
                    break;
                }
            }
        }
        if (hit || eof) break;
        have = valid - limit;
        std::memmove(buf.data(), buf.data() + limit, have);
    }
    pclose(f);
    return hit;
}

// v1.0.608: generic GLES-3.1 detection for automatic ANGLE-backend selection.
// ANGLE's Metal backend caps at OpenGL ES 3.0 (upstream: "no plans" for ES 3.1
// on Metal -- it targets WebGL, which only needs 3.0). ES 3.1 on macOS is ONLY
// available via ANGLE's Vulkan backend on top of MoltenVK. A RenderDragon-class
// engine (Minecraft Bedrock) REQUIRES 3.1 (compute shaders, image load/store,
// indirect draw) and renders BLACK on Metal. So we must route 3.1 apps to
// Vulkan -- but every ES 2.0/3.0 app should stay on the native, well-tested
// Metal path (no extra MoltenVK hop, no perf tax, no re-validation).
//
// Detection is GENERIC (keys off API usage, never a package name): scan the
// app's own native libraries for references to ES-3.1-core-only GL entry points.
// Engines resolve GL functions via eglGetProcAddress(<name>), so the entry-point
// NAME appears as a string in the .so even when it is not a link-time import --
// hence a raw string scan (not just the dynamic symbol table). The symbol set
// below is ES-3.1-only (absent from 2.0/3.0), so a hit means the app genuinely
// targets 3.1. Worst-case false-positive => an app takes the Vulkan path it did
// not strictly need (still renders); false-negative => it would render black, so
// the set is chosen to be broad across the 3.1 feature groups. `unzip -p` streams
// the matching .so entries and PipelineOutputContainsAny stops at the first hit
// (SIGPIPE ends unzip early). Scans only the arm64 / arm32 libs we actually run.
// Fails SAFE to false (Metal) on any error. MACAPK_ANGLE_BACKEND=metal|vulkan
// overrides it.
// Does the app ship libpairipcore.so (the native-VM pairip marker)? Used to
// ENGINE-GATE the pairip frontier surfaces (see __macapk_pairip_surface_on in
// bionic_shim.cpp): main() publishes the result as MACAPK_PAIRIP_APP BEFORE the
// bionic shim is dlopen'd, so the surfaces activate ONLY for a pairip app and a
// normal app is byte-for-byte unaffected even when the surface env flags are
// forced on (the regression those surfaces caused -- FAKE_JNIENV reroutes
// HotSpot's shared JNINativeInterface and faults the DEX converter:
// docs/pairip-fake-jnienv-shared-vtable-corruption_2026-10-16.md).
// PRESENCE check only (central-directory listing via `unzip -Z1`, no extraction):
// cheap, and failing toward "not pairip" is the safe direction (no surfaces = no
// regression). Scans the apk itself + installed split_*.apk siblings (the ABI
// split that carries libpairipcore for a split bundle, e.g. Minecraft / COS).
static bool MacApkApkHasPairipCore(const std::string& apk_path) {
    if (apk_path.empty()) return false;
    std::string q = "'";
    for (char c : apk_path) { if (c == '\'') q += "'\\''"; else q += c; }
    q += "'";
    std::string qsib = "'";
    for (char c : fs::path(apk_path).parent_path().string()) {
        if (c == '\'') qsib += "'\\''"; else qsib += c;
    }
    qsib += "'/split_*.apk";
    std::string cmd =
        "unzip -Z1 " + q + " 'lib/*/libpairipcore.so' 2>/dev/null; "
        "for a in " + qsib + "; do [ -f \"$a\" ] && unzip -Z1 \"$a\" 'lib/*/libpairipcore.so' 2>/dev/null; done";
    return PipelineOutputContainsAny(cmd, {std::string("libpairipcore.so")});
}

static bool ApkNeedsGles31(const std::string& apk_path) {
    if (apk_path.empty()) return false;
    // Single-quote the path for the shell, escaping embedded single quotes.
    std::string q = "'";
    for (char c : apk_path) { if (c == '\'') q += "'\\''"; else q += c; }
    q += "'";
    static const std::vector<std::string> kEs31Syms = {
        "glDispatchCompute", "glDispatchComputeIndirect", "glDrawArraysIndirect",
        "glDrawElementsIndirect", "glBindImageTexture", "glMemoryBarrier",
        "glGetProgramInterfaceiv", "glGetProgramResourceIndex", "glBindProgramPipeline",
        "glBindVertexBuffer", "glTexStorage2DMultisample", "glGetFramebufferParameteriv"};
    // Split-bundle aware (v1.0.1573). A plain APK has its libs at lib/<arch>/*.so; a split
    // bundle (.apkm/.xapk) puts them inside a nested config.<arch>.apk / base.apk, so
    // scanning only the OUTER zip's lib/ finds nothing and mis-picks Metal for a Vulkan app
    // (measured: Play-Minecraft's config.arm64_v8a.apk, whose libminecraftpe.so uses GLES 3.1,
    // read as "no GLES 3.1" -> RenderDragon black). Extract the lib-bearing splits to a temp
    // dir and scan those too; the outer-lib scan still covers a plain APK. Result is cached
    // (ApkNeedsGles31Cached), so the one nested extraction is paid once per APK.
    // Installed split set (v1.0.1585): the stub launches <install>/base.apk,
    // whose siblings split_*.apk carry the ABI split -- for Play-Minecraft the
    // ONLY copy of libminecraftpe.so. Scan them alongside base.apk, or the
    // installed app reads "no GLES 3.1" while the same bundle scanned as a
    // container read Vulkan: the exact installed-vs-dev asymmetry of v1.0.1576.
    // Quoted directory + an UNQUOTED glob suffix, so the shell expands it.
    std::string qsib = "'";
    for (char c : fs::path(apk_path).parent_path().string()) {
        if (c == '\'') qsib += "'\\''"; else qsib += c;
    }
    qsib += "'/split_*.apk";
    std::string cmd =
        "t=$(mktemp -d 2>/dev/null) || exit 1; "
        "unzip -o -q " + q + " '*arm64*.apk' 'base.apk' -d \"$t\" >/dev/null 2>&1; "
        "{ for a in \"$t\"/*.apk " + qsib + "; do [ -f \"$a\" ] && unzip -p \"$a\" 'lib/arm64-v8a/*.so' 'lib/armeabi-v7a/*.so' 2>/dev/null; done; "
        "unzip -p " + q + " 'lib/arm64-v8a/*.so' 'lib/armeabi-v7a/*.so' 2>/dev/null; }; "
        "rm -rf \"$t\"";
    return PipelineOutputContainsAny(cmd, kEs31Syms);
}

// Does the guest use the GLES 1.x FIXED-FUNCTION pipeline?
//
// WHY THIS EXISTS (v1.0.784): angle_glue asks for EGL_CONTEXT_CLIENT_VERSION 2
// unconditionally. ANGLE gates EVERY ES1 entry point on
// `context->getClientVersion() < ES_2_0` and otherwise records
// GL_INVALID_OPERATION and does nothing, so on an ES2 context the whole
// fixed-function pipeline is a silent no-op. Jetpack Joyride imports 15
// ES1-only names -- the entire transform, vertex-array and texture-combiner
// path -- so it can glClear and essentially nothing else, which is exactly
// the blank screen it presents. Discovery is NOT its problem: arm32rc finds
// 13,802 of its 13,823 functions with zero indirect-call errors.
//
// ES1 and ES2 are mutually exclusive context types (an ES1 context has no
// shaders; an ES2 context has no fixed-function), so this has to be chosen
// per app. Keying on IMPORTED SYMBOLS is engine-level, not per-APK: any app
// linking glMatrixMode is an ES1 app regardless of who shipped it.
//
// NOTE the lib/armeabi glob, which ApkNeedsGles31 above does NOT scan. JJ
// ships ONLY lib/armeabi (ARMv5TE), so scanning just v7a/arm64 would have
// missed the one app this detector exists for.
static bool ApkNeedsGles1(const std::string& apk_path) {
    if (apk_path.empty()) return false;
    std::string q = "'";
    for (char c : apk_path) { if (c == '\'') q += "'\\''"; else q += c; }
    q += "'";
    // ES1-ONLY names. Deliberately excludes calls that exist in BOTH ES1 and
    // ES2 (glClear, glViewport, glBindTexture, ...) -- those prove nothing.
    static const std::vector<std::string> kEs1Syms = {
        "glMatrixMode", "glLoadIdentity", "glLoadMatrixf", "glMultMatrixf",
        "glPushMatrix", "glPopMatrix", "glVertexPointer", "glColorPointer",
        "glNormalPointer", "glTexCoordPointer", "glEnableClientState",
        "glDisableClientState", "glClientActiveTexture", "glTexEnvi", "glOrthof"};
    std::string cmd =
        "unzip -p " + q + " 'lib/armeabi/*.so' 'lib/armeabi-v7a/*.so' "
        "'lib/arm64-v8a/*.so' 2>/dev/null";
    return PipelineOutputContainsAny(cmd, kEs1Syms);
}

// Efficiency audit 2026-07-24: ApkNeedsGles31 unzip|greps the guest's
// native libs (docs/hard-fixes-catalog.md measured ~2.7s for MCPE's
// 336 MB .so; ~1.1s reproduced independently for a 233 MB APK) on EVERY
// launch, with no memoing anywhere, even though the result is a pure
// function of the APK's bytes and can never change unless the APK itself
// does. Cache it keyed on the "<size>:<mtime>" fingerprint scheme
// SetupAndroidRoot uses for its own dir-rotation fingerprint
// (compute_apk_fp, ~line 445 below) -- same idea, but a fully independent
// instance here rather than a shared call: this runs from main() BEFORE
// UnpackApk/SetupAndroidRoot, i.e. before the per-app app_dir (and so
// meta.json) exists yet for a first-ever launch, so a per-package cache
// file isn't reachable at this point in the sequence -- only something
// keyed purely off the apk path is. A small flat cache directly under the
// android-root mirrors the MACAPK_APP_CACHE_DIR default location (also
// computed independently below, for the same before-UnpackApk reason --
// see that block's own comment for the established precedent) without
// requiring app_dir to exist yet.
static std::string ComputeApkFingerprintQuick(const std::string& apk_path) {
    std::error_code ec;
    std::uintmax_t sz = fs::file_size(apk_path, ec);
    if (ec) return {};
    auto mt = fs::last_write_time(apk_path, ec);
    if (ec) return {};
    long long ticks = static_cast<long long>(mt.time_since_epoch().count());
    return std::to_string(static_cast<unsigned long long>(sz)) + ":"
         + std::to_string(ticks);
}

// The GLES31 verdict cache lives under the data root. Root resolution
// is the SHARED ResolveAndroidRootDir (apk_unpacker.cpp) -- the
// independent copies this file used to carry are gone, because a
// divergence between them silently splits the cache from the data tree.
static fs::path Gles31CacheDir(const std::string& cli_root) {
    return fs::path(ResolveAndroidRootDir(cli_root))
        / "data" / "data" / ".gles31cache";
}

// Bump when ApkNeedsGles31's SCAN LOGIC changes, so every machine's cached
// verdict from the OLD logic is invalidated automatically.
//   v2 (2026-09-18): the split-bundle nested-.apk scan. Before it, a split
//   bundle (MCPE's .apkm) read as "no GLES 3.1" because only the outer apk's
//   lib/ was scanned -> Metal -> RenderDragon BLACK. The APK is unchanged, so
//   the fingerprint KEY is identical and a stale '0' (untagged, pre-v2) would
//   otherwise win forever. This is NOT a slow path: a stale verdict is a
//   correctness bug (wrong graphics backend), so it must self-heal on the fix.
static constexpr int kGles31ScanVersion = 3;   // v3: scans installed split_*.apk siblings

// Cached wrapper around ApkNeedsGles31(): reads/writes a "<scan-version>:<0|1>"
// stamp file named after the APK's fingerprint so a warm relaunch skips
// the unzip|grep scan entirely. Any failure (unfingerprintable apk,
// unwritable/unreadable cache dir, a version mismatch or malformed line)
// degrades gracefully to the uncached scan -- this is a pure speed
// optimization, never a correctness dependency, so it fails toward "just do
// the real scan" in every case.
static bool ApkNeedsGles31Cached(const std::string& apk_path,
                                  const std::string& cli_root) {
    std::string fp = ComputeApkFingerprintQuick(apk_path);
    if (fp.empty()) return ApkNeedsGles31(apk_path);  // can't fingerprint; don't cache
    fs::path cache_file = Gles31CacheDir(cli_root) / fp;
    std::error_code ec;
    if (fs::exists(cache_file, ec)) {
        std::ifstream in(cache_file);
        std::string line;
        if (in && std::getline(in, line) && !line.empty()) {
            // "<ver>:<0|1>". A bare "0"/"1" (no colon) is the pre-v2 format
            // written by the old scan logic -- treat it, and any version that
            // is not the current one, as a MISS so the fixed scan re-runs and
            // rewrites. A stale verdict here is a wrong graphics backend, not
            // a slow launch, so it must not be trusted across a logic change.
            auto colon = line.find(':');
            if (colon != std::string::npos && colon + 1 < line.size()) {
                int ver = std::atoi(line.substr(0, colon).c_str());
                char v = line[colon + 1];
                if (ver == kGles31ScanVersion && (v == '0' || v == '1')) {
                    std::fprintf(stderr,
                        "[macapk] ApkNeedsGles31: cache hit (%s) -- skipping scan\n",
                        v == '1' ? "GLES 3.1" : "GLES 2.0/3.0");
                    return v == '1';
                }
                std::fprintf(stderr,
                    "[macapk] ApkNeedsGles31: cache STALE (v%d, want v%d) -- rescanning\n",
                    ver, kGles31ScanVersion);
            } else {
                std::fprintf(stderr,
                    "[macapk] ApkNeedsGles31: cache pre-versioned -- rescanning\n");
            }
        }
    }
    bool result = ApkNeedsGles31(apk_path);
    std::error_code mec;
    fs::create_directories(cache_file.parent_path(), mec);
    std::ofstream out(cache_file);
    if (out) out << kGles31ScanVersion << ':' << (result ? '1' : '0') << '\n';
    return result;
}

// ---- AOSP large-screen window policy (v1.0.1647) ----------------------------
// Owner decision 2026-09-25: an app's window is the bounds Android's window
// manager gives it on a LARGE display (the tablet rule), with the letterbox
// bars cut away. Contract: include/macapk/window_policy.h; design, oracle and
// phase notes: docs/window-size-policy_2026-09-25.md; offline gate:
// debug/winpolicy_gate.sh.
//
// BISECT GATE, default aosp: MACAPK_WINDOW_POLICY=legacy restores the
// v1.0.1646 window rule exactly (portrait phone-app shape for every app that
// does not pin landscape). An installed app launched from the Dock never sees a
// shell variable (the stub passes only --apk/--android-root), so the same
// switch is also read from the file <android_root>/.macapk/window_policy
// ("legacy" or "aosp"); the environment wins when both are set. Both accept
// the repo's usual spellings, case-insensitively: legacy|0|off|false and
// aosp|1|on|true. Anything else is WARNED and keeps the default (aosp): a
// switch that silently read `=0` as "on" would bisect the wrong way.
// It applies ONLY to the automatic path: an explicit portrait/landscape
// --orientation or --width/--height keeps today's geometry exactly, which is
// what every pinned sweep row and comparison harness relies on.
// `--orientation auto` IS the automatic path (it is what no flag means), so
// the harnesses that pass it pin MACAPK_WINDOW_POLICY themselves
// (debug/sweep_one.sh, debug/boottime.sh; debug/fleet_ab.sh --tablet pins aosp).
static bool WindowPolicyIsAosp(const std::string& cli_root, std::string* source = nullptr) {
    if (const char* e = std::getenv("MACAPK_WINDOW_POLICY"); e && *e) {
        const int v = winpolicy::ParsePolicySwitch(e);
        if (source) *source = std::string("MACAPK_WINDOW_POLICY=") + e;
        if (v < 0) {
            std::fprintf(stderr, "[macapk] window-policy: MACAPK_WINDOW_POLICY=%s not understood "
                         "(legacy|0|off|false or aosp|1|on|true); keeping aosp\n", e);
            return true;
        }
        return v == 1;
    }
    const fs::path file = fs::path(ResolveAndroidRootDir(cli_root)) / ".macapk" / "window_policy";
    std::ifstream in(file);
    std::string word;
    if (in && (in >> word)) {
        const int v = winpolicy::ParsePolicySwitch(word);
        if (source) *source = file.string() + "=" + word;
        if (v < 0) {
            std::fprintf(stderr, "[macapk] window-policy: %s says '%s', not understood; keeping aosp\n",
                         file.c_str(), word.c_str());
            return true;
        }
        return v == 1;
    }
    if (source) *source = "default";
    return true;
}

// The launch decision, kept for the JVM system properties.
struct WindowPolicyOutcome {
    bool sized = false;
    float scale = 2.0f;                 // the screen's backing scale at decision time
    winpolicy::WindowDecision decision;
};
static WindowPolicyOutcome g_window_policy;

// Manifest traits for the window decision, cached per APK like the GLES 3.1
// verdict: <root>/data/data/.windowtraits/<size>_<mtime> (the fingerprint's
// ':' made a '_'; debug/boottime.sh clears `<size>_*` for a COLD run), holding the serialised
// traits whose first line is the reader's kTraitsFormatVersion -- a cache
// written by an older reader is a MISS, never a stale answer (the .gles31cache
// lesson). The uncached read is a few ms (one streamed zip entry), so the cache
// is a convenience; MACAPK_WINDOW_TRAITS_CACHE=0 bypasses it.
static winpolicy::WindowTraits LoadWindowTraits(const std::string& apk,
                                                const std::string& cli_root) {
    std::string ext = fs::path(apk).extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c) { return std::tolower(c); });
    const bool bundle = ext == ".apkm" || ext == ".xapk" || ext == ".apks";
    const char* cache_env = std::getenv("MACAPK_WINDOW_TRAITS_CACHE");
    const bool use_cache = !(cache_env && std::strcmp(cache_env, "0") == 0);
    const std::string fp = use_cache ? ComputeApkFingerprintQuick(apk) : std::string();
    fs::path cache_file;
    if (!fp.empty()) {
        std::string key = fp;
        std::replace(key.begin(), key.end(), ':', '_');
        cache_file = fs::path(ResolveAndroidRootDir(cli_root)) / "data" / "data"
                   / ".windowtraits" / key;
        std::ifstream in(cache_file);
        if (in) {
            std::stringstream ss;
            ss << in.rdbuf();
            winpolicy::WindowTraits t;
            if (winpolicy::DeserializeTraits(ss.str(), &t) && t.ok) return t;
        }
    }
    const winpolicy::WindowTraits t =
        winpolicy::ReadWindowTraits(apk, bundle ? BundleBaseApkEntry(apk) : std::string());
    if (!cache_file.empty() && t.ok) {
        std::error_code ec;
        fs::create_directories(cache_file.parent_path(), ec);
        std::ofstream out(cache_file, std::ios::trunc);
        if (out) out << winpolicy::SerializeTraits(t);
    }
    return t;
}

// Decide the window for `apk` on the main screen and, when the policy applies,
// install it (session + boxes). Returns false when the caller must use the
// legacy rule: no launcher, no screen, or a display that is not large (a 1x
// monitor) -- each logged with its reason.
static bool SizeWindowByPolicy(const std::string& apk, const std::string& cli_root,
                               const char* phase) {
    const winpolicy::WindowTraits traits = LoadWindowTraits(apk, cli_root);
    winpolicy::DisplayBudget budget;
    std::string budget_desc;
    const bool have_screen = CocoaPolicyDisplayBudget(&budget, &budget_desc);
    if (!traits.ok || !have_screen) {
        std::fprintf(stderr, "[macapk] window-policy (%s): %s -- legacy window rule\n", phase,
                     !traits.ok ? "manifest unreadable" : budget_desc.c_str());
        CocoaClearWindowPolicy();
        g_window_policy = WindowPolicyOutcome();
        g_window_policy.decision.reason = !traits.ok ? "manifest unreadable" : budget_desc;
        return false;
    }
    std::string warnings;
    const winpolicy::DeviceSettings settings = winpolicy::SettingsFromEnv(&warnings);
    if (!warnings.empty())
        std::fprintf(stderr, "[macapk] window-policy: %s\n", warnings.c_str());
    const std::string pref_path =
        winpolicy::UserPrefPath(ResolveAndroidRootDir(cli_root), traits.package);
    // MACAPK_WINDOW_PREF pins the Cmd+R preference for this run and wins over
    // the per-app file; while it is set Cmd+R does not write the file either,
    // so a harness run can neither read nor leave behind someone's choice.
    winpolicy::UserWindowPref pref = winpolicy::ReadUserPref(pref_path);
    bool persist_pref = true;
    std::string pref_src = "file";
    if (const char* e = std::getenv("MACAPK_WINDOW_PREF"); e && *e) {
        winpolicy::UserWindowPref forced;
        if (winpolicy::ParseUserPref(e, &forced)) {
            pref = forced;
            persist_pref = false;
            pref_src = "MACAPK_WINDOW_PREF";
        } else {
            std::fprintf(stderr, "[macapk] window-policy: MACAPK_WINDOW_PREF=%s not understood "
                         "(default|portrait); using the per-app file\n", e);
        }
    }
    const winpolicy::WindowDecision d = winpolicy::Resolve(traits, budget, settings, pref);
    std::fprintf(stderr,
        "[macapk] window-policy (%s): %s | budget %s, %s, pref=%s (%s)%s%s\n", phase,
        winpolicy::FormatDecision(d).c_str(), budget_desc.c_str(),
        winpolicy::FormatSettings(settings).c_str(), winpolicy::UserPrefName(pref),
        pref_src.c_str(), traits.notes.empty() ? "" : ", manifest: ", traits.notes.c_str());
    if (!d.policy_applies) {
        CocoaClearWindowPolicy();
        g_window_policy = WindowPolicyOutcome();
        g_window_policy.decision = d;
        return false;
    }
    CocoaInstallWindowPolicy(traits, settings, pref_path, pref, persist_pref);
    CocoaApplyWindowDecision(d, budget);
    g_window_policy.sized = true;
    g_window_policy.scale = budget.backing_scale;
    g_window_policy.decision = d;
    return true;
}

// --peek-window-policy <apk>: the decision this launch WOULD make, with the same
// inputs a real launch reads -- --android-root (for the per-app Cmd+R file and
// the traits cache) and the MACAPK_WINDOW_POLICY switch (env or file). With the
// switch at legacy it prints applies=0 and says why rather than a decision the
// launch would not use.
static int RunPeekWindowPolicy(const std::string& apk, const std::string& cli_root) {
    if (!fs::exists(apk)) {
        std::fprintf(stderr, "[macapk] --peek-window-policy: no such file: %s\n", apk.c_str());
        return 1;
    }
    std::string source;
    if (!WindowPolicyIsAosp(cli_root, &source)) {
        winpolicy::WindowDecision off;
        off.reason = "window policy switched off (" + source + "): the v1.0.1646 legacy rule";
        std::printf("%s\n", winpolicy::DecisionJson(off).c_str());
        return 0;
    }
    SizeWindowByPolicy(apk, cli_root, "peek");
    std::printf("%s\n", winpolicy::DecisionJson(g_window_policy.decision).c_str());
    return 0;
}

// Whether this launch puts the window up BEFORE the unpack (see "0c. WINDOW
// FIRST" in main). A function rather than a local because the ANGLE backend
// decision, which runs earlier, needs the same answer to know whether it may
// defer its scan onto the unpack worker. Not for --extract-only (the installer
// has no window); MACAPK_WINDOW_FIRST=0 restores the old ordering for an A/B.
static bool MacapkWindowFirst(const CliArgs& cli) {
    if (cli.extract_only) return false;
    const char* wf = std::getenv("MACAPK_WINDOW_FIRST");
    return !(wf && std::strcmp(wf, "0") == 0);
}

static bool EnvIs(const char* name, const char* value) {
    const char* e = std::getenv(name);
    return e && std::strcmp(e, value) == 0;
}

static void LogAngleAutoDetect(bool want_vulkan) {
    std::fprintf(stderr,
        "[macapk] ANGLE backend auto-detect: app %s GLES 3.1 APIs -> %s\n",
        want_vulkan ? "USES" : "does NOT use",
        want_vulkan ? "VULKAN (MoltenVK)" : "Metal");
}

// Apply the backend decision. Called from the backend block for an override or a
// synchronous scan, and after the unpack worker's join for a deferred one; in
// both cases before the JVM starts, so before any guest EGL display exists.
static void ApplyAngleBackend(bool want_vulkan, bool have_icd, const std::string& icd) {
    if (want_vulkan) {
        setenv("ANGLE_DEFAULT_PLATFORM", "vulkan", 1);
        // NOTE (v1.0.625): a v1.0.624 experiment forced MVK_CONFIG_USE_MTLHEAP=0
        // here to shrink a supposed MCPE GPU "balloon". It was REVERTED: the
        // balloon was a swap-degraded-machine measurement artifact (healthy box =
        // stable ~1.3 GB), and USE_MTLHEAP is active-by-default on Apple Silicon
        // to back Vulkan features ANGLE relies on (VK_EXT_image_2d_view_of_3d et
        // al.) -- forcing it off is a needless render risk. Leave MoltenVK's
        // default heap behavior alone. See docs/mcpe-gpu-memory-balloon_2026-07-14.md.
        if (!have_icd) {
            std::fprintf(stderr,
                "[macapk] WARN: Vulkan backend selected but "
                "MoltenVK_icd.json not found at %s; Vulkan init will fail\n",
                icd.c_str());
        }
        std::fprintf(stderr, "[macapk] ANGLE backend: VULKAN (MoltenVK)\n");
    } else {
        setenv("ANGLE_DEFAULT_PLATFORM", "metal", 1);
    }
}

// Bump when ApkNeedsGles1's SCAN LOGIC (the symbol set) changes.
static constexpr int kGles1ScanVersion = 1;

// Cached wrapper around ApkNeedsGles1(), and it is NOT a micro-optimisation.
//
// MEASURED 2026-09-22 with debug/boottime.sh, WARM launches, on the time from
// exec to the placeholder window appearing:
//     Subway Surf     16.9 s        Candy Crush   7.7 s (14.0 s cold)
//     Organic Maps     2.7 s        AnkiDroid     4.3 s
// against ~0.3 s for every app whose native libs are small. ApkNeedsGles31 was
// memoized in v1.0.771 for exactly this reason ("~2.7 s for MCPE's 336 MB .so
// on EVERY launch") and ApkNeedsGles1 -- added later, doing the same
// `unzip -p <apk> lib/**/*.so | grep` over the same bytes -- was not. So the
// scan the earlier fix removed came straight back on a second code path, and
// it now dominates time-to-window on every heavy app.
//
// Same shape as its sibling by design: same fingerprint, same cache dir, its
// own filename suffix and its own scan version, and every failure path
// degrades to the uncached scan. It is a speed cache, never a correctness
// dependency.
static bool ApkNeedsGles1Cached(const std::string& apk_path,
                                const std::string& cli_root) {
    std::string fp = ComputeApkFingerprintQuick(apk_path);
    if (fp.empty()) return ApkNeedsGles1(apk_path);
    fs::path cache_file = Gles31CacheDir(cli_root) / (fp + ".es1");
    std::error_code ec;
    if (fs::exists(cache_file, ec)) {
        std::ifstream in(cache_file);
        std::string line;
        if (in && std::getline(in, line) && !line.empty()) {
            auto colon = line.find(':');
            if (colon != std::string::npos && colon + 1 < line.size()) {
                int ver = std::atoi(line.substr(0, colon).c_str());
                char v = line[colon + 1];
                if (ver == kGles1ScanVersion && (v == '0' || v == '1')) {
                    std::fprintf(stderr,
                        "[macapk] ApkNeedsGles1: cache hit (ES%d) -- skipping scan\n",
                        v == '1' ? 1 : 2);
                    return v == '1';
                }
            }
        }
    }
    bool result = ApkNeedsGles1(apk_path);
    std::error_code mec;
    fs::create_directories(cache_file.parent_path(), mec);
    std::ofstream out(cache_file);
    if (out) out << kGles1ScanVersion << ':' << (result ? '1' : '0') << '\n';
    return result;
}

// --gms <subcommand> [args] -- see CliArgs::gms_args.
//
// Boots a JVM with only the framework + loader jars (no APK, no unpacker, no
// Cocoa window unless a subcommand asks for one) and hands the arguments to
// com.macapk.gms.GmsCli, which is the SAME entry point debug/gms.sh drives
// headlessly. One implementation of every GMS operation, two ways in.
//
// ⚠ RegisterAllNativeMethods IS REQUIRED HERE, and it is easy to think it is
// not. `--gms accounts` needs no natives, but `--gms signin-window` calls
// com.macapk.gms.NativeSignIn.presentSignInWindow, and an unregistered native
// surfaces as UnsatisfiedLinkError from inside the sign-in path -- i.e. as
// "sign-in is broken" rather than "the JVM was set up wrong".
static int RunGmsCommand(const std::string& bin_dir,
                         const std::vector<std::string>& args,
                         bool verbose) {
    const fs::path fw_jar = fs::path(bin_dir) / "../lib/android-framework.jar";
    const fs::path ld_jar = fs::path(bin_dir) / "../lib/macapk-loader.jar";
    if (!fs::exists(fw_jar) || !fs::exists(ld_jar)) {
        std::fprintf(stderr, "ERROR\tcannot find the framework jars next to %s\n",
                     bin_dir.c_str());
        return 1;
    }

    JvmConfig cfg;
    cfg.classpath_entries.push_back(fw_jar.string());
    cfg.classpath_entries.push_back(ld_jar.string());
    const fs::path bc_jar = fs::path(bin_dir) / "../lib/bcprov-jdk18on-1.79.jar";
    if (fs::exists(bc_jar)) cfg.classpath_entries.push_back(bc_jar.string());
    cfg.verbose = verbose;

    JavaVM* vm  = nullptr;
    JNIEnv* env = nullptr;
    StartJvm(cfg, &vm, &env);
    RegisterAllNativeMethods(env);

    jclass cls = env->FindClass("com/macapk/gms/GmsCli");
    if (!cls) {
        env->ExceptionClear();
        std::fprintf(stderr, "ERROR\tcom.macapk.gms.GmsCli is missing from the framework jars\n");
        return 1;
    }
    jmethodID m = env->GetStaticMethodID(cls, "run", "([Ljava/lang/String;)I");
    if (!m) {
        env->ExceptionClear();
        std::fprintf(stderr, "ERROR\tcom.macapk.gms.GmsCli.run(String[])int is missing\n");
        return 1;
    }

    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray jargs = env->NewObjectArray(static_cast<jsize>(args.size()), str_cls, nullptr);
    for (size_t i = 0; i < args.size(); ++i) {
        jstring js = env->NewStringUTF(args[i].c_str());
        env->SetObjectArrayElement(jargs, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }

    jint rc = env->CallStaticIntMethod(cls, m, jargs);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        rc = 1;
    }
    std::fflush(stdout);
    std::fflush(stderr);
    // exit() rather than a clean VM shutdown: a sign-in run has an AppKit run
    // loop and a WebView's XPC children behind it, and DestroyJavaVM would wait
    // on threads that are not ours to join. Nothing here needs a shutdown hook
    // -- GmsStore writes its file synchronously on every mutation.
    std::exit(static_cast<int>(rc));
}

// v1.0.1641: names OUR runtime keeps in a code dir (<root>/.macapk/code/<dir>).
// KEEP IN SYNC with Boot.CODE_DIR_OURS (runtime/java/com/macapk/loader/Boot.java)
// and DataCarryForward.codeDirArtifacts (launcher/InstallCore.swift).
static bool IsCodeDirOwnName(const std::string& n) {
    if (IsCodeArtifactName(n)) return true;
    if (n.rfind(".macapk", 0) == 0) return true;
    static const char* const kMore[] = {
        "meta.json", "icon.png", "base.apk", ".gles31cache", ".angle_blob", ".dexjar-pending",
    };
    for (const char* m : kMore) if (n == m) return true;
    return false;
}

// Move every APP-written entry of `code_dir` (anything not ours, never a link)
// into `data_dir`: a whole rename when the name is free there, else file by
// file. A file whose path is already taken is parked under
// <data_dir>/macapk-rescued/<entry>/... -- inside the data dir, so it is carried
// with the app's data -- and never deleted.
// v1.0.1641: after an in-place update, data/data/<oldDirName> is a LINK to the
// newer version's data dir (so absolute paths the app saved keep resolving).
// Anything that runs THIS old dirName again -- a downgrade into the same bundle,
// a stray older APK -- must not write through it into the newer version's data
// (an older app on a newer database is a downgrade the app never planned for).
static void ReplaceCompatLink(const fs::path& data_dir) {
    std::error_code lec;
    if (!fs::is_symlink(fs::symlink_status(data_dir, lec))) return;
    fs::remove(data_dir, lec);
    std::fprintf(stderr,
        "[macapk] %s was a link to a newer version's data dir; replaced with a fresh "
        "dir (this version must not write into that data)\n", data_dir.c_str());
}

static int RescueAppDataFromCodeDir(const fs::path& code_dir, const fs::path& data_dir) {
    std::error_code ec;
    if (!fs::is_directory(fs::symlink_status(code_dir, ec)) || ec) return 0;
    std::vector<fs::path> tops;
    for (fs::directory_iterator it(code_dir, ec), end; !ec && it != end; it.increment(ec)) {
        const std::string n = it->path().filename().string();
        std::error_code lec;
        if (it->is_symlink(lec) || IsCodeDirOwnName(n)) continue;
        tops.push_back(it->path());
    }
    if (tops.empty()) return 0;
    fs::create_directories(data_dir, ec);
    int moved = 0, merged = 0, parked = 0, failed = 0;
    for (const fs::path& src : tops) {
        const fs::path dst = data_dir / src.filename();
        std::error_code sec;
        // SoLoader marks its unpack dirs read-only; nothing moves out of a dir
        // without owner-write. The app's own dirs, so grant it.
        if (fs::is_directory(fs::symlink_status(src, sec))) {
            fs::permissions(src, fs::perms::owner_write, fs::perm_options::add, sec);
            for (fs::recursive_directory_iterator it(src, sec), end; !sec && it != end;
                 it.increment(sec)) {
                std::error_code pec;
                if (!it->is_symlink(pec) && it->is_directory(pec))
                    fs::permissions(it->path(), fs::perms::owner_write, fs::perm_options::add, pec);
            }
            sec.clear();
        }
        // A SoLoader unpack dir (lib-main, lib-compressed, ...) the data dir
        // already has is its superseded, regenerable output: drop the old copy.
        if (src.filename().string().rfind("lib-", 0) == 0
            && fs::exists(fs::symlink_status(dst, sec))) {
            fs::remove_all(src, sec);
            ++moved;
            continue;
        }
        if (!fs::exists(fs::symlink_status(dst, sec))) {
            fs::rename(src, dst, sec);
            if (!sec) { ++moved; continue; }
        }
        std::vector<fs::path> files;
        std::error_code wec;
        if (fs::is_directory(fs::symlink_status(src, wec))) {
            for (fs::recursive_directory_iterator it(src, wec), end; !wec && it != end;
                 it.increment(wec)) {
                std::error_code tec;
                if (it->is_symlink(tec) || !it->is_directory(tec)) files.push_back(it->path());
            }
        } else {
            files.push_back(src);
        }
        for (const fs::path& f : files) {
            std::error_code rec;
            fs::path rel = fs::relative(f, src.parent_path(), rec);
            if (rec) { ++failed; continue; }
            fs::path to = data_dir / rel;
            if (fs::exists(fs::symlink_status(to, rec))) {
                to = data_dir / "macapk-rescued" / rel;
                ++parked;
            } else {
                ++merged;
            }
            fs::create_directories(to.parent_path(), rec);
            rec.clear();
            fs::rename(f, to, rec);
            if (rec) ++failed;
        }
    }
    std::fprintf(stderr,
        "[macapk] rescued app data from code dir %s into %s: %d moved, %d merged, "
        "%d parked under macapk-rescued/, %d failed (ApplicationInfo.dataDir named "
        "the code dir before v1.0.1641)\n",
        code_dir.filename().c_str(), data_dir.c_str(), moved, merged, parked, failed);
    return failed;
}

// The stub's single-instance lock must die with THIS process, not with our
// children (owner, 2026-10-19: "apps are launching in the background again").
// launcher/AppStubMain.swift step 5 opens run/<dirName>.pid WITHOUT O_CLOEXEC on
// purpose, so the flock survives its execv into us. But every process we then
// posix_spawn/exec inherits that fd too -- the background DEX conversion that
// outlives a cold boot, a guest's fork+execve helpers (yt-dlp, ffmpeg through
// macapk-elfexec), CocoaExitProcess's relaunch /bin/sh and the `open` it execs.
// flock belongs to the open file description, so any such survivor keeps the
// app "running" after we quit: the next Dock or drawer launch finds the lock
// held, activates the dead pid written in the file, and exits, and the app
// never comes up. Marking it close-on-exec here keeps our own hold (this
// process never execs) while no exec'd child can carry it. Recognised by path,
// so it works with every stub generation; a plain fork() child that never
// execs still shares it, and the app-group kill on exit is what reaps those.
static void MacApkRunLockCloexec() {
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) return;
    const std::string run_dir = std::string(home) + "/Library/Application Support/Mac.apk/run/";
    for (int fd = 3; fd < 256; ++fd) {
        int flags = fcntl(fd, F_GETFD);
        if (flags < 0 || (flags & FD_CLOEXEC)) continue;
        char path[PATH_MAX];
        if (fcntl(fd, F_GETPATH, path) != 0) continue;
        const size_t n = std::strlen(path);
        if (n < 4 || std::strcmp(path + n - 4, ".pid") != 0) continue;
        if (std::strncmp(path, run_dir.c_str(), run_dir.size()) != 0) continue;
        if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0)
            std::fprintf(stderr, "[macapk] run lock fd %d (%s) is now close-on-exec\n", fd, path);
    }
}

int main(int argc, char** argv) {
    const long long main_entry_ms = NowEpochMs();   // BOOTSPEED clock: stub + dyld cost
    MacApkRunLockCloexec();
    CliArgs cli = ParseArgs(argc, argv);
    // Engine-gate the pairip frontier surfaces: detect the native-VM pairip
    // marker ONCE here (pre-JVM, BEFORE the bionic shim is dlopen'd) and publish
    // it as MACAPK_PAIRIP_APP. The shim's __macapk_pairip_surface_on then lets a
    // surface flag (FAKE_JNIENV, TAGGED_POINTERS, PTRACE_EMU, ...) take effect
    // ONLY for a pairip app; a normal app never sets it and stays unaffected even
    // under a forced-on protected sweep. A tester may set MACAPK_PAIRIP_APP=1
    // beforehand to force the surfaces onto a non-pairip app for an A/B -- this
    // detection respects that (it does not override an existing value).
    if (!std::getenv("MACAPK_PAIRIP_APP") && MacApkApkHasPairipCore(cli.apk)) {
        setenv("MACAPK_PAIRIP_APP", "1", 1);
        std::fprintf(stderr,
            "[macapk] pairip app detected (libpairipcore.so) -- frontier surfaces "
            "engine-gated ON for this app\n");
    }

    // Install the gated JVM device-path remap (fishhook rebind of libjava/libjvm open/stat) BEFORE any
    // JVM load. g_jvmfs_root is set from --android-root in ParseArgs; cover the env case here too. No-op
    // unless MACAPK_ANDROID_MAPS_PATHS is set (fleet-safe). rebind_symbols also registers for future
    // images, so libjava (loaded later, in StartJvm) is covered.
    if (!g_jvmfs_root && !cli.android_root.empty()) {
        static std::string s_jvmfs_root2 = cli.android_root;
        while (s_jvmfs_root2.size() > 1 && s_jvmfs_root2.back() == '/') s_jvmfs_root2.pop_back();
        g_jvmfs_root = s_jvmfs_root2.c_str();
    }
    macapk_install_jvmfs_rebind();

    // --peek-manifest is a pure read: no extraction, no identity
    // digests, no JVM. It runs before everything else so the install
    // dialog's latency is one AXML scan, and prints its one JSON line
    // to STDOUT (all diagnostics below go to stderr).
    if (!cli.peek_manifest.empty()) {
        return RunPeekManifest(cli.peek_manifest);
    }
    if (!cli.peek_window_policy.empty()) {
        return RunPeekWindowPolicy(cli.peek_window_policy, cli.android_root);
    }
    if (!cli.gles_scan.empty()) {
        const bool es31 = ApkNeedsGles31(cli.gles_scan);
        const bool es1 = ApkNeedsGles1(cli.gles_scan);
        std::printf("{\"es31\":%d,\"es1\":%d}\n", es31 ? 1 : 0, es1 ? 1 : 0);
        return 0;
    }

    // --gms: a JVM, the GMS classes, and no app. Runs before every APK-shaped
    // step below because none of them applies -- there is no package to unpack,
    // no native library to translate and no game window to open.
    // Deferred ES1 fixed-function scan (see the GLES1 block below): true when
    // nothing overrode MACAPK_GLES1_CONTEXT and the unzip|grep still has to
    // run. It rides the unpack worker so it cannot delay the window.
    bool need_es1_scan = false;
    bool es1_scan_result = false;
    // Deferred GLES 3.1 scan (v1.0.1634) -- the same treatment for the ANGLE
    // backend decision. On a first launch ApkNeedsGles31 unzips every native lib
    // in the APK through grep, and it ran BEFORE the window: the ES1 scan's move
    // took that path from 16.9 s to 1.0 s on Subway Surf, but this one stayed,
    // and it is most of Candy Crush's 14.0 s cold time-to-window. Its answer
    // (ANGLE_DEFAULT_PLATFORM) is first read when the guest opens an EGL display,
    // long after the unpack, so it rides the unpack worker too. The ICD facts are
    // kept here because the setenv that applies the answer runs after the join.
    bool need_es31_scan = false;
    bool es31_scan_result = false;
    bool es31_have_icd = false;
    std::string es31_icd;
    if (!cli.gms_args.empty()) {
        return RunGmsCommand(BinaryDir(), cli.gms_args, cli.verbose);
    }

    const std::string bin_dir = BinaryDir();

    // BOOTSPEED clock: from here on this process is a launch (or the
    // --extract-only installer, which is excluded).
    g_boot_clock_launch = !cli.extract_only;
    BootClockAt("main() entered (stub pre-exec + dyld before this)", main_entry_ms);

    // v1.0.142: print the runtime build's own VERSION + the binary's
    // mtime to stderr at startup, so it's unambiguous which build the
    // user is running. The version string is baked in at build time
    // by CMake reading the project's VERSION file (see
    // runtime/CMakeLists.txt for the target_compile_definitions
    // setup).
    //
    // v1.0.944: the same line now also carries the BUILD IDENTITY
    // (JAVA_ID/NATIVE_ID, Contract 3). A log that names its build makes
    // a stale-runtime run distinguishable from a good one after the
    // fact, which is the analysis half of the staleness problem (the
    // nine-days-stale launcher runtime of v942 was caught by luck).
#ifndef MACAPK_VERSION_STR
#define MACAPK_VERSION_STR "unknown"
#endif
    {
        char mtime_str[64] = "?";
        try {
            auto bin = fs::path(argv[0]);
            auto t = fs::last_write_time(bin);
            auto sys_t = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
                t - decltype(t)::clock::now() + std::chrono::system_clock::now());
            std::time_t tt = std::chrono::system_clock::to_time_t(sys_t);
            std::strftime(mtime_str, sizeof(mtime_str), "%Y-%m-%d %H:%M:%S",
                           std::localtime(&tt));
        } catch (...) {}
        const BuildIdentity& id = GetBuildIdentity(bin_dir);
        std::fprintf(stderr,
            "[macapk] runtime v%s -- binary %s mtime %s java-id %s native-id %s\n",
            MACAPK_VERSION_STR, argv[0], mtime_str,
            id.java_id.empty() ? "?" : id.java_id.c_str(),
            id.native_id.empty() ? "?" : id.native_id.c_str());
        // Exported so the Java side can key ITS caches on the same
        // identity: the translated-dylib cache is existence-only today
        // (AppClassLoader.java:713-719) and needs NATIVE_ID next to each
        // dylib. The loader lane reads these; setting them costs nothing
        // until it does.
        if (!id.java_id.empty())
            setenv("MACAPK_BUILD_JAVA_ID", id.java_id.c_str(), 1);
        if (!id.native_id.empty())
            setenv("MACAPK_BUILD_NATIVE_ID", id.native_id.c_str(), 1);
    }

    // v1.0.603: ANGLE render-backend selection (generic, NOT per-APK).
    // ANGLE is now built with BOTH the Metal and the Vulkan backend in one
    // libGLESv2/libEGL. Which one a guest's eglGetDisplay(EGL_DEFAULT_DISPLAY)
    // resolves to is chosen by ANGLE's ANGLE_DEFAULT_PLATFORM env var
    // ("metal" -> EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, "vulkan" ->
    // EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE; see ANGLE
    // src/libANGLE/Display.cpp GetDisplayTypeFromEnvironment).
    //   Default / "metal": Metal backend -- unchanged for every GLES 2.0/3.0
    //     engine we already run (cocos2d-x, Unity, AndEngine, libGDX). Set
    //     explicitly so the choice is deterministic regardless of ANGLE's
    //     built-in platform default.
    //   MACAPK_ANGLE_BACKEND=vulkan: ANGLE-Vulkan on top of MoltenVK, the ONLY
    //     way to give an APK a real GLES 3.1 context on macOS (Apple's system
    //     GL and ANGLE's Metal backend both cap at a 3.0 feature level).
    //     RenderDragon-class engines (Minecraft Bedrock) REQUIRE 3.1 (compute
    //     shaders, image load/store, SSBO) and render nothing without it.
    // MoltenVK is the Vulkan ICD; ANGLE's bundled Vulkan-Loader finds it via
    // VK_ICD_FILENAMES (older loaders) / VK_DRIVER_FILES (>=1.3.207) pointing
    // at the MoltenVK_icd.json staged next to the runtime binary.
    {
        // ICD discovery is INDEPENDENT of the backend decision below, so it
        // happens FIRST and unconditionally. VK_ICD_FILENAMES/VK_DRIVER_FILES
        // are how the Vulkan-Loader in this process finds a driver at all;
        // they are our stand-in for the platform loader on a real device,
        // which always knows where the vendor HAL lives no matter which
        // graphics API the app also happens to use. Leaving them unset unless
        // ANGLE itself picked Vulkan made Vulkan availability depend on a GLES
        // heuristic: a guest that talks Vulkan directly (never touching GLES,
        // so ApkNeedsGles31 cannot see it) got VK_ERROR_INCOMPATIBLE_DRIVER
        // from vkCreateInstance, which reads to the app as "this machine has
        // no GPU driver" -- an answer real Android never gives. Setting them
        // on the Metal path costs nothing: ANGLE-Metal never loads the Vulkan
        // loader, so nothing reads these vars unless someone calls Vulkan.
        const fs::path icd = fs::path(bin_dir) / "MoltenVK_icd.json";
        std::error_code icd_ec;
        const bool have_icd = fs::exists(icd, icd_ec);
        if (have_icd) {
            setenv("VK_ICD_FILENAMES", icd.string().c_str(), 1);
            setenv("VK_DRIVER_FILES",  icd.string().c_str(), 1);
        }

        // Pairip refs default-on (v1.0.1573). A pairip-protected app is detected
        // STRUCTURALLY (Image::is_pairip_import_protected: a large count of reloc-less
        // .got.plt holes), and its non-engine protected libs are neutered so boot reaches
        // the engine -- general to any pairip app. For the ONE build harvested so far
        // (Play-Minecraft), bundled refs next to the runtime (<bin>/../lib/pairip-refs)
        // additionally (a) fill the import holes by name, (b) memcpy the one decrypted
        // code segment, and (c) inject the decrypted string pool, so MC boots + renders +
        // reaches Xbox sign-in with NO env flags. We default the ref PATHS here
        // (overwrite=0, so the dev repro's explicit env still wins). Safety: every
        // downstream application is gated on the structural pairip detection + a soname
        // match; the raw-.text code patch is additionally gated on a per-build byte-check
        // (apply_pairip_code_patches verifies an FNV-1a of the pristine region against a
        // .fnv sidecar); the string inject is self-gating (FindClass/GetStaticFieldID only
        // writes fields the app actually declares). So a non-pairip app, or a different
        // pairip build, is left untouched. Kill switch: MACAPK_PAIRIP_GATE_OFF=1.
        const fs::path pref = fs::path(bin_dir) / "../lib/pairip-refs";
        std::error_code pref_ec;
        if (!std::getenv("MACAPK_PAIRIP_GATE_OFF") && fs::exists(pref, pref_ec)) {
            // Per-build ref selection (v1.0.1745). Only the string POOL is genuinely per build; the
            // import table and the decrypted code blob are BUILD-INVARIANT (the protected libs are
            // ~99% identical across builds, .plt/.rela.plt/.dynsym are byte-identical, and the
            // decrypted blob is identical -- pairip just re-encrypts it per build; measured in
            // docs/pairip-build-invariance_2026-10-08.md, generated by debug/pairip_static_generate.py
            // with no device capture). We still key selection on the libmaesdk FNV so each build loads
            // its own pool, and so the FNV-gated code patch always byte-matches. Default to top-level,
            // but if a refs/builds/<tag>/ subdir's code_patches/libmaesdk.so__0x332000.fnv matches
            // the INSTALLED libmaesdk's pristine 0x332000 region, use that subdir, so 45.1/51.1/
            // 52.3 can coexist and the right set loads by the EXACT protected binary (the key is
            // the FNV, not a label). ANY failure or no match falls back to the top-level dir --
            // i.e. exactly the prior single-build behaviour -- so this can never make a working
            // setup worse. The FNV is the same value apply_pairip_code_patches checks, so a
            // selected dir's code blob always build-id-matches.
            fs::path refs = pref;
            try {
                const fs::path maesdk = fs::path(cli.apk).parent_path() / "lib" / "arm64" / "libmaesdk.so";
                const fs::path builds = pref / "builds";
                if (fs::exists(maesdk, pref_ec) && fs::is_directory(builds, pref_ec)) {
                    std::ifstream mf(maesdk, std::ios::binary);
                    std::vector<char> buf(0xD000);
                    mf.seekg(0x332000);
                    mf.read(buf.data(), (std::streamsize)buf.size());
                    if (mf && mf.gcount() == (std::streamsize)buf.size()) {
                        uint64_t h = 0xcbf29ce484222325ULL;
                        for (unsigned char c : buf) h = (h ^ (uint64_t)c) * 0x100000001b3ULL;
                        char want[17];
                        std::snprintf(want, sizeof want, "%016llx", (unsigned long long)h);
                        for (const auto& e : fs::directory_iterator(builds, pref_ec)) {
                            std::ifstream ff(e.path() / "code_patches" / "libmaesdk.so__0x332000.fnv");
                            std::string got;
                            if (ff && std::getline(ff, got)) {
                                const auto end = got.find_last_not_of(" \t\r\n");
                                got = (end == std::string::npos) ? std::string() : got.substr(0, end + 1);
                                if (got == want) { refs = e.path(); break; }
                            }
                        }
                    }
                }
            } catch (...) { refs = pref; }
            std::fprintf(stderr, "[macapk] pairip refs dir: %s (%s)\n", refs.string().c_str(),
                         (refs == pref) ? "top-level" : "per-build FNV match");
            const fs::path itbl = refs / "pairip_import_table.txt";
            const fs::path pool = refs / "pool_inject.bin";
            const fs::path cpd  = refs / "code_patches";
            if (fs::exists(itbl, pref_ec)) setenv("MACAPK_PAIRIP_IMPORT_TABLE",   itbl.string().c_str(), 0);
            if (fs::exists(pool, pref_ec)) setenv("MACAPK_PAIRIP_INJECT_STRINGS", pool.string().c_str(), 0);
            if (fs::exists(cpd,  pref_ec)) setenv("MACAPK_PAIRIP_CODE_PATCH_DIR",  cpd.string().c_str(), 0);
        }
        // The platform resource table (v1.0.1589): the framework-res apk the
        // Java ResourceIndex resolves @android: references and platform themes
        // against. Bundled next to the tools by scripts/stage_apktool.sh (the
        // bundle) / runtime/scripts/build.sh (the dev tree) -- apktool's own
        // prebuilt/android-framework.jar, API 36, Apache-2.0 -- and DEFAULTED
        // here with overwrite=0 so an explicit MACAPK_FW_RES_APK (debug/
        // fwprobe.sh pins the API-30 build for its API-30 golden) still wins.
        // Before this the table was chosen by the process's cwd: the repo's
        // API-30 build for every script-launched run, apktool's API-36 cache
        // for an installed stub, and the two mis-name every post-30 public id
        // differently (docs/resources-in-place-gap-assessment_2026-09-20.md
        // FINDING 1).
        {
            const fs::path fwres = fs::path(bin_dir) / "tools" / "framework-res.apk";
            std::error_code fw_ec;
            if (fs::is_regular_file(fwres, fw_ec))
                setenv("MACAPK_FW_RES_APK", fwres.string().c_str(), 0);
            else
                std::fprintf(stderr,
                    "[macapk] no bundled framework-res.apk at %s; the Java side falls back to"
                    " apktool's ~/Library/apktool/framework/1.apk\n", fwres.c_str());
        }
        // One-time visibility into the ref-default outcome. A pairip app that boots ref-less
        // (no import table / string pool) crashes deep in the guest (jni_GetStringLength on a
        // null pool jstring), and the ONLY way to tell "refs missing" from "refs present but a
        // downstream bug" is to know here whether they resolved. Cheap, once per launch.
        std::fprintf(stderr, "[macapk] pairip refs: pref=%s exists=%d | IMPORT_TABLE=%s INJECT_STRINGS=%s CODE_PATCH_DIR=%s\n",
            pref.string().c_str(), (int)fs::exists(pref, pref_ec),
            std::getenv("MACAPK_PAIRIP_IMPORT_TABLE")   ? "set" : "UNSET",
            std::getenv("MACAPK_PAIRIP_INJECT_STRINGS") ? "set" : "UNSET",
            std::getenv("MACAPK_PAIRIP_CODE_PATCH_DIR") ? "set" : "UNSET");

        // Set when the ES1 scan below is DEFERRED onto the unpack worker (see
        // the comment there). Declared at function scope because the worker and
        // the setenv that applies its answer both live further down.
        // (Declared in the enclosing scope; see below.)
        // Backend decision order: an explicit MACAPK_ANGLE_BACKEND=metal|vulkan
        // wins (debug/override); otherwise AUTO-DETECT -- Vulkan iff the app's
        // native code uses GLES 3.1 (see ApkNeedsGles31), else Metal. This makes
        // a 3.1 app (MCPE) render from the .app with no flags while every
        // ES 2.0/3.0 app keeps its native Metal path unchanged. Generic: no
        // per-APK/package branch. v1.0.608.
        const char* be = std::getenv("MACAPK_ANGLE_BACKEND");
        bool want_vulkan;
        if (be && std::string(be) == "vulkan") {
            want_vulkan = true;
            std::fprintf(stderr, "[macapk] ANGLE backend: VULKAN (env override)\n");
        } else if (be && std::string(be) == "metal") {
            want_vulkan = false;
            std::fprintf(stderr, "[macapk] ANGLE backend: METAL (env override)\n");
        } else if (MacapkWindowFirst(cli) && !EnvIs("MACAPK_DEFER_ES31", "0")) {
            // Decided on the unpack worker; see need_es31_scan. Nothing reads
            // ANGLE_DEFAULT_PLATFORM before the join except CocoaInit's
            // nextDrawable policy, which CocoaRefreshDrawablePolicy re-reads.
            // MACAPK_DEFER_ES31=0 puts the scan back before the window (A/B).
            need_es31_scan = true;
            want_vulkan = false;
        } else {
            want_vulkan = ApkNeedsGles31Cached(cli.apk, cli.android_root);
            LogAngleAutoDetect(want_vulkan);
        }
        // ES1 fixed-function detection. Independent of the Vulkan/Metal
        // choice above: it selects the CONTEXT TYPE, not the backend.
        // MACAPK_GLES1_CONTEXT=0/1 overrides the scan.
        //
        // THE SCAN IS DEFERRED, the override is not. A first-launch scan is an
        // unzip|grep over every native lib in the APK, and it used to run HERE
        // -- before the window exists -- so on a heavy app it was pure black
        // screen: measured 14.0 s of cold time-to-window on Candy Crush and
        // 16.9 s WARM on Subway Surf (debug/boottime.sh, 2026-09-22). Its
        // answer is not needed until Boot.run asks for a GL context, which is
        // far downstream, so it rides along on the unpack worker instead and
        // the result is applied on the main thread after the join. The env
        // override stays here because reading an environment variable is free
        // and answering it early keeps the log honest about what was decided.
        if (const char* g1 = std::getenv("MACAPK_GLES1_CONTEXT");
            g1 && (g1[0] == '0' || g1[0] == '1')) {
            std::fprintf(stderr,
                "[macapk] GLES1 fixed-function context: %s (env override)\n",
                g1[0] == '1' ? "YES" : "no");
        } else {
            need_es1_scan = true;
        }
        if (need_es31_scan) {
            es31_have_icd = have_icd;
            es31_icd = icd.string();
        } else {
            ApplyAngleBackend(want_vulkan, have_icd, icd.string());
        }
    }

    const fs::path framework_jar = fs::path(bin_dir) / "../lib/android-framework.jar";
    const fs::path loader_jar    = fs::path(bin_dir) / "../lib/macapk-loader.jar";
    // Vendored BouncyCastle: Android ships a "BC" crypto provider; the host
    // OpenJDK does not. Boot.java registers it reflectively so BC-named
    // algorithms (e.g. PBEWITHSHAAND256BITAES-CBC-BC, needed by Google Play
    // LVL's AESObfuscator that MC's GooglePlayStore constructs) resolve.
    const fs::path bcprov_jar    = fs::path(bin_dir) / "../lib/bcprov-jdk18on-1.79.jar";

    // java.base patch dir (see runtime/scripts/build.sh). Holds compiled
    // replacements for java.* classes that CANNOT be delivered as ordinary
    // framework-jar shadows, because java.* may only be defined by the
    // bootstrap loader and ClassLoader.preDefineClass rejects an app-classpath
    // copy outright ("Prohibited package name").
    const fs::path java_base_patch_dir = fs::path(bin_dir) / "../lib/java_base_patch";

    // jdk.unsupported patch dir. Same mechanism, different module: `sun.misc`
    // belongs to jdk.unsupported, so a framework-jar shadow can never win
    // (the platform loader resolves the module's copy before the app
    // classpath is ever consulted) and javac will not even compile
    // `package sun.misc;` outside that module. Holds libcore's
    // sun.misc.Unsafe -- see runtime/jdk_unsupported_patch/sun/misc/Unsafe.java.
    const fs::path jdk_unsupported_patch_dir =
        fs::path(bin_dir) / "../lib/jdk_unsupported_patch";

    // Packaging (Phase C, v1.0.203): a distributed .app bundles a JRE next
    // to the runtime (Contents/Resources/jre). On such a target there is no
    // system clang, so default the arm64 wrapper packer to the in-process
    // Mach-O emitter (MACAPK_ARM64_EMIT), which needs no compiler. The
    // v1.0.802: the in-process emitter is now the ONLY arm64 path, so this
    // export is vestigial and kept only so an explicitly-set value still
    // reads consistently. The clang path it used to select is deleted.
    //
    // Packaging (Phase D) REMOVED v1.0.802: the bundled LLVM toolchain is gone.
    // arm32rc emits the Mach-O directly and the arm64 packer uses the in-process
    // emitter, so a distributed .app needs no compiler and no LLVM.
    {
        std::error_code emit_ec;
        if (fs::exists(fs::path(bin_dir) / "jre", emit_ec)) {
            setenv("MACAPK_ARM64_EMIT", "1", 0);

        }
    }

    // The cache root is where COLD extracts stage
    // ("<pkg>.staging.<pid>"); the finalize block below always lands the
    // result under <android_root>/.macapk/code/<pkg>_<ver> (by rename,
    // or by copy across volumes), so the cache root holds no finished
    // installs -- since the shared root was removed (owner decision
    // 2026-08-10) the per-version code/data pair under the android root
    // is an app's ONLY home.
    //
    // MACAPK_APP_CACHE_DIR is still honoured for explicit overrides (a
    // per-app bundle's stub points it inside the bundle; CI rigs pin a
    // temp dir). Without it, we compute android_root the same way
    // Boot.java and SetupAndroidRootEx do (CLI flag >
    // MACAPK_ANDROID_ROOT env > $HOME/Documents/android) and stage under
    // <android_root>/data/data.
    //
    // The default exists for the BARE dev invocation only
    // (`macapk-runtime --apk X` with no root anywhere): bringup, triage
    // and the sweep harness rely on it. An INSTALLED app never reaches
    // it -- its stub always passes --android-root
    // <bundle>/Contents/android -- so reaching the default with no CLI
    // and no env is worth saying out loud: if a stub ever regressed
    // into omitting the flag, this line is the only visible symptom
    // short of data appearing in the wrong tree.
    //
    // That default is $HOME/Library/Caches/MacApk/scratch since
    // 2026-08-12; it used to be $HOME/Documents/android, which every
    // caller that forgot the flag kept resurrecting after the owner
    // deleted it. ResolveAndroidRootDir (apk_unpacker.cpp) has the full
    // reasoning and is the single place the value lives.
    if (cli.android_root.empty()) {
        std::fprintf(stderr,
            "[macapk] no --android-root/MACAPK_ANDROID_ROOT given; using "
            "the dev default (%s). Installed apps always pass their "
            "bundle root explicitly.\n",
            ResolveAndroidRootDir("").c_str());
    }
    if (const char* existing = std::getenv("MACAPK_APP_CACHE_DIR");
        !existing || !*existing) {
        // One shared resolver (apk_unpacker.cpp) replaces the third
        // independent copy of the CLI > env > $HOME chain that used to
        // live here; the copies had already diverged once (the literal
        // "~" directory bug this block's history records).
        fs::path final_cache =
            fs::path(ResolveAndroidRootDir(cli.android_root))
            / "data" / "data";
        std::error_code ec;
        fs::create_directories(final_cache, ec);
        setenv("MACAPK_APP_CACHE_DIR", final_cache.c_str(), 1);
        std::fprintf(stderr,
                     "[macapk] cache dir defaulted to %s "
                     "(override with MACAPK_APP_CACHE_DIR)\n",
                     final_cache.c_str());
    } else {
        std::fprintf(stderr,
                     "[macapk] cache dir = %s (from env)\n",
                     existing);
    }

    // Tell the unpacker whether this process will BOOT the app afterwards:
    // a warm launch that finds a background DEX conversion still running
    // WAITS for it (converting per-class under the booting app's 1 GiB heap
    // is where TikTok's 1.45 MB mega-class OOMs), while --extract-only must
    // return to the installer immediately. See SettlePendingDexJar.
    if (cli.extract_only) setenv("MACAPK_EXTRACT_ONLY", "1", 1);

    // ---- 0c. WINDOW FIRST. Put something on screen BEFORE the long pole. ----
    //
    // THE BUG THIS FIXES (owner, 2026-09-22): "an arm64 app does not open."
    // UnpackApk below does the DEX->JAR conversion AND the Stage-6 arm64
    // native translation SYNCHRONOUSLY, and it used to run on the main thread
    // with no window in existence -- the placeholder NSWindow is created much
    // later, inside CocoaInit on the way into Boot.run. So a COLD arm64 launch
    // (which pays BOTH conversion and heavy native translation) showed NOTHING
    // AT ALL for the whole long pole, and the only honest reading of that from
    // the Dock is "it failed to open". The tiny ARM32 corpus apps convert in a
    // blink, which is why they always looked fine.
    //
    // The fix is the ordering, not the work: create the window here, hand the
    // unpack to a worker, and spend the wait servicing AppKit so the window
    // paints, moves and closes like any other macOS app while it happens.
    //
    // Orientation comes from the APK's OWN binary manifest, which
    // ScanManifestOrientation reads straight out of the zip -- it never used
    // the unpacked manifest path (it ignores that argument), so it is
    // available this early. For a split bundle (.apkm/.xapk) the base manifest
    // is one level down and the scan legitimately comes back AUTO; the window
    // then opens portrait and the authoritative block further down reshapes it
    // once the installed base.apk is known. A reshape mid-boot is a far
    // smaller problem than no window at all.
    //
    // NOT for --extract-only: that path is the installer, it has no user
    // waiting on a window of ours, and a window there would steal focus from
    // the store UI. MACAPK_WINDOW_FIRST=0 restores the old ordering for an A/B.
    const bool window_first = MacapkWindowFirst(cli);
    // The window policy reads the SAME manifest early and late (a bundle's base
    // is streamed out of the bundle here), so for it the early answer is the
    // authoritative one and the post-unpack block below finds nothing to fix.
    const bool auto_window = cli.orient == Orient::AUTO && !(cli.width > 0 && cli.height > 0);
    std::string window_policy_source;
    const bool aosp_window = auto_window && WindowPolicyIsAosp(cli.android_root, &window_policy_source);
    if (auto_window && !aosp_window)
        std::fprintf(stderr, "[macapk] window-policy: off (%s) -- the v1.0.1646 legacy window rule\n",
                     window_policy_source.c_str());
    if (window_first) {
        if (!(aosp_window && SizeWindowByPolicy(cli.apk, cli.android_root, "early"))) {
            Orient early = cli.orient;
            if (early == Orient::AUTO) {
                Orient hint = ScanManifestOrientation(std::string(), cli.apk);
                early = (hint == Orient::AUTO) ? Orient::PORTRAIT : hint;
            }
            if (cli.width > 0 && cli.height > 0) CocoaSetPhoneSize(cli.width, cli.height);
            else                                 CocoaSetPhoneSizeMax(early == Orient::LANDSCAPE);
        }
        CocoaInit();
        BootClock("placeholder window shown (T0)");
    }

    // ---- 1. Convert the APK to a JAR (com.macapk.dex) and stage it. ----
    UnpackedApp app;
    if (window_first) {
        // The unpack runs on a worker; the main thread stays in AppKit.
        //
        // UnpackApk touches no Cocoa and no ObjC -- it is file I/O plus
        // posix_spawn/system() children that exec immediately -- so it is
        // safe off the main thread, and the fork-after-AppKit-init hazard
        // does not apply to a fork that execs at once.
        std::atomic<bool> unpack_done{false};
        UnpackedApp result;
        std::thread worker([&] {
            std::fprintf(stderr, "[macapk] unpack worker: entered (pid %d)\n", (int)getpid());
            // The deferred ES3.1 and ES1 scans ride along here: each is an
            // unzip|grep over the APK's native libs whose answer is not needed
            // until Boot.run opens a display or asks for a GL context. Running
            // them FIRST (before the much longer unpack) keeps the results ready
            // well before the join, and they write only locals -- setenv happens
            // on the main thread after the join, so nothing races the runloop's
            // own getenv calls. Serial on purpose: a second thread would buy no
            // overlap, because Apple's system() takes a process-wide mutex
            // (__systemfn_mutex, Libc stdlib/FreeBSD/system.c) before its
            // sigaction/posix_spawn and drops it only after wait4 returns (lines
            // 108-154 there), so any system() the unpack made would queue behind
            // the scan anyway.
            if (need_es31_scan)
                es31_scan_result = ApkNeedsGles31Cached(cli.apk, cli.android_root);
            if (need_es1_scan)
                es1_scan_result = ApkNeedsGles1Cached(cli.apk, cli.android_root);
            try {
                result = UnpackApk(cli.apk, bin_dir);
            } catch (const std::exception& e) {
                std::fprintf(stderr, "[macapk] unpack worker: EXCEPTION %s\n", e.what());
                unpack_done.store(true, std::memory_order_release);
                throw;
            } catch (...) {
                std::fprintf(stderr, "[macapk] unpack worker: UNKNOWN EXCEPTION\n");
                unpack_done.store(true, std::memory_order_release);
                throw;
            }
            std::fprintf(stderr, "[macapk] unpack worker: returned (pid %d)\n", (int)getpid());
            unpack_done.store(true, std::memory_order_release);
        });
        // Only raise the setup notice if the work is actually going to take a
        // perceptible amount of time. A WARM launch finishes here in well
        // under the threshold, so a returning user never sees a flash of it;
        // a COLD one sees it for as long as the work really takes, which is
        // the whole point of showing it.
        const auto t_start = std::chrono::steady_clock::now();
        bool overlay_up = false;
        long hb = 0;
        while (!unpack_done.load(std::memory_order_acquire)) {
            CocoaPumpEvents(0.05);
            if (++hb % 100 == 0)
                std::fprintf(stderr, "[macapk] window: pumping, unpack still running (%lds)\n",
                             (long)std::chrono::duration_cast<std::chrono::seconds>(
                                 std::chrono::steady_clock::now() - t_start).count());
            if (!overlay_up) {
                auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::steady_clock::now() - t_start).count();
                if (ms >= 700) {
                    overlay_up = true;
                    const char* label = std::getenv("MACAPK_APP_LABEL");
                    std::string head = std::string("First-time setup")
                        + ((label && label[0]) ? std::string(" — ") + label
                                               : std::string());
                    CocoaShowSetupOverlay(
                        head.c_str(),
                        "Converting the app's code so it can run natively on "
                        "macOS. This happens once per install; later launches "
                        "skip it entirely.");
                    std::fprintf(stderr, "[macapk] window: setup overlay shown "
                                         "(cold launch, unpack still running)\n");
                }
            }
        }
        worker.join();
        BootClock(overlay_up ? "unpack worker joined (cold: setup overlay was up)"
                             : "unpack worker joined");
        app = std::move(result);
        if (overlay_up) CocoaHideTranslationOverlay();
    } else {
        if (need_es1_scan)
            es1_scan_result = ApkNeedsGles1Cached(cli.apk, cli.android_root);
        app = UnpackApk(cli.apk, bin_dir);
    }
    // Apply the deferred GLES 3.1 answer, on the main thread, with the worker
    // joined and before the JVM exists -- so no EGL display has been opened yet
    // and no CAMetalLayer is inside a nextDrawable while the policy is re-read.
    if (need_es31_scan) {
        LogAngleAutoDetect(es31_scan_result);
        ApplyAngleBackend(es31_scan_result, es31_have_icd, es31_icd);
        CocoaRefreshDrawablePolicy();
    }
    // Apply the deferred ES1 answer, on the main thread, with the worker
    // already joined. Still far upstream of the GL context Boot.run creates.
    if (need_es1_scan) {
        setenv("MACAPK_GLES1_CONTEXT", es1_scan_result ? "1" : "0", 1);
        std::fprintf(stderr,
            "[macapk] GLES1 auto-detect: app %s ES1 fixed-function APIs "
            "-> requesting an ES%d context\n",
            es1_scan_result ? "USES" : "does NOT use", es1_scan_result ? 1 : 2);
    }

    // Finalize a COLD extract's staging dir into its terminal layout.
    //
    // v1.0.848: keyed off staged_fresh, not a dir-name comparison. Cold
    // extracts stage in a per-process "<pkg>.staging.<pid>" so concurrent
    // same-package installs cannot destroy each other's staging; every
    // cache-hit path leaves staged_fresh false.
    //
    // v1.0.944 (Contract 1 + owner decision 2026-08-10, per-version
    // identity): the terminal layout is the CODE/DATA SPLIT with ONE
    // shared, version-qualified basename:
    //   <root>/.macapk/code/<pkg>_<ver>/  install products (regenerable,
    //                                     REPLACED on a same-version
    //                                     reinstall)
    //   <root>/data/data/<pkg>_<ver>/     app data + extracted assets
    //                                     (precious, never deleted here)
    // Boot derives the data-dir name LEXICALLY from basename(appDir)
    // (Boot.java ~:760), so handing it the CODE dir as appDir makes both
    // sides agree on the same name with no Java change. Versions of a
    // package COEXIST ("one .app per version"); nothing ever collapses
    // or renames another version's dirs.
    //
    // Since the owner's bundle decision (2026-08-10, "all the app data
    // contained to the .app bundle" + "remove [the shared root] entirely"
    // + "for dev, we will start fresh") the per-version pair is an app's
    // ONLY home and versions are INDEPENDENT:
    //   - Same-version reinstall (incl. a rebuilt test APK, the common
    //     case on this machine): the code dir is REPLACED, the data dir
    //     stays where it is and only the closed allowlist of runtime
    //     artifact names (IsCodeArtifactName) is stripped from it, so
    //     the saves never move at all. AOSP treats this as an ordinary
    //     data-preserving update.
    //   - A DIFFERENT version starts with a fresh, EMPTY data dir. The
    //     cross-version carry-forward (pick_predecessor/carry_forward,
    //     which scanned data/data for sibling versions) is DELETED, not
    //     just unreachable: a per-app bundle's root contains only that
    //     one version, so the sibling scan could never find a
    //     predecessor there anyway (the v1.0.946 A9 finding), and the
    //     owner chose independence over inheritance.
    //   - The legacy combined layout (code+saves in one data/data dir)
    //     is gone with the shared root; nothing scans for it and
    //     nothing can create it -- on a rename failure the staging tree
    //     is COPIED into the code dir instead of being merged into a
    //     combined dir (see the code side below).
    //   - Unknown file names always classify as DATA and are never
    //     deleted -- the safe direction when a later lane adds a new
    //     artifact name.
    PkgFinalizeLock pkg_lock;
    if (app.staged_fresh) {
        fs::path cur = app.app_dir;
        fs::path staging_root = cur.parent_path();
        // Manifest package wins over the filename-derived name.
        // SetupAndroidRootEx does the same fallback chain so the two
        // converge on a single dir per app.
        //
        // The package straight from the APK's binary AndroidManifest.xml,
        // degrading to the filename heuristic only when that is unreadable
        // (v1.0.989; since v1.0.1590 there is no text decode to consult
        // first). The 2026-08-14 apktool-PATH outage is why this must never
        // depend on a decode: it named this dir "base_7.11.1" (from
        // base.apkm), splitting BOTH the code and the data tree away from
        // com.yodo1.crossyroad_7.11.1, so the app started over with fresh
        // data and the owner's age gate re-ran. The VERSION half of this
        // derivation reads the same manifest (DeriveAppVersion step 1).
        // app.apk_path here is the installed base for bundles, a real APK
        // either way.
        std::string mfst_pkg = BinaryManifestPackage(app.apk_path);
        if (mfst_pkg.empty()) mfst_pkg = app.package_name;
        std::string ver = DeriveAppVersion(app.manifest_path, mfst_pkg,
                                           app.package_name, app.apk_path);

        const fs::path root      = ResolveAndroidRootDir(cli.android_root);
        const fs::path code_root = root / kCodeRootRel;
        const fs::path data_root = root / "data" / "data";
        // Per-version identity (owner decision 2026-08-10): code and data
        // share one version-qualified basename, and versions of a package
        // COEXIST -- nothing here ever merges or renames another
        // version's dirs. Boot.java derives the data dir lexically from
        // basename(appDir), so handing it the code dir yields data_dir.
        const std::string dir_name = ver.empty() ? mfst_pkg
                                                 : mfst_pkg + "_" + ver;
        const fs::path code_dir = code_root / dir_name;
        const fs::path data_dir = data_root / dir_name;

        // Serialize same-package finalizes across processes (the
        // launcher installs 4-wide). Held through SetupAndroidRootEx so
        // two installs of one package cannot interleave dir replacement
        // with asset extraction; released after the android root is set
        // up. An aborted holder is stolen via pid liveness.
        pkg_lock = AcquirePkgFinalizeLock(staging_root, mfst_pkg);

        // Repoint app.* fields at the finalized dir. v1.0.152 history:
        // for bundles the merged apk was staged INSIDE app_dir; without
        // the repoint SetupAndroidRoot saw a dead path and silently
        // skipped lib/asset extraction (Crossy Road rendered nothing).
        auto adopt = [&](const fs::path& target) {
            std::error_code lec;
            fs::path old_apk = app.apk_path;
            app.app_dir       = target.string();
            app.app_jar       = (target / "classes.jar").string();
            app.manifest_path = (target / "AndroidManifest.xml").string();
            if (!old_apk.empty()) {
                fs::path rel = fs::relative(old_apk, cur, lec);
                if (!lec && !rel.empty()
                    && rel.string().rfind("..", 0) != 0) {
                    app.apk_path = (target / rel).string();
                }
            }
        };

        // Did the SOURCE APK itself change since the existing extraction,
        // or did only our side move (ABI selector flip, JAVA_ID bump)?
        // The distinction decides lib_native's fate below: translated
        // dylibs are a pure function of the APK's .so files + NATIVE_ID,
        // so a framework/converter bump or ABI A/B must NOT throw away
        // minutes of ARM32 translation (the "-<abi>" cache suffix already
        // keys flips apart, v1.0.864), while an APK content change MUST
        // (a stale dylib surviving re-extraction is the measured
        // false-verdict hazard of 2026-08-09).
        auto stamp_mtime = [](const fs::path& dir) -> long long {
            std::ifstream f(dir / ".source-mtime");
            long long v = 0;
            f >> v;
            return f ? v : 0;
        };
        long long cur_apk_mtime = 0;
        {
            std::error_code mec;
            auto t = fs::last_write_time(cli.apk, mec);
            if (!mec)
                cur_apk_mtime =
                    static_cast<long long>(t.time_since_epoch().count());
        }
        auto apk_unchanged_vs = [&](const fs::path& dir) {
            long long old_m = stamp_mtime(dir);
            return old_m != 0 && old_m == cur_apk_mtime;
        };

        // Where the code side actually landed. false = neither rename nor
        // copy worked and this session runs from the staging dir.
        bool code_landed = false;

        // ---- CODE side: replace <code_root>/<dir_name> with staging ----
        {
            std::error_code sec;
            fs::create_directories(code_root, sec);
            // Same-version rebuild: park the existing code dir's
            // lib_native aside when only OUR side moved (see
            // apk_unchanged_vs above), then replace the dir.
            fs::path parked_libs;
            {
                std::error_code kec;
                if (!sec && fs::exists(code_dir, kec) && !kec) {
                    if (apk_unchanged_vs(code_dir)
                        && fs::exists(code_dir / "lib_native", kec) && !kec) {
                        parked_libs = code_root
                            / (".libkeep." + std::to_string(getpid()));
                        std::error_code pkec;
                        fs::rename(code_dir / "lib_native", parked_libs, pkec);
                        if (pkec) parked_libs.clear();
                    }
                    // v1.0.1641: before ApplicationInfo.dataDir was fixed to name
                    // the DATA dir, apps stored real data in this CODE dir
                    // (measured: Instagram's session prefs, drafts, app_* stores,
                    // SoLoader's lib-main). Boot moves it home at launch, but an
                    // app that has not launched on a fixed build and is
                    // reinstalled / updated under the same dirName would lose it
                    // right here. Rescue it into the data dir first.
                    bool keep_old = false;
                    if (RescueAppDataFromCodeDir(code_dir, data_dir) > 0) {
                        // Something could not be moved: never delete it. Park
                        // the whole old code dir aside, hidden, and keep going.
                        std::error_code pec;
                        fs::path parked = code_root
                            / ("." + dir_name + ".rescue-" + std::to_string(getpid()));
                        fs::rename(code_dir, parked, pec);
                        keep_old = static_cast<bool>(pec);   // could not park: do not delete
                        std::fprintf(stderr,
                            "[macapk] kept the old code dir as %s (app files in it could "
                            "not be moved)%s\n", pec ? code_dir.c_str() : parked.c_str(),
                            pec ? " -- park failed, NOT deleted" : "");
                    }
                    std::error_code rmec;
                    if (!keep_old) fs::remove_all(code_dir, rmec);
                }
            }
            sec.clear();
            fs::rename(cur, code_dir, sec);
            if (!sec) {
                code_landed = true;
            } else {
                // rename fails legitimately when staging and the android
                // root sit on different volumes (a CI rig pinning
                // MACAPK_APP_CACHE_DIR to a scratch disk); copy is the
                // EXDEV-proof equivalent. What must NOT happen is the old
                // fallback -- a combined code+data dir in the STAGING
                // root -- because that hands an installed app a second
                // home outside its bundle, the exact layout the owner
                // deleted (2026-08-10).
                std::error_code cec;
                fs::copy(cur, code_dir,
                         fs::copy_options::recursive
                         | fs::copy_options::overwrite_existing
                         | fs::copy_options::copy_symlinks, cec);
                if (!cec) {
                    std::error_code rmec;
                    fs::remove_all(cur, rmec);
                    code_landed = true;
                    std::fprintf(stderr,
                        "[macapk] split: staging copied (not renamed) into "
                        "%s (%s)\n",
                        code_dir.c_str(), sec.message().c_str());
                } else {
                    std::fprintf(stderr,
                        "[macapk] split: cannot move OR copy staging %s -> "
                        "%s (rename: %s; copy: %s) -- running this session "
                        "from the staging dir\n",
                        cur.c_str(), code_dir.c_str(),
                        sec.message().c_str(), cec.message().c_str());
                }
            }
            if (code_landed && !parked_libs.empty()) {
                std::error_code bkec;
                fs::rename(parked_libs, code_dir / "lib_native", bkec);
                if (!bkec) {
                    std::fprintf(stderr,
                        "[macapk] split: kept translated lib_native across "
                        "the rebuild (source APK unchanged)\n");
                }
            } else if (!parked_libs.empty()) {
                // Code side never landed; do not strand the parked dylibs
                // (regenerable -- losing them costs a re-translation, not
                // data).
                std::error_code bkec;
                fs::remove_all(parked_libs, bkec);
            }
        }

        if (code_landed) {
            // ---- DATA side ----
            std::error_code dec;
            ReplaceCompatLink(data_dir);
            bool had_data = fs::exists(data_dir, dec) && !dec;
            if (had_data) {
                // Same-version reinstall: the data dir stays where it is
                // and only the closed code-artifact list is stripped from
                // it -- stale copies of what the fresh code dir now owns
                // (Android likewise clears code_cache on update). Unknown
                // names are data and stay. The legacy-combined lib_native
                // rescue that lived here is gone with the shared root: a
                // split-era data dir never holds lib_native, so on the
                // first launch after a pre-split dir somehow survives the
                // wipe, the cost is a re-translation, never data.
                size_t removed = 0;
                std::error_code itec;
                for (auto& e : fs::directory_iterator(data_dir, itec)) {
                    if (itec) break;
                    if (!IsCodeArtifactName(e.path().filename().string()))
                        continue;
                    std::error_code rmec;
                    fs::remove_all(e.path(), rmec);
                    if (!rmec) ++removed;
                }
                std::fprintf(stderr,
                    "[macapk] reinstall into existing data dir %s "
                    "(app data preserved; %zu stale code artifact(s) "
                    "removed)\n", data_dir.c_str(), removed);
            } else {
                // First install of THIS version: a deliberately empty
                // data dir. Versions are independent (owner decision
                // 2026-08-10, "for dev, we will start fresh") -- nothing
                // is inherited from any sibling version anywhere.
                fs::create_directories(data_dir, dec);
                std::fprintf(stderr,
                    "[macapk] fresh data dir %s (versions are "
                    "independent; no cross-version carry-forward)\n",
                    data_dir.c_str());
            }
            adopt(code_dir);
            MirrorInstallMetadata(code_dir.string());
            std::fprintf(stderr,
                "[macapk] split install: code=%s data=%s\n",
                code_dir.c_str(), data_dir.c_str());
        } else {
            // Degraded last resort (rename AND copy failed above): run
            // this one session from the staging dir so the user still
            // gets a window. Nothing is written to any other location,
            // and the next launch re-stages cleanly. The data dir Boot
            // derives from this basename is the staging-named one; that
            // is the pre-existing degenerate behavior of the old
            // "continuing with the staging dir" branch, now loud.
            adopt(cur);
        }
    }

    std::fprintf(stderr,
                 "[macapk] app=%s jar=%s\n",
                 app.package_name.c_str(), app.app_jar.c_str());

    // ---- 1b. Set up the user-visible android filesystem mirror. ----
    // We need this before the shim is loaded so the path-remap helpers
    // see the right root from their very first call.
    AndroidRootResult android = SetupAndroidRootEx(cli.android_root, app);
    const std::string& android_root = android.root;
    const std::string& android_pkg  = android.pkg;
    // The native per-app proxy panel and Java startup policy must read the
    // same resolved root, including CLI defaults and tilde expansion.
    if (!android_root.empty()) setenv("MACAPK_ANDROID_ROOT", android_root.c_str(), 1);

    // APK-shipped native EXECUTABLES. An app may ship a helper BINARY under
    // lib/<abi>/ and fork it (the lib*.so name is forced by PackageManager,
    // which extracts and chmods 0755 nothing else); macOS cannot exec a Linux
    // ELF, so swap in the macapk-elfexec stand-in. Deliberately OUTSIDE
    // SetupAndroidRootEx's extraction-freshness cache so toggling the gate
    // needs no MACAPK_FORCE_REEXTRACT. No-op for every app that ships none.
    // See include/macapk/apk_native_exec.h.
    // The install dir's lib/ (v1.0.1588); the pre-1588 apktool tree only
    // when the app could not be installed under the root at all.
    const std::string app_lib_root = !app.installed_apk.empty()
        ? (fs::path(app.installed_apk).parent_path() / "lib").string()
        : (fs::path(app.app_dir) / "_apkt" / "lib").string();
    macapk::ReconcileApkNativeExecutables(
        app_lib_root, bin_dir, android_root,
        fs::path(app.app_dir).filename().string());

    // The android root is fully set up; overlapping same-package installs
    // may proceed. (No-op unless this run finalized a cold extract.)
    ReleasePkgFinalizeLock(pkg_lock);

    // A DIRECT launch of a cold extract may have handed its DEX conversion
    // to a detached background child (the install path stops waiting once
    // the parse verdict is in). This process is about to BOOT the app, and
    // converting per-class underneath a booting app risks the app JVM's
    // 1 GiB heap (measured: TikTok's 1.45 MB mega-class OOMs there), so
    // adopt a finished jar or wait for the running converter -- exactly what
    // a warm launch does via the cache-hit path. No-op when nothing is
    // pending, and --extract-only returns to the installer without waiting
    // (the warm-launch settle handles it later).
    if (!cli.extract_only)
        SettlePendingDexJar(fs::path(app.app_dir), /*wait_if_running=*/true);
    BootClock("android root ready (assets, native exec reconcile, DEX settled)");

    // v1.0.80: --extract-only mode. The launcher's Installed-Apps
    // backfill calls us here to populate the data/data/<pkg>_<ver>/
    // dir (icon.png, meta.json, lib_native, classes.jar) without launching
    // the JVM. By the time we get
    // here, UnpackApk + WriteAppMetadata + SetupAndroidRoot have all
    // run, so the dir is complete. Exit clean -- skip JVM boot, Cocoa
    // window creation, native library translation. The launcher then
    // re-scans data/data and the entry shows up with a real label +
    // icon as if the user had launched the app.
    if (cli.extract_only) {
        // icon-lifecycle fan-out lane (2026-07-23), BUG A -- "icon only
        // appears after the first launch, not at install". ROOT CAUSE:
        // WriteAppMetadata (inside UnpackApk, above) only COPIES a raster
        // mipmap row out of the apk (apk_unpacker.cpp ArscBestRasterEntry); an app whose
        // android:icon is an <adaptive-icon>/<vector> XML with no plain
        // raster mipmap (Duolingo's vector owl, GD Meltdown's adaptive
        // robot, Smash Hit) gets NO icon.png here. The only rasterizer that
        // can composite/paint those (Boot.java's writeLauncherIcon --
        // AdaptiveIconDrawable compositing, VectorDrawable path rendering --
        // it needs the real framework Drawable pipeline, which only exists
        // in Java) was reachable exclusively from a REAL boot (run(), called
        // only when a JVM exists), and this --extract-only branch returns
        // long before "---- 2. Boot HotSpot ----" ever runs one. AOSP
        // resolves an app's icon at INSTALL time (PackageManager, off the
        // parsed manifest + resource table), not on first launch -- so match
        // that: when the raster copy found nothing, boot a JVM here and
        // reach the SAME rasterizer via a new minimal entry point,
        // Boot.extractIconOnly (see its doc comment for the full contract:
        // no NativeCocoa.init(), no AppClassLoader / native translation --
        // both irrelevant to painting one PNG, and the former would flash a
        // visible window during a supposedly non-interactive install).
        //
        // Non-fatal by design: CheckException()/StartJvm()'s own abort()
        // convention is deliberately NOT used below. A failure here
        // degrades to the pre-fix behavior (monogram tile in the launcher)
        // rather than aborting an otherwise-successful install.
        std::error_code icon_ec;
        fs::path icon_png_path = fs::path(app.app_dir) / "icon.png";
        bool have_raster_icon = fs::exists(icon_png_path, icon_ec) && !icon_ec
            && fs::file_size(icon_png_path, icon_ec) > 0 && !icon_ec;
        // v1.0.848: also translate the app's native libs at install. The
        // one-time ARM32 lift takes minutes for a large lib and used to
        // land on the FIRST LAUNCH as an unexplained stall; Android does
        // its AOT work (dexopt) at install time, so match that and do the
        // translation here, inside the launcher's visible "Installing..."
        // progress. The work itself runs in Java
        // (Boot.translateNativeLibsForInstall -> the SAME
        // AppClassLoader.preloadAllApkLibs a first launch runs, because
        // the ARM32 per-image load-bias assignment is baked into each
        // dylib and the lib_native cache is existence-based -- a second
        // implementation that ordered/biased differently would poison the
        // cache), so the throw-away JVM below now boots for EITHER job.
        bool has_native_libs = false;
        {
            std::error_code lib_ec;
            fs::path libdir = fs::path(app.app_dir) / "_apkt" / "lib";
            if (fs::exists(libdir, lib_ec) && !lib_ec) {
                for (auto& e : fs::directory_iterator(libdir, lib_ec)) {
                    (void)e;
                    if (!lib_ec) has_native_libs = true;
                    break;
                }
            }
        }
        if (!have_raster_icon || has_native_libs) {
            const fs::path inst_fw_jar = fs::path(bin_dir) / "../lib/android-framework.jar";
            const fs::path inst_ld_jar = fs::path(bin_dir) / "../lib/macapk-loader.jar";
            if (fs::exists(inst_fw_jar) && fs::exists(inst_ld_jar)) {
                std::fprintf(stderr,
                    "[macapk] booting an install-time JVM (icon needed: %s, "
                    "native libs to translate: %s)\n",
                    have_raster_icon ? "no" : "yes",
                    has_native_libs ? "yes" : "no");
                JvmConfig inst_jvm_cfg;
                inst_jvm_cfg.classpath_entries.push_back(inst_fw_jar.string());
                inst_jvm_cfg.classpath_entries.push_back(inst_ld_jar.string());
                inst_jvm_cfg.verbose = cli.verbose;
                JavaVM* inst_vm  = nullptr;
                JNIEnv* inst_env = nullptr;
                StartJvm(inst_jvm_cfg, &inst_vm, &inst_env);
                RegisterAllNativeMethods(inst_env);
                // Same "effective apk" resolution main.cpp uses for
                // Boot.run (F8 plan 5): a bundle input (.apkm/.xapk/
                // .apks) presents the merged single-zip, a plain
                // .apk presents itself.
                std::string inst_java_apk =
                    (!app.apk_path.empty() && fs::exists(app.apk_path))
                        ? app.apk_path : cli.apk;
                jclass inst_boot_cls = inst_env->FindClass("com/macapk/loader/Boot");
                if (inst_boot_cls && !inst_env->ExceptionCheck()) {
                    if (!have_raster_icon) {
                        jmethodID icon_m = inst_env->GetStaticMethodID(inst_boot_cls,
                            "extractIconOnly",
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                        if (icon_m && !inst_env->ExceptionCheck()) {
                            jstring j_icon_apk = inst_env->NewStringUTF(inst_java_apk.c_str());
                            jstring j_icon_dir = inst_env->NewStringUTF(app.app_dir.c_str());
                            jstring j_icon_mnf = inst_env->NewStringUTF(app.manifest_path.c_str());
                            inst_env->CallStaticVoidMethod(inst_boot_cls, icon_m,
                                j_icon_apk, j_icon_dir, j_icon_mnf);
                        }
                    }
                    // Drain any icon exception BEFORE the next JNI calls: a
                    // pending exception poisons the following call into
                    // returning NULL (the v1.0.510 lesson).
                    if (inst_env->ExceptionCheck()) {
                        std::fprintf(stderr,
                            "[macapk] icon rasterization threw (non-fatal, "
                            "install continues):\n");
                        inst_env->ExceptionDescribe();
                        inst_env->ExceptionClear();
                    }
                    if (has_native_libs) {
                        std::fprintf(stderr,
                            "[macapk] translating native libs at install "
                            "(one-time; ARM32 libs can take minutes)\n");
                        jmethodID xlat_m = inst_env->GetStaticMethodID(inst_boot_cls,
                            "translateNativeLibsForInstall",
                            "(Ljava/lang/String;Ljava/lang/String;)V");
                        if (xlat_m && !inst_env->ExceptionCheck()) {
                            jstring j_xlat_apk = inst_env->NewStringUTF(inst_java_apk.c_str());
                            jstring j_xlat_dir = inst_env->NewStringUTF(app.app_dir.c_str());
                            inst_env->CallStaticVoidMethod(inst_boot_cls, xlat_m,
                                j_xlat_apk, j_xlat_dir);
                        }
                    }
                }
                if (inst_env->ExceptionCheck()) {
                    std::fprintf(stderr,
                        "[macapk] install-time translation threw (non-fatal, "
                        "install continues; first launch translates lazily):\n");
                    inst_env->ExceptionDescribe();
                    inst_env->ExceptionClear();
                }
                // extract-only never enters the Cocoa run loop, so there is
                // nothing else for this throw-away VM to do.
                inst_vm->DestroyJavaVM();
            } else {
                std::fprintf(stderr,
                    "[macapk] WARN: framework/loader jar missing, skipping "
                    "install-time icon rasterization + native-lib "
                    "translation (%s)\n",
                    inst_fw_jar.c_str());
            }
        }
        // v1.0.1000: re-mirror AFTER the install-time rasterizer.
        //
        // MirrorInstallMetadata copies the two launcher-facing files
        // (meta.json, icon.png) from the split-layout CODE dir into
        // data/data/<dirName>, because that is where the installer and the
        // launcher's Installed-Apps scan read them. Its only other call sites
        // run EARLIER -- main.cpp:1677 (split-install finalize, before the
        // extract_only gate above) and apk_unpacker.cpp:2225 (the cache-hit
        // path, i.e. a LATER launch) -- so on a fresh install nothing mirrored
        // whatever Boot.extractIconOnly had just produced.
        //
        // Measured on YouTube (com.google.android.youtube 21.33.322,
        // 2026-08-19): the rasterizer correctly wrote a real 4392-byte
        // icon.png into .macapk/code/<pkg>/, data/data/<pkg>/ had none, and
        // InstallCore.swift's finalizeIdentity therefore read nil and stamped
        // macapk_default_icon.png -- the bundle's AppIcon.png came out
        // md5-identical to the default. The app declares
        // android:icon="@mipmap/ringo2_ic_launcher" with a mipmap-anydpi
        // (adaptive) entry, so it needs the rasterizer, which is exactly the
        // path whose output was being dropped.
        //
        // Unconditional is safe: the function is a no-op unless app_dir's
        // parent chain is <root>/.macapk/code, so a non-split or degraded
        // install pays nothing. Non-fatal by the same convention as the rest
        // of this block -- an install must never fail over an icon.
        // v1.0.2239: the app's anydpi (adaptive/vector) icon won, so
        // WriteAppMetadata kept its density raster aside rather than as
        // icon.png. Promote it only when the rasterizer above produced nothing
        // (a degenerate render is refused by Boot.writeLauncherIcon), so an icon
        // our Drawable pipeline cannot paint still shows the app's own legacy
        // raster instead of the generic placeholder.
        {
            std::error_code fb_ec;
            const fs::path fb = fs::path(app.app_dir) / macapk::kIconRasterFallback;
            if (!fs::exists(icon_png_path, fb_ec) && fs::exists(fb, fb_ec)) {
                fs::copy_file(fb, icon_png_path, fs::copy_options::overwrite_existing, fb_ec);
                std::fprintf(stderr,
                    "[macapk] no rendered launcher icon; using the app's density raster "
                    "(%s)\n", fb_ec ? "copy FAILED" : "fallback");
            }
        }
        macapk::MirrorInstallMetadata(app.app_dir);
        // Finished-stage marker for InstallCore.extract()'s sub-stage
        // progress (see `stage merged` in apk_unpacker.cpp). Covers the
        // install-time JVM work above -- icon rasterization + the native-lib
        // translation -- including the nothing-to-do case, which finishes the
        // stage trivially.
        std::fprintf(stderr, "[macapk] stage natives-translated\n");
        std::fprintf(stderr,
            "[macapk] --extract-only complete: %s\n", app.app_dir.c_str());
        // The one copy of the app the runtime keeps (also meta.json's
        // installedApk); the installer records it as the row's apkPath.
        if (!app.installed_apk.empty())
            std::fprintf(stderr, "[macapk] installed-apk %s\n", app.installed_apk.c_str());
        return 0;
    }

    // ---- 1a. Resolve orientation and size the Cocoa window. ----
    // Order:
    //   - explicit --width/--height  : honor literally (no swapping)
    //   - explicit --orientation P/L : pick a default size for that side
    //   - --orientation auto         : read AndroidManifest, default to portrait
    Orient resolved = cli.orient;
    const int pre_w = CocoaPhoneWidth(), pre_h = CocoaPhoneHeight();
    // AOSP window policy on the INSTALLED base.apk (the authoritative read).
    const bool policy_sized = aosp_window
        && SizeWindowByPolicy(app.apk_path, cli.android_root, "authoritative");
    if (resolved == Orient::AUTO && !policy_sized) {
        Orient hint = ScanManifestOrientation(app.manifest_path, app.apk_path);
        if (hint == Orient::AUTO) {
            // No screenOrientation found in the manifest. Most modern
            // game APKs don't declare it explicitly because they call
            // setRequestedOrientation() at runtime, so portrait is a
            // safer default than crashing the user's window into a
            // tiny vertical strip. Override with --orientation
            // landscape if the app turns out to be a landscape title.
            resolved = Orient::PORTRAIT;
            std::fprintf(stderr,
                "[macapk] orientation=auto: manifest didn't declare "
                "screenOrientation, defaulting to portrait\n");
        } else {
            resolved = hint;
            std::fprintf(stderr,
                "[macapk] orientation=auto -> %s (from manifest)\n",
                resolved == Orient::LANDSCAPE ? "landscape" : "portrait");
        }
    }
    if (policy_sized) {
        // Sized above; the legacy branches below must not re-shape it.
    } else if (cli.width > 0 && cli.height > 0) {
        // User pinned dimensions; just trust them.
        CocoaSetPhoneSize(cli.width, cli.height);
    } else {
        // No explicit size: use the biggest canvas the display
        // supports, shaped to the RESOLVED orientation (v1.0.345;
        // replaces the old small 640x360 / 360x640 defaults). This is
        // the runtime side of the launcher's "Auto" screen-size preset
        // -- it must live HERE, after the manifest read above, so that
        // --orientation auto still honors a landscape manifest (an
        // explicit size from the launcher would pin the shape).
        CocoaSetPhoneSizeMax(resolved == Orient::LANDSCAPE);
    }
    // Window-first boot (see "0c. WINDOW FIRST" above) already opened a window
    // from a best-effort orientation read of the APK zip. THIS block is the
    // authoritative answer, so correct the live window if the two disagree --
    // which they do for a split bundle, whose base manifest the early read
    // cannot reach. No-op when they agree, which is the common case.
    if (window_first &&
        (CocoaPhoneWidth() != pre_w || CocoaPhoneHeight() != pre_h)) {
        std::fprintf(stderr,
            "[macapk] window: reshaping placeholder %dx%d -> %dx%d "
            "(authoritative orientation)\n",
            pre_w, pre_h, CocoaPhoneWidth(), CocoaPhoneHeight());
        CocoaReshapePlaceholder();
    }

    // ---- 2. Boot HotSpot. ----
    JvmConfig jvm_cfg;
    jvm_cfg.classpath_entries.push_back(framework_jar.string());
    jvm_cfg.classpath_entries.push_back(loader_jar.string());
    // BouncyCastle provider jar, if present (see bcprov_jar above). Optional:
    // absent on a partial build just means BC-named crypto stays unavailable.
    if (fs::exists(bcprov_jar))
        jvm_cfg.classpath_entries.push_back(bcprov_jar.string());
    // java.base patch (docs/jetfix_2026-07-31.md). Currently supplies
    // java.text.SimpleDateFormat's pattern letter 'c' (standalone day of week),
    // which real Android accepts and vanilla OpenJDK rejects with
    // IllegalArgumentException. Not app-specific: Google's desugar_jdk_libs
    // constructs new SimpleDateFormat("ccc"/"cccc") ITSELF inside
    // DesugarDateTimeTextProviderHelper to back java.time.DayOfWeek text, so any
    // app bundling desugared java.time hits it. Must be --patch-module rather
    // than a framework-jar shadow: java.* can only be defined by the bootstrap
    // loader. Single-string form, like the --add-opens flags in jvm_loader.cpp.
    // Optional, exactly like bcprov above: absent on a partial build simply
    // means the unpatched JDK behaviour stands.
    // ⚠ AND SAY SO WHEN IT IS ABSENT. "Optional" used to mean SILENT, and that
    // cost the project weeks: java_base_patch was never copied into the .app at
    // all, so the SimpleDateFormat 'c' fix was live in the repo binary and gone
    // from every bundle the owner launched, with no log line to notice it by --
    // one of three "silent success-but-wrong path" bugs found on 2026-08-12.
    // The copy list was fixed, but a positive assertion HERE catches the next
    // omission from ANY producer (build_launcher.sh, the staged runtime under
    // ~/Library/Application Support/Mac.apk/runtime, a future packager),
    // which is the reason it lives at the consumer.
    if (fs::exists(java_base_patch_dir)) {
        jvm_cfg.extra_options.push_back(
            "--patch-module=java.base=" + java_base_patch_dir.string());
    } else {
        std::fprintf(stderr,
            "[macapk] WARN: no java.base patch at %s -- SimpleDateFormat's "
            "standalone 'c' pattern letter is UNPATCHED, so desugared "
            "java.time.DayOfWeek text will throw. This binary was packaged "
            "without lib/java_base_patch.\n",
            java_base_patch_dir.c_str());
    }
    // jdk.unsupported patch: libcore's sun.misc.Unsafe. On Android, Unsafe is a
    // PLATFORM class and app code obtains it with
    // Unsafe.class.getDeclaredMethod("getUnsafe").invoke(null) -- the documented
    // path used by HiddenApiBypass, Netty, Kryo, protobuf, RxJava, okio. On the
    // host JDK that call cannot work for guest code: getUnsafe() is
    // @CallerSensitive and resolves its caller with a stack walk that SKIPS
    // reflection frames (ART's counts them, so a device sees bootclasspath code
    // and admits the call), and the JDK additionally hides getUnsafe() from
    // getDeclaredMethod() once the class has initialized. Measured cost of not
    // having this: Aurora Store 4.8.4's Application.onCreate aborts on the
    // class-init trigger for its inlined HiddenApiBypass call and ~580
    // bytecodes of app setup never run. Must be --patch-module rather than a
    // framework-jar shadow -- the platform loader owns sun.misc and always wins
    // parent-first delegation. Optional exactly like the java.base patch above.
    // Loud when absent, for the same reason as java.base above.
    if (fs::exists(jdk_unsupported_patch_dir)) {
        jvm_cfg.extra_options.push_back(
            "--patch-module=jdk.unsupported=" + jdk_unsupported_patch_dir.string());
    } else {
        std::fprintf(stderr,
            "[macapk] WARN: no jdk.unsupported patch at %s -- sun.misc.Unsafe "
            "is the host JDK's, so any app reaching it through "
            "getDeclaredMethod(\"getUnsafe\") (HiddenApiBypass, Netty, Kryo, "
            "protobuf, RxJava, okio) will abort its class init. This binary "
            "was packaged without lib/jdk_unsupported_patch.\n",
            jdk_unsupported_patch_dir.c_str());
    }
    // Deliberately NOT adding app.app_jar to the system classpath:
    // Boot.java loads it through its own AppClassLoader so that the
    // findLibrary() override there can redirect System.loadLibrary
    // calls inside an APK's <clinit> to the stub dylib. If the app
    // jar were on the system classpath, parent-first delegation
    // would resolve the activity class via the system classloader
    // and our override would never run.
    jvm_cfg.verbose = cli.verbose;
    // Route JVM crash dumps (hs_err_pid<pid>.log) to a known location
    // instead of HotSpot's default (current working directory, which
    // for the runtime binary is wherever the launcher / shell was when
    // it spawned us -- often the project root or the APKs folder).
    // Stray hs_err files next to the APKs are noise; we want them
    // grouped with the other Mac.apk logs.
    //
    // Resolution: walk up from the runtime executable looking for a
    // sibling `logs/` directory (matches the launcher's resolution).
    // If we find one, use it. Otherwise fall back to
    // ~/Library/Logs/macapk/ (the macOS-conventional spot, also
    // the launcher's fallback). Create the directory on demand.
    {
        fs::path hs_logs_dir;
        // The path of argv[0] is in app.runtime_path; walk up to find
        // a `logs/` sibling.
        fs::path probe = fs::path(argv[0]);
        if (probe.is_relative()) probe = fs::current_path() / probe;
        probe = fs::weakly_canonical(probe).parent_path();
        for (int depth = 0; depth < 6 && !probe.empty(); ++depth) {
            fs::path candidate = probe / "logs";
            std::error_code ecp;
            // The VERSION + runtime/ markers are load-bearing, not caution.
            // A bare "does probe/logs exist" test walked up from the STAGED
            // runtime (~/Library/Application Support/Mac.apk/runtime/Resources/)
            // to ~/Library and matched ~/Library/Logs, because the filesystem
            // is case-insensitive so "logs" == "Logs". Every Dock-launched app
            // therefore aimed -XX:ErrorFile at ~/Library/Logs/ directly rather
            // than the ~/Library/Logs/macapk/ this block intends -- crash dumps
            // scattered loose among every other app's logs, which is exactly
            // the "stray hs_err files are noise" problem it exists to prevent.
            // Requiring a sibling VERSION file and runtime/ directory pins the
            // match to a real repo root. Found 2026-08-12 while giving
            // Dock launches a run log (launcher/AppStubMain.swift), whose
            // resolution deliberately mirrors this one so a JVM crash dump and
            // the stderr that led to it land in the same directory.
            if (fs::exists(candidate, ecp) && fs::is_directory(candidate, ecp)
                    && fs::exists(probe / "VERSION", ecp)
                    && fs::exists(probe / "runtime", ecp)) {
                hs_logs_dir = candidate;
                break;
            }
            fs::path parent = probe.parent_path();
            if (parent == probe) break;
            probe = parent;
        }
        if (hs_logs_dir.empty()) {
            const char* home = std::getenv("HOME");
            if (home && *home) {
                hs_logs_dir = fs::path(home) / "Library/Logs/macapk";
            }
        }
        if (!hs_logs_dir.empty()) {
            std::error_code ec_mkdir;
            fs::create_directories(hs_logs_dir, ec_mkdir);
            std::string opt = "-XX:ErrorFile=" + hs_logs_dir.string()
                + "/hs_err_pid%p.log";
            jvm_cfg.extra_options.push_back(opt);
        }
    }

    // Tell Java the resolved android root (the value the C++ side ended
    // up using, whether from --android-root, MACAPK_ANDROID_ROOT, or
    // the default). Boot.java reads this via System.getProperty to set
    // filesDir / cacheDir for SharedPreferences + openFileOutput so app
    // state lands in the persistent user dir, not in the volatile
    // runtime/build/var/apps/<pkg>/.
    if (!android.root.empty()) {
        jvm_cfg.extra_options.push_back(
            "-Dmacapk.android.root=" + android.root);
    }
    // The build's VERSION, for Java code that has to name the runtime it ran
    // on: the input recorder's header (InputRecorder), so a replay on another
    // build says so instead of silently diverging.
    jvm_cfg.extra_options.push_back(std::string("-Dmacapk.runtime.version=") + MACAPK_VERSION_STR);
    // v1.0.2187: the display density, decided once from the launch screen (320 on
    // Retina, 160 on a 1x monitor; CocoaDisplayDensityDpi). SystemProperties
    // answers ro.sf.lcd_density with it, which is where DisplayMetrics'
    // DENSITY_DEVICE / DENSITY_DEVICE_STABLE and every default metrics come from.
    jvm_cfg.extra_options.push_back(std::string("-Dmacapk.display.density=")
        + std::to_string(CocoaDisplayDensityDpi()));
    // v1.0.1647: what the window policy decided, so Boot takes the max aspect
    // from the decision (activity-level aware) instead of re-deriving it, and
    // logs what it tells the app next to what the policy said it would.
    jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.policy=")
        + (g_window_policy.sized ? "aosp" : "legacy"));
    if (g_window_policy.sized) {
        const winpolicy::WindowDecision& wd = g_window_policy.decision;
        char wbuf[64];
        std::snprintf(wbuf, sizeof wbuf, "%g", wd.max_aspect);
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.maxAspect=") + wbuf);
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.class=")
            + winpolicy::ClassName(wd.cls));
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.universal=")
            + (wd.universal ? "1" : "0"));
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.sizeCompat=")
            + (wd.size_compat ? "1" : "0"));
        // What the app will be told is derived by DisplayConfig from the
        // DELIVERED window, whose points are floored (window_policy.cpp: HCR
        // 1.8.1's 3341 px box is a 1670 pt window, 1670 dp, not AOSP's 1671),
        // so promise that, not the pixel-exact AOSP figure.
        const float wscale = g_window_policy.scale > 0 ? g_window_policy.scale : 2.0f;
        const float wdensity = CocoaDisplayDensity();
        const int dw = static_cast<int>(wd.box_w_pt * wscale / wdensity + 0.5f);
        const int dh = static_cast<int>(wd.box_h_pt * wscale / wdensity + 0.5f);
        std::snprintf(wbuf, sizeof wbuf, "%dx%d sw%d", dw, dh, std::min(dw, dh));
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.appSees=") + wbuf);
        // ...and, next to it, the pixel-exact figure Android's window manager
        // would report for the same box (HCR 1.8.1: 1671 vs our 1670;
        // portrait 1347 px: sw674 vs our 673), so Boot's line shows the
        // rounding gap instead of hiding it behind a MATCH that compares the
        // delivered window with itself.
        std::snprintf(wbuf, sizeof wbuf, "%dx%d sw%d", wd.screen_w_dp, wd.screen_h_dp, wd.sw_dp);
        jvm_cfg.extra_options.push_back(std::string("-Dmacapk.window.aospSees=") + wbuf);
    }

    // Tell Java the ORIGINAL input path (cli.apk), not the merged single-zip we
    // hand Boot.run as the effective apk. For a split bundle (.apkm/.xapk/.apks)
    // these differ: the merge flattens base.apk + splits into one zip and DROPS the
    // APK Signing Block in the process, so the merged apk reports no signature. The
    // signing identity lives only in the ORIGINAL bundle's inner base.apk, which
    // MacApkPackageManager.readApkSignatures reads via this property so
    // getPackageInfo(GET_SIGNATURES).signatures is the app's real, non-null cert --
    // what pairip's SignatureCheck.verifyIntegrity (and any signature-reading app)
    // needs. Absolute path so Java resolves it regardless of cwd.
    if (!cli.apk.empty()) {
        std::error_code ec_abs;
        fs::path abs_apk = fs::absolute(cli.apk, ec_abs);
        jvm_cfg.extra_options.push_back(
            "-Dmacapk.bundle.apk=" + (ec_abs ? cli.apk : abs_apk.string()));
    }

    // MACAPK_VMSPACE_PROBE=1 (default OFF, diagnostic only): report the sub-16 GiB
    // address-space budget AT PROCESS START, before JNI_CreateJavaVM maps the Java heap
    // and before any guest .so is loaded. This exists to answer ONE question:
    // libunity's arena is reserved late (at libunity load), by which time the low address
    // space is scattered -- a live Crossy Road measured 830-907 mapped regions and a
    // largest free hole of only 372 MiB, too small for the 512 MB pool reserves Unity's
    // MemoryManager asks for, so 9 of 16 land above the 16 GiB ceiling that Unity's
    // DynamicHeapAllocator requires and the scene never gets its memory. If the space is
    // materially LESS fragmented here at t=0, reserving the arena early would let it be
    // both larger and contiguous. If it is not, that redesign cannot help either.
    // Ref: docs/crossy-blank-blue-regression_2026-07-29.md sections 8c-bis / 8c-quinquies.
    if (const char* probe = std::getenv("MACAPK_VMSPACE_PROBE");
        probe && probe[0] == '1') {
        constexpr uint64_t kCeil = 0x400000000ull;   // 16 GiB
        uint64_t cursor = 0x100000000ull;            // above __PAGEZERO (low 4 GiB)
        uint64_t mapped = 0, freed = 0, best = 0, best_at = 0;
        int regions = 0;
        while (cursor < kCeil) {
            mach_vm_address_t a = cursor;
            mach_vm_size_t    sz = 0;
            natural_t         depth = 0;
            vm_region_submap_short_info_data_64_t info;
            mach_msg_type_number_t cnt = VM_REGION_SUBMAP_SHORT_INFO_COUNT_64;
            if (mach_vm_region_recurse(mach_task_self(), &a, &sz, &depth,
                                       reinterpret_cast<vm_region_recurse_info_t>(&info),
                                       &cnt) != KERN_SUCCESS) {
                if (cursor < kCeil) {                // everything above cursor is free
                    uint64_t g = kCeil - cursor;
                    freed += g;
                    if (g > best) { best = g; best_at = cursor; }
                }
                break;
            }
            if (a >= kCeil) {                        // free all the way to the ceiling
                uint64_t g = kCeil - cursor;
                freed += g;
                if (g > best) { best = g; best_at = cursor; }
                break;
            }
            ++regions;
            if (a > cursor) {
                uint64_t g = a - cursor;
                freed += g;
                if (g > best) { best = g; best_at = cursor; }
            }
            uint64_t next = static_cast<uint64_t>(a + sz);
            if (next > kCeil) next = kCeil;
            mapped += (next > a) ? (next - a) : 0;
            if (next <= cursor) break;
            cursor = next;
        }
        std::fprintf(stderr,
            "[vmspace-probe] t=0 (pre-JVM): free=%.2f GiB  mapped=%.2f GiB  "
            "largest_hole=%.2f GiB at 0x%llx  regions=%d  (below the 16 GiB ceiling; "
            "the low 4 GiB __PAGEZERO is excluded and unusable)\n",
            freed / 1073741824.0, mapped / 1073741824.0, best / 1073741824.0,
            (unsigned long long)best_at, regions);
        std::fflush(stderr);
    }

    JavaVM*  vm  = nullptr;
    JNIEnv*  env = nullptr;
    BootClock("JVM create begins");
    StartJvm(jvm_cfg, &vm, &env);
    BootClock("JVM created");

    RegisterAllNativeMethods(env);
    BootClock("JNI natives registered");

    // Hand the JavaVM to libmacapk-bionic-shim.dylib so the shim's
    // AAssetManager_open / __android_log_print / etc. can call back
    // into Java when a translated APK dylib invokes them. The shim is
    // also the dependency that translated dylibs link against, so it
    // gets dlopen'd a second time later by AppClassLoader; dyld
    // dedupes by path and our handle stays valid.
    //
    // Two layouts to support:
    //   - Dev tree: runtime binary and shim are both in runtime/build/bin/.
    //   - .app bundle: runtime is at Contents/Resources/macapk-runtime,
    //     shim is at Contents/bin/libmacapk-bionic-shim.dylib (one source
    //     of truth, matching what AppClassLoader passes as --shim-dir to
    //     the translator subprocess).
    {
        const std::array<fs::path, 2> candidates = {
            fs::path(bin_dir) / "libmacapk-bionic-shim.dylib",
            fs::path(bin_dir) / ".." / "bin" / "libmacapk-bionic-shim.dylib",
        };
        fs::path shim_path;
        for (const auto& c : candidates) {
            if (fs::exists(c)) { shim_path = c; break; }
        }
        if (!shim_path.empty()) {
            void* h = dlopen(shim_path.c_str(), RTLD_LAZY | RTLD_GLOBAL);
            if (h) {
                // Per-run log size cap + the liblog chatty tick (bionic_shim.cpp
                // __macapk_logcap_start; MACAPK_LOG_CAP_MB, default 64, 0 =
                // unlimited). First, so everything logged from here on is bounded:
                // a run log on a regular file rotates at the cap instead of growing
                // until the disk is full (Plague Inc spun at 22.9 MB/s, about 82 GB/h).
                if (auto logcap = reinterpret_cast<int(*)()>(
                        dlsym(h, "__macapk_logcap_start"))) {
                    logcap();
                }
                // Reap the synthesised /proc files ($TMPDIR/macapk_<pid>_*) that
                // earlier runs left behind. Nothing can remove them at exit (window
                // close is _exit, harnesses SIGKILL, and -Xrs leaves SIGTERM with no
                // Java handler), so the next launch does. Synchronous and before any
                // guest code on purpose: a reaper thread would change the thread
                // census the guest reads through /proc/self/task. The shim's load
                // constructor has already pinned TMPDIR. MACAPK_PROCFS_NO_PURGE=1
                // disables it. See docs/procfs-synth-tmp-lifetime_2026-09-24.md.
                if (auto reap = reinterpret_cast<size_t(*)()>(
                        dlsym(h, "__macapk_procfs_reap_now"))) {
                    const size_t n = reap();
                    if (n) {
                        std::fprintf(stderr,
                            "[macapk] procfs: reaped %zu stale /proc synth entr%s\n",
                            n, n == 1 ? "y" : "ies");
                    }
                }
                auto init_assets = reinterpret_cast<void(*)(JavaVM*)>(
                    dlsym(h, "__macapk_init_assets"));
                if (init_assets) {
                    init_assets(vm);
                } else {
                    std::fprintf(stderr,
                        "[macapk] shim missing __macapk_init_assets: %s\n",
                        dlerror());
                }
                // v1.0.484 (item 3a): gated end-to-end check of the NDK AMedia*
                // repoint through the SHIPPED shim dylib + the real macapk_md_*
                // bridge in-process. Off by default; MACAPK_AMEDIA_SELFTEST=1
                // decodes MACAPK_AMEDIA_SELFTEST_FILE (default /tmp/mdtest.m4a)
                // via the AMediaExtractor+AMediaCodec C API and logs frames/RMS.
                if (std::getenv("MACAPK_AMEDIA_SELFTEST")) {
                    if (auto amst = reinterpret_cast<long long(*)(const char*)>(
                            dlsym(h, "__macapk_arm64_amedia_selftest"))) {
                        const char* mf = std::getenv("MACAPK_AMEDIA_SELFTEST_FILE");
                        long long r = amst(mf && *mf ? mf : "/tmp/mdtest.m4a");
                        std::fprintf(stderr, "[macapk] AMEDIA_SELFTEST -> %lld frames (%s)\n",
                                     r, r > 0 ? "PASS" : "FAIL");
                    } else {
                        std::fprintf(stderr, "[macapk] AMEDIA_SELFTEST: symbol missing: %s\n", dlerror());
                    }
                }
                // v1.0.441: install the JVMTI NativeMethodBind ABI re-marshal so
                // name-based (Java_*) guest natives -- which bypass the v1.0.392
                // RegisterNatives wrap -- get the Apple->AAPCS64 stack repack too.
                // Self-gated by MACAPK_NMB (no-op unless set), so this is inert for
                // the shipped build until proven. Done right after init_assets so
                // the JavaVM + shim are both live; fires for binds that happen as
                // the app's guest .so natives resolve.
                if (auto install_nmb = reinterpret_cast<void(*)(JavaVM*)>(
                        dlsym(h, "__macapk_install_jni_remarshal"))) {
                    install_nmb(vm);
                }
                // v1.0.489: gated JVMTI Exception trace (MACAPK_EXC_TRACE). Surfaces
                // SWALLOWED exceptions (every throw, caught or not) so silent "frozen,
                // no-log" app-startup aborts (e.g. Reddit's anon OAuth token-fetch
                // chain) reveal the missing framework surface. No-op unless the env is
                // set; do NOT combine with MACAPK_NMB (SetEventCallbacks is exclusive).
                if (auto install_exc = reinterpret_cast<void(*)(JavaVM*)>(
                        dlsym(h, "__macapk_install_exception_trace"))) {
                    install_exc(vm);
                }
                // (The Bionic-va_list JNI bridge install happens later,
                // from inside arm64_native.cpp::Image::load -- by then
                // the JVM's startup class loading + JFR registration are
                // done, so patching the JNI vtable can't race the
                // JfrTraceId::assign chain that early JVM class loads
                // run. See arm64_native.cpp for the install call site.)
                // (Previously: optional JNI-vtable tracing via
                // MACAPK_TRACE_JNI. That patched the JVM's
                // process-wide JNINativeInterface vtable, which
                // corrupted JFR class-tracking and crashed the VM
                // in JfrTraceId::assign. Diagnostic shifted to the
                // shim's `__macapk_arm64__ZN7_JNIEnv*` overrides
                // -- those intercept libOpenGD's PLT calls without
                // touching JVM internals.)
                // Tell the shim where the unpacked APK lives so its
                // asset-path remap stops using hardcoded /Users/.../
                // runtime/build/... strings. Works for both the dev
                // tree and the .app bundle.
                auto set_apkt = reinterpret_cast<void(*)(const char*)>(
                    dlsym(h, "__macapk_set_apkt_dir"));
                if (set_apkt) {
                    fs::path apkt = fs::path(app.app_dir) / "_apkt";
                    set_apkt(apkt.c_str());
                } else {
                    std::fprintf(stderr,
                        "[macapk] shim missing __macapk_set_apkt_dir: %s\n",
                        dlerror());
                }
                // Also hand the shim the installed base.apk path. Two live
                // consumers, both in bionic_shim.cpp: the remap's `.apk`
                // read fallback (a guest opening an .apk path that resolves
                // to nothing is served the running app's own base.apk) and
                // the native asset-zip index nz_ensure_set (base.apk plus
                // the split_*.apk beside it). It is published here, at boot,
                // before any guest code runs.
                // (This comment used to say the point was a synthetic call
                // of cocos2d-x's nativeSetPaths JNI export to bootstrap
                // "s_pZipFile". That call is retired behind
                // MACAPK_COCOS_SETPATHS_FROM_APP -- the app's own
                // Cocos2dxActivity.setPackageName delivers the path, as on a
                // device -- and there was never an s_pZipFile. See
                // docs/cocos-nativesetpaths-retired_2026-09-27.md.)
                auto set_apk = reinterpret_cast<void(*)(const char*)>(
                    dlsym(h, "__macapk_set_runtime_apk_path"));
                if (set_apk && !app.apk_path.empty()) {
                    set_apk(app.apk_path.c_str());
                } else if (!set_apk) {
                    std::fprintf(stderr,
                        "[macapk] shim missing __macapk_set_runtime_apk_path: %s\n",
                        dlerror());
                }
                // The INSTALL dir (v1.0.1589): the directory the installed
                // base.apk sits in, which is where the native libraries live
                // (Phase 4). Pushed rather than recomposed in the shim from
                // root + package + nonce, so a Stage-6 wrapper's
                // "lib/<isa>/lib<name>.so" fragment resolves against the very
                // directory the unpacker wrote, whatever the shim thinks the
                // package name is. Empty (never pushed) when the app runs
                // from its source apk; the shim then recomposes.
                if (!app.installed_apk.empty()) {
                    auto set_inst = reinterpret_cast<void(*)(const char*)>(
                        dlsym(h, "__macapk_set_install_dir"));
                    if (set_inst)
                        set_inst(fs::path(app.installed_apk).parent_path().string().c_str());
                }
                // Tell the shim where the user-visible android root
                // is. The shim's path-remap pipeline rewrites Android-
                // style absolute paths (e.g. "/data/data/<pkg>/foo")
                // to point under this directory, where the runtime has
                // already symlinked the APK's assets and where save
                // files persist between runs.
                auto set_root = reinterpret_cast<void(*)(const char*)>(
                    dlsym(h, "__macapk_set_android_root"));
                if (set_root) {
                    set_root(android_root.c_str());
                }
                // And the current package name (parsed from the
                // manifest, not the cache-dir name), so the shim's
                // bare-relative-name resolver knows which
                // /data/data/<pkg>/ subdir to fall back to. This
                // MUST match the dir we created in SetupAndroidRoot.
                auto set_pkg = reinterpret_cast<void(*)(const char*)>(
                    dlsym(h, "__macapk_set_package_name"));
                if (set_pkg) {
                    set_pkg(android_pkg.c_str());
                }
                // Compute + push the per-version data subdir so the
                // shim's fopen path-remap uses
                // "<root>/data/data/<pkg>_<ver>/" from the very first
                // open call -- BEFORE Boot.java would otherwise push
                // the same value via Cocoa_setDataSubdir later. This
                // makes early pre-JVM fopens (apkt extraction, the
                // launcher's setup log writes) hit the same on-disk
                // tree the JVM-side code uses.
                //
                // Uses the SAME fallback chain as SetupAndroidRoot:
                //   1. manifest android:versionName / platformBuildVersionName
                //   2. filename-derived suffix in UnpackedApp.package_name
                // so we always agree on a single dir name.
                auto set_subdir = reinterpret_cast<void(*)(const char*)>(
                    dlsym(h, "__macapk_set_data_subdir"));
                if (set_subdir) {
                    // basename(app_dir), which is EXACTLY the value
                    // Boot.java derives later (dataSubdir =
                    // new File(appDir).getName()): the version-qualified
                    // name shared by the code and data dirs. Re-deriving
                    // the version here (the old code) could disagree
                    // with Boot whenever the derivation changed and
                    // point early native fopens at a data dir the Java
                    // side never uses.
                    std::string subdir =
                        fs::path(app.app_dir).filename().string();
                    set_subdir(subdir.c_str());
                }
            } else {
                std::fprintf(stderr,
                    "[macapk] cannot dlopen %s: %s\n",
                    shim_path.c_str(), dlerror());
            }
        } else {
            std::fprintf(stderr,
                "[macapk] libmacapk-bionic-shim.dylib not found next to "
                "the runtime or under ../bin -- translator output will fail "
                "to link\n");
        }
    }

    // ---- 3. Hand off to the Java loader. ----
    BootClock("bionic shim loaded and configured");
    jclass boot = env->FindClass("com/macapk/loader/Boot");
    CheckException(env, "FindClass com.macapk.loader.Boot");
    if (!boot) {
        std::fprintf(stderr, "[macapk] Boot class missing.\n");
        return 1;
    }
    jmethodID boot_main = env->GetStaticMethodID(
        boot, "run",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    CheckException(env, "GetStaticMethodID com.macapk.loader.Boot.run");

    // Hand Boot.run the INSTALLED base.apk (v1.0.1586: no merged single-zip any
    // more). The Java framework derives ApplicationInfo.sourceDir /
    // getPackageCodePath() / AssetManager(apkPath) from it, and finds the
    // installed split_*.apk beside it itself (com.macapk.loader.InstalledSplits
    // -- the same sibling rule Main.readDexes uses), mounting each one the way
    // ResourcesManager does on a device. app.apk_path is set by the unpacker on
    // both the cold and the warm path; cli.apk is only the fallback for a run
    // that never unpacked. dataSubdir is derived from appDir (Boot.java:254),
    // NOT this filename, so per-version data isolation is unaffected.
    std::string java_apk = (!app.apk_path.empty() && fs::exists(app.apk_path))
                               ? app.apk_path : cli.apk;
    jstring j_apk      = env->NewStringUTF(java_apk.c_str());
    jstring j_app_jar  = env->NewStringUTF(app.app_jar.c_str());
    jstring j_app_dir  = env->NewStringUTF(app.app_dir.c_str());
    jstring j_manifest = env->NewStringUTF(app.manifest_path.c_str());
    env->CallStaticVoidMethod(boot, boot_main, j_apk, j_app_jar, j_app_dir, j_manifest);
    CheckException(env, "Boot.run");

    // Boot.run is responsible for actually entering the runloop (it
    // calls NativeCocoa.runLoop() once it has the activity up). When
    // it returns, the runloop is over and we tear the VM down.
    vm->DestroyJavaVM();
    return 0;
}
