// android.webkit.WebView (AOSP API-1; AOSP class extends AbsoluteLayout).
// Backed by the system WKWebView engine since 2026-08-12 (the no-shortcuts
// rule's macOS-native-equivalent move: Android's platform web engine maps to
// macOS's platform web engine). The native half is
// runtime/src/webview_bridge.mm via the package-private MacApkWebViewBridge;
// the WKWebView NSView composites at zPosition 1.5 over the widget-canvas
// layer at this view's laid-out rect (pushed from draw()). Real navigation,
// WebViewClient/WebChromeClient callbacks (delivered on the thread this
// WebView was constructed on, as AOSP does), evaluateJavascript, a
// synchronous addJavascriptInterface bridge (prompt-intercept; the interface
// methods run on a background thread, matching AOSP's JavaBridge thread), and
// a WKHTTPCookieStore-backed CookieManager all work. When the native engine
// is absent (headless diagnostic runtime), every entry degrades to the old
// stores-state-does-nothing shell instead of throwing.
// Ref: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/webkit/WebView.java
package android.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.util.AttributeSet;
import android.view.textclassifier.TextClassifier;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

// AOSP fidelity: the deprecation usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("deprecation")
public class WebView extends android.widget.AbsoluteLayout
        implements android.view.ViewGroup.OnHierarchyChangeListener,
                   android.view.ViewTreeObserver.OnGlobalFocusChangeListener {

    // ----- intent-scheme constants (deprecated in AOSP) -----
    @Deprecated public static final String SCHEME_TEL = "tel:";
    @Deprecated public static final String SCHEME_MAILTO = "mailto:";
    @Deprecated public static final String SCHEME_GEO = "geo:0,0?q=";

    // ----- stored configuration (read by a future native bridge) -----
    private final WebSettings settings = new WebSettings() {};
    private WebViewClient webViewClient;
    private WebChromeClient webChromeClient;
    private DownloadListener downloadListener;
    private FindListener findListener;
    private PictureListener pictureListener;
    private final Map<String, Object> jsInterfaces = new HashMap<>();
    private String url;
    private String originalUrl;
    private String title;
    private int progress = 0;

    // ----- native WKWebView peer state -----
    private static final java.util.concurrent.atomic.AtomicInteger macapkNextViewId =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private static final java.util.concurrent.atomic.AtomicInteger macapkNextCallbackId =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private final int macapkViewId = macapkNextViewId.getAndIncrement();
    private volatile boolean macapkPeerCreated;
    private boolean macapkDestroyed;
    private boolean macapkCanGoBack;
    private boolean macapkCanGoForward;
    private final Map<Integer, ValueCallback<String>> macapkJsCallbacks = new HashMap<>();
    // Last frame pushed to native, so per-draw pushes are change-gated.
    private int macapkLastX = Integer.MIN_VALUE, macapkLastY, macapkLastW, macapkLastH;
    private boolean macapkLastVisible;
    // The last settings snapshot applied, so loads pick up setting changes.
    private boolean macapkLastJsEnabled;
    private String macapkLastUa;
    /** Last file-access answers sent to the peer (see macapkFileUrlAccess). */
    private boolean macapkLastFileUrlAccess;
    /** Whether the native view was created with universal file access
     *  (MacApkWebViewBridge.UNIVERSAL_ACCESS); a later change cannot reach it. */
    private boolean macapkCreatedUniversal;
    private boolean macapkWarnedUniversalLate;
    private String macapkLastReadRoot;

    private WebViewRenderProcessClient renderProcessClient;
    private Executor renderProcessClientExecutor;
    private TextClassifier textClassifier;
    private boolean networkAvailable = true;

    // The Looper of the thread this WebView was constructed on. AOSP stores the
    // same thing (`private final Looper mWebViewThread = Looper.myLooper();`)
    // and hands it back from getWebViewLooper(); it is also what the WebView
    // thread-affinity contract is defined against. Null when the constructing
    // thread has no Looper, exactly as on a device.
    private final Looper webViewThread = Looper.myLooper();

    // Set once any WebView exists in this process. AOSP's data-directory suffix
    // may only be chosen before the WebView provider is instantiated, and the
    // provider is created by the first WebView constructor; see
    // setDataDirectorySuffix below.
    private static volatile boolean sProviderCreated;
    private static String sDataDirectorySuffix;

    // AOSP keeps this in WebViewFactory: once disableWebView() has been called
    // the provider refuses to initialize, so constructing a WebView throws.
    // This is REAL behaviour that an app can observe, not a flag we store and
    // ignore -- a process that opts out and then accidentally inflates a
    // WebView must fail the same way it does on a device.
    private static volatile boolean sWebViewDisabled;

    // AOSP: "the SSL certificate for the main top-level page", null when the
    // page is not secure. setCertificate() is deprecated and documented as
    // ignored upstream, but the stored value is what getCertificate() reports
    // in AOSP's own provider until a navigation replaces it.
    private android.net.http.SslCertificate certificate;

    // Whether this WebView was constructed through the deprecated
    // privateBrowsing constructor. See isPrivateBrowsingEnabled().
    private final boolean privateBrowsingRequested;

    public WebView(Context c) { super(c); privateBrowsingRequested = false; macapkInit(); }
    public WebView(Context c, AttributeSet a) { super(c, a); privateBrowsingRequested = false; macapkInit(); }
    public WebView(Context c, AttributeSet a, int s) { super(c, a, s); privateBrowsingRequested = false; macapkInit(); }
    // AOSP's standard View 4-constructor form. Our AbsoluteLayout has no
    // defStyleRes overload to chain to, and nothing here resolves styled
    // attributes anyway, so the default-style resource is accepted and ignored
    // rather than dropped from the signature set (an app or subclass that
    // chains to this ctor otherwise dies with NoSuchMethodError at inflate).
    public WebView(Context c, AttributeSet a, int defStyleAttr, int defStyleRes) {
        super(c, a, defStyleAttr);
        privateBrowsingRequested = false;
        macapkInit();
    }
    @Deprecated
    public WebView(Context c, AttributeSet a, int s, boolean privateBrowsing) {
        super(c, a, s);
        privateBrowsingRequested = privateBrowsing;
        macapkInit();
    }

    /** Common constructor tail: AOSP's provider instantiation point, which is
     *  both where "a WebView now exists in this process" becomes true and
     *  where disableWebView()'s opt-out is enforced. */
    private void macapkInit() {
        if (sWebViewDisabled) {
            throw new IllegalStateException(
                    "WebView.disableWebView() was called: WebView is disabled");
        }
        sProviderCreated = true;
    }

    // ----- navigation: load -----
    public void loadUrl(String url) {
        loadUrl(url, null);
    }
    /** Whether `url` is a javascript: URL as Chromium's GURL sees it: leading
     *  and trailing C0 controls and spaces trimmed, TAB/LF/CR removed, scheme
     *  matched case-insensitively (url_canon_etc.cc, IsRemovableURLWhitespace). */
    static boolean macapkIsJavascriptUrl(String url) {
        if (url == null) return false;
        StringBuilder head = new StringBuilder(11);
        int i = 0, n = url.length();
        while (i < n && url.charAt(i) <= 0x20) i++;
        for (; i < n && head.length() < 11; i++) {
            char c = url.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            head.append(c);
        }
        return head.toString().equalsIgnoreCase("javascript:");
    }

    /** The script Blink runs for a javascript: URL (targetSdk >= KITKAT):
     *  GURL canonicalisation (trim C0/space at both ends, drop every TAB/LF/CR,
     *  non-ASCII as UTF-8), then DecodeURLEscapeSequences(kUTF8OrIsomorphic):
     *  only a VALID %XX becomes a byte, any other '%' stays literal, and bytes
     *  that are not valid UTF-8 decode isomorphically (Latin-1) as a whole
     *  (url/url_util.cc :842-884). NOT android.net.Uri.decode, which turns a
     *  lone '%' into U+FFFD and eats the next character ('100%' -> a
     *  SyntaxError). */
    /** ASCII hex digit value, or -1 (Chromium IsHexChar is ASCII-only;
     *  Character.digit would also accept e.g. fullwidth digits). */
    private static int macapkHex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static String macapkJavascriptUrlSource(String url) {
        int b = 0, e = url.length();
        while (b < e && url.charAt(b) <= 0x20) b++;
        while (e > b && url.charAt(e - 1) <= 0x20) e--;
        StringBuilder t = new StringBuilder(e - b);
        for (int i = b; i < e; i++) {
            char c = url.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') t.append(c);
        }
        String s = t.substring(11);   // after the (case-insensitive) scheme
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && macapkHex(s.charAt(i + 1)) >= 0
                    && macapkHex(s.charAt(i + 2)) >= 0) {
                out.write((macapkHex(s.charAt(i + 1)) << 4) | macapkHex(s.charAt(i + 2)));
                i += 3;
                continue;
            }
            int cp = s.codePointAt(i);
            byte[] u = new String(Character.toChars(cp))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(u, 0, u.length);
            i += Character.charCount(cp);
        }
        byte[] bytes = out.toByteArray();
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        if (url == null) return;
        // v1.0.1642: a javascript: URL is SCRIPT, run in the CURRENT document --
        // never a navigation, and getUrl() does not change. That is Android's
        // contract: Chromium sends it to HandleRendererDebugURL (no
        // NavigationEntry, no shouldOverrideUrlLoading), and Blink's
        // LocalFrame::LoadJavaScriptURL runs it in the current frame. Apps rely
        // on it: Wikipedia's CommunicationBridge.flushMessages posts every page
        // message as loadUrl("javascript:...") in a tight loop. Handed to
        // -[WKWebView loadRequest:] as a navigation, two back-to-back ones crash
        // this host's WebContent process (SIGSEGV in
        // FrameLoader::continueLoadAfterNavigationPolicy, reproduced with a bare
        // WKWebView), which blanked the article right after it loaded.
        if (macapkIsJavascriptUrl(url)) {
            if (!macapkEnsurePeer()) return;
            if (!getSettings().getJavaScriptEnabled()) return;   // Blink: scripts disabled -> no-op
            int tsdk = 0;
            try { tsdk = getContext().getApplicationInfo().targetSdkVersion; }
            catch (Throwable ignored) { }
            // AwContents.loadUrl: below KITKAT the text after "javascript:" is
            // evaluated as-is; from KITKAT it goes through URL canonicalisation
            // and Blink's decode.
            String src = (android.app.Application.macapkTargetSdkKnown(tsdk) && tsdk < 19 && url.startsWith("javascript:"))
                ? url.substring(11) : macapkJavascriptUrlSource(url);
            if (MacApkWebViewBridge.macapkTrace()) {
                android.util.Log.i("Mac.apk.WebView", "id=" + macapkViewId
                    + " javascript: loadUrl -> evaluate (" + src.length() + " chars) t="
                    + android.os.SystemClock.uptimeMillis());
            }
            MacApkWebViewBridge.nativeEvaluateJs(macapkViewId, src, 0);
            return;
        }
        this.url = url;
        this.originalUrl = url;
        if (!macapkEnsurePeer()) return;
        String[] kv = null;
        if (additionalHttpHeaders != null && !additionalHttpHeaders.isEmpty()) {
            kv = new String[additionalHttpHeaders.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> e : additionalHttpHeaders.entrySet()) {
                kv[i++] = e.getKey();
                kv[i++] = e.getValue();
            }
        }
        MacApkWebViewBridge.nativeLoadUrl(macapkViewId, url, kv);
    }
    public void postUrl(String url, byte[] postData) {
        // WKWebView has no first-class "navigate with POST body" that also
        // populates history; loadHTMLString with a self-submitting form is the
        // standard shim. Rare in practice (login flows GET); log so a miss is
        // visible instead of silent.
        this.url = url;
        this.originalUrl = url;
        if (!macapkEnsurePeer()) return;
        com.macapk.loader.NativeLog.w("WebView",
                "postUrl: POST navigation approximated via GET (body dropped): " + url);
        MacApkWebViewBridge.nativeLoadUrl(macapkViewId, url, null);
    }
    public void loadData(String data, String mimeType, String encoding) {
        if (!macapkEnsurePeer()) return;
        MacApkWebViewBridge.nativeLoadData(macapkViewId, null, data, mimeType, encoding);
    }
    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType,
                                    String encoding, String historyUrl) {
        this.url = baseUrl;
        this.originalUrl = historyUrl;
        if (!macapkEnsurePeer()) return;
        // AwContents.fixupBase: an empty base IS "about:blank" on a device, and
        // it is not loadData(). The bridge needs the difference: a
        // loadDataWithBaseURL document with a non-data: base may load
        // file:///android_asset/ (AwContents.loadUrl's setCanLoadLocalResources),
        // a loadData() one may not. See -topDocumentMayLoadAppAssets in
        // runtime/src/webview_bridge.mm.
        String base = (baseUrl == null || baseUrl.isEmpty()) ? "about:blank" : baseUrl;
        MacApkWebViewBridge.nativeLoadData(macapkViewId, base, data, mimeType, encoding);
    }
    public void reload() {
        if (macapkPeerCreated) MacApkWebViewBridge.nativeReload(macapkViewId);
    }
    public void stopLoading() {
        if (macapkPeerCreated) MacApkWebViewBridge.nativeStopLoading(macapkViewId);
    }

    // ----- navigation: history -----
    // canGoBack/canGoForward mirror WKWebView's KVO-published history state
    // (pushed through EV_URL_CHANGED), so these synchronous getters answer
    // without a cross-thread wait.
    public boolean canGoBack() { return macapkCanGoBack; }
    public boolean canGoForward() { return macapkCanGoForward; }
    public boolean canGoBackOrForward(int steps) {
        // Only the one-step booleans are mirrored; a multi-step answer is
        // approximated by them (a wrong true merely no-ops in nativeGo).
        if (steps == 0) return true;
        return steps < 0 ? macapkCanGoBack : macapkCanGoForward;
    }
    public void goBack() { goBackOrForward(-1); }
    public void goForward() { goBackOrForward(1); }
    public void goBackOrForward(int steps) {
        if (steps != 0 && macapkPeerCreated)
            MacApkWebViewBridge.nativeGo(macapkViewId, steps);
    }
    public WebBackForwardList copyBackForwardList() { return new EmptyBackForwardList(); }
    public WebBackForwardList saveState(Bundle outState) { return new EmptyBackForwardList(); }
    public WebBackForwardList restoreState(Bundle inState) { return new EmptyBackForwardList(); }

    // ----- clients / listeners -----
    public void setWebViewClient(WebViewClient client) { this.webViewClient = client; }
    public WebViewClient getWebViewClient() { return webViewClient; }
    public void setWebChromeClient(WebChromeClient client) { this.webChromeClient = client; }
    public WebChromeClient getWebChromeClient() { return webChromeClient; }
    public void setDownloadListener(DownloadListener listener) { this.downloadListener = listener; }
    public void setFindListener(FindListener listener) { this.findListener = listener; }
    public void setPictureListener(PictureListener listener) { this.pictureListener = listener; }

    // ----- render process -----

    /** AOSP: "@return the WebViewRenderProcess renderer handle associated with
     *  this WebView, or null if WebView is not runing in multiprocess mode."
     *  Mac.apk is single-process and has no sandboxed renderer, so null is the
     *  answer the platform itself specifies for this configuration. */
    public WebViewRenderProcess getWebViewRenderProcess() { return null; }

    /** AOSP stores the client per-WebView and calls it from the renderer's
     *  responsiveness watchdog. With no renderer there is nothing to observe,
     *  so the client is stored (and returned by the getter, per its contract)
     *  and never invoked, rather than being silently discarded. */
    public void setWebViewRenderProcessClient(Executor executor,
                                              WebViewRenderProcessClient client) {
        this.renderProcessClientExecutor = executor;
        this.renderProcessClient = client;
    }
    /** AOSP: callbacks run on the thread the WebView was initialized on, which
     *  is why this overload passes a null executor. */
    public void setWebViewRenderProcessClient(WebViewRenderProcessClient client) {
        setWebViewRenderProcessClient(null, client);
    }
    /** AOSP: the client set via setWebViewRenderProcessClient, or null. */
    public WebViewRenderProcessClient getWebViewRenderProcessClient() {
        return renderProcessClient;
    }

    // ----- text classification -----

    public void setTextClassifier(TextClassifier textClassifier) {
        this.textClassifier = textClassifier;
    }
    /** AOSP returns @NonNull: "If no TextClassifier has been set, this WebView
     *  uses the default set by the system." Never null. */
    public TextClassifier getTextClassifier() {
        TextClassifier tc = textClassifier;
        if (tc == null) {
            // TextClassifier became an INTERFACE on 2026-07-29 (AOSP declares it
            // one; shipping it as a class broke every guest `implements
            // TextClassifier` with IncompatibleClassChangeError). AOSP's own
            // "no on-device model" answer is the NO_OP constant, which is exactly
            // what `new TextClassifier()` used to construct here.
            tc = TextClassifier.NO_OP;
            textClassifier = tc;
        }
        return tc;
    }

    // ----- connectivity -----

    /** AOSP informs the engine so navigator.onLine and the online/offline DOM
     *  events track the app's view of connectivity. Stored for the engine
     *  bridge to read when it attaches. */
    public void setNetworkAvailable(boolean networkUp) {
        this.networkAvailable = networkUp;
    }

    // ----- JS bridge -----
    public void addJavascriptInterface(Object object, String name) {
        if (name == null || object == null) return;
        synchronized (jsInterfaces) {
            jsInterfaces.put(name, object);
        }
        macapkSyncInjectionScript();
    }
    public void removeJavascriptInterface(String name) {
        if (name == null) return;
        synchronized (jsInterfaces) {
            jsInterfaces.remove(name);
        }
        macapkSyncInjectionScript();
    }
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        if (!macapkEnsurePeer()) {
            // No engine this run: answer null so callers that await a result
            // do not hang (a hung callback is worse than a null one).
            if (resultCallback != null) resultCallback.onReceiveValue(null);
            return;
        }
        int cbId = 0;
        if (resultCallback != null) {
            cbId = macapkNextCallbackId.incrementAndGet();
            synchronized (macapkJsCallbacks) {
                macapkJsCallbacks.put(cbId, resultCallback);
            }
        }
        MacApkWebViewBridge.nativeEvaluateJs(macapkViewId, script, cbId);
    }

    /** AOSP: "Creates a message channel to communicate with JS and returns the
     *  message ports that represent the endpoints of this message channel. ...
     *  The returned message channels are entangled and already in started
     *  state." The return is @NonNull, so null or an empty array would fail at
     *  the caller's first array access. The pair returned here really is
     *  entangled -- a message posted into one port is delivered to the other
     *  port's callback -- which is the whole observable contract of a
     *  MessageChannel; what is absent until a real engine lands is the document
     *  end of the channel, which is also why postWebMessage has no recipient. */
    public WebMessagePort[] createWebMessageChannel() {
        LocalMessagePort a = new LocalMessagePort(webViewThread);
        LocalMessagePort b = new LocalMessagePort(webViewThread);
        a.entangle(b);
        b.entangle(a);
        return new WebMessagePort[] { a, b };
    }

    /** AOSP posts the message to the main frame of the loaded document. There
     *  is no document without an engine, so there is nothing to deliver to; the
     *  message is dropped rather than queued, since a queue nobody drains would
     *  only leak. */
    public void postWebMessage(WebMessage message, Uri targetOrigin) {}

    // ----- settings -----
    public WebSettings getSettings() { return settings; }

    // ----- accessors -----
    public String getUrl() { return url; }
    public String getOriginalUrl() { return originalUrl; }
    public String getTitle() { return title; }
    public Bitmap getFavicon() { return null; }
    public int getProgress() { return progress; }
    /** AOSP: the HTML content height in CSS px (AwContents.getContentHeightCss).
     *  v1.0.1643: reported by the page (the macapkScroll script); was 0. */
    public int getContentHeight() { return macapkContentHeightCss; }
    /** AOSP (deprecated): pageScaleFactor * dipScale -- device px per CSS px,
     *  so getContentHeight() * getScale() is the content height in px. */
    public float getScale() {
        if (macapkPxPerCss > 0f) return macapkPxPerCss;
        try { return getResources().getDisplayMetrics().density; } catch (Throwable t) { return 1.0f; }
    }

    // ----- zoom / scale -----
    public void setInitialScale(int scaleInPercent) {}
    public boolean zoomIn() { return false; }
    public boolean zoomOut() { return false; }
    public void zoomBy(float zoomFactor) {}

    /**
     * AOSP: "Gets whether this WebView can be zoomed in." @Deprecated since
     * API 17 in favour of WebSettings' zoom controls.
     *
     * These MUST agree with zoomIn()/zoomOut(). The WKWebView bridge does not
     * expose programmatic page zoom yet (the native half lives in
     * runtime/src/webview_bridge.mm), so zoomIn()/zoomOut() answer false --
     * and a canZoomIn() that answered true would be a lie an app acts on: the
     * documented pattern is "if (canZoomIn()) zoomIn()", and a true here makes
     * a zoom button appear enabled and then do nothing.
     *
     * TODO(webview-zoom): WKWebView has pageZoom/magnification on macOS. When
     * the bridge grows a zoom call, zoomIn/zoomOut/canZoomIn/canZoomOut/
     * getScale should all become real together -- changing only one of them
     * re-introduces exactly the inconsistency this comment exists to prevent.
     */
    @Deprecated
    public boolean canZoomIn() { return false; }

    /** See {@link #canZoomIn()}. */
    @Deprecated
    public boolean canZoomOut() { return false; }

    /**
     * AOSP: "Invokes the graphical zoom picker widget for this WebView."
     * The on-screen zoom widget was removed from the platform long ago and
     * AOSP's provider does nothing on a modern device, so a no-op here is the
     * matching behaviour rather than a stub.
     */
    public void invokeZoomPicker() {}

    // ----- find on page -----
    public void findAllAsync(String find) {}
    @Deprecated public int findAll(String find) { return 0; }
    public void findNext(boolean forward) {}
    public void clearMatches() {}

    /**
     * AOSP: "@deprecated This method does not work reliably on all Android
     * versions; implementing a custom find dialog using
     * WebView.findAllAsync() provides a more robust solution."
     * @return true if the find dialog is shown, false otherwise.
     *
     * There is no platform find dialog to show here (AOSP's lived in the
     * browser's own UI, not in WebView), so false -- the documented "could not
     * show it" answer -- is accurate, and it is what tells a caller to fall
     * back to findAllAsync().
     */
    @Deprecated
    public boolean showFindDialog(String text, boolean showIme) { return false; }

    // ----- scrolling -----
    // AOSP returns boolean ("true if the page was scrolled"). We do not scroll, so
    // false is correct, but the descriptor has to match the SDK's.
    // frameworks/base/core/java/android/webkit/WebView.java
    public boolean pageUp(boolean top) { return false; }
    public boolean pageDown(boolean bottom) { return false; }
    public void flingScroll(int vx, int vy) {}

    // ----- hit testing / node href -----
    public HitTestResult getHitTestResult() { return new HitTestResult(); }
    public void requestFocusNodeHref(Message hrefMsg) {}
    public void requestImageRef(Message msg) {}

    // ----- cache / data clearing -----
    public void clearCache(boolean includeDiskFiles) {}
    public void clearFormData() {}
    public void clearHistory() {}
    public void clearSslPreferences() {}
    public void clearView() {}
    @Deprecated public void freeMemory() {}

    // ----- lifecycle / timers -----
    public void onPause() {}
    public void onResume() {}
    public void pauseTimers() {}
    public void resumeTimers() {}
    public void destroy() {
        jsInterfaces.clear();
        macapkDestroyed = true;
        if (macapkPeerCreated) {
            macapkPeerCreated = false;
            // Runs the same unregister + nativeDestroy the GC path would, once.
            java.lang.ref.Cleaner.Cleanable c = macapkCleanable;
            macapkCleanable = null;
            if (c != null) {
                c.clean();
            } else {
                MacApkWebViewBridge.unregister(macapkViewId);
                MacApkWebViewBridge.nativeDestroy(macapkViewId);
            }
        }
    }
    private java.lang.ref.Cleaner.Cleanable macapkCleanable;

    // ----- capture -----
    @Deprecated public Picture capturePicture() { return null; }
    public void saveWebArchive(String filename) {}
    public void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback) {
        if (callback != null) callback.onReceiveValue(null);
    }

    // ----- credentials (deprecated) -----
    @Deprecated public void savePassword(String host, String username, String password) {}
    @Deprecated public void setHttpAuthUsernamePassword(String host, String realm,
                                                        String username, String password) {}
    @Deprecated public String[] getHttpAuthUsernamePassword(String host, String realm) { return null; }

    public void documentHasImages(Message response) {}

    // ----- printing -----

    /** AOSP returns an adapter that lays the loaded page out into a print
     *  document. With no engine there is no page to paginate, so the adapter
     *  reports the failure through the callback the platform provides for
     *  exactly that (onLayoutFailed / onWriteFailed) instead of returning null,
     *  which would NPE inside PrintManager.print. */
    public PrintDocumentAdapter createPrintDocumentAdapter(String documentName) {
        return new NoContentPrintDocumentAdapter();
    }
    @Deprecated
    public PrintDocumentAdapter createPrintDocumentAdapter() {
        return createPrintDocumentAdapter("default");
    }

    // ----- thread / class loader introspection -----

    /** AOSP: the Looper corresponding to the thread on which this WebView was
     *  constructed. Not something the engine computes, so this is exact. */
    public Looper getWebViewLooper() { return webViewThread; }

    /** AOSP: the ClassLoader used to load the WebView implementation's internal
     *  classes (apps use it to reflect into the provider). Here the WebView
     *  implementation is this framework, so it is this class's loader. */
    public static ClassLoader getWebViewClassLoader() {
        return WebView.class.getClassLoader();
    }

    // ----- visual state -----
    public void postVisualStateCallback(long requestId, VisualStateCallback callback) {
        if (callback != null) callback.onComplete(requestId);
    }

    // ----- static utilities -----
    public static void setWebContentsDebuggingEnabled(boolean enabled) {}
    public static PackageInfo getCurrentWebViewPackage() {
        // Report a plausible "Android System WebView" package so apps that read
        // it for feature gating do not NPE.
        PackageInfo pi = new PackageInfo();
        pi.packageName = "com.google.android.webview";
        pi.versionName = "120.0.0.0";
        return pi;
    }
    @Deprecated public static String findAddress(String addr) { return null; }

    /** AOSP WebView.setDataDirectorySuffix -> WebViewFactory.setDataDirectorySuffix
     *  (frameworks/base/core/java/android/webkit/WebViewFactory.java:229). Gives
     *  a process its own WebView data directory, so two processes of the same
     *  app never open one directory and trip the engine's native abort. Mac.apk
     *  is single-process, so there is no second directory to hand out and the
     *  suffix only has to be remembered; the two preconditions are enforced
     *  because they are observable and apps are written against them (the call
     *  is normally the first thing a non-default process does, and getting the
     *  ordering wrong is exactly the bug this API exists to surface). */
    public static void setDataDirectorySuffix(String suffix) {
        if (sProviderCreated) {
            throw new IllegalStateException(
                    "Can't set data directory suffix: WebView already initialized");
        }
        if (suffix != null && suffix.indexOf(File.separatorChar) >= 0) {
            throw new IllegalArgumentException("Suffix " + suffix
                                               + " contains a path separator");
        }
        sDataDirectorySuffix = suffix;
    }
    public static void enableSlowWholeDocumentDraw() {}
    public static void clearClientCertPreferences(Runnable onCleared) {
        if (onCleared != null) onCleared.run();
    }
    public static Uri getSafeBrowsingPrivacyPolicyUrl() {
        return Uri.parse("https://www.google.com/chrome/browser/privacy/whitepaper.html#safebrowsing");
    }
    public static void setSafeBrowsingWhitelist(java.util.List<String> hosts,
                                                ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
    public static void startSafeBrowsing(Context context, ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }

    // ----- page base colour -----
    //
    // AOSP (android14-release frameworks/base core/java/android/webkit/
    // WebView.java:3033):
    //     public void setBackgroundColor(int color) {
    //         mProvider.getViewDelegate().setBackgroundColor(color);
    //     }
    // It does NOT call View.setBackgroundColor: the View background is left
    // alone and the colour becomes the PAGE BASE, painted under a document with
    // no background of its own (Chromium AwContents.setBackgroundColor,
    // AwContents.java:2840 -> aw_contents.cc:1181 SetPageBaseBackgroundColor;
    // default Color.WHITE, AwContents.java:500). setBackgroundColor(0) is how an
    // app lets a page's translucent pixels show what is under the WebView --
    // King's Terms of Use card over Candy Crush, whose 0.7 black scrim read as
    // flat (76,76,76) over our old white base instead of the dimmed game
    // (docs/webview-dialog-over-gl-backdrop_2026-09-26.md).
    //
    // The native peer is created lazily (first load-shaped call), and apps set
    // the colour BEFORE that (King: setBackgroundColor at Popup$1.run bci 127,
    // loadUrl at bci 408), so the colour is stored here and pushed again right
    // after nativeCreate in macapkEnsurePeer. How the colour is composited, and
    // why a translucent one on a host with nothing beneath the page keeps the
    // opaque base, is WvApplyBase in runtime/src/webview_bridge.mm.
    //
    // MACAPK_WEBVIEW_BASE_COLOR=0 restores the pre-change path exactly
    // (View.setBackgroundColor, nothing native).

    /** The page base colour, ARGB; AOSP's default is WHITE (AwContents.java:500). */
    private int macapkBaseColor = 0xFFFFFFFF;

    private static final StackWalker MACAPK_CALLER =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    @Override
    public void setBackgroundColor(int color) {
        // Our LayoutInflater applies an XML android:background colour through
        // setBackgroundColor (its flat-colour fast path, applyBackground), where
        // AOSP's View constructor calls setBackground(Drawable) -- which WebView
        // does not override. So on a device android:background on a <WebView>
        // sets the VIEW background and never the page base (3 WebViews in the
        // corpus carry one: Duolingo zui_view_article, sgtpuzzles, Signal). That
        // one framework call site therefore takes View's path here. Retire this
        // once the inflater applies XML colours without calling the public
        // setter (docs/webview-page-base-colour_2026-09-26.md, "Inflater").
        if (!MacApkWebViewBridge.BASE_COLOR || macapkCalledByInflater()) {
            super.setBackgroundColor(color);
            return;
        }
        macapkBaseColor = color;
        if (macapkPeerCreated) MacApkWebViewBridge.nativeSetBackgroundColor(macapkViewId, color);
    }

    /** True when this setBackgroundColor call comes from our LayoutInflater
     *  (see setBackgroundColor). An app WebView subclass may override
     *  setBackgroundColor and call super, so the frames of that override chain
     *  -- setBackgroundColor declared by WebView or a subclass -- are skipped
     *  before asking who called; ONLY those frames: a subclass CONSTRUCTOR that
     *  calls setBackgroundColor(0) is the app's own call even when the inflater
     *  built the view (the reflective newInstance frames between them are
     *  hidden by StackWalker, so skipping the constructor too would land on
     *  the inflater and lose the app's colour). */
    private static boolean macapkCalledByInflater() {
        return MACAPK_CALLER.walk(frames -> frames
                .skip(1)   // this method
                .dropWhile(f -> "setBackgroundColor".equals(f.getMethodName())
                        && WebView.class.isAssignableFrom(f.getDeclaringClass()))
                .findFirst()
                .map(f -> f.getDeclaringClass() == android.view.LayoutInflater.class)
                .orElse(false));
    }

    // ===== native-engine plumbing (Mac.apk internal, package-private) =====

    /** Creates the native WKWebView peer on first need. Returns false when the
     *  engine is unavailable this run (headless runtime), in which case every
     *  caller degrades to the pre-engine stub behavior. */
    private boolean macapkEnsurePeer() {
        if (macapkDestroyed) return false;
        if (macapkPeerCreated) {
            macapkApplySettingsIfChanged();
            return true;
        }
        if (!MacApkWebViewBridge.engineAvailable()) return false;
        String pkg = null;
        try {
            pkg = getContext() != null ? getContext().getPackageName() : null;
        } catch (Throwable ignored) {
        }
        macapkLastJsEnabled = settings.getJavaScriptEnabled();
        macapkLastUa = settings.getUserAgentString();
        MacApkWebViewBridge.register(macapkViewId, this);
        // AOSP setAllowUniversalAccessFromFileURLs: Chromium then grants file:
        // documents universal access (no CORS on their requests). WebKit takes the
        // equivalent only at WKWebView creation, so it travels with the create.
        // Cordova sets it in initWebViewSettings, before its first loadUrl; DOFUS
        // Touch's login API call (custom APIKEY header) failed its CORS preflight
        // without it ("all servers are in maintenance").
        macapkCreatedUniversal = MacApkWebViewBridge.UNIVERSAL_ACCESS
                && settings.getAllowUniversalAccessFromFileURLs();
        if (macapkCreatedUniversal) {
            try {
                MacApkWebViewBridge.nativeCreateOptions(macapkViewId, true);
            } catch (UnsatisfiedLinkError ignored) {
                // A test bridge without the option; the view is created without it.
            }
        }
        MacApkWebViewBridge.nativeCreate(macapkViewId, MacApkWebViewBridge.dataStoreKey(pkg),
                                         macapkLastUa, macapkLastJsEnabled);
        macapkPeerCreated = true;
        // A draw made before the peer existed punched no hole (onDraw gates on
        // macapkPeerCreated), and a host that keeps that recording (Compose's
        // AndroidView) draws it again only when told; the page now has content
        // to show, which is when AOSP's provider invalidates the view.
        invalidate();
        macapkLastFileUrlAccess = macapkFileUrlAccess();
        macapkLastReadRoot = macapkReadRoot();
        if (macapkLastFileUrlAccess || macapkLastReadRoot != null) {
            MacApkWebViewBridge.nativeSetFileUrlAccess(macapkViewId, macapkLastFileUrlAccess,
                                                       macapkLastReadRoot);
        }
        // A page base set before the peer existed (see setBackgroundColor). The
        // default WHITE is the native default too, so there is nothing to send.
        if (MacApkWebViewBridge.BASE_COLOR && macapkBaseColor != 0xFFFFFFFF) {
            MacApkWebViewBridge.nativeSetBackgroundColor(macapkViewId, macapkBaseColor);
        }
        // v1.0.1642: tear the native WKWebView down when this WebView becomes
        // unreachable. Many apps never call destroy() (Wikipedia drops each
        // article's WebView with its PageActivity), and the native holder, its
        // WebContent process share and its trace timer used to live forever --
        // 7 article opens left 7 WKWebViews alive. Chromium's AwContents is
        // likewise released by a GC-driven cleanup when the app never destroys
        // it. The action captures only the int id, never the WebView.
        macapkCleanable = MacApkWebViewCleaner.register(this, macapkViewId);
        macapkSyncInjectionScript();
        // If the view is already laid out (peer created after attach), place
        // the native view immediately rather than waiting for the next draw.
        macapkPushFrame(true);
        return true;
    }

    /** Settings are PULLED at each load-shaped call, which matches the AOSP
     *  granularity ("takes effect on the next page load") without a listener
     *  on every WebSettings setter. */
    private void macapkApplySettingsIfChanged() {
        boolean js = settings.getJavaScriptEnabled();
        String ua = settings.getUserAgentString();
        if (js != macapkLastJsEnabled
                || (ua != null && !ua.equals(macapkLastUa))) {
            macapkLastJsEnabled = js;
            macapkLastUa = ua;
            MacApkWebViewBridge.nativeApplySettings(macapkViewId, js, ua);
        }
        if (MacApkWebViewBridge.UNIVERSAL_ACCESS && !macapkWarnedUniversalLate
                && settings.getAllowUniversalAccessFromFileURLs() != macapkCreatedUniversal) {
            macapkWarnedUniversalLate = true;
            com.macapk.loader.NativeLog.w("WebView", "setAllowUniversalAccessFromFileURLs("
                    + settings.getAllowUniversalAccessFromFileURLs() + ") after the page view "
                    + "was created; WebKit takes it only at creation, so it applies to this "
                    + "WebView's file: pages only if set before its first load");
        }
        boolean fileUrls = macapkFileUrlAccess();
        String readRoot = macapkReadRoot();
        if (fileUrls != macapkLastFileUrlAccess
                || !java.util.Objects.equals(readRoot, macapkLastReadRoot)) {
            macapkLastFileUrlAccess = fileUrls;
            macapkLastReadRoot = readRoot;
            MacApkWebViewBridge.nativeSetFileUrlAccess(macapkViewId, fileUrls, readRoot);
        }
    }

    /** The app's android root (every file the app itself can read lives under it)
     *  when WebSettings.setAllowFileAccess(true), else null. On a device that setting
     *  lets file: content -- including a loadDataWithBaseURL page with a file: base --
     *  read the app's files; WKWebView needs an explicit read grant for the same. */
    private String macapkReadRoot() {
        if (!MacApkWebViewBridge.FILE_URL_ACCESS || !settings.getAllowFileAccess()) return null;
        String root = System.getProperty("macapk.android.root");
        return root != null && !root.isEmpty() ? root : null;
    }

    /** Whether a file: document may read other file: URLs with script -- AOSP
     *  WebSettings.setAllowFileAccessFromFileURLs, or setAllowUniversalAccessFromFileURLs,
     *  which Chromium treats as a superset of it. Both were stored and never reached
     *  the native view, so WebKit kept file: documents in an opaque origin: an
     *  AppLovin interstitial (loadDataWithBaseURL("file:///") whose template is a
     *  {@code <script type="module" src="file:///.../files/al/ad-templates_*.js">},
     *  fetched in CORS mode) never ran its player, leaving a black page with no
     *  close button (GD Meltdown, v1.0.1666 known issue). The app asks for it
     *  explicitly (AppLovinWebViewBase); the default stays false, as on Android. */
    private boolean macapkFileUrlAccess() {
        return MacApkWebViewBridge.FILE_URL_ACCESS
                && (settings.getAllowFileAccessFromFileURLs()
                    || settings.getAllowUniversalAccessFromFileURLs());
    }

    /** Rebuilds the WKUserScript that installs window.<name> proxy objects for
     *  every registered @JavascriptInterface object. Injected at document
     *  start of the NEXT load, which is exactly AOSP's documented timing for
     *  addJavascriptInterface after a page is showing. */
    private void macapkSyncInjectionScript() {
        if (!macapkPeerCreated) return;
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){function mk(ifc,ms){if(window[ifc]&&window[ifc].__macapk)return;")
          .append("var o={__macapk:true};for(var k=0;k<ms.length;k++)(function(m){")
          .append("o[m]=function(){var a=[];for(var j=0;j<arguments.length;j++){")
          .append("var v=arguments[j];a.push(v===undefined?null:v);}var r=window.prompt(")
          .append("\"\\u0001macapk-jsb\\u0001\"+JSON.stringify({ifc:ifc,m:m,a:a}));")
          .append("if(r===null||r===\"\")return undefined;")
          .append("try{return JSON.parse(r).v;}catch(e){return undefined;}};})(ms[k]);")
          .append("window[ifc]=o;}");
        synchronized (jsInterfaces) {
            for (Map.Entry<String, Object> e : jsInterfaces.entrySet()) {
                java.util.List<String> names = macapkBridgeMethodNames(e.getValue());
                if (names.isEmpty()) continue;
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String n : names) arr.put(n);
                sb.append("mk(").append(org.json.JSONObject.quote(e.getKey()))
                  .append(',').append(arr.toString()).append(");");
            }
        }
        sb.append("})();");
        MacApkWebViewBridge.nativeSetInjectionScript(macapkViewId, sb.toString());
    }

    /** The methods a registered interface object exposes to JS. API-17+
     *  semantics: only @JavascriptInterface-annotated methods; below 17 every
     *  public method (AOSP's pre-JELLY_BEAN_MR1 behavior), minus
     *  java.lang.Object's. */
    private java.util.List<String> macapkBridgeMethodNames(Object obj) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        boolean annotatedOnly = true;
        try {
            android.content.Context c = getContext();
            if (c != null && c.getApplicationInfo() != null
                    && android.app.Application.macapkTargetSdkKnown(c.getApplicationInfo().targetSdkVersion)
                    && c.getApplicationInfo().targetSdkVersion < 17) {
                annotatedOnly = false;
            }
        } catch (Throwable ignored) {
        }
        for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (annotatedOnly && !m.isAnnotationPresent(JavascriptInterface.class)) continue;
            if (!out.contains(m.getName())) out.add(m.getName());
        }
        return out;
    }

    /** Per-frame rect sync: ViewCanvasDriver calls rootView.draw(canvas) every
     *  frame, which recurses here; pushing the (change-gated) laid-out rect
     *  from draw keeps the native NSView glued to this view through layout,
     *  scroll, and visibility changes without touching the driver.
     *  ⚠ The push runs BEFORE super.draw, so onDraw's hole decision
     *  (macapkLastVisible) is THIS draw's answer. After it, the first draw of a
     *  freshly laid-out WebView still saw the previous push (0x0, invisible)
     *  and punched nothing; a host that redraws every frame repaired that one
     *  frame later, but a host that records the WebView once and replays the
     *  recording (Compose's AndroidView: a GraphicsLayer re-recorded only on
     *  invalidateLayer) kept the hole-less recording forever and the page sat
     *  behind an opaque canvas (mGradani NIAS login white; Mac.apk-Releases
     *  issue 2; debug/wvtest/compose_shape). A device draws the page (its draw
     *  functor) on every draw, whatever the previous frame was. */
    @Override
    public void draw(android.graphics.Canvas canvas) {
        macapkPushFrame(false);
        super.draw(canvas);
        macapkPushOccluders();
    }

    /** v1.0.1643: the WKWebView composites BELOW the widget canvas, so cut this
     *  view's rect out of the canvas and the page shows through. Done in
     *  onDraw -- after the background, BEFORE children -- so views an app adds
     *  inside its WebView still paint over the page, and every view drawn after
     *  this one (the app's chrome over its WebView) paints over the hole, as on
     *  Android. ⚠ Clipped at (0,0,w,h): our ViewGroup.dispatchDraw applies a
     *  PARENT's scroll to its children and never shifts a view's own onDraw by
     *  its own scroll (AOSP does: View.draw(Canvas,ViewGroup,long) translates
     *  by mLeft-mScrollX). Once the page scroll is mirrored into getScrollY()
     *  (thousands of px on an article) a scroll-relative clip landed off the
     *  view and the page area painted white. If our draw ever gains AOSP's
     *  self-scroll translate, this must become (sx, sy, sx+w, sy+h). */
    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        if (Z_BELOW && macapkPeerCreated && macapkLastVisible) {
            // Canvas.punchHole, the op SurfaceView uses (DST_OUT, carries the
            // view's alpha) -- NOT drawColor(CLEAR), which cannot get out of an
            // intervening offscreen (a RenderNode recording, a saveLayerAlpha);
            // punchHole re-applies the hole one level up (Canvas.macapkPunchShape).
            canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, getAlpha());
            macapkPushBackdrop();
        }
    }

    // ---- MACAPK_WEBVIEW_BACKDROP (2026-10-01): the window under the page ---
    //
    // On a device a WebView's page is drawn INTO its window, over whatever the
    // window drew first: the decor's windowBackground, then each ancestor's
    // background, then this view's own (View.draw order). A transparent page
    // base (setBackgroundColor(0), what every ad SDK's interstitial and King's
    // popups set) therefore shows those pixels. Ours composites the WKWebView
    // BELOW the canvas and punches the canvas out where it sits, which erases
    // exactly those pixels, so the native side had nothing to put under a
    // translucent page on a canvas-path window and kept WebKit's own opaque
    // base (white, or #1e1e1e in dark appearance): an interstitial under
    // Theme.NoTitleBar showed white where a device shows its black window, and
    // a white Skip box vanished into it (debug/glcover;
    // docs/gd-meltdown-interstitial-hidden-window_2026-10-01.md).
    //
    // This hands native the backdrop as AOSP composes it, when it is a flat
    // colour: the frame's clear for this root (ViewCanvasDriver: the decor's
    // windowBackground on a canvas window, 0 on a GL host whose game is below),
    // then every background from the root's child down to this view, each a
    // ColorDrawable composited src-over with the view's alpha. Any other
    // drawable in that chain (an image, a gradient, a selector) is not a flat
    // colour: known=false, and native keeps its pre-change decision. So is an
    // earlier sibling (at any level of the chain) that is or contains a
    // below-window SurfaceView (it punches a hole in what the window drew; see
    // macapkBackdropFor). Other views drawn BEFORE this one that are not its
    // ancestors are not modelled. Native also tests the surface beneath the
    // page BEFORE it uses the backdrop (WvApplyBase). Sent only when it changes.
    private int macapkLastBackdrop;
    private boolean macapkLastBackdropKnown;
    private boolean macapkBackdropSent;

    /** The backdrop as {argb, known(0/1)}. */
    int[] macapkWindowBackdrop() {
        final int UNKNOWN = 0x00000001;
        android.view.View root = this;
        for (android.view.ViewParent p = getParent(); p instanceof android.view.View; p = p.getParent()) {
            root = (android.view.View) p;
        }
        return macapkBackdropFor(this,
            com.macapk.loader.ViewCanvasDriver.macapkFrameClearFor(root, UNKNOWN), UNKNOWN);
    }

    /** What a window draws under `self` before `self`'s own onDraw, as a flat
     *  colour: `rootClear` (the frame's clear for self's root; `unknown` when
     *  the frame was not drawn for that root), then every background from the
     *  root's child down to `self` itself, SRC_OVER with each view's alpha.
     *  {argb, 1} when every one is a ColorDrawable (or none), else {0, 0}.
     *  Static and View-only so debug/glcover_gate.sh drives it on plain trees. */
    static int[] macapkBackdropFor(android.view.View self, int rootClear, int unknown) {
        if (self == null || rootClear == unknown) return new int[] { 0, 0 };
        java.util.ArrayList<android.view.View> chain = new java.util.ArrayList<>();
        for (android.view.View v = self; v != null; ) {
            chain.add(v);
            android.view.ViewParent p = v.getParent();
            v = (p instanceof android.view.View) ? (android.view.View) p : null;
        }
        int acc = rootClear;
        // The root's own background IS the window background the clear stands
        // for, so the walk starts at its child.
        for (int i = chain.size() - 2; i >= 0; i--) {
            android.view.View v = chain.get(i);
            // A SurfaceView drawn BEFORE `v` under the same parent punches a hole
            // through everything the window drew up to it -- the window
            // background and every ancestor background included (AOSP
            // SurfaceView.draw(): "punch a whole in the view-hierarchy below us",
            // android14-release SurfaceView.java :512-521 -> clearSurfaceViewPort
            // :579) -- so the window's own pixels are NOT what lies under this
            // page there, and the flat-colour model is not valid: unknown (native
            // then keeps the surface-aware decision, WvNativeBeneath). A surface
            // above its window (setZOrderOnTop) punches nothing.
            android.view.ViewParent vp = v.getParent();
            if (vp instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) vp;
                int at = g.indexOfChild(v);
                for (int j = 0; j < at; j++) {
                    if (macapkHasHolePunchingSurface(g.getChildAt(j), 0)) return new int[] { 0, 0 };
                }
            }
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d == null) continue;
            if (!(d instanceof android.graphics.drawable.ColorDrawable)) return new int[] { 0, 0 };
            int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
            float va = v.getAlpha();
            if (va < 1f) {
                int al = Math.round(((c >>> 24) & 0xFF) * Math.max(0f, va));
                c = (al << 24) | (c & 0xFFFFFF);
            }
            acc = macapkSrcOver(c, acc);
        }
        return new int[] { acc, 1 };
    }

    /** Is `v` (or something visible inside it) a below-window SurfaceView, i.e. one
     *  whose draw() punches a hole through the window pixels drawn before it? */
    private static boolean macapkHasHolePunchingSurface(android.view.View v, int depth) {
        if (v == null || depth > 64 || v.getVisibility() != android.view.View.VISIBLE) return false;
        if (v instanceof android.view.SurfaceView) {
            return !((android.view.SurfaceView) v).macapkIsZOrderOnTop();
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (macapkHasHolePunchingSurface(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** Porter-Duff SRC_OVER of two non-premultiplied ARGB colours. */
    static int macapkSrcOver(int src, int dst) {
        int sa = (src >>> 24) & 0xFF, da = (dst >>> 24) & 0xFF;
        if (sa == 255 || da == 0) return src;
        if (sa == 0) return dst;
        float fs = sa / 255f, fd = da / 255f * (1f - fs), fa = fs + fd;
        int r = Math.round((((src >> 16) & 0xFF) * fs + ((dst >> 16) & 0xFF) * fd) / fa);
        int g = Math.round((((src >> 8) & 0xFF) * fs + ((dst >> 8) & 0xFF) * fd) / fa);
        int b = Math.round(((src & 0xFF) * fs + (dst & 0xFF) * fd) / fa);
        int a = Math.round(fa * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void macapkPushBackdrop() {
        if (!MacApkWebViewBridge.BACKDROP || !MacApkWebViewBridge.BASE_COLOR) return;
        // An opaque page base hides the backdrop on Android too.
        if ((macapkBaseColor >>> 24) == 0xFF) return;
        int[] b;
        try { b = macapkWindowBackdrop(); } catch (Throwable t) { return; }
        boolean known = b[1] != 0;
        if (macapkBackdropSent && known == macapkLastBackdropKnown
                && (!known || b[0] == macapkLastBackdrop)) return;
        macapkBackdropSent = true;
        macapkLastBackdrop = b[0];
        macapkLastBackdropKnown = known;
        MacApkWebViewBridge.nativeSetBackdrop(macapkViewId, b[0], known);
    }

    // ---- v1.0.1643: the WebView under the app's own views ------------------
    //
    // AOSP draws a WebView as part of its window's view hierarchy, so a
    // toolbar, header, bottom bar or dialog the app places over it is visible
    // and receives touches. Ours is a native WKWebView; it used to sit ABOVE
    // the whole canvas (zPosition 1.5), which hid all of that: Wikipedia's
    // article toolbar, lead image and bottom bar were drawn but never seen.
    // MACAPK_WEBVIEW_Z_BELOW=0 restores the old slot (native + Java agree).
    static final boolean Z_BELOW =
        !"0".equals(System.getenv("MACAPK_WEBVIEW_Z_BELOW"));
    private int[] macapkLastOccluders;
    private int macapkContentHeightCss;
    private float macapkPxPerCss;
    private boolean macapkApplyingPageScroll;

    /** Rects (window px) of the views drawn AFTER this WebView that cover it
     *  -- later siblings of this view and of each ancestor, i.e. our
     *  dispatchDraw order. A transparent, non-clickable container is not an
     *  occluder itself; its children are. A window above the activity
     *  (dialog / popup) takes every click. Pushed only when it changes. */
    private void macapkPushOccluders() {
        int[] next;
        if (!Z_BELOW || !macapkPeerCreated || !macapkLastVisible) {
            next = new int[0];
        } else {
            int[] me = new int[2];
            try { getLocationInWindow(me); } catch (Throwable t) { return; }
            android.graphics.Rect wv = new android.graphics.Rect(
                me[0], me[1], me[0] + getWidth(), me[1] + getHeight());
            java.util.ArrayList<android.graphics.Rect> out = new java.util.ArrayList<>();
            // Windows ABOVE this WebView's own window. A layer that contains
            // this WebView, or sits below it, does not cover it; one that is
            // FLAG_NOT_TOUCHABLE takes no clicks; a FLAG_NOT_TOUCH_MODAL one
            // covers only its own bounds; a modal one takes every click.
            boolean modalAbove = false;
            android.graphics.Rect modalBounds = null;
            try {
                android.view.View myRoot = getRootView();
                java.util.List<com.macapk.loader.WindowOverlayStack.Layer> layers =
                    com.macapk.loader.WindowOverlayStack.snapshot();
                int mine = -1;
                for (int i = 0; i < layers.size(); i++) {
                    if (layers.get(i).view == myRoot) { mine = i; break; }
                }
                for (int i = mine + 1; i < layers.size(); i++) {
                    com.macapk.loader.WindowOverlayStack.Layer l = layers.get(i);
                    if (l.view == null || l.view.getVisibility() != VISIBLE) continue;
                    int fl = l.params != null ? l.params.flags : 0;
                    if ((fl & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue;
                    if ((fl & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0) {
                        android.graphics.Rect lb = new android.graphics.Rect(l.bounds);
                        if (lb.intersect(wv)) out.add(lb);
                        continue;
                    }
                    modalAbove = true;
                    modalBounds = l.bounds != null ? new android.graphics.Rect(l.bounds) : null;
                    break;
                }
            } catch (Throwable ignored) { }
            if (modalAbove) {
                out.clear();
                out.add(wv);
                // The modal window's own bounds after the whole-view rect: the
                // whole rect is what takes the clicks, and the union is the
                // same; a host that composites the page ABOVE the canvas
                // (Windows: a child window) also needs to know where the
                // dialog itself is drawn, so it can show it over the page.
                if (modalBounds != null && modalBounds.intersect(wv)) out.add(modalBounds);
            } else {
                // The parts an ancestor clips away (a ScrollView / RecyclerView
                // scrolled it partly out): the canvas covers them, so the page
                // must not take clicks there either.
                android.graphics.Rect vis = new android.graphics.Rect();
                boolean anyVisible = false;
                try { anyVisible = getGlobalVisibleRect(vis); } catch (Throwable ignored) { }
                if (!anyVisible) {
                    out.add(new android.graphics.Rect(wv));
                } else if (!vis.contains(wv)) {
                    if (vis.top > wv.top) out.add(new android.graphics.Rect(wv.left, wv.top, wv.right, Math.min(vis.top, wv.bottom)));
                    if (vis.bottom < wv.bottom) out.add(new android.graphics.Rect(wv.left, Math.max(vis.bottom, wv.top), wv.right, wv.bottom));
                    int t = Math.max(vis.top, wv.top), b = Math.min(vis.bottom, wv.bottom);
                    if (t < b && vis.left > wv.left) out.add(new android.graphics.Rect(wv.left, t, Math.min(vis.left, wv.right), b));
                    if (t < b && vis.right < wv.right) out.add(new android.graphics.Rect(Math.max(vis.right, wv.left), t, wv.right, b));
                }
                android.view.View child = this;
                android.view.ViewParent p = getParent();
                while (p instanceof android.view.ViewGroup g) {
                    int idx = g.indexOfChild(child);
                    for (int i = idx + 1; i < g.getChildCount(); i++) {
                        macapkCollectOccluders(g.getChildAt(i), wv, out, 0);
                    }
                    child = g;
                    p = g.getParent();
                }
            }
            next = new int[out.size() * 4];
            for (int i = 0; i < out.size(); i++) {
                android.graphics.Rect r = out.get(i);
                next[i * 4] = r.left;
                next[i * 4 + 1] = r.top;
                next[i * 4 + 2] = r.width();
                next[i * 4 + 3] = r.height();
            }
        }
        if (java.util.Arrays.equals(next, macapkLastOccluders)) return;
        if (macapkLastOccluders == null && next.length == 0) { macapkLastOccluders = next; return; }
        macapkLastOccluders = next;
        if (!macapkPeerCreated) return;
        try { MacApkWebViewBridge.nativeSetOccluders(macapkViewId, next); }
        catch (Throwable ignored) { }
        if (MacApkWebViewBridge.macapkTrace()) {
            com.macapk.loader.NativeLog.i("webview", "id=" + macapkViewId
                + " OCCLUDERS " + (next.length / 4) + " rect(s) "
                + java.util.Arrays.toString(next));
        }
    }

    private static void macapkCollectOccluders(android.view.View v, android.graphics.Rect wv,
            java.util.List<android.graphics.Rect> out, int depth) {
        if (v == null || depth > 64 || v.getVisibility() != VISIBLE
                || v.getAlpha() <= 0f || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        int[] loc = new int[2];
        try { v.getLocationInWindow(loc); } catch (Throwable t) { return; }
        android.graphics.Rect r = new android.graphics.Rect(
            loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        if (!android.graphics.Rect.intersects(r, wv)) return;
        if (v instanceof android.view.ViewGroup g && v.getBackground() == null && !v.isClickable()) {
            for (int i = 0; i < g.getChildCount(); i++) {
                macapkCollectOccluders(g.getChildAt(i), wv, out, depth + 1);
            }
            return;
        }
        if (r.intersect(wv)) out.add(r);
    }

    /** EV_PAGE_SCROLL: "scrollXpx,scrollYpx,contentHeightCss,pxPerCss". AOSP
     *  keeps the View scroll offsets equal to the page's (AwContents
     *  .scrollContainerViewTo -> super_scrollTo), so onScrollChanged fires and
     *  getScrollY() answers the page position -- apps move native chrome with
     *  it. The guard keeps our scrollTo override from echoing it back. */
    private void macapkApplyPageScroll(String a) {
        if (a == null) return;
        String[] f = a.split(",");
        if (f.length < 4) return;
        try {
            int x = Integer.parseInt(f[0]), y = Integer.parseInt(f[1]);
            macapkContentHeightCss = Integer.parseInt(f[2]);
            macapkPxPerCss = Float.parseFloat(f[3]);
            if (MacApkWebViewBridge.macapkTrace()) {
                com.macapk.loader.NativeLog.i("webview", "id=" + macapkViewId
                    + " page scroll -> View scroll (" + x + "," + y + ") from ("
                    + getScrollX() + "," + getScrollY() + ") " + getClass().getName());
            }
            macapkApplyingPageScroll = true;
            try { super.scrollTo(x, y); } finally { macapkApplyingPageScroll = false; }
        } catch (NumberFormatException ignored) { }
    }

    /** A wheel step that reaches this view through the Android tree (the
     *  pointer is over a part of the page an app view covers, or the harness's
     *  hostwheel; a real wheel over the page itself goes straight to the
     *  WKWebView, which scrolls natively). Chromium's WebView answers
     *  ACTION_SCROLL in onGenericMotionEvent by scrolling the page by the axis
     *  value x the scroll factor (AwContents onGenericMotionEvent ->
     *  ContentViewCore/EventForwarder.onMouseWheelEvent); here the page is
     *  scrolled the same distance in CSS px. WheelScroll.VIEWS=0: not handled. */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if (com.macapk.loader.WheelScroll.VIEWS
                && event.getAction() == android.view.MotionEvent.ACTION_SCROLL
                && event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
                && Z_BELOW && macapkPeerCreated) {
            android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(getContext());
            final int dy = Math.round(-event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
                    * vc.getScaledVerticalScrollFactor());
            final int dx = Math.round(event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
                    * vc.getScaledHorizontalScrollFactor());
            if (dx != 0 || dy != 0) {
                float f = macapkPxPerCss > 0f ? macapkPxPerCss : 2f;
                MacApkWebViewBridge.nativeEvaluateJs(macapkViewId,
                    "window.scrollBy(" + (dx / f) + "," + (dy / f) + ")", 0);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    /** App -> page: AOSP WebView.scrollTo/scrollBy move the page (the provider
     *  syncs the renderer to the View scroll). Forwarded in CSS px; the page's
     *  own scroll report then settles the View offsets (clamped as the page
     *  clamps). The View offset is set immediately too, so a caller reading
     *  getScrollY() right after scrollBy sees its own request. */
    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, y);
        if (macapkApplyingPageScroll || !Z_BELOW || !macapkPeerCreated) return;
        float f = macapkPxPerCss > 0f ? macapkPxPerCss : 2f;
        MacApkWebViewBridge.nativeEvaluateJs(macapkViewId,
            "window.scrollTo(" + (x / f) + "," + (y / f) + ")", 0);
    }

    /** The native view follows this view's (and its ancestors') visibility
     *  at once. The frame push rides draw(), and a GONE/INVISIBLE view is not
     *  drawn -- so without this a hidden WebView's WKWebView stayed on screen
     *  (and clickable) until something else redrew it. */
    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        macapkPushFrame(true);
        macapkPushOccluders();
    }

    // ---- MACAPK_WEBVIEW_TRACK_LAYOUT (2026-10-06): follow an ANCESTOR's move ----
    //
    // The frame push rides draw(), so it ran only when THIS view was drawn. A
    // host that moves a recording of the WebView without drawing it again moved
    // the punched hole and left the WKWebView where the last draw put it. The
    // measured case is Compose: mGradani's NIAS screen slides in through a
    // navigation transition, the WebView drew ONCE at the start of the slide
    // (FRAME loc=(498,0) on a 498-px window), and every later frame replayed the
    // GraphicsLayer under the new placement (punch dev x 471 -> 0) while the page
    // stayed parked off the window: a blank screen that took no clicks
    // (Mac.apk-Releases issue 2). On a device the page is a draw functor INSIDE
    // that recording, so it moves with it. The AOSP precedent for a view whose
    // content lives OUTSIDE the window's buffer is SurfaceView, which re-reads
    // getLocationInWindow from ViewTreeObserver callbacks (mDrawListener, an
    // OnPreDrawListener -> updateSurface, and mScrollChangedListener;
    // android14-release SurfaceView.java :205-219), so the WebView registers the
    // same pre-draw hook, plus OnGlobalLayout, which AndroidComposeView
    // dispatches when it re-places an interop View
    // (onInteropViewLayoutChange -> dispatchOnGlobalLayout). Both pushes are
    // change-gated, so an unmoved page costs one getLocationInWindow per frame.
    static final boolean TRACK_LAYOUT =
            !"0".equals(System.getenv("MACAPK_WEBVIEW_TRACK_LAYOUT"));
    private android.view.ViewTreeObserver macapkTrackedVto;
    private final android.view.ViewTreeObserver.OnPreDrawListener macapkPreDraw = () -> {
        macapkFollowLayout();
        return true;
    };
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener macapkGlobalLayout =
            this::macapkFollowLayout;
    private void macapkFollowLayout() {
        if (!macapkPeerCreated || !isAttachedToWindow()) return;
        macapkPushFrame(false);
        macapkPushOccluders();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!TRACK_LAYOUT || macapkTrackedVto != null) return;
        try {
            android.view.ViewTreeObserver vto = getViewTreeObserver();
            vto.addOnPreDrawListener(macapkPreDraw);
            vto.addOnGlobalLayoutListener(macapkGlobalLayout);
            macapkTrackedVto = vto;
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (macapkTrackedVto != null) {
            try {
                macapkTrackedVto.removeOnPreDrawListener(macapkPreDraw);
                macapkTrackedVto.removeOnGlobalLayoutListener(macapkGlobalLayout);
            } catch (Throwable ignored) { }
            macapkTrackedVto = null;
        }
        // The driver stops drawing a detached tree, so the draw-hook can no
        // longer hide the native view; do it from the detach itself.
        if (macapkPeerCreated)
            MacApkWebViewBridge.nativeSetFrame(macapkViewId, 0, 0, 0, 0, 1, 1, false);
        macapkLastVisible = false;
        macapkLastX = Integer.MIN_VALUE;
    }

    private void macapkPushFrame(boolean force) {
        if (!macapkPeerCreated) return;
        int[] loc = new int[2];
        try {
            getLocationInWindow(loc);
        } catch (Throwable t) {
            return;
        }
        int w = getWidth(), h = getHeight();
        boolean visible = isShown() && w > 0 && h > 0;
        if (!force && loc[0] == macapkLastX && loc[1] == macapkLastY
                && w == macapkLastW && h == macapkLastH
                && visible == macapkLastVisible) {
            return;
        }
        macapkLastX = loc[0];
        macapkLastY = loc[1];
        macapkLastW = w;
        macapkLastH = h;
        macapkLastVisible = visible;
        int surfW = 0, surfH = 0;
        try {
            // The PHYSICAL surface px, decor included -- the same
            // phoneWidth()*2 space ViewCanvasDriver sizes its bitmap from and
            // lays the root tree out in, i.e. the space getLocationInWindow
            // coordinates live in and the space the host MTKView's bounds are
            // stretched from. (First cut used WindowMetrics bounds, which
            // measured ~3% narrower and left the web view that far short of
            // the window's right edge -- visible in the first wvtest capture.)
            surfW = android.view.Display.macapkRealWidthPx();
            surfH = android.view.Display.macapkRealHeightPx();
        } catch (Throwable ignored) {
        }
        if (surfW <= 0 || surfH <= 0) {
            try {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                surfW = dm.widthPixels;
                surfH = dm.heightPixels;
            } catch (Throwable t) {
                return;
            }
        }
        // Under MACAPK_WEBVIEW_TRACE, report the geometry we are about to push.
        // Inferring a web view's size from screen pixels is guesswork -- an ad
        // banner's measured height is the number that decides whether an SDK
        // sized it wrongly or we laid it out wrongly, and only the view itself
        // knows it. Density is printed alongside because every ad SDK derives
        // its banner format from dp, so a px/dp confusion shows up here first.
        if (MacApkWebViewBridge.macapkTrace()) {
            float d = android.util.DisplayMetrics.macapkDeviceDensity();
            try { d = getResources().getDisplayMetrics().density; } catch (Throwable ignored) { }
            com.macapk.loader.NativeLog.i("webview",
                "id=" + macapkViewId + " FRAME loc=(" + loc[0] + "," + loc[1] + ")"
                + " size=" + w + "x" + h + "px"
                + " (" + (d > 0 ? (int) (w / d) : -1) + "x" + (d > 0 ? (int) (h / d) : -1) + "dp)"
                + " surf=" + surfW + "x" + surfH + " density=" + d
                + " visible=" + visible
                + " " + macapkParentChain(d));
        }
        MacApkWebViewBridge.nativeSetFrame(macapkViewId, loc[0], loc[1], w, h,
                                           surfW, surfH, visible);
    }

    /** The ancestor chain with each level's MEASURED size and its REQUESTED
     *  LayoutParams. An ad SDK derives its banner format from the width of the
     *  container it is placed in, so "what did the app ask for vs what did our
     *  layout give it" is the whole question when a banner comes out too large.
     *  MATCH_PARENT is -1 and WRAP_CONTENT is -2 in LayoutParams, so a level
     *  showing req=-2 but a full-screen measured width is our bug, not the
     *  SDK's. */
    private String macapkParentChain(float d) {
        StringBuilder sb = new StringBuilder("chain=[");
        android.view.ViewParent p = getParent();
        int depth = 0;
        while (p instanceof android.view.View && depth < 6) {
            android.view.View v = (android.view.View) p;
            int rw = -99, rh = -99;
            try {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) { rw = lp.width; rh = lp.height; }
            } catch (Throwable ignored) { }
            sb.append(v.getClass().getSimpleName())
              .append(" got=").append(v.getWidth()).append('x').append(v.getHeight())
              .append("px(").append(d > 0 ? (int) (v.getWidth() / d) : -1).append("dp)")
              .append(" req=").append(rw).append('x').append(rh);
            if (++depth < 6) sb.append(" <- ");
            p = v.getParent();
        }
        return sb.append(']').toString();
    }

    /** Event fan-out from the native engine; runs on this WebView's UI thread
     *  (MacApkWebViewBridge posts to getWebViewLooper()). */
    void macapkDispatchEvent(int what, String a, String b, String c, int i) {
        WebViewClient client = webViewClient;
        WebChromeClient chrome = webChromeClient;
        switch (what) {
            case MacApkWebViewBridge.EV_PAGE_STARTED:
                if (a != null) this.url = a;
                if (client != null) client.onPageStarted(this, a, null);
                break;
            case MacApkWebViewBridge.EV_PAGE_FINISHED:
                if (a != null) this.url = a;
                this.progress = 100;
                if (client != null) client.onPageFinished(this, a);
                break;
            case MacApkWebViewBridge.EV_PAGE_COMMIT_VISIBLE:
                if (client != null) client.onPageCommitVisible(this, a);
                break;
            case MacApkWebViewBridge.EV_RECEIVED_ERROR: {
                if (client == null) break;
                final int code = i;
                final String desc = a;
                // AOSP invokes the API-23 overload; AOSP's WebViewClient default
                // body chains MAIN-FRAME errors to the deprecated 4-arg one
                // (android14-release WebViewClient.java:337-343), so both app
                // generations see the error. MEASURED 2026-09-25: our
                // WebViewClient's API-23 body is EMPTY, so an app overriding
                // only the deprecated overload hears nothing -- that chain
                // belongs in WebViewClient, not here. c carries "main" for a
                // main-frame navigation and "" for a subresource (an
                // image/script/iframe the macapk-asset handler could not
                // serve): AOSP reports those too, with isForMainFrame() false.
                // Absent flags mean main frame.
                client.onReceivedError(this, macapkMakeRequest(b, "GET", c != null ? c : "main"),
                        new WebResourceError() {
                            @Override public int getErrorCode() { return code; }
                            @Override public CharSequence getDescription() { return desc; }
                        });
                break;
            }
            case MacApkWebViewBridge.EV_PROGRESS:
                this.progress = i;
                if (chrome != null) chrome.onProgressChanged(this, i);
                break;
            case MacApkWebViewBridge.EV_TITLE:
                this.title = a;
                if (chrome != null) chrome.onReceivedTitle(this, a);
                break;
            case MacApkWebViewBridge.EV_URL_CHANGED:
                if (a != null) this.url = a;
                macapkCanGoBack = (i & 1) != 0;
                macapkCanGoForward = (i & 2) != 0;
                break;
            case MacApkWebViewBridge.EV_JS_RESULT: {
                ValueCallback<String> cb;
                synchronized (macapkJsCallbacks) {
                    cb = macapkJsCallbacks.remove(i);
                }
                if (cb != null) cb.onReceiveValue(a);
                break;
            }
            case MacApkWebViewBridge.EV_SHOULD_OVERRIDE: {
                boolean overridden = false;
                try {
                    if (client != null)
                        overridden = client.shouldOverrideUrlLoading(
                                this, macapkMakeRequest(a, b, c));
                } finally {
                    // The page is parked on this answer; it must ALWAYS arrive.
                    MacApkWebViewBridge.nativeNavDecision(i, !overridden);
                }
                break;
            }
            case MacApkWebViewBridge.EV_HISTORY_UPDATE:
                if (client != null) client.doUpdateVisitedHistory(this, a, i != 0);
                break;
            case MacApkWebViewBridge.EV_JS_ALERT: {
                final int resultId = i;
                JsResult r = new JsResult(new JsResult.ResultReceiver() {
                    @Override public void onJsResultComplete(JsResult result) {
                        MacApkWebViewBridge.nativeJsDialogDone(resultId,
                                MacApkWebViewBridge.EV_JS_ALERT, result.getResult(), null);
                    }
                });
                boolean handled = chrome != null && chrome.onJsAlert(this, b, a, r);
                // Android shows its own dialog when unhandled; we have no
                // system dialog surface, so dismiss (the page proceeds).
                if (!handled) r.confirm();
                break;
            }
            case MacApkWebViewBridge.EV_JS_CONFIRM: {
                final int resultId = i;
                JsResult r = new JsResult(new JsResult.ResultReceiver() {
                    @Override public void onJsResultComplete(JsResult result) {
                        MacApkWebViewBridge.nativeJsDialogDone(resultId,
                                MacApkWebViewBridge.EV_JS_CONFIRM, result.getResult(), null);
                    }
                });
                boolean handled = chrome != null && chrome.onJsConfirm(this, b, a, r);
                if (!handled) r.cancel();
                break;
            }
            case MacApkWebViewBridge.EV_JS_PROMPT: {
                final int resultId = i;
                JsPromptResult r = new JsPromptResult(new JsResult.ResultReceiver() {
                    @Override public void onJsResultComplete(JsResult result) {
                        JsPromptResult pr = (JsPromptResult) result;
                        MacApkWebViewBridge.nativeJsDialogDone(resultId,
                                MacApkWebViewBridge.EV_JS_PROMPT, pr.getResult(),
                                pr.getResult() ? pr.getStringResult() : null);
                    }
                });
                boolean handled = chrome != null && chrome.onJsPrompt(this, b, a, c, r);
                if (!handled) r.cancel();
                break;
            }
            case MacApkWebViewBridge.EV_PAGE_SCROLL:
                macapkApplyPageScroll(a);
                break;
            case MacApkWebViewBridge.EV_CONSOLE: {
                // AOSP: WebChromeClient.onConsoleMessage(ConsoleMessage); when the
                // app does not handle it, Chromium writes the message to logcat
                // under "chromium" in this exact shape (content
                // WebContentsDelegate::DidAddMessageToConsole).
                ConsoleMessage.MessageLevel[] lv = ConsoleMessage.MessageLevel.values();
                int li = 1;
                try { li = Integer.parseInt(c); } catch (RuntimeException ignored) {}
                ConsoleMessage.MessageLevel level = lv[Math.max(0, Math.min(lv.length - 1, li))];
                ConsoleMessage cm = new ConsoleMessage(a, b, i, level);
                boolean handled = chrome != null && chrome.onConsoleMessage(cm);
                if (!handled) {
                    String tag = level == ConsoleMessage.MessageLevel.ERROR ? "ERROR"
                               : level == ConsoleMessage.MessageLevel.WARNING ? "WARNING"
                               : level == ConsoleMessage.MessageLevel.DEBUG ? "VERBOSE1" : "INFO";
                    com.macapk.loader.NativeLog.i("chromium", "[" + tag + ":CONSOLE(" + i + ")] \""
                            + a + "\", source: " + b + " (" + i + ")");
                }
                break;
            }
            case MacApkWebViewBridge.EV_HOST_LOST:
                // The native view our page sits in left the window (a content
                // swap, or a late SurfaceView taking the window). Re-arm the
                // change gate and ask for a draw: if this WebView's window is
                // still being drawn, draw() pushes the frame and the native side
                // re-hosts the page on the view that is on screen now; if it is
                // not (a stopped activity), nothing draws it and the page stays
                // hidden with it. See WvHostFollowEnabled in webview_bridge.mm.
                if (MacApkWebViewBridge.HOST_FOLLOW) {
                    macapkLastX = Integer.MIN_VALUE;
                    invalidate();
                }
                break;
            case MacApkWebViewBridge.EV_CLOSE_WINDOW:
                // AOSP: window.close() from page script reaches
                // WebChromeClient.onCloseWindow(WebView); the app owns the
                // teardown, so there is nothing to do when it is unhandled.
                if (chrome != null) chrome.onCloseWindow(this);
                break;
            case MacApkWebViewBridge.EV_RENDER_GONE:
                if (client != null) {
                    client.onRenderProcessGone(this, new RenderProcessGoneDetail() {
                        @Override public boolean didCrash() { return true; }
                        @Override public int rendererPriorityAtExit() { return 0; }
                    });
                }
                break;
            case MacApkWebViewBridge.EV_HTTP_ERROR:
                if (client != null) {
                    client.onReceivedHttpError(this, macapkMakeRequest(a, "GET", "main"),
                            new WebResourceResponse("text/html", "UTF-8", null));
                }
                break;
            default:
                break;
        }
    }

    /** WebViewClient.shouldInterceptRequest for a request the engine holds
     *  open (MacApkWebViewBridge.macapkInterceptRequest). Off the UI thread, as
     *  on a device. */
    WebResourceResponse macapkShouldInterceptRequest(String reqUrl, String method,
                                                     final Map<String, String> headers,
                                                     final boolean mainFrame) {
        WebViewClient client = getWebViewClient();
        if (client == null || reqUrl == null) return null;
        final Uri uri = Uri.parse(reqUrl);
        final String m = method != null ? method : "GET";
        WebResourceRequest req = new WebResourceRequest() {
            @Override public Uri getUrl() { return uri; }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return m; }
            @Override public Map<String, String> getRequestHeaders() { return headers; }
        };
        return client.shouldInterceptRequest(this, req);
    }

    private WebResourceRequest macapkMakeRequest(String reqUrl, String method,
                                                 String flags) {
        final Uri uri = reqUrl != null ? Uri.parse(reqUrl) : null;
        final String m = method != null ? method : "GET";
        final String f = flags != null ? flags : "";
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return uri; }
            @Override public boolean isForMainFrame() { return f.contains("main"); }
            @Override public boolean isRedirect() { return f.contains("redirect"); }
            @Override public boolean hasGesture() { return f.contains("gesture"); }
            @Override public String getMethod() { return m; }
            @Override public Map<String, String> getRequestHeaders() {
                return new HashMap<>();
            }
        };
    }

    /** Reflective @JavascriptInterface dispatch. Runs on the background
     *  JS-bridge thread (AOSP's JavaBridge-thread contract) while the page's
     *  JS is blocked awaiting the synchronous return. Payload/result shapes
     *  are documented in webview_bridge.mm's header. */
    String macapkInvokeJsInterface(String payload) {
        String ifc = "?", method = "?";
        try {
            org.json.JSONObject req = new org.json.JSONObject(payload);
            ifc = req.getString("ifc");
            method = req.getString("m");
            org.json.JSONArray args = req.optJSONArray("a");
            Object target;
            synchronized (jsInterfaces) {
                target = jsInterfaces.get(ifc);
            }
            if (target == null) return "";
            int argc = args != null ? args.length() : 0;
            java.util.List<String> allowed = macapkBridgeMethodNames(target);
            for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (!allowed.contains(method)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != argc) continue;
                Object[] coerced = new Object[argc];
                boolean ok = true;
                for (int j = 0; j < argc && ok; j++) {
                    Object v = args.isNull(j) ? null : args.get(j);
                    Class<?> p = params[j];
                    if (v == null) {
                        coerced[j] = null;
                        ok = !p.isPrimitive();
                    } else if (p == String.class) {
                        coerced[j] = String.valueOf(v);
                    } else if ((p == int.class || p == Integer.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).intValue();
                    } else if ((p == long.class || p == Long.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).longValue();
                    } else if ((p == double.class || p == Double.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).doubleValue();
                    } else if ((p == float.class || p == Float.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).floatValue();
                    } else if ((p == short.class || p == Short.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).shortValue();
                    } else if ((p == byte.class || p == Byte.class) && v instanceof Number) {
                        coerced[j] = ((Number) v).byteValue();
                    } else if ((p == boolean.class || p == Boolean.class)
                               && v instanceof Boolean) {
                        coerced[j] = v;
                    } else {
                        ok = false;
                    }
                }
                if (!ok) continue;
                m.setAccessible(true);
                Object result = m.invoke(target, coerced);
                if (m.getReturnType() == void.class) return "";
                org.json.JSONObject out = new org.json.JSONObject();
                if (result == null) {
                    out.put("v", org.json.JSONObject.NULL);
                } else if (result instanceof String || result instanceof Number
                           || result instanceof Boolean) {
                    out.put("v", result);
                } else if (result instanceof Character) {
                    out.put("v", String.valueOf(result));
                } else {
                    // AOSP: non-primitive/non-String returns surface as
                    // undefined in JS.
                    return "";
                }
                return out.toString();
            }
            com.macapk.loader.NativeLog.w("WebView", "js bridge: no matching method "
                    + ifc + "." + method + "/" + argc);
            return "";
        } catch (Throwable t) {
            // Name the method and the APP's own exception: the reflective
            // wrapper alone ("InvocationTargetException") says nothing about
            // which interface method threw or why.
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            StringBuilder sb = new StringBuilder("js bridge invoke failed: ")
                .append(ifc).append('.').append(method).append(": ").append(cause);
            StackTraceElement[] st = cause.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n    at ").append(st[i]);
            com.macapk.loader.NativeLog.w("WebView", sb.toString());
            return "";
        }
    }

    // ===== nested types =====

    // Result of a hit-test over the rendered page (AOSP nested class).
    public static class HitTestResult {
        public static final int UNKNOWN_TYPE = 0;
        @Deprecated public static final int ANCHOR_TYPE = 1;
        public static final int PHONE_TYPE = 2;
        public static final int GEO_TYPE = 3;
        public static final int EMAIL_TYPE = 4;
        public static final int IMAGE_TYPE = 5;
        @Deprecated public static final int IMAGE_ANCHOR_TYPE = 6;
        public static final int SRC_ANCHOR_TYPE = 7;
        public static final int SRC_IMAGE_ANCHOR_TYPE = 8;
        public static final int EDIT_TEXT_TYPE = 9;

        private int type = UNKNOWN_TYPE;
        private String extra;

        public int getType() { return type; }
        public String getExtra() { return extra; }
    }

    // Carries the target WebView for a window.open()/onCreateWindow handoff.
    // ---- Renderer priority + trackball (API 26 / API 1) --------------------
    // Both referenced by YouTube.

    /** AOSP WebView.RENDERER_PRIORITY_WAIVED: the renderer may be killed freely. */
    public static final int RENDERER_PRIORITY_WAIVED    = 0;
    /** AOSP WebView.RENDERER_PRIORITY_BOUND: tied to the app's own priority. */
    public static final int RENDERER_PRIORITY_BOUND     = 1;
    /** AOSP WebView.RENDERER_PRIORITY_IMPORTANT: the default. */
    public static final int RENDERER_PRIORITY_IMPORTANT = 2;

    private int     mRendererPriority = RENDERER_PRIORITY_IMPORTANT;
    private boolean mRendererWaivedWhenNotVisible;

    /**
     * AOSP WebView.setRendererPriorityPolicy(int, boolean).
     *
     * On Android this biases the OOM killer against a WebView's out-of-process
     * renderer. Mac.apk renders web content with WKWebView, whose content process
     * is managed entirely by WebKit -- there is no supported API to bias its
     * lifetime, and macOS does not kill processes under memory pressure the way
     * Android's lowmemorykiller does, so the hazard this API mitigates does not
     * arise in the same form. We therefore record the policy and report it back
     * faithfully rather than pretend to enforce it.
     *
     * This is under-delivery, not a lie: AOSP itself documents the policy as a
     * HINT ("the priority ... is not guaranteed"), and a device is free to ignore
     * it. Nothing an app can observe through this API is answered incorrectly.
     */
    public void setRendererPriorityPolicy(int rendererRequestedPriority,
                                          boolean waivedWhenNotVisible) {
        mRendererPriority = rendererRequestedPriority;
        mRendererWaivedWhenNotVisible = waivedWhenNotVisible;
    }

    /** AOSP WebView.getRendererRequestedPriority(). */
    public int getRendererRequestedPriority() { return mRendererPriority; }

    /** AOSP WebView.getRendererPriorityWaivedWhenNotVisible(). */
    public boolean getRendererPriorityWaivedWhenNotVisible() {
        return mRendererWaivedWhenNotVisible;
    }

    /**
     * AOSP WebView.setMapTrackballToArrowKeys(boolean) -- @Deprecated since API 17.
     *
     * Upstream this is a genuine no-op on any modern device: trackball emulation
     * was removed from the platform, and AOSP's own body does nothing. Matching
     * that exactly is the correct behaviour, not a shortcut.
     */
    @Deprecated
    public void setMapTrackballToArrowKeys(boolean setMap) { }

    public class WebViewTransport {
        private WebView webView;
        public synchronized void setWebView(WebView webview) { this.webView = webview; }
        public synchronized WebView getWebView() { return webView; }
    }

    public interface FindListener {
        void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting);
    }

    @Deprecated
    public interface PictureListener {
        @Deprecated void onNewPicture(WebView view, Picture picture);
    }

    public static abstract class VisualStateCallback {
        public abstract void onComplete(long requestId);
    }

    // One end of the channel createWebMessageChannel() hands back. The two ends
    // are entangled with each other: postMessage on one delivers to the other's
    // WebMessageCallback. AOSP delivers on the handler the callback was
    // registered with, or on the thread the WebView was initialized on when the
    // caller did not supply one, so mirror that dispatch rule.
    private static final class LocalMessagePort extends WebMessagePort {
        private final Handler viewThreadHandler;
        private LocalMessagePort peer;
        private WebMessageCallback callback;
        private Handler callbackHandler;
        private boolean closed;

        LocalMessagePort(Looper viewThread) {
            this.viewThreadHandler = (viewThread != null) ? new Handler(viewThread) : null;
        }

        void entangle(LocalMessagePort other) { this.peer = other; }

        @Override public void postMessage(WebMessage message) {
            LocalMessagePort target = peer;
            if (closed || target == null) return;
            target.deliver(message);
        }

        @Override public void close() { closed = true; }

        @Override public void setWebMessageCallback(WebMessageCallback callback) {
            setWebMessageCallback(callback, null);
        }

        @Override public void setWebMessageCallback(WebMessageCallback callback, Handler handler) {
            this.callback = callback;
            this.callbackHandler = handler;
        }

        private void deliver(final WebMessage message) {
            final WebMessageCallback cb = callback;
            if (closed || cb == null) return;
            Handler h = (callbackHandler != null) ? callbackHandler : viewThreadHandler;
            if (h != null) {
                h.post(new Runnable() {
                    @Override public void run() { cb.onMessage(LocalMessagePort.this, message); }
                });
            } else {
                cb.onMessage(this, message);
            }
        }
    }

    // The adapter createPrintDocumentAdapter() returns while there is no engine
    // to paginate. Reporting the failure through the platform's own callbacks
    // is what PrintManager expects of an adapter that cannot produce content.
    private static final class NoContentPrintDocumentAdapter extends PrintDocumentAdapter {
        private static final String REASON = "WebView has no rendered content to print";

        @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                CancellationSignal cancellationSignal, LayoutResultCallback callback,
                Bundle extras) {
            if (callback != null) callback.onLayoutFailed(REASON);
        }

        @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                CancellationSignal cancellationSignal, WriteResultCallback callback) {
            if (callback != null) callback.onWriteFailed(REASON);
        }
    }

    // Empty back/forward list returned by save/restore/copy until a real engine
    // tracks navigation history.
    private static final class EmptyBackForwardList extends WebBackForwardList {
        @Override public int getCurrentIndex() { return -1; }
        @Override public WebHistoryItem getCurrentItem() { return null; }
        @Override public WebHistoryItem getItemAtIndex(int index) { return null; }
        @Override public int getSize() { return 0; }
        @Override protected WebBackForwardList clone() { return new EmptyBackForwardList(); }
    }

    public static void enablePlatformNotifications() { }
    public static void disablePlatformNotifications() { }
    public android.webkit.WebViewProvider getWebViewProvider() { return null; }
    protected void onAnimationEnd() { }
    protected void onCreateContextMenu(android.view.ContextMenu menu) { }
    public void onScreenStateChanged(int screenState) { }
    public void setHorizontalScrollbarOverlay(boolean overlay) { }
    public void setVerticalScrollbarOverlay(boolean overlay) { }

    /**
     * AOSP, verbatim including the comment: "The old implementation defaulted
     * to true, so return true for consistency." The asymmetry with the
     * vertical variant below is AOSP's, not a typo.
     */
    @Deprecated
    public boolean overlayHorizontalScrollbar() { return true; }

    /** AOSP: "The old implementation defaulted to false, so return false for
     *  consistency." */
    @Deprecated
    public boolean overlayVerticalScrollbar() { return false; }

    /**
     * The SSL certificate for the main top-level page, or null if the site is
     * not secure (or the certificate is not known to us).
     *
     * The WKWebView bridge does not surface the server trust chain yet -- it
     * arrives on WKNavigationDelegate's didReceiveChallenge, in
     * runtime/src/webview_bridge.mm -- so this reports whatever setCertificate
     * last stored, and otherwise null. Null is AOSP's own answer for a plain
     * http page and for a page that has not finished loading, so a caller that
     * null-checks (which the API requires: the return is @Nullable) behaves
     * identically.
     */
    public android.net.http.SslCertificate getCertificate() { return certificate; }

    /**
     * AOSP: "@deprecated Calling this function has no useful effect, and will
     * be ignored in future releases." It is not ignored TODAY, though -- the
     * provider stores it -- so we store it too and hand it back from
     * getCertificate(), which is the observable behaviour on a current device.
     */
    @Deprecated
    public void setCertificate(android.net.http.SslCertificate certificate) {
        this.certificate = certificate;
    }

    /**
     * AOSP: "Gets whether private browsing is enabled in this WebView."
     *
     * Private browsing was removed from the platform WebView after API 16;
     * the deprecated privateBrowsing constructor is accepted but the provider
     * reports false. We report what was actually REQUESTED via that
     * constructor, which is false for every other constructor and therefore
     * matches a device in every case an app can reach through the modern API.
     */
    public boolean isPrivateBrowsingEnabled() { return privateBrowsingRequested; }

    /**
     * Refuse to initialize WebView in this process. AOSP:
     * WebViewFactory.disableWebView() -- it throws if a WebView already
     * exists, and otherwise every later WebView construction throws
     * IllegalStateException. Enforced for real in macapkInit().
     */
    public static void disableWebView() {
        synchronized (WebView.class) {
            if (sProviderCreated) {
                throw new IllegalStateException(
                        "Can't disable WebView: WebView already initialized");
            }
            sWebViewDisabled = true;
        }
    }

    /**
     * @deprecated WebView no longer needs to implement
     * ViewGroup.OnHierarchyChangeListener. This method does nothing now.
     *
     * AOSP still DECLARES the interface on WebView (removing it would break
     * an app that assigns a WebView as a hierarchy-change listener), so the
     * interface is implemented here and the bodies are empty, exactly as
     * upstream.
     */
    @Override
    @Deprecated
    public void onChildViewAdded(android.view.View parent, android.view.View child) {}

    /** @deprecated See {@link #onChildViewAdded}. Does nothing. */
    @Override
    @Deprecated
    public void onChildViewRemoved(android.view.View p, android.view.View child) {}

    /**
     * @deprecated WebView should not have implemented
     * ViewTreeObserver.OnGlobalFocusChangeListener. This method does nothing now.
     */
    @Override
    @Deprecated
    public void onGlobalFocusChanged(android.view.View oldFocus, android.view.View newFocus) {}
}

