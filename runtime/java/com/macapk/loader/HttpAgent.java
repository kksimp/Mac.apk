// HttpAgent -- the process-wide HTTP User-Agent, set the way AOSP does.
//
// AOSP RuntimeInit.commonInit (frameworks/base core/java/com/android/internal/
// os/RuntimeInit.java, main):
//     String userAgent = getDefaultUserAgent();
//     System.setProperty("http.agent", userAgent);
// getDefaultUserAgent() builds "Dalvik/<java.vm.version> (Linux; U; Android
// <Build.VERSION.RELEASE_OR_CODENAME, "1.0" if empty>[; <Build.MODEL> when
// CODENAME is "REL" and MODEL is non-empty][ Build/<Build.ID> when non-empty])".
// HttpURLConnection sends it as its default User-Agent, and apps and their HTTP
// stacks read System.getProperty("http.agent") directly. We never set it, so
// the property was null and every request that named no agent of its own went
// out as the desktop JDK's "Java/21.x".
//
// The VM version is ART's "2.1.0", not System.getProperty("java.vm.version"):
// Boot sets that property to the same value, but MACAPK_VM_VERSION_ART=0 leaves
// it at the host JDK's ("21.0.x"), and "Dalvik/21.0.x" is not an agent any
// Android release has sent. The Build values are our own android.os.Build, so
// the agent and Build cannot disagree.
//
// Caveat (JDK, not AOSP): sun.net.www.protocol.http.HttpURLConnection appends
// " Java/<version>" to a set http.agent, so its default header reads
// "Dalvik/2.1.0 (...) Java/21.x". The property itself is exact.
//
// MACAPK_HTTP_AGENT_DALVIK=0 leaves the property unset (the old behaviour).
// Gate: debug/httpagent_gate.sh.
package com.macapk.loader;

public final class HttpAgent {
    private HttpAgent() {}

    /** ART's java.vm.version on every release since Android 5. */
    static final String ART_VM_VERSION = "2.1.0";

    /** AOSP RuntimeInit.getDefaultUserAgent(), line for line. */
    public static String defaultUserAgent() {
        StringBuilder result = new StringBuilder(64);
        result.append("Dalvik/");
        result.append(ART_VM_VERSION);
        result.append(" (Linux; U; Android ");
        String version = android.os.Build.VERSION.RELEASE_OR_CODENAME;
        result.append(version.length() > 0 ? version : "1.0");
        if ("REL".equals(android.os.Build.VERSION.CODENAME)) {
            String model = android.os.Build.MODEL;
            if (model.length() > 0) {
                result.append("; ");
                result.append(model);
            }
        }
        String id = android.os.Build.ID;
        if (id.length() > 0) {
            result.append(" Build/");
            result.append(id);
        }
        result.append(")");
        return result.toString();
    }

    /** RuntimeInit.commonInit's two lines. Must run before the first
     *  HttpURLConnection is created: the JDK reads http.agent once, in that
     *  class's static initializer. */
    public static void install() {
        if ("0".equals(System.getenv("MACAPK_HTTP_AGENT_DALVIK"))) return;
        try {
            System.setProperty("http.agent", defaultUserAgent());
        } catch (Throwable ignored) {}
    }
}
