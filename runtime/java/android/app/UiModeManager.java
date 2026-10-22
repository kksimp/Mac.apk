// android.app.UiModeManager -- stub. AOSP class lives at
// frameworks/base/core/java/android/app/UiModeManager.java and exposes
// "what kind of device am I on" queries (car, TV, watch, etc.) plus
// night-mode controls. Apps commonly grab it from
// Context.getSystemService(UI_MODE_SERVICE) inside onStart/onResume.
//
// Mac.apk only runs on macOS desktops, so we return MODE_TYPE_NORMAL
// (the "phone-or-tablet" default) for every query and quietly ignore
// the setters. This keeps Activity lifecycles that probe ui-mode from
// crashing without pretending to be a car/TV/watch (which would change
// the app's UI flow in surprising ways).
package android.app;

public class UiModeManager {
    public static final int MODE_TYPE_UNDEFINED = 0;
    public static final int MODE_TYPE_NORMAL    = 1;
    public static final int MODE_TYPE_DESK      = 2;
    public static final int MODE_TYPE_CAR       = 3;
    public static final int MODE_TYPE_TELEVISION = 4;
    public static final int MODE_TYPE_APPLIANCE = 5;
    public static final int MODE_TYPE_WATCH     = 6;
    public static final int MODE_TYPE_VR_HEADSET = 7;

    public static final int MODE_NIGHT_AUTO   = 0;
    public static final int MODE_NIGHT_NO     = 1;
    public static final int MODE_NIGHT_YES    = 2;
    public static final int MODE_NIGHT_CUSTOM = 3;

    public UiModeManager() {}

    public static boolean isTvMode() {
        return "1".equals(System.getenv("MACAPK_TV_MODE"))
            || "true".equalsIgnoreCase(System.getenv("MACAPK_TV_MODE"));
    }

    public int getCurrentModeType() {
        if (isTvMode()) {
            return MODE_TYPE_TELEVISION;
        }
        return MODE_TYPE_NORMAL;
    }

    // Night mode round-trips: AppCompat's AppCompatDelegate.setDefaultNightMode
    // and any in-app dark-mode toggle set this then re-read it to drive their
    // own theme switch, and assert the value stuck. A flat MODE_NIGHT_NO broke
    // that (the app's "dark mode" switch appeared to do nothing because the
    // read-back never changed). We persist the requested mode so get/set is
    // consistent.
    // v1.0.497: the default FOLLOWS the macOS system appearance -- Boot detects
    // it and publishes `macapk.nightMode` (yes/no), so getNightMode() reports
    // MODE_NIGHT_YES on a Dark Mac. This matches the Configuration.uiMode night
    // bit Boot also sets, so an app reading either path (UiModeManager
    // .getNightMode or the Resources Configuration) sees the same value
    // (AppCompatDelegate MODE_NIGHT_FOLLOW_SYSTEM read-back holds).
    //
    // v1.0.1047, TWO changes, both needed for a LIVE appearance flip:
    //
    // 1. The system-appearance answer is READ ON EVERY CALL instead of latched
    //    into a field at construction. The old code sampled `macapk.nightMode`
    //    in a field initializer, so a manager the app obtained before the user
    //    flipped macOS reported the boot polarity forever -- and AppCompat's
    //    MODE_NIGHT_FOLLOW_SYSTEM path asks this manager what the system is
    //    doing, so a stale answer here cancels the flip for every AppCompat app
    //    even though the Configuration and the ResourceIndex both moved.
    //    Boot.macapkApplySystemAppearance updates the property before it swaps
    //    the index, so a read during a flip never pairs old polarity with new
    //    resources.
    //
    // 2. The APP'S OWN override is now process-wide, not per-instance. Keeping
    //    set/get consistent is deliberate and predates this (an in-app dark-mode
    //    switch sets then re-reads and asserts the value stuck); what was
    //    fragile is that Context.getSystemService(UI_MODE_SERVICE) constructs a
    //    NEW UiModeManager on every call (Context.java, UI_MODE_SERVICE case --
    //    it is not one of the cached fetchers), so the override only survived
    //    for an app that happened to hold onto the same object. On a device the
    //    value lives in the system server, not in the manager instance, so
    //    process-wide is the AOSP-faithful scope and strictly more persistent
    //    than what it replaces.
    //
    // An explicit set therefore WINS over the host appearance from then on,
    // which is also what a device does: the app asked for a specific mode.
    // null = never set = follow the host.
    private static volatile Integer sAppRequestedNightMode;
    public int getNightMode() {
        Integer requested = sAppRequestedNightMode;
        if (requested != null) return requested.intValue();
        return "yes".equals(System.getProperty("macapk.nightMode"))
            ? MODE_NIGHT_YES : MODE_NIGHT_NO;
    }
    public void setNightMode(int mode) { sAppRequestedNightMode = Integer.valueOf(mode); }
    // API-30 per-app override; same persistence so AppCompat's read-back holds.
    public void setApplicationNightMode(int mode) { sAppRequestedNightMode = Integer.valueOf(mode); }

