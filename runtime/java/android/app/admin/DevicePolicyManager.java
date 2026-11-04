// android.app.admin.DevicePolicyManager
//
// AOSP's device-administration service. Apps fetch it via getSystemService(
// DEVICE_POLICY_SERVICE) and commonly probe isAdminActive / password-policy /
// management state. Mac.apk is never managed and has no device-admin, so the
// state queries report "not managed / no admin / no restrictions" -- the honest
// unmanaged-device result that lets enterprise-aware apps run their consumer
// path instead of NPE on a null service. Mirrors
// frameworks/base/core/java/android/app/admin/DevicePolicyManager.java.
package android.app.admin;

import android.content.ComponentName;
import android.content.Context;

public class DevicePolicyManager {

    // ================================================================
    // AOSP API-36 surface completion (android.app.admin.DevicePolicyManager).
    // AOSP ref: frameworks/base/core/java/android/app/admin/
    // DevicePolicyManager.java (android16-release).
    //
    // THE ONE RULE THIS WHOLE BLOCK FOLLOWS, and why it is not a cop-out:
    // isAdminActive() above is unconditionally false and always will be --
    // Mac.apk has no DeviceAdminReceiver activation flow, no work profile, no
    // device owner and no system server to hold policy in. AOSP documents, per
    // method, that the call throws SecurityException when the caller is not an
    // active admin / device owner / profile owner. That condition is
    // PERMANENTLY true here, so raising SecurityException is not a stub, it is
    // the SAME answer a real unmanaged phone gives an app that calls these
    // without ever having been granted admin rights -- and it is strictly
    // better than the NoSuchMethodError these calls used to raise, because
    // SecurityException is a RuntimeException that enterprise-aware code
    // already catches, while an Error is not stopped by catch(Exception).
    //
    // The classification is evidence-based, not a guess: each AOSP javadoc was
    // read for its "@throws SecurityException ..." clause. 207 of the members
    // below carry one, and every one of those names an owner/admin/delegate
    // condition. The members WITHOUT one are implemented for real, each with the
    // AOSP-documented unmanaged-device value and a note saying which. The single
    // permission-gated (rather than owner-gated) exception is
    // getPasswordComplexity, handled at its own site.
    //
    // Two members of the AOSP surface are absent and cannot be added from this
    // lane -- both need a TYPE that does not exist in this tree:
    //   * bindDeviceAdminServiceAsUser(..., Context.BindServiceFlags, ...)
    //     needs android.content.Context.BindServiceFlags (android.content lane).
    //     The int-flags overload IS present.
    //   * generateKeyPair(...) returns android.security.AttestedKeyPair
    //     (android.security lane).
    // ================================================================

    public static final int PASSWORD_QUALITY_UNSPECIFIED = 0;
    /** AOSP PASSWORD_QUALITY_* (the rest of the ladder). */
    public static final int PASSWORD_QUALITY_BIOMETRIC_WEAK = 0x8000;
    public static final int PASSWORD_QUALITY_SOMETHING = 0x10000;
    public static final int PASSWORD_QUALITY_NUMERIC = 0x20000;
    public static final int PASSWORD_QUALITY_NUMERIC_COMPLEX = 0x30000;
    public static final int PASSWORD_QUALITY_ALPHABETIC = 0x40000;
    public static final int PASSWORD_QUALITY_ALPHANUMERIC = 0x50000;
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;
    public static final int PASSWORD_QUALITY_MANAGED = 0x80000;

    /** AOSP PASSWORD_COMPLEXITY_*: the coarse buckets getPasswordComplexity
     *  reports. */
    public static final int PASSWORD_COMPLEXITY_NONE = 0;
    public static final int PASSWORD_COMPLEXITY_LOW = 0x10000;
    public static final int PASSWORD_COMPLEXITY_MEDIUM = 0x30000;
    public static final int PASSWORD_COMPLEXITY_HIGH = 0x50000;

    /** AOSP PERMISSION_POLICY_*: how runtime permission requests are answered
     *  under an admin. PROMPT is the unmanaged default. */
    public static final int PERMISSION_POLICY_PROMPT = 0;
    public static final int PERMISSION_POLICY_AUTO_GRANT = 1;
    public static final int PERMISSION_POLICY_AUTO_DENY = 2;

    /** AOSP PERMISSION_GRANT_STATE_*. */
    public static final int PERMISSION_GRANT_STATE_DEFAULT = 0;
    public static final int PERMISSION_GRANT_STATE_GRANTED = 1;
    public static final int PERMISSION_GRANT_STATE_DENIED = 2;

    /** AOSP WIFI_SECURITY_*: the minimum Wi-Fi security an admin may require.
     *  OPEN means "no requirement". */
    public static final int WIFI_SECURITY_OPEN = 0;
    public static final int WIFI_SECURITY_PERSONAL = 1;
    public static final int WIFI_SECURITY_ENTERPRISE_EAP = 2;
    public static final int WIFI_SECURITY_ENTERPRISE_192 = 3;

    /** AOSP NEARBY_STREAMING_*: whether nearby app/notification streaming is
     *  permitted. NOT_CONTROLLED_BY_POLICY is the unmanaged default. */
    public static final int NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int NEARBY_STREAMING_ENABLED = 1;
    public static final int NEARBY_STREAMING_DISABLED = 2;
    public static final int NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY = 3;

    /** AOSP PERSONAL_APPS_*: why personal apps are suspended on an org-owned
     *  device. NOT_SUSPENDED is the unmanaged default. */
    public static final int PERSONAL_APPS_NOT_SUSPENDED = 0;
    public static final int PERSONAL_APPS_SUSPENDED_EXPLICITLY = 1;
    public static final int PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT = 2;

    /** AOSP MTE_*: memory-tagging policy. NOT_CONTROLLED_BY_POLICY is the
     *  default. */
    public static final int MTE_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int MTE_ENABLED = 1;
    public static final int MTE_DISABLED = 2;

    /** AOSP CONTENT_PROTECTION_*: content-protection policy. */
    public static final int CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int CONTENT_PROTECTION_DISABLED = 1;
    public static final int CONTENT_PROTECTION_ENABLED = 2;

    /** AOSP APP_FUNCTIONS_*: app-functions policy (API 36). */
    public static final int APP_FUNCTIONS_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int APP_FUNCTIONS_DISALLOWED = 1;
    public static final int APP_FUNCTIONS_DISALLOWED_CROSS_PROFILE = 2;

    /** AOSP AUTO_TIME_* / AUTO_TIME_ZONE_*: automatic time policy. */
    public static final int AUTO_TIME_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int AUTO_TIME_ENABLED = 1;
    public static final int AUTO_TIME_DISABLED = 2;
    public static final int AUTO_TIME_ZONE_NOT_CONTROLLED_BY_POLICY = 0;
    public static final int AUTO_TIME_ZONE_ENABLED = 1;
    public static final int AUTO_TIME_ZONE_DISABLED = 2;

    /** AOSP LOCK_TASK_FEATURE_*: which system affordances remain available in
     *  lock-task ("screen pinning") mode. */
    public static final int LOCK_TASK_FEATURE_NONE = 0;
    public static final int LOCK_TASK_FEATURE_SYSTEM_INFO = 1;
    public static final int LOCK_TASK_FEATURE_NOTIFICATIONS = 1 << 1;
    public static final int LOCK_TASK_FEATURE_HOME = 1 << 2;
    public static final int LOCK_TASK_FEATURE_OVERVIEW = 1 << 3;
    public static final int LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 1 << 4;
    public static final int LOCK_TASK_FEATURE_KEYGUARD = 1 << 5;

    /** AOSP PRIVATE_DNS_MODE_* plus the two set*PrivateDnsMode result codes. */
    public static final int PRIVATE_DNS_MODE_UNKNOWN = 0;
    public static final int PRIVATE_DNS_MODE_OFF = 1;
    public static final int PRIVATE_DNS_MODE_OPPORTUNISTIC = 2;
    public static final int PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3;
    public static final int PRIVATE_DNS_SET_NO_ERROR = 0;
    public static final int PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 1;
    public static final int PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 2;

    /** AOSP MAX_PASSWORD_LENGTH: "the maximum length of a password that can be
     *  set" -- see getPasswordMaximumLength for why that method still answers
     *  0 here. */
    public static final int MAX_PASSWORD_LENGTH = 16;

    /** AOSP WIPE_* flags for wipeData / wipeDevice. */
    public static final int WIPE_EXTERNAL_STORAGE = 1;
    public static final int WIPE_RESET_PROTECTION_DATA = 1 << 1;
    public static final int WIPE_EUICC = 1 << 2;
    public static final int WIPE_SILENTLY = 1 << 3;

