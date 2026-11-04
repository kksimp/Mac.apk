// API-33 application locales: requested state lives in existing per-file preferences.
// https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/core/java/android/app/LocaleManager.java
package android.app;

import android.os.LocaleList;

import java.util.Locale;

public class LocaleManager {
    private final android.content.Context context;

    public LocaleManager() { this(null); }
    public LocaleManager(android.content.Context context) { this.context = context; }

    private android.content.SharedPreferences preferences() {
        android.content.Context owner = context != null ? context : Application.macapk_get();
        return com.macapk.loader.SharedPrefsImpl.macapkGet(owner, "macapk.application_locales");
    }

    /** Store the requested list; the effective list also includes system fallbacks. */
    public void setApplicationLocales(LocaleList locales) {
        if (locales == null) locales = LocaleList.getEmptyLocaleList();
        android.content.SharedPreferences prefs = preferences();
        synchronized (prefs) {
            if (getApplicationLocales().equals(locales)) return;
            if (!prefs.edit().putString("locales", locales.toLanguageTags()).commit()) {
                throw new IllegalStateException("Unable to persist application locales");
            }
        }
        Runnable apply = () -> {
            Application app = Application.macapk_get();
            if (app == null || prefs != com.macapk.loader.SharedPrefsImpl.macapkGet(
                    app, "macapk.application_locales")) return;
            android.content.res.Configuration next = applyToResources(app.getResourcesDirect());
            app.dispatchConfigurationChanged(new android.content.res.Configuration(next));
            Activity top = com.macapk.loader.ActivityHost.peek();
            if (top != null) com.macapk.loader.DisplayConfig.deliverToActivity(top, next);
            android.view.ViewRootImpl.scheduleLayout();
        };
        android.os.Looper looper = android.os.Looper.getMainLooper();
        if (Thread.currentThread() == looper.getThread()) apply.run();
        else {
            android.os.Handler handler = android.os.Looper.MACAPK_REAL_LOOPER
                ? android.os.Handler.macapkCreateOwnerOnly(looper) : new android.os.Handler(looper);
            handler.post(apply);
        }
    }

    public LocaleList getApplicationLocales() {
        return LocaleList.forLanguageTags(preferences().getString("locales", ""));
    }

    private LocaleList effectiveLocales() {
        java.util.LinkedHashSet<Locale> combined = new java.util.LinkedHashSet<>();
        LocaleList requested = getApplicationLocales();
        LocaleList system = getSystemLocales();
        for (int i = 0; i < requested.size(); i++) combined.add(requested.get(i));
        for (int i = 0; i < system.size(); i++) combined.add(system.get(i));
        return new LocaleList(combined.toArray(new Locale[0]));
    }

    private android.content.res.Configuration applyToResources(android.content.res.Resources res) {
        LocaleList effective = effectiveLocales();
        android.content.res.Configuration next = new android.content.res.Configuration(res.getConfiguration());
        next.setLocales(effective);
        Object index = android.content.res.Resources.macapkGetResourceIndex();
        if (index instanceof com.macapk.loader.ResourceIndex
                && ((com.macapk.loader.ResourceIndex) index).macapkSelectionChangesFor(next)) {
            Object built = com.macapk.loader.Boot.macapkNewResourceIndex(next);
            if (built == null) throw new IllegalStateException("Unable to select application locale resources");
            android.content.res.Resources.macapkSetResourceIndex(built);
        }
        Locale.setDefault(effective.get(0));
        LocaleList.setDefault(effective);
        res.updateConfiguration(next, null);
        return next;
    }

    /** Boot restores preferences before application classes and callbacks run. */
    public static void macapkRestoreApplicationLocales(String packageName, java.io.File dataDir,
                                                       android.content.res.Resources resources) {
        android.content.Context storage = new android.content.ContextWrapper(null) {
            @Override public String getPackageName() { return packageName; }
            @Override public java.io.File getDataDir() { return dataDir; }
            @Override public java.io.File getFilesDir() { return new java.io.File(dataDir, "files"); }
        };
        new LocaleManager(storage).applyToResources(resources);
    }

    /** AOSP getApplicationLocales(String packageName): system-app overload.
     *  Single-app process, so the package is ignored. */
    public LocaleList getApplicationLocales(String packageName) {
        return getApplicationLocales();
    }
    public void setApplicationLocales(String packageName, LocaleList locales) {
        setApplicationLocales(locales);
    }

    /** Host locales are independent of application/process defaults. */
    public LocaleList getSystemLocales() {
        return LocaleList.macapkGetSystemLocales();
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
