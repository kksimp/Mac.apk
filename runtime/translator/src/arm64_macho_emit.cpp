// arm64_macho_emit.cpp -- in-process arm64 Mach-O wrapper-dylib emitter.
//
// See arm64_macho_emit.h for the contract. This file builds a valid
// arm64 MH_DYLIB in the older "classic" Mach-O format (LC_DYLD_INFO_ONLY
// rebase/bind/export-trie + a __mod_init_func constructor pointer), which
// dyld still accepts, instead of the modern chained-fixups +
// LC_DYLD_EXPORTS_TRIE + __init_offsets layout that clang emits. The
// classic format is far simpler to synthesise by hand and needs no
// linker.
//
// The emitted dylib is byte-for-byte its own thing -- it is NOT meant to
// match clang's output. It is meant to be semantically identical and to
// load + run identically. Validation is by actually loading it (see the
// acceptance tests in the task spec).
//
// Layout (page size 0x4000 == 16 KiB, the Apple Silicon page size):
//
//   __TEXT     vmaddr 0       (mach header + load commands at its start)
//     __text     constructor, then N trampolines
//     __elfblob  raw ELF bytes   [embedded-blob format ONLY -- the
//                                 no-embed format omits this section
//                                 entirely, so __TEXT has 2 sections
//                                 instead of 3]
//     __cstring  soname\0 [so_path\0] name0\0 name1\0 ...
//   __DATA     next page
//     __got      2 pointers (bound to the 2 shim functions)
//     __modinit  1 pointer  (rebased -> constructor vmaddr)
//     __nameptrs N pointers (rebased -> __cstring name vmaddrs)
//     __bss      zerofill: handle (8) + targets[N] (N*8)
//   __LINKEDIT next page
//     rebase info, bind info, export trie, nlist array, string table
//
// All non-trivial constants / encodings are taken verbatim from the
// task spec, which in turn was cross-checked against the clang
// reference (otool / dyld_info on /tmp/ref_wrapper.dylib).

#include "macapk/arm64_macho_emit.h"

#include <mach-o/loader.h>
#include <mach-o/nlist.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace macapk::translator {

namespace {

// ------------------------------------------------------------------
// Small constants the system headers do not always spell out the same
// way. Use our own to keep the encoding self-documenting.
// ------------------------------------------------------------------

constexpr uint64_t kPageSize = 0x4000;  // 16 KiB Apple Silicon page

// Round `v` up to a multiple of `a` (a must be a power of two).
inline uint64_t round_up(uint64_t v, uint64_t a) {
    return (v + (a - 1)) & ~(a - 1);
}

// ------------------------------------------------------------------
// Byte-buffer helper. Little-endian appends.
// ------------------------------------------------------------------

struct Buf {
    std::vector<uint8_t> b;

    size_t size() const { return b.size(); }
    void u8(uint8_t v) { b.push_back(v); }
    void u16(uint16_t v) { for (int i = 0; i < 2; ++i) b.push_back(uint8_t(v >> (8 * i))); }
    void u32(uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); }
    void u64(uint64_t v) { for (int i = 0; i < 8; ++i) b.push_back(uint8_t(v >> (8 * i))); }
    void bytes(const void* p, size_t n) {
        const uint8_t* s = static_cast<const uint8_t*>(p);
        b.insert(b.end(), s, s + n);
    }
    void cstr(const std::string& s) { bytes(s.data(), s.size()); b.push_back(0); }
    void pad_to(size_t n) { while (b.size() < n) b.push_back(0); }
    void align(size_t a) { while (b.size() % a) b.push_back(0); }
};

// ULEB128: low 7 bits per byte, 0x80 set on all but the last.
void uleb(Buf& out, uint64_t v) {
    do {
        uint8_t byte = v & 0x7f;
        v >>= 7;
        if (v) byte |= 0x80;
        out.u8(byte);
    } while (v);
}

// Number of bytes a value occupies as ULEB128 (for fixed-point sizing
// of the export trie, where child offsets reference later nodes).
size_t uleb_size(uint64_t v) {
    size_t n = 0;
    do { ++n; v >>= 7; } while (v);
    return n;
}

// ------------------------------------------------------------------
// AArch64 instruction encoders (32-bit little-endian words). Each is a
// pure function of the spec's documented field layout.
// ------------------------------------------------------------------

// ADRP Xd, <page of target> -- PC is the *page* of the instruction.
uint32_t enc_adrp(uint32_t rd, int64_t page_delta) {
    uint32_t immlo = uint32_t(page_delta) & 3;
    uint32_t immhi = uint32_t(page_delta >> 2) & 0x7ffff;
    return 0x90000000u | (immlo << 29) | (immhi << 5) | (rd & 31);
}
// ADD Xd, Xn, #imm12 {, LSL #(12*sh)}
uint32_t enc_add_imm(uint32_t rd, uint32_t rn, uint32_t imm12, uint32_t sh) {
    return 0x91000000u | (sh << 22) | ((imm12 & 0xfff) << 10) | ((rn & 31) << 5) | (rd & 31);
}
// SUBS Xd, Xn, #imm12  (CMP = SUBS with Rd=31)
uint32_t enc_subs_imm(uint32_t rd, uint32_t rn, uint32_t imm12) {
    return 0xf1000000u | ((imm12 & 0xfff) << 10) | ((rn & 31) << 5) | (rd & 31);
}
// LDR Xt, [Xn, #pimm]   (64-bit unsigned-offset, pimm is a byte offset
// that must be a multiple of 8; the encoded immediate is pimm/8)
uint32_t enc_ldr_imm(uint32_t rt, uint32_t rn, uint32_t pimm) {
    uint32_t imm12 = (pimm / 8) & 0xfff;
    return 0xf9400000u | (imm12 << 10) | ((rn & 31) << 5) | (rt & 31);
}
// LDR Xt, [Xn, Xm, LSL #3]  (register offset, scaled by 8)
uint32_t enc_ldr_reg(uint32_t rt, uint32_t rn, uint32_t rm) {
    return 0xf8607800u | ((rm & 31) << 16) | ((rn & 31) << 5) | (rt & 31);
}
// STR Xt, [Xn, #pimm]
uint32_t enc_str_imm(uint32_t rt, uint32_t rn, uint32_t pimm) {
    uint32_t imm12 = (pimm / 8) & 0xfff;
    return 0xf9000000u | (imm12 << 10) | ((rn & 31) << 5) | (rt & 31);
}
// STR Xt, [Xn, Xm, LSL #3]
uint32_t enc_str_reg(uint32_t rt, uint32_t rn, uint32_t rm) {
    return 0xf8207800u | ((rm & 31) << 16) | ((rn & 31) << 5) | (rt & 31);
}
uint32_t enc_br(uint32_t rn) { return 0xd61f0000u | ((rn & 31) << 5); }
uint32_t enc_blr(uint32_t rn) { return 0xd63f0000u | ((rn & 31) << 5); }
uint32_t enc_ret() { return 0xd65f03c0u; }
// CBZ Xt, <off>  -- off is byte delta from this insn.
uint32_t enc_cbz(uint32_t rt, int64_t off) {
    uint32_t imm19 = uint32_t(off >> 2) & 0x7ffff;
    return 0xb4000000u | (imm19 << 5) | (rt & 31);
}
// B <off> -- off is byte delta from this insn.
uint32_t enc_b(int64_t off) {
    uint32_t imm26 = uint32_t(off >> 2) & 0x3ffffff;
    return 0x14000000u | imm26;
}
// MOVZ Xd, #imm16, LSL #(hw*16) / MOVK ...
uint32_t enc_movz(uint32_t rd, uint32_t imm16, uint32_t hw) {
    return 0xd2800000u | (hw << 21) | ((imm16 & 0xffff) << 5) | (rd & 31);
}
uint32_t enc_movk(uint32_t rd, uint32_t imm16, uint32_t hw) {
    return 0xf2800000u | (hw << 21) | ((imm16 & 0xffff) << 5) | (rd & 31);
}

