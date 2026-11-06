// main.cpp -- macapk-translator CLI.
//
// Pipeline:
//   input.so (ARM32 ELF or AArch64 ELF)
//     -> peek ELF e_ident[4] (class) to dispatch:
//        ELFCLASS32 -> elf_reader + arm32_lifter   (lift IR -> arm64 dylib)
//        ELFCLASS64 -> elf64_reader + arm64_pack   (embed verbatim,
//                                                   runtime-loaded by
//                                                   libmacapk-bionic-shim)
//     -> output Mach-O dylib at --out
//
// Apple Silicon macOS is arm64, so arm64 Android .so files don't need
// instruction translation -- the original .text bytes execute as-is.
// The arm64 path embeds the ELF in a tiny Mach-O wrapper that loads
// + relocates + runs init_array at dlopen time. The arm32 path
// continues to go through the per-instruction lifter because there
// is no arm32 hardware on Apple Silicon to fall back to.
//
// Run via:
//   macapk-translator --in <path> --out <path> [--keep-ir] [--clang <path>]

#include "macapk/arm32_lifter.h"
#include "macapk/arm32_discovery.h"
#include "arm32rc/arm32rc_driver.h"
#include "macapk/arm64_pack.h"
#include "macapk/elf_reader.h"
#include "macapk/elf64_reader.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <future>
#include <iostream>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <capstone/capstone.h>  // cocos_ccimage_layout: read CCImage's own ctor
#include <dlfcn.h>           // dlsym(RTLD_DEFAULT): does a bare import name
                             // actually resolve in the runtime namespace?
#include <stdlib.h>          // realpath
#include <unistd.h>          // sysconf(_SC_NPROCESSORS_ONLN)
#include <fcntl.h>           // open() for the .ll mmap path
#include <sys/mman.h>        // mmap() for streaming large IR into splitter
#include <sys/stat.h>        // fstat() for .ll size measurement
#include <spawn.h>           // posix_spawn -- thread-safe alternative
                             // to system() for parallel chunk compile
#include <sys/wait.h>        // waitpid() to harvest spawned children
#include <dirent.h>          // opendir/readdir -- cross-guest-library
                             // sibling .so scan (see
                             // docs/cross-guest-library-linking_2026-07-28.md)
#include <libkern/OSByteOrder.h>  // fat header is big-endian
#include <mach-o/fat.h>      // MACAPK_FIRSTLAUNCH_NM_INPROC: in-process
#include <mach-o/loader.h>   // LC_SYMTAB read of the runtime namespace
#include <mach-o/nlist.h>    // (replaces a popen of nm per ARM32 lib)

extern char** environ;       // inherited for the spawned child

namespace {

void usage(const char* exe) {
    std::fprintf(stderr,
        "usage: %s --in <input.so> --out <output.dylib> "
        "[--keep-ir]\n"
        "       [--emit-discovery <path>|auto]   ARM32 discovery sidecar\n"
        "       %s --print-runtime-exports <dir>   (diagnostic: the import "
        "resolver's runtime-namespace set)\n",
        exe, exe);
}

// Where to write the ARM32 discovery sidecar (`.disc.tsv`), or empty for
// "do not write one". Set by --emit-discovery; MACAPK_DISCOVERY_SIDECAR
// is the equivalent for callers that fork the translator and cannot add a
// flag (runtime/src/apk_unpacker.cpp builds the argv itself). The literal
// values "1" and "auto" mean `<output-dylib>.disc.tsv`, which is the name
// debug/arm32_sidecar.py::sidecar_path_for looks for.
std::string g_discovery_sidecar;

// Run a shell command and return its exit status. Uses posix_spawn
// rather than std::system because macOS's libc system() takes a
// process-global lock for the duration of the call (per `man 3
// system`: "Two threads using system() concurrently will not work").
// In the chunked-compile path multiple translator threads invoke
// run_cmd in parallel; with system() the second thread blocks until
// the first finishes, which serialises the per-chunk clang
// invocations and defeats the whole point of MACAPK_PARALLEL_CHUNKS.
//
// posix_spawn is thread-safe and non-blocking on the spawn itself; we
// then waitpid on the returned child PID, which is also thread-safe
// (each thread waits on its own child).
//
// We still want shell semantics (operators like &&, redirection, etc.)
// for the existing call sites that build a single command string, so
// the spawn invokes `/bin/sh -c <cmd>` rather than parsing argv. The
// performance hit of a one-shot sh fork is negligible compared to
// the clang/llvm-as runtime that follows.
[[maybe_unused]] int run_cmd(const std::string& cmd, bool verbose) {
    if (verbose) std::fprintf(stderr, "[translator] $ %s\n", cmd.c_str());
    const char* argv[] = {"/bin/sh", "-c", cmd.c_str(), nullptr};
    pid_t pid;
    int rc = posix_spawn(&pid, "/bin/sh", nullptr, nullptr,
                          const_cast<char* const*>(argv), environ);
    if (rc != 0) {
        std::fprintf(stderr,
            "[translator] posix_spawn failed: %s\n",
            std::strerror(rc));
        return -1;
    }
    int status = 0;
    if (waitpid(pid, &status, 0) == -1) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

// Imports whose ARM32 ABI takes/returns 32-bit guest pointers but
// whose libSystem counterparts take/return real 64-bit darwin
// pointers. Calling libSystem directly with our 32-bit values
// truncates the pointer and segfaults on first dereference. Each
// name in this set gets routed through the matching
// __macapk_shim_<name>() function in the bionic shim, which
// converts addresses against guest_base before forwarding to
// libSystem (or implements the function entirely for cases like
// malloc that need to live in guest memory).
static const std::unordered_set<std::string> kPointerAwareImports = {
    "memcpy", "memmove", "memset", "memcmp", "memchr",
    // _FORTIFY_SOURCE variants (extra trailing destlen arg, ignored by the
    // shim). Routed to the same pointer-translating shims as the plain ones;
    // without this they bind raw to libSystem and segfault on guest vaddrs.
    "__memcpy_chk", "__memmove_chk", "__memset_chk", "__strlen_chk",
    "strlen", "strcmp", "strncmp", "strcpy", "strncpy", "strcat",
    "strchr", "strrchr", "strstr", "strdup",
    // v1.0.808: the rest of the mem*/str* family. Every one of these takes at
    // least one POINTER argument, so an ARM32 guest calling it must go through
    // a translating shim; binding raw to libSystem hands Darwin a 32-bit guest
    // vaddr as a 64-bit host pointer and it segfaults on first dereference.
    // `memmem` is the confirmed Unity/il2cpp blocker -- libmain.so's
    // statically-linked NDK cpu-features.c calls it while parsing
    // /proc/cpuinfo, faulted at memmem+0x78 in libsystem_c.dylib with
    // r0=guest-heap-ptr r1=0x10. Our SEGV-recovery net caught it and returned
    // 0, which made NativeLoader.load() return false, which is the SOLE gate
    // on Unity's "Your hardware does not support this application."
    // The other 12 were found by a fleet-wide audit of the same gap shape.
    // Each has a matching __macapk_shim_<name> (verified present via `nm -gU`
    // before landing this list -- a name here with no shim falls through
    // safely, but silently, so do not add speculatively).
    // Still MISSING and deliberately not listed: wcslen, wmemcpy, wmemcmp,
    // wmemset, swprintf -- found by the same audit, shims not yet written.
    "memmem", "memrchr",
    // v1.0.819: the "bare-resolve with the WRONG ABI" family. Unlike a
    // missing symbol, these BIND SUCCESSFULLY -- Darwin exports a real `pipe`,
    // `newlocale`, `uselocale` etc. -- so the guest's import resolves, the host
    // function is handed a 32-bit guest vaddr as a 64-bit pointer, and it
    // faults. NOTHING warns, because from the resolver's point of view nothing
    // was missing. That is why this class outlived `memmem`: a live SEGV inside
    // `__pipe`, called straight from `Java_..._initializeNativeCode`, is the
    // proximate cause of Smash Hit's UnsatisfiedLinkError.
    //
    // The locale family is here because it is genuinely hot, not speculatively:
    // `uselocale` has 113 BL sites in libsmashhit.so and `newlocale` 76. Bionic
    // and Darwin also disagree bit-for-bit on the `LC_*_MASK` values, so the
    // shims translate those rather than passing them through.
    // Every name below was verified to have a real `__macapk_shim_*` export
    // before being listed -- an unlisted-but-shimmed name is inert, and a
    // listed-but-unshimmed name falls through SILENTLY.
    "pipe", "pipe2",
    // v1.0.820: these shims EXISTED but were in no set, so the resolver never
    // routed to them -- the guest bound to the bare host symbol instead and the
    // shim was dead code. `dladdr` is the proof: lane N implemented it, and
    // Smash Hit kept SEGVing at the identical address INSIDE dyld's own dladdr,
    // because the import never reached this table.
    //
    // Membership here means "use OUR shim", not literally "takes a pointer" --
    // several below (gettid, eventfd, epoll_create1, sched_getscheduler,
    // __ctype_get_mb_cur_max) take no pointer at all but still need our version
    // for Bionic-vs-Darwin semantics. A shim with no entry here is INERT; an
    // entry here with no shim falls through SILENTLY. Both directions are now
    // checked mechanically -- see the parity check in the commit message.
    //
    // Deliberately NOT added: cocos_init_png_data, fullPathFromRelativePath and
    // dynamic_cast, which are internal helpers rather than guest imports.
    // (fullPathFromRelativePath is reached only under MACAPK_COCOS_OWN_FULLPATH=0
    // or MACAPK_COCOS_FORCE_FOPEN=1 since 2026-09-26; see cocos_own_fullpath_on.
    // dynamic_cast only under MACAPK_ARM32_GUEST_DYNAMIC_CAST=0, same date; see
    // arm32_guest_dynamic_cast_on.)
    "dladdr",
    // v1.0.821: the ARM32 twins built by lanes SHIMGEN and INFRA. Wired in the
    // SAME commit that adds them, because v1.0.820 established that a shim with
    // no entry here is dead code -- all 36 of these were inert on arrival.
    //
    // `signal`/`sigaction` are the important ones to wire even though the shim
    // deliberately REFUSES to deliver a real guest function-pointer handler:
    // today they bind RAW to the host, which is handed a 32-bit ARM32 code
    // address as a handler and hard-crashes on delivery. A loud refusal is
    // strictly better than that, and it is honest about what we cannot do yet.
    //
    // `dl_unwind_find_exidx` is what ARM32 C++ exception unwinding needs to find
    // a module's .ARM.exidx table; arm64 has no twin because AArch64 does not
    // use EHABI at all.
    "AConfiguration_delete", "AConfiguration_fromAssetManager", "AConfiguration_getCountry", "AConfiguration_getLanguage",
    "AConfiguration_new", "__cmsg_nxthdr", "__pread_chk", "__pthread_cleanup_pop",
    "__pthread_cleanup_push", "__pwrite_chk", "__system_property_find", "__system_property_read",
    // v1.0.1169: __system_property_foreach. Added in the SAME commit as its
    // shim, per the v1.0.820 lesson recorded above -- a shim with no entry
    // here is inert, which is exactly how the property family got half-wired
    // before. Both the ARM32 shim and the arm64 twin exist; see
    // bionic_shim_arm32.cpp and bionic_shim.cpp.
    "__system_property_foreach",
    "dl_unwind_find_exidx", "epoll_ctl", "epoll_wait", "ftruncate64",
    "inotify_add_watch", "inotify_init", "lseek64", "memalign",
    "mmap64", "pread64", "pthread_condattr_setclock", "pthread_getattr_np",
    "sched_getaffinity", "sched_getparam", "sched_setaffinity", "sem_timedwait",
    "sigaction", "sigaddset", "sigdelset", "sigemptyset",
    "sigfillset", "sigismember", "signal", "slCreateEngine",
    "ALooper_addFd", "ALooper_pollOnce", "ALooper_removeFd",
    "__FD_CLR_chk", "__FD_ISSET_chk", "__FD_SET_chk",
    "__assert2", "__ctype_get_mb_cur_max", "__fgets_chk", "__open_2",
    "__read_chk", "__write_chk", "__strncpy_chk2", "__system_property_get",
    "android_set_abort_message",
    "epoll_create1", "eventfd", "gettid", "prctl", "sched_getscheduler", "syscall",
    "newlocale", "uselocale", "freelocale",
    "strcoll_l", "strxfrm_l", "wcscoll_l", "wcsxfrm_l",
    "strftime_l", "strtoll_l", "strtoull_l", "strtold_l",
    "iswalpha_l", "iswcntrl_l", "iswdigit_l", "iswlower_l", "iswprint_l",
    "iswpunct_l", "iswspace_l", "iswupper_l", "iswxdigit_l", "iswblank_l",
    "towlower_l", "towupper_l",
    "__strchr_chk",
    "__cxa_thread_atexit_impl",
    "strncat", "strcasecmp", "strncasecmp", "strnlen", "strndup",
    "strpbrk", "strspn", "strcspn", "strtok", "strtok_r", "strlcpy",
    "malloc", "calloc", "realloc", "free",
    // C++ operator new/delete (intra-module libc++; elf_reader rewrites every
    // `_Znw*`/`_Zna*` to `_Znwj` and `_Zdl*`/`_Zda*` to `_ZdlPv`). See the
    // __macapk_shim__Znwj/_ZdlPv shims.
    "_Znwj", "_ZdlPv",
    // v1.0.833: the ARRAY forms and the size_t-is-64-bit spellings. These were
    // believed to be collapsed to "_Znwj" before resolution by elf_reader.cpp,
    // but that rewrite only covers a .rel.plt JUMP_SLOT naming a DEFINED-LOCAL
    // symbol; a genuine UNDEFINED import keeps its own name. Bouncy's
    // libgdx-box2d.so imports "_Znaj" undefined, and without this entry it
    // resolved NOWHERE -> panic stub -> every new[] returned NULL.
    "_Znaj", "_Znam", "_Znwm", "_ZdaPv",
    "sprintf", "snprintf", "printf", "fprintf", "puts",
    // pthread family. Each takes guest-address pthread_X_t* args
    // that we map to host objects via a guest_addr -> host_obj
    // table inside the shim (Bionic and macOS pthread types differ
    // in size, so we can't reuse the lifted memory directly).
    "pthread_mutex_init", "pthread_mutex_destroy",
    "pthread_mutex_lock", "pthread_mutex_unlock", "pthread_mutex_trylock",
    "pthread_cond_init", "pthread_cond_destroy",
    "pthread_cond_signal", "pthread_cond_broadcast", "pthread_cond_wait",
    // pthread_cond_timedwait takes a guest `struct timespec*`, and
    // pthread_condattr_getclock a guest `clockid_t*`, so both must be
    // pointer-aware. Neither had an ARM32 shim before 2026-08-27; an unshimmed
    // pthread_cond_timedwait resolves to a panic stub that RETURNS 0, i.e. it
    // reports "signalled" on every call and turns any timed wait into a
    // busy-spin. (condattr_setclock is already listed above at :202 -- its shim
    // lives in bionic_shim_arm32.cpp.)
    "pthread_cond_timedwait", "pthread_condattr_getclock",
    "pthread_key_create", "pthread_key_delete",
    "pthread_setspecific", "pthread_getspecific",
    "pthread_self",
    "pthread_create", "pthread_join", "pthread_detach",
    "pthread_exit", "pthread_once",
    "pthread_mutexattr_init", "pthread_mutexattr_destroy",
    "pthread_mutexattr_settype",
    "pthread_condattr_init",  "pthread_condattr_destroy",
    "pthread_attr_init",      "pthread_attr_destroy",
    "pthread_attr_setdetachstate", "pthread_attr_setstacksize",
    // POSIX unnamed semaphores. macOS sem_init returns ENOSYS for
    // !pshared, so forwarding directly to libSystem makes Bionic-
    // compiled code see init failures and refuse to spawn workers.
    // HCR's libgame.so uses sem_post/sem_wait for its async asset-
    // loader queue: without working semaphores the AssetLoader's
    // m_pChildren never populates and the scene draws only glClear.
    // The shim implements counter+mutex+condvar semantics directly so
    // the lifted code sees a real-looking sem_t* (the guest vaddr is
    // remembered as a key into a SimSemaphore registry).
    "sem_init", "sem_destroy",
    "sem_post", "sem_wait", "sem_trywait", "sem_getvalue",
    // stdio family. FILE* is an opaque host pointer; we register it
    // with the JNI handle table so the lifted code's 32-bit "FILE*"
    // value resolves back to the real one.
    "fopen", "fclose", "fread", "fwrite",
    "fseek", "ftell", "feof", "ferror", "fflush",
    "fgets", "fputs", "fileno",
    // Additional stdio entries HCR's libpng pulls in. These all
    // take a FILE* as one of their args, and our FILE handle is a
    // 32-bit guest token (not a real host pointer). Without shims,
    // libSystem's getc/putc/setvbuf/fflush call flockfile() on the
    // raw token and SEGV at offset 0x110 of "FILE *NULL". Symptom is
    // a SEGV in flockfile right after libpng's PNG decode error
    // path tries to ungetc a byte during chunk parsing.
    "getc", "putc", "fputc", "fgetc", "ungetc",
    "setvbuf", "setbuf", "rewind", "clearerr",
    // POSIX file APIs. HCR's libgame.so uses raw open/read/stat/mmap
    // (in addition to fopen) -- HCR's CCFileUtils opens the APK via
    // open() rather than fopen() before walking it with zlib unz_*.
    // Without these shims libSystem's open() sees a guest 32-bit
    // vaddr where it expects a host pointer and the open silently
    // fails, leaving CCSprite::create("menu-bg.png") returning NULL
    // (sprite invisible -- this is the visible "black screen" symptom
    // for the HCR loading scene).
    "open", "read", "lseek", "close",
    "stat", "lstat", "fstat", "access",
    "mmap", "munmap",
    // POSIX path/IO that take a guest-pointer path or buffer arg and/or
    // a pointer-returning result. Bound directly to libSystem they get a
    // raw 32-bit guest vaddr as a host pointer (low-address SEGV) and the
    // path APIs miss the Android-root remap so saves land in the wrong
    // place / fail. Each routes through __macapk_shim_<name> which adds
    // guest_base (gptr) and remaps paths via macapk_arm64_remap_path.
    // __errno returns a guest-pointer to a guest-memory errno slot the
    // app can dereference (a raw host int* truncated to i32 SEGVs).
    "mkdir", "remove", "rename", "getcwd",
    "write", "writev", "poll", "ioctl", "fdopen",
    "__errno",
    // dlopen/dlsym/dlclose take guest-pointer path/name args and, for the lifted
    // path, must bridge dlopen("libandroid.so")+dlsym("AAsset*") to our
    // AAssetManager shims (libSystem has no libandroid.so -> NULL -> assets never
    // load -> blank cocos2d-x v2 menus). __macapk_shim_dlsym returns guest
    // trampolines; non-libandroid requests keep the prior libSystem behavior.
    "dlopen", "dlsym", "dlclose",
    // #105: getauxval + dl_iterate_phdr for the ARM32 xDL/shadowhook module lookup (see
    // macapk_arm32_synth_modules in bionic_shim.cpp).
    "getauxval", "dl_iterate_phdr",
    "gzopen", "gzclose",
    // setjmp/longjmp -- libpng uses these for error handling. Bionic's
    // arm jmp_buf is 64 WORDS (256 bytes, _JBLEN 64 in libc/include/
    // setjmp.h); macOS arm64's is ~336 bytes. With MACAPK_ARM32_SIGJMP=0
    // (the old path) this list binds the fake pair instead: setjmp always
    // returns 0 (so the error branch is never taken) and longjmp aborts
    // the whole invoke_safe call, corrupting caller state rather than
    // guest memory. With MACAPK_ARM32_SIGJMP on (the default), these
    // names bind the real *_bionic twins (see arm32_gap_shim below),
    // which write Bionic's exact word layout into the guest's own
    // 256-byte jmp_buf and let longjmp return through it for real.
    "setjmp", "longjmp", "_setjmp", "_longjmp",
    "siglongjmp",
    // zlib. Bionic z_stream is 56 bytes (32-bit pointers + uLong);
    // macOS arm64 z_stream is ~112 bytes (64-bit pointers + 8-byte
    // uLong). Forwarding directly to libSystem's zlib makes inflate()
    // walk past the guest's 56-byte slot and read/write garbage.
    // libpng then sees "Unknown zlib error" and aborts -- our exact
    // observed failure for menu-bg.png decode. The shims keep a host
    // z_stream per guest stream pointer and sync the few fields
    // libpng cares about (next_in, avail_in, next_out, avail_out,
    // total_in, total_out, adler, msg) on each call.
    "inflateInit_", "inflateInit2_", "inflate", "inflateEnd",
    "inflateReset", "inflateSync", "inflateCopy",
    "deflateInit_", "deflateInit2_", "deflate", "deflateEnd",
    "deflateReset", "deflateParams",
    "crc32", "uncompress",
    // Android's logcat family. Each takes guest-pointer tag/fmt args
    // that need guest_base translation; without this, libsystem's
    // strlen (called from fprintf inside the printf-family code)
    // dereferences raw 32-bit ARM vaddrs as host pointers and
    // crashes at low addresses (typically ~0x100000-range).
    "__android_log_print", "__android_log_write",
    "__android_log_vprint", "__android_log_assert",
    // Locale / classification family. cocos2d-x's libstdc++ static
    // initializers call setlocale/wctype/wctrans on guest .rodata
    // string literals during the first ctor (init_array[0]). Without
    // these shims, libsystem's setlocale and wctype dereference the
    // raw ARM vaddrs and crash inside _platform_strcmp / strcmp.
    "setlocale", "wctype", "wctrans",
    "getenv", "strerror", "perror",
    "strcoll", "strxfrm",
    "atoi", "atol", "atof",
    "strtol", "strtoul", "strtod",
    // Process-control shims. abort() is the bottom of the lifted
    // libstdc++ exception path -- when a throw escapes a lifted
    // frame and the ARM unwinder tables can't reach a handler the
    // chain bottoms out in __verbose_terminate_handler() -> abort().
    // Without intercept the whole runtime dies; our shim siglongjmps
    // back to invoke_safe so the JNI thunk just returns 0 and the
    // next JNI call gets a fresh attempt. exit/_Exit/raise get
    // similar treatment for the same reason.
    "abort", "exit", "_Exit", "raise",
    // C++ Itanium ABI: thread-safe function-local-static init guards.
    // Lifted code emits BL to libstdc++'s __cxa_guard_acquire/release
    // around `static T x = ...;` initializers. The pointer arg is a
    // guest-memory vaddr referencing a guard object in .bss / .data;
    // the host runtime would interpret it as a host pointer and write
    // to whatever happens to be at that low address (often a no-op,
    // sometimes a crash, never the right place). The shim translates
    // the pointer through guest_base before doing the standard
    // bit-test/bit-set dance. Without this, every cocos2d-x singleton
    // (CCEGLView::sharedOpenGLView, CCDirector::sharedDirector, etc.)
    // returns *uninitialized* memory and downstream vtable calls land
    // on .rodata strings reinterpreted as fnptrs.
    "__cxa_guard_acquire", "__cxa_guard_release", "__cxa_guard_abort",
    // C++ static-destructor registration. Like cxa_guard, takes a
    // guest-pointer arg (the dso handle / guard variable) the host
    // can't directly use. We accept the registration and no-op the
    // dtor invocation -- statics live until process exit anyway.
    "__cxa_atexit", "__cxa_finalize", "atexit",
    // Variadic printf family + sscanf. cocos2d-x's CCLog / CCAssert
    // path calls vfprintf with a guest .rodata format string; libc
    // dereferences the bare guest vaddr and faults inside __vfprintf.
    "vfprintf", "vprintf", "vsnprintf", "vsprintf", "sscanf",
    // _FORTIFY_SOURCE variants of the sprintf/snprintf family. Modern
    // NDK builds (the default well before API 24) rewrite plain
    // sprintf/snprintf/vsprintf/vsnprintf call sites to these four; none
    // were listed here, so they bare-resolved to DARWIN'S OWN identically
    // named __sprintf_chk/__snprintf_chk/__vsprintf_chk/__vsnprintf_chk
    // (libsystem_c.dylib ships its own FORTIFY_SOURCE implementation
    // under the same symbols) and handed it the guest's untranslated
    // 32-bit vaddr as a real host pointer -- confirmed by HCR 1.68.1
    // SEGV'ing inside host __vfprintf (called from __vsprintf_chk,
    // called from lifted code) at a small guest-shaped fault address.
    // Each also needs kPointerAwareWideImports below: the extra flags/
    // dst_len params in front of format push format itself (for the
    // two size-checked forms) and every vararg off r0-r3 onto the stack.
    "__sprintf_chk", "__snprintf_chk", "__vsprintf_chk", "__vsnprintf_chk",
    // v1.0.830: the rest of the _FORTIFY_SOURCE string family, found by
    // scanning every ARM32 binary in the fleet for undefined __*_chk
    // symbols missing from this set. All take guest pointers; unrouted
    // they bind to Darwin's own same-named fortify functions. Users in
    // the fleet: __strcpy_chk/__strcat_chk (HCR 1.68.1, sgtpuzzles),
    // __strncpy_chk (wikipedia maplibre), __strncat_chk (reddit cronet).
    // NOT added: __pread64_chk -- its off64_t argument needs an even
    // register pair under AAPCS32 and therefore a hand-written stack
    // walk, and its only fleet user (reddit cronet) is not an ARM32
    // target; adding it untested would be worse than leaving it loud.
    "__strcpy_chk", "__strcat_chk", "__strncpy_chk", "__strncat_chk",
    // Time / timezone family. gettimeofday writes into a guest-
    // pointer struct timeval; without translation libsystem
    // dereferences the guest vaddr and faults at the small low addr.
    // clock_gettime is load-bearing for ANY engine that computes a
    // per-frame dt (cocos2d-x v2 CCElapsedTimer, AndEngine, custom
    // GL loops). Without the shim the unresolved PLT panic-stub
    // returns 0, the engine sees dt=0 every frame, and the whole
    // action/animation/transition system silently freezes -- looks
    // like "game doesn't respond to touch" because button-press
    // animations and scene transitions are action-driven.
    // mktime was the ONE member of this family with no shim, so it
    // raw-bound to libSystem and dereferenced the guest's 32-bit vaddr as
    // a host `struct tm*`. Measured on libmortargame.so (Jetpack Joyride,
    // armeabi-only), which imports exactly gmtime/localtime/mktime/
    // strftime/time. The shim also has to marshal field-by-field: the
    // ILP32 guest's struct tm is 44 bytes (4-byte long + 4-byte char*)
    // against the host's 56. See __macapk_shim_mktime in bionic_shim.cpp.
    "gettimeofday", "time", "gmtime", "localtime", "strftime", "mktime",
    "clock_gettime", "nanosleep",
    // qsort/bsearch: function-pointer comparators need lifted-code
    // dispatch. The shims are best-effort no-ops for now.
    "qsort", "bsearch",
    // GLES2 family (<=4 args). Routed to ANGLE through the bionic
    // shim so libgame.so's PLT-imported gl* symbols hit our Metal
    // backend instead of trying to resolve nonexistent libGLESv2 on
    // darwin. Each shim converts guest-address pointer args against
    // guest_base before calling the real libGLESv2 entry point.
    "glActiveTexture", "glAttachShader", "glBindAttribLocation",
    "glBindBuffer", "glBindFramebuffer", "glBindRenderbuffer",
    "glBindTexture", "glBlendFunc", "glBufferData", "glBufferSubData",
    "glCheckFramebufferStatus", "glClear", "glClearColor",
    "glClearDepthf", "glClearStencil", "glCompileShader",
    "glCreateProgram", "glCreateShader",
    "glDeleteBuffers", "glDeleteFramebuffers", "glDeleteProgram",
    "glDeleteRenderbuffers", "glDeleteShader", "glDeleteTextures",
    "glDepthFunc", "glDepthMask", "glDisable",
    "glDisableVertexAttribArray", "glDrawArrays", "glDrawElements",
    "glEnable", "glEnableVertexAttribArray",
    "glFramebufferRenderbuffer",
    "glGenBuffers", "glGenFramebuffers", "glGenRenderbuffers",
    "glGenTextures", "glGenerateMipmap",
    "glGetError", "glGetFloatv", "glGetIntegerv",
    "glGetProgramInfoLog", "glGetProgramiv",
    "glGetShaderInfoLog", "glGetShaderiv",
    "glGetString", "glGetUniformLocation", "glHint",
    "glLineWidth", "glLinkProgram", "glPixelStorei",
    "glRenderbufferStorage", "glScissor", "glShaderSource",
    "glTexParameteri",
    "glUniform1f", "glUniform1i",
    "glUniform2f", "glUniform2fv",
    "glUniform3f", "glUniform3fv",
    "glUniform4fv",
    "glUniformMatrix4fv", "glUseProgram", "glValidateProgram",
    "glViewport",
    // v1.0.833. Listing a name HERE is what makes its __macapk_shim_
    // actually get used: resolve_import only builds the "__macapk_shim_"
    // + name form for members of these curated sets. A name absent from
    // them falls through to the BARE symbol, and since the shim links
    // libGLESv2, dlsym(RTLD_DEFAULT) finds ANGLE's own entry point and
    // the bind succeeds -- so the import stops being reported as
    // unresolved while quietly calling ANGLE DIRECTLY, with the guest's
    // 32-bit offsets used as host pointers. That is strictly worse than
    // the panic stub it replaced, which at least failed loudly-ish by
    // returning 0. Writing the shim is only half the job; this is the
    // other half (same trap as v1.0.820's 24 dead-code shims).
    "glStencilFunc", "glStencilOp", "glStencilMask",
    "glBlendEquation", "glIsEnabled", "glGetBooleanv",
    "glGetShaderSource", "glUniformMatrix3fv",
    "glUniform2i", "glUniform3i",
    "glUniform2iv", "glUniform3iv", "glUniform4iv",
    // GL functions whose arity exceeds 4 ARM-reg slots. Their shims
    // also need to live in kPointerAwareWideImports so the IR thunk
    // emits the 5-arg (sp-passing) signature.
    "glUniform4i",
    "glUniform4f", "glFramebufferTexture2D",
    "glReadPixels", "glTexImage2D", "glCompressedTexImage2D",
    "glVertexAttribPointer",
    // Bionic's combined sin+cos. These are here rather than in the
    // libm sets below because what makes them special is the OUT
    // POINTERS: they write their results through two guest vaddrs
    // that the shim has to rebase onto g_guest_base, exactly like
    // frexp/modf. macOS exports no bare `sincos`/`sincosf` (only
    // __sincos/__sincosf/__sincosf_stret), so before this entry an
    // ARM32 guest importing one bound to 0 -- and on arm32rc, whose
    // binds are NON-LAZY, that is a hard dlopen failure at load
    // (bouncy-v7a: "dlopen '_sincosf' not found in flat namespace").
    //
    // DELIBERATELY NOT in kSoftFloatDoubleImports even though sincos
    // takes a double: that set means "the shim returns i64 and the
    // call site splits it into r0:r1". sincos returns VOID. Adding it
    // there would make the call site split a return value that does
    // not exist and clobber r1. The double ARGUMENT needs no special
    // handling -- it arrives in r0:r1 and the (a,b,c,d) quad passes
    // it straight through.
    "sincos", "sincosf",
    // TEMPORARY LOCAL TEST EDIT (lane N, 2026-07-28) -- NOT a committed
    // change, reverted before this session ends. Real diff request is in
    // docs/anativewindow-bridge_2026-07-28.md for the boss to apply.
    "ANativeWindow_fromSurface", "ANativeWindow_release",
    "ANativeWindow_acquire", "ANativeWindow_getWidth",
    "ANativeWindow_getHeight", "ANativeWindow_getFormat",
    "ANativeWindow_setBuffersGeometry", "ANativeWindow_toSurface",
    "eglGetDisplay", "eglInitialize", "eglChooseConfig",
    "eglGetConfigAttrib", "eglCreateContext", "eglCreateWindowSurface",
    "eglMakeCurrent", "eglSwapBuffers", "eglQuerySurface",
    "eglDestroySurface", "eglDestroyContext", "eglTerminate",
    "ALooper_forThread", "ALooper_prepare", "ALooper_acquire",
    "ALooper_release",
    // v1.0.838: OpenGL ES 1.1 FIXED-FUNCTION + GL_OES_framebuffer_object.
    // Everything above this line is ES2; this set had 85 ES2 names and ZERO
    // ES1 names, which is why an ES1 app could not render.
    //
    // This is the v1.0.833 trap in its worst form. A name absent from these
    // sets falls through to the BARE symbol -- and for ES1 that is NOT the
    // panic stub, because ANGLE implements ES 1.1 natively and the shim links
    // libGLESv2, so dlsym(RTLD_DEFAULT) finds a REAL entry point for every
    // one of the 31 names below (verified with `nm -gU` on our
    // libGLESv2.dylib). The bind therefore SUCCEEDS, the import stops being
    // reported as unresolved, and ANGLE is called directly with the guest's
    // 32-bit vaddrs as 64-bit host pointers. Measured on Jetpack Joyride
    // (armeabi-only, Halfbrick Mortar): SEGV with pc inside libGLESv2 at
    // angle::Mat4::Mat4(const float*), i.e. the ES1 matrix stack
    // dereferencing the vaddr passed to glLoadMatrixf/glMultMatrixf.
    //
    // Three distinct ABI hazards are covered here, and only the first faults:
    //   1. POINTER args -- the two matrix loaders, the four gl*Pointer array
    //      entries, and six OES entries taking GLuint*/GLint*.
    //   2. SOFT-FLOAT -- glColor4f's four floats arrive as raw bits in r0-r3
    //      under ARM32 AAPCS but Apple arm64 reads s0-s3. A bare bind draws
    //      the WRONG COLOUR and never crashes, so it survives a "no more
    //      SEGVs" verdict unnoticed.
    //   3. VBO-vs-CLIENT-MEMORY on the four gl*Pointer entries -- with a
    //      buffer bound the pointer is an OFFSET and must NOT be translated;
    //      with none bound it is client memory and must be. Both error
    //      directions corrupt geometry silently. Implemented per ES 1.1 spec
    //      section 2.9.1, identically to the existing ES2
    //      __macapk_shim_glVertexAttribPointer so the tiers cannot drift.
    //
    // Shims live in the self-contained src/bionic_shim_gles1.cpp. Each name
    // below was verified to have a real __macapk_shim_* export, and each
    // produced dylib was checked with `nm -u` to confirm it binds to
    // __macapk_shim_* and not to the bare symbol -- the unresolved COUNT
    // cannot tell those apart. See docs/gles1-fixed-function_2026-07-30.md.
    //
    // ES1 needs an ES1 CONTEXT as well as these binds; that half already
    // exists (runtime/src/main.cpp ApkNeedsGles1 -> MACAPK_GLES1_CONTEXT).
    "glMatrixMode", "glLoadIdentity", "glPushMatrix", "glPopMatrix",
    "glLoadMatrixf", "glMultMatrixf",
    "glEnableClientState", "glDisableClientState", "glClientActiveTexture",
    "glColor4f", "glTexEnvi", "glCullFace",
    "glVertexPointer", "glTexCoordPointer", "glColorPointer", "glNormalPointer",
    "glBindFramebufferOES", "glBindRenderbufferOES",
    "glCheckFramebufferStatusOES", "glDeleteFramebuffersOES",
    "glDeleteRenderbuffersOES", "glFramebufferRenderbufferOES",
    "glFramebufferTexture2DOES", "glGenFramebuffersOES",
    "glGenRenderbuffersOES", "glGenerateMipmapOES",
    "glGetFramebufferAttachmentParameterivOES",
    "glGetRenderbufferParameterivOES", "glIsFramebufferOES",
    "glIsRenderbufferOES", "glRenderbufferStorageOES",
};

// Subset of pointer-aware imports whose shim reads more than four
// 32-bit ARM args. ARM AAPCS spills args 5+ onto the caller stack at
// sp+0, sp+4, sp+8, ... so the shim needs the lifted SP value to
// recover them. These shims take a 5th `i32 sp` parameter; the IR
// thunk passes the SP slot through instead of dropping it like the
// regular 4-arg shims do.
//
// All entries here MUST also live in kPointerAwareImports above.
static const std::unordered_set<std::string> kPointerAwareWideImports = {
    // v1.0.821: the 64-bit-off_t family. AAPCS32 rule C.3 rounds the NCRN up to
    // the next EVEN register for a double-word-aligned argument, and C.7 does the
    // same for the NSAA on the stack -- so these do NOT sit where a naive
    // left-to-right count would put them. mmap64 is the clearest case: addr/
    // length/prot/flags fill r0-r3, fd lands at sp+0, and the 64-bit offset is
    // pushed to sp+8 rather than sp+4. pread64 and lseek64 each burn one register
    // as alignment padding. The shim therefore has to walk SP, hence this set as
    // well as kPointerAwareImports (wide is ADDITIVE, not an alternative).
    //
    // NOTE __pread_chk/__pwrite_chk take the PLAIN 32-bit off_t -- they are NOT
    // the off64_t variants (__pread64_chk), so no alignment skip applies to them;
    // they are here only for their stack-spilled 5th argument.
    "__pread_chk", "__pwrite_chk", "pread64", "lseek64", "mmap64",
    // v1.0.819: strftime_l's 5th arg (locale_t) is stack-spilled under AAPCS32,
    // so the shim must walk SP past r0-r3 to reach it -- the same reason sscanf
    // is in this set. Listing it in kPointerAwareImports alone is not enough.
    "strftime_l",
    "glUniform4f",              // 5 args (location, x, y, z, w)
    "glUniform4i",              // 5 args (location, v0, v1, v2, v3) -- v1.0.833
    "glFramebufferTexture2D",   // 5 args
    "glReadPixels",             // 7 args
    "glTexImage2D",             // 9 args
    "glCompressedTexImage2D",   // 8 args
    "glVertexAttribPointer",    // 6 args
    "mmap",                     // 6 args (addr, len, prot, flags, fd, offset)
    // sscanf is variadic; cocos2d-x's BMFont parser stuffs 8+ out
    // pointer args into a single sscanf call. The shim needs SP to
    // walk past r2/r3 into the stack-passed pointers.
    "sscanf",
    // _FORTIFY_SOURCE sprintf/snprintf family -- see the
    // kPointerAwareImports comment above for why. All four need SP:
    // the flags/dst_len params shift format and/or every vararg onto
    // the stack.
    "__sprintf_chk", "__snprintf_chk", "__vsprintf_chk", "__vsnprintf_chk",
    // v1.0.1653 (MACAPK_ARM32_PRINTF_AAPCS, bionic_shim.cpp Arm32VarArgs): the
    // PLAIN printf family is variadic too, and any vararg past r3 is on the
    // stack. Listed only as pointer-aware, they reached their shims without SP,
    // so every stack-passed argument printed as 0 (HCR 1.8.1's "line:0").
    "sprintf", "snprintf", "printf", "fprintf",
    // ioctl(fd, request, argp): r0..r2 = fd/request/argp. Only 3 args
    // (no stack spill needed), so NOT wide. writev(fd, iov, iovcnt) and
    // poll(fds, nfds, timeout) likewise fit in r0..r2. mkdir(path,mode),
    // remove/rename, getcwd(buf,size), write(fd,buf,count), fdopen(fd,
    // mode) all <=3 args. None of the new POSIX shims need SP, so none
    // are added to kPointerAwareWideImports.
    // (malloc / calloc were listed here only to feed the RETIRED cocos2d-x
    // CCActionManager::addAction tripwire, which read the caller's `this`
    // from its spill frame. Bionic's allocator takes sizes only, so they are
    // not wide. docs/cocos-actionmanager-tripwire-retired_2026-09-27.md)
    // __android_log_print(prio, tag, fmt, ...): r0..r2 = prio/tag/fmt,
    // r3 = arg0 (first vararg), stack = args 1..3. cocos2d-x v2's
    // CCAssert uses 3 varargs (file, function, line); without the wide
    // signature the shim only sees `file` and prints "function:(null)
    // line:0" for every assertion -- losing 95% of the diagnostic value.
    "__android_log_print",
    // TEMPORARY LOCAL TEST EDIT (lane N, 2026-07-28) -- see matching note
    // above; reverted before this session ends.
    "eglChooseConfig",
    // v1.0.838: the ONLY ES1/OES name whose arity exceeds the four ARM arg
    // registers. glFramebufferTexture2DOES(target, attachment, textarget,
    // texture, level) spills `level` to sp+0, so its shim takes the extra
    // `sp` param and walks the caller frame. Wide is ADDITIVE -- the name is
    // in kPointerAwareImports too. Every other ES1 entry point added in this
    // batch is <=4 args (glNormalPointer is 3, not 4: ES 1.1 section 2.8
    // gives normals no `size` argument).
    "glFramebufferTexture2DOES",
};

// libm math imports that take and/or return doubles. ARM AAPCS soft-
// float passes doubles in register pairs (r0:r1 for first double arg,
// r2:r3 for second, etc.) and returns doubles in r0:r1. macOS arm64
// hard-float passes doubles in d-registers and returns in d0. These
// shims do the ABI conversion: receive (lo, hi) as i32 pair, pack
// into double, call libm's host function, return result as i64
// (packed (hi << 32) | lo). The lifted call sites are special-cased
// in arm32_lifter::emit_bl to capture the i64 return and split into
// r0/r1. WITHOUT THIS HANDLING, the lifted code's `vmov dN, r0, r1`
// post-call reads r1 (which was NEVER updated) and reconstructs a
// double that's the INPUT angle's high half OR'd with the LOW 32 bits
// of sin(input), producing visibly wrong projection matrices (cocos2d-x
// v2's kCCDirectorProjection3D uses cot(fovY/2) which always ends up
// being cot(45deg)=1.0 instead of the correct cot(30deg)=1.732).
//
// All entries here also need a __macapk_shim_<name> in bionic_shim.cpp
// that returns i64.
static const std::unordered_set<std::string> kSoftFloatDoubleImports = {
    // v1.0.819: these return 64 bits in the r0:r1 pair under the ARM soft-float
    // ABI, so the caller's next `vmov dN, r0, r1` reconstructs the value. Without
    // this the high word is stale and the result is silently half-right -- the
    // same failure shape as the JNI J/D return path fixed in v1.0.812.
    "strtoll_l", "strtoull_l", "strtold_l",
    "sin", "cos", "tan",
    "asin", "acos", "atan", "atan2",
    "sinh", "cosh", "tanh",
    "exp", "exp2", "log", "log2", "log10",
    "sqrt", "cbrt", "pow",
    "ceil", "floor", "trunc", "round", "rint",
    "fabs", "fmod", "fmin", "fmax",
    // Mixed-signature double functions with pointer / int out-params:
    // ldexp(x, int n)         -> returns x*2^n
    // frexp(x, int *exp)      -> returns mantissa, writes exponent
    // modf(x, double *ipart)  -> returns fractional, writes integer part
    // The shim's signature is the standard (a, b, c, d) i32 quad, with
    // (a, b) holding the input double and c holding either the second
    // arg (int n) or a guest-vaddr pointer the shim translates via
    // g_guest_base before dereferencing.
    "ldexp", "frexp", "modf",
    // Despite the set's name, membership here just means "this call needs
    // its return value delivered as i64 and split into r0:r1" -- the split
    // itself (arm32_lifter::emit_bl's trunc/lshr pair, mirrored by arm32rc's
    // is_softfloat_double_plt-driven codegen) is pure integer bit-shifting
    // with no floating-point reinterpretation anywhere in it, so it is
    // exactly as correct for a genuine 64-bit INTEGER return as for a
    // double. AAPCS32 6.4 (ARM IHI 0042) groups them under the identical
    // rule: "A double-word sized Fundamental Data Type (e.g., long long,
    // double and 64-bit containerized vectors) is returned in r0 and r1."
    // atoll/strtoll/strtoull (LANE M 2026-07-28): without a shim these fall
    // through to a bare dlsym(RTLD_DEFAULT, "<name>") against real host
    // libSystem, which dereferences the GUEST's untranslated 32-bit pointer
    // as a host address -- confirmed present (objdump -T) in HCR 1.43's
    // libgame.so at atoll@plt (v7a:0x71c3c, arm64:0xf583c is the Stage-6
    // sibling, which does NOT crash because Stage-6 guest pointers are
    // already real host addresses -- this hazard is ARM32-only).
    // strtoll/strtoull are not imported by anything in the local fleet today
    // (checked: objdump -T across every lib/armeabi-v7a/*.so in ../Apks) but
    // are added on the same "don't gate framework completeness on today's
    // fleet" principle as every other generic shim in this file -- the
    // failure mode for the next app that imports one is identical.
    // strtoll_l / strtoull_l were NOT added: the same fleet-wide scan found
    // zero imports of either, and inventing an untested locale-variant shim
    // for a symbol nothing currently needs is exactly the kind of unverified
    // work the project's own rules ask us not to ship speculatively.
    "atoll", "strtoll", "strtoull",
};

// v1.0.1655: MACAPK_ARM32_SOFTFP_ATOF (default ON; =0 restores the old
// binding exactly). atof and strtod return DOUBLE (bionic/libc/include/
// stdlib.h), i.e. r0:r1 under softfp (AAPCS32 6.4), but they sat only in
// kPointerAwareImports, bound to shims that return FLOAT bits in r0 and leave
// r1 stale. The guest's `vmov dN, r0, r1` then rebuilt a random double for
// every coordinate cocos2d-x parses out of a sprite-sheet .plist
// (CCPointFromString @0x473954 in GD Lite 2.2.11 v7a: `blx atof@plt; vmov d8,
// r0, r1`). With the gate on they bind NEW i64-returning shims
// (__macapk_shim_<name>_softfp, bionic_shim.cpp) and join the soft-float
// double set, so arm32rc's call site splits the result into r0:r1. New symbol
// names rather than a changed signature, so a cached dylib from either arm
// always binds the shim that matches its call sites.
// Write-up: docs/arm32-thumb2-pic-fnptr-lr-and-nopw_2026-09-27.md (section 5).
static bool arm32_softfp_atof_on() {
    static const bool on = [](){
        const char* e = std::getenv("MACAPK_ARM32_SOFTFP_ATOF");
        return !(e && *e == '0');
    }();
    return on;
}
static bool is_softfp_parse_double(const std::string& nm) {
    return arm32_softfp_atof_on() && (nm == "atof" || nm == "strtod");
}
// MACAPK_ARM32_SETENV_SHIM (v1.0.1655, default ON; =0 binds exactly as before:
// setenv/unsetenv/putenv to libSystem's bare names, clearenv unresolved). The
// guest-environment WRITERS had no ARM32 pointer-aware twin, so an ARM32 setenv
// handed libSystem a raw 32-bit guest vaddr (OpenTTD v7a libsdl_main nativeInit:
// SEGV at host setenv+0x1c) and, pointer aside, would have written the HOST
// environment that __macapk_shim_getenv no longer reads. With the gate on they
// bind __macapk_shim_<name> (bionic_shim.cpp, next to __macapk_shim_getenv),
// which write the one guest environment Stage 6 and Java's Os.setenv share.
// A separate set rather than four more kPointerAwareImports rows so =0 is a
// real, byte-identical escape.
static bool arm32_setenv_shim_on() {
    static const bool on = [](){
        const char* e = std::getenv("MACAPK_ARM32_SETENV_SHIM");
        return !(e && *e == '0');
    }();
    return on;
}
static bool is_arm32_env_writer_import(const std::string& nm) {
    return arm32_setenv_shim_on() &&
           (nm == "setenv" || nm == "unsetenv" || nm == "putenv" ||
            nm == "clearenv");
}
// ---- lane arm32-shim-gaps (2026-09-28): three families of ARM32 imports that
// bound a HOST function. tier_of's host-dlsym tier hands the recompiled guest's
// 32-bit GUEST pointers and softfp registers straight to an AAPCS64 (hardfp)
// libSystem function, which is a SEGV or a silently wrong answer. Each family
// has its own default-ON gate; with a gate =0 the names bind exactly as before
// (none of the new twins is referenced), so the translated dylib is
// byte-identical. New twin NAMES rather than changed shims, as
// MACAPK_ARM32_SOFTFP_ATOF did, so a dylib cached by either arm always binds the
// shim its call sites were translated for.
// Write-up: docs/arm32-shim-gaps-sigjmp-memalign-libm_2026-09-28.md.
static bool arm32_gap_gate_on(const char* name) {
    const char* e = std::getenv(name);
    return !(e && *e == '0');
}
// MACAPK_ARM32_RWLOCK_SHIM (v1.0.2374, default ON; =0 binds exactly as before, i.e. libSystem's bare
// names). pthread_rwlock_* / pthread_rwlockattr_* / flock-family had no ARM32 pointer-aware twin, so a
// guest rwlock (a 40-byte Bionic object in the arena) or FILE* token reached libSystem raw and SEGV'd
// (Douyin libmetasec_ml: pthread_rwlock_init inside a function-local static initialiser; the recovered
// fault left the __cxa_guard "in progress" and the process deadlocked under dyld's loader lock).
// A separate set, like the setenv family, so =0 is a real escape.
static bool arm32_rwlock_shim_on() {
    static const bool on = arm32_gap_gate_on("MACAPK_ARM32_RWLOCK_SHIM");
    return on;
}
static bool is_arm32_rwlock_import(const std::string& nm) {
    if (!arm32_rwlock_shim_on()) return false;
    static const std::set<std::string> k = {
        "pthread_rwlock_init", "pthread_rwlock_destroy", "pthread_rwlock_rdlock",
        "pthread_rwlock_tryrdlock", "pthread_rwlock_wrlock", "pthread_rwlock_trywrlock",
        "pthread_rwlock_unlock", "pthread_rwlock_timedrdlock", "pthread_rwlock_timedwrlock",
        "pthread_rwlock_timedrdlock_monotonic_np", "pthread_rwlock_timedwrlock_monotonic_np",
        "pthread_rwlock_clockrdlock", "pthread_rwlock_clockwrlock",
        "pthread_rwlockattr_init", "pthread_rwlockattr_destroy", "pthread_rwlockattr_getpshared",
        "pthread_rwlockattr_setpshared", "pthread_rwlockattr_getkind_np", "pthread_rwlockattr_setkind_np",
        "flockfile", "funlockfile", "ftrylockfile",
        "fseeko", "ftello", "popen", "pclose", "asprintf", "vasprintf",
        "eglGetProcAddress", "fscanf", "vfscanf", "tmpfile",
    };
    return k.count(nm) != 0;
}
// MACAPK_ARM32_SIGJMP: the whole Bionic setjmp family. Bionic arm
// (libc/arch-arm/bionic/setjmp.S) has ONE jmp_buf format: `setjmp` is
// `mov r1, #1; b sigsetjmp`, `_setjmp` is `mov r1, #0; b sigsetjmp`, and
// longjmp / _longjmp are siglongjmp. sigsetjmp had NO binding at all (host
// sigsetjmp wrote a 64-bit host context through a 32-bit guest vaddr: OpenTTD
// libcrypto init SEGV), and the five that had one were a fake (setjmp returned 0,
// longjmp aborted the whole invoke_safe call). The twins save and restore the
// recompiled caller's real state in the guest's own 256-byte jmp_buf.
static bool arm32_sigjmp_on() {
    static const bool on = arm32_gap_gate_on("MACAPK_ARM32_SIGJMP");
    return on;
}
// MACAPK_ARM32_ALIGNED_ALLOC: posix_memalign bound libSystem's, which wrote an
// 8-byte HOST pointer through the guest's 32-bit memptr (OpenTTD: SEGV in
// _posix_memalign at guest-stack 0x3e0fff58), and memalign's shim took memory
// from the HOST allocator and then refused it for lying outside the guest window
// (it could only ever return 0). Both now allocate from the guest heap.
static bool arm32_aligned_alloc_on() {
    static const bool on = arm32_gap_gate_on("MACAPK_ARM32_ALIGNED_ALLOC");
    return on;
}
// MACAPK_ARM32_SOFTFP_LIBM: libm / fpclassify entry points with a FLOATING
// argument or result that no set above covered, so they bound libSystem's
// hardfp versions: the guest's softfp argument (r0 / r0:r1, AAPCS32 6.4) was
// never read (libSystem reads s0/d0) and the result was never delivered
// (libSystem returns in s0/d0). Census 2026-09-28 (debug/arm32_import_census.py):
// GD Lite 2.2.11 imports __isnanf, __fpclassifyd and asinh; the rest are imported
// by Telegram, Signal, Subway, Dumb Ways, Fossify Gallery, Wikipedia, Organic Maps,
// AnarchRE and Rocks'n'Diamonds v7a libraries. The fpclassify names are the whole
// of bionic/libc/bionic/fpclassify.cpp, which returns BIONIC'S FP_* values
// (FP_INFINITE 0x01 ... FP_ZERO 0x10, libc/include/math.h), not Darwin's.
static bool arm32_softfp_libm_on() {
    static const bool on = arm32_gap_gate_on("MACAPK_ARM32_SOFTFP_LIBM");
    return on;
}
static const std::unordered_set<std::string> kArm32SigjmpImports = {
    "setjmp", "_setjmp", "sigsetjmp", "longjmp", "_longjmp", "siglongjmp",
};
// Results that are 64 bits wide (double, or long long) and therefore come back
// in r0:r1, so arm32rc's call site must split the shim's int64 return.
static const std::unordered_set<std::string> kArm32SoftfpLibmDouble = {
    "asinh", "expm1", "log1p", "hypot", "logb", "nan", "remainder", "scalbn",
    "llrint", "llround", "llrintf", "llroundf",
};
// Results that fit r0 (int, long, float): the default i32 call-site path.
static const std::unordered_set<std::string> kArm32SoftfpLibmWord = {
    "__fpclassifyd", "__fpclassify", "__fpclassifyf", "__fpclassifyl",
    "__isinf", "isinf", "__isinff", "isinff", "__isinfl", "isinfl",
    "__isnan", "isnan", "__isnanf", "isnanf", "__isnanl", "isnanl",
    "__isfinite", "isfinite", "__isfinitef", "isfinitef",
    "__isfinitel", "isfinitel",
    "__isnormal", "isnormal", "__isnormalf", "isnormalf",
    "__isnormall", "isnormall",
    "lrint", "lround", "lrintf", "modff",
};
// The __macapk_shim_* twin a gap import binds, or "" when `nm` is not one of
// them or its family's gate is =0.
static std::string arm32_gap_shim(const std::string& nm) {
    if (arm32_sigjmp_on() && kArm32SigjmpImports.count(nm))
        return "__macapk_shim_" + nm + "_bionic";
    if (arm32_aligned_alloc_on()) {
        if (nm == "posix_memalign") return "__macapk_shim_posix_memalign";
        if (nm == "memalign") return "__macapk_shim_memalign_heap";
    }
    if (arm32_softfp_libm_on() &&
        (kArm32SoftfpLibmDouble.count(nm) || kArm32SoftfpLibmWord.count(nm)))
        return "__macapk_shim_" + nm;
    return std::string();
}
static bool is_arm32_gap_double(const std::string& nm) {
    return arm32_softfp_libm_on() && kArm32SoftfpLibmDouble.count(nm) != 0;
}
static bool is_softfloat_double_import(const std::string& nm) {
    return kSoftFloatDoubleImports.count(nm) != 0 || is_softfp_parse_double(nm) ||
           is_arm32_gap_double(nm);
}

// Single-precision libm imports (the *f family). Under the ARM AAPCS
// BASE ("softfp") PCS -- which HCR/GD use (no Tag_ABI_VFP_args; the
// double family above already relies on this) -- a float argument is
// passed in a CORE register (first float in r0, second in r1, ...)
// and a float result is returned in r0 (AAPCS IHI 0042 section 6.1.2:
// under the base standard, floating point values live in core regs).
// So the guest does `vmov r0, sN; bl sinf@plt` and reads the result
// back from r0. macOS arm64 libSystem is HARDFP: `float sinf(float)`
// takes its arg in s0 and returns in s0. Binding the guest call to
// libSystem directly (declared `i32 sinf(i32,...)`) sends the float
// BITS to x0 while libSystem reads s0 (never written) and returns in
// s0 (never read) -- garbage in AND out. The __macapk_shim_<f>
// wrappers in bionic_shim.cpp do the conversion: bitcast the i32
// bits -> float, call host libm <f>, bitcast result float -> i32.
// Unlike the double family there is NO i64 r0:r1 split: a float fits
// in r0, so these are i32-returning shims and the lifter's DEFAULT
// i32-return BL path handles them with no special-casing (they are
// deliberately NOT registered into soft_float_double_plt_).
//
// Every entry here also needs an i32-returning __macapk_shim_<name>
// in bionic_shim.cpp.
static const std::unordered_set<std::string> kSoftFloatFloatImports = {
    "sinf", "cosf", "tanf",
    "asinf", "acosf", "atanf", "atan2f",
    "sinhf", "coshf", "tanhf",
    "expf", "exp2f", "expm1f",
    "logf", "log2f", "log10f", "log1pf",
    "sqrtf", "cbrtf", "powf", "hypotf",
    "ceilf", "floorf", "truncf", "roundf", "rintf", "nearbyintf",
    "fabsf", "fmodf", "fminf", "fmaxf", "fdimf",
    "copysignf",
    // lroundf/llroundf return an integer (long / long long) but still
    // take a float ARG in r0; without the shim libSystem reads s0 ->
    // garbage. lroundf fits r0 (long==i32 on arm32); the shim returns
    // the rounded value as i32. (llroundf returns i64 -> excluded
    // here; if it ever shows up it needs the double-style i64 split.)
    "lroundf",
    // ldexpf(float x, int n): x in r0 (float bits), n in r1 (plain int).
    "ldexpf",
    // scalbnf(float x, int n): same shape as ldexpf.
    "scalbnf",
};

// Encode a byte buffer as an LLVM IR string-literal constant body.
// Printable ASCII goes verbatim; everything else (including the
// regex-style escape characters " and \) becomes \xx hex. The
// returned text is meant to be inserted between c" and " in
// `@var = private constant [N x i8] c"..."`.
[[maybe_unused]] std::string encode_segment_bytes(const uint8_t* data, size_t n) {
    std::string out;
    out.reserve(n * 2);
    static const char* hex = "0123456789ABCDEF";
    for (size_t i = 0; i < n; ++i) {
        uint8_t b = data[i];
        // Printable ASCII range, excluding " and \ which are
        // metacharacters inside an LLVM IR `c"..."` literal.
        if (b >= 0x20 && b < 0x7F && b != '"' && b != '\\') {
            out.push_back(static_cast<char>(b));
        } else {
            out.push_back('\\');
            out.push_back(hex[b >> 4]);
            out.push_back(hex[b & 0xF]);
        }
    }
    return out;
}

// Emit the module-scope segment constants and the LLVM constructor
// that registers each segment with the shim at dlopen time. Also
// registers each lifted function's vaddr->host-pointer entry with
// the in-image LUT so BLX-via-register lookups (vtable / callback
// dispatch) resolve back to the translated arm64 entry, and runs
// each .init_array entry (C++ static ctors) so module-scope objects
// get constructed before any of the lifted JNI methods run.
// Templated on Image so both ARM32 and arm64 paths share plumbing.
template <typename Image, typename AddrToName>
void emit_segment_constructor(std::stringstream& out, const Image& img,
                              const AddrToName& lifted_addr_to_name,
                              const std::unordered_set<std::string>&
                                  renamed_jni_names,
                              bool aapcs, uint32_t load_bias = 0) {
    out << "; --- guest memory: PT_LOAD segments embedded as RO blobs ---\n";
    int idx = 0;
    for (const auto& seg : img.loadable) {
        if (seg.bytes.empty()) { ++idx; continue; }
        out << "@__macapk_segment_" << idx
            << " = private constant ["
            << seg.bytes.size() << " x i8] c\""
            << encode_segment_bytes(seg.bytes.data(), seg.bytes.size())
            << "\", align 1\n";
        ++idx;
    }
    out << "\n";

    // Per-init_array diagnostic tag constants. SEGV-handler reads
    // the tag set via __macapk_set_lifted_context to print the
    // offending ctor's vaddr, so a recovered crash log says e.g.
    // "init_array[3] vaddr=0x290abc". Constants live at module
    // scope; the function body below references them by symbol.
    {
        int ctor_idx = 0;
        for (uint32_t v : img.init_funcs) {
            auto it = lifted_addr_to_name.find(v);
            if (it == lifted_addr_to_name.end()) continue;
            std::ostringstream tag;
            tag << "init_array[" << ctor_idx << "] vaddr=0x"
                << std::hex << v;
            std::string tag_str = tag.str();
            out << "@__macapk_init_tag_" << ctor_idx
                << " = private constant ["
                << (tag_str.size() + 1) << " x i8] c\""
                << tag_str << "\\00\", align 1\n";
            ++ctor_idx;
        }
        if (ctor_idx > 0) out << "\n";
    }

    // External linkage on install_segments is load-bearing for the
    // chunked-compile path: every chunk's @llvm.global_ctors array
    // references this symbol, but only one chunk owns the DEFINE
    // (others get a `declare` synthesised by the splitter). With
    // `internal` linkage the symbol is private to its .o and the
    // cross-chunk declares can't resolve at link time, producing
    // `ld: fixup error ... target '___macapk_install_segments'
    // does not have address`. External works because the dylib's
    // top-level loader (libmacapk-bionic-shim) doesn't need to
    // import install_segments by name -- the ctor runs at dlopen
    // via @llvm.global_ctors regardless of linkage visibility.
    out << "define void @__macapk_install_segments() {\n";
    out << "entry:\n";
    idx = 0;
    for (const auto& seg : img.loadable) {
        if (!seg.bytes.empty()) {
            out << "  call void @__macapk_register_segment("
                << "i32 " << static_cast<uint64_t>(seg.vaddr + load_bias)  // per-image bias
                << ", ptr @__macapk_segment_" << idx
                << ", i32 " << seg.bytes.size()
                << ", i32 " << seg.bytes.size() << ")\n";
        }
        ++idx;
    }
    // Declare each PT_LOAD's writability to the arena W^X pass, then seal.
    // This is a SEPARATE call rather than two more parameters on
    // __macapk_register_segment because the lifted-dylib cache is
    // existence-based: a dylib translated before this landed still calls
    // the 4-arg form, and widening that signature would have it pass
    // garbage in the new registers. An old dylib simply never calls
    // these two, so it keeps today's fully-writable arena -- the safe
    // degradation. See __macapk_seal_guest_arena in bionic_shim.cpp.
    //
    // Sealing happens BEFORE .init_array runs, deliberately: a static
    // ctor writing through a bogus `this` into the code image is
    // precisely the class of bug this exists to surface (see the
    // install_late_null_guard block comment about CCRect::setRect
    // writing at guest vaddr ~0 during HCR's init_array).
    //
    // ARM32 only in practice: run_pipeline is instantiated solely with
    // GprBits==32, so this whole function never runs for an arm64 input
    // (those go through run_arm64_pack, whose images are mapped outside
    // the arena and already get real per-segment protections).
    {
        bool any = false;
        for (const auto& seg : img.loadable) {
            if (seg.bytes.empty()) continue;
            out << "  call void @__macapk_register_segment_prot("
                << "i32 " << static_cast<uint64_t>(seg.vaddr + load_bias)
                << ", i32 " << seg.bytes.size()
                << ", i32 " << (seg.executable ? 1 : 0) << ")\n";
            any = true;
        }
        if (any) out << "  call void @__macapk_seal_guest_arena()\n";
    }
    // Register every lifted function with the in-image LUT so
    // function-pointer calls (BLX <reg> with a runtime-loaded vaddr)
    // can be resolved back to the translated entry point. ARM32 only
    // -- arm64 inputs use the native function-pointer ABI directly
    // and don't need the LUT.
    if (!aapcs) {
        for (const auto& [vaddr, name] : lifted_addr_to_name) {
            // For JNI exports the lifted body got renamed to
            // __macapk_arm32_<name> when the JVM-side thunk was
            // emitted. Register the renamed symbol so any in-image
            // BLX-via-register-by-vaddr (rare for JNI funcs but cheap
            // to be safe) lands on the i32 ABI body, not the i64
            // JVM-shape thunk.
            std::string sym = renamed_jni_names.count(name)
                ? ("__macapk_arm32_" + name) : name;
            out << "  call void @__macapk_register_function("
                << "i32 " << static_cast<uint64_t>(vaddr + load_bias)  // per-image bias (LUT key)
                << ", ptr @" << sym << ")\n";
        }
        // Register every import's PLT trampoline by its PLT vaddr so
        // that GOT/.data slots populated by our R_ARM_GLOB_DAT /
        // R_ARM_ABS32 relocation pass (in elf_reader.cpp) can be
        // dispatched at runtime: lifted code does
        //   LDR Rx, [Ry]   ; Rx = plt_vaddr (we wrote this in the
        //                  ;   segment bytes when applying relocations)
        //   BLX Rx         ; indirect-call dispatcher resolves
        //                  ;   plt_vaddr -> __macapk_target_<plt_vaddr>
        //                  ;   which forwards to the host shim.
        // Without this register call the LUT lookup would return null
        // and the dispatcher would log "indirect call to in-image
        // vaddr 0x..." for every malloc/glXxx call going through GOT.
        for (const auto& imp : img.imports) {
            if (imp.plt_addr == 0) continue;
            out << "  call void @__macapk_register_function("
                << "i32 " << static_cast<uint64_t>(imp.plt_addr + load_bias)  // per-image bias (LUT key)
                << ", ptr @__macapk_target_"
                << std::hex << static_cast<uint64_t>(imp.plt_addr)  // symbol name stays RAW
                << std::dec << ")\n";
        }
        // Run .init_array entries (C++ static ctors). Each is a
        // void(void) function in ARM32 ABI; in lifted form a 5-arg
        // i32 function we can call with all-zero args. The shared
        // thread-stack helper gives us a valid SP for the call.
        // Each is wrapped in __macapk_invoke_safe so a crash in one
        // ctor (e.g. one whose dependencies aren't lifted yet)
        // doesn't kill dlopen and bring down the whole process. The
        // static state for that ctor stays uninitialized; later code
        // that depends on it may crash too, but we'll catch that
        // separately.
        if (!img.init_funcs.empty()) {
            out << "  ; --- run .init_array entries ---\n";
            out << "  %ctor_sp = call i32 @__macapk_alloc_thread_stack()\n";
            int ctor_idx = 0;
            for (uint32_t v : img.init_funcs) {
                auto it = lifted_addr_to_name.find(v);
                if (it == lifted_addr_to_name.end()) {
                    // Init entry whose function we couldn't lift --
                    // skip with a comment so the IR stays valid.
                    out << "  ; .init_array 0x" << std::hex << v
                        << std::dec << " unlifted; skipped\n";
                    continue;
                }
                std::string sym = renamed_jni_names.count(it->second)
                    ? ("__macapk_arm32_" + it->second) : it->second;
                out << "  call void @__macapk_set_lifted_context("
                    << "ptr @__macapk_init_tag_" << ctor_idx << ")\n";
                out << "  call i32 @__macapk_invoke_safe("
                    << "ptr @" << sym
                    << ", i32 0, i32 0, i32 0, i32 0, i32 %ctor_sp)\n";
                ++ctor_idx;
            }
            out << "  call void @__macapk_set_lifted_context(ptr null)\n";
        }
    }
    out << "  ret void\n";
    out << "}\n\n";

    // Hook the constructor into the global ctor list so it runs at
    // dlopen time, before any of the lifted functions can be called.
    out << "@llvm.global_ctors = appending global [1 x { i32, ptr, ptr }] [\n"
        << "  { i32, ptr, ptr } { i32 65535, "
        << "ptr @__macapk_install_segments, ptr null }\n"
        << "]\n\n";
}

// Peek the ELF class byte (e_ident[4]) so the caller can pick the
// right reader/lifter pair.
int peek_elf_class(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return -1;
    char buf[5];
    if (!f.read(buf, 5)) return -1;
    if (!(buf[0] == 0x7f && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F'))
        return -1;
    return static_cast<unsigned char>(buf[4]);
}

}  // namespace

namespace pt = macapk::translator;

namespace {

// ---------------------------------------------------------------------
// Cross-guest-library linking (2026-07-28). See
// docs/cross-guest-library-linking_2026-07-28.md for the full design and
// the empirical dyld verification behind it. TL;DR: when one guest .so
// calls a function a SIBLING guest .so in the same app genuinely
// implements (Subway Surfers' Firebase Analytics/Crashlytics/Messaging
// calling into libFirebaseCppApp), the resolver below needs to know that
// sibling's exported names -- and it must learn them from the sibling's
// ELF, never its Mach-O translation, or this reintroduces a
// translation-ORDER dependency (AppClassLoader.preloadAllApkLibs()
// translates every .so in an app CONCURRENTLY with no ordering
// guarantee).
// ---------------------------------------------------------------------

std::string dirname_of(const std::string& p) {
    const auto slash = p.find_last_of('/');
    return (slash == std::string::npos) ? std::string(".") : p.substr(0, slash);
}
std::string basename_of(const std::string& p) {
    const auto slash = p.find_last_of('/');
    return (slash == std::string::npos) ? p : p.substr(slash + 1);
}

// ---- MACAPK_FIRSTLAUNCH_TIMING=1 (2026-09-26, diagnostic, default OFF) ----
// One stderr line per phase boundary, "[firstlaunch-timing] <phase> <ms>",
// milliseconds since the translator started. This is what
// docs/firstlaunch-translation-cost_2026-09-26.md's per-phase table was read
// from; it changes no output byte and prints nothing unless asked.
const std::chrono::steady_clock::time_point g_xlat_t0 =
    std::chrono::steady_clock::now();
void firstlaunch_mark(const char* phase) {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_TIMING");
        return e && *e && *e != '0';
    }();
    if (!on) return;
    const double ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - g_xlat_t0).count();
    std::fprintf(stderr, "[firstlaunch-timing] %s %.1f\n", phase, ms);
}

// ---- MACAPK_FIRSTLAUNCH_FASTREAD, main.cpp half (2026-09-26) ----------
//
// Whole-file read with one pread instead of an istreambuf_iterator, which
// copies a byte at a time through the stream layer. Same bytes, same order.
// Used for the sibling-.so export scan below. =0 restores the stream read.
bool firstlaunch_fastread_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_FASTREAD");
        return !(e && *e == '0');
    }();
    return on;
}

std::vector<uint8_t> read_whole_file(const std::string& path) {
    std::vector<uint8_t> raw;
    if (!firstlaunch_fastread_on()) {
        std::ifstream f(path, std::ios::binary);
        if (!f) return raw;
        raw.assign(std::istreambuf_iterator<char>(f),
                   std::istreambuf_iterator<char>());
        return raw;
    }
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return raw;
    struct stat st {};
    if (fstat(fd, &st) == 0 && st.st_size > 0) {
        raw.resize(static_cast<size_t>(st.st_size));
        size_t off = 0;
        while (off < raw.size()) {
            const ssize_t r = pread(fd, raw.data() + off, raw.size() - off,
                                    static_cast<off_t>(off));
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) break;
            off += static_cast<size_t>(r);
        }
        raw.resize(off);
    }
    close(fd);
    return raw;
}

// ---- MACAPK_FIRSTLAUNCH_NM_INPROC (2026-09-26) -------------------------
//
// The ARM32 path models the runtime's flat namespace by reading the global
// defined symbols of every dylib next to the shim, plus macapk-runtime. It
// did that with `popen("for f in ...; do nm -gU $f; done")`: one shell and
// seven nm processes per ARM32 library, ~160 ms of wall clock that the
// translate waits on (5% of Jetpack Joyride's, 12% of HCR 1.8.1's).
//
// This reads the same LC_SYMTAB in process and applies nm -gU's selection:
// skip STABs, keep N_EXT, drop N_UNDF unless it is a common (n_value != 0),
// and for a universal file use the arm64 slice only (what nm prints on this
// host without -arch). It then post-processes each name EXACTLY as the popen
// parser did -- keep the text after the LAST space, drop one leading '_' --
// so even a name with a space in it lands on the same token.
//
// Anything it does not model exactly (an N_INDR symbol, whose nm line ends in
// "(indirect for X)"; a file that is not a thin or arm64-bearing fat Mach-O)
// returns false, and the caller then takes the nm path for the WHOLE set, so
// the answer is always the one nm gives. Verified set-equal to nm on every
// file of runtime/build/bin and of the staged runtime (debug/firstlaunch_gate.sh
// re-checks it every run). MACAPK_FIRSTLAUNCH_NM_INPROC=0 restores the popen.
bool nm_inproc_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_NM_INPROC");
        return !(e && *e == '0');
    }();
    return on;
}

bool macho_slice_global_defined(const uint8_t* p, size_t n,
                                std::unordered_set<std::string>* out) {
    if (n < sizeof(mach_header_64)) return false;
    mach_header_64 h{};
    std::memcpy(&h, p, sizeof h);
    if (h.magic != MH_MAGIC_64) return false;
    size_t off = sizeof h;
    for (uint32_t i = 0; i < h.ncmds; ++i) {
        load_command lc{};
        if (off + sizeof lc > n) return false;
        std::memcpy(&lc, p + off, sizeof lc);
        if (lc.cmdsize < sizeof lc) return false;
        if (lc.cmd == LC_SYMTAB) {
            symtab_command st{};
            if (off + sizeof st > n) return false;
            std::memcpy(&st, p + off, sizeof st);
            if (uint64_t(st.symoff) + uint64_t(st.nsyms) * sizeof(nlist_64) > n ||
                uint64_t(st.stroff) + st.strsize > n)
                return false;
            for (uint32_t k = 0; k < st.nsyms; ++k) {
                nlist_64 nl{};
                std::memcpy(&nl, p + st.symoff + size_t(k) * sizeof nl, sizeof nl);
                if (nl.n_type & N_STAB) continue;
                if (!(nl.n_type & N_EXT)) continue;
                const uint8_t t = nl.n_type & N_TYPE;
                if (t == N_UNDF && nl.n_value == 0) continue;
                if (t == N_INDR) return false;
                if (nl.n_un.n_strx >= st.strsize) return false;
                const char* sp = reinterpret_cast<const char*>(p) + st.stroff +
                                 nl.n_un.n_strx;
                std::string name(sp, strnlen(sp, st.strsize - nl.n_un.n_strx));
                const size_t last_space = name.rfind(' ');
                if (last_space != std::string::npos) name.erase(0, last_space + 1);
                if (!name.empty() && name[0] == '_') name.erase(0, 1);
                out->insert(std::move(name));
            }
        }
        off += lc.cmdsize;
    }
    return true;
}

// false = "not modelled exactly; ask nm". A file that cannot be opened is
// fine (nm printed nothing for it either).
bool macho_file_global_defined(const std::string& path,
                               std::unordered_set<std::string>* out) {
    const std::vector<uint8_t> b = read_whole_file(path);
    if (b.empty()) return true;
    if (b.size() < 4) return false;
    uint32_t magic = 0;
    std::memcpy(&magic, b.data(), 4);
    if (magic == MH_MAGIC_64) return macho_slice_global_defined(b.data(), b.size(), out);
    if (magic != FAT_CIGAM) return false;           // FAT_MAGIC, byte-swapped
    fat_header fh{};
    if (b.size() < sizeof fh) return false;
    std::memcpy(&fh, b.data(), sizeof fh);
    const uint32_t na = OSSwapBigToHostInt32(fh.nfat_arch);
    for (uint32_t i = 0; i < na; ++i) {
        fat_arch fa{};
        const size_t at = sizeof fh + size_t(i) * sizeof fa;
        if (at + sizeof fa > b.size()) return false;
        std::memcpy(&fa, b.data() + at, sizeof fa);
        if (static_cast<cpu_type_t>(OSSwapBigToHostInt32(fa.cputype)) != CPU_TYPE_ARM64)
            continue;
        const uint64_t so = OSSwapBigToHostInt32(fa.offset);
        const uint64_t sz = OSSwapBigToHostInt32(fa.size);
        if (so + sz > b.size()) return false;
        return macho_slice_global_defined(b.data() + so, sz, out);
    }
    return false;                                   // no arm64 slice
}

// The in-process twin of the popen loop: the same file list the shell glob
// '<dir>'/*.dylib expands to (no dotfiles) plus '<dir>'/macapk-runtime.
bool runtime_namespace_exports_inproc(const std::string& dir,
                                      std::unordered_set<std::string>* out) {
    std::vector<std::string> files;
    if (DIR* d = opendir(dir.c_str())) {
        while (struct dirent* e = readdir(d)) {
            const std::string nm = e->d_name;
            if (nm.empty() || nm[0] == '.') continue;
            if (nm.size() > 6 && nm.compare(nm.size() - 6, 6, ".dylib") == 0)
                files.push_back(dir + "/" + nm);
        }
        closedir(d);
    }
    files.push_back(dir + "/macapk-runtime");
    std::unordered_set<std::string> acc;
    for (const auto& f : files)
        if (!macho_file_global_defined(f, &acc)) return false;
    out->insert(acc.begin(), acc.end());
    return true;
}

// The runtime namespace's exported names, the ONE implementation both the
// ARM32 import resolver and `--print-runtime-exports` (debug/firstlaunch_gate.sh)
// use: in process when MACAPK_FIRSTLAUNCH_NM_INPROC allows and the files are
// ones it models exactly, else the original nm loop, parsed exactly as before.
void load_runtime_namespace_exports(const std::string& shim_dir,
                                    std::unordered_set<std::string>* out) {
    if (nm_inproc_on() && runtime_namespace_exports_inproc(shim_dir, out))
        return;
    const std::string cmd =
        "for f in '" + shim_dir + "'/*.dylib '" + shim_dir +
        "'/macapk-runtime; do nm -gU \"$f\" 2>/dev/null; done";
    if (FILE* f = popen(cmd.c_str(), "r")) {
        char line[512];
        while (std::fgets(line, sizeof(line), f)) {
            const char* u = std::strrchr(line, ' ');
            if (!u) continue;
            std::string s(u + 1);
            while (!s.empty() && (s.back() == '\n' ||
                                  s.back() == '\r')) s.pop_back();
            if (!s.empty() && s[0] == '_') s.erase(0, 1);
            out->insert(s);
        }
        pclose(f);
    }
}

// Read just the exported (defined, externally-visible) dynamic symbol
// names from an ARM32 .so -- NOT the full macapk::translator::read_elf32(),
// which additionally copies every PT_LOAD segment's bytes, applies
// load-bias relocations, and runs the discovery-seed scans. None of that
// is relevant to "what can another .so import from this one", and
// repeating it for every sibling from every OTHER sibling's translate()
// subprocess would be wasteful for a large plugin lib (libFirebaseCppApp
// is 5.6 MB). Reuses elf_reader.h's already-public structs/constants so
// the byte layout stays authoritative; no elf_reader.cpp change (that
// file is off limits this session; its public header already exposes
// everything this needs).
//
// Filters to STB_GLOBAL/STB_WEAK, matching what a real Bionic dynamic
// linker treats as externally resolvable -- a STB_LOCAL dynsym entry
// (unusual, but not forbidden) is not visible outside its own image
// regardless of SHN_UNDEF-ness.
std::vector<std::string> read_elf32_dynamic_export_names(const std::string& path) {
    std::vector<std::string> names;
    const std::vector<uint8_t> raw = read_whole_file(path);
    if (raw.size() < sizeof(pt::Elf32Ehdr)) return names;

    pt::Elf32Ehdr eh{};
    std::memcpy(&eh, raw.data(), sizeof(eh));
    if (!(eh.e_ident[0] == 0x7f && eh.e_ident[1] == 'E' &&
          eh.e_ident[2] == 'L' && eh.e_ident[3] == 'F'))
        return names;
    // abiDir is single-ABI by construction (the runtime installs one
    // instruction set's .so files per lib/<isa>/ directory), so every sibling
    // this is ever called on should already be ELFCLASS32/little-endian --
    // checked defensively anyway since this reads arbitrary files by name.
    if (eh.e_ident[4] != pt::ELFCLASS32 || eh.e_ident[5] != pt::ELFDATA2LSB)
        return names;
    if (eh.e_shoff == 0 || eh.e_shnum == 0 ||
        eh.e_shentsize < sizeof(pt::Elf32Shdr))
        return names;

    auto read_shdr = [&](uint32_t idx) -> std::optional<pt::Elf32Shdr> {
        const uint64_t off = uint64_t(eh.e_shoff) +
                             uint64_t(idx) * eh.e_shentsize;
        if (off + sizeof(pt::Elf32Shdr) > raw.size()) return std::nullopt;
        pt::Elf32Shdr sh{};
        std::memcpy(&sh, raw.data() + off, sizeof(sh));
        return sh;
    };

    std::optional<pt::Elf32Shdr> dynsym;
    for (uint32_t i = 0; i < eh.e_shnum; ++i) {
        auto sh = read_shdr(i);
        if (!sh) break;
        if (sh->sh_type == pt::SHT_DYNSYM) { dynsym = sh; break; }
    }
    if (!dynsym || dynsym->sh_entsize == 0) return names;
    if (dynsym->sh_link >= eh.e_shnum) return names;
    auto dynstr = read_shdr(dynsym->sh_link);
    if (!dynstr || dynstr->sh_type != pt::SHT_STRTAB) return names;
    if (uint64_t(dynsym->sh_offset) + dynsym->sh_size > raw.size() ||
        uint64_t(dynstr->sh_offset) + dynstr->sh_size > raw.size())
        return names;

    const size_t n = dynsym->sh_size / sizeof(pt::Elf32Sym);
    names.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        const uint64_t off = uint64_t(dynsym->sh_offset) + i * sizeof(pt::Elf32Sym);
        if (off + sizeof(pt::Elf32Sym) > raw.size()) break;
        pt::Elf32Sym es{};
        std::memcpy(&es, raw.data() + off, sizeof(es));
        if (es.st_shndx == 0) continue;      // SHN_UNDEF: an import, not an export
        const uint8_t bind = es.st_info >> 4;
        if (bind != pt::STB_GLOBAL && bind != pt::STB_WEAK) continue;
        if (es.st_name == 0) continue;
        const uint64_t name_off = uint64_t(dynstr->sh_offset) + es.st_name;
        if (name_off >= raw.size()) continue;
        const char* s = reinterpret_cast<const char*>(raw.data() + name_off);
        const size_t maxlen = raw.size() - name_off;
        const size_t len = ::strnlen(s, maxlen);
        if (len == maxlen) continue;          // unterminated: malformed, skip
        names.emplace_back(s, len);
    }
    return names;
}

// DT_NEEDED names of an ARM32 .so, in declaration order, read through
// PT_DYNAMIC exactly as Bionic's linker reads them (linker.cpp
// android14-release, soinfo::prelink_image: DT_STRTAB is a vaddr, each
// DT_NEEDED d_val an offset into it). Lightweight like
// read_elf32_dynamic_export_names: no segment copy, no relocation pass.
// Empty on any malformed input. Used by MACAPK_ARM32_CXXABI_SIBLING_FIRST to
// walk the importer's DT_NEEDED BFS closure over the app's own libraries.
std::vector<std::string> read_elf32_dt_needed(const std::string& path) {
    std::vector<std::string> out;
    const std::vector<uint8_t> raw = read_whole_file(path);
    if (raw.size() < sizeof(pt::Elf32Ehdr)) return out;
    pt::Elf32Ehdr eh{};
    std::memcpy(&eh, raw.data(), sizeof(eh));
    if (!(eh.e_ident[0] == 0x7f && eh.e_ident[1] == 'E' &&
          eh.e_ident[2] == 'L' && eh.e_ident[3] == 'F'))
        return out;
    if (eh.e_ident[4] != pt::ELFCLASS32 || eh.e_ident[5] != pt::ELFDATA2LSB)
        return out;
    if (eh.e_phentsize < sizeof(pt::Elf32Phdr)) return out;
    std::vector<pt::Elf32Phdr> ph;
    for (uint32_t i = 0; i < eh.e_phnum; ++i) {
        const uint64_t off = uint64_t(eh.e_phoff) + uint64_t(i) * eh.e_phentsize;
        if (off + sizeof(pt::Elf32Phdr) > raw.size()) return out;
        pt::Elf32Phdr p{};
        std::memcpy(&p, raw.data() + off, sizeof(p));
        ph.push_back(p);
    }
    auto va2off = [&](uint32_t va, uint64_t* o) -> bool {
        for (const auto& p : ph)
            if (p.p_type == pt::PT_LOAD && va >= p.p_vaddr &&
                va - p.p_vaddr < p.p_filesz) {
                *o = uint64_t(p.p_offset) + (va - p.p_vaddr);
                return true;
            }
        return false;
    };
    const pt::Elf32Phdr* dyn = nullptr;
    for (const auto& p : ph) if (p.p_type == pt::PT_DYNAMIC) { dyn = &p; break; }
    if (!dyn) return out;
    std::vector<uint32_t> needed_off;
    uint32_t strtab_va = 0;
    bool have_strtab = false;
    for (uint32_t i = 0; i + 8 <= dyn->p_filesz; i += 8) {
        const uint64_t o = uint64_t(dyn->p_offset) + i;
        if (o + 8 > raw.size()) break;
        int32_t tag; uint32_t val;
        std::memcpy(&tag, raw.data() + o, 4);
        std::memcpy(&val, raw.data() + o + 4, 4);
        if (tag == 0) break;                           // DT_NULL
        if (tag == 1) needed_off.push_back(val);        // DT_NEEDED
        if (tag == 5) { strtab_va = val; have_strtab = true; }   // DT_STRTAB
    }
    uint64_t str_off = 0;
    if (!have_strtab || !va2off(strtab_va, &str_off)) return out;
    for (uint32_t n : needed_off) {
        const uint64_t o = str_off + n;
        if (o >= raw.size()) continue;
        const char* s = reinterpret_cast<const char*>(raw.data() + o);
        const size_t len = ::strnlen(s, raw.size() - o);
        if (len == raw.size() - o) continue;
        out.emplace_back(s, len);
    }
    return out;
}

// MACAPK_ARM32_CXXABI_SIBLING_FIRST: is `n` part of the Itanium C++ runtime
// ABI that NO Android system library exports? On a device such a name can
// only be defined by one of the app's own libraries (its libc++_shared.so /
// libsupc++ copy), so Bionic's BFS over the importer's DT_NEEDED closure
// (linker_soinfo.cpp android14-release :60-80 lookup list, :119-205 first
// definer wins) always lands on an APK library. The exceptions are exactly
// what Bionic's system libraries DO export (libc.map.txt: __cxa_atexit,
// __cxa_finalize, __cxa_thread_atexit_impl, and on ARM the _Unwind_* /
// __gnu_Unwind_* / __aeabi_unwind_cpp_pr* family; libstdc++.map.txt:
// __cxa_guard_{acquire,release,abort}, __cxa_pure_virtual, new/delete,
// _ZSt7nothrow), so those are excluded -- they keep our shim, which IS our
// implementation of those system libraries.
bool is_cxx_runtime_abi_name(const std::string& n) {
    static const std::unordered_set<std::string> kBionicProvided = {
        "__cxa_atexit", "__cxa_finalize", "__cxa_thread_atexit_impl",
        "__cxa_guard_acquire", "__cxa_guard_release", "__cxa_guard_abort",
        "__cxa_pure_virtual", "_ZSt7nothrow",
    };
    if (kBionicProvided.count(n)) return false;
    if (n == "__dynamic_cast" || n == "__gxx_personality_v0") return true;
    if (n.rfind("__cxa_", 0) == 0) return true;
    for (const char* p : {"_ZSt", "_ZNSt", "_ZNKSt", "_ZTISt", "_ZTSSt", "_ZTVSt",
                          "_ZN10__cxxabiv1", "_ZTIN10__cxxabiv1",
                          "_ZTSN10__cxxabiv1", "_ZTVN10__cxxabiv1"})
        if (n.rfind(p, 0) == 0) return true;
    return false;
}

// The per-app cache suffix of a translated dylib, recovered from the output
// path the same way the cross-guest-library block below does it
// (AppClassLoader names the output "lib" + <libname> + <suffix> + ".dylib").
std::string derive_cache_suffix(const std::string& in_path,
                                const std::string& out_path) {
    const std::string in_base = basename_of(in_path);
    const std::string out_base = basename_of(out_path);
    if (in_base.size() <= 6 || in_base.compare(0, 3, "lib") != 0 ||
        in_base.compare(in_base.size() - 3, 3, ".so") != 0)
        return {};
    const std::string prefix = "lib" + in_base.substr(3, in_base.size() - 6);
    const std::string ext = ".dylib";
    if (out_base.size() < prefix.size() + ext.size() ||
        out_base.compare(0, prefix.size(), prefix) != 0 ||
        out_base.compare(out_base.size() - ext.size(), ext.size(), ext) != 0)
        return {};
    return out_base.substr(prefix.size(),
                           out_base.size() - prefix.size() - ext.size());
}

// DIAGNOSTIC A/B gate for the v1.0.828 literal-pool escape (see the two
// `is_in_literal_pool` vetoes below). MACAPK_ARM32_POOL_ESCAPE_828=0 turns the
// escape OFF so the same translator binary can emit a discovery sidecar both
// ways and the two function sets can be diffed. That diff is the only way to
// measure the carve-out's FALSE-POSITIVE rate: the escape admits code on the
// strength of "the ARM pool does not mark it", and a bad admission lifts data
// as a function, which shows up as a synth stub faulting on a string byte used
// as a pointer. Rebuilding at the prior revision would answer the same
// question, but it also drags in every other delta between the two revisions,
// so a gate on this one clause is the cleaner experiment.
// Default ON, i.e. shipping behaviour is unchanged when the env is unset.
// v1.0.833: observability for the function-size CEILING.
//
// Discovery never works out where a function ENDS. It takes the distance to
// `next_real_function_start` -- literally the next address some other
// mechanism already believes is a start -- and clamps it to 0x4000. A body
// that comes out at exactly 0x4000 therefore means "no end was found and we
// ran to the ceiling", which is the signature of data being lifted as code.
//
// NOTHING REPORTED THIS. The only counter with "capped" in its name
// (`n_capped`, in the sidecar/report path below) is scoped to a reporting
// fallback that never compiles anything, so the five real lift rounds could
// clamp freely and silently. Measured across the fleet, HCR 1.68.1 alone hits
// it 291 times including a 141-synth back-to-back run packed into 2.7 KB, and
// 94-100% of clamp hits per binary have NO .ARM.exidx entry backing them as a
// real function start. Pure logging; changes no behaviour.
// See docs/arm32-discovery-boundary-defect_2026-07-29.md.
static unsigned long g_size_clamp_hits = 0;

static bool pool_escape_828_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_POOL_ESCAPE_828");
        return !(e && e[0] == '0');
    }();
    return on;
}

// =====================================================================
// libstdc++ THROW HELPERS on the ARM32 path (the `pure_throw_helpers` scan in
// run_pipeline). Two gates, both read at TRANSLATION time. The dylib cache is
// keyed on NATIVE_ID only, so an A/B must drop lib_native/<lib>.dylib (or its
// .nativeid) between arms, the same caveat as MACAPK_COCOS_PNG_OVERRIDE.
// Evidence + method: docs/throw-helper-override-retired_2026-09-26.md;
// offline gate: debug/geh_pure-thrower-autodetect-override_gate.sh.
//
// MACAPK_ARM32_THROW_HELPERS_RUN (default ON; =0 re-applies `return 0` to the
// helpers the CURRENT classifier keeps -- byte-identical to the v1.0.24
// override only together with MACAPK_ARM32_NORETURN_REACH=0)
// ---------------------------------------------------------------------
// ON: every helper the scan finds (std::__throw_length_error and friends) is
// translated from the app's own bytes like any other function, so it really
// throws. That is what a device runs. The helpers are declared __noreturn__
// (gcc libstdc++-v3/include/bits/functexcept.h:52-75), their body is
// `_GLIBCXX_THROW_OR_ABORT(length_error(_(__s)))`, i.e. `throw`, and Android
// never scans app code at load or rewrites a body. The throw reaches our
// __cxa_throw override (__macapk_lifted_throw_recover), exactly as every inline
// `throw` in the same image already does.
//
// Bug being retired: v1.0.24 (2026-05-18) replaced each helper's body with
// `return 0` so that a throw CAUSED by our missing CallStaticBooleanMethodV
// would not abort HCR 1.43's nativeInit; the entry calls it a throw-suppression
// layer "until the JNI gap is plugged", and v1.0.25 plugged it. The override
// made a __noreturn__ function return, silently, and its classifier also listed
// ordinary functions (see MACAPK_ARM32_NORETURN_REACH below), so it deleted
// __gnu_cxx::__scoped_lock's lock/unlock on Jetpack Joyride and
// std::vector<float>::vector(size_t) on GD Meltdown. On HCR 1.8.1, the protected
// ARM32 row, it replaced exactly two bodies: one with no reference at all and
// one reached only when malloc AND the emergency-pool mutex both fail.
// Removal condition: the sweep is BLIND to this change (hcr181's two bodies are
// unreachable, gdlite-v7a is xfail), so sweeps cannot retire it. Retire when
// gate `purethrow` is green AND one gdlite-v7a and one Jetpack Joyride boot-log
// A/B (bodies default vs =0) show no new `__macapk_lifted_throw_recover`
// "recovering via siglongjmp" lines -- JJ now takes real locale/emergency-pool
// mutex locks where before it took none, the change most likely to show live.
// Then delete the gate and the two `ret i32 0` emitters in run_pipeline.
static bool arm32_throw_helpers_run() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_THROW_HELPERS_RUN");
        return !(e && e[0] == '0');
    }();
    return on;
}

// MACAPK_ARM32_NORETURN_REACH (default ON; =0 restores the v1.0.24 classifier
// and the v1.0.62 terminator checks exactly)
// ---------------------------------------------------------------------
// The scan's result is a DISCOVERY fact: `bl <helper>` does not return, so the
// terminator checks treat it as the end of the caller's body. ON adds three
// generic rules, each only ever REMOVING a helper or a terminator:
//   1. REACHABILITY: a candidate is kept only if no exit is reachable from its
//      entry without passing an unconditional `bl __cxa_throw` -- the standard
//      non-returning-function analysis (throw_helper_exit_reachable below).
//      v1.0.24 assumed "non-throwers do NOT call __cxa_allocate_exception". But
//      gcc's ext/concurrence.h declares __throw_concurrence_lock_error INLINE
//      (gcc-4.9.4 :100-102) and __mutex::lock calls it when the lock fails
//      (:152-153), and libc++ declares __throw_length_error inline too
//      (<stdexcept>, release/10.x :247-251), so __scoped_lock's ctor and
//      vector::__push_back_slow_path allocate an exception within 0x20 bytes of
//      their start and still return normally.
//   2. SYMBOL TRUST: when a `bl __cxa_throw` lies inside a sized function
//      symbol, the backward walk's start must BE that symbol's start (else it
//      is a literal pool or a block inside the function, not an entry), and the
//      reachability window is the symbol's own extent.
//   3. AL ONLY: a conditional call to a helper (ARM `blne`, or a Thumb `bl` that
//      ends an IT block) falls through when the condition fails, so it is not a
//      terminator -- the same mistake v1.0.553 fixed for conditional returns.
static bool arm32_noreturn_reach_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_NORETURN_REACH");
        return !(e && e[0] == '0');
    }();
    return on;
}

// MACAPK_ARM32_EXPORT_ALIASES (default ON; =0 is byte-identical to the
// pre-change translator on every library)
// ---------------------------------------------------------------------
// Export EVERY defined GLOBAL/WEAK STT_FUNC .dynsym name whose entry is a
// translated function start, not just the one name discovery kept for that
// address. Bionic resolves an import by NAME and binds it at st_value +
// load_bias (linker_soinfo.cpp gnu_lookup/elf_lookup + linker_relocate.h
// is_symbol_global_and_defined; linker_soinfo.h resolve_symbol_address), so a
// C1/C2 ctor pair, a D1/D2 dtor pair or two identical-code-folded functions
// are ordinary exports of one address on a device. lifted_address_map() is
// last-wins per address, so arm32rc exported ONE of them and every import of
// another failed its flat bind: OpenTTD's libapplication.dylib could not
// dlopen at all (std::__ndk1::locale::locale() C1, runtime_error(const
// string&) C1, __shared_weak_count D2, ...). Corpus: 17,179 such names in 78
// of 246 ARM32 libraries (debug/arm32alias_verify/census.py).
// docs/arm32rc-alias-exports_2026-09-27.md; gate `arm32alias`.
static bool arm32_export_aliases_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_EXPORT_ALIASES");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}

// MACAPK_ARM32_SIZELESS_EXPORTS (default ON; =0 restores the pre-change
// discovery seed list exactly)
// ---------------------------------------------------------------------
// A defined GLOBAL/WEAK STT_FUNC .dynsym export with st_size == 0 -- hand-
// written assembly with no `.size` directive, e.g. libc++abi's ARM EHABI
// `__cxa_end_cleanup` -- is an entry point like any other to Bionic (nothing
// in the lookup reads st_size for a FUNC), but discovery lifts only SIZED
// symbols, so such a function was translated only if some other seed happened
// to reach it. It is now a discovery seed of its own, in the mode its own
// st_value bit 0 declares (AAELF32 "Symbol Values": an STT_FUNC that addresses
// a Thumb instruction has bit 0 set; relocation uses st_value & ~1), sized by
// the same next-known-start rule every seed uses, and
// MACAPK_ARM32_EXPORT_ALIASES then exports its ELF name. Corpus: 485 in 77 of
// 246 ARM32 libraries.
static bool arm32_sizeless_exports_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_SIZELESS_EXPORTS");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}

// What throw_helper_exit_reachable() found first on a path from the entry.
enum class ThrowHelperExit { None, Return, Indirect, Escape, Undecodable };
static const char* throw_helper_exit_name(ThrowHelperExit k) {
    switch (k) {
        case ThrowHelperExit::None:        return "none";
        case ThrowHelperExit::Return:      return "return";
        case ThrowHelperExit::Indirect:    return "indirect";
        case ThrowHelperExit::Escape:      return "escape";
        case ThrowHelperExit::Undecodable: return "undecodable";
    }
    return "?";
}

// Non-returning-function analysis for ONE throw-helper candidate. Explores the
// intra-procedural CFG from `entry` (both arms of every conditional branch and
// CBZ/CBNZ), bounded to [entry, win_end) and to the executable segment, and
// reports the first EXIT reachable without passing an UNCONDITIONAL
// `bl __cxa_throw` (an address in throw_pcs; an IT-predicated one falls
// through). An exit is:
//   Return      pop/ldm off sp with pc, bx lr, mov pc,lr, ldr pc,[sp],#N!
//   Indirect    any other PC write (bx rN, mov pc,rN, ldr pc,[..], add pc,..,
//               ldm rN,{..pc}, tbb/tbh) -- it may return
//   Escape      a path or branch target leaving the window
//   Undecodable a path running into bytes capstone cannot decode
// Only None means "no-return". When in doubt the helper MAY return, so every
// unknown counts as an exit. Each path is decoded linearly from its own start,
// so a Thumb IT block is predicated as the CPU would see it from there (capstone
// resets IT state per cs_disasm call). `bl`/`blx` are calls and fall through.
// debug/throwhelper_scan.py (Reach.exit_from) is a line-for-line replica.
static ThrowHelperExit throw_helper_exit_reachable(
        const std::vector<pt::CodeSegment>& loadable, uint32_t entry, bool thumb,
        uint32_t win_end, const std::unordered_set<uint32_t>& throw_pcs,
        uint32_t* where) {
    *where = entry;
    const pt::CodeSegment* seg = nullptr;
    for (const auto& s : loadable) {
        if (s.executable && entry >= s.vaddr
            && entry - s.vaddr < s.bytes.size()) { seg = &s; break; }
    }
    if (!seg) return ThrowHelperExit::Escape;
    const uint32_t seg_end = seg->vaddr + static_cast<uint32_t>(seg->bytes.size());
    if (win_end > seg_end) win_end = seg_end;
    csh cs;
    if (cs_open(CS_ARCH_ARM, thumb ? CS_MODE_THUMB : CS_MODE_ARM, &cs) != CS_ERR_OK)
        return ThrowHelperExit::Undecodable;          // cannot judge: may return
    cs_option(cs, CS_OPT_DETAIL, CS_OPT_ON);
    ThrowHelperExit verdict = ThrowHelperExit::None;
    auto exit_at = [&](ThrowHelperExit k, uint32_t a) { verdict = k; *where = a; };
    std::unordered_set<uint32_t> seen;
    std::vector<uint32_t> work{entry};
    while (!work.empty() && verdict == ThrowHelperExit::None) {
        const uint32_t start = work.back();
        work.pop_back();
        if (seen.count(start)) continue;
        if (start < entry || start >= win_end) { exit_at(ThrowHelperExit::Escape, start); break; }
        cs_insn* ins = nullptr;
        const size_t n = cs_disasm(cs, seg->bytes.data() + (start - seg->vaddr),
                                   win_end - start, start, 0, &ins);
        uint32_t a = start;
        bool ended = false;
        for (size_t i = 0; i < n && verdict == ThrowHelperExit::None; ++i) {
            const cs_insn& in = ins[i];
            if (in.address != a) break;
            if (seen.count(a)) { ended = true; break; }
            seen.insert(a);
            const cs_arm& d = in.detail->arm;
            const bool uncond = d.cc == ARM_CC_AL || d.cc == ARM_CC_INVALID;
            const unsigned id = in.id;
            if (uncond && throw_pcs.count(a)) { ended = true; break; }  // the throw
            if (id == ARM_INS_B || id == ARM_INS_CBZ || id == ARM_INS_CBNZ) {
                const cs_arm_op* op = d.op_count ? &d.operands[d.op_count - 1] : nullptr;
                if (!op || op->type != ARM_OP_IMM) { exit_at(ThrowHelperExit::Indirect, a); break; }
                work.push_back(static_cast<uint32_t>(op->imm));
                if (id == ARM_INS_B && uncond) { ended = true; break; }
                a += in.size;
                continue;
            }
            if (id == ARM_INS_BL || id == ARM_INS_BLX) { a += in.size; continue; }  // a call returns
            if (id == ARM_INS_TBB || id == ARM_INS_TBH) { exit_at(ThrowHelperExit::Indirect, a); break; }
            cs_regs rr, rw;
            uint8_t nr = 0, nw = 0;
            bool writes_pc = false;
            if (cs_regs_access(cs, &in, rr, &nr, rw, &nw) == CS_ERR_OK) {
                for (uint8_t k = 0; k < nw; ++k)
                    if (rw[k] == ARM_REG_PC) writes_pc = true;
            }
            if (writes_pc) {
                int regs[40];
                int nregs = 0;
                const cs_arm_op* mem = nullptr;
                for (uint8_t k = 0; k < d.op_count; ++k) {
                    if (d.operands[k].type == ARM_OP_REG && nregs < 40)
                        regs[nregs++] = d.operands[k].reg;
                    else if (d.operands[k].type == ARM_OP_MEM && !mem)
                        mem = &d.operands[k];
                }
                bool list_has_pc = false;
                for (int k = 0; k < nregs; ++k)
                    if (regs[k] == ARM_REG_PC) list_has_pc = true;
                const bool is_list = id == ARM_INS_POP || id == ARM_INS_LDM
                    || id == ARM_INS_LDMDA || id == ARM_INS_LDMDB || id == ARM_INS_LDMIB;
                bool ret = false;
                if (is_list && list_has_pc)
                    ret = id == ARM_INS_POP || (nregs > 0 && regs[0] == ARM_REG_SP);
                else if (id == ARM_INS_BX && nregs > 0 && regs[0] == ARM_REG_LR)
                    ret = true;
                else if (id == ARM_INS_MOV && nregs == 2 && regs[1] == ARM_REG_LR)
                    ret = true;
                else if (id == ARM_INS_LDR && mem && mem->mem.base == ARM_REG_SP && d.writeback)
                    ret = true;
                exit_at(ret ? ThrowHelperExit::Return : ThrowHelperExit::Indirect, a);
                break;
            }
            a += in.size;
        }
        if (n) cs_free(ins, n);
        if (verdict != ThrowHelperExit::None) break;
        if (!ended) {
            if (a >= win_end) exit_at(ThrowHelperExit::Escape, a);
            else if (!seen.count(a)) exit_at(ThrowHelperExit::Undecodable, a);
        }
    }
    cs_close(&cs);
    return verdict;
}

// Is the Thumb `bl` at bl_pc the LAST instruction of an IT block, i.e. does it
// run only when the IT condition holds? The BL T1 encoding carries no condition
// field; IT is the only way to predicate it, and a PC-setting instruction in an
// IT block that is not the block's LAST instruction is UNPREDICTABLE (LLVM
// lib/Target/ARM/AsmParser/ARMAsmParser.cpp rejects it: "instruction must be
// outside of IT block or the last instruction in an IT block"). So try each
// plausible IT position up to 4 instructions back and accept only a decode
// whose IT block (its length read from the mask) ends exactly at this BL.
static bool thumb_bl_is_it_predicated(const pt::Elf32Image& img, uint32_t bl_pc) {
    csh cs;
    if (cs_open(CS_ARCH_ARM, CS_MODE_THUMB, &cs) != CS_ERR_OK) return false;
    cs_option(cs, CS_OPT_DETAIL, CS_OPT_ON);
    bool pred = false;
    // 14 = IT (2 bytes) + three 32-bit instructions: the longest `itttt` prefix
    // that still leaves the BL as the block's last slot.
    for (uint32_t back = 2; back <= 14 && !pred; back += 2) {
        if (bl_pc < back) break;
        auto code = img.code_at(bl_pc - back, back + 4);
        if (code.size() < back + 4) continue;
        const uint32_t hw = uint32_t(code[0]) | (uint32_t(code[1]) << 8);
        if ((hw & 0xff00u) != 0xbf00u || (hw & 0xfu) == 0) continue;   // not an IT
        const unsigned block_len = 4u - static_cast<unsigned>(__builtin_ctz(hw & 0xfu));
        cs_insn* ins = nullptr;
        const size_t n = cs_disasm(cs, code.data(), code.size(), bl_pc - back, 0, &ins);
        if (n >= 2 && n - 1 == block_len && ins[n - 1].address == bl_pc
            && ins[n - 1].id == ARM_INS_BL) {
            const auto cc = ins[n - 1].detail->arm.cc;
            pred = cc != ARM_CC_AL && cc != ARM_CC_INVALID;
        }
        if (n) cs_free(ins, n);
    }
    cs_close(&cs);
    return pred;
}

// ---------------------------------------------------------------------------
// MACAPK_ARM32_THUMB_PARITY (default ON; =0 restores the pre-change discovery
// byte for byte, including the narrow v1.0.1655 `str.w r11,[sp,#-4]!` idiom
// that this generalises).
//
// Discovery reads the halfword BEFORE a candidate function start as a Thumb-16
// terminator (`pop {..,pc}`, `bx lr`, `b imm11`, `bx Rm`) and the word before it
// as a Thumb-2 one (`pop.w`, `b.w`, `blx`, `bl <noreturn>`), and it walks
// backwards over Thumb NOPs. Every one of those readings silently assumed that
// the halfword it decodes BEGINS an instruction. When it is instead the SECOND
// halfword of a 32-bit Thumb-2 instruction it is an operand field and any
// reading of it is a coincidence: `str.w r11,[sp,#-4]!` is f84d bd04 and its
// tail reads as `pop {r2,pc}`; `ldr.w lr,[pc,#0x34]` is f8df e034 and its tail
// reads as `b imm11`; an `ldr.w r11,[rN,#0xf00]` tail reads as `nop`. Each
// such reading admits the NEXT address as a function entry and caps the real
// function that contains it (docs/arm32-thumb-parity-and-thumb-seed_2026-09-28.md).
//
// Thumb instruction length is decided by the FIRST halfword alone. ARM ARM DDI
// 0406C.b A6.1 "Thumb instruction set encoding": if bits [15:11] of the
// halfword being decoded are 0b11101, 0b11110 or 0b11111 it is the first
// halfword of a 32-bit instruction, otherwise it is a 16-bit instruction. LLVM
// states the same rule as a comparison (lib/Target/ARM/Disassembler/
// ARMDisassembler.cpp, suggestBytesToSkip: "a halfword is a standalone 2-byte
// instruction if and only if its value is less than 0xE800").
//
// Consequence used here: in an instruction stream, the halfword AFTER any
// halfword below 0xE800 begins an instruction, because such a halfword is
// either a whole 16-bit instruction or the LAST halfword of a 32-bit one --
// never a first halfword. So take the maximal run of halfwords >= 0xE800 that
// ends at hw_addr-2: its earliest element begins an instruction, every element
// is a first halfword, so they pair up front to back, and hw_addr-2 is a FIRST
// halfword (making hw_addr its tail) exactly when the run length is ODD. This
// is exact inside code. Backward Thumb decoding is otherwise ambiguous (f84d is
// also a legal BL second halfword, which is why the old idiom check could not
// be widened), and the run is what resolves it.
//
// Returns false -- "not proven", i.e. keep the historical reading -- when the
// walk leaves the executable segment, meets a Thumb literal-pool mark (data is
// not an instruction stream, so a parity computed through it means nothing) or
// exceeds kMaxRun halfwords. So the rule can only REFUSE a coincidental
// reading; it never invents one.
//
// Applied ONLY to terminator/padding readings BEFORE a candidate, never to the
// candidate start itself: the halfwords before a terminator are the end of the
// previous function's code, where the run is exact, but a real function entry
// is routinely preceded by a literal pool whose words (0xffffxxxx offsets) look
// like first halfwords -- on GD Lite's libfmod 757 discovered Thumb starts
// have an odd run before them and the sampled ones are real entries after a
// pool. The pool-mark stop is a measured trade: without it the rule also
// refused coincidental readings INSIDE pool data that happened to admit real
// entries (corpus: 256 .ARM.exidx-confirmed starts lost, e.g. Subway
// libcrashlytics-handler 0xee78 behind the "b.w" f7f7 be22 in its pool); with
// it the loss is 19 against 52 gained, and 5,400 of the 5,583 phantom starts
// inside exidx-described functions are still removed. The cost: a false Thumb
// pool mark on the f84d of the str.w idiom (libfmod 0xd6740) keeps that one
// reading, which MACAPK_ARM32_STRW_TAIL_NOT_POP still catches.
static bool arm32_thumb_parity_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_THUMB_PARITY");
        return !(e && e[0] == '0');
    }();
    return on;
}
// Distinct addresses whose coincidental Thumb-16 terminator reading (pop
// {..,pc} / bx lr / b imm11 / bx Rm) the rule refused, recorded only by the
// two `is_preceded_by_terminator[_early]` lambdas (the 32-bit / vaddr-4
// reading returns false without recording, since it has no separate summary
// line), for the one-line summary printed once the prologue scan finishes --
// not the final BL/synth rounds that follow it.
static std::set<uint32_t> g_thumb_parity_refused;
// The Thumb literal-pool mark set, once discovery has built it (null before).
static const std::set<uint32_t>* g_parity_pool_t = nullptr;
static bool thumb_hw_is_second_half(const pt::Elf32Image& img, uint32_t hw_addr) {
    if (!arm32_thumb_parity_on() || (hw_addr & 1u)) return false;
    const pt::CodeSegment* seg = nullptr;
    for (const auto& s : img.loadable) {
        if (s.executable && hw_addr >= s.vaddr
            && hw_addr - s.vaddr + 2 <= s.bytes.size()) { seg = &s; break; }
    }
    if (!seg) return false;
    constexpr unsigned kMaxRun = 64;
    unsigned run = 0;
    uint32_t a = hw_addr;
    for (;;) {
        if (a < seg->vaddr + 2) return false;          // stream start unknown
        const uint32_t h = a - 2;
        if (g_parity_pool_t && g_parity_pool_t->count(h))
            return false;                              // data: stream broken
        const size_t off = h - seg->vaddr;
        const uint16_t hw = uint16_t(seg->bytes[off])
                          | uint16_t(seg->bytes[off + 1] << 8);
        if (hw < 0xE800u) break;                       // sync point
        if (++run > kMaxRun) return false;
        a = h;
    }
    return (run & 1u) != 0;
}

// ---------------------------------------------------------------------------
// cocos2d::CCImage member layout, read from the binary's OWN constructor
// (MACAPK_COCOS_PNG_LAYOUT_CHECK, default ON; see the _initWithPngData override).
//
// WHY. The host-libpng override __macapk_shim_cocos_init_png_data writes the
// decoded image into CCImage at FIXED offsets taken from ONE build, HCR 1.8.1
// (bionic_shim.cpp: wr16 +0x12 width, +0x14 height, wr32 +0x18 bpc, +0x1c data,
// +0x20 hasAlpha, +0x21 preMulti). The override used to be installed on ANY
// ARM32 binary exporting the symbol, matched by NAME alone. The layout is per
// BUILD, not a cocos2d-x v2 invariant -- CCImage's first member is +0x12 on HCR
// 1.8.1, +0x14 on stock 2.2.6 (CCImage.h:159-167 after CCObject's 0x14), +0x30 on
// GD Lite 2.2.11 and +0x34 on GD Meltdown 2.2.147
// (docs/gdlite-arm32-never-rendered_2026-09-26.md section 1b). On GD Lite the
// shim therefore left width/height/data at the ctor's zeros (every texture 0x0:
// the never-rendered blank, and with reclaim ON the ceilf(w/0) -> INT_MAX
// ground-sprite loop) and its wr16(+0x12) landed on the HIGH half of the RobTop
// CCObject refcount at +0x10 (every CCImage leaked).
//
// WHAT. The ctor (_ZN7cocos2d7CCImageC2Ev, else C1) calls CCObject::CCObject and
// then zero-initialises the members in declaration order:
//   unsigned short m_nWidth, m_nHeight; int m_nBitsPerComponent;
//   unsigned char* m_pData; bool m_bHasAlpha, m_bPreMulti;
// so, after the FIRST call, the first two STRH through `this` are width/height,
// the first two word STRs bpc/data, the first two STRB alpha/premulti -- in
// INSTRUCTION order. Not sorted by offset: Meltdown's ctor ends with a third
// `strh r0,[r4,#0x1e]` back into the CCObject header (@0x65489c), which an
// offset sort would read as the width. The layout is only reported when all six
// were found; anything else is UNPARSED, and an unparsed layout never matches.
// ---------------------------------------------------------------------------
struct CocosCCImageLayout {
    bool     parsed = false;
    uint32_t width = 0, height = 0, bpc = 0, data = 0, alpha = 0, premulti = 0;
    std::string why;   // UNPARSED reason, or the ctor symbol that was read
};
// The one layout __macapk_shim_cocos_init_png_data writes (HCR 1.8.1's ctor at
// 0x174810 stores exactly these; see bionic_shim.cpp's wr16/wr32 calls).
static constexpr uint32_t kShimCCImageLayout[6] = {0x12, 0x14, 0x18, 0x1c, 0x20, 0x21};

static CocosCCImageLayout cocos_ccimage_layout(const pt::Elf32Image& img) {
    CocosCCImageLayout out;
    const pt::Symbol* ctor = nullptr;
    for (const char* want : {"_ZN7cocos2d7CCImageC2Ev", "_ZN7cocos2d7CCImageC1Ev"}) {
        for (const auto& s : img.symbols)
            if (s.name == want && s.size) { ctor = &s; break; }
        if (ctor) break;
    }
    if (!ctor) { out.why = "no CCImage::CCImage() symbol"; return out; }
    out.why = ctor->name;
    size_t n = 0;
    auto code = img.code_for(*ctor, &n);
    if (code.empty() || n == 0) { out.why = "ctor bytes not in a PT_LOAD"; return out; }
    csh cs;
    if (cs_open(CS_ARCH_ARM, ctor->is_thumb ? CS_MODE_THUMB : CS_MODE_ARM, &cs) != CS_ERR_OK) {
        out.why = "capstone open failed"; return out;
    }
    cs_option(cs, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn* ins = nullptr;
    const size_t cnt = cs_disasm(cs, code.data(), n, (ctor->address & ~1u), 0, &ins);
    int this_reg = ARM_REG_R0;        // `this` arrives in r0 (AAPCS)
    bool after_call = false;
    std::vector<uint32_t> h, w, b;    // STRH / STR / STRB displacements, in order
    for (size_t i = 0; i < cnt; ++i) {
        const cs_arm& a = ins[i].detail->arm;
        const unsigned id = ins[i].id;
        if (!after_call) {
            // `mov rX, r0` before the base-ctor call parks `this` in a callee-saved reg.
            if ((id == ARM_INS_MOV) && a.op_count == 2
                && a.operands[0].type == ARM_OP_REG && a.operands[1].type == ARM_OP_REG
                && a.operands[1].reg == ARM_REG_R0)
                this_reg = a.operands[0].reg;
            if (id == ARM_INS_BL || id == ARM_INS_BLX) after_call = true;
            continue;
        }
        if (id == ARM_INS_POP || id == ARM_INS_BX || id == ARM_INS_LDM
            || id == ARM_INS_B) break;                    // end of the member-init block
        if (id != ARM_INS_STRH && id != ARM_INS_STR && id != ARM_INS_STRB
            && id != ARM_INS_STRD) continue;
        const cs_arm_op* mem = nullptr;
        for (uint8_t k = 0; k < a.op_count; ++k)
            if (a.operands[k].type == ARM_OP_MEM) mem = &a.operands[k];
        if (!mem || mem->mem.base != this_reg || mem->mem.index != ARM_REG_INVALID
            || mem->mem.disp <= 0 || mem->subtracted || a.writeback) continue;
        const uint32_t d = static_cast<uint32_t>(mem->mem.disp);
        if (id == ARM_INS_STRH) h.push_back(d);
        else if (id == ARM_INS_STRB) b.push_back(d);
        else if (id == ARM_INS_STR) w.push_back(d);
        else { w.push_back(d); w.push_back(d + 4); }       // STRD: two adjacent words
    }
    if (ins) cs_free(ins, cnt);
    cs_close(&cs);
    if (h.size() < 2 || w.size() < 2 || b.size() < 2) {
        char buf[128];
        std::snprintf(buf, sizeof buf, "%s: %zu strh / %zu str / %zu strb after the base call",
                      ctor->name.c_str(), h.size(), w.size(), b.size());
        out.why = buf;
        return out;
    }
    out.parsed = true;
    out.width = h[0]; out.height = h[1];
    out.bpc = w[0];   out.data = w[1];
    out.alpha = b[0]; out.premulti = b[1];
    return out;
}
static bool cocos_ccimage_layout_matches_shim(const CocosCCImageLayout& l) {
    return l.parsed
        && l.width == kShimCCImageLayout[0] && l.height == kShimCCImageLayout[1]
        && l.bpc == kShimCCImageLayout[2]   && l.data == kShimCCImageLayout[3]
        && l.alpha == kShimCCImageLayout[4] && l.premulti == kShimCCImageLayout[5];
}
// MACAPK_COCOS_PNG_LAYOUT_CHECK=0: the pre-change rule, override on the name alone.
static bool cocos_png_layout_check_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_COCOS_PNG_LAYOUT_CHECK");
        return !(e && e[0] == '0');
    }();
    return on;
}
// MACAPK_COCOS_PNG_OVERRIDE=0 (DIAGNOSTIC, default on): never install the
// override, so every build runs its own statically linked libpng as a device
// does. It exists for the retirement A/B the gdlite note prescribes: the
// override's only recorded justification is a libpng miscompile on the LLVM
// lifter, which was deleted at v1.0.802. If hcr181 stays RICH with this at 0,
// delete the override; if it breaks, arm32rc has a real libpng miscompile.
static bool cocos_png_override_allowed() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_COCOS_PNG_OVERRIDE");
        return !(e && e[0] == '0');
    }();
    return on;
}
// The decision, in one place, for the pipeline AND the --probe-ccimage-layout
// gate hook (so the offline gate exercises exactly the code that decides).
static bool cocos_png_override_decision(const pt::Elf32Image& img,
                                        CocosCCImageLayout* layout_out) {
    CocosCCImageLayout l = cocos_ccimage_layout(img);
    if (layout_out) *layout_out = l;
    if (!cocos_png_override_allowed()) return false;
    if (!cocos_png_layout_check_on()) return true;
    return cocos_ccimage_layout_matches_shim(l);
}

// =====================================================================
// MACAPK_COCOS_OWN_FULLPATH (default ON; =0 restores the override exactly)
// ---------------------------------------------------------------------
// ON: cocos2d-x v2's exported CCFileUtils::fullPathFromRelativePath(const
// char*) is translated from the guest's OWN bytes like every other function.
// That is what a device runs: Android never replaces app code, the app's
// compiled function simply executes. cocos2d-x 2.0.1's Android body is
// `return pszRelativePath;` (cocos2dx/platform/android/CCFileUtils.cpp:81-84;
// the 1-arg overload in CCFileUtilsCommon_cpp.h:401-405 forwards to the 2-arg
// with a stack ccResolutionType), and HCR 1.8.1 ships exactly that: the 1-arg
// wrapper at 0x172ce4 (armeabi-v7a) / 0x17da4c (armeabi) BLs the 2-arg
// `mov r0,r1; bx lr` at 0x172cdc / 0x17da44. getFileData then sends the
// relative name through minizip against getPackageCodePath(), which our .apk
// path remap serves from the installed base.apk.
//
// Bug being retired: none left. The override (9ce85bad, 2026-05-13) prepended
// the extracted-asset root so getFileData took its '/'-first fopen branch,
// because the zip branch then had no real APK to open. Since v1.0.1586 the
// shim it forwards to answers the input unchanged by default (the device
// answer; hcr181 RICH 58,406 with 82 zip opens and no extracted tree), so in
// default mode the override did no work except replace the app's code.
// docs/cocos-fullpath-override-retired_2026-09-26.md has the evidence.
//
// Bisect levers, both read at TRANSLATION time:
//   MACAPK_COCOS_OWN_FULLPATH=0 -> install the override again (old path).
//   MACAPK_COCOS_FORCE_FOPEN=1  -> also installs it, so that lever keeps its
//                                  ARM32 half (the shim then prepends the root).
// The dylib cache is keyed on NATIVE_ID only, so an A/B must drop
// lib_native/<lib>.dylib or its .nativeid between arms (MACAPK_SWEEP_CLEAR=
// dylib), the same caveat as MACAPK_COCOS_PNG_OVERRIDE.
//
// Removal condition: once hcr181 has held RICH through a few sweeps with this
// ON, delete this gate, the name match in run_pipeline,
// MACAPK_PATH_REMAP_VADDRS and __macapk_shim_fullPathFromRelativePath.
static bool cocos_own_fullpath_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_COCOS_OWN_FULLPATH");
        return !(e && e[0] == '0');
    }();
    return on;
}
// Parsed exactly as __macapk_shim_fullPathFromRelativePath parses it.
static bool cocos_force_fopen_env() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_COCOS_FORCE_FOPEN");
        return e && e[0] == '1';
    }();
    return on;
}
static bool cocos_fullpath_override_installed() {
    return !cocos_own_fullpath_on() || cocos_force_fopen_env();
}

// =====================================================================
// MACAPK_ARM32_GUEST_DYNAMIC_CAST (default ON; =0 restores the override exactly)
// ---------------------------------------------------------------------
// ON: an ARM32 image's own `__dynamic_cast` is translated from its own bytes
// like every other function. That is what a device runs. `__dynamic_cast` is
// not a platform symbol: Bionic's libstdc++ is built from bionic/__cxa_guard.cpp,
// bionic/__cxa_pure_virtual.cpp and bionic/new.cpp only (AOSP
// bionic/libc/Android.bp, cc_library "libstdc++"), and nothing in Bionic
// implements RTTI. The app's statically linked libsupc++ (gnustl) or its
// libc++abi (static or libc++_shared.so) runs as compiled, and its semantics
// differ by STL build, which is why no single host walker can be right for all
// of them:
//   - libsupc++ on ARM EABI compares type_info by NAME (gcc libsupc++/typeinfo:
//     out-of-line comparison "for most ARM EABI systems"; `__name[0] != '*' &&
//     strcmp(...) == 0`). HCR 1.8.1's helper at 0x2831f8 does exactly that via
//     `bl strcmp@plt` at 0x283228.
//   - Jetpack Joyride 1.3.5's older armeabi libsupc++ compares by POINTER.
//   - libc++abi has its own algorithm (src2dst hint fast paths,
//     dyn_cast_to_derived, search_above_dst / search_below_dst).
//
// Bug being retired: the override (e1aecbf2, 2026-05-17) papered over the
// deleted LLVM lifter's miscompile of HCR's libsupc++ __dynamic_cast, which
// returned non-NULL garbage so CCDirector::setNextScene's early return fired
// and CCTransitionFade::onEnter never ran. That lifter went at v1.0.802; the
// arm32rc translation of the same bytes is exact. Measured on 2026-09-26 by a
// three-way differential over every vtable-derived source/destination pair
// (the guest's own bytes on an ARMv7 CPU = the device answer, vs the arm32rc
// translation, vs the host walker), in-contract cases:
//   HCR 1.8.1 v7a   2,296,908 cases: rc 0 mismatches, host walker 18
//   HCR 1.43 v7a    2,033,428 cases: rc 0,             host walker 64
//   GD Lite 2.2.11  3,106,828 cases: rc 0,             host walker 58
//   JJ 1.3.5 armeabi   32,688 cases: rc 0,             host walker 0
// (GD Lite samples 1 in 4 UNRELATED destinations; every destination in an
// object's own base closure, so every hit and every private base, is kept.)
// The walker is the LESS faithful one: it ignores __public_mask (private
// bases, e.g. cocos2d-x's `AppDelegate : private CCApplication`, return a
// pointer where a device returns NULL) and compares type_info by pointer where
// the name-compare libsupc++ builds match a duplicated typeinfo.
// docs/dynamic-cast-override-retired_2026-09-26.md has the method and numbers.
//
// One generic rule, no engine or library detection: the symbol name alone
// used to install the walker into every ARM32 image that defines
// `__dynamic_cast` (48 .so in ../Apks), and now installs nothing.
//
// Known limit, NOT caused by this gate (the walker is wrong there too):
// libc++abi images hit a discovery-extent defect (search_above_dst's body is
// cut short and falls off with a silent ret). On pk2's libc++_shared.so, as
// loaded, the translation misses 556 of 267,962 casts, every one NULL where a
// device hits, and never returns a wrong pointer; the walker returns a wrong
// non-NULL pointer on 246. Tracked separately. The other libc++abi
// prerequisite, the R_ARM_ABS32 addend the loader used to drop (every typeinfo
// vptr 8 bytes early; the retirement review measured the translated body
// recursing to a host stack overflow on those words), is fixed by
// MACAPK_ARM32_BIONIC_DATA_RELOC (v1.0.1653), so the gate measures libc++abi
// images AS LOADED.
//
// Bisect lever, read at TRANSLATION time: MACAPK_ARM32_GUEST_DYNAMIC_CAST=0
// installs the host walker again (__macapk_shim_dynamic_cast), byte for byte
// the pre-change output. The dylib cache is keyed on NATIVE_ID only, so an A/B
// must drop lib_native/<lib>.dylib or its .nativeid between arms
// (MACAPK_SWEEP_CLEAR=dylib). Offline gate: debug/geh_cocos-dynamic-cast-override_gate.sh.
//
// Removal condition: the dyncast gate stays green AND hcr181, gdlite-v7a and
// mindustry-v7a hold their bands AND the exidx-extent fix (pk2 0x6e56a, the
// A4 row's known defect) has landed so A4 promotes to RC == DEV outright
// (a colour band alone cannot see private-base / duplicate-typeinfo casts,
// which are where the walker and the translation differ). Then delete this gate, the name match in run_pipeline and
// __macapk_shim_dynamic_cast (bionic_shim.cpp) with its helpers.
static bool arm32_guest_dynamic_cast_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_GUEST_DYNAMIC_CAST");
        return !(e && e[0] == '0');
    }();
    return on;
}

// Module-IR builder shared by the 32- and 64-bit paths. The two lifter
// surfaces are intentionally similar so this just templatizes over the
// element width: GprBits is 32 for arm32, 64 for arm64. The function
// signature for cross-call thunks (`__macapk_target_<hex>`) follows the
// same width.
template <int GprBits, typename Lifter, typename Image>
int run_pipeline(const std::string& in_path,
                 const std::string& out_path,
                 const std::string& shim_dir,
                 bool verbose, bool strict, bool keep_ir,
                 const Image& img, uint32_t load_bias = 0) {
    Lifter lifter;
    lifter.set_strict(strict);
    // IR TEXT IS OPT-IN (v1.0.804). arm32rc is the only ARM32 backend and it
    // never reads the IR; discovery now takes LiftedFunction::insns instead of
    // parsing the comments back out. Formatting it anyway cost ~half the
    // translate (917 MB of text per library on HCR 1.43), so only --keep-ir
    // pays for it. GprBits==64 never enters this path at all.
    lifter.set_emit_ir(keep_ir);
    // Per-image load bias B (0 for the main / single-.so image). Threads
    // into every runtime guest-vaddr the lifter materializes so a 2nd+ .so
    // does not collide with another image in the shared guest arena.
    lifter.set_load_bias(load_bias);
    // Pre-register math import PLT addresses so the lifter knows to
    // emit i64-returning calls for sin/cos/etc. (ARM soft-float
    // double-return ABI: r0:r1). Without this, the lifter's default
    // i32-return path drops the high 32 bits of the result and the
    // caller's next `vmov dN, r0, r1` reconstructs a wrong double.
    // arm32-only: the arm64 path bypasses run_pipeline entirely and
    // packages the original ELF verbatim via arm64_pack.
    for (const auto& imp : img.imports) {
        if (imp.plt_addr != 0 &&
            is_softfloat_double_import(imp.name)) {
            lifter.register_softfloat_double_import_plt(imp.plt_addr);
        }
    }

    // (2026-09-27: the gated intra-module PLT redirect registration that sat
    // here -- MACAPK_PLT_REDIRECT / MACAPK_PLT_REDIRECT_FREETYPE -> the
    // lifter's `bl <plt_stub>` rewrite -- is retired. Intra-module PLT calls
    // are bound the way Bionic binds them: elf_reader writes st_value + bias
    // into every local R_ARM_JUMP_SLOT GOT slot, and arm32rc resolves the stub
    // statically from img.plt_local_bindings. See
    // docs/plt-redirect-knobs-retired_2026-09-27.md.)

    [[maybe_unused]] const char* iN     = (GprBits == 64) ? "i64" : "i32";
    // ARM32 lifter uses a 4-i32 cross-call ABI; arm64 uses 8-i64 +
    // 8-double to mirror AAPCS so FP args round-trip through internal
    // call sites and through dlsym entry points.
    const bool aapcs   = (GprBits == 64);
    // ARM32 cross-call ABI now includes a 5th i32 for SP (the shared
    // thread stack pointer; lifted bodies inherit it from the caller
    // and pass it through on every BL/BLX/B-extern). arm64 keeps the
    // 8-i64 + 8-double AAPCS shape, no SP plumbing needed since arm64
    // stack pointers are 64-bit and use the native libSystem stack.
    [[maybe_unused]] auto sig_params = [&](){
        if (!aapcs) return std::string("i32, i32, i32, i32, i32");
        return std::string(
            "i64, i64, i64, i64, i64, i64, i64, i64, "
            "double, double, double, double, double, double, double, double");
    };
    [[maybe_unused]] auto sig_named = [&](){
        if (!aapcs) return std::string(
            "i32 %a, i32 %b, i32 %c, i32 %d, i32 %sp");
        return std::string(
            "i64 %a0, i64 %a1, i64 %a2, i64 %a3, "
            "i64 %a4, i64 %a5, i64 %a6, i64 %a7, "
            "double %d0, double %d1, double %d2, double %d3, "
            "double %d4, double %d5, double %d6, double %d7");
    };
    [[maybe_unused]] auto sig_passthrough = [&](){
        if (!aapcs) return std::string(
            "i32 %a, i32 %b, i32 %c, i32 %d, i32 %sp");
        return std::string(
            "i64 %a0, i64 %a1, i64 %a2, i64 %a3, "
            "i64 %a4, i64 %a5, i64 %a6, i64 %a7, "
            "double %d0, double %d1, double %d2, double %d3, "
            "double %d4, double %d5, double %d6, double %d7");
    };
    // For pointer-aware libc shims, we keep the 4-arg shape libSystem
    // expects -- the 5-arg call from lifted code drops the SP slot.
    // v1.0.86: sig_libsys_4arg is currently unused (libSystem 4-arg
    // calling sites all moved through the regular sig_libsys() path);
    // [[maybe_unused]] silences the warning while keeping the helper
    // around in case a future shim re-needs the 4-arg shape.
    [[maybe_unused]] auto sig_libsys_4arg = [&](){
        return std::string(
            "i32 %a, i32 %b, i32 %c, i32 %d");
    };

    // Stream the module IR straight to its .ll file on disk rather than
    // accumulating the whole thing in a std::stringstream. A 30-50 MB
    // arm32 .so (libunity / libil2cpp) lifts to 10-20 GB of textual IR;
    // holding all of that in RAM jetsam-killed the translator (SIGKILL /
    // rc=137) before it ever reached the chunked-compile path. Writing
    // through to disk caps peak RSS at the write buffer + one lifted
    // function body, and the splitter / single-shot path later mmap()s
    // this file (sequential scan, OS-paged) so the whole IR is never
    // resident either. The .ll is the canonical artifact from here on.
    std::string ll_path = out_path + ".ll";
    // v1.0.801: on the arm32rc path this IR is DEAD WEIGHT. Discovery streams
    // it here only because arm32_lifter.cpp is BOTH the discovery engine and
    // the IR emitter; the recompiler emits machine code directly and never
    // compiles the file -- it is written in full and then deleted at the early
    // return below. Measured on HCR 1.43's libgame.so (3.5 MB input): 872 MB
    // written and thrown away, per library, for nothing.
    //
    // So write it to /dev/null instead. This costs nothing to correctness (the
    // arm32rc exit never reads the file back; the std::remove there simply
    // fails harmlessly) and removes the entire transient disk cost. The 8 MB
    // buffer keeps it to ~110 write syscalls for what used to be 872 MB of I/O.
    //
    // --keep-ir / MACAPK_KEEP_IR still produces the REAL file, so the IR
    // remains available for debugging exactly as before -- which is the point:
    // the artifact is opt-in for diagnosis rather than mandatory for everyone.
    // v1.0.802: arm32rc is the only ARM32 backend, so the IR is ALWAYS dead
    // weight unless it was explicitly asked for. Discovery still streams it
    // (arm32_lifter.cpp is both the discovery engine and the IR emitter), but
    // nothing consumes it.
    const bool ir_unused = !keep_ir && (GprBits == 32);
    const std::string ir_sink = ir_unused ? std::string("/dev/null") : ll_path;
    std::ofstream module_ir;
    // Big write buffer so the millions of tiny `<<` appends don't thrash
    // the stream/syscall layer. setvbuf-equivalent; must precede open().
    static std::vector<char> module_ir_buf(static_cast<size_t>(8) << 20);
    module_ir.rdbuf()->pubsetbuf(module_ir_buf.data(),
                                 static_cast<std::streamsize>(module_ir_buf.size()));
    module_ir.open(ir_sink, std::ios::binary | std::ios::trunc);
    if (!module_ir) {
        std::fprintf(stderr, "[translator] cannot open %s for IR: %s\n",
                     ir_sink.c_str(), std::strerror(errno));
        return 1;
    }
    if (ir_unused)
        std::fprintf(stderr,
            "[translator] arm32rc: IR discarded (not written to disk); "
            "use --keep-ir to materialise %s\n", ll_path.c_str());
    module_ir << "; Mac.apk translator output for " << img.soname << "\n";
    module_ir << "target triple = \"arm64-apple-darwin\"\n";
    module_ir << "target datalayout = \"e-m:o-i64:64-i128:128-n32:64-S128\"\n";
    // `nobuiltin` is load-bearing: a lifted body whose ARM symbol name
    // happens to match a C runtime or C++ operator (e.g. `_Znwj` is the
    // Itanium mangling of `operator new(unsigned int)`) is recognized by
    // LLVM's TargetLibraryInfo as a builtin allocator. AA then treats
    // any value returned from such a call as `noalias` w.r.t. every
    // other accessible object -- including the `guest_base + offset`
    // pointers we use for every guest memory access. The compounding
    // effect inside the lifted body is that the C++ constructor's
    // vtable-installation store (`str r3, [r4]`) gets DSE'd because the
    // optimizer believes the store cannot alias any later observation.
    // Marking every lifted body `nobuiltin` short-circuits the libfunc
    // recognition path so the optimizer sees these as ordinary opaque
    // functions with default `memory(readwrite)` semantics.
    //
    // `memory(readwrite)` is the second load-bearing piece. Without it,
    // LLVM's FunctionAttrs pass walks each lifted body and may infer a
    // narrower memory effect (e.g. `memory(argmem: readwrite,
    // inaccessiblemem: readwrite)`) for leaf-looking functions whose
    // only obvious memory traffic is through the per-function register
    // file alloca and the shim's `__macapk_get_guest_base()` return
    // value. FunctionAttrs cannot prove what guest memory the lifted
    // body actually touches -- the address computation goes through an
    // i32 → i64 zext that hides provenance -- so it may settle on a
    // restrictive attribute that breaks the cross-call memory effects
    // that lifted ARM relies on (push/pop, vtable installation, the
    // GOT-indirect vtable loads in C++ constructors). Forcing
    // `memory(readwrite)` keeps every lifted body conservative w.r.t.
    // every other lifted body, which is the only correct model when
    // the entire guest address space is reachable through a single
    // host-side pointer.
    module_ir << "attributes #0 = { nobuiltin memory(readwrite) "
                 "\"frame-pointer\"=\"non-leaf\" "
                 "\"no-trapping-math\"=\"true\" }\n\n";

    [[maybe_unused]] int lifted = 0, skipped = 0, failed = 0;
    // Names of lifted symbols that look like JNI exports. The JVM
    // calls them via the darwin-arm64 ABI (JNIEnv* as 64-bit x0,
    // jobject/jclass as 64-bit x1, then variadic typed args). Our
    // lifted body sees them as `i32 (i32, i32, i32, i32)` because the
    // original ARM32 ABI was 32-bit. We rename each Java_* (and
    // JNI_OnLoad) body to __macapk_arm32_<name> below and emit a
    // new exported `<name>` with the proper 64-bit ABI that
    // handle-izes pointer args and forwards. arm64 input dylibs
    // already match the JVM ABI so this rewrite is arm32-only.
    std::vector<std::string> jni_exports;
    auto looks_like_jni_export = [](const std::string& n) {
        return (n == "JNI_OnLoad")
            || (n.rfind("Java_", 0) == 0);
    };

    // ------------------------------------------------------------------
    // DISCOVERY DISPOSITION SIDE TABLE
    //
    // `lifter.lifted_address_map()` answers "discovery decided there is a
    // function here". It does NOT answer "the guest bytes at that address
    // were translated", and the two diverge for every address below that
    // calls register_lifted_function() and then `continue`s WITHOUT
    // lifting, because a host shim replaces the guest body. 91 such
    // addresses in HCR 1.8.1, 82 in Jetpack Joyride, 7 in Flappy Bird
    // (counted from this run's own banner tally, see
    // debug/arm32_discovery_golden.py).
    //
    // A consumer that sees only the address map therefore translates the
    // guest's real __cxa_throw / __cxa_guard_acquire bodies where the
    // incumbent substitutes a shim -- which is not a style problem but a
    // SIGABRT and an unwritten singleton vptr respectively, plus it
    // silently invalidates any A/B between the two output paths (they
    // would be comparing two different programs). (__dynamic_cast used to
    // be the third example, "a skipped cocos2d-x onEnter"; that was the
    // deleted LLVM lifter's miscompile, and since 2026-09-26 the guest's
    // own __dynamic_cast is translated by default, see
    // arm32_guest_dynamic_cast_on.)
    //
    // So every such site records what it decided here, and the arm32rc
    // hook at the end of discovery turns this into the
    // `DiscoveredFunction::disposition` the contract in
    // macapk/arm32_discovery.h specifies. Recording at the decision site
    // (rather than re-deriving the predicates later) is deliberate: a
    // re-derivation is a second copy of the rule and would drift.
    struct DispositionRecord {
        pt::Disposition disposition = pt::Disposition::Translate;
        std::string     override_symbol;   // OverrideShim
        int32_t         override_const = 0;    // OverrideConst
        uint32_t        veneer_target = 0;     // Veneer (guest vaddr,
                                               // Thumb lsb PRESERVED)
    };
    std::unordered_map<uint32_t, DispositionRecord> discovery_dispositions;
    auto note_override_shim = [&](uint32_t vaddr, const std::string& sym) {
        DispositionRecord r;
        r.disposition = pt::Disposition::OverrideShim;
        r.override_symbol = sym;
        discovery_dispositions[vaddr] = r;
    };
    auto note_override_const = [&](uint32_t vaddr, int32_t value) {
        DispositionRecord r;
        r.disposition = pt::Disposition::OverrideConst;
        r.override_const = value;
        discovery_dispositions[vaddr] = r;
    };
    auto note_veneer = [&](uint32_t vaddr, uint32_t target) {
        DispositionRecord r;
        r.disposition = pt::Disposition::Veneer;
        r.veneer_target = target;
        discovery_dispositions[vaddr] = r;
    };

    // Names whose lifted body we deliberately replace with a no-op
    // returning 0. Lifted libstdc++ exception runtime cannot
    // interoperate with the host arm64 unwinder -- ARM uses
    // `.ARM.exidx` tables that the host doesn't read, so any throw
    // bottoms out at __terminate -> abort. By neutering the throw
    // path (treat as `return 0`) we let lifted code skip past the
    // throw site as if it returned normally; the value the caller
    // would have got is whatever was already in r0 before the throw,
    // which is usually fine because the caller's error handling for
    // a missing-throw case is "use the result". Without this, the
    // first lifted exception (e.g. cocos2d-x's CCEGLView::CCEGLView
    // throwing during sharedOpenGLView's magic-static init) takes
    // down the entire process via SIGABRT.
    auto is_exception_runtime = [](const std::string& n) {
        return n == "__cxa_throw"
            || n == "__cxa_rethrow"
            || n == "_ZSt9terminatev"
            || n == "_ZN10__cxxabiv111__terminateEPFvvE"
            || n == "_ZN9__gnu_cxx27__verbose_terminate_handlerEv"
            || n == "_Unwind_RaiseException"
            || n == "_Unwind_Resume"
            || n == "__gnu_Unwind_RaiseException"
            || n == "__gnu_Unwind_ForcedUnwind"
            || n == "__gnu_Unwind_Resume"
            || n == "__gnu_Unwind_Resume_or_Rethrow"
            || n == "__gnu_Unwind_Backtrace";
    };

    // ====== pure-thrower auto-detect (v1.0.24: re-enabled with
    // __cxa_allocate_exception cross-check) ======
    //
    // STATUS 2026-09-26: the set this computes is a DISCOVERY FACT only (a
    // `bl <helper>` does not return; see the v1.0.62 terminator checks). The
    // `ret i32 0` body replacement described below is RETIRED by default
    // (MACAPK_ARM32_THROW_HELPERS_RUN, see arm32_throw_helpers_run) and the
    // classifier gained reachability + symbol trust (MACAPK_ARM32_NORETURN_REACH,
    // see arm32_noreturn_reach_on). The history below is kept for the =0 arms.
    //
    // The scan finds helper functions whose entire job is to
    // construct a C++ exception object and call __cxa_throw. Pattern:
    //
    //   _ZSt20__throw_length_errorPKc(msg):
    //     push {regs, lr}
    //     mov  r0, #8           ; sizeof(length_error)
    //     bl   __cxa_allocate_exception
    //     mov  r1, msg
    //     bl   <length_error ctor>
    //     ldr  r1, =typeinfo
    //     ldr  r2, =dtor
    //     mov  r0, exception
    //     bl   __cxa_throw       ; last executable instruction
    //     ; (literal pool follows; no fall-through)
    //
    // Lifting these the normal way produces functions whose only
    // side effect is to siglongjmp out via our __cxa_throw shim. The
    // siglongjmp escapes all the way back to __macapk_invoke_safe,
    // so the lifted ARM caller's post-throw fall-through code
    // (typically "return empty / use default") never runs and the
    // engine stays half-initialised.
    //
    // Fix: replace each pure thrower's body with `ret i32 0`. The
    // caller's `bl` then returns normally, the std::string the caller
    // was building becomes empty (size=0), and the engine recovers
    // gracefully.
    //
    // v1.0.23 disabled this scan because the terminator-walk produced
    // false positives -- the forward-prologue-skip after a Thumb-2
    // BL-to-throw landed on push prologues that were the START of
    // regular functions, not throw helpers. Those got `ret 0`'d and
    // a 5.2M-per-run cocos2d-x ccCArray.cpp fsassert flood resulted.
    //
    // v1.0.24 tightens the classifier with a second signal:
    //   * every pure thrower must also contain a
    //     `bl __cxa_allocate_exception` within the first 8
    //     instructions of its body (= 32 bytes for ARM, 16 bytes for
    //     Thumb16; both bounds covered by a single 32-byte window).
    //
    // Real throwers always allocate before throwing -- the allocation
    // produces the exception object that __cxa_throw needs. Non-
    // throwers that the previous scan miscategorised do NOT allocate
    // an exception object, so the cross-check filters them out.
    // Auto-detect "pure thrower" wrapper functions: small internal
    // libstdc++ helpers that exist solely to construct an exception
    // object and call __cxa_throw. Pattern (HCR libstdc++):
    //
    //   _ZSt20__throw_length_errorPKc(msg):
    //     push {regs, lr}
    //     mov r0, #8           ; sizeof(length_error)
    //     bl __cxa_allocate_exception
    //     mov r1, msg          ; what() arg
    //     bl <length_error::length_error(char const*)>
    //     ldr r1, =typeinfo
    //     ldr r2, =dtor
    //     mov r0, exception
    //     bl __cxa_throw       ; <-- last instruction
    //     ; (literal pool follows; no fall-through)
    //
    // Without symbols, the lifter would lift these as normal i32
    // functions whose only side effect is to siglongjmp via our
    // __cxa_throw shim. The siglongjmp escapes all the way back to
    // __macapk_invoke_safe -- meaning the lifted ARM caller's
    // post-throw code (which often DOES exist; the throw is just
    // ONE branch of a conditional) never runs, leaving the engine
    // in a half-initialized state.
    //
    // Fix: detect any function whose final executable instruction is
    // `bl __cxa_throw` (i.e. the function exists solely to throw)
    // and override it with `ret i32 0`. The caller's `bl` then
    // returns normally, post-throw code runs, and the engine state
    // converges to "operation did nothing" instead of "operation
    // aborted halfway".
    //
    // Detection is byte-level: scan executable segments for ARM and
    // Thumb-2 BL encodings whose target is __cxa_throw's vaddr.
    // Record the source PC. Then for each known function entry,
    // check whether the body contains a bl __cxa_throw within ~12
    // bytes of the function's end -- the "end" being the next known
    // function start. Functions matching this pattern join the
    // override set and get a ret 0 stub at lift time.
    std::unordered_set<uint32_t> pure_throw_helpers;
    {
        uint32_t cxa_throw_vaddr_strict = 0;  // ARM or Thumb-flagged
        uint32_t cxa_throw_vaddr_clean  = 0;  // low-bit stripped
        uint32_t cxa_alloc_vaddr_clean  = 0;  // __cxa_allocate_exception
        for (const auto& s : img.symbols) {
            if (s.name == "__cxa_throw") {
                cxa_throw_vaddr_strict = s.address;
                cxa_throw_vaddr_clean  = s.address & ~1u;
            } else if (s.name == "__cxa_allocate_exception") {
                cxa_alloc_vaddr_clean  = s.address & ~1u;
            }
            if (cxa_throw_vaddr_clean && cxa_alloc_vaddr_clean) break;
        }
        if (cxa_throw_vaddr_clean != 0) {
            // (source_pc, source_is_thumb) of every bl <target> found
            // in the file's executable segments, for either of the
            // two exception runtime calls we care about.
            std::vector<std::pair<uint32_t,bool>> bl_throw_sites;
            std::vector<std::pair<uint32_t,bool>> bl_alloc_sites;
            // Pre-bucketed quick-lookup for "is THIS pc a bl to alloc".
            std::unordered_set<uint32_t> bl_alloc_pcs;
            auto record_bl = [&](uint32_t v, bool thumb,
                                 uint32_t clean_target) {
                if (clean_target == cxa_throw_vaddr_clean) {
                    bl_throw_sites.emplace_back(v, thumb);
                } else if (clean_target == cxa_alloc_vaddr_clean
                           && cxa_alloc_vaddr_clean != 0) {
                    bl_alloc_sites.emplace_back(v, thumb);
                    bl_alloc_pcs.insert(v);
                }
            };
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                uint32_t seg_start = seg.vaddr;
                uint32_t seg_end   = seg.vaddr + seg.bytes.size();
                // ARM-mode BL: 4-byte aligned, encoding 0xEB000000 OR'd
                // with sign-extended 24-bit immediate (scaled by 4).
                // Target = pc + 8 + (imm24 << 2)
                for (uint32_t v = (seg_start + 3) & ~3u;
                     v + 4 <= seg_end; v += 4) {
                    size_t off = v - seg_start;
                    uint32_t w = uint32_t(seg.bytes[off])
                               | (uint32_t(seg.bytes[off+1]) << 8)
                               | (uint32_t(seg.bytes[off+2]) << 16)
                               | (uint32_t(seg.bytes[off+3]) << 24);
                    // Accept only `bl` with AL condition (0xE prefix).
                    if ((w & 0xff000000u) != 0xeb000000u) continue;
                    int32_t imm24 = static_cast<int32_t>(w << 8) >> 8;
                    uint32_t target = v + 8 + (imm24 << 2);
                    record_bl(v, false, target & ~1u);
                }
                // Thumb-2 BL: 32-bit, 2-byte aligned. Encoding:
                //   first halfword:  11110 S imm10
                //   second halfword: 11 J1 1 J2 imm11
                // imm32 = SignExt(S:I1:I2:imm10:imm11:0)
                // where I1 = !(J1^S), I2 = !(J2^S). Target = pc + 4 +
                // imm32 (PC is 4 ahead of the bl due to pipeline).
                for (uint32_t v = (seg_start + 1) & ~1u;
                     v + 4 <= seg_end; v += 2) {
                    size_t off = v - seg_start;
                    uint32_t hw1 = uint32_t(seg.bytes[off])
                                 | (uint32_t(seg.bytes[off+1]) << 8);
                    uint32_t hw2 = uint32_t(seg.bytes[off+2])
                                 | (uint32_t(seg.bytes[off+3]) << 8);
                    if ((hw1 & 0xf800u) != 0xf000u) continue;
                    if ((hw2 & 0xd000u) != 0xd000u) continue;
                    uint32_t S   = (hw1 >> 10) & 1u;
                    uint32_t imm10 = hw1 & 0x3ffu;
                    uint32_t J1  = (hw2 >> 13) & 1u;
                    uint32_t J2  = (hw2 >> 11) & 1u;
                    uint32_t imm11 = hw2 & 0x7ffu;
                    uint32_t I1 = (~(J1 ^ S)) & 1u;
                    uint32_t I2 = (~(J2 ^ S)) & 1u;
                    int32_t imm32 =
                        (int32_t(S) << 24) |
                        (int32_t(I1) << 23) |
                        (int32_t(I2) << 22) |
                        (int32_t(imm10) << 12) |
                        (int32_t(imm11) << 1);
                    // Sign-extend 25-bit field.
                    if (imm32 & 0x01000000) imm32 |= 0xfe000000;
                    uint32_t target = v + 4 + uint32_t(imm32);
                    record_bl(v, true, target & ~1u);
                }
            }
            // Returns true iff there is a bl __cxa_allocate_exception
            // call in the [fn_start, fn_start + 32) byte window. Pure
            // throwers always allocate the exception object near the
            // top of the function body, so this is the load-bearing
            // false-positive filter introduced in v1.0.24.
            auto has_alloc_near_start = [&](uint32_t fn_start) -> bool {
                if (cxa_alloc_vaddr_clean == 0) {
                    // Defensive: if we couldn't find the alloc symbol,
                    // skip the check rather than letting every candidate
                    // be filtered out.
                    return true;
                }
                // 32 bytes = 8 ARM instructions or 16 Thumb-16
                // instructions; in practice throwers call alloc within
                // the first 4-6 instructions, so 32 bytes is generous.
                uint32_t window_end = fn_start + 0x20;
                for (auto [pc, t] : bl_alloc_sites) {
                    if (pc >= fn_start && pc < window_end) return true;
                }
                return false;
            };
            // Counters for the final log line so we can audit how
            // many candidates each filter rejected.
            size_t cand_symboled_seen     = 0;
            size_t cand_symboled_too_big  = 0;
            size_t cand_symboled_no_alloc = 0;
            size_t cand_unsymboled_seen     = 0;
            size_t cand_unsymboled_too_big  = 0;
            size_t cand_unsymboled_no_alloc = 0;
            // MACAPK_ARM32_NORETURN_REACH (arm32_noreturn_reach_on): the two
            // extra filters. They run AFTER every v1.0.24 filter, so the ON set
            // is always a subset of the =0 set: they only ever remove a helper.
            const bool reach_on = arm32_noreturn_reach_on();
            size_t cand_symboled_exit      = 0;
            size_t cand_unsymboled_symveto = 0;
            size_t cand_unsymboled_exit    = 0;
            std::unordered_set<uint32_t> throw_pcs_all;
            for (auto [p, t] : bl_throw_sites) throw_pcs_all.insert(p);
            // Sized FUNC/NOTYPE symbols by start, with a running max of their
            // ends, so "which symbol contains this pc" is one binary search.
            struct FnRange { uint32_t start, end; bool thumb; };
            std::vector<FnRange> fn_ranges;
            std::vector<uint32_t> fn_end_pmax;
            if (reach_on) {
                for (const auto& s : img.symbols) {
                    if (s.size == 0) continue;
                    if (s.type != 0 /*STT_NOTYPE*/ && s.type != 2 /*STT_FUNC*/) continue;
                    const uint32_t st = s.address & ~1u;
                    fn_ranges.push_back({st, st + s.size, s.is_thumb});
                }
                std::sort(fn_ranges.begin(), fn_ranges.end(),
                          [](const FnRange& x, const FnRange& y) {
                              return x.start != y.start ? x.start < y.start
                                                        : x.end < y.end;
                          });
                uint32_t m = 0;
                for (const auto& r : fn_ranges) { m = std::max(m, r.end); fn_end_pmax.push_back(m); }
            }
            // The innermost (greatest-start) sized function symbol containing
            // pc, or nullptr.
            auto containing_fn = [&](uint32_t pc) -> const FnRange* {
                auto it = std::upper_bound(
                    fn_ranges.begin(), fn_ranges.end(), pc,
                    [](uint32_t v, const FnRange& r) { return v < r.start; });
                for (ptrdiff_t i = (it - fn_ranges.begin()) - 1; i >= 0; --i) {
                    if (fn_end_pmax[static_cast<size_t>(i)] <= pc) break;
                    const FnRange& r = fn_ranges[static_cast<size_t>(i)];
                    if (pc >= r.start && pc < r.end) return &r;
                }
                return nullptr;
            };
            auto log_reach_drop = [&](uint32_t fs, const char* why,
                                      ThrowHelperExit k, uint32_t at) {
                std::fprintf(stderr,
                    "[translator] throw-helper dropped (MACAPK_ARM32_NORETURN_REACH): "
                    "fn_start=0x%x %s %s@0x%x\n",
                    fs, why, throw_helper_exit_name(k), at);
            };
            // For each defined function in the symbol table whose body
            // contains a bl __cxa_throw AND the bl is the last
            // executable instruction (within last 12 bytes of the
            // declared symbol size), mark as a pure thrower.
            for (const auto& s : img.symbols) {
                if (s.size == 0) continue;
                if (is_exception_runtime(s.name)) continue;
                uint32_t fn_start = s.address & ~1u;
                uint32_t fn_end   = fn_start + s.size;
                bool matched = false;
                for (auto [bl_pc, bl_thumb] : bl_throw_sites) {
                    if (bl_pc < fn_start || bl_pc >= fn_end) continue;
                    // Last-instruction check: ARM bl is 4 bytes,
                    // Thumb-2 bl is 4 bytes. Accept if the bl ends
                    // within 4 bytes of fn_end (i.e. nothing executable
                    // after it).
                    uint32_t bl_end = bl_pc + 4;
                    if (bl_end + 4 < fn_end) continue;
                    matched = true;
                    break;
                }
                if (!matched) continue;
                ++cand_symboled_seen;
                // Belt-and-braces: bound function body size. Real
                // throwers are small (< 128 bytes); a longer body
                // suggests we matched something more complex.
                if (s.size > 128) {
                    ++cand_symboled_too_big;
                    continue;
                }
                // The alloc cross-check: an honest thrower allocates
                // the exception object near its start.
                if (!has_alloc_near_start(fn_start)) {
                    ++cand_symboled_no_alloc;
                    continue;
                }
                if (reach_on) {
                    uint32_t at = 0;
                    const ThrowHelperExit k = throw_helper_exit_reachable(
                        img.loadable, fn_start, s.is_thumb, fn_end,
                        throw_pcs_all, &at);
                    if (k != ThrowHelperExit::None) {
                        ++cand_symboled_exit;
                        log_reach_drop(fn_start, "symboled, exit", k, at);
                        continue;
                    }
                }
                pure_throw_helpers.insert(fn_start);
            }
            // Also discover UNSYMBOLED throw helpers. For each bl
            // __cxa_throw site that isn't already contained in a
            // detected symboled-thrower, walk backward up to 256 bytes
            // looking for an unconditional function terminator that
            // marks the previous function's end -- the next instruction
            // is the throw helper's prologue. The helpers we care about
            // (HCR's libstdc++ `__throw_length_error` family) are 16-64
            // bytes long and always end with bl __cxa_throw, so the bl
            // site IS in the last 8 bytes of the helper.
            //
            // Walk-backward terminator patterns (ARM mode):
            //   e8bd80xx       pop {...,pc}
            //   e1a0f00e       mov pc, lr
            //   e12fff1e       bx lr
            //   eaxxxxxx       b imm (unconditional)
            // Thumb mode (2-byte):
            //   bdxx           pop {...,pc}
            //   4770           bx lr
            //   e0xx-e7ff      b imm11
            // Track which (fn_start, fn_end) ranges we've already
            // resolved a thrower for. We use the bl __cxa_throw PC + 4
            // as the upper bound (pure throwers end with bl __cxa_throw,
            // and the literal pool that follows is not part of the
            // function body for our purposes). This is MUCH tighter
            // than the previous 0x200-byte heuristic, which lumped
            // adjacent throwers together and dropped all but the
            // first.
            std::vector<std::pair<uint32_t,uint32_t>> kept_ranges;
            for (auto [bl_pc, bl_thumb] : bl_throw_sites) {
                // Skip if this bl_pc is already inside a thrower we
                // resolved on an earlier iteration. The range check is
                // body-tight, so two adjacent throwers (separated only
                // by a literal pool) each get their own chance to be
                // discovered.
                bool covered = false;
                for (auto [fs, fe] : kept_ranges) {
                    if (bl_pc >= fs && bl_pc < fe) {
                        covered = true;
                        break;
                    }
                }
                if (covered) continue;
                // Build a quick-lookup set of all bl __cxa_throw start
                // PCs so the backward walk can recognize `bl __cxa_throw`
                // itself as a function-end terminator (any noreturn
                // tail call separates one function from the next).
                static std::unordered_set<uint32_t> bl_throw_pcs;
                if (bl_throw_pcs.empty()) {
                    for (auto [p, t] : bl_throw_sites) bl_throw_pcs.insert(p);
                }
                // Find seg containing bl_pc.
                const pt::CodeSegment* seg = nullptr;
                for (const auto& s : img.loadable) {
                    if (!s.executable) continue;
                    if (bl_pc >= s.vaddr && bl_pc < s.vaddr + s.bytes.size()) {
                        seg = &s; break;
                    }
                }
                if (!seg) continue;
                // Walk backward.
                uint32_t step = bl_thumb ? 2 : 4;
                uint32_t fn_start = 0;
                // Cap walk at 256 bytes back; throwers are tiny.
                for (uint32_t back = step; back <= 0x100; back += step) {
                    if (back > bl_pc) break;
                    uint32_t v = bl_pc - back;
                    if (v < seg->vaddr) break;
                    size_t off = v - seg->vaddr;
                    if (off + step > seg->bytes.size()) break;
                    if (bl_thumb) {
                        uint32_t hw = uint32_t(seg->bytes[off])
                                    | (uint32_t(seg->bytes[off+1]) << 8);
                        bool is_term = false;
                        uint32_t term_size = 2;
                        if (bl_throw_pcs.count(v)) {
                            // bl __cxa_throw is itself a function-end
                            // marker (noreturn tail call). HCR's
                            // libstdc++ packs multiple throw helpers
                            // back-to-back with only a literal pool
                            // between them; no traditional terminator
                            // separates them. Recognising bl-to-throw
                            // as a terminator lets us discover each
                            // subsequent helper. Checked BEFORE the
                            // parity tail test below: v is a decoded BL
                            // site, which is strictly stronger evidence
                            // that it begins an instruction than a
                            // heuristic can ever contradict.
                            is_term = true;
                            term_size = 4;
                        } else {
                            // MACAPK_ARM32_THUMB_PARITY: this walk steps
                            // back 2 bytes at a time, so it lands on
                            // 32-bit tails too. NOTE: this call site runs
                            // BEFORE the literal-pool scan below populates
                            // g_parity_pool_t, so the run here has no pool
                            // stop (thumb_hw_is_second_half degrades to
                            // its no-stop behaviour, same as the "without
                            // the pool-mark stop" corpus row) -- a
                            // measured cost specific to this early call
                            // site, not a bug in the rule itself.
                            const bool tail = thumb_hw_is_second_half(
                                img, v);
                            if (tail) { /* an operand field, not a terminator */ }
                            else if ((hw & 0xff00u) == 0xbd00u) is_term = true; // pop pc
                            else if (hw == 0x4770u) is_term = true;        // bx lr
                            else if ((hw & 0xf800u) == 0xe000u) is_term = true; // b imm11
                        }
                        if (is_term) {
                            // Skip forward past nop padding, literal
                            // pool entries, etc. until we find a
                            // recognizable Thumb prologue (push {regs,lr}
                            // = bdXX, push {regs} = b4XX, push.w =
                            // e92dXXXX). Cap forward walk at 64 bytes
                            // to avoid runaway scans.
                            uint32_t cand = v + term_size;
                            uint32_t cap = cand + 0x40;
                            while (cand + 2 <= seg->vaddr + seg->bytes.size()
                                   && cand < cap) {
                                size_t coff = cand - seg->vaddr;
                                uint32_t chw = uint32_t(seg->bytes[coff])
                                             | (uint32_t(seg->bytes[coff+1]) << 8);
                                // Thumb prologue patterns: bdXX/b4XX/b5XX (push)
                                if ((chw & 0xff00u) == 0xb500u ||  // push {regs,lr}
                                    (chw & 0xff00u) == 0xb400u ||  // push {regs}
                                    (chw & 0xff00u) == 0xb000u ||  // sub sp
                                    (chw & 0xff00u) == 0xaf00u) {  // add r7,sp
                                    break;
                                }
                                // Thumb-2 push.w (E92D xxxx): bytes
                                // [2d e9 XX XX] but our 16-bit halfword
                                // at LE shows the FIRST halfword as
                                // 0xe92d (which displays as bytes
                                // 2d e9).
                                if (chw == 0xe92du) break;
                                cand += 2;
                            }
                            fn_start = cand;
                            break;
                        }
                    } else {
                        if ((v & 3) != 0) continue;
                        uint32_t w = uint32_t(seg->bytes[off])
                                   | (uint32_t(seg->bytes[off+1]) << 8)
                                   | (uint32_t(seg->bytes[off+2]) << 16)
                                   | (uint32_t(seg->bytes[off+3]) << 24);
                        bool is_term = false;
                        // v1.0.553: UNCONDITIONAL only (cond=AL). A
                        // conditional return/branch falls through -- the
                        // code after it is the SAME function, not a new
                        // entry. `bxeq lr` before FT_GlyphLoader_Add's
                        // push made the prologue scan seed a mid-function
                        // start that truncated the Add body to 2 insns ->
                        // glyph outlines stayed empty -> no text.
                        if ((w & 0xffff8000u) == 0xe8bd8000u) is_term = true; // pop {..,pc}
                        else if (w == 0xe12fff1eu) is_term = true; // bx lr
                        else if ((w & 0xff000000u) == 0xea000000u) is_term = true; // b imm (AL)
                        else if (w == 0xe1a0f00eu) is_term = true; // mov pc, lr
                        if (is_term) {
                            fn_start = v + 4;
                            break;
                        }
                    }
                }
                if (fn_start == 0) continue;
                ++cand_unsymboled_seen;
                // Bound function body size from the throw side: throwers
                // are tiny (< 128 bytes between prologue and bl __cxa_throw).
                if (bl_pc - fn_start > 128) {
                    ++cand_unsymboled_too_big;
                    continue;
                }
                // Alloc cross-check: a real pure thrower allocates the
                // exception object near the start. This is the v1.0.24
                // filter -- before it, regular non-thrower functions
                // whose prologue happened to land in our backward-walk
                // skip window were getting overridden to ret 0, blowing
                // engine invariants downstream (cocos2d-x ccCArray
                // fsassert flood). Requiring a real bl __cxa_allocate_
                // exception within the first 32 bytes of body eliminates
                // those false positives.
                if (!has_alloc_near_start(fn_start)) {
                    ++cand_unsymboled_no_alloc;
                    continue;
                }
                if (reach_on) {
                    // SYMBOL TRUST (a veto, never a source): a throw site inside
                    // a sized function symbol belongs to THAT function. A walk
                    // result that is not its start (a literal pool just before
                    // it, a block inside it) is not an entry anything calls; one
                    // that is gets judged over the symbol's own extent.
                    const FnRange* sym = containing_fn(bl_pc);
                    if (sym && (sym->start != fn_start || sym->thumb != bl_thumb)) {
                        ++cand_unsymboled_symveto;
                        std::fprintf(stderr,
                            "[translator] throw-helper dropped (MACAPK_ARM32_NORETURN_REACH): "
                            "fn_start=0x%x unsymboled, symbol veto (the containing "
                            "symbol starts at 0x%x%s)\n",
                            fn_start, sym->start,
                            sym->thumb != bl_thumb ? ", other mode" : "");
                        continue;
                    }
                    uint32_t at = 0;
                    const ThrowHelperExit k = throw_helper_exit_reachable(
                        img.loadable, fn_start, bl_thumb,
                        sym ? sym->end : fn_start + 0x200, throw_pcs_all, &at);
                    if (k != ThrowHelperExit::None) {
                        ++cand_unsymboled_exit;
                        log_reach_drop(fn_start, "unsymboled, exit", k, at);
                        continue;
                    }
                }
                pure_throw_helpers.insert(fn_start);
                kept_ranges.emplace_back(fn_start, bl_pc + 4);
            }
            std::fprintf(stderr,
                "[translator] throw-helper scan (noreturn discovery fact; "
                "bodies %s; classifier %s): "
                "__cxa_throw=0x%x __cxa_allocate_exception=0x%x; "
                "bl_throw_sites=%zu bl_alloc_sites=%zu | "
                "symboled cand=%zu too_big=%zu no_alloc=%zu exit=%zu | "
                "unsymboled cand=%zu too_big=%zu no_alloc=%zu "
                "symbol_veto=%zu exit=%zu | "
                "kept=%zu\n",
                arm32_throw_helpers_run()
                    ? "RUN as compiled (MACAPK_ARM32_THROW_HELPERS_RUN=0 -> ret 0)"
                    : "ret 0 (v1.0.24 override, MACAPK_ARM32_THROW_HELPERS_RUN=0)",
                reach_on
                    ? "reach+symbol (MACAPK_ARM32_NORETURN_REACH=0 -> v1.0.24)"
                    : "v1.0.24 (MACAPK_ARM32_NORETURN_REACH=0)",
                cxa_throw_vaddr_strict,
                cxa_alloc_vaddr_clean,
                bl_throw_sites.size(),
                bl_alloc_sites.size(),
                cand_symboled_seen,
                cand_symboled_too_big,
                cand_symboled_no_alloc,
                cand_symboled_exit,
                cand_unsymboled_seen,
                cand_unsymboled_too_big,
                cand_unsymboled_no_alloc,
                cand_unsymboled_symveto,
                cand_unsymboled_exit,
                pure_throw_helpers.size());
            // Diagnostic: print every accepted helper vaddr + the bl
            // __cxa_allocate_exception offset that satisfied the
            // alloc-near-start check. This is invaluable when the
            // scan keeps fewer candidates than expected -- the user
            // can cross-check each entry against the file's disasm.
            {
                std::vector<uint32_t> kept_sorted(
                    pure_throw_helpers.begin(), pure_throw_helpers.end());
                std::sort(kept_sorted.begin(), kept_sorted.end());
                for (uint32_t fn_start : kept_sorted) {
                    uint32_t alloc_pc = 0;
                    for (auto [pc, t] : bl_alloc_sites) {
                        if (pc >= fn_start && pc < fn_start + 0x20) {
                            alloc_pc = pc; break;
                        }
                    }
                    uint32_t throw_pc = 0;
                    for (auto [pc, t] : bl_throw_sites) {
                        if (pc >= fn_start && pc < fn_start + 0x80) {
                            throw_pc = pc; break;
                        }
                    }
                    std::fprintf(stderr,
                        "[translator] throw-helper kept: fn_start=0x%x "
                        "alloc@0x%x (+0x%x) throw@0x%x (+0x%x)\n",
                        fn_start, alloc_pc,
                        alloc_pc ? alloc_pc - fn_start : 0u,
                        throw_pc,
                        throw_pc ? throw_pc - fn_start : 0u);
                }
                // Also log the unsymboled candidates that got filtered
                // so we can audit false negatives (the actual concern
                // for HCR's 0x2cd73c case is whether the right ones
                // got through).
                std::fprintf(stderr,
                    "[translator] throw-helper rejected-unsymboled: "
                    "(too_big=%zu no_alloc=%zu)\n",
                    cand_unsymboled_too_big,
                    cand_unsymboled_no_alloc);
            }
        }
    }
    // MACAPK_ARM32_NORETURN_REACH: a CONDITIONAL call to a throw helper is not
    // a terminator (the discovery checks below). Logged once per call site so
    // the effect of the rule is countable in the translator's log.
    std::unordered_set<uint32_t> cond_helper_call_sites;
    auto cond_helper_call_not_terminator = [&](uint32_t bl_pc, uint32_t target,
                                               const char* how) {
        if (cond_helper_call_sites.insert(bl_pc).second)
            std::fprintf(stderr,
                "[translator] throw-helper call at 0x%x -> 0x%x is %s: not a "
                "terminator (MACAPK_ARM32_NORETURN_REACH)\n",
                bl_pc, target, how);
    };
    // Override the lifted libstdc++ static-init guard helpers with
    // host shims that operate on guest memory directly. The lifted
    // ARM bodies for __cxa_guard_acquire/release/abort go through
    // pthread_mutex_lock with mutex addresses computed via PIC literal
    // pools, and tracing through the lifted disassembly shows
    // pthread_mutex_lock never fires for HCR's CCEGLView singleton --
    // the result is `*(0x308284) = 0` (vptr never written) and every
    // downstream vtable dispatch reads garbage. Bypassing the lifted
    // bodies entirely lets our pointer-aware shim do the simple
    // bit-test/bit-set on the guest guard byte. Same shim as the one
    // used for dynamically-linked libstdc++ apps, just now reachable
    // for static-linked HCR.
    auto is_guard_runtime = [](const std::string& n) {
        return n == "__cxa_guard_acquire"
            || n == "__cxa_guard_release"
            || n == "__cxa_guard_abort";
    };

    // Helper: lifted-functions we replace with a no-op-returning-0 body.
    // The ARM EABI div-by-zero stubs (__aeabi_idiv0 / __aeabi_ldiv0) are
    // the canonical case: ARM ABI says they raise SIGFPE, but macOS host
    // SDIV/UDIV silently return 0 on zero divisor. Bridging the two on
    // every divide is impractical (the call goes through lifted
    // __divsi3 which we don't intercept), so we cut the cord at the
    // ldiv0 stub and let the divide return whatever __divsi3 set in r0
    // before branching here (typically INT_MIN for signed div-by-zero,
    // 0 for the zero/zero case). That matches macOS/iOS semantics for
    // div-by-zero and keeps the parser path moving instead of dying in
    // raise(SIGFPE). Specifically unblocks cocos2d-x BMFont init, which
    // uses uthash whose HASH_EXPAND_BUCKETS path divides count by the
    // not-yet-recomputed ideal_chain_maxlen (= 0 on the first expand).
    auto is_eabi_div0_stub = [](const std::string& n) {
        return n == "__aeabi_idiv0" || n == "__aeabi_ldiv0";
    };
    // Track whether the `declare void @__macapk_lifted_throw_recover`
    // line has been emitted yet. Each exception-runtime override
    // (see below) used to re-emit the declare line, which produced N
    // identical declares for N overridden helpers and made llvm-as
    // reject the IR with "invalid redefinition." The fix: emit the
    // declare exactly once, before the first override; subsequent
    // overrides only emit the override define + call.
    bool throw_recover_declared = false;
    for (const auto& sym : img.symbols) {
        if (sym.type != pt::STT_FUNC) continue;
        if (sym.size == 0) continue;
        // Override the lifted bodies of C++ exception runtime helpers
        // with simple no-ops. Returning 0 is correct for all of them
        // (none have a meaningful return when the unwinder is dead).
        if constexpr (!aapcs) {
            if (is_eabi_div0_stub(sym.name)) {
                // Return INT_MAX (a "saturated" divide-by-zero result)
                // so callers that store the quotient and use it as a
                // threshold (e.g. uthash's HASH_EXPAND_BUCKETS storing
                // count/ideal_chain_maxlen into bucket->expand_mult)
                // end up with a value large enough to suppress further
                // expansion attempts and let the parse proceed.
                // Returning 0 here would cause re-entry on every add.
                module_ir << "; --- ARM EABI div-by-zero stub (returns INT_MAX, macOS-semantics) ---\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                             "i32 %arg_sp) local_unnamed_addr {\n"
                          << "entry:\n"
                          << "  ret i32 2147483647\n"
                          << "}\n\n";
                note_override_const(
                    static_cast<uint32_t>(sym.address & ~1u), 2147483647);
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
            if (is_exception_runtime(sym.name)) {
                // Earlier strategy was `ret i32 0` -- "treat the throw
                // as a no-op return". That let lifted code skip the
                // throw site, but the CALLER's post-throw code then
                // runs as if the throw didn't happen. For libstdc++
                // `__throw_*` helpers that wrap `__cxa_throw`, the
                // post-throw code includes a recursion back into the
                // throwing function (the wrapper is supposed to be
                // noreturn, so the lifter happily lifted whatever
                // bytes followed it). Observed on HCR 1.43: the
                // `_S_create` size-bounds throw spirals into infinite
                // self-recursion at 100% CPU.
                //
                // The fix: route the throw through a host shim
                // (__macapk_lifted_throw_recover) that siglongjmp's
                // back to __macapk_invoke_safe. The JNI thunk that
                // started the lifted call sees longjmp_kind=5 and
                // returns 0 cleanly; the cocos2d main loop continues
                // with whatever state was already initialized.
                if (!throw_recover_declared) {
                    module_ir << "; --- exception-runtime siglongjmp recovery (declare once) ---\n"
                              << "declare void @__macapk_lifted_throw_recover("
                                 "i32, i32, i32, i32, i32) noreturn\n";
                    throw_recover_declared = true;
                }
                module_ir << "; --- exception-runtime override for " << sym.name << " ---\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                             "i32 %arg_sp) local_unnamed_addr noreturn {\n"
                          << "entry:\n"
                          << "  call void @__macapk_lifted_throw_recover("
                             "i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                             "i32 %arg_sp)\n"
                          << "  unreachable\n"
                          << "}\n\n";
                // Track in addr_map so other code that calls
                // __macapk_target_<addr> for this symbol gets a clean
                // alias to the override (same path as a normal lifted
                // function).
                // __macapk_lifted_throw_recover is [[noreturn]] and takes
                // (a0,a1,a2,a3,sp) -- exactly the lifted shape -- so a
                // plain tail-forward is correct for a non-IR consumer too.
                note_override_shim(
                    static_cast<uint32_t>(sym.address & ~1u),
                    "__macapk_lifted_throw_recover");
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
            // Pure-thrower override: see pure_throw_helpers scan above.
            // RETIRED by default (MACAPK_ARM32_THROW_HELPERS_RUN, see
            // arm32_throw_helpers_run): the helper's own body is translated
            // below like any other function and really throws, as on a
            // device. Kept only for the =0 bisect arm. v1.0.24's reasoning:
            // These functions exist solely to construct an exception
            // object and call __cxa_throw. Their callers typically have
            // post-throw code (e.g. fall-through to a "return empty
            // value" path) -- our generic __cxa_throw siglongjmp
            // recovery would unwind PAST that code, aborting the JNI
            // call entirely. Replacing the body with `ret 0` lets the
            // caller's `bl` return cleanly so post-throw fall-through
            // runs. (That fall-through is code the compiler placed after
            // a __noreturn__ call; no device ever executes it.)
            if constexpr (!aapcs) {
                if (!arm32_throw_helpers_run() && pure_throw_helpers.count(
                        static_cast<uint32_t>(sym.address & ~1u))) {
                    module_ir << "; --- pure-thrower override (ret 0) ---\n"
                              << "define dso_local i32 @\"" << sym.name
                              << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                                 "i32 %arg_sp) local_unnamed_addr {\n"
                              << "entry:\n"
                              << "  ret i32 0\n"
                              << "}\n\n";
                    note_override_const(
                        static_cast<uint32_t>(sym.address & ~1u), 0);
                    lifter.register_lifted_function(
                        static_cast<uint32_t>(sym.address & ~1u), sym.name);
                    ++lifted;
                    continue;
                }
            }
            if (is_guard_runtime(sym.name)) {
                // Forward to the bionic-shim's pointer-aware
                // implementation. Each shim takes (i32 guard_addr, ...)
                // and returns i32 (1=do init, 0=skip for acquire;
                // ignored for release/abort). The lifted ABI is i32
                // x 5 args -- pass arg0 = guard_addr through and
                // ignore the others.
                module_ir << "; --- libstdc++ static-init guard override ---\n"
                          << "declare i32 @__macapk_shim_" << sym.name
                          << "(i32, i32, i32, i32)\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %guard, i32 %a1, i32 %a2, i32 %a3, "
                             "i32 %sp) local_unnamed_addr {\n"
                          << "entry:\n"
                          << "  %r = call i32 @__macapk_shim_" << sym.name
                          << "(i32 %guard, i32 0, i32 0, i32 0)\n"
                          << "  ret i32 %r\n"
                          << "}\n\n";
                // The shim really is `uint32_t(uint32_t guard, uint32_t,
                // uint32_t, uint32_t)`; the IR above zeroes a1..a3 only
                // because they are unread. Forwarding all five registers
                // is therefore equivalent, which is what lets the shared
                // OverrideShim shape cover this family.
                note_override_shim(
                    static_cast<uint32_t>(sym.address & ~1u),
                    "__macapk_shim_" + sym.name);
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
            // CCFileUtils::fullPathFromRelativePath(const char*): RETIRED by
            // default (MACAPK_COCOS_OWN_FULLPATH, see cocos_own_fullpath_on).
            // The guest's own body is translated like any other function, as
            // a device runs it. In HCR 1.8.1 that body is NOT a one-instruction
            // forwarder: it is a 6-instruction wrapper that passes a stack
            // ccResolutionType* in r2 and BLs the 2-arg overload, which is the
            // `mov r0,r1; bx lr` (cocos2d-x 2.0.1 `return pszRelativePath;`).
            //
            // History of the override kept below for the bisect arm: cocos2d-
            // x's getFileData has a fast path: if the filename starts
            // with '/', it opens the file directly via fopen instead
            // of going through unzOpen on the APK. When extracted assets
            // were the only copy we could serve, prepending the asset
            // directory turned every "menu-bg.png" lookup into a plain
            // fopen of the extracted file, which our fopen shim remaps --
            // bypassing an APK-zip pipeline that had no .apk to open.
            // Since v1.0.1586 the shim answers the input unchanged unless
            // MACAPK_COCOS_FORCE_FOPEN=1, so the override is installed only
            // under MACAPK_COCOS_OWN_FULLPATH=0 or that lever.
            auto is_path_remap_target = [](const std::string& n) {
                return n == "_ZN7cocos2d11CCFileUtils24fullPathFromRelativePathEPKc";
            };
            if (is_path_remap_target(sym.name)
                && !cocos_fullpath_override_installed()) {
                std::fprintf(stderr,
                    "[translator] CCFileUtils::fullPathFromRelativePath: "
                    "translating the guest's own body at 0x%x "
                    "(MACAPK_COCOS_OWN_FULLPATH=0 restores the override)\n",
                    static_cast<unsigned>(sym.address & ~1u));
                // Fall through to the ordinary lift below.
            } else if (is_path_remap_target(sym.name)) {
                module_ir << "; --- CCFileUtils::fullPathFromRelativePath path-remap override ---\n"
                          << "declare i32 @__macapk_shim_fullPathFromRelativePath"
                             "(i32, i32, i32, i32, i32)\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %a0, i32 %a1, i32 %a2, i32 %a3, "
                             "i32 %sp) local_unnamed_addr {\n"
                          << "entry:\n"
                          << "  %r = call i32 @__macapk_shim_fullPathFromRelativePath"
                             "(i32 %a0, i32 %a1, i32 %a2, i32 %a3, i32 %sp)\n"
                          << "  ret i32 %r\n"
                          << "}\n\n";
                note_override_shim(
                    static_cast<uint32_t>(sym.address & ~1u),
                    "__macapk_shim_fullPathFromRelativePath");
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
            // __dynamic_cast: RETIRED by default
            // (MACAPK_ARM32_GUEST_DYNAMIC_CAST, see
            // arm32_guest_dynamic_cast_on). The image's own libsupc++ /
            // libc++abi body is translated like any other function, as a
            // device runs it; the arm32rc translation matched the guest's
            // bytes on an ARMv7 CPU in every one of ~7.5M in-contract casts
            // across HCR 1.8.1, HCR 1.43, GD Lite 2.2.11 and JJ 1.3.5.
            //
            // History of the override kept below for the bisect arm: the
            // deleted LLVM lifter's __dynamic_cast in HCR's libgame.so
            // returned garbage non-zero when it should return NULL,
            // causing CCDirector::setNextScene to misclassify scenes as
            // CCTransitionScene and skip the onEnter call. An earlier
            // always-NULL stub broke rendering (setNextScene took the
            // non-transition cleanup path everywhere), so the host
            // Itanium-ABI walker in bionic_shim.cpp::
            // __macapk_shim_dynamic_cast replaced the body, matched by
            // the canonical symbol name in every ARM32 image.
            if (sym.name == "__dynamic_cast"
                && arm32_guest_dynamic_cast_on()) {
                std::fprintf(stderr,
                    "[translator] __dynamic_cast: translating the guest's "
                    "own body at 0x%x (MACAPK_ARM32_GUEST_DYNAMIC_CAST=0 "
                    "restores the host RTTI walker)\n",
                    static_cast<unsigned>(sym.address & ~1u));
                // Fall through to the ordinary lift below.
            } else if (sym.name == "__dynamic_cast") {
                module_ir << "; --- __dynamic_cast: proper RTTI walker override ---\n"
                          << "declare i32 @__macapk_shim_dynamic_cast"
                             "(i32, i32, i32, i32, i32)\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %a0, i32 %a1, i32 %a2, i32 %a3, "
                             "i32 %sp) local_unnamed_addr {\n"
                          << "entry:\n"
                          << "  %r = call i32 @__macapk_shim_dynamic_cast"
                             "(i32 %a0, i32 %a1, i32 %a2, i32 %a3, i32 %sp)\n"
                          << "  ret i32 %r\n"
                          << "}\n\n";
                note_override_shim(
                    static_cast<uint32_t>(sym.address & ~1u),
                    "__macapk_shim_dynamic_cast");
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
            // Override cocos2d::CCImage::_initWithPngData with a host-side
            // libpng decode. The lifted version of HCR's libpng + the
            // cocos2d-x decode driver corrupts png_struct field memory
            // after ~95 rows of a 256-row image (we suspect a lifter
            // miscompile in the row-processing inner loop). Using host
            // libpng sidesteps the problem entirely and lets HCR get a
            // correctly decoded RGBA buffer.
            //
            // MACAPK_COCOS_PNG_LAYOUT_CHECK (default ON): the shim writes ONE
            // build's CCImage layout, so install it only on a binary whose own
            // CCImage ctor stores that layout (cocos_ccimage_layout above). Any
            // other build keeps -- and translates -- its OWN _initWithPngData and
            // its own statically linked libpng, which is what a device runs.
            // =0 restores the old name-only match.
            auto is_cocos_init_png = [](const std::string& n) {
                return n == "_ZN7cocos2d7CCImage16_initWithPngDataEPvi";
            };
            bool cocos_png_override = false;
            if (is_cocos_init_png(sym.name)) {
                CocosCCImageLayout l;
                cocos_png_override = cocos_png_override_decision(img, &l);
                std::fprintf(stderr,
                    "[translator] CCImage layout %s w=0x%x h=0x%x bpc=0x%x data=0x%x "
                    "alpha=0x%x premulti=0x%x (%s) -> %s _initWithPngData\n",
                    l.parsed ? (cocos_ccimage_layout_matches_shim(l) ? "MATCHES shim"
                                                                    : "DIFFERS from shim")
                             : "UNPARSED",
                    l.width, l.height, l.bpc, l.data, l.alpha, l.premulti,
                    l.why.c_str(),
                    cocos_png_override ? "overriding" : "translating the guest's own");
            }
            if (cocos_png_override) {
                module_ir << "; --- cocos2d::CCImage::_initWithPngData host-side decode override ---\n"
                          << "declare i32 @__macapk_shim_cocos_init_png_data"
                             "(i32, i32, i32, i32, i32)\n"
                          << "define dso_local i32 @\"" << sym.name
                          << "\"(i32 %a0, i32 %a1, i32 %a2, i32 %a3, "
                             "i32 %sp) local_unnamed_addr {\n"
                          << "entry:\n"
                          << "  %r = call i32 @__macapk_shim_cocos_init_png_data"
                             "(i32 %a0, i32 %a1, i32 %a2, i32 %a3, i32 %sp)\n"
                          << "  ret i32 %r\n"
                          << "}\n\n";
                note_override_shim(
                    static_cast<uint32_t>(sym.address & ~1u),
                    "__macapk_shim_cocos_init_png_data");
                lifter.register_lifted_function(
                    static_cast<uint32_t>(sym.address & ~1u), sym.name);
                ++lifted;
                continue;
            }
        }
        auto lf = lifter.lift_function(img, sym);
        if (!lf.success) {
            if (verbose) {
                std::fprintf(stderr, "[translator] skip %s: %s\n",
                             sym.name.c_str(), lf.error.c_str());
            }
            ++skipped;
            continue;
        }
        // ARM32-only: rewrite the lifted symbol's first definition to
        // an internal helper name so we can emit a real-ABI thunk
        // under the original name.
        if (!aapcs && looks_like_jni_export(sym.name)) {
            std::string body = lf.ir_body;
            std::string original = "@" + sym.name + "(";
            std::string renamed  = "@__macapk_arm32_" + sym.name + "(";
            size_t at = body.find(original);
            if (at != std::string::npos) {
                body.replace(at, original.size(), renamed);
                jni_exports.push_back(sym.name);
            }
            module_ir << body;
        } else {
            module_ir << lf.ir_body;
        }
        ++lifted;
    }

    // ARM32 only: discover and lift unsymbolled functions. The ELF
    // symbol table is the lifter's normal source of function entries;
    // anything not listed there starts out as a panic stub. Stripped
    // .so files (and many compiler-internal helpers like __aeabi_*
    // intra-module variants, ABI thunks, and exception-handling
    // landing pads) appear ONLY as BL targets in the lifted code's
    // call graph. Plus the dynamic linker's .init_array entries
    // (C++ static constructors for cocos2d::CCPoolManager, etc.) --
    // those are listed only in .init_array and are never reached via
    // a BL from the symbol-tabled functions, so we seed them in the
    // discovery loop manually. We walk lifter.external_targets()
    // iteratively: any address that lands inside an executable
    // PT_LOAD segment and is not an import PLT stub gets lifted as a
    // synthetic function.  Each iteration may discover further
    // unsymbolled callees, so we loop to fixed point. Per-iteration:
    // we sort the known starts and use the next-greater entry as the
    // synthetic's upper bound, which mirrors how compilers lay out
    // functions contiguously.
    [[maybe_unused]] int discovered = 0;
    [[maybe_unused]] int discovered_init = 0;
    if constexpr (!aapcs) {
        // Build the initial set of known function starts (from the
        // symbol table) and the set of PLT stub addresses (which we
        // never want to lift -- they're our import thunks).
        std::set<uint32_t> known_starts;
        for (const auto& [addr, _name] : lifter.lifted_address_map()) {
            known_starts.insert(static_cast<uint32_t>(addr));
        }
        // Per-known-start thumbness map. Populated alongside
        // known_starts; the BL/synth pass uses this to inherit mode
        // from a nearby prologue-scan-discovered entry when its own
        // `sniff_thumb_at` heuristic is unreliable. Symbol-table
        // entries are populated below from the symbol's `is_thumb`
        // flag; prologue-scan and BL/synth lifts add their entries
        // as they go.
        std::unordered_map<uint32_t, bool> known_thumb;
        // Seed from the symbol table.
        for (const auto& s : img.symbols) {
            if (s.type != pt::STT_FUNC) continue;
            known_thumb[static_cast<uint32_t>(s.address & ~1u)]
                = s.is_thumb;
        }
        std::unordered_set<uint32_t> plt_addrs;
        for (const auto& imp : img.imports) {
            if (imp.plt_addr) plt_addrs.insert(imp.plt_addr);
        }
        // Build a sorted [start, end) -> is_thumb table from the
        // symbol table so unsymbolled discovered targets that fall
        // inside a known function's body inherit that function's
        // mode. ARM and Thumb code coexist in cocos2d-x dylibs (most
        // is ARM, libxml2 + a few helpers are Thumb); without this
        // lookup the discovery default of `false` (ARM) decodes the
        // Thumb function bodies as gibberish, which then produces
        // bogus BL targets that cascade into the panic-stub set.
        // Helper: emit a pure-thrower override stub for an unsymboled
        // synth function. The stub is `ret i32 0`, and gets registered
        // with the lifter so other code that calls
        // __macapk_target_<vaddr> resolves cleanly to it. Returns
        // true if the override was emitted and the caller should skip
        // the normal lift path.
        // RETIRED by default (MACAPK_ARM32_THROW_HELPERS_RUN): returns false,
        // so the synth is translated from the guest's own bytes; only the =0
        // bisect arm emits the stub.
        auto emit_pure_thrower_synth = [&](uint32_t vaddr) -> bool {
            if (arm32_throw_helpers_run()) return false;
            if (!pure_throw_helpers.count(vaddr)) return false;
            // Use the same naming convention the lifter uses for
            // unsymbolled synthetic functions.
            char buf[64];
            std::snprintf(buf, sizeof(buf),
                          "__macapk_synth_%08x", vaddr);
            std::string name = buf;
            module_ir << "; --- pure-thrower override (ret 0) for synth ---\n"
                      << "define dso_local i32 @\"" << name
                      << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                         "i32 %arg_sp) local_unnamed_addr {\n"
                      << "entry:\n"
                      << "  ret i32 0\n"
                      << "}\n\n";
            note_override_const(vaddr, 0);
            lifter.register_lifted_function(vaddr, name);
            return true;
        };
        // Diagnostic stub mechanism: MACAPK_STUB_VADDRS env var
        // is a comma/space-separated list of hex vaddrs. Any synth
        // whose start matches a stub vaddr gets replaced with a
        // `ret 0` body (same shape as the pure-thrower override).
        // Use to bisect lifter bugs: stub the synth at the
        // suspected corruption source and see whether downstream
        // behaviour changes. NOT for steady-state use; logged
        // verbose at translator startup so it's obvious when
        // diagnostic stubs are active.
        static std::set<uint32_t> stub_vaddrs;
        static bool stub_vaddrs_loaded = false;
        if (!stub_vaddrs_loaded) {
            stub_vaddrs_loaded = true;
            const char* env = std::getenv("MACAPK_STUB_VADDRS");
            if (env && *env) {
                std::fprintf(stderr,
                    "[translator] MACAPK_STUB_VADDRS=%s\n", env);
                const char* p = env;
                while (*p) {
                    while (*p == ',' || *p == ' ' || *p == '\t') ++p;
                    if (!*p) break;
                    char* endp = nullptr;
                    unsigned long v = std::strtoul(p, &endp, 16);
                    if (endp == p) break;
                    stub_vaddrs.insert(static_cast<uint32_t>(v));
                    std::fprintf(stderr,
                        "[translator] stubbing synth at 0x%lx as ret 0\n", v);
                    p = endp;
                }
            }
        }
        auto emit_stub_synth = [&](uint32_t vaddr) -> bool {
            if (!stub_vaddrs.count(vaddr)) return false;
            char buf[64];
            std::snprintf(buf, sizeof(buf),
                          "__macapk_synth_%08x", vaddr);
            std::string name = buf;
            module_ir << "; --- diagnostic stub (MACAPK_STUB_VADDRS) ---\n"
                      << "define dso_local i32 @\"" << name
                      << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                         "i32 %arg_sp) local_unnamed_addr {\n"
                      << "entry:\n"
                      << "  ret i32 0\n"
                      << "}\n\n";
            note_override_const(vaddr, 0);
            lifter.register_lifted_function(vaddr, name);
            return true;
        };
        // =====================================================================
        // [DIAGNOSTIC, USER-PERMITTED PER-APK]
        //   MACAPK_PATH_REMAP_VADDRS -- cocos2d-x v2 CCFileUtils::
        //                                fullPathFromRelativePath override
        // ---------------------------------------------------------------------
        // What it does:
        //   Env var is a comma/space-separated list of hex vaddrs. Any
        //   synth whose start matches one of these addresses gets its
        //   body replaced with a tail-call to
        //   `__macapk_shim_fullPathFromRelativePath` (in bionic_shim.cpp)
        //   which rewrites a relative asset name (e.g. "garage/aaa.dat")
        //   into an absolute path under `${apkt_dir}/assets/...`. fopen
        //   then succeeds on the host filesystem.
        // Why it exists:
        //   In cocos2d-x v2, asset loads go through CCFileUtils::
        //   fullPathFromRelativePath which (in real Android builds)
        //   returns either an absolute path or a path that
        //   getFileData's zip-reader branch resolves. Our zip cache
        //   is empty so the relative-path branch never reaches
        //   fopen, and the asset load fsasserts. Overriding the
        //   function with the shim coerces every asset name to an
        //   absolute path that fopen handles directly.
        // Why it's "per-APK"-flavored:
        //   The fullPathFromRelativePath vaddr varies per build. HCR
        //   1.43 ARM32 has it at 0x222238 (verified by deduction:
        //   one of 4 `mov r0, r1; bx lr` no-op forwarders in the
        //   stripped binary, the one called in the asset-load chain
        //   at 0xae2d4). Other builds have it elsewhere. The user
        //   passes the vaddr on the command line; if no env var is
        //   set the override is inactive and has zero translation-
        //   time cost.
        // Removal condition / framework-fix path:
        //   Three options for going generic:
        //   1) Lifter-side detection: scan all 2-instruction `mov r0,
        //      r1; bx lr` synths in cocos2d-x v2 binaries; the one(s)
        //      called from the asset-load BL chain at offsets
        //      matching CCFileUtils method indices ARE the target.
        //      Auto-emit the override for them.
        //   2) Shim-side detection: hook every potential cocos2d-x
        //      file-load vtable slot and replace at runtime. More
        //      invasive but doesn't require binary analysis.
        //   3) Runtime-side fallback in the existing fopen shim:
        //      when the lifted code asks for "garage/aaa.dat" via
        //      fopen, our shim already rewrites to apkt/assets/
        //      paths. The problem is the lifted code never CALLS
        //      fopen for asset loads -- it calls
        //      fullPathFromRelativePath first, which returns the
        //      relative path unchanged, and the zip-reader branch
        //      eats it. If the lifter can detect "this synth's
        //      body returns its r1 unchanged" (already known shape)
        //      and substitute it for ALL apps, we're done.
        //
        // VERIFIED CASES (per-APK observations, for future generic
        // detection):
        //   - HCR 1.43 ARM32, vaddr 0x222238, fullPathFromRelativePath.
        //     Body shape: 2 instructions `mov r0, r1; bx lr`. Called
        //     from the asset-loader chain (CCFileUtils::getFileData
        //     equivalent) at vaddr 0xae2d4.
        //
        // Documentation pointer:
        //   `Current_State.md` v1.0.57 entry (where the override
        //   landed) and `project_lifter_literal_pool_detection.md`
        //   (where the diagnostic was preserved).
        //
        // RETIRED by default (MACAPK_COCOS_OWN_FULLPATH, 2026-09-26): the list
        // is honoured only when the named override above is installed too
        // (MACAPK_COCOS_OWN_FULLPATH=0 or MACAPK_COCOS_FORCE_FOPEN=1), so both
        // bisect levers keep their exact pre-change ARM32 meaning. Otherwise a
        // set value is ignored with one warning. Two reasons it is not a live
        // tool: (1) since v1.0.1586 the shim it forwards to answers the input
        // unchanged by default, the same answer as the `mov r0,r1; bx lr` it
        // replaces, so it was already a no-op; (2) it has NO positive
        // identification gate, which CLAUDE.md requires of any hardcoded vaddr:
        // 0x222238 is that forwarder in HCR 1.43 armeabi-v7a, but in the SAME
        // APK's armeabi libgame.so it is `beq 0x222268` in the middle of an
        // unrelated function. Its only recorded target (HCR 1.43 ARM32) never
        // runs ARM32 anyway, because ABI pruning always picks arm64-v8a there.
        static std::set<uint32_t> path_remap_vaddrs;
        static bool path_remap_loaded = false;
        if (!path_remap_loaded) {
            path_remap_loaded = true;
            const char* env = std::getenv("MACAPK_PATH_REMAP_VADDRS");
            if (env && *env && !cocos_fullpath_override_installed()) {
                std::fprintf(stderr,
                    "[translator] MACAPK_PATH_REMAP_VADDRS=%s IGNORED: retired "
                    "with the fullPathFromRelativePath override "
                    "(MACAPK_COCOS_OWN_FULLPATH=0 honours it again)\n", env);
            } else if (env && *env) {
                std::fprintf(stderr,
                    "[translator] MACAPK_PATH_REMAP_VADDRS=%s\n", env);
                const char* p = env;
                while (*p) {
                    while (*p == ',' || *p == ' ' || *p == '\t') ++p;
                    if (!*p) break;
                    char* endp = nullptr;
                    unsigned long v = std::strtoul(p, &endp, 16);
                    if (endp == p) break;
                    path_remap_vaddrs.insert(static_cast<uint32_t>(v));
                    std::fprintf(stderr,
                        "[translator] path-remap override at 0x%lx\n", v);
                    p = endp;
                }
            }
        }
        auto emit_path_remap_synth = [&](uint32_t vaddr) -> bool {
            if (!path_remap_vaddrs.count(vaddr)) return false;
            char buf[64];
            std::snprintf(buf, sizeof(buf),
                          "__macapk_synth_%08x", vaddr);
            std::string name = buf;
            module_ir << "; --- path-remap override (MACAPK_PATH_REMAP_VADDRS) ---\n"
                      << "declare i32 @__macapk_shim_fullPathFromRelativePath"
                         "(i32, i32, i32, i32, i32)\n"
                      << "define dso_local i32 @\"" << name
                      << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                         "i32 %arg_sp) local_unnamed_addr {\n"
                      << "entry:\n"
                      << "  %r = call i32 @__macapk_shim_fullPathFromRelativePath"
                         "(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, i32 %arg_sp)\n"
                      << "  ret i32 %r\n"
                      << "}\n\n";
            note_override_shim(vaddr,
                               "__macapk_shim_fullPathFromRelativePath");
            lifter.register_lifted_function(vaddr, name);
            return true;
        };
        struct ThumbRange {
            uint32_t start;
            uint32_t end;       // exclusive
            bool     is_thumb;
        };
        std::vector<ThumbRange> thumb_ranges;
        thumb_ranges.reserve(img.symbols.size());
        for (const auto& s : img.symbols) {
            if (s.size == 0) continue;
            thumb_ranges.push_back({s.address, s.address + s.size,
                                     s.is_thumb});
        }
        std::sort(thumb_ranges.begin(), thumb_ranges.end(),
                  [](const ThumbRange& a, const ThumbRange& b) {
                      return a.start < b.start;
                  });
        // Fast lookup: is `vaddr` strictly inside the body of any
        // symbol-tabled function (i.e. > start AND < end)? Equality
        // with start returns false because the start IS a real
        // function entry. Used to filter out discovery candidates
        // (BL targets, data fnptr seeds, init_array seeds, prologue
        // scan hits) that fall in the middle of an already-known
        // function. Without this filter, a candidate at e.g.
        // sym_start+0xc creates a parallel synth_<addr> that lifts
        // the SAME bytes as the parent function starting from an
        // arbitrary mid-function instruction, with fresh-zero
        // register state. When called via __macapk_target_<addr>
        // it runs a loop body with whatever r0..r3 the caller had
        // and reads garbage from r4+ that the parent's prologue
        // would have initialized.
        auto inside_known_body = [&](uint32_t vaddr) -> bool {
            auto it = std::upper_bound(
                thumb_ranges.begin(), thumb_ranges.end(), vaddr,
                [](uint32_t a, const ThumbRange& r) { return a < r.start; });
            if (it == thumb_ranges.begin()) return false;
            --it;
            return vaddr > it->start && vaddr < it->end;
        };
        // v1.0.838: SHARED TAIL ENTRIES -- a cross-function `b` into a SIBLING
        // symbol's mid-body.
        //
        // inside_known_body() above is applied as an UNCONDITIONAL `continue`
        // at every discovery site (unlike inside_lifted_body(), which has
        // BL-provenance / prologue / terminator carve-outs). The reasoning was
        // that a symbol's [st_value, st_value+st_size) is authoritative linker
        // data, so an interior address cannot be an independent entry point.
        // That is true of compiler-generated C/C++, and FALSE of libgcc's
        // hand-written ARM soft-float assembly, which deliberately shares tail
        // code between sibling helpers by branching into another helper's
        // body. From libgcc/config/arm/ieee754-sf.S (gcc-mirror, master):
        //
        //     ARM_FUNC_START floatunsisf
        //         mov  r3, #0
        //         b    1f                  @ <-- into floatsisf's body
        //     ARM_FUNC_START floatsisf
        //         ands r3, r0, #0x80000000
        //         ...
        //     1:  movs ip, r0
        //
        // and divsf3 reaches mulsf3's shared special-value tails by
        // `b LSYM(Lml_z)` / `Lml_n` / `Lml_i` / `Lml_u` -- labels DEFINED
        // inside mulsf3. Each such target therefore got no lifted body and no
        // __macapk_target_<addr> alias, so the branch bound to the return-0
        // panic stub. Because these are TAIL branches (`b`, not `bl`), LR still
        // points at the ORIGINAL caller, so the panic helper's `return 0`
        // returns 0 to the app: measured on Jetpack Joyride (armeabi-only,
        // libmortargame.so) `__aeabi_i2f` / `__aeabi_ui2f` (int->float) and
        // `__aeabi_dadd` reached from `__floatsidf` / `__extendsfdf2`
        // (int/float->double), plus `__nesf2` / `__nedf2` reached from
        // `__gtsf2` / `__ltsf2` (float compares), ALL returned 0 -- which is
        // why the app dispatched 4200 frames while issuing zero GL calls.
        //
        // THE PREDICATE IS PROVABLE, NOT HEURISTIC. A target qualifies only
        // when BOTH endpoints of the branch sit strictly inside ARM-mode sized
        // STT_FUNC symbols and those symbols DIFFER:
        //   - both endpoints in known ARM code  => the word really is an ARM
        //     B/BL, not a Thumb pair or a literal-pool word misread as one
        //     (the failure mode that makes blind .text sweeps unreliable here);
        //   - target strictly interior          => not an already-known entry;
        //   - source symbol != target symbol    => the v1.0.28 hazard (HCR 1.43
        //     `synth_0017a3a0`, an INTRA-function `b` mis-classified as extern)
        //     is excluded BY CONSTRUCTION rather than by a byte heuristic.
        // BLX #imm is skipped outright: it switches to Thumb, so its target is
        // not ARM code and the mode-derived reasoning above would not hold.
        //
        // Measured selection set on libmortargame.so: exactly 17 targets, every
        // one a compiler-runtime soft-float helper (__aeabi_dadd, __muldf3,
        // __mulsf3, __floatdisf, __floatsisf, __nesf2, __nedf2). The looser
        // "any sized symbol" variant selects 50, including branches into
        // _ZTV*/_ZTI* vtables -- i.e. genuine misdecodes -- so the ARM-mode
        // STT_FUNC requirement on BOTH ends is what makes this clean.
        //
        // GATED DEFAULT-OFF. This adds entries to known_starts, and this
        // project's discovery heuristics have a violent regression history in
        // both directions (v1.0.29 +5132 functions, v1.0.30/31 a 17894->825
        // collapse), so it must not go default-ON without the protected sweep.
        // Enable with MACAPK_ARM32_SHARED_TAIL_ENTRY=1.
        //
        // KNOWN RESIDUAL (deliberate, not an oversight): the continuation is
        // lifted as an independent function starting at the target, so a branch
        // from inside that continuation BACKWARD into the enclosing symbol's
        // earlier body is itself cross-range but INTRA-symbol, so the predicate
        // (correctly) does not select it and it stays unresolved. __mulsf3's
        // `b Lml_x` back-edge at +0x110 is the real instance. The complete fix
        // is a lifted body with MULTIPLE ENTRY POINTS rather than N independent
        // continuations; that belongs in arm32rc's label map, not here.
        static const bool s_shared_tail_entry = [](){
            const char* e = std::getenv("MACAPK_ARM32_SHARED_TAIL_ENTRY");
            return e && *e && *e != '0'; }();
        std::unordered_set<uint32_t> shared_tail_entries;
        if (s_shared_tail_entry) {
            struct ArmFn { uint32_t start; uint32_t end; };
            std::vector<ArmFn> armfns;
            armfns.reserve(img.symbols.size());
            for (const auto& s : img.symbols) {
                // STT_FUNC(2), a real size, ARM mode (Bionic puts Thumb-ness in
                // st_value's low bit, which Elf32Image surfaces as is_thumb).
                if (s.size == 0 || s.type != 2 || s.is_thumb) continue;
                const uint32_t a = s.address & ~1u;
                armfns.push_back({a, a + s.size});
            }
            std::sort(armfns.begin(), armfns.end(),
                      [](const ArmFn& a, const ArmFn& b) {
                          return a.start < b.start;
                      });
            // Enclosing ARM function for `a`, INNERMOST first. Ranges legally
            // overlap: the EABI compare helpers are nested by fall-through
            // (__gtsf2 @+0 falls into __ltsf2 @+8 falls into __nesf2 @+0x10,
            // all three sharing one end), so the highest start that still
            // contains `a` is the one whose code `a` actually belongs to.
            auto encl = [&](uint32_t a) -> const ArmFn* {
                auto it = std::upper_bound(
                    armfns.begin(), armfns.end(), a,
                    [](uint32_t v, const ArmFn& r) { return v < r.start; });
                while (it != armfns.begin()) {
                    --it;
                    if (a >= it->start && a < it->end) return &*it;
                    // Ranges are start-sorted but variable-length, so a
                    // non-containing predecessor does not prove the rest fail;
                    // keep walking back while a start could still cover `a`.
                    if (it->start + 0x10000u < a) break;
                }
                return nullptr;
            };
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                const size_t n = seg.bytes.size();
                for (size_t off = 0; off + 4 <= n; off += 4) {
                    uint32_t w;
                    std::memcpy(&w, seg.bytes.data() + off, 4);
                    if (((w >> 25) & 0x7u) != 0x5u) continue;   // not B/BL class
                    if ((w >> 28) == 0xFu) continue;            // BLX imm: -> Thumb
                    int32_t imm = static_cast<int32_t>(w & 0x00FFFFFFu);
                    if (imm & 0x00800000) imm -= 0x01000000;    // sign-extend imm24
                    const uint32_t va = seg.vaddr + static_cast<uint32_t>(off);
                    // ARM branch: target = PC + 8 + (imm24 << 2).
                    const uint32_t tgt =
                        va + 8u + static_cast<uint32_t>(imm) * 4u;
                    if (tgt & 3u) continue;
                    const ArmFn* T = encl(tgt);
                    if (!T || tgt == T->start) continue;
                    const ArmFn* S = encl(va);
                    if (!S || S->start == T->start) continue;
                    shared_tail_entries.insert(tgt);
                }
            }
            if (!shared_tail_entries.empty())
                std::fprintf(stderr,
                    "[translator] shared-tail-entry: %zu cross-function "
                    "mid-body branch target(s)\n", shared_tail_entries.size());
        }
        auto is_shared_tail_entry = [&](uint32_t vaddr) -> bool {
            return shared_tail_entries.count(vaddr) > 0;
        };
        // Parallel tracking for SYNTH functions we've lifted in this
        // pipeline run. Each entry is [start, start + lift_size_cap)
        // where lift_size_cap is the byte budget we passed to
        // lift_synthetic at the time. The cap is conservative
        // (bounded by the next known_starts entry or 16 KiB), so it
        // may over-cover by a few bytes at the tail of a small
        // function -- the cost is rejecting a few legitimate small
        // helper entries; the benefit is catching mid-function BL
        // targets (intra-function branches the lifter mis-classified
        // as tail-calls) that would otherwise spawn a bogus synth.
        // Kept as a sorted vector so we can binary-search per lookup.
        struct LiftedRange {
            uint32_t start;
            uint32_t end;     // exclusive
            // v1.0.806: every address this range's own decode recorded as a
            // real instruction start (LiftedFunction::insns, sorted
            // ascending). PROVABLE, not a guess -- Capstone actually walked
            // these bytes and stopped here between two instructions. Used
            // by is_provably_mid_instruction: an address strictly inside
            // [start, end) that is NOT in this list cannot be a legitimate
            // independent function entry, because this function's own
            // linear decode ran straight over it without ever treating it
            // as a boundary -- it is provably the middle of some OTHER
            // instruction (a misaligned/mode-confused read) or, more often
            // for the ARM32 fixed-width case, present here but reached only
            // by ordinary fall-through from the previous (non-terminator)
            // entry -- see is_provably_mid_instruction's own comment for how
            // that second case is distinguished.
            std::vector<uint32_t> insn_starts;
        };
        std::vector<LiftedRange> lifted_synth_ranges;
        // vaddr -> the byte budget this synth was actually lifted with.
        // The incumbent computes each budget against `anticipated_starts`
        // AS OF THAT ROUND, so re-deriving it later from the FINAL
        // known_starts gives a different (sometimes far smaller) answer:
        // HCR's __macapk_synth_001fed46 re-derives to 2 bytes because
        // 001fed48 was discovered afterwards, and a 2-byte extent is
        // under one ARM instruction, so cs_disasm returns 0 and the
        // consumer drops the function outright. Recording the budget is
        // the only way to say "what the incumbent decoded" rather than
        // "what the rule would say now". Filled by record_lifted_range,
        // which every successful synth lift already calls.
        std::unordered_map<uint32_t, uint32_t> synth_lift_budget;
        // Scan the emitted IR body for the MAX vaddr in `; XXXXXX:`
        // instruction-trace comments AND extract the mnemonic of the
        // last lifted instruction. The lifter emits one such comment
        // per ARM/Thumb instruction it successfully decoded
        // (format: `  ; <hex_no_leading_zeros>:  <mnemonic> ...`).
        //
        // We need both pieces of info to distinguish a clean exit
        // (function ended with a real return / unconditional branch
        // before the size budget) from a bail (cs_disasm hit a byte
        // sequence it couldn't decode, e.g. a mid-function literal
        // pool). For the clean-exit case, end_actual is the real
        // function end and we use it as the in-body range upper
        // bound. For the bail case, the lifter never decoded
        // instructions past the bail point even though they're
        // within the budget; intra-function branches to addresses
        // past the bail leak out as extern targets and spawn bogus
        // synths. Using start + size (the full budget) as the range
        // for bailed lifts catches those mid-function extern
        // targets.
        struct IrScanResult {
            uint32_t last_addr;          // last lifted instruction address
            uint32_t last_terminator;    // address of last unconditional return
            // v1.0.65: byte size of the last_terminator instruction.
            // Without this, record_lifted_range was using a fixed +4
            // for the terminator's footprint, which over-shoots by 2
            // bytes when the terminator is Thumb16 (e.g. `pop {r4,
            // pc}` at 0xbdXX = 2 bytes). The over-shoot pulls the
            // recorded range into the start of the NEXT region's
            // bytes, mis-classifying real function entries that
            // happen to live exactly 2-3 bytes past a 2-byte
            // terminator as "inside_lifted_body" and rejecting them
            // from later discovery. HCR 1.43 motivating case: the
            // function at 0x2c5d74 ends with Thumb16 `pop {r4, pc}`
            // at 0x2c5d80; with the old +4, the recorded range was
            // [0x2c5d74, 0x2c5d84) (4 bytes too long), swallowing
            // 0x2c5d82 -- which is the start of an inlined helper
            // path that xmlDictCreate BLs into. The helper never
            // got its own lifted body, the BL resolved to a panic
            // stub, and HCR's first GL call was followed by a
            // null-pointer dispatch and JVM heap corruption.
            uint32_t last_terminator_size;
            bool     last_is_terminator;
        };
        // v1.0.804: consumes LiftedFunction::insns instead of re-parsing the
        // IR comment text. Same algorithm, same result -- the discovery
        // sidecar is byte-identical -- but discovery no longer depends on a
        // 917 MB artifact nothing compiles, nor on the exact spelling of a
        // comment. (Reformat a comment under the old scheme and function
        // extents moved silently; the stakes are in the header comment on
        // DecodedInsn.)
        auto scan_insns = [&](const std::vector<pt::DecodedInsn>& insns,
                              uint32_t start) -> IrScanResult {
            IrScanResult r{start, start, 4, false};
            for (const auto& di : insns) {
                if (di.addr > r.last_addr) r.last_addr = di.addr;
                // The address of the most recent CLEAN TERMINATOR. With
                // SKIPDATA on, Capstone decodes the whole byte range, so
                // last_addr lands at the budget tail rather than the function
                // end; the real end is the last unambiguous return idiom.
                // `b` is deliberately NOT counted -- it is a terminator for a
                // tail call but also an ordinary intra-function branch.
                if (di.ret_idiom && di.addr > r.last_terminator)
                    r.last_terminator = di.addr;
            }
            r.last_is_terminator = (r.last_terminator > start);
            // Terminator byte size: prefer the smallest recorded address
            // strictly greater than it (that delta IS its size). Otherwise
            // pattern-match the encoding out of the ELF -- Thumb-16 returns
            // (`pop {..pc}` = 0xbdXX, `bx Rm` = 0x47XX) are 2 bytes and
            // everything else is 4. Getting this wrong over-shoots the
            // recorded range into the next region and mis-classifies real
            // function entries (v1.0.65).
            if (r.last_is_terminator) {
                uint32_t next_after = 0;
                bool found = false;
                for (const auto& di : insns) {
                    if (di.addr > r.last_terminator
                        && (!found || di.addr < next_after)) {
                        next_after = di.addr;
                        found = true;
                    }
                }
                if (found) {
                    uint32_t sz = next_after - r.last_terminator;
                    r.last_terminator_size = (sz == 2 || sz == 4) ? sz : 4;
                } else {
                    auto bytes = img.code_at(r.last_terminator, 4);
                    if (bytes.size() >= 2) {
                        uint8_t b1 = bytes[1];
                        r.last_terminator_size = (b1 == 0xbd || b1 == 0x47) ? 2 : 4;
                    } else {
                        r.last_terminator_size = 4;
                    }
                }
            }
            return r;
        };
        auto record_lifted_range = [&](uint32_t start, uint32_t size,
                                        const std::vector<pt::DecodedInsn>& insns) {
            // Every successful synth lift routes through here, so this is
            // the one place that sees the budget each one really used.
            synth_lift_budget[start] = size;
            IrScanResult sr = scan_insns(insns, start);
            // Prefer the last clean TERMINATOR address: with SKIPDATA
            // on, Capstone decodes past the function's real end into
            // whatever bytes follow, so last_addr is not a reliable
            // function-end indicator. last_terminator points at the
            // last bx lr / pop {..pc} / blx -- the function genuinely
            // ends 4 bytes after that. Fall back to last_addr only
            // when no terminator was found (e.g., a thrower stub).
            // v1.0.65: use the SIZE of the last terminator (not a
            // fixed +4), so a 2-byte Thumb16 `pop` doesn't get a
            // 4-byte footprint and pull in two extra bytes that
            // belong to the next region.
            uint32_t end_actual;
            if (sr.last_is_terminator) {
                end_actual = sr.last_terminator + sr.last_terminator_size;
            } else if (sr.last_addr > start) {
                end_actual = sr.last_addr + 4;
            } else {
                end_actual = start + 4;
            }
            uint32_t end_capped = start + size;
            uint32_t end = std::min(end_actual, end_capped);
            LiftedRange r{start, end, {}};
            // v1.0.806: record every address Capstone actually decoded as
            // an instruction start, sorted, for is_provably_mid_instruction.
            // Only keep ones inside the (possibly capped) recorded range --
            // SKIPDATA can decode past `end` into the next region's bytes,
            // and those addresses describe someone else's instructions, not
            // this function's.
            r.insn_starts.reserve(insns.size());
            for (const auto& di : insns) {
                if (di.addr >= start && di.addr < end)
                    r.insn_starts.push_back(di.addr);
            }
            std::sort(r.insn_starts.begin(), r.insn_starts.end());
            r.insn_starts.erase(
                std::unique(r.insn_starts.begin(), r.insn_starts.end()),
                r.insn_starts.end());
            auto it = std::upper_bound(
                lifted_synth_ranges.begin(), lifted_synth_ranges.end(),
                start, [](uint32_t a, const LiftedRange& x) {
                    return a < x.start;
                });
            lifted_synth_ranges.insert(it, std::move(r));
        };
        auto inside_lifted_body = [&](uint32_t vaddr) -> bool {
            auto it = std::upper_bound(
                lifted_synth_ranges.begin(), lifted_synth_ranges.end(),
                vaddr, [](uint32_t a, const LiftedRange& r) {
                    return a < r.start;
                });
            if (it == lifted_synth_ranges.begin()) return false;
            --it;
            return vaddr > it->start && vaddr < it->end;
        };
        // v1.0.806: strictly PROVABLE strengthening of inside_lifted_body,
        // using LiftedFunction::insns (via LiftedRange::insn_starts) instead
        // of just the [start,end) span. inside_lifted_body only proves
        // "some already-lifted function's recorded extent covers vaddr" --
        // it says nothing about whether vaddr is actually a place that
        // function's own decode ever treated as an instruction boundary.
        // This does: vaddr strictly inside a lifted range AND absent from
        // that range's own recorded instruction-start addresses means the
        // function's linear Capstone decode walked straight over vaddr
        // without ever stopping there, so it is provably not an
        // independent entry point -- either genuinely mid-instruction (a
        // misaligned/mode-confused read) or, the common ARM32 fixed-width
        // case, a real instruction address but one reached only by
        // ordinary fall-through from whatever precedes it (this function
        // will still show it as a decoded insn -- ARM32 instructions are
        // always 4-byte aligned and 4 bytes long -- so containment plus
        // "not independently callable" together are the useful signal, not
        // decode-alignment by itself). Only meaningful once the ENCLOSING
        // function has actually been lifted; see the call sites' own
        // comments for why an early classification pass can't always rely
        // on this (the enclosing function may not be discovered yet) and
        // why the terminator-precedence gate (elsewhere in this function)
        // is still the primary fix for that case.
        auto is_provably_mid_instruction = [&](uint32_t vaddr) -> bool {
            auto it = std::upper_bound(
                lifted_synth_ranges.begin(), lifted_synth_ranges.end(),
                vaddr, [](uint32_t a, const LiftedRange& r) {
                    return a < r.start;
                });
            if (it == lifted_synth_ranges.begin()) return false;
            --it;
            if (!(vaddr > it->start && vaddr < it->end)) return false;
            return !std::binary_search(
                it->insn_starts.begin(), it->insn_starts.end(), vaddr);
        };
        // Combined "is this vaddr inside some other function we
        // already committed to" predicate. Used at every insertion
        // site that takes candidates from external discovery
        // (init_array seeds, data fnptr seeds, BL/synth, prologue
        // scan) before adding them to known_starts.
        auto inside_any_known_body = [&](uint32_t vaddr) -> bool {
            return inside_known_body(vaddr) || inside_lifted_body(vaddr);
        };
        // True when `vaddr` is the destination of an actual BL / BLX
        // #imm instruction somewhere in the lifted code. Such a target
        // is an authoritative function ENTRY per the ARM PCS: the
        // compiler never emits `bl` into the middle of a function.
        // When a heuristic synth's SKIPDATA over-run wrongly recorded
        // this address as "inside its body" (inside_lifted_body ==
        // true), the BL provenance overrides that -- the absorbed
        // function MUST still get its own lifted body + alias, or the
        // call site resolves to a return-0 panic stub and the engine
        // sees garbage (HCR 1.8.1: ~17 k bl-called cocos2d-x/HCR
        // helper functions were being dropped this way, starving the
        // menu/AssetLoader/shader path and spinning updateUniform-
        // Location's uthash chain forever). The narrower
        // `looks_like_fn_prologue` byte-pattern check is kept as a
        // fallback for the ambiguous B-extern case; the genuine
        // v1.0.28 hazard (HCR 1.43 `synth_0017a3a0`, an intra-
        // function B mis-classified as extern) is reached by B, never
        // BL, so it stays correctly filtered.
        auto is_authoritative_bl_target = [&](uint32_t vaddr) -> bool {
            return lifter.external_bl_targets().count(vaddr) > 0;
        };
        // v1.0.59: AUTHORITATIVE literal-pool detection. ARM mode
        // PC-relative loads (`LDR Rd, [PC, #imm]`, `VLDR S/Dd, [PC,
        // #imm]`, `LDRD Rt, Rt2, [PC, #imm]`) target literal pool
        // words sitting between functions inside .text. The target
        // is PROVABLY data -- the load instruction tells us so.
        //
        // Strategy: linear pre-scan of every 4-byte-aligned word in
        // executable PT_LOADs. For each word that decodes as a PC-
        // relative load (ARM mode), compute the target address and
        // mark the loaded bytes as data in `literal_pool_bytes`.
        // Any function-discovery candidate at a marked address is
        // PROVABLY mid-pool and gets rejected.
        //
        // Per "ELF for the Arm Architecture" (IHI0044, §5.5.5), real
        // ARM ELF files mark these regions with `$d` mapping symbols,
        // but Android NDK strip-all removes those before publish
        // (empirically verified across HCR, FB, libgdx, libgdx-
        // freetype shipped .so files). The LDR-target scan recovers
        // the same information from the code itself -- no symbols
        // needed.
        //
        // Encodings handled (ARMv7-A):
        //   LDR  (literal) A1:   cond_31-28 010P_27-24 U0W1_23-20
        //                         1111_19-16 Rt_15-12 imm12_11-0
        //                         mask 0x0e1f0000, value 0x041f0000
        //                         (P or U or W bits vary, L=1, Rn=PC)
        //   LDRD (literal):       0001_27-24 U_23 1 (P=any W=0) 0_20
        //                         1111_19-16 Rt_15-12 imm4h_11-8
        //                         1101_7-4 imm4l_3-0
        //                         mask 0x0e5f00f0, value 0x004f00d0
        //   VLDR (literal):       1101_27-24 PUDW_23-20 1 1111_19-16
        //                         Vd_15-12 101_11-9 sz_8 imm8_7-0
        //                         mask 0x0e1f0e00, value 0x0d1f0a00
        //                         (sz=0: 4-byte single; sz=1: 8-byte
        //                         double; both target 4-aligned)
        //   LDM/LDMIA/LDMIB sans PC -- NOT a literal pool target;
        //   skipped (we only pattern-match LDR-immediate-PC)
        //   Thumb LDR-pc encodings -- handled separately in the
        //   THUMB scan below.
        //
        // Conservative: we ignore PUSH/POP-encoded literal moves and
        // 64-bit MOVW+MOVT pair tracking; those don't define literal
        // pool data, just inline constants. We also DO NOT honor
        // condition codes -- a conditional LDR with cond != AL still
        // references the same literal pool entry; the pool slot is
        // data regardless.
        static const bool s_pool_skip_nv = [] {
            const char* e = std::getenv("MACAPK_ARM32_POOL_SKIP_NV");
            return !(e && *e == '0');
        }();
        static const bool s_strw_tail_not_pop = [] {
            const char* e = std::getenv("MACAPK_ARM32_STRW_TAIL_NOT_POP");
            return !(e && *e == '0');
        }();
        std::set<uint32_t> literal_pool_bytes_arm;  // ARM-mode pool starts (4 or 8 byte ranges)
        std::set<uint32_t> literal_pool_bytes_thumb;  // Thumb-mode pool starts
        // Helper: mark `count` bytes starting at addr as literal pool.
        auto mark_pool = [](std::set<uint32_t>& out,
                            uint32_t addr, uint32_t count) {
            for (uint32_t i = 0; i < count; ++i) {
                out.insert(addr + i);
            }
        };
        for (const auto& seg : img.loadable) {
            if (!seg.executable) continue;
            const uint8_t* base = seg.bytes.data();
            uint32_t seg_start = seg.vaddr;
            // --- ARM-mode 4-byte sweep ----------------------------
            for (size_t off = 0; off + 4 <= seg.bytes.size(); off += 4) {
                uint32_t insn = uint32_t(base[off])
                              | (uint32_t(base[off+1]) << 8)
                              | (uint32_t(base[off+2]) << 16)
                              | (uint32_t(base[off+3]) << 24);
                // v1.0.1655 MACAPK_ARM32_POOL_SKIP_NV (default ON; =0 is the
                // old scan byte for byte): a word whose cond field is 0b1111
                // is NOT an LDR / LDRD / VLDR (literal). ARM ARM DDI 0406C.d
                // A5.1 "ARM instruction set encoding", Table A5-1: the
                // load/store, data-processing/misc and extension-register
                // classes are all `cond != 1111`; `cond == 1111` selects
                // "Unconditional instructions" (A5.7). The only literal load
                // in that space is the generic-coprocessor LDC/LDC2
                // (literal) (1111 110P UDW1 1111 ...), which Android code
                // never uses; the VLDR mask below also matches it when
                // coproc is 101x, but a cond=1111 word there is never a VFP
                // load (PLD/PLI literal are hints with bit 22 set, which the
                // masks reject anyway). So in practice such a word is DATA
                // or THUMB code seen through a 4-byte window, and the mark
                // it made was false. The one that mattered: GD Lite 2.2.11 v7a
                // libcocos2dcpp.so, where the Thumb-2 pair at 0x4737fc
                // (inside the static splitWithForm helper) reads as ARM
                // 0xf04ffbd3 = "LDRD literal" of 0x473758..0x47375f -- the
                // helper's own `push.w {r4-r11,lr}` entry. That false mark
                // hid 0x473758 from the prologue boundary scan and let the
                // `sub sp,#0x24` at 0x47375c count as "preceded by pool
                // data", so the helper was lifted with a 4-byte budget and
                // every CCPoint/Size/RectFromString parsed an empty vector
                // (docs/arm32-thumb2-pic-fnptr-lr-and-nopw_2026-09-27.md 7).
                if (s_pool_skip_nv && (insn >> 28) == 0xFu) continue;
                uint32_t pc_addr = seg_start + uint32_t(off);
                uint32_t pc_value = pc_addr + 8;  // ARM PC = instr+8

                // LDR (literal) A1: mask 0x0e5f0000, val 0x041f0000.
                // Note we include bit 22 (W=0 means not writeback;
                // writeback is unpredictable for Rn=PC so compilers
                // never emit it -- check the bit just to be safe).
                if ((insn & 0x0e5f0000) == 0x041f0000) {
                    uint32_t imm12 = insn & 0xfff;
                    bool U = (insn >> 23) & 1;
                    uint32_t target = U ? (pc_value + imm12)
                                        : (pc_value - imm12);
                    mark_pool(literal_pool_bytes_arm, target, 4);
                    continue;
                }
                // LDRD (literal): mask 0x0e5f00f0, val 0x004f00d0.
                // Loads 8 bytes (Rt and Rt+1). imm4h:imm4l = imm8.
                if ((insn & 0x0e5f00f0) == 0x004f00d0) {
                    uint32_t imm4h = (insn >> 8) & 0xf;
                    uint32_t imm4l = insn & 0xf;
                    uint32_t imm8 = (imm4h << 4) | imm4l;
                    bool U = (insn >> 23) & 1;
                    uint32_t target = U ? (pc_value + imm8)
                                        : (pc_value - imm8);
                    mark_pool(literal_pool_bytes_arm, target, 8);
                    continue;
                }
                // VLDR (literal): mask 0x0f1f0e00, val 0x0d1f0a00.
                // Single (sz=0, bit 8 == 0): 4 bytes; double (sz=1):
                // 8 bytes. imm8 << 2 is the byte offset.
                if ((insn & 0x0f1f0e00) == 0x0d1f0a00) {
                    uint32_t imm8 = insn & 0xff;
                    uint32_t bytes = ((insn >> 8) & 1) ? 8 : 4;
                    bool U = (insn >> 23) & 1;
                    uint32_t off_bytes = imm8 << 2;
                    uint32_t target = U ? (pc_value + off_bytes)
                                        : (pc_value - off_bytes);
                    mark_pool(literal_pool_bytes_arm, target, bytes);
                    continue;
                }
            }
            // --- Thumb-16 / Thumb-2 sweep --------------------------
            // Thumb literal-pool target alignment: PC value at the
            // load instruction is (instr_addr & ~3) + 4. The PC is
            // rounded DOWN to a 4-aligned address before adding the
            // immediate.
            //
            // Thumb-16 LDR (literal) T1: 01001_15-11 Rt_10-8 imm8_7-0
            //   bytes (LE): [imm8, 0x48..0x4f]
            //   target = ((pc_addr+4) & ~3) + (imm8 << 2). U bit
            //   implicit positive.
            // Thumb-2 LDR.W (literal) T2:
            //   half1: 1111_15-12 1000_11-8 U_7 101_6-4 1111_3-0
            //          (U=bit 7, Rn=0xf)  bytes (LE): [0xdf or 0x5f, 0xf8]
            //          ... actually it's a 32-bit Thumb-2 with first
            //          halfword `0xf85f` (U=0) or `0xf8df` (U=1) and
            //          second halfword `Rt[15-12] imm12[11-0]`.
            // Thumb-2 LDRD (literal):
            //   half1: 1110_15-12 100_11-9 P_8 U_7 1_6 W_5 1_4 1111_3-0
            //          opcode 0xe85f (P=0,W=0,U=0) plus variants. Common.
            //   half2: Rt[15-12] Rt2[11-8] imm8[7-0]
            // Thumb-2 VLDR (literal):
            //   half1: 1110_15-12 1101_11-8 PUDW_7-4 1111_3-0
            //          0xed1f or 0xed9f range, etc.
            // We implement the THREE common forms and trust the rest
            // to be caught by ARM-mode scan in their respective gaps.
            for (size_t off = 0; off + 2 <= seg.bytes.size(); off += 2) {
                uint16_t hw1 = uint16_t(base[off])
                             | (uint16_t(base[off+1]) << 8);
                uint32_t pc_addr = seg_start + uint32_t(off);
                // Thumb PC alignment: (PC+4) & ~3, where PC = current
                // instruction address.
                uint32_t pc_aligned = (pc_addr + 4) & ~3u;
                // Thumb-16 LDR (literal) T1: 0x48xx-0x4fxx
                if ((hw1 & 0xf800) == 0x4800) {
                    uint32_t imm8 = hw1 & 0xff;
                    uint32_t target = pc_aligned + (imm8 << 2);
                    mark_pool(literal_pool_bytes_thumb, target, 4);
                    continue;
                }
                // Thumb-2 LDR.W (literal) T2: 0xf85f or 0xf8df + 2nd hw
                if (off + 4 > seg.bytes.size()) continue;
                if ((hw1 & 0xff7f) == 0xf85f) {
                    uint16_t hw2 = uint16_t(base[off+2])
                                 | (uint16_t(base[off+3]) << 8);
                    uint32_t imm12 = hw2 & 0xfff;
                    bool U = (hw1 >> 7) & 1;
                    uint32_t target = U ? (pc_aligned + imm12)
                                        : (pc_aligned - imm12);
                    mark_pool(literal_pool_bytes_thumb, target, 4);
                    continue;
                }
                // Thumb-2 LDRD (literal): 0xe85f or 0xe9df ... (multiple
                // P/W variants). Match: 0xe85f, 0xe8df, 0xe95f, 0xe9df.
                if ((hw1 & 0xfe5f) == 0xe85f && (hw1 & 0x0040)) {
                    // bit 6 set means it's LDRD/LDR-DW not pure LDR.
                    // Loads 8 bytes (two regs).
                    uint16_t hw2 = uint16_t(base[off+2])
                                 | (uint16_t(base[off+3]) << 8);
                    uint32_t imm8 = hw2 & 0xff;
                    bool U = (hw1 >> 7) & 1;
                    uint32_t target = U ? (pc_aligned + (imm8 << 2))
                                        : (pc_aligned - (imm8 << 2));
                    mark_pool(literal_pool_bytes_thumb, target, 8);
                    continue;
                }
                // Thumb-2 VLDR (literal) single/double: hw1 = 0xed1f
                // or 0xed9f (U bit) plus sz bit in hw2.
                if ((hw1 & 0xff7f) == 0xed1f) {
                    uint16_t hw2 = uint16_t(base[off+2])
                                 | (uint16_t(base[off+3]) << 8);
                    if ((hw2 & 0x0e00) == 0x0a00) {  // bits 11-9 = 101
                        uint32_t imm8 = hw2 & 0xff;
                        bool sz = (hw2 >> 8) & 1;
                        uint32_t bytes = sz ? 8 : 4;
                        bool U = (hw1 >> 7) & 1;
                        uint32_t off_bytes = imm8 << 2;
                        uint32_t target = U ? (pc_aligned + off_bytes)
                                            : (pc_aligned - off_bytes);
                        mark_pool(literal_pool_bytes_thumb, target, bytes);
                    }
                }
            }
        }
        // Helper used by the filter chain. The literal pool addrs
        // are recorded per-mode; we check both (the bytes themselves
        // are mode-independent -- they're just data).
        auto is_in_literal_pool = [&](uint32_t vaddr) -> bool {
            if (literal_pool_bytes_arm.count(vaddr)) return true;
            if (literal_pool_bytes_thumb.count(vaddr)) return true;
            // Also check the 4-byte-aligned word containing vaddr,
            // since literal pool entries are 4-byte aligned and a
            // candidate landing mid-word is still mid-pool.
            uint32_t aligned = vaddr & ~3u;
            if (literal_pool_bytes_arm.count(aligned)) return true;
            if (literal_pool_bytes_thumb.count(aligned)) return true;
            return false;
        };
        // v1.0.552: ARM-set-only variant for AUTHORITATIVE ARM-mode seeds.
        // The thumb pool set comes from a BLIND 2-byte scan of every
        // executable byte; ARM code bytes freely alias the Thumb16
        // `ldr rN,[pc]` pattern (any halfword 0x48xx-0x4Fxx), so in an
        // ARM-mode lib the thumb set is riddled with false marks. That
        // false-marked FreeType's ft_alloc entry (0x36a14; the alias sits
        // at 0x36850 inside ARM code) and rejected its authoritative
        // STORE-installed PIC-fnptr seed -> memory->alloc panic-stub ->
        // FT error 64. An ARM-mode seed only needs to respect pool words
        // that ARM-mode literal loads actually dereference.
        auto is_in_arm_literal_pool = [&](uint32_t vaddr) -> bool {
            if (literal_pool_bytes_arm.count(vaddr)) return true;
            uint32_t aligned = vaddr & ~3u;
            return literal_pool_bytes_arm.count(aligned) != 0;
        };
        // v1.0.825: an UNMISTAKABLY-ARM function entry -- `push {..}` /
        // `stmfd sp!, {..}` (e92dXXXX) at a 4-aligned address. ARM
        // instructions are 4-aligned, and this encoding's top byte is the AL
        // condition code, so unlike looks_like_fn_prologue's Thumb clauses it
        // cannot be the trailing half of a Thumb pair. That makes it strong
        // enough to overrule a THUMB-ONLY literal-pool mark, which in an
        // ARM-mode library is manufactured by the blind 2-byte pool scan
        // sweeping ARM bytes (the same false-mark family as v1.0.552).
        auto looks_like_arm_push_prologue = [&](uint32_t vaddr) -> bool {
            if (vaddr & 3u) return false;
            auto code = img.code_at(vaddr, 4);
            return code.size() >= 4 && code[2] == 0x2d && code[3] == 0xe9;
        };
        // MACAPK_ARM32_THUMB_PARITY: from here on a Thumb pool mark ends the
        // halfword run (see thumb_hw_is_second_half).
        g_parity_pool_t = &literal_pool_bytes_thumb;
        struct ParityPoolReset {   // never leave the pointer to a dead set
            ~ParityPoolReset() { g_parity_pool_t = nullptr; }
        } parity_pool_reset;
        std::fprintf(stderr,
            "[translator] literal-pool scan: arm=%zu bytes, "
            "thumb=%zu bytes\n",
            literal_pool_bytes_arm.size(),
            literal_pool_bytes_thumb.size());
        // v1.0.61: ARM long-call veneer detection.
        //
        // The linker inserts these between a Thumb-mode caller and a
        // far ARM-mode (or different-section) callee whose distance
        // exceeds the Thumb BL/BLX imm range. Veneer pattern:
        //
        //   <vaddr+0>:  e59fc000   ldr   r12, [pc, #0]   ← reads literal
        //   <vaddr+4>:  e08cf00f   add   pc,  r12, pc    ← jumps via offset
        //   <vaddr+8>:  <imm32>    the literal: signed offset added to
        //                          PC value at the `add` insn (=vaddr+12).
        //                          target = vaddr + 12 + imm32 (mod 2^32).
        //   <vaddr+0xc>: optional `46c04778` (Thumb `bx pc + nop`) used
        //                when the caller of the NEXT veneer is in Thumb;
        //                NOT part of THIS veneer.
        //
        // We detect the 8-byte code prefix + 4-byte literal, resolve the
        // target at translation time, and emit a synth body that
        // tail-calls __macapk_target_<target>. Importantly: do NOT
        // continue lifting bytes after the literal -- those are the next
        // veneer's mode-switch prefix or another veneer/data.
        //
        // Without this handler: force-lifting (or naturally discovering)
        // a veneer caused the lifter to decode the literal bytes as
        // instructions, which produced `svc #<random imm>` garbage and
        // killed the runtime path. See v1.0.60 force-lift experiment
        // notes in Current_State.md.
        std::unordered_map<uint32_t, uint32_t> arm_veneer_targets;  // veneer_va -> target
        // v1.0.63: targets discovered via veneer scan that need to be
        // lifted as functions in their own right. Merged into
        // init_seed_to_lift once that vector is declared further down.
        std::vector<uint32_t> veneer_target_seeds;
        // v1.0.63: handle TWO ARM long-call veneer variants emitted by
        // the linker (both 4-byte aligned, both consist of an LDR-pc-rel
        // that loads an offset literal, followed by code that adds the
        // offset to PC and jumps):
        //
        // Variant A (v1.0.61, ARM-only target): 12 bytes total
        //     ldr   r12, [pc, #0]    ; e59fc000  — literal at offset 8
        //     add   pc,  r12, pc     ; e08cf00f  — direct mode-preserving jump
        //     .word imm              ; offset; pc at the `add` = veneer+12
        //
        // Variant B (BX form, ARM/Thumb-compatible): 16 bytes total
        //     ldr   r12, [pc, #4]    ; e59fc004  — literal at offset 12
        //     add   r12, pc, r12     ; e08fc00c  — compute address
        //     bx    r12              ; e12fff1c  — BX switches mode via lsb
        //     .word imm              ; offset; pc at the `add` = veneer+12
        //
        // The B form is what the linker emits when the target is in the
        // OTHER ISA mode (Thumb-from-ARM or ARM-from-Thumb): only `bx
        // <reg>` performs the runtime mode switch via the low bit. HCR
        // 1.43's std::operator+ at 0x67e50 uses B-form veneers in the
        // 0x2f12XX range to reach Thumb-mode std::string helpers like
        // 0x2dc15c (_M_replace_aux). Without B-form recognition, the
        // operator+ lifted body emits a tail-call to the veneer-as-data
        // stub (panic stub) and operator+ hot-loops indirect calls
        // forever.
        //
        // Both variants compute target = veneer_va + 12 + imm (PC at the
        // `add` instruction is veneer_va + 12 in both forms), so the
        // post-detect logic is identical. They differ only in size and
        // the literal-data range that needs to be marked.
        for (const auto& seg : img.loadable) {
            if (!seg.executable) continue;
            const uint8_t* base = seg.bytes.data();
            uint32_t seg_start = seg.vaddr;
            // Walk 4-byte-aligned positions. Need at least 12 bytes
            // (variant A) or 16 (variant B).
            for (size_t off = 0; off + 12 <= seg.bytes.size(); off += 4) {
                uint32_t w0 = uint32_t(base[off])
                            | (uint32_t(base[off+1]) << 8)
                            | (uint32_t(base[off+2]) << 16)
                            | (uint32_t(base[off+3]) << 24);
                uint32_t w1 = uint32_t(base[off+4])
                            | (uint32_t(base[off+5]) << 8)
                            | (uint32_t(base[off+6]) << 16)
                            | (uint32_t(base[off+7]) << 24);
                uint32_t veneer_va = seg_start + uint32_t(off);
                uint32_t imm = 0;
                bool is_variant_a =
                    (w0 == 0xe59fc000) && (w1 == 0xe08cf00f);
                bool is_variant_b = false;
                if (!is_variant_a) {
                    // Variant B needs 16 bytes
                    if (off + 16 > seg.bytes.size()) continue;
                    if (w0 != 0xe59fc004) continue;  // ldr r12, [pc, #4]
                    if (w1 != 0xe08fc00c) continue;  // add r12, pc, r12
                    uint32_t w2 = uint32_t(base[off+8])
                                | (uint32_t(base[off+9]) << 8)
                                | (uint32_t(base[off+10]) << 16)
                                | (uint32_t(base[off+11]) << 24);
                    if (w2 != 0xe12fff1c) continue;  // bx r12
                    is_variant_b = true;
                    imm = uint32_t(base[off+12])
                        | (uint32_t(base[off+13]) << 8)
                        | (uint32_t(base[off+14]) << 16)
                        | (uint32_t(base[off+15]) << 24);
                } else {
                    imm = uint32_t(base[off+8])
                        | (uint32_t(base[off+9]) << 8)
                        | (uint32_t(base[off+10]) << 16)
                        | (uint32_t(base[off+11]) << 24);
                }
                // Both variants: PC at the `add` (at veneer_va+4) is
                // veneer_va + 4 + 8 = veneer_va + 12.
                uint32_t target = veneer_va + 12 + imm;
                arm_veneer_targets[veneer_va] = target;
                // Mark literal bytes as data so they don't get mis-
                // lifted. Variant A literal at +8, variant B at +12.
                uint32_t lit_off = is_variant_b ? 12 : 8;
                literal_pool_bytes_arm.insert(veneer_va + lit_off + 0);
                literal_pool_bytes_arm.insert(veneer_va + lit_off + 1);
                literal_pool_bytes_arm.insert(veneer_va + lit_off + 2);
                literal_pool_bytes_arm.insert(veneer_va + lit_off + 3);
                (void)is_variant_a; (void)is_variant_b;
                // v1.0.63: Thumb-to-ARM mode-switch prefix detection.
                // When the linker places a veneer to be reachable from
                // Thumb code, it emits a 4-byte prefix in front of the
                // ARM veneer body. The actual byte order (verified
                // via xxd + llvm-objdump --triple=thumbv7) is:
                //
                //     bytes:    0x78 0x47 0xc0 0x46
                //     thumb16:  bx pc; mov r8, r8 (nop padding)
                //     LE u32:   0x46c04778
                //
                // ARM ARM A8.6.25: BX in Thumb mode with the
                // destination's low bit clear performs a Thumb-to-
                // ARM switch. PC at `bx pc` is the instruction
                // address + 4 (Thumb pipeline), which is the next
                // 4-byte-aligned address -- the ARM veneer body at
                // veneer_va. The trailing 2-byte `nop` is alignment
                // padding; it's never executed because `bx pc`
                // unconditionally leaves Thumb mode.
                //
                // Without this prefix detection, the lifter has no
                // synth at veneer_va-4 (Thumb mode), so callers' BLs
                // to that address resolve to a panic stub at runtime.
                // The shim's "unresolved external call to ARM address
                // 0x<veneer_va-4>" log lines are exactly this case.
                // (Confirmed in HCR 1.43: 0x2f1d3c, 0x2f1d4c, ... -- the
                // Thumb entry of variant-A veneers at 0x2f1d40, 0x2f1d50.)
                //
                // We register an additional veneer entry at vaddr-4
                // in Thumb mode pointing at the same target. Same
                // tail-call body, just an extra entry point.
                if (off >= 4) {
                    uint32_t prefix_w = uint32_t(base[off-4])
                                      | (uint32_t(base[off-3]) << 8)
                                      | (uint32_t(base[off-2]) << 16)
                                      | (uint32_t(base[off-1]) << 24);
                    if (prefix_w == 0x46c04778) {  // bx pc; nop (Thumb)
                        uint32_t thumb_entry = veneer_va - 4;
                        arm_veneer_targets[thumb_entry] = target;
                    }
                }
            }
        }
        // MACAPK_ARM32_LLD_THUNKS (default ON; =0 scans exactly as before).
        // The veneers above are GNU ld's. lld (the NDK linker since r22) emits
        // its OWN long-branch / interworking thunks, and because lld picks a
        // thunk by the BRANCH that needs it -- "Branch instructions cannot
        // change state, can only select Thunk that starts in the same state as
        // the caller" (lld/ELF/Thunks.cpp addThunkArm) -- every Thumb `b.w`
        // tail call to an ARM-mode PLT entry (`return malloc(n);`) goes through
        // one. The two position-independent shapes, byte for byte as
        // lld/ELF/Thunks.cpp writes them:
        //   ThumbV7PILongThunk (Thumb, 12 bytes, halfword aligned):
        //     movw ip, #lo ; movt ip, #hi ; add ip, pc (0x44fc) ; bx ip (0x4760)
        //     target = P + 12 + (hi:lo)       ("offset = s - p - 12")
        //   ARMV7PILongThunk (ARM, 16 bytes):
        //     movw ip, #lo ; movt ip, #hi ; add ip, ip, pc (0xe08cc00f) ;
        //     bx ip (0xe12fff1c)
        //     target = P + 16 + (hi:lo)       ("offset = s - p - 16")
        // movw/movt immediates per ARM ARM DDI 0406C.b A8.8.102/A8.8.106:
        // T3/T1 imm16 = imm4:i:imm3:imm8, A2/A1 imm16 = imm4:imm12; Rd = r12.
        //
        // Nothing named the Thumb thunk's mode: it has no symbol after
        // stripping, no exidx entry, no Thumb-16 prologue, and B (unlike BL,
        // v1.0.814) leaves no call-site mode note -- so sniff_thumb_at said
        // ARM and the thunk was translated as ARM garbage that returned r0
        // unchanged. Measured: OpenTTD 14.1 v7a liblzma's lzma_alloc
        // (`b.w 0x1cd08`, malloc's thunk) returned its SIZE as the pointer.
        // Before this scan 100% of the translated Thumb thunks were ARM-moded
        // in every library measured (liblzma 20/20, libsdl-1.2 53/53, Mindustry
        // libarc 13/13, GD Lite libfmod 647/647 + 38 never translated).
        // Corpus: 9,803 Thumb + 9,085 ARM PI thunks in 149 of 246 ARM32 libs.
        //
        // Each becomes a Veneer (the same forwarder the GNU ld shapes get, in
        // its OWN mode). A target that is this image's own PLT stub for a
        // symbol it defines (img.plt_local_bindings, the slots elf_reader
        // binds locally, as Bionic does) is forwarded to that definition: the
        // dispatcher resolves an IMPORT's PLT address but not a local stub.
        // docs/arm32-exidx-confirmed-seeds_2026-09-28.md.
        static const bool lld_thunks_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_LLD_THUNKS");
            return !(e && *e == '0');
        }();
        std::unordered_set<uint32_t> veneer_is_thumb;
        if (lld_thunks_on) {
            std::unordered_map<uint32_t, uint32_t> plt_local;
            for (const auto& [stub, local] : img.plt_local_bindings)
                plt_local[stub & ~1u] = local & ~1u;
            auto rd16 = [](const uint8_t* q) -> uint32_t {
                return uint32_t(q[0]) | (uint32_t(q[1]) << 8);
            };
            auto rd32 = [](const uint8_t* q) -> uint32_t {
                return uint32_t(q[0]) | (uint32_t(q[1]) << 8)
                     | (uint32_t(q[2]) << 16) | (uint32_t(q[3]) << 24);
            };
            // Thumb MOVW (T3) / MOVT (T1) with Rd = r12 -> imm16, else -1.
            auto t_mov16 = [&](const uint8_t* q, uint32_t op) -> int64_t {
                const uint32_t h0 = rd16(q), h1 = rd16(q + 2);
                if ((h0 & 0xfbf0u) != op || (h1 & 0x8f00u) != 0x0c00u) return -1;
                return int64_t(((h0 & 0xfu) << 12) | (((h0 >> 10) & 1u) << 11)
                               | (((h1 >> 12) & 7u) << 8) | (h1 & 0xffu));
            };
            // ARM MOVW (A2) / MOVT (A1), cond AL, Rd = r12 -> imm16, else -1.
            auto a_mov16 = [&](const uint8_t* q, uint32_t op) -> int64_t {
                const uint32_t w = rd32(q);
                if ((w & 0xfff0f000u) != op) return -1;
                return int64_t(((w >> 16) & 0xfu) << 12 | (w & 0xfffu));
            };
            // Code SECTIONS only (SHF_ALLOC|SHF_EXECINSTR, which includes
            // .plt), as the other scans: the executable PT_LOAD also holds
            // .rodata, whose bytes alias code. A stripped section table
            // keeps the whole executable segment.
            const bool have_code_sections_lt = [&]() {
                for (const auto& sh : img.shdrs)
                    if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u)
                        && sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto in_code_lt = [&](uint32_t va) -> bool {
                if (!have_code_sections_lt) return true;
                for (const auto& sh : img.shdrs) {
                    if (!(sh.sh_flags & 0x4u) || !(sh.sh_flags & 0x2u)) continue;
                    if (va >= sh.sh_addr && va < sh.sh_addr + sh.sh_size)
                        return true;
                }
                return false;
            };
            size_t n_thumb = 0, n_arm = 0, n_local = 0;
            auto add_thunk = [&](uint32_t va, uint32_t target, bool thumb) {
                if (!in_code_lt(va) || !in_code_lt(target & ~1u)) return;
                if (arm_veneer_targets.count(va)) return;
                auto pl = plt_local.find(target & ~1u);
                if (pl != plt_local.end()) {
                    auto kt = known_thumb.find(pl->second);
                    target = pl->second
                           | ((kt != known_thumb.end() && kt->second) ? 1u : 0u);
                    ++n_local;
                }
                arm_veneer_targets[va] = target;
                if (thumb) { veneer_is_thumb.insert(va); ++n_thumb; }
                else ++n_arm;
            };
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                const uint8_t* base = seg.bytes.data();
                const size_t n = seg.bytes.size();
                for (size_t off = 0; off + 12 <= n; off += 2) {
                    const uint8_t* q = base + off;
                    const uint32_t va = seg.vaddr + uint32_t(off);
                    const int64_t lo = t_mov16(q, 0xf240u), hi = lo < 0 ? -1 : t_mov16(q + 4, 0xf2c0u);
                    if (lo >= 0 && hi >= 0 && rd16(q + 8) == 0x44fcu
                            && rd16(q + 10) == 0x4760u)
                        add_thunk(va, va + 12u + (uint32_t(hi) << 16 | uint32_t(lo)), true);
                    if ((off & 3u) == 0 && off + 16 <= n) {
                        const int64_t alo = a_mov16(q, 0xe300c000u);
                        const int64_t ahi = alo < 0 ? -1 : a_mov16(q + 4, 0xe340c000u);
                        if (alo >= 0 && ahi >= 0 && rd32(q + 8) == 0xe08cc00fu
                                && rd32(q + 12) == 0xe12fff1cu)
                            add_thunk(va, va + 16u + (uint32_t(ahi) << 16 | uint32_t(alo)), false);
                    }
                }
            }
            std::fprintf(stderr,
                "[translator] lld long-thunk scan: %zu Thumb + %zu ARM thunk(s), "
                "%zu forwarded past a local PLT stub (MACAPK_ARM32_LLD_THUNKS)\n",
                n_thumb, n_arm, n_local);
        }
        std::fprintf(stderr,
            "[translator] arm-veneer scan: %zu veneers detected\n",
            arm_veneer_targets.size());
        // Proactively emit veneer synths so they ARE lifted even if
        // nothing in the discovery pipeline mentions them (the original
        // Thumb caller's BLX/BL-imm to the veneer may not have been
        // discovered yet -- but the veneer itself IS code that real
        // call sites jump to, so we must lift it ahead of discovery).
        // Add to known_starts so the BL/synth rounds don't re-lift it.
        //
        // We DO NOT emit `declare i32 @__macapk_target_<target>`
        // here -- main.cpp's downstream alias generator emits the
        // declaration/alias for every cross-function call site. If
        // we emitted a declare AND the alias was also emitted, llvm-
        // as fails with "invalid redefinition of function". The
        // veneer body's tail-call just references the alias by name;
        // it resolves at link time the same way any other intra-
        // module call does.
        for (const auto& [vva, target] : arm_veneer_targets) {
            if (known_starts.count(vva)) continue;
            char vbuf[64];
            std::snprintf(vbuf, sizeof(vbuf), "__macapk_synth_%08x", vva);
            std::string vname = vbuf;
            // v1.0.63: dispatch via __macapk_indirect_call instead
            // of `tail call @__macapk_target_<target>`.
            //
            // The alias-based form is faster (direct branch at link
            // time) but requires the target to have been lifted -- if
            // the veneer's target is not in the lift set, llvm-as
            // fails with "use of undefined value @__macapk_target_
            // <target>". Variant-B veneers (BX form, 16 bytes) often
            // point into intra-libgame Thumb code we didn't otherwise
            // discover (e.g. HCR 1.43's 0x2dc15c, a libstdc++ string
            // _M_replace helper inside libgame.so).
            //
            // Going through __macapk_indirect_call:
            //   - works for ANY target (lifted, PLT, raw vaddr), so
            //     llvm-as never fails
            //   - the dispatcher's alias-table lookup is one extra
            //     function call + hashmap probe (~30-50 ns); veneers
            //     are on long-distance call paths, not in hot inner
            //     loops, so the overhead is invisible at runtime
            //   - preserves the Thumb lsb on `target` so the
            //     dispatcher can dispatch to a Thumb-mode body
            //     correctly (the lsb is also how the original ARM
            //     `bx r12` distinguishes mode)
            module_ir << "; --- arm long-call veneer @ 0x" << std::hex
                      << vva << " -> 0x" << target << std::dec << " ---\n"
                      << "define dso_local i32 @\"" << vname
                      << "\"(i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                         "i32 %arg_sp) local_unnamed_addr {\n"
                      << "entry:\n"
                      << "  %r = tail call i32 @__macapk_indirect_call("
                      << "i32 " << (target + load_bias)  // per-image bias (LUT key the dispatcher resolves)
                      << ", i32 %arg0, i32 %arg1, i32 %arg2, i32 %arg3, "
                         "i32 %arg_sp)\n"
                      << "  ret i32 %r\n"
                      << "}\n\n";
            // `target` keeps the Thumb lsb on purpose: that is how the
            // original `bx r12` distinguishes the callee's ISA, and the
            // indirect dispatcher's LUT is keyed the same way. A consumer
            // of DiscoveredFunction::veneer_target must add the image's
            // load_bias itself, exactly as the IR above does.
            note_veneer(vva, target);
            lifter.register_lifted_function(vva, vname);
            known_starts.insert(vva);
            // GNU ld veneers are ARM-mode; an lld ThumbV7PILongThunk is Thumb
            // (MACAPK_ARM32_LLD_THUNKS -- empty set when the gate is off).
            known_thumb[vva] = veneer_is_thumb.count(vva) != 0;
            // v1.0.63: The TARGET also needs to be lifted. If the
            // ONLY way callers reach `target` is through the veneer
            // (a long-call BL imm to the veneer's vaddr), then no
            // direct BL imm to `target` ever appears in the
            // discovery pipeline, so `target` never enters the lift
            // worklist and never gets an alias emitted. The veneer
            // synth's `tail call @__macapk_target_<target>` then
            // resolves to nothing, falls through to dynamic_lookup,
            // and at runtime hits the indirect-call dispatcher which
            // returns the panic-stub address (= 0). The caller
            // interprets that 0 result as "operation failed" and
            // retries -> hot-loop of `[macapk-shim] indirect call
            // to in-image vaddr 0x<target>` log spam.
            //
            // Concrete instance (HCR 1.43): veneer @ 0x2f126c routes
            // to 0x2dc15c (a libstdc++ string `_M_replace` helper
            // inside libgame.so, NOT a PLT stub). std::operator+
            // calls `bl 0x2f126c` because its caller is too far
            // from 0x2dc15c for a direct BL imm. Without the
            // target-seed below, every operator+ invocation hot-
            // loops indirect calls to 0x2dc15c forever.
            //
            // We stash the target (Thumb bit preserved) in a side
            // vector and merge it into init_seed_to_lift further
            // down (where that vector is declared). The discovery
            // loop handles both modes and applies inside-known-
            // body rejection so we don't lift mid-symbol false
            // targets.
            uint32_t target_clean = target & ~1u;
            bool target_is_thumb = (target & 1u) != 0;
            if (!known_starts.count(target_clean)
                && !plt_addrs.count(target_clean)) {
                veneer_target_seeds.push_back(target_clean);
                if (target_is_thumb) {
                    known_thumb[target_clean] = true;
                }
            }
        }
        // emit_veneer_synth: when the discovery pipeline encounters one
        // of these veneer addresses, emit a tail-call body that delegates
        // to the resolved target. Returns true if vaddr is a recognized
        // veneer and the body was emitted.
        // (DEAD now -- the proactive emit loop above adds all veneers
        // to known_starts before any dispatch-site call. Kept for
        // documentation of the dispatch-site contract; the loop in
        // BL/synth rounds checks emit_veneer_synth and gets `false`
        // because vaddr is already in known_starts.)
        auto emit_veneer_synth = [&](uint32_t vaddr) -> bool {
            (void)vaddr;
            return false;  // Proactive emission handles all veneers.
        };
        // Byte-pattern Thumb-mode sniffer for unsymbolled targets.
        // ARM and Thumb function prologues have very different
        // first-instruction patterns:
        //   ARM 32-bit `push {...}`        encodes as e92dXXXX (LE bytes
        //                                  XX XX 2d e9). High nibble e9.
        //   Thumb16 `push {r4..r7,lr}`     encodes as b5XX (LE bytes XX b5).
        //   Thumb16 `push {rN..rM,lr}`     b500..b5ff range.
        //   Thumb-2 `push.w {...}`         e92d XXXX (same as ARM but
        //                                  in Thumb-2 32-bit form).
        //   Thumb16 `sub sp, sp, #imm`     b080..b0ff.
        //   Thumb16 `add r7, sp, #imm`     af00..afff.
        //
        // A function we can't classify via symtab is highly likely to
        // be Thumb if its first 2 LE bytes are b5XX (the most common
        // libxml2/libstdc++ leaf-function prologue) or b0XX. Otherwise
        // we fall back to ARM mode. False positives here are tolerable
        // -- we only consult this when there's no better hint.
        auto sniff_thumb_at = [&](uint32_t addr) -> bool {
            // v1.0.814: ASK THE CALL SITE FIRST. Everything below is a byte
            // heuristic over four hardcoded Thumb-16 prologue patterns, and it
            // answers "ARM" for any shape it does not recognise -- including
            // Thumb-2's wide `push.w`, which is how an unsymboled Thumb helper
            // reached only by BL gets decoded as ARM. The bytes then produce
            // plausible nonsense rather than an error: on Subway Surfers'
            // libmain.so the helper at 0x1618 mis-decoded into two
            // "instructions" that appeared to write PC, and the translator's
            // otherwise-correct ALU/LDM-writes-PC safety net faithfully lowered
            // both into indirect dispatches through garbage registers, so the
            // routine that reads /sys/devices/system/cpu/{present,possible}
            // never did its real work on any invocation.
            //
            // A direct call knows the answer exactly: `BL #imm` does not
            // interwork, so the callee is in the caller's mode, and `BLX #imm`
            // is precisely the interworking form, so it is the opposite mode.
            // The lifter records that at every call site. It returns nullopt
            // both when nothing is known and when two call sites DISAGREE --
            // in which case we must not pick a side, and falling through to the
            // heuristic is no worse than the pre-v1.0.814 behaviour.
            if (auto known = lifter.call_target_thumb(addr)) return *known;
            const std::span<const uint8_t> code = img.code_at(addr, 4);
            if (code.size() < 2) return false;
            [[maybe_unused]] uint8_t b0 = code[0];
            uint8_t b1 = code[1];
            // Thumb16 push {rN..lr}: bytes b0=imm, b1=0xb5
            if (b1 == 0xb5) return true;
            // Thumb16 sub sp / add sp / add r7,sp variants
            if (b1 == 0xb0 || b1 == 0xaf) return true;
            // Thumb16 push {r0..r7} (no lr): b400..b4ff
            if (b1 == 0xb4) return true;
            return false;
        };
        // v1.0.56: detect a candidate address that's actually mid-ARM-
        // instruction (vaddr is 2-byte-aligned, the prior 2 bytes are
        // the first half of a 4-byte ARM-aligned instruction). The
        // bogus-Thumb-discovery cascade (`synth_0020338a` in HCR 1.43,
        // `synth_0020944a` in the v1.0.18 era) starts when one bogus
        // Thumb decode of mid-instruction bytes produces a branch whose
        // target is ALSO mid-instruction, recursively. Filtering these
        // at discovery time prevents the cascade.
        //
        // Heuristic: the byte at (vaddr-2)+3 is the high byte of a 4-
        // byte ARM instruction. In compiler-emitted ARM code, the high
        // nibble is the condition code -- 0..0xe is conditional or AL,
        // 0xf is special (BLX-immediate etc). All real ARM instructions
        // have this byte in the range 0x00..0xff (any value), but for
        // SHIPPING binaries virtually every instruction is AL (0xeX).
        // We require the prior-bytes high nibble to be 0xe (AL) or 0xf
        // (unconditional/BLX) AND the candidate's own bytes NOT to
        // match a Thumb prologue pattern. This combination is highly
        // diagnostic of "you're staring at the middle of an ARM instr".
        // (v1.0.56 had a here `is_mid_arm_instruction` lambda; it was
        // superseded in v1.0.57 by the more general
        // `is_mid_function_candidate` / `is_preceded_by_terminator_*`
        // helpers below, which use the same "is the prior 4 bytes a
        // real AL/UNCOND ARM instruction" idea but evaluate it
        // alongside an actual terminator-check. The is_mid_arm
        // heuristic on its own was too aggressive -- legit Thumb
        // entries at 2-byte-aligned addresses after AL-conditioned
        // ARM code (common in mixed-mode binaries) got false-flagged.)
        // v1.0.57: "is this preceded by a function terminator?" -- the
        // strongest available cheap signal that an address is a real
        // function boundary. Defined here (BEFORE the BL/synth budget-
        // tighten loops) so those loops can skip a candidate that lives
        // mid-body. (A duplicate definition follows in the prologue-scan
        // section -- mode-dependent helpers there use it.) The body is
        // exactly the same as the late copy; keep them in sync.
        auto is_preceded_by_terminator_early =
                [&](uint32_t vaddr, bool is_thumb) -> bool {
            if (is_thumb) {
                if (vaddr < 2) return true;  // section start: accept
                auto pre = img.code_at(vaddr - 2, 2);
                if (pre.size() < 2) return true;
                uint32_t hw = uint32_t(pre[0]) | (uint32_t(pre[1]) << 8);
                // MACAPK_ARM32_THUMB_PARITY: a 16-bit reading of vaddr-2 is
                // only an instruction if vaddr-2 is not the tail of a 32-bit
                // Thumb-2 instruction (see thumb_hw_is_second_half).
                const bool term16 = (hw & 0xff00) == 0xbd00   // pop {...,pc}
                                 || hw == 0x4770                 // bx lr
                                 || (hw & 0xf800) == 0xe000      // b imm11
                                 || (hw & 0xff80) == 0x4700;     // bx Rm
                if (term16) {
                    if (!thumb_hw_is_second_half(img, vaddr - 2))
                        return true;
                    g_thumb_parity_refused.insert(vaddr - 2);
                }
                if (vaddr < 4) return true;
                auto pre4 = img.code_at(vaddr - 4, 4);
                if (pre4.size() < 4) return false;
                // ...and a 32-bit reading of vaddr-4 only if vaddr-4 is not
                // itself a tail.
                if (thumb_hw_is_second_half(img, vaddr - 4))
                    return false;
                uint32_t lo = uint32_t(pre4[0]) | (uint32_t(pre4[1]) << 8);
                uint32_t hi = uint32_t(pre4[2]) | (uint32_t(pre4[3]) << 8);
                if (lo == 0xe8bd && (hi & 0x8000)) return true;
                // Thumb-2 B.W T4 encoding: hw1 = 11110_S_imm10
                // (bits 15-11 = 0x1e = 11110), hw2 has bit 12 = 1
                // distinguishing from T3 conditional B (bit 12 = 0).
                // v1.0.59: previous mask was 0xfff0 which required
                // imm10 = 0 — too restrictive. HCR 1.43's libxml2
                // region has B.W at 0x2cf4f4 with non-zero imm10
                // (hw1=0xfff7); the wrong mask rejected the
                // Thumb function entry at 0x2cf4f8 that the
                // 638-count indirect calls go to.
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd000) == 0x9000) return true;     // b.w T4
                // Also Thumb-2 BL imm24 (T1): same hw1 prefix but
                // hw2 bit 12 = 1. We do NOT treat BL as terminator
                // (it's a call, not a tail-call) — but a BLX-imm
                // that switches mode is effectively a tail call
                // for the SAME-MODE function that follows. Handled
                // below as part of BLX-imm.
                // Thumb-2 BLX-imm T2: hw1 = 11110_S_imm10, hw2 =
                // 11_J1_0_J2_imm10_H (bit 12 = 0, bit 11 = 1, bit
                // 0 = H = 0 always). Distinguished from B.W by
                // hw2 bit 12 = 0 (vs 1) and bit 0 = 0.
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd001) == 0xc000) return true;     // blx imm T2
                // v1.0.62: Thumb-2 BL imm to pure_throw_helper → effective
                // terminator. BL T1: hw1 = 11110_S_imm10, hw2 = 11_J1_1_J2_imm11
                // (bits 14-13=11, bit 12=1). Target uses J-bit flipping.
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd000) == 0xd000) {
                    uint32_t s_bit = (lo >> 10) & 1;
                    uint32_t imm10 = lo & 0x3ff;
                    uint32_t j1 = (hi >> 13) & 1;
                    uint32_t j2 = (hi >> 11) & 1;
                    uint32_t imm11 = hi & 0x7ff;
                    uint32_t i1 = (~(j1 ^ s_bit)) & 1;
                    uint32_t i2 = (~(j2 ^ s_bit)) & 1;
                    uint32_t offset_u = (i1 << 23) | (i2 << 22)
                                      | (imm10 << 12) | (imm11 << 1);
                    int32_t soffset = (int32_t)offset_u;
                    if (s_bit) soffset |= 0xff000000;
                    // Thumb PC at the BL = (vaddr - 4) + 4 = vaddr.
                    uint32_t target = vaddr + uint32_t(soffset);
                    if (pure_throw_helpers.count(target)) {
                        // MACAPK_ARM32_NORETURN_REACH: a BL that ends an IT
                        // block runs only when the condition holds, so the
                        // code after it is the same function.
                        if (arm32_noreturn_reach_on()
                            && thumb_bl_is_it_predicated(img, vaddr - 4))
                            cond_helper_call_not_terminator(
                                vaddr - 4, target, "IT-predicated (Thumb)");
                        else
                            return true;
                    }
                }
                return false;
            } else {
                if (vaddr < 4 || (vaddr & 3) != 0) return false;
                auto pre = img.code_at(vaddr - 4, 4);
                if (pre.size() < 4) return false;
                uint32_t insn = uint32_t(pre[0])
                              | (uint32_t(pre[1]) << 8)
                              | (uint32_t(pre[2]) << 16)
                              | (uint32_t(pre[3]) << 24);
                // v1.0.553: UNCONDITIONAL only (cond=AL) -- a conditional
                // return/branch falls through, so the following push is
                // the same function's body (bxeq lr + push = the common
                // early-out prelude; FT_GlyphLoader_Add was truncated to
                // 2 insns exactly this way).
                if ((insn & 0xffff8000) == 0xe8bd8000) return true;
                if ((insn & 0xfffffff0) == 0xe12fff10) return true;
                if ((insn & 0xff000000) == 0xea000000) return true;
                if (insn == 0xe1a0f00e) return true;
                if (insn == 0xe1a0f00d) return true;
                // v1.0.62: ARM `bl <pure_throw_helper>` is a NO-RETURN
                // call that effectively terminates the function (the
                // following bytes are dead code / literal pool until
                // the next real function entry).
                // ARM BL encoding: cond[31:28] 1011 imm24[23:0].
                // Target = (insn_addr + 8) + sign_extend(imm24 << 2).
                if ((insn & 0x0f000000) == 0x0b000000) {
                    int32_t imm24 = int32_t(insn & 0x00ffffff);
                    // Sign-extend from 24 bits.
                    if (imm24 & 0x00800000) imm24 |= 0xff000000;
                    uint32_t target = (vaddr - 4 + 8) + uint32_t(imm24 << 2);
                    if (pure_throw_helpers.count(target)) {
                        // MACAPK_ARM32_NORETURN_REACH: cond=AL only. `blne
                        // helper` falls through when the condition fails
                        // (v1.0.553's class); cond=0xF is BLX, not BL.
                        const uint32_t cond = insn >> 28;
                        if (!arm32_noreturn_reach_on() || cond == 0xeu)
                            return true;
                        if (cond != 0xfu)
                            cond_helper_call_not_terminator(
                                vaddr - 4, target, "conditional (ARM cond != AL)");
                    }
                }
                return false;
            }
        };
        // v1.0.57: "this candidate is mid-function" = NOT a known BL
        // target AND NOT preceded by a function terminator in ANY
        // plausible mode. Used by the budget-tighten in BL/synth round 1
        // and round 2 to skip mid-body candidates (e.g. HCR 1.43
        // 0x202f20, mid CCNode-ctor) so they don't truncate sibling
        // functions. We DO NOT remove them from to_lift -- their own
        // lift simply fails and they're skipped.
        // v1.0.58: "is this address preceded by a function terminator
        // in a sense that the SURROUNDING mode would observe?"
        //
        // Thumb-halfword coincidence: ARM-mode instruction bytes
        // (e.g. high half of `ldr r1, [pc, #0xd0]` = bytes 0x9f
        // 0xe5) coincidentally match the Thumb-16 `b imm11` encoding
        // 0xe000-0xe7ff. A naive Thumb-mode terminator check at
        // vaddr-2 false-positives here.
        //
        // Fix: when the candidate is 4-byte-aligned AND the prior 4
        // bytes form a valid AL or UNCOND ARM instruction (high
        // nibble 0xe / 0xf), trust ONLY the ARM-mode terminator
        // check. The ARM byte stream is real; the Thumb match is
        // coincidence. Otherwise accept either mode's terminator.
        //
        // NB: we DO NOT walk back through literal pool data here.
        // Trying to do so (v1.0.57g/h) regressed scene creation in
        // HCR -- some legitimate function entries directly follow a
        // bl (BL is not a terminator), and the walk-back fell off
        // into the BL's caller's body and accepted bogus targets as
        // function boundaries. Strict immediate-predecessor check
        // is the v1.0.57f compromise: it correctly accepts function
        // entries that follow a real terminator, and correctly
        // rejects mid-function candidates. The cost is that real
        // entries in literal-pool gaps (e.g. 0x20a070 in HCR) get
        // rejected too -- those become panic stubs, which the
        // engine handles gracefully (the function returns 0).
        // v1.0.59: with literal-pool detection now active as a
        // SAFETY NET, we can safely accept EITHER mode's terminator
        // since bogus mid-function false-positives are caught by
        // is_in_literal_pool. Additionally, walk back through
        // padding NOPs — compilers emit Thumb-16 `nop` (0xbf00) or
        // `mov r8, r8` (0x46c0) between a function terminator and
        // the next function to 4-byte-align the entry point. HCR's
        // libxml2 region has many functions preceded by a single
        // 2-byte NOP after a b.w terminator (e.g. 0x2db900 follows
        // `b.w` at 0x2db8fa + nop at 0x2db8fe → 637 indirect-call
        // panic-stub hits before this fix).
        //
        // Other ARM-mode NOPs: `mov r0, r0` (e1a00000), `nop` /
        // `andeq r0, r0, r0` (00000000) — though the latter is also
        // a common literal pool zero. We're conservative: accept
        // only the canonical Thumb-16 / ARM NOP encodings.
        // MACAPK_ARM32_TERM_THUMB_SEED (v1.0.1053 as a default-OFF diagnostic;
        // default ON since 2026-09-28 with the acceptance rule narrowed; =0 is
        // the pre-change filter byte for byte: the arm_prior veto below
        // applies to every candidate).
        //
        // The veto discards a Thumb terminator reading whenever the 4-aligned
        // word before the candidate has an ARM AL/UNCOND top nibble. For a
        // Thumb-TAGGED function-pointer seed that is wrong when the code
        // really is Thumb (a Thumb-16 `b` in the high half of that word is the
        // usual victim). The old =1 arm lifted the veto for EVERY Thumb-tagged
        // seed, and that is measurably wrong too: on GD Lite 2.2.11 v7a
        // libcocos2dcpp.so 24 of the seeds it newly admitted sit in ARM-mode
        // code (0x636f38..0x7780xx, `mov r0,#0xd; mov r1,#0x6c ...` -- a
        // Thumb-tagged value from the heuristic PIC scans, not a Thumb
        // function), and their cascade added ~1,000 phantom functions.
        //
        // The bit[0] tag says what the POINTER claims; it does not prove the
        // bytes agree. So the veto is lifted only when the candidate's own
        // bytes also open a Thumb function: PUSH with LR in the register list,
        // ARM ARM DDI 0406C.b A8.8.133 PUSH, encodings T1 (1011 010 M
        // register_list, M = LR -> 0xb5xx), T2 (STMDB SP! 1110 1001 0010 1101
        // | 0 M 0 register_list -> 0xe92d with bit 14 of the second halfword)
        // and T3 (single register, 1111 1000 0100 1101 | Rt 1101 0000 0100
        // with Rt = LR -> f84d ed04). That is the Thumb twin of the
        // MACAPK_ARM32_FNPTR_POOL_PRED narrowing (an ARM `push {..,lr}` entry).
        // Read as the ARM stream the veto fears, the candidate would have to
        // be an ARM word whose LOW halfword happens to be one of those, right
        // after a word whose high half happens to be a Thumb terminator.
        // Measured: docs/arm32-thumb-parity-and-thumb-seed_2026-09-28.md.
        static const bool term_thumb_seed_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_TERM_THUMB_SEED");
            return !(e && *e == '0');
        }();
        auto opens_with_thumb_push_lr = [&](uint32_t va) -> bool {
            auto c = img.code_at(va, 4);
            if (c.size() < 2) return false;
            const uint16_t h0 = uint16_t(c[0]) | uint16_t(c[1] << 8);
            if ((h0 & 0xff00u) == 0xb500u) return true;               // T1
            if (c.size() < 4) return false;
            const uint16_t h1 = uint16_t(c[2]) | uint16_t(c[3] << 8);
            if (h0 == 0xe92du && (h1 & 0xe000u) == 0x4000u) return true; // T2
            return h0 == 0xf84du && h1 == 0xed04u;                      // T3
        };
        // v1.0.1655: MACAPK_ARM32_TERM_NOPW (default ON; =0 restores the
        // pre-change walk-back exactly). For a Thumb-TAGGED candidate only, the
        // walk-back below also steps over the 32-bit Thumb-2 NOP,
        // `nop.w` = hw1 0xf3af, hw2 0x8000 (ARM ARM DDI 0406C.b A8.8.119 NOP,
        // Encoding T2: 1111 0011 1010 1111 1000 0000 0000 0000), exactly as it
        // already steps over the Thumb-16 NOPs 0xbf00 / 0x46c0. The assembler
        // pads a Thumb-2 function entry to its alignment with nop.w as readily
        // as with the 16-bit nop, so the padding before a real entry is
        // routinely `<terminator> ; nop ; <literal pool> ; nop.w`.
        //
        // Measured victim (GD Lite 2.2.11 libcocos2dcpp.so, md5 0822dd26...):
        // libpng's png_read_filter_row_sub at 0x50db40, which the inlined
        // png_init_filter_functions installs into png_ptr->read_filter[0] via
        //   0x510db6: f8df e034   ldr.w lr, [pc, #0x34]
        //   0x510dc0: 44fe        add   lr, pc          -> 0x50db41
        // is preceded by
        //   0x50db26: b.w ...     (terminator)   0x50db2a: nop
        //   0x50db2c..0x50db3b    literal pool (4 words the png_error calls load)
        //   0x50db3c: nop.w       <- the walk-back used to stop HERE
        // so the seed was rejected as "not preceded by terminator", the
        // function was never lifted, and every PNG row with filter type 1
        // (Sub) was left un-reconstructed by the panic stub's return 0.
        //
        // Scoped to candidate_is_thumb because nop.w is a Thumb-only encoding
        // (bit[0] of the seed SAYS Thumb, A2.3.2); an ARM-mode candidate keeps
        // the historical walk-back byte for byte, so every caller except the
        // data_fnptr seed site is untouched. Read as an ARM word the bytes are
        // 0x8000f3af (cond HI, a data-processing op writing PC), never padding.
        static const bool term_nopw_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_TERM_NOPW");
            return !(e && *e == '0');
        }();
        // v1.0.1659: two DATA_FNPTR-SEED-SITE-ONLY walk-back rules. Both are
        // read ONLY when the caller passes fnptr_seed=true (the two call sites
        // in the data_fnptr seed admission loop); every other caller keeps the
        // historical walk-back byte for byte, like MACAPK_ARM32_TERM_NOPW above.
        //
        // MACAPK_ARM32_TERM_NORETURN_PLT (default ON; =0 restores the old
        // walk-back exactly). An UNCONDITIONAL ARM `bl <plt stub>` whose import
        // is a no-return function ends the caller's body, exactly as
        // `bl <in-image throw helper>` already does (v1.0.62, pure_throw_helpers
        // above): the compiler emits nothing reachable after it, so the bytes
        // that follow are that function's literal pool and then the NEXT
        // function. The import list is the no-return set the platform itself
        // declares -- fetched 2026-09-30:
        //   bionic libc/include/stdlib.h:45-52  `__noreturn void abort(void)`,
        //       `__noreturn void exit(int)`, `__noreturn void _Exit(int)`,
        //       `void quick_exit(int) __noreturn`
        //   bionic libc/include/unistd.h:80     `__noreturn void _exit(int)`
        //   bionic libc/include/pthread.h:165   `void pthread_exit(void*) __noreturn`
        //   bionic libc/include/assert.h:79,85  `__assert(...)`, `__assert2(...)`
        //       both `__noreturn`
        //   bionic libc/include/setjmp.h:99,111,130 `_longjmp`, `longjmp`,
        //       `siglongjmp`, all `__noreturn`
        //   (sys/cdefs.h:101 `#define __noreturn __attribute__((__noreturn__))`)
        //   __stack_chk_fail: bionic's private/bionic_ssp.h:41 declares it plain
        //       `extern void __stack_chk_fail();`, but it is the COMPILER's
        //       no-return: gcc targhooks.cc default_external_stack_protect_fail
        //       builds its decl with `TREE_THIS_VOLATILE (t) = 1;` (:993), and
        //       tree.h: "On a FUNCTION_DECL node, this means the function does
        //       not return normally ... the same effect as setting the attribute
        //       noreturn". Its body (libc/bionic/__stack_chk_fail.cpp) is
        //       async_safe_fatal(), which aborts.
        //   _Unwind_Resume: gcc tree.cc:10331-10332 declares it ECF_NORETURN.
        //   libc++abi include/cxxabi.h:57-94 `[[__noreturn__]]` __cxa_throw,
        //       __cxa_rethrow, __cxa_bad_cast, __cxa_bad_typeid,
        //       __cxa_throw_bad_array_new_length, __cxa_pure_virtual.
        //
        // MACAPK_ARM32_TERM_POOL_WALK (default ON; =0 restores the old walk-back
        // exactly). The walk-back steps over at most 8 words, but a literal pool
        // is as long as the function needs (ARM ARM DDI 0406C.b A8.8.64 LDR
        // (literal): imm12, so a pool word can sit up to 4 KB from its load).
        // A word our own LDR-PC scan PROVES is pool data (literal_pool_bytes_arm)
        // can never be fallen into from code, so stepping over it is not a
        // guess and does not spend the 8-step heuristic budget; the extension
        // stops at 1024 words (the whole 4 KB reach).
        //
        // Measured victim (Jetpack Joyride 1.3.5 libmortargame.so, armeabi,
        // md5 c7c22b9a...): the member-function pointer 0x402158 (`b 0x401f5c`)
        // is address-taken only (`ldr r2,[pc]` 0x4026fc / `add r2,pc,r2`
        // 0x40270c / `str r2,[sp,#0x10]` into a delegate) and is preceded by
        // NINE provable pool words and then `bl __stack_chk_fail@plt`
        // (0x402130). The seed was rejected ("not preceded by terminator"),
        // never lifted, and every call through the delegate invoker 0x403528
        // (`blx r3` at 0x403554) hit the in-image panic stub and returned 0;
        // the engine's save-data layer then handed native_step a NULL stream
        // wrapper (0x3686e0 read the ELF header at guest 0) and the game shut
        // down after the splash. See docs/arm32-false-split-thumb-coincidence_2026-09-30.md.
        static const bool term_noreturn_plt_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_TERM_NORETURN_PLT");
            return !(e && *e == '0');
        }();
        static const bool term_pool_walk_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_TERM_POOL_WALK");
            return !(e && *e == '0');
        }();
        static const std::unordered_set<std::string> kNoReturnImports = {
            "abort", "exit", "_Exit", "quick_exit", "_exit", "pthread_exit",
            "__assert", "__assert2", "longjmp", "_longjmp", "siglongjmp",
            "__stack_chk_fail", "_Unwind_Resume",
            "__cxa_throw", "__cxa_rethrow", "__cxa_bad_cast",
            "__cxa_bad_typeid", "__cxa_throw_bad_array_new_length",
            "__cxa_pure_virtual",
        };
        // PLT stubs of the no-return imports, for EVERY consumer (the seed walk-
        // back below gates its use on TERM_NORETURN_PLT; the reach-extent walk
        // needs the same set unconditionally).
        std::unordered_set<uint32_t> all_noreturn_plt_stubs;
        for (const auto& imp : img.imports) {
            if (imp.plt_addr && kNoReturnImports.count(imp.name))
                all_noreturn_plt_stubs.insert(imp.plt_addr);
        }
        std::unordered_set<uint32_t> noreturn_plt_stubs;
        if (term_noreturn_plt_on) noreturn_plt_stubs = all_noreturn_plt_stubs;
        // Seeds each rule newly admitted (for the summary line after the loop).
        size_t term_noreturn_plt_hits = 0, term_pool_walk_hits = 0;
        auto is_preceded_by_terminator_in_context =
                [&](uint32_t vaddr, bool candidate_is_thumb = false,
                    bool fnptr_seed = false) -> bool {
            uint32_t check_addr = vaddr;
            // MACAPK_ARM32_TERM_POOL_WALK: provable pool words stepped over
            // beyond the historical 8-step budget (seed site only).
            int pool_walk_extra = 0;
            // MACAPK_ARM32_TERM_THUMB_SEED: the seed's own tag AND its own
            // bytes say "Thumb function entry" (see the gate's declaration).
            const bool thumb_entry_evidence = term_thumb_seed_on
                && candidate_is_thumb && opens_with_thumb_push_lr(vaddr);
            // Walk back through up to 8 padding instructions OR
            // literal-pool words. We allow either:
            //  - canonical NOPs (Thumb-16 bf00/46c0, ARM e1a00000)
            //  - literal-pool-LIKE 4-byte words (high nibble in
            //    {0,1,2,3} -- rare condition codes EQ/NE/CS/CC that
            //    compilers virtually never emit as the trailing
            //    instruction of a real function, so words with that
            //    high nibble in a 4-aligned slot before a candidate
            //    function entry are almost always literal pool data
            //    sitting between functions)
            //  - PROVABLY-literal-pool words (our LDR-PC scan caught
            //    them)
            //
            // HCR 1.43 motivating case: 0x76268 is a real function
            // (push {r4-r11, lr} ARM prologue) preceded by:
            //   0x76258: bl 0x2f126c   ← BL to no-return throw helper
            //   0x7625c..0x76264: 3 words of literal-pool data (e.g.
            //                     0x002ae88d) — high nibble 0 = EQ,
            //                     not a real ARM op
            //   0x76268: push {r4-r11, lr}
            // We never detect 0x2f126c as no-return, so the BL isn't a
            // terminator. But walking back through the 3 literal-pool
            // words finds 0x76248 = pop {r3-r7, pc} — a true ARM
            // terminator. With this walk-back, 0x76268 is accepted.
            for (int steps = 0; steps < 8; ++steps) {
                // v1.0.553: per-step Thumb-coincidence guard (same rule the
                // prologue boundary pass applies to the ORIGINAL candidate,
                // see its comment): when the prior 4-aligned word is a
                // valid AL/UNCOND ARM instruction, trust ONLY the ARM-mode
                // terminator check -- the Thumb check false-positives on
                // the high half of ARM words (e.g. `cmp r0,#0` = 0xe3500000,
                // high half 0xe350, byte-matches Thumb16 `b imm11`).
                // Without this, the walk-back stepped over a `bxeq lr`
                // (pool-like high nibble 0) and then "found" a Thumb
                // terminator inside FT_GlyphLoader_Add's cmp -> bogus
                // boundary at the push -> the Add body truncated to 2
                // insns -> glyph outlines never merged -> no text.
                bool arm_prior = false;
                if ((check_addr & 3u) == 0 && check_addr >= 4) {
                    auto pw = img.code_at(check_addr - 4, 4);
                    if (pw.size() == 4) {
                        uint8_t hn = pw[3] >> 4;
                        arm_prior = (hn == 0xe || hn == 0xf);
                    }
                }
                // v1.0.1053: the arm_prior veto asks "are the prior 4 bytes a
                // plausible AL/UNCOND ARM instruction", from ONE nibble, and on
                // that basis discards the Thumb reading as coincidence. In a
                // MIXED library that is the right call for an ARM-mode
                // candidate. It is the wrong call when the candidate arrived as
                // a Thumb-TAGGED function pointer, because bit[0] of a function
                // pointer is not a heuristic: ARM ARM DDI 0406C.b A2.3.2 and the
                // BX/BLX (register) operation pseudocode (A8.8.27 / A8.8.28) say
                // bit[0] of the branch value SELECTS the instruction set, so a
                // stored pointer with bit[0]=1 is the compiler stating that the
                // code at that address is Thumb. Code that falls into a Thumb
                // entry is therefore Thumb, and it is the ARM reading of the
                // preceding word that is the coincidence.
                //
                // Measured case (HCR 1.43 libgame.so, libxml2's xmlFileWrite at
                // 0x2b57d4, address-taken only, no dynsym, no relocation):
                //   0x2b57ca: 4f f0 ff 30   mov.w r0, #-1     (Thumb-2, 4 bytes)
                //   0x2b57ce: f3 e7         b 0x2b57b8        (Thumb-16, a real
                //                                              terminator)
                //   0x2b57d0: 38 80 09 00   literal pool word
                //   0x2b57d4: xmlFileWrite
                // The 4-ALIGNED word before 0x2b57d0 is 0xe7f330ff -- the tail
                // halfword of the mov.w plus the whole b -- whose top nibble is
                // 0xe, so arm_prior fires, the genuine Thumb `b` is vetoed, the
                // walk-back finds no ARM terminator and returns false. The seed
                // (already produced by the existing Thumb-16 PIC scan) is then
                // rejected, xmlFileWrite is never lifted, its panic stub returns
                // 0, and libxml2 writes a 0-byte UserDefault.xml.
                //
                // The v1.0.553 regression this veto exists to prevent is NOT
                // reopened: that candidate (FT_GlyphLoader_Add in ShatteredPD's
                // ARM-mode libgdx-freetype.so) is an ARM function, so its
                // pointers carry bit[0]=0 and candidate_is_thumb is false there.
                if (thumb_entry_evidence) arm_prior = false;
                if (is_preceded_by_terminator_early(check_addr, false) ||
                    (!arm_prior &&
                     is_preceded_by_terminator_early(check_addr, true))) {
                    if (pool_walk_extra > 0) ++term_pool_walk_hits;
                    return true;
                }
                // MACAPK_ARM32_TERM_NORETURN_PLT (see its declaration): an
                // UNCONDITIONAL ARM `bl` (cond AL, 0xeb) into the PLT stub of a
                // no-return import ends the previous function.
                if (fnptr_seed && term_noreturn_plt_on
                        && !noreturn_plt_stubs.empty()
                        && (check_addr & 3u) == 0 && check_addr >= 4) {
                    auto bw = img.code_at(check_addr - 4, 4);
                    if (bw.size() == 4) {
                        const uint32_t bi = uint32_t(bw[0])
                                          | (uint32_t(bw[1]) << 8)
                                          | (uint32_t(bw[2]) << 16)
                                          | (uint32_t(bw[3]) << 24);
                        if ((bi & 0xff000000u) == 0xeb000000u) {
                            int32_t imm24 = int32_t(bi & 0x00ffffffu);
                            if (imm24 & 0x00800000) imm24 |= int32_t(0xff000000u);
                            const uint32_t tgt = (check_addr - 4 + 8)
                                               + uint32_t(imm24 << 2);
                            if (noreturn_plt_stubs.count(tgt)) {
                                ++term_noreturn_plt_hits;
                                if (pool_walk_extra > 0) ++term_pool_walk_hits;
                                return true;
                            }
                        }
                    }
                }
                if (check_addr < 2) break;
                auto pre = img.code_at(check_addr - 2, 2);
                if (pre.size() < 2) break;
                uint16_t hw = uint16_t(pre[0]) | (uint16_t(pre[1]) << 8);
                if ((hw == 0xbf00 || hw == 0x46c0)   // Thumb-16 NOPs
                    && !thumb_hw_is_second_half(img, check_addr - 2)) {
                    check_addr -= 2;
                    continue;
                }
                // MACAPK_ARM32_TERM_NOPW: the Thumb-2 NOP (see the gate's
                // declaration). Thumb instructions are halfword-aligned, so
                // this is tested at any even address, not only 4-aligned ones.
                if (term_nopw_on && candidate_is_thumb && hw == 0x8000
                        && check_addr >= 4) {
                    auto pw4 = img.code_at(check_addr - 4, 2);
                    if (pw4.size() == 2
                            && (uint16_t(pw4[0]) | (uint16_t(pw4[1]) << 8))
                                   == 0xf3afu
                            && !thumb_hw_is_second_half(img, check_addr - 4)) {
                        check_addr -= 4;
                        continue;
                    }
                }
                if ((check_addr & 3) == 0 && check_addr >= 4) {
                    auto pre4 = img.code_at(check_addr - 4, 4);
                    if (pre4.size() == 4) {
                        uint32_t insn = uint32_t(pre4[0])
                                      | (uint32_t(pre4[1]) << 8)
                                      | (uint32_t(pre4[2]) << 16)
                                      | (uint32_t(pre4[3]) << 24);
                        if (insn == 0xe1a00000) {  // ARM NOP
                            check_addr -= 4;
                            continue;
                        }
                        // Walk back through literal-pool-looking
                        // 4-byte words. Two signals:
                        //   1) provably-literal-pool (our LDR-PC scan
                        //      caught it)
                        //   2) heuristically-literal-pool: high nibble
                        //      0..3 (rare condition codes), which
                        //      compilers virtually never emit as
                        //      runtime instructions, OR high nibble
                        //      0xf (unconditional/BLX-imm/data-special)
                        //      where the rest of the word doesn't
                        //      decode as a sensible BLX-imm (we
                        //      conservatively treat 0xf words as data
                        //      since BLX-imm rarely sits in a
                        //      pre-function-entry slot).
                        uint8_t hi = (insn >> 28) & 0xf;
                        bool likely_data =
                              (hi >= 0x0 && hi <= 0x3)  // rare conds
                            || (hi == 0xf);             // 0xf likely data
                        if (literal_pool_bytes_arm.count(check_addr - 4)
                            || literal_pool_bytes_thumb.count(check_addr - 4)
                            || likely_data) {
                            // MACAPK_ARM32_TERM_POOL_WALK (see its
                            // declaration): once the 8-step budget is spent,
                            // a PROVABLE ARM pool word is stepped over without
                            // spending it, up to 1024 words (seed site only).
                            if (fnptr_seed && term_pool_walk_on && steps == 7
                                    && pool_walk_extra < 1024
                                    && literal_pool_bytes_arm.count(check_addr - 4)) {
                                ++pool_walk_extra;
                                --steps;
                            }
                            check_addr -= 4;
                            continue;
                        }
                    }
                }
                break;
            }
            return false;
        };
        // Walk forward in known_starts past entries that are mid-
        // function (not preceded by a real function terminator).
        // Returns the first known_start STRICTLY > vaddr that looks
        // like a real function boundary, or seg_end if none exists.
        // MACAPK_ARM32_ARM_EXTENT_ALIGN=0 restores the pre-v1.0.1088 behaviour.
        // Default ON: an ARM instruction is 4 bytes and 4-ALIGNED (ARM ARM DDI
        // 0406C.b A3.1: "ARM instructions are word-aligned"), so an ARM
        // function's extent can only END on a 4-aligned address. Clipping an
        // ARM lift at a 2-mod-4 candidate is therefore unconditionally wrong --
        // not "probably a bad candidate", but arithmetically impossible as an
        // ARM boundary -- whoever minted that candidate, and whether or not it
        // is a real Thumb entry elsewhere in a mixed-mode image.
        //
        // MEASURED (Jetpack Joyride 1.3.5, libmortargame.so): discovery mints a
        // phantom Thumb start at 0x45468e, which is 2 bytes INTO the ARM
        // instruction `ldrlt lr, [sp, #0x14]` at 0x45468c, inside the ARM
        // function 0x454518. That clipped 0x454518's extent to
        // [0x454518, 0x45468e), so guest 0x454690..0x454770 -- including the
        // function's own `pop {..., pc}` -- was never translated ANYWHERE in the
        // module, and its body ended in a bare epilogue mid-basic-block. The
        // intra-function `beq 0x454740` then had to leave the function, and the
        // entry stub for it landed in the phantom's Thumb misdecode of the same
        // bytes, from which garbage control flow reached a real block with all
        // four argument registers zero and stored through r3 == 0.
        //
        // This is deliberately MUCH narrower than the terminator-gating that
        // v1.0.58 tried and reverted: that walked past 4-ALIGNED entries too and
        // merged real sibling functions. This cannot, because no ARM boundary is
        // ever 2-mod-4. Thumb lifts are untouched (Thumb IS 2-aligned).
        // Write-up: docs/jetpack-shared-tail-and-null-engine_2026-08-27.md
        static const bool s_arm_extent_align = [](){
            const char* e = std::getenv("MACAPK_ARM32_ARM_EXTENT_ALIGN");
            return !(e && *e == '0'); }();
        auto next_real_function_start = [&](
                const std::set<uint32_t>& starts,
                uint32_t vaddr, uint32_t seg_end,
                bool arm_mode = false) -> uint32_t {
            // v1.0.59 PROVABLY-data check ONLY: skip any
            // known_starts entry that landed inside a literal pool
            // (impossible to be a real function — the load
            // instruction defining the pool is the proof).
            // We DELIBERATELY DO NOT also gate on the terminator
            // heuristic here. The terminator check is an additional
            // filter for new-candidate admission (data_fnptr seeds,
            // BL targets, prologue scan), but treating it as a hard
            // requirement for already-committed known_starts entries
            // empirically broke HCR 1.43 scene creation (v1.0.57g→
            // v1.0.58: many real entries follow either a literal
            // pool gap or a non-canonical predecessor that the
            // terminator check rejects, and walking past them
            // merges sibling functions in ways the engine doesn't
            // tolerate).
            auto it = starts.upper_bound(vaddr);
            while (it != starts.end()) {
                uint32_t cand = *it;
                if (is_in_literal_pool(cand)) { ++it; continue; }
                // An ARM function cannot end on a non-word-aligned address.
                if (s_arm_extent_align && arm_mode && (cand & 3u) != 0u) {
                    ++it; continue;
                }
                return cand;
            }
            return seg_end;
        };
        auto is_mid_function_candidate = [&](uint32_t vaddr) -> bool {
            // v1.0.59 PROVABLY-data check: if the candidate is in a
            // literal pool word that some LDR-PC-relative load
            // dereferences, it's PROVABLY data and never a function.
            if (is_in_literal_pool(vaddr)) return true;
            // v1.0.59 LIBERAL otherwise: don't gate on the terminator
            // heuristic. Empirically this rejected too many real
            // entries (Thumb entries after a NOP, ARM entries
            // following literal pool gaps, entries preceded by BL,
            // etc.) and the literal-pool authoritative check is
            // strong enough to catch the common bogus cases.
            return false;
            // ↓ DEAD code (kept as a roadmap if we ever need to
            //   tighten again):
            // NB: we intentionally DO NOT short-circuit on
            // is_authoritative_bl_target. A bogus Thumb-mode synth's
            // decode of mid-ARM bytes can emit a fake BL whose target
            // ends up in external_bl_targets even though the target
            // isn't a real function entry. HCR 1.43 cascade:
            //   bogus synth_002033fa (Thumb decode of mid-ARM bytes at
            //   0x2033f8/0x2033fc) emits BL 0x202f20
            //   -> 0x202f20 becomes "authoritative"
            //   -> CCNode at 0x202ea0 capped at 0x80 instead of 0x124
            //   -> m_pActionManager = director->getActionManager()
            //      store at vaddr 0x202f9c never lifted
            //   -> all scenes have m_pActionManager = 0
            //   -> menu rendered all-black.
            // Trusting only the byte-pattern signal (preceded-by-
            // terminator in either mode) breaks the cascade -- real
            // function entries always have terminators above them per
            // every compiler we've seen.
            // [DEAD] If we ever need terminator-based gating again:
            // if (is_preceded_by_terminator_in_context(vaddr)) return false;
            // return true;
        };
        // Conservative "is this a real function entry?" byte check,
        // available early (the richer looks_like_arm/thumb_prologue
        // lambdas are defined later, after the BL/synth loop). Used
        // to let an authoritative BL target override a heuristic
        // synth that wrongly absorbed it via a SKIPDATA over-run.
        // Only the patterns a compiler actually emits at a function
        // entry -- a mid-function intra-branch leak (the v1.0.28
        // hazard this guards against) is mid-computation and will
        // NOT start with one of these.
        auto looks_like_fn_prologue = [&](uint32_t vaddr) -> bool {
            auto code = img.code_at(vaddr, 4);
            if (code.size() < 2) return false;
            // ARM `push {...}` / `stmfd sp!, {...}` -- e92dXXXX,
            // LE bytes [XX XX 2d e9].
            if (code.size() >= 4 && code[2] == 0x2d && code[3] == 0xe9)
                return true;
            // ARM `mov ip, sp` (e1a0c00d) -- AAPCS frame prologue.
            if (code.size() >= 4 && code[0] == 0x0d && code[1] == 0xc0 &&
                code[2] == 0xa0 && code[3] == 0xe1) return true;
            // ARM `sub sp, sp, #imm` (e24dd0XX) followed by a
            // callee-saved store -- leaf prologue.
            if (code.size() >= 4 && code[1] == 0x4d && code[2] == 0xd0 &&
                code[3] == 0xe2) {
                auto nxt = img.code_at(vaddr + 4, 4);
                if (nxt.size() >= 4 && nxt[3] == 0xe5 && nxt[2] == 0x8d)
                    return true;
            }
            // Thumb16 push {rN..lr} (b5XX) / push {rN..r7} (b4XX).
            if (code[1] == 0xb5 || code[1] == 0xb4) return true;
            // Thumb-2 push.w {...}: halfword-major [2d e9 XX XX].
            // Disambiguate from the trailing half of an ARM push:
            // if vaddr-2 (4-aligned) looks like an ARM push, these
            // bytes are inside that instruction, not a real entry.
            if (code.size() >= 2 && code[0] == 0x2d && code[1] == 0xe9) {
                // v1.0.531: the "trailing half of an ARM push" case can ONLY
                // arise when vaddr-2 is itself 4-aligned (ARM instructions are
                // 4-aligned), i.e. vaddr == 2 (mod 4). When vaddr is 4-aligned,
                // vaddr-2 is NOT an instruction boundary and pm[2..3] just alias
                // THIS push.w's own first halfword (2d e9) -> a FALSE reject.
                // The original comment intended "vaddr-2 (4-aligned)" but never
                // checked the alignment. This false-negative dropped real
                // 4-aligned Thumb-2 prologues that follow a nop / literal-pool
                // word ending in 2d e9 (HCR 1.43 libstdc++ num_put helpers
                // 0x2da378 / 0x2db6d4 / 0x2dbd20), so their `b`/`bl` callers
                // resolved to panic-stub-returns-0 and corrupted the streambuf.
                if ((vaddr & 3u) == 2u && vaddr >= 2) {
                    auto pm = img.code_at(vaddr - 2, 4);
                    if (pm.size() >= 4 && pm[2] == 0x2d && pm[3] == 0xe9)
                        return false;
                }
                return true;
            }
            // Thumb `nop` (bf00) followed by a real prologue. GCC emits a
            // 2-byte nop to 4-align the body of a Thumb function, and the
            // ENTRY is the nop -- function pointers taken to it point at the
            // nop, not past it. Checking only the first halfword therefore
            // misses the whole class.
            //
            // HCR 1.43 0x2705ee is exactly this: `nop ; push.w {r4-r8,sb,sl,
            // lr} ; ldrb r7,[r0,#0xb] ...`, a textbook Thumb-2 body reached
            // ONLY through a function pointer written at runtime -- so no
            // static relocation names it, and it is not in .ARM.exidx either
            // (no unwind info), which means neither the reloc seeds nor the
            // exidx authority can find it. The prologue scan is the only
            // route left, and one alignment nop was hiding it.
            //
            // Deliberately narrow: a bare nop proves nothing, so this only
            // fires when the NEXT halfword is itself a recognised prologue.
            if (code[0] == 0x00 && code[1] == 0xbf) {
                auto nxt = img.code_at(vaddr + 2, 4);
                if (nxt.size() >= 2 &&
                    (nxt[1] == 0xb5 || nxt[1] == 0xb4 ||
                     (nxt[0] == 0x2d && nxt[1] == 0xe9)))
                    return true;
            }
            return false;
        };
        // Lookup helper: returns the is_thumb flag of the symbol
        // whose body contains `addr`, or std::nullopt if `addr` is
        // outside every known symbol body.
        auto lookup_is_thumb = [&](uint32_t addr) -> std::optional<bool> {
            // Binary search for the largest range.start <= addr.
            auto it = std::upper_bound(
                thumb_ranges.begin(), thumb_ranges.end(), addr,
                [](uint32_t a, const ThumbRange& r) {
                    return a < r.start;
                });
            if (it != thumb_ranges.begin()) {
                auto prev = it;
                --prev;
                if (addr < prev->end) {
                    // addr is INSIDE a symboled function -- direct
                    // mode answer.
                    return prev->is_thumb;
                }
                // v1.0.65: addr is in an UNSYMBOLED GAP. Check the
                // symbol immediately before (prev) and immediately
                // after (it). If both have the same mode flag,
                // strongly infer addr is the same mode -- compilers
                // virtually never mix ARM and Thumb within a code
                // region (mode changes require a BX or interworking
                // veneer, both rare and always at function entries).
                //
                // Motivating HCR 1.43 case: 0x2cf478 lives in the
                // 0x2cd668..0x2cfdf4 unsymboled gap, bracketed by
                // xmlNewTextWriterFilename (Thumb, ends 0x2cd668)
                // and _ZSt14__convert_to_vIf... (Thumb, starts
                // 0x2cfdf4). Without this hint, sniff_thumb_at
                // fell back to ARM mode (the Thumb16 `ldr r2,
                // [r0,#0xc]` at 0x2cf478 doesn't byte-match any
                // recognized Thumb16 prologue), the lifter
                // ARM-decoded Thumb bytes, and one of those
                // mis-decodes hit `svc #0xe7f7` (the real Thumb16
                // backward branch encoding). That svc fired in a
                // hot loop, generating 2.9M log entries before
                // the JVM GC tripped on collateral heap corruption.
                if (it != thumb_ranges.end()
                    && prev->is_thumb == it->is_thumb) {
                    return prev->is_thumb;
                }
            }
            return std::nullopt;
        };
        // Secondary lookup: when `addr` isn't in a known symbol body
        // but there's a previously-discovered function entry within
        // `max_gap` bytes (forward), return its thumb flag. A BL
        // target found ~2-16 bytes before a prologue-scan-found push
        // is almost certainly the SAME function (the push is its
        // prologue, the BL target is its actual entry, the gap is
        // the function's pre-push bounds check / null guard / etc.).
        // Without this, `sniff_thumb_at` falls back to ARM mode when
        // the bytes don't match a Thumb-16 prologue pattern, but
        // Capstone can still produce a valid (wrong) ARM disasm of
        // Thumb bytes -- e.g. `ldr r3, [r1]` (Thumb 0x680b) decodes
        // as `ldrlt r6, [r0, #-0x80b]!` in ARM. Both succeed, so the
        // mode-retry doesn't fire. Observed in HCR 1.43 on
        // synth_002dc1e8 (_M_grab entry; 2 bytes before the
        // prologue-discovered synth_002dc1ea), producing wrong IR
        // that left `*dst` unwritten and triggered the downstream
        // throw chain.
        auto nearest_known_thumb = [&](
                const std::set<uint32_t>& starts,
                const std::unordered_map<uint32_t, bool>& thumb_map,
                uint32_t addr, uint32_t max_gap)
                -> std::optional<bool> {
            // First: was a nearby function already discovered (symbol
            // table, prologue scan, prior BL/synth)? If so, inherit
            // its mode -- a BL target found within a few bytes of a
            // real function entry almost certainly belongs to the
            // same function.
            auto it = starts.upper_bound(addr);
            if (it != starts.end() && (*it - addr) <= max_gap) {
                auto th = thumb_map.find(*it);
                if (th != thumb_map.end()) return th->second;
            }
            // Fallback: peek at EXACTLY +2 bytes for a Thumb-16
            // prologue pattern (b5/b4/b0/af in byte 1). Catches the
            // pattern where a Thumb function starts with a single
            // 2-byte instruction (e.g. `ldr r3, [r1]` for `_M_grab`
            // in libstdc++ pre-C++11 string code) and the actual
            // push prologue sits immediately after. Restricted to
            // exactly +2 because peeking deeper (+4..+16) into ARM
            // instruction streams generates false positives: byte 1
            // of any ARM 4-byte instruction can coincidentally
            // equal one of those prologue-pattern bytes, and that
            // bogus match cascades into a wrong-mode lift.
            auto peek = img.code_at(addr + 2, 2);
            if (peek.size() >= 2) {
                uint8_t b1 = peek[1];
                if (b1 == 0xb5 || b1 == 0xb4
                    || b1 == 0xb0 || b1 == 0xaf) {
                    return true;
                }
            }
            return std::nullopt;
        };
        // Seed: lift each .init_array entry too. They're not in
        // external_targets (no symboled function calls them), but
        // running them at dlopen time is what makes cocos2d-x's
        // singletons (CCPoolManager, CCDirector, ...) work.
        std::vector<uint32_t> init_seed_to_lift;
        for (uint32_t v : img.init_funcs) {
            if (known_starts.count(v)) continue;
            init_seed_to_lift.push_back(v);
        }
        // v1.0.63: merge veneer-target seeds collected during the
        // ARM long-call veneer scan above. These are functions
        // reached ONLY via a veneer's `bl <veneer_va>` + computed
        // jump, so the BL/synth discovery pass never sees them.
        // Without seeding them, the veneer body's tail-call to
        // `@__macapk_target_<target>` resolves to a panic stub
        // and the caller hot-loops indirect calls forever.
        for (uint32_t v : veneer_target_seeds) {
            if (known_starts.count(v)) continue;
            init_seed_to_lift.push_back(v);
        }
        // =====================================================================
        // [DIAGNOSTIC, USER-PERMITTED PER-APK]
        //   MACAPK_FORCE_LIFT_VADDRS — force-lift specific addresses
        // ---------------------------------------------------------------------
        // What it does:
        //   Env var is comma/space-separated list of hex vaddrs (with
        //   optional `:thumb` suffix per address; default ARM). Each
        //   listed vaddr is unconditionally added to init_seed_to_lift,
        //   bypassing all heuristic discovery filters. This lets us
        //   force-lift specific functions we suspect are real but were
        //   rejected by our filters, then test whether lifting them
        //   resolves a runtime issue.
        // Why it exists:
        //   The lifter's discovery (R_ARM_RELATIVE + BL/synth + PC-rel
        //   scan + prologue scan + terminator walk-back) is heuristic
        //   and conservative. Some real function entries fall through
        //   the cracks -- e.g. ARM long-call veneers
        //   `ldr r12, [pc]; add pc, r12, pc` that sit just after a
        //   Thumb `bx pc + nop` mode-switch, or Thumb functions with
        //   non-canonical prologues (`ldr r0, [r0]` instead of
        //   `push {...}`). Without force-lifting, the only way to test
        //   "is X a real function?" is to modify the heuristic itself
        //   and retranslate, which is slow.
        // Why it's "per-APK"-flavored:
        //   Vaddr list is build-specific. User passes them on the
        //   command line. If not set, the override is inactive and
        //   has zero translation-time cost.
        // Removal condition / framework-fix path:
        //   Once force-lifting a particular vaddr reliably fixes a
        //   runtime issue, derive the GENERIC discovery pattern that
        //   would have picked it up:
        //   - "ldr r12, [pc]; add pc, r12, pc" → ARM long-call veneer
        //     detector. Pattern-match the two instructions at a 4-
        //     aligned address. Emit forwarder-via-tail-call body.
        //   - Thumb fn with `ldr r0, [r0]` first → broaden the
        //     prologue patterns. This pattern is common for "small
        //     callback" functions in libxml2's xmlNewTextWriter*
        //     region.
        //   - Function preceded by literal pool + `bl no-return-helper`
        //     → mark the no-return helper, then accept any candidate
        //     after a literal-pool-after-no-return-BL gap.
        //
        // VERIFIED CASES (per-APK, for future generic detection):
        //   - HCR 1.43 ARM32: vaddrs 0x2f1c80, 0x2f1d40 are ARM long-
        //     call veneers (ldr r12,[pc]; add pc,r12,pc) called from
        //     Thumb code via `bx pc + nop` mode-switch. Force-lifting
        //     them turns ~882 indirect-call panic-stub hits into real
        //     forwarder dispatches.
        //   - HCR 1.43 ARM32: vaddrs 0x2db900, 0x2dbc80, 0x2cd988 are
        //     Thumb fns in libxml2's xmlNewTextWriterFilename region.
        //     Their prologue is `ldr r0,[r0]` (Thumb-16) not push, so
        //     they slip through prologue-pattern discovery. Force-
        //     lifting handles them until prologue scan is broadened.
        //
        // Documentation pointer:
        //   `Current_State.md` v1.0.60 entry; this comment block.
        {
            const char* env = std::getenv("MACAPK_FORCE_LIFT_VADDRS");
            if (env && *env) {
                std::fprintf(stderr,
                    "[translator] MACAPK_FORCE_LIFT_VADDRS=%s\n", env);
                const char* p = env;
                while (*p) {
                    while (*p == ',' || *p == ' ' || *p == '\t') ++p;
                    if (!*p) break;
                    char* endp = nullptr;
                    unsigned long v = std::strtoul(p, &endp, 16);
                    if (endp == p) break;
                    // The thumb-mode bit (LSB == 1) is the standard
                    // ARM convention for Thumb function pointers.
                    // We strip it when adding to known_starts but
                    // preserve the bit so synth_lift correctly
                    // picks Thumb-mode disassembly.
                    uint32_t v_clean = static_cast<uint32_t>(v) & ~1u;
                    bool thumb = (v & 1u) != 0;
                    p = endp;
                    if (known_starts.count(v_clean)) {
                        std::fprintf(stderr,
                            "[translator] force-lift 0x%x (thumb=%d): "
                            "already in known_starts\n",
                            v_clean, int(thumb));
                        continue;
                    }
                    std::fprintf(stderr,
                        "[translator] force-lift seed 0x%x (thumb=%d)\n",
                        v_clean, int(thumb));
                    // Force into init_seed_to_lift so it goes through
                    // the standard seed-lift path (with size = next-
                    // real-function-start - vaddr). If thumb, the
                    // discovery code will infer mode from byte
                    // patterns / nearby symbols; for a force-listed
                    // thumb function we ALSO pre-populate known_thumb.
                    init_seed_to_lift.push_back(v_clean);
                    if (thumb) {
                        // Pre-seed the thumb hint so sniff_thumb_at /
                        // lookup_is_thumb downstream don't have to
                        // re-derive it.
                        known_thumb[v_clean] = true;
                    }
                }
            }
        }
        // MACAPK_ARM32_SIZELESS_EXPORTS: seed every sizeless GLOBAL/WEAK
        // STT_FUNC export (see arm32_sizeless_exports_on). The map records
        // the mode the symbol itself declares (st_value bit 0), which the
        // seed loop below prefers over every heuristic: the linker wrote it.
        // Empty with the gate off, and then the seed loop is unchanged.
        std::unordered_map<uint32_t, bool> sizeless_export_mode;
        if (arm32_sizeless_exports_on()) {
            const bool have_x_sections = [&] {
                for (const auto& sh : img.shdrs)
                    if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u) &&
                        sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto in_exec_section = [&](uint32_t v) -> bool {
                if (!have_x_sections) return true;   // stripped: PT_LOAD decides
                for (const auto& sh : img.shdrs) {
                    if (!(sh.sh_flags & 0x4u) || !(sh.sh_flags & 0x2u)) continue;
                    if (v >= sh.sh_addr && v < sh.sh_addr + sh.sh_size) return true;
                }
                return false;
            };
            size_t n_seeded = 0, n_known = 0, n_notcode = 0, n_misaligned = 0;
            size_t n_total = 0;
            for (const auto& s : img.symbols) {
                if (s.type != pt::STT_FUNC || s.size != 0) continue;
                if (s.bind != 1 && s.bind != 2) continue;   // STB_GLOBAL / STB_WEAK
                ++n_total;
                const uint32_t v = s.address & ~1u;
                // ARM instructions are word aligned, so a misaligned
                // "ARM" FUNC is not an entry we can decode.
                if (!s.is_thumb && (v & 3u)) { ++n_misaligned; continue; }
                if (!in_exec_section(v)) { ++n_notcode; continue; }
                if (known_starts.count(v)) { ++n_known; continue; }
                if (!sizeless_export_mode.emplace(v, s.is_thumb).second) continue;
                init_seed_to_lift.push_back(v);
                ++n_seeded;
            }
            if (n_total)
                std::fprintf(stderr,
                    "[translator] sizeless FUNC exports: %zu, seeded %zu, "
                    "%zu already a known start, %zu not in an executable "
                    "section, %zu misaligned for ARM "
                    "(MACAPK_ARM32_SIZELESS_EXPORTS=0 disables)\n",
                    n_total, n_seeded, n_known, n_notcode, n_misaligned);
        }
        // Also seed every function pointer that lives in static data
        // (vtables / callback tables / globals). The ELF reader
        // collects these by walking R_ARM_RELATIVE entries that point
        // into executable PT_LOAD segments. Without seeding them we
        // miss bodies that are reachable only via indirect call
        // through such pointers -- notably libxml2's Thumb-mode init
        // helpers (xmlInitParser et al.) and the dispatch entries in
        // xmlOutputCallbackTable. Missing those silently breaks
        // sprite-frame loading in cocos2d-x (HCR's "black screen
        // with empty scene tree" symptom).
        // v1.0.63: split data_fnptr_seeds by provenance. R_ARM_RELATIVE
        // anchored seeds are linker-authoritative -- the linker emitted
        // the relocation because the source took the address of a
        // function (e.g. `&Class::method` for a vtable entry, or a
        // C-callback registration). These are guaranteed real function
        // pointers, so we trust them past the terminator-precedence
        // filter that catches heuristic PC-rel-scan false positives.
        //
        // The motivating case: HCR 1.43 AppDelegate::vtable[2] at vaddr
        // 0x361ce0 holds 0x76268 (applicationDidFinishLaunching). The
        // entry is reached via R_ARM_RELATIVE so it lands in
        // authoritative_fnptr_seeds. Its predecessor at 0x76264 is a
        // `bl 0x2f126c` to an ARM long-call veneer that targets
        // strpbrk@plt (NOT a no-return helper), so the BL-to-thrower
        // terminator check doesn't fire and the candidate is rejected
        // by the late `is_preceded_by_terminator_in_context` filter.
        // Without the bypass, applicationDidFinishLaunching never
        // gets lifted, CCApplication::run hits a panic stub on
        // vtable[2], and HCR's scene tree never gets constructed.
        std::unordered_set<uint32_t> authoritative_set;
        authoritative_set.reserve(img.authoritative_fnptr_seeds.size());
        for (uint32_t v : img.authoritative_fnptr_seeds) {
            authoritative_set.insert(v);
        }
        // v1.0.806: subset of authoritative_set whose ONLY backing is the
        // ARM-mode PIC ldr+add+str/stm scan (elf_reader.cpp), not a
        // .ARM.exidx entry or an R_ARM_RELATIVE relocation. See the field
        // comment on Elf32Image::heuristic_pic_fnptr_seeds (elf_reader.h)
        // and docs/arm32-discovery-mid-instruction-fnptr-seed_2026-07-28.md
        // for the full worked example this set exists to fix (HCR 1.8.1
        // libgame.so 0xe07d4). Checked with `|| .count(v | 1u)` at call
        // sites that also try the Thumb-tagged form, matching how
        // authoritative_set itself is probed elsewhere in this function.
        std::unordered_set<uint32_t> heuristic_pic_set;
        heuristic_pic_set.reserve(img.heuristic_pic_fnptr_seeds.size());
        for (uint32_t v : img.heuristic_pic_fnptr_seeds) {
            heuristic_pic_set.insert(v);
        }
        // v1.0.1053: THUMB-2 32-BIT PIC function-pointer scan.
        //
        // elf_reader.cpp carries two address-taken scans and neither covers the
        // Thumb-2 32-bit form:
        //   - the Thumb-16 scan matches `LDR Rt,[PC,#imm8*4]` T1 (bytes
        //     0x48..0x4f in the high byte, so Rt is r0-r7 ONLY) followed by
        //     `ADD Rdn, PC` T2 restricted to bytes 0x78|Rdn (Rdn r0-r7 ONLY).
        //   - the v1.0.529/533 ARM-mode analog matches `LDR Rt,[PC,#imm12]` A1
        //     plus `ADD Rt,PC,Rt` plus a store/BL-argument install.
        // A function whose address is materialised into a HIGH register, which
        // forces the 32-bit encodings of both instructions, is invisible to
        // both, so it is never lifted and its panic stub returns 0.
        //
        // Measured victim (GD Lite 2.2.11, libcocos2dcpp.so, pugixml's writer
        // callback at 0x4b2ffc; no dynsym entry, no relocation; the runtime logs
        // 8 x "indirect call to in-image vaddr 0x4b2ffd"):
        //   0x4b6a78: df f8 64 87   ldr.w r8, [pc, #0x764]
        //   0x4b6a7c: f8 44         add   r8, pc
        //
        // Encodings matched, from ARM ARM DDI 0406C.b:
        //
        //   A8.8.65 LDR (literal), Encoding T2 (ARMv7):
        //     hw1 = 1111 1000 U101 1111,  hw2 = Rt<<12 | imm12
        //     i.e. hw1 == 0xf85f (U=0) or 0xf8df (U=1), matched as
        //     (hw1 & 0xff7f) == 0xf85f -- the SAME test the literal-pool scan
        //     earlier in this file already uses, so the two stay consistent.
        //     "address = if U then Align(PC,4)+imm12 else Align(PC,4)-imm12",
        //     with PC = insn address + 4 (A2.3.1). Rt == '1111' (a load into
        //     PC, i.e. a branch) and Rt == '1101' (SP) are not a function-pointer
        //     MATERIALISATION into a register, so both are rejected.
        //     (Correction, v1.0.1655: this used to call Rt == '1111' "a memory
        //     hint". That is true only of the BYTE/HALFWORD literal forms, which
        //     LLVM's DecodeT2LoadLabel re-opcodes to PLD/PLI; for the WORD form
        //     matched here, Rt goes through DecodeGPRRegisterClass like any other
        //     register -- llvm/lib/Target/ARM/Disassembler/ARMDisassembler.cpp,
        //     DecodeT2LoadLabel. The verdict "reject 15" is unchanged.)
        //
        //   v1.0.1655, MACAPK_ARM32_PICFN_THUMB2_LR (default ON; =0 restores the
        //     old r0-r12 range): Rt == '1110' (LR) IS accepted. After the
        //     prologue has pushed LR, clang allocates it as an ordinary scratch
        //     register, and nothing in the T2 encoding restricts it (same
        //     DecodeGPRRegisterClass path). The earlier `rt >= 13` test threw it
        //     away together with SP and PC. Measured victim (GD Lite 2.2.11,
        //     libpng's png_read_filter_row with png_init_filter_functions
        //     inlined; the other three filters use r7/r6/r5 and the Thumb-16
        //     scan already seeds them):
        //       0x510db6: df f8 34 e0   ldr.w lr, [pc, #0x34]   (pool 0xffffcd7d)
        //       0x510dc0: fe 44         add   lr, pc            -> 0x50db41
        //       0x510dca: c0 f8 8c e3   str.w lr, [r0, #0x38c]  read_filter[0]
        //     0x50db40 is png_read_filter_row_sub. Unlifted, its panic stub
        //     returned 0 for every Sub-filtered row, so every PNG the guest
        //     decoded itself came out corrupt (garbled font glyphs, a
        //     transparent background); PNGs whose rows are all filter None
        //     (the slider bar) were the only ones that survived.
        //
        //   A8.8.6 ADD (register), Encoding T2 -- "ADD<c> <Rdn>,<Rm>":
        //     0100 0100 DN Rm(4) Rdn(3),  d = UInt(DN:Rdn), m = UInt(Rm)
        //     With Rm = PC = '1111' this is hw = 0x4478 | (DN<<7) | Rdn(2:0),
        //     which covers Rd = r0-r14 in one expression; the Thumb-16 scan's
        //     0x78|Rdn form is exactly the DN=0 half of it. Rd = 15 is excluded
        //     because `ADD PC,PC` is a branch, and Rd = 13 by the SP carve-out
        //     in the same encoding description.
        //
        //   PC value for the ADD: A2.3.1 -- "the PC value ... is the address of
        //     the instruction plus 4". Align(PC,4) applies only where the
        //     instruction description says so (ADR, LDR literal); ADD (register)
        //     does NOT, so the add uses the UNALIGNED insn+4. Getting this wrong
        //     is a known live bug family in this tree (a Thumb `add r2,pc`
        //     lifted with the word-ALIGNED pc), so it is spelled out here.
        //
        //   Low bit: the computed value is a Thumb function POINTER, so bit[0]
        //     is set (A2.3.2 / BX,BLX pseudocode: bit[0] selects the
        //     instruction set). We require bit[0]==1 -- exactly the
        //     discriminator the Thumb-16 scan uses to tell an fnptr from a data
        //     address -- then strip it to get the function start and record
        //     thumb=true. Not stripping it yields an odd address that decodes as
        //     garbage.
        //
        // The seeds join data_fnptr_seeds NON-authoritatively, so the whole
        // filter chain below (known body, literal pool, terminator precedence)
        // still adjudicates them.
        //
        // v1.0.1655: DEFAULT ON (was default OFF since v1.0.1053, "per the
        // discovery-regression history"). MACAPK_ARM32_PICFN_THUMB2=0 restores
        // the old default exactly (no scan, data_fnptr_seeds_all is a plain copy
        // in the same order). Why now: GD Lite's forced-ARM32 run loses BOTH
        // pugixml's strconv_attribute (0x4b2ffc: every <plist version="1.0">
        // fails with status_bad_attribute, so every sprite-frame plist is empty
        // and every createWithSpriteFrameName returns NULL) and libpng's Sub
        // filter (0x50db40, via the LR form above) to this one hole. Thumb-2
        // LDR.W is also what clang emits for a LOW register whenever the pool
        // is past the 1 KB reach of the 16-bit LDR, so the hole is generic, not
        // a GD Lite quirk: HCR 1.8.1's libjpeg method table (0x1c74cc,
        // 0x1c74ec, `ldr.w r3,[pc,..]; add r3,pc`) is the same shape.
        // Blast radius, predicted by debug/arm32_discovery_blast.py gateA over
        // every ARM32 .so in ../Apks and pinned by debug/picfnthumb2_gate.sh.
        // Write-up: docs/arm32-thumb2-pic-fnptr-lr-and-nopw_2026-09-27.md.
        std::vector<uint32_t> picfn_thumb2_seeds;
        static const bool picfn_thumb2_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_PICFN_THUMB2");
            return !(e && *e == '0');
        }();
        static const bool picfn_thumb2_lr_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_PICFN_THUMB2_LR");
            return !(e && *e == '0');
        }();
        if (picfn_thumb2_on) {
            // Same SHF_ALLOC|SHF_EXECINSTR restriction the other scans use: the
            // executable PT_LOAD also holds .rodata, whose bytes alias code.
            const bool have_code_sections_t2 = [&]() {
                for (const auto& sh : img.shdrs)
                    if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u)
                        && sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto in_code_section_t2 = [&](uint32_t va) -> bool {
                if (!have_code_sections_t2) return true;  // stripped: keep reach
                for (const auto& sh : img.shdrs) {
                    if (!(sh.sh_flags & 0x4u)) continue;
                    if (!(sh.sh_flags & 0x2u)) continue;
                    if (va < sh.sh_addr) continue;
                    if (va >= sh.sh_addr + sh.sh_size) continue;
                    return true;
                }
                return false;
            };
            std::unordered_set<uint32_t> seen_t2;
            for (uint32_t s : img.data_fnptr_seeds) seen_t2.insert(s);
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                const auto& bs = seg.bytes;
                const uint32_t base = seg.vaddr;
                if (bs.size() < 4) continue;
                for (size_t off = 0; off + 4 <= bs.size(); off += 2) {
                    uint16_t hw1 = uint16_t(bs[off])
                                 | (uint16_t(bs[off + 1]) << 8);
                    if ((hw1 & 0xff7f) != 0xf85f) continue;   // LDR (lit) T2
                    uint16_t hw2 = uint16_t(bs[off + 2])
                                 | (uint16_t(bs[off + 3]) << 8);
                    uint32_t rt = (hw2 >> 12) & 0xfu;
                    // SP / PC: not an fnptr materialisation. LR only with the
                    // LR gate (see MACAPK_ARM32_PICFN_THUMB2_LR above).
                    if (rt == 13 || rt == 15) continue;
                    if (rt == 14 && !picfn_thumb2_lr_on) continue;
                    uint32_t imm12 = hw2 & 0xfffu;
                    const bool U = (hw1 >> 7) & 1;
                    // Align(PC,4) where PC = insn + 4 (A8.8.65 T2).
                    uint32_t pc_aligned = (base + uint32_t(off) + 4) & ~3u;
                    uint32_t lit_addr = U ? (pc_aligned + imm12)
                                          : (pc_aligned - imm12);
                    if (lit_addr < base) continue;
                    size_t lit_off = lit_addr - base;
                    if (lit_off + 4 > bs.size()) continue;
                    uint32_t literal = uint32_t(bs[lit_off])
                                     | (uint32_t(bs[lit_off + 1]) << 8)
                                     | (uint32_t(bs[lit_off + 2]) << 16)
                                     | (uint32_t(bs[lit_off + 3]) << 24);
                    // ADD (register) T2 with Rm = PC and Rdn = the same Rt.
                    uint16_t want_add = uint16_t(0x4478u
                                                 | ((rt >> 3) << 7)
                                                 | (rt & 7u));
                    // Same 24-byte-ish look-ahead the Thumb-16 scan uses: clang
                    // schedules other setup between the load and the add.
                    for (size_t la = off + 4;
                         la + 2 <= bs.size() && la < off + 28; la += 2) {
                        uint16_t h = uint16_t(bs[la])
                                   | (uint16_t(bs[la + 1]) << 8);
                        if (h != want_add) continue;
                        // ADD uses the UNALIGNED PC = add address + 4.
                        uint32_t target = (base + uint32_t(la) + 4) + literal;
                        if ((target & 1u) == 0) break;       // ARM/data, not us
                        if (!in_code_section_t2(target & ~1u)) break;
                        if (seen_t2.insert(target).second)
                            picfn_thumb2_seeds.push_back(target);
                        break;
                    }
                }
            }
            std::fprintf(stderr,
                "[translator] thumb2 PIC-fnptr scan: %zu new seed(s)"
                " (lr=%d nopw=%d)\n",
                picfn_thumb2_seeds.size(), int(picfn_thumb2_lr_on),
                int(term_nopw_on));
        }
        // Gate OFF leaves this a plain copy of img.data_fnptr_seeds in the same
        // order, so the loop below sees an identical sequence.
        std::vector<uint32_t> data_fnptr_seeds_all = img.data_fnptr_seeds;
        data_fnptr_seeds_all.insert(data_fnptr_seeds_all.end(),
                                    picfn_thumb2_seeds.begin(),
                                    picfn_thumb2_seeds.end());
        // v1.0.1055 GATE P -- MACAPK_ARM32_FNPTR_POOL_PRED.
        //
        // Extends the v1.0.552 literal-pool relief from STORE-installed
        // PIC-fnptr seeds to the v1.0.806 BL-ARGUMENT ones. v1.0.552 observed
        // that a real function entry sitting immediately after a literal pool
        // is routine compiler output, and that the terminator-precedence
        // walk-back rejects it whenever the instruction BEFORE the pool is a
        // live BL rather than a ret/b/bx (the walk-back steps over pool-shaped
        // words but correctly refuses to step over a live call). It gave the
        // store-install provenance a bypass and deliberately left the BL-arg
        // provenance alone.
        //
        // Measured victim (HCR 1.68.1 libgame.so, cocos2d-x v2): the
        // SEL_SCHEDULE target 0x208090 -- an unmistakable `void update(float
        // dt)` (`vmul.f32 s0,s2,s0`, then ldrd/adds/adc/strd at this+0x18) --
        // is ALREADY seeded by the BL-arg idiom at the historical look-ahead,
        // and dies HERE: 0x20808c is a literal-pool word and 0x208088 is a
        // live `bl #0x60c170`. Its sibling 0x4694e8 needs gate W to be seeded
        // at all AND this relief to be admitted, so either gate alone leaves
        // the scheduler tick panic-stubbing.
        //
        // NOT A RENDERING FIX. HCR 1.68.1 compiles ZERO shaders and zero
        // programs (GL census in
        // docs/arm32-three-blanks-three-causes_2026-08-26.md), and a dead
        // `update(dt)` cannot prevent shader compilation, so this is almost
        // certainly not that app's blank screen. It lands on CORRECTNESS
        // grounds: the scheduler really does call these 465 times per run and
        // really does get 0 back, and the shape is generic to any cocos2d-x v2
        // title that schedules a selector.
        //
        // WHY THIS SHAPE AND NOT A BYPASS. Handing BL-arg seeds the same
        // blanket bypass the linker-backed provenances get would admit 654
        // seeds fleet-wide (484 of them in GD Lite alone) -- unacceptable for
        // a byte-pattern guess, and it would also hand them the in-body
        // override further down, which is precisely the v1.0.806 0xe07d4
        // failure. Requiring the PROVABLE pool word immediately before the
        // entry narrows that to 195, and additionally requiring the entry to
        // open with an ARM `push {.., lr}` narrows it to 136. Measured with
        // debug/arm32_discovery_blast.py gateP over all 17 armeabi-v7a .so on
        // this box; per binary: HCR 1.68.1 126, GD Lite 4, HCR 1.8.1 1, the
        // rest 5.
        //
        // The prologue requirement is UNCONDITIONAL, not a sub-gate. HCR 1.8.1
        // is the only large ARM32 app that renders, and this relief is the one
        // of the two gates that touches it at all; the narrowing takes it from
        // 2 admitted seeds to 1, and takes GD Lite from 36 to 4. A sub-gate
        // would mean the wide variant is reachable by env on a binary we
        // cannot afford to destabilise, for no measured benefit -- neither
        // target needs it (both are 0xe92d4c10, `push {r4,r10,r11,lr}`). If a
        // future victim turns out to be a push-less leaf or an empty virtual
        // (the shapes v1.0.808 documents), widen it THEN, with that victim
        // named here.
        //
        // Default OFF, per the discovery-regression history noted above.
        static const bool fnptr_pool_pred_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_FNPTR_POOL_PRED");
            return e && *e && *e != '0';
        }();
        // Seeds admitted ONLY because of gate P, for the log line below.
        size_t pool_pred_admitted = 0;
        // MACAPK_ARM32_SEED_EXIDX_CONFIRM (default ON; =0 restores the
        // pre-change admission exactly). A NON-authoritative heuristic seed --
        // the Thumb-16 PIC scan, the Thumb-2 PIC scan, the ARM BL-argument
        // idiom -- whose address .ARM.exidx names as a function start skips
        // the terminator-precedence walk-back, exactly as an authoritative
        // seed that exidx confirms already does (bypass_terminator_check
        // below, v1.0.806). The walk-back answers "is this address reached
        // other than by fall-through?" from byte PATTERNS; the index table
        // answers it from the linker. EHABI (ARM IHI 0038, "Index table
        // entries"): "An index table entry consists of 2 words. The first word
        // contains a prel31 offset ... to the start of a function". lld writes
        // one entry per executable input section (a linker CANTUNWIND entry "at
        // the start of the InputSection" when it has no unwind info), so an
        // exidx address is a code START by construction, never mid-body; the
        // table is only INCOMPLETE (lld --merge-exidx-entries drops an entry
        // whose inline unwind words repeat its predecessor's), so a seed exidx
        // does not name keeps the old walk-back.
        //
        // Nothing is seeded FROM exidx (that broad net stays opt-in,
        // MACAPK_EXIDX_DISCOVERY): the pointer was already computed by code in
        // the image, exidx only confirms where it lands. And exidx starts are
        // already function BOUNDARIES (anticipated_starts below), so admitting
        // one cannot move any other function's extent.
        //
        // Measured victim (OpenTTD 14.1 armeabi-v7a liblzma.so): the stream
        // decoder's `code` callback at 0x119b4 (`push {r4-r7,lr}`), installed
        // by lzma_stream_decoder_init via `ldr r3,[pc]` @0x118fe + `add r3,pc`
        // @0x11912 and called through `blx lr` @0xe2c8. It sits behind nine
        // literal-pool words (a 16-byte NEON constant loaded by adr+vld1, then
        // five LDR literals) and a nop before its predecessor's `pop {..,pc}`;
        // the walk-back budget is 8 steps, so the seed was rejected, the
        // callback was never translated, and every .xz decode returned garbage
        // ("Error downloading data from openttd-data-14.1-0.zip.xz"). All 18
        // walk-back rejects in that library are exidx starts; corpus-wide
        // (246 ARM32 libs) debug/arm32_discovery_blast.py gateX counts 11,965
        // of 15,421 rejected heuristic seeds. docs/arm32-exidx-confirmed-seeds_2026-09-28.md.
        static const bool seed_exidx_confirm_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_SEED_EXIDX_CONFIRM");
            return !(e && *e == '0');
        }();
        size_t exidx_confirm_admitted = 0;
        // ARM `push {..., lr}` == STMFD sp!,{..,lr}. Mask/value pin P=1, U=0,
        // W=1, L=0, Rn=SP and bit14=LR while leaving the condition field and
        // the rest of the register list free -- ARM ARM DDI 0406C.b A8.8.199
        // (STMDB/STMFD, "cond 100 1 0 0 1 0 Rn(1101) register_list") and
        // A8.8.133 (PUSH is the Rn=SP,W=1 form). Deliberately NOT
        // looks_like_fn_prologue: that helper is documented in this same
        // function as "too lax" and is what the v1.0.806 mid-instruction veto
        // exists to contain.
        auto opens_with_arm_push_lr = [&](uint32_t va) -> bool {
            auto w = img.code_at(va, 4);
            if (w.size() != 4) return false;
            uint32_t insn = uint32_t(w[0])
                          | (uint32_t(w[1]) << 8)
                          | (uint32_t(w[2]) << 16)
                          | (uint32_t(w[3]) << 24);
            return (insn & 0x0FFFC000u) == 0x092D4000u;
        };
        std::vector<std::pair<uint32_t,bool>> data_seed_to_lift;
        for (uint32_t v : data_fnptr_seeds_all) {
            uint32_t v_clean = v & ~1u;
            bool     thumb   = (v & 1u) != 0;
            bool     is_authoritative = authoritative_set.count(v) != 0;
            // v1.0.806: is this candidate's ONLY authoritative backing the
            // heuristic ARM-mode PIC scan (as opposed to a genuinely
            // linker-verified .ARM.exidx entry or R_ARM_RELATIVE
            // relocation)? See heuristic_pic_set's declaration above.
            bool     is_heuristic_pic_only = heuristic_pic_set.count(v) != 0;
            if (known_starts.count(v_clean)) continue;
            if (plt_addrs.count(v_clean))    continue;
            // Drop seeds that land in the body of a symboled function
            // -- a real callback pointer points at function START. A
            // relocation/PC-rel that resolves to a mid-function offset
            // is either a basic-block-address-taken construct (not a
            // call target) or a heuristic false-positive from
            // elf_reader's PC-rel scan, and lifting it as a synth
            // produces broken IR that may corrupt memory when invoked
            // through __macapk_target_<addr>.
            if (inside_known_body(v_clean)) continue;
            // v1.0.57: drop seeds whose target isn't preceded by a
            // function terminator in the seed's claimed mode.
            // elf_reader's R_ARM_RELATIVE / PC-rel scan can pick up
            // basic-block address-taken constructs (switch table
            // entries), C++ exception-handling LSDA entries, and raw
            // literal-pool words that coincidentally point into
            // .text. None of those are real function pointers; lifting
            // them creates bogus synths that pollute known_starts and
            // cap legitimate sibling functions via upper_bound (HCR
            // 1.43: data_fnptr_seed at 0x202f20 = mid CCNode-ctor at
            // 0x202ea0; the bogus synth gets lifted FIRST, then
            // CCNode's body caps at 0x202f20 instead of its real
            // 0x202fc0 end, dropping the m_pActionManager initializer
            // at 0x202f9c). Conservative filter -- any plausible
            // terminator (ret, b, bx) in ARM or Thumb works.
            // v1.0.59: PROVABLY-data check first. If the candidate
            // lives in a literal pool word that some LDR-PC-relative
            // load dereferences, it cannot be a function entry.
            // v1.0.552: authoritative ARM-mode seeds check only the ARM
            // pool set -- the blind Thumb-alias scan false-marks ARM code
            // (see is_in_arm_literal_pool). Non-authoritative and Thumb
            // seeds keep the conservative both-sets check.
            if (is_authoritative && !thumb
                    ? is_in_arm_literal_pool(v_clean)
                    : is_in_literal_pool(v_clean)) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] data_fnptr seed reject 0x%x "
                        "(thumb=%d): in literal pool\n",
                        v_clean, int(thumb));
                }
                continue;
            }
            // v1.0.59: terminator-precedence check is REQUIRED for
            // data_fnptr seeds. The elf_reader's R_ARM_RELATIVE +
            // PC-rel-load scan picks up many false-positive "function
            // pointers" (basic-block addresses in switch tables,
            // exception-handler LSDA entries, etc.). Without the
            // terminator filter, v1.0.59f admitted 5012 init/data
            // seeds (vs 3018 with filter) and one of the extras
            // produced a broken synth body that std::string operator+
            // hot-loops over, generating 400k+ indirect-call panic
            // stubs per second.
            //
            // We tried adding a "look-like-prologue" OR-carve-out to
            // recover real entries like HCR's 0x76268 (preceded by
            // literal pool, starts with `push {r4-r11, lr}`), but
            // looks_like_fn_prologue is too lax — random literal pool
            // values like 0xe92dXXXX byte-match the ARM push pattern,
            // so the carve-out re-admitted bogus seeds and the std::
            // string hot-loop returned. Need a stronger heuristic.
            //
            // v1.0.63: authoritative R_ARM_RELATIVE seeds bypass the
            // terminator filter -- the linker emitted them precisely
            // because the source said "take the address of this
            // function," so they ARE real entries by construction
            // even when their predecessor doesn't byte-match a
            // recognized terminator pattern (long-call veneer, throw
            // helper with unusual signature, etc.). The literal-pool
            // check above still applies -- a relocation pointing into
            // a literal-pool word can't be a function entry no matter
            // who emitted it.
            //
            // v1.0.806: the bypass does NOT extend to is_heuristic_pic_only
            // seeds -- unlike exidx/R_ARM_RELATIVE, that provenance has no
            // linker verification behind it at all (see heuristic_pic_set's
            // declaration above). This check is still the RIGHT gate for
            // it: is_preceded_by_terminator_in_context walks back through
            // NOPs and literal-pool-shaped words looking for a genuine
            // terminator (ret/pop-pc/bx/b), which is exactly "is this
            // address reached some way other than ordinary fall-through
            // from a live, still-executing instruction" -- provable from
            // the actual decoded bytes, not a fuzzy guess. Two confirmed
            // outcomes on the real fleet (2026-07-28):
            //   - HCR 1.8.1 libgame.so 0xe07d4 (the bug this fixes): the
            //     immediately preceding instruction at 0xe07d0 is
            //     `bl CCPoint::CCPoint(float,float)` -- a live call, not a
            //     terminator and not pool-shaped, so the walk-back correctly
            //     refuses to step past it and returns false -- REJECTED
            //     here, before it can ever poison anticipated_starts (built
            //     just below) and silently cap 0xe0358's real extent.
            //   - ShatteredPD's libgdx-freetype.so ft_alloc (0x36a14, the
            //     v1.0.552 motivating case for the bypass) is preceded by
            //     THREE literal-pool words (0x36a08/0c/10, all high-nibble
            //     0x0 -- the pool-shaped pattern the walk-back already
            //     recognizes) which are themselves preceded by a genuine
            //     `pop {fp,pc}` at 0x36a04 -- the walk-back finds it and
            //     returns true -- STILL ADMITTED. (ft_realloc/ft_free at
            //     0x36a1c/0x36a28 don't even need the walk-back: each is
            //     preceded by the previous helper's own tail-call `b`, an
            //     immediate terminator.) Confirmed by direct disassembly,
            //     not by re-trusting old comments -- see
            //     docs/arm32-discovery-mid-instruction-fnptr-seed_2026-07-28.md.
            //
            // CAUGHT BY MY OWN VERIFICATION, not a hypothetical: a first
            // version of this gate checked ONLY is_heuristic_pic_only and
            // wrongly rejected HCR 1.8.1 libgame.so 0x18612c -- a genuine
            // 3-insn leaf (`ldrh r0,[r1,#0xc]; ubfx r0,r0,#6,#1; bx lr`)
            // that happens to be BOTH a heuristic-PIC-scan seed AND named by
            // .ARM.exidx (confirmed by direct exidx-table decode: entry
            // present, `in exidx = True`). exidx is independently
            // linker-verified regardless of which OTHER seed mechanism also
            // happened to find the same address, so it must keep bypassing
            // this check exactly like a real R_ARM_RELATIVE seed does --
            // only a PURE heuristic-PIC-scan hit, with no exidx or
            // relocation backing at all, loses the bypass.
            bool exidx_confirmed = img.exidx_fn_starts.count(v_clean) != 0;
            bool bypass_terminator_check =
                is_authoritative && (!is_heuristic_pic_only || exidx_confirmed);
            // MACAPK_ARM32_SEED_EXIDX_CONFIRM (see its declaration): the same
            // exidx confirmation for the NON-authoritative scans. Counted only
            // when the walk-back would have rejected the seed, so the log line
            // is exactly the set this gate adds.
            if (seed_exidx_confirm_on && !bypass_terminator_check
                && !is_authoritative && exidx_confirmed
                && !is_preceded_by_terminator_in_context(v_clean, thumb, true)) {
                bypass_terminator_check = true;
                ++exidx_confirm_admitted;
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] data_fnptr seed admit 0x%x (thumb=%d): "
                        ".ARM.exidx function start "
                        "(MACAPK_ARM32_SEED_EXIDX_CONFIRM)\n",
                        v_clean, int(thumb));
                }
            }
            // v1.0.1053: pass the seed's OWN claimed mode. Only this call site
            // knows it (the seed carries bit[0]); every other caller keeps the
            // historical ARM-biased behaviour via the default argument.
            if (!bypass_terminator_check
                && !is_preceded_by_terminator_in_context(v_clean, thumb, true)) {
                // GATE P. A NON-authoritative, ARM-mode (bit[0] == 0)
                // data_fnptr seed can only have come from elf_reader's ARM
                // PIC scan via the BL-ARGUMENT install: the exidx and
                // R_ARM_RELATIVE producers push to authoritative_fnptr_seeds
                // as well, the store/stm install does too, and the Thumb-16
                // and Thumb-2 PIC scans both refuse a target whose bit[0] is
                // clear. So this pair of conditions IS the BL-arg provenance,
                // derived rather than plumbed -- no new field, and nothing
                // else can slip in through it.
                if (fnptr_pool_pred_on
                    && !is_authoritative && !thumb
                    && v_clean >= 4 && (v_clean & 3u) == 0
                    // PROVABLE pool word (our ARM LDR-PC scan dereferences
                    // it), not the "pool-shaped high nibble" heuristic the
                    // walk-back also accepts, and the ARM set only -- the
                    // Thumb set is a blind 2-byte scan that false-marks ARM
                    // code, which is the v1.0.552 lesson.
                    && literal_pool_bytes_arm.count(v_clean - 4)
                    && opens_with_arm_push_lr(v_clean)) {
                    ++pool_pred_admitted;
                    std::fprintf(stderr,
                        "[translator] data_fnptr seed admit 0x%x (thumb=0): "
                        "MACAPK_ARM32_FNPTR_POOL_PRED (BL-arg seed after a "
                        "literal pool, ARM push{..,lr} entry)\n", v_clean);
                    data_seed_to_lift.emplace_back(v_clean, false);
                    continue;
                }
                if (verbose || is_heuristic_pic_only) {
                    std::fprintf(stderr,
                        "[translator] data_fnptr seed reject 0x%x "
                        "(thumb=%d): not preceded by terminator%s\n",
                        v_clean, int(thumb),
                        is_heuristic_pic_only
                            ? " (heuristic PIC-scan seed, not exidx/reloc)"
                            : "");
                }
                continue;
            }
            if (is_authoritative && verbose) {
                std::fprintf(stderr,
                    "[translator] data_fnptr seed admit 0x%x "
                    "(thumb=%d): %s authoritative\n",
                    v_clean, int(thumb),
                    is_heuristic_pic_only
                        ? "PIC-scan (terminator-checked)"
                        : "R_ARM_RELATIVE/exidx");
            }
            data_seed_to_lift.emplace_back(v_clean, thumb);
        }
        if (seed_exidx_confirm_on) {
            std::fprintf(stderr,
                "[translator] exidx-confirmed fnptr seeds: %zu admitted past "
                "the terminator walk-back (MACAPK_ARM32_SEED_EXIDX_CONFIRM)\n",
                exidx_confirm_admitted);
        }
        // MACAPK_ARM32_TERM_NORETURN_PLT / MACAPK_ARM32_TERM_POOL_WALK are
        // self-describing: the count each rule admitted (a seed can count under
        // both). With both =0 the line is not printed, so that arm's stderr is
        // the old one.
        if (term_noreturn_plt_on || term_pool_walk_on) {
            std::fprintf(stderr,
                "[translator] data_fnptr walk-back: %zu seed(s) ended by a "
                "no-return PLT call (MACAPK_ARM32_TERM_NORETURN_PLT, %zu stub(s)), "
                "%zu past a >8-word provable literal pool "
                "(MACAPK_ARM32_TERM_POOL_WALK)\n",
                term_noreturn_plt_hits, noreturn_plt_stubs.size(),
                term_pool_walk_hits);
        }
        // Gate P is self-describing: emitted only when the gate is on, so the
        // default build's stderr is unchanged.
        if (fnptr_pool_pred_on) {
            std::fprintf(stderr,
                "[translator] MACAPK_ARM32_FNPTR_POOL_PRED=1: literal-pool-"
                "predecessor relief admitted %zu BL-arg seed(s)\n",
                pool_pred_admitted);
        }
        std::sort(data_seed_to_lift.begin(), data_seed_to_lift.end());
        data_seed_to_lift.erase(
            std::unique(data_seed_to_lift.begin(), data_seed_to_lift.end()),
            data_seed_to_lift.end());
        std::sort(init_seed_to_lift.begin(), init_seed_to_lift.end());
        init_seed_to_lift.erase(
            std::unique(init_seed_to_lift.begin(), init_seed_to_lift.end()),
            init_seed_to_lift.end());
        // v1.0.63: build a union set of "anticipated function starts"
        // that includes known_starts PLUS all pending init/data seeds.
        // The naive `next_real_function_start(known_starts, ...)` cap
        // computed in the init_seed loop below only sees seeds that
        // have ALREADY been processed earlier in the loop, so when
        // seed N is lifted with N+1, N+2... not yet inserted, the cap
        // reaches past those later seeds. For functions that share an
        // executable PT_LOAD segment with many siblings -- e.g. HCR
        // 1.43's std::__convert_to_v helpers in the 0x2dceXX-0x2dcfXX
        // range -- the over-extended size causes the lifter to
        // disassemble through the sibling's body, swallow its bytes
        // (including its prologue + first BL), and emit IR that
        // recursively tail-calls the same synth (the BL at the
        // sibling's offset becomes `call __macapk_target_<seed_n>`
        // because that's the synth that absorbed it). Result:
        // infinite recursion at runtime -> stack overflow -> SIGILL
        // KERN_PROTECTION_FAILURE at the JVM stack guard page.
        //
        // Fix: also insert init/data seeds into the boundary set up
        // front. Use a SEPARATE set rather than mutating known_starts,
        // so a seed that later fails to lift (disasm error etc.)
        // doesn't leave a phantom boundary cap behind.
        std::set<uint32_t> anticipated_starts = known_starts;
        for (uint32_t v : init_seed_to_lift) anticipated_starts.insert(v);
        for (auto [v, t] : data_seed_to_lift) {
            (void)t;
            anticipated_starts.insert(v);
        }
        // ALSO every .ARM.exidx function start, as a BOUNDARY only.
        //
        // This is the same swallowing fix as the v1.0.63 prologue-scan note
        // below, but sourced from the linker instead of a byte pattern, so it
        // also covers functions no pattern can recognise -- above all LEAVES,
        // which have no push prologue at all.
        //
        // Boundary, NOT a seed: nothing extra gets lifted, so the IR-bloat
        // reason .ARM.exidx SEEDING is gated (MACAPK_EXIDX_DISCOVERY, roughly
        // 2x lifted IR on big engines) does not apply here. All this does is
        // stop a body at an address the linker already said a function starts
        // at.
        //
        // HCR 1.43: 0x204704 is a 5-instruction ARM leaf (ldr/ldr/str/str/
        // bx lr) that got absorbed into its neighbour's extent, so
        // `bl 0x204704` had no entry point to reach and was panic-stubbed to
        // 0. It was the ONLY unresolved call that actually executed, and it
        // killed the cocos2d-x SAX parser's init chain -- nativeInit never
        // completed and the app rendered nothing.
        for (uint32_t v : img.exidx_fn_starts) anticipated_starts.insert(v);
        // v1.0.63: ALSO add every byte-pattern-detectable function
        // prologue to anticipated_starts so the size cap correctly
        // stops at sibling functions discovered later by the
        // dedicated prologue-scan pass (which runs AFTER BL/synth
        // rounds). Without this, BL/synth-lifted functions can
        // swallow downstream prologue-detected siblings. The
        // motivating case: HCR 1.43's synth_002dced0 (a
        // libstdc++ `std::has_facet`-style template helper at
        // 0x2dced0) absorbs all the way through 0x2dd21c because
        // it doesn't see the sibling at 0x2dcf18 as a boundary
        // -- 0x2dcf18 is discovered only by the late prologue
        // scan, after synth_002dced0 has been committed. When
        // synth_002dced0 then encounters `bl 0x2dced0` at offset
        // 0x2dcf2a (inside what should have been the sibling at
        // 0x2dcf18), it emits `call __macapk_target_002dced0`
        // = recursive self-call. Result: infinite recursion at
        // runtime -> stack overflow -> SIGILL.
        //
        // The scan here is byte-pattern-only (no terminator filter,
        // no literal-pool filter) -- this is fine for CAPPING
        // purposes because:
        //   - A false-positive boundary just truncates the parent
        //     function slightly early (the truncated tail bytes
        //     are usually a cold throw/abort path); much less bad
        //     than the alternative of swallowing a sibling.
        //   - The actual prologue-lift pass at the end of the
        //     pipeline still applies the terminator + literal-pool
        //     filters, so false-positive prologues never become
        //     lifted synths (just boundary entries).
        //
        // Patterns recognized (the common cases that cover ~95%
        // of compiler-emitted prologues in stripped Android NDK
        // .so files):
        //
        //   ARM32 (4-byte aligned):
        //     0xe92dXXXX  push  {regs}     (any register set)
        //     0xe52d4004  push  {lr}      (Thumb-to-ARM trampoline tail)
        //     0xe24dd0XX  sub   sp, #imm  (no push, stack-only frame)
        //
        //   Thumb16 (2-byte aligned):
        //     0xb5XX      push  {regs, lr}    (Thumb16, common leaf prologue)
        //     0xb4XX      push  {regs}        (Thumb16, no-link push)
        //     0xb0XX      sub   sp, #imm
        //
        //   Thumb-2 (32-bit, halfword pairs):
        //     0xe92d XXXX push.w  {regs}
        //     0xe92d 4XXX push.w  {... lr}
        //     0xb500-0xb5ff also caught by Thumb16
        //
        // v1.0.64 second pass: a byte-pattern match alone is NOT
        // sufficient -- intra-function `push {r4, lr}` to save
        // callee-saved regs around a callee appears OFTEN in the
        // middle of functions. We also require the new boundary's
        // immediate predecessor to be either a function terminator
        // (ret, b, bx, no-return BL) OR literal-pool data (i.e.
        // the same gate the later prologue-scan applies before
        // actually lifting). Without this check, my prologue scan
        // capped HCR 1.43's `synth_00221e04` (CCApplication::run)
        // to just 2 instructions because the function's natural
        // `push {r4, lr}` at offset +8 matched the pattern, and
        // the vtable[2] -> applicationDidFinishLaunching dispatch
        // got truncated away -> AppDelegate never ran -> scene
        // never created -> engine never issued GL traffic -> pure
        // black frames at 60 fps.
        // v1.0.553: the executable PT_LOAD also contains .rodata, whose bytes
        // freely alias push/sub-sp prologue patterns. A .rodata "prologue" at
        // 0x133aa (ShatteredPD libgdx-freetype.so) became a bogus Thumb synth
        // whose garbage decode recorded a phantom `b 0x93d10` extern target ->
        // discovery treated 0x93d10 as a function start -> load_truetype_glyph
        // TRUNCATED to 8 insns -> FreeType glyph loads returned stack garbage
        // -> no text. Restrict the prologue scan to real CODE sections
        // (SHF_ALLOC|SHF_EXECINSTR); fall back to the whole exec segment when
        // the section table is stripped.
        const bool have_code_sections_ps = [&]() {
            for (const auto& sh : img.shdrs)
                if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u) && sh.sh_size > 0)
                    return true;
            return false;
        }();
        auto in_code_section = [&](uint32_t va) -> bool {
            if (!have_code_sections_ps) return true;   // stripped: keep old reach
            for (const auto& sh : img.shdrs) {
                if (!(sh.sh_flags & 0x4u)) continue;   // SHF_EXECINSTR
                if (!(sh.sh_flags & 0x2u)) continue;   // SHF_ALLOC
                if (va < sh.sh_addr) continue;
                if (va >= sh.sh_addr + sh.sh_size) continue;
                return true;
            }
            return false;
        };
        // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT: see its use below.
        auto pro_scan_arm_context_on = []() -> bool {
            static const bool on = [] {
                const char* e = std::getenv("MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT");
                return !(e && *e == '0');
            }();
            return on;
        };
        size_t pro_scan_arm_context_rejects = 0;
        // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT=blind: a labelled POSITIVE-CONTROL arm,
        // not a mode to ship. It reproduces the first version of this rule, whose
        // A32 B/BL decode ignored every independent Thumb evidence (exidx, the
        // dynsym Thumb ranges) and so dropped real Thumb starts (GD Lite v7a
        // 0x5fad20 / 0x6129b8 / 0x62934c): the gate's L1 row must FAIL under it.
        static const bool pro_scan_blind = [] {
            const char* e = std::getenv("MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT");
            return e && std::strcmp(e, "blind") == 0;
        }();
        // Targets of every A32 B<cond> / BL (encoding A1: cond != 1111, bits
        // 27-25 = 101) in the code sections. ARM ARM DDI 0406C.b A8.8.18 (B)
        // and A8.8.25 (BL, BLX (immediate)): for encoding A1 the target
        // instruction set is ARM -- only BLX (immediate), cond == 1111, switches
        // to Thumb. So an address an A32 B/BL lands on is ARM code, and a
        // Thumb16 `push`/`sub sp` reading of its LOW halfword is a coincidence.
        std::unordered_set<uint32_t> arm_b_targets;
        if (pro_scan_arm_context_on()) {
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                const auto& bs = seg.bytes;
                for (size_t o = 0; o + 4 <= bs.size(); o += 4) {
                    const uint32_t va = seg.vaddr + uint32_t(o);
                    if (!in_code_section(va)) continue;
                    const uint32_t w = uint32_t(bs[o]) | (uint32_t(bs[o+1]) << 8)
                                     | (uint32_t(bs[o+2]) << 16)
                                     | (uint32_t(bs[o+3]) << 24);
                    if ((w >> 28) == 0xfu) continue;
                    if ((w & 0x0e000000u) != 0x0a000000u) continue;
                    // A word the symbol table places in THUMB code is two
                    // Thumb halfwords, not an A32 instruction: decoding it as
                    // a B/BL would mint a bogus "ARM-mode target" (measured:
                    // a blind A32 decode lands on 16.3% of Thumb-heavy GD Lite
                    // v7a's true 4-aligned function starts). lookup_is_thumb
                    // answers from dynsym ranges (and same-mode bracketed
                    // gaps), independent of discovery.
                    if (!pro_scan_blind)
                        if (auto isa = lookup_is_thumb(va); isa && *isa) continue;
                    int32_t imm24 = int32_t(w & 0x00ffffffu);
                    if (imm24 & 0x00800000) imm24 |= int32_t(0xff000000u);
                    arm_b_targets.insert(va + 8 + uint32_t(imm24 << 2));
                }
            }
        }
        // ...and a second, independent piece of ARM-context evidence for a
        // candidate nothing branches to: an A32 LDR (literal) BEFORE it whose
        // literal lies AFTER it (ARM ARM DDI 0406C.b A8.8.64, encoding A1,
        // U == 1). A compiler places a function's literal pool inside that
        // function, so [ldr, literal] is one function and the candidate is
        // interior to it. Only asked of a 4-aligned candidate whose Thumb
        // reading comes from a word that is ALSO an AL ARM instruction (top
        // nibble 0xe), i.e. exactly the coincidence shape.
        auto arm_literal_spans = [&](uint32_t va) -> bool {
            if ((va & 3u) != 0) return false;
            auto cw = img.code_at(va, 4);
            if (cw.size() < 4 || (cw[3] >> 4) != 0xe) return false;
            // Reach: an `ldr Rt,[pc,#+imm12]` addresses at most 4095+8 bytes
            // ahead (A8.8.64), so every literal that can lie after `va` is
            // loaded by one of the 1024 words before it. (It used to look back
            // 64 words and so missed Jetpack Joyride's 0x11a9e8, 0x238 bytes
            // after the `ldr r1,[pc,#0x264]` whose pool word is at 0x11aa1c.)
            for (uint32_t k = 1; k <= 1024 && va >= 4 * k; ++k) {
                const uint32_t a = va - 4 * k;
                auto b = img.code_at(a, 4);
                if (b.size() < 4) break;
                const uint32_t w = uint32_t(b[0]) | (uint32_t(b[1]) << 8)
                                 | (uint32_t(b[2]) << 16) | (uint32_t(b[3]) << 24);
                // ldr Rt, [pc, #+imm12] (P=1 U=1 B=0 W=0 L=1 Rn=pc), cond AL
                if ((w & 0xffff0000u) == 0xe59f0000u) {
                    const uint32_t lit = a + 8 + (w & 0xfffu);
                    if (lit > va) return true;
                }
            }
            return false;
        };
        // The one predicate both prologue passes use (MACAPK_ARM32_PRO_SCAN_ARM_
        // CONTEXT): is this 4-aligned Thumb-reading candidate provably ARM code?
        // EXEMPT, because each is independent evidence it IS a real Thumb entry:
        //  * .ARM.exidx names it a function start (the linker's own table). A
        //    blind A32 decode of Thumb-2 halfword pairs does hit real Thumb
        //    starts: GD Lite v7a lost 0x5fad20 / 0x6129b8 / 0x62934c (all
        //    exidx-confirmed, all `push`) to a coincidental "B" word before the
        //    exemption existed (review 2026-09-30).
        //  * the symbol table places it in Thumb code (lookup_is_thumb).
        //
        // Third evidence, for candidates no B/BL lands on and no pool spans
        // (the function body is long and its literals are not within an `ldr`'s
        // 4 KB reach): the neighbourhood DECODES AS ARM. Compilers emit
        // condition-AL (0xe) for the large majority of A32 instructions, while
        // the 4-aligned WORDS of Thumb-2 code read as cond 0xe only when their
        // second halfword is a 16-bit `b` or a 32-bit load/store-multiple
        // prefix. Measured 2026-09-30 over the 16 words at va-32 .. va+28:
        //   true ARM function starts  (Joyride dynsym, 796 4-aligned):  >= 12 of
        //       16 AL-cond words for 662 (83%), >= 9 for 785; min 4
        //   true Thumb function starts (GD Lite v7a dynsym, 12778 4-aligned):
        //       >= 12 of 16 for 4 (0.03%), >= 10 for 22 (0.17%), median 0-1
        // and an INTERIOR address of an ARM function sees the full-code density
        // (no pool word before it), which is higher than a start's. So >= 12 of
        // 16 is a near-perfect ARM-vs-Thumb separator. Victim: Joyride 0xce9e4
        // (`str fp,[sp,#0x4b4]` = 0xe58db4b4, low half 0xb4b4 = Thumb push) in a
        // 20 KB function whose pools are out of reach, after `mov r1,r7` whose
        // high half 0xe1a0 reads as a Thumb `b` -- the terminator check passed,
        // a false start capped the function, and its branches minted 0xcde70 /
        // 0xcdeec / 0xce100 / ... as further starts.
        auto arm_dense_neighbourhood = [&](uint32_t va) -> bool {
            if (va < 32) return false;
            int al = 0, seen = 0;
            for (int k = -8; k < 8; ++k) {
                auto b = img.code_at(va + uint32_t(int32_t(k) * 4), 4);
                if (b.size() < 4) continue;
                ++seen;
                if ((b[3] >> 4) == 0xe) ++al;
            }
            return seen == 16 && al >= 12;
        };
        // Does real ARM code start at `va`? >= 10 of the 16 words FROM va are
        // condition-AL (0xe). Measured 2026-09-30 (the code after an entry, no pool
        // before it): true ARM function entries (Joyride dynsym, 796 4-aligned) 779
        // (97.9%) reach >= 10; true Thumb-2 entries (GD Lite v7a dynsym, 12778
        // 4-aligned) 43 (0.34%). MACAPK_ARM32_REACH_EXTENT acts ONLY where this holds:
        // Thumb-2 libraries (Unity il2cpp / libunity, 100k-400k functions with a
        // mode-classification cascade) have no ARM-dense entries, so their discovery is
        // the old one byte for byte -- the first version of the walk + fixpoint turned
        // Temple Run 2's libil2cpp from 170k into 283k starts (113k impossible ARM-mode
        // starts) by following mode-confused bl targets for 64 rounds.
        auto arm_code_at = [&](uint32_t va) -> bool {
            if ((va & 3u) != 0) return false;
            int al = 0;
            for (int k = 0; k < 16; ++k) {
                auto b = img.code_at(va + uint32_t(k) * 4u, 4);
                if (b.size() < 4) return false;
                if ((b[3] >> 4) == 0xe) ++al;
            }
            return al >= 10;
        };
        auto arm_context_rejects = [&](uint32_t va) -> bool {
            if (!pro_scan_arm_context_on() || (va & 3u) != 0) return false;
            if (pro_scan_blind)
                return arm_b_targets.count(va) || arm_literal_spans(va);
            if (img.exidx_fn_starts.count(va)) return false;
            if (auto isa = lookup_is_thumb(va); isa && *isa) return false;
            return arm_b_targets.count(va) || arm_literal_spans(va)
                || arm_dense_neighbourhood(va);
        };

        // MACAPK_ARM32_REACH_EXTENT (default ON; =0 is the old extent byte for
        // byte): how far does an ARM function ACTUALLY reach?
        //
        // Discovery sizes a synthetic function as "the distance to the next
        // address some other mechanism already believes is a start" (clamped to
        // 0x4000). When the real successor is not known YET -- it is reached only
        // by `bl` from code discovery has not lifted -- the function is lifted
        // far PAST its own end, through the next function's body, and every
        // branch in that foreign code whose target lies beyond the over-long
        // extent is recorded as an "external target" and minted as a function
        // start in the MIDDLE of a real function. That is the false-split cascade
        // (Jetpack Joyride: 0xcabe0 is a 0xe4-byte function; lifted as 0x4000
        // bytes it recorded 0xcebe8 / 0xcf0c0, which capped the real 14 KB
        // function 0xcd9e0, whose own join blocks then became starts 0xcda94 /
        // 0xcdc98 / 0xcde70 / 0xce100 ..., each a tail call that restores r4-r11
        // and SP to their ENTRY values -- the NULL object reads and the SEGV in
        // synth_000ce100 that shut the game down).
        //
        // So bound the lift by REACHABILITY: walk the A32 control flow from the
        // entry -- fall through, conditional branches, intra-extent `b`
        // targets, stop at returns (`pop {..,pc}`, `ldm ..,{..,pc}`, `bx lr`,
        // `mov pc,lr`, `ldr pc,[sp],#4`), at unconditional `b`, at a `bl` to a
        // no-return import (__stack_chk_fail, abort, __cxa_throw, ...; bionic
        // headers quoted at the seed walk-back above), and at an address the LDR-PC
        // scan proves is literal-pool data -- and extend the extent over every
        // literal an `ldr Rt,[pc,#imm]` / `vldr [pc,#imm]` reaches (ARM ARM
        // DDI 0406C.b A8.8.64 LDR (literal): the pool lies inside its function).
        // The extent is the highest address reached, rounded up to a word. ANY
        // control flow the walk cannot follow -- an indirect jump that is not a
        // call (`ldr pc,[rX,..]`, `ldr pc,[pc,rN,lsl #2]`, `add pc,pc,rN`, `bx rX`,
        // `mov pc,rX` with no `mov lr,pc` before it: a switch table) or a path
        // that falls off the old extent -- returns the OLD size unchanged, so
        // the walk can only ever SHRINK an extent it fully understands -- with two
        // caveats the review of 2026-10-01 added: (1) a proven extent may also GROW
        // past the 0x4000 clamp (up to 0x8000), and (2) "fully understands" excludes
        // what only the UNWINDER enters: C++ catch / cleanup landing pads are reached
        // through the personality routine, never by a branch, so the walk cannot see
        // them. For a function with .ARM.extab data the trim is therefore floored at
        // the function's own .ARM.exidx range (the EH-aware floor below). A walk that
        // reaches an instruction straddling the end of its extent (an extent bounded
        // by a 2-mod-4 start) bails and keeps the old extent.
        // A call is `mov lr,pc` immediately before the PC write (ARM ARM A8.8.103
        // MOV: lr = address of the instruction after the PC write).
        static const bool reach_extent_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_REACH_EXTENT");
            return !(e && *e == '0');
        }();
        size_t reach_trim_fns = 0, reach_trim_bytes = 0, reach_bail_fns = 0, reach_ext_fns = 0;
        size_t reach_inside_extab = 0, reach_eh_floor = 0;
        // DIAGNOSTICS for debug/arm32falsesplit_gate.sh's positive controls (not shipping
        // toggles; both default to the reviewed behaviour, REACH_EXTENT=0 stays the old path):
        //   MACAPK_ARM32_REACH_EH_FLOOR=0   drop the .ARM.exidx EH-aware floor (hcr181 v7a
        //                                   then loses 0x24aa80 / 0x24d8b0 again)
        //   MACAPK_ARM32_REACH_STRADDLE=1   the pre-review guard `pc >= cap`: a straddling
        //                                   instruction is READ instead of bailing (Joyride
        //                                   0x464e30 grows 0x3e -> 0x40). `seen` gets one spare
        //                                   slot so this arm is not itself out of bounds.
        static const bool reach_eh_floor_on = [](){
            const char* e = std::getenv("MACAPK_ARM32_REACH_EH_FLOOR");
            return !(e && *e == '0');
        }();
        static const bool reach_straddle_diag = [](){
            const char* e = std::getenv("MACAPK_ARM32_REACH_STRADDLE");
            return e && *e == '1';
        }();
        auto arm_reach_trim = [&](uint32_t start, uint32_t size, uint32_t size_raw,
                                  bool is_thumb) -> uint32_t {
            if (!reach_extent_on || is_thumb || size < 8 || (start & 3u) != 0
                    || !arm_code_at(start))
                return size;
            // `size` is the old 0x4000-clamped extent; `size_raw` the distance
            // to the next known start. The clamp is a guess ("no end found:
            // probably data as code"); a function whose reachability is FULLY
            // proven needs no guess. Joyride 0xcd9e0 is a real 17.6 KB function
            // (its `pop {..,pc}` at 0xd14e4 is an EARLY return): clamped at 0x4000
            // its tail's branches and literals left the extent, minted starts
            // 0xd14e8 ... 0xd1dc4 inside it, and the virtual calls through them
            // read a NULL object. So walk up to 32 KB when the clamp applied;
            // if the walk completes inside that, the proven extent is used as is.
            // The ceiling is 32 KB: a conservative bound (ARM-mode instructions are
            // 4 bytes, so 0x8000 bytes is 8192 decoded entries = the lifter decode
            // cache's kDcMaxRequest in arm32_lifter.cpp, which handles a bigger
            // request by simply not caching it). It is NOT there to dodge a decode-
            // cache bug: the translator abort the first version of this walk (128 KB
            // ceiling) showed in dc_make_room -> cs_free ("pointer being freed was
            // not allocated") on Crossy Road's libunity / libil2cpp and Duolingo's
            // libunity was this walk's own heap overflow -- `seen[]` indexed one past
            // its end, see the straddle guard below (review 2026-10-01; ASan on
            // Joyride, and an instrumented Telegram build aborted in the same place).
            // With that fixed the same three libraries complete at a 0x20000 ceiling.
            // EH-aware floor (review 2026-10-01). The walk cannot see C++ landing
            // pads: catch / cleanup blocks are entered only by the unwinder
            // (personality routine -> _Unwind_SetIP), never by a branch, so a
            // function with an LSDA was trimmed to its last normal return and
            // its landing pads (and every callee reached only from them) fell
            // out of discovery -- hcr181 v7a lost 0x24aa80 / 0x24d8b0 (called
            // only from basic_filebuf catch handlers). .ARM.exidx is the
            // linker's own bound: entries are "ordered by increasing function
            // start address" (IHI0038 ehabi32, Index table entries), and word 1
            // with bit 31 clear and != EXIDX_CANTUNWIND (1) is a prel31 offset
            // to an .ARM.extab entry, i.e. the function has generic/compact-model
            // unwind data with a personality routine + LSDA possible (the same
            // section: an inline entry, bit 31 set, is the 3-opcode compact model
            // with no handler data; only EXIDX_CANTUNWIND entries "may be grouped"
            // by a linker, note under "Index table entries", fetched 2026-10-01
            // from ARM-software/abi-aa ehabi32.rst). So: a start strictly
            // INSIDE such an entry is not a function entry and is not trimmed;
            // AT such an entry the trim never cuts below the entry's range. The
            // table holds "one entry for each non-leaf function", so the next entry
            // can lie beyond further leaf functions: the range is a conservative
            // FLOOR, capped by `size` (the distance to the next known start).
            uint32_t eh_floor = 0;
            if (reach_eh_floor_on && !img.exidx_entries.empty()) {
                auto it = img.exidx_entries.upper_bound(start);
                if (it != img.exidx_entries.begin()) {
                    auto pv = std::prev(it);
                    const bool extab = !(pv->second & 0x80000000u) && pv->second != 1u;
                    const uint32_t hi = (it != img.exidx_entries.end()) ? it->first : 0u;
                    if (extab && hi > start) {
                        if (pv->first != start) { ++reach_inside_extab; return size; }
                        eh_floor = std::min<uint32_t>(size, hi - start);
                    }
                }
            }
            const uint32_t walk_size = (size_raw > size)
                ? std::min<uint32_t>(size_raw, 0x8000u) : size;
            const uint32_t cap = start + walk_size;
            std::vector<uint8_t> seen(walk_size / 4 + (reach_straddle_diag ? 1u : 0u), 0);
            std::vector<uint32_t> work{start};
            uint32_t max_end = start + 4;
            bool bail = false;
            auto rd = [&](uint32_t a, uint32_t* w) -> bool {
                auto b = img.code_at(a, 4);
                if (b.size() < 4) return false;
                *w = uint32_t(b[0]) | (uint32_t(b[1]) << 8)
                   | (uint32_t(b[2]) << 16) | (uint32_t(b[3]) << 24);
                return true;
            };
            auto note_lit = [&](uint32_t lit, uint32_t bytes) {
                if (lit < start || lit + bytes > cap) { bail = true; return; }
                max_end = std::max(max_end, (lit + bytes + 3u) & ~3u);
            };
            while (!work.empty() && !bail) {
                uint32_t pc = work.back(); work.pop_back();
                uint32_t prev = 0;
                for (;;) {
                    if (pc < start) break;            // not ours (filtered on push)
                    // ran off the extent, or the instruction STRADDLES its end (an extent
                    // bounded by a 2-mod-4 start): `seen` holds walk_size / 4 entries, so
                    // `pc >= cap` alone indexed one past it (ASan heap-buffer-overflow).
                    if (reach_straddle_diag ? (pc >= cap) : (pc + 4u > cap)) { bail = true; break; }
                    uint8_t& sn = seen[(pc - start) / 4];
                    if (sn) break;
                    sn = 1;
                    if (pc != start && is_in_arm_literal_pool(pc)) break;  // data
                    uint32_t w;
                    if (!rd(pc, &w)) { bail = true; break; }
                    max_end = std::max(max_end, pc + 4);
                    const uint32_t cond = w >> 28;
                    const bool al = (cond == 0xe);
                    const bool after_mov_lr_pc = (prev == 0xe1a0e00fu);
                    bool terminate = false;
                    if (cond == 0xf) {
                        // BLX (imm) / PLD / unconditional space: falls through.
                    } else if ((w & 0x0e000000u) == 0x0a000000u) {      // B / BL
                        int32_t imm = int32_t(w & 0x00ffffffu);
                        if (imm & 0x00800000) imm |= int32_t(0xff000000u);
                        const uint32_t tgt = pc + 8 + uint32_t(imm << 2);
                        if (w & 0x01000000u) {                           // BL
                            if (al && all_noreturn_plt_stubs.count(tgt))
                                terminate = true;
                        } else {                                         // B
                            if (tgt >= start && tgt < cap) work.push_back(tgt);
                            if (al) terminate = true;
                        }
                    } else if ((w & 0x0ffffff0u) == 0x012fff30u) {       // BLX reg
                        // a call: falls through
                    } else if ((w & 0x0ffffff0u) == 0x012fff10u) {       // BX
                        if (after_mov_lr_pc) {
                            // a call: falls through
                        } else if ((w & 0xfu) == 14) {                   // bx lr
                            if (al) terminate = true;
                        } else { bail = true; break; }
                    } else if ((w & 0x0e108000u) == 0x08108000u) {       // LDM {..,pc}
                        if (al) terminate = true;
                    } else if ((w & 0x0c000000u) == 0x04000000u
                               && (w & 0x00100000u)
                               && ((w >> 12) & 0xfu) == 15u) {           // LDR pc,..
                        if ((w & 0x0fffffffu) == 0x049df004u) {          // ldr pc,[sp],#4
                            if (al) terminate = true;
                        } else if (after_mov_lr_pc) {
                            // a virtual call: falls through
                        } else { bail = true; break; }
                    } else if ((w & 0x0c000000u) == 0x00000000u
                               && ((w >> 12) & 0xfu) == 15u
                               && !((w & 0x80u) && (w & 0x10u))
                               && !((w & 0x01900000u) == 0x01100000u)) { // DP Rd=pc
                        if ((w & 0x0fffffffu) == 0x01a0f00eu) {          // mov pc,lr
                            if (al) terminate = true;
                        } else if (after_mov_lr_pc) {
                            // a call through a PC write: falls through
                        } else { bail = true; break; }
                    }
                    // pc-relative literals (LDR/LDRB literal, VLDR literal).
                    if (!bail && (w & 0x0e5f0000u) == 0x041f0000u
                            && (w & 0x01200000u) == 0x01000000u
                            && ((w >> 12) & 0xfu) != 15u) {              // ldr Rt,[pc,#±i]
                        const uint32_t i12 = w & 0xfffu;
                        const uint32_t lit = pc + 8 + ((w & 0x00800000u) ? i12 : (0u - i12));
                        note_lit(lit, (w & 0x00400000u) ? 1u : 4u);
                    } else if (!bail && (w & 0x0f3f0e00u) == 0x0d1f0a00u) { // vldr [pc,#±i]
                        const uint32_t i8 = (w & 0xffu) << 2;
                        const uint32_t lit = pc + 8 + ((w & 0x00800000u) ? i8 : (0u - i8));
                        note_lit(lit, (w & 0x100u) ? 8u : 4u);
                    }
                    if (bail || terminate) break;
                    prev = w;
                    pc += 4;
                }
            }
            if (bail) { ++reach_bail_fns; return size; }
            uint32_t trimmed = max_end - start;
            if (trimmed < eh_floor) { ++reach_eh_floor; trimmed = eh_floor; }
            if (trimmed == size) return size;
            if (trimmed > size) {            // proven larger than the 0x4000 clamp
                ++reach_ext_fns;
                return trimmed;
            }
            ++reach_trim_fns;
            reach_trim_bytes += size - trimmed;
            return trimmed;
        };
        for (const auto& seg : img.loadable) {
            if (!seg.executable) continue;
            const uint8_t* base = seg.bytes.data();
            uint32_t seg_start = seg.vaddr;
            for (size_t off = 0; off + 4 <= seg.bytes.size(); off += 2) {
                uint32_t va = seg_start + uint32_t(off);
                if (!in_code_section(va)) continue;   // v553: skip .rodata bytes
                // Already-known boundary; nothing to add.
                if (anticipated_starts.count(va)) continue;
                // PLT addresses get their own boundary via plt_addrs;
                // skip to avoid double-counting (and to avoid
                // misclassifying PLT stubs that share byte patterns
                // with prologues).
                if (plt_addrs.count(va)) continue;
                // Skip literal-pool words (LDR-PC scan authoritative
                // data-vs-code map).
                if (is_in_literal_pool(va)) continue;
                uint16_t hw0 = uint16_t(base[off])
                             | (uint16_t(base[off+1]) << 8);
                // Thumb16: push (b5XX) / push-no-lr (b4XX) /
                // sub sp (b08X-b0FX).
                bool is_thumb_pro =
                    (hw0 >= 0xb500 && hw0 <= 0xb5ff) ||
                    (hw0 >= 0xb400 && hw0 <= 0xb4ff) ||
                    (hw0 >= 0xb080 && hw0 <= 0xb0ff);
                // ARM32-only patterns (4-byte aligned). Check the
                // full 32-bit word in LE.
                bool is_arm_pro = false;
                if ((va & 3) == 0 && off + 4 <= seg.bytes.size()) {
                    uint32_t w = uint32_t(base[off])
                               | (uint32_t(base[off+1]) << 8)
                               | (uint32_t(base[off+2]) << 16)
                               | (uint32_t(base[off+3]) << 24);
                    // e92dXXXX push.w {regs}
                    bool push_w = (w & 0xffff0000u) == 0xe92d0000u;
                    // e52d4004 push {lr} (str lr, [sp, #-4]!)
                    bool push_lr_only = (w == 0xe52d4004u);
                    // e24ddXXX sub sp, sp, #imm
                    bool sub_sp = (w & 0xfffff000u) == 0xe24dd000u;
                    is_arm_pro = push_w || push_lr_only || sub_sp;
                }
                if (!(is_thumb_pro || is_arm_pro)) continue;
                // CRITICAL: require terminator-precedence. Without
                // this, intra-function pushes to save callee-saved
                // regs around an arg-passing call site get marked
                // as boundaries and cap the enclosing function in
                // half.
                //
                // We cannot use is_preceded_by_terminator_in_context
                // directly here: it tries BOTH ARM and Thumb modes,
                // and the Thumb check has a known false-positive
                // when the prior bytes are the high half of an ARM
                // LDR (`9f e5` = 0xe59f...) -- those bytes happen
                // to byte-match the Thumb16 `b imm11` encoding
                // (0xe000-0xe7ff). The known-good guard from the
                // codebase (commented around line 2546) is: when
                // the candidate is 4-byte-aligned AND the prior
                // 4 bytes form a valid-AL ARM instruction (high
                // nibble 0xe/0xf), trust ONLY the ARM-mode
                // terminator check. We replicate that guard here.
                //
                // HCR 1.43 motivating case: synth_00221e04
                // (CCApplication::run) starts at 0x221e04 with
                // `ldr r1, [pc, #0x34]; ldr r2, [pc, #0x34];
                // push {r4, lr}; ...`. The push at +8 has hi nibble
                // 0xe (push.w pattern). Without this guard, the
                // Thumb-mode coincidence accepted +8 as a real
                // boundary, capping the parent to 2 instructions
                // and erasing the entire applicationDidFinishLaunching
                // dispatch path -> AppDelegate vtable[2] never
                // invoked -> setOpenGLView/runWithScene never
                // called -> CCDirector::m_pobOpenGLView stays NULL
                // -> mainLoop short-circuits -> no GL traffic at
                // all -> pure black frames at 60 fps.
                // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT (see the comment in the
                // prior_is_arm_word branch below and at arm_b_targets).
                if (is_thumb_pro && !is_arm_pro && arm_context_rejects(va)) {
                    ++pro_scan_arm_context_rejects;
                    continue;
                }
                bool aligned4 = ((va & 3u) == 0);
                bool prior_is_arm_word = false;
                if (aligned4 && off >= 4) {
                    uint32_t prior = uint32_t(base[off-4])
                                   | (uint32_t(base[off-3]) << 8)
                                   | (uint32_t(base[off-2]) << 16)
                                   | (uint32_t(base[off-1]) << 24);
                    uint8_t hi_nib = (prior >> 28) & 0xf;
                    prior_is_arm_word = (hi_nib == 0xe || hi_nib == 0xf);
                }
                if (prior_is_arm_word) {
                    // ARM-mode terminator ONLY. Use the early helper
                    // (Thumb=false) which checks the prior 4 bytes
                    // against ARM ret/b/bx patterns.
                    if (!is_preceded_by_terminator_early(va, false))
                        continue;
                    // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT (default ON; =0 is the
                    // old scan byte for byte) is applied ABOVE, before this
                    // guard: a candidate that an A32 B/BL lands on is ARM code
                    // (arm_b_targets), so its Thumb16 prologue match is a
                    // coincidence of the word's low halfword.
                    //
                    // Measured victim (Jetpack Joyride 1.3.5 libmortargame.so):
                    // 0x297964 is `bl __stack_chk_fail@plt` = 0xebf6b586, a
                    // cold block of the function 0x2974f4 after its `b
                    // 0x297688`, reached by that function's own `bne 0x297964`.
                    // Its low halfword 0xb586 reads as Thumb `push {r1,r2,r7,lr}`,
                    // so it was minted as a function start and capped 0x2974f4
                    // at 0x297964. The cold blocks after it (0x297964, 0x297968)
                    // became separate synths, the function's own `bne 0x297968`
                    // became a TAIL CALL whose epilogue restores x19-x28 --
                    // guest r4-r11 and SP -- to the function's ENTRY values, so
                    // the std::string release there ran on the caller's
                    // registers and its `b 0x2975bc` came back with r6 (the
                    // File object) == 0: the NULL stream read at 0x3686e0 and
                    // the shutdown after the splash.
                    // See docs/arm32-false-split-thumb-coincidence_2026-09-30.md.
                } else {
                    // Safe to try both modes.
                    if (!is_preceded_by_terminator_in_context(va))
                        continue;
                    // v1.0.1655 MACAPK_ARM32_STRW_TAIL_NOT_POP (default ON; =0
                    // is the old scan). Unmasked by MACAPK_ARM32_POOL_SKIP_NV,
                    // and a defect in its own right: clang's
                    // Thumb-2 frame setup is `push {r4-r7,lr}; add r7,sp,#N;
                    // str.w r11,[sp,#-4]!; sub sp,#M`. `str.w Rt,[sp,#-4]!` is
                    // f84d Rt:d04, so its SECOND halfword (0xbd04 for r11)
                    // reads as Thumb16 `pop {r2,pc}` and the `sub sp` after it
                    // passed the precedence check above as a function entry.
                    // A false NV pool mark used to hide exactly such a `sub sp`
                    // in GD Lite's libfmod (0x4d748, marked by the cond=1111
                    // word at 0x4e540); with the mark gone it capped the FMOD
                    // routine at 0x4d740 to 8 bytes and lost its body and the
                    // BL that discovers 0x4d184. The same shape was already
                    // truncating 112 other libfmod routines to 8 bytes with no
                    // lifted tail (the store's "pop" admitted their `sub sp` as
                    // an entry that the late pass then never lifted). So when
                    // the two halfwords
                    // before a Thumb16 `sub sp` / `push` (no lr) candidate are
                    // exactly that store, the "pop" is its tail, not a return.
                    // (Parity is ambiguous in general -- f84d can be a BL's
                    // second half -- but only this one prologue idiom is
                    // excluded, and only for the no-LR/sub-sp patterns, which
                    // are mid-prologue far more often than they are entries.)
                    if (s_strw_tail_not_pop && !is_arm_pro && off >= 4) {
                        const uint16_t h4 = uint16_t(base[off-4]) | (uint16_t(base[off-3]) << 8);
                        const uint16_t h2 = uint16_t(base[off-2]) | (uint16_t(base[off-1]) << 8);
                        const bool no_lr_or_subsp = !(hw0 >= 0xb500 && hw0 <= 0xb5ff);
                        if (no_lr_or_subsp && h4 == 0xf84du && (h2 & 0x0fffu) == 0x0d04u)
                            continue;
                    }
                }
                anticipated_starts.insert(va);
            }
        }
        // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT is self-describing. With =0 the line
        // is not printed, so that arm's stderr is the old one.
        const size_t pro_scan_arm_context_rejects_boundary = pro_scan_arm_context_rejects;
        if (pro_scan_arm_context_on()) {
            std::fprintf(stderr,
                "[translator] prologue scan: %zu Thumb-push coincidence(s) in ARM "
                "context rejected (MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT)\n",
                pro_scan_arm_context_rejects);
        }
        // v1.0.524: mid/intra-function BL targets. A `bl func+offset` to a valid
        // instruction boundary INSIDE a known function is the classic ARM
        // runtime-helper pattern -- e.g. `__aeabi_idivmod` does `bl __divsi3+0x8`
        // to reuse the division body PAST its redundant zero-check. The
        // inside_any_known_body filter below otherwise rejects such a target, so
        // the `bl` resolves to a return-0 panic stub (HCR 1.43: 0x2ee980 ->
        // every integer div/mod returned 0). A `bl T` to an address within the
        // SAME lifted function can't be a call-to-basic-block in LLVM, so we lift
        // T as its OWN synth (the tail of the host function) and let the call
        // resolve to it. ADMIT T only when it is: a REAL decoded direct-call
        // target (external_bl_targets, NOT a heuristic data seed), aligned to its
        // containing function's ISA (4 for ARM / 2 for Thumb -- rejects mid-
        // instruction garbage like the old mode-misclassification bl's), and not
        // a literal-pool word. Affects all apps -> render-sweep-gated.
        // MACAPK_DIAG_SKIP_BL=<hex>[,<hex>...]: DIAGNOSTIC ONLY -- exclude these
        // mid-function bl targets from being lifted, so a `bl <tc>` reverts to a
        // panic-stub return-0. Used to confirm whether a specific thunk's body is
        // what crashes (e.g. does appDidFinish reach its StageInfo builder if the
        // pre-builder thunk is skipped?). Generic (matches any vaddr), gated,
        // default empty. NOT a ship path -- remove the env to restore real lifting.
        std::unordered_set<uint32_t> diag_skip_bl;
        if (const char* sk = std::getenv("MACAPK_DIAG_SKIP_BL")) {
            std::string s(sk);
            size_t p = 0;
            while (p < s.size()) {
                size_t c = s.find(',', p);
                std::string tok = s.substr(p, c == std::string::npos
                                              ? std::string::npos : c - p);
                if (!tok.empty())
                    diag_skip_bl.insert(
                        (uint32_t)std::strtoul(tok.c_str(), nullptr, 0));
                if (c == std::string::npos) break;
                p = c + 1;
            }
        }
        std::unordered_set<uint32_t> mid_fn_bl_allow;
        for (uint32_t t : lifter.external_bl_targets()) {
            uint32_t tc = t & ~1u;
            if (known_starts.count(tc)) continue;
            if (diag_skip_bl.count(tc)) continue;   // diagnostic exclusion
            // v1.0.531: the literal-pool scan is a BLIND linear sweep (no $d
            // mapping symbols survive NDK strip), so in Thumb regions it
            // mis-decodes instruction pairs as ARM `ldr rX,[pc,#imm]` and marks
            // spurious "literal targets" (106k+ false bytes on HCR libgame).
            // `tc` here is by construction an authoritative `bl #imm` target;
            // when it ALSO carries a real function prologue, the bl-provenance
            // + prologue is far stronger evidence than the blind pool scan, so
            // DON'T let the false-positive pool verdict drop it. This was the
            // HCR 1.43 streambuf-corruption root cause: libstdc++ num_put
            // helper 0x2dac48 (bl'd from 0x2dacd4/0x2dad8e/0x2db2e0, real
            // `push {r4-r7,lr}` prologue) was pool-flagged -> never lifted ->
            // `bl 0x2dac48` panic-stubbed to 0 -> ostringstream get-area
            // pointers never set -> memcpy(dst=0x1,n=0xffffffff). Same carve-
            // out the inside_lifted_body check below already applies.
            // ...and the prologue test cannot rescue a LEAF, which has no
            // push at all. HCR 1.43's 0x204704 is exactly that: a real
            // 5-instruction ARM leaf (ldr/ldr/str/str/bx lr) that the blind
            // pool scan flagged as data because a genuine pool word sits
            // immediately before it. It was the ONLY unresolved call that
            // actually executed, and it panic-stubbed the cocos2d-x SAX
            // parser's init chain -> nativeInit never finished -> the app
            // rendered nothing at all.
            //
            // .ARM.exidx is the linker's own function-boundary table and the
            // only complete one that survives stripping, so when IT names an
            // address a function start, a heuristic pool verdict is simply
            // wrong. Note this consults the always-parsed VALIDATION set, so
            // it works with the expensive exidx SEEDING still gated off.
            if (is_in_literal_pool(tc) && !looks_like_fn_prologue(tc) &&
                !img.exidx_fn_starts.count(tc)) continue;
            auto rit = std::upper_bound(
                thumb_ranges.begin(), thumb_ranges.end(), tc,
                [](uint32_t a, const ThumbRange& r) { return a < r.start; });
            if (rit == thumb_ranges.begin()) continue;
            --rit;
            if (!(tc > rit->start && tc < rit->end)) continue;  // not mid-body
            if (rit->is_thumb) { if (tc & 1u) continue; }       // Thumb: 2-aligned
            else               { if (tc & 3u) continue; }       // ARM: 4-aligned
            if (mid_fn_bl_allow.insert(tc).second) {
                known_thumb[tc] = rit->is_thumb;
                init_seed_to_lift.push_back(tc);
            }
        }
        for (uint32_t vaddr : init_seed_to_lift) {
            auto it_seg = std::find_if(img.loadable.begin(), img.loadable.end(),
                [&](const auto& s) {
                    return s.executable && vaddr >= s.vaddr
                        && vaddr <  s.vaddr + s.bytes.size();
                });
            if (it_seg == img.loadable.end()) continue;
            // Drop candidates that land in the body of an already-
            // committed function (symboled or previously-lifted synth).
            // A real init_array entry will point at the START of a
            // function, never the middle. Skipping in-body candidates
            // prevents mid-function bogus synths whose fresh-register
            // body lifts the same bytes as the parent function and
            // gets called with garbage register state.
            // A sizeless FUNC export is exempt: the linker NAMED it as an
            // entry, so it is one even inside another symbol's extent (an
            // alternate entry, like the nested EABI compare helpers). No
            // corpus library has one there today (census sl_body = 0).
            const auto sl_mode = sizeless_export_mode.find(vaddr);
            const bool is_sizeless_export = sl_mode != sizeless_export_mode.end();
            if (inside_any_known_body(vaddr) && !mid_fn_bl_allow.count(vaddr) &&
                !is_sizeless_export) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] init_array seed skip 0x%x: "
                        "inside known function body\n", vaddr);
                }
                continue;
            }
            uint32_t seg_end = it_seg->vaddr + it_seg->bytes.size();
            // v1.0.57: walk past bogus known_starts entries when
            // computing body size. See next_real_function_start.
            // v1.0.63: use anticipated_starts (= known_starts ∪
            // pending init/data seeds) so the cap accounts for
            // siblings that haven't been lifted yet.
            // Resolve the mode BEFORE sizing: the extent walker needs to know
            // whether this is an ARM lift to apply the 4-alignment rule. Same
            // deterministic chain the lift uses, so the two always agree.
            const bool mode_arm_hint = [&]{
                if (is_sizeless_export) return !sl_mode->second;
                if (auto h = lookup_is_thumb(vaddr)) return !*h;
                if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                  vaddr, 16)) return !*nb;
                return !sniff_thumb_at(vaddr);
            }();
            uint32_t end = next_real_function_start(
                anticipated_starts, vaddr, seg_end, mode_arm_hint);
            if (end <= vaddr) continue;
            uint32_t size = end - vaddr;
            const uint32_t size_raw = size;
            if (size > 0x4000) { ++g_size_clamp_hits; size = 0x4000; }
            // Pure-thrower override: if this vaddr is a detected throw
            // helper, emit a `ret 0` stub instead of lifting. See the
            // "throw-helper scan" block above.
            if (emit_stub_synth(vaddr) || emit_veneer_synth(vaddr) || emit_path_remap_synth(vaddr) || emit_pure_thrower_synth(vaddr)) {
                known_starts.insert(vaddr);
                ++discovered_init;
                ++lifted;
                continue;
            }
            // Inherit Thumb-ness from the containing symbol when
            // discoverable; otherwise sniff the first instruction
            // bytes. Falling back to "default ARM" missed Thumb-only
            // libxml2 / libstdc++ helpers (xmlInitDict, etc.) reached
            // by direct BL from other Thumb code -- their first 2
            // bytes are recognizable (b5xx push, b0xx sub-sp).
            bool synth_is_thumb;
            bool have_hint = false;
            if (is_sizeless_export) {
                // The symbol's own st_value bit 0: authoritative, no retry.
                synth_is_thumb = sl_mode->second;
                have_hint = true;
            } else if (auto hint = lookup_is_thumb(vaddr)) {
                synth_is_thumb = *hint;
                have_hint = true;
            } else if (auto nearby = nearest_known_thumb(
                    known_starts, known_thumb, vaddr, 16)) {
                synth_is_thumb = *nearby;
                have_hint = true;
            } else {
                synth_is_thumb = sniff_thumb_at(vaddr);
            }
            size = arm_reach_trim(vaddr, size, size_raw, synth_is_thumb);
            auto lf = lifter.lift_synthetic(img, vaddr, size, synth_is_thumb);
            // Mode retry on disasm failure when we lacked a hint -- see
            // detailed comment in the BL/synth pass below.
            if (!lf.success && !have_hint) {
                auto retry = lifter.lift_synthetic(
                    img, vaddr, size, !synth_is_thumb);
                if (retry.success) {
                    lf = std::move(retry);
                    synth_is_thumb = !synth_is_thumb;
                }
            }
            if (!lf.success) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] init_array seed skip 0x%x: %s\n",
                        vaddr, lf.error.c_str());
                }
                continue;
            }
            module_ir << lf.ir_body;
            known_starts.insert(vaddr);
            known_thumb[vaddr] = synth_is_thumb;
            record_lifted_range(vaddr, size, lf.insns);
            ++discovered_init;
            ++lifted;
        }
        // The executable segment we care about (HCR's libgame has a
        // single executable PT_LOAD at vaddr 0; future inputs may
        // have multiple, so we keep this as a generic predicate).
        auto exec_segment_for = [&](uint32_t addr) -> const pt::CodeSegment* {
            for (const auto& seg : img.loadable) {
                if (!seg.executable) continue;
                if (addr >= seg.vaddr
                 && addr <  seg.vaddr + seg.bytes.size()) return &seg;
            }
            return nullptr;
        };
        // MACAPK_ARM32_REACH_EXTENT also lifts the two BL/branch-target loops to
        // a FIXPOINT (cap 64 rounds; `if (to_lift.empty()) break;` ends them).
        // Over-extended lifts used to discover functions BREADTH-first by
        // accident (their foreign bodies carried `bl`s to real functions); with
        // each function bounded by what it reaches, the call graph is walked
        // one level per round, and a chain deeper than the old 8 / 4 rounds
        // left real callees unlifted (Joyride 0x37fdc4 / 0x88c2c / 0x1932f8:
        // "unresolved external call ... returning 0" at run time).
        // MACAPK_ARM32_DISCOVERY_ROUNDS=<n> (diagnostic; only read with
        // REACH_EXTENT on) caps both loops at n rounds -- the gate's positive
        // control for the fixpoint (n=4 leaves the real callees above unlifted).
        const int kBlRoundsCap = [] {
            const char* e = std::getenv("MACAPK_ARM32_DISCOVERY_ROUNDS");
            const int n = e ? std::atoi(e) : 0;
            return n > 0 ? n : 64;
        }();
        const int kBlRounds1 = reach_extent_on ? kBlRoundsCap : 8;
        const int kBlRounds2 = reach_extent_on ? kBlRoundsCap : 4;
        for (int round = 0; round < kBlRounds1; ++round) {
            std::vector<uint32_t> to_lift;
            // DBG: dump external_targets entries in 0x2c5d60-0x2c5db0
            for (uint32_t t : lifter.external_targets()) {
                if (known_starts.count(t)) continue;
                if (round >= 8 && reach_extent_on && !arm_code_at(t)) continue;  // extra rounds: ARM code only
                if (plt_addrs.count(t))    continue;
                if (!exec_segment_for(t))  continue;
                // Drop external targets that resolve INSIDE a function
                // body we've already committed to (symboled or
                // previously-lifted synth). The lifter's
                // emit_uncond_branch and emit_bl classify a target as
                // "extern" whenever it falls outside the current
                // function's [fn_start, fn_end_excl) -- but if the
                // SOURCE function had a tight fn_end_excl (e.g.
                // because a sibling synth's known_starts entry was
                // already placed mid-function), an INTRA-function
                // branch leaks out as an extern target. Creating a
                // synth at that mid-function address spawns a parallel
                // body that lifts the SAME bytes as the parent and
                // gets invoked through __macapk_target_<addr> with
                // fresh-zero r4..r11 -- exactly the chain that drove
                // PlayerCarAttribute std::string corruption in HCR
                // 1.43 (synth_0017a3a0 inside the body of the function
                // at 0x17a394, 12 bytes after its real entry).
                //
                // BUT the filter must distinguish two cases:
                //  (a) symboled body covers t  -- authoritative, t is
                //      genuinely interior, keep filtering.
                //  (b) only a HEURISTIC synth's recorded range covers
                //      t (a SKIPDATA over-run / bogus prologue/data
                //      seed swallowed it). A `bl t` is an authoritative
                //      function entry the compiler emitted. If t has a
                //      real function prologue it is a real function the
                //      absorbing synth wrongly ate -- it MUST get its
                //      own lifted body + __macapk_target_<t> alias or
                //      the call resolves to a return-0 panic stub
                //      (HCR 1.43: vector<PlayerCarAttribute>::
                //      _M_insert_aux `bl 0x207b84` -> dropped call ->
                //      stale x0 (a JNI handle) stored as the nested
                //      vector _M_start -> realloc-copy SEGV -> black
                //      screen). Only override when t looks like a real
                //      entry; a no-prologue interior leak stays filtered.
                // v1.0.838: ...unless a cross-function `b` from a DIFFERENT
                // ARM-mode symbol proves `t` is a shared tail entry (libgcc
                // soft-float code sharing). Default OFF; see
                // shared_tail_entries' definition for the proof and the
                // measured selection set.
                if (inside_known_body(t) && !is_shared_tail_entry(t)) continue;
                // v1.0.533 (Bug-3): also KEEP a prologue-less target that is
                // PRECEDED BY A TERMINATOR -- a real tail-called leaf the lifter
                // absorbed into a neighbor (HCR: 0x20f7b0 `ldr..str..bx lr` after a
                // `pop {..,pc}`; 0x20fd5c cmp-guard after a `b`). The v1.0.28
                // intra-function-`b`-leak hazard is MID-function (not terminator-
                // preceded) so it stays dropped. Render-sweep-gated.
                if (inside_lifted_body(t) && !is_authoritative_bl_target(t)
                    && !looks_like_fn_prologue(t)
                    && !is_preceded_by_terminator_in_context(t))
                    continue;
                // v1.0.59: PROVABLY-data check. A BL target that lands
                // in a literal pool is bogus (probably from a Thumb
                // misclassification cascade). Drop it.
                // v1.0.531: ...UNLESS it is an authoritative `bl` target with a
                // real function prologue. The pool scan is a blind linear sweep
                // that false-flags real code in Thumb regions (it mis-decodes
                // Thumb pairs as ARM ldr-pc). bl-provenance + prologue overrides
                // that heuristic -- same carve-out as the inside_lifted_body
                // check above. (HCR 1.43: libstdc++ num_put helper 0x2dac48.)
                // ...and ALSO unless .ARM.exidx names it a function start.
                // The prologue clause cannot rescue a LEAF (no push at all),
                // and the terminator clause cannot either when a genuine pool
                // word sits between the previous function's `pop {..,pc}` and
                // this entry -- exactly the layout here. So a leaf that
                // follows a literal pool is unrescuable by either heuristic,
                // which is why this bug class keeps recurring. exidx is the
                // linker's own function-boundary table and the only complete
                // one that survives stripping: when it and bl-provenance
                // agree, a blind pool sweep is simply wrong.
                //
                // HCR 1.43 0x204704: ldr ip,[r1,#0x14] / ldr r2,[r1,#0x18] /
                // str ip,[r0,#0x14] / str r2,[r0,#0x18] / bx lr -- exidx entry
                // #4749, preceded by pool word 0x00177014, itself preceded by
                // `pop {r3,r4,r5,pc}`. It was the ONLY unresolved call that
                // actually executed; stubbing it to 0 killed the cocos2d-x
                // SAX parser's init chain, so nativeInit never completed and
                // the app rendered nothing.
                // v1.0.825: ...and ALSO unless the pool mark came ONLY from
                // the THUMB scan while the bytes are an unmistakable ARM
                // `push {..}` entry. Both carve-outs above require
                // is_authoritative_bl_target, so neither can rescue an
                // ADDRESS-TAKEN function -- one reached through a literal-pool
                // fnptr and an indirect call rather than by `bl`. That is
                // exactly the shape that stayed broken: HCR 1.8.1's
                // MainMenuCarLayer::create() at 0xde310 (arm=0 thumb=1) was
                // stubbed to 0, so MainMenuCarLayer::scene() added a NULL child
                // and every gameplay stage rendered black.
                if (is_in_literal_pool(t)
                    && !(is_authoritative_bl_target(t) &&
                         (looks_like_fn_prologue(t) ||
                          img.exidx_fn_starts.count(t) ||
                          // v1.0.828: ...or the mark is THUMB-ONLY. A literal
                          // pool word is never the target of a `bl` in correct
                          // code, so BL provenance plus "the ARM pool -- the
                          // only one ARM literal loads actually dereference --
                          // does not contain it" is already proof this is code.
                          // Requiring a prologue on top of that cannot see a
                          // LEAF, and exidx merges small leaves into a
                          // neighbour's entry, so both existing clauses miss the
                          // commonest shape in C++ output: a trivial
                          // operator=/getter/forwarder bounded by `bx lr` with
                          // no `push` at all. HCR 1.68.1 0x4ff0e8 is a 12-byte
                          // copy-assign with a self-assignment check; it
                          // panic-stubbed to 0, and the caller then printf'd a
                          // guest vaddr as a host pointer and dispatched through
                          // a NULL object (this=0x464c457f = "\x7fELF" at guest
                          // vaddr 0).
                          // v1.0.833: gated so the escape can be A/B'd.
                          (pool_escape_828_enabled() &&
                           !is_in_arm_literal_pool(t))))
                    && !(!is_in_arm_literal_pool(t) &&
                         looks_like_arm_push_prologue(t)))
                    continue;
                // v1.0.68: NARROW mid-ARM-instruction filter. Per
                // project_hcr143_ccnode_root_cause.md, the bogus
                // discovery cascade starts when a 2-byte-aligned
                // address falls MID a 4-byte ARM instruction. The
                // bogus Thumb-decode of those bytes produces a synth
                // whose BL target is ALSO mid-instruction → recursive
                // cascade → CCNode::CCNode truncation.
                //
                // The v1.0.56 broad filter (just check the prior 4
                // bytes look ARM-instruction-shaped) was too
                // aggressive — legit Thumb entries at 2-byte-aligned
                // addresses after AL-conditioned ARM code got false-
                // flagged.
                //
                // v1.0.68 narrow filter: require ALL THREE:
                //   1. t is 2-byte-aligned but NOT 4-byte-aligned
                //      (so it might be inside an ARM word)
                //   2. The byte at t+1 has high nibble 0xe (AL) or
                //      0xf (UNCOND/BLX). For a 4-byte-aligned ARM
                //      instruction at t-2, byte (t-2)+3 = t+1 is the
                //      condition + top opcode bits. AL or UNCOND
                //      is what compilers virtually always emit.
                //   3. t's own first 2 bytes don't match a Thumb-16
                //      prologue pattern (push, sub sp, add sp, push
                //      {r0..r7}, add r7,sp). A real Thumb function
                //      entry that happens to be 2-aligned would
                //      satisfy (2) by coincidence; the prologue check
                //      filters it out.
                //
                // Empirically this is a strong signature of "you're
                // staring at byte 2 of an ARM instruction" in HCR
                // 1.43 / 1.8.1.
                if ((t & 3) == 2) {
                    auto check_bytes = img.code_at(t + 1, 1);
                    if (check_bytes.size() >= 1) {
                        uint8_t high_byte = check_bytes[0];
                        uint8_t high_nibble = (high_byte >> 4) & 0xf;
                        if ((high_nibble == 0xe || high_nibble == 0xf)
                            && !sniff_thumb_at(t)) {
                            if (verbose) {
                                std::fprintf(stderr,
                                    "[translator] BL target 0x%x rejected: "
                                    "mid-ARM-instruction (prior insn cond=0x%x, "
                                    "no Thumb prologue at candidate)\n",
                                    t, high_nibble);
                            }
                            continue;
                        }
                    }
                }
                to_lift.push_back(t);
            }
            if (to_lift.empty()) break;
            // Sort + dedupe so we lift in address order, helping the
            // size-by-next-start heuristic.
            std::sort(to_lift.begin(), to_lift.end());
            to_lift.erase(std::unique(to_lift.begin(), to_lift.end()),
                          to_lift.end());
            for (size_t ti = 0; ti < to_lift.size(); ++ti) {
                uint32_t vaddr = to_lift[ti];
                // Re-check known_starts since previous iterations
                // within this round may have added it.
                if (known_starts.count(vaddr)) continue;
                // Re-check inside-known-body too: previous iterations
                // in this very loop may have lifted a synth whose
                // body now covers this candidate. Same authoritative-
                // BL-target carve-out as above: a heuristic synth that
                // absorbed a real (prologue-bearing) function entry
                // must not suppress that entry's own lift.
                // v1.0.838: same shared-tail-entry carve-out as the filter pass.
                if (inside_known_body(vaddr) && !is_shared_tail_entry(vaddr))
                    continue;
                if (inside_lifted_body(vaddr) &&
                    !is_authoritative_bl_target(vaddr) &&
                    !looks_like_fn_prologue(vaddr) &&
                    !is_preceded_by_terminator_in_context(vaddr)) continue;  // v1.0.533 Bug-3
                // Pure-thrower override before lifting.
                if (emit_stub_synth(vaddr) || emit_veneer_synth(vaddr) || emit_path_remap_synth(vaddr) || emit_pure_thrower_synth(vaddr)) {
                    known_starts.insert(vaddr);
                    ++discovered;
                    ++lifted;
                    continue;
                }
                const auto* seg = exec_segment_for(vaddr);
                if (!seg) continue;
                uint32_t seg_end = seg->vaddr + seg->bytes.size();
                // v1.0.57: walk past bogus known_starts entries.
                // Resolve the mode BEFORE sizing: the extent walker needs to know
                // whether this is an ARM lift to apply the 4-alignment rule. Same
                // deterministic chain the lift uses, so the two always agree.
                const bool mode_arm_hint = [&]{
                    if (auto h = lookup_is_thumb(vaddr)) return !*h;
                    if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                      vaddr, 16)) return !*nb;
                    return !sniff_thumb_at(vaddr);
                }();
                uint32_t end = next_real_function_start(
                    anticipated_starts, vaddr, seg_end, mode_arm_hint);
                // ALSO tighten by the next entry in to_lift -- it's a
                // pending candidate that WILL be lifted in this same
                // loop iteration order, so the current synth must not
                // absorb it. Without this, a synth (Thumb or ARM) at
                // vaddr X with no known_starts entry between X and
                // some-far-address would happily decode through bytes
                // belonging to a sibling function whose lift hasn't
                // run yet -- after which `inside_any_known_body`
                // rejects that sibling and it ends up as a panic
                // stub. HCR 1.43 ARM32 regression: synth_0020944a
                // (Thumb) absorbed bytes through 0x20a346 including
                // the ARM function at 0x20983c (setScreenWidthAnd-
                // Height); the lifted nativeInit's BL to 0x20983c
                // then hit a panic stub, screen dimensions stayed
                // BSS-zero, and cocos2d-x's downstream JNI calls
                // crashed libjvm.
                //
                // v1.0.57: walk PAST mid-function candidates when
                // tightening (any candidate that's NOT a known BL
                // target AND NOT preceded by a function terminator in
                // either ARM or Thumb mode). Capstone Thumb decode of
                // mid-ARM bytes, or a data_fnptr seed pointing into
                // a function body, can queue a bogus candidate in
                // to_lift (e.g. HCR 1.43 0x202f20 = mid CCNode-ctor:
                // its previous insn at 0x202f1c is `bl 0x2046c8`,
                // NOT a terminator, so is_mid_function_candidate
                // returns true). The bogus candidate truncates
                // sibling functions (CCNode was capped at offset
                // 0x80 instead of its full 0x108 byte body -> the
                // m_pActionManager `str r0,[r4,#0xd4]` at 0x202f9c
                // never executed -> menu rendered all-black). The
                // candidate STAYS in to_lift; its own lift simply
                // fails or produces a small wrong synth, and the
                // sibling that absorbs the bytes wins.
                // When the synth being sized is itself an AUTHORITATIVE
                // BL target -- a high-confidence real function entry the
                // compiler `bl`'d -- do NOT let a low-confidence interior
                // branch target tighten its budget. A large single-TU
                // inlined function (stb_image's ~5 KB PNG decoder at
                // Mindustry libarc 0x329ac) has dozens of interior basic
                // blocks reached by `b`/`b<cond>`; each leaks into
                // external_targets and queues in to_lift, and because they
                // sit right after an (intra-function) `b` terminator,
                // is_mid_function_candidate can't tell them from a real
                // entry -- so the old tightening capped 0x329ac at the
                // first one (0x32f2c) -> size 0x580 -> the function shredded
                // into 53 fragments, intra-function branches became
                // return-0 panic stubs, and PNG decode failed ("Image not
                // of any known type, or corrupt"). For a bl-target source,
                // only let another bl-target / symbol / real prologue
                // tighten it (the genuine sibling boundaries); skip
                // prologue-less non-bl b-targets, which are interior blocks
                // this function will cover once lifted whole. Non-bl-target
                // synths keep the original conservative tightening.
                const bool src_is_bl_target = is_authoritative_bl_target(vaddr);
                if (src_is_bl_target) {
                    // A bl-target is a high-confidence real function entry.
                    // Do NOT let an interior branch target tighten its
                    // budget: a large single-TU inlined function
                    // (stb_image's ~5 KB PNG decoder at Mindustry libarc
                    // 0x329ac) has dozens of interior basic blocks reached
                    // by `b`/`b<cond>` that leak into to_lift, and because
                    // they sit after an intra-function `b` terminator,
                    // is_mid_function_candidate can't distinguish them from
                    // a real entry -- the old to_lift tightening capped
                    // 0x329ac at the first one (0x32f2c) -> size 0x580 ->
                    // the function shredded into 53 fragments and PNG decode
                    // failed. Instead cap a bl-target synth only at the next
                    // PROLOGUE-BEARING bl-target (the genuine next function
                    // entry); interior b-targets are this function's own
                    // blocks and will be covered once it's lifted whole.
                    for (uint32_t t : lifter.external_bl_targets()) {
                        uint32_t tc = t & ~1u;
                        if (tc > vaddr && tc < end && looks_like_fn_prologue(tc))
                            end = tc;
                    }
                } else {
                    // Non-bl-target synth: keep the original conservative
                    // tightening by the next real to_lift entry (walking
                    // past provably-data mid-function candidates).
                    size_t tj = ti + 1;
                    while (tj < to_lift.size() &&
                           is_mid_function_candidate(to_lift[tj])) {
                        ++tj;
                    }
                    if (tj < to_lift.size() && to_lift[tj] < end) {
                        end = to_lift[tj];
                    }
                }
                if (end <= vaddr) continue;
                uint32_t size = end - vaddr;
                // Cap at 16 KiB so a stripped binary with widely-
                // spaced known starts doesn't pull half the segment
                // into one synthetic.
                const uint32_t size_raw = size;
                if (size > 0x4000) { ++g_size_clamp_hits; size = 0x4000; }
                // Inherit Thumb-ness from the containing symbol when
                // discoverable; otherwise prefer the mode of the nearest
                // known-Thumb function (within ~16 bytes) -- the BL
                // target and the nearby push-prologue almost always
                // belong to the same function. Last resort: sniff the
                // first instruction bytes.
                bool synth_is_thumb;
                bool have_hint = false;
                if (auto hint = lookup_is_thumb(vaddr)) {
                    synth_is_thumb = *hint;
                    have_hint = true;
                } else if (auto nearby = nearest_known_thumb(
                        known_starts, known_thumb, vaddr, 16)) {
                    synth_is_thumb = *nearby;
                    have_hint = true;
                } else {
                    synth_is_thumb = sniff_thumb_at(vaddr);
                }
                size = arm_reach_trim(vaddr, size, size_raw, synth_is_thumb);
                auto lf = lifter.lift_synthetic(img, vaddr, size, synth_is_thumb);
                // Mode retry: sniff_thumb_at only recognizes Thumb-16
                // prologue patterns (b5XX, b4XX, b0XX, afXX) and returns
                // false for everything else, including a Thumb-2 32-bit
                // instruction at function entry. Stripped libstdc++
                // helpers that begin with `movw rX, #imm` (e.g. the
                // libstdc++ pre-C++11 string `_S_create` size-bounds
                // check before the push prologue) end up incorrectly
                // tagged ARM, Capstone's ARM-mode disasm returns 0
                // instructions, and the resulting unresolved panic stub
                // silently corrupts callers' return values (HCR 1.43's
                // black-screen at boot, root-caused 2026-05-18).
                if (!lf.success && !have_hint) {
                    auto retry = lifter.lift_synthetic(
                        img, vaddr, size, !synth_is_thumb);
                    if (retry.success) {
                        lf = std::move(retry);
                        synth_is_thumb = !synth_is_thumb;
                    }
                }
                if (!lf.success) {
                    if (verbose) {
                        std::fprintf(stderr,
                            "[translator] synth skip 0x%x: %s\n",
                            vaddr, lf.error.c_str());
                    }
                    continue;
                }
                module_ir << lf.ir_body;
                known_starts.insert(vaddr);
                known_thumb[vaddr] = synth_is_thumb;
                record_lifted_range(vaddr, size, lf.insns);
                ++discovered;
                ++lifted;
            }
        }
        // Lift each function pointer found in static data (vtables,
        // callback tables, function-pointer globals). Mode comes from
        // the Thumb low-bit of the relocation addend, NOT from
        // surrounding-symbol heuristics -- the addend is the canonical
        // source of ISA truth for indirect callees. This catches
        // libxml2's xmlInitParser-style Thumb helpers that aren't
        // referenced by any BL we've already decoded.
        //
        // This loop intentionally runs AFTER BL/synth round 1 has
        // converged: BL-reachable functions are AUTHORITATIVE
        // function starts (the compiler emitted a `bl <addr>` so it
        // believes <addr> is a callable entry with a real prologue).
        // Data-fnptr seeds, by contrast, can come from
        // R_ARM_RELATIVE addends or PC-rel scans that pick up
        // mid-function labels (loop body targets, switch jump
        // targets, basic-block address-takes). Lifting those as
        // separate synths spawns parallel function bodies with
        // fresh-zero r4..r11 -- the caller's intra-function `b
        // <mid>` tail-call delivers r0..r3 cleanly but the
        // mid-function code expects r4..r11 set by the original
        // function's prologue, which was never run on the synth
        // entry path. (HCR 1.43: synth_0017a3a0 was the loop
        // condition of the function at 0x17a08c; lifted in isolation
        // it read r4=0 and the cmp/bne walked the wrong vector.)
        // Running BL/synth first means the surrounding function has
        // a known lifted range BEFORE we look at fnptr seeds, so
        // `inside_any_known_body` filters out the mid-function
        // candidates.
        for (auto [vaddr, is_thumb] : data_seed_to_lift) {
            if (known_starts.count(vaddr)) continue;
            // Re-check inside-known-body in case BL/synth round 1
            // committed a synth whose body now covers this vaddr.
            // This is the load-bearing filter for the HCR
            // synth_0017a3a0 case described above.
            // v1.0.552: AUTHORITATIVE seed + real prologue overrides the
            // in-body claim (same carve-out the v533 BL-target filter
            // uses): a relocation / PIC-store install pointing at a
            // push-prologue is a REAL entry by construction, and the
            // covering range is an earlier synth's byte-budget over-run.
            // FreeType's sfnt goto_table fnptr (tt_face_goto_table, raw
            // 0x2a9d4, `push {r4,r10,r11,lr}`) was dropped exactly this
            // way -> every sfnt table lookup indirect-called an
            // unregistered vaddr -> FT_New_Memory_Face returned garbage.
            //
            // v1.0.806: for a heuristic-PIC-only seed (see
            // heuristic_pic_set above), a PROVABLE mid-instruction finding
            // vetoes this override even when looks_like_fn_prologue matched
            // (a byte-pattern coincidence -- looks_like_fn_prologue matches
            // on raw bytes with no notion of "is this inside a function we
            // already decoded", so a mid-body address whose 4 bytes happen
            // to resemble e.g. an ARM push is exactly the false positive
            // the v1.0.552 comment above warns is "too lax"). This can only
            // narrow admission (never re-admit something already
            // rejected) and only applies to the heuristic-PIC provenance --
            // exidx/relocation-backed entries like tt_face_goto_table are
            // untouched. See is_provably_mid_instruction's comment.
            //
            // v1.0.806: same exidx carve-out as the classification-loop
            // gate above -- an address the PIC scan ALSO happened to find
            // is still exidx-confirmed if exidx names it, and exidx is
            // linker-verified ground truth regardless of what else tagged
            // the same address (the 0x18612c case documented above).
            bool is_pic_only_here =
                (heuristic_pic_set.count(vaddr) ||
                 heuristic_pic_set.count(vaddr | 1u))
                && !img.exidx_fn_starts.count(vaddr);
            // v1.0.808: looks_like_fn_prologue inspects only the FIRST
            // instruction and demands a push, so it rejects three shapes that
            // are all ordinary compiler output:
            //   1. an EMPTY function -- a bare `bx lr`. Every C++
            //      `virtual void onFoo() {}` override compiles to exactly
            //      this, and they are reached ONLY through a vtable, i.e.
            //      only through the indirect-call path that needs them
            //      registered.
            //   2. a prologue whose PIC literal load was scheduled ABOVE the
            //      push (`ldr r3,[pc,#..]` then `push {r4,lr}`) -- routine
            //      GCC/Clang ARM scheduling, not an edge case.
            //   3. a push-less leaf that only touches scratch registers.
            // On Jetpack Joyride ALL NINE authoritative seeds dropped here
            // were real functions of exactly those shapes (3 / 5 / 1), and
            // 0x91b18 -- an empty virtual -- was indirect-called at runtime
            // and panic-stubbed twice.
            //
            // For a LINKER-BACKED seed (`.ARM.exidx` or R_ARM_RELATIVE) the
            // proof that this address is an entry point is the RELOCATION --
            // the linker emitted it because the source took this address --
            // not whatever instruction happens to sit there. So gate those on
            // the evidence-based is_provably_mid_instruction instead, which
            // asks the enclosing function's own decode whether it ever
            // stopped here. Heuristic-PIC-only seeds keep BOTH the prologue
            // requirement and the mid-instruction veto exactly as v1.0.806
            // left them, because for those the byte pattern is the ONLY
            // evidence and the v1.0.552 comment above is right that it is lax.
            // MACAPK_AUTH_SEED_PROLOGUE_STRICT=1 restores the old gate for
            // bisecting.
            static const bool strict_prologue =
                std::getenv("MACAPK_AUTH_SEED_PROLOGUE_STRICT") != nullptr;
            bool auth_seed =
                (authoritative_set.count(vaddr) ||
                 authoritative_set.count(vaddr | 1u));
            bool auth_entry =
                auth_seed &&
                !is_provably_mid_instruction(vaddr) &&
                ((is_pic_only_here || strict_prologue)
                     ? looks_like_fn_prologue(vaddr)
                     : true);
            if (inside_any_known_body(vaddr) && !auth_entry) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] data fnptr seed skip 0x%x: "
                        "inside known function body\n", vaddr);
                }
                continue;
            }
            // Pure-thrower override before lifting.
            if (emit_stub_synth(vaddr) || emit_veneer_synth(vaddr) || emit_path_remap_synth(vaddr) || emit_pure_thrower_synth(vaddr)) {
                known_starts.insert(vaddr);
                known_thumb[vaddr] = is_thumb;
                ++discovered_init;
                ++lifted;
                continue;
            }
            const auto* seg = exec_segment_for(vaddr);
            if (!seg) continue;
            uint32_t seg_end = seg->vaddr + seg->bytes.size();
            // v1.0.57: walk past bogus known_starts entries.
            // Resolve the mode BEFORE sizing: the extent walker needs to know
            // whether this is an ARM lift to apply the 4-alignment rule. Same
            // deterministic chain the lift uses, so the two always agree.
            const bool mode_arm_hint = [&]{
                if (auto h = lookup_is_thumb(vaddr)) return !*h;
                if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                  vaddr, 16)) return !*nb;
                return !sniff_thumb_at(vaddr);
            }();
            uint32_t end = next_real_function_start(
                anticipated_starts, vaddr, seg_end, mode_arm_hint);
            if (end <= vaddr) continue;
            uint32_t size = end - vaddr;
            const uint32_t size_raw = size;
            if (size > 0x4000) { ++g_size_clamp_hits; size = 0x4000; }
            size = arm_reach_trim(vaddr, size, size_raw, is_thumb);
            auto lf = lifter.lift_synthetic(img, vaddr, size, is_thumb);
            if (!lf.success) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] data fnptr seed skip 0x%x (thumb=%d): %s\n",
                        vaddr, int(is_thumb), lf.error.c_str());
                }
                continue;
            }
            module_ir << lf.ir_body;
            known_starts.insert(vaddr);
            known_thumb[vaddr] = is_thumb;
            record_lifted_range(vaddr, size, lf.insns);
            // Bucket data-fnptr seeds with the .init_array seed count
            // so the summary log shows them under "via .init_array seed":
            // both reach the lifter through static-data analysis rather
            // than the BL-target round-trip.
            ++discovered_init;
            ++lifted;
        }

        // ------------------------------------------------------------
        // Prologue scan: pick up unsymbolled functions reached only via
        // *runtime-computed* function pointers. The cocos2d-x png read
        // callback is a textbook example -- its address never appears
        // as a literal in the binary; instead the compiler emits
        //
        //     ldr  r9, [pc, #N]    ; r9 = -0x490 (or some signed offset)
        //     add  w23, w25, w9    ; w23 = 0x174a1c + (-0x490)        ;= 0x17458c
        //     add  w2, w23, #0x118 ; w2  = 0x17458c + 0x118           ;= 0x1746a4
        //     bl   png_set_read_fn ; r2 (read_data_fn) is now the cb ptr
        //
        // libpng stores 0x1746a4 in png_struct->read_data_fn and later
        // calls back through a `blx r3` at png_read_data+0xc. The lifter
        // never saw 0x1746a4 as a BL target, so __macapk_resolve_function
        // returns NULL at that BLX and PNG decode falls over with
        // "Not a PNG file". To catch every such case, scan each gap
        // between known function starts for ARM/Thumb prologue byte
        // patterns and seed those addresses for synth lifting.
        //
        // We're conservative about what counts as a prologue: only the
        // patterns the compiler actually emits at function entries
        // (push, push.w, push-no-lr, sub sp, mov ip,sp). Random byte
        // sequences inside another function body would already be
        // covered by the larger function's lift; the gap-bounded scan
        // skips those.
        // A prologue match is suspicious unless the preceding bytes
        // are a function-end marker (return / tail-call). Without this
        // filter the scan false-positives on .rodata literal-pool
        // entries embedded in big functions: e.g. HCR 1.43's
        // ARM32 libgame at vaddrs 0x17a208 and 0x17a2b8 contain raw
        // pointer values 0x001ab5b5 / 0x0020b580 whose low halfword
        // (0xb5b5 / 0xb580) matches the Thumb16 `push {...,lr}`
        // encoding. The scanner added them as Thumb function entries,
        // which truncated the real ARM function at 0x17a090 to ~0x178
        // bytes; intra-function `b 0x17a3a0` was then treated as a
        // tail-call, the lifter created synth_0017a3a0 with fresh
        // register state, and the loop at 0x17a3a0 ran with r4=r6=0
        // instead of the caller's iterator values. Net symptom:
        // length_error throw from vector<PlayerCarInfo>::_M_insert_aux
        // during nativeInit.
        auto is_preceded_by_terminator = [&](uint32_t vaddr, bool is_thumb) -> bool {
            if (is_thumb) {
                if (vaddr < 2) return true;  // section start: accept
                auto pre = img.code_at(vaddr - 2, 2);
                if (pre.size() < 2) return true;
                uint32_t hw = uint32_t(pre[0]) | (uint32_t(pre[1]) << 8);
                // MACAPK_ARM32_THUMB_PARITY: same rule as the early copy.
                const bool term16 = (hw & 0xff00) == 0xbd00   // pop {...,pc}
                                 || hw == 0x4770                 // bx lr
                                 || (hw & 0xf800) == 0xe000      // b imm11
                                 || (hw & 0xff80) == 0x4700;     // bx Rm (any Rm)
                if (term16) {
                    if (!thumb_hw_is_second_half(img, vaddr - 2))
                        return true;
                    g_thumb_parity_refused.insert(vaddr - 2);
                }
                // Thumb-2 32-bit at vaddr-4:
                if (vaddr < 4) return true;
                auto pre4 = img.code_at(vaddr - 4, 4);
                if (pre4.size() < 4) return false;
                if (thumb_hw_is_second_half(img, vaddr - 4))
                    return false;
                uint32_t lo = uint32_t(pre4[0]) | (uint32_t(pre4[1]) << 8);
                uint32_t hi = uint32_t(pre4[2]) | (uint32_t(pre4[3]) << 8);
                if (lo == 0xe8bd && (hi & 0x8000)) return true;  // pop.w {..pc}
                // v1.0.59: B.W T4 prefix mask 0xf800 (not 0xfff0).
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd000) == 0x9000) return true;     // b.w T4
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd001) == 0xc000) return true;     // blx imm T2
                // v1.0.62: Thumb-2 BL to no-return helper.
                if ((lo & 0xf800) == 0xf000
                    && (hi & 0xd000) == 0xd000) {
                    uint32_t s_bit = (lo >> 10) & 1;
                    uint32_t imm10 = lo & 0x3ff;
                    uint32_t j1 = (hi >> 13) & 1;
                    uint32_t j2 = (hi >> 11) & 1;
                    uint32_t imm11 = hi & 0x7ff;
                    uint32_t i1 = (~(j1 ^ s_bit)) & 1;
                    uint32_t i2 = (~(j2 ^ s_bit)) & 1;
                    uint32_t offset_u = (i1 << 23) | (i2 << 22)
                                      | (imm10 << 12) | (imm11 << 1);
                    int32_t soffset = (int32_t)offset_u;
                    if (s_bit) soffset |= 0xff000000;
                    uint32_t target = vaddr + uint32_t(soffset);
                    if (pure_throw_helpers.count(target)) {
                        // MACAPK_ARM32_NORETURN_REACH: not when IT-predicated.
                        if (arm32_noreturn_reach_on()
                            && thumb_bl_is_it_predicated(img, vaddr - 4))
                            cond_helper_call_not_terminator(
                                vaddr - 4, target, "IT-predicated (Thumb)");
                        else
                            return true;
                    }
                }
                return false;
            } else {
                if (vaddr < 4 || (vaddr & 3) != 0) return false;
                auto pre = img.code_at(vaddr - 4, 4);
                if (pre.size() < 4) return false;
                uint32_t insn = uint32_t(pre[0])
                              | (uint32_t(pre[1]) << 8)
                              | (uint32_t(pre[2]) << 16)
                              | (uint32_t(pre[3]) << 24);
                // v1.0.553: UNCONDITIONAL only (see the terminator-early
                // comment): a conditional return falls through into the
                // same function; treating it as a terminator seeds
                // mid-function starts that TRUNCATE the real function.
                if ((insn & 0xffff8000) == 0xe8bd8000) return true;  // pop {...,pc} AL
                if ((insn & 0xfffffff0) == 0xe12fff10) return true;  // bx Rm AL
                if ((insn & 0xff000000) == 0xea000000) return true;  // b imm AL
                // NOTE: BL is intentionally NOT treated as a
                // terminator. BL is a function CALL -- the return
                // address goes to LR and the function CONTINUES at
                // the instruction after the BL. The compiler emits
                // tail calls as `b imm` (no link) or `bx Rm`, never
                // `bl imm`. Treating BL as a terminator created
                // false-positive prologues immediately after any
                // intra-function `bl helper()`; the bogus prologue
                // got added to known_starts, the parent function's
                // synth fn_end_excl shrunk, an intra-function branch
                // leaked out as an extern target, and a parallel
                // synth was spawned for the leaked address with
                // fresh-zero register state.
                if (insn == 0xe1a0f00e) return true;  // mov pc, lr (AL)
                if (insn == 0xe1a0f00d) return true;  // mov pc, sp (rare, AL)
                // v1.0.62: ARM `bl <pure_throw_helper>` is no-return.
                if ((insn & 0x0f000000) == 0x0b000000) {
                    int32_t imm24 = int32_t(insn & 0x00ffffff);
                    if (imm24 & 0x00800000) imm24 |= 0xff000000;
                    uint32_t target = (vaddr - 4 + 8) + uint32_t(imm24 << 2);
                    if (pure_throw_helpers.count(target)) {
                        // MACAPK_ARM32_NORETURN_REACH: cond=AL only. `blne
                        // helper` falls through when the condition fails
                        // (v1.0.553's class); cond=0xF is BLX, not BL.
                        const uint32_t cond = insn >> 28;
                        if (!arm32_noreturn_reach_on() || cond == 0xeu)
                            return true;
                        if (cond != 0xfu)
                            cond_helper_call_not_terminator(
                                vaddr - 4, target, "conditional (ARM cond != AL)");
                    }
                }
                return false;
            }
        };
        auto looks_like_arm_prologue = [&](uint32_t vaddr) -> bool {
            auto code = img.code_at(vaddr, 4);
            if (code.size() < 4) return false;
            // ARM `push {...}` / `stmfd sp!, {...}` -- e92dXXXX (LE: XX XX 2d e9).
            if (code[2] == 0x2d && code[3] == 0xe9) return true;
            // ARM `mov ip, sp` (e1a0c00d) -- classic AAPCS prologue
            // start, followed by a stmfd in the next instruction.
            if (code[0] == 0x0d && code[1] == 0xc0 &&
                code[2] == 0xa0 && code[3] == 0xe1) return true;
            // ARM `sub sp, sp, #imm` (e24dd0XX) on leaf functions that
            // don't push lr -- accept only when followed by a store
            // from a callee-saved register so we don't pick up random
            // sub-sp patches inside a function body.
            if (code[2] == 0xd0 && code[3] == 0xe2 && code[1] == 0x4d) {
                auto nxt = img.code_at(vaddr + 4, 4);
                if (nxt.size() >= 4 && nxt[3] == 0xe5 && nxt[2] == 0x8d) {
                    return true;  // str rN, [sp, #X] follows
                }
            }
            return false;
        };
        auto looks_like_thumb_prologue = [&](uint32_t vaddr) -> bool {
            auto code = img.code_at(vaddr, 4);
            if (code.size() < 2) return false;
            uint8_t b1 = code[1];
            // Thumb16 push {rN..lr}: b5XX
            if (b1 == 0xb5) return true;
            // Thumb16 push {rN..r7} (no lr): b4XX
            if (b1 == 0xb4) return true;
            // Thumb-2 push.w {...}: 0xe92d 0xXXXX (Thumb-2 byte order:
            // halfword-major) -- LE bytes [2d e9 XX XX].
            //
            // BUT: this byte signature also appears at offset +2 of
            // an ARM-mode push. ARM `push {...}` is LE bytes
            // [MM MM 2d e9], so bytes [2d e9 MM MM] starting at +2
            // of the ARM push are the trailing half of the ARM
            // instruction, NOT a real Thumb-2 push.w. Disambiguate
            // by checking whether `vaddr - 2` (the 4-aligned address
            // just before) looks like an ARM push -- if so, we're
            // inside an ARM instruction's bytes, not at a real
            // Thumb function entry.
            //
            // Root-caused 2026-05-18 (HCR 1.43 v1.0.19): the prologue
            // scan at 0x205092 (= +2 of an ARM push at 0x205090) was
            // marking 0x205092 as a Thumb entry. Then BL/synth at
            // 0x205090 inherited Thumb mode via the nearby-known
            // lookup, lifted the function in the wrong ISA, and the
            // resulting incorrect IR caused a CCAutoreleasePool
            // assert flood every frame.
            if (code.size() >= 4 && code[0] == 0x2d && code[1] == 0xe9) {
                if (vaddr >= 2 && ((vaddr - 2) & 3) == 0) {
                    auto prev = img.code_at(vaddr - 2, 4);
                    if (prev.size() >= 4
                        && prev[2] == 0x2d && prev[3] == 0xe9) {
                        // Bytes are the +2 tail of an ARM push at
                        // vaddr-2. Don't treat as a Thumb entry.
                        return false;
                    }
                }
                return true;
            }
            return false;
        };
        std::vector<std::pair<uint32_t,bool>> prologue_lifts;
        for (const auto& seg : img.loadable) {
            if (!seg.executable) continue;
            uint32_t seg_start = seg.vaddr;
            uint32_t seg_end   = seg.vaddr + seg.bytes.size();
            // Skip the PLT range -- those are import stubs with their
            // own e28fc6XX prologue-ish bytes that we never want to
            // lift as functions.
            for (uint32_t vaddr = seg_start; vaddr + 4 <= seg_end; vaddr += 2) {
                // v1.0.553: real CODE sections only (see the boundary-pass
                // comment above) -- .rodata bytes alias prologue patterns
                // and the resulting bogus synths' garbage decodes pollute
                // discovery with phantom extern branch targets.
                if (!in_code_section(vaddr)) continue;
                if (known_starts.count(vaddr)) continue;
                if (plt_addrs.count(vaddr))    continue;
                if (inside_any_known_body(vaddr)) continue;
                // Only 4-byte aligned addresses can be ARM-mode entries.
                bool can_be_arm = ((vaddr & 3) == 0);
                bool is_arm    = can_be_arm && looks_like_arm_prologue(vaddr);
                bool is_thumb  = !is_arm && looks_like_thumb_prologue(vaddr);
                if (!is_arm && !is_thumb) continue;
                // MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT (see arm_b_targets): the
                // same ARM-context evidence for this second prologue pass.
                // Jetpack Joyride 0x120040 (`ldr r11,[pc,#0x564]` =
                // 0xe59fb564, low half 0xb564 = Thumb push) sat after
                // `ldr r3,[pc,#0x564]`, whose high half 0xe59f reads as a
                // Thumb16 `b`: it was lifted as a Thumb "function" over ARM
                // bytes, and its garbage body minted 0x11ffb4 / 0x120010 as
                // further starts inside the real function 0x11fef4.
                if (is_thumb && arm_context_rejects(vaddr)) {
                    ++pro_scan_arm_context_rejects;
                    continue;
                }
                // ⚠ A THUMB SEED AT A 2-MOD-4 ADDRESS INSIDE A KNOWN *ARM*
                // FUNCTION IS PROVABLY THE TAIL OF AN ARM INSTRUCTION.
                //
                // The Thumb-2 push.w clause in looks_like_thumb_prologue above
                // already disambiguates one such case by byte pattern (HCR 1.43,
                // 2026-05-18). The Thumb-16 b5XX/b4XX clauses did not, and byte
                // patterns cannot save us here: measured on Jetpack Joyride
                // (2026-08-24), the ARM instruction b59de014 at 0x45468c is
                // `ldrlt lr,[sp,#0x14]`, whose TOP halfword reads as Thumb
                // `push {r0,r2,r3,r4,r7,lr}` AND whose LOW halfword reads as a
                // Thumb `b` -- so the SAME instruction satisfied both the
                // prologue test and its is_preceded_by_terminator guard. Adding
                // a third byte test would be guessing against the same bytes.
                //
                // The symbol table is INDEPENDENT evidence and survives the
                // failure: lookup_is_thumb answers from dynsym ranges, not from
                // discovery, so it is unaffected by the extent truncation this
                // phantom causes. libmortargame carries 2,280 STT_FUNC dynsyms,
                // so coverage is real.
                //
                // WHY IT MATTERS SO MUCH: admitting the phantom TRUNCATES the
                // real enclosing function at that address. Ordinary
                // intra-function b/b<cond> branches then cross an artificial
                // boundary, get reclassified as external calls to unlifted
                // addresses, and bind to __macapk_panic_unresolved_helper -- and
                // because they are `b` TAIL branches, LR still points at the
                // original caller, so the helper's `return 0` silently becomes
                // THE ENCLOSING GUEST FUNCTION'S RETURN VALUE. That is how a
                // table-driven CRC-32 in this binary returned 0 while walking the
                // APK's zip central directory. 1,976 of 13,500 discovered extents
                // on this binary overlap the next one's start.
                if (is_thumb && (vaddr & 3) != 0) {
                    if (auto isa = lookup_is_thumb(vaddr); isa && !*isa) {
                        continue;   // inside ARM code: this is an instruction tail
                    }
                }
                // Filter false-positive prologues in literal-pool data.
                // The bytes-look-like-a-prologue test alone admits many
                // pointer-value entries (e.g. 0x001ab5b5 has a Thumb16
                // push pattern in its low halfword). Require the
                // preceding instruction to be a function terminator
                // (return / unconditional branch / tail-call). See
                // comment on `is_preceded_by_terminator` above for the
                // HCR 1.43 cascade this prevents.
                if (!is_preceded_by_terminator(vaddr, is_thumb)) {
                    continue;
                }
                // v1.0.59: PROVABLY-data check. A "prologue-looking"
                // byte pattern inside a literal pool is a false
                // positive (literal pool values can have any high
                // byte 0xb5/0xb4 etc. with positive probability).
                if (is_in_literal_pool(vaddr)) continue;
                // Symbol-table mode hint takes priority: if the gap
                // we landed in is bracketed by Thumb symbols, treat
                // the prologue as Thumb regardless of byte pattern.
                bool synth_is_thumb = is_thumb;
                if (auto hint = lookup_is_thumb(vaddr)) {
                    synth_is_thumb = *hint;
                }
                prologue_lifts.emplace_back(vaddr, synth_is_thumb);
            }
        }
        std::sort(prologue_lifts.begin(), prologue_lifts.end());
        prologue_lifts.erase(
            std::unique(prologue_lifts.begin(), prologue_lifts.end()),
            prologue_lifts.end());
        int prologue_lifted = 0;
        for (auto [vaddr, is_thumb] : prologue_lifts) {
            if (known_starts.count(vaddr)) continue;
            // Re-check inside-known-body since prior iterations of
            // this loop (or the BL/synth round above) may have
            // committed a synth whose body now covers this vaddr.
            if (inside_any_known_body(vaddr)) continue;
            // Pure-thrower override before lifting.
            if (emit_stub_synth(vaddr) || emit_veneer_synth(vaddr) || emit_path_remap_synth(vaddr) || emit_pure_thrower_synth(vaddr)) {
                known_starts.insert(vaddr);
                known_thumb[vaddr] = is_thumb;
                ++prologue_lifted;
                ++lifted;
                continue;
            }
            const auto* seg = exec_segment_for(vaddr);
            if (!seg) continue;
            uint32_t seg_end = seg->vaddr + seg->bytes.size();
            // v1.0.57: walk past bogus known_starts entries.
            // Resolve the mode BEFORE sizing: the extent walker needs to know
            // whether this is an ARM lift to apply the 4-alignment rule. Same
            // deterministic chain the lift uses, so the two always agree.
            const bool mode_arm_hint = [&]{
                if (auto h = lookup_is_thumb(vaddr)) return !*h;
                if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                  vaddr, 16)) return !*nb;
                return !sniff_thumb_at(vaddr);
            }();
            uint32_t end = next_real_function_start(
                anticipated_starts, vaddr, seg_end, mode_arm_hint);
            if (end <= vaddr) continue;
            uint32_t size = end - vaddr;
            const uint32_t size_raw = size;
            if (size > 0x4000) { ++g_size_clamp_hits; size = 0x4000; }
            size = arm_reach_trim(vaddr, size, size_raw, is_thumb);
            auto lf = lifter.lift_synthetic(img, vaddr, size, is_thumb);
            if (!lf.success) {
                if (verbose) {
                    std::fprintf(stderr,
                        "[translator] prologue-scan skip 0x%x (thumb=%d): %s\n",
                        vaddr, int(is_thumb), lf.error.c_str());
                }
                continue;
            }
            module_ir << lf.ir_body;
            known_starts.insert(vaddr);
            known_thumb[vaddr] = is_thumb;
            record_lifted_range(vaddr, size, lf.insns);
            ++prologue_lifted;
            ++lifted;
        }
        if (prologue_lifted > 0) {
            std::fprintf(stderr,
                "[translator] prologue-scan: lifted %d additional function(s)\n",
                prologue_lifted);
        }
        // The late (lifting) pass has its own rejections; they were counted but
        // never printed. Only with the gate on, so the =0 stderr is the old one.
        if (pro_scan_arm_context_on()) {
            std::fprintf(stderr,
                "[translator] prologue scan (late pass): %zu Thumb-push "
                "coincidence(s) in ARM context rejected "
                "(MACAPK_ARM32_PRO_SCAN_ARM_CONTEXT)\n",
                pro_scan_arm_context_rejects - pro_scan_arm_context_rejects_boundary);
        }
        // Self-describing: printed only when the gate is on, so the =0 arm's
        // stderr is unchanged too. -v lists the refused halfword addresses.
        if (arm32_thumb_parity_on()) {
            std::fprintf(stderr,
                "[translator] thumb-parity: refused %zu Thumb-16 terminator "
                "reading(s) of a 32-bit instruction's second halfword "
                "(MACAPK_ARM32_THUMB_PARITY)\n",
                g_thumb_parity_refused.size());
            if (verbose)
                for (uint32_t a : g_thumb_parity_refused)
                    std::fprintf(stderr, "[translator] thumb-parity refused @0x%x\n", a);
        }
        // One more BL/synth pass in case the new prologue-discovered
        // bodies introduced fresh BL targets we hadn't seen yet.
        for (int round = 0; round < kBlRounds2; ++round) {
            std::vector<uint32_t> to_lift;
            for (uint32_t t : lifter.external_targets()) {
                if (known_starts.count(t)) continue;
                if (round >= 4 && reach_extent_on && !arm_code_at(t)) continue;  // extra rounds: ARM code only
                if (plt_addrs.count(t))    continue;
                if (!exec_segment_for(t))  continue;
                // Same in-body filter as the first BL/synth pass,
                // including the authoritative-BL-target carve-out: a
                // heuristic synth that absorbed a real prologue-bearing
                // function entry must not suppress that entry's own
                // lift (else the `bl` resolves to a return-0 panic
                // stub). Symboled bodies stay authoritative.
                // v1.0.838: ...unless a cross-function `b` from a DIFFERENT
                // ARM-mode symbol proves `t` is a shared tail entry (libgcc
                // soft-float code sharing). Default OFF; see
                // shared_tail_entries' definition for the proof and the
                // measured selection set.
                if (inside_known_body(t) && !is_shared_tail_entry(t)) continue;
                // v1.0.533 (Bug-3): also KEEP a prologue-less target that is
                // PRECEDED BY A TERMINATOR -- a real tail-called leaf the lifter
                // absorbed into a neighbor (HCR: 0x20f7b0 `ldr..str..bx lr` after a
                // `pop {..,pc}`; 0x20fd5c cmp-guard after a `b`). The v1.0.28
                // intra-function-`b`-leak hazard is MID-function (not terminator-
                // preceded) so it stays dropped. Render-sweep-gated.
                if (inside_lifted_body(t) && !is_authoritative_bl_target(t)
                    && !looks_like_fn_prologue(t)
                    && !is_preceded_by_terminator_in_context(t))
                    continue;
                // v1.0.59: PROVABLY-data check.
                // v1.0.531: authoritative-bl-target + prologue overrides the
                // blind pool-scan false positive (see the first BL/synth pass).
                // ...and ALSO unless .ARM.exidx names it a function start.
                // The prologue clause cannot rescue a LEAF (no push at all),
                // and the terminator clause cannot either when a genuine pool
                // word sits between the previous function's `pop {..,pc}` and
                // this entry -- exactly the layout here. So a leaf that
                // follows a literal pool is unrescuable by either heuristic,
                // which is why this bug class keeps recurring. exidx is the
                // linker's own function-boundary table and the only complete
                // one that survives stripping: when it and bl-provenance
                // agree, a blind pool sweep is simply wrong.
                //
                // HCR 1.43 0x204704: ldr ip,[r1,#0x14] / ldr r2,[r1,#0x18] /
                // str ip,[r0,#0x14] / str r2,[r0,#0x18] / bx lr -- exidx entry
                // #4749, preceded by pool word 0x00177014, itself preceded by
                // `pop {r3,r4,r5,pc}`. It was the ONLY unresolved call that
                // actually executed; stubbing it to 0 killed the cocos2d-x
                // SAX parser's init chain, so nativeInit never completed and
                // the app rendered nothing.
                // v1.0.825: ...and ALSO unless the pool mark came ONLY from
                // the THUMB scan while the bytes are an unmistakable ARM
                // `push {..}` entry. Both carve-outs above require
                // is_authoritative_bl_target, so neither can rescue an
                // ADDRESS-TAKEN function -- one reached through a literal-pool
                // fnptr and an indirect call rather than by `bl`. That is
                // exactly the shape that stayed broken: HCR 1.8.1's
                // MainMenuCarLayer::create() at 0xde310 (arm=0 thumb=1) was
                // stubbed to 0, so MainMenuCarLayer::scene() added a NULL child
                // and every gameplay stage rendered black.
                if (is_in_literal_pool(t)
                    && !(is_authoritative_bl_target(t) &&
                         (looks_like_fn_prologue(t) ||
                          img.exidx_fn_starts.count(t) ||
                          // v1.0.828: ...or the mark is THUMB-ONLY. A literal
                          // pool word is never the target of a `bl` in correct
                          // code, so BL provenance plus "the ARM pool -- the
                          // only one ARM literal loads actually dereference --
                          // does not contain it" is already proof this is code.
                          // Requiring a prologue on top of that cannot see a
                          // LEAF, and exidx merges small leaves into a
                          // neighbour's entry, so both existing clauses miss the
                          // commonest shape in C++ output: a trivial
                          // operator=/getter/forwarder bounded by `bx lr` with
                          // no `push` at all. HCR 1.68.1 0x4ff0e8 is a 12-byte
                          // copy-assign with a self-assignment check; it
                          // panic-stubbed to 0, and the caller then printf'd a
                          // guest vaddr as a host pointer and dispatched through
                          // a NULL object (this=0x464c457f = "\x7fELF" at guest
                          // vaddr 0).
                          // v1.0.833: gated so the escape can be A/B'd.
                          (pool_escape_828_enabled() &&
                           !is_in_arm_literal_pool(t))))
                    && !(!is_in_arm_literal_pool(t) &&
                         looks_like_arm_push_prologue(t)))
                    continue;
                to_lift.push_back(t);
            }
            if (to_lift.empty()) break;
            std::sort(to_lift.begin(), to_lift.end());
            to_lift.erase(std::unique(to_lift.begin(), to_lift.end()),
                          to_lift.end());
            for (size_t ti = 0; ti < to_lift.size(); ++ti) {
                uint32_t vaddr = to_lift[ti];
                if (known_starts.count(vaddr)) continue;
                // v1.0.838: same shared-tail-entry carve-out as the filter pass.
                if (inside_known_body(vaddr) && !is_shared_tail_entry(vaddr))
                    continue;
                if (inside_lifted_body(vaddr) &&
                    !is_authoritative_bl_target(vaddr) &&
                    !looks_like_fn_prologue(vaddr) &&
                    !is_preceded_by_terminator_in_context(vaddr)) continue;  // v1.0.533 Bug-3
                // Pure-thrower override before lifting.
                if (emit_stub_synth(vaddr) || emit_veneer_synth(vaddr) || emit_path_remap_synth(vaddr) || emit_pure_thrower_synth(vaddr)) {
                    known_starts.insert(vaddr);
                    ++discovered;
                    ++lifted;
                    continue;
                }
                const auto* seg = exec_segment_for(vaddr);
                if (!seg) continue;
                uint32_t seg_end = seg->vaddr + seg->bytes.size();
                // v1.0.57: walk past bogus known_starts entries.
                // Resolve the mode BEFORE sizing: the extent walker needs to know
                // whether this is an ARM lift to apply the 4-alignment rule. Same
                // deterministic chain the lift uses, so the two always agree.
                const bool mode_arm_hint = [&]{
                    if (auto h = lookup_is_thumb(vaddr)) return !*h;
                    if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                      vaddr, 16)) return !*nb;
                    return !sniff_thumb_at(vaddr);
                }();
                uint32_t end = next_real_function_start(
                    anticipated_starts, vaddr, seg_end, mode_arm_hint);
                // Tighten budget by the next pending candidate in
                // to_lift (sorted asc). See the matching comment in
                // BL/synth round 1. v1.0.57: walk past mid-function
                // candidates so they cannot truncate legitimate
                // siblings.
                size_t tj = ti + 1;
                while (tj < to_lift.size() &&
                       is_mid_function_candidate(to_lift[tj])) {
                    ++tj;
                }
                if (tj < to_lift.size() && to_lift[tj] < end) {
                    end = to_lift[tj];
                }
                if (end <= vaddr) continue;
                uint32_t size = end - vaddr;
                const uint32_t size_raw = size;
                if (size > 0x4000) { ++g_size_clamp_hits; size = 0x4000; }
                bool synth_is_thumb;
                bool have_hint = false;
                if (auto hint = lookup_is_thumb(vaddr)) {
                    synth_is_thumb = *hint;
                    have_hint = true;
                } else if (auto nearby = nearest_known_thumb(
                        known_starts, known_thumb, vaddr, 16)) {
                    synth_is_thumb = *nearby;
                    have_hint = true;
                } else {
                    synth_is_thumb = sniff_thumb_at(vaddr);
                }
                size = arm_reach_trim(vaddr, size, size_raw, synth_is_thumb);
                auto lf = lifter.lift_synthetic(img, vaddr, size, synth_is_thumb);
                // Mode retry: sniff_thumb_at only recognizes Thumb-16 prologue
                // patterns (b5XX, b4XX, b0XX, afXX) and returns false for
                // everything else, including a Thumb-2 32-bit instruction at
                // function entry. Stripped libstdc++ helpers that begin with
                // `movw rX, #imm` (e.g. the libstdc++ pre-C++11 string
                // `_S_create` size-bounds check before the push prologue) end
                // up incorrectly tagged ARM, Capstone's ARM-mode disasm
                // returns 0 instructions, and the resulting unresolved
                // panic stub silently corrupts callers' return values. When
                // we have no authoritative hint (no symbol body covers this
                // address) and the first attempt failed, try the other mode
                // before giving up. The retry is gated on `!have_hint` so we
                // never override a symbol-table-derived mode.
                if (!lf.success && !have_hint) {
                    auto retry = lifter.lift_synthetic(
                        img, vaddr, size, !synth_is_thumb);
                    if (retry.success) {
                        lf = std::move(retry);
                        synth_is_thumb = !synth_is_thumb;
                    }
                }
                if (!lf.success) continue;
                module_ir << lf.ir_body;
                known_starts.insert(vaddr);
                known_thumb[vaddr] = synth_is_thumb;
                record_lifted_range(vaddr, size, lf.insns);
                ++discovered;
                ++lifted;
            }
        }
        // Self-describing; with =0 nothing is printed, so that arm's stderr is the old one.
        if (reach_extent_on) {
            std::fprintf(stderr,
                "[translator] reach-extent: %zu ARM function(s) bounded by "
                "reachability (-%zu bytes of over-extent), %zu proven LARGER "
                "than the 0x4000 clamp, %zu left at the old extent (indirect "
                "jump / ran off it), %zu kept at the .ARM.exidx bound (landing pads), "
                "%zu not trimmed (inside an EH-bearing exidx entry) "
                "(MACAPK_ARM32_REACH_EXTENT)\n",
                reach_trim_fns, reach_trim_bytes, reach_ext_fns, reach_bail_fns,
                reach_eh_floor, reach_inside_extab);
        }
        // DIAGNOSTIC: MACAPK_TRACE_DISCOVERY=<hex>[,<hex>...] -- for each
        // watched guest vaddr, dump the FINAL discovery verdict + every
        // predicate that gates lifting it. Reusable for any "function never
        // lifted -> bl resolves to panic-stub-returns-0 -> silent caller
        // corruption" hunt (the class behind the cocos2d-x ioapi-fnptr gap
        // and the HCR streambuf corruption). Here the discovery lambdas are
        // in scope and known_starts / lifted_synth_ranges / the literal-pool
        // sets are final. Default off.
        if (const char* td = std::getenv("MACAPK_TRACE_DISCOVERY")) {
            std::string s(td);
            size_t p = 0;
            while (p < s.size()) {
                size_t c = s.find(',', p);
                std::string tok = s.substr(p, c == std::string::npos
                                              ? std::string::npos : c - p);
                if (!tok.empty()) {
                    uint32_t a  = (uint32_t)std::strtoul(tok.c_str(), nullptr, 16);
                    uint32_t ac = a & ~1u;
                    std::fprintf(stderr,
                        "[discovery-trace] 0x%x: lifted=%d in_extern_targets=%d "
                        "(|1=%d) in_extern_bl_targets=%d (|1=%d) "
                        "is_in_literal_pool=%d (arm=%d thumb=%d) inside_known_body=%d "
                        "inside_lifted_body=%d looks_like_fn_prologue=%d "
                        "is_heuristic_pic_only=%d is_provably_mid_instruction=%d\n",
                        ac,
                        (int)known_starts.count(ac),
                        (int)lifter.external_targets().count(ac),
                        (int)lifter.external_targets().count(ac | 1u),
                        (int)lifter.external_bl_targets().count(ac),
                        (int)lifter.external_bl_targets().count(ac | 1u),
                        (int)is_in_literal_pool(ac),
                        // Split the two sets: the THUMB set comes from a blind
                        // 2-byte scan and false-marks ARM code (v1.0.552), so
                        // "pool" alone does not say which one vetoed.
                        (int)is_in_arm_literal_pool(ac),
                        (int)(literal_pool_bytes_thumb.count(ac) ||
                              literal_pool_bytes_thumb.count(ac & ~3u)),
                        (int)inside_known_body(ac),
                        (int)inside_lifted_body(ac),
                        (int)looks_like_fn_prologue(ac),
                        // v1.0.806
                        (int)(heuristic_pic_set.count(ac) ||
                              heuristic_pic_set.count(ac | 1u)),
                        (int)is_provably_mid_instruction(ac));
                }
                if (c == std::string::npos) break;
                p = c + 1;
            }
        }

        // -----------------------------------------------------------------
        // arm32rc: the direct ARM32 -> arm64 recompiler.
        //
        // Hooked HERE, at the end of discovery, on purpose. Discovery is
        // where most of this pipeline's accumulated value lives (the
        // Thumb-mode picker, the literal-pool scan, the synth cascade
        // filters, the exidx and prologue seeds); the recompiler replaces
        // CODEGEN only, so it consumes exactly the function set the
        // incumbent would have lifted. That is also what makes a
        // per-function A/B meaningful.
        //
        // `known_thumb` and the discovery lambdas are still in scope at
        // this point and go out of scope at the closing brace below,
        // which is why the hook is inside the block rather than after it.
        //
        // `if constexpr` because run_pipeline is a template shared with the
        // arm64 (Stage 6) path, whose Image type is Elf64Image; the
        // recompiler is an ARM32-only stage and must not be instantiated
        // for the 64-bit width at all.
        if constexpr (GprBits == 32) {
        // v1.0.802: arm32rc is the ONLY ARM32 codegen backend. The
        // MACAPK_ARM32RC opt-in gate is gone along with the LLVM path it used
        // to select between; this block always runs for a 32-bit image and
        // always returns, so nothing below it is reachable on the ARM32 path.
        {
            namespace rcd = macapk::translator::arm32rc;
            std::vector<uint32_t> starts(known_starts.begin(),
                                         known_starts.end());
            std::sort(starts.begin(), starts.end());
            // PT_LOAD end. Kept ONLY as the fallback for a fully
            // section-stripped image; see section_end_for below for why
            // it must not be the primary bound.
            auto seg_end_for = [&](uint32_t v) -> uint32_t {
                for (const auto& s : img.loadable)
                    if (!s.bytes.empty() && v >= s.vaddr &&
                        v < s.vaddr + s.bytes.size())
                        return static_cast<uint32_t>(s.vaddr + s.bytes.size());
                return 0;
            };
            // The upper bound a function may extend to is the end of the
            // SECTION that contains it, not the end of the PT_LOAD.
            //
            // On every ARM32 Android .so we ship, the read-only PT_LOAD
            // holds .text AND .rodata AND .ARM.extab AND .ARM.exidx, so
            // "clip to the PT_LOAD end" lets the LAST discovered function
            // run off the end of .text and swallow everything after it:
            //
            //   HCR    .text ends 0x292000, exec PT_LOAD ends 0x2e7b0c
            //          -> last fn at 0x291ff0 absorbed 351,004 bytes
            //   JJ     .text ends 0x4a1a4c, PT_LOAD ends 0x4e4154
            //          -> 271,624 bytes
            //   Flappy .text ends 0x260c,   PT_LOAD ends 0x2750
            //          -> 324 bytes
            //
            // Those bytes are then ARM-DECODED and arm64 is EMITTED for
            // them: 87,751 phantom instructions on HCR, roughly 1 MB of
            // output text, and 86.8% of the apparent unsupported-
            // instruction histogram (svc<cond> / stcl / cdp / mcr are
            // float constant pools, and `andeq r0,r0,r0` is the word
            // 0x00000000). Measured in
            // docs/arm32rc-integration_2026-07-25.md, "Coverage (lane 4)".
            //
            // SHF_ALLOC (0x2) selects mapped sections; SHT_NOBITS (8) is
            // skipped because .bss has no file bytes to decode. When two
            // allocated sections overlap we take the TIGHTEST end, which
            // is the conservative choice for an upper bound.
            const bool have_sections = [&]() {
                for (const auto& sh : img.shdrs)
                    if ((sh.sh_flags & 0x2u) && sh.sh_type != 8u &&
                        sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto section_end_for = [&](uint32_t v) -> uint32_t {
                uint32_t best = 0;
                if (have_sections) {
                    for (const auto& sh : img.shdrs) {
                        if (!(sh.sh_flags & 0x2u)) continue;   // SHF_ALLOC
                        if (sh.sh_type == 8u) continue;        // SHT_NOBITS
                        if (sh.sh_size == 0) continue;
                        if (v < sh.sh_addr) continue;
                        if (v >= sh.sh_addr + sh.sh_size) continue;
                        const uint32_t e = sh.sh_addr + sh.sh_size;
                        if (best == 0 || e < best) best = e;
                    }
                }
                // Stripped section table, or an address no allocated
                // section covers: fall back to the old PT_LOAD bound so
                // discovery output is never silently dropped.
                if (best == 0) best = seg_end_for(v);
                return best;
            };
            // Is this vaddr inside a real CODE section (SHF_EXECINSTR)?
            //
            // The first PT_LOAD of an Android .so covers the ELF header,
            // .dynsym, .hash and .rel as well as .text, and they are all
            // R+X at the SEGMENT level. So any "is this executable" test
            // written against segments happily accepts symbol-table bytes as
            // code, and discovery then invents functions inside .dynsym.
            // Disassembling a symbol table as Thumb yields plausible-looking
            // BL instructions whose targets are garbage, and every one of
            // those becomes a panic-stubbed call site.
            //
            // Measured on HCR 1.43 (65% Thumb): a phantom function at
            // 0x8a5e -- which is inside .dynsym (0x114..0xad58) -- emitted
            // BLs to 0x00448b12, 0xfed48cb2, 0xfe108ec6 and friends: correct
            // low halfword, garbage high halfword, i.e. exactly what decoding
            // a symbol table as code produces.
            //
            // Falls back to accepting when the section table is stripped or
            // carries no executable sections, so a stripped image never has
            // its whole discovery output dropped. Same shape as
            // elf_reader.cpp's is_code_vaddr (v553), which already applies
            // this rule to relocation-derived function-pointer seeds -- this
            // is the same rule for the START list.
            const bool have_code_sections = [&]() {
                if (!have_sections) return false;
                for (const auto& sh : img.shdrs)
                    if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u) &&
                        sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto in_code_section = [&](uint32_t v) -> bool {
                if (!have_code_sections) return true;   // stripped: accept
                for (const auto& sh : img.shdrs) {
                    if (!(sh.sh_flags & 0x4u)) continue;   // SHF_EXECINSTR
                    if (!(sh.sh_flags & 0x2u)) continue;   // SHF_ALLOC
                    if (v < sh.sh_addr) continue;
                    if (v >= sh.sh_addr + sh.sh_size) continue;
                    return true;
                }
                return false;
            };
            // Symbol-table extents. For a symboled function the incumbent
            // hands the lifter exactly `sym.size` bytes
            // (Elf32Lifter::lift_function -> Elf32Image::code_for ->
            // code_at(addr, sym.size)), so anything past that is padding
            // the incumbent never decoded. Reproducing that here is what
            // keeps a per-function A/B comparing the SAME program.
            // Iterated in symbol-table order with last-wins, matching how
            // lifted_address_map() ends up keyed when two symbols alias
            // one address.
            std::unordered_map<uint32_t, uint32_t> symbol_extent;
            for (const auto& s : img.symbols) {
                if (s.type != pt::STT_FUNC) continue;
                if (s.size == 0) continue;
                symbol_extent[static_cast<uint32_t>(s.address & ~1u)] =
                    s.size;
            }
            // MACAPK_ARM32RC_LEGACY_EXTENT was removed in v1.0.779. It
            // reproduced the pre-contract extent rule so one binary could
            // produce both arms of the A/B, and its own comment said to
            // delete it once the numbers were recorded. They are: the
            // census in docs/arm32rc-address-wrap-invariant_2026-07-26.md
            // measures 0 walked-Translate and 0 capped on HCR, JJ and
            // Flappy, so it had no live diagnostic use left.
            //
            // It was deleted rather than left dormant because it is an env
            // var that re-enables a KNOWN-DEFECTIVE codegen rule, and the
            // lifted-dylib cache is EXISTENCE-based: one stray run leaves a
            // defective cached dylib on disk that every later run silently
            // reuses. That is a measurement-poisoning footgun of exactly the
            // kind that has cost this project several sessions. `git show`
            // is the reproducible way to get the old arm back.
            const auto& amap = lifter.lifted_address_map();
            std::vector<pt::DiscoveredFunction> rfuncs;
            rfuncs.reserve(starts.size());
            size_t n_sym_extent = 0, n_budget_extent = 0, n_walk_extent = 0;
            size_t n_capped = 0, n_walk_translate = 0;
            size_t n_noncode = 0;   // starts rejected: not in a code section
            size_t n_translate = 0, n_shim = 0, n_const = 0, n_veneer = 0;
            size_t n_jni = 0;
            for (size_t i = 0; i < starts.size(); ++i) {
                const uint32_t v = starts[i];
                auto nm = amap.find(v);
                // Not in the address map = discovery never emitted a body
                // here (a lift that failed never reaches
                // lifted_addr_to_name, which is assigned on the success
                // path only). Emitting one would be a body the incumbent
                // does not have.
                if (nm == amap.end()) continue;
                const uint32_t sec_end = section_end_for(v);
                if (sec_end == 0 || sec_end <= v) continue;
                // Reject phantom starts that landed in a non-code section
                // (.dynsym / .hash / .rel are all inside the same R+X
                // PT_LOAD). Counted rather than silently dropped so the
                // stats line shows how much junk discovery handed us.
                if (!in_code_section(v)) { ++n_noncode; continue; }

                // MACAPK_ARM32_DROP_VADDRS=<hex[,hex...]> -- DIAGNOSTIC ONLY,
                // default inert. Suppress a discovered function by address, the
                // mirror of MACAPK_FORCE_LIFT_VADDRS (which adds one). A dropped
                // address emits no body, so anything calling it gets the usual
                // panic stub -- which is the point: it answers "is this
                // fabricated function on the live path?" in one run.
                //
                // WHY. Discovery can fabricate a Thumb "function" at an address
                // interior to a real ARM instruction, and the cascade then
                // discovers ITS branch targets, so one bad seed manufactures a
                // family. Measured on Jetpack Joyride 1.3.5 (libmortargame.so):
                // 0x45468e is 2 bytes into `ldrlt lr, [sp, #0x14]` at 0x45468c,
                // inside the ARM function 0x454518, yet is emitted as
                // __macapk_synth_0045468e.
                //
                // It is placed HERE, at the final function-list assembly, and
                // NOT at the init_seed_to_lift filter, because these bodies
                // arrive via the synth path and never pass through that list --
                // a drop applied there compiles, logs, and silently does
                // nothing, which is the dead-instrument trap.
                //
                // Not per-APK code: it reads an address from the environment and
                // knows nothing about any package. Same category as
                // MACAPK_FORCE_LIFT_VADDRS and MACAPK_TRACE_*.
                // docs/jetpack-shared-tail-and-null-engine_2026-08-27.md
                {
                    static const std::unordered_set<uint32_t> s_dropped = [] {
                        std::unordered_set<uint32_t> out;
                        const char* env = std::getenv("MACAPK_ARM32_DROP_VADDRS");
                        if (env && *env) {
                            const char* q = env;
                            while (*q) {
                                while (*q == ',' || *q == ' ' || *q == '\t') ++q;
                                if (!*q) break;
                                char* endp = nullptr;
                                unsigned long val = std::strtoul(q, &endp, 16);
                                if (endp == q) break;
                                q = endp;
                                out.insert(static_cast<uint32_t>(val) & ~1u);
                            }
                            std::fprintf(stderr,
                                "[translator] MACAPK_ARM32_DROP_VADDRS=%s parsed "
                                "%zu address(es)\n", env, out.size());
                        }
                        return out;
                    }();
                    if (!s_dropped.empty() && s_dropped.count(v)) {
                        std::fprintf(stderr,
                            "[translator] DROP_VADDRS: suppressing %s at 0x%x\n",
                            nm->second.c_str(), v);
                        continue;
                    }
                }

                pt::DiscoveredFunction f;
                f.vaddr = v;
                auto th = known_thumb.find(v);
                f.is_thumb = (th != known_thumb.end()) && th->second;
                f.symbol = nm->second;
                f.is_jni_export = looks_like_jni_export(f.symbol);
                if (f.is_jni_export) ++n_jni;

                // ---- extent -------------------------------------------
                // Three sources, in descending order of fidelity. All
                // three answer the SAME question -- how many bytes did
                // the incumbent hand to the decoder for this address --
                // because anything else makes a per-function A/B compare
                // two different programs.
                const bool is_synth_name =
                    nm->second.rfind("__macapk_synth_", 0) == 0;
                auto se = symbol_extent.find(v);
                auto sb = synth_lift_budget.find(v);
                if (!is_synth_name && se != symbol_extent.end()) {
                    // Symboled: lift_function passes Elf32Image::code_for,
                    // i.e. exactly sym.size bytes. Padding after it is
                    // bytes the incumbent never decoded.
                    f.size = se->second;
                    ++n_sym_extent;
                } else if (sb != synth_lift_budget.end()) {
                    // Synth: the budget its own lift really used. See the
                    // synth_lift_budget declaration for why re-deriving
                    // this from the final known_starts is wrong.
                    f.size = sb->second;
                    ++n_budget_extent;
                } else {
                    // No body was lifted here at all, so there is no
                    // budget to copy: an override, a veneer or a
                    // diagnostic stub, whose disposition below means the
                    // consumer never decodes these bytes. Fall back to
                    // the incumbent's synth RULE. NOT the raw next
                    // known_starts entry -- next_real_function_start
                    // walks PAST entries that landed inside a literal
                    // pool (the v1.0.57/59 fix); the raw form is what
                    // gave 21 Jetpack Joyride functions a 2-byte size, at
                    // which cs_disasm returns 0 and the driver drops the
                    // function so every caller lands on the panic helper.
                    // Resolve the mode BEFORE sizing: the extent walker needs to know
                    // whether this is an ARM lift to apply the 4-alignment rule. Same
                    // deterministic chain the lift uses, so the two always agree.
                    const bool mode_arm_hint = [&]{
                        if (auto h = lookup_is_thumb(v)) return !*h;
                        if (auto nb = nearest_known_thumb(known_starts, known_thumb,
                                                          v, 16)) return !*nb;
                        return !sniff_thumb_at(v);
                    }();
                    uint32_t end = next_real_function_start(
                        known_starts, v, sec_end, mode_arm_hint);
                    if (end > sec_end) end = sec_end;
                    if (end <= v) continue;
                    uint32_t size = end - v;
                    // Same 0x4000 ceiling the incumbent applies to every
                    // synthetic lift.
                    if (size > 0x4000) { size = 0x4000; ++n_capped; }
                    f.size = size;
                    ++n_walk_extent;
                    if (!discovery_dispositions.count(v)) ++n_walk_translate;
                }
                if (f.size == 0) continue;
                // The mandated SECTION clip, applied to every source. The
                // budget a synth was lifted with is itself PT_LOAD-bounded
                // (and 0x4000-capped), so HCR's last .text function still
                // reaches 16 KB into .rodata without this.
                if (v + f.size > sec_end) f.size = sec_end - v;

                // ---- disposition --------------------------------------
                // Recorded at the decision site during discovery; see the
                // DISCOVERY DISPOSITION SIDE TABLE comment above.
                auto dr = discovery_dispositions.find(v);
                if (dr != discovery_dispositions.end()) {
                    f.disposition     = dr->second.disposition;
                    f.override_symbol = dr->second.override_symbol;
                    f.override_const  = dr->second.override_const;
                    f.veneer_target   = dr->second.veneer_target;
                }
                switch (f.disposition) {
                    case pt::Disposition::Translate:     ++n_translate; break;
                    case pt::Disposition::OverrideShim:  ++n_shim;      break;
                    case pt::Disposition::OverrideConst: ++n_const;     break;
                    case pt::Disposition::Veneer:        ++n_veneer;    break;
                }
                rfuncs.push_back(f);
            }
            std::fprintf(stderr,
                "[arm32rc] discovery: %zu function(s) (extent: %zu from "
                "sym.size, %zu from the lift budget, %zu walked [%zu of "
                "them Translate], %zu capped at 0x4000, %zu REJECTED "
                "non-code)\n",
                rfuncs.size(), n_sym_extent, n_budget_extent, n_walk_extent,
                n_walk_translate, n_capped, n_noncode);
            std::fprintf(stderr,
                "[arm32rc] disposition: translate=%zu override_shim=%zu "
                "override_const=%zu veneer=%zu jni_export=%zu\n",
                n_translate, n_shim, n_const, n_veneer, n_jni);
            // v1.0.833: the REAL clamp count, from the five lift rounds.
            // `n_capped` above is a different, narrower number -- it belongs to
            // this reporting path, which compiles nothing. A body that lands on
            // the ceiling means discovery never found its end, so a non-zero
            // count here is a direct measure of how much data may be getting
            // lifted as code. Silent until now.
            if (g_size_clamp_hits) {
                std::fprintf(stderr,
                    "[arm32rc] size-ceiling: %lu function(s) hit the 0x4000 "
                    "clamp (no terminator found; suspect data-as-code -- "
                    "cross-check with debug/discovery_boundary_stats.py)\n",
                    g_size_clamp_hits);
            }

            // ---- discovery sidecar -----------------------------------
            // A 1:1 serialisation of macapk/arm32_discovery.h, consumed by
            // debug/arm32_sidecar.py. It exists because every ARM32
            // differential harness needs {vaddr, ISA mode, symbol,
            // disposition} per function and the ELF symbol table cannot
            // supply them: 2,131 of HCR's functions are `__macapk_synth_*`
            // with no STT_FUNC entry, so the harness had to GUESS their
            // mode (22.3% of the binary, and exactly where the historical
            // discovery bugs live), and disposition is not recoverable
            // from any artefact at all.
            //
            // Rows are written for every function HANDED to the driver,
            // including ones it later fails to translate: "in the
            // sidecar, absent from the dylib" is how the gate detects a
            // DROPPED function, so filtering here would hide the very
            // thing it measures. `size` is the FINAL post-clip byte
            // count, i.e. what was really decoded.
            {
                std::string sc = g_discovery_sidecar;
                if (sc.empty()) {
                    if (const char* e =
                            std::getenv("MACAPK_DISCOVERY_SIDECAR"))
                        sc = e;
                }
                if (sc == "1" || sc == "auto") sc = out_path + ".disc.tsv";
                if (!sc.empty() && sc != "0") {
                    // md5 via the system tool rather than CommonCrypto:
                    // CC_MD5 is deprecated as of macOS 26 and this file
                    // already shells out (see the nm read above).
                    std::string md5 = "?";
                    if (FILE* mf = popen(("md5 -q '" + in_path +
                                          "' 2>/dev/null").c_str(), "r")) {
                        char line[128];
                        if (std::fgets(line, sizeof(line), mf)) {
                            md5 = line;
                            while (!md5.empty() &&
                                   (md5.back() == '\n' || md5.back() == '\r'))
                                md5.pop_back();
                        }
                        pclose(mf);
                    }
                    std::string base = in_path;
                    if (auto s = base.find_last_of('/');
                        s != std::string::npos) base = base.substr(s + 1);
                    if (FILE* sf = std::fopen(sc.c_str(), "w")) {
                        std::fprintf(sf,
                            "# macapk-discovery v1 %s %s %zu\n",
                            base.c_str(), md5.c_str(), rfuncs.size());
                        std::fputs("vaddr\tsize\tthumb\tdisposition\tsymbol"
                                   "\toverride_symbol\toverride_const"
                                   "\tveneer_target\tis_jni_export\n", sf);
                        for (const auto& d : rfuncs) {
                            const char* dn = "Translate";
                            switch (d.disposition) {
                                case pt::Disposition::Translate:
                                    dn = "Translate"; break;
                                case pt::Disposition::OverrideShim:
                                    dn = "OverrideShim"; break;
                                case pt::Disposition::OverrideConst:
                                    dn = "OverrideConst"; break;
                                case pt::Disposition::Veneer:
                                    dn = "Veneer"; break;
                            }
                            std::fprintf(sf,
                                "%08x\t%08x\t%d\t%s\t%s\t%s\t%d\t%08x\t%d\n",
                                d.vaddr, d.size, d.is_thumb ? 1 : 0, dn,
                                d.symbol.c_str(),
                                d.override_symbol.empty()
                                    ? "-" : d.override_symbol.c_str(),
                                d.override_const, d.veneer_target,
                                d.is_jni_export ? 1 : 0);
                        }
                        std::fclose(sf);
                        std::fprintf(stderr,
                            "[arm32rc] discovery sidecar: %s (%zu rows)\n",
                            sc.c_str(), rfuncs.size());
                    } else {
                        std::fprintf(stderr,
                            "[arm32rc] WARN: cannot write discovery "
                            "sidecar %s: %s\n", sc.c_str(),
                            std::strerror(errno));
                    }
                }
            }

            if (n_shim + n_const + n_veneer !=
                discovery_dispositions.size()) {
                // Every recorded override MUST reach the consumer. If one
                // does not, the recompiler is about to translate a guest
                // body the incumbent replaces, which is the exact class of
                // bug DiscoveredFunction exists to prevent -- and it is
                // silent at runtime, so say it loudly here.
                std::fprintf(stderr,
                    "[arm32rc] WARN: %zu override(s) recorded by discovery "
                    "but only %zu reached the driver\n",
                    discovery_dispositions.size(),
                    n_shim + n_const + n_veneer);
            }

            // ----------------------------------------------------------
            // Cross-guest-library linking (2026-07-28). See
            // docs/cross-guest-library-linking_2026-07-28.md for the
            // design + the empirical dyld verification behind it. When
            // THIS .so calls a function a SIBLING guest .so in the same
            // app genuinely implements (Subway Surfers' Firebase
            // Analytics/Crashlytics/Messaging calling into
            // libFirebaseCppApp), resolve_import below could never see
            // it: its namespace is nm -gU on --shim-dir's own dylibs +
            // macapk-runtime, plus dlsym(RTLD_DEFAULT) inside the
            // macapk-translator PROCESS ITSELF, which has never dlopen'd
            // anything app-specific.
            //
            // Scanning the SIBLING'S ELF (never its Mach-O -- that file
            // may not exist yet) is what makes this order-independent: a
            // sibling's exported dynsym names exist from APK-extraction
            // time, before ANY translator has run, so it does not matter
            // that AppClassLoader.preloadAllApkLibs() translates every
            // .so in an app CONCURRENTLY with an explicit "no data
            // dependency on any other lib's translation" assumption.
            //
            // MACAPK_ARM32RC_SIBLING_LINK=0 disables this tier entirely,
            // reverting byte-for-byte to the panic-stub behavior these
            // names had before -- same escape-hatch precedent as
            // MACAPK_ARM32RC_PLT_LOCAL (arm32rc_driver.cpp).
            static const bool sibling_link_on = [](){
                const char* e = std::getenv("MACAPK_ARM32RC_SIBLING_LINK");
                return !(e && *e == '0');
            }();
            // name -> providing sibling's .so basename (e.g.
            // "libFirebaseCppApp-12_10_1.so"). Built once per translate
            // invocation (this process handles exactly one --in file).
            std::unordered_map<std::string, std::string> sibling_provides;
            if (sibling_link_on) {
                const std::string abi_dir = dirname_of(in_path);
                const std::string my_base = basename_of(in_path);
                std::vector<std::string> siblings;
                if (DIR* d = opendir(abi_dir.c_str())) {
                    while (struct dirent* e = readdir(d)) {
                        const std::string nm = e->d_name;
                        if (nm.size() > 6 && nm.compare(0, 3, "lib") == 0 &&
                            nm.compare(nm.size() - 3, 3, ".so") == 0 &&
                            nm != my_base)
                            siblings.push_back(nm);
                    }
                    closedir(d);
                }
                // Sorted so a duplicate export across two siblings (a
                // shared weak/template C++ symbol statically linked into
                // more than one plugin .so -- plausible, harmless, same
                // class of thing flat-namespace linking already tolerates
                // for libstdc++ symbols today) resolves deterministically
                // and is logged rather than silently arbitrary.
                std::sort(siblings.begin(), siblings.end());
                for (const auto& sib_name : siblings) {
                    for (auto& nm : read_elf32_dynamic_export_names(
                             abi_dir + "/" + sib_name)) {
                        auto ins = sibling_provides.emplace(std::move(nm),
                                                             sib_name);
                        if (!ins.second && ins.first->second != sib_name) {
                            std::fprintf(stderr,
                                "[arm32rc] NOTE: '%s' is exported by BOTH "
                                "%s and %s; keeping the first (%s)\n",
                                ins.first->first.c_str(),
                                ins.first->second.c_str(), sib_name.c_str(),
                                ins.first->second.c_str());
                        }
                    }
                }
                if (verbose && !sibling_provides.empty()) {
                    std::fprintf(stderr,
                        "[arm32rc] cross-guest-library: %zu sibling .so "
                        "export name(s) available from %zu sibling(s) in "
                        "%s\n",
                        sibling_provides.size(), siblings.size(),
                        abi_dir.c_str());
                }
            }

            rcd::RcOptions ropts;
            // MACAPK_ARM32RC_POOL_RESYNC's data oracle: the same literal-pool
            // byte set the seed-discovery veto already computes. ⚠ This is a
            // BLIND heuristic byte scan (any Thumb halfword matching the
            // 0x48xx-0x4fxx LDR-literal encoding marks 4 bytes as "pool"), not
            // a proof: it also fires on the second halfword of an ordinary
            // 32-bit Thumb-2 instruction, so a caller must not treat
            // membership as "this address is definitely data" -- see the
            // POOL_RESYNC branch-source filter in arm32rc_driver.cpp, added
            // after it miscompiled two gdlite-v7a libraries by trusting a
            // branch decoded from such a false-positive byte
            // (docs/arm32-exidx-confirmed-seeds_2026-09-28.md).
            ropts.is_literal_pool_byte = [&](uint32_t v) -> bool {
                return literal_pool_bytes_thumb.count(v) != 0
                    || literal_pool_bytes_arm.count(v) != 0;
            };
            // Same import-routing rule as the LLVM path below, from the
            // same sets, so the two cannot drift. Only the pointer-aware
            // and soft-float families go through __macapk_shim_<name>;
            // everything else binds the BARE name and resolves against
            // libSystem. Getting this wrong is not a runtime bug but a
            // hard dlopen failure, because macho_link binds non-lazily:
            // HCR died at load with "symbol not found in flat namespace
            // '___macapk_shim___stack_chk_fail'" when the driver assumed
            // every import was shimmed.
            //
            // ...and then VERIFY, because the rule alone is not enough.
            // A name can sit in kPointerAwareImports with no
            // __macapk_shim_<name> behind it: that happened to the ten
            // POSIX names added in 322fb64, nine of which (mkdir,
            // remove, rename, getcwd, write, writev, poll, ioctl,
            // fdopen) had no body until the bionic_shim block that now
            // implements them. The LLVM path hides such a gap because
            // it binds LAZILY -- a shim that is missing but never
            // called is invisible, and one that IS called kills the
            // process at the call site rather than at load. macho_link
            // binds NON-LAZILY by design, so the same gap is a hard
            // dlopen failure. This check is what turns either failure
            // into a translate-time line. Where the shim does not
            // actually export the wrapper we fall back to the bare
            // name, which resolves against libSystem exactly as the
            // LLVM path's call would have if it ever reached one.
            //
            // This is also the "missing shims are silent killers"
            // diagnostic (CLAUDE.md) surfaced at TRANSLATE time instead
            // of as a mystery crash.
            //
            // ...and the bare-name fallback needs the SAME verification,
            // for the same reason. `sincosf` is in none of the three sets
            // above, so it fell through to the bare name -- and macOS
            // exports only `__sincosf_stret`, so nothing answered it and
            // Bouncy's libgdx-box2d failed to dlopen outright with
            // "symbol not found in flat namespace '_sincosf'". A name
            // that resolves NOWHERE must go to the panic stub instead:
            // never called, it costs nothing; called, it says which
            // symbol is missing, which is the whole point of the
            // "missing shims are silent killers" diagnostic.
            //
            // The oracle for "does it resolve" has to model the flat
            // namespace the output dylib actually lands in, which is
            // macapk-runtime's: every dylib next to the shim (the shim
            // itself, ANGLE's libGLESv2/libEGL, MoltenVK, vulkan) plus
            // the runtime binary, plus the host libraries. A plain
            // dlsym(RTLD_DEFAULT) in THIS process is not that namespace
            // (measured: `glDrawArrays` and `inflate` both probe 0 here
            // and both resolve fine at runtime), so both halves are
            // needed and the host half dlopens the umbrella libraries
            // first.
            static std::unordered_set<std::string> shim_exports;
            static bool shim_exports_read = false;
            if (!shim_exports_read) {
                shim_exports_read = true;
                load_runtime_namespace_exports(shim_dir, &shim_exports);
                // The host half. dlopen is idempotent for an already-
                // loaded image, so this just widens RTLD_DEFAULT to the
                // umbrellas a guest .so can legitimately import from.
                for (const char* lib : {"/usr/lib/libSystem.B.dylib",
                                        "/usr/lib/libz.1.dylib",
                                        "/usr/lib/libc++.1.dylib",
                                        "/usr/lib/libobjc.A.dylib"})
                    dlopen(lib, RTLD_LAZY | RTLD_GLOBAL);
                std::fprintf(stderr,
                    "[arm32rc] runtime-namespace exports read: %zu "
                    "symbol(s) from %s/*.dylib + macapk-runtime\n",
                    shim_exports.size(), shim_dir.c_str());
            }
            static size_t n_panic_imports = 0;
            // The tier decision, factored out of resolve_import with its
            // outputs unchanged so the data-import link can ask "would we
            // answer this name as an Android system-library function?"
            // without resolve_import's per-call-site side effects (the WARN
            // line and the panic-import count). Order and rules are exactly
            // resolve_import's: shim wrapper, runtime namespace, host dlsym,
            // sibling ELF, nothing.
            using Tier = rcd::RcOptions::ImportTier;
            auto tier_of = [&sibling_provides](const std::string& nm,
                                               std::string* host,
                                               std::string* missing_shim) -> Tier {
                // Lane arm32-shim-gaps: a gated gap import binds its own twin
                // (arm32_gap_shim); with its gate =0 `gap` is empty and the
                // decision below is exactly the old one.
                const std::string gap = arm32_gap_shim(nm);
                if (!gap.empty() ||
                    kPointerAwareImports.count(nm) ||
                    is_arm32_env_writer_import(nm) ||
                    is_arm32_rwlock_import(nm) ||
                    is_softfloat_double_import(nm) ||
                    kSoftFloatFloatImports.count(nm)) {
                    // MACAPK_ARM32_SOFTFP_ATOF: atof/strtod bind their
                    // i64-returning _softfp twins (see the gate's comment).
                    const std::string s = !gap.empty() ? gap
                        : "__macapk_shim_" + nm
                          + (is_softfp_parse_double(nm) ? "_softfp" : "");
                    if (shim_exports.empty() || shim_exports.count(s)) {
                        *host = s;
                        return Tier::Shim;
                    }
                    if (missing_shim) *missing_shim = s;
                }
                if (shim_exports.empty() || shim_exports.count(nm)) {
                    *host = nm;
                    return Tier::RuntimeNs;
                }
                if (dlsym(RTLD_DEFAULT, nm.c_str()) != nullptr) {
                    *host = nm;
                    return Tier::HostDlsym;
                }
                if (sibling_provides.count(nm)) {
                    *host = nm;
                    return Tier::Sibling;
                }
                host->clear();
                return Tier::None;
            };
            ropts.import_tier = [tier_of](const std::string& nm,
                                          std::string* host) -> Tier {
                return tier_of(nm, host, nullptr);
            };
            ropts.resolve_import =
                    [tier_of](const std::string& nm) -> std::string {
                // Shim wrapper, then the runtime namespace, then host dlsym,
                // then a sibling ELF: tier_of above, whose decision this
                // returns unchanged. A pointer-aware name whose
                // __macapk_shim_ wrapper is missing is reported here and
                // falls through to the bare-name tiers (the LLVM path hid
                // that behind lazy binding).
                std::string host, missing;
                const Tier t = tier_of(nm, &host, &missing);
                if (!missing.empty())
                    std::fprintf(stderr,
                        "[arm32rc] NOTE: no %s in the shim; binding the bare "
                        "'%s' (the LLVM path hides this behind lazy "
                        "binding)\n", missing.c_str(), nm.c_str());
                if (t == Tier::Shim || t == Tier::RuntimeNs ||
                    t == Tier::HostDlsym)
                    return host;
                // Cross-guest-library linking (2026-07-28): a sibling
                // guest .so translated for this same app genuinely
                // exports this exact name -- verified from ITS OWN ELF
                // dynsym table above, never guessed. Route it through the
                // SAME flat-lookup bind path as any other unshimmed name
                // (deliberately no new binding mechanism); the caller
                // (below, before build_module runs) has already added
                // that sibling as a real LC_LOAD_DYLIB dependency, so
                // dyld loads and fully constructs it before this image's
                // own binds run, regardless of dlopen order. No printf
                // here on purpose -- resolve_import is invoked once per
                // CALL SITE, not once per distinct name, and a hot import
                // (firebase::Mutex::Acquire, say) would otherwise repeat
                // the same line for every site; see the ONE summary line
                // emitted after this .so's full import list is scanned.
                if (t == Tier::Sibling) return host;
                // NOT routed to __macapk_arm64_<nm> even when the shim
                // exports one. Those are STAGE-6 shims and speak the HOST
                // AAPCS64 ABI, while an ARM32 caller is Android
                // armeabi-v7a softfp: `__macapk_arm64_sincosf(float x,
                // float* s, float* c)` wants x in s0 and two 64-bit HOST
                // pointers in x0/x1, and the recompiled guest supplies
                // the float's BIT PATTERN in w0 and two 32-bit GUEST
                // offsets in w1/w2. Binding them would turn a loud load
                // failure into two silent wild stores. Making one work
                // needs a real __macapk_shim_<name> plus an entry in
                // kPointerAwareImports -- which is exactly what was done for
                // sincos/sincosf (v1.0.776) and for the nine bodyless names
                // mkdir/remove/rename/getcwd/write/writev/poll/ioctl/fdopen
                // (v1.0.778). Reaching this branch means another one is
                // missing; add the body, do not bind the arm64 twin.
                ++n_panic_imports;
                std::fprintf(stderr,
                    "[arm32rc] WARN unresolvable import '%s' -> panic stub "
                    "(nothing in the runtime namespace defines it)\n",
                    nm.c_str());
                return "";
            };
            // The soft-float double set, by PLT vaddr, so the call site
            // can split the packed int64 return into guest r0:r1.
            ropts.is_softfloat_double_plt =
                [&img](uint32_t plt) -> bool {
                    for (const auto& imp : img.imports)
                        if (imp.plt_addr == plt)
                            return is_softfloat_double_import(imp.name);
                    return false;
                };
            ropts.load_bias = load_bias;
            ropts.shim_dir = shim_dir;
            if (!shim_dir.empty())
                ropts.shim_path = shim_dir + "/libmacapk-bionic-shim.dylib";

            // Cross-guest-library linking, continued: for every import
            // THIS .so actually has that a sibling can answer, register
            // that sibling as an LC_LOAD_DYLIB dependency (deduped by
            // sibling, not by import name -- one Firebase plugin lib can
            // supply dozens of the 49-symbol class from a single sibling).
            // Not gated on whether the name would ALSO have resolved some
            // other way (kPointerAwareImports/shim/dlsym): an extra,
            // unused dependency edge is harmless (Test 1/2 in the design
            // doc), and re-deriving that multi-tier check here would
            // duplicate resolve_import's own logic for no benefit.
            if (sibling_link_on && !sibling_provides.empty()) {
                std::vector<std::string> needed;   // sibling basenames, deduped
                for (const auto& imp : img.imports) {
                    auto it = sibling_provides.find(imp.name);
                    if (it == sibling_provides.end()) continue;
                    if (std::find(needed.begin(), needed.end(), it->second) ==
                        needed.end())
                        needed.push_back(it->second);
                }
                if (!needed.empty()) {
                    const std::string out_dir = dirname_of(out_path);
                    // Derive THIS .so's own cache suffix from out_path's
                    // basename, which AppClassLoader.translate() builds as
                    // exactly "lib" + <libname> + <suffix> + ".dylib"
                    // (AppClassLoader.java: cached = new File(appDir,
                    // "lib_native/lib" + name + cacheSuffix + ".dylib")).
                    // <libname> is derivable from in_path's basename the
                    // identical way ("lib" + libname + ".so"), so
                    // stripping the known prefix/suffix from out_path's
                    // basename recovers <suffix> exactly for every
                    // existing and future suffix value -- no new CLI flag
                    // needed, and this cannot drift from AppClassLoader's
                    // own naming since both sides use the same
                    // deterministic rule (see the design doc, section 7.3,
                    // for why this was chosen over threading a new
                    // --cache-suffix flag through).
                    const std::string my_in_base = basename_of(in_path);
                    const std::string my_out_base = basename_of(out_path);
                    std::string cache_suffix;
                    if (my_in_base.size() > 6 &&
                        my_in_base.compare(0, 3, "lib") == 0 &&
                        my_in_base.compare(my_in_base.size() - 3, 3,
                                           ".so") == 0) {
                        const std::string libname =
                            my_in_base.substr(3, my_in_base.size() - 6);
                        const std::string prefix = "lib" + libname;
                        const std::string kDylibExt = ".dylib";
                        if (my_out_base.size() >=
                                prefix.size() + kDylibExt.size() &&
                            my_out_base.compare(0, prefix.size(), prefix) ==
                                0 &&
                            my_out_base.compare(
                                my_out_base.size() - kDylibExt.size(),
                                kDylibExt.size(), kDylibExt) == 0) {
                            cache_suffix = my_out_base.substr(
                                prefix.size(),
                                my_out_base.size() - prefix.size() -
                                    kDylibExt.size());
                        }
                    }
                    for (const auto& sib_name : needed) {
                        // sib_name is "lib<X>.so" -> "<X>"
                        const std::string sib_libname =
                            sib_name.substr(3, sib_name.size() - 6);
                        ropts.extra_dylib_deps.push_back(
                            out_dir + "/lib" + sib_libname + cache_suffix +
                            ".dylib");
                    }
                    std::fprintf(stderr,
                        "[arm32rc] cross-guest-library: resolving via %zu "
                        "sibling dependenc%s (",
                        needed.size(), needed.size() == 1 ? "y" : "ies");
                    for (size_t i = 0; i < needed.size(); ++i)
                        std::fprintf(stderr, "%s%s", i ? ", " : "",
                                     needed[i].c_str());
                    std::fprintf(stderr, ")\n");
                }
            }

            // ARM32 data-import runtime link (MACAPK_ARM32_DATA_IMPORT_LINK,
            // default ON; see elf_reader.h). =0: ropts stays at its defaults
            // and build_module emits exactly what it did before.
            if (pt::arm32_data_import_link_on()) {
                ropts.data_import_link = true;
                ropts.image_basename = basename_of(in_path);
            }

            // MACAPK_ARM32_EXPORT_ALIASES (default ON; =0 leaves the list
            // empty and build_module emits exactly what it did before). Every
            // defined GLOBAL/WEAK STT_FUNC name, which is exactly the set
            // Bionic's lookup can return (linker_relocate.h
            // is_symbol_global_and_defined). build_module decides which ones
            // land on a translated function start; this file only says which
            // names the ELF exports.
            if (arm32_export_aliases_on()) {
                ropts.alias_exports.reserve(img.symbols.size());
                for (const auto& s : img.symbols) {
                    if (s.type != pt::STT_FUNC) continue;
                    if (s.bind != 1 && s.bind != 2) continue;   // STB_GLOBAL / STB_WEAK
                    if (s.name.empty()) continue;
                    rcd::RcOptions::AliasExport a;
                    a.vaddr = s.address & ~1u;
                    a.name = s.name;
                    a.is_jni = looks_like_jni_export(s.name);
                    ropts.alias_exports.push_back(std::move(a));
                }
            }

            // MACAPK_ARM32_CXXABI_SIBLING_FIRST (default ON; =0 restores the
            // flat bind byte for byte). A PLT import that is part of the C++
            // runtime ABI no Android system library exports
            // (is_cxx_runtime_abi_name) and that resolve_import answers only
            // from the HOST namespace (Tier::HostDlsym: macOS libc++abi /
            // libc++) is bound TWO-LEVEL to the first library of the
            // importer's own DT_NEEDED BFS closure that defines it -- the
            // library Bionic's lookup picks (linker.cpp android14-release
            // :700-733 walk_dependencies_tree; linker_soinfo.cpp :60-80,
            // :119-205). A flat bind cannot express that: macapk-runtime
            // loads the host libc++abi long before any guest dylib, so flat
            // lookup of `__dynamic_cast` hands OpenTTD's libapplication.so the
            // host's 64-bit implementation with 32-bit guest pointers although
            // its DT_NEEDED libc++_shared.so defines it. Only HostDlsym-tier
            // names change: a Shim / RuntimeNs name is our implementation of
            // a system library, and a name the host lacks already reaches the
            // sibling through flat lookup.
            static const bool cxxabi_sibling_first = [] {
                const char* e = std::getenv("MACAPK_ARM32_CXXABI_SIBLING_FIRST");
                return !(e && e[0] == '0' && e[1] == '\0');
            }();
            if (cxxabi_sibling_first && sibling_link_on &&
                !sibling_provides.empty()) {
                std::vector<std::string> cand;   // HostDlsym-tier C++ ABI PLT imports
                for (const auto& imp : img.imports) {
                    if (imp.plt_addr == 0 || imp.name.empty()) continue;
                    if (!is_cxx_runtime_abi_name(imp.name)) continue;
                    if (!sibling_provides.count(imp.name)) continue;
                    std::string host;
                    if (tier_of(imp.name, &host, nullptr) != Tier::HostDlsym)
                        continue;
                    cand.push_back(imp.name);
                }
                if (!cand.empty()) {
                    // BFS over DT_NEEDED, restricted to this app's own .so
                    // files in the ABI dir (system libraries define none of
                    // these names, so skipping them cannot change the answer).
                    const std::string abi_dir = dirname_of(in_path);
                    const std::string my_base = basename_of(in_path);
                    std::vector<std::string> order;   // BFS, excluding self
                    std::unordered_set<std::string> seen{my_base};
                    std::vector<std::string> q{my_base};
                    for (size_t qi = 0; qi < q.size(); ++qi) {
                        for (const std::string& dn :
                             read_elf32_dt_needed(abi_dir + "/" + q[qi])) {
                            if (seen.count(dn)) continue;
                            struct stat sst{};
                            if (::stat((abi_dir + "/" + dn).c_str(), &sst) != 0)
                                continue;   // not an APK library
                            seen.insert(dn);
                            q.push_back(dn);
                            order.push_back(dn);
                        }
                    }
                    const std::string out_dir = dirname_of(out_path);
                    const std::string suffix = derive_cache_suffix(in_path, out_path);
                    std::unordered_map<std::string, std::unordered_set<std::string>> ex;
                    std::map<std::string, std::vector<std::string>> by_sib;
                    for (const std::string& nm : cand) {
                        for (const std::string& sib : order) {
                            auto e = ex.find(sib);
                            if (e == ex.end()) {
                                std::vector<std::string> v =
                                    read_elf32_dynamic_export_names(abi_dir + "/" + sib);
                                e = ex.emplace(sib, std::unordered_set<std::string>(
                                                        v.begin(), v.end())).first;
                            }
                            if (!e->second.count(nm)) continue;
                            if (sib.size() <= 6) break;
                            const std::string dep = out_dir + "/lib" +
                                sib.substr(3, sib.size() - 6) + suffix + ".dylib";
                            if (std::find(ropts.extra_dylib_deps.begin(),
                                          ropts.extra_dylib_deps.end(), dep) ==
                                ropts.extra_dylib_deps.end())
                                ropts.extra_dylib_deps.push_back(dep);
                            ropts.two_level_imports[nm] = dep;
                            by_sib[sib].push_back(nm);
                            break;
                        }
                    }
                    for (const auto& bs : by_sib) {
                        std::fprintf(stderr,
                            "[arm32rc] C++ ABI sibling-first: %zu import(s) bound "
                            "two-level to %s (host libc++abi would have answered):",
                            bs.second.size(), bs.first.c_str());
                        for (size_t k = 0; k < bs.second.size() && k < 12; ++k)
                            std::fprintf(stderr, " %s", bs.second[k].c_str());
                        std::fprintf(stderr, "%s\n", bs.second.size() > 12 ? " ..." : "");
                    }
                }
            }

            rcd::RcStats rstats;
            std::string rerr;
            // Takes the DiscoveredFunction form declared in
            // arm32rc_driver.h. (The transitional RcFunc overload and its
            // MACAPK_ARM32RC_LOCAL_DISPO symbol-name-sniffing producer were
            // deleted; this file owns the disposition rules.)
            firstlaunch_mark("discovery-done");
            const bool rok = rcd::build_module(img, rfuncs, ropts, out_path,
                                               &rstats, &rerr);
            firstlaunch_mark("build-module-done");
            std::fputs(rcd::format_stats(rstats).c_str(), stderr);
            if (!rok) {
                std::fprintf(stderr, "[arm32rc] FAILED: %s\n", rerr.c_str());
                return 1;
            }
            for (const std::string& fs : rstats.flat_symbols)
                if (fs.rfind("__macapk_shim_", 0) == 0)
                    std::fprintf(stderr,
                        "[arm32rc] WARN missing shim: %s\n", fs.c_str());
            if (n_panic_imports)
                std::fprintf(stderr,
                    "[arm32rc] %zu import(s) resolved NOWHERE and went to "
                    "the panic stub; each needs a __macapk_shim_<name> "
                    "before the app that calls it can work\n",
                    n_panic_imports);
            // Discovery streamed the incumbent's LLVM IR into ll_path on
            // the way here and the recompiler throws all of it away, so
            // delete it. Returning without this leaked 251 MB per ARM32
            // app for HCR (566 MB for Jetpack Joyride): measured
            // lib_native/ 9.8 MB -> 252 MB, a 25x disk regression and a
            // shipping blocker. The three normal exits below already do
            // this; the early return skipped all of them.
            if (!keep_ir) std::remove(ll_path.c_str());
            std::fprintf(stderr, "[arm32rc] wrote %s\n", out_path.c_str());
            return 0;
        }
        }  // if constexpr (GprBits == 32)
    }

    // v1.0.802: UNREACHABLE. run_pipeline is instantiated SOLELY as
    // <32, Arm32Lifter, Elf32Image> (see the call site at the bottom of this
    // file); the arm64 path returns via run_arm64_pack and never enters here.
    // The 32-bit block above always returns. Everything that used to follow --
    // ~2045 lines assembling the LLVM IR module, then shelling out to
    // llvm-as / clang / ld64.lld, plus the 1.2 GB IR-split pipeline that
    // existed only to keep clang under its memory ceiling -- was DELETED with
    // the LLVM backend. arm32rc emits the Mach-O directly.
    std::fprintf(stderr,
        "[translator] internal error: fell through run_pipeline for %s\n",
        in_path.c_str());
    return 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::string in_path;
    std::string out_path;
    std::string shim_dir;
    bool keep_ir = false;
    bool verbose = false;
    bool strict  = false;
    // Per-image load bias (guest-arena byte offset). 0 = main / single-.so
    // image (bit-identical output). The runtime assigns a non-zero bias to
    // 2nd+ .so files so they do not collide in the shared guest arena.
    uint32_t load_bias = 0;

    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto need = [&]{
            if (i + 1 >= argc) { usage(argv[0]); std::exit(2); }
        };
        if      (a == "--in")       { need(); in_path  = argv[++i]; }
        else if (a == "--out")      { need(); out_path = argv[++i]; }
        else if (a == "--shim-dir") { need(); shim_dir = argv[++i]; }
        else if (a == "--keep-ir")  { keep_ir = true; }
        else if (a == "--emit-discovery") { need(); g_discovery_sidecar = argv[++i]; }
        else if (a == "--load-bias"){ need(); load_bias = static_cast<uint32_t>(std::strtoul(argv[++i], nullptr, 0)); }
        else if (a == "--strict")   { strict  = true; }
        else if (a == "--print-runtime-exports") {
            // Diagnostic / gate hook: print the runtime-namespace export set
            // the ARM32 import resolver would use for DIR, sorted, one per
            // line, and exit. debug/firstlaunch_gate.sh diffs it with
            // MACAPK_FIRSTLAUNCH_NM_INPROC on and off.
            need();
            std::unordered_set<std::string> ex;
            load_runtime_namespace_exports(argv[++i], &ex);
            std::vector<std::string> v(ex.begin(), ex.end());
            std::sort(v.begin(), v.end());
            for (const auto& nm : v) std::printf("%s\n", nm.c_str());
            return 0;
        }
        else if (a == "--probe-ccimage-layout") {
            // Gate hook (debug/w5nativeshim_gate.sh): run the SAME decision the
            // ARM32 pipeline makes for cocos2d::CCImage::_initWithPngData
            // (cocos_png_override_decision) on one .so and print it, without
            // translating anything. Exit 0 whenever the ELF parses.
            need();
            const std::string so = argv[++i];
            pt::Elf32Image pimg;
            auto perr = pt::read_elf32(so, &pimg, 0);
            if (!perr.empty()) {
                std::fprintf(stderr, "[translator] elf32 parse error: %s\n", perr.c_str());
                return 1;
            }
            bool exports = false;
            for (const auto& s : pimg.symbols)
                if (s.name == "_ZN7cocos2d7CCImage16_initWithPngDataEPvi") exports = true;
            CocosCCImageLayout l;
            const bool ovr = exports && cocos_png_override_decision(pimg, &l);
            if (!exports) l = cocos_ccimage_layout(pimg);
            std::printf("exports=%d layout=%s w=0x%x h=0x%x bpc=0x%x data=0x%x alpha=0x%x "
                        "premulti=0x%x override=%d why=%s\n",
                        exports ? 1 : 0,
                        l.parsed ? (cocos_ccimage_layout_matches_shim(l) ? "MATCH" : "MISMATCH")
                                 : "UNPARSED",
                        l.width, l.height, l.bpc, l.data, l.alpha, l.premulti,
                        ovr ? 1 : 0, l.why.c_str());
            return 0;
        }
        else if (a == "--verbose" || a == "-v") { verbose = true; }
        else if (a == "-h" || a == "--help")    { usage(argv[0]); return 0; }
        else { usage(argv[0]); return 2; }
    }
    if (shim_dir.empty()) {
        char buf[4096];
        if (realpath(argv[0], buf)) {
            std::string p(buf);
            auto slash = p.find_last_of('/');
            if (slash != std::string::npos) shim_dir = p.substr(0, slash);
        }
        if (shim_dir.empty()) shim_dir = ".";
    }
    if (in_path.empty() || out_path.empty()) { usage(argv[0]); return 2; }

    firstlaunch_mark("start");
    int klass = peek_elf_class(in_path);
    if (klass < 0) {
        std::fprintf(stderr,
            "[translator] %s: not a valid ELF file\n", in_path.c_str());
        return 1;
    }

    if (klass == pt::ELFCLASS32) {
        pt::Elf32Image img;
        auto err = pt::read_elf32(in_path, &img, load_bias);
        if (!err.empty()) {
            std::fprintf(stderr, "[translator] elf32 parse error: %s\n",
                         err.c_str());
            return 1;
        }
        firstlaunch_mark("elf32-read");
        if (verbose) {
            std::fprintf(stderr,
                "[translator] %s: ARM32 ELF, %zu symbols, %zu loadable "
                "segments\n",
                in_path.c_str(), img.symbols.size(), img.loadable.size());
        }
        if (load_bias != 0)
            std::fprintf(stderr, "[translator] per-image load bias = 0x%x\n",
                         load_bias);
        return run_pipeline<32, pt::Arm32Lifter, pt::Elf32Image>(
            in_path, out_path, shim_dir,
            verbose, strict, keep_ir, img, load_bias);
    }
    if (klass == pt::ELFCLASS64) {
        pt::Elf64Image img;
        // The Stage-6 pack reads only the symbol tables and `raw`, never a
        // segment's contents, so skip the per-segment copy (a second resident
        // copy of the whole library). MACAPK_FIRSTLAUNCH_FASTREAD=0 restores
        // it; see read_elf64's header comment.
        auto err = pt::read_elf64(in_path, &img, /*copy_segment_bytes=*/false);
        if (!err.empty()) {
            std::fprintf(stderr, "[translator] elf64 parse error: %s\n",
                         err.c_str());
            return 1;
        }
        if (verbose) {
            std::fprintf(stderr,
                "[translator] %s: AArch64 ELF, %zu symbols, %zu "
                "loadable segments\n",
                in_path.c_str(), img.symbols.size(), img.loadable.size());
        }
        // arm64 path is fundamentally different from arm32: no
        // per-instruction translation, the original arm64 .text runs
        // as-is on Apple Silicon. The arm64_pack packager wraps the
        // ELF in a tiny Mach-O whose constructor runtime-loads it
        // (mmap + relocations + init_array) via the shim-side ELF
        // loader. (void) strict to keep the same CLI shape -- the
        // arm64 path has no equivalent of strict lifting since
        // nothing is being lifted.
        (void)strict;
        firstlaunch_mark("elf64-read");
        const int prc = pt::run_arm64_pack(in_path, out_path,
                                           shim_dir, verbose, keep_ir, img);
        firstlaunch_mark("pack-done");
        return prc;
    }

    std::fprintf(stderr, "[translator] %s: unsupported ELFCLASS %d\n",
                 in_path.c_str(), klass);
    return 1;
}
