package android.os;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;

// android.os.UserManager stub. AOSP's UserManager mediates multi-user
// state (work profiles, secondary users, ephemeral users, the
// distinction between credential-encrypted and device-encrypted
// storage). Mac.apk is single-user so all the boolean queries return
// the "single owner, fully unlocked" answer.
//
// We model the minimum surface SDKs actually probe. Most popular paths:
//   * Firebase: isUserUnlocked() to decide whether it can read its
//     prefs out of the credential-encrypted directory.
//   * AndroidX-Startup / WorkManager: isUserUnlocked() before scheduling.
//   * AdMob / Crashlytics: getUserHandle() for analytics dimensions.
//
// Returning JNI-friendly defaults (true for the unlocked/running checks,
// the singleton instance for everything else) keeps these SDKs from
// crashing during init. Acquire via Context.getSystemService(USER_SERVICE)
// or the Context.getSystemService(UserManager.class) overload.
public class UserManager {
    private static final UserManager INSTANCE = new UserManager();

    // ---- AOSP public constants, verbatim from
    // core/java/android/os/UserManager.java (values cross-checked against
    // /tmp/aosp_api android16 core/api/current.txt). These are compile-time
    // constants, so an app compiled against the real android.jar inlines them
    // and cannot observe them missing -- but a REFLECTIVE read
    // (UserManager.class.getField("DISALLOW_SMS")) can, and DevicePolicy /
    // MDM-aware libraries do exactly that when enumerating restriction keys.
    public static final String USER_TYPE_FULL_SYSTEM = "android.os.usertype.full.SYSTEM";
    public static final String USER_TYPE_FULL_SECONDARY = "android.os.usertype.full.SECONDARY";
    public static final String USER_TYPE_FULL_GUEST = "android.os.usertype.full.GUEST";
    public static final String USER_TYPE_FULL_DEMO = "android.os.usertype.full.DEMO";
    public static final String USER_TYPE_FULL_RESTRICTED = "android.os.usertype.full.RESTRICTED";
    public static final String USER_TYPE_SYSTEM_HEADLESS = "android.os.usertype.system.HEADLESS";
    public static final String USER_TYPE_PROFILE_MANAGED = "android.os.usertype.profile.MANAGED";
    public static final String USER_TYPE_PROFILE_CLONE = "android.os.usertype.profile.CLONE";
    public static final String USER_TYPE_PROFILE_PRIVATE = "android.os.usertype.profile.PRIVATE";
    public static final int QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED = 0x1;
    public static final String DISALLOW_MODIFY_ACCOUNTS = "no_modify_accounts";
    public static final String DISALLOW_CONFIG_WIFI = "no_config_wifi";
    public static final String DISALLOW_CHANGE_WIFI_STATE = "no_change_wifi_state";
    public static final String DISALLOW_WIFI_TETHERING = "no_wifi_tethering";
    public static final String DISALLOW_GRANT_ADMIN = "no_grant_admin";
    public static final String DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI = "no_sharing_admin_configured_wifi";
    public static final String DISALLOW_WIFI_DIRECT = "no_wifi_direct";
    public static final String DISALLOW_ADD_WIFI_CONFIG = "no_add_wifi_config";
    public static final String DISALLOW_CONFIG_LOCALE = "no_config_locale";
    public static final String DISALLOW_INSTALL_APPS = "no_install_apps";
    public static final String DISALLOW_UNINSTALL_APPS = "no_uninstall_apps";
    public static final String DISALLOW_SHARE_LOCATION = "no_share_location";
    public static final String DISALLOW_AIRPLANE_MODE = "no_airplane_mode";
    public static final String DISALLOW_CONFIG_BRIGHTNESS = "no_config_brightness";
    public static final String DISALLOW_AMBIENT_DISPLAY = "no_ambient_display";
    public static final String DISALLOW_CONFIG_SCREEN_TIMEOUT = "no_config_screen_timeout";
    public static final String DISALLOW_INSTALL_UNKNOWN_SOURCES = "no_install_unknown_sources";
    public static final String DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY = "no_install_unknown_sources_globally";
    public static final String DISALLOW_CONFIG_BLUETOOTH = "no_config_bluetooth";
    public static final String DISALLOW_BLUETOOTH = "no_bluetooth";
    public static final String DISALLOW_BLUETOOTH_SHARING = "no_bluetooth_sharing";
    public static final String DISALLOW_USB_FILE_TRANSFER = "no_usb_file_transfer";
    public static final String DISALLOW_CONFIG_CREDENTIALS = "no_config_credentials";
    public static final String DISALLOW_REMOVE_USER = "no_remove_user";
    public static final String DISALLOW_REMOVE_MANAGED_PROFILE = "no_remove_managed_profile";
    public static final String DISALLOW_DEBUGGING_FEATURES = "no_debugging_features";
    public static final String DISALLOW_CONFIG_VPN = "no_config_vpn";
    public static final String DISALLOW_CONFIG_LOCATION = "no_config_location";
    public static final String DISALLOW_CONFIG_DATE_TIME = "no_config_date_time";
    public static final String DISALLOW_CONFIG_TETHERING = "no_config_tethering";
    public static final String DISALLOW_NETWORK_RESET = "no_network_reset";
    public static final String DISALLOW_FACTORY_RESET = "no_factory_reset";
    public static final String DISALLOW_ADD_USER = "no_add_user";
    public static final String DISALLOW_ADD_MANAGED_PROFILE = "no_add_managed_profile";
    public static final String DISALLOW_ADD_PRIVATE_PROFILE = "no_add_private_profile";
    public static final String ENSURE_VERIFY_APPS = "ensure_verify_apps";
    public static final String DISALLOW_CONFIG_CELL_BROADCASTS = "no_config_cell_broadcasts";
    public static final String DISALLOW_CONFIG_MOBILE_NETWORKS = "no_config_mobile_networks";
    public static final String DISALLOW_APPS_CONTROL = "no_control_apps";
    public static final String DISALLOW_MOUNT_PHYSICAL_MEDIA = "no_physical_media";
    public static final String DISALLOW_UNMUTE_MICROPHONE = "no_unmute_microphone";
    public static final String DISALLOW_ADJUST_VOLUME = "no_adjust_volume";
    public static final String DISALLOW_OUTGOING_CALLS = "no_outgoing_calls";
    public static final String DISALLOW_SMS = "no_sms";
    public static final String DISALLOW_FUN = "no_fun";
    public static final String DISALLOW_CREATE_WINDOWS = "no_create_windows";
    public static final String DISALLOW_SYSTEM_ERROR_DIALOGS = "no_system_error_dialogs";
    public static final String DISALLOW_CROSS_PROFILE_COPY_PASTE = "no_cross_profile_copy_paste";
    public static final String DISALLOW_OUTGOING_BEAM = "no_outgoing_beam";
    public static final String DISALLOW_SET_WALLPAPER = "no_set_wallpaper";
    public static final String DISALLOW_SAFE_BOOT = "no_safe_boot";
    public static final String DISALLOW_DATA_ROAMING = "no_data_roaming";
    public static final String DISALLOW_SET_USER_ICON = "no_set_user_icon";
    public static final String DISALLOW_UNIFIED_PASSWORD = "no_unified_password";
    public static final String ALLOW_PARENT_PROFILE_APP_LINKING = "allow_parent_profile_app_linking";
    public static final String DISALLOW_AUTOFILL = "no_autofill";
    public static final String DISALLOW_CONTENT_CAPTURE = "no_content_capture";
    public static final String DISALLOW_CONTENT_SUGGESTIONS = "no_content_suggestions";
    public static final String DISALLOW_USER_SWITCH = "no_user_switch";
    public static final String DISALLOW_SHARE_INTO_MANAGED_PROFILE = "no_sharing_into_profile";
    public static final String DISALLOW_PRINTING = "no_printing";
    public static final String DISALLOW_CONFIG_PRIVATE_DNS = "disallow_config_private_dns";
    public static final String DISALLOW_MICROPHONE_TOGGLE = "disallow_microphone_toggle";
    public static final String DISALLOW_CAMERA_TOGGLE = "disallow_camera_toggle";
    public static final String DISALLOW_CONFIG_DEFAULT_APPS = "disallow_config_default_apps";
    public static final String KEY_RESTRICTIONS_PENDING = "restrictions_pending";
    public static final String DISALLOW_CELLULAR_2G = "no_cellular_2g";
    public static final String DISALLOW_ULTRA_WIDEBAND_RADIO = "no_ultra_wideband_radio";
    public static final String DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO = "no_near_field_communication_radio";
    public static final String DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO = "no_change_near_field_communication_radio";
    public static final String DISALLOW_THREAD_NETWORK = "no_thread_network";
    public static final String DISALLOW_SIM_GLOBALLY = "no_sim_globally";
    public static final String DISALLOW_ASSIST_CONTENT = "no_assist_content";
    public static final int USER_CREATION_FAILED_NOT_PERMITTED = Activity.RESULT_FIRST_USER;
    public static final int USER_CREATION_FAILED_NO_MORE_USERS = Activity.RESULT_FIRST_USER + 1;
    public static final int USER_OPERATION_SUCCESS = 0;
    public static final int USER_OPERATION_ERROR_UNKNOWN = 1;
    public static final int USER_OPERATION_ERROR_MANAGED_PROFILE = 2;
    public static final int USER_OPERATION_ERROR_MAX_RUNNING_USERS = 3;
    public static final int USER_OPERATION_ERROR_CURRENT_USER = 4;
    public static final int USER_OPERATION_ERROR_LOW_STORAGE = 5;
    public static final int USER_OPERATION_ERROR_MAX_USERS = 6;


