// native_cocoa.cpp -- com.macapk.loader.NativeCocoa on Windows.
//
// NativeCocoa is upstream's window system: the window, the surfaces the app
// draws into, input, the run loop and process exit. Here the app gets one
// Win32 window (win32_window.h) that shows its frames and turns the mouse and
// keyboard into Android input. Windows and views Java creates are plain handle
// numbers; they all draw into that one window. With WINAPK_HEADLESS=1, or
// when there is no desktop, there is no window at all, which is how CI runs.
//
// Drawing is driven from here, as on the Mac, where the display link calls the
// renderer the app's window registered (setRenderer) once per vsync: here a
// thread ticking at a steady 60 Hz does it. Frames come back through
// presentFrame or presentBitmap. With WINAPK_HEADLESS_FRAME set to a
// file path, the latest one is written there as a BMP, which is how a headless
// run shows that the app really drew something. WINAPK_HEADLESS_EXIT_AFTER_FRAMES
// ends the process with exit code 0 that many display frames after the first
// presented one, which is what the CI smoke test waits for. It counts display
// frames, not presented ones: an app that draws once and then sits idle
// presents nothing more, and still has to finish.

#include "host_natives.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <mutex>
#include <set>
#include <vector>
#include <string>

#include <windows.h>
#include <mmsystem.h>
#include <shellapi.h>

#include "cds.h"
#include "frame_timing.h"
#include "gl/gl_surface.h"
#include "host_url.h"
#include "native_window.h"
#include "win32_window.h"
#include "winapk/arm64_guest.h"
#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

// The phone the app is shown, in dp. Upstream's cocoa_bridge.mm starts from
// 360x640, a 16:9 phone; choose_phone_size() makes it 360x800 (9:20, the most
// common phone screen today and about the shape upstream's Mac launcher picks).
std::atomic<int> g_phone_w{360};
std::atomic<int> g_phone_h{640};
// The app's surface, in pixels: what the render thread asks every renderer to
// draw at, and the size the window shows pixel for pixel. The phone size
// times the density until the user resizes the window; from then on the
// window's own box (win32_window.h, WindowInput::resized), with the dp
// derived from it, so a frame is never a pixel off the window.
std::atomic<int> g_surface_w{0};
std::atomic<int> g_surface_h{0};
std::atomic<float> g_density{0.0f};  // px per dp, once Java has said (device_density)

int px_of(int dp) { return static_cast<int>(static_cast<float>(dp) * g_density.load() + 0.5f); }

// Whether the app was started landscape (the library opens apps that way
// unless told otherwise): what an UNSPECIFIED request returns the phone to.
std::atomic<bool> g_launch_landscape{false};

// The phone's size in dp, set by the app or the launch: the surface follows.
void set_phone_dp(int w, int h) {
    g_phone_w = w;
    g_phone_h = h;
    if (g_density.load() > 0) {
        g_surface_w = px_of(w);
        g_surface_h = px_of(h);
    }
}

// Each app keeps the window size the user last gave it, in dp, as
// <data>\library\<name>.window ("<w>x<h>"; beside the library's other files on
// the app, since the app's first run moves unknown files out of apps\<name>).
// launch_phone_size prefers it to the settings' screen size; the library's Get
// Info resets it.
std::mutex g_app_dir_lock;
std::wstring g_app_dir;  // <data>\apps\<name>, from set_app_dir

std::wstring window_size_file() {
    std::lock_guard<std::mutex> hold(g_app_dir_lock);
    const size_t name = g_app_dir.find_last_of(L"\\/");
    if (name == std::wstring::npos) return L"";
    const size_t apps = name == 0 ? std::wstring::npos : g_app_dir.find_last_of(L"\\/", name - 1);
    if (apps == std::wstring::npos) return L"";
    return g_app_dir.substr(0, apps) + L"\\library\\" + g_app_dir.substr(name + 1) + L".window";
}

void save_window_size(int w, int h) {
    const std::wstring file = window_size_file();
    if (file.empty()) return;
    const std::string text = std::to_string(w) + "x" + std::to_string(h) + "\n";
    HANDLE f = CreateFileW(file.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD n = 0;
    WriteFile(f, text.data(), static_cast<DWORD>(text.size()), &n, nullptr);
    CloseHandle(f);
}

// The window was maximized when the app last ran: "<w>x<h> max", the size being
// the one it has when it is not.
void save_window_maximized(bool maximized, int w, int h) {
    const std::wstring file = window_size_file();
    if (file.empty()) return;
    const std::string text = std::to_string(w) + "x" + std::to_string(h) + (maximized ? " max\n" : "\n");
    HANDLE f = CreateFileW(file.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD n = 0;
    WriteFile(f, text.data(), static_cast<DWORD>(text.size()), &n, nullptr);
    CloseHandle(f);
}

bool saved_window_maximized() {
    const std::wstring file = window_size_file();
    if (file.empty()) return false;
    HANDLE f = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    char buf[32] = {};
    DWORD n = 0;
    ReadFile(f, buf, sizeof(buf) - 1, &n, nullptr);
    CloseHandle(f);
    return std::strstr(buf, " max") != nullptr;
}

bool saved_window_size(int* w, int* h) {
    const std::wstring file = window_size_file();
    if (file.empty()) return false;
    HANDLE f = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    char buf[32] = {};
    DWORD n = 0;
    ReadFile(f, buf, sizeof(buf) - 1, &n, nullptr);
    CloseHandle(f);
    int pw = 0, ph = 0;
    if (std::sscanf(buf, "%dx%d", &pw, &ph) != 2 || pw < 200 || ph < 200 || pw > 4000 || ph > 4000) return false;
    *w = pw;
    *h = ph;
    return true;
}

std::atomic<jlong> g_next_handle{1};
std::atomic<long> g_frames{0};

jlong new_handle() { return g_next_handle.fetch_add(1); }

JavaVM* g_vm = nullptr;

// ---- input: the window's UI thread calls into the TouchDispatcher -----------

jobject g_dispatcher = nullptr;  // global ref, set by registerTouchDispatcher
jmethodID g_dispatch_mid = nullptr, g_key_mid = nullptr;
// TouchDispatcher's entries for what the window reports beside one finger and
// plain keys (win32_window.h, WindowInput); nullptr when the class has none.
jmethodID g_host_key_mid = nullptr, g_touch_mid = nullptr, g_wheel_mid = nullptr, g_pinch_mid = nullptr;
jmethodID g_mouse_mid = nullptr, g_mouse_exit_mid = nullptr, g_text_mid = nullptr, g_composing_mid = nullptr;
jmethodID g_ime_inline_mid = nullptr, g_ime_anchor_mid = nullptr;

JNIEnv* ui_thread_env() {
    thread_local JNIEnv* env = nullptr;
    if (!env && g_vm) {
        JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("winapk-ui"), nullptr};
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) env = nullptr;
    }
    return env;
}

void report_input_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return;
    std::fprintf(stderr, "[win.apk] exception while delivering input:\n");
    env->ExceptionDescribe();
    env->ExceptionClear();
}

// com.macapk.loader.ActivityHost, through the dispatcher's class loader, which
// is the one that loaded the runtime's classes (a thread attached from native
// code has only the system loader to look in); a global reference, or nullptr.
std::mutex g_activity_host_lock;
jclass g_activity_host = nullptr;

