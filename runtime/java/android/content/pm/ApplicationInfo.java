package android.content.pm;
import android.os.Bundle;

// AOSP: `ApplicationInfo extends PackageItemInfo`. This inheritance is
// LOAD-BEARING, not cosmetic. Apps + SDKs read app metadata via
//   getfield android/content/pm/PackageItemInfo.metaData
// on an ApplicationInfo instance (e.g. AppsFlyer's manifest <meta-data>
// reader, Google Flogger's platform discovery, GMS). The bytecode names the
// field's declaring class as PackageItemInfo because in AOSP ApplicationInfo
// IS-A PackageItemInfo and metaData is inherited.
//
// Mac.apk runs the guest JVM with -Xverify:none, so the verifier does NOT
// reject `getfield PackageItemInfo.metaData` on a receiver that isn't a
// PackageItemInfo. If ApplicationInfo does not extend PackageItemInfo, that
// getfield reads at PackageItemInfo.metaData's offset within an object that
// has a DIFFERENT layout -> a garbage (non-null) reference -> the next
// invokevirtual on it (metaData.get(key)) faults inside the JVM's method
// resolver (receiver oop is bogus). That was the deterministic
// resolve_invokevirtual SIGSEGV that crashed HCR 1.43 (AppsFlyer) and other
// SDK-heavy apps on launch. So ApplicationInfo MUST extend PackageItemInfo and
// INHERIT (never shadow) name/packageName/labelRes/icon/metaData.
public class ApplicationInfo extends PackageItemInfo implements android.os.Parcelable {
    public String processName;
    public String className;
    public String dataDir;
    public String sourceDir;
    public String publicSourceDir;
    public String nativeLibraryDir;
    // AOSP API-21+ split-APK fields. v1.0.494: re-added but DELIBERATELY left
    // NULL (no initializer), which is the correct AOSP value for a non-split /
    // merged install AND the value the v1.0.471 revert note identified as safe.
    // History: these were once TRIED as EMPTY ARRAYS (hypothesis: newer Unity
    // reads them while computing streamingAssetsPath) and that REGRESSED Crossy
    // (its older libunity trapped on a guest brk right after getApplicationInfo
    // when the field existed-as-empty-array vs absent). The v1.0.471 note
    // concluded "the field must stay ABSENT, or be NULL, for the pre-2021
    // engines" -- so NULL is the safe middle ground: the field now EXISTS (so a
    // JNI GetFieldID no longer throws NoSuchFieldError) while reading as null
    // (the non-split case every engine tolerates). Driver: Firebase
    // Crashlytics-NDK's JniNativeApi reads ApplicationInfo.splitSourceDirs via
    // JNI during native crash-handler init (Duolingo) -> a missing field threw
    // an uncaught NoSuchFieldError on the Firebase background thread and killed
    // the app. Generic: any Crashlytics-NDK app hits this. Crossy is re-swept
    // to confirm null does not reintroduce the empty-array regression.
    //
    // v1.0.1586 (Phase 2 of docs/disk-footprint-and-space-efficiency): since the
    // installed split set is real (data/app/.../split_*.apk), MacApkPackageManager
    // fills all three the way ParsingPackageUtils.asSplit does -- the REAL list
    // for a split install (Crossy: config.arm64_v8a, config.armeabi_v7a), and
    // NULL, never an empty array, for a package without splits. That is exactly
    // what a device answers, so the v1.0.471 empty-array trap cannot recur from
    // here; the crossy sweep row remains the guard.
    public String[] splitNames;
    public String[] splitSourceDirs;
    public String[] splitPublicSourceDirs;
    // More AOSP String[] path fields. Same v1.0.494 rationale as the split
    // fields above: left NULL (the non-split/merged value) so a JNI GetFieldID
    // succeeds without reintroducing the empty-array Unity regression.
    // Crashlytics-NDK's JniNativeApi reads sharedLibraryFiles (and may read
    // resourceDirs/overlayPaths) right after splitSourceDirs while building the
    // native crash context; each missing field threw an uncaught
    // NoSuchFieldError on the Firebase background thread. Generic.
    public String[] sharedLibraryFiles;
    public String[] resourceDirs;
    public String[] overlayPaths;
    public int    targetSdkVersion;
    public int    minSdkVersion;
    /** AOSP public field (ApplicationInfo.targetSandboxVersion). The
     *  app-sandbox schema the app opted into via
     *  android:targetSandboxVersion; AOSP defaults it to 1 and only
     *  manifests that request the stricter sandbox set 2. Play Services'
     *  event logger reads it reflectively while building a crash/telemetry
     *  record, throwing NoSuchFieldError on a background thread when it is
     *  absent. Generic: any app that reads it sees AOSP's own default. */
    public int    targetSandboxVersion = 1;
    public int    flags;
    public int    theme;
    public int    uid = 10000;            // arbitrary stable per-app uid
    /** AOSP public field. True if the application is currently
     *  enabled by the system. F-Droid reads this during prefs setup
     *  (its package-listing path). We claim "always enabled" --
     *  every loaded APK is treated as if the user just installed
     *  it. NoSuchFieldError otherwise. */
    public boolean enabled = true;

