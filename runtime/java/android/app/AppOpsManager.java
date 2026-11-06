// android.app.AppOpsManager -- runtime app-permission tracking. Apps
// query "is THIS permission op allowed for THIS package?" via
// AppOpsManagerCompat.permissionToOp(...) which is what F-Droid's
// WifiStateChangeService.registerReceiver chain calls during
// Application onCreate.
//
// We don't have a general op-tracking system; an op with a runtime-permission
// row is treated as granted (MODE_ALLOWED), its AOSP row default. That matches
// the install-time permission model the rest of the runtime uses (see
// MacApkPackageManager.checkPermission).
//
// The carve-outs are the ops a device does NOT hand out by default:
//   - GET_USAGE_STATS -- see macapkModeFor below. It is not a runtime
//     permission at all on a real device, and answering MODE_ALLOWED while
//     UsageStatsManager hands back an empty list leaves the app unable to tell
//     "no history yet" from "the user never enabled Usage Access".
//   - Stage S1 (docs/shared-storage-real-mac-folders-design_2026-09-25.md):
//     op 92 MANAGE_EXTERNAL_STORAGE and op 110 MANAGE_MEDIA answer what the
//     user granted in macapk-installhelper's out-of-process consent dialog,
//     else MODE_DEFAULT; op 87 LEGACY_STORAGE is computed from the manifest the
//     way PermissionPolicyService sets it at install; the storage rows AOSP
//     defaults to MODE_ERRORED (WRITE_MEDIA_*, NO_ISOLATED_STORAGE) say so; and
//     setMode/setUidMode throw SecurityException as AppOpsService does for a
//     caller without MANAGE_APP_OPS_MODES. The read side is
//     com.macapk.loader.SpecialAccessStore. MACAPK_ALLFILES_HONEST=0 restores
//     the pre-S1 table in one switch.
//
// AOSP reference:
//   frameworks/base/core/java/android/app/AppOpsManager.java
//   frameworks/base/core/java/android/app/AppOpInfo.java
package android.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class AppOpsManager {

    // Mode constants (AOSP)
    public static final int MODE_ALLOWED          = 0;
    public static final int MODE_IGNORED          = 1;
    public static final int MODE_ERRORED          = 2;
    public static final int MODE_DEFAULT          = 3;
    public static final int MODE_FOREGROUND       = 4;

    // Common op-string constants used by AppOpsManagerCompat
    public static final String OPSTR_COARSE_LOCATION  = "android:coarse_location";
    public static final String OPSTR_FINE_LOCATION    = "android:fine_location";
    public static final String OPSTR_CAMERA           = "android:camera";
    public static final String OPSTR_RECORD_AUDIO     = "android:record_audio";
    public static final String OPSTR_READ_CONTACTS    = "android:read_contacts";
    public static final String OPSTR_WRITE_CONTACTS   = "android:write_contacts";
    public static final String OPSTR_GET_USAGE_STATS  = "android:get_usage_stats";
    public static final String OPSTR_READ_PHONE_STATE = "android:read_phone_state";
    // The op names behind the Bluetooth and notification runtime permissions
    // (API 31 / 33). Values verified against AppOpsManager.java L1932, L2043-2047.
    public static final String OPSTR_POST_NOTIFICATION    = "android:post_notification";
    public static final String OPSTR_BLUETOOTH_SCAN       = "android:bluetooth_scan";
    public static final String OPSTR_BLUETOOTH_CONNECT    = "android:bluetooth_connect";
    public static final String OPSTR_BLUETOOTH_ADVERTISE  = "android:bluetooth_advertise";
    public static final String OPSTR_READ_EXTERNAL_STORAGE  = "android:read_external_storage";
    public static final String OPSTR_WRITE_EXTERNAL_STORAGE = "android:write_external_storage";
    public static final String OPSTR_ACCESS_MEDIA_LOCATION  = "android:access_media_location";
    public static final String OPSTR_BODY_SENSORS           = "android:body_sensors";
    public static final String OPSTR_ACTIVITY_RECOGNITION   = "android:activity_recognition";

    // ---- Stage S1: the storage op rows, AOSP verbatim ----------------------
    // Values from frameworks/base/core/java/android/app/AppOpsManager.java
    // (android14-release; identical ids in android16): the OPSTR_ literals at
    // :1888-1906, :1919, :1960, :2056, :2117, and the ids as each op's index in
    // sAppOpInfos (the array IS indexed by op id, :2819-2822) -- counted from
    // the fetched source, both branches: READ/WRITE_EXTERNAL_STORAGE 59/60,
    // READ/WRITE_MEDIA_AUDIO 81/82, _VIDEO 83/84, _IMAGES 85/86,
    // LEGACY_STORAGE 87, ACCESS_MEDIA_LOCATION 90, MANAGE_EXTERNAL_STORAGE 92,
    // NO_ISOLATED_STORAGE 99, MANAGE_MEDIA 110, READ_MEDIA_VISUAL_USER_SELECTED
    // 123, GET_USAGE_STATS 43. AOSP Environment.isExternalStorageManager calls
    // checkOpNoThrow(int OP_MANAGE_EXTERNAL_STORAGE, ...), which is why the int
    // forms exist here at all; apps use the String API.
    public static final int OP_NONE                            = -1;
    public static final int OP_GET_USAGE_STATS                 = 43;
    public static final int OP_READ_EXTERNAL_STORAGE           = 59;
    public static final int OP_WRITE_EXTERNAL_STORAGE          = 60;
    public static final int OP_READ_MEDIA_AUDIO                = 81;
    public static final int OP_WRITE_MEDIA_AUDIO               = 82;
    public static final int OP_READ_MEDIA_VIDEO                = 83;
    public static final int OP_WRITE_MEDIA_VIDEO               = 84;
    public static final int OP_READ_MEDIA_IMAGES               = 85;
    public static final int OP_WRITE_MEDIA_IMAGES              = 86;
    public static final int OP_LEGACY_STORAGE                  = 87;
    public static final int OP_ACCESS_MEDIA_LOCATION           = 90;
    public static final int OP_MANAGE_EXTERNAL_STORAGE         = 92;
    public static final int OP_NO_ISOLATED_STORAGE             = 99;
    public static final int OP_MANAGE_MEDIA                    = 110;
    public static final int OP_READ_MEDIA_VISUAL_USER_SELECTED = 123;

    public static final String OPSTR_READ_MEDIA_AUDIO   = "android:read_media_audio";
    public static final String OPSTR_WRITE_MEDIA_AUDIO  = "android:write_media_audio";
    public static final String OPSTR_READ_MEDIA_VIDEO   = "android:read_media_video";
    public static final String OPSTR_WRITE_MEDIA_VIDEO  = "android:write_media_video";
    public static final String OPSTR_READ_MEDIA_IMAGES  = "android:read_media_images";
    public static final String OPSTR_WRITE_MEDIA_IMAGES = "android:write_media_images";
    public static final String OPSTR_LEGACY_STORAGE     = "android:legacy_storage";
    public static final String OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage";
    public static final String OPSTR_NO_ISOLATED_STORAGE = "android:no_isolated_storage";
    public static final String OPSTR_MANAGE_MEDIA       = "android:manage_media";
    public static final String OPSTR_READ_MEDIA_VISUAL_USER_SELECTED =
            "android:read_media_visual_user_selected";

    /** (op id, OPSTR, AOSP simpleName) for the rows above -- the slice of
     *  sAppOpInfos this class models by number. An id not listed here is one we
     *  have no row for. */
    private static final Object[][] MACAPK_INT_OPS = {
        { OP_GET_USAGE_STATS,                 OPSTR_GET_USAGE_STATS,                 "GET_USAGE_STATS" },
        { OP_READ_EXTERNAL_STORAGE,           OPSTR_READ_EXTERNAL_STORAGE,           "READ_EXTERNAL_STORAGE" },
        { OP_WRITE_EXTERNAL_STORAGE,          OPSTR_WRITE_EXTERNAL_STORAGE,          "WRITE_EXTERNAL_STORAGE" },
        { OP_READ_MEDIA_AUDIO,                OPSTR_READ_MEDIA_AUDIO,                "READ_MEDIA_AUDIO" },
        { OP_WRITE_MEDIA_AUDIO,               OPSTR_WRITE_MEDIA_AUDIO,               "WRITE_MEDIA_AUDIO" },
        { OP_READ_MEDIA_VIDEO,                OPSTR_READ_MEDIA_VIDEO,                "READ_MEDIA_VIDEO" },
        { OP_WRITE_MEDIA_VIDEO,               OPSTR_WRITE_MEDIA_VIDEO,               "WRITE_MEDIA_VIDEO" },
        { OP_READ_MEDIA_IMAGES,               OPSTR_READ_MEDIA_IMAGES,               "READ_MEDIA_IMAGES" },
        { OP_WRITE_MEDIA_IMAGES,              OPSTR_WRITE_MEDIA_IMAGES,              "WRITE_MEDIA_IMAGES" },
        { OP_LEGACY_STORAGE,                  OPSTR_LEGACY_STORAGE,                  "LEGACY_STORAGE" },
        { OP_ACCESS_MEDIA_LOCATION,           OPSTR_ACCESS_MEDIA_LOCATION,           "ACCESS_MEDIA_LOCATION" },
        { OP_MANAGE_EXTERNAL_STORAGE,         OPSTR_MANAGE_EXTERNAL_STORAGE,         "MANAGE_EXTERNAL_STORAGE" },
        { OP_NO_ISOLATED_STORAGE,             OPSTR_NO_ISOLATED_STORAGE,             "NO_ISOLATED_STORAGE" },
        { OP_MANAGE_MEDIA,                    OPSTR_MANAGE_MEDIA,                    "MANAGE_MEDIA" },
        { OP_READ_MEDIA_VISUAL_USER_SELECTED, OPSTR_READ_MEDIA_VISUAL_USER_SELECTED, "READ_MEDIA_VISUAL_USER_SELECTED" },
    };

    private static Object[] macapkIntOpRow(int op) {
        for (Object[] r : MACAPK_INT_OPS) if (((Integer) r[0]) == op) return r;
        return null;
    }

    /** AOSP opToPublicName (@hide): the OPSTR for an op id. AOSP indexes its
     *  table directly and so throws ArrayIndexOutOfBounds for an id past the
     *  end; for an id we have no row for we throw the same class. */
    public static String opToPublicName(int op) {
        Object[] r = macapkIntOpRow(op);
        if (r == null) throw new ArrayIndexOutOfBoundsException(op);
        return (String) r[1];
    }

    /** AOSP opToName (@hide): the debug name; "NONE" for OP_NONE,
     *  "Unknown(n)" for an id with no row (AOSP :2868-2871). */
    public static String opToName(int op) {
        if (op == OP_NONE) return "NONE";
        Object[] r = macapkIntOpRow(op);
        return r != null ? (String) r[2] : ("Unknown(" + op + ")");
    }

    /** AOSP strOpToOp (@hide, :8169-8175): IllegalArgumentException("Unknown
     *  operation string: " + op) for a string with no row. */
    public static int strOpToOp(String op) {
        for (Object[] r : MACAPK_INT_OPS) if (r[1].equals(op)) return (Integer) r[0];
        throw new IllegalArgumentException("Unknown operation string: " + op);
    }

    /** AOSP permissionToOpCode (@hide): OP_NONE when the permission has no op. */
    public static int permissionToOpCode(String permission) {
        String op = permissionToOp(permission);
        if (op == null) return OP_NONE;
        for (Object[] r : MACAPK_INT_OPS) if (r[1].equals(op)) return (Integer) r[0];
        return OP_NONE;
    }

    /** The int-op forms route through the String table. An id with no row here
     *  answers the pre-S1 blanket MODE_ALLOWED -- no worse than before these
     *  overloads existed, when such a call was a NoSuchMethodError. */
    private static String macapkOpStr(int op) {
        Object[] r = macapkIntOpRow(op);
        return r != null ? (String) r[1] : null;
    }

    public AppOpsManager() {}
    public AppOpsManager(Context context) {}

    /** AOSP signature -- map a manifest-style permission name to an
     *  op string. We honor the well-known mappings F-Droid + similar
     *  apps query for; everything else returns null (which AOSP also
     *  does for un-mapped permissions, meaning the op-check is a
     *  no-op).
     *
     *  Each entry below is one row of AOSP's sAppOpInfos table, where the
     *  permission-to-op edge is declared by AppOpInfo.Builder.setPermission:
     *  POST_NOTIFICATION L2730, GET_USAGE_STATS L2836, BODY_SENSORS L2873,
     *  READ/WRITE_EXTERNAL_STORAGE L2881/L2884, BLUETOOTH_SCAN L2942,
     *  ACTIVITY_RECOGNITION L2950, ACCESS_MEDIA_LOCATION L2978,
     *  BLUETOOTH_CONNECT L3031, BLUETOOTH_ADVERTISE L3041. */
    public static String permissionToOp(String permission) {
        if (permission == null) return null;
        switch (permission) {
            case "android.permission.ACCESS_COARSE_LOCATION": return OPSTR_COARSE_LOCATION;
            case "android.permission.ACCESS_FINE_LOCATION":   return OPSTR_FINE_LOCATION;
            case "android.permission.CAMERA":                 return OPSTR_CAMERA;
            case "android.permission.RECORD_AUDIO":           return OPSTR_RECORD_AUDIO;
            case "android.permission.READ_CONTACTS":          return OPSTR_READ_CONTACTS;
            case "android.permission.WRITE_CONTACTS":         return OPSTR_WRITE_CONTACTS;
            case "android.permission.PACKAGE_USAGE_STATS":    return OPSTR_GET_USAGE_STATS;
            case "android.permission.READ_PHONE_STATE":       return OPSTR_READ_PHONE_STATE;
            case "android.permission.POST_NOTIFICATIONS":     return OPSTR_POST_NOTIFICATION;
            case "android.permission.BLUETOOTH_SCAN":         return OPSTR_BLUETOOTH_SCAN;
            case "android.permission.BLUETOOTH_CONNECT":      return OPSTR_BLUETOOTH_CONNECT;
            case "android.permission.BLUETOOTH_ADVERTISE":    return OPSTR_BLUETOOTH_ADVERTISE;
            case "android.permission.READ_EXTERNAL_STORAGE":  return OPSTR_READ_EXTERNAL_STORAGE;
            case "android.permission.WRITE_EXTERNAL_STORAGE": return OPSTR_WRITE_EXTERNAL_STORAGE;
            case "android.permission.ACCESS_MEDIA_LOCATION":  return OPSTR_ACCESS_MEDIA_LOCATION;
            case "android.permission.BODY_SENSORS":           return OPSTR_BODY_SENSORS;
            case "android.permission.ACTIVITY_RECOGNITION":   return OPSTR_ACTIVITY_RECOGNITION;
            default: break;
        }
        // Stage S1: the storage rows' setPermission edges (AppOpsManager.java
        // android14 :2620-2631 READ_MEDIA_*, :2647 MANAGE_EXTERNAL_STORAGE,
        // :2692 MANAGE_MEDIA, :2729-2731 READ_MEDIA_VISUAL_USER_SELECTED).
        // MediaStore.canManageMedia resolves its op through exactly this call.
        // Gated with the rest of S1: with MACAPK_ALLFILES_HONEST=0 these
        // permissions map to no op, as before.
        if (!com.macapk.loader.SpecialAccessStore.honest()) return null;
        switch (permission) {
            case "android.permission.READ_MEDIA_AUDIO":   return OPSTR_READ_MEDIA_AUDIO;
            case "android.permission.READ_MEDIA_VIDEO":   return OPSTR_READ_MEDIA_VIDEO;
            case "android.permission.READ_MEDIA_IMAGES":  return OPSTR_READ_MEDIA_IMAGES;
            case "android.permission.MANAGE_EXTERNAL_STORAGE": return OPSTR_MANAGE_EXTERNAL_STORAGE;
            case "android.permission.MANAGE_MEDIA":       return OPSTR_MANAGE_MEDIA;
            case "android.permission.READ_MEDIA_VISUAL_USER_SELECTED":
                return OPSTR_READ_MEDIA_VISUAL_USER_SELECTED;
            default: return null;
        }
    }

    /** The mode a never-explicitly-set op reports. AppOpsService has no stored
     *  entry for an op nobody has toggled, so it answers with that op's
     *  DEFAULT mode -- AppOpInfo.Builder.mDefaultMode, which starts life as
     *  MODE_DEFAULT (AppOpInfo.java:136) and is overridden per row by
     *  setDefaultMode(). Almost every row we care about sets MODE_ALLOWED, so
     *  MODE_ALLOWED stays the answer here.
     *
     *  GET_USAGE_STATS is the exception: its row (AppOpsManager.java:2835-2836)
     *  declares NO default mode, so it keeps the builder's MODE_DEFAULT.
     *  PACKAGE_USAGE_STATS is a special access the user can only grant by hand
     *  in Settings > Special app access > Usage access, and the canonical app
     *  idiom keys on exactly this value:
     *      if (mode == MODE_DEFAULT) granted = checkCallingOrSelfPermission(...)
     *      else                      granted = (mode == MODE_ALLOWED);
     *  Reporting MODE_ALLOWED told the app it holds Usage Access while
     *  UsageStatsManager.queryUsageStats returns an empty list -- the app then
     *  cannot distinguish "this device has no usage history" from "you need to
     *  send the user to Settings", and shows the wrong (or no) UI. */
    public static int macapkModeFor(String op) {
        if (OPSTR_GET_USAGE_STATS.equals(op)) return MODE_DEFAULT;
        if (op == null || !com.macapk.loader.SpecialAccessStore.honest()) return MODE_ALLOWED;
        switch (op) {
            // Special app access (stage S1): what the user granted in the
            // out-of-process consent dialog, else MODE_DEFAULT (the rows set no
            // default; AppOpInfo.java:136). SpecialAccessStore also applies the
            // declared-permission guard.
            case OPSTR_MANAGE_EXTERNAL_STORAGE:
            case OPSTR_MANAGE_MEDIA:
                return com.macapk.loader.SpecialAccessStore.specialOpMode(op);
            // Set by PermissionPolicyService at install from the manifest; see
            // SpecialAccessStore.legacyStorageMode for the exact rule.
            case OPSTR_LEGACY_STORAGE:
                return com.macapk.loader.SpecialAccessStore.legacyStorageMode();
            // Row defaults MODE_ERRORED (AppOpsManager.java android14
            // :2622-2633, :2662-2664): the WRITE_MEDIA_* ops belong to the
            // system gallery role and NO_ISOLATED_STORAGE to instrumentation
            // run with --no-isolated-storage; a third-party app holds neither.
            case OPSTR_WRITE_MEDIA_AUDIO:
            case OPSTR_WRITE_MEDIA_VIDEO:
            case OPSTR_WRITE_MEDIA_IMAGES:
            case OPSTR_NO_ISOLATED_STORAGE:
                return MODE_ERRORED;
            default:
                return MODE_ALLOWED;
        }
    }

    /** AOSP buildSecurityExceptionMsg (AppOpsManager.java:8159-8162): what the
     *  THROWING check/note/start forms raise for a MODE_ERRORED op. */
    private static SecurityException macapkOpDenied(String op, int uid, String packageName) {
        String simple = op;
        for (Object[] r : MACAPK_INT_OPS) if (r[1].equals(op)) { simple = (String) r[2]; break; }
        return new SecurityException(packageName + " from uid " + uid
                + " not allowed to perform " + simple);
    }
    /** The throwing forms: AOSP checkOp/noteOp/startOp throw for MODE_ERRORED
     *  (AppOpsManager.java:8658-8668) where the NoThrow forms return it. Before
     *  stage S1 no op here ever answered MODE_ERRORED, so this could not fire;
     *  with MACAPK_ALLFILES_HONEST=0 it still cannot. */
    private static int macapkThrowing(String op, int uid, String packageName) {
        int mode = macapkModeFor(op);
        if (mode == MODE_ERRORED) throw macapkOpDenied(op, uid, packageName);
        return mode;
    }

    public int noteOp(String op, int uid, String packageName) {
        return macapkThrowing(op, uid, packageName);
    }
    public int noteOpNoThrow(String op, int uid, String packageName) { return macapkModeFor(op); }
    public int checkOp(String op, int uid, String packageName) {
        return macapkThrowing(op, uid, packageName);
    }
    public int checkOpNoThrow(String op, int uid, String packageName) { return macapkModeFor(op); }
    public int startOp(String op, int uid, String packageName) {
        int mode = macapkThrowing(op, uid, packageName);
        if (mode == MODE_ALLOWED) macapkDispatchActive(op, uid, packageName, true);
        return mode;
    }

    // ---- int-op forms (@hide in AOSP) ----
    // AOSP's own framework code uses these -- Environment.isExternalStorageManager
    // (checkOpNoThrow(OP_MANAGE_EXTERNAL_STORAGE, ...)) and isExternalStorageLegacy
    // (noteOpNoThrow(OP_LEGACY_STORAGE, ...)) -- so porting those bodies needs
    // them. Each maps the id to its OPSTR and asks the same table.
    public int checkOp(int op, int uid, String packageName) {
        String s = macapkOpStr(op);
        return s == null ? MODE_ALLOWED : macapkThrowing(s, uid, packageName);
    }
    public int checkOpNoThrow(int op, int uid, String packageName) {
        String s = macapkOpStr(op);
        return s == null ? MODE_ALLOWED : macapkModeFor(s);
    }
    public int noteOp(int op, int uid, String packageName) {
        String s = macapkOpStr(op);
        return s == null ? MODE_ALLOWED : macapkThrowing(s, uid, packageName);
    }
    public int noteOpNoThrow(int op, int uid, String packageName) {
        String s = macapkOpStr(op);
        return s == null ? MODE_ALLOWED : macapkModeFor(s);
    }
    public int noteOpNoThrow(int op, int uid, String packageName, String attributionTag,
            String message) {
        return noteOpNoThrow(op, uid, packageName);
    }
    public int unsafeCheckOpRawNoThrow(int op, int uid, String packageName) {
        return checkOpNoThrow(op, uid, packageName);
    }
    public int startOpNoThrow(String op, int uid, String packageName) {
        // The NoThrow form returns MODE_ERRORED where startOp throws it.
        int mode = macapkModeFor(op);
        if (mode == MODE_ALLOWED) macapkDispatchActive(op, uid, packageName, true);
        return mode;
    }
    public void finishOp(String op, int uid, String packageName) {
        macapkDispatchActive(op, uid, packageName, false);
    }
    public int unsafeCheckOp(String op, int uid, String packageName) {
        return macapkThrowing(op, uid, packageName);
    }
    public int unsafeCheckOpNoThrow(String op, int uid, String packageName) { return macapkModeFor(op); }
    /** AOSP :8237-8239: the RAW form never throws; it is unsafeCheckOpRawNoThrow. */
    public int unsafeCheckOpRaw(String op, int uid, String packageName) {
        return unsafeCheckOpRawNoThrow(op, uid, packageName);
    }
    public int unsafeCheckOpRawNoThrow(String op, int uid, String packageName) { return macapkModeFor(op); }

    // ---------------------------------------------------------------------
    // Proxy ops. A proxy op records that THIS app performed an operation on
    // behalf of another one; AOSP notes it against both identities and returns
    // MODE_IGNORED when the proxied (uid, package) pair does not check out
    // (AppOpsManager.java L9345-9347, L9434-9436, L9442-9444). Mac.apk runs a
    // single package in the process, so there is no second identity to
    // disagree with and the answer collapses to the plain note for the op.
    // ---------------------------------------------------------------------

    /** @deprecated AOSP deprecates the 2-arg form in favor of the
     *  attribution-aware overload; kept because that is what the bundled
     *  androidx.core AppOpsManagerCompat calls. */
    @Deprecated
    public int noteProxyOp(String op, String proxiedPackageName) {
        return noteProxyOp(op, proxiedPackageName, android.os.Process.myUid(), null, null);
    }
    public int noteProxyOp(String op, String proxiedPackageName, int proxiedUid,
                           String proxiedAttributionTag, String message) {
        return noteOp(op, proxiedUid, proxiedPackageName);
    }
    /** @deprecated see {@link #noteProxyOp(String, String)}. */
    @Deprecated
    public int noteProxyOpNoThrow(String op, String proxiedPackageName) {
        return noteProxyOpNoThrow(op, proxiedPackageName, android.os.Process.myUid(), null, null);
    }
    /** @deprecated see {@link #noteProxyOp(String, String)}. */
    @Deprecated
    public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid) {
        return noteProxyOpNoThrow(op, proxiedPackageName, proxiedUid, null, null);
    }
    public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid,
                                  String proxiedAttributionTag, String message) {
        return noteOpNoThrow(op, proxiedUid, proxiedPackageName);
    }

    /** AOSP: throws SecurityException when packageName does not belong to uid
     *  (AppOpsManager.java L9633-9642, message text copied verbatim). Content
     *  providers call this with a caller-supplied package name to reject
     *  spoofing, so it has to be able to say no.
     *
     *  Exactly one package exists in a Mac.apk process, and it owns
     *  Process.myUid(), so the package name is the part we can answer
     *  truthfully. The uid is only enforced when the caller passed a real app
     *  uid: Binder.getCallingUid() is inert (0) in this runtime, and callers
     *  routinely pass it straight through, so treating that 0 as a mismatch
     *  would manufacture a denial out of our own missing Binder identity
     *  rather than anything the app did -- the same reasoning Context
     *  .checkCallingPermission already documents. This tightens for free once
     *  Binder reports a real caller. */
    public void checkPackage(int uid, String packageName) {
        String own = ActivityThread.currentPackageName();
        // Before the Application exists there is no package identity to
        // compare against; AOSP is never in that state, so refusing here would
        // be inventing a denial nothing decided.
        if (own == null || own.isEmpty()) return;
        boolean packageMatches = own.equals(packageName);
        boolean uidMatches = uid == android.os.Process.myUid()
                || !android.os.Process.isApplicationUid(uid);
        if (packageMatches && uidMatches) return;
        // Another installed app's uid (a cross-app Binder caller): AppOpsService
        // .checkPackage resolves the package's uid through PMS and compares. Each
        // package's uid is the shared per-package table's (AppUids).
        if (packageName != null && com.macapk.loader.AppUids.enabled()) {
            String[] owners = com.macapk.loader.AppUids.packagesFor(uid);
            if (owners != null) for (String o : owners) if (packageName.equals(o)) return;
        }
        throw new SecurityException("Package " + packageName + " does not belong to " + uid);
    }

    /** AOSP setMode/setUidMode (@hide / @SystemApi, @RequiresPermission
     *  MANAGE_APP_OPS_MODES): AppOpsService.enforceManageAppOpsModes
     *  (android14 :1751-1767) calls Context.enforcePermission(
     *  MANAGE_APP_OPS_MODES, callingPid, callingUid, null) for any caller that
     *  is not system_server itself, and ContextImpl.enforce words the refusal
     *  "uid <uid> does not have <permission>." -- which reaches the app through
     *  Binder as this SecurityException. No third-party app holds that
     *  permission (signature|installer|verifier), so on a device an app can
     *  NEVER grant itself an op, All files access included. It used to be a
     *  silent sink here, which is the same "no" without the exception an app's
     *  catch block expects. The only writer of the special-access grants is
     *  macapk-installhelper (see com.macapk.loader.SpecialAccessStore).
     *  MACAPK_ALLFILES_HONEST=0 restores the sink. */
    private static void macapkRefuseSetMode() {
        if (!com.macapk.loader.SpecialAccessStore.honest()) return;
        throw new SecurityException("uid " + android.os.Process.myUid()
                + " does not have android.permission.MANAGE_APP_OPS_MODES.");
    }
    public void setMode(int code, int uid, String packageName, int mode) {
        macapkRefuseSetMode();
    }
    public void setMode(String op, int uid, String packageName, int mode) {
        macapkRefuseSetMode();
    }
    public void setUidMode(int code, int uid, int mode) {
        macapkRefuseSetMode();
    }
    public void setUidMode(String appOp, int uid, int mode) {
        macapkRefuseSetMode();
    }

    public interface OnOpChangedListener {
        void onOpChanged(String op, String packageName);
        /** AOSP's API-36 device-aware overload, a DEFAULT method whose base body
         *  forwards to the two-argument form -- so an existing listener keeps
         *  working while a device-aware one can override. Mac.apk hosts only the
         *  default device, so deviceId is always the default. */
        default void onOpChanged(String op, String packageName, int userId, String persistentDeviceId) {
            onOpChanged(op, packageName);
        }
    }
    /** AOSP startWatchingMode: "Monitor for changes to the operating mode for
     *  the given op in the given app package". Since stage S1 ONE thing can
     *  change a mode in this process -- the user answering the special-access
     *  consent (op 92 / op 110), which SpecialAccessHandler reports through
     *  macapkDispatchOpChanged. Registration is static for the same reason
     *  sActiveWatchers is: getSystemService hands out a fresh manager per call
     *  while a device has one app-ops service per process. A null op or package
     *  watches everything, as AOSP's does. */
    private static final java.util.List<Object[]> sModeWatchers =
            new java.util.ArrayList<>();   // {op, packageName, callback}
    public void startWatchingMode(String op, String packageName, OnOpChangedListener callback) {
        if (callback == null) return;
        synchronized (sModeWatchers) {
            sModeWatchers.add(new Object[] { op, packageName, callback });
        }
    }
    /** AOSP startWatchingMode(String op, String packageName, int flags,
     *  OnOpChangedListener): the flags-taking overload (WATCH_FOREGROUND_CHANGES).
     *  Foreground-mode transitions do not exist here, so the flags change
     *  nothing. */
    public void startWatchingMode(String op, String packageName, int flags,
            OnOpChangedListener callback) {
        startWatchingMode(op, packageName, callback);
    }
    public void stopWatchingMode(OnOpChangedListener callback) {
        synchronized (sModeWatchers) {
            sModeWatchers.removeIf(w -> w[2] == callback);
        }
    }
    /** Loader hook (not AOSP API): the mode of `op` for `packageName` changed.
     *  AOSP delivers IAppOpsCallback.opChanged on a Binder thread, so the
     *  caller's own (non-main) thread is the faithful place to run the
     *  listeners; one that throws cannot stop the others, as a one-way Binder
     *  call could not. */
    public static void macapkDispatchOpChanged(String op, String packageName) {
        java.util.List<Object[]> targets;
        synchronized (sModeWatchers) {
            if (sModeWatchers.isEmpty()) return;
            targets = new java.util.ArrayList<>(sModeWatchers);
        }
        for (Object[] w : targets) {
            if (w[0] != null && !w[0].equals(op)) continue;
            if (w[1] != null && packageName != null && !w[1].equals(packageName)) continue;
            try { ((OnOpChangedListener) w[2]).onOpChanged(op, packageName); }
            catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------------
    // Active-op watching. AOSP AppOpsManager.java L7585-7594 (the listener)
    // and L8592-8648 / L8654-8664 (start/stopWatchingActive). A long-running
    // op has explicit start and finish delimiters, and every registered
    // watcher whose op set covers it is told when it flips.
    // ---------------------------------------------------------------------

    public interface OnOpActiveChangedListener {
        void onOpActiveChanged(String op, int uid, String packageName, boolean active);
        /** AOSP's API-36 device-aware overload, a DEFAULT method forwarding to
         *  the four-argument form. Mac.apk hosts only the default device and one
         *  attribution, so the extra arguments carry no additional information. */
        default void onOpActiveChanged(String op, int uid, String packageName,
                String attributionTag, int virtualDeviceId, boolean active,
                int attributionFlags, int attributionChainId) {
            onOpActiveChanged(op, uid, packageName, active);
        }
    }

    /** The registry is static because Context.getSystemService(APP_OPS_SERVICE)
     *  hands back a fresh AppOpsManager every call in this runtime, while
     *  AppOps is one system service per process on a device: a watcher
     *  registered through one handle has to be visible to startOp and
     *  removable through another. */
    private static final Map<OnOpActiveChangedListener, MacApkActiveWatcher> sActiveWatchers =
            new HashMap<>();

    private static final class MacApkActiveWatcher {
        final String[] ops;
        final Executor executor;
        MacApkActiveWatcher(String[] ops, Executor executor) {
            this.ops = ops;
            this.executor = executor;
        }
        boolean watches(String op) {
            for (String candidate : ops) {
                if (candidate != null && candidate.equals(op)) return true;
            }
            return false;
        }
    }

    public void startWatchingActive(String[] ops, Executor executor,
                                    OnOpActiveChangedListener callback) {
        if (ops == null) throw new NullPointerException("ops");
        if (executor == null) throw new NullPointerException("executor");
        if (callback == null) throw new NullPointerException("callback");
        synchronized (sActiveWatchers) {
            // AOSP returns early rather than re-registering; the documented way
            // to change the watched set is unregister then register again.
            if (sActiveWatchers.containsKey(callback)) return;
            sActiveWatchers.put(callback, new MacApkActiveWatcher(ops.clone(), executor));
        }
    }

    /** Unregistering a callback that was never registered has no effect. */
    public void stopWatchingActive(OnOpActiveChangedListener callback) {
        synchronized (sActiveWatchers) {
            sActiveWatchers.remove(callback);
        }
    }

    /** The set of ops this process currently has OPEN (startOp without a
     *  matching finishOp), keyed the way AOSP's AppOpsService keys them:
     *  (op, uid, package). isOpActive() reads it. Static for the same reason
     *  sActiveWatchers is: one app-ops service per process, many manager
     *  handles. */
    private static final java.util.Set<String> sActiveOps =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String activeKey(String op, int uid, String packageName) {
        return op + "\u0000" + uid + "\u0000" + packageName;
    }

    /** AOSP isOpActive's underlying question: is there an outstanding startOp
     *  for this (op, uid, package)? */
    private static boolean macapkIsActive(String op, int uid, String packageName) {
        return sActiveOps.contains(activeKey(op, uid, packageName));
    }

    private static void macapkDispatchActive(String op, int uid, String packageName,
                                             boolean active) {
        // Record the transition BEFORE notifying, so a watcher that calls
        // isOpActive() from its own callback sees the state the callback is
        // announcing -- the ordering AOSP's AppOpsService uses.
        if (active) sActiveOps.add(activeKey(op, uid, packageName));
        else sActiveOps.remove(activeKey(op, uid, packageName));
        List<Map.Entry<OnOpActiveChangedListener, MacApkActiveWatcher>> targets;
        synchronized (sActiveWatchers) {
            if (sActiveWatchers.isEmpty()) return;
            targets = new ArrayList<>(sActiveWatchers.entrySet());
        }
        for (Map.Entry<OnOpActiveChangedListener, MacApkActiveWatcher> e : targets) {
            MacApkActiveWatcher w = e.getValue();
            if (!w.watches(op)) continue;
            // AOSP delivers this over a one-way Binder callback, so a watcher
            // that misbehaves can never break the startOp/finishOp call that
            // triggered it. Swallowing here reproduces that isolation.
            try {
                w.executor.execute(() -> e.getKey().onOpActiveChanged(op, uid, packageName, active));
            } catch (Throwable ignored) { }
        }
    }

    // ================================================================
    // AOSP API-36 surface completion (attribution-tag + message overloads).
    // AOSP ref: frameworks/base/core/java/android/app/AppOpsManager.java
    // (android16-release). Every three-argument form above is DEPRECATED there
    // in favour of one of these, which additionally carry the attribution tag
    // and a developer message for the noted-op log. Mac.apk keeps no per-op
    // state and no attribution registry, so the extra arguments do not change
    // the answer -- but the descriptors must exist or an app compiled against
    // API 30+ takes a NoSuchMethodError on the modern spelling.
    // ================================================================

    /** AOSP checkOp(String, int, String, String attributionTag). */
    public int checkOp(String op, int uid, String packageName, String attributionTag) {
        return checkOp(op, uid, packageName);
    }
    /** AOSP checkOpNoThrow(String, int, String, String attributionTag). */
    public int checkOpNoThrow(String op, int uid, String packageName, String attributionTag) {
        return checkOpNoThrow(op, uid, packageName);
    }
    /** AOSP checkOpRawNoThrow(String, int, String, String attributionTag): the
     *  RAW form does not collapse MODE_FOREGROUND, which is why it is separate
     *  from checkOpNoThrow. unsafeCheckOpRawNoThrow above is the older spelling
     *  of the same query, so route through it rather than duplicating the
     *  default-mode logic. */
    public int checkOpRawNoThrow(String op, int uid, String packageName, String attributionTag) {
        return unsafeCheckOpRawNoThrow(op, uid, packageName);
    }

    /** AOSP noteOp(String op, int uid, String packageName, String attributionTag,
     *  String message). */
    public int noteOp(String op, int uid, String packageName, String attributionTag,
            String message) {
        return noteOp(op, uid, packageName);
    }
    /** AOSP noteOpNoThrow(String, int, String, String, String). */
    public int noteOpNoThrow(String op, int uid, String packageName, String attributionTag,
            String message) {
        return noteOpNoThrow(op, uid, packageName);
    }

    /** AOSP startOp(String, int, String, String attributionTag, String message):
     *  routes through the three-argument form, which is what dispatches the
     *  active-op watchers registered by startWatchingActive. */
    public int startOp(String op, int uid, String packageName, String attributionTag,
            String message) {
        return startOp(op, uid, packageName);
    }
    /** AOSP startOpNoThrow(String, int, String, String, String). */
    public int startOpNoThrow(String op, int uid, String packageName, String attributionTag,
            String message) {
        return startOpNoThrow(op, uid, packageName);
    }
    /** AOSP finishOp(String, int, String, String attributionTag): the paired
     *  finish for the start above; routes through the three-argument form so the
     *  active-op watchers see the flip back to inactive. */
    public void finishOp(String op, int uid, String packageName, String attributionTag) {
        finishOp(op, uid, packageName);
    }

    /** AOSP isOpActive(String op, int uid, String packageName): "Checks if the
     *  given op is currently active for the given package", i.e. whether a
     *  startOp is outstanding. This class already tracks exactly that in order
     *  to drive the active-op watchers, so answer from the same state rather
     *  than a constant -- an app polling this after its own startOp must see
     *  true. */
    public boolean isOpActive(String op, int uid, String packageName) {
        return macapkIsActive(op, uid, packageName);
    }

    /** AOSP startProxyOp(String op, int proxiedUid, String proxiedPackageName,
     *  String proxiedAttributionTag, String message): the long-running form of
     *  noteProxyOp. Delegates to the proxy note above, which carries the
     *  proxied-identity validation AOSP performs. */
    public int startProxyOp(String op, int proxiedUid, String proxiedPackageName,
            String proxiedAttributionTag, String message) {
        return noteProxyOp(op, proxiedPackageName, proxiedUid, proxiedAttributionTag, message);
    }
    /** AOSP startProxyOpNoThrow(String, int, String, String, String). */
    public int startProxyOpNoThrow(String op, int proxiedUid, String proxiedPackageName,
            String proxiedAttributionTag, String message) {
        return noteProxyOpNoThrow(op, proxiedPackageName, proxiedUid, proxiedAttributionTag,
                message);
    }
    /** AOSP finishProxyOp(String op, int proxiedUid, String proxiedPackageName,
     *  String proxiedAttributionTag): the paired finish. Nothing is held open by
     *  the proxy start above (it notes rather than latches), so there is nothing
     *  to release. */
    public void finishProxyOp(String op, int proxiedUid, String proxiedPackageName,
            String proxiedAttributionTag) { }

    // ---- Noted-op callback (API 30) ----
    // AOSP routes every op noted BY this process through the registered
    // callback, so an app can attribute its own private-data accesses. Mac.apk's
    // note/check entry points above are pure functions of the op name -- no
    // system service records anything -- so there is no note stream to
    // subscribe to and the callback never fires. That is the same observable
    // state as a process that never touches a noted op. Registration itself
    // round-trips, because AOSP documents passing (null, null) to UNregister and
    // an app may do that unconditionally on teardown.

    /** AOSP AppOpsManager.OnOpNotedCallback: "Callback for notification of an
     *  app-op being noted." Abstract in AOSP, with all three hooks abstract. */
    public abstract static class OnOpNotedCallback {
        public OnOpNotedCallback() {}
        /** AOSP onNoted(SyncNotedAppOp): an op noted by another app on this
         *  app's behalf, synchronously. */
        public abstract void onNoted(SyncNotedAppOp op);
        /** AOSP onSelfNoted(SyncNotedAppOp): an op this app noted itself. */
        public abstract void onSelfNoted(SyncNotedAppOp op);
        /** AOSP onAsyncNoted(AsyncNotedAppOp): an op noted while the app was not
         *  in a call that could carry the note synchronously. */
        public abstract void onAsyncNoted(AsyncNotedAppOp asyncOp);
    }

    private static Executor sNotedExecutor;
    private static OnOpNotedCallback sNotedCallback;

    /** AOSP setOnOpNotedCallback(Executor, OnOpNotedCallback): AOSP requires
     *  BOTH arguments null (to unregister) or BOTH non-null, and throws
     *  IllegalArgumentException otherwise -- keep that, it catches a real caller
     *  bug. Static storage for the same reason sActiveWatchers above is static:
     *  getSystemService hands back a fresh manager each call, while a device has
     *  one per process. */
    public void setOnOpNotedCallback(Executor executor, OnOpNotedCallback callback) {
        if ((executor == null) != (callback == null)) {
            throw new IllegalArgumentException(
                    "executor and callback must both be null or both be non-null");
        }
        synchronized (AppOpsManager.class) {
            sNotedExecutor = executor;
            sNotedCallback = callback;
        }
    }

    /** AOSP setOnOpNotedCallback(Executor, OnOpNotedCallback, int flags): the
     *  flags-taking overload (whether to also receive synchronous notes inline).
     *  Same registration; no note stream exists either way. */
    public void setOnOpNotedCallback(Executor executor, OnOpNotedCallback callback, int flags) {
        setOnOpNotedCallback(executor, callback);
    }

    // ---- AOSP API-36 constant completion (android.app.AppOpsManager) ----
    // Values extracted verbatim from the AOSP source for this class
    // (android16-release). javac INLINES a compile-time constant into the app at
    // ITS build time, so a shipped APK cannot observe a missing one at runtime --
    // these are here for our own framework code, for anything compiled against
    // THIS jar, and so the surface is complete rather than nearly complete.
    public static final String OPSTR_ADD_VOICEMAIL = "android:add_voicemail";
    public static final String OPSTR_ANSWER_PHONE_CALLS = "android:answer_phone_calls";
    public static final String OPSTR_CALL_PHONE = "android:call_phone";
    public static final String OPSTR_MOCK_LOCATION = "android:mock_location";
    public static final String OPSTR_MONITOR_HIGH_POWER_LOCATION = "android:monitor_location_high_power";
    public static final String OPSTR_MONITOR_LOCATION = "android:monitor_location";
    public static final String OPSTR_PICTURE_IN_PICTURE = "android:picture_in_picture";
    public static final String OPSTR_PROCESS_OUTGOING_CALLS = "android:process_outgoing_calls";
    public static final String OPSTR_READ_CALENDAR = "android:read_calendar";
    public static final String OPSTR_READ_CALL_LOG = "android:read_call_log";
    public static final String OPSTR_READ_CELL_BROADCASTS = "android:read_cell_broadcasts";
    public static final String OPSTR_READ_PHONE_NUMBERS = "android:read_phone_numbers";
    public static final String OPSTR_READ_SMS = "android:read_sms";
    public static final String OPSTR_RECEIVE_MMS = "android:receive_mms";
    public static final String OPSTR_RECEIVE_SMS = "android:receive_sms";
    public static final String OPSTR_RECEIVE_WAP_PUSH = "android:receive_wap_push";
    public static final String OPSTR_SEND_SMS = "android:send_sms";
    public static final String OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window";
    public static final String OPSTR_USE_FINGERPRINT = "android:use_fingerprint";
    public static final String OPSTR_USE_SIP = "android:use_sip";
    public static final String OPSTR_WRITE_CALENDAR = "android:write_calendar";
    public static final String OPSTR_WRITE_CALL_LOG = "android:write_call_log";
    public static final String OPSTR_WRITE_SETTINGS = "android:write_settings";
    public static final int OP_NOTED_CALLBACK_FLAG_IGNORE_ASYNC = 1;
    public static final int WATCH_FOREGROUND_CHANGES = 1 << 0;
}
