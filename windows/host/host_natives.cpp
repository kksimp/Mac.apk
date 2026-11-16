// host_natives.cpp -- see host_natives.h.

#include "host_natives.h"

#include "java_io_open.h"
#include "sqlite_paths.h"
#include "winapk/abi_thunk.h"
#include "winapk/mini_bionic.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

namespace winapk::host {

namespace {

// Every class in runtime/java that declares native methods. A class added
// upstream with natives and missing here fails with UnsatisfiedLinkError, which
// names it.
const char* const kHostClasses[] = {
    "android.app.NativeActivity",
    "android.content.ClipboardManager",
    "android.content.ClipboardManager$MacApkHostPasteboard",
    "android.opengl.EGL14",
    "android.opengl.EGL15",
    "android.opengl.EGLExt",
    "android.opengl.GLES10",
    "android.opengl.GLES11",
    "android.opengl.GLES20",
    "android.opengl.GLES30",
    "android.opengl.GLES31",
    "android.opengl.GLES31Ext",
    "android.opengl.GLES32",
    "android.opengl.GLUtils",
    "android.provider.MediaStoreProvider",
    "android.view.InputQueue",
    "android.os.MacApkBinderTransport",
    "android.webkit.MacApkWebViewBridge",
    "com.google.firebase.app.internal.cpp.CppThreadDispatcherContext",
    "com.google.firebase.app.internal.cpp.GoogleApiAvailabilityHelper",
    "com.google.firebase.app.internal.cpp.JniResultCallback",
    "com.google.firebase.app.internal.cpp.Log",
    "com.macapk.gms.NativeSignIn",
    "com.macapk.icu.HostIcu",
    "com.macapk.loader.AudioInputBridge",
    "com.macapk.loader.CameraBridge",
    "com.macapk.loader.DocumentPickerHandler",
    "com.macapk.loader.GamepadBridge",
    "com.macapk.loader.HostBluetooth",
    "com.macapk.loader.HostBrowserTab",
    "com.macapk.loader.HostNotifications",
    "com.macapk.loader.HostRadios",
    "com.macapk.loader.HostVideo",
    "com.macapk.loader.LocationBridge",
    "com.macapk.loader.NativeAudio",
    "com.macapk.loader.NativeCocoa",
    "com.macapk.loader.NativeGles",
    "com.macapk.loader.NativeImageCodec",
    "com.macapk.loader.NativeLinker",
    "com.macapk.loader.NativeLog",
    "com.macapk.loader.NativeLooper",
    "com.macapk.loader.NativeMedia",
    "com.macapk.loader.NativeOs",
    "com.macapk.loader.NativeSqlite",
    "com.macapk.loader.WinProcesses",
    "com.macapk.loader.monet.HostWallpaper",
    "javax.microedition.khronos.egl.MacApkEGL10",
};

// ---- stubs -----------------------------------------------------------------

struct StubTag {
    std::string method;  // "com.macapk.loader.NativeCocoa.init()Z"
    std::atomic<bool> reported{false};
};

// Called by every stub (make_tagged_zero_stub) before it returns zero.
void report_stub(JNIEnv*, const void* tag) {
    auto* t = static_cast<StubTag*>(const_cast<void*>(tag));
    if (t->reported.exchange(true)) return;
    std::fprintf(stderr, "[win.apk] native not implemented on Windows yet, returning 0: %s\n",
                 t->method.c_str());
    std::fflush(stderr);
}

const HostMethods kProviders[] = {
    native_log_methods(),
    native_cocoa_methods(),
    native_linker_methods(),
    native_os_methods(),
    native_image_methods(),
    native_looper_methods(),
    native_audio_methods(),
    native_clipboard_methods(),
    native_wallpaper_methods(),
    native_picker_methods(),
    native_gamepad_methods(),
    native_icu_methods(),
    native_audio_input_methods(),
    native_photos_methods(),
    native_gl_methods(),
    native_egl10_methods(),
    native_browser_tab_methods(),
    native_webview_methods(),
    native_camera_methods(),
    native_radios_methods(),
    native_bluetooth_methods(),
    native_location_methods(),
    native_activity_methods(),
    native_signin_methods(),
    native_media_video_methods(),
    native_media_codec_methods(),
    native_notifications_methods(),
    native_binder_methods(),
};

const JNINativeMethod* implementation_of(const char* clazz, const char* name, const char* sig) {
    for (const HostMethods& p : kProviders) {
        for (size_t k = 0; k < p.count; ++k) {
            const HostMethod& i = p.methods[k];
            if (std::strcmp(i.clazz, clazz) == 0 && std::strcmp(i.method.name, name) == 0 &&
                std::strcmp(i.method.signature, sig) == 0) {
                return &i.method;
            }
        }
    }
    return nullptr;
}

// ---- reflection helpers -----------------------------------------------------

struct Reflection {
    jclass class_class, method_class, loader_class;
    jmethodID for_name, declared_methods, modifiers, system_loader;
    jobject loader;
};

bool init_reflection(JNIEnv* env, Reflection* r) {
    r->class_class = env->FindClass("java/lang/Class");
    r->method_class = env->FindClass("java/lang/reflect/Method");
    r->loader_class = env->FindClass("java/lang/ClassLoader");
    if (!r->class_class || !r->method_class || !r->loader_class) return false;
    r->for_name = env->GetStaticMethodID(r->class_class, "forName",
                                         "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    r->declared_methods = env->GetMethodID(r->class_class, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    r->modifiers = env->GetMethodID(r->method_class, "getModifiers", "()I");
    r->system_loader = env->GetStaticMethodID(r->loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (!r->for_name || !r->declared_methods || !r->modifiers || !r->system_loader) return false;
    r->loader = env->CallStaticObjectMethod(r->loader_class, r->system_loader);
    return r->loader && !env->ExceptionCheck();
}

// Loads `name` without initializing it: binding natives must not run static
// initializers early, some of which call the very natives being bound.
jclass load_class(JNIEnv* env, const Reflection& r, const char* name) {
    jstring jname = env->NewStringUTF(name);
    auto cls = static_cast<jclass>(
        env->CallStaticObjectMethod(r.class_class, r.for_name, jname, JNI_FALSE, r.loader));
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return cls;
}

constexpr jint kAccNative = 0x100;

}  // namespace

bool register_host_natives(JNIEnv* env, jvmtiEnv* jvmti, NativeStats* stats) {
    Reflection r{};
    if (!init_reflection(env, &r)) {
        env->ExceptionClear();
        return false;
    }
    for (const char* clazz : kHostClasses) {
        jclass cls = load_class(env, r, clazz);
        if (!cls) {
            std::fprintf(stderr, "[win.apk] host class %s is not on the classpath; skipped\n", clazz);
            continue;
        }
        ++stats->classes;
        auto methods = static_cast<jobjectArray>(env->CallObjectMethod(cls, r.declared_methods));
        if (env->ExceptionCheck() || !methods) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            continue;
        }
        std::vector<JNINativeMethod> bind;
        std::deque<std::string> strings;  // stable storage for bind's names until RegisterNatives
        const jsize n = env->GetArrayLength(methods);
        for (jsize i = 0; i < n; ++i) {
            jobject m = env->GetObjectArrayElement(methods, i);
            const jint mods = env->CallIntMethod(m, r.modifiers);
            if (mods & kAccNative) {
                char* name = nullptr;
                char* sig = nullptr;
                if (jvmti->GetMethodName(env->FromReflectedMethod(m), &name, &sig, nullptr) ==
                    JVMTI_ERROR_NONE) {
                    if (const JNINativeMethod* impl = implementation_of(clazz, name, sig)) {
                        bind.push_back(*impl);
                        ++stats->implemented;
                    } else {
                        auto* tag = new StubTag();
                        tag->method = std::string(clazz) + "." + name + sig;
                        void* stub = make_tagged_zero_stub(reinterpret_cast<void*>(&report_stub), tag);
                        strings.emplace_back(name);
                        char* name_copy = &strings.back()[0];
                        strings.emplace_back(sig);
                        bind.push_back({name_copy, &strings.back()[0], stub});
                        ++stats->stubbed;
                    }
                    jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
                    jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
                }
            }
            env->DeleteLocalRef(m);
        }
        if (!bind.empty() && env->RegisterNatives(cls, bind.data(), static_cast<jint>(bind.size())) != JNI_OK) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            std::fprintf(stderr, "[win.apk] RegisterNatives failed for %s\n", clazz);
        }
        env->DeleteLocalRef(methods);
        env->DeleteLocalRef(cls);
    }
    // Every JVM this host runs (an app, the broker, --gms) opens java.io files
    // so that they can be renamed and deleted while open, as on Android.
    // Android paths ("/data/user/0/<package>/...") open the files the guest
    // shim maps them to.
    java_io_set_path_mapper(&winapk::android_host_path);
    java_io_open_init(env);
    sqlite_android_paths_init();
    return true;
}

}  // namespace winapk::host