    // ---- AOSP <application> attribute fields (v1.0.923) -------------------
    // Every field below is declared `public` in AOSP's ApplicationInfo with the
    // cited android-11.0.0_r48 line, and each one was measurably UNREACHABLE
    // here: the fwprobe answer-differential resolved 12 of them to
    // NoSuchFieldError against a real value on the reference. Field resolution
    // matches on name AND descriptor (JVMS 5.4.3.2), which is why
    // backupAgentName mattered even though a field of that NAME existed -- it
    // was declared `int` here and `String` upstream, so `getfield
    // ApplicationInfo.backupAgentName:Ljava/lang/String;` could never bind.
    //
    // Nothing in this tree ever WROTE the old int, so the type change is not a
    // behaviour change for us; it is the difference between an app's read
    // linking and throwing.

    /** ApplicationInfo.java:62. `<application android:taskAffinity>`; AOSP
     *  defaults it to the package name, which is what
     *  MacApkPackageManager.makeAppInfo assigns. */
    public String taskAffinity;
    /** ApplicationInfo.java:68. `<application android:permission>` -- the
     *  permission a caller needs to interact with this app's components. */
    public String permission;
    /** ApplicationInfo.java:86. `<application android:description>` resource id,
     *  0 when undeclared. */
    public int    descriptionRes;
    /** ApplicationInfo.java:101. `<application android:manageSpaceActivity>`. */
    public String manageSpaceActivityName;
    /** ApplicationInfo.java:109. `<application android:backupAgent>`.
     *  DESCRIPTOR-SENSITIVE: this is `String` upstream (it names a class), and
     *  was `int` here until v1.0.923. */
    public String backupAgentName;
    /** ApplicationInfo.java:127. `<application android:uiOptions>` bitmask. */
    public int    uiOptions = 0;
    /** ApplicationInfo.java:1046/1054/1062. `<supports-screens>`
     *  requiresSmallestWidthDp / compatibleWidthLimitDp / largestWidthLimitDp.
     *  0 means "not declared", which is the value for essentially every modern
     *  app (the attributes are pre-3.2 screen-compat machinery). */
    public int    requiresSmallestWidthDp  = 0;
    public int    compatibleWidthLimitDp   = 0;
    public int    largestWidthLimitDp      = 0;
    /** ApplicationInfo.java:1175. The storage volume this app's data lives on.
     *  Android always sets it (StorageManager.UUID_DEFAULT for internal
     *  storage); apps pass it to StorageStatsManager. */
    public java.util.UUID storageUuid;
    /** ApplicationInfo.java:1236. `<application android:appComponentFactory>`,
     *  null when undeclared. AndroidX's CoreComponentFactory is the common
     *  non-null value. */
    public String appComponentFactory;
    /** ApplicationInfo.java:1252, `public @Category int category =
     *  CATEGORY_UNDEFINED;`. ⚠ The initializer is load-bearing: leaving this at
     *  Java's default 0 would claim CATEGORY_GAME for every app. */
    public int    category = CATEGORY_UNDEFINED;

    /** AOSP public flags surface. Apps inspect these for behavioral
     *  branches (e.g. FLAG_DEBUGGABLE gates strict-mode probes).
     *  Values verbatim from android-11.0.0_r48 ApplicationInfo.java; the
     *  cited line is given where the constant is newly added here. */
    public static final int FLAG_SYSTEM              = 1<<0;
    public static final int FLAG_DEBUGGABLE         = 0x00000002;
    public static final int FLAG_HAS_CODE            = 1<<2;   // :265
    public static final int FLAG_PERSISTENT          = 1<<3;
    public static final int FLAG_FACTORY_TEST        = 1<<4;
    public static final int FLAG_ALLOW_TASK_REPARENTING = 1<<5;
    public static final int FLAG_ALLOW_CLEAR_USER_DATA  = 1<<6;   // :280
    public static final int FLAG_UPDATED_SYSTEM_APP  = 1<<7;
    public static final int FLAG_TEST_ONLY           = 0x00000100;
    public static final int FLAG_SUPPORTS_SMALL_SCREENS  = 1<<9;    // :407
    public static final int FLAG_SUPPORTS_NORMAL_SCREENS = 1<<10;   // :415
    public static final int FLAG_SUPPORTS_LARGE_SCREENS  = 1<<11;   // :423
    public static final int FLAG_RESIZEABLE_FOR_SCREENS  = 1<<12;   // :431
    public static final int FLAG_SUPPORTS_SCREEN_DENSITIES = 1<<13; // :441
    public static final int FLAG_VM_SAFE_MODE        = 1<<14;
    public static final int FLAG_ALLOW_BACKUP        = 0x00008000;  // :319
    public static final int FLAG_KILL_AFTER_RESTORE  = 1<<16;
    public static final int FLAG_RESTORE_ANY_VERSION = 1<<17;
    public static final int FLAG_EXTERNAL_STORAGE    = 1<<18;
    public static final int FLAG_SUPPORTS_XLARGE_SCREENS = 1<<19;   // :449
    public static final int FLAG_LARGE_HEAP          = 0x00100000;
    public static final int FLAG_STOPPED             = 1<<21;
    public static final int FLAG_SUPPORTS_RTL        = 1<<22;
    public static final int FLAG_INSTALLED           = 1<<23;       // :1340
    public static final int FLAG_IS_DATA_ONLY        = 1<<24;
    public static final int FLAG_IS_GAME             = 1<<25;
    public static final int FLAG_FULL_BACKUP_ONLY    = 1<<26;
    public static final int FLAG_USES_CLEARTEXT_TRAFFIC = 1<<27;
    public static final int FLAG_EXTRACT_NATIVE_LIBS = 1<<28;       // :488
    public static final int FLAG_HARDWARE_ACCELERATED = 1<<29;      // :494
    public static final int FLAG_SUSPENDED           = 1<<30;
    public static final int FLAG_MULTIARCH           = 1<<31;

