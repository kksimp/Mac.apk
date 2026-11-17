// jni_variadic_test.cpp -- drives the variadic Call<T>Method bridge
// (windows/src/jni_variadic.cpp) against mock host ...A slots, with no real
// HotSpot. It checks that a guest's variadic call -- both the C `...` form and
// the va_list form -- is read against the method signature and repacked into
// the jvalue[] the A-form receives, across the call kinds (virtual, static,
// nonvirtual, NewObject) and the argument classes (int, long, object, float,
// double, with C promotion).

#include "winapk/abi_thunk.h"
#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

#include "aapcs_call.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include <jni.h>

namespace {

int g_failures = 0;
void check(const char* what, bool ok, const std::string& detail = "") {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ",
                detail.c_str());
    if (!ok) ++g_failures;
}

JNIEnv_ g_mock_env;
JavaVM_ g_mock_vm;
JNINativeInterface_ g_mock_table;
JNIInvokeInterface_ g_mock_invoke;

// What the last mock ...A slot saw.
struct Seen {
    bool env_ok = true;
    const char* slot = "";
    jvalue argv[8];
    int nargs = 0;
    void* fixed1 = nullptr;  // obj / clazz
    void* fixed2 = nullptr;  // clazz (nonvirtual) or mid
    jmethodID mid = nullptr;
} g_seen;

void record(JNIEnv* env, const char* slot, const jvalue* a, int n, void* f1, void* f2,
            jmethodID mid) {
    g_seen.slot = slot;
    g_seen.env_ok = (env == &g_mock_env);
    g_seen.nargs = n;
    for (int i = 0; i < n && i < 8; ++i) g_seen.argv[i] = a[i];
    g_seen.fixed1 = f1;
    g_seen.fixed2 = f2;
    g_seen.mid = mid;
}

// The signatures the provider hands back, keyed by a fake jmethodID.
jmethodID MID_IFJDI = reinterpret_cast<jmethodID>(0x101);
jmethodID MID_DD = reinterpret_cast<jmethodID>(0x102);
jmethodID MID_OBJ_I = reinterpret_cast<jmethodID>(0x103);
jmethodID MID_CTOR_I = reinterpret_cast<jmethodID>(0x104);
jmethodID MID_I_OBJ = reinterpret_cast<jmethodID>(0x105);

const char* mock_signature(void*, jmethodID mid) {
    if (mid == MID_IFJDI) return "(IFJDI)I";
    if (mid == MID_DD) return "(DD)D";
    if (mid == MID_OBJ_I) return "(Ljava/lang/Object;I)V";
    if (mid == MID_CTOR_I) return "(I)V";
    if (mid == MID_I_OBJ) return "(I)Ljava/lang/Object;";
    return nullptr;
}

// ---- mock host ...A slots (Windows ARM64) ---------------------------------
jint JNICALL mock_CallIntMethodA(JNIEnv* e, jobject o, jmethodID m, const jvalue* a) {
    record(e, "CallIntMethodA", a, 5, o, nullptr, m);
    return a[0].i + static_cast<jint>(a[1].f) + static_cast<jint>(a[2].j) +
           static_cast<jint>(a[3].d) + a[4].i;
}
jdouble JNICALL mock_CallStaticDoubleMethodA(JNIEnv* e, jclass c, jmethodID m, const jvalue* a) {
    record(e, "CallStaticDoubleMethodA", a, 2, c, nullptr, m);
    return a[0].d + a[1].d;
}
void JNICALL mock_CallNonvirtualVoidMethodA(JNIEnv* e, jobject o, jclass c, jmethodID m,
                                            const jvalue* a) {
    record(e, "CallNonvirtualVoidMethodA", a, 2, o, c, m);
}
jobject JNICALL mock_NewObjectA(JNIEnv* e, jclass c, jmethodID m, const jvalue* a) {
    record(e, "NewObjectA", a, 1, c, nullptr, m);
    return reinterpret_cast<jobject>(0xA0000 | static_cast<uintptr_t>(a[0].i));
}
jobject JNICALL mock_CallObjectMethodA(JNIEnv* e, jobject o, jmethodID m, const jvalue* a) {
    record(e, "CallObjectMethodA", a, 1, o, nullptr, m);
    return reinterpret_cast<jobject>(0xB0000 | static_cast<uintptr_t>(a[0].i));
}

