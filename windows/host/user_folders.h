// user_folders.h -- the phone's shared folders are the user's own Windows folders.
//
// Download, Pictures, Documents, Music and Movies at the top of the Android
// volume (<data>\android\sdcard) become directory junctions to the user's
// Downloads, Pictures, Documents, Music and Videos, so a file an app saves to
// /sdcard/Download is in Windows' Downloads and the other way round. This is
// upstream's SharedStorage.mapUserFolders (symlinks on the Mac) done by the
// host before the JVM starts: a symlink needs Developer Mode or an
// administrator on Windows, a junction needs neither.
//
// Java sees a junction as a plain directory (Files.isSymbolicLink is false),
// so the host tells it which folders it mapped, in WINAPK_USER_FOLDER_TARGETS
// ("Download=C:\Users\me\Downloads|Pictures=..."); SharedStorage recognises
// the junctions from that, and MediaStore and StorageStatsManager then treat
// them as the mapping links they are.

#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace winapk::host {

struct UserFolder {
    std::wstring name;    // the folder in the volume: "Download"
    std::wstring target;  // the user's folder: "C:\Users\me\Downloads"; empty when Windows has none
};

// The five folders, with where Windows keeps each for this user (a known
// folder may be redirected, to OneDrive or another drive). DCIM is not mapped,
// as upstream: Windows has no camera-roll folder for it to be.
std::vector<UserFolder> user_folders();

enum class UserFolderMode {
    kMap,    // make the junctions, moving what the volume folders hold into the user's folders
    kKeep,   // change nothing (MACAPK_USER_FOLDERS=0): only report the junctions there are
    kUnmap,  // the Settings switch is off: each of our junctions becomes an empty folder again
};

// Applies `mode` to `folders` under `volume` and returns the folders that are
// junctions to their targets afterwards. Every file moved is logged to
// `journal` (time, from, to; tab-separated, as upstream's
// usermap-journal.tsv). Never replaces, deletes or renames a file in a target.
std::vector<UserFolder> map_user_folders(const std::filesystem::path& volume, const std::vector<UserFolder>& folders,
                                         const std::filesystem::path& journal, UserFolderMode mode);

// WINAPK_USER_FOLDER_TARGETS's value for `mapped`.
std::wstring user_folder_env(const std::vector<UserFolder>& mapped);

}  // namespace winapk::host
