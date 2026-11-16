// native_os.cpp -- com.macapk.loader.NativeOs on Windows.
//
// android.system.Os and ParcelFileDescriptor hand raw file descriptors to app
// native code (Chrome's ICU data, Unity's assets, SoLoader). For a number to
// mean the same file on both sides, these natives go through the Bionic shim:
// the same open/read/fstat guest code calls, the same descriptor table (the
// UCRT's), and the same Linux errno values. Upstream's runtime/src/native_os.cpp
// does the same on Darwin's libc.
//
// The environment calls go to the guest's environment for the same reason:
// android.system.Os.setenv exists so that an app's own library reads the
// variable with getenv(3) (AnkiDroid's Rust backend is upstream's case).
//
// A java.io.FileDescriptor on Windows carries a HANDLE, not a number.
// fdForHandle and handleForFd convert between the two by duplicating the
// handle, so Java and native code each own (and close) their own copy, which
// shares the file position as a dup(2) would.

#include "host_natives.h"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include <fcntl.h>
#include <io.h>
#include <windows.h>

#include "winapk/guest_abi.h"
#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

std::string utf8(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

constexpr jint kEINVAL = 22;

// A shim function by name, as guest code would bind it.
template <typename F>
F shim(const char* name) {
    return reinterpret_cast<F>(winapk::mini_bionic_resolve(name));
}
int guest_errno() { return *shim<int*(WINAPK_GUEST_ABI*)()>("__errno")(); }
// -errno for a failed call (result < 0), else the result.
template <typename T>
T result(T r) {
    return r < 0 ? -static_cast<T>(guest_errno()) : r;
}

jint JNICALL Os_open(JNIEnv* env, jclass, jstring path, jint flags, jint mode) {
    if (!path) return -kEINVAL;
    static const auto open = shim<int(WINAPK_GUEST_ABI*)(const char*, int, unsigned)>("open");
    return result(open(utf8(env, path).c_str(), flags, static_cast<unsigned>(mode)));
}
jlong JNICALL Os_lseek(JNIEnv*, jclass, jint fd, jlong offset, jint whence) {
    static const auto lseek = shim<int64_t(WINAPK_GUEST_ABI*)(int, int64_t, int)>("lseek");
    return result<jlong>(lseek(fd, offset, whence));
}
jint JNICALL Os_close(JNIEnv*, jclass, jint fd) {
    static const auto close = shim<int(WINAPK_GUEST_ABI*)(int)>("close");
    return result(close(fd));
}
jint JNICALL Os_dup(JNIEnv*, jclass, jint fd) {
    static const auto dup = shim<int(WINAPK_GUEST_ABI*)(int)>("dup");
    return result(dup(fd));
}
jint JNICALL Os_dup2(JNIEnv*, jclass, jint from, jint to) {
    static const auto dup2 = shim<int(WINAPK_GUEST_ABI*)(int, int)>("dup2");
    return result(dup2(from, to));
}
jlong JNICALL Os_sysconf(JNIEnv*, jclass, jint name) {
    static const auto sysconf = shim<int64_t(WINAPK_GUEST_ABI*)(int)>("sysconf");
    return sysconf(name);
}
jint JNICALL Os_memfdCreate(JNIEnv* env, jclass, jstring name, jint flags) {
    static const auto memfd = shim<int(WINAPK_GUEST_ABI*)(const char*, unsigned)>("memfd_create");
    return result(memfd(name ? utf8(env, name).c_str() : "", static_cast<unsigned>(flags)));
}
jint JNICALL Os_ftruncate(JNIEnv*, jclass, jint fd, jlong length) {
    static const auto ftruncate = shim<int(WINAPK_GUEST_ABI*)(int, int64_t)>("ftruncate");
    return result(ftruncate(fd, length));
}
jint JNICALL Os_read(JNIEnv* env, jclass, jint fd, jbyteArray buf, jint off, jint count) {
    if (!buf || off < 0 || count < 0 || off + count > env->GetArrayLength(buf)) return -kEINVAL;
    static const auto read = shim<int64_t(WINAPK_GUEST_ABI*)(int, void*, size_t)>("read");
    std::vector<jbyte> tmp(static_cast<size_t>(count));
    const int64_t r = read(fd, tmp.data(), tmp.size());
    if (r < 0) return -guest_errno();
    env->SetByteArrayRegion(buf, off, static_cast<jsize>(r), tmp.data());
    return static_cast<jint>(r);
}
jint JNICALL Os_write(JNIEnv* env, jclass, jint fd, jbyteArray buf, jint off, jint count) {
    if (!buf || off < 0 || count < 0 || off + count > env->GetArrayLength(buf)) return -kEINVAL;
    static const auto write = shim<int64_t(WINAPK_GUEST_ABI*)(int, const void*, size_t)>("write");
    std::vector<jbyte> tmp(static_cast<size_t>(count));
    env->GetByteArrayRegion(buf, off, count, tmp.data());
    const int64_t r = write(fd, tmp.data(), tmp.size());
    return r < 0 ? -guest_errno() : static_cast<jint>(r);
}

// fstat as upstream returns it: dev, ino, mode, nlink, size, atime, mtime,
// ctime, blksize, blocks, rdev; null on failure. Read from Bionic's struct stat.
jlongArray JNICALL Os_fstat(JNIEnv* env, jclass, jint fd) {
    struct GuestStat {
        uint64_t st_dev, st_ino, st_nlink;
        uint32_t st_mode, st_uid, st_gid;
        int32_t pad0;
        uint64_t st_rdev;
        int64_t st_size, st_blksize, st_blocks;
        int64_t st_atime_sec, st_atime_nsec, st_mtime_sec, st_mtime_nsec, st_ctime_sec, st_ctime_nsec;
        int64_t unused[3];
    } st;
    static const auto fstat = shim<int(WINAPK_GUEST_ABI*)(int, GuestStat*)>("fstat");
    if (fstat(fd, &st) < 0) return nullptr;
    const jlong v[11] = {static_cast<jlong>(st.st_dev),  static_cast<jlong>(st.st_ino),  st.st_mode,
                         static_cast<jlong>(st.st_nlink), st.st_size,    st.st_atime_sec,
                         st.st_mtime_sec, st.st_ctime_sec, st.st_blksize, st.st_blocks,
                         static_cast<jlong>(st.st_rdev)};
    jlongArray a = env->NewLongArray(11);
    if (a) env->SetLongArrayRegion(a, 0, 11, v);
    return a;
}

// statvfs/fstatvfs as upstream returns them: the eleven fields of struct statvfs.
struct GuestStatvfs {
    uint64_t f[11];
    uint32_t spare[6];
};
jlongArray statvfs_array(JNIEnv* env, const GuestStatvfs& sv) {
    jlongArray a = env->NewLongArray(11);
    if (a) env->SetLongArrayRegion(a, 0, 11, reinterpret_cast<const jlong*>(sv.f));
    return a;
}
jlongArray JNICALL Os_statvfs(JNIEnv* env, jclass, jstring path) {
    if (!path) return nullptr;
    static const auto statvfs = shim<int(WINAPK_GUEST_ABI*)(const char*, GuestStatvfs*)>("statvfs");
    GuestStatvfs sv;
    return statvfs(utf8(env, path).c_str(), &sv) < 0 ? nullptr : statvfs_array(env, sv);
}
jlongArray JNICALL Os_fstatvfs(JNIEnv* env, jclass, jint fd) {
    static const auto fstatvfs = shim<int(WINAPK_GUEST_ABI*)(int, GuestStatvfs*)>("fstatvfs");
    GuestStatvfs sv;
    return fstatvfs(fd, &sv) < 0 ? nullptr : statvfs_array(env, sv);
}

// The app's run lock (com.macapk.loader.RunLock): run/<app>.pid, locked
// exclusively for the life of this process, holding our pid. Returns the held
// descriptor or -errno (-EAGAIN: another process holds it).
jint JNICALL Os_takeRunLock(JNIEnv* env, jclass, jstring path) {
    if (!path) return -kEINVAL;
    static const auto open = shim<int(WINAPK_GUEST_ABI*)(const char*, int, unsigned)>("open");
    static const auto flock = shim<int(WINAPK_GUEST_ABI*)(int, int)>("flock");
    static const auto close = shim<int(WINAPK_GUEST_ABI*)(int)>("close");
    const int fd = open(utf8(env, path).c_str(), 02 | 0100 | 02000000, 0644);  // O_RDWR|O_CREAT|O_CLOEXEC
    if (fd < 0) return -guest_errno();
    if (flock(fd, 2 | 4) < 0) {  // LOCK_EX | LOCK_NB
        const int e = guest_errno();
        close(fd);
        return -e;
    }
    char buf[32];
    const int n = std::snprintf(buf, sizeof(buf), "%lu\n", GetCurrentProcessId());
    _chsize_s(fd, 0);
    _lseeki64(fd, 0, SEEK_SET);
    _write(fd, buf, static_cast<unsigned>(n));
    return fd;  // never closed: the lock lives as long as the process
}

// upstream decompresses .xz with Apple's Compression framework; there is no
// LZMA decoder here yet, and null is the documented failure result.
jbyteArray JNICALL Os_xzDecompress(JNIEnv*, jclass, jbyteArray) { return nullptr; }

jint JNICALL Os_fdForHandle(JNIEnv*, jclass, jlong handle) {
    HANDLE copy = nullptr;
    if (!DuplicateHandle(GetCurrentProcess(), reinterpret_cast<HANDLE>(handle), GetCurrentProcess(), &copy, 0, FALSE,
                         DUPLICATE_SAME_ACCESS)) {
        return -9;  // EBADF
    }
    const int fd = _open_osfhandle(reinterpret_cast<intptr_t>(copy), _O_BINARY);
    if (fd < 0) {
        CloseHandle(copy);
        return -24;  // EMFILE
    }
    return fd;
}
jlong JNICALL Os_handleForFd(JNIEnv*, jclass, jint fd) {
    const HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    HANDLE copy = nullptr;
    if (h == INVALID_HANDLE_VALUE ||
        !DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &copy, 0, FALSE, DUPLICATE_SAME_ACCESS)) {
        return -1;
    }
    return reinterpret_cast<jlong>(copy);
}

