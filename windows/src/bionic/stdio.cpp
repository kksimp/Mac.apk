// stdio.cpp -- file descriptors, FILE streams, directories and stat, for guest
// code.
//
// Descriptors are the UCRT's own (_wopen and friends), so 0, 1 and 2 are the
// process's standard streams as on Android. What differs is translated here:
// Linux's open flags and errno numbers, UTF-8 paths (Windows' native APIs take
// UTF-16), and the layouts of Bionic's struct stat and struct dirent on
// x86_64. A guest FILE* is the CRT's FILE*; guest code never looks inside one.
// The standard streams are also exported the two ways Bionic does: the
// variables stdin/stdout/stderr (API 23+) and the older __sF array, whose
// elements stand for the same three streams.

#include "bionic_internal.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include <direct.h>
#include <fcntl.h>
#include <io.h>
#include <sys/stat.h>
#include <windows.h>
#include <bcrypt.h>

namespace winapk::bionic {

namespace {

std::string narrow(const wchar_t* w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n > 0 ? n - 1 : 0), '\0');
    if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

int fail_crt() {
    set_errno_from_crt(errno);
    return -1;
}

// ---- the standard streams ----------------------------------------------------
// Bionic's __sF: three FILE structs, 152 bytes each on LP64. Only their
// addresses matter; they are mapped back to the CRT's streams.
alignas(16) char g_sF[3 * 152];
FILE* g_stdin_var = stdin;
FILE* g_stdout_var = stdout;
FILE* g_stderr_var = stderr;

FILE* host_file(void* f) {
    const auto* p = static_cast<const char*>(f);
    if (p >= g_sF && p < g_sF + sizeof(g_sF)) {
        const ptrdiff_t i = (p - g_sF) / 152;
        return i == 0 ? stdin : i == 1 ? stdout : stderr;
    }
    return static_cast<FILE*>(f);
}

// ---- file descriptors -----------------------------------------------------------
// Linux's open(2) flags (x86_64 values).
constexpr int kOWrOnly = 01, kORdWr = 02, kOCreat = 0100, kOExcl = 0200, kOTrunc = 01000, kOAppend = 02000,
              kODirectory = 0200000, kOCloexec = 02000000;

int crt_open_flags(int f) {
    int c = _O_BINARY;
    c |= (f & kORdWr) ? _O_RDWR : (f & kOWrOnly) ? _O_WRONLY : _O_RDONLY;
    if (f & kOCreat) c |= _O_CREAT;
    if (f & kOExcl) c |= _O_EXCL;
    if (f & kOTrunc) c |= _O_TRUNC;
    if (f & kOAppend) c |= _O_APPEND;
    if (f & kOCloexec) c |= _O_NOINHERIT;
    return c;
}

// /dev/urandom and /dev/random: a character device that reads as an endless
// stream from the system RNG. Windows has no such file, so open() failed and
// native code that CHECKs the descriptor (folly's Random.cpp, in WhatsApp
// right after registration) aborted the process.
class RandomDevice : public FdObject {
public:
    int64_t read(void* buf, size_t n) override {
        if (n == 0) return 0;
        if (n > 0x7fffffff) n = 0x7fffffff;
        if (BCryptGenRandom(nullptr, static_cast<PUCHAR>(buf), static_cast<ULONG>(n),
                            BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
            *guest_errno() = 5;  // EIO
            return -1;
        }
        return static_cast<int64_t>(n);
    }
    int64_t write(const void*, size_t n) override { return static_cast<int64_t>(n); }  // entropy is discarded
    short ready(short events) override { return static_cast<short>(events & (1 | 4)); }  // POLLIN | POLLOUT
    unsigned mode() const override { return 0020000; }  // S_IFCHR
};

// /proc/self/maps (and /proc/<our pid>/maps): synthesized from the guest
// linker on every open, as the kernel does, into a delete-on-close temporary
// file, so read, lseek, fstat and stdio all behave as on a regular file.
bool is_proc_maps(const char* path) {
    if (!path) return false;
    if (std::strcmp(path, "/proc/self/maps") == 0 || std::strcmp(path, "/proc/thread-self/maps") == 0) return true;
    char own[48];
    std::snprintf(own, sizeof own, "/proc/%lu/maps", static_cast<unsigned long>(GetCurrentProcessId()));
    return std::strcmp(path, own) == 0;
}

// A delete-on-close temporary file holding `content`, as a CRT descriptor
// positioned at 0; -1 with errno set.
int open_temp_with(const std::string& content) {
    wchar_t dir[MAX_PATH + 1], name[MAX_PATH + 1];
    if (!GetTempPathW(MAX_PATH, dir) || !GetTempFileNameW(dir, L"prc", 0, name)) {
        *guest_errno() = 5;  // EIO
        return -1;
    }
    const int fd = _wopen(name, _O_RDWR | _O_BINARY | _O_TEMPORARY);
    if (fd < 0) {
        set_errno_from_crt(errno);
        DeleteFileW(name);
        return -1;
    }
    if (!content.empty() && _write(fd, content.data(), static_cast<unsigned>(content.size())) < 0) {
        set_errno_from_crt(errno);
        _close(fd);
        return -1;
    }
    _lseek(fd, 0, SEEK_SET);
    return fd;
}

std::string proc_maps_text() {
    GuestLinker* linker = GuestLinker::process();
    return linker ? linker->proc_maps() : std::string();
}

bool is_random_device(const char* path) {
    return path && (std::strcmp(path, "/dev/urandom") == 0 || std::strcmp(path, "/dev/random") == 0);
}

// The host path of what descriptor `fd` has open (a directory, for the *at()
// calls and fdopendir): Windows knows it from the handle. False with errno set.
bool fd_host_path(int fd, std::wstring* out) {
    const HANDLE h = fd_object(fd) ? INVALID_HANDLE_VALUE : reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    if (h == INVALID_HANDLE_VALUE) {
        *guest_errno() = kEBADF;
        return false;
    }
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = GetFinalPathNameByHandleW(h, buf, MAX_PATH * 4, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (n == 0 || n >= MAX_PATH * 4) {
        *guest_errno() = kENOTDIR;
        return false;
    }
    // "\\?\C:\dir" or "\\?\UNC\server\share\dir": the prefix goes, because
    // under it a '/' in the guest's part of a joined path is no separator.
    out->assign(buf, n);
    if (out->compare(0, 8, L"\\\\?\\UNC\\") == 0) out->replace(0, 8, L"\\\\");
    else if (out->compare(0, 4, L"\\\\?\\") == 0) out->erase(0, 4);
    return true;
}

// The *at() calls: the file `path` names relative to the directory open as
// `dirfd`, as a path g_open and friends take. With AT_FDCWD (-100) or an
// absolute path that is `path` itself; otherwise the directory's host path
// joined with it. Dart's dart:io opens every file this way. False with errno set.
bool at_path(int dirfd, const char* path, std::string* out) {
    if (!path) {
        *guest_errno() = kEFAULT;
        return false;
    }
    // ("C:\x" is a host path; "a:b" a name in the directory, as on Android.)
    if (dirfd == -100 || path[0] == '/' || (path[0] && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))) {
        *out = path;
        return true;
    }
    std::wstring dir;
    if (!fd_host_path(dirfd, &dir)) return false;
    *out = narrow(dir.c_str());
    // The guest's part is Android names, stored as an Android path's are (paths.cpp).
    if (path[0]) *out += '\\' + narrow(host_names(path, std::strlen(path)).c_str());
    return true;
}

// ---- named pipes ---------------------------------------------------------------------
// Windows has no FIFO files. mkfifo writes a small marker file with these
// bytes; stat reports it as a FIFO and open gives a descriptor on the
// process's pipe for that path (fifo_open, fd.cpp). Realm creates its FIFOs
// next to the database (the `.note` notifier, the `.cv` condition variables);
// the missing-function stub answered success without a file, and its open
// failed.
constexpr char kFifoMarker[16] = {'W', 'i', 'n', '.', 'a', 'p', 'k', ' ', 'F', 'I', 'F', 'O', '\n', 0, 0, 0};

bool is_fifo_marker(const std::wstring& w) {
    WIN32_FILE_ATTRIBUTE_DATA d;
    if (!GetFileAttributesExW(w.c_str(), GetFileExInfoStandard, &d) ||
        (d.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || d.nFileSizeHigh != 0 ||
        d.nFileSizeLow != sizeof(kFifoMarker)) {
        return false;
    }
    const HANDLE h = CreateFileW(w.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    char got[sizeof(kFifoMarker)];
    DWORD n = 0;
    const bool ok = ReadFile(h, got, sizeof(got), &n, nullptr) && n == sizeof(got) &&
                    std::memcmp(got, kFifoMarker, sizeof(got)) == 0;
    CloseHandle(h);
    return ok;
}

// The key fifo_open shares a FIFO's buffer by: its full path, lower case.
std::wstring fifo_key(const std::wstring& w) {
    wchar_t full[MAX_PATH * 4];
    const DWORD n = GetFullPathNameW(w.c_str(), MAX_PATH * 4, full, nullptr);
    std::wstring k = (n > 0 && n < MAX_PATH * 4) ? std::wstring(full, n) : w;
    for (wchar_t& c : k) c = (c == L'/') ? L'\\' : static_cast<wchar_t>(towlower(c));
    return k;
}

WINAPK_GUEST_ABI int g_mkfifo(const char* path, unsigned) {
    const std::wstring w = host_path(path);
    const HANDLE h = CreateFileW(w.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        set_errno_from_win32(GetLastError());  // ERROR_FILE_EXISTS: EEXIST
        return -1;
    }
    DWORD n = 0;
    const bool ok = WriteFile(h, kFifoMarker, sizeof(kFifoMarker), &n, nullptr) && n == sizeof(kFifoMarker);
    CloseHandle(h);
    if (!ok) {
        DeleteFileW(w.c_str());
        *guest_errno() = kEIO;
        return -1;
    }
    return 0;
}
WINAPK_GUEST_ABI int g_mkfifoat(int dirfd, const char* path, unsigned mode) {
    std::string p;
    return at_path(dirfd, path, &p) ? g_mkfifo(p.c_str(), mode) : -1;
}
// mknod makes only FIFOs here (S_IFIFO); device nodes need privileges an app does not have.
WINAPK_GUEST_ABI int g_mknod(const char* path, unsigned mode, uint64_t) {
    if ((mode & 0170000) == 0010000) return g_mkfifo(path, mode);
    *guest_errno() = kEPERM;
    return -1;
}

// open() is variadic (its mode is the third argument only with O_CREAT), but a
// variadic int travels in the same register as a fixed one.
WINAPK_GUEST_ABI int g_open(const char* path, int flags, unsigned mode) {
    if (is_random_device(path)) return fd_install(std::make_shared<RandomDevice>());
    if (is_proc_maps(path)) return open_temp_with(proc_maps_text());
    const std::wstring w = host_path(path);
    if (is_fifo_marker(w)) return fifo_open(fifo_key(w), flags);
    // Linux opens a directory read-only without O_DIRECTORY too (O_PATH or
    // plain O_RDONLY, then fstat or fdopendir); _wopen would refuse it.
    const DWORD a = GetFileAttributesW(w.c_str());
    const bool read_only = (flags & (kOWrOnly | kORdWr | kOCreat)) == 0;
    if ((flags & kODirectory) || (read_only && a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY))) {
        if (a == INVALID_FILE_ATTRIBUTES) {
            set_errno_from_win32(GetLastError());
            return -1;
        }
        if (!(a & FILE_ATTRIBUTE_DIRECTORY)) {
            *guest_errno() = kENOTDIR;
            return -1;
        }
        // A directory descriptor (for fsync or *at() calls): a handle wrapped
        // in a CRT descriptor.
        HANDLE h = CreateFileW(w.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (h == INVALID_HANDLE_VALUE) {
            set_errno_from_win32(GetLastError());
            return -1;
        }
        return _open_osfhandle(reinterpret_cast<intptr_t>(h), _O_RDONLY);
    }
    const int fd = _wopen(w.c_str(), crt_open_flags(flags), (mode & 0200) ? (_S_IREAD | _S_IWRITE) : _S_IREAD);
    return fd < 0 ? fail_crt() : fd;
}
WINAPK_GUEST_ABI int g_open_2(const char* path, int flags) { return g_open(path, flags, 0); }
WINAPK_GUEST_ABI int g_openat(int dirfd, const char* path, int flags, unsigned mode) {
    std::string p;
    return at_path(dirfd, path, &p) ? g_open(p.c_str(), flags, mode) : -1;
}
WINAPK_GUEST_ABI int g_openat_2(int dirfd, const char* path, int flags) { return g_openat(dirfd, path, flags, 0); }
WINAPK_GUEST_ABI int g_creat(const char* path, unsigned mode) { return g_open(path, kOWrOnly | kOCreat | kOTrunc, mode); }
// Pipes, eventfds, epoll and sockets are objects (fd.cpp); each descriptor
// call checks for one first.
WINAPK_GUEST_ABI int g_close(int fd) {
    if (fd_close(fd)) return 0;
    if (fd_is_host_std(fd)) return 0;  // the runtime's log stays open
    return _close(fd) < 0 ? fail_crt() : 0;
}
WINAPK_GUEST_ABI int64_t g_read(int fd, void* buf, size_t n) {
    if (auto o = fd_object(fd)) return o->read(buf, n);
    const int r = _read(fd, buf, static_cast<unsigned>(n > 0x7fffffff ? 0x7fffffff : n));
    return r < 0 ? fail_crt() : r;
}
WINAPK_GUEST_ABI int64_t g_write(int fd, const void* buf, size_t n) {
    if (auto o = fd_object(fd)) return o->write(buf, n);
    const int r = _write(fd, buf, static_cast<unsigned>(n > 0x7fffffff ? 0x7fffffff : n));
    return r < 0 ? fail_crt() : r;
}
WINAPK_GUEST_ABI int64_t g_lseek(int fd, int64_t off, int whence) {
    if (fd_object(fd)) {
        *guest_errno() = kESPIPE;
        return -1;
    }
    const int64_t r = _lseeki64(fd, off, whence);
    return r < 0 ? fail_crt() : r;
}
// pread/pwrite at an offset without moving the file position.
WINAPK_GUEST_ABI int64_t g_pread(int fd, void* buf, size_t n, int64_t off) {
    const int64_t at = _lseeki64(fd, 0, SEEK_CUR);
    if (at < 0 || _lseeki64(fd, off, SEEK_SET) < 0) return fail_crt();
    const int r = _read(fd, buf, static_cast<unsigned>(n));
    const int e = errno;
    _lseeki64(fd, at, SEEK_SET);
    if (r < 0) {
        set_errno_from_crt(e);
        return -1;
    }
    return r;
}
WINAPK_GUEST_ABI int64_t g_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    const int64_t at = _lseeki64(fd, 0, SEEK_CUR);
    if (at < 0 || _lseeki64(fd, off, SEEK_SET) < 0) return fail_crt();
    const int r = _write(fd, buf, static_cast<unsigned>(n));
    const int e = errno;
    _lseeki64(fd, at, SEEK_SET);
    if (r < 0) {
        set_errno_from_crt(e);
        return -1;
    }
    return r;
}
WINAPK_GUEST_ABI int64_t g_read_chk(int fd, void* buf, size_t n, size_t bn) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: read: prevented buffer overflow\n");
        std::abort();
    }
    return g_read(fd, buf, n);
}
WINAPK_GUEST_ABI int64_t g_pread_chk(int fd, void* buf, size_t n, int64_t off, size_t bn) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: pread: prevented buffer overflow\n");
        std::abort();
    }
    return g_pread(fd, buf, n, off);
}
WINAPK_GUEST_ABI int64_t g_write_chk(int fd, const void* buf, size_t n, size_t bn) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: write: prevented buffer over-read\n");
        std::abort();
    }
    return g_write(fd, buf, n);
}
// The missing one of the family answered 0 through the loader's fallback, and a caller that writes
// until everything is written never ended: Play services' icing index, a whole processor for good.
WINAPK_GUEST_ABI int64_t g_pwrite_chk(int fd, const void* buf, size_t n, int64_t off, size_t bn) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: pwrite: prevented buffer over-read\n");
        std::abort();
    }
    return g_pwrite(fd, buf, n, off);
}
WINAPK_GUEST_ABI int g_dup(int fd) {
    const int o = fd_dup(fd, -1);
    if (o != -2) return o;
    const int r = _dup(fd);
    return r < 0 ? fail_crt() : r;
}
WINAPK_GUEST_ABI int g_dup2(int fd, int to) {
    const int o = fd_dup(fd, to);
    if (o != -2) return o;
    fd_close(to);  // a file replacing an object
    // The guest's own 0-2 are the runtime's streams too: a file put there (a
    // /dev/null, a log file) would take the runtime's log with it.
    if (fd_is_host_std(to)) return to;
    return _dup2(fd, to) < 0 ? fail_crt() : to;
}
WINAPK_GUEST_ABI int g_dup3(int fd, int to, int) { return g_dup2(fd, to); }
// Linux syncs whatever the descriptor names: a directory (LevelDB syncs the
// database's directory after writing its MANIFEST, as POSIX asks for a new
// file's name to be durable) or a file opened read-only. FlushFileBuffers
// refuses both, a directory outright and a handle without write access with
// ERROR_ACCESS_DENIED, which the CRT turned into EBADF, so every LevelDB open
// failed ("IO error: <dir>: Bad file descriptor": Google Play services' GCM,
// HomeKit and location stores). A directory's entries are NTFS metadata, which
// the file system journals itself; a read-only handle has nothing of its own
// to flush. Both succeed, as on Linux.
WINAPK_GUEST_ABI int g_fsync(int fd) {
    if (_commit(fd) == 0) return 0;
    const intptr_t h = _get_osfhandle(fd);
    if (h != -1) {
        BY_HANDLE_FILE_INFORMATION info{};
        const bool dir = GetFileInformationByHandle(reinterpret_cast<HANDLE>(h), &info) &&
                         (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY);
        if (dir || _doserrno == ERROR_ACCESS_DENIED) return 0;
    }
    return fail_crt();
}
WINAPK_GUEST_ABI int g_ftruncate(int fd, int64_t len) {
    const errno_t e = _chsize_s(fd, len);
    if (e) {
        set_errno_from_crt(e);
        return -1;
    }
    return 0;
}
// posix_fallocate: the file is at least offset + len bytes afterwards, as on
// Linux. SetEndOfFile extends without writing the zeros (NTFS reads the new
// range as zeros), so a large reservation costs no I/O; the space itself is
// not reserved, which Linux file systems without fallocate also allow. Realm
// (Bit) reserves its database file with it, then maps the file: the shim's
// missing-function stub answered success without growing it.
WINAPK_GUEST_ABI int g_posix_fallocate(int fd, int64_t offset, int64_t len) {
    if (offset < 0 || len <= 0) return 22;  // EINVAL
    const intptr_t h = _get_osfhandle(fd);
    if (h == -1 || fd_object(fd)) return 9;  // EBADF
    const HANDLE file = reinterpret_cast<HANDLE>(h);
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(file, &size)) return 9;
    if (size.QuadPart >= offset + len) return 0;
    LARGE_INTEGER end{}, here{}, zero{};
    end.QuadPart = offset + len;
    // SetEndOfFile works at the file pointer, which the descriptor shares.
    if (!SetFilePointerEx(file, zero, &here, FILE_CURRENT)) return 9;
    const bool ok = SetFilePointerEx(file, end, nullptr, FILE_BEGIN) && SetEndOfFile(file);
    const DWORD error = ok ? 0 : GetLastError();
    SetFilePointerEx(file, here, nullptr, FILE_BEGIN);
    if (ok) return 0;
    return error == ERROR_DISK_FULL ? 28 : error == ERROR_ACCESS_DENIED ? 9 : 5;  // ENOSPC, EBADF, EIO
}
// Linux fallocate(2): mode 0 is posix_fallocate with errno; FALLOC_FL_KEEP_SIZE
// alone only reserves space, which there is nothing to do for; punching holes
// and the other modes are not supported (EOPNOTSUPP), as on file systems
// without them.
WINAPK_GUEST_ABI int g_fallocate(int fd, int mode, int64_t offset, int64_t len) {
    int e = 0;
    if (mode == 0) e = g_posix_fallocate(fd, offset, len);
    else if (mode != 1) e = 95;
    if (!e) return 0;
    *guest_errno() = e;
    return -1;
}
WINAPK_GUEST_ABI int g_isatty(int fd) { return !fd_object(fd) && _isatty(fd) ? 1 : 0; }
// fcntl: descriptor flags are accepted and ignored (no fork, so close-on-exec
// has nothing to protect); F_GETFL answers read-write.
WINAPK_GUEST_ABI int g_fcntl(int fd, int cmd, int64_t arg) {
    if (auto o = fd_object(fd)) {
        // O_NONBLOCK is the status flag that matters for objects.
        if (cmd == 3) return kORdWr | (o->nonblocking ? 04000 : 0);  // F_GETFL
        if (cmd == 4) {                                              // F_SETFL
            o->set_nonblocking((arg & 04000) != 0);
            return 0;
        }
    }
    switch (cmd) {
        case 0: return g_dup(fd);                      // F_DUPFD
        case 1030: return g_dup(fd);                   // F_DUPFD_CLOEXEC
        case 1: case 2: case 4: return 0;              // F_GETFD, F_SETFD, F_SETFL
        case 3: return kORdWr;                         // F_GETFL
        // Locks are always granted, so F_GETLK answers that nothing holds
        // one: l_type, the struct flock's first field on both ABIs, is
        // F_UNLCK. Left as the caller set it, SQLite's own VFS (an app's
        // bundled libsqlite3) saw a lock it never got and failed with
        // SQLITE_PROTOCOL ("locking protocol" in X).
        case 5:                                        // F_GETLK
            if (arg) *reinterpret_cast<int16_t*>(arg) = 2;
            return 0;
        case 6: case 7: return 0;                      // F_SETLK, F_SETLKW
        default: (void)arg; *guest_errno() = kEINVAL; return -1;
    }
}
WINAPK_GUEST_ABI int g_ioctl(int fd, int request, void* arg) {
    if (auto o = fd_object(fd)) {
        if (request == 0x5421) {  // FIONBIO
            o->set_nonblocking(*static_cast<int*>(arg) != 0);
            return 0;
        }
        return o->ioctl(request, arg);
    }
    *guest_errno() = 25;  // ENOTTY
    return -1;
}

