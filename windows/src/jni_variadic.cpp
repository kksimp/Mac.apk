// jni_variadic.cpp -- see jni_variadic.h.

#include <atomic>
#include "winapk/jni_variadic.h"

#include "winapk/abi_thunk.h"
#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <tuple>
#include <type_traits>
#include <utility>

namespace winapk {

namespace {

SignatureProvider g_provider = nullptr;
void* g_provider_ctx = nullptr;

constexpr int kMaxArgs = 255;  // JNI's limit on method arguments

// Reads the argument stream `va` against the JNI method signature `sig` into
// `out`, returning the argument count or -1 on a malformed signature. The
// stream follows C variadic promotion, as the guest compiler emitted it:
// boolean/byte/char/short/int arrive in an integer slot, float arrives
// promoted to double in a floating slot.
int build_jvalues(const char* sig, SysVVaList* va, jvalue* out) {
    if (!sig || sig[0] != '(') return -1;
    const char* p = sig + 1;
    int n = 0;
    while (*p && *p != ')') {
        if (n >= kMaxArgs) return -1;
        char c = *p++;
        bool is_array = false;
        while (c == '[') {
            is_array = true;
            c = *p++;
        }
        if (is_array || c == 'L') {
            if (c == 'L') {
                while (*p && *p != ';') ++p;
                if (*p == ';') ++p;
            }
            out[n].l = reinterpret_cast<jobject>(static_cast<uintptr_t>(sysv_va_next_gp(va)));
        } else {
            switch (c) {
                case 'Z': out[n].z = static_cast<jboolean>(sysv_va_next_gp(va) & 1); break;
                case 'B': out[n].b = static_cast<jbyte>(sysv_va_next_gp(va)); break;
                case 'C': out[n].c = static_cast<jchar>(sysv_va_next_gp(va)); break;
                case 'S': out[n].s = static_cast<jshort>(sysv_va_next_gp(va)); break;
                case 'I': out[n].i = static_cast<jint>(sysv_va_next_gp(va)); break;
                case 'J': out[n].j = static_cast<jlong>(sysv_va_next_gp(va)); break;
                case 'F': out[n].f = static_cast<jfloat>(sysv_va_next_fp(va)); break;
                case 'D': out[n].d = static_cast<jdouble>(sysv_va_next_fp(va)); break;
                default: return -1;
            }
        }
        ++n;
    }
    return n;
}

[[noreturn]] void bad_signature(jmethodID mid) {
    std::fprintf(stderr, "[win.apk] no/invalid JNI signature for method %p in a variadic call\n",
                 reinterpret_cast<void*>(mid));
    std::fflush(stderr);
    std::abort();
}

// A constructor that threw, said in the log (the first few): native code that builds a Java object
// and gets null usually reports no more than "cannot create object", then goes on with the null
// and crashes somewhere else. The exception stays pending for the caller, as JNI leaves it.
void report_failed_new_object(JNIEnv* host) {
    static std::atomic<int> said{0};
    if (!host->ExceptionCheck() || said.fetch_add(1) >= 8) return;
    jthrowable ex = host->ExceptionOccurred();
    host->ExceptionClear();
    std::fputs("[win.apk] JNI NewObject: the constructor threw:\n", stderr);
    std::fflush(stderr);
    if (jclass cls = host->GetObjectClass(ex)) {
        if (jmethodID print = host->GetMethodID(cls, "printStackTrace", "()V")) host->CallVoidMethod(ex, print);
        host->DeleteLocalRef(cls);
    }
    host->ExceptionClear();  // whatever the printing itself threw
    host->Throw(ex);
    host->DeleteLocalRef(ex);
}

// One template covers every Call<T>Method / NewObject family: the A-form slot
// AField fixes the return type R and the argument list Args... after JNIEnv*,
// which is (the fixed arguments..., const jvalue*). The variadic entry point
// fn declares the SAME Args... -- a parameter pack must be last to be deduced,
// so the trailing pointer is declared const jvalue* and reinterpreted as the
// SysVVaList*/va_list the caller really passes there. The jmethodID is the
// argument before it; the fixed arguments are everything before that.
// Whether an A-form slot is NewObjectA (CallStaticObjectMethodA has the same type).
template <typename F>
constexpr bool is_new_object(F field) {
    if constexpr (std::is_same_v<F, decltype(&JNINativeInterface_::NewObjectA)>) {
        return field == &JNINativeInterface_::NewObjectA;
    } else {
        return false;
    }
}

template <auto AField>
struct VarBridge;

template <typename R, typename... Args, R(JNICALL* JNINativeInterface_::*AField)(JNIEnv*, Args...)>
struct VarBridge<AField> {
    static constexpr std::size_t N = sizeof...(Args);  // fixed args + the trailing pointer

    template <typename Tup, std::size_t... Is>
    static R call_aform(JNIEnv* host, const jvalue* argv, Tup& t, std::index_sequence<Is...>) {
        return (host->functions->*AField)(host, std::get<Is>(t)..., argv);
    }

    static WINAPK_GUEST_ABI R fn(JNIEnv* env, Args... args) {
        JNIEnv* host = host_env_of(env);
        auto t = std::forward_as_tuple(args...);
        SysVVaList* va =
            reinterpret_cast<SysVVaList*>(const_cast<jvalue*>(std::get<N - 1>(t)));
        jmethodID mid = std::get<N - 2>(t);
        const char* sig = g_provider ? g_provider(g_provider_ctx, mid) : nullptr;
        jvalue argv[kMaxArgs];
        if (build_jvalues(sig, va, argv) < 0) bad_signature(mid);
        if constexpr (std::is_same_v<R, jobject>) {
            R made = call_aform(host, argv, t, std::make_index_sequence<N - 1>{});
            if (!made && is_new_object(AField)) report_failed_new_object(host);
            return made;
        } else {
            return call_aform(host, argv, t, std::make_index_sequence<N - 1>{});
        }
    }
};

}  // namespace

void install_variadic_slots(JNINativeInterface_* table, SignatureProvider provider, void* ctx) {
    g_provider = provider;
    g_provider_ctx = ctx;
#define WINAPK_JNI_RESERVED(i)
#define WINAPK_JNI_PLAIN(Name)
#define WINAPK_JNI_CALL(Name, AForm, Named)                               \
    *reinterpret_cast<void**>(&table->Name) = make_sysv_variadic_entry(   \
        (Named), reinterpret_cast<void*>(&VarBridge<&JNINativeInterface_::AForm>::fn));
// The ...V forms take the guest's AAPCS64 va_list right after the `Named`
// fixed arguments: the layout the bridge reads.
#define WINAPK_JNI_CALLV(Name, AForm, Named) \
    *reinterpret_cast<void**>(&table->Name) = reinterpret_cast<void*>(&VarBridge<&JNINativeInterface_::AForm>::fn);
#include "jni_slots.inc"
#undef WINAPK_JNI_RESERVED
#undef WINAPK_JNI_PLAIN
#undef WINAPK_JNI_CALL
#undef WINAPK_JNI_CALLV
}

}  // namespace winapk
