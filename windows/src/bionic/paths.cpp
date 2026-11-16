// paths.cpp -- where a guest path lives on the host.
//
// Guest code names files two ways: with the host paths Java hands it
// (getFilesDir(), nativeLibraryDir: "C:\...\android\data\...") and with
// Android's own absolute paths, hard-coded or built by hand ("/data/data/<pkg>/
// files", "/sdcard/...", "/system/fonts/Roboto-Regular.ttf"). On Windows a path
// that starts with '/' means the root of the current drive, which is never what
// the guest means. Upstream's shim (macapk_arm64_remap_path in
// runtime/translator/src/bionic_shim.cpp) solves the same problem the same way:
//
//   /system/etc/fonts.xml, /system/fonts/...  -> the fonts directory Boot wrote
//                                                (setSystemFontsDir)
//   /system/etc/security/cacerts,
//   /apex/com.android.conscrypt/cacerts       -> the CA store cacerts.cpp builds
//                                                from the roots Windows trusts
//   /dev/null                                 -> NUL
//   /proc/self/...                            -> /proc/<this process's pid>/...
//   any other /...                            -> <android root>/...
//
// The android root is the per-user folder the runtime mirrors Android's
// layout in (data/app, data/data/<pkg>, sdcard); the runtime sets it before
// any guest code runs.
//
// An Android file name may hold what a Windows one may not: < > : " | ? *, the
// control characters, a final dot or space (YouTube's per-account database is
// "<account id>||.entitystore"; every open of it failed). In the Android part
// of a mapped path each such character is stored as U+F000 + the character, a
// private-use code point: the mapping WSL's DrvFs and Cygwin use, so such a
// file reads right from them too. A listing of a directory named by an Android
// path turns the names back (android_name). A host path is never touched, nor
// the host prefix the table puts before the Android part; '\' stays a
// separator, since Java code joins Android paths with File.separator.

#include "bionic_internal.h"

#include <cstring>
#include <mutex>
#include <string>

#include <windows.h>

#include "winapk/mini_bionic.h"

namespace winapk::bionic {

namespace {

std::mutex g_lock;
std::wstring g_android_root;  // no trailing separator
std::wstring g_fonts_dir;
std::string g_app_package;     // the running app's package, for /data/user/0/<package>
std::wstring g_app_data_dir;   // its data directory on the host (set_app_data)

std::wstring to_wide(const char* utf8, size_t len) {
    if (len == 0) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, utf8, static_cast<int>(len), nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, static_cast<int>(len), &w[0], n);
    return w;
}

std::wstring trimmed(std::wstring w) {
    while (w.size() > 3 && (w.back() == L'\\' || w.back() == L'/')) w.pop_back();
    return w;
}

// Whether Windows refuses `c` in a file name wherever it stands.
bool forbidden(wchar_t c) {
    if (c < 0x20) return c != 0;
    return c == L'<' || c == L'>' || c == L':' || c == L'"' || c == L'|' || c == L'?' || c == L'*';
}
bool is_separator(wchar_t c) { return c == L'/' || c == L'\\'; }

// A final dot or space of the name that starts at `start` and ends at `end`
// (inclusive), which Windows drops; "." and ".." are the directory links.
template <typename Char>
bool dropped_ending(const Char* s, size_t start, size_t end) {
    if (s[end] == ' ') return true;
    if (s[end] != '.') return false;
    const size_t n = end + 1 - start;
    return !(n == 1 || (n == 2 && s[start] == '.'));
}

constexpr wchar_t kPrivate = 0xF000;  // DrvFs' and Cygwin's base for a forbidden character

// Whether any name in the Android path part `p` needs the mapping: the scan
// every path pays, so that a plain one costs nothing more.
bool needs_host_names(const char* p, size_t len) {
    size_t start = 0;  // of the name `i` is in
    for (size_t i = 0; i < len; ++i) {
        const unsigned char c = static_cast<unsigned char>(p[i]);
        if (c > '|') continue;  // UTF-8's bytes of other characters too
        if (is_separator(c)) {
            start = i + 1;
        } else if (forbidden(c)) {
            return true;
        } else if (i + 1 == len || is_separator(static_cast<unsigned char>(p[i + 1]))) {
            if (dropped_ending(p, start, i)) return true;
        }
    }
    return false;
}

// Guest code that knows no drive letters takes the host paths Java hands
// it ("C:\...") for relative ones and joins them onto getcwd() or "/":
// SQLite's unixFullPathname yields "C:\cwd/C:\data\x.db" or "/C:\data\x.db".
// The last drive-letter path after a separator is the one that was meant.
const char* meant_path(const char* p, size_t* len) {
    for (size_t i = *len; i-- > 2;) {
        if (p[i] == ':' && i + 1 < *len && (p[i + 1] == '\\' || p[i + 1] == '/') &&
            ((p[i - 1] >= 'A' && p[i - 1] <= 'Z') || (p[i - 1] >= 'a' && p[i - 1] <= 'z')) &&
            (p[i - 2] == '/' || p[i - 2] == '\\')) {
            *len -= i - 1;
            return p + i - 1;
        }
    }
    return p;
}

bool starts_with(const char* s, const char* prefix) { return std::strncmp(s, prefix, std::strlen(prefix)) == 0; }

// What follows Android's CA store directory in `p` ("" or "/<name>"), or null
// when `p` is not in it.
const char* cacerts_rest(const char* p) {
    for (const char* dir : {"/system/etc/security/cacerts", "/apex/com.android.conscrypt/cacerts"}) {
        const size_t n = std::strlen(dir);
        if (std::strncmp(p, dir, n) == 0 && (p[n] == 0 || p[n] == '/')) return p + n;
    }
    return nullptr;
}

}  // namespace

