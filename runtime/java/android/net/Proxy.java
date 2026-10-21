// android.net.Proxy -- legacy (mostly deprecated) helpers for reading the
// system HTTP proxy. Modern apps use the standard JVM proxy properties, but
// older libraries still call Proxy.getDefaultHost() / getHost(Context).
//
// AOSP source:
//   frameworks/base/core/java/android/net/Proxy.java
// Surface mirrored: PROXY_CHANGE_ACTION / EXTRA_PROXY_INFO constants and the
// getDefaultHost/getDefaultPort/getHost(Context)/getPort(Context)/
// getProxy(Context,String) statics. AOSP itself delegates these to the
// standard JVM proxy values ("use standard java vm proxy values to find the
// host, port and exclusion list"), so we read the same System properties --
// faithful to AOSP's own implementation, not a Mac.apk shortcut.
package android.net;

import android.content.Context;

public final class Proxy {
    /** AOSP broadcast fired when the system proxy changes. */
    public static final String PROXY_CHANGE_ACTION = "android.intent.action.PROXY_CHANGE";
    /** AOSP (deprecated) extra carrying a ProxyInfo on PROXY_CHANGE. */
    @Deprecated public static final String EXTRA_PROXY_INFO = "android.intent.extra.PROXY_INFO";

    /**
     * PUBLIC on AOSP. android.net.Proxy declares no constructor, so javac gives
     * it the default public one and it appears as `ctor public Proxy();` in
     * frameworks/base/core/api/current.txt -- an app really can `new Proxy()`,
     * pointless as that is. It was private here, which would have thrown
     * IllegalAccessError on such an app.
     */
    public Proxy() {}

    /** AOSP getDefaultHost(): the global HTTP proxy host, or null if none. */
    @Deprecated public static String getDefaultHost() {
        String h = System.getProperty("http.proxyHost");
        if (h == null || h.isEmpty()) {
            // useSystemProxies=true leaves the property unset (see ProxyInfo).
            ProxyInfo sys = ProxyInfo.macapkSystemProxyOrNull();
            return sys != null ? sys.getHost() : null;
        }
        return h;
    }

    /** AOSP getDefaultPort(): the global HTTP proxy port, or -1 if no host. */
    @Deprecated public static int getDefaultPort() {
        if (getDefaultHost() == null) return -1;
        if (System.getProperty("http.proxyHost") == null || System.getProperty("http.proxyHost").isEmpty()) {
            ProxyInfo sys = ProxyInfo.macapkSystemProxyOrNull();
            return sys != null ? sys.getPort() : -1;
        }
        String p = System.getProperty("http.proxyPort");
        if (p == null || p.isEmpty()) return 80;
        try { return Integer.parseInt(p); } catch (NumberFormatException e) { return 80; }
    }

    /** AOSP getHost(Context): per-AOSP, identical to getDefaultHost (it reads
     *  the same standard JVM proxy values). */
    @Deprecated public static String getHost(Context ctx) { return getDefaultHost(); }

    /** AOSP getPort(Context): identical to getDefaultPort. */
    @Deprecated public static int getPort(Context ctx) { return getDefaultPort(); }

    /** AOSP getProxy(Context, url): resolve the java.net.Proxy to use for the
     *  given URL via the JVM's ProxySelector (honors exclusion lists), falling
     *  back to Proxy.NO_PROXY. This is the non-deprecated accessor. */
    public static java.net.Proxy getProxy(Context ctx, String url) {
        try {
            if (url != null) {
                java.net.ProxySelector sel = java.net.ProxySelector.getDefault();
                if (sel != null) {
                    java.util.List<java.net.Proxy> list = sel.select(java.net.URI.create(url));
                    if (list != null && !list.isEmpty()) return list.get(0);
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed URL or no selector: fall through to NO_PROXY.
        }
        return java.net.Proxy.NO_PROXY;
    }
}
