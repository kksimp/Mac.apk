// android.os.SystemProperties -- the @hide gateway to the platform property
// store (the `getprop`/`setprop` key/value space: ro.build.*, ro.product.*,
// debug.*, etc.). Not public SDK, but it lives on the bootclasspath, so apps
// and bundled libraries reference it directly.
//
// Why Mac.apk needs it (v1.0.406): Chromium Cronet's CronetLibraryLoader
// .initializeOnInitThread() (Reddit's HTTP stack) reaches a helper that reads
// android.os.SystemProperties; with the class absent it threw
// NoClassDefFoundError on the CronetInit thread, aborting init before it built
// base::SingleThreadTaskExecutor -> the downstream GetCurrentDefault() brk.
//
// AOSP behavior mirrored: an UNSET property reads back as "" (get) or the
// caller's default (typed getters); set() is a no-op (we have no writable
// property store). We answer a handful of read-only build properties from our
// android.os.Build so callers that key on the platform identity see a
// consistent device, and return ""/default for everything else -- exactly what
// real Android does for properties that aren't defined.
package android.os;

import java.util.HashMap;
import java.util.Map;

public class SystemProperties {
    public static final int PROP_NAME_MAX  = 31;
    public static final int PROP_VALUE_MAX = 91;

    // Read-only properties we can answer faithfully from Build. Everything not
    // in this map is treated as "unset" -> "" (AOSP-correct for unknown keys).
    private static final Map<String, String> KNOWN = new HashMap<String, String>();
    static {
        // Mirrored 2026-09-05 for AOSP parity: 12 keys -> 36. EVERY value derives
        // from the android.os.Build field the Java half already answers, so the two
        // halves of this platform cannot drift -- a guest reading
        // __system_property_get("ro.build.fingerprint") and one reading
        // Build.FINGERPRINT now get the same string. Grouped in the order
        // system/build.prop emits them.
        //
        // WHAT IS DELIBERATELY ABSENT: ro.secure, ro.adb.secure and the
        // verified-boot keys (their only consumer is an integrity check, and we do
        // not answer questions we only answer to pass one); ro.serialno and the
        // ro.boot.* SKU keys (a device whose bootloader sets none has them unset,
        // and Build reads those THROUGH this store already); ro.soc.*,
        // media_performance_class and first_api_level (values we do not have, where
        // unset is the faithful state and Build's own .orElse default matches).
        //
        // ---- ro.build.* : build identity
        KNOWN.put("ro.build.id",                          Build.ID);
        KNOWN.put("ro.build.display.id",                  Build.DISPLAY);
        KNOWN.put("ro.build.version.incremental",         Build.VERSION.INCREMENTAL);
        KNOWN.put("ro.build.version.sdk",                 Integer.toString(Build.VERSION.SDK_INT));
        KNOWN.put("ro.build.version.sdk_full",
                  Build.getMajorSdkVersion(Build.VERSION.SDK_INT_FULL) + "."
                  + Build.getMinorSdkVersion(Build.VERSION.SDK_INT_FULL));
        KNOWN.put("ro.build.version.preview_sdk",         Integer.toString(Build.VERSION.PREVIEW_SDK_INT));
        KNOWN.put("ro.build.version.codename",            Build.VERSION.CODENAME);
        KNOWN.put("ro.build.version.all_codenames",
                  Build.VERSION.ACTIVE_CODENAMES.length == 0
                      ? "REL" : String.join(",", Build.VERSION.ACTIVE_CODENAMES));
        KNOWN.put("ro.build.version.release",             Build.VERSION.RELEASE);
        KNOWN.put("ro.build.version.release_or_codename", Build.VERSION.RELEASE_OR_CODENAME);
        KNOWN.put("ro.build.version.release_or_preview_display",
                  Build.VERSION.RELEASE_OR_PREVIEW_DISPLAY);
        KNOWN.put("ro.build.version.security_patch",      Build.VERSION.SECURITY_PATCH);
        // Present-but-EMPTY on a device with no base OS, which is not the same as
        // absent: _get returns 0 while _find returns a real handle.
        KNOWN.put("ro.build.version.base_os",             Build.VERSION.BASE_OS);
        // ⚠ SECONDS here, MILLISECONDS in Build.TIME. AOSP:
        // `TIME = getLong("ro.build.date.utc") * 1000` (Build.java:1928), and the
        // build writes `date +%s`. Pushing the millisecond value would make every
        // native reader's clock wrong by a factor of 1000.
        KNOWN.put("ro.build.date.utc",                    Long.toString(Build.TIME / 1000L));
        KNOWN.put("ro.build.type",                        Build.TYPE);
        KNOWN.put("ro.build.user",                        Build.USER);
        KNOWN.put("ro.build.host",                        Build.HOST);
        KNOWN.put("ro.build.tags",                        Build.TAGS);
        // The DERIVED 8-component string, identical in build/make/core/config.mk,
        // Build.deriveFingerprint() and init's ConstructBuildFingerprint:
        // brand/name/device:release/id/incremental:type/tags. Each component is
        // itself a key above, so a native reader can rebuild it and agree.
        KNOWN.put("ro.build.fingerprint",                 Build.FINGERPRINT);
        // ---- device identity
        KNOWN.put("ro.product.brand",                     Build.BRAND);
        KNOWN.put("ro.product.device",                    Build.DEVICE);
        KNOWN.put("ro.product.manufacturer",              Build.MANUFACTURER);
        KNOWN.put("ro.product.model",                     Build.MODEL);
        KNOWN.put("ro.product.name",                      Build.PRODUCT);
        KNOWN.put("ro.product.board",                     Build.BOARD);
        KNOWN.put("ro.bootloader",                        Build.BOOTLOADER);
        KNOWN.put("ro.hardware",                          Build.HARDWARE);
        // ---- ABIs. abilist32 is PRESENT WITH AN EMPTY VALUE on a 64-only device,
        // not absent: init sets all three base keys unconditionally.
        KNOWN.put("ro.product.cpu.abi",                   Build.CPU_ABI);
        KNOWN.put("ro.product.cpu.abilist",               String.join(",", Build.SUPPORTED_ABIS));
        KNOWN.put("ro.product.cpu.abilist32",             String.join(",", Build.SUPPORTED_32_BIT_ABIS));
        KNOWN.put("ro.product.cpu.abilist64",             String.join(",", Build.SUPPORTED_64_BIT_ABIS));
        // ---- the one partition getFingerprintedPartitions() answers for
        KNOWN.put("ro.system.build.fingerprint",          Build.FINGERPRINT);
        KNOWN.put("ro.system.build.date.utc",             Long.toString(Build.TIME / 1000L));
        // "0"/"1", NOT "false"/"true": AOSP reads it with getInt.
        KNOWN.put("ro.debuggable",                        Build.IS_DEBUGGABLE ? "1" : "0");
        KNOWN.put("ro.config.low_ram", "false");
        // The device's STABLE display density, in dpi. Every real device has
        // this set by init from build.prop before anything else runs (it is
        // write-once), and AOSP's DisplayMetrics.getDeviceDensity() reads it to
        // initialise DENSITY_DEVICE / DENSITY_DEVICE_STABLE. Answering "" here
        // made those fall back to DENSITY_DEFAULT (160), which would have
        // contradicted every other density in this tree.
        //
        // v1.0.2187: the launch screen's density, which the native side decides
        // once and passes as -Dmacapk.display.density (320 on a Retina screen,
        // 160 on a 1x monitor: one dp is one Mac point on both; see
        // CocoaDisplayDensityDpi in cocoa_bridge.mm). It was a fixed "320" until
        // then, which told a 1x monitor's app half the dp its window has.
        // DisplayMetrics' DENSITY_DEVICE / DENSITY_DEVICE_STABLE read THIS
        // property, and every other default density follows them. A literal
        // fallback rather than a reference to DisplayMetrics.DENSITY_XHIGH on
        // purpose: DisplayMetrics' own <clinit> reads this map, and a
        // back-reference would make the two static initialisers mutually
        // dependent.
        KNOWN.put("ro.sf.lcd_density", macapkLaunchDensity());

        // DIAGNOSTIC (MACAPK_PAIRIP_DEVICE_IDENTITY, default OFF): the pairip integrity
        // VM reads ~40 ro.* properties AND the /proc/self/maps + dl_iterate_phdr module
        // set. Our module set is transcribed from a genuine Google API34 emulator, but
        // these properties describe "Apple Mac / macapk / apple_silicon" -- an incoherent
        // Frankendevice a real Android never is, and every non-Android token is folded
        // into pairip's per-record accumulator (mod 186 -> bracket test), tipping it into
        // the poison bracket. This gate makes the DEVICE IDENTITY coherent with the module
        // set (a google emulator) so we can MEASURE whether that incoherence is the
        // divergence. Shipping it is a device-identity decision reserved for the owner; it
        // is not a license/attestation forge (no Google-signed token), just a coherent
        // public platform surface. Values are self-consistent (all google emulator).
        if (isSet("MACAPK_PAIRIP_DEVICE_IDENTITY")) {
            KNOWN.put("ro.product.brand",           "google");
            KNOWN.put("ro.product.manufacturer",    "Google");
            KNOWN.put("ro.product.model",           "sdk_gphone64_arm64");
            KNOWN.put("ro.product.name",            "sdk_gphone64_arm64");
            KNOWN.put("ro.product.device",          "emu64a");
            KNOWN.put("ro.product.board",           "goldfish_arm64");
            KNOWN.put("ro.hardware",                "ranchu");
            KNOWN.put("ro.bootloader",              "unknown");
            String fp = "google/sdk_gphone64_arm64/emu64a:"
                      + Build.VERSION.RELEASE + "/" + Build.ID + "/"
                      + Build.VERSION.INCREMENTAL + ":user/release-keys";
            KNOWN.put("ro.build.fingerprint",        fp);
            KNOWN.put("ro.system.build.fingerprint", fp);
            KNOWN.put("ro.build.host",              "abfarm-release");
            KNOWN.put("ro.build.user",              "android-build");
        }
    }
    private static boolean isSet(String k) {
        try { String v = System.getenv(k); return v != null && !v.isEmpty() && !v.equals("0"); }
        catch (Throwable t) { return false; }
    }

