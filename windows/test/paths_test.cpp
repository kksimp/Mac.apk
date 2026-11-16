// paths_test.cpp -- winapk-paths-test: the guest path mapper (src/bionic/paths.cpp,
// winapk::android_host_path), which turns an Android path ("/data/user/0/<pkg>/x",
// "/storage/emulated/0/y") into the host file it names. This is the one table the
// Bionic shim, the framework's SQLite VFS, java.io and java.nio all go by, so a
// regression here moves every app's files. Also the names Windows forbids
// ("a|b", "name."), stored with private-use characters and listed back as they
// were, by the table alone and by the shim's own calls on real files.
//
// Exit code: the number of failed checks.

#include "winapk/mini_bionic.h"

#include "winapk/guest_abi.h"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <iterator>
#include <set>
#include <string>

#include <windows.h>

namespace {

int g_failed = 0;

// Compare with separators folded, since the mapper keeps the '/' of the matched
// tail ("<root>\\sdcard/Download") and Windows reads either slash.
std::wstring fold(std::wstring s) {
    for (wchar_t& c : s)
        if (c == L'/') c = L'\\';
    return s;
}

void check(const char* what, const std::wstring& got, const std::wstring& want) {
    if (fold(got) == fold(want)) return;
    ++g_failed;
    std::fprintf(stderr, "FAIL %s: got \"%ls\" want \"%ls\"\n", what, got.c_str(), want.c_str());
}

std::wstring host(const char* p) { return winapk::android_host_path(p); }

// The shim's own calls on real files, under a scratch Android root in %TEMP%:
// a file whose name Windows forbids is created, found on disk under its
// private-use name, listed by its Android name (by path and by descriptor),
// opened again through the directory's descriptor, and removed.
struct Dirent {  // Bionic's struct dirent (the same on arm64)
    uint64_t d_ino;
    int64_t d_off;
    uint16_t d_reclen;
    uint8_t d_type;
    char d_name[256];
};
template <typename Fn>
Fn shim(const char* name) {
    return reinterpret_cast<Fn>(winapk::mini_bionic_resolve(name));
}

void listing_round_trip() {
    const auto g_mkdir = shim<int (WINAPK_GUEST_ABI*)(const char*, unsigned)>("mkdir");
    const auto g_open = shim<int (WINAPK_GUEST_ABI*)(const char*, int, unsigned)>("open");
    const auto g_openat = shim<int (WINAPK_GUEST_ABI*)(int, const char*, int, unsigned)>("openat");
    const auto g_close = shim<int (WINAPK_GUEST_ABI*)(int)>("close");
    const auto g_unlink = shim<int (WINAPK_GUEST_ABI*)(const char*)>("unlink");
    const auto g_rmdir = shim<int (WINAPK_GUEST_ABI*)(const char*)>("rmdir");
    const auto g_opendir = shim<void* (WINAPK_GUEST_ABI*)(const char*)>("opendir");
    const auto g_fdopendir = shim<void* (WINAPK_GUEST_ABI*)(int)>("fdopendir");
    const auto g_readdir = shim<Dirent* (WINAPK_GUEST_ABI*)(void*)>("readdir");
    const auto g_closedir = shim<int (WINAPK_GUEST_ABI*)(void*)>("closedir");

    wchar_t temp[MAX_PATH];
    GetTempPathW(MAX_PATH, temp);
    const std::wstring scratch = std::wstring(temp) + L"winapk-paths-test-" + std::to_wstring(GetCurrentProcessId());
    CreateDirectoryW(scratch.c_str(), nullptr);
    char utf8[MAX_PATH * 3];
    WideCharToMultiByte(CP_UTF8, 0, scratch.c_str(), -1, utf8, sizeof utf8, nullptr, nullptr);
    winapk::set_android_root(utf8);

    const char* names[] = {"a|b", "107904171429698120635||.entitystore", "name.", "name ", "a:b", "plain.txt"};
    const auto listed_all = [&](const char* what, void* dir) {
        std::set<std::string> found;
        while (dir) {
            const Dirent* e = g_readdir(dir);
            if (!e) break;
            found.insert(e->d_name);
        }
        if (dir) g_closedir(dir);
        for (const char* name : names) {
            if (found.count(name)) continue;
            ++g_failed;
            std::fprintf(stderr, "FAIL %s: \"%s\" is not listed\n", what, name);
        }
        if (found.size() != std::size(names) + 2) {  // "." and ".."
            ++g_failed;
            std::fprintf(stderr, "FAIL %s: %zu entries listed\n", what, found.size());
        }
    };

    if (g_mkdir("/names", 0777) != 0) {
        ++g_failed;
        std::fprintf(stderr, "FAIL mkdir /names\n");
    }
    for (const char* name : names) {
        const std::string path = std::string("/names/") + name;
        const int fd = g_open(path.c_str(), 0101 /* O_WRONLY | O_CREAT */, 0600);
        if (fd < 0) {
            ++g_failed;
            std::fprintf(stderr, "FAIL create %s\n", path.c_str());
            continue;
        }
        g_close(fd);
        // The file is there under the name the mapper says, and a listing of the host directory finds it.
        if (GetFileAttributesW(host(path.c_str()).c_str()) == INVALID_FILE_ATTRIBUTES) {
            ++g_failed;
            std::fprintf(stderr, "FAIL %s: no such host file\n", path.c_str());
        }
    }
    listed_all("opendir", g_opendir("/names"));
    const int dirfd = g_open("/names", 0200000 /* O_DIRECTORY */, 0);
    for (const char* name : names) {
        const int fd = g_openat(dirfd, name, 0 /* O_RDONLY */, 0);
        if (fd < 0) {
            ++g_failed;
            std::fprintf(stderr, "FAIL openat %s\n", name);
        } else {
            g_close(fd);
        }
    }
    listed_all("fdopendir", g_fdopendir(dirfd));  // closes dirfd
    for (const char* name : names) {
        if (g_unlink((std::string("/names/") + name).c_str()) != 0) {
            ++g_failed;
            std::fprintf(stderr, "FAIL unlink %s\n", name);
        }
    }
    if (g_rmdir("/names") != 0 || !RemoveDirectoryW(scratch.c_str())) {
        ++g_failed;
        std::fprintf(stderr, "FAIL the scratch directory is not empty after the unlinks\n");
    }
}

}  // namespace