jint JNICALL mock_vm_GetEnv(JavaVM*, void** p, jint) { *p = &g_mock_env; return JNI_OK; }
jint JNICALL mock_vm_Attach(JavaVM*, void** p, void*) { *p = &g_mock_env; return JNI_OK; }
jint JNICALL mock_vm_Detach(JavaVM*) { return JNI_OK; }

void build_mock() {
    std::memset(&g_mock_table, 0, sizeof(g_mock_table));
    g_mock_table.CallIntMethodA = mock_CallIntMethodA;
    g_mock_table.CallStaticDoubleMethodA = mock_CallStaticDoubleMethodA;
    g_mock_table.CallNonvirtualVoidMethodA = mock_CallNonvirtualVoidMethodA;
    g_mock_table.NewObjectA = mock_NewObjectA;
    g_mock_table.CallObjectMethodA = mock_CallObjectMethodA;
    g_mock_env.functions = &g_mock_table;

    std::memset(&g_mock_invoke, 0, sizeof(g_mock_invoke));
    g_mock_invoke.GetEnv = mock_vm_GetEnv;
    g_mock_invoke.AttachCurrentThread = mock_vm_Attach;
    g_mock_invoke.AttachCurrentThreadAsDaemon = mock_vm_Attach;
    g_mock_invoke.DetachCurrentThread = mock_vm_Detach;
    g_mock_vm.functions = &g_mock_invoke;
}

template <typename Fn>
Fn slot(JNIEnv* genv, Fn JNINativeInterface_::*field) {
    const JNINativeInterface_* t = reinterpret_cast<JNIEnv_*>(genv)->functions;
    return reinterpret_cast<Fn>(*reinterpret_cast<void* const*>(&(t->*field)));
}

}  // namespace

