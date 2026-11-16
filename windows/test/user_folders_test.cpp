// user_folders_test.cpp -- winapk-user-folders-test: the shared-folder mapping
// (host/user_folders.h) on a scratch volume, never the user's real folders.
//
//   winapk-user-folders-test <scratch dir>
//
// Checks: a volume folder with files becomes a junction to its target, every
// file moved in, a name the target already has coming in under a collision
// name and the target's own file untouched; a second run changes nothing; a
// file locked open keeps its folder real; kKeep only reports; kUnmap leaves an
// empty folder and every file in the target.

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include <windows.h>

#include "../host/user_folders.h"

namespace fs = std::filesystem;
namespace uf = winapk::host;

namespace {

int g_failures = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

void write(const fs::path& p, const std::string& text) {
    fs::create_directories(p.parent_path());
    std::ofstream(p, std::ios::binary) << text;
}

std::string read(const fs::path& p) {
    std::ifstream f(p, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}

bool is_junction(const fs::path& p) {
    const DWORD a = GetFileAttributesW(p.c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_REPARSE_POINT) && (a & FILE_ATTRIBUTE_DIRECTORY);
}

bool empty_dir(const fs::path& p) {
    std::error_code ec;
    return !is_junction(p) && fs::is_directory(p, ec) && fs::is_empty(p, ec);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-user-folders-test <scratch dir>\n");
        return 2;
    }
    const fs::path root = fs::absolute(argv[1]) / "user-folders";
    std::error_code ec;
    fs::remove_all(root, ec);
    const fs::path vol = root / "sdcard", home = root / "home", journal = root / "usermap-journal.tsv";
    const std::vector<uf::UserFolder> folders = {
        {L"Download", (home / "Downloads").wstring()},
        {L"Pictures", (home / "Pictures").wstring()},
        {L"Music", (home / "Music").wstring()},
        {L"Movies", (home / "Missing").wstring()},  // no such folder: stays in the volume
    };
    write(home / "Downloads" / "same.txt", "user");
    write(home / "Downloads" / "nested" / "keep.txt", "user nested");
    write(home / "Pictures" / "p.jpg", "jpg");
    fs::create_directories(home / "Music");
    write(vol / "Download" / "same.txt", "app");
    write(vol / "Download" / "new.txt", "app new");
    write(vol / "Download" / "nested" / "keep.txt", "app nested");
    write(vol / "Download" / "nested" / "deep" / "x.bin", "x");
    write(vol / "Music" / "locked.mp3", "mp3");
    fs::create_directories(vol / "Movies");

    std::printf("map\n");
    // An app holding a file open without FILE_SHARE_DELETE: it cannot move.
    HANDLE lock = CreateFileW((vol / "Music" / "locked.mp3").c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                              OPEN_EXISTING, 0, nullptr);
    auto mapped = uf::map_user_folders(vol, folders, journal, uf::UserFolderMode::kMap);
    CloseHandle(lock);
    check("Download and Pictures mapped", mapped.size() == 2 && mapped[0].name == L"Download" &&
                                              mapped[1].name == L"Pictures");
    check("Download is a junction", is_junction(vol / "Download"));
    check("Pictures was absent and is a junction", is_junction(vol / "Pictures"));
    check("the user's file is untouched", read(home / "Downloads" / "same.txt") == "user");
    check("the app's same-named file came in under a collision name",
          read(home / "Downloads" / "same (from Android).txt") == "app");
    check("a new file moved in", read(home / "Downloads" / "new.txt") == "app new");
    check("a folder both have merged, the user's copy kept",
          read(home / "Downloads" / "nested" / "keep.txt") == "user nested" &&
              read(home / "Downloads" / "nested" / "keep (from Android).txt") == "app nested");
    check("a folder only the volume had moved whole", read(home / "Downloads" / "nested" / "deep" / "x.bin") == "x");
    check("the volume reaches the files through the junction", read(vol / "Download" / "new.txt") == "app new");
    check("the user's folder shows through", read(vol / "Pictures" / "p.jpg") == "jpg");
    check("a locked file keeps its folder real", !is_junction(vol / "Music") &&
                                                     read(vol / "Music" / "locked.mp3") == "mp3");
    check("a missing target leaves the folder alone", empty_dir(vol / "Movies"));
    const std::string j = read(journal);
    check("every move is in the journal", j.find("new.txt") != std::string::npos &&
                                              j.find("same (from Android).txt") != std::string::npos);
    check("the environment line", uf::user_folder_env(mapped) == L"Download=" + (home / "Downloads").wstring() +
                                                                    L"|Pictures=" + (home / "Pictures").wstring());

    std::printf("map again\n");
    mapped = uf::map_user_folders(vol, folders, journal, uf::UserFolderMode::kMap);
    check("the locked file's folder finishes now", mapped.size() == 3 && is_junction(vol / "Music") &&
                                                       read(home / "Music" / "locked.mp3") == "mp3");
    const size_t before = read(journal).size();
    mapped = uf::map_user_folders(vol, folders, journal, uf::UserFolderMode::kMap);
    check("a third run moves nothing", mapped.size() == 3 && read(journal).size() == before &&
                                           !fs::exists(home / "Downloads" / "same (from Android) 2.txt"));

    std::printf("keep\n");
    mapped = uf::map_user_folders(vol, folders, journal, uf::UserFolderMode::kKeep);
    check("kKeep reports the junctions", mapped.size() == 3);

    std::printf("unmap\n");
    mapped = uf::map_user_folders(vol, folders, journal, uf::UserFolderMode::kUnmap);
    check("nothing is mapped", mapped.empty());
    check("each folder is empty again", empty_dir(vol / "Download") && empty_dir(vol / "Pictures") &&
                                            empty_dir(vol / "Music"));
    check("every file stays in the user's folders", read(home / "Downloads" / "new.txt") == "app new" &&
                                                        read(home / "Pictures" / "p.jpg") == "jpg" &&
                                                        read(home / "Downloads" / "same.txt") == "user");

    fs::remove_all(root, ec);
    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
