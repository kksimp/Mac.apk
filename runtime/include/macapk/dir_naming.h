// dir_naming.h -- the per-version folder name (GitHub #82). Header-only so the
// offline gate (debug/dirnaming_gate.sh) compiles the REAL code, not a copy.
//
// `<package>_<versionName with every "." as "_">`: com.cricfytv.sports_7_2, not
// com.cricfytv.sports_7.2. Apps that guard against a cloned package count the dots
// in getFilesDir().getPath() against the package's own; on a device that path never
// carries a version, so the version's dots made them call finishAffinity(). Mirrored
// in Swift by DirNaming (launcher/InstallCore.swift).
//
// Installs made before this carry the dotted spelling. ChooseDirName ADOPTS such an
// install instead of starting an empty one beside it (which would strand the saves in
// the dotted folder); the launcher's stub renames it (DirNameMigration) and from then
// on only the dot-free spelling exists.
#pragma once

#include <string>
#include <sys/stat.h>

namespace macapk_dirname {

// "1.2.3" -> "1_2_3".
inline std::string VersionPart(const std::string& ver) {
    std::string out = ver;
    for (char& c : out)
        if (c == '.') c = '_';
    return out;
}

// The dot-free name; the bare package when the version is unknown.
inline std::string Canonical(const std::string& pkg, const std::string& ver) {
    return ver.empty() ? pkg : pkg + "_" + VersionPart(ver);
}

// The pre-#82 name (the version as it is).
inline std::string Legacy(const std::string& pkg, const std::string& ver) {
    return ver.empty() ? pkg : pkg + "_" + ver;
}

// A real directory: lstat, so a compatibility LINK left at the old name by the
// migration (or by an in-place update) never counts as an install.
inline bool IsRealDir(const std::string& path) {
    struct stat st;
    return lstat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

// The name this install lands under. The dot-free one, unless the dotted one is
// already installed (a real code or data folder) and the dot-free one is not:
// then the dotted one, so the app keeps the saves it has. Anything at the dot-free
// name wins (a migrated install, or a fresh one).
inline std::string ChooseDirName(const std::string& pkg, const std::string& ver,
                                 const std::string& code_root,
                                 const std::string& data_root) {
    const std::string canon = Canonical(pkg, ver);
    const std::string legacy = Legacy(pkg, ver);
    if (legacy == canon) return canon;
    if (IsRealDir(code_root + "/" + canon) || IsRealDir(data_root + "/" + canon))
        return canon;
    if (IsRealDir(code_root + "/" + legacy) || IsRealDir(data_root + "/" + legacy))
        return legacy;
    return canon;
}

}  // namespace macapk_dirname
