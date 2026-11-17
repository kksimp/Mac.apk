// android.os.Environment -- "where on the device is this kind of
// directory" service. Apps look up external storage to plant download
// caches and analytics queues.
//
// getExternalStorageDirectory() must resolve to the SAME physical
// location that runtime/translator/src/bionic_shim.cpp's path remap treats
// as the primary shared-storage volume for NATIVE code, and that the
// launcher's "Reveal" button exposes to the user (launcher/
// MacApkLauncherApp.swift, revealAndroidRoot()). Since stage S0 of
// docs/shared-storage-real-mac-folders-design_2026-09-25.md
// (MACAPK_SDCARD_UNIFY, default on) the native side maps EVERY device
// spelling of that volume -- /sdcard, /mnt/sdcard, /storage/emulated/0,
// /storage/self/primary, with or without a trailing slash -- to
// "<android_root>/sdcard", and /sdcard/Android/{data,obb,media} to the
// app-private root under it. Before S0 only "/sdcard/..." landed there:
// "/storage/..." and "/mnt/sdcard/..." kept their prefix and went to two
// other trees that nothing created, which the line this replaces claimed
// otherwise. This used to return a directory under java.io.tmpdir -- a
// different, ephemeral location that macOS periodically reclaims -- so a
// native library's fopen("/sdcard/x") and a Java Environment call resolved
// to two disjoint trees. The android root and the volume root now come
// from com.macapk.loader.SharedStorage, the one Java resolver for shared
// storage.
package android.os;

import com.macapk.loader.SharedStorage;
import com.macapk.loader.SpecialAccessStore;
import java.io.File;

// AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
// mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
// app compiled against real Android that subclasses this fails to resolve
// against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
public class Environment {
    public static final String MEDIA_MOUNTED            = "mounted";
    public static final String MEDIA_MOUNTED_READ_ONLY  = "mounted_ro";
    public static final String MEDIA_REMOVED            = "removed";
    public static final String MEDIA_UNMOUNTED          = "unmounted";
    // The remaining getExternalStorageState() results, AOSP verbatim
    // (core/java/android/os/Environment.java). Mac.apk's single volume is
    // permanently MEDIA_MOUNTED, so none of these is ever RETURNED -- but an
    // app switches on the value and its own switch arms reference the
    // constants, and any of them may be read reflectively.
    public static final String MEDIA_UNKNOWN            = "unknown";
    public static final String MEDIA_CHECKING           = "checking";
    public static final String MEDIA_NOFS               = "nofs";
    public static final String MEDIA_SHARED             = "shared";
    public static final String MEDIA_BAD_REMOVAL        = "bad_removal";
    public static final String MEDIA_UNMOUNTABLE        = "unmountable";
    public static final String MEDIA_EJECTING           = "ejecting";

    // AOSP core/java/android/os/Environment.java:818-945 declares EVERY
    // DIRECTORY_* as `public static String` -- NOT final. That is deliberate
    // upstream (a non-constant cannot be inlined into an app at compile time,
    // and the fields are assignable), and the signature file records them the
    // same way, so `final` here is a real divergence: an app that assigns to
    // one gets IllegalAccessError against us and succeeds on a device. Only
    // DIRECTORY_ANDROID is final upstream (:98).
    public static String DIRECTORY_MUSIC      = "Music";
    public static String DIRECTORY_PICTURES   = "Pictures";
    public static String DIRECTORY_MOVIES     = "Movies";
    public static String DIRECTORY_DOWNLOADS  = "Download";
    public static String DIRECTORY_DCIM       = "DCIM";
    public static String DIRECTORY_DOCUMENTS  = "Documents";
    public static String DIRECTORY_PODCASTS   = "Podcasts";
    public static String DIRECTORY_RINGTONES  = "Ringtones";
    public static String DIRECTORY_NOTIFICATIONS = "Notifications";
    public static String DIRECTORY_ALARMS     = "Alarms";
    // AOSP :912 / :924 / :945 -- API 29/29/31.
    public static String DIRECTORY_SCREENSHOTS = "Screenshots";
    public static String DIRECTORY_AUDIOBOOKS  = "Audiobooks";
    public static String DIRECTORY_RECORDINGS  = "Recordings";
    // AOSP :98 -- `public static final String DIRECTORY_ANDROID = DIR_ANDROID`
    // where DIR_ANDROID is "Android" (:74).
    public static final String DIRECTORY_ANDROID = "Android";

    // AOSP declares a public no-arg constructor (it is in the signature file
    // as `ctor public Environment()`), so an app may subclass or instantiate
    // it. A private one made that a compile-time-visible-but-link-time-fatal
    // difference.
    public Environment() {}

