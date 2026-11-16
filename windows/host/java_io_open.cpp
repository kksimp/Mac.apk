// java_io_open.cpp -- java.io's file opens share deletion, as files on Android do.
//
// On Linux an open file can be renamed or deleted: the name goes, the open
// descriptor keeps the file. Android code relies on it, the write-a-pending-
// file-then-rename-it pattern above all, which often renames while the stream
// that wrote the file is still open (closed by a finally or a try-with-
// resources around the rename). Windows allows a rename or delete only when
// every open handle of the file was opened with FILE_SHARE_DELETE, and the
// JDK opens FileInputStream, FileOutputStream and RandomAccessFile without it
// (io_util_md.c winFileHandleOpen shares read and write only). Google Play
// services' Chimera module config failed "to rename pending to live config
// file" hundreds of times a run that way, its live file never written.
//
// The host binds the three classes' open0 to the same open with
// FILE_SHARE_DELETE added: everything else (the access, the disposition, the
// write-through and delete-on-close flags, the \\?\ form of a long path, the
// FileNotFoundException and its "<path> (<reason>)" message, the descriptor's
// handle and append fields) is the JDK's. java.nio's channels already share
// deletion (sun.nio.fs.WindowsChannelFactory), so java.io now matches them.

#include "java_io_open.h"

#include <windows.h>

#include <algorithm>
#include <cstdio>
#include <string>

namespace {

// java.io.RandomAccessFile's mode bits.
constexpr jint kRafReadOnly = 1;
constexpr jint kRafReadWrite = 2;
constexpr jint kRafSync = 4;
constexpr jint kRafDsync = 8;
constexpr jint kRafTemporary = 16;

jfieldID g_fis_fd = nullptr;
jfieldID g_fos_fd = nullptr;
jfieldID g_raf_fd = nullptr;
jfieldID g_fd_handle = nullptr;
jfieldID g_fd_append = nullptr;

std::wstring wide(JNIEnv* env, jstring s) {
    const jchar* c = env->GetStringChars(s, nullptr);
    if (!c) return {};
    std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return w;
}

// The host file a path names: an Android path ("/data/user/0/<package>/x",
// one leading '/') is the file the guest shim's table maps it to (paths.cpp);
// anything else is a host path already.
winapk::host::AndroidPathMapper g_path_mapper = nullptr;

std::wstring host_of(const std::wstring& p) {
    if (!g_path_mapper || p.empty() || p[0] != L'/' || (p.size() > 1 && p[1] == L'/')) return p;
    if (p.size() > 2 && p[2] == L':') return p.substr(1);  // "/C:/x", a file URL's path
    const int n = WideCharToMultiByte(CP_UTF8, 0, p.c_str(), static_cast<int>(p.size()), nullptr, 0, nullptr, nullptr);
    std::string u(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, p.c_str(), static_cast<int>(p.size()), &u[0], n, nullptr, nullptr);
    return g_path_mapper(u.c_str());
}

// jdk.internal.util.WinApkPaths.host0(String), Win.apk's java.base overlay:
// the host file an Android path names, for java.io.File's and java.nio's calls.
jstring JNICALL winapk_host_path(JNIEnv* env, jclass, jstring path) {
    if (!path) return nullptr;
    const std::wstring h = host_of(wide(env, path));
    return env->NewString(reinterpret_cast<const jchar*>(h.c_str()), static_cast<jsize>(h.size()));
}

// The JDK's pathToNTPath: a path near the Win32 limit is opened by the long-path
// (\\?\) form of its full path; a shorter one as given.
std::wstring win32_path(const std::wstring& p) {
    if (p.size() < MAX_PATH - 12) return p;
    const DWORD n = GetFullPathNameW(p.c_str(), 0, nullptr, nullptr);
    if (n == 0) return p;
    std::wstring full(n, L'\0');
    const DWORD m = GetFullPathNameW(p.c_str(), n, &full[0], nullptr);
    if (m == 0 || m >= n) return p;
    full.resize(m);
    if (full.rfind(L"\\\\?\\", 0) == 0) return full;
    if (full.rfind(L"\\\\", 0) == 0) return L"\\\\?\\UNC\\" + full.substr(2);
    return L"\\\\?\\" + full;
}

// The JDK's getLastErrorString: the system's message, without its final
// period and line break.
std::wstring error_text(DWORD error) {
    wchar_t* buf = nullptr;
    const DWORD n = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                                       FORMAT_MESSAGE_IGNORE_INSERTS,
                                   nullptr, error, 0, reinterpret_cast<wchar_t*>(&buf), 0, nullptr);
    std::wstring s = n && buf ? std::wstring(buf, n) : L"error " + std::to_wstring(error);
    if (buf) LocalFree(buf);
    while (!s.empty() && (s.back() == L'\n' || s.back() == L'\r' || s.back() == L' ')) s.pop_back();
    if (!s.empty() && s.back() == L'.') s.pop_back();
    return s;
}

