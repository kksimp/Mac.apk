// webview_bridge.mm -- android.webkit.WebView backed by the system WKWebView.
//
// WHY WKWEBVIEW. The no-shortcuts rule: when Android infrastructure is
// missing, implement it with the macOS-native equivalent of what real Android
// provides (the same move as android.database.sqlite.* -> libsqlite3).
// Android's WebView is a complete, always-present web engine owned by the
// platform; macOS's is WebKit/WKWebView. No bundled Chromium, no emulation.
// Entitlement posture PROVEN 2026-08-12: a hardened-runtime, ad-hoc-signed,
// non-bundled process with our exact jit-entitlements.plist creates WKWebView,
// spawns its WebContent/Networking XPC children, completes a TLS navigation to
// accounts.google.com (which serves the EmbeddedSetupAndroid flow to our
// Android UA), evaluates JS, and answers a synchronous prompt() bridge.
//
// THREADING CONTRACT (deadlock-free by one-directional blocking):
//  - Guest (JVM) threads NEVER run AppKit: every JNI entry marshals via
//    dispatch_async onto the main queue. All bridge state (g_holders and
//    everything inside a holder) is therefore MAIN-THREAD-ONLY, and because
//    one serial queue orders create -> configure -> load, there is no
//    create/use race.
//  - The ONLY blocking is a guest thread parking on a dispatch_semaphore
//    with a timeout while the main queue answers (the synchronous
//    CookieManager APIs). The main thread never waits on a guest thread, so
//    there is no cycle. Guard: if such an entry ever runs ON the main
//    thread, do the work inline instead of waiting.
//  - Upcalls INTO Java use the house pattern (cocoa_bridge.mm
//    keybind_set_active): GetEnv/AttachCurrentThread, call a static
//    trampoline on android.webkit.MacApkWebViewBridge, detach only if we
//    attached. The Java trampoline only Handler.post()s to the Android UI
//    thread, so the macOS main thread runs microseconds of bytecode.
//  - @JavascriptInterface calls run on g_jsbridge_queue (a GCD serial
//    queue), mirroring Android, which also invokes them on a dedicated
//    background "JavaBridge" thread, never the UI thread. The page's JS is
//    blocked inside prompt() while we work; the macOS main thread is NOT.
//
// COMPOSITING. The WKWebView is a real NSView added as a subview of the host
// MTKView (WvHostView(): CocoaWebViewHostView() = g_last_metal_view, else the
// CocoaPresentBitmap fallback order, with the canvas overlay's own
// windowless-Path-A rule applied on top), so its backing layer is a SIBLING of
// the canvas overlay layer. A page changes host only on its Java WebView's own
// frame push, never behind its back (WvHostFollowEnabled). Slot in the
// documented z-stack
// (docs/viewcanvasdriver-overlay-never-composited_2026-08-12.md):
//   angleLayer(0) < app widgets(0.5 gl-host / 1.0 path-a) < WKWebView(1.5)
//   < dedicated Unity/native-EGL layer(2.0)
// i.e. above the (opaque, full-window) widget canvas -- below it the web page
// would render into a layer nobody can see, which is this project's most
// expensive known failure shape -- and below the dedicated native-EGL layer.
// KNOWN COST (notated): a Java-side Dialog composited INTO the canvas bitmap
// cannot draw over the WKWebView's rect. Fixing that requires compositing web
// pixels into the canvas, which trades away WebKit's own render loop.
// PAGE BASE (2026-09-26): WebView.setBackgroundColor sets the colour painted
// under a page with no background of its own (AOSP -> SetPageBaseBackgroundColor);
// a non-white base is a transparent page (-_setDrawsBackground:NO) over a
// MacApkWvBaseView sibling just below the WKWebView. See WvApplyBase.
//
// COORDINATES. Java passes the view rect in SURFACE px (the ViewCanvasDriver
// bitmap space, via View.getLocationInWindow) plus the surface px dims; we map
// by fraction onto the host view's bounds, which the canvas overlay stretches
// onto with kCAGravityResize -- so the WKWebView lands exactly over the pixels
// the widget tree reserved for it. AppKit y-up: flip against bounds height.
//
// JS BRIDGE (addJavascriptInterface). Java composes an injection script
// (window.<name>.<method> stubs); each stub serializes the call as JSON and
// invokes prompt("\x01macapk-jsb\x01" + json). Our WKUIDelegate intercepts
// that prefix, hops to g_jsbridge_queue, calls
// MacApkWebViewBridge.onJsBridgeCall (reflect + invoke, JSON result), then
// answers the completionHandler. WebKit keeps the page's JS blocked until
// then, which is exactly the synchronous contract Android gives these
// methods. Real window.prompt/alert/confirm (no prefix) surface through
// WebChromeClient with the completionHandler parked in g_js_dialogs until
// the app's JsResult answers (Android blocks the page identically).
//
// APP ASSETS (file:///android_asset/, file:///android_res/). Android's WebView
// serves these two paths from the APP'S OWN AssetManager / Resources whatever
// setAllowFileAccess says. WebKit refuses a WKURLSchemeHandler for `file`, so
// they are re-homed onto the private scheme `macapk-asset` by a pure scheme swap
// (file:///android_asset/x <-> macapk-asset:///android_asset/x, which keeps
// relative AND root-relative references resolving exactly as on a device), read
// through JNI off the main thread (MacApkWebViewBridge.macapkReadAsset), and
// every URL leaving the bridge is swapped back in PostEvent, so the app only
// ever sees file:///android_asset/... . See WvAssetSchemeEnabled(). As on a
// device, a PAGE reaches them only from a local document or the one
// loadDataWithBaseURL granted local resources to; a network page is refused
// without a word to the app. See -topDocumentMayLoadAppAssets.

#import <AppKit/AppKit.h>
#import <MetalKit/MetalKit.h>
#import <QuartzCore/QuartzCore.h>   // CACurrentMediaTime
#import <WebKit/WebKit.h>
#import <objc/runtime.h>            // objc_getAssociatedObject: the host-kind tag (WvHostKind)
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>
#include <dlfcn.h>
#include <sqlite3.h>   // types and constants only: the calls are resolved with dlsym below
#include <sys/stat.h>

// cocoa_bridge.mm: the host MTKView the widget overlay composites onto
// (main-thread only; nil until a surface exists).
extern "C" void* CocoaWebViewHostView(void);
// cocoa_bridge.mm (declared in macapk/cocoa_bridge.h): the one runtime
// NSWindow, reused for every content swap. Read by WvHostView().
namespace macapk { void* CocoaGetPlaceholderWindow(); }

@class MacApkWvHolder;

namespace {

JavaVM*   g_vm = nullptr;
jclass    g_bridge_cls = nullptr;      // android.webkit.MacApkWebViewBridge (global ref)
jmethodID g_mid_on_event = nullptr;    // onEvent(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
jmethodID g_mid_js_bridge = nullptr;   // onJsBridgeCall(ILjava/lang/String;)Ljava/lang/String;
jmethodID g_mid_read_asset = nullptr;  // macapkReadAsset(ILjava/lang/String;)[B
jmethodID g_mid_asset_mime = nullptr;  // macapkAssetMime(Ljava/lang/String;[B)Ljava/lang/String;
jmethodID g_mid_asset_page = nullptr;
jmethodID g_mid_real_input = nullptr;  // onRealInput()V  // macapkMaterializeAssetPage(ILjava/lang/String;)[Ljava/lang/String;

// Concurrent queue for app-asset reads (the macapk-asset scheme handler). The
// JNI read never runs on the macOS main thread: a large asset would stall
// every window, and AOSP reads these on its own IO thread too.
dispatch_queue_t g_asset_queue = nullptr;

// Serial queue for @JavascriptInterface invocation -- the Android
// "JavaBridge" thread analog. Serial so app interface objects see the same
// single-threaded call discipline they get on a device.
dispatch_queue_t g_jsbridge_queue = nullptr;

// Serial queue for delegate/KVO event delivery into Java. SERIAL is
// load-bearing: a concurrent queue would let PAGE_FINISHED overtake
// PAGE_STARTED between two hops. Main thread enqueues in delegate order;
// this queue delivers in the same order; the Java trampoline's Handler.post
// preserves it onto the Android UI thread.
dispatch_queue_t g_event_queue = nullptr;

// Event codes -- keep in lockstep with MacApkWebViewBridge.java.
enum {
    EV_PAGE_STARTED        = 1,   // a=url
    EV_PAGE_FINISHED       = 2,   // a=url
    EV_PAGE_COMMIT_VISIBLE = 3,   // a=url
    EV_RECEIVED_ERROR      = 4,   // a=description b=failingUrl c="main" or "" (subresource) i=errorCode
    EV_PROGRESS            = 5,   // i=0..100
    EV_TITLE               = 6,   // a=title
    EV_URL_CHANGED         = 7,   // a=url i=(canGoBack?1:0)|(canGoForward?2:0)
    EV_JS_RESULT           = 8,   // a=json i=callbackId
    EV_SHOULD_OVERRIDE     = 9,   // a=url b=method c=flags("main,redirect,gesture") i=decisionId
    EV_HISTORY_UPDATE      = 10,  // a=url i=isReload
    EV_JS_ALERT            = 11,  // a=message b=url i=resultId
    EV_JS_CONFIRM          = 12,  // a=message b=url i=resultId
    EV_JS_PROMPT           = 13,  // a=message b=url c=defaultText i=resultId
    EV_RENDER_GONE         = 14,  //
    EV_HTTP_ERROR          = 15,  // a=url i=statusCode (main frame only)
    EV_CLOSE_WINDOW        = 16,  //  (window.close() -> WebChromeClient.onCloseWindow)
    EV_PAGE_SCROLL         = 17,  // a="scrollXpx,scrollYpx,contentHeightCss,pxPerCss"
    EV_HOST_LOST           = 18,  //  (the host view left the window; see WvHostFollowEnabled)
    EV_CONSOLE             = 19,  // a=message b=sourceUrl c=level("0".."4", ConsoleMessage.MessageLevel ordinal) i=line
};

// android.webkit.WebViewClient ERROR_* values (AOSP constants).
enum {
    ERR_UNKNOWN = -1, ERR_HOST_LOOKUP = -2, ERR_CONNECT = -6, ERR_TIMEOUT = -8,
    ERR_REDIRECT_LOOP = -9, ERR_UNSUPPORTED_SCHEME = -10, ERR_SSL = -11,
    ERR_BAD_URL = -12,
};

int MapNSURLError(NSError* e) {
    if (![e.domain isEqualToString:NSURLErrorDomain]) return ERR_UNKNOWN;
    switch (e.code) {
        case NSURLErrorCannotFindHost:
        case NSURLErrorDNSLookupFailed:           return ERR_HOST_LOOKUP;
        case NSURLErrorCannotConnectToHost:
        case NSURLErrorNetworkConnectionLost:
        case NSURLErrorNotConnectedToInternet:    return ERR_CONNECT;
        case NSURLErrorTimedOut:                  return ERR_TIMEOUT;
        case NSURLErrorHTTPTooManyRedirects:      return ERR_REDIRECT_LOOP;
        case NSURLErrorUnsupportedURL:            return ERR_UNSUPPORTED_SCHEME;
        case NSURLErrorBadURL:                    return ERR_BAD_URL;
        case NSURLErrorSecureConnectionFailed:
        case NSURLErrorServerCertificateHasBadDate:
        case NSURLErrorServerCertificateUntrusted:
        case NSURLErrorServerCertificateHasUnknownRoot:
        case NSURLErrorServerCertificateNotYetValid:
        case NSURLErrorClientCertificateRejected:
        case NSURLErrorClientCertificateRequired: return ERR_SSL;
        // A missing or unreadable local file is ERROR_UNKNOWN on Android, NOT
        // ERROR_FILE_NOT_FOUND (-14) or ERROR_FILE (-13). Chromium turns a
        // failed file:///android_asset open into net::ERR_FILE_NOT_FOUND, and
        // AwWebResourceError.convertErrorCode (chromium/main
        // android_webview/java/src/org/chromium/android_webview/
        // AwWebResourceError.java) has no case for it: the only source of
        // ERROR_FILE_NOT_FOUND is ERR_UPLOAD_FILE_CHANGED and the only source
        // of ERROR_FILE is ERR_FILE_TOO_BIG; everything else is `default:
        // return WebviewErrorCode.ERROR_UNKNOWN`. Spelled out so nobody
        // "fixes" these to -14 (proposed once, 2026-09-25; it would have made
        // us diverge from a device).
        case NSURLErrorFileDoesNotExist:
        case NSURLErrorNoPermissionsToReadFile:
        case NSURLErrorFileIsDirectory:           return ERR_UNKNOWN;
        default:                                  return ERR_UNKNOWN;
    }
}

// ---- file:///android_asset/ + file:///android_res/ (MACAPK_WEBVIEW_ASSET_SCHEME=0 disables)
//
// AOSP contract: WebSettings.setAllowFileAccess javadoc (android14-release):
// "Note that this enables or disables file system access only. Assets and
// resources are still accessible using file:///android_asset and
// file:///android_res." Chromium recognises the two paths in
// android_webview/common/url_constants.cc (kAndroidAssetPath = "/android_asset/",
// kAndroidResourcePath = "/android_res/"; IsAndroidSpecialFileUrl = a valid
// file: URL whose path starts with either) and serves them from
// AndroidProtocolHandler.openAsset -> getAssets().open(path, ACCESS_STREAMING).
//
// Before this we handed file:///android_asset/... to -loadFileURL: with the
// literal path, which does not exist on macOS, so every such load failed
// (NSURLErrorFileDoesNotExist) and reached the app as onReceivedError. Measured
// victim: Candy Crush Saga's first-run Terms of Use popup (King usdk
// PopupWebview, toupp.html under file:///android_asset/res_output/...) -- the
// popup got onReceivedError and tore itself down, so the game skipped the
// dialog entirely (docs/candy-crush-terms-dialog_2026-09-25.md). DEX string
// references to file:///android_asset also appear in AnkiDroid, Signal,
// Telegram, Fossify Gallery, Organic Maps and others.
//
// The gate is default ON; =0 restores the old loadFileURL path for an A/B
// (debug/wvtest/asset_gate.sh runs both arms).
bool WvAssetSchemeEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_ASSET_SCHEME");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

NSString* const kWvAssetScheme = @"macapk-asset";

// Guest -> host: file:///android_asset/x -> macapk-asset:///android_asset/x,
// or nil when `url` is not one of the two special paths. Only the SCHEME
// changes; the empty host and the path stay, so a page's relative references
// (style.css) and root-relative ones (/android_asset/img.png) resolve to the
// same asset they name on a device (measured with a headless WKWebView: both
// load; an absolute file:///android_asset/... reference inside page content
// cannot be intercepted -- see the known limit in
// docs/webview-wkwebview-bridge_2026-08-12.md). Path match is case-sensitive
// on the percent-encoded path, as Chromium's base::StartsWith(url.GetPath(),
// ..., CompareCase::SENSITIVE) is; the scheme is case-insensitive and an empty
// or "localhost" host both count, per the URL standard's file-host rule.
NSString* WvHostUrl(NSString* url) {
    if (!WvAssetSchemeEnabled() || url.length < 8) return nil;
    // Cheap reject first: this runs on every navigation policy ask.
    if ([url rangeOfString:@"file:" options:NSAnchoredSearch | NSCaseInsensitiveSearch].location
            == NSNotFound) return nil;
    NSURLComponents* c = [NSURLComponents componentsWithString:url];
    if (!c || !c.scheme || [c.scheme caseInsensitiveCompare:@"file"] != NSOrderedSame)
        return nil;
    NSString* host = c.percentEncodedHost;
    if (host.length && [host caseInsensitiveCompare:@"localhost"] != NSOrderedSame)
        return nil;
    NSString* path = c.percentEncodedPath;
    if (![path hasPrefix:@"/android_asset/"] && ![path hasPrefix:@"/android_res/"])
        return nil;
    c.scheme = kWvAssetScheme;
    c.percentEncodedHost = @"";
    return c.string;
}

// Host -> guest, the inverse. Applied to EVERY url that leaves the bridge
// (PostEvent) so getUrl(), onPageStarted/Finished, shouldOverrideUrlLoading,
// onReceivedError's failing url, history and dialog urls all read
// file:///android_asset/... exactly as on a device. Anything else is returned
// unchanged. WebKit hands schemes back lowercased, but compare loosely anyway.
// Materialised asset roots (MACAPK_WEBVIEW_FILE_ORIGIN): the file: URL of
// each <android root>/.macapk/webassets/<app>/android_asset/ directory a page
// was loaded from. WvGuestUrl maps them back to file:///android_asset/.
NSMutableArray<NSString*>* g_file_origin_roots = nil;   // guarded by @synchronized

void WvNoteFileOriginRoot(NSString* rootUrl) {
    if (!rootUrl.length) return;
    @synchronized ([NSObject class]) {
        if (!g_file_origin_roots) g_file_origin_roots = [NSMutableArray new];
        if (![g_file_origin_roots containsObject:rootUrl]) [g_file_origin_roots addObject:rootUrl];
    }
}

NSString* WvGuestUrl(NSString* url) {
    if (url.length > 8 && [url hasPrefix:@"file:"]) {
        @synchronized ([NSObject class]) {
            for (NSString* r in g_file_origin_roots)
                if ([url hasPrefix:r])
                    return [@"file:///android_asset/" stringByAppendingString:[url substringFromIndex:r.length]];
        }
        return url;
    }
    static const NSUInteger n = kWvAssetScheme.length;
    if (url.length <= n || [url characterAtIndex:n] != ':') return url;
    if ([url compare:kWvAssetScheme options:NSCaseInsensitiveSearch
               range:NSMakeRange(0, n)] != NSOrderedSame) return url;
    return [@"file" stringByAppendingString:[url substringFromIndex:n]];
}

// Is `url` an app asset in either spelling (file:///android_asset/...,
// file:///android_res/... or our macapk-asset: form)? The navigation checks
// below key on this; everything else keeps its old path untouched.
bool WvIsAppAssetUrl(NSString* url) {
    if (!WvAssetSchemeEnabled() || !url.length) return false;
    if (WvHostUrl(url)) return true;
    static const NSUInteger n = kWvAssetScheme.length;
    return url.length > n && [url characterAtIndex:n] == ':'
        && [url compare:kWvAssetScheme options:NSCaseInsensitiveSearch
                  range:NSMakeRange(0, n)] == NSOrderedSame;
}

// Blink's "local" origins for this purpose: a document whose own scheme is
// file: (or our spelling of an app asset) can load local resources by
// default. third_party/blink/renderer/platform/weborigin/security_origin.cc
// (chromium main): "By default, only local SecurityOrigins can load local
// resources. can_load_local_resources_ = IsLocal();". Takes a URL scheme or a
// WKSecurityOrigin.protocol, which spell it the same way.
bool WvIsLocalScheme(NSString* scheme) {
    return scheme.length
        && ([scheme caseInsensitiveCompare:kWvAssetScheme] == NSOrderedSame
            || [scheme caseInsensitiveCompare:@"file"] == NSOrderedSame);
}

// ---- Java upcalls (any thread; attach-per-call, house pattern) -------------

struct AttachedEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    AttachedEnv() {
        if (!g_vm) return;
        jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (r == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK)
                attached = true;
            else env = nullptr;
        } else if (r != JNI_OK) {
            env = nullptr;
        }
    }
    ~AttachedEnv() { if (attached) g_vm->DetachCurrentThread(); }
    AttachedEnv(const AttachedEnv&) = delete;
    AttachedEnv& operator=(const AttachedEnv&) = delete;
};

// UTF-16 out as well (2026-09-26, MACAPK_WEBVIEW_JSTRING_UTF16, default ON;
// =0 restores NewStringUTF(UTF8String)). NewStringUTF takes JNI *modified*
// UTF-8 (JNI spec, "Modified UTF-8 Strings": a supplementary character is a
// six-byte surrogate pair, never a four-byte sequence), and UTF8String is
// standard UTF-8, so every emoji in a page title, URL, evaluateJavascript
// result, alert text or @JavascriptInterface argument reached the app as
// garbage (measured by debug/wvtest/popup_gate.sh: "😀" arrived as
// "ð" and the rest of the string was lost); an embedded NUL also cut the
// string short. NewString takes the UTF-16 units as they are.
jstring ToJString(JNIEnv* env, NSString* s) {
    if (!s) return nullptr;
    static const bool utf16 = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_JSTRING_UTF16");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    if (!utf16) return env->NewStringUTF(s.UTF8String);
    const NSUInteger n = s.length;
    std::vector<unichar> buf(n ? n : 1);
    if (n) [s getCharacters:buf.data() range:NSMakeRange(0, n)];
    static_assert(sizeof(unichar) == sizeof(jchar), "unichar and jchar are both UTF-16 units");
    return env->NewString(reinterpret_cast<const jchar*>(buf.data()), (jsize)n);
}

// UTF-16 in, UTF-16 out. GetStringUTFChars is JNI *modified* UTF-8: a
// supplementary character (an emoji) arrives as a CESU-8 surrogate pair,
// which +stringWithUTF8String: rejects -- the whole string became nil, so a
// script, URL or page containing one was silently dropped. v1.0.1642 made it
// reachable from javascript: URLs (%F0%9F%98%80 decodes to U+1F600).
NSString* FromJString(JNIEnv* env, jstring js) {
    if (!js) return nil;
    const jsize n = env->GetStringLength(js);
    const jchar* u = env->GetStringChars(js, nullptr);
    if (!u) return nil;
    NSString* s = [NSString stringWithCharacters:(const unichar*)u length:(NSUInteger)n];
    env->ReleaseStringChars(js, u);
    return s;
}