jclass activity_host_class(JNIEnv* env) {
    std::lock_guard<std::mutex> hold(g_activity_host_lock);
    if (g_activity_host) return g_activity_host;
    jobject cls = nullptr;
    if (g_dispatcher) {
        jclass dcls = env->GetObjectClass(g_dispatcher);
        jclass ccls = env->FindClass("java/lang/Class");
        jmethodID get_loader = env->GetMethodID(ccls, "getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject loader = env->CallObjectMethod(dcls, get_loader);
        jclass lcls = env->FindClass("java/lang/ClassLoader");
        jmethodID load = env->GetMethodID(lcls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring name = env->NewStringUTF("com.macapk.loader.ActivityHost");
        cls = loader ? env->CallObjectMethod(loader, load, name) : nullptr;
    } else {
        cls = env->FindClass("com/macapk/loader/ActivityHost");  // no dispatcher yet: early in the boot
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        cls = nullptr;
    }
    if (cls) g_activity_host = static_cast<jclass>(env->NewGlobalRef(cls));
    return g_activity_host;
}

// A static void method of ActivityHost with no arguments, from the window's
// UI thread.
void call_activity_host(const char* method) {
    JNIEnv* env = ui_thread_env();
    jclass host = env ? activity_host_class(env) : nullptr;
    if (!host) return;
    jmethodID mid = env->GetStaticMethodID(host, method, "()V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(host, mid);
    report_input_exception(env);
}

// ---- closing: the app's lifecycle runs before the process ends ---------------
//
// On a device a visible activity always gets onPause and onStop before it goes
// away, which is why apps save there (an editor's text, a game's state, a
// database transaction), so closing the window must not end the process at
// once. The window goes at once (win32_window.cpp hides it
// on WM_CLOSE) and upstream's own close path runs behind it:
// ActivityHost.macapkTerminate posts the teardown to the app's main thread,
// onPause, onStop and onDestroy for every activity of the task from the top
// down, and waits for it; then the process ends as before, with exit code 0
// (the launcher reads that as "closed by its user", RunEnd::kind).
//
// Nothing here can keep a closed app alive:
//   - the wait is kCloseTeardownMs, upstream's cap (cocoa_bridge.mm,
//     macapk_terminate_now): Java-only apps finish in a few ms and game
//     engines in tens, so two seconds is only ever reached by an app whose
//     main thread is stuck, and it is well inside the 5 s Windows gives a
//     program at the end of a session;
//   - the watchdog thread touches no Java at all, so a VM that cannot answer
//     (a safepoint that never comes, a thread that cannot attach) is ended
//     kCloseGraceMs after the cap;
//   - a second close request ends the process at once, and the launcher's
//     Stop ends it from outside as it always did (Android's Force stop).
//
// The window is hidden before the pause, where upstream waits for onPause to
// return: there, a render thread cannot get a drawable for a window off
// screen, and an engine's pause that waits for its render thread never ends.
// Here the app renders into its own buffers on the render thread, which
// keeps ticking whether the window shows or not.
constexpr DWORD kCloseTeardownMs = 2000;
constexpr DWORD kCloseGraceMs = 500;
std::atomic<bool> g_closing{false};

// Ends the process for a close, once: the caller that comes second waits for
// the first to finish. `how` is the log line the CI and a bug report read.
void end_close(const char* how) {
    static std::atomic<bool> ended{false};
    if (ended.exchange(true)) Sleep(INFINITE);
    std::fprintf(stderr, "[win.apk] close: %s; ended\n", how);
    std::fflush(stdout);
    std::fflush(stderr);
    TerminateProcess(GetCurrentProcess(), 0);
}

DWORD WINAPI close_watchdog(void*) {
    Sleep(kCloseTeardownMs + kCloseGraceMs);
    end_close("the app did not answer in time");
    return 0;
}

DWORD WINAPI close_thread(void*) {
    const ULONGLONG t0 = GetTickCount64();
    bool done = false;
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("winapk-close"), nullptr};
    if (g_vm && g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) == JNI_OK && env) {
        if (jclass host = activity_host_class(env)) {
            const jmethodID terminate = env->GetStaticMethodID(host, "macapkTerminate", "(J)Z");
            if (terminate) {
                done = env->CallStaticBooleanMethod(host, terminate, static_cast<jlong>(kCloseTeardownMs)) == JNI_TRUE;
            }
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            done = false;
        }
    }
    char how[96];
    std::snprintf(how, sizeof how, "the app was paused, stopped and destroyed in %llu ms",
                  static_cast<unsigned long long>(GetTickCount64() - t0));
    end_close(done ? how : "the app did not answer in time");
    return 0;
}

class JavaInput : public WindowInput {
    // TouchDispatcher takes AppKit view coordinates: points, origin at the
    // bottom left; it multiplies them by backingScale (the density) for the
    // surface pixel. The view is the surface in points -- its pixels over the
    // density, not the rounded dp, so a touch lands on the surface pixel it
    // was made on when the window shows the surface pixel for pixel.
    struct View {
        float w = 0, h = 0;      // the view, in points
        float sx = 0, sy = 0;    // points per pixel of the window's box
        bool ok = false;
        float x(float px) const { return px * sx; }
        float y(float py) const { return h - py * sy; }
    };
    static View view(float view_w, float view_h) {
        View v;
        if (view_w <= 0 || view_h <= 0) return v;
        const float d = g_density.load() > 0 ? g_density.load() : 1.0f;
        const int sw = g_surface_w.load(), sh = g_surface_h.load();
        v.w = sw > 0 ? static_cast<float>(sw) / d : static_cast<float>(g_phone_w.load());
        v.h = sh > 0 ? static_cast<float>(sh) / d : static_cast<float>(g_phone_h.load());
        v.sx = v.w / view_w;
        v.sy = v.h / view_h;
        v.ok = true;
        return v;
    }
    static void call_text(jmethodID mid, const std::wstring& utf16) {
        JNIEnv* env = ui_thread_env();
        if (!env || !g_dispatcher || !mid) return;
        jstring s = env->NewString(reinterpret_cast<const jchar*>(utf16.data()), static_cast<jsize>(utf16.size()));
        if (s) env->CallVoidMethod(g_dispatcher, mid, s);
        report_input_exception(env);
        if (s) env->DeleteLocalRef(s);
    }

public:
    void pointer(int action, float x, float y, float view_w, float view_h) override {
        JNIEnv* env = ui_thread_env();
        const View v = view(view_w, view_h);
        if (!env || !g_dispatcher || !v.ok) return;
        env->CallVoidMethod(g_dispatcher, g_dispatch_mid, action, v.x(x), v.y(y), v.w, v.h);
        report_input_exception(env);
    }
    void key(int action, int key_code, int unicode, int meta, bool repeat) override {
        JNIEnv* env = ui_thread_env();
        if (!env || !g_dispatcher) return;
        if (g_host_key_mid) {
            env->CallVoidMethod(g_dispatcher, g_host_key_mid, action, key_code, unicode, meta,
                                repeat ? JNI_TRUE : JNI_FALSE);
        } else {
            env->CallVoidMethod(g_dispatcher, g_key_mid, action, key_code, unicode, meta);
        }
        report_input_exception(env);
    }
    // TouchDispatcher.dispatchHostTouch: the pointers of the event, side by side.
    void touch(const TouchEvent& e, float view_w, float view_h) override {
        JNIEnv* env = ui_thread_env();
        const View v = view(view_w, view_h);
        if (!env || !g_dispatcher || !g_touch_mid || !v.ok || e.count <= 0) return;
        jint ids[kMaxTouches], tools[kMaxTouches];
        jfloat xs[kMaxTouches], ys[kMaxTouches], pressures[kMaxTouches];
        for (int i = 0; i < e.count; ++i) {
            ids[i] = e.points[i].id;
            tools[i] = e.points[i].tool;
            xs[i] = v.x(e.points[i].x);
            ys[i] = v.y(e.points[i].y);
            pressures[i] = e.points[i].pressure;
        }
        jintArray jids = env->NewIntArray(e.count), jtools = env->NewIntArray(e.count);
        jfloatArray jxs = env->NewFloatArray(e.count), jys = env->NewFloatArray(e.count),
                    jpressures = env->NewFloatArray(e.count);
        if (jids && jtools && jxs && jys && jpressures) {
            env->SetIntArrayRegion(jids, 0, e.count, ids);
            env->SetIntArrayRegion(jtools, 0, e.count, tools);
            env->SetFloatArrayRegion(jxs, 0, e.count, xs);
            env->SetFloatArrayRegion(jys, 0, e.count, ys);
            env->SetFloatArrayRegion(jpressures, 0, e.count, pressures);
            env->CallVoidMethod(g_dispatcher, g_touch_mid, e.action, e.index, jids, jxs, jys, jpressures, jtools,
                                e.buttons, v.w, v.h);
        }
        report_input_exception(env);
        for (jobject o : {static_cast<jobject>(jids), static_cast<jobject>(jtools), static_cast<jobject>(jxs),
                          static_cast<jobject>(jys), static_cast<jobject>(jpressures)}) {
            if (o) env->DeleteLocalRef(o);
        }
    }
    // TouchDispatcher.dispatchHostWheel; the drag's steps are distances, so y only turns over.
    bool wheel(float x, float y, float view_w, float view_h, float vscroll, float hscroll, float slop_x, float slop_y,
               float drag_x, float drag_y) override {
        JNIEnv* env = ui_thread_env();
        const View v = view(view_w, view_h);
        if (!env || !g_dispatcher || !g_wheel_mid || !v.ok) return false;
        env->CallVoidMethod(g_dispatcher, g_wheel_mid, v.x(x), v.y(y), v.w, v.h, vscroll, hscroll, slop_x * v.sx,
                            -slop_y * v.sy, drag_x * v.sx, -drag_y * v.sy);
        report_input_exception(env);
        return true;
    }
    void pinch(const PinchStep& step, float view_w, float view_h) override {
        JNIEnv* env = ui_thread_env();
        const View v = view(view_w, view_h);
        if (!env || !g_dispatcher || !g_pinch_mid || !v.ok) return;
        env->CallVoidMethod(g_dispatcher, g_pinch_mid, step.phase, v.x(step.x), v.y(step.y), step.span * v.sx, v.w,
                            v.h);
        report_input_exception(env);
    }
    void mouse(int buttons, int meta, float x, float y, float view_w, float view_h) override {
        JNIEnv* env = ui_thread_env();
        const View v = view(view_w, view_h);
        if (!env || !g_dispatcher || !g_mouse_mid || !v.ok) return;
        env->CallVoidMethod(g_dispatcher, g_mouse_mid, buttons, meta, v.x(x), v.y(y), v.w, v.h);
        report_input_exception(env);
    }
    void mouse_exit() override {
        JNIEnv* env = ui_thread_env();
        if (!env || !g_dispatcher || !g_mouse_exit_mid) return;
        env->CallVoidMethod(g_dispatcher, g_mouse_exit_mid, 0.0f, 0.0f, 1.0f, 1.0f);  // an exit has no position
        report_input_exception(env);
    }
    void text(const std::wstring& utf16) override { call_text(g_text_mid, utf16); }
    bool composing_inline() override {
        JNIEnv* env = ui_thread_env();
        if (!env || !g_dispatcher || !g_ime_inline_mid || !g_composing_mid) return false;
        const bool yes = env->CallBooleanMethod(g_dispatcher, g_ime_inline_mid) == JNI_TRUE;
        if (env->ExceptionCheck()) {
            report_input_exception(env);
            return false;
        }
        return yes;
    }
    void composing(const std::wstring& utf16) override { call_text(g_composing_mid, utf16); }
    void composing_end() override {
        JNIEnv* env = ui_thread_env();
        if (!env || !g_dispatcher || !g_composing_mid) return;
        env->CallVoidMethod(g_dispatcher, g_composing_mid, static_cast<jstring>(nullptr));  // null: it ended
        report_input_exception(env);
    }
    // TouchDispatcher.hostImeAnchor: the caret in surface pixels, x in the high half.
    bool caret(float* x, float* y, float view_w, float view_h) override {
        JNIEnv* env = ui_thread_env();
        const float sw = static_cast<float>(g_surface_w.load()), sh = static_cast<float>(g_surface_h.load());
        if (!env || !g_dispatcher || !g_ime_anchor_mid || sw <= 0 || sh <= 0) return false;
        const jlong packed = env->CallLongMethod(g_dispatcher, g_ime_anchor_mid);
        if (env->ExceptionCheck()) {
            report_input_exception(env);
            return false;
        }
        if (packed < 0) return false;
        *x = static_cast<float>(static_cast<int32_t>(packed >> 32)) * view_w / sw;
        *y = static_cast<float>(static_cast<int32_t>(packed & 0xffffffff)) * view_h / sh;
        return true;
    }
    // The user closed the window (its X, Alt+F4, the taskbar; the end of the
    // Windows session too). See close_thread.
    void close_requested() override {
        if (g_closing.exchange(true)) end_close("asked to close again");  // no second wait
        HANDLE watchdog = CreateThread(nullptr, 0, close_watchdog, nullptr, 0, nullptr);
        HANDLE teardown = watchdog ? CreateThread(nullptr, 0, close_thread, nullptr, 0, nullptr) : nullptr;
        if (!teardown) end_close("no thread for the app's teardown");
        CloseHandle(watchdog);
        CloseHandle(teardown);
    }
    // ActivityHost.macapkHostMinimized: the resumed activity is paused and
    // stopped, as when its task goes behind home; restored() brings it back.
    void minimized() override { call_activity_host("macapkHostMinimized"); }
    // ActivityHost.macapkTaskToFront: a no-op unless the task went to the back
    // (the app sent it there, or the user minimized the window).
    void restored() override { call_activity_host("macapkTaskToFront"); }
    // The render thread sees the new surface size on its next tick and reports
    // it (DisplayConfig, then surfaceChanged to every renderer).
    void resized(int w_dp, int h_dp, int w_px, int h_px) override {
        g_phone_w = w_dp;
        g_phone_h = h_dp;
        g_surface_w = w_px;
        g_surface_h = h_px;
    }
    // A size the user settled on is kept for the app's next start.
    void remember_size(int w_dp, int h_dp) override { save_window_size(w_dp, h_dp); }
    void remember_maximized(bool maximized, int w_dp, int h_dp) override {
        save_window_maximized(maximized, w_dp, h_dp);
    }
};

JavaInput g_java_input;

void JNICALL Cocoa_registerTouchDispatcher(JNIEnv* env, jclass, jobject dispatcher) {
    if (g_dispatcher) env->DeleteGlobalRef(g_dispatcher);
    g_dispatcher = dispatcher ? env->NewGlobalRef(dispatcher) : nullptr;
    if (!dispatcher) return;
    jclass cls = env->GetObjectClass(dispatcher);
    g_dispatch_mid = env->GetMethodID(cls, "dispatch", "(IFFFF)V");
    g_key_mid = env->GetMethodID(cls, "dispatchKey", "(IIII)V");
    // A dispatcher without one of these keeps what the window did before it
    // (a wheel step as a drag, one finger, keys without their repeat).
    auto method = [&](const char* name, const char* sig) {
        jmethodID mid = env->GetMethodID(cls, name, sig);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            mid = nullptr;
        }
        return mid;
    };
    g_host_key_mid = method("dispatchHostKey", "(IIIIZ)V");
    g_touch_mid = method("dispatchHostTouch", "(II[I[F[F[F[IIFF)V");
    g_wheel_mid = method("dispatchHostWheel", "(FFFFFFFFFF)V");
    g_pinch_mid = method("dispatchHostPinch", "(IFFFFF)V");
    g_mouse_mid = method("dispatchHostMouse", "(IIFFFF)V");
    g_mouse_exit_mid = method("dispatchHostMouseExit", "(FFFF)V");
    g_text_mid = method("dispatchHostText", "(Ljava/lang/String;)V");
    g_composing_mid = method("dispatchHostComposingText", "(Ljava/lang/String;)V");
    g_ime_inline_mid = method("hostImeInline", "()Z");
    g_ime_anchor_mid = method("hostImeAnchor", "()J");
    env->DeleteLocalRef(cls);
}

