// cds_util.cpp -- see cds_util.h.

#include "cds_util.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <unordered_set>

#include <windows.h>
#include <bcrypt.h>

namespace fs = std::filesystem;

namespace winapk::host::cds {

namespace {

bool starts(const std::string& s, const char* prefix) { return s.compare(0, std::strlen(prefix), prefix) == 0; }

struct Listed {
    std::string rel;  // relative path, '/' separated, UTF-8
    uint64_t size = 0, time = 0;
};

std::string utf8_of(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &out[0], n, nullptr, nullptr);
    return out;
}

// Every file under `dir` with its size and time, straight from
// FindFirstFileEx: no file is opened and nothing is asked twice
// (std::filesystem's iterator plus relative() cost ~10 ms on ~80 files).
// False when `dir` cannot be listed.
bool list_files(const std::wstring& dir, const std::string& prefix, std::vector<Listed>* out) {
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileExW((dir + L"\\*").c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr,
                                FIND_FIRST_EX_LARGE_FETCH);
    if (h == INVALID_HANDLE_VALUE) return false;
    bool ok = true;
    do {
        const std::wstring name = fd.cFileName;
        if (name == L"." || name == L"..") continue;
        const std::string rel = prefix + utf8_of(name);
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            ok = list_files(dir + L"\\" + name, rel + "/", out) && ok;
        } else {
            out->push_back({rel, (static_cast<uint64_t>(fd.nFileSizeHigh) << 32) | fd.nFileSizeLow,
                            (static_cast<uint64_t>(fd.ftLastWriteTime.dwHighDateTime) << 32) |
                                fd.ftLastWriteTime.dwLowDateTime});
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return ok;
}

std::vector<Listed> listing(const fs::path& dir) {
    std::vector<Listed> files;
    if (!list_files(dir.wstring(), "", &files)) files.clear();
    std::sort(files.begin(), files.end(), [](const Listed& a, const Listed& b) { return a.rel < b.rel; });
    return files;  // byte order of the paths, as LC_ALL=C sort
}

}  // namespace

std::string patch_dir_digest(const fs::path& dir) {
    const std::vector<Listed> files = listing(dir);
    if (files.empty()) return {};
    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return {};
    std::string out;
    if (BCryptCreateHash(alg, &hash, nullptr, 0, nullptr, 0, 0) == 0) {
        bool ok = true;
        std::vector<char> buf(1 << 16);
        for (const Listed& f : files) {
            std::ifstream in(dir / fs::u8path(f.rel), std::ios::binary);
            const std::string head = f.rel + "\n" + std::to_string(f.size) + "\n";
            ok = ok && in &&
                 BCryptHashData(hash, reinterpret_cast<PUCHAR>(const_cast<char*>(head.data())),
                                static_cast<ULONG>(head.size()), 0) == 0;
            while (ok && (in.read(buf.data(), static_cast<std::streamsize>(buf.size())) || in.gcount() > 0))
                ok = BCryptHashData(hash, reinterpret_cast<PUCHAR>(buf.data()), static_cast<ULONG>(in.gcount()), 0) == 0;
        }
        unsigned char md[32];
        if (ok && BCryptFinishHash(hash, md, sizeof md, 0) == 0) {
            char two[3];
            for (unsigned char b : md) {
                std::snprintf(two, sizeof two, "%02x", b);
                out += two;
            }
        }
        BCryptDestroyHash(hash);
    }
    BCryptCloseAlgorithmProvider(alg, 0);
    return out;
}

std::vector<std::pair<std::string, std::string>> read_baked(const fs::path& file) {
    std::vector<std::pair<std::string, std::string>> out;
    std::ifstream in(file);
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream words(line);
        std::string mod, dig;
        if (words >> mod >> dig) out.emplace_back(mod, dig);
    }
    return out;
}

std::string patch_dir_stamp(const fs::path& dir) {
    const std::vector<Listed> files = listing(dir);
    if (files.empty()) return {};
    std::error_code ec;
    std::vector<std::string> parts = {fs::absolute(dir, ec).generic_u8string()};
    for (const Listed& f : files) parts.push_back(f.rel + "|" + std::to_string(f.size) + "|" + std::to_string(f.time));
    return key_of(parts);
}

