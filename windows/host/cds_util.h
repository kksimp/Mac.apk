// cds_util.h -- the parts of class-data sharing (cds.h) that need no JVM:
// the patch digest, the archive's key, the class list merge. test/cds_test.cpp.

#pragma once

#include <filesystem>
#include <string>
#include <utility>
#include <vector>

namespace winapk::host {

namespace cds {

// SHA-256 (hex) of a patch directory, as bake_jre.sh writes it: for every
// file, by relative path in byte order, "<path>\n<size>\n" and then its bytes.
// "" when the directory is missing, empty or unreadable.
std::string patch_dir_digest(const std::filesystem::path& dir);

// "<module> <sha256>" pairs of a JRE's lib\winapk-baked-patches.
std::vector<std::pair<std::string, std::string>> read_baked(const std::filesystem::path& file);

// The directory's files' paths, sizes and times, from its listing alone (no
// file is opened), as 16 hex digits; "" when it cannot be listed.
std::string patch_dir_stamp(const std::filesystem::path& dir);

// Removes each --patch-module whose directory the JRE carries (same digest
// in `baked`). Returns the modules whose flag had to stay. With `cache`, a
// directory whose stamp is the one recorded there reuses its digest: hashing
// the ~80 files cost 16-20 ms of every start on Windows, listing them ~1 ms.
std::vector<std::string> drop_baked_patches(std::vector<std::string>* opts,
                                            const std::vector<std::pair<std::string, std::string>>& baked,
                                            const std::filesystem::path& cache = {});

// The archive's key: FNV-1a, as 16 hex digits, over `parts` in order.
std::string key_of(const std::vector<std::string>& parts);

// "<size>:<time>" of a file, "-" when it cannot be read.
std::string stamp(const std::filesystem::path& p);

// Options that differ from launch to launch and cannot decide whether an
// archive maps (-D..., -XX:ErrorFile=, -Xlog:...): left out of the key.
bool per_launch(const std::string& option);

// Class lists merged for -XX:SharedClassListFile: comments dropped, the
// " id: <n>" of HotSpot's format dropped (ids are per file), the classes of
// other loaders (lines with super:/source:) dropped, each line once, in order.
std::string merge_classlists(const std::vector<std::string>& lists);

}  // namespace cds

}  // namespace winapk::host
