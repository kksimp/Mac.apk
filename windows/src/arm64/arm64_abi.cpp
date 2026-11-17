// arm64_abi.cpp -- the Bionic shim's functions whose structs arm64 lays out
// differently from x86_64 (arm64_guest.h, abi_override).
//
// The shim is written against Android's x86_64 headers. Most of bionic's LP64 structs are the same on arm64, but
// not all: struct stat (x86_64: 144 bytes, st_nlink before st_mode; arm64:
// 128, asm-generic order) and struct epoll_event (packed to 12 bytes on
// x86_64, 16 on arm64). Passing an arm64 caller's struct to the x86_64
// function writes past the end of it -- a stack object in the caller's
// frame, so saved registers and the stack protector's canary go first. An
// arm64 library's imports of these names bind to the wrappers here, which
// call the shim with an x86_64 struct and convert. Four of open's flags and
// the system call numbers differ too.

#include "winapk/arm64_guest.h"

#include "winapk/guest_abi.h"
#include "winapk/mini_bionic.h"

#include <cstdint>
#include <cstring>
#include <mutex>
#include <set>
#include <vector>

namespace winapk::arm64 {

namespace {

// Bionic x86_64 (the shim's GuestStat, src/bionic/stdio.cpp).
struct X86Stat {
    uint64_t st_dev, st_ino, st_nlink;
    uint32_t st_mode, st_uid, st_gid;
    int32_t pad0;
    uint64_t st_rdev;
    int64_t st_size, st_blksize, st_blocks;
    int64_t st_atime_sec, st_atime_nsec, st_mtime_sec, st_mtime_nsec, st_ctime_sec, st_ctime_nsec;
    int64_t unused[3];
};
static_assert(sizeof(X86Stat) == 144, "Bionic's struct stat on x86_64");

// Bionic arm64 (asm-generic/stat.h's layout).
struct Arm64Stat {
    uint64_t st_dev, st_ino;
    uint32_t st_mode, st_nlink, st_uid, st_gid;
    uint64_t st_rdev, pad1;
    int64_t st_size;
    int32_t st_blksize, pad2;
    int64_t st_blocks;
    int64_t st_atime_sec, st_atime_nsec, st_mtime_sec, st_mtime_nsec, st_ctime_sec, st_ctime_nsec;
    uint32_t unused4, unused5;
};
static_assert(sizeof(Arm64Stat) == 128, "Bionic's struct stat on arm64");

void to_arm64(const X86Stat& x, Arm64Stat* a) {
    std::memset(a, 0, sizeof(*a));
    a->st_dev = x.st_dev;
    a->st_ino = x.st_ino;
    a->st_mode = x.st_mode;
    a->st_nlink = static_cast<uint32_t>(x.st_nlink);
    a->st_uid = x.st_uid;
    a->st_gid = x.st_gid;
    a->st_rdev = x.st_rdev;
    a->st_size = x.st_size;
    a->st_blksize = static_cast<int32_t>(x.st_blksize);
    a->st_blocks = x.st_blocks;
    a->st_atime_sec = x.st_atime_sec;
    a->st_atime_nsec = x.st_atime_nsec;
    a->st_mtime_sec = x.st_mtime_sec;
    a->st_mtime_nsec = x.st_mtime_nsec;
    a->st_ctime_sec = x.st_ctime_sec;
    a->st_ctime_nsec = x.st_ctime_nsec;
}

#pragma pack(push, 4)
struct X86EpollEvent {  // packed on x86_64
    uint32_t events;
    uint64_t data;
};
#pragma pack(pop)
static_assert(sizeof(X86EpollEvent) == 12, "x86_64's packed struct epoll_event");

struct Arm64EpollEvent {
    uint32_t events;
    uint64_t data;
};
static_assert(sizeof(Arm64EpollEvent) == 16, "arm64's struct epoll_event");

template <typename Fn> Fn shim(const char* name) {
    return reinterpret_cast<Fn>(mini_bionic_resolve(name));
}

using PathStat = int(WINAPK_GUEST_ABI*)(const char*, X86Stat*);
using FdStat = int(WINAPK_GUEST_ABI*)(int, X86Stat*);
using AtStat = int(WINAPK_GUEST_ABI*)(int, const char*, X86Stat*, int);

WINAPK_GUEST_ABI int a64_stat(const char* path, Arm64Stat* out) {
    static const auto fn = shim<PathStat>("stat");
    X86Stat x;
    const int r = fn(path, &x);
    if (r == 0 && out) to_arm64(x, out);
    return r;
}
WINAPK_GUEST_ABI int a64_lstat(const char* path, Arm64Stat* out) {
    static const auto fn = shim<PathStat>("lstat");
    X86Stat x;
    const int r = fn(path, &x);
    if (r == 0 && out) to_arm64(x, out);
    return r;
}
WINAPK_GUEST_ABI int a64_fstat(int fd, Arm64Stat* out) {
    static const auto fn = shim<FdStat>("fstat");
    X86Stat x;
    const int r = fn(fd, &x);
    if (r == 0 && out) to_arm64(x, out);
    return r;
}
WINAPK_GUEST_ABI int a64_fstatat(int dirfd, const char* path, Arm64Stat* out, int flags) {
    static const auto fn = shim<AtStat>("fstatat");
    X86Stat x;
    const int r = fn(dirfd, path, &x, flags);
    if (r == 0 && out) to_arm64(x, out);
    return r;
}

// fts: each entry's fts_statp is a struct stat, filled in the caller's layout.
void fill_arm64_stat(const void* x86, void* out) {
    to_arm64(*static_cast<const X86Stat*>(x86), static_cast<Arm64Stat*>(out));
}
WINAPK_GUEST_ABI void* a64_fts_open(char* const* argv, int options, void* compar) {
    return fts_open_with_stat(argv, options, compar ? host_callable(compar) : nullptr, &fill_arm64_stat);
}

using EpollCtl = int(WINAPK_GUEST_ABI*)(int, int, int, X86EpollEvent*);
using EpollWait = int(WINAPK_GUEST_ABI*)(int, X86EpollEvent*, int, int);
using EpollPwait = int(WINAPK_GUEST_ABI*)(int, X86EpollEvent*, int, int, const void*);
using EpollPwait2 = int(WINAPK_GUEST_ABI*)(int, X86EpollEvent*, int, const void*, const void*);  // timespec, mask

WINAPK_GUEST_ABI int a64_epoll_ctl(int epfd, int op, int fd, Arm64EpollEvent* ev) {
    static const auto fn = shim<EpollCtl>("epoll_ctl");
    X86EpollEvent x{};
    if (ev) x = {ev->events, ev->data};
    return fn(epfd, op, fd, ev ? &x : nullptr);
}

int copy_events(int n, const std::vector<X86EpollEvent>& x, Arm64EpollEvent* out) {
    for (int i = 0; i < n; ++i) out[i] = {x[static_cast<size_t>(i)].events, x[static_cast<size_t>(i)].data};
    return n;
}

WINAPK_GUEST_ABI int a64_epoll_wait(int epfd, Arm64EpollEvent* events, int max, int timeout) {
    static const auto fn = shim<EpollWait>("epoll_wait");
    std::vector<X86EpollEvent> x(max > 0 ? static_cast<size_t>(max) : 1);
    const int n = fn(epfd, x.data(), max, timeout);
    return n > 0 ? copy_events(n, x, events) : n;
}

WINAPK_GUEST_ABI int a64_epoll_pwait(int epfd, Arm64EpollEvent* events, int max, int timeout, const void* mask) {
    static const auto fn = shim<EpollPwait>("epoll_pwait");
    std::vector<X86EpollEvent> x(max > 0 ? static_cast<size_t>(max) : 1);
    const int n = fn(epfd, x.data(), max, timeout, mask);
    return n > 0 ? copy_events(n, x, events) : n;
}

WINAPK_GUEST_ABI int a64_epoll_pwait2(int epfd, Arm64EpollEvent* events, int max, const void* ts, const void* mask) {
    static const auto fn = shim<EpollPwait2>("epoll_pwait2");
    std::vector<X86EpollEvent> x(max > 0 ? static_cast<size_t>(max) : 1);
    const int n = fn(epfd, x.data(), max, ts, mask);
    return n > 0 ? copy_events(n, x, events) : n;
}

// ---- open(2) flags --------------------------------------------------------------
//
// O_DIRECTORY, O_NOFOLLOW, O_DIRECT and O_LARGEFILE have asm-generic values on
// arm64 (arm64's O_DIRECTORY is x86_64's O_DIRECT); the rest are the same.

int to_x86_open_flags(int f) {
    constexpr int kA64Directory = 040000, kA64Nofollow = 0100000, kA64Direct = 0200000, kA64Largefile = 0400000;
    constexpr int kX86Direct = 040000, kX86Largefile = 0100000, kX86Directory = 0200000, kX86Nofollow = 0400000;
    return (f & ~(kA64Directory | kA64Nofollow | kA64Direct | kA64Largefile)) |
           ((f & kA64Directory) ? kX86Directory : 0) | ((f & kA64Nofollow) ? kX86Nofollow : 0) |
           ((f & kA64Direct) ? kX86Direct : 0) | ((f & kA64Largefile) ? kX86Largefile : 0);
}

// open and openat are variadic; AAPCS64 passes the mode as a named argument.
WINAPK_GUEST_ABI int a64_open(const char* path, int flags, unsigned mode) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(const char*, int, unsigned)>("open");
    return fn(path, to_x86_open_flags(flags), mode);
}
WINAPK_GUEST_ABI int a64_open_2(const char* path, int flags) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(const char*, int)>("__open_2");
    return fn(path, to_x86_open_flags(flags));
}
WINAPK_GUEST_ABI int a64_openat(int dirfd, const char* path, int flags, unsigned mode) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(int, const char*, int, unsigned)>("openat");
    return fn(dirfd, path, to_x86_open_flags(flags), mode);
}
WINAPK_GUEST_ABI int a64_openat_2(int dirfd, const char* path, int flags) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(int, const char*, int)>("__openat_2");
    return fn(dirfd, path, to_x86_open_flags(flags));
}
WINAPK_GUEST_ABI int a64_mkostemp(char* tmpl, int flags) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(char*, int)>("mkostemp");
    return fn(tmpl, to_x86_open_flags(flags));
}
WINAPK_GUEST_ABI int a64_mkostemps(char* tmpl, int suffix, int flags) {
    static const auto fn = shim<int(WINAPK_GUEST_ABI*)(char*, int, int)>("mkostemps");
    return fn(tmpl, suffix, to_x86_open_flags(flags));
}