void PostEvent(int viewId, int what, NSString* a, NSString* b, NSString* c, int i) {
    // The one exit for URLs: whatever WebKit reported in host terms
    // (macapk-asset:) reaches the app in its own terms (file:///android_asset/).
    switch (what) {
        case EV_PAGE_STARTED: case EV_PAGE_FINISHED: case EV_PAGE_COMMIT_VISIBLE:
        case EV_URL_CHANGED: case EV_SHOULD_OVERRIDE: case EV_HISTORY_UPDATE:
        case EV_HTTP_ERROR:
            a = WvGuestUrl(a);
            break;
        case EV_RECEIVED_ERROR: case EV_JS_ALERT: case EV_JS_CONFIRM: case EV_JS_PROMPT:
        case EV_CONSOLE:
            b = WvGuestUrl(b);
            break;
        default:
            break;
    }
    AttachedEnv ae;
    if (!ae.env || !g_bridge_cls || !g_mid_on_event) return;
    JNIEnv* env = ae.env;
    jstring ja = ToJString(env, a), jb = ToJString(env, b), jc = ToJString(env, c);
    env->CallStaticVoidMethod(g_bridge_cls, g_mid_on_event, (jint)viewId, (jint)what,
                              ja, jb, jc, (jint)i);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (ja) env->DeleteLocalRef(ja);
    if (jb) env->DeleteLocalRef(jb);
    if (jc) env->DeleteLocalRef(jc);
}

// Synchronous @JavascriptInterface invoke; runs on g_jsbridge_queue.
NSString* CallJsBridge(int viewId, NSString* payload) {
    AttachedEnv ae;
    if (!ae.env || !g_bridge_cls || !g_mid_js_bridge) return nil;
    JNIEnv* env = ae.env;
    jstring jp = ToJString(env, payload);
    jstring jr = (jstring)env->CallStaticObjectMethod(g_bridge_cls, g_mid_js_bridge,
                                                      (jint)viewId, jp);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); jr = nullptr; }
    NSString* result = nil;
    if (jr) {
        const char* utf = env->GetStringUTFChars(jr, nullptr);
        if (utf) {
            result = [NSString stringWithUTF8String:utf];
            env->ReleaseStringUTFChars(jr, utf);
        }
        env->DeleteLocalRef(jr);
    }
    if (jp) env->DeleteLocalRef(jp);
    return result;
}

// MACAPK_WEBVIEW_FILE_ORIGIN (see MacApkWebViewBridge.macapkMaterializeAssetPage
// for the why): copy the page's asset directory to real files once and return
// {page path, read-access root, android_asset root}, or nil to keep the
// macapk-asset route. Runs on g_asset_queue, never on the main thread.
NSArray<NSString*>* WvMaterializeAssetPage(int viewId, NSString* guestUrl) {
    AttachedEnv ae;
    if (!ae.env || !g_bridge_cls || !g_mid_asset_page) return nil;
    JNIEnv* env = ae.env;
    jstring ju = ToJString(env, guestUrl);
    jobjectArray arr = (jobjectArray)env->CallStaticObjectMethod(g_bridge_cls, g_mid_asset_page,
                                                                 (jint)viewId, ju);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); arr = nullptr; }
    NSMutableArray<NSString*>* out = nil;
    if (arr && env->GetArrayLength(arr) == 3) {
        out = [NSMutableArray arrayWithCapacity:3];
        for (jsize k = 0; k < 3; k++) {
            jstring js = (jstring)env->GetObjectArrayElement(arr, k);
            NSString* v = FromJString(env, js);
            if (js) env->DeleteLocalRef(js);
            if (!v.length) { out = nil; break; }
            [out addObject:v];
        }
    }
    if (arr) env->DeleteLocalRef(arr);
    if (ju) env->DeleteLocalRef(ju);
    return out;
}

// Read one app asset / resource for the macapk-asset scheme handler. `guestUrl`
// is the file:///android_asset/... or file:///android_res/... form, still
// percent-encoded: the Java side parses it with android.net.Uri exactly as
// Chromium's AndroidProtocolHandler does (Uri.getPath() is what decodes it).
// Returns nil on a miss. Runs on g_asset_queue, never on the main thread.
NSData* WvReadAsset(int viewId, NSString* guestUrl, NSString** outMime) {
    *outMime = nil;
    AttachedEnv ae;
    if (!ae.env || !g_bridge_cls || !g_mid_read_asset || !g_mid_asset_mime) return nil;
    JNIEnv* env = ae.env;
    jstring ju = ToJString(env, guestUrl);
    jbyteArray arr = (jbyteArray)env->CallStaticObjectMethod(g_bridge_cls, g_mid_read_asset,
                                                             (jint)viewId, ju);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); arr = nullptr; }
    NSData* data = nil;
    if (arr) {
        const jsize n = env->GetArrayLength(arr);
        NSMutableData* d = [NSMutableData dataWithLength:(NSUInteger)n];
        if (n > 0) env->GetByteArrayRegion(arr, 0, n, (jbyte*)d.mutableBytes);
        data = d;
        jstring jm = (jstring)env->CallStaticObjectMethod(g_bridge_cls, g_mid_asset_mime, ju, arr);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); jm = nullptr; }
        if (jm) {
            *outMime = FromJString(env, jm);
            env->DeleteLocalRef(jm);
        }
        env->DeleteLocalRef(arr);
    }
    if (ju) env->DeleteLocalRef(ju);
    return data;
}

// ---- evaluateJavascript's result string, Chromium's bytes ------------------
// (2026-09-26, MACAPK_WEBVIEW_JSON_CHROMIUM, default ON; =0 restores the
// NSJSONSerialization encoder below.)
//
// AOSP hands ValueCallback<String> the JSON Chromium writes:
// content/browser/web_contents/web_contents_android.cc JavaScriptResultCallback
// does `base::WriteJson(result)` on the value V8ValueConverterImpl made
// (content/renderer/v8_value_converter_impl.cc FromV8ValueImpl). Apps parse it,
// but many UNWRAP it by hand instead, so the exact bytes are the contract.
// MEASURED: King's usdk Popup$4$1.sanitizeJsonResult strips the quotes and
// replaces only \" -- and NSJSONSerialization writes "/" as "\/", so a result
// holding a URL reached King's native side as https:\/\/... (the popup_gate
// row king-evaluateJavascript-json-value caught "ToU\/PP" for "ToU/PP").
// What Chromium writes, and this reproduces from WebKit's Foundation values:
//   strings  base/json/string_escape.cc EscapeSpecialCodePoint: \b \f \n \r \t
//            \\ \" escaped, '<' as <, U+2028/U+2029 as  / , other
//            code points below 32 as \u%04X; '/' and non-ASCII written as is;
//            an unpaired surrogate becomes U+FFFD (V8 Utf8Value).
//   numbers  an int32 value (not -0) is an INTEGER ("1"); any other finite
//            number is a DOUBLE: JS Number::toString digits (base
//            NumberToString, the EcmaScript converter) plus ".0" when neither
//            '.' nor an exponent is present (json_writer.cc BuildJSONString
//            (double)), so 2147483648 is "2147483648.0" and 1e21 is "1e+21";
//            NaN / Infinity do not convert: null in a list, key dropped in a
//            dict, "null" at top level.
//   objects  keys in byte order (base::DictValue is a sorted flat_map), no
//            spaces; a Date is an object with no own enumerable keys, "{}"
//            (date_allowed_ is false); bool is true/false; null is null.
bool WvJsonChromiumEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_JSON_CHROMIUM");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// ECMA-262 Number::toString(x) for a finite, nonzero-or-zero double: the
// shortest digit string that round-trips, laid out by the spec's four cases.
void WvAppendEcmaNumber(double d, std::string* out) {
    if (d == 0) { out->push_back('0'); return; }   // -0 prints as "0" too
    if (d < 0) { out->push_back('-'); d = -d; }
    char buf[40];
    for (int p = 1; p <= 17; ++p) {
        std::snprintf(buf, sizeof buf, "%.*e", p - 1, d);
        if (std::strtod(buf, nullptr) == d) break;
    }
    const char* e = std::strchr(buf, 'e');
    if (!e) { out->append(buf); return; }
    std::string digits;
    for (const char* c = buf; c < e; ++c) if (*c != '.') digits.push_back(*c);
    while (digits.size() > 1 && digits.back() == '0') digits.pop_back();
    const int k = (int)digits.size();
    const int n = std::atoi(e + 1) + 1;   // value = 0.<digits> * 10^n
    if (k <= n && n <= 21) {
        out->append(digits);
        out->append((size_t)(n - k), '0');
    } else if (0 < n && n <= 21) {
        out->append(digits, 0, (size_t)n);
        out->push_back('.');
        out->append(digits, (size_t)n, std::string::npos);
    } else if (-6 < n && n <= 0) {
        out->append("0.");
        out->append((size_t)(-n), '0');
        out->append(digits);
    } else {
        out->push_back(digits[0]);
        if (k > 1) { out->push_back('.'); out->append(digits, 1, std::string::npos); }
        const int x = n - 1;
        out->push_back('e');
        out->push_back(x < 0 ? '-' : '+');
        out->append(std::to_string(x < 0 ? -x : x));
    }
}