std::vector<std::string> drop_baked_patches(std::vector<std::string>* opts,
                                            const std::vector<std::pair<std::string, std::string>>& baked,
                                            const fs::path& cache) {
    // "<stamp> <digest>" lines from earlier starts.
    std::vector<std::pair<std::string, std::string>> known, now;
    if (!cache.empty()) {
        std::ifstream in(cache);
        std::string stamp_, dig;
        while (in >> stamp_ >> dig) known.emplace_back(stamp_, dig);
    }
    auto digest_of = [&](const fs::path& dir) {
        const std::string st = cache.empty() ? std::string() : patch_dir_stamp(dir);
        if (!st.empty()) {
            for (const auto& k : known) {
                if (k.first == st) {
                    now.push_back(k);
                    return k.second;
                }
            }
        }
        const std::string dig = patch_dir_digest(dir);
        if (!st.empty() && !dig.empty()) now.emplace_back(st, dig);
        return dig;
    };
    std::vector<std::string> kept, blocked;
    for (const std::string& o : *opts) {
        static const char kPm[] = "--patch-module=";
        if (!starts(o, kPm)) {
            kept.push_back(o);
            continue;
        }
        const std::string spec = o.substr(sizeof kPm - 1);  // <module>=<dir>
        const size_t eq = spec.find('=');
        const std::string mod = spec.substr(0, eq);
        bool ok = false;
        if (eq != std::string::npos) {
            for (const auto& b : baked) {
                if (b.first != mod) continue;
                const std::string dig = digest_of(fs::u8path(spec.substr(eq + 1)));
                ok = !dig.empty() && dig == b.second;
                break;
            }
        }
        if (!ok) {
            blocked.push_back(mod);
            kept.push_back(o);
        }
    }
    opts->swap(kept);
    if (!cache.empty() && now != known) {
        // Only this start's directories: a stale line never answers again.
        std::ofstream out(cache, std::ios::trunc);
        for (const auto& n : now) out << n.first << " " << n.second << "\n";
    }
    return blocked;
}

std::string key_of(const std::vector<std::string>& parts) {
    uint64_t h = 1469598103934665603ULL;
    for (const std::string& s : parts) {
        for (unsigned char c : s) h = (h ^ c) * 1099511628211ULL;
        h = (h ^ 0xff) * 1099511628211ULL;  // a separator, so "ab"+"c" != "a"+"bc"
    }
    char hex[20];
    std::snprintf(hex, sizeof hex, "%016llx", static_cast<unsigned long long>(h));
    return hex;
}

std::string stamp(const fs::path& p) {
    std::error_code ec;
    const auto size = fs::file_size(p, ec);
    if (ec) return "-";
    const auto t = fs::last_write_time(p, ec);
    if (ec) return "-";
    return std::to_string(size) + ":" + std::to_string(static_cast<long long>(t.time_since_epoch().count()));
}

bool per_launch(const std::string& o) {
    if (starts(o, "-Djava.class.path=")) return false;
    return starts(o, "-D") || starts(o, "-XX:ErrorFile=") || starts(o, "-Xlog:");
}

std::string merge_classlists(const std::vector<std::string>& lists) {
    std::string out;
    std::unordered_set<std::string> seen;
    for (const std::string& text : lists) {
        std::istringstream in(text);
        std::string line;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line[0] == '#') continue;
            // Another loader's class (an "unregistered" one) names its super
            // and source by the ids of its own file; the framework's are the
            // built-in loaders' and need neither.
            if (line[0] != '@' && (line.find(" super: ") != std::string::npos ||
                                   line.find(" source: ") != std::string::npos))
                continue;
            if (line[0] != '@') {
                const size_t id = line.find(" id: ");
                if (id != std::string::npos) line.erase(id);
            }
            if (seen.insert(line).second) out += line + "\n";
        }
    }
    return out;
}

}  // namespace winapk::host::cds
