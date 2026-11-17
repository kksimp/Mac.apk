package android.webkit;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

// Mac.apk-internal glue between android.webkit.WebView and the native
// WKWebView engine (runtime/src/webview_bridge.mm). NOT an AOSP class; apps
// never reference it (AOSP hides its engine glue behind WebViewProvider the
// same way). Everything here is package-private or gated so the public
// android.webkit surface stays exactly AOSP's.
//
// Threading: the native side calls onEvent from its own serial event queue
// (order-preserving) and onJsBridgeCall from the dedicated JS-bridge queue
// (the Android "JavaBridge" background thread analog). onEvent only posts to
// the WebView's UI-thread Handler; onJsBridgeCall runs the app's
// @JavascriptInterface method ON the calling background thread, exactly as
// AOSP documents ("this method runs on a private, background thread").
final class MacApkWebViewBridge {

    private MacApkWebViewBridge() {}

    // Event codes -- keep in lockstep with webview_bridge.mm.
    static final int EV_PAGE_STARTED = 1;
    static final int EV_PAGE_FINISHED = 2;
    static final int EV_PAGE_COMMIT_VISIBLE = 3;
    static final int EV_RECEIVED_ERROR = 4;
    static final int EV_PROGRESS = 5;
    static final int EV_TITLE = 6;
    static final int EV_URL_CHANGED = 7;
    static final int EV_JS_RESULT = 8;
    static final int EV_SHOULD_OVERRIDE = 9;
    static final int EV_HISTORY_UPDATE = 10;
    static final int EV_JS_ALERT = 11;
    static final int EV_JS_CONFIRM = 12;
    static final int EV_JS_PROMPT = 13;
    static final int EV_RENDER_GONE = 14;
    static final int EV_HTTP_ERROR = 15;
    static final int EV_CLOSE_WINDOW = 16;
    /** v1.0.1643: a="scrollXpx,scrollYpx,contentHeightCss,pxPerCss". */
    static final int EV_PAGE_SCROLL = 17;
    /** 2026-09-26: the native host view this WebView's page sits in left the
     *  window (a content swap / SurfaceView rewire). No payload. The WebView
     *  re-places itself on its next draw (WebView.macapkDispatchEvent); see
     *  WvHostFollowEnabled in webview_bridge.mm. */
    static final int EV_HOST_LOST = 18;
    /** 2026-10-16: a page console message. a=message b=sourceUrl
     *  c=ConsoleMessage.MessageLevel ordinal i=line (webview_bridge.mm
     *  kWvConsoleHookJS). */
    static final int EV_CONSOLE = 19;
    /** MACAPK_WEBVIEW_HOST_FOLLOW, the native gate's Java half: =0 ignores
     *  EV_HOST_LOST (native does not post it then either). */
    static final boolean HOST_FOLLOW = !"0".equals(System.getenv("MACAPK_WEBVIEW_HOST_FOLLOW"));
    /** MACAPK_WEBVIEW_BASE_COLOR (2026-09-26, default ON), the Java half of the
     *  native gate (WvBaseColorEnabled in webview_bridge.mm): WebView
     *  .setBackgroundColor sets the PAGE BASE colour as AOSP does. =0 restores
     *  the pre-change path exactly: View.setBackgroundColor, nothing native. */
    static final boolean BASE_COLOR = !"0".equals(System.getenv("MACAPK_WEBVIEW_BASE_COLOR"));

    // ---- native surface (registered by RegisterWebViewBridgeMethods) ------
    static native boolean nativeAvailable();
    static native void nativeInit(String pkg);
    static native void nativeCreate(int viewId, String pkg, String userAgent,
                                    boolean jsEnabled);
    static native void nativeDestroy(int viewId);
    /** Creation-time options for the NEXT nativeCreate of `viewId` (sent just
     *  before it). WebKit reads allowUniversalAccessFromFileURLs only from the
     *  configuration a WKWebView is created with, so it cannot be applied later. */
    static native void nativeCreateOptions(int viewId, boolean universalFileAccess);
    /** MACAPK_WEBVIEW_UNIVERSAL_ACCESS (default ON): WebSettings
     *  .setAllowUniversalAccessFromFileURLs(true) reaches WebKit, so a file: page
     *  of an app that asked for it makes cross-origin requests without CORS, as
     *  Chromium's WebView does (Cordova asks for it on every file:// app). =0 sends
     *  nothing and file: pages stay CORS-checked. */
    static final boolean UNIVERSAL_ACCESS =
        !"0".equals(System.getenv("MACAPK_WEBVIEW_UNIVERSAL_ACCESS"));
    /** Mirrors the native MACAPK_WEBVIEW_TRACE gate so the Java side can log
     *  geometry under the same switch. */
    private static final boolean TRACE =
        System.getenv("MACAPK_WEBVIEW_TRACE") != null
        && !"0".equals(System.getenv("MACAPK_WEBVIEW_TRACE"));
    static boolean macapkTrace() { return TRACE; }

