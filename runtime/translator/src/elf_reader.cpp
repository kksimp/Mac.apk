// elf_reader.cpp -- the parsing side. Bounds-checked, little-endian
// only. Returns descriptive errors instead of asserting because the
// translator is run as a CLI from build.sh and a clear "this APK's
// .so is malformed" message is more useful than a SIGABRT.

#include "macapk/elf_reader.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <ios>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <capstone/capstone.h>   // exidx ARM/Thumb mode validity-vote

namespace macapk::translator {

namespace {

// Read a POD struct out of the mmap-style buffer at `off`. Returns
// false if the read would exceed `buf.size()`. We use memcpy to dodge
// alignment concerns -- ELF struct fields aren't naturally aligned in
// the file representation on every host.
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

}  // namespace

std::span<const uint8_t> Elf32Image::code_for(const Symbol& sym,
                                              size_t* out_size) const {
    // Bionic encodes Thumb-mode entry points by setting the low bit of
    // the symbol's value, so the actual address is value & ~1.
    uint32_t addr = sym.address & ~1u;
    size_t want = sym.size;
    auto span = code_at(addr, want);
    if (out_size) *out_size = span.size();
    return span;
}

std::span<const uint8_t> Elf32Image::code_at(uint32_t vaddr,
                                             size_t bytes_wanted) const {
    for (const auto& seg : loadable) {
        if (vaddr < seg.vaddr) continue;
        size_t off = vaddr - seg.vaddr;
        if (off >= seg.bytes.size()) continue;
        size_t avail = seg.bytes.size() - off;
        size_t take = bytes_wanted ? std::min(avail, bytes_wanted) : avail;
        return std::span<const uint8_t>(seg.bytes.data() + off, take);
    }
    return {};
}

std::string read_elf32(const std::string& path, Elf32Image* out,
                       uint32_t load_bias) {
    if (!out) return "internal error: null Elf32Image*";

    // Slurp the file. Mac.apk's APK .so files are at most a few MB; no
    // need for streaming.
    std::ifstream f(path, std::ios::binary);
    if (!f) return "cannot open " + path + ": " + std::strerror(errno);
    std::ostringstream ss;
    ss << f.rdbuf();
    std::string data = ss.str();
    out->raw.assign(data.begin(), data.end());

    // Minimum size to even have a header.
    if (out->raw.size() < sizeof(Elf32Ehdr))
        return path + ": file is too small to contain an ELF header";

    if (!read_at(out->raw, 0, &out->hdr))
        return path + ": failed to read ELF header";

    // Magic.
    const auto& id = out->hdr.e_ident;
    if (!(id[0] == 0x7f && id[1] == 'E' && id[2] == 'L' && id[3] == 'F'))
        return path + ": not an ELF file (bad magic)";

    if (id[5] != ELFDATA2LSB)
        return path + ": expected little-endian (ELFDATA2LSB)";

    // ELFCLASS64 inputs go through the parallel read_elf64() path;
    // main.cpp peeks at the class byte and dispatches before calling
    // either reader. If we got here with ELFCLASS64 the caller skipped
    // the dispatch -- treat it as an internal error so misuse is loud.
    if (id[4] == ELFCLASS64) {
        return path + ": ELFCLASS64 input passed to read_elf32 "
                      "(call read_elf64 instead)";
    }
    if (id[4] != ELFCLASS32)
        return path + ": expected ELFCLASS32 or ELFCLASS64";

    if (out->hdr.e_machine != EM_ARM)
        return path + ": expected EM_ARM (40); got " +
               std::to_string(out->hdr.e_machine);

    // Program headers.
    out->phdrs.resize(out->hdr.e_phnum);
    for (uint16_t i = 0; i < out->hdr.e_phnum; ++i) {
        size_t off = out->hdr.e_phoff + i * out->hdr.e_phentsize;
        if (!read_at(out->raw, off, &out->phdrs[i]))
            return path + ": malformed program header table";
    }

    // Section headers.
    out->shdrs.resize(out->hdr.e_shnum);
    for (uint16_t i = 0; i < out->hdr.e_shnum; ++i) {
        size_t off = out->hdr.e_shoff + i * out->hdr.e_shentsize;
        if (!read_at(out->raw, off, &out->shdrs[i]))
            return path + ": malformed section header table";
    }

    // Resolve PT_LOAD segments. Each becomes a CodeSegment (executable
    // ones drive the lifter; non-executable ones carry .rodata + .data
    // for relocations and constant references).
    for (const auto& ph : out->phdrs) {
        // PT_DYNAMIC: recorded for the runtime linker only (DataImportSite);
        // nothing below reads it.
        if (ph.p_type == PT_DYNAMIC && out->dynamic_vaddr == 0)
            out->dynamic_vaddr = ph.p_vaddr;
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
        CodeSegment seg{};
        seg.vaddr      = ph.p_vaddr;
        seg.executable = (ph.p_flags & PF_X) != 0;
        // Copy filesz bytes; the BSS portion (memsz - filesz) is left
        // zero-padded inside the lifter's address-space model.
        seg.bytes.assign(out->raw.begin() + ph.p_offset,
                          out->raw.begin() + ph.p_offset + ph.p_filesz);
        if (ph.p_memsz > ph.p_filesz)
            seg.bytes.resize(ph.p_memsz, 0);
        out->loadable.push_back(std::move(seg));
    }

    // Find the section header string table for symbol resolution.
    // For Bionic .so files we want .dynsym + .dynstr (loaded at runtime)
    // rather than .symtab + .strtab (sometimes stripped). We try .dynsym
    // first and fall back to .symtab.
    auto find_section = [&](uint32_t type) -> const Elf32Shdr* {
        for (const auto& sh : out->shdrs) {
            if (sh.sh_type == type) return &sh;
        }
        return nullptr;
    };

    const Elf32Shdr* dynsym = find_section(SHT_DYNSYM);
    const Elf32Shdr* symtab = find_section(SHT_SYMTAB);
    const Elf32Shdr* sym_section = dynsym ? dynsym : symtab;
    // Map of dynsym index -> import name (for .rel.plt resolution below).
    std::vector<std::string> dynsym_imports_by_index;
    std::vector<uint8_t>     dynsym_type_by_index;
    std::vector<bool>        dynsym_is_import;
    std::vector<uint32_t>    dynsym_value_by_index;   // intra-module PLT redirect target
    // st_value exactly as the file has it (bit 0 NOT masked): a data
    // relocation against a byte-aligned object (a typeinfo NAME string at an
    // odd address) must write that address, not the Thumb-masked one.
    std::vector<uint32_t>    dynsym_raw_value_by_index;
    std::vector<std::string> dynsym_name_by_index;    // for operator-new detection
    // (2026-09-27: the `is_freetype_lib` detection that used to live here is
    // gone. It fed only the opt-in MACAPK_PLT_REDIRECT_FREETYPE codegen
    // redirect, retired with the rest of the MACAPK_PLT_REDIRECT* knobs --
    // see the JUMP_SLOT pass below and
    // docs/plt-redirect-knobs-retired_2026-09-27.md. Bionic has no per-library
    // or per-name policy for intra-module PLT calls, so neither do we.)
    if (sym_section) {
        // The associated string table is in sh_link.
        if (sym_section->sh_link >= out->shdrs.size())
            return path + ": symbol section has invalid sh_link";
        const Elf32Shdr& strs = out->shdrs[sym_section->sh_link];
        if (strs.sh_type != SHT_STRTAB)
            return path + ": symbol section's sh_link is not a strtab";

        size_t entries = sym_section->sh_size / sizeof(Elf32Sym);
        dynsym_imports_by_index.resize(entries);
        dynsym_type_by_index.resize(entries);
        dynsym_is_import.resize(entries, false);
        dynsym_value_by_index.resize(entries, 0);
        dynsym_raw_value_by_index.resize(entries, 0);
        dynsym_name_by_index.resize(entries);
        for (size_t i = 0; i < entries; ++i) {
            Elf32Sym es{};
            size_t off = sym_section->sh_offset + i * sizeof(Elf32Sym);
            if (!read_at(out->raw, off, &es)) break;
            uint8_t bind = es.st_info >> 4;
            uint8_t type = es.st_info & 0xf;
            dynsym_type_by_index[i] = type;

            if (es.st_shndx != 0) {
                dynsym_value_by_index[i] = es.st_value & ~1u;
                dynsym_raw_value_by_index[i] = es.st_value;
            }

            if (es.st_name == 0) continue;          // unnamed
            std::string name = read_cstr(out->raw, strs.sh_offset + es.st_name,
                                          strs.sh_size - es.st_name);
            if (name.empty()) continue;
            dynsym_name_by_index[i] = name;          // all symbols (for operator-new routing)

            // Imports (st_shndx == SHN_UNDEF) go into a separate list
            // so the translator can resolve PLT stubs to import names.
            if (es.st_shndx == 0) {
                dynsym_imports_by_index[i] = name;
                dynsym_is_import[i] = true;
                ImportedSymbol is{};
                is.name      = std::move(name);
                is.bind      = bind;
                is.type      = type;
                is.plt_addr  = 0;       // populated below from .rel.plt
                out->imports.push_back(std::move(is));
                continue;
            }
            if (es.st_value == 0 && es.st_size == 0) continue;

            Symbol s{};
            s.name     = std::move(name);
            s.address  = es.st_value & ~1u;
            s.size     = es.st_size;
            s.bind     = bind;
            s.type     = type;
            s.is_thumb = (es.st_value & 1u) != 0;
            out->symbols.push_back(std::move(s));
        }
    }

    // ---- PLT layout: derive plt_addr for each import ----
    // The .rel.plt section's relocation entries appear in PLT-stub
    // order. For ARM32, PLT[0] is the resolver (20 bytes) and
    // PLT[N] for N >= 1 is 12 bytes. So the Nth .rel.plt entry maps
    // to PLT_BASE + 20 + (N-1)*12. The .plt section's sh_addr gives
    // PLT_BASE.
    auto find_section_by_name = [&](const std::string& name) -> const Elf32Shdr* {
        if (out->hdr.e_shstrndx == 0
            || out->hdr.e_shstrndx >= out->shdrs.size()) return nullptr;
        const Elf32Shdr& strtab = out->shdrs[out->hdr.e_shstrndx];
        for (const auto& sh : out->shdrs) {
            std::string n = read_cstr(out->raw, strtab.sh_offset + sh.sh_name,
                                       strtab.sh_size - sh.sh_name);
            if (n == name) return &sh;
        }
        return nullptr;
    };

    // MACAPK_ARM32_BIONIC_THUMB_BIT (default ON): a relocation against a
    // DEFINED symbol writes st_value + load_bias with bit 0 -- the ARM Thumb
    // flag -- exactly as the symbol declares it. Bionic masks nothing:
    //   bionic/linker/linker_soinfo.h, soinfo::resolve_symbol_address():
    //     `return static_cast<ElfW(Addr)>(s->st_value + load_bias);`
    //   bionic/linker/linker_relocate.cpp, process_relocation_impl():
    //     `sym_addr = found_in->resolve_symbol_address(sym);` then
    //     R_GENERIC_JUMP_SLOT `result = sym_addr + get_addend_norel()`,
    //     R_GENERIC_ABSOLUTE `result = sym_addr + get_addend_rel()`,
    //     R_GENERIC_GLOB_DAT `result = sym_addr + get_addend_norel()`.
    // We used to write `st_value & ~1` into every local JUMP_SLOT GOT word and
    // every GLOB_DAT / ABS32 word against an STT_FUNC, so a Thumb function's
    // address read out of the GOT was EVEN while the same function's address
    // out of a vtable or .data.rel.ro (R_ARM_RELATIVE, whose in-place addend
    // the static linker already made odd) was ODD: `&f == vtbl[n]` compared
    // false, and a guest that reads its own GOT saw the wrong instruction set.
    // 94,417 local JUMP_SLOT words and 67,040 STT_FUNC data words change on the
    // 244-library ARM32 corpus (debug/arm32reloc_verify/census.py). Indirect
    // calls are unaffected: __macapk_resolve_function looks the LUT up at
    // `vaddr & ~1u`. =0 restores the masked writes byte for byte.
    // docs/arm32-bionic-reloc-exact_2026-09-28.md.
    static const bool bionic_thumb_bit = [] {
        const char* e = std::getenv("MACAPK_ARM32_BIONIC_THUMB_BIT");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    // Local R_ARM_JUMP_SLOT GOT writes, APPLIED AFTER the .rel.dyn pass below.
    // Bionic's soinfo::relocate() (linker_relocate.cpp) runs DT_REL
    // (`plain_relocate<RelocMode::Typical>(relocator, rel_, rel_count_)`) and
    // only then DT_JMPREL (`plain_relocate<RelocMode::JumpTable>(relocator,
    // plt_rel_, plt_rel_count_)`). We still WALK .rel.plt first -- the import
    // plt_addr it records is what .rel.dyn writes for an import GLOB_DAT --
    // but the words land in Bionic's order, so an R_ARM_ABS32 whose in-place
    // addend sits in a JUMP_SLOT word reads the file's value, as on Android,
    // and the JUMP_SLOT value is the one that survives. (No corpus library
    // relocates one word from both tables -- census `overlap=0` on 244 -- so
    // this changes no byte today; it is the order, made faithful.)
    std::vector<std::pair<uint32_t, uint32_t>> deferred_jump_slots;
    size_t patched_local_js = 0;
    auto patch_got_slot = [&](uint32_t vaddr, uint32_t value) -> bool {
        for (auto& seg : out->loadable) {
            if (vaddr < seg.vaddr) continue;
            size_t soff = vaddr - seg.vaddr;
            if (soff + 4 > seg.bytes.size()) continue;
            seg.bytes[soff + 0] = static_cast<uint8_t>(value);
            seg.bytes[soff + 1] = static_cast<uint8_t>(value >> 8);
            seg.bytes[soff + 2] = static_cast<uint8_t>(value >> 16);
            seg.bytes[soff + 3] = static_cast<uint8_t>(value >> 24);
            return true;
        }
        return false;
    };
    const Elf32Shdr* plt        = find_section_by_name(".plt");
    const Elf32Shdr* relplt     = find_section_by_name(".rel.plt");
    const Elf32Shdr* relaplt    = find_section_by_name(".rela.plt");
    if (plt && (relplt || relaplt)) {
        bool is_rela = (relaplt != nullptr);
        const Elf32Shdr* rel = is_rela ? relaplt : relplt;
        // Each rel entry: (r_offset:u32, r_info:u32) for REL,
        //                 (r_offset:u32, r_info:u32, r_addend:i32) for RELA.
        size_t entry_size = is_rela ? 12 : 8;
        size_t count = rel->sh_size / entry_size;
        // Map dynsym index to import-list index.
        std::unordered_map<uint32_t, size_t> dynidx_to_import;
        // Build by walking imports vector in dynsym order. We didn't
        // record dynsym index on the imports themselves, so do a fresh
        // pass: the imports vector's order matches dynsym iteration.
        // We re-walk dynsym and emit the same names, picking up indices.
        if (sym_section) {
            size_t entries = sym_section->sh_size / sizeof(Elf32Sym);
            size_t imp_i = 0;
            for (size_t i = 0; i < entries && imp_i < out->imports.size(); ++i) {
                if (i < dynsym_is_import.size() && dynsym_is_import[i]) {
                    dynidx_to_import[static_cast<uint32_t>(i)] = imp_i;
                    ++imp_i;
                }
            }
        }
        // Map each import's GOT slot (its JUMP_SLOT reloc r_offset) to
        // its PLT stub vaddr by DECODING the .plt, instead of assuming a
        // fixed stub stride. ARM PLT layout varies by linker: the classic
        // GNU form is a 20-byte PLT[0] + 12-byte entries, but lld emits a
        // 32-byte PLT[0] + 16-byte entries (the 0xd4-padded form -- e.g.
        // Mindustry's libarc, whose .plt has sh_entsize=16). The hardcoded
        // 20+12 stride mis-maps EVERY entry on the lld form: malloc@plt
        // resolved to the import 2 slots later (__memcpy_chk) -> a lifted
        // `bl malloc` actually called memmove(numBytes, jclass_handle, ...)
        // -> SIGSEGV reading the raw handle. Each import stub is
        // `add ip,pc,#a; add ip,ip,#b; ldr pc,[ip,#c]!`, whose GOT target
        // is (stub+8)+a+b+c; matching that against the reloc r_offset
        // (= the GOT slot) is layout-independent and needs no stride.
        std::unordered_map<uint32_t, uint32_t> got_to_plt;
        {
            auto dp_imm = [](uint32_t w) -> uint32_t {
                uint32_t imm8 = w & 0xFFu;
                uint32_t rot  = ((w >> 8) & 0xFu) * 2u;
                return rot ? ((imm8 >> rot) | (imm8 << (32u - rot))) : imm8;
            };
            for (uint32_t o = 0; o + 12u <= plt->sh_size; o += 4u) {
                uint32_t i0 = 0, i1 = 0, i2 = 0;
                if (!read_at(out->raw, plt->sh_offset + o,      &i0)) break;
                if (!read_at(out->raw, plt->sh_offset + o + 4u, &i1)) break;
                if (!read_at(out->raw, plt->sh_offset + o + 8u, &i2)) break;
                // add ip,pc,#a ; add ip,ip,#b ; ldr pc,[ip,#c]!
                if ((i0 & 0xFFFFF000u) != 0xe28fc000u) continue;
                if ((i1 & 0xFFFFF000u) != 0xe28cc000u) continue;
                if ((i2 & 0xFFFFF000u) != 0xe5bcf000u) continue;
                uint32_t stub = plt->sh_addr + o;
                uint32_t got  = stub + 8u + dp_imm(i0) + dp_imm(i1)
                              + (i2 & 0xFFFu);
                got_to_plt[got] = stub;
            }
        }
        // Apply a .rel.plt R_ARM_JUMP_SLOT whose symbol is DEFINED-LOCAL by
        // writing the symbol value into its GOT slot -- exactly what Bionic's
        // linker does. Without this the slot keeps the value the linker baked
        // in for LAZY binding, which is PLT[0] (the `_dl_runtime_resolve`
        // header). We have no dynamic linker, so the guest "calls" PLT[0] and
        // the call silently does nothing. Measured on unciv's libgdx.so:
        //     gdx2d_load: GOT@0x2ab40 = 0x00026710 (== PLT[0]), st_value 0x97cc
        // i.e. the correct answer was already sitting in the relocation.
        //
        // This is deliberately a RELOCATION fix, not a codegen fix. The old
        // `plt_local_redirects` path (retired 2026-09-27 with the
        // MACAPK_PLT_REDIRECT* knobs) rewrote `bl <plt_stub>` inside
        // Arm32Lifter at translate time, so it only ever fixed codegen for the
        // deleted LLVM emitter, covered only DIRECT calls, and under arm32rc
        // could only perturb discovery. Patching the GOT fixes every access path --
        // the stub's own `ldr pc,[ip,#c]!`, an indirect call through a taken
        // function pointer, and any code that reads the slot as data --
        // because it changes the guest image bytes rather than the emitted
        // code, which is what Bionic's linker does too (linker_relocate.cpp
        // process_relocation_impl, R_GENERIC_JUMP_SLOT: `*rel_target =
        // sym_addr + addend`, bound eagerly, no per-name policy).
        //
        // Scope note: IMPORTS are untouched (they keep the PLT-stub/shim
        // mechanism above), and operator new/delete are excluded below because
        // they are routed to the guest-heap shim on purpose.
        // (patch_got_slot / patched_local_js / deferred_jump_slots: hoisted
        // above, because the writes are applied after .rel.dyn.)
        // Kill switch for bisecting a regression back to this pass.
        static const bool local_js_off = [](){
            const char* e = std::getenv("MACAPK_NO_LOCAL_JUMPSLOT");
            return e && *e && *e != '0';
        }();
        for (size_t i = 0; i < count; ++i) {
            size_t off = rel->sh_offset + i * entry_size;
            uint32_t r_offset = 0, r_info = 0;
            if (!read_at(out->raw, off, &r_offset)) break;
            if (!read_at(out->raw, off + 4, &r_info)) break;
            uint32_t sym_idx = r_info >> 8;
            // Layout-independent: look the stub up by its GOT slot. Fall
            // back to the classic 20+12 stride only if the decode missed
            // this slot (e.g. a Thumb PLT or an unrecognized stub form),
            // which preserves the prior behavior for those rare cases.
            uint32_t plt_addr;
            auto pit = got_to_plt.find(r_offset);
            if (pit != got_to_plt.end()) {
                plt_addr = pit->second;
            } else {
                plt_addr = plt->sh_addr + ARM_PLT0_SIZE + i * ARM_PLT_ENTRY;
            }
            // Interworking PLT (GNU ld, Thumb-compiled library): some entries
            // are `bx pc ; nop` (Thumb 0x4778 0x46c0) followed by the three
            // ARM instructions, so the entry is reachable at TWO addresses: a
            // Thumb `bl` / `b.w` branches to the veneer (stub - 4) and a
            // `blx` or ARM `bl` to the ARM stub itself (binutils
            // elf32-arm.c elf32_arm_plt_thumb_stub = { 0x4778, 0x46c0 }). On a
            // device `bx pc` falls into the stub, so both mean the same
            // import. Only the stub used to be registered: a call through
            // the veneer ran the lifted veneer whose `ldr pc,[GOT]` read the
            // lazy slot (PLT0), or hit the unresolved-call panic stub and
            // returned 0 (libsqlcipher: free, pthread_mutex_lock/unlock ...,
            // so sqlite3_initialize and sqlite3_open failed).
            // The duplicate ImportedSymbol is appended AFTER every real
            // import (the vector is positional for dynidx_to_import and the
            // data-import sites), so no existing index moves.
            uint32_t veneer = 0;
            if (plt_addr >= plt->sh_addr + 4u) {
                uint32_t v = 0;
                if (read_at(out->raw, plt->sh_offset + (plt_addr - plt->sh_addr) - 4u, &v)
                    && v == 0x46c04778u)
                    veneer = plt_addr - 4u;
            }
            auto it = dynidx_to_import.find(sym_idx);
            if (it != dynidx_to_import.end() && it->second < out->imports.size()) {
                out->imports[it->second].plt_addr = plt_addr;
                if (veneer) {
                    ImportedSymbol dup = out->imports[it->second];
                    dup.plt_addr = veneer;
                    out->imports.push_back(std::move(dup));
                }
            } else if (sym_idx < dynsym_value_by_index.size()
                       && dynsym_value_by_index[sym_idx] != 0) {
                // .rel.plt JUMP_SLOT naming a DEFINED-LOCAL symbol -- an
                // intra-module PLT call (default-visibility PIC).
                const std::string& nm = sym_idx < dynsym_name_by_index.size()
                    ? dynsym_name_by_index[sym_idx] : std::string();
                // Operator new / delete (Itanium `_Znw*`/`_Zna*` new,
                // `_Zdl*`/`_Zda*` delete): route to the guest-heap SHIM via
                // a synthetic import rather than the lifted local body, which
                // mis-lifts (returns a bad pointer -> memcpy corrupts .text).
                // The size/ptr is always arg0 across all variants, so one
                // canonical name per family covers them.
                bool is_new = nm.rfind("_Znw", 0) == 0 || nm.rfind("_Zna", 0) == 0;
                bool is_del = nm.rfind("_Zdl", 0) == 0 || nm.rfind("_Zda", 0) == 0;
                if (is_new || is_del) {
                    ImportedSymbol is{};
                    is.name     = is_new ? "_Znwj" : "_ZdlPv";
                    is.plt_addr = plt_addr;
                    if (veneer) {
                        ImportedSymbol dup = is;
                        dup.plt_addr = veneer;
                        out->imports.push_back(std::move(dup));
                    }
                    out->imports.push_back(std::move(is));
                } else {
                    // Bind the GOT slot to the local definition: st_value +
                    // bias with bit 0 (the Thumb flag) kept, which is what
                    // Bionic writes (MACAPK_ARM32_BIONIC_THUMB_BIT above; =0
                    // writes `st_value & ~1` + bias, the pre-2026-09-28 word).
                    // Before that gate a Thumb slot got an EVEN word; see
                    // docs/plt-redirect-knobs-retired_2026-09-27.md section 4.
                    // v1.0.814: APPLY THE LOAD BIAS. This wrote the RAW
                    // st_value, which is only correct for the FIRST ARM32 .so
                    // in an APK (bias 0). The 2nd+ image gets a distinct
                    // per-image bias so its PT_LOADs do not collide, and every
                    // other consumer already accounts for it -- the
                    // R_ARM_RELATIVE path above does `addend + load_bias`, the
                    // GLOB_DAT/ABS32 path below does
                    // `dynsym_value_by_index[r_sym] + load_bias`, and
                    // arm32rc's module constructor registers each function in
                    // the indirect-call LUT at `vaddr + load_bias`. Only this
                    // one site omitted it, so the guest jumped to an UNBIASED
                    // address that the LUT could never contain.
                    //
                    // Confirmed on ShatteredPD: libgdx-freetype.so loads
                    // second with bias 0x30000, and its own PLT stub at raw
                    // 0xb6930 (`add ip,pc,#0,#12` / `add ip,ip,#8,#20` /
                    // `ldr pc,[ip,#0x524]!`) jumped to 0x3702c --
                    // FT_Init_FreeType's RAW vaddr -- while the function was
                    // registered at 0x3702c + 0x30000. Result:
                    // "indirect call to in-image vaddr 0x3702c" and a call
                    // that silently returns 0.
                    //
                    // A load bias is page-aligned, so adding it never
                    // disturbs bit 0.
                    if (!local_js_off) {
                        deferred_jump_slots.emplace_back(
                            r_offset,
                            (bionic_thumb_bit ? dynsym_raw_value_by_index[sym_idx]
                                              : dynsym_value_by_index[sym_idx])
                                + load_bias);
                    }
                    // v1.0.808: record the binding UNCONDITIONALLY, for every
                    // library, with no policy applied. Patching
                    // the GOT slot only helps a caller that EXECUTES the guest
                    // `.plt` bytes; arm32rc resolves branch targets statically
                    // at translate time and never runs them, so without this it
                    // cannot tell an intra-module stub from an unknown address
                    // and emits a panic stub that returns 0.
                    // NOTE: deliberately RAW (unbiased), unlike the GOT patch
                    // above. This list is consumed at TRANSLATE time by
                    // arm32rc_driver, which looks the target up in
                    // `vaddr_to_sym` -- a map keyed by the discovery pass's
                    // RAW ELF vaddrs. Adding the bias here to "match" the GOT
                    // patch would make every lookup miss. Two consumers, two
                    // address spaces; do not unify them.
                    out->plt_local_bindings.emplace_back(
                        plt_addr, dynsym_value_by_index[sym_idx]);
                    if (veneer)
                        out->plt_local_bindings.emplace_back(
                            veneer, dynsym_value_by_index[sym_idx]);
                }
            }
        }
    }

    // .init_array: pointer-sized array of C++ static-constructor
    // function vaddrs. The dynamic linker on real Android runs them
    // at dlopen time. Our translator parses the section and the
    // module ctor in the lifted dylib calls each one in order so
    // cocos2d-x's CCPoolManager / CCDirector / etc. singletons get
    // properly initialized before the JNI methods that use them run.
    //
    // We also pick up `.init` (the legacy single _init function),
    // again called once at startup, and `.preinit_array` which the
    // dynamic linker runs before .init_array (rare but cheap).
    auto collect_array = [&](const char* name,
                             std::vector<uint32_t>* out_vec) {
        const Elf32Shdr* sh = find_section_by_name(name);
        if (!sh) return;
        size_t count = sh->sh_size / 4;
        out_vec->reserve(count);
        for (size_t i = 0; i < count; ++i) {
            uint32_t v = 0;
            if (!read_at(out->raw, sh->sh_offset + i * 4, &v)) break;
            // 0 / 0xFFFFFFFF are sentinels meaning "no constructor";
            // skip them so we don't try to lift garbage.
            if (v == 0 || v == 0xFFFFFFFFu) continue;
            out_vec->push_back(v & ~1u);     // strip Thumb bit
        }
    };
    collect_array(".preinit_array", &out->init_funcs);
    collect_array(".init_array",    &out->init_funcs);

    // ---- .ARM.exidx: authoritative function-start table -------------
    //
    // The ARM EHABI exception index (SHT_ARM_EXIDX = 0x70000001) holds
    // one 8-byte entry per function that has unwind info: word0 is a
    // PREL31 self-relative offset to the function start, word1 is the
    // unwind descriptor (0x1 = EXIDX_CANTUNWIND, else inline/extab).
    // Ref: "Exception Handling ABI for the ARM Architecture" (IHI0038),
    // section 5 "Index table entries" + 4.1 PREL31.
    //
    // This is the ONLY complete function-boundary table that survives
    // stripping: a stripped C++ .so (cocos2d-x libgame.so, libarc.so)
    // keeps .ARM.exidx but loses .symtab. The lifter's other discovery
    // roots -- exported JNI symbols, .init_array, relocation/PC-rel
    // function-pointer seeds, and BL-target recursion -- never reach a
    // function that is called ONLY through a C++ vtable / function
    // pointer whose slot is written at runtime (not by a static
    // relocation). Those become "unresolved panic stub" call targets
    // that silently return 0. HCR 1.43's nativeSetPaths reaches such a
    // function at 0x2db6d4 (a real Thumb body, never lifted), so the
    // asset-path setup is corrupted and the subsequent nativeInit hands
    // a bad jstring to the JVM -> SIGSEGV in java_lang_String::utf8_length.
    //
    // Seeding every exidx function start closes that gap generically.
    // We claim Thumb (low bit set) because the NEON/Thumb-2 corpus is
    // Thumb-dominant; an ARM-mode entry mis-claimed as Thumb fails the
    // downstream "preceded by a function terminator in the claimed mode"
    // filter (main.cpp) and is simply not lifted -- same as today, so no
    // regression. We push to data_fnptr_seeds (NOT authoritative) so the
    // mode-validating + inside-known-body filters still run.
    //
    // GATED (default OFF) behind MACAPK_EXIDX_DISCOVERY. exidx is a
    // BROAD net: it seeds EVERY function with an unwind entry, including
    // the many that are never actually called, which roughly 2x's the
    // lifted IR for large engines (GD Lite libcocos2dcpp 2.6 GB -> ~5 GB,
    // libunity worse). That cost is real (translate time + peak disk on
    // disk-constrained hosts) and -- on its own -- unblocks nothing
    // (HCR 1.43 still hits a separate pointer-corruption bug deeper in
    // boot). Until the discovery is refined to seed only REACHABLE
    // functions (so the dead-function bloat goes away), this stays an
    // opt-in diagnostic so it cannot regress the working render corpus.
    // To make it default-on: add reachability pruning, then validate the
    // protected render sweep is flat.
    // ---- exidx VALIDATION set (always parsed, never seeds) -----------
    // Decoding the table costs one pass over ~8 KB and gives every address
    // the linker considered a function start. That is a cheap veto against
    // heuristic false positives even when the expensive exidx SEEDING below
    // stays off. See Elf32Image::exidx_fn_starts for why this is needed
    // (HCR 1.43's 0x204704, an ARM leaf mis-flagged as literal-pool data).
    {
        const Elf32Shdr* ex = find_section(0x70000001u /* SHT_ARM_EXIDX */);
        if (!ex) ex = find_section_by_name(".ARM.exidx");
        const Elf32Shdr* tx = find_section_by_name(".text");
        if (ex && ex->sh_size >= 8) {
            const uint32_t lo = tx ? tx->sh_addr : 0;
            const uint32_t hi = tx ? (tx->sh_addr + tx->sh_size) : 0xffffffffu;
            const size_t   cn = ex->sh_size / 8;
            for (size_t k = 0; k < cn; ++k) {
                uint32_t w0 = 0;
                if (!read_at(out->raw, ex->sh_offset + k * 8, &w0)) break;
                // PREL31: 31-bit signed, self-relative to the entry's OWN
                // address (IHI0038 section 4.1). Bit 31 is reserved.
                const uint32_t entry_va = ex->sh_addr +
                                          static_cast<uint32_t>(k * 8);
                int32_t rel = static_cast<int32_t>(w0 & 0x7fffffffu);
                if (rel & 0x40000000) rel -= 0x80000000;
                const uint32_t fn =
                    (entry_va + static_cast<uint32_t>(rel)) & ~1u;
                if (fn >= lo && fn < hi) {
                    out->exidx_fn_starts.insert(fn);
                    // Word 1 (IHI0038 ehabi32, "Index table entries"): 1 =
                    // EXIDX_CANTUNWIND; bit 31 set = inline compact unwind
                    // data; bit 31 clear and != 1 = prel31 offset to an
                    // .ARM.extab entry (long unwind opcodes / personality
                    // routine + LSDA). arm_reach_trim (main.cpp) uses it as an
                    // EH-aware floor. Unreadable word 1 reads as CANTUNWIND.
                    uint32_t w1 = 1;
                    (void)read_at(out->raw, ex->sh_offset + k * 8 + 4, &w1);
                    out->exidx_entries[fn] = w1;
                }
            }
        }
    }

    if (std::getenv("MACAPK_EXIDX_DISCOVERY")) {
        const Elf32Shdr* exidx = find_section(0x70000001u /* SHT_ARM_EXIDX */);
        if (!exidx) exidx = find_section_by_name(".ARM.exidx");
        const Elf32Shdr* text = find_section_by_name(".text");
        if (exidx && exidx->sh_size >= 8) {
            uint32_t tlo = text ? text->sh_addr : 0;
            uint32_t thi = text ? (text->sh_addr + text->sh_size) : 0xffffffffu;
            size_t   n   = exidx->sh_size / 8;
            std::unordered_set<uint32_t> seen_exidx;
            size_t added = 0, arm_seeds = 0;

            // Detect ARM vs Thumb per exidx entry. exidx PREL31 offsets are
            // EVEN (no ISA bit), so the old code blindly claimed Thumb
            // (fn | 1). That is WRONG for ARM-default builds: HCR 1.43 (2013
            // GCC) is ~49% ARM, and an exidx-ONLY-discovered ARM function
            // (no relocation/BL seed to override the mode) then gets lifted
            // as THUMB -> garbage instruction stream -> garbage branches/calls
            // into literal-pool data (e.g. 0xe4de0 = TranslationLanguage::
            // push_back, ARM `push {r4,lr}; sub sp,#216`, was decoded Thumb
            // as `ands r0,r2; beq 0xe4d98` -> blx into the 0x15555555 div-
            // magic word -> the layer-3 GC-time SEGV). Reloc/BL-discovered
            // functions were unaffected (their authoritative mode wins the
            // sort), which is why this hid behind the symptom functions.
            //
            // Classifier: prologue fast-paths (strong, cheap priors) then a
            // CAPSTONE VALIDITY-VOTE for everything else -- decode a window in
            // BOTH modes and pick the one that decodes further with fewer
            // rare/coprocessor/SVC ops (those flood the WRONG mode: a Thumb
            // body read as 4-byte ARM words yields svc/mrc2/cdp garbage; a
            // mis-aligned ARM body read as Thumb hits undecodable sooner). An
            // earlier AL-dominance heuristic was tried + REVERTED (Thumb-2
            // high halfwords are 0xE/0xF -> it over-classified ~30% of Thumb
            // as ARM). The vote correctly classifies non-prologue ARM (e.g.
            // 0x1280b4 = `cmp r0,r1`) AND keeps genuine Thumb (0x2da0dc =
            // push.w, whose ARM decode is svc/mrc2 garbage) as Thumb.
            csh cs_arm = 0, cs_thumb = 0;
            bool cs_ok = (cs_open(CS_ARCH_ARM, CS_MODE_ARM, &cs_arm) == CS_ERR_OK)
                      && (cs_open(CS_ARCH_ARM, CS_MODE_THUMB, &cs_thumb) == CS_ERR_OK);
            // Detail ON so the quality vote can inspect operands (return regs,
            // branch condition + target). Classifier runs once per exidx fn
            // (~8k) -- the cost is negligible and it is exidx-gated anyway.
            if (cs_ok) {
                cs_option(cs_arm,   CS_OPT_DETAIL, CS_OPT_ON);
                cs_option(cs_thumb, CS_OPT_DETAIL, CS_OPT_ON);
            }
            // No SKIPDATA: decode STOPS at the first undecodable byte.
            //
            // mode_quality: a STRUCTURAL "does this byte window look like a real
            // function body in mode H" score, NOT a raw instruction count. The
            // old count metric had a fatal bias: Thumb (2-byte) decodes ~2x as
            // many instructions as ARM (4-byte) in the same window, so whenever
            // BOTH modes decoded cleanly (common -- ARM code is often valid-ish
            // as Thumb) Thumb ALWAYS won. That mis-classified leaf/guarded ARM
            // functions as Thumb -> lifted as garbage that emitted spurious
            // branches into mid-instruction addresses (HCR 1.43: 0x22d714,
            // 0x20f7b0 -> bogus `b 0x22ddba`/`b 0x20f2d6` -> 0-returns nulled
            // fields -> the 0x1d005c null-vtable call -> nativeInit SEGV -> no
            // scene). The structural score is mode-agnostic:
            //   + bytes covered before the first undecodable byte (unbiased: a
            //     full clean decode scores the window size in EITHER mode),
            //   + a bonus for reaching a clean RETURN (bx lr / pop{..,pc} /
            //     ldm{..,pc} / mov pc,lr) -- a real function body ends in one,
            //   - a penalty per UNCONDITIONAL branch whose target lands OUTSIDE
            //     [fn, fn+window] (wrong-mode garbage sprays these; a real body
            //     keeps intra-fn branches in-range),
            //   - the rare/coprocessor/SVC penalty (kept; strong wrong-mode tell).
            // Decoding stops at the first clean return (we don't score past the
            // function into its neighbors). base=fn so branch targets are absolute.
            auto mode_quality = [](csh h, const uint8_t* code, size_t nb,
                                   uint32_t fn) -> int {
                cs_insn* insn = nullptr;
                size_t cnt = cs_disasm(h, code, nb, fn, 64, &insn);
                int bytes = 0, rare = 0, wild = 0; bool ret = false;
                for (size_t k = 0; k < cnt; ++k) {
                    cs_insn& I = insn[k];
                    bytes += I.size;
                    switch (I.id) {
                        case ARM_INS_SVC:  case ARM_INS_CDP: case ARM_INS_CDP2:
                        case ARM_INS_MCR:  case ARM_INS_MCR2:
                        case ARM_INS_MRC:  case ARM_INS_MRC2:
                        case ARM_INS_MCRR: case ARM_INS_MRRC:
                        case ARM_INS_LDC:  case ARM_INS_STC:
                        case ARM_INS_HVC:  case ARM_INS_SMC:
                            ++rare; break;
                        default: break;
                    }
                    const auto* d = I.detail ? &I.detail->arm : nullptr;
                    // Clean return -> a real function body ends here. Stop.
                    if (I.id == ARM_INS_BX && d && d->op_count == 1 &&
                        d->operands[0].type == ARM_OP_REG &&
                        d->operands[0].reg == ARM_REG_LR) { ret = true; break; }
                    if ((I.id == ARM_INS_POP || I.id == ARM_INS_LDM) && d) {
                        for (int o = 0; o < d->op_count; ++o)
                            if (d->operands[o].type == ARM_OP_REG &&
                                d->operands[o].reg == ARM_REG_PC) { ret = true; break; }
                        if (ret) break;
                    }
                    if (I.id == ARM_INS_MOV && d && d->op_count == 2 &&
                        d->operands[0].type == ARM_OP_REG &&
                        d->operands[0].reg == ARM_REG_PC &&
                        d->operands[1].type == ARM_OP_REG &&
                        d->operands[1].reg == ARM_REG_LR) { ret = true; break; }
                    // Unconditional branch to OUTSIDE the window = wrong-mode tell.
                    if ((I.id == ARM_INS_B || I.id == ARM_INS_BX) && d &&
                        d->cc == ARM_CC_AL && d->op_count == 1 &&
                        d->operands[0].type == ARM_OP_IMM) {
                        uint32_t tgt = (uint32_t)d->operands[0].imm;
                        if (tgt < fn || tgt >= fn + (uint32_t)nb) ++wild;
                    }
                }
                if (insn) cs_free(insn, cnt);
                return bytes + (ret ? 16 : 0) - 4 * wild - 8 * rare;
            };
            auto exidx_is_thumb = [&](uint32_t fn) -> bool {
                if (!text) return true;
                size_t foff = (size_t)(fn - text->sh_addr)
                            + (size_t)text->sh_offset;
                if (foff + 2 > out->raw.size()) return true;
                const uint8_t* win = out->raw.data() + foff;
                size_t avail = out->raw.size() - foff;
                if (thi > fn && (uint32_t)(thi - fn) < avail) avail = thi - fn;
                if (avail > 64) avail = 64;
                uint16_t hw0 = (uint16_t)(win[0] | (win[1] << 8));
                // Strong Thumb-16 prologue -> Thumb.
                if ((hw0 & 0xFF00u) == 0xB500u || (hw0 & 0xFF00u) == 0xB400u ||
                    (hw0 & 0xFF80u) == 0xB080u || (hw0 & 0xFF00u) == 0xAF00u)
                    return true;
                bool aligned4 = ((fn & 3u) == 0);
                // A real ARM `push` prologue (stmdb sp!,{...}) SAVES LR and never
                // includes SP or PC (a store-multiple of SP/PC is UNPREDICTABLE,
                // and no compiler emits it). A Thumb body misdecoded as ARM can
                // coincidentally form an stmdb-sp! whose reglist includes SP/PC
                // or omits LR -- e.g. 0x2dacdc `e92d6a03` = `push {r0,r1,r9,r11,
                // sp,lr}` is really the Thumb `ldr r3,[r0,#0x20]; push.w {r4-r8,
                // lr}` of libstdc++ __convert_to_v (hit when HCR parses mission
                // floats). The original mask matched ANY push and so mis-voted
                // this Thumb fn as ARM -> garbage svc/cdp2 -> SIGTRAP. Gate the
                // push fast-path on a plausible prologue reglist; implausible
                // pushes fall through to the structural quality vote (which
                // correctly picks Thumb here).
                auto plausible_arm_push = [](uint32_t reglist) -> bool {
                    return (reglist & (1u << 14))    // LR saved
                        && !(reglist & (1u << 13))   // SP not pushed
                        && !(reglist & (1u << 15));  // PC not pushed
                };
                if (aligned4 && avail >= 4) {
                    uint32_t w = (uint32_t)win[0] | ((uint32_t)win[1] << 8)
                               | ((uint32_t)win[2] << 16) | ((uint32_t)win[3] << 24);
                    // Strong ARM prologue (word[0]) -> ARM.
                    if (((w & 0x0FFF0000u) == 0x092D0000u            // push
                         && plausible_arm_push(w & 0xFFFFu)) ||
                        (w & 0x0FFFFFFFu) == 0x052DE004u ||   // str lr,[sp,#-4]!
                        (w & 0x0FFFF000u) == 0x024DD000u ||   // sub sp,sp,#imm
                        (w & 0x0FFFFFFFu) == 0x01A0C00Du)     // mov ip, sp
                        return false;
                }
                // Extended ARM-prologue scan (decode a few ARM insns): many
                // ARM functions begin with a guard (`cmp/tst` + cond-branch)
                // or a mov BEFORE the push/sub-sp prologue, so the word[0]
                // fast-path above misses them. Without this, the validity
                // vote below favors Thumb purely because Thumb (2-byte)
                // decodes ~2x as many instructions as ARM (4-byte) in the
                // same window -- a COUNT BIAS. HCR 1.43 motivating case:
                // 0x22d714 (`cmp r0,#1; push {r4,lr}; bne ...`, real ARM,
                // called 3x by applicationDidFinishLaunching) scored Thumb
                // and lifted as garbage (`movs r1,r0; b #0x22ddba`) -> the
                // bogus 0-return nulled a field -> the 0x1d005c null-vtable
                // call -> nativeInit SEGV -> no scene -> blank menu. If a
                // genuine `push {...}` (ARM_INS_PUSH = the e92d... prologue
                // store) appears within the first few ARM instructions, this
                // is ARM. High precision: a Thumb body decoded as ARM rarely
                // yields ARM_INS_PUSH that early, and real Thumb push
                // prologues were already caught by the Thumb-16 fast-path
                // above. cs_arm has no detail enabled, so we key on the
                // instruction id only (PUSH), which Capstone reports for the
                // canonical `push {..., lr}` encoding.
                if (cs_ok && aligned4 && avail >= 8) {
                    cs_insn* ai = nullptr;
                    size_t ac = cs_disasm(cs_arm, win, avail, 0x10000u, 5, &ai);
                    bool arm_prologue = false;
                    for (size_t k = 0; k < ac; ++k) {
                        if (ai[k].id == ARM_INS_PUSH) {
                            // Only a PLAUSIBLE prologue push (saves LR, omits
                            // SP/PC) counts as ARM. A Thumb body misdecoded as
                            // ARM can yield an stmdb-sp! whose reglist includes
                            // SP/PC or omits LR (e.g. 0x2dacdc e92d6a03); that is
                            // not a real prologue, so fall through to the vote.
                            const auto* dd = ai[k].detail ? &ai[k].detail->arm
                                                          : nullptr;
                            bool has_lr = false, has_sp = false, has_pc = false;
                            if (dd) for (int o = 0; o < dd->op_count; ++o)
                                if (dd->operands[o].type == ARM_OP_REG) {
                                    unsigned rg = dd->operands[o].reg;
                                    has_lr |= (rg == ARM_REG_LR);
                                    has_sp |= (rg == ARM_REG_SP);
                                    has_pc |= (rg == ARM_REG_PC);
                                }
                            if (dd && has_lr && !has_sp && !has_pc)
                                arm_prologue = true;
                            break;
                        }
                        // Stop at the first control-flow transfer: a real
                        // prologue precedes any branch/return, so a PUSH found
                        // only AFTER a branch is a different basic block (or
                        // garbage), not this function's prologue.
                        if (ai[k].id == ARM_INS_B   || ai[k].id == ARM_INS_BX ||
                            ai[k].id == ARM_INS_BL  || ai[k].id == ARM_INS_BLX ||
                            ai[k].id == ARM_INS_POP)
                            break;
                    }
                    if (ai) cs_free(ai, ac);
                    if (arm_prologue) return false;
                }
                // Structural quality vote. ARM only if 4-aligned (ARM is always
                // 4-aligned). On a tie, prefer Thumb (the historical default;
                // the broad exidx net is Thumb-dominant and Thumb-as-ARM
                // garbage is the more dangerous mis-lift).
                if (cs_ok) {
                    int as = (aligned4) ? mode_quality(cs_arm, win, avail, fn) : -100000;
                    int ts = mode_quality(cs_thumb, win, avail, fn);
                    return as > ts ? false : true;   // ARM only if it scores higher
                }
                return true;   // no Capstone -> conservative default Thumb
            };

            for (size_t i = 0; i < n; ++i) {
                uint32_t w0 = 0;
                if (!read_at(out->raw, exidx->sh_offset + i * 8, &w0)) break;
                if (w0 == 0) continue;
                // PREL31 sign-extend (bit 30 is the sign); the value is
                // self-relative to THIS entry's vaddr.
                int32_t  off = ((int32_t)(w0 << 1)) >> 1;
                uint32_t entry_va = exidx->sh_addr + (uint32_t)(i * 8);
                uint32_t fn = entry_va + (uint32_t)off;
                fn &= ~1u;                       // exidx stores the even address
                if (text && (fn < tlo || fn >= thi)) continue;
                if (!seen_exidx.insert(fn).second) continue;
                bool is_thumb = exidx_is_thumb(fn);
                uint32_t seed = fn | (is_thumb ? 1u : 0u);
                out->data_fnptr_seeds.push_back(seed);
                // exidx IS the canonical function-start table (one entry per
                // unwindable function), so trust it as AUTHORITATIVE -- bypass
                // main.cpp's heuristic terminator-precedence filter, which
                // wrongly rejects real exidx functions that are PRECEDED BY A
                // LITERAL POOL rather than a ret/b/bx (e.g. 0x126428 =
                // random_shuffle's `lrand48()%n` RNG functor, preceded by a
                // pointer pool -> the call to it fell back to a panic stub and
                // the shuffle ran on garbage). The is_in_literal_pool check
                // (does the ADDRESS itself sit in a pool) still applies to
                // every seed, so this can't admit a pool word as a function.
                out->authoritative_fnptr_seeds.push_back(seed);
                if (!is_thumb) ++arm_seeds;
                ++added;
            }
            if (cs_arm)   cs_close(&cs_arm);
            if (cs_thumb) cs_close(&cs_thumb);
            if (added) {
                std::fprintf(stderr,
                    "[elf_reader] .ARM.exidx: seeded %zu function start(s) "
                    "(of %zu entries; %zu ARM, %zu Thumb) for discovery\n",
                    added, n, arm_seeds, added - arm_seeds);
            }
        }
    }

    // ---- .rel.dyn: apply R_ARM_GLOB_DAT and R_ARM_ABS32 to import
    // GOT/.data slots --------------------------------------------------
    //
    // ARM ELF relocation types we care about here:
    //   R_ARM_RELATIVE  (23): *ptr = load_base + *ptr. With load_base=0
    //                          the value at *ptr is already absolute, so
    //                          we skip these entries (no-op for our
    //                          segment-loading layout).
    //   R_ARM_GLOB_DAT  (21): *ptr = symbol_value. For imports the
    //                          symbol_value is undefined in the .so, so
    //                          we substitute the import's PLT stub vaddr
    //                          (the trampoline our translator wires up
    //                          for direct BL imports). When lifted code
    //                          later does `LDR Rx, [Ry]; BLX Rx` through
    //                          the GOT slot, Rx ends up = plt_vaddr and
    //                          the indirect-call dispatcher resolves it
    //                          via the runtime LUT to the host shim.
    //                          Without this fix, GOT slots stay at 0 and
    //                          BLX 0 silently fails -- the most visible
    //                          symptom in cocos2d-x is malloc/free/glXxx
    //                          calls through the GOT returning garbage.
    //   R_ARM_ABS32     (2):  *ptr = *ptr + symbol_value. For imports the
    //                          file's pre-populated value is the addend
    //                          (typically 0); same treatment as GLOB_DAT.
    //
    // Both relocation types reference dynsym entries; we look up the
    // import name there and find the matching PLT vaddr. Data symbols
    // (e.g. __stack_chk_guard, __sF, another library's typeinfo) have
    // plt_addr == 0 and aren't patched here: their definer is another
    // image, known only at load time. They are recorded as DataImportSites
    // and resolved by the runtime linker (bionic_shim_arm32_link.cpp,
    // MACAPK_ARM32_DATA_IMPORT_LINK); `_ctype_` / `_toupper_tab_` /
    // `_tolower_tab_` keep the synthetic segment below.
    {
        const Elf32Shdr* rel_dyn = find_section_by_name(".rel.dyn");
        const Elf32Shdr* rela_dyn = find_section_by_name(".rela.dyn");
        const Elf32Shdr* rel = rel_dyn ? rel_dyn : rela_dyn;
        if (rel && sym_section) {
            bool is_rela = (rela_dyn != nullptr);
            size_t entry_size = is_rela ? 12 : 8;
            size_t count = rel->sh_size / entry_size;
            // Map dynsym index -> import index (same iteration order
            // we used to populate dynidx_to_import for .rel.plt above).
            std::unordered_map<uint32_t, size_t> dyn_to_imp;
            size_t dyn_entries = sym_section->sh_size / sizeof(Elf32Sym);
            size_t imp_i = 0;
            for (size_t i = 0; i < dyn_entries
                              && imp_i < out->imports.size(); ++i) {
                if (i < dynsym_is_import.size() && dynsym_is_import[i]) {
                    dyn_to_imp[static_cast<uint32_t>(i)] = imp_i;
                    ++imp_i;
                }
            }
            // Helper to write a 32-bit value into the loaded segment
            // bytes at the given vaddr. Walks `out->loadable` to find
            // the segment, then patches its bytes. Returns true on
            // success.
            auto patch_seg = [&](uint32_t vaddr, uint32_t value) -> bool {
                for (auto& seg : out->loadable) {
                    if (vaddr < seg.vaddr) continue;
                    size_t off = vaddr - seg.vaddr;
                    if (off + 4 > seg.bytes.size()) continue;
                    seg.bytes[off + 0] = static_cast<uint8_t>(value);
                    seg.bytes[off + 1] = static_cast<uint8_t>(value >> 8);
                    seg.bytes[off + 2] = static_cast<uint8_t>(value >> 16);
                    seg.bytes[off + 3] = static_cast<uint8_t>(value >> 24);
                    return true;
                }
                return false;
            };
            // The word currently at `vaddr` in the loaded segment bytes.
            auto read_seg = [&](uint32_t vaddr, uint32_t* value) -> bool {
                for (const auto& seg : out->loadable) {
                    if (vaddr < seg.vaddr) continue;
                    size_t o = vaddr - seg.vaddr;
                    if (o + 4 > seg.bytes.size()) continue;
                    *value = static_cast<uint32_t>(seg.bytes[o])
                           | (static_cast<uint32_t>(seg.bytes[o + 1]) << 8)
                           | (static_cast<uint32_t>(seg.bytes[o + 2]) << 16)
                           | (static_cast<uint32_t>(seg.bytes[o + 3]) << 24);
                    return true;
                }
                return false;
            };
            // MACAPK_ARM32_BIONIC_DATA_RELOC (default ON, v1.0.1653): write
            // what Bionic's linker writes for a data relocation against a
            // symbol. (1) Apply the ADDEND the way it does
            // (bionic/linker/linker_relocate.cpp, android14-release):
            //   :227-231  REL:  get_addend_rel() = *rel_target (the in-place
            //                   word), get_addend_norel() = 0;
            //             RELA: both = reloc.r_addend
            //   :326-331  R_GENERIC_ABSOLUTE (R_ARM_ABS32):
            //                   result = sym_addr + get_addend_rel()
            //   :333-341  R_GENERIC_GLOB_DAT (R_ARM_GLOB_DAT):
            //                   result = sym_addr + get_addend_norel()
            // The old code wrote sym (+ B) for both and DROPPED the addend.
            // That is only right when the addend is 0, and it is not for C++
            // typeinfo: every `__si_class_type_info` / `__vmi_class_type_info`
            // object's vptr is R_ARM_ABS32 against the defined vtable symbol
            // with an in-place addend of 8 (the address point, past
            // offset-to-top and the typeinfo slot). Dropped, the vptr points
            // at the start of the vtable, so every virtual call libc++abi
            // makes on a type_info (search_below_dst, can_catch, ...) lands
            // two slots early -- and a translated __dynamic_cast recurses
            // until the host stack overflows (measured by the dynamic_cast
            // retirement review, 2026-09-27: 168 of 1,326 casts on
            // libc++_shared.so). libsupc++ images (HCR, GD Lite, JJ) carry no
            // such relocations; every NDK r18+ libc++abi image carries 95 to
            // 2,145 of them.
            // (2) Use the symbol's st_value EXACTLY (sym_addr = st_value +
            // load_bias; Bionic masks nothing). v1.0.1653 kept bit 0 masked
            // for an STT_FUNC target; since 2026-09-28 that is kept too
            // (MACAPK_ARM32_BIONIC_THUMB_BIT, declared before the .rel.plt
            // pass). The mask came from dynsym_value_by_index, which
            // masked it for every symbol and so put each C++ typeinfo NAME
            // pointer (`_ZTS*`, a byte-aligned string, often at an odd
            // address) one byte early: 107 of 1,963 such sites on pk2's
            // libc++_shared.so. libc++abi compares non-unique RTTI by strcmp
            // of those names.
            // =0 restores the old write (masked S, addend dropped).
            static const bool rel_addend = [] {
                const char* e = std::getenv("MACAPK_ARM32_BIONIC_DATA_RELOC");
                return !(e && e[0] == '0' && e[1] == '\0');
            }();
            // The addend Bionic would add for this entry (see above).
            auto bionic_addend = [&](uint32_t type, uint32_t where,
                                     uint32_t rela_add) -> uint32_t {
                if (!rel_addend) return 0;
                if (is_rela) return rela_add;
                if (type != 2 /*R_ARM_ABS32*/) return 0;   // GLOB_DAT: norel = 0
                uint32_t a = 0;
                return read_seg(where, &a) ? a : 0;
            };
            size_t patched_glob_dat = 0;
            size_t patched_abs32    = 0;
            size_t patched_addend   = 0;   // entries whose addend was non-zero
            size_t skipped_no_plt   = 0;
            // Legacy Bionic case-conversion DATA imports. Pre-Lollipop
            // bionic ctype.h (android.googlesource.com/platform/bionic,
            // libc/include/ctype.h before API 21) declares
            //     extern const short *_tolower_tab_;
            //     extern const short *_toupper_tab_;
            // and expands the tolower()/toupper() MACROS to
            // `(_tolower_tab_ + 1)[c]` / `(_toupper_tab_ + 1)[c]`, so any
            // .so built against an old NDK sysroot has the table
            // dereference INLINED into its own .text and imports the
            // pointer variable as an UND OBJECT symbol resolved through an
            // R_ARM_GLOB_DAT GOT slot (verified on HCR 1.8.1's libgame.so:
            // `00302348 0005e815 R_ARM_GLOB_DAT ... _toupper_tab_`).
            // Those are DATA imports, so plt_addr == 0 and the slot used to
            // be left at 0: the guest then loaded the "pointer" from guest
            // address 0 (the ELF header bytes) and indexed a nonsense
            // address, silently mis-folding every character. We record the
            // slots here and materialise the real tables below.
            std::vector<uint32_t> toupper_got_slots;
            std::vector<uint32_t> tolower_got_slots;
            // `_ctype_` (the same pre-API-21 ctype.h family: kitkat-release
            // libc/include/ctype.h:56 `extern const char *_ctype_;`, and every
            // inline is<x>() is `(_ctype_ + 1)[(unsigned char)c] & _CTYPE_<x>`,
            // :104-154). Same two-level shape as the case tables below. Left at
            // 0 it read the ELF header as the table, so every inline isdigit /
            // isspace / isalpha answered false (19 corpus libs, HCR 1.8.1 and
            // GD Lite among them; docs/arm32-data-import-resolution-design_2026-09-27.md).
            // Pairs of (slot, Bionic addend).
            std::vector<std::pair<uint32_t, uint32_t>> ctype_got_slots;
            // The same three names' sites as DataImportSites (Bionic addend,
            // import index), kept in case the synthetic segment below cannot
            // be placed inside this image's bias band and the runtime linker
            // has to provide the tables instead (MACAPK_ARM32_DATA_IMPORT_LINK).
            std::vector<DataImportSite> ctype_family_sites;
            // Bionic's addend for a data relocation, computed UNCONDITIONALLY
            // (linker_relocate.cpp android14-release :227-231: REL
            // get_addend_rel() = *rel_target, get_addend_norel() = 0; RELA both
            // = r_addend; :326-341 ABS32 adds get_addend_rel(), GLOB_DAT adds
            // get_addend_norel()). DataImportSite carries this so the runtime
            // linker can write S + A no matter what MACAPK_ARM32_BIONIC_DATA_RELOC
            // says about DEFINED-symbol writes.
            auto site_addend = [&](uint32_t type, uint32_t where,
                                   uint32_t rela_add) -> uint32_t {
                if (is_rela) return rela_add;
                if (type != 2 /*R_ARM_ABS32*/) return 0;
                uint32_t a = 0;
                return read_seg(where, &a) ? a : 0;
            };
            static const bool ctype_table_on = [] {
                const char* e = std::getenv("MACAPK_ARM32_CTYPE_TABLE");
                return !(e && e[0] == '0' && e[1] == '\0');
            }();
            // Build a quick "is this vaddr inside an executable PT_LOAD"
            // predicate so we can decide whether an R_ARM_RELATIVE entry
            // points at code (function pointer) or data.
            auto is_exec_vaddr = [&](uint32_t vaddr) {
                for (const auto& seg : out->loadable) {
                    if (!seg.executable) continue;
                    if (vaddr < seg.vaddr) continue;
                    if (vaddr >= seg.vaddr + seg.bytes.size()) continue;
                    return true;
                }
                return false;
            };
            // v1.0.553: SECTION-aware code check. The executable PT_LOAD also
            // contains .rodata, so the segment-level predicate above admits
            // DATA addresses as function-pointer seeds. That seeded bogus
            // Thumb "functions" inside .rodata (e.g. 0x133aa in ShatteredPD's
            // libgdx-freetype.so); their garbage decodes recorded extern
            // branch targets (a phantom `b 0x93d10`) that entered discovery
            // as anticipated function starts and TRUNCATED real .text
            // functions mid-body (load_truetype_glyph -> 8 insns -> FreeType
            // glyph loads returned stack garbage -> no text). A function
            // entry must live in a section with SHF_EXECINSTR (.text/.plt);
            // fall back to the segment predicate when the section table is
            // stripped or carries no exec sections.
            const bool have_code_sections = [&]() {
                for (const auto& sh : out->shdrs)
                    if ((sh.sh_flags & 0x4u) && (sh.sh_flags & 0x2u) &&
                        sh.sh_size > 0)
                        return true;
                return false;
            }();
            auto is_code_vaddr = [&](uint32_t vaddr) {
                if (!have_code_sections) return is_exec_vaddr(vaddr);
                for (const auto& sh : out->shdrs) {
                    if (!(sh.sh_flags & 0x4u)) continue;   // SHF_EXECINSTR
                    if (!(sh.sh_flags & 0x2u)) continue;   // SHF_ALLOC
                    if (vaddr < sh.sh_addr) continue;
                    if (vaddr >= sh.sh_addr + sh.sh_size) continue;
                    return true;
                }
                return false;
            };
            std::unordered_set<uint32_t> seen_fnptr_seed;
            for (size_t i = 0; i < count; ++i) {
                size_t off = rel->sh_offset + i * entry_size;
                uint32_t r_offset = 0, r_info = 0;
                if (!read_at(out->raw, off, &r_offset)) break;
                if (!read_at(out->raw, off + 4, &r_info))  break;
                uint32_t r_type = r_info & 0xff;
                uint32_t r_sym  = r_info >> 8;
                // RELA carries its addend in the entry (Elf32_Rela.r_addend,
                // the third word); REL keeps it in place at *r_offset.
                uint32_t rela_addend = 0;
                if (is_rela && !read_at(out->raw, off + 8, &rela_addend)) break;
                // R_ARM_RELATIVE (23): the addend at *r_offset is an
                // absolute vaddr (or 0+addend for PIE). When the
                // addend points into an executable segment, we treat
                // it as a function-pointer seed so synthetic discovery
                // can reach Thumb-only init helpers (libxml2's
                // xmlInitParser, etc.) that are otherwise invisible
                // to our ARM-side BL/BLX scan. Without this, libxml2
                // dispatch tables (xmlOutputCallbackTable et al.)
                // reference functions we never lifted, and HCR's
                // sprite-frame loader silently returns empty atlases.
                if (r_type == 23 /*R_ARM_RELATIVE*/) {
                    // Find the addend value at the relocation site.
                    uint32_t addend = 0;
                    bool found = false;
                    for (const auto& seg : out->loadable) {
                        if (r_offset < seg.vaddr) continue;
                        size_t soff = r_offset - seg.vaddr;
                        if (soff + 4 > seg.bytes.size()) continue;
                        addend = static_cast<uint32_t>(seg.bytes[soff])
                               | (static_cast<uint32_t>(seg.bytes[soff+1]) << 8)
                               | (static_cast<uint32_t>(seg.bytes[soff+2]) << 16)
                               | (static_cast<uint32_t>(seg.bytes[soff+3]) << 24);
                        found = true;
                        break;
                    }
                    // PER-IMAGE BIAS memory write: R_ARM_RELATIVE semantics is
                    // *ptr = load_base + addend. In the single-arena model
                    // load_base was implicitly 0 so the raw addend (already in
                    // the segment bytes) was left untouched. With a non-zero
                    // per-image bias B, EVERY relative pointer (vtables,
                    // .data.rel.ro fnptr tables, C++ typeinfo, data globals)
                    // MUST become B + addend or it derefs into the wrong
                    // image. UNCONDITIONAL for all RELATIVE entries (data AND
                    // code). Gated on B!=0 so the B=0 path writes nothing and
                    // stays byte-identical. The RAW addend is still used below
                    // as a discovery seed (which indexes the ELF at the raw
                    // link-time vaddr -- biasing it would mis-index).
                    if (found && load_bias != 0) {
                        patch_seg(r_offset, addend + load_bias);
                    }
                    if (addend == 0) continue;
                    // Strip Thumb tag for the executable-range check
                    // but keep it on the seed so the lifter knows the
                    // ISA mode.
                    uint32_t target_clean = addend & ~1u;
                    if (!is_code_vaddr(target_clean)) continue;   // v553: code SECTION, not just exec segment
                    if (seen_fnptr_seed.insert(addend).second) {
                        out->data_fnptr_seeds.push_back(addend);
                        // v1.0.63: linker-authoritative seed -- the
                        // linker emitted R_ARM_RELATIVE because the
                        // source said `&Class::method` or similar.
                        // main.cpp uses this set to bypass the
                        // terminator-precedence filter that rejects
                        // legitimate vtable targets like HCR's
                        // AppDelegate::applicationDidFinishLaunching
                        // at 0x76268 (preceded by a non-thrower BL
                        // to an ARM long-call veneer).
                        out->authoritative_fnptr_seeds.push_back(addend);
                    }
                    continue;
                }
                if (r_type != 21 /*R_ARM_GLOB_DAT*/
                    && r_type != 2 /*R_ARM_ABS32*/) continue;
                auto it = dyn_to_imp.find(r_sym);
                if (it == dyn_to_imp.end()) {
                    // Not an import. If r_sym is a DEFINED-LOCAL symbol, this
                    // GOT/.data slot must hold the symbol's BIASED vaddr
                    // (sym.st_value + B), exactly like R_ARM_RELATIVE. Without
                    // this, a GOT-indirected PIC reference to in-image data
                    // stays 0. The load-bearing case: libGDX/arc FreeType's 19
                    // module-class pointers (tt_driver_class, sfnt_module_class,
                    // cff_driver_class, ...) are R_ARM_GLOB_DAT against
                    // defined-local symbols (R_ARM_RELATIVE count over the table
                    // range is 0). Left at 0, FT_Add_Default_Modules registers
                    // no font drivers, FT_Init_FreeType still returns 0 (its
                    // result is FT_New_Library's; FT_Add_Default_Modules is void
                    // and only TRACE0-logs failures), and the next
                    // FT_New_Memory_Face walks a driverless module list and
                    // returns FT_Err_Missing_Module (11) -- the "Couldn't load
                    // font, FreeType error code: 11" we saw. GLOB_DAT is
                    // *ptr = sym; ABS32 is *ptr = sym + (in-place addend) --
                    // 0 at those FreeType GOT slots, but 8 at every C++
                    // typeinfo vptr (see MACAPK_ARM32_BIONIC_DATA_RELOC above). (S
                    // is st_value exactly, Thumb bit included, per
                    // MACAPK_ARM32_BIONIC_THUMB_BIT; indirect-call resolution
                    // masks the lsb for the function-pointer slots.)
                    if (r_sym < dynsym_value_by_index.size()
                        && dynsym_value_by_index[r_sym] != 0) {
                        const uint32_t add = bionic_addend(r_type, r_offset, rela_addend);
                        // (2) above: exact st_value. An STT_FUNC target keeps
                        // its Thumb bit too under MACAPK_ARM32_BIONIC_THUMB_BIT
                        // (Bionic's resolve_symbol_address masks nothing); =0
                        // masks it, the v1.0.1653 write.
                        const uint32_t S =
                            (rel_addend && r_sym < dynsym_type_by_index.size()
                             && (bionic_thumb_bit
                                 || dynsym_type_by_index[r_sym] != 2 /*STT_FUNC*/))
                            ? dynsym_raw_value_by_index[r_sym]
                            : dynsym_value_by_index[r_sym];
                        if (patch_seg(r_offset, S + load_bias + add)) {
                            if (r_type == 21) ++patched_glob_dat;
                            else              ++patched_abs32;
                            if (add != 0) ++patched_addend;
                        }
                    }
                    continue;
                }
                const ImportedSymbol& imp = out->imports[it->second];
                if (imp.plt_addr == 0) {
                    // Data import (no PLT stub). The two legacy ctype
                    // conversion tables get a real backing store below;
                    // everything else is left unpatched here and recorded
                    // as a DataImportSite for the runtime linker
                    // (MACAPK_ARM32_DATA_IMPORT_LINK; recording is pure
                    // bookkeeping and changes no byte).
                    const DataImportSite dis{
                        r_offset, site_addend(r_type, r_offset, rela_addend),
                        static_cast<uint32_t>(it->second),
                        static_cast<uint8_t>(r_type)};
                    if (imp.name == "_toupper_tab_") {
                        toupper_got_slots.push_back(r_offset);
                        ctype_family_sites.push_back(dis);
                    } else if (imp.name == "_tolower_tab_") {
                        tolower_got_slots.push_back(r_offset);
                        ctype_family_sites.push_back(dis);
                    } else if (ctype_table_on && imp.name == "_ctype_") {
                        ctype_got_slots.emplace_back(
                            r_offset, bionic_addend(r_type, r_offset, rela_addend));
                        ctype_family_sites.push_back(dis);
                    } else {
                        ++skipped_no_plt;
                        // `_ctype_` with MACAPK_ARM32_CTYPE_TABLE=0 stays out
                        // of the runtime link too, so that gate keeps its
                        // meaning ("the slot is left unresolved").
                        if (imp.name != "_ctype_")
                            out->data_imports.push_back(dis);
                    }
                    continue;
                }
                // For both GLOB_DAT and ABS32 we just write plt_addr.
                // ABS32 is technically *ptr = *ptr + sym_value, but the
                // pre-populated *ptr (addend) is 0 in practice for these
                // import GOT/.data slots, so the result is plt_addr too.
                // Per-image bias: the GOT slot must hold the BIASED PLT-stub
                // vaddr (B + plt_addr) so the lifted `ldr Rx,[GOT]; blx Rx`
                // dispatches the value the biased LUT is keyed on. +0 at B=0.
                // (MACAPK_ARM32_BIONIC_DATA_RELOC: + the Bionic addend, normally 0
                // here, so the usual result is unchanged.)
                const uint32_t imp_add = bionic_addend(r_type, r_offset, rela_addend);
                if (patch_seg(r_offset, imp.plt_addr + load_bias + imp_add)) {
                    if (r_type == 21) ++patched_glob_dat;
                    else              ++patched_abs32;
                    if (imp_add != 0) ++patched_addend;
                }
            }
            // ---- backing store for the legacy ctype conversion tables ----
            //
            // Bionic (libc/stdlib/{toupper,tolower}_.c, inherited from the
            // BSD ctype implementation):
            //     const short _C_toupper_[1 + 256] = { EOF, 0x00, 0x01, ... };
            //     const short *_toupper_tab_ = _C_toupper_;
            // so the exported symbol is a POINTER whose value is the address
            // of element 0 (the EOF sentinel), and consumers index [c + 1].
            // R_ARM_GLOB_DAT against that symbol wants the ADDRESS OF THE
            // POINTER VARIABLE in the GOT slot, which is why we need two
            // levels here: a 4-byte cell holding the table's guest vaddr,
            // plus the 514-byte table itself.
            //
            // Both must live in GUEST memory (lifted loads go through
            // guest_base + vaddr), so we append a synthetic non-executable
            // PT_LOAD past the image's last segment. main.cpp's
            // emit_segment_constructor registers every entry of
            // `out->loadable` with __macapk_register_segment, so the bytes
            // land in the arena with no other plumbing. The segment is
            // non-executable, so every code-discovery pass in main.cpp
            // (which all filter on seg.executable) ignores it.
            //
            // This mirrors the already-shipped Stage-6/arm64 fix, which
            // exports __macapk_arm64__toupper_tab_/__macapk_arm64__tolower_tab_
            // as `const short* const` pointing at the same 257-entry tables
            // (bionic_shim.cpp). Keep the two in sync.
            if (!toupper_got_slots.empty() || !tolower_got_slots.empty()
                || !ctype_got_slots.empty()) {
                // Guest arena layout (bionic_shim.cpp): the ELF images own
                // everything below kGuestHeapBase = 0x20000000; the heap,
                // handle, trampoline and thread-stack regions start there.
                // Refuse to place the tables past that boundary rather than
                // silently colliding with the heap.
                constexpr uint32_t kGuestHeapBase = 0x20000000u;
                constexpr uint32_t kCellToupper   = 0x000u;   // 4-byte pointer cell
                constexpr uint32_t kCellTolower   = 0x004u;   // 4-byte pointer cell
                constexpr uint32_t kTabToupper    = 0x010u;   // 257 * sizeof(short)
                constexpr uint32_t kTabTolower    = 0x220u;   // 257 * sizeof(short)
                constexpr uint32_t kCellCtype     = 0x008u;   // 4-byte pointer cell
                constexpr uint32_t kTabCtype      = 0x430u;   // 1 + 256 chars
                // The `_ctype_` table extends the segment only for an image
                // that imports it, so every other image's synthetic segment
                // (and dylib) stays byte-identical to before.
                const uint32_t kSynthSize = ctype_got_slots.empty() ? 0x430u : 0x540u;
                uint32_t max_end = 0;
                for (const auto& seg : out->loadable) {
                    uint32_t end = seg.vaddr
                                 + static_cast<uint32_t>(seg.bytes.size());
                    if (end > max_end) max_end = end;
                }
                uint32_t synth_base = (max_end + 0xfffu) & ~0xfffu;
                if (synth_base < 0x1000u) synth_base = 0x1000u;
                // The segment must also fit inside THIS image's bias band.
                // AppClassLoader.preloadAllApkLibs gives the next image the
                // bias B + align64K(max(span, 64 KiB)), span = max PT_LOAD
                // p_vaddr + p_memsz (ElfReader.readMaxVaddr) -- i.e. exactly
                // `band_end` below, relative to B. When align4K(max_end) is
                // itself 64 KiB-aligned the synthetic bytes land on the next
                // image's first page (its ELF header, phdrs and .dynsym),
                // whichever constructor runs second wins, and both images
                // are corrupt. Measured 2026-09-27: 4 corpus libs sit exactly
                // there (Fruit Ninja / Temple Run 2 / Alto libunity.so,
                // Frozen Bubble v7a libmodplug-1.0.so). The translator cannot
                // know whether an image follows this one, so the check is
                // conservative. With MACAPK_ARM32_DATA_IMPORT_LINK on, such an
                // image gets no synthetic segment; its slots become
                // DataImportSites and the runtime linker points them at libc
                // host data in the guest heap. =0 keeps the old placement.
                const uint64_t band_end =
                    (static_cast<uint64_t>(max_end < 0x10000u ? 0x10000u : max_end)
                     + 0xffffu) & ~static_cast<uint64_t>(0xffffu);
                const bool band_fits =
                    static_cast<uint64_t>(synth_base) + kSynthSize <= band_end;
                if (!band_fits && arm32_data_import_link_on()) {
                    for (const DataImportSite& s : ctype_family_sites)
                        out->data_imports.push_back(s);
                    std::fprintf(stderr,
                        "[elf_reader] ctype tables: a synthetic segment at 0x%x "
                        "(%u bytes) would cross this image's bias band end 0x%llx; "
                        "%zu slot(s) left to the runtime linker (libc host data)\n",
                        synth_base, kSynthSize,
                        static_cast<unsigned long long>(band_end),
                        ctype_family_sites.size());
                } else if (static_cast<uint64_t>(synth_base) + kSynthSize
                        + load_bias > kGuestHeapBase) {
                    std::fprintf(stderr,
                        "[elf_reader] ctype tables: no room below the guest "
                        "heap base for a synthetic segment at 0x%x; "
                        "_toupper_tab_/_tolower_tab_/_ctype_ left unresolved\n",
                        synth_base);
                } else {
                    CodeSegment synth{};
                    synth.vaddr      = synth_base;
                    synth.executable = false;
                    synth.bytes.assign(static_cast<size_t>(kSynthSize),
                                       static_cast<uint8_t>(0));
                    auto put32 = [&](uint32_t off, uint32_t v) {
                        synth.bytes[off + 0] = static_cast<uint8_t>(v);
                        synth.bytes[off + 1] = static_cast<uint8_t>(v >> 8);
                        synth.bytes[off + 2] = static_cast<uint8_t>(v >> 16);
                        synth.bytes[off + 3] = static_cast<uint8_t>(v >> 24);
                    };
                    // Element 0 is EOF (-1) exactly as in _C_toupper_ /
                    // _C_tolower_; elements 1..256 map char 0..255. C locale
                    // only, which is all bionic's tables encode.
                    auto put_table = [&](uint32_t off, bool to_upper) {
                        auto put16 = [&](uint32_t o, int16_t v) {
                            uint16_t u = static_cast<uint16_t>(v);
                            synth.bytes[o + 0] = static_cast<uint8_t>(u);
                            synth.bytes[o + 1] = static_cast<uint8_t>(u >> 8);
                        };
                        put16(off, static_cast<int16_t>(-1));
                        for (int c = 0; c < 256; ++c) {
                            int r = c;
                            if (to_upper) {
                                if (c >= 'a' && c <= 'z') r = c - 'a' + 'A';
                            } else {
                                if (c >= 'A' && c <= 'Z') r = c - 'A' + 'a';
                            }
                            put16(off + 2u * static_cast<uint32_t>(c + 1),
                                  static_cast<int16_t>(r));
                        }
                    };
                    put_table(kTabToupper, true);
                    put_table(kTabTolower, false);
                    put32(kCellToupper, synth_base + kTabToupper + load_bias);
                    put32(kCellTolower, synth_base + kTabTolower + load_bias);
                    if (!ctype_got_slots.empty()) {
                        // bionic libc/upstream-openbsd/lib/libc/gen/ctype_.c
                        // (android14): `const char _C_ctype_[1 + 256] = { 0, ...}`
                        // and `const char *_ctype_ = _C_ctype_;`. Bits from
                        // kitkat ctype.h:45-52 (U 01 L 02 N 04 S 08 P 10 C 20
                        // X 40 B 80). Element 0 is the EOF slot; 0x80-0xff are 0.
                        constexpr uint8_t U = 0x01, L = 0x02, N = 0x04, S = 0x08,
                                          P = 0x10, C = 0x20, X = 0x40, B = 0x80;
                        synth.bytes[kTabCtype] = 0;
                        for (int c = 0; c < 128; ++c) {
                            uint8_t v;
                            if (c < 0x20)       v = (c >= 0x09 && c <= 0x0d) ? (C | S) : C;
                            else if (c == 0x20) v = S | B;
                            else if (c < 0x30)  v = P;
                            else if (c < 0x3a)  v = N;
                            else if (c < 0x41)  v = P;
                            else if (c < 0x47)  v = U | X;
                            else if (c < 0x5b)  v = U;
                            else if (c < 0x61)  v = P;
                            else if (c < 0x67)  v = L | X;
                            else if (c < 0x7b)  v = L;
                            else if (c < 0x7f)  v = P;
                            else                v = C;   // DEL
                            synth.bytes[kTabCtype + 1 + static_cast<uint32_t>(c)] = v;
                        }
                        put32(kCellCtype, synth_base + kTabCtype + load_bias);
                    }
                    out->loadable.push_back(std::move(synth));
                    size_t patched_ctype = 0;
                    for (uint32_t slot : toupper_got_slots) {
                        if (patch_seg(slot,
                                      synth_base + kCellToupper + load_bias))
                            ++patched_ctype;
                    }
                    for (uint32_t slot : tolower_got_slots) {
                        if (patch_seg(slot,
                                      synth_base + kCellTolower + load_bias))
                            ++patched_ctype;
                    }
                    size_t patched_ctype_tab = 0;
                    for (const auto& sa : ctype_got_slots) {
                        if (patch_seg(sa.first,
                                      synth_base + kCellCtype + load_bias + sa.second))
                            ++patched_ctype_tab;
                    }
                    std::fprintf(stderr,
                        "[elf_reader] ctype tables: synthetic segment at "
                        "0x%x (%u bytes), patched %zu "
                        "_toupper_tab_/_tolower_tab_ GOT slot(s), %zu _ctype_\n",
                        synth_base, kSynthSize, patched_ctype, patched_ctype_tab);
                }
            }
            // Pre-scan executable PT_LOADs for the Thumb16 LDR pc-rel +
            // ADD pc pattern. This produces an absolute vaddr at runtime
            // (literal_pool_value + pc_at_add). When the result is a
            // Thumb-marked function pointer (lsb=1), it's typically a
            // statically-linked helper -- e.g. xmlFileOpen_real,
            // xmlFileMatch -- registered as a libxml2 callback by code
            // like xmlRegisterDefaultInputCallbacks. Such helpers have
            // no symbol entry, no relocation (PC-relative computation
            // skips the static-data fixup that drives data_fnptr_seeds),
            // and no direct BL (the call goes through a runtime register
            // load + BLX). Without this scan they're invisible to lift
            // discovery and the runtime indirect-call dispatcher logs
            // "indirect call to in-image vaddr 0x1fe2e9" forever, which
            // is the smoking gun behind HCR's empty scene tree.
            //
            // Algorithm:
            //   1. Walk every 2-byte aligned offset in each executable
            //      PT_LOAD.
            //   2. If bytes match Thumb16 LDR Rt, [pc, #imm8*4]:
            //          byte[1] in {0x48..0x4F}  (0x48 | Rt for Rt=0..7)
            //          byte[0] = imm8
            //      compute the literal-pool value V (4 bytes at
            //      ((insn+4) & ~3) + imm8*4).
            //   3. Look up to 24 bytes ahead for ADD Rt, pc (Thumb16):
            //          byte[1] = 0x44, byte[0] = 0x78 | Rt   (Rt 0..7)
            //      compute target = V + (add_addr + 4).
            //   4. If target points into an executable PT_LOAD AND has
            //      its low bit set (Thumb fnptr), seed it. ARM-mode
            //      results (lsb=0) are usually data references (string
            //      tables, struct addresses) so we skip them.
            //
            // We're deliberately permissive about register reuse: a
            // false positive just spends one wasted lift attempt that
            // returns "couldn't decode," whereas a false negative leaves
            // a real callback uncovered. The 24-byte window matches what
            // ARM Clang actually emits for "load-N-fnptrs-then-call-
            // register" sequences (see xmlRegisterDefaultInputCallbacks
            // at 0x1fcc48 for the canonical example: 4 LDRs interleaved
            // with their ADDs, all within ~16 bytes).
            size_t pcrel_seeds_added = 0;
            {
                std::unordered_set<uint32_t> seen_pcrel;
                for (uint32_t s : out->data_fnptr_seeds) seen_pcrel.insert(s);
                for (const auto& seg : out->loadable) {
                    if (!seg.executable) continue;
                    const uint32_t base = seg.vaddr;
                    const auto& bs = seg.bytes;
                    if (bs.size() < 4) continue;
                    for (size_t off = 0; off + 2 <= bs.size(); off += 2) {
                        uint8_t b0 = bs[off];
                        uint8_t b1 = bs[off + 1];
                        if ((b1 & 0xF8) != 0x48) continue;
                        int rt = b1 & 0x07;
                        uint32_t imm8 = b0;
                        uint32_t insn_addr = base + static_cast<uint32_t>(off);
                        uint32_t pc_at_ldr = (insn_addr + 4) & ~3u;
                        uint32_t lit_addr  = pc_at_ldr + imm8 * 4;
                        if (lit_addr < base) continue;
                        size_t lit_off = lit_addr - base;
                        if (lit_off + 4 > bs.size()) continue;
                        uint32_t literal =
                              static_cast<uint32_t>(bs[lit_off])
                            | (static_cast<uint32_t>(bs[lit_off + 1]) << 8)
                            | (static_cast<uint32_t>(bs[lit_off + 2]) << 16)
                            | (static_cast<uint32_t>(bs[lit_off + 3]) << 24);
                        uint8_t want_b0 = static_cast<uint8_t>(0x78 | rt);
                        for (size_t la = off + 2;
                             la + 2 <= bs.size() && la < off + 26;
                             la += 2) {
                            uint8_t lb0 = bs[la];
                            uint8_t lb1 = bs[la + 1];
                            if (lb1 == 0x44 && lb0 == want_b0) {
                                uint32_t add_addr = base
                                    + static_cast<uint32_t>(la);
                                uint32_t pc_at_add = add_addr + 4;
                                uint32_t target =
                                    pc_at_add + literal;
                                uint32_t target_clean = target & ~1u;
                                if (!is_code_vaddr(target_clean)) break;   // v553: code SECTION, not just exec segment
                                if ((target & 1u) == 0) break;
                                if (seen_pcrel.insert(target).second) {
                                    out->data_fnptr_seeds.push_back(target);
                                    ++pcrel_seeds_added;
                                }
                                break;
                            }
                        }
                    }
                }
            }
            // v1.0.529: the ARM-mode analog of the Thumb16 PIC-fnptr scan above.
            // The Thumb scan only matches Thumb16, so it misses ARM-mode runtime
            // function-pointer tables built by the canonical idiom
            //   ldr Rt, [pc, #imm12]    ; Rt = link-time PC-relative offset V
            //   add Rt, pc, Rt          ; Rt = (add_pc+8) + V = absolute target
            //   str Rt, [Rbase, #off]   ; install the fnptr into a table / struct
            // minizip's fill_fopen64_filefunc (cocos2d-x v2's apk-zip reader) is
            // exactly this: it installs fopen64_file_func / fread / fseek / fclose
            // into the ioapi filefunc table, which unzOpen then blx's (table[0] =
            // the open callback -- e.g. 0x22f6e8 in HCR 1.43, which tail-calls
            // fopen). That callback is address-taken ONLY here: no symbol, no
            // relocation, no direct BL. Without seeding it the indirect blx panic-
            // stubs to 0, unzOpen returns NULL, and EVERY relative cocos2d-x v2
            // asset load (fonts, textures) fails -> blank menu (the HCR black
            // screen). ARM fnptrs are lsb=0 (unlike Thumb's lsb=1), so we cannot
            // use the low bit to tell fnptr from data; instead we require BOTH
            // (a) the add result lands in an EXECUTABLE segment (GOT entries /
            // rodata that the plain `ldr;add pc` idiom also computes live in
            // non-exec segments and are filtered out), and (b) the add is closely
            // followed by a `str Rt, [..]` -- the table-install idiom -- so an
            // inline data-address computation is not mistaken for a stored fnptr.
            // Seeds go to data_fnptr_seeds (NOT authoritative), so the discovery
            // candidate filter still rejects any mid-function / mid-pool false
            // positive. Render-sweep-gated (discovery affects every ARM32 app).
            size_t arm_pcrel_seeds_added = 0;
            // v1.0.1055 GATE W -- MACAPK_ARM32_FNPTR_ARG_WINDOW.
            // Widens ONLY the BL-argument idiom's look-ahead below from 6 to
            // 7 instructions. Why 7 and not "more": the window has to reach
            // from the `add Rt,pc,Rt` that materialises the function pointer
            // past the callee's OTHER argument setup to the BL. Six was
            // enough for a call whose remaining arguments are cheap, and it
            // is one short for a call that sets up six.
            //
            // Measured victim (HCR 1.68.1 libgame.so, cocos2d-x v2
            // CCScheduler::scheduleSelector): the SEL_SCHEDULE target
            // 0x4694e8 is materialised at ldr@0x46820c / add@0x468210 and the
            // BL that consumes it sits at k=7, with six argument-setup
            // instructions in between. It is address-taken ONLY there -- no
            // .dynsym entry, no relocation, no direct BL -- so at k<=6 it is
            // never discovered and every scheduler tick indirect-calls a
            // panic stub that returns 0.
            //
            // NOT A RENDERING FIX, and do not let a later reader infer one:
            // HCR 1.68.1 compiles ZERO shaders and zero programs (measured GL
            // census, docs/arm32-three-blanks-three-causes_2026-08-26.md), and
            // a dead `update(dt)` cannot prevent shader compilation. This
            // lands on CORRECTNESS grounds -- it is the third member of the
            // address-taken-only family (minizip ioapi, fixed v1.0.533;
            // libxml2's xmlFileWrite, still open; now the C++
            // pointer-to-member-function call) and it is generic to any
            // cocos2d-x v2 title that schedules a selector.
            //
            // Blast radius, measured statically over all 17 armeabi-v7a .so
            // on this box with debug/arm32_discovery_blast.py gateW: 2718 ->
            // 2725 seeds, ALL SEVEN of them in that one binary; every other
            // library (HCR 1.8.1 and GD Lite included) is bit-identical. That
            // is why it is a separate gate from the pool-predecessor relief,
            // which is NOT fleet-neutral.
            //
            // Default OFF: discovery changes in this tree have a violent
            // regression history (v1.0.58->59, v1.0.30->31, and the note at
            // main.cpp's discovery loop), so the default path must stay
            // byte-identical until a sweep says otherwise.
            static const bool arm_arg_window_on = [](){
                const char* e = std::getenv("MACAPK_ARM32_FNPTR_ARG_WINDOW");
                return e && *e && *e != '0';
            }();
            // The historical look-ahead, kept as the accounting baseline so
            // "extra seed(s)" below means "found beyond what v1.0.815 saw".
            constexpr size_t kArgLookaheadBase = 6;
            // Seeds that ONLY the widened window found, for the log line below.
            size_t arm_pcrel_argwin_extra = 0;
            {
                std::unordered_set<uint32_t> seen_arm;
                for (uint32_t s : out->data_fnptr_seeds) seen_arm.insert(s);
                auto rd32 = [](const std::vector<uint8_t>& b, size_t o) -> uint32_t {
                    return  static_cast<uint32_t>(b[o])
                        | (static_cast<uint32_t>(b[o + 1]) << 8)
                        | (static_cast<uint32_t>(b[o + 2]) << 16)
                        | (static_cast<uint32_t>(b[o + 3]) << 24);
                };
                for (const auto& seg : out->loadable) {
                    if (!seg.executable) continue;
                    const uint32_t base = seg.vaddr;
                    const auto& bs = seg.bytes;
                    if (bs.size() < 8) continue;
                    for (size_t off = 0; off + 4 <= bs.size(); off += 4) {
                        uint32_t insn = rd32(bs, off);
                        // ARM `ldr Rt, [pc, #imm12]` (P=1,U=1,B=0,W=0,L=1,Rn=pc).
                        if ((insn & 0x0FFF0000u) != 0x059F0000u) continue;
                        uint32_t rt    = (insn >> 12) & 0xFu;
                        uint32_t imm12 = insn & 0xFFFu;
                        uint32_t insn_addr = base + static_cast<uint32_t>(off);
                        uint32_t lit_addr  = (insn_addr + 8) + imm12;  // ARM PC=insn+8
                        if (lit_addr < base) continue;
                        size_t lit_off = lit_addr - base;
                        if (lit_off + 4 > bs.size()) continue;
                        uint32_t literal = rd32(bs, lit_off);
                        // `add Rt, pc, Rt` (same Rt, no shift) within a small window.
                        //
                        // MACAPK_ARM32_FNPTR_ADD_WINDOW (default ON; =0 is the
                        // old 36-byte window byte for byte): past the historical
                        // 36 bytes the search continues up to 256 bytes, but ONLY
                        // while nothing in between can have changed Rt -- it stops
                        // at any branch (B/BL/BX/BLX: a call clobbers r0-r3, r12,
                        // lr, and control may not fall through), at any
                        // LDM/POP that loads Rt, and at any instruction whose
                        // bits 15-12 (the Rd/Rt field of data-processing and
                        // loads) or bits 19-16 (the Rd field of the multiplies)
                        // name Rt. Conservative: a store's source also sits in
                        // bits 15-12 and stops the search, which costs a seed,
                        // never correctness. Measured victim (Jetpack Joyride
                        // 1.3.5 libmortargame.so): the member-function pointer
                        // 0x18c838 (a real `push {r4-r8,lr}` entry) is built by
                        // `ldr r0,[pc,#0x85c]` @0x18fa10 ... `add r0,pc,r0`
                        // @0x18fa48 (56 bytes, 13 scheduled instructions later)
                        // and stored to [sp,#0x1c]; it was never seeded, and the
                        // functor invoker 0x194508 (`blx r3` @0x194534) hit the
                        // in-image panic stub 8x per boot.
                        static const bool add_window_on = [](){
                            const char* e = std::getenv("MACAPK_ARM32_FNPTR_ADD_WINDOW");
                            return !(e && *e == '0');
                        }();
                        const size_t add_window = add_window_on ? 256 : 36;
                        for (size_t la = off + 4;
                             la + 8 <= bs.size() && la < off + add_window; la += 4) {
                            uint32_t a = rd32(bs, la);
                            if (la >= off + 36) {
                                // Only reachable with the gate on.
                                const bool is_branch =
                                    (a & 0x0E000000u) == 0x0A000000u ||   // B/BL/BLX(imm)
                                    (a & 0x0FFFFFD0u) == 0x012FFF10u;     // BX/BLX(reg)
                                const bool ldm_rt =
                                    (a & 0x0E100000u) == 0x08100000u && (a & (1u << rt));
                                const bool mul_rt =
                                    (a & 0x0F0000F0u) == 0x00000090u && ((a >> 16) & 0xFu) == rt;
                                const bool is_target_add =
                                    (a & 0x0FFF0FF0u) == 0x008F0000u
                                    && ((a >> 12) & 0xFu) == rt && (a & 0xFu) == rt;
                                // A function EXIT ends the window too: `pop {..,pc}`
                                // / `ldm Rn,{..,pc}` (LDM whose list holds PC) and
                                // any data-processing / single-load instruction
                                // whose Rd/Rt is PC (`mov pc,lr`, `ldr pc,[..]`,
                                // `add pc,..`; bits 27-26 = 00 or 01 only, so
                                // VFP/coprocessor words whose bits 15-12 are a
                                // Vd field do not stop it). Past a return the
                                // bytes are the NEXT function, whose own
                                // `add Rt,pc,Rt` is not this literal's.
                                const bool ldm_pc =
                                    (a & 0x0E100000u) == 0x08100000u && (a & (1u << 15));
                                const bool writes_pc =
                                    ((a >> 26) & 3u) <= 1u && ((a >> 12) & 0xFu) == 15u;
                                if (!is_target_add
                                        && (is_branch || ldm_rt || mul_rt || ldm_pc
                                            || writes_pc
                                            || ((a >> 12) & 0xFu) == rt))
                                    break;
                            }
                            if ((a & 0x0FFF0FF0u) != 0x008F0000u) continue;
                            if (((a >> 12) & 0xFu) != rt || (a & 0xFu) != rt) continue;
                            // Require a `str Rt, [..]` in the next 1-2 insns (the
                            // table-install idiom) so inline data-address compute
                            // is not seeded as a function.
                            bool installed = false;
                            bool store_installed = false;
                            // Which look-ahead slot the BL-argument idiom
                            // matched at, 0 when it did not (gate W accounting).
                            size_t bl_arg_k = 0;
                            // v1.0.552: window widened 8 -> 16 bytes and STM
                            // accepted as an install. FreeType's FT_New_Memory
                            // (ftsystem.c) installs its alloc trio as
                            //   ldr r12,[pc]; ldr r2,[pc]; ldr r3,[pc]
                            //   add r12,pc,r12 ; add r2,pc,r2
                            //   stm r0, {r1, r12}        <- ft_alloc via STM
                            //   add r3,pc,r3 ; str r2,[r0,#8]  <- ft_free str
                            //                    is 3 insns after its add
                            // so the old single-str-within-2-insns check missed
                            // ft_alloc AND ft_free -> FT_New_Library's
                            // memory->alloc indirect call panic-stubbed ->
                            // FT_Err_Out_Of_Memory (64) on every face load.
                            // v1.0.553: 16 -> 64. tt_face_init's loader-hook
                            // block computes FIVE fnptrs (interleaved ldr/add
                            // pairs) before storing any: `str r1,[r10,#0x210]`
                            // lands 28 bytes after its add (r0's str 44 bytes)
                            // -> TT_Load_Glyph's read-glyph hooks (raw 0x98bcc
                            // et al) were never seeded -> glyph loads died on
                            // an unresolved in-image indirect call. The
                            // code-SECTION target gate (v553) rejects the
                            // false positives a wide window used to admit.
                            for (size_t sa = la + 4;
                                 sa + 4 <= bs.size() && sa <= la + 64; sa += 4) {
                                uint32_t s = rd32(bs, sa);
                                if (((s >> 26) & 0x3u) == 0x1u &&   // single data xfer
                                    ((s >> 20) & 0x1u) == 0x0u &&   // L=0 -> store
                                    ((s >> 12) & 0xFu) == rt) {     // storing Rt
                                    installed = true; store_installed = true;
                                    break;
                                }
                                if ((s & 0x0E000000u) == 0x08000000u &&  // block xfer
                                    ((s >> 20) & 0x1u) == 0x0u &&        // L=0 -> STM
                                    (s & (1u << rt)) != 0u) {            // list has Rt
                                    installed = true; store_installed = true;
                                    break;
                                }
                            }
                            // Call-argument idiom: `add Rt,pc,Rt` then `bl/blx`
                            // with Rt an arg reg (r0..r3) -- a fnptr passed
                            // straight to the callee (e.g. __emutls_get_address
                            // -> pthread_once(&once, emutls_init)).
                            // v1.0.806: DEFAULT-ON. Taking a function's address
                            // straight into an ARGUMENT register and immediately
                            // calling is the `qsort(base, n, sz, cmp)` /
                            // `bsearch` / callback-registration idiom, and the
                            // comparator it names is address-taken ONLY here --
                            // no symbol, no relocation, no direct BL. Without a
                            // seed, discovery never lifts it and the indirect
                            // call inside qsort panic-stubs.
                            //
                            // A fleet sweep found this is the most-repeated
                            // concrete ARM32 signature we have: unciv (78 hits),
                            // mindustry (140) and bouncy (5) all panic from the
                            // IDENTICAL relative offset <synth>+0xf0 with
                            // comparator-shaped arguments.
                            //
                            // It was gated off only by accident of history: the
                            // condition rode on MACAPK_PLT_REDIRECT, an env var
                            // whose actual job is PLT redirection, so a discovery
                            // feature was switched by an unrelated knob and
                            // nobody turned it on. The match is already tight --
                            // PIC address computation into r0-r3, immediately
                            // followed by BL/BLX #imm, target must land in a code
                            // SECTION -- and the seed is NON-authoritative, so
                            // the candidate filter still rejects mid-function and
                            // mid-pool false positives. MACAPK_FNPTR_ARG_SEED=0
                            // restores the old behaviour.
                            //
                            // 2026-09-27: MACAPK_FNPTR_ARG_SEED is now the ONLY
                            // switch. The two unrelated knobs that could also
                            // force this seed on (MACAPK_PLT_REDIRECT, and
                            // MACAPK_PLT_REDIRECT_FREETYPE for a lib defining
                            // FT_Init_FreeType) went with the retired PLT
                            // redirect; see
                            // docs/plt-redirect-knobs-retired_2026-09-27.md.
                            // With the default (=1) the condition is unchanged.
                            static const bool arg_seed_on = [](){
                                const char* e = std::getenv("MACAPK_FNPTR_ARG_SEED");
                                return !(e && *e == '0');
                            }();
                            if (arg_seed_on
                                && !installed && rt <= 3
                                && la + 8 <= bs.size()) {
                                // v1.0.815: LOOK AHEAD, don't demand that the
                                // BL be the very next instruction. Requiring
                                // adjacency only ever matched a ONE-argument
                                // call, because a call with more than one
                                // argument sets up its other argument registers
                                // in between:
                                //
                                //     add r1, pc, r1      ; r1 = init_routine
                                //     ldr r0, [pc, #Y]    ; <-- intervening
                                //     add r0, pc, r0      ; r0 = &once_control
                                //     bl  pthread_once
                                //
                                // That is exactly the shape this block's own
                                // comment cites as its motivating example
                                // (`__emutls_get_address -> pthread_once(&once,
                                // emutls_init)`), and it never matched. On
                                // Jetpack Joyride the consequence was severe:
                                // libgcc's `__emutls_init_once` (0x41b0f0) was
                                // never discovered, so __macapk_shim_pthread_once
                                // could not resolve it, the emulated-TLS
                                // pthread_key_t was never created, and every one
                                // of ~947 __emutls_get_address call sites got a
                                // fresh zero-filled slot instead of the stored
                                // value -- including the cached JNIEnv*, which
                                // JNI_OnLoad had written correctly a moment
                                // earlier.
                                //
                                // Bounded and conservative: stop at the first
                                // branch, and abandon the match if anything in
                                // between could clobber Rt. Widening the window
                                // can only ADD seeds, and they stay
                                // NON-authoritative, so the candidate filter
                                // still rejects mid-function and mid-pool
                                // false positives.
                                // v1.0.1055: 6 unless gate W widens it to 7.
                                // See MACAPK_ARM32_FNPTR_ARG_WINDOW above.
                                const size_t kMaxLookahead =
                                    arm_arg_window_on ? 7 : kArgLookaheadBase;
                                for (size_t k = 1; k <= kMaxLookahead; ++k) {
                                    const size_t off = la + 4 * k;
                                    if (off + 4 > bs.size()) break;
                                    const uint32_t w = rd32(bs, off);
                                    if ((w & 0x0F000000u) == 0x0B000000u ||  // BL
                                        (w & 0xFE000000u) == 0xFA000000u) {  // BLX #imm
                                        installed = true;
                                        bl_arg_k = k;
                                        break;
                                    }
                                    // Any other branch ends the basic block --
                                    // whatever the call is, it is not this one.
                                    if ((w & 0x0F000000u) == 0x0A000000u ||  // B
                                        (w & 0x0FFFFFF0u) == 0x012FFF10u) {  // BX
                                        break;
                                    }
                                    // LDM/POP that loads Rt clobbers it.
                                    if ((w & 0x0E100000u) == 0x08100000u &&
                                        (w & (1u << rt))) {
                                        break;
                                    }
                                    // Conservative Rd check: bits 15-12 name the
                                    // destination for data-processing and LDR.
                                    // For STR they name the SOURCE, so bailing
                                    // there is unnecessary but harmless -- it
                                    // costs a seed, never correctness.
                                    if (((w >> 12) & 0xFu) == rt) break;
                                }
                            }
                            if (!installed) break;
                            uint32_t target = (base + static_cast<uint32_t>(la) + 8)
                                              + literal;            // ARM PC=insn+8
                            if (is_code_vaddr(target & ~1u) &&   // v553: code SECTION
                                seen_arm.insert(target).second) {
                                out->data_fnptr_seeds.push_back(target);
                                // v1.0.552: STORE/STM-installed results are
                                // AUTHORITATIVE (bypass the terminator-
                                // precedence admission filter, keep the
                                // literal-pool + inside-body checks). The
                                // 3-insn structural match (pc-rel ldr + add
                                // rt,pc,rt + install of rt) is the compiler's
                                // take-address-of-function idiom; requiring a
                                // terminator before the target rejected real
                                // entries that FOLLOW a literal pool --
                                // FreeType's ft_alloc (mov r0,r1; b malloc@plt
                                // preceded by its caller's pool words) was
                                // dropped exactly this way -> memory->alloc
                                // panic-stub -> FT error 64 on face load. The
                                // BL-arg variant stays non-authoritative.
                                //
                                // v1.0.806: ALSO record it in
                                // heuristic_pic_fnptr_seeds. Unlike an exidx
                                // entry or an R_ARM_RELATIVE relocation (both
                                // linker/toolchain-verified), this 3-insn
                                // match is a byte-pattern GUESS -- "compute a
                                // PC-relative address, then store it" is also
                                // exactly how ordinary value/struct
                                // construction compiles (a `CCPoint(x, y)`
                                // call is structurally identical), so `target`
                                // here can land on a real in-range .text
                                // address that is NOT a function start, just
                                // an instruction the arithmetic happened to
                                // hit. main.cpp uses this narrower set to
                                // require terminator-precedence for THIS
                                // provenance specifically (still bypassed for
                                // genuine exidx/relocation seeds), rather than
                                // letting a bogus target become a permanent,
                                // un-revisitable cap boundary for whatever
                                // function actually contains it. Confirmed
                                // case: HCR 1.8.1 libgame.so 0xe07d4 lands
                                // mid a CCPoint-construction 4-insn run inside
                                // 0xe0358's lock-overlay block, immediately
                                // preceded by `bl CCPoint::CCPoint(float,
                                // float)` -- a live call, not a terminator --
                                // yet was admitted as authoritative and became
                                // arm32rc's cap for 0xe0358, silently
                                // truncating it and zeroing the loop's own
                                // live registers on fall-through into the
                                // "next" synth. See
                                // docs/arm32-discovery-mid-instruction-fnptr-seed_2026-07-28.md.
                                if (store_installed) {
                                    out->authoritative_fnptr_seeds.push_back(target);
                                    out->heuristic_pic_fnptr_seeds.push_back(target);
                                }
                                ++arm_pcrel_seeds_added;
                                if (bl_arg_k > kArgLookaheadBase) {
                                    ++arm_pcrel_argwin_extra;
                                }
                            }
                            break;
                        }
                    }
                }
            }
            // Gate W is self-describing: a run that carries the env says so,
            // and says what it bought. Emitted only when the gate is on, so
            // the default build's stderr is unchanged.
            if (arm_arg_window_on) {
                std::fprintf(stderr,
                    "[elf_reader] %s: MACAPK_ARM32_FNPTR_ARG_WINDOW=1: "
                    "BL-arg look-ahead 6 -> 7 admitted %zu extra seed(s) "
                    "(%zu ARM PIC seeds total)\n",
                    out->soname.c_str(), arm_pcrel_argwin_extra,
                    arm_pcrel_seeds_added);
            }
            if (patched_glob_dat || patched_abs32 || skipped_no_plt
                || !out->data_fnptr_seeds.empty()) {
                std::fprintf(stderr,
                    "[elf_reader] %s: applied relocations: "
                    "GLOB_DAT=%zu ABS32=%zu (addend!=0: %zu), skipped data-imports=%zu, "
                    "data_fnptr_seeds=%zu (pcrel=%zu arm_pcrel=%zu)\n",
                    out->soname.c_str(),
                    patched_glob_dat, patched_abs32, patched_addend, skipped_no_plt,
                    out->data_fnptr_seeds.size(), pcrel_seeds_added,
                    arm_pcrel_seeds_added);
            }
        }
    }

    // DT_JMPREL after DT_REL, as Bionic's soinfo::relocate() orders them: the
    // local R_ARM_JUMP_SLOT words collected by the .rel.plt walk above.
    for (const auto& [slot, value] : deferred_jump_slots)
        if (patch_got_slot(slot, value)) ++patched_local_js;
    if (patched_local_js) {
        std::fprintf(stderr,
            "[elf] bound %zu intra-module JUMP_SLOT GOT slot(s) to their "
            "local definitions\n", patched_local_js);
    }

    // Default soname to the file's basename if DT_SONAME isn't extracted.
    auto slash = path.find_last_of('/');
    out->soname = (slash == std::string::npos) ? path : path.substr(slash + 1);

    // MACAPK_DUMP_GUEST_SEGMENTS=<file>: write the loaded, RELOCATED PT_LOAD
    // bytes -- exactly what the backends embed as guest memory -- as
    // repeated [u32 vaddr][u32 size][size bytes], little-endian. Diagnostic
    // only (debug/reladdend_gate.sh reads it to check every relocated word
    // against the ELF's own S + A); no effect on the translation.
    if (const char* dump = std::getenv("MACAPK_DUMP_GUEST_SEGMENTS")) {
        if (dump[0]) {
            if (FILE* f = std::fopen(dump, "wb")) {
                for (const auto& seg : out->loadable) {
                    const uint32_t hdr[2] = {seg.vaddr,
                                             static_cast<uint32_t>(seg.bytes.size())};
                    std::fwrite(hdr, sizeof hdr, 1, f);
                    if (!seg.bytes.empty())
                        std::fwrite(seg.bytes.data(), 1, seg.bytes.size(), f);
                }
                std::fclose(f);
            }
        }
    }
    // MACAPK_DUMP_DATA_IMPORTS=<file>: the DataImportSite table, one line per
    // site in hex: `slot name addend r_type sym_type sym_bind`, after a
    // `# dynamic_vaddr <v> sites <n>` header. Diagnostic only
    // (debug/dataimport_gate.sh compares it with a reference linker); changes
    // no output byte.
    if (const char* dump = std::getenv("MACAPK_DUMP_DATA_IMPORTS")) {
        if (dump[0]) {
            if (FILE* f = std::fopen(dump, "w")) {
                std::fprintf(f, "# dynamic_vaddr 0x%x sites %zu\n",
                             out->dynamic_vaddr, out->data_imports.size());
                for (const DataImportSite& s : out->data_imports) {
                    const ImportedSymbol& imp = out->imports[s.import_index];
                    std::fprintf(f, "%08x %s %08x %u %u %u\n", s.slot,
                                 imp.name.c_str(), s.addend,
                                 static_cast<unsigned>(s.r_type),
                                 static_cast<unsigned>(imp.type),
                                 static_cast<unsigned>(imp.bind));
                }
                std::fclose(f);
            }
        }
    }

    return {};
}

}  // namespace macapk::translator
