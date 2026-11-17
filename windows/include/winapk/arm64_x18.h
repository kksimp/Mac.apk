// arm64_x18.h -- does an arm64 library's code use register x18?
//
// Windows on Arm keeps the TEB in x18; an exception taken while it holds
// anything else ends the process. NDK code leaves x18 alone, but some code uses
// it as scratch (the shadow call stack: `str x30, [x18], #8`; hand-written
// assembly), and such a library cannot run natively. This finds it at load.
//
// uses_x18 decodes the general-register fields of one A64 instruction (31 is
// SP/XZR; v18 is not x18). Exact for what compilers emit, "yes" when unsure.

#pragma once

#include "winapk/elf64_defs.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace winapk::arm64 {

namespace x18_detail {

constexpr uint32_t kX18 = 18;
inline constexpr uint32_t bits(uint32_t v, unsigned hi, unsigned lo) { return (v >> lo) & ((1u << (hi - lo + 1)) - 1); }
inline constexpr bool is18(uint32_t r) { return r == kX18; }

}  // namespace x18_detail

inline bool uses_x18(uint32_t i) {
    using namespace x18_detail;
    const uint32_t rd = bits(i, 4, 0), rn = bits(i, 9, 5), rm = bits(i, 20, 16), ra = bits(i, 14, 10);
    const uint32_t op0 = bits(i, 28, 25);
    const bool any3 = is18(rd) || is18(rn) || is18(rm);  // for the classes below that are decoded only loosely

    // Data processing, immediate: 100x.
    if ((op0 & 0xe) == 0x8) {
        if (bits(i, 28, 24) == 0x10) return is18(rd);  // ADR, ADRP: Rn is an immediate
        switch (bits(i, 25, 23)) {
            case 0x5: return is18(rd);                         // move wide
            case 0x7: return is18(rd) || is18(rn) || is18(rm);  // extract
            default: return is18(rd) || is18(rn);               // add/sub (with tags), logical, bitfield
        }
    }

    // Branches, system: 101x.
    if ((op0 & 0xe) == 0xa) {
        if (bits(i, 30, 26) == 0x05) return false;                                 // B, BL
        if (bits(i, 31, 25) == 0x2a) return false;                                 // B.cond
        if (bits(i, 31, 24) == 0xd4) return false;                                 // exceptions
        if (bits(i, 30, 25) == 0x1a || bits(i, 30, 25) == 0x1b) return is18(rd);   // CBZ/CBNZ, TBZ/TBNZ: Rt
        if (bits(i, 31, 25) == 0x6b) return is18(rn) || is18(rd);                  // BR, BLR, RET, BRAA..
        if (bits(i, 31, 22) == 0x354) return is18(rd);                             // MSR, MRS, SYS, SYSL, hints: Rt
        if (bits(i, 31, 22) == 0x355) return is18(rd) || bits(i, 4, 0) == 17;      // MRRS, MSRR, SYSP: Rt and Rt+1
        return any3;                                                               // newer branch forms (compare and branch)
    }

    // Loads and stores: x1x0.
    if ((op0 & 0x5) == 0x4) {
        const bool v = bits(i, 26, 26) != 0;  // a SIMD/FP register is the data register
        const uint32_t cls = bits(i, 29, 27);
        const bool data = !v && is18(rd);
        // PRFM (immediate, register, unscaled) and its literal form: Rt is the prefetch operation.
        const uint32_t op4 = bits(i, 11, 10);
        const bool prfm = bits(i, 31, 30) == 3 && !v &&
                          (cls == 3 ? bits(i, 25, 24) == 0
                                    : (cls == 7 && bits(i, 23, 22) == 2 &&
                                       (bits(i, 24, 24) || (!bits(i, 21, 21) && op4 == 0) || (bits(i, 21, 21) && op4 == 2))));
        // Memory copy and set (FEAT_MOPS): Xd, Xs, Xn are all general registers.
        if (cls == 0x3 && bits(i, 25, 24) == 1 && !bits(i, 21, 21) && op4 == 1 && !bits(i, 31, 31)) return any3;
        if (cls == 0x5) return data || (!v && is18(ra)) || is18(rn);  // pairs: Rt, Rt2, Rn
        if (cls == 0x3) return (prfm ? false : data) || (bits(i, 25, 24) == 0 ? false : is18(rn));  // literal, and LDAPUR forms
        if (cls == 0x1) {
            if (v) return is18(rn) || (bits(i, 23, 23) && is18(rm));  // SIMD structures: base, post-index register
            return data || is18(rm) || is18(ra) || is18(rn);           // exclusive and ordered: Rs, Rt2, Rn, Rt
        }
        if (cls == 0x7) {
            const bool rt = prfm ? false : data;
            if (bits(i, 24, 24)) return rt || is18(rn);  // unsigned offset
            if (bits(i, 21, 21)) {                       // register offset, atomics, pointer-authenticated
                if (op4 == 0x2) return rt || is18(rn) || is18(rm);           // register offset: Rm is always a general register
                if (op4 == 0x0) return rt || is18(rn) || (!v && is18(rm));   // atomic: Rs
                return rt || is18(rn);                                         // LDRAA, LDRAB
            }
            return rt || is18(rn);  // immediate offset, pre/post index, unprivileged, ordered
        }
        return data || is18(rn);  // anything else in the class (tags, ...): the data register and the base
    }

    // Data processing, register: x101.
    if ((op0 & 0x7) == 0x5) {
        const uint32_t top = bits(i, 28, 21);
        if (top == 0xd2) return is18(rn) || (!bits(i, 11, 11) && is18(rm));  // conditional compare: imm5 or Rm
        bool use_rm = true;
        if (bits(i, 30, 30) && !bits(i, 29, 29) && top == 0xd6) use_rm = false;  // one source: opcode2
        const bool three = bits(i, 28, 24) == 0x1b;                              // three source: Ra
        return is18(rd) || is18(rn) || (use_rm && is18(rm)) || (three && is18(ra));
    }

    // Data processing, SIMD and FP: x111. Only the moves between the general
    // and the vector registers name a general register.
    if ((op0 & 0x7) == 0x7) {
        if (bits(i, 28, 24) == 0x1e && bits(i, 30, 29) == 0 && (bits(i, 21, 21) == 0 || bits(i, 15, 10) == 0)) {
            // Conversions between FP and integer (bit 21 set, bits 15..10 clear) and fixed point
            // (bit 21 clear): the general register is Rn for SCVTF, UCVTF and FMOV to FP (opcodes
            // 010, 011, 111), else Rd.
            const uint32_t opcode = bits(i, 18, 16);
            const bool gpr_in_rn = opcode == 2 || opcode == 3 || (bits(i, 21, 21) && opcode == 7);
            return gpr_in_rn ? is18(rn) : is18(rd);
        }
        if (bits(i, 31, 31) == 0 && bits(i, 29, 29) == 0 && bits(i, 28, 21) == 0x70 && bits(i, 15, 15) == 0 &&
            bits(i, 10, 10) == 1) {  // SIMD copy
            const uint32_t imm4 = bits(i, 14, 11);
            if (imm4 == 1 || imm4 == 3) return is18(rn);  // DUP, INS from a general register
            if (imm4 == 5 || imm4 == 7) return is18(rd);  // SMOV, UMOV to a general register
        }
        return false;
    }

    // SVE and SME (op0 0010, and 0000 with bit 31 set) are not scanned: z18 is a vector register.

    return false;  // reserved
}
// Does the instruction write x18 (a destination, a write-back base, `mrs x18`)? Reading it is harmless.
// "Yes" when a form is not decoded.
inline bool writes_x18(uint32_t i) {
    using namespace x18_detail;
    if (!uses_x18(i)) return false;
    const uint32_t rd = bits(i, 4, 0), rn = bits(i, 9, 5), rm = bits(i, 20, 16), ra = bits(i, 14, 10);
    const uint32_t op0 = bits(i, 28, 25);

    if ((op0 & 0xe) == 0x8) return is18(rd);  // data processing, immediate: Rd
    if ((op0 & 0xe) == 0xa) {                // branches and system
        if (bits(i, 31, 22) == 0x354) return bits(i, 21, 21) && is18(rd);  // MRS and SYSL write Rt; MSR and SYS read it
        if (bits(i, 31, 22) == 0x355) return bits(i, 21, 21) && (is18(rd) || rd == 17);
        return false;  // CBZ, TBZ, BR: sources
    }
    if ((op0 & 0x5) == 0x4) {  // loads and stores
        const bool v = bits(i, 26, 26) != 0;
        const uint32_t cls = bits(i, 29, 27), opc = bits(i, 23, 22), op4 = bits(i, 11, 10);
        const bool load = opc != 0 && !(bits(i, 31, 30) == 3 && opc == 2);  // single-register forms; PRFM is no load
        if (cls == 0x5) {            // pairs: bit 22 is the load bit, bits 24..23 the addressing mode
            const bool wb = bits(i, 24, 23) == 1 || bits(i, 24, 23) == 3;
            return (bits(i, 22, 22) && !v && (is18(rd) || is18(ra))) || (wb && is18(rn));
        }
        if (cls == 0x3 && bits(i, 25, 24) == 0) return !v && is18(rd);  // literal: a load
        if (cls == 0x1) {
            if (v) return bits(i, 23, 23) && is18(rn);  // SIMD structures: post-index writes the base
            return bits(i, 22, 22) ? (is18(rd) || is18(ra)) : is18(rm);  // exclusive: loads write Rt, stores write Rs
        }
        if (cls == 0x7) {
            if (bits(i, 24, 24)) return load && !v && is18(rd);  // unsigned offset
            if (bits(i, 21, 21)) {
                if (op4 == 0x2) return load && !v && is18(rd);  // register offset
                if (op4 == 0x0) return !v && is18(rd);          // atomics write Rt (the old value)
                return (load && !v && is18(rd)) || (op4 == 0x3 && is18(rn));  // LDRAA, LDRAB
            }
            return (load && !v && is18(rd)) || ((op4 == 1 || op4 == 3) && is18(rn));  // imm9: post and pre index
        }
        return is18(rd) || is18(rn);
    }
    if ((op0 & 0x7) == 0x5) return bits(i, 28, 21) == 0xd2 ? false : is18(rd);  // data processing, register
    if ((op0 & 0x7) == 0x7) {                                                      // FP and SIMD moves
        if (bits(i, 28, 24) == 0x1e && bits(i, 30, 29) == 0 && (bits(i, 21, 21) == 0 || bits(i, 15, 10) == 0)) {
            const uint32_t opcode = bits(i, 18, 16);
            const bool gpr_in_rn = opcode == 2 || opcode == 3 || (bits(i, 21, 21) && opcode == 7);
            return !gpr_in_rn && is18(rd);
        }
        return bits(i, 28, 21) == 0x70 && (bits(i, 14, 11) == 5 || bits(i, 14, 11) == 7) && is18(rd);
    }
    return true;
}

