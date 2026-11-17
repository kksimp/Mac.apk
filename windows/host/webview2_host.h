// webview2_host.h -- WebView2, the runtime's web engine on Windows.
//
// Web content an app shows -- the browser tab of a Custom Tab or Trusted Web
// Activity launch (native_browser_tab.cpp), the app's own android.webkit
// WebViews (native_webview.cpp) -- is Microsoft Edge WebView2, the
// counterpart of upstream's WKWebView (runtime/src/webview_bridge.mm).
//
// What this file provides:
//
//  * The loader. WebView2Loader.dll (the WebView2 SDK's redistributable
//    loader, BSD-3-Clause; windows/scripts/fetch_webview2.sh puts it next to
//    winapk-runtime.exe) is loaded at run time, so the runtime still starts
//    where it is missing. It finds the installed Evergreen WebView2 Runtime
//    (the EdgeUpdate client {F3017226-FE2A-4295-8BDF-00C3A9A7E4C5} under HKLM or
//    HKCU, or WEBVIEW2_BROWSER_EXECUTABLE_FOLDER), which Windows 11 and most
//    Windows 10 machines have.
//
//  * available(): whether web content can be shown in-runtime at all. When it
//    cannot (no runtime, no loader, WINAPK_HEADLESS, or WINAPK_WEBVIEW2=0), the
//    callers keep the behaviour from before WebView2: a web link opens in the
//    system browser, and WebView stays the engine-less stub. Decided and logged
//    once.
//
//  * The WebView2 thread. WebView2 objects are single-threaded COM objects: one
//    STA thread with a message loop creates and drives all of them, and every
//    event arrives on it. It is a thread of its own ("winapk-webview"), not the
//    app window's UI thread: that thread calls into Java synchronously for
//    every input event, and the Java side calls in here from any thread, so
//    keeping the two loops apart means neither can stall the other. post()
//    hands it work from any thread; calls into Java from it go through env(),
//    the thread's own JNIEnv.
//
//  * The environment: one per process, on a user-data folder inside the app's
//    data directory (so each app's cookies, storage and cache are its own and
//    survive restarts, and clearing the app's data clears them), created on
//    first use.

#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <utility>

#include <jni.h>
#include <windows.h>

#include "WebView2.h"

namespace winapk::host::wv2 {

// ---- a minimal COM smart pointer (no WRL in every toolchain) ---------------

template <typename T>
class ComPtr {
public:
    ComPtr() = default;
    ComPtr(std::nullptr_t) {}
    ComPtr(const ComPtr& o) : p_(o.p_) {
        if (p_) p_->AddRef();
    }
    ComPtr(ComPtr&& o) noexcept : p_(o.p_) { o.p_ = nullptr; }
    ~ComPtr() { reset(); }
    ComPtr& operator=(const ComPtr& o) {
        if (this != &o) {
            if (o.p_) o.p_->AddRef();
            reset();
            p_ = o.p_;
        }
        return *this;
    }
    ComPtr& operator=(ComPtr&& o) noexcept {
        if (this != &o) {
            reset();
            p_ = o.p_;
            o.p_ = nullptr;
        }
        return *this;
    }
    // Takes a reference the caller already owns.
    static ComPtr attach(T* p) {
        ComPtr c;
        c.p_ = p;
        return c;
    }
    // Adds a reference to a borrowed pointer.
    static ComPtr retain(T* p) {
        if (p) p->AddRef();
        return attach(p);
    }
    void reset() {
        if (p_) p_->Release();
        p_ = nullptr;
    }
    T** put() {
        reset();
        return &p_;
    }
    T* get() const { return p_; }
    T* operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }
    template <typename U>
    ComPtr<U> query(REFIID iid) const {
        ComPtr<U> out;
        if (p_) p_->QueryInterface(iid, reinterpret_cast<void**>(out.put()));
        return out;
    }

private:
    T* p_ = nullptr;
};

// ---- event and completion handlers from lambdas ---------------------------
//
// Every WebView2 handler interface is IUnknown plus one Invoke. Handler<I>
// implements I around a std::function with Invoke's own parameter list, read
// off &I::Invoke.

template <typename I, typename... A>
class HandlerImpl final : public I {
public:
    using Fn = std::function<HRESULT(A...)>;
    HandlerImpl(const IID& iid, Fn fn) : iid_(iid), fn_(std::move(fn)) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
        if (!out) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, iid_)) {
            *out = static_cast<I*>(this);
            AddRef();
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG r = --refs_;
        if (r == 0) delete this;
        return r;
    }
    HRESULT STDMETHODCALLTYPE Invoke(A... a) override { return fn_ ? fn_(a...) : S_OK; }

private:
    const IID iid_;
    Fn fn_;
    std::atomic<ULONG> refs_{1};
};

template <typename M>
struct HandlerOf;
template <typename I, typename... A>
struct HandlerOf<HRESULT (I::*)(A...)> {
    using type = HandlerImpl<I, A...>;
};

template <typename I, typename F>
ComPtr<I> handler(const IID& iid, F&& fn) {
    using H = typename HandlerOf<decltype(&I::Invoke)>::type;
    return ComPtr<I>::attach(new H(iid, std::forward<F>(fn)));
}

// ---- the engine --------------------------------------------------------------

// See the header comment. Safe from any thread.
bool available();
// Why available() is false, as a code for logs and error messages
// ("WEBVIEW2_RUNTIME_MISSING (0x80070002)", "HEADLESS"); empty when available.
std::string unavailable_reason();
// The HRESULT the environment failed with, S_OK while it has not. WebView2 thread only.
HRESULT environment_error();
// The installed runtime's version ("155.0.4283.62"), or empty when unavailable.
std::wstring runtime_version();

// Runs fn on the WebView2 thread, starting it on first use. False when the
// thread could not be started (fn is then dropped).
bool post(std::function<void()> fn);
bool on_thread();

// Remembers the JavaVM; every JNI entry point calls this before posting work.
void set_vm(JavaVM* vm);
// The WebView2 thread's JNIEnv (attached as a daemon thread). WebView2 thread only.
JNIEnv* env();

// Calls cb on the WebView2 thread with the process's environment, created on
// first use on `folder` (later calls share the first one's folder), or with
// nullptr when it cannot be created. WebView2 thread only.
void with_environment(const std::wstring& folder, std::function<void(ICoreWebView2Environment*)> cb);

// ---- small helpers --------------------------------------------------------------

std::wstring from_jstring(JNIEnv* env, jstring s);
jstring to_jstring(JNIEnv* env, const std::wstring& s);
std::string narrow(const std::wstring& s);  // UTF-8
// A CoTaskMem string from WebView2, taken and freed.
std::wstring take_co_string(LPWSTR s);
// Clears and prints a pending Java exception; true when there was one.
bool check_java(JNIEnv* env, const char* what);
// The URL's scheme, lower case ("" when it has none).
std::wstring scheme_of(const std::wstring& url);
// For logs: the URL without its query and fragment (an OAuth redirect's query
// is a one-time code), as HostUrlIntake.redact does.
std::string redact(const std::wstring& url);

}  // namespace winapk::host::wv2
