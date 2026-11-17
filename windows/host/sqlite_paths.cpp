// sqlite_paths.cpp -- the framework's SQLite opens Android paths.
//
// An app names its databases by Android paths ("/data/user/0/<package>/
// databases/x.db", Context.getDatabasePath), which the framework's
// SQLiteDatabase hands to SQLite as they are (upstream's NativeSqlite,
// runtime/src/native_sqlite.cpp). SQLite's Windows VFS took "/data/..." for
// the root of the current drive and failed to open every database. SQLite
// turns a database name into a full path once (xFullPathname) and derives the
// journal and WAL names from that, so the default VFS here is the Windows one
// with only that step changed: an Android path becomes the host file the guest
// shim maps it to (paths.cpp).

#include "sqlite_paths.h"

#include "winapk/mini_bionic.h"

#include <sqlite3.h>
#include <windows.h>

#include <string>

namespace {

sqlite3_vfs g_vfs;
sqlite3_vfs* g_base = nullptr;

int full_pathname(sqlite3_vfs*, const char* name, int n, char* out) {
    std::string mapped;
    if (name && name[0] == '/' && name[1] != '/') {
        const std::wstring w = winapk::android_host_path(name);
        const int len = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), nullptr, 0, nullptr,
                                            nullptr);
        mapped.resize(static_cast<size_t>(len));
        WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), &mapped[0], len, nullptr, nullptr);
        name = mapped.c_str();
    }
    return g_base->xFullPathname(g_base, name, n, out);
}

}  // namespace

namespace winapk::host {

void sqlite_android_paths_init() {
    if (g_base) return;
    g_base = sqlite3_vfs_find(nullptr);
    if (!g_base) return;
    g_vfs = *g_base;
    g_vfs.zName = "winapk-android-paths";
    g_vfs.xFullPathname = full_pathname;
    sqlite3_vfs_register(&g_vfs, 1);
}

}  // namespace winapk::host