    /** Mirrors AOSP's `UserManager.get(Context)` factory. SDKs that
     *  use reflection to get an instance hit this path. */
    public static UserManager get(Context context) { return INSTANCE; }

    /** Tagged by AOSP as @SystemApi but Firebase calls it via plain
     *  method invocation on the returned instance, so we expose it as
     *  a public no-arg ctor + factory. */
    UserManager() {}

    /** AOSP docs: "Return whether the calling user is running in an
     *  'unlocked' state. A user is unlocked when the user's credential-
     *  encrypted storage is available." On macOS this is always true --
     *  there's no concept of work-profile lock, no FBE encryption tied
     *  to user credential. */
    public boolean isUserUnlocked() { return true; }

    /** AOSP: "Returns true if the user is running and has the same
     *  user-id as the running process." Always true for single-user. */
    public boolean isUserRunning(UserHandle user) { return true; }
    public boolean isUserRunningOrStopping(UserHandle user) { return true; }

    /** AOSP: returns the UserHandle for the calling user. We always
     *  hand back the singleton "user 0" handle. */
    public UserHandle getUserHandle() { return UserHandle.SYSTEM; }

    /** AOSP: returns the handle for the user that owns the current
     *  process. Same semantics on macOS. */
    public UserHandle getCurrentUserHandle() { return UserHandle.SYSTEM; }

