// android.content.pm.PackageInstaller -- system service for installing /
// uninstalling APKs at runtime. AOSP API-21+.
//
// REAL since v1.0.956: sessions stage bytes into per-session files, and
// commit() hands the staged APK (or a base+splits bundle) to the macOS
// install helper, then delivers the AOSP status Intent through the caller's
// IntentSender. The heavy lifting lives in
// com.macapk.loader.PackageInstallerBackend; this class is the AOSP-shaped
// front door. Motivating caller: Aurora Store, whose DEFAULT installer is
// this session API (SessionInstaller.kt) -- with the old no-op stub its
// install UI hung forever because commit() never reported anything.
//
// The one deliberate deviation from a device: the install itself must pass
// the helper's own consent + device-owner auth gate (--source android runs a
// real LAContext check). We therefore deliver a TERMINAL status directly and
// never STATUS_PENDING_USER_ACTION: the user action happens natively inside
// the helper, not via an EXTRA_INTENT confirm activity. Store receivers
// handle a direct terminal status unconditionally (verified in both F-Droid's
// SessionInstallManager bytecode and Aurora's BaseInstallerStatusReceiver
// source, 2026-08-12).
//
// uninstall() is REAL since v1.1.0 (owner directive 2026-08-19, "should be
// gated by touch id tho"): it rides the same helper machinery as install.
// The helper's uninstall verb now carries the SAME LAContext device-owner
// gate as --source android installs, and its prompt NAMES the target app
// (label + package, resolved from the helper's own registry -- never from
// guest-supplied text) as well as the requestor, because that prompt is the
// human's confirmation, playing the role of AOSP's system-owned
// UninstallerActivity dialog. The runtime never deletes anything itself; it
// asks, and reports what the helper proved. See
// com.macapk.loader.PackageInstallerBackend.performUninstall.
package android.content.pm;

import java.util.ArrayList;
import java.util.List;

import com.macapk.loader.PackageInstallerBackend;

public class PackageInstaller {
    // Constants verbatim from AOSP android14-release
    // core/java/android/content/pm/PackageInstaller.java. The string VALUES are
    // the contract: session-status Intents carry them as extra keys, and both
    // the system side (commit() building the fillIn Intent, mirroring
    // PackageInstallerSession.sendOnPackageInstalled) and app-side receivers
    // read them by value. EXTRA_LEGACY_STATUS is @hide upstream but IS
    // delivered in every status Intent and real installer apps read it.
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";
    public static final String EXTRA_STATUS     = "android.content.pm.extra.STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";
    public static final String EXTRA_PACKAGE_NAME = "android.content.pm.extra.PACKAGE_NAME";
    public static final String EXTRA_OTHER_PACKAGE_NAME = "android.content.pm.extra.OTHER_PACKAGE_NAME";
    public static final String EXTRA_STORAGE_PATH = "android.content.pm.extra.STORAGE_PATH";
    public static final String EXTRA_PRE_APPROVAL = "android.content.pm.extra.PRE_APPROVAL";
    public static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";

    public static final int STATUS_PENDING_USER_ACTION = -1;
    public static final int STATUS_SUCCESS              = 0;
    public static final int STATUS_FAILURE              = 1;
    public static final int STATUS_FAILURE_BLOCKED      = 2;
    public static final int STATUS_FAILURE_ABORTED      = 3;
    public static final int STATUS_FAILURE_INVALID      = 4;
    public static final int STATUS_FAILURE_CONFLICT     = 5;
    public static final int STATUS_FAILURE_STORAGE      = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    public static final int STATUS_FAILURE_TIMEOUT      = 8;

    /** AOSP PackageInstaller broadcast actions and extras absent until v1.1.0.
     *  javac inlines a String constant into the caller, so an app compiled
     *  against the real SDK cannot observe a missing one -- but our own code,
     *  a reflective reader, and anything comparing a received Intent's action
     *  against the constant all can. Values verbatim from the API-36 signature
     *  file. */
    public static final String ACTION_SESSION_COMMITTED =
            "android.content.pm.action.SESSION_COMMITTED";
    public static final String ACTION_SESSION_UPDATED =
            "android.content.pm.action.SESSION_UPDATED";
    public static final String ACTION_SESSION_DETAILS =
            "android.content.pm.action.SESSION_DETAILS";
    public static final String EXTRA_SESSION = "android.content.pm.extra.SESSION";
    public static final String EXTRA_INSTALL_CONSTRAINTS =
            "android.content.pm.extra.INSTALL_CONSTRAINTS";
    public static final String EXTRA_INSTALL_CONSTRAINTS_RESULT =
            "android.content.pm.extra.INSTALL_CONSTRAINTS_RESULT";
    public static final String EXTRA_UNARCHIVE_PACKAGE_NAME =
            "android.content.pm.extra.UNARCHIVE_PACKAGE_NAME";
    public static final String EXTRA_UNARCHIVE_ALL_USERS =
            "android.content.pm.extra.UNARCHIVE_ALL_USERS";
    public static final String EXTRA_UNARCHIVE_ID = "android.content.pm.extra.UNARCHIVE_ID";
    public static final String EXTRA_UNARCHIVE_STATUS =
            "android.content.pm.extra.UNARCHIVE_STATUS";

    /** AOSP API-35 unarchival status codes reported through
     *  reportUnarchivalState / reportUnarchivalStatus. */
    public static final int UNARCHIVAL_OK = 0;
    public static final int UNARCHIVAL_ERROR_USER_ACTION_NEEDED = 1;
    public static final int UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE = 2;
    public static final int UNARCHIVAL_ERROR_NO_CONNECTIVITY = 3;
    public static final int UNARCHIVAL_ERROR_INSTALLER_DISABLED = 4;
    public static final int UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED = 5;
    public static final int UNARCHIVAL_GENERIC_ERROR = 100;

    /** AOSP: SessionParams.PACKAGE_SOURCE_* also surface on the outer class
     *  via static import in app code; the canonical constants live on
     *  SessionParams below, and these mirrors match apps that reference them
     *  through PackageInstaller directly (Aurora imports
     *  PackageInstaller.PACKAGE_SOURCE_STORE). */
    public static final int PACKAGE_SOURCE_UNSPECIFIED     = 0;
    public static final int PACKAGE_SOURCE_OTHER           = 1;
    public static final int PACKAGE_SOURCE_STORE           = 2;
    public static final int PACKAGE_SOURCE_LOCAL_FILE      = 3;
    public static final int PACKAGE_SOURCE_DOWNLOADED_FILE = 4;

    /** AOSP: "Returns the Id of a newly created session ... @throws IOException
     *  if parameters were unsatisfiable, such as lack of disk space". */
    public int createSession(SessionParams params) throws java.io.IOException {
        String pkg = params != null ? params.appPackageName : null;
        long size = params != null ? params.sizeBytes : -1;
        String label = (params != null && params.appLabel != null)
                ? params.appLabel.toString() : null;
        int id = PackageInstallerBackend.create(pkg, size, label).id;
        // Record the rest of the SessionParams so getSessionInfo() can echo
        // them back; see SessionMeta for why this half lives here.
        SessionMeta.record(id, params);
        return id;
    }

    /** AOSP: open a session for reading/writing. Every open of the same id is
     *  a view over the SAME session state -- Aurora opens once to write the
     *  splits and AGAIN later to commit, and the second open must see the
     *  first open's blobs. @throws SecurityException when the session is
     *  invalid (AOSP's wording; abandoned and finished sessions read as
     *  invalid). */
    public Session openSession(int sessionId) throws java.io.IOException {
        PackageInstallerBackend.SessionState s = PackageInstallerBackend.get(sessionId);
        if (s == null) {
            throw new SecurityException("Session " + sessionId + " does not exist");
        }
        return new Session(s);
    }

    public void abandonSession(int sessionId) {
        PackageInstallerBackend.SessionState s = PackageInstallerBackend.get(sessionId);
        if (s != null) s.abandon();
    }

    public List<SessionInfo> getAllSessions() { return liveInfos(); }
    public List<SessionInfo> getMySessions() { return liveInfos(); }

    /** AOSP: staged (reboot-applied / APEX) sessions. Mac.apk sessions are
     *  never the staged kind, so the empty answer is the honest one -- the
     *  same a device gives when nothing is staged. */
    public List<SessionInfo> getStagedSessions() { return new ArrayList<>(); }
    public List<SessionInfo> getActiveStagedSessions() { return new ArrayList<>(); }
    public SessionInfo getActiveStagedSession() { return null; }

    /** AOSP: details for a session, "or null if the session does not exist".
     *  Aurora null-checks this immediately before commit and silently drops
     *  the install when it reads null, so live sessions MUST answer. */
    public SessionInfo getSessionInfo(int sessionId) {
        PackageInstallerBackend.SessionState s = PackageInstallerBackend.get(sessionId);
        if (s == null) return null;
        return infoFor(s);
    }

