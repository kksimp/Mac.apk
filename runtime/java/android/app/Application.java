// android.app.Application -- the per-process Context. Apps can subclass
// it via android:name="..." in AndroidManifest.xml; v2 either
// instantiates the named subclass or falls back to plain Application.
// All getters route to the loader's app-bound services.
package android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.File;
import java.util.HashMap;

// AOSP: `public class Application extends ContextWrapper implements ComponentCallbacks2`
// (aosp-mirror/platform_frameworks_base, main, core/java/android/app/Application.java:54).
// We declared only `extends ContextWrapper`, and the missing interface is OBSERVABLE:
// library code casts the Application to ComponentCallbacks because on a device it always
// succeeds. Koin-Android does exactly that to find the scope backing an `inject()`
// delegate, so Thunderbird 21.0 died with
//   ClassCastException: net.thunderbird.android.ThunderbirdApp cannot be cast to
//   android.content.ComponentCallbacks
// out of `ThunderbirdApp$special$$inlined$inject$default$1.invoke` (v1.0.960 run log).
// This costs NOTHING at runtime: all three ComponentCallbacks2 methods
// (onConfigurationChanged / onLowMemory / onTrimMemory) were ALREADY declared below with
// exactly these signatures, and macapkDispatchConfigurationChanged / the low-memory
// dispatcher already call them. The class simply never advertised the type, so every
// `instanceof ComponentCallbacks` probe answered false and every cast threw.
public class Application extends ContextWrapper
        implements android.content.ComponentCallbacks2 {
    private static Application sInstance;

    private String packageName = "com.macapk.unknown";
    private File   filesDir;
    private File   cacheDir;
    private AssetManager assets;
    private Resources    resources;
    // The installed base.apk (v1.0.1585: <root>/data/app/~~<nonce>/<pkg>-<nonce>/
    // base.apk; its split_*.apk siblings are the rest of the app, derived by
    // com.macapk.loader.InstalledSplits wherever they are needed). Until the
    // C++ merge is deleted a bundle arrives as the legacy synthetic
    // `_merged.apk`; nothing here depends on either file NAME. cocos2d-x and
    // other engines read this through getApplicationInfo().sourceDir to mmap
    // the APK directly and pull asset bytes out of its ZIP entries.
    // Left null until the loader fills it in via macapkInitInstall().
    private String apkPath;
    // Per-app working directory (under runtime/build/var/apps/<pkg>/).
    private File   appDir;
    // Where translated dylibs land. Mirrors Android's nativeLibraryDir
    // semantics close enough that engine code probing for foo.so finds
    // libfoo.dylib instead.
    private File   nativeLibraryDir;
    // Manifest version / SDK metadata, set by the loader from the parsed
    // manifest (macapkInitVersion). Real Android always populates these on
    // ApplicationInfo/PackageInfo; leaving them 0 / "1.0" made Unity log
    // "Min API Level '0'" and crash on an empty version string at native
    // init. minSdk defaults to 1 (AOSP), versionName to the package's real
    // value. See Manifest.java for the parse.
    private int    minSdk      = 1;
    // targetSdk defaults to 0, NOT 1, and the difference is load-bearing.
    // The compat gates in this tree read a 0 through macapkTargetSdkKnown
    // (below) as the "our ApplicationInfo stub answered before the manifest
    // was parsed" sentinel -- which is AOSP's own default for the field
    // (android/content/pm/ApplicationInfo.java:58 here, an int left at 0) --
    // UNLESS the manifest bound a real 0 (no <uses-sdk>, MACAPK_TARGETSDK_ZERO).
    // They used to test `ai.targetSdkVersion > 0`, which cannot tell the two
    // apart; debug/targetsdk0_gate.sh (census) now fails any new `> 0` guard.
    // While this defaulted to 1 the sentinel COULD NOT FIRE: an unwired
    // Application handed those gates a perfectly valid, extremely ancient
    // targetSdk of 1, so a MODERN app took the legacy branch, and the three
    // process-wide latches (LinearLayout weight measurement, TextView fallback
    // line spacing, ViewGroup split-motion default) could freeze there
    // permanently. See docs/legacy-compat-audit_2026-08-24.md 5.1.
    // minSdk keeps its AOSP default of 1 (PackageParser's), which is a real
    // value rather than a sentinel -- Unity reads it and logs
    // "Min API Level '0'" on a zero.
    private int    targetSdk   = 0;
    private String versionName = "1.0";
    private int    versionCode = 1;

    public Application() {
        super(null);
        sInstance = this;
        // AOSP: an Application's base Context is a real ContextImpl -- which is
        // NOT a ContextWrapper and is never null (ActivityThread builds it via
        // ContextImpl.createAppContext, then Application.attach() installs it).
        // Ours used super(null), so getBaseContext() stayed null for the whole
        // process. That breaks any code that unwraps the getBaseContext() chain
        // asserting each link non-null until it reaches a non-ContextWrapper
        // terminator. duolingo's BaseActivity.attachBaseContext (-> bq.a.a ->
        // z9.t.Y) does exactly this: it loops
        //   base = ((ContextWrapper) base).getBaseContext(); base.getClass();
        // stopping only on its own gr.k ContextWrapper or a non-ContextWrapper,
        // and NPE'd the instant it unwrapped the Application (the Activity's base
        // context in Mac.apk) to its null base. Install a ContextImpl-shaped
        // terminator so the chain ends cleanly: AppBaseContext extends Context
        // directly (so `instanceof ContextWrapper` is false and the walk
        // terminates there), and it serves every getter from this Application's
        // own fields, mirroring how a real ContextImpl serves the app's
        // resources/services independently of the Application wrapper.
        // Use super.attachBaseContext (invokespecial -> ContextWrapper's impl)
        // so a manifest Application subclass that overrides attachBaseContext is
        // NOT invoked from inside this constructor (calling an overridable method
        // during construction is unsafe, and we are not changing whether/when
        // such overrides run -- a separate pre-existing concern).
        super.attachBaseContext(new AppBaseContext(this));
        macapkTraceLaunchStack();
    }

    /** MACAPK_TRACE_LAUNCH_STACK=1 -- dump the call stack the app's own
     *  Application construction runs on, one frame per line.
     *
     *  This is the only app-independent instrument in the tree that can SEE the
     *  launch chain. scripts/sweep.sh scores colour bands on games and
     *  debug/recap_fleet.sh scores pixels against emulator pairs; a launch
     *  pipeline that names the right classes but does not actually CALL through
     *  them is invisible to both. It captures here, in the base Application
     *  constructor, because that is the same region of the stack an app's own
     *  <clinit> sees -- the reflective instantiation that triggers <clinit> is
     *  the immediate caller of this constructor.
     *
     *  Prints the frame's toString(), NOT just class#method, deliberately: on
     *  HotSpot a bootstrap-loaded frame is stamped `java.base/...` while ART
     *  emits no module prefix at all, and the SIZE of that jdk.internal
     *  reflection cluster differs per instantiation primitive. Both facts
     *  matter to any app that hashes its own launch stack, and neither is
     *  visible if the prefix is stripped. Default OFF; costs nothing unset. */
    private static void macapkTraceLaunchStack() {
        if (!"1".equals(System.getenv("MACAPK_TRACE_LAUNCH_STACK"))) return;
        StackTraceElement[] st = new Exception().getStackTrace();
        int jb = 0;
        for (StackTraceElement e : st) if (e.toString().startsWith("java.base/")) jb++;
        android.util.Log.i("Mac.apk.LaunchStack",
            "frames=" + st.length + " java.base=" + jb);
        for (int i = 0; i < st.length; i++) {
            android.util.Log.i("Mac.apk.LaunchStack", "  [" + i + "] " + st[i]);
        }
    }

    /** ContextImpl-shaped base Context for the Application: a non-ContextWrapper
     *  terminator for the getBaseContext() chain. Real Android's ContextImpl IS
     *  the chain end (it extends Context, not ContextWrapper) and serves the
     *  app's resources INDEPENDENTLY of the Application wrapper; mirroring that
     *  keeps chain-walkers (duolingo z9.t.Y, View's owner walk, AppCompat)
     *  terminating cleanly instead of dereferencing a null base.
     *
     *  CRITICAL: this reads the Application's raw FIELDS directly (a nested class
     *  has private access), NOT the overridable app.getXxx() methods. A real
     *  ContextImpl never delegates back to the Application, and neither must we:
     *  if a manifest Application subclass overrides getResources()/getAssets()/
     *  etc. and delegates to getBaseContext() (a common per-app-language / font
     *  wrapper pattern), forwarding to app.getResources() here would cycle
     *  base -> app -> base -> ... and stack-overflow. Field access cannot cycle. */
    private static final class AppBaseContext extends Context {
        private final Application app;
        AppBaseContext(Application app) { this.app = app; }
        // The 8 abstract getters + close kin, served from the app's fields.
        @Override public String getPackageName()            { return app.packageName; }
        @Override public Resources getResources()           { return app.resources; }
        @Override public Resources getResourcesDirect()     { return app.resources; }
        @Override public AssetManager getAssets()           { return app.assets; }
        @Override public Context getApplicationContext()    { return app; }
        @Override public ClassLoader getClassLoader() {
            return app.classLoader != null ? app.classLoader
                                           : Thread.currentThread().getContextClassLoader();
        }
        @Override public File getFilesDir()                 { return app.filesDir != null ? app.filesDir : new File("."); }
        @Override public File getCacheDir()                 { return app.cacheDir != null ? app.cacheDir : new File("."); }
        @Override public File getDir(String name, int mode) { File d = new File(getDataDir(), "app_" + name); d.mkdirs(); return d; }
        // v1.0.1586: the SAME answer the Application gives (ApplicationInfo.
        // sourceDir), not the raw apkPath field -- AOSP's ContextImpl.
        // getPackageCodePath() is `mPackageInfo.getAppDir()`, which IS
        // sourceDir, so the two doors could never disagree there. Safe under
        // the terminator rule because macapkCodePath() is PRIVATE and reaches
        // the PackageManager through private, non-overridable methods (see
        // macapkApplicationInfoReal): no virtual hop can come back here.
        @Override public String getPackageCodePath()        { return app.macapkCodePath(); }
        @Override public String getPackageResourcePath()    { return app.macapkCodePath(); }
        // getSystemService MUST serve from this terminator's OWN Context
        // registry (super == Context.getSystemService), NOT app.getSystemService.
        // Application.getSystemService's default case delegates to
        // super.getSystemService (ContextWrapper), which now consults its
        // baseContext (this terminator); forwarding back to app.getSystemService
        // would cycle app -> ContextWrapper -> base -> app -> ... and
        // stack-overflow during onCreate when a clinit requests a service name
        // the Application's switch does not specialize. Context.getSystemService
        // is the same self-contained per-service registry ContextWrapper falls
        // through to when the base is null (the pre-terminator behavior), so this
        // preserves exactly what unmapped services resolved to before.
        @Override public Object getSystemService(String name) { return super.getSystemService(name); }
        // Reach the canonical builders without virtual dispatch: an Application
        // subclass may override these getters and delegate back to its base.
        // Calling app.getXxx() here would re-enter that override and recurse.
        // Public Application getters remain overridable for app-facing calls.
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return app.macapkApplicationInfoReal(); }
        @Override public PackageManager getPackageManager() { return app.macapkPackageManagerReal(); }
        // Serve from the REAL provider, NOT app.getSharedPreferences: an app that
        // overrides getSharedPreferences and delegates back to its base context
        // (Instagram) would cycle base -> app -> base and StackOverflow. Same
        // terminator rule as getContentResolver/getSystemService below.
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return app.macapkGetSharedPreferencesReal(name, mode); }
        /** The ContentResolver is OWNED HERE, not fetched from the Application.
         *
         *  AOSP: ContextImpl's constructor builds it itself --
         *    mContentResolver = new ApplicationContentResolver(this, mainThread);
         *  (frameworks/base/core/java/android/app/ContextImpl.java, and
         *  getContentResolver() is a bare `return mContentResolver;`). A real
         *  ContextImpl never asks the Application for one, and neither may we.
         *
         *  MEASURED (2026-08-23): this used to be `return app.getContentResolver()`,
         *  and Application does NOT override getContentResolver -- so once
         *  ContextWrapper started delegating it to its base the way AOSP does
         *  (ContextWrapper.java:136-138), the pair recursed:
         *    ContextWrapper.getContentResolver -> AppBaseContext.getContentResolver
         *    -> app.getContentResolver -> ContextWrapper.getContentResolver -> ...
         *  StackOverflowError inside Activity.onCreate, swallowed by
         *  ActivityHost.runTolerant, so the app stayed alive at 60fps with its GL
         *  renderer never constructed: unciv 26089 -> 296 colours, hcr181
         *  58406 -> 389. Same shape as the getSystemService cycle documented
         *  above, and the same fix -- serve it from THIS terminator.
         *
         *  Bonus correctness: because every wrapper in the process now delegates
         *  down to this single terminator, the app has exactly ONE
         *  ContentResolver, so an observer registered through any Context sees a
         *  notifyChange made through any other. AOSP has the same property for
         *  the same reason. */
        @Override public ContentResolver getContentResolver() { return super.getContentResolver(); }
    }

    /** Process-wide singleton accessor for framework code that needs
     *  the running Application but isn't on the lifecycle path (e.g.
     *  android.app.ActivityThread.currentApplication()). Returns null
     *  before Boot.run instantiates the app subclass. */
    public static Application macapk_get() { return sInstance; }

    /** Set by the loader at boot time. */
    public void macapkInit(String packageName, File filesDir, File cacheDir,
                            AssetManager assets, Resources resources) {
        this.packageName = packageName;
        this.filesDir = filesDir;
        this.cacheDir = cacheDir;
        this.assets = assets;
        this.resources = resources;
    }

    /** Second-pass init for the install-style metadata that
     *  getApplicationInfo() / getPackageManager() callers expect.
     *  Split from the original macapkInit so we don't break call
     *  sites that already passed the basic five fields. */
    public void macapkInitInstall(String apkPath, File appDir,
                                   File nativeLibraryDir) {
        this.apkPath          = apkPath;
        this.appDir           = appDir;
        this.nativeLibraryDir = nativeLibraryDir;
    }

    /** v1.0.1641: the app's DATA dir (<root>/data/data/<dirName>, the parent of
     *  getFilesDir()), which ApplicationInfo.dataDir / deviceProtectedDataDir /
     *  credentialProtectedDataDir report from now on. AOSP: LoadedApk's
     *  mDataDirFile IS aInfo.dataDir, so getDataDir() and
     *  getApplicationInfo().dataDir can never differ on a device. They reported
     *  the CODE dir here (appDir), so apps that store under ai.dataDir wrote into
     *  a directory an update or reinstall replaces. Called by Boot before any
     *  app code; drops a record cached earlier in boot. */
    public void macapkSetDataDir(File dataDir) {
        this.macapkDataDir = dataDir;
        this.cachedApplicationInfo = null;
        PackageManager pm = packageManager;
        if (pm instanceof com.macapk.loader.MacApkPackageManager && dataDir != null) {
            ((com.macapk.loader.MacApkPackageManager) pm)
                .macapkSetReportedDataDir(dataDir.getAbsolutePath());
        }
    }
    private File macapkDataDir;

    /** Third-pass init for the manifest version / SDK metadata that
     *  getApplicationInfo().{min,target}SdkVersion and
     *  getPackageInfo().{versionName,versionCode} must report. Real
     *  Android always provides these; a 0 SDK / empty version drives
     *  some engines (Unity) down a null-fallback that crashes. Separate
     *  method so existing macapkInit/macapkInitInstall call sites are
     *  untouched. */
    public void macapkInitVersion(int minSdk, int targetSdk,
                                   String versionName, int versionCode) {
        if (minSdk > 0)    this.minSdk    = minSdk;
        if (targetSdk > 0) this.targetSdk = targetSdk;
        // The MANIFEST's value, which unlike any ApplicationInfo read is never
        // "not parsed yet": a 0 here is a real targetSdk (no <uses-sdk>).
        macapkBindTargetSdk(targetSdk);
        if (versionName != null && !versionName.isEmpty())
                           this.versionName = versionName;
        if (versionCode > 0) this.versionCode = versionCode;
        // If a PackageManager was already built, push the metadata into it
        // too (it is the same canonical source the Activity path reads).
        if (packageManager instanceof com.macapk.loader.MacApkPackageManager) {
            ((com.macapk.loader.MacApkPackageManager) packageManager)
                .macapkSetVersionInfo(this.minSdk, this.targetSdk,
                                      this.versionName, this.versionCode);
        }
        // AOSP ActivityThread.handleBindApplication tells ART the app's level
        // as part of process bind (VMRuntime.getRuntime().setTargetSdkVersion(
        // data.appInfo.targetSdkVersion)). This method is the moment the loader
        // hands us the parsed manifest, so it is our bind point. Without this
        // call dalvik.system.VMRuntime.getTargetSdkVersion() answered a
        // hardcoded constant for every app -- see that field's header and
        // docs/legacy-compat-audit_2026-08-24.md 5.2.
        // AOSP makes both calls unconditionally, 0 included; the guard only
        // skips an unresolved value (macapkTargetSdkKnown is `> 0` unless the
        // manifest bound a real 0).
        if (macapkTargetSdkKnown(this.targetSdk)) {
            try {
                dalvik.system.VMRuntime.getRuntime()
                    .setTargetSdkVersion(this.targetSdk);
            } catch (Throwable ignored) {}
            // Same bind moment, same AOSP line block: ActivityThread.java:7593
            // `Message.updateCheckRecycle(data.appInfo.targetSdkVersion);`.
            // Pre-L apps recycle their own Messages routinely and must not be
            // thrown at for it.
            try {
                android.os.Message.updateCheckRecycle(this.targetSdk);
            } catch (Throwable ignored) {}
        }
    }

    /** AOSP Application.java:345-348:
     *      /* package *\/ final void attach(Context context) {
     *          attachBaseContext(context);
     *          mLoadedApk = ContextImpl.getImpl(context).mPackageInfo;
     *      }
     *  We mirror it minus the mLoadedApk assignment, which needs a ContextImpl
     *  Mac.apk does not have.
     *
     *  This exists so the attach-time call stack carries the genuine
     *  android.app.Application.attach frame a device shows, instead of jumping
     *  straight from the loader to attachBaseContext. attachBaseContext stays a
     *  VIRTUAL call, so an app subclass override is invoked exactly as before.
     *  Package-private and final, as on AOSP -- android.app.ActivityThread
     *  .macapkAttachApplication is the bridge for callers outside this package. */
    /* package */ final void attach(Context context) {
        attachBaseContext(context);
    }

    /** Public bridge so android.app.Instrumentation (different package from
     *  ContextWrapper, where attachBaseContext is protected) can attach the
     *  base Context the way AOSP's Application.attach(Context) does. AOSP's
     *  ActivityThread builds the Application via Instrumentation.newApplication
     *  then the Application's attach() calls attachBaseContext(); we expose the
     *  same wiring without widening ContextWrapper's protected method. */
    public void macapkAttachBaseContext(Context base) {
        if (base != null) attachBaseContext(base);
    }

    /**
     * Per AOSP frameworks/base Application.java, this is a STATIC method
     * that returns the name of the current process. The default process
     * name equals the package name; android:process="..." manifest
     * entries produce ":suffix" forms which Mac.apk does not split.
     * Google Calculator's CalculatorApplication.onCreate calls this
     * during boot -- absent, it threw NoSuchMethodError after the
     * isIsolated stub let it past the first wall (v1.0.42).
     */
    public static String getProcessName() {
        // The process is bound BEFORE the Application constructor runs. Its
        // Context/package fields are populated later and cannot name a process
        // for subclass field initializers (AOSP delegates to ActivityThread).
        return ActivityThread.currentProcessName();
    }

    /** Application-scope <meta-data android:name="..." android:value=
     *  "..."/> entries from the manifest. Engines query these via
     *  PackageManager.getApplicationInfo().metaData to discover their
     *  native lib name (`android.app.lib_name`), Google Play Games
     *  app-id, AppLovin SDK key, etc. */
    public void macapkSetApplicationMetaData(
            java.util.Map<String, String> meta) {
        this.applicationMetaData = meta;
    }
    private java.util.Map<String, String> applicationMetaData;

    /** Parallel ANDROID-TYPE map for applicationMetaData entries
     *  (binary-manifest path; empty/null on the apktool text
     *  fallback). Forwarded into MacApkPackageManager so meta-data
     *  Bundle typing matches AOSP PackageParser. */
    public void macapkSetApplicationMetaTypes(
            java.util.Map<String, String> types) {
        this.applicationMetaTypes = types;
    }
    private java.util.Map<String, String> applicationMetaTypes;

    /** Service-class-name -> ServiceEntry map from the manifest.
     *  Consumed by PackageManager.getServiceInfo(ComponentName,
     *  GET_META_DATA) so Firebase ComponentDiscovery can enumerate
     *  registrars. We store via Object to avoid leaking the
     *  com.macapk.loader type out of android-framework.jar. */
    public void macapkSetServiceMap(java.util.Map<String, ?> services) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, com.macapk.loader.Manifest.ServiceEntry> typed =
            (java.util.Map<String, com.macapk.loader.Manifest.ServiceEntry>) services;
        this.serviceMap = typed;
    }
    private java.util.Map<String, com.macapk.loader.Manifest.ServiceEntry> serviceMap;

    /** Provider-class-name -> ProviderEntry map from the manifest.
     *  Consumed by PackageManager.getProviderInfo(ComponentName,
     *  GET_META_DATA) so androidx.startup's InitializationProvider can
     *  read its own <meta-data> children to find Initializer classes.
     *  Same Object-erasure trick as serviceMap above. */
    public void macapkSetProviderMap(java.util.Map<String, ?> providers) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, com.macapk.loader.Manifest.ProviderEntry> typed =
            (java.util.Map<String, com.macapk.loader.Manifest.ProviderEntry>) providers;
        this.providerMap = typed;
    }
    private java.util.Map<String, com.macapk.loader.Manifest.ProviderEntry> providerMap;

    /** Our AppClassLoader (rooted at the APK's classes.jar). Engines
     *  like axmol cache `Context.getClassLoader()` early in
     *  nativeSetContext, then call `ClassLoader.loadClass(...)` from
     *  JNI threads to look up their own classes
     *  (e.g. `org/axmol/lib/AxmolRenderer`). The Thread-context
     *  classloader is not reliably the APK loader on every thread the
     *  JVM creates -- JNI threads attached from native start out with
     *  the system classloader. Returning the AppClassLoader directly
     *  here fixes class lookups regardless of which thread is asking. */
    public void macapkSetClassLoader(ClassLoader cl) {
        this.classLoader = cl;
    }
    private ClassLoader classLoader;

    public String getMacApkApkPath()     { return apkPath; }
    public File   getMacApkAppDir()      { return appDir; }
    public File   getMacApkNativeLibDir(){ return nativeLibraryDir; }

    public static Application getInstance() { return sInstance; }

    /** THE canonical read of the APP's OWN declared targetSdkVersion, for the
     *  compat gates that have no Context to ask (android.os.AsyncTask,
     *  android.os.Message, android.os.StrictMode, dalvik.system.VMRuntime --
     *  AOSP drives all four from ActivityThread.handleBindApplication, which we
     *  have no equivalent of).
     *
     *  Returns 0 when it is not resolvable yet, which is a state AOSP never has
     *  (a real process always has an ApplicationInfo by bind time) -- and also
     *  for an app whose REAL targetSdk is 0 (no <uses-sdk>); a caller tells the
     *  two apart with macapkTargetSdkKnown below, never with `> 0`. THE
     *  CONVENTION, and every gate in this tree must cite it: an UNRESOLVED
     *  targetSdk takes the MODERN branch and MUST NOT be latched -- guessing
     *  legacy for a modern app is the failure mode
     *  docs/legacy-compat-audit_2026-08-24.md 5.1 documents, and a latch on a
     *  transient freezes the whole process.
     *
     *  Do NOT substitute Build.VERSION.SDK_INT (that is the level we PRESENT,
     *  clamp(target,29,36)) or PresentedSdk. AOSP's input is this field. */
    public static int macapkAppTargetSdk() {
        try {
            Application app = sInstance;
            if (app == null) return 0;
            android.content.pm.ApplicationInfo ai = app.getApplicationInfo();
            if (ai != null && ai.targetSdkVersion > 0) return ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Is `targetSdkVersion` a REAL answer, or the 0 an ApplicationInfo gives
     *  before the loader has bound the manifest? Every compat gate that used to
     *  test `targetSdkVersion > 0` tests this instead.
     *
     *  The `> 0` sentinel had a blind spot: 0 is also a real targetSdk. AOSP
     *  leaves it at ParsingUtils.DEFAULT_TARGET_SDK_VERSION = 0 when a manifest
     *  has no <uses-sdk> (android14 ParsingUtils.java:54, PackageImpl.java:367)
     *  and treats it as older than every level, e.g. ParsingPackageUtils.java
     *  :953-956 "an SDK version of 0 is common for pre-Doughnut applications"
     *  followed by `getTargetSdkVersion() < DONUT`. So once the manifest has
     *  bound a 0 (macapkBindTargetSdk), a 0 read anywhere in the process IS the
     *  app's value: one app per process, and after the bind every populated
     *  ApplicationInfo carries that same 0. Before the bind, and for every app
     *  whose manifest declares a level, this is exactly `t > 0`, so the
     *  "unresolved takes the modern branch and never latches" convention above
     *  is unchanged. MACAPK_TARGETSDK_ZERO=0 (com.macapk.loader.Manifest) never
     *  binds a 0, which restores the plain `> 0` test everywhere. */
    public static boolean macapkTargetSdkKnown(int targetSdkVersion) {
        return targetSdkVersion > 0 || (targetSdkVersion == 0 && sMacapkTargetSdkBoundZero);
    }

    /** Our handleBindApplication moment for the targetSdk: called with the
     *  MANIFEST's parsed value (macapkInitVersion). Static and separate so an
     *  offline gate can bind without constructing an Application. */
    public static void macapkBindTargetSdk(int manifestTargetSdk) {
        sMacapkTargetSdkBoundZero = manifestTargetSdk == 0
                && com.macapk.loader.Manifest.TARGETSDK_ZERO;
    }
    private static volatile boolean sMacapkTargetSdkBoundZero;

    public void onCreate()                               {}
    public void onTerminate()                            {}
    // AOSP Application overrides these three ComponentCallbacks2 methods and,
    // in addition to running its own body, fans the event out to every
    // registered ComponentCallbacks (see frameworks/base/core/java/android/app/
    // Application.java dispatchConfigurationChanged/dispatchLowMemory/
    // dispatchTrimMemory). We mirror that: the public on*() are the app
    // subclass override points (default empty), and the dispatch* helpers below
    // do the fan-out. ActivityHost/config-change code drives the dispatch*
    // forms so a registered ComponentCallbacks (Compose's image-vector cache,
    // Glide's MemoryCategory trimmer, ad SDKs) actually receives the callback.
    public void onConfigurationChanged(Configuration newConfig) {}
    public void onLowMemory()                            {}
    public void onTrimMemory(int level)                  {}

    // Registered per-activity lifecycle observers. MUST be stored and driven (a
    // prior no-op left androidx ProcessLifecycleOwner -- fed via LifecycleDispatcher /
    // ProcessLifecycleInitializer / ReportFragment, which register HERE -- stuck below
    // STARTED, so foreground-gated SDKs never fired: e.g. Play Games'
    // AutomaticGamesAuthenticator never triggered sign-in, hanging game bootstraps
    // that await the auth/foreground signal before LoadScene). Core Android framework
    // surface; every app depends on it.
    private final java.util.ArrayList<ActivityLifecycleCallbacks> mActivityLifecycleCallbacks =
            new java.util.ArrayList<>();
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks cb) {
        if (cb == null) return;
        synchronized (mActivityLifecycleCallbacks) {
            if (!mActivityLifecycleCallbacks.contains(cb)) mActivityLifecycleCallbacks.add(cb);
        }
    }
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks cb) {
        synchronized (mActivityLifecycleCallbacks) { mActivityLifecycleCallbacks.remove(cb); }
    }
    // Snapshot first -- a callback may (un)register during iteration. Tolerant -- one
    // throwing SDK observer must not abort the lifecycle drive.
    private ActivityLifecycleCallbacks[] snapshotLifecycleCallbacks() {
        synchronized (mActivityLifecycleCallbacks) {
            if (mActivityLifecycleCallbacks.isEmpty()) return null;
            return mActivityLifecycleCallbacks.toArray(new ActivityLifecycleCallbacks[0]);
        }
    }
    // v1.0.363: re-landed the AOSP-faithful Pre/base/Post 3-phase dispatch.
    // This is what androidx's API-29 ReportFragment.LifecycleCallbacks uses
    // (it overrides onActivityPostCreated/PostStarted/PostResumed) to drive a
    // ComponentActivity's LifecycleRegistry to RESUMED -- the R8-rename-proof
    // path (the app advances its OWN renamed registry; we never reflect by
    // name). It was reverted at v1.0.297 because firing onActivityPostCreated
    // runs Reddit's RedditCrashlyticsUserBootstrapper -> a JNI call into
    // libreddit-ndk; back then the .apkm arch-split native libs failed to
    // extract so that call hit a null stub -> SIGSEGV. Both preconditions are
    // now met: (a) split-APK lib/* extraction works (the arm64 split's *.so
    // land in the install dir's lib/arm64 and translate on demand), and (b) an
    // unbound JNI native method now degrades to UnsatisfiedLinkError rather
    // than a wild call (see native_methods / app_native_stub). Each callback
    // is individually try-guarded so one app's misbehaving observer can't
    // abort the whole lifecycle dispatch.
    // Diagnostic: env-gated dump of the registered ActivityLifecycleCallbacks
    // at dispatch time. The R8-rename-proof lifecycle drive depends on the
    // app's own ReportFragment$LifecycleCallbacks being in this list; this
    // lets us confirm it registered + is fired, without reflecting by name.
    private static final boolean LC_TRACE = System.getenv("MACAPK_LIFECYCLE_TRACE") != null;
    private static void lcTrace(String phase, ActivityLifecycleCallbacks[] cbs) {
        if (!LC_TRACE) return;
        StringBuilder sb = new StringBuilder();
        sb.append("dispatch ").append(phase).append(": ")
          .append(cbs == null ? 0 : cbs.length).append(" callback(s)");
        if (cbs != null) for (ActivityLifecycleCallbacks cb : cbs) {
            sb.append("\n    - ").append(cb.getClass().getName());
        }
        android.util.Log.i("Mac.apk.AppLifecycle", sb.toString());
    }

    /** An ActivityLifecycleCallbacks implementation that throws USED to vanish
     *  into an empty catch here. That is worse than it sounds: these callbacks
     *  are how apps track their own foreground/background state, so a swallowed
     *  throw does not just lose a log line, it silently DESYNCHRONISES the app's
     *  model of its own lifecycle -- e.g. a started-activity counter that never
     *  increments, so the next onActivityStopped takes it to zero and the app
     *  concludes it has been backgrounded while its activity is on screen.
     *
     *  AOSP does not swallow at all: ActivityThread lets the throw propagate and
     *  the app crashes. We keep catching (this runtime's house style is to let a
     *  broken SDK not take the process down) but we no longer keep it a secret.
     *  Rate-limited so a callback that throws every frame cannot flood the log.
     */
    private static final java.util.concurrent.atomic.AtomicInteger LC_THREW =
        new java.util.concurrent.atomic.AtomicInteger();
    private static void lcThrew(String phase, Object cb, Throwable t) {
        if (LC_THREW.getAndIncrement() >= 40) return;
        android.util.Log.w("Mac.apk.AppLifecycle",
            "ActivityLifecycleCallbacks." + phase + " threw in "
            + (cb == null ? "null" : cb.getClass().getName()) + ": " + t);
    }
    public void dispatchActivityCreated(Activity a, Bundle b) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        lcTrace("created", cbs);
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPreCreated(a, b); } catch (Throwable t) { lcThrew("onActivityPreCreated", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityCreated(a, b); } catch (Throwable t) { lcThrew("onActivityCreated", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostCreated(a, b); } catch (Throwable t) { lcThrew("onActivityPostCreated", cb, t); } }
    }
    public void dispatchActivityStarted(Activity a) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        lcTrace("started", cbs);
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPreStarted(a); } catch (Throwable t) { lcThrew("onActivityPreStarted", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityStarted(a); } catch (Throwable t) { lcThrew("onActivityStarted", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostStarted(a); } catch (Throwable t) { lcThrew("onActivityPostStarted", cb, t); } }
    }
    public void dispatchActivityResumed(Activity a) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        lcTrace("resumed", cbs);
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPreResumed(a); } catch (Throwable t) { lcThrew("onActivityPreResumed", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityResumed(a); } catch (Throwable t) { lcThrew("onActivityResumed", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostResumed(a); } catch (Throwable t) { lcThrew("onActivityPostResumed", cb, t); } }
    }
    public void dispatchActivityPaused(Activity a) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        lcTrace("paused", cbs);
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPrePaused(a); } catch (Throwable t) { lcThrew("onActivityPrePaused", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPaused(a); } catch (Throwable t) { lcThrew("onActivityPaused", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostPaused(a); } catch (Throwable t) { lcThrew("onActivityPostPaused", cb, t); } }
    }
    public void dispatchActivityStopped(Activity a) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        lcTrace("stopped", cbs);
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPreStopped(a); } catch (Throwable t) { lcThrew("onActivityPreStopped", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityStopped(a); } catch (Throwable t) { lcThrew("onActivityStopped", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostStopped(a); } catch (Throwable t) { lcThrew("onActivityPostStopped", cb, t); } }
    }
    public void dispatchActivityDestroyed(Activity a) {
        ActivityLifecycleCallbacks[] cbs = snapshotLifecycleCallbacks();
        if (cbs == null) return;
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPreDestroyed(a); } catch (Throwable t) { lcThrew("onActivityPreDestroyed", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityDestroyed(a); } catch (Throwable t) { lcThrew("onActivityDestroyed", cb, t); } }
        for (ActivityLifecycleCallbacks cb : cbs) { try { cb.onActivityPostDestroyed(a); } catch (Throwable t) { lcThrew("onActivityPostDestroyed", cb, t); } }
    }
    // ComponentCallbacks family. AppLovin + SafeDK + most ad SDKs, plus
    // Compose's AndroidCompositionLocals image-vector cache and Glide's
    // memory-trim listener, register here to react to onLowMemory / config
    // changes / trim-memory. v1.0.464 (subsystem-04 close-list): was a no-op,
    // so any registered ComponentCallbacks never heard a configuration change
    // or a trim-memory level even when Mac.apk DID drive one. Maintain the
    // real list AOSP keeps (Context.mComponentCallbacks) and fan out from the
    // dispatch* helpers below. We don't synthesize memory-pressure events on
    // macOS, but a config change (rotation / night-mode) is a real event the
    // launcher can drive, and the registration must round-trip either way.
    private final java.util.ArrayList<android.content.ComponentCallbacks> mComponentCallbacks =
            new java.util.ArrayList<>();
    public void registerComponentCallbacks(android.content.ComponentCallbacks cb) {
        if (cb == null) return;
        synchronized (mComponentCallbacks) {
            if (!mComponentCallbacks.contains(cb)) mComponentCallbacks.add(cb);
        }
    }
    public void unregisterComponentCallbacks(android.content.ComponentCallbacks cb) {
        synchronized (mComponentCallbacks) { mComponentCallbacks.remove(cb); }
    }
    // Snapshot so a callback that (un)registers during dispatch can't CME, and
    // each call is try-guarded so one throwing SDK observer can't abort the fan-out.
    private android.content.ComponentCallbacks[] snapshotComponentCallbacks() {
        synchronized (mComponentCallbacks) {
            if (mComponentCallbacks.isEmpty()) return null;
            return mComponentCallbacks.toArray(new android.content.ComponentCallbacks[0]);
        }
    }
    /** AOSP Application.dispatchConfigurationChanged: run the app's own
     *  onConfigurationChanged then fan out to every registered callback. */
    public void dispatchConfigurationChanged(Configuration newConfig) {
        try { onConfigurationChanged(newConfig); } catch (Throwable t) {}
        android.content.ComponentCallbacks[] cbs = snapshotComponentCallbacks();
        if (cbs == null) return;
        for (android.content.ComponentCallbacks cb : cbs) {
            try { cb.onConfigurationChanged(newConfig); } catch (Throwable t) {}
        }
    }
    /** AOSP Application.dispatchLowMemory: app body then fan-out. */
    public void dispatchLowMemory() {
        try { onLowMemory(); } catch (Throwable t) {}
        android.content.ComponentCallbacks[] cbs = snapshotComponentCallbacks();
        if (cbs == null) return;
        for (android.content.ComponentCallbacks cb : cbs) {
            try { cb.onLowMemory(); } catch (Throwable t) {}
        }
    }
    /** AOSP Application.dispatchTrimMemory: app body then fan-out to the
     *  ComponentCallbacks2 subset (onTrimMemory is only on the 2 interface;
     *  plain ComponentCallbacks listeners are skipped, matching AOSP). */
    public void dispatchTrimMemory(int level) {
        try { onTrimMemory(level); } catch (Throwable t) {}
        android.content.ComponentCallbacks[] cbs = snapshotComponentCallbacks();
        if (cbs == null) return;
        for (android.content.ComponentCallbacks cb : cbs) {
            if (cb instanceof android.content.ComponentCallbacks2) {
                try { ((android.content.ComponentCallbacks2) cb).onTrimMemory(level); }
                catch (Throwable t) {}
            }
        }
    }

    /** AOSP Application.OnProvideAssistDataListener: invoked when the
     *  Assistant requests assist data for an activity. Mac.apk has no
     *  assistant transport, so the register/unregister round-trips but the
     *  callback is never invoked (the "feature absent" contract). Declared so
     *  reflective callers + apps that register an assist-data provider link.
     *  AOSP: frameworks/base/core/java/android/app/Application.java. */
    public interface OnProvideAssistDataListener {
        void onProvideAssistData(Activity activity, Bundle data);
    }
    private final java.util.ArrayList<OnProvideAssistDataListener> mAssistCallbacks =
            new java.util.ArrayList<>();
    public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        if (callback == null) return;
        synchronized (mAssistCallbacks) {
            if (!mAssistCallbacks.contains(callback)) mAssistCallbacks.add(callback);
        }
    }
    public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        synchronized (mAssistCallbacks) { mAssistCallbacks.remove(callback); }
    }

    /** AOSP Application.ActivityLifecycleCallbacks
     *  (frameworks/base/core/java/android/app/Application.java). The 7
     *  base callbacks are abstract; the 14 Pre/Post callbacks are
     *  `default {}` no-ops -- declared so framework consumers that
     *  override only the Pre/Post variant compile and dispatch
     *  correctly. THE LOAD-BEARING ONES: androidx's API-29
     *  ReportFragment.LifecycleCallbacks (registered via
     *  Activity.registerActivityLifecycleCallbacks, which we forward to
     *  this Application's list) overrides ONLY onActivityPostCreated /
     *  onActivityPostStarted / onActivityPostResumed, and from those it
     *  calls LifecycleRegistry.handleLifecycleEvent(ON_CREATE/START/
     *  RESUME). That is the R8-rename-proof way to drive a ComponentActivity's
     *  Lifecycle to RESUMED (no reflection by name): the app's own
     *  ReportFragment, using the app's own renamed androidx classes,
     *  advances its own registry. Compose's WindowRecomposer launches
     *  runRecomposeAndApplyChanges on ON_CREATE, so without the Post
     *  dispatch a Compose UI (Reddit's login) never composes. */
    public interface ActivityLifecycleCallbacks {
        default void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {}
        void onActivityCreated(Activity activity, Bundle savedInstanceState);
        default void onActivityPostCreated(Activity activity, Bundle savedInstanceState) {}
        default void onActivityPreStarted(Activity activity) {}
        void onActivityStarted(Activity activity);
        default void onActivityPostStarted(Activity activity) {}
        default void onActivityPreResumed(Activity activity) {}
        void onActivityResumed(Activity activity);
        default void onActivityPostResumed(Activity activity) {}
        default void onActivityPrePaused(Activity activity) {}
        void onActivityPaused(Activity activity);
        default void onActivityPostPaused(Activity activity) {}
        default void onActivityPreStopped(Activity activity) {}
        void onActivityStopped(Activity activity);
        default void onActivityPostStopped(Activity activity) {}
        default void onActivityPreSaveInstanceState(Activity activity, Bundle outState) {}
        void onActivitySaveInstanceState(Activity activity, Bundle outState);
        default void onActivityPostSaveInstanceState(Activity activity, Bundle outState) {}
        default void onActivityPreDestroyed(Activity activity) {}
        void onActivityDestroyed(Activity activity);
        default void onActivityPostDestroyed(Activity activity) {}
    }

    @Override public String getPackageName()     { return packageName; }
    @Override public Resources getResources()    { return resources; }
    @Override public AssetManager getAssets()    { return assets; }
    @Override public Context getApplicationContext() { return this; }

    // cocos2d-x 2.0/2.1's Cocos2dxActivity.setPackageName (called from the
    // app's MainActivity.onCreate) does
    // `nativeSetPaths(getPackageManager().getApplicationInfo(pkg,0).sourceDir)`,
    // which hands the APK path to CCFileUtils::setResourcePath: the
    // s_strResourcePath string that ALL relative asset lookups
    // (fonts/gamefont.fnt, sprite atlases, audio, JSON) are read from via
    // getFileDataFromZip. It reads sourceDir, not getPackageCodePath(), but
    // the two are the same value on AOSP and here (see below). Without a
    // real APK path there, every getFileData returns empty,
    // CCLabelBMFont::create returns NULL, the caller addChilds NULL, and
    // retain() segfaults. Same value for getPackageResourcePath -- AOSP
    // always returns the APK path for both on a single-APK install.
    // ⚠ v1.0.938: these must agree with ApplicationInfo.sourceDir, and since
    // v1.0.938 sourceDir is the `<dataDir>/base.apk` link rather than the raw
    // dropped-APK path. AOSP has no way for them to disagree:
    // ContextImpl.getPackageCodePath() is `mPackageInfo.getAppDir()`, which IS
    // ApplicationInfo.sourceDir. Returning the raw path here would have
    // reintroduced, in miniature, exactly the class of bug this session spent
    // its time on: one question answered two ways.
    // Both paths address byte-identical content (the link is a hard link), so
    // nothing was broken -- but an app that compares getPackageCodePath() with
    // getApplicationInfo().sourceDir would have seen an incoherent device.
    @Override public String getPackageCodePath()     { return macapkCodePath(); }
    @Override public String getPackageResourcePath() { return macapkCodePath(); }
    /** The one answer for getPackageCodePath()/getPackageResourcePath():
     *  ApplicationInfo.sourceDir, whatever file that names (v1.0.1586: the
     *  installed base.apk's published link; nothing assumes a `_merged.apk`
     *  or any other spelling). PRIVATE and built on the private
     *  macapkApplicationInfoReal() so AppBaseContext can call it under the
     *  terminator rule (no overridable method on the path). */
    private String macapkCodePath() {
        // DIAGNOSTIC (MACAPK_TEST_APK_PATH, default OFF): pairip's integrity VM
        // materializes getPackageCodePath()/sourceDir into its hash-steered table;
        // ours used to leak the macOS `_merged.apk` path (a device returns
        // /data/app/~~<h>/<pkg>-<h>/base.apk). Test whether that dishonest string
        // is the branch gate. See docs/pairip-vm-oracle-fork-ptrace-is-the-lever.
        String t = System.getenv("MACAPK_TEST_APK_PATH");
        if (t != null && !t.isEmpty()) return t;
        // Before macapkInitInstall() there is no path to report, and asking
        // for the record here would BUILD the PackageManager with a null
        // apkPath and cache it for the rest of the run. The answer was ""
        // in that window before v1.0.1586 too; keep it, without the side
        // effect.
        if (apkPath == null) return "";
        try {
            android.content.pm.ApplicationInfo ai = macapkApplicationInfoReal();
            if (ai != null && ai.sourceDir != null && !ai.sourceDir.isEmpty()) {
                return ai.sourceDir;
            }
        } catch (Throwable ignored) {}
        return apkPath != null ? apkPath : "";
    }
    @Override public PackageManager getPackageManager() { return macapkPackageManagerReal(); }
    /** The process's one PackageManager, built on first use. Private so the
     *  paths that must not take a virtual hop (macapkApplicationInfoReal,
     *  AppBaseContext) can reach it; an Application subclass that overrides
     *  getPackageManager() still wraps what app code sees. */
    private PackageManager macapkPackageManagerReal() {
        if (packageManager == null) {
            com.macapk.loader.MacApkPackageManager pm =
                new com.macapk.loader.MacApkPackageManager(
                    packageName, apkPath,
                    appDir != null ? appDir.getAbsolutePath() : null,
                    nativeLibraryDir != null ? nativeLibraryDir.getAbsolutePath() : null,
                    applicationMetaData,
                    serviceMap,
                    providerMap);
            pm.macapkSetApplicationMetaTypes(applicationMetaTypes);
            pm.macapkSetVersionInfo(minSdk, targetSdk, versionName, versionCode);
            if (macapkDataDir != null) pm.macapkSetReportedDataDir(macapkDataDir.getAbsolutePath());
            packageManager = pm;
        }
        return packageManager;
    }
    private PackageManager packageManager;

    /** AOSP ContextImpl.getApplicationInfo() is
     *      return mPackageInfo.getApplicationInfo();
     *  i.e. LoadedApk.mApplicationInfo -- THE SAME RECORD PackageManager hands
     *  back for this package. A process has exactly one ApplicationInfo; the
     *  Context and the PackageManager are two doors onto it, never two sources.
     *
     *  ⚠ THIS METHOD USED TO BUILD ITS OWN. That was the structural bug of this
     *  class: it emitted a metaData Bundle unconditionally, never set labelRes
     *  / nonLocalizedLabel / theme / flags / taskAffinity, and had its own
     *  @integer/ resolution -- while MacApkPackageManager.makeAppInfo set the
     *  label and the theme, typed meta-data the way PackageParser does, and
     *  gated it on GET_META_DATA. So `context.getApplicationInfo().theme` was 0
     *  and `pm.getApplicationInfo(pkg, 0).theme` was the app's real theme, in
     *  the same process, for the same app. An app that reads one and acts on
     *  the other is the classic re-read-forever shape, and it is exactly what
     *  the fwprobe PM.ctxagree.* subgroup measures (theme and nonLocalizedLabel
     *  both read "these two disagree" here and "these two agree" on a device).
     *
     *  The FLAGS argument is AOSP's, not a choice: ActivityManagerService binds
     *  a process with STOCK_PM_FLAGS == GET_SHARED_LIBRARY_FILES, WITHOUT
     *  GET_META_DATA. That is why `context.getApplicationInfo().metaData` is
     *  NULL on a real device and every SDK that wants application meta-data
     *  calls `pm.getApplicationInfo(pkg, GET_META_DATA)` instead -- it has to,
     *  or it would not work on Android either.
     *
     *  Cached because AOSP returns the same object every time and this is a hot
     *  path (per-child resource / theme resolution calls it constantly); the
     *  old body allocated an ApplicationInfo and rebuilt a Bundle on each call. */
    private volatile android.content.pm.ApplicationInfo cachedApplicationInfo;

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        return macapkApplicationInfoReal();
    }

    /** The body of getApplicationInfo(), reached through OUR PackageManager
     *  by a private, non-virtual path. AOSP's ContextImpl.getApplicationInfo
     *  never consults getPackageManager() at all (it is LoadedApk's record),
     *  so a subclass override of getPackageManager() rightly does not steer
     *  this; and being private is what lets AppBaseContext.getPackageCodePath
     *  reach the same record without a hop an app could turn into a cycle. */
    private android.content.pm.ApplicationInfo macapkApplicationInfoReal() {
        android.content.pm.ApplicationInfo cached = cachedApplicationInfo;
        if (cached != null) return cached;
        android.content.pm.ApplicationInfo info = null;
        try {
            PackageManager pm = macapkPackageManagerReal();
            if (pm != null && packageName != null) {
                info = pm.getApplicationInfo(packageName,
                        PackageManager.GET_SHARED_LIBRARY_FILES);
            }
        } catch (Throwable t) {
            // Never let a manifest/resource hiccup turn getApplicationInfo()
            // into null or into a throw: engines call it during their own init
            // and dereference the result immediately (cocos2d-x hands
            // sourceDir to nativeSetApkPath). The fallback below carries the
            // install paths, which is the part those callers need.
            info = null;
        }
        if (info == null) {
            info = new android.content.pm.ApplicationInfo();
            info.packageName      = packageName;
            info.processName      = packageName;
            info.sourceDir        = apkPath;
            info.publicSourceDir  = apkPath;
            // v1.0.1641: the DATA dir (see macapkSetDataDir); the code dir only
            // when boot has not published one.
            info.dataDir          = macapkDataDir != null ? macapkDataDir.getAbsolutePath()
                                  : appDir != null ? appDir.getAbsolutePath() : null;
            info.nativeLibraryDir = nativeLibraryDir != null
                                     ? nativeLibraryDir.getAbsolutePath()
                                     : null;
            // MACAPK_DATADIR_DEVICE (EXPERIMENT): this FALLBACK ApplicationInfo (used when the
            // PackageManager record is unavailable) leaks the macOS appDir/apkPath. pairip reads
            // a code path here, dirnames it, and memcmp's vs /data/app|/mnt/asec|/mnt/expand at
            // dispatch record 10092 (buf[0x437]). Present device-faithful /data paths.
            if (System.getenv("MACAPK_DATADIR_DEVICE") != null && packageName != null) {
                String inst = "/data/app/~~m4c4pk0Zx9AbC1dEfGhIjw==/" + packageName
                              + "-Qm4c4pk0Zx9AbC1dEfGh8Q==";
                info.sourceDir        = inst + "/base.apk";
                info.publicSourceDir  = info.sourceDir;
                info.dataDir          = "/data/data/" + packageName;
                info.nativeLibraryDir = inst + "/lib/arm64";
            }
            // v1.0.1586: the installed splits, so even this fallback record
            // has the device shape (null for a package without any; see
            // MacApkPackageManager.makeAppInfo for the AOSP lines).
            try {
                java.util.List<File> set = com.macapk.loader.InstalledSplits.of(apkPath);
                if (set.size() > 1) {
                    int n = set.size() - 1;
                    String[] names = new String[n];
                    String[] dirs  = new String[n];
                    boolean device = System.getenv("MACAPK_DATADIR_DEVICE") != null
                                     && packageName != null;
                    String inst = "/data/app/~~m4c4pk0Zx9AbC1dEfGhIjw==/" + packageName
                                  + "-Qm4c4pk0Zx9AbC1dEfGh8Q==";
                    for (int i = 0; i < n; i++) {
                        File f = set.get(i + 1);
                        names[i] = com.macapk.loader.InstalledSplits.splitName(f);
                        dirs[i]  = device ? inst + "/" + f.getName() : f.getAbsolutePath();
                    }
                    info.splitNames            = names;
                    info.splitSourceDirs       = dirs;
                    info.splitPublicSourceDirs = dirs.clone();
                }
            } catch (Throwable ignored) {}
            info.minSdkVersion    = minSdk;
            info.targetSdkVersion = targetSdk;
            // Do NOT cache the fallback: it is built before the loader has
            // finished wiring the PackageManager, and the real record must win
            // on the next call.
            macapkApplyTestApkPath(info);
            return info;
        }
        macapkApplyTestApkPath(info);
        cachedApplicationInfo = info;
        return info;
    }
    // DIAGNOSTIC (MACAPK_TEST_APK_PATH): see macapkCodePath(). Overrides the
    // sourceDir/publicSourceDir a guest reads off ApplicationInfo directly.
    private static void macapkApplyTestApkPath(android.content.pm.ApplicationInfo info) {
        String t = System.getenv("MACAPK_TEST_APK_PATH");
        if (t != null && !t.isEmpty() && info != null) {
            info.sourceDir = t; info.publicSourceDir = t;
        }
    }

    // Historical note (2026-05-20), kept because it is the reason there is no
    // meta-data injection anywhere in this path: a default
    // `com.google.android.gms.version=12451000` was once injected here to make
    // Google Mobile Ads' <clinit> stop throwing IllegalStateException when the
    // manifest entry was an unresolved `@integer/...` reference. That "fix" had
    // the opposite of the intended effect -- Mobile Ads init then proceeded
    // further into the Ads SDK and started intermittently SEGV'ing inside
    // libgame.so's libjpeg-turbo (jpeg_idct_islow with a null coef_block). The
    // pre-injection behaviour was healthy: Mobile Ads <clinit> threw, the
    // EULA/init path caught it, and the game rendered cleanly. The real fix was
    // to RESOLVE the reference, which MacApkPackageManager.putTypedMeta now does
    // for every meta-data value, typed the way AOSP PackageParser types it.

    @Override public ClassLoader getClassLoader() {
        if (classLoader != null) return classLoader;
        return Thread.currentThread().getContextClassLoader();
    }
    @Override public File getFilesDir()          { return filesDir != null ? filesDir : new File("."); }
    @Override public File getCacheDir()          { return cacheDir != null ? cacheDir : new File("."); }
    @Override public File getDir(String name, int mode) {
        File d = new File(getDataDir(), "app_" + name); d.mkdirs(); return d;
    }

    /** Cache of opened SharedPreferences. Real Android scopes these per
     *  process so two callers naming the same prefs file see the same
     *  edits live; we mirror that by interning by name. */
    private final HashMap<String, SharedPreferences> prefsCache = new HashMap<>();

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return macapkGetSharedPreferencesReal(name, mode);
    }
    /** The REAL, NON-overridable SharedPreferences provider. AOSP: ContextImpl
     *  serves getSharedPreferences from disk and a ContextWrapper delegates DOWN
     *  to it. The AppBaseContext terminator (below) calls THIS directly, never the
     *  overridable getSharedPreferences: an app that overrides getSharedPreferences
     *  and delegates to its base context -- Instagram's InstagramAppShell ->
     *  X.3Fk -> InstagramApplicationForMainProcess.getSharedPrefsInternal -> back --
     *  would otherwise cycle base -> app.getSharedPreferences -> base -> ... and
     *  StackOverflow (FATAL AppInit1, System.exit(10)) in attachBaseContext, before
     *  the first activity. Same terminator rule as getContentResolver /
     *  getSystemService. The shared prefsCache keeps one object per file. */
    synchronized SharedPreferences macapkGetSharedPreferencesReal(String name, int mode) {
        // AOSP's pre-KitKat null-name compat; the citation is on
        // android.content.Context.getSharedPreferences, which carries the same
        // branch. A no-op for every non-null name and every app at or above 19.
        if (name == null) {
            int targetSdkVersion = Context.macapkTargetSdkOf(this);
            if (macapkTargetSdkKnown(targetSdkVersion)
                    && targetSdkVersion < android.os.Build.VERSION_CODES.KITKAT) {
                name = "null";
            }
        }
        SharedPreferences p = prefsCache.get(name);
        if (p != null) return p;
        // Interned by FILE, not by this cache's key: the same file reached
        // through a raw Context must be the SAME object, or two independent
        // locks interleave the atomic-write dance and can destroy the data.
        p = com.macapk.loader.SharedPrefsImpl.macapkGet(this, name);
        prefsCache.put(name, p);
        return p;
    }

    /** Cache of system service shims. Apps frequently fetch the same
     *  service repeatedly (audio especially); real Android caches per
     *  process and we mirror that. */
    private final HashMap<String, Object> systemServices = new HashMap<>();

    @Override public synchronized Object getSystemService(String name) {
        Object svc = systemServices.get(name);
        if (svc != null) return svc;
        svc = createSystemService(name);
        if (svc != null) systemServices.put(name, svc);
        return svc;
    }

    private Object createSystemService(String name) {
        // ⚠ v1.0.938: AOSP's SystemServiceRegistry.getSystemService returns null
        // for an unknown name, and null is an unknown name -- it does NOT throw
        // (SystemServiceRegistry.java:1370-1373, `ServiceFetcher<?> fetcher =
        // SYSTEM_SERVICE_FETCHERS.get(name); ... return fetcher != null ? ... : null`,
        // reached through a HashMap.get that tolerates null).
        // Context.java already guards this; Application OVERRIDES getSystemService
        // and lost the guard, so `getSystemService(null)` NPE'd on the switch
        // instead of answering null. Probe row CTX.svc.nullName.
        if (name == null) return null;
        switch (name) {
            case ACTIVITY_SERVICE:     return new android.app.ActivityManager();
            case ALARM_SERVICE:        return new android.app.AlarmManager();
            case AUDIO_SERVICE:        return new android.media.AudioManager();
            case INPUT_SERVICE:        return new android.hardware.input.InputManager();
            case CONNECTIVITY_SERVICE: return new android.net.ConnectivityManager();
            case INPUT_METHOD_SERVICE: return android.view.inputmethod.InputMethodManager.peekInstance();
            case POWER_SERVICE:        return new android.os.PowerManager();
            case SENSOR_SERVICE:       return android.hardware.SensorManager.macapkNew();
            case TELEPHONY_SERVICE:    return new android.telephony.TelephonyManager();
            case UI_MODE_SERVICE:      return new android.app.UiModeManager();
            case VIBRATOR_SERVICE:     return new android.os.Vibrator();
            case WIFI_SERVICE:         return new android.net.wifi.WifiManager();
            case WINDOW_SERVICE:       return new com.macapk.loader.MacApkWindowManager();
            // v1.0.72: F-Droid's WorkManager initialization queries
            // JOB_SCHEDULER_SERVICE via this Application path. Without
            // a non-null return the cast (Kotlin checkNotNull) throws
            // and entire WorkManager init aborts. Stub the service so
            // schedules silently succeed.
            case JOB_SCHEDULER_SERVICE: return android.app.job.JobScheduler.STUB;
            case LAYOUT_INFLATER_SERVICE: return android.view.LayoutInflater.from(this);
            case KEYGUARD_SERVICE:     return new android.app.KeyguardManager();
            case NOTIFICATION_SERVICE: return new android.app.NotificationManager();
            // v1.0.313: ShortcutManager so getSystemService(ShortcutManager.class)
            // on the app context is non-null (Wikipedia NPE).
            case SHORTCUT_SERVICE:     return new android.content.pm.ShortcutManager();
            // v1.0.1595: nine more names whose manager class we already ship but
            // which this switch never returned, so getSystemService answered NULL.
            // A device NEVER answers null for a name it supports, and modern app
            // code is Kotlin: `getSystemService(...) as TelecomManager` compiles to
            // a non-null cast, so a null is not a graceful degrade -- it is an
            // immediate NPE on whatever thread asked. MEASURED on Instagram, which
            // died on exactly that during AppInit:
            //   NullPointerException: null cannot be cast to non-null type
            //   android.telecom.TelecomManager    (X.4pu.<init>)
            // uncaught on a ThreadPoolExecutor worker -> the app's own AOSP-style
            // KillApplicationHandler ended the process with System.exit(10).
            // Same failure shape as the JOB_SCHEDULER note above. Found with
            // `debug/levelprobe.sh --service-gate`, which walks every
            // Context.*_SERVICE constant and reports the nulls; these are the ones
            // whose class already exists here, so returning a real instance is
            // strictly closer to the device than null. No per-app code.
            case TELECOM_SERVICE:      return new android.telecom.TelecomManager();
            case NETWORK_STATS_SERVICE:return new android.app.usage.NetworkStatsManager();
            case TEXT_CLASSIFICATION_SERVICE:
                                       return new android.view.textclassifier.TextClassificationManager();
            case CARRIER_CONFIG_SERVICE:
                                       return new android.telephony.CarrierConfigManager();
            case EUICC_SERVICE:        return new android.telephony.euicc.EuiccManager();
            case CROSS_PROFILE_APPS_SERVICE:
                                       return new android.content.pm.CrossProfileApps();
            case DOMAIN_VERIFICATION_SERVICE:
                                       return new android.content.pm.verify.domain.DomainVerificationManager();
            case VPN_MANAGEMENT_SERVICE:
                                       return new android.net.VpnManager();
            case DISPLAY_HASH_SERVICE: return new android.view.displayhash.DisplayHashManager();
            // v1.0.464 (subsystem-04 close-list): for every name this switch
            // does NOT specialize, delegate to Context.getSystemService rather
            // than returning null. AOSP has exactly ONE service registry
            // (SystemServiceRegistry, reached via ContextImpl.getSystemService);
            // Application/Activity do not each maintain a divergent subset.
            // Before v1.0.463 Context.getSystemService was itself sparse, so the
            // v1.0.313 "blanket chain to super" experiment was reverted because
            // super returned ad-hoc/no managers and flipped behavior unevenly.
            // That precondition is gone: Context.getSystemService is now a
            // faithful per-service registry (LOCATION/CAMERA/BLUETOOTH/NFC/USB/
            // BATTERY/TELEPHONY/media/print/etc., each an honest "feature absent"
            // manager), and USER_SERVICE is still null there on purpose (no case
            // exists), so delegation preserves the deliberate USER_SERVICE
            // hold-back. The result: the app's own Context now returns the SAME
            // non-null managers a real device would, instead of an arbitrarily
            // smaller set than a non-app Context. Results are cached by the
            // caller (getSystemService above), so a delegated manager is created
            // at most once per name per process.
            default:
                return super.getSystemService(name);
        }
    }
}
