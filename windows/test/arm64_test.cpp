// arm64_test.cpp -- winapk-arm64-test: arm64 guest code running natively, and its bridge to
// the Windows host.
//
//   winapk-arm64-test <dir with libwinapk_arm64_*.so>
//
//   * test/guest/arm64_isa.c is compiled into this program and, three times
//     (-O2, -O3, -O3 with the ARMv8.1 atomics), into guest libraries; every
//     function must return the same both ways.
//   * arm64_bridge_guest.c: libc calls into the Bionic shim, variadic calls,
//     host -> guest callbacks, stack arguments and floating point,
//     constructors, the stack protector, setjmp/longjmp.
//   * arm64_flow_guest.c, arm64_tls_guest.c, arm64_eh_guest.cpp,
//     arm64_svc_guest.c, arm64_signal_guest.c: control flow, ELF TLS through
//     TLSDESC, C++ exceptions, raw system calls, signals; and dlclose.
//
// Exit code: the number of failed checks.

#include "winapk/arm64_guest.h"
#include "winapk/guest_abi.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <algorithm>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <windows.h>

#define ISA_EXPORT static __attribute__((noinline, used))
#include "arm64_isa.c"

namespace {

int g_failures = 0;

void check(const char* what, bool ok, const std::string& detail = "") {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ", detail.c_str());
    if (!ok) ++g_failures;
}

// The directory of the test libraries (argv[1]).
std::string g_lib_dir;

std::string hex(uint64_t v) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "0x%" PRIx64, v);
    return buf;
}

void* sym(winapk::GuestLinker& linker, void* lib, const char* name) {
    std::string error;
    void* p = linker.lookup(lib, name, nullptr, &error);
    if (!p) check(name, false, error);
    return p;
}

const uint64_t kInputs[][3] = {
    {0, 0, 0},
    {1, 2, 3},
    {0xffffffffffffffffull, 1, 0x8000000000000000ull},
    {12345678901234ull, 987654321ull, 42},
    {0x7fffffff, 0x80000000, 0xffffffff},
    {0xdeadbeefcafebabeull, 0x0123456789abcdefull, 0xfedcba9876543210ull},
    {17, 0xffffffffffffffffull, 5},
    {1000000, 3, 7},
};

using IsaFn = u64 (*)(u64, u64, u64);
using GuestIsaFn = u64(WINAPK_GUEST_ABI*)(u64, u64, u64);

void run_isa(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open", lib != nullptr, error);
    if (!lib) return;
    struct Case {
        const char* name;
        IsaFn native;
    };
#define ISA_CASE(name) {#name, &name},
    static const Case kCases[] = {ISA_TESTS(ISA_CASE)};
#undef ISA_CASE
    for (const Case& c : kCases) {
        void* fn = sym(linker, lib, c.name);
        if (!fn) continue;
        std::string first_mismatch;
        int mismatches = 0;
        for (const auto& in : kInputs) {
            const uint64_t want = c.native(in[0], in[1], in[2]);
            const uint64_t got = winapk::arm64::call(fn, in, 3);
            if (got != want) {
                if (!mismatches)
                    first_mismatch = "(" + hex(in[0]) + ", " + hex(in[1]) + ", " + hex(in[2]) + "): native " +
                                     hex(want) + ", arm64 " + hex(got);
                ++mismatches;
            }
        }
        check(c.name, mismatches == 0, first_mismatch);
    }
    // The same code entered the two other ways the host reaches guest code.
    auto* fn = reinterpret_cast<GuestIsaFn>(sym(linker, lib, "isa_loop"));
    if (fn) {
        const uint64_t want = isa_loop(5, 6, 300);
        check("a direct call into arm64 code", fn(5, 6, 300) == want);
        auto* entry = reinterpret_cast<GuestIsaFn>(winapk::arm64::host_callable(reinterpret_cast<void*>(fn)));
        check("the same call through host_callable()", entry != fn && entry(5, 6, 300) == want);
    }
}

WINAPK_GUEST_ABI uint64_t missing_function() { return 0; }

