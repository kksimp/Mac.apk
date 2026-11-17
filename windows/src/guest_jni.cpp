// guest_jni.cpp -- see guest_jni.h.
//
// The 235 JNINativeInterface_ slots fall in three groups (windows/src/jni_slots.inc):
//
//   * non-variadic (WINAPK_JNI_PLAIN): forwarded by a single template whose
//     signature is deduced from the JNINativeInterface_ field itself, so every
//     one of them is generated, not hand-written. The exceptions:
//     RegisterNatives wraps the guest's function pointers first (jni_bind.h),
//     GetJavaVM hands out the guest VM, GetMethodID finds constructors the
//     way ART does, and GetFieldID knows libcore's one renamed field.
//   * the Call<T>Method(env, obj, mid, ...) families (WINAPK_JNI_CALL) and
//     their va_list variants (WINAPK_JNI_CALLV): bridged by jni_variadic.cpp
//     once a signature provider is set, a loud-abort stub until then.
//   * reserved: left null, as HotSpot leaves them.
//
// The guest table is shared by every thread (the forwarders are stateless).
// The per-thread part is the GuestEnv wrapper, which carries the host JNIEnv*
// this thread is attached to.

#include "winapk/guest_jni.h"

#include "winapk/guest_abi.h"
#include "winapk/guest_stack.h"
#include "winapk/jni_bind.h"
#include "winapk/mini_bionic.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace winapk {