    /** AOSP's one shared refusal. AOSP's own messages are per-call-site and say
     *  which role was required; this says WHY the role can never be held, which
     *  is the part a developer reading a Mac.apk stack trace needs. */
    private static SecurityException notAdmin(String api) {
        return new SecurityException("DevicePolicyManager." + api + ": the caller is not an active"
                + " device admin, device owner or profile owner. Mac.apk has no device-admin"
                + " activation flow, so isAdminActive() is always false and no caller can hold"
                + " those roles.");
    }

    /** AOSP getResources() hands back one instance per manager. */
    private final DevicePolicyResourcesManager mResources = new DevicePolicyResourcesManager();

    /** AOSP DevicePolicyManager.OnClearApplicationUserDataListener: told when
     *  clearApplicationUserData finishes. Nested in AOSP, so nested here. */
    public interface OnClearApplicationUserDataListener {
        void onApplicationUserDataCleared(String packageName, boolean succeeded);
    }

    /** AOSP DevicePolicyManager.InstallSystemUpdateCallback: told when
     *  installSystemUpdate FAILS (there is no success callback -- a successful
     *  install reboots the device). Abstract in AOSP with an empty body, so a
     *  caller overrides only what it needs. */
    public abstract static class InstallSystemUpdateCallback {
        public static final int UPDATE_ERROR_UNKNOWN = 1;
        public static final int UPDATE_ERROR_INCORRECT_OS_VERSION = 2;
        public static final int UPDATE_ERROR_UPDATE_FILE_INVALID = 3;
        public static final int UPDATE_ERROR_FILE_NOT_FOUND = 4;
        public static final int UPDATE_ERROR_BATTERY_LOW = 5;
        public InstallSystemUpdateCallback() { }
        /** AOSP: empty body -- "Method invoked when there was an error while
         *  installing an update." */
        public void onInstallUpdateError(int errorCode, String errorMessage) { }
    }
    public static final int ENCRYPTION_STATUS_UNSUPPORTED = 0;
    public static final int ENCRYPTION_STATUS_INACTIVE    = 1;
    public static final int ENCRYPTION_STATUS_ACTIVE      = 3;
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    public DevicePolicyManager() {}
    public DevicePolicyManager(Context context) {}

    public boolean isAdminActive(ComponentName admin) { return false; }
    public boolean isDeviceOwnerApp(String packageName) { return false; }
    /** @hide (used by Google Play services): the device owner's package; none here. */
    public String getDeviceOwner() { return null; }
    public boolean isProfileOwnerApp(String packageName) { return false; }
    // @hide/@SystemApi getProfileOwner(): the profile owner component of the
    // calling user's profile, or null if none. Mac.apk models a single
    // UNMANAGED user (isProfileOwnerApp/isDeviceOwnerApp both report false), so
    // there is no profile owner -- null is the device-faithful answer. The real
    // Play Store's device-state scan (wxm.r on a bg executor) calls this and
    // NoSuchMethodError'd without it.
    public ComponentName getProfileOwner() { return null; }
    // @hide/@SystemApi getDeviceOwnerComponentOnAnyUser(): the device owner's
    // admin component across all users, or null if the device has no device
    // owner. Mac.apk models a single UNMANAGED device, so null is the
    // device-faithful answer. The Play Store's device-state scan calls this on a
    // background executor and NoSuchMethodError'd without it.
    public ComponentName getDeviceOwnerComponentOnAnyUser() { return null; }
    public boolean isManagedProfile(ComponentName admin) { return false; }
    public boolean isDeviceManaged() { return false; }
    public java.util.List<ComponentName> getActiveAdmins() { return new java.util.ArrayList<>(); }
    public int getPasswordQuality(ComponentName admin) { return PASSWORD_QUALITY_UNSPECIFIED; }
    public boolean isActivePasswordSufficient() { return true; }
    public int getStorageEncryptionStatus() { return ENCRYPTION_STATUS_UNSUPPORTED; }
    public long getMaximumTimeToLock(ComponentName admin) { return 0; }
    public int getKeyguardDisabledFeatures(ComponentName admin) { return KEYGUARD_DISABLE_FEATURES_NONE; }
    public boolean getCameraDisabled(ComponentName admin) { return false; }
    public CharSequence getDeviceOwnerOrganizationName() { return null; }
    public java.util.List<String> getPermittedInputMethods() { return null; }

    // AOSP core/java/android/app/admin/DevicePolicyManager.java
    // (android13-release) :5893/8003/12889/12906 -- setMaximumTimeToLock,
    // setCameraDisabled, setOrganizationName, and getOrganizationName(admin)
    // were entirely missing (NoSuchMethodError for any real caller), which is
    // the same class of bug as the getters above -- except NOT the same fix.
    // The getters above are correctly hardcoded (isAdminActive() is always
    // false, matching the file header's "Mac.apk is never managed" design --
    // no ComponentName can ever be a real active admin here), and each of
    // these four is documented to throw SecurityException precisely when
    // called with an admin that "is not an active administrator" / "is not a
    // profile owner". Since that is unconditionally true on Mac.apk, throwing
    // is the AOSP-faithful behavior for a genuinely unmanaged device -- not a
    // silently-successful setter, which no real unmanaged phone would give an
    // app that (incorrectly) tries to disable the camera without ever having
    // been granted admin rights.
    public void setMaximumTimeToLock(ComponentName admin, long timeMs) {
        throw new SecurityException(admin + " is not an active admin: no device admin can be"
            + " activated on Mac.apk");
    }
    public void setCameraDisabled(ComponentName admin, boolean disabled) {
        throw new SecurityException(admin + " is not an active admin: no device admin can be"
            + " activated on Mac.apk");
    }
    public void setOrganizationName(ComponentName admin, CharSequence title) {
        throw new SecurityException(admin + " is not a device or profile owner: no device admin"
            + " can be activated on Mac.apk");
    }
    public CharSequence getOrganizationName(ComponentName admin) {
        throw new SecurityException(admin + " is not a profile owner: no device admin can be"
            + " activated on Mac.apk");
    }

    public void acknowledgeDeviceCompliant() {
        throw notAdmin("acknowledgeDeviceCompliant");
    }

    public void addCrossProfileIntentFilter(android.content.ComponentName componentName0, android.content.IntentFilter intentFilter1, int value2) {
        throw notAdmin("addCrossProfileIntentFilter");
    }

    public boolean addCrossProfileWidgetProvider(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("addCrossProfileWidgetProvider");
    }

    public int addOverrideApn(android.content.ComponentName componentName0, android.telephony.data.ApnSetting apnSetting1) {
        throw notAdmin("addOverrideApn");
    }

    public void addPersistentPreferredActivity(android.content.ComponentName componentName0, android.content.IntentFilter intentFilter1, android.content.ComponentName componentName2) {
        throw notAdmin("addPersistentPreferredActivity");
    }

    public void addUserRestriction(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("addUserRestriction");
    }

    public void addUserRestrictionGlobally(String name0) {
        throw notAdmin("addUserRestrictionGlobally");
    }

    public boolean bindDeviceAdminServiceAsUser(android.content.ComponentName componentName0, android.content.Intent intent1, android.content.ServiceConnection serviceConnection2, int value3, android.os.UserHandle userHandle4) {
        throw notAdmin("bindDeviceAdminServiceAsUser");
    }

    /** AOSP: "Returns true if the caller is running on a device where an admin can 
     *  grant permissions related to device sensors... a signal that the device is a 
     *  fully-managed device where personal usage is discouraged." No admin exists 
     *  here. */
    public boolean canAdminGrantSensorsPermissions() {
        return false;
    }

    /** AOSP: whether the DEVICE HARDWARE supports turning USB data signalling off. 
     *  macOS exposes no such control to us, so the capability is absent -- and an 
     *  admin must not be told it can disable something we cannot. */
    public boolean canUsbDataSignalingBeDisabled() {
        return false;
    }

    public void clearApplicationUserData(android.content.ComponentName componentName0, String name1, java.util.concurrent.Executor executor2, android.app.admin.DevicePolicyManager.OnClearApplicationUserDataListener onClearApplicationUserDataListener3) {
        throw notAdmin("clearApplicationUserData");
    }

    public void clearCrossProfileIntentFilters(android.content.ComponentName componentName0) {
        throw notAdmin("clearCrossProfileIntentFilters");
    }

    @Deprecated
    public void clearDeviceOwnerApp(String name0) {
        throw notAdmin("clearDeviceOwnerApp");
    }

    public void clearPackagePersistentPreferredActivities(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("clearPackagePersistentPreferredActivities");
    }

    @Deprecated
    public void clearProfileOwner(android.content.ComponentName componentName0) {
        throw notAdmin("clearProfileOwner");
    }

    public boolean clearResetPasswordToken(android.content.ComponentName componentName0) {
        throw notAdmin("clearResetPasswordToken");
    }