// The host function arm64_call_host calls.
WINAPK_GUEST_ABI int64_t host_many_args(int64_t a, int64_t b, int64_t c, int64_t d, int64_t e, int64_t f, int64_t g,
                                        int64_t h, int64_t i, int64_t j, double x, double y) {
    return a + 2 * b + 3 * c + 4 * d + 5 * e + 6 * f + 7 * g + 8 * h + 9 * i + 10 * j +
           static_cast<int64_t>(x * 100 + y * 1000);
}

void run_bridge(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open (APS2 + RELR relocations, stack protector)", lib != nullptr, error);
    if (!lib) return;
    auto fn = [&](const char* name) { return sym(linker, lib, name); };
    // For functions returning int: AAPCS64 leaves the upper half of x0 undefined.
    auto call0 = [&](const char* name) -> int {
        void* f = fn(name);
        return f ? static_cast<int>(winapk::arm64::call(f)) : -999999;
    };

    check("constructor ran", call0("arm64_ctor_ran") == 0x5150);
    {
        const uint64_t seed = 3;
        int want = 0;
        for (int i = 0; i < 100; ++i) want += static_cast<signed char>(seed + i);
        check("stack-protected function (guard in TPIDR_EL0[5])",
              static_cast<int>(winapk::arm64::call(fn("arm64_stack_guard"), &seed, 1)) == want);
    }
    check("libc through the shim (malloc, memcpy, strlen, strcmp, errno)", call0("arm64_libc") == 36);

    char buf[256] = {};
    uint64_t args[2] = {reinterpret_cast<uint64_t>(buf), sizeof buf};
    winapk::arm64::call(fn("arm64_snprintf"), args, 2);
    const char* want_printf = "1 two 3 4.50 5 6 7f 8 9.3 10 eleven 12";
    check("variadic snprintf with stack arguments", std::strcmp(buf, want_printf) == 0, buf);
    std::memset(buf, 0, sizeof buf);
    winapk::arm64::call(fn("arm64_vsnprintf"), args, 2);
    const char* want_v = "va|1|2.500|3|4|5|6|7|8|9.5|end";
    check("the guest's AAPCS64 va_list given to vsnprintf", std::strcmp(buf, want_v) == 0, buf);
    check("__android_log_print", call0("arm64_log_print") > 0);

    check("guest comparator called by the host's qsort", call0("arm64_qsort") == -3912);
    check("guest comparator called by the host's bsearch", call0("arm64_bsearch") == 325);
    check("guest -> host -> guest -> host (qsort comparator calling snprintf)", call0("arm64_nested") == 10004);
    int64_t want_threads = 0;
    for (int64_t n = 100; n < 104; ++n) want_threads += n * (n + 1) * (2 * n + 1) / 6;
    const int64_t threads = static_cast<int64_t>(winapk::arm64::call(fn("arm64_threads")));
    check("pthread_create with a guest start routine, four threads", threads == want_threads,
          std::to_string(threads));

    using ManyFn = int64_t(WINAPK_GUEST_ABI*)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
                                               int64_t, int64_t, double, double);
    if (auto* many = reinterpret_cast<ManyFn>(fn("arm64_many_args"))) {
        check("host -> guest: 10 integer + 2 double arguments", many(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0.5, 0.25) == 685);
    }
    using JniSizedFn = int64_t(WINAPK_GUEST_ABI*)(
        int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
        int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t,
        int64_t, int64_t, int64_t, const int64_t*, const int64_t*);
    if (auto* jni_sized = reinterpret_cast<JniSizedFn>(fn("arm64_jni_sized"))) {
        // Each argument i is i + 1, so the sum is the sum of (i + 1)^2 for i < 29.
        const int64_t a27 = 28, a28 = 29;
        check("host -> guest: 29 integer arguments, 21 on the stack (Flutter's nativeSetViewportMetrics)",
              jni_sized(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                        27, &a27, &a28) == 8555);
    }
    using ScaleFn = double(WINAPK_GUEST_ABI*)(double, float, int);
    if (auto* scale = reinterpret_cast<ScaleFn>(fn("arm64_scale"))) {
        check("host -> guest: double and float arguments, double result", scale(2.5, 4.0f, 3) == 30.0);
    }
    const uint64_t host_fn = reinterpret_cast<uint64_t>(&host_many_args);
    check("guest -> host: 10 integer + 2 double arguments",
          static_cast<int64_t>(winapk::arm64::call(fn("arm64_call_host"), &host_fn, 1)) == 685);
    using ParseFn = double(WINAPK_GUEST_ABI*)(const char*);
    if (auto* parse = reinterpret_cast<ParseFn>(fn("arm64_parse"))) {
        check("double result from the host (strtod) back to the host", parse("1.25") == 2.5);
    }

    check("setjmp/longjmp across three guest frames", call0("arm64_setjmp") == 421239,
          std::to_string(call0("arm64_setjmp")));

    // fts with arm64's struct stat (arm64_abi.cpp), and a guest comparator.
    {
        const std::string& dir = g_lib_dir;
        const char* name = "libwinapk_arm64_bridge.so";
        const uint64_t a[2] = {reinterpret_cast<uint64_t>(dir.c_str()), reinterpret_cast<uint64_t>(name)};
        check("fts_read fills arm64's struct stat", static_cast<int>(winapk::arm64::call(fn("arm64_fts"), a, 2)) == 1,
              dir);
    }
    check("cleanup handlers: pop(1) and pthread_exit run guest routines", call0("arm64_cleanup") == 213,
          std::to_string(call0("arm64_cleanup")));

    const int exits_before = call0("arm64_exit_count");
    check("__cxa_atexit with a guest handler", call0("arm64_register_atexit") == 0);
    winapk::mini_bionic_run_atexit();
    check("the handler ran from the host's atexit list", call0("arm64_exit_count") == exits_before + 7);
}

