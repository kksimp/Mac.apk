// guest_libs.cpp -- loads an app's arm64 native libraries; see
// guest_libs.h.

#include "guest_libs.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

#include <windows.h>

#include "winapk/abi_thunk.h"
#include "winapk/arm64_guest.h"
#include "winapk/guest_jni.h"
#include "winapk/jni_bind.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include "native_asset.h"
#include "winapk/jnigraphics.h"

#include "native_aaudio.h"
#include "native_activity.h"
#include "native_media.h"
#include "native_opensles.h"
#include "native_window.h"

namespace {

std::mutex g_lock;
std::unique_ptr<winapk::GuestLinker> g_linker;
JavaVM* g_guest_vm = nullptr;
// The stub module of each app library System.load()'ed, and its guest library.
std::unordered_map<jlong, winapk::GuestLibrary*> g_by_module;

using FindEntry = jlong(JNICALL*)(JNIEnv*, jclass, jlong, jstring);
FindEntry g_jdk_find_entry = nullptr;

std::string utf8(const std::wstring& w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n > 0 ? n - 1 : 0), '\0');
    if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, &s[0], n, nullptr, nullptr);
    return s;
}

bool is_guest_code(const void* addr) {
    winapk::GuestLinker* linker = winapk::GuestLinker::process();
    return linker && linker->image_containing(addr);
}

// ---- system functions the shim does not have yet ----------------------------

struct MissingTag {
    std::string name;
    std::atomic<bool> reported{false};  // two threads may meet in the same stub
};

// Called (Windows ARM64, from the tagged stub) by the guest; see
// make_tagged_zero_stub. The guest gets 0.
void report_missing(void*, const void* tag) {
    auto* t = static_cast<MissingTag*>(const_cast<void*>(tag));
    if (t->reported.exchange(true)) return;
    std::fprintf(stderr, "[win.apk] guest called a system function not implemented on Windows yet, returning 0: %s\n",
                 t->name.c_str());
    std::fflush(stderr);
}

void* missing_stub(const char* name) {
    static std::mutex lock;
    static std::unordered_map<std::string, void*> stubs;
    std::lock_guard<std::mutex> hold(lock);
    auto it = stubs.find(name);
    if (it != stubs.end()) return it->second;
    auto* tag = new MissingTag{name};
    void* stub = winapk::make_tagged_zero_stub(reinterpret_cast<void*>(&report_missing), tag);
    stubs.emplace(name, stub);
    return stub;
}

// A system variable the shim does not have: zeroed memory, large enough for
// the objects libraries import (FILE arrays, vtables, interface ids).
void* missing_object(const char* name) {
    static std::mutex lock;
    static std::unordered_map<std::string, void*> blocks;
    std::lock_guard<std::mutex> hold(lock);
    auto it = blocks.find(name);
    if (it != blocks.end()) return it->second;
    void* block = std::calloc(1, 4096);
    blocks.emplace(name, block);
    std::fprintf(stderr, "[win.apk] guest uses a system variable not implemented on Windows yet, bound to zeroed memory: %s\n",
                 name);
    return block;
}

// A guest library is being unloaded (dlclose brought its last reference
// down): the thunks HotSpot was handed for its functions and the stub-module
// entry that named it must not outlive it. A library the JVM System.load()ed
// keeps the JVM's reference, so this only happens for one the app dlopen'ed
// itself.
void on_guest_unload(const winapk::ElfImage* image, void*) {
    winapk::guest_native_thunks_forget(image->base(), image->mapped_size());
    std::lock_guard<std::mutex> hold(g_lock);
    for (auto it = g_by_module.begin(); it != g_by_module.end();) {
        it = winapk::GuestLinker::image_of(it->second) == image ? g_by_module.erase(it) : std::next(it);
    }
}

// ---- the app library behind a stub module -----------------------------------

// The .so a stub copy stands for: the translator writes its path, UTF-8, to
// <stub>.source.
std::wstring source_of(const std::wstring& stub_path) {
    std::wstring so;
    FILE* f = _wfopen((stub_path + L".source").c_str(), L"rb");
    if (!f) return so;
    std::string bytes;
    char buf[1024];
    for (size_t n; (n = std::fread(buf, 1, sizeof(buf), f)) > 0;) bytes.append(buf, n);
    std::fclose(f);
    while (!bytes.empty() && (bytes.back() == '\n' || bytes.back() == '\r')) bytes.pop_back();
    const int n = MultiByteToWideChar(CP_UTF8, 0, bytes.data(), static_cast<int>(bytes.size()), nullptr, 0);
    so.resize(static_cast<size_t>(n > 0 ? n : 0));
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, bytes.data(), static_cast<int>(bytes.size()), &so[0], n);
    return so;
}