// The device's other spellings of two directories: the app's own data
// directory (/data/user/0/<package> and /data/data/<package>, which here is
// <android root>/data/data/<the app's folder>, not always named after the
// package), and primary shared storage (/storage/emulated/0, /sdcard and
// their aliases, all <android root>/sdcard). The rest of `p` after the match,
// or null. Caller holds g_lock.
const char* app_data_rest(const char* p) {
    if (g_app_package.empty() || g_app_data_dir.empty()) return nullptr;
    for (const char* dir : {"/data/user/0/", "/data/data/"}) {
        const size_t n = std::strlen(dir);
        if (std::strncmp(p, dir, n) != 0) continue;
        const char* pkg = p + n;
        const size_t m = g_app_package.size();
        if (std::strncmp(pkg, g_app_package.c_str(), m) == 0 && (pkg[m] == 0 || pkg[m] == '/')) return pkg + m;
    }
    return nullptr;
}
const char* storage_rest(const char* p) {
    for (const char* dir : {"/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard", "/sdcard"}) {
        const size_t n = std::strlen(dir);
        if (std::strncmp(p, dir, n) == 0 && (p[n] == 0 || p[n] == '/')) return p + n;
    }
    return nullptr;
}

// The Android part of a path (`p`, UTF-8: "/name/name", or names relative to
// an open directory) as it is on the host: the names Windows would refuse
// spelled with private-use characters, see the top of the file.
std::wstring host_names(const char* p, size_t len) {
    std::wstring w = to_wide(p, len);
    if (!needs_host_names(p, len)) return w;
    size_t start = 0;
    for (size_t i = 0; i < w.size(); ++i) {
        const wchar_t c = w[i];
        if (is_separator(c)) {
            start = i + 1;
            continue;
        }
        const bool last = i + 1 == w.size() || is_separator(w[i + 1]);
        if (forbidden(c) || (last && dropped_ending(w.c_str(), start, i))) w[i] = static_cast<wchar_t>(kPrivate + c);
    }
    return w;
}

// host_names' inverse for one listed name, in place (a mapped character is one
// UTF-16 unit either way). A private-use dot or space counts only at the end,
// where host_names writes it, so every name comes back exactly as it went.
void android_name(wchar_t* name) {
    for (wchar_t* c = name; *c; ++c) {
        if (*c <= kPrivate || *c > kPrivate + 0x7F) continue;
        const wchar_t plain = static_cast<wchar_t>(*c - kPrivate);
        if (forbidden(plain) || ((plain == L'.' || plain == L' ') && c[1] == 0)) *c = plain;
    }
}

