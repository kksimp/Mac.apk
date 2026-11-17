// user_folders.cpp -- see user_folders.h.
//
// The rules are upstream's (runtime/java/com/macapk/loader/SharedStorage.java,
// mapUserFolders, mergeInto, moveNoClobber, removeEmptyTree), per volume
// folder N with the user's folder T:
//   - N is already a junction to T: nothing to do;
//   - N is a real folder: what it holds MOVES into T first (a rename, never a
//     copy; a name T already has comes in as "name (from Android).ext"), and
//     only when N is then empty is it removed and replaced by the junction.
//     Anything that cannot move (another drive, a file an app holds open)
//     leaves N a real folder with what is left, so apps still find every file
//     at the same path, and a later launch finishes the job;
//   - N is absent: the junction is made.
// Upstream names the app in a collision ("from <package>"). The volume here is
// shared by every app, so the host cannot tell which one saved a file, and
// says Android instead.

#include "user_folders.h"

#include <cstdio>
#include <cstring>
#include <ctime>

#include <windows.h>
#include <shlobj.h>
#include <winioctl.h>

namespace winapk::host {

namespace {

namespace fs = std::filesystem;

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

void log(const std::string& msg) { std::fprintf(stderr, "[win.apk] user folders: %s\n", msg.c_str()); }

std::string u(const fs::path& p) { return utf8(p.wstring()); }

// The attributes of `p` itself, not of what a junction at `p` names.
DWORD attributes(const fs::path& p) { return GetFileAttributesW(p.c_str()); }

bool is_real_dir(DWORD a) {
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY) && !(a & FILE_ATTRIBUTE_REPARSE_POINT);
}

DWORD reparse_tag(const fs::path& p) {
    HANDLE h = CreateFileW(p.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (h == INVALID_HANDLE_VALUE) return 0;
    FILE_ATTRIBUTE_TAG_INFO info{};
    const bool ok = GetFileInformationByHandleEx(h, FileAttributeTagInfo, &info, sizeof info);
    CloseHandle(h);
    return ok && (info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) ? info.ReparseTag : 0;
}

// True when `a` and `b` (following links) are the same folder. By file id
// rather than by name: a known folder's path may itself go through a junction
// or differ in case from what the junction stores.
bool same_folder(const fs::path& a, const fs::path& b) {
    BY_HANDLE_FILE_INFORMATION ia{}, ib{};
    auto info = [](const fs::path& p, BY_HANDLE_FILE_INFORMATION* out) {
        HANDLE h = CreateFileW(p.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (h == INVALID_HANDLE_VALUE) return false;
        const bool ok = GetFileInformationByHandle(h, out);
        CloseHandle(h);
        return ok;
    };
    return info(a, &ia) && info(b, &ib) && ia.dwVolumeSerialNumber == ib.dwVolumeSerialNumber &&
           ia.nFileIndexHigh == ib.nFileIndexHigh && ia.nFileIndexLow == ib.nFileIndexLow;
}

// A junction can only name a folder on a local volume ("C:\..."), not a share.
bool local_path(const std::wstring& p) { return p.size() >= 3 && p[1] == L':' && (p[2] == L'\\' || p[2] == L'/'); }

// mklink /J: an empty folder with a mount-point reparse point. Unlike a
// symbolic link it needs no privilege. The buffer is REPARSE_DATA_BUFFER's
// MountPointReparseBuffer (ntifs.h, not in the user-mode headers).
bool make_junction(const fs::path& link, std::wstring target) {
    while (target.size() > 3 && (target.back() == L'\\' || target.back() == L'/')) target.pop_back();
    const std::wstring sub = L"\\??\\" + target;
    const size_t sub_bytes = sub.size() * sizeof(wchar_t), print_bytes = target.size() * sizeof(wchar_t);
    const size_t path_bytes = sub_bytes + sizeof(wchar_t) + print_bytes + sizeof(wchar_t);
    if (8 + path_bytes > 0xFFFF) return false;
    // Tag, data length, reserved; then the four name offsets and lengths; then the names with their NULs.
    std::vector<BYTE> buf(8 + 8 + path_bytes, 0);
    auto put = [&](size_t at, const void* v, size_t n) { std::memcpy(&buf[at], v, n); };
    auto put16 = [&](size_t at, size_t v) {
        const WORD w = static_cast<WORD>(v);
        put(at, &w, 2);
    };
    const DWORD tag = IO_REPARSE_TAG_MOUNT_POINT;
    put(0, &tag, 4);
    put16(4, 8 + path_bytes);
    put16(8, 0);
    put16(10, sub_bytes);
    put16(12, sub_bytes + sizeof(wchar_t));
    put16(14, print_bytes);
    put(16, sub.data(), sub_bytes);
    put(16 + sub_bytes + sizeof(wchar_t), target.data(), print_bytes);

    if (!CreateDirectoryW(link.c_str(), nullptr)) return false;
    HANDLE h = CreateFileW(link.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    DWORD got = 0;
    const bool ok = h != INVALID_HANDLE_VALUE && DeviceIoControl(h, FSCTL_SET_REPARSE_POINT, buf.data(),
                                                                 static_cast<DWORD>(buf.size()), nullptr, 0, &got,
                                                                 nullptr);
    const DWORD err = GetLastError();
    if (h != INVALID_HANDLE_VALUE) CloseHandle(h);
    if (!ok) {
        RemoveDirectoryW(link.c_str());
        SetLastError(err);
    }
    return ok;
}

bool list(const fs::path& dir, std::vector<WIN32_FIND_DATAW>* out) {
    WIN32_FIND_DATAW f;
    HANDLE h = FindFirstFileW((dir / L"*").c_str(), &f);
    if (h == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND;
    do {
        if (std::wcscmp(f.cFileName, L".") != 0 && std::wcscmp(f.cFileName, L"..") != 0) out->push_back(f);
    } while (FindNextFileW(h, &f));
    FindClose(h);
    return true;
}

void journal(const fs::path& file, const fs::path& from, const fs::path& to) {
    std::error_code ec;
    fs::create_directories(file.parent_path(), ec);
    FILE* out = _wfopen(file.c_str(), L"ab");
    if (!out) return;
    std::fprintf(out, "%lld\t%s\t%s\n", static_cast<long long>(std::time(nullptr)), u(from).c_str(), u(to).c_str());
    std::fclose(out);
}

// "IMG_1.jpg" -> "IMG_1 (from Android).jpg"; n > 1 adds " n". A leading dot
// (".nomedia") is part of the name, not an extension.
std::wstring collision_name(const std::wstring& name, int n) {
    const size_t dot = name.find_last_of(L'.');
    const bool ext = dot != std::wstring::npos && dot > 0;
    return (ext ? name.substr(0, dot) : name) + L" (from Android)" + (n > 1 ? L" " + std::to_wstring(n) : L"") +
           (ext ? name.substr(dot) : L"");
}

// Moves `k` into `dst` under its own name, or under a collision name when dst
// has that one. MoveFileEx without MOVEFILE_REPLACE_EXISTING never replaces,
// even a file created in the instant after the check, and without
// MOVEFILE_COPY_ALLOWED it fails across volumes instead of copying.
bool move_no_clobber(const fs::path& k, const std::wstring& name, const fs::path& dst, const fs::path& jfile) {
    for (int n = 0; n < 1000; ++n) {
        const fs::path to = dst / (n == 0 ? name : collision_name(name, n));
        if (attributes(to) != INVALID_FILE_ATTRIBUTES) continue;
        if (MoveFileExW(k.c_str(), to.c_str(), 0)) {
            journal(jfile, k, to);
            return true;
        }
        const DWORD err = GetLastError();
        if (err == ERROR_ALREADY_EXISTS || err == ERROR_FILE_EXISTS) continue;  // lost a race: next name
        log("could not move " + u(k) + " -> " + u(to) + " (error " + std::to_string(err) + ")");
        return false;
    }
    return false;
}

// Every entry of `src` into `dst`, a folder both have merged recursively.
// True when src is left holding only emptied folders.
bool merge_into(const fs::path& src, const fs::path& dst, const fs::path& jfile) {
    std::vector<WIN32_FIND_DATAW> kids;
    if (!list(src, &kids)) return false;
    bool all = true;
    for (const WIN32_FIND_DATAW& e : kids) {
        const fs::path k = src / e.cFileName, there = dst / e.cFileName;
        if (is_real_dir(e.dwFileAttributes) && is_real_dir(attributes(there))) {
            if (!merge_into(k, there, jfile)) all = false;
            continue;
        }
        if (!move_no_clobber(k, e.cFileName, dst, jfile)) all = false;
    }
    return all;
}

// Removes `dir` and its folders, deepest first, and no file: anything else
// left below (a file, a link) stops it with that part intact.
bool remove_empty_tree(const fs::path& dir) {
    std::vector<WIN32_FIND_DATAW> kids;
    if (!list(dir, &kids)) return false;
    for (const WIN32_FIND_DATAW& e : kids) {
        if (!is_real_dir(e.dwFileAttributes) || !remove_empty_tree(dir / e.cFileName)) return false;
    }
    return RemoveDirectoryW(dir.c_str()) != 0;
}

std::wstring known_folder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    std::wstring out;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) out = p;
    CoTaskMemFree(p);
    return out;
}

}  // namespace

std::vector<UserFolder> user_folders() {
    // Environment.DIRECTORY_* names on the left; Download is singular on Android.
    return {
        {L"Download", known_folder(FOLDERID_Downloads)}, {L"Pictures", known_folder(FOLDERID_Pictures)},
        {L"Documents", known_folder(FOLDERID_Documents)}, {L"Music", known_folder(FOLDERID_Music)},
        {L"Movies", known_folder(FOLDERID_Videos)},
    };
}

std::vector<UserFolder> map_user_folders(const fs::path& volume, const std::vector<UserFolder>& folders,
                                         const fs::path& journal_file, UserFolderMode mode) {
    // Two apps starting together would otherwise both merge the same folder.
    HANDLE lock = CreateMutexW(nullptr, FALSE, L"Local\\WinApkUserFolders");
    if (lock) WaitForSingleObject(lock, 30000);
    std::error_code ec;
    if (mode == UserFolderMode::kMap) fs::create_directories(volume, ec);

    std::vector<UserFolder> mapped;
    for (const UserFolder& f : folders) {
        if (f.target.empty()) continue;
        const fs::path link = volume / f.name;
        const DWORD a = attributes(link);
        const bool exists = a != INVALID_FILE_ATTRIBUTES;
        const bool reparse = exists && (a & FILE_ATTRIBUTE_REPARSE_POINT);
        const bool junction = reparse && reparse_tag(link) == IO_REPARSE_TAG_MOUNT_POINT;
        const bool ours = junction && same_folder(link, f.target);

        if (mode == UserFolderMode::kKeep) {
            if (ours) mapped.push_back(f);
            continue;
        }
        if (mode == UserFolderMode::kUnmap) {
            // Removing a junction removes the link only: every file stays in
            // the user's folder. Only one to exactly this folder is undone.
            if (!ours) continue;
            if (RemoveDirectoryW(link.c_str()) && CreateDirectoryW(link.c_str(), nullptr)) {
                log("unmapped " + u(link) + "; its files stay in " + u(f.target));
            } else {
                mapped.push_back(f);
            }
            continue;
        }

        if (ours) {
            mapped.push_back(f);
            continue;
        }
        if (!fs::is_directory(f.target, ec)) {
            log(u(f.target) + " missing; " + u(f.name) + " stays in the volume");
            continue;
        }
        if (!local_path(f.target)) {
            log(u(f.target) + " is not on a local drive; " + u(f.name) + " stays in the volume");
            continue;
        }
        if (junction) {
            // A junction to some other folder: one an earlier launch made before
            // the user moved the folder (Properties > Location), or to one that
            // is gone. The volume is Win.apk's own folder, and removing a
            // junction removes only the link, so it is pointed anew.
            if (!RemoveDirectoryW(link.c_str())) {
                log("could not replace the old junction " + u(link));
                continue;
            }
        } else if (reparse) {
            log("left alone: " + u(link) + " is a link Win.apk did not make");
            continue;
        } else if (exists && !(a & FILE_ATTRIBUTE_DIRECTORY)) {
            log("left alone: " + u(link) + " is not a folder");
            continue;
        } else if (exists) {
            if (!merge_into(link, f.target, journal_file)) {
                log(u(f.name) + ": not every file could move into " + u(f.target) +
                    "; it stays a real folder until a later launch finishes");
                continue;
            }
            if (!remove_empty_tree(link)) {
                log(u(f.name) + ": not empty after the move; left as a real folder");
                continue;
            }
        }
        if (make_junction(link, f.target)) {
            mapped.push_back(f);
            log("mapped " + u(link) + " -> " + u(f.target));
        } else {
            log("could not make " + u(link) + " a junction (error " + std::to_string(GetLastError()) + ")");
            CreateDirectoryW(link.c_str(), nullptr);  // the volume keeps the folder
        }
    }
    if (lock) {
        ReleaseMutex(lock);
        CloseHandle(lock);
    }
    return mapped;
}

std::wstring user_folder_env(const std::vector<UserFolder>& mapped) {
    // '|' cannot appear in a Windows path.
    std::wstring v;
    for (const UserFolder& f : mapped) {
        if (!v.empty()) v += L'|';
        v += f.name + L'=' + f.target;
    }
    return v;
}

}  // namespace winapk::host
