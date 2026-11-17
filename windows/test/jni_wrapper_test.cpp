// jni_wrapper_test.cpp -- drives the guest JNIEnv/JavaVM wrapper
// (windows/src/guest_jni.cpp) against a MOCK host JVM, so the forwarding and
// the System V <-> Microsoft x64 boundary are tested without real HotSpot.
//
// The mock host JNINativeInterface_ / JNIInvokeInterface_ are ordinary
// Microsoft x64 functions (JNICALL is a no-op on x64). The guest table's slots
// are System V. A test calls a guest slot the way guest code would -- load the
// pointer at the slot and call it System V -- and checks that:
//   * the call reaches the right mock slot with the arguments intact
//     (including floating-point returns, narrow returns, and a long argument
//     list that overflows into the stack),
//   * the mock sees the HOST env, i.e. the wrapper swapped the guest JNIEnv*
//     for the host one,
//   * JavaVM GetEnv / AttachCurrentThread hand back a guest env, not the host.

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

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

// ---- mock host JVM --------------------------------------------------------

JNIEnv_ g_mock_env;
JavaVM_ g_mock_vm;
JNINativeInterface_ g_mock_table;
JNIInvokeInterface_ g_mock_invoke;

// Every mock slot records that it ran and asserts it was handed the host env.
struct Record {
    bool env_ok = true;        // false if any slot saw the wrong env
    const char* last = "";     // name of the last slot called
    // captured arguments for the slots the test inspects
    std::string str;
    jint i0 = 0;
    jsize region_start = 0, region_len = 0;
    const jint* region_buf = nullptr;
    const char* mid_name = nullptr;
    const char* mid_sig = nullptr;
} g_rec;

void note(JNIEnv* env, const char* name) {
    g_rec.last = name;
    if (env != &g_mock_env) g_rec.env_ok = false;
}

jint JNICALL mock_GetVersion(JNIEnv* env) {
    note(env, "GetVersion");
    return JNI_VERSION_1_6;
}

// jstring carries strlen in a tagged pointer so NewStringUTF/GetStringUTFLength
// can round-trip without real objects.
constexpr uintptr_t kStrTag = 0x5700000000000000ull;
jstring JNICALL mock_NewStringUTF(JNIEnv* env, const char* s) {
    note(env, "NewStringUTF");
    g_rec.str = s ? s : "";
    return reinterpret_cast<jstring>(kStrTag | (s ? std::strlen(s) : 0));
}
jsize JNICALL mock_GetStringUTFLength(JNIEnv* env, jstring js) {
    note(env, "GetStringUTFLength");
    return static_cast<jsize>(reinterpret_cast<uintptr_t>(js) & 0xffffff);
}

jsize JNICALL mock_GetArrayLength(JNIEnv* env, jarray a) {
    note(env, "GetArrayLength");
    return static_cast<jsize>(reinterpret_cast<uintptr_t>(a) & 0xffff);
}

void JNICALL mock_SetIntField(JNIEnv* env, jobject obj, jfieldID f, jint v) {
    note(env, "SetIntField");
    g_rec.i0 = v + static_cast<jint>(reinterpret_cast<uintptr_t>(obj) & 0xff) +
               static_cast<jint>(reinterpret_cast<uintptr_t>(f) & 0xff);
}

jdouble JNICALL mock_GetDoubleField(JNIEnv* env, jobject obj, jfieldID f) {
    note(env, "GetDoubleField");
    return 1.5 + static_cast<double>(reinterpret_cast<uintptr_t>(obj) & 0xff);
}

jbyte JNICALL mock_GetByteField(JNIEnv* env, jobject obj, jfieldID f) {
    note(env, "GetByteField");
    return static_cast<jbyte>(-7);
}

// Four scalar args after env plus a pointer: exercises argument-count handling.
void JNICALL mock_SetIntArrayRegion(JNIEnv* env, jintArray a, jsize start, jsize len,
                                    const jint* buf) {
    note(env, "SetIntArrayRegion");
    g_rec.region_start = start;
    g_rec.region_len = len;
    g_rec.region_buf = buf;
}

// Classes are small integers. 1 declares everything. 5 extends 6 extends 7;
// only 6 declares "<init>()V", as Object does for a class with no no-argument
// constructor. A miss leaves an exception pending, as HotSpot does.
constexpr uintptr_t kNoSuchMethod = 0xE77;
jthrowable g_pending = nullptr;
int g_thrown = 0;