    /** Boolean shortcut some SDKs use to gate enterprise / work-profile
     *  features they shouldn't run in a personal context. We're always
     *  the primary user. */
    public boolean isSystemUser()  { return true; }
    public boolean isAdminUser()   { return true; }
    public boolean isPrimaryUser() { return true; }
    public boolean isProfile()     { return false; }
    /** API 34: the user that owns the device (non-headless system user here). */
    public boolean isMainUser()    { return true; }
    /** API 35. */
    public boolean isPrivateProfile() { return false; }
    /** @SystemApi: this process's user type -- the one full system user. */
    public String getUserType()    { return USER_TYPE_FULL_SYSTEM; }
    /** @SystemApi (API 30): whether the calling user is of the given type. */
    public boolean isUserOfType(String userType) { return getUserType().equals(userType); }

    public int getUserCount() { return 1; }

    // ---- Multi-user surface. Mac.apk runs exactly one user (the macOS
    // account that launched it), user id 0 == UserHandle.SYSTEM, with no
    // profiles, no work profile and no quiet mode. Every answer below is the
    // single-owner answer that a device in that configuration gives; none of
    // them pretends a facility we do not have.

    /** AOSP :2655 -- `getMaxSupportedUsers() > 1 && ...`. Mac.apk has exactly
     *  one user and no way to create another (createUser is not public API and
     *  we implement no UserManagerService), so the max is 1. */
    public static boolean supportsMultipleUsers() { return false; }