// `ldp x18, x19, [xN, #imm]`: libunwind's jumpto and longjmp restore a context saved on the same thread,
// so x18 gets the TEB back. Not a scratch use.
inline bool is_context_restore(uint32_t i) {
    return (i & 0xffc07c1f) == 0xa9404c12;  // ldp x18, x19, [xN, #imm]: the offset form, any base and offset
}

struct X18Scan {
    size_t uses = 0;          // instructions that name x18
    size_t first_offset = 0;  // byte offset in the scanned range of the first
    size_t writes = 0;        // ... that write it (not a context restore): x18 as a scratch register
    size_t first_write = 0;   // byte offset of the first of those
    bool found() const { return uses != 0; }
    bool scratch() const { return writes != 0; }  // the code cannot run as it is: x18 is the TEB here
};

// Scans `len` bytes of little-endian A64 code (4-byte aligned) at `code`.
inline X18Scan scan_x18(const uint8_t* code, size_t len) {
    X18Scan r;
    for (size_t at = 0; at + 4 <= len; at += 4) {
        const uint32_t w = static_cast<uint32_t>(code[at]) | static_cast<uint32_t>(code[at + 1]) << 8 |
                           static_cast<uint32_t>(code[at + 2]) << 16 | static_cast<uint32_t>(code[at + 3]) << 24;
        if (uses_x18(w)) {
            if (!r.uses) r.first_offset = at;
            ++r.uses;
            if (writes_x18(w) && !is_context_restore(w)) {
                if (!r.writes) r.first_write = at;
                ++r.writes;
            }
        }
    }
    return r;
}