// ---- window and metrics -----------------------------------------------------

bool headless_requested() {
    const char* e = std::getenv("WINAPK_HEADLESS");
    return e && *e && *e != '0';
}

float device_density(JNIEnv* env);

void choose_phone_size() {
    int w = 0, h = 0;
    launch_phone_size(&w, &h);
    set_phone_dp(w, h);
    g_launch_landscape = w > h;
}

// ---- the app icon -----------------------------------------------------------
//
// Boot renders the app's launcher icon, adaptive and vector ones included, to
// <app dir>/icon.png for upstream's launcher. Here it becomes the window and
// taskbar icon: right away when an earlier run left one, and again whenever
// the file changes (a first run renders it during boot, before the activity
// shows its window).

std::mutex g_icon_lock;
std::wstring g_icon_path;
FILETIME g_icon_applied{};

void apply_app_icon() {
    std::lock_guard<std::mutex> hold(g_icon_lock);
    if (g_icon_path.empty() || !window_running()) return;
    WIN32_FILE_ATTRIBUTE_DATA attrs;
    if (!GetFileAttributesExW(g_icon_path.c_str(), GetFileExInfoStandard, &attrs)) return;
    if (CompareFileTime(&attrs.ftLastWriteTime, &g_icon_applied) == 0) return;
    if (window_set_icon_png(g_icon_path)) g_icon_applied = attrs.ftLastWriteTime;
}

jboolean JNICALL Cocoa_init(JNIEnv* env, jclass) {
    env->GetJavaVM(&g_vm);
    g_density = device_density(env);
    choose_phone_size();
    if (headless_requested()) {
        std::fprintf(stderr, "[win.apk] WINAPK_HEADLESS is set; running without a window\n");
    } else if (!window_start(g_phone_w, g_phone_h, g_density.load(), &g_java_input)) {
        std::fprintf(stderr, "[win.apk] no desktop to open a window on; running headless\n");
    }
    apply_app_icon();
    return JNI_TRUE;
}

void JNICALL Cocoa_setPhoneSize(JNIEnv*, jclass, jint w, jint h) {
    set_phone_dp(w > 0 ? w : g_phone_w.load(), h > 0 ? h : g_phone_h.load());
    window_set_phone_size(g_phone_w, g_phone_h);
}

jint JNICALL Cocoa_phoneWidth(JNIEnv*, jclass) { return g_phone_w; }
jint JNICALL Cocoa_phoneHeight(JNIEnv*, jclass) { return g_phone_h; }
jint JNICALL Cocoa_displayHz(JNIEnv*, jclass) { return 60; }

// Surface pixels per point. TouchDispatcher multiplies view points by it, and
// the surface is the phone size at the device density, so the two must agree.
// (On the Mac this is the display's scale, which equals the density on a
// Retina screen.)
jfloat JNICALL Cocoa_backingScale(JNIEnv* env, jclass) {
    static const float density = device_density(env);
    return density;
}

// Same rule as upstream: the short side stays the short side.
void JNICALL Cocoa_applyRequestedOrientation(JNIEnv*, jclass, jboolean landscape) {
    const int a = g_phone_w, b = g_phone_h;
    const int lo = a < b ? a : b, hi = a < b ? b : a;
    window_set_orientation_lock(landscape ? 2 : 1);
    set_phone_dp(landscape ? hi : lo, landscape ? lo : hi);
    // Also when the phone already has that shape: the lock alone changes the
    // box a maximized window gives the app.
    window_set_phone_size(g_phone_w, g_phone_h);
}

// ActivityInfo.screenOrientation: the landscape and portrait families turn
// the phone and fix that shape for a window the user resizes; the rest
// (unspecified, sensor, behind...) leave the phone as it is and free it.
void apply_screen_orientation(jint o) {
    if (o == 0 || o == 6 || o == 8 || o == 11) Cocoa_applyRequestedOrientation(nullptr, nullptr, JNI_TRUE);
    else if (o == 1 || o == 7 || o == 9 || o == 12) Cocoa_applyRequestedOrientation(nullptr, nullptr, JNI_FALSE);
    else window_set_orientation_lock(0);
}
void JNICALL Cocoa_applyWindowOrientation(JNIEnv*, jclass, jint o) { apply_screen_orientation(o); }
void JNICALL Cocoa_applyWindowOrientationFor(JNIEnv*, jclass, jstring, jint, jint o, jint) {
    apply_screen_orientation(o);
}
// The user rotation is the orientation the app was started in (the library's
// choice for it: landscape unless the settings or the app's own entry say
// portrait; there is no Cmd+R rotation here), and the window is free again.
void JNICALL Cocoa_restoreUserOrientation(JNIEnv*, jclass) {
    Cocoa_applyRequestedOrientation(nullptr, nullptr, g_launch_landscape ? JNI_TRUE : JNI_FALSE);
    window_set_orientation_lock(0);
}
// No user aspect-ratio override exists here, so the record's own orientation stands.
jint JNICALL Cocoa_windowOverrideOrientation(JNIEnv*, jclass, jstring, jint record) { return record; }
// No per-activity window policy session: Java falls back to the display
// commit (documented null).
jintArray JNICALL Cocoa_activityWindowTransition(JNIEnv*, jclass, jstring, jint, jboolean, jboolean, jint, jint, jint,
                                                 jint, jint) {
    return nullptr;
}

// One window holds every activity, so these window-shaping calls have
// nothing to act on.
void JNICALL Cocoa_setTopActivityConfigChanges(JNIEnv*, jclass, jint) {}
void JNICALL Cocoa_setMaxAspectRatio(JNIEnv*, jclass, jfloat) {}
// The view a window shows. When it is a GL view (createMetalSurfaceViewForGl),
// the window shows that view's GL frames and the Java UI comes as the overlay
// layer; otherwise it shows what the view tree presents (presentBitmap).
std::atomic<jlong> g_content_view{0};
std::mutex g_gl_views_lock;
std::set<jlong> g_gl_views;
bool is_gl_view(jlong view) {
    std::lock_guard<std::mutex> hold(g_gl_views_lock);
    return g_gl_views.count(view) != 0;
}
void clear_composite_tree();
void JNICALL Cocoa_setContentView(JNIEnv*, jclass, jlong, jlong view) {
    // Upstream's overlay layer belongs to the content view, so a new one starts
    // without the previous tree's frame (a GL game must not sit under the
    // splash it replaced); the tree that owns the new view presents again.
    if (g_content_view.exchange(view) != view) clear_composite_tree();
}
void JNICALL Cocoa_closeWindow(JNIEnv*, jclass, jlong) {}

// FLAG_SECURE: kept out of screenshots, recording and screen sharing.
void JNICALL Cocoa_setSecureContent(JNIEnv*, jclass, jboolean secure) { window_set_secure(secure == JNI_TRUE); }

// An implicit ACTION_VIEW/SENDTO/DIAL intent nobody in the app handles: the
// system opens the URL (browser, mail, ...), if it is one a phone opens
// outside the app (host_url.h); any other scheme would reach whatever program
// Windows registered for it.
jboolean JNICALL Cocoa_openHostUrl(JNIEnv* env, jclass, jstring url) {
    if (!url) return JNI_FALSE;
    const jchar* c = env->GetStringChars(url, nullptr);
    const std::wstring u(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(url)));
    env->ReleaseStringChars(url, c);
    const std::wstring target = host_open_target(u);
    if (target.empty()) return JNI_FALSE;
    const auto r =
        reinterpret_cast<INT_PTR>(ShellExecuteW(nullptr, L"open", target.c_str(), nullptr, nullptr, SW_SHOWNORMAL));
    return r > 32 ? JNI_TRUE : JNI_FALSE;
}

// URLs the host hands to this app, as upstream's kAEGetURL Apple Event queues
// the ones the macOS browser sends: here, the ones the in-runtime browser tab
// (native_browser_tab.cpp) does not load because the app's own manifest takes
// them. queue_host_url adds one and rings HostUrlIntake.macapkOnHostUrl, whose
// drain (on the app's main thread) takes them all from here and delivers each
// as the browser's ACTION_VIEW intent. Custom URL schemes are not registered
// with Windows, so nothing outside the runtime adds to this queue.
std::mutex g_host_urls_lock;
std::vector<std::wstring> g_host_urls;
jclass g_intake_cls = nullptr;
jmethodID g_intake_on_url = nullptr;