void throw_not_found(JNIEnv* env, const std::wstring& path, DWORD error) {
    const std::wstring msg = path + L" (" + error_text(error) + L")";
    jclass cls = env->FindClass("java/io/FileNotFoundException");
    jmethodID ctor = cls ? env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V") : nullptr;
    jstring jmsg = env->NewString(reinterpret_cast<const jchar*>(msg.c_str()), static_cast<jsize>(msg.size()));
    if (!ctor || !jmsg) return;  // an exception is pending already
    if (auto* e = static_cast<jthrowable>(env->NewObject(cls, ctor, jmsg))) env->Throw(e);
}

void open_file(JNIEnv* env, jobject self, jfieldID fd_field, jstring path, DWORD access, DWORD disposition,
               DWORD flags, bool append) {
    if (!path) {
        jclass npe = env->FindClass("java/lang/NullPointerException");
        if (npe) env->ThrowNew(npe, nullptr);
        return;
    }
    const std::wstring p = wide(env, path);
    const HANDLE h = CreateFileW(win32_path(host_of(p)).c_str(), access,
                                 FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, disposition, flags, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        throw_not_found(env, p, GetLastError());
        return;
    }
    jobject fdo = env->GetObjectField(self, fd_field);
    if (!fdo) {
        CloseHandle(h);
        return;
    }
    env->SetLongField(fdo, g_fd_handle, static_cast<jlong>(reinterpret_cast<intptr_t>(h)));
    env->SetBooleanField(fdo, g_fd_append, append ? JNI_TRUE : JNI_FALSE);
}

// FileInputStream.open0(String): O_RDONLY.
void JNICALL fis_open(JNIEnv* env, jobject self, jstring path) {
    open_file(env, self, g_fis_fd, path, GENERIC_READ, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, false);
}

// FileOutputStream.open0(String, boolean append): O_WRONLY | O_CREAT and
// O_APPEND or O_TRUNC; O_TRUNC wins over O_CREAT, as in the JDK.
void JNICALL fos_open(JNIEnv* env, jobject self, jstring path, jboolean append) {
    open_file(env, self, g_fos_fd, path, GENERIC_WRITE, append ? OPEN_ALWAYS : CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL,
              append);
}

// RandomAccessFile.open0(String, int mode): "r" is O_RDONLY; "rw", "rws" and
// "rwd" O_RDWR | O_CREAT, the last two writing through.
void JNICALL raf_open(JNIEnv* env, jobject self, jstring path, jint mode) {
    DWORD access = GENERIC_READ, disposition = OPEN_EXISTING, flags = FILE_ATTRIBUTE_NORMAL;
    if (!(mode & kRafReadOnly) && (mode & kRafReadWrite)) {
        access = GENERIC_READ | GENERIC_WRITE;
        disposition = OPEN_ALWAYS;
        if (mode & (kRafSync | kRafDsync)) flags = FILE_FLAG_WRITE_THROUGH;
    }
    if (mode & kRafTemporary) flags |= FILE_FLAG_DELETE_ON_CLOSE;
    open_file(env, self, g_raf_fd, path, access, disposition, flags, false);
}

// java.io.WinNTFileSystem.winapkReplace(String from, String to), Win.apk's
// java.base overlay: rename(2) onto an existing file. A POSIX-semantics rename
// (Windows 10 1709 and later, NTFS) replaces the target even while another
// process has it open, as rename(2) does; MoveFileEx refuses that. 0, or the
// Windows error.
struct RenameInfoEx {
    DWORD flags;
    HANDLE root;
    DWORD length;
    wchar_t name[1];
};
constexpr int kFileRenameInfoEx = 22;  // FILE_INFO_BY_HANDLE_CLASS