// ---- arm64_flow_guest.c -------------------------------------------------------------

// What an arm64 CPU's FPToFixed gives for an already rounded value: NaN is 0,
// out of range saturates (C leaves both undefined, and x86 differs).
int64_t arm_sat_s(double r, int bits) {
    if (std::isnan(r)) return 0;
    const double hi = std::ldexp(1.0, bits - 1);
    if (r >= hi) return static_cast<int64_t>((uint64_t{1} << (bits - 1)) - 1);
    if (r < -hi) return -static_cast<int64_t>(uint64_t{1} << (bits - 2)) * 2;
    return static_cast<int64_t>(r);
}
uint64_t arm_sat_u(double r, int bits) {
    if (std::isnan(r) || r <= 0) return 0;
    if (r >= std::ldexp(1.0, bits)) return bits == 64 ? ~uint64_t{0} : (uint64_t{1} << bits) - 1;
    return static_cast<uint64_t>(r);
}
// Round to nearest, ties away from zero, exactly (some C runtimes' round()
// is not: Wine's msvcrt rounds 0.49999999999999994 to 1).
template <typename F> F round_away(F v) {
    const F t = std::trunc(v);
    return std::fabs(v - t) >= F(0.5) ? t + std::copysign(F(1), v) : t;
}
uint32_t float_bits(float f) {
    uint32_t u;
    std::memcpy(&u, &f, 4);
    return u;
}

// The results of flow_fp_convert for one input, in its order.
std::vector<int64_t> want_convert(double d) {
    const float f = static_cast<float>(d);
    std::vector<int64_t> w;
    w.push_back(arm_sat_s(std::trunc(d), 64));
    w.push_back(static_cast<int32_t>(arm_sat_s(std::trunc(d), 32)));
    w.push_back(static_cast<int64_t>(arm_sat_u(std::trunc(d), 64)));
    w.push_back(static_cast<int64_t>(arm_sat_u(std::trunc(f), 32)));
    w.push_back(arm_sat_s(std::nearbyint(d), 64));
    w.push_back(arm_sat_s(round_away(d), 64));
    w.push_back(static_cast<int32_t>(arm_sat_s(std::floor(f), 32)));
    w.push_back(static_cast<int32_t>(arm_sat_s(std::ceil(f), 32)));
    for (double r : {std::nearbyint(d), round_away(d), std::floor(d), std::ceil(d), std::trunc(d), std::nearbyint(d)}) {
        w.push_back(arm_sat_s(std::trunc(r * 4), 64));  // (int64_t)(r * 4) is FCVTZS
    }
    return w;
}