// ---- which words are code -------------------------------------------------------------------------------
//
// .text can hold strings (BoringSSL's "ARMv8..."), whose words may decode as x18 uses. A word outside every
// function .eh_frame lists is skipped when its four bytes are printable ASCII; everything else is scanned.

struct FdeRange {
    uint64_t lo, hi;  // virtual addresses
};

inline uint64_t eh_read_uleb(const uint8_t*& p, const uint8_t* end) {
    uint64_t v = 0;
    for (int shift = 0; p < end && shift < 64; shift += 7) {
        const uint8_t b = *p++;
        v |= static_cast<uint64_t>(b & 0x7f) << shift;
        if (!(b & 0x80)) break;
    }
    return v;
}

// The function ranges a .eh_frame section describes (CIE augmentation "zR", the usual pointer encodings).
inline std::vector<FdeRange> eh_frame_functions(const uint8_t* sec, size_t size, uint64_t vaddr) {
    std::vector<FdeRange> out;
    auto rd32 = [&](size_t at) {
        uint32_t v;
        std::memcpy(&v, sec + at, 4);
        return v;
    };
    // The pointer encoding of each CIE, by its offset.
    struct Cie {
        size_t at;
        uint8_t enc;
    };
    std::vector<Cie> cies;
    size_t at = 0;
    while (at + 4 <= size) {
        uint64_t len = rd32(at);
        size_t hdr = 4;
        if (len == 0) break;
        if (len == 0xffffffffu) return out;  // 64-bit lengths: not seen in arm64 libraries
        const size_t next = at + hdr + static_cast<size_t>(len);
        if (next > size || at + 8 > size) break;
        const uint32_t id = rd32(at + 4);
        const uint8_t* p = sec + at + 8;
        const uint8_t* end = sec + next;
        if (id == 0) {  // a CIE: version, augmentation string, factors, return register, augmentation data
            if (p >= end) break;
            const uint8_t version = *p++;
            const char* aug = reinterpret_cast<const char*>(p);
            while (p < end && *p) ++p;
            const bool z = aug[0] == 'z';
            if (p < end) ++p;
            if (version >= 4) p += 2;  // address and segment sizes
            eh_read_uleb(p, end);      // code alignment
            eh_read_uleb(p, end);      // data alignment (signed, read the same)
            if (version == 1) ++p; else eh_read_uleb(p, end);  // return address register
            uint8_t enc = 0;
            if (z) {
                const uint64_t alen = eh_read_uleb(p, end);
                const uint8_t* a = p;
                for (const char* c = aug + 1; *c && a < p + alen; ++c) {
                    if (*c == 'R') enc = *a++;
                    else if (*c == 'L') ++a;
                    else if (*c == 'P') { const uint8_t pe = *a++; a += (pe & 7) == 4 ? 8 : 4; }
                    else if (*c == 'S' || *c == 'B') {}
                }
            }
            cies.push_back({at, enc});
        } else {  // an FDE: id is the distance back to its CIE
            const size_t cie_at = at + 4 - id;
            uint8_t enc = 0x1b;
            for (const Cie& c : cies) {
                if (c.at == cie_at) enc = c.enc;
            }
            const bool sdata4 = (enc & 0x0f) == 0x0b, udata4 = (enc & 0x0f) == 0x03, pcrel = (enc & 0x70) == 0x10;
            if ((sdata4 || udata4) && p + 8 <= end) {
                const size_t loc_at = static_cast<size_t>(p - sec);
                int64_t loc = sdata4 ? static_cast<int32_t>(rd32(loc_at)) : static_cast<int64_t>(rd32(loc_at));
                if (pcrel) loc += static_cast<int64_t>(vaddr + loc_at);
                const uint32_t range = rd32(loc_at + 4);
                if (range) out.push_back({static_cast<uint64_t>(loc), static_cast<uint64_t>(loc) + range});
            }
        }
        at = next;
    }
    std::sort(out.begin(), out.end(), [](const FdeRange& a, const FdeRange& b) { return a.lo < b.lo; });
    return out;
}

