// xlat_core.cpp -- direct ARM32/Thumb -> arm64 recompiler, core half.
// See xlat_core.h for the integration contract and the three traps that
// make A64 a non-drop-in for A32.
//
// Semantics are carried over from runtime/translator/src/arm32_lifter.cpp
// (the LLVM-IR pipeline this replaces). Where a behaviour here differs
// from that file the comment says so explicitly and why, so a future
// session bisecting a regression can find it.

#include "xlat_core.h"

// mem_or_simd_owns() needs the two ownership predicates, but the sibling
// HEADERS cannot be included here: xlat_mem.h and xlat_simd.h are written
// against `using namespace a64`, so their `Label` / `Cond` / `cc_is_always`
// collide with this file's same-named declarations in the shared
// macapk::arm32rc namespace. Forward-declare the two functions instead;
// they are the entire surface this file needs.
#if MACAPK_XLAT_CORE_HAVE_SIBLINGS
namespace macapk {
namespace arm32rc {
bool is_mem_insn(unsigned id);
bool is_simd_insn(unsigned id);
}  // namespace arm32rc
}  // namespace macapk
#endif

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace macapk {
namespace arm32rc {

// =====================================================================
//  Refusal tracing (MACAPK_ARM32RC_STUB_TRACE)
// =====================================================================
//
// See the header for the rationale. The sink is resolved once: an
// env value of "1"/"on"/"yes" goes to stderr, anything else is taken
// as a path and appended to.

namespace {
FILE* g_stub_trace = nullptr;
}  // namespace

// Resolved exactly once, through a function-local static: C++11 makes that
// initialisation thread-safe, and since 2026-09-26 build_module translates
// functions on several threads (MACAPK_FIRSTLAUNCH_RC_THREADS), so the old
// "-1 = unknown, then store" int was a data race the first time two workers
// asked. Same env contract, same answer.
bool rc_stub_trace_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_STUB_TRACE");
        if (!e || !*e || std::strcmp(e, "0") == 0) return false;
        if (std::strcmp(e, "1") == 0 || std::strcmp(e, "on") == 0 ||
            std::strcmp(e, "yes") == 0) {
            g_stub_trace = stderr;
        } else {
            g_stub_trace = std::fopen(e, "a");
            if (!g_stub_trace) g_stub_trace = stderr;
        }
        return true;
    }();
    return on;
}

void rc_stub_trace(uint32_t vaddr, bool thumb, const cs_insn* in,
                   const char* stage, const char* reason) {
    if (!rc_stub_trace_enabled() || !g_stub_trace) return;
    // The instruction WORD is printed too: for the literal-pool and
    // mis-clipped-.rodata cases the raw bytes are the whole story (an
    // `andeq r0,r0,r0` whose word is 0x00000000 is a zero constant, not
    // an instruction), and recovering them afterwards from an address
    // alone means re-opening the ELF.
    uint32_t word = 0;
    const unsigned n = in && in->size <= 4 ? in->size : 0;
    for (unsigned i = 0; i < n; ++i)
        word |= static_cast<uint32_t>(in->bytes[i]) << (8 * i);
    std::fprintf(g_stub_trace, "[rc-stub] 0x%08x %c %-14s %08x %s %s :: %s\n",
                 vaddr, thumb ? 'T' : 'A', stage, word,
                 in ? in->mnemonic : "?", in ? in->op_str : "",
                 reason ? reason : "(no reason recorded)");
    std::fflush(g_stub_trace);
}

// =====================================================================
//  Free helpers
// =====================================================================

const char* cond_name(Cond c) {
    switch (c) {
        case Cond::EQ: return "eq";
        case Cond::NE: return "ne";
        case Cond::CS: return "cs";
        case Cond::CC: return "cc";
        case Cond::MI: return "mi";
        case Cond::PL: return "pl";
        case Cond::VS: return "vs";
        case Cond::VC: return "vc";
        case Cond::HI: return "hi";
        case Cond::LS: return "ls";
        case Cond::GE: return "ge";
        case Cond::LT: return "lt";
        case Cond::GT: return "gt";
        case Cond::LE: return "le";
        case Cond::AL: return "al";
        case Cond::NV: return "nv";
    }
    return "??";
}

bool alu_is_arith(AluOp op) {
    return op == AluOp::ADD || op == AluOp::SUB ||
           op == AluOp::ADC || op == AluOp::SBC;
}

namespace {

// Capstone register id -> ARM32 register index, identical to
// arm32_lifter.cpp's arm_reg_idx so both translators agree on operand
// decoding.
int guest_reg_of(unsigned int reg) {
    switch (reg) {
        case ARM_REG_R0:  return 0;
        case ARM_REG_R1:  return 1;
        case ARM_REG_R2:  return 2;
        case ARM_REG_R3:  return 3;
        case ARM_REG_R4:  return 4;
        case ARM_REG_R5:  return 5;
        case ARM_REG_R6:  return 6;
        case ARM_REG_R7:  return 7;
        case ARM_REG_R8:  return 8;
        case ARM_REG_R9:  return 9;
        case ARM_REG_R10: return 10;
        case ARM_REG_R11: return 11;
        case ARM_REG_R12: return 12;
        case ARM_REG_SP:  return 13;
        case ARM_REG_LR:  return 14;
        case ARM_REG_PC:  return 15;
        default:          return -1;
    }
}

bool cc_is_always(arm_cc cc) {
    return cc == ARM_CC_AL || cc == ARM_CC_INVALID;
}

// A32 Table A8-1 and A64 Table C1-1 use the SAME 4-bit encodings, so
// this is the identity mapping written out longhand (capstone's arm_cc
// is 1-based and has drifted between major versions, so an explicit
// switch is safer than `cc - 1`).
Cond map_cond(arm_cc cc) {
    switch (cc) {
        case ARM_CC_EQ: return Cond::EQ;
        case ARM_CC_NE: return Cond::NE;
        case ARM_CC_HS: return Cond::CS;
        case ARM_CC_LO: return Cond::CC;
        case ARM_CC_MI: return Cond::MI;
        case ARM_CC_PL: return Cond::PL;
        case ARM_CC_VS: return Cond::VS;
        case ARM_CC_VC: return Cond::VC;
        case ARM_CC_HI: return Cond::HI;
        case ARM_CC_LS: return Cond::LS;
        case ARM_CC_GE: return Cond::GE;
        case ARM_CC_LT: return Cond::LT;
        case ARM_CC_GT: return Cond::GT;
        case ARM_CC_LE: return Cond::LE;
        default:        return Cond::AL;
    }
}

bool is_shifted_mask64(uint64_t v) {
    // A run of consecutive set bits, nothing else.
    if (v == 0) return false;
    uint64_t low = v & (~v + 1u);       // lowest set bit
    return ((v + low) & v) == 0;
}

int popcount64(uint64_t v) {
    int n = 0;
    while (v) { v &= v - 1; ++n; }
    return n;
}

}  // namespace

bool a64_encodable_add_imm(uint64_t v) {
    if (v <= 0xfffu) return true;
    if ((v & 0xfffu) == 0 && (v >> 12) <= 0xfffu) return true;
    return false;
}

// AArch64 logical (bitmask) immediates encode a value that is the
// replication, across the register, of an element of size 2/4/8/16/32/64
// bits whose content is a rotation of a run of consecutive ones
// (DDI 0487 C4.1.4, DecodeBitMasks). The 8-bit-rotated A32 immediate
// encoding is unrelated, so most A32 logical immediates do NOT survive
// the translation and have to be materialised into a register.
bool a64_is_logical_imm(uint64_t v, unsigned width) {
    if (width == 32) {
        v &= 0xffffffffull;
        // Replicate into 64 bits, which is how a 32-bit logical
        // immediate is defined (N == 0 forces element size <= 32).
        v |= v << 32;
    }
    if (v == 0 || v == ~0ull) return false;

    // Find the element size: the smallest power-of-two width whose
    // low half equals its high half all the way down.
    unsigned size = 64;
    for (;;) {
        size /= 2;
        uint64_t mask = (size == 64) ? ~0ull : ((1ull << size) - 1);
        if ((v & mask) != ((v >> size) & mask)) { size *= 2; break; }
        if (size <= 2) break;
    }

    uint64_t mask = (size == 64) ? ~0ull : ((1ull << size) - 1);
    uint64_t elt = v & mask;
    if (elt == 0 || elt == mask) return false;

    // The element must be a rotation of 0^m 1^n. Either it is already a
    // contiguous run, or its complement (within the element) is.
    if (is_shifted_mask64(elt)) return true;
    uint64_t inv = (~elt) & mask;
    if (is_shifted_mask64(inv)) return true;

    // Wrapping run: ones at both the top and the bottom of the element.
    // Rotating right by the trailing-ones count makes it contiguous.
    int ones = popcount64(elt);
    if (ones <= 0 || ones >= static_cast<int>(size)) return false;
    int trailing_ones = 0;
    while (trailing_ones < static_cast<int>(size) &&
           ((elt >> trailing_ones) & 1u)) ++trailing_ones;
    uint64_t rot = ((elt >> trailing_ones) |
                    (elt << (size - trailing_ones))) & mask;
    return is_shifted_mask64(rot) && popcount64(rot) == ones;
}

// =====================================================================
//  Sink / RegFile boilerplate
// =====================================================================

A64Sink::~A64Sink() {}
void A64Sink::comment(const std::string&) {}
RegFile::~RegFile() {}

// =====================================================================
//  TextA64Sink
// =====================================================================

namespace {

std::string reg_name(int r, bool sf64) {
    if (r == kZR) return sf64 ? "xzr" : "wzr";
    char buf[8];
    std::snprintf(buf, sizeof(buf), "%c%d", sf64 ? 'x' : 'w', r);
    return buf;
}

const char* sh_name(Sh s) {
    switch (s) {
        case Sh::LSL: return "lsl";
        case Sh::LSR: return "lsr";
        case Sh::ASR: return "asr";
        case Sh::ROR: return "ror";
    }
    return "lsl";
}

const char* alu_mnemonic(AluOp op, bool S) {
    switch (op) {
        case AluOp::ADD: return S ? "adds" : "add";
        case AluOp::SUB: return S ? "subs" : "sub";
        case AluOp::ADC: return S ? "adcs" : "adc";
        case AluOp::SBC: return S ? "sbcs" : "sbc";
        case AluOp::AND: return S ? "ands" : "and";
        case AluOp::ORR: return "orr";
        case AluOp::EOR: return "eor";
        case AluOp::BIC: return S ? "bics" : "bic";
        case AluOp::ORN: return "orn";
        case AluOp::EON: return "eon";
    }
    return "?";
}

const char* ext_name(Ext e) {
    switch (e) {
        case Ext::UXTB: return "uxtb";
        case Ext::UXTH: return "uxth";
        case Ext::UXTW: return "uxtw";
        case Ext::SXTB: return "sxtb";
        case Ext::SXTH: return "sxth";
        case Ext::SXTW: return "sxtw";
    }
    return "uxtw";
}

std::string hexi(uint64_t v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "0x%llx",
                  static_cast<unsigned long long>(v));
    return buf;
}

std::string deci(long long v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%lld", v);
    return buf;
}

std::string label_name(Label l) {
    return ".L" + deci(l.id);
}

}  // namespace

void TextA64Sink::line(const std::string& s) { out_ += "  " + s + "\n"; }

void TextA64Sink::alu_reg(AluOp op, int rd, int rn, int rm, Sh sh, int amt,
                          bool S, bool sf64) {
    std::string s = std::string(alu_mnemonic(op, S)) + " " +
                    reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", " +
                    reg_name(rm, sf64);
    if (amt != 0) s += std::string(", ") + sh_name(sh) + " #" + deci(amt);
    line(s);
}

void TextA64Sink::alu_imm(AluOp op, int rd, int rn, uint64_t imm, bool S,
                          bool sf64) {
    line(std::string(alu_mnemonic(op, S)) + " " + reg_name(rd, sf64) + ", " +
         reg_name(rn, sf64) + ", #" + hexi(imm));
}

void TextA64Sink::alu_extended(AluOp op, int rd, int rn, int rm, Ext ext,
                               int shift, bool S, bool sf64) {
    // The extended-register form always takes a W source operand for the
    // byte / halfword / word extends.
    std::string s = std::string(alu_mnemonic(op, S)) + " " +
                    reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", " +
                    reg_name(rm, false) + ", " + ext_name(ext);
    if (shift != 0) s += " #" + deci(shift);
    line(s);
}

void TextA64Sink::movz(int rd, uint16_t imm16, int shift, bool sf64) {
    std::string s = "movz " + reg_name(rd, sf64) + ", #" + hexi(imm16);
    if (shift) s += ", lsl #" + deci(shift);
    line(s);
}

void TextA64Sink::movn(int rd, uint16_t imm16, int shift, bool sf64) {
    std::string s = "movn " + reg_name(rd, sf64) + ", #" + hexi(imm16);
    if (shift) s += ", lsl #" + deci(shift);
    line(s);
}

void TextA64Sink::movk(int rd, uint16_t imm16, int shift, bool sf64) {
    std::string s = "movk " + reg_name(rd, sf64) + ", #" + hexi(imm16);
    if (shift) s += ", lsl #" + deci(shift);
    line(s);
}

void TextA64Sink::mov_reg(int rd, int rm, bool sf64) {
    line("mov " + reg_name(rd, sf64) + ", " + reg_name(rm, sf64));
}

void TextA64Sink::shift_reg(Sh sh, int rd, int rn, int rm, bool sf64) {
    const char* m = "lslv";
    switch (sh) {
        case Sh::LSL: m = "lslv"; break;
        case Sh::LSR: m = "lsrv"; break;
        case Sh::ASR: m = "asrv"; break;
        case Sh::ROR: m = "rorv"; break;
    }
    line(std::string(m) + " " + reg_name(rd, sf64) + ", " +
         reg_name(rn, sf64) + ", " + reg_name(rm, sf64));
}

void TextA64Sink::shift_imm(Sh sh, int rd, int rn, int amt, bool sf64) {
    line(std::string(sh_name(sh)) + " " + reg_name(rd, sf64) + ", " +
         reg_name(rn, sf64) + ", #" + deci(amt));
}

void TextA64Sink::extr(int rd, int rn, int rm, int lsb, bool sf64) {
    line("extr " + reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", " +
         reg_name(rm, sf64) + ", #" + deci(lsb));
}

void TextA64Sink::ubfx(int rd, int rn, int lsb, int width, bool sf64) {
    line("ubfx " + reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", #" +
         deci(lsb) + ", #" + deci(width));
}

void TextA64Sink::sbfx(int rd, int rn, int lsb, int width, bool sf64) {
    line("sbfx " + reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", #" +
         deci(lsb) + ", #" + deci(width));
}

void TextA64Sink::bfi(int rd, int rn, int lsb, int width, bool sf64) {
    line("bfi " + reg_name(rd, sf64) + ", " + reg_name(rn, sf64) + ", #" +
         deci(lsb) + ", #" + deci(width));
}

void TextA64Sink::mul3(MulOp op, int rd, int rn, int rm, int ra) {
    switch (op) {
        case MulOp::MADD:
            line("madd " + reg_name(rd, false) + ", " + reg_name(rn, false) +
                 ", " + reg_name(rm, false) + ", " + reg_name(ra, false));
            break;
        case MulOp::MSUB:
            line("msub " + reg_name(rd, false) + ", " + reg_name(rn, false) +
                 ", " + reg_name(rm, false) + ", " + reg_name(ra, false));
            break;
        case MulOp::UMADDL:
            line("umaddl " + reg_name(rd, true) + ", " + reg_name(rn, false) +
                 ", " + reg_name(rm, false) + ", " + reg_name(ra, true));
            break;
        case MulOp::SMADDL:
            line("smaddl " + reg_name(rd, true) + ", " + reg_name(rn, false) +
                 ", " + reg_name(rm, false) + ", " + reg_name(ra, true));
            break;
        case MulOp::SMSUBL:
            line("smsubl " + reg_name(rd, true) + ", " + reg_name(rn, false) +
                 ", " + reg_name(rm, false) + ", " + reg_name(ra, true));
            break;
    }
}

void TextA64Sink::divide(bool is_signed, int rd, int rn, int rm, bool sf64) {
    line(std::string(is_signed ? "sdiv " : "udiv ") + reg_name(rd, sf64) +
         ", " + reg_name(rn, sf64) + ", " + reg_name(rm, sf64));
}

void TextA64Sink::bit1(Bit1Op op, int rd, int rn, bool sf64) {
    const char* m = "clz";
    switch (op) {
        case Bit1Op::CLZ:   m = "clz";   break;
        case Bit1Op::RBIT:  m = "rbit";  break;
        case Bit1Op::REV:   m = "rev";   break;
        case Bit1Op::REV16: m = "rev16"; break;
        case Bit1Op::SXTB:  m = "sxtb";  break;
        case Bit1Op::SXTH:  m = "sxth";  break;
        case Bit1Op::UXTB:  m = "uxtb";  break;
        case Bit1Op::UXTH:  m = "uxth";  break;
    }
    // The extend aliases always read a W source, even in the X form.
    bool src64 = sf64 && (op == Bit1Op::CLZ || op == Bit1Op::RBIT ||
                          op == Bit1Op::REV || op == Bit1Op::REV16);
    line(std::string(m) + " " + reg_name(rd, sf64) + ", " +
         reg_name(rn, src64));
}

void TextA64Sink::csel(CselOp op, int rd, int rn, int rm, Cond c, bool sf64) {
    const char* m = "csel";
    switch (op) {
        case CselOp::CSEL:  m = "csel";  break;
        case CselOp::CSINC: m = "csinc"; break;
        case CselOp::CSINV: m = "csinv"; break;
        case CselOp::CSNEG: m = "csneg"; break;
    }
    line(std::string(m) + " " + reg_name(rd, sf64) + ", " +
         reg_name(rn, sf64) + ", " + reg_name(rm, sf64) + ", " +
         cond_name(c));
}

void TextA64Sink::cset(int rd, Cond c, bool sf64) {
    line("cset " + reg_name(rd, sf64) + ", " + cond_name(c));
}

void TextA64Sink::mrs_nzcv(int xd) {
    line("mrs " + reg_name(xd, true) + ", nzcv");
}

void TextA64Sink::msr_nzcv(int xn) {
    line("msr nzcv, " + reg_name(xn, true));
}

Label TextA64Sink::new_label() {
    Label l;
    l.id = next_label_++;
    return l;
}

void TextA64Sink::bind(Label l) { out_ += label_name(l) + ":\n"; }
void TextA64Sink::b(Label l) { line("b " + label_name(l)); }

void TextA64Sink::b_cond(Cond c, Label l) {
    line(std::string("b.") + cond_name(c) + " " + label_name(l));
}

void TextA64Sink::cbz(int rt, Label l, bool sf64) {
    line("cbz " + reg_name(rt, sf64) + ", " + label_name(l));
}

void TextA64Sink::cbnz(int rt, Label l, bool sf64) {
    line("cbnz " + reg_name(rt, sf64) + ", " + label_name(l));
}

void TextA64Sink::call_direct(uint32_t t) {
    line("bl __macapk_target_" + hexi(t));
}

void TextA64Sink::call_indirect(int host_reg) {
    line("blr " + reg_name(host_reg, true));
}

void TextA64Sink::tail_branch_direct(uint32_t t) {
    line("b __macapk_target_" + hexi(t));
}

void TextA64Sink::tail_branch_indirect(int host_reg) {
    line("br " + reg_name(host_reg, true));
}

void TextA64Sink::ret_from_function() { line("ret"); }

void TextA64Sink::load_guest_u32(int rd, int addr_reg) {
    // The guest-base addressing model belongs to the memory translator;
    // the text sink renders the intent so the sequence is still
    // assemblable and readable.
    line("ldr " + reg_name(rd, false) + ", [x_guestbase, " +
         reg_name(addr_reg, true) + "]");
}

void TextA64Sink::load_guest_zx(int rd, int addr_reg, int bytes) {
    line(std::string(bytes == 1 ? "ldrb " : "ldrh ") + reg_name(rd, false) +
         ", [x_guestbase, " + reg_name(addr_reg, true) + "]");
}

void TextA64Sink::guest_state_load(int rd, uint32_t byte_off, int scratch) {
    line("// GuestState +" + hexi(byte_off) + " via " +
         reg_name(scratch, true));
    line("ldr " + reg_name(rd, false) + ", [x_gueststate, #" +
         deci(static_cast<long long>(byte_off)) + "]");
}

void TextA64Sink::guest_state_store(int rs, uint32_t byte_off, int scratch) {
    line("// GuestState +" + hexi(byte_off) + " via " +
         reg_name(scratch, true));
    line("str " + reg_name(rs, false) + ", [x_gueststate, #" +
         deci(static_cast<long long>(byte_off)) + "]");
}

void TextA64Sink::svc_bridge(uint32_t imm, int rd) {
    line("// svc #" + deci(static_cast<long long>(imm)));
    line("movn " + reg_name(rd, false) + ", #37");   // -38 == -ENOSYS
}

void TextA64Sink::sys_op(SysOp op, uint16_t imm) {
    switch (op) {
        case SysOp::Nop:        line("nop"); break;
        case SysOp::Yield:      line("yield"); break;
        case SysOp::Sev:        line("sev"); break;
        case SysOp::Sevl:       line("sevl"); break;
        case SysOp::Wfe:        line("wfe"); break;
        case SysOp::Wfi:        line("wfi"); break;
        case SysOp::Breakpoint: line("brk " + hexi(imm)); break;
        case SysOp::Undefined:  line("udf " + hexi(imm)); break;
    }
}

void TextA64Sink::comment(const std::string& text) {
    out_ += "  // " + text + "\n";
}

// =====================================================================
//  XlatCore
// =====================================================================

XlatCore::XlatCore(A64Sink& sink, RegFile& regs, const XlatOptions& opts,
                   const XlatHooks& hooks)
    : sink_(sink), regs_(regs), opts_(opts), hooks_(hooks) {}

void XlatCore::set_stream(const cs_insn* insns, size_t count) {
    insns_ = insns;
    count_ = count;
    // Rebuilt per function: the table-branch lowering needs to know
    // whether a case target is a real decoded instruction before it is
    // allowed to mint a label for it (see case_labels_ in the header).
    addr_index_.clear();
    addr_index_.reserve(count * 2);
    for (size_t i = 0; i < count; ++i) {
        if (insns[i].id == 0) continue;   // SKIPDATA filler, never visited
        addr_index_[static_cast<uint32_t>(insns[i].address)] = i;
    }
    case_labels_.clear();
    case_labels_bound_.clear();

    // v1.0.816: discover and pre-mint labels for the intermediate-
    // register PC-table idiom's table entries in BOTH directions, before
    // any instruction in this function is translated -- see
    // prescan_pc_tables()'s own header comment in xlat_core.h for why
    // this is what makes a backward table entry resolvable at all.
    prescan_pc_tables();
}

void XlatCore::warn(const std::string& msg) const {
    if (hooks_.warn) hooks_.warn(msg);
    else std::fprintf(stderr, "[arm32rc] WARN: %s\n", msg.c_str());
}

void XlatCore::set_error(const std::string& msg) { error_ = msg; }

bool XlatCore::refuse(const char* why) {
    refuse_reason_ = why;
    return false;
}

#if !MACAPK_XLAT_CORE_HAVE_SIBLINGS
// Standalone / self-test build: no other stage exists, so nothing else
// owns any id and every un-dispatched instruction is worth tracing.
bool mem_or_simd_owns(unsigned) { return false; }
#endif

// A2.3 ("PC, the program counter"): reading R15 yields the address of the
// current instruction plus 8 in ARM state and plus 4 in Thumb state.
// A8.8.12 ADR and every PC-relative literal form: the PC-relative
// ADDRESS forms (ADR, literal pool) use Align(PC, 4).
uint32_t XlatCore::pc_value_for(uint32_t insn_addr, bool aligned) const {
    uint32_t pc = insn_addr + (opts_.thumb ? 4u : 8u);
    if (aligned) pc &= ~3u;
    return pc + opts_.load_bias;
}

uint32_t XlatCore::pc_value_for_lookup(uint32_t insn_addr, bool aligned) const {
    return pc_value_for(insn_addr, aligned) - opts_.load_bias;
}

void XlatCore::materialise_u32(int rd, uint32_t v) {
    if ((v & 0xffff0000u) == 0) {
        sink_.movz(rd, static_cast<uint16_t>(v), 0, false);
        return;
    }
    if ((v & 0x0000ffffu) == 0) {
        sink_.movz(rd, static_cast<uint16_t>(v >> 16), 16, false);
        return;
    }
    uint32_t inv = ~v;
    if ((inv & 0xffff0000u) == 0) {
        sink_.movn(rd, static_cast<uint16_t>(inv), 0, false);
        return;
    }
    if ((inv & 0x0000ffffu) == 0) {
        sink_.movn(rd, static_cast<uint16_t>(inv >> 16), 16, false);
        return;
    }
    if (a64_is_logical_imm(v, 32)) {
        sink_.alu_imm(AluOp::ORR, rd, kZR, v, false, false);
        return;
    }
    sink_.movz(rd, static_cast<uint16_t>(v), 0, false);
    sink_.movk(rd, static_cast<uint16_t>(v >> 16), 16, false);
}

int XlatCore::materialise_tmp(uint32_t v, std::vector<int>* owned) {
    int t = regs_.temp_alloc();
    owned->push_back(t);
    materialise_u32(t, v);
    return t;
}

void XlatCore::release(std::vector<int>& owned) {
    for (size_t i = owned.size(); i > 0; --i) regs_.temp_free(owned[i - 1]);
    owned.clear();
}

// ---------------------------------------------------------------------
//  Operand decoding
// ---------------------------------------------------------------------

bool XlatCore::decode_op2(const cs_arm_op& src, const cs_arm_op* rot,
                          Op2* out) {
    *out = Op2{};

    // ARM-mode 4-operand modified-immediate form: capstone splits the
    // encoded imm12 into (#imm8, #rotation_in_bits) and has already
    // doubled the 4-bit rotate field, so it applies directly.
    if (rot != nullptr && rot->type == ARM_OP_IMM &&
        src.type == ARM_OP_IMM) {
        uint32_t imm8 = static_cast<uint32_t>(src.imm);
        uint32_t r = static_cast<uint32_t>(rot->imm) & 31u;
        uint32_t v = (r == 0) ? imm8
                              : ((imm8 >> r) | (imm8 << ((32 - r) & 31)));
        out->is_imm = true;
        out->imm = v;
        // ARMExpandImm_C (A5.2.4): carry_out is unchanged when the
        // rotation is zero and is imm32<31> otherwise. This is a
        // translate-time constant, so modelling it exactly costs
        // nothing. The LLVM lifter left C alone in both cases
        // (resolve_alu_src_c, "conservative"); opts_.exact_modified_imm_carry
        // restores that if a regression is ever traced here.
        if (r != 0 && opts_.exact_modified_imm_carry) {
            out->imm_carry_known = true;
            out->imm_carry = (v >> 31) != 0;
        }
        return true;
    }

    if (src.type == ARM_OP_IMM) {
        out->is_imm = true;
        out->imm = static_cast<uint32_t>(src.imm);
        // The 4-operand (#imm8, #rot) branch above is the EXCEPTION, not
        // the rule: capstone normally reports a modified immediate as two
        // operands with the value already expanded and the rotation
        // DROPPED. Measured -- `movs r1, #0xf0000000` decodes with
        // op_count == 2 and operands[1].imm == 0xf0000000. So keying the
        // carry-out only on that branch left C wrong for the common
        // encoding, which is exactly the bug the differential harness
        // caught on this path's first end-to-end run
        // (modimm_carry_clear: C stayed at whatever the caller left).
        //
        // The rotation is recoverable from the instruction WORD:
        //   A32 (A5.2.4 ARMExpandImm_C): rotation = 2*imm12<11:8>;
        //       carry_out = imm32<31> when nonzero, else UNCHANGED.
        //   T32 (A6.3.2 ThumbExpandImm_C): imm12 = i:imm3:imm8 (bit 26,
        //       bits 14:12, bits 7:0); imm12<11:10> == 0b00 selects the
        //       byte-replication patterns (carry UNCHANGED), anything
        //       else is the rotate form with carry_out = imm32<31>.
        if (opts_.exact_modified_imm_carry && insns_ && idx_ < count_) {
            const cs_insn& cur = insns_[idx_];
            if (cur.size == 4) {
                const uint8_t* b = cur.bytes;
                bool rotates = false, applicable = false;
                if (opts_.thumb) {
                    const uint32_t w = (static_cast<uint32_t>(b[1]) << 24) |
                                       (static_cast<uint32_t>(b[0]) << 16) |
                                       (static_cast<uint32_t>(b[3]) << 8) |
                                        static_cast<uint32_t>(b[2]);
                    if ((w >> 27) == 0x1Eu && ((w >> 15) & 1u) == 0u) {
                        const uint32_t imm12 = (((w >> 26) & 1u) << 11) |
                                               (((w >> 12) & 7u) << 8) |
                                                (w & 0xFFu);
                        applicable = true;
                        rotates = ((imm12 >> 10) & 3u) != 0u;
                    }
                } else {
                    const uint32_t w =  static_cast<uint32_t>(b[0])        |
                                       (static_cast<uint32_t>(b[1]) << 8)  |
                                       (static_cast<uint32_t>(b[2]) << 16) |
                                       (static_cast<uint32_t>(b[3]) << 24);
                    if (((w >> 26) & 3u) == 0u && ((w >> 25) & 1u) != 0u) {
                        applicable = true;
                        rotates = ((w >> 8) & 0xFu) != 0u;
                    }
                }
                if (applicable && rotates) {
                    out->imm_carry_known = true;
                    out->imm_carry = (out->imm >> 31) != 0;
                }
            }
        }
        return true;
    }

    if (src.type != ARM_OP_REG)
        return refuse("operand2 is neither an immediate nor a register "
                      "(coprocessor / sysreg / setend operand class)");
    int g = guest_reg_of(src.reg);
    if (g < 0)
        return refuse("operand2 register is not a core register r0..r15");
    out->guest_reg = g;

    const arm_shifter st = src.shift.type;
    if (st == ARM_SFT_INVALID) return true;
    if (st == ARM_SFT_LSL && src.shift.value == 0) return true;

    const bool reg_shift = (st == ARM_SFT_LSL_REG || st == ARM_SFT_LSR_REG ||
                            st == ARM_SFT_ASR_REG || st == ARM_SFT_ROR_REG);
    if (!reg_shift && src.shift.value == 0 && st != ARM_SFT_RRX) {
        // An immediate-amount non-LSL shift of 0 is not a real encoding;
        // treat it as "no shift" exactly like the LLVM lifter did.
        return true;
    }

    out->has_shift = true;
    switch (st) {
        case ARM_SFT_LSL: case ARM_SFT_LSL_REG: out->sh = Sh::LSL; break;
        case ARM_SFT_LSR: case ARM_SFT_LSR_REG: out->sh = Sh::LSR; break;
        case ARM_SFT_ASR: case ARM_SFT_ASR_REG: out->sh = Sh::ASR; break;
        case ARM_SFT_ROR: case ARM_SFT_ROR_REG: out->sh = Sh::ROR; break;
        case ARM_SFT_RRX: out->rrx = true; out->sh = Sh::ROR; break;
        default: return refuse("unrecognised barrel-shifter kind");
    }
    if (reg_shift) {
        int sg = guest_reg_of(src.shift.value);
        if (sg < 0) return refuse("shift-amount register is not r0..r15");
        out->shift_by_reg = true;
        out->sh_guest_reg = sg;
    } else if (!out->rrx) {
        out->sh_amt = static_cast<int>(src.shift.value);
    }
    return true;
}

bool XlatCore::lower_op2(const Op2& o, bool allow_fold, bool logical_ctx,
                         Src* out, std::vector<int>* owned) {
    *out = Src{};
    if (o.is_imm) {
        out->is_imm = true;
        out->imm = o.imm;
        return true;
    }
    if (o.guest_reg < 0) return refuse("operand2 has no decoded register");

    int base;
    if (o.guest_reg == 15) {
        // Register-operand PC read: ALWAYS unaligned (insn+4 Thumb / insn+8
        // ARM). Align(PC,4) is only for instructions using the PC as an
        // address BASE -- LDR literal, ADR, ADD Rd,PC,#imm -- DDI 0406C.d A2.3.
        // Verified against Unicorn; see the same fix in do_alu. This helper is
        // the shared operand2 lowering, so getting it wrong mistranslated the
        // PC operand of EVERY data-processing instruction that reaches here.
        base = materialise_tmp(pc_value_for(addr_, false), owned);
    } else {
        base = regs_.read(o.guest_reg);
    }

    if (!o.has_shift) {
        out->reg = base;
        return true;
    }

    // RRX: value = (C << 31) | (val >> 1). `cset` READS PSTATE, so this
    // must run before anything in this instruction clobbers it, which is
    // why operand lowering always precedes the flag work.
    if (o.rrx) {
        int t = regs_.temp_alloc();
        owned->push_back(t);
        int c = regs_.temp_alloc();
        owned->push_back(c);
        sink_.cset(c, Cond::CS, false);
        sink_.shift_imm(Sh::LSR, t, base, 1, false);
        sink_.alu_reg(AluOp::ORR, t, t, c, Sh::LSL, 31, false, false);
        out->reg = t;
        return true;
    }

    if (!o.shift_by_reg) {
        const int n = o.sh_amt;
        // A64's shifted-register operand covers LSL/LSR/ASR #0..31 for
        // every ALU form and ROR only for the logical forms
        // (DDI 0487 C6.2.4 vs C6.2.11).
        const bool foldable_kind =
            (o.sh != Sh::ROR) || logical_ctx;
        if (allow_fold && foldable_kind && n >= 1 && n <= 31) {
            out->reg = base;
            out->sh = o.sh;
            out->amt = n;
            out->shifted = true;
            return true;
        }
        // A32 encodes LSR/ASR #32 as an amount of 0; capstone reports 32.
        // A64 cannot express it as an operand modifier, so fold the
        // architectural result instead.
        if (n >= 32 && o.sh == Sh::LSR) {
            out->is_imm = true;
            out->imm = 0;
            return true;
        }
        int t = regs_.temp_alloc();
        owned->push_back(t);
        if (n >= 32 && o.sh == Sh::ASR) {
            sink_.shift_imm(Sh::ASR, t, base, 31, false);
        } else if (o.sh == Sh::ROR) {
            // ROR #n is EXTR Wd, Wn, Wn, #n.
            sink_.extr(t, base, base, n & 31, false);
        } else {
            sink_.shift_imm(o.sh, t, base, n & 31, false);
        }
        out->reg = t;
        return true;
    }

    // Register-controlled shift. Trap T2: A32 uses R[s]<7:0> and
    // saturates (A8.4.2 "Register controlled shifts": "the bottom byte of
    // Rs contains the shift amount"; A8.4.3 Shift_C), A64's
    // LSLV/LSRV/ASRV/RORV (DDI 0487 C6.2.180 and siblings) take the
    // amount MOD 32. Every fixup below is branch-free and, critically,
    // sets no flags -- guest NZCV lives in PSTATE (assumption A3) and a
    // non-S shift must not disturb it.
    int amtreg = regs_.read(o.sh_guest_reg);
    int t = regs_.temp_alloc();
    owned->push_back(t);

    if (o.sh == Sh::ROR) {
        // (Rs & 0xff) MOD 32 == Rs MOD 32, so RORV needs no fixup at all.
        sink_.shift_reg(Sh::ROR, t, base, amtreg, false);
        out->reg = t;
        return true;
    }

    // Transient scratch is freed as soon as it dies so the concurrent
    // scratch demand stays inside the budget documented in xlat_core.h.
    int a = regs_.temp_alloc();
    sink_.alu_imm(AluOp::AND, a, amtreg, 0xffull, false, false);

    int k = regs_.temp_alloc();
    // k = (a - 32) >> 31 arithmetic  ->  all-ones when a < 32, else 0.
    sink_.alu_imm(AluOp::SUB, k, a, 32, false, false);
    sink_.shift_imm(Sh::ASR, k, k, 31, false);

    if (o.sh == Sh::ASR) {
        // A32 ASR by >= 32 gives the sign bit replicated. Force the A64
        // amount to 0xffffffff in that case: ASRV's MOD-32 masking turns
        // that into 31, which IS the architectural answer.
        int a2 = regs_.temp_alloc();
        sink_.alu_reg(AluOp::ORN, a2, a, k, Sh::LSL, 0, false, false);
        sink_.shift_reg(Sh::ASR, t, base, a2, false);
        regs_.temp_free(a2);
    } else {
        // LSL / LSR by >= 32 give 0. Compute the masked shift, then AND
        // with the "amount was in range" mask.
        sink_.shift_reg(o.sh, t, base, a, false);
        sink_.alu_reg(AluOp::AND, t, t, k, Sh::LSL, 0, false, false);
    }
    regs_.temp_free(k);
    regs_.temp_free(a);
    out->reg = t;
    return true;
}