jint JNICALL rename_replace(JNIEnv* env, jclass, jstring from, jstring to) {
    if (!from || !to) return ERROR_INVALID_PARAMETER;
    const std::wstring src = win32_path(host_of(wide(env, from)));
    std::wstring dst = host_of(wide(env, to));
    const DWORD n = GetFullPathNameW(dst.c_str(), 0, nullptr, nullptr);
    if (n == 0) return GetLastError();
    std::wstring full(n, L'\0');
    const DWORD m = GetFullPathNameW(dst.c_str(), n, &full[0], nullptr);
    if (m == 0 || m >= n) return GetLastError();
    full.resize(m);
    const HANDLE h = CreateFileW(src.c_str(), DELETE | SYNCHRONIZE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return GetLastError();
    const size_t bytes = sizeof(RenameInfoEx) + full.size() * sizeof(wchar_t);
    std::string buf(bytes, '\0');
    auto* info = reinterpret_cast<RenameInfoEx*>(&buf[0]);
    info->flags = FILE_RENAME_FLAG_REPLACE_IF_EXISTS | FILE_RENAME_FLAG_POSIX_SEMANTICS;
    info->root = nullptr;
    info->length = static_cast<DWORD>(full.size() * sizeof(wchar_t));
    std::copy(full.begin(), full.end(), info->name);
    DWORD error = 0;
    if (!SetFileInformationByHandle(h, static_cast<FILE_INFO_BY_HANDLE_CLASS>(kFileRenameInfoEx), info,
                                    static_cast<DWORD>(bytes))) {
        error = GetLastError();
    }
    CloseHandle(h);
    return static_cast<jint>(error);
}

bool bind(JNIEnv* env, const char* clazz, jfieldID* fd_field, const char* sig, void* fn) {
    jclass cls = env->FindClass(clazz);
    if (!cls) return false;
    *fd_field = env->GetFieldID(cls, "fd", "Ljava/io/FileDescriptor;");
    if (!*fd_field) return false;
    JNINativeMethod m{const_cast<char*>("open0"), const_cast<char*>(sig), fn};
    return env->RegisterNatives(cls, &m, 1) == JNI_OK;
}

}  // namespace

namespace winapk::host {

void java_io_set_path_mapper(AndroidPathMapper mapper) { g_path_mapper = mapper; }

bool java_io_open_init(JNIEnv* env) {
    // Android paths for java.io.File and java.nio (the overlay's WinApkPaths),
    // whatever the opens below do.
    if (jclass paths = env->FindClass("jdk/internal/util/WinApkPaths")) {
        JNINativeMethod m{const_cast<char*>("host0"), const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
                          reinterpret_cast<void*>(&winapk_host_path)};
        if (env->RegisterNatives(paths, &m, 1) != JNI_OK) env->ExceptionClear();
    } else {
        env->ExceptionClear();
    }
    // WINAPK_JAVA_IO_SHARE_DELETE=0 keeps the JDK's own opens, for comparing.
    wchar_t off[4] = {};
    if (GetEnvironmentVariableW(L"WINAPK_JAVA_IO_SHARE_DELETE", off, 4) && off[0] == L'0') return true;
    jclass fdc = env->FindClass("java/io/FileDescriptor");
    if (fdc) {
        g_fd_handle = env->GetFieldID(fdc, "handle", "J");
        g_fd_append = env->GetFieldID(fdc, "append", "Z");
    }
    const bool ok = g_fd_handle && g_fd_append &&
                    bind(env, "java/io/FileInputStream", &g_fis_fd, "(Ljava/lang/String;)V",
                         reinterpret_cast<void*>(&fis_open)) &&
                    bind(env, "java/io/FileOutputStream", &g_fos_fd, "(Ljava/lang/String;Z)V",
                         reinterpret_cast<void*>(&fos_open)) &&
                    bind(env, "java/io/RandomAccessFile", &g_raf_fd, "(Ljava/lang/String;I)V",
                         reinterpret_cast<void*>(&raf_open));
    if (!ok) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::fprintf(stderr, "[win.apk] cannot bind java.io's file opens; open files cannot be renamed or deleted\n");
    }
    // The overlay's replacing rename, where the overlay is there (a plain JDK
    // has no such method, and its renameTo then never replaces).
    if (jclass fs = env->FindClass("java/io/WinNTFileSystem")) {
        JNINativeMethod m{const_cast<char*>("winapkReplace"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)I"),
                          reinterpret_cast<void*>(&rename_replace)};
        if (env->RegisterNatives(fs, &m, 1) != JNI_OK) env->ExceptionClear();
    } else {
        env->ExceptionClear();
    }
    return ok;
}

}  // namespace winapk::host
