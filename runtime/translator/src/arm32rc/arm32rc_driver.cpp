// arm32rc_driver.cpp -- see arm32rc_driver.h for what this joins up.

#include <set>
#include "arm32rc_driver.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <thread>
#include <unordered_map>
#include <unordered_set>

#include <capstone/capstone.h>

#include "a64_emit.h"
#include "macho_link.h"
#include "regmap.h"
#include "xlat_core.h"
#include "xlat_mem.h"
#include "xlat_simd.h"

namespace macapk::translator::arm32rc {
namespace {
bool tail_live_enabled();   // defined below
bool bl_live_regs_enabled();   // defined below
bool frame_jump_enabled();     // defined below
bool extend_falloff_enabled(); // defined below
std::vector<uint32_t>& sorted_fn_starts();   // defined below
std::unordered_set<uint32_t>& frame_jump_targets();   // defined below
std::unordered_set<uint32_t>& all_entry_targets();     // defined below
std::unordered_set<uint32_t>& continuation_targets();   // defined below


namespace rc = macapk::arm32rc;
namespace a64 = macapk::a64;

using macapk::arm32rc::HostGpr;
using macapk::arm32rc::enc;

constexpr unsigned X(HostGpr h) { return static_cast<unsigned>(enc(h)); }

// The Parity-convention frame (docs/arm32-recompiler-design_2026-07-25.md
// section 5.3). 96 bytes keeps SP mod 16 == 0 while holding the frame
// record plus the five callee-saved pairs we repurpose as guest homes.
constexpr int kFrameBytes = 96;

// Name of the __DATA word holding the guest arena base. Read with
// adrp/ldr in every prologue: two instructions that clobber nothing,
// where calling __macapk_get_guest_base() would destroy the incoming
// guest arguments in x0-x3.
constexpr const char* kGuestBaseSym = "macapk_rc_guest_base";

// ---------------------------------------------------------------------
// Sink
// ---------------------------------------------------------------------
//
// BufSink already turns the abstract A64Sink into A64Buf words and
// records the cross-function branch/call sites. Two things it cannot
// know are owned here:
//
//   * a guest return is the host epilogue plus `ret`, not a bare `ret`;
//   * a guest call must not be emitted as a raw BL to a guest vaddr the
//     linker has never heard of, so `call_direct` records the site and
//     the driver patches it once every function's address is known
//     (BufSink already does exactly this; we only add the epilogue).
class DriverSink : public rc::BufSink {
public:
    DriverSink(a64::A64Buf& buf) : rc::BufSink(buf), buf_(buf) {}

    // Guest vaddr of the instruction being translated, for the indirect
    // dispatcher's diagnostic argument. Set per instruction by the driver.
    uint32_t insn_addr = 0;
    // "Is this PLT vaddr a soft-float double-returning math import?"
    std::function<bool(uint32_t)> is_softfloat_double;

    void ret_from_function() override {
        emit_epilogue(buf_);
        buf_.put(a64::ret());
    }

    // DEFECT 1. The host contract is (r0,r1,r2,r3, guest_sp) -> r0
    // (design 5.1), and the incumbent honours it: arm32_lifter.cpp passes
    // `load_reg(13)` as the 5th argument at every call. BufSink emits a
    // bare `bl`, so nothing set w4 and every callee's prologue read
    // whatever scratch happened to be in x4 as the guest stack pointer --
    // 30,952 live sites in HCR. The callee then pushed its frame over
    // arbitrary guest memory, which faults nowhere (the arena is 4 GiB
    // demand-paged R/W) and renders nothing.
    //
    // x4 is the first register ScratchSet hands out, so this MUST be the
    // last thing before the branch.
    void call_direct(uint32_t guest_target) override {
        buf_.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X27)));
        rc::BufSink::call_direct(guest_target);
        maybe_split_double(guest_target);
    }

    // DEFECT 4. A tail branch left the 96-byte host frame allocated: the
    // callee stacks its own on top and its epilogue restores x19-x28 from
    // ITS save area, so the original caller resumes with the tail-callee's
    // guest registers. Tear our frame down first. w4 is captured BEFORE
    // the epilogue because the epilogue restores x27.
    void tail_branch_direct(uint32_t guest_target) override {
        // MACAPK_ARM32RC_FRAME_JUMP: a branch into another translated extent
        // keeps our host frame and every guest register (see the gate).
        if (frame_jump_enabled() &&
            frame_jump_targets().count(guest_target & ~1u)) {
            fjs_.push_back(static_cast<uint32_t>(buf_.size_words()));
            rc::BufSink::tail_branch_direct(guest_target);
            return;
        }
        buf_.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X27)));
        // ONLY for a SHARED-TAIL target. v1.0.1081 converted EVERY direct tail
        // branch and that was too broad: the protected sweep with the gate on
        // came back PASS=8 REGRESS=3, and all three losses were the
        // forced-ARM32 tier collapsing to the chrome floor (unciv-v7a 296,
        // bouncy-v7a 339, mindustry-v7a 306) while the REAL ARM32 apps hcr181
        // (58,132) and flappy passed. Turning ordinary tail calls into real
        // calls grows the host stack on tail-recursive guest code, and those
        // libGDX titles are where that shows.
        //
        // A shared tail is the only case that needs live registers, so key on
        // exactly that: the target must be an alternate entry we minted.
        // Everything else keeps the cheap `epilogue ; b` tail jump.
        if (tail_live_enabled() && continuation_targets().count(guest_target)) {
            // CALL the target with our guest registers still live, then unwind
            // normally. Not a tail call any more -- that is the trade, and it
            // is now paid only where it buys something.
            rc::BufSink::call_direct(guest_target);
            emit_epilogue(buf_);
            buf_.put(a64::ret());
            return;
        }
        emit_epilogue(buf_);
        rc::BufSink::tail_branch_direct(guest_target);
    }

    // DEFECT 2. `blr x3` / `br x16` on a register holding a 32-bit GUEST
    // vaddr branches to host address 0x00000000_00xxxxxx. The incumbent
    // routes every one of these through the shim's dispatcher (6,132 sites
    // in HCR's LLVM dylib; the recompiler had zero). The driver already
    // populates the LUT via __macapk_register_function, so the
    // dispatcher's input exists and was simply never consulted.
    void call_indirect(int host_reg) override {
        emit_dispatch(static_cast<unsigned>(host_reg));
    }
    void tail_branch_indirect(int host_reg) override {
        emit_dispatch(static_cast<unsigned>(host_reg));
        emit_epilogue(buf_);
        buf_.put(a64::ret());
    }

    // Where the `bl __macapk_indirect_call` sites landed, so the module
    // builder can bind them. Uses the same word-index shape as
    // BufSink::ExternSite.
    const std::vector<uint32_t>& dispatch_sites() const { return disp_; }
    const std::vector<uint32_t>& frame_jump_sites() const { return fjs_; }
    const std::vector<uint32_t>& capture_sites() const { return cap_; }

    // __macapk_capture_entry(fn_vaddr, r0, r1, r2, r3, sp, is_thumb).
    //
    // DIAGNOSTIC ONLY, selected at translate time by MACAPK_DIFF_VADDRS and a
    // no-op at run time unless MACAPK_DIFF_OUT names a sink. This is the same
    // hook the LLVM path has emitted since the differential harness landed;
    // arm32rc never had it, which is why an LLVM-vs-arm32rc register diff was
    // impossible and HCR's divergence had to be chased by disassembly.
    //
    // Guest r0-r3 live in x0-x3 and guest SP in x27 (regmap static_assert), so
    // the arguments shift UP by one exactly like emit_dispatch -- and for the
    // same reason the moves must run in DESCENDING order or each clobbers the
    // next source. Unlike emit_dispatch this is not a tail transfer: the call
    // clobbers the caller-saved x0-x18, which is where the guest's own r0-r3
    // live, so they are spilled around it.
    void emit_capture_entry(uint32_t fn_vaddr, bool is_thumb) {
        using I = a64::Index;
        buf_.put(a64::stp(true, X(HostGpr::X0), X(HostGpr::X1),
                          a64::kSP, -32, I::PreIndex));
        buf_.put(a64::stp(true, X(HostGpr::X2), X(HostGpr::X3), a64::kSP, 16));
        buf_.mov_imm(false, X(HostGpr::X6), is_thumb ? 1u : 0u);
        buf_.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X27)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X3)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X3), X(HostGpr::X2)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X2), X(HostGpr::X1)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X1), X(HostGpr::X0)));
        buf_.mov_imm(false, X(HostGpr::X0), fn_vaddr);
        cap_.push_back(static_cast<uint32_t>(buf_.size_words()));
        buf_.put(a64::bl(0));
        buf_.put(a64::ldp(true, X(HostGpr::X2), X(HostGpr::X3), a64::kSP, 16));
        buf_.put(a64::ldp(true, X(HostGpr::X0), X(HostGpr::X1),
                          a64::kSP, 32, I::PostIndex));
    }

    // THERE IS DELIBERATELY NO svc_bridge() OVERRIDE HERE, and this is the
    // file that would have to host one, so the reasoning lives here.
    //
    // xlat_core.cpp's BufSink::svc_bridge invites an override: guest r0-r3
    // are already in the host argument registers, and routing to
    // `bl __macapk_svc` needs only a third extern-site sentinel next to
    // 0xfffffffd / 0xfffffffe. Mechanically that is small. It is still the
    // wrong change, for three measured reasons.
    //
    // 1. IT WOULD BE A CORRECTNESS REGRESSION. The ARM ARM (DDI 0406C.d
    //    A8.8.361) defines SVC as "causes a Supervisor Call exception" and
    //    nothing more; the return convention is the OS ABI's, and here that
    //    is Linux/Bionic. AOSP's own arm stub template
    //    (bionic/libc/tools/gensyscalls.py) shows what the guest does with
    //    r0 after the trap:
    //        swi #0
    //        cmn r0, #(MAX_ERRNO + 1)
    //        bxls lr
    //        neg  r0, r0
    //        b    __set_errno_internal
    //    i.e. r0 is the RAW kernel result, a negative errno on failure. The
    //    current in-line `movn w0, #37` puts -38 there, so the guest's own
    //    machinery sets guest errno = ENOSYS and returns -1: exactly what
    //    hardware would produce. `__macapk_svc` (bionic_shim.cpp) instead
    //    returns the LIBC-level -1 and sets the HOST errno, so the same stub
    //    would compute guest errno = 1 (EPERM). Routing makes the guest see
    //    a WRONG errno that it cannot even correlate with the host's.
    //
    // 2. A `bl` BREAKS REGISTER PRESERVATION THAT `svc` GUARANTEES. The
    //    Linux ARM syscall entry preserves r1-r12, and the AOSP stub above
    //    RELIES on it: the short form saves only r7, leaving r4-r6 and
    //    r8-r11 to the kernel. Guest r4-r11 live in x19-x26 and survive a
    //    host call for free, but guest r1-r3 (x1-x3), r12 (x9) and r14 (x10)
    //    are AAPCS64 caller-saved and a `bl` may destroy them. Doing this
    //    right means saving and restoring those around the call plus the
    //    descending-order argument shuffle emit_dispatch performs. That is
    //    not the "~5 lines" it looks like.
    //
    // 3. THERE IS NOTHING TO GAIN TODAY. Measured over the whole ARM32
    //    fleet with rc_cov's CFG classifier and the discovery sidecar
    //    (2026-07-26): HCR 1,014 SVC encodings, 20 on a reachable path;
    //    JJ 3,673, 1,604 reachable; Flappy 0. NOT ONE of the reachable ones
    //    is code. Their immediates say so outright: `svclo #0x800000` is the
    //    word 0x3f800000 (float 1.0), `svclo #0` is 0x3f000000 (0.5),
    //    `svclo #0x666666` is 0x3f666666 (0.9), and JJ's 1,604 all sit in
    //    one 16 KB synth extent whose operands spell "char" / "sic" / "te",
    //    i.e. a string table. They are literal pools the reachability walk
    //    cannot distinguish from code. So routing would plant a call plus a
    //    relocation at ~1,624 sites of provable DATA and change nothing that
    //    executes.
    //
    // WHAT TO BUILD IF A TARGET EVER NEEDS REAL SYSCALLS: a new shim entry
    // point with the KERNEL convention, `__macapk_svc_raw(imm, r0..r3) ->
    // raw result` returning a negative errno, plus an override here that
    // saves guest r1-r3/r12/r14, shuffles the arguments, calls it and
    // restores. The shim half belongs to bionic_shim.cpp, not this file.

    static void emit_prologue(a64::A64Buf& b) {
        using I = a64::Index;
        b.put(a64::stp(true, X(HostGpr::X29), X(HostGpr::X30),
                       a64::kSP, -kFrameBytes, I::PreIndex));
        b.put(a64::mov_sp(true, X(HostGpr::X29), a64::kSP));
        b.put(a64::stp(true, X(HostGpr::X19), X(HostGpr::X20), a64::kSP, 16));
        b.put(a64::stp(true, X(HostGpr::X21), X(HostGpr::X22), a64::kSP, 32));
        b.put(a64::stp(true, X(HostGpr::X23), X(HostGpr::X24), a64::kSP, 48));
        b.put(a64::stp(true, X(HostGpr::X25), X(HostGpr::X26), a64::kSP, 64));
        b.put(a64::stp(true, X(HostGpr::X27), X(HostGpr::X28), a64::kSP, 80));
    }

    // The guest-state staging that follows the prologue on EVERY entry
    // into a translated body. Extracted (v1.0.1047) because
    // MACAPK_ARM32RC_MULTI_ENTRY emits a SECOND entry sequence per
    // alternate entry point, and two hand-written copies of this would
    // drift: the DEFECT 3 comment below records what one wrong
    // instruction here already cost once.
    //
    // Returns the word index of the `adrp` in the guest-base pair, which
    // the caller must report to the module builder as a 0xfffffffe site
    // so the linker fixups get attached. Every entry sequence needs its
    // OWN pair, so every one contributes its own site.
    // zero_regs=false is the SHARED-TAIL entry: the branching code left guest
    // r4-r11 live in X19-X26 and the tail reads them, so zeroing would destroy
    // exactly the state it needs. See tail_live_enabled().
    static uint32_t emit_entry_state(a64::A64Buf& b, bool zero_regs = true) {
        // Guest SP arrives as the 5th C argument (w4); the arena base
        // comes from the module's own __DATA word. Guest r4..r12 and r14
        // start zeroed, matching the LLVM path's fresh-alloca semantics
        // exactly so a per-function A/B is meaningful.
        b.put(a64::mov_reg(false, X(HostGpr::X27), X(HostGpr::X4)));
        // adrp/ldr for the guest base is patched by the caller (it needs
        // a linker fixup), so leave two placeholder words and record where.
        const uint32_t base_adrp_word = static_cast<uint32_t>(b.size_words());
        b.put(a64::adrp(X(HostGpr::X28), 0));
        b.put(a64::ldr(true, X(HostGpr::X28), X(HostGpr::X28), 0));
        // DEFECT 3. `a64::mov_sp` is ADD rd, rn, #0, and in ADD (immediate)
        // Rn == 31 decodes as the STACK POINTER, not ZR (DDI 0487, ADD
        // (immediate) 32-bit: "<Wn|WSP>"). So `mov_sp(false, rd, kZR)` emitted
        // `mov wN, wsp` and every function started with guest r4-r12 and r14
        // holding the low half of the host SP instead of 0. That silently
        // voids Parity's "fresh zeroed r4-r11", which is the whole basis of a
        // per-function A/B against the LLVM path. mov_reg is ORR-based, where
        // Rn == 31 really is ZR.
        if (zero_regs) {
            for (int g = 4; g <= 12; ++g)
                b.put(a64::mov_reg(false,
                                   X(macapk::arm32rc::host_gpr_for_guest(g)),
                                   a64::kZR));
            b.put(a64::mov_reg(false, X(HostGpr::X10), a64::kZR));   // guest r14
        }
        return base_adrp_word;
    }

    static void emit_epilogue(a64::A64Buf& b) {
        using I = a64::Index;
        b.put(a64::ldp(true, X(HostGpr::X27), X(HostGpr::X28), a64::kSP, 80));
        b.put(a64::ldp(true, X(HostGpr::X25), X(HostGpr::X26), a64::kSP, 64));
        b.put(a64::ldp(true, X(HostGpr::X23), X(HostGpr::X24), a64::kSP, 48));
        b.put(a64::ldp(true, X(HostGpr::X21), X(HostGpr::X22), a64::kSP, 32));
        b.put(a64::ldp(true, X(HostGpr::X19), X(HostGpr::X20), a64::kSP, 16));
        b.put(a64::ldp(true, X(HostGpr::X29), X(HostGpr::X30),
                       a64::kSP, kFrameBytes, I::PostIndex));
    }

private:
    // __macapk_indirect_call(target, r0, r1, r2, r3, sp, from_vaddr).
    // Guest r0-r3 already sit in x0-x3, so they shift UP one register;
    // the moves must run in descending order or each would clobber the
    // next source. `target` is stashed in x16 first because it may itself
    // be x0-x4; x16 is safe here because no branch (and therefore no
    // linker veneer, AAPCS64 Table 2) sits between the stash and its use.
    void emit_dispatch(unsigned target_reg) {
        buf_.put(a64::mov_reg(false, X(HostGpr::X16), target_reg));
        buf_.mov_imm(false, X(HostGpr::X6), insn_addr);
        buf_.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X27)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X3)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X3), X(HostGpr::X2)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X2), X(HostGpr::X1)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X1), X(HostGpr::X0)));
        buf_.put(a64::mov_reg(false, X(HostGpr::X0), X(HostGpr::X16)));
        disp_.push_back(static_cast<uint32_t>(buf_.size_words()));
        buf_.put(a64::bl(0));
    }

    // DEFECT 5. kSoftFloatDoubleImports shims return the double packed as
    // an int64 (hi << 32) | lo, and AAPCS32 wants it in r0:r1. The
    // incumbent splits it (arm32_lifter.cpp trunc / lshr 32 / trunc);
    // without the split guest r1 keeps a stale high word, so every
    // sin/cos/atan2/pow result is wrong in its top half. 253 sites in HCR,
    // 0 in Flappy, which is exactly why Flappy did not expose it.
    void maybe_split_double(uint32_t guest_target) {
        if (!is_softfloat_double || !is_softfloat_double(guest_target)) return;
        buf_.put(a64::lsr_imm(true, X(HostGpr::X1), X(HostGpr::X0), 32));
        buf_.put(a64::mov_reg(false, X(HostGpr::X0), X(HostGpr::X0)));
    }

    a64::A64Buf& buf_;
    std::vector<uint32_t> disp_;
    std::vector<uint32_t> cap_;
    std::vector<uint32_t> fjs_;   // MACAPK_ARM32RC_FRAME_JUMP `b` word indices
};

// ---------------------------------------------------------------------
// MACAPK_ARM32RC_ADC_SBIT (default ON; =0 trusts Capstone as before).
//
// Capstone 5 reports `update_flags = true` for EVERY 32-bit ADC / SBC / RSC,
// whatever its S bit says: A32 `adc r3, r1, r2` (e0a13002), T32
// `adc r3, r1, #-1` (f141 33ff) and `adc.w r3, r1, r2` (eb41 0302) all come
// back flag-setting although the mnemonic it prints has no `s`. (It derives
// the field from the instruction touching CPSR, and these READ the carry.
// The 16-bit Thumb forms, whose flag behaviour depends on the IT state, are
// reported correctly.) Every xlat_core ALU path keys on that field, so a
// non-S ADC / SBC / RSC was emitted as ADCS / SBCS and overwrote NZCV:
//     cmp r0, r1 ; adc r2, r2, #0 ; moveq ...     -- EQ tested the adc result
// ARM ARM DDI 0406C.d A8.8.1 ADC (immediate) / A8.8.2 (register), A8.8.161 /
// A8.8.162 SBC, A8.8.152 / A8.8.153 RSC: `setflags = (S == '1')`, S = bit 20
// in A1 and in T1/T2 of the 32-bit Thumb encodings (bit 4 of the first
// halfword). So the encoding decides, not the decoder's summary. Found on
// SQLCipher's libsqlcipher.so (sqlite3Malloc's 64-bit range check at 0x3737c).
// ---------------------------------------------------------------------
void fix_carry_alu_setflags(cs_insn* insns, size_t n, bool thumb) {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_ADC_SBIT");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    if (!on) return;
    for (size_t i = 0; i < n; ++i) {
        cs_insn& in = insns[i];
        if (in.id != ARM_INS_ADC && in.id != ARM_INS_SBC && in.id != ARM_INS_RSC)
            continue;
        if (in.size != 4 || !in.detail) continue;   // 16-bit Thumb: IT-dependent, already right
        // A32: bit 20 of the little-endian word = bit 4 of byte 2.
        // T32: bit 4 of the first halfword = bit 4 of byte 0.
        const bool s = thumb ? ((in.bytes[0] >> 4) & 1) != 0
                             : ((in.bytes[2] >> 4) & 1) != 0;
        in.detail->arm.update_flags = s;
    }
}

// ---------------------------------------------------------------------
// Capstone handles
// ---------------------------------------------------------------------
class CsPair {
public:
    CsPair() {
        if (cs_open(CS_ARCH_ARM, CS_MODE_ARM, &arm_) == CS_ERR_OK) {
            cs_option(arm_, CS_OPT_DETAIL, CS_OPT_ON);
            cs_option(arm_, CS_OPT_SKIPDATA, CS_OPT_ON);
            arm_ok_ = true;
        }
        if (cs_open(CS_ARCH_ARM, CS_MODE_THUMB, &thumb_) == CS_ERR_OK) {
            cs_option(thumb_, CS_OPT_DETAIL, CS_OPT_ON);
            cs_option(thumb_, CS_OPT_SKIPDATA, CS_OPT_ON);
            thumb_ok_ = true;
        }
    }
    ~CsPair() {
        if (arm_ok_) cs_close(&arm_);
        if (thumb_ok_) cs_close(&thumb_);
    }
    csh get(bool thumb) const { return thumb ? thumb_ : arm_; }
    bool ok() const { return arm_ok_ && thumb_ok_; }

