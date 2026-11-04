// com.macapk.loader.SpecialAccessHandler -- the host side of Android's three
// "special app access" Settings screens for storage:
//
//   android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION  (+ package: data)
//   android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION      (no data)
//   android.settings.REQUEST_MANAGE_MEDIA                    (with or without package:)
//
// Stage S1 of docs/shared-storage-real-mac-folders-design_2026-09-25.md.
//
// WHAT A DEVICE DOES. Settings declares four activities for these
// (packages/apps/Settings AndroidManifest.xml android14 :4096-4155), each with
// ONE <intent-filter android:priority="1"> of {action, category DEFAULT} and,
// for the per-app forms, <data android:scheme="package"/>:
//   Settings$AppManageExternalStorageActivity  MANAGE_APP_ALL_FILES_ACCESS_PERMISSION + package:
//   Settings$ManageExternalStorageActivity     MANAGE_ALL_FILES_ACCESS_PERMISSION, no data
//   Settings$AppMediaManagementAppsActivity    REQUEST_MANAGE_MEDIA + package:
//   Settings$MediaManagementAppsActivity       REQUEST_MANAGE_MEDIA, no data
// IntentFilter.match therefore resolves exactly those shapes: the per-app action
// WITHOUT package: data does not resolve (the app gets
// ActivityNotFoundException on a device), MANAGE_ALL_... WITH data does not
// either, nor does any intent carrying a MIME type or a category other than
// DEFAULT. The screen is a switch; toggling it is
// ManageExternalStorageDetails.java:111-114 `setUidMode(OP_MANAGE_EXTERNAL_STORAGE,
// uid, newState ? MODE_ALLOWED : MODE_ERRORED)` (MediaManagementAppsDetails the
// same for OP_MANAGE_MEDIA), it NEVER calls setResult, so a for-result caller
// gets RESULT_CANCELED with null data and re-reads the real state itself
// (Fossify in onResume, AnkiDroid and Aurora in their ActivityResultLauncher
// callback). Leaving the screen does not restart the app; turning op 92 OFF
// kills it (StorageManagerService.onAppOpsChanged -> killAppForOpChange,
// sms14 :4838-4845); op 110 has no such rule.
//
// WHAT WE DO. The switch is macapk-installhelper's `special-access` verb: a
// separate process that identifies the caller from its PARENT process (never
// from arguments) and asks through a CFUserNotification, which a system
// process draws and this app therefore cannot press. The helper, and only the
// helper, writes the signed grant; SpecialAccessStore reads it. Around that we
// reproduce what the caller observes on a device:
//   1. claim the activity-result pairing synchronously (as InstallIntentHandler
//      does), for EVERY for-result start of all three actions -- AnkiDroid and
//      Aurora launch MANAGE_APP_ALL_FILES_ACCESS_PERMISSION for result, Fossify
//      REQUEST_MANAGE_MEDIA (the fossify skeptic's correction to the plan);
//   2. drive the caller down as a covering Settings activity would
//      (onPause, onStop);
//   3. run the helper on a worker thread and re-read the store (never trusting
//      the helper's exit code alone);
//   4. deliver RESULT_CANCELED + null data and bring the caller back in
//      AOSP's order: onRestart, onStart, onActivityResult, onResume for an app
//      targeting above S, the result first (while still STOPPED) at or below
//      it -- ActivityResultItem.getPostExecutionState is ON_RESUME only under
//      CALL_ACTIVITY_RESULT_BEFORE_RESUME, @EnabledAfter(S) (android14
//      ActivityResultItem.java :50-61), and TransactionExecutorHelper cycles a
//      STOPPED activity to ON_START, the closest of {ON_START, ON_PAUSE}
//      (:51, :140-151). Cover.uncoverWithResult; MACAPK_RESULT_ORDER_AOSP=0
//      restores the old posted-result-then-restore order;
//   5. on op 92 ALLOWED -> not ALLOWED, end the process and relaunch the app
//      (the killAppForOpChange rule; the relaunch stands in for the user
//      pressing Back out of Settings into a restarted task).
//
// The screen shows ONE app. A per-app intent naming another package gets no
// prompt and RESULT_CANCELED (we cannot grant for another app), and the list
// forms prompt for the calling app, the only app this process can speak for.
// An app that does not DECLARE the op's permission gets no prompt either --
// Settings shows its switch disabled (AppStateAppOpsBridge.java:314-322) -- but
// still the covered/uncovered round-trip and RESULT_CANCELED, as the user
// opening and leaving that screen would produce.
//
// MACAPK_ALLFILES_HONEST=0 (SpecialAccessStore.honest()) makes opFor() answer
// null, so the three actions resolve to nothing again, as before S1.
//
// STAGE S5 (2026-09-26) adds, in this class, the two other host screens that
// write the same store: the runtime-permission dialog behind
// Activity.requestPermissions (requestPermissions below; gate
// MACAPK_MEDIA_PERMS_ASK) and Settings' "App info"
// (ACTION_APPLICATION_DETAILS_SETTINGS; gate MACAPK_APP_DETAILS_SETTINGS).
// docs/storage-runtime-permissions_2026-09-26.md; offline gate
// debug/mediaperm_gate.sh.
package com.macapk.loader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

public final class SpecialAccessHandler {
    private static final String TAG = "SpecialAccess";

    static final String ACTION_MANAGE_ALL_FILES =
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION";
    static final String ACTION_MANAGE_APP_ALL_FILES =
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION";
    static final String ACTION_REQUEST_MANAGE_MEDIA = "android.settings.REQUEST_MANAGE_MEDIA";
    /** The resolution row's package: AOSP's Settings, so
     *  `resolveActivity(...).activityInfo.packageName` names the app a device
     *  names. Deliberately not ours (see MacApkPackageManager's host row). */
    static final String SETTINGS_PACKAGE = "com.android.settings";