    // -Dmacapk.android.root -> MACAPK_ANDROID_ROOT -> the dev scratch default,
    // with "~" expanded. The body moved to SharedStorage.androidRoot() (stage
    // S0) so shared storage has ONE Java resolver; Boot.java and
    // SettingsStore.java still carry their own copies of the same order.
    private static String resolveAndroidRoot() {
        return SharedStorage.androidRoot();
    }

    // Win.apk: the device name of primary shared storage, "/storage/emulated/0".
    // The app (and native code, which splits a path on '/' and walks up to "/")
    // sees and hands around this Android path; java.io, java.nio and the guest
    // shim open the host folder it names, <android root>/sdcard (WinApkPaths,
    // paths.cpp). On a plain JVM (no -Dwinapk.android.root) the host folder
    // itself is returned. SharedStorage.externalStorageRoot() stays the host
    // resolver the framework's own file walks use (MediaStoreProvider,
    // HostDocumentsProvider).
    private static final String WINAPK_SHARED_STORAGE = "/storage/emulated/0";
    private static boolean winApkAndroidPaths() {
        return System.getProperty("winapk.android.root") != null;
    }
    public static File getExternalStorageDirectory() {
        // Matches bionic_shim.cpp's native ext root exactly: a native
        // fopen("/sdcard/foo") or fopen("/storage/emulated/0/foo") rewrites to
        // "<android_root>/sdcard/foo". The volume root itself always exists on
        // a device, so creating it here is not a divergence.
        File f = SharedStorage.externalStorageRoot();
        f.mkdirs();
        return winApkAndroidPaths() ? new File(WINAPK_SHARED_STORAGE) : f;
    }
    // AOSP env14 :1085-1092: the returned directory "may not yet exist, so you
    // must make sure it exists before using it such as with File.mkdirs()" --
    // the getter never creates it. MediaProvider creates the standard folders
    // ONCE per volume (SharedStorage.ensureDefaultFolders, run from Boot), so a
    // folder the user deleted stays deleted and a getter call for a name that
    // is not a default folder (Instagram's "Screenshots") creates nothing.
    // With MACAPK_SDCARD_UNIFY=0 the pre-S0 eager mkdirs comes back.
    public static File getExternalStoragePublicDirectory(String type) {
        File f = new File(getExternalStorageDirectory(), type != null ? type : "");
        if (!SharedStorage.unifyEnabled()) f.mkdirs();
        return f;
    }
    public static File getDownloadCacheDirectory() {
        File f = new File(getExternalStorageDirectory(), "cache");
        f.mkdirs();
        return f;
    }
    // AOSP android-11.0.0_r48 core/java/android/os/Environment.java:
    //     :81   DIR_ANDROID_DATA = getDirectory(ENV_ANDROID_DATA, "/data")
    //     :353  public static File getDataDirectory() { return DIR_ANDROID_DATA; }
    //     :80   DIR_ANDROID_ROOT = getDirectory(ENV_ANDROID_ROOT, "/system")
    //     :218  public static File getRootDirectory() { return DIR_ANDROID_ROOT; }
    // So the last component is `data` and `system` on EVERY Android device --
    // fixed strings, and therefore things an app can legitimately hardcode,
    // string-compare, or use to derive a sibling path. We answered the
    // external-storage dir (name `sdcard`) for the first and filesystem root
    // (name "") for the second, so any app deriving a path from either got a
    // location with no relationship to the one Android describes.
    //
    // Rebased under the Mac.apk android_root rather than returned as the
    // literal "/data" and "/system", for two reasons. (1) AOSP itself derives
    // both from environment variables via getDirectory(), so a relocated root
    // is inside the contract, not a deviation from it. (2) These are the
    // locations that actually EXIST here and that the rest of the runtime
    // already agrees on: per-app storage lives at <root>/data/data/<pkg>_<ver>
    // (Boot.java), and runtime/translator/src/bionic_shim.cpp:24273-24285
    // remaps a native /data/data/... or /data/user/0/... open to exactly that
    // subtree -- so getDataDirectory() now names the real parent of the app's
    // real data dir on both the Java and the native side. Returning the bare
    // "/data" and "/system" would name paths that do not exist on macOS, so
    // isDirectory() would answer false where a device answers true.
    //
    // mkdirs() is additive only: it creates <root>/data and <root>/system if
    // absent and moves nothing.
    public static File getDataDirectory() {
        File f = new File(resolveAndroidRoot(), "data");
        f.mkdirs();
        return f;
    }
    public static File getRootDirectory() {
        File f = new File(resolveAndroidRoot(), "system");
        f.mkdirs();
        return f;
    }
    // AOSP :252  public static File getStorageDirectory() { return DIR_ANDROID_STORAGE; }
    // where DIR_ANDROID_STORAGE = getDirectory(ENV_ANDROID_STORAGE, "/storage").
    // Rebased under the Mac.apk android_root for the same reason as
    // getDataDirectory()/getRootDirectory() above, and to the same place the
    // native side resolves it: bionic_shim.cpp's remap sends a native
    // "/storage" or "/storage/..." here, prefix kept -- EXCEPT the primary
    // volume's own spellings (/storage/emulated/0, /storage/self/primary),
    // which since stage S0 go to the shared-storage root like /sdcard does.
    // Caveat, stated rather than papered over: on a device
    // getExternalStorageDirectory() ("/storage/emulated/0") nests UNDER this
    // one, whereas here external storage is "<root>/sdcard". Stage S3 of
    // docs/shared-storage-real-mac-folders-design_2026-09-25.md moves shared
    // storage to a Mac.apk-wide folder that does nest; until then the nesting
    // is not literal here.
    public static File getStorageDirectory() {
        File f = new File(resolveAndroidRoot(), "storage");
        f.mkdirs();
        return f;
    }

