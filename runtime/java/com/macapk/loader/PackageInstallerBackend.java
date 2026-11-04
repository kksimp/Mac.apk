// PackageInstallerBackend -- the host side of android.content.pm.
// PackageInstaller's SESSION surface. android.content.pm.PackageInstaller is
// the AOSP-shaped front door (constants, SessionParams, Session, SessionInfo);
// every mutation it performs lands here: the process-wide session registry,
// the per-session staging directory the blobs are written into, the handoff of
// a committed session to the macOS install helper, and the delivery of the
// AOSP status Intent back through the caller's IntentSender.
//
// WHY THIS IS THE LOAD-BEARING INSTALL PATH: Aurora Store's default installer
// is SessionInstaller (AppInstaller.kt: `defaultInstaller get() =
// sessionInstaller`, read from its master source 2026-08-12), and every other
// backend it offers (Root/Service/AM/Shizuku/MicroG) falls back to it the
// moment its availability check fails -- which they all do on Mac.apk. The
// flow Aurora runs, verbatim from SessionInstaller.kt:
//     pm.getPackageInstaller() -> createSession(params) -> openSession(id)
//     -> openWrite("<pkg>_<file>", 0, len) per apk (base + config splits)
//     -> fsync -> close -> openSession(id) AGAIN -> commit(sender) -> close
// with the outcome read by a BroadcastReceiver (InstallerStatusReceiver,
// manifest-declared, exported=false, NO intent-filter) behind a
// PendingIntent.getBroadcast whose base Intent is EXPLICIT
// (Intent(context, InstallerStatusReceiver.class)). Three consequences this
// file is built around:
//   1. openSession(id) must return a view over the SAME state every time
//      (Aurora opens the session once to write and once to commit).
//   2. getSessionInfo(id) must be non-null for a live session (Aurora
//      null-checks it right before commit and silently drops the install
//      otherwise).
//   3. the status Intent must ride IntentSender.sendIntent -> PendingIntent
//      fill-in merge -> Context.sendBroadcast's explicit-component manifest
//      dispatch. Delivering any other way (activity result, a synthesized
//      broadcast of our own) leaves the receiver's `action ==
//      ACTION_INSTALL_STATUS` guard or its `EXTRA_PACKAGE_NAME ?: return`
//      unsatisfied and reproduces the exact silent hang this replaces.
//
// ⛔ SECURITY -- THE AUTHORIZATION IS THE HELPER'S, NEVER OURS TO SKIP.
// Same stance as InstallIntentHandler (the ACTION_INSTALL_PACKAGE sibling of
// this class), restated because it is load-bearing: this class runs in the
// same process as untrusted guest code, so nothing here can be an
// authorization decision. We always invoke the helper with --source android
// (a compile-time constant, never an env var, never anything a guest can
// select), and the HELPER owns the consent+auth gate for that source (a real
// LAContext device-owner check; --no-ui is refused for that source by the
// helper itself). Do not add any code path that selects a different --source
// value at runtime. The MACAPK_INSTALLHELPER override picks WHICH binary we
// ask, not what the binary demands: a guest that redirects it to a fake
// helper gets a fabricated result delivered to itself and no install happens
// anywhere.
//
// SHARED-LOGIC NOTE: locateHelper() / stagingRoot() / runInstallHelper() /
// requestorLabel() are PUBLIC statics because they are the ONE implementation
// of those facts for BOTH install surfaces. InstallIntentHandler (the
// ACTION_INSTALL_PACKAGE sibling) delegates to them as of 2026-08-12; before
// that it carried private copies, and the copies had already drifted -- only
// this path passed --requestor-label, so the helper's auth prompt named the
// asking app for a PackageInstaller.Session install and said "An Android app"
// for the identical install requested by intent. Keep them here and keep them
// singular: two surfaces disagreeing about where bytes land or which binary
// they ask is how an app silently installs wrong.
package com.macapk.loader;