int main() {
    const char* root = "C:\\root";
    const char* app_host = "C:\\root\\data\\data\\sub-8.3";  // the real folder, not named after the package
    winapk::set_android_root(root);
    winapk::set_app_data("com.example.app", app_host);

    // The app's own data directory, by both device spellings.
    check("data/user/0", host("/data/user/0/com.example.app/files/x.db"),
          L"C:\\root\\data\\data\\sub-8.3\\files\\x.db");
    check("data/data", host("/data/data/com.example.app/databases/y.db"),
          L"C:\\root\\data\\data\\sub-8.3\\databases\\y.db");
    check("data/user/0 root only", host("/data/user/0/com.example.app"),
          L"C:\\root\\data\\data\\sub-8.3");

    // Primary shared storage, by every device spelling.
    check("storage/emulated/0", host("/storage/emulated/0/Download/a.bin"),
          L"C:\\root\\sdcard\\Download\\a.bin");
    check("sdcard", host("/sdcard/DCIM/p.jpg"), L"C:\\root\\sdcard\\DCIM\\p.jpg");
    check("storage/self/primary", host("/storage/self/primary/m.txt"),
          L"C:\\root\\sdcard\\m.txt");
    check("mnt/sdcard", host("/mnt/sdcard/n.txt"), L"C:\\root\\sdcard\\n.txt");

    // Another package's data (no device would allow it): it is NOT the app's own
    // folder, so it rebases under the root as the generic rule, not app_data.
    check("other package data", host("/data/data/com.other/z"),
          L"C:\\root\\data\\data\\com.other\\z");
    // Device-protected storage sibling (StorageContext).
    check("data/user_de", host("/data/user_de/0/com.example.app/files"),
          L"C:\\root\\data\\user_de\\0\\com.example.app\\files");

    // /dev/null is Windows' NUL.
    check("dev/null", host("/dev/null"), L"NUL");

    // A file URL's path ("/C:/x", /D:/...) is the drive path, not an Android one;
    // the drive-letter scan strips the leading slash.
    check("file url drive path", host("/C:/a/b.jar"), L"C:/a/b.jar");
    check("file url drive path D", host("/D:/x/y"), L"D:/x/y");

    // A host path (drive letter, UNC, relative) passes through unchanged.
    check("host drive path", host("C:\\Users\\x\\file"), L"C:\\Users\\x\\file");
    check("host unc", host("\\\\server\\share\\f"), L"\\\\server\\share\\f");
    check("relative", host("files/sub"), L"files/sub");
    check("empty", host(""), L"");

    // SQLite's unixFullPathname concatenation ("<cwd>/C:\\x" or "/C:\\x"): the
    // last drive-letter path after a separator is the one meant.
    check("concatenated cwd + drive", host("C:\\cwd/C:\\data\\x.db"), L"C:\\data\\x.db");
    check("leading slash + drive", host("/C:\\data\\x.db"), L"C:\\data\\x.db");

    // A generic Android absolute path with no special prefix nests under the root.
    check("generic /opt", host("/opt/thing"), L"C:\\root\\opt\\thing");

    // Names Windows forbids: in the Android part each such character is stored
    // as U+F000 + the character (WSL's and Cygwin's mapping), and a listing
    // turns the name back (android_file_name).
    const std::wstring db = L"C:\\root\\data\\data\\sub-8.3\\databases\\";
    check("pipe", host("/data/user/0/com.example.app/databases/a|b"), db + L"a\xF07C" L"b");
    check("entitystore", host("/data/user/0/com.example.app/databases/107904171429698120635||.entitystore"),
          db + L"107904171429698120635\xF07C\xF07C.entitystore");
    check("final dot", host("/data/user/0/com.example.app/databases/name."), db + L"name\xF02E");
    check("final space", host("/data/user/0/com.example.app/databases/name "), db + L"name\xF020");
    check("only the last dot", host("/sdcard/name../x"), L"C:\\root\\sdcard\\name.\xF02E\\x");
    check("inner dot and space", host("/sdcard/a. b.txt"), L"C:\\root\\sdcard\\a. b.txt");
    check("colon", host("/sdcard/a:b/c"), L"C:\\root\\sdcard\\a\xF03A" L"b\\c");
    check("the rest", host("/opt/<>\"?*\x01\x1F"), L"C:\\root\\opt\\\xF03C\xF03E\xF022\xF03F\xF02A\xF001\xF01F");
    check("dot", host("/sdcard/./x"), L"C:\\root\\sdcard\\.\\x");
    check("dot dot", host("/sdcard/a/.."), L"C:\\root\\sdcard\\a\\..");
    check("three dots", host("/sdcard/..."), L"C:\\root\\sdcard\\..\xF02E");
    check("plain name", host("/sdcard/plain-name_1.txt"), L"C:\\root\\sdcard\\plain-name_1.txt");
    check("non-ASCII name", host("/sdcard/\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D|"),
          L"C:\\root\\sdcard\\\x05E9\x05DC\x05D5\x05DD\xF07C");
    // '\' separates, as '/' does: Java joins Android paths with File.separator.
    check("backslash separator", host("/sdcard\\a|\\b."), L"C:\\root\\sdcard\\a\xF07C\\b\xF02E");
    // Only the Android part: a host path keeps its drive colon and everything
    // else, whatever it holds.
    check("host path with forbidden", host("C:\\dir\\a|b."), L"C:\\dir\\a|b.");
    check("file url drive path, forbidden", host("/C:/a/b|c"), L"C:/a/b|c");
    check("relative with forbidden", host("a|b"), L"a|b");

    // The round trip a listing makes: the host name of an Android name, turned back.
    // (A name that holds U+F07C itself is taken for '|': the one kind that does not survive.)
    for (const char* name : {"a|b", "x||.entitystore", "name.", "name ", "a:b", "plain.txt", "...", " ", "<>\"?*\x01",
                             "a\xEF\x81\xBC" "b"}) {
        const std::wstring mapped = host((std::string("/sdcard/") + name).c_str());
        const std::wstring listed = winapk::android_file_name(mapped.substr(mapped.find_last_of(L"\\/") + 1));
        const std::wstring direct = host((std::string("C:\\") + name).c_str()).substr(3);  // the name in UTF-16
        const bool survives = std::string(name).find("\xEF\x81\xBC") == std::string::npos;
        check(name, listed, survives ? direct : L"a|b");
        if (survives && mapped.find_first_of(L"<>\"|?*", 2) != std::wstring::npos) {
            ++g_failed;
            std::fprintf(stderr, "FAIL %s: the host name keeps a character Windows forbids\n", name);
        }
    }
    // A private-use dot or space inside a name is not one this mapping wrote: it stays.
    check("inner private dot", winapk::android_file_name(L"a\xF02E" L"b"), L"a\xF02E" L"b");
    check("listed plain", winapk::android_file_name(L"plain.txt"), L"plain.txt");
    check("listed dot dot", winapk::android_file_name(L".."), L"..");

    listing_round_trip();

    if (g_failed == 0) std::fprintf(stderr, "paths_test: all checks passed\n");

    // Microbenchmark: the native mapping cost per file op (what the Bionic shim,
    // the SQLite VFS and java.io's host0 each pay). This is the cost the path
    // feature ADDS on top of the Win32/host syscall a file op makes anyway.
    {
        const int N = 1000000;
        volatile size_t sink = 0;
        const char* android = "/data/user/0/com.example.app/files/sub/thing.db";
        const char* hostp = "C:\\root\\data\\data\\sub-8.3\\files\\sub\\thing.db";
        auto bench = [&](const char* p) {
            auto t0 = std::chrono::steady_clock::now();
            for (int i = 0; i < N; i++) sink += winapk::android_host_path(p).size();
            auto t1 = std::chrono::steady_clock::now();
            return std::chrono::duration<double, std::nano>(t1 - t0).count() / N;
        };
        const double a = bench(android), h = bench(hostp);
        std::fprintf(stderr, "paths_test bench (x%d): android_host_path Android=%.1f ns/op, host passthrough=%.1f ns/op (sink=%zu)\n",
                     N, a, h, (size_t)sink);
    }
    return g_failed;
}
