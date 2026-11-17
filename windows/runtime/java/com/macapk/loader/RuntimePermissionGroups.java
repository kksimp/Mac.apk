// com.macapk.loader.RuntimePermissionGroups -- the dangerous permission groups
// OUTSIDE the storage family and the two foreground location permissions,
// which SpecialAccessStore.PermModel and MacApkPackageManager.
// locationPermissionGranted model on their own: camera, microphone, contacts,
// calendar, phone, call log, SMS, notifications, nearby devices (Bluetooth),
// physical activity, body sensors, and the two "all the time" background
// permissions (location, body sensors).
//
// WHAT A DEVICE DOES. Each of these is `dangerous`: DENIED until the user
// answers PermissionController's GrantPermissionsActivity for the permission's
// GROUP (PermissionMapping.kt PLATFORM_PERMISSIONS, lineage-21.0 :94-150), the
// answer persisted per package with the USER_SET / USER_FIXED flags, "Don't
// allow" twice making the group USER_FIXED (no more dialogs), App info turning
// the group on or off (off kills the process, PermissionManagerServiceImpl.
// onPermissionRevoked -> killUid). A pre-M app is granted them at install.
//
// HERE. The same signed store and helper verb as the storage family (stage S5):
// `runtime-permission --prompt <group>`, the answer written as `perm:<name>=
// <token>` lines, read back by SpecialAccessStore. This table is the model's
// data: which permission is in which group at which platform level, which
// level defines it, and which permissions a legacy target gets IMPLICITLY
// (platform.xml's <split-permission>, and the T "pre-granted" notification
// permission). The decisions (granted, plan, rationale) are in
// SpecialAccessStore, next to the storage model's.
//
// GATE. SpecialAccessStore.allPermsAsk(): the helper must know every group
// (Win.apk's launcher does; macapk-installhelper knows storage and location),
// so it is on where the host is Windows and MACAPK_ALL_PERMS_ASK is not "0".
// Off, every permission here keeps the install-time rule (declared == held),
// which is what the Mac does today.
package com.macapk.loader;

public final class RuntimePermissionGroups {
    private RuntimePermissionGroups() {}

    // AOSP Manifest.permission_group names.
    public static final String GROUP_CONTACTS = "android.permission-group.CONTACTS";
    public static final String GROUP_CALENDAR = "android.permission-group.CALENDAR";
    public static final String GROUP_SMS = "android.permission-group.SMS";
    public static final String GROUP_MICROPHONE = "android.permission-group.MICROPHONE";
    public static final String GROUP_ACTIVITY_RECOGNITION = "android.permission-group.ACTIVITY_RECOGNITION";
    public static final String GROUP_CAMERA = "android.permission-group.CAMERA";
    public static final String GROUP_CALL_LOG = "android.permission-group.CALL_LOG";
    public static final String GROUP_PHONE = "android.permission-group.PHONE";
    public static final String GROUP_SENSORS = "android.permission-group.SENSORS";
    public static final String GROUP_NEARBY_DEVICES = "android.permission-group.NEARBY_DEVICES";
    public static final String GROUP_NOTIFICATIONS = "android.permission-group.NOTIFICATIONS";
    public static final String GROUP_LOCATION = "android.permission-group.LOCATION";

    public static final String PERM_CAMERA = "android.permission.CAMERA";
    public static final String PERM_RECORD_AUDIO = "android.permission.RECORD_AUDIO";
    public static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    public static final String PERM_BODY_SENSORS = "android.permission.BODY_SENSORS";
    public static final String PERM_BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND";
    public static final String PERM_ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION";
    public static final String PERM_ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION";
    public static final String PERM_READ_PHONE_STATE = "android.permission.READ_PHONE_STATE";
    public static final String PERM_READ_PHONE_NUMBERS = "android.permission.READ_PHONE_NUMBERS";

