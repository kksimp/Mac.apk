// PermissionModelTest -- checks SpecialAccessStore.GroupModel, the decision
// model behind every dangerous permission group outside the storage family
// (camera, microphone, contacts, ... and the "all the time" background
// permissions), against what PermissionController does on a device: DENIED
// until allowed, one dialog per group, a silent grant into a group with a
// granted member, no dialog after "Don't allow" twice, the rationale rule,
// legacy targets' implicit grants, and the groups App info lists.
//
//   javac -cp "<runtime>/lib/macapk-loader.jar;<runtime>/lib/android-framework.jar" -d out \
//         windows/test/java/PermissionModelTest.java
//   java  -cp "<runtime>/lib/macapk-loader.jar;<runtime>/lib/android-framework.jar;out" PermissionModelTest
//
// Exits 0 when every check passes. The model takes explicit inputs (level,
// target, manifest, store tokens), so no store, registry or native runtime is
// needed; RuntimePermissionGroups is checked alongside.

import com.macapk.loader.RuntimePermissionGroups;
import com.macapk.loader.SpecialAccessStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PermissionModelTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) failures++;
    }

    private static final String CAMERA = "android.permission.CAMERA";
    private static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";
    private static final String READ_CONTACTS = "android.permission.READ_CONTACTS";
    private static final String WRITE_CONTACTS = "android.permission.WRITE_CONTACTS";
    private static final String READ_CALL_LOG = "android.permission.READ_CALL_LOG";
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String BG_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION";
    private static final String BT = "android.permission.BLUETOOTH";
    private static final String BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String GMS_AR = "com.google.android.gms.permission.ACTIVITY_RECOGNITION";
    private static final String AR = "android.permission.ACTIVITY_RECOGNITION";
    private static final String BODY = "android.permission.BODY_SENSORS";
    private static final String BODY_BG = "android.permission.BODY_SENSORS_BACKGROUND";

    private static SpecialAccessStore.GroupModel model(int level, int target, List<String> declared,
                                                       String... tokens) {
        Map<String, String> state = new HashMap<>();
        for (int i = 0; i + 1 < tokens.length; i += 2) state.put(tokens[i], tokens[i + 1]);
        return new SpecialAccessStore.GroupModel(level, target, declared, state, true);
    }

    private static String plan(SpecialAccessStore.GroupModel m, String... request) {
        StringBuilder sb = new StringBuilder();
        for (SpecialAccessStore.PermModel.Step s : m.plan(request)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.kind).append(s.prompt != null ? "(" + s.prompt + ")" : "").append(s.perms);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        // The table.
        check(RuntimePermissionGroups.isTracked(CAMERA) && !RuntimePermissionGroups.isTracked(FINE),
              "table: CAMERA is tracked, ACCESS_FINE_LOCATION is the location model's");
        check("camera".equals(RuntimePermissionGroups.promptName(RuntimePermissionGroups.groupOf(CAMERA, 33))),
              "table: CAMERA prompts as camera");
        check(RuntimePermissionGroups.GROUP_PHONE.equals(RuntimePermissionGroups.groupOf(READ_CALL_LOG, 27))
              && RuntimePermissionGroups.GROUP_CALL_LOG.equals(RuntimePermissionGroups.groupOf(READ_CALL_LOG, 28)),
              "table: call log is PHONE below P, CALL_LOG from P");
        check(RuntimePermissionGroups.groupOf(POST_NOTIFICATIONS, 32) == null
              && RuntimePermissionGroups.sinceLevel(POST_NOTIFICATIONS) == 33,
              "table: POST_NOTIFICATIONS exists from T");

        // A camera app targeting T on T, never asked.
        SpecialAccessStore.GroupModel m = model(33, 33, Arrays.asList(CAMERA, RECORD_AUDIO));
        check(!m.granted(CAMERA), "never asked: CAMERA is DENIED");
        check(!m.rationale(CAMERA), "never asked: no rationale");
        check("PROMPT(camera)[android.permission.CAMERA] PROMPT(microphone)[android.permission.RECORD_AUDIO]"
              .equals(plan(m, CAMERA, RECORD_AUDIO)), "never asked: one dialog per group, in request order: "
              + plan(m, CAMERA, RECORD_AUDIO));
        check("".equals(plan(m, READ_CONTACTS)), "an undeclared permission is not asked about");

        // Allowed, denied once, denied twice.
        m = model(33, 33, Arrays.asList(CAMERA), CAMERA, "granted");
        check(m.granted(CAMERA) && "".equals(plan(m, CAMERA)), "granted: held, no dialog");
        m = model(33, 33, Arrays.asList(CAMERA), CAMERA, "denied");
        check(!m.granted(CAMERA) && m.rationale(CAMERA), "denied once: DENIED, rationale true");
        check("PROMPT(camera)[android.permission.CAMERA]".equals(plan(m, CAMERA)), "denied once: asked again");
        m = model(33, 33, Arrays.asList(CAMERA), CAMERA, "denied_fixed");
        check(!m.granted(CAMERA) && !m.rationale(CAMERA), "denied twice: DENIED, rationale false");
        check("FIXED[android.permission.CAMERA]".equals(plan(m, CAMERA)), "denied twice: no dialog");

        // A group with a granted member: the rest is granted silently.
        m = model(33, 33, Arrays.asList(READ_CONTACTS, WRITE_CONTACTS), READ_CONTACTS, "granted");
        check("AUTO[android.permission.WRITE_CONTACTS]".equals(plan(m, WRITE_CONTACTS)),
              "WRITE_CONTACTS after READ_CONTACTS: auto-granted, no dialog");
        check(!m.granted(WRITE_CONTACTS), "the auto grant is the helper's to write, not assumed");

        // A pre-M app holds what it declares.
        m = model(33, 22, Arrays.asList(CAMERA, READ_CONTACTS));
        check(m.granted(CAMERA) && m.granted(READ_CONTACTS) && "".equals(plan(m, CAMERA)),
              "target 22: granted at install, nothing asked");
        check(m.appDetailsGroups().isEmpty(), "target 22: App info lists no groups");

        // Notifications: undefined below T; pre-granted to a pre-T target; asked of a T target.
        m = model(32, 32, Arrays.asList(POST_NOTIFICATIONS));
        check(!m.granted(POST_NOTIFICATIONS) && "".equals(plan(m, POST_NOTIFICATIONS)),
              "level 32: POST_NOTIFICATIONS does not exist");
        m = model(33, 32, Collections.<String>emptyList());
        check(m.granted(POST_NOTIFICATIONS) && "".equals(plan(m, POST_NOTIFICATIONS)),
              "target 32 on T: notifications pre-granted, nothing asked");
        m = model(33, 33, Arrays.asList(POST_NOTIFICATIONS));
        check("PROMPT(notifications)[android.permission.POST_NOTIFICATIONS]".equals(plan(m, POST_NOTIFICATIONS)),
              "target 33 on T: notifications asked");
        m = model(33, 33, Collections.<String>emptyList());
        check(!m.granted(POST_NOTIFICATIONS), "target 33 without the declaration: notifications off");

        // Background location: only after the foreground, asked on its own.
        m = model(33, 33, Arrays.asList(FINE, BG_LOCATION));
        check(!m.granted(BG_LOCATION) && !m.foregroundGranted(BG_LOCATION),
              "background location without foreground: DENIED");
        check("BACKGROUND(location_background)[android.permission.ACCESS_BACKGROUND_LOCATION]"
              .equals(plan(m, FINE, BG_LOCATION)), "background location: its own step after the group: "
              + plan(m, FINE, BG_LOCATION));
        m = model(33, 33, Arrays.asList(FINE, BG_LOCATION), FINE, "granted");
        check(m.foregroundGranted(BG_LOCATION) && !m.granted(BG_LOCATION), "foreground granted: background still asked");
        m = model(33, 33, Arrays.asList(FINE, BG_LOCATION), FINE, "granted", BG_LOCATION, "granted");
        check(m.granted(BG_LOCATION), "both granted: background held");
        m = model(33, 33, Arrays.asList(FINE, BG_LOCATION), FINE, "denied", BG_LOCATION, "granted");
        check(!m.granted(BG_LOCATION), "foreground revoked: background goes with it");
        m = model(33, 28, Arrays.asList(FINE), FINE, "granted");
        check(m.granted(BG_LOCATION) && "".equals(plan(m, BG_LOCATION)),
              "target 28: background location implicit, follows the foreground, no dialog");
        m = model(28, 28, Arrays.asList(FINE), FINE, "granted");
        check(!m.granted(BG_LOCATION), "level 28: no background location permission");

        // Body sensors background the same way.
        m = model(34, 34, Arrays.asList(BODY, BODY_BG), BODY, "granted");
        check("BACKGROUND(sensors_background)[android.permission.BODY_SENSORS_BACKGROUND]".equals(plan(m, BODY_BG)),
              "body sensors background: asked once the foreground is granted");

        // Legacy Bluetooth: the S permissions are implicit and held.
        m = model(31, 30, Arrays.asList(BT));
        check(m.granted(BT_CONNECT) && "".equals(plan(m, BT_CONNECT)),
              "target 30 declaring BLUETOOTH: BLUETOOTH_CONNECT held, nothing asked");
        m = model(31, 31, Arrays.asList(BT_CONNECT));
        check("PROMPT(nearby_devices)[android.permission.BLUETOOTH_CONNECT]".equals(plan(m, BT_CONNECT)),
              "target 31: nearby devices asked");

        // The GMS activity-recognition split.
        m = model(29, 28, Arrays.asList(GMS_AR));
        check(m.granted(AR), "target 28 with the GMS permission: ACTIVITY_RECOGNITION held");
        m = model(29, 29, Arrays.asList(AR));
        check(!m.granted(AR) && "PROMPT(activity_recognition)[android.permission.ACTIVITY_RECOGNITION]"
              .equals(plan(m, AR)), "target 29: physical activity asked");

        // Call log's group follows the level.
        m = model(27, 27, Arrays.asList(READ_CALL_LOG));
        check("PROMPT(phone)[android.permission.READ_CALL_LOG]".equals(plan(m, READ_CALL_LOG)),
              "level 27: call log asked as phone");
        m = model(28, 28, Arrays.asList(READ_CALL_LOG));
        check("PROMPT(call_log)[android.permission.READ_CALL_LOG]".equals(plan(m, READ_CALL_LOG)),
              "level 28: call log asked as call_log");

        // App info rows: one per group, background rows on their own.
        m = model(33, 33, Arrays.asList(CAMERA, READ_CONTACTS, WRITE_CONTACTS, FINE, BG_LOCATION));
        check(Arrays.asList("contacts", "camera", "location_background").equals(
                  new java.util.ArrayList<>(m.appDetailsGroups().keySet())),
              "App info: contacts, camera, location (all the time): " + m.appDetailsGroups());
        check(m.appDetailsGroups().get("contacts").size() == 2, "App info: the contacts row holds both members");

        // An unresolved targetSdk takes the modern branch.
        SpecialAccessStore.GroupModel u = new SpecialAccessStore.GroupModel(33, 0, Arrays.asList(CAMERA),
                                                                            Collections.<String, String>emptyMap(), false);
        check(!u.granted(CAMERA), "unresolved target: not the legacy grant");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