int XlatCore::force_reg(const Src& s, std::vector<int>* owned) {
    if (!s.is_imm) {
        if (!s.shifted) return s.reg;
        int t = regs_.temp_alloc();
        owned->push_back(t);
        if (s.sh == Sh::ROR) sink_.extr(t, s.reg, s.reg, s.amt, false);
        else sink_.shift_imm(s.sh, t, s.reg, s.amt, false);
        return t;
    }
    if (s.imm == 0) return kZR;
    return materialise_tmp(s.imm, owned);
}

// ---------------------------------------------------------------------
//  Shifter carry-out (A8.4.3 Shift_C over the A2.2.1 primitives)
// ---------------------------------------------------------------------
//
//   LSL #n : carry = value<32-n>   (n == 32 -> value<0>; n > 32 -> 0)
//   LSR #n : carry = value<n-1>    (n == 32 -> value<31>; n > 32 -> 0)
//   ASR #n : carry = value<n-1>    (n >= 32 -> value<31>)
//   ROR #n : carry = result<31> = value<(n-1) MOD 32>
//   RRX    : carry = value<0>
// Register-controlled forms use shift_n = R[s]<7:0> and leave C
// UNCHANGED when that is zero.
//
// NOTE vs arm32_lifter.cpp: its register-amount LSR path returned 0 for
// an amount of exactly 32, but LSR_C's carry_out is extended_x<shift-1>,
// which for shift == 32 is value<31>. Fixed here.
bool XlatCore::shifter_carry(const Op2& o, int src_reg, CarrySpec* spec,
                             std::vector<int>* owned) {
    *spec = CarrySpec::preserve();
    if (o.is_imm) {
        if (o.imm_carry_known) *spec = CarrySpec::constant(o.imm_carry);
        return true;
    }
    if (!o.has_shift) return true;

    if (o.rrx) {
        int c = regs_.temp_alloc();
        owned->push_back(c);
        sink_.ubfx(c, src_reg, 0, 1, false);
        *spec = CarrySpec::from_reg(c);
        return true;
    }

    if (!o.shift_by_reg) {
        const int n = o.sh_amt;
        if (n <= 0) return true;              // no carry change
        if (o.sh == Sh::LSL && n > 32) { *spec = CarrySpec::constant(false); return true; }
        if (o.sh == Sh::LSR && n > 32) { *spec = CarrySpec::constant(false); return true; }
        int bit;
        if (o.sh == Sh::LSL) bit = (n >= 32) ? 0 : (32 - n);
        else if (o.sh == Sh::ASR) bit = (n >= 32) ? 31 : (n - 1);
        else bit = (n - 1) & 31;              // LSR (n <= 32) and ROR
        int c = regs_.temp_alloc();
        owned->push_back(c);
        sink_.ubfx(c, src_reg, bit & 31, 1, false);
        *spec = CarrySpec::from_reg(c);
        return true;
    }

    // Register-controlled amount. The caller has already snapshotted
    // PSTATE (flags_save) and captured the OLD C, so CMP/CSEL are legal
    // here even though they clobber flags.
    int amtreg = regs_.read(o.sh_guest_reg);
    int c = regs_.temp_alloc();
    owned->push_back(c);                 // survives, holds the carry
    int oldc = regs_.temp_alloc();
    int a = regs_.temp_alloc();
    int pos = regs_.temp_alloc();

    sink_.cset(oldc, Cond::CS, false);   // must precede any flag clobber
    sink_.alu_imm(AluOp::AND, a, amtreg, 0xffull, false, false);

    if (o.sh == Sh::LSL) {
        // pos = 32 - a, valid for a in [1,32]; a > 32 handled by the
        // range mask below.
        sink_.movz(pos, 32, 0, false);
        sink_.alu_reg(AluOp::SUB, pos, pos, a, Sh::LSL, 0, false, false);
    } else {
        // LSR / ASR / ROR: pos = a - 1.
        sink_.alu_imm(AluOp::SUB, pos, a, 1, false, false);
    }
    if (o.sh == Sh::ASR) {
        // ASR saturates at bit 31 for any amount >= 32; LSRV's MOD-32
        // masking turns 0xffffffff into 31, so reuse the ORN trick.
        int k = regs_.temp_alloc();
        sink_.alu_imm(AluOp::SUB, k, a, 32, false, false);
        sink_.shift_imm(Sh::ASR, k, k, 31, false);
        sink_.alu_reg(AluOp::ORN, pos, pos, k, Sh::LSL, 0, false, false);
        regs_.temp_free(k);
    }
    sink_.shift_reg(Sh::LSR, c, src_reg, pos, false);
    sink_.alu_imm(AluOp::AND, c, c, 1, false, false);
    regs_.temp_free(pos);

    if (o.sh == Sh::LSL || o.sh == Sh::LSR) {
        // Amount > 32 shifts every bit out: carry is 0.
        sink_.alu_imm(AluOp::SUB, kZR, a, 32, true, false);   // cmp a, #32
        sink_.csel(CselOp::CSEL, c, c, kZR, Cond::LS, false);
    }
    // Amount == 0 leaves C untouched.
    sink_.alu_imm(AluOp::SUB, kZR, a, 0, true, false);        // cmp a, #0
    sink_.csel(CselOp::CSEL, c, oldc, c, Cond::EQ, false);
    regs_.temp_free(a);
    regs_.temp_free(oldc);

    *spec = CarrySpec::from_reg(c);
    return true;
}

// ---------------------------------------------------------------------
//  Flags
// ---------------------------------------------------------------------

bool XlatCore::cv_dead_here() const {
    return hooks_.cv_dead_after && hooks_.cv_dead_after(addr_);
}

int XlatCore::flags_save(std::vector<int>* owned) {
    int t = regs_.temp_alloc();
    owned->push_back(t);
    sink_.mrs_nzcv(t);
    return t;
}

// A32's logical / MOV / MVN / MUL family writes N and Z from the result,
// writes C only from the shifter carry-out, and leaves V ALONE
// (DDI 0406C.d A8.8.14 AND, A8.8.124 ORR, A8.8.48 EOR, A8.8.22 BIC,
// A8.8.117 MVN, A8.8.242 TST, A8.8.239 TEQ). A64 has no instruction with those
// effects: ANDS forces C and V to zero (DDI 0487 C6.2.13). So the flags
// are rebuilt by hand:
//
//    mrs  xOld, nzcv          (snapshot, unless the caller took one)
//    ands wzr, wRes, wRes     N,Z from result; C,V := 0
//    mrs  xNew, nzcv
//    and  xOld, xOld, #keep   keep V, and C when C is not being written
//    orr  xNew, xNew, xOld
//    [orr xNew, xNew, xC lsl #29]   when C comes from the shifter
//    msr  nzcv, xNew
void XlatCore::flags_commit_nz(int res, bool sf64, const CarrySpec& spec,
                               int saved, std::vector<int>* owned) {
    if (spec.kind == CarrySpec::Kind::Preserve && saved < 0 &&
        cv_dead_here()) {
        // A CFG-aware caller has proven C and V are dead before their
        // next read, so the single-instruction form is legal.
        sink_.alu_reg(AluOp::AND, kZR, res, res, Sh::LSL, 0, true, sf64);
        return;
    }

    int old = saved;
    if (old < 0) old = flags_save(owned);

    int nw = regs_.temp_alloc();
    owned->push_back(nw);

    sink_.alu_reg(AluOp::AND, kZR, res, res, Sh::LSL, 0, true, sf64);
    sink_.mrs_nzcv(nw);

    // Bit 31 N, bit 30 Z, bit 29 C, bit 28 V.
    const uint64_t keep = (spec.kind == CarrySpec::Kind::Preserve)
                              ? 0x30000000ull   // C and V
                              : 0x10000000ull;  // V only
    sink_.alu_imm(AluOp::AND, old, old, keep, false, true);
    sink_.alu_reg(AluOp::ORR, nw, nw, old, Sh::LSL, 0, false, true);

    if (spec.kind == CarrySpec::Kind::FromReg) {
        sink_.alu_reg(AluOp::ORR, nw, nw, spec.reg, Sh::LSL, 29, false, true);
    } else if (spec.kind == CarrySpec::Kind::Const && spec.value) {
        sink_.alu_imm(AluOp::ORR, nw, nw, 0x20000000ull, false, true);
    }
    sink_.msr_nzcv(nw);
}

// ---------------------------------------------------------------------
//  Predication (trap T3)
// ---------------------------------------------------------------------

XlatCore::PredScope XlatCore::pred_begin(Cond c) {
    PredScope s;
    if (c == Cond::AL || c == Cond::NV) return s;
    s.skip = sink_.new_label();
    s.active = true;
    sink_.b_cond(invert(c), s.skip);
    return s;
}

void XlatCore::pred_end(PredScope& s) {
    if (!s.active) return;
    sink_.bind(s.skip);
    s.active = false;
}

// ---------------------------------------------------------------------
//  ALU emit helper
// ---------------------------------------------------------------------

void XlatCore::emit_alu_generic(AluOp op, int rd, int rn, const Src& s,
                                bool S, std::vector<int>* owned) {
    if (!s.is_imm) {
        if (op == AluOp::ADC || op == AluOp::SBC) {
            // A64 has no shifted ADC/SBC form.
            int r = force_reg(s, owned);
            sink_.alu_reg(op, rd, rn, r, Sh::LSL, 0, S, false);
            return;
        }
        sink_.alu_reg(op, rd, rn, s.reg, s.sh, s.amt, S, false);
        return;
    }

    const uint32_t v = s.imm;
    switch (op) {
        case AluOp::ADD:
        case AluOp::SUB: {
            if (a64_encodable_add_imm(v)) {
                sink_.alu_imm(op, rd, rn, v, S, false);
                return;
            }
            // ADD #-x and SUB #x are flag-identical on A32 as well as
            // A64: the unsigned carry of rn + (2^32 - x) is exactly
            // "rn >= x", which is SUB's NOT-borrow, and the signed
            // overflow of rn + (-x) is that of rn - x.
            const uint32_t neg = static_cast<uint32_t>(-static_cast<int32_t>(v));
            if (a64_encodable_add_imm(neg)) {
                sink_.alu_imm(op == AluOp::ADD ? AluOp::SUB : AluOp::ADD,
                              rd, rn, neg, S, false);
                return;
            }
            break;
        }
        case AluOp::AND:
        case AluOp::ORR:
        case AluOp::EOR:
            if (v == 0 && op != AluOp::AND && !S) {
                sink_.mov_reg(rd, rn, false);
                return;
            }
            if (a64_is_logical_imm(v, 32)) {
                sink_.alu_imm(op, rd, rn, v, S, false);
                return;
            }
            break;
        case AluOp::BIC:
            // BIC #imm is AND #~imm; the complement of a bitmask
            // immediate is usually also encodable.
            if (a64_is_logical_imm(~v, 32)) {
                sink_.alu_imm(AluOp::AND, rd, rn, ~v & 0xffffffffu, S, false);
                return;
            }
            break;
        case AluOp::ORN:
            if (a64_is_logical_imm(~v, 32)) {
                sink_.alu_imm(AluOp::ORR, rd, rn, ~v & 0xffffffffu, S, false);
                return;
            }
            break;
        default:
            break;
    }
    int t = materialise_tmp(v, owned);
    sink_.alu_reg(op, rd, rn, t, Sh::LSL, 0, S, false);
}

void XlatCore::emit_mov_src(int rd, const Src& s, std::vector<int>* owned) {
    if (s.is_imm) { materialise_u32(rd, s.imm); return; }
    if (!s.shifted) { sink_.mov_reg(rd, s.reg, false); return; }
    if (s.sh == Sh::ROR) sink_.extr(rd, s.reg, s.reg, s.amt, false);
    else sink_.shift_imm(s.sh, rd, s.reg, s.amt, false);
    (void)owned;
}

// ---------------------------------------------------------------------
//  The `add rN, pc` PC-convention discrimination (carried over verbatim
//  from arm32_lifter.cpp emit_alu, v1.0.521)
// ---------------------------------------------------------------------
//
// The GCC Thumb PIC pair `ldr rN, [pc, #imm]; add rN, pc` computes the
// address of a GOT slot or a global data base. The ARM ARM says the add
// reads R15 UNALIGNED (insn+4), but the aligned Align(insn+4,4) is what
// GD Lite's cocos2d-x needs while HCR's libxml2 needs the unaligned one:
// the SAME instruction with opposite conventions from different NDK
// eras. v1.0.520 proved that neither a blanket nor a mnemonic-scoped
// rule fits both (both regressed GD Lite to BLANK). So discriminate per
// SEQUENCE: recover the literal L, and override to unaligned only when
// that makes the result a 4-byte-aligned address inside one of this
// image's pointer/struct sections. Safe by construction for GD Lite,
// whose correct (aligned) result already IS that 4-aligned target, so
// the unaligned candidate is 2-mod-4 and can never be chosen.
bool XlatCore::find_pc_literal_for(unsigned cs_reg, uint32_t* out) const {
    if (!insns_ || idx_ == 0 || !hooks_.read_image_u32) return false;
    const int window = opts_.pc_literal_scan_window;
    for (int back = 1; back <= window &&
                       static_cast<size_t>(back) <= idx_; ++back) {
        const cs_insn& p = insns_[idx_ - static_cast<size_t>(back)];
        if (!p.detail) continue;
        // Never pair across control flow: a different predecessor could
        // have set rN.
        if (p.id == ARM_INS_B   || p.id == ARM_INS_BX  ||
            p.id == ARM_INS_BL  || p.id == ARM_INS_BLX ||
            p.id == ARM_INS_CBZ || p.id == ARM_INS_CBNZ) return false;
        const cs_arm& pd = p.detail->arm;
        if (p.id == ARM_INS_LDR && pd.op_count == 2 &&
            pd.operands[0].type == ARM_OP_REG &&
            static_cast<unsigned>(pd.operands[0].reg) == cs_reg &&
            pd.operands[1].type == ARM_OP_MEM &&
            pd.operands[1].mem.base == ARM_REG_PC &&
            pd.operands[1].mem.index == ARM_REG_INVALID) {
            uint32_t pc_base = (static_cast<uint32_t>(p.address) +
                                (opts_.thumb ? 4u : 8u)) & ~3u;
            uint32_t lit = pc_base + static_cast<uint32_t>(
                static_cast<int32_t>(pd.operands[1].mem.disp));
            return hooks_.read_image_u32(lit, out);
        }
        // Any other definition of rN breaks the pair.
        if (pd.op_count >= 1 && pd.operands[0].type == ARM_OP_REG &&
            static_cast<unsigned>(pd.operands[0].reg) == cs_reg) return false;
    }
    return false;
}

bool XlatCore::pc_add_wants_unaligned(const cs_arm& d, int rn_idx,
                                      int src_idx) const {
    if (!hooks_.in_pointer_target_range) return false;
    if (d.operands[src_idx].type != ARM_OP_REG) return false;
    if (d.operands[src_idx].reg != ARM_REG_PC) return false;
    if (d.operands[src_idx].shift.type != ARM_SFT_INVALID) return false;
    uint32_t pc_un = addr_ + (opts_.thumb ? 4u : 8u);
    uint32_t pc_al = pc_un & ~3u;
    if (pc_un == pc_al) return false;      // only ambiguous when 2-aligned
    uint32_t lit = 0;
    if (!find_pc_literal_for(d.operands[rn_idx].reg, &lit)) return false;
    uint32_t r_un = lit + pc_un;
    return (r_un & 3u) == 0 && hooks_.in_pointer_target_range(r_un);
}

// ---------------------------------------------------------------------
//  MOV / MVN
// ---------------------------------------------------------------------

bool XlatCore::do_mov(const cs_insn* in, bool is_mvn) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 2 || d.op_count > 3) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;

    const cs_arm_op* rot = nullptr;
    if (d.op_count == 3) {
        if (d.operands[1].type != ARM_OP_IMM ||
            d.operands[2].type != ARM_OP_IMM) return false;
        rot = &d.operands[2];
    }

    Op2 o;
    if (!decode_op2(d.operands[1], rot, &o)) return false;

    // Verified carve-out: A8.8.105 MOV (register, ARM) leaves BOTH C and
    // V unchanged, because that encoding has no shifter at all. Only
    // A8.8.103 MOV (immediate) writes C, and it writes the
    // ARMExpandImm_C carry-out. A shifted MOVS is encoded as a separate
    // LSL/LSR/ASR/ROR/RRX instruction and so lands in do_shift_insn.
    // All three fall out correctly below, because shifter_carry()
    // reports Preserve for an unshifted register operand.
    std::vector<int> owned;
    // Capture the pre-shift source: MVNS takes its carry-out from the
    // operand BEFORE the bitwise NOT (A8.8.117 MVN), and the shifter carry
    // rules are defined on the unshifted value.
    int pre = kZR;
    if (!o.is_imm) {
        // `mov rN, pc` is a common idiom, and a register-operand PC read is
        // never Align(PC,4) (DDI 0406C.d A2.3). Same fix as lower_op2/do_alu.
        pre = (o.guest_reg == 15)
                  ? materialise_tmp(pc_value_for(addr_, false), &owned)
                  : regs_.read(o.guest_reg);
    }

    Src s;
    if (!lower_op2(o, /*allow_fold=*/false, /*logical_ctx=*/true, &s, &owned)) {
        release(owned);
        return false;
    }

    if (rd == 15) {
        // MOV PC, Rm is the pre-ARMv5 return / computed-jump idiom.
        int t = force_reg(s, &owned);
        if (!o.is_imm && o.guest_reg == 14 && !o.has_shift) {
            sink_.ret_from_function();
        } else {
            sink_.tail_branch_indirect(t);
        }
        release(owned);
        return true;
    }

    int saved = -1;
    CarrySpec cspec = CarrySpec::preserve();
    if (d.update_flags) {
        saved = flags_save(&owned);
        shifter_carry(o, pre, &cspec, &owned);
    }

    int dst = regs_.write_begin(rd);
    if (is_mvn) {
        if (s.is_imm) {
            materialise_u32(dst, ~s.imm);
        } else {
            // MVN is ORN Wd, WZR, Wm{, shift}.
            sink_.alu_reg(AluOp::ORN, dst, kZR, s.reg, s.sh, s.amt, false,
                          false);
        }
    } else {
        emit_mov_src(dst, s, &owned);
    }
    // Flags are committed while `dst` is still live: in a memory-backed
    // register model write_end() may spill and invalidate it, and
    // re-reading the guest register would emit a pointless reload.
    if (d.update_flags) flags_commit_nz(dst, false, cspec, saved, &owned);
    regs_.write_end(rd);

    release(owned);
    return true;
}

bool XlatCore::do_movw_movt(const cs_insn* in, bool is_movt) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_IMM) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;
    uint16_t imm = static_cast<uint16_t>(d.operands[1].imm & 0xffff);
    int dst = regs_.write_begin(rd);
    if (is_movt) {
        // MOVT writes bits 31:16 and PRESERVES 15:0, which is
        // exactly MOVK.
        sink_.movk(dst, imm, 16, false);
    } else {
        // MOVW zero-extends its 16-bit immediate, which is exactly MOVZ.
        sink_.movz(dst, imm, 0, false);
    }
    regs_.write_end(rd);
    return true;
}

// ---------------------------------------------------------------------
//  The main data-processing family
// ---------------------------------------------------------------------

bool XlatCore::do_alu(const cs_insn* in, AluOp op, bool flags_only) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 2 || d.op_count > 4)
        return refuse("op_count outside 2..4");

    int rd = -1, rn_idx = 0, src_idx = 1, rot_idx = -1;
    if (flags_only) {
        // CMP/CMN/TST/TEQ: (Rn, src) or (Rn, #imm8, #rot).
        if (d.op_count == 2) { rn_idx = 0; src_idx = 1; }
        else if (d.op_count == 3 && d.operands[1].type == ARM_OP_IMM &&
                 d.operands[2].type == ARM_OP_IMM) {
            rn_idx = 0; src_idx = 1; rot_idx = 2;
        } else return refuse("flag-only form with an unrecognised 3-operand "
                             "shape");
    } else {
        if (d.operands[0].type != ARM_OP_REG)
            return refuse("Rd operand is not a register");
        rd = guest_reg_of(d.operands[0].reg);
        if (rd < 0) return refuse("Rd is not a core register r0..r15");
        if (d.op_count == 2) { rn_idx = 0; src_idx = 1; }
        else if (d.op_count == 3) { rn_idx = 1; src_idx = 2; }
        else {
            if (d.operands[2].type != ARM_OP_IMM ||
                d.operands[3].type != ARM_OP_IMM)
                return refuse("4-operand form is not (#imm8, #rot)");
            rn_idx = 1; src_idx = 2; rot_idx = 3;
        }
    }
    if (d.operands[rn_idx].type != ARM_OP_REG)
        return refuse("Rn operand is not a register");
    int rn = guest_reg_of(d.operands[rn_idx].reg);
    if (rn < 0) return refuse("Rn is not a core register r0..r15");

    Op2 o;
    if (!decode_op2(d.operands[src_idx],
                    rot_idx >= 0 ? &d.operands[rot_idx] : nullptr, &o))
        return false;   // decode_op2 records its own reason

    std::vector<int> owned;

    int lhs;
    if (rn == 15) {
        // Rn == PC. `ADD Rd, PC, #imm` IS the ADR form and genuinely reads
        // Align(PC,4) (DDI 0406C.d A2.3 lists "any of ADD/SUB that specifies
        // the PC as the base for a PC-relative address"). With a REGISTER
        // operand2 it is an ordinary register read and must stay unaligned.
        // In ARM state the two coincide (insn+8 is already 4-aligned), so this
        // only bites in Thumb.
        lhs = materialise_tmp(pc_value_for(addr_, o.is_imm), &owned);
    } else {
        lhs = regs_.read(rn);
    }

    // v1.0.521 discrimination: only for a plain `add rD, rN, pc`.
    // (The v1.0.521 pc_add_wants_unaligned() heuristic used to run here. It is
    // now unconditional in the o.guest_reg == 15 branch below, because the
    // architecture leaves nothing to guess: a register-operand PC read is
    // never aligned. See the comment there.)

    int pre = kZR;
    if (!o.is_imm) {
        if (o.guest_reg == 15) {
            // A PC read as a REGISTER OPERAND is ALWAYS unaligned: the ALU
            // reads R[15] = insn+4 (Thumb) / insn+8 (ARM). Align(PC,4) applies
            // only to instructions that use the PC as an address BASE -- LDR
            // (literal), ADR, and `ADD Rd, PC, #imm` -- per DDI 0406C.d A2.3
            // "Reading the PC". `ADD Rdn, Rm` (T2) is not one of them.
            //
            // VERIFIED against Unicorn, not just read off the manual: `add r1,
            // pc` placed at a 2-aligned Thumb address returns insn+4, not
            // Align(PC,4).
            //
            // This used to be gated on pc_add_wants_unaligned(), a heuristic
            // that only chose the unaligned value when it could find the paired
            // literal AND the result landed in a "pointer target range". When
            // the heuristic missed, the address came out TWO BYTES LOW, which
            // is silent and arbitrarily damaging because it is the standard GCC
            // Thumb PIC idiom (`ldr rN,[pc,#k]` + `add rN,pc`, where the linker
            // computes the literal as `target - (.LPIC + 4)`, unaligned).
            //
            // Measured on HCR: it turned the fopen mode literal "r" (0x2b6668)
            // into "bCr" (0x2b6666, two bytes into the tail-merged "YCbCr"),
            // and "file://localhost/" into "". There is no ambiguity to
            // resolve here, so the heuristic is gone.
            pre = materialise_tmp(pc_value_for(addr_, false), &owned);
        } else {
            pre = regs_.read(o.guest_reg);
        }
    }

    Src s;
    const bool logical = !alu_is_arith(op);
    if (o.guest_reg == 15) {
        // PC was already materialised into `pre`; reuse it so the
        // discrimination above is not thrown away.
        s = Src{};
        s.reg = pre;
        if (o.has_shift) {
            // Generic lowering cannot be reused here because the base is
            // a materialised constant rather than a guest register, so
            // apply the shift explicitly.
            int t = regs_.temp_alloc();
            owned.push_back(t);
            if (o.rrx) {
                int c = regs_.temp_alloc();
                owned.push_back(c);
                sink_.cset(c, Cond::CS, false);
                sink_.shift_imm(Sh::LSR, t, pre, 1, false);
                sink_.alu_reg(AluOp::ORR, t, t, c, Sh::LSL, 31, false, false);
            } else if (o.shift_by_reg) {
                release(owned);
                // `pc, lsl rN` is not a real encoding: A32 register-shifted
                // register (DDI 0406C.d A5.2.2) makes Rm == PC and Rs == PC
                // UNPREDICTABLE, so there is no defined behaviour to emit.
                return refuse("register-shifted PC operand (UNPREDICTABLE, "
                              "A5.2.2)");
            } else if (o.sh == Sh::ROR) {
                sink_.extr(t, pre, pre, o.sh_amt & 31, false);
            } else {
                sink_.shift_imm(o.sh, t, pre, o.sh_amt & 31, false);
            }
            s.reg = t;
        }
    } else if (!lower_op2(o, /*allow_fold=*/true, logical, &s, &owned)) {
        release(owned);
        return false;
    }

    // --- flag-only forms ---------------------------------------------
    if (flags_only) {
        if (op == AluOp::SUB || op == AluOp::ADD) {
            // CMP / CMN: A64's SUBS/ADDS reproduce all four A32 flags
            // exactly (C is NOT-borrow in both architectures).
            emit_alu_generic(op, kZR, lhs, s, true, &owned);
        } else {
            // TST / TEQ: N,Z from the result, C from the shifter,
            // V untouched. Trap T1 forbids A64's ANDS here.
            int saved = flags_save(&owned);
            CarrySpec cspec = CarrySpec::preserve();
            shifter_carry(o, pre, &cspec, &owned);
            int t = regs_.temp_alloc();
            owned.push_back(t);
            emit_alu_generic(op, t, lhs, s, false, &owned);
            flags_commit_nz(t, false, cspec, saved, &owned);
        }
        release(owned);
        return true;
    }

    static const bool jt_trace = std::getenv("MACAPK_JT_TRACE") != nullptr;
    if (rd == 15 && jt_trace)
        std::fprintf(stderr, "[jt] rd==15 at 0x%x rn=%d op=%d thumb=%d isimm=%d "
                     "greg=%d shbyreg=%d hooks=%d\n", addr_, rn, (int)op,
                     (int)opts_.thumb, (int)o.is_imm, o.guest_reg,
                     (int)o.shift_by_reg,
                     (int)(!!hooks_.label_for_guest && !!hooks_.read_image_u32));
    if (rd == 15 && rn == 15 && op == AluOp::ADD && !opts_.thumb &&
        !o.is_imm && o.guest_reg >= 0 && !o.shift_by_reg &&
        hooks_.label_for_guest && hooks_.read_image_u32) {
        // ARM INLINE JUMP TABLE: `add pc, pc, rN, lsl #k`.
        //
        // The architectural target is pc_value + (rN << k), and pc_value is
        // insn+8 in ARM state -- i.e. the run of unconditional B slots
        // immediately following this instruction. The index is exactly rN,
        // so slot i lives at base + (i << k).
        //
        // Before this, the generic ALU-writes-PC path below routed it to
        // tail_branch_indirect -> __macapk_indirect_call, which resolves via
        // the FUNCTION LUT. These targets are MID-FUNCTION, never function
        // starts, so every lookup missed and panicked: 27 distinct sites in
        // HCR 1.8.1's libgame (cocos2d CCDictionary::objectForKey at
        // 0x12a73c fired 145 times) and the reason that app renders nothing
        // on this pipeline.
        //
        // The driver's pre-scan mints a label per slot; dispatch over them
        // with a compare chain. Tables are small (11 slots in the HCR case)
        // and the guest range-checks the index itself (the real instruction
        // is predicated, e.g. `addls`), so the chain is short and its
        // fall-through is unreachable in practice -- but it still ends in the
        // generic indirect path rather than running on into the table bytes.
        const unsigned shift_amt =
            (o.has_shift && o.sh == Sh::LSL && !o.rrx)
                ? static_cast<unsigned>(o.sh_amt & 31) : 0u;
        // UNBIASED: every use of `base` below is a static image lookup
        // (hooks_.read_image_u32 / hooks_.label_for_guest, both keyed by
        // the raw per-image vaddr), never a runtime-materialised pointer
        // -- see pc_value_for_lookup's header comment (v1.0.818).
        const uint32_t base = pc_value_for_lookup(addr_, false);
        std::vector<Label> slots;
        for (uint32_t i = 0; i < 512u; ++i) {
            const uint32_t sa = base + (i << shift_amt);
            uint32_t word = 0;
            Label l{};
            if (!hooks_.read_image_u32(sa, &word)) break;
            if ((word >> 24) != 0xEAu) break;        // ARM unconditional B
            if (!hooks_.label_for_guest(sa, &l)) break;
            slots.push_back(l);
        }
        if (jt_trace)
            std::fprintf(stderr, "[jt] xlat at 0x%x: base=0x%x sh=%u slots=%zu\n",
                         addr_, base, shift_amt, slots.size());
        if (!slots.empty()) {
            const int idx = regs_.read(o.guest_reg);
            for (size_t i = 0; i < slots.size(); ++i) {
                sink_.alu_imm(AluOp::SUB, kZR, idx,
                              static_cast<uint64_t>(i), true, false);
                sink_.b_cond(Cond::EQ, slots[i]);
            }
            char jb[128];
            std::snprintf(jb, sizeof(jb),
                          "inline jump table at 0x%x: %zu slots", addr_,
                          slots.size());
            warn(jb);
            release(owned);
            return true;
        }
        // Table did not decode as a run of literal B opcodes -- it may
        // still be a DIRECT jump into ladder CODE (this function's own
        // div-by-shift-and-subtract idiom, verbatim, with no b-table at
        // all -- see do_bx's "ARM PC-RELATIVE LADDER DISPATCH" below, the
        // confirmed instance of this general class). Reuse the identical
        // evidence-based affine-candidate check: a slot only counts when
        // it is a real, still-unvisited instruction boundary in THIS
        // function (addr_index_, gated on > idx_). Deliberately does NOT
        // return here even when rungs are found: the rd==15 fallback
        // just below recomputes this exact value and emits the
        // architecturally correct indirect branch, which is the honest
        // safety net for any runtime value the enumeration did not cover.
        const uint32_t ladder_rungs = emit_affine_ladder_dispatch(
            o.guest_reg, base, static_cast<int32_t>(1u << shift_amt), 512u);
        if (ladder_rungs > 0) {
            char lb[160];
            std::snprintf(lb, sizeof(lb),
                          "affine ladder dispatch (non-branch-table) at "
                          "0x%x: base=0x%x coeff=%d rungs=%u", addr_, base,
                          static_cast<int>(1u << shift_amt), ladder_rungs);
            warn(lb);
        }
        // Table did not decode: fall through to the generic path, which
        // still warns and emits an architecturally correct indirect branch.
    }

    if (rd == 15) {
        // An ALU write to R15 is architecturally a branch. The LLVM
        // lifter lowered it as a dead store plus a warning because the
        // CFG layer intercepts every KNOWN computed-jump idiom upstream;
        // an unrecognised one silently fell through, which is how the
        // v552 gdx2d wrong-switch-case bug stayed invisible. Falling
        // through is never right, so emit the architecturally correct
        // indirect branch and keep the warning.
        char buf[160];
        std::snprintf(buf, sizeof(buf),
                      "ALU writes PC at 0x%x (unrecognised computed-jump "
                      "idiom?)", addr_);
        warn(buf);
        if (opts_.alu_pc_write_is_branch) {
            int t = regs_.temp_alloc();
            owned.push_back(t);
            emit_alu_generic(op, t, lhs, s, false, &owned);

            // "add pc, rBASE, rOFF" via an intermediate-register PC table
            // (v1.0.812) -- see match_pc_table_ref's header comment and
            // docs/arm32rc-computed-intrafunction-jump_2026-07-28.md.
            // `t` already holds rBASE+rOFF (the architectural target),
            // computed by the SAME emit_alu_generic call every other
            // path through here relies on, so comparing it against a
            // table-read candidate is exactly as safe as the existing
            // b-table dispatch above -- purely additive: a failed match,
            // or zero entries resolved, leaves tail_branch_indirect(t)
            // immediately below byte-for-byte unchanged.
            if (!d.update_flags && op == AluOp::ADD && !opts_.thumb &&
                rn >= 0 && rn != 15 && !o.is_imm && o.guest_reg >= 0 &&
                !o.shift_by_reg && !o.has_shift && hooks_.read_image_u32) {
                uint32_t table_base = 0;
                if (match_pc_table_ref(idx_, rn, o.guest_reg, &table_base)) {
                    const uint32_t entries =
                        emit_table_offset_dispatch(t, table_base, 512u);
                    if (entries > 0) {
                        char lb[192];
                        std::snprintf(lb, sizeof(lb),
                                      "pc table-offset dispatch at 0x%x: "
                                      "table_base=0x%x entries=%u",
                                      addr_, table_base, entries);
                        warn(lb);
                    }
                }
            }

            sink_.tail_branch_indirect(t);
        }
        release(owned);
        return true;
    }

    if (alu_is_arith(op)) {
        // ADD/SUB/ADC/SBC: A64's S-forms give bit-identical NZCV.
        int dst = regs_.write_begin(rd);
        emit_alu_generic(op, dst, lhs, s, d.update_flags, &owned);
        regs_.write_end(rd);
        release(owned);
        return true;
    }

    // Logical family. Compute with the non-flag-setting form, then
    // rebuild N/Z/C by hand (trap T1).
    int saved = -1;
    CarrySpec cspec = CarrySpec::preserve();
    if (d.update_flags) {
        saved = flags_save(&owned);
        shifter_carry(o, pre, &cspec, &owned);
    }
    int dst = regs_.write_begin(rd);
    emit_alu_generic(op, dst, lhs, s, false, &owned);
    if (d.update_flags) flags_commit_nz(dst, false, cspec, saved, &owned);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// RSB Rd, Rn, op2 = op2 - Rn