import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageInstallerBackend {
    private static final String TAG = "PackageInstaller";

    /** AOSP legacy install status codes, verbatim from android14-release
     *  core/java/android/content/pm/PackageManager.java: INSTALL_SUCCEEDED = 1,
     *  INSTALL_FAILED_INVALID_APK = -2, INSTALL_FAILED_INTERNAL_ERROR = -110,
     *  INSTALL_FAILED_ABORTED = -115. Literals because they ride in
     *  EXTRA_LEGACY_STATUS and the hidden constants are not public surface. */
    private static final int INSTALL_SUCCEEDED             = 1;
    private static final int INSTALL_FAILED_INVALID_APK    = -2;
    private static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;
    private static final int INSTALL_FAILED_INTERNAL_ERROR = -110;
    private static final int INSTALL_FAILED_ABORTED        = -115;
    /** android14-release PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE. */
    private static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;

    /** Non-null (the AOSP message) when {@code artifact} is an update of an
     *  installed package and shares NO signer with it. AOSP compares against
     *  the installed SigningDetails, accepting any signer in its rotation
     *  history (a v3 lineage), so this accepts a match against either the
     *  installed signers or the installed history, and never requires the
     *  arrays to be byte-equal as a whole. Unknown on either side (an archive
     *  the parser cannot read, a registry row with no readable APK, a package
     *  the caller cannot see) is NOT a conflict: the gate refuses only what
     *  it can prove, so it cannot turn a legitimate update into a failure. */
    static String updateSignerConflict(File artifact, java.util.Collection<File> blobs) {
        try {
            android.app.Application app = android.app.Application.getInstance();
            if (app == null || artifact == null) return null;
            android.content.pm.PackageManager pm = app.getPackageManager();
            int CERT = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
            // The assembled artifact first; a multi-APK session's artifact is a
            // container the manifest parser may not read, so fall back to the
            // session's own APKs -- every split of one package carries the same
            // signer (AOSP rejects a session whose splits disagree).
            android.content.pm.PackageInfo incoming =
                pm.getPackageArchiveInfo(artifact.getPath(), CERT);
            if ((incoming == null || incoming.signingInfo == null) && blobs != null) {
                for (File b : blobs) {
                    android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(b.getPath(), CERT);
                    if (pi != null && pi.packageName != null && pi.signingInfo != null
                            && pi.signingInfo.getApkContentsSigners().length > 0) {
                        incoming = pi;
                        break;
                    }
                }
            }
            if (incoming == null || incoming.packageName == null
                    || incoming.signingInfo == null) return null;
            android.content.pm.PackageInfo installed;
            try { installed = pm.getPackageInfo(incoming.packageName, CERT); }
            catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return null;   // a fresh install: nothing to match
            }
            if (installed == null || installed.signingInfo == null) return null;
            android.content.pm.Signature[] have = installed.signingInfo.getApkContentsSigners();
            android.content.pm.Signature[] past =
                installed.signingInfo.getSigningCertificateHistory();
            android.content.pm.Signature[] want = incoming.signingInfo.getApkContentsSigners();
            if (have == null || have.length == 0 || want == null || want.length == 0) return null;
            for (android.content.pm.Signature w : want) {
                for (android.content.pm.Signature h : have) if (w.equals(h)) return null;
                if (past != null) for (android.content.pm.Signature h : past) if (w.equals(h)) return null;
            }
            return "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package " + incoming.packageName
                + " signatures do not match newer version; ignoring!";
        } catch (Throwable t) {
            NativeLog.w(TAG, "update signer check could not run (" + t + "); not refusing");
            return null;
        }
    }

    /** AOSP legacy DELETE status codes, verbatim from android14-release
     *  core/java/android/content/pm/PackageManager.java (:2428, :2438, :2472).
     *  They ride in EXTRA_LEGACY_STATUS for an uninstall exactly as the
     *  INSTALL_* codes do for an install: PackageInstallerService's
     *  PackageDeleteObserverAdapter.onPackageDeleted puts the RAW DELETE_*
     *  returnCode there (PackageInstallerService.java:1556-1573), alongside
     *  deleteStatusToPublicStatus(returnCode) in EXTRA_STATUS. Only the three
     *  codes Mac.apk can actually produce are mirrored. */
    private static final int DELETE_SUCCEEDED             = 1;
    private static final int DELETE_FAILED_INTERNAL_ERROR = -1;
    private static final int DELETE_FAILED_ABORTED        = -5;

    /** AOSP allocates session ids from a random positive int and increments;
     *  the only contract callers rely on is "positive and unique for the
     *  process lifetime", so a plain counter is enough. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, SessionState> SESSIONS = new ConcurrentHashMap<>();

    /** Registered SessionCallbacks. STATIC because AOSP keeps callback state
     *  in the system service: an app that registers on one
     *  getPackageInstaller() instance must observe sessions driven through
     *  another (Aurora holds a singleton installer but nothing guarantees
     *  other apps do). Dispatch is posted to the main thread, matching the
     *  1-arg registerSessionCallback overload's documented behavior. */
    private static final List<PackageInstaller.SessionCallback> CALLBACKS =
        new CopyOnWriteArrayList<>();

    /** One committed install at a time, off the caller's thread. commit() is
     *  asynchronous on AOSP (the status arrives later through the
     *  IntentSender), and our helper can legitimately block on a human at its
     *  LAContext prompt for minutes -- the guest's main loop must keep running
     *  during that, and two auth prompts at once would be nonsense. Daemon so
     *  a pending install never pins process exit. */
    private static final java.util.concurrent.ExecutorService EXEC =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "macapk-session-install");
            t.setDaemon(true);
            return t;
        });

    private PackageInstallerBackend() {}

    // ---- registry -------------------------------------------------------

    public static SessionState create(String appPackageName, long sizeBytes,
                                      String appLabel) throws IOException {
        int id = NEXT_ID.getAndIncrement();
        File dir = new File(stagingRoot(), "session-" + id + "-"
                + Integer.toHexString((int) (Math.random() * 0x7fffffff)));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // AOSP createSession throws IOException "if parameters were
            // unsatisfiable, such as lack of disk space" -- a staging dir we
            // cannot create is exactly that shape.
            throw new IOException("cannot create session staging dir " + dir);
        }
        SessionState s = new SessionState(id, appPackageName, sizeBytes, appLabel, dir);
        SESSIONS.put(id, s);
        NativeLog.i(TAG, "createSession id=" + id + " pkg=" + appPackageName
                + " size=" + sizeBytes + " dir=" + dir);
        final int fid = id;
        postToMain(() -> {
            for (PackageInstaller.SessionCallback cb : CALLBACKS) {
                try { cb.onCreated(fid); } catch (Throwable ignored) {}
            }
        });
        return s;
    }

    /** Live session for id, or null (committed-terminal / abandoned /
     *  never-existed all read as null, which is AOSP's getSessionInfo
     *  contract). */
    public static SessionState get(int id) { return SESSIONS.get(id); }

    public static List<SessionState> live() {
        return new ArrayList<>(SESSIONS.values());
    }

    public static void registerCallback(PackageInstaller.SessionCallback cb) {
        if (cb != null && !CALLBACKS.contains(cb)) CALLBACKS.add(cb);
    }

    public static void unregisterCallback(PackageInstaller.SessionCallback cb) {
        CALLBACKS.remove(cb);
    }

    // ---- guest-initiated uninstall ---------------------------------------

    /** Packages whose uninstall THIS class is currently driving through the
     *  helper. Consulted by MacApkPackageManager.InstalledRegistry's
     *  announceRemovals so the registry-diff observer does not ALSO broadcast
     *  PACKAGE_REMOVED for a removal this path announces itself (with real
     *  dataRemoved evidence from the helper, in AOSP's broadcast-before-status
     *  order). Registered BEFORE the helper runs because the helper rewrites
     *  the registry mid-flight and any concurrent PackageManager query would
     *  otherwise diff-and-announce first. */
    private static final java.util.Set<String> UNINSTALLS_IN_FLIGHT =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isUninstallInFlight(String pkg) {
        return pkg != null && UNINSTALLS_IN_FLIGHT.contains(pkg);
    }

    /** The host side of PackageInstaller.uninstall. Synchronous part: AOSP's
     *  permission gate (below); everything else runs on the same
     *  single-threaded EXEC as installs, so at most one helper auth prompt
     *  exists at a time and an uninstall queues behind a running install.
     *
     *  THE PERMISSION GATE, from AOSP android14-release
     *  PackageInstallerService.uninstall (:1243-1265): a caller holding
     *  DELETE_PACKAGES goes straight through ("Sweet, call straight
     *  through!"); everyone else takes "a short detour to confirm with user",
     *  and for targetSdk >= P first
     *      mContext.enforceCallingOrSelfPermission(
     *              Manifest.permission.REQUEST_DELETE_PACKAGES, null);
     *  which throws SecurityException. Mac.apk deliberately does NOT model
     *  the silent DELETE_PACKAGES branch: that permission is signature-level,
     *  no guest can genuinely hold it (our lenient checkPermission grants any
     *  permission the manifest merely DECLARES, and e.g. Aurora-adjacent
     *  builds do declare it for their root path), and a guest-initiated
     *  deletion with no human is exactly what this path must never allow.
     *  Every guest uninstall takes the confirm detour; the helper's
     *  device-owner (Touch ID) prompt IS the confirm UI, playing the role of
     *  AOSP's ACTION_UNINSTALL_PACKAGE UninstallerActivity dialog -- which is
     *  also why STATUS_PENDING_USER_ACTION is never sent (same reasoning as
     *  the install side's class header).
     *
     *  `versionCode` is PackageManager.VERSION_CODE_HIGHEST (-1) for the
     *  String overload; an explicit code must match the installed one or the
     *  request fails, per DeletePackageHelper.java:163-167 ("Not removing
     *  package ... with versionCode X != Y" -> DELETE_FAILED_INTERNAL_ERROR). */
    public static void uninstall(String packageName, long versionCode,
                                 IntentSender statusReceiver) {
        if (statusReceiver == null) {
            // AOSP declares the parameter @NonNull; a real device NPEs.
            throw new NullPointerException("statusReceiver must not be null");
        }
        android.app.Application app = null;
        try { app = android.app.Application.getInstance(); }
        catch (Throwable ignored) {}
        if (app != null) {
            boolean enforce = false;
            try {
                android.content.pm.ApplicationInfo ai = app.getApplicationInfo();
                enforce = ai != null && ai.targetSdkVersion >= 28   // P
                        && app.getPackageManager().checkPermission(
                                "android.permission.REQUEST_DELETE_PACKAGES",
                                app.getPackageName())
                           != android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) {
                // Cannot evaluate the manifest -> let the request through to
                // the helper, whose human gate stands regardless. Same
                // absence-of-evidence stance as declaredPermissions().
                enforce = false;
            }
            if (enforce) {
                // ContextImpl.enforceCallingOrSelfPermission's message shape.
                throw new SecurityException("Neither user nor current process has "
                        + "android.permission.REQUEST_DELETE_PACKAGES.");
            }
        }
        final String pkg = packageName;
        final long vc = versionCode;
        NativeLog.i(TAG, "uninstall requested for " + pkg
                + (vc != -1 ? (" versionCode=" + vc) : "") + "; queued");
        EXEC.execute(() -> performUninstall(pkg, vc, statusReceiver));
    }

    /** Where an uninstall's result goes when the caller used the legacy
     *  PackageManager.deletePackage observer instead of an IntentSender. Set
     *  on the EXEC thread for the duration of one performUninstall. */
    public interface DeleteSink { void onDeleted(String pkg, int returnCode, String message); }
    private static final ThreadLocal<DeleteSink> DELETE_SINK = new ThreadLocal<>();

    /** The host side of the hidden PackageManager.deletePackage(String,
     *  IPackageDeleteObserver, int) family (what Google Play's UninstallerImpl
     *  calls, by reflection): the same performUninstall, helper verb and
     *  device-owner gate as the IntentSender surface, with the result handed to
     *  `sink` after the PACKAGE_REMOVED broadcasts (AOSP order). */
    public static void uninstallWithObserver(String packageName, long versionCode, DeleteSink sink) {
        final String pkg = packageName;
        NativeLog.i(TAG, "deletePackage requested for " + pkg
                + (versionCode != -1 ? (" versionCode=" + versionCode) : "") + "; queued");
        EXEC.execute(() -> {
            DELETE_SINK.set(sink);
            try { performUninstall(pkg, versionCode, null); }
            finally { DELETE_SINK.remove(); }
        });
    }

    /** The INTENT half of the guest uninstall surface: ACTION_DELETE (and the
     *  deprecated ACTION_UNINSTALL_PACKAGE) carrying package:<pkg> data.
     *
     *  Aurora Store 4.8.4 uninstalls EXACTLY this way -- measured from its own
     *  converted bytecode 2026-08-19 (class g00, r8-minified):
     *      new Intent().setData(Uri.fromParts("package", pkg, null))
     *          .addFlags(FLAG_ACTIVITY_NEW_TASK)
     *          .setAction(ACTION_DELETE)            // or, on the pre-P branch,
     *          // ACTION_UNINSTALL_PACKAGE + EXTRA_RETURN_RESULT=true
     *      context.startActivity(i)                 // NOT startActivityForResult
     *  It never reads an activity result: its outcome routes are the
     *  PACKAGE_REMOVED broadcast (AuroraApp's dynamic receiver) and
     *  getInstalledPackages polling, both of which performUninstall serves.
     *  F-Droid's SessionInstallManager takes the OTHER surface
     *  (PackageInstaller.uninstall(String, IntentSender)) -- two stores, two
     *  disjoint routes, same argument as on the install side.
     *
     *  On a device these intents resolve to the system packageinstaller's
     *  UninstallerActivity, whose confirm dialog names the app and gates the
     *  deletion; AOSP requires NO permission to merely fire the intent (the
     *  system-owned UI is the gate), which is why, unlike uninstall() above,
     *  there is no REQUEST_DELETE_PACKAGES enforcement here. Our helper's
     *  device-owner prompt plays the dialog's role, identically to the
     *  session path: same performUninstall, same helper verb, same gate.
     *
     *  Returns true when the intent is claimed (the caller treats it as
     *  handled); false leaves it to fall through exactly as before. */
    public static boolean maybeHandleUninstallIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_DELETE.equals(action)
                && !Intent.ACTION_UNINSTALL_PACKAGE.equals(action)) {
            return false;
        }
        android.net.Uri data = intent.getData();
        if (data == null || !"package".equals(data.getScheme())) return false;
        String pkg = data.getSchemeSpecificPart();
        if (pkg == null || pkg.isEmpty()) return false;
        NativeLog.i(TAG, "uninstall intent (" + action + ") for " + pkg);
        EXEC.execute(() -> performUninstall(pkg, -1, null));
        return true;
    }

    // ---- per-session state ---------------------------------------------

    public static final class SessionState {
        public final int id;
        public final String appPackageName;   // SessionParams.setAppPackageName, may be null
        public final long sizeBytes;          // SessionParams.setSize hint, -1 unknown
        public final String appLabel;         // SessionParams.setAppLabel, may be null
        private final File dir;

        /** Written blobs, in write order: logical openWrite name -> staging
         *  file. The on-disk names are OURS (blob<n>.bin); the guest's names
         *  exist only as map keys and, sanitized, as zip entry strings --
         *  no guest-controlled string ever becomes a filesystem path. */
        private final LinkedHashMap<String, File> blobs = new LinkedHashMap<>();
        private final List<OutputStream> openStreams = new ArrayList<>();
        private int nextBlob = 0;
        private boolean sealed;
        private boolean destroyed;
        private boolean active;

        /** AOSP's progress model, field for field (android14-release
         *  services/core/java/com/android/server/pm/PackageInstallerSession.java
         *  :1049-1061). Keeping the same THREE numbers rather than one is what
         *  makes the published value match a device: `progress` is a pure
         *  function of the other two, and `reportedProgress` is what implements
         *  "only publish meaningful changes".
         *
         *  mClientProgress is the STAGING half -- how much of the APK the app
         *  has handed us. mInternalProgress is the INSTALL half -- how far the
         *  system has got with it. The 0.8/0.2 split between them is AOSP's,
         *  not ours, and it is why a store's bar sits at 80% while the install
         *  itself runs. */
        private float clientProgress   = 0f;
        private float internalProgress = 0f;
        private volatile float progress = 0f;
        private float reportedProgress  = -1f;

        SessionState(int id, String appPackageName, long sizeBytes,
                     String appLabel, File dir) {
            this.id = id;
            this.appPackageName = appPackageName;
            this.sizeBytes = sizeBytes;
            this.appLabel = appLabel;
            this.dir = dir;
        }

        private void requireOpen() {
            // AOSP throws SecurityException from a sealed/destroyed session's
            // mutators ("assertPreparedAndNotSealed").
            if (destroyed) throw new SecurityException("session " + id + " was abandoned");
            if (sealed) throw new SecurityException("session " + id + " is sealed");
        }

        public synchronized OutputStream openWrite(String name, long offsetBytes,
                                                   long lengthBytes) throws IOException {
            requireOpen();
            // AOSP PackageInstallerSession.doWriteInternal: a write that names
            // its length gets the space ALLOCATED up front
            // (StorageManager.allocateBytes), which throws IOException
            // "Failed to allocate N because only M allocatable" when the volume
            // cannot hold it. The store sees its write fail at once instead of
            // streaming gigabytes into a disk that fills to zero (Asphalt via
            // Aurora, 2026-10-02: ~3.7 GB of splits ran the Mac out of space).
            if (lengthBytes > 0 && offsetBytes == 0) {
                long allocatable = allocatableBytes(dir);
                if (allocatable >= 0 && lengthBytes > allocatable) {
                    throw new IOException("Failed to allocate " + lengthBytes
                            + " because only " + allocatable + " allocatable");
                }
            }
            File f = blobs.get(name);
            if (f == null) {
                f = new File(dir, "blob" + (nextBlob++) + ".bin");
                blobs.put(name == null ? "" : name, f);
            }
            OutputStream out;
            if (offsetBytes > 0) {
                // Resume-at-offset (AOSP supports continuing a partial write).
                // RandomAccessFile is kept alive by the wrapper so its fd
                // cannot be finalized out from under the stream.
                RandomAccessFile raf = new RandomAccessFile(f, "rw");
                raf.seek(offsetBytes);
                out = new SessionStream(raf, this, sizeBytes);
            } else {
                out = new SessionStream(f, this, sizeBytes);   // fresh write from 0
            }
            openStreams.add(out);
            NativeLog.i(TAG, "session " + id + " openWrite name=" + name
                    + " offset=" + offsetBytes + " length=" + lengthBytes
                    + " -> " + f.getName());
            return out;
        }

        public synchronized InputStream openRead(String name) throws IOException {
            if (destroyed) throw new SecurityException("session " + id + " was abandoned");
            File f = blobs.get(name);
            if (f == null) throw new IOException("no blob named " + name
                    + " in session " + id);
            return new FileInputStream(f);
        }

        /** AOSP Session.fsync(OutputStream): persist a stream obtained from
         *  openWrite. Only streams we handed out can be synced; anything else
         *  is ignored rather than thrown so a caller mixing wrappers does not
         *  lose the install over a diagnostic-grade mismatch. */
        public void fsync(OutputStream out) throws IOException {
            if (out instanceof FileOutputStream) {
                ((FileOutputStream) out).getFD().sync();
            }
        }

        public synchronized String[] getNames() {
            return blobs.keySet().toArray(new String[0]);
        }

        public synchronized void removeBlob(String name) {
            requireOpen();
            File f = blobs.remove(name);
            if (f != null && !f.delete()) {
                NativeLog.w(TAG, "session " + id + ": could not delete blob for " + name);
            }
        }

        /** AOSP Session.setStagingProgress -> setClientProgress ->
         *  setClientProgressLocked (PackageInstallerSession.java:1316-1328):
         *
         *      final boolean forcePublish = (mClientProgress == 0);
         *      mClientProgress = progress;
         *      computeProgressLocked(forcePublish);
         *
         *  The force on the FIRST movement is deliberate upstream ("Always
         *  publish first staging movement") -- without it a store that reports
         *  0.005 as its opening move would see nothing at all, because the
         *  0.01 publish threshold below would swallow it. The value is NOT
         *  clamped here; AOSP clamps the PRODUCT inside computeProgress, so an
         *  app that reports 1.5 still lands at exactly 0.8, and one that
         *  reports a negative still lands at 0. */
        public synchronized void setStagingProgress(float p) {
            if (destroyed || sealed) return;
            final boolean forcePublish = (clientProgress == 0f);
            clientProgress = p;
            computeProgressLocked(forcePublish);
        }

        /** AOSP addClientProgress (:1332-1337). Used by the write-through
         *  counting stream openWrite hands out, which is our equivalent of
         *  AOSP's doWriteInternal moving client progress by
         *  `delta / params.sizeBytes` as it copies the caller's fd
         *  (:1755-1770). Without it a store that never calls
         *  setStagingProgress itself -- most of them, because on a device the
         *  system moves it for them -- sees a bar frozen at 0 for the whole
         *  download-to-session write. */
        public synchronized void addClientProgress(float delta) {
            if (destroyed || sealed) return;
            final boolean forcePublish = (clientProgress == 0f);
            clientProgress += delta;
            computeProgressLocked(forcePublish);
        }

        /** The INSTALL half of the number. AOSP moves this exactly once, to
         *  0.5, when the committed session has parsed the package and is about
         *  to do its slow work (:2693-2699); every one of our calls is
         *  likewise a real phase boundary that has already happened, never a
         *  timer. Always force-published, as AOSP does at that site. */
        void setInternalProgress(float p) {
            synchronized (this) {
                if (destroyed) return;
                if (p <= internalProgress) return;   // never moves backwards
                internalProgress = p;
                computeProgressLocked(true);
            }
        }

        /** AOSP computeProgressLocked, verbatim in structure
         *  (PackageInstallerSession.java:1340-1361). The incremental-install
         *  branch is omitted because Mac.apk has no incremental sessions --
         *  isIncrementalInstallation() is false for every session we create,
         *  so upstream would take this same branch unconditionally.
         *
         *  MUST be called with the monitor held; the publish itself is posted
         *  off-lock by postToMain, so a callback cannot deadlock against a
         *  session mutation. */
        private void computeProgressLocked(boolean forcePublish) {
            progress = constrain(clientProgress * 0.8f, 0f, 0.8f)
                     + constrain(internalProgress * 0.2f, 0f, 0.2f);
            // "Only publish meaningful progress changes." The 0.01 threshold is
            // AOSP's and it is load-bearing at our byte rates: a 300 MB APK
            // written in 64 KB chunks would otherwise post ~4,800 callbacks
            // onto the main looper.
            if (!forcePublish && (progress - reportedProgress) < 0.01f) return;
            reportedProgress = progress;
            final float fp = progress;
            postToMain(() -> {
                for (PackageInstaller.SessionCallback cb : CALLBACKS) {
                    try { cb.onProgressChanged(id, fp); } catch (Throwable ignored) {}
                }
            });
        }

        /** AOSP tracks an mActiveCount and reports onActiveChanged when it
         *  crosses zero: a session is "active" while someone holds it open or
         *  a commit is running. We report the commit window, which is the
         *  transition a store's UI actually renders ("installing" vs queued). */
        void setActive(boolean now) {
            synchronized (this) {
                if (destroyed || active == now) return;
                active = now;
            }
            postToMain(() -> {
                for (PackageInstaller.SessionCallback cb : CALLBACKS) {
                    try { cb.onActiveChanged(id, now); } catch (Throwable ignored) {}
                }
            });
        }

        public float progress() { return progress; }
        // NOTE: `active` above drives onActiveChanged ONLY. SessionInfo.isActive()
        // deliberately still answers !isDestroyed(). AOSP's SessionInfo.isActive
        // means "ongoing forward progress ... such as the installer holding an
        // open FileBridge", which is TRUE from the first openSession, not just
        // across the commit -- modelling that needs per-handle refcounting, and
        // Session.close() is a deliberate no-op here (Aurora opens the same
        // session twice). Narrowing the reported value without that refcount
        // would make a session that is being written report inactive, which is
        // a worse answer than the current one. Left for a lane that needs it.
        public synchronized boolean isSealed() { return sealed; }
        public synchronized boolean isDestroyed() { return destroyed; }

        public synchronized void abandon() {
            if (destroyed) return;
            destroyed = true;
            closeStreamsLocked();
            SESSIONS.remove(id);
            deleteRecursively(dir);
            NativeLog.i(TAG, "session " + id + " abandoned");
            postToMain(() -> {
                for (PackageInstaller.SessionCallback cb : CALLBACKS) {
                    try { cb.onFinished(id, false); } catch (Throwable ignored) {}
                }
            });
        }

        /** Seal the session and hand it to the install thread. Returns
         *  immediately; the outcome arrives through `statusReceiver` (AOSP
         *  commit is asynchronous and Aurora calls close() right after). */
        public void commit(IntentSender statusReceiver) {
            if (statusReceiver == null) {
                // AOSP declares the parameter @NonNull; a real device NPEs.
                throw new NullPointerException("statusReceiver must not be null");
            }
            synchronized (this) {
                requireOpen();
                sealed = true;
                closeStreamsLocked();
                // AOSP streamValidateAndCommit (:2187-2192): "For non-incremental
                // installs, client staging is fully done at this point" --
                //     mClientProgress = 1f; computeProgressLocked(true);
                // which PUBLISHES 0.8. The old code assigned progress = 0.8f
                // and told nobody, so a store's bar sat wherever its last
                // staging report left it for the entire install window.
                clientProgress = 1f;
                computeProgressLocked(true);
            }
            // AOSP bumps mActiveCount across the commit ("This ongoing commit
            // should keep session active, even though client will probably
            // close their end", :2196) and reports the crossing. This is the
            // transition a store renders as "installing".
            setActive(true);
            NativeLog.i(TAG, "session " + id + " committed ("
                    + blobsSnapshot().size() + " blob(s)); install queued");
            EXEC.execute(() -> performCommit(this, statusReceiver));
        }

        private void closeStreamsLocked() {
            for (OutputStream o : openStreams) {
                try { o.close(); } catch (Throwable ignored) {}
            }
            openStreams.clear();
        }

        synchronized LinkedHashMap<String, File> blobsSnapshot() {
            return new LinkedHashMap<>(blobs);
        }

        File dir() { return dir; }

        void finish(boolean success) {
            if (success) {
                // The install half is done, so internalProgress is 1 and the
                // computed value is 1.0 -- published, not just assigned, so a
                // store's bar actually reaches the end before onFinished. On a
                // FAILURE the number deliberately stays where it stopped:
                // reporting 1.0 for an install that did not happen is the one
                // lie this whole path exists to avoid.
                setInternalProgress(1f);
            }
            setActive(false);
            synchronized (this) {
                destroyed = true;
            }
            SESSIONS.remove(id);
            deleteRecursively(dir);
            postToMain(() -> {
                for (PackageInstaller.SessionCallback cb : CALLBACKS) {
                    try { cb.onFinished(id, success); } catch (Throwable ignored) {}
                }
            });
        }
    }

    /** The stream openWrite hands the app. Two jobs, both of which have to
     *  live in a FileOutputStream SUBCLASS rather than a decorator: fsync()
     *  reaches for `instanceof FileOutputStream` to get at the real fd, and a
     *  FilterOutputStream wrapper would silently turn every fsync into a no-op.
     *
     *  1. Optionally sits on a RandomAccessFile positioned at an offset
     *     (AOSP supports resuming a partial write), holding the RAF so its fd
     *     stays valid for the stream's lifetime.
     *  2. Moves the session's CLIENT progress as bytes land, by
     *     `delta / params.sizeBytes` -- what AOSP's doWriteInternal does while
     *     it copies the caller's fd (PackageInstallerSession.java:1755-1770).
     *     On a device the system owns the copy, so most stores never call
     *     setStagingProgress at all and rely on this entirely.
     *
     *  Counting each override separately is safe against double-counting:
     *  FileOutputStream.write(byte[]) and write(byte[],int,int) both go
     *  straight to the native writeBytes rather than delegating to each other. */
    private static final class SessionStream extends FileOutputStream {
        private final RandomAccessFile raf;   // null unless resuming at an offset
        private final SessionState owner;
        private final long total;             // <= 0 disables progress entirely
        SessionStream(File f, SessionState owner, long total) throws IOException {
            super(f);
            this.raf = null; this.owner = owner; this.total = total;
        }
        SessionStream(RandomAccessFile raf, SessionState owner, long total) throws IOException {
            super(raf.getFD());
            this.raf = raf; this.owner = owner; this.total = total;
        }
        private void bump(long n) {
            if (total <= 0 || n <= 0 || owner == null) return;
            owner.addClientProgress((float) ((double) n / (double) total));
        }
        @Override public void write(int b) throws IOException { super.write(b); bump(1); }
        @Override public void write(byte[] b) throws IOException {
            super.write(b); bump(b == null ? 0 : b.length);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len); bump(len);
        }
        @Override public void close() throws IOException {
            super.close();
            if (raf != null) raf.close();
        }
    }

    /** MathUtils.constrain, which AOSP's computeProgressLocked uses. */
    private static float constrain(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- the slow half, on the macapk-session-install thread -------------

    private static void performCommit(SessionState s, IntentSender statusReceiver) {
        File artifact = null;
        boolean success = false;
        try {
            LinkedHashMap<String, File> blobs = s.blobsSnapshot();
            if (blobs.isEmpty()) {
                deliverStatus(statusReceiver, s.id, s.appPackageName,
                        PackageInstaller.STATUS_FAILURE_INVALID,
                        INSTALL_FAILED_INVALID_APK, "session contains no APK data");
                return;
            }
            // v1.0.1640: AOSP's update-signer gate, before any work. An update
            // must be signed by a signer of the INSTALLED package, or the
            // install fails INSTALL_FAILED_UPDATE_INCOMPATIBLE (-7), which
            // installStatusToPublicStatus maps to STATUS_FAILURE_CONFLICT
            // (android14 InstallPackageHelper.preparePackageLI ->
            // ReconcilePackageUtils / PackageManagerServiceUtils
            // .verifySignatures: "signatures do not match newer version").
            // Load-bearing now that a store update REPLACES the installed app
            // and inherits its data (InstallPipeline in-place update): a
            // differently-signed APK must never adopt another signer's saves.
            // Read from the session's own APKs (the largest is the base of a
            // base+splits set), BEFORE assembleArtifact consumes them.
            String signerConflict = updateSignerConflict(largest(blobs.values()), blobs.values());
            if (signerConflict != null) {
                NativeLog.w(TAG, "session " + s.id + " refused: " + signerConflict);
                deliverStatus(statusReceiver, s.id, s.appPackageName,
                        PackageInstaller.STATUS_FAILURE_CONFLICT,
                        INSTALL_FAILED_UPDATE_INCOMPATIBLE, signerConflict);
                return;
            }
            // STORAGE. The install writes the app once more into its bundle
            // (the extracted APKs), so the volume must hold the package again
            // on top of what is staged. AOSP refuses a package that does not fit
            // with INSTALL_FAILED_INSUFFICIENT_STORAGE (-4), which
            // installStatusToPublicStatus maps to STATUS_FAILURE_STORAGE; we
            // check before writing anything rather than let unzip run the disk
            // to zero. Assembly itself needs at most ONE blob of headroom,
            // because it deletes each blob as soon as it is in the container.
            long packageBytes = 0;
            for (File b : blobs.values()) packageBytes += b.length();
            long allocatable = allocatableBytes(stagingRoot());
            if (allocatable >= 0 && packageBytes > allocatable) {
                NativeLog.w(TAG, "session " + s.id + " refused: needs " + packageBytes
                        + " bytes, only " + allocatable + " allocatable");
                deliverStatus(statusReceiver, s.id, s.appPackageName,
                        PackageInstaller.STATUS_FAILURE_STORAGE,
                        INSTALL_FAILED_INSUFFICIENT_STORAGE,
                        "Insufficient storage: needs " + packageBytes + " bytes, "
                                + allocatable + " available");
                return;
            }
            artifact = assembleArtifact(s, blobs);
            // First INTERNAL step: the blobs are one installable artifact on
            // disk. AOSP's single internal step is likewise "the package has
            // been parsed, the slow part is next" (:2696). Real boundary, not a
            // timer -- the file exists when this line runs.
            s.setInternalProgress(0.25f);
            File helper = locateHelper();
            if (helper == null) {
                NativeLog.e(TAG, "macapk-installhelper not found (MACAPK_INSTALLHELPER"
                        + " unset, no Contents/bin sibling of the runtime binary,"
                        + " no canonical app)");
                deliverStatus(statusReceiver, s.id, s.appPackageName,
                        PackageInstaller.STATUS_FAILURE,
                        INSTALL_FAILED_INTERNAL_ERROR, "install helper missing");
                return;
            }
            String requestor = requestorLabel();
            // The helper's own stage reports drive the rest of the install
            // band. Everything it prints has ALREADY happened, so the number
            // never runs ahead of the work -- see the HelperProgress contract
            // on runInstallHelper.
            HelperResult r = runInstallHelper(helper, artifact, requestor,
                    (stage, fraction) -> {
                        s.setInternalProgress(0.25f + 0.75f * fraction);
                        NativeLog.i(TAG, "session " + s.id + " install stage "
                                + stage + " -> progress " + s.progress());
                    });
            String installedPkg = r.jsonField("package");
            String pkg = (installedPkg != null && !installedPkg.isEmpty())
                    ? installedPkg : s.appPackageName;
            if (r.timedOut) {
                deliverStatus(statusReceiver, s.id, pkg,
                        PackageInstaller.STATUS_FAILURE_TIMEOUT,
                        INSTALL_FAILED_INTERNAL_ERROR,
                        "install helper timed out");
                return;
            }
            switch (r.exit) {
                case 0:
                    success = true;
                    // The installer of record, BEFORE the broadcasts and the
                    // status: Play's post-install step (setApplicationCategoryHint)
                    // is allowed only for the installer of record.
                    InstallerOfRecord.set(pkg, requestor);
                    // ORDER IS AOSP'S, AND IT MATTERS. handlePackagePostInstall
                    // sends the package broadcasts and only then calls
                    // notifyInstallObserver, which is what fires the caller's
                    // IntentSender (InstallPackageHelper.java:2890-2990 then
                    // :3101). A store whose broadcast handler and whose status
                    // handler both refresh the same row therefore sees the
                    // broadcast first on a device too; delivering the status
                    // first would let a store finish its install flow and tear
                    // down before its own package-added listener ever ran.
                    broadcastPackageInstalled(pkg, "true".equals(r.jsonField("wasUpdate")));
                    deliverStatus(statusReceiver, s.id, pkg,
                            PackageInstaller.STATUS_SUCCESS,
                            INSTALL_SUCCEEDED, "INSTALL_SUCCEEDED");
                    break;
                case 3:   // cancelled -- the user declined the confirm
                case 4:   // auth failed -- the user failed/dismissed the auth
                    // AOSP maps "user actively declined" to
                    // STATUS_FAILURE_ABORTED (its javadoc's example is exactly
                    // this: "the user declined the request").
                    deliverStatus(statusReceiver, s.id, pkg,
                            PackageInstaller.STATUS_FAILURE_ABORTED,
                            INSTALL_FAILED_ABORTED,
                            "declined by device owner (helper exit " + r.exit + ")");
                    break;
                case 6:   // unsupported input (incl. the invalid-apk stage)
                    deliverStatus(statusReceiver, s.id, pkg,
                            PackageInstaller.STATUS_FAILURE_INVALID,
                            INSTALL_FAILED_INVALID_APK,
                            "helper rejected the package (exit 6)"
                                    + helperReason(r));
                    break;
                default:  // 2 bad args, 5 error
                    // The reason rides in the helper's own JSON stage/message
                    // fields. Passing them through is what lets a human
                    // diagnose the failure from the store's error dialog --
                    // the bare "helper exit 5" told the owner nothing.
                    deliverStatus(statusReceiver, s.id, pkg,
                            PackageInstaller.STATUS_FAILURE,
                            INSTALL_FAILED_INTERNAL_ERROR,
                            "helper exit " + r.exit + helperReason(r));
                    break;
            }
        } catch (Throwable t) {
            NativeLog.e(TAG, "session " + s.id + " install failed: " + t);
            t.printStackTrace();
            deliverStatus(statusReceiver, s.id, s.appPackageName,
                    PackageInstaller.STATUS_FAILURE,
                    INSTALL_FAILED_INTERNAL_ERROR, String.valueOf(t));
        } finally {
            if (artifact != null && artifact.exists() && !artifact.delete()) {
                NativeLog.w(TAG, "could not delete staged artifact " + artifact);
            }
            s.finish(success);
        }
    }

    /** The slow half of a guest-initiated uninstall, on the same install
     *  thread. Mirrors AOSP's shape end to end:
     *
     *   - presence + visibility pre-check through the SAME PackageManager the
     *     guest itself queries, so this leaks nothing getPackageInfo would not
     *     (an invisible package and an absent one both answer
     *     DELETE_FAILED_INTERNAL_ERROR, which is AOSP's own answer --
     *     DeletePackageHelper.java:158 "Not removing non-existent package");
     *   - an explicit versionCode must match the installed one
     *     (DeletePackageHelper.java:163-167);
     *   - the HELPER owns the human gate: we invoke it with --source android
     *     (compile-time constant) and it refuses to act for that source
     *     without a successful LAContext device-owner check naming the target;
     *   - on success, broadcast ACTION_PACKAGE_REMOVED (and _FULLY_REMOVED
     *     when the helper's evidence says the data went too) BEFORE the status
     *     Intent -- AOSP fires the removed broadcasts from deletePackageX
     *     (DeletePackageHelper.java:265, info.sendPackageRemovedBroadcasts)
     *     and the observer that feeds the caller's IntentSender only after it
     *     returns (:838), same order as the install side;
     *   - never report STATUS_SUCCESS for an uninstall that did not happen,
     *     and never delete anything from THIS process: the runtime side of
     *     this path performs zero filesystem mutation, so a guest that
     *     redirects MACAPK_INSTALLHELPER at a fake helper gets a fabricated
     *     answer delivered to itself and nothing is deleted anywhere. */
    private static void performUninstall(String pkg, long versionCode,
                                         IntentSender sender) {
        if (pkg == null || pkg.isEmpty()) {
            deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                    DELETE_FAILED_INTERNAL_ERROR,
                    deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                            "no package name"));
            return;
        }
        android.app.Application app = null;
        try { app = android.app.Application.getInstance(); }
        catch (Throwable ignored) {}
        String selfPkg = null;
        try { selfPkg = app != null ? app.getPackageName() : null; }
        catch (Throwable ignored) {}

        // Presence + visibility, via the one implementation the app itself
        // sees. getPackageInfo throws NameNotFoundException both for a package
        // that is not installed and for one the caller may not see (API-30
        // visibility filtering) -- identical to a device, where the filtered
        // snapshot answers null and deletePackageX reports non-existent.
        android.content.pm.PackageInfo target = null;
        try {
            if (app != null) target = app.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Throwable ignored) {}
        if (target == null) {
            deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                    DELETE_FAILED_INTERNAL_ERROR,
                    deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                            "Not removing non-existent package " + pkg));
            return;
        }
        if (versionCode != -1) {   // PackageManager.VERSION_CODE_HIGHEST
            long installed;
            try { installed = target.getLongVersionCode(); }
            catch (Throwable t) { installed = target.versionCode; }
            if (installed != versionCode) {
                deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                        DELETE_FAILED_INTERNAL_ERROR,
                        deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                                "Not removing package " + pkg + " with versionCode "
                                + installed + " != " + versionCode));
                return;
            }
        }
        File helper = locateHelper();
        if (helper == null) {
            NativeLog.e(TAG, "macapk-installhelper not found for uninstall");
            deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                    DELETE_FAILED_INTERNAL_ERROR,
                    deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                            "install helper missing"));
            return;
        }
        // Flag BEFORE the helper runs: it rewrites the registry mid-flight,
        // and a concurrent PackageManager query would otherwise diff the file
        // and announce the removal before we can (with worse evidence).
        UNINSTALLS_IN_FLIGHT.add(pkg);
        try {
            HelperResult r = runUninstallHelper(helper, pkg, requestorLabel());
            if (r.timedOut) {
                deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                        DELETE_FAILED_INTERNAL_ERROR,
                        deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                                "uninstall helper timed out"));
                return;
            }
            switch (r.exit) {
                case 0: {
                    // The helper VERIFIED what it removed; dataRemoved is its
                    // evidence (bundle gone + data dir gone + nothing parked),
                    // not our assumption.
                    boolean dataRemoved = "true".equals(r.jsonField("dataRemoved"));
                    if (pkg.equals(selfPkg)) {
                        // The hosted app uninstalled ITSELF. On a device the
                        // package is frozen and its process killed during the
                        // delete (DeletePackageHelper.deletePackageX takes a
                        // PackageFreezer and sendPackageRemovedBroadcasts runs
                        // with killApp=true), so the app never observes its own
                        // STATUS_SUCCESS -- and the removed package is excluded
                        // from its own PACKAGE_REMOVED. In this process the
                        // only possible recipient of either IS the app being
                        // removed, so mirror the device: log for the human,
                        // then exit.
                        NativeLog.i(TAG, "uninstalled the hosted app itself ("
                                + pkg + "); exiting, as a device kills the"
                                + " removed app's process");
                        System.exit(0);
                        return;   // unreachable
                    }
                    // Advance the registry observer's baseline WHILE the
                    // in-flight flag is up, so no later PackageManager query
                    // can re-diff this removal and announce it a second time.
                    try { MacApkPackageManager.InstalledRegistry.snapshot(); }
                    catch (Throwable ignored) {}
                    // ORDER IS AOSP'S: broadcasts first, then the caller's
                    // IntentSender -- see the method header.
                    InstallerOfRecord.set(pkg, null);
                    broadcastPackageRemoved(pkg, dataRemoved);
                    deliverUninstallStatus(sender, pkg,
                            PackageInstaller.STATUS_SUCCESS, DELETE_SUCCEEDED,
                            deleteStatusToString(DELETE_SUCCEEDED, null));
                    break;
                }
                case 3:   // cancelled -- the user declined
                case 4:   // auth failed -- the user failed/dismissed the check
                    // AOSP: the confirm UI's decline surfaces as
                    // DELETE_FAILED_ABORTED, which deleteStatusToPublicStatus
                    // maps to STATUS_FAILURE_ABORTED ("the user actively
                    // declined", PackageInstaller.java's own javadoc).
                    deliverUninstallStatus(sender, pkg,
                            PackageInstaller.STATUS_FAILURE_ABORTED,
                            DELETE_FAILED_ABORTED,
                            deleteStatusToString(DELETE_FAILED_ABORTED,
                                    "declined by device owner (helper exit "
                                    + r.exit + ")"));
                    break;
                default:  // 2 bad args, 5 error, 6 not installed (helper view)
                    deliverUninstallStatus(sender, pkg,
                            PackageInstaller.STATUS_FAILURE,
                            DELETE_FAILED_INTERNAL_ERROR,
                            deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                                    "helper exit " + r.exit + helperReason(r)));
                    break;
            }
        } catch (Throwable t) {
            NativeLog.e(TAG, "uninstall of " + pkg + " failed: " + t);
            deliverUninstallStatus(sender, pkg, PackageInstaller.STATUS_FAILURE,
                    DELETE_FAILED_INTERNAL_ERROR,
                    deleteStatusToString(DELETE_FAILED_INTERNAL_ERROR,
                            String.valueOf(t)));
        } finally {
            UNINSTALLS_IN_FLIGHT.remove(pkg);
        }
    }

    /** The uninstall status fill-in, shaped verbatim from AOSP
     *  android14-release PackageInstallerService.PackageDeleteObserverAdapter
     *  .onPackageDeleted (:1556-1573): EXTRA_PACKAGE_NAME, EXTRA_STATUS
     *  (deleteStatusToPublicStatus of the code), EXTRA_STATUS_MESSAGE
     *  (deleteStatusToString), EXTRA_LEGACY_STATUS (the raw DELETE_* code) --
     *  and deliberately NO EXTRA_SESSION_ID and NO EXTRA_PRE_APPROVAL: an
     *  uninstall has no session, and AOSP's fill-in carries neither. */
    private static void deliverUninstallStatus(IntentSender sender, String pkg,
                                               int status, int legacyStatus,
                                               String message) {
        // Logged before the null-sender return: the INTENT uninstall surface
        // has no IntentSender (Aurora never reads a result), and the outcome
        // must still leave a record.
        NativeLog.i(TAG, "uninstall status=" + status + " pkg=" + pkg
                + " (" + message + ")");
        DeleteSink sink = DELETE_SINK.get();
        if (sink != null) {
            try { sink.onDeleted(pkg, legacyStatus, message); }
            catch (Throwable t) { NativeLog.e(TAG, "delete observer failed for " + pkg + ": " + t); }
            return;
        }
        if (sender == null) return;
        Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS, status);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, legacyStatus);
        try {
            sender.sendIntent(android.app.Application.getInstance(), 0, fillIn, null, null);
        } catch (Throwable t) {
            NativeLog.e(TAG, "uninstall status delivery failed for " + pkg + ": " + t);
        }
    }

    /** PackageManager.deleteStatusToString(status, msg), mirrored for the
     *  three codes this class produces: the name, then ": msg" when a detail
     *  is present (AOSP appends exactly that). */
    private static String deleteStatusToString(int status, String msg) {
        String str;
        switch (status) {
            case DELETE_SUCCEEDED:             str = "DELETE_SUCCEEDED"; break;
            case DELETE_FAILED_INTERNAL_ERROR: str = "DELETE_FAILED_INTERNAL_ERROR"; break;
            case DELETE_FAILED_ABORTED:        str = "DELETE_FAILED_ABORTED"; break;
            default:                           str = Integer.toString(status); break;
        }
        return msg != null ? (str + ": " + msg) : str;
    }

    /** Turn the session's written blobs into the ONE file the helper installs.
     *
     *  Single blob: the common no-splits case; renamed to a name of OURS in
     *  the staging root and handed over exactly like InstallIntentHandler's
     *  staged file.
     *
     *  Multiple blobs (Aurora writes base + config splits as separate
     *  openWrite names): packaged as a root-layout zip-of-apks and handed to
     *  the helper as ONE `.apkm`. This deliberately reuses the runtime's
     *  EXISTING bundle machinery end to end instead of inventing a second
     *  merge: --peek-manifest and the unpacker both already understand the
     *  root layout (apk_unpacker.cpp SelectBundleApks: base = the entry named
     *  base.apk, else <package>.apk, else the largest -- and the largest IS
     *  the base for a base+config-splits set), and the launch path merges the
     *  splits the same way it does for an APKMirror .apkm dropped by hand.
     *  Entry names: the guest's openWrite names, sanitized, with a
     *  "<appPackageName>_" prefix stripped when Aurora's naming convention
     *  ("${pkg}_${file.name}", SessionInstaller.kt) left one -- that recovers
     *  the vendor-shaped "base.apk"/"split_config.<q>.apk" names the split-
     *  resource merge keys on -- and a ".apk" suffix enforced because the
     *  bundle reader only considers *.apk entries. */
    private static File assembleArtifact(SessionState s,
                                         LinkedHashMap<String, File> blobs)
            throws IOException {
        String ours = "guest-session-" + s.id + "-" + System.currentTimeMillis()
                + "-" + Integer.toHexString((int) (Math.random() * 0x7fffffff));
        if (blobs.size() == 1) {
            File src = blobs.values().iterator().next();
            File dst = new File(stagingRoot(), ours + ".apk");
            if (!src.renameTo(dst)) {
                copyFile(src, dst);
            }
            NativeLog.i(TAG, "session " + s.id + " staged single apk "
                    + dst.getName() + " (" + dst.length() + " bytes)");
            return dst;
        }
        File dst = new File(stagingRoot(), ours + ".apkm");
        java.util.LinkedHashSet<String> used = new java.util.LinkedHashSet<>();
        // ONE COPY AT A TIME (2026-10-02). The container used to be built
        // beside the blobs, which were removed only when the session finished,
        // so a 3.7 GB Asphalt install held the blobs AND a 3.5 GB container AND
        // the installed app at once (with the store's own download, ~14.7 GB)
        // and filled the disk. Now each blob is deleted as soon as it is in the
        // container, so assembly needs one blob of headroom, not the whole
        // package. The session is committed and sealed, so nothing reads the
        // blobs again; a failure here fails the install either way.
        // DEFLATE at BEST_SPEED, not STORED: modern APKs keep their .so and
        // resources.arsc uncompressed, so a STORED container of Reddit's 33
        // splits measured 174 MB where the deflated APKMirror bundle is 82 MB;
        // the fastest level keeps most of that saving for a fraction of the CPU.
        byte[] buf = new byte[1 << 20];
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(
                new FileOutputStream(dst), 1 << 20))) {
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
            for (Map.Entry<String, File> e : blobs.entrySet()) {
                String entry = containerEntryName(e.getKey(), s.appPackageName, used);
                File src = e.getValue();
                zip.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new FileInputStream(src)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
                zip.flush();
                if (!src.delete()) {
                    NativeLog.w(TAG, "session " + s.id + ": could not delete consumed blob "
                            + src.getName());
                }
            }
        } catch (IOException ioe) {
            dst.delete();
            throw ioe;
        }
        NativeLog.i(TAG, "session " + s.id + " staged " + blobs.size()
                + "-part bundle " + dst.getName() + " (" + dst.length()
                + " bytes, entries=" + used + ")");
        return dst;
    }

    /** Zip entry name for a written blob: strip the caller's package-name
     *  prefix when present, keep only filesystem-inert characters, force the
     *  .apk suffix, dedupe. The result is used ONLY inside the zip we build. */
    private static String containerEntryName(String logical, String pkg,
                                             java.util.LinkedHashSet<String> used) {
        String name = logical == null ? "" : logical;
        if (pkg != null && !pkg.isEmpty() && name.startsWith(pkg + "_")
                && name.length() > pkg.length() + 1) {
            name = name.substring(pkg.length() + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        while (out.startsWith(".")) out = "_" + out.substring(1);
        if (out.length() > 180) out = out.substring(out.length() - 180);
        if (out.isEmpty()) out = "blob";
        if (!out.endsWith(".apk")) out = out + ".apk";
        String candidate = out;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = out.substring(0, out.length() - 4) + "-" + (n++) + ".apk";
        }
        return candidate;
    }

    /** The largest file, or null: the base APK of a base+config-splits set. */
    private static File largest(java.util.Collection<File> files) {
        File best = null;
        for (File f : files) if (best == null || f.length() > best.length()) best = f;
        return best;
    }

    /** Bytes a write may still use on dir's volume, keeping back the low-storage
     *  reserve as Android's StorageManager.getAllocatableBytes does
     *  (StorageManager.getStorageLowBytes: 5% of the volume, capped at 500 MB),
     *  so an install can never take the disk to zero. -1 when unknown (then
     *  nothing is refused on that ground). */
    static long allocatableBytes(File dir) {
        try {
            long usable = dir.getUsableSpace();
            long total = dir.getTotalSpace();
            if (usable <= 0 || total <= 0) return -1;
            long reserve = Math.min(total / 20, 500L * 1024 * 1024);
            return Math.max(0, usable - reserve);
        } catch (SecurityException e) {
            return -1;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---- status delivery -------------------------------------------------

    /** Build the AOSP status fill-in Intent and push it through the caller's
     *  IntentSender. Shape verbatim from android14-release
     *  PackageInstallerSession.sendOnPackageInstalled: EXTRA_PACKAGE_NAME,
     *  EXTRA_SESSION_ID, EXTRA_STATUS, EXTRA_STATUS_MESSAGE,
     *  EXTRA_LEGACY_STATUS, EXTRA_PRE_APPROVAL, delivered with
     *  IntentSender.sendIntent so the creator's base Intent (its action, its
     *  explicit receiver component, the app's own extras) survives via
     *  PendingIntent's fill-in merge. */
    private static void deliverStatus(IntentSender sender, int sessionId, String pkg,
                                      int status, int legacyStatus, String message) {
        Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg);
        fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS, status);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, legacyStatus);
        fillIn.putExtra(PackageInstaller.EXTRA_PRE_APPROVAL, false);
        NativeLog.i(TAG, "session " + sessionId + " status=" + status
                + " pkg=" + pkg + " (" + message + ")");
        try {
            sender.sendIntent(android.app.Application.getInstance(), 0, fillIn, null, null);
        } catch (Throwable t) {
            NativeLog.e(TAG, "session " + sessionId + " status delivery failed: " + t);
        }
    }

    // ---- package-lifecycle broadcasts (the SECOND confirmation route) -----

    /** Announce a completed install to the process the way a device does, so
     *  an app that watches for package events -- rather than, or as well as,
     *  the session's IntentSender status -- learns the install finished.
     *
     *  WHY THIS EXISTS, and why it is not Aurora-shaped. On a device a store
     *  has at least THREE independent ways to observe "installed", and the two
     *  we can measure use different ones:
     *    - Aurora Store 4.8.4 registers a runtime receiver in AuroraApp for
     *      {PACKAGE_INSTALL, PACKAGE_ADDED, PACKAGE_REMOVED} with
     *      addDataScheme("package"), AND registers a SessionCallback, AND
     *      re-checks getInstalledPackages from its status receiver;
     *    - F-Droid 1.23.2 registers NO package receiver and NO SessionCallback,
     *      and polls getPackageInfo / getInstalledPackages instead.
     *  (Both measured from their own converted bytecode + decoded manifests,
     *  2026-08-18, not from recollection of what those apps "probably" do.)
     *  Before this, route 1 did not exist at all in Mac.apk and only F-Droid's
     *  route worked. Implementing all of the device's routes is what makes a
     *  THIRD store we have never run work without knowing anything about it.
     *
     *  SHAPE, verbatim from AOSP android14-release:
     *    BroadcastHelper.doSendBroadcast:118-125 --
     *      new Intent(action, Uri.fromParts("package", pkg, null))
     *      ... intent.putExtra(Intent.EXTRA_USER_HANDLE, userId)
     *    InstallPackageHelper:2879-2883 -- extras carry EXTRA_UID and, for an
     *      update, EXTRA_REPLACING=true
     *    InstallPackageHelper:2890-2985 -- PACKAGE_ADDED always; PACKAGE_REPLACED
     *      as well when this replaced an existing package; then
     *      ACTION_MY_PACKAGE_REPLACED targeted at the replaced package ITSELF,
     *      with no data Uri and no extras.
     *  The `package:<pkg>` data is not decoration: a filter that declares
     *  addDataScheme("package") -- which every package-event receiver does --
     *  cannot match an action-only intent, so sending these bare would reach
     *  nobody and look exactly like the bug this fixes.
     *
     *  DELIBERATE OMISSION -- EXTRA_UID. AOSP always carries the installed
     *  package's uid. Mac.apk hosts one app per process and models no uid for
     *  any OTHER package, so there is no honest value to put here: sending
     *  Process.myUid() would assert the new package shares this app's sandbox,
     *  which on Android means something specific and false. Omitting it leaves
     *  getIntExtra(EXTRA_UID, -1) == -1, and -1 is AOSP's OWN sentinel for
     *  "no uid on this intent" (BroadcastHelper.java:135 only rewrites when
     *  `uid >= 0`). A store that keys off uid rather than the package name will
     *  not work here; that is a known, stated gap rather than a fabricated
     *  number.
     *
     *  PUBLIC because BOTH install surfaces must announce identically -- see
     *  the SHARED-LOGIC note in the class header. InstallIntentHandler calls
     *  this too; two surfaces that announce differently is the same class of
     *  bug as two surfaces that stage differently. */
    public static void broadcastPackageInstalled(String pkg, boolean wasUpdate) {
        if (pkg == null || pkg.isEmpty()) return;
        android.app.Application app;
        try { app = android.app.Application.getInstance(); }
        catch (Throwable t) { return; }
        if (app == null) return;

        android.net.Uri data;
        try { data = android.net.Uri.fromParts("package", pkg, null); }
        catch (Throwable t) { return; }

        NativeLog.i(TAG, "broadcasting package " + (wasUpdate ? "REPLACED" : "ADDED")
                + " for " + pkg);
        // ON THE MAIN THREAD, in ONE post. Android delivers a broadcast to a
        // receiver registered without an explicit Handler on the app's main
        // thread -- that is why BroadcastReceiver.onReceive may touch views at
        // all -- and this runs on the macapk-session-install worker, so a
        // store's package-event handler would otherwise get called off-main and
        // any UI it touches would be wrong in a way that only shows up in the
        // field. (Mac.apk's general dynamic-broadcast dispatch is still inline
        // on the caller's thread; narrowing the fix to this announcement keeps
        // the install contract right without changing what every other
        // in-process broadcast in the fleet does.)
        //
        // One post rather than three keeps AOSP's ORDER intact -- PACKAGE_ADDED,
        // then PACKAGE_REPLACED, then MY_PACKAGE_REPLACED -- and keeps the whole
        // announcement ahead of the status Intent, which reaches the main looper
        // through its own later post.
        final android.app.Application capp = app;
        final android.net.Uri cdata = data;
        final String cpkg = pkg;
        final boolean cupd = wasUpdate;
        postToMain(() -> {
            sendPackageBroadcast(capp, Intent.ACTION_PACKAGE_ADDED, cdata, cupd);
            if (!cupd) return;
            sendPackageBroadcast(capp, Intent.ACTION_PACKAGE_REPLACED, cdata, true);
            // AOSP sends MY_PACKAGE_REPLACED with targetPackage = the replaced
            // package and NO data, i.e. only that app hears it. In this process
            // that is only ever true when the hosted app just updated ITSELF.
            String self = null;
            try { self = capp.getPackageName(); } catch (Throwable ignored) {}
            if (cpkg.equals(self)) {
                Intent mine = new Intent(Intent.ACTION_MY_PACKAGE_REPLACED);
                mine.setPackage(self);
                try { capp.sendBroadcast(mine); } catch (Throwable ignored) {}
            }
        });
    }

    /** The REMOVAL half of the same contract: announce that a package this
     *  process previously reported as installed is not installed any more.
     *
     *  WHY IT EXISTS AT ALL, given that Mac.apk's PackageInstaller.uninstall()
     *  still reports STATUS_FAILURE (no auth gate on the helper's uninstall
     *  verb -- unchanged, and deliberately not weakened here): a package can
     *  stop being installed WITHOUT any uninstall API being called. macOS lets
     *  the user drag an app's .app bundle to the Trash at any moment, behind
     *  our back and with no notification, and the bundle IS the app's only
     *  home (code and data both). The launcher's registry reconciliation
     *  notices and flips the row's state; this is how the RUNNING guest finds
     *  out. Aurora Store 4.8.4 registers exactly this action, dynamically, in
     *  AuroraApp, alongside PACKAGE_ADDED (measured from its own bytecode,
     *  2026-08-18) -- so a store that is open while the user trashes an app
     *  learns about it the way it would on a device.
     *
     *  SHAPE, from AOSP android14-release:
     *    Intent.ACTION_PACKAGE_REMOVED -- "A package is no longer available on
     *      the system", data = package:<pkg>. The package being removed does
     *      NOT receive it (mirrored below by the self-check).
     *    Intent.ACTION_PACKAGE_FULLY_REMOVED -- "A package has been completely
     *      removed from the device"; PackageRemovedInfo's
     *      sendPackageRemovedBroadcastInternal sends it AFTER
     *      ACTION_PACKAGE_REMOVED and only when the data went too.
     *    EXTRA_DATA_REMOVED -- true when the entire application, data and
     *      code, is going.
     *
     *  `dataRemoved` is NOT assumed. The caller passes true only for the case
     *  where the evidence says so (the row survives in the registry marked
     *  `removed`, which in Mac.apk means precisely "the bundle -- and
     *  therefore Contents/android, the app's data -- is gone"). A row that
     *  vanished from the registry entirely came from an explicit uninstall,
     *  which may have PARKED the data (KeptData), so we claim less and send no
     *  FULLY_REMOVED.
     *
     *  EXTRA_UID is omitted for the same reason as on the install side: we
     *  model no uid for any package other than the hosted one, and -1 is
     *  AOSP's own sentinel. */
    public static void broadcastPackageRemoved(String pkg, boolean dataRemoved) {
        if (pkg == null || pkg.isEmpty()) return;
        android.app.Application app;
        try { app = android.app.Application.getInstance(); }
        catch (Throwable t) { return; }
        if (app == null) return;

        // AOSP: "a special case ... do not allow the package being added to
        // see this broadcast" has a removal twin -- the removed package does
        // not receive ACTION_PACKAGE_REMOVED. In this process the only
        // possible recipient IS the hosted app, so if the hosted app is the
        // one that went away there is nobody to tell.
        try {
            String self = app.getPackageName();
            if (pkg.equals(self)) return;
        } catch (Throwable ignored) {}

        android.net.Uri data;
        try { data = android.net.Uri.fromParts("package", pkg, null); }
        catch (Throwable t) { return; }

        NativeLog.i(TAG, "broadcasting package REMOVED for " + pkg
                + (dataRemoved ? " (data removed)" : " (data may be kept)"));
        final android.app.Application capp = app;
        final android.net.Uri cdata = data;
        final boolean cdrm = dataRemoved;
        // One post, main thread, AOSP order -- same reasoning as the install
        // announcement above.
        postToMain(() -> {
            Intent i = new Intent(Intent.ACTION_PACKAGE_REMOVED, cdata);
            i.putExtra(Intent.EXTRA_DATA_REMOVED, cdrm);
            i.putExtra(Intent.EXTRA_USER_HANDLE, 0);
            try { capp.sendBroadcast(i); }
            catch (Throwable t) { NativeLog.w(TAG, "PACKAGE_REMOVED broadcast failed: " + t); }
            if (!cdrm) return;
            Intent f = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED, cdata);
            f.putExtra(Intent.EXTRA_DATA_REMOVED, true);
            f.putExtra(Intent.EXTRA_USER_HANDLE, 0);
            try { capp.sendBroadcast(f); }
            catch (Throwable t) { NativeLog.w(TAG, "PACKAGE_FULLY_REMOVED broadcast failed: " + t); }
        });
    }

    private static void sendPackageBroadcast(android.app.Application app, String action,
                                             android.net.Uri data, boolean replacing) {
        Intent i = new Intent(action, data);
        if (replacing) i.putExtra(Intent.EXTRA_REPLACING, true);
        // AOSP puts the user id on every one of these; we are always user 0.
        i.putExtra(Intent.EXTRA_USER_HANDLE, 0);
        try { app.sendBroadcast(i); }
        catch (Throwable t) { NativeLog.w(TAG, action + " broadcast failed: " + t); }
    }

    // ---- helper invocation (shared facts with InstallIntentHandler) ------

    /** The staging root both install surfaces use. The launcher's retention
     *  sweep (launcher/InstallCore.swift) cleans day-old leftovers here, so an
     *  interrupted session never accumulates. */
    public static File stagingRoot() throws IOException {
        File dir = new File(System.getProperty("user.home"),
                "Library/Application Support/Mac.apk/staging");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create staging dir " + dir);
        }
        return dir;
    }

    /** Where the Mac.apk control panel (Mac.apk.app) may live, in order:
     *  MACAPK_CANONICAL_APP (the app stub exports the one it resolved, which
     *  covers any folder Launch Services knows), /Applications, then the
     *  per-user ~/Applications (GitHub #13). The staged shared runtime ships
     *  neither macapk-installhelper nor the location helper, so an installed
     *  app finds both here. */
    public static java.util.List<File> canonicalAppCandidates() {
        java.util.List<File> out = new java.util.ArrayList<>();
        String env = System.getenv("MACAPK_CANONICAL_APP");
        if (env != null && !env.isEmpty()) out.add(new File(env));
        out.add(new File("/Applications/Mac.apk.app"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) out.add(new File(home, "Applications/Mac.apk.app"));
        return out;
    }

    /** Find the install helper, for BOTH install surfaces (InstallIntentHandler
     *  delegates here; it used to carry an identical private copy). Order: the
     *  MACAPK_INSTALLHELPER environment export (picks WHICH binary we ask, not
     *  what it demands -- see the class header), then <bundle>/Contents/bin next
     *  to the running runtime binary (the runtime lives in Contents/Resources),
     *  then the canonical installed app. A repo-tree headless run has no helper
     *  in runtime/build/bin, so it resolves to the canonical app's copy --
     *  acceptable skew for a diagnostic run, and debug/staleness.sh already
     *  polices bundle drift. */
    public static File locateHelper() {
        String env = System.getenv("MACAPK_INSTALLHELPER");
        if (env != null && !env.isEmpty()) {
            File f = new File(env);
            if (f.canExecute()) return f;
            NativeLog.w(TAG, "MACAPK_INSTALLHELPER=" + env + " is not executable -- ignoring");
        }
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd != null) {
                File exeDir = new File(cmd).getParentFile();
                if (exeDir != null) {
                    File contents = exeDir.getParentFile();
                    if (contents != null) {
                        File f = new File(contents, "bin/macapk-installhelper");
                        if (f.canExecute()) return f;
                    }
                    File f2 = new File(exeDir, "macapk-installhelper");
                    if (f2.canExecute()) return f2;
                }
            }
        } catch (Throwable ignored) {}
        // The control panel: MACAPK_CANONICAL_APP (the stub exports the one it
        // resolved), then the two standard install locations (GitHub #13: a
        // Mac.apk in ~/Applications left installed apps with no helper).
        for (File app : PackageInstallerBackend.canonicalAppCandidates()) {
            File f = new File(app, "Contents/bin/macapk-installhelper");
            if (f.canExecute()) return f;
        }
        return null;
    }

    public static final class HelperResult {
        public final int exit;
        public final String output;
        public final boolean timedOut;
        HelperResult(int exit, String output, boolean timedOut) {
            this.exit = exit;
            this.output = output;
            this.timedOut = timedOut;
        }
        /** Pull a string field out of the helper's one-line --json object.
         *  The helper's emitter is ours (InstallHelperMain.emit), so a plain
         *  scan is safe against the only producer. */
        public String jsonField(String key) {
            if (output == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(output);
            String last = null;
            while (m.find()) last = m.group(1);
            return last;
        }
    }

    /** Called for each stage the helper reports it has FINISHED. `fraction` is
     *  in [0,1] over the install's own work. Never a prediction: the helper
     *  prints a stage only after doing it, so a listener can move a progress
     *  bar without any chance of running ahead of the install. */
    public interface HelperProgress {
        void onStage(String stage, float fraction);
    }

    /** Machine-readable stage line the helper prints on stderr (which we
     *  redirect into the same stream): `[helper] progress <fraction> <stage>`.
     *  Emitted by launcher/InstallHelperMain.swift from InstallPipeline's
     *  progress hook. A dedicated line rather than pattern-matching the
     *  human-readable "[install] ..." log, because parsing prose is how a
     *  progress bar silently dies the next time someone rewords a log
     *  message. */
    private static final java.util.regex.Pattern HELPER_PROGRESS =
        java.util.regex.Pattern.compile(
            "^\\[helper\\] progress ([0-9]*\\.?[0-9]+) (\\S+)");

    public static HelperResult runInstallHelper(File helper, File staged, String requestor)
            throws IOException, InterruptedException {
        return runInstallHelper(helper, staged, requestor, null);
    }

    /** Invoke the helper SYNCHRONOUSLY: install --apk <staged> --source
     *  android --requestor-label <who> --json. argv array, never a shell, so
     *  no injection surface.
     *
     *  THE HELPER'S OUTPUT GOES TO A FILE, NEVER TO A PIPE, AND THAT IS THE
     *  WHOLE POINT OF THIS METHOD'S SHAPE. It used to be
     *  `redirectErrorStream(true)` + a reader on `p.getInputStream()`, i.e. an
     *  ordinary pipe whose ONLY reader was this JVM. A pipe with no live reader
     *  kills its writer: the moment this process goes away, the read end
     *  closes, and the helper's very next line of output raises SIGPIPE, whose
     *  default disposition terminates it. So the guest dying took the install
     *  down with it -- mid-extract, because extraction is the long stage that
     *  emits progress lines, so it is overwhelmingly where the next write
     *  lands.
     *
     *  MEASURED 2026-08-19, both directions, because the two differ only in
     *  where the child writes:
     *    - child writing to a FILE, parent JVM SIGTERMed: child ran to
     *      completion, reparented to launchd (ppid 1).
     *    - child writing to a PIPE this JVM reads (the old shape, reproduced
     *      exactly), parent JVM SIGTERMed: child died at its next write.
     *  That is the mechanism behind the owner's aborted install on the same
     *  day: he quit the launcher, the child runtime went with it, and an
     *  install he had ALREADY approved at the Touch ID prompt was killed
     *  part-way through extraction, leaving a half-built bundle and a ledger
     *  row stuck at `installing`.
     *
     *  AOSP is the argument for the fix, not just the symptom. A
     *  PackageInstallerSession's work is owned by the SYSTEM, not by the
     *  installing app's process (PackageInstallerService even persists sessions
     *  to /data/system/install_sessions.xml and resumes them across a REBOOT),
     *  so killing the store on a device does not abort an install in progress.
     *  We cannot host a system_server, but the helper is already a separate
     *  process with its own consent gate -- so the faithful move is to let it
     *  OWN the install and finish without us, rather than to keep the guest
     *  alive to babysit it. Keeping the guest alive would also be worse for the
     *  user: it makes quitting an app hang.
     *
     *  Nothing here touches the security contract. The helper is still invoked
     *  with --source android (a compile-time constant, see the class header),
     *  it still owns the consent + device-owner gate, and it still decides on
     *  its own whether to act. All that changed is which file descriptor its
     *  stdout points at.
     *
     *  TIMEOUT SEMANTICS, precisely: there is NO ceiling on the helper's work,
     *  deliberately -- a legitimate install has no upper bound, because the
     *  helper blocks on a human at a Touch ID prompt and then on a multi-minute
     *  extract (TikTok's 197 MB bundle: ~4.5 min of extract alone), so any
     *  wall-clock ceiling would fail real installs. The previous code carried a
     *  600 s `waitFor` that appeared to be such a ceiling but was not: the read
     *  loop ran to stream EOF first, and EOF arrives when the child exits, so
     *  the ceiling only ever covered the empty window after that. With a file
     *  there is no EOF event at all, so the same (non-)guarantee is now written
     *  honestly as an unbounded wait. `HelperResult.timedOut` is consequently
     *  never set from here; the field stays because HelperResult is shared, and
     *  because a caller reading it as "false" is correct. The known gap it
     *  papered over is unchanged and still stated: a helper that hangs silently
     *  is waited on forever -- indistinguishable from a human who never answers
     *  the auth prompt, which is also unbounded on a real device.
     *
     *  `progress`, when non-null, receives the helper's own stage reports as
     *  they stream in. That is the only way this path can report movement
     *  during a window that legitimately lasts minutes -- it blocks on a human
     *  at a Touch ID prompt AND on a large extract -- without inventing
     *  numbers. */
    public static HelperResult runInstallHelper(File helper, File staged, String requestor,
                                                HelperProgress progress)
            throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.add("install");
        argv.add("--apk");
        argv.add(staged.getAbsolutePath());
        argv.add("--source");
        argv.add("android");   // constant; see the class header
        if (requestor != null && !requestor.isEmpty()) {
            argv.add("--requestor-label");
            argv.add(requestor);
        }
        argv.add("--json");
        return runHelper(argv, progress);
    }

    /** Invoke the helper's single-package uninstall verb SYNCHRONOUSLY:
     *  uninstall --package <pkg> --source android --requestor-label <who>
     *  --json. Deliberately NO --keep-data (AOSP's public uninstall passes
     *  flags=0, which removes the data; DELETE_KEEP_DATA is @hide and
     *  DELETE_PACKAGES-gated), NO --dir-name (the public surface removes the
     *  package "completely from the device"), and --source android as a
     *  compile-time constant exactly as on the install path -- the helper
     *  refuses to act for that source without its LAContext device-owner
     *  check, and refuses those flags for that source outright. */
    static HelperResult runUninstallHelper(File helper, String pkg, String requestor)
            throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.add("uninstall");
        argv.add("--package");
        argv.add(pkg);
        argv.add("--source");
        argv.add("android");   // constant; see the class header
        if (requestor != null && !requestor.isEmpty()) {
            argv.add("--requestor-label");
            argv.add(requestor);
        }
        argv.add("--json");
        return runHelper(argv, null);
    }

    /** Invoke the helper's special-access verb SYNCHRONOUSLY (stage S1):
     *  special-access --op <op> --source android --json. This is Android's
     *  Settings "All files access" / "Media management" switch, shown by the
     *  helper as a CFUserNotification (a system process draws it, so this
     *  process cannot press it) and recorded by the helper alone, signed.
     *
     *  The argv carries NO identity: the helper derives the asking app from its
     *  PARENT process (our pid -> our --android-root -> the registry row whose
     *  bundle that is -> the run/<dirName>.pid lock we hold), so a guest cannot
     *  ask on behalf of another app by editing arguments. --source android is a
     *  compile-time constant, as on the install path; the helper refuses the
     *  verb for any other source and refuses --no-ui outright.
     *
     *  Exit codes: 0 allowed, 3 not allowed (Don't Allow), 4 no answer within
     *  300 s (nothing changed), 5 identity refused or store error, 2 usage.
     *  The caller must NOT act on the code alone -- SpecialAccessHandler
     *  re-reads the store, which only a genuine helper can sign. That is also
     *  why the MACAPK_INSTALLHELPER override (locateHelper) grants nothing:
     *  a redirected helper can lie about its exit code but cannot mint a
     *  signature SpecialAccessStore accepts. The wait is bounded by the
     *  helper's own 300 s dialog timeout. */
    static HelperResult runSpecialAccessHelper(File helper, String op)
            throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.add("special-access");
        argv.add("--op");
        argv.add(op);
        argv.add("--source");
        argv.add("android");   // constant; see the class header
        argv.add("--json");
        return runHelper(argv, null);
    }

    /** The one process-driving implementation behind both helper verbs; see
     *  runInstallHelper's header for the timeout semantics and the
     *  head+tail output capture rationale. */
    private static HelperResult runHelper(List<String> argv, HelperProgress progress)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        File transcript = helperTranscript(argv);
        if (transcript != null) {
            pb.redirectOutput(ProcessBuilder.Redirect.to(transcript));
        }
        NativeLog.i(TAG, "helper: " + String.join(" ", argv)
                + (transcript == null ? "" : " (transcript: " + transcript + ")"));
        Process p = pb.start();
        // HEAD + TAIL capture, not head-only. The line that carries the actual
        // failure reason is the helper's one-line --json object, and it is the
        // LAST thing the helper prints -- after pages of "[install] ..." prose
        // on a large app. The old `first 4000 chars` cap dropped it for
        // anything big (measured on TikTok's 197 MB bundle), so jsonField()
        // read nothing and the store-visible message degraded to the generic
        // "helper exit 5". Keeping the tail keeps the diagnosis.
        HelperOutput out = (transcript != null)
                ? new TranscriptTail(transcript)
                : new PipeDrain(p.getInputStream());
        try {
            // Poll rather than block. A file has no readable-EOF event to wait
            // on, so the PROCESS'S OWN EXIT is the terminator, and
            // waitFor(timeout) doubles as the sleep so this needs only one wait
            // primitive. 150 ms is far finer than the helper's seven real stage
            // boundaries, so the progress bar loses nothing.
            while (!p.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                out.pump(progress);
            }
            // The child's fd is closed by now, so one final pass drains
            // everything written between the last poll and exit -- including
            // the one-line --json object, which is the LAST thing the helper
            // prints and therefore the line most likely to land in that window.
            out.pump(progress);
        } finally {
            out.close();
        }
        String output = out.captured();
        int exit = p.exitValue();
        NativeLog.i(TAG, "helper exit " + exit
                + (output.isEmpty() ? "" : (" output: " + output.trim())));
        return new HelperResult(exit, output, false);
    }

    /** Where THIS helper invocation's stdout+stderr is written, or null when we
     *  could not make a staging dir (in which case runHelper falls back to the
     *  old pipe -- a degraded install is still better than no install).
     *
     *  Named from the verb plus a monotonic stamp, so two invocations cannot
     *  collide and a human reading the directory can tell which is which. It
     *  lives in the staging dir on purpose: ApkRetention.sweepStaging already
     *  reaps that directory after a day, so transcripts cannot accumulate, and
     *  performCommit's `finally` deletes only the .apkm artifact -- so the
     *  transcript OUTLIVES it and is the sole surviving record of why a failed
     *  install failed. */
    private static File helperTranscript(List<String> argv) {
        String verb = argv.size() > 1 ? argv.get(1) : "helper";
        StringBuilder safe = new StringBuilder(verb.length());
        for (int i = 0; i < verb.length(); ++i) {
            char c = verb.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '-' || c == '_';
            safe.append(ok ? c : '_');
        }
        try {
            return new File(stagingRoot(),
                    "helper-" + safe + "-" + System.currentTimeMillis() + ".log");
        } catch (IOException e) {
            NativeLog.w(TAG, "no staging dir for the helper transcript (" + e
                    + ") -- falling back to a pipe, which the helper does NOT"
                    + " survive if this process dies mid-install");
            return null;
        }
    }

    /** The helper's output, however we are reading it. Two implementations
     *  because the transcript file is the path that matters and the pipe is
     *  only the fallback for a machine with no writable staging dir. Both keep
     *  the identical HEAD + TAIL capture and progress parsing, which is why
     *  those live here rather than in either subclass. */
    private abstract static class HelperOutput implements java.io.Closeable {
        private final StringBuilder headBuf = new StringBuilder();
        private final java.util.ArrayDeque<String> tailBuf = new java.util.ArrayDeque<>();
        private int tailChars;

        /** Consume whatever is available right now; must not block. */
        abstract void pump(HelperProgress progress);

        final void onLine(String line, HelperProgress progress) {
            if (headBuf.length() < 4000) {
                headBuf.append(line).append('\n');
            } else {
                tailBuf.addLast(line);
                tailChars += line.length() + 1;
                while (tailChars > 4000 && !tailBuf.isEmpty()) {
                    tailChars -= tailBuf.removeFirst().length() + 1;
                }
            }
            if (progress == null) return;
            java.util.regex.Matcher m = HELPER_PROGRESS.matcher(line);
            if (!m.find()) return;
            try {
                float f = Float.parseFloat(m.group(1));
                progress.onStage(m.group(2), constrain(f, 0f, 1f));
            } catch (Throwable ignored) {
                // A malformed stage line must never be able to fail an
                // install; the worst case is a bar that does not move.
            }
        }

        String captured() {
            if (tailBuf.isEmpty()) return headBuf.toString();
            StringBuilder all = new StringBuilder(headBuf);
            all.append("...[helper output truncated]...\n");
            for (String t : tailBuf) all.append(t).append('\n');
            return all.toString();
        }
    }

    /** Follows the transcript file while the helper is still writing it.
     *  Reading a growing file needs state a `while (readLine())` loop did not:
     *  a byte offset, and a partial-line remainder because a 150 ms poll
     *  almost always lands mid-line and treating a fragment as a line would
     *  corrupt both the capture and the progress regex.
     *
     *  Not thread-safe and does not need to be: one install runs at a time
     *  (see EXEC) and only that thread pumps. */
    private static final class TranscriptTail extends HelperOutput {
        private final java.io.RandomAccessFile raf;
        private StringBuilder partial = new StringBuilder();

        TranscriptTail(File f) {
            java.io.RandomAccessFile r = null;
            try {
                r = new java.io.RandomAccessFile(f, "r");
            } catch (java.io.FileNotFoundException e) {
                // Redirect.to() creates the file before exec, so this is
                // effectively unreachable -- but a missing transcript must
                // degrade to "no captured output", never fail the install.
                NativeLog.w(TAG, "no helper transcript at " + f + " (" + e + ")");
            }
            this.raf = r;
        }

        @Override void pump(HelperProgress progress) {
            if (raf == null) return;
            try {
                long len = raf.length();
                long pos = raf.getFilePointer();
                if (len <= pos) return;
                byte[] buf = new byte[(int) Math.min(len - pos, 1 << 20)];
                int n = raf.read(buf);
                if (n <= 0) return;
                partial.append(new String(buf, 0, n,
                        java.nio.charset.StandardCharsets.UTF_8));
                int nl;
                while ((nl = partial.indexOf("\n")) >= 0) {
                    String line = partial.substring(0, nl);
                    partial.delete(0, nl + 1);
                    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                        line = line.substring(0, line.length() - 1);
                    }
                    onLine(line, progress);
                }
            } catch (java.io.IOException e) {
                NativeLog.w(TAG, "helper transcript read failed: " + e);
            }
        }

        /** A helper that exits without a trailing newline still gets its last
         *  line counted -- and on the failure path that line is the --json
         *  object jsonField() reads the reason out of. */
        @Override String captured() {
            if (partial.length() > 0) {
                onLine(partial.toString(), null);
                partial = new StringBuilder();
            }
            return super.captured();
        }

        @Override public void close() {
            if (raf == null) return;
            try { raf.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /** The pre-existing pipe reader, kept ONLY as the no-staging-dir fallback.
     *  Its defining weakness is the reason the transcript exists: this pipe's
     *  only reader is this JVM, so if we die the helper takes SIGPIPE on its
     *  next write and the install dies with us. `available()` keeps pump()
     *  non-blocking so the caller's exit poll stays responsive. */
    private static final class PipeDrain extends HelperOutput {
        private final java.io.InputStream in;
        private StringBuilder partial = new StringBuilder();
        PipeDrain(java.io.InputStream in) { this.in = in; }

        @Override void pump(HelperProgress progress) {
            try {
                int avail = in.available();
                while (avail > 0) {
                    byte[] buf = new byte[Math.min(avail, 1 << 20)];
                    int n = in.read(buf);
                    if (n <= 0) break;
                    partial.append(new String(buf, 0, n,
                            java.nio.charset.StandardCharsets.UTF_8));
                    int nl;
                    while ((nl = partial.indexOf("\n")) >= 0) {
                        String line = partial.substring(0, nl);
                        partial.delete(0, nl + 1);
                        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                            line = line.substring(0, line.length() - 1);
                        }
                        onLine(line, progress);
                    }
                    avail = in.available();
                }
            } catch (java.io.IOException e) {
                NativeLog.w(TAG, "helper pipe read failed: " + e);
            }
        }

        @Override String captured() {
            if (partial.length() > 0) {
                onLine(partial.toString(), null);
                partial = new StringBuilder();
            }
            return super.captured();
        }

        @Override public void close() {
            try { in.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /** " -- [stage] message", from the helper's one-line --json object, or ""
     *  when it printed none. The suffix a status message carries so the
     *  store's error dialog names the actual failure instead of a bare exit
     *  code. */
    static String helperReason(HelperResult r) {
        String stage = r.jsonField("stage");
        String why = r.jsonField("message");
        if ((why == null || why.isEmpty()) && (stage == null || stage.isEmpty())) return "";
        StringBuilder sb = new StringBuilder(" -- ");
        if (stage != null && !stage.isEmpty()) sb.append('[').append(stage).append("] ");
        if (why != null && !why.isEmpty()) sb.append(why);
        return sb.toString();
    }

    /** Who is asking, for the helper's auth prompt. The requesting app is the
     *  one hosted in this process; its package name is honest and cannot be
     *  spoofed upward (the guest could lie about a LABEL, which is why the
     *  helper treats the label as display-only text). PUBLIC so the intent
     *  surface names the requestor the same way this one does. */
    public static String requestorLabel() {
        try {
            android.app.Application app = android.app.Application.getInstance();
            if (app != null) {
                String pkg = app.getPackageName();
                if (pkg != null && !pkg.isEmpty()) return pkg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---- small utilities -------------------------------------------------

    private static void postToMain(Runnable r) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable t) {
            // Main looper not up yet (early boot); run inline rather than drop.
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursively(k);
        }
        if (f.exists() && !f.delete()) {
            NativeLog.w(TAG, "could not delete " + f);
        }
    }
}
