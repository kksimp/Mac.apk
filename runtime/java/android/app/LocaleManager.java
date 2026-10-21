// android.app.LocaleManager -- the API-33 per-app locale service. Apps
// (and androidx.appcompat's AppLocalesStorageHelper) set/read their
// preferred UI locales through it. AOSP:
//   https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/app/LocaleManager.java
//
// Previously a bare stub, so any app that called getApplicationLocales /
// setApplicationLocales (Settings-style language pickers, AppCompat 1.6+
// per-app language) hit a NoSuchMethodError. We back it with a stored
// LocaleList that defaults to the system locale; setting it updates the
// JVM default locale so subsequent Locale.getDefault() / formatting
// reflects the choice, round-trips through getApplicationLocales, AND
// (see setApplicationLocales below) dispatches a Configuration change to
// the current top Activity so the running UI actually re-renders in the
// new language, the same way a real device-level locale change would.
package android.app;

import android.os.LocaleList;

import java.util.Locale;

public class LocaleManager {
    public LocaleManager() {}

    // The app's currently-selected locales. Empty == "follow the system",
    // which is what AOSP returns when the app has set no override.
    //
    // PROCESS-WIDE AND PERSISTED. AOSP keeps the selection in the system's
    // LocaleManagerService and it survives restarts -- that is the point of a
    // per-app language: AppCompat 1.6+ on API 33+ hands its storage to the
    // platform (AppLocalesStorageHelper) and reads it back at every launch.
    // Here it was an instance field, and Context caches one LocaleManager per
    // Context, so an Activity's choice was invisible through the Application
    // and every choice was gone on the next start. It is now one value per
    // process, saved to <dataDir>/macapk_app_locales and read back on first use.
    private static volatile LocaleList applicationLocales;
    private static final String STORE_NAME = "macapk_app_locales";

