// x18_scan_test.cpp -- the x18 scan (include/winapk/arm64_x18.h) on one instruction per case.
//
// The encodings are llvm-mc's for the text beside each; the answer is whether
// that text names x18 or w18 as a general register (v18, d18, q18 and an
// immediate 18 do not). Plain C++: runs on any host.

#include "winapk/arm64_x18.h"

#include <cstdio>

namespace {

struct Case {
    uint32_t word;
    int expect;
    const char* text;
};

const Case kCases[] = {
    {0xf800865e, 1, "str x30, [x18], #8"},
    {0xf85f8e5e, 1, "ldr x30, [x18, #-8]!"},
    {0xaa1203e0, 1, "mov x0, x18"},
    {0xaa0003f2, 1, "mov x18, x0"},
    {0x528000b2, 1, "mov w18, #5"},
    {0x91004240, 1, "add x0, x18, #16"},
    {0xd1711e41, 1, "sub x1, x18, #3143, lsl #12"},
    {0x8b120020, 1, "add x0, x1, x18"},
    {0x8b324020, 1, "add x0, x1, w18, uxtw"},
    {0x0a120020, 1, "and w0, w1, w18"},
    {0xaa1203e0, 1, "orr x0, xzr, x18"},
    {0xd3431a40, 1, "ubfx x0, x18, #3, #4"},
    {0x93d21420, 1, "extr x0, x1, x18, #5"},
    {0xa9400640, 1, "ldp x0, x1, [x18]"},
    {0xa94007f2, 1, "ldp x18, x1, [sp]"},
    {0xa9014be0, 1, "stp x0, x18, [sp, #16]"},
    {0x3dc00640, 1, "ldr q0, [x18, #16]"},
    {0xfc327841, 1, "str d1, [x2, x18, lsl #3]"},
    {0xb872c820, 1, "ldr w0, [x1, w18, sxtw]"},
    {0xc85f7e40, 1, "ldxr x0, [x18]"},
    {0xc8127c41, 1, "stxr w18, x1, [x2]"},
    {0xf8e00241, 1, "ldaddal x0, x1, [x18]"},
    {0xf8320041, 1, "ldadd x18, x1, [x2]"},
    {0x4c407240, 1, "ld1 {v0.16b}, [x18]"},
    {0x4cd27020, 1, "ld1 {v0.16b}, [x1], x18"},
    {0xb4000052, 1, "cbz x18, #8"},
    {0x37180092, 1, "tbnz w18, #3, #16"},
    {0xd61f0240, 1, "br x18"},
    {0xd63f0240, 1, "blr x18"},
    {0x9a920020, 1, "csel x0, x1, x18, eq"},
    {0xfa431a40, 1, "ccmp x18, #3, #0, ne"},
    {0xfa521000, 1, "ccmp x0, x18, #0, ne"},
    {0x9b024820, 1, "madd x0, x1, x2, x18"},
    {0x9ac20a40, 1, "udiv x0, x18, x2"},
    {0xdac00240, 1, "rbit x0, x18"},
    {0x9e620240, 1, "scvtf d0, x18"},
    {0x9e670240, 1, "fmov d0, x18"},
    {0x9e780012, 1, "fcvtzs x18, d0"},
    {0x9e660032, 1, "fmov x18, d1"},
    {0x4e040e40, 1, "dup v0.4s, w18"},
    {0x4e0c1e40, 1, "mov v0.s[1], w18"},
    {0x0e0c3c12, 1, "umov w18, v0.s[1]"},
    {0xd53bd052, 1, "mrs x18, tpidr_el0"},
    {0xd51bd052, 1, "msr tpidr_el0, x18"},
    {0x10000052, 1, "adr x18, #8"},
    {0xb0000012, 1, "adrp x18, #4096"},
    {0x10000040, 0, "adr x0, #8"},
    {0x8b020020, 0, "add x0, x1, x2"},
    {0x91004820, 0, "add x0, x1, #18"},
    {0xd2800240, 0, "mov x0, #18"},
    {0xf9404820, 0, "ldr x0, [x1, #144]"},
    {0xf8627820, 0, "ldr x0, [x1, x2, lsl #3]"},
    {0xf84123e0, 0, "ldr x0, [sp, #18]"},
    {0xf8012441, 0, "str x1, [x2], #18"},
    {0x3dc00012, 0, "ldr q18, [x0]"},
    {0xfd000452, 0, "str d18, [x2, #8]"},
    {0xad404c12, 0, "ldp q18, q19, [x0]"},
    {0x1e604272, 0, "fmov d18, d19"},
    {0x4e22d432, 0, "fadd v18.4s, v1.4s, v2.4s"},
    {0x4c407032, 0, "ld1 {v18.16b}, [x1]"},
    {0x58000800, 0, "ldr x0, 0x100"},
    {0xf9804800, 0, "prfm pldl1keep, [x0, #144]"},
    {0xf9bdfaf2, 0, "prfm pstl2keep, [x23, #31728]"},
    {0x6e0c6640, 0, "mov v0.s[1], v18.s[3]"},
    {0x9e620012, 0, "scvtf d18, x0"},
    {0x9e780240, 0, "fcvtzs x0, d18"},
    {0xfa521800, 0, "ccmp x0, #18, #0, ne"},
    {0xd95ee020, 0, "ldapur x0, [x1, #-18]"},
    {0x99024020, 0, "stlur w0, [x1, #36]"},
    {0x38bfc020, 0, "ldaprb w0, [x1]"},
    {0xb4000480, 0, "cbz x0, #144"},
    {0x37900080, 0, "tbnz w0, #18, #16"},
    {0x14000012, 0, "b #72"},
    {0x97ffffee, 0, "bl #-72"},
    {0xd65f03c0, 0, "ret"},
    {0xd503201f, 0, "nop"},
    {0xd4000241, 0, "svc #18"},
    {0xd5033bbf, 0, "dmb ish"},
    {0xd53bd040, 0, "mrs x0, tpidr_el0"},
    {0xd9325ea6, 0, "stg x6, [x21, #-3504]!"},
    {0xd503245f, 0, "bti c"},
    {0xd503233f, 0, "paciasp"},
    {0xf8e00041, 0, "ldaddal x0, x1, [x2]"},
    {0xf85eec20, 0, "ldr x0, [x1, #-18]!"},
};

// Does the instruction write x18 (not just read it)?
const Case kWriteCases[] = {
    {0xd37ef5d2, 1, "lsl x18, x14, #2"},
    {0xb8726972, 1, "ldr w18, [x11, x18]"},
    {0xf9400012, 1, "ldr x18, [x0]"},
    {0x8b020032, 1, "add x18, x1, x2"},
    {0xaa0003f2, 1, "mov x18, x0"},
    {0xd28000b2, 1, "movz x18, #5"},
    {0xf800865e, 1, "str x30, [x18], #8"},
    {0xf85f8e5e, 1, "ldr x30, [x18, #-8]!"},
    {0xa9810640, 1, "stp x0, x1, [x18, #16]!"},
    {0xa8c10e42, 1, "ldp x2, x3, [x18], #16"},
    {0xa94007f2, 1, "ldp x18, x1, [sp]"},
    {0xf9400240, 0, "ldr x0, [x18]"},
    {0xf9400640, 0, "ldr x0, [x18, #8]"},
    {0xf9000012, 0, "str x18, [x0]"},
    {0xa9094c12, 0, "stp x18, x19, [x0, #0x90]"},
    {0xaa1203e0, 0, "mov x0, x18"},
    {0x91002240, 0, "add x0, x18, #8"},
    {0xf100165f, 0, "cmp x18, #5"},
    {0x2c38764d, 0, "stnp s13, s29, [x18, #-0x40]"},
    {0xad000640, 0, "stp q0, q1, [x18]"},
    {0x3dc00640, 0, "ldr q0, [x18, #16]"},
    {0xb4000052, 0, "cbz x18, #8"},
    {0xd61f0240, 0, "br x18"},
    {0xfc727820, 0, "ldr d0, [x1, x18, lsl #3]"},
    {0xb8326820, 0, "str w0, [x1, x18]"},
    {0xfa431a40, 0, "ccmp x18, #3, #0, ne"},
    {0x9a920020, 0, "csel x0, x1, x18, eq"},
    {0x9e670240, 0, "fmov d0, x18"},
    {0x9e620240, 0, "scvtf d0, x18"},
    {0xd51bd052, 0, "msr tpidr_el0, x18"},
    {0xf9800240, 0, "prfm pldl1keep, [x18]"},
    {0xd53bd052, 1, "mrs x18, tpidr_el0"},
    {0x9e780012, 1, "fcvtzs x18, d0"},
    {0x0e0c3c12, 1, "umov w18, v0.s[1]"},
    {0x58000812, 1, "ldr x18, 0x100"},
    {0xf8e00032, 1, "ldaddal x0, x18, [x1]"},
    {0xc85f7c12, 1, "ldxr x18, [x0]"},
    {0xc8127c41, 1, "stxr w18, x1, [x2]"},
    {0xc85f7e40, 0, "ldxr x0, [x18]"},
    {0x4e040e40, 0, "dup v0.4s, w18"},
    {0x4e0c1e40, 0, "mov v0.s[1], w18"},
    {0x4c407240, 0, "ld1 {v0.16b}, [x18]"},
    {0x4cd27020, 0, "ld1 {v0.16b}, [x1], x18"},
};

}  // namespace

