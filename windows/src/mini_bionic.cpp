// mini_bionic.cpp -- see mini_bionic.h.

#include "winapk/mini_bionic.h"

#include "winapk/elf_tls.h"
#include "winapk/guest_abi.h"

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

#include <windows.h>

#include "bionic/bionic_internal.h"

namespace winapk {

namespace {

// ---- memory ---------------------------------------------------------------
// Bionic and the UCRT both return 16-byte aligned blocks on x86_64, which is
// what guest code compiled for Android assumes.
WINAPK_GUEST_ABI void* g_malloc(size_t n) { return std::malloc(n); }
WINAPK_GUEST_ABI void* g_calloc(size_t n, size_t sz) { return std::calloc(n, sz); }

// Over-aligned blocks (posix_memalign / memalign / aligned_alloc above 16).
// The guest frees them with plain free(), so each is carved out of a larger
// malloc block and remembered: free and realloc look the pointer up first.
// The count keeps that lookup off the common path while there are none.
struct AlignedBlock {
    void* base;
    size_t size;
};
std::mutex g_aligned_lock;
std::unordered_map<void*, AlignedBlock> g_aligned;
std::atomic<size_t> g_aligned_count{0};

void* aligned_block(size_t align, size_t n) {
    if (align <= 16) return std::malloc(n ? n : 1);
    void* base = std::malloc(n + align);
    if (!base) return nullptr;
    // Strictly above base, so an aligned pointer is never a plain block.
    auto* p = reinterpret_cast<void*>((reinterpret_cast<uintptr_t>(base) + align) & ~(uintptr_t{align} - 1));
    std::lock_guard<std::mutex> hold(g_aligned_lock);
    g_aligned[p] = {base, n};
    g_aligned_count.fetch_add(1, std::memory_order_relaxed);
    return p;
}

// Takes `p` out of the aligned registry; false when it is a plain block.
bool take_aligned(void* p, AlignedBlock* out) {
    if (g_aligned_count.load(std::memory_order_relaxed) == 0) return false;
    std::lock_guard<std::mutex> hold(g_aligned_lock);
    auto it = g_aligned.find(p);
    if (it == g_aligned.end()) return false;
    *out = it->second;
    g_aligned.erase(it);
    g_aligned_count.fetch_sub(1, std::memory_order_relaxed);
    return true;
}

// malloc_usable_size: what the block can hold. Skia and Dart's allocator ask,
// and a 0 makes them treat every block as full. The UCRT's _msize answers for
// a plain block; an over-aligned one holds what it was asked for.
WINAPK_GUEST_ABI size_t g_malloc_usable_size(const void* p) {
    if (!p) return 0;
    if (g_aligned_count.load(std::memory_order_relaxed) != 0) {
        std::lock_guard<std::mutex> hold(g_aligned_lock);
        auto it = g_aligned.find(const_cast<void*>(p));
        if (it != g_aligned.end()) return it->second.size;
    }
    return _msize(const_cast<void*>(p));
}

WINAPK_GUEST_ABI void* g_realloc(void* p, size_t n) {
    AlignedBlock b;
    if (p && take_aligned(p, &b)) {
        void* q = std::malloc(n ? n : 1);
        if (q) std::memcpy(q, p, b.size < n ? b.size : n);
        std::free(b.base);
        return q;
    }
    return std::realloc(p, n);
}
WINAPK_GUEST_ABI void g_free(void* p) {
    AlignedBlock b;
    if (p && take_aligned(p, &b)) {
        std::free(b.base);
        return;
    }
    std::free(p);
}

bool valid_alignment(size_t a) { return a != 0 && (a & (a - 1)) == 0; }

WINAPK_GUEST_ABI int g_posix_memalign(void** out, size_t align, size_t n) {
    if (!out) return EINVAL;
    if (!valid_alignment(align) || align % sizeof(void*) != 0) return 22;  // Linux EINVAL
    void* p = aligned_block(align, n);
    if (!p) return 12;  // Linux ENOMEM
    *out = p;
    return 0;
}
WINAPK_GUEST_ABI void* g_memalign(size_t align, size_t n) {
    if (!valid_alignment(align)) {
        size_t a = 16;
        while (a < align) a <<= 1;
        align = a;
    }
    return aligned_block(align, n);
}
WINAPK_GUEST_ABI void* g_aligned_alloc(size_t align, size_t n) {
    return valid_alignment(align) ? aligned_block(align, n) : nullptr;
}
WINAPK_GUEST_ABI void* g_valloc(size_t n) { return aligned_block(4096, n); }

// ---- string / memory routines --------------------------------------------
WINAPK_GUEST_ABI void* g_memcpy(void* d, const void* s, size_t n) { return std::memcpy(d, s, n); }
WINAPK_GUEST_ABI void* g_memmove(void* d, const void* s, size_t n) { return std::memmove(d, s, n); }
WINAPK_GUEST_ABI void* g_memset(void* d, int c, size_t n) { return std::memset(d, c, n); }
WINAPK_GUEST_ABI int g_memcmp(const void* a, const void* b, size_t n) { return std::memcmp(a, b, n); }
WINAPK_GUEST_ABI size_t g_strlen(const char* s) { return std::strlen(s); }
WINAPK_GUEST_ABI int g_strcmp(const char* a, const char* b) { return std::strcmp(a, b); }
WINAPK_GUEST_ABI int g_strncmp(const char* a, const char* b, size_t n) { return std::strncmp(a, b, n); }
WINAPK_GUEST_ABI char* g_strcpy(char* d, const char* s) { return std::strcpy(d, s); }

// ---- stdio ----------------------------------------------------------------
WINAPK_GUEST_ABI int g_puts(const char* s) {
    int r = std::puts(s);
    std::fflush(stdout);
    return r;
}

// ---- liblog ---------------------------------------------------------------
WINAPK_GUEST_ABI int g_android_log_write(int prio, const char* tag, const char* text) {
    bionic::log_line(prio, tag, text);
    return 1;
}

// ---- stack protector / abort ----------------------------------------------
// abort() is raise(SIGABRT): the guest's handler (a crash reporter's) runs
// first; if it returns, the default action ends the process through the
// host's abort, which writes the crash report and the minidump.
[[noreturn]] void guest_abort(const char* why) {
    std::fprintf(stderr, "[win.apk] %s\n", why);
    std::fflush(stderr);
    bionic::signal_send(0, 6);  // SIGABRT
    std::abort();
}

[[noreturn]] WINAPK_GUEST_ABI void g_stack_chk_fail() { guest_abort("guest stack corruption detected (__stack_chk_fail)"); }

[[noreturn]] WINAPK_GUEST_ABI void g_abort() { guest_abort("guest called abort()"); }

// ---- errno ----------------------------------------------------------------
// Bionic's errno is `*__errno()`, in Linux numbering; see bionic_internal.h.
WINAPK_GUEST_ABI int* g_errno() { return bionic::guest_errno(); }

// ---- C++ static destructors -----------------------------------------------
using GuestDtor = void(WINAPK_GUEST_ABI*)(void*);
struct AtexitEntry { GuestDtor fn; void* arg; void* dso; };
std::mutex g_atexit_lock;
std::vector<AtexitEntry> g_atexit;

WINAPK_GUEST_ABI int g_cxa_atexit(GuestDtor fn, void* arg, void* dso) {
    std::lock_guard<std::mutex> lock(g_atexit_lock);
    g_atexit.push_back({fn, arg, dso});
    return 0;
}

WINAPK_GUEST_ABI void g_cxa_finalize(void* dso) {
    std::vector<AtexitEntry> run;
    {
        std::lock_guard<std::mutex> lock(g_atexit_lock);
        for (size_t i = g_atexit.size(); i-- > 0;) {
            if (dso == nullptr || g_atexit[i].dso == dso) {
                run.push_back(g_atexit[i]);
                g_atexit.erase(g_atexit.begin() + static_cast<ptrdiff_t>(i));
            }
        }
    }
    for (const AtexitEntry& e : run) e.fn(e.arg);
}

// C++ thread_local destructors: Bionic's __cxa_thread_atexit_impl, kept with
// the thread's ELF TLS blocks (elf_tls.h) so the destructor runs before the
// block its object lives in is freed, and the library holding the destructor
// stays loaded until then (the linker counts it as a reference).
using GuestThreadDtor = void(WINAPK_GUEST_ABI*)(void*);
WINAPK_GUEST_ABI int g_cxa_thread_atexit_impl(GuestThreadDtor fn, void* obj, void* dso) {
    tls_thread_atexit(reinterpret_cast<void*>(fn), obj, dso);
    return 0;
}

// ---- __cxa_guard_*: function-local statics ------------------------------------
// The Itanium C++ ABI's guards (libc++abi's, which an app that links the C++
// runtime as a system library imports from it). The guard's first byte says
// "initialized"; acquire returns 1 when the caller must run the initializer,
// holding a lock that release or abort drops. One recursive lock for all
// guards: an initializer may itself initialize other statics.
std::recursive_mutex g_guard_lock;

WINAPK_GUEST_ABI int g_cxa_guard_acquire(uint64_t* guard) {
    if (__atomic_load_n(reinterpret_cast<uint8_t*>(guard), __ATOMIC_ACQUIRE)) return 0;
    g_guard_lock.lock();
    if (*reinterpret_cast<uint8_t*>(guard)) {
        g_guard_lock.unlock();
        return 0;
    }
    return 1;
}

WINAPK_GUEST_ABI void g_cxa_guard_release(uint64_t* guard) {
    __atomic_store_n(reinterpret_cast<uint8_t*>(guard), uint8_t{1}, __ATOMIC_RELEASE);
    g_guard_lock.unlock();
}

WINAPK_GUEST_ABI void g_cxa_guard_abort(uint64_t*) { g_guard_lock.unlock(); }

// ---- operator new / delete: Android's libstdc++.so ---------------------------
// A library linked against the system C++ runtime (NEEDED libstdc++.so, as
// Starling's DexProtector libalice.so is) imports the global operators from
// it, as it does __cxa_guard_*. Without them `new` returned 0 and the first
// store through it crashed. Same heap as malloc/free, which guest code mixes
// with them. No C++ exception can cross into the guest, so a failed
// allocation stops the program as an unhandled std::bad_alloc would.
WINAPK_GUEST_ABI void* g_operator_new(size_t n) {
    if (void* p = std::malloc(n ? n : 1)) return p;
    std::fprintf(stderr, "[win.apk] guest operator new(%zu): out of memory\n", n);
    std::fflush(stderr);
    std::abort();
}
WINAPK_GUEST_ABI void* g_operator_new_nothrow(size_t n, const void*) { return std::malloc(n ? n : 1); }
WINAPK_GUEST_ABI void* g_operator_new_aligned(size_t n, size_t align) {
    if (void* p = aligned_block(align, n ? n : 1)) return p;
    std::fprintf(stderr, "[win.apk] guest operator new(%zu, align %zu): out of memory\n", n, align);
    std::fflush(stderr);
    std::abort();
}
WINAPK_GUEST_ABI void g_operator_delete(void* p) { g_free(p); }
WINAPK_GUEST_ABI void g_operator_delete_extra(void* p, size_t) { g_free(p); }  // sized, aligned, nothrow

[[noreturn]] WINAPK_GUEST_ABI void g_cxa_pure_virtual() {
    std::fprintf(stderr, "[win.apk] guest called a pure virtual function\n");
    std::fflush(stderr);
    std::abort();
}

// ---- assert ---------------------------------------------------------------------
// Bionic's assert() calls these, which never return. A stub that returned 0
// let the code run on past the failed check, into a crash with no message
// (ChatGPT's libopenai_xplat_export.so); now the check's message is in the log.
[[noreturn]] WINAPK_GUEST_ABI void g_assert2(const char* file, int line, const char* function, const char* failed) {
    std::fprintf(stderr, "[win.apk] guest assertion failed: %s:%d: %s: %s\n", file ? file : "?", line,
                 function ? function : "?", failed ? failed : "?");
    std::fflush(stderr);
    std::abort();
}
[[noreturn]] WINAPK_GUEST_ABI void g_assert(const char* file, int line, const char* failed) {
    g_assert2(file, line, nullptr, failed);
}

// ---- qsort: a guest callback invoked from host code -------------------------
// The CRT's qsort calls its comparator with the Microsoft ABI. The guest's
// comparator expects System V. An ms_abi trampoline reads the guest pointer
// from a thread_local; saving and restoring it keeps nested qsort calls (a
// comparator that itself sorts) correct.
using GuestCompare = int(WINAPK_GUEST_ABI*)(const void*, const void*);
thread_local GuestCompare t_guest_compare = nullptr;

int host_compare_trampoline(const void* a, const void* b) { return t_guest_compare(a, b); }

WINAPK_GUEST_ABI void g_qsort(void* base, size_t n, size_t size, GuestCompare cmp) {
    GuestCompare saved = t_guest_compare;
    t_guest_compare = cmp;
    std::qsort(base, n, size, host_compare_trampoline);
    t_guest_compare = saved;
}

// bsearch, the same way. BoringSSL's error-string and cipher tables are
// searched with it; the stub's null left Conscrypt (DAVx5) faulting at start,
// and Flutter's engine after its first frame (City Car).
WINAPK_GUEST_ABI void* g_bsearch(const void* key, const void* base, size_t n, size_t size, GuestCompare cmp) {
    GuestCompare saved = t_guest_compare;
    t_guest_compare = cmp;
    void* r = std::bsearch(key, base, n, size, host_compare_trampoline);
    t_guest_compare = saved;
    return r;
}

using bionic::ShimSymbol;
using bionic::ShimTable;

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("malloc", g_malloc),
    WINAPK_SHIM("calloc", g_calloc),
    WINAPK_SHIM("realloc", g_realloc),
    WINAPK_SHIM("free", g_free),
    WINAPK_SHIM("posix_memalign", g_posix_memalign),
    WINAPK_SHIM("memalign", g_memalign),
    WINAPK_SHIM("aligned_alloc", g_aligned_alloc),
    WINAPK_SHIM("valloc", g_valloc),
    WINAPK_SHIM("pvalloc", g_valloc),
    WINAPK_SHIM("malloc_usable_size", g_malloc_usable_size),
    WINAPK_SHIM("bsearch", g_bsearch),
    WINAPK_SHIM("memcpy", g_memcpy),
    WINAPK_SHIM("memmove", g_memmove),
    WINAPK_SHIM("memset", g_memset),
    WINAPK_SHIM("memcmp", g_memcmp),
    WINAPK_SHIM("strlen", g_strlen),
    WINAPK_SHIM("strcmp", g_strcmp),
    WINAPK_SHIM("strncmp", g_strncmp),
    WINAPK_SHIM("strcpy", g_strcpy),
    WINAPK_SHIM("puts", g_puts),
    WINAPK_SHIM("__android_log_write", g_android_log_write),
    WINAPK_SHIM("__stack_chk_fail", g_stack_chk_fail),
    WINAPK_SHIM("abort", g_abort),
    WINAPK_SHIM("__errno", g_errno),
    WINAPK_SHIM("__cxa_atexit", g_cxa_atexit),
    WINAPK_SHIM("__cxa_finalize", g_cxa_finalize),
    WINAPK_SHIM("__cxa_thread_atexit_impl", g_cxa_thread_atexit_impl),
    WINAPK_SHIM("__cxa_guard_acquire", g_cxa_guard_acquire),
    WINAPK_SHIM("__cxa_guard_release", g_cxa_guard_release),
    WINAPK_SHIM("__cxa_guard_abort", g_cxa_guard_abort),
    WINAPK_SHIM("__cxa_pure_virtual", g_cxa_pure_virtual),
    WINAPK_SHIM("_Znwm", g_operator_new),
    WINAPK_SHIM("_Znam", g_operator_new),
    WINAPK_SHIM("_ZnwmRKSt9nothrow_t", g_operator_new_nothrow),
    WINAPK_SHIM("_ZnamRKSt9nothrow_t", g_operator_new_nothrow),
    WINAPK_SHIM("_ZnwmSt11align_val_t", g_operator_new_aligned),
    WINAPK_SHIM("_ZnamSt11align_val_t", g_operator_new_aligned),
    WINAPK_SHIM("_ZdlPv", g_operator_delete),
    WINAPK_SHIM("_ZdaPv", g_operator_delete),
    WINAPK_SHIM("_ZdlPvm", g_operator_delete_extra),
    WINAPK_SHIM("_ZdaPvm", g_operator_delete_extra),
    WINAPK_SHIM("_ZdlPvRKSt9nothrow_t", g_operator_delete_extra),
    WINAPK_SHIM("_ZdaPvRKSt9nothrow_t", g_operator_delete_extra),
    WINAPK_SHIM("_ZdlPvSt11align_val_t", g_operator_delete_extra),
    WINAPK_SHIM("_ZdaPvSt11align_val_t", g_operator_delete_extra),
    WINAPK_SHIM("_ZdlPvmSt11align_val_t", g_operator_delete_extra),
    WINAPK_SHIM("_ZdaPvmSt11align_val_t", g_operator_delete_extra),
    WINAPK_SHIM("__assert", g_assert),
    WINAPK_SHIM("__assert2", g_assert2),
    WINAPK_SHIM("qsort", g_qsort),
    WINAPK_SHIM("__tls_get_addr", winapk::tls_get_addr),
};

}  // namespace