    public void enableCarMode(int flags)  { /* no-op */ }
    public void disableCarMode(int flags) { /* no-op */ }

    /** AOSP getContrast (API 34): the user's contrast preference in [0,1].
     *  Mac.apk follows the standard (0.0) contrast. */
    public float getContrast() { return 0.0f; }

    /** Notified when the user's contrast preference changes. API 34. */
    public interface ContrastChangeListener {
        void onContrastChanged(float contrast);
    }

    /** AOSP addContrastChangeListener (API 34). Mac.apk has no live contrast
     *  setting, so the listener registers but never fires. */
    public void addContrastChangeListener(java.util.concurrent.Executor executor,
            ContrastChangeListener listener) { }

    public void removeContrastChangeListener(ContrastChangeListener listener) { }

    // ================================================================
    // AOSP API-36 surface completion.
    // AOSP ref: frameworks/base/core/java/android/app/UiModeManager.java
    // (android16-release).
    // ================================================================

    /** AOSP car/desk-mode broadcast actions. NOTE these are declared
     *  {@code public static String} in AOSP -- NOT final -- so javac does not
     *  inline them and an app really does read the field at runtime. Values are
     *  AOSP's verbatim (UiModeManager.java:156, :189, :230, :237). */
    public static String ACTION_ENTER_CAR_MODE = "android.app.action.ENTER_CAR_MODE";
    public static String ACTION_EXIT_CAR_MODE = "android.app.action.EXIT_CAR_MODE";
    public static String ACTION_ENTER_DESK_MODE = "android.app.action.ENTER_DESK_MODE";
    public static String ACTION_EXIT_DESK_MODE = "android.app.action.EXIT_DESK_MODE";

    /** AOSP enableCarMode / disableCarMode flags. */
    public static final int ENABLE_CAR_MODE_GO_CAR_HOME = 0x1;
    public static final int ENABLE_CAR_MODE_ALLOW_SLEEP = 0x2;
    public static final int DISABLE_CAR_MODE_GO_HOME = 0x1;

    // ---- MODE_NIGHT_CUSTOM schedule ----
    // AOSP stores the two times in the ui-mode service and falls back to
    // LocalTime.MIDNIGHT when no service is reachable (UiModeManager.java
    // :1173-1189). Mac.apk has no ui-mode service, but the pair is a plain
    // user setting an app both writes and reads back -- AppCompat's custom
    // dark-theme schedule does exactly that -- so keep the values here rather
    // than answering MIDNIGHT to a caller that just set 22:00. AOSP's
    // no-service fallback is what an unset schedule reports.
    private volatile java.time.LocalTime mCustomNightStart = java.time.LocalTime.MIDNIGHT;
    private volatile java.time.LocalTime mCustomNightEnd = java.time.LocalTime.MIDNIGHT;

    /** AOSP getCustomNightModeStart(): "the time of the day Dark theme
     *  activates" when night mode is MODE_NIGHT_CUSTOM. */
    public java.time.LocalTime getCustomNightModeStart() { return mCustomNightStart; }
    /** AOSP setCustomNightModeStart(LocalTime). */
    public void setCustomNightModeStart(java.time.LocalTime time) {
        if (time != null) mCustomNightStart = time;
    }
    /** AOSP getCustomNightModeEnd(): "the time of the day Dark theme
     *  deactivates". */
    public java.time.LocalTime getCustomNightModeEnd() { return mCustomNightEnd; }
    /** AOSP setCustomNightModeEnd(LocalTime). */
    public void setCustomNightModeEnd(java.time.LocalTime time) {
        if (time != null) mCustomNightEnd = time;
    }
}
