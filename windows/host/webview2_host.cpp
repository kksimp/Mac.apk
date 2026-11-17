// webview2_host.cpp -- see webview2_host.h.

#include "webview2_host.h"

#include <cstdio>
#include <cstdlib>
#include <cwctype>
#include <deque>
#include <mutex>
#include <vector>

#include <shlobj.h>

namespace winapk::host::wv2 {

namespace {

using CreateEnvironmentFn = HRESULT(STDAPICALLTYPE*)(PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions*,
                                                     ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*);
using GetVersionFn = HRESULT(STDAPICALLTYPE*)(PCWSTR, LPWSTR*);

CreateEnvironmentFn g_create_environment = nullptr;
std::wstring g_version;

bool env_flag_off(const char* name) {
    const char* e = std::getenv(name);
    return e && e[0] == '0' && e[1] == '\0';
}

bool headless() {
    const char* e = std::getenv("WINAPK_HEADLESS");
    return e && *e && *e != '0';
}

// WebView2Loader.dll from the runtime's own folder only: a loader found on the
// DLL search path could be anything.
HMODULE load_loader() {
    wchar_t exe[MAX_PATH * 4];
    const DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH * 4);
    if (n == 0 || n >= MAX_PATH * 4) return nullptr;
    std::wstring dir(exe, n);
    const size_t slash = dir.find_last_of(L"\\/");
    if (slash == std::wstring::npos) return nullptr;
    dir.resize(slash + 1);
    return LoadLibraryExW((dir + L"WebView2Loader.dll").c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
}

// Why available() is false, as a code (see webview2_host.h).
std::string g_unavailable;

std::string hex_hr(HRESULT hr) {
    char b[16];
    std::snprintf(b, sizeof b, "0x%08lx", static_cast<unsigned long>(hr));
    return b;
}

bool decide() {
    if (env_flag_off("WINAPK_WEBVIEW2")) {
        g_unavailable = "WEBVIEW2_OFF";
        std::fprintf(stderr, "[win.apk] webview2: off (WINAPK_WEBVIEW2=0); web links open in the system browser\n");
        return false;
    }
    if (headless()) {
        g_unavailable = "HEADLESS";
        std::fprintf(stderr, "[win.apk] webview2: not used headless; web links open in the system browser\n");
        return false;
    }
    HMODULE loader = load_loader();
    if (!loader) {
        g_unavailable = "WEBVIEW2_LOADER_MISSING (error " + std::to_string(GetLastError()) + ")";
        std::fprintf(stderr,
                     "[win.apk] webview2: WebView2Loader.dll is not next to the runtime (error %lu); web links "
                     "open in the system browser\n",
                     GetLastError());
        return false;
    }
    auto get_version = reinterpret_cast<GetVersionFn>(
        reinterpret_cast<void*>(GetProcAddress(loader, "GetAvailableCoreWebView2BrowserVersionString")));
    g_create_environment = reinterpret_cast<CreateEnvironmentFn>(
        reinterpret_cast<void*>(GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions")));
    if (!get_version || !g_create_environment) {
        g_unavailable = "WEBVIEW2_LOADER_BAD";
        std::fprintf(stderr, "[win.apk] webview2: WebView2Loader.dll lacks its entry points; web links open in "
                             "the system browser\n");
        return false;
    }
    // The loader's own runtime lookup: the EdgeUpdate client key of the
    // Evergreen runtime ({F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}) under HKLM and
    // HKCU, or WEBVIEW2_BROWSER_EXECUTABLE_FOLDER for a fixed-version runtime.
    LPWSTR version = nullptr;
    const HRESULT hr = get_version(nullptr, &version);
    g_version = take_co_string(version);
    if (FAILED(hr) || g_version.empty()) {
        g_unavailable = "WEBVIEW2_RUNTIME_MISSING (" + hex_hr(hr) + ")";
        std::fprintf(stderr,
                     "[win.apk] webview2: the WebView2 Runtime is not installed (0x%08lx); web links open in the "
                     "system browser. Get it from https://go.microsoft.com/fwlink/p/?LinkId=2124703\n",
                     static_cast<unsigned long>(hr));
        g_version.clear();
        return false;
    }
    std::fprintf(stderr, "[win.apk] webview2: runtime %s; web content shows in-runtime\n", narrow(g_version).c_str());
    return true;
}

// ---- the thread ------------------------------------------------------------

constexpr UINT kMsgRun = WM_APP + 1;
constexpr wchar_t kMsgClass[] = L"WinApkWebView2Thread";

JavaVM* g_vm = nullptr;
std::mutex g_queue_lock;
std::deque<std::function<void()>> g_queue;
HWND g_msg_hwnd = nullptr;
DWORD g_thread_id = 0;
thread_local JNIEnv* t_env = nullptr;

void run_queue() {
    for (;;) {
        std::function<void()> fn;
        {
            std::lock_guard<std::mutex> hold(g_queue_lock);
            if (g_queue.empty()) return;
            fn = std::move(g_queue.front());
            g_queue.pop_front();
        }
        if (fn) fn();
    }
}

LRESULT CALLBACK msg_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == kMsgRun) {
        run_queue();
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

struct ThreadStart {
    HANDLE ready;
    bool ok;
};

DWORD WINAPI thread_main(void* param) {
    auto* start = static_cast<ThreadStart*>(param);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (g_vm) {
        JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("winapk-webview"), nullptr};
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&t_env), &args) != JNI_OK) t_env = nullptr;
    }
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = msg_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = kMsgClass;
    RegisterClassExW(&wc);
    g_msg_hwnd = CreateWindowExW(0, kMsgClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, wc.hInstance, nullptr);
    start->ok = g_msg_hwnd != nullptr;
    SetEvent(start->ready);
    if (!g_msg_hwnd) return 1;
    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}

