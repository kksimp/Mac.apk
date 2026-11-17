// HostBrowserTab -- the browser tab of a Custom Tab or Trusted Web Activity
// launch, shown by the runtime itself instead of the host's browser.
//
// On a device a Custom Tab (androidx.browser's CustomTabsIntent, a TWA
// launcher, AppAuth's sign-in) is the browser's tab activity in the app's own
// task, and the browser checks every navigation in it: a page that goes to a
// URL the app itself handles -- an OAuth or sign-in callback, as a custom
// scheme or an App Link -- is not loaded but started as the app's activity
// (Chromium ExternalNavigationHandler; a Custom Tab's own client is the
// trusted calling app), and the tab goes away. The host's browser can do
// neither: it keeps an https callback for itself, and a custom scheme has no
// way back to the app at all, so a sign-in through it never finishes.
//
// Where the host has a web engine (Windows: WebView2, windows/host/
// native_browser_tab.cpp) the tab is a window of the runtime, and its
// navigation check is macapkClaims below: the URL as the browser's sanitised
// ACTION_VIEW intent against THIS app's exported BROWSABLE activities, exactly
// HostUrlIntake's resolution of a URL the host hands the app. A claimed URL
// goes through HostUrlIntake's delivery (NativeCocoa.takeHostUrls), so it
// reaches the app the way a redirect from the host's browser does upstream.
//
// WHICH LAUNCHES GET THE TAB. Only a Custom Tab or TWA launch: the intent
// carries androidx.browser's EXTRA_SESSION (CustomTabsIntent always puts it,
// null session or not) or the TWA extra. A plain ACTION_VIEW of a web link --
// an "open in browser", a Privacy Policy link, a URLSpan tap -- still goes to
// the host's browser, which is what a device does with it: it opens the
// user's browser app, not a tab of the calling app, and the user's own browser
// is the faithful counterpart of that. While the tab is up the app's top
// activity is paused and stopped, as under the browser's tab activity, and it
// comes back when the tab closes.
//
// Where there is no engine (macOS today, or no WebView2 Runtime) available()
// is false and nothing changes: the URL goes to the host's browser.
package com.macapk.loader;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class HostBrowserTab {
    private static final String TAG = "Mac.apk.BrowserTab";

    /** androidx.browser CustomTabsIntent.EXTRA_SESSION: put by every
     *  CustomTabsIntent, with a null binder when there is no session. */
    static final String EXTRA_SESSION = "android.support.customtabs.extra.SESSION";
    /** androidx.browser TrustedWebUtils.EXTRA_LAUNCH_AS_TRUSTED_WEB_ACTIVITY. */
    static final String EXTRA_LAUNCH_AS_TWA = "android.support.customtabs.extra.LAUNCH_AS_TRUSTED_WEB_ACTIVITY";

    /** CustomTabsCallback navigation events. */
    static final int TAB_SHOWN = 5;
    static final int TAB_HIDDEN = 6;

    private HostBrowserTab() {}

    // ---- natives (windows/host/native_browser_tab.cpp) ----------------------
    private static native boolean nativeAvailable();
    /** A tab for `url`, the app's WebView2 user data in `folder`;
     *  `initialClaimed`: the app itself handles `url`. The tab's id, or 0. */
    private static native int nativeOpen(String url, String folder, boolean initialClaimed);

    private static volatile int sAvailable = -1;

    /** Whether this host shows browser tabs itself. Never throws: a host
     *  without the natives (macOS) answers false. */
    public static boolean available() {
        int a = sAvailable;
        if (a < 0) {
            try {
                a = nativeAvailable() ? 1 : 0;
            } catch (Throwable t) {
                a = 0;
            }
            sAvailable = a;
        }
        return a == 1;
    }

    /** A Custom Tab or TWA launch (see the header). */
    static boolean isTabIntent(Intent intent) {
        if (intent == null) return false;
        try {
            return intent.hasExtra(EXTRA_SESSION) || intent.hasExtra(EXTRA_LAUNCH_AS_TWA);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Should `uri`, started by `intent`, open in a tab here? */
    static boolean handles(String uri, Intent intent) {
        if (uri == null || !isTabIntent(intent)) return false;
        String low = uri.toLowerCase(Locale.ROOT);
        if (!low.startsWith("https://") && !low.startsWith("http://")) return false;
        return available();
    }

    // ---- open tabs -------------------------------------------------------------

    private static final class Tab {
        final SpecialAccessHandler.Cover cover;
        final IBinder session;   // the client's ICustomTabsCallback, or null
        Tab(SpecialAccessHandler.Cover cover, IBinder session) {
            this.cover = cover;
            this.session = session;
        }
    }

    private static final Map<Integer, Tab> sTabs = new HashMap<>();

    /** Opens `uri` in a tab; false when no tab could be shown (the caller then
     *  opens it as it did before). The policy gates have already passed. */
    static boolean open(String uri, Intent intent) {
        File folder = userDataFolder();
        if (folder == null) return false;
        boolean initialClaimed = macapkClaims(uri);
        int id;
        try {
            id = nativeOpen(uri, folder.getPath(), initialClaimed);
        } catch (Throwable t) {
            NativeLog.w(TAG, "browser tab unavailable: " + t);
            return false;
        }
        if (id <= 0) return false;
        IBinder session = null;
        try {
            if (intent.getExtras() != null) session = intent.getExtras().getBinder(EXTRA_SESSION);
        } catch (Throwable ignored) {}
        // The browser's tab activity is full screen: the caller is paused and
        // stopped under it, and comes back when it closes.
        SpecialAccessHandler.Cover cover = ActivityHost.hostCover(true);
        synchronized (sTabs) {
            sTabs.put(id, new Tab(cover, HostCustomTabsService.isSession(session) ? session : null));
        }
        cover.cover();
        NativeLog.i(TAG, "tab " + id + ": " + HostUrlIntake.redact(uri)
                + (intent.hasExtra(EXTRA_LAUNCH_AS_TWA) ? " (TWA launch, shown as a Custom Tab)" : "")
                + (initialClaimed ? " (a URL this app handles itself: not sent back)" : ""));
        macapkTabEvent(id, TAB_SHOWN);
        return true;
    }

    /** The app's WebView2 user-data folder: in its data directory, beside
     *  Android's own app_webview, so it is the app's alone, persists, and goes
     *  with the app's data. */
    /** userDataFolder() as a path, for the host's web engine (the app's own
     *  WebViews share it, in a profile of their own); null when unknown. */
    public static String macapkUserDataFolderPath() {
        File f = userDataFolder();
        return f != null ? f.getPath() : null;
    }

    static File userDataFolder() {
        try {
            android.app.Application app = android.app.Application.macapk_get();
            File data = app != null ? app.getDataDir() : null;
            return data != null ? new File(data, "app_webview2") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- upcalls from the tab (the host's web-engine thread) ---------------

    /** Does this app take `url` from its browser? HostUrlIntake's own
     *  resolution, so the tab hands off exactly what the intake delivers. */
    static boolean macapkClaims(String url) {
        if (!HostUrlIntake.ON || url == null) return false;
        try {
            android.app.Application app = android.app.Application.macapk_get();
            if (app == null) return false;
            String own = app.getPackageName();
            PackageManager pm = app.getPackageManager();
            Intent browser = HostUrlIntake.hostIntentFor(url, own, HostUrlIntake.INTENT_HANDOFF);
            return HostUrlIntake.resolveOwn(browser, own, pm) != null;
        } catch (Throwable t) {
            NativeLog.w(TAG, "claim check failed for " + HostUrlIntake.redact(url) + ": " + t);
            return false;
        }
    }

    /** A URL the tab does not load and the app does not take: a scheme a
     *  browser hands to another app (mailto:, tel:, an intent: URI for another
     *  package). A mail / phone / map link the user clicked goes to the host,
     *  through the same gate as any host open; an intent: URI's
     *  S.browser_fallback_url is what the tab loads instead, as Chromium does.
     *  Returns the URL to load, or null. */
    static String macapkExternal(String url, boolean userInitiated) {
        if (url == null) return null;
        String scheme = schemeOf(url);
        if (HostUrlIntake.isIntentUri(url)) {
            try {
                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = parsed.getStringExtra(HostUrlIntake.EXTRA_BROWSER_FALLBACK_URL);
                if (fallback != null) {
                    String f = fallback.toLowerCase(Locale.ROOT);
                    if (f.startsWith("https://") || f.startsWith("http://")) return fallback;
                }
            } catch (Throwable ignored) {}
            NativeLog.i(TAG, "tab: " + HostUrlIntake.redact(url) + " is for another app; not opened");
            return null;
        }
        if ("mailto".equals(scheme) || "tel".equals(scheme) || "geo".equals(scheme)) {
            if (userInitiated) {
                // A click in the page is the user's own input, as a tap on a
                // WebView is on a device.
                TouchDispatcher.noteRealInputFromHostView();
                ActivityHost.openHostUrlGated(url, "browser tab link");
            } else {
                NativeLog.i(TAG, "tab: " + scheme + ": link without a click; not opened");
            }
            return null;
        }
        NativeLog.i(TAG, "tab: no handler here for " + HostUrlIntake.redact(url) + "; not opened");
        return null;
    }

    /** A CustomTabsCallback.onNavigationEvent for the tab's session, if any. */
    static void macapkTabEvent(int id, int event) {
        Tab t;
        synchronized (sTabs) { t = sTabs.get(id); }
        if (t != null && t.session != null) HostCustomTabsService.navigationEvent(t.session, event);
    }

    /** The tab closed: by the user, by the page, or because its URL went to
     *  the app (`handedOff`; that delivery is already posted, so the app's
     *  activity it starts comes before the uncovered one). */
    static void macapkTabClosed(int id, boolean handedOff) {
        Tab t;
        synchronized (sTabs) { t = sTabs.remove(id); }
        if (t == null) return;
        if (t.session != null) HostCustomTabsService.navigationEvent(t.session, TAB_HIDDEN);
        NativeLog.i(TAG, "tab " + id + " closed" + (handedOff ? " (its URL went to the app)" : ""));
        t.cover.uncover();
    }

    /** The tab could not show its page after all (the engine failed to
     *  start): the URL opens as it did before there were tabs. */
    static void macapkTabFailed(int id, String url) {
        Tab t;
        synchronized (sTabs) { t = sTabs.remove(id); }
        NativeLog.w(TAG, "tab " + id + " could not be shown; " + HostUrlIntake.redact(url)
                + " goes to the host's browser");
        if (t != null) t.cover.uncover();
        ActivityHost.openHostNow(url, "browser tab fallback", false);
    }

    private static String schemeOf(String url) {
        int colon = url.indexOf(':');
        return colon > 0 ? url.substring(0, colon).toLowerCase(Locale.ROOT) : "";
    }
}
