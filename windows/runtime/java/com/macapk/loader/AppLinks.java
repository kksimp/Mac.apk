package com.macapk.loader;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Android App Links on Mac.apk: the verification a device's domain verifier
 * runs, and the part of a device browser that hands a verified link to its app.
 *
 * VERIFICATION is AOSP's (DomainVerificationService + the verifier agent): for
 * every host in an `android:autoVerify="true"` filter that takes VIEW +
 * BROWSABLE + DEFAULT on http/https, fetch
 * `https://<host>/.well-known/assetlinks.json` (HTTPS, no redirects) and accept
 * the host when a `delegate_permission/common.handle_all_urls` statement names
 * this package and the SHA-256 of one of the app's REAL signing certificates
 * (read from the APK, MacApkPackageManager.readApkSignatures). A wildcard host
 * `*.example.com` is verified against `example.com`, as AOSP does. Nothing is
 * assumed: a host whose file is missing, redirected or does not list the cert
 * stays DOMAIN_STATE_NONE. DomainVerificationManager reports the result.
 * Win.apk: a domain is checked only when an answer needs it, and the verdict is
 * cached per APK (see "lazy verification" below); nothing is fetched at boot.
 *
 * THE BROWSER HALF. On a device the app opens its sign-in page in the browser,
 * and when the page finally navigates to the app's own verified link (an OAuth
 * redirect_uri such as Epic's https://store.epicgames.com/authorize), the
 * browser hands that navigation to the app instead of loading it. Safari knows
 * nothing about Android App Links, so it loads the address as a web page (the
 * Epic Games Store's 404). So an open whose `redirect_uri` is one of the app's
 * verified links goes to a Mac.apk sign-in window (applink_window.mm) that
 * behaves like that browser: the navigation to the redirect is cancelled and
 * delivered to the app through the same queue as a URL LaunchServices handed
 * us (HostUrlIntake). Every other open still goes to the Mac's browser.
 *
 * MACAPK_APP_LINKS=0 turns both halves off (the pre-change behaviour: no
 * verification, every open goes to the Mac's browser).
 * docs/android-app-links-signin-window_2026-10-19.md.
 */
public final class AppLinks {
    private AppLinks() {}

    private static final String TAG = "Mac.apk.AppLinks";
    static final boolean ON = !"0".equals(System.getenv("MACAPK_APP_LINKS"));
    static final String RELATION = "delegate_permission/common.handle_all_urls";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 512 * 1024;

    private static volatile String sPkg;
    private static volatile PackageManager sPm;

    /** The hosts this manifest asks to verify: autoVerify filters that take
     *  VIEW + BROWSABLE + DEFAULT on http/https only (AOSP ignores autoVerify on a
     *  filter with any other scheme). Lower case, declaration order. */
    static List<String> autoVerifyHosts(Manifest m) {
        Set<String> out = new LinkedHashSet<>();
        if (m == null || m.activities == null) return new ArrayList<>(out);
        for (Manifest.ActivityEntry a : m.activities.values()) {
            if (a == null || !a.enabled || !a.exported) continue;
            for (Manifest.IntentFilterEntry f : a.filters) {
                if (!f.autoVerify) continue;
                if (!f.actions.contains(Intent.ACTION_VIEW)) continue;
                if (!f.categories.contains(Intent.CATEGORY_BROWSABLE)) continue;
                if (!f.categories.contains(Intent.CATEGORY_DEFAULT)) continue;
                List<String> schemes = MacApkPackageManager.macapkResolvedDataValues(f.schemes);
                boolean web = !schemes.isEmpty();
                for (String s : schemes) {
                    if (!"http".equalsIgnoreCase(s) && !"https".equalsIgnoreCase(s)) web = false;
                }
                if (!web) continue;
                for (String h : MacApkPackageManager.macapkResolvedDataValues(f.hosts)) {
                    if (h != null && !h.isEmpty()) out.add(h.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The domain a declared host is verified against ("*.a.com" -> "a.com"). */
    static String verifyDomain(String host) {
        return host.startsWith("*.") ? host.substring(2) : host;
    }

    /** "AB:CD:..." upper-case hex, the assetlinks.json fingerprint spelling. */
    static String fingerprint(byte[] cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", d[i] & 0xff));
        }
        return sb.toString();
    }

    /** Does this assetlinks.json grant `pkg` (signed by any of `fps`) the
     *  handle_all_urls relation? Statement shape per
     *  developers.google.com/digital-asset-links/v1/getting-started. */
    static boolean grants(String json, String pkg, Set<String> fps) {
        org.json.JSONArray arr;
        try {
            arr = new org.json.JSONArray(json);
        } catch (Throwable t) {
            return false;
        }
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject st = arr.optJSONObject(i);
            if (st == null) continue;
            org.json.JSONArray rel = st.optJSONArray("relation");
            boolean all = false;
            for (int r = 0; rel != null && r < rel.length(); r++) {
                if (RELATION.equals(rel.optString(r))) all = true;
            }
            if (!all) continue;
            org.json.JSONObject t = st.optJSONObject("target");
            if (t == null || !"android_app".equals(t.optString("namespace"))) continue;
            if (!pkg.equals(t.optString("package_name"))) continue;
            org.json.JSONArray f = t.optJSONArray("sha256_cert_fingerprints");
            for (int k = 0; f != null && k < f.length(); k++) {
                if (fps.contains(f.optString(k).toUpperCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    /** One assetlinks.json fetch: the body on a 200, NO_FILE when the server
     *  answered anything else (a redirect, 404 ...: the verifier does not follow
     *  redirects, so that is a definite "no"), null when it could not be
     *  reached (no network, a timeout, TLS): worth asking again soon. */
    static final String NO_FILE = "";

    static String fetchAssetLinks(String domain) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://" + domain + "/.well-known/assetlinks.json")
                    .openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return NO_FILE;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_BYTES) return NO_FILE;
                }
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- lazy verification and its cache -----------------------------------------
    //
    // A device verifies once, at install or update, and keeps the answer. Win.apk
    // used to fetch every autoVerify host on every start: Google Maps declares
    // hundreds of Google country domains, so each start made ~356 HTTPS requests
    // for answers nothing had asked for. Now nothing is fetched at boot. A domain
    // is checked when an answer depends on it -- a sign-in whose redirect_uri is
    // on that domain -- one domain at a time, and the result is kept in
    // <data>\cache\applinks\<package>.properties: a verdict for VERIFY_TTL_MS, a
    // fetch that could not reach the site for FAIL_TTL_MS. The file is dropped
    // when the APK changes (versionCode, update time or signing certificate).
    // DomainVerificationManager answers from what is known: a domain not checked
    // yet is DOMAIN_STATE_NONE, as on a device before its verifier has run.

    static final long VERIFY_TTL_MS = 7L * 24 * 3600 * 1000;
    static final long FAIL_TTL_MS = 3600L * 1000;
    /** How long a sign-in open waits for a fetch; a slower one finishes in the
     *  background and its verdict serves the next open. */
    private static final long SIGNIN_WAIT_MS = 5000;

    static final String OK = "ok", NO = "no", FAIL = "fail";

    /** One domain's cached answer. */
    static final class Entry {
        final String verdict;
        final long at;
        Entry(String verdict, long at) { this.verdict = verdict; this.at = at; }
        boolean fresh(long now) {
            long ttl = FAIL.equals(verdict) ? FAIL_TTL_MS : VERIFY_TTL_MS;
            return at <= now && now - at < ttl;
        }
    }

    /** Test seams: where the cache lives, the fetch, the clock, the APK identity. */
    interface Fetcher { String fetch(String domain); }
    static volatile File cacheDirOverride;
    static volatile Fetcher fetcher = AppLinks::fetchAssetLinks;
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    private static volatile Manifest sManifest;
    private static List<String> sHosts;              // under LOCK
    private static Set<String> sFps;                 // under LOCK
    private static String sStamp;                    // under LOCK
    private static Map<String, Entry> sCache;        // domain -> entry, under LOCK
    private static final Object LOCK = new Object();
    private static final Map<String, Thread> sInFlight = new HashMap<>();  // under LOCK

    /** Boot: remember the app; no network, no disk. */
    static void init(final String pkg, final Manifest manifest, final PackageManager pm) {
        sPkg = pkg;
        sPm = pm;
        sManifest = manifest;
    }

    /** Test seam: the app as init + its PackageInfo would give it. */
    static void initForTest(String pkg, List<String> hosts, Set<String> fps, String stamp) {
        synchronized (LOCK) {
            sPkg = pkg;
            sPm = null;
            sManifest = null;
            sHosts = new ArrayList<>(hosts);
            sFps = new HashSet<>(fps);
            sStamp = stamp;
            sCache = null;
            sInFlight.clear();
        }
    }

    /** The declared hosts, computed once. */
    private static List<String> hosts() {
        synchronized (LOCK) {
            if (sHosts == null) sHosts = ON ? autoVerifyHosts(sManifest) : Collections.<String>emptyList();
            return sHosts;
        }
    }

    /** The APK's signing-cert fingerprints and its identity stamp, once. */
    private static void loadIdentity() {
        synchronized (LOCK) {
            if (sFps != null) return;
            Set<String> fps = new HashSet<>();
            String stamp = "";
            try {
                PackageManager pm = sPm;
                PackageInfo pi = pm == null ? null : pm.getPackageInfo(sPkg, PackageManager.GET_SIGNATURES);
                if (pi != null) {
                    if (pi.signatures != null) {
                        for (Signature s : pi.signatures) fps.add(fingerprint(s.toByteArray()));
                    }
                    List<String> sorted = new ArrayList<>(fps);
                    Collections.sort(sorted);
                    stamp = pi.getLongVersionCode() + "/" + pi.lastUpdateTime + "/" + String.join(",", sorted);
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "[applinks] no signing certificate for " + sPkg + ": " + t);
            }
            sFps = fps;
            sStamp = stamp;
        }
    }

    static File cacheFile(String pkg) {
        File dir = cacheDirOverride;
        if (dir == null) dir = HostAppSupport.file("cache/applinks");
        return new File(dir, pkg + ".properties");
    }

    /** The cache, read once (under LOCK); an older APK's file reads as empty. */
    private static Map<String, Entry> cache() {
        if (sCache != null) return sCache;
        Map<String, Entry> m = new HashMap<>();
        File f = cacheFile(sPkg);
        if (f.isFile()) {
            // The certificate is read only when there is a file to check it
            // against: an app never signed in through an App Link reads nothing.
            loadIdentity();
            try (java.io.Reader in = new java.io.InputStreamReader(new java.io.FileInputStream(f),
                    StandardCharsets.UTF_8)) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                if (sStamp.equals(p.getProperty("apk"))) {
                    for (String k : p.stringPropertyNames()) {
                        if (!k.startsWith("d.")) continue;
                        String v = p.getProperty(k);
                        int comma = v.indexOf(',');
                        if (comma <= 0) continue;
                        try {
                            m.put(k.substring(2), new Entry(v.substring(0, comma),
                                    Long.parseLong(v.substring(comma + 1))));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "[applinks] cache unreadable (" + t + "); starting over");
            }
        }
        sCache = m;
        return m;
    }

    /** Write the cache (under LOCK): the stamp and the fresh entries only. */
    private static void save() {
        File f = cacheFile(sPkg);
        long now = clock.getAsLong();
        java.util.Properties p = new java.util.Properties();
        p.setProperty("apk", sStamp);
        for (Map.Entry<String, Entry> e : sCache.entrySet()) {
            if (e.getValue().fresh(now)) p.setProperty("d." + e.getKey(), e.getValue().verdict + "," + e.getValue().at);
        }
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, f.getName() + ".tmp");
            try (java.io.Writer out = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    StandardCharsets.UTF_8)) {
                p.store(out, "Win.apk: App Link verification of " + sPkg);
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            NativeLog.w(TAG, "[applinks] cache not written: " + t);
        }
    }

    /** The cached verdict for `domain` when fresh, else null. No network. */
    private static String known(String domain) {
        synchronized (LOCK) {
            Entry e = cache().get(domain);
            return e != null && e.fresh(clock.getAsLong()) ? e.verdict : null;
        }
    }

    /** Fetch and record one domain's verdict (the verifier's check). */
    private static void check(String domain) {
        Set<String> fps;
        String pkg;
        synchronized (LOCK) {
            loadIdentity();
            fps = sFps;
            pkg = sPkg;
        }
        String verdict;
        if (fps.isEmpty()) {
            verdict = NO;   // no real certificate: nothing a site could vouch for
        } else {
            String json = fetcher.fetch(domain);
            verdict = json == null ? FAIL : (json.isEmpty() || !grants(json, pkg, fps)) ? NO : OK;
        }
        NativeLog.i(TAG, "[applinks] " + pkg + ": " + domain + " -> " + verdict);
        synchronized (LOCK) {
            cache().put(domain, new Entry(verdict, clock.getAsLong()));
            save();
        }
    }

    /** Is `domain` verified? Uses the cache, else fetches it, waiting at most
     *  `waitMs`; a fetch already running for it is joined, not repeated. */
    static boolean verifyDomainNow(final String domain, long waitMs) {
        String v = known(domain);
        if (v != null) return OK.equals(v);
        Thread t;
        synchronized (LOCK) {
            t = sInFlight.get(domain);
            if (t == null) {
                t = new Thread(() -> {
                    try {
                        check(domain);
                    } finally {
                        synchronized (LOCK) { sInFlight.remove(domain); }
                    }
                }, "macapk-applinks");
                t.setDaemon(true);
                sInFlight.put(domain, t);
                t.start();
            }
        }
        try {
            t.join(waitMs);
        } catch (InterruptedException ignored) {
        }
        return OK.equals(known(domain));
    }

    /** The domains whose verification would cover `host`: those of each
     *  declared host that matches it exactly or as a wildcard. */
    static List<String> domainsFor(String host, List<String> declared) {
        List<String> out = new ArrayList<>();
        if (host == null) return out;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : declared) {
            boolean covers = h.equals(host)
                    || (h.startsWith("*.") && (host.endsWith(h.substring(1)) || host.equals(h.substring(2))));
            String d = verifyDomain(h);
            if (covers && !out.contains(d)) out.add(d);
        }
        return out;
    }

    /** The sign-in path's question: is `host` one of this app's verified App
     *  Links? Checks only the domain(s) that would cover it. */
    static boolean hostVerifiedLazily(String host, long waitMs) {
        for (String d : domainsFor(host, hosts())) {
            if (verifyDomainNow(d, waitMs)) return true;
        }
        return false;
    }

    /** The declared hosts' states from what is known now (no network). */
    static Map<String, Integer> knownStates() {
        Map<String, Integer> states = new java.util.LinkedHashMap<>();
        for (String h : hosts()) {
            states.put(h, OK.equals(known(verifyDomain(h)))
                    ? android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
                    : android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE);
        }
        return states;
    }

    /** DomainVerificationManager's answer for our own package: null when it
     *  declares no autoVerify host (AOSP's "does not declare any domains").
     *  Answers from the cache and never waits on the network: a domain not
     *  checked yet is DOMAIN_STATE_NONE. */
    public static Map<String, Integer> stateMapFor(String pkg) {
        if (!ON || pkg == null || !pkg.equals(sPkg)) return null;
        if (hosts().isEmpty()) return null;
        return Collections.unmodifiableMap(knownStates());
    }

    /** The `redirect_uri` query parameter of `url`, decoded, or null. */
    static String redirectUriOf(String url) {
        try {
            String q = new URI(url).getRawQuery();
            if (q == null) return null;
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                if (!"redirect_uri".equals(kv.substring(0, eq))) continue;
                return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** scheme://host/path of `url` with no query or fragment, or null. */
    static String returnPrefix(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (scheme == null || u.getHost() == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + port + path;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The sign-in window decision for a host open of `url`: the return prefix
     * (scheme://host/path of its redirect_uri) when that redirect is one of this
     * app's VERIFIED App Links and resolves to its own exported BROWSABLE
     * activity, else null (open in the Mac's browser as before).
     */
    static String signInReturnPrefix(String url, java.util.function.Predicate<String> verified, String pkg,
                                     PackageManager pm) {
        String redirect = redirectUriOf(url);
        if (redirect == null) return null;
        if (!isWebScheme(redirect)) {
            // Resolve the way a browser builds the intent: scheme lower-cased
            // first. Epic's EOS SDK sends redirect_uri=eos.xyza7891U64M...://
            // while the app's filter declares eos.xyza7891u64m..., and filter
            // schemes match exactly (IntentFilter.matchData), so the raw form
            // never resolved and the sign-in fell back to Safari.
            boolean own = pm != null && HostUrlIntake.resolveOwn(
                    HostUrlIntake.browserIntentFor(lowerScheme(redirect)), pkg, pm) != null;
            return schemeReturnPrefix(url, redirect, own);
        }
        String prefix = returnPrefix(redirect);
        if (prefix == null) return null;
        String host;
        try {
            host = new URI(redirect).getHost();
        } catch (Throwable t) {
            return null;
        }
        if (pm != null && HostUrlIntake.resolveOwn(HostUrlIntake.browserIntentFor(lowerScheme(redirect)), pkg, pm) == null) {
            return null;
        }
        // Last, as it may fetch the domain's assetlinks.json (once, then cached).
        if (!verified.test(host)) return null;
        return prefix;
    }

    /** MACAPK_SIGNIN_WINDOW_SCHEMES (default ON; owner's choice, 2026-10-19): a
     *  sign-in whose redirect_uri is one of the app's OWN link schemes (Rocket
     *  League's EOS login returns at eos.<client id>://epic/auth) also opens in
     *  the sign-in window. On a device that login runs in a Custom Tab, which
     *  closes when the app takes the redirect back; in Safari the tab stayed
     *  open. Trade-off the owner accepted: the window has no Safari saved
     *  passwords or passkeys. =0: those logins go to the Mac's browser again. */
    static final boolean SCHEMES_ON = !"0".equals(System.getenv("MACAPK_SIGNIN_WINDOW_SCHEMES"));

    /** Google refuses OAuth in embedded browsers ("disallowed_useragent",
     *  developers.google.com/identity/protocols/oauth2/policies#embedded-webviews),
     *  so a Google account sign-in stays in the Mac's browser. */
    static boolean embeddedRefused(String url) {
        try {
            String h = new URI(url).getHost();
            return h != null && "accounts.google.com".equalsIgnoreCase(h);
        } catch (Throwable t) {
            return true;
        }
    }

    /** `url` with its scheme lower-cased (RFC 3986 3.1: schemes are
     *  case-insensitive, and a browser canonicalises them -- Chromium's GURL --
     *  before it builds the VIEW intent). */
    static String lowerScheme(String url) {
        if (url == null) return null;
        int colon = url.indexOf(':');
        return colon <= 0 ? url : url.substring(0, colon).toLowerCase(Locale.ROOT) + url.substring(colon);
    }

    static boolean isWebScheme(String url) {
        return url.regionMatches(true, 0, "https:", 0, 6) || url.regionMatches(true, 0, "http:", 0, 5);
    }

    /** The return prefix for a sign-in that redirects to an app link SCHEME:
     *  the redirect_uri up to its query / fragment, scheme lower-cased (a
     *  browser canonicalises it before it makes the intent). Null when the
     *  scheme case is off, the redirect does not come back to this app
     *  (`redirectIsOwn`, its exported BROWSABLE activity), or the provider
     *  refuses embedded sign-in. */
    static String schemeReturnPrefix(String signInUrl, String redirect, boolean redirectIsOwn) {
        if (!SCHEMES_ON || !redirectIsOwn || redirect == null || embeddedRefused(signInUrl)) return null;
        int colon = redirect.indexOf(':');
        if (colon <= 0) return null;
        int cut = redirect.length();
        int q = redirect.indexOf('?');
        int h = redirect.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        String p = redirect.substring(0, colon).toLowerCase(Locale.ROOT) + redirect.substring(colon, cut);
        return p.length() > colon + 1 ? p : null;
    }

    /** ActivityHost.openHost: open `url` in the sign-in window when it is a
     *  sign-in that returns to one of our verified links. True when it did. */
    static boolean maybeOpenSignInWindow(String url) {
        if (!ON || url == null) return false;
        if (!url.regionMatches(true, 0, "https:", 0, 6) && !url.regionMatches(true, 0, "http:", 0, 5)) {
            return false;
        }
        if (redirectUriOf(url) == null) return false;
        String prefix = signInReturnPrefix(url, h -> hostVerifiedLazily(h, SIGNIN_WAIT_MS), sPkg, sPm);
        if (prefix == null) return false;
        boolean ok;
        try {
            ok = NativeCocoa.openAppLinkWindow(url, prefix);
        } catch (Throwable t) {
            NativeLog.w(TAG, "[applinks] sign-in window unavailable: " + t);
            return false;
        }
        NativeLog.i(TAG, "[applinks] sign-in opened in the Mac.apk window; returns to "
                + prefix + (isWebScheme(prefix) ? " (a verified App Link of " : " (a link scheme of ")
                + sPkg + ") = " + ok);
        return ok;
    }
}
