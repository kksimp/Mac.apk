package com.macapk.loader;

/**
 * Whether this app is presented a TELEVISION device (Android TV) instead of a phone.
 *
 * Many apps ship a phone interface and a Leanback (10-foot, D-pad) interface in the
 * one APK and choose between them by asking the platform what kind of device it is:
 * UiModeManager.getCurrentModeType(), Configuration.uiMode, and the
 * android.software.leanback / android.hardware.type.television features. On a real
 * Android TV all of those say "television". This is the switch that makes our device
 * answer the same way for all of them at once, so an app never sees a mix.
 *
 * It is opt-in per app (the "Run in Android TV mode" toggle in the app's info sheet,
 * which stores MACAPK_TV_MODE=1 in that app's environment overrides) and never keyed
 * on a package name. Off, nothing changes: the device is the phone it always was.
 */
public final class TvMode {
    private TvMode() {}

    public static final String ENV = "MACAPK_TV_MODE";

    private static final boolean ENABLED = parse(System.getenv(ENV));

    /** "1" or "true" (any case) turns it on; anything else, or unset, is off. */
    static boolean parse(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    public static boolean isEnabled() { return ENABLED; }

    /** The features a TV reports on top of the phone set. AOSP names:
     *  PackageManager.FEATURE_LEANBACK and FEATURE_TELEVISION. */
    public static final String[] FEATURES = {
        "android.software.leanback",
        "android.hardware.type.television",
    };

    /** The Configuration.uiMode device-type bits for the current mode. */
    public static int uiModeType() {
        return ENABLED ? android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                       : android.content.res.Configuration.UI_MODE_TYPE_NORMAL;
    }
}