static void check_restore(int& failed) {
    const uint32_t restore = 0xa9494c12;      // ldp x18, x19, [x0, #0x90]
    const uint32_t not_restore = 0xa9405012;  // ldp x18, x20, [x0]
    const bool ok = winapk::arm64::is_context_restore(restore) && !winapk::arm64::is_context_restore(not_restore) &&
                    winapk::arm64::writes_x18(restore);
    std::printf("  [%s] ldp x18, x19 is a context restore, ldp x18, x20 is not\n", ok ? "PASS" : "FAIL");
    if (!ok) ++failed;
}

int main() {
    int failed = 0;
    for (const Case& c : kCases) {
        const bool got = winapk::arm64::uses_x18(c.word);
        const bool ok = got == (c.expect != 0);
        std::printf("  [%s] %-34s %s\n", ok ? "PASS" : "FAIL", c.text, got ? "uses x18" : "no x18");
        if (!ok) ++failed;
    }
    for (const Case& c : kWriteCases) {
        const bool got = winapk::arm64::writes_x18(c.word);
        const bool ok = got == (c.expect != 0);
        std::printf("  [%s] writes: %-34s %s\n", ok ? "PASS" : "FAIL", c.text, got ? "writes x18" : "no write");
        if (!ok) ++failed;
    }
    // A context restore (libunwind, longjmp) is not a scratch use.
    check_restore(failed);
    // The range scan: counts, first offset, ignores a short tail.
    const uint8_t code[] = {0x1f, 0x20, 0x03, 0xd5,  // nop
                            0x5e, 0x86, 0x00, 0xf8,  // str x30, [x18], #8
                            0x1f, 0x20, 0x03, 0xd5,  // nop
                            0xe0, 0x03, 0x12, 0xaa,  // mov x0, x18
                            0x5e, 0x86};             // a partial word
    const winapk::arm64::X18Scan s = winapk::arm64::scan_x18(code, sizeof code);
    const bool range_ok = s.uses == 2 && s.first_offset == 4;
    std::printf("  [%s] scan_x18 over a range: %zu use(s), first at %zu\n", range_ok ? "PASS" : "FAIL", s.uses,
                s.first_offset);
    if (!range_ok) ++failed;
    std::printf("%d failed\n", failed);
    return failed ? 1 : 0;
}
