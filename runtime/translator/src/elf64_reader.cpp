// elf64_reader.cpp -- AArch64 ELF parser for the Mac.apk translator.
//
// Same shape as elf_reader.cpp (Elf32). Bounds-checked, little-endian,
// returns descriptive errors. The file format is stable enough that
// the only meaningful difference from Elf32 is field widths plus the
// AArch64 PLT entry geometry (32-byte PLT[0], 16-byte PLT[N>=1]).

#include "macapk/elf64_reader.h"
#include "macapk/elf_reader.h"   // for ELFCLASS64 + shared SHT_*/STB_*/PT_LOAD constants

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <ios>
#include <sstream>
#include <unordered_map>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace macapk::translator {

namespace {

// ---- MACAPK_FIRSTLAUNCH_FASTREAD (default ON, 2026-09-26) -------------
//
// WHY. read_elf64 slurped the file with `ss << f.rdbuf()`, then `ss.str()`,
// then `raw.assign(...)`: three full copies, the first of them grown by
// repeated reallocation inside the stringbuf. Measured on Crossy Road's
// libil2cpp.so (87.6 MB), that read alone is 325-380 ms of a 450 ms Stage-6
// pack, and it holds ~3x the file in RSS at its peak (the pack's peak RSS was
// 410-445 MB for an 80-88 MB input). A single pread() into the final vector
// is 13-14 ms and one copy. See docs/firstlaunch-translation-cost_2026-09-26.md.
//
// SAME BYTES, BY CONSTRUCTION. Both paths put the file's bytes, in order, into
// `raw`, and everything downstream reads only `raw`; so the translator's
// output cannot change. debug/firstlaunch_gate.sh proves it on real libraries
// by hashing the output dylib with this gate on and off.
//
// MACAPK_FIRSTLAUNCH_FASTREAD=0 restores the exact stream path.
bool fastread_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_FASTREAD");
        return !(e && *e == '0');
    }();
    return on;
}

// One open + fstat + pread loop straight into `out`. A short read (the file
// shrank under us) or a read error ends the loop and keeps what was read,
// which is what the stream path did at EOF: the "too small" / "past EOF"
// checks below then report it exactly as before.
bool read_file_pread(const std::string& path, std::vector<uint8_t>* out,
                     std::string* err) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *err = "cannot open " + path + ": " + std::strerror(errno);
        return false;
    }
    struct stat st {};
    if (::fstat(fd, &st) != 0 || st.st_size < 0) {
        *err = "cannot open " + path + ": " + std::strerror(errno);
        ::close(fd);
        return false;
    }
    out->resize(static_cast<size_t>(st.st_size));
    size_t off = 0;
    while (off < out->size()) {
        const ssize_t r = ::pread(fd, out->data() + off, out->size() - off,
                                  static_cast<off_t>(off));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        off += static_cast<size_t>(r);
    }
    out->resize(off);
    ::close(fd);
    return true;
}

template <typename T>
bool read_at(const std::vector<uint8_t>& buf, size_t off, T* out) {
    // Subtract, never add: `off` is derived from attacker-controlled ELF
    // header fields (sh_offset + i * sh_entsize), and on the 64-bit reader
    // those are full 64-bit values, so `off + sizeof(T)` can wrap past
    // buf.size() and admit a read from wherever `data() + off` lands. The
    // subtraction cannot wrap because the first test pins off <= size.
    if (off > buf.size() || sizeof(T) > buf.size() - off) return false;
    std::memcpy(out, buf.data() + off, sizeof(T));
    return true;
}

std::string read_cstr(const std::vector<uint8_t>& buf, size_t off,
                      size_t max_len) {
    if (off >= buf.size()) return {};
    // Same wrap hazard as read_at: max_len reaches here as
    // `sh_size - st_name`, which is itself an unsigned subtraction that
    // underflows to a near-SIZE_MAX value whenever st_name points past the
    // string table. Clamping max_len to what remains keeps `off + max_len`
    // out of the arithmetic entirely.
    size_t avail = buf.size() - off;
    size_t end = off + (max_len < avail ? max_len : avail);
    const char* p = reinterpret_cast<const char*>(buf.data()) + off;
    size_t len = strnlen(p, end - off);
    return std::string(p, len);
}