std::vector<uint32_t> want_vector(const float* in) {
    std::vector<uint32_t> w;
    for (int i = 0; i < 4; ++i) w.push_back(static_cast<uint32_t>(arm_sat_s(std::trunc(in[i]), 32)));
    for (int i = 0; i < 4; ++i) w.push_back(static_cast<uint32_t>(arm_sat_u(std::trunc(in[i]), 32)));
    for (int i = 0; i < 4; ++i) w.push_back(static_cast<uint32_t>(arm_sat_s(std::nearbyint(in[i]), 32)));
    for (int i = 0; i < 4; ++i) w.push_back(float_bits(round_away(in[i])));
    for (int i = 0; i < 4; ++i) w.push_back(float_bits(std::nearbyint(in[i])));
    return w;
}

uint64_t deep_reference(uint64_t n) {
    uint64_t r = 1;
    for (uint64_t i = 1; i <= n; ++i) r = r * 3 + i;
    return r;
}

void run_flow(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open", lib != nullptr, error);
    if (!lib) return;
    auto call = [&](const char* name, std::vector<uint64_t> args = {}) -> uint64_t {
        void* f = sym(linker, lib, name);
        return f ? winapk::arm64::call(f, args.data(), args.size()) : ~uint64_t{0};
    };
    auto expect = [&](const char* what, uint64_t got, uint64_t want) {
        check(what, got == want, got == want ? "" : "got " + hex(got) + ", want " + hex(want));
    };

    // acc goes 1, 12, 88, 619, 4335, 30346, 212422 down the six frames; x19
    // and the volatile local come back as they were at the save.
    expect("a return with RET into an ancestor frame (an unwinder's jump)", call("flow_unwind_ret"), 212422010006);
    expect("the same with BR", call("flow_unwind_br"), 212422010006);
    {
        uint64_t local = 100, acc = 0;
        for (uint64_t r = 0; r < 1000; ++r) {
            local = local * 3 + r;
            acc = acc * 31 + local;
        }
        expect("coroutines switching at one call site on their own stacks", call("flow_coroutines", {1000}), acc);
    }
    expect("a variadic guest function: 8 integers and 4 doubles through va_arg", call("flow_variadic"), 1634474);
    expect("a function entered at its start and at a symbol in its middle", call("flow_entries", {1}), 12112011);
    expect("the second entry from the host", call("flow_multi_mid", {1}), 11);
    expect("RETAA from a call", call("flow_call_retaa", {5}), 12);
    expect("RETAA from the host's call", call("flow_retaa", {7}), 8);
    expect("a return through BR x16 to the host's call", call("flow_br_lr", {5}), 7);
    {
        uint64_t acc = 1;
        for (uint64_t i = 0; i < 50; ++i) acc = ((i & 1) ? acc * 5 : acc + 3) + (i + 2) + (i + 1);
        expect("tail calls through a function pointer, BR and RETAA returns in a loop", call("flow_calls", {50}), acc);
    }
    // 20,000 frames of 32 bytes: 640 KB of guest stack, which an Android
    // thread's 1 MB holds. The host's stack must hold it too.
    const uint64_t depth = 20000;
    expect("recursion 20,000 deep", call("flow_deep", {depth}), deep_reference(depth));
    expect("recursion 20,000 deep through a function pointer", call("flow_deep_indirect", {depth}),
           deep_reference(depth));
    expect("both on a guest thread (1 MB stack)", call("flow_deep_thread", {depth}), deep_reference(depth) * 4);

    {
        const double nan = std::nan("");
        const double in[] = {nan, INFINITY, -INFINITY, 3e9, -3e9, 1e20, -1e20, 2.5, -2.5, -0.5, 3.5,
                             4294967295.5, -1.0, 0.49999999999999994, 9.2233720368547758e18};
        const int n = sizeof in / sizeof in[0];
        int64_t out[n * 14] = {};
        const uint64_t args[3] = {reinterpret_cast<uint64_t>(in), static_cast<uint64_t>(n),
                                  reinterpret_cast<uint64_t>(out)};
        void* f = sym(linker, lib, "flow_fp_convert");
        const uint64_t k = f ? winapk::arm64::call(f, args, 3) : 0;
        std::string first;
        int bad = 0;
        for (int i = 0; i < n; ++i) {
            const std::vector<int64_t> want = want_convert(in[i]);
            for (size_t j = 0; j < want.size(); ++j) {
                if (out[i * 14 + j] == want[j]) continue;
                if (!bad++)
                    first = "input " + std::to_string(in[i]) + ", result " + std::to_string(j) + ": got " +
                            hex(static_cast<uint64_t>(out[i * 14 + j])) + ", want " +
                            hex(static_cast<uint64_t>(want[j]));
            }
        }
        check("FCVT{ZS,ZU,NS,AS,MS,PS} saturation and FRINT{N,A,M,P,Z,X} (scalar)", k == uint64_t(n) * 14 && !bad,
              first);
    }
    {
        const float sets[2][4] = {{std::nanf(""), INFINITY, -INFINITY, 5e9f}, {2.5f, -2.5f, -0.5f, 3.5f}};
        std::string first;
        int bad = 0;
        for (const auto& in : sets) {
            uint32_t out[20] = {};
            const uint64_t args[2] = {reinterpret_cast<uint64_t>(in), reinterpret_cast<uint64_t>(out)};
            void* f = sym(linker, lib, "flow_fp_vector");
            if (f) winapk::arm64::call(f, args, 2);
            const std::vector<uint32_t> want = want_vector(in);
            for (size_t j = 0; j < want.size(); ++j) {
                if (out[j] != want[j] && !bad++) {
                    first = "result " + std::to_string(j) + ": got " + hex(out[j]) + ", want " + hex(want[j]);
                }
            }
        }
        check("FCVTZS, FCVTZU, FCVTNS, FRINTA, FRINTN (vector) on NaN, infinities, halves", !bad, first);
    }
    {
        // b * a for lane 3 is 1 + 2^-11 + 2^-24: fused, 2 - that is 1 - 2^-11 - 2^-24.
        const float a[4] = {0.0f, INFINITY, 2.0f, 1.000244140625f};
        const float b[4] = {INFINITY, 0.0f, 0.75f, 1.000244140625f};
        const uint32_t want[] = {0x40000000, 0x40000000, 0x3f000000, 0x3f7fdfff,  // FRECPS
                                 0x3fc00000, 0x3fc00000, 0x3f400000, 0x3f7ff000,  // FRSQRTS
                                 0x40000000, 0x3fc00000,                          // scalar, 0 * inf
                                 0x00000000, 0x80000000, 0x3f800000, 0x3f800000};  // FMAX FMIN FMAXNM FMINNM
        uint32_t out[14] = {};
        const uint64_t args[3] = {reinterpret_cast<uint64_t>(a), reinterpret_cast<uint64_t>(b),
                                  reinterpret_cast<uint64_t>(out)};
        void* f = sym(linker, lib, "flow_fp_steps");
        if (f) winapk::arm64::call(f, args, 3);
        std::string first;
        for (size_t j = 0; j < 14 && first.empty(); ++j) {
            if (out[j] != want[j]) {
                first = "result " + std::to_string(j) + ": got " + hex(out[j]) + ", want " + hex(want[j]);
            }
        }
        check("FRECPS/FRSQRTS (fused; 0 times infinity), FMAX/FMIN on zeros, FMAXNM/FMINNM on NaN", first.empty(),
              first);
    }

    expect("LDAXR/STLXR counter: a library's code and a mapped copy on two threads", call("flow_mixed", {200000}),
           400000);
    expect("store-buffer litmus test, STLR then LDAR: never both 0", call("flow_litmus", {100000}), 0);
}

