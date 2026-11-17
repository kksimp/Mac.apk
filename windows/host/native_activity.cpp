// native_activity.cpp -- see native_activity.h.
//
// Mirrors frameworks/base core/jni/android_app_NativeActivity.cpp,
// core/jni/android_view_InputQueue.cpp, native/android/native_activity.cpp,
// native/android/input.cpp and native/android/configuration.cpp, through
// upstream's Mac port (runtime/translator/src/bionic_shim_nativeactivity.cpp and
// the AConfiguration section of bionic_shim.cpp). What differs on Windows:
//
//   * The library is the one BaseDexClassLoader.findLibrary returned: a stub
//     copy with a .source sidecar naming the ELF (guest_libs.h), opened
//     through the guest linker, and the entry point looked up in it. Neither
//     runs JNI_OnLoad, as AOSP's OpenNativeLibrary + dlsym do not.
//   * Guest code calls AAPCS64 functions and hands back AAPCS64 callbacks
//     (guest_abi.h), so every function the guest calls is WINAPK_GUEST_ABI and
//     every callback pointer in ANativeActivityCallbacks is a WINAPK_GUEST_ABI
//     type. An arm64 library's callbacks run through the arm64 bridge's
//     execute-fault handler, like any other callback.
//   * ANativeActivity.vm and .env are the guest JavaVM and the loading
//     thread's guest JNIEnv (guest_jni.h), not HotSpot's.
//   * The queue's readiness descriptor is an eventfd of the shim's descriptor
//     layer (bionic/fd.cpp), registered on the app's ALooper with the shim's own
//     ALooper_addFd, so AInputQueue_attachLooper wakes the looper the glue
//     polls.
//   * AConfiguration_fromAssetManager fills a phone-class configuration from
//     the phone the app sees (NativeCocoa's size in dp and DisplayMetrics'
//     density), as the Mac fills it from its canvas: there is no resource
//     table to read it from.
//
// The two Java-side pieces upstream added (NativeActivity.macapkPostWork for
// ANativeActivity_finish & co, InputQueue.macapkPostFinish for
// AInputQueue_finishEvent) are used unchanged.

#include "native_activity.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <unordered_map>

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"
#include "winapk/mini_bionic.h"

#include "guest_libs.h"
#include "native_asset.h"
#include "native_window.h"

namespace winapk::host {

namespace {

// ---- NDK structs (android/native_activity.h, android/rect.h) ---------------
//
// The layout is the public ABI the guest compiled against (LP64).
// The layout is the public ABI the guest compiled against (LP64). The callback pointers are the guest's.
struct ANativeActivity;
struct ARect {
    int32_t left, top, right, bottom;
};

struct ANativeActivityCallbacks {
    void(WINAPK_GUEST_ABI* onStart)(ANativeActivity*);
    void(WINAPK_GUEST_ABI* onResume)(ANativeActivity*);
    void*(WINAPK_GUEST_ABI* onSaveInstanceState)(ANativeActivity*, size_t* outSize);
    void(WINAPK_GUEST_ABI* onPause)(ANativeActivity*);
    void(WINAPK_GUEST_ABI* onStop)(ANativeActivity*);
    void(WINAPK_GUEST_ABI* onDestroy)(ANativeActivity*);
    void(WINAPK_GUEST_ABI* onWindowFocusChanged)(ANativeActivity*, int hasFocus);
    void(WINAPK_GUEST_ABI* onNativeWindowCreated)(ANativeActivity*, void* window);
    void(WINAPK_GUEST_ABI* onNativeWindowResized)(ANativeActivity*, void* window);
    void(WINAPK_GUEST_ABI* onNativeWindowRedrawNeeded)(ANativeActivity*, void* window);
    void(WINAPK_GUEST_ABI* onNativeWindowDestroyed)(ANativeActivity*, void* window);
    void(WINAPK_GUEST_ABI* onInputQueueCreated)(ANativeActivity*, void* queue);
    void(WINAPK_GUEST_ABI* onInputQueueDestroyed)(ANativeActivity*, void* queue);
    void(WINAPK_GUEST_ABI* onContentRectChanged)(ANativeActivity*, const ARect* rect);
    void(WINAPK_GUEST_ABI* onConfigurationChanged)(ANativeActivity*);
    void(WINAPK_GUEST_ABI* onLowMemory)(ANativeActivity*);
};

struct ANativeActivity {
    ANativeActivityCallbacks* callbacks;
    JavaVM* vm;
    JNIEnv* env;
    jobject clazz;
    const char* internalDataPath;
    const char* externalDataPath;
    int32_t sdkVersion;
    void* instance;
    void* assetManager;
    const char* obbPath;
};

using CreateFn = void(WINAPK_GUEST_ABI*)(ANativeActivity*, void* savedState, size_t savedStateSize);

// android_app_NativeActivity.cpp's ActivityWork commands, as
// NativeActivity.macapkPostWork numbers them.
enum { kCmdFinish = 1, kCmdSetWindowFormat, kCmdSetWindowFlags, kCmdShowSoftInput, kCmdHideSoftInput };

// AOSP's NativeCode: the activity the guest sees, followed by what the host
// keeps for it.
struct NativeCode : ANativeActivity {
    NativeCode() {
        std::memset(static_cast<ANativeActivity*>(this), 0, sizeof(ANativeActivity));
        std::memset(&cbs, 0, sizeof(cbs));
        callbacks = &cbs;
    }
    ANativeActivityCallbacks cbs;
    CreateFn create = nullptr;
    std::string internal_data_path, external_data_path, obb_path;
    void* native_window = nullptr;
    int32_t last_window_w = 0, last_window_h = 0;
    jobject java_asset_manager = nullptr;  // global ref