jobjectArray JNICALL Cocoa_takeHostUrls(JNIEnv* env, jclass) {
    std::vector<std::wstring> urls;
    {
        std::lock_guard<std::mutex> hold(g_host_urls_lock);
        urls.swap(g_host_urls);
    }
    jclass string_cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(urls.size()), string_cls, nullptr);
    for (size_t i = 0; out && i < urls.size(); ++i) {
        jstring s = env->NewString(reinterpret_cast<const jchar*>(urls[i].data()), static_cast<jsize>(urls[i].size()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}
jobjectArray JNICALL Cocoa_urlSchemeHandlers(JNIEnv*, jclass, jstring) { return nullptr; }
jint JNICALL Cocoa_setUrlSchemeDefault(JNIEnv*, jclass, jstring, jstring) { return -1; }

// The GL overlay layer: dialogs and toasts over content the view tree does
// not draw (GL, surfaces).
void JNICALL Cocoa_presentOverlayBitmap(JNIEnv* env, jclass, jintArray argb, jint w, jint h) {
    if (!argb || w <= 0 || h <= 0 || env->GetArrayLength(argb) < w * h) return;
    jint* px = env->GetIntArrayElements(argb, nullptr);
    if (!px) return;
    window_set_overlay(reinterpret_cast<const uint32_t*>(px), w, h);
    env->ReleaseIntArrayElements(argb, px, JNI_ABORT);
}
void JNICALL Cocoa_clearOverlayBitmap(JNIEnv*, jclass) { window_clear_overlay(); }

// Quitting: the window goes away at once, before the app's teardown runs.
jboolean JNICALL Cocoa_hostHideWindowsForQuit(JNIEnv*, jclass) {
    window_hide();
    return window_running() ? JNI_TRUE : JNI_FALSE;
}

// Upstream shows "Translating <lib>..." while it translates ARM code ahead of
// time; arm64 libraries run as they are here, so there is nothing to show.
void JNICALL Cocoa_showTranslationOverlay(JNIEnv*, jclass, jstring) {}
void JNICALL Cocoa_updateTranslationOverlay(JNIEnv*, jclass, jstring) {}
void JNICALL Cocoa_hideTranslationOverlay(JNIEnv*, jclass) {}

// A RENDERMODE_WHEN_DIRTY GLSurfaceView with nothing to draw withholds the
// tick: the pbuffer still holds the last frame, so the readback is skipped.
// (Upstream may ask for a draw anyway for its self-capture; there is none.)
thread_local bool t_tick_withheld = false;
jboolean JNICALL Cocoa_beginWithheldTick(JNIEnv*, jclass, jboolean) {
    t_tick_withheld = true;
    return JNI_FALSE;
}
jboolean JNICALL Cocoa_hostRenderTick(JNIEnv*, jclass) { return JNI_FALSE; }

void JNICALL Cocoa_showWindow(JNIEnv*, jclass, jlong) {
    if (g_closing.load()) return;  // closed by the user: nothing its teardown does brings the window back
    apply_app_icon();
    window_show();
}
void JNICALL Cocoa_moveTaskToBack(JNIEnv*, jclass) { window_minimize(); }
void JNICALL Cocoa_moveTaskToFront(JNIEnv*, jclass) { window_restore(); }
void JNICALL Cocoa_setStartingWindowColor(JNIEnv*, jclass, jint argb) {
    window_set_background(static_cast<uint32_t>(argb));
}

// Upstream mirrors the app's System.setProperty calls into the native property
// store so guest code reading __system_property_get sees the same values.
void JNICALL Cocoa_setSystemProperty(JNIEnv* env, jclass, jstring key, jstring value) {
    if (!key) return;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    if (k) winapk::property_set(k, v ? v : "");
    if (v) env->ReleaseStringUTFChars(value, v);
    if (k) env->ReleaseStringUTFChars(key, k);
}

// Boot writes Android's fonts (and a fonts.xml) to a cache directory; native
// text stacks (Skia in Flutter apps) read them as /system/fonts.
void JNICALL Cocoa_setSystemFontsDir(JNIEnv* env, jclass, jstring dir) {
    if (!dir) return;
    const char* d = env->GetStringUTFChars(dir, nullptr);
    if (d) {
        winapk::set_system_fonts_dir(d);
        env->ReleaseStringUTFChars(dir, d);
    }
}
// Upstream uses these for its /proc/self/maps and relative-path emulation,
// which the Windows shim does not have; accepting them keeps boot quiet.
void JNICALL Cocoa_setNativeLibDir(JNIEnv*, jclass, jstring) {}
void JNICALL Cocoa_setDataSubdir(JNIEnv*, jclass, jstring) {}
// Boot: the app's package and its data directory on the host, so that
// /data/user/0/<package> names that directory for guest code and for java.io
// and java.nio (the overlay's WinApkPaths), whatever the folder is called.
void JNICALL Cocoa_setAppData(JNIEnv* env, jclass, jstring package, jstring host_dir) {
    auto text = [env](jstring s) {
        std::string out;
        if (!s) return out;
        const jchar* c = env->GetStringChars(s, nullptr);
        if (!c) return out;
        const auto* w = reinterpret_cast<const wchar_t*>(c);
        const int len = env->GetStringLength(s);
        const int n = WideCharToMultiByte(CP_UTF8, 0, w, len, nullptr, 0, nullptr, nullptr);
        out.resize(static_cast<size_t>(n));
        WideCharToMultiByte(CP_UTF8, 0, w, len, &out[0], n, nullptr, nullptr);
        env->ReleaseStringChars(s, c);
        return out;
    };
    winapk::set_app_data(text(package).c_str(), text(host_dir).c_str());
}
void JNICALL Cocoa_setSharedStorageRoot(JNIEnv*, jclass, jstring) {}
// java.io.FileReader's hook for a /proc path (Boot installs it): the file Boot
// wrote for it under <android root>/proc (meminfo, this process's cmdline), as
// the guest shim's opens find it. Null for a path with no such file: the open
// then fails as the JDK's does.
jstring JNICALL Cocoa_remapProcPath(JNIEnv* env, jclass, jstring path) {
    if (!path) return nullptr;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return nullptr;
    const std::wstring host = winapk::android_proc_file(p);
    env->ReleaseStringUTFChars(path, p);
    if (host.empty()) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(host.c_str()), static_cast<jsize>(host.size()));
}
// The host's CJK faces by PostScript name (CoreText on the Mac). None here:
// MacApkSystemFonts links them into /system/fonts with symbolic links, which a
// standard Windows account may not create, so the bundled fonts stay the set.
jstring JNICALL Cocoa_hostFontFile(JNIEnv*, jclass, jstring) { return nullptr; }
// An App Link sign-in page in Win.apk's own window (com.macapk.loader.AppLinks;
// native_browser_tab.cpp). False sends AppLinks down its other path, the browser.
jboolean JNICALL Cocoa_openAppLinkWindow(JNIEnv* env, jclass, jstring url, jstring prefix) {
    if (!url || !prefix) return JNI_FALSE;
    auto wide = [env](jstring j) {
        const jchar* c = env->GetStringChars(j, nullptr);
        std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(j)));
        env->ReleaseStringChars(j, c);
        return w;
    };
    return open_applink_window(env, wide(url), wide(prefix)) ? JNI_TRUE : JNI_FALSE;
}

void JNICALL Cocoa_setWindowTitle(JNIEnv* env, jclass, jlong, jstring title) {
    if (!title) return;
    const char* t = env->GetStringUTFChars(title, nullptr);
    if (t) {
        window_set_title(t);
        env->ReleaseStringUTFChars(title, t);
    }
    apply_app_icon();
}

jlong JNICALL Cocoa_createWindow(JNIEnv*, jclass, jstring) { return new_handle(); }
jlong JNICALL Cocoa_createView(JNIEnv*, jclass, jint, jint) { return new_handle(); }
jlong JNICALL Cocoa_createGlView(JNIEnv*, jclass, jint, jint) {
    const jlong view = new_handle();
    std::lock_guard<std::mutex> hold(g_gl_views_lock);
    g_gl_views.insert(view);
    return view;
}
jlong JNICALL Cocoa_getPlaceholderWindow(JNIEnv*, jclass) {
    static const jlong placeholder = new_handle();
    return placeholder;
}

// ---- the render tick ------------------------------------------------------

// Every view with a renderer (MetalSurfaceCallbacks) is driven each tick:
// the canvas views draw the view tree and present it themselves; a GL view
// draws with ANGLE's context current on its pbuffer (gl_surface.h).
struct Renderer {
    jobject callbacks = nullptr;  // global ref
    bool gl = false;
    int announced_w = 0, announced_h = 0;
};
std::mutex g_renderer_lock;
std::map<jlong, Renderer> g_renderers;
std::vector<jlong> g_released_gl_views;  // pbuffers for the render thread to drop

// The surface is the phone size in pixels, at the density the Java side uses
// for the display metrics (DisplayConfig.seedFromCanvas).
float device_density(JNIEnv* env) {
    jclass cls = env->FindClass("android/util/DisplayMetrics");
    jmethodID m = cls ? env->GetStaticMethodID(cls, "macapkDeviceDensity", "()F") : nullptr;
    const float d = m ? env->CallStaticFloatMethod(cls, m) : 0.0f;
    if (env->ExceptionCheck()) env->ExceptionClear();
    return d > 0 ? d : 1.0f;
}

bool check_tick_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    std::fprintf(stderr, "[win.apk] exception in the render tick:\n");
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

void JNICALL Cocoa_exitProcess(JNIEnv*, jclass, jint code, jstring);

void present_pixels(const uint32_t* argb, int w, int h);

// ---- the guest layer ---------------------------------------------------------
//
// Frames an app's native code renders into its ANativeWindow
// (native_window.h). While they keep coming the window shows them, and the
// view tree's frame is composited over them through the hole the owning
// SurfaceView punches, or not at all when the native window has no known
// owner or sits on top (SurfaceView.macapkNativeWindowLayerZ, pulled by the
// render thread: 0 legacy above-all slot, 1 below the window, 2 on top).
std::atomic<int> g_content_w{0}, g_content_h{0};
std::atomic<int> g_guest_layer_z{0};
std::atomic<unsigned long long> g_guest_last_ms{0};
std::mutex g_tree_lock;
std::vector<uint32_t> g_tree_frame;
int g_tree_w = 0, g_tree_h = 0;
// The native code's last frame (under g_tree_lock). A compositor shows a
// surface's last buffer until the next one, and an app draws only when
// something changes: Flutter drew City Car's sign-in screen, went idle, and
// the tree's next frame covered its hole with black.
std::vector<uint32_t> g_guest_frame;
int g_guest_w = 0, g_guest_h = 0;