int main() {
    std::printf("Win.apk variadic JNI bridge test\n");
    build_mock();
    winapk::guest_jni_set_signature_provider(mock_signature, nullptr);
    JavaVM* gvm = winapk::guest_jni_init(reinterpret_cast<JavaVM*>(&g_mock_vm));
    (void)gvm;
    JNIEnv* genv = winapk::guest_env_for_current_thread();
    if (!genv) { std::printf("\nFAILED: no guest env\n"); return 1; }

    // Virtual, C `...`: CallIntMethod(env, obj, mid, (I)1,(F)2.5,(J)100,(D)4.5,(I)9) sig (IFJDI)I.
    {
        auto fn = reinterpret_cast<jint(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jmethodID, ...)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::CallIntMethod)));
        // named: env, obj, mid; then I -> x3, F (promoted to double) -> v0, J -> x4, D -> v1, I -> x5
        const uint64_t x[8] = {reinterpret_cast<uint64_t>(genv), 0x11, reinterpret_cast<uint64_t>(MID_IFJDI), 1, 100, 9, 0, 0};
        const double d[8] = {2.5, 4.5, 0, 0, 0, 0, 0, 0};
        uint64_t xr;
        double dr;
        aapcs_call(reinterpret_cast<void*>(fn), x, d, &xr, &dr);
        jint r = static_cast<jint>(xr);
        bool args_ok = g_seen.nargs == 5 && g_seen.argv[0].i == 1 && g_seen.argv[1].f == 2.5f &&
                       g_seen.argv[2].j == 100 && g_seen.argv[3].d == 4.5 && g_seen.argv[4].i == 9;
        check("CallIntMethod (...) repacks I,F,J,D,I and dispatches to CallIntMethodA",
              r == (1 + 2 + 100 + 4 + 9) && args_ok && std::strcmp(g_seen.slot, "CallIntMethodA") == 0,
              "r=" + std::to_string(r));
    }
    // Static, `...`, all FP: CallStaticDoubleMethod sig (DD)D.
    {
        auto fn = reinterpret_cast<jdouble(WINAPK_GUEST_ABI*)(JNIEnv*, jclass, jmethodID, ...)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::CallStaticDoubleMethod)));
        const uint64_t x[8] = {reinterpret_cast<uint64_t>(genv), 0x22, reinterpret_cast<uint64_t>(MID_DD), 0, 0, 0, 0, 0};
        const double d[8] = {1.25, 8.75, 0, 0, 0, 0, 0, 0};
        uint64_t xr;
        double r;
        aapcs_call(reinterpret_cast<void*>(fn), x, d, &xr, &r);
        check("CallStaticDoubleMethod (...) repacks D,D (FP class)",
              r == 10.0 && g_seen.argv[0].d == 1.25 && g_seen.argv[1].d == 8.75 &&
                  std::strcmp(g_seen.slot, "CallStaticDoubleMethodA") == 0,
              "r=" + std::to_string(r));
    }
    // Nonvirtual (named=4), object + int: CallNonvirtualVoidMethod sig (Ljava/lang/Object;I)V.
    {
        auto fn = reinterpret_cast<void(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jclass, jmethodID,
                                                           ...)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::CallNonvirtualVoidMethod)));
        fn(genv, reinterpret_cast<jobject>(0x33), reinterpret_cast<jclass>(0x44), MID_OBJ_I,
           reinterpret_cast<jobject>(0xDEAD), (jint)77);
        check("CallNonvirtualVoidMethod (...) carries obj+clazz+mid and repacks L,I",
              g_seen.nargs == 2 && g_seen.argv[0].l == reinterpret_cast<jobject>(0xDEAD) &&
                  g_seen.argv[1].i == 77 && g_seen.fixed1 == reinterpret_cast<void*>(0x33) &&
                  g_seen.fixed2 == reinterpret_cast<void*>(0x44));
    }
    // NewObject (...), sig (I)V, returns jobject.
    {
        auto fn = reinterpret_cast<jobject(WINAPK_GUEST_ABI*)(JNIEnv*, jclass, jmethodID, ...)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::NewObject)));
        jobject o = fn(genv, reinterpret_cast<jclass>(0x55), MID_CTOR_I, (jint)0x42);
        check("NewObject (...) dispatches to NewObjectA",
              o == reinterpret_cast<jobject>(0xA0042) && g_seen.argv[0].i == 0x42 &&
                  std::strcmp(g_seen.slot, "NewObjectA") == 0);
    }
    // va_list form: CallObjectMethodV(env, obj, mid, va) sig (I)Ljava/lang/Object;. Build the
    // AAPCS64 va_list by hand: one int argument in the first integer slot.
    {
        uint8_t reg_save[48 + 128] = {0};
        winapk::SysVVaList va{};
        // AAPCS64's: the saved x0-x7 end at gr_top, and the next integer slot is gr_offs below it.
        uint8_t gr[64] = {0}, vr[128] = {0};
        va.stack = reg_save;  // unused: the single arg is in a register slot
        va.gr_top = gr + sizeof gr;
        va.vr_top = vr + sizeof vr;
        va.gr_offs = -64;
        va.vr_offs = -128;
        *reinterpret_cast<uint64_t*>(gr) = 0x1234;  // first integer vararg
        auto fn = reinterpret_cast<jobject(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jmethodID,
                                                             winapk::SysVVaList*)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::CallObjectMethodV)));
        jobject o = fn(genv, reinterpret_cast<jobject>(0x66), MID_I_OBJ, &va);
        check("CallObjectMethodV (va_list) decodes I and dispatches to CallObjectMethodA",
              o == reinterpret_cast<jobject>(0xB1234) && g_seen.argv[0].i == 0x1234 &&
                  std::strcmp(g_seen.slot, "CallObjectMethodA") == 0);
    }

    check("every variadic dispatch saw the host env", g_seen.env_ok);
    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