    void set_surface(JNIEnv* env, jobject surface) {
        native_window = surface ? native_window_for_surface(env, surface) : nullptr;
    }
};

JavaVM* g_host_vm = nullptr;
jclass g_activity_class = nullptr;  // global ref: android.app.NativeActivity
jmethodID g_post_work = nullptr;    // static macapkPostWork(NativeActivity, int, int, int)
jmethodID g_queue_post_finish = nullptr;  // InputQueue.macapkPostFinish(long, boolean)
jmethodID g_weak_get = nullptr;           // java.lang.ref.Reference.get()
std::once_flag g_ids_once;
thread_local std::string t_dl_error;

// The host JNIEnv of the calling thread. A thread HotSpot has not seen (the
// glue's android_main thread calling ANativeActivity_finish or
// AInputQueue_finishEvent) is attached for the call and detached again.
struct EnvGuard {
    JNIEnv* env = nullptr;
    bool attached = false;
    EnvGuard() {
        if (!g_host_vm) return;
        const jint rc = g_host_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            attached = g_host_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        } else if (rc != JNI_OK) {
            env = nullptr;
        }
    }
    ~EnvGuard() {
        if (attached) g_host_vm->DetachCurrentThread();
    }
    EnvGuard(const EnvGuard&) = delete;
    EnvGuard& operator=(const EnvGuard&) = delete;
};

bool clear(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

// The Java methods the host calls back, resolved once from a host thread.
void init_ids(JNIEnv* env) {
    env->GetJavaVM(&g_host_vm);
    if (jclass c = env->FindClass("android/app/NativeActivity")) {
        g_activity_class = static_cast<jclass>(env->NewGlobalRef(c));
        g_post_work = env->GetStaticMethodID(c, "macapkPostWork", "(Landroid/app/NativeActivity;III)V");
        env->DeleteLocalRef(c);
    }
    clear(env);
    if (jclass c = env->FindClass("android/view/InputQueue")) {
        g_queue_post_finish = env->GetMethodID(c, "macapkPostFinish", "(JZ)V");
        env->DeleteLocalRef(c);
    }
    clear(env);
    if (jclass c = env->FindClass("java/lang/ref/Reference")) {
        g_weak_get = env->GetMethodID(c, "get", "()Ljava/lang/Object;");
        env->DeleteLocalRef(c);
    }
    clear(env);
    if (!g_post_work || !g_queue_post_finish || !g_weak_get) {
        std::fprintf(stderr, "[win.apk] NativeActivity: macapkPostWork / macapkPostFinish not found; "
                             "finish() and input completion from native code will not work\n");
    }
}

void post_work(ANativeActivity* activity, int32_t cmd, int32_t arg1 = 0, int32_t arg2 = 0) {
    if (!activity || !activity->clazz || !g_activity_class || !g_post_work) return;
    EnvGuard g;
    if (!g.env) return;
    g.env->CallStaticVoidMethod(g_activity_class, g_post_work, activity->clazz, static_cast<jint>(cmd),
                                static_cast<jint>(arg1), static_cast<jint>(arg2));
    clear(g.env);
}

std::atomic<int> g_trace_n{0};
void trace(const char* what, const void* code) {
    if (g_trace_n.fetch_add(1) < 64) std::fprintf(stderr, "[win.apk] NativeActivity: %s (code=%p)\n", what, code);
}

std::string utf8_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::wstring wide_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* c = env->GetStringChars(s, nullptr);
    std::wstring out(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return out;
}

// ---- android.app.NativeActivity natives -------------------------------------

jlong JNICALL NA_loadNativeCode(JNIEnv* env, jobject activity, jstring path, jstring func_name,
                                jobject /*message_queue*/, jstring internal_data_dir, jstring obb_dir,
                                jstring external_data_dir, jint sdk_version, jobject asset_manager,
                                jbyteArray saved_state, jobject /*class_loader*/, jstring /*library_path*/) {
    std::call_once(g_ids_once, init_ids, env);
    if (!path || !func_name) {
        t_dl_error = "null library path or entry point";
        return 0;
    }
    const std::wstring lib_path = wide_of(env, path);
    const std::string func = utf8_of(env, func_name);
    std::string error;
    void* lib = guest_library_open(lib_path, &error);
    if (!lib) {
        t_dl_error = error.empty() ? "cannot load the library (see the log)" : error;
        std::fprintf(stderr, "[win.apk] NativeActivity: cannot load %ls: %s\n", lib_path.c_str(), t_dl_error.c_str());
        return 0;
    }
    void* entry = guest_library_symbol(lib, func.c_str(), &error);
    if (!entry) {
        t_dl_error = error.empty() ? func + " not found" : error;
        std::fprintf(stderr, "[win.apk] NativeActivity: %s not found in %ls: %s\n", func.c_str(), lib_path.c_str(),
                     t_dl_error.c_str());
        return 0;
    }

    auto* code = new NativeCode();
    code->create = reinterpret_cast<CreateFn>(entry);
    code->vm = winapk::guest_java_vm();
    code->env = winapk::guest_env_for_current_thread();
    code->clazz = env->NewGlobalRef(activity);
    code->internal_data_path = utf8_of(env, internal_data_dir);
    code->internalDataPath = code->internal_data_path.c_str();
    code->external_data_path = utf8_of(env, external_data_dir);
    code->externalDataPath = code->external_data_path.c_str();
    code->obb_path = utf8_of(env, obb_dir);
    code->obbPath = code->obb_path.c_str();
    code->sdkVersion = sdk_version;
    code->java_asset_manager = asset_manager ? env->NewGlobalRef(asset_manager) : nullptr;
    using FromJava = void*(WINAPK_GUEST_ABI*)(JNIEnv*, jobject);
    if (auto from_java = reinterpret_cast<FromJava>(native_asset_resolve("AAssetManager_fromJava"))) {
        code->assetManager = from_java(code->env, code->java_asset_manager);
    }

    jbyte* raw_state = nullptr;
    jsize raw_size = 0;
    if (saved_state) {
        raw_state = env->GetByteArrayElements(saved_state, nullptr);
        raw_size = env->GetArrayLength(saved_state);
    }
    std::fprintf(stderr, "[win.apk] NativeActivity: %ls -> %s (at %p), sdk %d, internal %s\n", lib_path.c_str(),
                 func.c_str(), entry, static_cast<int>(sdk_version), code->internalDataPath);
    code->create(code, raw_state, static_cast<size_t>(raw_size));
    trace("ANativeActivity_onCreate returned", code);
    if (raw_state) env->ReleaseByteArrayElements(saved_state, raw_state, 0);
    return reinterpret_cast<jlong>(code);
}

jstring JNICALL NA_getDlError(JNIEnv* env, jobject) {
    jstring r = env->NewStringUTF(t_dl_error.c_str());
    t_dl_error.clear();
    return r;
}

void JNICALL NA_unloadNativeCode(JNIEnv* env, jobject, jlong handle) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    trace("onDestroy", code);
    if (code->cbs.onDestroy) code->cbs.onDestroy(code);
    if (code->clazz) env->DeleteGlobalRef(code->clazz);
    if (code->java_asset_manager) env->DeleteGlobalRef(code->java_asset_manager);
    code->set_surface(env, nullptr);
    // AOSP keeps the library loaded ("for now don't unload"); so does the linker.
    delete code;
}

#define WINAPK_NA_SIMPLE(fn, cb, label)                              \
    void JNICALL fn(JNIEnv*, jobject, jlong handle) {                \
        auto* code = reinterpret_cast<NativeCode*>(handle);          \
        if (!code) return;                                           \
        trace(label, code);                                          \
        if (code->cbs.cb) code->cbs.cb(code);                        \
    }
WINAPK_NA_SIMPLE(NA_onStart, onStart, "onStart")
WINAPK_NA_SIMPLE(NA_onResume, onResume, "onResume")
WINAPK_NA_SIMPLE(NA_onPause, onPause, "onPause")
WINAPK_NA_SIMPLE(NA_onStop, onStop, "onStop")
WINAPK_NA_SIMPLE(NA_onConfigurationChanged, onConfigurationChanged, "onConfigurationChanged")
WINAPK_NA_SIMPLE(NA_onLowMemory, onLowMemory, "onLowMemory")
#undef WINAPK_NA_SIMPLE

jbyteArray JNICALL NA_onSaveInstanceState(JNIEnv* env, jobject, jlong handle) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code || !code->cbs.onSaveInstanceState) return nullptr;
    size_t len = 0;
    auto* state = static_cast<jbyte*>(code->cbs.onSaveInstanceState(code, &len));
    jbyteArray array = nullptr;
    if (state && len > 0) {
        array = env->NewByteArray(static_cast<jsize>(len));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(len), state);
    }
    // The guest allocated it with its malloc, which is the shim's.
    using Free = void(WINAPK_GUEST_ABI*)(void*);
    if (state) {
        if (auto f = reinterpret_cast<Free>(winapk::mini_bionic_resolve("free"))) f(state);
    }
    return array;
}

void JNICALL NA_onWindowFocusChanged(JNIEnv*, jobject, jlong handle, jboolean focused) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    trace(focused ? "onWindowFocusChanged(1)" : "onWindowFocusChanged(0)", code);
    if (code->cbs.onWindowFocusChanged) code->cbs.onWindowFocusChanged(code, focused ? 1 : 0);
}

void window_size(void* window, int32_t* w, int32_t* h) {
    int ww = 0, hh = 0;
    if (native_window_buffer_size(window, &ww, &hh)) {
        *w = ww;
        *h = hh;
    }
}

void JNICALL NA_onSurfaceCreated(JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    code->set_surface(env, surface);
    std::fprintf(stderr, "[win.apk] NativeActivity: onNativeWindowCreated window=%p (code=%p)\n", code->native_window,
                 static_cast<void*>(code));
    if (code->native_window && code->cbs.onNativeWindowCreated) code->cbs.onNativeWindowCreated(code, code->native_window);
}