bool guest_live() {
    const unsigned long long last = g_guest_last_ms.load();
    return last != 0 && GetTickCount64() - last < 500;
}

// ---- the surface composite ----------------------------------------------------
//
// AOSP stacks a window as: a SurfaceView's buffer BELOW the window at the
// view's rect (APPLICATION_MEDIA_SUBLAYER), the view tree over it -- seen
// through the transparent hole SurfaceView.draw punches -- and a
// setZOrderOnTop surface above the tree. Upstream gets that from CALayers (the
// tree's overlay layer sits above the GL view, each lockCanvas surface has a
// layer of its own). Here it is one composite of: the content GL view's frame
// (when the content is one), the lockCanvas surfaces (presentSurfaceBitmap) at
// the rects their SurfaceViews report, and the view tree's latest frame.
//
// Without it, a window whose content had become the shared GL view (a
// SurfaceView joined the tree after setContentView: a camera preview, a video
// player) dropped every view-tree frame and showed only the GL view's empty
// pbuffer, and a surface's own frames went nowhere: WhatsApp's "Link a device"
// screen was solid black, its "link with phone number" button included.
struct SurfaceLayer {
    std::vector<uint32_t> px;
    int w = 0, h = 0;
};
std::mutex g_comp_lock;
std::map<int, SurfaceLayer> g_surface_layers;
std::vector<uint32_t> g_comp_tree, g_comp_gl, g_comp_out;
int g_comp_tree_w = 0, g_comp_tree_h = 0, g_comp_gl_w = 0, g_comp_gl_h = 0;
std::atomic<bool> g_have_surfaces{false};

jclass g_sv_cls = nullptr;
jmethodID g_sv_rect = nullptr, g_sv_on_top = nullptr;