    CsPair(const CsPair&) = delete;
    CsPair& operator=(const CsPair&) = delete;

private:
    csh arm_ = 0, thumb_ = 0;
    bool arm_ok_ = false, thumb_ok_ = false;
};

// ---------------------------------------------------------------------
// MACAPK_FIRSTLAUNCH_RC_THREADS (2026-09-26): worker threads for the two
// per-function passes of build_module -- the MULTI_ENTRY branch-target scan
// (plan_multi_entry) and the per-function recompile (FunctionTranslator::run).
//
// WHY. Both passes are a pure function of ONE DiscoveredFunction plus
// read-only module state (the image, the options, the multi-entry plan), and
// together they were 43% of an arm32rc translate (Jetpack Joyride's
// libmortargame.so: plan 16%, run 27% of 3.15 s; GD Lite v7a 34% of 11.9 s).
// They ran on one core while the rest of the machine idled; the first launch
// (or install) of an ARM32 app waits for them.
//
// BYTE-IDENTICAL BY CONSTRUCTION. Workers only FILL per-function slots
// (FuncOut, or a private candidate set); every step that touches the linker,
// assigns a symbol, or orders anything still runs on the calling thread, in
// `funcs` order, exactly as before. The candidate sets are unions (order-free)
// and the plan is sorted after the merge, as it always was. Stats are summed.
// Each worker gets its OWN capstone handles (a csh is not thread-safe), opened
// on the calling thread. debug/firstlaunch_gate.sh hashes real outputs with
// this gate at 1 and at N.
//
// Value: unset = min(hardware threads, 8); 0 or 1 = the exact serial path;
// N = N workers (capped at 16). Diagnostic traces that print per instruction
// (MACAPK_ARM32RC_STUB_TRACE, MACAPK_JT_TRACE) force the serial path so their
// output stays in order.
unsigned rc_worker_threads() {
    static const unsigned n = [] {
        const char* e = std::getenv("MACAPK_FIRSTLAUNCH_RC_THREADS");
        if (e && *e) {
            const long v = std::strtol(e, nullptr, 10);
            if (v <= 1) return 1u;
            return static_cast<unsigned>(std::min(v, 16L));
        }
        unsigned hw = std::thread::hardware_concurrency();
        if (hw == 0) hw = 1;
        return std::min(hw, 8u);
    }();
    static const bool traced = rc::rc_stub_trace_enabled() ||
                               std::getenv("MACAPK_JT_TRACE") != nullptr;
    return traced ? 1u : n;
}

// MACAPK_FIRSTLAUNCH_TIMING=1: wall time of each build_module phase on stderr
// ("[firstlaunch-timing] rc.<phase> <ms>"). Diagnostic only, default off,
// changes no output byte.
class PhaseTimer {
public:
    PhaseTimer() : t_(std::chrono::steady_clock::now()) {}
    void mark(const char* phase) {
        static const bool on = [] {
            const char* e = std::getenv("MACAPK_FIRSTLAUNCH_TIMING");
            return e && *e && *e != '0';
        }();
        const auto now = std::chrono::steady_clock::now();
        if (on)
            std::fprintf(stderr, "[firstlaunch-timing] rc.%s %.1f (threads=%u)\n",
                         phase,
                         std::chrono::duration<double, std::milli>(now - t_).count(),
                         rc_worker_threads());
        t_ = now;
    }
private:
    std::chrono::steady_clock::time_point t_;
};

// Run body(worker_index, item_index) for every item in [0, count) on
// `nthreads` threads, handing out items in small chunks from a shared cursor
// so a few huge functions cannot leave the other workers idle. Worker 0 is the
// calling thread. body must only write state private to its item (or to its
// worker).
template <class Body>
void parallel_for_items(size_t count, unsigned nthreads, Body body) {
    constexpr size_t kChunk = 16;
    std::atomic<size_t> next{0};
    auto work = [&](unsigned w) {
        for (;;) {
            const size_t lo = next.fetch_add(kChunk, std::memory_order_relaxed);
            if (lo >= count) return;
            const size_t hi = std::min(count, lo + kChunk);
            for (size_t i = lo; i < hi; ++i) body(w, i);
        }
    };
    std::vector<std::thread> pool;
    pool.reserve(nthreads > 0 ? nthreads - 1 : 0);
    for (unsigned w = 1; w < nthreads; ++w) pool.emplace_back(work, w);
    work(0);
    for (auto& t : pool) t.join();
}

// ---------------------------------------------------------------------
// Image helpers
// ---------------------------------------------------------------------
class ImageView {
public:
    explicit ImageView(const Elf32Image& img) : img_(img) {}

    const uint8_t* at(uint32_t vaddr, uint32_t need) const {
        for (const auto& seg : img_.loadable) {
            if (seg.bytes.empty()) continue;
            if (vaddr < seg.vaddr) continue;
            const uint64_t off = vaddr - seg.vaddr;
            if (off + need > seg.bytes.size()) continue;
            return seg.bytes.data() + off;
        }
        return nullptr;
    }

    bool read_u32(uint32_t vaddr, uint32_t* out) const {
        const uint8_t* p = at(vaddr, 4);
        if (!p) return false;
        std::memcpy(out, p, 4);
        return true;
    }

private:
    const Elf32Image& img_;
};

// ---------------------------------------------------------------------
// One function
// ---------------------------------------------------------------------
struct FuncOut {
    std::vector<uint8_t> code;
    std::vector<rc::BufSink::ExternSite> sites;
    bool ok = false;
    bool partial = false;
    // MACAPK_ARM32RC_MULTI_ENTRY: (guest vaddr, byte offset within
    // `code`) of each ALTERNATE entry stub emitted for a mid-body
    // address that some OTHER function branches to. Always empty with
    // the gate off, which is what keeps the default path byte-identical.
    std::vector<std::pair<uint32_t, uint64_t>> entries;
    // MACAPK_ARM32RC_BL_LIVE_REGS: byte offset within `code` of the
    // non-zeroing entry stub a direct guest `bl` binds to; ~0 = none.
    uint64_t direct_off = ~0ull;
    // MACAPK_ARM32RC_FRAME_JUMP: byte offset of the first guest instruction
    // (the body entry), the offset of each planned interior target's own
    // instruction, and the word indices of this body's frame-keeping `b`s.
    uint64_t body_off = ~0ull;
    std::vector<std::pair<uint32_t, uint64_t>> mids;
    std::vector<uint32_t> frame_sites;
};

// MACAPK_ARM32RC_MULTI_ENTRY (v1.0.1047, default OFF).
//
// THE DEFECT. Discovery over-fragments: Jetpack Joyride's
// libmortargame.so yields 13,511 function starts for 2,092 real ARM
// STT_FUNC, so a `b`/`bl` whose target is a real entry point of a real
// function lands INTERIOR to whatever extent discovery happened to
// draw. build_module resolves a call target only against
// `vaddr_to_sym`, which holds function STARTS, so every such site falls
// through to a `__macapk_target_<vaddr>` panic trampoline that returns
// 0. Measured on that binary: 6,145 panic-stubbed call sites over 3,646
// distinct targets, of which 3,172 (87%) are interior to a discovered
// extent and ZERO are at a discovered start (so the resolution logic
// itself is sound; only the label set is too small).
//
// The live instance that motivated this is zlib's crc32(): a table step
// at 0x47bbb0, its `mvn r0, r0` tail at 0x47bbb8, and a shared epilogue
// at 0x47bb68 (`add sp,sp,#8 ; pop {r4-r8,r10} ; bx lr`). Both tails are
// reached at runtime from native_init, both return 0, so every ZIP
// central-directory CRC compares wrong, unzip reports UNZ_CRCERROR, no
// asset loads, and the whole run issues six GL calls and one texture.
//
// THE FIX, and why it lives here rather than in discovery. main.cpp's
// MACAPK_ARM32_SHARED_TAIL_ENTRY comment already names it: "The complete
// fix is a lifted body with MULTIPLE ENTRY POINTS rather than N
// independent continuations; that belongs in arm32rc's label map, not
// here." Discovery's heuristics have a violent regression history in
// both directions (v1.0.29 +5132 functions, v1.0.30/31 a 17894->825
// collapse), and that path can only reason about ARM-mode STT_FUNC
// extents, which is why it selects 17 of the 3,172. Here we have the
// Capstone-decoded instruction stream of every function, so "the target
// is a real instruction boundary" is a fact rather than a heuristic.
//
// This pre-pass answers ONE question per function: which addresses
// strictly inside it does some other function branch to? It costs a
// second disassembly of .text, which is why it is gated rather than
// folded into the emit pass.
// Every alternate-entry target in the MODULE, not just the current function.
// tail_branch_direct needs it because the target of a shared-tail branch lives
// in a DIFFERENT function than the branch. Populated once, read-only after.
std::unordered_set<uint32_t>& all_entry_targets() {
    static std::unordered_set<uint32_t> s;
    return s;
}

// Interior targets reached by a NON-LINKING branch (B / CBZ / CBNZ), i.e. a
// CONTINUATION of the branching function rather than a call. Only these may
// inherit live guest registers; an interior target reached by BL/BLX is a real
// CALL and must still get the defined zeroed state.
//
// v1.0.1081 skipped the zeroing for EVERY alternate entry and the sweep said
// no: PASS=8 REGRESS=3, the three forced-ARM32 rows collapsing to the chrome
// floor (unciv-v7a 296, bouncy-v7a 339, mindustry-v7a 306) while real-ARM32
// hcr181 (58,132) and flappy passed. Restricting the TAIL-BRANCH half alone did
// not help -- the same three failed again -- which localised it to the ENTRY
// half: with 1,897 alternate entries published, the call-reached ones were
// inheriting garbage where a defined zero was correct.
std::unordered_set<uint32_t>& continuation_targets() {
    static std::unordered_set<uint32_t> s;
    return s;
}

struct MultiEntryPlan {
    // function start vaddr -> sorted, unique interior target vaddrs
    std::unordered_map<uint32_t, std::vector<uint32_t>> by_fn;
    size_t targets = 0;
};

// MACAPK_ARM32RC_TAIL_LIVE (v1.0.1081, default OFF). Implies MULTI_ENTRY.
//
// MULTI_ENTRY alone mints alternate entries for shared tails but every entry
// runs emit_entry_state, which ZEROES guest r4-r12/r14. That is right for a
// real function entry (fresh-alloca parity with the LLVM path) and WRONG for a
// shared tail, which is a CONTINUATION: the branching code left those registers
// live and the tail reads them. The driver comment already measured the cost --
// 33-55% of interior targets read a callee-saved register before writing it --
// which is why MULTI_ENTRY stayed off.
//
// Simply not zeroing is not enough on its own, and the same comment says why:
// tail_branch_direct runs emit_epilogue BEFORE the branch, so x19-x28 already
// hold the GRANDPARENT's guest registers by the time control arrives.
//
// So this gate changes BOTH halves together:
//   * a tail branch becomes `bl target ; epilogue ; ret` instead of
//     `epilogue ; b target`. At the bl, x19-x26 still hold OUR guest r4-r11
//     (they are host callee-saved, kGuestGprHome maps r4..r11 -> X19..X26),
//     x0-x3 still hold r0-r3, and w4 still carries the guest SP. Frames stay
//     balanced because the callee returns to us and we then unwind normally.
//   * an ALTERNATE entry does not zero, so it inherits exactly that state.
// The cost is that a guest tail call is no longer a tail call, so deeply
// tail-recursive guest code grows the host stack. That is the reason this is
// gated rather than simply switched on.
//
// Motivating case, and the driver comment predicted the symptom exactly:
// Jetpack Joyride's zlib crc32() shared epilogue at 0x47bb68 plus soft-float
// tails inside __aeabi_fmul (0x41964c, 0x4195f0) and __aeabi_ul2f (0x419474)
// all return 0, so CRCs mismatch, no assets load, and the run issues a handful
// of GL calls and one texture -- which is what it still does.
// DEFAULT ON since v1.0.1086. It was gated while the PLT defect above made
// MULTI_ENTRY unshippable; with the PLT excluded the protected sweep is
// PASS=11 REGRESS=0 WITH THIS PATH ACTIVE (MACAPK_SWEEP_CLEAR=dylib), and the
// three rows that used to collapse are back and then some -- unciv-v7a
// 296 -> 29,304, mindustry-v7a 306 -> 124,909, bouncy-v7a 339 -> 713.
// Jetpack Joyride renders its splash instead of nothing.
// MACAPK_ARM32RC_TAIL_LIVE=0 reverts, and is the bisect lever if a guest with
// very deep tail recursion ever grows the host stack (the known trade: a guest
// tail call to a shared tail becomes a real call).
bool tail_live_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_TAIL_LIVE");
        return !(e && *e == '0');   // default ON
    }();
    return on;
}

// MACAPK_ARM32RC_BL_LIVE_REGS (default ON; =0 emits exactly the old code).
//
// A direct guest `bl` used to land on the callee's ordinary entry, which runs
// emit_entry_state and ZEROES guest r4-r12 and r14. For an AAPCS32 callee that
// is invisible (IHI 0042 5.1.1: r4-r11 are callee-saved, a callee may not rely
// on them). It is WRONG for hand-written assembly, which is free to pass
// arguments to its own internal routines in any register, and on a CPU a `bl`
// changes nothing but pc and lr (ARM ARM DDI 0406C.d A8.8.25 BL: `LR =
// PC - 4 or next_instr_addr; BranchWritePC(targetAddress)`).
//
// Measured on OpenSSL 1.0.2g's aes-armv4.S inside SQLCipher's libsqlcipher.so:
// AES_encrypt (0xdbda0) sets r10 = the Te table, r11 = the key schedule, and
// `bl _armv4_AES_encrypt` (0xdbe00), whose first instructions are
// `ldm r11!, {r4-r7}` and `ldr r12, [r11, #0xe0]` (the round count). With r11
// zeroed it read the key and the round count from guest address 0, so
// AES_encrypt looped on a garbage count and AES_decrypt produced zeros; the
// bit-sliced path (_bsaes_key_convert takes r4, r5 and r12) failed the same
// way. Every page SQLCipher decrypts goes through these.
//
// Fix: each translated function gets a second entry, `__macapk_dcall_<vaddr>`,
// that runs the full host prologue (so the frame and the host callee-saved
// registers are exactly as for the ordinary entry) but does NOT zero, and a
// direct intra-module `bl` binds to it. At that `bl` x19-x26 hold the calling
// guest function's r4-r11, x9 its r12, and the call site has just written the
// return address into w10 (guest lr), so the callee starts with the register
// file a CPU would give it. The ordinary entry (JNI thunks, the indirect-call
// dispatcher, tail branches) is unchanged and still zeroes.
//
// What this does NOT do: values a non-AAPCS callee leaves in r4-r11 do not
// flow BACK, because the shared epilogue restores x19-x26. r0-r3, r12 and the
// NEON bank (guest memory) do flow back, which covers the routines above.
bool bl_live_regs_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_BL_LIVE_REGS");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}

// MACAPK_ARM32RC_FRAME_JUMP (default ON; =0 emits exactly the old code).
//
// A guest `b` (or a table-branch case) whose target lies in ANOTHER translated
// extent used to be `epilogue ; b <target's ordinary entry>`: the target ran
// its own prologue and emit_entry_state, i.e. it started with guest r4-r12 and
// r14 ZEROED. On a CPU a branch changes pc and nothing else (ARM ARM DDI
// 0406C.d A8.8.18 B: `BranchWritePC(PC + imm32)`).
//
// That matters whenever discovery draws MORE THAN ONE extent over one real
// function, because then an ordinary local jump is a "cross-function" branch
// with every callee-saved register live across it. Measured on SQLCipher's
// libsqlcipher.so: sqlite3VdbeExec (0x74c8c..0x79da0, 20,090 bytes, one
// function, the bytecode interpreter) is cut at the 0x4000 extent clamp and at
// interior starts into about 25 extents. Its opcode `tbh` dispatch and its
// `b`s between the pieces entered each piece with r11 (the sqlite3* handle)
// and r4 zeroed, so `db->xProfile` was read from guest address 0xb0, which is
// the library's own ELF program headers at load bias 0: the "indirect call to
// in-image vaddr 0x4 / 0x6" lines, an empty cursor, "unable to close due to
// unfinalized statements".
//
// Fix: every translated extent has ONE host frame shape (emit_prologue: 96
// bytes, x19-x28 + fp/lr), so a jump between two of them does not need a new
// frame at all. The branch becomes a plain host `b` to the target's BODY (a
// local `__macapk_body_<vaddr>` symbol at its first guest instruction, or
// `__macapk_mid_<vaddr>` at a planned interior entry), with our frame left in
// place: x19-x26, x9, x10, x27 and x28 arrive untouched, the stack does not
// grow, and the target's own epilogue pops the frame we pushed and returns to
// OUR caller. For a genuine tail call that is exactly a tail call; for a split
// function it is the local jump it always was. This also replaces the
// MACAPK_ARM32RC_TAIL_LIVE `bl ; epilogue ; ret` for the targets it covers,
// without that path's host-stack growth.
//
// The set of targets is fixed BEFORE translation (function starts with
// Disposition::Translate plus the multi-entry plan's interior targets, guest
// PLT excluded). A site whose target then turns out to have no body symbol
// (the function failed to translate, or pass 1 rejected the interior address)
// is bound to a small thunk that pops the frame and continues the old way.
bool frame_jump_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_FRAME_JUMP");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}
std::unordered_set<uint32_t>& frame_jump_targets() {
    static std::unordered_set<uint32_t> s;
    return s;
}

// MACAPK_ARM32RC_EXTEND_FALLOFF (default ON; =0 translates each extent exactly
// as discovery drew it).
//
// Discovery sometimes ends an extent in the middle of straight-line code: its
// prologue boundary scan treats a `push {.., lr}` as the start of a NEW
// function, but compilers do not always put the push first. GCC schedules
// argument arithmetic ahead of it:
//     decodeFlags (SQLite btree.c), libsqlcipher.so 0x50210:
//         ubfx r3, r1, #3, #8 ; bic r1, r1, #8 ; push {r4, lr} ; ...
//     sqlite3Malloc 0x37378:  adds.w / adc / movs ; push.w {r4-r8, lr}
// The scan marks the push (0x50218), nothing ever lifts a function there (no
// symbol, no call, no pointer names it), and the real function is left with
// an 8-byte extent: two instructions and then this translator's "well-formed
// exit", i.e. it RETURNS. decodeFlags therefore never set MemPage.leaf /
// intKey, and every schema write reported "database disk image is malformed"
// (moveToRoot's `(pCur->pKeyInfo==0) != pRoot->intKey`).
//
// On a CPU there is no such thing as running off a function: execution
// continues at the next address. So when an extent's last instruction is NOT
// an unconditional transfer of control AND the address after it is not the
// start of any discovered function, the extent is simply too short, and it is
// extended to the next discovered start (bounded) before translation. When
// the address after it IS a discovered start, MACAPK_ARM32RC_FRAME_JUMP's
// fall-through handles it instead. Code that really does end in a no-return
// call followed by padding gains a few unreachable instructions and nothing
// else.
bool extend_falloff_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_EXTEND_FALLOFF");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}
std::vector<uint32_t>& sorted_fn_starts() {
    static std::vector<uint32_t> v;
    return v;
}
std::atomic<size_t>& extend_falloff_count() {
    static std::atomic<size_t> n{0};
    return n;
}
std::atomic<size_t>& extend_falloff_bytes() {
    static std::atomic<size_t> n{0};
    return n;
}
// True when `in` unconditionally leaves straight-line execution.
bool insn_is_uncond_terminator(const cs_insn& in) {
    if (in.id == 0 || !in.detail) return false;
    const cs_arm& d = in.detail->arm;
    if (d.cc != ARM_CC_AL && d.cc != ARM_CC_INVALID) return false;
    auto names_pc = [&](int from) {
        for (int k = from; k < d.op_count; ++k)
            if (d.operands[k].type == ARM_OP_REG && d.operands[k].reg == ARM_REG_PC)
                return true;
        return false;
    };
    switch (in.id) {
        case ARM_INS_B: case ARM_INS_BX: case ARM_INS_BXJ:
        case ARM_INS_TBB: case ARM_INS_TBH: case ARM_INS_UDF:
            return true;
        case ARM_INS_POP:
            return names_pc(0);
        case ARM_INS_LDM: case ARM_INS_LDMIB: case ARM_INS_LDMDA: case ARM_INS_LDMDB:
            return names_pc(1);
        case ARM_INS_LDR: case ARM_INS_MOV: case ARM_INS_ADD: case ARM_INS_SUB:
            return d.op_count >= 1 && d.operands[0].type == ARM_OP_REG &&
                   d.operands[0].reg == ARM_REG_PC;
        default:
            return false;
    }
}

bool multi_entry_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_MULTI_ENTRY");
        if (e && *e && *e != '0') return true;
        if (e && *e == '0') return false;      // explicit opt-out wins
        // TAIL_LIVE needs the alternate entries to exist at all, so FOLLOW it
        // rather than re-reading its env var -- which is what this used to do,
        // and it was wrong the moment TAIL_LIVE became default-ON (v1.0.1087):
        // an UNSET MACAPK_ARM32RC_TAIL_LIVE left tail_live_enabled() true and
        // this false, i.e. the shipped default emitted `bl <tail>` for tail
        // branches while publishing none of those targets as entries.
        //
        // MEASURED (Jetpack Joyride 1.3.5): that combination produced SIX
        // distinct `unresolved external call to ARM address 0x...` warnings,
        // each of which silently returns 0 -- and three of the six are on live
        // paths (0x419638 from native_step, the per-frame loop; 0x41964c and
        // 0x4195f0 from native_init). They are mid-function tails inside
        // soft-float helpers, so a zero return corrupts float math rather than
        // failing loudly. With the entries published the count is 0 of 6.
        return tail_live_enabled();
    }();
    return on;
}

