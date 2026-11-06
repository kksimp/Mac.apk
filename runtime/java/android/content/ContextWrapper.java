// ContextWrapper -- forwarding base for Activity, Service, Application.
// Real Android delegates almost everything to a "base context" set by
// the framework's Activity instantiation code. Mac.apk's Activity
// instantiates with `attachBaseContext(applicationContext)`; everything
// resolves the app context.
package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;

import java.io.File;

public class ContextWrapper extends Context {
    private Context baseContext;

    public ContextWrapper(Context base) { this.baseContext = base; }
    protected void attachBaseContext(Context base) { this.baseContext = base; }
    public Context getBaseContext() { return baseContext; }

    @Override public String getPackageName() {
        if (baseContext != null) {
            String p = baseContext.getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        // A real Context always has a package name; fall back to the process
        // Application singleton rather than "" (Unity caches this as its app
        // identifier; an empty one crashes its PlayerPrefs path build).
        android.app.Application a = android.app.Application.macapk_get();
        String p = (a != null) ? a.getPackageName() : null;
        return (p != null) ? p : "";
    }
    @Override public Resources getResources() {
        return baseContext != null ? baseContext.getResources() : null;
    }
    @Override public AssetManager getAssets() {
        return baseContext != null ? baseContext.getAssets() : null;
    }
    @Override public Context getApplicationContext() {
        return baseContext != null ? baseContext.getApplicationContext() : this;
    }
    @Override public ClassLoader getClassLoader() {
        return baseContext != null ? baseContext.getClassLoader()
                                   : getClass().getClassLoader();
    }
    @Override public File getFilesDir() {
        return baseContext != null ? baseContext.getFilesDir() : new File(".");
    }
    @Override public File getCacheDir() {
        return baseContext != null ? baseContext.getCacheDir() : new File(".");
    }
    @Override public File getDir(String name, int mode) {
        return baseContext != null ? baseContext.getDir(name, mode) : new File(".");
    }

    /** Forward to baseContext, but fall back to Context's own
     *  switch (which provides stubs for JobScheduler, ActivityManager,
     *  LAYOUT_INFLATER_SERVICE) when baseContext is null or returns
     *  null. Without the fallback, Application (which sets
     *  baseContext=null in its no-arg ctor) returns null for every
     *  system service and apps that cast the result NPE. */
    @Override public Object getSystemService(String name) {
        // v1.0.102 diag: trace the CW path so we can localize where a
        // getSystemService("jobscheduler") request is going.
        Object svc = null;
        String via = "super";
        if (baseContext != null) {
            svc = baseContext.getSystemService(name);
            via = "baseContext(" + baseContext.getClass().getSimpleName() + ")";
        }
        if (svc == null) {
            svc = super.getSystemService(name);
            via += "->super";
        }
        if ("jobscheduler".equals(name) || "1".equals(System.getenv("MACAPK_TRACE_GETSVC"))) {
            try {
                com.macapk.loader.NativeLog.i("ContextWrapper",
                    getClass().getSimpleName() + ".getSystemService(\"" + name + "\") -> "
                    + (svc == null ? "null" : svc.getClass().getName())
                    + " via " + via);
            } catch (Throwable ignored) {}
        }
        return svc;
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return baseContext != null ? baseContext.getSharedPreferences(name, mode) : null;
    }
    // Forward to the base context's populated ApplicationInfo /
    // PackageManager. Without these overrides, ContextWrapper inherited
    // Context's empty stubs, which broke cocos2d-x's
    // Cocos2dxHelper.nativeSetApkPath -- it passed null sourceDir to
    // native code, so the engine had no APK path to mmap-load asset
    // resources from, causing every texture lookup to silently fail.
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
        // getApplicationInfo() is called on hot paths (per-child resource /
        // theme resolution runs it every frame). An unconditional INFO log here
        // floods the launcher log -- tens of thousands of lines/min on a View
        // app -- which both buries real signal and adds per-call string +
        // I/O pressure. Gate it behind an env var (default silent); the real
        // diagnostic value (which Context subclass is asked) is preserved when
        // MACAPK_TRACE_APPINFO is set.
        if (System.getenv("MACAPK_TRACE_APPINFO") != null) {
            com.macapk.loader.NativeLog.i("Mac.apk.CW",
                "getApplicationInfo() on " + this.getClass().getSimpleName());
        }
        return baseContext != null
            ? baseContext.getApplicationInfo()
            : super.getApplicationInfo();
    }
    @Override public android.content.pm.PackageManager getPackageManager() {
        android.content.pm.PackageManager pm = baseContext != null
            ? baseContext.getPackageManager()
            : super.getPackageManager();
        // On real Android getPackageManager() is never null for an
        // attached context, so a null here is always a framework lie
        // that will NPE somewhere downstream (appcompat probes it
        // during attachBaseContext2, e.g.). Name the context class so
        // the next bring-up doesn't have to rediscover the chain.
        if (pm == null) {
            com.macapk.loader.NativeLog.w("Mac.apk.CW",
                "getPackageManager() -> null on " + getClass().getName()
                + " (base=" + (baseContext == null ? "null"
                              : baseContext.getClass().getName()) + ")");
        }
        return pm;
    }
    // cocos2d-x v2's Cocos2dxActivity uses getPackageCodePath() to
    // hand the APK path to native code. Activity inherits this from
    // ContextWrapper; without delegation the call walks up the
    // ContextWrapper chain to Context's default empty-string return,
    // and cocos's s_pZipFile is never initialized.
    @Override public String getPackageCodePath() {
        return baseContext != null
            ? baseContext.getPackageCodePath()
            : super.getPackageCodePath();
    }
    @Override public String getPackageResourcePath() {
        return baseContext != null
            ? baseContext.getPackageResourcePath()
            : super.getPackageResourcePath();
    }