// ---- stat ----------------------------------------------------------------------------
// Bionic's struct stat on x86_64.
struct GuestStat {
    uint64_t st_dev, st_ino, st_nlink;
    uint32_t st_mode, st_uid, st_gid;
    int32_t pad0;
    uint64_t st_rdev;
    int64_t st_size, st_blksize, st_blocks;
    int64_t st_atime_sec, st_atime_nsec, st_mtime_sec, st_mtime_nsec, st_ctime_sec, st_ctime_nsec;
    int64_t unused[3];
};
static_assert(sizeof(GuestStat) == 144, "Bionic's struct stat on x86_64");

void fill_stat(const struct _stat64& s, GuestStat* g) {
    std::memset(g, 0, sizeof(*g));
    const bool dir = (s.st_mode & _S_IFMT) == _S_IFDIR;
    g->st_dev = s.st_dev;
    g->st_ino = s.st_ino;
    g->st_nlink = s.st_nlink ? s.st_nlink : 1;
    g->st_mode = dir ? (0040000 | 0771) : (0100000 | ((s.st_mode & _S_IWRITE) ? 0660 : 0440));
    g->st_size = s.st_size;
    g->st_blksize = 4096;
    g->st_blocks = (s.st_size + 511) / 512;
    g->st_atime_sec = s.st_atime;
    g->st_mtime_sec = s.st_mtime;
    g->st_ctime_sec = s.st_ctime;
}
// The CRT leaves st_ino 0 for every file, and SQLite's unix VFS (bundled in many
// apps) keys its per-file lock and shared-memory state on (st_dev, st_ino): every
// database in the process then shared one lock, and a write to one made the
// others "database is locked". Waze never created its tile table and showed no
// map. The volume serial and the NTFS file index are what identify a file.
void fill_identity(HANDLE h, GuestStat* g) {
    BY_HANDLE_FILE_INFORMATION info;
    if (h == INVALID_HANDLE_VALUE || !GetFileInformationByHandle(h, &info)) return;
    g->st_dev = info.dwVolumeSerialNumber;
    g->st_ino = (static_cast<uint64_t>(info.nFileIndexHigh) << 32) | info.nFileIndexLow;
}
WINAPK_GUEST_ABI int g_stat(const char* path, GuestStat* g) {
    if (is_proc_maps(path)) {  // a regular file of no length, as procfs reports its files
        std::memset(g, 0, sizeof(*g));
        g->st_mode = 0100444;  // S_IFREG | r--r--r--
        g->st_nlink = 1;
        g->st_blksize = 4096;
        return 0;
    }
    const std::wstring w = host_path(path);
    struct _stat64 s;
    if (_wstat64(w.c_str(), &s) < 0) return fail_crt();
    fill_stat(s, g);
    if (g->st_size == sizeof(kFifoMarker) && is_fifo_marker(w)) {
        g->st_mode = 0010000 | 0600;  // S_IFIFO
        g->st_size = g->st_blocks = 0;
    }
    // No access rights asked for, so this opens a file another program holds too.
    const HANDLE h = CreateFileW(w.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                                 OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h != INVALID_HANDLE_VALUE) {
        fill_identity(h, g);
        CloseHandle(h);
    }
    return 0;
}
WINAPK_GUEST_ABI int g_fstat(int fd, GuestStat* g) {
    if (auto o = fd_object(fd)) {
        std::memset(g, 0, sizeof(*g));
        g->st_mode = o->mode() | 0600;
        g->st_nlink = 1;
        g->st_blksize = 4096;
        return 0;
    }
    struct _stat64 s;
    if (_fstat64(fd, &s) < 0) return fail_crt();
    fill_stat(s, g);
    fill_identity(reinterpret_cast<HANDLE>(_get_osfhandle(fd)), g);
    return 0;
}
// memfd_create: an anonymous file. A temporary file deleted on close behaves
// the same for its uses (ftruncate, read/write, mmap of a private copy).
WINAPK_GUEST_ABI int g_memfd_create(const char*, unsigned) {
    wchar_t dir[MAX_PATH], path[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, dir) || !GetTempFileNameW(dir, L"mfd", 0, path)) {
        set_errno_from_win32(GetLastError());
        return -1;
    }
    HANDLE h = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        set_errno_from_win32(GetLastError());
        return -1;
    }
    const int fd = _open_osfhandle(reinterpret_cast<intptr_t>(h), _O_RDWR | _O_BINARY);
    if (fd < 0) {
        CloseHandle(h);
        return fail_crt();
    }
    return fd;
}

// flock: LOCK_SH 1, LOCK_EX 2, LOCK_NB 4, LOCK_UN 8, on the whole file.
WINAPK_GUEST_ABI int g_flock(int fd, int op) {
    const HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    if (h == INVALID_HANDLE_VALUE) {
        *guest_errno() = kEBADF;
        return -1;
    }
    OVERLAPPED at{};
    if (op & 8) {
        UnlockFileEx(h, 0, MAXDWORD, MAXDWORD, &at);
        return 0;
    }
    DWORD flags = (op & 2) ? LOCKFILE_EXCLUSIVE_LOCK : 0;
    if (op & 4) flags |= LOCKFILE_FAIL_IMMEDIATELY;
    if (LockFileEx(h, flags, 0, MAXDWORD, MAXDWORD, &at)) return 0;
    const DWORD e = GetLastError();
    if (e == ERROR_LOCK_VIOLATION || e == ERROR_IO_PENDING) {
        *guest_errno() = kEAGAIN;  // EWOULDBLOCK
        return -1;
    }
    set_errno_from_win32(e);
    return -1;
}