    /** AOSP ApplicationInfo.java:1274 and the CATEGORY_* block that follows it.
     *  CATEGORY_UNDEFINED is -1 ("Value when category is undefined"), NOT 0 --
     *  0 is CATEGORY_GAME. */
    public static final int CATEGORY_UNDEFINED    = -1;
    public static final int CATEGORY_GAME         = 0;
    public static final int CATEGORY_AUDIO        = 1;
    public static final int CATEGORY_VIDEO        = 2;
    public static final int CATEGORY_IMAGE        = 3;
    public static final int CATEGORY_SOCIAL       = 4;
    public static final int CATEGORY_NEWS         = 5;
    public static final int CATEGORY_MAPS         = 6;
    public static final int CATEGORY_PRODUCTIVITY = 7;
    /** ApplicationInfo.java:1332 (API 30). */
    public static final int CATEGORY_ACCESSIBILITY = 8;

    // ---- API 30/31 native-hardening + storage modes ----------------------
    // Values verbatim from ApplicationInfo.java:1558-1663 (android16-release).
    // The DEFAULT members are -1 / 0, not the Java zero-default in two of the
    // three cases, so the initializers below are load-bearing.
    public static final int GWP_ASAN_DEFAULT = -1;
    public static final int GWP_ASAN_NEVER   = 0;
    public static final int GWP_ASAN_ALWAYS  = 1;
    public static final int MEMTAG_DEFAULT = -1;
    public static final int MEMTAG_OFF     = 0;
    public static final int MEMTAG_ASYNC   = 1;
    public static final int MEMTAG_SYNC    = 2;
    public static final int ZEROINIT_DEFAULT  = -1;
    public static final int ZEROINIT_DISABLED = 0;
    public static final int ZEROINIT_ENABLED  = 1;
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT       = 0;
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED     = 1;
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED = 2;

    /** AOSP PRIVATE_FLAG_* bits this class's public getters read
     *  (ApplicationInfo.java:600-800). @hide upstream, so package-private here:
     *  they exist so the getters below are a real read of real state rather
     *  than a hardcoded constant. `privateFlags` / `privateFlagsExt` are @hide
     *  public fields upstream; they are public here for the same reason
     *  `flags` is -- our loader fills them. */
    static final int PRIVATE_FLAG_VIRTUAL_PRELOAD             = 1 << 5;
    static final int PRIVATE_FLAG_IS_RESOURCE_OVERLAY         = 1 << 28;
    static final int PRIVATE_FLAG_PROFILEABLE_BY_SHELL        = 1 << 21;
    static final int PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE = 1 << 27;
    static final int PRIVATE_FLAG_EXT_PROFILEABLE             = 1 << 0;
    static final int PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE = 1 << 1;

    /** AOSP @hide public fields (ApplicationInfo.java:817, :838). Mac.apk's
     *  manifest parser does not read the <application> attributes that set most
     *  of these bits, so they stay 0 -- which is the value AOSP reports for an
     *  app that declares none of them, i.e. nearly every app. They are NOT
     *  invented state: the setter is simply a manifest attribute we do not
     *  parse yet, and the moment the parser learns one, its getter starts
     *  answering without any other change. */
    public int privateFlags;
    public int privateFlagsExt;

