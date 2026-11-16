// native_clipboard.cpp -- ClipboardManager's host pasteboard
// (android.content.ClipboardManager$MacApkHostPasteboard) on the Windows
// clipboard. Upstream's clipboard_bridge.mm does the same on NSPasteboard:
// copy in an app reaches every other program, and the other way round.
//
//   text       CF_UNICODETEXT
//   html       "HTML Format" (CF_HTML, with its offset header)
//   url        "UniformResourceLocatorW"
//   sensitive  "ExcludeClipboardContentFromMonitorProcessing" and
//              "CanIncludeInClipboardHistory" = 0, Windows' way to keep a
//              password out of clipboard history and sync
//   source     "WinApk.ClipSource": the writing app's package and label, UTF-8
//
// Opening the clipboard to write needs a window; a hidden message-only one is
// created on first use.

#include "host_natives.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#include <windows.h>

namespace winapk::host {

namespace {

constexpr jint kText = 1, kHtml = 2, kUrl = 4, kConcealed = 8, kMacApk = 16;

UINT fmt(const wchar_t* name) { return RegisterClipboardFormatW(name); }
UINT html_format() { static const UINT f = fmt(L"HTML Format"); return f; }
UINT url_format() { static const UINT f = fmt(L"UniformResourceLocatorW"); return f; }
UINT conceal_format() { static const UINT f = fmt(L"ExcludeClipboardContentFromMonitorProcessing"); return f; }
UINT history_format() { static const UINT f = fmt(L"CanIncludeInClipboardHistory"); return f; }
UINT source_format() { static const UINT f = fmt(L"WinApk.ClipSource"); return f; }

HWND owner_window() {
    static HWND h = CreateWindowExW(0, L"STATIC", L"Win.apk clipboard", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr,
                                    GetModuleHandleW(nullptr), nullptr);
    return h;
}

// The clipboard can be held by another program for a moment.
class ClipboardLock {
public:
    explicit ClipboardLock(HWND owner) {
        for (int i = 0; i < 20 && !(open_ = OpenClipboard(owner) != 0); ++i) Sleep(5);
    }
    ~ClipboardLock() {
        if (open_) CloseClipboard();
    }
    bool open() const { return open_; }

private:
    bool open_ = false;
};

std::wstring wide(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* c = env->GetStringChars(s, nullptr);
    std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return w;
}
std::string utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
jstring jstr(JNIEnv* env, const std::wstring& w) {
    return env->NewString(reinterpret_cast<const jchar*>(w.data()), static_cast<jsize>(w.size()));
}

bool put(UINT format, const void* data, size_t size) {
    HGLOBAL g = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!g) return false;
    std::memcpy(GlobalLock(g), data, size);
    GlobalUnlock(g);
    if (SetClipboardData(format, g)) return true;
    GlobalFree(g);
    return false;
}
std::string get_bytes(UINT format) {
    HANDLE h = GetClipboardData(format);
    if (!h) return {};
    const void* p = GlobalLock(h);
    std::string s(static_cast<const char*>(p), GlobalSize(h));
    GlobalUnlock(h);
    return s;
}
std::wstring get_wide(UINT format) {
    std::string b = get_bytes(format);
    std::wstring w(reinterpret_cast<const wchar_t*>(b.data()), b.size() / sizeof(wchar_t));
    const size_t nul = w.find(L'\0');
    if (nul != std::wstring::npos) w.resize(nul);
    return w;
}

// CF_HTML: a header of byte offsets in front of the document.
std::string make_cf_html(const std::string& fragment) {
    const char* kHead = "Version:0.9\r\nStartHTML:%010zu\r\nEndHTML:%010zu\r\nStartFragment:%010zu\r\nEndFragment:%010zu\r\n";
    const std::string pre = "<html><body><!--StartFragment-->", post = "<!--EndFragment--></body></html>";
    char probe[160];
    const size_t head = static_cast<size_t>(std::snprintf(probe, sizeof(probe), kHead, size_t{0}, size_t{0}, size_t{0}, size_t{0}));
    const size_t start_frag = head + pre.size(), end_frag = start_frag + fragment.size();
    const size_t end_html = end_frag + post.size();
    char header[160];
    std::snprintf(header, sizeof(header), kHead, head, end_html, start_frag, end_frag);
    return header + pre + fragment + post;
}
std::string cf_html_fragment(const std::string& doc) {
    auto offset = [&](const char* key) -> long {
        const size_t at = doc.find(key);
        return at == std::string::npos ? -1 : std::strtol(doc.c_str() + at + std::strlen(key), nullptr, 10);
    };
    const long a = offset("StartFragment:"), b = offset("EndFragment:");
    if (a < 0 || b < a || static_cast<size_t>(b) > doc.size()) return {};
    return doc.substr(static_cast<size_t>(a), static_cast<size_t>(b - a));
}

jint current_types() {
    jint t = 0;
    if (IsClipboardFormatAvailable(CF_UNICODETEXT)) t |= kText;
    if (IsClipboardFormatAvailable(html_format())) t |= kHtml;
    if (IsClipboardFormatAvailable(url_format())) t |= kUrl;
    if (IsClipboardFormatAvailable(conceal_format())) t |= kConcealed;
    if (IsClipboardFormatAvailable(source_format())) t |= kMacApk;
    return t;
}

jlong JNICALL Clip_changeCount(JNIEnv*, jclass) { return GetClipboardSequenceNumber(); }
jint JNICALL Clip_types(JNIEnv*, jclass) { return current_types(); }

jlong JNICALL Clip_write(JNIEnv* env, jclass, jstring text, jstring html, jstring url, jstring pkg, jstring label,
                         jboolean sensitive) {
    ClipboardLock lock(owner_window());
    if (!lock.open() || !EmptyClipboard()) return -1;
    if (text) {
        const std::wstring w = wide(env, text);
        put(CF_UNICODETEXT, w.c_str(), (w.size() + 1) * sizeof(wchar_t));
    }
    if (html) {
        const std::string h = make_cf_html(utf8(env, html));
        put(html_format(), h.c_str(), h.size() + 1);
    }
    if (url) {
        const std::wstring w = wide(env, url);
        put(url_format(), w.c_str(), (w.size() + 1) * sizeof(wchar_t));
    }
    if (sensitive) {
        const DWORD zero = 0;
        put(conceal_format(), &zero, sizeof(zero));
        put(history_format(), &zero, sizeof(zero));
    }
    const std::string source = utf8(env, pkg) + std::string(1, '\0') + utf8(env, label);
    put(source_format(), source.data(), source.size() + 1);
    return GetClipboardSequenceNumber();
}

jlong JNICALL Clip_clear(JNIEnv*, jclass) {
    ClipboardLock lock(owner_window());
    if (!lock.open() || !EmptyClipboard()) return -1;
    return GetClipboardSequenceNumber();
}

// {text, html, url, sourcePackage, label}; out = {changeCount, types}.
jobjectArray JNICALL Clip_read(JNIEnv* env, jclass, jlongArray out) {
    ClipboardLock lock(nullptr);
    if (!lock.open()) return nullptr;
    const jint types = current_types();
    std::wstring text, url;
    std::string html, pkg, label;
    if (types & kText) text = get_wide(CF_UNICODETEXT);
    if (types & kUrl) url = get_wide(url_format());
    if (types & kHtml) html = cf_html_fragment(get_bytes(html_format()));
    if (types & kMacApk) {
        const std::string s = get_bytes(source_format());
        pkg = s.c_str();
        if (pkg.size() + 1 < s.size()) label = s.c_str() + pkg.size() + 1;
    }
    jobjectArray r = env->NewObjectArray(5, env->FindClass("java/lang/String"), nullptr);
    if (!r) return nullptr;
    if (types & kText) env->SetObjectArrayElement(r, 0, jstr(env, text));
    if (types & kHtml) env->SetObjectArrayElement(r, 1, env->NewStringUTF(html.c_str()));
    if (types & kUrl) env->SetObjectArrayElement(r, 2, jstr(env, url));
    if (types & kMacApk) {
        env->SetObjectArrayElement(r, 3, env->NewStringUTF(pkg.c_str()));
        env->SetObjectArrayElement(r, 4, env->NewStringUTF(label.c_str()));
    }
    if (out && env->GetArrayLength(out) >= 2) {
        const jlong v[2] = {static_cast<jlong>(GetClipboardSequenceNumber()), types};
        env->SetLongArrayRegion(out, 0, 2, v);
    }
    return r;
}

jboolean JNICALL Clip_hostFocused(JNIEnv*, jclass) {
    DWORD pid = 0;
    HWND fg = GetForegroundWindow();
    if (fg) GetWindowThreadProcessId(fg, &pid);
    return pid == GetCurrentProcessId() ? JNI_TRUE : JNI_FALSE;
}
jstring JNICALL Clip_name(JNIEnv* env, jclass) { return env->NewStringUTF("Windows clipboard"); }

constexpr const char* kCls = "android.content.ClipboardManager$MacApkHostPasteboard";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeChangeCount", "()J", Clip_changeCount),
    WINAPK_HOST_NATIVE(kCls, "nativeTypes", "()I", Clip_types),
    WINAPK_HOST_NATIVE(kCls, "nativeWrite",
                       "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)J",
                       Clip_write),
    WINAPK_HOST_NATIVE(kCls, "nativeClear", "()J", Clip_clear),
    WINAPK_HOST_NATIVE(kCls, "nativeRead", "([J)[Ljava/lang/String;", Clip_read),
    WINAPK_HOST_NATIVE(kCls, "nativeHostFocused", "()Z", Clip_hostFocused),
    WINAPK_HOST_NATIVE(kCls, "nativePasteboardName", "()Ljava/lang/String;", Clip_name),
};

}  // namespace

HostMethods native_clipboard_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
