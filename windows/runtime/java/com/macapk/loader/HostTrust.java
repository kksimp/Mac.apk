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
     * The merged set lives in this process's memory only, never in a file
     * another process could replace: a security provider ahead of the JDK's
     * supplies the "PKIX" TrustManagerFactory, which answers
     * TrustManagerFactory.init((KeyStore) null) -- what apps (OkHttp, Volley,
     * Conscrypt-style code), the GMS clients and SSLContext.getDefault use for
     * "the default trust store" -- with the merged set, and an init with an
     * explicit KeyStore or parameters goes to the JDK's factory unchanged. An
     * explicit javax.net.ssl.trustStore is left alone, which also makes a
     * second call a no-op.
     *
     * Building the set costs 0.6-1.3 s (the JDK's TLS classes, cacerts, the
     * Windows store), so install() only registers the provider and starts the
     * build on a background thread; a connection that needs the set before it
     * is done waits for it.
     */
    public static synchronized void install() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        if (System.getProperty("javax.net.ssl.trustStore") != null) return;
        if (java.security.Security.getProvider(Provider.NAME) != null) return;
        java.security.Security.insertProviderAt(new Provider(), 1);
        Thread build = new Thread(() -> {
            try {
                merged();
            } catch (Throwable ignored) {
                // merged() has logged it; a connection that needs the set reports its own failure.
            }
        }, "winapk-trust-build");
        build.setDaemon(true);
        build.setPriority(Thread.MIN_PRIORITY);
        build.start();
    }

    /** The JDK's CAs plus the roots Windows trusts; null if building failed (the JDK's alone are used then). */
    private static java.security.KeyStore mergedStore;
    private static boolean mergedFailed;

    private static synchronized java.security.KeyStore merged() throws Exception {
        if (mergedStore != null || mergedFailed) return mergedStore;
        try {
            mergedStore = build();
        } catch (Throwable t) {
            mergedFailed = true;
            log(true, "TLS trust anchors: kept the JDK's (" + t + ")");
        }
        return mergedStore;
    }

    private static java.security.KeyStore build() throws Exception {
        java.security.KeyStore merged = java.security.KeyStore.getInstance("JKS");
        merged.load(null, null);
        int n = 0;
        // SunJSSE by name: the provider registered here must not answer for itself.
        javax.net.ssl.TrustManagerFactory jdk = javax.net.ssl.TrustManagerFactory.getInstance(
            Provider.ALGORITHM, "SunJSSE");
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
        log(false, "TLS trust anchors: " + n + " from the JDK + " + fromWindows + " more that Windows trusts");
        return merged;
    }

    /** Puts {@link Factory} ahead of the JDK's "PKIX" TrustManagerFactory. */
    public static final class Provider extends java.security.Provider {
        static final String NAME = "WinApkTrust";
        static final String ALGORITHM = "PKIX";
        private static final long serialVersionUID = 1L;

        public Provider() {
            super(NAME, "1.0", "TLS trust anchors: the JDK's plus the roots Windows trusts");
            put("TrustManagerFactory." + ALGORITHM, Factory.class.getName());
        }
    }

    /** Hands out the JDK's trust managers, over the merged set when no trust store is named. */
    public static final class Factory extends javax.net.ssl.TrustManagerFactorySpi {
        private javax.net.ssl.TrustManagerFactory delegate;

        private static javax.net.ssl.TrustManagerFactory jdk() throws java.security.GeneralSecurityException {
            return javax.net.ssl.TrustManagerFactory.getInstance(Provider.ALGORITHM, "SunJSSE");
        }

        @Override
        protected void engineInit(java.security.KeyStore ks) throws java.security.KeyStoreException {
            try {
                javax.net.ssl.TrustManagerFactory f = jdk();
                java.security.KeyStore set = ks;
                if (set == null) {
                    try {
                        set = merged();
                    } catch (Exception e) {
                        set = null;
                    }
                }
                f.init(set);
                delegate = f;
            } catch (java.security.GeneralSecurityException e) {
                throw new java.security.KeyStoreException(e);
            }
        }

        @Override
        protected void engineInit(javax.net.ssl.ManagerFactoryParameters spec)
                throws java.security.InvalidAlgorithmParameterException {
            try {
                javax.net.ssl.TrustManagerFactory f = jdk();
                f.init(spec);
                delegate = f;
            } catch (java.security.GeneralSecurityException e) {
                if (e instanceof java.security.InvalidAlgorithmParameterException) {
                    throw (java.security.InvalidAlgorithmParameterException) e;
                }
                throw new java.security.InvalidAlgorithmParameterException(e);
            }
        }

        @Override
        protected javax.net.ssl.TrustManager[] engineGetTrustManagers() {
            if (delegate == null) throw new IllegalStateException("TrustManagerFactoryImpl is not initialized");
            return delegate.getTrustManagers();
        }
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