inline bool in_functions(const std::vector<FdeRange>& fns, uint64_t va) {
    size_t lo = 0, hi = fns.size();
    while (lo < hi) {  // the last range that starts at or before va
        const size_t mid = (lo + hi) / 2;
        if (fns[mid].lo <= va) lo = mid + 1; else hi = mid;
    }
    return lo > 0 && va < fns[lo - 1].hi;
}

inline bool looks_like_text(uint32_t w) {
    for (int i = 0; i < 4; ++i) {
        const uint32_t b = (w >> (8 * i)) & 0xff;
        if (b != 0 && (b < 0x20 || b > 0x7e)) return false;
    }
    return true;
}

// The code of an arm64 ELF file: its SHF_EXECINSTR sections, or its executable segments when it has no
// section headers (lld's first segment also holds the headers and symbol tables).
struct ExecRange {
    uint64_t offset;  // in the file
    uint64_t size;
    uint64_t vaddr;   // where the loader maps it, before the load bias
};

struct ElfCode {
    std::vector<ExecRange> ranges;
    std::vector<FdeRange> functions;  // from .eh_frame, if the file has it
    bool by_sections = false;
    bool ok = false;  // false: not a readable arm64 ELF file
};

inline ElfCode elf_code(const uint8_t* file, size_t size) {
    ElfCode r;
    elf::Ehdr eh;
    if (size < sizeof eh) return r;
    std::memcpy(&eh, file, sizeof eh);
    if (eh.e_ident[0] != elf::kMag0 || eh.e_ident[1] != elf::kMag1 || eh.e_ident[2] != elf::kMag2 ||
        eh.e_ident[3] != elf::kMag3 || eh.e_machine != elf::kEmAarch64)
        return r;
    r.ok = true;
    auto inside = [&](uint64_t off, uint64_t len) { return off <= size && len <= size - off; };
    if (eh.e_shoff && eh.e_shnum && eh.e_shentsize >= sizeof(elf::Shdr) && eh.e_shoff <= size &&
        static_cast<uint64_t>(eh.e_shnum) * eh.e_shentsize <= size - eh.e_shoff) {
        for (unsigned i = 0; i < eh.e_shnum; ++i) {
            elf::Shdr sh;
            std::memcpy(&sh, file + eh.e_shoff + static_cast<size_t>(i) * eh.e_shentsize, sizeof sh);
            if (sh.sh_type == elf::kShtProgbits && (sh.sh_flags & elf::kShfExecinstr) && inside(sh.sh_offset, sh.sh_size)) {
                r.by_sections = true;
                r.ranges.push_back({sh.sh_offset, sh.sh_size, sh.sh_addr});
            }
        }
        // .eh_frame, by name
        if (eh.e_shstrndx < eh.e_shnum) {
            elf::Shdr strs;
            std::memcpy(&strs, file + eh.e_shoff + static_cast<size_t>(eh.e_shstrndx) * eh.e_shentsize, sizeof strs);
            for (unsigned i = 0; i < eh.e_shnum && inside(strs.sh_offset, strs.sh_size); ++i) {
                elf::Shdr sh;
                std::memcpy(&sh, file + eh.e_shoff + static_cast<size_t>(i) * eh.e_shentsize, sizeof sh);
                if (sh.sh_type != elf::kShtProgbits || sh.sh_name + 10 > strs.sh_size || !inside(sh.sh_offset, sh.sh_size)) continue;
                if (std::memcmp(file + strs.sh_offset + sh.sh_name, ".eh_frame", 10) == 0)  // with its terminator
                    r.functions = eh_frame_functions(file + sh.sh_offset, static_cast<size_t>(sh.sh_size), sh.sh_addr);
            }
        }
    }
    if (!r.by_sections) {
        for (unsigned i = 0; i < eh.e_phnum; ++i) {
            elf::Phdr ph;
            const uint64_t at = eh.e_phoff + static_cast<uint64_t>(i) * eh.e_phentsize;
            if (!inside(at, sizeof ph)) break;
            std::memcpy(&ph, file + at, sizeof ph);
            if (ph.p_type == elf::kPtLoad && (ph.p_flags & elf::kPfX) && inside(ph.p_offset, ph.p_filesz))
                r.ranges.push_back({ph.p_offset, ph.p_filesz, ph.p_vaddr});
        }
    }
    return r;
}