    // ---- AOSP ContextWrapper delegation: theme + content resolver -------
    // aosp-mirror/platform_frameworks_base android16-release
    // core/java/android/content/ContextWrapper.java:
    //   136-138  getContentResolver()  -> mBase.getContentResolver()
    //   156-158  setTheme(int resid)   -> mBase.setTheme(resid)
    //   168-170  getTheme()            -> mBase.getTheme()
    // A ContextWrapper on Android owns NO theme and NO ContentResolver of its
    // own; both are the base context's. Inheriting Context's concrete versions
    // gave every plain wrapper a private Theme built from
    // macapkSelectDefaultTheme() and a private ContentResolver, so a wrapper
    // resolved ?attr against Theme.DeviceDefault instead of the app's real
    // theme and observers registered on it never saw a notifyChange made
    // through the activity.
    // The null-base fallback to super is the Application no-arg-ctor window
    // (before attachBaseContext installs the terminator).
    @Override public ContentResolver getContentResolver() {
        return baseContext != null ? baseContext.getContentResolver()
                                   : super.getContentResolver();
    }
    @Override public void setTheme(int resid) {
        if (baseContext != null) baseContext.setTheme(resid);
        else super.setTheme(resid);
    }
    @Override public Resources.Theme getTheme() {
        return baseContext != null ? baseContext.getTheme() : super.getTheme();
    }

    // ---- AOSP ContextWrapper: device-/credential-protected storage flavor is
    // the base context's. A ContextWrapper owns no storage of its own, so it
    // delegates all four to mBase (android14-release ContextWrapper.java). This
    // matters so a ContextThemeWrapper / createConfigurationContext wrapper
    // around a device-protected StorageContext still reports DE and still
    // resolves file APIs to the DE area, rather than inheriting Context's
    // credential-protected defaults.
    @Override public boolean isDeviceProtectedStorage() {
        return baseContext != null ? baseContext.isDeviceProtectedStorage() : super.isDeviceProtectedStorage();
    }
    @Override public boolean isCredentialProtectedStorage() {
        return baseContext != null ? baseContext.isCredentialProtectedStorage() : super.isCredentialProtectedStorage();
    }
    @Override public Context createDeviceProtectedStorageContext() {
        return baseContext != null ? baseContext.createDeviceProtectedStorageContext()
                                   : super.createDeviceProtectedStorageContext();
    }
    @Override public Context createCredentialProtectedStorageContext() {
        return baseContext != null ? baseContext.createCredentialProtectedStorageContext()
                                   : super.createCredentialProtectedStorageContext();
    }
}