// An app whose library was refused for x18 usually cannot start (libgdx.so) and would sit with an empty window;
// some only lose an optional feature (OpenVPN's speed test). So it is ended, with the reason, only if no frame
// appears in time.
void watch_for_unstartable_app(const std::string& lib, const std::string& why) {
    static std::once_flag once;
    std::call_once(once, [lib, why] {
        std::thread([lib, why] {
            constexpr int kWaitSeconds = 25;
            for (int s = 0; s < kWaitSeconds; ++s) {
                if (winapk::host::frame_presented()) return;
                Sleep(1000);
            }
            if (winapk::host::frame_presented()) return;
            std::fprintf(stderr,
                         "[win.apk] this app cannot run on this PC: it needs %s, which %s. It has presented no "
                         "window in %d seconds, so it is ended.\n",
                         lib.c_str(), why.c_str(), kWaitSeconds);
            std::fflush(stderr);
            TerminateProcess(GetCurrentProcess(), 3);
        }).detach();
    });
}

// Loads (or finds) the guest library a stub copy stands for, with its
// dependencies and constructors, as the dynamic linker does.
winapk::GuestLibrary* open_for_stub(const std::wstring& stub_path) {
    const std::wstring so = source_of(stub_path);
    if (so.empty()) {
        std::fprintf(stderr, "[win.apk] %s names no .so (no .source file)\n", utf8(stub_path).c_str());
        return nullptr;
    }
    std::string error;
    winapk::GuestLibrary* lib =
        g_linker->open(utf8(so), winapk::GuestLinker::kRtldNow, &error);
    if (!lib) std::fprintf(stderr, "[win.apk] cannot load %s: %s\n", utf8(so).c_str(), error.c_str());
    if (!lib && error.find("x18 as a scratch register") != std::string::npos) {
        watch_for_unstartable_app(utf8(std::filesystem::path(so).filename().wstring()),
                                  "uses register x18 as a scratch register, which Windows on Arm keeps for itself");
    }
    return lib;
}

// jdk.internal.loader.NativeLibrary.findEntry0(long handle, String name): for
// a stub module, the guest library's symbol (its dependency tree included, as
// dlsym on an Android handle); the NativeMethodBind hook wraps it before
// HotSpot calls it. For any other module, the JDK's own lookup.
jlong JNICALL find_entry(JNIEnv* env, jclass cls, jlong handle, jstring name) {
    winapk::GuestLibrary* lib = nullptr;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_by_module.find(handle);
        if (it != g_by_module.end()) lib = it->second;
    }
    if (!lib) return g_jdk_find_entry ? g_jdk_find_entry(env, cls, handle, name) : 0;
    const char* n = env->GetStringUTFChars(name, nullptr);
    if (!n) return 0;
    std::string error;
    void* p = g_linker->lookup(lib, n, nullptr, &error);
    env->ReleaseStringUTFChars(name, n);
    return reinterpret_cast<jlong>(p);
}

// com.macapk.loader.NativeLinker.nativeLinkOnly(String path): null when mapped,
// else the error.
jstring JNICALL link_only(JNIEnv* env, jclass, jstring path) {
    if (!path) return env->NewStringUTF("null path");
    const jchar* chars = env->GetStringChars(path, nullptr);
    const std::wstring stub(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(env->GetStringLength(path)));
    env->ReleaseStringChars(path, chars);
    return open_for_stub(stub) ? nullptr : env->NewStringUTF("cannot load the library (see the log)");
}

// The system libraries' functions: the Bionic shim's, then libEGL/libGLES and
// libandroid's native window, assets, activity, input and configuration,
// libOpenSLES and libaaudio, which live with the host's GL, window, Java and
// audio.
void* resolve_system(const char* name) {
    if (void* p = winapk::mini_bionic_resolve(name)) return p;
    if (void* p = winapk::host::guest_gl_resolve(name)) return p;
    if (void* p = winapk::host::native_window_resolve(name)) return p;
    if (void* p = winapk::host::native_asset_resolve(name)) return p;
    if (void* p = winapk::host::native_activity_resolve(name)) return p;
    if (void* p = winapk::host::native_aaudio_resolve(name)) return p;
    if (void* p = winapk::host::native_media_resolve(name)) return p;
    if (void* p = winapk::host::native_opensles_resolve(name)) return p;
    return winapk::jnigraphics_resolve(name);
}

const winapk::host::HostMethod kLinkerMethods[] = {
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeLinker", "nativeLinkOnly", "(Ljava/lang/String;)Ljava/lang/String;",
                       link_only),
};

// com.macapk.loader.CriticalNatives.isCritical, for jni_bind: whether ART
// would call a guest native as @CriticalNative.
jclass g_critical_cls = nullptr;
jmethodID g_critical_mid = nullptr;

bool is_critical_native(JNIEnv* env, jclass cls, const char* name, const char* sig) {
    if (!g_critical_cls || !g_critical_mid || !cls || !name || !sig) return false;
    // A pending exception (RegisterNatives' caller may have one) would make the
    // call fail; it is kept for the caller.
    jthrowable pending = env->ExceptionOccurred();
    if (pending) env->ExceptionClear();
    jstring jn = env->NewStringUTF(name);
    jstring js = env->NewStringUTF(sig);
    const bool critical = jn && js && env->CallStaticBooleanMethod(g_critical_cls, g_critical_mid, cls, jn, js);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (jn) env->DeleteLocalRef(jn);
    if (js) env->DeleteLocalRef(js);
    if (pending) {
        env->Throw(pending);
        env->DeleteLocalRef(pending);
    }
    return critical;
}

}  // namespace