// Bionic's struct statvfs (x86_64): eleven unsigned longs, then spare ints.
struct GuestStatvfs {
    uint64_t f_bsize, f_frsize, f_blocks, f_bfree, f_bavail, f_files, f_ffree, f_favail, f_fsid, f_flag, f_namemax;
    uint32_t spare[6];
};
static_assert(sizeof(GuestStatvfs) == 112, "Bionic's struct statvfs on x86_64");
int statvfs_of(const std::wstring& path, GuestStatvfs* out) {
    ULARGE_INTEGER avail, total, free_bytes;
    BOOL ok = GetDiskFreeSpaceExW(path.c_str(), &avail, &total, &free_bytes);
    DWORD error = ok ? 0 : GetLastError();
    // GetDiskFreeSpaceExW takes directories only; Linux answers for the
    // filesystem of any path, a file's included.
    wchar_t root[MAX_PATH];
    if (!ok && GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES &&
        GetVolumePathNameW(path.c_str(), root, MAX_PATH)) {
        ok = GetDiskFreeSpaceExW(root, &avail, &total, &free_bytes);
        if (!ok) error = GetLastError();
    }
    if (!ok) {
        set_errno_from_win32(error);
        return -1;
    }
    std::memset(out, 0, sizeof(*out));
    out->f_bsize = out->f_frsize = 4096;
    out->f_blocks = total.QuadPart / 4096;
    out->f_bfree = free_bytes.QuadPart / 4096;
    out->f_bavail = avail.QuadPart / 4096;
    out->f_files = out->f_ffree = out->f_favail = 1u << 20;  // NTFS has no inode limit to report
    out->f_namemax = 255;
    return 0;
}
WINAPK_GUEST_ABI int g_statvfs(const char* path, GuestStatvfs* out) { return statvfs_of(host_path(path), out); }
WINAPK_GUEST_ABI int g_fstatvfs(int fd, GuestStatvfs* out) {
    const HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    wchar_t path[MAX_PATH + 8];
    if (h == INVALID_HANDLE_VALUE || !GetFinalPathNameByHandleW(h, path, MAX_PATH, FILE_NAME_NORMALIZED)) {
        *guest_errno() = kEBADF;
        return -1;
    }
    // \\?\C:\dir\file -> its volume root.
    wchar_t root[MAX_PATH];
    if (!GetVolumePathNameW(path, root, MAX_PATH)) {
        set_errno_from_win32(GetLastError());
        return -1;
    }
    return statvfs_of(root, out);
}

// Bionic's struct statfs on x86_64, which is also its struct statfs64: the
// kernel's, where every field is a 64-bit __kernel_long_t except f_fsid (two
// ints). Windows has no statfs, so it is filled from statvfs_of's answer:
//
//   field       offset  size   from
//   f_type         0     8     ext4's magic (below)
//   f_bsize        8     8     f_bsize
//   f_blocks      16     8     f_blocks
//   f_bfree       24     8     f_bfree
//   f_bavail      32     8     f_bavail
//   f_files       40     8     f_files
//   f_ffree       48     8     f_ffree
//   f_fsid        56     8     0 (int[2])
//   f_namelen     64     8     f_namemax
//   f_frsize      72     8     f_frsize
//   f_flags       80     8     /data's mount flags (below)
//   f_spare       88    32     0 (long[4])
//   sizeof       120
//
// f_type and f_flags are what an Android /data reports, as upstream answers:
// EXT4_SUPER_MAGIC 0xef53, and ST_VALID | ST_NOSUID | ST_NODEV | ST_NOATIME.
struct GuestStatfs {
    uint64_t f_type, f_bsize, f_blocks, f_bfree, f_bavail, f_files, f_ffree;
    int32_t f_fsid[2];
    uint64_t f_namelen, f_frsize, f_flags, f_spare[4];
};
static_assert(sizeof(GuestStatfs) == 120 && offsetof(GuestStatfs, f_fsid) == 56 &&
                  offsetof(GuestStatfs, f_namelen) == 64 && offsetof(GuestStatfs, f_spare) == 88,
              "Bionic's struct statfs on x86_64");
int to_statfs(const GuestStatvfs& v, GuestStatfs* out) {
    std::memset(out, 0, sizeof(*out));
    out->f_type = 0xef53;
    out->f_bsize = v.f_bsize;
    out->f_blocks = v.f_blocks;
    out->f_bfree = v.f_bfree;
    out->f_bavail = v.f_bavail;
    out->f_files = v.f_files;
    out->f_ffree = v.f_ffree;
    out->f_namelen = v.f_namemax;
    out->f_frsize = v.f_frsize;
    out->f_flags = 0x0426;
    return 0;
}
WINAPK_GUEST_ABI int g_statfs(const char* path, GuestStatfs* out) {
    GuestStatvfs v;
    return statvfs_of(host_path(path), &v) < 0 ? -1 : to_statfs(v, out);
}
WINAPK_GUEST_ABI int g_fstatfs(int fd, GuestStatfs* out) {
    GuestStatvfs v;
    return g_fstatvfs(fd, &v) < 0 ? -1 : to_statfs(v, out);
}

// AT_EMPTY_PATH (0x1000) with "" is fstat of `dirfd` itself.
WINAPK_GUEST_ABI int g_fstatat(int dirfd, const char* path, GuestStat* g, int flags) {
    if ((flags & 0x1000) && path && !path[0] && dirfd != -100) return g_fstat(dirfd, g);
    std::string p;
    return at_path(dirfd, path, &p) ? g_stat(p.c_str(), g) : -1;
}
WINAPK_GUEST_ABI int g_access(const char* path, int mode) {
    // /proc/self/maps is no file on disk, yet it is there and readable, as open() and
    // fopen() have it. Mono asks before it starts ("Mono requires /proc to be mounted."
    // and exit(1) when access() says no), so every Xamarin and .NET app stopped here.
    if (is_proc_maps(path)) {
        if (mode & 2) {
            errno = EACCES;
            return fail_crt();
        }
        return 0;
    }
    // _waccess knows no X_OK (1); anything readable counts.
    return _waccess(host_path(path).c_str(), mode & 6) < 0 ? fail_crt() : 0;
}
WINAPK_GUEST_ABI int g_faccessat(int dirfd, const char* path, int mode, int) {
    std::string p;
    return at_path(dirfd, path, &p) ? g_access(p.c_str(), mode) : -1;
}

// ---- the file system -------------------------------------------------------------------
WINAPK_GUEST_ABI int g_mkdir(const char* path, unsigned) { return _wmkdir(host_path(path).c_str()) < 0 ? fail_crt() : 0; }
WINAPK_GUEST_ABI int g_mkdirat(int dirfd, const char* path, unsigned mode) {
    std::string p;
    return at_path(dirfd, path, &p) ? g_mkdir(p.c_str(), mode) : -1;
}
WINAPK_GUEST_ABI int g_rmdir(const char* path) { return _wrmdir(host_path(path).c_str()) < 0 ? fail_crt() : 0; }
WINAPK_GUEST_ABI int g_unlink(const char* path) {
    // POSIX unlinks an open file; Windows deletes one only when every open
    // handle shares delete access, and otherwise reports EBUSY
    // (ERROR_SHARING_VIOLATION) through set_errno_from_win32.
    const std::wstring w = host_path(path);
    if (DeleteFileW(w.c_str())) return 0;
    set_errno_from_win32(GetLastError());
    return -1;
}
WINAPK_GUEST_ABI int g_rename(const char* from, const char* to) {
    if (MoveFileExW(host_path(from).c_str(), host_path(to).c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED)) return 0;
    set_errno_from_win32(GetLastError());
    return -1;
}
WINAPK_GUEST_ABI int g_remove(const char* path) {
    const std::wstring w = host_path(path);
    const DWORD a = GetFileAttributesW(w.c_str());
    if (a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY)) return g_rmdir(path);
    return g_unlink(path);
}
WINAPK_GUEST_ABI int g_chdir(const char* path) { return _wchdir(host_path(path).c_str()) < 0 ? fail_crt() : 0; }
WINAPK_GUEST_ABI char* g_getcwd(char* buf, size_t n) {
    wchar_t w[MAX_PATH * 4];
    if (!_wgetcwd(w, MAX_PATH * 4)) {
        fail_crt();
        return nullptr;
    }
    // POSIX code takes a directory that does not start with '/' for a relative
    // one and resolves it against getcwd() again: JUCE's File did that until
    // the stack overflowed (Spotify's libcomScore). So the directory is rooted,
    // "/C:/dir", which host_path() reads back as "C:/dir".
    std::string s = narrow(w);
    for (char& c : s) {
        if (c == '\\') c = '/';
    }
    if (s[0] != '/') s.insert(0, 1, '/');
    if (!buf) return _strdup(s.c_str());
    if (s.size() + 1 > n) {
        *guest_errno() = kERANGE;
        return nullptr;
    }
    std::memcpy(buf, s.c_str(), s.size() + 1);
    return buf;
}
WINAPK_GUEST_ABI char* g_realpath(const char* path, char* out) {
    wchar_t full[MAX_PATH * 4];
    if (!GetFullPathNameW(host_path(path).c_str(), MAX_PATH * 4, full, nullptr)) {
        set_errno_from_win32(GetLastError());
        return nullptr;
    }
    if (GetFileAttributesW(full) == INVALID_FILE_ATTRIBUTES) {
        set_errno_from_win32(GetLastError());
        return nullptr;
    }
    const std::string s = narrow(full);
    if (!out) return _strdup(s.c_str());
    std::strncpy(out, s.c_str(), 4095);
    out[4095] = '\0';
    return out;
}
WINAPK_GUEST_ABI int g_chmod(const char*, unsigned) { return 0; }
WINAPK_GUEST_ABI int g_fchmod(int, unsigned) { return 0; }
WINAPK_GUEST_ABI unsigned g_umask(unsigned) { return 022; }
WINAPK_GUEST_ABI unsigned g_umask_chk(unsigned mode) { return g_umask(mode); }