void JNICALL Os_closeHandle(JNIEnv*, jclass, jlong handle) { CloseHandle(reinterpret_cast<HANDLE>(handle)); }

jint JNICALL Os_setenv(JNIEnv* env, jclass, jstring name, jstring value, jboolean overwrite) {
    if (!name || !value) return -kEINVAL;
    return winapk::bionic_setenv(utf8(env, name).c_str(), utf8(env, value).c_str(), overwrite ? 1 : 0);
}
jstring JNICALL Os_getenv(JNIEnv* env, jclass, jstring name) {
    if (!name) return nullptr;
    const char* v = winapk::bionic_getenv(utf8(env, name).c_str());
    return v ? env->NewStringUTF(v) : nullptr;
}
jint JNICALL Os_unsetenv(JNIEnv* env, jclass, jstring name) {
    if (!name) return -kEINVAL;
    return winapk::bionic_unsetenv(utf8(env, name).c_str());
}

constexpr const char* kCls = "com.macapk.loader.NativeOs";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "open", "(Ljava/lang/String;II)I", Os_open),
    WINAPK_HOST_NATIVE(kCls, "lseek", "(IJI)J", Os_lseek),
    WINAPK_HOST_NATIVE(kCls, "close", "(I)I", Os_close),
    WINAPK_HOST_NATIVE(kCls, "dup", "(I)I", Os_dup),
    WINAPK_HOST_NATIVE(kCls, "dup2", "(II)I", Os_dup2),
    WINAPK_HOST_NATIVE(kCls, "sysconf", "(I)J", Os_sysconf),
    WINAPK_HOST_NATIVE(kCls, "memfdCreate", "(Ljava/lang/String;I)I", Os_memfdCreate),
    WINAPK_HOST_NATIVE(kCls, "fstat", "(I)[J", Os_fstat),
    WINAPK_HOST_NATIVE(kCls, "ftruncate", "(IJ)I", Os_ftruncate),
    WINAPK_HOST_NATIVE(kCls, "takeRunLock", "(Ljava/lang/String;)I", Os_takeRunLock),
    WINAPK_HOST_NATIVE(kCls, "read", "(I[BII)I", Os_read),
    WINAPK_HOST_NATIVE(kCls, "write", "(I[BII)I", Os_write),
    WINAPK_HOST_NATIVE(kCls, "xzDecompress", "([B)[B", Os_xzDecompress),
    WINAPK_HOST_NATIVE(kCls, "statvfs", "(Ljava/lang/String;)[J", Os_statvfs),
    WINAPK_HOST_NATIVE(kCls, "fstatvfs", "(I)[J", Os_fstatvfs),
    WINAPK_HOST_NATIVE(kCls, "fdForHandle", "(J)I", Os_fdForHandle),
    WINAPK_HOST_NATIVE(kCls, "handleForFd", "(I)J", Os_handleForFd),
    WINAPK_HOST_NATIVE(kCls, "closeHandle", "(J)V", Os_closeHandle),
    WINAPK_HOST_NATIVE(kCls, "setenv", "(Ljava/lang/String;Ljava/lang/String;Z)I", Os_setenv),
    WINAPK_HOST_NATIVE(kCls, "getenv", "(Ljava/lang/String;)Ljava/lang/String;", Os_getenv),
    WINAPK_HOST_NATIVE(kCls, "unsetenv", "(Ljava/lang/String;)I", Os_unsetenv),
};

}  // namespace

HostMethods native_os_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