    static native void nativeSetFrame(int viewId, int x, int y, int w, int h,
                                      int surfW, int surfH, boolean visible);
    /** v1.0.1643: x,y,w,h quadruples (surface px) the app's own views cover
     *  over this WebView; native hit-testing sends clicks there to the
     *  Android view tree instead of the page. */
    static native void nativeSetOccluders(int viewId, int[] rects);
    /** WebView.setBackgroundColor: the page base colour, ARGB (AOSP
     *  AwContents.setBackgroundColor -> SetPageBaseBackgroundColor). */
    static native void nativeSetBackgroundColor(int viewId, int argb);
    /** MACAPK_WEBVIEW_BACKDROP: what the window drew BENEATH this WebView
     *  (ARGB), which a translucent page base composites over, as on a device;
     *  known=false when it is not a flat colour we can model. */
    static native void nativeSetBackdrop(int viewId, int argb, boolean known);
    /** MACAPK_WEBVIEW_BACKDROP (2026-10-01, default ON; =0 never sends a
     *  backdrop, so the native side keeps its pre-change decision exactly). */
    static final boolean BACKDROP = !"0".equals(System.getenv("MACAPK_WEBVIEW_BACKDROP"));
    static native void nativeApplySettings(int viewId, boolean jsEnabled,
                                           String userAgent);
    /** File access for this view (2026-10-02). {@code fromFileUrls}:
     *  WebSettings.setAllowFileAccessFromFileURLs / setAllowUniversalAccessFromFileURLs
     *  (either one; universal implies file-from-file in Chromium too) -> the WKWebView's
     *  allowFileAccessFromFileURLs preference. {@code readRoot}: the app's android root
     *  when WebSettings.setAllowFileAccess(true), else null -- a loadDataWithBaseURL
     *  page with a file: base then gets read access to the app's own files.
     *  MACAPK_WEBVIEW_FILE_URL_ACCESS=0 never sends either (the pre-change behaviour). */
    static native void nativeSetFileUrlAccess(int viewId, boolean fromFileUrls, String readRoot);
    static final boolean FILE_URL_ACCESS =
            !"0".equals(System.getenv("MACAPK_WEBVIEW_FILE_URL_ACCESS"));
    static native void nativeLoadUrl(int viewId, String url, String[] headerKv);
    static native void nativeLoadData(int viewId, String baseUrl, String data,
                                      String mime, String encoding);
    static native void nativeGo(int viewId, int steps);
    static native void nativeReload(int viewId);
    static native void nativeStopLoading(int viewId);
    static native void nativeEvaluateJs(int viewId, String script, int callbackId);
    static native void nativeSetInjectionScript(int viewId, String script);
    static native void nativeNavDecision(int decisionId, boolean allow);
    static native void nativeJsDialogDone(int resultId, int kind, boolean confirmed,
                                          String text);
    static native String nativeCookieGet(String url);
    static native void nativeCookieSet(String url, String value);
    static native void nativeCookieRemoveAll();
    static native boolean nativeCookieHas();

    // ---- availability -------------------------------------------------------

    private static volatile int sAvailable = -1;  // -1 unknown, 0 no, 1 yes

    /** True when the native WKWebView engine registered its methods this run.
     *  A miss (headless diagnostic runtime, registration soft-fail) leaves the
     *  pre-engine stub behavior in place rather than throwing. */
    static boolean engineAvailable() {
        int a = sAvailable;
        if (a == -1) {
            try {
                a = nativeAvailable() ? 1 : 0;
            } catch (UnsatisfiedLinkError e) {
                a = 0;
            }
            sAvailable = a;
        }
        return a == 1;
    }

    /** Lazily creates the shared per-app website data store (cookie jar) so
     *  CookieManager works before any WebView has been constructed, exactly as
     *  it does on a device. Safe to call repeatedly. */
    static void ensureDataStore() {
        if (!engineAvailable()) return;
        String pkg = null;
        try {
            android.app.Application app = android.app.ActivityThread.currentApplication();
            if (app != null) pkg = app.getPackageName();
        } catch (Throwable ignored) {
        }
        nativeInit(dataStoreKey(pkg));
    }

    /** MACAPK_WEBVIEW_STORE_PER_ROOT=0: every copy of a package shares one
     *  website data store again (the pre-v1.0.2252 key), for one A/B. */
    static final boolean STORE_PER_ROOT =
        !"0".equals(System.getenv("MACAPK_WEBVIEW_STORE_PER_ROOT"));

    /** The string the native side hashes into this app's WKWebsiteDataStore
     *  identifier (webview_bridge.mm EnsureDataStore).
     *
     *  On a device the WebView's cookies and storage live in the app's OWN data
     *  directory (AwBrowserProcess: context.getDir("webview") =
     *  /data/data/<pkg>/app_webview), so two copies of an app with different
     *  data never share a sign-in, and clearing the data clears it. The key was
     *  the package name alone, so every copy of a package shared one jar: a
     *  scratch or harness run of an app read and wrote the installed app's
     *  sign-ins (measured 2026-10-19 with Metrolist: a sign-in made in a
     *  scratch run became the installed app's session).
     *
     *  An installed bundle (root <X>.app/Contents/android) keeps the package
     *  key every install has used so far, so existing sign-ins survive; any
     *  other android root gets a store of its own, keyed by package + root. */
    static String dataStoreKey(String pkg) {
        String p = (pkg != null && !pkg.isEmpty()) ? pkg : "default";
        if (!STORE_PER_ROOT) return p;
        String root;
        try { root = com.macapk.loader.SharedStorage.androidRoot(); }
        catch (Throwable t) { return p; }
        if (root == null || root.isEmpty()) return p;
        java.io.File rootF = new java.io.File(root);
        java.io.File contents = rootF.getParentFile();
        java.io.File bundle = contents != null ? contents.getParentFile() : null;
        if ("android".equals(rootF.getName()) && contents != null
                && "Contents".equals(contents.getName()) && bundle != null
                && bundle.getName().endsWith(".app")) {
            return p;
        }
        String canon = root;
        try { canon = rootF.getCanonicalPath(); } catch (Throwable ignored) {}
        return p + "|" + canon;
    }