void JNICALL NA_onSurfaceChanged(JNIEnv* env, jobject, jlong handle, jobject surface, jint /*format*/, jint /*width*/,
                                 jint /*height*/) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    void* old_window = code->native_window;
    code->set_surface(env, surface);
    if (old_window != code->native_window) {
        if (old_window && code->cbs.onNativeWindowDestroyed) code->cbs.onNativeWindowDestroyed(code, old_window);
        if (code->native_window) {
            if (code->cbs.onNativeWindowCreated) code->cbs.onNativeWindowCreated(code, code->native_window);
            window_size(code->native_window, &code->last_window_w, &code->last_window_h);
        }
    } else if (code->native_window) {
        int32_t w = code->last_window_w, h = code->last_window_h;
        window_size(code->native_window, &w, &h);
        if (w != code->last_window_w || h != code->last_window_h) {
            // AOSP compares against lastWindow* without updating them here.
            if (code->cbs.onNativeWindowResized) code->cbs.onNativeWindowResized(code, code->native_window);
        }
    }
}

void JNICALL NA_onSurfaceRedrawNeeded(JNIEnv*, jobject, jlong handle, jobject /*surface*/) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    if (code->native_window && code->cbs.onNativeWindowRedrawNeeded) {
        code->cbs.onNativeWindowRedrawNeeded(code, code->native_window);
    }
}

void JNICALL NA_onSurfaceDestroyed(JNIEnv* env, jobject, jlong handle) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    trace("onNativeWindowDestroyed", code);
    if (code->native_window && code->cbs.onNativeWindowDestroyed) {
        code->cbs.onNativeWindowDestroyed(code, code->native_window);
    }
    code->set_surface(env, nullptr);
}

void JNICALL NA_onInputQueueCreated(JNIEnv*, jobject, jlong handle, jlong queue) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    trace("onInputQueueCreated", code);
    if (code->cbs.onInputQueueCreated) code->cbs.onInputQueueCreated(code, reinterpret_cast<void*>(queue));
}

void JNICALL NA_onInputQueueDestroyed(JNIEnv*, jobject, jlong handle, jlong queue) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code) return;
    trace("onInputQueueDestroyed", code);
    if (code->cbs.onInputQueueDestroyed) code->cbs.onInputQueueDestroyed(code, reinterpret_cast<void*>(queue));
}

void JNICALL NA_onContentRectChanged(JNIEnv*, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    auto* code = reinterpret_cast<NativeCode*>(handle);
    if (!code || !code->cbs.onContentRectChanged) return;
    const ARect rect{x, y, x + w, y + h};
    code->cbs.onContentRectChanged(code, &rect);
}

// ---- input events and the queue (android_view_InputQueue.cpp, input.cpp) ---

constexpr int32_t kTypeKey = 1, kTypeMotion = 2;    // AINPUT_EVENT_TYPE_*
constexpr int32_t kFlagPredispatch = 0x20000000;    // AKEY_EVENT_FLAG_PREDISPATCH
constexpr int kMaxPointers = 16;                    // MAX_POINTERS
constexpr int kAxes = 48;                           // AMOTION_EVENT_AXIS_GENERIC_16 + 1
constexpr int32_t kAxisX = 0, kAxisY = 1, kAxisPressure = 2, kAxisSize = 3, kAxisTouchMajor = 4,
                  kAxisTouchMinor = 5, kAxisToolMajor = 6, kAxisToolMinor = 7, kAxisOrientation = 8;
constexpr int32_t kSourceClassJoystick = 0x10;
constexpr int kLooperEventInput = 1;  // ALOOPER_EVENT_INPUT

// The NDK treats AInputEvent as opaque, so this is the runtime's own layout;
// only the accessors below read it. No batched history is carried (an event
// with history size 0).
struct InputEvent {
    int32_t type = 0;
    int32_t device_id = 0, source = 0, display_id = 0;
    int32_t action = 0, flags = 0, meta_state = 0;
    int64_t down_time_ns = 0, event_time_ns = 0;
    // key
    int32_t key_code = 0, scan_code = 0, repeat_count = 0;
    // motion
    int32_t button_state = 0, edge_flags = 0, action_button = 0;
    float x_offset = 0, y_offset = 0, x_precision = 1, y_precision = 1;
    int32_t pointer_count = 0;
    int32_t pointer_id[kMaxPointers] = {};
    int32_t tool_type[kMaxPointers] = {};
    float raw_x[kMaxPointers] = {}, raw_y[kMaxPointers] = {};
    float axes[kMaxPointers][kAxes] = {};
    int64_t id = 0;
};

// The shim's descriptor functions, which the guest's looper polls.
struct ShimFd {
    int(WINAPK_GUEST_ABI* eventfd)(unsigned, int) = nullptr;
    int64_t(WINAPK_GUEST_ABI* read)(int, void*, size_t) = nullptr;
    int64_t(WINAPK_GUEST_ABI* write)(int, const void*, size_t) = nullptr;
    int(WINAPK_GUEST_ABI* close)(int) = nullptr;
    int(WINAPK_GUEST_ABI* looper_add_fd)(void*, int, int, int, void*, void*) = nullptr;
    int(WINAPK_GUEST_ABI* looper_remove_fd)(void*, int) = nullptr;
    bool ok = false;
};

const ShimFd& shim_fd() {
    static const ShimFd s = [] {
        ShimFd f;
        f.eventfd = reinterpret_cast<decltype(f.eventfd)>(winapk::mini_bionic_resolve("eventfd"));
        f.read = reinterpret_cast<decltype(f.read)>(winapk::mini_bionic_resolve("read"));
        f.write = reinterpret_cast<decltype(f.write)>(winapk::mini_bionic_resolve("write"));
        f.close = reinterpret_cast<decltype(f.close)>(winapk::mini_bionic_resolve("close"));
        f.looper_add_fd = reinterpret_cast<decltype(f.looper_add_fd)>(winapk::mini_bionic_resolve("ALooper_addFd"));
        f.looper_remove_fd =
            reinterpret_cast<decltype(f.looper_remove_fd)>(winapk::mini_bionic_resolve("ALooper_removeFd"));
        f.ok = f.eventfd && f.read && f.write && f.close && f.looper_add_fd && f.looper_remove_fd;
        if (!f.ok) std::fprintf(stderr, "[win.apk] NativeActivity: the shim's eventfd or ALooper is missing\n");
        return f;
    }();
    return s;
}

constexpr int kEfdNonblock = 04000;  // EFD_NONBLOCK (O_NONBLOCK)

struct InputQueue {
    int fd = -1;                   // readable while events are pending
    jobject weak_queue = nullptr;  // global ref to the Java WeakReference<InputQueue>
    std::mutex lock;
    std::deque<InputEvent*> pending;
    void* looper = nullptr;  // the app looper attachLooper registered on
};

std::atomic<int64_t> g_next_event_id{1};

void enqueue(InputQueue* q, InputEvent* ev) {
    std::lock_guard<std::mutex> hold(q->lock);
    q->pending.push_back(ev);
    if (q->pending.size() == 1 && q->fd >= 0) {
        const uint64_t one = 1;
        shim_fd().write(q->fd, &one, sizeof(one));
    }
}

jlong JNICALL IQ_nativeInit(JNIEnv* env, jclass, jobject weak_queue, jobject /*message_queue*/) {
    std::call_once(g_ids_once, init_ids, env);
    const ShimFd& s = shim_fd();
    if (!s.ok) {
        if (jclass re = env->FindClass("java/lang/RuntimeException")) env->ThrowNew(re, "InputQueue failed to initialize");
        return 0;
    }
    auto* q = new InputQueue();
    q->fd = s.eventfd(0, kEfdNonblock);
    q->weak_queue = env->NewGlobalRef(weak_queue);
    std::fprintf(stderr, "[win.apk] NativeActivity: InputQueue created %p (fd %d)\n", static_cast<void*>(q), q->fd);
    return reinterpret_cast<jlong>(q);
}

