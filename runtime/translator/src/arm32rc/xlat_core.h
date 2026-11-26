// xlat_core.h -- direct ARM32/Thumb -> arm64 recompiler: the CORE
// integer, data-processing and control-flow translation half.
//
// This replaces the "emit LLVM IR text" half of arm32_lifter.cpp with
// "emit A64". Decoding stays Capstone-based and unchanged: this file
// consumes a decoded `cs_insn` and emits arm64 through an abstract
// sink. Memory access (LDR/STR/LDM/STM/PUSH/POP), VFP/NEON, the
// saturating / parallel-SIMD families and the exclusive monitors are
// deliberately NOT handled here; they belong to the sibling files.
//
// =====================================================================
//  INTEGRATION ASSUMPTIONS (read this before wiring the agents together)
// =====================================================================
//
// The A64 encoder (`arm32rc/a64_emit.h`, agent B1) and the register /
// flag model (`arm32rc/regmap.h` + `arm32rc/guest_state.h`, agent B2)
// did not exist on disk when this file was written. Rather than guess
// their exact method names, this file declares the NARROW surface it
// actually needs:
//
//   * `A64Sink`  -- every A64 instruction form the core emits.
//   * `RegFile`  -- guest-register read/write plus scratch allocation.
//
// Integration is therefore mechanical and lives in exactly one place:
// write one adapter class deriving from `A64Sink` that forwards to
// B1's encoder (roughly one line per method), and one deriving from
// `RegFile` that forwards to B2's model. Nothing in xlat_core.cpp has
// to change. `TextA64Sink` below is a complete worked example of such
// an adapter (it renders GNU-as text, which is also how the core is
// unit-tested: emit, assemble, compare).
//
// The behavioural assumptions the core makes about B2's model, which
// the adapter MUST honour:
//
//  A1. Host register numbering is 0..30 == X0..X30 and 31 == XZR/WZR.
//      SP is never used to hold a guest value by this core.
//
//  A2. A guest 32-bit register value is held ZERO-EXTENDED in its host
//      64-bit register. Every write this core performs uses a W-form
//      (which zeroes the upper 32 bits on AArch64), so the invariant is
//      self-maintaining. UMLAL/SMLAL rely on it to build the 64-bit
//      accumulator with a single `orr xA, xLo, xHi, lsl #32`.
//
//  A3. Guest NZCV lives in the HOST PSTATE NZCV between instructions of
//      a translated block. This is the whole point of a direct
//      recompiler: ARM32 and A64 agree bit-for-bit on N, Z, C (C is
//      NOT-borrow for subtract in both) and V for the arithmetic
//      family, so ADDS/SUBS/ADCS/SBCS/CMP/CMN translate 1:1 and cost
//      nothing. Consequences the rest of the pipeline must respect:
//        - No emitted host instruction may set flags unless the guest
//          instruction being translated sets flags. This core never
//          emits a stray flag-setter (address math uses ADD, not ADDS;
//          selects that would need a CMP are replaced by branch-free
//          mask arithmetic or are bracketed by an explicit save).
//        - PSTATE is not preserved across a host call, and the guest's
//          flags must survive a guest BL (the LLVM lifter modelled
//          flags as per-function allocas, so they did survive). The
//          call sequence therefore has to spill/reload NZCV; that
//          belongs to the ABI owner, so this core routes calls through
//          `A64Sink::call_direct` / `call_indirect` and expects THOSE
//          to do the bracketing. See `XlatOptions::calls_preserve_nzcv`.
//
//  A4. `RegFile::read()` results stay valid for the whole of one
//      instruction's translation. The core needs up to 6 concurrent
//      reads plus 8 concurrent scratch registers (the peak is an
//      S-suffixed logical op with a register-controlled shift, which
//      needs the shifted value, the PSTATE snapshot and the runtime
//      shifter-carry chain live at the same time). If `read()` itself
//      spills into scratch in a memory-backed model, that must come
//      from a separate pool.
//
//  A5. Guest R15 (PC) is never read through `RegFile`. It is a
//      translate-time constant and the core materialises it itself
//      (see the PC-value rules below, which are load-bearing).
//
// =====================================================================
//  WHY A64 IS NOT A DROP-IN FOR A32 (the three traps)
// =====================================================================
//
//  T1. A64 `ANDS`/`BICS` set C and V to ZERO
//      (DDI 0487 C6.2.13: "PSTATE.<N,Z,C,V> = <result<31>,
//      IsZeroBit(result), '0', '0'>"), whereas A32 ANDS/ORRS/EORS/BICS/
//      TST/TEQ/MOVS/MVNS set N and Z from the result, set C from the
//      BARREL SHIFTER carry-out, and leave V UNCHANGED
//      (DDI 0406C.d A8.8.13 and friends). So the logical S-forms cannot
//      use A64's S-forms directly; the core does a read-modify-write of
//      PSTATE. `XlatHooks::cv_dead_after` lets a CFG-aware caller opt
//      into the cheap single-instruction form where C/V are provably
//      dead.
//
//  T2. A64 register-controlled shifts take the amount MOD datasize
//      (DDI 0487 C6.2.x LSLV), while A32 uses the full R[s]<7:0> and
//      saturates: LSL/LSR by >= 32 gives 0, ASR by >= 32 gives the sign.
//      The core emits branch-free fixups so it never disturbs PSTATE.
//
//  T3. A64 has no predication. Every A32 condition code and every
//      Thumb IT-block slot becomes either a CSEL (single GPR write, no
//      flag update) or a branch-over (anything with flag or memory side
//      effects, matching the A8.3 rule that a failed predicate performs
//      NO memory access). The 4-bit condition encodings themselves are
//      identical between A32 Table A8-1 and A64 Table C1-1 for 0b0000
//      through 0b1110, so the mapping is the identity. 0b1111 is the one
//      row that differs and it never reaches this code: in A64 it is NV,
//      which behaves as AL, while in A32 it is not a predicate at all
//      but the escape into the unconditional-instruction encoding space
//      (BLX-immediate, PLD, SETEND), which capstone reports with a cc of
//      AL or INVALID.
//
// Every claim above was re-verified against DDI 0406C.d and DDI 0487G.b
// while writing this file, and the translations are checked by
// execution: see the differential self-test at the bottom of
// xlat_core.cpp.
//
// No em dashes in this file (project convention). Comments explain WHY.

#ifndef MACAPK_RUNTIME_TRANSLATOR_ARM32RC_XLAT_CORE_H_
#define MACAPK_RUNTIME_TRANSLATOR_ARM32RC_XLAT_CORE_H_

