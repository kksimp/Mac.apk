// android.app.ActivityThread -- the per-process main-thread state
// container. Hidden API (not in the public SDK), but apps reach into
// it via reflection to:
//   - currentActivityThread() / currentApplication() to get the
//     Application context without holding a reference
//   - getSystemContext() for static SDK init
// AOSP source: frameworks/base/core/java/android/app/ActivityThread.java
//
// Reflective callers expect THESE specific static methods to exist
// and return non-null values. We satisfy that with a minimal shim
// that returns our singleton Application via Application.macapk_get().
package android.app;

import android.content.Context;

public final class ActivityThread {

    /** The static singleton AOSP creates at process start; reflectively
     *  retrieved by ContentProvider/library code that wants a process-
     *  wide Application reference outside of the normal lifecycle
     *  callbacks. */
    private static final ActivityThread INSTANCE = new ActivityThread();

    private ActivityThread() {
        // AOSP constructs the process Instrumentation during attach(); we do it
        // here because ActivityThread is a lazily-initialized singleton for us.
        // Instrumentation's constructor is empty, so this cannot re-enter.
        mInstrumentation.mThread = this;
    }

    /** AOSP ActivityThread.java:411 -- `Instrumentation mInstrumentation;`,
     *  package-private and NON-final. The standard reflective instrumentation
     *  hook (Class.forName("android.app.ActivityThread") -> currentActivityThread
     *  -> field "mInstrumentation" -> set) WRITES to this field, so it must not
     *  be private and must not be final. */
    Instrumentation mInstrumentation = new Instrumentation();

    /** AOSP ActivityThread.java:2812. */
    public Instrumentation getInstrumentation() { return mInstrumentation; }

    /** The single package this process binds. AOSP keys a map of these by
     *  package name (mPackages); a Mac.apk process runs exactly one app, so one
     *  field is the whole map. */
    private LoadedApk mBoundLoadedApk;
    private String mBoundProcessName;
    private Application mInitialApplication;

    /** AOSP peekPackageInfo(String, boolean). Instrumentation.getFactory calls
     *  this to find the LoadedApk whose declared appComponentFactory should
     *  construct a component.
     *
     *  DEVIATION, deliberate and documented: a null pkg resolves to the package
     *  currently being bound. AOSP never needs that because it always has a
     *  ContextImpl to name the package with; our launch path has no Context yet
     *  (see LoadedApk's null-Context note), so without this the factory lookup
     *  would fall through to the stock AppComponentFactory and the app's own
     *  declared factory -- androidx.core.app.CoreComponentFactory on most of the
     *  fleet -- would never run, which is exactly the fidelity this pipeline
     *  exists to provide. Single-app process, so there is no ambiguity about
     *  which package "the one being bound" means. */
    LoadedApk peekPackageInfo(String pkg) {
        LoadedApk apk = mBoundLoadedApk;
        if (apk == null) return null;
        if (pkg == null || pkg.equals(apk.getPackageName())) return apk;
        return null;
    }

    /** AOSP's private AppBindData, reduced to the fields this path uses. */
    private static final class AppBindData {
        LoadedApk info;
        String processName;
        boolean restrictedBackupMode;
    }

    /** AOSP ActivityThread.handleBindApplication (:6694), reduced to the
     *  Application-construction step.
     *
     *  NON-GOALS, stated because four comments elsewhere in the tree justify
     *  lazy resolution with "Mac.apk has no handleBindApplication" and would
     *  otherwise read as an invitation to move work in here: this method creates
     *  NO ContextImpl, binds NO targetSdk (Application.macapkInitVersion keeps
     *  that), installs NO ContentProviders, and does NOT call onCreate. On a
     *  device those are siblings of this call, not children of it: AOSP does
     *  installContentProviders at :6983 and callApplicationOnCreate at :6998,
     *  AFTER this returns -- which is exactly the attach < providers < onCreate
     *  order com.macapk.loader.Boot already implements. */
    private void handleBindApplication(AppBindData data) {
        mBoundLoadedApk = data.info;
        mBoundProcessName = data.processName;
        // Pass null for the Instrumentation, exactly as AOSP :6959 does. The
        // parameter's only job in LoadedApk's body is to gate
        // callApplicationOnCreate; null keeps that block dead, so onCreate can
        // never double-run against Boot's own call.
        mInitialApplication = data.info.makeApplicationInner(
                data.restrictedBackupMode, /*instrumentation=*/ null);
    }

