// native_signin.cpp -- com.macapk.gms.NativeSignIn on Windows: the Google
// sign-in window, upstream's runtime/src/gms_signin.mm on WebView2.
//
// The Java GMS layer decides that an account is needed (a licence check, an
// app's Google sign-in chooser's "Add an account", AccountManager.addAccount,
// the launcher's Settings) and asks the host to put Google's own sign-in page
// in front of the user. Google has no password API, and should not: the
// supported path for a device adding an account is to render
// accounts.google.com/EmbeddedSetup and take the `oauth_token` cookie the
// completed flow deposits. So this file shows that page, waits, reads one
// cookie, and hands it back; GoogleAuthClient exchanges it for a master token.
// Win.apk never sees the password.
//
// What makes Google serve the embedded flow is the User-Agent: an Android
// WebView's with ` MinuteMaid` appended, the suffix a device-setup WebView
// sends. Without it Google serves the desktop sign-in, which never sets
// `oauth_token`. The UA names Win.apk in its Build token rather than a
// handset: it is a statement about the engine, and the device fields are
// our own (the Mac does the same with Mac.apk).
//
// How the flow's end is seen, as upstream: three triggers converging on one
// check of the cookie jar. Every completed navigation; every source change
// (the `#close` fragment Google appends at the end is a same-document
// navigation, which fires no NavigationCompleted, only SourceChanged); and a
// 1-second timer while the window is open.
//
// The page runs in an in-private profile of the app's WebView2 environment
// (WebView2's counterpart of a non-persistent WKWebsiteDataStore): the Google
// session lives only as long as the window, is not written to disk and is not
// the app's own WebView jar. The window closes once the token is in the jar,
// so nothing is left for the next sign-in to find.
//
// Before the window closes, while its session still exists, the page is asked
// for the account's picture (ask_photo): the launcher's account button shows
// it, as a browser's profile button does. Best effort; without one the button
// keeps the account's initial.
//
// A window that ends without a token says why, as a code (fail(), read by
// NativeSignIn.lastWindowError through signInWindowError): the WebView2
// HRESULT, the page's web error status (COREWEBVIEW2_WEB_ERROR_STATUS) and
// HTTP status, the timeout. The user closing the window over a page that
// loaded is a cancel and has none; closing it over a page that failed to load
// reports that failure, as it is why they gave up. `--gms signin-window`
// prints it as its ERROR line, which the launcher shows.
//
// presentSignInWindow BLOCKS for as long as a human takes, which is what the
// Java callers expect (they run it off the main thread; NativeSignIn's own
// guards keep it from the UI thread and from stacking windows). The window
// lives on the WebView2 thread (webview2_host.h), which pumps it; the calling
// Java thread waits on an event. Without WebView2 (headless, no runtime) there
// is no window: the call answers null, which the callers treat as a cancel,
// the state a Mac headless run is in.

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cwchar>
#include <mutex>
#include <string>

#include <windows.h>
#include <shellapi.h>

#include "host_natives.h"
#include "webview2_host.h"
#include "win32_window.h"