// RSC Rd, Rn, op2 = AddWithCarry(NOT(R[n]), op2, C) = op2 - Rn - NOT(C)
// (A8.8.157 RSC (register)). Both take C from AddWithCarry, NOT from the
// barrel shifter: RSB/RSC/ADC/SBC call Shift, not Shift_C, so the shifter
// carry-out is discarded.
//
// The LLVM lifter originally aliased RSC to SBC, which computes
// Rn - op2 instead: a sign flip that corrupted every compiler-emitted
// 64-bit negate/abs (`rsbs lo,#0 ; rsc hi,#0`). Keep the operands in
// this order.
bool XlatCore::do_rsb(const cs_insn* in, bool with_carry) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 2 || d.op_count > 4) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;

    int rn_idx, src_idx, rot_idx = -1;
    if (d.op_count == 2) { rn_idx = 0; src_idx = 1; }
    else if (d.op_count == 3) { rn_idx = 1; src_idx = 2; }
    else {
        if (d.operands[2].type != ARM_OP_IMM ||
            d.operands[3].type != ARM_OP_IMM) return false;
        rn_idx = 1; src_idx = 2; rot_idx = 3;
    }
    if (d.operands[rn_idx].type != ARM_OP_REG) return false;
    int rn = guest_reg_of(d.operands[rn_idx].reg);
    if (rn < 0) return false;

    Op2 o;
    if (!decode_op2(d.operands[src_idx],
                    rot_idx >= 0 ? &d.operands[rot_idx] : nullptr, &o))
        return false;

    std::vector<int> owned;
    // RSB is NOT in A2.3's Align(PC,4) list (that covers LDC/LDR*/PLD/PLI/ADR
    // and ADD/SUB used as a PC-relative base), so Rn == PC is an ordinary
    // unaligned register read. Moot in ARM state and UNPREDICTABLE for Thumb
    // RSB.W with Rn == PC, so this is a correctness tidy rather than a
    // behaviour change -- but leaving it aligned would be a trap for the next
    // reader auditing this file against the manual.
    int lhs = (rn == 15) ? materialise_tmp(pc_value_for(addr_, false), &owned)
                         : regs_.read(rn);
    Src s;
    if (!lower_op2(o, /*allow_fold=*/false, /*logical_ctx=*/false, &s,
                   &owned)) {
        release(owned);
        return false;
    }
    // `rsb rd, rn, #0` is a negate, and WZR spares the materialisation.
    int minuend = force_reg(s, &owned);
    int dst = regs_.write_begin(rd);
    sink_.alu_reg(with_carry ? AluOp::SBC : AluOp::SUB, dst, minuend, lhs,
                  Sh::LSL, 0, d.update_flags, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// LSL/LSR/ASR/ROR as standalone instructions are MOV with a shifted
// operand, so route them through the same Op2 machinery. That also
// fixes an arm32_lifter.cpp bug: it masked the register shift amount to
// 5 bits, so `lsl r0, r1, r2` with r2 == 32 left r0 unchanged instead of
// producing 0 (A8.4.2: the amount is R[s]<7:0>, and Shift_C saturates).
bool XlatCore::do_shift_insn(const cs_insn* in, Sh sh) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2 && d.op_count != 3) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rn = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rn < 0) return false;

    Op2 o;
    o.guest_reg = rn;
    o.has_shift = true;
    o.sh = sh;

    if (d.op_count == 3) {
        const cs_arm_op& amt = d.operands[2];
        if (amt.type == ARM_OP_IMM) {
            o.sh_amt = static_cast<int>(amt.imm);
            if (o.sh_amt == 0) o.has_shift = false;
        } else if (amt.type == ARM_OP_REG) {
            int sg = guest_reg_of(amt.reg);
            if (sg < 0) return false;
            o.shift_by_reg = true;
            o.sh_guest_reg = sg;
        } else return false;
    } else {
        const cs_arm_op& op1 = d.operands[1];
        if (op1.shift.type == ARM_SFT_INVALID && op1.shift.value == 0) {
            // Thumb-16 T1 `LSRS Rd, Rs`: Rd = Rd shifted by Rs.
            o.guest_reg = rd;
            o.shift_by_reg = true;
            o.sh_guest_reg = rn;
        } else {
            switch (op1.shift.type) {
                case ARM_SFT_LSL: case ARM_SFT_LSR:
                case ARM_SFT_ASR: case ARM_SFT_ROR:
                    o.sh_amt = static_cast<int>(op1.shift.value);
                    break;
                case ARM_SFT_LSL_REG: case ARM_SFT_LSR_REG:
                case ARM_SFT_ASR_REG: case ARM_SFT_ROR_REG: {
                    int sg = guest_reg_of(op1.shift.value);
                    if (sg < 0) return false;
                    o.shift_by_reg = true;
                    o.sh_guest_reg = sg;
                    break;
                }
                default: return false;
            }
        }
    }

    std::vector<int> owned;
    int pre = regs_.read(o.guest_reg);
    Src s;
    // allow_fold lets emit_mov_src write `lsl wD, wN, #k` straight into
    // the destination instead of shifting into a temp and copying.
    if (!lower_op2(o, /*allow_fold=*/true, /*logical_ctx=*/true, &s,
                   &owned)) {
        release(owned);
        return false;
    }

    int saved = -1;
    CarrySpec cspec = CarrySpec::preserve();
    if (d.update_flags) {
        saved = flags_save(&owned);
        shifter_carry(o, pre, &cspec, &owned);
    }
    int dst = regs_.write_begin(rd);
    emit_mov_src(dst, s, &owned);
    if (d.update_flags) flags_commit_nz(dst, false, cspec, saved, &owned);
    regs_.write_end(rd);
    release(owned);
    return true;
}

bool XlatCore::do_rrx(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rn = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rn < 0) return false;

    Op2 o;
    o.guest_reg = rn;
    o.has_shift = true;
    o.rrx = true;

    std::vector<int> owned;
    int pre = regs_.read(rn);
    Src s;
    if (!lower_op2(o, false, true, &s, &owned)) { release(owned); return false; }

    int saved = -1;
    CarrySpec cspec = CarrySpec::preserve();
    if (d.update_flags) {
        saved = flags_save(&owned);
        shifter_carry(o, pre, &cspec, &owned);
    }
    int dst = regs_.write_begin(rd);
    emit_mov_src(dst, s, &owned);
    if (d.update_flags) flags_commit_nz(dst, false, cspec, saved, &owned);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// ---------------------------------------------------------------------
//  Multiply family
// ---------------------------------------------------------------------

bool XlatCore::do_mul(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2 && d.op_count != 3) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;
    int rn_idx = 1;
    int rm_idx = (d.op_count == 3) ? 2 : 1;
    if (d.operands[rn_idx].type != ARM_OP_REG ||
        d.operands[rm_idx].type != ARM_OP_REG) return false;
    int rn = guest_reg_of(d.operands[rn_idx].reg);
    int rm = guest_reg_of(d.operands[rm_idx].reg);
    if (rn < 0 || rm < 0) return false;

    std::vector<int> owned;
    int a = regs_.read(rn);
    int b = regs_.read(rm);
    int saved = d.update_flags ? flags_save(&owned) : -1;
    int dst = regs_.write_begin(rd);
    sink_.mul3(MulOp::MADD, dst, a, b, kZR);
    // MULS sets N and Z only; C and V are untouched on ARMv5 and later
    // (UNKNOWN only on ARMv4). Same NZ-only path as the logical family.
    if (d.update_flags)
        flags_commit_nz(dst, false, CarrySpec::preserve(), saved, &owned);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// MLA Rd, Rn, Rm, Ra = Ra + Rn*Rm ; MLS = Ra - Rn*Rm.
bool XlatCore::do_mla(const cs_insn* in, bool is_sub) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return false;
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rn = guest_reg_of(d.operands[1].reg);
    int rm = guest_reg_of(d.operands[2].reg);
    int ra = guest_reg_of(d.operands[3].reg);
    if (rd < 0 || rn < 0 || rm < 0 || ra < 0) return false;

    std::vector<int> owned;
    int a = regs_.read(rn);
    int b = regs_.read(rm);
    int c = regs_.read(ra);
    int saved = d.update_flags ? flags_save(&owned) : -1;
    int dst = regs_.write_begin(rd);
    sink_.mul3(is_sub ? MulOp::MSUB : MulOp::MADD, dst, a, b, c);
    if (d.update_flags)
        flags_commit_nz(dst, false, CarrySpec::preserve(), saved, &owned);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// UMULL/SMULL/UMLAL/SMLAL RdLo, RdHi, Rn, Rm.
//
// A64's UMADDL/SMADDL do the whole 32x32 -> 64 multiply-accumulate in
// one instruction, so the accumulate forms cost one extra ORR to
// assemble (RdHi:RdLo) into an X register. That relies on assumption A2
// (guest values are held zero-extended in their host X register).
bool XlatCore::do_mull(const cs_insn* in, bool is_signed, bool accumulate) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return false;
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG) return false;
    int rd_lo = guest_reg_of(d.operands[0].reg);
    int rd_hi = guest_reg_of(d.operands[1].reg);
    int rn    = guest_reg_of(d.operands[2].reg);
    int rm    = guest_reg_of(d.operands[3].reg);
    if (rd_lo < 0 || rd_hi < 0 || rn < 0 || rm < 0) return false;

    std::vector<int> owned;
    int a = regs_.read(rn);
    int b = regs_.read(rm);

    int acc = kZR;
    if (accumulate) {
        int lo = regs_.read(rd_lo);
        int hi = regs_.read(rd_hi);
        acc = regs_.temp_alloc();
        owned.push_back(acc);
        sink_.alu_reg(AluOp::ORR, acc, lo, hi, Sh::LSL, 32, false, true);
    }

    int prod = regs_.temp_alloc();
    owned.push_back(prod);
    sink_.mul3(is_signed ? MulOp::SMADDL : MulOp::UMADDL, prod, a, b, acc);

    int saved = d.update_flags ? flags_save(&owned) : -1;

    int dlo = regs_.write_begin(rd_lo);
    sink_.mov_reg(dlo, prod, false);       // W-form zeroes bits 63:32
    regs_.write_end(rd_lo);
    int dhi = regs_.write_begin(rd_hi);
    sink_.shift_imm(Sh::LSR, dhi, prod, 32, true);
    regs_.write_end(rd_hi);

    if (d.update_flags) {
        // A8.8.190 SMULL / A8.8.258 UMULL / A8.8.179 SMLAL / A8.8.257
        // UMLAL all carry the identical block:
        //   APSR.N = result<63>; APSR.Z = IsZeroBit(result<63:0>);
        //   // APSR.C, APSR.V unchanged (ARMv5+)
        // The
        // LLVM lifter derived BOTH from the high word alone, so Z was
        // wrong whenever the low word was non-zero. Use the full 64-bit
        // product.
        flags_commit_nz(prod, true, CarrySpec::preserve(), saved, &owned);
    }
    release(owned);
    return true;
}

// UMAAL RdLo, RdHi, Rn, Rm = Rn*Rm + RdLo + RdHi. No S form exists.
bool XlatCore::do_umaal(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return false;
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG) return false;
    int rd_lo = guest_reg_of(d.operands[0].reg);
    int rd_hi = guest_reg_of(d.operands[1].reg);
    int rn    = guest_reg_of(d.operands[2].reg);
    int rm    = guest_reg_of(d.operands[3].reg);
    if (rd_lo < 0 || rd_hi < 0 || rn < 0 || rm < 0) return false;

    std::vector<int> owned;
    int a = regs_.read(rn);
    int b = regs_.read(rm);
    int lo = regs_.read(rd_lo);
    int hi = regs_.read(rd_hi);
    int prod = regs_.temp_alloc();
    owned.push_back(prod);
    sink_.mul3(MulOp::UMADDL, prod, a, b, lo);
    sink_.alu_reg(AluOp::ADD, prod, prod, hi, Sh::LSL, 0, false, true);
    int dlo = regs_.write_begin(rd_lo);
    sink_.mov_reg(dlo, prod, false);
    regs_.write_end(rd_lo);
    int dhi = regs_.write_begin(rd_hi);
    sink_.shift_imm(Sh::LSR, dhi, prod, 32, true);
    regs_.write_end(rd_hi);
    release(owned);
    return true;
}

// SDIV / UDIV. A64's SDIV and UDIV match A32's bit for bit: divide by
// zero yields 0 in BOTH (DDI 0487 C6.2.236 SDIV / C6.2.340 UDIV vs
// DDI 0406C.d A8.8.166 SDIV / A8.8.249 UDIV, with A4.4.8 pinning
// "in an ARMv7-A profile implementation ... divide-by-zero always returns
// a zero result"), and
// INT_MIN / -1 yields INT_MIN in both. The LLVM lifter needed explicit
// selects only because LLVM IR's sdiv/udiv are undefined on those
// inputs, not because the architectures disagree.
//
// Note on availability: integer divide is optional on ARMv7-A and most
// armeabi-v7a code calls __aeabi_idiv instead, which arrives here as an
// ordinary BL (or, for the unrolled software-divide idiom, as a computed
// jump the CFG layer intercepts). We translate SDIV/UDIV when the
// decoder produces them; nothing here assumes the guest has them.
bool XlatCore::do_div(const cs_insn* in, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return false;
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rn = guest_reg_of(d.operands[1].reg);
    int rm = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rm < 0) return false;
    int a = regs_.read(rn);
    int b = regs_.read(rm);
    int dst = regs_.write_begin(rd);
    sink_.divide(is_signed, dst, a, b, false);
    regs_.write_end(rd);
    return true;
}

// ---------------------------------------------------------------------
//  Bit manipulation
// ---------------------------------------------------------------------

bool XlatCore::do_bit1(const cs_insn* in, Bit1Op op) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rs = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rs < 0) return false;
    int v = regs_.read(rs);
    int dst = regs_.write_begin(rd);
    sink_.bit1(op, dst, v, false);
    regs_.write_end(rd);
    return true;
}

// REVSH Rd, Rm = SignExtend(Rm<7:0> : Rm<15:8>, 32)  (A8.8.148). Only the
// LOW halfword of Rm is read; bits 31:16 are ignored.
// A64's REV16 reverses the bytes inside EACH halfword, so the low
// halfword already holds Rm<7:0>:Rm<15:8>; SXTH finishes the job.
bool XlatCore::do_revsh(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rs = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rs < 0) return false;
    int v = regs_.read(rs);
    int dst = regs_.write_begin(rd);
    sink_.bit1(Bit1Op::REV16, dst, v, false);
    sink_.bit1(Bit1Op::SXTH, dst, dst, false);
    regs_.write_end(rd);
    return true;
}

namespace {
// The rotate the (U|S)XT(B|H)(16)? family applies BEFORE extracting.
// Capstone carries it as an ARM_SFT_ROR on the source operand. The LLVM
// lifter dropped it for a while (the "extend-ror-dropped" audit
// finding), so it is explicitly modelled here.
int extend_rotate(const cs_arm_op& op) {
    if (op.shift.type == ARM_SFT_ROR) return static_cast<int>(op.shift.value) & 31;
    return 0;
}
}  // namespace

bool XlatCore::do_extend(const cs_insn* in, int bits, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rs = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rs < 0) return false;
    int rot = extend_rotate(d.operands[1]);
    int v = regs_.read(rs);
    int dst = regs_.write_begin(rd);
    if (rot == 0) {
        sink_.bit1(is_signed ? (bits == 8 ? Bit1Op::SXTB : Bit1Op::SXTH)
                             : (bits == 8 ? Bit1Op::UXTB : Bit1Op::UXTH),
                   dst, v, false);
    } else if (rot + bits <= 32) {
        // The rotated field does not wrap, so one bitfield extract does
        // the rotate and the extension together.
        if (is_signed) sink_.sbfx(dst, v, rot, bits, false);
        else           sink_.ubfx(dst, v, rot, bits, false);
    } else {
        sink_.extr(dst, v, v, rot, false);
        sink_.bit1(is_signed ? (bits == 8 ? Bit1Op::SXTB : Bit1Op::SXTH)
                             : (bits == 8 ? Bit1Op::UXTB : Bit1Op::UXTH),
                   dst, dst, false);
    }
    regs_.write_end(rd);
    return true;
}

// UXTAB/UXTAH/SXTAB/SXTAH Rd, Rn, Rm{, ROR #n}: Rd = Rn + Extend(ROR(Rm)).
// A64's extended-register ADD does the extend for free.
bool XlatCore::do_extend_add(const cs_insn* in, int bits, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return false;
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rn = guest_reg_of(d.operands[1].reg);
    int rm = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rm < 0) return false;

    std::vector<int> owned;
    int base = regs_.read(rn);
    int v = regs_.read(rm);
    int rot = extend_rotate(d.operands[2]);
    if (rot != 0) {
        int t = regs_.temp_alloc();
        owned.push_back(t);
        sink_.extr(t, v, v, rot, false);
        v = t;
    }
    Ext e = is_signed ? (bits == 8 ? Ext::SXTB : Ext::SXTH)
                      : (bits == 8 ? Ext::UXTB : Ext::UXTH);
    int dst = regs_.write_begin(rd);
    sink_.alu_extended(AluOp::ADD, dst, base, v, e, 0, false, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

bool XlatCore::do_bfx(const cs_insn* in, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    if (d.operands[2].type != ARM_OP_IMM) return false;
    if (d.operands[3].type != ARM_OP_IMM) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rs = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rs < 0) return false;
    int lsb = static_cast<int>(d.operands[2].imm);
    int width = static_cast<int>(d.operands[3].imm);
    // An out-of-range field is only reachable when data has been decoded
    // as instructions. Emitting nothing keeps the rest of the function
    // liftable; returning false would demote the whole instruction to an
    // UNSUPPORTED comment (the LLVM lifter made the same choice).
    if (width <= 0 || width > 32 || lsb < 0 || lsb + width > 32) return true;
    int v = regs_.read(rs);
    int dst = regs_.write_begin(rd);
    if (is_signed) sink_.sbfx(dst, v, lsb, width, false);
    else           sink_.ubfx(dst, v, lsb, width, false);
    regs_.write_end(rd);
    return true;
}

bool XlatCore::do_bfi(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_REG) return false;
    if (d.operands[2].type != ARM_OP_IMM) return false;
    if (d.operands[3].type != ARM_OP_IMM) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    int rs = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rs < 0) return false;
    int lsb = static_cast<int>(d.operands[2].imm);
    int width = static_cast<int>(d.operands[3].imm);
    if (width <= 0 || width > 32 || lsb < 0 || lsb + width > 32) return false;
    int v = regs_.read(rs);
    int dst = regs_.write_begin(rd);
    sink_.bfi(dst, v, lsb, width, false);
    regs_.write_end(rd);
    return true;
}

// BFC Rd, #lsb, #width is BFI with a zero source.
bool XlatCore::do_bfc(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_IMM) return false;
    if (d.operands[2].type != ARM_OP_IMM) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;
    int lsb = static_cast<int>(d.operands[1].imm);
    int width = static_cast<int>(d.operands[2].imm);
    if (width <= 0 || width > 32 || lsb < 0 || lsb + width > 32) return false;
    int dst = regs_.write_begin(rd);
    sink_.bfi(dst, kZR, lsb, width, false);
    regs_.write_end(rd);
    return true;
}

// ADR Rd, label -- result = Align(PC,4) +/- imm32 (DDI 0406C.d A8.8.12).
//
// Capstone does NOT hand us the absolute target, despite what the comment here
// used to claim. It reports the raw DISPLACEMENT, verified in both modes:
//   ARM   `adr r1,#8` -> decodes as ARM_INS_ADD `add r1, pc, #8`, imm = 8
//   THUMB `adr r1,#8` -> ARM_INS_ADR,                             imm = 8
// so using the operand directly materialises 8 instead of Align(PC,4)+8. The
// ARM form is fine because it lands in do_alu's Rn == PC path, which already
// applies Align(PC,4); only this Thumb path was wrong, and it silently
// produced a small integer where a code address belongs.
//
// HCR 1.43: libxml2's xmlNodeGetContent dispatches its node-type switch through
// a PC-relative jump table --
//     adr   r1, #8                 ; table base = Align(PC,4)+8 = 0x2af864
//     ldr.w r2, [r1, r3, lsl #2]   ; r2 = table[node->type]
//     add   r1, r2
//     bx    r1
// With r1 = 8 the table read comes from address 8 and the branch target was
// EXACTLY 0x8, which is what the indirect-call panics reported. Every Thumb
// ADR in every app was affected; this one just happened to be load-bearing.
//
// Signed so the T2 subtract form (Align(PC,4) - imm32) works too.
bool XlatCore::do_adr(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_IMM) return false;
    int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return false;
    // pc_value_for() already folds in the load bias; do not add it twice.
    uint32_t target = pc_value_for(addr_, /*aligned=*/true) +
                      static_cast<uint32_t>(
                          static_cast<int32_t>(d.operands[1].imm));
    int dst = regs_.write_begin(rd);
    materialise_u32(dst, target);
    regs_.write_end(rd);
    return true;
}

// LDR Rt, [pc, #imm] -- the compiler's constant pool.
//
// The pool contents are NOT constant-folded at translate time: the
// runtime loader applies relocations to the image after we translate,
// so a value baked in here would be the pre-relocation one. Materialise
// the (aligned, biased) address and issue a real load.
bool XlatCore::do_ldr_literal(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_MEM) return false;
    if (d.operands[1].mem.base != ARM_REG_PC) return false;
    if (d.operands[1].mem.index != ARM_REG_INVALID) return false;
    int rt = guest_reg_of(d.operands[0].reg);
    if (rt < 0) return false;
    // Literal addressing uses Align(PC, 4). In Thumb this is NOT a no-op:
    // PC = insn + 4 is only halfword-aligned when the instruction sits at
    // an address 2 mod 4, so dropping the mask is off by 2 for half of all
    // instruction addresses.
    uint32_t addr = pc_value_for(addr_, true) +
                    static_cast<uint32_t>(
                        static_cast<int32_t>(d.operands[1].mem.disp));
    std::vector<int> owned;
    int t = materialise_tmp(addr, &owned);
    int dst = regs_.write_begin(rt);
    sink_.load_guest_u32(dst, t);
    regs_.write_end(rt);
    release(owned);
    return true;
}

// ---------------------------------------------------------------------
//  Signed halfword multiplies (17 families)
// ---------------------------------------------------------------------
//
// Sources: Arm A32/T32 Instruction Set Architecture, 2025-06 release
// (documentation-service.arm.com/static/685eb49f98cc0c1f1a2e63c8),
// whose Operation blocks are the current spelling of DDI 0406C.d
// A8.8.176 (SMLABB..SMLATT), A8.8.178 (SMLALBB..SMLALTT), A8.8.179
// (SMLAWB/SMLAWT), A8.8.189 (SMULBB..SMULTT) and A8.8.191
// (SMULWB/SMULWT). Every pseudocode block below is quoted verbatim from
// that PDF, so a future session does not have to re-fetch it.
//
// Three facts drive the whole lowering:
//
//  1. The half selection is `if n_high then R[n]<31:16> else R[n]<15:0>`,
//     read as a SIGNED 16-bit value. A64 SBFX does both in one
//     instruction: `sbfx Wd, Wn, #16, #16` for the top half and
//     `sbfx Wd, Wn, #0, #16` (== SXTH) for the bottom.
//
//  2. NONE of these instructions touches N/Z/C/V. There is no S form.
//     So every emitted instruction here must be a non-flag-setting form,
//     including the overflow test (which is why it is EOR + CBZ and not
//     CMP + B.EQ): guest NZCV lives in the host PSTATE (assumption A3).
//
//  3. The accumulate forms set the STICKY APSR.Q on signed overflow, and
//     Q is not a PSTATE bit on A64. It is homed in GuestState.apsr_q
//     (guest_state.h) and is only ever observable through MRS, which is
//     now implemented, so modelling it costs a handful of instructions
//     on a rare opcode and closes the loop. arm32_lifter.cpp explicitly
//     did NOT model Q ("we don't model Q (read by MRS APSR which we
//     don't expose)"); that was consistent for the incumbent and is not
//     consistent here.

// Sign-extended 16-bit half of a guest register, in a fresh temp.
int XlatCore::half_signed(int guest_reg, bool top, std::vector<int>* owned) {
    const int src = regs_.read(guest_reg);
    const int t = regs_.temp_alloc();
    owned->push_back(t);
    sink_.sbfx(t, src, top ? 16 : 0, 16, false);
    return t;
}

// APSR.Q is sticky: set it when the exact (64-bit) result is not the
// sign extension of the 32-bit value the instruction actually wrote.
// That is precisely the spec's `if result != SInt(result<31:0>)` and
// `if (result >> 16) != SInt(R[d])` tests.
//
// Flag-free by construction: EOR and CBZ neither read nor write PSTATE.
void XlatCore::set_q_if_overflow(int wide_reg, int narrow_reg,
                                 std::vector<int>* owned) {
    // These three all die here; freeing them keeps the ten-register
    // scratch pool inside budget for the callers that chain several of
    // these helpers (QDADD, SSAT16).
    const int chk = regs_.temp_alloc();
    //   sxtw chk, narrow ; eor chk, chk, wide ; cbz chk, Lok
    // SXTW is an alias of SBFM Xd, Xn, #0, #31 (DDI 0487 C6.2.320), which
    // is what sbfx(lsb 0, width 32, sf64) encodes.
    sink_.sbfx(chk, narrow_reg, 0, 32, true);
    sink_.alu_reg(AluOp::EOR, chk, chk, wide_reg, Sh::LSL, 0, false, true);
    Label ok = sink_.new_label();
    sink_.cbz(chk, ok, true);
    const int one = regs_.temp_alloc();
    const int sc = regs_.temp_alloc();
    sink_.movz(one, 1, 0, false);
    sink_.guest_state_store(one, kGuestStateApsrQOffset, sc);
    regs_.temp_free(sc);
    regs_.temp_free(one);
    sink_.bind(ok);
    regs_.temp_free(chk);
    (void)owned;
}

// SMULBB / SMULBT / SMULTB / SMULTT  Rd, Rn, Rm
//
//   constant bits(16) operand1 = if n_high then R[n]<31:16> else R[n]<15:0>;
//   constant bits(16) operand2 = if m_high then R[m]<31:16> else R[m]<15:0>;
//   constant integer result = SInt(operand1) * SInt(operand2);
//   R[d] = result<31:0>;
//   // Signed overflow cannot occur
//
// The product of two 16-bit signed values is at most 2^30 in magnitude,
// so the 32-bit MUL is exact and Q genuinely cannot be set.
bool XlatCore::do_smul_half(const cs_insn* in, bool n_top, bool m_top) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("SMUL<x><y> needs 3 operands");
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMUL<x><y> operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rm < 0 || rd == 15 || rn == 15 || rm == 15)
        return refuse("SMUL<x><y> with PC or a non-core register "
                      "(UNPREDICTABLE)");

    std::vector<int> owned;
    const int a = half_signed(rn, n_top, &owned);
    const int b = half_signed(rm, m_top, &owned);
    const int dst = regs_.write_begin(rd);
    sink_.mul3(MulOp::MADD, dst, a, b, kZR);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// SMLABB / SMLABT / SMLATB / SMLATT  Rd, Rn, Rm, Ra
//
//   constant integer result = SInt(operand1) * SInt(operand2) + SInt(R[a]);
//   R[d] = result<31:0>;
//   if result != SInt(result<31:0>) then // Signed overflow
//       PSTATE.Q = '1';
//
// The exact result needs 64 bits (a 2^30 product plus a full 32-bit
// signed accumulator), which is what makes SMADDL the right instruction:
// `smaddl Xd, Wn, Wm, Xa` computes Xa + SInt(Wn)*SInt(Wm) exactly
// (DDI 0487 C6.2.211), and its low 32 bits are `result<31:0>`.
bool XlatCore::do_smla_half(const cs_insn* in, bool n_top, bool m_top) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return refuse("SMLA<x><y> needs 4 operands");
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMLA<x><y> operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    const int ra = guest_reg_of(d.operands[3].reg);
    if (rd < 0 || rn < 0 || rm < 0 || ra < 0 ||
        rd == 15 || rn == 15 || rm == 15 || ra == 15)
        return refuse("SMLA<x><y> with PC or a non-core register "
                      "(UNPREDICTABLE)");

    std::vector<int> owned;
    const int a = half_signed(rn, n_top, &owned);
    const int b = half_signed(rm, m_top, &owned);
    const int acc = regs_.temp_alloc();
    owned.push_back(acc);
    sink_.sbfx(acc, regs_.read(ra), 0, 32, true);      // sxtw acc, Ra
    const int res = regs_.temp_alloc();
    owned.push_back(res);
    sink_.mul3(MulOp::SMADDL, res, a, b, acc);
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, res, false);                    // result<31:0>
    regs_.write_end(rd);
    set_q_if_overflow(res, dst, &owned);
    release(owned);
    return true;
}

// SMLALBB / SMLALBT / SMLALTB / SMLALTT  RdLo, RdHi, Rn, Rm
//
//   constant integer result = SInt(operand1) * SInt(operand2)
//                             + SInt(R[dHi]:R[dLo]);
//   R[dHi] = result<63:32>;
//   R[dLo] = result<31:0>;
//
// No Q: the spec block has no overflow test at all (the 64-bit
// accumulator cannot overflow from a 2^30 product in any way the
// architecture reports).
bool XlatCore::do_smlal_half(const cs_insn* in, bool n_top, bool m_top) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return refuse("SMLAL<x><y> needs 4 operands");
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMLAL<x><y> operand is not a register");
    const int rd_lo = guest_reg_of(d.operands[0].reg);
    const int rd_hi = guest_reg_of(d.operands[1].reg);
    const int rn    = guest_reg_of(d.operands[2].reg);
    const int rm    = guest_reg_of(d.operands[3].reg);
    if (rd_lo < 0 || rd_hi < 0 || rn < 0 || rm < 0 ||
        rd_lo == 15 || rd_hi == 15 || rn == 15 || rm == 15)
        return refuse("SMLAL<x><y> with PC or a non-core register "
                      "(UNPREDICTABLE)");

    std::vector<int> owned;
    const int a = half_signed(rn, n_top, &owned);
    const int b = half_signed(rm, m_top, &owned);
    // Guest registers are held ZERO-extended in their host homes
    // (assumption A2), so the 64-bit accumulator is one ORR.
    const int acc = regs_.temp_alloc();
    owned.push_back(acc);
    sink_.alu_reg(AluOp::ORR, acc, regs_.read(rd_lo), regs_.read(rd_hi),
                  Sh::LSL, 32, false, true);
    const int res = regs_.temp_alloc();
    owned.push_back(res);
    sink_.mul3(MulOp::SMADDL, res, a, b, acc);
    const int dlo = regs_.write_begin(rd_lo);
    sink_.mov_reg(dlo, res, false);
    regs_.write_end(rd_lo);
    const int dhi = regs_.write_begin(rd_hi);
    sink_.shift_imm(Sh::LSR, dhi, res, 32, true);
    regs_.write_end(rd_hi);
    release(owned);
    return true;
}

// SMULWB / SMULWT  Rd, Rn, Rm
//
//   constant bits(16) operand2 = if m_high then R[m]<31:16> else R[m]<15:0>;
//   constant integer product = SInt(R[n]) * SInt(operand2);
//   R[d] = product<47:16>;
//   // Signed overflow cannot occur
//
// The product needs at most 48 signed bits, so `product >> 16`
// (arithmetic) lands inside 32 bits and its low word IS product<47:16>.
bool XlatCore::do_smul_w(const cs_insn* in, bool m_top) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("SMULW<y> needs 3 operands");
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMULW<y> operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rm < 0 || rd == 15 || rn == 15 || rm == 15)
        return refuse("SMULW<y> with PC or a non-core register "
                      "(UNPREDICTABLE)");

    std::vector<int> owned;
    const int b = half_signed(rm, m_top, &owned);
    const int p = regs_.temp_alloc();
    owned.push_back(p);
    sink_.mul3(MulOp::SMADDL, p, regs_.read(rn), b, kZR);   // smull
    sink_.shift_imm(Sh::ASR, p, p, 16, true);
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, p, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// SMLAWB / SMLAWT  Rd, Rn, Rm, Ra
//
//   constant bits(16) operand2 = if m_high then R[m]<31:16> else R[m]<15:0>;
//   constant integer result = SInt(R[n]) * SInt(operand2)
//                             + (SInt(R[a]) << 16);
//   R[d] = result<47:16>;
//   if (result >> 16) != SInt(R[d]) then // Signed overflow
//       PSTATE.Q = '1';
//
// Note there is NO rounding term here (that belongs to SMMLAR / SMUAD,
// a different family): the accumulator is pre-shifted LEFT by 16 and the
// whole sum is then shifted RIGHT by 16, which is an exact truncating
// operation, and the discarded low 16 bits are exactly the product's.
bool XlatCore::do_smla_w(const cs_insn* in, bool m_top) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 4) return refuse("SMLAW<y> needs 4 operands");
    for (int i = 0; i < 4; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMLAW<y> operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    const int ra = guest_reg_of(d.operands[3].reg);
    if (rd < 0 || rn < 0 || rm < 0 || ra < 0 ||
        rd == 15 || rn == 15 || rm == 15 || ra == 15)
        return refuse("SMLAW<y> with PC or a non-core register "
                      "(UNPREDICTABLE)");

    std::vector<int> owned;
    const int b = half_signed(rm, m_top, &owned);
    const int acc = regs_.temp_alloc();
    owned.push_back(acc);
    sink_.sbfx(acc, regs_.read(ra), 0, 32, true);            // sxtw
    sink_.shift_imm(Sh::LSL, acc, acc, 16, true);            // << 16
    const int res = regs_.temp_alloc();
    owned.push_back(res);
    sink_.mul3(MulOp::SMADDL, res, regs_.read(rn), b, acc);
    sink_.shift_imm(Sh::ASR, res, res, 16, true);            // result >> 16
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, res, false);                          // result<47:16>
    regs_.write_end(rd);
    set_q_if_overflow(res, dst, &owned);
    release(owned);
    return true;
}


// ---------------------------------------------------------------------
//  ARMv6 media / DSP families
// ---------------------------------------------------------------------
//
// These are GPR operations that happen to be SIMD-SHAPED, so they live
// here rather than in xlat_simd: the driver tries the core first, and a
// core dispatch entry shadows anything the SIMD stage might claim.
//
// Every Operation block below is quoted verbatim from the Arm A32/T32
// Instruction Set Architecture, 2025-06 release (the current spelling
// of DDI 0406C.d A8.8.x), so the next reader does not have to re-fetch.
//
// TWO INVARIANTS THAT SHAPE ALL OF THEM:
//
//  * None of these writes N/Z/C/V. Guest NZCV lives in the host PSTATE
//    (assumption A3), so any lowering that needs CMP/CSEL has to
//    bracket itself with MRS/MSR. The ones that can be done branch-free
//    (PKH, SEL, the extends, USAD8) are, and say so.
//  * The saturating ones write the STICKY APSR.Q, which is memory-homed
//    in GuestState. Q is only observable through MRS, which this file
//    now implements, so modelling it closes the loop rather than being
//    decoration.