// mkstemp/mkostemp: the template's trailing XXXXXX replaced until a new file
// can be created (O_EXCL), as bionic does; the path goes through g_open, so
// guest paths map as for any other open.
// mkstemps/mkostemps keep `suffix` characters after the XXXXXX ("/tmp/abcXXXXXX.txt", 4).
int make_temp_file(char* tmpl, int suffix, int flags) {
    const size_t len = tmpl ? std::strlen(tmpl) : 0;
    if (suffix < 0 || len < static_cast<size_t>(suffix) + 6) return *guest_errno() = kEINVAL, -1;
    const size_t n = len - static_cast<size_t>(suffix);  // where the XXXXXX ends
    if (std::strncmp(tmpl + n - 6, "XXXXXX", 6) != 0) return *guest_errno() = kEINVAL, -1;
    static const char kChars[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    uint64_t seed = GetTickCount64() ^ (static_cast<uint64_t>(GetCurrentThreadId()) << 32);
    for (int attempt = 0; attempt < 100; ++attempt) {
        for (size_t i = n - 6; i < n; ++i) {
            seed = seed * 6364136223846793005ull + 1442695040888963407ull;
            tmpl[i] = kChars[(seed >> 33) % (sizeof kChars - 1)];
        }
        const int fd = g_open(tmpl, kORdWr | kOCreat | kOExcl | (flags & ~(kOCreat | kOExcl | 03)), 0600);
        if (fd >= 0 || *guest_errno() != 17) return fd;  // EEXIST: try another name
    }
    return -1;
}
WINAPK_GUEST_ABI int g_mkostemp(char* tmpl, int flags) { return make_temp_file(tmpl, 0, flags); }
WINAPK_GUEST_ABI int g_mkstemp(char* tmpl) { return make_temp_file(tmpl, 0, 0); }
// Missing, these two answered 0 through the loader's fallback: descriptor 0 as the new temporary file.
WINAPK_GUEST_ABI int g_mkostemps(char* tmpl, int suffix, int flags) { return make_temp_file(tmpl, suffix, flags); }
WINAPK_GUEST_ABI int g_mkstemps(char* tmpl, int suffix) { return make_temp_file(tmpl, suffix, 0); }

// ---- directories -------------------------------------------------------------------------
// Bionic's struct dirent on x86_64.
struct GuestDirent {
    uint64_t d_ino;
    int64_t d_off;
    uint16_t d_reclen;
    uint8_t d_type;
    char d_name[256];
};

struct GuestDir {
    HANDLE find = INVALID_HANDLE_VALUE;
    WIN32_FIND_DATAW data;
    bool pending = false;  // `data` holds an entry not handed out yet
    int64_t offset = 0;
    GuestDirent entry;
    std::wstring path;  // the host directory
    int fd = -1;        // its descriptor (fdopendir, dirfd), closed with it
    // The guest joins the names onto an Android path (or uses them with the
    // *at() calls), which stores the characters Windows forbids in a name as
    // private-use ones (paths.cpp): the names are listed with them turned
    // back. Not when it named the directory by a host path: a name joined
    // onto that is opened as it is.
    bool android_names = false;
};

std::wstring dir_pattern(const std::wstring& path) {
    std::wstring pattern = path;
    if (!pattern.empty() && pattern.back() != L'/' && pattern.back() != L'\\') pattern += L'\\';
    return pattern + L'*';
}

GuestDir* open_dir(std::wstring path) {
    auto* d = new GuestDir();
    d->find = FindFirstFileW(dir_pattern(path).c_str(), &d->data);
    if (d->find == INVALID_HANDLE_VALUE) {
        set_errno_from_win32(GetLastError());
        delete d;
        return nullptr;
    }
    d->pending = true;
    d->path = std::move(path);
    return d;
}

WINAPK_GUEST_ABI GuestDir* g_opendir(const char* path) {
    GuestDir* d = open_dir(host_path(path));
    if (d) d->android_names = is_android_path(path);
    return d;
}
// fdopendir: the DIR owns `fd` from here on (closedir closes it).
WINAPK_GUEST_ABI GuestDir* g_fdopendir(int fd) {
    std::wstring path;
    if (!fd_host_path(fd, &path)) return nullptr;
    GuestDir* d = open_dir(std::move(path));
    if (d) {
        d->fd = fd;
        d->android_names = true;
    }
    return d;
}
// dirfd: a directory descriptor for the *at() calls, opened on first use.
WINAPK_GUEST_ABI int g_dirfd(GuestDir* d) {
    if (!d) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    if (d->fd < 0) d->fd = g_open(narrow(d->path.c_str()).c_str(), kODirectory, 0);
    return d->fd;
}
WINAPK_GUEST_ABI GuestDirent* g_readdir(GuestDir* d) {
    if (!d) return nullptr;
    if (!d->pending) {
        if (!FindNextFileW(d->find, &d->data)) return nullptr;  // end: errno untouched
    }
    d->pending = false;
    std::memset(&d->entry, 0, sizeof(d->entry));
    if (d->android_names) android_name(d->data.cFileName);
    const std::string name = narrow(d->data.cFileName);
    std::strncpy(d->entry.d_name, name.c_str(), sizeof(d->entry.d_name) - 1);
    d->entry.d_ino = static_cast<uint64_t>(++d->offset);
    d->entry.d_off = d->offset;
    d->entry.d_reclen = sizeof(GuestDirent);
    d->entry.d_type = (d->data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) ? 4 : 8;  // DT_DIR, DT_REG
    return &d->entry;
}
WINAPK_GUEST_ABI int g_closedir(GuestDir* d) {
    if (!d) return -1;
    if (d->find != INVALID_HANDLE_VALUE) FindClose(d->find);
    if (d->fd >= 0) g_close(d->fd);
    delete d;
    return 0;
}
// rewinddir: the listing starts over, and shows what the directory holds now. (It did nothing, so a
// second pass over a directory -- count the entries, then read them -- found it empty.)
WINAPK_GUEST_ABI void g_rewinddir(GuestDir* d) {
    if (!d) return;
    WIN32_FIND_DATAW first;
    const HANDLE again = FindFirstFileW(dir_pattern(d->path).c_str(), &first);
    if (again == INVALID_HANDLE_VALUE) return;  // the directory is gone: the old listing just ends
    if (d->find != INVALID_HANDLE_VALUE) FindClose(d->find);
    d->find = again;
    d->data = first;
    d->pending = true;
    d->offset = 0;
}
// readdir_r: readdir into the caller's entry. *result is null at the end, with 0 returned. Missing, it
// answered 0 through the loader's fallback and left *result as it was: a loop on it never ended.
WINAPK_GUEST_ABI int g_readdir_r(GuestDir* d, GuestDirent* entry, GuestDirent** result) {
    const int before = *guest_errno();
    *guest_errno() = 0;
    const GuestDirent* e = g_readdir(d);
    const int error = e ? 0 : *guest_errno();
    *guest_errno() = before;
    if (e) *entry = *e;
    *result = e ? entry : nullptr;
    return error;
}

// scandir: the entries `filter` keeps, each malloc'ed, in `compare`'s order, in a malloc'ed array.
using DirFilter = int(WINAPK_GUEST_ABI*)(const GuestDirent*);
using DirCompare = int(WINAPK_GUEST_ABI*)(const GuestDirent**, const GuestDirent**);
WINAPK_GUEST_ABI int g_alphasort(const GuestDirent** a, const GuestDirent** b) {
    return std::strcmp((*a)->d_name, (*b)->d_name);  // strcoll in the C locale
}
int scan_dir(GuestDir* d, GuestDirent*** out, DirFilter filter, DirCompare compare) {
    if (!d) return -1;
    std::vector<GuestDirent*> found;
    bool ok = true;
    while (const GuestDirent* e = g_readdir(d)) {
        if (filter && !filter(e)) continue;
        auto* copy = static_cast<GuestDirent*>(std::malloc(sizeof(GuestDirent)));
        if (!copy) {
            ok = false;
            break;
        }
        *copy = *e;
        found.push_back(copy);
    }
    g_closedir(d);
    auto** list = ok ? static_cast<GuestDirent**>(std::malloc((found.size() + 1) * sizeof(GuestDirent*))) : nullptr;
    if (!list) {
        for (GuestDirent* e : found) std::free(e);
        return *guest_errno() = kENOMEM, -1;
    }
    if (compare) {
        // stable_sort merges: a comparator that is not a strict order cannot send it out of the range.
        std::stable_sort(found.begin(), found.end(), [compare](const GuestDirent* a, const GuestDirent* b) {
            return compare(&a, &b) < 0;
        });
    }
    std::copy(found.begin(), found.end(), list);
    *out = list;
    return static_cast<int>(found.size());
}
WINAPK_GUEST_ABI int g_scandir(const char* path, GuestDirent*** out, DirFilter filter, DirCompare compare) {
    return scan_dir(g_opendir(path), out, filter, compare);
}
WINAPK_GUEST_ABI int g_scandirat(int dirfd, const char* path, GuestDirent*** out, DirFilter filter,
                                 DirCompare compare) {
    std::string p;
    if (!at_path(dirfd, path, &p)) return -1;
    GuestDir* d = g_opendir(p.c_str());
    // Under a directory descriptor at_path made a host path of `path`; the names are Android's all the same.
    if (d && p != path) d->android_names = true;
    return scan_dir(d, out, filter, compare);
}

// ---- pathconf ------------------------------------------------------------------
// pathconf's answers for a local file system (<bits/posix_limits.h> values);
// a name this does not know is "no limit", -1 with errno untouched, as the
// manual allows.
WINAPK_GUEST_ABI int64_t g_pathconf(const char*, int name) {
    switch (name) {
        case 0: return 127;   // _PC_LINK_MAX
        case 1: return 255;   // _PC_MAX_CANON
        case 2: return 255;   // _PC_MAX_INPUT
        case 3: return 255;   // _PC_NAME_MAX
        case 4: return 4096;  // _PC_PATH_MAX
        case 5: return 4096;  // _PC_PIPE_BUF
        case 6: return 1;     // _PC_CHOWN_RESTRICTED
        case 7: return 1;     // _PC_NO_TRUNC
        case 8: return 0;     // _PC_VDISABLE
        default: return -1;
    }
}
WINAPK_GUEST_ABI int64_t g_fpathconf(int, int name) { return g_pathconf(nullptr, name); }

// ---- symbolic links -----------------------------------------------------------
//
// /proc/self/exe is the one link every Android process has, and Dart's VM
// (Flutter) resolves it at start: lstat64, S_ISLNK, readlink. With lstat
// failing (no such file) the engine's error path copied a -1 byte count off
// its stack and died (LocalSend). So lstat shows it as a link, readlink
// answers the runtime's own executable, /proc/self/fd/<n> the descriptor's
// file, and a path that is a Windows symbolic link or junction its target;
// anything else is not a link (EINVAL), as on Linux.
bool is_proc_self_exe(const char* path) {
    if (!path || std::strncmp(path, "/proc/", 6) != 0) return false;
    const char* p = path + 6;
    if (std::strncmp(p, "self", 4) == 0) p += 4;
    else while (*p >= '0' && *p <= '9') ++p;
    return std::strcmp(p, "/exe") == 0;
}
int proc_self_fd(const char* path) {  // /proc/self/fd/<n> -> n, else -1
    if (!path || std::strncmp(path, "/proc/", 6) != 0) return -1;
    const char* p = path + 6;
    if (std::strncmp(p, "self", 4) == 0) p += 4;
    else while (*p >= '0' && *p <= '9') ++p;
    if (std::strncmp(p, "/fd/", 4) != 0) return -1;
    p += 4;
    if (!*p) return -1;
    int fd = 0;
    for (; *p; ++p) {
        if (*p < '0' || *p > '9') return -1;
        fd = fd * 10 + (*p - '0');
    }
    return fd;
}
std::wstring final_path_of_handle(HANDLE h) {
    wchar_t buf[2048];
    const DWORD n = GetFinalPathNameByHandleW(h, buf, 2048, FILE_NAME_NORMALIZED);
    if (n == 0 || n >= 2048) return {};
    std::wstring p(buf, n);
    if (p.rfind(L"\\\\?\\", 0) == 0) p.erase(0, 4);
    return p;
}
// The link target as a host path, or empty with errno set.
std::wstring link_target(const char* path) {
    if (is_proc_self_exe(path)) {
        wchar_t exe[MAX_PATH];
        const DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH);
        return n ? std::wstring(exe, n) : std::wstring();
    }
    if (const int fd = proc_self_fd(path); fd >= 0) {
        HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
        if (h == INVALID_HANDLE_VALUE) {
            *guest_errno() = 9;  // EBADF
            return {};
        }
        return final_path_of_handle(h);
    }
    const std::wstring w = host_path(path);
    const DWORD a = GetFileAttributesW(w.c_str());
    if (a == INVALID_FILE_ATTRIBUTES) {
        set_errno_from_win32(GetLastError());
        return {};
    }
    if (!(a & FILE_ATTRIBUTE_REPARSE_POINT)) {
        *guest_errno() = kEINVAL;  // not a link
        return {};
    }
    HANDLE h = CreateFileW(w.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        set_errno_from_win32(GetLastError());
        return {};
    }
    std::wstring target = final_path_of_handle(h);
    CloseHandle(h);
    if (target.empty()) *guest_errno() = kEINVAL;
    return target;
}
WINAPK_GUEST_ABI int64_t g_readlink(const char* path, char* buf, size_t size) {
    if (!path || !buf) {
        *guest_errno() = 14;  // EFAULT
        return -1;
    }
    const std::wstring target = link_target(path);
    if (target.empty()) return -1;
    const std::string s = narrow(target.c_str());
    const size_t n = std::min(size, s.size());
    std::memcpy(buf, s.data(), n);  // no terminator, as readlink(2) has none
    return static_cast<int64_t>(n);
}
WINAPK_GUEST_ABI int64_t g_readlink_chk(const char* path, char* buf, size_t size, size_t buf_size) {
    return g_readlink(path, buf, std::min(size, buf_size));
}
WINAPK_GUEST_ABI int64_t g_readlinkat(int dirfd, const char* path, char* buf, size_t size) {
    std::string p;
    return at_path(dirfd, path, &p) ? g_readlink(p.c_str(), buf, size) : -1;
}
// lstat: stat, except that a link is reported as one. The proc links have no
// file of their own, so they get a link's mode and the target's length.
WINAPK_GUEST_ABI int g_lstat(const char* path, GuestStat* g) {
    if (is_proc_self_exe(path) || proc_self_fd(path) >= 0) {
        const std::wstring target = link_target(path);
        if (target.empty()) return -1;
        std::memset(g, 0, sizeof(*g));
        g->st_mode = 0120777;  // S_IFLNK | 0777
        g->st_nlink = 1;
        g->st_size = static_cast<int64_t>(narrow(target.c_str()).size());
        g->st_blksize = 4096;
        return 0;
    }
    const int rc = g_stat(path, g);
    if (rc == 0) {
        const DWORD a = GetFileAttributesW(host_path(path).c_str());
        if (a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_REPARSE_POINT)) g->st_mode = (g->st_mode & 0777) | 0120000;
    }
    return rc;
}
// unlinkat: the same joins as openat (at_path).
WINAPK_GUEST_ABI int g_unlinkat(int dirfd, const char* path, int flags) {
    std::string p;
    if (!at_path(dirfd, path, &p)) return -1;
    return (flags & 0x200) ? g_rmdir(p.c_str()) : g_unlink(p.c_str());  // AT_REMOVEDIR
}

// link: a hard link, which NTFS has (CreateHardLinkW). Linux refuses one to a
// directory with EPERM, and so does a file system without hard links (FAT,
// exFAT: ERROR_INVALID_FUNCTION here).
WINAPK_GUEST_ABI int g_link(const char* from, const char* to) {
    const std::wstring src = host_path(from), dst = host_path(to);
    const DWORD a = GetFileAttributesW(src.c_str());
    if (a == INVALID_FILE_ATTRIBUTES) {
        set_errno_from_win32(GetLastError());
        return -1;
    }
    if (a & FILE_ATTRIBUTE_DIRECTORY) return *guest_errno() = kEPERM, -1;
    if (CreateHardLinkW(dst.c_str(), src.c_str(), nullptr)) return 0;
    const DWORD err = GetLastError();
    if (err == ERROR_INVALID_FUNCTION) return *guest_errno() = kEPERM, -1;
    set_errno_from_win32(err);
    return -1;
}

// ---- fts ------------------------------------------------------------------------------
//
// Bionic's fts (libc/bionic/fts.c, OpenBSD's), ported with its structures
// laid out as Bionic's <fts.h> has them, over this file's stat, lstat and
// directory calls, so the walk sees the same mapped paths as open and
// opendir. One difference: the walk never changes the current directory, as
// if FTS_NOCHDIR were always given (it is set in fts_options). fchdir'ing a
// process the JVM's threads share is not safe, and with NOCHDIR fts_accpath
// is the whole path, which is right for every caller.
struct GuestFtsent {
    GuestFtsent* fts_cycle;
    GuestFtsent* fts_parent;
    GuestFtsent* fts_link;
    int64_t fts_number;
    void* fts_pointer;
    char* fts_accpath;
    char* fts_path;
    int32_t fts_errno;
    int32_t fts_symfd;
    uint64_t fts_pathlen;
    uint64_t fts_namelen;
    uint64_t fts_ino;
    uint64_t fts_dev;
    uint32_t fts_nlink;
    int16_t fts_level;
    uint16_t fts_info;
    uint16_t fts_flags;
    uint16_t fts_instr;
    void* fts_statp;
    char fts_name[1];
};
static_assert(offsetof(GuestFtsent, fts_ino) == 80 && offsetof(GuestFtsent, fts_level) == 100 &&
                  offsetof(GuestFtsent, fts_statp) == 112 && offsetof(GuestFtsent, fts_name) == 120,
              "Bionic's FTSENT on LP64");
using GuestFtsCompar = int(WINAPK_GUEST_ABI*)(const GuestFtsent**, const GuestFtsent**);
struct GuestFts {
    GuestFtsent* fts_cur;
    GuestFtsent* fts_child;
    GuestFtsent** fts_array;
    uint64_t fts_dev;
    char* fts_path;
    int32_t fts_rfd;
    uint64_t fts_pathlen;
    int32_t fts_nitems;
    GuestFtsCompar fts_compar;
    int32_t fts_options;
};
static_assert(sizeof(GuestFts) == 80, "Bionic's FTS on LP64");
// The FTS handed out, and what the shim keeps beside it: how the caller lays
// out struct stat (an arm64 caller's differs; mini_bionic.h).
struct HostFts {
    GuestFts fts;
    FtsStatFill fill = nullptr;
};