namespace winapk::host {

namespace {

using wv2::ComPtr;

constexpr wchar_t kClass[] = L"WinApkGoogleSignIn";
constexpr wchar_t kProfile[] = L"signin";
constexpr wchar_t kUrl[] = L"https://accounts.google.com/EmbeddedSetup";
constexpr wchar_t kCookieOrigin[] = L"https://accounts.google.com";
constexpr wchar_t kCookieName[] = L"oauth_token";
// The Android WebView UA plus the MinuteMaid suffix (see the header).
constexpr wchar_t kUserAgent[] =
    L"Mozilla/5.0 (Linux; Android 10; Win.apk Build/PR1; wv) "
    L"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
    L"Chrome/120.0.0.0 Mobile Safari/537.36 MinuteMaid";
constexpr UINT_PTR kPollTimer = 1;
constexpr UINT kPollMs = 1000;
// A hard ceiling so a forgotten window cannot park a guest thread forever:
// long enough for a password plus a 2FA prompt on a phone.
constexpr UINT_PTR kTimeoutTimer = 2;
constexpr UINT kTimeoutMs = 600 * 1000;
// How long the picture may hold up the end of the flow.
constexpr UINT_PTR kPhotoTimer = 3;
constexpr UINT kPhotoMs = 5000;

// The account's picture, the way Chrome learns the accounts in its cookie jar:
// accounts.google.com/ListAccounts answers the session's accounts, each with
// its name, email and picture URL. Run in the page, so the in-private
// session's cookies go with it (same origin), and synchronous, so the
// script's result is the answer. Any failure is an empty string.
constexpr wchar_t kListAccountsScript[] =
    L"(function(){try{var x=new XMLHttpRequest();"
    L"x.open('GET','https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard',false);"
    L"x.send();return x.status==200?x.responseText:'';}catch(e){return '';}})()";

std::mutex g_photo_lock;
std::wstring g_photo;  // the last window's picture URL, for signInPhotoUrl
std::wstring g_error;  // why the last window ended without a token, for signInWindowError

std::atomic<bool> g_active{false};

// One window at a time (g_active), so one of these at a time. WebView2 thread
// only, except `done`, `token` and `finished`, written before `done` is set.
struct Window {
    HWND hwnd = nullptr;
    UINT dpi = 96;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> view;
    ComPtr<ICoreWebView2CookieManager> cookies;
    std::wstring folder;
    bool finished = false;
    bool checking = false;  // a GetCookies is in flight
    bool asking_photo = false;  // the token is in; ListAccounts is in flight
    std::wstring token;
    std::wstring photo;
    std::wstring error;      // why it ended without a token (fail), else empty
    std::wstring nav_error;  // the page on show failed to load: its codes
    HANDLE done = nullptr;  // signalled when the window is gone
};

Window* g_window = nullptr;

UINT dpi_of(HWND hwnd) {
    using Fn = UINT(WINAPI*)(HWND);
    static auto fn = reinterpret_cast<Fn>(
        reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow")));
    const UINT d = fn && hwnd ? fn(hwnd) : 96;
    return d ? d : 96;
}

void layout(Window* w) {
    RECT c;
    GetClientRect(w->hwnd, &c);
    if (w->controller) w->controller->put_Bounds(c);
}

std::wstring hex_hr(HRESULT hr) {
    wchar_t b[16];
    std::swprintf(b, 16, L"0x%08lX", static_cast<unsigned long>(hr));
    return b;
}

// COREWEBVIEW2_WEB_ERROR_STATUS's names, for the code the user sees.
const wchar_t* web_error_name(COREWEBVIEW2_WEB_ERROR_STATUS s) {
    static const wchar_t* const kNames[] = {
        L"UNKNOWN", L"CERTIFICATE_COMMON_NAME_IS_INCORRECT", L"CERTIFICATE_EXPIRED",
        L"CLIENT_CERTIFICATE_CONTAINS_ERRORS", L"CERTIFICATE_REVOKED", L"CERTIFICATE_IS_INVALID",
        L"SERVER_UNREACHABLE", L"TIMEOUT", L"ERROR_HTTP_INVALID_SERVER_RESPONSE", L"CONNECTION_ABORTED",
        L"CONNECTION_RESET", L"DISCONNECTED", L"CANNOT_CONNECT", L"HOST_NAME_NOT_RESOLVED", L"OPERATION_CANCELED",
        L"REDIRECT_FAILED", L"UNEXPECTED_ERROR", L"VALID_AUTHENTICATION_CREDENTIALS_REQUIRED",
        L"VALID_PROXY_AUTHENTICATION_REQUIRED"};
    const int i = static_cast<int>(s);
    return i >= 0 && i < static_cast<int>(sizeof(kNames) / sizeof(kNames[0])) ? kNames[i] : L"UNKNOWN";
}

// The window is done: the token is in `token` or stays empty (cancelled,
// timed out, failed). Closing it releases the waiting Java thread (WM_DESTROY).
void finish(Window* w) {
    if (w->finished) return;
    w->finished = true;
    KillTimer(w->hwnd, kPollTimer);
    KillTimer(w->hwnd, kTimeoutTimer);
    KillTimer(w->hwnd, kPhotoTimer);
    PostMessageW(w->hwnd, WM_CLOSE, 0, 0);
}

// The window ends without a token, for `why` (a code; see the header).
void fail(Window* w, const std::wstring& why) {
    if (w->finished) return;
    if (w->error.empty()) w->error = why;
    std::fprintf(stderr, "[win.apk] gms-signin: failed: %s\n", wv2::narrow(why).c_str());
    std::fflush(stderr);
    finish(w);
}

// One level of a JSON string literal, quotes included: `"a\"b"` -> `a"b`.
std::wstring json_unquote(const std::wstring& q) {
    std::wstring out;
    if (q.size() < 2 || q.front() != L'"' || q.back() != L'"') return out;
    for (size_t i = 1; i + 1 < q.size(); ++i) {
        wchar_t c = q[i];
        if (c != L'\\' || i + 2 >= q.size()) {
            out += c;
            continue;
        }
        c = q[++i];
        switch (c) {
            case L'n': out += L'\n'; break;
            case L't': out += L'\t'; break;
            case L'r': out += L'\r'; break;
            case L'b': out += L'\b'; break;
            case L'f': out += L'\f'; break;
            case L'u':
                if (i + 4 < q.size()) {
                    out += static_cast<wchar_t>(std::wcstoul(q.substr(i + 1, 4).c_str(), nullptr, 16));
                    i += 4;
                }
                break;
            default: out += c; break;  // \" \\ \/
        }
    }
    return out;
}

// The first picture URL in ListAccounts' answer (the in-private session holds
// the one account just signed in), or empty. Google's pictures are served from
// lh<n>.googleusercontent.com; nothing else is taken.
std::wstring photo_url_in(const std::wstring& body) {
    const size_t host = body.find(L".googleusercontent.com");  // its "/" may be written "\/"
    if (host == std::wstring::npos) return L"";
    const size_t open = body.rfind(L'"', host);
    size_t close = host;
    while ((close = body.find(L'"', close + 1)) != std::wstring::npos && body[close - 1] == L'\\') {
    }
    if (open == std::wstring::npos || close == std::wstring::npos) return L"";
    const std::wstring url = json_unquote(body.substr(open, close - open + 1));
    if (url.rfind(L"https://lh", 0) != 0 || url.size() > 2048) return L"";
    const size_t slash = url.find(L'/', 8);
    if (slash == std::wstring::npos || slash < 30 || url.compare(slash - 22, 22, L".googleusercontent.com") != 0) {
        return L"";
    }
    for (const wchar_t c : url) {
        if (c <= L' ' || c == L'"' || c == L'<' || c == L'>' || c == L'\\') return L"";
    }
    return url;
}

// The token is in: ask the page for the account's picture, then finish. The
// timer finishes without one if the answer is slow.
void ask_photo(Window* w) {
    if (w->finished || w->asking_photo) return;
    w->asking_photo = true;
    KillTimer(w->hwnd, kPollTimer);
    SetTimer(w->hwnd, kPhotoTimer, kPhotoMs, nullptr);
    auto done = wv2::handler<ICoreWebView2ExecuteScriptCompletedHandler>(
        IID_ICoreWebView2ExecuteScriptCompletedHandler, [](HRESULT hr, LPCWSTR json) -> HRESULT {
            Window* w = g_window;
            if (!w || w->finished) return S_OK;
            // The script's result is JSON: the answer's text as a string literal.
            if (SUCCEEDED(hr) && json) w->photo = photo_url_in(json_unquote(json));
            std::fprintf(stderr, "[win.apk] gms-signin: the account's picture: %s\n",
                         w->photo.empty() ? "none" : "found");
            std::fflush(stderr);
            finish(w);
            return S_OK;
        });
    if (!w->view || FAILED(w->view->ExecuteScript(kListAccountsScript, done.get()))) finish(w);
}

// The whole detection mechanism: does the jar hold `oauth_token` yet?
void check_cookies(Window* w) {
    if (w->finished || w->checking || w->asking_photo || !w->cookies) return;
    w->checking = true;
    auto done = wv2::handler<ICoreWebView2GetCookiesCompletedHandler>(
        IID_ICoreWebView2GetCookiesCompletedHandler, [](HRESULT hr, ICoreWebView2CookieList* list) -> HRESULT {
            Window* w = g_window;
            if (!w) return S_OK;
            w->checking = false;
            if (w->finished || FAILED(hr) || !list) return S_OK;
            UINT count = 0;
            list->get_Count(&count);
            for (UINT i = 0; i < count && w->token.empty(); ++i) {
                ComPtr<ICoreWebView2Cookie> c;
                if (FAILED(list->GetValueAtIndex(i, c.put())) || !c) continue;
                LPWSTR raw = nullptr;
                c->get_Name(&raw);
                if (wv2::take_co_string(raw) != kCookieName) continue;
                c->get_Value(&raw);
                w->token = wv2::take_co_string(raw);
            }
            if (!w->token.empty() && !w->asking_photo) {
                std::fprintf(stderr, "[win.apk] gms-signin: the flow finished (oauth_token, %zu chars)\n",
                             w->token.size());
                std::fflush(stderr);
                ask_photo(w);
            }
            return S_OK;
        });
    if (FAILED(w->cookies->GetCookies(kCookieOrigin, done.get()))) w->checking = false;
}

void hook_events(Window* w) {
    EventRegistrationToken token;
    w->view->add_NavigationCompleted(
        wv2::handler<ICoreWebView2NavigationCompletedEventHandler>(
            IID_ICoreWebView2NavigationCompletedEventHandler,
            [](ICoreWebView2* sender, ICoreWebView2NavigationCompletedEventArgs* a) -> HRESULT {
                Window* w = g_window;
                if (!w) return S_OK;
                BOOL ok = FALSE;
                a->get_IsSuccess(&ok);
                LPWSTR raw = nullptr;
                sender->get_Source(&raw);
                const std::wstring src = wv2::take_co_string(raw);
                int http = 0;
                if (auto a2 = ComPtr<ICoreWebView2NavigationCompletedEventArgs>::retain(a)
                                  .query<ICoreWebView2NavigationCompletedEventArgs2>(
                                      IID_ICoreWebView2NavigationCompletedEventArgs2)) {
                    a2->get_HttpStatusCode(&http);
                }
                if (ok) {
                    std::fprintf(stderr, "[win.apk] gms-signin: loaded %s (HTTP %d)\n", wv2::redact(src).c_str(), http);
                    // Google's error pages load too: their status is the code.
                    w->nav_error = http >= 400 ? L"HTTP " + std::to_wstring(http) : std::wstring();
                } else {
                    // Not necessarily the end: the flow redirects through
                    // schemes the view refuses, and the token may be in the jar.
                    COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                    a->get_WebErrorStatus(&status);
                    std::fprintf(stderr, "[win.apk] gms-signin: navigation failed (%d %s, HTTP %d) at %s\n",
                                 static_cast<int>(status), wv2::narrow(web_error_name(status)).c_str(), http,
                                 wv2::redact(src).c_str());
                    if (status != COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED) {
                        w->nav_error = std::wstring(L"WEB_ERROR ") + web_error_name(status) + L" (" +
                                       std::to_wstring(static_cast<int>(status)) + L")" +
                                       (http > 0 ? L", HTTP " + std::to_wstring(http) : std::wstring());
                    }
                }
                std::fflush(stderr);
                check_cookies(w);
                return S_OK;
            })
            .get(),
        &token);
    // The `#close` fragment at the end of the flow is a same-document
    // navigation: this is the only event it raises.
    w->view->add_SourceChanged(
        wv2::handler<ICoreWebView2SourceChangedEventHandler>(
            IID_ICoreWebView2SourceChangedEventHandler,
            [](ICoreWebView2*, ICoreWebView2SourceChangedEventArgs*) -> HRESULT {
                if (Window* w = g_window) check_cookies(w);
                return S_OK;
            })
            .get(),
        &token);
    // The flow stays in this window: a pop-up (a help link) loads here too.
    w->view->add_NewWindowRequested(
        wv2::handler<ICoreWebView2NewWindowRequestedEventHandler>(
            IID_ICoreWebView2NewWindowRequestedEventHandler,
            [](ICoreWebView2* sender, ICoreWebView2NewWindowRequestedEventArgs* a) -> HRESULT {
                a->put_Handled(TRUE);
                LPWSTR raw = nullptr;
                a->get_Uri(&raw);
                const std::wstring uri = wv2::take_co_string(raw);
                const std::wstring scheme = wv2::scheme_of(uri);
                if (scheme == L"http" || scheme == L"https") sender->Navigate(uri.c_str());
                return S_OK;
            })
            .get(),
        &token);
    w->view->add_WindowCloseRequested(
        wv2::handler<ICoreWebView2WindowCloseRequestedEventHandler>(
            IID_ICoreWebView2WindowCloseRequestedEventHandler, [](ICoreWebView2*, IUnknown*) -> HRESULT {
                if (Window* w = g_window) finish(w);
                return S_OK;
            })
            .get(),
        &token);
    // The flow ends in custom-scheme redirects WebView2 would otherwise ask
    // Windows to open: never.
    if (auto v18 = w->view.query<ICoreWebView2_18>(IID_ICoreWebView2_18)) {
        v18->add_LaunchingExternalUriScheme(
            wv2::handler<ICoreWebView2LaunchingExternalUriSchemeEventHandler>(
                IID_ICoreWebView2LaunchingExternalUriSchemeEventHandler,
                [](ICoreWebView2*, ICoreWebView2LaunchingExternalUriSchemeEventArgs* a) -> HRESULT {
                    a->put_Cancel(TRUE);
                    if (Window* w = g_window) check_cookies(w);
                    return S_OK;
                })
                .get(),
            &token);
    }
    w->view->add_ProcessFailed(
        wv2::handler<ICoreWebView2ProcessFailedEventHandler>(
            IID_ICoreWebView2ProcessFailedEventHandler,
            [](ICoreWebView2* sender, ICoreWebView2ProcessFailedEventArgs* a) -> HRESULT {
                Window* w = g_window;
                if (!w) return S_OK;
                COREWEBVIEW2_PROCESS_FAILED_KIND kind = COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED;
                a->get_ProcessFailedKind(&kind);
                std::fprintf(stderr, "[win.apk] gms-signin: WebView2 process failed (kind %d)\n",
                             static_cast<int>(kind));
                if (kind == COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED) {
                    fail(w, L"WEBVIEW2_PROCESS_FAILED (kind " + std::to_wstring(static_cast<int>(kind)) + L")");
                }
                else if (kind == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED ||
                         kind == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE)
                    sender->Reload();
                return S_OK;
            })
            .get(),
        &token);
}

void controller_ready(HRESULT hr, ICoreWebView2Controller* controller) {
    Window* w = g_window;
    if (!w || w->finished) {
        if (controller) controller->Close();
        return;
    }
    if (FAILED(hr) || !controller) {
        fail(w, L"WEBVIEW2_CONTROLLER " + hex_hr(FAILED(hr) ? hr : E_FAIL));
        return;
    }
    w->controller = ComPtr<ICoreWebView2Controller>::retain(controller);
    controller->get_CoreWebView2(w->view.put());
    if (!w->view) {
        fail(w, L"WEBVIEW2_NO_VIEW");
        return;
    }
    ComPtr<ICoreWebView2Settings> settings;
    w->view->get_Settings(settings.put());
    if (settings) {
        settings->put_AreDevToolsEnabled(FALSE);
        settings->put_IsStatusBarEnabled(FALSE);
        settings->put_AreDefaultContextMenusEnabled(FALSE);
        if (auto s2 = settings.query<ICoreWebView2Settings2>(IID_ICoreWebView2Settings2)) {
            s2->put_UserAgent(kUserAgent);
        }
    }
    if (auto v2 = w->view.query<ICoreWebView2_2>(IID_ICoreWebView2_2)) v2->get_CookieManager(w->cookies.put());
    if (!w->cookies) {
        fail(w, L"WEBVIEW2_NO_COOKIE_MANAGER");
        return;
    }
    hook_events(w);
    layout(w);
    controller->put_IsVisible(TRUE);
    if (GetForegroundWindow() == w->hwnd) controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
    // Google's page in English (hl), as Win.apk's own screens are.
    const std::wstring url = std::wstring(kUrl) + L"?hl=en";
    std::fprintf(stderr, "[win.apk] gms-signin: window up, loading %s\n", wv2::narrow(url.c_str()).c_str());
    std::fflush(stderr);
    w->view->Navigate(url.c_str());
    SetTimer(w->hwnd, kPollTimer, kPollMs, nullptr);
}

void create_controller(ICoreWebView2Environment* environment) {
    Window* w = g_window;
    if (!w || w->finished) return;
    if (!environment) {
        fail(w, L"WEBVIEW2_ENVIRONMENT " + hex_hr(wv2::environment_error()));
        return;
    }
    auto done = wv2::handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
        IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
        [](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
            controller_ready(hr, c);
            return S_OK;
        });
    // An in-private profile of its own (WebView2 1.0.1774+): nothing of the
    // Google session is kept. An older runtime has only the default profile;
    // the session would then be the app's WebViews' too, so no sign-in there.
    auto env10 = ComPtr<ICoreWebView2Environment>::retain(environment)
                     .query<ICoreWebView2Environment10>(IID_ICoreWebView2Environment10);
    ComPtr<ICoreWebView2ControllerOptions> options;
    if (env10 && SUCCEEDED(env10->CreateCoreWebView2ControllerOptions(options.put())) && options) {
        options->put_ProfileName(kProfile);
        options->put_IsInPrivateModeEnabled(TRUE);
        const HRESULT hr = env10->CreateCoreWebView2ControllerWithOptions(w->hwnd, options.get(), done.get());
        if (SUCCEEDED(hr)) return;
        controller_ready(hr, nullptr);
        return;
    }
    std::fprintf(stderr, "[win.apk] gms-signin: this WebView2 Runtime (%s) has no private profiles; update it\n",
                 wv2::narrow(wv2::runtime_version()).c_str());
    fail(w, L"WEBVIEW2_RUNTIME_TOO_OLD (" + wv2::runtime_version() + L")");
}

LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* w = reinterpret_cast<Window*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_NCCREATE: {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
            break;
        }
        case WM_CREATE: {
            w->hwnd = hwnd;
            w->dpi = dpi_of(hwnd);
            layout(w);
            SetTimer(hwnd, kTimeoutTimer, kTimeoutMs, nullptr);
            return 0;
        }
        case WM_SIZE:
            if (w) layout(w);
            return 0;
        case WM_DPICHANGED: {
            w->dpi = HIWORD(wp);
            const RECT* r = reinterpret_cast<const RECT*>(lp);
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
            layout(w);
            return 0;
        }
        case WM_TIMER:
            if (w && wp == kPollTimer) {
                check_cookies(w);
            } else if (w && wp == kTimeoutTimer) {
                fail(w, L"TIMEOUT (" + std::to_wstring(kTimeoutMs / 1000) + L" s)");
            } else if (w && wp == kPhotoTimer) {
                std::fprintf(stderr, "[win.apk] gms-signin: no answer about the account's picture\n");
                finish(w);
            }
            return 0;
        case WM_ERASEBKGND: {
            RECT c;
            GetClientRect(hwnd, &c);
            FillRect(reinterpret_cast<HDC>(wp), &c, GetSysColorBrush(COLOR_WINDOW));
            return 1;
        }
        case WM_SETFOCUS:
            if (w && w->controller) w->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
            return 0;
        case WM_CLOSE:
            // The user closed the window: a cancel, which resolves the wait;
            // over a page that did not load, that failure is the answer.
            if (w && !w->finished && w->token.empty() && w->error.empty() && !w->nav_error.empty()) {
                w->error = w->nav_error;
                std::fprintf(stderr, "[win.apk] gms-signin: closed over a failed page: %s\n",
                             wv2::narrow(w->error).c_str());
            }
            if (w) w->finished = true;
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (w) {
                KillTimer(hwnd, kPollTimer);
                KillTimer(hwnd, kTimeoutTimer);
                KillTimer(hwnd, kPhotoTimer);
                if (w->controller) w->controller->Close();
                w->view.reset();
                w->cookies.reset();
                w->controller.reset();
                g_window = nullptr;
                SetEvent(w->done);
            }
            return 0;
        case WM_NCDESTROY:
            if (w) SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
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
    wc.lpfnWndProc = wnd_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));  // IDC_ARROW
    wc.hIcon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));      // IDI_APPLICATION
    wc.lpszClassName = kClass;
    RegisterClassExW(&wc);
}

