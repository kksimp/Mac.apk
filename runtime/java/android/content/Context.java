// android.content.Context -- abstract root of the Android service-locator
// hierarchy. Activities, Services, and Application all extend this. Most
// of the API surface is unimplemented in v2: the apps we boot get their
// "system services" via direct getSystemService(...) calls, and we hand
// back our own thin shims keyed by the well-known service names.
package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class Context {

    // Standard Android service names. Apps look up services by string,
    // and we route these in getSystemService overrides.
    public static final String AUDIO_SERVICE          = "audio";
    public static final String INPUT_SERVICE          = "input";
    public static final String CONNECTIVITY_SERVICE   = "connectivity";
    public static final String INPUT_METHOD_SERVICE   = "input_method";
    public static final String LOCATION_SERVICE       = "location";
    public static final String NOTIFICATION_SERVICE   = "notification";
    public static final String POWER_SERVICE          = "power";
    public static final String SENSOR_SERVICE         = "sensor";
    public static final String VIBRATOR_SERVICE       = "vibrator";
    public static final String WIFI_SERVICE           = "wifi";
    public static final String WINDOW_SERVICE         = "window";
    public static final String DISPLAY_SERVICE        = "display";
    public static final String ACTIVITY_SERVICE       = "activity";
    public static final String TELEPHONY_SERVICE      = "phone";
    public static final String CAMERA_SERVICE         = "camera";
    public static final String BIOMETRIC_SERVICE      = "biometric";
    public static final String FINGERPRINT_SERVICE    = "fingerprint";

    public static final int MODE_PRIVATE             = 0x0000;
    public static final int MODE_WORLD_READABLE      = 0x0001;
    public static final int MODE_WORLD_WRITEABLE     = 0x0002;
    public static final int MODE_APPEND              = 0x8000;
    public static final int MODE_MULTI_PROCESS       = 0x0004;
    // Database open modes consumed by openOrCreateDatabase(name, mode, ...).
    // Values verified against AOSP core/java/android/content/Context.java.
    public static final int MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x0008;
    public static final int MODE_NO_LOCALIZED_COLLATORS     = 0x0010;

    public static final int BIND_AUTO_CREATE         = 0x00000001;
    public static final int BIND_DEBUG_UNBIND        = 0x00000002;
    public static final int BIND_NOT_FOREGROUND      = 0x00000004;

    // ---- The rest of the API-36 public constant surface of Context, from
    // AOSP core/java/android/content/Context.java (android16-release), values
    // transcribed and cross-checked against core/api/current.txt.
    //
    // Compile-time constants, so an app inlines them; present here for
    // reflective readers and because our own getSystemService() has to compare
    // against the same literals. NOTE that declaring a *_SERVICE name here
    // does NOT by itself make getSystemService(name) return an object -- the
    // switch below is the thing that answers, and a name with no
    // implementation still returns null exactly as it did before. That is
    // deliberate: an app testing `getSystemService(X) != null` for a facility
    // we do not have must keep getting null (see debug/levelprobe.sh
    // --service-gate).
    public static final int DEVICE_ID_INVALID = -1;
    public static final int BIND_ABOVE_CLIENT = 0x0008;
    public static final int BIND_ALLOW_OOM_MANAGEMENT = 0x0010;
    public static final int BIND_WAIVE_PRIORITY = 0x0020;
    public static final int BIND_IMPORTANT = 0x0040;
    public static final int BIND_ADJUST_WITH_ACTIVITY = 0x0080;
    public static final int BIND_NOT_PERCEPTIBLE = 0x00000100;
    public static final int BIND_ALLOW_ACTIVITY_STARTS = 0X000000200;
    public static final int BIND_INCLUDE_CAPABILITIES = 0x000001000;
    public static final int BIND_SHARED_ISOLATED_PROCESS = 0x00002000;
    public static final int BIND_PACKAGE_ISOLATED_PROCESS = 1 << 14;
    public static final int BIND_EXTERNAL_SERVICE = 0x80000000;
    public static final long BIND_EXTERNAL_SERVICE_LONG = 1L << 62;
    public static final String KEYSTORE_SERVICE = "keystore";
    public static final String TETHERING_SERVICE = "tethering";
    public static final String TV_AD_SERVICE = "tv_ad";
    public static final String PERSISTENT_DATA_BLOCK_SERVICE = "persistent_data_block";
    public static final String APP_FUNCTION_SERVICE = "app_function";
    public static final String ADVANCED_PROTECTION_SERVICE = "advanced_protection";
    public static final String SATELLITE_SERVICE = "satellite";
    public static final String SECURITY_STATE_SERVICE = "security_state";
    public static final String CONTACT_KEYS_SERVICE = "contact_keys";
    public static final String PROFILING_SERVICE = "profiling";
    public static final String MEDIA_QUALITY_SERVICE = "media_quality";
    public static final int CONTEXT_INCLUDE_CODE = 0x00000001;
    public static final int CONTEXT_IGNORE_SECURITY = 0x00000002;
    public static final int CONTEXT_RESTRICTED = 0x00000004;

    public static final String ALARM_SERVICE         = "alarm";
    public static final String CLIPBOARD_SERVICE     = "clipboard";
    public static final String DOWNLOAD_SERVICE      = "download";
    public static final String SEARCH_SERVICE        = "search";
    public static final String STORAGE_SERVICE       = "storage";
    public static final String AUTOFILL_SERVICE      = "autofill";
    public static final String UI_MODE_SERVICE       = "uimode";
    public static final String USER_SERVICE          = "user";

    public abstract String getPackageName();
    public abstract Resources getResources();
    public abstract AssetManager getAssets();

    /** AOSP Context.getTheme(). Lazy-allocates a Theme tied to this
     *  Context. PreferenceScreen, View, and several androidx classes
     *  call this during construction (via TypedArrayUtils.getAttr)
     *  to resolve theme-referenced attributes. We return the same
     *  Theme on every call so reference-equality holds within the
     *  Context lifetime, matching AOSP behavior. */
    private android.content.res.Resources.Theme macapkTheme;
    /** AOSP ContextImpl.mThemeResource -- the resId last passed to
     *  setTheme(), applied onto macapkTheme the moment a Theme object
     *  exists (immediately in setTheme() if getTheme() already
     *  materialized one, else lazily the first time getTheme() runs).
     *
     *  Previously NOT stored at all: setTheme() below was a bare
     *  no-op, so any Context whose setTheme()/getTheme() resolve to
     *  THIS class -- Application, Service, a bare ContextWrapper, or
     *  createConfigurationContext's ConfigOverrideContext -- silently
     *  dropped every setTheme() call, and obtainStyledAttributes /
     *  getTheme() callers on those Contexts always saw an unstyled
     *  Theme no matter what the app asked for.
     *
     *  android.view.ContextThemeWrapper (which Activity extends)
     *  already stores its own resId in a `macapkThemeResource` field
     *  of its own -- see that class's header comment -- so this brings
     *  the base Context class to the same storage contract for the
     *  Contexts that are NOT a ContextThemeWrapper. It does NOT touch
     *  ContextThemeWrapper's separate MACAPK_VIEW_THEME-gated
     *  View-level theme cascade (a different, fleet-wide-impact
     *  RESOLUTION mechanism, left untouched here). */
    private int macapkThemeResource;
    // Cached singletons for hardware system services. SensorManager holds
    // a delivery timer + listener registrations, so it must be a stable
    // per-Context instance (AOSP also caches it via SystemServiceRegistry).
    private android.hardware.SensorManager macapkSensorManager;
    /** Cached facade; LocaleManager shares stored state through SharedPrefsImpl. */
    private android.app.LocaleManager macapkLocaleManager;
    private android.location.LocationManager macapkLocationManager;
    public android.content.res.Resources.Theme getTheme() {
        if (macapkTheme == null) {
            Resources r = getResources();
            if (r == null) {
                // v1.0.110: Theme is now a non-static inner class so it
                // MUST have an enclosing Resources. Materialize one with
                // empty asset paths so the Theme has a valid back-ref
                // (its getResources() lookup must not return null;
                // Glide DrawableDecoderCompat.loadDrawableV7 needs it).
                r = new Resources(null, null, null);
            }
            // AOSP ContextImpl.getTheme (android11-release ContextImpl.java:
            // 378-390) selects a DEFAULT theme when setTheme() was never
            // called, BEFORE materializing:
            //     mThemeResource = Resources.selectDefaultTheme(mThemeResource,
            //             getOuterContext().getApplicationInfo().targetSdkVersion);
            //     initializeTheme();
            // This is why, on real Android, an Application/Service context's
            // theme is the system default (Theme.DeviceDefault.* by target
            // SDK) and NOT the <application android:theme> -- the manifest
            // theme reaches an Activity via ActivityThread's setTheme call,
            // never a plain ContextImpl. The fwprobe application anchor is
            // the measured case: the emulator answers ok=0 for every app
            // attr on Application.getTheme(); we answered the manifest
            // theme's colours. Self-scoping: applyStyle can only push a
            // style it can NAME, so where the framework table is absent
            // (a build without tools/framework-res.apk) the cascade stays
            // empty and the legacy manifest-tier fallback keeps serving
            // exactly as before.
            if (macapkThemeResource == 0) {
                macapkThemeResource = macapkSelectDefaultTheme();
            }
            macapkTheme = r.newTheme();
            // AOSP ContextImpl.initializeTheme(): a Theme materialized
            // after setTheme() already recorded a resId must come up
            // carrying that style, not blank.
            if (macapkThemeResource != 0) {
                try { macapkTheme.applyStyle(macapkThemeResource, true); }
                catch (Throwable ignored) {}
            }
        }
        return macapkTheme;
    }

    /** AOSP Resources.selectDefaultTheme -> selectSystemTheme
     *  (android11-release Resources.java:192-216):
     *      < HONEYCOMB(11)          -> R.style.Theme
     *      < ICE_CREAM_SANDWICH(14) -> R.style.Theme_Holo
     *      < N(24)                  -> R.style.Theme_DeviceDefault
     *      else                     -> R.style.Theme_DeviceDefault_Light_DarkActionBar
     *  Ids from core/res/res/values/public.xml (frozen public ids):
     *      Theme                                   0x01030005
     *      Theme.Holo                              0x0103006b
     *      Theme.DeviceDefault                     0x01030128
     *      Theme.DeviceDefault.Light.DarkActionBar 0x0103013f
     *  An UNRESOLVED targetSdk means our ApplicationInfo stub answered (real
     *  Android always populates it); treat as modern, matching Boot.java's
     *  default application-theme selection for a theme-less manifest. A REAL
     *  0 (no <uses-sdk>, Application.macapkTargetSdkKnown) is below 11 and
     *  takes R.style.Theme, as AOSP's selectSystemTheme does. */
    private int macapkSelectDefaultTheme() {
        int targetSdk = 0;
        try {
            android.content.pm.ApplicationInfo ai = getApplicationInfo();
            if (ai != null) targetSdk = ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        if (!android.app.Application.macapkTargetSdkKnown(targetSdk)) return 0x0103013f;
        if (targetSdk < 11)  return 0x01030005;
        if (targetSdk < 14)  return 0x0103006b;
        if (targetSdk < 24)  return 0x01030128;
        return 0x0103013f;
    }
    /** AOSP ContextImpl.setTheme(int) (frameworks/base/core/java/android/
     *  app/ContextImpl.java, aosp-mirror platform_frameworks_base@main,
     *  fetched 2026-07-30):
     *
     *      public void setTheme(int resId) {
     *          synchronized (mThemeLock) {
     *              if (mThemeResource != resId) {
     *                  mThemeResource = resId;
     *                  initializeTheme();
     *              }
     *          }
     *      }
     *      private void initializeTheme() {
     *          if (mTheme == null) { mTheme = mResources.newTheme(); }
     *          mTheme.applyStyle(mThemeResource, true);
     *      }
     *
     *  Ported as store-then-reapply-if-already-materialized (no
     *  synchronized block: every other lazily-cached field on this
     *  class, e.g. macapkTheme/macapkSensorManager above, is already
     *  unsynchronized single-writer-per-instance, so adding locking to
     *  only this pair would be a style outlier, not a real fix for a
     *  race nothing here otherwise guards against).
     *
     *  Deliberately NOT ported: ContextImpl.getTheme()'s
     *  Resources.selectDefaultTheme() step, which picks a fallback
     *  theme by target-SDK when setTheme() was never called. That is a
     *  default-THEME-SELECTION behavior, separate from the store-and-
     *  apply contract this fix targets, and out of scope here. */
    public void setTheme(int resId) {
        if (macapkThemeResource != resId) {
            macapkThemeResource = resId;
            if (macapkTheme != null) {
                try { macapkTheme.applyStyle(resId, true); }
                catch (Throwable ignored) {}
            }
        }
    }

    /** API-24+ direct-boot storage. AOSP semantics: an app can register
     *  for device-protected storage (encrypted storage available
     *  before the user unlocks the device) by calling
     *  createDeviceProtectedStorageContext() and storing data there.
     *  We don't have direct-boot semantics; reporting "not device-
     *  protected" makes apps default to their normal storage path.
     *  HCR 1.68's androidx.startup chain calls isDeviceProtectedStorage()
     *  during init -- NoSuchMethodError otherwise. */
    public boolean isDeviceProtectedStorage()    { return false; }
    public Context createDeviceProtectedStorageContext() { return this; }
    /** v1.0.100: AOSP isRestricted() returns true when the Context is the
     *  parsed Context from a LayoutInflater factory (Resources-only, no app
     *  context). F-Droid's StatusBanner constructor checks this so it can
     *  skip lifecycle-only work during preview inflation. We are always a
     *  full Context, so return false. */
    public boolean isRestricted() { return false; }

    /** AOSP Context.isUiContext(): true for an Activity, a window context
     *  (createWindowContext) or a system/system-UI context; false for a
     *  display context or a non-UI context. ContextImpl.isUiContext()
     *  switches on the mContextType tag stamped at construction time.
     *  Mac.apk carries no such tag, and the only Context in the tree that
     *  owns a real window surface is an Activity, so derive the answer from
     *  the type. The loop mirrors AOSP's ContextWrapper, which delegates
     *  isUiContext() to its base context, so a ContextThemeWrapper /
     *  createConfigurationContext wrapper around an Activity still reports
     *  true. The depth bound keeps a pathological wrapper cycle from
     *  spinning. */
    public boolean isUiContext() {
        Context c = this;
        for (int depth = 0; c != null && depth < 16; depth++) {
            if (c instanceof android.app.Activity) return true;
            if (!(c instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) c).getBaseContext();
            if (base == c) break;
            c = base;
        }
        return false;
    }

    /** AOSP Context.getDisplay(): the Display this Context is associated
     *  with. ContextImpl.getDisplay() throws UnsupportedOperationException
     *  when the Context is not display-associated and otherwise hands back
     *  the DisplayManager's display. Mac.apk hosts exactly one logical
     *  display and every Context in the tree ultimately belongs to it, so
     *  there is no non-associated case to distinguish; returning the default
     *  display is the honest answer and throwing would only add crashes
     *  without adding fidelity on a single-display host. */
    public android.view.Display getDisplay() {
        Object dm = getSystemService(DISPLAY_SERVICE);
        if (dm instanceof android.hardware.display.DisplayManager) {
            return ((android.hardware.display.DisplayManager) dm)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        }
        return null;
    }

    /** API-17+ helpers for theme-/config-/display-scoped Context.
     *  androidx.appcompat.AppCompatDelegate.attachBaseContext2 calls
     *  createConfigurationContext during night-mode override.
     *
     *  CRITICAL: must return a Context whose `getResources()` does
     *  NOT recurse back into the CALLER's ContextThemeWrapper. Pattern
     *  observed: AppCompat's ContextThemeWrapper.getResourcesInternal
     *  does `createConfigurationContext(cfg).getResources()` and if
     *  that returns Resources via a chain that re-enters
     *  getResourcesInternal -> infinite recursion -> StackOverflowError.
     *
     *  We break the chain by returning a ConfigOverrideContext that
     *  fetches Resources directly from the Application's prepared
     *  ResourceIndex, never delegating to the original caller. */
    public Context createConfigurationContext(android.content.res.Configuration overrideConfiguration) {
        return new ConfigOverrideContext(this, overrideConfiguration);
    }

    /** Internal: the Context returned by createConfigurationContext.
     *  A ContextWrapper around the origin (v1.0.345) so that EVERY
     *  Context method -- getPackageManager, getSystemService,
     *  getContentResolver, startActivity, ... -- delegates to the real
     *  context, the way AOSP's createConfigurationContext returns a
     *  fully functional ContextImpl. It used to extend Context
     *  directly with an 8-method subset, so anything else bottomed out
     *  in base-Context defaults: Plague Inc's MainActivity wraps its
     *  base context through createConfigurationContext (locale
     *  override) and appcompat's attachBaseContext2 then probed
     *  getPackageManager() on the wrapper -> null -> NPE ->
     *  MainActivity never started (black splash). Only the two
     *  overrides below intentionally do NOT delegate: getResources
     *  breaks AppCompat's ContextThemeWrapper.getResourcesInternal
     *  recursion (see createConfigurationContext doc above), and
     *  getApplicationContext pins the global Application. */
    private static final class ConfigOverrideContext extends ContextWrapper {
        // Stored for fidelity with AOSP (the override config shapes the
        // returned context's Resources); Mac.apk's single-configuration
        // Resources model has no consumer for it yet.
        private final android.content.res.Configuration overrideConfig;
        ConfigOverrideContext(Context origin, android.content.res.Configuration cfg) {
            super(origin);
            this.overrideConfig = cfg;
        }
        @Override public Resources getResources() {
            // Skip the chain. Go straight to Application.
            android.app.Application app = android.app.Application.macapk_get();
            return app != null ? app.getResourcesDirect()
                               : getBaseContext().getResources();
        }
        @Override public Context getApplicationContext() {
            return android.app.Application.macapk_get();
        }
    }

    /** Subclass hook (Application overrides) -- returns the Resources
     *  field directly without invoking getResources() (which might be
     *  overridden to chain through a theme wrapper). Default delegates
     *  to getResources(). */
    public Resources getResourcesDirect() { return getResources(); }
    public Context createPackageContextAsUser(String packageName, int flags,
                                                android.os.UserHandle user)
            throws android.content.pm.PackageManager.NameNotFoundException { return this; }
    public Context createContextForSplit(String splitName)
            throws android.content.pm.PackageManager.NameNotFoundException { return this; }
    public Context createAttributionContext(String attributionTag) { return this; }

    /** AOSP ContextImpl.getOpPackageName() is
     *  `mAttributionSource.getPackageName()` -- the package name AppOps
     *  attributes this Context's operations to. A normal app Context's
     *  attribution source just wraps the app's own package, which is the
     *  only case Mac.apk has, so this is getPackageName(). Anything built
     *  against API 30+ (AppOps-aware AndroidX / Compose / media code) calls
     *  it during construction, so its absence was a NoSuchMethodError.
     *
     *  getAttributionTag() is null for an untagged Context, matching both
     *  AOSP's Context base implementation and our own reality:
     *  createAttributionContext() above is still a pass-through stub, so no
     *  Context in this tree ever carries a tag. */
    public String getOpPackageName()  { return getPackageName(); }
    public String getAttributionTag() { return null; }
    public Context createDisplayContext(android.view.Display display) { return this; }
    public Context createWindowContext(int type, android.os.Bundle options) { return this; }
    /** AOSP Context.getSharedPreferencesPath(name) -> `<prefsDir>/<name>.xml`, where
     *  ContextImpl's prefsDir is `<dataDir>/shared_prefs` and is created on demand
     *  (`getPreferencesDir()` calls `ensurePrivateDirExists`).
     *
     *  ⚠ HISTORY, because the reasoning still matters: this used to anchor on
     *  `getFilesDir()` rather than `getDataDir()`, deliberately, because that was where
     *  `com.macapk.loader.SharedPrefsImpl` ACTUALLY wrote preferences. Reporting AOSP's
     *  nominal path while storing somewhere else would make this getter disagree with
     *  reality, and an app that probes `getSharedPreferencesPath(name).exists()` before
     *  migrating (Clock does exactly that) would be told "no prefs" forever and
     *  re-migrate on every launch. That constraint was real and the old choice was the
     *  right one GIVEN the storage.
     *
     *  v1.0.1061 fixed the storage instead: SharedPrefsImpl now writes
     *  `<dataDir>/shared_prefs/<name>.xml` in Android's own XML, migrating any legacy
     *  file on first access. So this getter can finally report AOSP's path AND agree
     *  with reality -- the two stopped being in tension. Keep them in step: if the
     *  storage ever moves again, this getter moves with it. */
    public File getSharedPreferencesPath(String name) {
        File dir = new File(getDataDir(), "shared_prefs");
        try { dir.mkdirs(); } catch (Throwable ignored) {}
        return new File(dir, name + ".xml");
    }

    /** AOSP ContextImpl.moveSharedPreferencesFrom / moveDatabaseFrom.
     *
     *  These returned a flat `false`, which is the OPPOSITE of AOSP for the
     *  case that always applies here. ContextImpl:
     *
     *      public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
     *          final File source = sourceContext.getSharedPreferencesPath(name);
     *          final File target = getSharedPreferencesPath(name);
     *          final int res = moveFiles(source.getParentFile(),
     *                  target.getParentFile(), source.getName());
     *          ...
     *          return res != -1;
     *      }
     *      private static int moveFiles(File sourceDir, File targetDir, String prefix) {
     *          final File[] sourceFiles = FileUtils.listFilesOrEmpty(sourceDir, ...);
     *          int res = 0;
     *          for (File sourceFile : sourceFiles) { ...copy...; res++; }   // IOException -> res = -1
     *          return res;
     *      }
     *
     *  (aosp-mirror/platform_frameworks_base main,
     *   core/java/android/app/ContextImpl.java). So "nothing to move" is
     *  res == 0 -> TRUE. Only a real I/O failure is false. Since our
     *  createDeviceProtectedStorageContext() returns `this`, source and target
     *  are the same directory for every app, i.e. the no-op-success case is
     *  the ONLY case -- and we were reporting failure for it.
     *
     *  Measured on Clock (com.best.deskclock 2035, 2026-07-29): its
     *  `DeskClockApplication.a(Context)` does
     *      Context de = ContextCompat.createDeviceProtectedStorageContext(ctx);
     *      if (!new File(<de shared_prefs>/<pkg>_preferences.xml).exists()
     *              && !de.moveSharedPreferencesFrom(ctx, name)) {
     *          LogUtils.wtf("Failed to migrate shared preferences");
     *      }
     *  and that logger's own gate re-enters DeskClockApplication.a() to read
     *  `key_display_debug_settings` -- so `false` produced UNBOUNDED
     *  RECURSION: DeskClockApplication.a -> ze0.J -> af0.i -> ... ->
     *  StackOverflowError thrown out of Application.onCreate. The app's global
     *  state was therefore never initialised, DeskClock.onResume's fragment
     *  transaction then failed with "FragmentManager has not been attached to
     *  a host", and ContentFrameLayout stayed EMPTY (drawn = 0.2%).
     *
     *  Implemented faithfully rather than flipped to a blind `true`: a real
     *  cross-directory migration (an app that does have separate DE storage
     *  paths, or that migrates between two names) now actually moves the
     *  files. */
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        if (sourceContext == null || name == null) return false;
        try {
            File source = sourceContext.getSharedPreferencesPath(name);
            File target = getSharedPreferencesPath(name);
            return macapkMoveFiles(source.getParentFile(), target.getParentFile(),
                                   source.getName()) != -1;
        } catch (Throwable t) { return false; }
    }
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        if (sourceContext == null || name == null) return false;
        try {
            File source = sourceContext.getDatabasePath(name);
            File target = getDatabasePath(name);
            return macapkMoveFiles(source.getParentFile(), target.getParentFile(),
                                   source.getName()) != -1;
        } catch (Throwable t) { return false; }
    }
    /** ContextImpl.moveFiles: move every file in sourceDir whose name starts
     *  with `prefix` into targetDir. Returns the number moved, or -1 if any
     *  move failed. 0 (nothing to move) is SUCCESS, per AOSP's `res != -1`. */
    private static int macapkMoveFiles(File sourceDir, File targetDir, final String prefix) {
        if (sourceDir == null || targetDir == null || prefix == null) return -1;
        // Same directory: there is by definition nothing to migrate, and
        // copying a file onto itself would truncate it.
        try {
            if (sourceDir.getCanonicalFile().equals(targetDir.getCanonicalFile())) return 0;
        } catch (java.io.IOException ignored) {}
        File[] sourceFiles = sourceDir.listFiles((dir, n) -> n.startsWith(prefix));
        if (sourceFiles == null) return 0;   // AOSP: listFilesOrEmpty -> loop skipped -> 0
        int res = 0;
        for (File sourceFile : sourceFiles) {
            File targetFile = new File(targetDir, sourceFile.getName());
            try {
                targetDir.mkdirs();
                java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!sourceFile.delete()) {
                    throw new java.io.IOException("Failed to clean up " + sourceFile);
                }
                if (res != -1) res++;
            } catch (java.io.IOException e) {
                com.macapk.loader.NativeLog.w("Context",
                    "moveFiles: failed to migrate " + sourceFile + ": " + e);
                res = -1;
            }
        }
        return res;
    }
    /** AOSP ContextImpl.deleteDatabase (android-11.0.0_r48
     *  core/java/android/app/ContextImpl.java:893-900):
     *
     *      public boolean deleteDatabase(String name) {
     *          try {
     *              File f = getDatabasePath(name);
     *              return SQLiteDatabase.deleteDatabase(f);
     *          } catch (Exception e) { }
     *          return false;
     *      }
     *
     *  This was a flat `return false`, which lies in BOTH directions at once:
     *  the file stays on disk AND the caller is told the delete failed. The
     *  victims are corrupt-database recovery paths -- Room's
     *  fallbackToDestructiveMigration and every hand-rolled
     *  `if (!ctx.deleteDatabase(n)) giveUp()` -- which then reopen the same
     *  broken file on every launch, forever, because the one API that could
     *  clear it always reports failure.
     *
     *  Pure delegation: SQLiteDatabase.deleteDatabase(File) is already a
     *  faithful port and removes the -journal / -wal / -shm / -mj sidecars
     *  along with the main file. The exception swallowing is AOSP's own. */
    public boolean deleteDatabase(String name) {
        try {
            return android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name));
        } catch (Exception e) {
            return false;
        }
    }

    /** URI permission API (API-1+). grant stays a no-op: this app granting to
     *  another package has no one to reach (one app), and a grant to ITSELF
     *  adds nothing it does not already hold (UGMS checkGrantUriPermission
     *  refuses an un-held Uri). The grants this app RECEIVES are recorded by
     *  android.content.MacApkUriGrants (a picker result, a provider's narrow
     *  grant). */
    public void grantUriPermission(String toPackage, android.net.Uri uri, int modeFlags) {}

    /** UGMS.revokeUriPermissionLocked's CALLER-side branch (android14-release
     *  :838-861, quoted in MacApkUriGrants.java): reachable now that
     *  HostDocumentsProvider genuinely stands in for another package
     *  (com.android.externalstorage), so this app never holds ITS permissions
     *  (MANAGE_DOCUMENTS) and always takes the "revoke any ownerless URI
     *  permissions" path -- MacApkUriGrants.revokeNonOwned, which drops only
     *  the GLOBAL half (a provider's narrow grant), leaving a picker-result
     *  (owned) grant alone. Was a pure no-op labelled "one app, nothing to
     *  reach", which was right only for a provider this app itself hosts (the
     *  ENFORCE=0 and same-app cases below stay a no-op for exactly that
     *  reason). Review issue 2 (2026-09-28). */
    public void revokeUriPermission(android.net.Uri uri, int modeFlags) {
        if (MacApkUriGrants.ENFORCE && macapkUriProviderIsAnotherPackage(uri)) {
            MacApkUriGrants.revokeNonOwned(uri, modeFlags);
        }
    }
    /** The toPackage form: on a device this also walks every OTHER app's
     *  grants naming that uid's records (:864-900, not modelled -- one app,
     *  nothing else to walk); the branch above fires (as it does for any
     *  target) only for a `toPackage` that names this app. */
    public void revokeUriPermission(String toPackage, android.net.Uri uri, int modeFlags) {
        if (toPackage != null && !toPackage.equals(getPackageName())) return;
        revokeUriPermission(uri, modeFlags);
    }

    /** {@hide} Is `uri`'s provider one Mac.apk hosts on behalf of ANOTHER
     *  package (ContentProvider.macapkIsAnotherPackage), e.g. HostDocumentsProvider
     *  standing in for com.android.externalstorage? Unregistered / unresolvable
     *  authorities answer false (nothing to revoke against). */
    private static boolean macapkUriProviderIsAnotherPackage(android.net.Uri uri) {
        if (uri == null) return false;
        ContentProvider p = ContentProviderRegistry.forUri(uri);
        return p != null && p.macapkIsAnotherPackage();
    }

    /** The checks (v1.0.1657, MACAPK_URI_GRANT_ENFORCE, default ON) answer from the
     *  grant records as a device does (frameworks/base android14-release
     *  core/java/android/app/ContextImpl.java): checkUriPermission asks
     *  ActivityManagerService.checkUriPermission (:2401-2409 -> AMS :6362-6377 ->
     *  UGMS.checkUriPermissionLocked), which knows only GRANTS -- not the app's
     *  own providers, not its permissions -- so a Uri this app was never granted
     *  is DENIED, even one of its own. checkCallingUriPermission is DENIED when
     *  the caller is this process (:2440-2447; Binder.getCallingPid() is
     *  myPid() here). =0: every check answers GRANTED and enforce* never
     *  throws, as before. */
    public int checkUriPermission(android.net.Uri uri, int pid, int uid, int modeFlags) {
        if (!MacApkUriGrants.ENFORCE) return 0;
        return MacApkUriGrants.checkUriPermission(uri, pid, uid, modeFlags);
    }
    /** ContextImpl :2474-2497. */
    public int checkUriPermission(android.net.Uri uri, String readPermission, String writePermission, int pid, int uid, int modeFlags) {
        if (!MacApkUriGrants.ENFORCE) return 0;
        if ((modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            if (readPermission == null
                    || checkPermission(readPermission, pid, uid)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
        }
        if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (writePermission == null
                    || checkPermission(writePermission, pid, uid)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
        }
        return uri != null ? checkUriPermission(uri, pid, uid, modeFlags)
                : android.content.pm.PackageManager.PERMISSION_DENIED;
    }
    /** ContextImpl :2440-2447. */
    public int checkCallingUriPermission(android.net.Uri uri, int modeFlags) {
        if (!MacApkUriGrants.ENFORCE) return 0;
        int pid = android.os.Binder.getCallingPid();
        if (pid != android.os.Process.myPid()) {
            return checkUriPermission(uri, pid, android.os.Binder.getCallingUid(), modeFlags);
        }
        return android.content.pm.PackageManager.PERMISSION_DENIED;
    }
    /** ContextImpl :2462-2465. */
    public int checkCallingOrSelfUriPermission(android.net.Uri uri, int modeFlags) {
        if (!MacApkUriGrants.ENFORCE) return 0;
        return checkUriPermission(uri, android.os.Binder.getCallingPid(),
                android.os.Binder.getCallingUid(), modeFlags);
    }
    /** ContextImpl :2539-2575 over enforceForUri :2522-2536. */
    public void enforceUriPermission(android.net.Uri uri, int pid, int uid, int modeFlags, String message) {
        if (!MacApkUriGrants.ENFORCE) return;
        macapkEnforceForUri(modeFlags, checkUriPermission(uri, pid, uid, modeFlags), false, uid, uri, message);
    }
    public void enforceUriPermission(android.net.Uri uri, String readPermission, String writePermission, int pid, int uid, int modeFlags, String message) {
        if (!MacApkUriGrants.ENFORCE) return;
        macapkEnforceForUri(modeFlags, checkUriPermission(uri, readPermission, writePermission, pid, uid, modeFlags),
                false, uid, uri, message);
    }
    public void enforceCallingUriPermission(android.net.Uri uri, int modeFlags, String message) {
        if (!MacApkUriGrants.ENFORCE) return;
        macapkEnforceForUri(modeFlags, checkCallingUriPermission(uri, modeFlags), false,
                android.os.Binder.getCallingUid(), uri, message);
    }
    public void enforceCallingOrSelfUriPermission(android.net.Uri uri, int modeFlags, String message) {
        if (!MacApkUriGrants.ENFORCE) return;
        macapkEnforceForUri(modeFlags, checkCallingOrSelfUriPermission(uri, modeFlags), true,
                android.os.Binder.getCallingUid(), uri, message);
    }
    /** ContextImpl.enforceForUri :2522-2536 + uriModeFlagToString :2499-2520. */
    private static void macapkEnforceForUri(int modeFlags, int resultOfCheck, boolean selfToo,
                                            int uid, android.net.Uri uri, String message) {
        if (resultOfCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            StringBuilder b = new StringBuilder();
            if ((modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) b.append("read and ");
            if ((modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) b.append("write and ");
            if ((modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) b.append("persistable and ");
            if ((modeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0) b.append("prefix and ");
            if (b.length() <= 5) {
                throw new IllegalArgumentException("Unknown permission mode flags: " + modeFlags);
            }
            b.setLength(b.length() - 5);
            throw new SecurityException(
                    (message != null ? (message + ": ") : "")
                    + (selfToo ? "Neither user " + uid + " nor current process has "
                               : "User " + uid + " does not have ")
                    + b + " permission on " + uri + ".");
        }
    }

    // ---- API-30+/34+ BULK Uri-permission checks. AOSP's ContextImpl asks the
    // ActivityManager about a whole list in one round trip and returns one
    // result per Uri, in order. Each element gets the answer the single-Uri
    // form above gives (the grant records, or GRANTED with
    // MACAPK_URI_GRANT_ENFORCE=0) -- built by CALLING it, so the bulk and
    // single forms cannot drift apart.
    public int[] checkUriPermissions(java.util.List<android.net.Uri> uris, int pid, int uid,
                                     int modeFlags) {
        int n = (uris == null) ? 0 : uris.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = checkUriPermission(uris.get(i), pid, uid, modeFlags);
        }
        return out;
    }
    public int[] checkCallingUriPermissions(java.util.List<android.net.Uri> uris, int modeFlags) {
        int n = (uris == null) ? 0 : uris.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = checkCallingUriPermission(uris.get(i), modeFlags);
        }
        return out;
    }
    public int[] checkCallingOrSelfUriPermissions(java.util.List<android.net.Uri> uris,
                                                  int modeFlags) {
        int n = (uris == null) ? 0 : uris.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = checkCallingOrSelfUriPermission(uris.get(i), modeFlags);
        }
        return out;
    }

    /** AOSP API-35 -- "Determine whether a particular process and uid has been
     *  granted permission to access a specific content URI ... unlike
     *  checkUriPermission, this requires the FULL permission (both read and
     *  write) rather than either." Same reasoning as the checks above. */
    public int checkContentUriPermissionFull(android.net.Uri uri, int pid, int uid,
                                             int modeFlags) {
        return checkUriPermission(uri, pid, uid, modeFlags);
    }
    public boolean deleteSharedPreferences(String name) { return false; }

    /** API-21+ user-handle accessors. ProcessLifecycleInitializer
     *  reads getUser() to key process-lifecycle state by user. */
    public android.os.UserHandle getUser()       { return android.os.UserHandle.SYSTEM; }
    public int                    getUserId()    { return 0; }

    /** AOSP Context.obtainStyledAttributes -- four overloads. Returns
     *  a TypedArray for the given attribute set. View / Preference
     *  constructors call these to extract themed attribute values.
     *  Our TypedArray is a stub that returns defaults for every
     *  getter; that's enough to let constructors complete and
     *  setContentView proceed. Without these, every Preference and
     *  View subclass throws NoSuchMethodError in its constructor. */
    public android.content.res.TypedArray obtainStyledAttributes(int[] attrs) {
        // AOSP Context.obtainStyledAttributes(int[]) delegates to the THEME
        // (getTheme().obtainStyledAttributes(attrs)); this was the last of the
        // four overloads still building a theme-less TypedArray -- the other
        // three already delegate. MEASURED INERT on its own (v1.0.886), same
        // reasoning as Resources.Theme.obtainStyledAttributes(int[]).
        try {
            android.content.res.Resources.Theme t = getTheme();
            if (t != null) return t.obtainStyledAttributes(attrs);
        } catch (Throwable ignored) {}
        return new android.content.res.TypedArray(getResources(), attrs);
    }
    /** AOSP ContextThemeWrapper/Context: this overload delegates straight to
     *  `getTheme().obtainStyledAttributes(resid, attrs)` -- the STYLE named by
     *  resid supplies the values. We were returning an all-defaults TypedArray
     *  and dropping the style entirely; route it to the Theme, which resolves
     *  the style and binds its items. See the Theme-side comment for the
     *  measured failure this fixes (Anuto TD's every-sprite-id-is-0). */
    public android.content.res.TypedArray obtainStyledAttributes(int resId, int[] attrs) {
        try {
            android.content.res.Resources.Theme t = getTheme();
            if (t != null) return t.obtainStyledAttributes(resId, attrs);
        } catch (Throwable ignored) {}
        return new android.content.res.TypedArray(getResources(), attrs);
    }
    public android.content.res.TypedArray obtainStyledAttributes(
            android.util.AttributeSet set, int[] attrs) {
        // lane layout, 2026-08-01: route through the Theme like AOSP does for
        // ALL FOUR overloads (this one delegates to
        // getTheme().obtainStyledAttributes(set, attrs, 0, 0)). With a real
        // AttributeSet and defStyle == 0 the Theme's plain-set default path
        // constructs the same TypedArray as before PLUS the Theme reference,
        // so `?attr` values read through it (menu `android:icon` vectors via
        // appcompat's SupportMenuInflater, DrawerLayout's layout_gravity read)
        // resolve against the runtime theme cascade instead of only the
        // static application theme. The null-Resources hazard documented in
        // docs/attr-theme-threading_2026-07-31.md ("Explicitly NOT done")
        // applies to the no-AttributeSet int[]-only overload above, NOT here.
        try {
            android.content.res.Resources.Theme t = getTheme();
            if (t != null) return t.obtainStyledAttributes(set, attrs, 0, 0);
        } catch (Throwable ignored) {}
        return new android.content.res.TypedArray(getResources(), attrs, set);
    }
    public android.content.res.TypedArray obtainStyledAttributes(
            android.util.AttributeSet set, int[] attrs,
            int defStyleAttr, int defStyleRes) {
        // AOSP routes this through the Context's Theme so defStyleAttr/defStyleRes
        // resolve against the theme's applied-style cascade. We did NOT before --
        // we returned a plain TypedArray(set), dropping the defStyle path entirely
        // (so a programmatically-styled view like AppCompat's OverflowMenuButton,
        // `super(ctx, null, R.attr.actionOverflowButtonStyle)`, never read its
        // style's android:src and the toolbar overflow icon was blank). Delegate
        // to the Theme, whose obtainStyledAttributes resolves the defStyle style
        // and binds its values. For set != null / defStyle == 0 the Theme returns
        // the same TypedArray(set) / empty TypedArray as before.
        try {
            android.content.res.Resources.Theme t = getTheme();
            if (t != null) return t.obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes);
            macapkTraceThemelessOsa(defStyleAttr, null);
        } catch (Throwable t) {
            macapkTraceThemelessOsa(defStyleAttr, t);
        }
        return new android.content.res.TypedArray(getResources(), attrs, set);
    }
    /** Diagnostic (MACAPK_TRACE_DEFSTYLE, zero cost unset): the 4-arg
     *  obtainStyledAttributes fell back to the THEME-LESS TypedArray --
     *  meaning every defStyleAttr-delivered token for this widget silently
     *  vanished. Names the context class (an app-side ContextThemeWrapper
     *  whose getTheme() throws lands here) and the cause. */
    private void macapkTraceThemelessOsa(int defStyleAttr, Throwable cause) {
        if (defStyleAttr == 0) return;
        if (System.getenv("MACAPK_TRACE_DEFSTYLE") == null) return;
        try {
            com.macapk.loader.NativeLog.i("Mac.apk.Context",
                "oSA4 THEMELESS ctx=" + getClass().getName()
                + " dsa=0x" + Integer.toHexString(defStyleAttr)
                + " cause=" + (cause == null ? "getTheme()==null" : cause.toString()));
        } catch (Throwable ignored) {}
    }

    // v1.0.95: API 21+ Context.getDrawable / getColor convenience.
    // Material widgets and androidx ViewCompat delegate to these in
    // their <init> chains; without them the BottomNavigationView
    // instantiation chain falls back to placeholder. Implementation
    // forwards to Resources -- the same observable behavior real
    // Android has.
    // docs/attr-theme-threading_2026-07-31.md: AOSP's real bodies (fetched
    // 2026-07-31, aosp-mirror/platform_frameworks_base main,
    // core/java/android/content/Context.java) are
    //     getResources().getDrawable(id, getTheme());
    //     getResources().getColor(id, getTheme());
    //     getResources().getColorStateList(id, getTheme());
    // -- i.e. all three thread getTheme() through. We previously called the
    // theme-LESS Resources overload, silently discarding whichever Theme
    // this Context (an Activity, or a per-View ContextThemeWrapper) actually
    // carries. getDrawable(id, theme) is the one with a real consumer today
    // (the XML-drawable ?attr/ chain); getColor/getColorStateList accept the
    // theme argument but do not yet consult it (no @color/<selector> XML with
    // ?attr/ resolves through those paths yet), so threading it here is
    // forward-compatible and changes no observable behavior today.
    public android.graphics.drawable.Drawable getDrawable(int id) {
        android.content.res.Resources r = getResources();
        return r == null ? null : r.getDrawable(id, getTheme());
    }
    public int getColor(int id) {
        android.content.res.Resources r = getResources();
        return r == null ? 0 : r.getColor(id, getTheme());
    }
    public android.content.res.ColorStateList getColorStateList(int id) {
        android.content.res.Resources r = getResources();
        return r == null ? null : r.getColorStateList(id, getTheme());
    }

    public abstract Context getApplicationContext();
    public abstract ClassLoader getClassLoader();
    public abstract File getFilesDir();
    public abstract File getCacheDir();
    public abstract File getDir(String name, int mode);
    /** API-21+ directory that the platform auto-backup skips. AOSP
     *  ContextImpl (android-11.0.0_r48 core/java/android/app/ContextImpl.java
     *  :739-746):
     *
     *      public File getNoBackupFilesDir() {
     *          synchronized (mSync) {
     *              if (mNoBackupFilesDir == null) {
     *                  mNoBackupFilesDir = new File(getDataDir(), "no_backup");
     *              }
     *              return ensurePrivateDirExists(mNoBackupFilesDir);
     *          }
     *      }
     *
     *  -- a SIBLING of `files`, not a child of it, exactly like `cache`
     *  (:780-788) and `code_cache` (:807-809). We returned
     *  <filesDir>/no_backup, and the comment that used to sit here misread the
     *  AOSP line as "same parent as getFilesDir()" when it says "same parent
     *  AS files", i.e. getDataDir(). The path SHAPE is the part that matters
     *  and the part we had wrong: Firebase IID and androidx.work compose
     *  deeper paths under this directory, and an app that computes
     *  <dataDir>/no_backup itself (or reads a path a backup manifest wrote)
     *  disagreed with what we handed it.
     *
     *  ⚠ LAYOUT CHANGE. Anything an app already wrote under
     *  <filesDir>/no_backup stays exactly where it is -- nothing is moved and
     *  nothing is deleted -- but the app will now look one level up, so a
     *  previously-stored Firebase token or WorkManager database reads as
     *  absent and is recreated in the AOSP-correct place. */
    public File getNoBackupFilesDir() {
        File parent = getDataDir();
        if (parent == null) parent = new File(".");
        File d = new File(parent, "no_backup");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** AOSP Context.getCodeCacheDir(): returns
     *  `<filesDir>/../code_cache`. Used by ART for JIT profile dumps,
     *  by androidx.work for its native-code cache. */
    public File getCodeCacheDir() {
        File parent = getFilesDir();
        if (parent == null) parent = new File(".");
        File d = new File(parent.getParentFile() != null ? parent.getParentFile() : parent, "code_cache");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** AOSP Context.getDataDir(): returns the per-app data dir
     *  (parent of getFilesDir()). */
    public File getDataDir() {
        File parent = getFilesDir();
        if (parent == null) return new File(".");
        File pp = parent.getParentFile();
        return pp != null ? pp : parent;
    }

    /** AOSP Context.getObbDir(): the per-app OBB (expansion-file) directory,
     *  which lives on EXTERNAL storage, not in the private data dir.
     *  ContextImpl:767-779 is `getObbDirs()[0]` over
     *  Environment.buildExternalStorageAppObbDirs(getPackageName()), i.e.
     *  <external>/Android/obb/<pkg> -- so its last component is the PACKAGE
     *  NAME. We answered <dataDir>/obb, whose last component is `obb`, and an
     *  expansion-file loader that builds `main.<ver>.<pkg>.obb` under the
     *  directory the platform names is the exact caller that cares.
     *  runtime/src/apk_unpacker.cpp:1490's XAPK TODO already names
     *  `Android/obb/<pkg>/*.obb` as the destination it would extract to, so
     *  this also puts the getter and the (future) unpacker in the same place. */
    public File getObbDir() {
        return macapkExternalAppDir("obb", null);
    }

    /** AOSP Environment.buildExternalStorageApp*Dirs (android-11.0.0_r48
     *  core/java/android/os/Environment.java:185-211):
     *
     *      buildExternalStorageAppDataDirs(pkg)  -> <ext>/Android/data/<pkg>
     *      buildExternalStorageAppFilesDirs(pkg) -> <ext>/Android/data/<pkg>/files
     *      buildExternalStorageAppCacheDirs(pkg) -> <ext>/Android/data/<pkg>/cache
     *      buildExternalStorageAppMediaDirs(pkg) -> <ext>/Android/media/<pkg>
     *      buildExternalStorageAppObbDirs(pkg)   -> <ext>/Android/obb/<pkg>
     *
     *  with DIR_ANDROID = "Android" (:69) and the kind segments data / media /
     *  obb (:70-72). ContextImpl reaches every external getter through these
     *  and then ensureExternalDirsExistOrFilter(), which CREATES the directory
     *  before handing it back -- hence the mkdirs here.
     *
     *  The external root is Environment.getExternalStorageDirectory(), which
     *  this runtime pins to <android_root>/sdcard precisely so that a native
     *  fopen("/sdcard/...") (remapped in runtime/translator/src/
     *  bionic_shim.cpp:24287-24291) and a Java Environment call land on the
     *  same bytes. Building the per-app external tree from that same root
     *  keeps that invariant: a guest that hardcodes
     *  "/sdcard/Android/data/<pkg>/files/save.dat" natively and one that calls
     *  getExternalFilesDir(null) from Java now agree.
     *
     *  The BARE package name is used, not the version-suffixed data-dir name.
     *  That is AOSP's contract (external storage is not version-scoped, which
     *  is why app data there survives an upgrade), and it is also the only
     *  choice that matches what the app itself would type into a hardcoded
     *  native path.
     *
     *  Returns null when there is no package name to build under. That is an
     *  answer AOSP itself can give (getExternalFilesDir is documented
     *  @Nullable, and ensureExternalDirsExistOrFilter drops volumes it cannot
     *  create), and it only arises for a Context so detached that getFilesDir()
     *  is already answering "." */
    private File macapkExternalAppDir(String kind, String leaf) {
        File ext;
        try { ext = android.os.Environment.getExternalStorageDirectory(); }
        catch (Throwable t) { return null; }
        if (ext == null) return null;
        String pkg;
        try { pkg = getPackageName(); } catch (Throwable t) { return null; }
        if (pkg == null || pkg.isEmpty()) return null;
        File d = new File(new File(new File(ext, "Android"), kind), pkg);
        if (leaf != null) d = new File(d, leaf);
        try { d.mkdirs(); } catch (Throwable ignored) {}
        return d;
    }

    /** AOSP Context.getDatabasePath(name). Two cases per the AOSP
     *  ContextImpl source (frameworks/base/core/java/android/app/
     *  ContextImpl.java):
     *    - if `name` is an absolute path: return new File(name)
     *      (the caller knows where it wants the db; common with Room
     *      which puts WorkDatabase under `<filesDir>/no_backup/<name>`
     *      via a full path -- absolute and inside the per-app tree)
     *    - if `name` is a bare filename: place under
     *      `<filesDir>/../databases/<name>` (standard Android layout)
     *  Pre-v1.0.102 we did the second case unconditionally, which
     *  for Room's absolute-path callers produced an unhinged nested
     *  result like `databases/Users/<user>/.../androidx.work.workdb`
     *  -- the file was created and queries worked, but each app
     *  effectively had a fresh DB on every run, breaking persistence
     *  + breaking the Schedulers.schedule eligibility check (which
     *  re-reads the WorkSpec by id after enqueue). */
    public File getDatabasePath(String name) {
        if (name == null) name = "default.db";
        // AOSP behavior: absolute-path names are returned verbatim.
        if (name.length() > 0 && name.charAt(0) == java.io.File.separatorChar) {
            File f = new File(name);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return f;
        }
        File parent = getFilesDir();
        File dbDir = (parent != null) ? new File(parent.getParentFile(), "databases")
                                       : new File("databases");
        if (!dbDir.exists()) dbDir.mkdirs();
        return new File(dbDir, name);
    }
    // (getNoBackupFilesDir defined above)
    /** AOSP signature: `public abstract String[] databaseList()` --
     *  returns the *names* (not Files) of databases owned by this
     *  context's package. Room queries this during onConfigure to
     *  decide whether the file already exists. */
    public String[] databaseList() {
        File dir = getDatabasePath("dummy").getParentFile();
        if (dir == null || !dir.exists()) return new String[0];
        File[] files = dir.listFiles();
        if (files == null) return new String[0];
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        return names;
    }

    /** AOSP Context.openOrCreateDatabase. ContextImpl maps the mode bits to
     *  SQLiteDatabase open flags (CREATE_IF_NECESSARY always, plus
     *  ENABLE_WRITE_AHEAD_LOGGING / NO_LOCALIZED_COLLATORS when the matching
     *  MODE_ bit is set) and opens getDatabasePath(name). Mac.apk's
     *  SQLiteDatabase is the real libsqlite3-backed implementation, so this
     *  is pure plumbing over factories that already exist -- it was simply
     *  never declared on Context, so apps doing raw pre-Room SQLite through
     *  the Context convenience method hit NoSuchMethodError. */
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
            String name, int mode,
            android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
        return openOrCreateDatabase(name, mode, factory, null);
    }
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
            String name, int mode,
            android.database.sqlite.SQLiteDatabase.CursorFactory factory,
            android.database.DatabaseErrorHandler errorHandler) {
        // MODE_ENABLE_WRITE_AHEAD_LOGGING is not forwarded: our SQLiteDatabase
        // has no ENABLE_WRITE_AHEAD_LOGGING flag to name and no WAL knob behind
        // it, so claiming to honor it would be a lie. WAL is a durability /
        // concurrency setting, not an observable API contract, so dropping it
        // changes no answer the app can read back.
        int flags = android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                  | android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_NO_LOCALIZED_COLLATORS) != 0) {
            flags |= android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        }
        return android.database.sqlite.SQLiteDatabase.openDatabase(
            getDatabasePath(name).getPath(), factory, flags, errorHandler);
    }

    public Looper getMainLooper() { return Looper.getMainLooper(); }

    /** AOSP Context.getMainExecutor() (API 28+): an Executor that runs tasks on
     *  the main (UI) thread. ContextImpl returns a HandlerExecutor over the main
     *  Handler; we mirror that -- posting each Runnable to a Handler bound to the
     *  main Looper. Was MISSING -> Plague Inc's SocialService.onResume (and any
     *  app that schedules UI-thread work via getMainExecutor, e.g. androidx
     *  ContextCompat.getMainExecutor) hit NoSuchMethodError and aborted onResume. */
    public java.util.concurrent.Executor getMainExecutor() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        return new java.util.concurrent.Executor() {
            @Override public void execute(Runnable command) {
                // Always post, matching AOSP HandlerExecutor: callers can rely on
                // execute() being asynchronous even when called from the main thread.
                if (command != null) h.post(command);
            }
        };
    }

    /** v1.0.72: AOSP service constants not already defined further
     *  up in this file. */
    public static final String JOB_SCHEDULER_SERVICE     = "jobscheduler";
    public static final String LAYOUT_INFLATER_SERVICE   = "layout_inflater";
    public static final String KEYGUARD_SERVICE          = "keyguard";
    public static final String ACCESSIBILITY_SERVICE     = "accessibility";
    public static final String USB_SERVICE               = "usb";
    public static final String SHORTCUT_SERVICE          = "shortcut";

    /** v1.0.463 (subsystem-04 close-list): the remaining documented
     *  SERVICE_NAME constants from AOSP android.content.Context. Apps that
     *  reference Context.<NAME>_SERVICE in source (rather than the literal
     *  string) need the constant to resolve at compile/link time even when
     *  the manager behind it is a thin honest stub. String values verified
     *  against android-34 android.jar via `javap -constants`. */
    public static final String ACCOUNT_SERVICE               = "account";
    public static final String APPWIDGET_SERVICE             = "appwidget";
    public static final String APP_OPS_SERVICE               = "appops";
    public static final String APP_SEARCH_SERVICE            = "app_search";
    public static final String BATTERY_SERVICE               = "batterymanager";
    public static final String BLOB_STORE_SERVICE            = "blob_store";
    public static final String BLUETOOTH_SERVICE             = "bluetooth";
    public static final String BUGREPORT_SERVICE             = "bugreport";
    public static final String CAPTIONING_SERVICE            = "captioning";
    public static final String CARRIER_CONFIG_SERVICE        = "carrier_config";
    public static final String COMPANION_DEVICE_SERVICE      = "companiondevice";
    public static final String CONNECTIVITY_DIAGNOSTICS_SERVICE = "connectivity_diagnostics";
    public static final String CONSUMER_IR_SERVICE           = "consumer_ir";
    public static final String CREDENTIAL_SERVICE            = "credential";
    public static final String CROSS_PROFILE_APPS_SERVICE    = "crossprofileapps";
    public static final String DEVICE_LOCK_SERVICE           = "device_lock";
    public static final String DEVICE_POLICY_SERVICE         = "device_policy";
    public static final String DISPLAY_HASH_SERVICE          = "display_hash";
    public static final String DOMAIN_VERIFICATION_SERVICE   = "domain_verification";
    public static final String DROPBOX_SERVICE               = "dropbox";
    public static final String EUICC_SERVICE                 = "euicc";
    public static final String FILE_INTEGRITY_SERVICE        = "file_integrity";
    public static final String GAME_SERVICE                  = "game";
    public static final String GRAMMATICAL_INFLECTION_SERVICE = "grammatical_inflection";
    public static final String HARDWARE_PROPERTIES_SERVICE   = "hardware_properties";
    public static final String HEALTHCONNECT_SERVICE         = "healthconnect";
    public static final String IPSEC_SERVICE                 = "ipsec";
    public static final String LAUNCHER_APPS_SERVICE         = "launcherapps";
    public static final String LOCALE_SERVICE                = "locale";
    public static final String MEDIA_COMMUNICATION_SERVICE   = "media_communication";
    public static final String MEDIA_METRICS_SERVICE         = "media_metrics";
    public static final String MEDIA_PROJECTION_SERVICE      = "media_projection";
    public static final String MEDIA_ROUTER_SERVICE          = "media_router";
    public static final String MEDIA_SESSION_SERVICE         = "media_session";
    public static final String MIDI_SERVICE                  = "midi";
    public static final String NETWORK_STATS_SERVICE         = "netstats";
    public static final String NFC_SERVICE                   = "nfc";
    public static final String NSD_SERVICE                   = "servicediscovery";
    public static final String OVERLAY_SERVICE               = "overlay";
    public static final String PEOPLE_SERVICE                = "people";
    public static final String PERFORMANCE_HINT_SERVICE      = "performance_hint";
    public static final String PRINT_SERVICE                 = "print";
    public static final String RESTRICTIONS_SERVICE          = "restrictions";
    public static final String ROLE_SERVICE                  = "role";
    public static final String STATUS_BAR_SERVICE            = "statusbar";
    public static final String STORAGE_STATS_SERVICE         = "storagestats";
    public static final String SYSTEM_HEALTH_SERVICE         = "systemhealth";
    public static final String TELECOM_SERVICE               = "telecom";
    public static final String TELEPHONY_IMS_SERVICE         = "telephony_ims";
    public static final String TELEPHONY_SUBSCRIPTION_SERVICE = "telephony_subscription_service";
    public static final String TEXT_CLASSIFICATION_SERVICE   = "textclassification";
    public static final String TEXT_SERVICES_MANAGER_SERVICE = "textservices";
    public static final String TV_INPUT_SERVICE              = "tv_input";
    public static final String TV_INTERACTIVE_APP_SERVICE    = "tv_interactive_app";
    public static final String USAGE_STATS_SERVICE           = "usagestats";
    public static final String VIBRATOR_MANAGER_SERVICE      = "vibrator_manager";
    public static final String VIRTUAL_DEVICE_SERVICE        = "virtualdevice";
    public static final String VPN_MANAGEMENT_SERVICE        = "vpn_management";
    public static final String WALLPAPER_SERVICE             = "wallpaper";
    public static final String WIFI_AWARE_SERVICE            = "wifiaware";
    public static final String WIFI_P2P_SERVICE              = "wifip2p";
    public static final String WIFI_RTT_RANGING_SERVICE      = "wifirtt";

    /** AOSP Context.getWallpaperDesiredMinimumWidth/Height -- declared
     *  ABSTRACT on Context itself (frameworks/base/core/java/android/content/
     *  Context.java, android11-release:1781,1788), each carrying only an
     *  @Deprecated pointer at the WallpaperManager replacement. The real
     *  body lives on ContextImpl (core/java/android/app/ContextImpl.java,
     *  same branch, lines 959-967), fetched 2026-08-12:
     *      public int getWallpaperDesiredMinimumWidth() {
     *          return getWallpaperManager().getDesiredMinimumWidth();
     *      }
     *      public int getWallpaperDesiredMinimumHeight() {
     *          return getWallpaperManager().getDesiredMinimumHeight();
     *      }
     *  where getWallpaperManager() (ContextImpl.java:987) is
     *  `getSystemService(WallpaperManager.class)`.
     *
     *  Ported as a direct delegate through our own
     *  getSystemService(WALLPAPER_SERVICE), which is already wired to a real
     *  WallpaperManager two cases below in the switch. That manager
     *  honestly answers 0 for both dimensions (Mac.apk has no wallpaper),
     *  so these two just carry the same honest 0 up to the Context-level
     *  convenience instead of throwing NoSuchMethodError on any caller that
     *  still uses the pre-API-15 accessor rather than fetching
     *  WallpaperManager directly. Given concrete (not abstract) bodies here,
     *  matching this class's own established pattern of implementing
     *  every method that does not genuinely need to vary per Context
     *  subclass (see e.g. getDisplay/getSharedPreferencesPath/getUser
     *  above) rather than leaving it abstract for no behavioral reason. */
    @Deprecated
    public int getWallpaperDesiredMinimumWidth() {
        Object wm = getSystemService(WALLPAPER_SERVICE);
        return (wm instanceof android.app.WallpaperManager)
            ? ((android.app.WallpaperManager) wm).getDesiredMinimumWidth() : 0;
    }
    @Deprecated
    public int getWallpaperDesiredMinimumHeight() {
        Object wm = getSystemService(WALLPAPER_SERVICE);
        return (wm instanceof android.app.WallpaperManager)
            ? ((android.app.WallpaperManager) wm).getDesiredMinimumHeight() : 0;
    }

    /** AOSP getSystemService(String) — returns the system service for
     *  the named service. Most return null (we don't model them), but
     *  a few have stub impls (JobScheduler, ActivityManager, etc.)
     *  that satisfy "the cast must not fail" callers like F-Droid's
     *  CleanCacheWorker that does
     *  `(JobScheduler) ctx.getSystemService(JOB_SCHEDULER_SERVICE)`. */
    public Object getSystemService(String name) {
        Object result;
        if (name == null) {
            result = null;
        } else switch (name) {
            // Wired 2026-08-24: these three managers existed but getSystemService
            // returned null for them, which is the NPE-shaped trap this codebase
            // keeps hitting -- an app that takes the branch crashes rather than
            // seeing an honest "unsupported" answer from the manager itself. Each
            // one refuses individually (StrongBox unavailable, grants
            // ERROR_UNIMPLEMENTED, apk-verity false), so handing one out is
            // strictly better than a null.
            case KEYSTORE_SERVICE:
                result = new android.security.keystore.KeyStoreManager();
                break;
            case FILE_INTEGRITY_SERVICE:
                result = new android.security.FileIntegrityManager();
                break;
            case ADVANCED_PROTECTION_SERVICE:
                result = new android.security.advancedprotection.AdvancedProtectionManager();
                break;
            case JOB_SCHEDULER_SERVICE:
                result = android.app.job.JobScheduler.STUB;
                break;
            case ACTIVITY_SERVICE:
                result = new android.app.ActivityManager();
                break;
            case LAYOUT_INFLATER_SERVICE:
                result = android.view.LayoutInflater.from(this);
                break;
            case ACCESSIBILITY_SERVICE:
                // v1.0.98: AccessibilityManager stub. F-Droid +
                // androidx ripple effects do `(AccessibilityManager)
                // getSystemService(ACCESSIBILITY_SERVICE)` and chain
                // .isEnabled(); null would NPE.
                result = new android.view.accessibility.AccessibilityManager();
                break;
            case PROFILING_SERVICE:
                // android.os.ProfilingManager already existed in this tree but
                // was unreachable from this switch because the constant that
                // names it did not: exactly the "manager implemented and simply
                // unreachable" shape debug/levelprobe/ServiceGate.java's header
                // describes for the API-31 block.
                result = new android.os.ProfilingManager();
                break;
            case SECURITY_STATE_SERVICE:
                // android.os.SecurityStateManager exists in this tree and
                // answers the two security-patch facts we genuinely know
                // (Build.VERSION.SECURITY_PATCH and the host kernel version),
                // so the constant declared above is BACKED, not merely
                // advertised. Every other *_SERVICE name added alongside it
                // (keystore, tethering, tv_ad, persistent_data_block,
                // app_function, advanced_protection, satellite, contact_keys,
                // profiling, media_quality) has no manager class in this tree
                // and therefore still resolves to null -- which is the state
                // debug/levelprobe.sh --service-gate exists to surface.
                result = new android.os.SecurityStateManager();
                break;
            case STORAGE_SERVICE:
                // v1.0.98: StorageManager stub. F-Droid's NearbyView
                // chains .getStorageVolumes() which would NPE on null.
                result = new android.os.storage.StorageManager();
                break;
            case AUTOFILL_SERVICE:
                // v1.0.98: AutofillManager stub. Compose chains throw
                // "Autofill service could not be located" if this is
                // null during onAttachedToWindow.
                try {
                    Class<?> afmCls = Class.forName("android.view.autofill.AutofillManager");
                    result = afmCls.getDeclaredConstructor().newInstance();
                } catch (Throwable t) { result = null; }
                break;
            case CLIPBOARD_SERVICE:
                try {
                    Class<?> cmCls = Class.forName("android.content.ClipboardManager");
                    result = cmCls.getDeclaredConstructor().newInstance();
                } catch (Throwable t) { result = null; }
                break;
            case DISPLAY_SERVICE:
                // libGDX AndroidGraphics reads the display refresh-rate /
                // metrics via DisplayManager.getDisplay(0); without this it
                // NoClassDefFounds and onCreate aborts.
                result = new android.hardware.display.DisplayManager();
                break;
            case SENSOR_SERVICE:
                // SensorManager hands out real Sensor objects and delivers a
                // synthetic "device flat, still" event stream (see
                // android.hardware.SensorManager) so tilt games stay alive.
                // Cache per-Context: it owns a delivery timer + registrations.
                if (macapkSensorManager == null) {
                    macapkSensorManager = android.hardware.SensorManager.macapkNew();
                }
                result = macapkSensorManager;
                break;
            case LOCATION_SERVICE:
                // Backed by macOS Location Services (com.macapk.loader.LocationBridge);
                // its state is process-wide, so one instance per Context is only a handle.
                if (macapkLocationManager == null) {
                    macapkLocationManager = new android.location.LocationManager();
                }
                result = macapkLocationManager;
                break;
            case CAMERA_SERVICE:
                // camera2 entry point; reports "no cameras present".
                result = new android.hardware.camera2.CameraManager();
                break;
            case BIOMETRIC_SERVICE:
                result = new android.hardware.biometrics.BiometricManager();
                break;
            case FINGERPRINT_SERVICE:
                result = new android.hardware.fingerprint.FingerprintManager();
                break;
            case SHORTCUT_SERVICE:
                // Wikipedia's onCreate does
                // `getSystemService(ShortcutManager.class).getDynamicShortcuts()`;
                // a null service NPEs. The ShortcutManager impl already
                // returns empty lists (no launcher shortcuts on macOS),
                // so wiring it is the honest "pinning unsupported" result.
                result = new android.content.pm.ShortcutManager();
                break;
            case APPWIDGET_SERVICE:
                // AOSP registers AppWidgetManager under Context.APPWIDGET_SERVICE
                // ("appwidget") in SystemServiceRegistry. Duolingo's Hilt graph
                // (and any app with a Glance/RemoteViews widget) does
                // `requireNotNull(ContextCompat.getSystemService(ctx,
                // AppWidgetManager.class))` during Application.onCreate; a null
                // service made requireNotNull throw IllegalArgumentException and
                // aborted onCreate before first frame. AppWidgetManager.getInstance
                // is the canonical accessor and returns our honest "no placed
                // widgets" manager (Mac.apk has no home screen). Generic.
                result = android.appwidget.AppWidgetManager.getInstance(this);
                break;
            // v1.0.489: USER_SERVICE WIRED. The app's REAL Firebase (loaded under
            // MACAPK_GMS_CHILD_FIRST) FirebaseApp.<init> calls
            // getSystemService(UserManager.class).isUserUnlocked() UNGUARDED; a null
            // here threw NPE in the ctor (swallowed by FirebaseInitProvider's
            // catch(Throwable)) so the default FirebaseApp never initialized and
            // FirebaseCrashlytics.getInstance() later threw "Default FirebaseApp is not
            // initialized" -> the launcher activity construct failed -> blank/dead.
            // Real Android ALWAYS returns a non-null UserManager. The PRIOR held-back
            // note feared this would unmask a Crashlytics-NDK native SIGSEGV because
            // "lib/* failed to extract" -- but the arch-split native libs DO extract +
            // translate now (lib_native/lib*.dylib: libreddit-ndk, libcronet, ...), so
            // re-enable condition (a) is met. Generic + AOSP-faithful; isUserUnlocked()
            // returns true (single-user, unlocked).
            case USER_SERVICE:
                result = android.os.UserManager.get(this);
                break;
            // android.app.* system services (05-app subsystem). These
            // back foreground-service / notification / scheduling / search
            // /download/wallpaper/keyguard/ui-mode/appops callers so the
            // getSystemService cast succeeds with a working manager.
            case ALARM_SERVICE:
                result = new android.app.AlarmManager();
                break;
            case NOTIFICATION_SERVICE:
                result = new android.app.NotificationManager();
                break;
            case SEARCH_SERVICE:
                result = new android.app.SearchManager(this);
                break;
            case DOWNLOAD_SERVICE:
                result = new android.app.DownloadManager(this);
                break;
            case UI_MODE_SERVICE:
                result = new android.app.UiModeManager();
                break;
            case KEYGUARD_SERVICE:
                result = new android.app.KeyguardManager();
                break;
            case APP_OPS_SERVICE:
                result = new android.app.AppOpsManager();
                break;
            case WALLPAPER_SERVICE:
                result = android.app.WallpaperManager.getInstance(this);
                break;
            case LOCALE_SERVICE:
                // API-33 per-app locale service. AppCompat 1.6+ and any
                // in-app language picker call getSystemService(LocaleManager
                // .class).setApplicationLocales(...); a null service NPEs.
                // The LocaleManager round-trips the selection and updates the
                // JVM default locale (live activity-recreate on change is a
                // native-layer task -- see Activity.dispatchConfigurationChanged).
                //
                // CACHED (2026-08-20). This used to be `new LocaleManager()` on
                // EVERY call, and the selection lives in an instance field, so
                // set-then-get across two getSystemService() calls returned
                // empty -- the app's own language picker read back nothing it
                // had just written. AOSP registers this with a
                // CachedServiceFetcher like every other manager, and API 33 is
                // exactly where AppCompatDelegate hands locale duty to it.
                if (macapkLocaleManager == null) {
                    macapkLocaleManager = new android.app.LocaleManager(this);
                }
                result = macapkLocaleManager;
                break;
            // v1.0.463 (subsystem-04): hardware / media / telephony / misc
            // managers. Each is an honest "feature absent" manager (empty
            // lists / null adapters where macOS has no backend) so the
            // getSystemService cast and the subsequent null-check chain behave
            // the way they would on a device lacking the hardware, instead of
            // NPE'ing on a null service. (USER_SERVICE is wired above, v1.0.489.)
            case TELEPHONY_SERVICE:
                result = new android.telephony.TelephonyManager();
                break;
            case TELEPHONY_SUBSCRIPTION_SERVICE:
                result = new android.telephony.SubscriptionManager(this);
                break;
            case BATTERY_SERVICE:
                result = new android.os.BatteryManager();
                break;
            case BLUETOOTH_SERVICE:
                result = new android.bluetooth.BluetoothManager(this);
                break;
            case NFC_SERVICE:
                result = new android.nfc.NfcManager(this);
                break;
            case USB_SERVICE:
                result = new android.hardware.usb.UsbManager(this);
                break;
            case ACCOUNT_SERVICE:
                result = android.accounts.AccountManager.get(this);
                break;
            case MEDIA_SESSION_SERVICE:
                result = new android.media.session.MediaSessionManager(this);
                break;
            case MEDIA_ROUTER_SERVICE:
                result = new android.media.MediaRouter(this);
                break;
            case MEDIA_PROJECTION_SERVICE:
                result = new android.media.projection.MediaProjectionManager(this);
                break;
            case MIDI_SERVICE:
                result = new android.media.midi.MidiManager(this);
                break;
            case PRINT_SERVICE:
                result = new android.print.PrintManager(this);
                break;
            case DEVICE_POLICY_SERVICE:
                result = new android.app.admin.DevicePolicyManager(this);
                break;
            case USAGE_STATS_SERVICE:
                result = new android.app.usage.UsageStatsManager(this);
                break;
            case STORAGE_STATS_SERVICE:
                result = new android.app.usage.StorageStatsManager(this);
                break;
            case TEXT_SERVICES_MANAGER_SERVICE:
                result = new android.view.textservice.TextServicesManager(this);
                break;
            case HARDWARE_PROPERTIES_SERVICE:
                result = new android.os.HardwarePropertiesManager(this);
                break;
            case NSD_SERVICE:
                result = new android.net.nsd.NsdManager(this);
                break;
            case WIFI_P2P_SERVICE:
                result = new android.net.wifi.p2p.WifiP2pManager(this);
                break;
            case COMPANION_DEVICE_SERVICE:
                result = new android.companion.CompanionDeviceManager(this);
                break;
            case LAUNCHER_APPS_SERVICE:
                result = new android.content.pm.LauncherApps(this);
                break;
            case ROLE_SERVICE:
                result = new android.app.role.RoleManager(this);
                break;
            case SYSTEM_HEALTH_SERVICE:
                result = new android.os.health.SystemHealthManager(this);
                break;
            case DROPBOX_SERVICE:
                result = new android.os.DropBoxManager();
                break;
            case IPSEC_SERVICE:
                result = new android.net.IpSecManager();
                break;
            // AOSP has ONE service registry, reached from every Context; a
            // bare/base Context (not the Application) must return the SAME
            // managers as the app context, else getSystemService(CONNECTIVITY/
            // POWER/WINDOW/AUDIO/...) returns null on it and the caller NPEs
            // (the [jni-guard] NULL-receiver class). Mirror the exact (proven)
            // constructors from Application.createSystemService.
            case CONNECTIVITY_SERVICE:
                result = new android.net.ConnectivityManager();
                break;
            case POWER_SERVICE:
                result = new android.os.PowerManager();
                break;
            case WINDOW_SERVICE:
                result = new com.macapk.loader.MacApkWindowManager();
                break;
            case AUDIO_SERVICE:
                result = new android.media.AudioManager();
                break;
            case INPUT_METHOD_SERVICE:
                result = android.view.inputmethod.InputMethodManager.peekInstance();
                break;
            case VIBRATOR_SERVICE:
                result = new android.os.Vibrator();
                break;
            // ---- the API-31 additions (2026-08-20) -------------------------
            // These five were absent from this switch, so they hit `default:`
            // and returned null. On AOSP every one is registered with a
            // CachedServiceFetcher that CANNOT return null, and
            // `getSystemService(VibratorManager.class).getDefaultVibrator()`
            // is the documented 31+ pattern -- so an app taking its own
            // SDK_INT >= 31 branch got an NPE where a device gives it a
            // vibrator. Three of the managers were already implemented in this
            // tree and simply unreachable.
            case VIBRATOR_MANAGER_SERVICE:
                result = new android.os.VibratorManager();
                break;
            case PERFORMANCE_HINT_SERVICE:
                result = new android.os.PerformanceHintManager();
                break;
            case GAME_SERVICE:
                result = new android.app.GameManager();
                break;
            // DOMAIN_VERIFICATION_SERVICE and APP_SEARCH_SERVICE are
            // DELIBERATELY still absent, and that is a named gap rather than an
            // oversight: both are large subsystems we do not implement, and
            // CLAUDE.md's decision ladder has no rung on which we hand an app a
            // convincing fake. Returning null matches a device whose
            // corresponding mainline module is not installed, which is a real
            // device state; inventing a manager that answers "verified" or
            // "indexed" would fail later and harder. Revisit when an app in the
            // corpus actually calls one.
            case WIFI_SERVICE:
                result = new android.net.wifi.WifiManager();
                break;
            case INPUT_SERVICE:
                result = new android.hardware.input.InputManager();
                break;
            // v1.0.678: three more managers whose ABSENCE (a null service) is
            // what actually breaks callers. Each reports the honest
            // feature-absent state AOSP itself reports on a device without the
            // capability, so the getSystemService cast and the usual
            // isEnabled()/hasX() guard both behave normally.
            case MEDIA_METRICS_SERVICE:
                // ExoPlayer/Media3 create a playback session and report into
                // it on every buffer/error/track change; a null service NPEs
                // the player. The sessions accept and drop (no collector).
                result = new android.media.metrics.MediaMetricsManager();
                break;
            case GRAMMATICAL_INFLECTION_SERVICE:
                result = new android.app.GrammaticalInflectionManager();
                break;
            case CAPTIONING_SERVICE:
                // Media players do getSystemService(CaptioningManager.class)
                // .isEnabled() unguarded; null NPEs before playback starts.
                result = new android.view.accessibility.CaptioningManager();
                break;
            case RESTRICTIONS_SERVICE:
                // Unmanaged device: an empty restrictions Bundle, never null.
                result = new android.content.RestrictionsManager();
                break;
            case CONSUMER_IR_SERVICE:
                // No IR emitter on any Mac; hasIrEmitter() false is the AOSP
                // answer for such a device.
                result = new android.hardware.ConsumerIrManager();
                break;
            case CREDENTIAL_SERVICE:
                // No credential providers on a de-Googled device; the manager
                // exists and its get/create calls report "no credential" via
                // onError (see android.credentials.CredentialManager).
                result = new android.credentials.CredentialManager();
                break;
            // null for the rest -- callers that strictly need them
            // will short-circuit, which our runTolerant catches.
            default:
                result = null;
                break;
        }
        // Diagnostic v1.0.72: always log via NativeLog (System.err
        // sometimes buffered differently in JVM-threading paths;
        // NativeLog goes straight to fprintf(stderr)).
        try {
            com.macapk.loader.NativeLog.i("ctx",
                "getSystemService(\"" + name + "\") -> "
                + (result == null ? "null" : result.getClass().getName())
                + " [via " + getClass().getSimpleName() + "]");
        } catch (Throwable ignored) {}
        return result;
    }

    /** API-23+ typed accessor. Apps call `ctx.getSystemService(
     *  AccessibilityManager.class)` or similar; AOSP routes via
     *  getSystemServiceName(clazz) -> getSystemService(name). We
     *  delegate to the String overload with a best-effort name
     *  derived from the class's simple-name -> lowercase. Returns
     *  null when no service is registered. F-Droid uses this for
     *  Settings -> KeyguardManager / NotificationManager probes
     *  during preferences setup. */
    public <T> T getSystemService(Class<T> serviceClass) {
        if (serviceClass == null) return null;
        String name = getSystemServiceName(serviceClass);
        if (name == null) return null;
        Object svc = getSystemService(name);
        if (svc == null) return null;
        if (serviceClass.isInstance(svc)) return serviceClass.cast(svc);
        return null;
    }
    /** AOSP-internal helper mapping class -> service name. AOSP uses
     *  a hardcoded HashMap<Class, String> in SystemServiceRegistry.
     *  We have an explicit map for known classes; everything else
     *  falls back to a heuristic (strip "Manager" suffix, lowercase
     *  first letter). The explicit map matches AOSP's
     *  `getSystemServiceName` for service classes that DON'T follow
     *  the "<Type>Manager" pattern (JobScheduler, PowerManager
     *  delegates, etc.) where the simple-name lowercase doesn't
     *  match the canonical service string. */
    public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == null) return null;
        String fq = serviceClass.getName();
        // Explicit AOSP mappings for services whose name doesn't
        // derive from the class simple-name via the strip-Manager
        // heuristic. Each maps to the constant string from this
        // class above; AOSP's SystemServiceRegistry uses the same
        // values (verified against `frameworks/base/core/java/
        // android/app/SystemServiceRegistry.java`).
        switch (fq) {
            case "android.app.job.JobScheduler":
                return JOB_SCHEDULER_SERVICE;
            case "android.app.AlarmManager":
                return ALARM_SERVICE;
            case "android.view.LayoutInflater":
                return LAYOUT_INFLATER_SERVICE;
            case "android.app.NotificationManager":
                return NOTIFICATION_SERVICE;
            case "android.app.ActivityManager":
                return ACTIVITY_SERVICE;
            case "android.net.ConnectivityManager":
                return CONNECTIVITY_SERVICE;
            case "android.os.PowerManager":
                return POWER_SERVICE;
            case "android.os.Vibrator":
                return VIBRATOR_SERVICE;
            // REQUIRED, not decorative: the strip-Manager heuristic below turns
            // VibratorManager into "vibrator", which returns a Vibrator, which
            // then FAILS serviceClass.isInstance() and yields null -- the typed
            // route would miss even with the string cases above present.
            case "android.os.VibratorManager":
                return VIBRATOR_MANAGER_SERVICE;
            case "android.os.PerformanceHintManager":
                return PERFORMANCE_HINT_SERVICE;
            case "android.app.GameManager":
                return GAME_SERVICE;
            case "android.net.wifi.WifiManager":
                return WIFI_SERVICE;
            case "android.media.AudioManager":
                return AUDIO_SERVICE;
            case "android.view.WindowManager":
                return WINDOW_SERVICE;
            case "android.view.inputmethod.InputMethodManager":
                return INPUT_METHOD_SERVICE;
            case "android.app.KeyguardManager":
                return KEYGUARD_SERVICE;
            case "android.content.ClipboardManager":
                return CLIPBOARD_SERVICE;
            case "android.location.LocationManager":
                return LOCATION_SERVICE;
            case "android.hardware.SensorManager":
                return SENSOR_SERVICE;
            case "android.app.UiModeManager":
                return UI_MODE_SERVICE;
            case "android.app.LocaleManager":
                return LOCALE_SERVICE;               // "locale"
            case "android.app.DownloadManager":
                return DOWNLOAD_SERVICE;
            case "android.app.SearchManager":
                return SEARCH_SERVICE;
            case "android.view.accessibility.AccessibilityManager":
                return ACCESSIBILITY_SERVICE;
            case "android.credentials.CredentialManager":
                return CREDENTIAL_SERVICE;
            // v1.0.463 (subsystem-04): explicit mappings for managers whose
            // canonical service name does NOT derive from the simple-name via
            // the strip-Manager + lowercase-first heuristic (snake_case names,
            // "Manager"-less classes, or names that diverge from the type).
            // Verified against android-34 android.jar `javap -constants`.
            case "android.telephony.TelephonyManager":
                return TELEPHONY_SERVICE;            // "phone"
            case "android.telephony.SubscriptionManager":
                return TELEPHONY_SUBSCRIPTION_SERVICE;
            case "android.os.BatteryManager":
                return BATTERY_SERVICE;              // "batterymanager"
            case "android.bluetooth.BluetoothManager":
                return BLUETOOTH_SERVICE;
            case "android.nfc.NfcManager":
                return NFC_SERVICE;
            case "android.hardware.usb.UsbManager":
                return USB_SERVICE;
            case "android.accounts.AccountManager":
                return ACCOUNT_SERVICE;
            case "android.media.session.MediaSessionManager":
                return MEDIA_SESSION_SERVICE;
            case "android.media.MediaRouter":
                return MEDIA_ROUTER_SERVICE;
            case "android.media.projection.MediaProjectionManager":
                return MEDIA_PROJECTION_SERVICE;
            case "android.media.midi.MidiManager":
                return MIDI_SERVICE;
            case "android.print.PrintManager":
                return PRINT_SERVICE;
            case "android.app.admin.DevicePolicyManager":
                return DEVICE_POLICY_SERVICE;
            case "android.app.usage.UsageStatsManager":
                return USAGE_STATS_SERVICE;
            case "android.app.usage.StorageStatsManager":
                return STORAGE_STATS_SERVICE;
            case "android.view.textservice.TextServicesManager":
                return TEXT_SERVICES_MANAGER_SERVICE;
            case "android.os.HardwarePropertiesManager":
                return HARDWARE_PROPERTIES_SERVICE;
            case "android.net.nsd.NsdManager":
                return NSD_SERVICE;                  // "servicediscovery"
            case "android.net.wifi.p2p.WifiP2pManager":
                return WIFI_P2P_SERVICE;
            case "android.companion.CompanionDeviceManager":
                return COMPANION_DEVICE_SERVICE;
            case "android.content.pm.LauncherApps":
                return LAUNCHER_APPS_SERVICE;
            case "android.app.role.RoleManager":
                return ROLE_SERVICE;
            case "android.os.health.SystemHealthManager":
                return SYSTEM_HEALTH_SERVICE;
            case "android.app.WallpaperManager":
                return WALLPAPER_SERVICE;
            case "android.app.AppOpsManager":
                return APP_OPS_SERVICE;
            case "android.appwidget.AppWidgetManager":
                // The strip-Manager + lowercase-first heuristic would yield
                // "appWidget" (camelCase), but AOSP's canonical service name is
                // the all-lowercase APPWIDGET_SERVICE ("appwidget"). Map
                // explicitly so the typed getSystemService(AppWidgetManager.class)
                // / ContextCompat.getSystemService path resolves to the real
                // case in the String switch above instead of falling through to
                // null (which broke Duolingo's Hilt requireNotNull at onCreate).
                return APPWIDGET_SERVICE;
            // The strip-Manager + lowercase-first heuristic below produces
            // camelCase, and AOSP's canonical names for these five are
            // snake_case or all-lowercase, so the heuristic answered a string
            // that the getSystemService(String) switch above does NOT have a
            // case for -- i.e. getSystemService(XManager.class), which is what
            // ContextCompat.getSystemService(ctx, XManager.class) compiles to
            // and which is the modern AndroidX idiom, returned NULL for five
            // managers this class already constructs perfectly well by name.
            // (`mediaMetrics` vs `media_metrics`, `consumerIr` vs
            // `consumer_ir`, `dropBox` vs `dropbox`, `ipSec` vs `ipsec`,
            // `grammaticalInflection` vs `grammatical_inflection`.)
            //
            // Names verified against AOSP's own class -> name bindings:
            // SystemServiceRegistry.registerService(Context.DROPBOX_SERVICE,
            // DropBoxManager.class, ...) at android-14.0.0_r1 core/java/
            // android/app/SystemServiceRegistry.java:487, and likewise
            // CONSUMER_IR_SERVICE :889, MEDIA_METRICS_SERVICE :1495,
            // GRAMMATICAL_INFLECTION_SERVICE :1562. IpSecManager's binding
            // lives in the Connectivity mainline module rather than the
            // registry, so it is taken from the constant's own javadoc
            // (android-11.0.0_r48 core/java/android/content/Context.java
            // :3997-4004, "retrieve a {@link android.net.IpSecManager}").
            //
            // The remaining classes reaching the heuristic are ones whose
            // canonical name it happens to reproduce exactly (StorageManager
            // -> "storage", CameraManager -> "camera", DisplayManager ->
            // "display", ...); it is kept as a last resort rather than a
            // primary, because a name it gets wrong is indistinguishable from
            // "no such service" at the call site.
            case "android.os.DropBoxManager":
                return DROPBOX_SERVICE;                  // "dropbox"
            case "android.net.IpSecManager":
                return IPSEC_SERVICE;                    // "ipsec"
            case "android.media.metrics.MediaMetricsManager":
                return MEDIA_METRICS_SERVICE;            // "media_metrics"
            case "android.app.GrammaticalInflectionManager":
                return GRAMMATICAL_INFLECTION_SERVICE;   // "grammatical_inflection"
            case "android.hardware.ConsumerIrManager":
                return CONSUMER_IR_SERVICE;              // "consumer_ir"
            // v1.0.1637: eleven more the heuristic got wrong, every one of which
            // debug/levelprobe.sh --service-gate had been PRINTING as TYPED-MISS
            // without failing. Found through a real crash: Aurora Store's
            // DomainVerificationManager provider does
            // `ctx.getSystemService(DomainVerificationManager.class).getClass()`
            // and NPE'd (FATAL on main, then its ProcessPhoenix self-restart),
            // because the heuristic answered "domainVerification".
            // Bindings verified against AOSP SystemServiceRegistry (main):
            // ADVANCED_PROTECTION :1753, CROSS_PROFILE_APPS :1417, DISPLAY_HASH
            // :1654, DOMAIN_VERIFICATION :1641, FILE_INTEGRITY :1560, KEYSTORE
            // :1714, SECURITY_STATE :676, TEXT_CLASSIFICATION :419,
            // VPN_MANAGEMENT :471; NETWORK_STATS at android12-release :990 (it
            // moved into the Connectivity module later); CARRIER_CONFIG in
            // TelephonyFrameworkInitializer (main) :114-115.
            case "android.security.advancedprotection.AdvancedProtectionManager":
                return ADVANCED_PROTECTION_SERVICE;
            case "android.telephony.CarrierConfigManager":
                return CARRIER_CONFIG_SERVICE;
            case "android.content.pm.CrossProfileApps":
                return CROSS_PROFILE_APPS_SERVICE;
            case "android.view.displayhash.DisplayHashManager":
                return DISPLAY_HASH_SERVICE;
            case "android.content.pm.verify.domain.DomainVerificationManager":
                return DOMAIN_VERIFICATION_SERVICE;
            case "android.security.FileIntegrityManager":
                return FILE_INTEGRITY_SERVICE;
            case "android.security.keystore.KeyStoreManager":
                return KEYSTORE_SERVICE;
            case "android.app.usage.NetworkStatsManager":
                return NETWORK_STATS_SERVICE;
            case "android.os.SecurityStateManager":
                return SECURITY_STATE_SERVICE;
            case "android.view.textclassifier.TextClassificationManager":
                return TEXT_CLASSIFICATION_SERVICE;
            case "android.net.VpnManager":
                return VPN_MANAGEMENT_SERVICE;
            default:
                break;
        }
        String n = serviceClass.getSimpleName();
        if (n == null || n.isEmpty()) return null;
        // Strip "Manager" suffix and lowercase the first letter.
        // android.view.accessibility.AccessibilityManager -> "accessibility"
        // android.app.NotificationManager -> "notification"
        if (n.endsWith("Manager")) n = n.substring(0, n.length() - 7);
        return Character.toLowerCase(n.charAt(0))
            + (n.length() > 1 ? n.substring(1) : "");
    }

    // v1.0.426 Tier-2: the base Context returned null, so any SharedPreferences
    // fetched through a raw Context / ContextThemeWrapper / createConfiguration
    // Context whose chain bottoms out here (androidx.preference.PreferenceManager.
    // getDefaultSharedPreferences hands whatever Context it has) NPE'd. Return a
    // real process-wide prefs interned by name (Application overrides this with
    // its own per-name cache; SharedPrefsImpl keys storage by name + the shared
    // app data dir, so the instance is consistent for a given file).
    private static final java.util.HashMap<String, SharedPreferences> sBasePrefs = new java.util.HashMap<>();

    /** The APP's own declared targetSdkVersion as seen from this Context, or 0
     *  when it cannot be read. The one input a targetSdk compat gate may use;
     *  see android.app.Application.macapkAppTargetSdk for the convention. */
    public static int macapkTargetSdkOf(Context ctx) {
        try {
            android.content.pm.ApplicationInfo ai =
                    (ctx != null) ? ctx.getApplicationInfo() : null;
            if (ai != null && ai.targetSdkVersion > 0) return ai.targetSdkVersion;
        } catch (Throwable ignored) { }
        return 0;
    }

    /** AOSP ContextImpl.java:588-596 (android16-release) opens with its own
     *  comment: "At least one application in the world actually passes in a
     *  null name. This happened to work because when we generated the file name
     *  we would stringify it to null.xml. Nice." So a pre-KitKat app that
     *  passes null gets a working null.xml; at or above KITKAT the null flows
     *  on and blows up exactly as it does on a device. The branch below cannot
     *  change any app at or above 19, nor any call with a non-null name. */
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (name == null) {
            int targetSdkVersion = macapkTargetSdkOf(this);
            if (android.app.Application.macapkTargetSdkKnown(targetSdkVersion)
                    && targetSdkVersion < android.os.Build.VERSION_CODES.KITKAT) {
                name = "null";
            }
        }
        synchronized (sBasePrefs) {
            SharedPreferences p = sBasePrefs.get(name);
            // Interned by FILE (see SharedPrefsImpl.macapkGet): Application has
            // its own cache, and one file must not end up with two instances
            // holding independent locks over the atomic-write dance.
            if (p == null) { p = com.macapk.loader.SharedPrefsImpl.macapkGet(this, name); sBasePrefs.put(name, p); }
            return p;
        }
    }

    // AOSP Context.openFileInput returns a FileInputStream (NOT InputStream):
    // the public method descriptor is ...(String)Ljava/io/FileInputStream; and
    // callers compiled against it (e.g. Firebase Remote Config's
    // ConfigStorageClient.read) invokevirtual that exact descriptor, so a
    // narrower return type here surfaces as NoSuchMethodError at runtime.
    public java.io.FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }
    // AOSP honors MODE_APPEND (0x8000): open in append mode rather than
    // truncating when the bit is set. MODE_PRIVATE (0) truncates.
    public java.io.FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        final boolean append = (mode & 0x8000) != 0;   // Context.MODE_APPEND
        File f = getFileStreamPath(name);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        return new FileOutputStream(f, append);
    }
    // AOSP Context.getFileStreamPath(name) = makeFilename(getFilesDir(), name).
    public File getFileStreamPath(String name) {
        return new File(getFilesDir(), name);
    }
    public boolean deleteFile(String name) {
        return getFileStreamPath(name).delete();
    }
    public String[] fileList() {
        File[] f = getFilesDir().listFiles();
        if (f == null) return new String[0];
        String[] out = new String[f.length];
        for (int i = 0; i < f.length; ++i) out[i] = f[i].getName();
        return out;
    }

    // Route to ActivityHost.start, exactly as startActivities (below) routes to
    // ActivityHost.startAll. Activity overrides this to ALSO thread the
    // (caller, requestCode) result pairing, but the base launch is the same
    // ActivityThread path, and a NON-Activity context must perform it too.
    //
    // ⚠ v1.0.953: this used to be an empty no-op, on the reasoning that only an
    // Activity starts activities. That silently dropped every launch made from
    // an app/service context -- most importantly PendingIntent.send(), whose
    // dispatch runs startActivity on its creator context. That creator is
    // routinely the Application (PendingIntent.getActivity(getApplicationContext(),
    // ...)), so a notification tap, an alarm callback, or a widget action that
    // wraps an activity fired into a no-op. Concretely it blocked the F-Droid
    // install end to end: DefaultInstaller broadcasts INSTALL_USER_INTERACTION
    // with a PendingIntent wrapping DefaultInstallerActivity, AppDetailsActivity
    // fires PendingIntent.send() on it, and the activity that would issue
    // ACTION_INSTALL_PACKAGE never launched. AOSP allows an app-context
    // startActivity (it requires FLAG_ACTIVITY_NEW_TASK, which
    // PendingIntent.getActivity sets); ActivityHost models the single foreground
    // task, so the flag is a no-op here and the launch is correct.
    public void startActivity(Intent intent) {
        com.macapk.loader.ActivityHost.start(intent);
    }
    // AOSP API-16+ overload (intent + ActivityOptions.toBundle()). Apps invoke
    // this against the Context static type (e.g. F-Droid's row-click handler
    // -> Context.startActivity(intent, options.toBundle())), so it must be
    // declared here for resolution; delegate to the single-arg form so any
    // subclass that only overrides startActivity(Intent) (Activity does, via
    // ActivityHost.start) still performs the launch. The Bundle (launch
    // animation hints) is ignored -- Mac.apk has no activity transitions.
    public void startActivity(Intent intent, android.os.Bundle options) { startActivity(intent); }
    // AOSP startActivities(Intent[]) launches a back-STACK: intents[0] is the
    // ROOT of the task and each later intent is pushed on top of it, so the
    // LAST one ends up visible/resumed and every earlier one sits underneath.
    // androidx TaskStackBuilder.startActivities() routes here, and the parent
    // stack it synthesises is the entire reason the API exists.
    //
    // v1.0.834: this used to start ONLY the top-most intent carrying a
    // component, on the reasoning that Mac.apk has a single foreground
    // activity. But com.macapk.loader.ActivityHost keeps a real LIFO task
    // stack and reveals the activity underneath on finish(), so the
    // simplification was silently discarding a stack we CAN model. Measured
    // consequence (Sgt Puzzles 1.49, 2026-07-29): its launcher builds
    // [GameChooser, GamePlay] via addNextIntentWithParentStack and starts
    // both; with only GamePlay started, GamePlay's own "no saved game" branch
    // (NavUtils.navigateUpFromSameTask -> Activity.navigateUpTo -> finish())
    // had nothing underneath to reveal, so the chooser never appeared.
    //
    // ActivityHost.startAll is called instead of looping startActivity()
    // because under MACAPK_REAL_LOOPER the single-intent path enters
    // Looper.loop() and never returns -- the whole stack has to be posted
    // before the loop is entered. See its header.
    public void startActivities(Intent[] intents) { startActivities(intents, null); }
    public void startActivities(Intent[] intents, android.os.Bundle options) {
        if (intents == null || intents.length == 0) return;
        com.macapk.loader.ActivityHost.startAll(intents);
    }
    // v1.0.426 Tier-2: a real process-wide dynamic-BroadcastReceiver registry.
    // Was all no-ops -> androidx ProcessLifecycle-adjacent code, Connectivity
    // change receivers, ACTION_LOCALE_CHANGED / ACTION_TIME_TICK handlers, and
    // any component that registers a receiver and broadcasts to itself in-process
    // got no callback. Single-VM, so a registered receiver hears every in-process
    // sendBroadcast whose action its filter matches (system broadcasts we don't
    // emit simply never arrive, same as a device with the feature absent).
    private static final java.util.List<Object[]> sReceivers =
        new java.util.concurrent.CopyOnWriteArrayList<>();   // {BroadcastReceiver, IntentFilter}

    // ---- STICKY BROADCASTS ----------------------------------------------
    //
    // AOSP Context.registerReceiver: "If your IntentFilter matches one of these
    // sticky Intents, that Intent will be returned by this function AND sent to
    // your receiver as if it had just been broadcast... you can supply null for
    // your receiver... the function simply returns the sticky Intent".
    //
    // We retained nothing and always returned null, which broke the single most
    // common battery idiom on Android -- `registerReceiver(null,
    // new IntentFilter(ACTION_BATTERY_CHANGED))` to read the level once -- with
    // an app-side NPE or a permanent "battery unknown". 17 corpus apps embed
    // ACTION_BATTERY_CHANGED.
    private static final java.util.Map<String, Intent> sStickies =
        new java.util.concurrent.ConcurrentHashMap<>();

    static void macapkPutSticky(Intent i) {
        if (i == null || i.getAction() == null) return;
        // STORE A COPY. AOSP's AMS does `new Intent(intent)` on both sticky
        // paths; aliasing the caller's object lets an app that reuses and
        // mutates its Intent after sendStickyBroadcast silently mutate the
        // retained sticky.
        try { sStickies.put(i.getAction(), new Intent(i)); }
        catch (Throwable t) { sStickies.put(i.getAction(), i); }
    }

    /** EVERY sticky this filter matches, in AOSP's sense.
     *
     *  AOSP registerReceiver, verbatim: "There may be multiple sticky Intents
     *  that match filter, in which case EACH OF THESE WILL BE SENT TO RECEIVER.
     *  In this case, only one of these can be returned directly by the function;
     *  which of these that is returned is arbitrarily decided by the system."
     *  So the arbitrary-return is by design, but delivering only one is not.
     *
     *  Matching is the FULL filter match, not matchAction: this file already
     *  learned that once -- sendBroadcast's own comment records that
     *  "matchAction-only was over-delivery" -- and the lesson did not carry to
     *  this helper when it was written. */
    private java.util.List<Intent> macapkStickiesFor(android.content.IntentFilter f) {
        java.util.ArrayList<Intent> out = new java.util.ArrayList<>(2);
        if (f == null) return out;
        for (Intent i : sStickies.values()) {
            if (i.getAction() == null) continue;
            boolean match;
            try { match = f.match(getContentResolver(), i, true, "macapk") >= 0; }
            catch (Throwable t) {
                try { match = f.matchAction(i.getAction()); } catch (Throwable t2) { match = false; }
            }
            if (match) out.add(i);
        }
        return out;
    }

    private Intent macapkRegisterReceiver(android.content.BroadcastReceiver receiver,
                                          android.content.IntentFilter filter) {
        return macapkRegisterReceiver(receiver, filter, null);
    }

    private Intent macapkRegisterReceiver(android.content.BroadcastReceiver receiver,
                                          android.content.IntentFilter filter,
                                          android.os.Handler scheduler) {
        // BATTERY_CHANGED is synthesized on demand rather than published on a
        // timer: a registration is the only moment its value is observed, and
        // BatteryManager already caches its pmset read behind a short TTL.
        try {
            if (filter != null && filter.matchAction(Intent.ACTION_BATTERY_CHANGED)) {
                macapkPutSticky(macapkBatteryIntent());
            }
        } catch (Throwable ignored) { }
        // AOSP's LoadedApk.ReceiverDispatcher keeps the registrant's own Context
        // and Handler per registration. We kept neither, which cost two bugs:
        // every dispatch passed whatever Context happened to be handy, and the
        // minute ticker captured the FIRST registrant's Activity and pinned it
        // for the process lifetime. Store both.
        if (receiver != null && filter != null) {
            sReceivers.add(new Object[]{ receiver, filter, this, scheduler });
        }
        // Start the minute ticker only when something actually wants it.
        try {
            if (filter != null && filter.matchAction(Intent.ACTION_TIME_TICK)) macapkStartMinuteTicker();
        } catch (Throwable ignored) { }

        java.util.List<Intent> stickies = macapkStickiesFor(filter);
        if (!stickies.isEmpty() && receiver != null) {
            final android.content.BroadcastReceiver r = receiver;
            for (Intent one : stickies) {
                final Intent si = one;
                Runnable task = new Runnable() { @Override public void run() {
                    // Unregistered between the post and now: AOSP drops the
                    // queued delivery when the filter is removed, so a receiver
                    // that released state in unregisterReceiver is not called.
                    boolean live = false;
                    for (Object[] e : sReceivers) if (e[0] == r) { live = true; break; }
                    if (!live) return;
                    // The PendingResult install the ordinary dispatch paths do.
                    // Without it goAsync() returns null on THIS path only, which
                    // is the exact bug v1.0.1110 fixed -- and isInitialSticky
                    // must be TRUE here, which is the whole point of that API:
                    // it is how an app tells retained state from a real change.
                    final android.content.BroadcastReceiver.PendingResult prev = r.getPendingResult();
                    try { r.setPendingResult(new android.content.BroadcastReceiver.PendingResult(
                            0, null, null,
                            android.content.BroadcastReceiver.PendingResult.TYPE_REGISTERED,
                            /*ordered=*/ false, /*sticky=*/ true, 0,
                            android.os.Process.myUid(), getPackageName())); }
                    catch (Throwable ignored) { }
                    try { r.onReceive(Context.this, si); }
                    catch (Throwable t) {
                        com.macapk.loader.NativeLog.w("Context",
                            "sticky replay to " + r.getClass().getName() + " threw: " + t);
                    }
                    finally { try { r.setPendingResult(prev); } catch (Throwable ignored) { } }
                } };
                if (android.os.Looper.macapkHasMainThread()) {
                    try { new android.os.Handler(getMainLooper()).post(task); continue; }
                    catch (Throwable ignored) { }
                }
                task.run();
            }
        }
        // AOSP returns ONE of the matches, "arbitrarily decided by the system".
        return stickies.isEmpty() ? null : stickies.get(0);
    }

    // ---- ACTION_TIME_TICK ------------------------------------------------
    //
    // Intent.ACTION_TIME_TICK, verbatim: "The current time has changed. Sent
    // every minute. You *cannot* receive this through components declared in
    // manifests, only by explicitly registering for it with
    // Context.registerReceiver()."
    //
    // Nothing anywhere emitted it, so a clock driven by TIME_TICK froze at the
    // minute it launched -- silently, with no error. 21 corpus apps embed the
    // action, deskclock among them, which makes this pixel-falsifiable on the
    // fleet: the minute digit must advance.
    //
    // The manifest exclusion is AOSP-mandated, not an optimisation, so this
    // fans out to the DYNAMIC registry only and never touches the manifest path.
    private static volatile boolean sTickerStarted;

    private void macapkStartMinuteTicker() {
        if (sTickerStarted) return;
        synchronized (Context.class) {
            if (sTickerStarted) return;
            sTickerStarted = true;
        }
        final android.os.Handler h;
        try { h = new android.os.Handler(getMainLooper()); }
        catch (Throwable t) { sTickerStarted = false; return; }
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() { @Override public void run() {
            try { macapkSendToDynamicOnly(new Intent(Intent.ACTION_TIME_TICK)); }
            catch (Throwable ignored) { }
            try { h.postDelayed(tick[0], macapkMsToNextMinute()); } catch (Throwable ignored) { }
        } };
        // Align to the wall-clock minute boundary, as the system does -- a clock
        // that ticks 43 seconds late is visibly wrong even though it ticks.
        try { h.postDelayed(tick[0], macapkMsToNextMinute()); }
        catch (Throwable t) { sTickerStarted = false; }
    }

    private static long macapkMsToNextMinute() {
        long now = System.currentTimeMillis();
        long ms = 60_000L - (now % 60_000L);
        return (ms < 1_000L) ? ms + 60_000L : ms;   // never schedule a near-zero delay
    }

    /** Fan out to the DYNAMIC registry only. ACTION_TIME_TICK must never reach a
     *  manifest-declared receiver (AOSP forbids it), so this deliberately does
     *  not call the manifest path that sendBroadcast uses. */
    private void macapkSendToDynamicOnly(Intent intent) {
        if (intent == null) return;
        for (Object[] e : sReceivers) {
            android.content.IntentFilter f = (android.content.IntentFilter) e[1];
            boolean match;
            try { match = f.match(intent.getAction(), null, null, intent.getData(), null, "macapk") >= 0; }
            catch (Throwable t) {
                try { match = f.matchAction(intent.getAction()); } catch (Throwable t2) { match = false; }
            }
            if (!match) continue;
            android.content.BroadcastReceiver rcv = (android.content.BroadcastReceiver) e[0];
            // Deliver with the REGISTRANT'S context, not the one that happened to
            // start the ticker. The ticker's Runnable is re-posted forever, so
            // capturing a Context here pinned the FIRST registrant's Activity for
            // the process lifetime and then handed that (possibly destroyed)
            // Activity to every later receiver too. deskclock's AnalogClock
            // registers TIME_TICK from View.getContext(), i.e. the Activity.
            Context rctx = (e.length > 2 && e[2] instanceof Context) ? (Context) e[2] : this;
            final android.content.BroadcastReceiver.PendingResult prev = rcv.getPendingResult();
            try { rcv.setPendingResult(new android.content.BroadcastReceiver.PendingResult(
                    0, null, null, android.content.BroadcastReceiver.PendingResult.TYPE_REGISTERED,
                    false, false, 0, android.os.Process.myUid(), getPackageName())); }
            catch (Throwable ignored) { }
            try { rcv.onReceive(rctx, intent); }
            catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Context",
                    "receiver " + rcv.getClass().getName() + " threw on "
                    + intent.getAction() + ": " + t);
            }
            finally { try { rcv.setPendingResult(prev); } catch (Throwable ignored) { } }
        }
    }

    /** The ACTION_BATTERY_CHANGED sticky a device always has, built from the
     *  REAL host battery (BatteryManager reads `pmset -g batt`). Extras follow
     *  AOSP's BatteryService: level/scale/status/present/health/plugged. */
    private Intent macapkBatteryIntent() {
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        try {
            Object o = getSystemService(BATTERY_SERVICE);
            android.os.BatteryManager bm = (android.os.BatteryManager) o;
            int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            int status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS);
            boolean charging = bm.isCharging();
            if (level < 0) level = 100;
            i.putExtra(android.os.BatteryManager.EXTRA_LEVEL, level);
            i.putExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            i.putExtra(android.os.BatteryManager.EXTRA_STATUS, status);
            i.putExtra(android.os.BatteryManager.EXTRA_PRESENT, true);
            i.putExtra(android.os.BatteryManager.EXTRA_HEALTH, 2 /* BATTERY_HEALTH_GOOD */);
            // AOSP EXTRA_PLUGGED: 0 when on battery, else the source. A Mac on
            // mains is AC, which is the honest answer and the one apps branch on.
            i.putExtra("plugged", charging ? 1 /* BATTERY_PLUGGED_AC */ : 0);
            i.putExtra(android.os.BatteryManager.EXTRA_BATTERY_LOW, level <= 15);
            i.putExtra("technology", "Li-ion");
        } catch (Throwable ignored) { }
        return i;
    }

    public void sendBroadcast(Intent intent) {
        if (intent == null) return;
        // AOSP: an EXPLICIT broadcast (component set) is delivered to exactly
        // that component's manifest-declared receiver and NEVER consults the
        // dynamic registry -- ActivityManagerService.broadcastIntentLocked
        // builds a component intent's receiver list by resolving the component
        // alone. Load-bearing for PackageInstaller session status callbacks:
        // stores wrap PendingIntent.getBroadcast(Intent(context,
        // XReceiver.class)) where XReceiver is manifest-declared with
        // exported=false and NO intent-filter (Aurora's
        // InstallerStatusReceiver, read from its manifest 2026-08-12), so
        // component dispatch is the ONLY way such a receiver can ever fire --
        // the filter loop below cannot match a receiver that has no filter.
        if (intent.getComponent() != null) {
            macapkDeliverToManifestReceiver(intent);
            return;
        }
        // A package-targeted broadcast must not reach a DYNAMIC receiver of a
        // different package either. The manifest path below already returns
        // early on this test; the dynamic loop did not, and Mac.apk hosts
        // exactly one package, so `setPackage(somethingElse)` should deliver to
        // NOTHING rather than to whatever dynamic filter happens to match.
        // Concretely this is the FCM routing field: McsClient dispatches
        // setPackage(category), and a dynamically-registered c2dm RECEIVE
        // receiver would otherwise see another app's push.
        String targeted = intent.getPackage();
        if (targeted != null) {
            String selfPkgName = null;
            try { selfPkgName = getPackageName(); } catch (Throwable ignored) {}
            // Same direction as the manifest path below: a null self DROPS. The
            // two guards answer the same question and must not disagree, and
            // dropping is the safe side (over-delivery is the failure with
            // consequences, per that path's own note).
            if (!targeted.equals(selfPkgName)) return;
        }
        for (Object[] e : sReceivers) {
            android.content.IntentFilter f = (android.content.IntentFilter) e[1];
            // Full AOSP filter match (action AND data/scheme/type AND categories),
            // not action-only: a data- or category-constrained dynamic receiver
            // must NOT fire for an intent whose data/category does not match
            // (matchAction-only was over-delivery). IntentFilter.match returns
            // >= 0 on a match, a NO_MATCH_* (< 0) otherwise. Fall back to the old
            // action-only test if match() ever throws, so nothing regresses.
            boolean match;
            try {
                match = f.match(getContentResolver(), intent, true, null) >= 0;
            } catch (Throwable t) {
                String action = intent.getAction();
                match = (action == null) ? (f.countActions() == 0) : f.matchAction(action);
            }
            if (match) {
                // AOSP installs the PendingResult before EVERY dispatch, ordered
                // or not: LoadedApk.ReceiverDispatcher.Args does
                // `receiver.setPendingResult(this)` unconditionally
                // (android14-release LoadedApk.java:1801). Only the ordered
                // FINAL receiver got one here, so goAsync() returned null on
                // every other dispatch -- and a null deref inside the app's own
                // receiver is then SWALLOWED by the catch below, leaving the
                // receiver's deferred half silently un-run. 15 apps in the
                // corpus call goAsync().
                android.content.BroadcastReceiver rcv = (android.content.BroadcastReceiver) e[0];
                // Save the OUTER result: a receiver whose onReceive sends a
                // broadcast that matches its own filter re-enters here, and a
                // nested `finally` that nulled the field would leave the outer
                // frame's goAsync() returning null again -- reintroducing the
                // exact NPE this install exists to prevent. AOSP cannot hit this
                // because its dispatch is handler-serialized with one fresh Args
                // per delivery; ours is inline, so it must save and restore.
                // AOSP registerReceiver, verbatim: "The receiver will be called
                // with any broadcast Intent that matches filter, IN THE MAIN
                // APPLICATION THREAD" -- and the 4-arg form runs it "in the
                // context of scheduler ... If null, the main thread of the
                // process will be used."
                //
                // We ran it INLINE on the caller's thread, and that has a traced
                // victim: AlarmManager's POOL thread -> PendingIntent.send() ->
                // sendBroadcast() -> onReceive() OFF MAIN, where androidx
                // LiveData.setValue throws IllegalStateException and the catch
                // below swallows it. 40 of 63 corpus APKs call AlarmManager.set*
                // and 30 reference PendingIntent.getBroadcast.
                //
                // The tree already knew this rule one file away: DownloadManager
                // hops to the main looper with the comment "Broadcast receivers
                // run on the main thread on Android; delivering straight from
                // this worker would run app code that expects the UI thread off
                // it." The manifest path here already posts to main too -- plain
                // sendBroadcast was the last inline path.
                //
                // Deliver with the REGISTRANT'S own Context (AOSP's
                // ReceiverDispatcher holds mContext per registration), not
                // whichever Context happened to make the call.
                final Context rctx = (e.length > 2 && e[2] instanceof Context) ? (Context) e[2] : this;
                final android.os.Handler sched =
                        (e.length > 3 && e[3] instanceof android.os.Handler) ? (android.os.Handler) e[3] : null;
                final Intent deliver = intent;
                Runnable dispatch = new Runnable() { @Override public void run() {
                    final android.content.BroadcastReceiver.PendingResult prev = rcv.getPendingResult();
                    try { rcv.setPendingResult(new android.content.BroadcastReceiver.PendingResult(
                            0, null, null, android.content.BroadcastReceiver.PendingResult.TYPE_REGISTERED,
                            false, false, 0, android.os.Process.myUid(), getPackageName())); }
                    catch (Throwable ignored) { }
                    try { rcv.onReceive(rctx, deliver); }
                    catch (Throwable t) {
                        com.macapk.loader.NativeLog.w("Context",
                            "registered receiver " + rcv.getClass().getName()
                            + " threw on " + deliver.getAction() + ": " + t);
                    }
                    finally { try { rcv.setPendingResult(prev); } catch (Throwable ignored) { } }
                } };
                boolean posted = false;
                if (sched != null) { try { sched.post(dispatch); posted = true; } catch (Throwable ignored) { } }
                else if (android.os.Looper.macapkHasMainThread()) {
                    try { new android.os.Handler(getMainLooper()).post(dispatch); posted = true; }
                    catch (Throwable ignored) { }
                }
                // No looper yet (early boot): inline is better than dropped.
                if (!posted) dispatch.run();
                // (The "a receiver that throws must NOT vanish" logging that used
                // to live here now runs inside the dispatch Runnable above, so a
                // throw is still absorbed and still logged -- the 2026-08-20
                // Aurora case where a silent throw left a store row stuck on
                // "Installing" forever stays covered.)
            }
        }
        // ...AND the MANIFEST-declared receivers whose <intent-filter> matches.
        // AOSP delivers an implicit broadcast to BOTH lists: ActivityManagerService
        // .broadcastIntentLocked builds `registeredReceivers` from the dynamic
        // registry and `receivers` from collectReceiverComponents (which calls
        // PackageManagerInternal.queryIntentReceivers with no target-SDK gate of
        // its own, android14-release AMS.java:14132-14147), then merges the two.
        //
        // WHY THIS IS NOT OVER-DELIVERY under the O+ implicit-broadcast rule:
        // that rule is a BACKGROUND-EXECUTION limit, applied per receiver via
        // getAppStartModeLOSP, and that method returns APP_START_MODE_NORMAL
        // whenever the target uid has a live, non-idle UidRecord (AMS.java:6058-
        // 6117 -- the `alwaysRestrict || uidRec.isIdle()` branch is what leads to
        // the restricted answer). Mac.apk hosts exactly one app, in this process,
        // in the foreground, so on a device it would never be idle and its
        // manifest receivers would fire. Dropping them here was our gap, not
        // Android's behaviour.
        //
        // Load-bearing for the install-completion contract: a store is free to
        // declare its package-event receiver in the manifest rather than
        // registering it at runtime (Aurora happens to register dynamically;
        // ACTION_MY_PACKAGE_REPLACED is manifest-only in BOTH stores we have),
        // and before this it could never hear one. See
        // PackageInstallerBackend.broadcastPackageInstalled.
        macapkDeliverToMatchingManifestReceivers(intent);
    }

    /** Manifest-declared receivers of the hosted package whose `<intent-filter>`
     *  matches a component-less intent. Resolution is PackageManager's
     *  queryBroadcastReceivers, so the filter semantics are the same ones
     *  queryIntentActivities already uses and there is no second matcher to
     *  drift. */
    private void macapkDeliverToMatchingManifestReceivers(Intent intent) {
        java.util.List<android.content.pm.ResolveInfo> hits;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (pm == null) return;
            hits = pm.queryBroadcastReceivers(intent, 0);
        } catch (Throwable t) {
            return;   // no manifest / no PM yet: same as a device with nothing declared
        }
        if (hits == null || hits.isEmpty()) return;
        // AOSP: `intent.setPackage(x)` restricts delivery to package x. When we
        // cannot establish our own name the safe direction is to DROP -- the
        // sender named a specific target, and delivering to an unidentified one
        // is the failure mode with consequences. (This is how AOSP's
        // ACTION_MY_PACKAGE_REPLACED, which is targeted and carries no data,
        // stays scoped to the app it is about.)
        String only = intent.getPackage();
        String selfPkg = null;
        try { selfPkg = getPackageName(); } catch (Throwable ignored) {}
        if (only != null && !only.equals(selfPkg)) return;
        // AOSP's PACKAGE_ADDED self-exclusion, verbatim in intent:
        // "A special case for PACKAGE_ADDED: do not allow the package being
        // added to see this broadcast. This prevents them from using this as a
        // back door to get run as soon as they are installed."
        // (AMS.java:15060-15095; it applies to PACKAGE_ADDED, PACKAGE_RESTARTED
        // and PACKAGE_DATA_CLEARED, and only to the MANIFEST list.) It never
        // fires for a store installing something else, which is the whole point
        // of keeping it: the one case it does fire for is an app that somehow
        // reinstalls itself, and there a device would stay silent.
        String skipPackage = null;
        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                || Intent.ACTION_PACKAGE_RESTARTED.equals(action)
                || Intent.ACTION_PACKAGE_DATA_CLEARED.equals(action)) {
            try {
                android.net.Uri d = intent.getData();
                if (d != null) skipPackage = d.getSchemeSpecificPart();
            } catch (Throwable ignored) {}
        }
        for (android.content.pm.ResolveInfo ri : hits) {
            if (ri == null || ri.activityInfo == null || ri.activityInfo.name == null) continue;
            String owner = ri.activityInfo.packageName;
            if (skipPackage != null && skipPackage.equals(owner)) continue;
            macapkRunManifestReceiver(ri.activityInfo.name, intent);
        }
    }
    /** Instantiate the hosted app's manifest-declared receiver named by the
     *  intent's component and deliver on the MAIN thread (AOSP runs manifest
     *  receivers there; a fresh instance per broadcast is also AOSP's
     *  behavior). Only the hosted package can receive: a component in any
     *  other package would, on a device, be delivered into that app's own
     *  process, and an app that is not running here has no process -- so the
     *  faithful answer for foreign components is a silent drop, same as a
     *  device with the target app not installed.
     *
     *  Leniency note, NARROWED at v1.1.0. Delivery used to be keyed purely on
     *  "the class loads from the app's classloader and is a BroadcastReceiver",
     *  which bypassed the parsed <receiver> set entirely -- so an
     *  android:enabled="false" receiver received here while the SAME receiver
     *  was correctly skipped by the filter-matched sibling
     *  (macapkDeliverToMatchingManifestReceivers -> queryBroadcastReceivers,
     *  which has always tested the flag). Two paths, one rule, opposite
     *  answers: an explicit-component broadcast reached a receiver the implicit
     *  one would not. 124 corpus receivers across 31 apps are enabled=false.
     *
     *  The leniency that remains is exactly the case it was introduced for --
     *  an UNDECLARED receiver still gets delivery, so a manifest-parse gap
     *  cannot eat a store's install-status callback. What is no longer lenient
     *  is a receiver the manifest DECLARES and DISABLES: that is an explicit
     *  statement by the app, not a gap in our parser, and a device drops it.
     *  AOSP is stricter than both (undeclared components do not resolve at
     *  all); the residual cost is unchanged -- a component the app ships but
     *  never declared fires here and would not on a device. */
    private void macapkDeliverToManifestReceiver(Intent intent) {
        android.content.ComponentName cn = intent.getComponent();
        String selfPkg = null;
        try { selfPkg = getPackageName(); } catch (Throwable ignored) {}
        if (selfPkg != null && cn.getPackageName() != null
                && !selfPkg.equals(cn.getPackageName())) {
            return;   // foreign package: not running in this process; dropped
        }
        String cls = cn.getClassName();
        if (cls == null) return;
        // Manifest-relative names (".Foo") normalize against the package.
        final String className = cls.startsWith(".") && cn.getPackageName() != null
                ? cn.getPackageName() + cls : cls;
        if (macapkIsDeclaredButDisabledReceiver(className)) {
            com.macapk.loader.NativeLog.i("Context",
                "manifest broadcast to " + className
                + " dropped: declared android:enabled=false");
            return;
        }
        macapkRunManifestReceiver(className, intent);
    }

    /** True only when the manifest DECLARES this receiver AND the effective
     *  gate says it is disabled -- see the leniency note on
     *  macapkDeliverToManifestReceiver for why "declared but disabled" and
     *  "undeclared" must be told apart rather than both treated as absent.
     *
     *  Two probes, in this order: getReceiverInfo with
     *  MATCH_DISABLED_COMPONENTS throws only for a genuinely undeclared
     *  component (stay lenient); the same call without the flag throws when a
     *  declared component is disabled (drop). Same idiom as
     *  ServiceRegistry.macapkIsDeclaredButDisabled, deliberately -- the two
     *  dispatch paths should not have two different ways of asking one
     *  question. */
    private boolean macapkIsDeclaredButDisabledReceiver(String className) {
        if (className == null || className.isEmpty()) return false;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (pm == null) return false;
            android.content.ComponentName cn =
                new android.content.ComponentName(getPackageName(), className);
            try {
                pm.getReceiverInfo(cn,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (android.content.pm.PackageManager.NameNotFoundException undeclared) {
                return false;
            }
            try {
                pm.getReceiverInfo(cn, 0);
                return false;
            } catch (android.content.pm.PackageManager.NameNotFoundException disabled) {
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Instantiate the hosted app's receiver class and run onReceive on the
     *  MAIN thread (AOSP runs manifest receivers there, and a fresh instance
     *  per broadcast is also AOSP's behavior). ONE implementation for both the
     *  explicit-component path and the filter-matched path -- two copies of
     *  "how do we actually invoke a manifest receiver" is how one of them ends
     *  up on the wrong thread. */
    private void macapkRunManifestReceiver(final String className, Intent intent) {
        if (className == null || className.isEmpty()) return;
        final Intent delivered = intent;
        Runnable run = () -> {
            try {
                ClassLoader cl = com.macapk.loader.ActivityHost.macapkAppClassLoader();
                if (cl == null) cl = Context.class.getClassLoader();
                Class<?> c = Class.forName(className, true, cl);
                if (!android.content.BroadcastReceiver.class.isAssignableFrom(c)) return;
                android.content.BroadcastReceiver r = (android.content.BroadcastReceiver)
                        c.getDeclaredConstructor().newInstance();
                // Same PendingResult contract on the manifest path (AOSP's
                // ReceiverDispatcher does not distinguish).
                try { r.setPendingResult(new android.content.BroadcastReceiver.PendingResult(
                        0, null, null, android.content.BroadcastReceiver.PendingResult.TYPE_COMPONENT,
                        false, false, 0, 0, getPackageName())); }
                catch (Throwable ignored) { }
                try { r.onReceive(Context.this, delivered); }
                finally { try { r.setPendingResult(null); } catch (Throwable ignored) { } }
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Context",
                        "manifest broadcast to " + className + " failed: " + t);
            }
        };
        try {
            new android.os.Handler(getMainLooper()).post(run);
        } catch (Throwable t) {
            run.run();   // main looper unavailable (early boot): inline
        }
    }

    public void sendBroadcast(Intent intent, String receiverPermission) { sendBroadcast(intent); }
    public void sendStickyBroadcast(Intent intent) {
        // AOSP retains the intent so a LATER registerReceiver replays it. We
        // previously only fanned it out to current receivers and kept nothing,
        // so anyone registering afterwards never learned the state.
        macapkPutSticky(intent);
        sendBroadcast(intent);
    }

    // ---- Cross-user / permission-list / sticky broadcast variants.
    //
    // Mac.apk has ONE user (UserManager reports a single profile and
    // UserHandle is always user 0) and dispatches broadcasts in-process to the
    // app's own registered receivers, which by definition hold every
    // permission the app declares. So the *AsUser and *WithMultiplePermissions
    // forms cannot select a different receiver set than the plain form -- they
    // are the SAME delivery, not a weakened one, and each is routed through
    // the existing method so it cannot drift.
    public void sendBroadcastAsUser(Intent intent, android.os.UserHandle user) {
        sendBroadcast(intent);
    }
    public void sendBroadcastAsUser(Intent intent, android.os.UserHandle user,
                                    String receiverPermission) {
        sendBroadcast(intent, receiverPermission);
    }
    public void sendBroadcastWithMultiplePermissions(Intent intent, String[] receiverPermissions) {
        sendBroadcast(intent);
    }
    public void sendStickyBroadcastAsUser(Intent intent, android.os.UserHandle user) {
        sendStickyBroadcast(intent);
    }
    /** AOSP API-31: `options` is a BroadcastOptions.toBundle() payload; see
     *  sendBroadcast(Intent,String,Bundle) below for why none of its knobs
     *  apply here. */
    public void sendStickyBroadcast(Intent intent, android.os.Bundle options) {
        sendStickyBroadcast(intent);
    }

    /** AOSP removeStickyBroadcast: drop a previously-sent sticky so a later
     *  registerReceiver no longer replays it.
     *
     *  These were no-ops, justified by a comment saying our sticky send
     *  "RETAINS NOTHING, so there is nothing for a remove to erase". That
     *  justification died the moment retention landed (v1.0.1112), and the
     *  comment itself said so: "If sticky retention is ever implemented, these
     *  must be implemented with it." Left alone they became a LIVE bug -- a
     *  sticky that can never be withdrawn. */
    public void removeStickyBroadcast(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        sStickies.remove(intent.getAction());
    }
    public void removeStickyBroadcastAsUser(Intent intent, android.os.UserHandle user) {
        removeStickyBroadcast(intent);
    }
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) { sendBroadcast(intent); }
    /** AOSP API-26 overload. `options` is a BroadcastOptions.toBundle()
     *  payload: temp-allowlist duration, background-activity-start grants,
     *  deferral policy. None of those knobs exist in a single-process host
     *  with no background-execution limits, so delivery is identical to the
     *  2-arg form. Declaring it is what matters -- callers compiled against
     *  API 26+ invoke this exact descriptor and got NoSuchMethodError. */
    public void sendBroadcast(Intent intent, String receiverPermission,
                              android.os.Bundle options) {
        sendBroadcast(intent, receiverPermission);
    }
    /** AOSP 7-arg ordered broadcast: the registered receivers run in
     *  priority order and then the caller-supplied resultReceiver runs as
     *  the final receiver in the chain, dispatched on `scheduler` (or the
     *  main thread when scheduler is null). Our sendBroadcast already fans
     *  out to every matching dynamic receiver; the piece missing entirely
     *  was the final-receiver callback, so a caller that used the ordered
     *  form specifically to collect a result never heard back.
     *
     *  initialCode / initialData / initialExtras ARE seeded now:
     *  BroadcastReceiver carries real result state (a PendingResult), so the
     *  final receiver is handed one pre-loaded with the caller's initial
     *  values and can read them through getResultCode()/getResultData()/
     *  getResultExtras().
     *
     *  ⚠ STATED PARTIAL. What is still missing is the CHAIN: the fan-out above
     *  (sendBroadcast) invokes every matching receiver without installing that
     *  PendingResult on each of them in turn, so an intermediate receiver's
     *  setResult() is not visible to the final receiver, and abortBroadcast()
     *  does not stop later receivers. Threading it means restructuring the
     *  shared delivery loop in sendBroadcast, which is a separate change; this
     *  one moves the caller-seeded half from "impossible" to "works" without
     *  touching that loop. */
    /** AOSP API-30 -- ordered broadcast with a BroadcastOptions bundle but no
     *  result receiver. */
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
                                     android.os.Bundle options) {
        sendOrderedBroadcast(intent, receiverPermission);
    }

    /** AOSP API-30 -- the 7-arg ordered form plus BroadcastOptions. */
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
                                     android.os.Bundle options,
                                     android.content.BroadcastReceiver resultReceiver,
                                     android.os.Handler scheduler,
                                     int initialCode, String initialData,
                                     android.os.Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                             initialCode, initialData, initialExtras);
    }

    /** AOSP API-33 -- adds an appOp string alongside the permission. Mac.apk
     *  has no AppOps enforcement (there is no AppOpsManager gate on delivery),
     *  so the appOp cannot filter a receiver out. */
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
                                     String receiverAppOp,
                                     android.content.BroadcastReceiver resultReceiver,
                                     android.os.Handler scheduler,
                                     int initialCode, String initialData,
                                     android.os.Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                             initialCode, initialData, initialExtras);
    }

    /** AOSP API-34 -- the widest ordered form: initialCode first, then BOTH a
     *  permission and an appOp, then the result receiver, then the initial
     *  data/extras and a BroadcastOptions bundle. Note the ARGUMENT ORDER
     *  differs from the other overloads (initialCode is second, not sixth);
     *  that is AOSP's, and getting it wrong would silently swap the initial
     *  data with the permission. */
    public void sendOrderedBroadcast(Intent intent, int initialCode, String receiverPermission,
                                     String receiverAppOp,
                                     android.content.BroadcastReceiver resultReceiver,
                                     android.os.Handler scheduler, String initialData,
                                     android.os.Bundle initialExtras,
                                     android.os.Bundle options) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                             initialCode, initialData, initialExtras);
    }

    /** AOSP sendOrderedBroadcastAsUser -- single user here, see the *AsUser
     *  note above. */
    public void sendOrderedBroadcastAsUser(Intent intent, android.os.UserHandle user,
                                           String receiverPermission,
                                           android.content.BroadcastReceiver resultReceiver,
                                           android.os.Handler scheduler,
                                           int initialCode, String initialData,
                                           android.os.Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                             initialCode, initialData, initialExtras);
    }

    /** AOSP sendStickyOrderedBroadcast / ...AsUser (both @Deprecated). Ordered
     *  delivery to the already-registered receivers, AND retention so a later
     *  registerReceiver replays it -- the sticky half was previously skipped on
     *  the reasoning that nothing retained anything, which stopped being true
     *  at v1.0.1112 and left this method inconsistent with its non-ordered
     *  sibling. */
    @Deprecated
    public void sendStickyOrderedBroadcast(Intent intent,
                                           android.content.BroadcastReceiver resultReceiver,
                                           android.os.Handler scheduler,
                                           int initialCode, String initialData,
                                           android.os.Bundle initialExtras) {
        macapkPutSticky(intent);
        sendOrderedBroadcast(intent, null, resultReceiver, scheduler,
                             initialCode, initialData, initialExtras);
    }
    @Deprecated
    public void sendStickyOrderedBroadcastAsUser(Intent intent, android.os.UserHandle user,
                                                 android.content.BroadcastReceiver resultReceiver,
                                                 android.os.Handler scheduler,
                                                 int initialCode, String initialData,
                                                 android.os.Bundle initialExtras) {
        sendStickyOrderedBroadcast(intent, resultReceiver, scheduler,
                                   initialCode, initialData, initialExtras);
    }

    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
                                     android.content.BroadcastReceiver resultReceiver,
                                     android.os.Handler scheduler,
                                     int initialCode, String initialData,
                                     android.os.Bundle initialExtras) {
        sendBroadcast(intent, receiverPermission);
        if (resultReceiver == null) return;
        final android.content.BroadcastReceiver finalReceiver = resultReceiver;
        final Intent delivered = intent;
        // AOSP's ordered dispatch hands the final receiver a PendingResult
        // carrying whatever the chain left in it, seeded from the caller's
        // initial values. TYPE_REGISTERED because this receiver was supplied
        // directly rather than resolved from a manifest component; ordered=true
        // so setResult() on it is legal (checkSynchronousHint).
        final android.content.BroadcastReceiver.PendingResult pending =
                new android.content.BroadcastReceiver.PendingResult(
                        initialCode, initialData, initialExtras,
                        android.content.BroadcastReceiver.PendingResult.TYPE_REGISTERED,
                        /*ordered=*/ true, /*sticky=*/ false,
                        android.os.UserHandle.USER_SYSTEM,
                        android.os.Process.myUid(), getPackageName());
        Runnable run = new Runnable() {
            @Override public void run() {
                finalReceiver.setPendingResult(pending);
                try { finalReceiver.onReceive(Context.this, delivered); }
                catch (Throwable t) {
                    // Same reasoning as the registered-receiver dispatch above:
                    // a result receiver that throws used to disappear without a
                    // trace, which is how an install confirmation goes missing
                    // with an empty log.
                    com.macapk.loader.NativeLog.w("Context",
                        "result receiver " + finalReceiver.getClass().getName()
                        + " threw on " + delivered.getAction() + ": " + t);
                } finally {
                    // AOSP clears the pending result once the receiver returns
                    // (unless it took it with goAsync()), so a later
                    // getResultCode() on the same receiver object does not read
                    // a stale result from a finished broadcast.
                    if (finalReceiver.getPendingResult() == pending) {
                        pending.finish();
                        finalReceiver.setPendingResult(null);
                    }
                }
            }
        };
        // AOSP documents the ordered send as asynchronous ("returns
        // immediately, and you will continue executing while the receivers
        // are run"), so post rather than calling inline.
        android.os.Handler h = (scheduler != null) ? scheduler
                                                   : new android.os.Handler(getMainLooper());
        h.post(run);
    }

    public Intent registerReceiver(android.content.BroadcastReceiver receiver,
                                   android.content.IntentFilter filter) {
        return macapkRegisterReceiver(receiver, filter);
    }
    /** AOSP API-26+ -- 3-arg overload with flags (e.g.
     *  RECEIVER_NOT_EXPORTED). F-Droid + ContextCompat call this. */
    public Intent registerReceiver(android.content.BroadcastReceiver receiver,
                                   android.content.IntentFilter filter,
                                   int flags) { return macapkRegisterReceiver(receiver, filter); }
    /** AOSP API-1+ 4-arg variant with broadcast permission and Handler.
     *  ContextCompat probes this via reflection to gate flag-arg vs
     *  permission-arg overloads. */
    public Intent registerReceiver(android.content.BroadcastReceiver receiver,
                                   android.content.IntentFilter filter,
                                   String broadcastPermission,
                                   android.os.Handler scheduler) { return macapkRegisterReceiver(receiver, filter, scheduler); }
    /** AOSP API-26+ 5-arg variant. */
    public Intent registerReceiver(android.content.BroadcastReceiver receiver,
                                   android.content.IntentFilter filter,
                                   String broadcastPermission,
                                   android.os.Handler scheduler,
                                   int flags) { return macapkRegisterReceiver(receiver, filter, scheduler); }
    /** Receiver-export flags for the 3-arg / 5-arg variants. AOSP. */
    public static final int RECEIVER_EXPORTED     = 0x2;
    public static final int RECEIVER_NOT_EXPORTED = 0x4;
    public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1;

    public void unregisterReceiver(android.content.BroadcastReceiver receiver) {
        if (receiver == null) return;
        for (Object[] e : sReceivers) if (e[0] == receiver) sReceivers.remove(e);
    }

    /** AOSP LoadedApk.removeContextRegistrations (android14 :1529-1569), run by
     *  ActivityThread.handleDestroyActivity -> ContextImpl.scheduleFinalCleanup:
     *  every receiver a destroyed activity registered and never unregistered is
     *  unregistered ("has leaked IntentReceiver"), and every service it bound
     *  and never unbound is unbound ("has leaked ServiceConnection"). v1.0.1643:
     *  ours kept them forever -- the entry pins the activity, and a leaked
     *  receiver kept running against torn-down views and fragments. */
    public static void macapkRemoveContextRegistrations(Context dead) {
        if (dead == null) return;
        String who = dead.getClass().getName();
        for (Object[] e : sReceivers) {   // copy-on-write: removing while iterating is safe
            if (e.length > 2 && e[2] instanceof Context && macapkWraps((Context) e[2], dead)) {
                sReceivers.remove(e);
                try {
                    com.macapk.loader.NativeLog.w("Context", "Activity " + who
                        + " has leaked IntentReceiver " + e[0]
                        + " that was originally registered here. Are you missing a call to unregisterReceiver()?");
                } catch (Throwable ignored) {}
            }
        }
        synchronized (sBoundConnections) {
            java.util.Iterator<java.util.Map.Entry<ServiceConnection, Context>> it =
                sBoundBy.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<ServiceConnection, Context> en = it.next();
                if (macapkWraps(en.getValue(), dead)) {
                    sBoundConnections.remove(en.getKey());
                    it.remove();
                    try {
                        com.macapk.loader.NativeLog.w("Context", "Activity " + who
                            + " has leaked ServiceConnection " + en.getKey()
                            + " that was originally bound here");
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    /** Whether `c` is `target` or a ContextWrapper chain around it. */
    private static boolean macapkWraps(Context c, Context target) {
        for (int i = 0; c != null && i < 16; i++) {
            if (c == target) return true;
            if (!(c instanceof ContextWrapper)) return false;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return false;
    }

    /** AOSP API-14+: registers a ComponentCallbacks for memory-pressure
     *  / configuration-change notifications. v1.0.464 (subsystem-04): AOSP's
     *  ContextImpl.registerComponentCallbacks forwards to the associated
     *  Application (mPackageInfo.getApplication()) so the process-wide
     *  config-change / trim-memory dispatch reaches the listener. We mirror
     *  that: a callback registered on ANY Context lands in the Application's
     *  list, where Application.dispatchConfigurationChanged/dispatchTrimMemory
     *  fan it out. (Application itself overrides these to use its own list
     *  directly, so this base impl only handles non-Application contexts.)
     *  Before this, the no-op silently dropped Compose's image-vector cache
     *  and Glide's memory-trim listener registrations. */
    public void registerComponentCallbacks(android.content.ComponentCallbacks cb) {
        android.app.Application app = android.app.Application.macapk_get();
        if (app != null && app != this) app.registerComponentCallbacks(cb);
    }
    public void unregisterComponentCallbacks(android.content.ComponentCallbacks cb) {
        android.app.Application app = android.app.Application.macapk_get();
        if (app != null && app != this) app.unregisterComponentCallbacks(cb);
    }

    /** Live bindings, keyed by ServiceConnection identity (AOSP keys its
     *  ServiceDispatchers the same way, per (Context, ServiceConnection)).
     *  One connection may be bound to several components at once, hence the
     *  set of ComponentNames. */
    private static final java.util.Map<ServiceConnection, java.util.HashSet<ComponentName>>
        sBoundConnections = new java.util.IdentityHashMap<>();
    /** Which Context bound each live connection (guarded by sBoundConnections),
     *  so macapkRemoveContextRegistrations can unbind a destroyed activity's. */
    private static final java.util.Map<ServiceConnection, Context> sBoundBy =
        new java.util.IdentityHashMap<>();
    /** Connections whose bind is queued on the main looper and has not run yet
     *  (guarded by sBoundConnections). unbindService removes the entry, and the
     *  queued bind then does nothing. */
    private static final java.util.Map<ServiceConnection, Boolean> sPendingBinds =
        new java.util.IdentityHashMap<>();

    /** AOSP bindService. Once the target Service's onBind() returns, the
     *  IBinder is delivered through LoadedApk.ServiceDispatcher: connected()
     *  posts a RunConnection to the caller's Handler (or Executor) and
     *  doConnected() calls mConnection.onServiceConnected(name, binder). The
     *  callback is ASYNCHRONOUS -- it is never delivered inline out of
     *  bindService(). (frameworks/base/core/java/android/app/LoadedApk.java,
     *  ServiceDispatcher.connected / doConnected.)
     *
     *  This used to return false unconditionally and never touch the
     *  ServiceConnection, so every bound-service client sat waiting forever
     *  for a callback that could not arrive. Mac.apk is single-process, so
     *  there is nothing to fake: the IBinder that
     *  android.app.ServiceRegistry.bind() returns IS the Service's own
     *  onBind() result and the client can call straight through it.
     *
     *  Flags: AOSP only brings the Service up when BIND_AUTO_CREATE is set.
     *  ServiceRegistry exposes no "is this component already live" query, so
     *  we always ensure+bind. The effect is that the rare non-AUTO_CREATE
     *  bind creates the Service slightly earlier than Android would -- over
     *  eager, not a different answer. */
    public boolean bindService(Intent intent, ServiceConnection conn, int flags) {
        return bindService(intent, flags, null, conn);
    }
    /** AOSP bindServiceAsUser(Intent, ServiceConnection, int, UserHandle): bind
     *  on behalf of another user. It is a SystemApi guarded by
     *  INTERACT_ACROSS_USERS, and apps that call it (Signal does) are built to
     *  run as a device/profile owner where it is granted.
     *
     *  Mac.apk models exactly one user -- UserHandle is always the single
     *  running user, and `UserManager` reports one profile -- so a cross-user
     *  bind and a same-user bind are the same operation here. Delegating is
     *  therefore the faithful answer rather than a shortcut: there is no other
     *  user whose service we could fail to reach. The alternative, returning
     *  false, would tell the app the bind failed when the service it wanted is
     *  in fact running. */
    public boolean bindServiceAsUser(Intent intent, ServiceConnection conn, int flags,
                                     android.os.UserHandle user) {
        return bindService(intent, conn, flags);
    }
    /** AOSP API-29 overload: the ServiceConnection callbacks are delivered on
     *  the supplied Executor rather than the caller's main-thread Handler
     *  (LoadedApk.ServiceDispatcher.connected prefers mActivityExecutor when
     *  one was registered). */
    public boolean bindService(Intent intent, int flags,
                               java.util.concurrent.Executor executor,
                               ServiceConnection conn) {
        // AOSP ContextImpl.bindServiceCommon treats a null connection as an
        // IllegalArgumentException, not a quiet false.
        if (conn == null) throw new IllegalArgumentException("connection is null");
        if (intent == null) return false;
        // AOSP bindService only resolves and returns: CREATE_SERVICE and
        // BIND_SERVICE are main-looper messages that run after the caller has
        // unwound (ActivityThread.H handleCreateService / handleBindService). We
        // created + bound inline, so a Hilt @Singleton whose constructor binds
        // (media3 MediaController.buildAsync in Libro.fm) had the Service's
        // onCreate inject that singleton mid-construction and Dagger threw
        // "Scoped provider was invoked recursively" -> black screen (issue #7).
        // Resolve now (the return value), create + bind on the main looper.
        if (android.app.ServiceRegistry.BIND_ASYNC) {
            if (!android.app.ServiceRegistry.resolves(this, intent)) return false;
            final Intent fi = intent; final int ff = flags;
            final java.util.concurrent.Executor fe = executor; final ServiceConnection fc = conn;
            synchronized (sBoundConnections) { sPendingBinds.put(fc, Boolean.TRUE); }
            new android.os.Handler(getMainLooper()).post(() -> {
                // unbindService before the bind ran: AOSP drops the dispatcher,
                // so the connection never hears onServiceConnected.
                synchronized (sBoundConnections) { if (sPendingBinds.remove(fc) == null) return; }
                if (!macapkBindNow(fi, ff, fe, fc)) macapkDeliverNullBinding(fi, fe, fc);
            });
            return true;
        }
        // A bind to a Service that is inside its own onCreate (it binds to
        // itself, as a media3 MediaSessionService building a MediaController for
        // its own SessionToken does) is a BIND_SERVICE message queued behind
        // CREATE_SERVICE on a device, so onBind runs on the published instance
        // after onCreate returns. Binding inline here re-entered onCreate on a
        // second instance. The target is being created, so it resolved: true.
        if (android.app.ServiceRegistry.isCreating(intent)) {
            final Intent fi = intent; final int ff = flags;
            final java.util.concurrent.Executor fe = executor; final ServiceConnection fc = conn;
            new android.os.Handler(getMainLooper()).post(() -> macapkBindNow(fi, ff, fe, fc));
            return true;
        }
        return macapkBindNow(intent, flags, executor, conn);
    }

    /** AOSP LoadedApk.ServiceDispatcher.doConnected with a null binder: the
     *  component resolved but its onBind() returned null, so bindService said
     *  true and the connection hears onNullBinding (API 26+), never
     *  onServiceConnected. */
    private void macapkDeliverNullBinding(Intent intent,
                                          java.util.concurrent.Executor executor,
                                          final ServiceConnection conn) {
        ComponentName c = intent.getComponent();
        if (c == null) {
            try {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveService(intent, 0);
                if (ri != null && ri.serviceInfo != null && ri.serviceInfo.name != null) {
                    c = new ComponentName(ri.serviceInfo.packageName != null
                            ? ri.serviceInfo.packageName : getPackageName(), ri.serviceInfo.name);
                }
            } catch (Throwable ignored) {}
        }
        final ComponentName name = c;
        Runnable deliver = () -> {
            try { conn.onNullBinding(name); }
            catch (Throwable t) {
                try { com.macapk.loader.NativeLog.w("ctx", "onNullBinding(" + name + ") threw " + t); }
                catch (Throwable ignored) {}
            }
        };
        if (executor != null) executor.execute(deliver);
        else new android.os.Handler(getMainLooper()).post(deliver);
    }

    /** bindService's inline half: bring the Service up, onBind, record the
     *  connection and post onServiceConnected. */
    private boolean macapkBindNow(Intent intent, int flags,
                                  java.util.concurrent.Executor executor,
                                  ServiceConnection conn) {
        final android.os.IBinder binder = android.app.ServiceRegistry.bind(this, intent);
        if (binder == null) {
            // Two AOSP cases collapse to null here: the Intent names no
            // resolvable Service (AOSP returns false), or the Service's
            // onBind() legitimately returned null (AOSP returns true and calls
            // ServiceConnection.onNullBinding). ServiceRegistry cannot tell
            // them apart and our ServiceConnection has no onNullBinding to
            // call, so report the "couldn't find the service" answer: a client
            // told false takes its no-service path instead of blocking on a
            // callback that cannot come.
            return false;
        }
        // AOSP ALWAYS hands onServiceConnected a real ComponentName: the one the
        // intent RESOLVED to, not the one the caller happened to write. For an
        // implicit bind (setPackage + action, which is how every Firebase/GMS
        // client binds) getComponent() is null, so we used to deliver
        // onServiceConnected(null, binder). Most SDKs ignore the argument, but a
        // null there is a latent NPE for any that does not -- and it also
        // collapsed every implicit connection onto ONE key in sBoundConnections
        // below, so two different implicit binds looked like a duplicate and the
        // second was dropped by the "already have this one" check.
        // Resolving it became possible at v1.0.1177, when the PackageManager
        // learned to answer an implicit service action at all.
        ComponentName resolved = intent.getComponent();
        if (resolved == null) {
            try {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveService(intent, 0);
                if (ri != null && ri.serviceInfo != null && ri.serviceInfo.name != null) {
                    String rp = ri.serviceInfo.packageName != null
                              ? ri.serviceInfo.packageName : getPackageName();
                    resolved = new ComponentName(rp, ri.serviceInfo.name);
                }
            } catch (Throwable ignored) {
                // A context without a PackageManager keeps the old behaviour
                // rather than failing the bind: null is what it delivered before.
            }
        }
        final ComponentName name = resolved;
        synchronized (sBoundConnections) {
            java.util.HashSet<ComponentName> bound = sBoundConnections.get(conn);
            if (bound == null) {
                bound = new java.util.HashSet<>();
                sBoundConnections.put(conn, bound);
            }
            sBoundBy.putIfAbsent(conn, this);
            // AOSP doConnected() early-returns when this connection already
            // holds this binder ("Huh, already have this one").
            if (!bound.add(name)) return true;
        }
        final ServiceConnection target = conn;
        Runnable deliver = new Runnable() {
            @Override public void run() {
                try { target.onServiceConnected(name, binder); }
                catch (Throwable t) {
                    try {
                        com.macapk.loader.NativeLog.w("ctx",
                            "onServiceConnected(" + name + ") threw " + t);
                    } catch (Throwable ignored) {}
                }
            }
        };
        if (executor != null) executor.execute(deliver);
        else new android.os.Handler(getMainLooper()).post(deliver);
        return true;
    }
    /** AOSP unbindService drops the ServiceDispatcher for this connection and
     *  does NOT call onServiceDisconnected -- that fires only on unexpected
     *  service death (LoadedApk.ServiceDispatcher.doDeath). ContextImpl also
     *  throws IllegalArgumentException("Service not registered") for a
     *  connection it never handed out; we deliberately do not, because our
     *  bind path returns false (and therefore records nothing) in cases where
     *  AOSP would have returned true, so a faithful throw here would invent a
     *  crash real Android never produces. */
    public void    unbindService(ServiceConnection conn) {
        if (conn == null) return;
        synchronized (sBoundConnections) {
            sBoundConnections.remove(conn); sBoundBy.remove(conn); sPendingBinds.remove(conn);
        }
    }
    /** AOSP startService: instantiate (once) + drive the target Service's
     *  onCreate/onStartCommand. v1.0: was a no-op returning null, so
     *  Context.startService and PendingIntent.getService never ran the
     *  service. Now backed by android.app.ServiceRegistry (single-process
     *  Service host), keyed on the Intent's ComponentName -- generic, no
     *  package-name branch. Returns the started ComponentName or null
     *  when the Intent names no resolvable component. */
    public android.content.ComponentName startService(Intent service) {
        return android.app.ServiceRegistry.start(this, service, false);
    }
    /** AOSP API-26+: same contract as startService except the service
     *  is started in the foreground (allowed to bypass background
     *  execution limits for the first 5 seconds while it calls
     *  startForeground). Called by ContextCompat.startForegroundService
     *  inside WorkManager's Processor.startForeground when a Worker
     *  publishes ForegroundInfo via setForegroundAsync. We don't
     *  display notifications, so we just no-op-return null; the
     *  caller (WorkForegroundUpdater) completes its future
     *  regardless. */
    public android.content.ComponentName startForegroundService(Intent service) {
        return android.app.ServiceRegistry.start(this, service, true);
    }
    public boolean stopService(Intent service) {
        return android.app.ServiceRegistry.stop(service);
    }
    /** AOSP permission checks. ContextImpl funnels every one of these
     *  through a SINGLE per-UID granted-permission set:
     *    checkSelfPermission(p)          -> checkPermission(p, myPid, myUid)
     *    checkCallingOrSelfPermission(p) -> checkPermission(p, callingPid,
     *                                                       callingUid)
     *    checkCallingPermission(p)       -> PERMISSION_DENIED when the caller
     *                                       IS this process, else delegate
     *  and every one throws IllegalArgumentException on a null permission
     *  string. (frameworks/base/core/java/android/app/ContextImpl.java.)
     *
     *  These four used to ignore the permission argument and each return
     *  PERMISSION_GRANTED independently. The structure below restores the
     *  single decision point, so seeding a real grant table (the app's
     *  declared <uses-permission> set plus whatever host consent state
     *  exists) fixes every entry point at once instead of four places
     *  drifting apart. The decision itself is delegated to
     *  PackageManager.checkPermission, which is the tree's existing
     *  package-scoped answer, rather than a second hardcode living here. */
    public int  checkSelfPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        return checkPermission(permission, android.os.Process.myPid(),
                                           android.os.Process.myUid());
    }
    public int  checkCallingPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        // Same-process guard: an app must not be able to answer a permission
        // question about itself as though it were an IPC peer. Mac.apk's
        // Binder.getCallingPid() is inert (0) outside a transaction rather
        // than returning our own pid the way AOSP's does, so today this
        // delegates; once Binder reports the process identity this becomes
        // the AOSP DENIED with no further change here.
        int pid = android.os.Binder.getCallingPid();
        if (pid != android.os.Process.myPid()) {
            return checkPermission(permission, pid, android.os.Binder.getCallingUid());
        }
        return android.content.pm.PackageManager.PERMISSION_DENIED;
    }
    public int  checkCallingOrSelfPermission(String permission) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        return checkPermission(permission, android.os.Binder.getCallingPid(),
                                           android.os.Binder.getCallingUid());
    }
    public int  checkPermission(String permission, int pid, int uid) {
        if (permission == null) throw new IllegalArgumentException("permission is null");
        android.content.pm.PackageManager pm = getPackageManager();
        if (pm != null) {
            try { return pm.checkPermission(permission, getPackageName()); }
            catch (Throwable ignored) {}
        }
        // A bare Context constructed before the app's PackageManager exists.
        // Real Android has no such state (getPackageManager() is never null on
        // an attached Context), so keep the pre-existing answer rather than
        // inventing a denial that no grant table decided.
        return android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    /** AOSP enforce*: run the matching check and raise SecurityException when
     *  it comes back denied. These were no-ops, which meant they could never
     *  enforce anything even once the checks start answering honestly. */
    public void enforcePermission(String permission, int pid, int uid, String message) {
        macapkEnforce(permission, checkPermission(permission, pid, uid), false, uid, message);
    }
    public void enforceCallingOrSelfPermission(String permission, String message) {
        macapkEnforce(permission, checkCallingOrSelfPermission(permission), true,
                      android.os.Binder.getCallingUid(), message);
    }
    /** AOSP ContextImpl.enforceCallingPermission -- the CALLING-only variant
     *  (it does NOT fall back to this process's own grant, which is the whole
     *  point: a service uses it to reject a caller that lacks a permission the
     *  service itself holds). Routed through checkCallingPermission so it
     *  cannot diverge from it. */
    public void enforceCallingPermission(String permission, String message) {
        macapkEnforce(permission, checkCallingPermission(permission), false,
                      android.os.Binder.getCallingUid(), message);
    }
    /** Message text mirrors ContextImpl.enforce() verbatim so an app that
     *  parses or logs the SecurityException sees what Android produces. */
    private static void macapkEnforce(String permission, int resultOfCheck,
                                      boolean selfToo, int uid, String message) {
        if (resultOfCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                (message != null ? (message + ": ") : "")
                + (selfToo ? "Neither user " + uid + " nor current process has "
                           : "uid " + uid + " does not have ")
                + permission
                + ".");
        }
    }

    public Context createPackageContext(String packageName, int flags) { return this; }

    // ---- Wallpaper (all @Deprecated upstream; the replacement is
    // WallpaperManager, which these delegate to exactly as AOSP's ContextImpl
    // does -- `getWallpaperManager().getDrawable()` and friends. Routing
    // through getSystemService() means the two entry points cannot disagree
    // about what the wallpaper is.
    @Deprecated
    public android.graphics.drawable.Drawable getWallpaper() {
        android.app.WallpaperManager wm = macapkWallpaperManager();
        return (wm != null) ? wm.getDrawable() : null;
    }
    @Deprecated
    public android.graphics.drawable.Drawable peekWallpaper() {
        android.app.WallpaperManager wm = macapkWallpaperManager();
        return (wm != null) ? wm.peekDrawable() : null;
    }
    @Deprecated
    public void setWallpaper(android.graphics.Bitmap bitmap) throws java.io.IOException {
        android.app.WallpaperManager wm = macapkWallpaperManager();
        if (wm != null) wm.setBitmap(bitmap);
    }
    @Deprecated
    public void setWallpaper(java.io.InputStream data) throws java.io.IOException {
        android.app.WallpaperManager wm = macapkWallpaperManager();
        if (wm != null) wm.setStream(data);
    }
    @Deprecated
    public void clearWallpaper() throws java.io.IOException {
        android.app.WallpaperManager wm = macapkWallpaperManager();
        if (wm != null) wm.clear();
    }
    private android.app.WallpaperManager macapkWallpaperManager() {
        Object wm = getSystemService(WALLPAPER_SERVICE);
        return (wm instanceof android.app.WallpaperManager)
                ? (android.app.WallpaperManager) wm : null;
    }

    // ---- Context creation variants -----------------------------------------

    /** AOSP :7904 declares this abstract and ContextImpl builds a new
     *  ContextImpl carrying the params. Mac.apk has exactly one Context
     *  implementation per role and the only thing ContextParams carries that
     *  we model is the attribution tag, which createAttributionContext() above
     *  also ignores -- so the returned Context is this one, with the params
     *  recorded so getParams() can hand them back. That read-back is the half
     *  a caller can actually observe. */
    public Context createContext(ContextParams contextParams) {
        mParams = contextParams;
        return this;
    }
    private ContextParams mParams;

    /** AOSP :1213 -- "the set of parameters which this Context was created
     *  with, IF it was created via createContext(ContextParams)"; null
     *  otherwise, which is the case for every Context this runtime builds
     *  itself. */
    public ContextParams getParams() { return mParams; }

    /** AOSP :7889 -- createWindowContext with an explicit Display. Mac.apk has
     *  exactly one display (createDisplayContext above also returns this), and
     *  the single-argument createWindowContext already returns this, so the
     *  two agree. The IllegalArgumentException for a null Display is AOSP's
     *  and is kept: it is the one input a caller can get wrong. */
    public Context createWindowContext(android.view.Display display, int type,
                                       android.os.Bundle options) {
        if (display == null) throw new IllegalArgumentException("Display must not be null");
        return createWindowContext(type, options);
    }

    // ---- IntentSender launch ------------------------------------------------

    /** AOSP ContextImpl.startIntentSender(5-arg) delegates to the 6-arg form
     *  with null options. */
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
                                  int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }

    /** AOSP hands the sender to the ActivityManager, which applies
     *  fillInIntent and the flag mask before launching. There is no
     *  ActivityManager here; IntentSender.sendIntent() re-dispatches through
     *  the wrapped PendingIntent, which is the same operation the sender
     *  names. flagsMask/flagsValues rewrite Intent FLAG_ACTIVITY_* bits on the
     *  launched intent, so they are applied to fillInIntent here rather than
     *  dropped -- that is the one part of the contract we can honour exactly.
     *  extraFlags is documented "Always set to 0." */
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
                                  int flagsMask, int flagsValues, int extraFlags,
                                  android.os.Bundle options)
            throws IntentSender.SendIntentException {
        if (intent == null) throw new NullPointerException("intent");
        if (fillInIntent != null && flagsMask != 0) {
            fillInIntent.setFlags((fillInIntent.getFlags() & ~flagsMask)
                                  | (flagsValues & flagsMask));
        }
        intent.sendIntent(this, 0, fillInIntent, null, null);
    }

    // ---- Misc ---------------------------------------------------------------

    /** AOSP: start an Instrumentation class against a target package. Mac.apk
     *  has no ActivityManager to host an instrumentation run, and there is no
     *  second process to instrument. AOSP's own documented failure is a false
     *  return ("if the instrumentation could not be found"), which is exactly
     *  the state here -- so this reports failure rather than silently
     *  pretending a test harness started. */
    public boolean startInstrumentation(ComponentName className, String profileFile,
                                        android.os.Bundle arguments) {
        return false;
    }

    /** AOSP API-30/31 -- ask the system to revoke one of THIS app's own
     *  runtime permissions the next time the app's process is killed. Mac.apk
     *  grants every permission unconditionally (see checkPermission above) and
     *  has no runtime-permission store to revoke from, and the process is not
     *  restarted after a kill, so there is no state to change. Documented
     *  rather than silently swallowed: an app calling this to drop a
     *  permission it no longer needs keeps reading GRANTED here. */
    public void revokeSelfPermissionOnKill(String permName) {
        revokeSelfPermissionsOnKill(java.util.Collections.singletonList(permName));
    }
    public void revokeSelfPermissionsOnKill(java.util.Collection<String> permissions) { }

    /** AOSP API-29 -- reorder a bound service's process within its LRU group.
     *  A pure memory-management hint to the ActivityManager, with no return
     *  value and no observable effect on the binding. Mac.apk runs one process
     *  and never kills it for memory, so there is no group to move it in.
     *  AOSP's own IllegalArgumentException for an unbound connection is kept
     *  -- it is the one thing a caller can get wrong. */
    public void updateServiceGroup(ServiceConnection conn, int group, int importance) {
        if (conn == null) throw new IllegalArgumentException("connection is null");
    }

    /** AOSP bindIsolatedService (API 29) -- "Variation of bindService() that,
     *  in the specific case of ISOLATED services, allows the caller to
     *  generate multiple instances of the service from a single component
     *  declaration", distinguished by instanceName.
     *
     *  ⚠ STATED DIVERGENCE. The bind itself is real: it goes through the same
     *  ServiceRegistry path bindService() uses, so the caller gets a live
     *  binder and its onServiceConnected fires. What Mac.apk cannot provide is
     *  the two properties in the method's name. There is one process, so the
     *  service is not isolated (a service asking Process.isIsolated() gets
     *  false -- as it does for every service here). And ServiceRegistry keys
     *  instances by COMPONENT, so two different instanceNames resolve to the
     *  same instance rather than two.
     *
     *  Binding anyway rather than returning false, because a false would leave
     *  a caller with no service at all, while the properties it loses are ones
     *  nothing in a single-process runtime could have supplied under any
     *  answer. The null-instanceName check is AOSP's. */
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
                                       java.util.concurrent.Executor executor,
                                       ServiceConnection conn) {
        if (instanceName == null) throw new NullPointerException("null instanceName");
        return bindService(service, flags, executor, conn);
    }
    public boolean bindIsolatedService(Intent service, BindServiceFlags flags,
                                       String instanceName,
                                       java.util.concurrent.Executor executor,
                                       ServiceConnection conn) {
        return bindIsolatedService(service, (flags != null) ? (int) flags.getValue() : 0,
                                   instanceName, executor, conn);
    }

    /** AOSP Context.BindServiceFlags (API 34) -- a long-width wrapper around
     *  the BIND_* bitmask, introduced because the int form ran out of bits
     *  (BIND_EXTERNAL_SERVICE_LONG is 1L << 62). */
    public static final class BindServiceFlags {
        private final long mValue;
        private BindServiceFlags(long value) { mValue = value; }
        /** @hide upstream; the packed value. */
        public long getValue() { return mValue; }
        /** AOSP :375, verbatim -- including the guard against passing the
         *  legacy int BIND_EXTERNAL_SERVICE (0x80000000), which sign-extends
         *  into the high long bits and would silently set the wrong flags. */
        public static BindServiceFlags of(long value) {
            if ((value & Integer.toUnsignedLong(BIND_EXTERNAL_SERVICE)) != 0) {
                throw new IllegalArgumentException(
                        "BIND_EXTERNAL_SERVICE is deprecated. Use BIND_EXTERNAL_SERVICE_LONG"
                                + " instead");
            }
            return new BindServiceFlags(value);
        }
    }

    public android.content.pm.PackageManager getPackageManager() { return null; }
    // AOSP: getContentResolver returns a non-null ContentResolver
    // associated with this Context. Callers (Compose WindowRecomposer,
    // Settings observers, etc.) register observers on it -- null would
    // NPE every register call. We lazy-init to keep the Context's
    // ctor light.
    private ContentResolver mContentResolver;
    public ContentResolver getContentResolver() {
        if (mContentResolver == null) mContentResolver = new ContentResolver(this);
        return mContentResolver;
    }
    public String getPackageResourcePath()  { return ""; }
    public String getPackageCodePath()      { return ""; }
    // AOSP gives an app FOUR DISTINCT trees -- internal files, internal cache,
    // external files, external cache -- and these getters used to hand back
    // only two of them: getExternalFilesDir(null) WAS getFilesDir() and
    // getExternalCacheDir() WAS getCacheDir(). Aliasing them is not a harmless
    // simplification, it is a data-loss hazard in the app's own code: the
    // standard "free up space" routine walks getExternalCacheDir() and deletes
    // everything in it, which under the old mapping deleted the INTERNAL
    // cache too, and a routine that clears external files deleted the app's
    // real internal files. It also made getExternalFilesDir(DIRECTORY_PICTURES)
    // litter `Pictures` inside the private files dir.
    //
    // Each getter is now the AOSP composition (see macapkExternalAppDir for
    // the Environment.buildExternalStorageApp*Dirs citations):
    //   getExternalFilesDir(type) -> <ext>/Android/data/<pkg>/files[/<type>]
    //   getExternalCacheDir()     -> <ext>/Android/data/<pkg>/cache
    //   getExternalMediaDirs()    -> <ext>/Android/media/<pkg>
    //   getObbDir()/getObbDirs()  -> <ext>/Android/obb/<pkg>
    //
    // ⚠ LAYOUT CHANGE. Whatever an app already wrote through the old aliases
    // is still on disk under getFilesDir()/getCacheDir() -- nothing is moved
    // and nothing is deleted -- but it was written to a location the app will
    // now read as its INTERNAL storage, which is what it always actually was.
    //
    // The per-type subdirectory stays honored (apps separate Pictures / Music /
    // Movies / Download by passing Environment.DIRECTORY_* as `type`), and
    // type==null/empty still yields the files root itself, matching AOSP.
    public java.io.File getExternalFilesDir(String type) {
        java.io.File base = macapkExternalAppDir("data", "files");
        if (base == null) return null;
        if (type == null || type.isEmpty()) return base;
        java.io.File d = new java.io.File(base, type);
        try { d.mkdirs(); } catch (Throwable ignored) {}
        return d;
    }
    public java.io.File[] getExternalFilesDirs(String type) {
        java.io.File d = getExternalFilesDir(type);
        return d != null ? new java.io.File[]{d} : new java.io.File[0];
    }
    public java.io.File[] getExternalCacheDirs() {
        java.io.File d = getExternalCacheDir();
        return d != null ? new java.io.File[]{d} : new java.io.File[0];
    }
    public java.io.File[] getExternalMediaDirs() {
        java.io.File d = macapkExternalAppDir("media", null);
        return d != null ? new java.io.File[]{d} : new java.io.File[0];
    }
    // AOSP ContextImpl:767-779 defines getObbDir() AS getObbDirs()[0], so an
    // empty array here while getObbDir() answered non-null was internally
    // inconsistent: an app that iterates the plural form saw no expansion
    // storage at all while the singular form pointed at a real directory.
    public java.io.File[] getObbDirs() {
        java.io.File d = getObbDir();
        return d != null ? new java.io.File[]{d} : new java.io.File[0];
    }
    public java.io.File getExternalCacheDir() { return macapkExternalAppDir("data", "cache"); }
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        return new android.content.pm.ApplicationInfo();
    }

    // No-op resource resolvers; ContextWrapper-style helpers.
    public String getString(int resId) { return getResources() != null ? getResources().getString(resId) : ""; }
    // v1.0.98: API 1+ String.format varargs overload. NearbyViewBinder
    // and many AOSP idioms use ctx.getString(R.string.foo, arg, arg2).
    public String getString(int resId, Object... formatArgs) {
        String s = getString(resId);
        if (s == null || s.isEmpty() || formatArgs == null || formatArgs.length == 0) return s;
        try { return String.format(s, formatArgs); }
        catch (Throwable t) { return s; }
    }
    public CharSequence getText(int resId) { return getString(resId); }
    public android.content.res.Configuration getResourcesConfiguration() {
        return getResources() != null ? getResources().getConfiguration() : null;
    }

    public static final int DEVICE_ID_DEFAULT = 0;
    /** AOSP getDeviceId (API 34): the logical device this Context is associated
     *  with. Mac.apk is the default device. */
    public int getDeviceId() { return DEVICE_ID_DEFAULT; }
    public Context createDeviceContext(int deviceId) { return this; }

    /** AOSP registerDeviceIdChangeListener (API 34): notified when this Context's
     *  logical device changes. Mac.apk is one fixed device, so it never fires. */
    public void registerDeviceIdChangeListener(java.util.concurrent.Executor executor,
            java.util.function.IntConsumer listener) { }
    public void unregisterDeviceIdChangeListener(java.util.function.IntConsumer listener) { }

    /** AOSP getAttributionSource (API 31): the identity attributed to work done
     *  through this Context. Mac.apk has one app identity. */
    public android.content.AttributionSource getAttributionSource() {
        return new android.content.AttributionSource(android.os.Process.myUid(),
                com.macapk.loader.Boot.processName(), null);
    }

    public String getBasePackageName() { return getPackageName(); }
}