// Set Q when `flag` (0 or 1) is nonzero. Flag-free: CBZ reads no PSTATE.
void XlatCore::set_q_if(int flag, std::vector<int>* owned) {
    if (flag < 0) return;
    Label ok = sink_.new_label();
    sink_.cbz(flag, ok, false);
    const int one = regs_.temp_alloc();
    const int sc = regs_.temp_alloc();
    sink_.movz(one, 1, 0, false);
    sink_.guest_state_store(one, kGuestStateApsrQOffset, sc);
    regs_.temp_free(sc);
    regs_.temp_free(one);
    sink_.bind(ok);
    (void)owned;
}

int XlatCore::half_of_reg(int host_reg, bool top, std::vector<int>* owned) {
    const int t = regs_.temp_alloc();
    owned->push_back(t);
    sink_.sbfx(t, host_reg, top ? 16 : 0, 16, false);
    return t;
}

// SignedSatQ(SInt(x), bits) on a 64-bit value, in place. CMP/CSEL, so
// PSTATE must already be saved by the caller.
int XlatCore::clamp_signed(int x, int bits, std::vector<int>* owned) {
    if (bits >= 64) return -1;
    const int64_t hi = (int64_t(1) << (bits - 1)) - 1;
    // `orig` and `c` die inside this helper, so they are freed here
    // rather than added to the caller's list. The scratch pool is ten
    // registers and QDADD needs TWO clamps plus its own operands; leaking
    // three per clamp exhausts it, which is a translator abort rather
    // than a wrong answer, but an abort all the same.
    const int orig = regs_.temp_alloc();
    const int c = regs_.temp_alloc();
    sink_.mov_reg(orig, x, true);
    // r = min(r, hi)
    materialise_u32(c, static_cast<uint32_t>(hi));   // hi always fits u32
    sink_.alu_reg(AluOp::SUB, kZR, x, c, Sh::LSL, 0, true, true);   // cmp
    sink_.csel(CselOp::CSEL, x, c, x, Cond::GT, true);
    // r = max(r, lo). lo is negative, so build it as -(hi + 1).
    materialise_u32(c, static_cast<uint32_t>(-(hi + 1)));
    sink_.sbfx(c, c, 0, 32, true);                                  // sxtw
    sink_.alu_reg(AluOp::SUB, kZR, x, c, Sh::LSL, 0, true, true);
    sink_.csel(CselOp::CSEL, x, c, x, Cond::LT, true);
    const int sat = regs_.temp_alloc();
    owned->push_back(sat);
    sink_.alu_reg(AluOp::EOR, sat, x, orig, Sh::LSL, 0, false, true);
    sink_.alu_reg(AluOp::SUB, kZR, sat, kZR, Sh::LSL, 0, true, true);
    sink_.cset(sat, Cond::NE, false);
    regs_.temp_free(c);
    regs_.temp_free(orig);
    return sat;
}

// UnsignedSatQ(SInt(x), bits): clamp into [0, 2^bits - 1].
int XlatCore::clamp_unsigned(int x, int bits, std::vector<int>* owned) {
    const uint64_t hi = (bits >= 32) ? 0xffffffffull
                                     : ((1ull << bits) - 1ull);
    const int orig = regs_.temp_alloc();
    const int c = regs_.temp_alloc();
    sink_.mov_reg(orig, x, true);
    materialise_u32(c, static_cast<uint32_t>(hi));
    sink_.alu_reg(AluOp::SUB, kZR, x, c, Sh::LSL, 0, true, true);
    sink_.csel(CselOp::CSEL, x, c, x, Cond::GT, true);
    sink_.alu_reg(AluOp::SUB, kZR, x, kZR, Sh::LSL, 0, true, true);
    sink_.csel(CselOp::CSEL, x, kZR, x, Cond::LT, true);
    const int sat = regs_.temp_alloc();
    owned->push_back(sat);
    sink_.alu_reg(AluOp::EOR, sat, x, orig, Sh::LSL, 0, false, true);
    sink_.alu_reg(AluOp::SUB, kZR, sat, kZR, Sh::LSL, 0, true, true);
    sink_.cset(sat, Cond::NE, false);
    regs_.temp_free(c);
    regs_.temp_free(orig);
    return sat;
}

// PKHBT / PKHTB  Rd, Rn, Rm{, shift}
//
//   constant bits(32) operand2 = Shift(R[m], shift_t, shift_n, PSTATE.C);
//   R[d]<15:0>  = if tbform then operand2<15:0> else R[n]<15:0>;
//   R[d]<31:16> = if tbform then R[n]<31:16>    else operand2<31:16>;
//
// Branch-free and flag-free: two bitfield ops.
bool XlatCore::do_pkh(const cs_insn* in, bool tbform) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 3 || d.op_count > 3)
        return refuse("PKHBT/PKHTB needs 3 operands");
    if (d.operands[0].type != ARM_OP_REG || d.operands[1].type != ARM_OP_REG)
        return refuse("PKHBT/PKHTB Rd or Rn is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    if (rd < 0 || rn < 0 || rd == 15 || rn == 15)
        return refuse("PKHBT/PKHTB with PC or a non-core register");
    Op2 o;
    if (!decode_op2(d.operands[2], nullptr, &o)) return false;
    if (o.is_imm || o.guest_reg == 15)
        return refuse("PKHBT/PKHTB operand2 must be a non-PC register");

    std::vector<int> owned;
    Src s;
    if (!lower_op2(o, /*allow_fold=*/false, /*logical_ctx=*/false, &s, &owned)) {
        release(owned);
        return false;
    }
    const int op2 = force_reg(s, &owned);
    const int nreg = regs_.read(rn);
    const int t = regs_.temp_alloc();
    owned.push_back(t);
    const int dst = regs_.write_begin(rd);
    if (tbform) {
        sink_.shift_imm(Sh::LSR, t, nreg, 16, false);   // Rn<31:16>
        sink_.ubfx(dst, op2, 0, 16, false);                    // operand2<15:0>
    } else {
        sink_.shift_imm(Sh::LSR, t, op2, 16, false);    // operand2<31:16>
        sink_.ubfx(dst, nreg, 0, 16, false);                   // Rn<15:0>
    }
    sink_.bfi(dst, t, 16, 16, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// SEL  Rd, Rn, Rm
//
//   R[d]<7:0>   = if PSTATE.GE<0> == '1' then R[n]<7:0>   else R[m]<7:0>;
//   ... one per byte, driven by GE<3:0>
//
// GE is memory-homed in GuestState. The 4 GE bits are spread into a
// per-byte mask with the standard bit-scatter multiply, which keeps the
// whole lowering branch-free and flag-free:
//   y = (ge * 0x00204081) & 0x01010101   puts GE<i> in bit 8*i
//   mask = y * 255                       widens each to a full byte
bool XlatCore::do_sel(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("SEL needs 3 operands");
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SEL operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rm < 0 || rd == 15 || rn == 15 || rm == 15)
        return refuse("SEL with PC or a non-core register (UNPREDICTABLE)");

    std::vector<int> owned;
    const int sc = regs_.temp_alloc();
    owned.push_back(sc);
    const int ge = regs_.temp_alloc();
    owned.push_back(ge);
    sink_.guest_state_load(ge, kGuestStateApsrGeOffset, sc);
    sink_.alu_imm(AluOp::AND, ge, ge, 0xfull, false, false);
    const int k = regs_.temp_alloc();
    owned.push_back(k);
    materialise_u32(k, 0x00204081u);
    sink_.mul3(MulOp::MADD, ge, ge, k, kZR);
    sink_.alu_imm(AluOp::AND, ge, ge, 0x01010101ull, false, false);
    // mask = ge * 255, as (ge << 8) - ge.
    sink_.shift_imm(Sh::LSL, k, ge, 8, false);
    sink_.alu_reg(AluOp::SUB, k, k, ge, Sh::LSL, 0, false, false);
    const int a = regs_.temp_alloc();
    owned.push_back(a);
    sink_.alu_reg(AluOp::AND, a, regs_.read(rn), k, Sh::LSL, 0, false, false);
    const int b = regs_.temp_alloc();
    owned.push_back(b);
    sink_.alu_reg(AluOp::BIC, b, regs_.read(rm), k, Sh::LSL, 0, false, false);
    const int dst = regs_.write_begin(rd);
    sink_.alu_reg(AluOp::ORR, dst, a, b, Sh::LSL, 0, false, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// SXTB16 / UXTB16 / SXTAB16 / UXTAB16
//
//   constant bits(32) rotated = ROR(R[m], rotation);
//   R[d]<15:0>  = [R[n]<15:0>  +] Extend(rotated<7:0>,   16);
//   R[d]<31:16> = [R[n]<31:16> +] Extend(rotated<23:16>, 16);
//
// The two halves wrap independently at 16 bits, which is why each is
// masked before being recombined.
bool XlatCore::do_xtb16(const cs_insn* in, bool is_signed, bool accumulate) {
    const cs_arm& d = in->detail->arm;
    const int need = accumulate ? 3 : 2;
    if (d.op_count < need || d.op_count > need)
        return refuse("xxTB16 operand count");
    for (int i = 0; i < need; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("xxTB16 operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = accumulate ? guest_reg_of(d.operands[1].reg) : -1;
    const int rm = guest_reg_of(d.operands[need - 1].reg);
    if (rd < 0 || rm < 0 || rd == 15 || rm == 15 ||
        (accumulate && (rn < 0 || rn == 15)))
        return refuse("xxTB16 with PC or a non-core register");
    // extend_rotate reports 0 for "no ROR", which is exactly the
    // architectural default, so there is no failure case here.
    const int rot = extend_rotate(d.operands[need - 1]);

    std::vector<int> owned;
    int src = regs_.read(rm);
    if (rot) {
        const int r = regs_.temp_alloc();
        owned.push_back(r);
        sink_.extr(r, src, src, rot, false);       // ROR(Rm, rotation)
        src = r;
    }
    const int lo = regs_.temp_alloc();
    owned.push_back(lo);
    const int hi = regs_.temp_alloc();
    owned.push_back(hi);
    if (is_signed) {
        sink_.sbfx(lo, src, 0, 8, false);
        sink_.sbfx(hi, src, 16, 8, false);
    } else {
        sink_.ubfx(lo, src, 0, 8, false);
        sink_.ubfx(hi, src, 16, 8, false);
    }
    if (accumulate) {
        const int nreg = regs_.read(rn);
        sink_.alu_reg(AluOp::ADD, lo, lo, nreg, Sh::LSL, 0, false, false);
        const int t = regs_.temp_alloc();
        owned.push_back(t);
        sink_.shift_imm(Sh::LSR, t, nreg, 16, false);
        sink_.alu_reg(AluOp::ADD, hi, hi, t, Sh::LSL, 0, false, false);
    }
    const int dst = regs_.write_begin(rd);
    sink_.ubfx(dst, lo, 0, 16, false);
    sink_.bfi(dst, hi, 16, 16, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// SSAT / USAT  Rd, #sat, Rn{, shift}
//
//   constant bits(32) operand = Shift(R[n], shift_t, shift_n, PSTATE.C);
//   (result, sat) = SignedSatQ(SInt(operand), saturate_to);   // or Unsigned
//   R[d] = SignExtend(result, 32);   // ZeroExtend for USAT
//   if sat then PSTATE.Q = '1';
//
// The clamp needs CMP/CSEL, and SSAT does NOT write N/Z/C/V, so the whole
// sequence is bracketed by an NZCV save/restore. `sat` is exactly
// "the clamp changed the value", which is what clamp_signed returns.
bool XlatCore::do_sat(const cs_insn* in, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("SSAT/USAT needs 3 operands");
    if (d.operands[0].type != ARM_OP_REG || d.operands[1].type != ARM_OP_IMM)
        return refuse("SSAT/USAT shape is (Rd, #sat, Rn)");
    const int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0 || rd == 15) return refuse("SSAT/USAT Rd is PC or non-core");
    int bits = static_cast<int>(d.operands[1].imm);
    // A8.8.183 SSAT: saturate_to = UInt(sat_imm) + 1, range 1..32.
    // A8.8.223 USAT: saturate_to = UInt(sat_imm), range 0..31. Capstone
    // reports the ASSEMBLY value, which already is saturate_to for both.
    if (bits < (is_signed ? 1 : 0) || bits > 32)
        return refuse("SSAT/USAT width outside the architectural range");

    Op2 o;
    if (!decode_op2(d.operands[2], nullptr, &o)) return false;
    if (o.is_imm || o.guest_reg == 15)
        return refuse("SSAT/USAT operand must be a non-PC register");

    std::vector<int> owned;
    Src s;
    if (!lower_op2(o, /*allow_fold=*/false, /*logical_ctx=*/false, &s, &owned)) {
        release(owned);
        return false;
    }
    const int src = force_reg(s, &owned);
    const int x = regs_.temp_alloc();
    owned.push_back(x);
    sink_.sbfx(x, src, 0, 32, true);            // sxtw: SInt(operand)
    const int saved = flags_save(&owned);
    const int sat = is_signed ? clamp_signed(x, bits, &owned)
                              : clamp_unsigned(x, bits, &owned);
    sink_.msr_nzcv(saved);
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, x, false);
    regs_.write_end(rd);
    set_q_if(sat, &owned);
    release(owned);
    return true;
}

// SSAT16 / USAT16  Rd, #sat, Rn -- the same clamp per halfword.
bool XlatCore::do_sat16(const cs_insn* in, bool is_signed) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("SSAT16/USAT16 needs 3 operands");
    if (d.operands[0].type != ARM_OP_REG || d.operands[1].type != ARM_OP_IMM ||
        d.operands[2].type != ARM_OP_REG)
        return refuse("SSAT16/USAT16 shape is (Rd, #sat, Rn)");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rn < 0 || rd == 15 || rn == 15)
        return refuse("SSAT16/USAT16 with PC or a non-core register");
    const int bits = static_cast<int>(d.operands[1].imm);
    if (bits < (is_signed ? 1 : 0) || bits > 16)
        return refuse("SSAT16/USAT16 width outside the architectural range");

    std::vector<int> owned;
    const int nreg = regs_.read(rn);
    const int saved = flags_save(&owned);
    int sat_any = -1;
    int half[2];
    for (int i = 0; i < 2; ++i) {
        half[i] = regs_.temp_alloc();
        owned.push_back(half[i]);
        sink_.sbfx(half[i], nreg, i * 16, 16, false);
        sink_.sbfx(half[i], half[i], 0, 32, true);      // sxtw
        const int st = is_signed ? clamp_signed(half[i], bits, &owned)
                                 : clamp_unsigned(half[i], bits, &owned);
        if (st >= 0) {
            if (sat_any < 0) sat_any = st;
            else sink_.alu_reg(AluOp::ORR, sat_any, sat_any, st, Sh::LSL, 0,
                               false, false);
        }
    }
    sink_.msr_nzcv(saved);
    const int dst = regs_.write_begin(rd);
    sink_.ubfx(dst, half[0], 0, 16, false);
    sink_.bfi(dst, half[1], 16, 16, false);
    regs_.write_end(rd);
    set_q_if(sat_any, &owned);
    release(owned);
    return true;
}

// QADD / QSUB / QDADD / QDSUB  Rd, Rm, Rn
//
//   (doubled, sat1) = SignedSatQ(2 * SInt(R[n]), 32);        // QD* only
//   (r, sat2) = SignedSatQ(SInt(R[m]) +/- SInt(doubled|R[n]), 32);
//   R[d] = r;  if sat1 || sat2 then PSTATE.Q = '1';
//
// NOTE the operand order: the syntax is `QSUB{<c>} {<Rd>,} <Rm>, <Rn>`
// and the operation is Rm - Rn, i.e. operands[1] - operands[2]. Getting
// that backwards is a sign flip that only shows on the subtracting
// forms, which is why the differential suite tests QSUB specifically.
bool XlatCore::do_qaddsub(const cs_insn* in, bool is_sub, bool doubled) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 3) return refuse("Q(D)ADD/SUB needs 3 operands");
    for (int i = 0; i < 3; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("Q(D)ADD/SUB operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rm = guest_reg_of(d.operands[1].reg);
    const int rn = guest_reg_of(d.operands[2].reg);
    if (rd < 0 || rm < 0 || rn < 0 || rd == 15 || rm == 15 || rn == 15)
        return refuse("Q(D)ADD/SUB with PC or a non-core register");

    std::vector<int> owned;
    const int a = regs_.temp_alloc();
    owned.push_back(a);
    const int b = regs_.temp_alloc();
    owned.push_back(b);
    sink_.sbfx(a, regs_.read(rm), 0, 32, true);      // SInt(Rm)
    sink_.sbfx(b, regs_.read(rn), 0, 32, true);      // SInt(Rn)
    const int saved = flags_save(&owned);
    int sat_any = -1;
    if (doubled) {
        sink_.alu_reg(AluOp::ADD, b, b, b, Sh::LSL, 0, false, true);
        sat_any = clamp_signed(b, 32, &owned);
    }
    sink_.alu_reg(is_sub ? AluOp::SUB : AluOp::ADD, a, a, b, Sh::LSL, 0,
                  false, true);
    const int s2 = clamp_signed(a, 32, &owned);
    if (sat_any < 0) sat_any = s2;
    else if (s2 >= 0)
        sink_.alu_reg(AluOp::ORR, sat_any, sat_any, s2, Sh::LSL, 0, false,
                      false);
    sink_.msr_nzcv(saved);
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, a, false);
    regs_.write_end(rd);
    set_q_if(sat_any, &owned);
    release(owned);
    return true;
}

// USAD8 / USADA8
//
//   absdiffN = Abs(UInt(R[n]<8N+7:8N>) - UInt(R[m]<8N+7:8N>));
//   result = [UInt(R[a]) +] absdiff1 + absdiff2 + absdiff3 + absdiff4;
//
// Branch-free and flag-free: each byte difference fits in [-255, 255], so
// the classic `s = d >> 31 (arithmetic); (d ^ s) - s` gives |d| without
// any comparison, and the running sum cannot need more than 32 bits.
bool XlatCore::do_usad8(const cs_insn* in, bool accumulate) {
    const cs_arm& d = in->detail->arm;
    const int need = accumulate ? 4 : 3;
    if (d.op_count != need) return refuse("USAD8/USADA8 operand count");
    for (int i = 0; i < need; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("USAD8/USADA8 operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    const int ra = accumulate ? guest_reg_of(d.operands[3].reg) : -1;
    if (rd < 0 || rn < 0 || rm < 0 || rd == 15 || rn == 15 || rm == 15 ||
        (accumulate && (ra < 0 || ra == 15)))
        return refuse("USAD8/USADA8 with PC or a non-core register");

    std::vector<int> owned;
    const int nreg = regs_.read(rn);
    const int mreg = regs_.read(rm);
    const int acc = regs_.temp_alloc();
    owned.push_back(acc);
    if (accumulate) sink_.mov_reg(acc, regs_.read(ra), false);
    else            sink_.mov_reg(acc, kZR, false);
    const int x = regs_.temp_alloc();
    owned.push_back(x);
    const int y = regs_.temp_alloc();
    owned.push_back(y);
    for (int i = 0; i < 4; ++i) {
        sink_.ubfx(x, nreg, i * 8, 8, false);
        sink_.ubfx(y, mreg, i * 8, 8, false);
        sink_.alu_reg(AluOp::SUB, x, x, y, Sh::LSL, 0, false, false);
        sink_.shift_imm(Sh::ASR, y, x, 31, false);
        sink_.alu_reg(AluOp::EOR, x, x, y, Sh::LSL, 0, false, false);
        sink_.alu_reg(AluOp::SUB, x, x, y, Sh::LSL, 0, false, false);
        sink_.alu_reg(AluOp::ADD, acc, acc, x, Sh::LSL, 0, false, false);
    }
    const int dst = regs_.write_begin(rd);
    sink_.mov_reg(dst, acc, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// The dual-halfword multiplies: SMUAD/SMUADX, SMUSD/SMUSDX,
// SMLAD/SMLADX, SMLSD/SMLSDX, SMLALD/SMLALDX, SMLSLD/SMLSLDX.
//
//   constant bits(32) operand2 = if m_swap then ROR(R[m],16) else R[m];
//   constant integer product1 = SInt(R[n]<15:0>)  * SInt(operand2<15:0>);
//   constant integer product2 = SInt(R[n]<31:16>) * SInt(operand2<31:16>);
//   result = product1 +/- product2 [+ SInt(R[a]) | + SInt(R[dHi]:R[dLo])];
//   R[d] = result<31:0>;                       // or the 64-bit pair
//   if result != SInt(result<31:0>) then PSTATE.Q = '1';   // adding forms
//
// `X` in the mnemonic is the m_swap variant, which exchanges the two
// halves of Rm before pairing them. Capstone encodes that in the
// mnemonic, not the operands, so it arrives here as the `swap` argument.
// SMUSD is the one member with "Signed overflow cannot occur".
bool XlatCore::do_dual_mul(const cs_insn* in, bool subtract, bool swap,
                           bool accumulate, bool long_form) {
    const cs_arm& d = in->detail->arm;
    const int need = (accumulate || long_form) ? 4 : 3;
    if (d.op_count != need) return refuse("dual-multiply operand count");
    for (int i = 0; i < need; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("dual-multiply operand is not a register");
    int idx = 0;
    const int rd_lo = guest_reg_of(d.operands[idx++].reg);
    const int rd_hi = long_form ? guest_reg_of(d.operands[idx++].reg) : -1;
    const int rn    = guest_reg_of(d.operands[idx++].reg);
    const int rm    = guest_reg_of(d.operands[idx++].reg);
    const int ra    = (accumulate && !long_form)
                          ? guest_reg_of(d.operands[idx++].reg) : -1;
    if (rd_lo < 0 || rn < 0 || rm < 0 || rd_lo == 15 || rn == 15 || rm == 15 ||
        (long_form && (rd_hi < 0 || rd_hi == 15)) ||
        (accumulate && !long_form && (ra < 0 || ra == 15)))
        return refuse("dual-multiply with PC or a non-core register");

    std::vector<int> owned;
    int mreg = regs_.read(rm);
    if (swap) {
        const int r = regs_.temp_alloc();
        owned.push_back(r);
        sink_.extr(r, mreg, mreg, 16, false);          // ROR(Rm, 16)
        mreg = r;
    }
    const int nreg = regs_.read(rn);
    const int a0 = half_of_reg(nreg, false, &owned);
    const int a1 = half_of_reg(nreg, true,  &owned);
    const int b0 = half_of_reg(mreg, false, &owned);
    const int b1 = half_of_reg(mreg, true,  &owned);

    const int acc = regs_.temp_alloc();
    owned.push_back(acc);
    if (long_form) {
        // Guest registers are zero-extended (assumption A2), so the
        // 64-bit accumulator is one ORR and its two's-complement value
        // IS SInt(R[dHi]:R[dLo]).
        sink_.alu_reg(AluOp::ORR, acc, regs_.read(rd_lo), regs_.read(rd_hi),
                      Sh::LSL, 32, false, true);
    } else if (accumulate) {
        sink_.sbfx(acc, regs_.read(ra), 0, 32, true);  // sxtw
    } else {
        sink_.mov_reg(acc, kZR, true);
    }
    const int res = regs_.temp_alloc();
    owned.push_back(res);
    sink_.mul3(MulOp::SMADDL, res, a0, b0, acc);          // acc + p1
    sink_.mul3(subtract ? MulOp::SMSUBL : MulOp::SMADDL, res, a1, b1, res);

    if (long_form) {
        const int dlo = regs_.write_begin(rd_lo);
        sink_.mov_reg(dlo, res, false);
        regs_.write_end(rd_lo);
        const int dhi = regs_.write_begin(rd_hi);
        sink_.shift_imm(Sh::LSR, dhi, res, 32, true);
        regs_.write_end(rd_hi);
    } else {
        const int dst = regs_.write_begin(rd_lo);
        sink_.mov_reg(dst, res, false);
        regs_.write_end(rd_lo);
        // "Signed overflow cannot occur" for SMUSD, and the long forms
        // have no overflow test at all.
        if (!(subtract && !accumulate)) set_q_if_overflow(res, dst, &owned);
    }
    release(owned);
    return true;
}

// SMMUL/SMMULR, SMMLA/SMMLAR, SMMLS/SMMLSR
//
//   integer result = [(SInt(R[a]) << 32) +/-] SInt(R[n]) * SInt(R[m]);
//   if round then result = result + 0x80000000;
//   R[d] = result<63:32>;
//
// The `R` suffix is round-half-up, added BEFORE the shift, which is why
// it is a plain 64-bit ADD of 0x80000000 rather than anything cleverer.
bool XlatCore::do_smm(const cs_insn* in, bool is_sub, bool round,
                      bool accumulate) {
    const cs_arm& d = in->detail->arm;
    const int need = accumulate ? 4 : 3;
    if (d.op_count != need) return refuse("SMMUL/SMMLA/SMMLS operand count");
    for (int i = 0; i < need; ++i)
        if (d.operands[i].type != ARM_OP_REG)
            return refuse("SMMUL/SMMLA/SMMLS operand is not a register");
    const int rd = guest_reg_of(d.operands[0].reg);
    const int rn = guest_reg_of(d.operands[1].reg);
    const int rm = guest_reg_of(d.operands[2].reg);
    const int ra = accumulate ? guest_reg_of(d.operands[3].reg) : -1;
    if (rd < 0 || rn < 0 || rm < 0 || rd == 15 || rn == 15 || rm == 15 ||
        (accumulate && (ra < 0 || ra == 15)))
        return refuse("SMMUL/SMMLA/SMMLS with PC or a non-core register");

    std::vector<int> owned;
    const int p = regs_.temp_alloc();
    owned.push_back(p);
    sink_.mul3(MulOp::SMADDL, p, regs_.read(rn), regs_.read(rm), kZR);
    if (accumulate) {
        const int acc = regs_.temp_alloc();
        owned.push_back(acc);
        sink_.sbfx(acc, regs_.read(ra), 0, 32, true);
        sink_.shift_imm(Sh::LSL, acc, acc, 32, true);      // SInt(Ra) << 32
        if (is_sub) sink_.alu_reg(AluOp::SUB, p, acc, p, Sh::LSL, 0, false,
                                  true);
        else        sink_.alu_reg(AluOp::ADD, p, p, acc, Sh::LSL, 0, false,
                                  true);
    }
    if (round) {
        const int r = regs_.temp_alloc();
        owned.push_back(r);
        materialise_u32(r, 0x80000000u);
        sink_.alu_reg(AluOp::ADD, p, p, r, Sh::LSL, 0, false, true);
    }
    const int dst = regs_.write_begin(rd);
    sink_.shift_imm(Sh::LSR, dst, p, 32, true);            // result<63:32>
    regs_.write_end(rd);
    release(owned);
    return true;
}

// ---------------------------------------------------------------------
//  SVC
// ---------------------------------------------------------------------
//
// `SVC #imm` is the Linux supervisor call. Only guest r0 changes (the
// kernel preserves r1-r12 across a syscall), so the lowering is one
// sink call that writes guest r0; see BufSink::svc_bridge for what the
// in-tree implementation puts there and why.
bool XlatCore::do_svc(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 1 || d.operands[0].type != ARM_OP_IMM)
        return refuse("SVC operand is not an immediate");
    const int dst = regs_.write_begin(0);
    sink_.svc_bridge(static_cast<uint32_t>(d.operands[0].imm), dst);
    regs_.write_end(0);
    return true;
}

// ---------------------------------------------------------------------
//  The coprocessor transfer space and the User-mode-inert forms
// ---------------------------------------------------------------------
//
// This is the last family in the fleet that reached the stub counter,
// and it is worth being exact about, because "emit nothing" and "this is
// translated" are different claims.
//
// WHAT THESE ARE. After TBB/TBH, the halfword multiplies, SVC and
// MRS/MSR landed, the ONLY remaining CFG-live refusals across HCR,
// Jetpack Joyride and Flappy Bird are `ldcl` / `stcl` / `ldc2` / `stc2`
// (plus their predicated spellings), and every reachable one of them
// sits inside libgcc's `__gnu_Unwind_Save_WMMXD`,
// `__gnu_Unwind_Restore_WMMXD`, `__gnu_Unwind_Save_WMMXC` and
// `__gnu_Unwind_Restore_WMMXC`. Those are iWMMXt (coprocessor p0/p1)
// register save/restore helpers. In Jetpack Joyride the rest are
// literal pools sitting after a `bl` to a noreturn throw helper, i.e.
// data that the decoder read as code.
//
// WHY THIS IS A TRANSLATION AND NOT A SHRUG. The PE that armeabi-v7a
// describes is ARMv7-A with VFPv3 and Advanced SIMD. Its coprocessor
// space is p10 and p11 (the floating-point and Advanced SIMD registers),
// and Capstone decodes every p10/p11 access as a V-mnemonic, so none of
// them arrives here. Everything that DOES arrive here therefore targets
// a coprocessor the modelled PE does not implement, and DDI 0406C.d
// A8.6.51 (LDC/LDC2) says of exactly that case: "If no coprocessor can
// execute the instruction, an Undefined Instruction exception is
// generated." There is no register state to update and no memory to
// touch; the architectural effect is a fault.
//
// TWO LOWERINGS, and the default is the conservative one:
//
//   * default: emit nothing. This reproduces arm32_lifter.cpp's
//     long-tail no-op block byte for byte, which is what every current
//     baseline was measured against, so it cannot regress anything.
//   * MACAPK_ARM32RC_COPROC_UNDEF=1: emit A64's permanently-undefined
//     encoding, which faults exactly as the guest would on hardware
//     without the coprocessor.
//
// The gate exists because flipping a silent no-op into a fault is a
// behaviour change that has to clear the protected sweep before it
// becomes the default, and this lane does not run the sweep. It follows
// the same pattern the design doc sets for MACAPK_RC_REAL_EXCLUSIVES.
//
// NOT COVERED, and recorded so it is not mistaken for solved: `MRC p15,
// 0, Rt, c13, c0, 3` reads TPIDRURO, which is how some Bionic builds
// implement `__get_tls()`. Neither pipeline models a guest TLS register,
// so both leave Rt untouched. No app in this fleet emits it (zero live
// sites in all three libraries), and inventing a value would be worse
// than the honest gap.

namespace {
// Capstone's arm_sysreg is TWO encodings sharing one enum: values below
// 256 are a bitmask of PSR field-mask bits, values from 256 up are
// ordinals naming a whole special register. Only the first half may be
// tested with `&`.
bool sysreg_is_spsr(unsigned sr) {
    if (sr >= 256u) return false;
    return (sr & (static_cast<unsigned>(ARM_SYSREG_SPSR_C) |
                  static_cast<unsigned>(ARM_SYSREG_SPSR_X) |
                  static_cast<unsigned>(ARM_SYSREG_SPSR_S) |
                  static_cast<unsigned>(ARM_SYSREG_SPSR_F))) != 0;
}

// Function-local static for thread-safe one-time init (see
// rc_stub_trace_enabled): build_module may call this from several workers.
bool coproc_undef_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_COPROC_UNDEF");
        return e && *e && std::strcmp(e, "0") != 0;
    }();
    return on;
}
}  // namespace

bool XlatCore::do_coproc(const cs_insn* in) {
    if (rc_stub_trace_enabled())
        rc_stub_trace(addr_, opts_.thumb, in, "core-coproc",
                      coproc_undef_enabled()
                          ? "coprocessor not implemented by the modelled PE "
                            "-> undefined-instruction trap"
                          : "coprocessor not implemented by the modelled PE "
                            "-> no-op (incumbent parity; set "
                            "MACAPK_ARM32RC_COPROC_UNDEF=1 to fault)");
    if (coproc_undef_enabled())
        sink_.sys_op(SysOp::Undefined, 0xA32Cu);   // greppable in a dump
    return true;
}

// The forms a User-mode PE cannot act on. Each one is architecturally
// inert HERE, not merely inconvenient:
//
//   CPS / CPSID / CPSIE: "CPS is treated as NOP if executed in User mode
//       unless it is defined as being CONSTRAINED UNPREDICTABLE elsewhere
//       in this section" (Arm A32/T32 ISA, CPS, CPSID, CPSIE). So NOP is
//       the specified behaviour, not an approximation.
//   SETEND: "Set Endianness writes a new value to PSTATE.E". The host is
//       fixed little-endian and so is every armeabi-v7a target, so the
//       only value the guest can usefully request is the one already in
//       effect. `setend be` is unrepresentable rather than ignored, and
//       is deprecated in ARMv8 besides.
//   HVC / SMC: hypervisor and secure-monitor calls. Both are UNDEFINED
//       at EL0, so they belong to the same gate as the coprocessor space.
//   BXNS / BLXNS: ARMv8-M secure-state branches. They do not exist in the
//       A-profile instruction set at all; Capstone reports them for some
//       ambiguous T32 encodings, which means the bytes were data.
bool XlatCore::do_el0_inert(const cs_insn* in, const char* why) {
    if (rc_stub_trace_enabled())
        rc_stub_trace(addr_, opts_.thumb, in, "core-inert", why);
    return true;
}

// BKPT: "Breakpoint causes a Breakpoint Instruction exception" (Arm
// A32/T32 ISA, BKPT; DDI 0406C.d A8.8.24). A64's BRK raises the same
// exception class, so this is a 1:1 mapping and NOT a no-op, which
// matters: clang lowers `__builtin_trap` and a failed assert to a trap
// instruction, and a no-op turns an abort into a fall-through.
//
// UDF / TRAP: "Permanently Undefined generates an Undefined Instruction
// exception" (Arm A32/T32 ISA, UDF). Control does not continue past it,
// so the block terminates here.
bool XlatCore::do_trap(const cs_insn* in, bool is_breakpoint,
                       bool* terminated) {
    const cs_arm& d = in->detail->arm;
    uint16_t imm = 0;
    if (d.op_count >= 1 && d.operands[0].type == ARM_OP_IMM)
        imm = static_cast<uint16_t>(d.operands[0].imm);
    if (is_breakpoint) {
        sink_.sys_op(SysOp::Breakpoint, imm);
        // A Breakpoint Instruction exception is resumable, so execution
        // may continue at the next instruction.
        return true;
    }
    sink_.sys_op(SysOp::Undefined, imm);
    *terminated = true;
    return true;
}

// ---------------------------------------------------------------------
//  MRS / MSR (the APSR surface)
// ---------------------------------------------------------------------
//
// Arm A32/T32 ISA, MRS, "Operation" (DDI 0406C.d A8.8.109):
//
//   constant bits(32) maskval = '11111000 11101111 00000011 11011111';
//   bits(32) psr_val = GetPSRFromPSTATE(...) AND maskval;
//   if PSTATE.EL == EL0 then
//       psr_val<22>  = bits(1) UNKNOWN;
//       psr_val<9:6> = bits(4) UNKNOWN;
//       psr_val<4:0> = bits(5) UNKNOWN;
//   R[d] = psr_val;
//
// Read that carefully, because it determines the whole implementation:
// maskval clears the IT, J, SS, IL and T bits, and at EL0 (which is the
// only privilege Mac.apk's guest ever runs at) the E bit, bits<9:6> and
// the whole M field are UNKNOWN. What is LEFT architecturally defined is
// exactly N(31) Z(30) C(29) V(28), Q(27) and GE[19:16]. So a
// specification-faithful MRS at EL0 is: host PSTATE.NZCV, plus Q and
// GE out of GuestState. Producing zero for the UNKNOWN fields is a
// legal choice of UNKNOWN.
//
// MRS from SPSR is a separate matter: "An MRS that accesses the SPSRs is
// UNPREDICTABLE if executed in User mode or System mode", so there is no
// defined value to synthesise and this refuses rather than inventing one.
bool XlatCore::do_mrs(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return refuse("MRS needs 2 operands");
    if (d.operands[0].type != ARM_OP_REG) return refuse("MRS Rd not a reg");
    const int rd = guest_reg_of(d.operands[0].reg);
    if (rd < 0) return refuse("MRS Rd is not a core register");
    if (rd == 15) return refuse("MRS to PC (UNPREDICTABLE: 'if d == 15 then "
                                "UNPREDICTABLE')");

    // The source spelling depends on which encoding Capstone matched:
    // ARM_OP_REG carrying ARM_REG_APSR / _CPSR / _SPSR, or ARM_OP_SYSREG
    // carrying an arm_sysreg. The two enums are numerically unrelated, so
    // the TYPE has to be checked before the value or an arm_sysreg could
    // alias ARM_REG_SPSR by accident.
    const cs_arm_op& src = d.operands[1];
    const bool from_spsr =
        (src.type == ARM_OP_REG && src.reg == ARM_REG_SPSR) ||
        (src.type == ARM_OP_SYSREG && sysreg_is_spsr(src.reg));
    if (from_spsr)
        return refuse("MRS from SPSR in User mode (UNPREDICTABLE: 'An MRS "
                      "that accesses the SPSRs is UNPREDICTABLE if executed "
                      "in User mode or System mode')");

    std::vector<int> owned;
    const int t = regs_.temp_alloc();
    owned.push_back(t);
    sink_.mrs_nzcv(t);                    // bits 31..28, everything else 0

    // Q -> bit 27. GE[3:0] -> bits 19..16.
    const int sc = regs_.temp_alloc();
    owned.push_back(sc);
    const int v = regs_.temp_alloc();
    owned.push_back(v);
    sink_.guest_state_load(v, kGuestStateApsrQOffset, sc);
    sink_.alu_imm(AluOp::AND, v, v, 1ull, false, false);
    sink_.alu_reg(AluOp::ORR, t, t, v, Sh::LSL, 27, false, false);
    sink_.guest_state_load(v, kGuestStateApsrGeOffset, sc);
    sink_.alu_imm(AluOp::AND, v, v, 0xfull, false, false);
    const int dst = regs_.write_begin(rd);
    sink_.alu_reg(AluOp::ORR, dst, t, v, Sh::LSL, 16, false, false);
    regs_.write_end(rd);
    release(owned);
    return true;
}

// MSR <spec_reg>, Rn | #imm.
//
// Arm A32/T32 ISA, MSR (register), "Operation": at EL0 the write goes
// through CPSRWriteByInstr(R[n], mask), and the prose above it pins the
// EL0 behaviour exactly: an MSR executed in User mode "does not update
// any CPSR field that is accessible only at EL1 or higher", and "Is
// UNPREDICTABLE if it attempts to update the SPSR" (with the
// CONSTRAINED UNPREDICTABLE list permitting "The instruction executes as
// NOP"). So:
//
//   mask<3> (the `f` byte, bits 31:24) -> N Z C V and Q
//   mask<2> (the `s` byte, bits 23:16) -> GE[3:0]
//   mask<1>, mask<0> (`x` and `c`)     -> nothing at EL0
//   any SPSR target                    -> NOP (an architecturally
//                                        permitted CONSTRAINED
//                                        UNPREDICTABLE behaviour)
//
// which means EVERY encoding of MSR has a defined lowering here and
// none of them is a refusal.
bool XlatCore::do_msr(const cs_insn* in) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 2 || d.op_count > 3)
        return refuse("MSR operand count outside 2..3");

    // Capstone spells the destination two different ways depending on the
    // encoding it matched: an ARM_OP_SYSREG carrying an arm_sysreg
    // bitfield, or a plain ARM_OP_REG carrying ARM_REG_APSR / _CPSR.
    bool write_f = false, write_s = false, is_spsr = false;
    const cs_arm_op& tgt = d.operands[0];
    if (tgt.type == ARM_OP_SYSREG) {
        const unsigned sr = tgt.reg;
        // The two halves of arm_sysreg must not be mixed. Below 256 the
        // values ARE a bitmask of the four field-mask bits per PSR
        // (SPSR_C=1, _X=2, _S=4, _F=8, CPSR_C=16, _X=32, _S=64, _F=128)
        // so `msr cpsr_fs` arrives as 192. From 256 up they are plain
        // ORDINALS (APSR=256, APSR_G=257, APSR_NZCVQ=258,
        // APSR_NZCVQG=259, ...). Testing the low bits of an ordinal is
        // what made `msr APSR_nzcvq, r0` (258) match SPSR_X (2) and turn
        // the whole instruction into a NOP, which the differential
        // harness caught as msr_mrs_roundtrip returning stale flags.
        if (sr < 256u) {
            if (sr & static_cast<unsigned>(ARM_SYSREG_CPSR_F)) write_f = true;
            if (sr & static_cast<unsigned>(ARM_SYSREG_CPSR_S)) write_s = true;
            is_spsr = sysreg_is_spsr(sr);
        } else {
            switch (sr) {
                case ARM_SYSREG_APSR:
                case ARM_SYSREG_APSR_NZCVQ:  write_f = true; break;
                case ARM_SYSREG_APSR_G:      write_s = true; break;
                case ARM_SYSREG_APSR_NZCVQG: write_f = write_s = true; break;
                default: break;   // an M-profile / banked name: inert here
            }
        }
    } else if (tgt.type == ARM_OP_REG) {
        switch (tgt.reg) {
            case ARM_REG_APSR:
            case ARM_REG_APSR_NZCV:
            case ARM_REG_CPSR:  write_f = true; write_s = true; break;
            case ARM_REG_SPSR:  is_spsr = true; break;
            default: break;    // FPSCR etc: VMSR, not this instruction
        }
    } else {
        return refuse("MSR destination operand class not recognised");
    }

    if (is_spsr || (!write_f && !write_s)) {
        // NOP: either an SPSR write from User mode (CONSTRAINED
        // UNPREDICTABLE, "executes as NOP" is a permitted behaviour) or a
        // mask that only selects the `x` / `c` bytes, which EL0 cannot
        // change. Translated, not skipped.
        return true;
    }

    // The value: a register, a plain immediate, or the 3-operand
    // (#imm8, #rot) modified-immediate shape.
    Op2 o;
    if (!decode_op2(d.operands[1],
                    d.op_count == 3 ? &d.operands[2] : nullptr, &o))
        return false;

    std::vector<int> owned;
    int val;
    if (o.is_imm) val = materialise_tmp(o.imm, &owned);
    else if (o.guest_reg == 15)
        return refuse("MSR from PC (UNPREDICTABLE: 'if n == 15 then "
                      "UNPREDICTABLE')");
    else val = regs_.read(o.guest_reg);

    const int sc = regs_.temp_alloc();
    owned.push_back(sc);
    const int t = regs_.temp_alloc();
    owned.push_back(t);

    if (write_f) {
        // N Z C V are bits 31..28 in both APSR and A64's NZCV register,
        // so the flag half installs with a bare MSR.
        sink_.msr_nzcv(val);
        // Q is bit 27.
        sink_.shift_imm(Sh::LSR, t, val, 27, false);
        sink_.alu_imm(AluOp::AND, t, t, 1ull, false, false);
        sink_.guest_state_store(t, kGuestStateApsrQOffset, sc);
    }
    if (write_s) {
        sink_.ubfx(t, val, 16, 4, false);          // GE[3:0]
        sink_.guest_state_store(t, kGuestStateApsrGeOffset, sc);
    }
    release(owned);
    return true;
}

// ---------------------------------------------------------------------
//  T32 table branches: TBB [Rn, Rm] / TBH [Rn, Rm, LSL #1]
// ---------------------------------------------------------------------
//
// Arm A32/T32 ISA, TBB/TBH, "Operation" (DDI 0406C.d A8.8.237):
//
//   if ConditionPassed() then
//       EncodingSpecificOperations();
//       integer halfwords;
//       if is_tbh then
//            halfwords = UInt(MemU[R[n]+LSL(R[m],1), 2]);
//       else
//            halfwords = UInt(MemU[R[n]+R[m], 1]);
//       BranchWritePC(PC32 + 2*halfwords, BranchType_INDIR);
//
// This is every `switch` in Thumb-2 code. Stubbing it emits NOTHING, so
// control falls straight into the inline jump table and the table BYTES
// execute as instructions; that is the single worst failure mode in the
// whole coverage list, and it is why libpng's png_create_read_struct_2
// mis-returns on the incumbent when its own TBB handling is bypassed.
//
// THE SHAPE OF THE LOWERING, and why it is not the incumbent's shape.
//
// arm32_lifter.cpp decodes the table at translate time and emits an LLVM
// `switch` on the INDEX register. This emitter cannot do that directly
// (there is no `switch`), and more importantly a translate-time-only
// lowering has to be RIGHT about the table extent or it silently
// mistranslates. So the table VALUE is read from guest memory at RUN
// time exactly as the pseudocode says, and the static decode is used
// only to enumerate which values can occur, i.e. only to build the
// dispatch. The consequences:
//
//   * a guest that rewrites its own table (or a relocation we did not
//     model) still branches where the hardware would;
//   * an index the static decode did not predict does not fall into
//     data, it goes to the guest-vaddr dispatcher, which logs;
//   * the emitted sequence never touches PSTATE, so guest NZCV survives
//     the branch. That matters because the `cmp Rm, #N ; bhi default`
//     that always precedes a TBB leaves live flags, and A64's CMP would
//     destroy them. Everything below is non-flag-setting ADD/SUB plus
//     CBZ, which reads no flags at all.
//
// The case targets are MID-FUNCTION addresses, and the driver's label
// pre-scan only mints labels for B / CBZ / CBNZ destinations, so this
// mints and binds its own (see case_labels_ in the header).

bool XlatCore::read_image_u8(uint32_t vaddr, uint32_t* out) const {
    if (!hooks_.read_image_u32) return false;
    uint32_t word = 0;
    const uint32_t base = vaddr & ~3u;
    if (!hooks_.read_image_u32(base, &word)) return false;
    *out = (word >> (8u * (vaddr & 3u))) & 0xffu;
    return true;
}

bool XlatCore::decode_table(const cs_insn* in, bool is_tbh,
                            TbbTable* out) const {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 1 || d.operands[0].type != ARM_OP_MEM) return false;
    const auto& mem = d.operands[0].mem;

    out->pc32 = addr_ + in->size;          // T32: PC32 == addr + 4
    out->entry_size = is_tbh ? 2u : 1u;

    if (mem.base == ARM_REG_PC) {
        // "The PC can be used. If it is, the table immediately follows
        // this instruction."
        out->table_addr = out->pc32;
    } else {
        // Register base: the compiler just set Rn PC-relatively, so scan
        // back a short window for that definition. Same recovery the
        // incumbent does (arm32_lifter.cpp decode_tbb_table), and the
        // same window.
        const int base_reg = guest_reg_of(mem.base);
        if (base_reg < 0 || !insns_) return false;
        bool found = false;
        const size_t lo = (idx_ > 8) ? idx_ - 8 : 0;
        for (size_t j = idx_; j-- > lo; ) {
            const cs_insn* pi = &insns_[j];
            if (!pi->detail) continue;
            const cs_arm& pd = pi->detail->arm;
            if (pd.op_count < 2 || pd.operands[0].type != ARM_OP_REG ||
                guest_reg_of(pd.operands[0].reg) != base_reg) continue;
            const uint32_t tpc = (static_cast<uint32_t>(pi->address) + 4u) & ~3u;
            if (pi->id == ARM_INS_ADD && pd.op_count == 3 &&
                pd.operands[1].type == ARM_OP_REG &&
                pd.operands[1].reg == ARM_REG_PC &&
                pd.operands[2].type == ARM_OP_IMM) {
                out->table_addr =
                    tpc + static_cast<uint32_t>(pd.operands[2].imm);
                found = true;
            } else if (pi->id == ARM_INS_ADR && pd.op_count == 2 &&
                       pd.operands[1].type == ARM_OP_IMM) {
                // Capstone hands ADR the absolute target already.
                out->table_addr = static_cast<uint32_t>(pd.operands[1].imm);
                found = true;
            }
            break;   // nearest definition wins, whatever it was
        }
        if (!found) return false;
    }

    // Extent. Two independent bounds, and the SMALLER wins:
    //
    //  (a) the bounding `cmp Rm, #N` the compiler emits before the TBB,
    //      which gives N+1 entries;
    //  (b) the table's own contents. Every entry points at code that
    //      lies AFTER the table, so once `table_addr + (i+1)*esize`
    //      would pass the lowest target seen so far, the table has
    //      ended. This is self-terminating and needs no pattern match,
    //      which is what makes it safe to use as the fallback where the
    //      incumbent instead GUESSES 8 (TBB) or 4 (TBH) entries.
    uint32_t cmp_count = 0;
    if (insns_) {
        const size_t lo = (idx_ > 6) ? idx_ - 6 : 0;
        for (size_t j = idx_; j-- > lo; ) {
            const cs_insn* c = &insns_[j];
            if (!c->detail) continue;
            const cs_arm& cd = c->detail->arm;
            if (c->id != ARM_INS_CMP || cd.op_count != 2 ||
                cd.operands[0].type != ARM_OP_REG ||
                cd.operands[0].reg != static_cast<arm_reg>(mem.index) ||
                cd.operands[1].type != ARM_OP_IMM) continue;
            const int64_t imm = cd.operands[1].imm;
            if (imm >= 0 && imm < 4096) cmp_count = static_cast<uint32_t>(imm) + 1;
            break;
        }
    }

    uint32_t min_target = 0xffffffffu;
    for (uint32_t i = 0; ; ++i) {
        if (cmp_count && i >= cmp_count) break;
        const uint32_t ent = out->table_addr + i * out->entry_size;
        if (ent + out->entry_size > min_target) break;
        uint32_t v = 0;
        if (out->entry_size == 1) {
            if (!read_image_u8(ent, &v)) break;
        } else {
            uint32_t b0 = 0, b1 = 0;
            if (!read_image_u8(ent, &b0) || !read_image_u8(ent + 1, &b1)) break;
            v = b0 | (b1 << 8);
        }
        const uint32_t target = out->pc32 + 2u * v;
        if (target <= out->pc32) break;         // a zero entry is padding
        if (target < min_target) min_target = target;
        out->values.push_back(v);
        if (out->values.size() >= 4096) break;  // sanity ceiling
    }
    out->entry_count = static_cast<uint32_t>(out->values.size());
    return out->entry_count > 0;
}

bool XlatCore::do_table_branch(const cs_insn* in, bool is_tbh,
                               bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 1 || d.operands[0].type != ARM_OP_MEM)
        return refuse("TBB/TBH operand is not [Rn, Rm]");
    const auto& mem = d.operands[0].mem;
    const int rn = guest_reg_of(mem.base);
    const int rm = guest_reg_of(mem.index);
    if (rn < 0 || rm < 0)
        return refuse("TBB/TBH base or index is not a core register");
    if (rm == 15)
        return refuse("TBB/TBH with Rm == PC (UNPREDICTABLE: 'if m == 15 "
                      "then UNPREDICTABLE')");

    ++tb_.sites;
    TbbTable t;
    const bool decoded = decode_table(in, is_tbh, &t);
    if (decoded) ++tb_.decoded;

    std::vector<int> owned;

    // 1. the guest address of the table entry, in a scratch.
    //    TBB: Rn + Rm.   TBH: Rn + LSL(Rm, 1).
    //    A PC base is a translate-time constant and carries the image's
    //    load bias, because this is a MEMORY address (control-flow
    //    lookups below use the unbiased vaddr; the two must not be mixed).
    const int addr = regs_.temp_alloc();
    owned.push_back(addr);
    const int idxr = regs_.read(rm);
    if (rn == 15) {
        // PC32 is NOT Align(PC,4)-ed here: the table follows the
        // instruction and the pseudocode adds R[n] directly.
        materialise_u32(addr, addr_ + size_ + opts_.load_bias);
        sink_.alu_reg(AluOp::ADD, addr, addr, idxr, Sh::LSL, is_tbh ? 1 : 0,
                      false, false);
    } else {
        sink_.alu_reg(AluOp::ADD, addr, regs_.read(rn), idxr, Sh::LSL,
                      is_tbh ? 1 : 0, false, false);
    }

    // 2. read the table entry from GUEST memory (the pseudocode's MemU).
    const int hw = regs_.temp_alloc();
    owned.push_back(hw);
    sink_.load_guest_zx(hw, addr, is_tbh ? 2 : 1);

    // 3. dispatch. Comparing the raw halfword COUNT rather than the
    //    computed target keeps the constants small (a TBB entry is a
    //    byte, so `sub wC, wH, #k` is always a single instruction) and
    //    stays flag-free. Distinct values only: two indices sharing a
    //    case share a target.
    if (decoded) {
        const int cmpt = regs_.temp_alloc();
        owned.push_back(cmpt);
        std::vector<uint32_t> seen;
        for (uint32_t v : t.values) {
            bool dup = false;
            for (uint32_t s : seen) if (s == v) { dup = true; break; }
            if (dup) continue;
            seen.push_back(v);
            const uint32_t target = t.pc32 + 2u * v;   // UNBIASED guest vaddr
            Label l;
            auto ex = case_labels_.find(target);
            if (ex != case_labels_.end()) {
                // Already minted by an earlier table in this function. It
                // may or may not be bound yet; A64Buf resolves backward
                // and forward references identically, so either is fine.
                l = ex->second;
            } else if (hooks_.label_for_guest &&
                       hooks_.label_for_guest(target, &l) && l.valid()) {
                // The driver already minted one (some B in this function
                // targets the same address).
            } else {
                auto ai = addr_index_.find(target);
                if (ai == addr_index_.end() || ai->second <= idx_) {
                    if (ai == addr_index_.end() && hooks_.extern_tail_target &&
                        hooks_.extern_tail_target(target)) {
                        // The case lives in ANOTHER translated extent (one
                        // real function that discovery cut in pieces: a
                        // bytecode interpreter's opcode switch is the usual
                        // shape). Branch there directly; the driver keeps the
                        // frame and every register (MACAPK_ARM32RC_FRAME_JUMP).
                        const Label skip = sink_.new_label();
                        if (v == 0) {
                            sink_.cbnz(hw, skip, false);
                        } else if (a64_encodable_add_imm(v)) {
                            sink_.alu_imm(AluOp::SUB, cmpt, hw, v, false, false);
                            sink_.cbnz(cmpt, skip, false);
                        } else {
                            materialise_u32(cmpt, v);
                            sink_.alu_reg(AluOp::SUB, cmpt, hw, cmpt, Sh::LSL, 0,
                                          false, false);
                            sink_.cbnz(cmpt, skip, false);
                        }
                        sink_.tail_branch_direct(target);
                        sink_.bind(skip);
                        ++tb_.cases_labelled;
                        continue;
                    }
                    // Not a decoded instruction we are still going to
                    // visit, so a label here could never be bound and
                    // would make A64Buf::resolve() drop the whole
                    // function. Fall through to the dispatcher instead.
                    //
                    // This is the one way the table lowering degrades, and
                    // it is worth seeing rather than counting silently: it
                    // means Capstone desynchronised on the table bytes (it
                    // decodes them as instructions, and nothing in this
                    // stage can tell the driver to skip them), so the case
                    // target no longer lands on an instruction boundary.
                    ++tb_.cases_offstream;
                    if (rc_stub_trace_enabled()) {
                        char buf[128];
                        std::snprintf(buf, sizeof(buf),
                                      "TBB/TBH case target 0x%08x is not a "
                                      "decoded instruction boundary; routed "
                                      "to the guest-vaddr dispatcher",
                                      target);
                        rc_stub_trace(addr_, opts_.thumb, in,
                                      "core-tb-offstream", buf);
                    }
                    continue;
                }
                l = sink_.new_label();
                case_labels_[target] = l;
            }
            ++tb_.cases_labelled;
            if (v == 0) {
                sink_.cbz(hw, l, false);
            } else if (a64_encodable_add_imm(v)) {
                sink_.alu_imm(AluOp::SUB, cmpt, hw, v, false, false);
                sink_.cbz(cmpt, l, false);
            } else {
                // A TBH entry is a 16-bit halfword count, so it can exceed
                // what A64's 12-bit ADD/SUB immediate holds. BufSink's
                // alu_imm ASSUMES encodability (it derives the shift-by-12
                // form from `imm > 0xfff` alone), so an unencodable value
                // has to be materialised instead of handed to it.
                materialise_u32(cmpt, v);
                sink_.alu_reg(AluOp::SUB, cmpt, hw, cmpt, Sh::LSL, 0, false,
                              false);
                sink_.cbz(cmpt, l, false);
            }
        }
    }

    // 4. fallback: compute the architectural target and go through the
    //    guest-vaddr dispatcher. Reached when the static decode failed,
    //    when a case target was not on an instruction boundary, or when
    //    the runtime index is outside the table the compiler declared.
    //    The dispatcher logs an unresolved in-image vaddr instead of
    //    executing table bytes, which is the whole point.
    const int tgt = regs_.temp_alloc();
    owned.push_back(tgt);
    materialise_u32(tgt, t.pc32 ? (t.pc32 + opts_.load_bias)
                                : (addr_ + size_ + opts_.load_bias));
    sink_.alu_reg(AluOp::ADD, tgt, tgt, hw, Sh::LSL, 1, false, false);
    sink_.tail_branch_indirect(tgt);
    release(owned);
    *terminated = true;
    return true;
}