    public static String getExternalStorageState() { return MEDIA_MOUNTED; }
    public static String getExternalStorageState(File path) { return MEDIA_MOUNTED; }
    // AOSP-deprecated (since API 19) thin alias -- still referenced by
    // apps that never migrated to getExternalStorageState(File).
    @Deprecated
    public static String getStorageState(File path) { return getExternalStorageState(path); }
    public static boolean isExternalStorageEmulated() { return true; }
    public static boolean isExternalStorageEmulated(File path) { return true; }
    public static boolean isExternalStorageRemovable() { return false; }
    // v1.0.98: File overload used by NearbyViewBinder's storage probe.
    public static boolean isExternalStorageRemovable(File path) { return false; }
    // ---- Stage S1: the scoped-storage answers, AOSP bodies ----------------
    //
    // docs/shared-storage-real-mac-folders-design_2026-09-25.md S1 + 3c.
    // Both used to be a hardcoded `true`:
    //   - isExternalStorageManager() since 6c69653e (v1.0.677), on the premise
    //     that "every permission check returns GRANTED" -- false since
    //     checkPermission became declared-only (812e7a4a, 2026-08-09), and the
    //     true hid every app's own "All files access" onboarding (Fossify's
    //     AllFilesPermissionDialog, AnkiDroid's and Aurora's REQUIRED screen).
    //     docs/fossify-all-files-access-prompt_2026-09-25.md.
    //   - isExternalStorageLegacy() as "the honest answer for every caller"
    //     because nothing enforces scoping -- but the answer a device gives is a
    //     function of the manifest, and AnkiDroid (target 35) branches on it
    //     BEFORE its can-manage check (g9.b7.a), so `true` sent it down a
    //     legacy-folder branch no device takes.
    // Now both port AOSP's bodies (env14 :1397-1522, identical in android16),
    // reading the op modes from AppOpsManager, whose storage rows are backed by
    // com.macapk.loader.SpecialAccessStore (the out-of-process grant) and the
    // manifest. MACAPK_ALLFILES_HONEST=0 restores both `true`s, together with
    // AppOpsManager's and PackageManager's pre-S1 answers -- one switch, so a
    // bisect never sees a half-applied (self-contradicting) state.

    /** AOSP :1397-1400: the primary volume. */
    public static boolean isExternalStorageLegacy() {
        return isExternalStorageLegacy(SharedStorage.externalStorageRoot());
    }