void WvAppendUtf8(uint32_t cp, std::string* out) {
    if (cp < 0x80) {
        out->push_back((char)cp);
    } else if (cp < 0x800) {
        out->push_back((char)(0xC0 | (cp >> 6)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out->push_back((char)(0xE0 | (cp >> 12)));
        out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    } else {
        out->push_back((char)(0xF0 | (cp >> 18)));
        out->push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
        out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    }
}

void WvAppendChromiumJsonString(NSString* s, std::string* out) {
    out->push_back('"');
    const NSUInteger n = s.length;
    for (NSUInteger i = 0; i < n; ++i) {
        uint32_t cp = [s characterAtIndex:i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
            const unichar lo = [s characterAtIndex:i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        switch (cp) {
            case '\b': out->append("\\b"); continue;
            case '\f': out->append("\\f"); continue;
            case '\n': out->append("\\n"); continue;
            case '\r': out->append("\\r"); continue;
            case '\t': out->append("\\t"); continue;
            case '\\': out->append("\\\\"); continue;
            case '"':  out->append("\\\""); continue;
            case '<':  out->append("\\u003C"); continue;
            case 0x2028: out->append("\\u2028"); continue;
            case 0x2029: out->append("\\u2029"); continue;
            default: break;
        }
        if (cp < 32) {
            char esc[8];
            std::snprintf(esc, sizeof esc, "\\u%04X", (unsigned)cp);
            out->append(esc);
        } else {
            WvAppendUtf8(cp, out);
        }
    }
    out->push_back('"');
}

// Appends `v` and returns true, or returns false (appending nothing) for a
// value Chromium's converter would not produce (NaN / Infinity, an unknown
// class, too deep): the caller then writes null (list, top level) or drops
// the key (dict), as V8ValueConverterImpl does.
bool WvAppendChromiumJson(id v, std::string* out, int depth) {
    if (depth > 100) return false;   // V8ValueConverterImpl kMaxRecursionDepth
    if (!v || v == [NSNull null]) { out->append("null"); return true; }
    if ([v isKindOfClass:[NSString class]]) {
        WvAppendChromiumJsonString((NSString*)v, out);
        return true;
    }
    if ([v isKindOfClass:[NSNumber class]]) {
        if (CFGetTypeID((__bridge CFTypeRef)v) == CFBooleanGetTypeID()) {
            out->append([(NSNumber*)v boolValue] ? "true" : "false");
            return true;
        }
        const double d = [(NSNumber*)v doubleValue];
        if (!std::isfinite(d)) return false;
        if (d >= -2147483648.0 && d <= 2147483647.0 && d == (double)(int32_t)d
                && !(d == 0 && std::signbit(d))) {
            out->append(std::to_string((int32_t)d));
            return true;
        }
        std::string real;
        WvAppendEcmaNumber(d, &real);
        if (real.find_first_of(".eE") == std::string::npos) real.append(".0");
        out->append(real);
        return true;
    }
    if ([v isKindOfClass:[NSArray class]]) {
        out->push_back('[');
        bool first = true;
        for (id e in (NSArray*)v) {
            if (!first) out->push_back(',');
            first = false;
            if (!WvAppendChromiumJson(e, out, depth + 1)) out->append("null");
        }
        out->push_back(']');
        return true;
    }
    if ([v isKindOfClass:[NSDictionary class]]) {
        NSDictionary* dict = (NSDictionary*)v;
        std::vector<std::pair<std::string, id>> items;
        for (id key in dict) {
            if (![key isKindOfClass:[NSString class]]) continue;
            const char* u = [(NSString*)key UTF8String];
            items.emplace_back(u ? std::string(u) : std::string(), dict[key]);
        }
        std::sort(items.begin(), items.end(),
                  [](const auto& a, const auto& b) { return a.first < b.first; });
        out->push_back('{');
        bool first = true;
        for (const auto& kv : items) {
            std::string val;
            if (!WvAppendChromiumJson(kv.second, &val, depth + 1)) continue;
            if (!first) out->push_back(',');
            first = false;
            WvAppendChromiumJsonString([NSString stringWithUTF8String:kv.first.c_str()] ?: @"", out);
            out->push_back(':');
            out->append(val);
        }
        out->push_back('}');
        return true;
    }
    if ([v isKindOfClass:[NSDate class]]) { out->append("{}"); return true; }
    return false;
}

// Encode an evaluateJavaScript completion value the way Android's
// evaluateJavascript encodes its result: a JSON string ("null" for
// null/undefined/unsupported). With the gate off: NSJSONSerialization, which
// refuses top-level fragments, so scalars are wrapped in an array and the
// brackets stripped.
NSString* JsonEncodeResult(id value) {
    if (WvJsonChromiumEnabled()) {
        std::string out;
        if (!WvAppendChromiumJson(value, &out, 0)) return @"null";
        return [[NSString alloc] initWithBytes:out.data() length:out.size()
                                      encoding:NSUTF8StringEncoding] ?: @"null";
    }
    if (!value || value == [NSNull null]) return @"null";
    @try {
        NSData* d = [NSJSONSerialization dataWithJSONObject:@[ value ] options:0 error:nil];
        if (!d) return @"null";
        NSString* s = [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding];
        if (s.length < 2) return @"null";
        return [s substringWithRange:NSMakeRange(1, s.length - 2)];
    } @catch (NSException* ex) {
        return @"null";
    }
}

}  // namespace

// ---- per-WebView holder + delegates ---------------------------------------

// The prefix the Java-composed interface stubs use. \x01 cannot appear in a
// legitimate page prompt() message's first character in practice, and even if
// it did the worst case is a missed app dialog, not a wrong bridge call.
static NSString* const kJsBridgePrefix = @"\x01macapk-jsb\x01";

// v1.0.1643: the WKWebView sits BELOW the widget canvas (see WvZBelow), so
// Android views the app draws over its WebView -- a toolbar, a header, a
// bottom bar, a dialog -- are finally visible. AppKit hit-tests the NSView
// tree, not layer z, so without this every click on that chrome would still
// land in the web page. Java pushes the rects its own views cover
// (nativeSetOccluders); inside one we answer nil, AppKit falls back to the
// host MTKView, and the click reaches the Android view tree like any other.
@interface MacApkWKWebView : WKWebView
// Occluded rects in the SUPERVIEW's coordinate space (what -hitTest: gets).
@property (nonatomic, copy) NSArray<NSValue*>* macapkOccluders;
// The Java WebView's id, for EV_HOST_LOST (see WvHostFollowEnabled). 0 = none.
@property (nonatomic) int macapkViewId;
// Whether the last -viewDidMoveToWindow saw a window.
@property (nonatomic) BOOL macapkInWindow;
@end

namespace {
bool WvHostFollowEnabled();
// True while ApplyFrame / NativeDestroy move a WKWebView themselves, so the
// window change that causes is not reported as the HOST leaving the window.
bool g_wv_rehosting = false;
}  // namespace

// MACAPK_WEBVIEW_REAL_INPUT (default ON; =0 = the old answer, where no click or
// key inside a page ever counted as user input). The host-launch policy
// (ActivityHost.macapkLaunchAllowed) opens the Mac's browser for an app only
// within a grace window after a REAL user action, recorded by TouchDispatcher.
// A WKWebView gets its events straight from AppKit, so an app whose UI is a
// WebView (Cordova, Ionic) never recorded one: DOFUS Touch's "Play as a guest"
// -> Terms -> Accept opened auth.ankama.com through InAppBrowser target=_system
// and was REFUSED with "last real input NEVER". A user's click or key on the
// page now counts, exactly as a tap on the WebView does on a device. A
// synthesized CGEvent click is indistinguishable here, as it already is for the
// canvas path; harnesses keep MACAPK_EXTERNAL_URLS=0.
static bool WvRealInputEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_REAL_INPUT");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}
static void WvNoteRealInput() {
    if (!WvRealInputEnabled()) return;
    AttachedEnv ae;
    if (!ae.env || !g_bridge_cls || !g_mid_real_input) return;
    ae.env->CallStaticVoidMethod(g_bridge_cls, g_mid_real_input);
    if (ae.env->ExceptionCheck()) { ae.env->ExceptionDescribe(); ae.env->ExceptionClear(); }
}

@implementation MacApkWKWebView
- (void)mouseDown:(NSEvent*)e      { WvNoteRealInput(); [super mouseDown:e]; }
- (void)rightMouseDown:(NSEvent*)e { WvNoteRealInput(); [super rightMouseDown:e]; }
- (void)otherMouseDown:(NSEvent*)e { WvNoteRealInput(); [super otherMouseDown:e]; }
- (void)keyDown:(NSEvent*)e        { WvNoteRealInput(); [super keyDown:e]; }

// Cmd+X/C/V/A/Z and Cmd+Shift+Z inside a page. AppKit never turns those keys
// into editing commands by itself: a WKWebView (like Safari's) copies and
// pastes because the app's Edit menu carries the key equivalents and sends
// cut:/copy:/paste:/selectAll:/undo:/redo: down the responder chain. Our menu
// bar has no Edit menu (App, View, Controls), so a page got nothing for Cmd+V
// while the right-click menu's Paste (which sends the same paste:) worked.
// Answering here, only when THIS web view holds focus, leaves the Android key
// path (MacApkMetalView's Command-chord handling, see host_keymap.h) and every
// other window untouched; an Edit menu would intercept those chords for the
// Android view tree as well. The page still receives the clipboard events
// (paste, copy, cut) the command fires; it does not see a keydown for these
// six chords. MACAPK_WEBVIEW_EDIT_KEYS=0 restores the old behaviour.
static bool WvEditKeysEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_EDIT_KEYS");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}
- (BOOL)performKeyEquivalent:(NSEvent*)e {
    if (WvEditKeysEnabled() && e.type == NSEventTypeKeyDown) {
        NSResponder* fr = self.window.firstResponder;
        BOOL focused = (fr == self) ||
            ([fr isKindOfClass:[NSView class]] && [(NSView*)fr isDescendantOf:self]);
        NSEventModifierFlags mods = e.modifierFlags &
            (NSEventModifierFlagCommand | NSEventModifierFlagShift |
             NSEventModifierFlagOption | NSEventModifierFlagControl);
        NSString* key = e.charactersIgnoringModifiers.lowercaseString;
        SEL sel = nullptr;
        if (focused && key.length == 1) {
            unichar c = [key characterAtIndex:0];
            if (mods == NSEventModifierFlagCommand) {
                if (c == 'x') sel = @selector(cut:);
                else if (c == 'c') sel = @selector(copy:);
                else if (c == 'v') sel = @selector(paste:);
                else if (c == 'a') sel = @selector(selectAll:);
                else if (c == 'z') sel = @selector(undo:);
            } else if (mods == (NSEventModifierFlagCommand | NSEventModifierFlagShift)) {
                if (c == 'z') sel = @selector(redo:);
            }
        }
        if (sel) {
            WvNoteRealInput();
            if ([self tryToPerform:sel with:self]) return YES;
        }
    }
    return [super performKeyEquivalent:e];
}

- (NSView*)hitTest:(NSPoint)point {
    for (NSValue* v in self.macapkOccluders) {
        if (NSPointInRect(point, v.rectValue)) return nil;
    }
    return [super hitTest:point];
}

// AppKit sends this to every subview when an ANCESTOR changes window, so it is
// how a WKWebView learns that its host MTKView was taken out of the window
// (CocoaSetWindowContentView removes every letterbox subview on a content
// swap). Only the transition in-a-window -> no-window while still parented
// counts; our own re-hosting is excluded. See WvHostFollowEnabled for why this
// only TELLS the Java WebView instead of moving the page here.
- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    BOOL was = self.macapkInWindow;
    self.macapkInWindow = (self.window != nil);
    if (!was || self.window || !self.superview || g_wv_rehosting) return;
    if (self.macapkViewId <= 0 || !WvHostFollowEnabled()) return;
    std::fprintf(stderr,
                 "[I] webview: id=%d host view=%p left the window; the WebView "
                 "re-places itself on its next draw\n",
                 self.macapkViewId, (__bridge void*)self.superview);
    PostEvent(self.macapkViewId, EV_HOST_LOST, nil, nil, nil, 0);
}
@end

// The painted page base under a WKWebView whose own base is transparent (see
// WvApplyBase). A sibling NSView kept directly below the WKWebView, same frame,
// same zPosition, same hidden state. It is a view of our own because WebKit
// owns the WKWebView's layer colour: WebViewImpl::updateLayer (WebKit
// Source/WebKit/UIProcess/mac/WebViewImpl.mm:2139, main @5f66068c) sets
//   [m_view layer].backgroundColor = drawsBackground() ? backgroundColor().CGColor
//                                                      : kCGColorClear;
// on every display, and MEASURED 2026-09-26 on macOS 26.5.2: a red
// layer.backgroundColor put on the WKWebView after _setDrawsBackground:NO read
// back clear after the next load/resize. It takes no clicks: the WKWebView above
// it answers them, and where that WKWebView defers to the Android view tree (an
// occluder) this view must defer too.
@interface MacApkWvBaseView : NSView
@end

@implementation MacApkWvBaseView
- (NSView*)hitTest:(NSPoint)point {
    (void)point;
    return nil;
}
// Layer-backed with a colour and no drawing: AppKit must never replace the
// layer's contents or colour behind our back.
- (BOOL)wantsUpdateLayer { return YES; }
- (void)updateLayer {}
@end

// Receives the page's scroll reports without the userContentController
// retaining the holder (it retains its message handlers strongly).
@interface MacApkWvScrollProxy : NSObject <WKScriptMessageHandler>
@property (nonatomic, weak) id<WKScriptMessageHandler> target;
@end
@implementation MacApkWvScrollProxy
- (void)userContentController:(WKUserContentController*)ucc
      didReceiveScriptMessage:(WKScriptMessage*)message {
    [self.target userContentController:ucc didReceiveScriptMessage:message];
}
@end

@interface MacApkWvHolder : NSObject <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler>
// Rects (Android surface px) the app's own views cover over this WebView.
@property (nonatomic, copy) NSArray<NSValue*>* occluderSurfaceRects;
@property (nonatomic) int viewId;
@property (nonatomic, strong) WKWebView* wv;
@property (nonatomic) BOOL jsEnabled;
// Rect in Android surface px + the surface dims it is relative to.
@property (nonatomic) CGRect surfaceRect;
@property (nonatomic) int surfaceW;
@property (nonatomic) int surfaceH;
@property (nonatomic) BOOL visibleWanted;
@property (nonatomic) BOOL attachedToHost;
// The URL of a load WE started (loadUrl/reload/back/forward). Android does
// NOT report app-initiated loads through shouldOverrideUrlLoading; clearing
// on first main-frame match keeps redirects reportable (API-24 semantics).
// v1.0.1642: hardening, a small time-bounded FIFO instead of one slot. With
// one slot, a second app-initiated load issued before WebKit asked policy for
// the first overwrote it, and the first load was then offered to
// shouldOverrideUrlLoading as if the user had clicked it. Entries hold the URL
// as WebKit will ASK it (wv.URL right after the load call is already the
// normalized form: 'https://EXAMPLE.org' is asked as 'https://example.org/'),
// and expire after kPendingProgrammaticTtl so a load WebKit never asks about
// cannot later misfile a real user click as app-initiated.
@property (nonatomic, strong) NSMutableArray<NSString*>* pendingProgrammaticUrls;
@property (nonatomic, strong) NSMutableArray<NSNumber*>* pendingProgrammaticTimes;
- (void)notePendingProgrammaticUrl:(NSString*)url;
- (BOOL)consumePendingProgrammaticUrl:(NSString*)url;
- (void)reissueAssetNavigation:(NSString*)assetUrl;
// Last url delivered through didFinishNavigation:. The same-document check in the
// URL KVO compares against it; see WvSameDocEnabled().
@property (nonatomic, strong) NSString* lastCommittedUrl;
// Who may reach app assets (see -topDocumentMayLoadAppAssets). The main-frame
// document WebKit last COMMITTED: its URL scheme, and whether it is the one
// document a loadDataWithBaseURL() granted local-resource access to.
// grantNavigation is that load's WKNavigation until it commits.
@property (nonatomic, copy) NSString* committedTopScheme;
@property (nonatomic) BOOL committedTopGranted;
@property (nonatomic, strong) WKNavigation* grantNavigation;
// READ GRANT (2026-10-02, MACAPK_WEBVIEW_FILE_URL_ACCESS): the app's android root,
// set when WebSettings.setAllowFileAccess(true); nil = no file access asked for.
// loadHTMLString:baseURL:file:... gives the WebContent process NO sandbox
// extension (WebKit only "assumes" read access for the base directory), so a
// loadDataWithBaseURL("file:///", ...) page could read none of the app's own
// files. -loadFileURL:allowingReadAccessToURL: is the one API that issues the
// extension, so the first such data load is preceded by one hidden navigation to
// a blank file inside the root; its callbacks never reach the app.
@property (nonatomic, copy) NSString* readGrantRoot;
@property (nonatomic) BOOL readGranted;
@property (nonatomic, strong) WKNavigation* readGrantNav;
@property (nonatomic, copy) NSString* readGrantUrl;
@property (nonatomic, copy) dispatch_block_t afterReadGrant;
@property (nonatomic) NSUInteger loadGen;
- (void)endReadGrant:(BOOL)ok;
- (BOOL)topDocumentMayLoadAppAssets;
// MACAPK_WEBVIEW_TRACE page-state sampler (see WvInstallTraceScript): nil when off.
@property (nonatomic, strong) dispatch_source_t traceTimer;
@property (nonatomic, strong) NSString* lastTraceState;
// The page base colour (WebView.setBackgroundColor), ARGB. AOSP's default is
// WHITE (AwContents.java:500); NativeCreate sets it, since a fresh holder's 0
// would read as TRANSPARENT. See WvApplyBase.
@property (nonatomic) uint32_t baseColor;
// What WvApplyBase last applied: 0 = WebKit's own opaque base (drawsBackground
// YES, the default and the pre-change state), 1 = transparent page base
// (drawsBackground NO) over baseView.
@property (nonatomic) int baseMode;
// The painted base (MacApkWvBaseView) while mode 1 has a visible colour, else nil.
@property (nonatomic, strong) NSView* baseView;
// (mode << 32 | colour) of the last base line logged; -1 = none yet.
@property (nonatomic) int64_t baseLogKey;
// MACAPK_WEBVIEW_BACKDROP: the window's own pixels beneath the WebView as a flat
// ARGB colour (WebView.macapkWindowBackdrop), valid when backdropKnown.
@property (nonatomic) uint32_t backdrop;
@property (nonatomic) BOOL backdropKnown;
@end

namespace {

NSMutableDictionary<NSNumber*, MacApkWvHolder*>* g_holders = nil;  // main-thread only
// Pending decidePolicy completion handlers, keyed by decisionId (main-thread).
NSMutableDictionary<NSNumber*, void (^)(WKNavigationActionPolicy)>* g_nav_decisions = nil;
// Pending JS-dialog completion handlers, keyed by resultId (main-thread).
// Values: alert -> void(^)(void); confirm -> void(^)(BOOL);
//         prompt -> void(^)(NSString*). Stored type-erased with a tag.
NSMutableDictionary<NSNumber*, id>* g_js_dialogs = nil;
std::atomic<int> g_next_decision_id{1};
std::atomic<int> g_next_dialog_id{1};

// ---- MACAPK_WEBVIEW_TRACE (diagnostic, default OFF) ------------------------
// Reading what a remote site actually served is otherwise a screenshot, which
// costs ~1k tokens and yields an impression rather than a fact -- and for a
// WKWebView it is worse than that, because the web content is a native NSView
// SIBLING of the canvas, so the runtime's own frame dumps (rendercheck /
// frame-score, which read the ViewCanvasDriver bitmap) CANNOT see it at all.
// With the trace on, every navigation and the finished page's title / URL /
// effective navigator.userAgent / visible innerText land in the runtime log as
// greppable text. App-agnostic and env-gated: nothing runs when it is off.
// v1.0.1643, default ON: composite the WKWebView BELOW the widget canvas (the
// app's views over a WebView become visible, as on Android) and let Java cut a
// hole where it is. MACAPK_WEBVIEW_Z_BELOW=0 restores the old slot (1.5, above
// every app view) for an A/B. Java reads the same variable (WebView.Z_BELOW).
bool WvZBelow() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_Z_BELOW");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// ---- The host view: the canvas overlay's windowless-Path-A rule -----------
// (2026-09-26, MACAPK_WEBVIEW_HOST_ONSCREEN, default ON; =0 returns
// CocoaWebViewHostView() verbatim, the pre-change host.)
//
// THE BUG. CocoaWebViewHostView() answers g_last_metal_view whenever it is
// non-nil. That is the MTKView Activity.setContentView creates for a tree with
// NO native surface (Path A). When a SurfaceView / GLSurfaceView joins that
// tree later, Activity.macapkMaybeRewireNativeSurface hands the WINDOW to the
// SurfaceView's MTKView and the Path-A view leaves the window -- but
// g_last_metal_view still points at it. cocoa_bridge.mm learned this at
// v1.0.997 (PlayTube): the canvas overlay (MacapkCanvasOverlayForPresent) and
// the video plane both skip a Path-A view whose `window == nil` and fall back
// to the view that is actually drawing. The WKWebView host predates that rule
// (v1.0.963) and never got it, so on such an app every WebView was added to a
// view with no window: WebKit loaded, ran and "finished" the page into a layer
// nothing composites.
//
// MEASURED on Candy Crush (tablet A/B run 2026-09-26 03:16, v1.0.1648 run.log):
//   L4325  setRenderer view=0x8437e1900 target=ViewCanvasDriver   (Path A)
//   L5631  canvas overlay (re)attached to MTKView=0x8437e1400 host=gl
//          (only reachable with g_last_metal_view non-nil when its window is nil)
//   L22523 webview: id=2 attached to host view=0x8437e1900        (the orphan)
//   L23966 Overlay addView PopupDialog$PopupDecorView               (King's ToU)
// and the Terms of Use card never appeared, while every wake-kick names
// 0x8437e1400 as the window's one MTKView. The page had loaded: King only
// opens its PopupDialog on PageState.LOADED (Popup$5.run bci 53-83), which
// Popup$CustomWebViewClient.onPageFinished sets only after getProgress() >= 100
// and getOriginalUrl().equals(loadingTargetUrl) (bci 122-193).
//
// THE RULE, identical to cocoa_bridge.mm's: a host that is in a window is the
// host (every pure Path-A app, e.g. Wikipedia, is unchanged). A host with no
// window is not a host, it is a hole; use the MTKView that IS in the runtime
// window instead. cocoa_bridge.mm picks g_active_render_view /
// g_last_gl_metal_view there, which this file cannot see; after a rewire the
// window holds exactly that one MTKView (CocoaSetWindowContentView removes
// every other letterbox subview), so it is found by walking the window. More
// than one candidate has not been observed; the frontmost visible one wins,
// the one the user sees. With nothing better in the window the old answer
// stands, so this can only move a WebView from a view nobody sees to one they
// do. Main thread only (every caller already is).
//
// ONE RULE, NOT TWO (open follow-up). The page must sit in the SAME view as the
// canvas overlay: WebView.onDraw punches the hole the page shows through in that
// overlay, which only works when the WKWebView (zPosition 0.25) and the overlay
// layer (0.5 / 1.0) are siblings. cocoa_bridge.mm drops a windowless Path-A view
// only under MacapkRewireKeepCanvas() (MACAPK_REWIRE_KEEP_CANVAS), so this
// reads the same switch the same way: with that gate off the overlay stays on
// the orphan and so does the page. What is still duplicated is the choice of
// the replacement (its g_active_render_view vs this window walk; the same view
// after a rewire). The single-rule fix is in cocoa_bridge.mm: give
// CocoaWebViewHostView() the windowless check itself, before its GL fallback,
// and this function becomes a pass-through.
bool WvRewireKeepCanvas() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_REWIRE_KEEP_CANVAS");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}

bool WvHostOnscreenEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_HOST_ONSCREEN");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// ---- Who may MOVE a WKWebView to a new host (2026-09-26) ------------------
// (MACAPK_WEBVIEW_HOST_FOLLOW, default ON; =0 restores the previous two
// behaviours exactly: no EV_HOST_LOST, and the host-frame observer re-hosts
// every holder.)
//
// WvHostView() answers where a page SHOULD be; this decides which pages get
// moved there, and when. Two defects, one rule.
//
// 1. A page never followed its own window across a rewire. When
//    Activity.macapkMaybeRewireNativeSurface hands the window to a late
//    SurfaceView, CocoaSetWindowContentView (cocoa_bridge.mm) removes every
//    letterbox subview, sets the new MTKView's frame and only THEN adds it.
//    So the NSViewFrameDidChangeNotification the observer below waits for
//    arrives while the new view is not in the window yet, WvHostView() still
//    answers the orphan, and nothing moves; a later same-size refit posts
//    nothing, and WebView.macapkPushFrame is change-gated, so an unchanged
//    rect never pushes again. The page stayed in a view no window shows.
//    (An offline driver that set the frame after addSubview certified this
//    as working; the real order is the one debug/wvtest/popup_gate.sh now
//    replays.)
// 2. The observer moved pages that are not on screen on Android either. It
//    re-hosted EVERY holder whenever the host's frame changed, including the
//    WebView of an activity that is stopped but not destroyed: our runtime
//    sends no onWindowVisibilityChanged(GONE) on stop (View.java
//    dispatchWindowVisibilityChanged is a no-op) and WebView hides its native
//    view only in onDetachedFromWindow, which runs at destroy. Such a page
//    stayed on its orphaned view -- invisible by accident -- until the next
//    window resize carried it onto the new activity's view: on a GL host, where
//    the canvas overlay is transparent, it showed over the game (at zPosition
//    0.25 it is above the ANGLE layer); on a Path-A host it stayed hidden under
//    the opaque canvas and still took the clicks in its rect. WvHostView's
//    windowless-host substitution made the GL case newly reachable.
//
// THE RULE. A page moves to a new host only when its Java WebView pushes a
// frame, i.e. from WebView.draw / onVisibilityChanged / attach, the calls AOSP
// makes for a window that is on screen. When a WKWebView's host leaves the
// window it posts EV_HOST_LOST; the Java WebView re-arms its change gate and
// invalidates, so a WebView whose window is still being drawn re-places itself
// on its very next draw. A stopped activity's tree is no longer drawn (its
// canvas MTKView's driver is released at the next setContentView), so its page
// stays where it was, hidden with its view. The observer keeps its real job:
// re-placing pages already ON the host when the host's own geometry changes
// (window resize, fullscreen, letterbox refit).
//
// This makes no claim about window visibility: when the runtime learns to
// dispatch dispatchWindowVisibilityChanged on stop/restart (AOSP ViewRootImpl
// does), WebView should hide on GONE as well, and nothing here needs to change.
bool WvHostFollowEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_HOST_FOLLOW");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// The frontmost (last in back-to-front subview order) visible, non-empty
// MTKView at or under `v`, or nil.
NSView* WvFindOnscreenMetalView(NSView* v, int depth) {
    // A WKWebView's own subtree is WebKit's; there is no MTKView in it.
    if (!v || depth > 16 || v.hidden || [v isKindOfClass:[WKWebView class]]) return nil;
    NSView* best = nil;
    if ([v isKindOfClass:[MTKView class]] && v.bounds.size.width > 0
            && v.bounds.size.height > 0) {
        best = v;
    }
    for (NSView* sub in v.subviews) {
        NSView* f = WvFindOnscreenMetalView(sub, depth + 1);
        if (f) best = f;
    }
    return best;
}

NSView* WvHostView() {
    NSView* base = (__bridge NSView*)CocoaWebViewHostView();
    if (!WvHostOnscreenEnabled() || !WvRewireKeepCanvas()) return base;
    if (base && base.window) return base;
    NSWindow* w = (__bridge NSWindow*)macapk::CocoaGetPlaceholderWindow();
    NSView* onscreen = w ? WvFindOnscreenMetalView(w.contentView, 0) : nil;
    if (!onscreen) return base;
    // Say so once per change: this substitution is invisible otherwise, and it
    // is exactly the question a "the page loaded but nothing shows" report asks.
    static __weak NSView* s_lastBase = nil;
    static __weak NSView* s_lastOnscreen = nil;
    if (s_lastBase != base || s_lastOnscreen != onscreen) {
        s_lastBase = base;
        s_lastOnscreen = onscreen;
        std::fprintf(stderr,
                     "[I] webview: host view=%p has no window (a Path-A view the window "
                     "was rewired away from); hosting on the window's MTKView=%p\n",
                     (__bridge void*)base, (__bridge void*)onscreen);
    }
    return onscreen;
}

bool WvTraceEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_TRACE");
        return v && *v && std::strcmp(v, "0") != 0;
    }();
    return on;
}

// SAME-DOCUMENT NAVIGATION DELIVERY (MACAPK_WEBVIEW_SAMEDOC=0 disables).
//
// AOSP contract, from Chromium's AwWebContentsObserver.didFinishNavigation
// (android_webview/java/src/org/chromium/android_webview/AwWebContentsObserver.java):
//   * onPageStarted is skipped -- the guard is `if (!navigation.isSameDocument() ...)`;
//   * onPageFinished IS posted, under
//       `if (client != null && navigation.isPrimaryMainFrameFragmentNavigation())`,
//     with the source comment "Note fragment navigations do not have a matching
//     onPageStarted.";
//   * doUpdateVisitedHistory is posted for every committed navigation.
// So on Android a fragment-only URL change (a `#close` appended by script, say)
// delivers doUpdateVisitedHistory + onPageFinished, and no onPageStarted.
//
// WKWebView routes those through neither didFinishNavigation: nor
// didStartProvisionalNavigation: -- only the `URL` KVO fires -- so we posted
// EV_URL_CHANGED alone and the guest's WebViewClient.onPageFinished never ran.
//
// WHAT THAT COST (2026-09-04): Aurora Store's Google sign-in completes by having
// its WebViewClient re-read the cookie jar inside onPageFinished. Google's
// EmbeddedSetup flow ends by appending `#close` to the consent URL -- a
// same-document navigation -- to tell the host to take over. We delivered no
// page-finished for it, the app never re-read the jar, and the sign-in sat on
// its spinner forever. Every earlier step in that flow is a form POST, which
// commits normally, which is why only the very last hop broke.
//
// The gate exists so the change can be A/B'd against the same build; it is ON by
// default because the AOSP behaviour is the correct one.
bool WvSameDocEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_SAMEDOC");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// Flatten to one log line: the point is grep-ability, not fidelity.
NSString* WvFlatten(NSString* s, NSUInteger cap) {
    if (!s) return @"";
    NSString* f = [[[s stringByReplacingOccurrencesOfString:@"\n" withString:@" | "]
                      stringByReplacingOccurrencesOfString:@"\r" withString:@" "]
                      stringByReplacingOccurrencesOfString:@"\t" withString:@" "];
    if (f.length > cap) f = [[f substringToIndex:cap] stringByAppendingString:@"...<cut>"];
    return f;
}

// A URL for an UNCONDITIONAL log line: the query and fragment are dropped. A
// failed navigation is often an OAuth redirect to a scheme WebKit cannot open
// (myapp://cb?code=...), and the log is a plain file on disk; the page's path is
// what identifies the failure. (The opt-in TRACE lines keep full URLs.)
NSString* WvLogUrl(NSString* url) {
    if (!url) return @"";
    NSRange r = [url rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"?#"]];
    NSString* u = r.location == NSNotFound ? url
                : [[url substringToIndex:r.location] stringByAppendingString:@"?<redacted>"];
    return WvFlatten(u, 400);
}

// MACAPK_WEBVIEW_TRACE, part 2: what the PAGE is doing between navigations.
// The FINISH dump above reads a page once, when it loads. That could not explain
// the Aurora Store Google sign-in freeze of 2026-09-04: the consent page loaded
// and dumped fine, then its "I agree" button stayed inert and pale, and NOTHING
// reached the log -- no navigation, no dialog, no event -- because a page stuck
// inside its own script emits none of those. So with the trace on, every page
// also gets a document-start hook that records script errors, unhandled promise
// rejections, console.error, and a requestAnimationFrame tick count (a page whose
// rAF counter stops is not being painted; a page whose counter runs but whose
// buttons stay disabled is stuck in its own logic), and a 2 s main-thread timer
// reads that state plus readyState / visibilityState / running animations /
// the first buttons' disabled flags, printed as a `[T] webview: id=N STATE` line.
// Generic: no site knowledge, no app name, and zero cost when the flag is off.
static NSString* const kWvTraceHookJS =
    @"(function(){if(window.__macapkT)return;var T=window.__macapkT={errs:[],raf:0};"
     "window.addEventListener('error',function(e){T.errs.push(String(e.message)+' @'+(e.filename||'')+':'+(e.lineno||0));});"
     "window.addEventListener('unhandledrejection',function(e){var r=e.reason;T.errs.push('rejection: '+String(r&&(r.stack||r.message)||r).slice(0,300));});"
     "var ce=console.error;console.error=function(){try{T.errs.push('console.error: '+Array.prototype.slice.call(arguments).map(String).join(' ').slice(0,300));}catch(_){}return ce.apply(console,arguments);};"
     "T.net=[];var pushN=function(e){T.net.push(e);if(T.net.length>40)T.net.shift();};"
     "var of=window.fetch;if(of){window.fetch=function(u,o){var e={u:String((u&&u.url)||u).slice(0,160),m:(o&&o.method)||'GET',t:Date.now(),d:0,s:0};pushN(e);"
     "return of.apply(this,arguments).then(function(r){e.d=1;e.s=r.status;return r;},function(x){e.d=1;e.s=-1;throw x;});};}"
     "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
     "XMLHttpRequest.prototype.open=function(m,u){this.__mk={u:String(u).slice(0,160),m:m,t:Date.now(),d:0,s:0};return oo.apply(this,arguments);};"
     "XMLHttpRequest.prototype.send=function(){var e=this.__mk;if(e){pushN(e);this.addEventListener('loadend',function(){e.d=1;e.s=this.status;});}return os.apply(this,arguments);};"
     "(function tick(){T.raf++;requestAnimationFrame(tick);})();})();";
static NSString* const kWvTraceStateJS =
    @"(function(){var T=window.__macapkT||{errs:[],raf:-1};"
     "var b=[].slice.call(document.querySelectorAll('button')).slice(0,10).map(function(x){return ((x.innerText||'').trim().slice(0,18))+(x.disabled?'[D]':'');});"
     "var a=-1,an=[];try{var L=document.getAnimations();a=L.length;an=L.slice(0,6).map(function(x){var t=x.effect&&x.effect.target;return (t?t.tagName+'.'+String(t.className||'').slice(0,28):'?')+':'+x.playState;});}catch(e){}"
     "var now=Date.now(),net=(T.net||[]);var pend=net.filter(function(e){return !e.d;}).map(function(e){return e.m+' '+e.u+' +'+(now-e.t)+'ms';}).slice(0,6);"
     "var last=net.slice(-3).map(function(e){return e.m+' '+e.u.slice(0,90)+' -> '+(e.d?e.s:'pending');});"
     "var busy=!!document.querySelector('[aria-busy=\"true\"]');"
     "return JSON.stringify({rs:document.readyState,vis:document.visibilityState,raf:T.raf,anims:a,an:an,busy:busy,pend:pend,netN:net.length,last:last,errs:T.errs.slice(-4),btn:b,"
     "ae:document.activeElement?document.activeElement.tagName:'',p:location.pathname});})()";