// ------------------------------------------------------------------
// Export trie (Patricia / radix tree over the export symbol names,
// which carry the leading Mach-O underscore). Image-relative addresses.
// ------------------------------------------------------------------

struct TrieEntry { std::string name; uint64_t addr; };

struct TrieNode {
    // Edge label leading into this node (consumed from the parent).
    std::string edge;
    bool is_terminal = false;
    uint64_t addr = 0;                  // image-relative, valid if terminal
    std::vector<int> children;          // indices into the node pool
    // Filled during serialisation:
    size_t offset = 0;                  // byte offset of this node in the blob
};

class ExportTrie {
public:
    explicit ExportTrie(const std::vector<TrieEntry>& entries) {
        // Root node.
        nodes_.push_back(TrieNode{});
        for (const auto& e : entries) insert(0, e.name, e.addr);
    }

    // Serialise to a flat byte blob. Child offsets are back-references
    // (relative to the blob start) to nodes that may sit *after* the
    // current one, and the ULEB width of those offsets depends on their
    // value -- so we iterate the whole layout to a fixed point.
    std::vector<uint8_t> serialize() {
        // Order: a simple pre-order works; dyld only requires that every
        // child offset is consistent, not a particular traversal.
        std::vector<int> order;
        order.reserve(nodes_.size());
        preorder(0, order);

        std::vector<uint8_t> blob;
        // Seed offsets from a first pass that assumes 1-byte child
        // offsets, then re-run until stable.
        for (int idx : order) nodes_[idx].offset = 0;
        bool changed = true;
        int guard = 0;
        while (changed && guard++ < 64) {
            changed = false;
            size_t cursor = 0;
            for (int idx : order) {
                if (nodes_[idx].offset != cursor) { nodes_[idx].offset = cursor; changed = true; }
                cursor += node_size(idx);
            }
        }
        // Final emit with the converged offsets.
        for (int idx : order) emit_node(blob, idx);
        return blob;
    }

private:
    std::vector<TrieNode> nodes_;

    void insert(int at, const std::string& key, uint64_t addr) {
        // Walk children looking for one whose edge shares a prefix.
        //
        // IMPORTANT: every nodes_.push_back() may reallocate the vector,
        // invalidating any TrieNode& or std::string& into nodes_. So we
        // (a) index children by position, (b) copy the edge string out by
        // VALUE before computing, and (c) never hold a reference into
        // nodes_ across a push_back -- re-index with nodes_[idx] each time.
        const size_t nchildren = nodes_[at].children.size();
        for (size_t k = 0; k < nchildren; ++k) {
            const int ci = nodes_[at].children[k];
            const std::string edge = nodes_[ci].edge;   // copy by value
            size_t common = 0;
            while (common < edge.size() && common < key.size() &&
                   edge[common] == key[common])
                ++common;
            if (common == 0) continue;  // no shared prefix with this edge
            if (common == edge.size()) {
                // Edge fully consumed; recurse with the remainder.
                if (common == key.size()) {
                    nodes_[ci].is_terminal = true;
                    nodes_[ci].addr = addr;
                } else {
                    insert(ci, key.substr(common), addr);
                }
                return;
            }
            // Partial overlap: split this edge at `common`. Allocate the
            // split (and possibly a leaf) first, then assign fields via
            // fresh nodes_[idx] lookups so no dangling reference survives a
            // reallocation.
            const int split = int(nodes_.size());
            nodes_.push_back(TrieNode{});
            int leaf = -1;
            if (common != key.size()) {
                leaf = int(nodes_.size());
                nodes_.push_back(TrieNode{});
            }
            // split node: shared prefix; its child is the old node (which
            // keeps the tail of its edge).
            nodes_[split].edge = edge.substr(0, common);
            nodes_[split].children.push_back(ci);
            nodes_[ci].edge = edge.substr(common);
            if (leaf < 0) {
                // key ends exactly at the split point.
                nodes_[split].is_terminal = true;
                nodes_[split].addr = addr;
            } else {
                nodes_[leaf].edge = key.substr(common);
                nodes_[leaf].is_terminal = true;
                nodes_[leaf].addr = addr;
                nodes_[split].children.push_back(leaf);
            }
            nodes_[at].children[k] = split;  // rewire parent's slot
            return;
        }
        // No existing child shares a prefix: add a fresh leaf.
        const int leaf = int(nodes_.size());
        nodes_.push_back(TrieNode{});
        nodes_[leaf].edge = key;
        nodes_[leaf].is_terminal = true;
        nodes_[leaf].addr = addr;
        nodes_[at].children.push_back(leaf);
    }

    void preorder(int idx, std::vector<int>& order) {
        order.push_back(idx);
        for (int c : nodes_[idx].children) preorder(c, order);
    }

    // Byte size of node `idx` given the current converged child offsets.
    size_t node_size(int idx) {
        const TrieNode& n = nodes_[idx];
        size_t sz = 0;
        if (n.is_terminal) {
            size_t term = uleb_size(0 /*flags*/) + uleb_size(n.addr);
            sz += uleb_size(term);   // terminalSize prefix
            sz += term;              // the terminal payload itself
        } else {
            sz += uleb_size(0);      // terminalSize = 0
        }
        sz += 1;                     // childCount byte
        for (int c : nodes_[idx].children) {
            sz += nodes_[c].edge.size() + 1;       // edge cstring + NUL
            sz += uleb_size(nodes_[c].offset);     // child node offset
        }
        return sz;
    }

    void emit_node(std::vector<uint8_t>& blob, int idx) {
        Buf tmp;
        const TrieNode& n = nodes_[idx];
        if (n.is_terminal) {
            Buf term;
            uleb(term, 0);          // flags = regular export
            uleb(term, n.addr);     // image-relative address
            uleb(tmp, term.size()); // terminalSize
            tmp.bytes(term.b.data(), term.b.size());
        } else {
            uleb(tmp, 0);           // terminalSize = 0
        }
        tmp.u8(uint8_t(n.children.size()));
        for (int c : nodes_[idx].children) {
            tmp.cstr(nodes_[c].edge);
            uleb(tmp, nodes_[c].offset);
        }
        blob.insert(blob.end(), tmp.b.begin(), tmp.b.end());
    }
};

}  // namespace

// ----------------------------------------------------------------------
// The emitter.
// ----------------------------------------------------------------------

