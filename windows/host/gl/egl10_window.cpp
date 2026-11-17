// egl10_window.cpp -- MacApkEGL10's natives: a window surface on a SurfaceView
// for an app's own Java GL thread (surface_view_window_create, guest_gl.cpp).

#include "host_natives.h"
#include "native_window.h"

#include <jni.h>

namespace winapk::host {

namespace {

constexpr const char* kCls = "javax.microedition.khronos.egl.MacApkEGL10";

jlong handle_of(JNIEnv* env, jobject o) {
    if (!o) return 0;
    jclass c = env->GetObjectClass(o);
    jmethodID m = c ? env->GetMethodID(c, "getNativeHandle", "()J") : nullptr;
    const jlong h = m ? env->CallLongMethod(o, m) : 0;
    if (c) env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return h;
}

// nativeCreateSurfaceViewWindow(EGLDisplay, EGLConfig, int[]) -> android.opengl.EGLSurface or null.
// A null display is the binding probe: nothing, null.
jobject JNICALL create_window(JNIEnv* env, jclass, jobject dpy, jobject config, jintArray) {
    if (!dpy || !config) return nullptr;
    void* s = surface_view_window_create(reinterpret_cast<void*>(handle_of(env, dpy)),
                                         reinterpret_cast<void*>(handle_of(env, config)));
    if (!s) return nullptr;
    jclass cls = env->FindClass("android/opengl/EGLSurface");
    jmethodID ctor = cls ? env->GetMethodID(cls, "<init>", "(J)V") : nullptr;
    jobject out = ctor ? env->NewObject(cls, ctor, static_cast<jlong>(reinterpret_cast<intptr_t>(s))) : nullptr;
    if (cls) env->DeleteLocalRef(cls);
    return out;
}

// nativeSwapSurfaceViewWindow(EGLDisplay, EGLSurface) -> 1, 0, or -1 (not such a window).
jint JNICALL swap_window(JNIEnv* env, jclass, jobject dpy, jobject surface) {
    return surface_view_window_swap(reinterpret_cast<void*>(handle_of(env, dpy)),
                                    reinterpret_cast<void*>(handle_of(env, surface)));
}

// nativeForgetSurfaceViewWindow(EGLSurface): it is being destroyed.
void JNICALL forget_window(JNIEnv* env, jclass, jobject surface) {
    surface_view_window_destroyed(reinterpret_cast<void*>(handle_of(env, surface)));
}

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeCreateSurfaceViewWindow",
                       "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;[I)Landroid/opengl/EGLSurface;",
                       create_window),
    WINAPK_HOST_NATIVE(kCls, "nativeSwapSurfaceViewWindow",
                       "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)I", swap_window),
    WINAPK_HOST_NATIVE(kCls, "nativeForgetSurfaceViewWindow", "(Landroid/opengl/EGLSurface;)V", forget_window),
};

}  // namespace

HostMethods native_egl10_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