void WvInstallTraceScript(WKUserContentController* ucc) {
    if (!WvTraceEnabled() || !ucc) return;
    WKUserScript* us = [[WKUserScript alloc] initWithSource:kWvTraceHookJS
                                              injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                           forMainFrameOnly:YES];
    [ucc addUserScript:us];
}

// ---- console.* -> WebChromeClient.onConsoleMessage (2026-10-16) -----------
// (MACAPK_WEBVIEW_CONSOLE, default ON; =0 installs neither the script nor the
// message handler, the pre-change behaviour where no console message ever
// reached the app.)
//
// AOSP: every console message a page logs reaches
// WebChromeClient.onConsoleMessage(ConsoleMessage) (Chromium
// AwWebContentsDelegate::DidAddMessageToConsole -> AwContentsClient
// .onConsoleMessage), and when the app does not handle it Chromium writes it to
// logcat as `[INFO:CONSOLE(line)] "message", source: url (line)`. Hybrid apps
// lean on this: Cordova's SystemWebChromeClient logs every page message through
// it, which is the only place a Cordova app's JS errors show up. WKWebView has
// no console delegate, so a document-start script wraps console.* and posts
// [level, text, source, line] to the macapkConsole handler. Levels follow
// Chromium's mapping onto ConsoleMessage.MessageLevel (TIP 0, LOG 1, WARNING 2,
// ERROR 3, DEBUG 4): log/info -> LOG, warn -> WARNING, error/assert -> ERROR,
// debug/trace -> DEBUG. The source line is the caller's frame from the stack.
// Uncaught errors and unhandled rejections are reported as Chromium reports
// them ("Uncaught <message>", ERROR). All frames, like Chromium.
bool WvConsoleEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_CONSOLE");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}
static NSString* const kWvConsoleHookJS =
    @"(function(){if(window.__macapkConsole)return;window.__macapkConsole=1;"
     "var H=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.macapkConsole;if(!H)return;"
     "function fmt(v){if(typeof v==='string')return v;if(v instanceof Error)return String(v);"
       "try{var j=JSON.stringify(v);if(j!==undefined&&typeof v==='object')return j;}catch(_){}return String(v);}"
     "function where(){var st=String((new Error()).stack||'').split('\\n'),k=0;"
       "for(;k<st.length;k++)if(st[k].indexOf('__macapkConsoleWrap')>=0)break;"
       "for(k++;k<st.length;k++){var m=/(\\S+?):(\\d+):\\d+$/.exec(st[k].replace(/^[^@]*@/,''));"
         "if(m)return[m[1],+m[2]];}return['',0];}"
     "function stk(args,err){var e=err;for(var k=0;!e&&k<args.length;k++)if(args[k] instanceof Error)e=args[k];"
       "return e&&e.stack?String(e.stack).slice(0,4000):'';}"
     "function post(l,args,src,line,err){try{var t=Array.prototype.map.call(args,fmt).join(' ');"
       "if(src===undefined){var w=where();src=w[0];line=w[1];}H.postMessage([l,t,String(src||''),line|0,stk(args,err)]);}catch(_){}}"
     "var map={log:1,info:1,warn:2,error:3,debug:4,trace:4};"
     "Object.keys(map).forEach(function(n){var o=console[n];if(typeof o!=='function')return;"
       "var __macapkConsoleWrap=function(){post(map[n],arguments);return o.apply(console,arguments);};"
       "console[n]=__macapkConsoleWrap;});"
     "var oa=console.assert;if(typeof oa==='function')console.assert=function __macapkConsoleWrap(c){"
       "if(!c)post(3,['Assertion failed:'].concat(Array.prototype.slice.call(arguments,1)));return oa.apply(console,arguments);};"
     "window.addEventListener('error',function(e){post(3,['Uncaught '+(e.message||e)],e.filename||'',e.lineno||0,e.error);});"
     "window.addEventListener('unhandledrejection',function(e){var r=e.reason;"
       "post(3,['Uncaught (in promise) '+fmt(r&&r.stack?r.stack:r)],'',0,r instanceof Error?r:null);});"
     "})();";

// ---- XMLHttpRequest DONE exactly once (2026-10-17) -------------------------
// (MACAPK_WEBVIEW_XHR_DONE_ONCE, default ON; =0 installs nothing.)
//
// Chromium dispatches readystatechange synchronously at each state change, so a
// handler sees readyState 4 exactly once per send(). WebKit queues XHR events
// and, while the page's main thread is busy, delivers the queued ones late: by
// the time they run, readyState already reads 4, so `if (xhr.readyState === 4)`
// runs its DONE branch two to four times for one request. Measured live in DOFUS
// Touch during its first map load (trusted readystatechange events, one open, one
// send, sequence 4:0,4:0,4:0,4:0 on a local JSON); its loaders then called their
// completion twice ("Callback was already called.", a template built before its
// atlas loaded -> "i.element"), the map never finished loading and the game drew
// only its HTML interface over a black map. An idle page shows 1,2,3,4 once (same
// files, same session), which is why nothing else had tripped it.
// So, for every XHR, a capture listener on the request itself (it runs before
// the onreadystatechange attribute and bubble listeners at the target) lets the
// first readystatechange that sees DONE through and stops any later one for
// that send(), which is exactly what a Chromium page can observe. open() re-arms
// it. Events for states before DONE are untouched.
bool WvXhrDoneOnceEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_XHR_DONE_ONCE");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}
static NSString* const kWvXhrDoneOnceJS =
    @"(function(){var X=window.XMLHttpRequest;if(!X||X.prototype.__macapkDoneOnce)return;"
     "var P=X.prototype;P.__macapkDoneOnce=1;var oo=P.open,os=P.send;"
     "function g(ev){if(this.readyState!==4)return;if(this.__macapkDone){ev.stopImmediatePropagation();return;}this.__macapkDone=1;}"
     "P.open=function(){this.__macapkDone=0;if(!this.__macapkG){this.__macapkG=1;"
       "this.addEventListener('readystatechange',g,true);}return oo.apply(this,arguments);};"
     "P.send=function(){this.__macapkDone=0;return os.apply(this,arguments);};})();";

void WvInstallXhrDoneOnceScript(WKUserContentController* ucc) {
    if (!WvXhrDoneOnceEnabled() || !ucc) return;
    WKUserScript* us = [[WKUserScript alloc] initWithSource:kWvXhrDoneOnceJS
                                              injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                           forMainFrameOnly:NO];
    [ucc addUserScript:us];
}

void WvInstallConsoleScript(WKUserContentController* ucc) {
    if (!WvConsoleEnabled() || !ucc) return;
    WKUserScript* us = [[WKUserScript alloc] initWithSource:kWvConsoleHookJS
                                              injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                           forMainFrameOnly:NO];
    [ucc addUserScript:us];
}

// Shared per-app website data store: the Android analog of
// /data/data/<pkg>/app_webview (one cookie jar + storage per APP, shared by
// all WebViews AND CookieManager). Identifier is derived from the package
// name so it persists across runs, keyed per app.
WKWebsiteDataStore* g_data_store = nil;   // main-thread only
NSString* g_data_store_pkg = nil;

WKWebsiteDataStore* EnsureDataStore(NSString* pkg) {
    if (g_data_store) return g_data_store;
    if (!pkg.length) pkg = @"default";
    // Stable UUID from the package name: FNV-1a over the bytes into 16 bytes
    // (two 64-bit lanes with different offsets), then force RFC-4122 v4
    // variant bits so the identifier is always a UUID WebKit accepts.
    const char* s = pkg.UTF8String;
    uint64_t h1 = 1469598103934665603ull, h2 = 1099511628211ull ^ 0x9e3779b97f4a7c15ull;
    for (const char* p = s; *p; ++p) {
        h1 = (h1 ^ (uint8_t)*p) * 1099511628211ull;
        h2 = (h2 ^ (uint8_t)*p) * 0x100000001b3ull;
    }
    uuid_t u;
    memcpy(u, &h1, 8);
    memcpy(u + 8, &h2, 8);
    u[6] = (u[6] & 0x0f) | 0x40;  // version 4
    u[8] = (u[8] & 0x3f) | 0x80;  // variant 10xx
    NSUUID* ident = [[NSUUID alloc] initWithUUIDBytes:u];
    if (@available(macOS 14.0, *)) {
        @try {
            g_data_store = [WKWebsiteDataStore dataStoreForIdentifier:ident];
        } @catch (NSException* ex) {
            std::fprintf(stderr, "[W] webview: dataStoreForIdentifier threw (%s); "
                         "falling back to defaultDataStore\n",
                         ex.reason.UTF8String ?: "?");
            g_data_store = [WKWebsiteDataStore defaultDataStore];
        }
    } else {
        g_data_store = [WKWebsiteDataStore defaultDataStore];
    }
    g_data_store_pkg = pkg;
    std::fprintf(stderr, "[I] webview: data store ready pkg=%s uuid=%s\n",
                 pkg.UTF8String, ident.UUIDString.UTF8String);
    return g_data_store;
}

MacApkWvHolder* HolderFor(int viewId) {
    return g_holders[@(viewId)];
}

// The page -> Android scroll report (see NativeCreate). A named function
// because NativeSetInjectionScript replaces the whole user-script set and must
// put this back, as it does the trace hook.
void WvInstallScrollScript(WKUserContentController* ucc) {
    WKUserScript* us = [[WKUserScript alloc]
        initWithSource:
          @"(function(){if(window.__macapkScroll)return;window.__macapkScroll=1;"
               "var p=false,lx=-1,ly=-1,lh=-1;"
               "function s(){p=false;var d=document.documentElement;"
               "var x=Math.round(window.scrollX),y=Math.round(window.scrollY),"
               "h=Math.round(d?d.scrollHeight:0);"
               "if(x===lx&&y===ly&&h===lh)return;lx=x;ly=y;lh=h;"
               "try{window.webkit.messageHandlers.macapkScroll.postMessage([x,y,h]);}catch(e){}}"
               "function q(){if(!p){p=true;requestAnimationFrame(s);}}"
               "window.addEventListener('scroll',q,{passive:true});"
               "window.addEventListener('resize',q);"
               "try{new ResizeObserver(q).observe(document.documentElement);}catch(e){}"
               "q();})();"
         injectionTime:WKUserScriptInjectionTimeAtDocumentEnd
      forMainFrameOnly:YES];
    [ucc addUserScript:us];
}

// ---- The page base colour: WebView.setBackgroundColor (2026-09-26) ---------
// (MACAPK_WEBVIEW_BASE_COLOR, default ON; =0 is the exact pre-change path: Java
// calls View.setBackgroundColor and never reaches native, and this file never
// touches drawsBackground or creates a base view.)
//
// AOSP. WebView.setBackgroundColor does NOT call View's: android14-release
// frameworks/base core/java/android/webkit/WebView.java:3033
//     mProvider.getViewDelegate().setBackgroundColor(color);
// and Chromium's AwContents.setBackgroundColor (AwContents.java:2840) stores it
// (default WHITE, :500) and hands it to aw_contents.cc:1181
// AwContents::SetBackgroundColor -> WebContents::SetPageBaseBackgroundColor: the
// colour painted UNDER a document that has no background of its own. Apps use
// it for two things: setBackgroundColor(0) to let the page's translucent pixels
// show what is under the WebView (ad and consent overlays, King's Terms of Use
// card over the game, subtitle WebViews over video), and an opaque colour so a
// page without a background matches the app theme.
//
// macOS. WebCore has exactly this knob (LocalFrameView::updateBackgroundRecursively,
// WebCore/page/LocalFrameView.cpp:4456, setBaseBackgroundColor + setTransparent),
// but WKWebView on macOS reaches it only through the SPI -_setDrawsBackground:
// (WebViewImpl::setDrawsBackground, WebViewImpl.mm:1577: NO sets the page
// background to Color::transparentBlack, YES restores WebKit's own). No public
// or private macOS API sets an arbitrary opaque page base: -_setBackgroundColor:
// and underPageBackgroundColor colour the UI layer / the overscroll area, and
// neither changes the page base (MEASURED in the snapshot probe of
// docs/webview-dialog-over-gl-backdrop_2026-09-26.md). So a non-white base is
// "transparent page + a painted MacApkWvBaseView under it", composited exactly
// as Chromium composites a page over its base colour. SPI, owner-approved; the
// call is guarded by respondsToSelector so a macOS without it keeps WebKit's own
// opaque base (today's behaviour) instead of raising NSUnknownKeyException.
//
// WHAT IS UNDER THE PAGE. A transparent base shows whatever lies beneath the
// WKWebView inside its host view. On Android that is the window's own pixels
// under the WebView (call it B) and, below the window, any SurfaceView. Here
// WebView.onDraw punches the WebView's rect out of the canvas overlay (the
// Z_BELOW hole), which erases B; below the page there is then either a native
// surface layer carrying guest pixels (the ANGLE game on a GL host, a video
// plane) or no content at all: on a canvas-path (Path-A) host the framework
// angleLayer is still there -- drawInMTKView adds it to EVERY MTKView with a
// delegate (cocoa_bridge.mm, "ANGLE sublayer added") and presents an undrawn
// ANGLE framebuffer into it each tick, because that view's content goes to the
// canvas overlay instead -- and under it the host MTKView's own clear, opaque
// black (cocoa_bridge.mm CocoaCreateMetalSurfaceViewImpl,
// MTLClearColorMake(0,0,0,1)). So:
//   - opaque colour: exact on every host (an opaque base hides B on Android too);
//   - translucent colour over a native surface layer (a GL / video host): the
//     page and base composite over the game or video, as on a device (for King
//     B is transparent: its popup has no background and the GL host's canvas is
//     cleared to 0 wherever the app drew nothing);
//   - translucent colour with no guest content beneath (a Path-A host): the
//     page would composite over black where a device shows the app's own
//     background -- MEASURED in debug/wvtest/popup_gate.sh (the
//     MACAPK_WEBVIEW_BASE_OVER_CLEAR=1 row: a blank transparent page reads
//     (0,0,0,255)). Until the canvas can hand B to a backdrop layer (see the
//     note), such a page keeps WebKit's own opaque base: exactly the pre-change
//     result, never the black one. MACAPK_WEBVIEW_BASE_OVER_CLEAR=1 (diagnostic,
//     default off) forces the transparent base there anyway, to see or measure
//     that leak.
// Which of the last two applies turns on WHAT KIND of view hosts the page,
// which only cocoa_bridge.mm knows (WvHostKind): a Path-A view's framework
// angleLayer is a CAMetalLayer beneath the page like any other, so a layer-tree
// walk that does not know the host's kind counts it as a native surface and
// sends every translucent Path-A page to black (found in review 2026-09-26; the
// gate's first Path-A model had left that layer out).
bool WvBaseColorEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_BASE_COLOR");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

bool WvBaseOverClear() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_WEBVIEW_BASE_OVER_CLEAR");
        return v && std::strcmp(v, "1") == 0;
    }();
    return on;
}

// -[WKWebView _setDrawsBackground:] through KVC (setValue:forKey: resolves
// _set<Key>: when there is no set<Key>:). False when this WebKit has no such
// SPI, in which case nothing was changed.
bool WvSetDrawsBackground(WKWebView* wv, bool draws) {
    static const SEL spi = NSSelectorFromString(@"_setDrawsBackground:");
    if (!wv || ![wv respondsToSelector:spi]) return false;
    @try {
        [wv setValue:@(draws) forKey:@"drawsBackground"];
    } @catch (NSException* ex) {
        std::fprintf(stderr, "[W] webview: drawsBackground=%d threw (%s)\n", draws ? 1 : 0,
                     ex.reason.UTF8String ?: "?");
        return false;
    }
    return true;
}

// ---- The host's KIND, as cocoa_bridge.mm made it (2026-09-26) -------------
// cocoa_bridge.mm creates every app MTKView through one of two entry points,
// and they differ in exactly the fact the page base needs:
//   CocoaCreateMetalSurfaceView       a canvas-path (Path-A) view. Its content
//                                     is the canvas overlay; its framework
//                                     angleLayer presents an undrawn ANGLE
//                                     framebuffer (nothing renders GL into it).
//   CocoaCreateMetalSurfaceViewForGl  a SurfaceView / GLSurfaceView view. Its
//                                     framework angleLayer IS the guest's GL.
// Each entry point tags its view with an associated object keyed by the
// uniqued selector "macapkCanvasPathView": @YES for the first, @NO for the
// second. A selector is uniqued process-wide by the ObjC runtime
// (sel_registerName), so the key needs no link-time symbol: this file builds
// and runs against a cocoa_bridge.mm that does not tag yet, and the kind then
// reads UNKNOWN. Unknown is handled as "do not count the framework angleLayer"
// (WvNativeBeneath): the pre-change opaque base on every host, never the black
// Path-A leak -- the King backdrop simply waits for the tag.
// (Considered and rejected: inferring the kind from zPosition values another
// file assigns, or from which branch of CocoaWebViewHostView() answered --
// g_last_metal_view vs its GL fallback cannot be told apart from here.)
enum WvHostKindT { kWvHostUnknown = -1, kWvHostGl = 0, kWvHostCanvasPath = 1 };

int WvHostKind(NSView* host) {
    if (!host) return kWvHostUnknown;
    static const void* const key = (const void*)sel_registerName("macapkCanvasPathView");
    id tag = objc_getAssociatedObject(host, key);
    if (![tag isKindOfClass:[NSNumber class]]) return kWvHostUnknown;
    return [(NSNumber*)tag boolValue] ? kWvHostCanvasPath : kWvHostGl;
}

// The host MTKView's FRAMEWORK angleLayer (cocoa_bridge.mm
// MacApkV2RenderDelegate.angleLayer, the "dedicated sublayer ANGLE renders
// into"), read through the delegate's own property by name, or nil. Unity's and
// the native-EGL engines' dedicated layers and the video planes are other
// layers and are not this one.
CALayer* WvFrameworkAngleLayer(NSView* host) {
    if (![host isKindOfClass:[MTKView class]]) return nil;
    id d = ((MTKView*)host).delegate;
    static const SEL getter = sel_registerName("angleLayer");
    if (!d || ![d respondsToSelector:getter]) return nil;
    id l = nil;
    @try {
        l = [d valueForKey:@"angleLayer"];
    } @catch (NSException*) {
        return nil;
    }
    return [l isKindOfClass:[CALayer class]] ? (CALayer*)l : nil;
}

// Is there a native surface carrying guest pixels (a CAMetalLayer: the ANGLE
// game layer of a GL host, a video plane) or, when the page sits above the
// canvas (MACAPK_WEBVIEW_Z_BELOW=0), a canvas overlay with content, BENEATH
// this page in its host and under its rect? Sublayers that back a view
// (another WKWebView, a base view) do not count: they are pages, not what a
// page composites over. Equal zPosition falls back to sublayer order, as Core
// Animation does. The host's framework angleLayer counts only on a GL host
// (WvHostKind): on a Path-A host it carries no content, and on an untagged host
// its kind is unknown -- *untaggedAngle then says it was skipped for that
// reason, so the decision line can name it.
bool WvNativeBeneath(MacApkWvHolder* h, bool* untaggedAngle) {
    if (untaggedAngle) *untaggedAngle = false;
    NSView* host = h.wv.superview;
    CALayer* hl = host.layer;
    CALayer* wl = h.wv.layer;
    if (!hl || !wl) return false;
    CALayer* angle = WvFrameworkAngleLayer(host);
    const int kind = angle ? WvHostKind(host) : kWvHostGl;
    NSArray<CALayer*>* subs = hl.sublayers;
    const NSUInteger wi = [subs indexOfObjectIdenticalTo:wl];
    const CGRect wf = NSRectToCGRect(h.wv.frame);
    bool skippedUntagged = false;
    for (NSUInteger i = 0; i < subs.count; i++) {
        CALayer* l = subs[i];
        if (l == wl || (h.baseView && l == h.baseView.layer)) continue;
        if ([(id)l.delegate isKindOfClass:[NSView class]]) continue;
        if (l.hidden || l.opacity <= 0.f) continue;
        if (![l isKindOfClass:[CAMetalLayer class]] && l.contents == nil) continue;
        if (l.zPosition > wl.zPosition) continue;
        if (l.zPosition == wl.zPosition && (wi == NSNotFound || i > wi)) continue;
        if (!CGRectIntersectsRect(l.frame, wf)) continue;
        if (l == angle && kind != kWvHostGl) {
            if (kind == kWvHostUnknown) skippedUntagged = true;
            continue;
        }
        return true;
    }
    if (untaggedAngle) *untaggedAngle = skippedUntagged;
    return false;
}

// Porter-Duff SRC_OVER of two non-premultiplied ARGB colours (the same
// arithmetic as WebView.macapkSrcOver).
uint32_t WvSrcOver(uint32_t src, uint32_t dst) {
    const uint32_t sa = src >> 24, da = dst >> 24;
    if (sa == 255 || da == 0) return src;
    if (sa == 0) return dst;
    const double fs = sa / 255.0, fd = da / 255.0 * (1.0 - fs), fa = fs + fd;
    auto ch = [&](int sh) {
        return (uint32_t)std::lround((((src >> sh) & 0xff) * fs + ((dst >> sh) & 0xff) * fd) / fa);
    };
    return ((uint32_t)std::lround(fa * 255.0) << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0);
}