    public void clearUserRestriction(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("clearUserRestriction");
    }

    /** AOSP: "@return intent for the activity, or null if the restriction is not set 
     *  by an admin." No admin sets any restriction here. */
    public android.content.Intent createAdminSupportIntent(String name0) {
        return null;
    }

    public android.os.UserHandle createAndManageUser(android.content.ComponentName componentName0, String name1, android.content.ComponentName componentName2, android.os.PersistableBundle persistableBundle3, int value4) {
        throw notAdmin("createAndManageUser");
    }

    public void enableSystemApp(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("enableSystemApp");
    }

    public int enableSystemApp(android.content.ComponentName componentName0, android.content.Intent intent1) {
        throw notAdmin("enableSystemApp");
    }

    /** AOSP: the account types an admin has blocked. None are blocked; an empty array 
     *  (not null) is what a caller can iterate. */
    public String[] getAccountTypesWithManagementDisabled() {
        return new String[0];
    }

    public java.util.Set<java.lang.String> getAffiliationIds(android.content.ComponentName componentName0) {
        throw notAdmin("getAffiliationIds");
    }

    public java.util.Set<java.lang.String> getAlwaysOnVpnLockdownWhitelist(android.content.ComponentName componentName0) {
        throw notAdmin("getAlwaysOnVpnLockdownWhitelist");
    }

    public String getAlwaysOnVpnPackage(android.content.ComponentName componentName0) {
        throw notAdmin("getAlwaysOnVpnPackage");
    }

    public int getAppFunctionsPolicy() {
        throw notAdmin("getAppFunctionsPolicy");
    }

    public android.os.Bundle getApplicationRestrictions(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("getApplicationRestrictions");
    }

    @Deprecated
    public String getApplicationRestrictionsManagingPackage(android.content.ComponentName componentName0) {
        throw notAdmin("getApplicationRestrictionsManagingPackage");
    }