// ---- code the guest writes itself ----------------------------------------------
//
// Memory an arm64 caller maps or protects executable holds arm64 code it is
// about to write and run: a JIT of its own (Mono, LuaJIT, V8), or a
// trampoline (Facebook's force_dlopen writes one over a string gai_strerror
// returned, so a call it makes appears to come from libc). The bridge runs
// only registered ranges as guest code, and would call anything else as a
// host function, so such ranges are registered here, and dropped again when
// unmapped or made non-executable.

constexpr int kProtExec = 4;
constexpr uint64_t kPage = 4096;

// Only ranges registered here are ever dropped: an app that makes a page of
// a loaded library's own code writable for a moment must not unregister the
// library.
std::mutex g_exec_lock;
std::set<uint64_t> g_exec_ranges;  // starts of the ranges track_exec registered

void track_exec(void* addr, size_t len, int prot) {
    if (!addr || !len) return;
    const uint64_t lo = reinterpret_cast<uint64_t>(addr) & ~(kPage - 1);
    const uint64_t hi = (reinterpret_cast<uint64_t>(addr) + len + kPage - 1) & ~(kPage - 1);
    void* start = reinterpret_cast<void*>(lo);
    std::lock_guard<std::mutex> hold(g_exec_lock);
    if (prot & kProtExec) {
        if (is_guest_code(start)) return;
        register_code(start, hi - lo);
        g_exec_ranges.insert(lo);
    } else if (g_exec_ranges.erase(lo)) {
        unregister_code(start);
    }
}