    /** Mac.apk-namespaced entry point: com.macapk.loader.Boot calls this to bind
     *  the application through the AOSP chain.
     *
     *  Why the extra frame rather than a public handleBindApplication overload:
     *  AOSP's handleBindApplication is private and takes AppBindData, so a
     *  public handleBindApplication(String, String, ClassLoader) would be an
     *  AOSP-SIGNATURED method that exists on no device, visible to any app that
     *  reflects android.app.ActivityThread. One extra clearly-foreign frame is
     *  the cheaper cost. */
    public Application macapkBindApplication(String packageName, String appClassName,
                                             String appComponentFactoryName,
                                             ClassLoader cl) {
        AppBindData data = new AppBindData();
        data.info = new LoadedApk(this, packageName, appClassName,
                                  appComponentFactoryName, cl);
        data.processName = packageName;
        data.restrictedBackupMode = false;
        handleBindApplication(data);
        return mInitialApplication;
    }

    /** Application.attach is package-private in android.app (AOSP
     *  Application.java:345), so com.macapk.loader.Boot cannot call it directly.
     *  This is the bridge, and it exists so the ATTACH-time stack also carries
     *  its genuine android.app.Application.attach frame rather than jumping
     *  straight from Boot to attachBaseContext. */
    public static void macapkAttachApplication(Application app, Context base) {
        if (app != null && base != null) app.attach(base);
    }

    /** AOSP static accessor -- returns the per-process ActivityThread.
     *  Many SDKs call this via reflection: Class.forName("android.app.
     *  ActivityThread").getMethod("currentActivityThread").invoke(null). */
    public static ActivityThread currentActivityThread() { return INSTANCE; }

    /** AOSP static accessor -- the package name of the running APK.
     *  Returned via a separate static method, NOT via getApplication
     *  (some SDKs call this without a Context). */
    public static String currentPackageName() {
        LoadedApk bound = INSTANCE.mBoundLoadedApk;
        if (bound != null) return bound.getPackageName();
        // Boot's default constructor path has no LoadedApk bind, but publishes
        // this manifest identity before loading any Application subclass.
        String bootProcess = com.macapk.loader.Boot.processName();
        if (bootProcess != null) return bootProcess;
        Application app = Application.macapk_get();
        return app != null ? app.getPackageName() : "";
    }
    public static String currentProcessName() {
        String bound = INSTANCE.mBoundProcessName;
        if (bound != null) return bound;
        String bootProcess = com.macapk.loader.Boot.processName();
        return bootProcess != null ? bootProcess : currentPackageName();
    }

    /** Returns the per-process Application. Equivalent to
     *  `currentActivityThread().getApplication()`. */
    public static Application currentApplication() {
        return Application.macapk_get();
    }

    public Application getApplication() {
        return Application.macapk_get();
    }

    /** AOSP ActivityThread.getProcessName() (INSTANCE method, core/java/android/app/
     *  ActivityThread.java:3209): "public String getProcessName() { return
     *  mBoundApplication.processName; }". Instagram's obfuscated onCreate init
     *  (X.1do.A00 -> X.3Dm.A04 -> InstagramAppShell.onCreate) calls it via
     *  currentActivityThread().getProcessName(); without it the call is a
     *  NoSuchMethodError that aborts onCreate and leaves the app's DI half-set.
     *  We have no multi-process model, so the process name is the package name
     *  (== currentProcessName()). */
    public String getProcessName() {
        return currentProcessName();
    }

    /** AOSP method: returns the system context (one tied to the
     *  framework, not any specific app). For us, return the Application
     *  context -- callers typically use it for getSharedPreferences or
     *  resource lookups, both of which work fine through our App. */
    public Context getSystemContext() {
        return Application.macapk_get();
    }

    /** AOSP method: returns the ApplicationThread Binder. Many apps
     *  call this looking for an IBinder identity; we don't have one
     *  but returning null is allowed by the contract -- callers
     *  null-check. */
    public android.os.IBinder getApplicationThread() { return null; }
}