    /** Mirror this table into the bionic shim's property store, so a GUEST
     *  asking `__system_property_get(key, buf)` gets the same answer this class
     *  gives a Java caller.
     *
     *  WHY IT IS A PUSH AND NOT A PULL. The shim answered "not found" for every
     *  key, so a guest's inlined `android_get_device_api_level()` -- which is
     *  literally `__system_property_get("ro.build.version.sdk", buf)` with a -1
     *  fallback -- returned **-1** while Java answered PresentedSdk.get(). That
     *  is a silent Java-vs-native disagreement about the one value the whole
     *  API-level campaign turns on, and it becomes WRONG rather than merely
     *  absent the moment we present 34 (libandroidx.graphics.path.so, in 9
     *  corpus apps, branches at exactly 34).
     *
     *  The values are the ones THIS class already computed from Build, which is
     *  PresentedSdk.get(). Deriving the level a second time in C++ is exactly
     *  the multi-source incoherence PresentedSdk was created to end.
     *
     *  Called once from Boot, after the JNI natives are registered and before
     *  any guest code runs. Failure is non-fatal and loud: a guest that cannot
     *  read the level behaves as it did before this existed. */
    public static void macapkPushToNative() {
        int pushed = 0;
        for (Map.Entry<String, String> e : KNOWN.entrySet()) {
            try {
                com.macapk.loader.NativeCocoa.setSystemProperty(e.getKey(), e.getValue());
                pushed++;
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.SystemProperties",
                    "could not push " + e.getKey() + " to the native store: " + t);
                return;   // the bridge is missing entirely; one line, not N
            }
        }
        com.macapk.loader.NativeLog.i("Mac.apk.SystemProperties",
            "pushed " + pushed + " properties to the native store"
            + " (ro.build.version.sdk=" + KNOWN.get("ro.build.version.sdk") + ")");
    }

    public static String get(String key) {
        if (key == null) return "";
        String v = KNOWN.get(key);
        return v != null ? v : "";
    }

    public static String get(String key, String def) {
        if (key == null) return def;
        String v = KNOWN.get(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    public static int getInt(String key, int def) {
        try {
            String v = get(key);
            return v.isEmpty() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        try {
            String v = get(key);
            return v.isEmpty() ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = get(key);
        if (v.isEmpty()) return def;
        if ("1".equals(v) || "y".equals(v) || "yes".equals(v) || "true".equals(v) || "on".equals(v))
            return true;
        if ("0".equals(v) || "n".equals(v) || "no".equals(v) || "false".equals(v) || "off".equals(v))
            return false;
        return def;
    }

    /** Mac.apk has no writable property store; AOSP set() requires the
     *  "init"/"shell" UID anyway, so apps treat failures as no-ops. */
    public static void set(String key, String val) {}

    public static void addChangeCallback(Runnable callback) {}
    public static void callChangeCallbacks() {}
    public static void reportSyspropChanged() {}

    public static String getProp(String key) { return get(key); }
    public static String getProp(String key, String def) { return get(key, def); }

    /** The -Dmacapk.display.density the runtime passes (a dpi, 120..640), else
     *  "320" -- the answer for a bare JVM with no runtime (offline gates). */
    private static String macapkLaunchDensity() {
        try {
            String v = System.getProperty("macapk.display.density");
            if (v != null) {
                int dpi = Integer.parseInt(v.trim());
                if (dpi >= 120 && dpi <= 640) return Integer.toString(dpi);
            }
        } catch (Throwable ignored) {}
        return "320";
    }
}