// The app window's icons for the title bar and taskbar, when there is an app
// (a bounded ask: that window's thread may be busy in Java).
// Without an app window (Settings > Google account, the Play Store's Sign in
// with the app embedded or not yet up) the window had the generic Windows
// icon; it then takes Win.apk's own, from Win.apk.exe beside bin\.
void copy_app_icon(HWND hwnd) {
    bool set = false;
    if (HWND app = static_cast<HWND>(window_handle())) {
        for (WPARAM which : {static_cast<WPARAM>(ICON_BIG), static_cast<WPARAM>(ICON_SMALL)}) {
            DWORD_PTR icon = 0;
            if (SendMessageTimeoutW(app, WM_GETICON, which, 0, SMTO_ABORTIFHUNG | SMTO_BLOCK, 300, &icon) && icon) {
                SendMessageW(hwnd, WM_SETICON, which, static_cast<LPARAM>(icon));
                set = true;
            }
        }
    }
    if (set) return;
    wchar_t exe[MAX_PATH];
    const DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return;
    std::wstring dir(exe, n);
    dir.resize(dir.find_last_of(L"\\/") == std::wstring::npos ? 0 : dir.find_last_of(L"\\/"));  // ...\bin
    const size_t up = dir.find_last_of(L"\\/");
    for (const std::wstring& path : {(up == std::wstring::npos ? dir : dir.substr(0, up)) + L"\\Win.apk.exe",
                                     dir + L"\\Win.apk.exe"}) {
        HICON big = nullptr, small = nullptr;
        if (ExtractIconExW(path.c_str(), 0, &big, &small, 1) > 0 && (big || small)) {
            if (big) SendMessageW(hwnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(big));
            if (small) SendMessageW(hwnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(small));
            return;
        }
    }
}

