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
        HostHttps.install();
        initialized = true;
    }
    public static Scope use() {
        Object providers = startup;
        return providers == null ? new Scope(null, false) : use(providers);
    }
    /** Install the host implementation of Android's platform HTTPS default.
     * Explicit application factories remain under application control. */
    public static void installHttpsDefaults() {
        if (startup != null) HostHttps.install();
    }
    /** Configure an in-process system-service request independently of the
     * guest's mutable HTTPS defaults. Call while a host crypto scope is active. */
    public static void configureServiceHttps(javax.net.ssl.HttpsURLConnection connection) throws java.io.IOException {
        if (startup != null) HostHttps.configureService(connection);
    }
    static Object currentProviders() {
        if (startup == null) return null;
        return providerList();
    }
    static Scope use(Object providers) {
        return providers == null ? new Scope(null, false) : new Scope(begin(providers), true);
    }
    public static final class Scope implements AutoCloseable {
        private final Object previous;
        private final boolean active;
        private final Thread owner=Thread.currentThread();
        private boolean closed;
        private Scope(Object previous, boolean active) { this.previous=previous; this.active=active; }
        @Override public void close() {
            if(Thread.currentThread()!=owner) throw new IllegalStateException("Crypto scope closed on another thread");
            if(!closed) { if(active) end(previous);closed=true; }
        }
    }
}
