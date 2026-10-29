// bionic_shim_nativeactivity.cpp -- android.app.NativeActivity and the NDK
// input queue: the natives of android.app.NativeActivity / android.view.InputQueue
// and the NDK functions guest code calls on them (ANativeActivity_*,
// AInputQueue_*, AInputEvent_*, AKeyEvent_*, AMotionEvent_*).
//
// WHY THIS FILE EXISTS
// --------------------
// NativeActivity is how an NDK app with no Java of its own runs
// (android_native_app_glue), and Unreal Engine 4/5's GameActivity EXTENDS it:
// Rocket League Sideswipe (com.Psyonix.RL2D) and Fortnite. Until 2026-10-02
// our android/app/NativeActivity.java was a probe stub that never loaded the
// library named by android.app.lib_name or called ANativeActivity_onCreate, so
// an Unreal game booted Java, loaded libUE4.so, and then nothing ever started
// the engine's android_main: no window, no frames, the starting background
// forever. The guest's imports of the NDK input surface (AInputQueue_*,
// AMotionEvent_*, AKeyEvent_*, ANativeActivity_setWindowFormat) were
// unresolved.
//
// WHAT IT MIRRORS
// ---------------
// frameworks/base core/jni/android_app_NativeActivity.cpp,
// core/jni/android_view_InputQueue.cpp, native/android/native_activity.cpp and
// native/android/input.cpp (android16-release), function for function.
// Deviations, each forced by the host:
//   - The library is opened with dlopen on the path BaseDexClassLoader.
//     findLibrary returned (our translated wrapper dylib, whose constructor maps
//     the ELF), and the entry point is dlsym'd from it -- AOSP's
//     OpenNativeLibrary + dlsym. Neither runs JNI_OnLoad, on either platform.
//   - The ANativeWindow is the one __macapk_arm64_ANativeWindow_fromSurface
//     answers for the Surface (android_view_Surface_getNativeWindow on AOSP).
//   - The ANativeActivity_finish & co work items are posted to the main Looper
//     from Java (NativeActivity.macapkPostWork) instead of through a pipe the
//     native main Looper polls; AInputQueue_finishEvent reaches the queue's
//     thread through InputQueue.macapkPostFinish the same way.
//   - Input events are our own struct, filled from the Java event's getters.
//     The NDK treats AInputEvent as opaque, so only the accessors below can
//     see the layout. History is not carried (getHistorySize answers 0, an
//     event with no batched samples).
//
// WHY A SEPARATE TU: the same reason as bionic_shim_gles1.cpp and the other
// self-contained shim TUs -- bionic_shim.cpp is shared by many lanes. The
// cross-TU dependencies are the exported shim entry points named in the
// extern block below, plus __macapk_looper_add_fd_plain (bionic_shim.cpp),
// which registers a looper fd WITHOUT ALooper_addFd's Unity vsync-feed hookup.
// Registration runs from __macapk_init_assets, when the shim receives the
// JavaVM on the main thread.

#include <jni.h>

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>

extern "C" {
void*   __macapk_arm64_ANativeWindow_fromSurface(void* env, void* surface);
int32_t __macapk_arm64_ANativeWindow_getWidth(void* w);
int32_t __macapk_arm64_ANativeWindow_getHeight(void* w);
int     __macapk_arm64_ALooper_removeFd(void* looper, int fd);
int     __macapk_looper_add_fd_plain(void* looper, int fd, int ident, int events,
                                     void* callback, void* data);
void*   AAssetManager_fromJava(JNIEnv* env, jobject assetManager);
}

namespace {

// ---------------------------------------------------------------------------
// NDK structs (android/native_activity.h, android/rect.h). Layout is the
// public ABI the guest compiled against; Apple arm64 lays these out the same.
// ---------------------------------------------------------------------------
struct ANativeActivity;
struct ARect { int32_t left, top, right, bottom; };

struct ANativeActivityCallbacks {
    void  (*onStart)(ANativeActivity* activity);
    void  (*onResume)(ANativeActivity* activity);
    void* (*onSaveInstanceState)(ANativeActivity* activity, size_t* outSize);
    void  (*onPause)(ANativeActivity* activity);
    void  (*onStop)(ANativeActivity* activity);
    void  (*onDestroy)(ANativeActivity* activity);
    void  (*onWindowFocusChanged)(ANativeActivity* activity, int hasFocus);
    void  (*onNativeWindowCreated)(ANativeActivity* activity, void* window);
    void  (*onNativeWindowResized)(ANativeActivity* activity, void* window);
    void  (*onNativeWindowRedrawNeeded)(ANativeActivity* activity, void* window);
    void  (*onNativeWindowDestroyed)(ANativeActivity* activity, void* window);
    void  (*onInputQueueCreated)(ANativeActivity* activity, void* queue);
    void  (*onInputQueueDestroyed)(ANativeActivity* activity, void* queue);
    void  (*onContentRectChanged)(ANativeActivity* activity, const ARect* rect);
    void  (*onConfigurationChanged)(ANativeActivity* activity);
    void  (*onLowMemory)(ANativeActivity* activity);
};

struct ANativeActivity {
    ANativeActivityCallbacks* callbacks;
    JavaVM*     vm;
    JNIEnv*     env;
    jobject     clazz;
    const char* internalDataPath;
    const char* externalDataPath;
    int32_t     sdkVersion;
    void*       instance;
    void*       assetManager;
    const char* obbPath;
};

using ANativeActivity_createFunc = void (*)(ANativeActivity*, void*, size_t);

// android_app_NativeActivity.cpp ActivityWork commands.
enum { CMD_FINISH = 1, CMD_SET_WINDOW_FORMAT, CMD_SET_WINDOW_FLAGS,
       CMD_SHOW_SOFT_INPUT, CMD_HIDE_SOFT_INPUT };

struct NativeCode : public ANativeActivity {
    NativeCode(void* h, ANativeActivity_createFunc f) {
        std::memset(static_cast<ANativeActivity*>(this), 0, sizeof(ANativeActivity));
        std::memset(&cbs, 0, sizeof(cbs));
        dlhandle = h;
        createActivityFunc = f;
    }
    ANativeActivityCallbacks cbs;
    void* dlhandle = nullptr;
    ANativeActivity_createFunc createActivityFunc = nullptr;
    std::string internalDataPathObj, externalDataPathObj, obbPathObj;
    void*   nativeWindow = nullptr;
    int32_t lastWindowWidth = 0, lastWindowHeight = 0;
    jobject javaAssetManager = nullptr;

