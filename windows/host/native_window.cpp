// native_window.cpp -- see native_window.h.

#include "native_window.h"

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <jni.h>

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

namespace winapk::host {

namespace {

// AHardwareBuffer/ANativeWindow formats (android/native_window.h).
constexpr int32_t kRgba8888 = 1, kRgb888 = 3, kRgb565 = 4;  // RGBX_8888 is 2

// What the guest holds as ANativeWindow*. Only the runtime reads it; the
// magic tells it from a pointer the app made up.
struct NativeWindow {
    uint32_t magic = 0x57444e57;  // 'WNDW'
    int32_t buf_w = 0, buf_h = 0, format = kRgba8888;
    jobject surface = nullptr;  // global ref, for ANativeWindow_toSurface
    std::vector<uint8_t> locked;
    int32_t locked_w = 0, locked_h = 0, locked_format = 0;
    bool is_locked = false;
};

NativeWindow g_window;
std::mutex g_lock;

// android/native_window.h's ANativeWindow_Buffer and android/rect.h's ARect.
struct GuestBuffer {
    int32_t width, height, stride, format;
    void* bits;
    uint32_t reserved[6];
};
struct GuestRect {
    int32_t left, top, right, bottom;
};

NativeWindow* ours(const void* window) {
    return window == &g_window ? &g_window : nullptr;
}

void size_of(const NativeWindow& win, int* w, int* h) {
    if (win.buf_w > 0 && win.buf_h > 0) {
        *w = win.buf_w;
        *h = win.buf_h;
    } else if (!content_pixel_size(w, h)) {
        // Before the first render tick: upstream's default phone size.
        *w = 720;
        *h = 1280;
    }
}

int bytes_per_pixel(int32_t format) { return format == kRgb565 ? 2 : format == kRgb888 ? 3 : 4; }

// ---- the guest's ANativeWindow_* ---------------------------------------------

void* from_surface(JNIEnv* env, jobject surface) {
    if (!env || !surface) return nullptr;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        if (g_window.surface) env->DeleteGlobalRef(g_window.surface);
        g_window.surface = env->NewGlobalRef(surface);
    }
    // The owning SurfaceView now composites under its window and punches its
    // hole, as upstream's fromSurface tells it.
    jclass cls = env->GetObjectClass(surface);
    jmethodID note = cls ? env->GetMethodID(cls, "macapkNoteNativeWindowConsumer", "()V") : nullptr;
    if (note) env->CallVoidMethod(surface, note);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls) env->DeleteLocalRef(cls);
    return &g_window;
}

WINAPK_GUEST_ABI void* ANativeWindow_fromSurface(JNIEnv* guest_env, jobject surface) {
    return from_surface(winapk::host_env_of(guest_env), surface);
}

WINAPK_GUEST_ABI jobject ANativeWindow_toSurface(JNIEnv* guest_env, void* window) {
    JNIEnv* env = winapk::host_env_of(guest_env);
    NativeWindow* w = ours(window);
    std::lock_guard<std::mutex> hold(g_lock);
    return env && w && w->surface ? env->NewLocalRef(w->surface) : nullptr;
}

WINAPK_GUEST_ABI void ANativeWindow_acquire(void*) {}
WINAPK_GUEST_ABI void ANativeWindow_release(void*) {}

WINAPK_GUEST_ABI int32_t ANativeWindow_getWidth(void* window) {
    NativeWindow* win = ours(window);
    if (!win) return -22;  // -EINVAL
    int w = 0, h = 0;
    size_of(*win, &w, &h);
    return w;
}

WINAPK_GUEST_ABI int32_t ANativeWindow_getHeight(void* window) {
    NativeWindow* win = ours(window);
    if (!win) return -22;
    int w = 0, h = 0;
    size_of(*win, &w, &h);
    return h;
}

WINAPK_GUEST_ABI int32_t ANativeWindow_getFormat(void* window) {
    NativeWindow* win = ours(window);
    return win ? win->format : -22;
}

// 0 for a dimension or the format keeps the window's own (AOSP).
WINAPK_GUEST_ABI int32_t ANativeWindow_setBuffersGeometry(void* window, int32_t w, int32_t h, int32_t format) {
    NativeWindow* win = ours(window);
    if (!win || (w == 0) != (h == 0) || w < 0 || h < 0) return -22;
    std::lock_guard<std::mutex> hold(g_lock);
    win->buf_w = w;
    win->buf_h = h;
    win->format = format ? format : kRgba8888;
    return 0;
}

// Software rendering: the app draws into a buffer the runtime owns, then posts.
WINAPK_GUEST_ABI int32_t ANativeWindow_lock(void* window, GuestBuffer* out, GuestRect* dirty) {
    NativeWindow* win = ours(window);
    if (!win || !out) return -22;
    std::lock_guard<std::mutex> hold(g_lock);
    if (win->is_locked) return -22;
    int w = 0, h = 0;
    size_of(*win, &w, &h);
    win->locked_w = w;
    win->locked_h = h;
    win->locked_format = win->format;
    win->locked.resize(static_cast<size_t>(w) * h * bytes_per_pixel(win->format));
    win->is_locked = true;
    out->width = w;
    out->height = h;
    out->stride = w;
    out->format = win->format;
    out->bits = win->locked.data();
    std::memset(out->reserved, 0, sizeof(out->reserved));
    // The whole buffer is handed back, so it all counts as dirty.
    if (dirty) *dirty = GuestRect{0, 0, w, h};
    return 0;
}