jmethodID JNICALL mock_GetMethodID(JNIEnv* env, jclass c, const char* name, const char* sig) {
    note(env, "GetMethodID");
    g_rec.mid_name = name;
    g_rec.mid_sig = sig;
    const uintptr_t k = reinterpret_cast<uintptr_t>(c);
    if (k == 1) return reinterpret_cast<jmethodID>(0xABCD);
    if (k == 6 && std::strcmp(name, "<init>") == 0 && std::strcmp(sig, "()V") == 0) {
        return reinterpret_cast<jmethodID>(0x6666);
    }
    g_pending = reinterpret_cast<jthrowable>(kNoSuchMethod);
    return nullptr;
}
jclass JNICALL mock_GetSuperclass(JNIEnv* env, jclass c) {
    note(env, "GetSuperclass");
    const uintptr_t k = reinterpret_cast<uintptr_t>(c);
    return (k == 5 || k == 6) ? reinterpret_cast<jclass>(k + 1) : nullptr;
}
jthrowable JNICALL mock_ExceptionOccurred(JNIEnv* env) {
    note(env, "ExceptionOccurred");
    return g_pending;
}
void JNICALL mock_ExceptionClear(JNIEnv* env) {
    note(env, "ExceptionClear");
    g_pending = nullptr;
}
jint JNICALL mock_Throw(JNIEnv* env, jthrowable t) {
    note(env, "Throw");
    g_pending = t;
    ++g_thrown;
    return 0;
}
void JNICALL mock_DeleteLocalRef(JNIEnv* env, jobject) { note(env, "DeleteLocalRef"); }

// mock JavaVM
jint JNICALL mock_vm_GetEnv(JavaVM*, void** penv, jint) {
    *penv = &g_mock_env;
    return JNI_OK;
}
jint JNICALL mock_vm_Attach(JavaVM*, void** penv, void*) {
    *penv = &g_mock_env;
    return JNI_OK;
}
jint JNICALL mock_vm_Detach(JavaVM*) { return JNI_OK; }

void build_mock() {
    std::memset(&g_mock_table, 0, sizeof(g_mock_table));
    g_mock_table.GetVersion = mock_GetVersion;
    g_mock_table.NewStringUTF = mock_NewStringUTF;
    g_mock_table.GetStringUTFLength = mock_GetStringUTFLength;
    g_mock_table.GetArrayLength = mock_GetArrayLength;
    g_mock_table.SetIntField = mock_SetIntField;
    g_mock_table.GetDoubleField = mock_GetDoubleField;
    g_mock_table.GetByteField = mock_GetByteField;
    g_mock_table.SetIntArrayRegion = mock_SetIntArrayRegion;
    g_mock_table.GetMethodID = mock_GetMethodID;
    g_mock_table.GetSuperclass = mock_GetSuperclass;
    g_mock_table.ExceptionOccurred = mock_ExceptionOccurred;
    g_mock_table.ExceptionClear = mock_ExceptionClear;
    g_mock_table.Throw = mock_Throw;
    g_mock_table.DeleteLocalRef = mock_DeleteLocalRef;
    g_mock_env.functions = &g_mock_table;

    std::memset(&g_mock_invoke, 0, sizeof(g_mock_invoke));
    g_mock_invoke.GetEnv = mock_vm_GetEnv;
    g_mock_invoke.AttachCurrentThread = mock_vm_Attach;
    g_mock_invoke.AttachCurrentThreadAsDaemon = mock_vm_Attach;
    g_mock_invoke.DetachCurrentThread = mock_vm_Detach;
    g_mock_vm.functions = &g_mock_invoke;
}

// Reads a guest-table slot as the System V function pointer it really holds.
template <typename Fn>
Fn slot(JNIEnv* genv, Fn JNINativeInterface_::*field) {
    const JNINativeInterface_* t = reinterpret_cast<JNIEnv_*>(genv)->functions;
    void* raw = *reinterpret_cast<void* const*>(&(t->*field));
    return reinterpret_cast<Fn>(raw);
}

}  // namespace