constexpr int kFtsComfollow = 0x1, kFtsLogical = 0x2, kFtsNochdir = 0x4, kFtsNostat = 0x8, kFtsPhysical = 0x10,
              kFtsSeedot = 0x20, kFtsXdev = 0x40, kFtsOptionMask = 0xff, kFtsNameonly = 0x1000, kFtsStop = 0x2000;
constexpr int16_t kFtsRootParentLevel = -1, kFtsRootLevel = 0, kFtsMaxLevel = 0x7fff;
constexpr uint16_t kFtsD = 1, kFtsDc = 2, kFtsDefault = 3, kFtsDnr = 4, kFtsDot = 5, kFtsDp = 6, kFtsErr = 7,
                   kFtsF = 8, kFtsInit = 9, kFtsNs = 10, kFtsNsok = 11, kFtsSl = 12, kFtsSlnone = 13;
constexpr uint16_t kFtsAgain = 1, kFtsFollow = 2, kFtsNoinstr = 3, kFtsSkip = 4;
constexpr int kBChild = 1, kBNames = 2, kBRead = 3;

bool fts_isset(const GuestFts* sp, int opt) { return (sp->fts_options & opt) != 0; }
bool is_dot(const char* n) { return n[0] == '.' && (!n[1] || (n[1] == '.' && !n[2])); }
size_t fts_nappend(const GuestFtsent* p) {
    return p->fts_path[p->fts_pathlen - 1] == '/' ? p->fts_pathlen - 1 : p->fts_pathlen;
}

GuestFtsent* fts_alloc(HostFts* h, const char* name, size_t namelen) {
    GuestFts* sp = &h->fts;
    size_t len = offsetof(GuestFtsent, fts_name) + namelen + 2;
    const size_t stat_at = (len + 15) & ~size_t{15};
    if (!fts_isset(sp, kFtsNostat)) len = stat_at + sizeof(GuestStat);
    auto* p = static_cast<GuestFtsent*>(std::calloc(1, len));
    if (!p) return nullptr;
    p->fts_path = sp->fts_path;
    p->fts_namelen = namelen;
    p->fts_instr = kFtsNoinstr;
    if (!fts_isset(sp, kFtsNostat)) p->fts_statp = reinterpret_cast<char*>(p) + stat_at;
    std::memcpy(p->fts_name, name, namelen);
    return p;
}
void fts_lfree(GuestFtsent* head) {
    while (GuestFtsent* p = head) {
        head = head->fts_link;
        std::free(p);
    }
}
bool fts_palloc(GuestFts* sp, size_t more) {
    more += 256;
    if (sp->fts_pathlen + more < sp->fts_pathlen) {
        std::free(sp->fts_path);
        sp->fts_path = nullptr;
        *guest_errno() = kENAMETOOLONG;
        return false;
    }
    auto* p = static_cast<char*>(std::realloc(sp->fts_path, sp->fts_pathlen + more));
    if (!p) {
        std::free(sp->fts_path);
        sp->fts_path = nullptr;
        *guest_errno() = kENOMEM;
        return false;
    }
    std::memset(p + sp->fts_pathlen, 0, more);
    sp->fts_pathlen += more;
    sp->fts_path = p;
    return true;
}
// The path buffer moved: every entry handed out points into it.
void fts_padjust(GuestFts* sp, GuestFtsent* head) {
    char* addr = sp->fts_path;
    auto adjust = [addr](GuestFtsent* p) {
        if (p->fts_accpath != p->fts_name) p->fts_accpath = addr + (p->fts_accpath - p->fts_path);
        p->fts_path = addr;
    };
    for (GuestFtsent* p = sp->fts_child; p; p = p->fts_link) adjust(p);
    for (GuestFtsent* p = head; p->fts_level >= kFtsRootLevel;) {
        adjust(p);
        p = p->fts_link ? p->fts_link : p->fts_parent;
    }
}

uint16_t fts_stat(HostFts* h, GuestFtsent* p, bool follow) {
    GuestFts* sp = &h->fts;
    GuestStat sb{};
    const char* path = p->fts_accpath;
    auto store = [&](const GuestStat* s) {
        if (!p->fts_statp) return;
        if (h->fill) h->fill(s, p->fts_statp);
        else std::memcpy(p->fts_statp, s, sizeof(GuestStat));
    };
    if (fts_isset(sp, kFtsLogical) || follow) {
        if (g_stat(path, &sb)) {
            const int saved = *guest_errno();
            if (!g_lstat(path, &sb)) {
                store(&sb);
                *guest_errno() = 0;
                return kFtsSlnone;
            }
            p->fts_errno = saved;
            sb = GuestStat{};
            store(&sb);
            return kFtsNs;
        }
    } else if (g_lstat(path, &sb)) {
        p->fts_errno = *guest_errno();
        sb = GuestStat{};
        store(&sb);
        return kFtsNs;
    }
    store(&sb);
    const uint32_t type = sb.st_mode & 0170000;
    if (type == 0040000) {
        p->fts_dev = sb.st_dev;
        p->fts_ino = sb.st_ino;
        p->fts_nlink = static_cast<uint32_t>(sb.st_nlink);
        if (is_dot(p->fts_name)) return kFtsDot;
        for (GuestFtsent* t = p->fts_parent; t->fts_level >= kFtsRootLevel; t = t->fts_parent) {
            if (p->fts_ino == t->fts_ino && p->fts_dev == t->fts_dev) {
                p->fts_cycle = t;
                return kFtsDc;
            }
        }
        return kFtsD;
    }
    if (type == 0120000) return kFtsSl;
    if (type == 0100000) return kFtsF;
    return kFtsDefault;
}

GuestFtsent* fts_sort(GuestFts* sp, GuestFtsent* head, int nitems) {
    if (nitems > sp->fts_nitems) {
        auto* a = static_cast<GuestFtsent**>(std::realloc(sp->fts_array, (nitems + 40) * sizeof(GuestFtsent*)));
        if (!a) {
            std::free(sp->fts_array);
            sp->fts_array = nullptr;
            sp->fts_nitems = 0;
            return head;
        }
        sp->fts_nitems = nitems + 40;
        sp->fts_array = a;
    }
    GuestFtsent** ap = sp->fts_array;
    for (GuestFtsent* p = head; p; p = p->fts_link) *ap++ = p;
    const GuestFtsCompar compar = sp->fts_compar;
    std::stable_sort(sp->fts_array, sp->fts_array + nitems, [compar](GuestFtsent* a, GuestFtsent* b) {
        return compar(const_cast<const GuestFtsent**>(&a), const_cast<const GuestFtsent**>(&b)) < 0;
    });
    ap = sp->fts_array;
    head = *ap;
    for (; --nitems; ++ap) ap[0]->fts_link = ap[1];
    ap[0]->fts_link = nullptr;
    return head;
}

void fts_load(GuestFts* sp, GuestFtsent* p) {
    size_t len = p->fts_pathlen = p->fts_namelen;
    std::memmove(sp->fts_path, p->fts_name, len + 1);
    char* cp = std::strrchr(p->fts_name, '/');
    if (cp && (cp != p->fts_name || cp[1])) {
        len = std::strlen(++cp);
        std::memmove(p->fts_name, cp, len + 1);
        p->fts_namelen = len;
    }
    p->fts_accpath = p->fts_path = sp->fts_path;
    sp->fts_dev = p->fts_dev;
}

// The entries of the directory fts_cur is on, as fts_build reads them with
// NOCHDIR: each one's path is built in the shared buffer before its stat.
GuestFtsent* fts_build(HostFts* h, int type) {
    GuestFts* sp = &h->fts;
    GuestFtsent* cur = sp->fts_cur;
    GuestDir* dirp = g_opendir(cur->fts_accpath);
    if (!dirp) {
        if (type == kBRead) {
            cur->fts_info = kFtsDnr;
            cur->fts_errno = *guest_errno();
        }
        return nullptr;
    }
    int nlinks;
    bool nostat = false;
    if (type == kBNames) {
        nlinks = 0;
    } else if (fts_isset(sp, kFtsNostat) && fts_isset(sp, kFtsPhysical)) {
        nlinks = static_cast<int>(cur->fts_nlink) - (fts_isset(sp, kFtsSeedot) ? 0 : 2);
        nostat = true;
    } else {
        nlinks = -1;
    }
    size_t len = fts_nappend(cur);
    char* cp = sp->fts_path + len;
    *cp++ = '/';
    ++len;
    size_t maxlen = sp->fts_pathlen - len;
    int16_t level = cur->fts_level;
    if (level < kFtsMaxLevel) ++level;

    bool doadjust = false;
    int nitems = 0;
    GuestFtsent *head = nullptr, *tail = nullptr;
    while (GuestDirent* dp = g_readdir(dirp)) {
        if (!fts_isset(sp, kFtsSeedot) && is_dot(dp->d_name)) continue;
        const size_t namelen = std::strlen(dp->d_name);
        GuestFtsent* p = fts_alloc(h, dp->d_name, namelen);
        if (p && namelen >= maxlen) {
            char* oldaddr = sp->fts_path;
            if (!fts_palloc(sp, namelen + len + 1)) {
                std::free(p);
                p = nullptr;
            } else {
                if (oldaddr != sp->fts_path) {
                    doadjust = true;
                    cp = sp->fts_path + len;
                }
                maxlen = sp->fts_pathlen - len;
            }
        }
        if (!p) {
            const int saved = *guest_errno();
            fts_lfree(head);
            g_closedir(dirp);
            cur->fts_info = kFtsErr;
            sp->fts_options |= kFtsStop;
            *guest_errno() = saved;
            return nullptr;
        }
        p->fts_level = level;
        p->fts_parent = sp->fts_cur;
        p->fts_pathlen = len + namelen;
        if (nlinks == 0 || (nostat && dp->d_type != 4 && dp->d_type != 0)) {  // not DT_DIR, DT_UNKNOWN
            p->fts_accpath = p->fts_path;
            p->fts_info = kFtsNsok;
        } else {
            p->fts_accpath = p->fts_path;
            std::memmove(cp, p->fts_name, p->fts_namelen + 1);
            p->fts_info = fts_stat(h, p, false);
            if (nlinks > 0 && (p->fts_info == kFtsD || p->fts_info == kFtsDc || p->fts_info == kFtsDot)) --nlinks;
        }
        p->fts_link = nullptr;
        if (!head) head = tail = p;
        else tail = tail->fts_link = p;
        ++nitems;
    }
    g_closedir(dirp);
    if (doadjust) fts_padjust(sp, head);
    if (len == sp->fts_pathlen || nitems == 0) --cp;
    *cp = '\0';
    if (!nitems) {
        if (type == kBRead) cur->fts_info = kFtsDp;
        return nullptr;
    }
    if (sp->fts_compar && nitems > 1) head = fts_sort(sp, head, nitems);
    return head;
}

GuestFts* fts_open_impl(char* const* argv, int options, GuestFtsCompar compar, FtsStatFill fill) {
    if (options & ~kFtsOptionMask) return *guest_errno() = kEINVAL, nullptr;
    if (!*argv) return *guest_errno() = kEINVAL, nullptr;
    auto* h = new (std::nothrow) HostFts();
    if (!h) return *guest_errno() = kENOMEM, nullptr;
    h->fill = fill;
    GuestFts* sp = &h->fts;
    sp->fts_compar = compar;
    sp->fts_options = options | kFtsNochdir;
    sp->fts_rfd = -1;
    size_t maxarg = 0;
    for (char* const* a = argv; *a; ++a) maxarg = std::max(maxarg, std::strlen(*a));
    if (!fts_palloc(sp, std::max<size_t>(maxarg, 4096))) {
        delete h;
        return nullptr;
    }
    GuestFtsent* parent = fts_alloc(h, "", 0);
    GuestFtsent *root = nullptr, *prev = nullptr;
    int nitems = 0;
    bool ok = parent != nullptr;
    if (ok) parent->fts_level = kFtsRootParentLevel;
    for (; ok && *argv; ++argv, ++nitems) {
        GuestFtsent* p = fts_alloc(h, *argv, std::strlen(*argv));
        if (!p) {
            ok = false;
            break;
        }
        p->fts_level = kFtsRootLevel;
        p->fts_parent = parent;
        p->fts_accpath = p->fts_name;
        p->fts_info = fts_stat(h, p, fts_isset(sp, kFtsComfollow));
        if (p->fts_info == kFtsDot) p->fts_info = kFtsD;  // a "." or ".." given is a real directory
        if (compar) {
            p->fts_link = root;
            root = p;
        } else {
            p->fts_link = nullptr;
            if (!root) root = p;
            else prev->fts_link = p;
            prev = p;
        }
    }
    if (ok && compar && nitems > 1) root = fts_sort(sp, root, nitems);
    if (ok) ok = (sp->fts_cur = fts_alloc(h, "", 0)) != nullptr;
    if (!ok) {
        fts_lfree(root);
        std::free(parent);
        std::free(sp->fts_path);
        std::free(sp->fts_array);
        delete h;
        return *guest_errno() = kENOMEM, nullptr;
    }
    sp->fts_cur->fts_link = root;
    sp->fts_cur->fts_info = kFtsInit;
    return sp;
}

WINAPK_GUEST_ABI GuestFts* g_fts_open(char* const* argv, int options, GuestFtsCompar compar) {
    return fts_open_impl(argv, options, compar, nullptr);
}