void JNICALL IQ_nativeDispose(JNIEnv* env, jclass, jlong ptr) {
    auto* q = reinterpret_cast<InputQueue*>(ptr);
    if (!q) return;
    void* looper;
    {
        std::lock_guard<std::mutex> hold(q->lock);
        looper = q->looper;
        q->looper = nullptr;
    }
    if (looper) shim_fd().looper_remove_fd(looper, q->fd);
    if (q->weak_queue) env->DeleteGlobalRef(q->weak_queue);
    for (InputEvent* e : q->pending) delete e;
    if (q->fd >= 0) shim_fd().close(q->fd);
    delete q;
}

// The getters of android.view.KeyEvent and MotionEvent, cached once.
struct EventMethods {
    bool ok = false;
    jmethodID k_device_id, k_source, k_action, k_flags, k_key_code, k_scan_code, k_meta, k_repeat, k_down_time,
        k_event_time;
    jmethodID m_device_id, m_source, m_action, m_flags, m_meta, m_buttons, m_edge, m_action_button, m_down_time,
        m_event_time_nanos, m_x_prec, m_y_prec, m_count, m_pointer_id, m_tool_type, m_axis, m_raw_x, m_raw_y, m_x,
        m_y;
};
EventMethods g_em;
std::once_flag g_em_once;

void init_event_methods(JNIEnv* env) {
    jclass k = env->FindClass("android/view/KeyEvent");
    jclass m = env->FindClass("android/view/MotionEvent");
    if (!k || !m) {
        env->ExceptionClear();
        std::fprintf(stderr, "[win.apk] NativeActivity: KeyEvent / MotionEvent not found\n");
        return;
    }
    auto mid = [env](jclass c, const char* n, const char* s) {
        jmethodID id = env->GetMethodID(c, n, s);
        if (!id) env->ExceptionClear();
        return id;
    };
    EventMethods& e = g_em;
    e.k_device_id = mid(k, "getDeviceId", "()I");
    e.k_source = mid(k, "getSource", "()I");
    e.k_action = mid(k, "getAction", "()I");
    e.k_flags = mid(k, "getFlags", "()I");
    e.k_key_code = mid(k, "getKeyCode", "()I");
    e.k_scan_code = mid(k, "getScanCode", "()I");
    e.k_meta = mid(k, "getMetaState", "()I");
    e.k_repeat = mid(k, "getRepeatCount", "()I");
    e.k_down_time = mid(k, "getDownTime", "()J");
    e.k_event_time = mid(k, "getEventTime", "()J");
    e.m_device_id = mid(m, "getDeviceId", "()I");
    e.m_source = mid(m, "getSource", "()I");
    e.m_action = mid(m, "getAction", "()I");
    e.m_flags = mid(m, "getFlags", "()I");
    e.m_meta = mid(m, "getMetaState", "()I");
    e.m_buttons = mid(m, "getButtonState", "()I");
    e.m_edge = mid(m, "getEdgeFlags", "()I");
    e.m_action_button = mid(m, "getActionButton", "()I");
    e.m_down_time = mid(m, "getDownTime", "()J");
    e.m_event_time_nanos = mid(m, "getEventTimeNanos", "()J");
    e.m_x_prec = mid(m, "getXPrecision", "()F");
    e.m_y_prec = mid(m, "getYPrecision", "()F");
    e.m_count = mid(m, "getPointerCount", "()I");
    e.m_pointer_id = mid(m, "getPointerId", "(I)I");
    e.m_tool_type = mid(m, "getToolType", "(I)I");
    e.m_axis = mid(m, "getAxisValue", "(II)F");
    e.m_raw_x = mid(m, "getRawX", "(I)F");
    e.m_raw_y = mid(m, "getRawY", "(I)F");
    e.m_x = mid(m, "getX", "(I)F");
    e.m_y = mid(m, "getY", "(I)F");
    e.ok = e.k_device_id && e.k_source && e.k_action && e.k_flags && e.k_key_code && e.k_scan_code && e.k_meta &&
           e.k_repeat && e.k_down_time && e.k_event_time && e.m_device_id && e.m_source && e.m_action && e.m_flags &&
           e.m_meta && e.m_buttons && e.m_edge && e.m_down_time && e.m_event_time_nanos && e.m_x_prec && e.m_y_prec &&
           e.m_count && e.m_pointer_id && e.m_tool_type && e.m_axis && e.m_x && e.m_y;
    if (!e.ok) std::fprintf(stderr, "[win.apk] NativeActivity: KeyEvent / MotionEvent getters missing\n");
    env->DeleteLocalRef(k);
    env->DeleteLocalRef(m);
}

jlong JNICALL IQ_nativeSendKeyEvent(JNIEnv* env, jclass, jlong ptr, jobject event, jboolean predispatch) {
    auto* q = reinterpret_cast<InputQueue*>(ptr);
    std::call_once(g_em_once, init_event_methods, env);
    if (!q || !event || !g_em.ok) return -1;
    auto* ev = new InputEvent();
    ev->type = kTypeKey;
    ev->device_id = env->CallIntMethod(event, g_em.k_device_id);
    ev->source = env->CallIntMethod(event, g_em.k_source);
    ev->action = env->CallIntMethod(event, g_em.k_action);
    ev->flags = env->CallIntMethod(event, g_em.k_flags);
    ev->key_code = env->CallIntMethod(event, g_em.k_key_code);
    ev->scan_code = env->CallIntMethod(event, g_em.k_scan_code);
    ev->meta_state = env->CallIntMethod(event, g_em.k_meta);
    ev->repeat_count = env->CallIntMethod(event, g_em.k_repeat);
    // Java KeyEvent times are uptimeMillis; the NDK's are nanoseconds.
    ev->down_time_ns = static_cast<int64_t>(env->CallLongMethod(event, g_em.k_down_time)) * 1000000LL;
    ev->event_time_ns = static_cast<int64_t>(env->CallLongMethod(event, g_em.k_event_time)) * 1000000LL;
    if (clear(env)) {
        delete ev;
        return -1;
    }
    if (predispatch) ev->flags |= kFlagPredispatch;
    ev->id = g_next_event_id.fetch_add(1);
    const jlong id = ev->id;
    enqueue(q, ev);
    return id;
}