    void setSurface(JNIEnv* e, jobject surface) {
        nativeWindow = surface ? __macapk_arm64_ANativeWindow_fromSurface(e, surface)
                               : nullptr;
    }
};

JavaVM*   g_vm = nullptr;
jclass    g_nativeActivityClass = nullptr;   // global ref
jmethodID g_postWork = nullptr;              // static macapkPostWork(NativeActivity,int,int,int)
jmethodID g_queuePostFinish = nullptr;       // InputQueue.macapkPostFinish(long,boolean)
jmethodID g_weakGet = nullptr;               // WeakReference.get()
thread_local std::string g_error_msg;

// JNIEnv for the calling thread. A native thread the JVM has not seen (the
// glue's android_main thread calling ANativeActivity_finish or
// AInputQueue_finishEvent) is attached for the call and detached again, like
// bionic_shim.cpp's AttachGuard.
struct EnvGuard {
    JNIEnv* env = nullptr;
    bool attached = false;
    EnvGuard() {
        if (!g_vm) return;
        jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            attached = g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        } else if (rc != JNI_OK) {
            env = nullptr;
        }
    }
    ~EnvGuard() { if (attached) g_vm->DetachCurrentThread(); }
    EnvGuard(const EnvGuard&) = delete;
    EnvGuard& operator=(const EnvGuard&) = delete;
};

void post_work(ANativeActivity* activity, int32_t cmd, int32_t arg1 = 0, int32_t arg2 = 0) {
    if (!activity || !activity->clazz || !g_nativeActivityClass || !g_postWork) return;
    EnvGuard g;
    JNIEnv* e = g.env;
    if (!e) return;
    e->CallStaticVoidMethod(g_nativeActivityClass, g_postWork, activity->clazz,
                            (jint)cmd, (jint)arg1, (jint)arg2);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

std::atomic<int> g_trace_n{0};
void trace(const char* what, const void* code) {
    if (g_trace_n.fetch_add(1) < 64)
        std::fprintf(stderr, "[nativeactivity] %s (code=%p)\n", what, code);
}

// ---------------------------------------------------------------------------
// android.app.NativeActivity natives
// ---------------------------------------------------------------------------
jlong JNICALL loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path, jstring funcName,
        jobject /*messageQueue*/, jstring internalDataDir, jstring obbDir,
        jstring externalDataDir, jint sdkVersion, jobject jAssetMgr,
        jbyteArray savedState, jobject /*classLoader*/, jstring /*libraryPath*/) {
    if (!path || !funcName) { g_error_msg = "null library path or entry point"; return 0; }
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    std::string pathCopy = pathStr ? pathStr : "";
    if (pathStr) env->ReleaseStringUTFChars(path, pathStr);

    void* handle = dlopen(pathCopy.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* err = dlerror();
        g_error_msg = err ? err : "dlopen failed";
        std::fprintf(stderr, "[nativeactivity] LoadNativeLibrary(\"%s\") failed: %s\n",
                     pathCopy.c_str(), g_error_msg.c_str());
        return 0;
    }

    const char* funcStr = env->GetStringUTFChars(funcName, nullptr);
    std::string funcCopy = funcStr ? funcStr : "";
    if (funcStr) env->ReleaseStringUTFChars(funcName, funcStr);
    void* funcPtr = dlsym(handle, funcCopy.c_str());
    if (!funcPtr) {
        const char* err = dlerror();
        g_error_msg = err ? err : "symbol not found";
        std::fprintf(stderr, "[nativeactivity] %s not found in %s: %s\n",
                     funcCopy.c_str(), pathCopy.c_str(), g_error_msg.c_str());
        return 0;
    }

    NativeCode* code = new NativeCode(handle, reinterpret_cast<ANativeActivity_createFunc>(funcPtr));
    code->callbacks = &code->cbs;
    if (env->GetJavaVM(&code->vm) < 0) {
        g_error_msg = "NativeActivity GetJavaVM failed";
        delete code;
        return 0;
    }
    code->env = env;
    code->clazz = env->NewGlobalRef(clazz);

    auto copy_string = [env](jstring s, std::string& out) {
        if (!s) return;
        const char* c = env->GetStringUTFChars(s, nullptr);
        if (c) { out = c; env->ReleaseStringUTFChars(s, c); }
    };
    copy_string(internalDataDir, code->internalDataPathObj);
    code->internalDataPath = code->internalDataPathObj.c_str();
    copy_string(externalDataDir, code->externalDataPathObj);
    code->externalDataPath = code->externalDataPathObj.c_str();
    code->sdkVersion = sdkVersion;
    code->javaAssetManager = jAssetMgr ? env->NewGlobalRef(jAssetMgr) : nullptr;
    code->assetManager = AAssetManager_fromJava(env, jAssetMgr);
    copy_string(obbDir, code->obbPathObj);
    code->obbPath = code->obbPathObj.c_str();

    jbyte* rawSavedState = nullptr;
    jsize rawSavedSize = 0;
    if (savedState) {
        rawSavedState = env->GetByteArrayElements(savedState, nullptr);
        rawSavedSize = env->GetArrayLength(savedState);
    }

    std::fprintf(stderr, "[nativeactivity] %s -> %s(%p) sdk=%d internal=%s\n",
                 pathCopy.c_str(), funcCopy.c_str(), funcPtr, (int)sdkVersion,
                 code->internalDataPath);
    code->createActivityFunc(code, rawSavedState, (size_t)rawSavedSize);
    std::fprintf(stderr, "[nativeactivity] %s returned (code=%p)\n", funcCopy.c_str(),
                 (void*)code);

    if (rawSavedState) env->ReleaseByteArrayElements(savedState, rawSavedState, 0);
    return reinterpret_cast<jlong>(code);
}

jstring JNICALL getDlError_native(JNIEnv* env, jobject) {
    jstring r = env->NewStringUTF(g_error_msg.c_str());
    g_error_msg.clear();
    return r;
}

void JNICALL unloadNativeCode_native(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    trace("onDestroy", code);
    if (code->cbs.onDestroy) code->cbs.onDestroy(code);
    if (code->clazz) env->DeleteGlobalRef(code->clazz);
    if (code->javaAssetManager) env->DeleteGlobalRef(code->javaAssetManager);
    code->setSurface(env, nullptr);
    // AOSP keeps the library loaded ("for now don't unload"); so do we.
    delete code;
}

#define NA_SIMPLE(fnName, cb, label)                                         \
    void JNICALL fnName(JNIEnv*, jobject, jlong handle) {                    \
        if (!handle) return;                                                 \
        NativeCode* code = reinterpret_cast<NativeCode*>(handle);            \
        trace(label, code);                                                  \
        if (code->cbs.cb) code->cbs.cb(code);                                \
    }