void resolve_surface_view_pulls(JNIEnv* env) {
    static std::once_flag once;
    std::call_once(once, [env] {
        jclass local = env->FindClass("android/view/SurfaceView");
        if (local) {
            g_sv_cls = static_cast<jclass>(env->NewGlobalRef(local));
            g_sv_rect = env->GetStaticMethodID(g_sv_cls, "macapkSurfaceRectPacked", "(I)J");
            g_sv_on_top = env->GetStaticMethodID(g_sv_cls, "macapkSurfaceZOrderOnTop", "(I)Z");
            env->DeleteLocalRef(local);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    });
}

struct LayerPlace {
    int key;
    long long packed;  // 0: the surface is gone; -1: not shown; else x,y,w,h (16 bits each)
    bool on_top;
    // A placement is a rect, not a sign: x and y are signed, so a surface
    // starting left of the window (a center-cropped camera preview) packs to a
    // NEGATIVE value, and only the two sentinels mean "nothing to draw".
    bool shown() const { return packed != 0 && packed != -1; }
};

// Where each surface is now, asked of its SurfaceView. Outside g_comp_lock: it
// calls into Java.
std::vector<LayerPlace> pull_layer_places(JNIEnv* env) {
    std::vector<LayerPlace> places;
    if (!g_have_surfaces.load()) return places;
    {
        std::lock_guard<std::mutex> hold(g_comp_lock);
        for (auto& [key, layer] : g_surface_layers) places.push_back({key, -1, false});
    }
    resolve_surface_view_pulls(env);
    for (auto& pl : places) {
        if (!g_sv_rect) break;
        pl.packed = env->CallStaticLongMethod(g_sv_cls, g_sv_rect, pl.key);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            pl.packed = -1;
        }
        if (pl.shown() && g_sv_on_top) {
            pl.on_top = env->CallStaticBooleanMethod(g_sv_cls, g_sv_on_top, pl.key) == JNI_TRUE;
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    return places;
}

// Nearest-sampled copy of src (sw x sh) into the rect (x, y, w, h) of dst.
void blit_scaled(uint32_t* dst, int dw, int dh, const uint32_t* src, int sw, int sh, int x, int y, int w,
                 int h) {
    if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return;
    const int y0 = y < 0 ? 0 : y, y1 = (y + h) > dh ? dh : (y + h);
    const int x0 = x < 0 ? 0 : x, x1 = (x + w) > dw ? dw : (x + w);
    for (int yy = y0; yy < y1; ++yy) {
        const uint32_t* srow = src + static_cast<size_t>((yy - y) * sh / h) * sw;
        uint32_t* drow = dst + static_cast<size_t>(yy) * dw;
        for (int xx = x0; xx < x1; ++xx) drow[xx] = srow[(xx - x) * sw / w] | 0xff000000u;
    }
}

// src ("source over", straight alpha) over dst, both w x h.
void blend_over(uint32_t* dst, const uint32_t* src, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        const uint32_t t = src[i];
        const uint32_t a = t >> 24;
        if (a == 255) {
            dst[i] = t;
        } else if (a != 0) {
            const uint32_t g = dst[i];
            uint32_t px = 0xff000000u;
            for (int sh = 0; sh <= 16; sh += 8) {
                const uint32_t c = (((t >> sh) & 0xff) * a + ((g >> sh) & 0xff) * (255 - a)) / 255;
                px |= c << sh;
            }
            dst[i] = px;
        }
    }
}

// Builds the composite from what is stored and presents it. The size is the
// view tree's (the display surface the layer rects are given in), else the GL
// frame's.
void compose_and_present(JNIEnv* env) {
    const std::vector<LayerPlace> places = pull_layer_places(env);
    std::lock_guard<std::mutex> hold(g_comp_lock);
    for (const auto& pl : places) {
        if (pl.packed == 0) g_surface_layers.erase(pl.key);
    }
    g_have_surfaces = !g_surface_layers.empty();
    const int W = g_comp_tree_w > 0 ? g_comp_tree_w : g_comp_gl_w;
    const int H = g_comp_tree_w > 0 ? g_comp_tree_h : g_comp_gl_h;
    if (W <= 0 || H <= 0) return;
    g_comp_out.assign(static_cast<size_t>(W) * H, 0xff000000u);
    uint32_t* out = g_comp_out.data();
    if (!g_comp_gl.empty() && is_gl_view(g_content_view.load())) {
        // Both are drawn at the surface size. One from before a resize is
        // copied as it is (the next tick draws it at the new size), never
        // stretched to pretend otherwise.
        blit_scaled(out, W, H, g_comp_gl.data(), g_comp_gl_w, g_comp_gl_h, 0, 0, g_comp_gl_w, g_comp_gl_h);
    }
    auto draw_layers = [&](bool on_top) {
        for (const auto& pl : places) {
            if (!pl.shown() || pl.on_top != on_top) continue;
            auto it = g_surface_layers.find(pl.key);
            if (it == g_surface_layers.end() || it->second.px.empty()) continue;
            const auto u = static_cast<unsigned long long>(pl.packed);
            const int x = static_cast<int16_t>((u >> 48) & 0xffff), y = static_cast<int16_t>((u >> 32) & 0xffff);
            const int w = static_cast<int>((u >> 16) & 0xffff), h = static_cast<int>(u & 0xffff);
            blit_scaled(out, W, H, it->second.px.data(), it->second.w, it->second.h, x, y, w, h);
        }
    };
    draw_layers(false);
    if (g_comp_tree_w == W && g_comp_tree_h == H && !g_comp_tree.empty()) {
        blend_over(out, g_comp_tree.data(), g_comp_out.size());
    }
    draw_layers(true);
    present_pixels(out, W, H);
}

// Whether frames go through the composite: the content is a GL view (the view
// tree is then its overlay), or a surface has shown a frame.
bool composite_active() { return is_gl_view(g_content_view.load()) || g_have_surfaces.load(); }

void clear_composite_tree() {
    std::lock_guard<std::mutex> hold(g_comp_lock);
    g_comp_tree.clear();
    g_comp_tree_w = g_comp_tree_h = 0;
    g_comp_gl.clear();
    g_comp_gl_w = g_comp_gl_h = 0;
}

void composite_tree_frame(JNIEnv* env, const uint32_t* argb, int w, int h) {
    {
        std::lock_guard<std::mutex> hold(g_comp_lock);
        g_comp_tree.assign(argb, argb + static_cast<size_t>(w) * h);
        g_comp_tree_w = w;
        g_comp_tree_h = h;
    }
    compose_and_present(env);
}

// The content GL view's frame. Alone (no tree overlay, no surfaces) it is
// presented as before; an unchanged frame is not re-composited.
void composite_gl_frame(JNIEnv* env, const std::vector<uint32_t>& px, int w, int h) {
    bool alone;
    {
        std::lock_guard<std::mutex> hold(g_comp_lock);
        alone = g_comp_tree.empty() && g_surface_layers.empty();
        if (!alone && g_comp_gl_w == w && g_comp_gl_h == h && g_comp_gl == px) return;
        g_comp_gl = px;
        g_comp_gl_w = w;
        g_comp_gl_h = h;
    }
    if (alone) {
        present_pixels(px.data(), w, h);
        return;
    }
    compose_and_present(env);
}

void JNICALL Cocoa_presentSurfaceBitmap(JNIEnv* env, jclass, jint key, jintArray argb, jint w, jint h) {
    if (!argb || w <= 0 || h <= 0 || env->GetArrayLength(argb) < w * h) return;
    jint* px = env->GetIntArrayElements(argb, nullptr);
    if (!px) return;
    {
        std::lock_guard<std::mutex> hold(g_comp_lock);
        SurfaceLayer& layer = g_surface_layers[key];
        layer.px.assign(reinterpret_cast<const uint32_t*>(px), reinterpret_cast<const uint32_t*>(px) + static_cast<size_t>(w) * h);
        layer.w = w;
        layer.h = h;
        g_have_surfaces = true;
    }
    env->ReleaseIntArrayElements(argb, px, JNI_ABORT);
    if (!guest_live()) compose_and_present(env);
}

// The view tree's latest frame, kept for compositing once a guest has shown.
void keep_tree_frame(const uint32_t* argb, int w, int h) {
    if (g_guest_last_ms.load() == 0) return;
    std::lock_guard<std::mutex> hold(g_tree_lock);
    g_tree_frame.assign(argb, argb + static_cast<size_t>(w) * h);
    g_tree_w = w;
    g_tree_h = h;
}

// One GL view's tick: ANGLE's context on its pbuffer, the renderer's
// lifecycle and frame, then the pixels for the window when it is the content.
void gl_tick(JNIEnv* env, jlong view, Renderer& r, jobject callbacks, jmethodID changed, jmethodID draw,
             int w, int h) {
    namespace ft = frame_timing;
    const bool timed = ft::on();
    int64_t t = timed ? ft::now_us() : 0;
    const bool ready = gl_frame_begin(view, w, h);
    if (timed) ft::add(ft::kBegin, t);
    if (r.announced_w != w || r.announced_h != h) {
        r.announced_w = w;
        r.announced_h = h;
        env->CallVoidMethod(callbacks, changed, w, h);
        check_tick_exception(env);
    }
    t_tick_withheld = false;
    if (timed) t = ft::now_us();
    env->CallVoidMethod(callbacks, draw);
    check_tick_exception(env);
    if (timed) ft::add(ft::kDraw, t);
    static std::vector<uint32_t> pixels;
    int fw = 0, fh = 0;
    if (ready && !t_tick_withheld && view == g_content_view.load() && !guest_live()) {
        if (timed) {
            // Timing only: so that "read" is the copy alone.
            t = ft::now_us();
            gl_frame_finish();
            ft::add(ft::kGpu, t);
            t = ft::now_us();
        }
        if (gl_frame_read(&pixels, &fw, &fh)) {
            if (timed) {
                ft::add(ft::kRead, t);
                t = ft::now_us();
            }
            composite_gl_frame(env, pixels, fw, fh);
            if (timed) {
                ft::add(ft::kCompose, t);
                ft::frame_done(fw, fh);
            }
        }
    }
}

void pull_guest_layer_z(JNIEnv* env) {
    static jclass cls = nullptr;
    static jmethodID z = nullptr;
    static bool looked = false;
    if (!looked) {
        looked = true;
        jclass local = env->FindClass("android/view/SurfaceView");
        if (local) {
            cls = static_cast<jclass>(env->NewGlobalRef(local));
            z = env->GetStaticMethodID(cls, "macapkNativeWindowLayerZ", "()I");
            env->DeleteLocalRef(local);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (!z) return;
    g_guest_layer_z = env->CallStaticIntMethod(cls, z);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// The 60 Hz tick. Sleep(16) ran at the scheduler's granularity -- 15.6 ms
// by default, so a tick every one or two of those: 40 to 60 frames a second,
// unevenly. A high-resolution waitable timer (Windows 10 1803 and later;
// the multimedia timer's 1 ms resolution stands in before that) wakes the
// thread at the next multiple of 1/60 s. A tick that overran its slot starts
// the next one at once; it is not made up for.
class TickClock {
public:
    TickClock() {
        QueryPerformanceFrequency(&freq_);
        QueryPerformanceCounter(&next_);
        timer_ = CreateWaitableTimerExW(nullptr, nullptr, 0x00000002 /* HIGH_RESOLUTION */, TIMER_ALL_ACCESS);
        if (!timer_) {
            timer_ = CreateWaitableTimerExW(nullptr, nullptr, 0, TIMER_ALL_ACCESS);
            timeBeginPeriod(1);
        }
    }
    void wait() {
        next_.QuadPart += freq_.QuadPart / 60;
        LARGE_INTEGER now;
        QueryPerformanceCounter(&now);
        if (next_.QuadPart <= now.QuadPart) {
            next_ = now;  // behind: tick now, on a fresh schedule
            return;
        }
        if (!timer_) {
            Sleep(static_cast<DWORD>((next_.QuadPart - now.QuadPart) * 1000 / freq_.QuadPart));
            return;
        }
        LARGE_INTEGER due;
        due.QuadPart = -((next_.QuadPart - now.QuadPart) * 10000000LL / freq_.QuadPart);
        if (due.QuadPart >= 0) due.QuadPart = -1;
        SetWaitableTimer(timer_, &due, 0, nullptr, nullptr, FALSE);
        WaitForSingleObject(timer_, INFINITE);
    }

private:
    HANDLE timer_ = nullptr;
    LARGE_INTEGER freq_{}, next_{};
};

DWORD WINAPI render_thread(void*) {
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("winapk-render"), nullptr};
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return 1;
    if (g_density.load() <= 0) g_density = device_density(env);
    const char* e = std::getenv("WINAPK_HEADLESS_EXIT_AFTER_FRAMES");
    const long exit_after = (e && *e) ? std::atol(e) : 0L;
    long ticks_since_first = 0;
    jclass cls = env->FindClass("com/macapk/loader/MetalSurfaceCallbacks");
    const jmethodID changed = env->GetMethodID(cls, "macapkSurfaceChanged", "(II)V");
    const jmethodID draw = env->GetMethodID(cls, "macapkDrawFrame", "()V");
    env->DeleteLocalRef(cls);
    // A new phone size is a display change (DisplayConfig, upstream's
    // v1.0.1637 model): it is noted first, and the renderers hear the new
    // surface size only once the Configuration and DisplayMetrics describe it
    // (macapkNoteSurfaceSize answers true), as cocoa_bridge.mm holds its
    // re-fire, so a surface is never re-created against the old
    // configuration. Bounded: after ~0.5 s the size goes out anyway. The
    // first size is Boot's own, which it seeds from the canvas.
    jclass display = env->FindClass("com/macapk/loader/DisplayConfig");
    const jmethodID note = display ? env->GetStaticMethodID(display, "macapkNoteSurfaceSize", "(II)Z") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (display) display = static_cast<jclass>(env->NewGlobalRef(display));
    int shown_w = 0, shown_h = 0, asked_w = 0, asked_h = 0, held = 0;
    TickClock clock;
    for (;;) {
        clock.wait();
        // The surface: the window's box in pixels, or the phone at the density.
        int w = g_surface_w.load(), h = g_surface_h.load();
        if (w <= 0 || h <= 0) {
            w = px_of(g_phone_w.load());
            h = px_of(g_phone_h.load());
        }
        if (w != shown_w || h != shown_h) {
            // The bound is per size: a drag brings a new one every few ticks,
            // and each gets its own wait for the commit.
            if (w != asked_w || h != asked_h) {
                asked_w = w;
                asked_h = h;
                held = 0;
            }
            bool committed = shown_w == 0 || !note;
            if (!committed) {
                committed = env->CallStaticBooleanMethod(display, note, w, h) == JNI_TRUE;
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    committed = true;
                }
            }
            if (committed || ++held > 30) {
                shown_w = w;
                shown_h = h;
                held = 0;
            } else {
                w = shown_w;
                h = shown_h;
            }
        }
        g_content_w = w;
        g_content_h = h;
        if (g_guest_last_ms.load() != 0) pull_guest_layer_z(env);
        std::vector<std::pair<jlong, jobject>> now;
        std::vector<jlong> dropped;
        {
            std::lock_guard<std::mutex> hold(g_renderer_lock);
            for (auto& [view, r] : g_renderers) now.emplace_back(view, env->NewLocalRef(r.callbacks));
            dropped.swap(g_released_gl_views);
        }
        for (jlong view : dropped) gl_view_release(view);
        for (auto& [view, callbacks] : now) {
            Renderer r;
            {
                std::lock_guard<std::mutex> hold(g_renderer_lock);
                auto it = g_renderers.find(view);
                if (it == g_renderers.end()) {
                    env->DeleteLocalRef(callbacks);
                    continue;
                }
                r = it->second;
            }
            if (r.gl) {
                gl_tick(env, view, r, callbacks, changed, draw, w, h);
            } else {
                if (r.announced_w != w || r.announced_h != h) {
                    r.announced_w = w;
                    r.announced_h = h;
                    env->CallVoidMethod(callbacks, changed, w, h);
                    check_tick_exception(env);
                }
                env->CallVoidMethod(callbacks, draw);
                check_tick_exception(env);
            }
            {
                std::lock_guard<std::mutex> hold(g_renderer_lock);
                auto it = g_renderers.find(view);
                if (it != g_renderers.end() && it->second.callbacks == r.callbacks) {
                    it->second.announced_w = r.announced_w;
                    it->second.announced_h = r.announced_h;
                }
            }
            env->DeleteLocalRef(callbacks);
        }
        if (exit_after > 0 && g_frames > 0 && ++ticks_since_first >= exit_after) {
            std::fprintf(stderr,
                         "[win.apk] %ld frames presented in %ld display frames; exiting as "
                         "WINAPK_HEADLESS_EXIT_AFTER_FRAMES asks\n",
                         g_frames.load(), ticks_since_first);
            Cocoa_exitProcess(env, nullptr, 0, nullptr);
        }
    }
}

void JNICALL Cocoa_setRenderer(JNIEnv* env, jclass, jlong view, jobject callbacks) {
    static bool started = false;
    std::lock_guard<std::mutex> hold(g_renderer_lock);
    auto it = g_renderers.find(view);
    if (it != g_renderers.end()) {
        env->DeleteGlobalRef(it->second.callbacks);
        g_renderers.erase(it);
    }
    if (!callbacks) return;
    Renderer r;
    r.callbacks = env->NewGlobalRef(callbacks);
    r.gl = is_gl_view(view);
    g_renderers[view] = r;
    if (!started) {
        started = true;
        env->GetJavaVM(&g_vm);
        HANDLE t = CreateThread(nullptr, 16 * 1024 * 1024, render_thread, nullptr,
                                STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
        if (t) CloseHandle(t);
    }
}

void JNICALL Cocoa_releaseRenderer(JNIEnv* env, jclass, jlong view) {
    std::lock_guard<std::mutex> hold(g_renderer_lock);
    auto it = g_renderers.find(view);
    if (it == g_renderers.end()) return;
    if (it->second.gl) g_released_gl_views.push_back(view);
    env->DeleteGlobalRef(it->second.callbacks);
    g_renderers.erase(it);
}

// Upstream hands the boot thread to [NSApp run] here. The window has a UI
// thread of its own (win32_window.cpp), so there is nothing to run: the app's
// own threads carry on, and the process ends through exitProcess.
void JNICALL Cocoa_runLoop(JNIEnv*, jclass) {
    for (;;) Sleep(INFINITE);
}

// Upstream ends the process with _exit: no Java shutdown, no atexit. The
// Windows equivalent is TerminateProcess on ourselves.
// A non-null `relaunch` (the file of intents MacApkProcessExit marshals for a
// ProcessPhoenix-style restart, or SpecialAccessHandler's after a permission
// was turned off) asks for the app to come back once this process is gone, as
// the ActivityManager restarts it. The same command line starts again, at the
// app's launcher activity (the intents in the file are not replayed), and
// waits for this process to end before it claims the app's single instance
// (WINAPK_RELAUNCH_AFTER, runtime_main.cpp).
void JNICALL Cocoa_exitProcess(JNIEnv*, jclass, jint code, jstring relaunch) {
    // The user is closing the app and its teardown asked to exit (System.exit
    // in onDestroy is common): the run still ended on the user's word, so it
    // is no failure whatever the code, and the app does not come back.
    if (g_closing.load()) {
        std::fprintf(stderr, "[win.apk] close: the app left by itself during its teardown\n");
        code = 0;
        relaunch = nullptr;
    }
    std::fprintf(stderr, "[win.apk] exitProcess(%d)%s\n", static_cast<int>(code), relaunch ? " with a relaunch" : "");
    std::fflush(stderr);
    if (relaunch) {
        SetEnvironmentVariableW(L"WINAPK_RELAUNCH_AFTER", std::to_wstring(GetCurrentProcessId()).c_str());
        std::wstring cmd = GetCommandLineW();
        STARTUPINFOW si{};
        si.cb = sizeof(si);
        si.dwFlags = STARTF_USESTDHANDLES;  // the same log
        si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
        PROCESS_INFORMATION pi{};
        std::fprintf(stderr, "[win.apk] relaunching the app after it ends\n");
        if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, 0, nullptr, nullptr, &si, &pi)) {
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
        } else {
            std::fprintf(stderr, "[win.apk] the relaunch failed (error %lu)\n", GetLastError());
        }
    }
    cds_before_exit();  // the framework archive this start still owes
    std::fflush(stdout);
    std::fflush(stderr);
    TerminateProcess(GetCurrentProcess(), static_cast<UINT>(code));
}

void JNICALL Cocoa_quitBackstopExit(JNIEnv* env, jclass cls) { Cocoa_exitProcess(env, cls, 0, nullptr); }

// Writes 32-bit ARGB pixels as a top-down BMP, which keeps the byte order
// (BGRA in memory) and needs no encoder. It goes to a temporary file that then
// replaces `path`, so the process ending mid-write (the headless exit runs on
// the render thread, not the presenting one) never leaves half a frame.
bool write_bmp(const wchar_t* path, const jint* argb, int w, int h) {
    const uint32_t pixels = static_cast<uint32_t>(w) * static_cast<uint32_t>(h) * 4;
    BITMAPFILEHEADER fh{};
    BITMAPINFOHEADER ih{};
    fh.bfType = 0x4d42;  // "BM"
    fh.bfOffBits = sizeof(fh) + sizeof(ih);
    fh.bfSize = fh.bfOffBits + pixels;
    ih.biSize = sizeof(ih);
    ih.biWidth = w;
    ih.biHeight = -h;  // top-down
    ih.biPlanes = 1;
    ih.biBitCount = 32;
    ih.biCompression = BI_RGB;
    const std::wstring tmp = std::wstring(path) + L".part";
    HANDLE f = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD n = 0;
    bool ok = WriteFile(f, &fh, sizeof(fh), &n, nullptr) && WriteFile(f, &ih, sizeof(ih), &n, nullptr) &&
              WriteFile(f, argb, pixels, &n, nullptr);
    CloseHandle(f);
    return ok && MoveFileExW(tmp.c_str(), path, MOVEFILE_REPLACE_EXISTING);
}

// WINAPK_HEADLESS_FRAME: the file every presented frame is also written to.
const std::wstring& headless_frame_path() {
    static const std::wstring path = [] {
        wchar_t buf[MAX_PATH * 4];
        const DWORD n = GetEnvironmentVariableW(L"WINAPK_HEADLESS_FRAME", buf, MAX_PATH * 4);
        return (n > 0 && n < MAX_PATH * 4) ? std::wstring(buf, n) : std::wstring();
    }();
    return path;
}

// A frame for the window (and for WINAPK_HEADLESS_FRAME): from the view tree,
// or from the content GL view (gl_tick).
void present_pixels(const uint32_t* argb, int w, int h) {
    const long frame = ++g_frames;
    if (frame == 1) {
        std::fprintf(stderr, "[win.apk] first frame presented (%dx%d)\n", w, h);
        std::fflush(stderr);
        cds_first_frame();
    }
    const std::wstring& path = headless_frame_path();
    // An ARGB int is B, G, R, A in memory: the byte order of a 32-bit DIB.
    window_present(argb, w, h);
    if (!path.empty()) {
        static std::mutex lock;
        std::lock_guard<std::mutex> hold(lock);
        write_bmp(path.c_str(), reinterpret_cast<const jint*>(argb), w, h);
    }
}

}  // namespace