    private SpecialAccessHandler() {}

    /** How ActivityHost lets us drive the calling activity's lifecycle without
     *  exposing its stack: all three are POSTED to the Android main thread by
     *  the implementation, so they run after anything already queued there.
     *  ActivityHost.hostCover(opaque) builds one: an OPAQUE cover (a Settings
     *  screen) pauses and stops the top, a translucent one (PermissionController's
     *  GrantPermissionsActivity, PackageInstaller's dialog-themed activities)
     *  only pauses it -- ActivityTaskManager stops the activity below only when
     *  the new top occludes it. */
    public interface Cover {
        /** onPause (+ onStop when opaque) of the current top. */
        void cover();
        /** onRestart + onStart + onResume of the activity cover() drove down. */
        void uncover();
        /** uncover() with a host-produced RESULT in AOSP's slot
         *  (MACAPK_RESULT_ORDER_AOSP): `result` runs once, synchronously, on the
         *  Android main thread -- after onRestart/onStart and before onResume
         *  for an app targeting above S (ActivityResultItem
         *  CALL_ACTIVITY_RESULT_BEFORE_RESUME), while still stopped for one at
         *  or below it, and before onResume for a paused one either way. A null
         *  `result` is plain uncover(). If the covered activity is no longer the
         *  top, `result` still runs (a device delivers the result to its
         *  requester regardless). The default, for a Cover that has no restore
         *  of its own to place the result in (a test double), runs the result
         *  and then uncover() -- the pre-S5 order; ActivityHost.hostCover
         *  overrides it with the real slot. */
        default void uncoverWithResult(Runnable result) {
            if (result != null) result.run();
            uncover();
        }
    }

    /** MACAPK_RESULT_ORDER_AOSP (labelled bisect gate, default ON): deliver a
     *  host-produced activity result (this class, InstallIntentHandler) inside
     *  restoreTop's AOSP result slot instead of posting it ahead of the
     *  restore. =0 restores the exact pre-change path: the result is POSTED
     *  and then the posted restore runs onRestart/onStart/onResume, and the
     *  install handoff covers nothing. See Cover.uncoverWithResult and
     *  docs/storage-runtime-permissions_2026-09-26.md section 4. */
    static final boolean RESULT_ORDER_AOSP;
    /** MACAPK_APP_DETAILS_SETTINGS (labelled bisect gate, default ON):
     *  Settings.ACTION_APPLICATION_DETAILS_SETTINGS (+ package:) resolves to
     *  AOSP's InstalledAppDetails and opens the app-info alert. =0: the intent
     *  resolves to nothing again, as before. */
    static final boolean APP_DETAILS;
    static {
        String r = System.getenv("MACAPK_RESULT_ORDER_AOSP");
        RESULT_ORDER_AOSP = r == null || r.isEmpty() || r.charAt(0) != '0';
        String d = System.getenv("MACAPK_APP_DETAILS_SETTINGS");
        APP_DETAILS = d == null || d.isEmpty() || d.charAt(0) != '0';
    }

    /** Hand a host-produced result back and bring the caller up, in the order
     *  the gate selects. `token` null (a plain startActivity) only uncovers. */
    static void finishWithResult(Cover cover, final Activity.MacApkHostResult token,
                                 final int resultCode, final Intent data) {
        if (RESULT_ORDER_AOSP) {
            cover.uncoverWithResult(token == null ? null
                    : () -> token.deliverNow(resultCode, data));
        } else {
            if (token != null) token.deliver(resultCode, data);
            cover.uncover();
        }
    }