NA_SIMPLE(onStart_native, onStart, "onStart")
NA_SIMPLE(onResume_native, onResume, "onResume")
NA_SIMPLE(onPause_native, onPause, "onPause")
NA_SIMPLE(onStop_native, onStop, "onStop")
NA_SIMPLE(onConfigurationChanged_native, onConfigurationChanged, "onConfigurationChanged")
NA_SIMPLE(onLowMemory_native, onLowMemory, "onLowMemory")
#undef NA_SIMPLE

jbyteArray JNICALL onSaveInstanceState_native(JNIEnv* env, jobject, jlong handle) {
    jbyteArray array = nullptr;
    if (!handle) return nullptr;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    if (code->cbs.onSaveInstanceState) {
        size_t len = 0;
        jbyte* state = static_cast<jbyte*>(code->cbs.onSaveInstanceState(code, &len));
        if (len > 0) {
            array = env->NewByteArray((jsize)len);
            if (array) env->SetByteArrayRegion(array, 0, (jsize)len, state);
        }
        if (state) std::free(state);
    }
    return array;
}

void JNICALL onWindowFocusChanged_native(JNIEnv*, jobject, jlong handle, jboolean focused) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    trace(focused ? "onWindowFocusChanged(1)" : "onWindowFocusChanged(0)", code);
    if (code->cbs.onWindowFocusChanged) code->cbs.onWindowFocusChanged(code, focused ? 1 : 0);
}

void JNICALL onSurfaceCreated_native(JNIEnv* env, jobject, jlong handle, jobject surface) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    code->setSurface(env, surface);
    std::fprintf(stderr, "[nativeactivity] onNativeWindowCreated window=%p (code=%p)\n",
                 code->nativeWindow, (void*)code);
    if (code->nativeWindow && code->cbs.onNativeWindowCreated)
        code->cbs.onNativeWindowCreated(code, code->nativeWindow);
}

void JNICALL onSurfaceChanged_native(JNIEnv* env, jobject, jlong handle, jobject surface,
        jint /*format*/, jint /*width*/, jint /*height*/) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    void* oldNativeWindow = code->nativeWindow;
    code->setSurface(env, surface);
    if (oldNativeWindow != code->nativeWindow) {
        if (oldNativeWindow && code->cbs.onNativeWindowDestroyed)
            code->cbs.onNativeWindowDestroyed(code, oldNativeWindow);
        if (code->nativeWindow) {
            std::fprintf(stderr, "[nativeactivity] onNativeWindowCreated window=%p "
                         "(surfaceChanged, code=%p)\n", code->nativeWindow, (void*)code);
            if (code->cbs.onNativeWindowCreated)
                code->cbs.onNativeWindowCreated(code, code->nativeWindow);
            code->lastWindowWidth  = __macapk_arm64_ANativeWindow_getWidth(code->nativeWindow);
            code->lastWindowHeight = __macapk_arm64_ANativeWindow_getHeight(code->nativeWindow);
        }
    } else if (code->nativeWindow) {
        // Maybe it resized?
        int32_t newWidth  = __macapk_arm64_ANativeWindow_getWidth(code->nativeWindow);
        int32_t newHeight = __macapk_arm64_ANativeWindow_getHeight(code->nativeWindow);
        if (newWidth != code->lastWindowWidth || newHeight != code->lastWindowHeight) {
            // AOSP compares against lastWindow* without updating them here.
            if (code->cbs.onNativeWindowResized)
                code->cbs.onNativeWindowResized(code, code->nativeWindow);
        }
    }
}

void JNICALL onSurfaceRedrawNeeded_native(JNIEnv*, jobject, jlong handle, jobject /*surface*/) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    if (code->nativeWindow && code->cbs.onNativeWindowRedrawNeeded)
        code->cbs.onNativeWindowRedrawNeeded(code, code->nativeWindow);
}

void JNICALL onSurfaceDestroyed_native(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    trace("onNativeWindowDestroyed", code);
    if (code->nativeWindow && code->cbs.onNativeWindowDestroyed)
        code->cbs.onNativeWindowDestroyed(code, code->nativeWindow);
    code->setSurface(env, nullptr);
}

void JNICALL onInputQueueCreated_native(JNIEnv*, jobject, jlong handle, jlong queuePtr) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    trace("onInputQueueCreated", code);
    if (code->cbs.onInputQueueCreated)
        code->cbs.onInputQueueCreated(code, reinterpret_cast<void*>(queuePtr));
}

void JNICALL onInputQueueDestroyed_native(JNIEnv*, jobject, jlong handle, jlong queuePtr) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    trace("onInputQueueDestroyed", code);
    if (code->cbs.onInputQueueDestroyed)
        code->cbs.onInputQueueDestroyed(code, reinterpret_cast<void*>(queuePtr));
}

void JNICALL onContentRectChanged_native(JNIEnv*, jobject, jlong handle,
        jint x, jint y, jint w, jint h) {
    if (!handle) return;
    NativeCode* code = reinterpret_cast<NativeCode*>(handle);
    if (code->cbs.onContentRectChanged) {
        ARect rect{x, y, x + w, y + h};
        code->cbs.onContentRectChanged(code, &rect);
    }
}

