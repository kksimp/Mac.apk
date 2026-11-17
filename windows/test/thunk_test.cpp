// thunk_test.cpp -- exercises the runtime ABI thunks (windows/src/abi_thunk.cpp)
// without a JVM: a native-method thunk forwarding a long argument list to a
// guest-ABI target, a variadic entry stub feeding a va_list decoder, and the
// tagged zero stub that stands in for unimplemented host natives.
//
// Built for Windows ARM64; it is standalone so the
// machine-code emitter can be validated before the JNIEnv table is built on
// top of it.

#include "winapk/abi_thunk.h"
#include "aapcs_call.h"
#include "winapk/guest_abi.h"

#include <cstdint>
#include <cstdio>
#include <string>

using namespace winapk;

namespace {

int g_failures = 0;

void check(const char* what, bool ok, long long got, long long want) {
    std::printf("  [%s] %s: got %lld%s\n", ok ? "PASS" : "FAIL", what, got,
                ok ? "" : (" want " + std::to_string(want)).c_str());
    if (!ok) ++g_failures;
}

// Stands in for a guest JNIEnv*: env_xlate maps the host pointer the caller
// passes to the guest view. A real bridge looks the pair up; here it just adds
// a marker the target can check.
constexpr uintptr_t kHostEnv = 0x5000;
constexpr uintptr_t kGuestEnv = 0x9000;

void* env_xlate(void* host) {
    return reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(host) == kHostEnv ? kGuestEnv : 0);
}

// The guest-ABI native method the thunk forwards to. The argument list mixes
// integer and SSE classes and overflows both into the stack, and includes a
// narrow (jshort-like) argument to check sign extension.
WINAPK_GUEST_ABI long long native_target(void* env, void* obj, int a, double b, long long c,
                                         short narrow, int e, float f, int g, double h, int i,
                                         double j, int k, double l) {
    if (reinterpret_cast<uintptr_t>(env) != kGuestEnv) return -1;
    if (reinterpret_cast<uintptr_t>(obj) != 0x7777) return -2;
    long long int_sum = a + c + narrow + e + g + i + k;      // 1 + 100 + (-5) + 7 + 11 + 13 + 17 = 144
    long long flt_sum = (long long)(b + f + h + j + l);      // 2 + 4 + 8 + 16 + 32 = 62
    return int_sum * 1000 + flt_sum;                         // 144062
}

using NativeFn = long long(*)(void*, void*, int, double, long long, short, int, float, int, double,
                              int, double, int, double);

void test_native_thunk() {
    const ArgKind args[] = {
        ArgKind::Word,   // env
        ArgKind::Word,   // obj
        ArgKind::Word,   // a (jint)
        ArgKind::Double, // b
        ArgKind::Word,   // c (jlong)
        ArgKind::Short,  // narrow
        ArgKind::Word,   // e
        ArgKind::Float,  // f
        ArgKind::Word,   // g
        ArgKind::Double, // h
        ArgKind::Word,   // i
        ArgKind::Double, // j
        ArgKind::Word,   // k
        ArgKind::Double, // l
    };
    auto thunk = reinterpret_cast<NativeFn>(
        make_jni_native_thunk(reinterpret_cast<void*>(&native_target), args,
                              sizeof(args) / sizeof(args[0]), reinterpret_cast<void*>(&env_xlate)));
    if (!thunk) { check("thunk allocated", false, 0, 1); return; }
    long long r = thunk(reinterpret_cast<void*>(kHostEnv), reinterpret_cast<void*>(0x7777), 1, 2.0,
                        100, (short)-5, 7, 4.0f, 11, 8.0, 13, 16.0, 17, 32.0);
    check("native thunk: env xlate + mixed/overflow args + narrow", r == 144062, r, 144062);
}

// A C-variadic JNI-style function: handler(env, obj, mid, va). The entry stub
// has 3 named integer arguments and hands the rest as a SysVVaList.
WINAPK_GUEST_ABI long long sum_handler(void* env, void* obj, void* mid, SysVVaList* va) {
    // The three named arguments must arrive intact alongside the va_list.
    if (reinterpret_cast<uintptr_t>(env) != 0xE || reinterpret_cast<uintptr_t>(obj) != 0xB ||
        reinterpret_cast<uintptr_t>(mid) != 0x10) {
        return -1;
    }
    // 4 ints then 2 doubles then 1 int: crosses from the register save area
    // into the overflow area for the integer class.
    long long s = 0;
    for (int n = 0; n < 4; ++n) s += (long long)(int)sysv_va_next_gp(va);
    s += (long long)(sysv_va_next_fp(va) + sysv_va_next_fp(va));
    s += (long long)(int)sysv_va_next_gp(va);
    return s;
}

using VariadicFn = long long(WINAPK_GUEST_ABI*)(void*, void*, void*, ...);

void test_variadic_entry() {
    auto fn = reinterpret_cast<VariadicFn>(
        make_sysv_variadic_entry(3, reinterpret_cast<void*>(&sum_handler)));
    if (!fn) { check("variadic entry allocated", false, 0, 1); return; }
    // x0-x2 named, then 10 20 30 40 in x3-x6, 99 in x7, the doubles in v0 and v1
    const uint64_t x[8] = {0xE, 0xB, 0x10, 10, 20, 30, 40, 99};
    const double d[8] = {1.5, 2.5, 0, 0, 0, 0, 0, 0};
    uint64_t xr;
    double dr;
    aapcs_call(reinterpret_cast<void*>(fn), x, d, &xr, &dr);
    long long r = static_cast<long long>(xr);
    // 10+20+30+40 = 100, +4 (1.5+2.5), +99 = 203
    check("variadic entry: gp/fp va_list decode", r == 203, r, 203);
}

// The handler a tagged stub calls: records what it was given.
const void* g_seen_first = nullptr;
const void* g_seen_tag = nullptr;
void tag_handler(const void* first, const void* tag) {
    g_seen_first = first;
    g_seen_tag = tag;
}

void test_tagged_zero_stub() {
    static const char kTag[] = "pkg/Cls.method(I)D";
    void* stub = make_tagged_zero_stub(reinterpret_cast<void*>(&tag_handler), kTag);
    if (!stub) { check("tagged stub allocated", false, 0, 1); return; }
    // Called as a Windows ARM64 function of several return types and with
    // more arguments than the handler takes, as HotSpot would.
    auto as_long = reinterpret_cast<long long (*)(void*, void*, int, double, int, int)>(stub);
    auto as_double = reinterpret_cast<double (*)(void*, void*)>(stub);
    long long r = as_long(reinterpret_cast<void*>(0xE1), reinterpret_cast<void*>(0xC1), 7, 2.5, 8, 9);
    check("tagged stub returns 0 in rax", r == 0, r, 0);
    check("tagged stub passes the first argument", g_seen_first == reinterpret_cast<void*>(0xE1),
          reinterpret_cast<long long>(g_seen_first), 0xE1);
    check("tagged stub passes its tag", g_seen_tag == kTag, 0, 0);
    double d = as_double(nullptr, nullptr);
    check("tagged stub returns 0.0 in xmm0", d == 0.0, static_cast<long long>(d), 0);
}

}  // namespace

int main() {
    std::printf("Win.apk ABI thunk test\n");
    test_native_thunk();
    test_variadic_entry();
    test_tagged_zero_stub();
    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