WINAPK_GUEST_ABI GuestFtsent* g_fts_read(GuestFts* sp) {
    auto* h = reinterpret_cast<HostFts*>(sp);
    if (!sp->fts_cur || fts_isset(sp, kFtsStop)) return nullptr;
    GuestFtsent* p = sp->fts_cur;
    const uint16_t instr = p->fts_instr;
    p->fts_instr = kFtsNoinstr;
    if (instr == kFtsAgain) {  // any node may be visited again: stat it afresh
        p->fts_info = fts_stat(h, p, false);
        return p;
    }
    if (instr == kFtsFollow && (p->fts_info == kFtsSl || p->fts_info == kFtsSlnone)) {
        p->fts_info = fts_stat(h, p, true);
        return p;
    }
    GuestFtsent* tmp;
    if (p->fts_info == kFtsD) {
        // Skipped, or across a mount point: the post-order visit now.
        if (instr == kFtsSkip || (fts_isset(sp, kFtsXdev) && p->fts_dev != sp->fts_dev)) {
            if (sp->fts_child) {
                fts_lfree(sp->fts_child);
                sp->fts_child = nullptr;
            }
            p->fts_info = kFtsDp;
            return p;
        }
        // fts_children read only the names: read them properly now.
        if (sp->fts_child && fts_isset(sp, kFtsNameonly)) {
            sp->fts_options &= ~kFtsNameonly;
            fts_lfree(sp->fts_child);
            sp->fts_child = nullptr;
        }
        if (!sp->fts_child && !(sp->fts_child = fts_build(h, kBRead))) {
            if (fts_isset(sp, kFtsStop)) return nullptr;
            return p;
        }
        p = sp->fts_child;
        sp->fts_child = nullptr;
        goto name;
    }
next:
    tmp = p;
    if ((p = p->fts_link)) {
        std::free(tmp);
        if (p->fts_level == kFtsRootLevel) {  // the next root
            fts_load(sp, p);
            return sp->fts_cur = p;
        }
        // fts_set on the node: skipped, or a link to follow.
        if (p->fts_instr == kFtsSkip) goto next;
        if (p->fts_instr == kFtsFollow) {
            p->fts_info = fts_stat(h, p, true);
            p->fts_instr = kFtsNoinstr;
        }
    name: {
        char* t = sp->fts_path + fts_nappend(p->fts_parent);
        *t++ = '/';
        std::memmove(t, p->fts_name, p->fts_namelen + 1);
        return sp->fts_cur = p;
    }
    }
    // Up to the parent.
    p = tmp->fts_parent;
    std::free(tmp);
    if (p->fts_level == kFtsRootParentLevel) {
        // Done: errno 0 tells the end from an error.
        std::free(p);
        *guest_errno() = 0;
        return sp->fts_cur = nullptr;
    }
    sp->fts_path[p->fts_pathlen] = '\0';
    p->fts_info = p->fts_errno ? kFtsErr : kFtsDp;
    return sp->fts_cur = p;
}

WINAPK_GUEST_ABI int g_fts_set(GuestFts*, GuestFtsent* p, int instr) {
    if (instr && instr != kFtsAgain && instr != kFtsFollow && instr != kFtsNoinstr && instr != kFtsSkip) {
        *guest_errno() = kEINVAL;
        return 1;
    }
    p->fts_instr = static_cast<uint16_t>(instr);
    return 0;
}

WINAPK_GUEST_ABI GuestFtsent* g_fts_children(GuestFts* sp, int instr) {
    if (instr && instr != kFtsNameonly) return *guest_errno() = kEINVAL, nullptr;
    GuestFtsent* p = sp->fts_cur;
    *guest_errno() = 0;  // an empty directory from an error
    if (fts_isset(sp, kFtsStop)) return nullptr;
    if (p->fts_info == kFtsInit) return p->fts_link;  // the roots
    if (p->fts_info != kFtsD) return nullptr;
    if (sp->fts_child) fts_lfree(sp->fts_child);
    int type = kBChild;
    if (instr == kFtsNameonly) {
        sp->fts_options |= kFtsNameonly;
        type = kBNames;
    }
    return sp->fts_child = fts_build(reinterpret_cast<HostFts*>(sp), type);
}

WINAPK_GUEST_ABI int g_fts_close(GuestFts* sp) {
    // The dummy entry links to the roots, so this works before any read too.
    if (sp->fts_cur) {
        GuestFtsent* p = sp->fts_cur;
        while (p->fts_level >= kFtsRootLevel) {
            GuestFtsent* freep = p;
            p = p->fts_link ? p->fts_link : p->fts_parent;
            std::free(freep);
        }
        std::free(p);
    }
    if (sp->fts_child) fts_lfree(sp->fts_child);
    std::free(sp->fts_array);
    std::free(sp->fts_path);
    delete reinterpret_cast<HostFts*>(sp);
    return 0;
}

// ---- FILE streams ----------------------------------------------------------------------------
// Bionic's mode letters, less "e" (O_CLOEXEC); the CRT stream is always binary,
// as Linux's are.
std::wstring crt_mode(const char* mode) {
    std::wstring m;
    for (const char* p = mode; p && *p; ++p) {
        if (*p == 'e' || *p == 'b') continue;
        m.push_back(static_cast<wchar_t>(*p));
    }
    m.push_back(L'b');
    return m;
}

// fopen("/dev/urandom"): a guest FILE* is the CRT's, which cannot wrap an
// FdObject, so hand out a delete-on-close temporary file pre-filled from the
// system RNG. Entropy readers (mbedtls' platform poll, in WhatsApp's msys
// networking) open, read a few dozen bytes and close; without this their DRBG
// seed failed (-52) and the process aborted.
FILE* open_random_stream() {
    wchar_t dir[MAX_PATH + 1], name[MAX_PATH + 1];
    if (!GetTempPathW(MAX_PATH, dir) || !GetTempFileNameW(dir, L"rnd", 0, name)) {
        *guest_errno() = 5;  // EIO
        return nullptr;
    }
    FILE* f = _wfopen(name, L"w+bD");  // D: deleted when closed
    if (!f) {
        set_errno_from_crt(errno);
        DeleteFileW(name);
        return nullptr;
    }
    static constexpr size_t kBytes = 64 * 1024;
    std::unique_ptr<unsigned char[]> buf(new unsigned char[kBytes]);
    BCryptGenRandom(nullptr, buf.get(), static_cast<ULONG>(kBytes), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    std::fwrite(buf.get(), 1, kBytes, f);
    std::rewind(f);
    return f;
}
WINAPK_GUEST_ABI FILE* g_fopen(const char* path, const char* mode) {
    if (is_random_device(path)) return open_random_stream();
    if (is_proc_maps(path)) {
        const int fd = open_temp_with(proc_maps_text());
        if (fd < 0) return nullptr;
        FILE* f = _fdopen(fd, "rb");
        if (!f) {
            set_errno_from_crt(errno);
            _close(fd);
        }
        return f;
    }
    FILE* f = _wfopen(host_path(path).c_str(), crt_mode(mode).c_str());
    if (!f) set_errno_from_crt(errno);
    return f;
}
WINAPK_GUEST_ABI FILE* g_fdopen(int fd, const char* mode) {
    std::string m;
    for (const wchar_t c : crt_mode(mode)) m.push_back(static_cast<char>(c));
    FILE* f = _fdopen(fd, m.c_str());
    if (!f) set_errno_from_crt(errno);
    return f;
}
WINAPK_GUEST_ABI FILE* g_freopen(const char* path, const char* mode, void* f) {
    // stdout and stderr are the runtime's log as well; the guest keeps writing
    // to it rather than taking it away (fd_is_host_std).
    FILE* file = host_file(f);
    if (file == stdout || file == stderr) return file;
    FILE* r = _wfreopen(host_path(path).c_str(), crt_mode(mode).c_str(), file);
    if (!r) set_errno_from_crt(errno);
    return r;
}
// ---- funopen ------------------------------------------------------------------
//
// BSD's FILE over callbacks, which bionic has and SDL's and some games' asset
// readers use (a FILE over an AAsset). The CRT's FILE cannot run callbacks,
// so a read-only stream is snapshotted through readfn into a temporary file,
// which seeks and reads as the stream would; its closefn runs at fclose. A
// writefn is accepted but never called: raylib's android_fopen passes one that
// only returns EACCES, and refusing it was a null FILE for every asset (Alex
// vs Bus: "Failed to load graphics"). Writes land in the snapshot, unseen by
// the stream, which no asset reader does.
using FunopenRead = int(WINAPK_GUEST_ABI*)(void*, char*, int);
using FunopenWrite = int(WINAPK_GUEST_ABI*)(void*, const char*, int);
using FunopenClose = int(WINAPK_GUEST_ABI*)(void*);
struct FunopenFile {
    void* cookie;
    FunopenClose closefn;
};
std::mutex g_funopen_lock;
std::map<FILE*, FunopenFile> g_funopen_files;

WINAPK_GUEST_ABI FILE* g_funopen(void* cookie, FunopenRead readfn, FunopenWrite /*writefn*/, void* /*seekfn*/,
                                 FunopenClose closefn) {
    if (!readfn) {
        *guest_errno() = 22;  // EINVAL
        return nullptr;
    }
    wchar_t dir[MAX_PATH + 1], name[MAX_PATH + 1];
    if (!GetTempPathW(MAX_PATH, dir) || !GetTempFileNameW(dir, L"fun", 0, name)) {
        *guest_errno() = 5;  // EIO
        return nullptr;
    }
    FILE* f = _wfopen(name, L"w+bD");  // D: deleted when closed
    if (!f) {
        set_errno_from_crt(errno);
        DeleteFileW(name);
        return nullptr;
    }
    std::vector<char> buf(64 * 1024);
    for (;;) {
        const int n = readfn(cookie, buf.data(), static_cast<int>(buf.size()));
        if (n <= 0) break;
        std::fwrite(buf.data(), 1, static_cast<size_t>(n), f);
    }
    std::rewind(f);
    std::lock_guard<std::mutex> hold(g_funopen_lock);
    g_funopen_files[f] = {cookie, closefn};
    return f;
}

// ---- popen, pclose, system -------------------------------------------------------
//
// Bionic runs the command with /system/bin/sh -c, in a child it vforks. There
// is no Android shell on Windows to run, and running the command with cmd.exe
// would be a different language, so it goes the way it goes on a device
// whose exec of the shell fails: the child exits 127. popen still hands back
// its stream, which reads end-of-file (or takes writes nobody reads), and
// pclose, or fclose as in Bionic, returns that exit status, 127 << 8.
// system(NULL), "is there a shell", is 1 in Bionic whatever the device has.
constexpr int kNoShellStatus = 127 << 8;
std::mutex g_popen_lock;
std::map<FILE*, int> g_popen_status;

void report_command(const char* fn, const char* command) {
    static std::atomic<int> reported{0};
    if (reported.fetch_add(1) >= 20) return;  // a guest polling a command must not flood the log
    std::fprintf(stderr, "[win.apk] %s(\"%s\"): there is no /system/bin/sh on Windows; the command exits 127\n", fn,
                 command ? command : "");
    std::fflush(stderr);
}

WINAPK_GUEST_ABI FILE* g_popen(const char* command, const char* mode) {
    // "r" or "w", and an optional "e" (close-on-exec).
    const bool ok = mode && (mode[0] == 'r' || mode[0] == 'w') && (!mode[1] || (mode[1] == 'e' && !mode[2]));
    if (!ok) return *guest_errno() = kEINVAL, nullptr;
    report_command("popen", command);
    FILE* f = _wfopen(L"NUL", mode[0] == 'r' ? L"rb" : L"wb");
    if (!f) {
        set_errno_from_crt(errno);
        return nullptr;
    }
    std::lock_guard<std::mutex> hold(g_popen_lock);
    g_popen_status[f] = kNoShellStatus;
    return f;
}

WINAPK_GUEST_ABI int g_system(const char* command) {
    if (!command) return 1;
    report_command("system", command);
    return kNoShellStatus;
}

WINAPK_GUEST_ABI int g_fclose(void* f) {
    FILE* file = host_file(f);
    if (file == stdin || file == stdout || file == stderr) return 0;  // see fd_is_host_std
    {
        std::lock_guard<std::mutex> hold(g_popen_lock);
        auto it = g_popen_status.find(file);
        if (it != g_popen_status.end()) {
            const int status = it->second;
            g_popen_status.erase(it);
            std::fclose(file);
            return status;
        }
    }
    FunopenFile fun{};
    bool is_funopen = false;
    {
        std::lock_guard<std::mutex> hold(g_funopen_lock);
        auto it = g_funopen_files.find(file);
        if (it != g_funopen_files.end()) {
            fun = it->second;
            g_funopen_files.erase(it);
            is_funopen = true;
        }
    }
    const int rc = std::fclose(file) ? fail_crt() : 0;
    if (is_funopen && fun.closefn) fun.closefn(fun.cookie);
    return rc;
}
WINAPK_GUEST_ABI size_t g_fread(void* buf, size_t sz, size_t n, void* f) { return std::fread(buf, sz, n, host_file(f)); }
WINAPK_GUEST_ABI size_t g_fwrite(const void* buf, size_t sz, size_t n, void* f) {
    return std::fwrite(buf, sz, n, host_file(f));
}
WINAPK_GUEST_ABI int g_fputs(const char* s, void* f) { return std::fputs(s, host_file(f)); }
WINAPK_GUEST_ABI int g_fputc(int c, void* f) { return std::fputc(c, host_file(f)); }
WINAPK_GUEST_ABI int g_putchar(int c) { return std::fputc(c, stdout); }
WINAPK_GUEST_ABI char* g_fgets(char* s, int n, void* f) { return std::fgets(s, n, host_file(f)); }
WINAPK_GUEST_ABI char* g_fgets_chk(char* s, int n, void* f, size_t bn) {
    if (n < 0 || static_cast<size_t>(n) > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: fgets: prevented buffer overflow\n");
        std::abort();
    }
    return g_fgets(s, n, f);
}
// Bionic's FORTIFY fread/fwrite: the buffer must hold size * count bytes.
bool fits_fortify(size_t sz, size_t n, size_t bn) { return n == 0 || sz <= bn / n; }
WINAPK_GUEST_ABI size_t g_fread_chk(void* buf, size_t sz, size_t n, void* f, size_t bn) {
    if (!fits_fortify(sz, n, bn)) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: fread: prevented buffer overflow\n");
        std::abort();
    }
    return g_fread(buf, sz, n, f);
}
WINAPK_GUEST_ABI size_t g_fwrite_chk(const void* buf, size_t sz, size_t n, void* f, size_t bn) {
    if (!fits_fortify(sz, n, bn)) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: fwrite: prevented buffer over-read\n");
        std::abort();
    }
    return g_fwrite(buf, sz, n, f);
}
WINAPK_GUEST_ABI int g_fgetc(void* f) { return std::fgetc(host_file(f)); }
WINAPK_GUEST_ABI int g_ungetc(int c, void* f) { return std::ungetc(c, host_file(f)); }
WINAPK_GUEST_ABI int g_fflush(void* f) { return std::fflush(f ? host_file(f) : nullptr); }
WINAPK_GUEST_ABI int g_fseek(void* f, glong off, int whence) { return _fseeki64(host_file(f), off, whence) ? fail_crt() : 0; }
WINAPK_GUEST_ABI glong g_ftell(void* f) { return _ftelli64(host_file(f)); }
WINAPK_GUEST_ABI void g_rewind(void* f) { std::rewind(host_file(f)); }
WINAPK_GUEST_ABI int g_feof(void* f) { return std::feof(host_file(f)); }
WINAPK_GUEST_ABI int g_ferror(void* f) { return std::ferror(host_file(f)); }
WINAPK_GUEST_ABI void g_clearerr(void* f) { std::clearerr(host_file(f)); }
WINAPK_GUEST_ABI int g_fileno(void* f) { return _fileno(host_file(f)); }
WINAPK_GUEST_ABI int g_setvbuf(void* f, char* buf, int mode, size_t n) {
    // Linux: _IOFBF 0, _IOLBF 1, _IONBF 2.
    const int crt = mode == 2 ? _IONBF : mode == 1 ? _IOLBF : _IOFBF;
    return std::setvbuf(host_file(f), buf, crt, (crt == _IONBF || n >= 2) ? n : 2);
}
WINAPK_GUEST_ABI void g_setbuf(void* f, char* buf) { std::setbuf(host_file(f), buf); }
WINAPK_GUEST_ABI FILE* g_tmpfile() { return std::tmpfile(); }
WINAPK_GUEST_ABI void g_perror(const char* s) {
    std::fprintf(stderr, "%s%s%s\n", s ? s : "", s && *s ? ": " : "", errno_text(*guest_errno()));
}
// getline / getdelim: grow *line with the guest's allocator (the CRT's).
WINAPK_GUEST_ABI int64_t g_getdelim(char** line, size_t* cap, int delim, void* f) {
    FILE* h = host_file(f);
    size_t n = 0;
    for (;;) {
        const int c = std::fgetc(h);
        if (c == EOF) break;
        if (!*line || n + 2 > *cap) {
            const size_t grow = *cap ? *cap * 2 : 128;
            char* p = static_cast<char*>(std::realloc(*line, grow));
            if (!p) {
                *guest_errno() = kENOMEM;
                return -1;
            }
            *line = p;
            *cap = grow;
        }
        (*line)[n++] = static_cast<char>(c);
        if (c == delim) break;
    }
    if (n == 0) return -1;
    (*line)[n] = '\0';
    return static_cast<int64_t>(n);
}
WINAPK_GUEST_ABI int64_t g_getline(char** line, size_t* cap, void* f) { return g_getdelim(line, cap, '\n', f); }