// arm64_eh_guest.cpp: C++ exceptions thrown through guest frames (built
// only where an NDK was found; skipped otherwise).
void run_eh(winapk::GuestLinker& linker, const std::filesystem::path& dir) {
    std::printf("libwinapk_arm64_eh.so\n");
    std::error_code ec;
    if (!std::filesystem::exists(dir / "libwinapk_arm64_eh.so", ec)) {
        std::printf("  (not built: no Android NDK)\n");
        return;
    }
    std::string error;
    void* lib = linker.open("libwinapk_arm64_eh.so", winapk::GuestLinker::kRtldNow, &error);
    check("open (the NDK's libc++ and unwinder inside)", lib != nullptr, error);
    if (!lib) return;
    auto call = [&](const char* name, std::vector<uint64_t> args = {}) -> uint64_t {
        void* f = sym(linker, lib, name);
        return f ? winapk::arm64::call(f, args.data(), args.size()) : ~uint64_t{0};
    };
    auto expect = [&](const char* what, uint64_t got, uint64_t want) {
        check(what, got == want, got == want ? "" : "got " + std::to_string(got) + ", want " + std::to_string(want));
    };
    expect("a throw 40 frames down, their destructors run", call("eh_deep"), 780);
    expect("through a virtual call and a function pointer, rethrown", call("eh_mixed"), 2003313535);
    expect("1,000 throws in a loop", call("eh_loop", {1000}), 3500);
}