    private static SessionInfo infoFor(PackageInstallerBackend.SessionState s) {
        SessionInfo info = new SessionInfo();
        info.sessionId = s.id;
        info.appPackageName = s.appPackageName;
        info.appLabel = s.appLabel;
        info.progress = s.progress();
        info.active = !s.isDestroyed();
        info.sealed = s.isSealed();
        info.meta = SessionMeta.get(s.id);
        // updateSessionAppLabel/Icon override what the params carried, matching
        // AOSP (the update replaces the badging for every later reader).
        if (info.meta != null) {
            if (info.meta.appLabel != null) info.appLabel = info.meta.appLabel;
        }
        return info;
    }

    private static List<SessionInfo> liveInfos() {
        List<SessionInfo> out = new ArrayList<>();
        for (PackageInstallerBackend.SessionState s : PackageInstallerBackend.live()) {
            out.add(infoFor(s));
        }
        return out;
    }

    /** AOSP has a (callback) and a (callback, Handler) overload; both observe
     *  every session in the process. Dispatch is posted to the main thread
     *  (the 1-arg form's documented behavior; the Handler variant's executor
     *  choice is not modelled). */
    public void registerSessionCallback(SessionCallback callback) {
        PackageInstallerBackend.registerCallback(callback);
    }
    public void registerSessionCallback(SessionCallback callback, android.os.Handler handler) {
        PackageInstallerBackend.registerCallback(callback);
    }
    public void unregisterSessionCallback(SessionCallback callback) {
        PackageInstallerBackend.unregisterCallback(callback);
    }

    // AOSP: every real uninstall() overload takes android.content.IntentSender,
    // never android.app.PendingIntent (confirmed by grepping the entire
    // upstream PackageInstaller.java for PendingIntent -- no such overload
    // exists there). A PendingIntent-typed parameter here has a different
    // method descriptor than what real app bytecode (compiled against the
    // actual SDK) calls, so it resolves to NoSuchMethodError -- functionally
    // identical to the method being absent.
    /** AOSP android14-release PackageInstaller.java:838 -- "Uninstall the
     *  given package, removing it completely from the device", delegating to
     *  uninstall(new VersionedPackage(packageName, VERSION_CODE_HIGHEST),
     *  statusReceiver). @RequiresPermission(anyOf = {DELETE_PACKAGES,
     *  REQUEST_DELETE_PACKAGES}) upstream; the enforcement (SecurityException
     *  for a targetSdk >= P caller that declares neither) lives in the
     *  backend, mirroring PackageInstallerService.uninstall:1263.
     *
     *  The outcome arrives through statusReceiver as an Intent carrying
     *  EXTRA_STATUS / EXTRA_PACKAGE_NAME / EXTRA_STATUS_MESSAGE /
     *  EXTRA_LEGACY_STATUS -- and no EXTRA_SESSION_ID, matching AOSP's
     *  PackageDeleteObserverAdapter fill-in. A terminal status is always
     *  delivered, never STATUS_PENDING_USER_ACTION: the user action happens
     *  natively at the helper's device-owner prompt, exactly as on the
     *  install side (see the class header). */
    public void uninstall(String packageName, android.content.IntentSender statusReceiver) {
        uninstall(new VersionedPackage(packageName,
                VersionedPackage.VERSION_CODE_HIGHEST), statusReceiver);
    }

    /** AOSP PackageInstaller.java:885: "If the version code of the package
     *  does not match the one passed in the versioned package argument this
     *  method is a no-op" -- concretely, DeletePackageHelper.java:163-167
     *  reports DELETE_FAILED_INTERNAL_ERROR through the observer, which is
     *  what the backend mirrors. */
    public void uninstall(VersionedPackage versionedPackage,
                          android.content.IntentSender statusReceiver) {
        String pkg = versionedPackage != null ? versionedPackage.getPackageName() : null;
        long vc = versionedPackage != null ? versionedPackage.getLongVersionCode()
                                           : VersionedPackage.VERSION_CODE_HIGHEST;
        PackageInstallerBackend.uninstall(pkg, vc, statusReceiver);
    }

    /** AOSP PackageInstaller.uninstall(VersionedPackage, int, IntentSender)
     *  (API 34): the flags-carrying form. The only public flag is
     *  PackageManager.DELETE_KEEP_DATA, which asks to remove the code but keep
     *  the app's data directory.
     *
     *  ⚠ Mac.apk's uninstall path is the macOS install helper (see the class
     *  header), and the helper removes the installed app wholesale; it has no
     *  keep-the-data verb. So a caller passing DELETE_KEEP_DATA would be told
     *  "done" while the data went away -- the exact shape of lie this project
     *  forbids. AOSP's own behaviour for an uninstall the system refuses is a
     *  FAILURE status delivered through statusReceiver, so that is what we
     *  deliver, with a message naming the unsupported flag. flags==0 is the
     *  ordinary uninstall and rides the normal path. */
    public void uninstall(VersionedPackage versionedPackage, int flags,
                          android.content.IntentSender statusReceiver) {
        if (flags != 0) {
            deliverFailure(versionedPackage != null ? versionedPackage.getPackageName() : null,
                    statusReceiver,
                    "uninstall flags 0x" + Integer.toHexString(flags)
                            + " are not supported on this device");
            return;
        }
        uninstall(versionedPackage, statusReceiver);
    }

    /** AOSP PackageInstaller.uninstallExistingPackage(String, IntentSender)
     *  (API 29): remove the package FOR THE CALLING USER only, leaving it
     *  installed for other users. Mac.apk has exactly one user, so "uninstall
     *  for this user" and "uninstall" are the same operation -- which is also
     *  what AOSP does on a single-user device, where the last user's removal
     *  deletes the package. Routed to the same helper-gated path so the human
     *  still sees the confirmation prompt. */
    public void uninstallExistingPackage(String packageName,
                                         android.content.IntentSender statusReceiver) {
        uninstall(packageName, statusReceiver);
    }

    /** AOSP PackageInstaller.installExistingPackage(String, int, IntentSender)
     *  (API 29): make an ALREADY-INSTALLED-FOR-ANOTHER-USER package visible to
     *  the calling user, without re-staging any bytes.
     *
     *  There is no such package on Mac.apk: one user, and a package is either
     *  installed for it or not installed at all. AOSP reports a package it
     *  cannot find through the status receiver rather than throwing, so this
     *  delivers STATUS_FAILURE_INVALID with that reason. Inventing a success
     *  would tell the caller an app is now available that is not. */
    public void installExistingPackage(String packageName, int installReason,
                                       android.content.IntentSender statusReceiver) {
        deliverFailure(packageName, statusReceiver,
                "Package " + packageName + " is not installed for any other user");
    }

    /** AOSP PackageInstaller.updateSessionAppIcon(int, Bitmap) /
     *  updateSessionAppLabel(int, CharSequence) (API 21): update the badging a
     *  session shows while it installs. Both are recorded here so
     *  getSessionInfo() reports what the installer set -- an installer that
     *  writes a label and reads it back must see its own value. */
    public void updateSessionAppIcon(int sessionId, android.graphics.Bitmap appIcon) {
        SessionMeta meta = SessionMeta.get(sessionId);
        if (meta != null) { meta.appIcon = appIcon; meta.touch(); }
    }
    public void updateSessionAppLabel(int sessionId, CharSequence appLabel) {
        SessionMeta meta = SessionMeta.get(sessionId);
        if (meta != null) {
            meta.appLabel = (appLabel != null) ? appLabel.toString() : null;
            meta.touch();
        }
    }