// Whether the app has presented a frame yet (host_natives.h).
bool frame_presented() { return g_frames.load() > 0; }

bool content_pixel_size(int* w, int* h) {
    *w = g_content_w.load();
    *h = g_content_h.load();
    return *w > 0 && *h > 0;
}

void phone_size_dp(int* w, int* h) {
    *w = g_phone_w.load();
    *h = g_phone_h.load();
}

void present_over_guest(const uint32_t* argb, int w, int h);

void guest_frame_present(const uint32_t* argb, int w, int h) {
    if (g_guest_last_ms.exchange(GetTickCount64()) == 0) {
        std::fprintf(stderr, "[win.apk] native window: first frame from the app's native code (%dx%d)\n", w, h);
    }
    if (g_guest_layer_z.load() != 1) {
        present_pixels(argb, w, h);
        return;
    }
    {
        std::lock_guard<std::mutex> hold(g_tree_lock);
        g_guest_frame.assign(argb, argb + static_cast<size_t>(w) * h);
        g_guest_w = w;
        g_guest_h = h;
    }
    present_over_guest(argb, w, h);
}

// The view tree over a guest frame: where the tree is transparent (the
// SurfaceView's hole) the guest shows. Both are the surface's size; an app
// that set its own buffer geometry (a game rendering at a lower resolution)
// is scaled up to the tree, as Android's compositor scales its buffer to the
// window. Otherwise a size they disagree on means one of them is from before
// a resize: the guest's frame goes out alone, at its own size (the window
// shows it unscaled), and the next frame of each brings them together.
void present_over_guest(const uint32_t* argb, int w, int h) {
    thread_local std::vector<uint32_t> out;
    int tw = 0, th = 0;
    {
        std::lock_guard<std::mutex> hold(g_tree_lock);
        tw = g_tree_w;
        th = g_tree_h;
        if (tw <= 0 || th <= 0 || ((tw != w || th != h) && !native_window_has_geometry())) {
            present_pixels(argb, w, h);
            return;
        }
        out.resize(static_cast<size_t>(tw) * th);
        for (int y = 0; y < th; ++y) {
            const uint32_t* grow = argb + static_cast<size_t>(y * h / th) * w;
            const uint32_t* trow = g_tree_frame.data() + static_cast<size_t>(y) * tw;
            uint32_t* orow = out.data() + static_cast<size_t>(y) * tw;
            for (int x = 0; x < tw; ++x) {
                const uint32_t t = trow[x], g = grow[x * w / tw];
                const uint32_t a = t >> 24;
                if (a == 255) {
                    orow[x] = t;
                } else if (a == 0) {
                    orow[x] = g | 0xff000000u;
                } else {
                    uint32_t px = 0xff000000u;
                    for (int sh = 0; sh <= 16; sh += 8) {
                        const uint32_t c = (((t >> sh) & 0xff) * a + ((g >> sh) & 0xff) * (255 - a)) / 255;
                        px |= c << sh;
                    }
                    orow[x] = px;
                }
            }
        }
    }
    present_pixels(out.data(), tw, th);
}