    // ---- registry -----------------------------------------------------------

    private static final Map<Integer, WeakReference<WebView>> sViews = new HashMap<>();

    static synchronized void register(int id, WebView wv) {
        sViews.put(id, new WeakReference<>(wv));
    }

    static synchronized void unregister(int id) {
        sViews.remove(id);
    }

    private static synchronized WebView viewFor(int id) {
        WeakReference<WebView> ref = sViews.get(id);
        return ref != null ? ref.get() : null;
    }

    // ---- upcalls from native ------------------------------------------------

    /** Called by native on its serial event queue. Posts to the WebView's
     *  UI thread; must never run app code on the calling thread. */
    static void onEvent(final int viewId, final int what, final String a,
                        final String b, final String c, final int i) {
        WebView wv = viewFor(viewId);
        if (wv == null) {
            // The WebView is gone (destroyed / GC'd) but native still awaits
            // an answer for decision-shaped events; a dropped answer would
            // park the page until the watchdog. Resolve them immediately.
            if (what == EV_SHOULD_OVERRIDE) nativeNavDecision(i, true);
            else if (what == EV_JS_ALERT || what == EV_JS_CONFIRM || what == EV_JS_PROMPT)
                nativeJsDialogDone(i, what, false, null);
            return;
        }
        Looper target = wv.getWebViewLooper();
        if (target == null) target = Looper.getMainLooper();
        new Handler(target).post(new Runnable() {
            @Override public void run() {
                WebView v = viewFor(viewId);
                if (v == null) {
                    if (what == EV_SHOULD_OVERRIDE) nativeNavDecision(i, true);
                    else if (what == EV_JS_ALERT || what == EV_JS_CONFIRM
                             || what == EV_JS_PROMPT)
                        nativeJsDialogDone(i, what, false, null);
                    return;
                }
                try {
                    v.macapkDispatchEvent(what, a, b, c, i);
                } catch (Throwable t) {
                    // An app callback threw. Log, and never leave a pending
                    // native decision unanswered (that is the "loads forever"
                    // failure this bridge exists to remove).
                    com.macapk.loader.NativeLog.w("WebView",
                            "client callback threw for event " + what + ": " + t);
                    if (what == EV_SHOULD_OVERRIDE) nativeNavDecision(i, true);
                    else if (what == EV_JS_ALERT || what == EV_JS_CONFIRM
                             || what == EV_JS_PROMPT)
                        nativeJsDialogDone(i, what, false, null);
                }
            }
        });
    }

    /** Called by native (AppKit main thread) when the user clicks or types into
     *  a WKWebView (MACAPK_WEBVIEW_REAL_INPUT). Those events never pass through
     *  TouchDispatcher, so this is what lets a page's own "open in browser" (a
     *  Cordova InAppBrowser _system / BrowserTab login, a link with
     *  target=_blank) pass the host-launch recency check, as the same tap does
     *  on a device. Only records a timestamp. */
    static void onRealInput() {
        com.macapk.loader.TouchDispatcher.noteRealInputFromHostView();
    }

    /** Synchronous @JavascriptInterface invocation; called by native on the
     *  JS-bridge queue while the page's JS is blocked inside prompt().
     *  Returns the JSON result document ({"v":...}) or "" for void/error --
     *  the injected stub maps "" to undefined. */
    static String onJsBridgeCall(int viewId, String payload) {
        final WebView wv = viewFor(viewId);
        if (wv == null) return "";
        try {
            return JAVA_BRIDGE.submit(() -> wv.macapkInvokeJsInterface(payload)).get();
        } catch (Throwable t) {
            Throwable c = (t instanceof java.util.concurrent.ExecutionException
                    && t.getCause() != null) ? t.getCause() : t;
            com.macapk.loader.NativeLog.w("WebView", "js bridge invoke threw: " + c);
            return "";
        }
    }