    /** AOSP ApplicationInfo.java:1249/1259 (API 26): the compileSdkVersion and
     *  its codename, from `<manifest android:compileSdkVersion>` -- aapt2
     *  stamps both into every APK's manifest, so a real device always reports
     *  them. Read by crash reporters and by libraries that branch on the SDK
     *  the app was BUILT against rather than the one it targets (which is a
     *  different question from targetSdkVersion).
     *
     *  Mac.apk's manifest parser does not extract them yet, so they read 0 /
     *  null -- the same values AOSP reports for a package whose manifest omits
     *  the attributes (anything built before API 26's aapt). Declaring them is
     *  what matters here: we present API 36, and a JNI GetFieldID or a plain
     *  getfield on a missing field is an uncatchable NoSuchFieldError. */
    public int    compileSdkVersion;
    public String compileSdkVersionCodename;

    /** AOSP ApplicationInfo.mKnownActivityEmbeddingCerts (API 34), the
     *  `android:knownActivityEmbeddingCerts` set. Private upstream with a
     *  getter; same here. */
    private java.util.Set<String> mKnownActivityEmbeddingCerts;

    private int gwpAsanMode = GWP_ASAN_DEFAULT;
    private int memtagMode = MEMTAG_DEFAULT;
    private int nativeHeapZeroInitialized = ZEROINIT_DEFAULT;
    /** AOSP keeps this as a NULLABLE Boolean, not an int, precisely so
     *  "undeclared" is distinguishable from "declared false". */
    private Boolean requestRawExternalStorageAccess;
    // NOTE: packageName, labelRes, icon, metaData are INHERITED from
    // PackageItemInfo -- do NOT redeclare them here (that would shadow the
    // parent field and reintroduce the wrong-offset getfield crash above).
    public ApplicationInfo() {}

    /** AOSP ApplicationInfo(ApplicationInfo orig) (ApplicationInfo.java:2050-2140):
     *  the copy constructor. Chains through PackageItemInfo(PackageItemInfo),
     *  then copies every field of its own. Missing until v1.1.0, so
     *  `new ApplicationInfo(other)` -- what an app does to take a mutable
     *  snapshot before overriding a field, and what several AndroidX and
     *  Firebase paths do -- linked against nothing.
     *
     *  Restricted to the fields this tree declares; AOSP additionally copies
     *  ~40 @hide fields (seInfo, primaryCpuAbi, splitDependencies, ...) that do
     *  not exist here. No null-check on `orig`, matching AOSP. */
    public ApplicationInfo(ApplicationInfo orig) {
        super(orig);
        taskAffinity = orig.taskAffinity;
        permission = orig.permission;
        processName = orig.processName;
        className = orig.className;
        theme = orig.theme;
        flags = orig.flags;
        privateFlags = orig.privateFlags;
        privateFlagsExt = orig.privateFlagsExt;
        requiresSmallestWidthDp = orig.requiresSmallestWidthDp;
        compatibleWidthLimitDp = orig.compatibleWidthLimitDp;
        largestWidthLimitDp = orig.largestWidthLimitDp;
        storageUuid = orig.storageUuid;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        splitNames = orig.splitNames;
        splitSourceDirs = orig.splitSourceDirs;
        splitPublicSourceDirs = orig.splitPublicSourceDirs;
        nativeLibraryDir = orig.nativeLibraryDir;
        resourceDirs = orig.resourceDirs;
        overlayPaths = orig.overlayPaths;
        sharedLibraryFiles = orig.sharedLibraryFiles;
        dataDir = orig.dataDir;
        deviceProtectedDataDir = orig.deviceProtectedDataDir;
        credentialProtectedDataDir = orig.credentialProtectedDataDir;
        uid = orig.uid;
        minSdkVersion = orig.minSdkVersion;
        targetSdkVersion = orig.targetSdkVersion;
        targetSandboxVersion = orig.targetSandboxVersion;
        compileSdkVersion = orig.compileSdkVersion;
        compileSdkVersionCodename = orig.compileSdkVersionCodename;
        enabled = orig.enabled;
        manageSpaceActivityName = orig.manageSpaceActivityName;
        backupAgentName = orig.backupAgentName;
        descriptionRes = orig.descriptionRes;
        uiOptions = orig.uiOptions;
        category = orig.category;
        appComponentFactory = orig.appComponentFactory;
        gwpAsanMode = orig.gwpAsanMode;
        memtagMode = orig.memtagMode;
        nativeHeapZeroInitialized = orig.nativeHeapZeroInitialized;
        requestRawExternalStorageAccess = orig.requestRawExternalStorageAccess;
        mKnownActivityEmbeddingCerts = orig.mKnownActivityEmbeddingCerts;
    }

    public String deviceProtectedDataDir;
    public String credentialProtectedDataDir;

    // ---- AOSP public accessors over privateFlags / the hardening modes -----
    // Each one is the upstream body verbatim against the same bit or field.
    // Reading the bit (rather than returning a constant) is what makes these
    // correct the moment the manifest parser learns the matching attribute.