// `plt_lo`/`plt_hi` bound the guest PLT. Any candidate inside it is DROPPED.
//
// WHY (v1.0.1085, and this is what made MULTI_ENTRY unshippable): discovery
// mints synth extents over `.plt`, so an import stub is INTERIOR to a
// Translate extent and the planner happily published an alternate entry AT the
// stub's vaddr. Section 1b defines those as `__macapk_entry_<vaddr>` in
// `vaddr_to_sym` -- and extern-site resolution consults `vaddr_to_sym` BEFORE
// `plt_to_import`, so `bl malloc@plt` linked to the translated PLT STUB instead
// of `__macapk_shim_malloc`. The stub's GOT load yields the unrelocated lazy
// resolver (PLT[0]), misses the runtime LUT, and returns 0. malloc returning
// NULL and memcpy being a no-op from the first frame is why the libGDX/arc
// titles rendered nothing. Worse, the PLT-thunk registration loop SKIPS any
// plt_addr already in `vaddr_to_sym`, so the entries also suppressed the
// legitimate LUT thunks.
//
// MEASURED on the collapsed build's own dylib: 13 `__macapk_entry_*` symbols,
// EVERY ONE a PLT vaddr (0x267b0=malloc, 0x267d0=free, 0x267f0=__aeabi_memcpy,
// 0x268d0=gdx2d_clear ...), and the gate-ON log carries 8x
// `indirect call to in-image vaddr 0x26710` (PLT[0]) from inside the malloc and
// free stubs, where the BASE log has zero.
//
// Excluding the PLT keeps every `.text` alternate entry, which is where the
// shared tails this feature exists for actually live.
MultiEntryPlan plan_multi_entry(const std::vector<DiscoveredFunction>& funcs,
                                const ImageView& view, const CsPair& cs, uint32_t plt_lo, uint32_t plt_hi) {
    MultiEntryPlan plan;

    // Only Disposition::Translate bodies have an interior to enter. An
    // OverrideShim / OverrideConst / Veneer body is a host replacement
    // whose bytes have no correspondence to the guest's, so branching
    // into the middle of one would be meaningless.
    struct Extent { uint32_t start; uint32_t end; };
    std::vector<Extent> ext;
    ext.reserve(funcs.size());
    for (const DiscoveredFunction& fn : funcs) {
        if (fn.disposition != Disposition::Translate) continue;
        if (fn.size == 0) continue;
        const uint32_t s = fn.vaddr & ~1u;
        ext.push_back({s, s + fn.size});
    }
    std::sort(ext.begin(), ext.end(),
              [](const Extent& a, const Extent& b) { return a.start < b.start; });

    // Enclosing extent for `a`, INNERMOST first. Extents legally overlap:
    // the EABI compare helpers are nested by fall-through (__gtsf2 @+0
    // falls into __ltsf2 @+8 falls into __nesf2 @+0x10, all three sharing
    // one end), so the highest start that still contains `a` is the one
    // whose code `a` actually belongs to. Same rule, and the same
    // walk-back termination guard, as main.cpp's shared-tail-entry scan,
    // so the two agree on which body owns an address.
    auto encl = [&ext](uint32_t a) -> const Extent* {
        auto it = std::upper_bound(
            ext.begin(), ext.end(), a,
            [](uint32_t v, const Extent& r) { return v < r.start; });
        while (it != ext.begin()) {
            --it;
            if (a >= it->start && a < it->end) return &*it;
            if (it->start + 0x10000u < a) break;
        }
        return nullptr;
    };

    // Every immediate branch target that leaves its own function. An
    // INTRA-function target already gets a label from run()'s pass 1, so
    // it is not a candidate and collecting it would only cost memory.
    std::unordered_set<uint32_t> cand;
    std::unordered_set<uint32_t> cont_cand;
    auto scan_one = [&view](const DiscoveredFunction& fn, const CsPair& c,
                            std::unordered_set<uint32_t>& cnd,
                            std::unordered_set<uint32_t>& cont) {
        if (fn.disposition != Disposition::Translate) return;
        if (fn.size == 0) return;
        const uint32_t start = fn.vaddr & ~1u;
        const uint8_t* bytes = view.at(start, fn.size);
        if (!bytes) return;
        cs_insn* insns = nullptr;
        const size_t n = cs_disasm(c.get(fn.is_thumb), bytes, fn.size,
                                   start, 0, &insns);
        if (n == 0) return;
        const uint32_t end = start + fn.size;
        for (size_t i = 0; i < n; ++i) {
            const cs_insn& in = insns[i];
            if (in.id != ARM_INS_B && in.id != ARM_INS_BL &&
                in.id != ARM_INS_BLX && in.id != ARM_INS_CBZ &&
                in.id != ARM_INS_CBNZ)
                continue;
            if (!in.detail) continue;
            const cs_arm& d = in.detail->arm;
            const bool links = (in.id == ARM_INS_BL || in.id == ARM_INS_BLX);
            for (int k = 0; k < d.op_count; ++k) {
                if (d.operands[k].type != ARM_OP_IMM) continue;
                // The Thumb LSB is masked off: it selects DECODE MODE in
                // the guest, and the target function is disassembled in
                // its own recorded mode either way. Everything we emit is
                // arm64, so there is no mode to switch at run time.
                const uint32_t t =
                    static_cast<uint32_t>(d.operands[k].imm) & ~1u;
                if (t >= start && t < end) continue;
                cnd.insert(t);
                if (!links) cont.insert(t);
            }
        }
        cs_free(insns, n);
    };
    // MACAPK_FIRSTLAUNCH_RC_THREADS: the scan is per-function and its result
    // is two SETS, so splitting it across workers and taking the union gives
    // the same sets in any order. Everything below this loop is unchanged.
    const unsigned nthr = rc_worker_threads();
    if (nthr <= 1 || funcs.size() < 64) {
        for (const DiscoveredFunction& fn : funcs) scan_one(fn, cs, cand, cont_cand);
    } else {
        std::vector<std::unique_ptr<CsPair>> handles;
        std::vector<std::unordered_set<uint32_t>> wc(nthr), wcont(nthr);
        bool handles_ok = true;
        for (unsigned w = 1; w < nthr; ++w) {
            handles.push_back(std::make_unique<CsPair>());
            handles_ok = handles_ok && handles.back()->ok();
        }
        if (!handles_ok) {
            for (const DiscoveredFunction& fn : funcs)
                scan_one(fn, cs, cand, cont_cand);
        } else {
            parallel_for_items(funcs.size(), nthr, [&](unsigned w, size_t i) {
                scan_one(funcs[i], w == 0 ? cs : *handles[w - 1], wc[w], wcont[w]);
            });
            for (unsigned w = 0; w < nthr; ++w) {
                cand.insert(wc[w].begin(), wc[w].end());
                cont_cand.insert(wcont[w].begin(), wcont[w].end());
            }
        }
    }

    for (uint32_t t : cand) {
        const Extent* e = encl(t);
        if (!e) continue;
        // A discovered function START is already in vaddr_to_sym and
        // resolves without help. Only strictly-interior addresses are the
        // defect. Note this also excludes the case where `t` names an
        // OUTER overlapping extent's start, because encl can never return
        // an extent whose start is above `t`.
        if (t == e->start) continue;
        // Never publish an entry inside the PLT (see the header above).
        if (plt_hi > plt_lo && t >= plt_lo && t < plt_hi) continue;
        // ⚠ v1.0.1083 TRIED "publish only continuations" here and it made BOTH
        // apps worse, so it was reverted: unciv-v7a stayed collapsed at 296 AND
        // Jetpack lost its fix (23,189 -> 341). So MULTI_ENTRY's damage to the
        // forced-ARM32 tier is NOT about which targets are published. The next
        // suspect is that publishing interior addresses as module ENTRIES also
        // puts them in the indirect-call LUT, so an indirect call that should
        // land on a function START can land mid-body. NOT yet tested.
        plan.by_fn[e->start].push_back(t);
        all_entry_targets().insert(t);
        if (cont_cand.count(t)) continuation_targets().insert(t);
    }
    for (auto& [fn_start, tgts] : plan.by_fn) {
        (void)fn_start;
        std::sort(tgts.begin(), tgts.end());
        tgts.erase(std::unique(tgts.begin(), tgts.end()), tgts.end());
        plan.targets += tgts.size();
    }
    return plan;
}

class FunctionTranslator {
public:
    FunctionTranslator(const Elf32Image& img, const ImageView& view,
                       const CsPair& cs, const RcOptions& opts, RcStats* st)
        : img_(img), view_(view), cs_(cs), opts_(opts), st_(st) {}

    // `entry_targets` is the MACAPK_ARM32RC_MULTI_ENTRY plan for THIS
    // function (sorted, unique, strictly interior), or nullptr with the
    // gate off.
    bool run_impl(const DiscoveredFunction& fn,
                  const std::vector<uint32_t>* entry_targets, FuncOut* out);
    uint32_t extended_size(const DiscoveredFunction& fn);
    bool run(const DiscoveredFunction& fn,
             const std::vector<uint32_t>* entry_targets, FuncOut* out);

private:
    const Elf32Image& img_;
    const ImageView& view_;
    const CsPair& cs_;
    const RcOptions& opts_;
    RcStats* st_;
};

// MACAPK_ARM32RC_EXTEND_FALLOFF: the size this extent should be translated at.
uint32_t extended_size_of(const ImageView& view_, const CsPair& cs_,
                          const DiscoveredFunction& fn) {
    if (!extend_falloff_enabled() || fn.size == 0) return fn.size;
    const uint8_t* bytes = view_.at(fn.vaddr, fn.size);
    if (!bytes) return fn.size;
    cs_insn* ins = nullptr;
    const size_t n = cs_disasm(cs_.get(fn.is_thumb), bytes, fn.size, fn.vaddr, 0, &ins);
    if (n == 0) return fn.size;
    bool falls_off = false;
    uint32_t end = fn.vaddr + fn.size;
    for (size_t i = n; i-- > 0;) {
        if (ins[i].id == 0) continue;            // skipdata: look at the last real one
        falls_off = !insn_is_uncond_terminator(ins[i]);
        end = static_cast<uint32_t>(ins[i].address) + ins[i].size;
        break;
    }
    cs_free(ins, n);
    if (!falls_off) return fn.size;
    const auto& st = sorted_fn_starts();
    auto it = std::upper_bound(st.begin(), st.end(), fn.vaddr & ~1u);
    // The next discovered start. At or before our end: the fall-through lands
    // on a translated function (FRAME_JUMP's business). None: leave it alone.
    while (it != st.end() && *it < end) ++it;
    if (it == st.end() || *it == end) return fn.size;
    const uint32_t kMaxExtend = 0x2000;          // a prologue-scan cut is a few hundred bytes at most
    if (*it - end > kMaxExtend) return fn.size;
    const uint32_t ext = *it - (fn.vaddr & ~1u);
    if (!view_.at(fn.vaddr, ext)) return fn.size;
    extend_falloff_count().fetch_add(1, std::memory_order_relaxed);
    extend_falloff_bytes().fetch_add(ext - fn.size, std::memory_order_relaxed);
    return ext;
}

uint32_t FunctionTranslator::extended_size(const DiscoveredFunction& fn) {
    return extended_size_of(view_, cs_, fn);
}

bool FunctionTranslator::run(const DiscoveredFunction& fn,
                             const std::vector<uint32_t>* entry_targets,
                             FuncOut* out) {
    const uint32_t ext = extended_size(fn);
    if (ext == fn.size) return run_impl(fn, entry_targets, out);
    DiscoveredFunction fx = fn;
    fx.size = ext;
    return run_impl(fx, entry_targets, out);
}