    /** AOSP PackageInstaller.checkInstallConstraints(List, InstallConstraints,
     *  Executor, Consumer) (API 34): ask, right now, whether the given
     *  constraints hold for the named packages.
     *
     *  Every constraint AOSP defines is about state Mac.apk does not have: a
     *  device idle window, a phone call in progress, another app in the
     *  foreground or interacting. There is one app and no telephony, so each
     *  constraint is vacuously satisfied -- the same answer a device gives when
     *  the phone is idle and the target app is not running. Delivered on the
     *  caller's Executor, as the contract requires. */
    public void checkInstallConstraints(java.util.List<String> packageNames,
            InstallConstraints constraints, java.util.concurrent.Executor executor,
            java.util.function.Consumer<InstallConstraintsResult> callback) {
        if (packageNames == null || constraints == null || executor == null
                || callback == null) {
            throw new NullPointerException("checkInstallConstraints requires non-null arguments");
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                callback.accept(new InstallConstraintsResult(true));
            }
        });
    }

    /** AOSP PackageInstaller.waitForInstallConstraints(List, InstallConstraints,
     *  IntentSender, long) (API 34): the asynchronous form -- wait up to
     *  timeoutMillis for the constraints to hold, then report through the
     *  IntentSender. They already hold here (see checkInstallConstraints), so
     *  the result is delivered immediately, which is the same observable
     *  sequence a device produces when the constraints are met on the first
     *  check. */
    public void waitForInstallConstraints(java.util.List<String> packageNames,
            InstallConstraints constraints, android.content.IntentSender statusReceiver,
            long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > java.util.concurrent.TimeUnit.DAYS.toMillis(7)) {
            // AOSP: "@throws IllegalArgumentException if the timeout is out of range."
            throw new IllegalArgumentException("timeoutMillis out of range: " + timeoutMillis);
        }
        deliverConstraintsSatisfied(statusReceiver);
    }

    // ---- App archiving (API 35) -------------------------------------------
    //
    // ⚠ MAC.APK DOES NOT IMPLEMENT ARCHIVING, and these four methods say so
    // rather than pretending. Archiving is "delete the APK, keep the icon and
    // the user data, restore on tap"; it needs an installer that implements an
    // unarchival flow, and there is no macOS analogue and nothing in this
    // runtime that could restore an archived app. PackageManager
    // .getArchivedPackage() and PackageManager.isAppArchivable() already answer
    // "nothing is archived / nothing can be archived" consistently.
    //
    // The AOSP-documented failure for each is used verbatim, because each is a
    // failure a real device produces and every caller therefore handles.

    /** AOSP: "Requests to archive a package... @throws NameNotFoundException if
     *  the package is not installed." AOSP additionally requires the package to
     *  have an installer that supports unarchival and reports that failure
     *  through the IntentSender. Here NO package is archivable, so an installed
     *  package gets that failure status and an unknown one gets the
     *  exception -- the two branches a device has. */
    public void requestArchive(String packageName, android.content.IntentSender statusReceiver)
            throws PackageManager.NameNotFoundException {
        requireInstalled(packageName);
        deliverFailure(packageName, statusReceiver,
                "No installer supports unarchival for " + packageName);
    }

    /** AOSP: "Requests to unarchive a currently archived package." Nothing is
     *  archived here, so this always fails with the not-archived reason. */
    public void requestUnarchive(String packageName, android.content.IntentSender statusReceiver)
            throws java.io.IOException, PackageManager.NameNotFoundException {
        requireInstalled(packageName);
        deliverFailure(packageName, statusReceiver,
                "Package " + packageName + " is not archived");
    }

    /** AOSP: "Reports the result of an unarchival to the system", called by an
     *  INSTALLER that was asked to unarchive. Mac.apk never sends an
     *  ACTION_UNARCHIVE_PACKAGE broadcast, so no unarchive id can be
     *  outstanding; AOSP throws NameNotFoundException for an unarchive id it
     *  does not know, which is every id here. */
    public void reportUnarchivalState(UnarchivalState unarchivalState)
            throws PackageManager.NameNotFoundException {
        throw new PackageManager.NameNotFoundException(
                "No outstanding unarchival for id "
                        + (unarchivalState != null ? unarchivalState.getUnarchiveId() : -1));
    }

    /** AOSP: the pre-API-35 spelling of reportUnarchivalState, deprecated
     *  upstream and delegating to the same check. */
    public void reportUnarchivalStatus(int unarchiveId, int status, long requiredStorageBytes,
            android.app.PendingIntent userActionIntent)
            throws PackageManager.NameNotFoundException {
        throw new PackageManager.NameNotFoundException(
                "No outstanding unarchival for id " + unarchiveId);
    }

    /** AOSP PackageInstaller.installPackageArchived(ArchivedPackageInfo,
     *  SessionParams, IntentSender) (API 35): install a package in the ARCHIVED
     *  state -- icon and metadata only, no APK. Mac.apk cannot represent an
     *  archived install (there is nothing to unarchive it with later), so the
     *  request fails through the status receiver rather than producing an entry
     *  that could never be launched. */
    public void installPackageArchived(ArchivedPackageInfo archivedPackageInfo,
            SessionParams params, android.content.IntentSender statusReceiver) {
        String pkg = (archivedPackageInfo != null) ? archivedPackageInfo.getPackageName() : null;
        deliverFailure(pkg, statusReceiver,
                "Archived installs are not supported on this device");
    }

    // ---- status delivery + per-session metadata ---------------------------
    //
    // deliverStatus below MIRRORS com.macapk.loader.PackageInstallerBackend
    // .deliverStatus, which is private to that class: same extras, same order,
    // same "always terminal, never STATUS_PENDING_USER_ACTION" stance (see the
    // class header). It is duplicated rather than shared because the backend
    // lives in another package with no public entry point for it; if that
    // method is ever made public, delete this one and call it, so the two
    // cannot drift.

    /** AOSP INSTALL_FAILED_INTERNAL_ERROR (PackageManager.java:1128). The
     *  EXTRA_LEGACY_STATUS half of a failure; AOSP's own
     *  installStatusToPublicStatus maps it to STATUS_FAILURE, so this pair is
     *  self-consistent rather than invented. */
    private static final int LEGACY_INSTALL_FAILED_INTERNAL_ERROR = -110;

    private static void deliverFailure(String pkg, android.content.IntentSender statusReceiver,
                                       String message) {
        deliverStatus(statusReceiver, SessionInfo.INVALID_ID, pkg,
                STATUS_FAILURE, LEGACY_INSTALL_FAILED_INTERNAL_ERROR, message);
    }

    /** waitForInstallConstraints' success shape: AOSP fills the sender with
     *  EXTRA_PACKAGES, EXTRA_INSTALL_CONSTRAINTS_RESULT and a STATUS_SUCCESS. */
    private static void deliverConstraintsSatisfied(android.content.IntentSender statusReceiver) {
        if (statusReceiver == null) return;
        android.content.Intent fillIn = new android.content.Intent();
        fillIn.putExtra(EXTRA_STATUS, STATUS_SUCCESS);
        fillIn.putExtra(EXTRA_INSTALL_CONSTRAINTS_RESULT, new InstallConstraintsResult(true));
        sendQuietly(statusReceiver, fillIn);
    }

    private static void deliverStatus(android.content.IntentSender sender, int sessionId,
            String pkg, int status, int legacyStatus, String message) {
        if (sender == null) return;
        android.content.Intent fillIn = new android.content.Intent();
        fillIn.putExtra(EXTRA_PACKAGE_NAME, pkg);
        fillIn.putExtra(EXTRA_SESSION_ID, sessionId);
        fillIn.putExtra(EXTRA_STATUS, status);
        fillIn.putExtra(EXTRA_STATUS_MESSAGE, message);
        fillIn.putExtra(EXTRA_LEGACY_STATUS, legacyStatus);
        fillIn.putExtra(EXTRA_PRE_APPROVAL, false);
        sendQuietly(sender, fillIn);
    }

    private static void sendQuietly(android.content.IntentSender sender,
                                    android.content.Intent fillIn) {
        try {
            sender.sendIntent(android.app.Application.macapk_get(), 0, fillIn, null, null);
        } catch (Throwable ignored) {
            // AOSP swallows a dead IntentSender the same way (the caller is
            // gone; there is nobody left to tell).
        }
    }

    /** The SessionParams state a session was created with, plus the timestamps
     *  and parent/child links AOSP's SessionInfo reports.
     *
     *  WHY IT LIVES HERE and not in PackageInstallerBackend.SessionState: that
     *  class models the STAGING half (blobs, progress, seal/abandon) and is
     *  owned by the loader package. Everything below is pure SessionParams
     *  echo -- what the installer asked for, handed straight back through
     *  getSessionInfo() -- so it belongs with the class that received the
     *  params. Keyed by session id, process-wide, exactly like the backend's
     *  own registry, so both halves of a session survive an openSession from a
     *  different call site. */
    static final class SessionMeta {
        private static final java.util.Map<Integer, SessionMeta> MAP =
                java.util.Collections.synchronizedMap(new java.util.HashMap<Integer, SessionMeta>());

        int mode = SessionParams.MODE_FULL_INSTALL;
        int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        int installReason;
        int installScenario = PackageManager.INSTALL_SCENARIO_DEFAULT;
        int originatingUid = -1;
        int requireUserAction = SessionParams.USER_ACTION_UNSPECIFIED;
        int packageSource = SessionParams.PACKAGE_SOURCE_UNSPECIFIED;
        int unarchiveId = -1;
        long sizeBytes = -1;
        String installerPackageName;
        String installerAttributionTag;
        String appLabel;
        android.graphics.Bitmap appIcon;
        boolean multiPackage;
        boolean requestUpdateOwnership;
        boolean applicationEnabledSettingPersistent;
        boolean dontKillApp;
        boolean autoInstallDependenciesEnabled;
        boolean preApprovalRequested;
        boolean committed;
        long createdMillis;
        long updatedMillis;
        int parentSessionId = SessionInfo.INVALID_ID;
        int[] childSessionIds = new int[0];
        android.os.PersistableBundle appMetadata;

        static SessionMeta get(int sessionId) { return MAP.get(sessionId); }

        static SessionMeta record(int sessionId, SessionParams params) {
            SessionMeta m = new SessionMeta();
            if (params != null) {
                m.mode = params.mode;
                m.installLocation = params.installLocation;
                m.installReason = params.installReason;
                m.installScenario = params.installScenario;
                m.originatingUid = params.originatingUid;
                m.requireUserAction = params.requireUserAction;
                m.packageSource = params.packageSource;
                m.unarchiveId = params.unarchiveId;
                m.sizeBytes = params.sizeBytes;
                m.installerPackageName = params.installerPackageName;
                m.appIcon = params.appIcon;
                m.appLabel = (params.appLabel != null) ? params.appLabel.toString() : null;
                m.multiPackage = params.multiPackage;
                m.requestUpdateOwnership = params.requestUpdateOwnership;
                m.applicationEnabledSettingPersistent = params.applicationEnabledSettingPersistent;
                m.dontKillApp = params.dontKillApp;
                m.autoInstallDependenciesEnabled = params.autoInstallDependenciesEnabled;
            }
            m.createdMillis = System.currentTimeMillis();
            m.updatedMillis = m.createdMillis;
            MAP.put(sessionId, m);
            return m;
        }

        void touch() { updatedMillis = System.currentTimeMillis(); }
    }

    /** AOSP PackageInstaller.InstallConstraintsResult (API 34): the answer
     *  checkInstallConstraints delivers. */
    public static final class InstallConstraintsResult implements android.os.Parcelable {
        private final boolean mAllConstraintsSatisfied;

        public InstallConstraintsResult(boolean allConstraintsSatisfied) {
            mAllConstraintsSatisfied = allConstraintsSatisfied;
        }

        public boolean areAllConstraintsSatisfied() { return mAllConstraintsSatisfied; }

        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeBoolean(mAllConstraintsSatisfied);
        }
        public static final android.os.Parcelable.Creator<InstallConstraintsResult> CREATOR =
                new android.os.Parcelable.Creator<InstallConstraintsResult>() {
            @Override public InstallConstraintsResult createFromParcel(android.os.Parcel in) {
                return new InstallConstraintsResult(in.readBoolean());
            }
            @Override public InstallConstraintsResult[] newArray(int size) {
                return new InstallConstraintsResult[size];
            }
        };
    }

    /** AOSP PackageInstaller.PreapprovalDetails (API 34): the label, icon and
     *  locale an installer shows the user when it asks for install approval
     *  BEFORE downloading the APK. Built by the installer, read by the system
     *  UI. Mirrors upstream field for field. */
    public static final class PreapprovalDetails implements android.os.Parcelable {
        private final android.graphics.Bitmap mIcon;
        private final CharSequence mLabel;
        private final android.icu.util.ULocale mLocale;
        private final String mPackageName;

        private PreapprovalDetails(android.graphics.Bitmap icon, CharSequence label,
                android.icu.util.ULocale locale, String packageName) {
            mIcon = icon; mLabel = label; mLocale = locale; mPackageName = packageName;
        }

        public android.graphics.Bitmap getIcon() { return mIcon; }
        public CharSequence getLabel() { return mLabel; }
        public android.icu.util.ULocale getLocale() { return mLocale; }
        public String getPackageName() { return mPackageName; }

        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            // AOSP writes a present-flag for the icon, then label, locale tag
            // and package name. The Bitmap is not marshalled here -- this
            // tree's Parcel has no bitmap transport and nothing sends a
            // PreapprovalDetails across a process boundary -- so it reads back
            // null, which getIcon() already documents as a legal value.
            dest.writeCharSequence(mLabel);
            // ULocale has no toLanguageTag() in this tree; getName() is ICU's
            // locale id, which its String constructor round-trips.
            dest.writeString(mLocale != null ? mLocale.getName() : null);
            dest.writeString(mPackageName);
        }
        public static final android.os.Parcelable.Creator<PreapprovalDetails> CREATOR =
                new android.os.Parcelable.Creator<PreapprovalDetails>() {
            @Override public PreapprovalDetails createFromParcel(android.os.Parcel in) {
                CharSequence label = in.readCharSequence();
                String tag = in.readString();
                String pkg = in.readString();
                return new PreapprovalDetails(null, label,
                        tag != null ? new android.icu.util.ULocale(tag) : null, pkg);
            }
            @Override public PreapprovalDetails[] newArray(int size) {
                return new PreapprovalDetails[size];
            }
        };

        /** AOSP PreapprovalDetails.Builder. Upstream's build() throws
         *  IllegalArgumentException when label, locale or packageName is
         *  missing -- reproduced, because an installer that forgets one gets a
         *  clear failure on a device rather than a half-built request. */
        public static final class Builder {
            private android.graphics.Bitmap mIcon;
            private CharSequence mLabel;
            private android.icu.util.ULocale mLocale;
            private String mPackageName;

            public Builder() {}
            public Builder setIcon(android.graphics.Bitmap icon) { mIcon = icon; return this; }
            public Builder setLabel(CharSequence label) { mLabel = label; return this; }
            public Builder setLocale(android.icu.util.ULocale locale) {
                mLocale = locale; return this;
            }
            public Builder setPackageName(String packageName) {
                mPackageName = packageName; return this;
            }
            public PreapprovalDetails build() {
                if (mLabel == null) {
                    throw new IllegalArgumentException("Package label is required");
                }
                if (mLocale == null) {
                    throw new IllegalArgumentException("Label locale is required");
                }
                if (mPackageName == null) {
                    throw new IllegalArgumentException("Package name is required");
                }
                return new PreapprovalDetails(mIcon, mLabel, mLocale, mPackageName);
            }
        }
    }

    /** AOSP PackageInstaller.UnarchivalState (API 35): the outcome an installer
     *  reports back after being asked to unarchive a package. Five static
     *  factories, no public constructor -- upstream's shape exactly. */
    public static final class UnarchivalState {
        private final int mStatus;
        private final int mUnarchiveId;
        private final long mRequiredStorageBytes;
        private final android.app.PendingIntent mUserActionIntent;

        private UnarchivalState(int status, int unarchiveId, long requiredStorageBytes,
                android.app.PendingIntent userActionIntent) {
            mStatus = status;
            mUnarchiveId = unarchiveId;
            mRequiredStorageBytes = requiredStorageBytes;
            mUserActionIntent = userActionIntent;
        }

        public static UnarchivalState createOkState(int unarchiveId) {
            return new UnarchivalState(UNARCHIVAL_OK, unarchiveId, 0, null);
        }
        public static UnarchivalState createUserActionRequiredState(int unarchiveId,
                android.app.PendingIntent userActionIntent) {
            if (userActionIntent == null) {
                throw new IllegalArgumentException("userActionIntent is required");
            }
            return new UnarchivalState(UNARCHIVAL_ERROR_USER_ACTION_NEEDED, unarchiveId, 0,
                    userActionIntent);
        }
        public static UnarchivalState createInsufficientStorageState(int unarchiveId,
                long requiredStorageBytes, android.app.PendingIntent userActionIntent) {
            return new UnarchivalState(UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE, unarchiveId,
                    requiredStorageBytes, userActionIntent);
        }
        public static UnarchivalState createNoConnectivityState(int unarchiveId) {
            return new UnarchivalState(UNARCHIVAL_ERROR_NO_CONNECTIVITY, unarchiveId, 0, null);
        }
        public static UnarchivalState createGenericErrorState(int unarchiveId) {
            return new UnarchivalState(UNARCHIVAL_GENERIC_ERROR, unarchiveId, 0, null);
        }

        /** @hide-equivalent accessors (AOSP's are @hide too, read by the
         *  system side of reportUnarchivalState). */
        public int getStatus() { return mStatus; }
        public int getUnarchiveId() { return mUnarchiveId; }
        public long getRequiredStorageBytes() { return mRequiredStorageBytes; }
        public android.app.PendingIntent getUserActionIntent() { return mUserActionIntent; }
    }

    private static void requireInstalled(String packageName)
            throws PackageManager.NameNotFoundException {
        android.app.Application app = android.app.Application.macapk_get();
        String self = (app != null) ? app.getPackageName() : null;
        if (packageName == null || self == null || !packageName.equals(self)) {
            throw new PackageManager.NameNotFoundException(String.valueOf(packageName));
        }
    }

    public static class SessionParams implements android.os.Parcelable {
        public static final int MODE_FULL_INSTALL = 1;
        public static final int MODE_INHERIT_EXISTING = 2;
        public static final int USER_ACTION_UNSPECIFIED = 0;
        public static final int USER_ACTION_REQUIRED = 1;
        public static final int USER_ACTION_NOT_REQUIRED = 2;
        public static final int PACKAGE_SOURCE_UNSPECIFIED     = 0;
        public static final int PACKAGE_SOURCE_OTHER           = 1;
        public static final int PACKAGE_SOURCE_STORE           = 2;
        public static final int PACKAGE_SOURCE_LOCAL_FILE      = 3;
        public static final int PACKAGE_SOURCE_DOWNLOADED_FILE = 4;
        /** AOSP API-31 permission states for setPermissionState. */
        public static final int PERMISSION_STATE_DEFAULT = 0;
        public static final int PERMISSION_STATE_GRANTED = 1;
        public static final int PERMISSION_STATE_DENIED  = 2;

        /** AOSP SessionParams.RESTRICTED_PERMISSIONS_ALL
         *  (PackageInstaller.java, API 29): the sentinel Set an installer
         *  passes to setWhitelistedRestrictedPermissions to mean "allowlist
         *  every restricted permission the package requests", as opposed to a
         *  specific set.
         *
         *  ⚠ Upstream's value is `new ArraySet<>()` -- an EMPTY set used purely
         *  as an identity sentinel (the code checks `== RESTRICTED_PERMISSIONS_ALL`,
         *  not its contents). An unmodifiable empty set reproduces that: it is
         *  distinguishable by identity, and its emptiness never leaks into a
         *  contents check because AOSP never makes one. */
        public static final java.util.Set<String> RESTRICTED_PERMISSIONS_ALL =
                java.util.Collections.unmodifiableSet(new java.util.HashSet<String>());

        // AOSP keeps these as public (@hide) fields on SessionParams itself;
        // public here too so the loader-side backend can read them without a
        // reflective dance.
        public int mode = MODE_FULL_INSTALL;
        public String appPackageName;
        public long sizeBytes = -1;
        public CharSequence appLabel;
        public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        public int installReason;
        public int originatingUid = -1;   // AOSP UID_UNKNOWN
        public int requireUserAction = USER_ACTION_UNSPECIFIED;
        public int packageSource = PACKAGE_SOURCE_UNSPECIFIED;
        public String installerPackageName;
        /** AOSP SessionParams fields added in v1.1.0 so the setters below store
         *  what they were given instead of discarding it. Every one is echoed
         *  back through PackageInstaller.getSessionInfo(), which is the whole
         *  reason a session records its params on a device. */
        public int installScenario = PackageManager.INSTALL_SCENARIO_DEFAULT;
        public int unarchiveId = -1;
        public android.graphics.Bitmap appIcon;
        public boolean multiPackage;
        public boolean requestUpdateOwnership;
        public boolean applicationEnabledSettingPersistent;
        public boolean dontKillApp;
        public boolean autoInstallDependenciesEnabled;
        public boolean autoRevokePermissionsMode = true;
        /** AOSP: the restricted permissions this install should allowlist, or
         *  RESTRICTED_PERMISSIONS_ALL. Null means "the default allowlist". */
        public java.util.Set<String> whitelistedRestrictedPermissions;
        /** AOSP API-31 setPermissionState: permission name -> PERMISSION_STATE_*. */
        public final java.util.Map<String, Integer> permissionStates =
                new java.util.LinkedHashMap<>();

        public SessionParams(int mode) { this.mode = mode; }
        public void setAppPackageName(String packageName) { this.appPackageName = packageName; }
        public void setPackageSource(int packageSource) { this.packageSource = packageSource; }
        /** AOSP API-34: ask to become the package's UPDATE OWNER, so other
         *  installers are steered away from updating it.
         *
         *  Recorded, and reported back by SessionInfo.isRequestUpdateOwnership().
         *  Mac.apk grants no ownership -- InstallSourceInfo
         *  .getUpdateOwnerPackageName() stays null, because the macOS install
         *  helper enforces no such policy -- so the request is honestly
         *  remembered and honestly not acted on, which is also what a device
         *  does when the requesting installer lacks the privilege. */
        public void setRequestUpdateOwnership(boolean requestUpdateOwnership) {
            this.requestUpdateOwnership = requestUpdateOwnership;
        }

        /** AOSP API-31: the badging icon shown while this session installs. */
        public void setAppIcon(android.graphics.Bitmap appIcon) { this.appIcon = appIcon; }

        /** AOSP API-31: mark this a MULTI-PACKAGE (parent) session, whose
         *  children are added with Session.addChildSessionId. There is no
         *  un-set form upstream either. */
        public void setMultiPackage() { this.multiPackage = true; }

        /** AOSP API-31: the install scenario, an INSTALL_SCENARIO_* hint about
         *  how aggressively to optimise (dexopt) the install. Mac.apk has no
         *  dexopt scheduler to hint -- our DEX conversion is unconditional and
         *  runs once at first launch -- so the value is recorded and reported
         *  but changes nothing, which is also true of a device whose
         *  performance profile ignores the hint. */
        public void setInstallScenario(int installScenario) {
            this.installScenario = installScenario;
        }

        /** AOSP API-30: whether the platform may auto-revoke this app's
         *  permissions after a long idle period. Mac.apk never auto-revokes
         *  anything (there is no permission-usage tracker and no idle job), so
         *  the value is recorded for read-back and nothing acts on it. */
        public void setAutoRevokePermissionsMode(boolean shouldAutoRevoke) {
            this.autoRevokePermissionsMode = shouldAutoRevoke;
        }

        /** AOSP API-31: pre-set a permission to GRANTED or DENIED for this
         *  install. Recorded per permission name. Mac.apk's permission model
         *  answers from the manifest rather than from a mutable grant table, so
         *  nothing consumes this yet; it is stored rather than dropped so an
         *  installer reading its own params back sees what it set. */
        public SessionParams setPermissionState(String permissionName, int state) {
            if (permissionName == null) {
                throw new NullPointerException("permissionName cannot be null");
            }
            permissionStates.put(permissionName, state);
            return this;
        }

        /** AOSP API-35: install this app's declared dependencies (SDK
         *  libraries) automatically. Mac.apk resolves no SDK libraries --
         *  PackageManager.getSharedLibraries() is empty -- so there is nothing
         *  to pull in and the flag is recorded only. */
        public void setAutoInstallDependenciesEnabled(boolean enabled) {
            this.autoInstallDependenciesEnabled = enabled;
        }

        /** AOSP API-35: the unarchive id this install is fulfilling. Mac.apk
         *  never issues one (see the archiving block on the outer class), so a
         *  caller can set it but no unarchival is ever matched to it. */
        public void setUnarchiveId(int unarchiveId) { this.unarchiveId = unarchiveId; }
        public void setAppLabel(CharSequence label) { this.appLabel = label; }
        public void setInstallLocation(int location) { this.installLocation = location; }
        public void setReferrerUri(android.net.Uri referrer) {}
        public void setOriginatingUri(android.net.Uri originating) {}
        // @hide on AOSP; the Play Store probes it reflectively. We never install
        // instant apps, so record nothing -- a regular install is the only mode.
        public void setInstallAsInstantApp(boolean isInstantApp) {}
        /** AOSP API-24+; Aurora calls it (setOriginatingUid(Process.myUid()))
         *  on every install at our presented SDK, so it must resolve. */
        public void setOriginatingUid(int originatingUid) { this.originatingUid = originatingUid; }
        public void setSize(long sizeBytes) { this.sizeBytes = sizeBytes; }
        public void setInstallReason(int installReason) { this.installReason = installReason; }
        public void setRequireUserAction(int action) { this.requireUserAction = action; }
        /** AOSP API-34+ trio; declared so an SDK_INT override past 33 does not
         *  turn Aurora's buildSessionParams into a NoSuchMethodError. */
        public void setInstallerPackageName(String installerPackageName) {
            this.installerPackageName = installerPackageName;
        }
        public void setApplicationEnabledSettingPersistent() {
            this.applicationEnabledSettingPersistent = true;
        }
        public void setDontKillApp(boolean dontKillApp) { this.dontKillApp = dontKillApp; }
        public void setWhitelistedRestrictedPermissions(java.util.Set<String> permissions) {
            this.whitelistedRestrictedPermissions = permissions;
        }

        // AOSP's SessionParams carries ~25 fields (installFlags,
        // installScenario, appIcon, originatingUri/referrerUri, abiOverride,
        // volumeUuid, permission states, rollback policy, ...;
        // PackageInstaller.java L3678-3712), most of which this class does
        // not model (the setters above are no-ops for several of them, e.g.
        // setReferrerUri/setOriginatingUri). This writes the fields this
        // class DOES store, in the same relative order AOSP uses for them.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(mode);
            dest.writeInt(installLocation);
            dest.writeInt(installReason);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeCharSequence(appLabel);
            dest.writeInt(originatingUid);
            dest.writeString(installerPackageName);
            dest.writeInt(requireUserAction);
            dest.writeInt(packageSource);
        }

        public static final android.os.Parcelable.Creator<SessionParams> CREATOR =
                new android.os.Parcelable.Creator<SessionParams>() {
            @Override public SessionParams createFromParcel(android.os.Parcel in) {
                SessionParams p = new SessionParams(in.readInt());
                p.installLocation = in.readInt();
                p.installReason = in.readInt();
                p.sizeBytes = in.readLong();
                p.appPackageName = in.readString();
                p.appLabel = in.readCharSequence();
                p.originatingUid = in.readInt();
                p.installerPackageName = in.readString();
                p.requireUserAction = in.readInt();
                p.packageSource = in.readInt();
                return p;
            }
            @Override public SessionParams[] newArray(int size) { return new SessionParams[size]; }
        };
    }

    public static class Session implements java.io.Closeable {
        private final PackageInstallerBackend.SessionState state;

        Session(PackageInstallerBackend.SessionState state) { this.state = state; }

        /** AOSP: an OutputStream to write one APK (base or a split) into the
         *  session. `name` is the caller's logical name for the blob; offset
         *  resumes a partial write. */
        public java.io.OutputStream openWrite(String name, long offset, long lengthBytes)
                throws java.io.IOException {
            return state.openWrite(name, offset, lengthBytes);
        }
        public java.io.InputStream openRead(String name) throws java.io.IOException {
            return state.openRead(name);
        }
        public void fsync(java.io.OutputStream out) throws java.io.IOException {
            state.fsync(out);
        }
        /** AOSP: close() releases this HANDLE only; the underlying session
         *  stays alive for a later openSession (Aurora relies on exactly
         *  that). abandon()/commit() are what end the session. */
        public void close() {}
        public void abandon() {
            SessionMeta m = meta();
            if (m != null) m.touch();
            state.abandon();
        }
        /** Seal the session and start the install; returns immediately. The
         *  outcome arrives through statusReceiver as an Intent carrying
         *  EXTRA_STATUS / EXTRA_PACKAGE_NAME / EXTRA_SESSION_ID /
         *  EXTRA_STATUS_MESSAGE (see PackageInstallerBackend.deliverStatus). */
        public void commit(android.content.IntentSender statusReceiver) {
            SessionMeta m = meta();
            if (m != null) m.touch();
            state.commit(statusReceiver);
        }
        public void setStagingProgress(float progress) {
            SessionMeta m = meta();
            if (m != null) m.touch();
            state.setStagingProgress(progress);
        }
        public void addProgressListener(SessionCallback listener) {
            PackageInstallerBackend.registerCallback(listener);
        }
        public String[] getNames() { return state.getNames(); }
        public void removeSplit(String splitName) { state.removeBlob(splitName); }

        private SessionMeta meta() { return SessionMeta.get(state.id); }

        // ---- multi-package sessions (API 29) ------------------------------
        //
        // A MULTI-PACKAGE session is a parent with no bytes of its own whose
        // children are committed atomically. The parent/child links are pure
        // session bookkeeping -- no APK content is involved -- so they are
        // modelled fully and truthfully here, and an installer that builds a
        // multi-package session can read its own structure back.
        //
        // ⚠ WHAT IS NOT MODELLED, stated rather than hidden: committing the
        // parent does NOT currently commit the children atomically -- our
        // commit() hands one staged payload to the macOS install helper, and
        // the helper installs one app. An installer that relies on
        // all-or-nothing across several packages will see them installed
        // independently. The links are still recorded because reporting an
        // empty child list to an installer that just added one would be a
        // second, gratuitous divergence on top of the first.

        /** AOSP Session.addChildSessionId(int): "Adds a session ID to the set
         *  of sessions that will be committed atomically with this session."
         *  @throws IllegalStateException when this is not a multi-package
         *  session -- AOSP's own guard. */
        public void addChildSessionId(int sessionId) {
            SessionMeta m = meta();
            if (m == null || !m.multiPackage) {
                throw new IllegalStateException(
                        "Session " + state.id + " is not a multi-package session");
            }
            SessionMeta child = SessionMeta.get(sessionId);
            if (child == null) {
                throw new IllegalStateException("Session " + sessionId + " does not exist");
            }
            synchronized (m) {
                for (int existing : m.childSessionIds) {
                    if (existing == sessionId) return;
                }
                int[] grown = java.util.Arrays.copyOf(m.childSessionIds,
                        m.childSessionIds.length + 1);
                grown[grown.length - 1] = sessionId;
                m.childSessionIds = grown;
            }
            child.parentSessionId = state.id;
            m.touch();
        }

        /** AOSP Session.removeChildSessionId(int). Upstream is tolerant of an
         *  id that is not a child ("no-op if the session is not a child"). */
        public void removeChildSessionId(int sessionId) {
            SessionMeta m = meta();
            if (m == null) return;
            synchronized (m) {
                int idx = -1;
                for (int i = 0; i < m.childSessionIds.length; i++) {
                    if (m.childSessionIds[i] == sessionId) { idx = i; break; }
                }
                if (idx < 0) return;
                int[] shrunk = new int[m.childSessionIds.length - 1];
                System.arraycopy(m.childSessionIds, 0, shrunk, 0, idx);
                System.arraycopy(m.childSessionIds, idx + 1, shrunk, idx,
                        shrunk.length - idx);
                m.childSessionIds = shrunk;
            }
            SessionMeta child = SessionMeta.get(sessionId);
            if (child != null) child.parentSessionId = SessionInfo.INVALID_ID;
            m.touch();
        }

        /** AOSP Session.getChildSessionIds(): @NonNull, empty for a
         *  non-multi-package session. */
        public int[] getChildSessionIds() {
            SessionMeta m = meta();
            return (m == null) ? new int[0] : m.childSessionIds.clone();
        }

        /** AOSP Session.getParentSessionId(): the parent's id, or
         *  SessionInfo.INVALID_ID when this session has no parent. */
        public int getParentSessionId() {
            SessionMeta m = meta();
            return (m == null) ? SessionInfo.INVALID_ID : m.parentSessionId;
        }

        public boolean isMultiPackage() {
            SessionMeta m = meta();
            return m != null && m.multiPackage;
        }

        /** AOSP Session.isStaged(): a STAGED session is one applied at the next
         *  reboot (APEX / system updates). Mac.apk has no reboot-time install
         *  path and SessionParams has no setStaged, so no session here is ever
         *  staged -- the same answer a device gives for an ordinary app
         *  install. */
        public boolean isStaged() { return false; }

        public boolean isRequestUpdateOwnership() {
            SessionMeta m = meta();
            return m != null && m.requestUpdateOwnership;
        }

        public boolean isApplicationEnabledSettingPersistent() {
            SessionMeta m = meta();
            return m != null && m.applicationEnabledSettingPersistent;
        }

        /** AOSP Session.setAppMetadata(PersistableBundle) (API 34): an
         *  installer-supplied metadata bundle stored ALONGSIDE the package and
         *  readable later. Stored per session here and returned by
         *  getAppMetadata below; AOSP additionally persists it past the install
         *  so PackageManager.getAppMetadata can read it, which this tree does
         *  not model (there is no per-package metadata store), so it lives as
         *  long as the session does.
         *
         *  Upstream's SIZE CAP is enforced, because it is observable: AOSP
         *  rejects a bundle larger than the app-metadata limit (default 32000
         *  bytes, Settings.Global.APP_METADATA_SIZE_LIMIT) with an IOException,
         *  so an installer that ships a too-large bundle must see the failure
         *  here rather than at some later read. The size is measured the way
         *  AOSP measures it -- by marshalling into a Parcel -- so the number
         *  compared is the real serialized size, not an estimate. */
        private static final int APP_METADATA_SIZE_LIMIT = 32000;

        public void setAppMetadata(android.os.PersistableBundle appMetadata)
                throws java.io.IOException {
            SessionMeta m = meta();
            if (m == null) throw new java.io.IOException("Session " + state.id + " is gone");
            if (appMetadata != null) {
                android.os.Parcel p = android.os.Parcel.obtain();
                int size;
                try {
                    appMetadata.writeToParcel(p, 0);
                    size = p.dataSize();
                } finally {
                    p.recycle();
                }
                if (size > APP_METADATA_SIZE_LIMIT) {
                    throw new java.io.IOException("App metadata size exceeds the limit of "
                            + APP_METADATA_SIZE_LIMIT + " bytes");
                }
            }
            m.appMetadata = appMetadata;
            m.touch();
        }

        /** AOSP Session.getAppMetadata(): @NonNull -- an empty bundle when
         *  none was set, never null. */
        public android.os.PersistableBundle getAppMetadata() {
            SessionMeta m = meta();
            android.os.PersistableBundle b = (m != null) ? m.appMetadata : null;
            return (b != null) ? b : new android.os.PersistableBundle();
        }

        /** AOSP Session.transfer(String): hand this session to another
         *  installer, which must then commit it. @throws NameNotFoundException
         *  "if the new owner could not be found" -- which is every package name
         *  except the one app running here, and that app is already the owner,
         *  so AOSP's other documented failure (IllegalArgumentException, "if
         *  the new owner is the current owner") covers the remaining case.
         *  There is no reachable success path on a one-app device, and
         *  silently keeping the session while reporting success would leave the
         *  caller waiting for an install the new owner will never commit. */
        public void transfer(String packageName) throws PackageManager.NameNotFoundException {
            android.app.Application app = android.app.Application.macapk_get();
            String self = (app != null) ? app.getPackageName() : null;
            if (packageName != null && packageName.equals(self)) {
                throw new IllegalArgumentException(
                        "Session " + state.id + " is already owned by " + packageName);
            }
            throw new PackageManager.NameNotFoundException(String.valueOf(packageName));
        }

        /** AOSP Session.requestUserPreapproval(PreapprovalDetails, IntentSender)
         *  (API 34): ask the user to approve an install BEFORE the APK is
         *  downloaded.
         *
         *  Mac.apk's real user approval (consent + Touch ID, naming the app)
         *  happens natively inside the macOS install helper at COMMIT time --
         *  see the class header. A device reports STATUS_SUCCESS with
         *  EXTRA_PRE_APPROVAL true once pre-approval is granted, and the
         *  installer then downloads and commits. We report exactly that, so
         *  current F-Droid (which treats a pre-approval failure as fatal,
         *  GitHub #139) proceeds to commit(). Nothing is skipped: success here
         *  grants no install permission and commit() still runs the full
         *  native gate, so the user is still prompted before anything lands.
         *  (Until v1.0.2377 this reported STATUS_FAILURE, which blocked every
         *  install from a pre-approval-using client.) */
        public void requestUserPreapproval(PreapprovalDetails details,
                android.content.IntentSender statusReceiver) {
            SessionMeta m = meta();
            if (m != null) { m.preApprovalRequested = true; m.touch(); }
            if (statusReceiver == null) return;
            android.content.Intent fillIn = new android.content.Intent();
            fillIn.putExtra(EXTRA_SESSION_ID, state.id);
            fillIn.putExtra(EXTRA_PACKAGE_NAME,
                    details != null ? details.getPackageName() : null);
            fillIn.putExtra(EXTRA_STATUS, STATUS_SUCCESS);
            fillIn.putExtra(EXTRA_STATUS_MESSAGE, "Pre-approval granted; the install is confirmed at commit time");
            fillIn.putExtra(EXTRA_LEGACY_STATUS, 1);
            fillIn.putExtra(EXTRA_PRE_APPROVAL, true);
            sendQuietly(statusReceiver, fillIn);
        }

        /** AOSP Session.setChecksums(String, List&lt;Checksum&gt;, byte[])
         *  (API 31): the installer supplies digests it computed for a file,
         *  signed with its own certificate, so a later requestChecksums can
         *  return them without re-hashing.
         *
         *  This is a pure installer-enforced OPTIMISATION on a device. Mac.apk
         *  has no checksum store to put them in, and PackageManager
         *  .requestChecksums correspondingly reports none -- the two answers
         *  agree. AOSP validates its arguments before storing, and those
         *  validations are the observable part a caller can hit, so they are
         *  reproduced: an unknown file name is an IOException upstream. */
        public void setChecksums(String name, java.util.List<Checksum> checksums,
                byte[] signature) throws java.io.IOException {
            if (name == null || checksums == null) {
                throw new NullPointerException("setChecksums requires a name and checksums");
            }
            boolean known = false;
            for (String n : state.getNames()) {
                if (name.equals(n)) { known = true; break; }
            }
            if (!known) {
                throw new java.io.IOException("Unknown file name: " + name);
            }
            // Deliberately not stored: see above. Nothing reads a checksum
            // store here, so keeping the bytes would only imply one exists.
        }

        /** AOSP Session.requestChecksums(...) (API 31): the session-scoped form
         *  of PackageManager.requestChecksums, for a file staged in THIS
         *  session. Same answer as the PackageManager form -- we compute no
         *  checksums -- delivered on the caller's Executor, which is the part
         *  of the contract a caller's threading depends on. AOSP's
         *  argument validation is reproduced first. */
        public void requestChecksums(String name, int required,
                java.util.List<java.security.cert.Certificate> trustedInstallers,
                java.util.concurrent.Executor executor,
                PackageManager.OnChecksumsReadyListener onChecksumsReadyListener)
                throws java.security.cert.CertificateEncodingException,
                       java.io.FileNotFoundException {
            if (executor == null || onChecksumsReadyListener == null || name == null) {
                throw new NullPointerException("requestChecksums requires non-null arguments");
            }
            if (trustedInstallers != null
                    && trustedInstallers != PackageManager.TRUST_ALL
                    && trustedInstallers != PackageManager.TRUST_NONE
                    && trustedInstallers.isEmpty()) {
                // AOSP: "@throws IllegalArgumentException if the list of
                // trusted installer certificates is empty."
                throw new IllegalArgumentException("trustedInstallers has to be one of TRUST_ALL"
                        + "/TRUST_NONE or a non-empty list of certificates.");
            }
            boolean known = false;
            for (String n : state.getNames()) {
                if (name.equals(n)) { known = true; break; }
            }
            if (!known) throw new java.io.FileNotFoundException(name);
            executor.execute(new Runnable() {
                @Override public void run() {
                    onChecksumsReadyListener.onChecksumsReady(
                            java.util.Collections.<ApkChecksum>emptyList());
                }
            });
        }
    }

    public static class SessionInfo implements android.os.Parcelable {
        /** AOSP SessionInfo.INVALID_ID: "used to indicate that no ID has been
         *  assigned", and the value getParentSessionId() returns for a session
         *  with no parent. */
        public static final int INVALID_ID = -1;

        /** AOSP SessionInfo staged-session error codes. The SESSION_* names are
         *  API 34's rename of the STAGED_SESSION_* set and carry the SAME
         *  values, so both spellings are declared exactly as upstream does. */
        public static final int STAGED_SESSION_NO_ERROR = 0;
        public static final int STAGED_SESSION_VERIFICATION_FAILED = 1;
        public static final int STAGED_SESSION_ACTIVATION_FAILED = 2;
        public static final int STAGED_SESSION_UNKNOWN = 3;
        public static final int STAGED_SESSION_CONFLICT = 4;
        public static final int SESSION_NO_ERROR = 0;
        public static final int SESSION_VERIFICATION_FAILED = 1;
        public static final int SESSION_ACTIVATION_FAILED = 2;
        public static final int SESSION_UNKNOWN_ERROR = 3;
        public static final int SESSION_CONFLICT = 4;

        int sessionId;
        String appPackageName;
        String appLabel;
        float progress;
        boolean active;
        boolean sealed;
        /** The SessionParams echo for this session; null for a SessionInfo that
         *  came off a parcel or that outlived its session. Every getter below
         *  falls back to the value AOSP reports for an unset field. */
        SessionMeta meta;

        // AOSP's SessionInfo carries 35+ fields (userId, installerPackageName,
        // resolvedBaseCodePath, mode, rollback policy, ...;
        // PackageInstaller.java L4594-4639); this class tracks the 6 above,
        // so this writes our own field order rather than AOSP's exact wire
        // shape, per the absent-fields-not-padded rule.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeString(appPackageName);
            dest.writeString(appLabel);
            dest.writeFloat(progress);
            dest.writeBoolean(active);
            dest.writeBoolean(sealed);
        }

        public static final android.os.Parcelable.Creator<SessionInfo> CREATOR =
                new android.os.Parcelable.Creator<SessionInfo>() {
            @Override public SessionInfo createFromParcel(android.os.Parcel in) {
                SessionInfo s = new SessionInfo();
                s.sessionId = in.readInt();
                s.appPackageName = in.readString();
                s.appLabel = in.readString();
                s.progress = in.readFloat();
                s.active = in.readBoolean();
                s.sealed = in.readBoolean();
                return s;
            }
            @Override public SessionInfo[] newArray(int size) { return new SessionInfo[size]; }
        };

        public int getSessionId() { return sessionId; }
        public String getAppPackageName() { return appPackageName; }
        public int getInstallerUid() { return android.os.Process.myUid(); }
        // AOSP: float in [0,1], not int.
        // frameworks/base/core/java/android/content/pm/PackageInstaller.java
        public float getProgress() { return progress; }
        public CharSequence getAppLabel() { return appLabel; }
        public android.net.Uri getReferrerUri() { return null; }
        public android.net.Uri getOriginatingUri() { return null; }
        public boolean isActive() { return active; }
        public boolean isSealed() { return sealed; }
        public boolean isStaged() { return false; }
        public String getInstallerPackageName() {
            return (meta != null) ? meta.installerPackageName : null;
        }

        // ---- SessionParams echo -------------------------------------------
        // AOSP's SessionInfo is, for the most part, the params the installer
        // handed to createSession read back. Each getter below returns exactly
        // what was set, or AOSP's own default when it was not.

        /** AOSP: MODE_FULL_INSTALL or MODE_INHERIT_EXISTING. */
        public int getMode() {
            return (meta != null) ? meta.mode : SessionParams.MODE_FULL_INSTALL;
        }
        /** AOSP: the PackageInfo.INSTALL_LOCATION_* the params asked for. */
        public int getInstallLocation() {
            return (meta != null) ? meta.installLocation
                                  : PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        }
        /** AOSP: the PackageManager.INSTALL_REASON_* the params carried. */
        public int getInstallReason() {
            return (meta != null) ? meta.installReason : PackageManager.INSTALL_REASON_UNKNOWN;
        }
        /** AOSP: setSize's hint, or -1 when unknown -- our SessionParams
         *  default, and AOSP's. */
        public long getSize() { return (meta != null) ? meta.sizeBytes : -1; }
        /** AOSP: setOriginatingUid, or Process.INVALID_UID (-1) when unset. */
        public int getOriginatingUid() { return (meta != null) ? meta.originatingUid : -1; }
        /** AOSP: the SessionParams.USER_ACTION_* the installer requested. */
        public int getRequireUserAction() {
            return (meta != null) ? meta.requireUserAction
                                  : SessionParams.USER_ACTION_UNSPECIFIED;
        }
        /** AOSP: the PACKAGE_SOURCE_* the installer declared. */
        public int getPackageSource() {
            return (meta != null) ? meta.packageSource
                                  : SessionParams.PACKAGE_SOURCE_UNSPECIFIED;
        }
        /** AOSP: the badging icon set by SessionParams.setAppIcon or
         *  updateSessionAppIcon; null when none was set. */
        public android.graphics.Bitmap getAppIcon() {
            return (meta != null) ? meta.appIcon : null;
        }
        /** AOSP API-31: "the attribution tag of the installer", null when the
         *  installer did not set one. Mac.apk's PackageInstaller has no
         *  setInstallerAttributionTag on SessionParams (it is a Context-level
         *  attribution on a device, which this tree does not model), so this is
         *  always null -- the value AOSP reports for an untagged installer. */
        public String getInstallerAttributionTag() {
            return (meta != null) ? meta.installerAttributionTag : null;
        }
        public boolean isMultiPackage() { return meta != null && meta.multiPackage; }
        public boolean isRequestUpdateOwnership() {
            return meta != null && meta.requestUpdateOwnership;
        }
        public boolean isApplicationEnabledSettingPersistent() {
            return meta != null && meta.applicationEnabledSettingPersistent;
        }
        /** AOSP API-34: whether SessionParams.setDontKillApp was requested. */
        public boolean getDontKillApp() { return meta != null && meta.dontKillApp; }
        /** AOSP API-35: whether SessionParams
         *  .setAutoInstallDependenciesEnabled was requested. */
        public boolean isAutoInstallDependenciesEnabled() {
            return meta != null && meta.autoInstallDependenciesEnabled;
        }
        /** AOSP API-34: whether Session.requestUserPreapproval was called. */
        public boolean isPreApprovalRequested() {
            return meta != null && meta.preApprovalRequested;
        }
        /** AOSP API-35: whether this session is fulfilling an unarchival.
         *  Mac.apk never issues an unarchive id, so no session is. */
        public boolean isUnarchival() { return false; }
        /** AOSP API-29: "whether this session has already been committed."
         *  Our sealed flag IS the commit flag -- PackageInstallerBackend seals
         *  a session inside commit() and nowhere else -- so the two agree
         *  rather than tracking a second, drift-prone boolean. */
        public boolean isCommitted() { return sealed; }

        // ---- timestamps ----------------------------------------------------
        /** AOSP API-29: when the session was created, in
         *  System.currentTimeMillis() units. */
        public long getCreatedMillis() { return (meta != null) ? meta.createdMillis : 0; }
        /** AOSP API-29: when the session was last updated. */
        public long getUpdatedMillis() { return (meta != null) ? meta.updatedMillis : 0; }

        // ---- multi-package linkage -----------------------------------------
        public int[] getChildSessionIds() {
            return (meta != null) ? meta.childSessionIds.clone() : new int[0];
        }
        public int getParentSessionId() {
            return (meta != null) ? meta.parentSessionId : INVALID_ID;
        }
        public boolean hasParentSessionId() { return getParentSessionId() != INVALID_ID; }

        // ---- staged sessions ------------------------------------------------
        // No session on Mac.apk is ever staged (see Session.isStaged), so every
        // staged-state question has one honest answer: not active, not applied,
        // not failed, not ready, no error. AOSP reports exactly this set for a
        // non-staged session, and a caller that branches on isStaged() first --
        // which is the documented way to read these -- never reaches them.
        public boolean isStagedSessionActive() { return false; }
        public boolean isStagedSessionApplied() { return false; }
        public boolean isStagedSessionFailed() { return false; }
        public boolean isStagedSessionReady() { return false; }
        public int getStagedSessionErrorCode() { return SESSION_NO_ERROR; }
        /** AOSP: @NonNull -- the empty string when there is no error. */
        public String getStagedSessionErrorMessage() { return ""; }

        /** AOSP API-29: "the user associated with this session." Mac.apk has
         *  one user, the system user, and every session belongs to it. */
        public android.os.UserHandle getUser() { return android.os.UserHandle.SYSTEM; }

        /** AOSP SessionInfo.createDetailsIntent(): an ACTION_SESSION_DETAILS
         *  Intent aimed at the INSTALLER, which is what a launcher fires when
         *  the user taps a pending-install icon. AOSP builds it unconditionally
         *  -- it does not check that anything resolves -- so this builds the
         *  same Intent with the same action, EXTRA_SESSION_ID and package
         *  target. Whether an activity resolves is the caller's problem on a
         *  device too. */
        public android.content.Intent createDetailsIntent() {
            android.content.Intent intent =
                    new android.content.Intent(ACTION_SESSION_DETAILS);
            intent.putExtra(EXTRA_SESSION_ID, sessionId);
            String installer = getInstallerPackageName();
            if (installer != null) intent.setPackage(installer);
            return intent;
        }
    }

    public static abstract class SessionCallback {
        public abstract void onCreated(int sessionId);
        public abstract void onBadgingChanged(int sessionId);
        public abstract void onActiveChanged(int sessionId, boolean active);
        public abstract void onProgressChanged(int sessionId, float progress);
        public abstract void onFinished(int sessionId, boolean success);
    }

    public static final class InstallConstraints implements android.os.Parcelable {
        public static final InstallConstraints GENTLE_UPDATE = new InstallConstraints();

        // 2026-08-23 conformance sweep: previously the Builder's setters were
        // no-ops (returned `this` without storing anything) and every
        // isXRequired() getter hardcoded false, so a constraint set built
        // via the Builder was indistinguishable from GENTLE_UPDATE -- which
        // made this class trivially "stateless" but also meant an app that
        // actually READ its own constraints back always got the wrong
        // answer. Real fields now back the getters (matching AOSP field
        // names 1:1, PackageInstaller.java L5241-5299); Mac.apk still
        // trivially satisfies every constraint immediately (see
        // commitSessionAfterInstallConstraintsAreMet below), so this does
        // not change install behaviour -- only what the object reports
        // about itself.
        private boolean mDeviceIdleRequired;
        private boolean mAppNotForegroundRequired;
        private boolean mAppNotInteractingRequired;
        private boolean mAppNotTopVisibleRequired;
        private boolean mNotInCallRequired;

        private InstallConstraints() {}
        private InstallConstraints(boolean deviceIdle, boolean appNotForeground,
                boolean appNotInteracting, boolean appNotTopVisible, boolean notInCall) {
            mDeviceIdleRequired = deviceIdle;
            mAppNotForegroundRequired = appNotForeground;
            mAppNotInteractingRequired = appNotInteracting;
            mAppNotTopVisibleRequired = appNotTopVisible;
            mNotInCallRequired = notInCall;
        }
        public boolean isAppNotForegroundRequired() { return mAppNotForegroundRequired; }
        public boolean isAppNotInteractingRequired() { return mAppNotInteractingRequired; }
        public boolean isAppNotTopVisibleRequired() { return mAppNotTopVisibleRequired; }
        public boolean isDeviceIdleRequired() { return mDeviceIdleRequired; }
        public boolean isNotInCallRequired() { return mNotInCallRequired; }

        @Override public int describeContents() { return 0; }

        // AOSP field order (as a bitfield byte): deviceIdle,
        // appNotForeground, appNotInteracting, appNotTopVisible, notInCall.
        // Written as plain booleans here rather than bit-packed -- this is
        // our own wire format, not a cross-process ABI, so there is no need
        // to match AOSP's byte-packing exactly.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeBoolean(mDeviceIdleRequired);
            dest.writeBoolean(mAppNotForegroundRequired);
            dest.writeBoolean(mAppNotInteractingRequired);
            dest.writeBoolean(mAppNotTopVisibleRequired);
            dest.writeBoolean(mNotInCallRequired);
        }

        public static final android.os.Parcelable.Creator<InstallConstraints> CREATOR =
                new android.os.Parcelable.Creator<InstallConstraints>() {
            @Override public InstallConstraints createFromParcel(android.os.Parcel in) {
                return new InstallConstraints(in.readBoolean(), in.readBoolean(),
                        in.readBoolean(), in.readBoolean(), in.readBoolean());
            }
            @Override public InstallConstraints[] newArray(int size) {
                return new InstallConstraints[size];
            }
        };

        public static final class Builder {
            private boolean mDeviceIdleRequired;
            private boolean mAppNotForegroundRequired;
            private boolean mAppNotInteractingRequired;
            private boolean mAppNotTopVisibleRequired;
            private boolean mNotInCallRequired;
            public Builder setAppNotForegroundRequired() { mAppNotForegroundRequired = true; return this; }
            public Builder setAppNotInteractingRequired() { mAppNotInteractingRequired = true; return this; }
            public Builder setAppNotTopVisibleRequired() { mAppNotTopVisibleRequired = true; return this; }
            public Builder setDeviceIdleRequired() { mDeviceIdleRequired = true; return this; }
            public Builder setNotInCallRequired() { mNotInCallRequired = true; return this; }
            public InstallConstraints build() {
                return new InstallConstraints(mDeviceIdleRequired, mAppNotForegroundRequired,
                        mAppNotInteractingRequired, mAppNotTopVisibleRequired, mNotInCallRequired);
            }
        }
    }

    /** AOSP API-34: commit once the constraints hold. On Mac.apk there is one
     *  foreground app and no calls/idle states to wait for, so the
     *  constraints are trivially met and this is a plain commit (F-Droid's
     *  SDK>=31 session path reaches here when canUseInstallConstraints). */
    public void commitSessionAfterInstallConstraintsAreMet(int sessionId,
            android.content.IntentSender statusReceiver,
            InstallConstraints constraints, long timeoutMillis) {
        PackageInstallerBackend.SessionState s = PackageInstallerBackend.get(sessionId);
        if (s == null) {
            throw new SecurityException("Session " + sessionId + " does not exist");
        }
        s.commit(statusReceiver);
    }
}