// Raw Linux system calls: patched to a trap the shim answers (arm64_guest.h, patch_svc).
void run_svc(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open (its svc instructions made answerable)", lib != nullptr, error);
    if (!lib) return;
    void* getpid_fn = sym(linker, lib, "arm64_svc_getpid");
    void* gettid_fn = sym(linker, lib, "arm64_svc_gettid");
    void* unknown_fn = sym(linker, lib, "arm64_svc_unknown");
    void* write_fn = sym(linker, lib, "arm64_svc_write");
    void* loop_fn = sym(linker, lib, "arm64_svc_loop");
    if (!getpid_fn || !gettid_fn || !unknown_fn || !write_fn || !loop_fn) return;
    check("getpid (172)", static_cast<uint32_t>(winapk::arm64::call(getpid_fn)) == GetCurrentProcessId());
    check("gettid (178)", static_cast<uint32_t>(winapk::arm64::call(gettid_fn)) == GetCurrentThreadId());
    check("an unknown number answers -ENOSYS", static_cast<int64_t>(winapk::arm64::call(unknown_fn)) == -38);
    const char msg[] = "svc write\n";
    const uint64_t wargs[3] = {1, reinterpret_cast<uint64_t>(msg), sizeof msg - 1};
    check("write (64) to stdout", winapk::arm64::call(write_fn, wargs, 3) == sizeof msg - 1);
    const uint64_t n = 100;
    check("registers survive the calls of a loop", winapk::arm64::call(loop_fn, &n, 1) == 3 * (100 * 99 / 2) + 100);
    uint64_t other = 0;
    std::thread t([&] { other = winapk::arm64::call(gettid_fn); });
    t.join();
    check("another thread's gettid is its own", static_cast<uint32_t>(other) != GetCurrentThreadId());
}

// Signals delivered to arm64 guest handlers (arm64_signal_guest.c).
void run_signals(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open", lib != nullptr, error);
    if (!lib) return;
    auto call0 = [&](const char* name) -> int64_t {
        void* f = sym(linker, lib, name);
        return f ? static_cast<int64_t>(static_cast<int32_t>(winapk::arm64::call(f))) : -999999;  // an int: the upper half of x0 is undefined
    };
    check("a SIGSEGV handler redirects pc: the thread resumes in the function it chose", call0("sig_redirect") == 77);
    check("... it saw si_code SEGV_MAPERR (1) and si_addr 16", call0("sig_code") == 1 && call0("sig_addr") == 16);
    check("... a ucontext with pc, sp, the fault address and an fpsimd_context", call0("sig_ucontext_ok") == 1);
    check("a handler that makes the page accessible: the load is retried", call0("sig_fix_mapping") == 1);
    check("a handler sets x0 and skips the load: the function returns it", call0("sig_regs") == 1234);
    check("brk #1 is SIGTRAP, TRAP_BRKPT (1)", call0("sig_trap") == 1 && call0("sig_code") == 1);
    check("raise() runs the handler before returning, with siginfo and ucontext", call0("sig_raise") == 1);
    check("... as a tkill (SI_TKILL -6)", call0("sig_raise_code") == -6);
    check("... with its signal blocked while it runs", call0("sig_mask_blocked") == 1);
    check("SA_ONSTACK: the handler runs on the alternate stack, uc_stack says so", call0("sig_altstack") == 1);
    check("siglongjmp out of a SIGSEGV handler, three times (the mask comes back)", call0("sig_longjmp") == 1);
    check("pthread_kill: the handler runs on the target thread", call0("sig_thread") == 1);
}