// Keep the painted base directly below its WKWebView (same superview, frame,
// zPosition, hidden), coloured `paint`; nil removes it.
void WvSyncBaseView(MacApkWvHolder* h, NSColor* paint) {
    NSView* sv = h.wv.superview;
    if (!paint || !sv) {
        if (h.baseView) {
            [h.baseView removeFromSuperview];
            if (!paint) h.baseView = nil;
        }
        return;
    }
    if (!h.baseView) {
        h.baseView = [[MacApkWvBaseView alloc] initWithFrame:h.wv.frame];
        h.baseView.wantsLayer = YES;
    }
    NSArray<NSView*>* subs = sv.subviews;
    const NSUInteger bi = [subs indexOfObjectIdenticalTo:h.baseView];
    const NSUInteger wi = [subs indexOfObjectIdenticalTo:h.wv];
    if (h.baseView.superview != sv || bi == NSNotFound || bi + 1 != wi) {
        [sv addSubview:h.baseView positioned:NSWindowBelow relativeTo:h.wv];
    }
    h.baseView.frame = h.wv.frame;
    h.baseView.hidden = h.wv.hidden;
    h.baseView.layer.zPosition = h.wv.layer.zPosition;
    h.baseView.layer.backgroundColor = paint.CGColor;
}

// Decide and apply the page base for one holder. Main thread only. Called on
// every colour push and from ApplyFrame (the host, and what lies beneath the
// page, can change with any frame push). A WebView that never called
// setBackgroundColor costs one compare.
void WvApplyBase(MacApkWvHolder* h) {
    if (!WvBaseColorEnabled() || !h.wv) return;
    const uint32_t c = h.baseColor;
    if (c == 0xFFFFFFFFu && h.baseMode == 0 && !h.baseView) return;
    const uint32_t a = c >> 24;
    int want = 0;
    bool useBackdrop = false;   // the branch that chose want=1 was the window backdrop
    const char* why = "AOSP default WHITE: WebKit's own opaque base";
    if (c != 0xFFFFFFFFu) {
        if (!h.wv.superview) return;   // not hosted yet: decided at the first frame push
        bool untaggedAngle = false;
        if (a == 255) {
            want = 1;
            why = "opaque: painted under a transparent page";
        } else if (WvNativeBeneath(h, &untaggedAngle)) {
            // A native surface beneath the page is AOSP's HOLE: a below-window
            // SurfaceView punches through everything the window drew before it
            // (android14-release SurfaceView.java draw() :512-521), so the
            // window's own pixels are NOT what lies under this page and the
            // backdrop model does not apply. Tested BEFORE the backdrop so a GL
            // game under an ad overlay in a window with an opaque background
            // stays visible through a transparent page, as on a device.
            want = 1;
            why = "translucent over the native surface beneath the page";
        } else if (h.backdropKnown && (h.backdrop >> 24) != 0) {
            // MACAPK_WEBVIEW_BACKDROP: the window drew these pixels under the
            // WebView before its page, so the page composites over THEM, not
            // over whatever lies below the window (a device's window is one
            // surface: an opaque window region hides every lower window and
            // every SurfaceView behind it). See WvSrcOver / the `under` below.
            want = 1;
            useBackdrop = true;
            why = "translucent over the window's own pixels beneath the WebView "
                  "(MACAPK_WEBVIEW_BACKDROP)";
        } else if (WvBaseOverClear()) {
            want = 1;
            why = "translucent over NOTHING (MACAPK_WEBVIEW_BASE_OVER_CLEAR=1, diagnostic: "
                  "the page composites over the host's black clear)";
        } else if (untaggedAngle) {
            want = 0;
            why = "translucent, but this host's kind is unknown (cocoa_bridge.mm did not tag "
                  "the view, see WvHostKind), so its framework angleLayer is not counted: "
                  "kept WebKit's opaque base (the pre-change result)";
        } else {
            want = 0;
            why = "translucent, but nothing lies beneath the page on this host (Path-A) and "
                  "the window backdrop is not available: kept WebKit's opaque base "
                  "(the pre-change result) instead of compositing over black";
        }
    }
    if (want != h.baseMode) {
        if (WvSetDrawsBackground(h.wv, want == 0)) {
            h.baseMode = want;
        } else {
            why = "unchanged: this WebKit has no -_setDrawsBackground:, so the page keeps "
                  "WebKit's opaque base";
            want = h.baseMode;
        }
    }
    // One line per (decision, colour): the Path-A fallback changes no mode, and
    // "why is this page white" is exactly the question the line answers.
    const int64_t logKey = (((int64_t)want << 32) | (int64_t)c)
                           ^ (useBackdrop ? (((int64_t)h.backdrop << 1) | 1) : 0);
    if (logKey != h.baseLogKey) {
        h.baseLogKey = logKey;
        std::fprintf(stderr, "[I] webview: id=%d base colour 0x%08x -> %s\n", h.viewId,
                     (unsigned)c, why);
    }
    NSColor* paint = nil;
    // The base under the page: the page-base colour over the window backdrop
    // when the latter is in use (translucent base), else the base colour alone.
    const uint32_t under = (want == 1 && useBackdrop) ? WvSrcOver(c, h.backdrop) : c;
    const uint32_t ua = under >> 24;
    if (want == 1 && ua > 0) {
        paint = [NSColor colorWithSRGBRed:((under >> 16) & 0xff) / 255.0
                                    green:((under >> 8) & 0xff) / 255.0
                                     blue:(under & 0xff) / 255.0
                                    alpha:ua / 255.0];
    }
    WvSyncBaseView(h, paint);
}

// Recompute the WKWebView's AppKit frame from the stored surface rect.
// Main-thread only. Re-run on every rect push AND on host-view frame changes.
void ApplyFrame(MacApkWvHolder* h) {
    NSView* host = WvHostView();
    if (!host) { h.wv.hidden = YES; WvApplyBase(h); return; }
    if (h.wv.superview != host) {
        g_wv_rehosting = true;
        [h.wv removeFromSuperview];
        [host addSubview:h.wv];
        g_wv_rehosting = false;
        // Sibling of the canvas overlay layer (path-a zPos 1.0): sit above it,
        // below the dedicated native-EGL layer (2.0). Re-asserted on every
        // frame push in case AppKit rebuilt the backing layer.
        h.attachedToHost = YES;
        // in-window=0 means the page renders into a view no window shows
        // (see WvHostView); it is the one fact this line exists to carry.
        std::fprintf(stderr,
                     "[I] webview: id=%d attached to host view=%p bounds=%.0fx%.0f in-window=%d\n",
                     h.viewId, (__bridge void*)host, host.bounds.size.width,
                     host.bounds.size.height, host.window ? 1 : 0);
    }
    // Below both canvas-overlay slots (0.5 on a GL host, 1.0 path-a) and
    // above the ANGLE layer (0): the app's views draw OVER the page and the
    // canvas's transparent hole (WebView.draw) shows it. One value for both
    // hosts -- the video plane learned at v1.0.997 that 0.75 sits above a
    // 0.5 overlay. 1.5 (above everything) under MACAPK_WEBVIEW_Z_BELOW=0.
    h.wv.layer.zPosition = WvZBelow() ? 0.25 : 1.5;
    if (h.surfaceW <= 0 || h.surfaceH <= 0) { h.wv.hidden = YES; WvApplyBase(h); return; }
    NSRect b = host.bounds;
    CGFloat sx = b.size.width  / (CGFloat)h.surfaceW;
    CGFloat sy = b.size.height / (CGFloat)h.surfaceH;
    // Surface px (y from the top) -> host coords (AppKit y-up, the host
    // MTKView is not flipped).
    NSRect (^toHost)(CGRect) = ^NSRect(CGRect r) {
        NSRect f;
        f.origin.x = b.origin.x + r.origin.x * sx;
        f.size.width = r.size.width * sx;
        f.size.height = r.size.height * sy;
        f.origin.y = b.origin.y + b.size.height - (r.origin.y + r.size.height) * sy;
        return f;
    };
    h.wv.frame = toHost(h.surfaceRect);
    h.wv.hidden = !h.visibleWanted;
    if ([h.wv isKindOfClass:[MacApkWKWebView class]]) {
        NSMutableArray<NSValue*>* occ = [NSMutableArray array];
        if (WvZBelow()) {
            for (NSValue* v in h.occluderSurfaceRects) {
                [occ addObject:[NSValue valueWithRect:toHost(v.rectValue)]];
            }
        }
        ((MacApkWKWebView*)h.wv).macapkOccluders = occ;
    }
    WvApplyBase(h);
}

void ApplyAllFrames() {
    for (NSNumber* k in g_holders) ApplyFrame(g_holders[k]);
}

}  // namespace

@implementation MacApkWvHolder

// Page scroll report (the macapkScroll user script): [scrollX, scrollY,
// scrollHeight] in CSS px. Converted to Android px with the host's surface-px
// per point times the page zoom, so Java can set the WebView's View scroll
// exactly as AOSP's AwContents does.
- (void)userContentController:(WKUserContentController*)ucc
      didReceiveScriptMessage:(WKScriptMessage*)message {
    if ([message.name isEqualToString:@"macapkConsole"]) {
        // [level, text, source, line] from kWvConsoleHookJS. Any page script can
        // post here, so check every element's type before touching it.
        NSArray* m = [message.body isKindOfClass:[NSArray class]] ? (NSArray*)message.body : nil;
        if (m.count < 4 || ![m[0] isKindOfClass:[NSNumber class]]
            || ![m[1] isKindOfClass:[NSString class]] || ![m[2] isKindOfClass:[NSString class]]
            || ![m[3] isKindOfClass:[NSNumber class]]) return;
        int level = [m[0] intValue];
        if (level < 0 || level > 4) level = 1;
        // The JS stack of an Error (an uncaught one, or one passed to console.*):
        // ours to log, not part of the app's ConsoleMessage (Chromium's carries
        // only the message). Without it a minified page's "Uncaught TypeError"
        // names a 5 MB line and nothing else.
        if (m.count >= 5 && [m[4] isKindOfClass:[NSString class]] && [(NSString*)m[4] length])
            std::fprintf(stderr, "[I] webview: id=%d console stack: %s\n", self.viewId,
                         WvFlatten(WvGuestUrl((NSString*)m[4]), 1500).UTF8String);
        PostEvent(self.viewId, EV_CONSOLE, (NSString*)m[1], (NSString*)m[2],
                  [NSString stringWithFormat:@"%d", level], [m[3] intValue]);
        return;
    }
    if (![message.name isEqualToString:@"macapkScroll"]) return;
    NSArray* a = [message.body isKindOfClass:[NSArray class]] ? (NSArray*)message.body : nil;
    if (a.count < 3) return;
    // The handler is reachable by any page script: never send a message to an
    // element that might not be a number (NSNull / a dictionary would raise).
    for (NSUInteger k = 0; k < 3; k++) {
        if (![a[k] isKindOfClass:[NSNumber class]]) return;
    }
    double cx = [a[0] doubleValue], cy = [a[1] doubleValue], ch = [a[2] doubleValue];
    NSView* host = WvHostView();
    double perPt = (host && host.bounds.size.width > 0 && self.surfaceW > 0)
                   ? self.surfaceW / host.bounds.size.width : 2.0;
    double zoom = 1.0;
    if (@available(macOS 11.0, *)) zoom = self.wv.pageZoom;
    double pxPerCss = perPt * zoom * self.wv.magnification;
    if (WvTraceEnabled()) {
        std::fprintf(stderr, "[T] webview: id=%d page scroll css=(%.0f,%.0f) h=%.0f pxPerCss=%.3f\n",
                     self.viewId, cx, cy, ch, pxPerCss);
    }
    PostEvent(self.viewId, EV_PAGE_SCROLL,
              [NSString stringWithFormat:@"%d,%d,%d,%.4f",
                   (int)llround(cx * pxPerCss), (int)llround(cy * pxPerCss),
                   (int)llround(ch), pxPerCss],
              nil, nil, 0);
}

static const double kPendingProgrammaticTtl = 30.0;   // seconds

- (void)purgeExpiredPendingProgrammaticUrls {
    double now = CACurrentMediaTime();
    while (self.pendingProgrammaticTimes.count
           && now - self.pendingProgrammaticTimes[0].doubleValue > kPendingProgrammaticTtl) {
        [self.pendingProgrammaticUrls removeObjectAtIndex:0];
        [self.pendingProgrammaticTimes removeObjectAtIndex:0];
    }
}

- (void)notePendingProgrammaticUrl:(NSString*)url {
    if (!url) return;
    if (!self.pendingProgrammaticUrls) {
        self.pendingProgrammaticUrls = [NSMutableArray array];
        self.pendingProgrammaticTimes = [NSMutableArray array];
    }
    [self purgeExpiredPendingProgrammaticUrls];
    [self.pendingProgrammaticUrls addObject:url];
    [self.pendingProgrammaticTimes addObject:@(CACurrentMediaTime())];
    while (self.pendingProgrammaticUrls.count > 16) {
        [self.pendingProgrammaticUrls removeObjectAtIndex:0];
        [self.pendingProgrammaticTimes removeObjectAtIndex:0];
    }
}

- (BOOL)consumePendingProgrammaticUrl:(NSString*)url {
    if (!url || !self.pendingProgrammaticUrls) return NO;
    [self purgeExpiredPendingProgrammaticUrls];
    NSUInteger i = [self.pendingProgrammaticUrls indexOfObject:url];
    if (i == NSNotFound) return NO;
    [self.pendingProgrammaticUrls removeObjectAtIndex:i];
    [self.pendingProgrammaticTimes removeObjectAtIndex:i];
    return YES;
}

// Load `assetUrl` (macapk-asset:) in place of a cancelled file:///android_asset
// navigation. Marked app-initiated: the app already answered
// shouldOverrideUrlLoading for the file: form, so the swap must not ask it a
// second time.
- (void)reissueAssetNavigation:(NSString*)assetUrl {
    NSURL* u = [NSURL URLWithString:assetUrl];
    if (!u || !self.wv) return;
    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d ASSET-NAV %s\n", self.viewId,
                     WvFlatten(WvGuestUrl(assetUrl), 300).UTF8String);
    [self.wv loadRequest:[NSURLRequest requestWithURL:u]];
    NSString* asked = self.wv.URL.absoluteString;
    [self notePendingProgrammaticUrl:(asked.length ? asked : assetUrl)];
}

// May the page in this WebView reach app assets? Chromium answers this in the
// RENDERER, against the origin of the document that asked, before the browser
// or the app hears anything: FrameLoader::AllowRequestForThisFrame
// (third_party/blink/renderer/core/loader/frame_loader.cc, chromium main
// ~l.602-630: "if (!request.CanDisplay(url)) ... Not allowed to load local
// resource") for a link / location / form / iframe, and CreateNewWindow
// (core/page/create_window.cc ~l.300-306, the opener's CanDisplay) for
// window.open. CanDisplay of a file: URL is SecurityOrigin::CanLoadLocalResources
// (security_origin.cc CanDisplay), which is true for a local document and for
// one that was GRANTED it: AwContents.loadUrl (android_webview/java/.../
// AwContents.java, the loadDataWithBaseURL block): "This allows data URLs with
// a non-data base URL access to file:///android_asset/ and file:///android_res/
// URLs. ... params.setCanLoadLocalResources(true)", applied to that one
// document at commit (render_frame_impl.cc grant_load_local_resources ->
// document_loader.cc origin->GrantLoadLocalResources()).
//
// Judged from the COMMITTED top document we record in didCommitNavigation:,
// NOT WKNavigationAction.sourceFrame: measured with a headless WKWebView,
// sourceFrame goes STALE across a WebContent process swap -- after asset page
// -> network page it kept naming the asset page, and after network -> asset it
// named the network page -- so it would both let a network page through and
// refuse the app's own asset page. createWebView's sourceFrame (the opener)
// stayed correct across swaps and is used there. Known gap: a network IFRAME
// inside an app's asset page is judged as its local top document.
- (BOOL)topDocumentMayLoadAppAssets {
    return WvIsLocalScheme(self.committedTopScheme) || self.committedTopGranted;
}

// The one [W] line for a refused page-initiated asset navigation. The app hears
// nothing, as on a device (the renderer drops it before the browser side).
- (void)logRefusedAssetNavigation:(NSString*)url how:(const char*)how
                             from:(NSString*)docScheme {
    std::fprintf(stderr, "[W] webview: id=%d refused %s to %s from a %s document "
                 "(Chromium: Not allowed to load local resource)\n", self.viewId, how,
                 WvLogUrl(WvGuestUrl(url)).UTF8String,
                 docScheme.length ? docScheme.UTF8String : "opaque-origin");
}

// ---- WKNavigationDelegate --------------------------------------------------

- (void)webView:(WKWebView*)wv
    decidePolicyForNavigationAction:(WKNavigationAction*)action
                        preferences:(WKWebpagePreferences*)prefs
                    decisionHandler:(void (^)(WKNavigationActionPolicy,
                                              WKWebpagePreferences*))decide {
    // Per-navigation JS toggle: honors WebSettings.setJavaScriptEnabled at the
    // same granularity Android does (takes effect on subsequent navigations).
    prefs.allowsContentJavaScript = self.jsEnabled;

    // The hidden read-grant navigation is ours, not the app's: never asked of
    // shouldOverrideUrlLoading.
    if (self.readGrantUrl && [action.request.URL.absoluteString isEqualToString:self.readGrantUrl]) {
        decide(WKNavigationActionPolicyAllow, prefs);
        return;
    }

    NSURLRequest* req = action.request;
    NSString* url = req.URL.absoluteString ?: @"";
    BOOL mainFrame = action.targetFrame ? action.targetFrame.isMainFrame : YES;

    // A navigation to file:///android_asset/ or file:///android_res/ that WebKit
    // itself is about to perform (the loadUrl() entry already maps its own) is
    // cancelled here and re-issued on the macapk-asset scheme -- the one other
    // place such a URL can enter. Measured with a headless WKWebView: the path
    // that reaches this is window.open (our createWebView re-loads the request
    // in this view); a link or location.href to an absolute
    // file:///android_asset URL from a page never gets here (WebKit refuses it
    // before asking), nor does an <iframe src> of one.
    NSString* assetUrl = WvHostUrl(url);
    if (assetUrl && !mainFrame) {
        // Never observed; re-targeting a subframe would need script in that
        // frame. Say so instead of failing silently.
        std::fprintf(stderr, "[W] webview: id=%d subframe navigation to %s is not served "
                     "(app assets load in the main frame or by relative reference)\n",
                     self.viewId, WvLogUrl(url).UTF8String);
    }

    // A PAGE may navigate to an app asset only from a document that may load
    // local resources (see -topDocumentMayLoadAppAssets). Session history is
    // exempt: Chromium runs back/forward from the browser side, where the
    // renderer check never applies, and the entry is one the app already let
    // load. Without this a network page's location.href / form / iframe to
    // macapk-asset: was served, and the app was asked about a
    // file:///android_asset URL it could not tell from its own.
    const BOOL assetRefused = WvIsAppAssetUrl(url)
        && action.navigationType != WKNavigationTypeBackForward
        && ![self topDocumentMayLoadAppAssets];

    // Android does not surface these through shouldOverrideUrlLoading:
    //  - subframe navigations
    //  - POST submissions
    //  - the navigation the app itself started via loadUrl()/reload()/go*()
    if (!mainFrame || [req.HTTPMethod isEqualToString:@"POST"]) {
        if (assetRefused) {
            [self logRefusedAssetNavigation:url how:(mainFrame ? "form POST" : "subframe navigation")
                                       from:self.committedTopScheme];
            decide(WKNavigationActionPolicyCancel, prefs);
            return;
        }
        decide(WKNavigationActionPolicyAllow, prefs);
        return;
    }
    // An app-initiated load is never re-issued, even with an android_asset
    // URL: loadUrl/loadDataWithBaseURL already swapped theirs, and turning a
    // loadHTMLString navigation into a URL load would throw its HTML away
    // (measured by mutation in debug/wvtest/asset_gate.sh). It is also never
    // origin-checked: an API load has no origin document, and Blink skips the
    // check for one ("If no origin Document* was specified, skip remaining
    // security checks", FrameLoader::AllowRequestForThisFrame).
    if ([self consumePendingProgrammaticUrl:url]) {
        decide(WKNavigationActionPolicyAllow, prefs);
        return;
    }
    if (assetRefused) {
        [self logRefusedAssetNavigation:url how:"navigation" from:self.committedTopScheme];
        decide(WKNavigationActionPolicyCancel, prefs);
        return;
    }

    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d NAV-ASK %s method=%s main=%d\n",
                     self.viewId, WvFlatten(WvGuestUrl(url), 300).UTF8String,
                     (req.HTTPMethod ?: @"GET").UTF8String, (int)mainFrame);
    int decisionId = g_next_decision_id.fetch_add(1);
    // The app decides on the URL it knows (file:///android_asset/...); when it
    // lets the navigation proceed, the asset form is what actually loads.
    __weak MacApkWvHolder* weakSelf = self;
    __block void (^handler)(WKNavigationActionPolicy) =
        ^(WKNavigationActionPolicy p) {
            if (assetUrl && p == WKNavigationActionPolicyAllow) {
                decide(WKNavigationActionPolicyCancel, prefs);
                [weakSelf reissueAssetNavigation:assetUrl];
                return;
            }
            decide(p, prefs);
        };
    g_nav_decisions[@(decisionId)] = handler;

    // Watchdog: if the app's shouldOverrideUrlLoading never answers (an
    // exception on the guest side), proceed after 8s -- Android's default
    // return is false (= proceed), and a leaked handler would hang the page
    // forever, which is exactly the "loads forever" symptom this bridge
    // exists to remove.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        void (^late)(WKNavigationActionPolicy) = g_nav_decisions[@(decisionId)];
        if (late) {
            [g_nav_decisions removeObjectForKey:@(decisionId)];
            std::fprintf(stderr,
                         "[W] webview: id=%d shouldOverrideUrlLoading decision %d "
                         "timed out; proceeding\n", self.viewId, decisionId);
            late(WKNavigationActionPolicyAllow);
        }
    });

    NSMutableArray* flags = [NSMutableArray array];
    if (mainFrame) [flags addObject:@"main"];
    if (action.navigationType == WKNavigationTypeOther) [flags addObject:@"redirect"];
    if (action.navigationType == WKNavigationTypeLinkActivated) [flags addObject:@"gesture"];
    NSString* flagStr = [flags componentsJoinedByString:@","];
    NSString* method = req.HTTPMethod ?: @"GET";
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_SHOULD_OVERRIDE, url, method, flagStr, decisionId);
    });
}