namespace {

// The view tree's frame. While a GL view is the window's content the view
// tree reaches the window as the overlay layer instead (presentOverlayBitmap).
void JNICALL Cocoa_presentBitmap(JNIEnv* env, jclass, jintArray argb, jint w, jint h) {
    if (!argb || w <= 0 || h <= 0 || env->GetArrayLength(argb) < w * h) return;
    jint* px = env->GetIntArrayElements(argb, nullptr);
    if (!px) return;
    keep_tree_frame(reinterpret_cast<const uint32_t*>(px), w, h);
    // While the app's native code shows frames, its next one brings this along.
    // With a GL content view or a surface on screen, the tree is their overlay.
    // When it has gone quiet, the tree still goes over its last frame.
    if (!guest_live()) {
        thread_local std::vector<uint32_t> guest;
        int gw = 0, gh = 0;
        if (g_guest_last_ms.load() != 0 && g_guest_layer_z.load() == 1) {
            std::lock_guard<std::mutex> hold(g_tree_lock);
            guest = g_guest_frame;
            gw = g_guest_w;
            gh = g_guest_h;
        }
        if (gw > 0 && gh > 0) present_over_guest(guest.data(), gw, gh);
        else if (composite_active()) composite_tree_frame(env, reinterpret_cast<const uint32_t*>(px), w, h);
        else present_pixels(reinterpret_cast<const uint32_t*>(px), w, h);
    }
    env->ReleaseIntArrayElements(argb, px, JNI_ABORT);
}

// A view tree frame of which only [l, r) x [t, b) was drawn again (a frame
// clipped to its damage: ViewCanvasDriver, FRAMESPEED S2): those rows alone are
// read from the array, patched into the frame the window holds, and repainted,
// instead of the whole frame being copied twice and blitted. Only for the plain
// case -- the tree is the window's whole content and no frame file is written;
// false otherwise, and the frame goes the whole way.
bool present_changed_rect(JNIEnv* env, jintArray argb, jint w, jint h, jint l, jint t, jint r, jint b, jint flags) {
    constexpr jint kForceFull = 1, kValidate = 2;  // NativeCocoa.PRESENT_FLAG_*
    if (flags & (kForceFull | kValidate)) return false;  // new bitmap; or drawn whole, the rectangle only claimed
    if (!argb || w <= 0 || h <= 0 || env->GetArrayLength(argb) < w * h) return false;
    if (l < 0 || t < 0 || r > w || b > h || r <= l || b <= t) return false;
    if (l == 0 && t == 0 && r == w && b == h) return false;
    if (g_guest_last_ms.load() != 0 || composite_active() || !headless_frame_path().empty()) return false;
    thread_local std::vector<uint32_t> rows;
    const size_t rw = static_cast<size_t>(r - l);
    rows.resize(rw * static_cast<size_t>(b - t));
    for (jint y = t; y < b; ++y) {
        env->GetIntArrayRegion(argb, y * w + l, r - l, reinterpret_cast<jint*>(rows.data() + (y - t) * rw));
    }
    if (!window_present_rect(rows.data(), w, h, l, t, r, b)) return false;
    ++g_frames;
    return true;
}

// The diffing present. There is no layer to diff against here, so a frame is
// published (PRESENT_PUBLISHED) whole, or as the rectangle the driver says is
// all that changed.
jint JNICALL Cocoa_presentFrame(JNIEnv* env, jclass cls, jintArray argb, jint w, jint h, jint l, jint t, jint r,
                                jint b, jint flags) {
    if (!present_changed_rect(env, argb, w, h, l, t, r, b, flags)) Cocoa_presentBitmap(env, cls, argb, w, h);
    return 1;
}

constexpr const char* kCls = "com.macapk.loader.NativeCocoa";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "init", "()Z", Cocoa_init),
    WINAPK_HOST_NATIVE(kCls, "setPhoneSize", "(II)V", Cocoa_setPhoneSize),
    WINAPK_HOST_NATIVE(kCls, "phoneWidth", "()I", Cocoa_phoneWidth),
    WINAPK_HOST_NATIVE(kCls, "phoneHeight", "()I", Cocoa_phoneHeight),
    WINAPK_HOST_NATIVE(kCls, "displayHz", "()I", Cocoa_displayHz),
    WINAPK_HOST_NATIVE(kCls, "backingScale", "()F", Cocoa_backingScale),
    WINAPK_HOST_NATIVE(kCls, "applyRequestedOrientation", "(Z)V", Cocoa_applyRequestedOrientation),
    WINAPK_HOST_NATIVE(kCls, "createWindow", "(Ljava/lang/String;)J", Cocoa_createWindow),
    WINAPK_HOST_NATIVE(kCls, "createMetalSurfaceView", "(II)J", Cocoa_createView),
    WINAPK_HOST_NATIVE(kCls, "createMetalSurfaceViewForGl", "(II)J", Cocoa_createGlView),
    WINAPK_HOST_NATIVE(kCls, "getPlaceholderWindow", "()J", Cocoa_getPlaceholderWindow),
    WINAPK_HOST_NATIVE(kCls, "runLoop", "()V", Cocoa_runLoop),
    WINAPK_HOST_NATIVE(kCls, "exitProcess", "(ILjava/lang/String;)V", Cocoa_exitProcess),
    WINAPK_HOST_NATIVE(kCls, "quitBackstopExit", "()V", Cocoa_quitBackstopExit),
    WINAPK_HOST_NATIVE(kCls, "presentBitmap", "([III)V", Cocoa_presentBitmap),
    WINAPK_HOST_NATIVE(kCls, "presentSurfaceBitmap", "(I[III)V", Cocoa_presentSurfaceBitmap),
    WINAPK_HOST_NATIVE(kCls, "registerTouchDispatcher", "(Lcom/macapk/loader/TouchDispatcher;)V",
                       Cocoa_registerTouchDispatcher),
    WINAPK_HOST_NATIVE(kCls, "showWindow", "(J)V", Cocoa_showWindow),
    WINAPK_HOST_NATIVE(kCls, "moveTaskToBack", "()V", Cocoa_moveTaskToBack),
    WINAPK_HOST_NATIVE(kCls, "moveTaskToFront", "()V", Cocoa_moveTaskToFront),
    WINAPK_HOST_NATIVE(kCls, "setStartingWindowColor", "(I)V", Cocoa_setStartingWindowColor),
    WINAPK_HOST_NATIVE(kCls, "setSystemProperty", "(Ljava/lang/String;Ljava/lang/String;)V",
                       Cocoa_setSystemProperty),
    WINAPK_HOST_NATIVE(kCls, "setSystemFontsDir", "(Ljava/lang/String;)V", Cocoa_setSystemFontsDir),
    WINAPK_HOST_NATIVE(kCls, "setSharedStorageRoot", "(Ljava/lang/String;)V", Cocoa_setSharedStorageRoot),
    WINAPK_HOST_NATIVE(kCls, "remapProcPath", "(Ljava/lang/String;)Ljava/lang/String;", Cocoa_remapProcPath),
    WINAPK_HOST_NATIVE(kCls, "hostFontFile", "(Ljava/lang/String;)Ljava/lang/String;", Cocoa_hostFontFile),
    WINAPK_HOST_NATIVE(kCls, "openAppLinkWindow", "(Ljava/lang/String;Ljava/lang/String;)Z",
                       Cocoa_openAppLinkWindow),
    WINAPK_HOST_NATIVE(kCls, "setNativeLibDir", "(Ljava/lang/String;)V", Cocoa_setNativeLibDir),
    WINAPK_HOST_NATIVE(kCls, "setDataSubdir", "(Ljava/lang/String;)V", Cocoa_setDataSubdir),
    WINAPK_HOST_NATIVE(kCls, "setAppData", "(Ljava/lang/String;Ljava/lang/String;)V", Cocoa_setAppData),
    WINAPK_HOST_NATIVE(kCls, "applyWindowOrientation", "(I)V", Cocoa_applyWindowOrientation),
    WINAPK_HOST_NATIVE(kCls, "applyWindowOrientationFor", "(Ljava/lang/String;III)V", Cocoa_applyWindowOrientationFor),
    WINAPK_HOST_NATIVE(kCls, "restoreUserOrientation", "()V", Cocoa_restoreUserOrientation),
    WINAPK_HOST_NATIVE(kCls, "windowOverrideOrientation", "(Ljava/lang/String;I)I", Cocoa_windowOverrideOrientation),
    WINAPK_HOST_NATIVE(kCls, "activityWindowTransition", "(Ljava/lang/String;IZZIIIII)[I",
                       Cocoa_activityWindowTransition),
    WINAPK_HOST_NATIVE(kCls, "setTopActivityConfigChanges", "(I)V", Cocoa_setTopActivityConfigChanges),
    WINAPK_HOST_NATIVE(kCls, "setMaxAspectRatio", "(F)V", Cocoa_setMaxAspectRatio),
    WINAPK_HOST_NATIVE(kCls, "setContentView", "(JJ)V", Cocoa_setContentView),
    WINAPK_HOST_NATIVE(kCls, "closeWindow", "(J)V", Cocoa_closeWindow),
    WINAPK_HOST_NATIVE(kCls, "setSecureContent", "(Z)V", Cocoa_setSecureContent),
    WINAPK_HOST_NATIVE(kCls, "openHostUrl", "(Ljava/lang/String;)Z", Cocoa_openHostUrl),
    WINAPK_HOST_NATIVE(kCls, "takeHostUrls", "()[Ljava/lang/String;", Cocoa_takeHostUrls),
    WINAPK_HOST_NATIVE(kCls, "urlSchemeHandlers", "(Ljava/lang/String;)[Ljava/lang/String;", Cocoa_urlSchemeHandlers),
    WINAPK_HOST_NATIVE(kCls, "setUrlSchemeDefault", "(Ljava/lang/String;Ljava/lang/String;)I", Cocoa_setUrlSchemeDefault),
    WINAPK_HOST_NATIVE(kCls, "presentOverlayBitmap", "([III)V", Cocoa_presentOverlayBitmap),
    WINAPK_HOST_NATIVE(kCls, "clearOverlayBitmap", "()V", Cocoa_clearOverlayBitmap),
    WINAPK_HOST_NATIVE(kCls, "hostHideWindowsForQuit", "()Z", Cocoa_hostHideWindowsForQuit),
    WINAPK_HOST_NATIVE(kCls, "showTranslationOverlay", "(Ljava/lang/String;)V", Cocoa_showTranslationOverlay),
    WINAPK_HOST_NATIVE(kCls, "updateTranslationOverlay", "(Ljava/lang/String;)V", Cocoa_updateTranslationOverlay),
    WINAPK_HOST_NATIVE(kCls, "hideTranslationOverlay", "()V", Cocoa_hideTranslationOverlay),
    WINAPK_HOST_NATIVE(kCls, "beginWithheldTick", "(Z)Z", Cocoa_beginWithheldTick),
    WINAPK_HOST_NATIVE(kCls, "hostRenderTick", "()Z", Cocoa_hostRenderTick),
    WINAPK_HOST_NATIVE(kCls, "setWindowTitle", "(JLjava/lang/String;)V", Cocoa_setWindowTitle),
    WINAPK_HOST_NATIVE(kCls, "presentFrame", "([IIIIIIII)I", Cocoa_presentFrame),
    WINAPK_HOST_NATIVE(kCls, "setRenderer", "(JLcom/macapk/loader/MetalSurfaceCallbacks;)V", Cocoa_setRenderer),
    WINAPK_HOST_NATIVE(kCls, "releaseRenderer", "(J)V", Cocoa_releaseRenderer),
};

}  // namespace

HostMethods native_cocoa_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

// WINAPK_PHONE_SIZE=<width>x<height>, in dp, sets the phone outright; else it
// is 360x800. Apps lay out for today's tall phones: at upstream's 640 dp
// WhatsApp's welcome screen centres its content up under the top bar, and the
// content then takes the taps meant for the bar's overflow button. A screen
// too small for the window gets a lower density (win32_window.h), never a
// shorter phone. Malformed or out-of-range values are ignored. The window size
// the user last gave this app (save_window_size) comes before both.
bool launch_maximized() { return saved_window_maximized(); }

void launch_phone_size(int* w, int* h) {
    *w = 360;
    *h = 800;
    if (saved_window_size(w, h)) return;
    const char* e = std::getenv("WINAPK_PHONE_SIZE");
    if (!e || !*e) return;
    int pw = 0, ph = 0;
    char x = 0;
    if (std::sscanf(e, "%d%c%d", &pw, &x, &ph) != 3 || (x != 'x' && x != 'X')) return;
    if (pw < 200 || ph < 200 || pw > 4000 || ph > 4000) return;
    *w = pw;
    *h = ph;
}

void set_app_dir(const std::wstring& dir) {
    {
        std::lock_guard<std::mutex> hold(g_app_dir_lock);
        g_app_dir = dir;
    }
    std::lock_guard<std::mutex> hold(g_icon_lock);
    g_icon_path = dir + L"\\icon.png";
}

std::wstring app_dir() {
    std::lock_guard<std::mutex> hold(g_app_dir_lock);
    return g_app_dir;
}

void prepare_host_url_queue(JNIEnv* env) {
    std::lock_guard<std::mutex> hold(g_host_urls_lock);
    if (g_intake_cls) return;
    jclass cls = env->FindClass("com/macapk/loader/HostUrlIntake");
    if (cls) {
        g_intake_on_url = env->GetStaticMethodID(cls, "macapkOnHostUrl", "()V");
        if (g_intake_on_url) g_intake_cls = static_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void queue_host_url(JNIEnv* env, const std::wstring& url) {
    jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> hold(g_host_urls_lock);
        g_host_urls.push_back(url);
        cls = g_intake_cls;
    }
    if (!env || !cls) return;
    env->CallStaticVoidMethod(cls, g_intake_on_url);
    if (env->ExceptionCheck()) {
        std::fprintf(stderr, "[win.apk] exception in HostUrlIntake.macapkOnHostUrl:\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

}  // namespace winapk::host