// ---- PT_DYNAMIC symbol fallback (2026-11-05) -------------------------
//
// A shared object is allowed to ship with its section headers stripped
// (e_shnum == 0): bionic's linker never reads them, only PT_DYNAMIC. Some
// hardened libraries are built exactly that way (Tencent's libmmkv.so in
// Binance, for example), and the section-only SHT_DYNSYM scan below then
// finds zero exports. The wrapper is still emitted, but with no
// JNI_OnLoad / Java_* trampolines -- so the JVM never calls JNI_OnLoad,
// the library's natives are never registered, and the app dies with
// UnsatisfiedLinkError on its first native call. The dynamic table
// carries everything needed, so read it when SHT_DYNSYM is absent.
constexpr int64_t DT_NULL     = 0;
constexpr int64_t DT_HASH     = 4;
constexpr int64_t DT_STRTAB   = 5;
constexpr int64_t DT_SYMTAB   = 6;
constexpr int64_t DT_STRSZ    = 10;
constexpr int64_t DT_GNU_HASH = 0x6ffffef5;

struct Elf64Dyn {
    int64_t  d_tag;
    uint64_t d_val;
};

// Map an ELF virtual address to a file offset through PT_LOAD. False when
// the address is not backed by file bytes (bss, or past EOF).
bool vaddr_to_file_off(const std::vector<Elf64Phdr>& phdrs, uint64_t vaddr,
                       uint64_t* off) {
    for (const auto& ph : phdrs) {
        if (ph.p_type != PT_LOAD || vaddr < ph.p_vaddr) continue;
        uint64_t delta = vaddr - ph.p_vaddr;
        if (delta >= ph.p_filesz) continue;
        *off = ph.p_offset + delta;
        return true;
    }
    return false;
}

// Symbol count implied by a DT_GNU_HASH table: one past the highest chain
// end, not nbuckets. Same walk as bionic_shim_arm32_link.cpp's lookup,
// with Elf64_WORD = 8 bytes for the bloom filter.
bool gnu_hash_symbol_count(const std::vector<uint8_t>& raw, uint64_t off,
                           uint32_t* count) {
    uint32_t nbuckets = 0, symoffset = 0, bloom_size = 0;
    if (!read_at(raw, off, &nbuckets) || !read_at(raw, off + 4, &symoffset)
        || !read_at(raw, off + 8, &bloom_size))
        return false;
    if (nbuckets == 0 || nbuckets > (1u << 20) || bloom_size > (1u << 20))
        return false;
    const uint64_t buckets_off = off + 16 + static_cast<uint64_t>(bloom_size) * 8;
    const uint64_t chains_off  = buckets_off + static_cast<uint64_t>(nbuckets) * 4;
    uint64_t max_index = symoffset;
    for (uint32_t i = 0; i < nbuckets; ++i) {
        uint32_t b = 0;
        if (!read_at(raw, buckets_off + i * 4, &b)) return false;
        if (b < symoffset) continue;
        uint64_t j = b;
        for (;; ++j) {
            uint32_t c = 0;
            if (!read_at(raw, chains_off + (j - symoffset) * 4, &c)) break;
            if (c & 1u) break;                       // last symbol in chain
            if (j - b > (1u << 24)) return false;    // malformed
        }
        if (j + 1 > max_index) max_index = j + 1;
    }
    if (max_index > (1u << 24)) return false;
    *count = static_cast<uint32_t>(max_index);
    return true;
}

}  // namespace

std::span<const uint8_t> Elf64Image::code_for(const Symbol64& sym,
                                              size_t* out_size) const {
    auto span = code_at(sym.address, sym.size);
    if (out_size) *out_size = span.size();
    return span;
}

std::span<const uint8_t> Elf64Image::code_at(uint64_t vaddr,
                                              size_t bytes_wanted) const {
    for (const auto& seg : loadable) {
        if (vaddr < seg.vaddr) continue;
        size_t off = static_cast<size_t>(vaddr - seg.vaddr);
        if (off >= seg.bytes.size()) continue;
        size_t avail = seg.bytes.size() - off;
        size_t take = bytes_wanted ? std::min(avail, bytes_wanted) : avail;
        return std::span<const uint8_t>(seg.bytes.data() + off, take);
    }
    return {};
}