    /** One row: {permission, group, the API level that defines it}. The group
     *  is the one PermissionMapping gives at the CURRENT levels; the call-log
     *  trio moved from PHONE to CALL_LOG at P (28), see groupOf. Values from
     *  frameworks/base core/res/AndroidManifest.xml (android14-release) and
     *  PermissionMapping.kt lineage-21.0 :94-150. */
    private static final Object[][] TABLE = {
        { "android.permission.READ_CONTACTS", GROUP_CONTACTS, 1 },
        { "android.permission.WRITE_CONTACTS", GROUP_CONTACTS, 1 },
        { "android.permission.GET_ACCOUNTS", GROUP_CONTACTS, 1 },
        { "android.permission.READ_CALENDAR", GROUP_CALENDAR, 1 },
        { "android.permission.WRITE_CALENDAR", GROUP_CALENDAR, 1 },
        { "android.permission.SEND_SMS", GROUP_SMS, 1 },
        { "android.permission.RECEIVE_SMS", GROUP_SMS, 1 },
        { "android.permission.READ_SMS", GROUP_SMS, 1 },
        { "android.permission.RECEIVE_WAP_PUSH", GROUP_SMS, 1 },
        { "android.permission.RECEIVE_MMS", GROUP_SMS, 1 },
        { "android.permission.READ_CELL_BROADCASTS", GROUP_SMS, 1 },
        { PERM_RECORD_AUDIO, GROUP_MICROPHONE, 1 },
        { PERM_ACTIVITY_RECOGNITION, GROUP_ACTIVITY_RECOGNITION, 29 },
        { PERM_CAMERA, GROUP_CAMERA, 1 },
        { "android.permission.READ_CALL_LOG", GROUP_CALL_LOG, 16 },
        { "android.permission.WRITE_CALL_LOG", GROUP_CALL_LOG, 16 },
        { "android.permission.PROCESS_OUTGOING_CALLS", GROUP_CALL_LOG, 1 },
        { PERM_READ_PHONE_STATE, GROUP_PHONE, 1 },
        { PERM_READ_PHONE_NUMBERS, GROUP_PHONE, 26 },
        { "android.permission.CALL_PHONE", GROUP_PHONE, 1 },
        { "android.permission.ADD_VOICEMAIL", GROUP_PHONE, 14 },
        { "android.permission.USE_SIP", GROUP_PHONE, 9 },
        { "android.permission.ANSWER_PHONE_CALLS", GROUP_PHONE, 26 },
        { "android.permission.ACCEPT_HANDOVER", GROUP_PHONE, 28 },
        { PERM_BODY_SENSORS, GROUP_SENSORS, 20 },
        { PERM_BODY_SENSORS_BACKGROUND, GROUP_SENSORS, 33 },
        { "android.permission.BLUETOOTH_ADVERTISE", GROUP_NEARBY_DEVICES, 31 },
        { "android.permission.BLUETOOTH_CONNECT", GROUP_NEARBY_DEVICES, 31 },
        { "android.permission.BLUETOOTH_SCAN", GROUP_NEARBY_DEVICES, 31 },
        { "android.permission.UWB_RANGING", GROUP_NEARBY_DEVICES, 31 },
        { "android.permission.NEARBY_WIFI_DEVICES", GROUP_NEARBY_DEVICES, 33 },
        { PERM_POST_NOTIFICATIONS, GROUP_NOTIFICATIONS, 33 },
        { PERM_ACCESS_BACKGROUND_LOCATION, GROUP_LOCATION, 29 },
    };

    private static Object[] row(String p) {
        if (p == null) return null;
        for (Object[] r : TABLE) if (r[0].equals(p)) return r;
        return null;
    }

    /** One of the permissions this table models. */
    public static boolean isTracked(String p) { return row(p) != null; }

    /** The API level that defines `p`; below it the platform has no such
     *  permission (never granted, no rationale). MAX_VALUE when not tracked. */
    public static int sinceLevel(String p) {
        Object[] r = row(p);
        return r == null ? Integer.MAX_VALUE : (Integer) r[2];
    }

    /** The group `p` is prompted under at `level`, or null when the level
     *  does not define it. The call-log permissions were PHONE before P. */
    public static String groupOf(String p, int level) {
        Object[] r = row(p);
        if (r == null || level < (Integer) r[2]) return null;
        String g = (String) r[1];
        if (GROUP_CALL_LOG.equals(g) && level < 28) return GROUP_PHONE;
        return g;
    }

    /** The "all the time" permissions: grantable only while a FOREGROUND
     *  permission of the same group is granted, and asked on their own
     *  (LocationGrantBehavior / the Q "Allow all the time" dialog). */
    public static boolean isBackground(String p) {
        return PERM_ACCESS_BACKGROUND_LOCATION.equals(p) || PERM_BODY_SENSORS_BACKGROUND.equals(p);
    }