    /** AOSP ApplicationInfo.isVirtualPreload() (API 26): "whether the app was
     *  installed as a virtual preload" -- a carrier/OEM pre-install that the
     *  user has not yet materialised. Nothing on Mac.apk is a preload. */
    public boolean isVirtualPreload() {
        return (privateFlags & PRIVATE_FLAG_VIRTUAL_PRELOAD) != 0;
    }

    /** AOSP ApplicationInfo.isResourceOverlay() (API 29): "whether the app is a
     *  Runtime Resource Overlay (RRO)". Mac.apk loads exactly one ordinary APK
     *  and has no overlay manager, so false for everything we generate -- the
     *  same answer a device gives for any non-RRO package.
     *
     *  Referenced by Aurora Store 4.8.4 (gapuse, 2026-08-24), which filters
     *  overlays out of its installed-apps list; the method being absent turned
     *  that filter into a NoSuchMethodError on whatever thread built the list. */
    public boolean isResourceOverlay() {
        return (privateFlags & PRIVATE_FLAG_IS_RESOURCE_OVERLAY) != 0;
    }

    /** AOSP ApplicationInfo.isInstantApp(): whether this is an instant (not
     *  installed) app. Mac.apk only ever loads a normally-installed APK, so this
     *  is false for everything we generate -- the answer a device gives for any
     *  installed package. The Play Store reads it during device-config init
     *  (NoSuchMethodError without it). */
    public boolean isInstantApp() { return false; }

    /** AOSP ApplicationInfo.isProfileableByShell() (API 29): `<profileable
     *  android:shell="true">`. Not parsed here -> false, AOSP's own default. */
    public boolean isProfileableByShell() {
        return (privateFlags & PRIVATE_FLAG_PROFILEABLE_BY_SHELL) != 0;
    }

    /** AOSP ApplicationInfo.isProfileable() (API 31): `<profileable>` at all. */
    public boolean isProfileable() {
        return (privateFlagsExt & PRIVATE_FLAG_EXT_PROFILEABLE) != 0;
    }

    /** AOSP ApplicationInfo.areAttributionsUserVisible() (API 31):
     *  `<application android:attributionsAreUserVisible>`, default false. */
    public boolean areAttributionsUserVisible() {
        return (privateFlagsExt & PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE) != 0;
    }

    /** AOSP ApplicationInfo.isAudioPlaybackCaptureAllowed() (API 29): whether
     *  another app may capture this app's audio output.
     *
     *  ⚠ The DEFAULT is not false. AOSP's parser sets the bit from
     *  `android:allowAudioPlaybackCapture`, whose default is
     *  `targetSdkVersion >= Build.VERSION_CODES.Q` -- so on a device an app
     *  targeting API 29+ that says nothing IS capturable, and only a pre-Q app
     *  is not. We can honour that rule exactly, because targetSdkVersion is a
     *  field we really do fill: the bit wins when the manifest set it, and the
     *  targetSdk rule supplies the default when it did not. Answering a flat
     *  false would have told every modern app the opposite of the truth. */
    public boolean isAudioPlaybackCaptureAllowed() {
        if ((privateFlags & PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) != 0) return true;
        return targetSdkVersion >= android.os.Build.VERSION_CODES.Q;
    }

    /** AOSP ApplicationInfo.getGwpAsanMode() (API 30). GWP-ASan is a sampling
     *  native heap-bug detector; the mode comes from
     *  `<application android:gwpAsanMode>`. Mac.apk does not run GWP-ASan and
     *  the attribute is not parsed, so GWP_ASAN_DEFAULT (-1, "let the platform
     *  decide") is both the honest answer and AOSP's own default. */
    public int getGwpAsanMode() { return gwpAsanMode; }

    /** AOSP ApplicationInfo.getMemtagMode() (API 31). ARM MTE mode from
     *  `<application android:memtagMode>`; MEMTAG_DEFAULT for undeclared. */
    public int getMemtagMode() { return memtagMode; }

    /** AOSP ApplicationInfo.getNativeHeapZeroInitialized() (API 31).
     *  ZEROINIT_DEFAULT for undeclared. */
    public int getNativeHeapZeroInitialized() { return nativeHeapZeroInitialized; }

    /** AOSP ApplicationInfo.getRequestRawExternalStorageAccess() (API 31),
     *  body verbatim -- note the three-way answer built from a NULLABLE
     *  Boolean, so "not declared" is its own value rather than being folded
     *  into "not requested". */
    public int getRequestRawExternalStorageAccess() {
        if (requestRawExternalStorageAccess == null) {
            return RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT;
        }
        return requestRawExternalStorageAccess
                ? RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED
                : RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED;
    }

