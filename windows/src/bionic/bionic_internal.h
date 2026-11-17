// bionic_internal.h -- shared pieces of the Windows Bionic shim (src/bionic/).
//
// The shim is Android's libc, libm, libdl and liblog for guest code, on
// the Windows CRT and Win32. Each module (string.cpp, printf.cpp, ...) exports
// one table of names; mini_bionic_resolve looks a name up in all of them.
// Every function a guest can call is WINAPK_GUEST_ABI, and every
// value that crosses the boundary uses Bionic's layout and Linux's numbers.
// Upstream's runtime/translator/src/bionic_shim.cpp is the behavioural
// reference for each function.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

#include "winapk/guest_abi.h"

namespace winapk {
struct SysVVaList;  // abi_thunk.h
}  // namespace winapk

namespace winapk::bionic {

// Guest C types whose size differs on Windows: Android's `long` is 64 bits
// (LP64), Windows' is 32 (LLP64). Never write `long` in a guest signature.
using glong = int64_t;
using gulong = uint64_t;
// A guest long double (IEEE binary128) as AAPCS64 passes it: in one Q register.
typedef long long GuestQuad __attribute__((vector_size(16)));
// The host's long double (a plain double on Windows ARM64) as a guest one (misc.cpp).
GuestQuad ld_to_quad(long double v);

struct ShimSymbol {
    const char* name;
    void* addr;
};

struct ShimTable {
    const ShimSymbol* symbols;
    size_t count;
};

#define WINAPK_SHIM(name, fn) {name, reinterpret_cast<void*>(&fn)}
#define WINAPK_SHIM_TABLE(array) ShimTable{array, sizeof(array) / sizeof(array[0])}

// The module tables.
ShimTable string_symbols();   // string.cpp: <string.h>, <stdlib.h> conversions, <ctype.h>, _chk
ShimTable printf_symbols();   // printf.cpp: the printf family and liblog's formatted entry points
ShimTable pthread_symbols();  // pthread.cpp: threads, mutexes, conditions, keys, once
ShimTable stdio_symbols();    // stdio.cpp: FILE, file descriptors, directories, stat
ShimTable setjmp_symbols();   // setjmp.cpp: setjmp and longjmp
ShimTable misc_symbols();     // misc.cpp: time, sysconf, system properties, environment, math
ShimTable signal_symbols();   // signal.cpp: sigaction, sigaltstack, masks, delivery of faults and self-sent signals
ShimTable fd_symbols();       // fd.cpp: pipe, eventfd, poll, select, epoll
ShimTable socket_symbols();   // socket.cpp: BSD sockets on Winsock, getaddrinfo, inet_*
ShimTable looper_symbols();   // looper.cpp: the NDK's ALooper
ShimTable locale_symbols();   // locale.cpp: <locale.h>, UTF-8 conversion, <wctype.h>, more <wchar.h>
ShimTable zlib_symbols();     // zlib.cpp: libz.so, on the host's zlib

// ---- errno ------------------------------------------------------------------

// The guest's errno (`*__errno()`), per thread, in Linux numbering.
int* guest_errno();

// Sets the guest's errno from a UCRT errno value, translated to Linux's.
void set_errno_from_crt(int crt_errno);
// Sets the guest's errno from a Win32 error code.
void set_errno_from_win32(unsigned long win32_error);
// Linux's number for a UCRT errno value.
int linux_errno(int crt_errno);

// Linux errno values the shim sets directly.
constexpr int kEPERM = 1, kENOENT = 2, kEINTR = 4, kEIO = 5, kEBADF = 9, kEAGAIN = 11, kENOMEM = 12,
              kEACCES = 13, kEFAULT = 14, kEBUSY = 16, kEEXIST = 17, kENOTDIR = 20, kEISDIR = 21,
              kEINVAL = 22, kEMFILE = 24, kENOSPC = 28, kESPIPE = 29, kERANGE = 34, kEDEADLK = 35,
              kENAMETOOLONG = 36, kENOSYS = 38, kENOTEMPTY = 39, kETIMEDOUT = 110, kENOTSUP = 95, kESRCH = 3,
              kECHILD = 10, kEXDEV = 18, kEMLINK = 31;

// ---- shared between modules ---------------------------------------------------

// signal.cpp: sends `sig` to the thread with Windows id `tid` (0: the calling
// thread) the way tgkill does: delivered now if it is this thread, injected
// into the other one otherwise. Returns 0 or a Linux errno.
int signal_send(uint32_t tid, int sig);
// The calling thread's blocked set, for sigsetjmp/siglongjmp (setjmp.cpp).
uint64_t signal_mask_of_current_thread();
void signal_set_mask_of_current_thread(uint64_t mask);
// The calling thread's own alternate signal stack, as Bionic gives every
// thread one; once per thread, nothing if the guest set its own.
void signal_altstack_for_current_thread();

// pthread.cpp: the Windows thread id behind a guest pthread_t (0 if null).
uint32_t thread_id_of(const void* pthread);

// strerror's text for a Linux errno value (string.cpp).
const char* errno_text(int linux_errno);

// stdio.cpp: write to a guest FILE* (returns the bytes written) or to a guest
// file descriptor (returns -1 and sets errno on failure), and the guest's
// stdout and stderr.
size_t file_write(void* guest_file, const char* data, size_t n);
int64_t fd_write(int fd, const void* data, size_t n);
// pread(2) on a guest file descriptor: -1 with errno set, and the file position left alone.
int64_t fd_pread(int fd, void* data, size_t n, int64_t off);
// The CRT FILE* behind a guest FILE* (the __sF entries are the standard streams).
void* host_stream(void* guest_file);
void* guest_stdout();
void* guest_stderr();

// printf.cpp: what vasprintf makes of a guest format string and va_list.
std::string format_va(const char* fmt, SysVVaList* va);

// paths.cpp: the host path for a guest path (Android's absolute paths are
// mapped under the android root; see paths.cpp).
std::wstring host_path(const char* guest_path);
// Whether host_path() maps `guest_path` as an Android path; a host path it
// leaves as it is.
bool is_android_path(const char* guest_path);
// Android names (UTF-8, '/' between them) as the host stores them, the
// characters Windows forbids in a name as U+F000 + the character, and the
// inverse for one listed name, in place.
std::wstring host_names(const char* android_names, size_t len);
void android_name(wchar_t* host_name);

// cacerts.cpp: the host directory that answers Android's system CA store
// (/system/etc/security/cacerts), built from the roots Windows trusts on the
// first call; empty when it is turned off or could not be built.
std::wstring android_cacerts_dir(const std::wstring& android_root);

// ---- descriptors that are not files (fd.cpp) -------------------------------------
// Pipes, eventfds, epoll instances and sockets. Each holds a CRT descriptor
// number of its own (open on NUL), so numbers never collide with files, and
// the descriptor functions in stdio.cpp hand calls on such a number to its
// object. dup shares the object; it is destroyed when its last number closes.
// Results follow the syscalls: >= 0, or -1 with the guest's errno set.
class FdObject {
public:
    virtual ~FdObject() = default;
    virtual int64_t read(void* buf, size_t n);
    virtual int64_t write(const void* buf, size_t n);
    // The subset of `events` (POLLIN 1, POLLOUT 4, ...) that is ready, plus
    // POLLERR 8 / POLLHUP 16 whatever was asked.
    virtual short ready(short events) = 0;
    // st_mode for fstat (S_IFIFO, S_IFSOCK, ...).
    virtual unsigned mode() const { return 0010000; }
    virtual int ioctl(int request, void* arg);
    // What the waits (fd.cpp) sleep on besides fd_notify(). A socket's handle
    // (a SOCKET; kNoSocket when this is not one): Winsock tells nobody when a
    // socket becomes ready, so a wait that includes one sleeps in WSAPoll.
    static constexpr uintptr_t kNoSocket = ~uintptr_t{0};
    virtual uintptr_t poll_socket() const { return kNoSocket; }
    // Milliseconds until time alone makes this readable (an armed timerfd);
    // -1 when it never does.
    virtual int64_t ready_in_ms() { return -1; }
    // Counters for edge-triggered epoll (EPOLLET): they move whenever a new
    // "readable" or "writable" edge may have happened that the level does not
    // show -- more bytes written to a pipe or eventfd that was readable
    // already; for a socket, whose arrivals cannot be seen, a read that took
    // bytes (more may be left) or a send that would have blocked.
    virtual uint32_t in_edges() const { return 0; }
    virtual uint32_t out_edges() const { return 0; }
    // O_NONBLOCK (fcntl F_SETFL, ioctl FIONBIO, the *_NONBLOCK flags).
    virtual void set_nonblocking(bool on) { nonblocking = on; }
    bool nonblocking = false;
};
std::shared_ptr<FdObject> fd_object(int fd);
// A new descriptor number for `object`, or -1 (errno set).
int fd_install(std::shared_ptr<FdObject> object);
// Close/dup for object descriptors; false if `fd` is not one.
bool fd_close(int fd);
int fd_dup(int fd, int to);  // to < 0: lowest free number
// 0, 1, 2: the runtime's own standard streams, which guest calls never close or replace.
bool fd_is_host_std(int fd);
// Something became readable/writable: wakes poll, select and epoll_wait.
void fd_notify();
// poll(2) over guest descriptors, for other modules (looper.cpp).
struct PollFd {
    int fd;
    short events, revents;
};
int fd_poll(PollFd* fds, uint64_t n, int timeout_ms);
// A descriptor on the named pipe (mkfifo) whose full lower-case host path is
// `key`, opened with Linux's `flags`; -1 with errno set.
int fifo_open(const std::wstring& key, int flags);

// ---- small helpers ----------------------------------------------------------

// A call the shim cannot do yet fails as Linux reports the failure (errno, the documented result) and
// says so in the app's log the first time: "[win.apk] <call>: <what>". An answer of "done" with nothing
// done is what keeps a caller waiting or looping; the line is what tells a report why the app gave up.
// `said` is the call site's own flag: `static std::atomic<bool> said{false};`.
void say_once(std::atomic<bool>& said, const char* call, const char* what);

// Writes a guest log line the way liblog does: "<prio letter>/<tag>: <text>".
void log_line(int prio, const char* tag, const char* text);

}  // namespace winapk::bionic