// Whether host_path() reads `p` as an Android path (one leading '/'), whose
// names it maps, and not as a host path, which it leaves as it is.
bool is_android_path(const char* p) {
    if (!p) return false;
    size_t len = std::strlen(p);
    p = meant_path(p, &len);
    return p[0] == '/' && p[1] != '/';
}

std::wstring host_path(const char* p) {
    if (!p) return {};
    size_t len = std::strlen(p);
    p = meant_path(p, &len);
    // A host path (drive letter, UNC, relative): as it is.
    if (p[0] != '/' || p[1] == '/') return to_wide(p, len);
    if (std::strcmp(p, "/dev/null") == 0) return L"NUL";
    // /proc/self is the process's own directory, /proc/<pid>. Its files that are
    // not made on the fly (stdio.cpp: maps) are real ones under <android root>/
    // proc/<pid>, written by Boot for this process (cmdline), so one running app
    // does not read another's.
    std::string own_proc;
    if (std::strncmp(p, "/proc/self", 10) == 0 && (p[10] == '\0' || p[10] == '/')) {
        own_proc = "/proc/" + std::to_string(GetCurrentProcessId()) + (p + 10);
        p = own_proc.c_str();
        len = own_proc.size();
    }
    if (const char* rest = cacerts_rest(p)) {
        std::wstring root;
        {
            std::lock_guard<std::mutex> hold(g_lock);
            root = g_android_root;
        }
        const std::wstring dir = android_cacerts_dir(root);
        if (!dir.empty()) return dir + host_names(rest, std::strlen(rest));
    }

    std::lock_guard<std::mutex> hold(g_lock);
    if (!g_fonts_dir.empty()) {
        if (std::strcmp(p, "/system/etc/fonts.xml") == 0) return g_fonts_dir + L"\\fonts.xml";
        if (starts_with(p, "/system/fonts") && (p[13] == '\0' || p[13] == '/')) {
            return g_fonts_dir + host_names(p + 13, len - 13);
        }
    }
    if (g_android_root.empty()) return to_wide(p, len);
    if (const char* rest = app_data_rest(p)) return g_app_data_dir + host_names(rest, std::strlen(rest));
    if (const char* rest = storage_rest(p)) return g_android_root + L"\\sdcard" + host_names(rest, std::strlen(rest));
    if (std::strncmp(p, "/data/user/0", 12) == 0 && (p[12] == 0 || p[12] == '/')) {
        return g_android_root + L"\\data\\data" + host_names(p + 12, len - 12);
    }
    return g_android_root + host_names(p, len);
}

}  // namespace winapk::bionic

namespace winapk {

void set_android_root(const char* dir) {
    std::lock_guard<std::mutex> hold(bionic::g_lock);
    bionic::g_android_root = bionic::trimmed(bionic::to_wide(dir, dir ? std::strlen(dir) : 0));
}
void set_app_data(const char* package, const char* host_dir) {
    std::lock_guard<std::mutex> hold(bionic::g_lock);
    bionic::g_app_package = package ? package : "";
    bionic::g_app_data_dir = bionic::trimmed(bionic::to_wide(host_dir, host_dir ? std::strlen(host_dir) : 0));
}
std::wstring android_host_path(const char* path) { return bionic::host_path(path); }
std::wstring android_file_name(std::wstring host_name) {
    if (!host_name.empty()) bionic::android_name(&host_name[0]);
    return host_name;
}
void set_system_fonts_dir(const char* dir) {
    std::lock_guard<std::mutex> hold(bionic::g_lock);
    bionic::g_fonts_dir = bionic::trimmed(bionic::to_wide(dir, dir ? std::strlen(dir) : 0));
}

std::wstring android_proc_file(const char* path) {
    if (!path || std::strncmp(path, "/proc/", 6) != 0) return {};
    const std::wstring host = bionic::host_path(path);
    const DWORD attrs = GetFileAttributesW(host.c_str());
    if (attrs == INVALID_FILE_ATTRIBUTES || (attrs & FILE_ATTRIBUTE_DIRECTORY)) return {};
    return host;
}

}  // namespace winapk