namespace winapk::host {

bool guest_libs_init(JNIEnv* env, jvmtiEnv* jvmti) {
    if (jclass c = env->FindClass("com/macapk/loader/CriticalNatives")) {
        g_critical_cls = static_cast<jclass>(env->NewGlobalRef(c));
        g_critical_mid =
            env->GetStaticMethodID(c, "isCritical", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Z");
        env->DeleteLocalRef(c);
        if (g_critical_mid) winapk::set_critical_native_query(&is_critical_native);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    // Upstream's order: the guest JNI view and the bind hook exist before the
    // first guest library is loaded.
    winapk::guest_jni_set_signature_provider(winapk::jvmti_method_signature, jvmti);
    g_guest_vm = winapk::guest_jni_init(vm);
    if (winapk::install_native_method_bind(vm, is_guest_code) != JNI_OK) {
        std::fprintf(stderr, "[win.apk] cannot install the NativeMethodBind hook\n");
        return false;
    }

    winapk::LinkerConfig config;
    config.host = resolve_system;
    config.load.unresolved = missing_stub;
    config.load.unresolved_object = missing_object;
    g_linker = std::make_unique<winapk::GuestLinker>(config);
    g_linker->add_unload_hook(on_guest_unload, nullptr);

    // HotSpot's lookup of a native method by name ends in this static native.
    HMODULE java_dll = GetModuleHandleW(L"java.dll");
    g_jdk_find_entry = java_dll ? reinterpret_cast<FindEntry>(GetProcAddress(
                                      java_dll, "Java_jdk_internal_loader_NativeLibrary_findEntry0"))
                                : nullptr;
    jclass cls = env->FindClass("jdk/internal/loader/NativeLibrary");
    JNINativeMethod m{const_cast<char*>("findEntry0"), const_cast<char*>("(JLjava/lang/String;)J"),
                      reinterpret_cast<void*>(&find_entry)};
    if (!g_jdk_find_entry || !cls || env->RegisterNatives(cls, &m, 1) != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::fprintf(stderr, "[win.apk] cannot hook NativeLibrary.findEntry0; app natives bound by name will not resolve\n");
        return false;
    }
    return true;
}

HostMethods native_linker_methods() {
    return {kLinkerMethods, sizeof(kLinkerMethods) / sizeof(kLinkerMethods[0])};
}

void* guest_library_open(const std::wstring& path, std::string* error) {
    if (!g_linker) {
        if (error) *error = "the guest linker is not initialized";
        return nullptr;
    }
    // A stub copy names its ELF in <stub>.source; anything else is the ELF.
    const std::wstring so = source_of(path);
    if (!so.empty()) {
        winapk::GuestLibrary* lib = open_for_stub(path);
        if (!lib && error) *error = "cannot load " + utf8(so) + " (see the log)";
        return lib;
    }
    std::string err;
    winapk::GuestLibrary* lib = g_linker->open(utf8(path), winapk::GuestLinker::kRtldNow, &err);
    if (!lib) {
        std::fprintf(stderr, "[win.apk] cannot load %s: %s\n", utf8(path).c_str(), err.c_str());
        if (error) *error = err;
    }
    return lib;
}

void* guest_library_symbol(void* library, const char* name, std::string* error) {
    if (!g_linker || !library || !name) return nullptr;
    std::string err;
    void* p = g_linker->lookup(library, name, nullptr, &err);
    if (!p && error) *error = err;
    return p;
}

}  // namespace winapk::host

extern "C" jint winapk_guest_library_loaded(JavaVM*, void* module, const wchar_t* stub_path) {
    if (!g_linker) return JNI_ERR;
    winapk::GuestLibrary* lib = open_for_stub(stub_path);
    if (!lib) return JNI_ERR;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        g_by_module[reinterpret_cast<jlong>(module)] = lib;
    }
    // System.loadLibrary's second half on Android: the library's own
    // JNI_OnLoad, found with dlsym on its handle, called with the JavaVM.
    using OnLoad = jint(WINAPK_GUEST_ABI*)(JavaVM*, void*);
    std::string error;
    auto on_load = reinterpret_cast<OnLoad>(g_linker->lookup(lib, "JNI_OnLoad", nullptr, &error));
    if (!on_load) return JNI_VERSION_1_6;
    // Its FindClass calls get ART's semantics (guest_jni.cpp, guest_find_class).
    winapk::guest_jni_enter_onload();
    const jint version =
        reinterpret_cast<OnLoad>(winapk::arm64::host_callable(reinterpret_cast<void*>(on_load)))(g_guest_vm, nullptr);
    winapk::guest_jni_leave_onload();
    // JNI_OnLoad's address lets a guest crash address in a log be mapped
    // back to the library (its st_value gives the load bias).
    std::fprintf(stderr, "[win.apk] %s: JNI_OnLoad (at %p) returned 0x%x\n", utf8(source_of(stub_path)).c_str(),
                 reinterpret_cast<void*>(on_load), static_cast<unsigned>(version));
    return version;
}