    /** The foreground permissions whose grant a background one depends on. */
    public static java.util.List<String> foregroundOf(String background) {
        if (PERM_ACCESS_BACKGROUND_LOCATION.equals(background)) {
            return java.util.Arrays.asList(SpecialAccessStore.PERM_ACCESS_FINE_LOCATION,
                                           SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION);
        }
        if (PERM_BODY_SENSORS_BACKGROUND.equals(background)) {
            return java.util.Collections.singletonList(PERM_BODY_SENSORS);
        }
        return java.util.Collections.emptyList();
    }

    /** platform.xml's split permissions for this family (frameworks/base
     *  data/etc/platform.xml android14): {source, targetSdk, new}: a package
     *  that requests `source` and targets below `targetSdk` implicitly
     *  requests `new`. The source may be a NORMAL permission (BLUETOOTH, the
     *  GMS activity-recognition one), in which case the implicit one is simply
     *  held, or a runtime one, in which case it follows the source's grant
     *  (PermissionManagerServiceImpl grants an implicit runtime permission
     *  when its source is granted). */
    private static final Object[][] SPLITS = {
        { "com.google.android.gms.permission.ACTIVITY_RECOGNITION", 29, PERM_ACTIVITY_RECOGNITION },
        { SpecialAccessStore.PERM_ACCESS_FINE_LOCATION, 29, PERM_ACCESS_BACKGROUND_LOCATION },
        { SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION, 29, PERM_ACCESS_BACKGROUND_LOCATION },
        { PERM_READ_PHONE_STATE, 30, PERM_READ_PHONE_NUMBERS },
        { "android.permission.BLUETOOTH", 31, "android.permission.BLUETOOTH_SCAN" },
        { "android.permission.BLUETOOTH", 31, "android.permission.BLUETOOTH_CONNECT" },
        { "android.permission.BLUETOOTH", 31, "android.permission.BLUETOOTH_ADVERTISE" },
        { "android.permission.BLUETOOTH_ADMIN", 31, "android.permission.BLUETOOTH_SCAN" },
        { "android.permission.BLUETOOTH_ADMIN", 31, "android.permission.BLUETOOTH_CONNECT" },
        { "android.permission.BLUETOOTH_ADMIN", 31, "android.permission.BLUETOOTH_ADVERTISE" },
        { PERM_BODY_SENSORS, 33, PERM_BODY_SENSORS_BACKGROUND },
    };

    /** The sources that make `p` implicitly requested for an app targeting
     *  `target` and declaring `declared`: empty when `p` is only ever
     *  explicit. */
    public static java.util.List<String> implicitSources(String p, int target,
                                                         java.util.Collection<String> declared) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (p == null || declared == null) return out;
        for (Object[] s : SPLITS) {
            if (p.equals(s[2]) && target < (Integer) s[1] && declared.contains((String) s[0])) {
                out.add((String) s[0]);
            }
        }
        return out;
    }

    /** T "pre-grants" POST_NOTIFICATIONS to an app targeting below T: the
     *  permission is requested for it implicitly and granted until the user
     *  turns it off (PermissionManagerServiceImpl, NotificationManagerService's
     *  review flow). */
    public static boolean notificationsPreGranted(String p, int target) {
        return PERM_POST_NOTIFICATIONS.equals(p) && target < 33;
    }

    /** The helper's name for a group: `--prompt <name>` on runtime-permission
     *  and `--group <NAME>=...` on app-details. The storage groups keep
     *  SpecialAccessStore.shortGroup's spelling; these are lower case so a
     *  helper can tell the families apart. */
    public static String promptName(String group) {
        if (group == null) return null;
        int i = group.lastIndexOf('.');
        return (i >= 0 ? group.substring(i + 1) : group).toLowerCase(java.util.Locale.ROOT);
    }

    /** The prompt a background permission is asked with. */
    public static String backgroundPromptName(String background) {
        return PERM_ACCESS_BACKGROUND_LOCATION.equals(background) ? "location_background"
             : PERM_BODY_SENSORS_BACKGROUND.equals(background) ? "sensors_background" : null;
    }

    /** Every tracked permission, in table order (for a sweep over a store). */
    public static java.util.List<String> all() {
        java.util.List<String> out = new java.util.ArrayList<>(TABLE.length);
        for (Object[] r : TABLE) out.add((String) r[0]);
        return out;
    }
}