jlong JNICALL IQ_nativeSendMotionEvent(JNIEnv* env, jclass, jlong ptr, jobject event) {
    auto* q = reinterpret_cast<InputQueue*>(ptr);
    std::call_once(g_em_once, init_event_methods, env);
    if (!q || !event || !g_em.ok) return -1;
    auto* ev = new InputEvent();
    ev->type = kTypeMotion;
    ev->device_id = env->CallIntMethod(event, g_em.m_device_id);
    ev->source = env->CallIntMethod(event, g_em.m_source);
    ev->action = env->CallIntMethod(event, g_em.m_action);
    ev->flags = env->CallIntMethod(event, g_em.m_flags);
    ev->meta_state = env->CallIntMethod(event, g_em.m_meta);
    ev->button_state = env->CallIntMethod(event, g_em.m_buttons);
    ev->edge_flags = env->CallIntMethod(event, g_em.m_edge);
    ev->action_button = g_em.m_action_button ? env->CallIntMethod(event, g_em.m_action_button) : 0;
    ev->down_time_ns = static_cast<int64_t>(env->CallLongMethod(event, g_em.m_down_time)) * 1000000LL;
    ev->event_time_ns = static_cast<int64_t>(env->CallLongMethod(event, g_em.m_event_time_nanos));
    ev->x_precision = env->CallFloatMethod(event, g_em.m_x_prec);
    ev->y_precision = env->CallFloatMethod(event, g_em.m_y_prec);
    int n = env->CallIntMethod(event, g_em.m_count);
    if (n < 0) n = 0;
    if (n > kMaxPointers) n = kMaxPointers;
    ev->pointer_count = n;
    // Joystick and gamepad events carry the generic axes; pointer events only
    // the nine per-pointer coordinates (the rest stay 0, as a touchscreen's do).
    const bool all_axes = (ev->source & kSourceClassJoystick) != 0;
    for (int i = 0; i < n; ++i) {
        ev->pointer_id[i] = env->CallIntMethod(event, g_em.m_pointer_id, static_cast<jint>(i));
        ev->tool_type[i] = env->CallIntMethod(event, g_em.m_tool_type, static_cast<jint>(i));
        const int last = all_axes ? kAxes : kAxisOrientation + 1;
        for (int a = 0; a < last; ++a) {
            ev->axes[i][a] = env->CallFloatMethod(event, g_em.m_axis, static_cast<jint>(a), static_cast<jint>(i));
        }
        ev->axes[i][kAxisX] = env->CallFloatMethod(event, g_em.m_x, static_cast<jint>(i));
        ev->axes[i][kAxisY] = env->CallFloatMethod(event, g_em.m_y, static_cast<jint>(i));
        ev->raw_x[i] = g_em.m_raw_x ? env->CallFloatMethod(event, g_em.m_raw_x, static_cast<jint>(i)) : ev->axes[i][kAxisX];
        ev->raw_y[i] = g_em.m_raw_y ? env->CallFloatMethod(event, g_em.m_raw_y, static_cast<jint>(i)) : ev->axes[i][kAxisY];
    }
    if (clear(env)) {
        delete ev;
        return -1;
    }
    if (n > 0) {
        // getX = raw + offset: the window is neither scaled nor rotated.
        ev->x_offset = ev->axes[0][kAxisX] - ev->raw_x[0];
        ev->y_offset = ev->axes[0][kAxisY] - ev->raw_y[0];
    }
    ev->id = g_next_event_id.fetch_add(1);
    const jlong id = ev->id;
    enqueue(q, ev);
    return id;
}

// The Java InputQueue behind a native queue gets finishInputEvent on its own
// thread (InputQueue.macapkPostFinish posts it there).
void finish_to_java(InputQueue* q, int64_t id, bool handled) {
    if (!q || !q->weak_queue || !g_weak_get || !g_queue_post_finish) return;
    EnvGuard g;
    JNIEnv* env = g.env;
    if (!env) return;
    jobject queue = env->CallObjectMethod(q->weak_queue, g_weak_get);
    if (clear(env)) return;
    if (!queue) {
        std::fprintf(stderr, "[win.apk] NativeActivity: InputQueue was finalized without being disposed\n");
        return;
    }
    env->CallVoidMethod(queue, g_queue_post_finish, static_cast<jlong>(id), static_cast<jboolean>(handled ? JNI_TRUE : JNI_FALSE));
    clear(env);
    env->DeleteLocalRef(queue);
}

constexpr const char* kActivity = "android.app.NativeActivity";
constexpr const char* kQueue = "android.view.InputQueue";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kActivity, "loadNativeCode",
                       "(Ljava/lang/String;Ljava/lang/String;Landroid/os/MessageQueue;Ljava/lang/String;"
                       "Ljava/lang/String;Ljava/lang/String;ILandroid/content/res/AssetManager;[B"
                       "Ljava/lang/ClassLoader;Ljava/lang/String;)J",
                       NA_loadNativeCode),
    WINAPK_HOST_NATIVE(kActivity, "getDlError", "()Ljava/lang/String;", NA_getDlError),
    WINAPK_HOST_NATIVE(kActivity, "unloadNativeCode", "(J)V", NA_unloadNativeCode),
    WINAPK_HOST_NATIVE(kActivity, "onStartNative", "(J)V", NA_onStart),
    WINAPK_HOST_NATIVE(kActivity, "onResumeNative", "(J)V", NA_onResume),
    WINAPK_HOST_NATIVE(kActivity, "onSaveInstanceStateNative", "(J)[B", NA_onSaveInstanceState),
    WINAPK_HOST_NATIVE(kActivity, "onPauseNative", "(J)V", NA_onPause),
    WINAPK_HOST_NATIVE(kActivity, "onStopNative", "(J)V", NA_onStop),
    WINAPK_HOST_NATIVE(kActivity, "onConfigurationChangedNative", "(J)V", NA_onConfigurationChanged),
    WINAPK_HOST_NATIVE(kActivity, "onLowMemoryNative", "(J)V", NA_onLowMemory),
    WINAPK_HOST_NATIVE(kActivity, "onWindowFocusChangedNative", "(JZ)V", NA_onWindowFocusChanged),
    WINAPK_HOST_NATIVE(kActivity, "onSurfaceCreatedNative", "(JLandroid/view/Surface;)V", NA_onSurfaceCreated),
    WINAPK_HOST_NATIVE(kActivity, "onSurfaceChangedNative", "(JLandroid/view/Surface;III)V", NA_onSurfaceChanged),
    WINAPK_HOST_NATIVE(kActivity, "onSurfaceRedrawNeededNative", "(JLandroid/view/Surface;)V",
                       NA_onSurfaceRedrawNeeded),
    WINAPK_HOST_NATIVE(kActivity, "onSurfaceDestroyedNative", "(J)V", NA_onSurfaceDestroyed),
    WINAPK_HOST_NATIVE(kActivity, "onInputQueueCreatedNative", "(JJ)V", NA_onInputQueueCreated),
    WINAPK_HOST_NATIVE(kActivity, "onInputQueueDestroyedNative", "(JJ)V", NA_onInputQueueDestroyed),
    WINAPK_HOST_NATIVE(kActivity, "onContentRectChangedNative", "(JIIII)V", NA_onContentRectChanged),
    WINAPK_HOST_NATIVE(kQueue, "nativeInit", "(Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;)J",
                       IQ_nativeInit),
    WINAPK_HOST_NATIVE(kQueue, "nativeDispose", "(J)V", IQ_nativeDispose),
    WINAPK_HOST_NATIVE(kQueue, "nativeSendKeyEvent", "(JLandroid/view/KeyEvent;Z)J", IQ_nativeSendKeyEvent),
    WINAPK_HOST_NATIVE(kQueue, "nativeSendMotionEvent", "(JLandroid/view/MotionEvent;)J", IQ_nativeSendMotionEvent),
};

// ---- NDK: android/native_activity.h (native_activity.cpp) ------------------

WINAPK_GUEST_ABI void ANativeActivity_finish(ANativeActivity* a) { post_work(a, kCmdFinish); }
WINAPK_GUEST_ABI void ANativeActivity_setWindowFormat(ANativeActivity* a, int32_t format) {
    post_work(a, kCmdSetWindowFormat, format);
}
WINAPK_GUEST_ABI void ANativeActivity_setWindowFlags(ANativeActivity* a, uint32_t add, uint32_t remove) {
    post_work(a, kCmdSetWindowFlags, static_cast<int32_t>(add), static_cast<int32_t>(add | remove));
}
WINAPK_GUEST_ABI void ANativeActivity_showSoftInput(ANativeActivity* a, uint32_t flags) {
    post_work(a, kCmdShowSoftInput, static_cast<int32_t>(flags));
}
WINAPK_GUEST_ABI void ANativeActivity_hideSoftInput(ANativeActivity* a, uint32_t flags) {
    post_work(a, kCmdHideSoftInput, static_cast<int32_t>(flags));
}

// ---- NDK: android/input.h, AInputQueue ---------------------------------------