    /** AOSP :2719 -- headless system user mode is a device build flag
     *  (automotive / TV). Our single user IS the human user, which is
     *  precisely what "not headless" means. */
    public static boolean isHeadlessSystemUserMode() { return false; }

    /** AOSP :3539 -- `isUserTypeManagedProfile(getProfileType(...))`. There is
     *  no work profile here; consistent with isProfile() above. */
    public boolean isManagedProfile() { return false; }

    /** AOSP :3086 -- the device's designated "communal profile" (a shared
     *  hub/dock user). Never provisioned here. */
    public boolean isCommunalProfile() { return false; }

    /** AOSP :3703 -- is the context user running in the foreground. There is
     *  only one user and it is the one running the app. */
    public boolean isUserForeground() { return true; }

    /** AOSP :3147 -- is the FOREGROUND user an admin user. The foreground user
     *  is our single user, and isAdminUser() above already answers true. */
    public boolean isForegroundUserAdmin() { return isAdminUser(); }

    /** AOSP :3886. Same answer as the no-arg form -- there is no
     *  credential-encrypted storage to be locked, for any user. */
    public boolean isUserUnlocked(UserHandle user) { return true; }

    /** AOSP :2943 -- "As of R, this method always returns false in order to
     *  protect goat privacy"; below R it reports whether
     *  com.coffeestainstudios.goatsimulator is installed. Mac.apk presents at
     *  least SDK 29 and installs exactly one package per process, so the
     *  pre-R branch could only differ if the app under test WERE Goat
     *  Simulator, which cannot query itself into a different answer. */
    public boolean isUserAGoat() { return false; }

    /** AOSP :5905 -- quiet mode is a work-profile feature. With no profile
     *  provisioned, no profile is in quiet mode. */
    public boolean isQuietModeEnabled(UserHandle userHandle) { return false; }

    /** AOSP :5836/:5854 delegate to the service, which (UserManagerService,
     *  ensureCanModifyQuietMode) throws IllegalArgumentException when the
     *  handle is not a PROFILE. Mac.apk has no profiles, so every handle
     *  reaches that branch. Throwing is the device behaviour; returning true
     *  would claim we toggled a profile that does not exist. */
    public boolean requestQuietModeEnabled(boolean enableQuietMode, UserHandle userHandle) {
        return requestQuietModeEnabled(enableQuietMode, userHandle, 0);
    }
    public boolean requestQuietModeEnabled(boolean enableQuietMode, UserHandle userHandle,
                                           int flags) {
        throw new IllegalArgumentException(
                "Can't set quiet mode, user " + userHandle + " is not a profile");
    }

    /** AOSP :5565 -- "A non-empty list of UserHandles associated with the
     *  context user", which always includes the user itself. */
    public List<UserHandle> getUserProfiles() {
        return Collections.singletonList(getUserHandle());
    }

    /** AOSP android14 :5084 (@hide) -- the profile's parent user, or null when
     *  the user is not a profile. This device's only user is the system user,
     *  which is not a profile, so null (UserManagerService.getProfileParent). */
    public android.content.pm.UserInfo getProfileParent(int userId) { return null; }
    /** API 31 public form: same answer as a UserHandle. */
    public UserHandle getProfileParent(UserHandle user) { return null; }