// The DPI of the monitor the window will open on. dpi_of(nullptr) is 96 on
// every screen, so on a 150% display the window opened at two thirds of its
// size and Google's page, wider than what was left, was cut at the sides.
UINT dpi_at(POINT pt) {
    using Fn = HRESULT(WINAPI*)(HMONITOR, int, UINT*, UINT*);
    static HMODULE shcore = LoadLibraryW(L"shcore.dll");
    static auto fn = shcore ? reinterpret_cast<Fn>(reinterpret_cast<void*>(GetProcAddress(shcore, "GetDpiForMonitor")))
                            : nullptr;
    UINT x = 96, y = 96;
    if (fn && SUCCEEDED(fn(MonitorFromPoint(pt, MONITOR_DEFAULTTOPRIMARY), 0 /* MDT_EFFECTIVE_DPI */, &x, &y)) && x) {
        return x;
    }
    return 96;
}

void open_on_thread(Window* w) {
    register_class();
    g_window = w;
    // Portrait, the flow is phone-shaped; centred on the app's window when
    // there is one, else on the primary monitor.
    // Per-monitor DPI for this window even in a process that never made a
    // window of its own (`--gms signin-window`), so it is sharp and its size
    // is in real pixels of the screen it opens on.
    using SetCtx = HANDLE(WINAPI*)(HANDLE);
    if (auto set_ctx = reinterpret_cast<SetCtx>(reinterpret_cast<void*>(
            GetProcAddress(GetModuleHandleW(L"user32.dll"), "SetThreadDpiAwarenessContext")))) {
        set_ctx(reinterpret_cast<HANDLE>(-4));  // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
    }
    RECT r{};
    HWND app = static_cast<HWND>(window_handle());
    const bool over_app = app && !IsIconic(app) && GetWindowRect(app, &r) && r.right - r.left > 100;
    POINT centre{0, 0};
    if (over_app) centre = {(r.left + r.right) / 2, (r.top + r.bottom) / 2};
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromPoint(centre, MONITOR_DEFAULTTOPRIMARY), &mi);
    const RECT& work = mi.rcWork;
    if (!over_app) centre = {(work.left + work.right) / 2, (work.top + work.bottom) / 2};
    // 520 x 800 DIPs: Google's sign-in pages lay out for at least ~450 px of
    // width plus their margins, and the 2-step pages are tall; never more
    // than the screen's work area.
    const UINT dpi = dpi_at(centre);
    const int width = std::min<int>(MulDiv(520, static_cast<int>(dpi), 96), (work.right - work.left) - 16);
    const int height = std::min<int>(MulDiv(800, static_cast<int>(dpi), 96), (work.bottom - work.top) - 16);
    int x = centre.x - width / 2, y = centre.y - height / 2;
    x = std::max<int>(work.left + 8, std::min<int>(x, work.right - width - 8));
    y = std::max<int>(work.top + 8, std::min<int>(y, work.bottom - height - 8));
    HWND hwnd = CreateWindowExW(0, kClass, L"Sign in to Google", WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN, x, y, width,
                                height, nullptr, nullptr, GetModuleHandleW(nullptr), w);
    if (!hwnd) {
        const DWORD e = GetLastError();
        std::fprintf(stderr, "[win.apk] gms-signin: no window (error %lu)\n", e);
        w->error = L"NO_WINDOW (error " + std::to_wstring(e) + L")";
        g_window = nullptr;
        SetEvent(w->done);
        return;
    }
    copy_app_icon(hwnd);
    // The user asked for this (an account chooser's button, a Settings
    // button, a licence check they are watching): in front, and focused, so
    // they can type into it.
    ShowWindow(hwnd, SW_SHOWNORMAL);
    SetForegroundWindow(hwnd);
    wv2::with_environment(w->folder, [](ICoreWebView2Environment* e) { create_controller(e); });
}