// ---------------------------------------------------------------------
//  Control flow
// ---------------------------------------------------------------------

void XlatCore::emit_branch_to_guest(uint32_t target, bool is_bl_provenance) {
    Label l;
    if (hooks_.label_for_guest && hooks_.label_for_guest(target, &l) &&
        l.valid()) {
        sink_.b(l);
        return;
    }
    if (hooks_.note_extern_target) hooks_.note_extern_target(target,
                                                             is_bl_provenance);
    sink_.tail_branch_direct(target);
}

// Guest NZCV lives in host PSTATE (assumption A3) and PSTATE does not
// survive a host call, but A32 code may legitimately read flags set
// before a BL (the LLVM lifter's per-function flag allocas preserved
// them). Bracket the call unless the ABI layer already does.
void XlatCore::emit_call_bracketed(uint32_t target) {
    if (opts_.calls_preserve_nzcv) { sink_.call_direct(target); return; }
    std::vector<int> owned;
    int f = flags_save(&owned);
    sink_.call_direct(target);
    sink_.msr_nzcv(f);
    release(owned);
}

void XlatCore::emit_call_indirect_bracketed(int host_reg) {
    if (opts_.calls_preserve_nzcv) { sink_.call_indirect(host_reg); return; }
    std::vector<int> owned;
    int f = flags_save(&owned);
    sink_.call_indirect(host_reg);
    sink_.msr_nzcv(f);
    release(owned);
}

bool XlatCore::do_branch(const cs_insn* in, bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 1 || d.operands[0].type != ARM_OP_IMM) return false;
    uint32_t target = static_cast<uint32_t>(d.operands[0].imm);
    const arm_cc cc = static_cast<arm_cc>(d.cc);

    if (cc_is_always(cc)) {
        emit_branch_to_guest(target, false);
        *terminated = true;
        return true;
    }

    // A64 has native conditional branches with the same condition
    // encodings, so a predicated B needs no branch-over.
    Cond c = map_cond(cc);
    Label l;
    if (hooks_.label_for_guest && hooks_.label_for_guest(target, &l) &&
        l.valid()) {
        sink_.b_cond(c, l);
    } else {
        if (hooks_.note_extern_target) hooks_.note_extern_target(target, false);
        Label fall = sink_.new_label();
        sink_.b_cond(invert(c), fall);
        sink_.tail_branch_direct(target);
        sink_.bind(fall);
    }
    *terminated = false;   // the fall-through path continues
    return true;
}

bool XlatCore::do_bl(const cs_insn* in, bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count < 1 || d.operands[0].type != ARM_OP_IMM) return false;
    uint32_t target = static_cast<uint32_t>(d.operands[0].imm);
    uint32_t ret_addr = addr_ + size_;

    // BL is predicable on A32. arm32_lifter.cpp excluded BL from its
    // predicate machinery AND ignored cc inside emit_bl, so `blne foo`
    // was lifted as an unconditional call. Handle it properly.
    PredScope ps = pred_begin(map_cond(static_cast<arm_cc>(d.cc)));

    // LR is written as data (returns themselves are host returns), which
    // is why it carries the per-image load bias like every other
    // materialised guest vaddr.
    //
    // v1.0.833: bit 0 of LR is the ISA of the RETURN SITE, i.e. the
    // CALLER's own mode, never the callee's. Verified against LLDB's
    // EmulateInstructionARM.cpp (which embeds the ARM ARM pseudocode
    // verbatim) and QEMU's target/arm/tcg/translate.c, which agree for BL,
    // BLX(imm) and BLX(reg) alike. We had always written it even, so a
    // Thumb caller's LR was wrong in the low bit. Nothing on the return
    // path noticed, because `bx lr` / `mov pc,lr` / `pop {..,pc}` never
    // read r14's materialised value at all -- they lower to a host `ret`.
    // It matters to guest code that INSPECTS lr & 1, which is what ARM
    // EHABI unwinders do.
    int lr = regs_.write_begin(14);
    materialise_u32(lr, (ret_addr + opts_.load_bias) | (opts_.thumb ? 1u : 0u));
    regs_.write_end(14);

    if (hooks_.note_extern_target) hooks_.note_extern_target(target, true);
    emit_call_bracketed(target);
    pred_end(ps);
    *terminated = false;
    return true;
}

// ---------------------------------------------------------------------
// Intra-function computed-jump ladders (v1.0.809)
// ---------------------------------------------------------------------
//
// arm32rc's ONLY notion of a computed (register-indirect) branch used to
// be tail_branch_indirect() -> __macapk_indirect_call, which resolves the
// runtime target against the module-wide FUNCTION LUT. That is correct
// for a genuine indirect CALL (a vtable slot, a function pointer), but a
// compiler emits several idioms whose "computed branch" is a jump to one
// of the CURRENT FUNCTION'S OWN basic blocks -- a target that is
// mid-function by construction, so it can never be a LUT entry and the
// dispatcher's only recourse is the "in-image vaddr" panic path, which
// returns 0 with no crash and no error. Confirmed instance: ShatteredPD's
// `libgdx.so` (armeabi-v7a) guest vaddr 0x2653c, a compiler-generated
// software divider (the classic clz-based shift-and-subtract ladder --
// this exact shape is emitted by both GCC's and Clang/compiler-rt's ARM
// `__udivsi3`/`__aeabi_uidiv` when the target has no hardware integer
// divide), verified by hand-decoding the disassembly and cross-checked
// with debug/arm32rc_replay.py (Unicorn ground truth):
//
//     26560: add r12, pc, #400        ; ADR-shaped base (A2.3: "ADD Rd,
//                                     ; PC, #imm IS the ADR form",
//                                     ; reads Align(PC,4))
//     26564: sub r12, r12, r3, lsl #2 ; net coefficient accumulates
//     26568: sub r12, r12, r3, lsl #3 ;   across the chain: -4 + -8 = -12
//     2656c: mov r3, #0               ; (harmless: resets the ACCUMULATOR
//                                     ;  r3 will use in the ladder, after
//                                     ;  r12's address computation above
//                                     ;  already consumed r3's OLD value)
//     26570: bx  r12                  ; r12 = 0x266f8 - r3_old*12
//
// r3_old = clz(r1) - clz(r0) (a shift-count difference the two guard
// compares upstream bound to 0..31), so the 32 possible targets are
// exactly the ladder's 32 unrolled `cmp/addhs/subhs` rungs at
// 0x26584..0x26700 -- every one a real, already-discovered instruction
// boundary INSIDE this same function, never a function entry. Observed
// r0=0x7fffffff r1=4 -> r3_old=28 -> target 0x265a8 (confirmed against
// the disassembly: the shift-#28 rung), and the CORRECT quotient is
// 0x1fffffff; the unfixed dispatcher instead panics and returns 0.
//
// See docs/arm32rc-computed-intrafunction-jump_2026-07-28.md for the
// full derivation, the fleet-impact scan, and the before/after replay.

bool XlatCore::match_pc_ladder(int rx, uint32_t* base_out,
                               int32_t* coeff_out,
                               int* index_reg_out) const {
    if (opts_.thumb) return false;   // Thumb's PC base is ADR, not this.
    if (rx < 0 || rx == 15) return false;

    int32_t coeff = 0;
    int index_reg = -1;
    static constexpr int kWindow = 12;   // generous: the confirmed case
                                        // needs 4 (2 chain steps + the
                                        // anchor + one harmless skip).
    for (int back = 1; back <= kWindow && static_cast<size_t>(back) <= idx_;
         ++back) {
        const cs_insn& p = insns_[idx_ - static_cast<size_t>(back)];
        if (p.id == 0 || !p.detail) return false;
        const cs_arm& pd = p.detail->arm;

        // Any control flow between the anchor and the bx invalidates the
        // straight-line assumption this match depends on.
        if (p.id == ARM_INS_B || p.id == ARM_INS_BL || p.id == ARM_INS_BX ||
            p.id == ARM_INS_BLX || p.id == ARM_INS_CBZ ||
            p.id == ARM_INS_CBNZ)
            return false;

        const bool is_add = p.id == ARM_INS_ADD;
        const bool is_sub = p.id == ARM_INS_SUB;

        // `{add,sub} rX, rX, rY, lsl #k` -- one ladder-coefficient step.
        // (No update_flags requirement: ADDS/SUBS here would not change
        // the VALUE prediction, only flag side effects this match does
        // not touch or rely on. A PREDICATED add/sub, though, executes
        // conditionally, so it would make the predicted value wrong on
        // whichever path skips it -- required unconditional here; a
        // predicated one falls through to the redefinition check below,
        // which correctly aborts the match since it still writes rX.)
        if (cc_is_always(static_cast<arm_cc>(pd.cc)) &&
            (is_add || is_sub) && pd.op_count == 3 &&
            pd.operands[0].type == ARM_OP_REG &&
            guest_reg_of(pd.operands[0].reg) == rx &&
            pd.operands[1].type == ARM_OP_REG &&
            guest_reg_of(pd.operands[1].reg) == rx &&
            pd.operands[2].type == ARM_OP_REG) {
            const int ry = guest_reg_of(pd.operands[2].reg);
            if (ry < 0 || ry == 15) return false;
            if (index_reg < 0) index_reg = ry;
            else if (ry != index_reg) return false;   // a second index reg
            unsigned k = 0;
            if (pd.operands[2].shift.type == ARM_SFT_LSL) {
                k = static_cast<unsigned>(pd.operands[2].shift.value);
            } else if (pd.operands[2].shift.type != ARM_SFT_INVALID) {
                return false;   // ROR/ASR/RRX: the affine formula does
                                // not generalise to these.
            }
            if (k > 30) return false;   // guard the shift below
            const int32_t contrib = static_cast<int32_t>(1u << k);
            coeff += is_add ? contrib : -contrib;
            continue;
        }

        // Anchor: `add rX, pc, #imm` -- the ADR-shaped base (do_alu's
        // rn==15 path applies the identical Align(PC,4) rule to this
        // exact instruction shape; reused via pc_value_for(..., true)).
        // Unconditional only, for the same reason as the chain step above.
        if (cc_is_always(static_cast<arm_cc>(pd.cc)) &&
            is_add && pd.op_count == 3 &&
            pd.operands[0].type == ARM_OP_REG &&
            guest_reg_of(pd.operands[0].reg) == rx &&
            pd.operands[1].type == ARM_OP_REG &&
            guest_reg_of(pd.operands[1].reg) == 15 &&
            pd.operands[2].type == ARM_OP_IMM) {
            if (index_reg < 0) return false;   // no coefficient chain
                                                // at all: not this idiom.
            const uint32_t anchor_addr = static_cast<uint32_t>(p.address);
            // UNBIASED (v1.0.818): *base_out feeds ladder_candidate_label,
            // an addr_index_ lookup keyed by the raw per-image vaddr.
            // emit_ladder_dispatch_by_target re-applies opts_.load_bias
            // when it needs the RUNTIME value to compare against.
            const uint32_t base =
                pc_value_for_lookup(anchor_addr, /*aligned=*/true) +
                static_cast<uint32_t>(pd.operands[2].imm);
            *base_out = base;
            *coeff_out = coeff;
            *index_reg_out = index_reg;
            return true;
        }

        // Neither shape: if this instruction WRITES rX or the (already
        // identified) index register, the value this match is trying to
        // predict is not what it thinks it is -- abandon. Anything else
        // (a read, or a write to an unrelated register, like the `mov
        // r3, #0` that resets the accumulator AFTER r12's address is
        // fully computed in the confirmed case above) is harmless:
        // keep scanning backward.
        if (pd.op_count > 0 && pd.operands[0].type == ARM_OP_REG) {
            const int wr = guest_reg_of(pd.operands[0].reg);
            if (wr == rx || (index_reg >= 0 && wr == index_reg))
                return false;
        }
    }
    return false;   // window exhausted without finding the anchor
}

XlatCore::CandidateStatus XlatCore::ladder_candidate_status(uint32_t candidate,
                                                             Label* out) {
    // Proof of "in-function": a real decoded instruction boundary in THIS
    // function's own stream (addr_index_) -- exactly the evidence the
    // TBB/TBH case-target lookup above already requires. Whether it is
    // still reachable for a label BIND depends on direction: binding only
    // happens as translate()'s preamble walks the function forward, so an
    // address behind `idx_` can never be bound (see the enum's own
    // comment in xlat_core.h for why, and emit_table_offset_dispatch for
    // the confirmed case -- gdx2d_draw_pixmap -- that makes this a real
    // rather than theoretical distinction).
    auto ai = addr_index_.find(candidate);
    if (ai == addr_index_.end()) return CandidateStatus::NotFound;
    if (ai->second <= idx_) return CandidateStatus::Backward;

    auto ex = case_labels_.find(candidate);
    if (ex != case_labels_.end()) {
        *out = ex->second;                // already minted for this address
        return CandidateStatus::Forward;
    }
    if (hooks_.label_for_guest && hooks_.label_for_guest(candidate, out) &&
        out->valid()) {
        // the driver's own pre-scan already minted one (some direct B
        // in this function also targets this address)
        return CandidateStatus::Forward;
    }
    *out = sink_.new_label();
    case_labels_[candidate] = *out;
    return CandidateStatus::Forward;
}

bool XlatCore::ladder_candidate_label(uint32_t candidate, Label* out) {
    // Self-terminate on anything but Forward, mirroring every other
    // table scan in this file: a false-positive match (wrong base/coeff)
    // almost always fails at i=0. Existing callers' targets are, by the
    // idiom they each recognise, always forward of the dispatch, so
    // collapsing Backward into "stop" here is not a behaviour change for
    // them -- it is what this function already did before the tri-state
    // split.
    return ladder_candidate_status(candidate, out) == CandidateStatus::Forward;
}