- (BOOL)isReadGrantNav:(WKNavigation*)nav {
    return self.readGrantNav && nav == self.readGrantNav;
}

// The hidden read-grant navigation ended (finished, failed or was cancelled):
// run the data load it was holding, unless the app has issued another load since.
- (void)endReadGrant:(BOOL)ok {
    if (ok) self.readGranted = YES;
    self.readGrantNav = nil;
    self.readGrantUrl = nil;
    dispatch_block_t next = self.afterReadGrant;
    self.afterReadGrant = nil;
    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d READ-GRANT %s %s\n", self.viewId,
                     ok ? "granted" : "failed", self.readGrantRoot.UTF8String);
    if (next) next();
}

- (void)webView:(WKWebView*)wv didStartProvisionalNavigation:(WKNavigation*)nav {
    if ([self isReadGrantNav:nav]) return;
    NSString* url = wv.URL.absoluteString;
    int viewId = self.viewId;
    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d START %s\n", viewId,
                     WvFlatten(WvGuestUrl(url), 400).UTF8String);
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_PAGE_STARTED, url, nil, nil, 0);
    });
}

- (void)webView:(WKWebView*)wv didCommitNavigation:(WKNavigation*)nav {
    if ([self isReadGrantNav:nav]) return;
    NSString* url = wv.URL.absoluteString;
    // The new top document, for -topDocumentMayLoadAppAssets. The grant belongs
    // to exactly the loadDataWithBaseURL document, so any other commit ends it
    // (a same-document navigation does not commit, and keeps it, as on a
    // device). WebKit delivers this before the page's first subresource ask
    // reaches the scheme handler: both come over the same WebContent
    // connection, in order (measured: commit, then the page's img task).
    self.committedTopScheme = wv.URL.scheme;
    self.committedTopGranted = nav && nav == self.grantNavigation;
    if (self.committedTopGranted) self.grantNavigation = nil;
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_PAGE_COMMIT_VISIBLE, url, nil, nil, 0);
    });
}

- (void)webView:(WKWebView*)wv didFinishNavigation:(WKNavigation*)nav {
    if ([self isReadGrantNav:nav]) { [self endReadGrant:YES]; return; }
    NSString* url = wv.URL.absoluteString;
    self.lastCommittedUrl = url;   // baseline for the same-document check
    // Push the title AHEAD of PAGE_FINISHED: the title KVO often lands after
    // didFinish, but Android apps read getTitle() inside onPageFinished
    // (measured on wvtest: getTitle was empty there until this).
    NSString* title = wv.title;
    int histBits = (wv.canGoBack ? 1 : 0) | (wv.canGoForward ? 2 : 0);
    int viewId = self.viewId;
    if (WvTraceEnabled()) {
        std::fprintf(stderr, "[T] webview: id=%d FINISH url=%s title=%s\n", viewId,
                     WvFlatten(WvGuestUrl(url), 400).UTF8String,
                     WvFlatten(title, 200).UTF8String);
        // The rendered TEXT is the only way to read what a remote site actually
        // served: the web content is a native NSView, so the runtime's own frame
        // dumps cannot see it, and a screenshot yields an impression rather than
        // a quotable fact.
        [wv evaluateJavaScript:@"navigator.userAgent + '\\u0001' + "
                                "(document.body ? document.body.innerText : '')"
             completionHandler:^(id r, __unused NSError* e) {
            NSString* s = [r isKindOfClass:NSString.class] ? (NSString*)r : @"";
            NSRange sep = [s rangeOfString:@"\x01"];
            NSString* ua = sep.location == NSNotFound ? s : [s substringToIndex:sep.location];
            NSString* body = sep.location == NSNotFound
                ? @"" : [s substringFromIndex:sep.location + 1];
            std::fprintf(stderr, "[T] webview: id=%d NAV-UA %s\n", viewId,
                         WvFlatten(ua, 400).UTF8String);
            std::fprintf(stderr, "[T] webview: id=%d TEXT %s\n", viewId,
                         WvFlatten(body, 1800).UTF8String);
        }];
    }
    dispatch_async(g_event_queue, ^{
        if (title.length) PostEvent(viewId, EV_TITLE, title, nil, nil, 0);
        PostEvent(viewId, EV_URL_CHANGED, url, nil, nil, histBits);
        PostEvent(viewId, EV_HISTORY_UPDATE, url, nil, nil, 0);
        PostEvent(viewId, EV_PAGE_FINISHED, url, nil, nil, 0);
    });
}

- (void)handleNavError:(NSError*)e {
    // NSURLErrorCancelled covers our own policy cancels and stopLoading();
    // Android does not report those as page errors.
    if ([e.domain isEqualToString:NSURLErrorDomain] && e.code == NSURLErrorCancelled)
        return;
    NSString* desc = e.localizedDescription ?: @"navigation failed";
    NSURL* failingUrl = e.userInfo[NSURLErrorFailingURLErrorKey];
    NSString* failing = failingUrl ? failingUrl.absoluteString : self.wv.URL.absoluteString;
    int code = MapNSURLError(e);
    int viewId = self.viewId;
    // Unconditional: a failed page load is rare, and until 2026-09-25 this path
    // printed NOTHING even under MACAPK_WEBVIEW_TRACE -- Candy Crush's Terms of
    // Use popup died here and the only fingerprint in the log was a class load
    // of android.webkit.WebResourceError.
    std::fprintf(stderr, "[W] webview: id=%d load error code=%d (%s %ld) url=%s desc=%s\n",
                 viewId, code, e.domain.UTF8String ?: "?", (long)e.code,
                 WvLogUrl(WvGuestUrl(failing)).UTF8String,
                 WvFlatten(desc, 200).UTF8String);
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_RECEIVED_ERROR, desc, failing, @"main", code);
    });
}

- (void)webView:(WKWebView*)wv didFailProvisionalNavigation:(WKNavigation*)nav
      withError:(NSError*)e {
    if ([self isReadGrantNav:nav]) { [self endReadGrant:NO]; return; }
    [self handleNavError:e];
}

- (void)webView:(WKWebView*)wv didFailNavigation:(WKNavigation*)nav withError:(NSError*)e {
    if ([self isReadGrantNav:nav]) { [self endReadGrant:NO]; return; }
    [self handleNavError:e];
}

- (void)webView:(WKWebView*)wv
    decidePolicyForNavigationResponse:(WKNavigationResponse*)response
                      decisionHandler:(void (^)(WKNavigationResponsePolicy))decide {
    // Main-frame HTTP error statuses surface as onReceivedHttpError (API 23).
    if (response.forMainFrame &&
        [response.response isKindOfClass:[NSHTTPURLResponse class]]) {
        NSHTTPURLResponse* http = (NSHTTPURLResponse*)response.response;
        if (http.statusCode >= 400) {
            NSString* url = http.URL.absoluteString;
            int status = (int)http.statusCode;
            int viewId = self.viewId;
            dispatch_async(g_event_queue, ^{
                PostEvent(viewId, EV_HTTP_ERROR, url, nil, nil, status);
            });
        }
    }
    decide(WKNavigationResponsePolicyAllow);
}

// window.close() from page script. AOSP routes it to
// WebChromeClient.onCloseWindow(WebView) and leaves the teardown to the app; we
// only deliver the callback, we never destroy the view ourselves.
- (void)webViewDidClose:(WKWebView*)wv {
    int viewId = self.viewId;
    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d WINDOW.CLOSE\n", viewId);
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_CLOSE_WINDOW, nil, nil, nil, 0);
    });
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView*)wv {
    std::fprintf(stderr, "[E] webview: id=%d WebContent process terminated\n", self.viewId);
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_RENDER_GONE, nil, nil, nil, 0);
    });
}

// ---- WKUIDelegate (JS dialogs + the sync interface bridge) -----------------

- (void)webView:(WKWebView*)wv
    runJavaScriptTextInputPanelWithPrompt:(NSString*)prompt
                              defaultText:(NSString*)dflt
                         initiatedByFrame:(WKFrameInfo*)frame
                        completionHandler:(void (^)(NSString*))done {
    if ([prompt hasPrefix:kJsBridgePrefix]) {
        NSString* payload = [prompt substringFromIndex:kJsBridgePrefix.length];
        int viewId = self.viewId;
        dispatch_async(g_jsbridge_queue, ^{
            NSString* result = CallJsBridge(viewId, payload);
            done(result ?: @"");
        });
        return;
    }
    int resultId = g_next_dialog_id.fetch_add(1);
    g_js_dialogs[@(resultId)] = [done copy];
    NSString* url = frame.request.URL.absoluteString ?: wv.URL.absoluteString;
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_JS_PROMPT, prompt, url, dflt, resultId);
    });
}

- (void)webView:(WKWebView*)wv runJavaScriptAlertPanelWithMessage:(NSString*)msg
    initiatedByFrame:(WKFrameInfo*)frame completionHandler:(void (^)(void))done {
    int resultId = g_next_dialog_id.fetch_add(1);
    g_js_dialogs[@(resultId)] = [done copy];
    NSString* url = frame.request.URL.absoluteString ?: wv.URL.absoluteString;
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_JS_ALERT, msg, url, nil, resultId);
    });
}

- (void)webView:(WKWebView*)wv runJavaScriptConfirmPanelWithMessage:(NSString*)msg
    initiatedByFrame:(WKFrameInfo*)frame completionHandler:(void (^)(BOOL))done {
    int resultId = g_next_dialog_id.fetch_add(1);
    g_js_dialogs[@(resultId)] = [done copy];
    NSString* url = frame.request.URL.absoluteString ?: wv.URL.absoluteString;
    int viewId = self.viewId;
    dispatch_async(g_event_queue, ^{
        PostEvent(viewId, EV_JS_CONFIRM, msg, url, nil, resultId);
    });
}

// window.open with setSupportMultipleWindows(false) (the Android default):
// AOSP loads the popup in the SAME WebView. Returning nil + loading the
// request ourselves mirrors that.
- (WKWebView*)webView:(WKWebView*)wv
    createWebViewWithConfiguration:(WKWebViewConfiguration*)cfg
               forNavigationAction:(WKNavigationAction*)action
                    windowFeatures:(WKWindowFeatures*)features {
    NSString* url = action.request.URL.absoluteString ?: @"";
    if (WvTraceEnabled())
        std::fprintf(stderr, "[T] webview: id=%d WINDOW.OPEN %s\n", self.viewId,
                     WvFlatten(WvGuestUrl(url), 400).UTF8String);
    // window.open of an app asset is judged against the OPENER, as Chromium's
    // CreateNewWindow does (see -topDocumentMayLoadAppAssets). Here, unlike in
    // decidePolicy, sourceFrame is the opener and measured reliable across a
    // process swap, so an opener iframe is judged as itself. The grant covers
    // only the granted top document.
    WKFrameInfo* opener = action.sourceFrame;
    if (WvIsAppAssetUrl(url)) {
        const BOOL may = opener
            ? (WvIsLocalScheme(opener.securityOrigin.protocol)
               || (opener.isMainFrame && self.committedTopGranted))
            : [self topDocumentMayLoadAppAssets];
        if (!may) {
            [self logRefusedAssetNavigation:url how:"window.open"
                                       from:(opener ? opener.securityOrigin.protocol
                                                    : self.committedTopScheme)];
            return nil;
        }
    }
    if (action.request.URL) [wv loadRequest:action.request];
    return nil;
}

// ---- KVO: progress / title / url / history state ---------------------------

- (void)observeValueForKeyPath:(NSString*)keyPath ofObject:(id)object
                        change:(NSDictionary*)change context:(void*)ctx {
    if (self.readGrantNav) return;   // the hidden read-grant load: nothing for the app
    int viewId = self.viewId;
    if ([keyPath isEqualToString:@"estimatedProgress"]) {
        int pct = (int)(self.wv.estimatedProgress * 100.0 + 0.5);
        dispatch_async(g_event_queue, ^{
            PostEvent(viewId, EV_PROGRESS, nil, nil, nil, pct);
        });
    } else if ([keyPath isEqualToString:@"title"]) {
        NSString* t = self.wv.title;
        dispatch_async(g_event_queue, ^{
            PostEvent(viewId, EV_TITLE, t, nil, nil, 0);
        });
    } else if ([keyPath isEqualToString:@"URL"] ||
               [keyPath isEqualToString:@"canGoBack"] ||
               [keyPath isEqualToString:@"canGoForward"]) {
        NSString* u = self.wv.URL.absoluteString;
        int histBits = (self.wv.canGoBack ? 1 : 0) | (self.wv.canGoForward ? 2 : 0);
        // A same-document navigation is a URL change with NO navigation in flight:
        // a real one moves the URL while isLoading is YES and then lands in
        // didFinishNavigation:, which is what sets lastCommittedUrl. See
        // WvSameDocEnabled() for the AOSP contract this reproduces.
        BOOL sameDoc = WvSameDocEnabled()
                    && [keyPath isEqualToString:@"URL"]
                    && !self.wv.isLoading
                    && u.length && self.lastCommittedUrl.length
                    && ![u isEqualToString:self.lastCommittedUrl];
        if (sameDoc) {
            self.lastCommittedUrl = u;
            if (WvTraceEnabled())
                std::fprintf(stderr, "[T] webview: id=%d SAMEDOC %s\n", viewId,
                             WvFlatten(WvGuestUrl(u), 400).UTF8String);
        }
        dispatch_async(g_event_queue, ^{
            PostEvent(viewId, EV_URL_CHANGED, u, nil, nil, histBits);
            if (sameDoc) {
                // AOSP order: doUpdateVisitedHistory, then onPageFinished. No
                // onPageStarted -- Chromium skips it for same-document commits.
                PostEvent(viewId, EV_HISTORY_UPDATE, u, nil, nil, 0);
                PostEvent(viewId, EV_PAGE_FINISHED, u, nil, nil, 0);
            }
        });
    }
}

- (void)dealloc {
    @try {
        [_wv removeObserver:self forKeyPath:@"estimatedProgress"];
        [_wv removeObserver:self forKeyPath:@"title"];
        [_wv removeObserver:self forKeyPath:@"URL"];
        [_wv removeObserver:self forKeyPath:@"canGoBack"];
        [_wv removeObserver:self forKeyPath:@"canGoForward"];
    } @catch (NSException* ex) {
    }
}

@end

// ---- macapk-asset scheme handler (file:///android_asset/, file:///android_res/)
//
// Chromium's equivalent is AndroidStreamReaderURLLoader fed by
// AndroidProtocolHandler: it opens the asset through the app's AssetManager and
// synthesizes an "HTTP/1.1 200 OK" response carrying only a Content-Type
// (components/embedder_support/android/util/android_stream_reader_url_loader.cc,
// the head.headers / kContentType block). Same shape here: bytes from JNI on
// g_asset_queue, then response + data + finish back on the main thread.
static NSError* WvAssetError(NSInteger code, NSString* desc, NSURL* url) {
    NSMutableDictionary* info = [NSMutableDictionary dictionary];
    info[NSLocalizedDescriptionKey] = desc;
    if (url) info[NSURLErrorFailingURLErrorKey] = url;
    return [NSError errorWithDomain:NSURLErrorDomain code:code userInfo:info];
}

@interface MacApkWvAssetHandler : NSObject <WKURLSchemeHandler>
@end

@implementation MacApkWvAssetHandler {
    // Tasks started and not yet answered or stopped. Main-thread only. WebKit
    // forbids messaging a task after stopURLSchemeTask:, and the read finishes
    // on another queue, so every answer checks membership first.
    NSHashTable* _live;
}

- (instancetype)init {
    if ((self = [super init])) {
        _live = [NSHashTable hashTableWithOptions:NSPointerFunctionsStrongMemory |
                                                  NSPointerFunctionsObjectPointerPersonality];
    }
    return self;
}

- (void)webView:(WKWebView*)wv startURLSchemeTask:(id<WKURLSchemeTask>)task {
    NSURLRequest* req = task.request;
    NSURL* url = req.URL;
    NSString* guest = WvGuestUrl(url.absoluteString ?: @"");
    MacApkWvHolder* holder = [wv.navigationDelegate isKindOfClass:[MacApkWvHolder class]]
                             ? (MacApkWvHolder*)wv.navigationDelegate : nil;
    int viewId = holder ? holder.viewId : 0;
    // Only a document that may load local resources may load app assets: a
    // LOCAL one, or the one loadDataWithBaseURL() granted it to (see
    // -topDocumentMayLoadAppAssets). Chromium refuses a network page's
    // reference to file:///android_asset in the renderer ("Not allowed to load
    // local resource") before any request exists, so the app sees no
    // onReceivedError for it -- and WebKit, measured, WOULD hand this handler a
    // network page's <img src="macapk-asset:..."> (mainDocumentURL http://...),
    // so the rule has to live here. A main-frame load asks with
    // mainDocumentURL == its own URL, so loadUrl() always passes. Known gap: the
    // task names no frame, so a network iframe inside a local page is judged as
    // that page.
    NSString* doc = req.mainDocumentURL.scheme.lowercaseString;
    if (!WvIsLocalScheme(doc) && !(holder && holder.committedTopGranted)) {
        std::fprintf(stderr, "[W] webview: id=%d refused %s: a %s document may not load "
                     "app assets (Chromium: Not allowed to load local resource)\n",
                     viewId, WvLogUrl(guest).UTF8String,
                     doc.length ? doc.UTF8String : "(no main document)");
        [task didFailWithError:WvAssetError(NSURLErrorNoPermissionsToReadFile,
                                            @"net::ERR_ACCESS_DENIED", url)];
        return;
    }
    // A main-frame document's failure reaches the app through
    // didFailProvisionalNavigation: -> handleNavError. Everything else (an
    // image, a script, an iframe's document) is reported from here, because
    // AOSP's onReceivedError(view, request, error) "will be called for any
    // resource (iframe, image, etc.), not just for the main page" (WebViewClient
    // javadoc, android14-release) and WebKit gives the navigation delegate no
    // callback for those.
    const BOOL mainDoc = req.mainDocumentURL && [req.mainDocumentURL isEqual:url];
    [_live addObject:task];
    dispatch_async(g_asset_queue, ^{
        NSString* mime = nil;
        NSData* bytes = WvReadAsset(viewId, guest, &mime);
        dispatch_async(dispatch_get_main_queue(), ^{
            if (![self->_live containsObject:task]) return;   // stopped meanwhile
            [self->_live removeObject:task];
            if (!bytes) {
                // Chromium: the stream fails to open -> net::ERR_FILE_NOT_FOUND,
                // which convertErrorCode reports as ERROR_UNKNOWN (see
                // MapNSURLError), with the net error name as the description.
                if (!mainDoc) {
                    std::fprintf(stderr, "[W] webview: id=%d app asset not found: %s\n",
                                 viewId, WvLogUrl(guest).UTF8String);
                    dispatch_async(g_event_queue, ^{
                        PostEvent(viewId, EV_RECEIVED_ERROR, @"net::ERR_FILE_NOT_FOUND",
                                  guest, @"", ERR_UNKNOWN);
                    });
                }
                [task didFailWithError:WvAssetError(NSURLErrorFileDoesNotExist,
                                                    @"net::ERR_FILE_NOT_FOUND", url)];
                return;
            }
            NSString* type = mime.length ? mime : @"application/octet-stream";
            if (WvTraceEnabled())
                std::fprintf(stderr, "[T] webview: id=%d ASSET %s %lu bytes %s\n", viewId,
                             WvFlatten(guest, 300).UTF8String, (unsigned long)bytes.length,
                             type.UTF8String);
            NSHTTPURLResponse* resp = [[NSHTTPURLResponse alloc]
                 initWithURL:url statusCode:200 HTTPVersion:@"HTTP/1.1"
                headerFields:@{ @"Content-Type" : type,
                                @"Content-Length" : [NSString stringWithFormat:@"%lu",
                                                        (unsigned long)bytes.length] }];
            [task didReceiveResponse:resp];
            [task didReceiveData:bytes];
            [task didFinish];
        });
    });
}

- (void)webView:(WKWebView*)wv stopURLSchemeTask:(id<WKURLSchemeTask>)task {
    [_live removeObject:task];
}

@end

// ---- JNI entry points ------------------------------------------------------

namespace {

void JNICALL NativeInit(JNIEnv* env, jclass, jstring jPkg) {
    NSString* pkg = FromJString(env, jPkg);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!g_holders) {
            g_holders = [NSMutableDictionary dictionary];
            g_nav_decisions = [NSMutableDictionary dictionary];
            g_js_dialogs = [NSMutableDictionary dictionary];
        }
        EnsureDataStore(pkg);
    });
}

// Views whose NEXT NativeCreate must grant universal file access (see
// MacApkWebViewBridge.UNIVERSAL_ACCESS). Main-thread only: NativeCreateOptions
// and NativeCreate both hop onto the main queue in call order.
NSMutableSet<NSNumber*>* g_create_universal = nil;

void JNICALL NativeCreateOptions(JNIEnv*, jclass, jint viewId, jboolean universal) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!g_create_universal) g_create_universal = [NSMutableSet set];
        if (universal == JNI_TRUE) [g_create_universal addObject:@(viewId)];
        else [g_create_universal removeObject:@(viewId)];
    });
}

