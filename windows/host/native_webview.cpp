// native_webview.cpp -- android.webkit.MacApkWebViewBridge on Windows: the
// app's own WebViews on WebView2.
//
// Upstream's android.webkit.WebView is a complete Java WebView over an engine
// it reaches through MacApkWebViewBridge's natives (runtime/src/
// webview_bridge.mm, on WKWebView). This file is that engine on WebView2, so
// the Java side -- WebViewClient and WebChromeClient callbacks, the
// @JavascriptInterface shim, evaluateJavascript, CookieManager -- runs as it
// is. The contract is upstream's; only the engine differs:
//
//  * Each WebView is a WebView2 controller in a child window of the app's
//    window, placed at the view's rect (nativeSetFrame, in the app's surface
//    pixels, scaled to the window's client area) and cut by the rects of the
//    app's own views drawn over it (nativeSetOccluders, as a window region),
//    so a toolbar over the page still shows and takes its clicks. The page is
//    zoomed so one CSS pixel is one dp, as on a device. The window is the
//    WebView2 thread's (webview2_host.h); its parent is the app window, on its
//    own UI thread. The app's WebViews use the default profile of the app's
//    WebView2 user data, the browser tab (native_browser_tab.cpp) another.
//
//  * Events go to MacApkWebViewBridge.onEvent, which posts them to the view's
//    UI thread. shouldOverrideUrlLoading is synchronous on a device: here the
//    navigation waits (bounded) for nativeNavDecision. Navigations the app
//    starts itself (loadUrl, reload, history) are not asked about, redirects
//    are, as on a device.
//
//  * @JavascriptInterface: the Java side injects a shim that calls
//    window.prompt("\1macapk-jsb\1" + JSON). With the default script dialogs
//    off, that prompt is a ScriptDialogOpening event; its deferral keeps the
//    page's script blocked while onJsBridgeCall runs the app's method on a
//    "JavaBridge" thread, and the method's JSON result becomes prompt()'s
//    return value -- the synchronous call with a return value a device has,
//    which postMessage could not give. Other alert/confirm/prompt dialogs go
//    to WebChromeClient the same way.
//
//  * WebViewClient.shouldInterceptRequest: every request of a view whose
//    client overrides it is held (a WebResourceRequested deferral) while the
//    app's method runs on a "WebViewIntercept" thread; its response is served
//    instead of the network. Cordova/Capacitor apps load from it.
//
//  * file:///android_asset/ pages are copied to real files by
//    MacApkWebViewBridge.macapkMaterializeAssetPage (upstream's own helper)
//    and loaded from there; URLs that go back to Java are mapped back.

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <ctime>
#include <memory>
#include <cstdio>
#include <cwctype>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <windows.h>
#include <shlwapi.h>

#include "host_natives.h"
#include "webview2_host.h"
#include "win32_window.h"