uint32_t XlatCore::emit_affine_ladder_dispatch(int index_reg, uint32_t base,
                                               int32_t coeff,
                                               uint32_t max_rungs) {
    uint32_t emitted = 0;
    const int idxv = regs_.read(index_reg);
    for (uint32_t i = 0; i < max_rungs; ++i) {
        const int64_t offset =
            static_cast<int64_t>(coeff) * static_cast<int64_t>(i);
        const uint32_t candidate = base + static_cast<uint32_t>(offset);
        Label l;
        if (!ladder_candidate_label(candidate, &l)) break;
        sink_.alu_imm(AluOp::SUB, kZR, idxv, static_cast<uint64_t>(i), true,
                     false);
        sink_.b_cond(Cond::EQ, l);
        ++emitted;
    }
    return emitted;
}

uint32_t XlatCore::emit_ladder_dispatch_by_target(int branch_reg,
                                                  uint32_t base,
                                                  int32_t coeff,
                                                  uint32_t max_rungs) {
    uint32_t emitted = 0;
    // Read ONCE: this is the value the `bx`/indirect-write instruction
    // itself is about to branch to, so unlike an "index" register
    // extracted from an earlier chain step, it cannot have gone stale --
    // there is no instruction between "this value is read" and "this
    // value is the branch target" for the caller to have clobbered it
    // with (they are the same instruction).
    const int regv = regs_.read(branch_reg);
    for (uint32_t i = 0; i < max_rungs; ++i) {
        const int64_t offset =
            static_cast<int64_t>(coeff) * static_cast<int64_t>(i);
        // `base` is UNBIASED (match_pc_ladder returns it that way, see
        // pc_value_for_lookup) -- correct for the addr_index_-backed
        // lookup below, which is keyed by the raw per-image vaddr.
        const uint32_t candidate = base + static_cast<uint32_t>(offset);
        Label l;
        if (!ladder_candidate_label(candidate, &l)) break;
        // Candidates are full 32-bit guest addresses (e.g. 0x265a8), far
        // outside A64's ADD/SUB immediate range, so -- unlike the
        // index-based dispatch above -- this needs a materialised
        // constant and a register-register compare. `regv` is what the
        // GUEST actually computed at runtime, which -- unlike `candidate`
        // -- IS biased (rX was built from a materialised, biased PC
        // value), so the compare needs the biased form of the candidate
        // too (v1.0.818); the label lookup just above stays unbiased.
        const uint32_t candidate_biased = candidate + opts_.load_bias;
        const int cmpt = regs_.temp_alloc();
        materialise_u32(cmpt, candidate_biased);
        sink_.alu_reg(AluOp::SUB, kZR, regv, cmpt, Sh::LSL, 0, true, false);
        regs_.temp_free(cmpt);
        sink_.b_cond(Cond::EQ, l);
        ++emitted;
    }
    return emitted;
}

// ---------------------------------------------------------------------
// Intra-function computed jump via an intermediate-register PC table
// (v1.0.812)
// ---------------------------------------------------------------------
//
// A THIRD idiom: `add pc, rBASE, rOFF` where `rBASE` is an ORDINARY
// register set two instructions earlier by `add rBASE,pc,#K`, and `rOFF`
// came from `ldr rOFF,[rBASE,rIDX,lsl#2]` immediately before the add --
// i.e. the compiler's PC-relative jump table with the base+read staged
// through an intermediate register instead of one `add pc,pc,rN,lsl#k`.
// Confirmed in ShatteredPD's `libgdx.so`:
//
//     gdx2d_get_pixel:
//       9d00: add  r4, pc, #8              ; anchor: r4 = (0x9d00+8)+8 = 0x9d10
//       9d08: ldr  r3, [r4, r3, lsl #2]     ; r3 = MEM32[0x9d10 + r3*4]
//       9d0c: add  pc, r4, r3               ; pc = 0x9d10 + r3
//       9d10: .word 0x18, 0x2c, 0x68, 0x7c, 0x2c, 0x2c   ; SIGNED offsets
//
//     gdx2d_draw_pixmap:
//       1da14: add  r7, pc, #4              ; r7 = (0x1da14+8)+4 = 0x1da20
//       1da18: ldr  r4, [r7, r1, lsl #2]
//       1da1c: add  pc, r7, r4
//       1da20: .word 0xffffffd0(-48), 0xffffffd0(-48), 0x1c, 0x2c, 0x1c,
//                    0xffffffd0(-48), 0xffffffd0(-48)   ; some targets
//                                                          BEFORE the table
//
// Both tables were read directly from the image and EVERY entry (within
// the guest's own `cmp rIDX,#N; bhi fallback` bound) resolves to a real
// instruction boundary inside the same function -- confirmed by hand for
// all 6 and all 7 entries respectively before this code was written.
//
// This is NOT the "ldr pc, [pc, rN, lsl #2]" shape it was first reported
// as: there is no LDR anywhere that writes PC directly in either
// confirmed instance. The LDR here writes an ORDINARY register (rOFF);
// the ADD that follows is what writes PC, with Rn (rBASE) an ordinary
// register too, not PC itself -- which is exactly why the existing
// `rn==15` special case in do_alu (the ARM b-table scan) does not catch
// it, and why this lives in do_alu's GENERIC rd==15 fallback rather than
// needing any change to xlat_mem.cpp (there is no PC-writing LDR to fix).

bool XlatCore::match_pc_table_ref(size_t at_idx, int rn, int r_off,
                                  uint32_t* table_base_out) const {
    if (opts_.thumb) return false;
    if (rn < 0 || rn == 15 || r_off < 0 || r_off == 15) return false;

    static constexpr int kWindow = 6;   // confirmed cases need exactly 2
                                        // (the ldr, then the anchor).
    bool have_ldr = false;
    for (int back = 1; back <= kWindow && static_cast<size_t>(back) <= at_idx;
         ++back) {
        const cs_insn& p = insns_[at_idx - static_cast<size_t>(back)];
        if (p.id == 0 || !p.detail) return false;
        const cs_arm& pd = p.detail->arm;

        if (p.id == ARM_INS_B || p.id == ARM_INS_BL || p.id == ARM_INS_BX ||
            p.id == ARM_INS_BLX || p.id == ARM_INS_CBZ ||
            p.id == ARM_INS_CBNZ)
            return false;

        const bool always = cc_is_always(static_cast<arm_cc>(pd.cc));

        if (!have_ldr) {
            // `ldr rOFF, [rn, rIDX, lsl #2]` -- the table read. The scale
            // MUST be lsl #2 (a table of 32-bit words) and there must be
            // no additional immediate displacement folded in (a register-
            // offset addressing mode cannot carry one anyway, but be
            // explicit rather than assume).
            if (always && p.id == ARM_INS_LDR && pd.op_count == 2 &&
                pd.operands[0].type == ARM_OP_REG &&
                guest_reg_of(pd.operands[0].reg) == r_off &&
                pd.operands[1].type == ARM_OP_MEM &&
                guest_reg_of(pd.operands[1].mem.base) == rn &&
                pd.operands[1].mem.index != ARM_REG_INVALID &&
                pd.operands[1].shift.type == ARM_SFT_LSL &&
                pd.operands[1].shift.value == 2 &&
                pd.operands[1].mem.disp == 0) {
                have_ldr = true;
                continue;
            }
        } else {
            // Anchor: `add rn, pc, #imm` -- the ADR-shaped base, same
            // Align(PC,4) rule as match_pc_ladder's anchor.
            if (always && p.id == ARM_INS_ADD && pd.op_count == 3 &&
                pd.operands[0].type == ARM_OP_REG &&
                guest_reg_of(pd.operands[0].reg) == rn &&
                pd.operands[1].type == ARM_OP_REG &&
                guest_reg_of(pd.operands[1].reg) == 15 &&
                pd.operands[2].type == ARM_OP_IMM) {
                const uint32_t anchor_addr = static_cast<uint32_t>(p.address);
                // UNBIASED (v1.0.818): *table_base_out feeds
                // hooks_.read_image_u32 and addr_index_/case_labels_
                // lookups in prescan_pc_tables() / emit_table_offset_
                // dispatch, all keyed by the raw per-image vaddr. The
                // latter re-applies opts_.load_bias when it needs the
                // RUNTIME value to compare against.
                *table_base_out =
                    pc_value_for_lookup(anchor_addr, /*aligned=*/true) +
                    static_cast<uint32_t>(pd.operands[2].imm);
                return true;
            }
        }

        // Neither shape matched: abort if this instruction redefines a
        // register the match still depends on (rn always matters; r_off
        // only matters until the ldr that explains it has been found).
        if (pd.op_count > 0 && pd.operands[0].type == ARM_OP_REG) {
            const int wr = guest_reg_of(pd.operands[0].reg);
            if (wr == rn || (!have_ldr && wr == r_off)) return false;
        }
    }
    return false;   // window exhausted without finding both pieces
}

void XlatCore::prescan_pc_tables() {
    if (opts_.thumb || !hooks_.read_image_u32) return;
    for (size_t i = 0; i < count_; ++i) {
        const cs_insn& in = insns_[i];
        // Mirrors do_alu's `add pc,rn,rOff` recognition (rd==15, rn an
        // ordinary register, operand2 an ordinary register with no
        // shift, not flag-setting) directly against raw capstone fields
        // -- see this function's header comment in xlat_core.h for why
        // it cannot simply call into do_alu's own Op2-based check.
        if (in.id != ARM_INS_ADD || !in.detail) continue;
        const cs_arm& d = in.detail->arm;
        if (d.update_flags || d.op_count != 3) continue;
        if (d.operands[0].type != ARM_OP_REG ||
            guest_reg_of(d.operands[0].reg) != 15)
            continue;
        if (d.operands[1].type != ARM_OP_REG) continue;
        const int rn = guest_reg_of(d.operands[1].reg);
        if (rn < 0 || rn == 15) continue;
        if (d.operands[2].type != ARM_OP_REG) continue;
        const int r_off = guest_reg_of(d.operands[2].reg);
        if (r_off < 0 || r_off == 15) continue;
        // "No shift": decode_op2 treats a bare register and an explicit
        // `lsl #0` identically (xlat_core.cpp:752-753), so both count.
        const arm_shifter st = d.operands[2].shift.type;
        if (!(st == ARM_SFT_INVALID ||
              (st == ARM_SFT_LSL && d.operands[2].shift.value == 0)))
            continue;

        uint32_t table_base = 0;
        if (!match_pc_table_ref(i, rn, r_off, &table_base)) continue;

        // Read the SAME table emit_table_offset_dispatch will read later,
        // in BOTH directions relative to `i` -- there is no "idx_" yet
        // for direction to even mean anything at prescan time, which is
        // the whole point. Stop at the first entry that is not a real
        // decoded instruction boundary in this function (addr_index_),
        // mirroring every other self-terminating table scan in this
        // file; a forward OR backward entry that passes gets a label
        // minted (or reused, if some other table in this function
        // already wanted the same address) immediately.
        for (uint32_t k = 0; k < 512u; ++k) {
            uint32_t raw = 0;
            if (!hooks_.read_image_u32(table_base + k * 4u, &raw)) break;
            const uint32_t candidate = table_base + raw;
            if (addr_index_.find(candidate) == addr_index_.end()) break;
            if (case_labels_.find(candidate) == case_labels_.end())
                case_labels_[candidate] = sink_.new_label();
        }
    }
}

uint32_t XlatCore::emit_table_offset_dispatch(int host_target_reg,
                                              uint32_t table_base,
                                              uint32_t max_entries) {
    if (!hooks_.read_image_u32) return 0;
    uint32_t emitted = 0;
    for (uint32_t i = 0; i < max_entries; ++i) {
        uint32_t raw = 0;
        if (!hooks_.read_image_u32(table_base + i * 4u, &raw)) break;
        // `raw` is a SIGNED offset from the table's own base (confirmed:
        // some entries in the gdx2d_draw_pixmap table are negative,
        // targets BEFORE the table); uint32_t arithmetic wraps correctly
        // either way. `table_base` is UNBIASED (match_pc_table_ref
        // returns it that way, see pc_value_for_lookup) -- correct here,
        // since read_image_u32 and case_labels_/addr_index_ are keyed by
        // the raw per-image vaddr.
        const uint32_t candidate = table_base + raw;
        // prescan_pc_tables() already read this identical table (from
        // set_stream(), before this function's own translation began)
        // and pre-minted a label in case_labels_ for every entry that is
        // a real instruction boundary, in EITHER direction -- so "not
        // present here" means prescan_pc_tables() stopped at this same
        // index for the same reason (genuinely past the table's end),
        // not merely "backward". A label found here may already be BOUND
        // (a backward target, bound when translate() walked past its
        // address earlier in this same function) or not yet (forward);
        // both are equally legal branch references to A64Buf.
        auto ex = case_labels_.find(candidate);
        if (ex == case_labels_.end()) break;
        const Label l = ex->second;
        // `host_target_reg` holds what the GUEST actually computed
        // (rBASE+rOFF via emit_alu_generic, built from a materialised,
        // BIASED PC value) -- unlike `candidate`, that IS biased, so the
        // compare needs the biased form (v1.0.818); case_labels_/
        // read_image_u32 just above stay unbiased.
        const uint32_t candidate_biased = candidate + opts_.load_bias;
        const int cmpt = regs_.temp_alloc();
        materialise_u32(cmpt, candidate_biased);
        sink_.alu_reg(AluOp::SUB, kZR, host_target_reg, cmpt, Sh::LSL, 0, true,
                     false);
        regs_.temp_free(cmpt);
        sink_.b_cond(Cond::EQ, l);
        ++emitted;
    }
    return emitted;
}

bool XlatCore::do_bx(const cs_insn* in, bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 1) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    int r = guest_reg_of(d.operands[0].reg);
    if (r < 0) return false;

    const bool predicated = !cc_is_always(static_cast<arm_cc>(d.cc));

    // THUMB INLINE JUMP TABLE, the Thumb-2 counterpart of the ARM
    // `add pc, pc, rN, lsl #k` table handled in do_alu (v1.0.785):
    //
    //     adr   rB, #imm                 ; rB = Align(PC,4) + imm = table base
    //     ldr.w rV, [rB, rI, lsl #2]     ; rV = table[rI]  (a 32-bit OFFSET)
    //     add   rB, rV                   ; rB = base + offset
    //     bx    rB
    //
    // Targets are MID-FUNCTION switch cases, never function starts, so the
    // generic tail_branch_indirect path resolves them through the function
    // LUT, misses every time, and panics. Same failure the ARM table had.
    //
    // HCR 1.43: libxml2's xmlNodeGetContent dispatches its node-type switch
    // exactly this way (table at 0x2af864). That single site accounted for 6
    // of the 8 remaining indirect-call panics, and it gates XML parsing, hence
    // UserDefault.xml, hence the whole asset load.
    if (!predicated && opts_.thumb && r >= 0 &&
        hooks_.label_for_guest && hooks_.read_image_u32) {
        // Walk back a few instructions for the adr/ldr pair that built rB.
        // Bounded and conservative: any redefinition of a register we depend
        // on, or any control flow, abandons the match.
        int      idx_reg  = -1;
        uint32_t tbl_base = 0;
        bool     have_ldr = false, have_adr = false;
        int      base_reg = -1;
        for (int back = 1; back <= 6 && static_cast<size_t>(back) <= idx_;
             ++back) {
            const cs_insn& p = insns_[idx_ - static_cast<size_t>(back)];
            if (!p.detail) break;
            const cs_arm& pd = p.detail->arm;
            if (p.id == ARM_INS_B || p.id == ARM_INS_BL || p.id == ARM_INS_BX ||
                p.id == ARM_INS_BLX || p.id == ARM_INS_CBZ ||
                p.id == ARM_INS_CBNZ) break;
            // `add rB, rV` -- the add that produced the branch register.
            if (!have_ldr && p.id == ARM_INS_ADD && pd.op_count == 2 &&
                pd.operands[0].type == ARM_OP_REG &&
                guest_reg_of(pd.operands[0].reg) == r) {
                continue;   // rV identified implicitly by the ldr below
            }
            // `ldr.w rV, [rB, rI, lsl #2]`
            // The scale MUST be `lsl #2`. Thumb-1's only register-offset
            // load, `LDR Rd,[Rb,Ro]`, is UNSCALED and otherwise satisfies
            // every clause here -- 626 candidates in Jetpack Joyride alone,
            // an ARMv5TE/Thumb-1 binary that has no Thumb-2 jump tables at
            // all. Without this check the emitter would then read the "table"
            // at a hardcoded 4-byte stride. It is bounded by label_for_guest
            // succeeding, so it mostly falls through harmlessly, but a
            // harmless-in-practice mismatch is still the wrong shape.
            if (!have_ldr && p.id == ARM_INS_LDR && pd.op_count == 2 &&
                pd.operands[1].type == ARM_OP_MEM &&
                pd.operands[1].mem.index != ARM_REG_INVALID &&
                pd.operands[1].shift.type == ARM_SFT_LSL &&
                pd.operands[1].shift.value == 2 &&
                pd.operands[1].mem.disp == 0) {
                base_reg = guest_reg_of(pd.operands[1].mem.base);
                idx_reg  = guest_reg_of(pd.operands[1].mem.index);
                if (base_reg < 0 || idx_reg < 0) break;
                have_ldr = true;
                continue;
            }
            // `adr rB, #imm` -- Align(PC,4) + imm (see do_adr).
            if (have_ldr && p.id == ARM_INS_ADR && pd.op_count == 2 &&
                pd.operands[0].type == ARM_OP_REG &&
                guest_reg_of(pd.operands[0].reg) == base_reg &&
                pd.operands[1].type == ARM_OP_IMM) {
                const uint32_t ppc =
                    (static_cast<uint32_t>(p.address) + 4u) & ~3u;
                tbl_base = ppc + static_cast<uint32_t>(
                                     static_cast<int32_t>(pd.operands[1].imm));
                have_adr = true;
                break;
            }
        }
        if (have_adr && have_ldr && idx_reg >= 0) {
            // Entries are 32-bit offsets FROM the table base. Stop at the
            // first entry that does not land inside the image, which
            // self-terminates the table without needing its length.
            std::vector<Label> slots;
            for (uint32_t i = 0; i < 512u; ++i) {
                uint32_t off = 0;
                Label l{};
                if (!hooks_.read_image_u32(tbl_base + i * 4u, &off)) break;
                const uint32_t tgt = (tbl_base + off) & ~1u;
                // Backward targets are legal (a switch into an unrolled
                // sequence puts its cases before the dispatch), so the label
                // lookup below is what terminates the table.
                if (tgt == tbl_base) break;
                if (!hooks_.label_for_guest(tgt, &l)) break;
                slots.push_back(l);
            }
            if (!slots.empty()) {
                const int idx = regs_.read(idx_reg);
                for (size_t i = 0; i < slots.size(); ++i) {
                    sink_.alu_imm(AluOp::SUB, kZR, idx,
                                  static_cast<uint64_t>(i), true, false);
                    sink_.b_cond(Cond::EQ, slots[i]);
                }
                char jb[160];
                std::snprintf(jb, sizeof(jb),
                              "thumb inline jump table at 0x%x: base=0x%x "
                              "%zu slots", addr_, tbl_base, slots.size());
                warn(jb);
                // Fall-through keeps the architecturally correct indirect
                // branch, exactly as the ARM table does.
                sink_.tail_branch_indirect(regs_.read(r));
                *terminated = true;
                return true;
            }
        }
    }

    // ARM PC-RELATIVE LADDER DISPATCH: the sibling of the Thumb table
    // above, for the idiom documented at this function's header comment
    // -- a computed jump into THIS function's own ladder, reached via a
    // register an `add rX,pc,#imm` plus a chained scaled add/sub
    // computed, rather than a direct `add pc,pc,...`. ARM mode only
    // (Thumb's PC base is the ADR case already handled above), and only
    // for an unconditional bx to a register other than lr (a return):
    // the confirmed ShatteredPD instance is exactly this shape. Purely
    // additive -- match_pc_ladder returning false, or the enumeration
    // finding zero rungs, leaves the existing tail_branch_indirect
    // fallback immediately below byte-for-byte unchanged.
    if (!predicated && !opts_.thumb && r != 14) {
        uint32_t base = 0;
        int32_t coeff = 0;
        int index_reg = -1;
        if (match_pc_ladder(r, &base, &coeff, &index_reg) && coeff != 0) {
            // By TARGET (r itself), not by index: index_reg's role ends
            // the moment the chain finishes computing r, and the
            // confirmed idiom immediately reuses it (the ladder's
            // quotient accumulator) before the bx -- see
            // emit_ladder_dispatch_by_target's header comment. r is
            // never stale: its value IS the architectural branch target.
            const uint32_t rungs =
                emit_ladder_dispatch_by_target(r, base, coeff, 512u);
            if (rungs > 0) {
                char lb[192];
                std::snprintf(lb, sizeof(lb),
                              "pc-relative ladder dispatch at 0x%x: "
                              "base=0x%x coeff=%d index=r%d rungs=%u",
                              addr_, base, static_cast<int>(coeff),
                              index_reg, rungs);
                warn(lb);
            }
        }
    }

    PredScope ps = pred_begin(map_cond(static_cast<arm_cc>(d.cc)));
    if (r == 14) {
        sink_.ret_from_function();
    } else {
        sink_.tail_branch_indirect(regs_.read(r));
    }
    pred_end(ps);
    // A predicated BX only ends the block on the taken path; the
    // fall-through keeps going.
    *terminated = !predicated;
    return true;
}

namespace {
// MACAPK_ARM32_BLX_LR_TEMP (default ON): see do_blx. Function-local static
// for thread-safe one-time init, as coproc_undef_enabled.
bool blx_lr_temp_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32_BLX_LR_TEMP");
        return !(e && std::strcmp(e, "0") == 0);
    }();
    return on;
}
}  // namespace

bool XlatCore::do_blx(const cs_insn* in, bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 1) return false;
    uint32_t ret_addr = addr_ + size_;

    PredScope ps = pred_begin(map_cond(static_cast<arm_cc>(d.cc)));

    // v1.0.833: read Rm BEFORE writing LR. That is the architectural order,
    // and it matters for the one register that is both: `blx lr` must jump to
    // the OLD lr, not to the return address we are about to store there.
    // Writing first made `blx lr` a jump to the instruction after itself.
    // arm32_lifter.cpp:4657-4667 documents finding and fixing exactly this on
    // the incumbent pipeline ("BUG 4"); the recompiler reintroduced it.
    // Reading unconditionally is safe: regs_.read on the immediate path is
    // dead but harmless, and hoisting it keeps the two operand forms in one
    // order.
    int target_reg_val = -1;
    std::vector<int> owned_target;
    if (d.operands[0].type == ARM_OP_REG) {
        int r = guest_reg_of(d.operands[0].reg);
        if (r < 0) { pred_end(ps); return false; }
        target_reg_val = regs_.read(r);
        // v1.0.1655 (MACAPK_ARM32_BLX_LR_TEMP, default ON): the hoist above
        // is NOT enough on the pinned register file. PinnedRegFile::read(14)
        // and write_begin(14) return the SAME host register (x10, regmap.h
        // r14 row), so the materialise_u32 below overwrote the target before
        // the call read it and `blx lr` still called its own return address
        // (liblzma lzma_code `blx lr` at 0xe2c8 -> OpenTTD "LZMA error
        // 1360"). ARM ARM DDI 0406C.d A8.8.26 BLX (register), Operation:
        //     target = R[m];
        //     if CurrentInstrSet() == InstrSet_ARM then
        //         next_instr_addr = PC - 4;  LR = next_instr_addr;
        //     else
        //         next_instr_addr = PC - 2;  LR = next_instr_addr<31:1> : '1';
        //     BXWritePC(target);
        // i.e. `target` is a copy of Rm taken BEFORE LR is written. Copy it
        // into a scratch register when Rm is LR. Only r14 can alias the LR
        // home, so every other Rm is emitted byte for byte as before.
        // =0 restores the pre-fix codegen exactly.
        if (r == 14 && blx_lr_temp_enabled()) {
            int t = regs_.temp_alloc();
            owned_target.push_back(t);
            sink_.mov_reg(t, target_reg_val, false);
            target_reg_val = t;
        }
    }

    // Bit 0 = the caller's own ISA, as in do_bl above. For BLX specifically
    // the callee's mode is the OPPOSITE of ours, which is exactly why the
    // return-site mode is the only correct source for this bit.
    int lr = regs_.write_begin(14);
    materialise_u32(lr, (ret_addr + opts_.load_bias) | (opts_.thumb ? 1u : 0u));
    regs_.write_end(14);

    if (d.operands[0].type == ARM_OP_IMM) {
        // BLX #imm also swaps instruction set, but the target's mode is
        // a translate-time property of the callee, so the call itself is
        // identical to BL.
        uint32_t target = static_cast<uint32_t>(d.operands[0].imm);
        if (hooks_.note_extern_target) hooks_.note_extern_target(target, true);
        emit_call_bracketed(target);
    } else if (d.operands[0].type == ARM_OP_REG) {
        emit_call_indirect_bracketed(target_reg_val);
    } else {
        release(owned_target);
        pred_end(ps);
        return false;
    }
    release(owned_target);
    pred_end(ps);
    *terminated = false;
    return true;
}

// CBZ / CBNZ: A64 has the same instruction with the same semantics and
// no flag effects, so this is a direct mapping. Only the branch RANGE
// differs in practice (translated code is larger than the guest), which
// is the encoder's veneering problem, not ours.
bool XlatCore::do_cb(const cs_insn* in, bool nonzero, bool* terminated) {
    const cs_arm& d = in->detail->arm;
    if (d.op_count != 2) return false;
    if (d.operands[0].type != ARM_OP_REG) return false;
    if (d.operands[1].type != ARM_OP_IMM) return false;
    int rn = guest_reg_of(d.operands[0].reg);
    if (rn < 0) return false;
    uint32_t target = static_cast<uint32_t>(d.operands[1].imm);
    int v = regs_.read(rn);

    Label l;
    if (hooks_.label_for_guest && hooks_.label_for_guest(target, &l) &&
        l.valid()) {
        if (nonzero) sink_.cbnz(v, l, false);
        else         sink_.cbz(v, l, false);
    } else {
        if (hooks_.note_extern_target) hooks_.note_extern_target(target, false);
        Label fall = sink_.new_label();
        if (nonzero) sink_.cbz(v, fall, false);
        else         sink_.cbnz(v, fall, false);
        sink_.tail_branch_direct(target);
        sink_.bind(fall);
    }
    *terminated = false;
    return true;
}

// ---------------------------------------------------------------------
//  Dispatch
// ---------------------------------------------------------------------