using Mmap = void*(WINAPK_GUEST_ABI*)(void*, size_t, int, int, int, int64_t);
using Mprotect = int(WINAPK_GUEST_ABI*)(void*, size_t, int);
using Munmap = int(WINAPK_GUEST_ABI*)(void*, size_t);

WINAPK_GUEST_ABI void* a64_mmap(void* addr, size_t len, int prot, int flags, int fd, int64_t off) {
    static const auto fn = shim<Mmap>("mmap");
    void* p = fn(addr, len, prot, flags, fd, off);
    if (p != reinterpret_cast<void*>(-1) && (prot & kProtExec)) track_exec(p, len, prot);
    return p;
}

WINAPK_GUEST_ABI int a64_mprotect(void* addr, size_t len, int prot) {
    static const auto fn = shim<Mprotect>("mprotect");
    const int r = fn(addr, len, prot);
    if (r == 0) track_exec(addr, len, prot);
    return r;
}

WINAPK_GUEST_ABI int a64_munmap(void* addr, size_t len) {
    static const auto fn = shim<Munmap>("munmap");
    const int r = fn(addr, len);
    if (r == 0) track_exec(addr, len, 0);
    return r;
}

// ---- system call numbers --------------------------------------------------------
//
// The shim's syscall() takes x86_64's numbers (futex 202, gettid 186...), and
// arm64 uses asm-generic's (futex 98, gettid 178...): an arm64 library's
// syscall(SYS_futex, ...) named another call. Both its syscall() import and
// its raw SVCs come here, are renumbered for the shim, and a few calls the
// shim's syscall() does not take go to its libc functions. The signal calls
// answer 0, as the shim's sigaction() family does: there are no guest
// signals yet.