WINAPK_GUEST_ABI void AInputQueue_attachLooper(void* queue, void* looper, int ident, void* callback, void* data) {
    auto* q = static_cast<InputQueue*>(queue);
    if (!q || !looper) return;
    {
        std::lock_guard<std::mutex> hold(q->lock);
        if (q->looper == looper) return;
        q->looper = looper;
    }
    shim_fd().looper_add_fd(looper, q->fd, ident, kLooperEventInput, callback, data);
    std::fprintf(stderr, "[win.apk] NativeActivity: AInputQueue_attachLooper queue=%p looper=%p ident=%d\n", queue,
                 looper, ident);
}

WINAPK_GUEST_ABI void AInputQueue_detachLooper(void* queue) {
    auto* q = static_cast<InputQueue*>(queue);
    if (!q) return;
    void* looper;
    {
        std::lock_guard<std::mutex> hold(q->lock);
        looper = q->looper;
        q->looper = nullptr;
    }
    if (looper) shim_fd().looper_remove_fd(looper, q->fd);
}

WINAPK_GUEST_ABI int32_t AInputQueue_hasEvents(void* queue) {
    auto* q = static_cast<InputQueue*>(queue);
    if (!q) return -22;  // -EINVAL
    std::lock_guard<std::mutex> hold(q->lock);
    return q->pending.empty() ? 0 : 1;
}

WINAPK_GUEST_ABI int32_t AInputQueue_getEvent(void* queue, void** out_event) {
    auto* q = static_cast<InputQueue*>(queue);
    if (out_event) *out_event = nullptr;
    if (!q) return -22;
    InputEvent* ev = nullptr;
    std::lock_guard<std::mutex> hold(q->lock);
    if (!q->pending.empty()) {
        ev = q->pending.front();
        q->pending.pop_front();
    }
    if (q->pending.empty() && q->fd >= 0) {
        uint64_t drained;
        shim_fd().read(q->fd, &drained, sizeof(drained));
    }
    if (out_event) *out_event = ev;
    if (ev) {
        static std::atomic<int> n{0};
        if (n.fetch_add(1) < 16) {
            std::fprintf(stderr, "[win.apk] NativeActivity: AInputQueue_getEvent type=%d action=0x%x source=0x%x pointers=%d\n",
                         ev->type, ev->action, ev->source, ev->pointer_count);
        }
    }
    return ev ? 0 : -11;  // -EAGAIN: would block
}

WINAPK_GUEST_ABI void AInputQueue_finishEvent(void* queue, void* event, int handled) {
    auto* q = static_cast<InputQueue*>(queue);
    auto* ev = static_cast<InputEvent*>(event);
    if (!ev) return;
    const int64_t id = ev->id;
    delete ev;
    finish_to_java(q, id, handled != 0);
}

WINAPK_GUEST_ABI int32_t AInputQueue_preDispatchEvent(void* queue, void* event) {
    auto* ev = static_cast<InputEvent*>(event);
    if (ev && ev->type == kTypeKey && (ev->flags & kFlagPredispatch)) {
        AInputQueue_finishEvent(queue, event, 0);
        return 1;
    }
    return 0;
}

// ---- NDK: AInputEvent / AKeyEvent / AMotionEvent accessors -------------------

const InputEvent* E(const void* e) { return static_cast<const InputEvent*>(e); }
bool index_ok(const void* e, size_t i) { return e && i < static_cast<size_t>(E(e)->pointer_count); }
float axis_of(const void* e, int32_t axis, size_t i) {
    if (!index_ok(e, i) || axis < 0 || axis >= kAxes) return 0.0f;
    return E(e)->axes[i][axis];
}

WINAPK_GUEST_ABI int32_t AInputEvent_getType(const void* e) { return e ? E(e)->type : 0; }
WINAPK_GUEST_ABI int32_t AInputEvent_getDeviceId(const void* e) { return e ? E(e)->device_id : 0; }
WINAPK_GUEST_ABI int32_t AInputEvent_getSource(const void* e) { return e ? E(e)->source : 0; }
WINAPK_GUEST_ABI int32_t AInputEvent_getDisplayId(const void* e) { return e ? E(e)->display_id : 0; }
// Only events from AKeyEvent_fromJava / AMotionEvent_fromJava may be released;
// none are produced here.
WINAPK_GUEST_ABI void AInputEvent_release(const void*) {}

WINAPK_GUEST_ABI int32_t AKeyEvent_getAction(const void* e) { return e ? E(e)->action : 0; }
WINAPK_GUEST_ABI int32_t AKeyEvent_getFlags(const void* e) { return e ? E(e)->flags : 0; }
WINAPK_GUEST_ABI int32_t AKeyEvent_getKeyCode(const void* e) { return e ? E(e)->key_code : 0; }
WINAPK_GUEST_ABI int32_t AKeyEvent_getScanCode(const void* e) { return e ? E(e)->scan_code : 0; }
WINAPK_GUEST_ABI int32_t AKeyEvent_getMetaState(const void* e) { return e ? E(e)->meta_state : 0; }
WINAPK_GUEST_ABI int32_t AKeyEvent_getRepeatCount(const void* e) { return e ? E(e)->repeat_count : 0; }
WINAPK_GUEST_ABI int64_t AKeyEvent_getDownTime(const void* e) { return e ? E(e)->down_time_ns : 0; }
WINAPK_GUEST_ABI int64_t AKeyEvent_getEventTime(const void* e) { return e ? E(e)->event_time_ns : 0; }