bool FunctionTranslator::run_impl(const DiscoveredFunction& fn,
                                  const std::vector<uint32_t>* entry_targets,
                                  FuncOut* out) {
    const uint8_t* bytes = view_.at(fn.vaddr, fn.size);
    if (!bytes) return false;

    cs_insn* raw_insns = nullptr;
    const size_t raw_n = cs_disasm(cs_.get(fn.is_thumb), bytes, fn.size,
                                   fn.vaddr, 0, &raw_insns);
    if (raw_n == 0) return false;
    fix_carry_alu_setflags(raw_insns, raw_n, fn.is_thumb);
    // Every capstone allocation this function makes, freed on every exit.
    struct CsHold {
        std::vector<std::pair<cs_insn*, size_t>> v;
        ~CsHold() { for (auto& p : v) cs_free(p.first, p.second); }
    } cs_hold;
    cs_hold.v.push_back({raw_insns, raw_n});
    cs_insn* insns = raw_insns;
    size_t n = raw_n;
    std::vector<cs_insn> resynced;   // owns the spliced stream, if any

    // MACAPK_ARM32RC_POOL_RESYNC (default ON; =0 decodes exactly as before).
    // The body is one LINEAR capstone sweep, and a Thumb-2 function can hold a
    // literal pool in its middle (clang places a constant island after an
    // unconditional branch once the function outgrows the LDR range). Pool
    // words decode as instructions too, and a word whose upper halfword looks
    // like a 32-bit prefix (0xe8xx..0xffff) swallows the first halfword of the
    // REAL instruction after the pool, so the sweep is misaligned from there
    // on and a branch to that instruction has no label: the driver took it
    // for an external target and panic-stubbed it. Measured victim: OpenTTD
    // 14.1 v7a liblzma's LZMA decoder (0x15048), whose `beq.w 0x172e0` resumes
    // an interrupted symbol after the pool word 0xffff3be2 at 0x172dc (loaded
    // by `ldr r0,[pc,#0x3a8]` @0x16f30) -- every input boundary that landed in
    // that state returned LZMA_DATA_ERROR, the app's "LZMA error 9".
    //
    // The fix re-decodes from such a branch target, which is where the CPU
    // really executes (ARM ARM DDI 0406C.b A6.1: a Thumb instruction stream is
    // a sequence of halfword-aligned instructions of 16 or 32 bits, decoded
    // from where execution enters), and splices the result over the
    // misaligned run until the two decodes agree again. Narrowed to targets
    // the DATA explains: the sweep instruction that swallowed the target must
    // START in a byte main.cpp's literal-pool scan marks. That scan is a
    // blind heuristic (it also marks the second halfword of an ordinary
    // 32-bit Thumb-2 instruction), not a proof, so the branch itself is
    // checked too, below: a "branch" whose own address sits in a marked byte,
    // or whose bytes are lld's 0xd4d4 trap fill, is not real code and its
    // operand is not trusted as a target. Two confirmed miscompiles
    // (libfmod's FIR loop, libcocos2dcpp's jpeg_idct_12x12 in gdlite-v7a)
    // came from skipping that second check; see
    // docs/arm32-exidx-confirmed-seeds_2026-09-28.md.
    static const bool s_pool_resync = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_POOL_RESYNC");
        return !(e && *e == '0');
    }();
    // MACAPK_ARM32RC_POOL_RESYNC_SRCFILTER (default ON; =0 keeps POOL_RESYNC
    // on but drops just the branch-SOURCE checks below, reproducing the
    // narrower pre-fix filter that only checked the covering instruction at
    // the target). Exists purely so lzmadec_gate.sh's C4 can demonstrate the
    // miscompile this filter fixes without needing a second translator binary
    // built from the pre-fix source: =0 is a regression-testing knob, not a
    // supported deployment configuration.
    static const bool s_pool_resync_srcfilter = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_POOL_RESYNC_SRCFILTER");
        return !(e && *e == '0');
    }();
    if (s_pool_resync && fn.is_thumb && opts_.is_literal_pool_byte) {
        const uint32_t end_va = fn.vaddr + fn.size;
        std::vector<cs_insn> cur(raw_insns, raw_insns + raw_n);
        bool changed = false;
        for (int round = 0; round < 16; ++round) {
            std::unordered_set<uint32_t> starts;
            for (const auto& in : cur) starts.insert(static_cast<uint32_t>(in.address));
            std::set<uint32_t> todo;
            for (const auto& in : cur) {
                if (in.id != ARM_INS_B && in.id != ARM_INS_CBZ &&
                    in.id != ARM_INS_CBNZ)
                    continue;
                // The branch SOURCE can itself be a mis-decode: the blind
                // literal-pool byte scan (main.cpp) marks a halfword pool
                // pattern (0x48xx-0x4fxx) even where it is really the second
                // halfword of a 32-bit instruction, so that scan is not a
                // ground truth for "this address is data" -- it only proves
                // "a pool word's second half starts here". Two independent
                // classes of non-code bytes decode as a plausible branch and
                // must not be trusted to relabel real code:
                //   (a) `in.address` itself sits in a byte the pool scan
                //       marked -- the "branch" is itself the tail of a pool
                //       word or an adjacent real instruction's second half,
                //       so its operand is not a real target.
                //   (b) the first halfword is lld's ARM trap-fill pattern
                //       0xd4d4 (lld ELF/Arch/ARM.cpp: `trapInstr = {0xd4,
                //       0xd4, 0xd4, 0xd4};`, used to pad unreachable gaps
                //       such as after `pop {..,pc}` before the next pool) --
                //       capstone happily decodes 0xd4d4 as `bmi`/`beq` with a
                //       real-looking PC-relative target, but it is padding,
                //       not a branch.
                // Confirmed on gdlite-v7a: libfmod.so __macapk_synth_0008c33c
                // (a 4-tap vmla FIR loop) and libcocos2dcpp.so jpeg_idct_12x12
                // both had a real instruction spliced out because a
                // 0xd4d4-decoded "bmi"/"beq" resynced onto them. See
                // docs/arm32-exidx-confirmed-seeds_2026-09-28.md.
                if (s_pool_resync_srcfilter) {
                    if (opts_.is_literal_pool_byte &&
                        opts_.is_literal_pool_byte(static_cast<uint32_t>(in.address)))
                        continue;
                    if (in.size >= 2 && in.bytes[0] == 0xd4 && in.bytes[1] == 0xd4)
                        continue;
                }
                if (!in.detail) continue;
                const cs_arm& d = in.detail->arm;
                if (d.op_count < 1 ||
                    d.operands[d.op_count - 1].type != ARM_OP_IMM)
                    continue;
                const uint32_t t =
                    static_cast<uint32_t>(d.operands[d.op_count - 1].imm);
                if (t < fn.vaddr || t >= end_va || (t & 1u) || starts.count(t))
                    continue;
                // The sweep instruction covering t (cur is address-sorted).
                auto it = std::upper_bound(
                    cur.begin(), cur.end(), t,
                    [](uint32_t a, const cs_insn& x) { return a < x.address; });
                if (it == cur.begin()) continue;
                --it;
                const uint32_t s = static_cast<uint32_t>(it->address);
                if (!(s < t && t < s + it->size)) continue;
                if (!opts_.is_literal_pool_byte(s)) continue;
                todo.insert(t);
            }
            if (todo.empty()) break;
            for (uint32_t t : todo) {
                std::unordered_set<uint32_t> st2;
                for (const auto& in : cur) st2.insert(static_cast<uint32_t>(in.address));
                if (st2.count(t)) continue;   // an earlier splice fixed it
                cs_insn* ex = nullptr;
                const size_t en = cs_disasm(cs_.get(true), bytes + (t - fn.vaddr),
                                            end_va - t, t, 0, &ex);
                if (en == 0) continue;
                fix_carry_alu_setflags(ex, en, true);
                cs_hold.v.push_back({ex, en});
                std::vector<cs_insn> fresh;
                uint32_t sync = end_va;
                for (size_t k = 0; k < en; ++k) {
                    const uint32_t a = static_cast<uint32_t>(ex[k].address);
                    if (k > 0 && st2.count(a)) { sync = a; break; }
                    fresh.push_back(ex[k]);
                }
                std::vector<cs_insn> nxt;
                nxt.reserve(cur.size() + fresh.size());
                for (const auto& in : cur) {
                    const uint32_t a = static_cast<uint32_t>(in.address);
                    if (a + in.size <= t || a >= sync) nxt.push_back(in);
                }
                nxt.insert(nxt.end(), fresh.begin(), fresh.end());
                std::sort(nxt.begin(), nxt.end(),
                          [](const cs_insn& x, const cs_insn& y) {
                              return x.address < y.address; });
                cur.swap(nxt);
                changed = true;
                ++st_->pool_resync_targets;
            }
        }
        if (changed) {
            resynced = std::move(cur);
            insns = resynced.data();
            n = resynced.size();
        }
    }

    a64::A64Buf buf;
    DriverSink sink(buf);
    sink.is_softfloat_double = opts_.is_softfloat_double_plt;
    rc::PinnedRegFile regs;

    // ---- pass 1: which guest addresses are branched to from INSIDE
    // this function? Those and only those need a label. Doing this up
    // front means a forward branch is as cheap as a backward one.
    std::unordered_map<uint32_t, size_t> addr_to_index;
    for (size_t i = 0; i < n; ++i)
        addr_to_index[static_cast<uint32_t>(insns[i].address)] = i;

    // Labels MUST be minted through the sink, not through A64Buf.
    // BufSink keeps its own rc::Label -> a64::Label table and indexes it
    // by rc::Label::id, so an a64::Label handed back from
    // `label_for_guest` indexes the wrong vector (an immediate SIGSEGV in
    // BufSink::b_cond the first time a predicated instruction branches).
    std::unordered_map<uint32_t, rc::Label> labels;
    const uint32_t fn_end = fn.vaddr + fn.size;
    for (size_t i = 0; i < n; ++i) {
        const cs_insn& in = insns[i];
        // ARM INLINE JUMP TABLE: `add pc, pc, rN, lsl #k`.
        //
        // MUST be tested BEFORE the branch-only filter below. The table's
        // slots are reached only through the computed branch, so nothing
        // branches to them explicitly and the filter (which admits only
        // B/CBZ/CBNZ/BL/BLX) never lets an ADD through -- which is exactly
        // why an earlier attempt at this fix silently did nothing.
        //
        // Without a label per slot, xlat_core's dispatch finds none and falls
        // back to tail_branch_indirect -> __macapk_indirect_call -> FUNCTION
        // LUT miss (slots are mid-function, never function starts) -> panic.
        // 27 distinct sites in HCR 1.8.1's libgame; cocos2d
        // CCDictionary::objectForKey at 0x12a73c fires 145 times.
        //
        // pc_value is insn+8 in ARM state, so slot i sits at
        // (insn + 8) + (i << k). Anything before that (e.g. insn+4) is the
        // predicated fall-through default case, not a table entry.
        if (in.id == ARM_INS_ADD && !fn.is_thumb && in.detail) {
            const cs_arm& da = in.detail->arm;
            if (da.op_count == 3 &&
                da.operands[0].type == ARM_OP_REG &&
                da.operands[0].reg == ARM_REG_PC &&
                da.operands[1].type == ARM_OP_REG &&
                da.operands[1].reg == ARM_REG_PC &&
                da.operands[2].type == ARM_OP_REG) {
                unsigned sh = 0;
                if (da.operands[2].shift.type == ARM_SFT_LSL)
                    sh = static_cast<unsigned>(da.operands[2].shift.value);
                const uint32_t base = static_cast<uint32_t>(in.address) + 8u;
                for (uint32_t q = 0; q < 512u; ++q) {
                    const uint32_t slot = base + (q << sh);
                    if (slot < fn.vaddr || slot >= fn_end) break;
                    uint32_t word = 0;
                    if (!view_.read_u32(slot, &word)) break;
                    if ((word >> 24) != 0xEAu) break;   // ARM unconditional B
                    if (addr_to_index.find(slot) == addr_to_index.end()) break;
                    if (labels.find(slot) == labels.end())
                        labels[slot] = sink.new_label();
                    static const bool jt_tr =
                        std::getenv("MACAPK_JT_TRACE") != nullptr;
                    if (jt_tr)
                        std::fprintf(stderr, "[jt] prescan 0x%llx slot 0x%x\n",
                                     (unsigned long long)in.address, slot);
                }
            }
        }
        // THUMB inline jump table: `adr rB,#imm` + `ldr.w rV,[rB,rI,lsl #2]`
        // + `add rB,rV` + `bx rB`. Entries are 32-bit offsets FROM the table
        // base, so target = base + entry.
        //
        // Keyed on the ADR rather than the BX so no backward walk is needed
        // here: minting a label is cheap and harmless, and xlat_core's do_bx
        // does the real four-instruction validation before it dispatches. An
        // ADR that merely points at data mints nothing, because the
        // addr_to_index guard below requires every target to be a real
        // instruction boundary inside this function.
        //
        // HCR 1.43: libxml2 xmlNodeGetContent's node-type switch (table at
        // 0x2af864) is 6 of the 8 remaining indirect-call panics, and it gates
        // XML parsing -> UserDefault.xml -> the whole asset load.
        if (in.id == ARM_INS_ADR && fn.is_thumb && in.detail) {
            const cs_arm& da = in.detail->arm;
            if (da.op_count == 2 && da.operands[1].type == ARM_OP_IMM) {
                const uint32_t base =
                    ((static_cast<uint32_t>(in.address) + 4u) & ~3u) +
                    static_cast<uint32_t>(
                        static_cast<int32_t>(da.operands[1].imm));
                for (uint32_t q = 0; q < 512u; ++q) {
                    uint32_t off = 0;
                    if (!view_.read_u32(base + q * 4u, &off)) break;
                    const uint32_t slot = (base + off) & ~1u;
                    // Targets may be BEFORE the table: a switch that jumps
                    // into an unrolled Duff's-device sequence has its cases
                    // ahead of the dispatch (HCR 1.43 0x2c5240 -> 0x2c521e /
                    // 0x2c5226). A forward-only rule silently drops those, so
                    // termination relies on the two guards below instead --
                    // the target must be inside THIS function and land on a
                    // real instruction boundary.
                    if (slot == base) break;
                    if (slot < fn.vaddr || slot >= fn_end) break;
                    if (addr_to_index.find(slot) == addr_to_index.end()) break;
                    if (labels.find(slot) == labels.end())
                        labels[slot] = sink.new_label();
                    static const bool jt_tr2 =
                        std::getenv("MACAPK_JT_TRACE") != nullptr;
                    if (jt_tr2)
                        std::fprintf(stderr,
                                     "[jt] thumb prescan 0x%llx base=0x%x "
                                     "slot 0x%x\n",
                                     (unsigned long long)in.address, base,
                                     slot);
                }
            }
        }
        if (in.id != ARM_INS_B && in.id != ARM_INS_CBZ &&
            in.id != ARM_INS_CBNZ && in.id != ARM_INS_BL &&
            in.id != ARM_INS_BLX)
            continue;
        const cs_arm& d = in.detail->arm;
        for (int k = 0; k < d.op_count; ++k) {
            if (d.operands[k].type != ARM_OP_IMM) continue;
            const uint32_t t =
                static_cast<uint32_t>(d.operands[k].imm) & ~1u;
            // A BL to an address inside this same function is a real
            // CALL, not a local jump, so it must NOT become a label:
            // recursion and the shared-tail idiom both rely on the
            // return address. Only B / CBZ / CBNZ produce local labels.
            if (in.id == ARM_INS_BL || in.id == ARM_INS_BLX) continue;
            if (t < fn.vaddr || t >= fn_end) continue;
            if (addr_to_index.find(t) == addr_to_index.end()) continue;
            if (labels.find(t) == labels.end()) labels[t] = sink.new_label();
        }

    }

    // MACAPK_ARM32RC_MULTI_ENTRY. Mint a label for every address INSIDE
    // this function that some OTHER function branches to. The loop above
    // deliberately scopes label minting to targets of this function's own
    // branches, which is right for local control flow and is exactly why
    // an interior target of a cross-function branch has nothing to bind
    // to. `addr_to_index` is the gate that makes this safe: Capstone
    // actually stopped between two instructions here, so the address is a
    // provable instruction boundary and not the middle of a wide encoding
    // or a literal-pool word. Anything that fails it is silently dropped
    // and keeps its panic stub, which is the correct conservative answer.
    if (entry_targets) {
        for (uint32_t t : *entry_targets) {
            if (addr_to_index.find(t) == addr_to_index.end()) continue;
            if (labels.find(t) == labels.end()) labels[t] = sink.new_label();
        }
    }
    // MACAPK_ARM32RC_BL_LIVE_REGS: the direct-call stub branches to the first
    // guest instruction, so that address needs a label too.
    const uint32_t first_addr = n ? static_cast<uint32_t>(insns[0].address) : 0u;
    const bool want_direct = bl_live_regs_enabled() && n != 0 && insns[0].id != 0;
    if (want_direct && labels.find(first_addr) == labels.end())
        labels[first_addr] = sink.new_label();

    // ---- hooks
    rc::XlatOptions xopts;
    xopts.load_bias = opts_.load_bias;
    // Load-bearing: pc_value_for() reads this to pick +4 (Thumb) or +8
    // (ARM). Leaving it at the default gives every Thumb function the ARM
    // PC convention, which silently mis-addresses every literal-pool load
    // and every `add rN, pc` PIC pointer in Thumb code.
    xopts.thumb = fn.is_thumb;

    rc::XlatHooks hooks;
    hooks.read_image_u32 = [this](uint32_t v, uint32_t* o) {
        return view_.read_u32(v, o);
    };
    hooks.in_pointer_target_range = [this](uint32_t v) {
        // Mirrors the LLVM lifter's discriminator: only pointer-bearing
        // sections count, so a 2-byte-aligned .rodata string cannot
        // trigger a false `add rN, pc` unaligned override.
        for (const auto& s : img_.shdrs) {
            if (!(s.sh_flags & 0x2)) continue;          // SHF_ALLOC
            if (s.sh_addr == 0 || s.sh_size == 0) continue;
            if (v < s.sh_addr || v >= s.sh_addr + s.sh_size) continue;
            return (s.sh_flags & 0x1) != 0;             // SHF_WRITE
        }
        return false;
    };
    hooks.extern_tail_target = [](uint32_t v) {
        return frame_jump_enabled() && frame_jump_targets().count(v & ~1u) != 0;
    };
    hooks.label_for_guest = [&labels](uint32_t v, rc::Label* out) {
        auto it = labels.find(v & ~1u);
        if (it == labels.end()) return false;
        *out = it->second;
        return true;
    };
    hooks.note_extern_target = [](uint32_t, bool) {};
    hooks.warn = [](const std::string&) {};

    rc::XlatCore core(sink, regs, xopts, hooks);
    core.set_stream(insns, n);

    rc::XlatMemCtx mem;
    mem.buf = &buf;
    mem.is_thumb = fn.is_thumb;
    mem.load_bias = opts_.load_bias;
    mem.warn = [](const std::string&) {};
    // Per-store watchpoint call sites (MACAPK_ARM32RC_TRACE_STORES).
    std::vector<uint32_t> trace_store_sites;
    mem.trace_store_sites = &trace_store_sites;

    // ---- pass 2: emit
    DriverSink::emit_prologue(buf);
    // MACAPK_DIFF_VADDRS=<hex,hex,...> -- mirror of the LLVM path's selection
    // (arm32_lifter.cpp:272) so the SAME function can be instrumented on both
    // pipelines and the register snapshots diffed.
    {
        static const std::set<uint32_t> diff_vaddrs = [] {
            std::set<uint32_t> out;
            if (const char* e = std::getenv("MACAPK_DIFF_VADDRS")) {
                const char* p = e;
                while (*p) {
                    while (*p == ',' || *p == ' ' || *p == '\t') ++p;
                    if (!*p) break;
                    char* endp = nullptr;
                    unsigned long v = std::strtoul(p, &endp, 16);
                    if (endp == p) break;
                    out.insert(static_cast<uint32_t>(v) & ~1u);
                    p = endp;
                }
            }
            return out;
        }();
        if (diff_vaddrs.count(fn.vaddr & ~1u))
            sink.emit_capture_entry(fn.vaddr, fn.is_thumb);
    }
    const uint32_t base_adrp_word = DriverSink::emit_entry_state(buf);

    bool partial = false;
    out->body_off = buf.size_bytes();
    std::unordered_set<uint32_t> mid_set;
    if (entry_targets && frame_jump_enabled())
        mid_set.insert(entry_targets->begin(), entry_targets->end());
    for (size_t i = 0; i < n; ++i) {
        const cs_insn& in = insns[i];
        const uint32_t addr = static_cast<uint32_t>(in.address);
        auto lit = labels.find(addr);
        if (lit != labels.end()) sink.bind(lit->second);
        if (!mid_set.empty() && mid_set.count(addr))
            out->mids.push_back({addr, buf.size_bytes()});

        ++st_->insns_total;
        if (in.id == 0) { ++st_->insns_skipdata; continue; }

        regs.reset();
        mem.insn_addr = addr;
        sink.insn_addr = addr;

        const rc::XlatCore::Result r = core.translate(i);
        if (r == rc::XlatCore::Result::Ok ||
            r == rc::XlatCore::Result::Terminated) {
            ++st_->insns_core;
            continue;
        }
        if (r == rc::XlatCore::Result::Failed) {
            ++st_->insns_stubbed;
            st_->stub_histogram[in.mnemonic]++;
            partial = true;
            continue;
        }

        rc::XlatResult mr;
        bool handled = false;
        if (rc::is_mem_insn(in.id)) {
            handled = rc::translate_mem(mem, &in, &mr);
            if (handled) ++st_->insns_mem;
        }
        if (!handled && rc::is_simd_insn(in.id)) {
            handled = rc::translate_simd(mem, &in, &mr);
            if (handled) ++st_->insns_simd;
        }
        if (!handled) {
            ++st_->insns_stubbed;
            st_->stub_histogram[in.mnemonic]++;
            partial = true;
            continue;
        }
        if (mr.wrote_pc) {
            // THREE cases, not two. A guest PC write is a RETURN when it
            // comes off the stack, a CALL when the previous instruction
            // parked the return address in LR, and a computed JUMP
            // otherwise. Only the first two end the straight-line run.
            //
            // The call case is `mov lr, pc` + a PC write:
            //     mov lr, pc          @ lr = insn+8 = the instruction AFTER
            //     ldr pc, [r3, #0x8]  @ indirect CALL through a vtable
            //     mov r5, r0          @ hardware returns HERE
            // This is the ARMv4T-compatible virtual dispatch every pre-`blx`
            // toolchain emits. Treating it as a tail jump is catastrophic and
            // SILENT: emit_dispatch does run the callee and the callee does
            // return, but tail_branch_indirect then appends the epilogue and
            // `ret`, so the CALLER exits instead of resuming at the next
            // instruction. Every value the call was supposed to produce is
            // discarded and the rest of the function never runs.
            //
            // Measured across the fleet -- same source, both ABIs:
            //     Jetpack Joyride (armeabi): 1075 sites, 100% paired, vs 2 blx
            //     HCR 1.8.1 armeabi:           57 sites  vs 3493 blx
            //     HCR 1.8.1 armeabi-v7a:       57 sites  vs 3375 blx
            //     Flappy (both):                3 sites  vs 2 blx
            // JJ is a 2012 minAPI8 ARMv5TE build and uses this form for
            // essentially EVERY C++ virtual call -- 87 of its 783 ARM
            // functions (11.1%) contain one. HCR and Flappy structurally
            // cannot expose it, which is why it survived until an
            // armeabi-only app was tested. Same blind spot the comment at
            // emit_dispatch already records for a different defect ("253
            // sites in HCR, 0 in Flappy").
            bool pc_write_is_call = false;
            if (!mr.is_return && i > 0 && insns[i - 1].detail) {
                const cs_insn&  pv = insns[i - 1];
                const cs_arm&   pd = pv.detail->arm;
                pc_write_is_call =
                    pv.id == ARM_INS_MOV && pd.op_count == 2 &&
                    pd.operands[0].type == ARM_OP_REG &&
                    pd.operands[0].reg  == ARM_REG_LR &&
                    pd.operands[1].type == ARM_OP_REG &&
                    pd.operands[1].reg  == ARM_REG_PC;
            }
            if (pc_write_is_call) {
                // Dispatch WITHOUT the epilogue, then fall through to the
                // next guest instruction exactly as the hardware does.
                sink.call_indirect(static_cast<int>(mr.pc_reg));
                ++st_->pc_write_calls;
                if (mr.has_pending_skip) buf.bind(mr.pending_skip);
                continue;
            }
            if (mr.is_return) {
                sink.ret_from_function();
            } else {
                // A computed guest jump. tail_branch_indirect now routes
                // through __macapk_indirect_call, so the guest vaddr is
                // resolved via the LUT instead of being branched to as if
                // it were a host address.
                sink.tail_branch_indirect(static_cast<int>(mr.pc_reg));
            }
            // A CONDITIONAL PC write binds its skip label HERE, past the
            // terminator, so that "condition false" falls through to the next
            // guest instruction instead of executing the return/jump. Binding
            // it at the end of the body (which is where it naturally wants to
            // go) silently converts every conditional return into an
            // unconditional one -- see XlatResult::has_pending_skip for the
            // cocos2d-x heap overrun that came of it.
            if (mr.has_pending_skip) buf.bind(mr.pending_skip);
        }
    }

    // A function whose last instruction fell through (no terminator)
    // still needs a well-formed exit, or execution runs into whatever
    // the linker placed next.
    //
    // MACAPK_ARM32RC_FRAME_JUMP: when the address right after this extent is
    // itself a translated extent's start (or a planned interior entry), the
    // fall-through is what the hardware does -- execution simply continues
    // there. That is the normal case for one real function cut in pieces
    // (the 0x4000 extent clamp ends a piece in the middle of straight-line
    // code); returning instead, as the old exit did, abandoned the function
    // half way with whatever was in r0. After a real terminator this branch
    // is unreachable and costs one word.
    if (frame_jump_enabled() && n != 0) {
        const cs_insn& li = insns[n - 1];
        const uint32_t end_addr =
            static_cast<uint32_t>(li.address) + static_cast<uint32_t>(li.size);
        if (frame_jump_targets().count(end_addr & ~1u))
            sink.tail_branch_direct(end_addr);
    }
    DriverSink::emit_epilogue(buf);
    buf.put(a64::ret());

    // Word index of each alternate entry stub's guest-base adrp, reported
    // below as its own 0xfffffffe site. Empty unless the gate is on.
    std::vector<uint32_t> entry_base_words;

    // MACAPK_ARM32RC_MULTI_ENTRY: one ALTERNATE ENTRY STUB per interior
    // target, appended past the body's terminator so no fall-through can
    // reach it.
    //
    // WHY A STUB AND NOT A BARE LABEL. Defining `__macapk_entry_<vaddr>`
    // directly on the mid-body label would enter guest code with NO host
    // frame: emit_prologue's 96-byte frame unallocated, x19-x28 unsaved,
    // x27 (guest SP) never taken from w4, and x28 (guest arena base)
    // never loaded. The body's own terminator would then run
    // emit_epilogue, popping a frame that was never pushed. That is a
    // guaranteed host stack corruption, so the stub runs the FULL,
    // SHARED entry sequence and then jumps to the label. The result is
    // not a mid-body entry in the host sense at all: every entry into
    // guest code, from any path, arrives at an identical prologue.
    //
    // WHAT A MID-BODY ENTRY CAN AND CANNOT ASSUME. The stub stages
    // exactly what the contract above emit_override_shim states every
    // entry path into guest code stages: on entry w0-w4 are
    // (r0, r1, r2, r3, guest_sp). So guest r0-r3 and guest SP arrive
    // CORRECT, carried by call_direct / tail_branch_direct's `mov w4, w27`
    // and by AAPCS64 argument passing. Guest r4-r12 and r14 arrive ZEROED,
    // which is what every other function entry does.
    //
    // Zeroing is correct where the target is a real ABI boundary, and
    // that is the measured population here: AAPCS32 (IHI 0042, section
    // 5.1.1) makes r4-r11 callee-saved and r12/r14 caller-saved, so a
    // callee may not READ any of them before writing, and the caller's
    // values survive in its own host frame. A shared epilogue reached by
    // tail `b` restores r4-r8/r10 from the GUEST stack via `pop`, and the
    // guest SP it pops from is the one the branching function handed over
    // in w4, so it recovers the right values regardless of what the stub
    // put in those registers.
    //
    // Zeroing is NOT correct where discovery split ONE real function in
    // two and the "cross-function" branch is really a local jump with
    // r4-r11 live across it. This change does not make that case right;
    // it moves it from "returns 0, the code never runs" to "the code runs
    // with guest r4-r12/r14 zeroed". Both are wrong, and the second is
    // not provably better, which is the reason this is gated. Note that
    // no non-stub construction fixes it either: tail_branch_direct runs
    // emit_epilogue BEFORE the branch, so by the time control arrives
    // here x19-x28 hold the GRANDPARENT's guest registers, and inheriting
    // those would be strictly worse than a defined zero.
    if (entry_targets) {
        for (uint32_t t : *entry_targets) {
            auto lit = labels.find(t);
            // Absent means the pass-1 boundary check rejected it.
            if (lit == labels.end()) continue;
            // The label is necessarily BOUND: it is only minted when `t`
            // is in addr_to_index, and the emit loop binds every decoded
            // instruction's label before it does anything else with it,
            // including on the skipdata and translate-failure paths.
            const uint64_t stub_off = buf.size_bytes();
            DriverSink::emit_prologue(buf);
            const bool is_cont =
                tail_live_enabled() && continuation_targets().count(t) != 0;
            const uint32_t stub_adrp =
                DriverSink::emit_entry_state(buf, !is_cont);
            sink.b(lit->second);
            out->entries.push_back({t, stub_off});
            entry_base_words.push_back(stub_adrp);
        }
    }
    // MACAPK_ARM32RC_BL_LIVE_REGS: the non-zeroing entry for direct `bl`.
    // Same construction as an alternate entry stub (full prologue, guest SP
    // from w4, its own guest-base pair), just without the zeroing, so the
    // body's one epilogue pairs with it exactly as with the ordinary entry.
    if (want_direct) {
        auto lit = labels.find(first_addr);
        if (lit != labels.end()) {
            out->direct_off = buf.size_bytes();
            DriverSink::emit_prologue(buf);
            entry_base_words.push_back(
                DriverSink::emit_entry_state(buf, /*zero_regs=*/false));
            sink.b(lit->second);
        }
    }

    std::vector<a64::Fixup> overflow;
    if (!buf.resolve(&overflow) || !overflow.empty()) {
        return false;   // cs_hold frees every capstone allocation
    }

    out->code = buf.bytes();
    out->sites = sink.extern_sites();
    out->frame_sites = sink.frame_jump_sites();
    // Record where the prologue's guest-base adrp/ldr pair lives so the
    // module builder can attach the linker fixups.
    out->sites.push_back({base_adrp_word, 0xfffffffeu, false});
    // Each alternate entry stub carries its OWN guest-base adrp/ldr pair.
    for (uint32_t w : entry_base_words)
        out->sites.push_back({w, 0xfffffffeu, false});
    // 0xfffffffd marks a `bl __macapk_indirect_call` the module builder
    // must bind; the target is a fixed shim symbol, not a guest vaddr.
    for (uint32_t w : sink.dispatch_sites())
        out->sites.push_back({w, 0xfffffffdu, true});
    // 0xfffffffc marks a `bl __macapk_capture_entry` (diagnostic harness).
    for (uint32_t w : sink.capture_sites())
        out->sites.push_back({w, 0xfffffffcu, true});
    // 0xfffffffb marks a `bl __macapk_trace_store` (per-store watchpoint).
    for (uint32_t w : trace_store_sites)
        out->sites.push_back({w, 0xfffffffbu, true});
    out->ok = true;
    out->partial = partial;
    return true;   // cs_hold frees every capstone allocation
}