using Syscall = int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);
using Fn3 = int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t, int64_t);
using Fn4 = int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t, int64_t, int64_t);

bool known_syscall(uint64_t nr) {
    switch (nr) {
        case 98: case 178: case 172: case 124: case 278: case 113: case 233: case 283:   // via the shim's syscall()
        case 123:
        case 56: case 57: case 62: case 63: case 64: case 79: case 80: case 93: case 94:  // via libc functions
        case 96: case 132: case 134: case 135: case 167:
        case 48: case 129: case 130: case 131:  // faccessat; kill, tkill, tgkill
        case 222: case 215: case 226:           // mmap, munmap, mprotect (mozjemalloc maps by raw call)
            return true;
        default:
            return false;
    }
}

// libc's convention: -1 and errno on failure.
int64_t do_syscall(uint64_t nr, const uint64_t a[6]) {
    static const auto shim_syscall = shim<Syscall>("syscall");
    auto via = [&](int64_t x86) {
        return shim_syscall(x86, static_cast<int64_t>(a[0]), static_cast<int64_t>(a[1]), static_cast<int64_t>(a[2]),
                            static_cast<int64_t>(a[3]), static_cast<int64_t>(a[4]));
    };
    auto fn3 = [&](const char* name) {
        const auto f = shim<Fn3>(name);
        return f(static_cast<int64_t>(a[0]), static_cast<int64_t>(a[1]), static_cast<int64_t>(a[2]));
    };
    switch (nr) {
        case 98: return via(202);   // futex
        case 178: return via(186);  // gettid
        case 172: return via(39);   // getpid
        case 124: return via(24);   // sched_yield
        case 123: return via(204);  // sched_getaffinity
        case 278: return via(318);  // getrandom
        case 113: return via(228);  // clock_gettime
        case 233: return via(28);   // madvise
        case 283: return via(324);  // membarrier
        case 96: return via(186);   // set_tid_address: returns the caller's tid
        case 48: {  // faccessat
            const auto f = shim<int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t, int64_t, int64_t)>("faccessat");
            return f(static_cast<int64_t>(a[0]), static_cast<int64_t>(a[1]), static_cast<int64_t>(a[2]),
                     static_cast<int64_t>(a[3]));
        }
        case 129: {  // kill
            const auto f = shim<int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t)>("kill");
            return f(static_cast<int64_t>(a[0]), static_cast<int64_t>(a[1]));
        }
        // tkill(tid, sig), tgkill(tgid, tid, sig): the threads are this
        // process's own, and no signal is delivered on Windows (sigaction
        // records handlers, it does not install them); signal 0, the check
        // that a thread is alive, is the common use and is answered yes.
        case 130: case 131: return 0;
        case 56: {  // openat: as open() for AT_FDCWD or an absolute path (the shim has no openat)
            const char* path = reinterpret_cast<const char*>(a[1]);
            if (static_cast<int>(a[0]) != -100 && !(path && path[0] == '/')) {
                *shim<int*(WINAPK_GUEST_ABI*)()>("__errno")() = 38;  // ENOSYS
                return -1;
            }
            const auto f = shim<Fn3>("open");
            return f(static_cast<int64_t>(a[1]), to_x86_open_flags(static_cast<int>(a[2])),
                     static_cast<int64_t>(a[3]));
        }
        case 57: return shim<int64_t(WINAPK_GUEST_ABI*)(int64_t)>("close")(static_cast<int64_t>(a[0]));
        case 62: return fn3("lseek");
        case 63: return fn3("read");
        case 64: return fn3("write");
        case 79:
            return a64_fstatat(static_cast<int>(a[0]), reinterpret_cast<const char*>(a[1]),
                               reinterpret_cast<Arm64Stat*>(a[2]), static_cast<int>(a[3]));
        case 80: return a64_fstat(static_cast<int>(a[0]), reinterpret_cast<Arm64Stat*>(a[1]));
        // Through the wrappers above, which track executable mappings.
        case 222:
            return reinterpret_cast<int64_t>(a64_mmap(reinterpret_cast<void*>(a[0]), static_cast<size_t>(a[1]),
                                                      static_cast<int>(a[2]), static_cast<int>(a[3]),
                                                      static_cast<int>(a[4]), static_cast<int64_t>(a[5])));
        case 215: return a64_munmap(reinterpret_cast<void*>(a[0]), static_cast<size_t>(a[1]));
        case 226: return a64_mprotect(reinterpret_cast<void*>(a[0]), static_cast<size_t>(a[1]), static_cast<int>(a[2]));
        case 93: case 94:
            shim<void(WINAPK_GUEST_ABI*)(int64_t)>("_exit")(static_cast<int64_t>(a[0]));
            return 0;
        default:  // sigaltstack, rt_sigaction, rt_sigprocmask, prctl
            return 0;
    }
}

