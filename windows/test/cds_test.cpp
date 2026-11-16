// cds_test.cpp -- winapk-cds-test: the pure parts of class-data sharing
// (host/cds_util.h): the patch digest the runtime compares with what
// windows/scripts/bake_jre.sh recorded, which --patch-module flags it drops,
// the framework archive's key, and the class list merge.
//
//   winapk-cds-test <scratch dir>
//
// The digest's expected value is bake_jre.sh's own recipe (find | sort |
// "<path>\n<size>\n<bytes>" | sha256sum) run over the same files, so the two
// sides cannot drift apart unnoticed. Exit code: the number of failed checks.

#include "cds_util.h"

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using namespace winapk::host::cds;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

void write(const fs::path& p, const std::string& bytes) {
    fs::create_directories(p.parent_path());
    std::ofstream(p, std::ios::binary) << bytes;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-cds-test <scratch dir>\n");
        return 2;
    }
    const fs::path root = fs::path(argv[1]) / "cds-test";
    std::error_code ec;
    fs::remove_all(root, ec);

    // A patch directory: nested, a '$' name (sorts before '.'), an empty file.
    const fs::path patch = root / "java_base_patch";
    write(patch / "java" / "lang" / "Thread.class", std::string("Thread bytes\n\x00\x01", 15));
    write(patch / "java" / "lang" / "Thread$1.class", "inner");
    write(patch / "java" / "util" / "Empty.class", "");
    write(patch / "a.class", "Z");
    const std::string want = "aa3ba285dc7c9d1e2bfa6e680f6a15076384d034d7905968676b933bf5a73f42";
    check(patch_dir_digest(patch) == want, "the digest is bake_jre.sh's");
    check(patch_dir_digest(root / "missing").empty(), "a missing directory has no digest");
    fs::create_directories(root / "empty");
    check(patch_dir_digest(root / "empty").empty(), "an empty directory has no digest");

    // The JRE's record, and the flags it lets go.
    write(root / "jre" / "lib" / "winapk-baked-patches",
          "# <module> <sha256 of the patch dir>\njava.base " + want + "\r\njdk.unsupported 00ff\n");
    const auto baked = read_baked(root / "jre" / "lib" / "winapk-baked-patches");
    check(baked.size() == 2 && baked[0].first == "java.base" && baked[0].second == want &&
              baked[1].first == "jdk.unsupported" && baked[1].second == "00ff",
          "the baked record is read, comments and CR left out");
    check(read_baked(root / "jre" / "nothing").empty(), "no record: nothing baked");

    const fs::path other = root / "jdk_unsupported_patch";
    write(other / "sun" / "misc" / "Unsafe.class", "unsafe");
    std::vector<std::string> opts = {"-Xrs", "--patch-module=java.base=" + patch.u8string(),
                                     "--patch-module=jdk.unsupported=" + other.u8string(), "-Xss16m"};
    auto blocked = drop_baked_patches(&opts, baked);
    check(blocked.size() == 1 && blocked[0] == "jdk.unsupported", "a patch that differs keeps its flag");
    check(opts.size() == 3 && opts[0] == "-Xrs" && opts[1].find("jdk.unsupported") != std::string::npos &&
              opts[2] == "-Xss16m",
          "the baked patch's flag goes, the rest stay in order");

    write(patch / "java" / "lang" / "Thread.class", "rebuilt");
    opts = {"--patch-module=java.base=" + patch.u8string()};
    blocked = drop_baked_patches(&opts, baked);
    check(blocked.size() == 1 && opts.size() == 1, "a patch rebuilt after the bake keeps its flag");
    opts = {"--patch-module=java.desktop=" + patch.u8string()};
    check(drop_baked_patches(&opts, baked).size() == 1 && opts.size() == 1, "a module the JRE did not bake stays");

    // The digest cache: a directory whose listing has not changed reuses the
    // digest recorded for it; any change in a file's size or time hashes again.
    write(patch / "java" / "lang" / "Thread.class", std::string("Thread bytes\n\x00\x01", 15));
    const fs::path cache = root / "patch-digests.txt";
    opts = {"--patch-module=java.base=" + patch.u8string()};
    check(drop_baked_patches(&opts, baked, cache).empty() && opts.empty(), "with a cache: the baked patch's flag goes");
    std::ifstream first(cache);
    std::string st, dig;
    first >> st >> dig;
    first.close();
    check(st == patch_dir_stamp(patch) && dig == want, "the cache records the directory's stamp and digest");
    // A cache that lies about this stamp is believed (that is the point: no
    // file is read), so the stamp must change with any change to a file.
    std::ofstream(cache, std::ios::trunc) << st << " 00ff\n";
    opts = {"--patch-module=java.base=" + patch.u8string()};
    check(drop_baked_patches(&opts, baked, cache).size() == 1, "the cached digest answers for an unchanged directory");
    write(patch / "java" / "lang" / "Thread.class", std::string("Thread bytes\n\x00\x02", 15));  // same size
    fs::last_write_time(patch / "java" / "lang" / "Thread.class",
                        fs::last_write_time(patch / "a.class") + std::chrono::seconds(5));
    check(patch_dir_stamp(patch) != st, "a file rewritten at the same size changes the stamp");
    opts = {"--patch-module=java.base=" + patch.u8string()};
    check(drop_baked_patches(&opts, baked, cache).size() == 1, "a changed directory is hashed again (and differs)");
    write(patch / "java" / "lang" / "Thread.class", std::string("Thread bytes\n\x00\x01", 15));
    opts = {"--patch-module=java.base=" + patch.u8string()};
    check(drop_baked_patches(&opts, baked, cache).empty(), "and the original content is baked again");

    // The key.
    check(key_of({"a", "bc"}) == "33b43574e9506a95", "the key is FNV-1a over the parts");
    check(key_of({"a", "bc"}) != key_of({"ab", "c"}), "parts are separated");
    check(key_of({"x"}) != key_of({"x", ""}), "an empty part still counts");
    check(stamp(root / "missing") == "-", "a missing file's stamp is '-'");
    const std::string s1 = stamp(other / "sun" / "misc" / "Unsafe.class");
    write(other / "sun" / "misc" / "Unsafe.class", "unsafe, longer");
    check(s1 != "-" && s1 != stamp(other / "sun" / "misc" / "Unsafe.class"), "a changed file changes its stamp");
    check(per_launch("-Dmacapk.bundle.apk=x") && per_launch("-XX:ErrorFile=x") && per_launch("-Xlog:cds"),
          "per-launch options are left out of the key");
    check(!per_launch("-Djava.class.path=a.jar") && !per_launch("-Xmx1g") && !per_launch("--add-opens=x"),
          "the class path and the JVM's flags are in it");

    // The class lists.
    const std::string a =
        "# NOTE: Do not modify this file.\n#\njava/lang/Object id: 0\r\nandroid/app/Activity id: 7\n"
        "@lambda-proxy java/lang/Thread run ()Ljava/lang/Runnable; id: 3\n"
        "com/app/Foo id: 9 super: 0 source: D:/apps/x/classes.jar\n";
    const std::string b = "java/lang/Object id: 4\nandroid/view/View id: 1\n";
    check(merge_classlists({a, b}) ==
              "java/lang/Object\nandroid/app/Activity\n@lambda-proxy java/lang/Thread run ()Ljava/lang/Runnable; "
              "id: 3\nandroid/view/View\n",
          "merged: ids and comments dropped, other loaders' classes left out, each class once, in order");
    check(merge_classlists({}).empty(), "nothing to merge is empty");

    fs::remove_all(root, ec);
    std::printf("%d failed\n", failures);
    return failures;
}