std::string read_elf64(const std::string& path, Elf64Image* out) {
    return read_elf64(path, out, /*copy_segment_bytes=*/true);
}

std::string read_elf64(const std::string& path, Elf64Image* out,
                       bool copy_segment_bytes) {
    if (!out) return "internal error: null Elf64Image*";
    if (!fastread_enabled()) copy_segment_bytes = true;

    if (fastread_enabled()) {
        std::string err;
        if (!read_file_pread(path, &out->raw, &err)) return err;
    } else {
        std::ifstream f(path, std::ios::binary);
        if (!f) return "cannot open " + path + ": " + std::strerror(errno);
        std::ostringstream ss;
        ss << f.rdbuf();
        std::string data = ss.str();
        out->raw.assign(data.begin(), data.end());
    }

    if (out->raw.size() < sizeof(Elf64Ehdr))
        return path + ": file is too small to contain an ELF64 header";

    if (!read_at(out->raw, 0, &out->hdr))
        return path + ": failed to read ELF64 header";

    const auto& id = out->hdr.e_ident;
    if (!(id[0] == 0x7f && id[1] == 'E' && id[2] == 'L' && id[3] == 'F'))
        return path + ": not an ELF file (bad magic)";
    if (id[4] != ELFCLASS64)
        return path + ": expected ELFCLASS64";
    if (id[5] != ELFDATA2LSB)
        return path + ": expected little-endian (ELFDATA2LSB)";
    if (out->hdr.e_machine != EM_AARCH64_64)
        return path + ": expected EM_AARCH64 (183); got " +
               std::to_string(out->hdr.e_machine);

    out->phdrs.resize(out->hdr.e_phnum);
    for (uint16_t i = 0; i < out->hdr.e_phnum; ++i) {
        size_t off = static_cast<size_t>(out->hdr.e_phoff)
                   + static_cast<size_t>(i) * out->hdr.e_phentsize;
        if (!read_at(out->raw, off, &out->phdrs[i]))
            return path + ": malformed program header table";
    }

    out->shdrs.resize(out->hdr.e_shnum);
    for (uint16_t i = 0; i < out->hdr.e_shnum; ++i) {
        size_t off = static_cast<size_t>(out->hdr.e_shoff)
                   + static_cast<size_t>(i) * out->hdr.e_shentsize;
        if (!read_at(out->raw, off, &out->shdrs[i]))
            return path + ": malformed section header table";
    }

    for (const auto& ph : out->phdrs) {
        if (ph.p_type != PT_LOAD) continue;
        // Bounds check WITHOUT the overflow. `p_offset + p_filesz` are both
        // unsigned and come straight out of an attacker-supplied APK, so the
        // sum WRAPS: p_offset = 0xfffff000 with p_filesz = 0x2000 sums to
        // 0x1000, sails past a `> raw.size()` test, and then the assign() below
        // walks `raw.begin() + 0xfffff000`. Subtracting instead cannot wrap
        // because the first test guarantees p_offset <= size. Same shape as the
        // classic `if (ptr + len > end)` UB, but on unsigned file offsets
        // rather than pointers, so it is a silent wrap and not UB.
        if (ph.p_offset > out->raw.size()
            || ph.p_filesz > out->raw.size() - ph.p_offset)
            return path + ": PT_LOAD segment extends past EOF";
        CodeSegment64 seg{};
        seg.vaddr      = ph.p_vaddr;
        seg.executable = (ph.p_flags & PF_X) != 0;
        if (copy_segment_bytes) {
            seg.bytes.assign(out->raw.begin() + ph.p_offset,
                              out->raw.begin() + ph.p_offset + ph.p_filesz);
            if (ph.p_memsz > ph.p_filesz)
                seg.bytes.resize(static_cast<size_t>(ph.p_memsz), 0);
        }
        out->loadable.push_back(std::move(seg));
    }

    auto find_section = [&](uint32_t type) -> const Elf64Shdr* {
        for (const auto& sh : out->shdrs) {
            if (sh.sh_type == type) return &sh;
        }
        return nullptr;
    };

    const Elf64Shdr* dynsym = find_section(SHT_DYNSYM);
    const Elf64Shdr* symtab = find_section(SHT_SYMTAB);
    const Elf64Shdr* sym_section = dynsym ? dynsym : symtab;
    std::vector<bool>        dynsym_is_import;
    if (sym_section) {
        if (sym_section->sh_link >= out->shdrs.size())
            return path + ": symbol section has invalid sh_link";
        const Elf64Shdr& strs = out->shdrs[sym_section->sh_link];
        if (strs.sh_type != SHT_STRTAB)
            return path + ": symbol section's sh_link is not a strtab";

        size_t entries = sym_section->sh_size / sizeof(Elf64Sym);
        dynsym_is_import.resize(entries, false);
        for (size_t i = 0; i < entries; ++i) {
            Elf64Sym es{};
            size_t off = static_cast<size_t>(sym_section->sh_offset)
                       + i * sizeof(Elf64Sym);
            if (!read_at(out->raw, off, &es)) break;
            uint8_t bind = es.st_info >> 4;
            uint8_t type = es.st_info & 0xf;

            if (es.st_name == 0) continue;
            std::string name = read_cstr(out->raw,
                static_cast<size_t>(strs.sh_offset) + es.st_name,
                static_cast<size_t>(strs.sh_size) - es.st_name);
            if (name.empty()) continue;

            if (es.st_shndx == 0) {
                dynsym_is_import[i] = true;
                ImportedSymbol64 is{};
                is.name      = std::move(name);
                is.bind      = bind;
                is.type      = type;
                is.plt_addr  = 0;
                out->imports.push_back(std::move(is));
                continue;
            }
            if (es.st_value == 0 && es.st_size == 0) continue;

            Symbol64 s{};
            s.name     = std::move(name);
            s.address  = es.st_value;
            s.size     = es.st_size;
            s.bind     = bind;
            s.type     = type;
            out->symbols.push_back(std::move(s));
        }
    } else {
        // No SHT_DYNSYM: section-stripped image. Recover the same symbol
        // list from PT_DYNAMIC / DT_SYMTAB (see the constants above).
        const Elf64Phdr* dynph = nullptr;
        for (const auto& ph : out->phdrs)
            if (ph.p_type == PT_DYNAMIC) { dynph = &ph; break; }
        if (dynph) {
            uint64_t symtab_v = 0, strtab_v = 0, strsz = 0;
            uint64_t hash_v = 0, gnu_v = 0;
            bool have_sym = false, have_str = false, have_hash = false, have_gnu = false;
            const uint64_t ndyn = dynph->p_filesz / sizeof(Elf64Dyn);
            for (uint64_t i = 0; i < ndyn; ++i) {
                Elf64Dyn d{};
                if (!read_at(out->raw, dynph->p_offset + i * sizeof(Elf64Dyn), &d))
                    break;
                if (d.d_tag == DT_NULL) break;
                switch (d.d_tag) {
                    case DT_SYMTAB:   symtab_v = d.d_val; have_sym  = true; break;
                    case DT_STRTAB:   strtab_v = d.d_val; have_str  = true; break;
                    case DT_STRSZ:    strsz    = d.d_val; break;
                    case DT_HASH:     hash_v   = d.d_val; have_hash = true; break;
                    case DT_GNU_HASH: gnu_v    = d.d_val; have_gnu  = true; break;
                    default: break;
                }
            }
            uint64_t symtab_off = 0, strtab_off = 0, hash_off = 0, gnu_off = 0;
            bool ok = have_sym && have_str && strsz > 0
                      && vaddr_to_file_off(out->phdrs, symtab_v, &symtab_off)
                      && vaddr_to_file_off(out->phdrs, strtab_v, &strtab_off)
                      && strtab_off <= out->raw.size()
                      && strsz <= out->raw.size() - strtab_off;
            uint32_t entries = 0;
            if (ok && have_hash
                && vaddr_to_file_off(out->phdrs, hash_v, &hash_off)) {
                // DT_HASH: { nbucket, nchain }; nchain is the symbol count.
                uint32_t nbucket = 0;
                ok = read_at(out->raw, hash_off, &nbucket)
                     && read_at(out->raw, hash_off + 4, &entries);
            } else if (ok && have_gnu
                       && vaddr_to_file_off(out->phdrs, gnu_v, &gnu_off)) {
                ok = gnu_hash_symbol_count(out->raw, gnu_off, &entries);
            } else if (ok && strtab_off > symtab_off
                       && (strtab_off - symtab_off) % sizeof(Elf64Sym) == 0) {
                // No hash at all: the usual layout puts .dynstr right after
                // .dynsym. Only trust that when the gap is a whole number of
                // symbols.
                entries = static_cast<uint32_t>(
                    (strtab_off - symtab_off) / sizeof(Elf64Sym));
            } else {
                ok = false;
            }
            if (ok && entries > 0 && symtab_off <= out->raw.size()
                && static_cast<uint64_t>(entries) * sizeof(Elf64Sym)
                       <= out->raw.size() - symtab_off) {
                for (uint32_t i = 0; i < entries; ++i) {
                    Elf64Sym es{};
                    if (!read_at(out->raw,
                                 symtab_off + static_cast<uint64_t>(i) * sizeof(Elf64Sym),
                                 &es))
                        break;
                    if (es.st_name == 0 || es.st_name >= strsz) continue;
                    uint8_t bind = es.st_info >> 4;
                    uint8_t type = es.st_info & 0xf;
                    std::string name = read_cstr(out->raw,
                        static_cast<size_t>(strtab_off) + es.st_name,
                        static_cast<size_t>(strsz) - es.st_name);
                    if (name.empty()) continue;

                    if (es.st_shndx == 0) {
                        ImportedSymbol64 is{};
                        is.name     = std::move(name);
                        is.bind     = bind;
                        is.type     = type;
                        is.plt_addr = 0;
                        out->imports.push_back(std::move(is));
                        continue;
                    }
                    if (es.st_value == 0 && es.st_size == 0) continue;

                    Symbol64 s{};
                    s.name    = std::move(name);
                    s.address = es.st_value;
                    s.size    = es.st_size;
                    s.bind    = bind;
                    s.type    = type;
                    out->symbols.push_back(std::move(s));
                }
            }
        }
    }

    // Resolve PLT addresses by name from .rela.plt order. AArch64 ELF
    // emits .rela.plt (RELA, with addend); REL form is not used.
    auto find_section_by_name = [&](const std::string& name) -> const Elf64Shdr* {
        if (out->hdr.e_shstrndx == 0
            || out->hdr.e_shstrndx >= out->shdrs.size()) return nullptr;
        const Elf64Shdr& strtab = out->shdrs[out->hdr.e_shstrndx];
        for (const auto& sh : out->shdrs) {
            std::string n = read_cstr(out->raw,
                static_cast<size_t>(strtab.sh_offset) + sh.sh_name,
                static_cast<size_t>(strtab.sh_size) - sh.sh_name);
            if (n == name) return &sh;
        }
        return nullptr;
    };

    const Elf64Shdr* plt    = find_section_by_name(".plt");
    const Elf64Shdr* relplt = find_section_by_name(".rela.plt");
    if (plt && relplt) {
        size_t entry_size = sizeof(Elf64Rela);
        size_t count = relplt->sh_size / entry_size;
        // Map dynsym index -> import-list index.
        std::unordered_map<uint32_t, size_t> dynidx_to_import;
        if (sym_section) {
            size_t entries = sym_section->sh_size / sizeof(Elf64Sym);
            size_t imp_i = 0;
            for (size_t i = 0; i < entries && imp_i < out->imports.size(); ++i) {
                if (i < dynsym_is_import.size() && dynsym_is_import[i]) {
                    dynidx_to_import[static_cast<uint32_t>(i)] = imp_i;
                    ++imp_i;
                }
            }
        }
        for (size_t i = 0; i < count; ++i) {
            Elf64Rela r{};
            size_t off = static_cast<size_t>(relplt->sh_offset)
                       + i * entry_size;
            if (!read_at(out->raw, off, &r)) break;
            uint32_t sym_idx = static_cast<uint32_t>(r.r_info >> 32);
            uint64_t plt_addr =
                plt->sh_addr + AARCH64_PLT0_SIZE + i * AARCH64_PLT_ENTRY;
            auto it = dynidx_to_import.find(sym_idx);
            if (it != dynidx_to_import.end() && it->second < out->imports.size()) {
                out->imports[it->second].plt_addr = plt_addr;
            }
        }
    }

    auto slash = path.find_last_of('/');
    out->soname = (slash == std::string::npos) ? path : path.substr(slash + 1);

    return {};
}

}  // namespace macapk::translator
