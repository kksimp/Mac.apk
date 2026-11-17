// elf_loader.cpp -- Android arm64 ELF loader for Windows. See
// elf_loader.h.

#include "winapk/elf_loader.h"

#include "winapk/arch.h"
#include "winapk/arm64_x18.h"
#include "winapk/arm64_guest.h"
#include "winapk/elf64_defs.h"
#include "winapk/elf_tls.h"
#include "winapk/guest_abi.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>

#include <windows.h>

namespace winapk {

namespace {

using namespace winapk::elf;

// Windows pages are 4 KiB. ELF segments built for Android are aligned
// to 4 KiB or 16 KiB, both multiples, so page math in ELF vaddrs lines up.
constexpr uint64_t kPage = 0x1000;

uint64_t page_down(uint64_t v) { return v & ~(kPage - 1); }
uint64_t page_up(uint64_t v) { return (v + kPage - 1) & ~(kPage - 1); }

// [off, off + len) lies within [0, size), written so that no sum can wrap: a
// corrupt header's huge values must fail the check, not pass it.
bool fits(uint64_t off, uint64_t len, uint64_t size) { return off <= size && len <= size - off; }

// Tags lld emits for RELR when targeting Android API < 30 (--use-android-relr-tags).
constexpr Sxword kDtAndroidRelr = 0x6fffe000;
constexpr Sxword kDtAndroidRelrSz = 0x6fffe001;

std::string hex(uint64_t v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "0x%llx", static_cast<unsigned long long>(v));
    return buf;
}

bool read_file(const std::filesystem::path& path, std::vector<uint8_t>* out, std::string* error) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) {
        *error = "cannot open " + path.string();
        return false;
    }
    std::streamsize n = f.tellg();
    if (n <= 0) {
        *error = "empty file " + path.string();
        return false;
    }
    out->resize(static_cast<size_t>(n));
    f.seekg(0);
    if (!f.read(reinterpret_cast<char*>(out->data()), n)) {
        *error = "short read on " + path.string();
        return false;
    }
    return true;
}

DWORD page_protection(uint32_t pf) {
    const bool r = pf & kPfR, w = pf & kPfW, x = pf & kPfX;
    if (x && w) return PAGE_EXECUTE_READWRITE;
    if (x) return PAGE_EXECUTE_READ;
    if (w) return PAGE_READWRITE;
    if (r) return PAGE_READONLY;
    return PAGE_NOACCESS;  // gap between segments
}

// SLEB128 stream used by Android's packed relocations (APS2).
class Sleb128Reader {
public:
    Sleb128Reader(const uint8_t* p, const uint8_t* end) : p_(p), end_(end) {}
    bool ok() const { return ok_; }
    int64_t next() {
        int64_t value = 0;
        unsigned shift = 0;
        uint8_t byte;
        do {
            if (p_ >= end_) {
                ok_ = false;
                return 0;
            }
            byte = *p_++;
            value |= static_cast<int64_t>(byte & 0x7f) << shift;
            shift += 7;
        } while ((byte & 0x80) && shift < 64);
        if (shift < 64 && (byte & 0x40)) value |= -(static_cast<int64_t>(1) << shift);
        return value;
    }

private:
    const uint8_t* p_;
    const uint8_t* end_;
    bool ok_ = true;
};

}  // namespace

std::unique_ptr<ElfImage> ElfImage::load(const std::filesystem::path& path,
                                         const SymbolResolver& resolve,
                                         std::string* error,
                                         const LoadOptions& options) {
    std::unique_ptr<ElfImage> img = map(path, error, options);
    if (!img || !img->link(resolve, error)) return nullptr;
    img->initialize();
    return img;
}