int emit_arm64_wrapper_dylib(const std::string& out_path,
                             const std::vector<const Symbol64*>& exports,
                             const uint8_t* elf_blob,
                             size_t elf_size,
                             const std::string& so_path_rel,
                             const std::string& soname,
                             const std::string& shim_install_name,
                             const std::string& shim_dir,
                             bool verbose,
                             bool deferred_jni_onload) {
    const size_t N = exports.size();

    // t3-mmap-emit (2026-07-24): which of the two wrapper formats to emit.
    // Non-empty so_path_rel == "Lever 3" no-embed: no __elfblob section,
    // the source .so's relative path goes in __cstring, and the
    // constructor calls the 2-arg __macapk_arm64_native_load_from_path.
    // Empty == the original self-contained embedded-blob format. The
    // caller (arm64_pack.cpp) makes the choice; see its so_path_rel /
    // MACAPK_ARM64_EMBED_BLOB block, which now gates BOTH emitters.
    //
    // Every layout decision below that used to be unconditional is
    // branched on this flag rather than duplicated into a parallel
    // emitter: the two formats differ only in (a) __TEXT's section count,
    // (b) whether elfblob_off/elf_size participate in the __TEXT layout,
    // (c) three instructions' worth of constructor argument setup, and
    // (d) which shim symbol __got[0] binds to. Everything else -- the
    // trampolines, the export trie, symtab, rebase/bind opcodes, the
    // __DATA and __LINKEDIT layout -- is format-independent.
    const bool no_embed = !so_path_rel.empty();

    // ---- install name for LC_ID_DYLIB: @rpath/<soname>.dylib ----------
    const std::string id_name = "@rpath/" + soname + ".dylib";
    const std::string libsystem_name = "/usr/lib/libSystem.B.dylib";
    const std::string rpath_loader = "@loader_path";

    // ==================================================================
    // STEP 1. Size the load-command region. The command set is fixed; we
    // need the byte size before laying out __TEXT (the header + LCs live
    // at the start of __TEXT).
    // ==================================================================

    auto lc_str_dylib_size = [](const std::string& path) -> uint32_t {
        // dylib_command head = 24 bytes, then NUL-terminated path, rounded
        // up to 8.
        return uint32_t(round_up(24 + path.size() + 1, 8));
    };
    auto lc_str_rpath_size = [](const std::string& path) -> uint32_t {
        // rpath_command head = 12 bytes, then NUL path, rounded up to 8.
        return uint32_t(round_up(12 + path.size() + 1, 8));
    };

    const uint32_t kSegCmd = uint32_t(sizeof(struct segment_command_64));   // 72
    const uint32_t kSect   = uint32_t(sizeof(struct section_64));           // 80

    // __TEXT has 3 sections (__text/__elfblob/__cstring), or 2 in the
    // no-embed format (__text/__cstring); __DATA has 4, __LINKEDIT 0.
    const uint32_t text_nsects = no_embed ? 2u : 3u;
    const uint32_t text_seg_cmdsize = kSegCmd + text_nsects * kSect;
    const uint32_t data_seg_cmdsize = kSegCmd + 4 * kSect;
    const uint32_t link_seg_cmdsize = kSegCmd;

    const uint32_t dyldinfo_cmdsize = uint32_t(sizeof(struct dyld_info_command));  // 48
    const uint32_t symtab_cmdsize   = uint32_t(sizeof(struct symtab_command));     // 24
    const uint32_t dysymtab_cmdsize = uint32_t(sizeof(struct dysymtab_command));   // 80

    const uint32_t iddylib_cmdsize  = lc_str_dylib_size(id_name);
    const uint32_t loadshim_cmdsize = lc_str_dylib_size(shim_install_name);
    const uint32_t loadsys_cmdsize  = lc_str_dylib_size(libsystem_name);
    const uint32_t rpath1_cmdsize   = lc_str_rpath_size(rpath_loader);
    const uint32_t rpath2_cmdsize   = lc_str_rpath_size(shim_dir);
    const uint32_t buildver_cmdsize = uint32_t(sizeof(struct build_version_command)); // 24
    const uint32_t uuid_cmdsize     = uint32_t(sizeof(struct uuid_command));          // 24

    // 13 load commands: 3x LC_SEGMENT_64 (__TEXT/__DATA/__LINKEDIT),
    // LC_DYLD_INFO_ONLY, LC_SYMTAB, LC_DYSYMTAB, LC_ID_DYLIB,
    // 2x LC_LOAD_DYLIB (shim + libSystem), 2x LC_RPATH, LC_BUILD_VERSION,
    // LC_UUID. (LC_UUID is required by some tooling, e.g. dyld_info, and
    // is conventional for every well-formed dylib.)
    const uint32_t ncmds = 13;
    const uint32_t sizeofcmds =
        text_seg_cmdsize + data_seg_cmdsize + link_seg_cmdsize +
        dyldinfo_cmdsize + symtab_cmdsize + dysymtab_cmdsize +
        iddylib_cmdsize + loadshim_cmdsize + loadsys_cmdsize +
        rpath1_cmdsize + rpath2_cmdsize + buildver_cmdsize + uuid_cmdsize;

    const uint64_t header_size = sizeof(struct mach_header_64);  // 32
    const uint64_t lc_end = header_size + sizeofcmds;

    // codesign appends an LC_CODE_SIGNATURE (a linkedit_data_command, 16
    // bytes) right after our last load command, bumping ncmds/sizeofcmds.
    // We must leave at least that many bytes of slack between the end of
    // our load commands and the first __text content, or codesign would
    // overwrite the constructor's leading instructions. Reserve it here.
    const uint64_t kCodesignReserve = sizeof(struct linkedit_data_command);

    // ==================================================================
    // STEP 2. Lay out __TEXT. header+LCs at [0, lc_end); then __text
    // (4-aligned, with codesign slack), __elfblob (16-aligned), __cstring.
    // ==================================================================

    const uint64_t text_section_off = round_up(lc_end + kCodesignReserve, 16);

    // ---- 2a. __cstring contents: soname\0 [so_path\0] name0\0 ... ------
    // (Built first so we know its size; its vmaddr is computed below.)
    // The so_path entry exists only in the no-embed format, where it is
    // the constructor's arg0.
    Buf cstr;
    const uint64_t soname_str_rel = cstr.size();
    cstr.cstr(soname);
    uint64_t so_path_str_rel = 0;
    if (no_embed) {
        so_path_str_rel = cstr.size();
        cstr.cstr(so_path_rel);
    }
    std::vector<uint64_t> name_str_rel(N);
    for (size_t i = 0; i < N; ++i) {
        name_str_rel[i] = cstr.size();
        cstr.cstr(exports[i]->name);  // Android name (no leading underscore)
    }

    // ---- 2b. We must know the __text size before placing __elfblob.
    // __text holds the constructor (a fixed-shape routine) followed by N
    // trampolines (4 or 5 instructions each -- 5 when the slot byte-offset
    // i*8 needs the middle `add #high, lsl #12`).
    //
    // The constructor's *length in instructions* is independent of where
    // it sits in memory (only the encoded ADRP page deltas depend on the
    // final vmaddrs, not the instruction count). So we generate it twice
    // via gen_ctor(): once here with all address vars still 0 just to
    // learn the length, and once in STEP 5 with the resolved vmaddrs to
    // get the real bytes. All the codegen-referenced address variables
    // are declared here and filled in STEP 4 (gen_ctor captures them by
    // reference, so their values only need to be correct at call time).

    uint64_t text_vmaddr = 0;        // image base (kept explicit; == 0)
    uint64_t ctor_vmaddr = 0;        // constructor start (== __text start)
    uint64_t cstr_vmaddr = 0;        // __cstring base
    uint64_t got_vmaddr = 0;         // __got base
    uint64_t bss_vmaddr = 0;         // __bss base (handle, then targets[])
    uint64_t nameptrs_vmaddr = 0;    // __nameptrs base
    uint64_t elfblob_vmaddr = 0;     // __elfblob base (constructor arg0)

    // blob_size constant baked into the constructor (size_t arg to load).
    const uint64_t blob_size_const = elf_size;

    // The constructor generator. Emits the routine shown in the reference
    // disassembly (arm64_pack.cpp's clang output), built directly from the
    // ARM64 encoders above. Returns the instruction word list.
    auto gen_ctor = [&]() -> std::vector<uint32_t> {
        std::vector<uint32_t> w;
        // We emit instructions, tracking the per-instruction vmaddr so
        // ADRP page deltas are correct. pc starts at ctor_vmaddr.
        auto pc = [&]() -> uint64_t { return ctor_vmaddr + 4 * w.size(); };
        auto adrp_to = [&](uint32_t rd, uint64_t target) {
            int64_t d = int64_t(target >> 12) - int64_t(pc() >> 12);
            w.push_back(enc_adrp(rd, d));
        };

        // Frame layout: sp-0x20; saved x29/x30 at [sp,#0x10]; loop index
        // i lives at [sp,#0x8]. These three fixed-shape words match the
        // reference prologue verbatim (sub/stp/add).
        w.push_back(0xd10083ffu);                        // sub sp, sp, #0x20
        w.push_back(0xa9017bfdu);                        // stp x29, x30, [sp, #0x10]
        w.push_back(0x910043fdu);                        // add x29, sp, #0x10

        if (no_embed) {
            // Lever 3: load_from_path(so_path, soname) -- 2 pointer args,
            // both into __cstring. No blob address and no size constant,
            // which is exactly why the movz/movk block below is skipped
            // (and why the ctor's instruction count differs between the
            // two formats -- gen_ctor is called twice and both passes see
            // the same `no_embed`, so the measured length still matches).
            const uint64_t p = cstr_vmaddr + so_path_str_rel;
            adrp_to(0, p);
            w.push_back(enc_add_imm(0, 0, uint32_t(p & 0xfff), 0));
            const uint64_t s = cstr_vmaddr + soname_str_rel;
            adrp_to(1, s);
            w.push_back(enc_add_imm(1, 1, uint32_t(s & 0xfff), 0));
        } else {
            // --- arg0 = blob start (__elfblob vmaddr).
            // adrp x0, elfblob ; add x0, x0, #pageoff
            adrp_to(0, elfblob_vmaddr);
            w.push_back(enc_add_imm(0, 0, uint32_t(elfblob_vmaddr & 0xfff), 0));

            // --- arg1 = blob_size (size_t). Build with movz/movk (up to 4 words).
            {
                uint64_t v = blob_size_const;
                w.push_back(enc_movz(1, uint32_t(v & 0xffff), 0));
                if ((v >> 16) & 0xffff) w.push_back(enc_movk(1, uint32_t((v >> 16) & 0xffff), 1));
                if ((v >> 32) & 0xffff) w.push_back(enc_movk(1, uint32_t((v >> 32) & 0xffff), 2));
                if ((v >> 48) & 0xffff) w.push_back(enc_movk(1, uint32_t((v >> 48) & 0xffff), 3));
            }

            // --- arg2 = soname cstring vmaddr.
            adrp_to(2, cstr_vmaddr + soname_str_rel);
            w.push_back(enc_add_imm(2, 2, uint32_t((cstr_vmaddr + soname_str_rel) & 0xfff), 0));
        }

        // --- call the load entry point (whichever format's) via __got[0].
        adrp_to(8, got_vmaddr + 0);
        w.push_back(enc_ldr_imm(8, 8, uint32_t((got_vmaddr + 0) & 0xfff)));
        w.push_back(enc_blr(8));

        // --- handle = x0; store to __bss[0]; if (!handle) goto end.
        adrp_to(8, bss_vmaddr + 0);
        w.push_back(enc_str_imm(0, 8, uint32_t((bss_vmaddr + 0) & 0xfff)));  // str x0,[x8,#off]
        // reload handle into x8 to test
        w.push_back(enc_ldr_imm(8, 8, uint32_t((bss_vmaddr + 0) & 0xfff)));
        // cbz x8, end  -- patch the offset after we know `end`.
        size_t cbz_idx = w.size();
        w.push_back(0);  // placeholder

        // --- i = 0 stored at [sp,#0x8]
        w.push_back(enc_str_imm(31, 31, 0x8));  // str xzr -> use xzr(31) with str_imm: STR XZR,[sp,#8]
        // enc_str_imm(31,31,8) encodes STR x31([==xzr]),[x31(sp),#8]; correct.

        // --- loop_top:
        size_t loop_top_idx = w.size();
        // if (i >= N) goto end:  load i, cmp i, #N, b.hs end
        w.push_back(enc_ldr_imm(8, 31, 0x8));         // ldr x8, [sp, #8]
        // For N up to 4095 we can use a single subs #imm12. Larger N is
        // not realistic for a single ELF's export count in this loop's
        // immediate, but guard anyway by materialising N in a register.
        size_t bhs_idx;
        if (N <= 0xfff) {
            w.push_back(enc_subs_imm(8, 8, uint32_t(N)));  // subs x8, x8, #N
            bhs_idx = w.size();
            w.push_back(0);  // b.hs end placeholder (B.cond cond=2 HS/CS)
        } else {
            // materialise N into x9, then cmp x8, x9 (subs x8,x8,x9 form).
            uint64_t v = N;
            w.push_back(enc_movz(9, uint32_t(v & 0xffff), 0));
            if ((v >> 16) & 0xffff) w.push_back(enc_movk(9, uint32_t((v >> 16) & 0xffff), 1));
            // subs x8, x8, x9 (SUBS shifted-register, imm6=0): 0xeb09010 8? compute.
            w.push_back(0xeb000000u | (9u << 16) | (8u << 5) | 8u);  // subs x8, x8, x9
            bhs_idx = w.size();
            w.push_back(0);  // b.hs end placeholder
        }

        // --- handle into x0: adrp x8,bss; ldr x0,[x8,#0]
        adrp_to(8, bss_vmaddr + 0);
        w.push_back(enc_ldr_imm(0, 8, uint32_t((bss_vmaddr + 0) & 0xfff)));   // ldr x0,[x8,#0]
        // --- name = __nameptrs[i]: adrp x8,nameptrs; add x8,x8,#off; ldr x1,[x8,x9,lsl#3]
        w.push_back(enc_ldr_imm(9, 31, 0x8));         // ldr x9, [sp, #8]  (i)
        adrp_to(8, nameptrs_vmaddr);
        w.push_back(enc_add_imm(8, 8, uint32_t(nameptrs_vmaddr & 0xfff), 0));
        w.push_back(enc_ldr_reg(1, 8, 9));            // ldr x1, [x8, x9, lsl #3]
        // --- call __macapk_arm64_native_dlsym via __got[1]
        adrp_to(8, got_vmaddr + 8);
        w.push_back(enc_ldr_imm(8, 8, uint32_t((got_vmaddr + 8) & 0xfff)));
        w.push_back(enc_blr(8));
        // --- targets[i] = x0: i lives in __bss as targets[] base = bss+8.
        //     adrp x8,bss; add x8,x8,#(8); ldr x9,[sp,#8]; str x0,[x8,x9,lsl#3]
        w.push_back(enc_ldr_imm(9, 31, 0x8));         // ldr x9,[sp,#8]
        adrp_to(8, bss_vmaddr + 8);
        w.push_back(enc_add_imm(8, 8, uint32_t((bss_vmaddr + 8) & 0xfff), 0));
        w.push_back(enc_str_reg(0, 8, 9));            // str x0, [x8, x9, lsl #3]
        // --- i++  : ldr x8,[sp,#8]; add x8,x8,#1; str x8,[sp,#8]
        w.push_back(enc_ldr_imm(8, 31, 0x8));
        w.push_back(enc_add_imm(8, 8, 1, 0));
        w.push_back(enc_str_imm(8, 31, 0x8));
        // --- b loop_top
        {
            int64_t off = int64_t(loop_top_idx) * 4 - int64_t(w.size()) * 4;
            w.push_back(enc_b(off));
        }

        // --- end: epilogue
        size_t end_idx = w.size();
        w.push_back(0xa9417bfdu);   // ldp x29, x30, [sp, #0x10]
        w.push_back(0x910083ffu);   // add sp, sp, #0x20
        w.push_back(enc_ret());

        // Patch the two forward branches now that we know targets.
        {
            // cbz x8, end
            int64_t off = int64_t(end_idx) * 4 - int64_t(cbz_idx) * 4;
            w[cbz_idx] = enc_cbz(8, off);
            // b.hs end (B.cond cond=2). imm19 = (end - here) >> 2.
            int64_t off2 = int64_t(end_idx) * 4 - int64_t(bhs_idx) * 4;
            uint32_t imm19 = uint32_t(off2 >> 2) & 0x7ffff;
            w[bhs_idx] = 0x54000000u | (imm19 << 5) | 2u;  // b.hs
        }
        return w;
    };
    // Measure the constructor length with the address vars still 0. The
    // instruction count is address-independent (it depends only on N and
    // blob_size_const, both known here), so this matches the STEP 5 pass.
    const size_t ctor_insn_count = gen_ctor().size();
    const uint64_t ctor_bytes = ctor_insn_count * 4;

    // Ordinary trampolines use 4 or 5 instructions; an optional JNI lifecycle
    // trampoline adds four instructions for the specified no-hook default.
    auto tramp_insn_count = [&](size_t i) -> size_t {
        size_t off = i * 8;
        size_t high = off >> 12;
        const bool optional_jni = deferred_jni_onload && exports[i]->name == "JNI_OnLoad";
        return (high > 0 ? 5 : 4) + (optional_jni ? 4 : 0);
    };
    // __text total = constructor + all trampolines.
    uint64_t text_size = ctor_bytes;
    for (size_t i = 0; i < N; ++i) text_size += tramp_insn_count(i) * 4;

    // __elfblob at 16-aligned offset after __text (embedded format only);
    // __cstring right after it (1-aligned). In the no-embed format there
    // is no __elfblob, so __cstring takes its place directly.
    const uint64_t elfblob_off =
        no_embed ? 0 : round_up(text_section_off + text_size, 16);
    const uint64_t cstr_off =
        no_embed ? round_up(text_section_off + text_size, 16)
                 : elfblob_off + elf_size;
    const uint64_t text_used_end = cstr_off + cstr.size();
    const uint64_t text_filesize = round_up(text_used_end, kPageSize);
    const uint64_t text_vmsize = text_filesize;

    // ==================================================================
    // STEP 3. Lay out __DATA at the next page.
    // ==================================================================
    const uint64_t data_vmaddr = text_vmsize;       // == text_filesize
    const uint64_t data_fileoff = text_filesize;

    const uint64_t got_off = data_fileoff;          // file offset of __got
    const uint64_t got_size = 16;                   // 2 pointers
    const uint64_t modinit_off = got_off + got_size;
    const uint64_t modinit_size = 8;                // 1 pointer
    const uint64_t nameptrs_off = modinit_off + modinit_size;
    const uint64_t nameptrs_size = N * 8;
    const uint64_t data_file_used = nameptrs_off + nameptrs_size;
    // MACAPK_FIRSTLAUNCH_COMPACT_DATA (default ON, 2026-09-26). data_file_used
    // is an ABSOLUTE file offset (nameptrs_off starts at data_fileoff), but it
    // was rounded up and used as a SIZE, so every wrapper's __DATA carried
    // data_fileoff -- i.e. the whole __TEXT size -- of extra zero bytes on
    // disk: 16 KB of an 84 KB small wrapper, 2.85 MB of Telegram's 10.3 MB
    // libtmessages wrapper. The file-backed size is now the real content,
    // page-rounded.
    //
    // ONLY THE FILE CHANGES. The VM layout below (data_vmsize, and __bss at
    // bss_off_in_data, which still uses the same absolute value) is left
    // exactly as it was, so every vmaddr of the loaded wrapper holds the same
    // byte as before: the dropped file bytes were zeros, and the kernel
    // zero-fills a segment between filesize and vmsize. __bss stays past the
    // new filesize by construction (round_up(x, P) < P + x <= data_fileoff + x,
    // as __TEXT is at least one page). What moves is __LINKEDIT's FILE offset,
    // and with it the offsets the load commands record (all derived from
    // linkedit_fileoff via le_off). debug/firstlaunch_gate.sh proves it by
    // rebuilding both files' VM images and comparing them, and __LINKEDIT's
    // content, byte for byte. MACAPK_FIRSTLAUNCH_COMPACT_DATA=0 restores the
    // old padding.
    static const bool compact_data = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_COMPACT_DATA");
        return !(e && *e == '0');
    }();
    const uint64_t data_filesize = compact_data
        ? round_up(data_file_used - data_fileoff, kPageSize)
        : round_up(data_file_used, kPageSize);

    // __bss (zerofill) immediately follows the file-backed data in VM.
    const uint64_t bss_off_in_data = data_file_used;  // vm offset within seg
    const uint64_t bss_size = 8 + N * 8;            // handle + targets[N]
    const uint64_t data_vm_used = bss_off_in_data + bss_size;
    const uint64_t data_vmsize = round_up(data_vm_used, kPageSize);

    // ==================================================================
    // STEP 4. Resolve every vmaddr now that the file layout is fixed.
    // ==================================================================
    text_vmaddr = 0;
    ctor_vmaddr = text_vmaddr + text_section_off;     // constructor starts __text
    // Stays 0 in the no-embed format; nothing reads it there (gen_ctor's
    // elfblob branch is not taken and no __elfblob section is emitted).
    elfblob_vmaddr = no_embed ? 0 : text_vmaddr + elfblob_off;
    cstr_vmaddr = text_vmaddr + cstr_off;
    got_vmaddr = data_vmaddr + (got_off - data_fileoff);
    const uint64_t modinit_vmaddr = data_vmaddr + (modinit_off - data_fileoff);
    nameptrs_vmaddr = data_vmaddr + (nameptrs_off - data_fileoff);
    bss_vmaddr = data_vmaddr + bss_off_in_data;

    // Per-trampoline vmaddrs (constructor first, then trampolines).
    std::vector<uint64_t> tramp_vmaddr(N);
    {
        uint64_t cur = ctor_vmaddr + ctor_bytes;
        for (size_t i = 0; i < N; ++i) {
            tramp_vmaddr[i] = cur;
            cur += tramp_insn_count(i) * 4;
        }
    }

    // ==================================================================
    // STEP 5. Generate __text machine code (constructor + trampolines),
    // the __cstring bytes (already built), __nameptrs, __modinit, __got.
    // ==================================================================
    std::vector<uint32_t> ctor_words = gen_ctor();
    if (ctor_words.size() != ctor_insn_count) {
        std::fprintf(stderr,
            "[emit] internal error: ctor length changed between passes "
            "(%zu vs %zu)\n", ctor_words.size(), ctor_insn_count);
        return 1;
    }

    Buf text;
    for (uint32_t w : ctor_words) text.u32(w);
    // Trampolines. Each loads targets[i] and branches to it. targets[]
    // lives in __bss right after the handle slot, so its base is bss+8.
    // The adrp/add pair materialises the array base; the high/low split
    // (matching arm64_pack.cpp) then reaches slot i = base + i*8.
    const uint64_t targets_base = bss_vmaddr + 8;
    for (size_t i = 0; i < N; ++i) {
        const uint64_t tva = tramp_vmaddr[i];
        size_t off = i * 8;
        size_t high = off >> 12;     // 4 KiB page chunks
        size_t low = off & 0xfff;    // within-page residue
        int64_t d = int64_t(targets_base >> 12) - int64_t(tva >> 12);
        text.u32(enc_adrp(16, d));                                   // adrp x16, targets@PAGE
        text.u32(enc_add_imm(16, 16, uint32_t(targets_base & 0xfff), 0));  // add x16, x16, @PAGEOFF
        if (high > 0) {
            text.u32(enc_add_imm(16, 16, uint32_t(high), 1));        // add x16, x16, #high, lsl #12
        }
        text.u32(enc_ldr_imm(16, 16, uint32_t(low)));               // ldr x16, [x16, #low]
        if (deferred_jni_onload && exports[i]->name == "JNI_OnLoad") {
            // A real post-constructor hook receives the original VM/reserved
            // arguments verbatim. With no hook, JNI specifies version 1.1 as
            // the VM's normal default; no guest initializer is skipped.
            text.u32(enc_cbz(16, 16) | 0x01000000u);               // cbnz x16, real
            text.u32(enc_movz(0, 1, 0));                           // x0 = JNI_VERSION_1_1
            text.u32(enc_movk(0, 1, 1));
            text.u32(enc_ret());
        }
        text.u32(enc_br(16));                                       // br x16
    }
    if (text.size() != text_size) {
        std::fprintf(stderr,
            "[emit] internal error: __text size mismatch (%zu vs %llu)\n",
            text.size(), (unsigned long long)text_size);
        return 1;
    }

    // __nameptrs: each = vmaddr of names[i] string in __cstring.
    Buf nameptrs;
    for (size_t i = 0; i < N; ++i) nameptrs.u64(cstr_vmaddr + name_str_rel[i]);

    // __modinit: 1 pointer = constructor vmaddr.
    Buf modinit;
    modinit.u64(ctor_vmaddr);

    // __got: 2 zero pointers (dyld binds them).
    Buf got;
    got.u64(0);
    got.u64(0);

    // ==================================================================
    // STEP 6. Build __LINKEDIT blobs.
    // ==================================================================

    // segment index of __DATA is 1 (__TEXT=0, __DATA=1, __LINKEDIT=2).
    const uint8_t kDataSegIndex = 1;

    // ---- rebase ------------------------------------------------------
    // Rebase the __modinit pointer + the N __nameptrs (REBASE_TYPE_POINTER).
    Buf rebase;
    rebase.u8(REBASE_OPCODE_SET_TYPE_IMM | REBASE_TYPE_POINTER);          // 0x11
    // modinit run (1 pointer):
    rebase.u8(REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB | kDataSegIndex); // 0x21
    uleb(rebase, modinit_off - data_fileoff);
    rebase.u8(REBASE_OPCODE_DO_REBASE_IMM_TIMES | 1);                     // 0x51
    // nameptrs run (N pointers):
    if (N > 0) {
        rebase.u8(REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB | kDataSegIndex);
        uleb(rebase, nameptrs_off - data_fileoff);
        if (N <= 15) {
            rebase.u8(REBASE_OPCODE_DO_REBASE_IMM_TIMES | uint8_t(N));    // 0x5N
        } else {
            rebase.u8(REBASE_OPCODE_DO_REBASE_ULEB_TIMES);               // 0x60
            uleb(rebase, N);
        }
    }
    rebase.u8(REBASE_OPCODE_DONE);                                        // 0x00

    // ---- bind --------------------------------------------------------
    // Bind the 2 __got slots to the 2 shim funcs (dylib ordinal 1).
    // __got[0] is the format's load entry point: the no-embed format
    // takes (path, soname), the embedded format (blob, size, soname).
    // Binding the wrong one would be a silent ABI mismatch, so the name
    // is derived from the same `no_embed` flag the constructor codegen
    // used -- there is no way for the two to disagree.
    Buf bind;
    const char* bind_names[2] = {
        no_embed ? "___macapk_arm64_native_load_from_path"
                 : "___macapk_arm64_native_load",
        "___macapk_arm64_native_dlsym",
    };
    for (int i = 0; i < 2; ++i) {
        bind.u8(BIND_OPCODE_SET_DYLIB_ORDINAL_IMM | 1);                  // 0x11
        bind.u8(BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM | 0);         // 0x40
        bind.cstr(bind_names[i]);
        bind.u8(BIND_OPCODE_SET_TYPE_IMM | BIND_TYPE_POINTER);          // 0x51
        bind.u8(BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB | kDataSegIndex);// 0x71
        uleb(bind, (got_off - data_fileoff) + i * 8);
        bind.u8(BIND_OPCODE_DO_BIND);                                    // 0x90
    }
    bind.u8(BIND_OPCODE_DONE);                                           // 0x00

    // ---- export trie -------------------------------------------------
    // Image-relative addresses. Export every trampoline as _<name> plus
    // the elfblob start/end markers are NOT required, so we only export
    // the trampolines (the JVM/dlsym only needs those).
    std::vector<TrieEntry> trie_entries;
    trie_entries.reserve(N);
    for (size_t i = 0; i < N; ++i) {
        trie_entries.push_back(TrieEntry{
            "_" + exports[i]->name,           // single Mach-O underscore
            tramp_vmaddr[i],                  // image-relative (text_vmaddr==0)
        });
    }
    std::vector<uint8_t> export_blob = ExportTrie(trie_entries).serialize();

    // ---- symtab + strtab --------------------------------------------
    // Symbol order: locals (none), ext-defined (N trampolines), undef (2).
    // String table: byte0 = \0, then NUL-terminated names.
    Buf strtab;
    strtab.u8(0);  // index 0 == empty string
    std::vector<struct nlist_64> nlist;
    nlist.reserve(N + 2);
    // ext-defined: the N trampolines, n_sect = 1 (__text is section 1).
    for (size_t i = 0; i < N; ++i) {
        struct nlist_64 nl{};
        std::string sym = "_" + exports[i]->name;
        nl.n_un.n_strx = uint32_t(strtab.size());
        strtab.cstr(sym);
        nl.n_type = N_SECT | N_EXT;        // 0x0f
        nl.n_sect = 1;                     // 1-based: __text
        nl.n_desc = 0;
        nl.n_value = tramp_vmaddr[i];
        nlist.push_back(nl);
    }
    // undef: the 2 imports, ordinal 1 in n_desc high byte.
    for (int i = 0; i < 2; ++i) {
        struct nlist_64 nl{};
        nl.n_un.n_strx = uint32_t(strtab.size());
        strtab.cstr(bind_names[i]);
        nl.n_type = N_UNDF | N_EXT;        // 0x01
        nl.n_sect = 0;
        nl.n_desc = uint16_t(1 << 8);      // SET_LIBRARY_ORDINAL(1)
        nl.n_value = 0;
        nlist.push_back(nl);
    }

    // ---- assemble __LINKEDIT, recording sub-blob offsets -------------
    const uint64_t linkedit_fileoff = data_fileoff + data_filesize;
    const uint64_t linkedit_vmaddr = data_vmaddr + data_vmsize;

    Buf linkedit;
    auto le_off = [&](size_t local) -> uint32_t {
        return uint32_t(linkedit_fileoff + local);
    };

    // rebase
    const uint32_t rebase_off = le_off(linkedit.size());
    linkedit.bytes(rebase.b.data(), rebase.b.size());
    const uint32_t rebase_sz = uint32_t(rebase.size());
    linkedit.align(8);

    // bind
    const uint32_t bind_off = le_off(linkedit.size());
    linkedit.bytes(bind.b.data(), bind.b.size());
    const uint32_t bind_sz = uint32_t(bind.size());
    linkedit.align(8);

    // export trie
    const uint32_t export_off = le_off(linkedit.size());
    linkedit.bytes(export_blob.data(), export_blob.size());
    const uint32_t export_sz = uint32_t(export_blob.size());
    linkedit.align(8);

    // symtab (nlist array)
    const uint32_t symoff = le_off(linkedit.size());
    for (const auto& nl : nlist) {
        linkedit.u32(nl.n_un.n_strx);
        linkedit.u8(nl.n_type);
        linkedit.u8(nl.n_sect);
        linkedit.u16(nl.n_desc);
        linkedit.u64(nl.n_value);
    }
    const uint32_t nsyms = uint32_t(nlist.size());
    linkedit.align(8);

    // string table
    const uint32_t stroff = le_off(linkedit.size());
    linkedit.bytes(strtab.b.data(), strtab.b.size());
    const uint32_t strsize = uint32_t(strtab.size());
    // codesign requires the file (and thus __LINKEDIT) to extend to where
    // it appends the signature; it pads as needed. We just align to 8.
    linkedit.align(8);

    const uint64_t linkedit_used = linkedit.size();
    const uint64_t linkedit_filesize = round_up(linkedit_used, kPageSize);
    const uint64_t linkedit_vmsize = linkedit_filesize;

    // ==================================================================
    // STEP 7. Emit the mach header + load commands.
    // ==================================================================
    Buf hdr;
    // mach_header_64
    hdr.u32(MH_MAGIC_64);                 // 0xfeedfacf
    hdr.u32(uint32_t(CPU_TYPE_ARM64));    // 0x0100000c
    hdr.u32(0);                           // cpusubtype = 0
    hdr.u32(MH_DYLIB);                    // filetype = 6
    hdr.u32(ncmds);
    hdr.u32(sizeofcmds);
    hdr.u32(MH_DYLDLINK | MH_TWOLEVEL | MH_NO_REEXPORTED_DYLIBS);  // 0x00100084
    hdr.u32(0);                           // reserved

    // ---- LC_SEGMENT_64 __TEXT ----------------------------------------
    {
        hdr.u32(LC_SEGMENT_64);
        hdr.u32(text_seg_cmdsize);
        char nm[16] = {0}; std::strncpy(nm, "__TEXT", 16); hdr.bytes(nm, 16);
        hdr.u64(text_vmaddr);
        hdr.u64(text_vmsize);
        hdr.u64(0);                       // fileoff
        hdr.u64(text_filesize);
        hdr.u32(VM_PROT_READ | VM_PROT_EXECUTE);   // maxprot r-x = 5
        hdr.u32(VM_PROT_READ | VM_PROT_EXECUTE);   // initprot r-x = 5
        hdr.u32(text_nsects);             // nsects (3 embed / 2 no-embed)
        hdr.u32(0);                       // flags
        // section __text
        auto emit_sect = [&](const char* sn, const char* sg, uint64_t addr,
                             uint64_t size, uint32_t off, uint32_t align,
                             uint32_t flags, uint32_t r1, uint32_t r2) {
            char s[16] = {0}; std::strncpy(s, sn, 16); hdr.bytes(s, 16);
            char g[16] = {0}; std::strncpy(g, sg, 16); hdr.bytes(g, 16);
            hdr.u64(addr);
            hdr.u64(size);
            hdr.u32(off);
            hdr.u32(align);
            hdr.u32(0);                   // reloff
            hdr.u32(0);                   // nreloc
            hdr.u32(flags);
            hdr.u32(r1);                  // reserved1
            hdr.u32(r2);                  // reserved2
            hdr.u32(0);                   // reserved3 (section_64 is 80 B)
        };
        emit_sect("__text", "__TEXT", ctor_vmaddr, text_size,
                  uint32_t(text_section_off), 2,
                  S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS, 0, 0);
        if (!no_embed) {
            emit_sect("__elfblob", "__TEXT", elfblob_vmaddr, elf_size,
                      uint32_t(elfblob_off), 4, S_REGULAR, 0, 0);
        }
        emit_sect("__cstring", "__TEXT", cstr_vmaddr, cstr.size(),
                  uint32_t(cstr_off), 0, S_CSTRING_LITERALS, 0, 0);
    }

    // ---- LC_SEGMENT_64 __DATA ----------------------------------------
    {
        hdr.u32(LC_SEGMENT_64);
        hdr.u32(data_seg_cmdsize);
        char nm[16] = {0}; std::strncpy(nm, "__DATA", 16); hdr.bytes(nm, 16);
        hdr.u64(data_vmaddr);
        hdr.u64(data_vmsize);
        hdr.u64(data_fileoff);
        hdr.u64(data_filesize);
        hdr.u32(VM_PROT_READ | VM_PROT_WRITE);     // maxprot rw- = 3
        hdr.u32(VM_PROT_READ | VM_PROT_WRITE);     // initprot rw- = 3
        hdr.u32(4);                       // nsects
        hdr.u32(0);                       // flags
        auto emit_sect = [&](const char* sn, uint64_t addr, uint64_t size,
                             uint32_t off, uint32_t align, uint32_t flags) {
            char s[16] = {0}; std::strncpy(s, sn, 16); hdr.bytes(s, 16);
            char g[16] = {0}; std::strncpy(g, "__DATA", 16); hdr.bytes(g, 16);
            hdr.u64(addr);
            hdr.u64(size);
            hdr.u32(off);
            hdr.u32(align);
            hdr.u32(0);                   // reloff
            hdr.u32(0);                   // nreloc
            hdr.u32(flags);
            hdr.u32(0);                   // reserved1
            hdr.u32(0);                   // reserved2
            hdr.u32(0);                   // reserved3 (section_64 is 80 B)
        };
        emit_sect("__got", got_vmaddr, got_size,
                  uint32_t(got_off), 3, S_NON_LAZY_SYMBOL_POINTERS);
        emit_sect("__modinit", modinit_vmaddr, modinit_size,
                  uint32_t(modinit_off), 3, S_MOD_INIT_FUNC_POINTERS);
        emit_sect("__nameptrs", nameptrs_vmaddr, nameptrs_size,
                  uint32_t(nameptrs_off), 3, S_REGULAR);
        // __bss: zerofill, file offset 0, must be LAST in __DATA.
        emit_sect("__bss", bss_vmaddr, bss_size, 0, 3, S_ZEROFILL);
    }

    // ---- LC_SEGMENT_64 __LINKEDIT ------------------------------------
    {
        hdr.u32(LC_SEGMENT_64);
        hdr.u32(link_seg_cmdsize);
        char nm[16] = {0}; std::strncpy(nm, "__LINKEDIT", 16); hdr.bytes(nm, 16);
        hdr.u64(linkedit_vmaddr);
        hdr.u64(linkedit_vmsize);
        hdr.u64(linkedit_fileoff);
        hdr.u64(linkedit_filesize);
        hdr.u32(VM_PROT_READ);            // maxprot r-- = 1
        hdr.u32(VM_PROT_READ);            // initprot r-- = 1
        hdr.u32(0);                       // nsects
        hdr.u32(0);                       // flags
    }

    // ---- LC_DYLD_INFO_ONLY ------------------------------------------
    {
        hdr.u32(LC_DYLD_INFO_ONLY);       // 0x80000022
        hdr.u32(dyldinfo_cmdsize);
        hdr.u32(rebase_off);
        hdr.u32(rebase_sz);
        hdr.u32(bind_off);
        hdr.u32(bind_sz);
        hdr.u32(0); hdr.u32(0);           // weak_bind off/size
        hdr.u32(0); hdr.u32(0);           // lazy_bind off/size
        hdr.u32(export_off);
        hdr.u32(export_sz);
    }

    // ---- LC_SYMTAB ---------------------------------------------------
    {
        hdr.u32(LC_SYMTAB);
        hdr.u32(symtab_cmdsize);
        hdr.u32(symoff);
        hdr.u32(nsyms);
        hdr.u32(stroff);
        hdr.u32(strsize);
    }

    // ---- LC_DYSYMTAB -------------------------------------------------
    {
        hdr.u32(LC_DYSYMTAB);
        hdr.u32(dysymtab_cmdsize);
        hdr.u32(0);                       // ilocalsym
        hdr.u32(0);                       // nlocalsym
        hdr.u32(0);                       // iextdefsym
        hdr.u32(uint32_t(N));             // nextdefsym
        hdr.u32(uint32_t(N));             // iundefsym
        hdr.u32(2);                       // nundefsym
        hdr.u32(0); hdr.u32(0);           // tocoff / ntoc
        hdr.u32(0); hdr.u32(0);           // modtaboff / nmodtab
        hdr.u32(0); hdr.u32(0);           // extrefsymoff / nextrefsyms
        hdr.u32(0); hdr.u32(0);           // indirectsymoff / nindirectsyms
        hdr.u32(0); hdr.u32(0);           // extreloff / nextrel
        hdr.u32(0); hdr.u32(0);           // locreloff / nlocrel
    }

    // ---- LC_ID_DYLIB -------------------------------------------------
    auto emit_dylib_lc = [&](uint32_t cmd, const std::string& path,
                             uint32_t cmdsize) {
        hdr.u32(cmd);
        hdr.u32(cmdsize);
        hdr.u32(24);                      // name.offset (string starts at +24)
        hdr.u32(1);                       // timestamp
        hdr.u32(0);                       // current_version
        hdr.u32(0);                       // compatibility_version
        size_t start = hdr.size();
        hdr.cstr(path);
        hdr.pad_to(start + (cmdsize - 24));  // pad path field to cmdsize
    };
    emit_dylib_lc(LC_ID_DYLIB, id_name, iddylib_cmdsize);
    emit_dylib_lc(LC_LOAD_DYLIB, shim_install_name, loadshim_cmdsize);
    emit_dylib_lc(LC_LOAD_DYLIB, libsystem_name, loadsys_cmdsize);

    // ---- LC_RPATH x2 -------------------------------------------------
    auto emit_rpath_lc = [&](const std::string& path, uint32_t cmdsize) {
        hdr.u32(LC_RPATH);                // 0x8000001c
        hdr.u32(cmdsize);
        hdr.u32(12);                      // path.offset (string starts at +12)
        size_t start = hdr.size();
        hdr.cstr(path);
        hdr.pad_to(start + (cmdsize - 12));
    };
    emit_rpath_lc(rpath_loader, rpath1_cmdsize);
    emit_rpath_lc(shim_dir, rpath2_cmdsize);

    // ---- LC_BUILD_VERSION -------------------------------------------
    {
        hdr.u32(LC_BUILD_VERSION);        // 0x32
        hdr.u32(buildver_cmdsize);
        hdr.u32(PLATFORM_MACOS);          // 1
        hdr.u32(0x000c0000);              // minos 12.0  (X<<16)|(Y<<8)|Z
        hdr.u32(0x000c0000);              // sdk 12.0
        hdr.u32(0);                       // ntools
    }

    // ---- LC_UUID -----------------------------------------------------
    // Deterministic UUID derived from the embedded ELF + export count, so
    // re-running the emitter on the same input yields a stable build id.
    // (The exact value is irrelevant to loading; dyld only requires the
    // command to be present and well-formed.)
    //
    // MACAPK_FIRSTLAUNCH_FASTUUID (default ON, 2026-09-26). The byte-serial
    // FNV-1a above is one dependent 64-bit multiply per input byte, so it is
    // latency-bound: 117 ms for Crossy Road's 87.6 MB libil2cpp.so, which after
    // the read fix (elf64_reader.cpp, MACAPK_FIRSTLAUNCH_FASTREAD) was most of
    // the whole Stage-6 pack. The fast digest folds the SAME bytes as 64-bit
    // little-endian words through four independent FNV-1a lanes (so the four
    // multiplies overlap), then the byte tail, then the lanes into one value:
    // still a deterministic function of every input byte, ~20x cheaper.
    //
    // NOT byte-identical, by design, and only here: it changes the 16 LC_UUID
    // bytes and therefore the ad-hoc signature's page hashes, nothing else.
    // Nothing reads a wrapper's UUID (the loader keys its caches on the guest
    // ELF's own content and on the SHIM's LC_UUID, never this one; see
    // macapk_shim_uuid in arm64_native.cpp), and dyld only needs the command to
    // be present and well-formed. debug/firstlaunch_gate.sh proves the
    // "nothing else" claim by masking the UUID, stripping the signature, and
    // comparing both outputs byte for byte.
    //
    // MACAPK_FIRSTLAUNCH_FASTUUID=0 restores the byte-serial digest, and with
    // it the exact pre-change output.
    static const bool fast_uuid = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_FASTUUID");
        return !(e && *e == '0');
    }();
    {
        uint8_t uuid[16] = {0};
        uint64_t h1 = 1469598103934665603ull;   // FNV-1a offset basis
        if (!fast_uuid) {
            for (size_t i = 0; i < elf_size; ++i) {
                h1 ^= elf_blob[i];
                h1 *= 1099511628211ull;
            }
        } else {
            uint64_t lane[4] = {h1, h1 ^ 1u, h1 ^ 2u, h1 ^ 3u};
            size_t i = 0;
            for (; i + 32 <= elf_size; i += 32) {
                for (int k = 0; k < 4; ++k) {
                    uint64_t w;
                    std::memcpy(&w, elf_blob + i + 8 * k, 8);   // host is LE
                    lane[k] ^= w;
                    lane[k] *= 1099511628211ull;
                }
            }
            for (; i < elf_size; ++i) {
                lane[i & 3] ^= elf_blob[i];
                lane[i & 3] *= 1099511628211ull;
            }
            for (int k = 0; k < 4; ++k) {
                h1 ^= lane[k];
                h1 *= 1099511628211ull;
            }
            h1 ^= uint64_t(elf_size);
            h1 *= 1099511628211ull;
        }
        uint64_t h2 = h1 ^ (uint64_t(N) * 1099511628211ull);
        for (int i = 0; i < 8; ++i) uuid[i]     = uint8_t(h1 >> (8 * i));
        for (int i = 0; i < 8; ++i) uuid[8 + i] = uint8_t(h2 >> (8 * i));
        // Set RFC-4122 version (4) + variant bits so it is a valid UUID.
        uuid[6] = uint8_t((uuid[6] & 0x0f) | 0x40);
        uuid[8] = uint8_t((uuid[8] & 0x3f) | 0x80);
        hdr.u32(LC_UUID);                 // 0x1b
        hdr.u32(uuid_cmdsize);
        hdr.bytes(uuid, 16);
    }

    if (hdr.size() != lc_end) {
        // Self-check: the emitted load-command bytes must exactly match
        // the size we reserved (ncmds/sizeofcmds + the segment vmaddr/
        // fileoff layout all depend on it). A mismatch is an internal
        // bug, not a bad input.
        std::fprintf(stderr,
            "[emit] internal error: LC region size mismatch "
            "(emitted %zu vs reserved %llu)\n",
            hdr.size(), (unsigned long long)lc_end);
        return 1;
    }

    // ==================================================================
    // STEP 8. Concatenate the file image, padding each segment to its
    // file offset, and write to disk.
    // ==================================================================
    std::vector<uint8_t> file;
    file.reserve(linkedit_fileoff + linkedit_filesize);

    // __TEXT: header+LCs then __text/__elfblob/__cstring at their offsets.
    file.insert(file.end(), hdr.b.begin(), hdr.b.end());        // [0, lc_end)
    file.resize(text_section_off, 0);
    file.insert(file.end(), text.b.begin(), text.b.end());      // __text
    if (!no_embed) {
        file.resize(elfblob_off, 0);
        file.insert(file.end(), elf_blob, elf_blob + elf_size); // __elfblob
    }
    file.resize(cstr_off, 0);
    file.insert(file.end(), cstr.b.begin(), cstr.b.end());      // __cstring
    file.resize(text_filesize, 0);                              // pad __TEXT

    // __DATA: __got, __modinit, __nameptrs (file-backed); __bss is zerofill.
    file.resize(got_off, 0);
    file.insert(file.end(), got.b.begin(), got.b.end());
    file.resize(modinit_off, 0);
    file.insert(file.end(), modinit.b.begin(), modinit.b.end());
    file.resize(nameptrs_off, 0);
    file.insert(file.end(), nameptrs.b.begin(), nameptrs.b.end());
    file.resize(data_fileoff + data_filesize, 0);               // pad __DATA

    // __LINKEDIT.
    file.resize(linkedit_fileoff, 0);
    file.insert(file.end(), linkedit.b.begin(), linkedit.b.end());
    file.resize(linkedit_fileoff + linkedit_filesize, 0);       // pad

    {
        std::ofstream f(out_path, std::ios::binary | std::ios::trunc);
        if (!f) {
            std::fprintf(stderr, "[emit] cannot open %s for write\n",
                         out_path.c_str());
            return 1;
        }
        f.write(reinterpret_cast<const char*>(file.data()),
                std::streamsize(file.size()));
        if (!f.good()) {
            std::fprintf(stderr, "[emit] write failed for %s\n",
                         out_path.c_str());
            return 1;
        }
    }

    if (verbose) {
        std::fprintf(stderr,
            "[emit] wrote %s (%zu bytes, %zu exports, %s) -- ad-hoc signing\n",
            out_path.c_str(), file.size(), N,
            no_embed ? "no embedded blob, no double residency"
                     : "embedded blob");
    }

    // ==================================================================
    // STEP 9. Ad-hoc sign via codesign (base macOS; no Xcode needed).
    // ==================================================================
    {
        // Quote the path for shell safety.
        std::string q = "'";
        for (char c : out_path) { if (c == '\'') q += "'\\''"; else q += c; }
        q += "'";
        std::string cmd = "codesign -f -s - " + q + " 2>&1";
        int rc = std::system(cmd.c_str());
        if (rc != 0) {
            std::fprintf(stderr,
                "[emit] codesign failed (rc=%d) for %s\n", rc, out_path.c_str());
            return 1;
        }
    }

    std::fprintf(stderr,
        "[emit] arm64 native pack (in-process emitter): %zu exports -> %s "
        "(%s)\n",
        N, out_path.c_str(),
        no_embed ? "from-path, no embedded blob" : "embedded blob");
    return 0;
}

}  // namespace macapk::translator