#include <capstone/capstone.h>

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace macapk {
namespace arm32rc {

// ---------------------------------------------------------------------
// Small shared vocabulary
// ---------------------------------------------------------------------

// A64 condition codes. The encodings are IDENTICAL to A32's, so
// `map_cond()` is the identity on the value; the enum exists so callers
// cannot accidentally pass a raw capstone arm_cc (which is 1-based).
enum class Cond : uint8_t {
    EQ = 0, NE = 1, CS = 2, CC = 3, MI = 4, PL = 5, VS = 6, VC = 7,
    HI = 8, LS = 9, GE = 10, LT = 11, GT = 12, LE = 13, AL = 14, NV = 15
};

// Inverting a condition is a single bit flip for everything except
// AL/NV, which have no inverse (DDI 0487 C1.2.4).
inline Cond invert(Cond c) {
    return static_cast<Cond>(static_cast<uint8_t>(c) ^ 1u);
}
const char* cond_name(Cond c);

enum class Sh : uint8_t { LSL = 0, LSR = 1, ASR = 2, ROR = 3 };

// The ALU forms the core needs. ADD/SUB/ADC/SBC are the arithmetic
// family (flags map 1:1 to A32); AND/ORR/EOR/BIC/ORN/EON are the
// logical family (S-form flags do NOT map, see trap T1).
enum class AluOp : uint8_t {
    ADD, SUB, ADC, SBC, AND, ORR, EOR, BIC, ORN, EON
};
bool alu_is_arith(AluOp op);

enum class MulOp : uint8_t {
    MADD,    // Wd = Wa + Wn*Wm
    MSUB,    // Wd = Wa - Wn*Wm
    UMADDL,  // Xd = Xa + UInt(Wn)*UInt(Wm)
    SMADDL,  // Xd = Xa + SInt(Wn)*SInt(Wm)
    SMSUBL,  // Xd = Xa - SInt(Wn)*SInt(Wm)
};

// Single-source bit manipulation.
enum class Bit1Op : uint8_t {
    CLZ, RBIT, REV, REV16, SXTB, SXTH, UXTB, UXTH
};

// Extend kinds for the extended-register ADD used by (S|U)XTA(B|H).
enum class Ext : uint8_t { UXTB, UXTH, UXTW, SXTB, SXTH, SXTW };

enum class CselOp : uint8_t { CSEL, CSINC, CSINV, CSNEG };

// The system / hint / exception-generating forms. A32 and A64 have a
// one-for-one instruction for each of these, so they are real
// translations rather than "close enough": YIELD/SEV/SEVL/WFE/WFI are
// the same architectural hints in both, BKPT and A64's BRK both raise a
// Breakpoint Instruction exception, and UDF is permanently undefined in
// both ("Permanently Undefined generates an Undefined Instruction
// exception", Arm A32/T32 ISA, UDF; DDI 0406C.d A8.8.247).
enum class SysOp : uint8_t {
    Nop, Yield, Sev, Sevl, Wfe, Wfi, Breakpoint, Undefined
};

// Host register numbering (assumption A1).
static constexpr int kZR = 31;

// Byte offsets into the per-thread `GuestState` block of the two APSR
// fields that are NOT host PSTATE bits. Duplicated from guest_state.h
// rather than included, because this header is deliberately buildable
// without the sibling components (see MACAPK_XLAT_CORE_HAVE_SIBLINGS at
// the bottom); the siblings region static_asserts the two agree, so
// drift is a compile error rather than a wild store into guest memory.
static constexpr uint32_t kGuestStateApsrGeOffset = 0x108;
static constexpr uint32_t kGuestStateApsrQOffset  = 0x10c;

// A branch destination inside the current translation unit. Ownership of
// the fixup / veneering machinery is the encoder's (B1); the core only
// creates, binds and references labels.
struct Label {
    int id = -1;
    bool valid() const { return id >= 0; }
};

// ---------------------------------------------------------------------
// A64Sink -- the emit surface (adapter target for agent B1's encoder)
// ---------------------------------------------------------------------
//
// Every method emits exactly one A64 instruction unless documented
// otherwise. `sf64 == false` means the W (32-bit) form.
struct A64Sink {
    virtual ~A64Sink();

    // --- data processing ---------------------------------------------
    // rd = rn <op> (rm shifted by `sh` #amt). `amt` must be 0..31 (or
    // 0..63 for sf64). ROR is only legal for the logical ops; ADD/SUB
    // shifted-register accept LSL/LSR/ASR only (DDI 0487 C6.2.4).
    // `S` selects the flag-setting form (ADDS/SUBS/ADCS/SBCS/ANDS/BICS).
    // ADC/SBC ignore `sh`/`amt` (A64 has no shifted ADC form).
    virtual void alu_reg(AluOp op, int rd, int rn, int rm,
                         Sh sh, int amt, bool S, bool sf64) = 0;

    // rd = rn <op> #imm. The core guarantees `imm` is encodable for the
    // op: ADD/SUB get a value accepted by a64_encodable_add_imm(), the
    // logical ops get one accepted by a64_is_logical_imm(). ADC/SBC are
    // never passed here (no immediate form exists).
    //
    // `imm` is the VALUE, not the encoded field. For ADD/SUB that means
    // the implementation must itself notice a value above 0xfff whose
    // low 12 bits are zero and set the shift-by-12 bit; for the logical
    // ops it must derive N:immr:imms from the bitmask.
    virtual void alu_imm(AluOp op, int rd, int rn, uint64_t imm,
                         bool S, bool sf64) = 0;

    // rd = rn + (extend(rm) << shift). Used by UXTAB/UXTAH/SXTAB/SXTAH.
    virtual void alu_extended(AluOp op, int rd, int rn, int rm,
                              Ext ext, int shift, bool S, bool sf64) = 0;

    virtual void movz(int rd, uint16_t imm16, int shift, bool sf64) = 0;
    virtual void movn(int rd, uint16_t imm16, int shift, bool sf64) = 0;
    virtual void movk(int rd, uint16_t imm16, int shift, bool sf64) = 0;
    virtual void mov_reg(int rd, int rm, bool sf64) = 0;

    // Variable (register-amount) and fixed (immediate-amount) shifts.
    // `shift_reg` is LSLV/LSRV/ASRV/RORV and therefore takes the amount
    // MOD datasize (trap T2); the core compensates.
    virtual void shift_reg(Sh sh, int rd, int rn, int rm, bool sf64) = 0;
    virtual void shift_imm(Sh sh, int rd, int rn, int amt, bool sf64) = 0;
    // rd = (rn:rm) >> lsb, i.e. EXTR. `ror_imm` is EXTR with rn == rm.
    virtual void extr(int rd, int rn, int rm, int lsb, bool sf64) = 0;

    // UBFX / SBFX / BFI, expressed at the useful level rather than as
    // raw immr/imms so the adapter does not have to re-derive them.
    virtual void ubfx(int rd, int rn, int lsb, int width, bool sf64) = 0;
    virtual void sbfx(int rd, int rn, int lsb, int width, bool sf64) = 0;
    virtual void bfi(int rd, int rn, int lsb, int width, bool sf64) = 0;

    virtual void mul3(MulOp op, int rd, int rn, int rm, int ra) = 0;
    virtual void divide(bool is_signed, int rd, int rn, int rm,
                        bool sf64) = 0;
    virtual void bit1(Bit1Op op, int rd, int rn, bool sf64) = 0;

    virtual void csel(CselOp op, int rd, int rn, int rm, Cond c,
                      bool sf64) = 0;
    // rd = (cond) ? 1 : 0. Reads PSTATE, never writes it.
    virtual void cset(int rd, Cond c, bool sf64) = 0;

    // Guest-flag spill / reload. NZCV occupies bits 31..28 of the
    // transferred register.
    virtual void mrs_nzcv(int xd) = 0;
    virtual void msr_nzcv(int xn) = 0;

    // --- control flow -------------------------------------------------
    virtual Label new_label() = 0;
    virtual void bind(Label l) = 0;
    virtual void b(Label l) = 0;
    virtual void b_cond(Cond c, Label l) = 0;
    virtual void cbz(int rt, Label l, bool sf64) = 0;
    virtual void cbnz(int rt, Label l, bool sf64) = 0;

    // --- ABI-owned control flow --------------------------------------
    //
    // These are deliberately coarse. How a guest call maps onto the host
    // (whether guest state lives in host registers across it, whether
    // NZCV is bracketed, how a guest vaddr is resolved to translated
    // code) is a cross-cutting decision that does not belong to the
    // instruction translator, so the core states intent and the ABI
    // layer implements it.
    //
    // `guest_target` is the raw guest vaddr WITHOUT the per-image load
    // bias; the core reports it unbiased so the fixup layer can look it
    // up in the translated-function map the same way the LLVM lifter's
    // `__macapk_target_<hex>` symbols did.
    virtual void call_direct(uint32_t guest_target) = 0;
    virtual void call_indirect(int host_reg) = 0;
    virtual void tail_branch_direct(uint32_t guest_target) = 0;
    virtual void tail_branch_indirect(int host_reg) = 0;
    virtual void ret_from_function() = 0;

    // Load 32 bits of guest memory at the guest address held in
    // `addr_reg` into `rd`. Only used by the PC-relative literal-pool
    // path; the real load/store family is another file's job. Literal
    // pools are NOT constant-folded at translate time because the loader
    // applies relocations to them at load time (see emit_ldr_literal).
    virtual void load_guest_u32(int rd, int addr_reg) = 0;

    // Zero-extending load of 1 or 2 bytes of guest memory at the guest
    // address held in `addr_reg`, into `rd`. `addr_reg` is a scratch the
    // caller does not read again, so the implementation may widen it in
    // place. Used by the T32 table branches TBB / TBH, whose branch
    // offsets are read from a table in GUEST memory at run time.
    virtual void load_guest_zx(int rd, int addr_reg, int bytes) = 0;

    // Accessors for the per-thread `GuestState` block (guest_state.h),
    // which is where the parts of the APSR that do NOT live in the host
    // PSTATE are homed: GE[3:0] and the sticky Q. `scratch` is a
    // caller-owned register the implementation may clobber to derive the
    // GuestState pointer from guest SP + the arena base.
    virtual void guest_state_load(int rd, uint32_t byte_off, int scratch) = 0;
    virtual void guest_state_store(int rs, uint32_t byte_off, int scratch) = 0;

    // A32/T32 `SVC #imm`, the Linux supervisor call. Writes the syscall
    // result into `rd` (guest r0); guest r0-r3 are already in the host
    // argument registers, so an implementation that routes to a real
    // helper needs no marshalling. See BufSink::svc_bridge for what the
    // in-tree implementation does and why.
    virtual void svc_bridge(uint32_t imm, int rd) = 0;

    // The hint / breakpoint / permanently-undefined forms. `imm` is the
    // guest immediate, carried through so a crash report names the guest
    // site rather than a generic trap.
    virtual void sys_op(SysOp op, uint16_t imm) = 0;

    // Free-form annotation. Binary sinks ignore it; the text sink emits
    // a comment. The core uses it to record the guest instruction being
    // translated, which makes emitted output diffable against objdump.
    virtual void comment(const std::string& text);
};

// ---------------------------------------------------------------------
// RegFile -- guest register access (adapter target for agent B2)
// ---------------------------------------------------------------------
struct RegFile {
    virtual ~RegFile();

    // Host register currently holding guest R<g>, g in 0..14. May emit
    // a reload for a memory-backed model. Never called with g == 15.
    virtual int read(int g) = 0;

    // Host register into which the core will write guest R<g>'s new
    // value. For a register-resident model this is just the mapped
    // register; for a memory-backed model it is a scratch that
    // `write_end` spills. `write_begin` must NOT clobber the current
    // value of R<g> before it returns, because predicated writes read
    // the old value afterwards.
    virtual int write_begin(int g) = 0;
    virtual void write_end(int g) = 0;

    // Scratch registers. The core never holds more than 8 at once
    // (assumption A4).
    virtual int temp_alloc() = 0;
    virtual void temp_free(int host_reg) = 0;
};

// A scoped temp so early returns cannot leak a scratch register.
class Temp {
public:
    Temp(RegFile& rf) : rf_(&rf), r_(rf.temp_alloc()) {}
    ~Temp() { if (rf_) rf_->temp_free(r_); }
    Temp(const Temp&) = delete;
    Temp& operator=(const Temp&) = delete;
    int r() const { return r_; }
    operator int() const { return r_; }
private:
    RegFile* rf_;
    int r_;
};

// ---------------------------------------------------------------------
// Options and hooks
// ---------------------------------------------------------------------
struct XlatOptions {
    // Per-image load bias. PC reads are THE central runtime-vaddr
    // materialisation in this architecture: every `add rd,pc` and
    // `ldr rd,[pc,#N]; add rd,pc` PIC pointer flows through them, so
    // biasing PC biases them all. Carried over verbatim from the LLVM
    // lifter's load_reg(15) / compute_mem_addr / emit_adr.
    uint32_t load_bias = 0;

    // Thumb changes the PC read offset from +8 to +4 (A2.3.1).
    bool thumb = false;

    // Fail instead of emitting a no-op for an unhandled instruction.
    bool strict = false;

    // A32 modified immediates carry a shifter carry-out that is a
    // translate-time CONSTANT: ARMExpandImm_C leaves C unchanged when
    // the rotation is zero and sets it to imm32<31> otherwise. The LLVM
    // lifter deliberately never modelled it ("conservative", see
    // resolve_alu_src_c). Modelling it exactly is free here because the
    // value is known at translate time, so it defaults ON; set false to
    // reproduce the old lifter bit-for-bit.
    bool exact_modified_imm_carry = true;

    // An ALU write to R15 is architecturally a BRANCH. The LLVM lifter
    // lowered it as a dead store plus a loud warning, because every
    // known computed-jump idiom is intercepted upstream by the CFG
    // layer and a stray one would otherwise hide (the v552 gdx2d
    // wrong-switch-case bug). Falling through is definitely wrong, so
    // the default here is "warn AND emit the architecturally correct
    // indirect branch". Set false to restore fall-through.
    bool alu_pc_write_is_branch = true;

    // Whether A64Sink::call_direct / call_indirect already bracket the
    // call with an NZCV spill/reload. When false the core emits the
    // bracket itself, which costs an MRS + MSR and two scratch uses per
    // call. See assumption A3.
    bool calls_preserve_nzcv = true;

    // Window for the backward scan that recovers the literal of a
    // preceding `ldr rN,[pc,#imm]` (the PIC discrimination below).
    int pc_literal_scan_window = 16;
};

struct XlatHooks {
    // Read 4 image bytes at a guest vaddr (literal pools live in
    // PROGBITS). Return false when the address is not image-backed.
    std::function<bool(uint32_t vaddr, uint32_t* out)> read_image_u32;

    // True when the runtime vaddr lands in one of the image's pointer /
    // struct sections (.got, .data, .bss, .data.rel.ro). Used ONLY by
    // the `add rN, pc` PC-convention discrimination; .rodata and .text
    // must be excluded there so a 2-byte-aligned string target cannot
    // trigger a false override.
    std::function<bool(uint32_t vaddr)> in_pointer_target_range;

    // Optional flag-liveness oracle: true when guest C and V are dead
    // immediately after the instruction at `vaddr`. Enables the cheap
    // one-instruction ANDS form for the logical S-family (trap T1).
    // Defaults to "always live", which is always correct.
    std::function<bool(uint32_t vaddr)> cv_dead_after;

    // Resolve a guest vaddr to a label inside the function currently
    // being translated. Returning false means "not in this function",
    // which turns the branch into a tail branch to a translated target.
    std::function<bool(uint32_t vaddr, Label* out)> label_for_guest;
    // MACAPK_ARM32RC_FRAME_JUMP: true when a guest vaddr OUTSIDE this function
    // is a target the driver can reach with a direct, frame-keeping branch
    // (another translated extent's start or planned interior entry). A
    // TBB/TBH case that lands there is then a direct tail branch instead of a
    // trip through the guest-vaddr dispatcher, which would enter the target
    // with zeroed callee-saved registers.
    std::function<bool(uint32_t vaddr)> extern_tail_target;

    // Report a branch/call target that left the current function.
    // `is_bl` distinguishes an authoritative BL/BLX #imm (a function
    // ENTRY per the ARM PCS) from an ambiguous B-extern, exactly like
    // the LLVM lifter's extern_bl_targets vs extern_call_targets split.
    std::function<void(uint32_t vaddr, bool is_bl)> note_extern_target;

    std::function<void(const std::string& msg)> warn;
};

// ---------------------------------------------------------------------
// Encodability predicates
// ---------------------------------------------------------------------
//
// The core has to DECIDE between an immediate form and materialising a
// constant, so it needs these even though the encoding itself is the
// encoder's job. If B1's header exports equivalents, delete these and
// forward.

// True when `v` fits A64's ADD/SUB immediate: unsigned 12-bit,
// optionally shifted left by 12 (DDI 0487 C6.2.4).
bool a64_encodable_add_imm(uint64_t v);

// True when `v` is a valid A64 logical (bitmask) immediate for a
// register of `width` bits (32 or 64). A32's rotated-8-bit immediates
// and A64's bitmask immediates are unrelated encodings, so most A32
// logical immediates need materialising.
bool a64_is_logical_imm(uint64_t v, unsigned width);

// ---------------------------------------------------------------------
// Refusal tracing (MACAPK_ARM32RC_STUB_TRACE)
// ---------------------------------------------------------------------
//
// A translator that returns false emits NOTHING, so the guest
// instruction silently disappears and the driver only records a
// per-mnemonic tally. That tally cannot answer the only question that
// matters ("WHICH address, and WHY"), which is how `andeq` x186 and
// `ldrbvc` x169 sat unexplained. Setting MACAPK_ARM32RC_STUB_TRACE=1
// dumps one line per refusal to stderr:
//
//   [rc-stub] 0x000fe4a0 T core-refuse andeq r0, r0, r0 :: <reason>
//
// MACAPK_ARM32RC_STUB_TRACE=<path> writes to that file instead, which
// is what debug/implC_stub_census.py consumes. Off by default and
// cheap when off (one cached getenv).
bool rc_stub_trace_enabled();
void rc_stub_trace(uint32_t vaddr, bool thumb, const cs_insn* in,
                   const char* stage, const char* reason);

// True when the memory or SIMD stage claims this instruction id. The
// core's dispatch switch not having an entry is then EXPECTED (every
// LDR / VADD takes that path), so only the remainder is worth tracing.
bool mem_or_simd_owns(unsigned insn_id);

// ---------------------------------------------------------------------
// XlatCore
// ---------------------------------------------------------------------
class XlatCore {
public:
    XlatCore(A64Sink& sink, RegFile& regs,
             const XlatOptions& opts, const XlatHooks& hooks);

    enum class Result {
        Ok,           // translated, control falls through
        Terminated,   // translated, block ends here (branch / return)
        Unsupported,  // not a core-owned instruction; caller should try
                      // the memory / VFP / SIMD translators
        Failed        // core-owned but malformed (strict mode only)
    };

    // The decoded instruction stream for the function being translated.
    // The core needs it for the backward literal scan (PIC
    // discrimination) and for IT-block bookkeeping.
    void set_stream(const cs_insn* insns, size_t count);

    // Translate insns[idx]. Handles the instruction's condition code
    // itself (CSEL or branch-over, trap T3).
    Result translate(size_t idx);

    // Last error text when translate() returned Failed.
    const std::string& error() const { return error_; }

    // --- reusable pieces the sibling translators need -----------------

    // The value an A32 read of R15 yields for the instruction at
    // `insn_addr`: insn_addr + 8 (ARM) or + 4 (Thumb), word-aligned when
    // `aligned`, plus the per-image load bias.
    //
    // The `aligned` argument is load-bearing and was the subject of the
    // v1.0.515 -> v1.0.521 saga: the ADR-alias and literal-pool forms
    // genuinely need Align(PC,4) (A5.1), and removing the mask
    // regressed GD Lite to BLANK, while HCR's libxml2 PIC pair needs the
    // UNALIGNED value. No blanket rule fits both. Everything therefore
    // defaults to ALIGNED and only `add rN, pc` discriminates per
    // sequence. Do not "simplify" this.
    uint32_t pc_value_for(uint32_t insn_addr, bool aligned) const;

    // v1.0.818: pc_value_for() bakes in opts_.load_bias, because its
    // normal job is producing a RUNTIME-materialised guest pointer (one
    // that must be biased so it addresses the shared multi-image guest
    // arena correctly). But every PC-relative table/ladder scanner in
    // this file (match_pc_ladder, match_pc_table_ref, prescan_pc_tables,
    // and do_alu's own `add pc,pc,rN,lsl#k` b-table scan) uses a PC-
    // derived base to index hooks_.read_image_u32 / addr_index_ / the
    // driver's own label map -- and those are ALL keyed by the RAW,
    // UNBIASED per-image ELF vaddr (arm32rc_driver.cpp's `fn.vaddr`
    // itself carries no bias; bias is added separately, only where a
    // RUNTIME pointer value is materialised). Confirmed live: ShatteredPD
    // loads `libgdx.so` (bias 0, which is why the first three fixes in
    // this family measured clean) and `libgdx-freetype.so` (a nonzero
    // bias); a table scan in the latter using pc_value_for()'s biased
    // result read from entirely the wrong address and found nothing,
    // reported as an unresolved indirect call whose target was off by
    // EXACTLY the bias (measured: reported target 0x76ce4, real
    // in-function target 0x46ce4, difference 0x30000). This returns the
    // UNBIASED equivalent by undoing pc_value_for()'s own addition,
    // rather than re-deriving the ARM/Thumb +8/+4 and Align(PC,4) rules
    // a second time -- so there is still exactly one implementation of
    // those rules.
    uint32_t pc_value_for_lookup(uint32_t insn_addr, bool aligned) const;

    // Materialise an arbitrary 32-bit constant into `rd`. Never touches
    // PSTATE.
    void materialise_u32(int rd, uint32_t v);

    // Predication primitives, exported so the memory translator can
    // enforce the A8.3 "no access when the predicate fails" rule the
    // same way.
    struct PredScope {
        Label skip;
        bool active = false;
    };
    PredScope pred_begin(Cond c);
    void pred_end(PredScope& s);

private:
    // ---- operand model ----------------------------------------------

    // A decoded A32 operand-2 (the barrel-shifter operand).
    struct Op2 {
        bool is_imm = false;
        uint32_t imm = 0;
        // Modified-immediate carry-out, known at translate time
        // (ARMExpandImm_C). Only meaningful when is_imm.
        bool imm_carry_known = false;
        bool imm_carry = false;

        int guest_reg = -1;      // when !is_imm
        bool has_shift = false;
        Sh sh = Sh::LSL;
        bool rrx = false;
        bool shift_by_reg = false;
        int sh_amt = 0;          // 1..32 when !shift_by_reg
        int sh_guest_reg = -1;   // when shift_by_reg
    };

    // An operand-2 lowered to something A64 can consume.
    struct Src {
        bool is_imm = false;
        uint32_t imm = 0;
        int reg = kZR;
        Sh sh = Sh::LSL;
        int amt = 0;      // 0 means "no shift"
        bool shifted = false;
    };

    // How the C flag should be updated by an NZ-only flag write.
    struct CarrySpec {
        enum class Kind { Preserve, FromReg, Const } kind = Kind::Preserve;
        int reg = kZR;     // 0/1 value, Kind::FromReg
        bool value = false; // Kind::Const
        static CarrySpec preserve() { return CarrySpec{}; }
        static CarrySpec from_reg(int r) {
            CarrySpec c; c.kind = Kind::FromReg; c.reg = r; return c;
        }
        static CarrySpec constant(bool v) {
            CarrySpec c; c.kind = Kind::Const; c.value = v; return c;
        }
    };

    bool decode_op2(const cs_arm_op& src, const cs_arm_op* rot, Op2* out);
    // Lower an Op2 into a Src. `allow_fold` permits leaving an immediate
    // shift as an A64 shifted-register operand; `logical_ctx` additionally
    // permits ROR (legal only on the logical forms).
    bool lower_op2(const Op2& o, bool allow_fold, bool logical_ctx,
                   Src* out, std::vector<int>* owned_temps);
    int force_reg(const Src& s, std::vector<int>* owned_temps);
    void release(std::vector<int>& owned_temps);

    // Shifter carry-out (A2.2.1 Shift_C). Sets *spec. Emits nothing when
    // the form leaves C unchanged. MUST be called after flags_save()
    // because the register-amount path uses CSEL and therefore clobbers
    // PSTATE.
    bool shifter_carry(const Op2& o, int src_reg, CarrySpec* spec,
                       std::vector<int>* owned_temps);

    // ---- flags -------------------------------------------------------

    // MRS the guest NZCV into a scratch and return it, or -1 when the
    // caller does not need it. Must run before anything clobbers PSTATE.
    int flags_save(std::vector<int>* owned_temps);
    // N,Z from `res`; C per `spec`; V preserved. `saved` is the register
    // returned by flags_save(), or -1 to have this take the snapshot.
    void flags_commit_nz(int res, bool sf64, const CarrySpec& spec,
                         int saved, std::vector<int>* owned_temps);
    // True when the caller may use the single-instruction A64 S-form
    // because C and V are provably dead after this instruction.
    bool cv_dead_here() const;

    // ---- emit helpers ------------------------------------------------
    void emit_alu_generic(AluOp op, int rd, int rn, const Src& s, bool S,
                          std::vector<int>* owned_temps);
    void emit_mov_src(int rd, const Src& s, std::vector<int>* owned_temps);
    int  materialise_tmp(uint32_t v, std::vector<int>* owned_temps);

    // ---- instruction families ---------------------------------------
    bool do_mov(const cs_insn* in, bool is_mvn);
    bool do_movw_movt(const cs_insn* in, bool is_movt);
    bool do_alu(const cs_insn* in, AluOp op, bool flags_only);
    bool do_rsb(const cs_insn* in, bool with_carry);
    bool do_shift_insn(const cs_insn* in, Sh sh);
    bool do_rrx(const cs_insn* in);
    bool do_mul(const cs_insn* in);
    bool do_mla(const cs_insn* in, bool is_sub);
    bool do_mull(const cs_insn* in, bool is_signed, bool accumulate);
    bool do_umaal(const cs_insn* in);
    bool do_div(const cs_insn* in, bool is_signed);
    bool do_bit1(const cs_insn* in, Bit1Op op);
    bool do_revsh(const cs_insn* in);
    bool do_extend(const cs_insn* in, int bits, bool is_signed);
    bool do_extend_add(const cs_insn* in, int bits, bool is_signed);
    bool do_bfx(const cs_insn* in, bool is_signed);
    bool do_bfi(const cs_insn* in);
    bool do_bfc(const cs_insn* in);
    bool do_adr(const cs_insn* in);
    bool do_ldr_literal(const cs_insn* in);

    // signed halfword multiplies (17 families). `n_top` / `m_top`
    // select R[n]<31:16> / R[m]<31:16> over the <15:0> halves.
    bool do_smul_half(const cs_insn* in, bool n_top, bool m_top);
    bool do_smla_half(const cs_insn* in, bool n_top, bool m_top);
    bool do_smlal_half(const cs_insn* in, bool n_top, bool m_top);
    bool do_smul_w(const cs_insn* in, bool m_top);
    bool do_smla_w(const cs_insn* in, bool m_top);
    // Sign-extended 16-bit half of a guest register into a fresh temp.
    int  half_signed(int guest_reg, bool top, std::vector<int>* owned);
    // Set the sticky APSR.Q when `hi` is not the sign extension of the
    // 32-bit result already in `lo_w`. Emits a flag-free test, because
    // none of these instructions writes NZCV.
    void set_q_if_overflow(int wide_reg, int narrow_reg,
                           std::vector<int>* owned);

    // ---- ARMv6 media / DSP families (GPR ops, so core-owned even
    // though they are "SIMD-shaped"; xlat_simd deliberately leaves them
    // here because core is dispatched first and would shadow them).
    bool do_pkh(const cs_insn* in, bool tbform);
    bool do_sel(const cs_insn* in);
    bool do_xtb16(const cs_insn* in, bool is_signed, bool accumulate);
    bool do_sat(const cs_insn* in, bool is_signed);
    bool do_sat16(const cs_insn* in, bool is_signed);
    bool do_qaddsub(const cs_insn* in, bool is_sub, bool doubled);
    bool do_usad8(const cs_insn* in, bool accumulate);
    bool do_dual_mul(const cs_insn* in, bool subtract, bool swap,
                     bool accumulate, bool long_form);
    bool do_smm(const cs_insn* in, bool is_sub, bool round, bool accumulate);
    // Clamp the signed 64-bit value in `x` to the signed range of
    // `bits`, writing the clamped value back into `x`. Uses CMP/CSEL, so
    // the caller must already hold a PSTATE snapshot. Returns a register
    // holding 1 when the clamp changed the value (the Q condition), or
    // -1 when saturation is impossible for this width.
    int  clamp_signed(int x, int bits, std::vector<int>* owned);
    int  clamp_unsigned(int x, int bits, std::vector<int>* owned);
    // Set the sticky APSR.Q when `flag` (a 0/1 register) is nonzero.
    void set_q_if(int flag, std::vector<int>* owned);
    // Sign-extended 16-bit half of an arbitrary register.
    int  half_of_reg(int host_reg, bool top, std::vector<int>* owned);

    bool do_svc(const cs_insn* in);
    // The coprocessor transfer space (LDC/STC/CDP/MCR/MRC and the 2 /
    // L / RR variants) and the privileged forms that a User-mode PE
    // cannot act on. See the long comment at the implementation.
    bool do_coproc(const cs_insn* in);
    bool do_el0_inert(const cs_insn* in, const char* why);
    bool do_trap(const cs_insn* in, bool is_breakpoint, bool* terminated);
    bool do_mrs(const cs_insn* in);
    bool do_msr(const cs_insn* in);

    // T32 table branches. `*terminated` is set: TBB/TBH are branches.
    bool do_table_branch(const cs_insn* in, bool is_tbh, bool* terminated);
    struct TbbTable {
        uint32_t pc32 = 0;         // T32 PC at the TBB: addr + 4, UNBIASED
        uint32_t table_addr = 0;   // where the table BYTES live, UNBIASED
        uint32_t entry_count = 0;
        uint32_t entry_size = 1;   // 1 (TBB) or 2 (TBH)
        std::vector<uint32_t> values;   // the raw halfword counts
    };
    bool decode_table(const cs_insn* in, bool is_tbh, TbbTable* out) const;
    bool read_image_u8(uint32_t vaddr, uint32_t* out) const;

    // control flow; *terminated is set when the block ends here
    bool do_branch(const cs_insn* in, bool* terminated);
    bool do_bl(const cs_insn* in, bool* terminated);
    bool do_bx(const cs_insn* in, bool* terminated);
    bool do_blx(const cs_insn* in, bool* terminated);
    bool do_cb(const cs_insn* in, bool nonzero, bool* terminated);
    void emit_branch_to_guest(uint32_t target, bool is_bl_provenance);
    void emit_call_bracketed(uint32_t target);
    void emit_call_indirect_bracketed(int host_reg);

    // ---- intra-function computed-jump ladders (v1.0.809) -------------
    //
    // Recognise the compiler's PC-relative "affine ladder dispatch" idiom
    // (the clz-based software-divide routine's `add rX,pc,#K` + chained
    // `{add,sub} rX,rX,rY,lsl #k` + `bx rX`, and the sibling case where an
    // `add pc,pc,rN,lsl#k` jumps directly into ladder CODE instead of a
    // table of B opcodes) so the target resolves to a LOCAL branch among
    // labels this same function already owns, instead of the global
    // `tail_branch_indirect` -> `__macapk_indirect_call` LUT, which can
    // never contain a mid-function address. See the do_bx / do_alu call
    // sites for the confirmed instance (ShatteredPD libgdx.so 0x2653c)
    // and docs/arm32rc-computed-intrafunction-jump_2026-07-28.md.

    // Walk backward from the BX at `idx_` for the exact idiom above,
    // bounded and conservative (bails on any control flow, any
    // redefinition of rX or the once-identified index register that is
    // not one of the two recognised shapes, or the window running out).
    // ARM mode only -- Thumb's PC-relative base is a different encoding
    // (ADR), already handled by the adr+ldr+add+bx table case above.
    bool match_pc_ladder(int rx, uint32_t* base_out, int32_t* coeff_out,
                         int* index_reg_out) const;

    // Three-way outcome for a candidate target, because "not a real
    // instruction boundary" and "a real one this scheme cannot reach" are
    // different failures with different correct responses. Binding only
    // happens at the top of translate() as it walks the function IN
    // ADDRESS ORDER (see case_labels_ below), so a label minted for an
    // address BEHIND the current instruction (`idx_`) would never be
    // bound and would make A64Buf::resolve() fail -- there is no general
    // fix for that without a driver-side pre-scan (see
    // emit_table_offset_dispatch's header comment for the confirmed case
    // this matters for: some table entries are negative, i.e. targets
    // BEFORE the dispatch). Backward is therefore "skip this one entry,
    // the table may still continue"; NotFound is "this address is not a
    // decoded instruction in this function at all", the real end-of-table
    // signal every caller should stop on.
    enum class CandidateStatus { Forward, Backward, NotFound };
    CandidateStatus ladder_candidate_status(uint32_t candidate, Label* out);

    // Look up (or mint) the label for candidate target `v`, but ONLY when
    // it is PROVABLY-IN-FUNCTION and PROVABLY-NOT-YET-VISITED (addr_index_,
    // gated on `> idx_` -- the same evidence the TBB/TBH case-target
    // lookup above already requires, so a label minted here is guaranteed
    // to still get bound when translate() reaches it). Returns false
    // (leaving `*out` untouched) whenever the status is not Forward,
    // which is the self-termination every EXISTING caller below relies
    // on: a false-positive match (wrong base/coeff) almost always fails
    // at i=0. Thin wrapper over ladder_candidate_status for callers that
    // have no use for the Forward/Backward distinction (their targets
    // are, or are assumed to be, always forward).
    bool ladder_candidate_label(uint32_t candidate, Label* out);

    // Shared enumeration for the ARM inline jump-table case in do_alu
    // (when its byte-scan for literal B opcodes finds none): compares the
    // RUNTIME value of `index_reg` against the small integers 0..N,
    // exactly like the existing b-table dispatch just above it. Safe
    // ONLY when `index_reg` cannot have been redefined between "the
    // instant its value determines the target" and "the instant this
    // compares it" -- true here because do_alu's caller is a SINGLE
    // atomic `add pc,pc,rN,lsl#k` (no room for an intervening write).
    // Returns the count of rungs emitted (0 = contributed nothing, so
    // the caller's existing fallback is byte-for-byte unaffected).
    uint32_t emit_affine_ladder_dispatch(int index_reg, uint32_t base,
                                         int32_t coeff, uint32_t max_rungs);

    // do_bx's counterpart -- NOT index-based. See the v1.0.809 bug this
    // fixed: a multi-instruction chain (`add rX,pc,#K` + chained
    // add/sub) can, and in the confirmed idiom DOES, reuse the "index"
    // register for something else (the ladder's quotient accumulator)
    // BEFORE the `bx`, so re-reading it at the bx returns a CLOBBERED
    // value (always 0 in the confirmed case), not the value that
    // determined the branch. `branch_reg` (rX itself, the bx's own
    // operand) is never stale -- its value AT THE BX *is* the
    // architectural target by definition -- so this compares it
    // directly against each MATERIALISED candidate address instead of
    // against a small index.
    uint32_t emit_ladder_dispatch_by_target(int branch_reg, uint32_t base,
                                            int32_t coeff, uint32_t max_rungs);

    // ---- intra-function computed jump via an intermediate-register PC
    // table (v1.0.812) -----------------------------------------------
    //
    // A THIRD idiom, distinct from both of the above: `add pc, rBASE,
    // rOFF` where `rBASE` is an ORDINARY register (not PC itself, so the
    // rn==15 special case above does not see it) that was set two
    // instructions earlier by `add rBASE,pc,#K`, and `rOFF` came from
    // `ldr rOFF,[rBASE,rIDX,lsl#2]` immediately before the add -- i.e. a
    // PIC jump table whose base+read is staged through an intermediate
    // register instead of being computed in one `add pc,pc,rN,lsl#k`.
    // Confirmed in ShatteredPD's libgdx.so `gdx2d_get_pixel` (0x9d00-
    // 0x9d0c) and `gdx2d_draw_pixmap` (0x1da14-0x1da1c) -- see
    // docs/arm32rc-computed-intrafunction-jump_2026-07-28.md.
    //
    // Unlike the ladder above, the "table" here is genuine DATA (one raw
    // signed 32-bit word per case, added to rBASE to get the target), so
    // there is no runtime index to compare against at all: the values
    // are readable from the image at TRANSLATE time via
    // hooks_.read_image_u32, exactly like the ARM `add pc,pc,rN,lsl#k`
    // b-table scan above reads its table of B opcodes.

    // Walk backward from index `at_idx` (bounded, unconditional-only,
    // aborts on any control flow or an unrecognised redefinition of
    // `rn`/`r_off`) for the ldr+anchor pair above. On success,
    // `*table_base_out` is the resolved, compile-time constant guest
    // address of the table's own first entry (== rBASE's runtime value,
    // which the anchor makes a translate-time constant). Takes an
    // explicit index rather than reading `idx_` because
    // prescan_pc_tables() (below) calls this for every candidate
    // instruction in the function during set_stream(), before `idx_` has
    // any meaning -- see that function's header comment for why.
    bool match_pc_table_ref(size_t at_idx, int rn, int r_off,
                            uint32_t* table_base_out) const;

    // v1.0.816: BACKWARD table entries, resolved rather than skipped.
    //
    // The first version of this fix (v1.0.812) discovered the anchor+
    // ldr+add-pc pattern REACTIVELY, at the moment do_alu translates the
    // `add pc,rn,rOff` instruction -- i.e. after translate() has already
    // walked past every earlier guest address. A label can only be BOUND
    // as translate()'s preamble walks the function forward (see
    // case_labels_ below), so a candidate BEHIND the add-pc instruction
    // could never get one: that is exactly what CandidateStatus::Backward
    // captures, and why v1.0.812 could only SKIP those entries (confirmed
    // live in gdx2d_draw_pixmap: 4 of its 7 table entries are negative
    // offsets, targets before the dispatch, and all 4 panicked).
    //
    // The fix is not a new admission test -- backward-ness is a fact
    // about the CALL SITE'S TIMING, not about whether the target is a
    // real instruction, and it is already provable via addr_index_ alone,
    // no `> idx_` gate needed. So do the discovery UP FRONT instead: scan
    // the WHOLE function once, in set_stream(), before any instruction is
    // translated and before "backward" or "forward" can even be asked --
    // every candidate this finds gets a label minted immediately
    // (BufSink::new_label() is position-independent, see its
    // implementation), and case_labels_ already gets checked by
    // translate()'s preamble for EVERY guest address as it walks forward,
    // so a pre-minted label for an EARLIER address is bound the first
    // time translate() reaches it -- before the add-pc instruction that
    // needed it is even translated. A64Buf's fixup resolution does not
    // care whether a label was bound before or after the branch that
    // references it (an ordinary backward branch, e.g. a loop, works the
    // same way), so this needs no new machinery there, only earlier
    // discovery.
    //
    // Mirrors do_alu's `add pc,rn,rOff` recognition condition directly
    // against raw capstone fields (no Op2/decode_op2 -- that abstraction
    // only exists per-instruction inside do_alu's own call, which is
    // exactly the timing this function exists to get out ahead of).
    // Keep the two conditions in sync if either changes.
    void prescan_pc_tables();

    // Reads consecutive signed 32-bit words starting at `table_base` via
    // hooks_.read_image_u32, treating entry `i` as `target = table_base +
    // table[i]` (a signed offset FROM the table's own base -- this
    // idiom's actual arithmetic, verified against both confirmed sites).
    // Trusts prescan_pc_tables() to have already validated and pre-minted
    // a label in case_labels_ for every entry that is a real instruction
    // boundary in this function, REGARDLESS of direction -- so this just
    // re-reads the identical table and stops the moment an entry is NOT
    // in case_labels_ (prescan_pc_tables() stopped there too, for the
    // same reason: not a real instruction boundary, i.e. genuinely past
    // the table's end). Every entry found gets materialised and compared
    // against `host_target_reg` (a HOST register -- the caller's own
    // emit_alu_generic result, already holding rBASE+rOFF, so it is never
    // stale), emitting `beq label` -- the label may already be BOUND (a
    // backward target) or not yet (forward); both are equally legal
    // branch references. Returns the count of entries actually emitted (0
    // = contributed nothing, so the caller's existing tail_branch_indirect
    // fallback, emitted unconditionally right after, is byte-for-byte
    // unaffected).
    uint32_t emit_table_offset_dispatch(int host_target_reg,
                                        uint32_t table_base,
                                        uint32_t max_entries);

    // The v1.0.521 `add rN, pc` PC-convention discrimination.
    bool find_pc_literal_for(unsigned cs_reg, uint32_t* out) const;
    bool pc_add_wants_unaligned(const cs_arm& d, int rn_idx,
                                int src_idx) const;

    void warn(const std::string& msg) const;
    void set_error(const std::string& msg);
    // Record WHY a family handler is giving up, then return false. The
    // reason string is what MACAPK_ARM32RC_STUB_TRACE prints, so it must
    // be specific enough to act on without re-reading this file.
    bool refuse(const char* why);

    A64Sink& sink_;
    RegFile& regs_;
    XlatOptions opts_;
    XlatHooks hooks_;

    const cs_insn* insns_ = nullptr;
    size_t count_ = 0;
    size_t idx_ = 0;
    uint32_t addr_ = 0;   // guest vaddr of the instruction being emitted
    uint32_t size_ = 4;
    std::string error_;
    const char* refuse_reason_ = nullptr;

    // Guest vaddr -> index into insns_, for the table-branch case-target
    // lookup. Rebuilt by set_stream().
    std::unordered_map<uint32_t, size_t> addr_index_;
    // Labels minted by a TBB/TBH for its case targets. The driver's
    // label pre-scan only sees B / CBZ / CBNZ destinations, so a
    // switch-case target has no label and this core has to mint AND bind
    // its own. Binding happens at the top of translate(), which the
    // driver calls for every decoded instruction in address order; case
    // targets are always FORWARD of the TBB (the table entry is an
    // unsigned halfword count added to PC), so the bind is always still
    // ahead of us. A label that is never bound makes A64Buf::resolve()
    // fail and drops the whole function, so a label is only minted when
    // the target is a decoded instruction in this function's stream.
    std::unordered_map<uint32_t, Label> case_labels_;
    // Which of those have already been bound, so two tables in one
    // function that share a case target bind it exactly once. The entry
    // is NOT erased on binding: A64Buf resolves backward and forward
    // references the same way, so a later table may legitimately branch
    // back to an already-bound case.
    std::unordered_set<uint32_t> case_labels_bound_;

public:
    // Diagnostics for the table-branch lowering, aggregated across the
    // whole run by the driver's stats print. Public so a harness can
    // read them without a friend declaration.
    struct TbStats {
        uint32_t sites = 0;          // TBB/TBH instructions seen
        uint32_t decoded = 0;        // table decoded (address + extent)
        uint32_t cases_labelled = 0; // case targets that got a real label
        uint32_t cases_offstream = 0;// case targets not on an insn boundary
    };
    const TbStats& tb_stats() const { return tb_; }

private:
    TbStats tb_;
};

// ---------------------------------------------------------------------
// TextA64Sink -- GNU-as text output
// ---------------------------------------------------------------------
//
// Not a toy: this is the reference adapter (one method, one line) AND
// the test oracle. Emitting text that the host assembler accepts proves
// every operand of every sequence in this file is legal A64, which no
// amount of code reading does. It is also the natural implementation of
// a `MACAPK_RC_DUMP_ASM` debug switch, mirroring MACAPK_KEEP_IR.
class TextA64Sink : public A64Sink {
public:
    void alu_reg(AluOp op, int rd, int rn, int rm, Sh sh, int amt,
                 bool S, bool sf64) override;
    void alu_imm(AluOp op, int rd, int rn, uint64_t imm, bool S,
                 bool sf64) override;
    void alu_extended(AluOp op, int rd, int rn, int rm, Ext ext,
                      int shift, bool S, bool sf64) override;
    void movz(int rd, uint16_t imm16, int shift, bool sf64) override;
    void movn(int rd, uint16_t imm16, int shift, bool sf64) override;
    void movk(int rd, uint16_t imm16, int shift, bool sf64) override;
    void mov_reg(int rd, int rm, bool sf64) override;
    void shift_reg(Sh sh, int rd, int rn, int rm, bool sf64) override;
    void shift_imm(Sh sh, int rd, int rn, int amt, bool sf64) override;
    void extr(int rd, int rn, int rm, int lsb, bool sf64) override;
    void ubfx(int rd, int rn, int lsb, int width, bool sf64) override;
    void sbfx(int rd, int rn, int lsb, int width, bool sf64) override;
    void bfi(int rd, int rn, int lsb, int width, bool sf64) override;
    void mul3(MulOp op, int rd, int rn, int rm, int ra) override;
    void divide(bool is_signed, int rd, int rn, int rm, bool sf64) override;
    void bit1(Bit1Op op, int rd, int rn, bool sf64) override;
    void csel(CselOp op, int rd, int rn, int rm, Cond c, bool sf64) override;
    void cset(int rd, Cond c, bool sf64) override;
    void mrs_nzcv(int xd) override;
    void msr_nzcv(int xn) override;
    Label new_label() override;
    void bind(Label l) override;
    void b(Label l) override;
    void b_cond(Cond c, Label l) override;
    void cbz(int rt, Label l, bool sf64) override;
    void cbnz(int rt, Label l, bool sf64) override;
    void call_direct(uint32_t guest_target) override;
    void call_indirect(int host_reg) override;
    void tail_branch_direct(uint32_t guest_target) override;
    void tail_branch_indirect(int host_reg) override;
    void ret_from_function() override;
    void load_guest_u32(int rd, int addr_reg) override;
    void load_guest_zx(int rd, int addr_reg, int bytes) override;
    void guest_state_load(int rd, uint32_t byte_off, int scratch) override;
    void guest_state_store(int rs, uint32_t byte_off, int scratch) override;
    void svc_bridge(uint32_t imm, int rd) override;
    void sys_op(SysOp op, uint16_t imm) override;
    void comment(const std::string& text) override;

    const std::string& text() const { return out_; }
    void clear() { out_.clear(); next_label_ = 0; }

private:
    void line(const std::string& s);
    std::string out_;
    int next_label_ = 0;
};

// ---------------------------------------------------------------------
// Sibling-pipeline adapters
// ---------------------------------------------------------------------
//
// These bind the abstract sink and register file above onto the real
// encoder (a64_emit.h) and register model (regmap.h / guest_state.h).
// They are the ONLY place that knows those APIs, so if a sibling header
// changes signatures this is the single file region to fix; the whole
// translator body stays untouched.
//
// The guard keeps xlat_core.cpp buildable, testable and mutation-checkable
// on its own, which is how it was developed before the siblings existed.
#if defined(__has_include)
#  if __has_include("a64_emit.h") && __has_include("regmap.h")
#    define MACAPK_XLAT_CORE_HAVE_SIBLINGS 1
#  endif
#endif
#ifndef MACAPK_XLAT_CORE_HAVE_SIBLINGS
#  define MACAPK_XLAT_CORE_HAVE_SIBLINGS 0
#endif

}  // namespace arm32rc
}  // namespace macapk