std::unique_ptr<ElfImage> ElfImage::map(const std::filesystem::path& path, std::string* error,
                                        const LoadOptions& options) {
    std::vector<uint8_t> file;
    if (!read_file(path, &file, error)) return nullptr;

    if (file.size() < sizeof(Ehdr)) {
        *error = "not an ELF file (too small)";
        return nullptr;
    }
    Ehdr eh;
    std::memcpy(&eh, file.data(), sizeof(eh));
    if (eh.e_ident[0] != kMag0 || eh.e_ident[1] != kMag1 || eh.e_ident[2] != kMag2 ||
        eh.e_ident[3] != kMag3) {
        *error = "not an ELF file (bad magic)";
        return nullptr;
    }
    if (eh.e_ident[kEiClass] != kClass64 || eh.e_ident[kEiData] != kData2Lsb) {
        *error = "not a little-endian ELF64 file";
        return nullptr;
    }
    if (eh.e_machine != kEmAarch64) {
        *error = eh.e_machine == kEmX86_64 ? "this is an x86_64 library, and this PC runs arm64 code only (an arm64-v8a build of the app is needed)"
                 : eh.e_machine == 40     ? "this is a 32-bit ARM library (armeabi-v7a); Win.apk runs arm64-v8a libraries"
                                          : "unsupported e_machine " + std::to_string(eh.e_machine);
        return nullptr;
    }
    if (eh.e_type != kEtDyn) {
        *error = "not a shared object (e_type " + std::to_string(eh.e_type) + ")";
        return nullptr;
    }
    if (eh.e_phentsize != sizeof(Phdr) || !fits(eh.e_phoff, uint64_t{eh.e_phnum} * sizeof(Phdr), file.size())) {
        *error = "program header table out of bounds";
        return nullptr;
    }

    std::unique_ptr<ElfImage> img(new ElfImage());
    img->path_ = path;
    img->options_ = options;
    img->machine_ = eh.e_machine;
    for (int i = 0; i < eh.e_phnum; ++i) {
        Phdr ph;
        std::memcpy(&ph, file.data() + eh.e_phoff + i * sizeof(Phdr), sizeof(ph));
        img->phdrs_.push_back(ph);
        if (ph.p_type == kPtLoad) {
            // The segment's end is rounded up to a page (map_segments), so that too must not wrap.
            if (ph.p_filesz > ph.p_memsz || !fits(ph.p_offset, ph.p_filesz, file.size()) ||
                !fits(ph.p_vaddr, ph.p_memsz, UINT64_MAX - (kPage - 1))) {
                *error = "PT_LOAD " + std::to_string(i) + " out of bounds";
                return nullptr;
            }
            img->loads_.push_back({ph.p_vaddr, ph.p_memsz, ph.p_filesz, ph.p_offset, ph.p_flags});
        } else if (ph.p_type == kPtDynamic) {
            img->dynamic_vaddr_ = ph.p_vaddr;
        } else if (ph.p_type == kPtGnuRelro) {
            img->has_relro_ = true;
            img->relro_vaddr_ = ph.p_vaddr;
            img->relro_memsz_ = ph.p_memsz;
        } else if (ph.p_type == kPtTls) {
            if (ph.p_filesz > ph.p_memsz) {
                *error = "PT_TLS " + std::to_string(i) + " is malformed";
                return nullptr;
            }
            img->has_tls_ = true;
            img->tls_vaddr_ = ph.p_vaddr;
            img->tls_filesz_ = ph.p_filesz;
            img->tls_memsz_ = ph.p_memsz;
            img->tls_align_ = ph.p_align;
        }
    }
    if (img->loads_.empty()) {
        *error = "no PT_LOAD segments";
        return nullptr;
    }
    if (img->dynamic_vaddr_ == 0) {
        *error = "no PT_DYNAMIC segment";
        return nullptr;
    }

    if (!img->map_segments(file, error)) return nullptr;
    if (!img->check_x18(file, error)) return nullptr;
    {
        // Raw Linux system calls become traps the runtime answers (arm64_guest.h, patch_svc).
        const arm64::ElfCode code = arm64::elf_code(file.data(), file.size());
        size_t unsupported = 0;
        for (const arm64::ExecRange& r : code.ranges) {
            if (img->in_image(r.vaddr, r.size)) arm64::patch_svc(img->at<uint8_t>(r.vaddr), r.size, &unsupported);
        }
        if (unsupported) {
            *error = "has " + std::to_string(unsupported) + " `svc` instruction(s) with a non-zero immediate: not a Linux system call";
            return nullptr;
        }
    }
    if (!img->read_dynamic(error)) return nullptr;
    if (img->has_tls_) {
        if (img->tls_filesz_ && !img->in_image(img->tls_vaddr_, img->tls_filesz_)) {
            *error = "PT_TLS initialization image outside the image";
            return nullptr;
        }
        // The template is read on each thread's first access, after link()
        // has relocated it in place.
        img->tls_module_ = tls_register_module(img->at<const void>(img->tls_vaddr_), img->tls_filesz_,
                                               img->tls_memsz_, img->tls_align_);
        if (!img->tls_module_) {
            *error = "too many libraries with TLS are loaded at once";
            return nullptr;
        }
    }

    img->register_arm64_code();
    return img;
}