void run_unload(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open", lib != nullptr, error);
    if (!lib) return;
    void* fn = sym(linker, lib, "isa_arith");
    if (!fn) return;
    const uint64_t in[3] = {1, 2, 3};
    check("runs", winapk::arm64::call(fn, in, 3) == isa_arith(1, 2, 3));
    check("dlclose", linker.close(lib, &error) == 0, error);
}

// ---- ELF TLS through TLSDESC (test/guest/arm64_tls_guest.c) ---------------------------

// tls_mix as the guest computes it, from the thread's TLS values.
long tls_mix_reference(long a, long b, long c, long d, long counter, long zero, long global) {
    double f = (double)a * 1.5;
    long r = a + counter;
    r += b * zero;
    r += c - global;
    return r + d + (long)(f * (double)counter);
}

void run_tls(winapk::GuestLinker& linker, const char* file) {
    std::printf("%s\n", file);
    std::string error;
    void* lib = linker.open(file, winapk::GuestLinker::kRtldNow, &error);
    check("open (R_AARCH64_TLSDESC relocated)", lib != nullptr, error);
    if (!lib) return;
    void* bump = sym(linker, lib, "tls_bump");
    void* addr = sym(linker, lib, "tls_addr");
    void* mix = sym(linker, lib, "tls_mix");
    if (!bump || !addr || !mix) return;
    // t_counter starts at 5 (.tdata), t_zero at 0 (.tbss).
    check("the first access reads the initial image", winapk::arm64::call(bump) == 6 + 2);
    check("the value is kept for the thread", winapk::arm64::call(bump) == 7 + 4);
    const uint64_t in[4] = {3, 5, 11, 13};
    const uint64_t got = winapk::arm64::call(mix, in, 4);
    check("values live across a TLSDESC call survive it", static_cast<long>(got) == tls_mix_reference(3, 5, 11, 13, 7, 4, 7),
          hex(got));
    uint64_t other = 0, other_addr = 0;
    std::thread t([&] {
        other = winapk::arm64::call(bump);
        other_addr = winapk::arm64::call(addr);
    });
    t.join();
    check("another thread gets its own block", other == 6 + 2, hex(other));
    check("at another address", other_addr != winapk::arm64::call(addr));
    check("dlclose", linker.close(lib, &error) == 0, error);
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);  // a crash must not take the lines before it along
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-arm64-test <dir with libwinapk_arm64_*.so>\n");
        return 2;
    }
    g_lib_dir = argv[1];
    winapk::LinkerConfig config;
    config.search_paths.push_back(std::filesystem::u8path(argv[1]));
    config.host = winapk::mini_bionic_resolve;
    // What the shim lacks and the runtime stubs (host/guest_libs.cpp): the
    // NDK's static libc++ refers to a few such (openlog, swprintf, ...),
    // never called here.
    config.load.unresolved = [](const char*) { return reinterpret_cast<void*>(&missing_function); };
    winapk::GuestLinker linker(config);

    run_isa(linker, "libwinapk_arm64_isa_O2.so");
    run_isa(linker, "libwinapk_arm64_isa_O3.so");
    run_isa(linker, "libwinapk_arm64_isa_lse.so");
    run_bridge(linker, "libwinapk_arm64_bridge.so");
    run_flow(linker, "libwinapk_arm64_flow.so");
    run_tls(linker, "libwinapk_arm64_tls.so");
    run_eh(linker, std::filesystem::u8path(argv[1]));
    run_unload(linker, "libwinapk_arm64_unload.so");
    run_svc(linker, "libwinapk_arm64_svc.so");
    run_signals(linker, "libwinapk_arm64_signal.so");

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