namespace {

// A guest JNIEnv is this struct, handed to guest code as a JNIEnv*. Its first
// member matches JNIEnv_ (a lone `functions` pointer), so a GuestEnv* and a
// JNIEnv* are interconvertible by reinterpret_cast.
struct GuestEnv {
    const JNINativeInterface_* functions;  // -> the shared guest table
    JNIEnv* host;                          // HotSpot's env for this thread
    bool alive;
};

// A guest JavaVM, likewise interconvertible with JavaVM_.
struct GuestVM {
    const JNIInvokeInterface_* functions;  // -> the guest invoke table
    JavaVM* host;                          // HotSpot's JavaVM
};

JavaVM* g_host_vm = nullptr;
GuestVM g_guest_vm;
JNINativeInterface_ g_guest_table;      // filled by build_guest_table()
JNIInvokeInterface_ g_guest_invoke;     // filled by guest_jni_init()
bool g_table_built = false;             // init runs once at startup, before threads
SignatureProvider g_sig_provider = nullptr;
void* g_sig_ctx = nullptr;

// How deep this thread is in guest JNI_OnLoad calls (guest_jni_enter_onload).
thread_local int t_onload_depth = 0;

// One guest env per thread. Its address is stable for the thread's life, which
// is what lets it be handed out as a JNIEnv*.
thread_local GuestEnv t_env;

GuestEnv* guest_env_from(JNIEnv* genv) {
    auto* g = reinterpret_cast<GuestEnv*>(genv);
    return (g && g->functions == &g_guest_table) ? g : nullptr;
}

// The generic forwarder. The partial specialization deduces (R, A...) from the
// JNINativeInterface_ field, so one template covers every non-variadic slot.
// The host env comes from the guest env the slot was called through, not from
// the calling thread as in upstream's ARM32 model (which tolerates engines
// that cache one env globally).
template <auto Field>
struct Slot;

template <typename R, typename... A, R(JNICALL* JNINativeInterface_::*Field)(JNIEnv*, A...)>
struct Slot<Field> {
    static WINAPK_GUEST_ABI R fn(JNIEnv* genv, A... args) {
        GuestEnv* g = guest_env_from(genv);
        JNIEnv* host = g ? g->host : genv;
        return (host->functions->*Field)(host, args...);
    }
};

// Slot stub for the variadic Call<T>Method families before a signature
// provider is set (guest_jni_set_signature_provider). Declared with fixed
// parameters (not `...`): an AAPCS64 caller cleans up its own stack, so the
// ignored variadic arguments are harmless, and they are never read.
[[noreturn]] WINAPK_GUEST_ABI void unimplemented_variadic(JNIEnv*) {
    std::fprintf(stderr,
                 "[win.apk] a guest called a variadic JNI Call*Method before the signature "
                 "provider was set\n");
    std::fflush(stderr);
    std::abort();
}

// GetMethodID as ART does it for constructors. ART resolves every method,
// "<init>" included, through the class and then its superclasses
// (Class::FindClassMethod), so a constructor only an ancestor declares is
// found, and NewObject then builds the subclass with it. HotSpot looks for
// "<init>" in the class alone and throws NoSuchMethodError. Waze's libwaze
// asks HOVPermitDescriptor, which has only (String, String), for "()V" during
// startup: on a device that is Object's constructor; here the error escaped
// NativeManager.onlineInit and the app never started.
WINAPK_GUEST_ABI jmethodID guest_get_method_id(JNIEnv* genv, jclass cls, const char* name, const char* sig) {
    GuestEnv* g = guest_env_from(genv);
    JNIEnv* env = g ? g->host : genv;
    jmethodID m = env->GetMethodID(cls, name, sig);
    if (m || !name || std::strcmp(name, "<init>") != 0) return m;
    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    for (jclass c = env->GetSuperclass(cls); c && !m;) {
        m = env->GetMethodID(c, name, sig);
        env->ExceptionClear();
        jclass super = m ? nullptr : env->GetSuperclass(c);
        env->DeleteLocalRef(c);
        c = super;
    }
    if (!m && error) env->Throw(error);  // no ancestor has it either: HotSpot's error stands
    if (error) env->DeleteLocalRef(error);
    return m;
}

// GetFieldID with upstream's one field alias (bionic_shim_jni_bare.cpp,
// macapk_field_alias): Android's libcore renamed java.io.FileDescriptor's
// private int from `fd` to `descriptor`, and native code reads it by name
// (Conscrypt's NativeCrypto, SDL's asset opener, ffmpeg-based players).
// HotSpot has `fd`, so the lookup failed with NoSuchFieldError pending, and a
// guest that skips the check went on with a null field id: Conscrypt printed
// "could not find field descriptor" and aborted DAVx5 at start. The alias is
// tried only on a miss, only for that exact class, name and signature.
WINAPK_GUEST_ABI jfieldID guest_get_field_id(JNIEnv* genv, jclass cls, const char* name, const char* sig) {
    GuestEnv* g = guest_env_from(genv);
    JNIEnv* env = g ? g->host : genv;
    jfieldID f = env->GetFieldID(cls, name, sig);
    if (f || !name || !sig || std::strcmp(name, "descriptor") != 0 || std::strcmp(sig, "I") != 0) return f;
    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    bool is_fd_class = false;
    if (jclass fd_class = env->FindClass("java/io/FileDescriptor")) {
        is_fd_class = env->IsSameObject(cls, fd_class);
        env->DeleteLocalRef(fd_class);
    }
    env->ExceptionClear();
    if (is_fd_class) f = env->GetFieldID(cls, "fd", sig);
    if (!f && error) env->Throw(error);
    if (error) env->DeleteLocalRef(error);
    return f;
}

// FindClass inside JNI_OnLoad, as ART does it. ART's FindClass loads and
// links a class but leaves it uninitialized (ClassLinker::FindClass); HotSpot's
// also runs its static initializer. Inside JNI_OnLoad that difference matters:
// HotSpot adds the library to its class loader's list only after JNI_OnLoad
// returns, so an initializer that calls a native of the library being loaded
// fails with UnsatisfiedLinkError and FindClass returns null. On Android the
// initializer runs later, on first use, when the library is registered.
// Realm's JNI_OnLoad looks up io.realm.internal.core.NativeRealmAny, whose
// initializer asks librealm-jni for its finalizer; Realm asserted and aborted
// Bit at start. So inside JNI_OnLoad the class is loaded with
// Class.forName(name, false, loader), where loader is the one HotSpot's own
// FindClass uses there: that of NativeLibraries.getFromClass(), the class whose
// System.load is running. Anything unexpected falls back to HotSpot's FindClass,
// which then also reports a missing class the usual way. Outside JNI_OnLoad
// FindClass is HotSpot's: the library is registered by then.
struct OnLoadIds {
    jclass native_libraries = nullptr;  // global refs
    jclass class_class = nullptr;
    jmethodID get_from_class = nullptr;  // NativeLibraries.getFromClass()
    jmethodID get_class_loader = nullptr;
    jmethodID for_name = nullptr;
};

OnLoadIds onload_ids(JNIEnv* env) {
    OnLoadIds ids;
    jclass libs = env->FindClass("jdk/internal/loader/NativeLibraries");
    jclass cls = env->FindClass("java/lang/Class");
    if (libs && cls) {
        ids.get_from_class = env->GetStaticMethodID(libs, "getFromClass", "()Ljava/lang/Class;");
        ids.get_class_loader = env->GetMethodID(cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
        ids.for_name =
            env->GetStaticMethodID(cls, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    }
    env->ExceptionClear();
    if (ids.get_from_class && ids.get_class_loader && ids.for_name) {
        ids.native_libraries = static_cast<jclass>(env->NewGlobalRef(libs));
        ids.class_class = static_cast<jclass>(env->NewGlobalRef(cls));
    }
    if (libs) env->DeleteLocalRef(libs);
    if (cls) env->DeleteLocalRef(cls);
    return ids;
}

jclass find_class_uninitialized(JNIEnv* env, const char* name) {
    static const OnLoadIds ids = onload_ids(env);
    if (!ids.native_libraries) return nullptr;
    jobject from = env->CallStaticObjectMethod(ids.native_libraries, ids.get_from_class);
    if (env->ExceptionCheck() || !from) {
        env->ExceptionClear();
        return nullptr;
    }
    jobject loader = env->CallObjectMethod(from, ids.get_class_loader);
    env->DeleteLocalRef(from);
    std::string dotted(name);
    for (char& c : dotted)
        if (c == '/') c = '.';
    jstring jname = env->NewStringUTF(dotted.c_str());
    jobject found = jname ? env->CallStaticObjectMethod(ids.class_class, ids.for_name, jname, JNI_FALSE, loader)
                          : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        found = nullptr;
    }
    if (jname) env->DeleteLocalRef(jname);
    if (loader) env->DeleteLocalRef(loader);
    return static_cast<jclass>(found);
}

WINAPK_GUEST_ABI jclass guest_find_class(JNIEnv* genv, const char* name) {
    GuestEnv* g = guest_env_from(genv);
    JNIEnv* env = g ? g->host : genv;
    if (t_onload_depth > 0 && name && !env->ExceptionCheck()) {
        if (jclass c = find_class_uninitialized(env, name)) return c;
    }
    return env->FindClass(name);
}

// GetJavaVM hands the guest the guest JavaVM (guest-ABI slots), never HotSpot's:
// a guest that calls (*vm)->GetEnv on HotSpot's own table would get HotSpot's
// JNIEnv, not the guest one, and fault on its first call through it.
WINAPK_GUEST_ABI jint guest_get_java_vm(JNIEnv*, JavaVM** vm) {
    if (!vm) return JNI_ERR;
    *vm = reinterpret_cast<JavaVM*>(&g_guest_vm);
    return JNI_OK;
}

void build_guest_table() {
    JNINativeInterface_& t = g_guest_table;
#define WINAPK_JNI_RESERVED(i)
#define WINAPK_JNI_PLAIN(Name) \
    *reinterpret_cast<void**>(&t.Name) = reinterpret_cast<void*>(&Slot<&JNINativeInterface_::Name>::fn);
#define WINAPK_JNI_CALL(Name, Kind, Type) \
    *reinterpret_cast<void**>(&t.Name) = reinterpret_cast<void*>(&unimplemented_variadic);
#define WINAPK_JNI_CALLV(Name, Kind, Type) \
    *reinterpret_cast<void**>(&t.Name) = reinterpret_cast<void*>(&unimplemented_variadic);
#include "jni_slots.inc"
#undef WINAPK_JNI_RESERVED
#undef WINAPK_JNI_PLAIN
#undef WINAPK_JNI_CALL
#undef WINAPK_JNI_CALLV

    // The plain slots that cannot just forward. The function pointers the
    // guest registers are AAPCS64 and need a thunk each (jni_bind.h).
    *reinterpret_cast<void**>(&t.RegisterNatives) = reinterpret_cast<void*>(&guest_register_natives);
    *reinterpret_cast<void**>(&t.GetJavaVM) = reinterpret_cast<void*>(&guest_get_java_vm);
    *reinterpret_cast<void**>(&t.GetMethodID) = reinterpret_cast<void*>(&guest_get_method_id);
    *reinterpret_cast<void**>(&t.GetFieldID) = reinterpret_cast<void*>(&guest_get_field_id);
    *reinterpret_cast<void**>(&t.FindClass) = reinterpret_cast<void*>(&guest_find_class);
}

// Updates this thread's guest env to wrap `host` and returns it as a JNIEnv*.
JNIEnv* wrap_host_env(JNIEnv* host) {
    // Every call from Java into a guest native passes here: the first on a
    // thread readies its stack for code that does not probe it.
    commit_guest_stack_for_current_thread();
    guest_signal_altstack_for_current_thread();
    t_env.functions = &g_guest_table;
    t_env.host = host;
    t_env.alive = true;
    return reinterpret_cast<JNIEnv*>(&t_env);
}

// ---- guest JavaVM slots (AAPCS64) ---------------------------------------

JavaVM* host_vm_from(JavaVM* vm) {
    auto* g = reinterpret_cast<GuestVM*>(vm);
    return (g && g->functions == &g_guest_invoke) ? g->host : vm;
}

WINAPK_GUEST_ABI jint gv_DestroyJavaVM(JavaVM*) {
    // The host owns the VM lifetime; a guest DestroyJavaVM is a no-op, as the
    // upstream ARM32 proxy does.
    return JNI_OK;
}

WINAPK_GUEST_ABI jint gv_AttachCurrentThread(JavaVM* vm, void** penv, void* args) {
    JavaVM* host = host_vm_from(vm);
    JNIEnv* henv = nullptr;
    jint r = host->AttachCurrentThread(reinterpret_cast<void**>(&henv), args);
    if (r != JNI_OK || !henv) return r;
    *penv = wrap_host_env(henv);
    return JNI_OK;
}

WINAPK_GUEST_ABI jint gv_AttachCurrentThreadAsDaemon(JavaVM* vm, void** penv, void* args) {
    JavaVM* host = host_vm_from(vm);
    JNIEnv* henv = nullptr;
    jint r = host->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&henv), args);
    if (r != JNI_OK || !henv) return r;
    *penv = wrap_host_env(henv);
    return JNI_OK;
}

