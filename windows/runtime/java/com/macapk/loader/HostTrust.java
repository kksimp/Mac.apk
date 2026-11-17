// com.macapk.loader.HostTrust -- the JVM's default TLS trust store on Windows.
//
// Its own class, not a method of Boot, because two entry points need it and
// only one of them boots an app: Boot.run (an app's process) and
// com.macapk.gms.GmsCli.run (`winapk-runtime --gms`, what Settings > Google
// account runs). Calling into Boot from the second would run Boot's static
// initialisers for a process that has no app. Without it there, the sign-in's
// token exchange behind a filtering proxy failed with "PKIX path building failed" while
// the same exchange from inside an app worked.
package com.macapk.loader;

public final class HostTrust {
    private static final String TAG = "Win.apk.HostTrust";

    private HostTrust() {}

    /**
     * On Windows, the trust anchors every TLS connection checks against are the
     * JDK's CAs AND the ones Windows itself trusts. On a device the system store
     * holds what the user's device trusts; on a PC that is the Windows root
     * store, which also holds the root of a filtering proxy, a
     * company's TLS inspection or a locally installed CA. With the JDK's
     * cacerts alone every connection behind such a proxy failed with "PKIX
     * path building failed". The Windows store alone is not enough either: it
     * fetches public roots on demand, so one it never needed may be missing.
     *
     * The merged set is a JKS file made the JVM's default trust store before
     * anything initialises TLS: apps (OkHttp, Volley, Conscrypt-style code) and
     * the GMS clients build their trust managers from
     * TrustManagerFactory.init((KeyStore) null), which reads exactly that
     * default when it first needs it. An explicit javax.net.ssl.trustStore is
     * left alone, which also makes a second call a no-op.
     *
     * Building the set costs 0.6-1.3 s (the JDK's TLS classes, cacerts, the
     * Windows store), on the main thread before the app's first frame. So it is
     * kept in one file, %TEMP%\winapk-trust.jks, shared by every app: a start
     * that finds it uses it at once. One more than a day old is rebuilt in the
     * background once the app is up, for the next start, so a root added to
     * Windows (a filter's, a company's) reaches the apps within a day; only the
     * first start ever builds it in line. JKS, not PKCS12: these are public certificates, and PKCS12 encrypts
     * every entry, which alone took 220-350 ms. (It also replaces the temporary
     * file each start wrote: deleteOnExit never ran, since the process ends with
     * TerminateProcess, so %TEMP% kept 0.7 MB per app start.)
     */
    public static synchronized void install() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        if (System.getProperty("javax.net.ssl.trustStore") != null) return;
        final java.io.File store = new java.io.File(System.getProperty("java.io.tmpdir"), "winapk-trust.jks");
        if (store.isFile() && store.length() > 0) {
            use(store);
            final long age = System.currentTimeMillis() - store.lastModified();
            if (age >= 0 && age < MAX_AGE_MS) {
                log(false, "TLS trust anchors: the JDK's and Windows' from " + store);
                return;
            }
            Thread refresh = new Thread(() -> {
                try {
                    Thread.sleep(REFRESH_DELAY_MS);
                    build(store);
                } catch (Throwable t) {
                    log(true, "TLS trust anchors: refresh failed (" + t + ")");
                }
            }, "winapk-trust-refresh");
            refresh.setDaemon(true);
            refresh.setPriority(Thread.MIN_PRIORITY);
            refresh.start();
            log(false, "TLS trust anchors: the JDK's and Windows' from " + store + " (refreshed in the background)");
            return;
        }
        try {
            build(store);
            use(store);
        } catch (Throwable t) {
            log(true, "TLS trust anchors: kept the JDK's (" + t + ")");
        }
    }

    /** How long a built set is used as it is. */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /** After the app's first frame, as a rule; the next start reads the result. */
    private static final long REFRESH_DELAY_MS = 20_000;

    private static void use(java.io.File store) {
        System.setProperty("javax.net.ssl.trustStore", store.getAbsolutePath());
        System.setProperty("javax.net.ssl.trustStoreType", "JKS");
        System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD);
    }

    private static final String PASSWORD = "winapk";

    /** Writes the JDK's CAs plus the roots Windows trusts to `store`, through a
     *  file of its own that replaces it in one step, so another app starting
     *  at the same moment reads either the old set or the new one. */
    private static void build(java.io.File store) throws Exception {
        java.security.KeyStore merged = java.security.KeyStore.getInstance("JKS");
        merged.load(null, null);
        int n = 0;
        javax.net.ssl.TrustManagerFactory jdk = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((java.security.KeyStore) null);
        for (javax.net.ssl.TrustManager tm : jdk.getTrustManagers()) {
            if (!(tm instanceof javax.net.ssl.X509TrustManager)) continue;
            for (java.security.cert.X509Certificate c : ((javax.net.ssl.X509TrustManager) tm).getAcceptedIssuers()) {
                merged.setCertificateEntry("jdk-" + (n++), c);
            }
        }
        int fromWindows = 0;
        java.security.KeyStore win = java.security.KeyStore.getInstance("Windows-ROOT");
        win.load(null, null);
        for (java.util.Enumeration<String> e = win.aliases(); e.hasMoreElements();) {
            java.security.cert.Certificate c = win.getCertificate(e.nextElement());
            if (c == null || merged.getCertificateAlias(c) != null) continue;
            merged.setCertificateEntry("windows-" + (fromWindows++), c);
        }
        java.io.File part = java.io.File.createTempFile("winapk-trust-", ".part", store.getParentFile());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                merged.store(out, PASSWORD.toCharArray());
            }
            java.nio.file.Files.move(part.toPath(), store.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            part.delete();
        }
        log(false, "TLS trust anchors: " + n + " from the JDK + " + fromWindows
            + " more that Windows trusts, written to " + store);
    }

    // NativeLog is a native: a bare JVM (debug/gms.sh) has none bound.
    private static void log(boolean warn, String m) {
        try {
            if (warn) NativeLog.w(TAG, m); else NativeLog.i(TAG, m);
        } catch (Throwable t) {
            System.err.println("[" + TAG + "] " + m);
        }
    }
}
