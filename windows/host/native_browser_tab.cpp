// native_browser_tab.cpp -- com.macapk.loader.HostBrowserTab on Windows: the
// browser tab of a Custom Tab or Trusted Web Activity launch, in a WebView2
// window.
//
// On a device, an app that opens a Custom Tab (androidx.browser's
// CustomTabsIntent, a TWA launcher, AppAuth) gets the browser's tab activity in
// its own task. Every navigation in that tab goes through the browser's
// external-navigation check, so a page that redirects to a URL the app itself
// handles -- an OAuth or sign-in callback, as a custom scheme
// (`com.example:/oauth2redirect?code=...`) or an App Link
// (`https://auth-callback.example.com/...`) -- is not loaded: the browser starts
// the app's activity with it and the tab goes away. A desktop browser has no
// such check, and a custom scheme has no way back to the app at all, which is
// why a sign-in that went through the system browser could never finish.
//
// Here the tab is a top-level window of the runtime with a minimal toolbar
// (close, back, the URL read-only) and the page title as its title, over the
// app's window. Its navigation check (decide below) asks the Java side,
// HostBrowserTab.macapkClaims, whether the app's own manifest takes the URL --
// HostUrlIntake's resolution: VIEW + BROWSABLE + the URL against the app's
// exported activities with MATCH_DEFAULT_ONLY, AOSP's <data> matching, the
// intent: URI hand-off -- and when it does, cancels the navigation, queues the
// URL for NativeCocoa.takeHostUrls and rings HostUrlIntake.macapkOnHostUrl, the
// same delivery a URL from the macOS browser takes upstream: the browser's
// sanitised ACTION_VIEW intent, referrer com.macapk.host, delivered as a
// cross-app arrival (onNewIntent for a singleTop top). Then the tab closes.
//
// As in Chromium's RedirectHandler, the URL the app asked to open is never sent
// back to it, nor are its redirects when that URL itself is one the app
// handles; any later navigation is checked. Other web URLs keep loading in the
// tab. A scheme the app does not handle (mailto:, tel:, another app's
// intent:) is given to HostBrowserTab.macapkExternal, which may hand it to the
// host as a browser would, or name a fallback URL to load.
//
// The same window is the App Link sign-in window (open_applink_window, for
// NativeCocoa.openAppLinkWindow; upstream's applink_window.mm). When an app
// opens a sign-in page in the BROWSER (a plain ACTION_VIEW, no Custom Tab)
// whose redirect_uri is one of its verified App Links or its own link scheme,
// com.macapk.loader.AppLinks asks for it here instead of the system browser:
// Edge or Chrome would load an https redirect as a web page, and the app would
// never get the result. Such a window checks one thing, the return prefix
// AppLinks passes (applink_match.h: the redirect_uri without its query): the
// navigation to it, a redirect or a pop-up included, is cancelled and
// delivered like a tab's hand-off, and the window closes. Everything else
// loads. It has no HostBrowserTab record on the Java side, so none of the
// tab's upcalls are made for it; closed by the user, or after kSignInTimeoutMs
// as the Google sign-in window, the app gets nothing, as when a user abandons
// the browser. A window that cannot show its page opens the URL in the system
// browser, the path from before.
//
// Everything here runs on the WebView2 thread (webview2_host.h).

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

#include <windows.h>
#include <shellapi.h>

#include "applink_match.h"
#include "host_natives.h"
#include "webview2_host.h"
#include "win32_window.h"