namespace winapk::host {

namespace {

using wv2::ComPtr;

// MacApkWebViewBridge event codes (keep in step with the Java side).
enum {
    EV_PAGE_STARTED = 1,
    EV_PAGE_FINISHED = 2,
    EV_PAGE_COMMIT_VISIBLE = 3,
    EV_RECEIVED_ERROR = 4,
    EV_PROGRESS = 5,
    EV_TITLE = 6,
    EV_URL_CHANGED = 7,
    EV_JS_RESULT = 8,
    EV_SHOULD_OVERRIDE = 9,
    EV_HISTORY_UPDATE = 10,
    EV_JS_ALERT = 11,
    EV_JS_CONFIRM = 12,
    EV_JS_PROMPT = 13,
    EV_RENDER_GONE = 14,
    EV_HTTP_ERROR = 15,
    EV_CLOSE_WINDOW = 16,
};

constexpr wchar_t kHostClass[] = L"WinApkWebViewHost";
constexpr wchar_t kBridgePrefix[] = L"\x0001macapk-jsb\x0001";
constexpr DWORD kDecisionWaitMs = 3000;
constexpr DWORD kCookieWaitMs = 3000;
constexpr UINT_PTR kFollowTimer = 1;

// ---- Java -------------------------------------------------------------------

jclass g_bridge = nullptr;
jmethodID g_on_event = nullptr, g_on_real_input = nullptr, g_on_js_bridge = nullptr, g_materialize = nullptr;
jmethodID g_wants_intercept = nullptr, g_on_intercept = nullptr;
jclass g_tab_cls = nullptr;
jmethodID g_data_folder = nullptr;

void resolve_java(JNIEnv* env) {
    static std::once_flag once;
    std::call_once(once, [env] {
        JavaVM* vm = nullptr;
        env->GetJavaVM(&vm);
        wv2::set_vm(vm);
        jclass b = env->FindClass("android/webkit/MacApkWebViewBridge");
        if (b) {
            g_bridge = static_cast<jclass>(env->NewGlobalRef(b));
            g_on_event = env->GetStaticMethodID(g_bridge, "onEvent",
                                                "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
            g_on_real_input = env->GetStaticMethodID(g_bridge, "onRealInput", "()V");
            g_on_js_bridge = env->GetStaticMethodID(g_bridge, "onJsBridgeCall", "(ILjava/lang/String;)Ljava/lang/String;");
            g_materialize = env->GetStaticMethodID(g_bridge, "macapkMaterializeAssetPage",
                                                   "(ILjava/lang/String;)[Ljava/lang/String;");
            // Absent on a runtime without them: requests then go to the network.
            g_wants_intercept = env->GetStaticMethodID(g_bridge, "macapkWantsIntercept", "(I)Z");
            if (env->ExceptionCheck()) env->ExceptionClear();
            g_on_intercept = env->GetStaticMethodID(
                g_bridge, "macapkInterceptRequest",
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)[Ljava/lang/Object;");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        jclass t = env->FindClass("com/macapk/loader/HostBrowserTab");
        if (t) {
            g_tab_cls = static_cast<jclass>(env->NewGlobalRef(t));
            g_data_folder = env->GetStaticMethodID(g_tab_cls, "macapkUserDataFolderPath", "()Ljava/lang/String;");
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    });
}

std::wstring g_folder;  // the app's WebView2 user data, from the Java side

void capture_folder(JNIEnv* env) {
    if (!g_folder.empty() || !g_tab_cls || !g_data_folder) return;
    auto s = static_cast<jstring>(env->CallStaticObjectMethod(g_tab_cls, g_data_folder));
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_folder = wv2::from_jstring(env, s);
}

void post_event(int view, int what, const std::wstring& a = L"", const std::wstring& b = L"",
                const std::wstring& c = L"", int i = 0, bool a_null = false) {
    JNIEnv* env = wv2::env();
    if (!env || !g_on_event) return;
    jstring ja = a_null ? nullptr : wv2::to_jstring(env, a);
    jstring jb = wv2::to_jstring(env, b);
    jstring jc = wv2::to_jstring(env, c);
    env->CallStaticVoidMethod(g_bridge, g_on_event, view, what, ja, jb, jc, i);
    wv2::check_java(env, "MacApkWebViewBridge.onEvent");
    if (ja) env->DeleteLocalRef(ja);
    env->DeleteLocalRef(jb);
    env->DeleteLocalRef(jc);
}

void note_real_input() {
    JNIEnv* env = wv2::env();
    if (!env || !g_on_real_input) return;
    env->CallStaticVoidMethod(g_bridge, g_on_real_input);
    wv2::check_java(env, "MacApkWebViewBridge.onRealInput");
}

// ---- decisions the Java side answers ------------------------------------------

struct Decision {
    HANDLE done = nullptr;
    bool allow = true;
};
std::mutex g_decisions_lock;
std::map<int, Decision> g_decisions;
std::atomic<int> g_next_decision{1};

// ---- the views ------------------------------------------------------------------

struct Dialog {
    int view = 0;
    ComPtr<ICoreWebView2ScriptDialogOpeningEventArgs> args;
    ComPtr<ICoreWebView2Deferral> deferral;
};

struct View {
    int id = 0;
    HWND host = nullptr;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> wv;
    bool ready = false, failed = false, destroyed = false;
    int busy = 0;  // script registrations in flight: later operations wait for them
    std::deque<std::function<void(View*)>> pending;
    // settings
    bool js = true;
    std::wstring ua;
    bool have_base = false;
    COREWEBVIEW2_COLOR base{255, 255, 255, 255};
    // geometry, in the app's surface pixels
    int x = 0, y = 0, w = 0, h = 0, sw = 1, sh = 1;
    bool visible = false;
    std::vector<int> occluders;
    RECT placed{0, 0, 0, 0};
    bool placed_visible = false;
    int placed_client_w = 0, placed_client_h = 0;
    double zoom = 0;
    // navigation
    std::wstring script, script_id;
    int own_navs = 0;          // navigations this side started, not yet seen
    UINT64 own_nav_id = 0;
    std::wstring last_url;
    // loadDataWithBaseURL with a web base: the document to answer for it
    std::wstring data_base;
    std::string data_bytes;
    std::wstring data_mime;
    // a materialized file:///android_asset/ tree: file URL prefix -> asset URL prefix
    std::wstring asset_file_prefix, asset_url_prefix = L"file:///android_asset/";
    // Navigations the app took (shouldOverrideUrlLoading true): WebView2 may
    // already be sending their request when the cancel lands, so the document
    // request is answered here instead (204, nothing loads), as on a device,
    // where the URL never reaches the network.
    std::vector<std::wstring> refused;
};

std::map<int, View*> g_views;  // WebView2 thread only
std::map<int, Dialog> g_dialogs;  // WebView2 thread only
std::atomic<int> g_next_dialog{1};

View* find_view(int id) {
    auto it = g_views.find(id);
    return (it == g_views.end() || it->second->destroyed) ? nullptr : it->second;
}

// Runs op on the view now if it is ready and idle, else after it is.
void run_or_queue(int id, std::function<void(View*)> op) {
    View* v = find_view(id);
    if (!v || v->failed) return;
    if (v->ready && v->busy == 0 && v->pending.empty()) {
        op(v);
        return;
    }
    v->pending.push_back(std::move(op));
}

void drain(View* v) {
    while (v->ready && v->busy == 0 && !v->pending.empty() && !v->destroyed) {
        auto op = std::move(v->pending.front());
        v->pending.pop_front();
        op(v);
    }
}

// URLs the Java side sees: a materialized asset page maps back to
// file:///android_asset/, as on a device.
std::wstring guest_url(const View* v, const std::wstring& u) {
    if (!v->asset_file_prefix.empty() && u.size() >= v->asset_file_prefix.size() &&
        _wcsnicmp(u.c_str(), v->asset_file_prefix.c_str(), v->asset_file_prefix.size()) == 0) {
        return v->asset_url_prefix + u.substr(v->asset_file_prefix.size());
    }
    return u;
}

std::wstring current_url(View* v) {
    LPWSTR raw = nullptr;
    if (v->wv) v->wv->get_Source(&raw);
    return guest_url(v, wv2::take_co_string(raw));
}

// ---- placement ------------------------------------------------------------------

void place(View* v) {
    if (!v->host) return;
    HWND app = static_cast<HWND>(window_handle());
    RECT client{};
    if (!app || !GetClientRect(app, &client) || client.right <= 0 || client.bottom <= 0 || v->sw <= 0 || v->sh <= 0) {
        if (v->placed_visible) ShowWindow(v->host, SW_HIDE);
        v->placed_visible = false;
        return;
    }
    // The page goes over the app's pixels, which fill the client area or, in a
    // maximized window, a centred phone-shaped part of it.
    int lx = 0, ly = 0, lw = client.right, lh = client.bottom;
    window_phone_rect(&lx, &ly, &lw, &lh);
    const double sx = static_cast<double>(lw) / v->sw, sy = static_cast<double>(lh) / v->sh;
    RECT r{static_cast<LONG>(lx + v->x * sx + 0.5), static_cast<LONG>(ly + v->y * sy + 0.5),
           static_cast<LONG>(lx + (v->x + v->w) * sx + 0.5), static_cast<LONG>(ly + (v->y + v->h) * sy + 0.5)};
    const bool show = v->visible && r.right > r.left && r.bottom > r.top && !IsIconic(app);
    v->placed_client_w = client.right;
    v->placed_client_h = client.bottom;
    if (!show) {
        if (v->placed_visible) ShowWindow(v->host, SW_HIDE);
        v->placed_visible = false;
        return;
    }
    if (!EqualRect(&r, &v->placed) || !v->placed_visible) {
        SetWindowPos(v->host, HWND_TOP, r.left, r.top, r.right - r.left, r.bottom - r.top,
                     SWP_NOACTIVATE | SWP_SHOWWINDOW);
        v->placed = r;
        v->placed_visible = true;
    }
    // The app's views drawn over the page: cut out of the window, so they show
    // (the parent paints there) and take their own clicks. A first rect that
    // is the whole view means a modal window is above it: alone, the view is
    // covered; followed by the modal window's bounds (WebView.java
    // macapkPushOccluders), the page stays in sight around the dialog and
    // takes no input until it goes.
    HRGN rgn = CreateRectRgn(0, 0, r.right - r.left, r.bottom - r.top);
    size_t first = 0;
    bool modal = false;
    if (v->occluders.size() >= 8 && v->occluders[0] <= v->x && v->occluders[1] <= v->y &&
        v->occluders[0] + v->occluders[2] >= v->x + v->w && v->occluders[1] + v->occluders[3] >= v->y + v->h) {
        first = 4;
        modal = true;
    }
    EnableWindow(v->host, modal ? FALSE : TRUE);
    for (size_t k = first; k + 3 < v->occluders.size(); k += 4) {
        const int ox = v->occluders[k], oy = v->occluders[k + 1], ow = v->occluders[k + 2], oh = v->occluders[k + 3];
        RECT o{static_cast<LONG>(lx + ox * sx + 0.5) - r.left, static_cast<LONG>(ly + oy * sy + 0.5) - r.top,
               static_cast<LONG>(lx + (ox + ow) * sx + 0.5) - r.left,
               static_cast<LONG>(ly + (oy + oh) * sy + 0.5) - r.top};
        HRGN cut = CreateRectRgnIndirect(&o);
        CombineRgn(rgn, rgn, cut, RGN_DIFF);
        DeleteObject(cut);
    }
    SetWindowRgn(v->host, rgn, TRUE);  // the window owns rgn now
    if (v->controller) {
        RECT b{0, 0, r.right - r.left, r.bottom - r.top};
        v->controller->put_Bounds(b);
        // One CSS pixel per dp: the window shows the phone's pixel width / phone-width px
        // per dp, and WebView2 already scales by the monitor's DPI.
        int pw = 0, ph = 0;
        phone_size_dp(&pw, &ph);
        using DpiFn = UINT(WINAPI*)(HWND);
        static auto dpi_for = reinterpret_cast<DpiFn>(
            reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow")));
        const UINT dpi = dpi_for ? dpi_for(v->host) : 96;
        if (pw > 0 && dpi > 0) {
            const double zoom = (static_cast<double>(lw) / pw) / (dpi / 96.0);
            if (zoom > 0.1 && std::abs(zoom - v->zoom) > 0.001) {
                v->controller->put_ZoomFactor(zoom);
                v->zoom = zoom;
            }
        }
    }
}

LRESULT CALLBACK host_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    const int id = static_cast<int>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_TIMER:
            // The app window can change size (another monitor, the work area,
            // the phone turned) without the view moving in the app's terms.
            if (wp == kFollowTimer) {
                if (View* v = find_view(id)) {
                    HWND app = static_cast<HWND>(window_handle());
                    RECT c{};
                    if (app && GetClientRect(app, &c) &&
                        (c.right != v->placed_client_w || c.bottom != v->placed_client_h)) {
                        place(v);
                    }
                }
            }
            return 0;
        case WM_SIZE:
            if (View* v = find_view(id)) {
                if (v->controller) {
                    RECT b{0, 0, LOWORD(lp), HIWORD(lp)};
                    v->controller->put_Bounds(b);
                }
            }
            return 0;
        case WM_ERASEBKGND:
            return 1;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void register_host_class() {
    static bool done = false;
    if (done) return;
    done = true;
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = host_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
    wc.lpszClassName = kHostClass;
    RegisterClassExW(&wc);
}

// ---- errors ----------------------------------------------------------------------

// WebViewClient.ERROR_* and Chromium's net error name for a WebView2 status.
int android_error(COREWEBVIEW2_WEB_ERROR_STATUS s, std::wstring* desc) {
    struct Row {
        COREWEBVIEW2_WEB_ERROR_STATUS s;
        int code;
        const wchar_t* name;
    };
    static const Row rows[] = {
        {COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_COMMON_NAME_IS_INCORRECT, -11, L"net::ERR_CERT_COMMON_NAME_INVALID"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED, -11, L"net::ERR_CERT_DATE_INVALID"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CLIENT_CERTIFICATE_CONTAINS_ERRORS, -11, L"net::ERR_BAD_SSL_CLIENT_AUTH_CERT"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_REVOKED, -11, L"net::ERR_CERT_REVOKED"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID, -11, L"net::ERR_CERT_INVALID"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_SERVER_UNREACHABLE, -6, L"net::ERR_ADDRESS_UNREACHABLE"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_TIMEOUT, -8, L"net::ERR_TIMED_OUT"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_ERROR_HTTP_INVALID_SERVER_RESPONSE, -1, L"net::ERR_INVALID_RESPONSE"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED, -6, L"net::ERR_CONNECTION_ABORTED"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_RESET, -6, L"net::ERR_CONNECTION_RESET"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED, -7, L"net::ERR_INTERNET_DISCONNECTED"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_CANNOT_CONNECT, -6, L"net::ERR_CONNECTION_REFUSED"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_HOST_NAME_NOT_RESOLVED, -2, L"net::ERR_NAME_NOT_RESOLVED"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_REDIRECT_FAILED, -9, L"net::ERR_TOO_MANY_REDIRECTS"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_VALID_AUTHENTICATION_CREDENTIALS_REQUIRED, -4, L"net::ERR_INVALID_AUTH_CREDENTIALS"},
        {COREWEBVIEW2_WEB_ERROR_STATUS_VALID_PROXY_AUTHENTICATION_REQUIRED, -5, L"net::ERR_PROXY_AUTH_REQUESTED"},
    };
    for (const Row& r : rows) {
        if (r.s == s) {
            *desc = r.name;
            return r.code;
        }
    }
    *desc = L"net::ERR_FAILED";
    return -1;
}

// ---- events ------------------------------------------------------------------------

void push_history(View* v) {
    BOOL back = FALSE, fwd = FALSE;
    v->wv->get_CanGoBack(&back);
    v->wv->get_CanGoForward(&fwd);
    post_event(v->id, EV_URL_CHANGED, current_url(v), L"", L"", (back ? 1 : 0) | (fwd ? 2 : 0));
}

// The navigation check: shouldOverrideUrlLoading, answered on the view's UI
// thread while this navigation waits. True when the app takes the URL.
bool app_overrides(View* v, const std::wstring& url, bool redirect, bool gesture) {
    const int id = g_next_decision.fetch_add(1);
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    {
        std::lock_guard<std::mutex> hold(g_decisions_lock);
        g_decisions[id] = Decision{done, true};
    }
    std::wstring flags = L"main";
    if (redirect) flags += L",redirect";
    if (gesture) flags += L",gesture";
    post_event(v->id, EV_SHOULD_OVERRIDE, guest_url(v, url), L"GET", flags, id);
    const DWORD r = WaitForSingleObject(done, kDecisionWaitMs);
    bool allow = true;
    {
        std::lock_guard<std::mutex> hold(g_decisions_lock);
        allow = g_decisions[id].allow;
        g_decisions.erase(id);
    }
    CloseHandle(done);
    if (r != WAIT_OBJECT_0) {
        std::fprintf(stderr, "[win.apk] webview %d: no shouldOverrideUrlLoading answer in %lu ms; loading %s\n",
                     v->id, kDecisionWaitMs, wv2::redact(url).c_str());
        return false;
    }
    return !allow;
}

// The "JavaBridge" thread: @JavascriptInterface methods run on it (a device
// runs them on a private background thread, never the UI thread), one at a
// time, while the calling page waits.
struct BridgeCall {
    int view, dialog;
    std::wstring payload;
};
std::mutex g_bridge_lock;
std::condition_variable g_bridge_cv;
std::deque<BridgeCall> g_bridge_calls;
JavaVM* g_vm = nullptr;

void finish_dialog(int dialog_id, bool accept, const std::wstring* text) {
    auto it = g_dialogs.find(dialog_id);
    if (it == g_dialogs.end()) return;
    Dialog d = it->second;
    g_dialogs.erase(it);
    if (accept) {
        if (text) d.args->put_ResultText(text->c_str());
        d.args->Accept();
    }
    d.deferral->Complete();
}

void bridge_thread() {
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("JavaBridge"), nullptr};
    if (!g_vm || g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return;
    for (;;) {
        BridgeCall call;
        {
            std::unique_lock<std::mutex> hold(g_bridge_lock);
            g_bridge_cv.wait(hold, [] { return !g_bridge_calls.empty(); });
            call = std::move(g_bridge_calls.front());
            g_bridge_calls.pop_front();
        }
        std::wstring result;
        if (g_on_js_bridge) {
            jstring p = wv2::to_jstring(env, call.payload);
            auto r = static_cast<jstring>(env->CallStaticObjectMethod(g_bridge, g_on_js_bridge, call.view, p));
            if (!wv2::check_java(env, "MacApkWebViewBridge.onJsBridgeCall")) result = wv2::from_jstring(env, r);
            env->DeleteLocalRef(p);
            if (r) env->DeleteLocalRef(r);
        }
        const int dialog = call.dialog;
        wv2::post([dialog, result] { finish_dialog(dialog, true, &result); });
    }
}

void queue_bridge_call(int view, int dialog, std::wstring payload) {
    static std::once_flag once;
    std::call_once(once, [] { std::thread(bridge_thread).detach(); });
    {
        std::lock_guard<std::mutex> hold(g_bridge_lock);
        g_bridge_calls.push_back(BridgeCall{view, dialog, std::move(payload)});
    }
    g_bridge_cv.notify_one();
}

// ---- shouldInterceptRequest ------------------------------------------------------
//
// On a device every request a WebView makes is first offered to
// WebViewClient.shouldInterceptRequest, on a background thread, and a response
// the app returns is used instead of the network. Cordova and Capacitor serve
// their whole app that way (WebViewAssetLoader: https://localhost/... from the
// APK's assets); without it the page fails with net::ERR_FAILED. Here a
// request is deferred, the app's method runs on one of a few "WebViewIntercept"
// threads, and the answer completes the deferral on the WebView2 thread. Only
// views whose client overrides the method are asked, so other pages load as
// fast as before.

struct InterceptCall {
    int id;
    int view;
    std::wstring url, method, headers;
    bool main_frame;
};
struct PendingIntercept {
    ComPtr<ICoreWebView2WebResourceRequestedEventArgs> args;
    ComPtr<ICoreWebView2Deferral> deferral;
    ComPtr<ICoreWebView2Environment> env;
};
std::map<int, PendingIntercept> g_intercepts;  // WebView2 thread only
std::atomic<int> g_next_intercept{1};
std::mutex g_intercept_lock;
std::condition_variable g_intercept_cv;
std::deque<InterceptCall> g_intercept_calls;
constexpr int kInterceptThreads = 4;

// The app's answer: none (go to the network), or a whole response.
struct InterceptAnswer {
    bool have = false;
    int status = 200;
    std::wstring reason = L"OK", headers;
    std::string body;
};

void finish_intercept(int id, const InterceptAnswer& ans) {
    auto it = g_intercepts.find(id);
    if (it == g_intercepts.end()) return;
    PendingIntercept p = it->second;
    g_intercepts.erase(it);
    if (ans.have && p.env) {
        IStream* body = SHCreateMemStream(reinterpret_cast<const BYTE*>(ans.body.data()),
                                          static_cast<UINT>(ans.body.size()));
        ComPtr<ICoreWebView2WebResourceResponse> resp;
        p.env->CreateWebResourceResponse(body, ans.status, ans.reason.c_str(), ans.headers.c_str(), resp.put());
        if (body) body->Release();
        if (resp) p.args->put_Response(resp.get());
    }
    p.deferral->Complete();
}

// {meta, body}: meta is "status\nreason\nName: value\r\nName: value...".
InterceptAnswer read_answer(JNIEnv* env, jobjectArray r) {
    InterceptAnswer ans;
    if (!r || env->GetArrayLength(r) < 2) return ans;
    auto meta = static_cast<jstring>(env->GetObjectArrayElement(r, 0));
    auto body = static_cast<jbyteArray>(env->GetObjectArrayElement(r, 1));
    const std::wstring m = wv2::from_jstring(env, meta);
    const size_t a = m.find(L'\n'), b = a == std::wstring::npos ? a : m.find(L'\n', a + 1);
    if (b != std::wstring::npos) {
        ans.have = true;
        ans.status = _wtoi(m.substr(0, a).c_str());
        if (ans.status < 100 || ans.status > 599) ans.status = 200;
        ans.reason = m.substr(a + 1, b - a - 1);
        if (ans.reason.empty()) ans.reason = L"OK";
        ans.headers = m.substr(b + 1);
        if (body) {
            const jsize n = env->GetArrayLength(body);
            ans.body.resize(static_cast<size_t>(n));
            if (n > 0) env->GetByteArrayRegion(body, 0, n, reinterpret_cast<jbyte*>(&ans.body[0]));
        }
    }
    if (meta) env->DeleteLocalRef(meta);
    if (body) env->DeleteLocalRef(body);
    return ans;
}

void intercept_thread() {
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("WebViewIntercept"), nullptr};
    if (!g_vm || g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return;
    for (;;) {
        InterceptCall call;
        {
            std::unique_lock<std::mutex> hold(g_intercept_lock);
            g_intercept_cv.wait(hold, [] { return !g_intercept_calls.empty(); });
            call = std::move(g_intercept_calls.front());
            g_intercept_calls.pop_front();
        }
        InterceptAnswer ans;
        if (g_on_intercept) {
            jstring u = wv2::to_jstring(env, call.url);
            jstring m = wv2::to_jstring(env, call.method);
            jstring h = wv2::to_jstring(env, call.headers);
            auto r = static_cast<jobjectArray>(env->CallStaticObjectMethod(
                g_bridge, g_on_intercept, call.view, u, m, h, call.main_frame ? JNI_TRUE : JNI_FALSE));
            if (!wv2::check_java(env, "MacApkWebViewBridge.macapkInterceptRequest")) ans = read_answer(env, r);
            env->DeleteLocalRef(u);
            env->DeleteLocalRef(m);
            env->DeleteLocalRef(h);
            if (r) env->DeleteLocalRef(r);
        }
        const int id = call.id;
        wv2::post([id, ans] { finish_intercept(id, ans); });
    }
}

// Does this view's WebViewClient override shouldInterceptRequest? WebView2 thread.
bool wants_intercept(int view) {
    JNIEnv* env = wv2::env();
    if (!env || !g_wants_intercept || !g_on_intercept || !g_vm) return false;
    const jboolean r = env->CallStaticBooleanMethod(g_bridge, g_wants_intercept, view);
    if (wv2::check_java(env, "MacApkWebViewBridge.macapkWantsIntercept")) return false;
    return r == JNI_TRUE;
}

// The request's headers as "Name: value\n" lines.
std::wstring request_headers(ICoreWebView2WebResourceRequest* req) {
    std::wstring out;
    ComPtr<ICoreWebView2HttpRequestHeaders> h;
    if (!req || FAILED(req->get_Headers(h.put())) || !h) return out;
    ComPtr<ICoreWebView2HttpHeadersCollectionIterator> it;
    if (FAILED(h->GetIterator(it.put())) || !it) return out;
    BOOL has = FALSE;
    while (SUCCEEDED(it->get_HasCurrentHeader(&has)) && has) {
        LPWSTR name = nullptr, value = nullptr;
        if (SUCCEEDED(it->GetCurrentHeader(&name, &value))) {
            out += wv2::take_co_string(name) + L": " + wv2::take_co_string(value) + L"\n";
        }
        BOOL more = FALSE;
        if (FAILED(it->MoveNext(&more)) || !more) break;
    }
    return out;
}

// Offers the request to the app; true when it was deferred for that.
bool offer_to_app(View* v, ICoreWebView2WebResourceRequestedEventArgs* a, ICoreWebView2WebResourceRequest* req,
                  const std::wstring& uri, bool main_frame, ICoreWebView2Environment* env) {
    const std::wstring scheme = wv2::scheme_of(uri);
    if (scheme != L"http" && scheme != L"https" && scheme != L"file") return false;
    if (!wants_intercept(v->id)) return false;
    PendingIntercept p;
    p.args = ComPtr<ICoreWebView2WebResourceRequestedEventArgs>::retain(a);
    p.env = ComPtr<ICoreWebView2Environment>::retain(env);
    if (FAILED(a->GetDeferral(p.deferral.put())) || !p.deferral) return false;
    InterceptCall call;
    call.id = g_next_intercept.fetch_add(1);
    call.view = v->id;
    call.url = guest_url(v, uri);
    LPWSTR method = nullptr;
    if (req) req->get_Method(&method);
    call.method = wv2::take_co_string(method);
    if (call.method.empty()) call.method = L"GET";
    call.headers = request_headers(req);
    call.main_frame = main_frame;
    g_intercepts[call.id] = p;
    static std::once_flag once;
    std::call_once(once, [] {
        for (int i = 0; i < kInterceptThreads; ++i) std::thread(intercept_thread).detach();
    });
    {
        std::lock_guard<std::mutex> hold(g_intercept_lock);
        g_intercept_calls.push_back(std::move(call));
    }
    g_intercept_cv.notify_one();
    return true;
}

void hook(View* v) {
    const int id = v->id;
    EventRegistrationToken tok;
    v->wv->add_NavigationStarting(
        wv2::handler<ICoreWebView2NavigationStartingEventHandler>(
            IID_ICoreWebView2NavigationStartingEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* a) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                LPWSTR raw = nullptr;
                a->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                UINT64 nav = 0;
                a->get_NavigationId(&nav);
                BOOL redirected = FALSE, user = FALSE;
                a->get_IsRedirected(&redirected);
                a->get_IsUserInitiated(&user);
                if (user) note_real_input();
                bool ask = true;
                if (v->own_navs > 0 && !redirected) {
                    // loadUrl / loadData / reload / history: the app's own.
                    --v->own_navs;
                    v->own_nav_id = nav;
                    ask = false;
                }
                if (auto a3 = ComPtr<ICoreWebView2NavigationStartingEventArgs>::retain(a)
                                  .query<ICoreWebView2NavigationStartingEventArgs3>(
                                      IID_ICoreWebView2NavigationStartingEventArgs3)) {
                    COREWEBVIEW2_NAVIGATION_KIND kind = COREWEBVIEW2_NAVIGATION_KIND_NEW_DOCUMENT;
                    a3->get_NavigationKind(&kind);
                    if (kind != COREWEBVIEW2_NAVIGATION_KIND_NEW_DOCUMENT && !redirected) ask = false;
                }
                const std::wstring scheme = wv2::scheme_of(uri);
                if (scheme == L"about" || scheme == L"data" || scheme == L"blob" || scheme == L"javascript") {
                    ask = false;
                }
                if (ask && app_overrides(v, uri, redirected == TRUE, user == TRUE)) {
                    v->refused.push_back(uri);
                    if (v->refused.size() > 8) v->refused.erase(v->refused.begin());
                    a->put_Cancel(TRUE);
                    return S_OK;
                }
                if (scheme != L"http" && scheme != L"https" && scheme != L"file" && scheme != L"about" &&
                    scheme != L"data" && scheme != L"blob" && scheme != L"javascript") {
                    // A device's WebView cannot load another app's scheme either.
                    a->put_Cancel(TRUE);
                    post_event(id, EV_RECEIVED_ERROR, L"net::ERR_UNKNOWN_URL_SCHEME", uri, L"main", -10);
                    return S_OK;
                }
                post_event(id, EV_PROGRESS, L"", L"", L"", 10);
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_ContentLoading(
        wv2::handler<ICoreWebView2ContentLoadingEventHandler>(
            IID_ICoreWebView2ContentLoadingEventHandler,
            [id](ICoreWebView2*, ICoreWebView2ContentLoadingEventArgs*) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                const std::wstring url = current_url(v);
                post_event(id, EV_PAGE_STARTED, url);
                post_event(id, EV_PROGRESS, L"", L"", L"", 30);
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_NavigationCompleted(
        wv2::handler<ICoreWebView2NavigationCompletedEventHandler>(
            IID_ICoreWebView2NavigationCompletedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* a) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                BOOL ok = FALSE;
                a->get_IsSuccess(&ok);
                COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                a->get_WebErrorStatus(&status);
                const std::wstring url = current_url(v);
                if (!ok && status == COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED) return S_OK;
                if (!ok) {
                    std::wstring desc;
                    const int code = android_error(status, &desc);
                    post_event(id, EV_RECEIVED_ERROR, desc, url, L"main", code);
                } else if (auto a2 = ComPtr<ICoreWebView2NavigationCompletedEventArgs>::retain(a)
                                         .query<ICoreWebView2NavigationCompletedEventArgs2>(
                                             IID_ICoreWebView2NavigationCompletedEventArgs2)) {
                    int http = 0;
                    a2->get_HttpStatusCode(&http);
                    if (http >= 400) post_event(id, EV_HTTP_ERROR, url, L"", L"", http);
                }
                post_event(id, EV_PROGRESS, L"", L"", L"", 100);
                post_event(id, EV_PAGE_FINISHED, url);
                return S_OK;
            })
            .get(),
        &tok);
    if (auto w2 = v->wv.query<ICoreWebView2_2>(IID_ICoreWebView2_2)) w2->add_DOMContentLoaded(
        wv2::handler<ICoreWebView2DOMContentLoadedEventHandler>(
            IID_ICoreWebView2DOMContentLoadedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2DOMContentLoadedEventArgs*) -> HRESULT {
                if (View* v = find_view(id)) post_event(id, EV_PAGE_COMMIT_VISIBLE, current_url(v));
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_DocumentTitleChanged(
        wv2::handler<ICoreWebView2DocumentTitleChangedEventHandler>(
            IID_ICoreWebView2DocumentTitleChangedEventHandler,
            [id](ICoreWebView2* s, IUnknown*) -> HRESULT {
                LPWSTR raw = nullptr;
                s->get_DocumentTitle(&raw);
                post_event(id, EV_TITLE, wv2::take_co_string(raw));
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_SourceChanged(
        wv2::handler<ICoreWebView2SourceChangedEventHandler>(
            IID_ICoreWebView2SourceChangedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2SourceChangedEventArgs*) -> HRESULT {
                if (View* v = find_view(id)) push_history(v);
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_HistoryChanged(
        wv2::handler<ICoreWebView2HistoryChangedEventHandler>(
            IID_ICoreWebView2HistoryChangedEventHandler,
            [id](ICoreWebView2*, IUnknown*) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                push_history(v);
                const std::wstring url = current_url(v);
                const bool reload = url == v->last_url;
                v->last_url = url;
                post_event(id, EV_HISTORY_UPDATE, url, L"", L"", reload ? 1 : 0);
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_NewWindowRequested(
        wv2::handler<ICoreWebView2NewWindowRequestedEventHandler>(
            IID_ICoreWebView2NewWindowRequestedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* a) -> HRESULT {
                // WebSettings.setSupportMultipleWindows(false), the default: a
                // new window's URL loads in this view.
                a->put_Handled(TRUE);
                LPWSTR raw = nullptr;
                a->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                if (View* v = find_view(id)) {
                    if (!uri.empty()) v->wv->Navigate(uri.c_str());
                }
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_WindowCloseRequested(
        wv2::handler<ICoreWebView2WindowCloseRequestedEventHandler>(
            IID_ICoreWebView2WindowCloseRequestedEventHandler,
            [id](ICoreWebView2*, IUnknown*) -> HRESULT {
                post_event(id, EV_CLOSE_WINDOW);
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_ProcessFailed(
        wv2::handler<ICoreWebView2ProcessFailedEventHandler>(
            IID_ICoreWebView2ProcessFailedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2ProcessFailedEventArgs* a) -> HRESULT {
                COREWEBVIEW2_PROCESS_FAILED_KIND kind = COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED;
                a->get_ProcessFailedKind(&kind);
                if (kind == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED ||
                    kind == COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED) {
                    post_event(id, EV_RENDER_GONE);
                }
                return S_OK;
            })
            .get(),
        &tok);
    v->wv->add_ScriptDialogOpening(
        wv2::handler<ICoreWebView2ScriptDialogOpeningEventHandler>(
            IID_ICoreWebView2ScriptDialogOpeningEventHandler,
            [id](ICoreWebView2*, ICoreWebView2ScriptDialogOpeningEventArgs* a) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                COREWEBVIEW2_SCRIPT_DIALOG_KIND kind = COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT;
                a->get_Kind(&kind);
                LPWSTR m = nullptr, def = nullptr, u = nullptr;
                a->get_Message(&m);
                a->get_DefaultText(&def);
                a->get_Uri(&u);
                const std::wstring msg = wv2::take_co_string(m), dflt = wv2::take_co_string(def),
                                   url = guest_url(v, wv2::take_co_string(u));
                Dialog d;
                d.view = id;
                d.args = ComPtr<ICoreWebView2ScriptDialogOpeningEventArgs>::retain(a);
                if (FAILED(a->GetDeferral(d.deferral.put())) || !d.deferral) return S_OK;
                const int did = g_next_dialog.fetch_add(1);
                g_dialogs[did] = d;
                const size_t plen = wcslen(kBridgePrefix);
                if (kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_PROMPT && msg.compare(0, plen, kBridgePrefix) == 0) {
                    queue_bridge_call(id, did, msg.substr(plen));
                } else if (kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT) {
                    post_event(id, EV_JS_ALERT, msg, url, L"", did);
                } else if (kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_CONFIRM ||
                           kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD) {
                    post_event(id, EV_JS_CONFIRM, msg, url, L"", did);
                } else {
                    post_event(id, EV_JS_PROMPT, msg, url, dflt, did);
                }
                return S_OK;
            })
            .get(),
        &tok);
    // A scheme no navigation check let through: never Windows' handler.
    if (auto v18 = v->wv.query<ICoreWebView2_18>(IID_ICoreWebView2_18)) {
        v18->add_LaunchingExternalUriScheme(
            wv2::handler<ICoreWebView2LaunchingExternalUriSchemeEventHandler>(
                IID_ICoreWebView2LaunchingExternalUriSchemeEventHandler,
                [](ICoreWebView2*, ICoreWebView2LaunchingExternalUriSchemeEventArgs* a) -> HRESULT {
                    a->put_Cancel(TRUE);
                    return S_OK;
                })
                .get(),
            &tok);
    }
    // A loadDataWithBaseURL document with a web base is served for that URL,
    // so the page has the base's origin, as on a device; then the app's
    // shouldInterceptRequest (see offer_to_app).
    v->wv->add_WebResourceRequested(
        wv2::handler<ICoreWebView2WebResourceRequestedEventHandler>(
            IID_ICoreWebView2WebResourceRequestedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2WebResourceRequestedEventArgs* a) -> HRESULT {
                View* v = find_view(id);
                if (!v) return S_OK;
                ComPtr<ICoreWebView2WebResourceRequest> req;
                a->get_Request(req.put());
                LPWSTR raw = nullptr;
                if (req) req->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                COREWEBVIEW2_WEB_RESOURCE_CONTEXT ctx = COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL;
                a->get_ResourceContext(&ctx);
                ComPtr<ICoreWebView2Environment> env;
                if (auto v2 = v->wv.query<ICoreWebView2_2>(IID_ICoreWebView2_2)) v2->get_Environment(env.put());
                if (!env) return S_OK;
                const bool document = ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT;
                if (!document) {
                    offer_to_app(v, a, req.get(), uri, false, env.get());
                    return S_OK;
                }
                for (auto r = v->refused.begin(); r != v->refused.end(); ++r) {
                    if (*r != uri) continue;
                    v->refused.erase(r);
                    ComPtr<ICoreWebView2WebResourceResponse> none;
                    env->CreateWebResourceResponse(nullptr, 204, L"No Content", L"", none.put());
                    if (none) a->put_Response(none.get());
                    return S_OK;
                }
                if (v->data_base.empty() || uri != v->data_base) {
                    offer_to_app(v, a, req.get(), uri, true, env.get());
                    return S_OK;
                }
                IStream* body = SHCreateMemStream(reinterpret_cast<const BYTE*>(v->data_bytes.data()),
                                                  static_cast<UINT>(v->data_bytes.size()));
                const std::wstring headers = L"Content-Type: " + v->data_mime;
                ComPtr<ICoreWebView2WebResourceResponse> resp;
                env->CreateWebResourceResponse(body, 200, L"OK", headers.c_str(), resp.put());
                if (body) body->Release();
                if (resp) a->put_Response(resp.get());
                v->data_base.clear();
                v->data_bytes.clear();
                return S_OK;
            })
            .get(),
        &tok);
    // Every request: the app's shouldInterceptRequest sees subresources too.
    v->wv->AddWebResourceRequestedFilter(L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    v->controller->add_GotFocus(
        wv2::handler<ICoreWebView2FocusChangedEventHandler>(
            IID_ICoreWebView2FocusChangedEventHandler,
            [](ICoreWebView2Controller*, IUnknown*) -> HRESULT {
                note_real_input();
                return S_OK;
            })
            .get(),
        &tok);
    // Back (Escape, Alt+Left, the browser Back key) is the app's, as on a
    // device: the activity decides, usually WebView.goBack().
    v->controller->add_AcceleratorKeyPressed(
        wv2::handler<ICoreWebView2AcceleratorKeyPressedEventHandler>(
            IID_ICoreWebView2AcceleratorKeyPressedEventHandler,
            [](ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs* a) -> HRESULT {
                COREWEBVIEW2_KEY_EVENT_KIND kind = COREWEBVIEW2_KEY_EVENT_KIND_KEY_UP;
                a->get_KeyEventKind(&kind);
                UINT key = 0;
                a->get_VirtualKey(&key);
                const bool down = kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN ||
                                  kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN;
                const bool sys = kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN ||
                                 kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_UP;
                if (key == VK_ESCAPE || key == VK_BROWSER_BACK || (sys && key == VK_LEFT)) {
                    a->put_Handled(TRUE);
                    if (HWND app = static_cast<HWND>(window_handle())) {
                        PostMessageW(app, down ? WM_KEYDOWN : WM_KEYUP, VK_ESCAPE, down ? 1 : 0xC0000001);
                    }
                }
                return S_OK;
            })
            .get(),
        &tok);
}

void apply_settings(View* v) {
    ComPtr<ICoreWebView2Settings> s;
    v->wv->get_Settings(s.put());
    if (!s) return;
    s->put_IsScriptEnabled(v->js ? TRUE : FALSE);
    s->put_AreDefaultScriptDialogsEnabled(FALSE);
    s->put_IsStatusBarEnabled(FALSE);
    s->put_AreDevToolsEnabled(FALSE);
    s->put_IsZoomControlEnabled(FALSE);
    s->put_IsWebMessageEnabled(TRUE);
    if (auto s2 = s.query<ICoreWebView2Settings2>(IID_ICoreWebView2Settings2)) {
        if (!v->ua.empty()) s2->put_UserAgent(v->ua.c_str());
    }
    if (auto s3 = s.query<ICoreWebView2Settings3>(IID_ICoreWebView2Settings3)) {
        s3->put_AreBrowserAcceleratorKeysEnabled(FALSE);
    }
    if (auto s5 = s.query<ICoreWebView2Settings5>(IID_ICoreWebView2Settings5)) {
        s5->put_IsPinchZoomEnabled(FALSE);
    }
    if (auto s6 = s.query<ICoreWebView2Settings6>(IID_ICoreWebView2Settings6)) {
        s6->put_IsSwipeNavigationEnabled(FALSE);
    }
}

void controller_ready(int id, HRESULT hr, ICoreWebView2Controller* c) {
    View* v = find_view(id);
    if (!v) {
        if (c) c->Close();
        return;
    }
    if (FAILED(hr) || !c) {
        std::fprintf(stderr, "[win.apk] webview %d: no WebView2 controller (0x%08lx)\n", id,
                     static_cast<unsigned long>(hr));
        v->failed = true;
        v->pending.clear();
        return;
    }
    v->controller = ComPtr<ICoreWebView2Controller>::retain(c);
    c->get_CoreWebView2(v->wv.put());
    if (!v->wv) {
        v->failed = true;
        return;
    }
    apply_settings(v);
    if (v->have_base) {
        if (auto c2 = v->controller.query<ICoreWebView2Controller2>(IID_ICoreWebView2Controller2)) {
            c2->put_DefaultBackgroundColor(v->base);
        }
    }
    hook(v);
    c->put_IsVisible(TRUE);
    v->ready = true;
    place(v);
    std::fprintf(stderr, "[win.apk] webview %d: ready\n", id);
    drain(v);
}

void create_on_thread(int id) {
    View* v = find_view(id);
    if (!v) return;
    HWND app = static_cast<HWND>(window_handle());
    if (!app) {
        v->failed = true;
        return;
    }
    register_host_class();
    v->host = CreateWindowExW(0, kHostClass, L"", WS_CHILD | WS_CLIPSIBLINGS | WS_CLIPCHILDREN, 0, 0, 1, 1, app,
                              nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!v->host) {
        v->failed = true;
        return;
    }
    SetWindowLongPtrW(v->host, GWLP_USERDATA, static_cast<LONG_PTR>(id));
    SetTimer(v->host, kFollowTimer, 250, nullptr);
    wv2::with_environment(g_folder, [id](ICoreWebView2Environment* e) {
        View* v = find_view(id);
        if (!v) return;
        if (!e) {
            v->failed = true;
            v->pending.clear();
            return;
        }
        auto done = wv2::handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
            IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
            [id](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
                controller_ready(id, hr, c);
                return S_OK;
            });
        const HRESULT hr = e->CreateCoreWebView2Controller(v->host, done.get());
        if (FAILED(hr)) controller_ready(id, hr, nullptr);
    });
}

void destroy_on_thread(int id) {
    auto it = g_views.find(id);
    if (it == g_views.end()) return;
    View* v = it->second;
    v->destroyed = true;
    if (v->controller) v->controller->Close();
    v->controller.reset();
    v->wv.reset();
    if (v->host) DestroyWindow(v->host);
    g_views.erase(it);
    // Dialogs of this view must not hold its page.
    for (auto d = g_dialogs.begin(); d != g_dialogs.end();) {
        if (d->second.view == id) {
            d->second.deferral->Complete();
            d = g_dialogs.erase(d);
        } else {
            ++d;
        }
    }
    delete v;
}

// ---- cookies ---------------------------------------------------------------------
//
// CookieManager is synchronous on a device; WebView2's cookie manager answers
// on the WebView2 thread. The calling Java thread waits (bounded). The
// default profile's manager comes from any ready view, else from a hidden
// one made for it.

ComPtr<ICoreWebView2CookieManager> g_cookies;
bool g_cookie_view_started = false;
std::vector<std::function<void(ICoreWebView2CookieManager*)>> g_cookie_waiters;
HWND g_cookie_parent = nullptr;
ComPtr<ICoreWebView2Controller> g_cookie_controller;

void settle_cookies(ICoreWebView2CookieManager* m) {
    if (m && !g_cookies) g_cookies = ComPtr<ICoreWebView2CookieManager>::retain(m);
    auto waiters = std::move(g_cookie_waiters);
    g_cookie_waiters.clear();
    for (auto& w : waiters) w(g_cookies.get());
}

void with_cookies(std::function<void(ICoreWebView2CookieManager*)> fn) {
    if (g_cookies) {
        fn(g_cookies.get());
        return;
    }
    for (auto& [id, v] : g_views) {
        if (v->ready && v->wv) {
            if (auto v2 = v->wv.query<ICoreWebView2_2>(IID_ICoreWebView2_2)) {
                ComPtr<ICoreWebView2CookieManager> m;
                v2->get_CookieManager(m.put());
                if (m) {
                    g_cookies = m;
                    fn(m.get());
                    return;
                }
            }
        }
    }
    g_cookie_waiters.push_back(std::move(fn));
    if (g_cookie_view_started) return;
    g_cookie_view_started = true;
    register_host_class();
    g_cookie_parent = CreateWindowExW(WS_EX_TOOLWINDOW, kHostClass, L"", WS_POPUP, 0, 0, 1, 1, nullptr, nullptr,
                                      GetModuleHandleW(nullptr), nullptr);
    wv2::with_environment(g_folder, [](ICoreWebView2Environment* e) {
        if (!e || !g_cookie_parent) {
            settle_cookies(nullptr);
            return;
        }
        auto done = wv2::handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
            IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
            [](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
                ComPtr<ICoreWebView2CookieManager> m;
                if (SUCCEEDED(hr) && c) {
                    g_cookie_controller = ComPtr<ICoreWebView2Controller>::retain(c);
                    ComPtr<ICoreWebView2> w;
                    c->get_CoreWebView2(w.put());
                    if (auto v2 = w.query<ICoreWebView2_2>(IID_ICoreWebView2_2)) v2->get_CookieManager(m.put());
                }
                settle_cookies(m.get());
                return S_OK;
            });
        if (FAILED(e->CreateCoreWebView2Controller(g_cookie_parent, done.get()))) settle_cookies(nullptr);
    });
}

// Runs fn on the WebView2 thread and waits for it to call finish (bounded).
template <typename T>
T cookie_call(T fallback, std::function<void(std::function<void(T)>)> fn) {
    struct Shared {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        T value;
    };
    auto shared = std::make_shared<Shared>();
    shared->value = fallback;
    const bool posted = wv2::post([shared, fn] {
        fn([shared](T v) {
            std::lock_guard<std::mutex> hold(shared->m);
            if (shared->done) return;
            shared->value = v;
            shared->done = true;
            shared->cv.notify_all();
        });
    });
    if (!posted) return fallback;
    std::unique_lock<std::mutex> hold(shared->m);
    shared->cv.wait_for(hold, std::chrono::milliseconds(kCookieWaitMs), [&] { return shared->done; });
    return shared->done ? shared->value : fallback;
}

// "a=b; c=d" (CookieManager.getCookie's format) from WebView2's list.
std::wstring cookie_header(ICoreWebView2CookieList* list) {
    std::wstring out;
    UINT n = 0;
    if (list) list->get_Count(&n);
    for (UINT k = 0; k < n; ++k) {
        ComPtr<ICoreWebView2Cookie> c;
        list->GetValueAtIndex(k, c.put());
        if (!c) continue;
        LPWSTR name = nullptr, value = nullptr;
        c->get_Name(&name);
        c->get_Value(&value);
        if (!out.empty()) out += L"; ";
        out += wv2::take_co_string(name) + L"=" + wv2::take_co_string(value);
    }
    return out;
}

std::wstring trim(const std::wstring& s) {
    size_t a = 0, b = s.size();
    while (a < b && iswspace(s[a])) ++a;
    while (b > a && iswspace(s[b - 1])) --b;
    return s.substr(a, b - a);
}

// CookieManager.setCookie(url, "name=value; Domain=..; Path=..; Max-Age=..;
// Expires=..; Secure; HttpOnly; SameSite=..").
void set_cookie_now(ICoreWebView2CookieManager* m, const std::wstring& url, const std::wstring& value) {
    if (!m) return;
    std::vector<std::wstring> parts;
    size_t start = 0;
    for (;;) {
        const size_t semi = value.find(L';', start);
        parts.push_back(trim(value.substr(start, semi == std::wstring::npos ? std::wstring::npos : semi - start)));
        if (semi == std::wstring::npos) break;
        start = semi + 1;
    }
    if (parts.empty() || parts[0].empty()) return;
    const size_t eq = parts[0].find(L'=');
    const std::wstring name = trim(eq == std::wstring::npos ? parts[0] : parts[0].substr(0, eq));
    const std::wstring val = eq == std::wstring::npos ? L"" : trim(parts[0].substr(eq + 1));
    // The URL's host and, as a device does, "/" for the path.
    std::wstring host;
    {
        size_t s = url.find(L"://");
        s = s == std::wstring::npos ? 0 : s + 3;
        const size_t e = url.find_first_of(L":/?#", s);
        host = url.substr(s, e == std::wstring::npos ? std::wstring::npos : e - s);
    }
    std::wstring domain = host, path = L"/";
    double expires = -1;
    bool secure = false, http_only = false;
    COREWEBVIEW2_COOKIE_SAME_SITE_KIND same_site = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_LAX;
    bool same_site_set = false;
    for (size_t k = 1; k < parts.size(); ++k) {
        const size_t e = parts[k].find(L'=');
        std::wstring key = trim(e == std::wstring::npos ? parts[k] : parts[k].substr(0, e));
        const std::wstring v = e == std::wstring::npos ? L"" : trim(parts[k].substr(e + 1));
        for (wchar_t& ch : key) ch = static_cast<wchar_t>(towlower(ch));
        if (key == L"domain" && !v.empty()) domain = v;
        else if (key == L"path" && !v.empty()) path = v;
        else if (key == L"secure") secure = true;
        else if (key == L"httponly") http_only = true;
        else if (key == L"max-age") expires = static_cast<double>(time(nullptr)) + _wtof(v.c_str());
        else if (key == L"samesite") {
            same_site_set = true;
            if (_wcsicmp(v.c_str(), L"none") == 0) same_site = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;
            else if (_wcsicmp(v.c_str(), L"strict") == 0) same_site = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_STRICT;
        }
    }
    ComPtr<ICoreWebView2Cookie> c;
    if (FAILED(m->CreateCookie(name.c_str(), val.c_str(), domain.c_str(), path.c_str(), c.put())) || !c) return;
    if (expires > 0) c->put_Expires(expires);
    c->put_IsSecure(secure ? TRUE : FALSE);
    c->put_IsHttpOnly(http_only ? TRUE : FALSE);
    if (same_site_set) c->put_SameSite(same_site);
    m->AddOrUpdateCookie(c.get());
}

// ---- JNI --------------------------------------------------------------------------

jboolean JNICALL Wv_nativeAvailable(JNIEnv* env, jclass) {
    if (!wv2::available() || !window_running()) return JNI_FALSE;
    resolve_java(env);
    capture_folder(env);
    return g_bridge && !g_folder.empty() ? JNI_TRUE : JNI_FALSE;
}

void JNICALL Wv_nativeInit(JNIEnv* env, jclass, jstring) {
    resolve_java(env);
    capture_folder(env);
}

void JNICALL Wv_nativeCreate(JNIEnv* env, jclass, jint id, jstring, jstring ua, jboolean js) {
    resolve_java(env);
    capture_folder(env);
    if (!g_vm) env->GetJavaVM(&g_vm);
    const std::wstring agent = wv2::from_jstring(env, ua);
    const bool script = js == JNI_TRUE;
    wv2::post([id, agent, script] {
        if (g_views.count(id)) return;
        auto* v = new View();
        v->id = id;
        v->ua = agent;
        v->js = script;
        g_views[id] = v;
        create_on_thread(id);
    });
}

void JNICALL Wv_nativeDestroy(JNIEnv*, jclass, jint id) {
    wv2::post([id] { destroy_on_thread(id); });
}

void JNICALL Wv_nativeCreateOptions(JNIEnv*, jclass, jint, jboolean) {}

void JNICALL Wv_nativeSetFrame(JNIEnv*, jclass, jint id, jint x, jint y, jint w, jint h, jint sw, jint sh,
                               jboolean visible) {
    const bool vis = visible == JNI_TRUE;
    wv2::post([=] {
        View* v = find_view(id);
        if (!v) return;
        v->x = x;
        v->y = y;
        v->w = w;
        v->h = h;
        v->sw = sw > 0 ? sw : 1;
        v->sh = sh > 0 ? sh : 1;
        v->visible = vis;
        place(v);
    });
}

void JNICALL Wv_nativeSetOccluders(JNIEnv* env, jclass, jint id, jintArray rects) {
    std::vector<int> r;
    if (rects) {
        const jsize n = env->GetArrayLength(rects);
        r.resize(static_cast<size_t>(n));
        if (n > 0) env->GetIntArrayRegion(rects, 0, n, reinterpret_cast<jint*>(r.data()));
    }
    wv2::post([id, r] {
        View* v = find_view(id);
        if (!v) return;
        v->occluders = r;
        place(v);
    });
}

void JNICALL Wv_nativeSetBackgroundColor(JNIEnv*, jclass, jint id, jint argb) {
    const auto u = static_cast<uint32_t>(argb);
    // WebView2 takes an opaque or a fully transparent base only.
    COREWEBVIEW2_COLOR c{static_cast<BYTE>((u >> 24) >= 128 ? 255 : 0), static_cast<BYTE>((u >> 16) & 0xff),
                         static_cast<BYTE>((u >> 8) & 0xff), static_cast<BYTE>(u & 0xff)};
    wv2::post([id, c] {
        View* v = find_view(id);
        if (!v) return;
        v->have_base = true;
        v->base = c;
        if (v->controller) {
            if (auto c2 = v->controller.query<ICoreWebView2Controller2>(IID_ICoreWebView2Controller2)) {
                c2->put_DefaultBackgroundColor(c);
            }
        }
    });
}

void JNICALL Wv_nativeSetBackdrop(JNIEnv*, jclass, jint, jint, jboolean) {}

void JNICALL Wv_nativeApplySettings(JNIEnv* env, jclass, jint id, jboolean js, jstring ua) {
    const std::wstring agent = wv2::from_jstring(env, ua);
    const bool script = js == JNI_TRUE;
    wv2::post([=] {
        View* v = find_view(id);
        if (!v) return;
        v->js = script;
        if (!agent.empty()) v->ua = agent;
        if (v->wv) apply_settings(v);
    });
}

void JNICALL Wv_nativeSetFileUrlAccess(JNIEnv*, jclass, jint, jboolean, jstring) {}

// file:///android_asset/...: upstream's helper copies the page's tree to real
// files once (MacApkWebViewBridge.macapkMaterializeAssetPage); the page then
// loads from them, and its URLs map back for the Java side. Returns the URL
// to navigate to, and sets *prefix to the copied tree's file: URL. WebView2
// thread.
std::wstring materialize(int id, const std::wstring& url, std::wstring* prefix) {
    JNIEnv* env = wv2::env();
    if (!env || !g_materialize || _wcsnicmp(url.c_str(), L"file:///android_asset/", 22) != 0) return url;
    jstring ju = wv2::to_jstring(env, url);
    auto r = static_cast<jobjectArray>(env->CallStaticObjectMethod(g_bridge, g_materialize, id, ju));
    env->DeleteLocalRef(ju);
    std::wstring out = url;
    if (!wv2::check_java(env, "macapkMaterializeAssetPage") && r && env->GetArrayLength(r) >= 3) {
        auto page = static_cast<jstring>(env->GetObjectArrayElement(r, 0));
        auto base = static_cast<jstring>(env->GetObjectArrayElement(r, 2));
        const std::wstring p = wv2::from_jstring(env, page), b = wv2::from_jstring(env, base);
        wchar_t buf[4096];
        DWORD n = 4096;
        if (SUCCEEDED(UrlCreateFromPathW(p.c_str(), buf, &n, 0))) {
            const size_t q = url.find_first_of(L"?#");  // the asset URL's query and fragment stay
            out = std::wstring(buf, n) + (q == std::wstring::npos ? L"" : url.substr(q));
            wchar_t bbuf[4096];
            DWORD bn = 4096;
            if (SUCCEEDED(UrlCreateFromPathW(b.c_str(), bbuf, &bn, 0))) *prefix = std::wstring(bbuf, bn) + L"/";
        }
        if (page) env->DeleteLocalRef(page);
        if (base) env->DeleteLocalRef(base);
    }
    if (r) env->DeleteLocalRef(r);
    return out;
}

void JNICALL Wv_nativeLoadUrl(JNIEnv* env, jclass, jint id, jstring jurl, jobjectArray headers) {
    const std::wstring url = wv2::from_jstring(env, jurl);
    std::wstring hdr;
    if (headers) {
        const jsize n = env->GetArrayLength(headers);
        for (jsize k = 0; k + 1 < n; k += 2) {
            auto key = static_cast<jstring>(env->GetObjectArrayElement(headers, k));
            auto val = static_cast<jstring>(env->GetObjectArrayElement(headers, k + 1));
            hdr += wv2::from_jstring(env, key) + L": " + wv2::from_jstring(env, val) + L"\r\n";
            if (key) env->DeleteLocalRef(key);
            if (val) env->DeleteLocalRef(val);
        }
    }
    wv2::post([id, url, hdr] {
        run_or_queue(id, [url, hdr](View* v) {
            std::wstring prefix;
            const std::wstring target = materialize(v->id, url, &prefix);
            if (!prefix.empty()) v->asset_file_prefix = prefix;
            ++v->own_navs;
            if (!hdr.empty()) {
                ComPtr<ICoreWebView2Environment> env;
                auto v2 = v->wv.query<ICoreWebView2_2>(IID_ICoreWebView2_2);
                if (v2) v2->get_Environment(env.put());
                auto env2 = env.query<ICoreWebView2Environment2>(IID_ICoreWebView2Environment2);
                ComPtr<ICoreWebView2WebResourceRequest> req;
                if (env2 && SUCCEEDED(env2->CreateWebResourceRequest(target.c_str(), L"GET", nullptr, hdr.c_str(),
                                                                     req.put())) &&
                    req && SUCCEEDED(v2->NavigateWithWebResourceRequest(req.get()))) {
                    return;
                }
            }
            if (FAILED(v->wv->Navigate(target.c_str()))) {
                --v->own_navs;
                post_event(v->id, EV_RECEIVED_ERROR, L"net::ERR_INVALID_URL", url, L"main", -12);
            }
        });
    });
}

std::string utf8_of(const std::wstring& s) { return wv2::narrow(s); }

void JNICALL Wv_nativeLoadData(JNIEnv* env, jclass, jint id, jstring jbase, jstring jdata, jstring jmime,
                               jstring jenc) {
    const std::wstring base = wv2::from_jstring(env, jbase), data = wv2::from_jstring(env, jdata);
    std::wstring mime = wv2::from_jstring(env, jmime), enc = wv2::from_jstring(env, jenc);
    if (mime.empty()) mime = L"text/html";
    wv2::post([=] {
        run_or_queue(id, [=](View* v) {
            ++v->own_navs;
            const std::wstring scheme = wv2::scheme_of(base);
            if (scheme == L"http" || scheme == L"https") {
                // Served for the base URL: the page gets the base's origin.
                v->data_base = base;
                v->data_bytes = utf8_of(data);
                v->data_mime = mime + L"; charset=utf-8";
                v->wv->Navigate(base.c_str());
                return;
            }
            if (!base.empty() && _wcsicmp(enc.c_str(), L"base64") != 0 && mime == L"text/html") {
                // about:blank (or a base WebView2 cannot serve): the document as
                // a string.
                v->wv->NavigateToString(data.c_str());
                return;
            }
            // loadData(): a data: URL, built as AwContents.loadData builds it
            // (the data as the app gave it).
            const bool b64 = _wcsicmp(enc.c_str(), L"base64") == 0;
            const std::wstring url = L"data:" + mime + (b64 ? L";base64," : L";charset=utf-8,") + data;
            if (SUCCEEDED(v->wv->Navigate(url.c_str()))) return;
            if (!b64) v->wv->NavigateToString(data.c_str());
            else --v->own_navs;
            return;
        });
    });
}

void JNICALL Wv_nativeGo(JNIEnv*, jclass, jint id, jint steps) {
    wv2::post([id, steps] {
        run_or_queue(id, [steps](View* v) {
            if (steps == -1) v->wv->GoBack();
            else if (steps == 1) v->wv->GoForward();
            else v->wv->ExecuteScript((L"history.go(" + std::to_wstring(steps) + L")").c_str(), nullptr);
        });
    });
}

void JNICALL Wv_nativeReload(JNIEnv*, jclass, jint id) {
    wv2::post([id] { run_or_queue(id, [](View* v) { v->wv->Reload(); }); });
}

void JNICALL Wv_nativeStopLoading(JNIEnv*, jclass, jint id) {
    wv2::post([id] { run_or_queue(id, [](View* v) { v->wv->Stop(); }); });
}

void JNICALL Wv_nativeEvaluateJs(JNIEnv* env, jclass, jint id, jstring jscript, jint cb) {
    const std::wstring script = wv2::from_jstring(env, jscript);
    wv2::post([id, script, cb] {
        View* v = find_view(id);
        if (!v || v->failed) {
            if (cb) post_event(id, EV_JS_RESULT, L"", L"", L"", cb, true);
            return;
        }
        run_or_queue(id, [script, cb](View* v) {
            const int vid = v->id;
            auto done = wv2::handler<ICoreWebView2ExecuteScriptCompletedHandler>(
                IID_ICoreWebView2ExecuteScriptCompletedHandler,
                [vid, cb](HRESULT hr, LPCWSTR json) -> HRESULT {
                    if (cb) {
                        if (SUCCEEDED(hr) && json) post_event(vid, EV_JS_RESULT, json, L"", L"", cb);
                        else post_event(vid, EV_JS_RESULT, L"", L"", L"", cb, true);
                    }
                    return S_OK;
                });
            v->wv->ExecuteScript(script.c_str(), done.get());
        });
    });
}

void JNICALL Wv_nativeSetInjectionScript(JNIEnv* env, jclass, jint id, jstring jscript) {
    const std::wstring script = wv2::from_jstring(env, jscript);
    wv2::post([id, script] {
        run_or_queue(id, [script](View* v) {
            if (!v->script_id.empty()) v->wv->RemoveScriptToExecuteOnDocumentCreated(v->script_id.c_str());
            v->script_id.clear();
            v->script = script;
            ++v->busy;
            const int vid = v->id;
            auto done = wv2::handler<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler>(
                IID_ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler,
                [vid](HRESULT hr, LPCWSTR sid) -> HRESULT {
                    View* v = find_view(vid);
                    if (!v) return S_OK;
                    if (SUCCEEDED(hr) && sid) v->script_id = sid;
                    if (v->busy > 0) --v->busy;
                    drain(v);
                    return S_OK;
                });
            if (FAILED(v->wv->AddScriptToExecuteOnDocumentCreated(script.c_str(), done.get())) && v->busy > 0) {
                --v->busy;
            }
        });
    });
}

void JNICALL Wv_nativeNavDecision(JNIEnv*, jclass, jint id, jboolean allow) {
    std::lock_guard<std::mutex> hold(g_decisions_lock);
    auto it = g_decisions.find(id);
    if (it == g_decisions.end()) return;
    it->second.allow = allow == JNI_TRUE;
    SetEvent(it->second.done);
}

void JNICALL Wv_nativeJsDialogDone(JNIEnv* env, jclass, jint id, jint, jboolean confirmed, jstring text) {
    const bool ok = confirmed == JNI_TRUE;
    const bool has_text = text != nullptr;
    const std::wstring t = wv2::from_jstring(env, text);
    wv2::post([id, ok, has_text, t] { finish_dialog(id, ok, has_text ? &t : nullptr); });
}

jstring JNICALL Wv_nativeCookieGet(JNIEnv* env, jclass, jstring jurl) {
    if (!wv2::available()) return nullptr;
    resolve_java(env);
    capture_folder(env);
    const std::wstring url = wv2::from_jstring(env, jurl);
    struct R {
        bool have;
        std::wstring v;
    };
    const R r = cookie_call<R>(R{false, L""}, [url](std::function<void(R)> finish) {
        with_cookies([url, finish](ICoreWebView2CookieManager* m) {
            if (!m) {
                finish(R{false, L""});
                return;
            }
            auto done = wv2::handler<ICoreWebView2GetCookiesCompletedHandler>(
                IID_ICoreWebView2GetCookiesCompletedHandler, [finish](HRESULT hr, ICoreWebView2CookieList* list) {
                    finish(R{SUCCEEDED(hr), SUCCEEDED(hr) ? cookie_header(list) : L""});
                    return S_OK;
                });
            if (FAILED(m->GetCookies(url.c_str(), done.get()))) finish(R{false, L""});
        });
    });
    if (!r.have || r.v.empty()) return nullptr;  // getCookie answers null when there are none
    return wv2::to_jstring(env, r.v);
}

void JNICALL Wv_nativeCookieSet(JNIEnv* env, jclass, jstring jurl, jstring jvalue) {
    if (!wv2::available()) return;
    resolve_java(env);
    capture_folder(env);
    const std::wstring url = wv2::from_jstring(env, jurl), value = wv2::from_jstring(env, jvalue);
    wv2::post([url, value] {
        with_cookies([url, value](ICoreWebView2CookieManager* m) { set_cookie_now(m, url, value); });
    });
}

void JNICALL Wv_nativeCookieRemoveAll(JNIEnv* env, jclass) {
    if (!wv2::available()) return;
    resolve_java(env);
    capture_folder(env);
    wv2::post([] {
        with_cookies([](ICoreWebView2CookieManager* m) {
            if (m) m->DeleteAllCookies();
        });
    });
}

jboolean JNICALL Wv_nativeCookieHas(JNIEnv* env, jclass) {
    if (!wv2::available()) return JNI_FALSE;
    resolve_java(env);
    capture_folder(env);
    const bool has = cookie_call<bool>(false, [](std::function<void(bool)> finish) {
        with_cookies([finish](ICoreWebView2CookieManager* m) {
            if (!m) {
                finish(false);
                return;
            }
            auto done = wv2::handler<ICoreWebView2GetCookiesCompletedHandler>(
                IID_ICoreWebView2GetCookiesCompletedHandler, [finish](HRESULT hr, ICoreWebView2CookieList* list) {
                    UINT n = 0;
                    if (SUCCEEDED(hr) && list) list->get_Count(&n);
                    finish(n > 0);
                    return S_OK;
                });
            if (FAILED(m->GetCookies(L"", done.get()))) finish(false);
        });
    });
    return has ? JNI_TRUE : JNI_FALSE;
}

constexpr const char* kCls = "android.webkit.MacApkWebViewBridge";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeAvailable", "()Z", Wv_nativeAvailable),
    WINAPK_HOST_NATIVE(kCls, "nativeInit", "(Ljava/lang/String;)V", Wv_nativeInit),
    WINAPK_HOST_NATIVE(kCls, "nativeCreate", "(ILjava/lang/String;Ljava/lang/String;Z)V", Wv_nativeCreate),
    WINAPK_HOST_NATIVE(kCls, "nativeDestroy", "(I)V", Wv_nativeDestroy),
    WINAPK_HOST_NATIVE(kCls, "nativeCreateOptions", "(IZ)V", Wv_nativeCreateOptions),
    WINAPK_HOST_NATIVE(kCls, "nativeSetFrame", "(IIIIIIIZ)V", Wv_nativeSetFrame),
    WINAPK_HOST_NATIVE(kCls, "nativeSetOccluders", "(I[I)V", Wv_nativeSetOccluders),
    WINAPK_HOST_NATIVE(kCls, "nativeSetBackgroundColor", "(II)V", Wv_nativeSetBackgroundColor),
    WINAPK_HOST_NATIVE(kCls, "nativeSetBackdrop", "(IIZ)V", Wv_nativeSetBackdrop),
    WINAPK_HOST_NATIVE(kCls, "nativeApplySettings", "(IZLjava/lang/String;)V", Wv_nativeApplySettings),
    WINAPK_HOST_NATIVE(kCls, "nativeSetFileUrlAccess", "(IZLjava/lang/String;)V", Wv_nativeSetFileUrlAccess),
    WINAPK_HOST_NATIVE(kCls, "nativeLoadUrl", "(ILjava/lang/String;[Ljava/lang/String;)V", Wv_nativeLoadUrl),
    WINAPK_HOST_NATIVE(kCls, "nativeLoadData",
                       "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                       Wv_nativeLoadData),
    WINAPK_HOST_NATIVE(kCls, "nativeGo", "(II)V", Wv_nativeGo),
    WINAPK_HOST_NATIVE(kCls, "nativeReload", "(I)V", Wv_nativeReload),
    WINAPK_HOST_NATIVE(kCls, "nativeStopLoading", "(I)V", Wv_nativeStopLoading),
    WINAPK_HOST_NATIVE(kCls, "nativeEvaluateJs", "(ILjava/lang/String;I)V", Wv_nativeEvaluateJs),
    WINAPK_HOST_NATIVE(kCls, "nativeSetInjectionScript", "(ILjava/lang/String;)V", Wv_nativeSetInjectionScript),
    WINAPK_HOST_NATIVE(kCls, "nativeNavDecision", "(IZ)V", Wv_nativeNavDecision),
    WINAPK_HOST_NATIVE(kCls, "nativeJsDialogDone", "(IIZLjava/lang/String;)V", Wv_nativeJsDialogDone),
    WINAPK_HOST_NATIVE(kCls, "nativeCookieGet", "(Ljava/lang/String;)Ljava/lang/String;", Wv_nativeCookieGet),
    WINAPK_HOST_NATIVE(kCls, "nativeCookieSet", "(Ljava/lang/String;Ljava/lang/String;)V", Wv_nativeCookieSet),
    WINAPK_HOST_NATIVE(kCls, "nativeCookieRemoveAll", "()V", Wv_nativeCookieRemoveAll),
    WINAPK_HOST_NATIVE(kCls, "nativeCookieHas", "()Z", Wv_nativeCookieHas),
};

}  // namespace

HostMethods native_webview_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