// =====================================================================
// Dispositions other than Translate
// =====================================================================
//
// These three emitters replace a guest body rather than translating it,
// for the reasons enumerated in macapk/arm32_discovery.h. All of them
// are entered under the SAME convention as a translated body, because
// every entry path into guest code stages it:
//
//   * DriverSink::call_direct / tail_branch_direct    `mov w4, w27`
//   * __macapk_invoke_safe(fn, r0,r1,r2,r3, sp)       5th C argument
//   * __macapk_indirect_call(t, r0,r1,r2,r3, sp, ..)  6th C argument
//
// so on entry w0-w4 are exactly (r0, r1, r2, r3, guest_sp). That is why
// an OverrideShim is ONE instruction: the arguments are already in the
// right registers and in the right order for a host callee declared
// `(uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) -> uint32_t`,
// which is the shape every override shim in bionic_shim.cpp has.

// A branch site inside a hand-emitted body: byte offset within the body
// and the undefined external it binds to.
struct BranchSite {
    uint64_t    byte_off;
    std::string symbol;
};

// Disposition::OverrideShim. `b <override_symbol>` and nothing else.
//
// The incumbent wraps the same call in a full LLVM function
// (`%r = call i32 @__macapk_shim_X(...); ret i32 %r`), which clang then
// tail-folds; a tail branch is the same thing arrived at directly. It
// is also correct for the NORETURN members of the family
// (__macapk_lifted_throw_recover siglongjmps back into
// __macapk_invoke_safe and never returns), where a call-and-return
// shape would emit dead code after the call.
//
// Note the guard family: bionic_shim declares
// `__macapk_shim___cxa_guard_acquire(uint32_t guard_vaddr, uint32_t,
// uint32_t, uint32_t)` and IGNORES arguments 1-3, so forwarding the
// guest's real r1-r3 instead of the incumbent's explicit zeros is
// observationally identical.
void emit_override_shim(a64::A64Buf& b, const DiscoveredFunction& fn,
                        std::vector<BranchSite>* sites) {
    sites->push_back({b.size_words() * 4ull, fn.override_symbol});
    b.put(a64::b(0));
}

// Disposition::OverrideConst. Return a fixed value.
//
// The Parity frame is emitted even though the body touches no
// callee-saved register, so that EVERY function this driver produces
// has the identical prologue/epilogue shape. That uniformity is not
// cosmetic: the static audits fingerprint the Parity prologue across
// 100% of functions to prove SP alignment, x29 chaining and
// epilogue-before-every-ret, and a body that opts out is a hole in that
// coverage rather than a saving worth 12 instructions on a path that
// runs once per divide-by-zero.
void emit_override_const(a64::A64Buf& b, const DiscoveredFunction& fn) {
    DriverSink::emit_prologue(b);
    // int32_t -> the 32-bit pattern. mov_imm picks the shortest form:
    // INT_MAX (the __aeabi_[il]div0 value) is a single ORR-bitmask
    // because 0x7fffffff is a run of ones, and 0 (the pure-thrower
    // value) is a single MOVZ.
    b.mov_imm(false, X(HostGpr::X0),
              static_cast<uint32_t>(fn.override_const));
    DriverSink::emit_epilogue(b);
    b.put(a64::ret());
}

// Disposition::Veneer. An ARM long-call veneer is a pure forwarder, so
// this is a tail call with no frame at all.
//
// It goes through __macapk_indirect_call rather than branching straight
// at the target's symbol, mirroring the incumbent (main.cpp:2526) and
// for its stated reason: a variant-B (`bx r12`) veneer often points at
// intra-image Thumb code that discovery did not otherwise reach, so the
// target may not be in this module. The dispatcher resolves ANY guest
// vaddr (lifted, PLT thunk, or miss-with-a-diagnostic) and, critically,
// it is the only path that can honour the Thumb LSB the original
// `bx r12` used to select mode.
//
//   in : w0-w4 = r0, r1, r2, r3, guest_sp
//   out: __macapk_indirect_call(target, r0, r1, r2, r3, sp, from_vaddr)
//
// The register moves run in DESCENDING order because each destination
// is the next one's source.
void emit_veneer(a64::A64Buf& b, const DiscoveredFunction& fn,
                 uint32_t load_bias, std::vector<BranchSite>* sites) {
    // from_vaddr: the diagnostic argument. The incumbent's IR declares
    // the dispatcher with 6 parameters and passes 6, so its 7th reads
    // whatever was in w6; naming the veneer here is strictly better and
    // costs nothing.
    b.mov_imm(false, X(HostGpr::X6), fn.vaddr + load_bias);
    b.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X4)));
    b.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X3)));
    b.put(a64::mov_reg(false, X(HostGpr::X3), X(HostGpr::X2)));
    b.put(a64::mov_reg(false, X(HostGpr::X2), X(HostGpr::X1)));
    b.put(a64::mov_reg(false, X(HostGpr::X1), X(HostGpr::X0)));
    // The Thumb LSB on `veneer_target` is DELIBERATELY not masked: it is
    // how the original `bx r12` selected mode and how the dispatcher
    // picks a Thumb-mode body.
    b.mov_imm(false, X(HostGpr::X0), fn.veneer_target + load_bias);
    sites->push_back({b.size_words() * 4ull, "__macapk_indirect_call"});
    b.put(a64::b(0));
}

// =====================================================================
// JNI entry thunks
// =====================================================================
//
// HotSpot binds `<name>`; the guest body speaks the lifted ABI. Between
// them sits an AAPCS64 function that (a) tags the call for the crash
// handler, (b) allocates a guest stack, (c) narrows every 64-bit
// JNIEnv*/jobject to a 32-bit handle, and (d) widens a 32-bit handle
// back to a pointer on the way out. Reference: main.cpp:5288-5421.
//
// TWO shapes, and the difference is ABI-forced:
//
//   generic   -> __macapk_arm32_jni_call, which owns the AAPCS32
//                soft-float walk (ints and refs in core slots, jfloat as
//                its raw 32 bits in the next core slot, jlong/jdouble in
//                an even-aligned pair) keyed on the descriptor the JVMTI
//                NativeMethodBind callback registered against THIS
//                THUNK's address. It forwards x0-x7 AND v0-v7 (v1.0.801),
//                so a float parameter reaches the marshaller wherever it
//                sits in the signature.
//   JNI_OnLoad-> its first argument is a JavaVM*, not a JNIEnv*, so it
//                mints a VM-flavoured handle and calls invoke_safe
//                directly; it also clamps the returned JNI version.
//
// A third, NAME-KEYED shape ("typed float", cocos2d-x nativeTouchesBegin /
// nativeTouchesEnd) survives only behind MACAPK_ARM32RC_GENERIC_FP_JNI=0.
// Real Android has no name-based special case: ART marshals every native
// from its shorty, and on 32-bit ARM that is soft-float ("JNI uses
// soft-float, so there is only a GPR list", art
// compiler/jni/quick/arm/calling_convention_arm.cc:34). The generic shape
// plus the descriptor walk IS that rule. See generic_fp_jni_enabled().
class JniThunkEmitter {
public:
    JniThunkEmitter(MachOLinker& link, uint32_t gb_sym)
        : link_(link), gb_sym_(gb_sym) {}

    // Emit the thunk for `fn`, whose recompiled body is symbol
    // `body_sym`. Defines `fn.symbol` at the thunk. Returns false only
    // on an internal encoding failure.
    bool emit(const DiscoveredFunction& fn, uint32_t body_sym,
              std::string* error);

private:
    // LEGACY, emitted only under MACAPK_ARM32RC_GENERIC_FP_JNI=0. The
    // cocos2d-x (IFF)V touch entries, transcribed from the deleted LLVM
    // path's get_float_handler (9ce85bad, "Hill Climb Boots"), which
    // keyed a hand-written float thunk on the export NAME because the
    // IR thunk of the day declared every parameter `ptr` and so read
    // only x-registers.
    //
    // Why it is no longer needed (2026-09-27, the history in one place):
    //   * v1.0.801 (23a0205a) made emit_generic spill and forward d0-d7,
    //     so the FP argument bank reaches __macapk_arm32_jni_call for
    //     EVERY export. The old KNOWN BUG note here ("the emitted thunk
    //     contains ZERO fmov / str-s", onSensorChanged (FFFJ)V dropping
    //     its jfloats) described the thunk before that change.
    //   * Two marshaller defects still stopped the floats short, which
    //     is why deleting this table used to break HCR 1.8.1 taps: the
    //     sig registry kept only J/D-bearing descriptors (so (IFF)V was
    //     a miss, and the miss path reads x2-x7 only), and a jfloat was
    //     decoded as `static_cast<float>(double)`, a NUMERIC conversion
    //     of the denormal 0x00000000_<floatbits> that HotSpot's S-write
    //     leaves in d0, i.e. +0.0 always. Both are fixed in
    //     bionic_shim.cpp (MACAPK_ARM32_JNI_SIG_FP,
    //     MACAPK_ARM32_JNI_FLOAT_BITS), after which the generic thunk
    //     hands the guest exactly the soft-float image this shape does:
    //     (IFF)V -> r2 = id, r3 = x bits, [sp+0] = y bits.
    //   * The `jni_has_fp_args` DiscoveredFunction flag once proposed
    //     here is NOT needed and would be the wrong layer: the
    //     translator has no signature, only the NMB bind-time
    //     descriptor does.
    //   * The "two lists MUST be edited together" rule died with the
    //     LLVM twin (v1.0.802, 54aa580a); this is the last copy.
    //
    // Delete this predicate and emit_typed_float after one soak with the
    // gate ON (docs/arm32-jni-float-args_2026-09-27.md).
    static bool is_typed_float(const std::string& n) {
        return n.size() >= 18 &&
               (n.find("nativeTouchesBegin") != std::string::npos ||
                n.find("nativeTouchesEnd") != std::string::npos);
    }

    void call(a64::A64Buf& c, const char* name) {
        calls_.push_back({c.size_words() * 4ull, link_.undefined(name)});
        c.put(a64::bl(0));
    }
    void adrp_add(a64::A64Buf& c, unsigned rd, Ref target) {
        adrp_pairs_.push_back({c.size_words() * 4ull, target});
        c.put(a64::adrp(rd, 0));
        c.put(a64::add_imm(true, rd, rd, 0));
    }
    // adrp/ldr onto the module's guest-base word. Same pair the function
    // prologue uses; scale 8 because the patched instruction is an X-reg
    // load (macho_link.h, Fix::LdrLo12).
    void adrp_ldr_guest_base(a64::A64Buf& c, unsigned rd) {
        gb_pairs_.push_back(c.size_words() * 4ull);
        c.put(a64::adrp(rd, 0));
        c.put(a64::ldr(true, rd, rd, 0));
    }

    // The per-symbol diagnostic tag the SEGV handler reads back, so a
    // crash log says "JNI:Java_..._nativeRender" instead of "?".
    Ref tag_for(const std::string& symbol) {
        const std::string tag = "JNI:" + symbol;
        return Ref::local(Sec::Const,
                          link_.add_const(tag.c_str(), tag.size() + 1, 1));
    }

    void emit_generic(a64::A64Buf& c, const DiscoveredFunction& fn,
                      uint32_t body_sym);
    void emit_on_load(a64::A64Buf& c, uint32_t body_sym);
    void emit_typed_float(a64::A64Buf& c, const DiscoveredFunction& fn,
                          uint32_t body_sym);

    MachOLinker& link_;
    uint32_t gb_sym_;
    std::vector<std::pair<uint64_t, uint32_t>> calls_;      // off, sym
    std::vector<std::pair<uint64_t, Ref>>      adrp_pairs_;  // off, target
    std::vector<uint64_t>                      gb_pairs_;    // off of adrp
};

// Outgoing stack-argument area for __macapk_arm32_jni_call. Its 11
// arguments put a6 at [sp+0], a7 at [sp+8] and sp_init at [sp+16].
//
// sp_init is an i32 and Apple's ARM64 ABI ("Writing ARM64 Code for
// Apple Platforms", Passing Arguments to Functions) says: "Function
// arguments may consume slots on the stack that are not multiples of
// 8 bytes", so it occupies FOUR bytes at +16, not eight. Confirmed
// against the callee's own machine code rather than assumed: clang
// compiles __macapk_arm32_jni_call's prologue to
//     ldr x8,  [x29, #0x10]   ; a6      (8 bytes at incoming sp+0)
//     ldr x8,  [x29, #0x18]   ; a7      (8 bytes at incoming sp+8)
//     ldr w26, [x29, #0x20]   ; sp_init (FOUR bytes at incoming sp+16)
// For THIS argument list the Apple rule and naive 8-byte slots agree on
// the offset 16; they would not for a signature with two adjacent
// sub-8-byte arguments, which is the class of bug recorded in the
// "JNI native-downcall ABI stack packing" note (v392).
//
// 20 bytes of arguments rounded up to 32 so SP stays 16-byte aligned at
// the branch, which the ABI requires at every public interface.
constexpr int kJniCallArgArea = 32;

// 8 doubles = 64 bytes, already a multiple of 16 so SP stays aligned.
constexpr int kJniFpSaveArea = 64;

// MACAPK_ARM32RC_GENERIC_FP_JNI (2026-09-27, default ON). Read at TRANSLATE
// time, like MACAPK_ARM32RC_TAIL_LIVE: every JNI export except JNI_OnLoad
// gets the generic thunk, including the cocos2d-x nativeTouchesBegin/End
// names that used to get the name-keyed typed-float shape. =0 restores that
// dispatch exactly (is_typed_float + emit_typed_float are kept for it).
//
// Depends on the shim half landing first: the generic thunk only carries a
// jfloat correctly once __macapk_arm32_jni_register_sig keeps F-bearing
// descriptors (MACAPK_ARM32_JNI_SIG_FP) and the walk decodes a jfloat as the
// low 32 bits of its register (MACAPK_ARM32_JNI_FLOAT_BITS). With either
// shim gate at =0 the generic thunk hands (IFF)V taps junk or +0.0, so an
// A/B of THIS gate must leave both shim gates at their default.
//
// A/B note: the env var is not part of the translated-dylib cache key (the
// <dylib>.nativeid sidecar holds NATIVE_ID, which hashes the translator and
// shim BINARIES, not the environment), so flipping it needs a re-translate:
// MACAPK_SWEEP_CLEAR=dylib (a debug/sweep_one.sh convention, not a runtime
// env var), or delete the app's lib_native/*.dylib by hand. The translator
// runs as a child of the runtime, so the variable must be in the RUNTIME's
// environment on the launch that re-translates.
bool generic_fp_jni_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_ARM32RC_GENERIC_FP_JNI");
        return !(e && *e == '0');   // default ON
    }();
    return on;
}

bool JniThunkEmitter::emit(const DiscoveredFunction& fn, uint32_t body_sym,
                           std::string* error) {
    calls_.clear();
    adrp_pairs_.clear();
    gb_pairs_.clear();

    a64::A64Buf c;
    if (fn.symbol == "JNI_OnLoad")        emit_on_load(c, body_sym);
    else if (!generic_fp_jni_enabled() && is_typed_float(fn.symbol))
                                          emit_typed_float(c, fn, body_sym);
    else                                  emit_generic(c, fn, body_sym);

    std::vector<a64::Fixup> overflow;
    if (!c.resolve(&overflow) || !overflow.empty()) {
        if (error) *error = "JNI thunk fixup out of range: " + fn.symbol;
        return false;
    }

    const std::vector<uint8_t> cb = c.bytes();
    const uint64_t off = link_.add_text(cb.data(), cb.size(), 4);
    link_.define(fn.symbol, Sec::Text, off, true);

    for (const auto& p : calls_)
        link_.branch(Sec::Text, off + p.first, p.second);
    for (const auto& p : adrp_pairs_)
        link_.adrp_add_pair(Sec::Text, off + p.first, off + p.first + 4,
                            p.second);
    for (uint64_t g : gb_pairs_) {
        Fixup f1;
        f1.kind = Fix::Adrp21; f1.sec = Sec::Text; f1.off = off + g;
        f1.target = Ref::symbol(gb_sym_);
        link_.add_fixup(f1);
        Fixup f2;
        f2.kind = Fix::LdrLo12; f2.sec = Sec::Text; f2.off = off + g + 4;
        f2.target = Ref::symbol(gb_sym_);
        f2.scale = 8;
        link_.add_fixup(f2);
    }
    return true;
}

// The generic thunk. Mirrors main.cpp:5364-5408.
//
//   in : x0 = JNIEnv*, x1..x7 = the JNI arguments as opaque 64-bit values
//   out: x0 = a resolved jobject pointer, or zext(the 32-bit primitive)
void JniThunkEmitter::emit_generic(a64::A64Buf& c,
                                   const DiscoveredFunction& fn,
                                   uint32_t body_sym) {
    // `self` is THIS thunk's own address: the key the JVMTI
    // NativeMethodBind callback registered the method descriptor under,
    // because that is the address dlsym handed the JVM. A label bound at
    // word 0 plus ADR reaches it without a relocation, and the image is
    // arm64 (not arm64e), so no pointer-authentication diversifier can
    // make the runtime value differ from this one.
    const a64::Label self = c.new_label();
    c.bind(self);

    DriverSink::emit_prologue(c);
    // x0-x7 must survive two calls; x19-x28 are callee-saved (AAPCS64
    // Table 2) and the Parity prologue already saved all of them.
    for (unsigned i = 0; i <= 7; ++i)
        c.put(a64::mov_reg(true, 19 + i, i));

    // v1.0.801: d0-d7 must survive those same two calls. AAPCS64 makes
    // v0-v7 CALLER-saved exactly like x0-x7, and a jfloat/jdouble JNI
    // parameter arrives in that bank -- so __macapk_set_lifted_context and
    // __macapk_alloc_thread_stack were destroying every float argument
    // before the marshaller ever saw one. Only x0-x7 were preserved, which
    // is why a name-keyed table of hand-written "typed float" thunks was
    // needed at all, and why box2d's jniGetLocalPoint(JFF[F)V handed
    // GetPrimitiveArrayCritical a garbage jfloat[] and aborted HotSpot.
    //
    // Spill to our own 64-byte frame rather than into d8-d15: those are
    // callee-saved, so parking values there would oblige THIS thunk to
    // save/restore them for the JVM. The frame is freed just before the
    // epilogue; the kJniCallArgArea push below nests inside it, which is
    // why the reload offset adds kJniCallArgArea.
    c.put(a64::sub_imm(true, a64::kSP, a64::kSP, kJniFpSaveArea));
    for (unsigned i = 0; i <= 7; ++i)
        c.put(a64::str_d(i, a64::kSP, i * 8));

    adrp_add(c, X(HostGpr::X0), tag_for(fn.symbol));
    call(c, "__macapk_set_lifted_context");
    call(c, "__macapk_alloc_thread_stack");            // w0 = sp_init
    c.put(a64::mov_reg(false, X(HostGpr::X27), X(HostGpr::X0)));

    // Arguments 9, 10 and 11 go on the stack. See kJniCallArgArea.
    c.put(a64::sub_imm(true, a64::kSP, a64::kSP, kJniCallArgArea));
    c.put(a64::str(true,  X(HostGpr::X25), a64::kSP, 0));    // a6
    c.put(a64::str(true,  X(HostGpr::X26), a64::kSP, 8));    // a7
    c.put(a64::str(false, X(HostGpr::X27), a64::kSP, 16));   // sp_init, 4 B

    // x3..x7 = a1..a5, x2 = env. Sources are x19-x24, destinations are
    // x2-x7, so the two sets are disjoint and the order is free.
    c.put(a64::mov_reg(true, X(HostGpr::X3), X(HostGpr::X20)));
    c.put(a64::mov_reg(true, X(HostGpr::X4), X(HostGpr::X21)));
    c.put(a64::mov_reg(true, X(HostGpr::X5), X(HostGpr::X22)));
    c.put(a64::mov_reg(true, X(HostGpr::X6), X(HostGpr::X23)));
    c.put(a64::mov_reg(true, X(HostGpr::X7), X(HostGpr::X24)));
    c.put(a64::mov_reg(true, X(HostGpr::X2), X(HostGpr::X19)));
    c.adr(X(HostGpr::X1), self);
    adrp_add(c, X(HostGpr::X0), Ref::symbol(body_sym));
    // Restore the FP argument bank into v0-v7, where
    // __macapk_arm32_jni_call takes its 8 trailing `double` parameters.
    // SP is currently entry-kJniFpSaveArea-kJniCallArgArea.
    for (unsigned i = 0; i <= 7; ++i)
        c.put(a64::ldr_d(i, a64::kSP, kJniCallArgArea + i * 8));
    call(c, "__macapk_arm32_jni_call");                // w0 = lifted_ret
    c.put(a64::add_imm(true, a64::kSP, a64::kSP, kJniCallArgArea));

    // A return value IS a jobject iff it resolves to a LIVE handle;
    // resolve_handle returns null for anything that is not a key in the
    // handle map, so "resolved != null" is an exact membership test with
    // no magic threshold (main.cpp documents the 3 GiB predicate that
    // used to be here and never matched). The 32-bit `mov` zero-extends,
    // which is the `zext i32 %lifted_ret to i64` on the primitive arm,
    // and it does not disturb w0, which is already the argument.
    c.put(a64::mov_reg(false, X(HostGpr::X19), X(HostGpr::X0)));
    call(c, "__macapk_jni_resolve_handle");            // x0 = ptr or null
    c.put(a64::cmp_imm(true, X(HostGpr::X0), 0));
    c.put(a64::csel(true, X(HostGpr::X0), X(HostGpr::X0), X(HostGpr::X19),
                    a64::CondNE));

    c.put(a64::add_imm(true, a64::kSP, a64::kSP, kJniFpSaveArea));
    DriverSink::emit_epilogue(c);
    c.put(a64::ret());
}