    /** AOSP android14 :4558 (@SystemApi) -- every user's handle, from getUsers.
     *  This device has exactly one user, the system user 0 (the same answer
     *  getUserProfiles gives), and nobody is dying, so the flag changes
     *  nothing. Google Play services' bound-service entry asks it. */
    public List<UserHandle> getUserHandles(boolean excludeDying) {
        return new java.util.ArrayList<>(Collections.singletonList(UserHandle.SYSTEM));
    }

    /** AOSP :4387 -- `getUserSerialNumber(user.getIdentifier())`, documented
     *  to "return -1 if the given UserHandle does not exist". User 0 is the
     *  only user that exists here; AOSP assigns the system user serial 0. */
    public long getSerialNumberForUser(UserHandle user) {
        if (user == null || user.getIdentifier() != UserHandle.USER_SYSTEM) return -1L;
        return 0L;
    }

    /** AOSP :4400 -- inverse of the above; null when no user has that
     *  serial. */
    public UserHandle getUserForSerialNumber(long serialNumber) {
        return (serialNumber == 0L) ? UserHandle.SYSTEM : null;
    }

    /** AOSP :6824 -- "creation time in milliseconds since Epoch time".
     *  Answered from the real host rather than from a constant: the Mac.apk
     *  android_root is created once, when this install first ran, which is
     *  exactly when this "user" came into existence. macOS records a birth
     *  time (HFS+/APFS `st_birthtime`), which java.nio surfaces as
     *  BasicFileAttributes.creationTime(); fall back to lastModified() if the
     *  filesystem has no birth time, and to 0 if the tree is not there yet. */
    public long getUserCreationTime(UserHandle userHandle) {
        try {
            File root = Environment.getDataDirectory();
            BasicFileAttributes a =
                    Files.readAttributes(root.toPath(), BasicFileAttributes.class);
            long t = a.creationTime().toMillis();
            if (t > 0L) return t;
            long m = root.lastModified();
            return m > 0L ? m : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** AOSP :2888 -- @NonNull; the user's own display name. Wire it to the
     *  real host account (the macOS user actually running Mac.apk) rather
     *  than a made-up "Owner": that is the same host-first choice
     *  BatteryManager makes with /usr/bin/pmset. `user.name` is always set by
     *  the JDK, but keep AOSP's empty-string (never null) fallback. */
    public String getUserName() {
        String n = System.getProperty("user.name");
        return (n == null) ? "" : n;
    }

    /** AOSP :4800 -- pure Intent construction, no service call, so this is a
     *  verbatim port including the accountName-without-accountType
     *  IllegalArgumentException. The extra keys are the AOSP literals
     *  (core/java/android/os/UserManager.java:2185-2207); they are not in the
     *  public signature file, so they stay private here. */
    public static Intent createUserCreationIntent(String userName, String accountName,
                                                  String accountType,
                                                  PersistableBundle accountOptions) {
        Intent intent = new Intent(ACTION_CREATE_USER);
        if (userName != null) {
            intent.putExtra(EXTRA_USER_NAME, userName);
        }
        if (accountName != null && accountType == null) {
            throw new IllegalArgumentException(
                    "accountType must be specified if accountName is specified");
        }
        if (accountName != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_NAME, accountName);
        }
        if (accountType != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_TYPE, accountType);
        }
        if (accountOptions != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_OPTIONS, accountOptions);
        }
        return intent;
    }
    // AOSP :2167 (private upstream too), :2185, :2192, :2199, :2206.
    private static final String ACTION_CREATE_USER = "android.os.action.CREATE_USER";
    private static final String EXTRA_USER_NAME = "android.os.extra.USER_NAME";
    private static final String EXTRA_USER_ACCOUNT_NAME = "android.os.extra.USER_ACCOUNT_NAME";
    private static final String EXTRA_USER_ACCOUNT_TYPE = "android.os.extra.USER_ACCOUNT_TYPE";
    private static final String EXTRA_USER_ACCOUNT_OPTIONS =
            "android.os.extra.USER_ACCOUNT_OPTIONS";

    /** AOSP :6787 -- the restrictions-PIN feature was removed from the
     *  platform; the method "is preserved for backwards compatibility reasons
     *  and always returns false". Verbatim. */
    @Deprecated
    public boolean setRestrictionsChallenge(String newPin) { return false; }

    /** AOSP :4197/:4207 -- both throw, verbatim:
     *      throw new UnsupportedOperationException("This method is no longer supported");
     *  Do NOT soften these to a no-op: an app that catches the exception (or
     *  that must not believe the call succeeded) behaves differently. */
    @Deprecated
    public void setUserRestrictions(Bundle restrictions) {
        throw new UnsupportedOperationException("This method is no longer supported");
    }
    @Deprecated
    public void setUserRestrictions(Bundle restrictions, UserHandle userHandle) {
        throw new UnsupportedOperationException("This method is no longer supported");
    }

    // Real backing store for the restriction surface rather than a constant
    // "false". AOSP :4224 setUserRestriction writes through to
    // UserManagerService and :4141 getUserRestrictions reads the same table
    // back, so an app that sets a key and then asks for it sees its own write.
    // Answering a hardcoded false there would be a lie the caller can catch in
    // one line. Empty by default, which is what a device with no device-owner
    // reports, so hasUserRestriction()'s previous behaviour is unchanged.
    private final Bundle mRestrictions = new Bundle();

    /** AOSP :4224. MANAGE_USERS is a signature permission, so only a
     *  device-owner-shaped app reaches this at all. */
    @Deprecated
    public void setUserRestriction(String key, boolean value) {
        synchronized (mRestrictions) {
            if (value) mRestrictions.putBoolean(key, true);
            else mRestrictions.remove(key);
        }
    }

    /** AOSP :4141 -- "a Bundle containing all the restrictions". A COPY, so a
     *  caller mutating the result does not silently rewrite our table. */
    public Bundle getUserRestrictions() {
        synchronized (mRestrictions) { return new Bundle(mRestrictions); }
    }
    /** AOSP :4161. Single user, so the handle cannot select a different
     *  table. */
    public Bundle getUserRestrictions(UserHandle userHandle) { return getUserRestrictions(); }

    /** AOSP docs: "a Bundle with the restrictions for that package, or an
     *  empty Bundle if there are no saved restrictions" -- never null, never
     *  throws on a device with no MDM/admin. Mac.apk has no
     *  DevicePolicyManager backing this, so "no restrictions imposed" is the
     *  honest single-owner answer, matching isSystemUser/isAdminUser above. */
    public Bundle getApplicationRestrictions(String packageName) { return new Bundle(); }

    /** AOSP: "whether the context user has been disallowed from performing
     *  certain actions or setting certain settings." No admin exists to
     *  impose any restriction, so every key is unset. */
    public boolean hasUserRestriction(String restrictionKey) {
        synchronized (mRestrictions) { return mRestrictions.getBoolean(restrictionKey, false); }
    }

    /** AOSP: "whether the context user is a demo user." Mac.apk has no
     *  demo-mode retail-provisioning path. */
    public boolean isDemoUser() { return false; }

    /** AOSP core/java/android/os/UserManager.java:2501 -- thrown by the
     *  user-creation APIs, carrying one of the USER_OPERATION_* result codes
     *  declared above. The constructor is @hide but public upstream. */
    public static class UserOperationException extends RuntimeException {
        private static final long serialVersionUID = 0L;
        private final int mUserOperationResult;

        public UserOperationException(String message, int userOperationResult) {
            super(message);
            mUserOperationResult = userOperationResult;
        }

        public int getUserOperationResult() { return mUserOperationResult; }
    }
}