    /** AOSP isExternalStorageLegacy(File), env14 :1414-1467. The compat-change
     *  pair collapses to its default: DEFAULT_SCOPED_STORAGE (149924527) is a
     *  plain @ChangeId, enabled for every app, and FORCE_ENABLE_SCOPED_STORAGE
     *  (132649864) is @Disabled, so scoped storage is neither "enforced" nor
     *  "disabled" and the answer is the app-op: OP_LEGACY_STORAGE ALLOWED, else
     *  OP_NO_ISOLATED_STORAGE ALLOWED (instrumentation only; row default
     *  ERRORED). PROPERTY_NO_APP_DATA_STORAGE is not modelled (our
     *  getProperty answers "not declared"), which is its observable default. */
    public static boolean isExternalStorageLegacy(File path) {
        if (!SpecialAccessStore.honest()) return true;   // pre-S1 answer
        final android.content.Context context = macapkInitialApplication();
        if (context == null) {
            // AOSP dereferences AppGlobals.getInitialApplication() here, which a
            // device has set before any app code (provider or static
            // initialiser) can run. Our Application may not exist yet on an
            // early call, so answer the SAME op question without a Context
            // rather than NPE: the app-op is the whole decision below anyway.
            return android.app.AppOpsManager.macapkModeFor(
                    android.app.AppOpsManager.OPSTR_LEGACY_STORAGE)
                == android.app.AppOpsManager.MODE_ALLOWED;
        }
        int uid;
        try { uid = context.getApplicationInfo().uid; }
        catch (Throwable t) { uid = Process.myUid(); }
        // Isolated processes and Instant apps are never allowed to be in
        // scoped storage (AOSP's own comment, :1419-1426) -- both false here.
        if (Process.isIsolatedUid(uid) || Process.isSdkSandboxUid(uid)) return false;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            if (pm != null && pm.isInstantApp()) return false;
        } catch (Throwable ignored) {}
        android.app.AppOpsManager appOps = macapkAppOps(context);
        String opPackageName = context.getPackageName();
        if (appOps.noteOpNoThrow(android.app.AppOpsManager.OP_LEGACY_STORAGE, uid,
                opPackageName) == android.app.AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        return appOps.noteOpNoThrow(android.app.AppOpsManager.OP_NO_ISOLATED_STORAGE, uid,
                opPackageName) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /** AOSP :1480-1483: the primary volume. (Not getExternalStorageDirectory():
     *  that one creates the volume root, and a yes/no question must not.) */
    public static boolean isExternalStorageManager() {
        return isExternalStorageManager(SharedStorage.externalStorageRoot());
    }

    /** AOSP isExternalStorageManager(File), env14 :1500-1522 (android16
     *  :1521-1543, identical): op MANAGE_EXTERNAL_STORAGE, and on MODE_DEFAULT
     *  the permission -- which is signature|appop|preinstalled and therefore
     *  DENIED for every third-party app (MacApkPackageManager.checkPermission).
     *  So a fresh install answers false, and true only after the user allowed
     *  it in macapk-installhelper's consent dialog. */
    public static boolean isExternalStorageManager(File path) {
        if (!SpecialAccessStore.honest()) return true;   // pre-S1 answer
        final android.content.Context context = macapkInitialApplication();
        if (context == null) {
            // Same no-Context fallback as isExternalStorageLegacy: the
            // decision without the Context. MODE_DEFAULT would ask
            // checkPermission(MANAGE_EXTERNAL_STORAGE), which is DENIED for
            // every app we host, so only an explicit ALLOWED answers true.
            return android.app.AppOpsManager.macapkModeFor(
                    android.app.AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE)
                == android.app.AppOpsManager.MODE_ALLOWED;
        }
        String packageName = java.util.Objects.requireNonNull(context.getPackageName());
        int uid;
        try { uid = context.getApplicationInfo().uid; }
        catch (Throwable t) { uid = Process.myUid(); }
        android.app.AppOpsManager appOps = macapkAppOps(context);
        final int opMode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE, uid, packageName);
        switch (opMode) {
            case android.app.AppOpsManager.MODE_DEFAULT:
                return android.content.pm.PackageManager.PERMISSION_GRANTED
                        == context.checkPermission(
                                SpecialAccessStore.PERM_MANAGE_EXTERNAL_STORAGE,
                                Process.myPid(), uid);
            case android.app.AppOpsManager.MODE_ALLOWED:
                return true;
            case android.app.AppOpsManager.MODE_ERRORED:
            case android.app.AppOpsManager.MODE_IGNORED:
                return false;
            default:
                throw new IllegalStateException("Unknown AppOpsManager mode " + opMode);
        }
    }

    /** AppGlobals.getInitialApplication(): the process's Application, or null
     *  before it exists (see the fallbacks above). */
    private static android.content.Context macapkInitialApplication() {
        try { return android.app.ActivityThread.currentApplication(); }
        catch (Throwable t) { return null; }
    }

    /** context.getSystemService(AppOpsManager.class), which AOSP never answers
     *  null for; ours can for an unusual Context, and the manager has no
     *  per-instance state worth losing. */
    private static android.app.AppOpsManager macapkAppOps(android.content.Context context) {
        android.app.AppOpsManager a = null;
        try { a = context.getSystemService(android.app.AppOpsManager.class); }
        catch (Throwable ignored) {}
        return a != null ? a : new android.app.AppOpsManager(context);
    }
}