    private static java.io.File storeFile() {
        try {
            Application app = Application.getInstance();
            java.io.File dir = app != null ? app.getDataDir() : null;
            return dir != null ? new java.io.File(dir, STORE_NAME) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static LocaleList loaded() {
        LocaleList l = applicationLocales;
        if (l != null) return l;
        synchronized (LocaleManager.class) {
            if (applicationLocales != null) return applicationLocales;
            l = LocaleList.getEmptyLocaleList();
            java.io.File f = storeFile();
            if (f != null && f.isFile()) {
                try {
                    String tags = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!tags.isEmpty()) {
                        l = LocaleList.forLanguageTags(tags);
                        if (l.size() > 0 && l.get(0) != null) Locale.setDefault(l.get(0));
                    }
                } catch (Throwable ignored) {}
            }
            applicationLocales = l;
            return l;
        }
    }

    private static void save(LocaleList l) {
        java.io.File f = storeFile();
        if (f == null) return;
        try {
            if (l.size() == 0) {
                f.delete();
                return;
            }
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(),
                l.toLanguageTags().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            android.util.Log.w("LocaleManager", "could not save app locales: " + t);
        }
    }

    // Captured once at class-load, before any app has ever called
    // setApplicationLocales -- the true boot-time system locale, so a caller
    // that clears its override (empty LocaleList = "follow system", the AOSP
    // contract) can be restored to the real system locale rather than
    // whatever locale happened to be active most recently.
    private static final Locale SYSTEM_LOCALE = Locale.getDefault();

    /** AOSP: set this app's preferred UI locales. An empty list clears the
     *  override (revert to system). We also push the primary locale to the
     *  JVM default so date/number formatting and Locale.getDefault() follow
     *  -- correct here (not the cross-process bleed AOSP's real per-app
     *  override guards against) because Mac.apk hosts exactly one app per
     *  JVM process, so "JVM-global default" and "this app's process default"
     *  are the same scope; real Android's own ActivityThread.handleConfigurationChanged
     *  path calls Locale.setDefault() too when applying a locale change,
     *  for the same single-process-per-app reason.
     *
     *  AOSP's setApplicationLocales additionally drives a per-app
     *  configuration change that recreates/onConfigurationChanged's the
     *  calling app's activities so the UI actually re-renders in the new
     *  language (API 33 LocaleManager). Previously nothing dispatched that
     *  here: the value round-tripped correctly but the screen never changed,
     *  which is indistinguishable from a broken language picker to the user.
     *  We mirror it by building a locale-updated Configuration off the
     *  current top activity's Resources and dispatching it exactly the way a
     *  real device-level config change would (Activity.dispatchConfigurationChanged,
     *  which updates Resources, calls onConfigurationChanged, and fans out to
     *  the Application's ComponentCallbacks -- see Activity.java). ActivityHost
     *  (com.macapk.loader, our ActivityThread analogue) is Mac.apk's one-window
     *  "task stack", so its top entry is the whole live Activity stack to
     *  scope this to. */
    public void setApplicationLocales(LocaleList locales) {
        if (locales == null) locales = LocaleList.getEmptyLocaleList();
        synchronized (LocaleManager.class) {
            applicationLocales = locales;
            save(locales);
        }
        Locale primary = locales.size() > 0 ? locales.get(0) : SYSTEM_LOCALE;
        try {
            if (primary != null) Locale.setDefault(primary);
        } catch (Throwable ignored) {}
        try {
            Activity top = com.macapk.loader.ActivityHost.peek();
            if (top != null) {
                android.content.res.Resources res = top.getResources();
                android.content.res.Configuration cur = (res != null) ? res.getConfiguration() : null;
                android.content.res.Configuration next = new android.content.res.Configuration(cur);
                next.setLocale(primary);
                top.dispatchConfigurationChanged(next);
            }
        } catch (Throwable ignored) {}
    }

    /** AOSP: returns the app's selected locales, or the empty list if it
     *  follows the system. */
    public LocaleList getApplicationLocales() {
        return loaded();
    }

    /** AOSP getApplicationLocales(String packageName): system-app overload.
     *  Single-app process, so the package is ignored. */
    public LocaleList getApplicationLocales(String packageName) {
        return getApplicationLocales();
    }
    public void setApplicationLocales(String packageName, LocaleList locales) {
        setApplicationLocales(locales);
    }

    /** AOSP: the device's system locales. Mac.apk has a single locale, so
     *  this mirrors the JVM default list. */
    public LocaleList getSystemLocales() {
        return LocaleList.getDefault();
    }

    // ---- Override LocaleConfig (API 34) ----
    // AOSP lets an app REPLACE the locale list its manifest declared, so a
    // dynamically-delivered language pack can widen the picker without a new
    // manifest. AOSP stores the override in the system's locale service and
    // getOverrideLocaleConfig returns null when none was set. The scope of that
    // store is "this app", and Mac.apk runs exactly one app per process, so
    // holding it here is the same scope -- and it means the setter/getter pair
    // an app uses to check its own override actually round-trips.

    private volatile LocaleConfig mOverrideLocaleConfig;

    /** AOSP setOverrideLocaleConfig(LocaleConfig): "Sets the app's override
     *  LocaleConfig"; passing null CLEARS the override, which is the documented
     *  way to fall back to the manifest one. AOSP additionally rejects a
     *  LocaleConfig whose status is not STATUS_SUCCESS with
     *  IllegalArgumentException -- keep that, an app that hands over an unparsed
     *  config wants to know. */
    public void setOverrideLocaleConfig(LocaleConfig localeConfig) {
        if (localeConfig != null && localeConfig.getStatus() != LocaleConfig.STATUS_SUCCESS) {
            throw new IllegalArgumentException("The status of the LocaleConfig is not "
                    + "STATUS_SUCCESS");
        }
        mOverrideLocaleConfig = localeConfig;
    }

    /** AOSP getOverrideLocaleConfig(): "@return the override LocaleConfig, or
     *  null if it has not been set." */
    public LocaleConfig getOverrideLocaleConfig() { return mOverrideLocaleConfig; }
}