bool ensure_thread() {
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [] {
        ThreadStart start{CreateEventW(nullptr, TRUE, FALSE, nullptr), false};
        HANDLE t = CreateThread(nullptr, 0, thread_main, &start, 0, &g_thread_id);
        if (!t) return;
        WaitForSingleObject(start.ready, INFINITE);
        CloseHandle(start.ready);
        CloseHandle(t);
        ok = start.ok;
    });
    return ok;
}

// ---- the environment --------------------------------------------------------

enum class EnvState { kNone, kCreating, kReady, kFailed };
EnvState g_env_state = EnvState::kNone;
HRESULT g_env_hr = S_OK;
ComPtr<ICoreWebView2Environment> g_environment;
std::vector<std::function<void(ICoreWebView2Environment*)>> g_env_waiters;

void env_settle(HRESULT hr, ICoreWebView2Environment* environment) {
    if (SUCCEEDED(hr) && environment) {
        g_environment = ComPtr<ICoreWebView2Environment>::retain(environment);
        g_env_state = EnvState::kReady;
    } else {
        std::fprintf(stderr, "[win.apk] webview2: the environment could not be created (0x%08lx)\n",
                     static_cast<unsigned long>(hr));
        g_env_state = EnvState::kFailed;
        g_env_hr = FAILED(hr) ? hr : E_FAIL;
    }
    auto waiters = std::move(g_env_waiters);
    g_env_waiters.clear();
    for (auto& w : waiters) w(g_environment.get());
}

}  // namespace

bool available() {
    static std::once_flag once;
    static bool yes = false;
    std::call_once(once, [] { yes = decide(); });
    return yes;
}

std::string unavailable_reason() { return available() ? std::string() : g_unavailable; }
HRESULT environment_error() { return g_env_hr; }

std::wstring runtime_version() { return available() ? g_version : std::wstring(); }

void set_vm(JavaVM* vm) {
    if (!g_vm) g_vm = vm;
}

JNIEnv* env() { return t_env; }

bool on_thread() { return g_thread_id != 0 && GetCurrentThreadId() == g_thread_id; }

bool post(std::function<void()> fn) {
    if (!ensure_thread()) return false;
    {
        std::lock_guard<std::mutex> hold(g_queue_lock);
        g_queue.push_back(std::move(fn));
    }
    PostMessageW(g_msg_hwnd, kMsgRun, 0, 0);
    return true;
}

void with_environment(const std::wstring& folder, std::function<void(ICoreWebView2Environment*)> cb) {
    switch (g_env_state) {
        case EnvState::kReady:
            cb(g_environment.get());
            return;
        case EnvState::kFailed:
            cb(nullptr);
            return;
        case EnvState::kCreating:
            g_env_waiters.push_back(std::move(cb));
            return;
        case EnvState::kNone:
            break;
    }
    g_env_waiters.push_back(std::move(cb));
    g_env_state = EnvState::kCreating;
    if (!available() || !g_create_environment || folder.empty()) {
        env_settle(E_FAIL, nullptr);
        return;
    }
    SHCreateDirectoryExW(nullptr, folder.c_str(), nullptr);
    auto done = handler<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
        IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler,
        [](HRESULT hr, ICoreWebView2Environment* environment) -> HRESULT {
            env_settle(hr, environment);
            return S_OK;
        });
    std::fprintf(stderr, "[win.apk] webview2: user data in %s\n", narrow(folder).c_str());
    const HRESULT hr = g_create_environment(nullptr, folder.c_str(), nullptr, done.get());
    if (FAILED(hr)) env_settle(hr, nullptr);
}

// ---- helpers ------------------------------------------------------------------

std::wstring from_jstring(JNIEnv* env, jstring s) {
    if (!env || !s) return std::wstring();
    const jchar* c = env->GetStringChars(s, nullptr);
    if (!c) return std::wstring();
    std::wstring out(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return out;
}

jstring to_jstring(JNIEnv* env, const std::wstring& s) {
    return env->NewString(reinterpret_cast<const jchar*>(s.data()), static_cast<jsize>(s.size()));
}

std::string narrow(const std::wstring& s) {
    if (s.empty()) return std::string();
    const int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(n > 0 ? n : 0), '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n, nullptr, nullptr);
    return out;
}

std::wstring take_co_string(LPWSTR s) {
    if (!s) return std::wstring();
    std::wstring out(s);
    CoTaskMemFree(s);
    return out;
}

bool check_java(JNIEnv* env, const char* what) {
    if (!env || !env->ExceptionCheck()) return false;
    std::fprintf(stderr, "[win.apk] webview2: exception in %s:\n", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

std::wstring scheme_of(const std::wstring& url) {
    const size_t colon = url.find(L':');
    if (colon == std::wstring::npos || colon == 0) return std::wstring();
    std::wstring s = url.substr(0, colon);
    for (wchar_t& c : s) {
        if (!(iswalnum(c) || c == L'+' || c == L'-' || c == L'.')) return std::wstring();
        c = static_cast<wchar_t>(towlower(c));
    }
    return s;
}

std::string redact(const std::wstring& url) {
    const size_t cut = url.find_first_of(L"?#");
    if (cut == std::wstring::npos) return narrow(url);
    return narrow(url.substr(0, cut)) + " (+" + std::to_string(url.size() - cut) + " chars)";
}

}  // namespace winapk::host::wv2
