package dalvik.system;

/** AOSP libcore's VM debugger-query entry points, backed by this host VM.
 * ART's JDWP connection counter is not available on HotSpot. If a JDWP agent
 * is enabled we conservatively report debugging, including while it listens;
 * this never hides an enabled debugger from an app's security checks. */
public final class VMDebug {
    private VMDebug() {}
    public static boolean isDebuggingEnabled() {
        for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-agentlib:jdwp") || arg.startsWith("-Xrunjdwp")) return true;
        }
        return false;
    }
    public static boolean isDebuggerConnected() { return isDebuggingEnabled(); }
    public static long lastDebuggerActivity() { return isDebuggingEnabled() ? 0 : -1; }
}