namespace bionic {

thread_local int t_guest_errno = 0;
int* guest_errno() { return &t_guest_errno; }

int linux_errno(int e) {
    // The UCRT agrees with Linux up to ERANGE (34), except where noted.
    switch (e) {
        case EDEADLK: return 35;        // UCRT 36
        case ENAMETOOLONG: return 36;   // 38
        case ENOLCK: return 37;         // 39
        case ENOSYS: return 38;         // 40
        case ENOTEMPTY: return 39;      // 41
        case EILSEQ: return 84;         // 42
        case EADDRINUSE: return 98;
        case EADDRNOTAVAIL: return 99;
        case EAFNOSUPPORT: return 97;
        case EALREADY: return 114;
        case EBADMSG: return 74;
        case ECANCELED: return 125;
        case ECONNABORTED: return 103;
        case ECONNREFUSED: return 111;
        case ECONNRESET: return 104;
        case EDESTADDRREQ: return 89;
        case EHOSTUNREACH: return 113;
        case EIDRM: return 43;
        case EINPROGRESS: return 115;
        case EISCONN: return 106;
        case ELOOP: return 40;
        case EMSGSIZE: return 90;
        case ENETDOWN: return 100;
        case ENETRESET: return 102;
        case ENETUNREACH: return 101;
        case ENOBUFS: return 105;
        case ENODATA: return 61;
        case ENOLINK: return 67;
        case ENOMSG: return 42;
        case ENOPROTOOPT: return 92;
        case ENOSR: return 63;
        case ENOSTR: return 60;
        case ENOTCONN: return 107;
        case ENOTRECOVERABLE: return 131;
        case ENOTSOCK: return 88;
        case ENOTSUP: return 95;
        case EOPNOTSUPP: return 95;
        case EOVERFLOW: return 75;
        case EOWNERDEAD: return 130;
        case EPROTO: return 71;
        case EPROTONOSUPPORT: return 93;
        case EPROTOTYPE: return 91;
        case ETIME: return 62;
        case ETIMEDOUT: return 110;
        case ETXTBSY: return 26;
        case EWOULDBLOCK: return 11;
        default: return (e > 0 && e <= 34) ? e : kEIO;
    }
}

void set_errno_from_crt(int crt_errno) { t_guest_errno = linux_errno(crt_errno); }

void set_errno_from_win32(unsigned long e) {
    switch (e) {
        case ERROR_FILE_NOT_FOUND: case ERROR_PATH_NOT_FOUND: case ERROR_INVALID_DRIVE:
        case ERROR_BAD_NETPATH: case ERROR_BAD_PATHNAME: t_guest_errno = kENOENT; break;
        case ERROR_ACCESS_DENIED: case ERROR_WRITE_PROTECT: t_guest_errno = kEACCES; break;
        case ERROR_INVALID_HANDLE: t_guest_errno = kEBADF; break;
        case ERROR_NOT_ENOUGH_MEMORY: case ERROR_OUTOFMEMORY: t_guest_errno = kENOMEM; break;
        case ERROR_ALREADY_EXISTS: case ERROR_FILE_EXISTS: t_guest_errno = kEEXIST; break;
        case ERROR_DIR_NOT_EMPTY: t_guest_errno = kENOTEMPTY; break;
        case ERROR_INVALID_PARAMETER: case ERROR_INVALID_NAME: t_guest_errno = kEINVAL; break;
        case ERROR_DISK_FULL: case ERROR_HANDLE_DISK_FULL: t_guest_errno = kENOSPC; break;
        case ERROR_SHARING_VIOLATION: case ERROR_LOCK_VIOLATION: t_guest_errno = kEBUSY; break;
        case ERROR_DIRECTORY: t_guest_errno = kENOTDIR; break;
        case ERROR_NOT_SAME_DEVICE: t_guest_errno = kEXDEV; break;
        case ERROR_TOO_MANY_LINKS: t_guest_errno = kEMLINK; break;
        case ERROR_TOO_MANY_OPEN_FILES: t_guest_errno = kEMFILE; break;
        case ERROR_FILENAME_EXCED_RANGE: t_guest_errno = kENAMETOOLONG; break;
        case ERROR_NOT_SUPPORTED: case ERROR_CALL_NOT_IMPLEMENTED: t_guest_errno = kENOSYS; break;
        case WAIT_TIMEOUT: t_guest_errno = kETIMEDOUT; break;
        default: t_guest_errno = kEIO; break;
    }
}

void log_line(int prio, const char* tag, const char* text) {
    // android_LogPriority: 2 VERBOSE .. 7 FATAL.
    static const char kLetters[] = "??VDIWEF";
    const char letter = (prio >= 0 && prio <= 7) ? kLetters[prio] : '?';
    std::fprintf(stderr, "%c/%s: %s\n", letter, tag ? tag : "", text ? text : "");
    std::fflush(stderr);
}

}  // namespace bionic

