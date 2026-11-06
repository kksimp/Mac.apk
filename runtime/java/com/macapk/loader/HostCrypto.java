package com.macapk.loader;

/** Host-service crypto runs with the startup provider list, as it would in a
 * separate Android system-service process. The guest's global list is never
 * changed, and the thread's previous list is restored on every exit path. */
public final class HostCrypto {
    private HostCrypto() {}
    private static volatile Object startup;
    private static boolean initialized;
    private static native Object providerList();
    private static native Object begin(Object list);
    private static native void end(Object previous);
    public static synchronized void capture() {
        if(startup!=null) return;
        try { startup=providerList(); }
        catch(UnsatisfiedLinkError bareJvm) { /* standalone Java smoke harness */ }
    }
    /** Called immediately after JNI registration, before either GMS CLI or
     * application initialization. Later calls never capture guest providers. */
    public static synchronized void initializeRuntime() {
        if (initialized) return;
        capture();
        if (startup == null) return; // bare-JVM framework regression harness
        if (System.getProperty("javax.net.ssl.trustStoreProvider") == null
                && System.getProperty("javax.net.ssl.trustStoreType") == null) {
            try {
                java.security.KeyStore host = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                System.setProperty("javax.net.ssl.trustStoreProvider", host.getProvider().getName());
            } catch (java.security.KeyStoreException failure) {
                throw new IllegalStateException("Host trust store is unavailable", failure);
            }
        }
        HostHttps.install();
        initialized = true;
    }
    public static Scope use() {
        if(startup==null) throw new IllegalStateException("Host crypto was not captured by this runtime");
        return new Scope(begin(startup));
    }
    /** Install the host implementation of Android's platform HTTPS default.
     * Explicit application factories remain under application control. */
    public static void installHttpsDefaults() {
        if (startup != null) HostHttps.install();
    }
    /** Configure an in-process system-service request independently of the
     * guest's mutable HTTPS defaults. Call while a host crypto scope is active. */
    public static void configureServiceHttps(javax.net.ssl.HttpsURLConnection connection) throws java.io.IOException {
        HostHttps.configureService(connection);
    }
    static Object currentProviders() { return providerList(); }
    static Scope use(Object providers) { return new Scope(begin(providers)); }
    public static final class Scope implements AutoCloseable {
        private final Object previous;
        private final Thread owner=Thread.currentThread();
        private boolean closed;
        private Scope(Object previous) { this.previous=previous; }
        @Override public void close() {
            if(Thread.currentThread()!=owner) throw new IllegalStateException("Crypto scope closed on another thread");
            if(!closed) { end(previous);closed=true; }
        }
    }
}