WINAPK_GUEST_ABI jint gv_DetachCurrentThread(JavaVM* vm) {
    JavaVM* host = host_vm_from(vm);
    jint r = host->DetachCurrentThread();
    t_env.alive = false;
    t_env.host = nullptr;
    return r;
}

WINAPK_GUEST_ABI jint gv_GetEnv(JavaVM* vm, void** penv, jint version) {
    JavaVM* host = host_vm_from(vm);
    JNIEnv* henv = nullptr;
    jint r = host->GetEnv(reinterpret_cast<void**>(&henv), version);
    if (r != JNI_OK || !henv) {
        *penv = nullptr;
        return r;
    }
    *penv = wrap_host_env(henv);
    return JNI_OK;
}

}  // namespace

JavaVM* guest_jni_init(JavaVM* host_vm) {
    g_host_vm = host_vm;
    if (!g_table_built) {
        build_guest_table();
        g_table_built = true;
    }
    if (g_sig_provider) install_variadic_slots(&g_guest_table, g_sig_provider, g_sig_ctx);

    JNIInvokeInterface_& iv = g_guest_invoke;
    iv.reserved0 = nullptr;
    iv.reserved1 = nullptr;
    iv.reserved2 = nullptr;
    *reinterpret_cast<void**>(&iv.DestroyJavaVM) = reinterpret_cast<void*>(&gv_DestroyJavaVM);
    *reinterpret_cast<void**>(&iv.AttachCurrentThread) = reinterpret_cast<void*>(&gv_AttachCurrentThread);
    *reinterpret_cast<void**>(&iv.DetachCurrentThread) = reinterpret_cast<void*>(&gv_DetachCurrentThread);
    *reinterpret_cast<void**>(&iv.GetEnv) = reinterpret_cast<void*>(&gv_GetEnv);
    *reinterpret_cast<void**>(&iv.AttachCurrentThreadAsDaemon) =
        reinterpret_cast<void*>(&gv_AttachCurrentThreadAsDaemon);

    g_guest_vm.functions = &g_guest_invoke;
    g_guest_vm.host = host_vm;
    return reinterpret_cast<JavaVM*>(&g_guest_vm);
}