void* mini_bionic_resolve(const char* name) {
    // Every module's table, merged once; the core table above wins a clash.
    static const std::unordered_map<std::string, void*> all = [] {
        std::unordered_map<std::string, void*> m;
        auto add = [&m](const ShimSymbol* syms, size_t n) {
            for (size_t i = 0; i < n; ++i) m.emplace(syms[i].name, syms[i].addr);
        };
        add(kSymbols, sizeof(kSymbols) / sizeof(kSymbols[0]));
        for (ShimTable t : {bionic::string_symbols(), bionic::printf_symbols(), bionic::pthread_symbols(),
                            bionic::stdio_symbols(), bionic::misc_symbols(), bionic::signal_symbols(),
                            bionic::locale_symbols(), bionic::fd_symbols(),
                            bionic::socket_symbols(), bionic::looper_symbols(),
                            bionic::zlib_symbols(), bionic::setjmp_symbols()}) {
            add(t.symbols, t.count);
        }
        return m;
    }();
    auto it = all.find(name);
    return it == all.end() ? nullptr : it->second;
}

void mini_bionic_run_atexit() { g_cxa_finalize(nullptr); }

void mini_bionic_run_atexit_in(const void* base, size_t size) {
    const auto lo = reinterpret_cast<uintptr_t>(base);
    std::vector<AtexitEntry> run;
    {
        std::lock_guard<std::mutex> lock(g_atexit_lock);
        for (size_t i = g_atexit.size(); i-- > 0;) {
            const auto dso = reinterpret_cast<uintptr_t>(g_atexit[i].dso);
            if (dso >= lo && dso - lo < size) {
                run.push_back(g_atexit[i]);
                g_atexit.erase(g_atexit.begin() + static_cast<ptrdiff_t>(i));
            }
        }
    }
    for (const AtexitEntry& e : run) e.fn(e.arg);
}

}  // namespace winapk