const JNINativeMethod kNativeActivityMethods[] = {
    {const_cast<char*>("loadNativeCode"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Landroid/os/MessageQueue;"
                       "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I"
                       "Landroid/content/res/AssetManager;[BLjava/lang/ClassLoader;"
                       "Ljava/lang/String;)J"),
     reinterpret_cast<void*>(loadNativeCode_native)},
    {const_cast<char*>("getDlError"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(getDlError_native)},
    {const_cast<char*>("unloadNativeCode"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(unloadNativeCode_native)},
    {const_cast<char*>("onStartNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onStart_native)},
    {const_cast<char*>("onResumeNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onResume_native)},
    {const_cast<char*>("onSaveInstanceStateNative"), const_cast<char*>("(J)[B"),
     reinterpret_cast<void*>(onSaveInstanceState_native)},
    {const_cast<char*>("onPauseNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onPause_native)},
    {const_cast<char*>("onStopNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onStop_native)},
    {const_cast<char*>("onConfigurationChangedNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onConfigurationChanged_native)},
    {const_cast<char*>("onLowMemoryNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onLowMemory_native)},
    {const_cast<char*>("onWindowFocusChangedNative"), const_cast<char*>("(JZ)V"),
     reinterpret_cast<void*>(onWindowFocusChanged_native)},
    {const_cast<char*>("onSurfaceCreatedNative"), const_cast<char*>("(JLandroid/view/Surface;)V"),
     reinterpret_cast<void*>(onSurfaceCreated_native)},
    {const_cast<char*>("onSurfaceChangedNative"),
     const_cast<char*>("(JLandroid/view/Surface;III)V"),
     reinterpret_cast<void*>(onSurfaceChanged_native)},
    {const_cast<char*>("onSurfaceRedrawNeededNative"),
     const_cast<char*>("(JLandroid/view/Surface;)V"),
     reinterpret_cast<void*>(onSurfaceRedrawNeeded_native)},
    {const_cast<char*>("onSurfaceDestroyedNative"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(onSurfaceDestroyed_native)},
    {const_cast<char*>("onInputQueueCreatedNative"), const_cast<char*>("(JJ)V"),
     reinterpret_cast<void*>(onInputQueueCreated_native)},
    {const_cast<char*>("onInputQueueDestroyedNative"), const_cast<char*>("(JJ)V"),
     reinterpret_cast<void*>(onInputQueueDestroyed_native)},
    {const_cast<char*>("onContentRectChangedNative"), const_cast<char*>("(JIIII)V"),
     reinterpret_cast<void*>(onContentRectChanged_native)},
};

// ---------------------------------------------------------------------------
// Input events and the queue (android_view_InputQueue.cpp, input.cpp)
// ---------------------------------------------------------------------------
constexpr int32_t kTypeKey = 1;     // AINPUT_EVENT_TYPE_KEY
constexpr int32_t kTypeMotion = 2;  // AINPUT_EVENT_TYPE_MOTION
constexpr int32_t kFlagPredispatch = 0x20000000;  // AKEY_EVENT_FLAG_PREDISPATCH
constexpr int kMaxPointers = 16;    // MAX_POINTERS
constexpr int kAxes = 48;           // AMOTION_EVENT_AXIS_GENERIC_16 + 1
constexpr int32_t kAxisX = 0, kAxisY = 1, kAxisPressure = 2, kAxisSize = 3,
                  kAxisTouchMajor = 4, kAxisTouchMinor = 5, kAxisToolMajor = 6,
                  kAxisToolMinor = 7, kAxisOrientation = 8;

struct MacApkInputEvent {
    int32_t type = 0;
    int32_t deviceId = 0, source = 0, displayId = 0;
    int32_t action = 0, flags = 0, metaState = 0;
    int64_t downTimeNs = 0, eventTimeNs = 0;
    // key
    int32_t keyCode = 0, scanCode = 0, repeatCount = 0;
    // motion
    int32_t buttonState = 0, edgeFlags = 0, actionButton = 0;
    float   xOffset = 0, yOffset = 0, xPrecision = 1, yPrecision = 1;
    int32_t pointerCount = 0;
    int32_t pointerId[kMaxPointers] = {};
    int32_t toolType[kMaxPointers] = {};
    float   rawX[kMaxPointers] = {}, rawY[kMaxPointers] = {};
    float   axes[kMaxPointers][kAxes] = {};
    // bookkeeping
    int64_t id = 0;
};

struct MacApkInputQueue {
    int readFd = -1, writeFd = -1;
    jobject weakQueue = nullptr;   // global ref to the Java WeakReference<InputQueue>
    std::mutex mu;
    std::deque<MacApkInputEvent*> pending;
    void* looper = nullptr;        // the app looper attachLooper registered on
};

std::atomic<int64_t> g_next_event_id{1};

// The getEvent / finishEvent trace logs the first 16 events of each; a hover
// stream spends that before the first click. MACAPK_NA_INPUT_TRACE=1 logs
// every event (and the fields an engine filters on: device, tool, buttons).
bool input_trace_all() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_NA_INPUT_TRACE");
        return e && e[0] == '1';
    }();
    return on;
}

void enqueue(MacApkInputQueue* q, MacApkInputEvent* ev) {
    std::lock_guard<std::mutex> lk(q->mu);
    q->pending.push_back(ev);
    if (q->pending.size() == 1) {
        char payload = '\0';
        ssize_t r;
        do { r = ::write(q->writeFd, &payload, 1); } while (r < 0 && errno == EINTR);
    }
}

jlong JNICALL InputQueue_nativeInit(JNIEnv* env, jclass, jobject queueWeak, jobject) {
    int fds[2];
    if (::pipe(fds) != 0) {
        jclass re = env->FindClass("java/lang/RuntimeException");
        if (re) env->ThrowNew(re, "InputQueue failed to initialize");
        return 0;
    }
    ::fcntl(fds[0], F_SETFL, O_NONBLOCK);
    ::fcntl(fds[1], F_SETFL, O_NONBLOCK);
    auto* q = new MacApkInputQueue();
    q->readFd = fds[0];
    q->writeFd = fds[1];
    q->weakQueue = env->NewGlobalRef(queueWeak);
    std::fprintf(stderr, "[nativeactivity] InputQueue created %p (fd %d)\n", (void*)q, q->readFd);
    return reinterpret_cast<jlong>(q);
}

void JNICALL InputQueue_nativeDispose(JNIEnv* env, jclass, jlong ptr) {
    auto* q = reinterpret_cast<MacApkInputQueue*>(ptr);
    if (!q) return;
    void* looper;
    { std::lock_guard<std::mutex> lk(q->mu); looper = q->looper; q->looper = nullptr; }
    if (looper) __macapk_arm64_ALooper_removeFd(looper, q->readFd);
    if (q->weakQueue) env->DeleteGlobalRef(q->weakQueue);
    // Events still pending are owned by nobody now; the fds go with the queue.
    for (MacApkInputEvent* e : q->pending) delete e;
    ::close(q->readFd);
    ::close(q->writeFd);
    delete q;
}

// Lazily cached getters of android.view.KeyEvent / MotionEvent.
struct EventMethods {
    bool ok = false;
    jmethodID kDeviceId, kSource, kAction, kFlags, kKeyCode, kScanCode, kMeta, kRepeat,
              kDownTime, kEventTime;
    jmethodID mDeviceId, mSource, mAction, mFlags, mMeta, mButtons, mEdge, mDownTime,
              mEventTimeNanos, mXPrec, mYPrec, mCount, mPointerId, mToolType, mAxis,
              mRawX, mRawY, mX, mY;
};
EventMethods g_em;
std::once_flag g_em_once;

void init_event_methods(JNIEnv* env) {
    jclass k = env->FindClass("android/view/KeyEvent");
    jclass m = env->FindClass("android/view/MotionEvent");
    if (!k || !m) { env->ExceptionClear(); return; }
    auto mid = [env](jclass c, const char* n, const char* s) {
        jmethodID id = env->GetMethodID(c, n, s);
        if (!id) env->ExceptionClear();
        return id;
    };
    EventMethods& e = g_em;
    e.kDeviceId = mid(k, "getDeviceId", "()I");
    e.kSource   = mid(k, "getSource", "()I");
    e.kAction   = mid(k, "getAction", "()I");
    e.kFlags    = mid(k, "getFlags", "()I");
    e.kKeyCode  = mid(k, "getKeyCode", "()I");
    e.kScanCode = mid(k, "getScanCode", "()I");
    e.kMeta     = mid(k, "getMetaState", "()I");
    e.kRepeat   = mid(k, "getRepeatCount", "()I");
    e.kDownTime = mid(k, "getDownTime", "()J");
    e.kEventTime = mid(k, "getEventTime", "()J");
    e.mDeviceId = mid(m, "getDeviceId", "()I");
    e.mSource   = mid(m, "getSource", "()I");
    e.mAction   = mid(m, "getAction", "()I");
    e.mFlags    = mid(m, "getFlags", "()I");
    e.mMeta     = mid(m, "getMetaState", "()I");
    e.mButtons  = mid(m, "getButtonState", "()I");
    e.mEdge     = mid(m, "getEdgeFlags", "()I");
    e.mDownTime = mid(m, "getDownTime", "()J");
    e.mEventTimeNanos = mid(m, "getEventTimeNanos", "()J");
    e.mXPrec    = mid(m, "getXPrecision", "()F");
    e.mYPrec    = mid(m, "getYPrecision", "()F");
    e.mCount    = mid(m, "getPointerCount", "()I");
    e.mPointerId = mid(m, "getPointerId", "(I)I");
    e.mToolType = mid(m, "getToolType", "(I)I");
    e.mAxis     = mid(m, "getAxisValue", "(II)F");
    e.mRawX     = mid(m, "getRawX", "(I)F");
    e.mRawY     = mid(m, "getRawY", "(I)F");
    e.mX        = mid(m, "getX", "(I)F");
    e.mY        = mid(m, "getY", "(I)F");
    e.ok = e.kDeviceId && e.kSource && e.kAction && e.kFlags && e.kKeyCode && e.kScanCode
        && e.kMeta && e.kRepeat && e.kDownTime && e.kEventTime && e.mDeviceId && e.mSource
        && e.mAction && e.mFlags && e.mMeta && e.mButtons && e.mEdge && e.mDownTime
        && e.mEventTimeNanos && e.mXPrec && e.mYPrec && e.mCount && e.mPointerId
        && e.mToolType && e.mAxis && e.mX && e.mY;
    if (!e.ok) std::fprintf(stderr, "[nativeactivity] KeyEvent/MotionEvent getters missing\n");
    env->DeleteLocalRef(k);
    env->DeleteLocalRef(m);
}

jlong JNICALL InputQueue_nativeSendKeyEvent(JNIEnv* env, jclass, jlong ptr, jobject eventObj,
                                            jboolean predispatch) {
    auto* q = reinterpret_cast<MacApkInputQueue*>(ptr);
    std::call_once(g_em_once, init_event_methods, env);
    if (!q || !eventObj || !g_em.ok) return -1;
    auto* ev = new MacApkInputEvent();
    ev->type = kTypeKey;
    ev->deviceId    = env->CallIntMethod(eventObj, g_em.kDeviceId);
    ev->source      = env->CallIntMethod(eventObj, g_em.kSource);
    ev->action      = env->CallIntMethod(eventObj, g_em.kAction);
    ev->flags       = env->CallIntMethod(eventObj, g_em.kFlags);
    ev->keyCode     = env->CallIntMethod(eventObj, g_em.kKeyCode);
    ev->scanCode    = env->CallIntMethod(eventObj, g_em.kScanCode);
    ev->metaState   = env->CallIntMethod(eventObj, g_em.kMeta);
    ev->repeatCount = env->CallIntMethod(eventObj, g_em.kRepeat);
    // Java KeyEvent times are uptimeMillis; the NDK's are nanoseconds.
    ev->downTimeNs  = (int64_t)env->CallLongMethod(eventObj, g_em.kDownTime) * 1000000LL;
    ev->eventTimeNs = (int64_t)env->CallLongMethod(eventObj, g_em.kEventTime) * 1000000LL;
    if (env->ExceptionCheck()) { env->ExceptionClear(); delete ev; return -1; }
    if (predispatch) ev->flags |= kFlagPredispatch;
    ev->id = g_next_event_id.fetch_add(1);
    const jlong id = ev->id;
    enqueue(q, ev);
    return id;
}

jlong JNICALL InputQueue_nativeSendMotionEvent(JNIEnv* env, jclass, jlong ptr, jobject eventObj) {
    auto* q = reinterpret_cast<MacApkInputQueue*>(ptr);
    std::call_once(g_em_once, init_event_methods, env);
    if (!q || !eventObj || !g_em.ok) return -1;
    auto* ev = new MacApkInputEvent();
    ev->type = kTypeMotion;
    ev->deviceId    = env->CallIntMethod(eventObj, g_em.mDeviceId);
    ev->source      = env->CallIntMethod(eventObj, g_em.mSource);
    ev->action      = env->CallIntMethod(eventObj, g_em.mAction);
    ev->flags       = env->CallIntMethod(eventObj, g_em.mFlags);
    ev->metaState   = env->CallIntMethod(eventObj, g_em.mMeta);
    ev->buttonState = env->CallIntMethod(eventObj, g_em.mButtons);
    ev->edgeFlags   = env->CallIntMethod(eventObj, g_em.mEdge);
    ev->downTimeNs  = (int64_t)env->CallLongMethod(eventObj, g_em.mDownTime) * 1000000LL;
    ev->eventTimeNs = (int64_t)env->CallLongMethod(eventObj, g_em.mEventTimeNanos);
    ev->xPrecision  = env->CallFloatMethod(eventObj, g_em.mXPrec);
    ev->yPrecision  = env->CallFloatMethod(eventObj, g_em.mYPrec);
    int n = env->CallIntMethod(eventObj, g_em.mCount);
    if (n < 0) n = 0;
    if (n > kMaxPointers) n = kMaxPointers;
    ev->pointerCount = n;
    // Joystick / trackball / gamepad events carry the generic axes; a touch
    // event only the nine per-pointer coordinates (the rest stay 0, as a
    // touchscreen's do on a device). A mouse SCROLL / HOVER carries more:
    // AXIS_VSCROLL (9) and AXIS_HSCROLL (10) are what a wheel step IS
    // (PointerCoords keeps any axis the event set), and RetroArch reads them
    // with AMotionEvent_getAxisValue. So every axis is copied except for a
    // plain touchscreen event. MACAPK_NA_POINTER_AXES=0 restores the
    // nine-axis copy for pointer events.
    static const bool kPointerAxes = [] {
        const char* e = std::getenv("MACAPK_NA_POINTER_AXES");
        return !(e && e[0] == '0');
    }();
    const bool touchOnly = (ev->source & 0x1000 /* SOURCE_TOUCHSCREEN */) == 0x1000;
    const bool allAxes = (ev->source & 0x10 /* SOURCE_CLASS_JOYSTICK */) != 0
                      || (kPointerAxes && !touchOnly);
    for (int i = 0; i < n; i++) {
        ev->pointerId[i] = env->CallIntMethod(eventObj, g_em.mPointerId, (jint)i);
        ev->toolType[i]  = env->CallIntMethod(eventObj, g_em.mToolType, (jint)i);
        const int last = allAxes ? kAxes : kAxisOrientation + 1;
        for (int a = 0; a < last; a++)
            ev->axes[i][a] = env->CallFloatMethod(eventObj, g_em.mAxis, (jint)a, (jint)i);
        ev->axes[i][kAxisX] = env->CallFloatMethod(eventObj, g_em.mX, (jint)i);
        ev->axes[i][kAxisY] = env->CallFloatMethod(eventObj, g_em.mY, (jint)i);
        ev->rawX[i] = g_em.mRawX ? env->CallFloatMethod(eventObj, g_em.mRawX, (jint)i)
                                 : ev->axes[i][kAxisX];
        ev->rawY[i] = g_em.mRawY ? env->CallFloatMethod(eventObj, g_em.mRawY, (jint)i)
                                 : ev->axes[i][kAxisY];
    }
    if (env->ExceptionCheck()) { env->ExceptionClear(); delete ev; return -1; }
    if (n > 0) {
        // getX = raw + offset (MotionEvent's transform reduces to a translation
        // for a window that is not scaled or rotated).
        ev->xOffset = ev->axes[0][kAxisX] - ev->rawX[0];
        ev->yOffset = ev->axes[0][kAxisY] - ev->rawY[0];
    }
    ev->id = g_next_event_id.fetch_add(1);
    const jlong id = ev->id;
    enqueue(q, ev);
    return id;
}

const JNINativeMethod kInputQueueMethods[] = {
    {const_cast<char*>("nativeInit"),
     const_cast<char*>("(Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;)J"),
     reinterpret_cast<void*>(InputQueue_nativeInit)},
    {const_cast<char*>("nativeDispose"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(InputQueue_nativeDispose)},
    {const_cast<char*>("nativeSendKeyEvent"), const_cast<char*>("(JLandroid/view/KeyEvent;Z)J"),
     reinterpret_cast<void*>(InputQueue_nativeSendKeyEvent)},
    {const_cast<char*>("nativeSendMotionEvent"),
     const_cast<char*>("(JLandroid/view/MotionEvent;)J"),
     reinterpret_cast<void*>(InputQueue_nativeSendMotionEvent)},
};

// The Java InputQueue behind a native queue: its finishInputEvent runs on the
// queue's thread (InputQueue.macapkPostFinish posts it there).
void finish_to_java(MacApkInputQueue* q, int64_t id, bool handled) {
    if (!q || !q->weakQueue || !g_weakGet || !g_queuePostFinish) return;
    EnvGuard g;
    JNIEnv* e = g.env;
    if (!e) return;
    jobject queue = e->CallObjectMethod(q->weakQueue, g_weakGet);
    if (e->ExceptionCheck()) { e->ExceptionClear(); return; }
    if (!queue) {
        std::fprintf(stderr, "[nativeactivity] InputQueue was finalized without being disposed\n");
        return;
    }
    e->CallVoidMethod(queue, g_queuePostFinish, (jlong)id, (jboolean)(handled ? JNI_TRUE : JNI_FALSE));
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
    e->DeleteLocalRef(queue);
}

bool register_methods(JNIEnv* env, const char* cls, const JNINativeMethod* m, int n,
                      jclass* keep) {
    jclass c = env->FindClass(cls);
    if (!c) {
        env->ExceptionClear();
        std::fprintf(stderr, "[nativeactivity] FindClass(%s) failed; natives not registered\n", cls);
        return false;
    }
    if (env->RegisterNatives(c, m, n) != JNI_OK) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        std::fprintf(stderr, "[nativeactivity] RegisterNatives(%s) failed\n", cls);
        env->DeleteLocalRef(c);
        return false;
    }
    if (keep) *keep = static_cast<jclass>(env->NewGlobalRef(c));
    env->DeleteLocalRef(c);
    return true;
}

}  // namespace

// ---------------------------------------------------------------------------
// Registration (called by __macapk_init_assets on the main thread)
// ---------------------------------------------------------------------------
extern "C" void __macapk_register_nativeactivity_natives(JavaVM* vm) {
    if (!vm || g_vm) return;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return;
    g_vm = vm;
    jclass naCls = nullptr, iqCls = nullptr;
    bool a = register_methods(env, "android/app/NativeActivity", kNativeActivityMethods,
                              (int)(sizeof(kNativeActivityMethods) / sizeof(kNativeActivityMethods[0])),
                              &naCls);
    bool b = register_methods(env, "android/view/InputQueue", kInputQueueMethods,
                              (int)(sizeof(kInputQueueMethods) / sizeof(kInputQueueMethods[0])),
                              &iqCls);
    if (naCls) {
        g_nativeActivityClass = naCls;
        g_postWork = env->GetStaticMethodID(naCls, "macapkPostWork",
                                            "(Landroid/app/NativeActivity;III)V");
        if (!g_postWork) env->ExceptionClear();
    }
    if (iqCls) {
        g_queuePostFinish = env->GetMethodID(iqCls, "macapkPostFinish", "(JZ)V");
        if (!g_queuePostFinish) env->ExceptionClear();
        env->DeleteGlobalRef(iqCls);
    }
    if (jclass wr = env->FindClass("java/lang/ref/Reference")) {
        g_weakGet = env->GetMethodID(wr, "get", "()Ljava/lang/Object;");
        if (!g_weakGet) env->ExceptionClear();
        env->DeleteLocalRef(wr);
    } else {
        env->ExceptionClear();
    }
    std::fprintf(stderr, "[nativeactivity] natives registered (NativeActivity=%d InputQueue=%d)\n",
                 a ? 1 : 0, b ? 1 : 0);
}

// ---------------------------------------------------------------------------
// NDK: android/native_activity.h (native_activity.cpp)
// ---------------------------------------------------------------------------
extern "C" void __macapk_arm64_ANativeActivity_finish(ANativeActivity* activity) {
    post_work(activity, CMD_FINISH);
}
extern "C" void __macapk_arm64_ANativeActivity_setWindowFormat(ANativeActivity* activity,
                                                               int32_t format) {
    post_work(activity, CMD_SET_WINDOW_FORMAT, format);
}
extern "C" void __macapk_arm64_ANativeActivity_setWindowFlags(ANativeActivity* activity,
                                                              uint32_t addFlags,
                                                              uint32_t removeFlags) {
    post_work(activity, CMD_SET_WINDOW_FLAGS, (int32_t)addFlags, (int32_t)(addFlags | removeFlags));
}
extern "C" void __macapk_arm64_ANativeActivity_showSoftInput(ANativeActivity* activity,
                                                             uint32_t flags) {
    post_work(activity, CMD_SHOW_SOFT_INPUT, (int32_t)flags);
}
extern "C" void __macapk_arm64_ANativeActivity_hideSoftInput(ANativeActivity* activity,
                                                             uint32_t flags) {
    post_work(activity, CMD_HIDE_SOFT_INPUT, (int32_t)flags);
}

// ---------------------------------------------------------------------------
// NDK: android/input.h -- AInputQueue
// ---------------------------------------------------------------------------
extern "C" void __macapk_arm64_AInputQueue_attachLooper(void* queue, void* looper, int ident,
                                                        void* callback, void* data) {
    auto* q = static_cast<MacApkInputQueue*>(queue);
    if (!q || !looper) return;
    {
        std::lock_guard<std::mutex> lk(q->mu);
        if (q->looper == looper) return;
        q->looper = looper;
    }
    __macapk_looper_add_fd_plain(looper, q->readFd, ident, 1 /*ALOOPER_EVENT_INPUT*/,
                                 callback, data);
    std::fprintf(stderr, "[nativeactivity] AInputQueue_attachLooper queue=%p looper=%p ident=%d\n",
                 queue, looper, ident);
}
extern "C" void __macapk_arm64_AInputQueue_detachLooper(void* queue) {
    auto* q = static_cast<MacApkInputQueue*>(queue);
    if (!q) return;
    void* looper;
    { std::lock_guard<std::mutex> lk(q->mu); looper = q->looper; q->looper = nullptr; }
    if (looper) __macapk_arm64_ALooper_removeFd(looper, q->readFd);
}
extern "C" int32_t __macapk_arm64_AInputQueue_hasEvents(void* queue) {
    auto* q = static_cast<MacApkInputQueue*>(queue);
    if (!q) return -EINVAL;
    std::lock_guard<std::mutex> lk(q->mu);
    return q->pending.empty() ? 0 : 1;
}
extern "C" int32_t __macapk_arm64_AInputQueue_getEvent(void* queue, void** outEvent) {
    auto* q = static_cast<MacApkInputQueue*>(queue);
    if (outEvent) *outEvent = nullptr;
    if (!q) return -EINVAL;
    MacApkInputEvent* ev = nullptr;
    std::lock_guard<std::mutex> lk(q->mu);
    if (!q->pending.empty()) {
        ev = q->pending.front();
        q->pending.pop_front();
    }
    if (q->pending.empty()) {
        char buf[16];
        ssize_t r;
        do { r = ::read(q->readFd, buf, sizeof buf); } while (r > 0 || (r < 0 && errno == EINTR));
    }
    if (outEvent) *outEvent = ev;
    if (ev) {
        static std::atomic<int> n{0};
        if (n.fetch_add(1) < 16 || input_trace_all())
            std::fprintf(stderr, "[nativeactivity] AInputQueue_getEvent -> type=%d action=0x%x "
                         "source=0x%x device=%d tool=%d buttons=0x%x pointers=%d x=%.1f y=%.1f\n",
                         ev->type, ev->action, ev->source, ev->deviceId,
                         ev->pointerCount ? ev->toolType[0] : 0, ev->buttonState,
                         ev->pointerCount,
                         ev->pointerCount ? ev->axes[0][kAxisX] : 0.0f,
                         ev->pointerCount ? ev->axes[0][kAxisY] : 0.0f);
    }
    return ev ? 0 : -EWOULDBLOCK;   // OK : WOULD_BLOCK
}
extern "C" void __macapk_arm64_AInputQueue_finishEvent(void* queue, void* event, int handled);
extern "C" int32_t __macapk_arm64_AInputQueue_preDispatchEvent(void* queue, void* event) {
    auto* ev = static_cast<MacApkInputEvent*>(event);
    if (ev && ev->type == kTypeKey && (ev->flags & kFlagPredispatch)) {
        __macapk_arm64_AInputQueue_finishEvent(queue, event, 0);
        return 1;
    }
    return 0;
}
extern "C" void __macapk_arm64_AInputQueue_finishEvent(void* queue, void* event, int handled) {
    auto* q = static_cast<MacApkInputQueue*>(queue);
    auto* ev = static_cast<MacApkInputEvent*>(event);
    if (!ev) return;
    const int64_t id = ev->id;
    {
        static std::atomic<int> n{0};
        if (n.fetch_add(1) < 16 || input_trace_all())
            std::fprintf(stderr, "[nativeactivity] AInputQueue_finishEvent type=%d action=0x%x "
                         "source=0x%x handled=%d\n", ev->type, ev->action, ev->source, handled);
    }
    delete ev;
    finish_to_java(q, id, handled != 0);
}

// ---------------------------------------------------------------------------
// NDK: android/input.h -- AInputEvent / AKeyEvent / AMotionEvent accessors
// ---------------------------------------------------------------------------
namespace {
inline const MacApkInputEvent* E(const void* e) { return static_cast<const MacApkInputEvent*>(e); }
inline bool idx_ok(const void* e, size_t i) { return e && i < (size_t)E(e)->pointerCount; }
inline float axis_of(const void* e, int32_t axis, size_t i) {
    if (!idx_ok(e, i) || axis < 0 || axis >= kAxes) return 0.0f;
    return E(e)->axes[i][axis];
}
}  // namespace

extern "C" int32_t __macapk_arm64_AInputEvent_getType(const void* e) { return e ? E(e)->type : 0; }
extern "C" int32_t __macapk_arm64_AInputEvent_getDeviceId(const void* e) { return e ? E(e)->deviceId : 0; }
extern "C" int32_t __macapk_arm64_AInputEvent_getSource(const void* e) { return e ? E(e)->source : 0; }
extern "C" int32_t __macapk_arm64_AInputEvent_getDisplayId(const void* e) { return e ? E(e)->displayId : 0; }
// Only events from AKeyEvent_fromJava / AMotionEvent_fromJava may be released;
// those are not produced here, so there is nothing to free.
extern "C" void __macapk_arm64_AInputEvent_release(const void* /*e*/) {}

extern "C" int32_t __macapk_arm64_AKeyEvent_getAction(const void* e) { return e ? E(e)->action : 0; }
extern "C" int32_t __macapk_arm64_AKeyEvent_getFlags(const void* e) { return e ? E(e)->flags : 0; }
extern "C" int32_t __macapk_arm64_AKeyEvent_getKeyCode(const void* e) { return e ? E(e)->keyCode : 0; }
extern "C" int32_t __macapk_arm64_AKeyEvent_getScanCode(const void* e) { return e ? E(e)->scanCode : 0; }
extern "C" int32_t __macapk_arm64_AKeyEvent_getMetaState(const void* e) { return e ? E(e)->metaState : 0; }
extern "C" int32_t __macapk_arm64_AKeyEvent_getRepeatCount(const void* e) { return e ? E(e)->repeatCount : 0; }
extern "C" int64_t __macapk_arm64_AKeyEvent_getDownTime(const void* e) { return e ? E(e)->downTimeNs : 0; }
extern "C" int64_t __macapk_arm64_AKeyEvent_getEventTime(const void* e) { return e ? E(e)->eventTimeNs : 0; }

extern "C" int32_t __macapk_arm64_AMotionEvent_getAction(const void* e) { return e ? E(e)->action : 0; }
// AOSP masks the private flags (AMOTION_EVENT_PRIVATE_FLAG_MASK) off; Java
// MotionEvent.getFlags never carries them.
extern "C" int32_t __macapk_arm64_AMotionEvent_getFlags(const void* e) { return e ? E(e)->flags : 0; }
extern "C" int32_t __macapk_arm64_AMotionEvent_getMetaState(const void* e) { return e ? E(e)->metaState : 0; }
extern "C" int32_t __macapk_arm64_AMotionEvent_getButtonState(const void* e) { return e ? E(e)->buttonState : 0; }
extern "C" int32_t __macapk_arm64_AMotionEvent_getEdgeFlags(const void* e) { return e ? E(e)->edgeFlags : 0; }
extern "C" int32_t __macapk_arm64_AMotionEvent_getActionButton(const void* e) { return e ? E(e)->actionButton : 0; }
extern "C" int32_t __macapk_arm64_AMotionEvent_getClassification(const void* /*e*/) { return 0; }
extern "C" int64_t __macapk_arm64_AMotionEvent_getDownTime(const void* e) { return e ? E(e)->downTimeNs : 0; }
extern "C" int64_t __macapk_arm64_AMotionEvent_getEventTime(const void* e) { return e ? E(e)->eventTimeNs : 0; }
extern "C" float __macapk_arm64_AMotionEvent_getXOffset(const void* e) { return e ? E(e)->xOffset : 0; }
extern "C" float __macapk_arm64_AMotionEvent_getYOffset(const void* e) { return e ? E(e)->yOffset : 0; }
extern "C" float __macapk_arm64_AMotionEvent_getXPrecision(const void* e) { return e ? E(e)->xPrecision : 0; }
extern "C" float __macapk_arm64_AMotionEvent_getYPrecision(const void* e) { return e ? E(e)->yPrecision : 0; }
extern "C" size_t __macapk_arm64_AMotionEvent_getPointerCount(const void* e) {
    return e ? (size_t)E(e)->pointerCount : 0;
}
extern "C" int32_t __macapk_arm64_AMotionEvent_getPointerId(const void* e, size_t i) {
    return idx_ok(e, i) ? E(e)->pointerId[i] : -1;
}
extern "C" int32_t __macapk_arm64_AMotionEvent_getToolType(const void* e, size_t i) {
    return idx_ok(e, i) ? E(e)->toolType[i] : 0;
}
extern "C" float __macapk_arm64_AMotionEvent_getRawX(const void* e, size_t i) {
    return idx_ok(e, i) ? E(e)->rawX[i] : 0.0f;
}
extern "C" float __macapk_arm64_AMotionEvent_getRawY(const void* e, size_t i) {
    return idx_ok(e, i) ? E(e)->rawY[i] : 0.0f;
}
extern "C" float __macapk_arm64_AMotionEvent_getX(const void* e, size_t i) { return axis_of(e, kAxisX, i); }
extern "C" float __macapk_arm64_AMotionEvent_getY(const void* e, size_t i) { return axis_of(e, kAxisY, i); }
extern "C" float __macapk_arm64_AMotionEvent_getPressure(const void* e, size_t i) { return axis_of(e, kAxisPressure, i); }
extern "C" float __macapk_arm64_AMotionEvent_getSize(const void* e, size_t i) { return axis_of(e, kAxisSize, i); }
extern "C" float __macapk_arm64_AMotionEvent_getTouchMajor(const void* e, size_t i) { return axis_of(e, kAxisTouchMajor, i); }
extern "C" float __macapk_arm64_AMotionEvent_getTouchMinor(const void* e, size_t i) { return axis_of(e, kAxisTouchMinor, i); }
extern "C" float __macapk_arm64_AMotionEvent_getToolMajor(const void* e, size_t i) { return axis_of(e, kAxisToolMajor, i); }
extern "C" float __macapk_arm64_AMotionEvent_getToolMinor(const void* e, size_t i) { return axis_of(e, kAxisToolMinor, i); }
extern "C" float __macapk_arm64_AMotionEvent_getOrientation(const void* e, size_t i) { return axis_of(e, kAxisOrientation, i); }
extern "C" float __macapk_arm64_AMotionEvent_getAxisValue(const void* e, int32_t axis, size_t i) {
    return axis_of(e, axis, i);
}
// No batched history is carried (see the banner): an event with history size 0.
extern "C" size_t  __macapk_arm64_AMotionEvent_getHistorySize(const void* /*e*/) { return 0; }
extern "C" int64_t __macapk_arm64_AMotionEvent_getHistoricalEventTime(const void*, size_t) { return 0; }
#define NA_HIST(name) \
    extern "C" float __macapk_arm64_AMotionEvent_getHistorical##name(const void*, size_t, size_t) { return 0.0f; }
NA_HIST(RawX) NA_HIST(RawY) NA_HIST(X) NA_HIST(Y) NA_HIST(Pressure) NA_HIST(Size)
NA_HIST(TouchMajor) NA_HIST(TouchMinor) NA_HIST(ToolMajor) NA_HIST(ToolMinor)
NA_HIST(Orientation)
#undef NA_HIST
extern "C" float __macapk_arm64_AMotionEvent_getHistoricalAxisValue(const void*, int32_t,
                                                                    size_t, size_t) {
    return 0.0f;
}
