// sqlite_paths.h -- the framework's SQLite opens Android paths (sqlite_paths.cpp).
#pragma once

namespace winapk::host {

// Makes SQLite's default VFS the Windows one with Android paths mapped to the
// host files they name. Once, before the first database opens.
void sqlite_android_paths_init();

}  // namespace winapk::host