WINAPK_GUEST_ABI int32_t ANativeWindow_unlockAndPost(void* window) {
    NativeWindow* win = ours(window);
    if (!win) return -22;
    thread_local std::vector<uint32_t> argb;
    int w = 0, h = 0;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        if (!win->is_locked) return -22;
        win->is_locked = false;
        w = win->locked_w;
        h = win->locked_h;
        const size_t n = static_cast<size_t>(w) * h;
        argb.resize(n);
        const uint8_t* src = win->locked.data();
        switch (win->locked_format) {
            case kRgb565:
                for (size_t i = 0; i < n; ++i) {
                    const uint16_t p = static_cast<uint16_t>(src[i * 2] | (src[i * 2 + 1] << 8));
                    const uint32_t r = (p >> 11) & 0x1f, g = (p >> 5) & 0x3f, b = p & 0x1f;
                    argb[i] = 0xff000000u | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
                }
                break;
            case kRgb888:
                for (size_t i = 0; i < n; ++i) {
                    argb[i] = 0xff000000u | (src[i * 3] << 16) | (src[i * 3 + 1] << 8) | src[i * 3 + 2];
                }
                break;
            default:  // RGBA_8888, RGBX_8888: bytes R, G, B, A
                for (size_t i = 0; i < n; ++i) {
                    argb[i] = 0xff000000u | (src[i * 4] << 16) | (src[i * 4 + 1] << 8) | src[i * 4 + 2];
                }
                break;
        }
    }
    guest_frame_present(argb.data(), w, h);
    return 0;
}

// Pacing and transforms the window does not need; AOSP's "OK".
WINAPK_GUEST_ABI int32_t ANativeWindow_setFrameRate(void*, float, int8_t) { return 0; }
WINAPK_GUEST_ABI int32_t ANativeWindow_setFrameRateWithChangeStrategy(void*, float, int8_t, int8_t) { return 0; }
WINAPK_GUEST_ABI int32_t ANativeWindow_setBuffersTransform(void*, int32_t) { return 0; }
WINAPK_GUEST_ABI int32_t ANativeWindow_setBuffersDataSpace(void*, int32_t) { return 0; }
WINAPK_GUEST_ABI int32_t ANativeWindow_getBuffersDataSpace(void*) { return 0; }
WINAPK_GUEST_ABI int32_t ANativeWindow_getBuffersDefaultDataSpace(void*) { return 0; }
WINAPK_GUEST_ABI void ANativeWindow_tryAllocateBuffers(void*) {}

#define WINAPK_NW(fn) {#fn, reinterpret_cast<void*>(&fn)}
const std::unordered_map<std::string, void*> kSymbols = {
    WINAPK_NW(ANativeWindow_fromSurface), WINAPK_NW(ANativeWindow_toSurface),
    WINAPK_NW(ANativeWindow_acquire), WINAPK_NW(ANativeWindow_release),
    WINAPK_NW(ANativeWindow_getWidth), WINAPK_NW(ANativeWindow_getHeight),
    WINAPK_NW(ANativeWindow_getFormat), WINAPK_NW(ANativeWindow_setBuffersGeometry),
    WINAPK_NW(ANativeWindow_lock), WINAPK_NW(ANativeWindow_unlockAndPost),
    WINAPK_NW(ANativeWindow_setFrameRate), WINAPK_NW(ANativeWindow_setFrameRateWithChangeStrategy),
    WINAPK_NW(ANativeWindow_setBuffersTransform), WINAPK_NW(ANativeWindow_setBuffersDataSpace),
    WINAPK_NW(ANativeWindow_getBuffersDataSpace), WINAPK_NW(ANativeWindow_getBuffersDefaultDataSpace),
    WINAPK_NW(ANativeWindow_tryAllocateBuffers),
};

}  // namespace

bool native_window_buffer_size(const void* window, int* w, int* h) {
    NativeWindow* win = ours(window);
    if (!win) return false;
    std::lock_guard<std::mutex> hold(g_lock);
    size_of(*win, w, h);
    return true;
}

const void* native_window_handle() { return &g_window; }

bool native_window_has_geometry() {
    std::lock_guard<std::mutex> hold(g_lock);
    return g_window.buf_w > 0 && g_window.buf_h > 0;
}

void* native_window_resolve(const char* name) {
    auto it = kSymbols.find(name);
    return it == kSymbols.end() ? nullptr : it->second;
}

void* native_window_for_surface(JNIEnv* env, jobject surface) { return from_surface(env, surface); }

}  // namespace winapk::host