bool ElfImage::link(const SymbolResolver& resolve, std::string* error,
                    const TlsSymbolResolver& resolve_tls) {
    if (linked_) return true;
    if (!apply_relocations(resolve, resolve_tls, error)) return false;
    if (!protect_segments(error)) return false;
    if (!apply_irelative(error)) return false;
    seal_relro();
    FlushInstructionCache(GetCurrentProcess(), mem_, size_);
    linked_ = true;
    return true;
}

void ElfImage::initialize() {
    if (linked_ && !initialized_) run_initializers();
}
void ElfImage::finalize() {
    if (initialized_) run_finalizers();
}

ElfImage::~ElfImage() {
    if (initialized_) run_finalizers();
    for (const void* start : arm64_code_) arm64::unregister_code(start);
    if (tls_module_) tls_unregister_module(tls_module_);
    if (mem_ && owns_mem_) VirtualFree(mem_, 0, MEM_RELEASE);
}

bool ElfImage::in_image(uint64_t vaddr, size_t len) const {
    return vaddr >= lo_vaddr_ && vaddr <= hi_vaddr_ && len <= hi_vaddr_ - vaddr;
}

bool ElfImage::map_segments(const std::vector<uint8_t>& file, std::string* error) {
    lo_vaddr_ = UINT64_MAX;
    hi_vaddr_ = 0;
    for (const Segment& s : loads_) {
        lo_vaddr_ = std::min(lo_vaddr_, page_down(s.vaddr));
        hi_vaddr_ = std::max(hi_vaddr_, page_up(s.vaddr + s.memsz));
    }
    size_ = static_cast<size_t>(hi_vaddr_ - lo_vaddr_);

    // Reserve and commit the whole span read-write; protect_segments() narrows
    // it per page once relocation is done. Fresh pages are zero, which covers
    // every segment's .bss tail.
    if (options_.reserved_address) {
        if (size_ > options_.reserved_size) {
            *error = "needs " + std::to_string(size_) + " bytes, the reserved range has " +
                     std::to_string(options_.reserved_size);
            return false;
        }
        // Committing pages of an existing reservation is allowed whether or not
        // they are committed already; the caller's old contents are cleared.
        mem_ = static_cast<uint8_t*>(VirtualAlloc(options_.reserved_address, size_, MEM_COMMIT, PAGE_READWRITE));
        if (mem_) {
            DWORD old;
            VirtualProtect(mem_, size_, PAGE_READWRITE, &old);
            std::memset(mem_, 0, size_);
            owns_mem_ = false;
        }
    } else {
        mem_ = static_cast<uint8_t*>(VirtualAlloc(nullptr, size_, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
    }
    if (!mem_) {
        *error = "VirtualAlloc(" + std::to_string(size_) + ") failed: " + std::to_string(GetLastError());
        return false;
    }
    bias_ = reinterpret_cast<uintptr_t>(mem_) - lo_vaddr_;

    for (const Segment& s : loads_) {
        std::memcpy(at<uint8_t>(s.vaddr), file.data() + s.offset, s.filesz);
    }
    return true;
}

bool ElfImage::read_dynamic(std::string* error) {
    std::vector<uint64_t> needed_offsets;
    uint64_t soname_offset = UINT64_MAX;
    for (const Dyn* d = at<Dyn>(dynamic_vaddr_);; ++d) {
        if (!in_image(reinterpret_cast<uintptr_t>(d) - bias_, sizeof(Dyn))) {
            *error = "PT_DYNAMIC runs past the image";
            return false;
        }
        if (d->d_tag == kDtNull) break;
        const uint64_t v = d->d_un.d_val;
        switch (d->d_tag) {
            case kDtNeeded: needed_offsets.push_back(v); break;
            case kDtSoname: soname_offset = v; break;
            case kDtStrTab: strtab_ = v; break;
            case kDtStrSz: strsz_ = v; break;
            case kDtSymTab: symtab_ = v; break;
            case kDtHash: hash_ = v; break;
            case kDtGnuHash: gnu_hash_ = v; break;
            case kDtRela: rela_ = v; break;
            case kDtRelaSz: relasz_ = v; break;
            case kDtRelaEnt:
                if (v != sizeof(Rela)) {
                    *error = "unexpected DT_RELAENT " + std::to_string(v);
                    return false;
                }
                break;
            case kDtJmpRel: jmprel_ = v; break;
            case kDtPltRelSz: pltrelsz_ = v; break;
            case kDtRelr: case kDtAndroidRelr: relr_ = v; break;
            case kDtRelrSz: case kDtAndroidRelrSz: relrsz_ = v; break;
            case kDtAndroidRela: android_rela_ = v; break;
            case kDtAndroidRelaSz: android_relasz_ = v; break;
            case kDtInit: init_ = v; break;
            case kDtFini: fini_ = v; break;
            case kDtInitArray: init_array_ = v; break;
            case kDtInitArraySz: init_arraysz_ = v; break;
            case kDtFiniArray: fini_array_ = v; break;
            case kDtFiniArraySz: fini_arraysz_ = v; break;
            default: break;
        }
    }
    if (!strtab_ || !symtab_ || !strsz_ || !in_image(strtab_, strsz_) || !in_image(symtab_, sizeof(Sym))) {
        *error = "missing or out-of-range DT_STRTAB/DT_SYMTAB";
        return false;
    }
    const char* strtab = at<const char>(strtab_);
    // With a terminated table, any st_name below strsz_ is a C string inside it.
    if (strtab[strsz_ - 1] != '\0') {
        *error = "DT_STRTAB does not end in a NUL";
        return false;
    }
    auto str = [&](uint64_t off) -> std::string {
        return off < strsz_ ? std::string(strtab + off, strnlen(strtab + off, strsz_ - off)) : std::string();
    };
    for (uint64_t off : needed_offsets) needed_.push_back(str(off));
    if (soname_offset != UINT64_MAX) soname_ = str(soname_offset);

    // Symbol count: DT_HASH stores it (nchain); DT_GNU_HASH has to be walked
    // to the end of its last non-empty chain. Every table is bounded here, once,
    // so relocation and symbol lookup can index them without checks of their own.
    uint64_t nsyms = 0;
    if (hash_) {
        if (!in_image(hash_, 2 * sizeof(uint32_t))) {
            *error = "DT_HASH outside the image";
            return false;
        }
        nsyms = at<const uint32_t>(hash_)[1];
    } else if (gnu_hash_) {
        if (!in_image(gnu_hash_, 4 * sizeof(uint32_t))) {
            *error = "DT_GNU_HASH outside the image";
            return false;
        }
        const uint32_t* gh = at<const uint32_t>(gnu_hash_);
        const uint32_t nbuckets = gh[0], symoffset = gh[1], bloom_words = gh[2];
        // At most 16 + 2^35 + 2^34 bytes: the sum cannot wrap.
        const uint64_t buckets_vaddr = gnu_hash_ + 4 * sizeof(uint32_t) + uint64_t{bloom_words} * sizeof(uint64_t);
        const uint64_t chain_vaddr = buckets_vaddr + uint64_t{nbuckets} * sizeof(uint32_t);
        if (!in_image(gnu_hash_, chain_vaddr - gnu_hash_)) {
            *error = "DT_GNU_HASH buckets outside the image";
            return false;
        }
        const uint32_t* buckets = at<const uint32_t>(buckets_vaddr);
        uint64_t last = 0;
        for (uint32_t b = 0; b < nbuckets; ++b) last = std::max<uint64_t>(last, buckets[b]);
        if (last >= symoffset) {
            for (;; ++last) {
                const uint64_t link = chain_vaddr + (last - symoffset) * sizeof(uint32_t);
                if (!in_image(link, sizeof(uint32_t))) {
                    *error = "DT_GNU_HASH chain runs past the image";
                    return false;
                }
                if (*at<const uint32_t>(link) & 1) break;
            }
            nsyms = last + 1;
        } else {
            nsyms = symoffset;
        }
    }
    // Symbol 0 is always there: relocations name it for "no symbol".
    nsyms = std::max<uint64_t>(nsyms, 1);
    if (nsyms > (hi_vaddr_ - symtab_) / sizeof(Sym)) {
        *error = "DT_SYMTAB with " + std::to_string(nsyms) + " symbols runs past the image";
        return false;
    }
    nsyms_ = static_cast<size_t>(nsyms);
    const Sym* syms = at<const Sym>(symtab_);
    for (size_t i = 0; i < nsyms_; ++i) {
        if (syms[i].st_name >= strsz_) {
            *error = "symbol " + std::to_string(i) + "'s name is outside DT_STRTAB";
            return false;
        }
    }
    for (size_t i = 1; i < nsyms_; ++i) {
        const Sym& s = syms[i];
        const uint8_t bind = st_bind(s.st_info);
        if (s.st_shndx == kShnUndef || (bind != kStbGlobal && bind != kStbWeak)) continue;
        if (st_type(s.st_info) == kSttTls) {
            tls_exports_.emplace(str(s.st_name), s.st_value);
            continue;
        }
        exports_.emplace(str(s.st_name), bias_ + s.st_value);
    }
    return true;
}

bool ElfImage::apply_relocations(const SymbolResolver& resolve, const TlsSymbolResolver& resolve_tls,
                                 std::string* error) {
    const Sym* syms = at<const Sym>(symtab_);
    const char* strtab = at<const char>(strtab_);
    std::vector<std::string> missing;
    std::string fatal;

    // read_dynamic() checked every symbol below nsyms_ and its name; a relocation can name any index.
    auto symbol_in_range = [&](uint32_t idx) -> bool {
        if (idx < nsyms_) return true;
        fatal = "relocation names symbol " + std::to_string(idx) + ", the table has " + std::to_string(nsyms_);
        return false;
    };

    auto symbol_value = [&](uint32_t idx, uint64_t* out) -> bool {
        if (!symbol_in_range(idx)) return false;
        const Sym& s = syms[idx];
        const char* name = strtab + s.st_name;
        if (st_type(s.st_info) == kSttTls) {
            fatal = std::string("TLS symbol ") + name + " used by a non-TLS relocation";
            return false;
        }
        if (s.st_shndx != kShnUndef) {
            *out = bias_ + s.st_value;
            return true;
        }
        if (void* intrinsic = arm64::guest_intrinsic(name)) {
            *out = reinterpret_cast<uint64_t>(intrinsic);
            return true;
        }
        if (void* wrapper = arm64::abi_override(name)) {
            *out = reinterpret_cast<uint64_t>(wrapper);
            return true;
        }
        if (void* host = resolve(name)) {
            *out = reinterpret_cast<uint64_t>(host);
            return true;
        }
        if (st_bind(s.st_info) == kStbWeak) {
            *out = 0;
            return true;
        }
        const SymbolResolver& stand_in_for =
            st_type(s.st_info) == kSttObject ? options_.unresolved_object : options_.unresolved;
        if (stand_in_for) {
            if (void* stand_in = stand_in_for(name)) {
                *out = reinterpret_cast<uint64_t>(stand_in);
                return true;
            }
        }
        if (std::find(missing.begin(), missing.end(), name) == missing.end()) missing.push_back(name);
        *out = 0;
        return true;  // keep going so every missing symbol is reported at once
    };

    // The (module, offset) pair of a dynamic TLS relocation's symbol. Symbol 0
    // stands for this library's own block (local-dynamic code).
    auto tls_symbol_value = [&](uint32_t idx, size_t* module, uint64_t* offset) -> bool {
        if (!symbol_in_range(idx)) return false;
        if (idx == 0 || syms[idx].st_shndx != kShnUndef) {
            if (!tls_module_) {
                fatal = "TLS relocation in a library without PT_TLS";
                return false;
            }
            *module = tls_module_;
            *offset = idx == 0 ? 0 : syms[idx].st_value;
            return true;
        }
        const char* name = strtab + syms[idx].st_name;
        if (resolve_tls && resolve_tls(name, module, offset)) return true;
        fatal = std::string("undefined TLS symbol ") + name;
        return false;
    };

    auto apply = [&](const Rela& r) -> bool {
        const uint32_t type = r_type(r.r_info);
        if (type == kRAarch64_None) return true;
        if (!in_image(r.r_offset, sizeof(uint64_t))) {
            fatal = "relocation target " + hex(r.r_offset) + " outside the image";
            return false;
        }
        uint64_t* where = at<uint64_t>(r.r_offset);
        uint64_t s = 0;
        switch (type) {
            case kRAarch64_Relative:
                *where = bias_ + r.r_addend;
                break;
            case kRAarch64_Abs64:
            case kRAarch64_GlobDat:
            case kRAarch64_JumpSlot:
                if (!symbol_value(r_sym(r.r_info), &s)) return false;
                *where = s + r.r_addend;
                break;
            case kRAarch64_IRelative:
                irelative_slots_.push_back(r.r_offset);
                irelative_addends_.push_back(r.r_addend);
                break;
            case kRAarch64_TlsDtpMod64:
            case kRAarch64_TlsDtpRel64: {
                size_t module = 0;
                uint64_t offset = 0;
                if (!tls_symbol_value(r_sym(r.r_info), &module, &offset)) return false;
                *where = type == kRAarch64_TlsDtpMod64 ? module : offset + r.r_addend;
                break;
            }
            case kRAarch64_TlsDesc: {
                // ELF TLS on arm64 (thread_local in a library built for API
                // 29+). The descriptor is two words: the resolver the code
                // calls with x0 = &descriptor, and its argument. The resolver
                // is the bridge's TLSDESC intrinsic (arm64_native.cpp), which
                // answers the variable's offset from TPIDR_EL0 through the
                // same dynamic TLS blocks __tls_get_addr uses.
                if (!in_image(r.r_offset, 2 * sizeof(uint64_t))) {
                    fatal = "TLSDESC target " + hex(r.r_offset) + " outside the image";
                    return false;
                }
                size_t module = 0;
                uint64_t offset = 0;
                if (!tls_symbol_value(r_sym(r.r_info), &module, &offset)) return false;
                void* resolver = arm64::guest_intrinsic("__winapk_tlsdesc");
                if (!resolver) {
                    fatal = "no TLSDESC resolver";
                    return false;
                }
                tlsdesc_args_.push_back(TlsIndex{module, offset + r.r_addend});
                where[0] = reinterpret_cast<uint64_t>(resolver);
                where[1] = reinterpret_cast<uint64_t>(&tlsdesc_args_.back());
                break;
            }
            case kRAarch64_TlsTpRel64:
                fatal = "static ELF TLS (R_AARCH64_TLS_TPREL64) is not supported";
                return false;
            default:
                fatal = "unsupported arm64 relocation type " + std::to_string(type);
                return false;
        }
        ++relocs_applied_;
        return true;
    };

    auto apply_table = [&](uint64_t vaddr, uint64_t size) -> bool {
        if (!vaddr || !size) return true;
        if (!in_image(vaddr, size)) {
            fatal = "relocation table " + hex(vaddr) + " outside the image";
            return false;
        }
        const Rela* r = at<const Rela>(vaddr);
        for (uint64_t i = 0; i < size / sizeof(Rela); ++i) {
            if (!apply(r[i])) return false;
        }
        return true;
    };

    // DT_RELR: a bitmap encoding of R_AARCH64_RELATIVE runs (gABI "RELR").
    auto apply_relr = [&]() -> bool {
        if (!relr_ || !relrsz_) return true;
        if (!in_image(relr_, relrsz_)) {
            fatal = "DT_RELR table outside the image";
            return false;
        }
        const uint64_t* e = at<const uint64_t>(relr_);
        uint64_t base = 0;  // vaddr of the next word an odd entry describes
        for (uint64_t i = 0; i < relrsz_ / 8; ++i) {
            if ((e[i] & 1) == 0) {
                if (!in_image(e[i], 8)) {
                    fatal = "RELR address outside the image";
                    return false;
                }
                *at<uint64_t>(e[i]) += bias_;
                base = e[i] + 8;
                ++relocs_applied_;
            } else {
                uint64_t bits = e[i] >> 1;
                for (uint64_t k = 0; bits; ++k, bits >>= 1) {
                    if (!(bits & 1)) continue;
                    if (!in_image(base + k * 8, 8)) {
                        fatal = "RELR bitmap address outside the image";
                        return false;
                    }
                    *at<uint64_t>(base + k * 8) += bias_;
                    ++relocs_applied_;
                }
                base += 63 * 8;
            }
        }
        return true;
    };

    // DT_ANDROID_RELA: "APS2" + SLEB128 groups, decoded exactly like bionic's
    // packed_reloc_iterator (linker/linker_reloc_iterators.h).
    auto apply_android_packed = [&]() -> bool {
        if (!android_rela_ || !android_relasz_) return true;
        if (!in_image(android_rela_, android_relasz_) || android_relasz_ < 4) {
            fatal = "DT_ANDROID_RELA outside the image";
            return false;
        }
        const uint8_t* p = at<const uint8_t>(android_rela_);
        if (std::memcmp(p, "APS2", 4) != 0) {
            fatal = "DT_ANDROID_RELA without the APS2 magic";
            return false;
        }
        constexpr int64_t kGroupedByInfo = 1, kGroupedByOffsetDelta = 2, kGroupedByAddend = 4,
                          kGroupHasAddend = 8;
        Sleb128Reader in(p + 4, p + android_relasz_);
        const int64_t count = in.next();
        Rela rel{};
        rel.r_offset = static_cast<uint64_t>(in.next());
        int64_t done = 0;
        while (done < count && in.ok()) {
            const int64_t group_size = in.next();
            const int64_t flags = in.next();
            int64_t offset_delta = 0;
            if (flags & kGroupedByOffsetDelta) offset_delta = in.next();
            if (flags & kGroupedByInfo) rel.r_info = static_cast<uint64_t>(in.next());
            if ((flags & kGroupHasAddend) && (flags & kGroupedByAddend)) {
                rel.r_addend += in.next();
            } else if (!(flags & kGroupHasAddend)) {
                rel.r_addend = 0;
            }
            for (int64_t i = 0; i < group_size && in.ok(); ++i) {
                rel.r_offset += (flags & kGroupedByOffsetDelta) ? offset_delta : in.next();
                if (!(flags & kGroupedByInfo)) rel.r_info = static_cast<uint64_t>(in.next());
                if ((flags & kGroupHasAddend) && !(flags & kGroupedByAddend)) rel.r_addend += in.next();
                if (!apply(rel)) return false;
                ++done;
            }
        }
        if (!in.ok()) {
            fatal = "truncated APS2 relocation stream";
            return false;
        }
        return true;
    };

    const bool ok = apply_relr() && apply_android_packed() && apply_table(rela_, relasz_) &&
                    apply_table(jmprel_, pltrelsz_);
    if (!ok) {
        *error = fatal;
        return false;
    }
    if (!missing.empty()) {
        *error = std::to_string(missing.size()) + " undefined symbol(s) the host does not provide:";
        for (size_t i = 0; i < missing.size() && i < 64; ++i) *error += "\n    " + missing[i];
        if (missing.size() > 64) *error += "\n    ...";
        return false;
    }
    return true;
}

bool ElfImage::protect_segments(std::string* error) {
    // Two segments can share a page (lld does this without -z separate-code);
    // the page gets the union of their permissions, as with mmap on Linux.
    const size_t npages = size_ / kPage;
    std::vector<uint32_t> flags(npages, 0);
    for (const Segment& s : loads_) {
        const size_t first = (page_down(s.vaddr) - lo_vaddr_) / kPage;
        const size_t last = (page_up(s.vaddr + s.memsz) - lo_vaddr_) / kPage;
        for (size_t p = first; p < last; ++p) flags[p] |= s.flags & (kPfR | kPfW | kPfX);
    }
    size_t run = 0;
    for (size_t p = 1; p <= npages; ++p) {
        if (p < npages && flags[p] == flags[run]) continue;
        DWORD old;
        if (!VirtualProtect(mem_ + run * kPage, (p - run) * kPage, page_protection(flags[run]), &old)) {
            *error = "VirtualProtect failed: " + std::to_string(GetLastError());
            return false;
        }
        run = p;
    }
    return true;
}

bool ElfImage::apply_irelative(std::string* error) {
    for (size_t i = 0; i < irelative_slots_.size(); ++i) {
        uint64_t* where = at<uint64_t>(irelative_slots_[i]);
        // The slot sits in a writable segment (GOT / data); RELRO is sealed later.
        // bionic passes (AT_HWCAP | _IFUNC_ARG_HWCAP, &arg); no optional
        // CPU features are reported, so resolvers pick their plain code.
        const uint64_t args[2] = {uint64_t{1} << 62, 0};
        *where = arm64::call(reinterpret_cast<const void*>(bias_ + irelative_addends_[i]), args, 2);
        ++relocs_applied_;
    }
    (void)error;
    return true;
}

void ElfImage::seal_relro() {
    if (!has_relro_) return;
    // Same rounding as bionic's phdr_table_protect_gnu_relro.
    const uint64_t start = page_down(relro_vaddr_);
    const uint64_t end = page_up(relro_vaddr_ + relro_memsz_);
    if (end <= start || !in_image(start, end - start)) return;
    DWORD old;
    VirtualProtect(at<uint8_t>(start), end - start, PAGE_READONLY, &old);
}

// x18 is the TEB on Windows on Arm (arm64_x18.h). WINAPK_ARM64_ALLOW_X18=1 loads such a library anyway.
bool ElfImage::check_x18(const std::vector<uint8_t>& file, std::string* error) const {
    const arm64::ElfX18Scan e = arm64::scan_elf_x18(file.data(), file.size());
    // Reading x18 and restoring a saved context (libunwind's, a longjmp) leave the TEB where it is.
    if (!e.ok || !e.scan.scratch()) return true;
    char text[256];
    std::snprintf(text, sizeof text, "uses register x18 as a scratch register (%llu write(s), the first at file offset 0x%llx)",
                  static_cast<unsigned long long>(e.scan.writes), static_cast<unsigned long long>(e.scan.first_write));
    if (GetEnvironmentVariableA("WINAPK_ARM64_ALLOW_X18", nullptr, 0) > 0) {
        std::fprintf(stderr, "[win.apk] %s: %s; loaded anyway (WINAPK_ARM64_ALLOW_X18)\n", path_.u8string().c_str(), text);
        return true;
    }
    *error = std::string(text) + "; Windows on Arm keeps the thread's TEB in x18, so this library cannot run natively";
    return false;
}

void ElfImage::register_arm64_code() {
    for (const Segment& s : loads_) {
        if (!(s.flags & kPfX) || s.memsz == 0) continue;
        arm64::register_code(at<const void>(s.vaddr), s.memsz);
        arm64_code_.push_back(at<const void>(s.vaddr));
    }
}

// DT_INIT_ARRAY entries are called as fn(argc, argv, envp); DT_INIT,
// DT_FINI and DT_FINI_ARRAY entries take nothing.
void ElfImage::call_init_function(uint64_t fn, bool with_args) const {
    static char* empty_argv[] = {nullptr};
    static char* empty_envp[] = {nullptr};
    const uint64_t args[3] = {0, reinterpret_cast<uint64_t>(empty_argv), reinterpret_cast<uint64_t>(empty_envp)};
    arm64::call(reinterpret_cast<const void*>(fn), args, with_args ? 3 : 0);
}

void ElfImage::run_initializers() {
    if (init_) call_init_function(bias_ + init_, false);
    if (init_array_ && init_arraysz_ && in_image(init_array_, init_arraysz_)) {
        const uint64_t* fns = at<const uint64_t>(init_array_);
        for (uint64_t i = 0; i < init_arraysz_ / 8; ++i) {
            // 0 and -1 are placeholders some toolchains leave in the array.
            if (fns[i] == 0 || fns[i] == UINT64_MAX) continue;
            call_init_function(fns[i], true);
        }
    }
    initialized_ = true;
}

void ElfImage::run_finalizers() {
    if (fini_array_ && fini_arraysz_ && in_image(fini_array_, fini_arraysz_)) {
        const uint64_t* fns = at<const uint64_t>(fini_array_);
        for (uint64_t i = fini_arraysz_ / 8; i-- > 0;) {
            if (fns[i] == 0 || fns[i] == UINT64_MAX) continue;
            call_init_function(fns[i], false);
        }
    }
    if (fini_) call_init_function(bias_ + fini_, false);
    initialized_ = false;
}

void* ElfImage::dlsym(const char* name) const {
    auto it = exports_.find(name);
    return it == exports_.end() ? nullptr : reinterpret_cast<void*>(it->second);
}

bool ElfImage::tls_symbol(const char* name, uint64_t* offset) const {
    if (!tls_module_) return false;
    auto it = tls_exports_.find(name);
    if (it == tls_exports_.end()) return false;
    *offset = it->second;
    return true;
}

bool ElfImage::symbol_at(const void* p, const char** name, void** start) const {
    // Like bionic's soinfo::find_symbol_by_address: only a defined symbol whose
    // [value, value + size) covers the address counts.
    if (!contains(p)) return false;
    const uint64_t addr = reinterpret_cast<uintptr_t>(p);
    const Sym* syms = at<const Sym>(symtab_);
    const char* strtab = at<const char>(strtab_);
    for (size_t i = 1; i < nsyms_; ++i) {
        const Sym& s = syms[i];
        if (s.st_shndx == kShnUndef || s.st_name >= strsz_) continue;
        const uint64_t lo = bias_ + s.st_value;
        if (addr < lo || addr - lo >= s.st_size) continue;
        *name = strtab + s.st_name;
        *start = reinterpret_cast<void*>(lo);
        return true;
    }
    return false;
}

}  // namespace winapk