// ---- calls that were missing -------------------------------------------------------------
// The loader binds a function the shim does not have to a stub that answers 0 (host/guest_libs.cpp).
// For the calls below 0 is not "failed" but "done", "nothing more to read" or "descriptor 0", and a
// caller that believes it loops, waits or writes into the wrong file. Each is now either the real thing
// over the calls above, or fails as Linux reports the failure and says so once in the log (say_once).

// readv/writev and the positioned forms: one read or write of the whole vector, as the syscall is
// (a datagram stays one datagram; a pipe's reader gets what is there, not one read per buffer).
struct GuestIovec {
    void* base;
    size_t len;
};
bool iov_total(const GuestIovec* iov, int n, size_t* total) {
    if (n < 0 || n > 1024 || (n && !iov)) return *guest_errno() = kEINVAL, false;  // IOV_MAX
    *total = 0;
    for (int i = 0; i < n; ++i) *total += iov[i].len;
    return true;
}
// `off` null: at the file position.
int64_t write_vector(int fd, const GuestIovec* iov, int n, const int64_t* off) {
    size_t total = 0;
    if (!iov_total(iov, n, &total)) return -1;
    if (n == 1) return off ? g_pwrite(fd, iov[0].base, iov[0].len, *off) : g_write(fd, iov[0].base, iov[0].len);
    std::unique_ptr<char[]> all(new (std::nothrow) char[total ? total : 1]);
    if (!all) return *guest_errno() = kENOMEM, -1;
    size_t at = 0;
    for (int i = 0; i < n; ++i) {
        std::memcpy(all.get() + at, iov[i].base, iov[i].len);
        at += iov[i].len;
    }
    return off ? g_pwrite(fd, all.get(), total, *off) : g_write(fd, all.get(), total);
}
int64_t read_vector(int fd, const GuestIovec* iov, int n, const int64_t* off) {
    size_t total = 0;
    if (!iov_total(iov, n, &total)) return -1;
    if (n == 1) return off ? g_pread(fd, iov[0].base, iov[0].len, *off) : g_read(fd, iov[0].base, iov[0].len);
    std::unique_ptr<char[]> all(new (std::nothrow) char[total ? total : 1]);
    if (!all) return *guest_errno() = kENOMEM, -1;
    const int64_t got = off ? g_pread(fd, all.get(), total, *off) : g_read(fd, all.get(), total);
    size_t at = 0;
    for (int i = 0; i < n && got > 0 && at < static_cast<size_t>(got); ++i) {
        const size_t part = std::min(iov[i].len, static_cast<size_t>(got) - at);
        std::memcpy(iov[i].base, all.get() + at, part);
        at += part;
    }
    return got;
}
WINAPK_GUEST_ABI int64_t g_writev(int fd, const GuestIovec* iov, int n) { return write_vector(fd, iov, n, nullptr); }
WINAPK_GUEST_ABI int64_t g_readv(int fd, const GuestIovec* iov, int n) { return read_vector(fd, iov, n, nullptr); }
WINAPK_GUEST_ABI int64_t g_pwritev(int fd, const GuestIovec* iov, int n, int64_t off) {
    return write_vector(fd, iov, n, &off);
}
WINAPK_GUEST_ABI int64_t g_preadv(int fd, const GuestIovec* iov, int n, int64_t off) {
    return read_vector(fd, iov, n, &off);
}
// preadv2/pwritev2: offset -1 is the file position; the RWF_* flags are hints here.
WINAPK_GUEST_ABI int64_t g_pwritev2(int fd, const GuestIovec* iov, int n, int64_t off, int) {
    return write_vector(fd, iov, n, off == -1 ? nullptr : &off);
}
WINAPK_GUEST_ABI int64_t g_preadv2(int fd, const GuestIovec* iov, int n, int64_t off, int) {
    return read_vector(fd, iov, n, off == -1 ? nullptr : &off);
}

// sendfile: read from `in` (at *offset, which then moves, or at its position) and write to `out`.
// Every byte taken from `in` is written or given back, so a short count loses nothing.
WINAPK_GUEST_ABI int64_t g_sendfile(int out, int in, int64_t* offset, size_t count) {
    std::vector<char> buf(std::min<size_t>(count, 65536));
    size_t done = 0;
    while (done < count) {
        const size_t want = std::min(count - done, buf.size());
        const int64_t got = offset ? g_pread(in, buf.data(), want, *offset) : g_read(in, buf.data(), want);
        if (got < 0) return done ? static_cast<int64_t>(done) : -1;
        if (got == 0) break;
        size_t put = 0;
        while (put < static_cast<size_t>(got)) {
            const int64_t w = g_write(out, buf.data() + put, static_cast<size_t>(got) - put);
            if (w <= 0) break;  // errno is the write's (EAGAIN on a full non-blocking socket)
            put += static_cast<size_t>(w);
        }
        done += put;
        if (offset) *offset += static_cast<int64_t>(put);
        if (put < static_cast<size_t>(got)) {
            if (!offset) {
                const int e = *guest_errno();
                g_lseek(in, static_cast<int64_t>(put) - got, SEEK_CUR);
                *guest_errno() = e;
            }
            return done ? static_cast<int64_t>(done) : -1;
        }
    }
    return static_cast<int64_t>(done);
}
// splice, tee, vmsplice: EINVAL is Linux's answer for descriptors a splice cannot serve, and
// copy_file_range's ENOSYS that of a kernel before it; callers copy with read and write then.
// TODO(splice): move the bytes here, as sendfile does, if an app turns out to have no other path.
WINAPK_GUEST_ABI int64_t g_splice() {
    static std::atomic<bool> said{false};
    say_once(said, "splice()/tee()/vmsplice()", "not supported; the call fails with EINVAL");
    return *guest_errno() = kEINVAL, -1;
}
WINAPK_GUEST_ABI int64_t g_copy_file_range() {
    static std::atomic<bool> said{false};
    say_once(said, "copy_file_range()", "not supported; the call fails with ENOSYS");
    return *guest_errno() = kENOSYS, -1;
}

WINAPK_GUEST_ABI int g_truncate(const char* path, int64_t len) {
    const int fd = g_open(path, kOWrOnly, 0);
    if (fd < 0) return -1;
    const int r = g_ftruncate(fd, len);
    const int e = *guest_errno();
    g_close(fd);
    if (r < 0) *guest_errno() = e;
    return r;
}
WINAPK_GUEST_ABI int g_renameat(int from_dir, const char* from, int to_dir, const char* to) {
    std::string a, b;
    return at_path(from_dir, from, &a) && at_path(to_dir, to, &b) ? g_rename(a.c_str(), b.c_str()) : -1;
}
// renameat2: RENAME_NOREPLACE (1) fails with EEXIST when the new name is taken; the other flags
// (exchange, whiteout) are EINVAL, as on a file system without them.
WINAPK_GUEST_ABI int g_renameat2(int from_dir, const char* from, int to_dir, const char* to, unsigned flags) {
    if (flags == 0) return g_renameat(from_dir, from, to_dir, to);
    if (flags != 1) return *guest_errno() = kEINVAL, -1;
    std::string a, b;
    if (!at_path(from_dir, from, &a) || !at_path(to_dir, to, &b)) return -1;
    if (MoveFileExW(host_path(a.c_str()).c_str(), host_path(b.c_str()).c_str(), MOVEFILE_COPY_ALLOWED)) return 0;
    const DWORD error = GetLastError();
    if (error == ERROR_ALREADY_EXISTS || error == ERROR_FILE_EXISTS) return *guest_errno() = kEEXIST, -1;
    set_errno_from_win32(error);
    return -1;
}
WINAPK_GUEST_ABI int g_linkat(int from_dir, const char* from, int to_dir, const char* to, int) {
    std::string a, b;
    return at_path(from_dir, from, &a) && at_path(to_dir, to, &b) ? g_link(a.c_str(), b.c_str()) : -1;
}
// symlink: EPERM, Linux's answer on a file system without symbolic links (an SD card's FAT), which
// apps already meet on Android.
// TODO(symlinks): CreateSymbolicLinkW needs developer mode or elevation; with it, readlink and lstat
// would have to report the link too.
WINAPK_GUEST_ABI int g_symlink() {
    static std::atomic<bool> said{false};
    say_once(said, "symlink()", "symbolic links are not supported; the call fails with EPERM");
    return *guest_errno() = kEPERM, -1;
}

WINAPK_GUEST_ABI int64_t g_readlinkat_chk(int dirfd, const char* path, char* buf, size_t size, size_t buf_size) {
    return g_readlinkat(dirfd, path, buf, std::min(size, buf_size));
}
WINAPK_GUEST_ABI char* g_getcwd_chk(char* buf, size_t n, size_t bn) {
    if (buf && n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: getcwd: prevented buffer overflow\n");
        std::abort();
    }
    return g_getcwd(buf, n);
}

// ---- terminals -----------------------------------------------------------------------------
// No descriptor of an app is a terminal, and there is no /dev/ptmx to open one from.
WINAPK_GUEST_ABI int g_not_a_tty() { return *guest_errno() = 25, -1; }  // ENOTTY: tcgetattr and the rest
WINAPK_GUEST_ABI int g_ttyname_r() { return *guest_errno() = 25; }      // these two return the error
// TODO(pseudo-terminals): a terminal app's shell needs a pty (ConPTY) and a process to run in it
// (see fork in misc.cpp).
WINAPK_GUEST_ABI int g_openpty() {
    static std::atomic<bool> said{false};
    say_once(said, "openpty()/posix_openpt()",
             "the app asked for a pseudo-terminal, which Win.apk does not have; the call fails with ENOENT");
    return *guest_errno() = kENOENT, -1;
}

// ---- nftw, glob ---------------------------------------------------------------------------
// TODO(nftw): ftw and nftw over the fts walk above (the callback gets Bionic's struct stat and FTW).
WINAPK_GUEST_ABI int g_nftw() {
    static std::atomic<bool> said{false};
    say_once(said, "ftw()/nftw()", "not implemented; the walk fails with ENOSYS and visits nothing");
    return *guest_errno() = kENOSYS, -1;
}
// TODO(glob): glob over opendir and fnmatch. GLOB_NOSYS (-4) is <glob.h>'s "not supported"; the
// result is left empty (gl_pathc, and gl_pathv at offset 32) unless the caller appends to one.
WINAPK_GUEST_ABI int g_glob(const char*, int flags, void*, void* result) {
    static std::atomic<bool> said{false};
    say_once(said, "glob()", "not implemented; the call fails with GLOB_NOSYS");
    if (result && !(flags & 0x0001)) {  // GLOB_APPEND
        std::memset(result, 0, sizeof(size_t));
        std::memset(static_cast<char*>(result) + 32, 0, sizeof(char**));
    }
    return -4;
}
WINAPK_GUEST_ABI void g_globfree(void*) {}

WINAPK_GUEST_ABI int g_getchar() { return std::fgetc(stdin); }

// libandroid's ASharedMemory: an anonymous file of `size` bytes to mmap (memfd_create above). Missing,
// ASharedMemory_create answered 0 through the loader's fallback: descriptor 0 as the new region.
// setProt only ever takes rights away from later mmaps, which nothing else here could make.
WINAPK_GUEST_ABI int g_ASharedMemory_create(const char* name, size_t size) {
    const int fd = g_memfd_create(name, 0);
    if (fd < 0) return -1;
    if (g_ftruncate(fd, static_cast<int64_t>(size)) == 0) return fd;
    const int e = *guest_errno();
    g_close(fd);
    return *guest_errno() = e, -1;
}
WINAPK_GUEST_ABI size_t g_ASharedMemory_getSize(int fd) {
    const int64_t size = _filelengthi64(fd);
    return size < 0 ? 0 : static_cast<size_t>(size);  // 0 is its answer for a descriptor that is not one
}
WINAPK_GUEST_ABI int g_ASharedMemory_setProt(int, int) { return 0; }

