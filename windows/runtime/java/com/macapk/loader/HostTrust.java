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
     * another process could replace. A security provider ahead of the JDK's
     * reaches it by two doors, because the JDK's own default context never
     * calls init(null): its trust managers come from the first "PKIX" factory,
     * and when that is not SunJSSE's it is initialised with the JDK's cacerts
     * KeyStore, an explicit store that must stay untouched (an app's own store
     * looks the same).
     * <ul>
     * <li>The "PKIX" TrustManagerFactory answers init((KeyStore) null) -- what
     * apps (OkHttp, Volley, Conscrypt-style code) and the GMS clients use for
     * "the default trust store" -- with the merged set; an init with an
     * explicit KeyStore or parameters goes to the JDK's factory unchanged.</li>
     * <li>The "Default" SSLContext, which SSLContext.getDefault and so
     * HttpsURLConnection, SSLSocketFactory.getDefault and an HttpClient with no
     * context use, is replaced by one over the same merged set
     * ({@link DefaultContext}).</li>
     * </ul>
     * An explicit javax.net.ssl.trustStore is left alone, which also makes a
     * second call a no-op.
     *
     * Building the set costs 0.6-1.3 s (the JDK's TLS classes, cacerts, the
     * Windows store), so install() only registers the provider and starts the
     * build on a background thread; a connection that needs the set before it
     * is done waits for it, the default context's included.
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

    /** Puts {@link Factory} and {@link DefaultContext} ahead of the JDK's "PKIX" factory and "Default" context. */
    public static final class Provider extends java.security.Provider {
        static final String NAME = "WinApkTrust";
        static final String ALGORITHM = "PKIX";
        private static final long serialVersionUID = 1L;

        public Provider() {
            super(NAME, "1.0", "TLS trust anchors: the JDK's plus the roots Windows trusts");
            put("TrustManagerFactory." + ALGORITHM, Factory.class.getName());
            put("SSLContext.Default", DefaultContext.class.getName());
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

    /**
     * What SSLContext.getInstance("Default") returns while this provider is installed: SunJSSE's "TLS" context
     * (so the jdk.tls.* properties apply, as they do to the JDK's default) with the merged trust managers and
     * the javax.net.ssl.keyStore* key managers the JDK's default would have. One context is shared by every
     * instance, as the JDK's is, so sessions are cached across callers.
     */
    public static final class DefaultContext extends javax.net.ssl.SSLContextSpi {
        private static javax.net.ssl.SSLContext shared;

        private final javax.net.ssl.SSLContext ctx;

        public DefaultContext() throws java.security.GeneralSecurityException, java.io.IOException {
            ctx = shared();
        }

        private static synchronized javax.net.ssl.SSLContext shared()
                throws java.security.GeneralSecurityException, java.io.IOException {
            if (shared == null) {
                Factory f = new Factory();
                f.engineInit((java.security.KeyStore) null);
                javax.net.ssl.SSLContext c = javax.net.ssl.SSLContext.getInstance("TLS", "SunJSSE");
                c.init(keyManagers(), f.engineGetTrustManagers(), null);
                shared = c;
            }
            return shared;
        }

        /** The JDK default context's key managers: javax.net.ssl.keyStore* (an empty store when unset). */
        private static javax.net.ssl.KeyManager[] keyManagers()
                throws java.security.GeneralSecurityException, java.io.IOException {
            String path = System.getProperty("javax.net.ssl.keyStore", "");
            String type = System.getProperty("javax.net.ssl.keyStoreType", java.security.KeyStore.getDefaultType());
            String prov = System.getProperty("javax.net.ssl.keyStoreProvider", "");
            String pw = System.getProperty("javax.net.ssl.keyStorePassword", "");
            char[] passwd = pw.isEmpty() ? null : pw.toCharArray();
            java.security.KeyStore ks = null;
            if (!type.isEmpty()) {
                ks = prov.isEmpty() ? java.security.KeyStore.getInstance(type)
                                    : java.security.KeyStore.getInstance(type, prov);
                if (path.isEmpty() || path.equals("NONE")) {
                    ks.load(null, passwd);
                } else {
                    try (java.io.InputStream in = new java.io.FileInputStream(path)) {
                        ks.load(in, passwd);
                    }
                }
            }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "PKCS11".equals(type) ? null : passwd);
            return kmf.getKeyManagers();
        }

        @Override
        protected void engineInit(javax.net.ssl.KeyManager[] km, javax.net.ssl.TrustManager[] tm,
                java.security.SecureRandom sr) throws java.security.KeyManagementException {
            throw new java.security.KeyManagementException("Default SSLContext is initialized automatically");
        }

        @Override
        protected javax.net.ssl.SSLSocketFactory engineGetSocketFactory() {
            return ctx.getSocketFactory();
        }

        @Override
        protected javax.net.ssl.SSLServerSocketFactory engineGetServerSocketFactory() {
            return ctx.getServerSocketFactory();
        }

        @Override
        protected javax.net.ssl.SSLEngine engineCreateSSLEngine() {
            return ctx.createSSLEngine();
        }

        @Override
        protected javax.net.ssl.SSLEngine engineCreateSSLEngine(String host, int port) {
            return ctx.createSSLEngine(host, port);
        }

        @Override
        protected javax.net.ssl.SSLSessionContext engineGetServerSessionContext() {
            return ctx.getServerSessionContext();
        }

        @Override
        protected javax.net.ssl.SSLSessionContext engineGetClientSessionContext() {
            return ctx.getClientSessionContext();
        }

        @Override
        protected javax.net.ssl.SSLParameters engineGetDefaultSSLParameters() {
            return ctx.getDefaultSSLParameters();
        }

        @Override
        protected javax.net.ssl.SSLParameters engineGetSupportedSSLParameters() {
            return ctx.getSupportedSSLParameters();
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