void JNICALL NativeCreate(JNIEnv* env, jclass, jint viewId, jstring jPkg,
                          jstring jUserAgent, jboolean jsEnabled) {
    NSString* pkg = FromJString(env, jPkg);
    NSString* ua = FromJString(env, jUserAgent);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!g_holders) {
            g_holders = [NSMutableDictionary dictionary];
            g_nav_decisions = [NSMutableDictionary dictionary];
            g_js_dialogs = [NSMutableDictionary dictionary];
        }
        if (g_holders[@(viewId)]) return;
        WKWebViewConfiguration* cfg = [WKWebViewConfiguration new];
        cfg.websiteDataStore = EnsureDataStore(pkg);
        // WebSettings.setAllowUniversalAccessFromFileURLs(true): a file: document
        // gets universal access, so its cross-origin requests skip CORS as in
        // Chromium's WebView. WebKit SPI, readable only from the configuration a
        // view is created with (measured with a headless WKWebView: set before
        // init, a file: page's fetch with a custom header to an API that does not
        // allow it for origin null reaches the server; set on wv.configuration
        // after init, or on WKPreferences, it does not). Also lets file: pages read
        // other file: URLs, which Chromium's universal access implies too.
        if ([g_create_universal containsObject:@(viewId)]) {
            [g_create_universal removeObject:@(viewId)];
            @try {
                [cfg setValue:@YES forKey:@"allowUniversalAccessFromFileURLs"];
                [cfg.preferences setValue:@YES forKey:@"allowFileAccessFromFileURLs"];
                std::fprintf(stderr, "[I] webview: id=%d created with universal access from "
                             "file URLs (the app asked for it)\n", (int)viewId);
            } @catch (NSException* e) {
                std::fprintf(stderr, "[W] webview: id=%d universal file access not settable: %s\n",
                             (int)viewId, e.reason.UTF8String);
            }
        }
        MacApkWvHolder* h = [MacApkWvHolder new];
        h.viewId = (int)viewId;
        h.jsEnabled = (jsEnabled == JNI_TRUE);
        // AOSP's page base until setBackgroundColor says otherwise
        // (AwContents.java:500 mBaseBackgroundColor = Color.WHITE), which is
        // WebKit's own opaque base: nothing to apply. See WvApplyBase.
        h.baseColor = 0xFFFFFFFFu;
        h.baseMode = 0;
        h.baseLogKey = -1;
        cfg.defaultWebpagePreferences.allowsContentJavaScript = h.jsEnabled;
        // Page -> Android scroll mirror (v1.0.1643). AOSP keeps the WebView's
        // View scroll offsets equal to the page's scroll position
        // (AwContents.scrollContainerViewTo -> super_scrollTo), which is what
        // WebView.getScrollY(), onScrollChanged listeners and getContentHeight()
        // report; apps move native chrome with it (Wikipedia's article header
        // translates with the page). WKWebView exposes no scroll offset on
        // macOS, so the page reports it, once per animation frame at most.
        MacApkWvScrollProxy* sp = [MacApkWvScrollProxy new];
        sp.target = h;
        [cfg.userContentController addScriptMessageHandler:sp name:@"macapkScroll"];
        WvInstallScrollScript(cfg.userContentController);
        if (WvConsoleEnabled()) {
            [cfg.userContentController addScriptMessageHandler:sp name:@"macapkConsole"];
            WvInstallConsoleScript(cfg.userContentController);
        }
        WvInstallXhrDoneOnceScript(cfg.userContentController);
        // App assets (see WvAssetSchemeEnabled). Must be set BEFORE
        // initWithFrame:configuration:, which copies the configuration. One
        // handler serves every WebView; it finds the view from the WKWebView.
        if (WvAssetSchemeEnabled()) {
            static MacApkWvAssetHandler* assetHandler = [MacApkWvAssetHandler new];
            [cfg setURLSchemeHandler:assetHandler forURLScheme:kWvAssetScheme];
        }
        h.wv = [[MacApkWKWebView alloc] initWithFrame:NSMakeRect(0, 0, 10, 10)
                                         configuration:cfg];
        ((MacApkWKWebView*)h.wv).macapkViewId = (int)viewId;
        h.wv.navigationDelegate = h;
        h.wv.UIDelegate = h;
        if (ua.length) h.wv.customUserAgent = ua;
        h.wv.hidden = YES;
        if (@available(macOS 13.3, *)) h.wv.inspectable = YES;
        if (WvTraceEnabled()) {
            WvInstallTraceScript(cfg.userContentController);
            dispatch_source_t t = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0,
                                                         dispatch_get_main_queue());
            dispatch_source_set_timer(t, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC),
                                      2 * NSEC_PER_SEC, 200 * NSEC_PER_MSEC);
            __weak MacApkWvHolder* wh = h;
            // MACAPK_WEBVIEW_PROBE_FILE=<path>: whenever that file's mtime changes,
            // its contents are evaluated in the page and printed as a PROBE line.
            // Asking the live page a new question then costs a `cat > file`, not a
            // rebuild + a re-run of a login flow.
            static const char* probePath = std::getenv("MACAPK_WEBVIEW_PROBE_FILE");
            __block time_t probeSeen = 0;
            dispatch_source_set_event_handler(t, ^{
                MacApkWvHolder* s = wh;
                if (!s || !s.wv) return;
                int vid = s.viewId;
                if (probePath) {
                    struct stat st;
                    if (::stat(probePath, &st) == 0 && st.st_mtime != probeSeen) {
                        probeSeen = st.st_mtime;
                        NSString* js = [NSString stringWithContentsOfFile:@(probePath)
                                                                 encoding:NSUTF8StringEncoding error:nil];
                        if (js.length) {
                            [s.wv evaluateJavaScript:js completionHandler:^(id r, NSError* e) {
                                NSString* st2 = [r isKindOfClass:NSString.class] ? (NSString*)r
                                              : (r ? [NSString stringWithFormat:@"%@", r]
                                                   : (e ? [NSString stringWithFormat:@"ERR %@", e.localizedDescription] : @"null"));
                                std::fprintf(stderr, "[T] webview: id=%d PROBE %s\n", vid,
                                             WvFlatten(st2, 3000).UTF8String);
                            }];
                        }
                    }
                }
                [s.wv evaluateJavaScript:kWvTraceStateJS completionHandler:^(id r, NSError* e) {
                    NSString* st = [r isKindOfClass:NSString.class] ? (NSString*)r
                                 : (e ? [NSString stringWithFormat:@"ERR %@", e.localizedDescription]
                                      : @"null");
                    std::fprintf(stderr, "[T] webview: id=%d STATE %s\n", vid,
                                 WvFlatten(st, 900).UTF8String);
                }];
            });
            dispatch_resume(t);
            h.traceTimer = t;
        }
        [h.wv addObserver:h forKeyPath:@"estimatedProgress" options:0 context:nullptr];
        [h.wv addObserver:h forKeyPath:@"title" options:0 context:nullptr];
        [h.wv addObserver:h forKeyPath:@"URL" options:0 context:nullptr];
        [h.wv addObserver:h forKeyPath:@"canGoBack" options:0 context:nullptr];
        [h.wv addObserver:h forKeyPath:@"canGoForward" options:0 context:nullptr];
        g_holders[@(viewId)] = h;
        std::fprintf(stderr, "[I] webview: id=%d created (js=%d ua=%s)\n",
                     (int)viewId, h.jsEnabled ? 1 : 0,
                     ua.length ? ua.UTF8String : "(default)");
    });
}

void JNICALL NativeDestroy(JNIEnv*, jclass, jint viewId) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        if (h.traceTimer) { dispatch_source_cancel(h.traceTimer); h.traceTimer = nil; }
        [h.wv.configuration.userContentController
            removeScriptMessageHandlerForName:@"macapkScroll"];
        if (WvConsoleEnabled())
            [h.wv.configuration.userContentController
                removeScriptMessageHandlerForName:@"macapkConsole"];
        [h.wv stopLoading];
        g_wv_rehosting = true;
        [h.wv removeFromSuperview];
        g_wv_rehosting = false;
        [h.baseView removeFromSuperview];
        h.baseView = nil;
        [g_holders removeObjectForKey:@(viewId)];
        std::fprintf(stderr, "[I] webview: id=%d destroyed\n", (int)viewId);
    });
}

// Rects (x,y,w,h quadruples, Android surface px) that the app's own views
// cover over this WebView; clicks there go to the Android view tree.
void JNICALL NativeSetOccluders(JNIEnv* env, jclass, jint viewId, jintArray jRects) {
    NSMutableArray<NSValue*>* rects = [NSMutableArray array];
    if (jRects) {
        jsize n = env->GetArrayLength(jRects);
        jint* p = env->GetIntArrayElements(jRects, nullptr);
        if (p) {
            for (jsize i = 0; i + 3 < n; i += 4) {
                [rects addObject:[NSValue valueWithRect:NSMakeRect(p[i], p[i + 1],
                                                                   p[i + 2], p[i + 3])]];
            }
            env->ReleaseIntArrayElements(jRects, p, JNI_ABORT);
        }
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        h.occluderSurfaceRects = rects;
        ApplyFrame(h);
    });
}

void JNICALL NativeSetFrame(JNIEnv*, jclass, jint viewId, jint x, jint y, jint w,
                            jint h_, jint surfW, jint surfH, jboolean visible) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        h.surfaceRect = CGRectMake(x, y, w, h_);
        h.surfaceW = surfW;
        h.surfaceH = surfH;
        h.visibleWanted = (visible == JNI_TRUE);
        ApplyFrame(h);
    });
}

// WebView.setBackgroundColor (see WvApplyBase). Java pushes it on every call
// once the peer exists and once right after nativeCreate for a colour set
// before it (King's order); both ride the main queue, so create comes first.
void JNICALL NativeSetBackgroundColor(JNIEnv*, jclass, jint viewId, jint argb) {
    if (!WvBaseColorEnabled()) return;
    const uint32_t c = (uint32_t)argb;
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        h.baseColor = c;
        WvApplyBase(h);
    });
}

void JNICALL NativeSetBackdrop(JNIEnv*, jclass, jint viewId, jint argb, jboolean known) {
    if (!WvBaseColorEnabled()) return;
    const uint32_t c = (uint32_t)argb;
    const BOOL k = known == JNI_TRUE;
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        if (h.backdropKnown == k && (!k || h.backdrop == c)) return;
        h.backdrop = c;
        h.backdropKnown = k;
        WvApplyBase(h);
    });
}

void JNICALL NativeApplySettings(JNIEnv* env, jclass, jint viewId,
                                 jboolean jsEnabled, jstring jUserAgent) {
    NSString* ua = FromJString(env, jUserAgent);
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        h.jsEnabled = (jsEnabled == JNI_TRUE);
        if (ua.length && ![ua isEqualToString:h.wv.customUserAgent])
            h.wv.customUserAgent = ua;
    });
}

static void NativeLoadDataNow(MacApkWvHolder* h, NSString* baseUrl, NSString* data,
                              NSString* mime, NSString* enc, NSURL* base);

// WebSettings.setAllowFileAccessFromFileURLs (or ...Universal...) -> WKPreferences
// allowFileAccessFromFileURLs, applied to the LIVE view before the load that needs it
// (measured on a headless WKWebView: setting it on wv.configuration.preferences after
// creation lets a file: document's <script type="module" src="file:///..."> run;
// without it WebKit fires the script's error event and the module never executes).
// The key is WebKit SPI with no public setter; KVC is the documented-by-practice way.
void JNICALL NativeSetFileUrlAccess(JNIEnv* env, jclass, jint viewId, jboolean allow,
                                    jstring jReadRoot) {
    NSString* readRoot = jReadRoot ? FromJString(env, jReadRoot) : nil;
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        if (!(readRoot.length ? [readRoot isEqualToString:h.readGrantRoot ?: @""]
                              : h.readGrantRoot == nil)) {
            h.readGrantRoot = readRoot.length ? readRoot : nil;
            h.readGranted = NO;
        }
        @try {
            [h.wv.configuration.preferences setValue:@(allow == JNI_TRUE)
                                              forKey:@"allowFileAccessFromFileURLs"];
            if (WvTraceEnabled())
                std::fprintf(stderr, "[T] webview: id=%d allowFileAccessFromFileURLs=%d\n",
                             (int)viewId, allow == JNI_TRUE);
        } @catch (NSException* e) {
            std::fprintf(stderr, "[W] webview: id=%d allowFileAccessFromFileURLs not settable: %s\n",
                         (int)viewId, e.reason.UTF8String);
        }
    });
}

// The main-queue half of loadUrl. `allowFileOrigin` is NO on the retry after
// the Java side declined to copy an asset page, so that load takes the
// macapk-asset route exactly as before MACAPK_WEBVIEW_FILE_ORIGIN.
static void WvLoadUrlOnMain(int viewId, NSString* url, NSArray<NSString*>* headers,
                            bool allowFileOrigin) {
    MacApkWvHolder* h = HolderFor(viewId);
    if (!h || !url.length) return;
    ++h.loadGen;   // supersedes a data load still waiting on its read grant
    NSURL* u = [NSURL URLWithString:url];
    if (!u) {
        std::fprintf(stderr, "[W] webview: id=%d loadUrl unparseable: %s\n",
                     (int)viewId, url.UTF8String);
        return;
    }
    NSString* assetUrl = WvHostUrl(url);
    NSURL* au = assetUrl ? [NSURL URLWithString:assetUrl] : nil;
    if (!au) assetUrl = nil;
    // An android_asset PAGE on a real file: origin (MACAPK_WEBVIEW_FILE_ORIGIN;
    // MacApkWebViewBridge.macapkMaterializeAssetPage says why). The Java side
    // answers nil when the gate is off or the directory is over its cap, and the
    // load then takes the scheme route below. The copy is file I/O, so it runs
    // on the asset queue; a later load (loadGen) supersedes this one meanwhile.
    if (assetUrl && allowFileOrigin && g_mid_asset_page
        && [u.path hasPrefix:@"/android_asset/"]) {
        const NSUInteger gen = h.loadGen;
        dispatch_async(g_asset_queue, ^{
            NSArray<NSString*>* r = WvMaterializeAssetPage(viewId, url);
            dispatch_async(dispatch_get_main_queue(), ^{
                MacApkWvHolder* h2 = HolderFor(viewId);
                if (!h2 || h2.loadGen != gen) return;
                if (!r) { WvLoadUrlOnMain(viewId, url, headers, false); return; }
                NSURL* root = [NSURL fileURLWithPath:r[2] isDirectory:YES];
                WvNoteFileOriginRoot(root.absoluteString);
                NSURLComponents* src = [NSURLComponents componentsWithURL:u
                                                  resolvingAgainstBaseURL:NO];
                NSURLComponents* fc = [NSURLComponents
                    componentsWithURL:[NSURL fileURLWithPath:r[0]] resolvingAgainstBaseURL:NO];
                fc.percentEncodedQuery = src.percentEncodedQuery;
                fc.percentEncodedFragment = src.percentEncodedFragment;
                NSURL* fu = fc.URL ?: [NSURL fileURLWithPath:r[0]];
                if (WvTraceEnabled())
                    std::fprintf(stderr, "[T] webview: id=%d loadUrl app asset page on a "
                                 "file: origin %s\n", viewId,
                                 WvFlatten(fu.absoluteString, 300).UTF8String);
                [h2.wv loadFileURL:fu
                    allowingReadAccessToURL:[NSURL fileURLWithPath:r[1] isDirectory:YES]];
                NSString* asked = h2.wv.URL.absoluteString;
                [h2 notePendingProgrammaticUrl:(asked.length ? asked : fu.absoluteString)];
            });
        });
        return;
    }
    if (assetUrl) {
        // file:///android_asset/ or file:///android_res/: served from the
        // APK by the macapk-asset handler, never from the host filesystem.
        u = au;
        if (WvTraceEnabled())
            std::fprintf(stderr, "[T] webview: id=%d loadUrl app asset %s\n",
                         (int)viewId, WvFlatten(url, 300).UTF8String);
    }
    if (!assetUrl && [u.scheme isEqualToString:@"file"]) {
        // Local content: grant read access to the containing directory so
        // relative subresources load, matching Android's file:// behavior
        // under setAllowFileAccess.
        NSURL* dir = [u URLByDeletingLastPathComponent];
        [h.wv loadFileURL:u allowingReadAccessToURL:dir];
    } else {
        NSMutableURLRequest* req = [NSMutableURLRequest requestWithURL:u];
        for (NSUInteger i = 0; headers && i + 1 < headers.count; i += 2)
            [req setValue:headers[i + 1] forHTTPHeaderField:headers[i]];
        [h.wv loadRequest:req];
    }
    // Noted AFTER the load call: wv.URL is then the normalized URL WebKit
    // asks policy with (the policy ask itself arrives later on this queue).
    NSString* asked = h.wv.URL.absoluteString;
    [h notePendingProgrammaticUrl:(asked.length ? asked : url)];
}

void JNICALL NativeLoadUrl(JNIEnv* env, jclass, jint viewId, jstring jUrl,
                           jobjectArray jHeaderKv) {
    NSString* url = FromJString(env, jUrl);
    NSMutableArray<NSString*>* headers = nil;
    if (jHeaderKv) {
        jsize n = env->GetArrayLength(jHeaderKv);
        headers = [NSMutableArray arrayWithCapacity:(NSUInteger)n];
        for (jsize i = 0; i < n; i++) {
            jstring js = (jstring)env->GetObjectArrayElement(jHeaderKv, i);
            NSString* s = FromJString(env, js);
            [headers addObject:s ?: @""];
            if (js) env->DeleteLocalRef(js);
        }
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        WvLoadUrlOnMain((int)viewId, url, headers, true);
    });
}

void JNICALL NativeLoadData(JNIEnv* env, jclass, jint viewId, jstring jBaseUrl,
                            jstring jData, jstring jMime, jstring jEncoding) {
    NSString* baseUrl = FromJString(env, jBaseUrl);
    NSString* data = FromJString(env, jData);
    NSString* mime = FromJString(env, jMime);
    NSString* enc = FromJString(env, jEncoding);
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h || !data) return;
        // loadDataWithBaseURL("file:///android_asset/", ...) is the common way
        // to give inline HTML access to bundled css/js/images: the base moves to
        // the macapk-asset scheme so those relative references are served.
        NSString* assetBase = WvHostUrl(baseUrl);
        NSURL* base = assetBase ? [NSURL URLWithString:assetBase]
                    : (baseUrl.length ? [NSURL URLWithString:baseUrl] : nil);
        const NSUInteger gen = ++h.loadGen;
        // A file: base on a WebView whose app turned file access on reads the
        // app's own files (AOSP: setAllowFileAccess + loadDataWithBaseURL). Get
        // the WebContent process its read grant first (see readGrantRoot).
        if (!assetBase && base.isFileURL && h.readGrantRoot.length && !h.readGranted
            && !h.readGrantNav) {
            NSString* dir = [h.readGrantRoot stringByAppendingPathComponent:@".macapk/webview"];
            NSString* blank = [dir stringByAppendingPathComponent:@"read-grant.html"];
            [[NSFileManager defaultManager] createDirectoryAtPath:dir
                withIntermediateDirectories:YES attributes:nil error:nil];
            if (![[NSFileManager defaultManager] fileExistsAtPath:blank])
                [@"<!doctype html><title></title>" writeToFile:blank atomically:YES
                                                   encoding:NSUTF8StringEncoding error:nil];
            NSURL* bu = [NSURL fileURLWithPath:blank];
            __weak MacApkWvHolder* wh = h;
            h.afterReadGrant = ^{
                MacApkWvHolder* s = wh;
                if (s && s.loadGen == gen)
                    NativeLoadDataNow(s, baseUrl, data, mime, enc, base);
            };
            h.readGrantUrl = bu.absoluteString;
            h.readGrantNav = [h.wv loadFileURL:bu
                       allowingReadAccessToURL:[NSURL fileURLWithPath:h.readGrantRoot isDirectory:YES]];
            if (!h.readGrantNav) [h endReadGrant:NO];
            return;
        }
        NativeLoadDataNow(h, baseUrl, data, mime, enc, base);
    });
}

static void NativeLoadDataNow(MacApkWvHolder* h, NSString* baseUrl, NSString* data,
                              NSString* mime, NSString* enc, NSURL* base) {
    {
        // loadHTMLString navigates "to" the base URL (about:blank when nil)
        // and that navigation DOES pass through decidePolicy -- measured on
        // wvtest, where the loadDataWithBaseURL load surfaced through
        // shouldOverrideUrlLoading until marked programmatic here. Android
        // never reports app-initiated data loads there.
        WKNavigation* nav = nil;
        if (!mime.length || [mime isEqualToString:@"text/html"]) {
            nav = [h.wv loadHTMLString:data baseURL:base];
        } else {
            NSData* bytes = [data dataUsingEncoding:NSUTF8StringEncoding];
            nav = [h.wv loadData:bytes MIMEType:mime
                characterEncodingName:(enc.length ? enc : @"UTF-8") baseURL:base];
        }
        // loadDataWithBaseURL with a non-data: base grants THIS document
        // local-resource access (AwContents.loadUrl; see
        // -topDocumentMayLoadAppAssets). WebView.loadDataWithBaseURL passes a
        // null base on as "about:blank" (AwContents.fixupBase), so the null base
        // that reaches us here is plain loadData(): a data: URL on a device,
        // which gets no grant.
        h.grantNavigation = (WvAssetSchemeEnabled() && baseUrl.length
                             && ![baseUrl.lowercaseString hasPrefix:@"data:"]) ? nav : nil;
        NSString* asked = h.wv.URL.absoluteString;
        [h notePendingProgrammaticUrl:(asked.length ? asked
                                       : (baseUrl.length ? baseUrl : @"about:blank"))];
    }
}

void JNICALL NativeGo(JNIEnv*, jclass, jint viewId, jint steps) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        WKBackForwardListItem* item = nil;
        if (steps < 0) {
            NSArray* back = h.wv.backForwardList.backList;
            NSInteger idx = (NSInteger)back.count + steps;
            if (idx >= 0 && idx < (NSInteger)back.count) item = back[(NSUInteger)idx];
        } else if (steps > 0) {
            NSArray* fwd = h.wv.backForwardList.forwardList;
            if (steps - 1 < (NSInteger)fwd.count) item = fwd[(NSUInteger)(steps - 1)];
        }
        if (item) {
            [h notePendingProgrammaticUrl:item.URL.absoluteString];
            [h.wv goToBackForwardListItem:item];
        }
    });
}