JavaVM* guest_java_vm() { return g_host_vm ? reinterpret_cast<JavaVM*>(&g_guest_vm) : nullptr; }

JNIEnv* guest_env_for_current_thread() {
    if (t_env.alive && t_env.functions == &g_guest_table) return reinterpret_cast<JNIEnv*>(&t_env);
    if (!g_host_vm) return nullptr;
    JNIEnv* host = nullptr;
    jint r = g_host_vm->GetEnv(reinterpret_cast<void**>(&host), JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        r = g_host_vm->AttachCurrentThread(reinterpret_cast<void**>(&host), nullptr);
    }
    if (r != JNI_OK || !host) return nullptr;
    return wrap_host_env(host);
}

JNIEnv* host_env_of(JNIEnv* guest) {
    GuestEnv* g = guest_env_from(guest);
    return g ? g->host : nullptr;
}

JNIEnv* guest_env_wrapping(JNIEnv* host) { return host ? wrap_host_env(host) : nullptr; }

void guest_jni_enter_onload() { ++t_onload_depth; }

void guest_jni_leave_onload() {
    if (t_onload_depth > 0) --t_onload_depth;
}

void guest_jni_set_signature_provider(SignatureProvider provider, void* ctx) {
    g_sig_provider = provider;
    g_sig_ctx = ctx;
    // If the table already exists, install the bridges now; otherwise
    // guest_jni_init will, once it has built the table.
    if (g_table_built) install_variadic_slots(&g_guest_table, provider, ctx);
}

}  // namespace winapk
