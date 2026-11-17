// winapk-x18scan -- which arm64 libraries use register x18 (arm64_x18.h)?
//
//   winapk-x18scan [--list] <lib.so> ...     the code of each ELF file
//   winapk-x18scan --raw <file> ...          the whole file as A64 code (differential tests)
//
// One line per file: clean, reads only (fine), or uses x18 as scratch. --raw --list prints every
// offset. Exit status 1 if a file uses x18 as scratch, 2 if one cannot be read.

#include "winapk/arm64_x18.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

namespace {

bool read_file(const char* path, std::vector<uint8_t>* out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out->assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    bool list = false, raw = false;
    int status = 0, files = 0;
    for (int a = 1; a < argc; ++a) {
        if (!std::strcmp(argv[a], "--list")) { list = true; continue; }
        if (!std::strcmp(argv[a], "--raw")) { raw = true; continue; }
        ++files;
        std::vector<uint8_t> file;
        if (!read_file(argv[a], &file)) {
            std::printf("%s: cannot read\n", argv[a]);
            status = 2;
            continue;
        }
        if (raw) {
            if (list) {
                for (size_t at = 0; at + 4 <= file.size(); at += 4) {
                    if (winapk::arm64::scan_x18(file.data() + at, 4).found()) std::printf("%zx\n", at);
                }
                continue;
            }
            const winapk::arm64::X18Scan s = winapk::arm64::scan_x18(file.data(), file.size());
            std::printf("%s: %zu use(s)\n", argv[a], s.uses);
            if (s.found() && status == 0) status = 1;
            continue;
        }
        const winapk::arm64::ElfX18Scan e = winapk::arm64::scan_elf_x18(file.data(), file.size());
        if (!e.ok) {
            std::printf("%s: not an arm64 library\n", argv[a]);
            status = 2;
        } else if (e.scan.scratch()) {
            std::printf("%s: x18 as a scratch register: %zu write(s), first at file offset 0x%zx (%zu use(s) in all)%s\n",
                        argv[a], e.scan.writes, e.scan.first_write, e.scan.uses,
                        e.by_sections ? "" : " (no section headers: whole segments scanned)");
            if (status == 0) status = 1;
        } else if (e.scan.found()) {
            std::printf("%s: reads x18 or restores a context (%zu use(s)): fine\n", argv[a], e.scan.uses);
        } else {
            std::printf("%s: clean\n", argv[a]);
        }
    }
    if (!files) {
        std::fprintf(stderr, "usage: winapk-x18scan [--list] [--raw] <file> ...\n");
        return 2;
    }
    return status;
}