// JNI_OnLoad. Mirrors main.cpp:5297-5313.
//
// Its first argument is a JavaVM*, not a JNIEnv*, so it mints a
// VM-flavoured handle (a separate trampoline range, which is what makes
// a later (*vm)->GetEnv() dispatch through our VmGetEnv forwarder). It
// calls __macapk_invoke_safe directly rather than the marshalling
// helper, because the guest signature is fixed: (vm, 0, 0, 0, sp).
void JniThunkEmitter::emit_on_load(a64::A64Buf& c, uint32_t body_sym) {
    DriverSink::emit_prologue(c);
    call(c, "__macapk_jni_vm_handle");                 // x0 already = JavaVM*
    c.put(a64::mov_reg(false, X(HostGpr::X19), X(HostGpr::X0)));
    call(c, "__macapk_alloc_thread_stack");            // w0 = sp_init
    c.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X0)));   // sp
    c.put(a64::mov_reg(false, X(HostGpr::X1), X(HostGpr::X19)));  // r0 = vm
    c.put(a64::mov_reg(false, X(HostGpr::X2), a64::kZR));
    c.put(a64::mov_reg(false, X(HostGpr::X3), a64::kZR));
    c.put(a64::mov_reg(false, X(HostGpr::X4), a64::kZR));
    adrp_add(c, X(HostGpr::X0), Ref::symbol(body_sym));
    call(c, "__macapk_invoke_safe");                   // w0 = returned version

    // Default to JNI 1.6 if the guest returned something implausible.
    // 0x10004 does not fit CMP (immediate), whose imm12 is 0..4095
    // optionally shifted left by 12 (DDI 0487, SUBS (immediate)), and
    // 0x10004 is neither, so it has to be materialised first. x8/x9 are
    // temporaries with no ABI role; x16/x17 would NOT be safe here in
    // general because the linker may clobber them in a branch veneer
    // (AAPCS64 Table 2, "IP0/IP1 ... may be used by a linker as a
    // scratch register between a routine and any subroutine it calls").
    c.mov_imm(false, X(HostGpr::X8), 65540);           // 0x10004, JNI 1.4
    c.put(a64::cmp_reg(false, X(HostGpr::X0), X(HostGpr::X8)));
    c.mov_imm(false, X(HostGpr::X9), 65542);           // 0x10006, JNI 1.6
    // Signed compare, matching `icmp slt`: a negative return is also
    // implausible and must be replaced. The 32-bit csel zero-extends,
    // which is the `zext i32 %final to i64`.
    c.put(a64::csel(false, X(HostGpr::X0), X(HostGpr::X9), X(HostGpr::X0),
                    a64::CondLT));

    DriverSink::emit_epilogue(c);
    c.put(a64::ret());
}

// The typed-float thunk. LEGACY: emitted only under
// MACAPK_ARM32RC_GENERIC_FP_JNI=0 (see generic_fp_jni_enabled()); the
// generic thunk plus the shim's descriptor walk now produces the same guest
// register image from the method's signature instead of its name. Mirrors
// the deleted LLVM path's main.cpp:5266-5286 + 5340-5356.
//
// WHY A SEPARATE SHAPE. AAPCS64 fills the general and SIMD argument
// banks INDEPENDENTLY, so for
//   nativeTouchesBegin(JNIEnv*, jobject, jint id, jfloat x, jfloat y)
// the JVM passes x0 = env, x1 = this, w2 = id, s0 = x, s1 = y. A thunk
// that only reads x-registers never sees the two floats at all. The
// lifted callee is soft-float, so each jfloat crosses as its raw 32-bit
// pattern: x in guest r3, y on the guest stack at sp+0 (ARM passes only
// four arguments in core registers).
//
// s0/s1 are captured BEFORE the first `bl` because v0-v7 are
// caller-saved (AAPCS64 Table 2) and the very first call would destroy
// them.
void JniThunkEmitter::emit_typed_float(a64::A64Buf& c,
                                        const DiscoveredFunction& fn,
                                        uint32_t body_sym) {
    DriverSink::emit_prologue(c);
    // FMOV Wd, Sn (DDI 0487, FMOV (general), sf=0 ptype=00 rmode=00
    // opcode=110): move the raw bits, no conversion.
    c.put(a64::fmov_to_gpr(false, 0, X(HostGpr::X19), 0));   // x_bits <- s0
    c.put(a64::fmov_to_gpr(false, 0, X(HostGpr::X20), 1));   // y_bits <- s1
    c.put(a64::mov_reg(true,  X(HostGpr::X21), X(HostGpr::X0)));  // env
    c.put(a64::mov_reg(true,  X(HostGpr::X22), X(HostGpr::X1)));  // this
    c.put(a64::mov_reg(false, X(HostGpr::X23), X(HostGpr::X2)));  // jint id

    adrp_add(c, X(HostGpr::X0), tag_for(fn.symbol));
    call(c, "__macapk_set_lifted_context");
    c.put(a64::mov_reg(true, X(HostGpr::X0), X(HostGpr::X21)));
    call(c, "__macapk_jni_env_handle");
    c.put(a64::mov_reg(false, X(HostGpr::X24), X(HostGpr::X0)));  // h0
    c.put(a64::mov_reg(true, X(HostGpr::X0), X(HostGpr::X22)));
    call(c, "__macapk_jni_make_handle");
    c.put(a64::mov_reg(false, X(HostGpr::X25), X(HostGpr::X0)));  // h1
    call(c, "__macapk_alloc_thread_stack");
    c.put(a64::mov_reg(false, X(HostGpr::X26), X(HostGpr::X0)));  // sp_init

    // Store y's bits at guest sp+0. The guest arena base comes from the
    // module's own __DATA word, which the module constructor filled from
    // __macapk_get_guest_base() at dlopen; every recompiled function
    // prologue already depends on that ordering, and a JNI entry cannot
    // run before the image's initialisers have.
    adrp_ldr_guest_base(c, X(HostGpr::X8));
    // The 32-bit mov above zero-extended sp_init, so this 64-bit add is
    // a clean base + offset.
    c.put(a64::add_reg(true, X(HostGpr::X8), X(HostGpr::X8),
                       X(HostGpr::X26)));
    c.put(a64::str(false, X(HostGpr::X20), X(HostGpr::X8), 0));

    c.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X26)));  // sp
    c.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X19)));  // r3 = x
    c.put(a64::mov_reg(false, X(HostGpr::X3), X(HostGpr::X23)));  // r2 = id
    c.put(a64::mov_reg(false, X(HostGpr::X2), X(HostGpr::X25)));  // r1 = h1
    c.put(a64::mov_reg(false, X(HostGpr::X1), X(HostGpr::X24)));  // r0 = h0
    adrp_add(c, X(HostGpr::X0), Ref::symbol(body_sym));
    call(c, "__macapk_invoke_safe");
    // The touch entries return void; the i64 return exists only for the
    // ABI. Zero-extend so no stale high half reaches the JVM.
    c.put(a64::mov_reg(false, X(HostGpr::X0), X(HostGpr::X0)));

    DriverSink::emit_epilogue(c);
    c.put(a64::ret());
}

}  // namespace