void JNICALL NativeReload(JNIEnv*, jclass, jint viewId) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        [h notePendingProgrammaticUrl:h.wv.URL.absoluteString];
        WKNavigation* nav = [h.wv reload];
        // Reloading the loadDataWithBaseURL document keeps its local-resource
        // grant: Chromium keeps it on the NavigationEntry and re-sends it with
        // every commit of that entry (navigation_entry_impl.cc,
        // ConstructCommitNavigationParams -> GetCanLoadLocalResources()).
        if (h.committedTopGranted) h.grantNavigation = nav;
    });
}

void JNICALL NativeStopLoading(JNIEnv*, jclass, jint viewId) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [HolderFor(viewId).wv stopLoading];
    });
}

void JNICALL NativeEvaluateJs(JNIEnv* env, jclass, jint viewId, jstring jScript,
                              jint callbackId) {
    NSString* script = FromJString(env, jScript);
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h || !script) {
            if (callbackId) {
                dispatch_async(g_event_queue, ^{
                    PostEvent(viewId, EV_JS_RESULT, @"null", nil, nil, callbackId);
                });
            }
            return;
        }
        [h.wv evaluateJavaScript:script completionHandler:^(id result, NSError* e) {
            if (!callbackId) return;
            NSString* json = e ? @"null" : JsonEncodeResult(result);
            dispatch_async(g_event_queue, ^{
                PostEvent(viewId, EV_JS_RESULT, json, nil, nil, callbackId);
            });
        }];
    });
}

// Replace the whole user-script set with one injection script (the Java side
// composes it from every registered @JavascriptInterface object). Effective
// on the NEXT page load, exactly as AOSP documents for addJavascriptInterface.
void JNICALL NativeSetInjectionScript(JNIEnv* env, jclass, jint viewId, jstring jScript) {
    NSString* script = FromJString(env, jScript);
    dispatch_async(dispatch_get_main_queue(), ^{
        MacApkWvHolder* h = HolderFor(viewId);
        if (!h) return;
        WKUserContentController* ucc = h.wv.configuration.userContentController;
        [ucc removeAllUserScripts];
        WvInstallTraceScript(ucc);   // the trace hook survives the app's script set
        WvInstallScrollScript(ucc);  // and so does the page-scroll report
        WvInstallConsoleScript(ucc); // and the console -> onConsoleMessage hook
        WvInstallXhrDoneOnceScript(ucc); // and XHR DONE-once
        if (script.length) {
            WKUserScript* us = [[WKUserScript alloc]
                  initWithSource:script
                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                forMainFrameOnly:NO];
            [ucc addUserScript:us];
        }
    });
}

// Java answered shouldOverrideUrlLoading. allow == !overridden.
void JNICALL NativeNavDecision(JNIEnv*, jclass, jint decisionId, jboolean allow) {
    dispatch_async(dispatch_get_main_queue(), ^{
        void (^handler)(WKNavigationActionPolicy) = g_nav_decisions[@(decisionId)];
        if (!handler) return;  // watchdog already resolved it
        [g_nav_decisions removeObjectForKey:@(decisionId)];
        if (WvTraceEnabled())
            std::fprintf(stderr, "[T] webview: NAV-ANSWER decision=%d %s\n",
                         (int)decisionId, allow == JNI_TRUE ? "allow" : "CANCEL");
        handler(allow == JNI_TRUE ? WKNavigationActionPolicyAllow
                                  : WKNavigationActionPolicyCancel);
    });
}

// Java answered a JS dialog. The three dialog kinds park BLOCKS OF DIFFERENT
// ARITY in g_js_dialogs (alert ()->void, confirm (BOOL)->void, prompt
// (NSString*)->void), and invoking a block through the wrong arity is UB --
// so the kind must arrive explicitly. `kind` is 11/12/13 (the EV_JS_* code
// Java received the dialog under and echoes back verbatim). For prompt,
// jText carries the typed string when confirmed; nil/cancelled -> nil.
void JNICALL NativeJsDialogDone(JNIEnv* env, jclass, jint resultId, jint kind,
                                jboolean confirmed, jstring jText) {
    NSString* text = FromJString(env, jText);
    dispatch_async(dispatch_get_main_queue(), ^{
        id done = g_js_dialogs[@(resultId)];
        if (!done) return;
        [g_js_dialogs removeObjectForKey:@(resultId)];
        switch (kind) {
            case EV_JS_ALERT: {
                void (^blk)(void) = done;
                blk();
                break;
            }
            case EV_JS_CONFIRM: {
                void (^blk)(BOOL) = done;
                blk(confirmed == JNI_TRUE);
                break;
            }
            case EV_JS_PROMPT: {
                void (^blk)(NSString*) = done;
                blk(confirmed == JNI_TRUE ? (text ?: @"") : nil);
                break;
            }
            default:
                std::fprintf(stderr, "[E] webview: finishJsDialog bad kind=%d\n",
                             (int)kind);
                break;
        }
    });
}

// ---- synchronous CookieManager backing ------------------------------------
// Guest thread parks on a semaphore (bounded) while the main queue answers.
// Never called on the macOS main thread by construction (guest threads only),
// but guard anyway: run inline if we ARE main.

constexpr int64_t kCookieWaitNs = 2 * NSEC_PER_SEC;

jstring JNICALL NativeCookieGet(JNIEnv* env, jclass, jstring jUrl) {
    NSString* url = FromJString(env, jUrl);
    if (!url.length) return nullptr;
    NSURL* u = [NSURL URLWithString:url];
    if (!u || !u.host) return nullptr;
    __block NSString* result = nil;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    void (^work)(void) = ^{
        WKWebsiteDataStore* store = g_data_store ?: [WKWebsiteDataStore defaultDataStore];
        [store.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie*>* cookies) {
            NSString* host = u.host.lowercaseString;
            BOOL secure = [u.scheme isEqualToString:@"https"];
            NSString* path = u.path.length ? u.path : @"/";
            NSMutableString* s = [NSMutableString string];
            for (NSHTTPCookie* c in cookies) {
                NSString* d = c.domain.lowercaseString;
                BOOL match;
                if ([d hasPrefix:@"."]) {
                    NSString* bare = [d substringFromIndex:1];
                    match = [host isEqualToString:bare] ||
                            [host hasSuffix:[@"." stringByAppendingString:bare]];
                } else {
                    match = [host isEqualToString:d] ||
                            [host hasSuffix:[@"." stringByAppendingString:d]];
                }
                if (!match) continue;
                if (c.secure && !secure) continue;
                if (c.path.length && ![path hasPrefix:c.path]) continue;
                if (s.length) [s appendString:@"; "];
                [s appendFormat:@"%@=%@", c.name, c.value];
            }
            if (WvTraceEnabled()) {
                // NAMES ONLY, never values: these cookies are the signed-in
                // account's credentials and the log is a plain file on disk.
                NSMutableArray* names = [NSMutableArray array];
                for (NSHTTPCookie* c in cookies) [names addObject:c.name];
                std::fprintf(stderr, "[T] webview: COOKIE-GET host=%s -> %lu matched, jar=[%s]\n",
                             host.UTF8String, (unsigned long)(s.length ? 1 : 0),
                             WvFlatten([names componentsJoinedByString:@","], 600).UTF8String);
            }
            result = s.length ? s : nil;
            dispatch_semaphore_signal(sem);
        }];
    };
    if ([NSThread isMainThread]) {
        // Cannot park the main thread against its own queue; drain the runloop
        // until the async cookie fetch answers (bounded).
        work();
        NSDate* deadline = [NSDate dateWithTimeIntervalSinceNow:2.0];
        while (dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW) != 0 &&
               [deadline timeIntervalSinceNow] > 0) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                     beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
        }
    } else {
        dispatch_async(dispatch_get_main_queue(), work);
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, kCookieWaitNs));
    }
    return ToJString(env, result);   // nil -> null, as before
}

void JNICALL NativeCookieSet(JNIEnv* env, jclass, jstring jUrl, jstring jValue) {
    NSString* url = FromJString(env, jUrl);
    NSString* value = FromJString(env, jValue);
    if (!url.length || !value.length) return;
    NSURL* u = [NSURL URLWithString:url];
    if (!u) return;
    // Let the system's Set-Cookie parser handle attributes/expiry/domain.
    NSArray<NSHTTPCookie*>* parsed =
        [NSHTTPCookie cookiesWithResponseHeaderFields:@{@"Set-Cookie" : value}
                                               forURL:u];
    if (!parsed.count) return;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        WKWebsiteDataStore* store = g_data_store ?: [WKWebsiteDataStore defaultDataStore];
        __block NSUInteger remaining = parsed.count;
        for (NSHTTPCookie* c in parsed) {
            [store.httpCookieStore setCookie:c completionHandler:^{
                if (--remaining == 0) dispatch_semaphore_signal(sem);
            }];
        }
    });
    if (![NSThread isMainThread])
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, kCookieWaitNs));
}

void JNICALL NativeCookieRemoveAll(JNIEnv*, jclass) {
    dispatch_async(dispatch_get_main_queue(), ^{
        WKWebsiteDataStore* store = g_data_store ?: [WKWebsiteDataStore defaultDataStore];
        [store.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie*>* cookies) {
            for (NSHTTPCookie* c in cookies)
                [store.httpCookieStore deleteCookie:c completionHandler:nil];
        }];
    });
}

jboolean JNICALL NativeCookieHas(JNIEnv*, jclass) {
    __block BOOL has = NO;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    void (^work)(void) = ^{
        WKWebsiteDataStore* store = g_data_store ?: [WKWebsiteDataStore defaultDataStore];
        [store.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie*>* cookies) {
            has = cookies.count > 0;
            dispatch_semaphore_signal(sem);
        }];
    };
    if ([NSThread isMainThread]) {
        work();
        NSDate* deadline = [NSDate dateWithTimeIntervalSinceNow:2.0];
        while (dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW) != 0 &&
               [deadline timeIntervalSinceNow] > 0) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                     beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
        }
    } else {
        dispatch_async(dispatch_get_main_queue(), work);
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, kCookieWaitNs));
    }
    return has ? JNI_TRUE : JNI_FALSE;
}

jboolean JNICALL NativeAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

const JNINativeMethod kWebViewBridgeMethods[] = {
    {(char*)"nativeAvailable", (char*)"()Z", (void*)NativeAvailable},
    {(char*)"nativeInit", (char*)"(Ljava/lang/String;)V", (void*)NativeInit},
    {(char*)"nativeCreate", (char*)"(ILjava/lang/String;Ljava/lang/String;Z)V",
     (void*)NativeCreate},
    {(char*)"nativeDestroy", (char*)"(I)V", (void*)NativeDestroy},
    {(char*)"nativeCreateOptions", (char*)"(IZ)V", (void*)NativeCreateOptions},
    {(char*)"nativeSetFrame", (char*)"(IIIIIIIZ)V", (void*)NativeSetFrame},
    {(char*)"nativeSetOccluders", (char*)"(I[I)V", (void*)NativeSetOccluders},
    {(char*)"nativeSetBackgroundColor", (char*)"(II)V", (void*)NativeSetBackgroundColor},
    {(char*)"nativeSetBackdrop", (char*)"(IIZ)V", (void*)NativeSetBackdrop},
    {(char*)"nativeSetFileUrlAccess", (char*)"(IZLjava/lang/String;)V", (void*)NativeSetFileUrlAccess},
    {(char*)"nativeApplySettings", (char*)"(IZLjava/lang/String;)V",
     (void*)NativeApplySettings},
    {(char*)"nativeLoadUrl", (char*)"(ILjava/lang/String;[Ljava/lang/String;)V",
     (void*)NativeLoadUrl},
    {(char*)"nativeLoadData",
     (char*)"(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
     (void*)NativeLoadData},
    {(char*)"nativeGo", (char*)"(II)V", (void*)NativeGo},
    {(char*)"nativeReload", (char*)"(I)V", (void*)NativeReload},
    {(char*)"nativeStopLoading", (char*)"(I)V", (void*)NativeStopLoading},
    {(char*)"nativeEvaluateJs", (char*)"(ILjava/lang/String;I)V", (void*)NativeEvaluateJs},
    {(char*)"nativeSetInjectionScript", (char*)"(ILjava/lang/String;)V",
     (void*)NativeSetInjectionScript},
    {(char*)"nativeNavDecision", (char*)"(IZ)V", (void*)NativeNavDecision},
    {(char*)"nativeJsDialogDone", (char*)"(IIZLjava/lang/String;)V",
     (void*)NativeJsDialogDone},
    {(char*)"nativeCookieGet", (char*)"(Ljava/lang/String;)Ljava/lang/String;",
     (void*)NativeCookieGet},
    {(char*)"nativeCookieSet", (char*)"(Ljava/lang/String;Ljava/lang/String;)V",
     (void*)NativeCookieSet},
    {(char*)"nativeCookieRemoveAll", (char*)"()V", (void*)NativeCookieRemoveAll},
    {(char*)"nativeCookieHas", (char*)"()Z", (void*)NativeCookieHas},
};

}  // namespace

namespace macapk {
namespace webview {

void RegisterWebViewBridgeMethods(JNIEnv* env) {
    jclass cls = env->FindClass("android/webkit/MacApkWebViewBridge");
    if (!cls) {
        // The framework jar always carries the class; a miss means a build
        // mismatch. Follow the soft pattern (camera/audio) rather than abort:
        // WebView then reports the engine unavailable instead of killing boot.
        std::fprintf(stderr,
                     "[E] webview: FindClass(android.webkit.MacApkWebViewBridge) "
                     "failed; WebView engine disabled this run\n");
        env->ExceptionClear();
        return;
    }
    if (env->RegisterNatives(cls, kWebViewBridgeMethods,
                             sizeof(kWebViewBridgeMethods) /
                                 sizeof(kWebViewBridgeMethods[0])) != JNI_OK) {
        std::fprintf(stderr, "[E] webview: RegisterNatives failed\n");
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return;
    }
    g_bridge_cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    g_mid_on_event = env->GetStaticMethodID(
        g_bridge_cls, "onEvent",
        "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    g_mid_js_bridge = env->GetStaticMethodID(
        g_bridge_cls, "onJsBridgeCall", "(ILjava/lang/String;)Ljava/lang/String;");
    if (!g_mid_on_event || !g_mid_js_bridge) {
        std::fprintf(stderr, "[E] webview: trampoline methods missing\n");
        env->ExceptionClear();
    }
    g_mid_read_asset = env->GetStaticMethodID(
        g_bridge_cls, "macapkReadAsset", "(ILjava/lang/String;)[B");
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_mid_asset_mime = env->GetStaticMethodID(
        g_bridge_cls, "macapkAssetMime", "(Ljava/lang/String;[B)Ljava/lang/String;");
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_mid_real_input = env->GetStaticMethodID(g_bridge_cls, "onRealInput", "()V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_mid_asset_page = env->GetStaticMethodID(
        g_bridge_cls, "macapkMaterializeAssetPage", "(ILjava/lang/String;)[Ljava/lang/String;");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!g_mid_read_asset || !g_mid_asset_mime) {
        // Every file:///android_asset load would then fail as not-found; make
        // the jar/runtime mismatch loud instead.
        std::fprintf(stderr, "[E] webview: app-asset readers missing; "
                     "file:///android_asset/ will not load\n");
    }
    env->GetJavaVM(&g_vm);
    g_jsbridge_queue = dispatch_queue_create("org.macapk.jsbridge", DISPATCH_QUEUE_SERIAL);
    g_event_queue = dispatch_queue_create("org.macapk.webview-events", DISPATCH_QUEUE_SERIAL);
    g_asset_queue = dispatch_queue_create("org.macapk.webview-assets", DISPATCH_QUEUE_CONCURRENT);
    // Host-view geometry changes (window resize / rotate) must re-place every
    // attached WKWebView; the per-frame Java pushes only fire when the ANDROID
    // rect changes. Under MACAPK_WEBVIEW_HOST_FOLLOW only the pages already ON
    // this host are re-placed: moving a page to a new host is its Java
    // WebView's call (see WvHostFollowEnabled).
    [[NSNotificationCenter defaultCenter]
        addObserverForName:NSViewFrameDidChangeNotification
                    object:nil
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification* note) {
                    if (g_holders.count == 0) return;
                    NSView* host = WvHostView();
                    if (note.object != host) return;
                    if (!WvHostFollowEnabled()) { ApplyAllFrames(); return; }
                    for (NSNumber* k in [g_holders allKeys]) {
                        MacApkWvHolder* h = g_holders[k];
                        if (h.wv.superview == host) ApplyFrame(h);
                    }
                }];
    std::fprintf(stderr, "[I] webview: WKWebView bridge registered\n");
}

}  // namespace webview
}  // namespace macapk

// ---------------------------------------------------------------------------
// MacApkWebViewCheckpointStores -- flush WebKit's SQLite write-ahead logs on exit.
//
// WHY. The process ends with _exit() (cocoa_bridge.mm), as an Android process
// death does, which closes none of the databases WebKit's UI-process half holds
// open in ~/Library/WebKit/<bundle id>/WebsiteDataStore/<uuid>/ (the
// EnhancedSecurity sites DB, AlternativeServices, ResourceLoadStatistics).
// Each leaves a non-empty "-wal". The NEXT launch's first open of such a store
// has to RECOVER it, and recovery writes a notice through sqlite3_log
// (walIndexRecover -> "recovered N frames"); on 2026-11-03 that call crashed
// Instagram's login (docs entry v1.0.2370). A WAL checkpointed to zero length
// has no frames to recover, so nothing is logged and the next open is a plain
// one -- the state every app is already in after a store was closed normally.
//
// WHAT. For every "*-wal" under this app's WebKit folder that is not empty,
// open the database read-write with a short busy timeout, run
// PRAGMA wal_checkpoint(TRUNCATE) and close. If WebKit still holds a write
// lock the checkpoint answers BUSY and we leave that file alone: best effort,
// bounded to about a second overall, never blocks the exit.
// Called from the quit and backstop paths just before _exit, on a non-main
// thread, so it touches only the filesystem and SQLite, never AppKit/WebKit.
// ---------------------------------------------------------------------------
// BEGIN MacApkWebViewCheckpointStores (debug/webview_wal_gate.sh compiles exactly this text)
extern "C" void MacApkWebViewCheckpointStores(void) {
    @autoreleasepool {
        // MACAPK_WEBKIT_ROOT: the folder to scan, for the offline gate only.
        const char* seam = getenv("MACAPK_WEBKIT_ROOT");
        NSString* root;
        if (seam && *seam) {
            root = [NSString stringWithUTF8String:seam];
        } else {
            NSString* bid = NSBundle.mainBundle.bundleIdentifier;
            if (!bid.length) return;
            root = [NSHomeDirectory() stringByAppendingPathComponent:
                    [@"Library/WebKit" stringByAppendingPathComponent:bid]];
        }
        NSFileManager* fm = NSFileManager.defaultManager;
        BOOL isDir = NO;
        if (![fm fileExistsAtPath:root isDirectory:&isDir] || !isDir) return;
        // The calls are resolved at run time (the system libsqlite3 is always
        // loaded in this process), so this file adds no link dependency for the
        // test drivers that compile it on its own.
        struct Q {
            int (*open)(const char*, sqlite3**, int, const char*);
            int (*close)(sqlite3*);
            int (*busy)(sqlite3*, int);
            int (*exec)(sqlite3*, const char*, int (*)(void*, int, char**, char**), void*, char**);
            int (*ckpt)(sqlite3*, const char*, int, int*, int*);
        } q;
        void* lib = dlopen("/usr/lib/libsqlite3.dylib", RTLD_LAZY | RTLD_NOLOAD);
        if (!lib) lib = dlopen("/usr/lib/libsqlite3.dylib", RTLD_LAZY);
        if (!lib) return;
        q.open  = (decltype(q.open))dlsym(lib, "sqlite3_open_v2");
        q.close = (decltype(q.close))dlsym(lib, "sqlite3_close");
        q.busy  = (decltype(q.busy))dlsym(lib, "sqlite3_busy_timeout");
        q.exec  = (decltype(q.exec))dlsym(lib, "sqlite3_exec");
        q.ckpt  = (decltype(q.ckpt))dlsym(lib, "sqlite3_wal_checkpoint_v2");
        if (!q.open || !q.close || !q.busy || !q.exec || !q.ckpt) return;
        const CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
        int done = 0, busy = 0;
        NSDirectoryEnumerator* en = [fm enumeratorAtPath:root];
        for (NSString* rel in en) {
            if (CFAbsoluteTimeGetCurrent() - t0 > 1.0) break;
            if (![rel hasSuffix:@"-wal"]) continue;
            NSString* wal = [root stringByAppendingPathComponent:rel];
            NSDictionary* at = [fm attributesOfItemAtPath:wal error:nil];
            if ([at fileSize] == 0) continue;
            NSString* db = [wal substringToIndex:wal.length - 4];
            sqlite3* h = nullptr;
            if (q.open(db.fileSystemRepresentation, &h, SQLITE_OPEN_READWRITE, nullptr) != SQLITE_OK) {
                if (h) q.close(h);
                continue;
            }
            q.busy(h, 100);
            // A fresh connection has not attached the WAL yet and a checkpoint
            // on it is a silent no-op: touch the schema first.
            q.exec(h, "SELECT count(*) FROM sqlite_master", nullptr, nullptr, nullptr);
            int logFrames = 0, ckptFrames = 0;
            const int rc = q.ckpt(h, nullptr, SQLITE_CHECKPOINT_TRUNCATE, &logFrames, &ckptFrames);
            if (rc == SQLITE_OK) ++done; else ++busy;
            q.close(h);
        }
        if (done || busy)
            std::fprintf(stderr, "[quit] webview stores: checkpointed %d WAL file(s), %d busy\n", done, busy);
    }
}
// END MacApkWebViewCheckpointStores