    // ---- WebViewClient.shouldInterceptRequest (called by native) -----------
    //
    // On a device every request is first offered to shouldInterceptRequest on a
    // background thread; Cordova and Capacitor serve their app that way
    // (WebViewAssetLoader, https://localhost/...). An engine that can hold a
    // request open (Windows: WebView2's WebResourceRequested deferral) asks
    // macapkWantsIntercept first, so views whose client does not override the
    // method never pay for the round trip, then macapkInterceptRequest on a
    // worker thread. Engines that cannot (the Mac's) never call either.

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> sInterceptors =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Does this class override either shouldInterceptRequest below WebViewClient? */
    static boolean overridesIntercept(Class<?> k) {
        Boolean known = sInterceptors.get(k);
        if (known != null) return known;
        boolean found = false;
        for (Class<?> c = k; c != null && c != WebViewClient.class && !found; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("shouldInterceptRequest")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == WebView.class
                        && (p[1] == WebResourceRequest.class || p[1] == String.class)) {
                    found = true;
                    break;
                }
            }
        }
        sInterceptors.put(k, found);
        return found;
    }

    /** Called by native for every request of the view: should it be offered? */
    static boolean macapkWantsIntercept(int viewId) {
        WebView wv = viewFor(viewId);
        if (wv == null) return false;
        WebViewClient c = wv.getWebViewClient();
        return c != null && overridesIntercept(c.getClass());
    }

    /** Called by native on a "WebViewIntercept" thread. `headers` is
     *  "Name: value\n" lines. Returns null to let the request go to the network,
     *  else {meta, body}: meta is "status\nreason\n" then the response headers
     *  as "Name: value\r\n" lines (Content-Type first), body the whole data. */
    static Object[] macapkInterceptRequest(int viewId, String url, String method, String headers,
                                           boolean mainFrame) {
        WebView wv = viewFor(viewId);
        if (wv == null) return null;
        WebResourceResponse r;
        try {
            r = wv.macapkShouldInterceptRequest(url, method, parseHeaders(headers), mainFrame);
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.w("WebView", "shouldInterceptRequest threw: " + t);
            return null;
        }
        if (r == null) return null;
        byte[] body = new byte[0];
        java.io.InputStream in = r.getData();
        if (in != null) {
            try (java.io.InputStream data = in) {
                body = data.readAllBytes();
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("WebView", "intercepted response for "
                        + url + " could not be read: " + t);
                return new Object[] { "500\nInternal Server Error\n", new byte[0] };
            }
        }
        StringBuilder meta = new StringBuilder();
        int status = r.getStatusCode() > 0 ? r.getStatusCode() : 200;
        String reason = r.getReasonPhrase() != null && !r.getReasonPhrase().isEmpty()
                ? r.getReasonPhrase() : "OK";
        meta.append(status).append('\n').append(reason).append('\n');
        boolean typed = false;
        Map<String, String> h = r.getResponseHeaders();
        if (h != null) {
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getKey().equalsIgnoreCase("Content-Type")) typed = true;
                meta.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (!typed && r.getMimeType() != null && !r.getMimeType().isEmpty()) {
            String type = r.getMimeType();
            if (r.getEncoding() != null && !r.getEncoding().isEmpty()) type += "; charset=" + r.getEncoding();
            meta.append("Content-Type: ").append(type).append("\r\n");
        }
        return new Object[] { meta.toString(), body };
    }

    private static Map<String, String> parseHeaders(String headers) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (headers == null) return out;
        for (String line : headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return out;
    }

    /** The "JavaBridge" thread: where every @JavascriptInterface method runs,
     *  one Java thread for the process, as Chromium's WebView has. The native
     *  side calls in from a GCD worker on its serial JS-bridge queue, and a GCD
     *  worker has a 512 KiB stack, so the app's bridge code ran with a fraction
     *  of the stack every other Java thread gets (-Xss16m, jvm_loader.cpp).
     *
     *  WHY 256 MiB. App code written for Android leans on its regex engine
     *  being ICU, which backtracks on a heap stack; java.util.regex recurses
     *  once per repetition. Measured on SmartTube: its PoToken generator parses
     *  the BotGuard challenge with `'((?:[^'\\]|\\[\s\S])*)'` inside
     *  downloadAndRunBotguard(), which threw StackOverflowError here, so no
     *  token was made and no video could load. HotSpot, that pattern: 16 MiB
     *  overflows at 100k characters, 64 MiB at 1M, 256 MiB holds 1M. It is ONE
     *  thread and the reservation is virtual (pages commit only when touched). */
    private static final java.util.concurrent.ExecutorService JAVA_BRIDGE =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(null, r, "JavaBridge", 256L << 20);
            t.setDaemon(true);
            return t;
        });

    // ---- file:///android_asset/ + file:///android_res/ (called by native) ---
    //
    // The Java half of the macapk-asset scheme handler (webview_bridge.mm,
    // WvAssetSchemeEnabled). Mirrors Chromium's AndroidProtocolHandler
    // (chromium/main android_webview/java/src/org/chromium/android_webview/
    // AndroidProtocolHandler.java): verifyUrl -> openByScheme -> openAsset /
    // openResource, the .svgz gunzip in open(), and getMimeType's
    // name -> well-known -> stream-sniff order. Native calls both methods on its
    // asset queue, never the UI thread; AssetManager reads here are the same
    // in-place zip reads every other asset open uses.

    static final String ANDROID_ASSET_PATH = "/android_asset/";
    static final String ANDROID_RES_PATH = "/android_res/";

    // ---- file:///android_asset/ pages on a REAL file: origin (2026-10-16) ----
    //
    // (MACAPK_WEBVIEW_FILE_ORIGIN, default ON; =0 keeps every app asset on the
    // macapk-asset scheme, the pre-change route.)
    //
    // The macapk-asset scheme serves the bytes faithfully but the PAGE can see
    // it: location.href / origin / protocol of a file:///android_asset page read
    // macapk-asset:, where a device reports file: / "file://". Location members
    // are unforgeable, so no script can hide that, and hybrid apps branch on it.
    // Measured victim: DOFUS Touch 3.14 (Cordova). cordova-plugin-file's Android
    // Entry.toURL is
    //     window.location.origin.includes('file://') ? this.nativeURL : this.toInternalURL()
    // and its FileSystem.__format__ builds window.location.origin +
    // '/__cdvfile_<fs>__/...'; on our scheme every downloaded script came back
    // as macapk-asset:///__cdvfile_persistent__/..., the game's loader rejected it
    // ("File not local") and showed "Server cannot be reached".
    //
    // So a top-level loadUrl of an asset page is served from REAL files: the
    // page's own asset directory is copied once (per installed APK set) under the
    // android root, and native loads it with loadFileURL, granting read access to
    // the android root so the app's own files (cdvfile nativeURLs point into
    // getFilesDir()) load too, as setAllowFileAccess allows on a device. Every
    // URL leaving the bridge maps back to file:///android_asset/ (WvGuestUrl), so
    // the app's Java side sees exactly what it sees on a device; the page sees a
    // file: URL with a longer path. A directory larger than FILE_ORIGIN_MAX_BYTES
    // (the assets root of a game, say) stays on the scheme.
    static final boolean FILE_ORIGIN = !"0".equals(System.getenv("MACAPK_WEBVIEW_FILE_ORIGIN"));
    private static final long FILE_ORIGIN_MAX_BYTES = 48L << 20;

    /** Called by native (off the main thread) for a top-level loadUrl of a
     *  file:///android_asset/ page. Returns {page file path, read-access root,
     *  materialised android_asset root}, or null to keep the scheme route. */
    static String[] macapkMaterializeAssetPage(int viewId, String url) {
        if (!FILE_ORIGIN) return null;
        try {
            android.net.Uri uri = macapkVerifyUrl(url);
            if (uri == null || !"file".equals(uri.getScheme())) return null;
            String path = uri.getPath();
            if (path == null || !path.startsWith(ANDROID_ASSET_PATH)) return null;
            String rel = path.substring(ANDROID_ASSET_PATH.length());
            if (rel.isEmpty() || rel.contains("..")) return null;
            android.content.Context ctx = macapkAppContext(viewId);
            if (ctx == null) return null;
            java.io.File dataDir = ctx.getDataDir();
            java.io.File dd = dataDir != null ? dataDir.getParentFile() : null;   // <root>/data/data
            java.io.File d = dd != null ? dd.getParentFile() : null;                // <root>/data
            java.io.File root = d != null ? d.getParentFile() : null;               // <root>
            if (root == null || !"data".equals(dd.getName()) || !"data".equals(d.getName()))
                return null;
            java.io.File base = new java.io.File(root,
                    ".macapk/webassets/" + dataDir.getName() + "/android_asset");
            // Copy the page's TOP-LEVEL asset directory (www/ for www/pages/a.html), so
            // "../" references inside a web app still resolve; when that is over the
            // cap, the page's own directory (Candy Crush's ToU page sits deep inside
            // a 128 MB res_output/); when both are, the scheme route.
            int slash = rel.lastIndexOf('/');
            String pageDir = slash < 0 ? "" : rel.substring(0, slash);
            int first = rel.indexOf('/');
            String topDir = first < 0 ? "" : rel.substring(0, first);
            String dir = macapkAssetBytes(ctx, topDir) <= FILE_ORIGIN_MAX_BYTES ? topDir
                       : macapkAssetBytes(ctx, pageDir) <= FILE_ORIGIN_MAX_BYTES ? pageDir : null;
            if (dir == null || !macapkEnsureAssetDir(ctx, base, dir)) return null;
            java.io.File page = new java.io.File(base, rel);
            if (!page.isFile()) return null;
            return new String[] { page.getPath(), root.getPath(), base.getPath() };
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.w("WebView", "asset page copy failed, keeping the "
                    + "macapk-asset route: " + t);
            return null;
        }
    }

    /** Uncompressed bytes under assets/<dir>/ across the installed APK set, from
     *  the zip central directories (no entry is read). Long.MAX_VALUE when the
     *  set cannot be read, which keeps the scheme route. */
    private static long macapkAssetBytes(android.content.Context ctx, String dir) {
        android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
        if (ai == null || ai.sourceDir == null) return Long.MAX_VALUE;
        java.util.List<String> apks = new java.util.ArrayList<>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) apks.addAll(java.util.Arrays.asList(ai.splitSourceDirs));
        String prefix = dir.isEmpty() ? "assets/" : "assets/" + dir + "/";
        long total = 0;
        for (String a : apks) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(a)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (!e.isDirectory() && e.getName().startsWith(prefix)) {
                        long n = e.getSize();
                        if (n < 0) return Long.MAX_VALUE;
                        total += n;
                    }
                }
            } catch (java.io.IOException e) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /** Copy assets/<dir>/** under base once per installed APK set (a stamp of
     *  every apk's path, size and mtime). Staged in a sibling and renamed, so a
     *  reader never sees half a tree. False when the tree is over the cap. */
    private static synchronized boolean macapkEnsureAssetDir(android.content.Context ctx,
            java.io.File base, String dir) throws java.io.IOException {
        android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
        StringBuilder sb = new StringBuilder();
        java.util.List<String> apks = new java.util.ArrayList<>();
        if (ai != null && ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai != null && ai.splitSourceDirs != null) apks.addAll(java.util.Arrays.asList(ai.splitSourceDirs));
        for (String a : apks) {
            java.io.File f = new java.io.File(a);
            sb.append(a).append(' ').append(f.length()).append(' ').append(f.lastModified()).append('\n');
        }
        String stamp = sb.toString();
        java.io.File target = dir.isEmpty() ? base : new java.io.File(base, dir);
        java.io.File stampFile = new java.io.File(base.getParentFile(),
                "stamp-" + (dir.isEmpty() ? "_root" : dir.replace('/', '_')));
        // A tree once found over the cap is remembered for this APK set, so a
        // big directory (a game's asset root) is not re-copied on every load.
        final String tooBig = "TOO-BIG\n" + stamp;
        try {
            if (stampFile.isFile()) {
                String have = new String(java.nio.file.Files.readAllBytes(stampFile.toPath()),
                                         java.nio.charset.StandardCharsets.UTF_8);
                if (have.equals(tooBig)) return false;
                if (target.isDirectory() && have.equals(stamp)) return true;
            }
        } catch (java.io.IOException ignored) { }

        java.io.File stage = new java.io.File(base.getParentFile(),
                "stage-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        java.io.File stageDir = dir.isEmpty() ? stage : new java.io.File(stage, dir);
        long[] total = { 0 };
        android.content.res.AssetManager am = ctx.getAssets();
        boolean ok = macapkCopyAssetTree(am, dir, stageDir, total);
        if (!ok || total[0] == 0) {
            macapkDeleteTree(stage);
            if (!ok) {
                java.nio.file.Files.createDirectories(stampFile.getParentFile().toPath());
                java.nio.file.Files.write(stampFile.toPath(),
                        tooBig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                com.macapk.loader.NativeLog.i("WebView", "asset page dir "
                        + (dir.isEmpty() ? "(assets root)" : dir) + " is over "
                        + (FILE_ORIGIN_MAX_BYTES >> 20) + " MB; it stays on the macapk-asset route");
            }
            return false;
        }
        macapkDeleteTree(target);
        java.nio.file.Files.createDirectories(target.getParentFile().toPath());
        java.nio.file.Files.move(stageDir.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        macapkDeleteTree(stage);
        java.nio.file.Files.write(stampFile.toPath(), stamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.macapk.loader.NativeLog.i("WebView", "asset page dir copied for a file: origin: "
                + (dir.isEmpty() ? "(assets root)" : dir) + " (" + total[0] + " bytes)");
        return true;
    }

    private static boolean macapkCopyAssetTree(android.content.res.AssetManager am, String dir,
            java.io.File out, long[] total) throws java.io.IOException {
        String[] names = am.list(dir);
        if (names == null) return true;
        java.nio.file.Files.createDirectories(out.toPath());
        for (String n : names) {
            if (n.isEmpty() || n.contains("/") || n.equals(".") || n.equals("..")) continue;
            String child = dir.isEmpty() ? n : dir + "/" + n;
            java.io.InputStream in = null;
            try {
                in = am.open(child, android.content.res.AssetManager.ACCESS_STREAMING);
            } catch (java.io.IOException notAFile) {
                in = null;
            }
            if (in == null) {
                if (!macapkCopyAssetTree(am, child, new java.io.File(out, n), total)) return false;
                continue;
            }
            try (java.io.InputStream src = in) {
                long len = java.nio.file.Files.copy(src, new java.io.File(out, n).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                total[0] += len;
                if (total[0] > FILE_ORIGIN_MAX_BYTES) return false;
            }
        }
        return true;
    }

    private static void macapkDeleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())
                ? f.listFiles() : null;
        if (kids != null) for (java.io.File k : kids) macapkDeleteTree(k);
        f.delete();
    }

    /** Bytes of the asset/resource `url` names (file:///android_asset/... or
     *  file:///android_res/..., still percent-encoded), or null when it does
     *  not exist -- the handler then fails the load as net::ERR_FILE_NOT_FOUND,
     *  which is what a device reports. */
    static byte[] macapkReadAsset(int viewId, String url) {
        android.content.Context ctx = macapkAppContext(viewId);
        return ctx != null ? macapkReadAsset(ctx, url) : null;
    }

    /** AndroidProtocolHandler.open against an explicit Context (the offline
     *  gate, debug/wvtest/asset_gate.sh, drives this with a synthetic APK). */
    static byte[] macapkReadAsset(android.content.Context ctx, String url) {
        android.net.Uri uri = macapkVerifyUrl(url);
        if (uri == null) return null;
        java.io.InputStream in = null;
        try {
            in = macapkOpenByScheme(ctx, uri);
            if (in == null) return null;
            String last = uri.getLastPathSegment();
            if (last != null && last.endsWith(".svgz")) {
                in = new java.util.zip.GZIPInputStream(in);
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (java.io.IOException ignored) { }
            }
        }
    }

    /** AndroidProtocolHandler.getMimeType. Never null: where Chromium hands an
     *  empty type to its network-layer sniffer (net::SniffMimeType, whose last
     *  resort is text/plain vs application/octet-stream), we finish that step
     *  here, because WebKit given NO type for a main-frame document refuses to
     *  show it (measured: WebKitErrorDomain 102, "Frame load interrupted"). */
    static String macapkAssetMime(String url, byte[] data) {
        android.net.Uri uri = macapkVerifyUrl(url);
        String mime = null;
        if (uri != null && "file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null && path.startsWith(ANDROID_ASSET_PATH)) {
                mime = macapkGuessContentTypeFromName(path);
                if (mime == null) mime = macapkWellKnownMimeType(path);
            }
        }
        if (mime != null && !mime.isEmpty()) return mime;
        if (data != null) {
            try {
                mime = java.net.URLConnection.guessContentTypeFromStream(
                        new java.io.ByteArrayInputStream(data));
            } catch (java.io.IOException ignored) {
                mime = null;
            }
            if (mime != null) return mime;
            return macapkLooksBinary(data) ? "application/octet-stream" : "text/plain";
        }
        return "application/octet-stream";
    }

    private static android.content.Context macapkAppContext(int viewId) {
        // Chromium: ContextUtils.getApplicationContext().
        try {
            android.app.Application app = android.app.ActivityThread.currentApplication();
            if (app != null) return app;
        } catch (Throwable ignored) {
        }
        WebView wv = viewFor(viewId);
        if (wv == null) return null;
        android.content.Context c = wv.getContext();
        if (c == null) return null;
        android.content.Context a = c.getApplicationContext();
        return a != null ? a : c;
    }

    /** AndroidProtocolHandler.verifyUrl: a parsed Uri with a real path. */
    private static android.net.Uri macapkVerifyUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        android.net.Uri uri = android.net.Uri.parse(url);
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) return null;
        return uri;
    }

    private static java.io.InputStream macapkOpenByScheme(android.content.Context ctx,
                                                          android.net.Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path.startsWith(ANDROID_ASSET_PATH)) {
                    // openAsset: path.replaceFirst(assetPath, "") then
                    // getAssets().open(path, ACCESS_STREAMING). The path is
                    // Uri.getPath(), i.e. already percent-decoded.
                    return ctx.getAssets().open(path.substring(ANDROID_ASSET_PATH.length()),
                            android.content.res.AssetManager.ACCESS_STREAMING);
                } else if (path.startsWith(ANDROID_RES_PATH)) {
                    return macapkOpenResource(ctx, uri);
                }
            }
        } catch (Exception ex) {
            // Chromium logs "Error opening inputstream" and answers null; the
            // native side prints the [W] line with the url.
        }
        return null;
    }

    /** openResource: "/android_res/<type>/<name>.<ext>", exactly three path
     *  segments, resolved by name (getIdentifier, then the R$<type> reflection
     *  fallback with application-id suffix stripping, crbug.com/923956), and
     *  only a STRING-typed value (a file-backed resource) is opened. */
    private static java.io.InputStream macapkOpenResource(android.content.Context ctx,
                                                          android.net.Uri uri) throws Exception {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 3) return null;
        if (!("/" + seg.get(0) + "/").equals(ANDROID_RES_PATH)) return null;
        String type = seg.get(1);
        String name = seg.get(2).split("\\.")[0];
        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        int id = res.getIdentifier(name, type, pkg);
        if (id == 0) {
            Class<?> clazz = null;
            String p = pkg;
            while (clazz == null && p != null) {
                try {
                    clazz = ctx.getClassLoader().loadClass(p + ".R$" + type);
                } catch (ClassNotFoundException e) {
                    int dot = p.lastIndexOf('.');
                    p = dot < 0 ? null : p.substring(0, dot);
                }
            }
            if (clazz == null) return null;
            id = clazz.getField(name).getInt(null);
        }
        android.util.TypedValue value = new android.util.TypedValue();
        res.getValue(id, value, true);
        if (value.type != android.util.TypedValue.TYPE_STRING) return null;
        return res.openRawResource(id);
    }

    /** Android's URLConnection.guessContentTypeFromName, i.e. libcore's
     *  DefaultFileNameMap.getContentTypeFor (API 34): drop a fragment, a
     *  trailing '/' is text/html, then try the extension after each '.' of the
     *  last path segment, longest first ("tar.gz" before "gz"), against the
     *  platform MimeMap -- which in this tree is MimeTypeMap. */
    static String macapkGuessContentTypeFromName(String filename) {
        int fragmentIndex = filename.indexOf('#');
        if (fragmentIndex >= 0) filename = filename.substring(0, fragmentIndex);
        if (filename.endsWith("/")) return "text/html";
        int slashIndex = filename.lastIndexOf('/');
        if (slashIndex >= 0) filename = filename.substring(slashIndex);
        MimeTypeMap map = MimeTypeMap.getSingleton();
        int dotIndex = -1;
        do {
            String ext = filename.substring(dotIndex + 1);
            // An extension containing '/' is the whole segment, never a type.
            String result = ext.indexOf('/') >= 0 ? null : map.getMimeTypeFromExtension(ext);
            if (result != null && (slashIndex < 0 || dotIndex >= 0)) return result;
            dotIndex = filename.indexOf('.', dotIndex + 1);
        } while (dotIndex >= 0);
        return null;
    }

    /** Chromium's AndroidProtocolHandler.getWellKnownMimeType ->
     *  net::GetWellKnownMimeTypeFromFile: kPrimaryMappings then
     *  kSecondaryMappings (net/base/mime_util.cc, chromium/main), first match
     *  wins, extension compared case-insensitively. */
    private static final String[][] WELL_KNOWN_MIME = {
        // kPrimaryMappings
        {"video/webm", "webm"}, {"audio/mpeg", "mp3"}, {"application/wasm", "wasm"},
        {"application/x-chrome-extension", "crx"}, {"application/xhtml+xml", "xhtml,xht,xhtm"},
        {"audio/flac", "flac"}, {"audio/matroska", "mka"}, {"audio/mp3", "mp3"},
        {"audio/ogg", "ogg,oga,opus"}, {"audio/wav", "wav"}, {"audio/webm", "webm"},
        {"audio/x-m4a", "m4a"}, {"image/avif", "avif"}, {"image/gif", "gif"},
        {"image/jpeg", "jpeg,jpg,jpe"}, {"image/jxl", "jxl"}, {"image/png", "png"},
        {"image/apng", "png,apng"}, {"image/svg+xml", "svg,svgz"}, {"image/webp", "webp"},
        {"multipart/related", "mht,mhtml"}, {"text/css", "css"},
        {"text/html", "html,htm,shtml,shtm"}, {"text/javascript", "js,mjs"},
        {"text/xml", "xml"}, {"video/matroska", "mkv"}, {"video/mp4", "mp4,m4v"},
        {"video/ogg", "ogv,ogm"}, {"text/csv", "csv"},
        // kSecondaryMappings
        {"image/x-icon", "ico"}, {"application/epub+zip", "epub"},
        {"application/font-woff", "woff"}, {"application/gzip", "gz,tgz"},
        {"application/javascript", "js"}, {"application/json", "json"},
        {"application/msword", "doc,dot"}, {"application/octet-stream", "bin,exe,com"},
        {"application/pdf", "pdf"}, {"application/pkcs7-mime", "p7m,p7c,p7z"},
        {"application/pkcs7-signature", "p7s"}, {"application/postscript", "ps,eps,ai"},
        {"application/rdf+xml", "rdf"}, {"application/rss+xml", "rss"},
        {"application/rtf", "rtf"}, {"application/vnd.android.package-archive", "apk"},
        {"application/vnd.mozilla.xul+xml", "xul"}, {"application/vnd.ms-excel", "xls"},
        {"application/vnd.ms-powerpoint", "ppt"},
        {"application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"},
        {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"},
        {"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"},
        {"application/x-gzip", "gz,tgz"}, {"application/x-mpegurl", "m3u8"},
        {"application/x-shockwave-flash", "swf,swl"}, {"application/x-tar", "tar"},
        {"application/x-x509-ca-cert", "cer,crt"}, {"application/zip", "zip"},
        {"audio/webm", "weba"}, {"image/bmp", "bmp"}, {"image/jpeg", "jfif,pjpeg,pjp"},
        {"image/tiff", "tiff,tif"}, {"image/vnd.microsoft.icon", "ico"},
        {"image/x-png", "png"}, {"image/x-xbitmap", "xbm"}, {"message/rfc822", "eml"},
        {"text/calendar", "ics"}, {"text/html", "ehtml"}, {"text/markdown", "md"},
        {"text/plain", "txt,text"}, {"text/vtt", "vtt"}, {"text/x-sh", "sh"},
        {"text/xml", "xsl,xbl,xslt"}, {"video/mpeg", "mpeg,mpg,mpe"},
    };

    static String macapkWellKnownMimeType(String path) {
        // base::FilePath::Extension() is the text after the last '.' of the
        // last component, except that it keeps a few double extensions whole
        // (".tar.gz", ".user.js"). Those never reach this table: the name guess
        // above already answers them, since it tries every dotted suffix.
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1);
        for (String[] m : WELL_KNOWN_MIME) {
            for (String e : m[1].split(",")) {
                if (e.equalsIgnoreCase(ext)) return m[0];
            }
        }
        return null;
    }

    /** net::SniffBinary (net/base/mime_sniffer.cc), the last step of
     *  SniffMimeType: over the first net::kMaxBytesToSniff = 1024 bytes, a byte
     *  order mark means text; otherwise any byte LooksLikeBinary counts (every
     *  byte below 0x20 except \t \n \f \r and ESC, per the WHATWG
     *  binary-data-byte set) means application/octet-stream. */
    private static boolean macapkLooksBinary(byte[] data) {
        if (data.length >= 2 && (((data[0] & 0xff) == 0xfe && (data[1] & 0xff) == 0xff)
                || ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xfe))) return false;
        if (data.length >= 3 && (data[0] & 0xff) == 0xef && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf) return false;
        int n = Math.min(data.length, 1024);
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xff;
            if (b <= 0x08 || b == 0x0b || (b >= 0x0e && b <= 0x1a) || (b >= 0x1c && b <= 0x1f)) {
                return true;
            }
        }
        return false;
    }
}
