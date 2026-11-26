package android.os;

/**
 * android.os.ServiceManager -- a HIDDEN (@hide) platform class apps reach only
 * by reflection to look up system-service binders. AOSP:
 *   frameworks/base/core/java/android/os/ServiceManager.java
 * Mac.apk has no binder / servicemanager, so every lookup honestly reports
 * "not available" (null), exactly as a device does for a service the process
 * has no permission for. Facebook's lacrima crash reporter references it to
 * install a "system binder died detector" (X.0a3.<init>); without the class the
 * reference is a NoClassDefFoundError that the reporter logs and swallows, but
 * the class must exist for a caller that reflects getMethod("getService", ...)
 * and only catches ReflectiveOperationException.
 *
 * Everything returns null / empty: there are no binders to hand out, and a
 * null IBinder is the documented result for an absent service.
 */
public final class ServiceManager {
    private ServiceManager() {}

    public static class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String name) {
            super("No service published for: " + name);
        }
    }

    public static IBinder getService(String name) { return null; }
    public static IBinder checkService(String name) { return null; }
    public static IBinder getServiceOrThrow(String name) throws ServiceNotFoundException {
        IBinder service = getService(name);
        if (service != null) {
            return service;
        }
        throw new ServiceNotFoundException(name);
    }
    public static IBinder waitForService(String name) { return null; }
    public static IBinder waitForDeclaredService(String name) { return null; }
    public static void addService(String name, IBinder service) {}
    public static void addService(String name, IBinder service, boolean allowIsolated) {}
    public static void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) {}
    public static String[] listServices() { return new String[0]; }
    public static String[] listServices(int dumpPriority) { return new String[0]; }
    public static boolean isDeclared(String name) { return false; }
    public static String[] getDeclaredInstances(String iface) { return new String[0]; }
    public static void initServiceCache(java.util.Map<String, IBinder> cache) {}
}