// The WebView2 user-data folder: the app's own (HostBrowserTab's
// app_webview2 in its data directory, the one environment this process has)
// when an app runs, else a folder under the GMS state directory, for a
// `--gms signin-window` run with no app. The in-private profile writes nothing
// of the session there either way.
std::wstring user_data_folder(JNIEnv* env) {
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
    if (!folder.empty()) return folder;
    wchar_t buf[32768];
    const DWORD n = GetEnvironmentVariableW(L"MACAPK_GMS_DIR", buf, 32768);
    if (n > 0 && n < 32768) return std::wstring(buf, n) + L"\\webview2";
    const DWORD m2 = GetEnvironmentVariableW(L"LOCALAPPDATA", buf, 32768);
    if (m2 > 0 && m2 < 32768) return std::wstring(buf, m2) + L"\\Win.apk\\gms\\webview2";
    return std::wstring();
}

// ---- JNI ----------------------------------------------------------------------

void set_error(const std::wstring& e) {
    std::lock_guard<std::mutex> lock(g_photo_lock);
    g_error = e;
}

jstring JNICALL SignIn_present(JNIEnv* env, jclass) {
    if (!wv2::available()) {
        const std::string why = wv2::unavailable_reason();
        std::fprintf(stderr, "[win.apk] gms-signin: no WebView2 (%s), so no sign-in window\n", why.c_str());
        std::fflush(stderr);
        set_error(std::wstring(why.begin(), why.end()));
        return nullptr;
    }
    // One window at a time: a second licence check arriving while the user is
    // typing must not stack a second window on the first.
    bool expected = false;
    if (!g_active.compare_exchange_strong(expected, true)) {
        std::fprintf(stderr, "[win.apk] gms-signin: a sign-in window is already open\n");
        return nullptr;
    }
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    wv2::set_vm(vm);
    set_error(L"");
    auto* w = new Window();
    w->folder = user_data_folder(env);
    w->done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::wstring token, photo, error;
    if (!w->done) {
        error = L"NO_EVENT (error " + std::to_wstring(GetLastError()) + L")";
    } else if (w->folder.empty()) {
        error = L"NO_USER_DATA_FOLDER";
    } else if (!wv2::post([w] { open_on_thread(w); })) {
        error = L"WEBVIEW2_THREAD";
    } else {
        WaitForSingleObject(w->done, INFINITE);
        token = w->token;
        photo = w->photo;
        error = w->error;
    }
    {
        std::lock_guard<std::mutex> lock(g_photo_lock);
        g_photo = token.empty() ? std::wstring() : photo;
        g_error = token.empty() ? error : std::wstring();
    }
    if (w->done) CloseHandle(w->done);
    delete w;
    g_active.store(false);
    if (token.empty()) {
        std::fprintf(stderr, "[win.apk] gms-signin: no token (%s)\n",
                     error.empty() ? "cancelled" : wv2::narrow(error).c_str());
        std::fflush(stderr);
        return nullptr;  // never an empty string: the Java side reads "" as a token
    }
    return wv2::to_jstring(env, token);
}

jboolean JNICALL SignIn_active(JNIEnv*, jclass) { return g_active.load() ? JNI_TRUE : JNI_FALSE; }

// The picture URL the last window found for its account, or null.
jstring JNICALL SignIn_photo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_photo_lock);
    return g_photo.empty() ? nullptr : wv2::to_jstring(env, g_photo);
}

// Why the last window ended without a token (a code), or null: a cancel.
jstring JNICALL SignIn_error(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_photo_lock);
    return g_error.empty() ? nullptr : wv2::to_jstring(env, g_error);
}

constexpr const char* kCls = "com.macapk.gms.NativeSignIn";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "presentSignInWindow", "()Ljava/lang/String;", SignIn_present),
    WINAPK_HOST_NATIVE(kCls, "signInWindowActive", "()Z", SignIn_active),
    WINAPK_HOST_NATIVE(kCls, "signInPhotoUrl", "()Ljava/lang/String;", SignIn_photo),
    WINAPK_HOST_NATIVE(kCls, "signInWindowError", "()Ljava/lang/String;", SignIn_error),
};

}  // namespace

HostMethods native_signin_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