namespace winapk::host {

namespace {

using wv2::ComPtr;

constexpr wchar_t kTabClass[] = L"WinApkBrowserTab";
constexpr int kIdClose = 101, kIdBack = 102, kIdUrl = 103;
// The browser profile of these tabs, in the app's WebView2 user-data folder:
// apart from the profile the app's own WebViews use, as a device keeps the
// browser's cookies apart from the app's WebView.
constexpr wchar_t kProfile[] = L"browser";

// CustomTabsCallback navigation events (androidx.browser).
constexpr int kNavStarted = 1, kNavFinished = 2, kNavFailed = 3;

// The App Link sign-in window's ceiling, the Google sign-in window's: long
// enough for a password and a second factor, and a forgotten window still goes.
constexpr UINT_PTR kSignInTimer = 1;
constexpr UINT kSignInTimeoutMs = 600 * 1000;

struct Tab {
    int id = 0;
    HWND hwnd = nullptr, close = nullptr, back = nullptr, url = nullptr;
    HFONT text_font = nullptr, icon_font = nullptr;
    UINT dpi = 96;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> view;
    std::wstring folder;
    std::wstring initial_url;
    bool initial_claimed = false;  // the app itself handles the URL it opened
    bool first_seen = false;
    UINT64 first_nav = 0;          // the navigation the tab was opened with
    bool handed_off = false;       // a URL went back to the app
    bool notified = false;         // Java heard that the tab is gone
    bool hover_close = false, hover_back = false;
    // Web URLs the tab did not load (they went to the app): WebView2 may
    // already be sending their request when the cancel lands, so the document
    // request is answered here (204, nothing loads). On a device the browser
    // decides before any request, so a sign-in callback's code never reaches
    // the network.
    std::vector<std::wstring> refused;
    // Non-empty: the App Link sign-in window (see the header), which hands
    // back only the navigation to this address.
    std::wstring return_prefix;
    bool applink() const { return !return_prefix.empty(); }
    const char* tag() const { return applink() ? "applink-signin" : "browser tab"; }
};

std::map<int, Tab*> g_tabs;  // WebView2 thread only
std::atomic<int> g_next_id{1};

// HostBrowserTab's upcalls, resolved by nativeOpen on a Java thread.
jclass g_cls = nullptr;
jmethodID g_claims = nullptr, g_external = nullptr, g_event = nullptr, g_closed = nullptr, g_failed = nullptr;

Tab* find_tab(int id) {
    auto it = g_tabs.find(id);
    return it == g_tabs.end() ? nullptr : it->second;
}

// ---- the Java side -----------------------------------------------------------

bool java_claims(const std::wstring& url) {
    JNIEnv* env = wv2::env();
    if (!env || !g_claims) return false;
    jstring j = wv2::to_jstring(env, url);
    const bool yes = env->CallStaticBooleanMethod(g_cls, g_claims, j) == JNI_TRUE;
    env->DeleteLocalRef(j);
    if (wv2::check_java(env, "HostBrowserTab.macapkClaims")) return false;
    return yes;
}

// What the tab loads instead of `url` (a scheme it cannot load), or empty.
std::wstring java_external(const std::wstring& url, bool user) {
    JNIEnv* env = wv2::env();
    if (!env || !g_external) return std::wstring();
    jstring j = wv2::to_jstring(env, url);
    auto r = static_cast<jstring>(env->CallStaticObjectMethod(g_cls, g_external, j, user ? JNI_TRUE : JNI_FALSE));
    env->DeleteLocalRef(j);
    if (wv2::check_java(env, "HostBrowserTab.macapkExternal")) return std::wstring();
    std::wstring out = wv2::from_jstring(env, r);
    if (r) env->DeleteLocalRef(r);
    return out;
}

void java_event(int id, int event) {
    if (Tab* t = find_tab(id); t && t->applink()) return;
    JNIEnv* env = wv2::env();
    if (!env || !g_event) return;
    env->CallStaticVoidMethod(g_cls, g_event, id, event);
    wv2::check_java(env, "HostBrowserTab.macapkTabEvent");
}

void java_closed(Tab* t) {
    if (t->notified) return;
    t->notified = true;
    if (t->applink()) return;
    JNIEnv* env = wv2::env();
    if (!env || !g_closed) return;
    env->CallStaticVoidMethod(g_cls, g_closed, t->id, t->handed_off ? JNI_TRUE : JNI_FALSE);
    wv2::check_java(env, "HostBrowserTab.macapkTabClosed");
}

// The tab could not show its page (no environment, no controller): the Java
// side opens the URL the way it did before there was a tab.
void java_failed(Tab* t) {
    if (t->notified) return;
    t->notified = true;
    if (t->applink()) {
        // The Java side was told the window opened; the browser is where the
        // URL went before there was one.
        std::fprintf(stderr, "[win.apk] applink-signin %d: no window; %s goes to the system browser\n", t->id,
                     wv2::redact(t->initial_url).c_str());
        std::fflush(stderr);
        ShellExecuteW(nullptr, L"open", t->initial_url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
        return;
    }
    JNIEnv* env = wv2::env();
    if (!env || !g_failed) return;
    jstring j = wv2::to_jstring(env, t->initial_url);
    env->CallStaticVoidMethod(g_cls, g_failed, t->id, j);
    env->DeleteLocalRef(j);
    wv2::check_java(env, "HostBrowserTab.macapkTabFailed");
}

// ---- the navigation check ------------------------------------------------------

enum class Route { kLoad, kHandOff, kExternal };

Route decide(Tab* t, const std::wstring& url, bool initial_chain) {
    const std::wstring scheme = wv2::scheme_of(url);
    // A page's own documents, never anything an app takes.
    if (scheme == L"about" || scheme == L"data" || scheme == L"blob" || scheme == L"javascript") {
        return Route::kLoad;
    }
    if (t->applink()) {
        if (applink::returns_to_app(url, t->return_prefix)) return Route::kHandOff;
        return scheme == L"http" || scheme == L"https" ? Route::kLoad : Route::kExternal;
    }
    if (java_claims(url) && !(initial_chain && t->initial_claimed)) return Route::kHandOff;
    if (scheme == L"http" || scheme == L"https") return Route::kLoad;
    return Route::kExternal;
}

void hand_off(Tab* t, const std::wstring& url) {
    if (t->handed_off) return;
    t->handed_off = true;
    std::fprintf(stderr, "[win.apk] %s %d: %s goes back to the app\n", t->tag(), t->id, wv2::redact(url).c_str());
    std::fflush(stderr);
    queue_host_url(wv2::env(), url);
    PostMessageW(t->hwnd, WM_CLOSE, 0, 0);
}

// A URL the tab will not load: a navigation that was cancelled, or a new window.
void route_away(Tab* t, const std::wstring& url, bool user, Route r) {
    const std::wstring scheme = wv2::scheme_of(url);
    if (scheme == L"http" || scheme == L"https") {
        t->refused.push_back(url);
        if (t->refused.size() > 8) t->refused.erase(t->refused.begin());
    }
    if (r == Route::kHandOff) {
        hand_off(t, url);
        return;
    }
    std::wstring fallback = java_external(url, user);
    if (!fallback.empty() && t->view) t->view->Navigate(fallback.c_str());
}

// ---- the toolbar --------------------------------------------------------------

int toolbar_h(const Tab* t) { return MulDiv(44, static_cast<int>(t->dpi), 96); }

void make_fonts(Tab* t) {
    if (t->text_font) DeleteObject(t->text_font);
    if (t->icon_font) DeleteObject(t->icon_font);
    const int px = MulDiv(14, static_cast<int>(t->dpi), 96);
    t->text_font = CreateFontW(-px, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                               CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
    // Segoe Fluent Icons (Windows 11), else Segoe MDL2 Assets (Windows 10):
    // the same code points for Cancel and Back.
    const int ipx = MulDiv(14, static_cast<int>(t->dpi), 96);
    t->icon_font = CreateFontW(-ipx, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                               CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe Fluent Icons");
    LOGFONTW probe{};
    if (!t->icon_font || !GetObjectW(t->icon_font, sizeof(probe), &probe) ||
        lstrcmpiW(probe.lfFaceName, L"Segoe Fluent Icons") != 0) {
        if (t->icon_font) DeleteObject(t->icon_font);
        t->icon_font = CreateFontW(-ipx, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                                   OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH,
                                   L"Segoe MDL2 Assets");
    }
    if (t->url) SendMessageW(t->url, WM_SETFONT, reinterpret_cast<WPARAM>(t->text_font), TRUE);
}

void layout(Tab* t) {
    RECT c;
    GetClientRect(t->hwnd, &c);
    const int bar = toolbar_h(t);
    const int pad = MulDiv(4, static_cast<int>(t->dpi), 96);
    const int btn = bar - 2 * pad;
    MoveWindow(t->close, pad, pad, btn, btn, TRUE);
    MoveWindow(t->back, pad + btn + pad, pad, btn, btn, TRUE);
    const int ex = pad + 2 * (btn + pad) + pad;
    const int eh = MulDiv(22, static_cast<int>(t->dpi), 96);
    MoveWindow(t->url, ex, (bar - eh) / 2, (c.right - ex - 2 * pad) > 0 ? c.right - ex - 2 * pad : 0, eh, TRUE);
    if (t->controller) {
        RECT web{0, bar, c.right, c.bottom > bar ? c.bottom : bar};
        t->controller->put_Bounds(web);
    }
}

void draw_button(Tab* t, const DRAWITEMSTRUCT* d) {
    const bool back = d->CtlID == kIdBack;
    const bool disabled = (d->itemState & ODS_DISABLED) != 0;
    const bool pressed = (d->itemState & ODS_SELECTED) != 0;
    const bool hover = back ? t->hover_back : t->hover_close;
    const COLORREF bg = GetSysColor(COLOR_WINDOW);
    COLORREF fill = bg;
    if (!disabled && pressed) fill = RGB(GetRValue(bg) * 80 / 100, GetGValue(bg) * 80 / 100, GetBValue(bg) * 80 / 100);
    else if (!disabled && hover) fill = RGB(GetRValue(bg) * 90 / 100, GetGValue(bg) * 90 / 100, GetBValue(bg) * 90 / 100);
    HBRUSH brush = CreateSolidBrush(fill);
    FillRect(d->hDC, &d->rcItem, brush);
    DeleteObject(brush);
    SetBkMode(d->hDC, TRANSPARENT);
    SetTextColor(d->hDC, GetSysColor(disabled ? COLOR_GRAYTEXT : COLOR_WINDOWTEXT));
    HGDIOBJ old = SelectObject(d->hDC, t->icon_font);
    const wchar_t glyph[2] = {static_cast<wchar_t>(back ? 0xE72B : 0xE711), 0};  // Back, Cancel
    RECT r = d->rcItem;
    DrawTextW(d->hDC, glyph, 1, &r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(d->hDC, old);
}

void set_url_text(Tab* t, const std::wstring& u) { SetWindowTextW(t->url, u.c_str()); }

std::wstring host_of(const std::wstring& url) {
    size_t s = url.find(L"://");
    if (s == std::wstring::npos) return url;
    s += 3;
    const size_t e = url.find_first_of(L"/?#", s);
    return url.substr(s, e == std::wstring::npos ? std::wstring::npos : e - s);
}

// ---- the WebView ----------------------------------------------------------------

void hook_events(Tab* t) {
    const int id = t->id;
    EventRegistrationToken token;
    t->view->add_NavigationStarting(
        wv2::handler<ICoreWebView2NavigationStartingEventHandler>(
            IID_ICoreWebView2NavigationStartingEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                LPWSTR raw = nullptr;
                a->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                UINT64 nav = 0;
                a->get_NavigationId(&nav);
                BOOL user = FALSE;
                a->get_IsUserInitiated(&user);
                if (t->handed_off) {
                    a->put_Cancel(TRUE);
                    return S_OK;
                }
                if (!t->first_seen) {
                    // The URL the app asked to open: the tab's own page.
                    t->first_seen = true;
                    t->first_nav = nav;
                    java_event(id, kNavStarted);
                    return S_OK;
                }
                const Route r = decide(t, uri, nav == t->first_nav);
                if (r == Route::kLoad) {
                    java_event(id, kNavStarted);
                    return S_OK;
                }
                a->put_Cancel(TRUE);
                route_away(t, uri, user == TRUE, r);
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_NewWindowRequested(
        wv2::handler<ICoreWebView2NewWindowRequestedEventHandler>(
            IID_ICoreWebView2NewWindowRequestedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                // A tab has no second window: target=_blank and window.open load
                // here, after the same check, as in a Custom Tab.
                a->put_Handled(TRUE);
                LPWSTR raw = nullptr;
                a->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                BOOL user = FALSE;
                a->get_IsUserInitiated(&user);
                if (uri.empty() || t->handed_off) return S_OK;
                const Route r = decide(t, uri, false);
                if (r == Route::kLoad) t->view->Navigate(uri.c_str());
                else route_away(t, uri, user == TRUE, r);
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_NavigationCompleted(
        wv2::handler<ICoreWebView2NavigationCompletedEventHandler>(
            IID_ICoreWebView2NavigationCompletedEventHandler,
            [id](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t || t->handed_off) return S_OK;
                BOOL ok = FALSE;
                a->get_IsSuccess(&ok);
                COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                a->get_WebErrorStatus(&status);
                // A navigation this side cancelled is no failure of the page.
                if (!ok && status == COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED) return S_OK;
                if (t->applink()) {
                    int http = 0;
                    if (auto a2 = ComPtr<ICoreWebView2NavigationCompletedEventArgs>::retain(a)
                                      .query<ICoreWebView2NavigationCompletedEventArgs2>(
                                          IID_ICoreWebView2NavigationCompletedEventArgs2)) {
                        a2->get_HttpStatusCode(&http);
                    }
                    if (!ok || http >= 400) {
                        // The page says what went wrong; the log keeps its codes.
                        LPWSTR raw = nullptr;
                        if (t->view) t->view->get_Source(&raw);
                        std::fprintf(stderr, "[win.apk] applink-signin %d: %s failed (web error %d, HTTP %d)\n", id,
                                     wv2::redact(wv2::take_co_string(raw)).c_str(), static_cast<int>(status), http);
                        std::fflush(stderr);
                    }
                }
                java_event(id, ok ? kNavFinished : kNavFailed);
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_SourceChanged(
        wv2::handler<ICoreWebView2SourceChangedEventHandler>(
            IID_ICoreWebView2SourceChangedEventHandler,
            [id](ICoreWebView2* sender, ICoreWebView2SourceChangedEventArgs*) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                LPWSTR raw = nullptr;
                sender->get_Source(&raw);
                set_url_text(t, wv2::take_co_string(raw));
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_DocumentTitleChanged(
        wv2::handler<ICoreWebView2DocumentTitleChangedEventHandler>(
            IID_ICoreWebView2DocumentTitleChangedEventHandler,
            [id](ICoreWebView2* sender, IUnknown*) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                LPWSTR raw = nullptr;
                sender->get_DocumentTitle(&raw);
                std::wstring title = wv2::take_co_string(raw);
                if (title.empty()) {
                    LPWSTR src = nullptr;
                    sender->get_Source(&src);
                    title = host_of(wv2::take_co_string(src));
                }
                SetWindowTextW(t->hwnd, title.c_str());
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_HistoryChanged(
        wv2::handler<ICoreWebView2HistoryChangedEventHandler>(
            IID_ICoreWebView2HistoryChangedEventHandler,
            [id](ICoreWebView2* sender, IUnknown*) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                BOOL can = FALSE;
                sender->get_CanGoBack(&can);
                EnableWindow(t->back, can);
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_WindowCloseRequested(
        wv2::handler<ICoreWebView2WindowCloseRequestedEventHandler>(
            IID_ICoreWebView2WindowCloseRequestedEventHandler,
            [id](ICoreWebView2*, IUnknown*) -> HRESULT {
                if (Tab* t = find_tab(id)) PostMessageW(t->hwnd, WM_CLOSE, 0, 0);
                return S_OK;
            })
            .get(),
        &token);
    t->view->add_ProcessFailed(
        wv2::handler<ICoreWebView2ProcessFailedEventHandler>(
            IID_ICoreWebView2ProcessFailedEventHandler,
            [id](ICoreWebView2* sender, ICoreWebView2ProcessFailedEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                COREWEBVIEW2_PROCESS_FAILED_KIND kind = COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED;
                a->get_ProcessFailedKind(&kind);
                std::fprintf(stderr, "[win.apk] %s %d: WebView2 process failed (kind %d)\n", t->tag(), id,
                             static_cast<int>(kind));
                if (kind == COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED) {
                    PostMessageW(t->hwnd, WM_CLOSE, 0, 0);
                } else if (kind == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED ||
                           kind == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE) {
                    sender->Reload();
                }
                return S_OK;
            })
            .get(),
        &token);
    // The backstop for a scheme no navigation check saw (WebView2 would ask the
    // user, then start Windows' handler for it): never Windows', always ours.
    if (auto v18 = t->view.query<ICoreWebView2_18>(IID_ICoreWebView2_18)) {
        v18->add_LaunchingExternalUriScheme(
            wv2::handler<ICoreWebView2LaunchingExternalUriSchemeEventHandler>(
                IID_ICoreWebView2LaunchingExternalUriSchemeEventHandler,
                [id](ICoreWebView2*, ICoreWebView2LaunchingExternalUriSchemeEventArgs* a) -> HRESULT {
                    a->put_Cancel(TRUE);
                    Tab* t = find_tab(id);
                    if (!t || t->handed_off) return S_OK;
                    LPWSTR raw = nullptr;
                    a->get_Uri(&raw);
                    const std::wstring uri = wv2::take_co_string(raw);
                    BOOL user = FALSE;
                    a->get_IsUserInitiated(&user);
                    route_away(t, uri, user == TRUE, decide(t, uri, false));
                    return S_OK;
                })
                .get(),
            &token);
    }
    t->view->add_WebResourceRequested(
        wv2::handler<ICoreWebView2WebResourceRequestedEventHandler>(
            IID_ICoreWebView2WebResourceRequestedEventHandler,
            [id](ICoreWebView2* sender, ICoreWebView2WebResourceRequestedEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t || t->refused.empty()) return S_OK;
                ComPtr<ICoreWebView2WebResourceRequest> req;
                a->get_Request(req.put());
                LPWSTR raw = nullptr;
                if (req) req->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                for (auto r = t->refused.begin(); r != t->refused.end(); ++r) {
                    if (*r != uri) continue;
                    t->refused.erase(r);
                    ComPtr<ICoreWebView2Environment> env;
                    if (auto v2 = ComPtr<ICoreWebView2>::retain(sender).query<ICoreWebView2_2>(IID_ICoreWebView2_2)) {
                        v2->get_Environment(env.put());
                    }
                    ComPtr<ICoreWebView2WebResourceResponse> none;
                    if (env) env->CreateWebResourceResponse(nullptr, 204, L"No Content", L"", none.put());
                    if (none) a->put_Response(none.get());
                    break;
                }
                return S_OK;
            })
            .get(),
        &token);
    t->view->AddWebResourceRequestedFilter(L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT);
    t->controller->add_AcceleratorKeyPressed(
        wv2::handler<ICoreWebView2AcceleratorKeyPressedEventHandler>(
            IID_ICoreWebView2AcceleratorKeyPressedEventHandler,
            [id](ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs* a) -> HRESULT {
                Tab* t = find_tab(id);
                if (!t) return S_OK;
                COREWEBVIEW2_KEY_EVENT_KIND kind = COREWEBVIEW2_KEY_EVENT_KIND_KEY_UP;
                a->get_KeyEventKind(&kind);
                UINT key = 0;
                a->get_VirtualKey(&key);
                const bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
                if (kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN && ctrl && key == 'W') {
                    a->put_Handled(TRUE);
                    PostMessageW(t->hwnd, WM_CLOSE, 0, 0);
                } else if ((kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN && key == VK_LEFT) ||
                           (kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN && key == VK_BROWSER_BACK)) {
                    a->put_Handled(TRUE);
                    t->view->GoBack();
                }
                return S_OK;
            })
            .get(),
        &token);
}

void controller_ready(int id, HRESULT hr, ICoreWebView2Controller* controller) {
    Tab* t = find_tab(id);
    if (!t) {
        if (controller) controller->Close();
        return;
    }
    if (FAILED(hr) || !controller) {
        std::fprintf(stderr, "[win.apk] %s %d: no WebView2 controller (HRESULT 0x%08lx)\n", t->tag(), id,
                     static_cast<unsigned long>(hr));
        java_failed(t);
        DestroyWindow(t->hwnd);
        return;
    }
    t->controller = ComPtr<ICoreWebView2Controller>::retain(controller);
    controller->get_CoreWebView2(t->view.put());
    if (!t->view) {
        java_failed(t);
        DestroyWindow(t->hwnd);
        return;
    }
    ComPtr<ICoreWebView2Settings> settings;
    t->view->get_Settings(settings.put());
    if (settings) settings->put_AreDevToolsEnabled(FALSE);
    hook_events(t);
    layout(t);
    controller->put_IsVisible(TRUE);
    if (GetForegroundWindow() == t->hwnd) controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
    t->view->Navigate(t->initial_url.c_str());
}

void create_controller(int id, ICoreWebView2Environment* environment) {
    Tab* t = find_tab(id);
    if (!t) return;
    if (!environment) {
        std::fprintf(stderr, "[win.apk] %s %d: no WebView2 environment\n", t->tag(), id);
        java_failed(t);
        DestroyWindow(t->hwnd);
        return;
    }
    auto done = wv2::handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
        IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
        [id](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
            controller_ready(id, hr, c);
            return S_OK;
        });
    // A profile of its own (WebView2 1.0.1774+); older runtimes share the default one.
    auto env10 = ComPtr<ICoreWebView2Environment>::retain(environment)
                     .query<ICoreWebView2Environment10>(IID_ICoreWebView2Environment10);
    if (env10) {
        ComPtr<ICoreWebView2ControllerOptions> options;
        if (SUCCEEDED(env10->CreateCoreWebView2ControllerOptions(options.put())) && options) {
            options->put_ProfileName(kProfile);
            if (SUCCEEDED(env10->CreateCoreWebView2ControllerWithOptions(t->hwnd, options.get(), done.get()))) return;
        }
    }
    const HRESULT hr = environment->CreateCoreWebView2Controller(t->hwnd, done.get());
    if (FAILED(hr)) controller_ready(id, hr, nullptr);
}

// ---- the window ------------------------------------------------------------------

UINT dpi_of(HWND hwnd) {
    using Fn = UINT(WINAPI*)(HWND);
    static auto fn = reinterpret_cast<Fn>(
        reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow")));
    const UINT d = fn && hwnd ? fn(hwnd) : 96;
    return d ? d : 96;
}

// The effective DPI of a monitor (shcore's GetDpiForMonitor, Windows 8.1+).
UINT monitor_dpi(HMONITOR mon) {
    using Fn = HRESULT(WINAPI*)(HMONITOR, int, UINT*, UINT*);
    static HMODULE shcore = LoadLibraryW(L"shcore.dll");
    static auto fn = shcore ? reinterpret_cast<Fn>(reinterpret_cast<void*>(GetProcAddress(shcore, "GetDpiForMonitor")))
                            : nullptr;
    UINT x = 96, y = 96;
    if (fn && SUCCEEDED(fn(mon, 0 /* MDT_EFFECTIVE_DPI */, &x, &y)) && x) return x;
    return 96;
}

LRESULT CALLBACK tab_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* t = reinterpret_cast<Tab*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_NCCREATE: {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
            break;
        }
        case WM_CREATE: {
            t->hwnd = hwnd;
            t->dpi = dpi_of(hwnd);
            HINSTANCE inst = GetModuleHandleW(nullptr);
            t->close = CreateWindowExW(0, L"BUTTON", L"Close", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW, 0, 0, 0, 0, hwnd,
                                       reinterpret_cast<HMENU>(static_cast<INT_PTR>(kIdClose)), inst, nullptr);
            t->back = CreateWindowExW(0, L"BUTTON", L"Back", WS_CHILD | WS_VISIBLE | WS_DISABLED | BS_OWNERDRAW, 0,
                                      0, 0, 0, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kIdBack)), inst,
                                      nullptr);
            t->url = CreateWindowExW(0, L"EDIT", t->initial_url.c_str(),
                                     WS_CHILD | WS_VISIBLE | ES_READONLY | ES_AUTOHSCROLL, 0, 0, 0, 0, hwnd,
                                     reinterpret_cast<HMENU>(static_cast<INT_PTR>(kIdUrl)), inst, nullptr);
            make_fonts(t);
            layout(t);
            if (t->applink()) SetTimer(hwnd, kSignInTimer, kSignInTimeoutMs, nullptr);
            return 0;
        }
        case WM_TIMER:
            if (t && wp == kSignInTimer) {
                KillTimer(hwnd, kSignInTimer);
                std::fprintf(stderr, "[win.apk] applink-signin %d: timed out after %u s; nothing went to the app\n",
                             t->id, kSignInTimeoutMs / 1000);
                std::fflush(stderr);
                PostMessageW(hwnd, WM_CLOSE, 0, 0);
            }
            return 0;
        case WM_SIZE:
            if (t) layout(t);
            return 0;
        case WM_DPICHANGED: {
            t->dpi = HIWORD(wp);
            make_fonts(t);
            const RECT* r = reinterpret_cast<const RECT*>(lp);
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
            layout(t);
            return 0;
        }
        case WM_ERASEBKGND: {
            RECT c;
            GetClientRect(hwnd, &c);
            FillRect(reinterpret_cast<HDC>(wp), &c, GetSysColorBrush(COLOR_WINDOW));
            return 1;
        }
        case WM_CTLCOLORSTATIC:
        case WM_CTLCOLOREDIT: {
            HDC dc = reinterpret_cast<HDC>(wp);
            SetTextColor(dc, GetSysColor(COLOR_GRAYTEXT));
            SetBkColor(dc, GetSysColor(COLOR_WINDOW));
            return reinterpret_cast<LRESULT>(GetSysColorBrush(COLOR_WINDOW));
        }
        case WM_DRAWITEM:
            if (t) draw_button(t, reinterpret_cast<const DRAWITEMSTRUCT*>(lp));
            return TRUE;
        case WM_SETCURSOR:
            if (t && (reinterpret_cast<HWND>(wp) == t->close || reinterpret_cast<HWND>(wp) == t->back)) {
                const bool c = reinterpret_cast<HWND>(wp) == t->close;
                if ((c ? t->hover_close : t->hover_back) == false) {
                    t->hover_close = c;
                    t->hover_back = !c;
                    InvalidateRect(t->close, nullptr, FALSE);
                    InvalidateRect(t->back, nullptr, FALSE);
                }
            } else if (t && (t->hover_close || t->hover_back)) {
                t->hover_close = t->hover_back = false;
                InvalidateRect(t->close, nullptr, FALSE);
                InvalidateRect(t->back, nullptr, FALSE);
            }
            break;
        case WM_COMMAND:
            if (t && LOWORD(wp) == kIdClose) {
                PostMessageW(hwnd, WM_CLOSE, 0, 0);
                return 0;
            }
            if (t && LOWORD(wp) == kIdBack && t->view) {
                t->view->GoBack();
                return 0;
            }
            break;
        case WM_SETFOCUS:
            if (t && t->controller) t->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
            return 0;
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (t) {
                KillTimer(hwnd, kSignInTimer);
                if (t->controller) t->controller->Close();
                t->view.reset();
                t->controller.reset();
                std::fprintf(stderr, "[win.apk] %s %d closed%s\n", t->tag(), t->id,
                             t->handed_off ? " (its URL went to the app)"
                                           : t->applink() ? " (nothing went to the app)" : "");
                std::fflush(stderr);
                java_closed(t);
                g_tabs.erase(t->id);
            }
            return 0;
        case WM_NCDESTROY:
            if (t) {
                if (t->text_font) DeleteObject(t->text_font);
                if (t->icon_font) DeleteObject(t->icon_font);
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                delete t;
            }
            break;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void register_class() {
    static bool done = false;
    if (done) return;
    done = true;
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = tab_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));  // IDC_ARROW
    wc.hIcon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));      // IDI_APPLICATION
    wc.lpszClassName = kTabClass;
    RegisterClassExW(&wc);
}

// The app window's icons, for the tab's title bar and taskbar button. A
// bounded ask: that window's thread may be busy in Java.
void copy_app_icon(HWND tab) {
    HWND app = static_cast<HWND>(window_handle());
    if (!app) return;
    for (WPARAM which : {static_cast<WPARAM>(ICON_BIG), static_cast<WPARAM>(ICON_SMALL)}) {
        DWORD_PTR icon = 0;
        if (SendMessageTimeoutW(app, WM_GETICON, which, 0, SMTO_ABORTIFHUNG | SMTO_BLOCK, 300, &icon) && icon) {
            SendMessageW(tab, WM_SETICON, which, static_cast<LPARAM>(icon));
        }
    }
}

void open_on_thread(Tab* t) {
    register_class();
    g_tabs[t->id] = t;
    // Over the app's window, the way a Custom Tab covers the app on a phone
    // (that window is created, and placed, before the app's first activity
    // shows it); a phone-sized window where the app has none.
    HWND app = static_cast<HWND>(window_handle());
    RECT r{};
    const bool over_app = app && !IsIconic(app) && GetWindowRect(app, &r) && r.right - r.left > 100;
    int x = CW_USEDEFAULT, y = CW_USEDEFAULT, w = CW_USEDEFAULT, h = CW_USEDEFAULT;
    if (over_app) {
        x = r.left;
        y = r.top;
        w = r.right - r.left;
        h = r.bottom - r.top;
    } else {
        // Phone-shaped on the primary monitor, in its pixels (dpi_of(nullptr)
        // is 96 everywhere, which made the window small on a 150% screen).
        MONITORINFO mi{sizeof(mi)};
        GetMonitorInfoW(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY), &mi);
        const RECT& work = mi.rcWork;
        const UINT dpi = monitor_dpi(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY));
        w = std::min<int>(MulDiv(480, static_cast<int>(dpi), 96), (work.right - work.left) - 16);
        h = std::min<int>(MulDiv(800, static_cast<int>(dpi), 96), (work.bottom - work.top) - 16);
        x = work.left + ((work.right - work.left) - w) / 2;
        y = work.top + ((work.bottom - work.top) - h) / 2;
    }
    HWND hwnd = CreateWindowExW(0, kTabClass, host_of(t->initial_url).c_str(), WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
                                x, y, w, h, nullptr, nullptr, GetModuleHandleW(nullptr), t);
    if (!hwnd) {
        std::fprintf(stderr, "[win.apk] %s %d: no window (error %lu)\n", t->tag(), t->id, GetLastError());
        java_failed(t);
        g_tabs.erase(t->id);
        delete t;
        return;
    }
    copy_app_icon(hwnd);
    // Activated when the user is in the app (they just asked for the page),
    // never taken from another program.
    DWORD fg_pid = 0;
    HWND fg = GetForegroundWindow();
    if (fg) GetWindowThreadProcessId(fg, &fg_pid);
    if (fg && fg_pid == GetCurrentProcessId()) {
        ShowWindow(hwnd, SW_SHOWNORMAL);
        SetForegroundWindow(hwnd);
    } else {
        ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    }
    if (t->applink()) {
        std::fprintf(stderr, "[win.apk] applink-signin %d: %s, returns at %s\n", t->id,
                     wv2::redact(t->initial_url).c_str(), wv2::redact(t->return_prefix).c_str());
    } else {
        std::fprintf(stderr, "[win.apk] browser tab %d: %s\n", t->id, wv2::redact(t->initial_url).c_str());
    }
    std::fflush(stderr);
    const int id = t->id;
    wv2::with_environment(t->folder, [id](ICoreWebView2Environment* e) { create_controller(id, e); });
}

// ---- JNI ----------------------------------------------------------------------

void resolve_upcalls(JNIEnv* env, jclass cls) {
    if (g_cls) return;
    g_cls = static_cast<jclass>(env->NewGlobalRef(cls));
    g_claims = env->GetStaticMethodID(g_cls, "macapkClaims", "(Ljava/lang/String;)Z");
    g_external = env->GetStaticMethodID(g_cls, "macapkExternal", "(Ljava/lang/String;Z)Ljava/lang/String;");
    g_event = env->GetStaticMethodID(g_cls, "macapkTabEvent", "(II)V");
    g_closed = env->GetStaticMethodID(g_cls, "macapkTabClosed", "(IZ)V");
    g_failed = env->GetStaticMethodID(g_cls, "macapkTabFailed", "(ILjava/lang/String;)V");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

jboolean JNICALL Tab_nativeAvailable(JNIEnv*, jclass) { return wv2::available() ? JNI_TRUE : JNI_FALSE; }

// Opens a tab for `url` with the app's WebView2 user data in `folder`;
// `initial_claimed`: the app itself handles `url`. Returns the tab's id, or 0
// when no tab can be shown (the caller then opens the URL as before).
jint JNICALL Tab_nativeOpen(JNIEnv* env, jclass cls, jstring url, jstring folder, jboolean initial_claimed) {
    if (!wv2::available() || !url || !folder) return 0;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    wv2::set_vm(vm);
    resolve_upcalls(env, cls);
    prepare_host_url_queue(env);
    auto* t = new Tab();
    t->id = g_next_id.fetch_add(1);
    t->initial_url = wv2::from_jstring(env, url);
    t->folder = wv2::from_jstring(env, folder);
    t->initial_claimed = initial_claimed == JNI_TRUE;
    const int id = t->id;
    if (!wv2::post([t] { open_on_thread(t); })) {
        delete t;
        return 0;
    }
    return id;
}

constexpr const char* kCls = "com.macapk.loader.HostBrowserTab";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeAvailable", "()Z", Tab_nativeAvailable),
    WINAPK_HOST_NATIVE(kCls, "nativeOpen", "(Ljava/lang/String;Ljava/lang/String;Z)I", Tab_nativeOpen),
};

// HostBrowserTab.macapkUserDataFolderPath: the app's WebView2 user data.
std::wstring app_user_data_folder(JNIEnv* env) {
    jclass tab = env->FindClass("com/macapk/loader/HostBrowserTab");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID m = tab ? env->GetStaticMethodID(tab, "macapkUserDataFolderPath", "()Ljava/lang/String;") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    std::wstring folder;
    if (m) {
        auto s = static_cast<jstring>(env->CallStaticObjectMethod(tab, m));
        if (env->ExceptionCheck()) env->ExceptionClear();
        folder = wv2::from_jstring(env, s);
        if (s) env->DeleteLocalRef(s);
    }
    if (tab) env->DeleteLocalRef(tab);
    return folder;
}

}  // namespace

bool open_applink_window(JNIEnv* env, const std::wstring& url, const std::wstring& return_prefix) {
    if (url.empty() || return_prefix.empty() || !wv2::available()) return false;
    const std::wstring scheme = wv2::scheme_of(url);
    if (scheme != L"https" && scheme != L"http") return false;
    const std::wstring folder = app_user_data_folder(env);
    if (folder.empty()) return false;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    wv2::set_vm(vm);
    prepare_host_url_queue(env);
    auto* t = new Tab();
    t->id = g_next_id.fetch_add(1);
    t->initial_url = url;
    t->folder = folder;
    t->return_prefix = return_prefix;
    if (!wv2::post([t] { open_on_thread(t); })) {
        delete t;
        return false;
    }
    return true;
}

HostMethods native_browser_tab_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