    /** One consent at a time: a device stacks Settings activities, but a
     *  second dialog for the same app while the first is up would only ask the
     *  same question twice. */
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.ExecutorService EXEC =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "macapk-special-access");
            t.setDaemon(true);
            return t;
        });

    // ---- the pure predicate (resolution) -----------------------------------

    /** The app-op a Settings special-access intent asks about, or null when the
     *  intent matches none of Settings' four filters. IntentFilter.match
     *  semantics, on action / categories / type / data only (never extras, the
     *  v1.0.940 rule ActivityHost.hostActionFor documents). */
    static String opFor(Intent intent) {
        if (intent == null || !SpecialAccessStore.honest()) return null;
        String action = intent.getAction();
        if (action == null) return null;
        boolean perApp;   // which filter shapes exist for this action
        boolean list;
        String op;
        switch (action) {
            case ACTION_MANAGE_APP_ALL_FILES:
                perApp = true;  list = false; op = SpecialAccessStore.OP_MANAGE_EXTERNAL_STORAGE; break;
            case ACTION_MANAGE_ALL_FILES:
                perApp = false; list = true;  op = SpecialAccessStore.OP_MANAGE_EXTERNAL_STORAGE; break;
            case ACTION_REQUEST_MANAGE_MEDIA:
                perApp = true;  list = true;  op = SpecialAccessStore.OP_MANAGE_MEDIA; break;
            default:
                return null;
        }
        // Categories: every category on the intent must be in the filter's set,
        // which is {DEFAULT} (IntentFilter.matchCategories).
        java.util.Set<String> cats = intent.getCategories();
        if (cats != null) {
            for (String c : cats) {
                if (!Intent.CATEGORY_DEFAULT.equals(c)) return null;
            }
        }
        // No filter declares a MIME type, so a typed intent matches none
        // (IntentFilter.matchData: types == null && type != null -> NO_MATCH_TYPE).
        if (intent.getType() != null) return null;
        Uri data = intent.getData();
        if (data == null) return list ? op : null;
        // A filter with no <data> matches only an intent with no data; the
        // per-app filter declares scheme "package" and nothing else.
        return (perApp && "package".equals(data.getScheme())) ? op : null;
    }

    /** The AOSP activity the intent resolves to (see the header table). */
    static String settingsActivityFor(Intent intent) {
        String action = intent.getAction();
        boolean hasData = intent.getData() != null;
        if (ACTION_MANAGE_APP_ALL_FILES.equals(action)) {
            return "com.android.settings.Settings$AppManageExternalStorageActivity";
        }
        if (ACTION_MANAGE_ALL_FILES.equals(action)) {
            return "com.android.settings.Settings$ManageExternalStorageActivity";
        }
        return hasData ? "com.android.settings.Settings$AppMediaManagementAppsActivity"
                       : "com.android.settings.Settings$MediaManagementAppsActivity";
    }

    /** The ResolveInfo PackageManager reports for a matching intent: AOSP's
     *  component and filter (priority 1, category DEFAULT, scheme "package"
     *  for the per-app forms), so `resolveActivity(intent, MATCH_DEFAULT_ONLY)`
     *  -- the guard AnkiDroid (Intent.resolveActivity) and Aurora
     *  (pm.resolveActivity(.., 0x10000)) run before launching -- is non-null. */
    static android.content.pm.ResolveInfo settingsResolveInfo(Intent intent) {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(intent.getAction());
        f.addCategory(Intent.CATEGORY_DEFAULT);
        if (intent.getData() != null) f.addDataScheme("package");
        f.setPriority(1);
        android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
        ri.filter = f;
        ri.priority = 1;
        ri.isDefault = true;
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.name = settingsActivityFor(intent);
        ai.packageName = SETTINGS_PACKAGE;
        ai.exported = true;
        ai.enabled = true;
        android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = SETTINGS_PACKAGE;
        ai.applicationInfo = app;
        ri.activityInfo = ai;
        return ri;
    }

    // ---- performing it --------------------------------------------------------

    /**
     * Perform the Settings round-trip. Called from ActivityHost's implicit-intent
     * resolution with the intent the app handed startActivity[ForResult]
     * (`outer`, the identity a pending result was recorded against) and the one
     * being resolved (`real`, differs only under a chooser). Returns true.
     *
     * The claim runs SYNCHRONOUSLY, inside startActivityForResult's try block,
     * which is what keeps its finally from settling the pairing as a no-launch
     * RESULT_CANCELED on this same turn; everything else is asynchronous, as a
     * real activity start is.
     */
    static boolean handle(Intent outer, Intent real, final String op, final Cover cover) {
        final Activity.MacApkHostResult token = Activity.macapkClaimHostResult(outer);
        final String self = selfPackage();
        String target = null;
        Uri data = real.getData();
        if (data != null) target = data.getSchemeSpecificPart();
        NativeLog.i(TAG, "settings intent " + real.getAction() + " data=" + data
                + " op=" + op + " forResult=" + (token != null)
                + " -> " + settingsActivityFor(real));
        if (target != null && self != null && !target.equals(self)) {
            // A device would show THAT app's switch. We can only ever speak for
            // the app we host, so there is nothing to ask; the caller sees the
            // screen closed without a change.
            NativeLog.w(TAG, "special access requested for another package (" + target
                    + "); no prompt, RESULT_CANCELED");
            deliverCancelled(token);
            return true;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            NativeLog.w(TAG, "a special-access consent is already showing; this request"
                    + " gets RESULT_CANCELED");
            deliverCancelled(token);
            return true;
        }
        cover.cover();
        EXEC.execute(() -> {
            try {
                perform(op, self, token, cover);
            } catch (Throwable t) {
                NativeLog.e(TAG, "special-access round-trip failed: " + t);
                finishCancelled(token, cover);
            } finally {
                IN_FLIGHT.set(false);
            }
        });
        return true;
    }

    /** The slow half, on the macapk-special-access thread. */
    private static void perform(String op, String self, Activity.MacApkHostResult token,
                                Cover cover) throws Exception {
        // An app that does not DECLARE the permission gets Settings' screen with
        // its switch DISABLED (AppStateAppOpsBridge.java:314-322: permissionDeclared
        // false -> the switch cannot be turned on), so the user cannot grant
        // anything and backs out: the caller sees the screen close with
        // RESULT_CANCELED. Asking here would record an Allow that
        // SpecialAccessStore's declared-permission guard then ignores -- a
        // working-looking button that does nothing.
        String perm = SpecialAccessStore.permissionForOp(op);
        if (perm != null && !SpecialAccessStore.declaresPermission(perm)) {
            NativeLog.w(TAG, (self != null ? self : "this app") + " does not declare " + perm
                    + ": Settings shows its switch disabled, so there is nothing to ask;"
                    + " RESULT_CANCELED");
            finishCancelled(token, cover);
            return;
        }
        SpecialAccessStore.invalidate();
        final int before = SpecialAccessStore.specialOpMode(op);
        NativeLog.i(TAG, "before: " + op + "=" + modeName(before) + " ("
                + SpecialAccessStore.describe() + ")");
        java.io.File helper = PackageInstallerBackend.locateHelper();
        if (helper == null) {
            NativeLog.e(TAG, "macapk-installhelper not found; the Settings screen cannot be"
                    + " shown, nothing changes");
        } else {
            PackageInstallerBackend.HelperResult r =
                    PackageInstallerBackend.runSpecialAccessHelper(helper, op);
            NativeLog.i(TAG, "helper exit " + r.exit + " status=" + r.jsonField("status")
                    + PackageInstallerBackend.helperReason(r));
        }
        // The store is the truth, not the exit code: a helper that lied, or a
        // redirected MACAPK_INSTALLHELPER, cannot produce a signature.
        SpecialAccessStore.invalidate();
        final int after = SpecialAccessStore.specialOpMode(op);
        NativeLog.i(TAG, "after: " + op + "=" + modeName(after) + " ("
                + SpecialAccessStore.describe() + ")");
        if (after != before) {
            android.app.AppOpsManager.macapkDispatchOpChanged(op, self);
            if (SpecialAccessStore.OP_MANAGE_EXTERNAL_STORAGE.equals(op)
                    && after != SpecialAccessStore.MODE_ALLOWED) {
                killForOpChange(op, self);   // does not return
            }
        }
        finishCancelled(token, cover);
    }

    /** A covered Settings round-trip ends: RESULT_CANCELED + null data (none of
     *  these screens calls setResult) and the caller comes back up, in the
     *  order MACAPK_RESULT_ORDER_AOSP selects. */
    private static void finishCancelled(Activity.MacApkHostResult token, Cover cover) {
        if (token != null) {
            NativeLog.i(TAG, "result: requestCode=" + token.requestCode()
                    + " resultCode=RESULT_CANCELED data=null"
                    + (RESULT_ORDER_AOSP ? " (in restoreTop's result slot)" : " (posted)"));
        }
        finishWithResult(cover, token, Activity.RESULT_CANCELED, null);
    }

    /** ManageExternalStorageDetails never calls setResult: RESULT_CANCELED with
     *  null data, through the ordinary main-looper result delivery. Used where
     *  nothing was covered (a request answered without opening the screen). */
    private static void deliverCancelled(Activity.MacApkHostResult token) {
        if (token == null) return;
        NativeLog.i(TAG, "result delivered: requestCode=" + token.requestCode()
                + " resultCode=RESULT_CANCELED data=null");
        token.deliver(Activity.RESULT_CANCELED, null);
    }

    // ==== Stage S5: runtime permissions (Activity.requestPermissions) =========
    //
    // Activity.requestPermissions on a device starts PermissionController's
    // GrantPermissionsActivity for result (Activity.java android14 :5471-5496).
    // That activity is translucent (GrantPermissions theme,
    // windowIsTranslucent=true, PermissionController res/values/themes.xml
    // lineage-21.0 :51-58), so the caller is PAUSED, never stopped; it shows one
    // dialog per permission group that needs one, and finishes with
    // EXTRA_REQUEST_PERMISSIONS_RESULTS = pm.checkPermission of each ORIGINALLY
    // requested permission (GrantPermissionsActivity.setResultIfNeeded
    // :1005-1040). The caller then gets onRequestPermissionsResult and onResume.
    // It is started even when nothing needs asking (it finishes at once), so
    // the pause/result/resume round-trip happens for every request.
    //
    // Here: SpecialAccessStore.PermModel.plan decides, per group, what
    // GrantPermissionsViewModel would do; each dialog is the helper's
    // `runtime-permission` verb (a CFUserNotification, identity from the
    // parent, answer signed into the S1 store); an auto-grant into an already
    // granted group goes through the same verb with --auto and no alert, since
    // this process never writes the store. The result is then read back from
    // the store, never from an exit code.

    /** How a permission result reaches the requester (an Activity, or a
     *  platform Fragment through its host activity). Runs on the Android main
     *  thread, inside restoreTop's result slot. */
    public interface PermissionSink {
        void deliver(String[] permissions, int[] grantResults);
    }

    /** One permission dialog at a time; see requestPermissions. */
    private static final java.util.concurrent.atomic.AtomicBoolean PERM_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Activity.requestPermissions / the platform Fragment form, with
     * MACAPK_MEDIA_PERMS_ASK on. `cover` must be a TRANSLUCENT cover (pause
     * only). Returns at once; the result arrives on the main thread.
     *
     * A second request while one is showing is answered with empty arrays,
     * which is what AOSP's Activity does ("Can request only one set of
     * permissions at a time", Activity.java :5476-5481). (A device queues a
     * platform-Fragment request instead; we cancel it. Stated, not hidden.)
     */
    public static void requestPermissions(final Activity requester, String[] permissions,
                                          final PermissionSink sink, final Cover cover) {
        final String[] req = permissions == null ? new String[0] : permissions.clone();
        if (!PERM_IN_FLIGHT.compareAndSet(false, true)) {
            NativeLog.w(TAG, "Can request only one set of permissions at a time;"
                    + " answering this request with empty arrays (a cancellation)");
            postToMain(() -> sink.deliver(new String[0], new int[0]));
            return;
        }
        try {
            cover.cover();
        } catch (RuntimeException | Error e) {
            // Nothing was started; the caller answers some other way.
            PERM_IN_FLIGHT.set(false);
            throw e;
        }
        try {
            EXEC.execute(() -> {
                int[] grants;
                try {
                    grants = performPermissionRequest(requester, req);
                } catch (Throwable t) {
                    NativeLog.e(TAG, "permission request failed: " + t);
                    grants = currentGrants(requester, req);
                }
                finishPermissions(cover, sink, req, grants);
            });
        } catch (Throwable t) {
            // The executor refused (shutting down): answer now, never drop it.
            NativeLog.e(TAG, "could not queue the permission request: " + t);
            finishPermissions(cover, sink, req, currentGrants(requester, req));
        }
    }

    /** The slow half, on the macapk-special-access thread: run each planned
     *  dialog through the helper, then read the answers back. */
    static int[] performPermissionRequest(Activity requester, String[] req) throws Exception {
        int lvl;
        try { lvl = android.os.Build.VERSION.SDK_INT; } catch (Throwable t) { lvl = 30; }
        askLocation(requester, req, lvl);
        askBluetooth(requester, req);
        SpecialAccessStore.invalidate();
        SpecialAccessStore.PermModel m = SpecialAccessStore.liveModel();
        if (m == null) return currentGrants(requester, req);
        java.util.List<SpecialAccessStore.PermModel.Step> plan = m.plan(req);
        NativeLog.i(TAG, "requestPermissions " + java.util.Arrays.toString(req)
                + " at API " + m.level + " (targetSdk " + m.target + "): plan " + plan);
        java.io.File helper = null;
        for (SpecialAccessStore.PermModel.Step s : plan) {
            if (!"PROMPT".equals(s.kind) && !"AUTO".equals(s.kind)) {
                // REJECT / FIXED / REDIRECT: GrantPermissionsViewModel answers
                // these with no UI; the permission simply stays as it is.
                NativeLog.i(TAG, "  " + s + ": no dialog");
                continue;
            }
            if (helper == null) helper = PackageInstallerBackend.locateHelper();
            if (helper == null) {
                NativeLog.e(TAG, "macapk-installhelper not found; the permission dialog cannot"
                        + " be shown, nothing changes");
                break;
            }
            java.util.List<String> argv = permissionHelperArgv(helper, m.level, s);
            HelperRun r = runHelperVerb(argv);
            NativeLog.i(TAG, "  " + s + ": helper exit " + r.exit + " " + r.lastLine());
            SpecialAccessStore.invalidate();
            if ("AUTO".equals(s.kind) && r.exit == HELPER_EXIT_AUTO_REFUSED) {
                // The helper, reading the store itself, found no granted
                // permission in the group. Ask instead (see promptInstead),
                // planned from the store as it is NOW.
                SpecialAccessStore.PermModel now = SpecialAccessStore.liveModel();
                SpecialAccessStore.PermModel.Step ask = now != null ? now.promptInstead(s) : null;
                if (ask == null) continue;
                r = runHelperVerb(permissionHelperArgv(helper, now.level, ask));
                NativeLog.i(TAG, "  " + ask + " (the helper refused " + s + "): helper exit "
                        + r.exit + " " + r.lastLine());
                SpecialAccessStore.invalidate();
            }
        }
        return currentGrants(requester, req);
    }

    /** TCC owns the Bluetooth decision. This runs on the permission worker,
     *  so the native queue and the app's UI remain free while the user answers. */
    private static void askBluetooth(Activity requester, String[] permissions) {
        if (!BluetoothBridge.available()) return;
        for (String permission : permissions) {
            if (MacApkPackageManager.isBluetoothRuntimePermission(permission)) {
                // Ignore undeclared permissions, as the platform does.
                try {
                    android.content.pm.PackageInfo info = requester.getPackageManager()
                        .getPackageInfo(requester.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
                    if (info.requestedPermissions == null
                            || !java.util.Arrays.asList(info.requestedPermissions).contains(permission)) continue;
                } catch (android.content.pm.PackageManager.NameNotFoundException missing) { continue; }
                if (BluetoothBridge.authorization() == 0) {
                    BluetoothBridge.requestAuthorizationAndWait(120_000);
                }
                return;
            }
        }
    }

    /** How long the location request waits on the macOS prompt. The app is
     *  paused behind it, as behind GrantPermissionsActivity, so the cap is what
     *  bounds a prompt macOS never showed (it skipped every one for the bare
     *  runtime, 2026-10-06: the app sat frozen for the old 10 minutes). Two
     *  minutes is ample to read and answer a real one. */
    static final long LOCATION_PROMPT_WAIT_MS = 2 * 60 * 1000;

    /** The location group's dialogs, in the order a user meets them.
     *  1. The per-app Android dialog (macapk-installhelper runtime-permission
     *     --prompt location): GrantPermissionsActivity's "Allow / Only
     *     approximate / Don't allow" for THIS app, recorded in the signed store.
     *     Skipped when the group is already answered for good (every requested
     *     permission granted, or denied twice), as the platform skips it.
     *  2. macOS's Location Services prompt, the host gate behind every app's
     *     grant (MacApkPackageManager.locationPermissionGranted): asked once,
     *     only after this app was allowed, and waited for on this thread (never
     *     the AppKit thread, which shows it).
     *  A pre-M app never gets here (Activity answers it first). */
    static void askLocation(Activity requester, String[] req, int level) {
        if (!LocationBridge.available()) return;
        java.util.List<String> perms = new java.util.ArrayList<>();
        for (String p : req) {
            // An undeclared permission stays DENIED whatever is answered, so it
            // is no reason to ask (GrantPermissionsActivity skips it too).
            if (SpecialAccessStore.isLocationPermission(p) && !perms.contains(p)) {
                try { if (declares(requester, p)) perms.add(p); } catch (Throwable ignored) { }
            }
        }
        if (perms.isEmpty()) return;
        boolean allGranted = true, fixedDenial = false;
        for (String p : perms) {
            if (!SpecialAccessStore.locationGranted(p)) allGranted = false;
            if (SpecialAccessStore.ST_DENIED_FIXED.equals(SpecialAccessStore.storedPermState(p))) {
                fixedDenial = true;
            }
        }
        if (!allGranted && !fixedDenial) {
            java.io.File helper = PackageInstallerBackend.locateHelper();
            if (helper == null) {
                NativeLog.e(TAG, "macapk-installhelper not found; the location dialog cannot"
                        + " be shown, nothing changes");
                return;
            }
            java.util.List<String> argv = new java.util.ArrayList<>();
            argv.add(helper.getAbsolutePath());
            argv.add("runtime-permission");
            argv.add("--source");
            argv.add("android");
            argv.add("--level");
            argv.add(Integer.toString(Math.max(23, level)));
            argv.add("--prompt");
            argv.add("location");
            for (String p : perms) { argv.add("--perm"); argv.add(p); }
            // The precise / approximate choice exists only when both were asked.
            if (perms.contains(SpecialAccessStore.PERM_ACCESS_FINE_LOCATION)
                    && perms.contains(SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION)) {
                argv.add("--partial");
                argv.add(SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION);
            }
            argv.add("--json");
            try {
                HelperRun r = runHelperVerb(argv);
                NativeLog.i(TAG, "requestPermissions(location) " + perms + ": helper exit "
                        + r.exit + " " + r.lastLine());
            } catch (Throwable t) {
                NativeLog.e(TAG, "location dialog failed: " + t);
            }
            SpecialAccessStore.invalidate();
        }
        boolean anyGranted = false;
        for (String p : perms) if (SpecialAccessStore.locationGranted(p)) anyGranted = true;
        if (!anyGranted) return;
        if (!LocationBridge.servicesEnabled()) {
            NativeLog.i(TAG, "requestPermissions(location): Location Services is off in macOS;"
                    + " no macOS prompt, the app sees location disabled");
            return;
        }
        if (LocationBridge.authStatus() != LocationBridge.AUTH_NOT_DETERMINED) return;
        NativeLog.i(TAG, "requestPermissions(location): showing the macOS location prompt");
        int st = LocationBridge.requestAuthorizationAndWait(LOCATION_PROMPT_WAIT_MS);
        NativeLog.i(TAG, "  macOS location answer: status " + st
                + (LocationBridge.reducedAccuracy() ? " (approximate only)" : ""));
    }

    private static boolean declares(Activity requester, String perm) throws Exception {
        android.content.pm.PackageInfo pi = requester.getPackageManager().getPackageInfo(
                requester.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
        if (pi.requestedPermissions == null) return false;
        for (String p : pi.requestedPermissions) if (perm.equals(p)) return true;
        return false;
    }

    /** macapk-installhelper runtime-permission's exit for an --auto grant it
     *  will not make (launcher/InstallHelperMain.swift RuntimePermission.run:
     *  "--auto needs a granted permission in the same group"). */
    static final int HELPER_EXIT_AUTO_REFUSED = 6;

    /** The helper argv for one planned dialog. Names only -- the helper
     *  derives the question's wording from the permissions themselves, reads
     *  the current state from the store, and identifies the app from its
     *  parent process. */
    static java.util.List<String> permissionHelperArgv(java.io.File helper, int level,
                                                      SpecialAccessStore.PermModel.Step s) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.add("runtime-permission");
        argv.add("--source");
        argv.add("android");   // constant; see PackageInstallerBackend's header
        argv.add("--level");
        argv.add(Integer.toString(level));
        if ("AUTO".equals(s.kind)) {
            argv.add("--auto");
        } else {
            argv.add("--prompt");
            argv.add(s.prompt);
        }
        for (String p : s.perms) { argv.add("--perm"); argv.add(p); }
        for (String p : s.members) { argv.add("--member"); argv.add(p); }
        if (s.partial != null && "PROMPT".equals(s.kind)) {
            for (String p : s.partial) {
                if (s.perms.contains(p)) { argv.add("--partial"); argv.add(p); }
            }
        }
        argv.add("--json");
        return argv;
    }

    /** GrantPermissionsActivity's result: pm.checkPermission of every
     *  permission as originally requested (order and duplicates kept). */
    static int[] currentGrants(Activity requester, String[] req) {
        SpecialAccessStore.invalidate();
        int[] g = new int[req.length];
        for (int i = 0; i < req.length; i++) {
            int v = android.content.pm.PackageManager.PERMISSION_DENIED;
            try {
                if (req[i] != null) {
                    v = requester != null ? requester.checkSelfPermission(req[i])
                      : android.app.Application.getInstance().checkSelfPermission(req[i]);
                }
            } catch (Throwable ignored) {}
            g[i] = v;
        }
        return g;
    }

    private static void finishPermissions(Cover cover, final PermissionSink sink,
                                          final String[] req, final int[] grants) {
        NativeLog.i(TAG, "permission result: " + java.util.Arrays.toString(req) + " -> "
                + java.util.Arrays.toString(grants));
        // The flag is released on the main thread, just before the callback,
        // as Activity.dispatchRequestPermissionsResult clears
        // mHasCurrentPermissionsRequest first (:9082-9090): an app that asks
        // again from inside onRequestPermissionsResult must not be refused.
        final Runnable deliver = () -> {
            PERM_IN_FLIGHT.set(false);
            sink.deliver(req, grants);
        };
        if (RESULT_ORDER_AOSP) {
            cover.uncoverWithResult(deliver);
        } else {
            postToMain(deliver);
            cover.uncover();
        }
    }

    private static void postToMain(Runnable r) {
        try {
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (main != null) { new android.os.Handler(main).post(r); return; }
        } catch (Throwable ignored) {}
        r.run();
    }

    // ==== Settings "App info" (ACTION_APPLICATION_DETAILS_SETTINGS) ==========
    //
    // packages/apps/Settings AndroidManifest.xml android14 :1691-1708: the
    // activity-alias .applications.InstalledAppDetails (target
    // .applications.InstalledAppDetailsTop) declares ONE filter, priority 1:
    // actions APPLICATION_DETAILS_SETTINGS and AUTO_REVOKE_PERMISSIONS,
    // category DEFAULT, <data android:scheme="package"/>. So it resolves only
    // with package: data. Apps open it after a permission was denied for good
    // ("open Settings to grant it") and re-check in onResume or in the result
    // callback; the screen never calls setResult.
    //
    // Our control surface for it is the helper's `app-details` verb: one alert
    // with a check box per storage permission group the app requests and per
    // special access it declares, drawn by a system process and written only
    // by the helper, like S1. It is not the Mac.apk control panel because a
    // GRANT must come from the human answering an alert whose identity the
    // helper takes from its parent process (the panel can only revoke), and
    // because the app expects to be covered and resumed around it.
    // Turning a runtime permission off kills the app, as AOSP does
    // (PermissionManagerServiceImpl.onPermissionRevoked -> killUid,
    // pmsi14 :347-370).

    static final String ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    static final String ACTION_AUTO_REVOKE = "android.intent.action.AUTO_REVOKE_PERMISSIONS";
    static final String APP_DETAILS_ALIAS = "com.android.settings.applications.InstalledAppDetails";
    static final String APP_DETAILS_TARGET = "com.android.settings.applications.InstalledAppDetailsTop";

    /** IntentFilter.match of InstalledAppDetails' one filter: either action,
     *  categories within {DEFAULT}, no MIME type, and data with scheme
     *  "package" (a filter that names a scheme matches no data-less intent). */
    static boolean isAppDetailsIntent(Intent intent) {
        if (intent == null || !APP_DETAILS) return false;
        String action = intent.getAction();
        if (!ACTION_APP_DETAILS.equals(action) && !ACTION_AUTO_REVOKE.equals(action)) return false;
        java.util.Set<String> cats = intent.getCategories();
        if (cats != null) {
            for (String c : cats) if (!Intent.CATEGORY_DEFAULT.equals(c)) return false;
        }
        if (intent.getType() != null) return false;
        Uri data = intent.getData();
        return data != null && "package".equals(data.getScheme());
    }

    /** The ResolveInfo for InstalledAppDetails: the alias's name, its target
     *  activity, Settings' package, the filter above. */
    static android.content.pm.ResolveInfo appDetailsResolveInfo() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ACTION_APP_DETAILS);
        f.addAction(ACTION_AUTO_REVOKE);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        f.addDataScheme("package");
        f.setPriority(1);
        android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
        ri.filter = f;
        ri.priority = 1;
        ri.isDefault = true;
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.name = APP_DETAILS_ALIAS;
        ai.targetActivity = APP_DETAILS_TARGET;
        ai.packageName = SETTINGS_PACKAGE;
        ai.exported = true;
        ai.enabled = true;
        android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = SETTINGS_PACKAGE;
        ai.applicationInfo = app;
        ri.activityInfo = ai;
        return ri;
    }

    /** Perform the App info round-trip. Same claim / cover / worker / restore
     *  shape as handle(); `cover` is opaque (Settings is a full-screen
     *  activity). */
    static boolean handleAppDetails(Intent outer, Intent real, final Cover cover) {
        final Activity.MacApkHostResult token = Activity.macapkClaimHostResult(outer);
        final String self = selfPackage();
        Uri data = real.getData();
        String target = data != null ? data.getSchemeSpecificPart() : null;
        NativeLog.i(TAG, "settings intent " + real.getAction() + " data=" + data
                + " forResult=" + (token != null) + " -> " + APP_DETAILS_ALIAS);
        if (target != null && self != null && !target.equals(self)) {
            NativeLog.w(TAG, "App info requested for another package (" + target
                    + "); no alert, RESULT_CANCELED");
            deliverCancelled(token);
            return true;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            NativeLog.w(TAG, "a settings alert is already showing; this request gets"
                    + " RESULT_CANCELED");
            deliverCancelled(token);
            return true;
        }
        cover.cover();
        EXEC.execute(() -> {
            try {
                performAppDetails(self, token, cover);
            } catch (Throwable t) {
                NativeLog.e(TAG, "App info round-trip failed: " + t);
                finishCancelled(token, cover);
            } finally {
                IN_FLIGHT.set(false);
            }
        });
        return true;
    }

    /** The permission groups the App info alert shows for `m`: every storage
     *  group the app requests at this level, except one no answer could change
     *  (STORAGE for a T+ app on a T+ platform). Name -> members. */
    static java.util.LinkedHashMap<String, java.util.Set<String>> appDetailsGroups(
            SpecialAccessStore.PermModel m) {
        java.util.LinkedHashMap<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
        if (m == null || m.legacyInstallGrant()) return out;
        for (String p : m.requested) {
            String g = m.promptGroup(p);
            if (g == null) continue;
            if (SpecialAccessStore.GROUP_STORAGE.equals(g) && m.level >= 33 && m.target >= 33) continue;
            out.computeIfAbsent(g, k -> m.members(k));
        }
        return out;
    }

    /** The helper argv for the App info alert. */
    static java.util.List<String> appDetailsArgv(java.io.File helper, int level,
            java.util.Map<String, java.util.Set<String>> groups, java.util.List<String> ops) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.add("app-details");
        argv.add("--source");
        argv.add("android");
        argv.add("--level");
        argv.add(Integer.toString(level));
        for (java.util.Map.Entry<String, java.util.Set<String>> e : groups.entrySet()) {
            argv.add("--group");
            argv.add(SpecialAccessStore.shortGroup(e.getKey()) + "=" + String.join(",", e.getValue()));
        }
        for (String op : ops) { argv.add("--op"); argv.add(op); }
        argv.add("--json");
        return argv;
    }

    private static void performAppDetails(String self, Activity.MacApkHostResult token,
                                          Cover cover) throws Exception {
        SpecialAccessStore.invalidate();
        SpecialAccessStore.PermModel m = SpecialAccessStore.liveModel();
        java.util.LinkedHashMap<String, java.util.Set<String>> groups = appDetailsGroups(m);
        java.util.List<String> ops = new java.util.ArrayList<>();
        if (SpecialAccessStore.honest()) {
            for (String op : new String[] { SpecialAccessStore.OP_MANAGE_EXTERNAL_STORAGE,
                                            SpecialAccessStore.OP_MANAGE_MEDIA }) {
                if (SpecialAccessStore.declaresPermission(SpecialAccessStore.permissionForOp(op))) {
                    ops.add(op);
                }
            }
        }
        java.util.Map<String, Boolean> before = grantSnapshot(m, ops);
        java.io.File helper = PackageInstallerBackend.locateHelper();
        if (helper == null) {
            NativeLog.e(TAG, "macapk-installhelper not found; App info cannot be shown");
        } else {
            int level = m != null ? m.level : android.os.Build.VERSION.SDK_INT;
            HelperRun r = runHelperVerb(appDetailsArgv(helper, level, groups, ops));
            NativeLog.i(TAG, "App info: helper exit " + r.exit + " " + r.lastLine());
        }
        SpecialAccessStore.invalidate();
        java.util.Map<String, Boolean> after = grantSnapshot(SpecialAccessStore.liveModel(), ops);
        NativeLog.i(TAG, "App info: before " + before + " after " + after);
        for (String op : ops) {
            if (!java.util.Objects.equals(before.get(op), after.get(op))) {
                android.app.AppOpsManager.macapkDispatchOpChanged(op, self);
            }
        }
        String revoked = revokedAny(before, after);
        if (revoked != null) {
            killAndRelaunch(revoked + " was turned off in App info: ending the process as"
                    + " AOSP does for a revoked runtime permission (killUid) or op 92"
                    + " (killAppForOpChange), and relaunching " + self, self);   // does not return
        }
        finishCancelled(token, cover);
    }

    /** Name -> allowed, for every storage permission the app requests and
     *  every special-access op listed. */
    static java.util.Map<String, Boolean> grantSnapshot(SpecialAccessStore.PermModel m,
                                                        java.util.List<String> ops) {
        java.util.Map<String, Boolean> out = new java.util.TreeMap<>();
        if (m != null) for (String p : m.requested) out.put(p, m.granted(p));
        for (String op : ops) {
            out.put(op, SpecialAccessStore.specialOpMode(op) == SpecialAccessStore.MODE_ALLOWED);
        }
        return out;
    }

    /** The first permission or op that went from allowed to not allowed, or
     *  null. Op 110 (media management) has no kill rule on a device. */
    static String revokedAny(java.util.Map<String, Boolean> before,
                             java.util.Map<String, Boolean> after) {
        for (java.util.Map.Entry<String, Boolean> e : before.entrySet()) {
            if (SpecialAccessStore.OP_MANAGE_MEDIA.equals(e.getKey())) continue;
            if (Boolean.TRUE.equals(e.getValue()) && !Boolean.TRUE.equals(after.get(e.getKey()))) {
                return e.getKey();
            }
        }
        return null;
    }

    // ==== running the helper's S5 verbs ========================================

    /** A helper run: exit code and its (small) output. */
    static final class HelperRun {
        final int exit;
        final String out;
        HelperRun(int exit, String out) { this.exit = exit; this.out = out; }
        String lastLine() {
            String t = out == null ? "" : out.trim();
            int i = t.lastIndexOf('\n');
            return i >= 0 ? t.substring(i + 1) : t;
        }
    }

    /** Run one helper verb to completion. Its only output is a few log lines
     *  and the one-line --json object, so a bounded in-memory drain is enough
     *  (PackageInstallerBackend's transcript file exists for the install verb's
     *  pages of output). The wait is bounded by the helper's own 300 s alert
     *  timeout. */
    static HelperRun runHelperVerb(java.util.List<String> argv) throws Exception {
        NativeLog.i(TAG, "helper: " + String.join(" ", argv));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (java.io.InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (sb.length() < 65536) sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        int exit = p.waitFor();
        return new HelperRun(exit, sb.toString());
    }

    /** StorageManagerService.killAppForOpChange (sms14 :4838-4845): op 92 went
     *  from ALLOWED to something else, so the process dies. The relaunch of the
     *  app's launcher entry stands in for the user returning from Settings to a
     *  task that restarts; it goes through the same native exit + relaunch the
     *  ProcessPhoenix path uses (MacApkProcessExit rule b). */
    private static void killForOpChange(String op, String pkg) {
        killAndRelaunch(op + " changed away from MODE_ALLOWED: ending the process as"
                + " StorageManagerService.killAppForOpChange does, and relaunching " + pkg, pkg);
    }

    /** End this process and relaunch the app's launcher entry (see
     *  killForOpChange). Does not return. */
    private static void killAndRelaunch(String why, String pkg) {
        NativeLog.i(TAG, why);
        String file = null;
        try {
            Manifest m = Boot.macapkManifest();
            if (m != null && m.launchableActivity != null && pkg != null) {
                Intent in = new Intent(Intent.ACTION_MAIN);
                in.addCategory(m.launchableCategory);
                in.setComponent(new android.content.ComponentName(pkg, m.launchableActivity));
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.os.Parcel p = android.os.Parcel.obtain();
                p.writeInt(1);
                in.writeToParcel(p, 0);
                java.io.File f = java.io.File.createTempFile("macapk-restart-", ".parcel");
                java.nio.file.Files.write(f.toPath(), p.marshall());
                file = f.getAbsolutePath();
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "could not stage the relaunch (" + t + "); exiting without it");
            file = null;
        }
        try {
            NativeCocoa.exitProcess(0, file);
        } catch (Throwable t) {
            // No native exit (an offline JVM): the process still has to die.
        }
        Runtime.getRuntime().halt(0);
    }

    private static String selfPackage() {
        Manifest m = Boot.macapkManifest();
        if (m != null && m.packageName != null && !m.packageName.isEmpty()) return m.packageName;
        String p = android.app.ActivityThread.currentPackageName();
        return (p == null || p.isEmpty()) ? null : p;
    }

    private static String modeName(int mode) {
        switch (mode) {
            case SpecialAccessStore.MODE_ALLOWED: return "MODE_ALLOWED";
            case SpecialAccessStore.MODE_IGNORED: return "MODE_IGNORED";
            case SpecialAccessStore.MODE_ERRORED: return "MODE_ERRORED";
            case SpecialAccessStore.MODE_DEFAULT: return "MODE_DEFAULT";
            default: return "mode " + mode;
        }
    }
}