WINAPK_GUEST_ABI int32_t AMotionEvent_getAction(const void* e) { return e ? E(e)->action : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getFlags(const void* e) { return e ? E(e)->flags : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getMetaState(const void* e) { return e ? E(e)->meta_state : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getButtonState(const void* e) { return e ? E(e)->button_state : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getEdgeFlags(const void* e) { return e ? E(e)->edge_flags : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getActionButton(const void* e) { return e ? E(e)->action_button : 0; }
WINAPK_GUEST_ABI int32_t AMotionEvent_getClassification(const void*) { return 0; }
WINAPK_GUEST_ABI int64_t AMotionEvent_getDownTime(const void* e) { return e ? E(e)->down_time_ns : 0; }
WINAPK_GUEST_ABI int64_t AMotionEvent_getEventTime(const void* e) { return e ? E(e)->event_time_ns : 0; }
WINAPK_GUEST_ABI float AMotionEvent_getXOffset(const void* e) { return e ? E(e)->x_offset : 0; }
WINAPK_GUEST_ABI float AMotionEvent_getYOffset(const void* e) { return e ? E(e)->y_offset : 0; }
WINAPK_GUEST_ABI float AMotionEvent_getXPrecision(const void* e) { return e ? E(e)->x_precision : 0; }
WINAPK_GUEST_ABI float AMotionEvent_getYPrecision(const void* e) { return e ? E(e)->y_precision : 0; }
WINAPK_GUEST_ABI size_t AMotionEvent_getPointerCount(const void* e) {
    return e ? static_cast<size_t>(E(e)->pointer_count) : 0;
}
WINAPK_GUEST_ABI int32_t AMotionEvent_getPointerId(const void* e, size_t i) {
    return index_ok(e, i) ? E(e)->pointer_id[i] : -1;
}
WINAPK_GUEST_ABI int32_t AMotionEvent_getToolType(const void* e, size_t i) {
    return index_ok(e, i) ? E(e)->tool_type[i] : 0;
}
WINAPK_GUEST_ABI float AMotionEvent_getRawX(const void* e, size_t i) { return index_ok(e, i) ? E(e)->raw_x[i] : 0.0f; }
WINAPK_GUEST_ABI float AMotionEvent_getRawY(const void* e, size_t i) { return index_ok(e, i) ? E(e)->raw_y[i] : 0.0f; }
WINAPK_GUEST_ABI float AMotionEvent_getX(const void* e, size_t i) { return axis_of(e, kAxisX, i); }
WINAPK_GUEST_ABI float AMotionEvent_getY(const void* e, size_t i) { return axis_of(e, kAxisY, i); }
WINAPK_GUEST_ABI float AMotionEvent_getPressure(const void* e, size_t i) { return axis_of(e, kAxisPressure, i); }
WINAPK_GUEST_ABI float AMotionEvent_getSize(const void* e, size_t i) { return axis_of(e, kAxisSize, i); }
WINAPK_GUEST_ABI float AMotionEvent_getTouchMajor(const void* e, size_t i) { return axis_of(e, kAxisTouchMajor, i); }
WINAPK_GUEST_ABI float AMotionEvent_getTouchMinor(const void* e, size_t i) { return axis_of(e, kAxisTouchMinor, i); }
WINAPK_GUEST_ABI float AMotionEvent_getToolMajor(const void* e, size_t i) { return axis_of(e, kAxisToolMajor, i); }
WINAPK_GUEST_ABI float AMotionEvent_getToolMinor(const void* e, size_t i) { return axis_of(e, kAxisToolMinor, i); }
WINAPK_GUEST_ABI float AMotionEvent_getOrientation(const void* e, size_t i) { return axis_of(e, kAxisOrientation, i); }
WINAPK_GUEST_ABI float AMotionEvent_getAxisValue(const void* e, int32_t axis, size_t i) { return axis_of(e, axis, i); }
// No batched history is carried: an event with history size 0.
WINAPK_GUEST_ABI size_t AMotionEvent_getHistorySize(const void*) { return 0; }
WINAPK_GUEST_ABI int64_t AMotionEvent_getHistoricalEventTime(const void*, size_t) { return 0; }
#define WINAPK_NA_HIST(name) \
    WINAPK_GUEST_ABI float AMotionEvent_getHistorical##name(const void*, size_t, size_t) { return 0.0f; }
WINAPK_NA_HIST(RawX)
WINAPK_NA_HIST(RawY)
WINAPK_NA_HIST(X)
WINAPK_NA_HIST(Y)
WINAPK_NA_HIST(Pressure)
WINAPK_NA_HIST(Size)
WINAPK_NA_HIST(TouchMajor)
WINAPK_NA_HIST(TouchMinor)
WINAPK_NA_HIST(ToolMajor)
WINAPK_NA_HIST(ToolMinor)
WINAPK_NA_HIST(Orientation)
#undef WINAPK_NA_HIST
WINAPK_GUEST_ABI float AMotionEvent_getHistoricalAxisValue(const void*, int32_t, size_t, size_t) { return 0.0f; }

// ---- NDK: android/configuration.h --------------------------------------------
//
// Values from <android/configuration.h>. The opaque AConfiguration is the
// runtime's own struct; apps only ever hold the pointer.

enum {
    kOrientationAny = 0,
    kOrientationPort = 1,
    kOrientationLand = 2,
    kTouchscreenFinger = 3,
    kDensityDefault = 160,
    kKeyboardQwerty = 2,
    kNavigationNonav = 1,
    kKeysHiddenNo = 1,
    kNavHiddenYes = 2,
    kScreenSizeNormal = 2,
    kScreenLongYes = 2,
    kUiModeTypeNormal = 1,
    kUiModeNightNo = 1,
    kLayoutDirLtr = 1,
};

struct Configuration {
    int32_t mcc = 0, mnc = 0;
    char language[2] = {'e', 'n'};
    char country[2] = {'U', 'S'};
    int32_t orientation = kOrientationAny;
    int32_t touchscreen = kTouchscreenFinger;
    int32_t density = kDensityDefault;
    // Windows has a physical keyboard, as the Java Configuration says
    // (keyboard = QWERTY, keyboardHidden = NO).
    int32_t keyboard = kKeyboardQwerty;
    int32_t navigation = kNavigationNonav;
    int32_t keys_hidden = kKeysHiddenNo;
    int32_t nav_hidden = kNavHiddenYes;
    int32_t sdk_version = 29;
    int32_t screen_size = kScreenSizeNormal;
    int32_t screen_long = kScreenLongYes;
    int32_t ui_mode_type = kUiModeTypeNormal;
    int32_t ui_mode_night = kUiModeNightNo;
    int32_t layout_direction = kLayoutDirLtr;
    int32_t screen_width_dp = 0, screen_height_dp = 0, smallest_screen_width_dp = 0;
};

// The density (dpi) and SDK level the Java side reports, read once.
void java_config(int32_t* density, int32_t* sdk) {
    static int32_t s_density = 0, s_sdk = 0;
    static std::once_flag once;
    std::call_once(once, [] {
        JNIEnv* guest = winapk::guest_env_for_current_thread();
        JNIEnv* env = guest ? winapk::host_env_of(guest) : nullptr;
        if (!env) return;
        if (jclass dm = env->FindClass("android/util/DisplayMetrics")) {
            if (jmethodID m = env->GetStaticMethodID(dm, "macapkDeviceDensity", "()F")) {
                const float d = env->CallStaticFloatMethod(dm, m);
                if (!env->ExceptionCheck() && d > 0) s_density = static_cast<int32_t>(d * 160.0f + 0.5f);
            }
            env->DeleteLocalRef(dm);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jclass v = env->FindClass("android/os/Build$VERSION")) {
            if (jfieldID f = env->GetStaticFieldID(v, "SDK_INT", "I")) s_sdk = env->GetStaticIntField(v, f);
            env->DeleteLocalRef(v);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    });
    if (s_density > 0) *density = s_density;
    if (s_sdk > 0) *sdk = s_sdk;
}

WINAPK_GUEST_ABI Configuration* AConfiguration_new() { return new Configuration(); }
WINAPK_GUEST_ABI void AConfiguration_delete(Configuration* c) { delete c; }
WINAPK_GUEST_ABI void AConfiguration_fromAssetManager(Configuration* out, void*) {
    if (!out) return;
    int w = 0, h = 0;
    phone_size_dp(&w, &h);
    if (w <= 0 || h <= 0) {
        w = 360;
        h = 800;
    }
    out->orientation = w >= h ? kOrientationLand : kOrientationPort;
    out->screen_width_dp = w;
    out->screen_height_dp = h;
    out->smallest_screen_width_dp = w < h ? w : h;
    const int long_edge = w > h ? w : h, short_edge = w < h ? w : h;
    out->screen_long = (short_edge > 0 && long_edge * 3 >= short_edge * 5) ? kScreenLongYes : 0;
    java_config(&out->density, &out->sdk_version);
}
WINAPK_GUEST_ABI void AConfiguration_copy(Configuration* dest, Configuration* src) {
    if (dest && src) *dest = *src;
}
WINAPK_GUEST_ABI void AConfiguration_getLanguage(Configuration* c, char* out) {
    if (c && out) std::memcpy(out, c->language, 2);
}
WINAPK_GUEST_ABI void AConfiguration_setLanguage(Configuration* c, const char* v) {
    if (c && v) std::memcpy(c->language, v, 2);
}
WINAPK_GUEST_ABI void AConfiguration_getCountry(Configuration* c, char* out) {
    if (c && out) std::memcpy(out, c->country, 2);
}
WINAPK_GUEST_ABI void AConfiguration_setCountry(Configuration* c, const char* v) {
    if (c && v) std::memcpy(c->country, v, 2);
}
#define WINAPK_ACONF(Name, field)                                                                   \
    WINAPK_GUEST_ABI int32_t AConfiguration_get##Name(Configuration* c) { return c ? c->field : 0; } \
    WINAPK_GUEST_ABI void AConfiguration_set##Name(Configuration* c, int32_t v) {                   \
        if (c) c->field = v;                                                                        \
    }
WINAPK_ACONF(Mcc, mcc)
WINAPK_ACONF(Mnc, mnc)
WINAPK_ACONF(Orientation, orientation)
WINAPK_ACONF(Touchscreen, touchscreen)
WINAPK_ACONF(Density, density)
WINAPK_ACONF(Keyboard, keyboard)
WINAPK_ACONF(Navigation, navigation)
WINAPK_ACONF(KeysHidden, keys_hidden)
WINAPK_ACONF(NavHidden, nav_hidden)
WINAPK_ACONF(SdkVersion, sdk_version)
WINAPK_ACONF(ScreenSize, screen_size)
WINAPK_ACONF(ScreenLong, screen_long)
WINAPK_ACONF(UiModeType, ui_mode_type)
WINAPK_ACONF(UiModeNight, ui_mode_night)
WINAPK_ACONF(LayoutDirection, layout_direction)
WINAPK_ACONF(ScreenWidthDp, screen_width_dp)
WINAPK_ACONF(ScreenHeightDp, screen_height_dp)
WINAPK_ACONF(SmallestScreenWidthDp, smallest_screen_width_dp)
#undef WINAPK_ACONF
WINAPK_GUEST_ABI int32_t AConfiguration_getScreenRound(Configuration*) { return 1; }  // ACONFIGURATION_SCREENROUND_NO
WINAPK_GUEST_ABI void AConfiguration_setScreenRound(Configuration*, int32_t) {}
// diff and isBetterThan need a resource-table comparison; "same" and "no"
// are what AOSP answers for identical configurations.
WINAPK_GUEST_ABI int32_t AConfiguration_diff(Configuration*, Configuration*) { return 0; }
WINAPK_GUEST_ABI int32_t AConfiguration_match(Configuration*, Configuration*) { return 1; }
WINAPK_GUEST_ABI int32_t AConfiguration_isBetterThan(Configuration*, Configuration*, Configuration*) { return 0; }

#define WINAPK_NA(fn) {#fn, reinterpret_cast<void*>(&fn)}
#define WINAPK_NA_CONF(Name) WINAPK_NA(AConfiguration_get##Name), WINAPK_NA(AConfiguration_set##Name)
const std::unordered_map<std::string, void*> kSymbols = {
    WINAPK_NA(ANativeActivity_finish), WINAPK_NA(ANativeActivity_setWindowFormat),
    WINAPK_NA(ANativeActivity_setWindowFlags), WINAPK_NA(ANativeActivity_showSoftInput),
    WINAPK_NA(ANativeActivity_hideSoftInput),
    WINAPK_NA(AInputQueue_attachLooper), WINAPK_NA(AInputQueue_detachLooper), WINAPK_NA(AInputQueue_hasEvents),
    WINAPK_NA(AInputQueue_getEvent), WINAPK_NA(AInputQueue_preDispatchEvent), WINAPK_NA(AInputQueue_finishEvent),
    WINAPK_NA(AInputEvent_getType), WINAPK_NA(AInputEvent_getDeviceId), WINAPK_NA(AInputEvent_getSource),
    WINAPK_NA(AInputEvent_getDisplayId), WINAPK_NA(AInputEvent_release),
    WINAPK_NA(AKeyEvent_getAction), WINAPK_NA(AKeyEvent_getFlags), WINAPK_NA(AKeyEvent_getKeyCode),
    WINAPK_NA(AKeyEvent_getScanCode), WINAPK_NA(AKeyEvent_getMetaState), WINAPK_NA(AKeyEvent_getRepeatCount),
    WINAPK_NA(AKeyEvent_getDownTime), WINAPK_NA(AKeyEvent_getEventTime),
    WINAPK_NA(AMotionEvent_getAction), WINAPK_NA(AMotionEvent_getFlags), WINAPK_NA(AMotionEvent_getMetaState),
    WINAPK_NA(AMotionEvent_getButtonState), WINAPK_NA(AMotionEvent_getEdgeFlags),
    WINAPK_NA(AMotionEvent_getActionButton), WINAPK_NA(AMotionEvent_getClassification),
    WINAPK_NA(AMotionEvent_getDownTime), WINAPK_NA(AMotionEvent_getEventTime), WINAPK_NA(AMotionEvent_getXOffset),
    WINAPK_NA(AMotionEvent_getYOffset), WINAPK_NA(AMotionEvent_getXPrecision), WINAPK_NA(AMotionEvent_getYPrecision),
    WINAPK_NA(AMotionEvent_getPointerCount), WINAPK_NA(AMotionEvent_getPointerId), WINAPK_NA(AMotionEvent_getToolType),
    WINAPK_NA(AMotionEvent_getRawX), WINAPK_NA(AMotionEvent_getRawY), WINAPK_NA(AMotionEvent_getX),
    WINAPK_NA(AMotionEvent_getY), WINAPK_NA(AMotionEvent_getPressure), WINAPK_NA(AMotionEvent_getSize),
    WINAPK_NA(AMotionEvent_getTouchMajor), WINAPK_NA(AMotionEvent_getTouchMinor), WINAPK_NA(AMotionEvent_getToolMajor),
    WINAPK_NA(AMotionEvent_getToolMinor), WINAPK_NA(AMotionEvent_getOrientation), WINAPK_NA(AMotionEvent_getAxisValue),
    WINAPK_NA(AMotionEvent_getHistorySize), WINAPK_NA(AMotionEvent_getHistoricalEventTime),
    WINAPK_NA(AMotionEvent_getHistoricalRawX), WINAPK_NA(AMotionEvent_getHistoricalRawY),
    WINAPK_NA(AMotionEvent_getHistoricalX), WINAPK_NA(AMotionEvent_getHistoricalY),
    WINAPK_NA(AMotionEvent_getHistoricalPressure), WINAPK_NA(AMotionEvent_getHistoricalSize),
    WINAPK_NA(AMotionEvent_getHistoricalTouchMajor), WINAPK_NA(AMotionEvent_getHistoricalTouchMinor),
    WINAPK_NA(AMotionEvent_getHistoricalToolMajor), WINAPK_NA(AMotionEvent_getHistoricalToolMinor),
    WINAPK_NA(AMotionEvent_getHistoricalOrientation), WINAPK_NA(AMotionEvent_getHistoricalAxisValue),
    WINAPK_NA(AConfiguration_new), WINAPK_NA(AConfiguration_delete), WINAPK_NA(AConfiguration_fromAssetManager),
    WINAPK_NA(AConfiguration_copy), WINAPK_NA(AConfiguration_getLanguage), WINAPK_NA(AConfiguration_setLanguage),
    WINAPK_NA(AConfiguration_getCountry), WINAPK_NA(AConfiguration_setCountry),
    WINAPK_NA_CONF(Mcc), WINAPK_NA_CONF(Mnc), WINAPK_NA_CONF(Orientation), WINAPK_NA_CONF(Touchscreen),
    WINAPK_NA_CONF(Density), WINAPK_NA_CONF(Keyboard), WINAPK_NA_CONF(Navigation), WINAPK_NA_CONF(KeysHidden),
    WINAPK_NA_CONF(NavHidden), WINAPK_NA_CONF(SdkVersion), WINAPK_NA_CONF(ScreenSize), WINAPK_NA_CONF(ScreenLong),
    WINAPK_NA_CONF(UiModeType), WINAPK_NA_CONF(UiModeNight), WINAPK_NA_CONF(LayoutDirection),
    WINAPK_NA_CONF(ScreenWidthDp), WINAPK_NA_CONF(ScreenHeightDp), WINAPK_NA_CONF(SmallestScreenWidthDp),
    WINAPK_NA_CONF(ScreenRound),
    WINAPK_NA(AConfiguration_diff), WINAPK_NA(AConfiguration_match), WINAPK_NA(AConfiguration_isBetterThan),
};
#undef WINAPK_NA_CONF
#undef WINAPK_NA

}  // namespace

HostMethods native_activity_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

void* native_activity_resolve(const char* name) {
    auto it = kSymbols.find(name);
    return it == kSymbols.end() ? nullptr : it->second;
}

}  // namespace winapk::host