// AAPCS64 passes variadic integers as named ones, so the sixth argument (mmap's offset) is a seventh parameter.
WINAPK_GUEST_ABI int64_t a64_syscall(int64_t nr, int64_t a0, int64_t a1, int64_t a2, int64_t a3, int64_t a4,
                                     int64_t a5) {
    const uint64_t a[6] = {static_cast<uint64_t>(a0), static_cast<uint64_t>(a1), static_cast<uint64_t>(a2),
                           static_cast<uint64_t>(a3), static_cast<uint64_t>(a4), static_cast<uint64_t>(a5)};
    if (!known_syscall(static_cast<uint64_t>(nr))) {
        *shim<int*(WINAPK_GUEST_ABI*)()>("__errno")() = 38;  // ENOSYS
        return -1;
    }
    return do_syscall(static_cast<uint64_t>(nr), a);
}

}  // namespace

bool linux_syscall(uint64_t nr, const uint64_t args[6], uint64_t* result) {
    if (!known_syscall(nr)) return false;
    const int64_t r = do_syscall(nr, args);
    // The kernel's convention: -errno on failure.
    *result = static_cast<uint64_t>(r == -1 ? -static_cast<int64_t>(*shim<int*(WINAPK_GUEST_ABI*)()>("__errno")()) : r);
    return true;
}

void* abi_override(const char* name) {
    static const struct {
        const char* name;
        void* fn;
    } kOverrides[] = {
        {"stat", reinterpret_cast<void*>(&a64_stat)},
        {"stat64", reinterpret_cast<void*>(&a64_stat)},
        {"lstat", reinterpret_cast<void*>(&a64_lstat)},
        {"lstat64", reinterpret_cast<void*>(&a64_lstat)},
        {"fstat", reinterpret_cast<void*>(&a64_fstat)},
        {"fstat64", reinterpret_cast<void*>(&a64_fstat)},
        {"fstatat", reinterpret_cast<void*>(&a64_fstatat)},
        {"fstatat64", reinterpret_cast<void*>(&a64_fstatat)},
        {"fts_open", reinterpret_cast<void*>(&a64_fts_open)},
        {"epoll_ctl", reinterpret_cast<void*>(&a64_epoll_ctl)},
        {"epoll_wait", reinterpret_cast<void*>(&a64_epoll_wait)},
        {"epoll_pwait", reinterpret_cast<void*>(&a64_epoll_pwait)},
        {"epoll_pwait2", reinterpret_cast<void*>(&a64_epoll_pwait2)},
        {"open", reinterpret_cast<void*>(&a64_open)},
        {"open64", reinterpret_cast<void*>(&a64_open)},
        {"__open_2", reinterpret_cast<void*>(&a64_open_2)},
        {"openat", reinterpret_cast<void*>(&a64_openat)},
        {"openat64", reinterpret_cast<void*>(&a64_openat)},
        {"__openat_2", reinterpret_cast<void*>(&a64_openat_2)},
        {"mkostemp", reinterpret_cast<void*>(&a64_mkostemp)},
        {"mkostemp64", reinterpret_cast<void*>(&a64_mkostemp)},
        {"mkostemps", reinterpret_cast<void*>(&a64_mkostemps)},
        {"mkostemps64", reinterpret_cast<void*>(&a64_mkostemps)},
        {"syscall", reinterpret_cast<void*>(&a64_syscall)},
        {"mmap", reinterpret_cast<void*>(&a64_mmap)},
        {"mmap64", reinterpret_cast<void*>(&a64_mmap)},
        {"mprotect", reinterpret_cast<void*>(&a64_mprotect)},
        {"munmap", reinterpret_cast<void*>(&a64_munmap)},
    };
    for (const auto& o : kOverrides) {
        if (std::strcmp(o.name, name) == 0) return mini_bionic_resolve(name) ? o.fn : nullptr;
    }
    return nullptr;
}

}  // namespace winapk::arm64