XlatCore::Result XlatCore::translate(size_t idx) {
    error_.clear();
    refuse_reason_ = nullptr;
    if (!insns_ || idx >= count_) {
        set_error("instruction index out of range");
        return Result::Failed;
    }
    idx_ = idx;
    const cs_insn* in = &insns_[idx];
    addr_ = static_cast<uint32_t>(in->address);

    // Bind any switch-case label a TBB/TBH earlier in this function
    // minted for this address. This runs BEFORE anything is emitted for
    // the instruction, and before the detail check, so a label can never
    // be left unbound (which A64Buf::resolve treats as an error and the
    // driver turns into "drop the whole function").
    if (!case_labels_.empty()) {
        auto cl = case_labels_.find(addr_);
        if (cl != case_labels_.end() && !case_labels_bound_.count(addr_)) {
            sink_.bind(cl->second);
            case_labels_bound_.insert(addr_);
        }
    }

    if (!in->detail) {
        set_error("capstone detail missing (need CS_OPT_DETAIL)");
        return Result::Failed;
    }
    size_ = in->size;

    sink_.comment(std::string(in->mnemonic) + " " + in->op_str);

    const cs_arm& d = in->detail->arm;
    const arm_cc cc = static_cast<arm_cc>(d.cc);
    const bool predicated = !cc_is_always(cc);

    // Branches consume the condition natively; everything else gets a
    // branch-over (trap T3). Predication is applied here so no family
    // handler has to think about it. A CSEL fast path exists only for
    // the single shape where it is unambiguously a win.
    switch (in->id) {
        case ARM_INS_B:
        case ARM_INS_BL:
        case ARM_INS_BLX:
        case ARM_INS_BX:
        case ARM_INS_CBZ:
        case ARM_INS_CBNZ:
            break;   // handled inside their emitters
        default:
            if (predicated) {
                // MOV<cc> Rd, #imm / Rd, Rm without S is by far the most
                // common predicated shape (`moveq r0, #0`). CSEL turns it
                // into two flag-free instructions with no branch.
                if ((in->id == ARM_INS_MOV || in->id == ARM_INS_MOVS) &&
                    !d.update_flags && d.op_count == 2 &&
                    d.operands[0].type == ARM_OP_REG &&
                    d.operands[1].shift.type == ARM_SFT_INVALID) {
                    int rd = guest_reg_of(d.operands[0].reg);
                    if (rd >= 0 && rd != 15) {
                        std::vector<int> owned;
                        int src;
                        if (d.operands[1].type == ARM_OP_IMM) {
                            src = materialise_tmp(
                                static_cast<uint32_t>(d.operands[1].imm),
                                &owned);
                        } else if (d.operands[1].type == ARM_OP_REG) {
                            int rs = guest_reg_of(d.operands[1].reg);
                            if (rs < 0 || rs == 15) { src = -1; }
                            else src = regs_.read(rs);
                        } else {
                            src = -1;
                        }
                        if (src >= 0) {
                            int old = regs_.read(rd);
                            int dst = regs_.write_begin(rd);
                            sink_.csel(CselOp::CSEL, dst, src, old,
                                       map_cond(cc), false);
                            regs_.write_end(rd);
                            release(owned);
                            return Result::Ok;
                        }
                        release(owned);
                    }
                }
            }
            break;
    }

    PredScope ps;
    bool guarded = false;
    switch (in->id) {
        case ARM_INS_B: case ARM_INS_BL: case ARM_INS_BLX:
        case ARM_INS_BX: case ARM_INS_CBZ: case ARM_INS_CBNZ:
            break;
        default:
            if (predicated) { ps = pred_begin(map_cond(cc)); guarded = true; }
            break;
    }

    bool ok = true;
    bool terminated = false;

    switch (in->id) {
        // --- moves ----------------------------------------------------
        case ARM_INS_MOV:
        case ARM_INS_MOVS: ok = do_mov(in, false); break;
        case ARM_INS_MVN:  ok = do_mov(in, true);  break;
        case ARM_INS_MOVW: ok = do_movw_movt(in, false); break;
        case ARM_INS_MOVT: ok = do_movw_movt(in, true);  break;

        // --- data processing -----------------------------------------
        case ARM_INS_ADD: ok = do_alu(in, AluOp::ADD, false); break;
        case ARM_INS_SUB: ok = do_alu(in, AluOp::SUB, false); break;
        case ARM_INS_ADC: ok = do_alu(in, AluOp::ADC, false); break;
        case ARM_INS_SBC: ok = do_alu(in, AluOp::SBC, false); break;
        case ARM_INS_AND: ok = do_alu(in, AluOp::AND, false); break;
        case ARM_INS_ORR: ok = do_alu(in, AluOp::ORR, false); break;
        case ARM_INS_EOR: ok = do_alu(in, AluOp::EOR, false); break;
        case ARM_INS_BIC: ok = do_alu(in, AluOp::BIC, false); break;
        case ARM_INS_ORN: ok = do_alu(in, AluOp::ORN, false); break;
        case ARM_INS_CMP: ok = do_alu(in, AluOp::SUB, true);  break;
        case ARM_INS_CMN: ok = do_alu(in, AluOp::ADD, true);  break;
        case ARM_INS_TST: ok = do_alu(in, AluOp::AND, true);  break;
        case ARM_INS_TEQ: ok = do_alu(in, AluOp::EOR, true);  break;
        case ARM_INS_RSB: ok = do_rsb(in, false); break;
        case ARM_INS_RSC: ok = do_rsb(in, true);  break;

        // --- shifts ---------------------------------------------------
        case ARM_INS_LSL: ok = do_shift_insn(in, Sh::LSL); break;
        case ARM_INS_LSR: ok = do_shift_insn(in, Sh::LSR); break;
        case ARM_INS_ASR: ok = do_shift_insn(in, Sh::ASR); break;
        case ARM_INS_ROR: ok = do_shift_insn(in, Sh::ROR); break;
        case ARM_INS_RRX: ok = do_rrx(in); break;

        // --- multiply / divide ----------------------------------------
        case ARM_INS_MUL:   ok = do_mul(in); break;
        case ARM_INS_MLA:   ok = do_mla(in, false); break;
        case ARM_INS_MLS:   ok = do_mla(in, true);  break;
        case ARM_INS_UMULL: ok = do_mull(in, false, false); break;
        case ARM_INS_SMULL: ok = do_mull(in, true,  false); break;
        case ARM_INS_UMLAL: ok = do_mull(in, false, true);  break;
        case ARM_INS_SMLAL: ok = do_mull(in, true,  true);  break;
        case ARM_INS_UMAAL: ok = do_umaal(in); break;
        case ARM_INS_UDIV:  ok = do_div(in, false); break;
        case ARM_INS_SDIV:  ok = do_div(in, true);  break;

        // --- signed halfword multiplies (17 families) ------------------
        // The mnemonic suffix names the halves in Rn-then-Rm order:
        // B == <15:0>, T == <31:16>. SMULWx / SMLAWx take a full word
        // from Rn and a half from Rm, so only the m half is selected.
        case ARM_INS_SMULBB: ok = do_smul_half(in, false, false); break;
        case ARM_INS_SMULBT: ok = do_smul_half(in, false, true);  break;
        case ARM_INS_SMULTB: ok = do_smul_half(in, true,  false); break;
        case ARM_INS_SMULTT: ok = do_smul_half(in, true,  true);  break;
        case ARM_INS_SMLABB: ok = do_smla_half(in, false, false); break;
        case ARM_INS_SMLABT: ok = do_smla_half(in, false, true);  break;
        case ARM_INS_SMLATB: ok = do_smla_half(in, true,  false); break;
        case ARM_INS_SMLATT: ok = do_smla_half(in, true,  true);  break;
        case ARM_INS_SMLALBB: ok = do_smlal_half(in, false, false); break;
        case ARM_INS_SMLALBT: ok = do_smlal_half(in, false, true);  break;
        case ARM_INS_SMLALTB: ok = do_smlal_half(in, true,  false); break;
        case ARM_INS_SMLALTT: ok = do_smlal_half(in, true,  true);  break;
        case ARM_INS_SMULWB: ok = do_smul_w(in, false); break;
        case ARM_INS_SMULWT: ok = do_smul_w(in, true);  break;
        case ARM_INS_SMLAWB: ok = do_smla_w(in, false); break;
        case ARM_INS_SMLAWT: ok = do_smla_w(in, true);  break;

        // --- ARMv6 media / DSP (GPR ops, core-owned) ------------------
        case ARM_INS_PKHBT: ok = do_pkh(in, false); break;
        case ARM_INS_PKHTB: ok = do_pkh(in, true);  break;
        case ARM_INS_SEL:   ok = do_sel(in); break;
        case ARM_INS_SXTB16:  ok = do_xtb16(in, true,  false); break;
        case ARM_INS_UXTB16:  ok = do_xtb16(in, false, false); break;
        case ARM_INS_SXTAB16: ok = do_xtb16(in, true,  true);  break;
        case ARM_INS_UXTAB16: ok = do_xtb16(in, false, true);  break;
        case ARM_INS_SSAT:   ok = do_sat(in, true);  break;
        case ARM_INS_USAT:   ok = do_sat(in, false); break;
        case ARM_INS_SSAT16: ok = do_sat16(in, true);  break;
        case ARM_INS_USAT16: ok = do_sat16(in, false); break;
        case ARM_INS_QADD:  ok = do_qaddsub(in, false, false); break;
        case ARM_INS_QSUB:  ok = do_qaddsub(in, true,  false); break;
        case ARM_INS_QDADD: ok = do_qaddsub(in, false, true);  break;
        case ARM_INS_QDSUB: ok = do_qaddsub(in, true,  true);  break;
        case ARM_INS_USAD8:  ok = do_usad8(in, false); break;
        case ARM_INS_USADA8: ok = do_usad8(in, true);  break;
        // sub, swap, accumulate, long
        case ARM_INS_SMUAD:   ok = do_dual_mul(in, false, false, false, false); break;
        case ARM_INS_SMUADX:  ok = do_dual_mul(in, false, true,  false, false); break;
        case ARM_INS_SMUSD:   ok = do_dual_mul(in, true,  false, false, false); break;
        case ARM_INS_SMUSDX:  ok = do_dual_mul(in, true,  true,  false, false); break;
        case ARM_INS_SMLAD:   ok = do_dual_mul(in, false, false, true,  false); break;
        case ARM_INS_SMLADX:  ok = do_dual_mul(in, false, true,  true,  false); break;
        case ARM_INS_SMLSD:   ok = do_dual_mul(in, true,  false, true,  false); break;
        case ARM_INS_SMLSDX:  ok = do_dual_mul(in, true,  true,  true,  false); break;
        case ARM_INS_SMLALD:  ok = do_dual_mul(in, false, false, true,  true);  break;
        case ARM_INS_SMLALDX: ok = do_dual_mul(in, false, true,  true,  true);  break;
        case ARM_INS_SMLSLD:  ok = do_dual_mul(in, true,  false, true,  true);  break;
        case ARM_INS_SMLSLDX: ok = do_dual_mul(in, true,  true,  true,  true);  break;
        case ARM_INS_SMMUL:  ok = do_smm(in, false, false, false); break;
        case ARM_INS_SMMULR: ok = do_smm(in, false, true,  false); break;
        case ARM_INS_SMMLA:  ok = do_smm(in, false, false, true);  break;
        case ARM_INS_SMMLAR: ok = do_smm(in, false, true,  true);  break;
        case ARM_INS_SMMLS:  ok = do_smm(in, true,  false, true);  break;
        case ARM_INS_SMMLSR: ok = do_smm(in, true,  true,  true);  break;

        // --- supervisor call and the PSR surface ----------------------
        case ARM_INS_SVC: ok = do_svc(in); break;
        case ARM_INS_MRS: ok = do_mrs(in); break;
        case ARM_INS_MSR: ok = do_msr(in); break;

        // --- bit manipulation ------------------------------------------
        case ARM_INS_CLZ:   ok = do_bit1(in, Bit1Op::CLZ);   break;
        case ARM_INS_RBIT:  ok = do_bit1(in, Bit1Op::RBIT);  break;
        case ARM_INS_REV:   ok = do_bit1(in, Bit1Op::REV);   break;
        case ARM_INS_REV16: ok = do_bit1(in, Bit1Op::REV16); break;
        case ARM_INS_REVSH: ok = do_revsh(in); break;
        case ARM_INS_UXTB:  ok = do_extend(in, 8,  false); break;
        case ARM_INS_UXTH:  ok = do_extend(in, 16, false); break;
        case ARM_INS_SXTB:  ok = do_extend(in, 8,  true);  break;
        case ARM_INS_SXTH:  ok = do_extend(in, 16, true);  break;
        case ARM_INS_UXTAB: ok = do_extend_add(in, 8,  false); break;
        case ARM_INS_UXTAH: ok = do_extend_add(in, 16, false); break;
        case ARM_INS_SXTAB: ok = do_extend_add(in, 8,  true);  break;
        case ARM_INS_SXTAH: ok = do_extend_add(in, 16, true);  break;
        case ARM_INS_UBFX:  ok = do_bfx(in, false); break;
        case ARM_INS_SBFX:  ok = do_bfx(in, true);  break;
        case ARM_INS_BFI:   ok = do_bfi(in); break;
        case ARM_INS_BFC:   ok = do_bfc(in); break;

        // --- PC-relative ----------------------------------------------
        case ARM_INS_ADR: ok = do_adr(in); break;
        case ARM_INS_LDR:
            // Only the literal-pool form belongs to the core; every
            // other LDR is the memory translator's.
            if (d.op_count == 2 && d.operands[1].type == ARM_OP_MEM &&
                d.operands[1].mem.base == ARM_REG_PC &&
                d.operands[1].mem.index == ARM_REG_INVALID) {
                ok = do_ldr_literal(in);
            } else {
                if (guarded) pred_end(ps);
                return Result::Unsupported;
            }
            break;

        // --- control flow ----------------------------------------------
        case ARM_INS_B:    ok = do_branch(in, &terminated); break;
        case ARM_INS_BL:   ok = do_bl(in, &terminated);     break;
        case ARM_INS_BLX:  ok = do_blx(in, &terminated);    break;
        case ARM_INS_BX:   ok = do_bx(in, &terminated);     break;
        case ARM_INS_CBZ:  ok = do_cb(in, false, &terminated); break;
        case ARM_INS_CBNZ: ok = do_cb(in, true,  &terminated); break;
        // TBB / TBH are ordinary predicable instructions as far as the
        // dispatcher is concerned (they may be the LAST slot of an IT
        // block), so they take the generic branch-over above rather than
        // the native-condition path the B family uses.
        case ARM_INS_TBB: ok = do_table_branch(in, false, &terminated); break;
        case ARM_INS_TBH: ok = do_table_branch(in, true,  &terminated); break;

        // --- coprocessor space -----------------------------------------
        case ARM_INS_CDP:   case ARM_INS_CDP2:
        case ARM_INS_LDC:   case ARM_INS_LDCL:
        case ARM_INS_LDC2:  case ARM_INS_LDC2L:
        case ARM_INS_STC:   case ARM_INS_STCL:
        case ARM_INS_STC2:  case ARM_INS_STC2L:
        case ARM_INS_MCR:   case ARM_INS_MCR2:
        case ARM_INS_MCRR:  case ARM_INS_MCRR2:
        case ARM_INS_MRC:   case ARM_INS_MRC2:
        case ARM_INS_MRRC:  case ARM_INS_MRRC2:
            ok = do_coproc(in); break;

        // --- architectural hints, 1:1 with A64 -------------------------
        case ARM_INS_YIELD: sink_.sys_op(SysOp::Yield, 0); break;
        case ARM_INS_SEV:   sink_.sys_op(SysOp::Sev,   0); break;
        case ARM_INS_SEVL:  sink_.sys_op(SysOp::Sevl,  0); break;
        case ARM_INS_WFE:   sink_.sys_op(SysOp::Wfe,   0); break;
        case ARM_INS_WFI:   sink_.sys_op(SysOp::Wfi,   0); break;
        // DBG is a hint whose only architected effect is on debug
        // hardware the model does not have (DDI 0406C.d A8.8.42:
        // "DBG ... provides a hint to the debug system"), so a NOP is
        // the whole of its user-visible behaviour.
        case ARM_INS_DBG:   sink_.sys_op(SysOp::Nop,   0); break;

        // --- exception-generating forms --------------------------------
        case ARM_INS_BKPT: ok = do_trap(in, true,  &terminated); break;
        case ARM_INS_UDF:
        case ARM_INS_TRAP: ok = do_trap(in, false, &terminated); break;

        // --- inert at EL0 ----------------------------------------------
        case ARM_INS_CPS:
            ok = do_el0_inert(in, "CPS is treated as NOP in User mode");
            break;
        case ARM_INS_SETEND:
            ok = do_el0_inert(in, "PSTATE.E is fixed little-endian in this "
                                  "model");
            break;
        case ARM_INS_HVC:
        case ARM_INS_SMC:
            ok = do_el0_inert(in, "hypervisor / secure-monitor call is "
                                  "UNDEFINED at EL0");
            break;
        case ARM_INS_BXNS:
        case ARM_INS_BLXNS:
            ok = do_el0_inert(in, "ARMv8-M secure-state branch; not in the "
                                  "A-profile instruction set (the bytes were "
                                  "data)");
            break;

        // --- no-ops ----------------------------------------------------
        case ARM_INS_NOP:
        case ARM_INS_HINT:
            break;
        case ARM_INS_IT:
            // Capstone propagates the resulting condition onto each
            // instruction inside the block, so the IT itself is a no-op
            // for us and every slot is handled by the generic
            // predication above (branch-over or CSEL, trap T3).
            break;

        default:
            if (guarded) pred_end(ps);
            if (rc_stub_trace_enabled() && !mem_or_simd_owns(in->id))
                rc_stub_trace(addr_, opts_.thumb, in, "core-nodispatch",
                              "instruction id is in no dispatch table");
            return Result::Unsupported;
    }

    if (guarded) {
        pred_end(ps);
        // The branch-over means control always reaches the next
        // instruction, even when the guarded body returned or branched.
        terminated = false;
    }

    if (!ok) {
        set_error(std::string("failed to translate '") + in->mnemonic + " " +
                  in->op_str + "'");
        rc_stub_trace(addr_, opts_.thumb, in, "core-refuse", refuse_reason_);
        return opts_.strict ? Result::Failed : Result::Unsupported;
    }
    return terminated ? Result::Terminated : Result::Ok;
}

// =====================================================================
//  Sibling-pipeline adapters (a64_emit.h + regmap.h)
// =====================================================================
//
// One method per encoder call. This is the entire integration surface:
// if a sibling header changes a signature, everything that needs fixing
// is between here and the end of PinnedRegFile.

#if MACAPK_XLAT_CORE_HAVE_SIBLINGS

// The APSR-in-memory offsets this file hard-codes (so the header stays
// standalone) against the single source of truth in guest_state.h. A
// mismatch would put the Q / GE read-modify-write somewhere else in the
// per-thread block, which is a silent wrong-value bug rather than a
// crash, so it is asserted.
static_assert(kGuestStateApsrGeOffset == kOffApsrGe,
              "GuestState.apsr_ge offset drifted from guest_state.h");
static_assert(kGuestStateApsrQOffset == kOffApsrQ,
              "GuestState.apsr_q offset drifted from guest_state.h");

bool mem_or_simd_owns(unsigned insn_id) {
    return is_mem_insn(insn_id) || is_simd_insn(insn_id);
}

namespace {

// The two enums that already agree numerically, asserted rather than
// assumed so a future edit to either side fails loudly at compile time.
static_assert(static_cast<uint32_t>(Sh::LSL) == a64::ShiftLSL, "shift enum drift");
static_assert(static_cast<uint32_t>(Sh::LSR) == a64::ShiftLSR, "shift enum drift");
static_assert(static_cast<uint32_t>(Sh::ASR) == a64::ShiftASR, "shift enum drift");
static_assert(static_cast<uint32_t>(Sh::ROR) == a64::ShiftROR, "shift enum drift");
static_assert(static_cast<uint8_t>(Cond::EQ) == a64::CondEQ, "cond enum drift");
static_assert(static_cast<uint8_t>(Cond::LE) == a64::CondLE, "cond enum drift");
static_assert(static_cast<uint8_t>(Cond::AL) == a64::CondAL, "cond enum drift");
static_assert(kZR == static_cast<int>(a64::kZR), "zero-register drift");

inline a64::Shift sh_of(Sh s) { return static_cast<a64::Shift>(s); }
inline a64::Cond  cd_of(Cond c) { return static_cast<a64::Cond>(c); }
inline unsigned   r_of(int r) { return static_cast<unsigned>(r); }

// The extend enums do NOT agree: A64 interleaves UXTX at 3, this file's
// Ext does not carry it. Map explicitly.
a64::Extend ext_of(Ext e) {
    switch (e) {
        case Ext::UXTB: return a64::ExtUXTB;
        case Ext::UXTH: return a64::ExtUXTH;
        case Ext::UXTW: return a64::ExtUXTW;
        case Ext::SXTB: return a64::ExtSXTB;
        case Ext::SXTH: return a64::ExtSXTH;
        case Ext::SXTW: return a64::ExtSXTW;
    }
    return a64::ExtUXTW;
}

}  // namespace

void BufSink::alu_reg(AluOp op, int rd, int rn, int rm, Sh sh, int amt,
                      bool S, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn), m = r_of(rm);
    const a64::Shift s = sh_of(sh);
    const unsigned a = static_cast<unsigned>(amt);
    switch (op) {
        case AluOp::ADD: buf_.put(S ? a64::adds_reg(sf64, d, n, m, s, a)
                                    : a64::add_reg (sf64, d, n, m, s, a)); break;
        case AluOp::SUB: buf_.put(S ? a64::subs_reg(sf64, d, n, m, s, a)
                                    : a64::sub_reg (sf64, d, n, m, s, a)); break;
        // A64 has no shifted ADC/SBC; the core never asks for one.
        case AluOp::ADC: buf_.put(S ? a64::adcs(sf64, d, n, m)
                                    : a64::adc (sf64, d, n, m)); break;
        case AluOp::SBC: buf_.put(S ? a64::sbcs(sf64, d, n, m)
                                    : a64::sbc (sf64, d, n, m)); break;
        case AluOp::AND: buf_.put(S ? a64::ands_reg(sf64, d, n, m, s, a)
                                    : a64::and_reg (sf64, d, n, m, s, a)); break;
        case AluOp::ORR: buf_.put(a64::orr_reg(sf64, d, n, m, s, a)); break;
        case AluOp::EOR: buf_.put(a64::eor_reg(sf64, d, n, m, s, a)); break;
        case AluOp::BIC: buf_.put(S ? a64::bics_reg(sf64, d, n, m, s, a)
                                    : a64::bic_reg (sf64, d, n, m, s, a)); break;
        case AluOp::ORN: buf_.put(a64::orn_reg(sf64, d, n, m, s, a)); break;
        case AluOp::EON: buf_.put(a64::eon_reg(sf64, d, n, m, s, a)); break;
    }
}

void BufSink::alu_imm(AluOp op, int rd, int rn, uint64_t imm, bool S,
                      bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn);
    if (op == AluOp::ADD || op == AluOp::SUB) {
        // a64_encodable_add_imm() guaranteed one of these two shapes.
        const bool shifted = (imm > 0xfffu);
        const uint32_t f = static_cast<uint32_t>(shifted ? (imm >> 12) : imm);
        if (op == AluOp::ADD)
            buf_.put(S ? a64::adds_imm(sf64, d, n, f, shifted)
                       : a64::add_imm (sf64, d, n, f, shifted));
        else
            buf_.put(S ? a64::subs_imm(sf64, d, n, f, shifted)
                       : a64::sub_imm (sf64, d, n, f, shifted));
        return;
    }
    // Logical immediate: BIC/ORN/EON have no immediate encoding, and the
    // core already folded them into AND/ORR/EOR of the complement.
    uint32_t field = 0;
    if (!a64::encode_logical_imm(imm, sf64, &field)) {
        // Unreachable: the core checks a64_is_logical_imm() first.
        buf_.put(a64::brk(0xdead));
        return;
    }
    switch (op) {
        case AluOp::AND: buf_.put(S ? a64::ands_imm(sf64, d, n, field)
                                    : a64::and_imm (sf64, d, n, field)); break;
        case AluOp::ORR: buf_.put(a64::orr_imm(sf64, d, n, field)); break;
        case AluOp::EOR: buf_.put(a64::eor_imm(sf64, d, n, field)); break;
        default:         buf_.put(a64::brk(0xdead)); break;
    }
}

void BufSink::alu_extended(AluOp op, int rd, int rn, int rm, Ext ext,
                           int shift, bool S, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn), m = r_of(rm);
    const a64::Extend o = ext_of(ext);
    const unsigned s = static_cast<unsigned>(shift);
    if (op == AluOp::SUB)
        buf_.put(S ? a64::subs_ext(sf64, d, n, m, o, s)
                   : a64::sub_ext (sf64, d, n, m, o, s));
    else
        buf_.put(S ? a64::adds_ext(sf64, d, n, m, o, s)
                   : a64::add_ext (sf64, d, n, m, o, s));
}

void BufSink::movz(int rd, uint16_t imm16, int shift, bool sf64) {
    buf_.put(a64::movz(sf64, r_of(rd), imm16,
                       static_cast<unsigned>(shift) / 16u));
}
void BufSink::movn(int rd, uint16_t imm16, int shift, bool sf64) {
    buf_.put(a64::movn(sf64, r_of(rd), imm16,
                       static_cast<unsigned>(shift) / 16u));
}
void BufSink::movk(int rd, uint16_t imm16, int shift, bool sf64) {
    buf_.put(a64::movk(sf64, r_of(rd), imm16,
                       static_cast<unsigned>(shift) / 16u));
}
void BufSink::mov_reg(int rd, int rm, bool sf64) {
    buf_.put(a64::mov_reg(sf64, r_of(rd), r_of(rm)));
}

void BufSink::shift_reg(Sh sh, int rd, int rn, int rm, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn), m = r_of(rm);
    switch (sh) {
        case Sh::LSL: buf_.put(a64::lslv(sf64, d, n, m)); break;
        case Sh::LSR: buf_.put(a64::lsrv(sf64, d, n, m)); break;
        case Sh::ASR: buf_.put(a64::asrv(sf64, d, n, m)); break;
        case Sh::ROR: buf_.put(a64::rorv(sf64, d, n, m)); break;
    }
}

void BufSink::shift_imm(Sh sh, int rd, int rn, int amt, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn);
    const unsigned a = static_cast<unsigned>(amt);
    switch (sh) {
        case Sh::LSL: buf_.put(a64::lsl_imm(sf64, d, n, a)); break;
        case Sh::LSR: buf_.put(a64::lsr_imm(sf64, d, n, a)); break;
        case Sh::ASR: buf_.put(a64::asr_imm(sf64, d, n, a)); break;
        case Sh::ROR: buf_.put(a64::ror_imm(sf64, d, n, a)); break;
    }
}

void BufSink::extr(int rd, int rn, int rm, int lsb, bool sf64) {
    buf_.put(a64::extr(sf64, r_of(rd), r_of(rn), r_of(rm),
                       static_cast<unsigned>(lsb)));
}
void BufSink::ubfx(int rd, int rn, int lsb, int width, bool sf64) {
    buf_.put(a64::ubfx(sf64, r_of(rd), r_of(rn), static_cast<unsigned>(lsb),
                       static_cast<unsigned>(width)));
}
void BufSink::sbfx(int rd, int rn, int lsb, int width, bool sf64) {
    buf_.put(a64::sbfx(sf64, r_of(rd), r_of(rn), static_cast<unsigned>(lsb),
                       static_cast<unsigned>(width)));
}
void BufSink::bfi(int rd, int rn, int lsb, int width, bool sf64) {
    buf_.put(a64::bfi(sf64, r_of(rd), r_of(rn), static_cast<unsigned>(lsb),
                      static_cast<unsigned>(width)));
}

void BufSink::mul3(MulOp op, int rd, int rn, int rm, int ra) {
    const unsigned d = r_of(rd), n = r_of(rn), m = r_of(rm), a = r_of(ra);
    switch (op) {
        case MulOp::MADD:   buf_.put(a64::madd(false, d, n, m, a)); break;
        case MulOp::MSUB:   buf_.put(a64::msub(false, d, n, m, a)); break;
        case MulOp::UMADDL: buf_.put(a64::umaddl(d, n, m, a)); break;
        case MulOp::SMADDL: buf_.put(a64::smaddl(d, n, m, a)); break;
        case MulOp::SMSUBL: buf_.put(a64::smsubl(d, n, m, a)); break;
    }
}

void BufSink::divide(bool is_signed, int rd, int rn, int rm, bool sf64) {
    buf_.put(is_signed ? a64::sdiv(sf64, r_of(rd), r_of(rn), r_of(rm))
                       : a64::udiv(sf64, r_of(rd), r_of(rn), r_of(rm)));
}

void BufSink::bit1(Bit1Op op, int rd, int rn, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn);
    switch (op) {
        case Bit1Op::CLZ:   buf_.put(a64::clz  (sf64, d, n)); break;
        case Bit1Op::RBIT:  buf_.put(a64::rbit (sf64, d, n)); break;
        case Bit1Op::REV:   buf_.put(a64::rev  (sf64, d, n)); break;
        case Bit1Op::REV16: buf_.put(a64::rev16(sf64, d, n)); break;
        // The extend aliases always read a W source.
        case Bit1Op::SXTB:  buf_.put(a64::sxtb(sf64, d, n)); break;
        case Bit1Op::SXTH:  buf_.put(a64::sxth(sf64, d, n)); break;
        case Bit1Op::UXTB:  buf_.put(a64::uxtb(d, n)); break;
        case Bit1Op::UXTH:  buf_.put(a64::uxth(d, n)); break;
    }
}

void BufSink::csel(CselOp op, int rd, int rn, int rm, Cond c, bool sf64) {
    const unsigned d = r_of(rd), n = r_of(rn), m = r_of(rm);
    const a64::Cond cc = cd_of(c);
    switch (op) {
        case CselOp::CSEL:  buf_.put(a64::csel (sf64, d, n, m, cc)); break;
        case CselOp::CSINC: buf_.put(a64::csinc(sf64, d, n, m, cc)); break;
        case CselOp::CSINV: buf_.put(a64::csinv(sf64, d, n, m, cc)); break;
        case CselOp::CSNEG: buf_.put(a64::csneg(sf64, d, n, m, cc)); break;
    }
}

void BufSink::cset(int rd, Cond c, bool sf64) {
    buf_.put(a64::cset(sf64, r_of(rd), cd_of(c)));
}
void BufSink::mrs_nzcv(int xd) { buf_.put(a64::mrs(r_of(xd), a64::kSR_NZCV)); }
void BufSink::msr_nzcv(int xn) { buf_.put(a64::msr_reg(a64::kSR_NZCV, r_of(xn))); }

Label BufSink::new_label() {
    Label l;
    l.id = static_cast<int>(labels_.size());
    labels_.push_back(buf_.new_label());
    return l;
}
void BufSink::bind(Label l)  { buf_.bind(labels_[static_cast<size_t>(l.id)]); }
void BufSink::b(Label l)     { buf_.b(labels_[static_cast<size_t>(l.id)]); }
void BufSink::b_cond(Cond c, Label l) {
    buf_.b_cond(cd_of(c), labels_[static_cast<size_t>(l.id)]);
}
void BufSink::cbz(int rt, Label l, bool sf64) {
    buf_.cbz(sf64, r_of(rt), labels_[static_cast<size_t>(l.id)]);
}
void BufSink::cbnz(int rt, Label l, bool sf64) {
    buf_.cbnz(sf64, r_of(rt), labels_[static_cast<size_t>(l.id)]);
}

void BufSink::call_direct(uint32_t guest_target) {
    sites_.push_back({static_cast<uint32_t>(buf_.size_words()), guest_target,
                      true});
    buf_.put(a64::bl(0));
}
void BufSink::tail_branch_direct(uint32_t guest_target) {
    sites_.push_back({static_cast<uint32_t>(buf_.size_words()), guest_target,
                      false});
    buf_.put(a64::b(0));
}
void BufSink::call_indirect(int host_reg) { buf_.put(a64::blr(r_of(host_reg))); }
void BufSink::tail_branch_indirect(int host_reg) {
    buf_.put(a64::br(r_of(host_reg)));
}
void BufSink::ret_from_function() { buf_.put(a64::ret()); }

// THE 2^32 WRAP INVARIANT (xlat_mem.h) applies to the three arena
// accesses below exactly as it does to xlat_mem.cpp's choke points: each
// folds ONE already-flattened 32-bit guest address with ONE UXTW, and
// none of them may grow a second guest-register operand. The asserts are
// repeated here because this is a separate translation unit and the ones
// in xlat_mem.cpp do not cover it.
static_assert(a64::ExtUXTW == 2u && (a64::ExtUXTW & 4u) == 0u,
              "the arena fold must ZERO-extend. SXTW would put every guest "
              "address >= 0x80000000 up to 2 GiB below the arena base, "
              "because unlike MAMBO-X64 (PLDI 2017 section 3.3) our arena "
              "base is pinned and non-zero.");
static_assert(kGuestArenaBytes == (1ull << 32),
              "a single-register UXTW fold is in-bounds only while the arena "
              "spans the whole 32-bit space.");

void BufSink::load_guest_u32(int rd, int addr_reg) {
    // addr_reg holds a 32-bit guest address and is a scratch register the
    // caller does not read again, so it doubles as the host pointer.
    //   ADD Xaddr, Xguestbase, Waddr, UXTW
    //   LDR Wrd, [Xaddr]
    // ONE guest register, UXTW, no second operand: see the note above.
    const unsigned a = r_of(addr_reg);
    buf_.put(a64::add_ext(true, a,
                          static_cast<unsigned>(enc(kHostGuestBase)), a,
                          a64::ExtUXTW, 0));
    buf_.put(a64::ldr(false, r_of(rd), a, 0));
}

// Same shape as load_guest_u32 but for the 1- and 2-byte reads a table
// branch does. ONE guest register, UXTW, no second operand (the wrap
// invariant). The register-offset form does the zero-extend of the
// 32-bit guest address for free (UXTW), and `scaled` MUST be false: the
// address in addr_reg is already a byte address, and letting the LDRH
// form scale it by 2 would double every table offset.
void BufSink::load_guest_zx(int rd, int addr_reg, int bytes) {
    const unsigned sz = (bytes == 1) ? a64::kSizeB : a64::kSizeH;
    buf_.put(a64::ldst_reg(sz, false, /*opc=*/1u, r_of(rd),
                           static_cast<unsigned>(enc(kHostGuestBase)),
                           r_of(addr_reg), a64::ExtUXTW, /*scaled=*/false));
}

namespace {
// Xdst = host pointer to this thread's GuestState, exactly as specified
// in guest_state.h section 3.2 and implemented identically by
// xlat_mem.cpp's emit_guest_state_ptr:
//     AND Wdst, W<guestSP>, #0xfff00000    ; slot base, guest vaddr
//     ADD Xdst, X<guestBase>, Xdst         ; host pointer
// The W-form AND zero-extends into the full X register (DDI 0487: a
// 32-bit destination clears bits 63:32), which IS the 2^32 truncation a
// UXTW would perform, so the ADD needs no extend and this site still
// folds exactly ONE 32-bit value into the arena base. Do not turn the AND
// into an X-form one to "save" the extend: that reintroduces exactly the
// wrap hazard THE 2^32 WRAP INVARIANT in xlat_mem.h exists to prevent.
//
// TRAP, and it cost a real bug before the emitted words were read back:
// `a64::and_imm`'s last argument is the ENCODED N:immr:imms bitmask
// field, NOT the value. Handing it 0xfff00000 directly truncates to a
// 13-bit field and assembles as `and w12, w27, #0x1`, which points the
// GuestState at the bottom of the arena instead of the thread's slot,
// so every Q / GE read-modify-write scribbles on guest memory near
// address 0. The differential harness could not see it because the
// store and the load agree on the same wrong address. Encode first.
void emit_guest_state_ptr_into(a64::A64Buf& b, unsigned xdst) {
    uint32_t mask_field = 0;
    if (a64::encode_logical_imm(kSlotBaseMask, false, &mask_field)) {
        b.put(a64::and_imm(false, xdst,
                           static_cast<unsigned>(enc(kHostGuestSp)),
                           mask_field));
    } else {
        b.mov_imm(false, xdst, kSlotBaseMask);
        b.put(a64::and_reg(false, xdst,
                           static_cast<unsigned>(enc(kHostGuestSp)), xdst));
    }
    b.put(a64::add_reg(true, xdst,
                       static_cast<unsigned>(enc(kHostGuestBase)), xdst));
}
}  // namespace

void BufSink::guest_state_load(int rd, uint32_t byte_off, int scratch) {
    emit_guest_state_ptr_into(buf_, r_of(scratch));
    buf_.put(a64::ldr(false, r_of(rd), r_of(scratch), byte_off));
}

void BufSink::guest_state_store(int rs, uint32_t byte_off, int scratch) {
    emit_guest_state_ptr_into(buf_, r_of(scratch));
    buf_.put(a64::str(false, r_of(rs), r_of(scratch), byte_off));
}

// SVC.
//
// The observable effect of a Linux supervisor call is confined to r0:
// the kernel preserves r1-r12 across a syscall, and the return value is
// the raw kernel result, i.e. a NEGATIVE errno on failure. Bionic's own
// syscall stubs are built on exactly that convention:
//
//     svc #0
//     cmn r0, #(MAX_ERRNO + 1)
//     bxls lr
//     neg r0, r0
//     b   __set_errno_internal
//
// so returning -ENOSYS (-38) in r0 lets the GUEST's own errno machinery
// run and produces the errno==ENOSYS / return==-1 that its caller then
// sees. That is strictly closer to the hardware than the LLVM path's
// helper, which returns the libc-level -1 and sets the HOST's errno,
// where the guest cannot see it.
//
// This is deliberately a sink method rather than an inline sequence in
// XlatCore: guest r0-r3 already sit in the host argument registers, so
// an owner of the module-linking half can override this one method to
// emit `bl __macapk_svc` (the helper exists in bionic_shim.cpp) without
// touching the translator. It is not done here because a call needs a
// named undefined symbol, and the extern-site channel this sink writes
// to only carries guest vaddrs plus two reserved sentinels; adding a
// third is a change to arm32rc_driver.cpp, which this file does not own.
// A64 has a direct counterpart for every one of these.
//
// The permanently-undefined encoding is the one worth spelling out,
// because a64_emit.h has no name for it: DDI 0487 gives UDF as
// `0000000000000000 imm16`, i.e. the WHOLE 32-bit word is the 16-bit
// immediate. Verified by assembling: `udf #0` is the word 0x00000000 and
// `udf #0xdead` is 0x0000dead.
void BufSink::sys_op(SysOp op, uint16_t imm) {
    switch (op) {
        case SysOp::Nop:        buf_.put(a64::nop()); break;
        case SysOp::Yield:      buf_.put(a64::yield()); break;
        case SysOp::Sev:        buf_.put(a64::sev()); break;
        // SEVL is HINT #5, WFI is HINT #3 (DDI 0487 C6.2.109 HINT).
        case SysOp::Sevl:       buf_.put(a64::hint(5)); break;
        case SysOp::Wfe:        buf_.put(a64::wfe()); break;
        case SysOp::Wfi:        buf_.put(a64::hint(3)); break;
        case SysOp::Breakpoint: buf_.put(a64::brk(imm)); break;
        case SysOp::Undefined:  buf_.put(static_cast<uint32_t>(imm)); break;
    }
}

void BufSink::svc_bridge(uint32_t imm, int rd) {
    (void)imm;   // the immediate selects the syscall, which none of the
                 // available routes can service; see above.
    buf_.put(a64::movn(false, r_of(rd), 37));   // MOVN #37 -> 0xffffffda
}

int PinnedRegFile::read(int g) {
    return static_cast<int>(enc(host_gpr_for_guest(g)));
}
int PinnedRegFile::write_begin(int g) {
    // Guest GPRs are pinned, so the destination IS the home register and
    // no spill is needed on write_end.
    return static_cast<int>(enc(host_gpr_for_guest(g)));
}
int PinnedRegFile::temp_alloc() {
    HostGpr h = scratch_.acquire();
    if (h == HostGpr::kNone) {
        std::fprintf(stderr,
                     "[arm32rc] FATAL: scratch pool exhausted (need 8)\n");
        std::abort();
    }
    return static_cast<int>(enc(h));
}
void PinnedRegFile::temp_free(int host_reg) {
    scratch_.release(static_cast<HostGpr>(host_reg));
}

#endif  // MACAPK_XLAT_CORE_HAVE_SIBLINGS

}  // namespace arm32rc
}  // namespace macapk

// =====================================================================
//  Differential self-test (compiled out by default)
// =====================================================================
//
// Reading translation code does not prove flag semantics; executing it
// does. This harness translates a corpus of ARM32 encodings, assembles
// the emitted A64, runs it natively on the host, and compares all 16
// guest registers plus NZCV against a reference interpreter transcribed
// from DDI 0406C.d. It exists in this file, rather than in a scratch
// directory, so the proof survives the session that produced it.
//
//   SP=/tmp/rc && mkdir -p $SP
//   CS=/opt/homebrew/opt/capstone
//   clang++ -std=c++17 -Wall -Wextra -DMACAPK_ARM32RC_SELFTEST \
//       -I. -I$CS/include xlat_core.cpp \
//       -L$CS/lib -lcapstone -Wl,-rpath,$CS/lib -o $SP/dt
//   $SP/dt asm $SP/a32.s
//   clang --target=armv7-none-eabi -mcpu=cortex-a15 -c $SP/a32.s -o $SP/a32.o
//   llvm-objcopy -O binary --only-section=.text $SP/a32.o $SP/a32.bin
//   $SP/dt gen $SP/a32.bin $SP/gen.s
//   clang -c -arch arm64 $SP/gen.s -o $SP/gen.o
//   clang -shared -arch arm64 $SP/gen.o -o $SP/gen.dylib
//   $SP/dt run $SP/a32.bin $SP/gen.dylib
//
// Status at the time of writing: 113/113 instructions match across a
// 20x20x2x4 input matrix. The harness was itself mutation-tested, and
// catches: dropping C preservation in the NZ-only flag write (17
// failures), dropping the >= 32 saturation on register-controlled
// LSL/LSR (11), using A64's ANDS directly for the A32 logical S-forms
// (51), and taking the long-multiply flags from the high word alone,
// which is what arm32_lifter.cpp did (2).

#ifdef MACAPK_ARM32RC_SELFTEST

#include <dlfcn.h>
#include <cstdlib>

