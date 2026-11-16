// abi_thunk_arm64.cpp -- abi_thunk.h on an arm64 host: AArch64 thunks and the
// reading of AAPCS64 variadic arguments.
//
// Fixed arguments need no conversion between AAPCS64 and Windows ARM64. The
// thunks: a JNI native (swaps in the guest JNIEnv*; for @CriticalNative drops
// env and class), a variadic entry (Windows reads variadic FP arguments from
// integer registers, AAPCS64 from v0-v7, so both are spilled under an AAPCS64
// va_list), and the unimplemented-native stub. x18 is never touched. The pool
// is RWX.

#include "winapk/abi_thunk.h"

#include "winapk/arch.h"


#include <cstring>
#include <mutex>
#include <vector>

#include <windows.h>

namespace winapk {

namespace {

// ---- a small A64 assembler -------------------------------------------------------------

constexpr uint32_t SP = 31;  // as a base register or in add/sub
constexpr uint32_t XZR = 31;

constexpr uint32_t imm7(int32_t off, int scale) { return static_cast<uint32_t>((off / scale) & 0x7f) << 15; }

constexpr uint32_t stp_pre(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xa9800000 | imm7(off, 8) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t ldp_post(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xa8c00000 | imm7(off, 8) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t stp(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xa9000000 | imm7(off, 8) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t ldp(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xa9400000 | imm7(off, 8) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t stp_q(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xad000000 | imm7(off, 16) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t ldp_q(uint32_t t, uint32_t t2, uint32_t n, int32_t off) {
    return 0xad400000 | imm7(off, 16) | t2 << 10 | n << 5 | t;
}
constexpr uint32_t str_x(uint32_t t, uint32_t n, int32_t off) {
    return 0xf9000000 | static_cast<uint32_t>(off / 8) << 10 | n << 5 | t;
}
constexpr uint32_t ldr_x(uint32_t t, uint32_t n, int32_t off) {
    return 0xf9400000 | static_cast<uint32_t>(off / 8) << 10 | n << 5 | t;
}
constexpr uint32_t str_w(uint32_t t, uint32_t n, int32_t off) {
    return 0xb9000000 | static_cast<uint32_t>(off / 4) << 10 | n << 5 | t;
}
constexpr uint32_t add_imm(uint32_t d, uint32_t n, uint32_t imm) { return 0x91000000 | imm << 10 | n << 5 | d; }
constexpr uint32_t sub_imm(uint32_t d, uint32_t n, uint32_t imm) { return 0xd1000000 | imm << 10 | n << 5 | d; }
constexpr uint32_t mov_x(uint32_t d, uint32_t m) { return 0xaa0003e0 | m << 16 | d; }  // orr xd, xzr, xm
constexpr uint32_t movn_w(uint32_t d, uint32_t imm16) { return 0x12800000 | imm16 << 5 | d; }  // mov wd, #~imm16
constexpr uint32_t blr(uint32_t n) { return 0xd63f0000 | n << 5; }
constexpr uint32_t br(uint32_t n) { return 0xd61f0000 | n << 5; }
constexpr uint32_t ret() { return 0xd65f03c0; }
constexpr uint32_t fmov_d0_xzr() { return 0x9e6703e0; }

// A thunk under construction: code words, then 8-byte literals that `ldr xN, =value` reads.
class Asm {
public:
    void emit(uint32_t w) { code_.push_back(w); }
    size_t size() const { return code_.size(); }
    // Points the cbz at index `at` to the next instruction to be emitted.
    void patch_cbz(size_t at) { code_[at] |= static_cast<uint32_t>((code_.size() - at) & 0x7ffff) << 5; }
    // ldr xt, <literal holding v>
    void ldr_lit(uint32_t t, uint64_t v) {
        fixups_.push_back({code_.size(), literals_.size()});
        literals_.push_back(v);
        emit(0x58000000 | t);
    }
    std::vector<uint8_t> bytes() const {
        // Literals follow the code, 8-byte aligned.
        size_t words = code_.size();
        if (words & 1) ++words;
        std::vector<uint8_t> out((words + literals_.size() * 2) * 4, 0);
        std::memcpy(out.data(), code_.data(), code_.size() * 4);
        std::memcpy(out.data() + words * 4, literals_.data(), literals_.size() * 8);
        for (const Fixup& f : fixups_) {
            const int64_t delta = static_cast<int64_t>(words * 4 + f.literal * 8) - static_cast<int64_t>(f.at * 4);
            uint32_t w;
            std::memcpy(&w, out.data() + f.at * 4, 4);
            w |= static_cast<uint32_t>((delta / 4) & 0x7ffff) << 5;
            std::memcpy(out.data() + f.at * 4, &w, 4);
        }
        return out;
    }

private:
    struct Fixup {
        size_t at;
        size_t literal;
    };
    std::vector<uint32_t> code_;
    std::vector<uint64_t> literals_;
    std::vector<Fixup> fixups_;
};

// ---- the pool --------------------------------------------------------------------------

class Pool {
public:
    void* place(const std::vector<uint8_t>& code) {
        std::lock_guard<std::mutex> hold(lock_);
        const size_t n = (code.size() + 15) & ~size_t{15};
        if (!cur_ || used_ + n > kChunk) {
            cur_ = static_cast<uint8_t*>(VirtualAlloc(nullptr, kChunk, MEM_RESERVE | MEM_COMMIT, PAGE_EXECUTE_READWRITE));
            used_ = 0;
            if (!cur_) return nullptr;
        }
        uint8_t* at = cur_ + used_;
        std::memcpy(at, code.data(), code.size());
        used_ += n;
        FlushInstructionCache(GetCurrentProcess(), at, code.size());
        return at;
    }

private:
    static constexpr size_t kChunk = 64 * 1024;
    std::mutex lock_;
    uint8_t* cur_ = nullptr;
    size_t used_ = 0;
};

Pool& pool() {
    static Pool p;
    return p;
}

bool is_fp(ArgKind k) { return k == ArgKind::Float || k == ArgKind::Double; }

// Saves x0-x8 and q0-q7 in a 224-byte frame (x29, x30 at the bottom) and restores them.
constexpr int32_t kFrame = 224;
void save_args(Asm& a) {
    a.emit(stp_pre(29, 30, SP, -kFrame));
    a.emit(add_imm(29, SP, 0));
    for (uint32_t r = 0; r < 8; r += 2) a.emit(stp(r, r + 1, SP, 16 + static_cast<int32_t>(r) * 8));
    a.emit(str_x(8, SP, 80));
    for (uint32_t q = 0; q < 8; q += 2) a.emit(stp_q(q, q + 1, SP, 96 + static_cast<int32_t>(q) * 16));
}
void restore_args(Asm& a) {
    for (uint32_t q = 0; q < 8; q += 2) a.emit(ldp_q(q, q + 1, SP, 96 + static_cast<int32_t>(q) * 16));
    for (uint32_t r = 0; r < 8; r += 2) a.emit(ldp(r, r + 1, SP, 16 + static_cast<int32_t>(r) * 8));
    a.emit(ldr_x(8, SP, 80));
    a.emit(ldp_post(29, 30, SP, kFrame));
}

}  // namespace

// A critical native (skip 2) drops env and class: x2.. move down, and the stack's first two slots fill x6, x7.
void* make_jni_native_thunk(void* target, const ArgKind* args, size_t nargs, void* env_xlate, size_t skip) {
    if (nargs < 2 || skip > nargs) return nullptr;
    Asm a;
    if (skip == 0) {
        save_args(a);
        a.ldr_lit(16, reinterpret_cast<uint64_t>(env_xlate));
        a.emit(blr(16));              // x0 is still the HotSpot JNIEnv*
        a.emit(str_x(0, SP, 16));     // the guest's, where the saved x0 was
        restore_args(a);
        a.ldr_lit(17, reinterpret_cast<uint64_t>(target));
        a.emit(br(17));
        return pool().place(a.bytes());
    }
    unsigned nint = 0, nfp = 0;
    for (size_t i = skip; i < nargs; ++i) (is_fp(args[i]) ? nfp : nint)++;
    if (nint > 8 || nfp > 8) return nullptr;  // stack-passed arguments would have to move
    // Integer arguments i < 6 sit in x(i+2); 6 and 7 are the first stack slots.
    for (unsigned i = 0; i < nint && i < 6; ++i) a.emit(mov_x(i, i + 2));
    for (unsigned i = 6; i < nint; ++i) a.emit(ldr_x(i, SP, static_cast<int32_t>(8 * (i - 6))));
    a.ldr_lit(17, reinterpret_cast<uint64_t>(target));
    a.emit(br(17));
    return pool().place(a.bytes());
}

// The caller's arguments x0..x7 and q0..q7 are spilled below a va_list that describes them (AAPCS64, B.3):
//
//   [sp + 0]    x29, x30
//   [sp + 16]   x0..x7        gr_top = sp + 80
//   [sp + 80]   q0..q7        vr_top = sp + 208
//   [sp + 208]  the va_list: stack, gr_top, vr_top, gr_offs, vr_offs
//
// The handler gets the `named` integer arguments, then a pointer to the va_list.
void* make_sysv_variadic_entry(int named, void* handler) {
    if (named < 0 || named > 7) return nullptr;
    constexpr int32_t kVa = 208, kSize = 240;
    Asm a;
    a.emit(sub_imm(SP, SP, kSize));
    a.emit(stp(29, 30, SP, 0));
    for (uint32_t r = 0; r < 8; r += 2) a.emit(stp(r, r + 1, SP, 16 + static_cast<int32_t>(r) * 8));
    for (uint32_t q = 0; q < 8; q += 2) a.emit(stp_q(q, q + 1, SP, 80 + static_cast<int32_t>(q) * 16));
    a.emit(add_imm(9, SP, kSize));            // the caller's stack arguments start here
    a.emit(str_x(9, SP, kVa));
    a.emit(add_imm(9, SP, 80));
    a.emit(str_x(9, SP, kVa + 8));            // gr_top
    a.emit(add_imm(9, SP, kVa));
    a.emit(str_x(9, SP, kVa + 16));           // vr_top
    a.emit(movn_w(9, static_cast<uint32_t>(8 * (8 - named) - 1)));  // -(8 - named) * 8
    a.emit(str_w(9, SP, kVa + 24));           // gr_offs
    a.emit(movn_w(9, 127));                   // -128
    a.emit(str_w(9, SP, kVa + 28));           // vr_offs
    a.emit(add_imm(static_cast<uint32_t>(named), SP, kVa));
    a.ldr_lit(16, reinterpret_cast<uint64_t>(handler));
    a.emit(blr(16));
    a.emit(ldp(29, 30, SP, 0));
    a.emit(add_imm(SP, SP, kSize));
    a.emit(ret());
    return pool().place(a.bytes());
}

// handler(first_arg, tag), then 0 in x0 and v0, whatever the caller wanted back.
void* make_tagged_zero_stub(void* handler, const void* tag) {
    Asm a;
    a.emit(stp_pre(29, 30, SP, -16));
    a.ldr_lit(1, reinterpret_cast<uint64_t>(tag));
    a.ldr_lit(16, reinterpret_cast<uint64_t>(handler));
    a.emit(blr(16));
    a.emit(mov_x(0, XZR));
    a.emit(fmov_d0_xzr());
    a.emit(ldp_post(29, 30, SP, 16));
    a.emit(ret());
    return pool().place(a.bytes());
}

void* make_arm64_entry_thunk(void* target, void* enter) {
    Asm a;
    a.emit(0xd53bd050);  // mrs x16, tpidr_el0
    a.emit(0xb4000010);  // cbz x16, <the slow path>, patched below
    const size_t cbz_at = a.size() - 1;
    a.ldr_lit(17, reinterpret_cast<uint64_t>(target));
    a.emit(br(17));
    a.patch_cbz(cbz_at);  // the slow path starts here
    save_args(a);
    a.ldr_lit(16, reinterpret_cast<uint64_t>(enter));
    a.emit(blr(16));
    restore_args(a);
    a.ldr_lit(17, reinterpret_cast<uint64_t>(target));
    a.emit(br(17));
    return pool().place(a.bytes());
}

// ---- reading AAPCS64 variadic arguments (Arm's procedure call standard, B.3 and C.9) ----------------

uint64_t sysv_va_next_gp(SysVVaList* va) {
    uint64_t v;
    if (va->gr_offs < 0) {
        std::memcpy(&v, va->gr_top + va->gr_offs, sizeof v);
        va->gr_offs += 8;
    } else {
        std::memcpy(&v, va->stack, sizeof v);
        va->stack += 8;
    }
    return v;
}

double sysv_va_next_fp(SysVVaList* va) {
    double v;
    if (va->vr_offs < 0) {
        std::memcpy(&v, va->vr_top + va->vr_offs, sizeof v);
        va->vr_offs += 16;
    } else {
        std::memcpy(&v, va->stack, sizeof v);
        va->stack += 8;
    }
    return v;
}

// binary128 (a Q register, or 16 aligned stack bytes), truncated to the host's double.
long double guest_va_next_long_double(SysVVaList* va) {
    uint64_t q[2];  // little endian: q[0] the low half
    if (va->vr_offs < 0) {
        std::memcpy(q, va->vr_top + va->vr_offs, sizeof q);
        va->vr_offs += 16;
    } else {
        uintptr_t p = (reinterpret_cast<uintptr_t>(va->stack) + 15) & ~uintptr_t{15};
        std::memcpy(q, reinterpret_cast<void*>(p), sizeof q);
        va->stack = reinterpret_cast<uint8_t*>(p + 16);
    }
    const bool neg = (q[1] >> 63) != 0;
    const int exp = static_cast<int>((q[1] >> 48) & 0x7fff);
    const uint64_t frac_hi = q[1] & 0x0000ffffffffffffull;  // 48 bits of the 112
    const uint64_t mant52 = (frac_hi << 4) | (q[0] >> 60);   // the top 52 fraction bits
    uint64_t bits = neg ? uint64_t{1} << 63 : 0;
    if (exp == 0x7fff) {
        bits |= uint64_t{0x7ff} << 52 | (frac_hi || q[0] ? uint64_t{1} << 51 : 0);  // infinity or NaN
    } else if (exp == 0 && !frac_hi && !q[0]) {
        // zero
    } else {
        const int e = exp - 16383 + 1023;
        if (e >= 0x7ff) {
            bits |= uint64_t{0x7ff} << 52;  // too large: infinity
        } else if (e > 0) {
            bits |= static_cast<uint64_t>(e) << 52 | mant52;
        }  // too small for a normal double: zero
    }
    double d;
    std::memcpy(&d, &bits, sizeof d);
    return d;
}

}  // namespace winapk