const ShimSymbol kSymbols[] = {
    {"__sF", g_sF},
    {"stdin", &g_stdin_var},
    {"stdout", &g_stdout_var},
    {"stderr", &g_stderr_var},
    WINAPK_SHIM("open", g_open),
    WINAPK_SHIM("open64", g_open),
    WINAPK_SHIM("__open_2", g_open_2),
    WINAPK_SHIM("openat", g_openat),
    WINAPK_SHIM("openat64", g_openat),
    WINAPK_SHIM("__openat_2", g_openat_2),
    WINAPK_SHIM("creat", g_creat),
    WINAPK_SHIM("close", g_close),
    WINAPK_SHIM("read", g_read),
    WINAPK_SHIM("__read_chk", g_read_chk),
    WINAPK_SHIM("__pread_chk", g_pread_chk),
    WINAPK_SHIM("__pread64_chk", g_pread_chk),
    WINAPK_SHIM("__write_chk", g_write_chk),
    WINAPK_SHIM("__pwrite_chk", g_pwrite_chk),
    WINAPK_SHIM("__pwrite64_chk", g_pwrite_chk),
    WINAPK_SHIM("write", g_write),
    WINAPK_SHIM("lseek", g_lseek),
    WINAPK_SHIM("lseek64", g_lseek),
    WINAPK_SHIM("pread", g_pread),
    WINAPK_SHIM("pread64", g_pread),
    WINAPK_SHIM("pwrite", g_pwrite),
    WINAPK_SHIM("pwrite64", g_pwrite),
    WINAPK_SHIM("readv", g_readv),
    WINAPK_SHIM("writev", g_writev),
    WINAPK_SHIM("preadv", g_preadv),
    WINAPK_SHIM("preadv64", g_preadv),
    WINAPK_SHIM("pwritev", g_pwritev),
    WINAPK_SHIM("pwritev64", g_pwritev),
    WINAPK_SHIM("preadv2", g_preadv2),
    WINAPK_SHIM("preadv64v2", g_preadv2),
    WINAPK_SHIM("pwritev2", g_pwritev2),
    WINAPK_SHIM("pwritev64v2", g_pwritev2),
    WINAPK_SHIM("sendfile", g_sendfile),
    WINAPK_SHIM("sendfile64", g_sendfile),
    WINAPK_SHIM("splice", g_splice),
    WINAPK_SHIM("tee", g_splice),
    WINAPK_SHIM("vmsplice", g_splice),
    WINAPK_SHIM("copy_file_range", g_copy_file_range),
    WINAPK_SHIM("__readlinkat_chk", g_readlinkat_chk),
    WINAPK_SHIM("__getcwd_chk", g_getcwd_chk),
    WINAPK_SHIM("tcgetattr", g_not_a_tty),
    WINAPK_SHIM("tcsetattr", g_not_a_tty),
    WINAPK_SHIM("tcflush", g_not_a_tty),
    WINAPK_SHIM("tcdrain", g_not_a_tty),
    WINAPK_SHIM("tcflow", g_not_a_tty),
    WINAPK_SHIM("tcsendbreak", g_not_a_tty),
    WINAPK_SHIM("tcgetpgrp", g_not_a_tty),
    WINAPK_SHIM("tcsetpgrp", g_not_a_tty),
    WINAPK_SHIM("tcgetsid", g_not_a_tty),
    WINAPK_SHIM("unlockpt", g_not_a_tty),
    WINAPK_SHIM("ttyname_r", g_ttyname_r),
    WINAPK_SHIM("ptsname_r", g_ttyname_r),
    WINAPK_SHIM("openpty", g_openpty),
    WINAPK_SHIM("forkpty", g_openpty),
    WINAPK_SHIM("posix_openpt", g_openpty),
    WINAPK_SHIM("getpt", g_openpty),
    WINAPK_SHIM("ftw", g_nftw),
    WINAPK_SHIM("ftw64", g_nftw),
    WINAPK_SHIM("nftw", g_nftw),
    WINAPK_SHIM("nftw64", g_nftw),
    WINAPK_SHIM("glob", g_glob),
    WINAPK_SHIM("globfree", g_globfree),
    WINAPK_SHIM("dup", g_dup),
    WINAPK_SHIM("dup2", g_dup2),
    WINAPK_SHIM("dup3", g_dup3),
    WINAPK_SHIM("fsync", g_fsync),
    WINAPK_SHIM("fdatasync", g_fsync),
    WINAPK_SHIM("ftruncate", g_ftruncate),
    WINAPK_SHIM("ftruncate64", g_ftruncate),
    WINAPK_SHIM("posix_fallocate", g_posix_fallocate),
    WINAPK_SHIM("posix_fallocate64", g_posix_fallocate),
    WINAPK_SHIM("fallocate", g_fallocate),
    WINAPK_SHIM("fallocate64", g_fallocate),
    WINAPK_SHIM("isatty", g_isatty),
    WINAPK_SHIM("fcntl", g_fcntl),
    WINAPK_SHIM("ioctl", g_ioctl),
    WINAPK_SHIM("stat", g_stat),
    WINAPK_SHIM("stat64", g_stat),
    WINAPK_SHIM("lstat", g_lstat),
    WINAPK_SHIM("lstat64", g_lstat),
    WINAPK_SHIM("readlink", g_readlink),
    WINAPK_SHIM("__readlink_chk", g_readlink_chk),
    WINAPK_SHIM("readlinkat", g_readlinkat),
    WINAPK_SHIM("unlinkat", g_unlinkat),
    WINAPK_SHIM("fstat", g_fstat),
    WINAPK_SHIM("fstat64", g_fstat),
    WINAPK_SHIM("fstatat", g_fstatat),
    WINAPK_SHIM("memfd_create", g_memfd_create),
    WINAPK_SHIM("ASharedMemory_create", g_ASharedMemory_create),
    WINAPK_SHIM("ASharedMemory_getSize", g_ASharedMemory_getSize),
    WINAPK_SHIM("ASharedMemory_setProt", g_ASharedMemory_setProt),
    WINAPK_SHIM("flock", g_flock),
    WINAPK_SHIM("statvfs", g_statvfs),
    WINAPK_SHIM("fstatvfs", g_fstatvfs),
    WINAPK_SHIM("statfs", g_statfs),
    WINAPK_SHIM("statfs64", g_statfs),
    WINAPK_SHIM("fstatfs", g_fstatfs),
    WINAPK_SHIM("fstatfs64", g_fstatfs),
    WINAPK_SHIM("fstatat64", g_fstatat),
    WINAPK_SHIM("access", g_access),
    WINAPK_SHIM("faccessat", g_faccessat),
    WINAPK_SHIM("mkdir", g_mkdir),
    WINAPK_SHIM("mkdirat", g_mkdirat),
    WINAPK_SHIM("rmdir", g_rmdir),
    WINAPK_SHIM("unlink", g_unlink),
    WINAPK_SHIM("mkfifo", g_mkfifo),
    WINAPK_SHIM("mkfifoat", g_mkfifoat),
    WINAPK_SHIM("mknod", g_mknod),
    WINAPK_SHIM("rename", g_rename),
    WINAPK_SHIM("renameat", g_renameat),
    WINAPK_SHIM("renameat2", g_renameat2),
    WINAPK_SHIM("truncate", g_truncate),
    WINAPK_SHIM("truncate64", g_truncate),
    WINAPK_SHIM("remove", g_remove),
    WINAPK_SHIM("chdir", g_chdir),
    WINAPK_SHIM("getcwd", g_getcwd),
    WINAPK_SHIM("realpath", g_realpath),
    WINAPK_SHIM("chmod", g_chmod),
    WINAPK_SHIM("fchmod", g_fchmod),
    WINAPK_SHIM("umask", g_umask),
    WINAPK_SHIM("__umask_chk", g_umask_chk),
    WINAPK_SHIM("mkstemp", g_mkstemp),
    WINAPK_SHIM("mkstemp64", g_mkstemp),
    WINAPK_SHIM("mkostemp", g_mkostemp),
    WINAPK_SHIM("mkstemps", g_mkstemps),
    WINAPK_SHIM("mkstemps64", g_mkstemps),
    WINAPK_SHIM("mkostemps", g_mkostemps),
    WINAPK_SHIM("mkostemps64", g_mkostemps),
    WINAPK_SHIM("mkostemp64", g_mkostemp),
    WINAPK_SHIM("opendir", g_opendir),
    WINAPK_SHIM("fdopendir", g_fdopendir),
    WINAPK_SHIM("dirfd", g_dirfd),
    WINAPK_SHIM("pathconf", g_pathconf),
    WINAPK_SHIM("fpathconf", g_fpathconf),
    WINAPK_SHIM("funopen", g_funopen),
    WINAPK_SHIM("readdir", g_readdir),
    WINAPK_SHIM("readdir64", g_readdir),
    WINAPK_SHIM("readdir_r", g_readdir_r),
    WINAPK_SHIM("readdir64_r", g_readdir_r),
    WINAPK_SHIM("scandir", g_scandir),
    WINAPK_SHIM("scandir64", g_scandir),
    WINAPK_SHIM("scandirat", g_scandirat),
    WINAPK_SHIM("scandirat64", g_scandirat),
    WINAPK_SHIM("alphasort", g_alphasort),
    WINAPK_SHIM("alphasort64", g_alphasort),
    WINAPK_SHIM("closedir", g_closedir),
    WINAPK_SHIM("rewinddir", g_rewinddir),
    WINAPK_SHIM("fopen", g_fopen),
    WINAPK_SHIM("fopen64", g_fopen),
    WINAPK_SHIM("fdopen", g_fdopen),
    WINAPK_SHIM("freopen", g_freopen),
    WINAPK_SHIM("fclose", g_fclose),
    WINAPK_SHIM("pclose", g_fclose),
    WINAPK_SHIM("popen", g_popen),
    WINAPK_SHIM("system", g_system),
    WINAPK_SHIM("link", g_link),
    WINAPK_SHIM("linkat", g_linkat),
    WINAPK_SHIM("symlink", g_symlink),
    WINAPK_SHIM("symlinkat", g_symlink),
    WINAPK_SHIM("fts_open", g_fts_open),
    WINAPK_SHIM("fts_read", g_fts_read),
    WINAPK_SHIM("fts_children", g_fts_children),
    WINAPK_SHIM("fts_set", g_fts_set),
    WINAPK_SHIM("fts_close", g_fts_close),
    WINAPK_SHIM("fread", g_fread),
    WINAPK_SHIM("fwrite", g_fwrite),
    WINAPK_SHIM("fputs", g_fputs),
    WINAPK_SHIM("fputc", g_fputc),
    WINAPK_SHIM("putc", g_fputc),
    WINAPK_SHIM("putchar", g_putchar),
    WINAPK_SHIM("fgets", g_fgets),
    WINAPK_SHIM("__fgets_chk", g_fgets_chk),
    WINAPK_SHIM("__fread_chk", g_fread_chk),
    WINAPK_SHIM("__fwrite_chk", g_fwrite_chk),
    WINAPK_SHIM("fgetc", g_fgetc),
    WINAPK_SHIM("getc", g_fgetc),
    WINAPK_SHIM("ungetc", g_ungetc),
    WINAPK_SHIM("fflush", g_fflush),
    WINAPK_SHIM("fseek", g_fseek),
    WINAPK_SHIM("fseeko", g_fseek),
    WINAPK_SHIM("fseeko64", g_fseek),
    WINAPK_SHIM("ftell", g_ftell),
    WINAPK_SHIM("ftello", g_ftell),
    WINAPK_SHIM("ftello64", g_ftell),
    WINAPK_SHIM("rewind", g_rewind),
    WINAPK_SHIM("feof", g_feof),
    WINAPK_SHIM("ferror", g_ferror),
    WINAPK_SHIM("clearerr", g_clearerr),
    WINAPK_SHIM("fileno", g_fileno),
    WINAPK_SHIM("setvbuf", g_setvbuf),
    WINAPK_SHIM("setbuf", g_setbuf),
    WINAPK_SHIM("tmpfile", g_tmpfile),
    WINAPK_SHIM("tmpfile64", g_tmpfile),
    // The _unlocked forms (a stream's lock is the CRT's own). Missing, getc_unlocked answered 0 for
    // ever, never EOF.
    WINAPK_SHIM("getc_unlocked", g_fgetc),
    WINAPK_SHIM("fgetc_unlocked", g_fgetc),
    WINAPK_SHIM("getchar", g_getchar),
    WINAPK_SHIM("getchar_unlocked", g_getchar),
    WINAPK_SHIM("putc_unlocked", g_fputc),
    WINAPK_SHIM("fputc_unlocked", g_fputc),
    WINAPK_SHIM("putchar_unlocked", g_putchar),
    WINAPK_SHIM("fread_unlocked", g_fread),
    WINAPK_SHIM("fwrite_unlocked", g_fwrite),
    WINAPK_SHIM("fputs_unlocked", g_fputs),
    WINAPK_SHIM("fgets_unlocked", g_fgets),
    WINAPK_SHIM("feof_unlocked", g_feof),
    WINAPK_SHIM("ferror_unlocked", g_ferror),
    WINAPK_SHIM("clearerr_unlocked", g_clearerr),
    WINAPK_SHIM("fflush_unlocked", g_fflush),
    WINAPK_SHIM("fileno_unlocked", g_fileno),
    WINAPK_SHIM("perror", g_perror),
    WINAPK_SHIM("getdelim", g_getdelim),
    WINAPK_SHIM("getline", g_getline),
};

}  // namespace

size_t file_write(void* f, const char* data, size_t n) { return std::fwrite(data, 1, n, host_file(f)); }
int64_t fd_write(int fd, const void* data, size_t n) { return g_write(fd, data, n); }
void* host_stream(void* guest_file) { return host_file(guest_file); }
void* guest_stdout() { return stdout; }
void* guest_stderr() { return stderr; }

ShimTable stdio_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic

namespace winapk {

void* fts_open_with_stat(char* const* argv, int options, void* compar, FtsStatFill fill) {
    return bionic::fts_open_impl(argv, options, reinterpret_cast<bionic::GuestFtsCompar>(compar), fill);
}

}  // namespace winapk