    /** AOSP ApplicationInfo.getKnownActivityEmbeddingCerts() (API 34), body
     *  verbatim including the @NonNull empty-set answer: the certificate
     *  digests this app trusts to embed its activities
     *  (`android:knownActivityEmbeddingCerts`). Not parsed here, so empty --
     *  which means "trusts nobody", the safe and AOSP-default answer. */
    public java.util.Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts == null
                ? java.util.Collections.<String>emptySet()
                : mKnownActivityEmbeddingCerts;
    }

    /** AOSP ApplicationInfo.DisplayNameComparator (API 1): the
     *  ApplicationInfo-typed sibling of PackageItemInfo.DisplayNameComparator,
     *  so a `List<ApplicationInfo>` sorts without a wildcard cast. Body
     *  verbatim from ApplicationInfo.java:1874-1892 (android16-release):
     *  loadLabel, falling back to packageName -- note the fallback here is the
     *  PACKAGE name, where the base class falls back to `name`. */
    public static class DisplayNameComparator
            implements java.util.Comparator<ApplicationInfo> {
        private final java.text.Collator sCollator = java.text.Collator.getInstance();
        private final android.content.pm.PackageManager mPM;

        public DisplayNameComparator(android.content.pm.PackageManager pm) { mPM = pm; }

        @Override public final int compare(ApplicationInfo aa, ApplicationInfo ab) {
            CharSequence sa = aa.loadLabel(mPM);
            if (sa == null) sa = aa.packageName;
            CharSequence sb = ab.loadLabel(mPM);
            if (sb == null) sb = ab.packageName;
            return sCollator.compare(String.valueOf(sa), String.valueOf(sb));
        }
    }

    /** AOSP ApplicationInfo.getApplicationInfo() override: `return this`. */
    @Override protected ApplicationInfo getApplicationInfo() { return this; }

    /** AOSP ApplicationInfo.getCategoryTitle(Context, int) (API 26): "a concise,
     *  localized title for the given category value, or null for an invalid
     *  category."
     *
     *  Upstream reads com.android.internal.R.string.app_category_*. Mac.apk has
     *  no framework resource table, so the strings are inlined VERBATIM from
     *  frameworks/base/core/res/res/values/strings.xml (android16-release) --
     *  the same text the default locale would produce. That is a real answer,
     *  not a placeholder: the alternative (null for everything) is the value
     *  AOSP reserves for an INVALID category, so a caller would render an
     *  unlabelled row and, worse, could not tell a bad category from a good
     *  one. The known limitation is localization: we always answer in English,
     *  because there is no framework string table to localize against. Wire
     *  this to Resources the moment one exists.
     *
     *  `context` is unused for the same reason and is kept because the AOSP
     *  descriptor includes it. */
    public static CharSequence getCategoryTitle(android.content.Context context, int category) {
        switch (category) {
            case CATEGORY_GAME:          return "Games";
            case CATEGORY_AUDIO:         return "Music & Audio";
            case CATEGORY_VIDEO:         return "Movies & Video";
            case CATEGORY_IMAGE:         return "Photos & Images";
            case CATEGORY_SOCIAL:        return "Social & Communication";
            case CATEGORY_NEWS:          return "News & Magazines";
            case CATEGORY_MAPS:          return "Maps & Navigation";
            case CATEGORY_PRODUCTIVITY:  return "Productivity";
            case CATEGORY_ACCESSIBILITY: return "Accessibility";
            default:                     return null;
        }
    }

    /** AOSP ApplicationInfo.dump(Printer, String):1920-1922 -> the flags form.
     *  Structure and line TEXT follow ApplicationInfo.java:1924-2010, minus the
     *  lines whose fields this tree does not declare (seInfo, classLoaderName,
     *  longVersionCode, targetSandboxVersion, fullBackupContent,
     *  networkSecurityConfigRes, localeConfigRes, createTimestamp,
     *  mPageSizeAppCompatFlags and the hidden-api policy). Omitted rather than
     *  padded with invented values: a dump line naming a field we do not model
     *  would read as data. */
    public void dump(android.util.Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    void dump(android.util.Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0 && className != null) {
            pw.println(prefix + "className=" + className);
        }
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        pw.println(prefix + "processName=" + processName);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "taskAffinity=" + taskAffinity);
        }
        pw.println(prefix + "uid=" + uid + " flags=0x" + Integer.toHexString(flags)
                + " privateFlags=0x" + Integer.toHexString(privateFlags)
                + " theme=0x" + Integer.toHexString(theme));
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "requiresSmallestWidthDp=" + requiresSmallestWidthDp
                    + " compatibleWidthLimitDp=" + compatibleWidthLimitDp
                    + " largestWidthLimitDp=" + largestWidthLimitDp);
        }
        pw.println(prefix + "sourceDir=" + sourceDir);
        if (!java.util.Objects.equals(sourceDir, publicSourceDir)) {
            pw.println(prefix + "publicSourceDir=" + publicSourceDir);
        }
        if (splitSourceDirs != null && splitSourceDirs.length > 0) {
            pw.println(prefix + "splitSourceDirs=" + java.util.Arrays.toString(splitSourceDirs));
        }
        if (splitPublicSourceDirs != null && splitPublicSourceDirs.length > 0
                && !java.util.Arrays.equals(splitSourceDirs, splitPublicSourceDirs)) {
            pw.println(prefix + "splitPublicSourceDirs="
                    + java.util.Arrays.toString(splitPublicSourceDirs));
        }
        if (resourceDirs != null) {
            pw.println(prefix + "resourceDirs=" + java.util.Arrays.toString(resourceDirs));
        }
        if (overlayPaths != null) {
            pw.println(prefix + "overlayPaths=" + java.util.Arrays.toString(overlayPaths));
        }
        pw.println(prefix + "dataDir=" + dataDir);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "deviceProtectedDataDir=" + deviceProtectedDataDir);
            pw.println(prefix + "credentialProtectedDataDir=" + credentialProtectedDataDir);
            if (sharedLibraryFiles != null) {
                pw.println(prefix + "sharedLibraryFiles="
                        + java.util.Arrays.toString(sharedLibraryFiles));
            }
        }
        pw.println(prefix + "enabled=" + enabled
                + " minSdkVersion=" + minSdkVersion
                + " targetSdkVersion=" + targetSdkVersion);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            if (manageSpaceActivityName != null) {
                pw.println(prefix + "manageSpaceActivityName=" + manageSpaceActivityName);
            }
            if (descriptionRes != 0) {
                pw.println(prefix + "description=0x" + Integer.toHexString(descriptionRes));
            }
            if (uiOptions != 0) {
                pw.println(prefix + "uiOptions=0x" + Integer.toHexString(uiOptions));
            }
            pw.println(prefix + "supportsRtl=" + ((flags & FLAG_SUPPORTS_RTL) != 0));
            if (category != CATEGORY_UNDEFINED) {
                pw.println(prefix + "category=" + category);
            }
            pw.println(prefix + "allowsPlaybackCapture=" + isAudioPlaybackCaptureAllowed());
            if (gwpAsanMode != GWP_ASAN_DEFAULT) {
                pw.println(prefix + "gwpAsanMode=" + gwpAsanMode);
            }
            if (memtagMode != MEMTAG_DEFAULT) {
                pw.println(prefix + "memtagMode=" + memtagMode);
            }
            if (nativeHeapZeroInitialized != ZEROINIT_DEFAULT) {
                pw.println(prefix + "nativeHeapZeroInitialized=" + nativeHeapZeroInitialized);
            }
            if (requestRawExternalStorageAccess != null) {
                pw.println(prefix + "requestRawExternalStorageAccess="
                        + requestRawExternalStorageAccess);
            }
        }
        if (mKnownActivityEmbeddingCerts != null) {
            pw.println(prefix + "knownActivityEmbeddingCerts=" + mKnownActivityEmbeddingCerts);
        }
        super.dumpBack(pw, prefix);
    }

    // ---- Parcelable --------------------------------------------------------
    // AOSP: `ApplicationInfo extends PackageItemInfo implements Parcelable`
    // (android11-release ApplicationInfo.java:41). This used to be a CREATOR
    // that read NOTHING and handed back `new ApplicationInfo()`, with no
    // writeToParcel at all -- so every field was silently dropped by a round
    // trip, and the class was not actually Parcelable, which is what made
    // PackageInfo unable to marshal it once Parcel started really flattening.
    //
    // Field ORDER below is AOSP ApplicationInfo.java:2127-2196 (android11)
    // restricted to the fields this tree declares. AOSP additionally marshals
    // privateFlags, scanSourceDir, scanPublicSourceDir, splitDependencies,
    // secondaryNativeLibraryDir, nativeLibraryRootDir, nativeLibraryRootRequiresIsa,
    // primaryCpuAbi, secondaryCpuAbi, seInfo, seInfoUser, sharedLibraryInfos,
    // longVersionCode, enabledSetting, installLocation, fullBackupContent,
    // crossProfile, networkSecurityConfigRes, targetSandboxVersion,
    // classLoaderName, splitClassLoaderNames, compileSdkVersion,
    // compileSdkVersionCodename, iconRes, roundIconRes, mHiddenApiPolicy,
    // hiddenUntilInstalled, zygotePreloadName and gwpAsanMode -- none of which
    // this tree declares, so writing them would invent state rather than mirror
    // it. Skipping a field changes only the wire form, never a value, because
    // both sides of this pair are ours.
    //
    // overlayPaths is not in android11's writer; it sits immediately after
    // resourceDirs in AOSP main (ApplicationInfo.java:2173-2174), which is
    // where it goes here.
    //
    // AOSP guards the whole body with Parcel.maybeWriteSquashed/allowSquashing
    // (a de-duplication cache for the many ComponentInfos in one parcel that
    // share an ApplicationInfo). That is a size optimisation on a real binder
    // transaction; this tree's Parcel has no squashing hook, and its absence is
    // unobservable in field values -- each component simply carries its own
    // copy, which is what pre-API-31 Android did too.
    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString(taskAffinity);
        dest.writeString(permission);
        dest.writeString(processName);
        dest.writeString(className);
        dest.writeInt(theme);
        dest.writeInt(flags);
        dest.writeInt(privateFlags);
        dest.writeInt(privateFlagsExt);
        dest.writeInt(requiresSmallestWidthDp);
        dest.writeInt(compatibleWidthLimitDp);
        dest.writeInt(largestWidthLimitDp);
        // AOSP ApplicationInfo.java:2141-2147: a UUID is a present-flag plus its
        // two longs, NOT writeSerializable -- so a null one costs a single int.
        if (storageUuid != null) {
            dest.writeInt(1);
            dest.writeLong(storageUuid.getMostSignificantBits());
            dest.writeLong(storageUuid.getLeastSignificantBits());
        } else {
            dest.writeInt(0);
        }
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(splitNames);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeString(nativeLibraryDir);
        dest.writeStringArray(resourceDirs);
        dest.writeStringArray(overlayPaths);
        dest.writeStringArray(sharedLibraryFiles);
        dest.writeString(dataDir);
        dest.writeString(deviceProtectedDataDir);
        dest.writeString(credentialProtectedDataDir);
        dest.writeInt(uid);
        dest.writeInt(minSdkVersion);
        dest.writeInt(targetSdkVersion);
        dest.writeInt(compileSdkVersion);
        dest.writeString(compileSdkVersionCodename);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeString(manageSpaceActivityName);
        dest.writeString(backupAgentName);
        dest.writeInt(descriptionRes);
        dest.writeInt(uiOptions);
        dest.writeInt(category);
        dest.writeString(appComponentFactory);
        dest.writeInt(gwpAsanMode);
        dest.writeInt(memtagMode);
        dest.writeInt(nativeHeapZeroInitialized);
        // AOSP flattens the nullable Boolean as a present-flag + value
        // (ApplicationInfo.java:2237-2243) so "undeclared" survives the trip.
        if (requestRawExternalStorageAccess != null) {
            dest.writeInt(1);
            dest.writeBoolean(requestRawExternalStorageAccess);
        } else {
            dest.writeInt(0);
        }
        dest.writeStringArray(mKnownActivityEmbeddingCerts == null
                ? null : mKnownActivityEmbeddingCerts.toArray(new String[0]));
    }

    /** AOSP ApplicationInfo.java:2207-2276, same subset and same order as the
     *  writer above. */
    private ApplicationInfo(android.os.Parcel source) {
        super(source);
        taskAffinity = source.readString();
        permission = source.readString();
        processName = source.readString();
        className = source.readString();
        theme = source.readInt();
        flags = source.readInt();
        privateFlags = source.readInt();
        privateFlagsExt = source.readInt();
        requiresSmallestWidthDp = source.readInt();
        compatibleWidthLimitDp = source.readInt();
        largestWidthLimitDp = source.readInt();
        if (source.readInt() != 0) {
            long hi = source.readLong();
            long lo = source.readLong();
            storageUuid = new java.util.UUID(hi, lo);
        }
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        splitNames = source.createStringArray();
        splitSourceDirs = source.createStringArray();
        splitPublicSourceDirs = source.createStringArray();
        nativeLibraryDir = source.readString();
        resourceDirs = source.createStringArray();
        overlayPaths = source.createStringArray();
        sharedLibraryFiles = source.createStringArray();
        dataDir = source.readString();
        deviceProtectedDataDir = source.readString();
        credentialProtectedDataDir = source.readString();
        uid = source.readInt();
        minSdkVersion = source.readInt();
        targetSdkVersion = source.readInt();
        compileSdkVersion = source.readInt();
        compileSdkVersionCodename = source.readString();
        enabled = source.readInt() != 0;
        manageSpaceActivityName = source.readString();
        backupAgentName = source.readString();
        descriptionRes = source.readInt();
        uiOptions = source.readInt();
        category = source.readInt();
        appComponentFactory = source.readString();
        gwpAsanMode = source.readInt();
        memtagMode = source.readInt();
        nativeHeapZeroInitialized = source.readInt();
        requestRawExternalStorageAccess =
                (source.readInt() != 0) ? Boolean.valueOf(source.readBoolean()) : null;
        String[] certs = source.createStringArray();
        mKnownActivityEmbeddingCerts = (certs == null)
                ? null : new java.util.LinkedHashSet<>(java.util.Arrays.asList(certs));
    }

    public static final android.os.Parcelable.Creator<ApplicationInfo> CREATOR =
        new android.os.Parcelable.Creator<ApplicationInfo>() {
            @Override public ApplicationInfo createFromParcel(android.os.Parcel source) {
                return new ApplicationInfo(source);
            }
            @Override public ApplicationInfo[] newArray(int size) {
                return new ApplicationInfo[size];
            }
        };
}