#if MACAPK_XLAT_CORE_HAVE_SIBLINGS
#include "a64_emit.h"
#include "regmap.h"

namespace macapk {
namespace arm32rc {

// A64Sink over a64::A64Buf.
//
// Direct branches and calls to a guest vaddr cannot be resolved here:
// the target may be another recompiled function, a PLT thunk or a shim.
// They are emitted as a correctly-shaped word with a zero displacement
// and recorded in extern_sites(), which is the same contract A64Buf uses
// for its own label fixups and what the Mach-O linker half needs.
class BufSink : public A64Sink {
public:
    struct ExternSite {
        uint32_t insn_index;     // word index into the buffer, for patching
        uint32_t guest_target;   // UNBIASED guest vaddr
        bool is_call;            // BL (returns here) vs B (tail branch)
    };

    explicit BufSink(a64::A64Buf& buf) : buf_(buf) {}

    const std::vector<ExternSite>& extern_sites() const { return sites_; }
    void clear_extern_sites() { sites_.clear(); }

    void alu_reg(AluOp op, int rd, int rn, int rm, Sh sh, int amt,
                 bool S, bool sf64) override;
    void alu_imm(AluOp op, int rd, int rn, uint64_t imm, bool S,
                 bool sf64) override;
    void alu_extended(AluOp op, int rd, int rn, int rm, Ext ext,
                      int shift, bool S, bool sf64) override;
    void movz(int rd, uint16_t imm16, int shift, bool sf64) override;
    void movn(int rd, uint16_t imm16, int shift, bool sf64) override;
    void movk(int rd, uint16_t imm16, int shift, bool sf64) override;
    void mov_reg(int rd, int rm, bool sf64) override;
    void shift_reg(Sh sh, int rd, int rn, int rm, bool sf64) override;
    void shift_imm(Sh sh, int rd, int rn, int amt, bool sf64) override;
    void extr(int rd, int rn, int rm, int lsb, bool sf64) override;
    void ubfx(int rd, int rn, int lsb, int width, bool sf64) override;
    void sbfx(int rd, int rn, int lsb, int width, bool sf64) override;
    void bfi(int rd, int rn, int lsb, int width, bool sf64) override;
    void mul3(MulOp op, int rd, int rn, int rm, int ra) override;
    void divide(bool is_signed, int rd, int rn, int rm, bool sf64) override;
    void bit1(Bit1Op op, int rd, int rn, bool sf64) override;
    void csel(CselOp op, int rd, int rn, int rm, Cond c, bool sf64) override;
    void cset(int rd, Cond c, bool sf64) override;
    void mrs_nzcv(int xd) override;
    void msr_nzcv(int xn) override;
    Label new_label() override;
    void bind(Label l) override;
    void b(Label l) override;
    void b_cond(Cond c, Label l) override;
    void cbz(int rt, Label l, bool sf64) override;
    void cbnz(int rt, Label l, bool sf64) override;
    void call_direct(uint32_t guest_target) override;
    void call_indirect(int host_reg) override;
    void tail_branch_direct(uint32_t guest_target) override;
    void tail_branch_indirect(int host_reg) override;
    void ret_from_function() override;
    void load_guest_u32(int rd, int addr_reg) override;
    void load_guest_zx(int rd, int addr_reg, int bytes) override;
    void guest_state_load(int rd, uint32_t byte_off, int scratch) override;
    void guest_state_store(int rs, uint32_t byte_off, int scratch) override;
    void svc_bridge(uint32_t imm, int rd) override;
    void sys_op(SysOp op, uint16_t imm) override;

private:
    a64::A64Buf& buf_;
    std::vector<a64::Label> labels_;
    std::vector<ExternSite> sites_;
};

// RegFile over regmap.h. Guest r0..r14 are PINNED to host homes, so a
// read is free and a write needs no spill; scratch comes from
// regmap.h's kScratchGpr via ScratchSet.
//
// Call reset() between guest instructions. regmap.h's contract is that
// scratch never lives across an instruction boundary, so sharing the
// pool with xlat_mem.cpp is safe: only one translator owns a given
// instruction.
class PinnedRegFile : public RegFile {
public:
    int read(int g) override;
    int write_begin(int g) override;
    void write_end(int) override {}
    int temp_alloc() override;
    void temp_free(int host_reg) override;
    void reset() { scratch_.release_all(); }

private:
    ScratchSet scratch_;
};

}  // namespace arm32rc
}  // namespace macapk
#endif  // MACAPK_XLAT_CORE_HAVE_SIBLINGS

#endif  // MACAPK_RUNTIME_TRANSLATOR_ARM32RC_XLAT_CORE_H_