int main() {
    std::printf("Win.apk guest JNIEnv/JavaVM wrapper test\n");
    build_mock();

    JavaVM* gvm = winapk::guest_jni_init(reinterpret_cast<JavaVM*>(&g_mock_vm));
    check("guest_jni_init returned a guest VM", gvm && gvm != reinterpret_cast<JavaVM*>(&g_mock_vm));

    // JavaVM GetEnv, called System V like guest code, must return a guest env.
    JNIEnv* genv = nullptr;
    {
        auto getenv_fn = reinterpret_cast<jint(WINAPK_GUEST_ABI*)(JavaVM*, void**, jint)>(
            *reinterpret_cast<void* const*>(&reinterpret_cast<JNIInvokeInterface_*>(
                 const_cast<struct JNIInvokeInterface_*>(reinterpret_cast<JavaVM_*>(gvm)->functions))
                 ->GetEnv));
        jint r = getenv_fn(gvm, reinterpret_cast<void**>(&genv), JNI_VERSION_1_6);
        check("guest VM GetEnv returns JNI_OK + a guest env",
              r == JNI_OK && genv && genv != &g_mock_env);
        check("host_env_of(guest env) == mock host env",
              winapk::host_env_of(genv) == &g_mock_env);
    }
    if (!genv) {
        std::printf("\nFAILED: no guest env\n");
        return 1;
    }

    // Each slot, called System V, must reach the mock with the host env.
    {
        auto f = slot(genv, &JNINativeInterface_::GetVersion);
        auto fn = reinterpret_cast<jint(WINAPK_GUEST_ABI*)(JNIEnv*)>(reinterpret_cast<void*>(f));
        jint v = fn(genv);
        check("GetVersion forwards (jint return, no args)", v == JNI_VERSION_1_6,
              "got 0x" + [](jint x){char b[16];std::snprintf(b,sizeof b,"%x",x);return std::string(b);}(v));
    }
    {
        auto fn = reinterpret_cast<jstring(WINAPK_GUEST_ABI*)(JNIEnv*, const char*)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::NewStringUTF)));
        jstring js = fn(genv, "win.apk");
        check("NewStringUTF forwards (const char* arg, pointer return)",
              g_rec.str == "win.apk" && (reinterpret_cast<uintptr_t>(js) & 0xffffff) == 7);
    }
    {
        auto fn = reinterpret_cast<jsize(WINAPK_GUEST_ABI*)(JNIEnv*, jarray)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::GetArrayLength)));
        jsize n = fn(genv, reinterpret_cast<jarray>(0x1234));
        check("GetArrayLength forwards (jsize return)", n == 0x1234);
    }
    {
        auto fn = reinterpret_cast<void(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jfieldID, jint)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::SetIntField)));
        fn(genv, reinterpret_cast<jobject>(0x02), reinterpret_cast<jfieldID>(0x03), 40);
        check("SetIntField forwards (void, mixed args)", g_rec.i0 == 45,
              "got " + std::to_string(g_rec.i0));
    }
    {
        auto fn = reinterpret_cast<jdouble(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jfieldID)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::GetDoubleField)));
        jdouble d = fn(genv, reinterpret_cast<jobject>(0x10), reinterpret_cast<jfieldID>(0));
        check("GetDoubleField forwards (jdouble return, FP class)", d == 1.5 + 16.0,
              "got " + std::to_string(d));
    }
    {
        auto fn = reinterpret_cast<jbyte(WINAPK_GUEST_ABI*)(JNIEnv*, jobject, jfieldID)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::GetByteField)));
        jbyte b = fn(genv, reinterpret_cast<jobject>(1), reinterpret_cast<jfieldID>(1));
        check("GetByteField forwards (jbyte return)", b == static_cast<jbyte>(-7),
              "got " + std::to_string(static_cast<int>(b)));
    }
    {
        jint buf[3] = {1, 2, 3};
        auto fn = reinterpret_cast<void(WINAPK_GUEST_ABI*)(JNIEnv*, jintArray, jsize, jsize,
                                                           const jint*)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::SetIntArrayRegion)));
        fn(genv, reinterpret_cast<jintArray>(9), 11, 22, buf);
        check("SetIntArrayRegion forwards (4 args + pointer)",
              g_rec.region_start == 11 && g_rec.region_len == 22 && g_rec.region_buf == buf);
    }
    {
        auto fn = reinterpret_cast<jmethodID(WINAPK_GUEST_ABI*)(JNIEnv*, jclass, const char*,
                                                               const char*)>(
            reinterpret_cast<void*>(slot(genv, &JNINativeInterface_::GetMethodID)));
        jmethodID m = fn(genv, reinterpret_cast<jclass>(1), "<init>", "()V");
        check("GetMethodID forwards (multiple pointer args)",
              m == reinterpret_cast<jmethodID>(0xABCD) &&
                  std::strcmp(g_rec.mid_name, "<init>") == 0 &&
                  std::strcmp(g_rec.mid_sig, "()V") == 0);

        // ART semantics: a constructor only a superclass declares is found
        // there, and the miss in the class itself leaves nothing pending.
        g_pending = nullptr;
        g_thrown = 0;
        m = fn(genv, reinterpret_cast<jclass>(5), "<init>", "()V");
        check("GetMethodID finds an inherited constructor (ART)",
              m == reinterpret_cast<jmethodID>(0x6666) && !g_pending && g_thrown == 0);

        // No ancestor has it: the class's own NoSuchMethodError is rethrown.
        m = fn(genv, reinterpret_cast<jclass>(5), "<init>", "(I)V");
        check("GetMethodID keeps the error when no ancestor has the constructor",
              !m && g_pending == reinterpret_cast<jthrowable>(kNoSuchMethod) && g_thrown == 1);

        // Other methods are HotSpot's lookup alone, error left as it was.
        g_pending = nullptr;
        g_thrown = 0;
        m = fn(genv, reinterpret_cast<jclass>(5), "run", "()V");
        check("GetMethodID does not retry a missing non-constructor",
              !m && g_pending == reinterpret_cast<jthrowable>(kNoSuchMethod) && g_thrown == 0);
        g_pending = nullptr;
    }

    check("every forwarded slot saw the host env (env translation)", g_rec.env_ok);

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