    public boolean getAutoTimeEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("getAutoTimeEnabled");
    }

    public int getAutoTimePolicy() {
        throw notAdmin("getAutoTimePolicy");
    }

    /** AOSP: whether an admin requires automatic time. None does. @deprecated matches 
     *  AOSP. */
    @Deprecated
    public boolean getAutoTimeRequired() {
        return false;
    }

    public boolean getAutoTimeZoneEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("getAutoTimeZoneEnabled");
    }

    public int getAutoTimeZonePolicy() {
        throw notAdmin("getAutoTimeZonePolicy");
    }

    public java.util.List<android.os.UserHandle> getBindDeviceAdminTargetUsers(android.content.ComponentName componentName0) {
        throw notAdmin("getBindDeviceAdminTargetUsers");
    }

    public boolean getBluetoothContactSharingDisabled(android.content.ComponentName componentName0) {
        throw notAdmin("getBluetoothContactSharingDisabled");
    }

    @Deprecated
    public String getCertInstallerPackage(android.content.ComponentName componentName0) throws java.lang.SecurityException {
        throw notAdmin("getCertInstallerPackage");
    }

    public int getContentProtectionPolicy(android.content.ComponentName componentName0) {
        throw notAdmin("getContentProtectionPolicy");
    }

    /** AOSP: "@return The current credential manager policy" -- null when no policy is 
     *  set. */
    public android.app.admin.PackagePolicy getCredentialManagerPolicy() {
        return null;
    }

    @Deprecated
    public java.util.Set<java.lang.String> getCrossProfileCalendarPackages(android.content.ComponentName componentName0) {
        throw notAdmin("getCrossProfileCalendarPackages");
    }

    @Deprecated
    public boolean getCrossProfileCallerIdDisabled(android.content.ComponentName componentName0) {
        throw notAdmin("getCrossProfileCallerIdDisabled");
    }

    @Deprecated
    public boolean getCrossProfileContactsSearchDisabled(android.content.ComponentName componentName0) {
        throw notAdmin("getCrossProfileContactsSearchDisabled");
    }

    public java.util.Set<java.lang.String> getCrossProfilePackages(android.content.ComponentName componentName0) {
        throw notAdmin("getCrossProfilePackages");
    }

    public java.util.List<java.lang.String> getCrossProfileWidgetProviders(android.content.ComponentName componentName0) {
        throw notAdmin("getCrossProfileWidgetProviders");
    }

    public int getCurrentFailedPasswordAttempts() {
        throw notAdmin("getCurrentFailedPasswordAttempts");
    }

    public java.util.List<java.lang.String> getDelegatePackages(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("getDelegatePackages");
    }

    /** AOSP: "@return A list containing the scopes given to delegatePackage". AOSP 
     *  allows the DELEGATE itself to call this with a null admin -- that form needs no 
     *  owner -- while a non-null admin requires the caller to be the owner that 
     *  granted the delegation. Reproduce both halves: no package is a delegate of 
     *  anything here, so the delegate-side answer is the empty list AOSP returns, and 
     *  the owner-side call is refused. */
    public java.util.List<java.lang.String> getDelegatedScopes(android.content.ComponentName componentName0, String name1) {
        if (componentName0 != null) throw notAdmin("getDelegatedScopes");
        return new java.util.ArrayList<java.lang.String>();
    }

    /** AOSP: the device-owner lock-screen message, null when none is set. */
    public CharSequence getDeviceOwnerLockScreenInfo() {
        return null;
    }

    /** AOSP: "@return the package name of the device policy management role holder", 
     *  null when the role is unfilled -- and there is no role holder here. */
    public String getDevicePolicyManagementRoleHolderPackage() {
        return null;
    }

    public CharSequence getEndUserSessionMessage(android.content.ComponentName componentName0) {
        throw notAdmin("getEndUserSessionMessage");
    }

    public String getEnrollmentSpecificId() {
        throw notAdmin("getEnrollmentSpecificId");
    }

    public android.app.admin.FactoryResetProtectionPolicy getFactoryResetProtectionPolicy(android.content.ComponentName componentName0) {
        throw notAdmin("getFactoryResetProtectionPolicy");
    }

    public String getGlobalPrivateDnsHost(android.content.ComponentName componentName0) {
        throw notAdmin("getGlobalPrivateDnsHost");
    }

    public int getGlobalPrivateDnsMode(android.content.ComponentName componentName0) {
        throw notAdmin("getGlobalPrivateDnsMode");
    }

    public java.util.List<byte[]> getInstalledCaCerts(android.content.ComponentName componentName0) {
        throw notAdmin("getInstalledCaCerts");
    }

    public java.util.List<java.lang.String> getKeepUninstalledPackages(android.content.ComponentName componentName0) {
        throw notAdmin("getKeepUninstalledPackages");
    }

    public java.util.Map<java.lang.Integer,java.util.Set<java.lang.String>> getKeyPairGrants(String name0) {
        throw notAdmin("getKeyPairGrants");
    }

    public int getLockTaskFeatures(android.content.ComponentName componentName0) {
        throw notAdmin("getLockTaskFeatures");
    }

    public String[] getLockTaskPackages(android.content.ComponentName componentName0) {
        throw notAdmin("getLockTaskPackages");
    }

    public CharSequence getLongSupportMessage(android.content.ComponentName componentName0) {
        throw notAdmin("getLongSupportMessage");
    }

    public android.app.admin.PackagePolicy getManagedProfileCallerIdAccessPolicy() {
        throw notAdmin("getManagedProfileCallerIdAccessPolicy");
    }

    public android.app.admin.PackagePolicy getManagedProfileContactsAccessPolicy() {
        throw notAdmin("getManagedProfileContactsAccessPolicy");
    }

    public long getManagedProfileMaximumTimeOff(android.content.ComponentName componentName0) {
        throw notAdmin("getManagedProfileMaximumTimeOff");
    }

    /** AOSP: @NonNull -- the policy in force. With no work profile every subscription 
     *  is personal, which is AOSP's own default. */
    public android.app.admin.ManagedSubscriptionsPolicy getManagedSubscriptionsPolicy() {
        return new ManagedSubscriptionsPolicy(
                ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS);
    }

    /** AOSP: "@return The current maximum number of failed password attempts... 0 
     *  means there is no restriction." Nothing sets one. */
    public int getMaximumFailedPasswordsForWipe(android.content.ComponentName componentName0) {
        return 0;
    }

    public java.util.List<java.lang.String> getMeteredDataDisabledPackages(android.content.ComponentName componentName0) {
        throw notAdmin("getMeteredDataDisabledPackages");
    }

    /** AOSP: the minimum Wi-Fi security an admin requires; WIFI_SECURITY_OPEN is 
     *  AOSP's value for "no requirement". */
    public int getMinimumRequiredWifiSecurityLevel() {
        return WIFI_SECURITY_OPEN;
    }

    public int getMtePolicy() {
        throw notAdmin("getMtePolicy");
    }

    /** AOSP: NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY when no admin has set one. */
    public int getNearbyAppStreamingPolicy() {
        return NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
    }

    /** AOSP: NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY when no admin has set one. */
    public int getNearbyNotificationStreamingPolicy() {
        return NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
    }

    @Deprecated
    public int getOrganizationColor(android.content.ComponentName componentName0) {
        throw notAdmin("getOrganizationColor");
    }

    public java.util.List<android.telephony.data.ApnSetting> getOverrideApns(android.content.ComponentName componentName0) {
        throw notAdmin("getOverrideApns");
    }

    public android.app.admin.DevicePolicyManager getParentProfileInstance(android.content.ComponentName componentName0) {
        throw notAdmin("getParentProfileInstance");
    }

    /** AOSP: "@return The current password complexity" and it throws SecurityException 
     *  only when the caller LACKS REQUEST_PASSWORD_COMPLEXITY -- which Mac.apk grants 
     *  (see Activity.requestPermissions), so the permission branch does not apply. 
     *  There is no lock screen and therefore no password, which is 
     *  PASSWORD_COMPLEXITY_NONE. */
    public int getPasswordComplexity() {
        return PASSWORD_COMPLEXITY_NONE;
    }

    /** AOSP: "@return The password expiration time, in milliseconds since epoch. 
     *  Returns 0 if not configured." */
    public long getPasswordExpiration(android.content.ComponentName componentName0) {
        return 0L;
    }

    /** AOSP: "@return The timeout for the given admin or the minimum of all timeouts, 
     *  0 if not configured." */
    public long getPasswordExpirationTimeout(android.content.ComponentName componentName0) {
        return 0L;
    }

    /** AOSP: "@return The length of the password history" -- 0 when no admin sets one. */
    public int getPasswordHistoryLength(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP body: "if (!pm.hasSystemFeature(FEATURE_SECURE_LOCK_SCREEN)) return 0; 
     *  return MAX_PASSWORD_LENGTH;" -- Mac.apk has no secure lock screen 
     *  (KeyguardManager.isDeviceSecure() is false), so this takes AOSP's FIRST branch. 
     *  Returning 16 would claim a lock screen we do not have. */
    public int getPasswordMaximumLength(int value0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumLength(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumLetters(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumLowerCase(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumNonLetter(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumNumeric(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumSymbols(android.content.ComponentName componentName0) {
        return 0;
    }

    /** AOSP: 0 when no admin sets a minimum. @deprecated matches AOSP. */
    @Deprecated
    public int getPasswordMinimumUpperCase(android.content.ComponentName componentName0) {
        return 0;
    }

    public android.app.admin.SystemUpdateInfo getPendingSystemUpdate(android.content.ComponentName componentName0) {
        throw notAdmin("getPendingSystemUpdate");
    }

    public int getPermissionGrantState(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("getPermissionGrantState");
    }

    /** AOSP: the runtime-permission policy an admin installed; 
     *  PERMISSION_POLICY_PROMPT is the unmanaged default. */
    public int getPermissionPolicy(android.content.ComponentName componentName0) {
        return PERMISSION_POLICY_PROMPT;
    }

    public java.util.List<java.lang.String> getPermittedAccessibilityServices(android.content.ComponentName componentName0) {
        throw notAdmin("getPermittedAccessibilityServices");
    }

    public java.util.List<java.lang.String> getPermittedCrossProfileNotificationListeners(android.content.ComponentName componentName0) {
        throw notAdmin("getPermittedCrossProfileNotificationListeners");
    }

    public java.util.List<java.lang.String> getPermittedInputMethods(android.content.ComponentName componentName0) {
        throw notAdmin("getPermittedInputMethods");
    }

    public int getPersonalAppsSuspendedReasons(android.content.ComponentName componentName0) {
        throw notAdmin("getPersonalAppsSuspendedReasons");
    }

    public java.util.List<android.app.admin.PreferentialNetworkServiceConfig> getPreferentialNetworkServiceConfigs() {
        throw notAdmin("getPreferentialNetworkServiceConfigs");
    }

    public int getRequiredPasswordComplexity() {
        throw notAdmin("getRequiredPasswordComplexity");
    }

    /** AOSP: "@return The timeout in milliseconds or 0 if not configured for the 
     *  provided admin." */
    public long getRequiredStrongAuthTimeout(android.content.ComponentName componentName0) {
        return 0L;
    }

    /** AOSP: @NonNull -- the device-policy resource overrides. See 
     *  DevicePolicyResourcesManager: with no override store every lookup falls through 
     *  to the caller's own default, which is a real answer rather than a stub. */
    public android.app.admin.DevicePolicyResourcesManager getResources() {
        return mResources;
    }

    /** AOSP: whether an admin disabled screen capture. None has. */
    public boolean getScreenCaptureDisabled(android.content.ComponentName componentName0) {
        return false;
    }

    public java.util.List<android.os.UserHandle> getSecondaryUsers(android.content.ComponentName componentName0) {
        throw notAdmin("getSecondaryUsers");
    }

    public CharSequence getShortSupportMessage(android.content.ComponentName componentName0) {
        throw notAdmin("getShortSupportMessage");
    }

    public CharSequence getStartUserSessionMessage(android.content.ComponentName componentName0) {
        throw notAdmin("getStartUserSessionMessage");
    }

    /** AOSP: whether the admin REQUESTED encryption (distinct from 
     *  getStorageEncryptionStatus, which reports the device state). No admin requests 
     *  it. @deprecated matches AOSP. */
    @Deprecated
    public boolean getStorageEncryption(android.content.ComponentName componentName0) {
        return false;
    }

    public java.util.Set<java.lang.Integer> getSubscriptionIds() {
        throw notAdmin("getSubscriptionIds");
    }

    /** AOSP: "@return The current policy object, or null if no policy is set." */
    public android.app.admin.SystemUpdatePolicy getSystemUpdatePolicy() {
        return null;
    }

    public android.os.PersistableBundle getTransferOwnershipBundle() {
        throw notAdmin("getTransferOwnershipBundle");
    }

    /** AOSP: "@return configuration for the given trust agent" -- null when the agent 
     *  is not configured by an admin. */
    public java.util.List<android.os.PersistableBundle> getTrustAgentConfiguration(android.content.ComponentName componentName0, android.content.ComponentName componentName1) {
        return null;
    }

    public java.util.List<java.lang.String> getUserControlDisabledPackages(android.content.ComponentName componentName0) {
        throw notAdmin("getUserControlDisabledPackages");
    }

    public android.os.Bundle getUserRestrictions(android.content.ComponentName componentName0) {
        throw notAdmin("getUserRestrictions");
    }

    public android.os.Bundle getUserRestrictionsGlobally() {
        throw notAdmin("getUserRestrictionsGlobally");
    }

    public String getWifiMacAddress(android.content.ComponentName componentName0) {
        throw notAdmin("getWifiMacAddress");
    }

    public android.app.admin.WifiSsidPolicy getWifiSsidPolicy() {
        throw notAdmin("getWifiSsidPolicy");
    }

    public boolean grantKeyPairToApp(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("grantKeyPairToApp");
    }

    public boolean grantKeyPairToWifiAuth(String name0) {
        throw notAdmin("grantKeyPairToWifiAuth");
    }

    public boolean hasCaCertInstalled(android.content.ComponentName componentName0, byte[] data1) {
        throw notAdmin("hasCaCertInstalled");
    }

    public boolean hasGrantedPolicy(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("hasGrantedPolicy");
    }

    public boolean hasKeyPair(String name0) {
        throw notAdmin("hasKeyPair");
    }

    public boolean hasLockdownAdminConfiguredNetworks(android.content.ComponentName componentName0) {
        throw notAdmin("hasLockdownAdminConfiguredNetworks");
    }

    public boolean installCaCert(android.content.ComponentName componentName0, byte[] data1) {
        throw notAdmin("installCaCert");
    }

    public boolean installExistingPackage(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("installExistingPackage");
    }

    public boolean installKeyPair(android.content.ComponentName componentName0, java.security.PrivateKey privateKey1, java.security.cert.Certificate certificate2, String name3) {
        throw notAdmin("installKeyPair");
    }

    public boolean installKeyPair(android.content.ComponentName componentName0, java.security.PrivateKey privateKey1, java.security.cert.Certificate[] certificates2, String name3, boolean enabled4) {
        throw notAdmin("installKeyPair");
    }

    public boolean installKeyPair(android.content.ComponentName componentName0, java.security.PrivateKey privateKey1, java.security.cert.Certificate[] certificates2, String name3, int value4) {
        throw notAdmin("installKeyPair");
    }

    public void installSystemUpdate(android.content.ComponentName componentName0, android.net.Uri uri1, java.util.concurrent.Executor executor2, android.app.admin.DevicePolicyManager.InstallSystemUpdateCallback installSystemUpdateCallback3) {
        throw notAdmin("installSystemUpdate");
    }

    public boolean isActivePasswordSufficientForDeviceRequirement() {
        throw notAdmin("isActivePasswordSufficientForDeviceRequirement");
    }

    /** AOSP: whether the user is affiliated with the device owner. There is no device 
     *  owner. */
    public boolean isAffiliatedUser() {
        return false;
    }

    public boolean isAlwaysOnVpnLockdownEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isAlwaysOnVpnLockdownEnabled");
    }

    public boolean isApplicationHidden(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("isApplicationHidden");
    }

    public boolean isBackupServiceEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isBackupServiceEnabled");
    }

    /** AOSP: whether the caller was delegated app-restriction management. Nothing 
     *  delegates here. @deprecated matches AOSP. */
    @Deprecated
    public boolean isCallerApplicationRestrictionsManagingPackage() {
        return false;
    }

    public boolean isCommonCriteriaModeEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isCommonCriteriaModeEnabled");
    }

    public boolean isComplianceAcknowledgementRequired() {
        throw notAdmin("isComplianceAcknowledgementRequired");
    }

    public boolean isDeviceFinanced() {
        throw notAdmin("isDeviceFinanced");
    }

    /** AOSP: whether the device supports attesting device IDs from hardware keystore. 
     *  Mac.apk has no Android hardware keystore, so it cannot -- and an admin that 
     *  believed otherwise would issue an attestation request that could never be 
     *  satisfied. */
    public boolean isDeviceIdAttestationSupported() {
        return false;
    }

    public boolean isEphemeralUser(android.content.ComponentName componentName0) {
        throw notAdmin("isEphemeralUser");
    }

    public boolean isKeyPairGrantedToWifiAuth(String name0) {
        throw notAdmin("isKeyPairGrantedToWifiAuth");
    }

    /** AOSP: "@return true if the package is allowed to enter lock task mode." Nothing 
     *  is allowlisted, and Activity.startLockTask above is likewise inert. */
    public boolean isLockTaskPermitted(String name0) {
        return false;
    }

    /** AOSP: whether a device owner enabled the logout affordance for secondary users. 
     *  There are no secondary users. */
    public boolean isLogoutEnabled() {
        return false;
    }

    public boolean isMasterVolumeMuted(android.content.ComponentName componentName0) {
        throw notAdmin("isMasterVolumeMuted");
    }

    /** AOSP: whether a memory-tagging policy is enforced. STATIC in AOSP. macOS has no 
     *  MTE and no admin to require it. */
    public static boolean isMtePolicyEnforced() {
        return false;
    }

    public boolean isNetworkLoggingEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isNetworkLoggingEnabled");
    }

    /** AOSP: whether the device is org-owned with a work profile. It is neither. */
    public boolean isOrganizationOwnedDeviceWithManagedProfile() {
        return false;
    }

    public boolean isOverrideApnEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isOverrideApnEnabled");
    }

    public boolean isPackageSuspended(android.content.ComponentName componentName0, String name1) throws android.content.pm.PackageManager.NameNotFoundException {
        throw notAdmin("isPackageSuspended");
    }

    public boolean isPreferentialNetworkServiceEnabled() {
        throw notAdmin("isPreferentialNetworkServiceEnabled");
    }

    /** AOSP: "@return whether provisioning is allowed" for the given 
     *  ACTION_PROVISION_* action. Mac.apk cannot provision a work profile or a device 
     *  owner at all, so no action is allowed -- telling an app otherwise would send it 
     *  into a provisioning flow that cannot complete. */
    public boolean isProvisioningAllowed(String name0) {
        return false;
    }

    public boolean isResetPasswordTokenActive(android.content.ComponentName componentName0) {
        throw notAdmin("isResetPasswordTokenActive");
    }

    /** AOSP: "Checks if it's safe to run operations that can be affected by the given 
     *  reason" -- unsafe only while the device is in a transient state such as 
     *  driving. Mac.apk is never in one. */
    public boolean isSafeOperation(int value0) {
        return true;
    }

    public boolean isSecurityLoggingEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("isSecurityLoggingEnabled");
    }

    public boolean isStatusBarDisabled() {
        throw notAdmin("isStatusBarDisabled");
    }

    public boolean isUninstallBlocked(android.content.ComponentName componentName0, String name1) {
        // AOSP DevicePolicyManagerService.isUninstallBlocked is a QUERY, not an
        // admin-only operation: it reports whether any active admin has blocked
        // uninstall of the package and, with no admin, returns false. Mac.apk has
        // no device-admin activation, so nothing blocks uninstall. Returning false
        // (not throwing) matters because the Play Store calls this during install
        // and an exception aborts the flow.
        return false;
    }

    /** AOSP: whether the device supports unique-device attestation from hardware 
     *  keystore. Same reason as isDeviceIdAttestationSupported. */
    public boolean isUniqueDeviceAttestationSupported() {
        return false;
    }

    /** AOSP: whether USB data signalling is currently enabled. TRUE is the value on 
     *  any device where no admin disabled it -- and canUsbDataSignalingBeDisabled() 
     *  above says it cannot be disabled here, so true is the only consistent answer. */
    public boolean isUsbDataSignalingEnabled() {
        return true;
    }

    public boolean isUsingUnifiedPassword(android.content.ComponentName componentName0) {
        throw notAdmin("isUsingUnifiedPassword");
    }

    public java.util.List<android.os.UserHandle> listForegroundAffiliatedUsers() {
        throw notAdmin("listForegroundAffiliatedUsers");
    }

    public void lockNow() {
        throw notAdmin("lockNow");
    }

    public void lockNow(int value0) {
        throw notAdmin("lockNow");
    }

    public int logoutUser(android.content.ComponentName componentName0) {
        throw notAdmin("logoutUser");
    }

    public void reboot(android.content.ComponentName componentName0) {
        throw notAdmin("reboot");
    }

    public void removeActiveAdmin(android.content.ComponentName componentName0) {
        throw notAdmin("removeActiveAdmin");
    }

    public boolean removeCrossProfileWidgetProvider(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("removeCrossProfileWidgetProvider");
    }

    public boolean removeKeyPair(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("removeKeyPair");
    }

    public boolean removeOverrideApn(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("removeOverrideApn");
    }

    public boolean removeUser(android.content.ComponentName componentName0, android.os.UserHandle userHandle1) {
        throw notAdmin("removeUser");
    }

    public boolean requestBugreport(android.content.ComponentName componentName0) {
        throw notAdmin("requestBugreport");
    }

    @Deprecated
    public boolean resetPassword(String name0, int value1) {
        throw notAdmin("resetPassword");
    }

    public boolean resetPasswordWithToken(android.content.ComponentName componentName0, String name1, byte[] data2, int value3) {
        throw notAdmin("resetPasswordWithToken");
    }

    public java.util.List<android.app.admin.NetworkEvent> retrieveNetworkLogs(android.content.ComponentName componentName0, long value1) {
        throw notAdmin("retrieveNetworkLogs");
    }

    public java.util.List<android.app.admin.SecurityLog.SecurityEvent> retrievePreRebootSecurityLogs(android.content.ComponentName componentName0) {
        throw notAdmin("retrievePreRebootSecurityLogs");
    }

    public java.util.List<android.app.admin.SecurityLog.SecurityEvent> retrieveSecurityLogs(android.content.ComponentName componentName0) {
        throw notAdmin("retrieveSecurityLogs");
    }

    public boolean revokeKeyPairFromApp(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("revokeKeyPairFromApp");
    }

    public boolean revokeKeyPairFromWifiAuth(String name0) {
        throw notAdmin("revokeKeyPairFromWifiAuth");
    }

    public void setAccountManagementDisabled(android.content.ComponentName componentName0, String name1, boolean enabled2) {
        throw notAdmin("setAccountManagementDisabled");
    }

    public void setAffiliationIds(android.content.ComponentName componentName0, java.util.Set<java.lang.String> set1) {
        throw notAdmin("setAffiliationIds");
    }

    public void setAlwaysOnVpnPackage(android.content.ComponentName componentName0, String name1, boolean enabled2) throws android.content.pm.PackageManager.NameNotFoundException {
        throw notAdmin("setAlwaysOnVpnPackage");
    }

    public void setAlwaysOnVpnPackage(android.content.ComponentName componentName0, String name1, boolean enabled2, java.util.Set<java.lang.String> set3) throws android.content.pm.PackageManager.NameNotFoundException {
        throw notAdmin("setAlwaysOnVpnPackage");
    }

    public void setAppFunctionsPolicy(int value0) {
        throw notAdmin("setAppFunctionsPolicy");
    }

    public boolean setApplicationHidden(android.content.ComponentName componentName0, String name1, boolean enabled2) {
        throw notAdmin("setApplicationHidden");
    }

    public void setApplicationRestrictions(android.content.ComponentName componentName0, String name1, android.os.Bundle bundle2) {
        throw notAdmin("setApplicationRestrictions");
    }

    @Deprecated
    public void setApplicationRestrictionsManagingPackage(android.content.ComponentName componentName0, String name1) throws android.content.pm.PackageManager.NameNotFoundException {
        throw notAdmin("setApplicationRestrictionsManagingPackage");
    }

    public void setAutoTimeEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setAutoTimeEnabled");
    }

    public void setAutoTimePolicy(int value0) {
        throw notAdmin("setAutoTimePolicy");
    }

    @Deprecated
    public void setAutoTimeRequired(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setAutoTimeRequired");
    }

    public void setAutoTimeZoneEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setAutoTimeZoneEnabled");
    }

    public void setAutoTimeZonePolicy(int value0) {
        throw notAdmin("setAutoTimeZonePolicy");
    }

    public void setBackupServiceEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setBackupServiceEnabled");
    }

    public void setBluetoothContactSharingDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setBluetoothContactSharingDisabled");
    }

    @Deprecated
    public void setCertInstallerPackage(android.content.ComponentName componentName0, String name1) throws java.lang.SecurityException {
        throw notAdmin("setCertInstallerPackage");
    }

    public void setCommonCriteriaModeEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setCommonCriteriaModeEnabled");
    }

    public void setConfiguredNetworksLockdownState(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setConfiguredNetworksLockdownState");
    }

    public void setContentProtectionPolicy(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setContentProtectionPolicy");
    }

    public void setCredentialManagerPolicy(android.app.admin.PackagePolicy packagePolicy0) {
        throw notAdmin("setCredentialManagerPolicy");
    }

    @Deprecated
    public void setCrossProfileCalendarPackages(android.content.ComponentName componentName0, java.util.Set<java.lang.String> set1) {
        throw notAdmin("setCrossProfileCalendarPackages");
    }

    @Deprecated
    public void setCrossProfileCallerIdDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setCrossProfileCallerIdDisabled");
    }

    @Deprecated
    public void setCrossProfileContactsSearchDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setCrossProfileContactsSearchDisabled");
    }

    public void setCrossProfilePackages(android.content.ComponentName componentName0, java.util.Set<java.lang.String> set1) {
        throw notAdmin("setCrossProfilePackages");
    }

    public void setDefaultDialerApplication(String name0) {
        throw notAdmin("setDefaultDialerApplication");
    }

    public void setDefaultSmsApplication(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("setDefaultSmsApplication");
    }

    public void setDelegatedScopes(android.content.ComponentName componentName0, String name1, java.util.List<java.lang.String> list2) {
        throw notAdmin("setDelegatedScopes");
    }

    public void setDeviceOwnerLockScreenInfo(android.content.ComponentName componentName0, CharSequence text1) {
        throw notAdmin("setDeviceOwnerLockScreenInfo");
    }

    public void setEndUserSessionMessage(android.content.ComponentName componentName0, CharSequence text1) {
        throw notAdmin("setEndUserSessionMessage");
    }

    public void setFactoryResetProtectionPolicy(android.content.ComponentName componentName0, android.app.admin.FactoryResetProtectionPolicy factoryResetProtectionPolicy1) {
        throw notAdmin("setFactoryResetProtectionPolicy");
    }

    public int setGlobalPrivateDnsModeOpportunistic(android.content.ComponentName componentName0) {
        throw notAdmin("setGlobalPrivateDnsModeOpportunistic");
    }

    public int setGlobalPrivateDnsModeSpecifiedHost(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("setGlobalPrivateDnsModeSpecifiedHost");
    }

    public void setGlobalSetting(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("setGlobalSetting");
    }

    public void setKeepUninstalledPackages(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setKeepUninstalledPackages");
    }

    public boolean setKeyPairCertificate(android.content.ComponentName componentName0, String name1, java.util.List<java.security.cert.Certificate> list2, boolean enabled3) {
        throw notAdmin("setKeyPairCertificate");
    }

    public boolean setKeyguardDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setKeyguardDisabled");
    }

    public void setKeyguardDisabledFeatures(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setKeyguardDisabledFeatures");
    }

    public void setLocationEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setLocationEnabled");
    }

    public void setLockTaskFeatures(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setLockTaskFeatures");
    }

    public void setLockTaskPackages(android.content.ComponentName componentName0, String[] strings1) throws java.lang.SecurityException {
        throw notAdmin("setLockTaskPackages");
    }

    public void setLogoutEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setLogoutEnabled");
    }

    public void setLongSupportMessage(android.content.ComponentName componentName0, CharSequence text1) {
        throw notAdmin("setLongSupportMessage");
    }

    public void setManagedProfileCallerIdAccessPolicy(android.app.admin.PackagePolicy packagePolicy0) {
        throw notAdmin("setManagedProfileCallerIdAccessPolicy");
    }

    public void setManagedProfileContactsAccessPolicy(android.app.admin.PackagePolicy packagePolicy0) {
        throw notAdmin("setManagedProfileContactsAccessPolicy");
    }

    public void setManagedProfileMaximumTimeOff(android.content.ComponentName componentName0, long value1) {
        throw notAdmin("setManagedProfileMaximumTimeOff");
    }

    public void setManagedSubscriptionsPolicy(android.app.admin.ManagedSubscriptionsPolicy managedSubscriptionsPolicy0) {
        throw notAdmin("setManagedSubscriptionsPolicy");
    }

    public void setMasterVolumeMuted(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setMasterVolumeMuted");
    }

    public void setMaximumFailedPasswordsForWipe(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setMaximumFailedPasswordsForWipe");
    }

    public java.util.List<java.lang.String> setMeteredDataDisabledPackages(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setMeteredDataDisabledPackages");
    }

    public void setMinimumRequiredWifiSecurityLevel(int value0) {
        throw notAdmin("setMinimumRequiredWifiSecurityLevel");
    }

    public void setMtePolicy(int value0) {
        throw notAdmin("setMtePolicy");
    }

    public void setNearbyAppStreamingPolicy(int value0) {
        throw notAdmin("setNearbyAppStreamingPolicy");
    }

    public void setNearbyNotificationStreamingPolicy(int value0) {
        throw notAdmin("setNearbyNotificationStreamingPolicy");
    }

    public void setNetworkLoggingEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setNetworkLoggingEnabled");
    }

    @Deprecated
    public void setOrganizationColor(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setOrganizationColor");
    }

    public void setOrganizationId(String name0) {
        throw notAdmin("setOrganizationId");
    }

    public void setOverrideApnsEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setOverrideApnsEnabled");
    }

    public String[] setPackagesSuspended(android.content.ComponentName componentName0, String[] strings1, boolean enabled2) {
        throw notAdmin("setPackagesSuspended");
    }

    public void setPasswordExpirationTimeout(android.content.ComponentName componentName0, long value1) {
        throw notAdmin("setPasswordExpirationTimeout");
    }

    public void setPasswordHistoryLength(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordHistoryLength");
    }

    @Deprecated
    public void setPasswordMinimumLength(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumLength");
    }

    @Deprecated
    public void setPasswordMinimumLetters(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumLetters");
    }

    @Deprecated
    public void setPasswordMinimumLowerCase(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumLowerCase");
    }

    @Deprecated
    public void setPasswordMinimumNonLetter(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumNonLetter");
    }

    @Deprecated
    public void setPasswordMinimumNumeric(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumNumeric");
    }

    @Deprecated
    public void setPasswordMinimumSymbols(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumSymbols");
    }

    @Deprecated
    public void setPasswordMinimumUpperCase(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordMinimumUpperCase");
    }

    @Deprecated
    public void setPasswordQuality(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPasswordQuality");
    }

    public boolean setPermissionGrantState(android.content.ComponentName componentName0, String name1, String name2, int value3) {
        throw notAdmin("setPermissionGrantState");
    }

    public void setPermissionPolicy(android.content.ComponentName componentName0, int value1) {
        throw notAdmin("setPermissionPolicy");
    }

    public boolean setPermittedAccessibilityServices(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setPermittedAccessibilityServices");
    }

    public boolean setPermittedCrossProfileNotificationListeners(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setPermittedCrossProfileNotificationListeners");
    }

    public boolean setPermittedInputMethods(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setPermittedInputMethods");
    }

    public void setPersonalAppsSuspended(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setPersonalAppsSuspended");
    }

    public void setPreferentialNetworkServiceConfigs(java.util.List<android.app.admin.PreferentialNetworkServiceConfig> list0) {
        throw notAdmin("setPreferentialNetworkServiceConfigs");
    }

    public void setPreferentialNetworkServiceEnabled(boolean enabled0) {
        throw notAdmin("setPreferentialNetworkServiceEnabled");
    }

    public void setProfileEnabled(android.content.ComponentName componentName0) {
        throw notAdmin("setProfileEnabled");
    }

    public void setProfileName(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("setProfileName");
    }

    public void setRecommendedGlobalProxy(android.content.ComponentName componentName0, android.net.ProxyInfo proxyInfo1) {
        throw notAdmin("setRecommendedGlobalProxy");
    }

    public void setRequiredPasswordComplexity(int value0) {
        throw notAdmin("setRequiredPasswordComplexity");
    }

    public void setRequiredStrongAuthTimeout(android.content.ComponentName componentName0, long value1) {
        throw notAdmin("setRequiredStrongAuthTimeout");
    }

    public boolean setResetPasswordToken(android.content.ComponentName componentName0, byte[] data1) {
        throw notAdmin("setResetPasswordToken");
    }

    public void setRestrictionsProvider(android.content.ComponentName componentName0, android.content.ComponentName componentName1) {
        throw notAdmin("setRestrictionsProvider");
    }

    public void setScreenCaptureDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setScreenCaptureDisabled");
    }

    public void setSecureSetting(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("setSecureSetting");
    }

    public void setSecurityLoggingEnabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setSecurityLoggingEnabled");
    }

    public void setShortSupportMessage(android.content.ComponentName componentName0, CharSequence text1) {
        throw notAdmin("setShortSupportMessage");
    }

    public void setStartUserSessionMessage(android.content.ComponentName componentName0, CharSequence text1) {
        throw notAdmin("setStartUserSessionMessage");
    }

    public boolean setStatusBarDisabled(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setStatusBarDisabled");
    }

    @Deprecated
    public int setStorageEncryption(android.content.ComponentName componentName0, boolean enabled1) {
        throw notAdmin("setStorageEncryption");
    }

    public void setSystemSetting(android.content.ComponentName componentName0, String name1, String name2) {
        throw notAdmin("setSystemSetting");
    }

    public void setSystemUpdatePolicy(android.content.ComponentName componentName0, android.app.admin.SystemUpdatePolicy systemUpdatePolicy1) {
        throw notAdmin("setSystemUpdatePolicy");
    }

    public boolean setTime(android.content.ComponentName componentName0, long value1) {
        throw notAdmin("setTime");
    }

    public boolean setTimeZone(android.content.ComponentName componentName0, String name1) {
        throw notAdmin("setTimeZone");
    }

    public void setTrustAgentConfiguration(android.content.ComponentName componentName0, android.content.ComponentName componentName1, android.os.PersistableBundle persistableBundle2) {
        throw notAdmin("setTrustAgentConfiguration");
    }

    public void setUninstallBlocked(android.content.ComponentName componentName0, String name1, boolean enabled2) {
        throw notAdmin("setUninstallBlocked");
    }

    public void setUsbDataSignalingEnabled(boolean enabled0) {
        throw notAdmin("setUsbDataSignalingEnabled");
    }

    public void setUserControlDisabledPackages(android.content.ComponentName componentName0, java.util.List<java.lang.String> list1) {
        throw notAdmin("setUserControlDisabledPackages");
    }

    public void setUserIcon(android.content.ComponentName componentName0, android.graphics.Bitmap bitmap1) {
        throw notAdmin("setUserIcon");
    }

    public void setWifiSsidPolicy(android.app.admin.WifiSsidPolicy wifiSsidPolicy0) {
        throw notAdmin("setWifiSsidPolicy");
    }

    public int startUserInBackground(android.content.ComponentName componentName0, android.os.UserHandle userHandle1) {
        throw notAdmin("startUserInBackground");
    }

    public int stopUser(android.content.ComponentName componentName0, android.os.UserHandle userHandle1) {
        throw notAdmin("stopUser");
    }

    public boolean switchUser(android.content.ComponentName componentName0, android.os.UserHandle userHandle1) {
        throw notAdmin("switchUser");
    }

    public void transferOwnership(android.content.ComponentName componentName0, android.content.ComponentName componentName1, android.os.PersistableBundle persistableBundle2) {
        throw notAdmin("transferOwnership");
    }

    public void uninstallAllUserCaCerts(android.content.ComponentName componentName0) {
        throw notAdmin("uninstallAllUserCaCerts");
    }

    public void uninstallCaCert(android.content.ComponentName componentName0, byte[] data1) {
        throw notAdmin("uninstallCaCert");
    }

    public boolean updateOverrideApn(android.content.ComponentName componentName0, int value1, android.telephony.data.ApnSetting apnSetting2) {
        throw notAdmin("updateOverrideApn");
    }

    public void wipeData(int value0) {
        throw notAdmin("wipeData");
    }

    public void wipeData(int value0, CharSequence text1) {
        throw notAdmin("wipeData");
    }

    public void wipeDevice(int value0) {
        throw notAdmin("wipeDevice");
    }

    // ---- AOSP API-36 constant completion (android.app.admin.DevicePolicyManager) ----
    // Values extracted verbatim from the AOSP source for this class
    // (android16-release). javac INLINES a compile-time constant into the app at
    // ITS build time, so a shipped APK cannot observe a missing one at runtime --
    // these are here for our own framework code, for anything compiled against
    // THIS jar, and so the surface is complete rather than nearly complete.
    public static final String ACTION_ADD_DEVICE_ADMIN = "android.app.action.ADD_DEVICE_ADMIN";
    public static final String ACTION_ADMIN_POLICY_COMPLIANCE = "android.app.action.ADMIN_POLICY_COMPLIANCE";
    public static final String ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED = "android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED";
    public static final String ACTION_CHECK_POLICY_COMPLIANCE = "android.app.action.CHECK_POLICY_COMPLIANCE";
    public static final String ACTION_DEVICE_ADMIN_SERVICE = "android.app.action.DEVICE_ADMIN_SERVICE";
    public static final String ACTION_DEVICE_FINANCING_STATE_CHANGED = "android.app.admin.action.DEVICE_FINANCING_STATE_CHANGED";
    public static final String ACTION_DEVICE_OWNER_CHANGED = "android.app.action.DEVICE_OWNER_CHANGED";
    public static final String ACTION_DEVICE_POLICY_RESOURCE_UPDATED = "android.app.action.DEVICE_POLICY_RESOURCE_UPDATED";
    public static final String ACTION_GET_PROVISIONING_MODE = "android.app.action.GET_PROVISIONING_MODE";
    public static final String ACTION_MANAGED_PROFILE_PROVISIONED = "android.app.action.MANAGED_PROFILE_PROVISIONED";
    public static final String ACTION_PROFILE_OWNER_CHANGED = "android.app.action.PROFILE_OWNER_CHANGED";
    public static final String ACTION_PROVISIONING_SUCCESSFUL = "android.app.action.PROVISIONING_SUCCESSFUL";
    public static final String ACTION_PROVISION_MANAGED_DEVICE = "android.app.action.PROVISION_MANAGED_DEVICE";
    public static final String ACTION_PROVISION_MANAGED_PROFILE = "android.app.action.PROVISION_MANAGED_PROFILE";
    public static final String ACTION_SET_NEW_PARENT_PROFILE_PASSWORD = "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD";
    public static final String ACTION_SET_NEW_PASSWORD = "android.app.action.SET_NEW_PASSWORD";
    public static final String ACTION_START_ENCRYPTION = "android.app.action.START_ENCRYPTION";
    public static final String ACTION_SYSTEM_UPDATE_POLICY_CHANGED = "android.app.action.SYSTEM_UPDATE_POLICY_CHANGED";
    public static final int APP_FUNCTIONS_DISABLED = 1;
    public static final int APP_FUNCTIONS_DISABLED_CROSS_PROFILE = 2;
    public static final String DELEGATION_APP_RESTRICTIONS = "delegation-app-restrictions";
    public static final String DELEGATION_BLOCK_UNINSTALL = "delegation-block-uninstall";
    public static final String DELEGATION_CERT_INSTALL = "delegation-cert-install";
    public static final String DELEGATION_CERT_SELECTION = "delegation-cert-selection";
    public static final String DELEGATION_ENABLE_SYSTEM_APP = "delegation-enable-system-app";
    public static final String DELEGATION_INSTALL_EXISTING_PACKAGE = "delegation-install-existing-package";
    public static final String DELEGATION_KEEP_UNINSTALLED_PACKAGES = "delegation-keep-uninstalled-packages";
    public static final String DELEGATION_NETWORK_LOGGING = "delegation-network-logging";
    public static final String DELEGATION_PACKAGE_ACCESS = "delegation-package-access";
    public static final String DELEGATION_PERMISSION_GRANT = "delegation-permission-grant";
    public static final String DELEGATION_SECURITY_LOGGING = "delegation-security-logging";
    public static final int ENCRYPTION_STATUS_ACTIVATING = 2;
    public static final int ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY = 4;
    public static final int ENCRYPTION_STATUS_ACTIVE_PER_USER = 5;
    public static final String EXTRA_ADD_EXPLANATION = "android.app.extra.ADD_EXPLANATION";
    public static final String EXTRA_DELEGATION_SCOPES = "android.app.extra.DELEGATION_SCOPES";
    public static final String EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN";
    public static final String EXTRA_DEVICE_PASSWORD_REQUIREMENT_ONLY = "android.app.extra.DEVICE_PASSWORD_REQUIREMENT_ONLY";
    public static final String EXTRA_PASSWORD_COMPLEXITY = "android.app.extra.PASSWORD_COMPLEXITY";
    public static final String EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE = "android.app.extra.PROVISIONING_ACCOUNT_TO_MIGRATE";
    public static final String EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE";
    public static final String EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES = "android.app.extra.PROVISIONING_ALLOWED_PROVISIONING_MODES";
    public static final String EXTRA_PROVISIONING_ALLOW_OFFLINE = "android.app.extra.PROVISIONING_ALLOW_OFFLINE";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE = "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME";
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM";
    public static final String EXTRA_PROVISIONING_DISCLAIMERS = "android.app.extra.PROVISIONING_DISCLAIMERS";
    public static final String EXTRA_PROVISIONING_DISCLAIMER_CONTENT = "android.app.extra.PROVISIONING_DISCLAIMER_CONTENT";
    public static final String EXTRA_PROVISIONING_DISCLAIMER_HEADER = "android.app.extra.PROVISIONING_DISCLAIMER_HEADER";
    public static final String EXTRA_PROVISIONING_EMAIL_ADDRESS = "android.app.extra.PROVISIONING_EMAIL_ADDRESS";
    public static final String EXTRA_PROVISIONING_IMEI = "android.app.extra.PROVISIONING_IMEI";
    public static final String EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION = "android.app.extra.PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION";
    public static final String EXTRA_PROVISIONING_KEEP_SCREEN_ON = "android.app.extra.PROVISIONING_KEEP_SCREEN_ON";
    public static final String EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED = "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED";
    public static final String EXTRA_PROVISIONING_LOCALE = "android.app.extra.PROVISIONING_LOCALE";
    public static final String EXTRA_PROVISIONING_LOCAL_TIME = "android.app.extra.PROVISIONING_LOCAL_TIME";
    public static final String EXTRA_PROVISIONING_LOGO_URI = "android.app.extra.PROVISIONING_LOGO_URI";
    public static final String EXTRA_PROVISIONING_MAIN_COLOR = "android.app.extra.PROVISIONING_MAIN_COLOR";
    public static final String EXTRA_PROVISIONING_MODE = "android.app.extra.PROVISIONING_MODE";
    public static final String EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT = "android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT";
    public static final String EXTRA_PROVISIONING_SERIAL_NUMBER = "android.app.extra.PROVISIONING_SERIAL_NUMBER";
    public static final String EXTRA_PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT = "android.app.extra.PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT";
    public static final String EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS = "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS";
    public static final String EXTRA_PROVISIONING_SKIP_ENCRYPTION = "android.app.extra.PROVISIONING_SKIP_ENCRYPTION";
    public static final String EXTRA_PROVISIONING_SKIP_USER_CONSENT = "android.app.extra.PROVISIONING_SKIP_USER_CONSENT";
    public static final String EXTRA_PROVISIONING_TIME_ZONE = "android.app.extra.PROVISIONING_TIME_ZONE";
    public static final String EXTRA_PROVISIONING_USE_MOBILE_DATA = "android.app.extra.PROVISIONING_USE_MOBILE_DATA";
    public static final String EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY = "android.app.extra.PROVISIONING_WIFI_ANONYMOUS_IDENTITY";
    public static final String EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE = "android.app.extra.PROVISIONING_WIFI_CA_CERTIFICATE";
    public static final String EXTRA_PROVISIONING_WIFI_DOMAIN = "android.app.extra.PROVISIONING_WIFI_DOMAIN";
    public static final String EXTRA_PROVISIONING_WIFI_EAP_METHOD = "android.app.extra.PROVISIONING_WIFI_EAP_METHOD";
    public static final String EXTRA_PROVISIONING_WIFI_HIDDEN = "android.app.extra.PROVISIONING_WIFI_HIDDEN";
    public static final String EXTRA_PROVISIONING_WIFI_IDENTITY = "android.app.extra.PROVISIONING_WIFI_IDENTITY";
    public static final String EXTRA_PROVISIONING_WIFI_PAC_URL = "android.app.extra.PROVISIONING_WIFI_PAC_URL";
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD";
    public static final String EXTRA_PROVISIONING_WIFI_PHASE2_AUTH = "android.app.extra.PROVISIONING_WIFI_PHASE2_AUTH";
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_BYPASS = "android.app.extra.PROVISIONING_WIFI_PROXY_BYPASS";
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_HOST = "android.app.extra.PROVISIONING_WIFI_PROXY_HOST";
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_PORT = "android.app.extra.PROVISIONING_WIFI_PROXY_PORT";
    public static final String EXTRA_PROVISIONING_WIFI_SECURITY_TYPE = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE";
    public static final String EXTRA_PROVISIONING_WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID";
    public static final String EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE = "android.app.extra.PROVISIONING_WIFI_USER_CERTIFICATE";
    public static final String EXTRA_RESOURCE_IDS = "android.app.extra.RESOURCE_IDS";
    public static final String EXTRA_RESOURCE_TYPE = "android.app.extra.RESOURCE_TYPE";
    public static final int EXTRA_RESOURCE_TYPE_DRAWABLE = 1;
    public static final int EXTRA_RESOURCE_TYPE_STRING = 2;
    public static final String EXTRA_RESULT_LAUNCH_INTENT = "android.app.extra.RESULT_LAUNCH_INTENT";
    public static final int FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY = 1;
    public static final int FLAG_MANAGED_CAN_ACCESS_PARENT = 0x0002;
    public static final int FLAG_PARENT_CAN_ACCESS_MANAGED = 0x0001;
    public static final int ID_TYPE_BASE_INFO = 1;
    public static final int ID_TYPE_IMEI = 4;
    public static final int ID_TYPE_INDIVIDUAL_ATTESTATION = 16;
    public static final int ID_TYPE_MEID = 8;
    public static final int ID_TYPE_SERIAL = 2;
    public static final int INSTALLKEY_REQUEST_CREDENTIALS_ACCESS = 1;
    public static final int INSTALLKEY_SET_USER_SELECTABLE = 2;
    public static final int KEYGUARD_DISABLE_BIOMETRICS = DevicePolicyManager.KEYGUARD_DISABLE_FACE | DevicePolicyManager.KEYGUARD_DISABLE_IRIS | DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;
    public static final int KEYGUARD_DISABLE_FACE = 1 << 7;
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;
    public static final int KEYGUARD_DISABLE_FINGERPRINT = 1 << 5;
    public static final int KEYGUARD_DISABLE_IRIS = 1 << 8;
    public static final int KEYGUARD_DISABLE_REMOTE_INPUT = 1 << 6;
    public static final int KEYGUARD_DISABLE_SECURE_CAMERA = 1 << 1;
    public static final int KEYGUARD_DISABLE_SECURE_NOTIFICATIONS = 1 << 2;
    public static final int KEYGUARD_DISABLE_SHORTCUTS_ALL = 1 << 9;
    public static final int KEYGUARD_DISABLE_TRUST_AGENTS = 1 << 4;
    public static final int KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS = 1 << 3;
    public static final int KEYGUARD_DISABLE_WIDGETS_ALL = 1 << 0;
    public static final int LEAVE_ALL_SYSTEM_APPS_ENABLED = 0x0010;
    public static final int LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 1 << 6;
    public static final int MAKE_USER_EPHEMERAL = 0x0002;
    public static final String MIME_TYPE_PROVISIONING_NFC = "application/com.android.managedprovisioning";
    public static final int OPERATION_SAFETY_REASON_DRIVING_DISTRACTION = 1;
    public static final String POLICY_DISABLE_CAMERA = "policy_disable_camera";
    public static final String POLICY_DISABLE_SCREEN_CAPTURE = "policy_disable_screen_capture";
    public static final int PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1;
    public static final int PROVISIONING_MODE_MANAGED_PROFILE = 2;
    public static final int PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE = 3;
    public static final int RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT = 0x0002;
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;
    public static final int SKIP_SETUP_WIZARD = 0x0001;
}