// ---------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------
bool build_module(const Elf32Image& img,
                  const std::vector<DiscoveredFunction>& funcs_in,
                  const RcOptions& opts,
                  const std::string& out_path,
                  RcStats* stats,
                  std::string* error) {
    RcStats local;
    RcStats& st = stats ? *stats : local;
    st.functions_in = funcs_in.size();

    CsPair cs;
    if (!cs.ok()) {
        if (error) *error = "capstone open failed";
        return false;
    }
    ImageView view(img);

    LinkOptions lo;
    lo.install_name = out_path;
    lo.shim_dir = opts.shim_dir;
    lo.shim_path = opts.shim_path;
    lo.verbose = opts.verbose;
    MachOLinker link(lo);

    // Cross-guest-library linking (2026-07-28): register every sibling
    // dependency main.cpp's ELF scan found before anything else runs, so
    // it's in place no matter which of build_module's later phases first
    // needs it. See docs/cross-guest-library-linking_2026-07-28.md.
    for (const auto& dep : opts.extra_dylib_deps) link.add_dependency(dep);
    // MACAPK_ARM32_CXXABI_SIBLING_FIRST (main.cpp fills the map; empty when
    // the gate is off or nothing qualifies, and then nothing is recorded).
    for (const auto& tl : opts.two_level_imports)
        link.bind_to_dependency(tl.first, tl.second);

    // P5 (macho_link.h): periodically reserve a veneer pool so a
    // Unity/il2cpp-scale module (hundreds of MiB of recompiled __text)
    // never leaves a call site more than kVeneerPoolRecommendedInterval
    // Bytes from a pool that can reach it. Checked with a FRESH query
    // each time (text_size_hint(), not a hand-tracked running total),
    // so it can never drift out of sync no matter how many separate
    // loops/lambdas below call add_text(). A tiny module (HCR, Flappy,
    // ...) never crosses the threshold here at all; write() still
    // auto-reserves exactly one pool at the tail for it, identical to
    // every module built before this existed.
    uint64_t next_pool_at = kVeneerPoolRecommendedIntervalBytes;
    auto maybe_reserve_pool = [&]() {
        const uint64_t cur = link.text_size_hint();
        if (cur >= next_pool_at) {
            link.reserve_veneer_pool();
            next_pool_at = link.text_size_hint() + kVeneerPoolRecommendedIntervalBytes;
        }
    };

    // The guest-base word is declared BEFORE the function loop because
    // the typed-float JNI thunk needs to read it too.
    const uint64_t gb_off = link.add_bss(8, 8);
    const uint32_t gb_sym = link.define(kGuestBaseSym, Sec::Bss, gb_off, false);

    // ---- 1. emit every function ------------------------------------
    struct Emitted {
        const DiscoveredFunction* fn;
        uint64_t text_off;
        uint32_t sym;
        std::vector<rc::BufSink::ExternSite> sites;
        // MACAPK_ARM32RC_MULTI_ENTRY: (guest vaddr, byte offset into this
        // body) per alternate entry stub. Always empty with the gate off.
        std::vector<std::pair<uint32_t, uint64_t>> entries;
        uint64_t direct_off = ~0ull;   // MACAPK_ARM32RC_BL_LIVE_REGS stub, ~0 = none
        std::unordered_set<uint32_t> frame_sites;   // MACAPK_ARM32RC_FRAME_JUMP
    };
    std::vector<Emitted> emitted;
    // MACAPK_ARM32RC_BL_LIVE_REGS: function start vaddr -> its non-zeroing
    // `__macapk_dcall_<vaddr>` entry, for direct `bl` sites.
    std::unordered_map<uint32_t, uint32_t> vaddr_to_dcall;
    // MACAPK_ARM32RC_FRAME_JUMP: guest vaddr -> the body / interior symbol a
    // frame-keeping `b` binds to. Function starts are entered first and win.
    std::unordered_map<uint32_t, uint32_t> vaddr_to_fj;
    std::vector<std::pair<uint32_t, uint64_t>> fj_mids_pending;   // (vaddr, text off)
    size_t fj_bound = 0, fj_thunked = 0;
    std::unordered_map<uint32_t, uint32_t> vaddr_to_sym;

    // MACAPK_ARM32RC_EXTEND_FALLOFF: every discovered start, sorted, then the
    // repaired extents. Done BEFORE the multi-entry plan, which scans each
    // extent for branches into other extents: a plan made on the short extents
    // never sees the branches in the part that is added, and their targets
    // would bind panic stubs.
    std::vector<DiscoveredFunction> funcs_repaired;
    {
        auto& fs = sorted_fn_starts();
        fs.clear();
        for (const DiscoveredFunction& fn : funcs_in) fs.push_back(fn.vaddr & ~1u);
        std::sort(fs.begin(), fs.end());
        fs.erase(std::unique(fs.begin(), fs.end()), fs.end());
        extend_falloff_count() = 0;
        extend_falloff_bytes() = 0;
        funcs_repaired = funcs_in;
        if (extend_falloff_enabled())
            for (DiscoveredFunction& fn : funcs_repaired)
                if (fn.disposition == Disposition::Translate)
                    fn.size = extended_size_of(view, cs, fn);
    }
    const std::vector<DiscoveredFunction>& funcs = funcs_repaired;
    // MACAPK_ARM32RC_MULTI_ENTRY: which interior addresses does anything
    // branch to? Computed BEFORE the emit loop because a label has to
    // exist while its function is being emitted, and the function that
    // branches in may be emitted either before or after it.
    PhaseTimer ptimer;
    MultiEntryPlan me_plan;
    if (multi_entry_enabled()) {
        // Guest PLT span, from the image's own stub addresses. Padded by one
        // stub (lld: 32B header + 16B stubs) so a branch to stub+8 is covered.
        uint32_t plt_lo = 0xffffffffu, plt_hi = 0u;
        auto note_plt = [&](uint32_t a) {
            if (a == 0) return;
            a &= ~1u;
            if (a < plt_lo) plt_lo = a;
            if (a + 16u > plt_hi) plt_hi = a + 16u;
        };
        for (const auto& imp : img.imports) note_plt(imp.plt_addr);
        for (const auto& [pa, lt] : img.plt_local_bindings) { (void)lt; note_plt(pa); }
        if (plt_hi <= plt_lo) { plt_lo = 0; plt_hi = 0; }
        me_plan = plan_multi_entry(funcs, view, cs, plt_lo, plt_hi);
        if (plt_hi > plt_lo)
            std::fprintf(stderr,
                         "[arm32rc] multi-entry: excluding guest PLT "
                         "[0x%x,0x%x)\n",
                         plt_lo, plt_hi);
        std::fprintf(stderr,
                     "[arm32rc] multi-entry: %zu interior entry target(s) "
                     "across %zu function(s)\n",
                     me_plan.targets, me_plan.by_fn.size());
    }
    size_t me_emitted = 0;
    // MACAPK_ARM32RC_FRAME_JUMP: the targets a frame-keeping `b` may name,
    // fixed before any function is translated (the workers only read it).
    frame_jump_targets().clear();
    if (frame_jump_enabled()) {
        uint32_t fj_plt_lo = 0xffffffffu, fj_plt_hi = 0u;
        auto fj_note = [&](uint32_t a) {
            if (a == 0) return;
            a &= ~1u;
            if (a < fj_plt_lo) fj_plt_lo = a;
            if (a + 16u > fj_plt_hi) fj_plt_hi = a + 16u;
        };
        for (const auto& imp : img.imports) fj_note(imp.plt_addr);
        for (const auto& [pa, lt] : img.plt_local_bindings) { (void)lt; fj_note(pa); }
        auto in_plt = [&](uint32_t a) {
            return fj_plt_hi > fj_plt_lo && a + 4u >= fj_plt_lo && a < fj_plt_hi;
        };
        for (const DiscoveredFunction& fn : funcs)
            if (fn.disposition == Disposition::Translate && fn.size != 0 &&
                !in_plt(fn.vaddr & ~1u))
                frame_jump_targets().insert(fn.vaddr & ~1u);
        for (const auto& [f, ts] : me_plan.by_fn) {
            (void)f;
            for (uint32_t t : ts)
                if (!in_plt(t & ~1u)) frame_jump_targets().insert(t & ~1u);
        }
    }
    ptimer.mark("multi-entry-plan");

    FunctionTranslator ft(img, view, cs, opts, &st);
    JniThunkEmitter jni(link, gb_sym);

    auto plan_for = [&me_plan](const DiscoveredFunction& fn)
        -> const std::vector<uint32_t>* {
        if (me_plan.by_fn.empty()) return nullptr;
        auto mit = me_plan.by_fn.find(fn.vaddr & ~1u);
        return mit != me_plan.by_fn.end() ? &mit->second : nullptr;
    };

    // MACAPK_FIRSTLAUNCH_RC_THREADS: recompile every Translate body up front,
    // on worker threads, into a per-function slot. FunctionTranslator::run is
    // a pure function of (fn, its multi-entry plan) plus read-only module
    // state; its only other effect is counting into RcStats, so each worker
    // counts into its own RcStats and the sums are added to `st` below. The
    // loop that follows then consumes the slots IN `funcs` ORDER and does
    // everything order-dependent (add_text, define, branch, the JNI thunks,
    // veneer-pool placement) exactly as the serial path does, so the linker
    // sees the same calls in the same order and writes the same bytes.
    // Peak memory grows by at most the module's recompiled code, which the
    // linker holds a copy of anyway.
    const unsigned nthr = rc_worker_threads();
    const bool pre_translated = nthr > 1 && funcs.size() >= 64;
    std::vector<FuncOut> pre;
    std::vector<uint8_t> pre_ok;
    if (pre_translated) {
        pre.resize(funcs.size());
        pre_ok.assign(funcs.size(), 0);
        std::vector<std::unique_ptr<CsPair>> handles;
        std::vector<std::unique_ptr<RcStats>> wstats;
        std::vector<std::unique_ptr<FunctionTranslator>> wft;
        for (unsigned w = 0; w < nthr; ++w) {
            wstats.push_back(std::make_unique<RcStats>());
            const CsPair* c = &cs;
            if (w > 0) {
                handles.push_back(std::make_unique<CsPair>());
                c = handles.back().get();
            }
            wft.push_back(std::make_unique<FunctionTranslator>(
                img, view, *c, opts, wstats.back().get()));
        }
        bool handles_ok = true;
        for (const auto& h : handles) handles_ok = handles_ok && h->ok();
        if (!handles_ok) {
            // Could not open a second capstone handle: translate serially
            // below instead, which is the pre-change path.
            pre.clear();
            pre_ok.clear();
        } else {
            parallel_for_items(funcs.size(), nthr, [&](unsigned w, size_t i) {
                const DiscoveredFunction& fn = funcs[i];
                if (fn.disposition != Disposition::Translate) return;
                pre_ok[i] = (wft[w]->run(fn, plan_for(fn), &pre[i]) && pre[i].ok)
                                ? 1 : 0;
            });
            for (const auto& ws : wstats) {
                // Every RcStats field FunctionTranslator::run touches (the
                // `st_->` sites in run()). Keep in sync with run().
                st.insns_total    += ws->insns_total;
                st.insns_skipdata += ws->insns_skipdata;
                st.pool_resync_targets += ws->pool_resync_targets;
                st.insns_core     += ws->insns_core;
                st.insns_mem      += ws->insns_mem;
                st.insns_simd     += ws->insns_simd;
                st.insns_stubbed  += ws->insns_stubbed;
                st.pc_write_calls += ws->pc_write_calls;
                for (const auto& [mn, c] : ws->stub_histogram)
                    st.stub_histogram[mn] += c;
            }
        }
    }
    const bool use_pre = pre_translated && !pre.empty();
    ptimer.mark("pre-translate");

    for (const DiscoveredFunction& fn : funcs) {
        std::vector<uint8_t> code;
        std::vector<rc::BufSink::ExternSite> sites;
        std::vector<BranchSite> branches;
        std::vector<std::pair<uint32_t, uint64_t>> entries;
        uint64_t direct_off = ~0ull;
        uint64_t body_off = ~0ull;
        std::vector<std::pair<uint32_t, uint64_t>> mids;
        std::vector<uint32_t> frame_sites;

        if (fn.disposition == Disposition::Translate) {
            FuncOut fo;
            bool ok_fn;
            if (use_pre) {
                const size_t idx = static_cast<size_t>(&fn - funcs.data());
                ok_fn = pre_ok[idx] != 0;
                fo = std::move(pre[idx]);
            } else {
                ok_fn = ft.run(fn, plan_for(fn), &fo) && fo.ok;
            }
            if (!ok_fn) {
                ++st.functions_failed;
                if (opts.strict) {
                    if (error) *error = "failed to translate " + fn.symbol;
                    return false;
                }
                continue;
            }
            if (fo.partial) ++st.functions_partial; else ++st.functions_ok;
            code = std::move(fo.code);
            sites = std::move(fo.sites);
            entries = std::move(fo.entries);
            direct_off = fo.direct_off;
            body_off = fo.body_off;
            mids = std::move(fo.mids);
            frame_sites = std::move(fo.frame_sites);
        } else {
            a64::A64Buf b;
            switch (fn.disposition) {
                case Disposition::OverrideShim:
                    if (fn.override_symbol.empty()) {
                        if (error)
                            *error = "OverrideShim with no override_symbol: " +
                                     fn.symbol;
                        return false;
                    }
                    emit_override_shim(b, fn, &branches);
                    ++st.functions_override_shim;
                    break;
                case Disposition::OverrideConst:
                    emit_override_const(b, fn);
                    ++st.functions_override_const;
                    break;
                case Disposition::Veneer:
                    emit_veneer(b, fn, opts.load_bias, &branches);
                    ++st.functions_veneer;
                    break;
                case Disposition::Translate:
                    break;   // unreachable; kept for -Wswitch
            }
            std::vector<a64::Fixup> overflow;
            if (!b.resolve(&overflow) || !overflow.empty()) {
                if (error) *error = "override body fixup failed: " + fn.symbol;
                return false;
            }
            code = b.bytes();
        }

        // A JNI entry point's recompiled body is exported under
        // __macapk_arm32_<name> and the AAPCS64 thunk takes the original
        // name, which is what dlsym and the JVM bind to. The LUT entry
        // (below) keys off THIS symbol, the body, so a guest-to-guest
        // indirect call reaches guest code directly instead of paying a
        // pointless round trip through the JVM-facing thunk.
        const std::string body_name =
            fn.is_jni_export ? ("__macapk_arm32_" + fn.symbol) : fn.symbol;

        const uint64_t off = link.add_text(code.data(), code.size(), 4);
        const uint32_t sym = link.define(body_name, Sec::Text, off, true);
        for (const BranchSite& bs : branches)
            link.branch(Sec::Text, off + bs.byte_off,
                        link.undefined(bs.symbol));
        vaddr_to_sym[fn.vaddr] = sym;
        if (direct_off != ~0ull) {
            char dn[64];
            std::snprintf(dn, sizeof(dn), "__macapk_dcall_%08x", fn.vaddr & ~1u);
            vaddr_to_dcall[fn.vaddr & ~1u] =
                link.define(dn, Sec::Text, off + direct_off, false);
        }
        if (frame_jump_enabled() && body_off != ~0ull) {
            char bn[64];
            std::snprintf(bn, sizeof(bn), "__macapk_body_%08x", fn.vaddr & ~1u);
            vaddr_to_fj[fn.vaddr & ~1u] =
                link.define(bn, Sec::Text, off + body_off, false);
            for (const auto& m : mids)
                fj_mids_pending.push_back({m.first & ~1u, off + m.second});
        }
        emitted.push_back({&fn, off, sym, std::move(sites), std::move(entries),
                           direct_off,
                           std::unordered_set<uint32_t>(frame_sites.begin(),
                                                        frame_sites.end())});

        if (fn.is_jni_export) {
            if (!jni.emit(fn, sym, error)) return false;
            ++st.jni_thunks;
        }
        maybe_reserve_pool();
    }
    ptimer.mark("emit-loop");
    if (emitted.empty()) {
        if (error) *error = "no functions translated";
        return false;
    }

    // ---- 1b. MACAPK_ARM32RC_MULTI_ENTRY: publish the alternate entries
    //
    // Deliberately a SEPARATE loop rather than folded into the one above:
    // vaddr_to_sym must be fully populated with real function starts
    // before any entry symbol is allowed to claim a vaddr, so that a
    // start always wins no matter what order discovery listed the
    // functions in. plan_multi_entry already excludes starts, so the
    // guard below should never fire; it is here because the cost of
    // being wrong is a call to a real function silently landing in the
    // middle of another one.
    //
    // The symbols are LOCAL (exported = false), like the panic
    // trampolines: nothing outside this module refers to them, and
    // link.branch resolves a local target the same way it resolves an
    // exported one.
    for (const auto& m : fj_mids_pending) {
        if (vaddr_to_fj.count(m.first)) continue;   // a function start wins
        char mn[64];
        std::snprintf(mn, sizeof(mn), "__macapk_mid_%08x", m.first);
        vaddr_to_fj[m.first] = link.define(mn, Sec::Text, m.second, false);
    }
    if (!emitted.empty() && multi_entry_enabled()) {
        for (const Emitted& e : emitted) {
            for (const auto& en : e.entries) {
                if (vaddr_to_sym.find(en.first) != vaddr_to_sym.end()) continue;
                char nm[64];
                std::snprintf(nm, sizeof(nm), "__macapk_entry_%08x", en.first);
                vaddr_to_sym[en.first] =
                    link.define(nm, Sec::Text, e.text_off + en.second, false);
                ++me_emitted;
            }
        }
        std::fprintf(stderr,
                     "[arm32rc] multi-entry: %zu alternate entry point(s) "
                     "published\n", me_emitted);
    }

    // ---- 2. resolve every cross-function site ----------------------
    //
    // A guest target is one of three things, in priority order: a
    // function in this module (branch straight to it), an import's PLT
    // stub (route to the shim symbol, which is what the LLVM path's
    // __macapk_target_<plt> alias did), or unknown (a panic stub, so the
    // failure is a logged diagnostic rather than a jump into hyperspace).
    std::unordered_map<uint32_t, std::string> plt_to_import;
    for (const auto& imp : img.imports)
        if (imp.plt_addr != 0 && !imp.name.empty())
            plt_to_import[imp.plt_addr] = imp.name;
    // v1.0.808: the MISSING third case. A `.so` built default-visibility +
    // PIC routes calls to its OWN exported functions through its OWN PLT, so
    // the branch target is a stub whose R_ARM_JUMP_SLOT names a LOCAL symbol.
    // That is neither "a function in this module" (the stub vaddr is not a
    // discovered function start) nor "an import" -- so it fell all the way to
    // the panic stub, which RETURNS 0 with no crash. ShatteredPD called
    // `gdx2d_get_pixel@plt` (0x268e0 -> local 0x9cbc) once per pixel and got 0
    // every time: a 100%-CPU spin doing wrong work, not a hang.
    //
    // elf_reader already patches the GOT slot's bytes, but that only helps a
    // caller that EXECUTES the guest `.plt`; we resolve statically and never
    // run those bytes. (Arm32Lifter's old `plt_local_redirect_` rewrite, fed
    // by the opt-in MACAPK_PLT_REDIRECT / MACAPK_PLT_REDIRECT_FREETYPE knobs,
    // was retired 2026-09-27: it was a per-name, FreeType-shaped policy that
    // Bionic does not have, arm32rc never consumed it for codegen, and in its
    // all-libraries form (MACAPK_PLT_REDIRECT=1) it only perturbed discovery,
    // e.g. +13,812 panic-stubbed call sites on OpenTTD libapplication.so at
    // the pre-retire tree. See docs/plt-redirect-knobs-retired_2026-09-27.md.)
    //
    // MACAPK_ARM32RC_PLT_LOCAL=0 disables, restoring the panic-stub behaviour.
    // Kept as an escape hatch because the LLVM path's equivalent redirect was
    // deliberately OFF by default for non-FreeType libs: enabling it there
    // unmasked a LIFTER mis-translation in libgdx.so's gdx2d stb decode
    // (ShatteredPD -> flat blue). That was the deleted LLVM emitter; a forced
    // ARM32 ShatteredPD run on arm32rc at v1.0.808 showed 0 pixmap errors
    // (docs/arm32-silent-zero-call-classes_2026-07-28.md). Treat any future
    // ShatteredPD image regression as "arm32rc mis-translates gdx2d, go fix
    // that" -- calling a no-op stub that returns 0 is not a correct baseline
    // to preserve.
    static const bool plt_local_on = [](){
        const char* e = std::getenv("MACAPK_ARM32RC_PLT_LOCAL");
        return !(e && *e == '0');
    }();
    std::unordered_map<uint32_t, uint32_t> plt_to_local;
    if (plt_local_on)
        for (const auto& [pa, lt] : img.plt_local_bindings)
            plt_to_local[pa & ~1u] = lt;
    size_t plt_local_resolved = 0, plt_local_unlifted = 0;
    size_t bl_live_sites = 0;
    // MACAPK_ARM32RC_PLT_RELOC_FIRST (default ON): a branch to a PLT stub goes
    // where the stub's R_ARM_JUMP_SLOT relocation says, even when discovery
    // also lifted the stub's own bytes as a function. On Android the stub is
    // `add ip,pc; add ip,ip; ldr pc,[ip,#c]!`, so `foo@plt` lands exactly
    // where the linker bound the GOT word -- bionic/linker/linker_relocate.cpp
    // process_relocation_impl, R_GENERIC_JUMP_SLOT: `*static_cast<ElfW(Addr)*>
    // (rel_target) = result;` with `result = sym_addr + get_addend_norel()`,
    // applied eagerly for every DT_JMPREL entry (soinfo::relocate). The old
    // order consulted `vaddr_to_sym` first, so a LIFTED stub won: an import
    // stub then loaded its never-bound GOT word (the lazy PLT[0] value; we
    // write no import JUMP_SLOT word) and dispatched into nothing -- the
    // malloc-returns-0 failure MULTI_ENTRY hit (plan_multi_entry's comment) --
    // and a local stub took an indirect-dispatch round trip instead of a
    // direct branch. The same rule governs the import LUT thunk below: the
    // PLT vaddr an import GLOB_DAT slot holds means that import, so a lifted
    // stub no longer suppresses (shadows) its thunk. A stub whose relocation
    // cannot be honoured (local target not lifted, import with no host
    // symbol) keeps today's resolution, for the direct call AND the thunk.
    // One difference from Android, deliberate: the device reads the GOT word
    // at CALL time, while this binds the call statically, so a guest that
    // later rewrites one of its own LOCAL .got.plt slots (PLT hooking,
    // xhook/bhook style) is not honoured at these sites. That is the policy
    // arm32rc already applies to every stub discovery did NOT lift; the old
    // lifted-stub path happened to honour it. =0 restores the old order.
    // docs/arm32-bionic-reloc-exact_2026-09-28.md.
    static const bool plt_reloc_first = [](){
        const char* e = std::getenv("MACAPK_ARM32RC_PLT_RELOC_FIRST");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    size_t plt_stub_sites_lifted = 0;   // sites whose target stub was ALSO lifted
    size_t plt_stub_sites_moved  = 0;   // of those, resolved by the relocation

    const uint32_t panic_sym =
        link.undefined("__macapk_panic_unresolved_helper");
    // guest target -> its panic trampoline symbol (one per distinct target)
    std::unordered_map<uint32_t, uint32_t> target_to_tramp;
    const uint32_t dispatch_sym = link.undefined("__macapk_indirect_call");
    const uint32_t capture_sym = link.undefined("__macapk_capture_entry");
    const uint32_t trace_store_sym = link.undefined("__macapk_trace_store");

    for (const Emitted& e : emitted) {
        for (const auto& s : e.sites) {
            const uint64_t word_off = e.text_off + s.insn_index * 4ull;
            if (s.guest_target == 0xfffffffbu) {
                link.branch(Sec::Text, word_off, trace_store_sym);
                continue;
            }
            if (s.guest_target == 0xfffffffcu) {
                link.branch(Sec::Text, word_off, capture_sym);
                continue;
            }
            if (s.guest_target == 0xfffffffdu) {
                // `bl __macapk_indirect_call` (the guest-vaddr dispatcher).
                link.branch(Sec::Text, word_off, dispatch_sym);
                continue;
            }
            if (s.guest_target == 0xfffffffeu) {
                // The prologue's guest-base adrp/ldr pair.
                Fixup f1;
                f1.kind = Fix::Adrp21; f1.sec = Sec::Text; f1.off = word_off;
                f1.target = Ref::symbol(gb_sym);
                link.add_fixup(f1);
                Fixup f2;
                f2.kind = Fix::LdrLo12; f2.sec = Sec::Text;
                f2.off = word_off + 4; f2.target = Ref::symbol(gb_sym);
                f2.scale = 8;
                link.add_fixup(f2);
                continue;
            }
            ++st.extern_call_sites;
            uint32_t target_sym;
            const uint32_t gt = s.guest_target & ~1u;
            bool have_local = false;
            auto lf = vaddr_to_sym.find(gt);
            const bool is_plt_stub = plt_to_local.count(gt) != 0
                                     || plt_to_import.count(gt) != 0;
            const bool stub_lifted = is_plt_stub && lf != vaddr_to_sym.end();
            if (stub_lifted) ++plt_stub_sites_lifted;
            // MACAPK_ARM32RC_PLT_RELOC_FIRST: a stub's relocation outranks a
            // body discovery lifted at the stub's own address.
            const bool reloc_first = plt_reloc_first && stub_lifted;
            if (lf != vaddr_to_sym.end() && !reloc_first) {
                target_sym = lf->second;
                have_local = true;
                // MACAPK_ARM32RC_BL_LIVE_REGS: a direct `bl` to a function
                // start enters without zeroing the callee's r4-r12 / r14.
                if (s.is_call) {
                    auto dc = vaddr_to_dcall.find(gt);
                    if (dc != vaddr_to_dcall.end()) {
                        target_sym = dc->second;
                        ++bl_live_sites;
                    }
                }
            } else if (auto pl = plt_to_local.find(gt);
                       pl != plt_to_local.end()) {
                // Intra-module PLT stub -> the local body its JUMP_SLOT binds.
                auto lf2 = vaddr_to_sym.find(pl->second & ~1u);
                if (lf2 != vaddr_to_sym.end()) {
                    target_sym = lf2->second;
                    have_local = true;
                    ++plt_local_resolved;
                    if (s.is_call) {
                        auto dc = vaddr_to_dcall.find(pl->second & ~1u);
                        if (dc != vaddr_to_dcall.end()) {
                            target_sym = dc->second;
                            ++bl_live_sites;
                        }
                    }
                } else {
                    // Intra-module, but discovery never lifted the TARGET.
                    // That is a discovery gap, not an unknown address, and it
                    // deserves its own count: the panic diagnostic below names
                    // the stub vaddr, which hides the real missing function.
                    ++plt_local_unlifted;
                }
            }
            if (have_local) {
                ++st.extern_targets_resolved;
            } else {
                auto pi = plt_to_import.find(gt);
                std::string host;
                if (pi != plt_to_import.end())
                    host = opts.resolve_import ? opts.resolve_import(pi->second)
                                               : ("__macapk_shim_" + pi->second);
                if (!host.empty()) {
                    target_sym = link.undefined(host);
                    ++st.extern_targets_resolved;
                } else if (reloc_first) {
                    // The relocation cannot be honoured statically (a local
                    // target discovery never lifted): keep the lifted stub,
                    // exactly what the old order linked.
                    target_sym = lf->second;
                    ++st.extern_targets_resolved;
                } else {
                    // Per-target trampoline: load the REAL unresolved guest
                    // address into w0, then tail-call the panic helper.
                    //
                    // Branching straight to the helper (what this did before)
                    // makes its only parameter -- the unresolved address --
                    // receive whatever the guest happened to leave in r0,
                    // because guest r0 IS x0 under our ABI. Every
                    // "unresolved external call to ARM address 0x..." line on
                    // the arm32rc path was therefore printing a `this`
                    // pointer, which reads as a corrupted-pointer bug and sent
                    // this investigation down a wrong path for an hour. The
                    // LLVM lifter always had these trampolines; arm32rc did
                    // not, so its version of the same diagnostic was silently
                    // false rather than merely absent.
                    auto tr = target_to_tramp.find(s.guest_target & ~1u);
                    if (tr == target_to_tramp.end()) {
                        a64::A64Buf t;
                        t.mov_imm(false, X(HostGpr::X0),
                                  s.guest_target & ~1u);
                        t.put(a64::b(0));
                        const std::vector<uint8_t> tb = t.bytes();
                        const uint64_t toff =
                            link.add_text(tb.data(), tb.size(), 4);
                        link.branch(Sec::Text,
                                    toff + tb.size() - 4, panic_sym);
                        char nm[64];
                        std::snprintf(nm, sizeof(nm),
                                      "__macapk_target_%08x",
                                      s.guest_target & ~1u);
                        const uint32_t tsym =
                            link.define(nm, Sec::Text, toff, false);
                        tr = target_to_tramp
                                 .emplace(s.guest_target & ~1u, tsym).first;
                    }
                    target_sym = tr->second;
                    ++st.extern_targets_stubbed;
                    // MACAPK_ARM32RC_TRACE_STUBS=1: name the guest targets we
                    // could not resolve. Without this the runtime diagnostic is
                    // WORSE than nothing: the panic helper's only parameter is
                    // the unresolved address, but we branch to it directly, so
                    // it actually receives whatever the guest left in r0 (the
                    // `this` pointer) and prints THAT as "unresolved external
                    // call to ARM address 0x...". Every such line in a log is
                    // a red herring. Dumping the real targets here is the only
                    // trustworthy view until that trampoline is fixed.
                    static const bool trace_stubs =
                        std::getenv("MACAPK_ARM32RC_TRACE_STUBS") != nullptr;
                    static int shown = 0;
                    if (trace_stubs && shown < 400) {
                        std::fprintf(stderr,
                            "[arm32rc] STUBBED call -> guest 0x%08x "
                            "(from fn 0x%08x)\n",
                            s.guest_target & ~1u, e.fn->vaddr);
                        ++shown;
                    }
                }
            }
            if (reloc_first && target_sym != lf->second) ++plt_stub_sites_moved;
            if (!s.is_call && e.frame_sites.count(s.insn_index)) {
                // MACAPK_ARM32RC_FRAME_JUMP: this `b` was emitted WITHOUT the
                // epilogue, so it must land on a body that shares our frame.
                auto fj = vaddr_to_fj.find(gt);
                if (fj != vaddr_to_fj.end()) {
                    link.branch(Sec::Text, word_off, fj->second);
                    ++fj_bound;
                    continue;
                }
                // No body symbol after all: pop the frame here and continue
                // the way the old code did (`mov w4, w27 ; epilogue ; b`).
                a64::A64Buf t;
                t.put(a64::mov_reg(false, X(HostGpr::X4), X(HostGpr::X27)));
                DriverSink::emit_epilogue(t);
                t.put(a64::b(0));
                const std::vector<uint8_t> tb = t.bytes();
                const uint64_t toff = link.add_text(tb.data(), tb.size(), 4);
                link.branch(Sec::Text, toff + tb.size() - 4, target_sym);
                char tn[64];
                std::snprintf(tn, sizeof(tn), "__macapk_fjpop_%llx",
                              static_cast<unsigned long long>(toff));
                link.branch(Sec::Text, word_off,
                            link.define(tn, Sec::Text, toff, false));
                ++fj_thunked;
                continue;
            }
            link.branch(Sec::Text, word_off, target_sym);
        }
        // Per-target trampolines (above) are small (2 instructions
        // each) but a module with a very large number of distinct
        // unresolved targets can still accumulate real bytes here, so
        // keep the same cadence check going through this phase.
        maybe_reserve_pool();
    }
    // v1.0.808: report the intra-module PLT resolution separately from the
    // generic resolved/stubbed tallies. `unlifted` is the interesting number:
    // it means the stub IS intra-module but discovery never produced a body
    // for its target, i.e. a discovery gap wearing a linker-resolution mask.
    if (extend_falloff_enabled())
        std::fprintf(stderr,
                     "[arm32rc] extent repair: %zu extent(s) ended in straight-"
                     "line code with no function after them and were extended "
                     "to the next start, +%zu bytes (MACAPK_ARM32RC_EXTEND_FALLOFF)\n",
                     extend_falloff_count().load(), extend_falloff_bytes().load());
    if (frame_jump_enabled())
        std::fprintf(stderr,
                     "[arm32rc] frame jumps: %zu branch(es) between translated "
                     "extents keep the frame and the registers, %zu fell back "
                     "to a frame-pop thunk (MACAPK_ARM32RC_FRAME_JUMP)\n",
                     fj_bound, fj_thunked);
    if (bl_live_regs_enabled())
        std::fprintf(stderr,
                     "[arm32rc] direct-call entries: %zu function(s) have a "
                     "non-zeroing entry, %zu direct bl site(s) bound to one "
                     "(MACAPK_ARM32RC_BL_LIVE_REGS)\n",
                     vaddr_to_dcall.size(), bl_live_sites);
    if (plt_local_resolved || plt_local_unlifted) {
        std::fprintf(stderr,
                     "[arm32rc] intra-module PLT: %zu call site(s) bound to "
                     "local bodies, %zu target(s) not lifted\n",
                     plt_local_resolved, plt_local_unlifted);
    }
    // MACAPK_ARM32RC_PLT_RELOC_FIRST accounting, printed in BOTH arms so an
    // A/B reads off how many sites the order decides (absent == 0).
    if (plt_stub_sites_lifted)
        std::fprintf(stderr,
                 "[arm32rc] PLT reloc-first %s: %zu call site(s) reach a lifted "
                 "PLT stub, %zu resolved through the relocation\n",
                 plt_reloc_first ? "on" : "off",
                 plt_stub_sites_lifted, plt_stub_sites_moved);

    // ---- 3. the module constructor ---------------------------------
    {
        a64::A64Buf c;
        std::vector<std::pair<uint64_t, Ref>> adrp_pairs;   // word off, target
        std::vector<std::pair<uint64_t, uint32_t>> calls;   // word off, sym

        // 32 bytes: the frame record plus x19/x20. x19 holds the
        // thread stack across every __macapk_invoke_safe call and is
        // callee-saved (AAPCS64 Table 2), so it MUST be preserved for
        // dyld's initializer loop.
        c.put(a64::stp(true, X(HostGpr::X29), X(HostGpr::X30),
                       a64::kSP, -32, a64::Index::PreIndex));
        c.put(a64::mov_sp(true, X(HostGpr::X29), a64::kSP));
        c.put(a64::stp(true, X(HostGpr::X19), X(HostGpr::X20), a64::kSP, 16));

        auto call = [&](const char* name) {
            calls.push_back({c.size_words() * 4ull, link.undefined(name)});
            c.put(a64::bl(0));
        };
        auto adrp_add = [&](unsigned rd, Ref target) {
            adrp_pairs.push_back({c.size_words() * 4ull, target});
            c.put(a64::adrp(rd, 0));
            c.put(a64::add_imm(true, rd, rd, 0));
        };

        // guest base: g = __macapk_get_guest_base(); *macapk_rc_guest_base = g
        call("__macapk_get_guest_base");
        adrp_add(X(HostGpr::X9), Ref::symbol(gb_sym));
        c.put(a64::str(true, X(HostGpr::X0), X(HostGpr::X9), 0));

        // register every loadable segment's bytes
        const uint32_t reg_seg = link.undefined("__macapk_register_segment");
        for (const auto& seg : img.loadable) {
            if (seg.bytes.empty()) continue;
            const uint64_t blob =
                link.add_const(seg.bytes.data(), seg.bytes.size(), 16);
            c.mov_imm(false, X(HostGpr::X0), seg.vaddr + opts.load_bias);
            adrp_add(X(HostGpr::X1), Ref::local(Sec::Const, blob));
            c.mov_imm(false, X(HostGpr::X2), seg.bytes.size());
            c.mov_imm(false, X(HostGpr::X3), seg.bytes.size());
            calls.push_back({c.size_words() * 4ull, reg_seg});
            c.put(a64::bl(0));
        }

        // register every translated function with the in-image LUT
        const uint32_t reg_fn = link.undefined("__macapk_register_function");
        for (const Emitted& e : emitted) {
            c.mov_imm(false, X(HostGpr::X0), e.fn->vaddr + opts.load_bias);
            adrp_add(X(HostGpr::X1), Ref::symbol(e.sym));
            calls.push_back({c.size_words() * 4ull, reg_fn});
            c.put(a64::bl(0));
        }

        // Register every IMPORT's PLT trampoline by its PLT vaddr.
        //
        // Guest code reaches an import two ways. A direct `bl <plt>` is
        // resolved at link time by the extern-site loop above. But the
        // compiler also emits
        //     ldr rX, [rY]    ; rX = plt_vaddr, written into the GOT by
        //                     ;   our R_ARM_GLOB_DAT / R_ARM_ABS32 pass
        //     blx rX
        // and that goes through the runtime LUT. Without these entries the
        // lookup returns null and the dispatcher logs "indirect call to
        // in-image vaddr 0x..." for every malloc and every glXxx dispatched
        // through the GOT. Measured on HCR: the two misses inside
        // Cocos2dxActivity_nativeSetPaths were plt vaddrs 0x2084 and
        // 0x13d2. The LLVM path registers these; this one did not.
        //
        // Each gets a one-instruction thunk, `b <host import symbol>`,
        // which forwards x0-x4 unchanged and is therefore ABI-identical to
        // the LLVM path's __macapk_target_<plt> trampoline.
        {
            size_t n_plt = 0, n_plt_shadowed = 0, n_plt_over_stub = 0;
            for (const auto& imp : img.imports) {
                if (imp.plt_addr == 0 || imp.name.empty()) continue;
                // A stub discovery also lifted used to SHADOW its import's
                // thunk. Under MACAPK_ARM32RC_PLT_RELOC_FIRST the thunk is
                // registered anyway, AFTER the translated functions, and
                // __macapk_register_function overwrites (`g_in_image_funcs
                // [vaddr] = host_fn`), so the PLT vaddr an import GLOB_DAT slot
                // holds dispatches to the import, as its relocation says.
                const bool shadowed =
                    vaddr_to_sym.count(imp.plt_addr & ~1u) != 0;
                if (shadowed) {
                    ++n_plt_shadowed;
                    if (!plt_reloc_first) continue;
                }
                std::string host = opts.resolve_import
                                       ? opts.resolve_import(imp.name)
                                       : ("__macapk_shim_" + imp.name);
                // The relocation cannot be honoured (no host symbol for the
                // import): keep the lifted stub, exactly as the call-site loop
                // above does for the same stub, so one guest address never has
                // two answers (a direct branch to the stub vs a panic thunk
                // registered over it).
                if (shadowed && host.empty()) continue;
                if (shadowed) ++n_plt_over_stub;
                const uint32_t tsym = host.empty()
                                          ? panic_sym : link.undefined(host);
                const uint32_t word = a64::b(0);
                const uint64_t toff = link.add_text(&word, 4, 4);
                link.branch(Sec::Text, toff, tsym);
                char nm[64];
                std::snprintf(nm, sizeof(nm), "__macapk_plt_%08x",
                              imp.plt_addr);
                const uint32_t psym =
                    link.define(nm, Sec::Text, toff, false);
                c.mov_imm(false, X(HostGpr::X0), imp.plt_addr + opts.load_bias);
                adrp_add(X(HostGpr::X1), Ref::symbol(psym));
                calls.push_back({c.size_words() * 4ull, reg_fn});
                c.put(a64::bl(0));
                ++n_plt;
            }
            st.plt_thunks = n_plt;
            if (n_plt_shadowed) {
                if (plt_reloc_first)
                    std::fprintf(stderr,
                                 "[arm32rc] PLT reloc-first on: %zu import LUT "
                                 "thunk(s) at a lifted stub vaddr, %zu "
                                 "registered over the stub, %zu kept the stub "
                                 "(no host symbol)\n",
                                 n_plt_shadowed, n_plt_over_stub,
                                 n_plt_shadowed - n_plt_over_stub);
                else
                    std::fprintf(stderr,
                                 "[arm32rc] PLT reloc-first off: %zu import LUT "
                                 "thunk(s) at a lifted stub vaddr left "
                                 "shadowed\n", n_plt_shadowed);
            }
        }

        // ---- ARM32 data-import runtime link (MACAPK_ARM32_DATA_IMPORT_LINK).
        //
        // Bionic resolves a data relocation against an undefined symbol at
        // load time (linker_relocate.cpp android14-release :97-128
        // lookup_symbol -> soinfo_do_lookup over the lookup list; :326-341
        // ABS32 = S + A, GLOB_DAT = S), and every such relocation runs in
        // link_image BEFORE any constructor (linker.cpp :1694-1800, :3353).
        // We cannot: the definer is another .so. So the image hands its
        // own site table to __macapk_arm32_link_image, which reads the
        // providers' .dynsym / hash tables out of the arena and writes S + A.
        // Emitted AFTER the segments and the LUT are registered (the linker
        // reads this image's own dynamic section from the arena and may point
        // a slot at a thunk registered just above) and BEFORE .init_array
        // (the first reader of those slots). Every image calls it when ON,
        // including one with zero sites: a PROVIDER must register.
        // docs/arm32-data-import-resolution-design_2026-09-27.md.
        if (opts.data_import_link) {
            // (b) host-function fallback thunks: one `b <host>` per distinct
            // STT_FUNC data import that resolve_import would answer from the
            // Shim or RuntimeNs tier -- a name we deliberately provide as an
            // Android system-library function. Registered in the LUT under
            // key + B, key = the name's first slot vaddr: a data slot is never
            // a function entry and bias bands do not overlap, so the key is
            // unique process-wide. The runtime writes key + B into a slot
            // only if the importer's DT_NEEDED closure reaches a system
            // library first (Bionic's BFS order), so `sibling` bind mode and
            // an APK provider both leave it unused.
            std::unordered_map<std::string, uint32_t> fallback_key_of;
            std::vector<std::string> host_only_worklist;
            size_t n_dimp = 0;
            for (const DataImportSite& s : img.data_imports) {
                const ImportedSymbol& imp = img.imports[s.import_index];
                if (imp.type != STT_FUNC) continue;
                if (fallback_key_of.count(imp.name)) continue;
                std::string host;
                const RcOptions::ImportTier tier =
                    opts.import_tier ? opts.import_tier(imp.name, &host)
                                     : RcOptions::ImportTier::None;
                if ((tier != RcOptions::ImportTier::Shim &&
                     tier != RcOptions::ImportTier::RuntimeNs) || host.empty()) {
                    fallback_key_of[imp.name] = 0;
                    if (tier == RcOptions::ImportTier::HostDlsym)
                        host_only_worklist.push_back(imp.name);
                    continue;
                }
                const uint32_t word = a64::b(0);
                const uint64_t toff = link.add_text(&word, 4, 4);
                link.branch(Sec::Text, toff, link.undefined(host));
                char nm[64];
                std::snprintf(nm, sizeof(nm), "__macapk_dimp_%08x", s.slot);
                const uint32_t dsym = link.define(nm, Sec::Text, toff, false);
                c.mov_imm(false, X(HostGpr::X0), s.slot + opts.load_bias);
                adrp_add(X(HostGpr::X1), Ref::symbol(dsym));
                calls.push_back({c.size_words() * 4ull, reg_fn});
                c.put(a64::bl(0));
                fallback_key_of[imp.name] = s.slot;
                ++n_dimp;
            }
            // (a) the descriptor. Little-endian u32s, 4-aligned, no
            // pointers, so it needs no rebase fixup:
            //   header (40 B): magic "MDI1", version 1, dynamic_vaddr,
            //     basename_off, seg_lo, seg_hi (unbiased PT_LOAD extent),
            //     n_imports, imports_off, strtab_off, strtab_size
            //   entry (20 B): slot, name_off, addend (Bionic A), fallback_key,
            //     r_type, sym_type, sym_bind, flags (u8 each)
            // The runtime parser (bionic_shim_arm32_link.cpp) validates magic,
            // version and every offset against `size`.
            std::vector<uint8_t> strtab;
            std::unordered_map<std::string, uint32_t> str_off;
            auto intern = [&](const std::string& s) -> uint32_t {
                auto it = str_off.find(s);
                if (it != str_off.end()) return it->second;
                const uint32_t o = static_cast<uint32_t>(strtab.size());
                strtab.insert(strtab.end(), s.begin(), s.end());
                strtab.push_back(0);
                str_off.emplace(s, o);
                return o;
            };
            const uint32_t basename_off = intern(opts.image_basename);
            uint32_t seg_lo = 0xffffffffu, seg_hi = 0;
            for (const auto& ph : img.phdrs) {
                if (ph.p_type != PT_LOAD) continue;
                if (ph.p_vaddr < seg_lo) seg_lo = ph.p_vaddr;
                if (ph.p_vaddr + ph.p_memsz > seg_hi) seg_hi = ph.p_vaddr + ph.p_memsz;
            }
            if (seg_lo > seg_hi) seg_lo = seg_hi = 0;
            std::vector<uint8_t> ents;
            auto put32 = [](std::vector<uint8_t>& v, uint32_t x) {
                v.push_back(static_cast<uint8_t>(x));
                v.push_back(static_cast<uint8_t>(x >> 8));
                v.push_back(static_cast<uint8_t>(x >> 16));
                v.push_back(static_cast<uint8_t>(x >> 24));
            };
            for (const DataImportSite& s : img.data_imports) {
                const ImportedSymbol& imp = img.imports[s.import_index];
                auto fk = fallback_key_of.find(imp.name);
                put32(ents, s.slot);
                put32(ents, intern(imp.name));
                put32(ents, s.addend);
                put32(ents, fk == fallback_key_of.end() ? 0u : fk->second);
                ents.push_back(s.r_type);
                ents.push_back(imp.type);
                ents.push_back(imp.bind);
                ents.push_back(0);   // flags: reserved
            }
            constexpr uint32_t kHdr = 40;
            const uint32_t imports_off = kHdr;
            const uint32_t strtab_off =
                imports_off + static_cast<uint32_t>(ents.size());
            std::vector<uint8_t> desc;
            put32(desc, 0x3149444du);   // "MDI1"
            put32(desc, 1u);
            put32(desc, img.dynamic_vaddr);
            put32(desc, basename_off);
            put32(desc, seg_lo);
            put32(desc, seg_hi);
            put32(desc, static_cast<uint32_t>(img.data_imports.size()));
            put32(desc, imports_off);
            put32(desc, strtab_off);
            put32(desc, static_cast<uint32_t>(strtab.size()));
            desc.insert(desc.end(), ents.begin(), ents.end());
            desc.insert(desc.end(), strtab.begin(), strtab.end());
            while (desc.size() % 4) desc.push_back(0);
            const uint64_t doff = link.add_const(desc.data(), desc.size(), 8);
            // (c) __macapk_arm32_link_image(desc, size, B)
            adrp_add(X(HostGpr::X0), Ref::local(Sec::Const, doff));
            c.mov_imm(false, X(HostGpr::X1), static_cast<uint32_t>(desc.size()));
            c.mov_imm(false, X(HostGpr::X2), opts.load_bias);
            call("__macapk_arm32_link_image");
            std::fprintf(stderr,
                "[arm32rc] data-import link: %zu site(s), %zu host-fn "
                "fallback thunk(s), descriptor %zu bytes\n",
                img.data_imports.size(), n_dimp, desc.size());
            for (size_t k = 0; k < host_only_worklist.size() && k < 24; ++k)
                std::fprintf(stderr,
                    "[arm32rc] NOTE data-import fnptr '%s' has no host-function "
                    "fallback (only the host namespace defines it: wrong ABI for "
                    "a guest call); it binds only if a library in the DT_NEEDED "
                    "closure defines it -- shim-parity work list otherwise\n",
                    host_only_worklist[k].c_str());
        }

        // run .init_array under the crash-isolating wrapper
        std::vector<uint32_t> runnable;
        std::vector<uint32_t> skipped;
        for (uint32_t v : img.init_funcs) {
            if (vaddr_to_sym.count(v & ~1u)) runnable.push_back(v & ~1u);
            else if (v != 0u && v != 0xffffffffu) skipped.push_back(v);
        }
        // A SKIPPED .init_array entry is a static constructor that never
        // runs, which leaves its object's vptr as zero-initialised .bss --
        // and the first virtual call on it then branches to 0. This filter
        // used to be silent; HCR reaches drawScene with CCDirector's vptr
        // reading 0x0, so account for it out loud.
        std::fprintf(stderr,
            "[arm32rc] .init_array: %zu entries, %zu runnable, %zu SKIPPED"
            " (untranslated)\n",
            img.init_funcs.size(), runnable.size(), skipped.size());
        for (size_t k = 0; k < skipped.size() && k < 12; ++k)
            std::fprintf(stderr, "[arm32rc]   SKIPPED init 0x%08x\n",
                         skipped[k]);
        if (!runnable.empty()) {
            call("__macapk_alloc_thread_stack");
            c.put(a64::mov_reg(false, X(HostGpr::X19), X(HostGpr::X0)));
            const uint32_t inv = link.undefined("__macapk_invoke_safe");
            for (uint32_t v : runnable) {
                adrp_add(X(HostGpr::X0), Ref::symbol(vaddr_to_sym[v]));
                c.put(a64::mov_reg(false, X(HostGpr::X1), a64::kZR));
                c.put(a64::mov_reg(false, X(HostGpr::X2), a64::kZR));
                c.put(a64::mov_reg(false, X(HostGpr::X3), a64::kZR));
                c.put(a64::mov_reg(false, X(HostGpr::X4), a64::kZR));
                c.put(a64::mov_reg(false, X(HostGpr::X5), X(HostGpr::X19)));
                calls.push_back({c.size_words() * 4ull, inv});
                c.put(a64::bl(0));
            }
        }

        c.put(a64::ldp(true, X(HostGpr::X19), X(HostGpr::X20), a64::kSP, 16));
        c.put(a64::ldp(true, X(HostGpr::X29), X(HostGpr::X30),
                       a64::kSP, 32, a64::Index::PostIndex));
        c.put(a64::ret());

        const std::vector<uint8_t> cb = c.bytes();
        const uint64_t coff = link.add_text(cb.data(), cb.size(), 4);
        const uint32_t csym = link.define("__macapk_install_segments",
                                          Sec::Text, coff, true);
        for (const auto& p : adrp_pairs)
            link.adrp_add_pair(Sec::Text, coff + p.first,
                               coff + p.first + 4, p.second);
        for (const auto& p : calls)
            link.branch(Sec::Text, coff + p.first, p.second);
        link.add_initializer(Ref::symbol(csym));
    }

    // ---- 3b. MACAPK_ARM32_EXPORT_ALIASES: every ELF name of a body ---
    //
    // Bionic binds an import to the FIRST dynsym entry whose NAME matches
    // (linker_soinfo.cpp gnu_lookup / elf_lookup: strcmp on the name, then
    // is_symbol_global_and_defined), at st_value + load_bias
    // (linker_soinfo.h resolve_symbol_address). Nothing in that lookup cares
    // that another name shares the st_value, so on a device a C1/C2 ctor
    // pair, a D1/D2 dtor pair, or two identical-code-folded functions are
    // simply two exports of one address. Discovery keeps ONE name per
    // address, so the loop above exported one of them; this adds the rest.
    //
    // Only a FUNCTION START qualifies (an `emitted` body's own vaddr), never
    // a MULTI_ENTRY alternate entry: those are tail/branch landing stubs, and
    // an export has to be enterable with nothing but r0-r3 + sp live.
    //
    // Placed HERE, after every undefined() the module makes (cross-function
    // sites, the PLT thunks, the constructor, the data-import thunks) and
    // before the final veneer pool, for two reasons:
    //  - define() on a name that is already a pending UNDEFINED upgrades it
    //    in place into a local definition, i.e. it would silently turn the
    //    module's own external reference into a self-reference. has_symbol()
    //    here sees every such name and the alias is skipped instead.
    //  - appending leaves every earlier body at its old offset, so the only
    //    .text change is new JNI alias thunks at the end.
    // `alias_exports` is empty with the gate off: nothing below runs and not
    // one output byte changes.
    if (!opts.alias_exports.empty()) {
        std::unordered_map<uint32_t, const Emitted*> start_of;
        start_of.reserve(emitted.size());
        for (const Emitted& e : emitted) start_of.emplace(e.fn->vaddr & ~1u, &e);
        st.alias_candidates = opts.alias_exports.size();
        for (const RcOptions::AliasExport& a : opts.alias_exports) {
            auto it = start_of.find(a.vaddr & ~1u);
            if (it == start_of.end()) {
                ++st.alias_no_entry;
                if (st.alias_no_entry_names.size() < 8)
                    st.alias_no_entry_names.push_back(a.name);
                continue;
            }
            const Emitted& e = *it->second;
            // The body's own export: fn.symbol (a plain body, or the JNI
            // thunk that took that name).
            if (a.name == e.fn->symbol) { ++st.alias_already; continue; }
            if (a.name.empty() || link.has_symbol(a.name)) {
                ++st.alias_taken;
                continue;
            }
            if (a.is_jni) {
                // A JNI-shaped alias is what the JVM binds by name, so it
                // needs the same AAPCS64 entry thunk the primary would get,
                // onto the same guest body (e.sym is always the guest-ABI
                // body: `__macapk_arm32_<name>` for a JNI primary).
                DiscoveredFunction jf = *e.fn;
                jf.symbol = a.name;
                jf.is_jni_export = true;
                if (!jni.emit(jf, e.sym, error)) return false;
                ++st.alias_jni_thunks;
                ++st.jni_thunks;
                maybe_reserve_pool();
                continue;
            }
            link.define(a.name, Sec::Text, e.text_off, true);
            ++st.alias_exported;
        }
        std::fprintf(stderr,
                     "[arm32rc] alias exports: %zu name(s) in, %zu exported "
                     "as an alias, %zu JNI alias thunk(s), %zu already the "
                     "body's name, %zu skipped (name taken), %zu with no "
                     "translated function start",
                     st.alias_candidates, st.alias_exported,
                     st.alias_jni_thunks, st.alias_already, st.alias_taken,
                     st.alias_no_entry);
        for (size_t k = 0; k < st.alias_no_entry_names.size(); ++k)
            std::fprintf(stderr, "%s%s", k ? ", " : " (",
                         st.alias_no_entry_names[k].c_str());
        std::fprintf(stderr, "%s\n",
                     st.alias_no_entry_names.empty()
                         ? ""
                         : (st.alias_no_entry > st.alias_no_entry_names.size()
                                ? ", ...)" : ")"));
    }

    // Unconditional final reservation: guarantees a pool covers the
    // tail regardless of how the periodic checks above landed (and,
    // for a module small enough to never have crossed the threshold at
    // all, guarantees at least one pool exists here rather than relying
    // on write()'s own fallback -- both are equally correct, but doing
    // it here keeps every pool visible in RcStats/verbose logging from
    // the producer's own side, not just the linker's).
    //
    // This MUST stay the last thing appended to __text: it is what makes
    // this pool the tail of __text, which is the precondition for
    // MachOLinker::write() to cut its unused capacity
    // (MACAPK_ARM32RC_TRIM_VENEER_TAIL; Flappy 1.15 MB -> ~0.1 MB). Text
    // appended after it is still linked correctly; the trim just declines
    // ("[macho_link] veneer tail NOT trimmed") and the 1 MiB stays.
    link.reserve_veneer_pool();
    ptimer.mark("sites-and-tables");

    // ---- 4. link ----------------------------------------------------
    const LinkResult lr = link.write(out_path);
    ptimer.mark("link-write");
    if (!lr.ok) {
        if (error) *error = "link failed: " + lr.error;
        return false;
    }
    st.text_bytes = lr.text_bytes;
    st.file_bytes = lr.file_bytes;
    st.veneer_tail_trimmed = lr.veneer_tail_trimmed;
    st.flat_symbols = lr.flat_symbols;
    return true;
}

std::string format_stats(const RcStats& s, size_t top_n) {
    char buf[512];
    std::string out;
    std::snprintf(buf, sizeof(buf),
        "[arm32rc] functions: %zu in, %zu full, %zu partial, %zu failed\n"
        "[arm32rc] dispositions: %zu override-shim, %zu override-const, "
        "%zu veneer, %zu JNI thunk(s)\n"
        "[arm32rc] insns: %zu total, %zu core, %zu mem, %zu simd, "
        "%zu STUBBED, %zu skipdata\n"
        "[arm32rc] call sites: %zu, resolved %zu, panic-stubbed %zu, "
        "%zu import PLT thunks, %zu pc-write calls\n"
        "[arm32rc] text %llu bytes, file %llu bytes\n",
        s.functions_in, s.functions_ok, s.functions_partial,
        s.functions_failed,
        s.functions_override_shim, s.functions_override_const,
        s.functions_veneer, s.jni_thunks,
        s.insns_total, s.insns_core, s.insns_mem,
        s.insns_simd, s.insns_stubbed, s.insns_skipdata,
        s.extern_call_sites, s.extern_targets_resolved,
        s.extern_targets_stubbed, s.plt_thunks, s.pc_write_calls,
        static_cast<unsigned long long>(s.text_bytes),
        static_cast<unsigned long long>(s.file_bytes));
    if (s.pool_resync_targets)
        std::fprintf(stderr,
            "[arm32rc] pool resync: %zu Thumb branch target(s) re-decoded past "
            "a literal pool (MACAPK_ARM32RC_POOL_RESYNC)\n",
            s.pool_resync_targets);
    out += buf;
    // Printed only when something was cut, so a MACAPK_ARM32RC_TRIM_VENEER_TAIL=0
    // run's stats are the pre-change text line for line.
    if (s.veneer_tail_trimmed) {
        std::snprintf(buf, sizeof(buf),
            "[arm32rc] veneer tail trimmed: %llu bytes\n",
            static_cast<unsigned long long>(s.veneer_tail_trimmed));
        out += buf;
    }
    if (!s.stub_histogram.empty()) {
        std::vector<std::pair<std::string, size_t>> v(s.stub_histogram.begin(),
                                                      s.stub_histogram.end());
        std::sort(v.begin(), v.end(),
                  [](const auto& a, const auto& b) {
                      return a.second != b.second ? a.second > b.second
                                                  : a.first < b.first;
                  });
        out += "[arm32rc] TOP UNHANDLED (the work list):\n";
        for (size_t i = 0; i < v.size() && i < top_n; ++i) {
            std::snprintf(buf, sizeof(buf), "[arm32rc]   %8zu  %s\n",
                          v[i].second, v[i].first.c_str());
            out += buf;
        }
    }
    return out;
}

}  // namespace macapk::translator::arm32rc