// first_offset is a file offset. by_sections tells which of the two was done.
struct ElfX18Scan {
    X18Scan scan;
    bool by_sections = false;
    bool ok = false;  // false: not a readable arm64 ELF file
};

inline ElfX18Scan scan_elf_x18(const uint8_t* file, size_t size) {
    ElfX18Scan r;
    const ElfCode code = elf_code(file, size);
    r.ok = code.ok;
    r.by_sections = code.by_sections;
    for (const ExecRange& e : code.ranges) {
        for (uint64_t at = 0; at + 4 <= e.size; at += 4) {
            uint32_t w;
            std::memcpy(&w, file + e.offset + at, 4);
            if (!code.functions.empty() && !in_functions(code.functions, e.vaddr + at) && looks_like_text(w)) continue;
            if (!uses_x18(w)) continue;
            if (!r.scan.uses) r.scan.first_offset = static_cast<size_t>(e.offset + at);
            ++r.scan.uses;
            if (writes_x18(w) && !is_context_restore(w)) {
                if (!r.scan.writes) r.scan.first_write = static_cast<size_t>(e.offset + at);
                ++r.scan.writes;
            }
        }
    }
    return r;
}

// `svc #imm16`. Linux code makes system calls with `svc #0` (x8 the number, x0-x5 the arguments).
inline bool is_svc(uint32_t i) { return (i & 0xffe0001f) == 0xd4000001; }
inline uint32_t svc_imm(uint32_t i) { return (i >> 5) & 0xffff; }

}  // namespace winapk::arm64