namespace macapk {
namespace arm32rc {
namespace selftest {

struct State {
    uint32_t r[16];
    uint32_t nzcv;   // bits 31..28
};

// Guest g -> host x(1+g); the state pointer lives in x0.
static const int kScratch[8] = {16, 17, 19, 20, 21, 22, 23, 24};

struct DiffRegs : RegFile {
    bool used[8] = {};
    int read(int g) override { return 1 + g; }
    int write_begin(int g) override { return 1 + g; }
    void write_end(int) override {}
    int temp_alloc() override {
        for (int i = 0; i < 8; ++i)
            if (!used[i]) { used[i] = true; return kScratch[i]; }
        std::fprintf(stderr, "FATAL: out of scratch\n");
        std::abort();
    }
    void temp_free(int hr) override {
        for (int i = 0; i < 8; ++i)
            if (kScratch[i] == hr) { used[i] = false; return; }
    }
    void reset() { for (int i = 0; i < 8; ++i) used[i] = false; }
};

enum RSh { R_LSL, R_LSR, R_ASR, R_ROR, R_RRX };

// A2.2.1 LSL_C / LSR_C / ASR_C / ROR_C / RRX_C under A8.4.3 Shift_C.
static void shift_c(uint32_t x, RSh type, int amount, int carry_in,
                    uint32_t* result, int* carry_out) {
    if (type != R_RRX && amount == 0) {
        *result = x; *carry_out = carry_in; return;
    }
    switch (type) {
        case R_LSL:
            if (amount >= 64) { *result = 0; *carry_out = 0; return; }
            *result = (amount > 32) ? 0u
                    : static_cast<uint32_t>((static_cast<uint64_t>(x) << amount)
                                            & 0xffffffffu);
            *carry_out = (amount <= 32) ? ((x >> (32 - amount)) & 1u) : 0;
            break;
        case R_LSR:
            if (amount >= 64) { *result = 0; *carry_out = 0; return; }
            *result = (amount >= 32) ? 0u : (x >> amount);
            *carry_out = (amount <= 32) ? ((x >> (amount - 1)) & 1u) : 0;
            break;
        case R_ASR: {
            int32_t sx = static_cast<int32_t>(x);
            *result = static_cast<uint32_t>(sx >> ((amount >= 32) ? 31 : amount));
            *carry_out = (amount >= 32) ? ((x >> 31) & 1u)
                                        : ((x >> (amount - 1)) & 1u);
            break;
        }
        case R_ROR: {
            int m = amount % 32;
            uint32_t res = (m == 0) ? x : ((x >> m) | (x << (32 - m)));
            *result = res;
            *carry_out = (res >> 31) & 1u;
            break;
        }
        case R_RRX:
            *result = (static_cast<uint32_t>(carry_in) << 31) | (x >> 1);
            *carry_out = x & 1u;
            break;
    }
}

struct AddRes { uint32_t result; int carry; int overflow; };
static AddRes add_with_carry(uint32_t a, uint32_t b, int cin) {
    uint64_t usum = static_cast<uint64_t>(a) + b + static_cast<uint64_t>(cin);
    int64_t ssum = static_cast<int64_t>(static_cast<int32_t>(a)) +
                   static_cast<int32_t>(b) + cin;
    AddRes r;
    r.result = static_cast<uint32_t>(usum & 0xffffffffu);
    r.carry = (usum != r.result) ? 1 : 0;
    r.overflow = (ssum != static_cast<int32_t>(r.result)) ? 1 : 0;
    return r;
}

static int gidx(unsigned reg) { return guest_reg_of(reg); }

static int getC(const State& s) { return (s.nzcv >> 29) & 1; }
static int getV(const State& s) { return (s.nzcv >> 28) & 1; }
static void setNZCV(State& s, int n, int z, int c, int v) {
    s.nzcv = (static_cast<uint32_t>(n) << 31) |
             (static_cast<uint32_t>(z) << 30) |
             (static_cast<uint32_t>(c) << 29) |
             (static_cast<uint32_t>(v) << 28);
}

static bool ref_operand2(const cs_arm& d, int src_idx, int rot_idx,
                         const State& s, uint32_t* val, int* carry_out,
                         bool* carry_changed) {
    *carry_changed = false;
    *carry_out = getC(s);
    const cs_arm_op& src = d.operands[src_idx];
    if (rot_idx >= 0 && d.operands[rot_idx].type == ARM_OP_IMM &&
        src.type == ARM_OP_IMM) {
        uint32_t imm8 = static_cast<uint32_t>(src.imm);
        uint32_t r = static_cast<uint32_t>(d.operands[rot_idx].imm) & 31u;
        uint32_t v = (r == 0) ? imm8
                              : ((imm8 >> r) | (imm8 << ((32 - r) & 31)));
        *val = v;
        if (r != 0) { *carry_changed = true; *carry_out = (v >> 31) & 1u; }
        return true;
    }
    if (src.type == ARM_OP_IMM) {
        *val = static_cast<uint32_t>(src.imm);
        return true;
    }
    if (src.type != ARM_OP_REG) return false;
    int g = gidx(src.reg);
    if (g < 0) return false;
    uint32_t x = s.r[g];

    arm_shifter st = src.shift.type;
    if (st == ARM_SFT_INVALID) { *val = x; return true; }
    if (st == ARM_SFT_LSL && src.shift.value == 0) { *val = x; return true; }
    bool regsh = (st == ARM_SFT_LSL_REG || st == ARM_SFT_LSR_REG ||
                  st == ARM_SFT_ASR_REG || st == ARM_SFT_ROR_REG);
    if (!regsh && src.shift.value == 0 && st != ARM_SFT_RRX) {
        *val = x; return true;
    }
    RSh ty = R_LSL;
    switch (st) {
        case ARM_SFT_LSL: case ARM_SFT_LSL_REG: ty = R_LSL; break;
        case ARM_SFT_LSR: case ARM_SFT_LSR_REG: ty = R_LSR; break;
        case ARM_SFT_ASR: case ARM_SFT_ASR_REG: ty = R_ASR; break;
        case ARM_SFT_ROR: case ARM_SFT_ROR_REG: ty = R_ROR; break;
        case ARM_SFT_RRX: ty = R_RRX; break;
        default: return false;
    }
    int amount;
    if (ty == R_RRX) amount = 1;
    else if (regsh) {
        int sg = gidx(src.shift.value);
        if (sg < 0) return false;
        amount = static_cast<int>(s.r[sg] & 0xff);   // A8.4.2: R[s]<7:0>
    } else {
        amount = static_cast<int>(src.shift.value);
    }
    uint32_t res; int co;
    shift_c(x, ty, amount, getC(s), &res, &co);
    *val = res;
    if (!(ty != R_RRX && amount == 0)) {
        *carry_changed = true; *carry_out = co;
    }
    return true;
}

static bool ref_exec(const cs_insn* in, State& s) {
    const cs_arm& d = in->detail->arm;
    const bool S = d.update_flags;
    auto nz = [&](uint32_t r) {
        setNZCV(s, (r >> 31) & 1, r == 0 ? 1 : 0, getC(s), getV(s));
    };
    auto nz_c = [&](uint32_t r, int c) {
        setNZCV(s, (r >> 31) & 1, r == 0 ? 1 : 0, c, getV(s));
    };
    auto layout = [&](bool flags_only, int* rd, int* rn_idx, int* src_idx,
                      int* rot_idx) -> bool {
        *rot_idx = -1; *rd = -1;
        if (flags_only) {
            if (d.op_count == 2) { *rn_idx = 0; *src_idx = 1; }
            else if (d.op_count == 3 && d.operands[1].type == ARM_OP_IMM &&
                     d.operands[2].type == ARM_OP_IMM) {
                *rn_idx = 0; *src_idx = 1; *rot_idx = 2;
            } else return false;
        } else {
            if (d.operands[0].type != ARM_OP_REG) return false;
            *rd = gidx(d.operands[0].reg);
            if (*rd < 0) return false;
            if (d.op_count == 2) { *rn_idx = 0; *src_idx = 1; }
            else if (d.op_count == 3) { *rn_idx = 1; *src_idx = 2; }
            else { *rn_idx = 1; *src_idx = 2; *rot_idx = 3; }
        }
        return d.operands[*rn_idx].type == ARM_OP_REG;
    };

    int rd, rni, si, ri;
    switch (in->id) {
        case ARM_INS_AND: case ARM_INS_ORR: case ARM_INS_EOR:
        case ARM_INS_BIC: case ARM_INS_ORN: {
            if (!layout(false, &rd, &rni, &si, &ri)) return false;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, si, ri, s, &b, &co, &cc)) return false;
            uint32_t a = s.r[gidx(d.operands[rni].reg)], r;
            switch (in->id) {
                case ARM_INS_AND: r = a & b; break;
                case ARM_INS_ORR: r = a | b; break;
                case ARM_INS_EOR: r = a ^ b; break;
                case ARM_INS_BIC: r = a & ~b; break;
                default:          r = a | ~b; break;
            }
            s.r[rd] = r;
            if (S) { if (cc) nz_c(r, co); else nz(r); }
            return true;
        }
        case ARM_INS_TST: case ARM_INS_TEQ: {
            if (!layout(true, &rd, &rni, &si, &ri)) return false;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, si, ri, s, &b, &co, &cc)) return false;
            uint32_t a = s.r[gidx(d.operands[rni].reg)];
            uint32_t r = (in->id == ARM_INS_TST) ? (a & b) : (a ^ b);
            if (cc) nz_c(r, co); else nz(r);
            return true;
        }
        case ARM_INS_ADD: case ARM_INS_SUB:
        case ARM_INS_ADC: case ARM_INS_SBC: {
            if (!layout(false, &rd, &rni, &si, &ri)) return false;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, si, ri, s, &b, &co, &cc)) return false;
            (void)co; (void)cc;   // arithmetic discards the shifter carry
            uint32_t a = s.r[gidx(d.operands[rni].reg)];
            AddRes ar;
            switch (in->id) {
                case ARM_INS_ADD: ar = add_with_carry(a, b, 0); break;
                case ARM_INS_SUB: ar = add_with_carry(a, ~b, 1); break;
                case ARM_INS_ADC: ar = add_with_carry(a, b, getC(s)); break;
                default:          ar = add_with_carry(a, ~b, getC(s)); break;
            }
            s.r[rd] = ar.result;
            if (S) setNZCV(s, (ar.result >> 31) & 1, ar.result == 0,
                           ar.carry, ar.overflow);
            return true;
        }
        case ARM_INS_CMP: case ARM_INS_CMN: {
            if (!layout(true, &rd, &rni, &si, &ri)) return false;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, si, ri, s, &b, &co, &cc)) return false;
            (void)co; (void)cc;
            uint32_t a = s.r[gidx(d.operands[rni].reg)];
            AddRes ar = (in->id == ARM_INS_CMP) ? add_with_carry(a, ~b, 1)
                                                : add_with_carry(a, b, 0);
            setNZCV(s, (ar.result >> 31) & 1, ar.result == 0, ar.carry,
                    ar.overflow);
            return true;
        }
        case ARM_INS_RSB: case ARM_INS_RSC: {
            if (!layout(false, &rd, &rni, &si, &ri)) return false;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, si, ri, s, &b, &co, &cc)) return false;
            (void)co; (void)cc;
            uint32_t a = s.r[gidx(d.operands[rni].reg)];
            AddRes ar = (in->id == ARM_INS_RSB)
                            ? add_with_carry(~a, b, 1)
                            : add_with_carry(~a, b, getC(s));
            s.r[rd] = ar.result;
            if (S) setNZCV(s, (ar.result >> 31) & 1, ar.result == 0,
                           ar.carry, ar.overflow);
            return true;
        }
        case ARM_INS_MOV: case ARM_INS_MOVS: case ARM_INS_MVN: {
            if (d.operands[0].type != ARM_OP_REG) return false;
            rd = gidx(d.operands[0].reg);
            if (rd < 0) return false;
            ri = (d.op_count == 3) ? 2 : -1;
            uint32_t b; int co; bool cc;
            if (!ref_operand2(d, 1, ri, s, &b, &co, &cc)) return false;
            uint32_t r = (in->id == ARM_INS_MVN) ? ~b : b;
            s.r[rd] = r;
            if (S) { if (cc) nz_c(r, co); else nz(r); }
            return true;
        }
        case ARM_INS_LSL: case ARM_INS_LSR: case ARM_INS_ASR:
        case ARM_INS_ROR: case ARM_INS_RRX: {
            if (d.operands[0].type != ARM_OP_REG) return false;
            rd = gidx(d.operands[0].reg);
            if (rd < 0 || d.operands[1].type != ARM_OP_REG) return false;
            int rn = gidx(d.operands[1].reg);
            if (rn < 0) return false;
            RSh ty = R_LSL;
            switch (in->id) {
                case ARM_INS_LSL: ty = R_LSL; break;
                case ARM_INS_LSR: ty = R_LSR; break;
                case ARM_INS_ASR: ty = R_ASR; break;
                case ARM_INS_ROR: ty = R_ROR; break;
                default:          ty = R_RRX; break;
            }
            uint32_t x = s.r[rn];
            int amount = 1;
            if (ty != R_RRX) {
                if (d.op_count == 3) {
                    const cs_arm_op& am = d.operands[2];
                    if (am.type == ARM_OP_IMM) {
                        amount = static_cast<int>(am.imm);
                    } else {
                        int sg = gidx(am.reg);
                        if (sg < 0) return false;
                        amount = static_cast<int>(s.r[sg] & 0xff);
                    }
                } else {
                    const cs_arm_op& op1 = d.operands[1];
                    if (op1.shift.type == ARM_SFT_INVALID &&
                        op1.shift.value == 0) {
                        x = s.r[rd];
                        amount = static_cast<int>(s.r[rn] & 0xff);
                    } else if (op1.shift.type >= ARM_SFT_LSL_REG) {
                        int sg = gidx(op1.shift.value);
                        if (sg < 0) return false;
                        amount = static_cast<int>(s.r[sg] & 0xff);
                    } else {
                        amount = static_cast<int>(op1.shift.value);
                    }
                }
            }
            uint32_t res; int co;
            shift_c(x, ty, amount, getC(s), &res, &co);
            s.r[rd] = res;
            if (S) {
                if (ty != R_RRX && amount == 0) nz(res);
                else nz_c(res, co);
            }
            return true;
        }
        case ARM_INS_MUL: case ARM_INS_MLA: case ARM_INS_MLS: {
            if (d.operands[0].type != ARM_OP_REG) return false;
            rd = gidx(d.operands[0].reg);
            int rn = gidx(d.operands[1].reg);
            int rm = gidx(d.operands[(d.op_count >= 3) ? 2 : 1].reg);
            if (rd < 0 || rn < 0 || rm < 0) return false;
            uint32_t p = s.r[rn] * s.r[rm], r = p;
            if (in->id != ARM_INS_MUL) {
                int ra = gidx(d.operands[3].reg);
                if (ra < 0) return false;
                r = (in->id == ARM_INS_MLA) ? (s.r[ra] + p) : (s.r[ra] - p);
            }
            s.r[rd] = r;
            if (S) nz(r);
            return true;
        }
        case ARM_INS_UMULL: case ARM_INS_SMULL:
        case ARM_INS_UMLAL: case ARM_INS_SMLAL: {
            int lo = gidx(d.operands[0].reg), hi = gidx(d.operands[1].reg);
            int rn = gidx(d.operands[2].reg), rm = gidx(d.operands[3].reg);
            if (lo < 0 || hi < 0 || rn < 0 || rm < 0) return false;
            bool sgn = (in->id == ARM_INS_SMULL || in->id == ARM_INS_SMLAL);
            uint64_t prod = sgn
                ? static_cast<uint64_t>(
                      static_cast<int64_t>(static_cast<int32_t>(s.r[rn])) *
                      static_cast<int64_t>(static_cast<int32_t>(s.r[rm])))
                : (static_cast<uint64_t>(s.r[rn]) * s.r[rm]);
            if (in->id == ARM_INS_UMLAL || in->id == ARM_INS_SMLAL)
                prod += (static_cast<uint64_t>(s.r[hi]) << 32) | s.r[lo];
            s.r[lo] = static_cast<uint32_t>(prod);
            s.r[hi] = static_cast<uint32_t>(prod >> 32);
            // A8.8.190: N = result<63>, Z over the FULL 64-bit result.
            if (S) setNZCV(s, (prod >> 63) & 1, prod == 0, getC(s), getV(s));
            return true;
        }
        case ARM_INS_CLZ: case ARM_INS_RBIT: case ARM_INS_REV:
        case ARM_INS_REV16: case ARM_INS_REVSH: {
            rd = gidx(d.operands[0].reg);
            int rm = gidx(d.operands[1].reg);
            if (rd < 0 || rm < 0) return false;
            uint32_t x = s.r[rm], r = 0;
            switch (in->id) {
                case ARM_INS_CLZ: {
                    int n = 0;
                    while (n < 32 && !((x >> (31 - n)) & 1u)) ++n;
                    r = static_cast<uint32_t>(n);
                    break;
                }
                case ARM_INS_RBIT:
                    for (int i = 0; i < 32; ++i)
                        if ((x >> i) & 1u) r |= 1u << (31 - i);
                    break;
                case ARM_INS_REV:
                    r = ((x & 0xffu) << 24) | ((x & 0xff00u) << 8) |
                        ((x >> 8) & 0xff00u) | ((x >> 24) & 0xffu);
                    break;
                case ARM_INS_REV16:
                    r = ((x & 0x00ff00ffu) << 8) | ((x >> 8) & 0x00ff00ffu);
                    break;
                default: {
                    // A8.8.148 REVSH reads only the LOW halfword.
                    uint32_t lo16 = ((x & 0xffu) << 8) | ((x >> 8) & 0xffu);
                    r = static_cast<uint32_t>(
                        static_cast<int32_t>(static_cast<int16_t>(lo16)));
                    break;
                }
            }
            s.r[rd] = r;
            return true;
        }
        case ARM_INS_UXTB: case ARM_INS_UXTH:
        case ARM_INS_SXTB: case ARM_INS_SXTH: {
            rd = gidx(d.operands[0].reg);
            int rm = gidx(d.operands[1].reg);
            if (rd < 0 || rm < 0) return false;
            int rot = (d.operands[1].shift.type == ARM_SFT_ROR)
                          ? (static_cast<int>(d.operands[1].shift.value) & 31)
                          : 0;
            uint32_t x = s.r[rm];
            if (rot) x = (x >> rot) | (x << (32 - rot));
            switch (in->id) {
                case ARM_INS_UXTB: s.r[rd] = x & 0xffu; break;
                case ARM_INS_UXTH: s.r[rd] = x & 0xffffu; break;
                case ARM_INS_SXTB:
                    s.r[rd] = static_cast<uint32_t>(
                        static_cast<int32_t>(static_cast<int8_t>(x))); break;
                default:
                    s.r[rd] = static_cast<uint32_t>(
                        static_cast<int32_t>(static_cast<int16_t>(x))); break;
            }
            return true;
        }
        case ARM_INS_UXTAB: case ARM_INS_UXTAH:
        case ARM_INS_SXTAB: case ARM_INS_SXTAH: {
            rd = gidx(d.operands[0].reg);
            int rn = gidx(d.operands[1].reg), rm = gidx(d.operands[2].reg);
            if (rd < 0 || rn < 0 || rm < 0) return false;
            int rot = (d.operands[2].shift.type == ARM_SFT_ROR)
                          ? (static_cast<int>(d.operands[2].shift.value) & 31)
                          : 0;
            uint32_t x = s.r[rm], e;
            if (rot) x = (x >> rot) | (x << (32 - rot));
            switch (in->id) {
                case ARM_INS_UXTAB: e = x & 0xffu; break;
                case ARM_INS_UXTAH: e = x & 0xffffu; break;
                case ARM_INS_SXTAB:
                    e = static_cast<uint32_t>(
                        static_cast<int32_t>(static_cast<int8_t>(x))); break;
                default:
                    e = static_cast<uint32_t>(
                        static_cast<int32_t>(static_cast<int16_t>(x))); break;
            }
            s.r[rd] = s.r[rn] + e;
            return true;
        }
        case ARM_INS_UBFX: case ARM_INS_SBFX: {
            rd = gidx(d.operands[0].reg);
            int rn = gidx(d.operands[1].reg);
            if (rd < 0 || rn < 0) return false;
            int lsb = static_cast<int>(d.operands[2].imm);
            int w = static_cast<int>(d.operands[3].imm);
            if (w <= 0 || w > 32 || lsb < 0 || lsb + w > 32) return true;
            uint32_t f = (s.r[rn] >> lsb) &
                         ((w == 32) ? 0xffffffffu : ((1u << w) - 1u));
            if (in->id == ARM_INS_SBFX && w < 32 && ((f >> (w - 1)) & 1u))
                f |= ~((1u << w) - 1u);
            s.r[rd] = f;
            return true;
        }
        case ARM_INS_BFI: {
            rd = gidx(d.operands[0].reg);
            int rn = gidx(d.operands[1].reg);
            if (rd < 0 || rn < 0) return false;
            int lsb = static_cast<int>(d.operands[2].imm);
            int w = static_cast<int>(d.operands[3].imm);
            if (w <= 0 || w > 32 || lsb < 0 || lsb + w > 32) return false;
            uint32_t m = (w == 32) ? 0xffffffffu : (((1u << w) - 1u) << lsb);
            s.r[rd] = (s.r[rd] & ~m) | ((s.r[rn] << lsb) & m);
            return true;
        }
        case ARM_INS_BFC: {
            rd = gidx(d.operands[0].reg);
            if (rd < 0) return false;
            int lsb = static_cast<int>(d.operands[1].imm);
            int w = static_cast<int>(d.operands[2].imm);
            if (w <= 0 || w > 32 || lsb < 0 || lsb + w > 32) return false;
            uint32_t m = (w == 32) ? 0xffffffffu : (((1u << w) - 1u) << lsb);
            s.r[rd] &= ~m;
            return true;
        }
        case ARM_INS_UDIV: case ARM_INS_SDIV: {
            rd = gidx(d.operands[0].reg);
            int rn = gidx(d.operands[1].reg), rm = gidx(d.operands[2].reg);
            if (rd < 0 || rn < 0 || rm < 0) return false;
            uint32_t a = s.r[rn], b = s.r[rm];
            if (b == 0) { s.r[rd] = 0; return true; }
            if (in->id == ARM_INS_UDIV) { s.r[rd] = a / b; return true; }
            int32_t sa = static_cast<int32_t>(a), sb = static_cast<int32_t>(b);
            s.r[rd] = (sa == INT32_MIN && sb == -1)
                          ? 0x80000000u
                          : static_cast<uint32_t>(sa / sb);
            return true;
        }
        default:
            return false;
    }
}

static const char* kAsmSrc =
    "ands r0, r1, r2\n"                  "ands r0, r1, r2, lsl #1\n"
    "ands r0, r1, r2, lsl #31\n"         "ands r0, r1, r2, lsr #1\n"
    "ands r0, r1, r2, lsr #32\n"         "ands r0, r1, r2, asr #17\n"
    "ands r0, r1, r2, asr #32\n"         "ands r0, r1, r2, ror #7\n"
    "ands r0, r1, r2, rrx\n"             "ands r0, r1, r2, lsl r3\n"
    "ands r0, r1, r2, lsr r3\n"          "ands r0, r1, r2, asr r3\n"
    "ands r0, r1, r2, ror r3\n"          "orrs r0, r1, r2, lsl #5\n"
    "eors r0, r1, r2, asr #5\n"          "bics r0, r1, r2, ror #5\n"
    "ands r0, r1, #0xff\n"               "ands r0, r1, #0xff000000\n"
    "orrs r0, r1, #0x3fc\n"              "eors r0, r1, #0x80000000\n"
    "bics r0, r1, #0x0000ff00\n"         "and r0, r1, r2, lsl r3\n"
    "orr r0, r1, r2, lsr r3\n"           "eor r0, r1, r2, asr r3\n"
    "bic r0, r1, r2, ror r3\n"
    "tst r1, r2\n"                       "tst r1, r2, lsl #3\n"
    "tst r1, r2, lsr r3\n"               "teq r1, r2\n"
    "teq r1, r2, asr #9\n"               "tst r1, #0xf0\n"
    "cmp r1, r2\n"                       "cmp r1, r2, lsl #2\n"
    "cmp r1, #0x1000\n"                  "cmp r1, #0xff\n"
    "cmn r1, r2\n"                       "cmn r1, #0x3f\n"
    "adds r0, r1, r2\n"                  "adds r0, r1, r2, lsl #3\n"
    "adds r0, r1, r2, ror #3\n"          "adds r0, r1, #0x1000\n"
    "adds r0, r1, #0xff\n"               "subs r0, r1, r2\n"
    "subs r0, r1, r2, asr #4\n"          "subs r0, r1, #1\n"
    "adcs r0, r1, r2\n"                  "adcs r0, r1, r2, lsl #2\n"
    "sbcs r0, r1, r2\n"                  "rsbs r0, r1, r2\n"
    "rsbs r0, r1, #0\n"                  "rsbs r0, r1, #0x100\n"
    "rscs r0, r1, r2\n"                  "rscs r0, r1, #0\n"
    "add r0, r1, r2, lsl r3\n"           "sub r0, r1, r2, lsr r3\n"
    "movs r0, r1\n"                      "movs r0, r1, lsl #4\n"
    "movs r0, r1, lsr #20\n"             "movs r0, r1, asr #20\n"
    "movs r0, r1, ror #20\n"             "movs r0, r1, rrx\n"
    "movs r0, #0xff\n"                   "movs r0, #0xff000000\n"
    "mvns r0, r1\n"                      "mvns r0, r1, lsl #3\n"
    "mvns r0, r1, rrx\n"                 "mvn r0, r1, ror r3\n"
    "lsls r0, r1, #7\n"                  "lsrs r0, r1, #7\n"
    "asrs r0, r1, #7\n"                  "rors r0, r1, #7\n"
    "lsls r0, r1, r3\n"                  "lsrs r0, r1, r3\n"
    "asrs r0, r1, r3\n"                  "rors r0, r1, r3\n"
    "lsl r0, r1, r3\n"                   "lsr r0, r1, r3\n"
    "asr r0, r1, r3\n"                   "ror r0, r1, r3\n"
    "rrxs r0, r1\n"
    "mul r0, r1, r2\n"                   "muls r0, r1, r2\n"
    "mla r0, r1, r2, r4\n"               "mlas r0, r1, r2, r4\n"
    "mls r0, r1, r2, r4\n"               "umull r4, r5, r1, r2\n"
    "smull r4, r5, r1, r2\n"             "umlal r4, r5, r1, r2\n"
    "smlal r4, r5, r1, r2\n"             "umulls r4, r5, r1, r2\n"
    "smlals r4, r5, r1, r2\n"            "udiv r0, r1, r2\n"
    "sdiv r0, r1, r2\n"
    "clz r0, r1\n"                       "rbit r0, r1\n"
    "rev r0, r1\n"                       "rev16 r0, r1\n"
    "revsh r0, r1\n"                     "uxtb r0, r1\n"
    "uxth r0, r1\n"                      "sxtb r0, r1\n"
    "sxth r0, r1\n"                      "uxtb r0, r1, ror #8\n"
    "uxth r0, r1, ror #16\n"             "sxtb r0, r1, ror #24\n"
    "uxtab r0, r4, r1\n"                 "uxtah r0, r4, r1\n"
    "sxtab r0, r4, r1, ror #8\n"         "ubfx r0, r1, #3, #7\n"
    "sbfx r0, r1, #3, #7\n"              "ubfx r0, r1, #0, #32\n"
    "bfi r0, r1, #5, #11\n"              "bfc r0, #5, #11\n";

static std::vector<uint8_t> slurp(const char* path) {
    std::vector<uint8_t> v;
    FILE* f = std::fopen(path, "rb");
    if (!f) { std::fprintf(stderr, "cannot open %s\n", path); std::exit(1); }
    uint8_t buf[4096];
    size_t got;
    while ((got = std::fread(buf, 1, sizeof(buf), f)) > 0)
        v.insert(v.end(), buf, buf + got);
    std::fclose(f);
    return v;
}

static void gen(const char* blob_path, const char* out_s) {
    std::vector<uint8_t> code = slurp(blob_path);
    csh h;
    cs_open(CS_ARCH_ARM, CS_MODE_ARM, &h);
    cs_option(h, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn* insns = nullptr;
    size_t n = cs_disasm(h, code.data(), code.size(), 0x1000, 0, &insns);

    std::string body = ".text\n";
    DiffRegs regs;
    int emitted = 0;
    for (size_t i = 0; i < n; ++i) {
        TextA64Sink sink;
        XlatOptions opts;
        XlatHooks hooks;
        hooks.warn = [](const std::string&) {};
        XlatCore core(sink, regs, opts, hooks);
        core.set_stream(insns, n);
        regs.reset();
        if (core.translate(i) != XlatCore::Result::Ok) {
            std::fprintf(stderr, "gen: skipping [%zu] %s %s\n", i,
                         insns[i].mnemonic, insns[i].op_str);
            continue;
        }
        char name[64];
        std::snprintf(name, sizeof(name), "_case_%zu", i);
        body += ".globl "; body += name; body += "\n.p2align 2\n";
        body += name; body += ":\n";
        body += "  stp x19, x20, [sp, #-64]!\n"
                "  stp x21, x22, [sp, #16]\n"
                "  stp x23, x24, [sp, #32]\n";
        for (int g = 0; g <= 14; ++g) {
            char l[64];
            std::snprintf(l, sizeof(l), "  ldr w%d, [x0, #%d]\n", 1 + g, 4 * g);
            body += l;
        }
        body += "  ldr w16, [x0, #64]\n  msr nzcv, x16\n";
        std::string t = sink.text();
        size_t pos = 0;
        while (pos < t.size()) {
            size_t e = t.find('\n', pos);
            if (e == std::string::npos) e = t.size();
            std::string ln = t.substr(pos, e - pos);
            pos = e + 1;
            size_t ns = ln.find_first_not_of(" \t");
            if (ns == std::string::npos) continue;
            if (ln.compare(ns, 2, "//") == 0) continue;
            std::string fixed;   // per-case label namespace
            for (size_t k = 0; k < ln.size(); ++k) {
                if (ln.compare(k, 2, ".L") == 0) {
                    fixed += ".Lc" + std::to_string(i) + "_";
                    k += 1;
                } else fixed += ln[k];
            }
            body += fixed + "\n";
        }
        body += "  mrs x16, nzcv\n";
        for (int g = 0; g <= 14; ++g) {
            char l[64];
            std::snprintf(l, sizeof(l), "  str w%d, [x0, #%d]\n", 1 + g, 4 * g);
            body += l;
        }
        body += "  str w16, [x0, #64]\n"
                "  ldp x23, x24, [sp, #32]\n"
                "  ldp x21, x22, [sp, #16]\n"
                "  ldp x19, x20, [sp], #64\n"
                "  ret\n";
        ++emitted;
    }
    FILE* o = std::fopen(out_s, "w");
    std::fwrite(body.data(), 1, body.size(), o);
    std::fclose(o);
    std::fprintf(stderr, "gen: %d/%zu cases emitted\n", emitted, n);
    cs_free(insns, n);
    cs_close(&h);
}

static const uint32_t kVals[] = {
    0u, 1u, 2u, 3u, 7u, 31u, 32u, 33u, 63u, 255u, 256u,
    0x7fffffffu, 0x80000000u, 0xffffffffu, 0xfffffffeu,
    0x0f0f0f0fu, 0xdeadbeefu, 0x12345678u, 0x00ff00ffu, 0xabcd1234u
};
static const size_t kNVals = sizeof(kVals) / sizeof(kVals[0]);
// Shift amounts chosen to straddle every A32-vs-A64 boundary: 0 (carry
// unchanged), 1, exactly 32, and a value whose low byte is 0x21 == 33.
static const uint32_t kShAmts[4] = {0u, 1u, 32u, 0xffffff21u};

static int run(const char* blob_path, const char* dylib) {
    std::vector<uint8_t> code = slurp(blob_path);
    csh h;
    cs_open(CS_ARCH_ARM, CS_MODE_ARM, &h);
    cs_option(h, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn* insns = nullptr;
    size_t n = cs_disasm(h, code.data(), code.size(), 0x1000, 0, &insns);

    void* lib = dlopen(dylib, RTLD_NOW);
    if (!lib) { std::fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }

    int checked = 0, failed = 0, skipped = 0;
    for (size_t i = 0; i < n; ++i) {
        char name[64];
        std::snprintf(name, sizeof(name), "case_%zu", i);
        void (*fn)(State*) =
            reinterpret_cast<void (*)(State*)>(dlsym(lib, name));
        if (!fn) { ++skipped; continue; }
        bool any = false, bad = false;
        for (size_t a = 0; a < kNVals && !bad; ++a) {
            for (size_t b = 0; b < kNVals && !bad; ++b) {
                for (int cin = 0; cin < 2 && !bad; ++cin) {
                    for (int shk = 0; shk < 4 && !bad; ++shk) {
                        State st{};
                        for (int g = 0; g < 16; ++g)
                            st.r[g] = 0x11111111u * static_cast<uint32_t>(g + 1);
                        st.r[1] = kVals[a];
                        st.r[2] = kVals[b];
                        st.r[3] = kShAmts[shk];
                        st.r[4] = kVals[(a + 5) % kNVals];
                        st.r[5] = kVals[(b + 3) % kNVals];
                        st.nzcv = (static_cast<uint32_t>(cin) << 29) |
                                  ((a & 1u) << 28) | ((b & 1u) << 31) |
                                  (((a ^ b) & 1u) << 30);
                        State ref = st, got = st;
                        if (!ref_exec(&insns[i], ref)) { any = false; goto next; }
                        any = true;
                        fn(&got);
                        got.r[15] = ref.r[15] = 0;   // PC is not modelled
                        if (std::memcmp(&ref, &got, sizeof(State)) == 0) continue;
                        std::fprintf(stderr,
                            "MISMATCH [%zu] %s %s\n  in : r1=%08x r2=%08x "
                            "r3=%08x r4=%08x r5=%08x nzcv=%08x\n",
                            i, insns[i].mnemonic, insns[i].op_str, st.r[1],
                            st.r[2], st.r[3], st.r[4], st.r[5], st.nzcv);
                        for (int g = 0; g < 15; ++g)
                            if (ref.r[g] != got.r[g])
                                std::fprintf(stderr,
                                    "  r%-2d ref=%08x got=%08x\n", g,
                                    ref.r[g], got.r[g]);
                        if (ref.nzcv != got.nzcv)
                            std::fprintf(stderr,
                                "  NZCV ref=%c%c%c%c got=%c%c%c%c\n",
                                static_cast<char>((ref.nzcv >> 31 & 1) + '0'),
                                static_cast<char>((ref.nzcv >> 30 & 1) + '0'),
                                static_cast<char>((ref.nzcv >> 29 & 1) + '0'),
                                static_cast<char>((ref.nzcv >> 28 & 1) + '0'),
                                static_cast<char>((got.nzcv >> 31 & 1) + '0'),
                                static_cast<char>((got.nzcv >> 30 & 1) + '0'),
                                static_cast<char>((got.nzcv >> 29 & 1) + '0'),
                                static_cast<char>((got.nzcv >> 28 & 1) + '0'));
                        bad = true;
                    }
                }
            }
        }
    next:
        if (!any) { ++skipped; continue; }
        ++checked;
        if (bad) ++failed;
    }
    std::fprintf(stderr,
                 "\n=== differential: %d instructions checked, %d FAILED, "
                 "%d skipped (no reference model) ===\n",
                 checked, failed, skipped);
    cs_free(insns, n);
    cs_close(&h);
    return failed ? 1 : 0;
}

}  // namespace selftest
}  // namespace arm32rc
}  // namespace macapk

int main(int argc, char** argv) {
    using namespace macapk::arm32rc::selftest;
    if (argc >= 3 && std::strcmp(argv[1], "asm") == 0) {
        FILE* o = std::fopen(argv[2], "w");
        std::fputs(kAsmSrc, o);
        std::fclose(o);
        return 0;
    }
    if (argc >= 4 && std::strcmp(argv[1], "gen") == 0) { gen(argv[2], argv[3]); return 0; }
    if (argc >= 4 && std::strcmp(argv[1], "run") == 0) return run(argv[2], argv[3]);
    std::fprintf(stderr,
                 "usage: asm <out.s> | gen <blob> <out.s> | run <blob> <dylib>\n");
    return 2;
}

#endif  // MACAPK_ARM32RC_SELFTEST
