package dalvik.system;

/**
 * AOSP dalvik.system.ApplicationRuntime (public SDK API, Android 16). Mirrors
 * libcore/dalvik/src/main/java/dalvik/system/ApplicationRuntime.java: one static
 * method that forwards to VMRuntime.getBaseApkOptimizationInfo().
 *
 * Play Store 53.7 calls it from a background task; without the class that task
 * died with NoClassDefFoundError: dalvik/system/ApplicationRuntime.
 */
public final class ApplicationRuntime {
    private ApplicationRuntime() {}

    /**
     * Returns the optimization status of the base APK loaded in this process. If no base APK has
     * been loaded in this process, this methods returns an object, but the contents are undefined.
     */
    public static DexFile.OptimizationInfo getBaseApkOptimizationInfo() {
        return VMRuntime.getBaseApkOptimizationInfo();
    }
}
