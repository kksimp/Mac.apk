// android.content.pm.PackageManager -- the bag of "what's installed and
// who can handle this intent" queries. v2 has one APK installed at a
// time, so most queries return empty / single-element results.
package android.content.pm;

import android.content.Intent;
import android.content.IntentFilter;
import java.util.Collections;
import java.util.List;

// AOSP fidelity: the deprecation usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("deprecation")
public abstract class PackageManager {
    public static final int PERMISSION_GRANTED = 0;
    public static final int PERMISSION_DENIED  = -1;
    // AOSP getPackageInfo/getApplicationInfo GET_* request bits, values taken
    // verbatim from frameworks/base PackageManager.java (L1038-L1365). These are
    // compiled into app bytecode as literal ints, so the VALUE is the contract;
    // a missing constant is only visible when an app reads it reflectively, but
    // our own code (and any caller that ORs them together) needs the full set to
    // reason about what a caller asked for.
    public static final int GET_ACTIVITIES   = 0x00000001;
    public static final int GET_RECEIVERS    = 0x00000002;
    public static final int GET_SERVICES     = 0x00000004;
    public static final int GET_PROVIDERS    = 0x00000008;
    public static final int GET_INSTRUMENTATION = 0x00000010;
    public static final int GET_INTENT_FILTERS  = 0x00000020;
    public static final int GET_SIGNATURES   = 0x00000040;
    // AOSP deliberately reuses 0x40 for the ResolveInfo-side request bit.
    public static final int GET_RESOLVED_FILTER = 0x00000040;
    public static final int GET_META_DATA    = 0x00000080;
    public static final int GET_GIDS         = 0x00000100;
    public static final int GET_SHARED_LIBRARY_FILES = 0x00000400;
    public static final int GET_URI_PERMISSION_PATTERNS = 0x00000800;
    public static final int GET_PERMISSIONS  = 0x00001000;
    public static final int GET_CONFIGURATIONS = 0x00004000;
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;

    // AOSP MATCH_* resolver bits (frameworks/base core/java/android/content/pm/
    // PackageManager.java, android14-release L970/L975/L1035/L1043 and the
    // MATCH_DIRECT_BOOT_* block). Values verbatim.
    //
    // These were MISSING from this file until v1.1.0, and their absence was
    // invisible for exactly the reason it was dangerous: they are compile-time
    // int constants, so an app that ORs MATCH_DISABLED_COMPONENTS into its flags
    // carries the literal 0x200 in its bytecode and links fine against a
    // framework that never declared the name. We simply received 0x200 in
    // `flags` and ignored it. That matters because MATCH_DISABLED_COMPONENTS is
    // how the metadata-holder pattern reads a DELIBERATELY DISABLED component:
    // androidx.appcompat.app.AppLocalesMetadataHolderService.getServiceInfo
    // builds `GET_META_DATA | MATCH_DISABLED_COMPONENTS` with the comment "The
    // service is marked as disabled so we need to include the following flags."
    // CameraX MetadataHolderService, androidx.car.app CarAppMetadataHolderService,
    // GMS ModuleDependencies and PropertiesServiceHolder all do the same.
    // MacApkPackageManager.macapkIsComponentEnabled honours them.
    //
    // GET_DISABLED_COMPONENTS / GET_DISABLED_UNTIL_USED_COMPONENTS are AOSP's
    // deprecated aliases and share the identical value; both names are declared
    // because older SDK-targeting code still references the GET_ spelling.
    public static final int MATCH_DISABLED_COMPONENTS            = 0x00000200;
    public static final int GET_DISABLED_COMPONENTS              = 0x00000200;
    public static final int MATCH_UNINSTALLED_PACKAGES           = 0x00002000;
    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;
    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS   = 0x00008000;
    public static final int MATCH_DEFAULT_ONLY                   = 0x00010000;
    public static final int MATCH_ALL                            = 0x00020000;
    public static final int MATCH_DIRECT_BOOT_UNAWARE            = 0x00040000;
    public static final int MATCH_DIRECT_BOOT_AWARE              = 0x00080000;
    public static final int MATCH_SYSTEM_ONLY                    = 0x00100000;
    public static final int MATCH_DIRECT_BOOT_AUTO               = 0x10000000;

    // AOSP API-26 install-reason constants (frameworks/base PackageManager.java,
    // android14-release). Part of the PackageInstaller session surface:
    // SessionParams.setInstallReason(INSTALL_REASON_USER) is what every store
    // passes (F-Droid and Aurora both). Compile-time constants, so apps carry
    // the literal; declared for our own code and reflective readers.
    public static final int INSTALL_REASON_UNKNOWN        = 0;
    public static final int INSTALL_REASON_POLICY         = 1;
    public static final int INSTALL_REASON_DEVICE_RESTORE = 2;
    public static final int INSTALL_REASON_DEVICE_SETUP   = 3;
    public static final int INSTALL_REASON_USER           = 4;


    // ---- AOSP public constants absent until v1.1.0 -------------------------
    //
    // javac INLINES a compile-time constant into the caller, so an app built
    // against the real SDK cannot observe a missing one at runtime -- which is
    // exactly why they went unnoticed. They still matter for three readers:
    // (1) our OWN framework code, which has to reason about the same values an
    // app ORs together; (2) reflective readers (a plugin host or test harness
    // enumerating PackageManager's fields); (3) anything comparing a RECEIVED
    // string (an Intent action, a hasSystemFeature name) against the constant.
    //
    // ⚠ A FEATURE_* constant being DECLARED says nothing about whether we HAVE
    // the feature. hasSystemFeature() is the only answer to that, and it is
    // unchanged: it reports what Mac.apk actually provides. Declaring the name
    // is the opposite of claiming the capability -- it lets an app ASK by the
    // right string and get our honest no.
    //
    // Values generated from the API-36 signature file, not retyped by hand.
    public static final int DELETE_ARCHIVE = 16; // 0x10
    public static final String EXTRA_VERIFICATION_ID = "android.content.pm.extra.VERIFICATION_ID";
    public static final String EXTRA_VERIFICATION_RESULT = "android.content.pm.extra.VERIFICATION_RESULT";
    public static final String FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS = "android.software.activities_on_secondary_displays";
    public static final String FEATURE_APP_WIDGETS = "android.software.app_widgets";
    public static final String FEATURE_AUDIO_LOW_LATENCY = "android.hardware.audio.low_latency";
    public static final String FEATURE_AUDIO_OUTPUT = "android.hardware.audio.output";
    public static final String FEATURE_AUDIO_PRO = "android.hardware.audio.pro";
    public static final String FEATURE_AUDIO_SPATIAL_HEADTRACKING_LOW_LATENCY = "android.hardware.audio.spatial.headtracking.low_latency";
    public static final String FEATURE_AUTOFILL = "android.software.autofill";
    public static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";
    public static final String FEATURE_BACKUP = "android.software.backup";
    public static final String FEATURE_BLUETOOTH = "android.hardware.bluetooth";
    public static final String FEATURE_BLUETOOTH_LE = "android.hardware.bluetooth_le";
    public static final String FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING = "android.hardware.bluetooth_le.channel_sounding";
    public static final String FEATURE_CAMERA = "android.hardware.camera";
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any";
    public static final String FEATURE_CAMERA_AR = "android.hardware.camera.ar";
    public static final String FEATURE_CAMERA_AUTOFOCUS = "android.hardware.camera.autofocus";
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING = "android.hardware.camera.capability.manual_post_processing";
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR = "android.hardware.camera.capability.manual_sensor";
    public static final String FEATURE_CAMERA_CAPABILITY_RAW = "android.hardware.camera.capability.raw";
    public static final String FEATURE_CAMERA_CONCURRENT = "android.hardware.camera.concurrent";
    public static final String FEATURE_CAMERA_EXTERNAL = "android.hardware.camera.external";
    public static final String FEATURE_CAMERA_FLASH = "android.hardware.camera.flash";
    public static final String FEATURE_CAMERA_FRONT = "android.hardware.camera.front";
    public static final String FEATURE_CAMERA_LEVEL_FULL = "android.hardware.camera.level.full";
    public static final String FEATURE_CANT_SAVE_STATE = "android.software.cant_save_state";
    public static final String FEATURE_COMPANION_DEVICE_SETUP = "android.software.companion_device_setup";
    public static final String FEATURE_CONNECTION_SERVICE = "android.software.connectionservice";
    public static final String FEATURE_CONSUMER_IR = "android.hardware.consumerir";
    public static final String FEATURE_CONTROLS = "android.software.controls";
    public static final String FEATURE_CREDENTIALS = "android.software.credentials";
    public static final String FEATURE_DEVICE_ADMIN = "android.software.device_admin";
    public static final String FEATURE_DEVICE_LOCK = "android.software.device_lock";
    public static final String FEATURE_EMBEDDED = "android.hardware.type.embedded";
    public static final String FEATURE_ETHERNET = "android.hardware.ethernet";
    public static final String FEATURE_EXPANDED_PICTURE_IN_PICTURE = "android.software.expanded_picture_in_picture";
    public static final String FEATURE_FACE = "android.hardware.biometrics.face";
    public static final String FEATURE_FAKETOUCH = "android.hardware.faketouch";
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT = "android.hardware.faketouch.multitouch.distinct";
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND = "android.hardware.faketouch.multitouch.jazzhand";
    public static final String FEATURE_FINGERPRINT = "android.hardware.fingerprint";
    public static final String FEATURE_FREEFORM_WINDOW_MANAGEMENT = "android.software.freeform_window_management";
    public static final String FEATURE_GAMEPAD = "android.hardware.gamepad";
    public static final String FEATURE_HARDWARE_KEYSTORE = "android.hardware.hardware_keystore";
    public static final String FEATURE_HIFI_SENSORS = "android.hardware.sensor.hifi_sensors";
    public static final String FEATURE_HOME_SCREEN = "android.software.home_screen";
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE = "android.hardware.identity_credential";
    public static final String FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS = "android.hardware.identity_credential_direct_access";
    public static final String FEATURE_INPUT_METHODS = "android.software.input_methods";
    public static final String FEATURE_IPSEC_TUNNELS = "android.software.ipsec_tunnels";
    public static final String FEATURE_IPSEC_TUNNEL_MIGRATION = "android.software.ipsec_tunnel_migration";
    public static final String FEATURE_IRIS = "android.hardware.biometrics.iris";
    public static final String FEATURE_KEYSTORE_APP_ATTEST_KEY = "android.hardware.keystore.app_attest_key";
    public static final String FEATURE_KEYSTORE_LIMITED_USE_KEY = "android.hardware.keystore.limited_use_key";
    public static final String FEATURE_KEYSTORE_SINGLE_USE_KEY = "android.hardware.keystore.single_use_key";
    public static final String FEATURE_LEANBACK = "android.software.leanback";
    public static final String FEATURE_LEANBACK_ONLY = "android.software.leanback_only";
    public static final String FEATURE_LIVE_TV = "android.software.live_tv";
    public static final String FEATURE_LIVE_WALLPAPER = "android.software.live_wallpaper";
    public static final String FEATURE_LOCATION = "android.hardware.location";
    public static final String FEATURE_LOCATION_GPS = "android.hardware.location.gps";
    public static final String FEATURE_LOCATION_NETWORK = "android.hardware.location.network";
    public static final String FEATURE_MANAGED_USERS = "android.software.managed_users";
    public static final String FEATURE_MICROPHONE = "android.hardware.microphone";
    public static final String FEATURE_MIDI = "android.software.midi";
    public static final String FEATURE_NFC = "android.hardware.nfc";
    public static final String FEATURE_NFC_BEAM = "android.sofware.nfc.beam";
    public static final String FEATURE_NFC_CHARGING = "android.hardware.nfc.charging";
    public static final String FEATURE_NFC_HOST_CARD_EMULATION = "android.hardware.nfc.hce";
    public static final String FEATURE_NFC_HOST_CARD_EMULATION_NFCF = "android.hardware.nfc.hcef";
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE = "android.hardware.nfc.ese";
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC = "android.hardware.nfc.uicc";
    public static final String FEATURE_OPENGLES_DEQP_LEVEL = "android.software.opengles.deqp.level";
    public static final String FEATURE_OPENGLES_EXTENSION_PACK = "android.hardware.opengles.aep";
    public static final String FEATURE_PC = "android.hardware.type.pc";
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";
    public static final String FEATURE_PRINTING = "android.software.print";
    public static final String FEATURE_RAM_LOW = "android.hardware.ram.low";
    public static final String FEATURE_RAM_NORMAL = "android.hardware.ram.normal";
    public static final String FEATURE_SCREEN_LANDSCAPE = "android.hardware.screen.landscape";
    public static final String FEATURE_SCREEN_PORTRAIT = "android.hardware.screen.portrait";
    public static final String FEATURE_SECURELY_REMOVES_USERS = "android.software.securely_removes_users";
    public static final String FEATURE_SECURE_LOCK_SCREEN = "android.software.secure_lock_screen";
    public static final String FEATURE_SECURITY_MODEL_COMPATIBLE = "android.hardware.security.model.compatible";
    public static final String FEATURE_SENSOR_ACCELEROMETER = "android.hardware.sensor.accelerometer";
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES = "android.hardware.sensor.accelerometer_limited_axes";
    public static final String FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED = "android.hardware.sensor.accelerometer_limited_axes_uncalibrated";
    public static final String FEATURE_SENSOR_AMBIENT_TEMPERATURE = "android.hardware.sensor.ambient_temperature";
    public static final String FEATURE_SENSOR_BAROMETER = "android.hardware.sensor.barometer";
    public static final String FEATURE_SENSOR_COMPASS = "android.hardware.sensor.compass";
    public static final String FEATURE_SENSOR_DYNAMIC_HEAD_TRACKER = "android.hardware.sensor.dynamic.head_tracker";
    public static final String FEATURE_SENSOR_GYROSCOPE = "android.hardware.sensor.gyroscope";
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES = "android.hardware.sensor.gyroscope_limited_axes";
    public static final String FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES_UNCALIBRATED = "android.hardware.sensor.gyroscope_limited_axes_uncalibrated";
    public static final String FEATURE_SENSOR_HEADING = "android.hardware.sensor.heading";
    public static final String FEATURE_SENSOR_HEART_RATE = "android.hardware.sensor.heartrate";
    public static final String FEATURE_SENSOR_HEART_RATE_ECG = "android.hardware.sensor.heartrate.ecg";
    public static final String FEATURE_SENSOR_HINGE_ANGLE = "android.hardware.sensor.hinge_angle";
    public static final String FEATURE_SENSOR_LIGHT = "android.hardware.sensor.light";
    public static final String FEATURE_SENSOR_PROXIMITY = "android.hardware.sensor.proximity";
    public static final String FEATURE_SENSOR_RELATIVE_HUMIDITY = "android.hardware.sensor.relative_humidity";
    public static final String FEATURE_SENSOR_STEP_COUNTER = "android.hardware.sensor.stepcounter";
    public static final String FEATURE_SENSOR_STEP_DETECTOR = "android.hardware.sensor.stepdetector";
    public static final String FEATURE_SE_OMAPI_ESE = "android.hardware.se.omapi.ese";
    public static final String FEATURE_SE_OMAPI_SD = "android.hardware.se.omapi.sd";
    public static final String FEATURE_SE_OMAPI_UICC = "android.hardware.se.omapi.uicc";
    public static final String FEATURE_SIP = "android.software.sip";
    public static final String FEATURE_SIP_VOIP = "android.software.sip.voip";
    public static final String FEATURE_STRONGBOX_KEYSTORE = "android.hardware.strongbox_keystore";
    public static final String FEATURE_TELECOM = "android.software.telecom";
    public static final String FEATURE_TELEPHONY = "android.hardware.telephony";
    public static final String FEATURE_TELEPHONY_CALLING = "android.hardware.telephony.calling";
    public static final String FEATURE_TELEPHONY_CDMA = "android.hardware.telephony.cdma";
    public static final String FEATURE_TELEPHONY_DATA = "android.hardware.telephony.data";
    public static final String FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc";
    public static final String FEATURE_TELEPHONY_EUICC_MEP = "android.hardware.telephony.euicc.mep";
    public static final String FEATURE_TELEPHONY_GSM = "android.hardware.telephony.gsm";
    public static final String FEATURE_TELEPHONY_IMS = "android.hardware.telephony.ims";
    public static final String FEATURE_TELEPHONY_MBMS = "android.hardware.telephony.mbms";
    public static final String FEATURE_TELEPHONY_MESSAGING = "android.hardware.telephony.messaging";
    public static final String FEATURE_TELEPHONY_RADIO_ACCESS = "android.hardware.telephony.radio.access";
    public static final String FEATURE_TELEPHONY_SUBSCRIPTION = "android.hardware.telephony.subscription";
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";
    public static final String FEATURE_THREAD_NETWORK = "android.hardware.thread_network";
    public static final String FEATURE_TOUCHSCREEN = "android.hardware.touchscreen";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH = "android.hardware.touchscreen.multitouch";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT = "android.hardware.touchscreen.multitouch.distinct";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND = "android.hardware.touchscreen.multitouch.jazzhand";
    public static final String FEATURE_USB_ACCESSORY = "android.hardware.usb.accessory";
    public static final String FEATURE_USB_HOST = "android.hardware.usb.host";
    public static final String FEATURE_UWB = "android.hardware.uwb";
    public static final String FEATURE_VERIFIED_BOOT = "android.software.verified_boot";
    public static final String FEATURE_VR_HEADTRACKING = "android.hardware.vr.headtracking";
    public static final String FEATURE_VR_MODE = "android.software.vr.mode";
    public static final String FEATURE_VR_MODE_HIGH_PERFORMANCE = "android.hardware.vr.high_performance";
    public static final String FEATURE_VULKAN_DEQP_LEVEL = "android.software.vulkan.deqp.level";
    public static final String FEATURE_VULKAN_HARDWARE_COMPUTE = "android.hardware.vulkan.compute";
    public static final String FEATURE_VULKAN_HARDWARE_LEVEL = "android.hardware.vulkan.level";
    public static final String FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version";
    public static final String FEATURE_WALLET_LOCATION_BASED_SUGGESTIONS = "android.software.wallet_location_based_suggestions";
    public static final String FEATURE_WATCH = "android.hardware.type.watch";
    public static final String FEATURE_WEBVIEW = "android.software.webview";
    public static final String FEATURE_WIFI = "android.hardware.wifi";
    public static final String FEATURE_WIFI_AWARE = "android.hardware.wifi.aware";
    public static final String FEATURE_WIFI_DIRECT = "android.hardware.wifi.direct";
    public static final String FEATURE_WIFI_PASSPOINT = "android.hardware.wifi.passpoint";
    public static final String FEATURE_WIFI_RTT = "android.hardware.wifi.rtt";
    public static final String FEATURE_WINDOW_MAGNIFICATION = "android.software.window_magnification";
    public static final String FEATURE_XR_API_OPENXR = "android.software.xr.api.openxr";
    public static final String FEATURE_XR_API_SPATIAL = "android.software.xr.api.spatial";
    public static final String FEATURE_XR_INPUT_CONTROLLER = "android.hardware.xr.input.controller";
    public static final String FEATURE_XR_INPUT_EYE_TRACKING = "android.hardware.xr.input.eye_tracking";
    public static final String FEATURE_XR_INPUT_HAND_TRACKING = "android.hardware.xr.input.hand_tracking";
    public static final int FLAG_PERMISSION_WHITELIST_INSTALLER = 2; // 0x2
    public static final int FLAG_PERMISSION_WHITELIST_SYSTEM = 1; // 0x1
    public static final int FLAG_PERMISSION_WHITELIST_UPGRADE = 4; // 0x4
    public static final int GET_ATTRIBUTIONS = -2147483648; // 0x80000000
    public static final long GET_ATTRIBUTIONS_LONG = 2147483648L; // 0x80000000L
    public static final int GET_UNINSTALLED_PACKAGES = 8192; // 0x2000
    public static final int INSTALL_SCENARIO_BULK = 2; // 0x2
    public static final int INSTALL_SCENARIO_BULK_SECONDARY = 3; // 0x3
    public static final int INSTALL_SCENARIO_DEFAULT = 0; // 0x0
    public static final int INSTALL_SCENARIO_FAST = 1; // 0x1
    public static final int MATCH_APEX = 1073741824; // 0x40000000
    public static final long MATCH_ARCHIVED_PACKAGES = 4294967296L; // 0x100000000L
    public static final long MATCH_QUARANTINED_COMPONENTS = 8589934592L; // 0x200000000L
    public static final long MAXIMUM_VERIFICATION_TIMEOUT = 3600000L; // 0x36ee80L
    public static final String PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT = "android.camera.PROPERTY_COMPAT_OVERRIDE_LANDSCAPE_TO_PORTRAIT";
    public static final String PROPERTY_MEDIA_CAPABILITIES = "android.media.PROPERTY_MEDIA_CAPABILITIES";
    public static final String PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES = "android.net.PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES";
    public static final String PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE";
    public static final String PROPERTY_USE_RESTRICTED_BACKUP_MODE = "android.app.backup.PROPERTY_USE_RESTRICTED_BACKUP_MODE";
    public static final int SIGNATURE_FIRST_NOT_SIGNED = -1; // 0xffffffff
    public static final int SIGNATURE_MATCH = 0; // 0x0
    public static final int SIGNATURE_NEITHER_SIGNED = 1; // 0x1
    public static final int SIGNATURE_NO_MATCH = -3; // 0xfffffffd
    public static final int SIGNATURE_SECOND_NOT_SIGNED = -2; // 0xfffffffe
    public static final int SIGNATURE_UNKNOWN_PACKAGE = -4; // 0xfffffffc
    public static final int VERIFICATION_ALLOW = 1; // 0x1
    public static final int VERIFICATION_REJECT = -1; // 0xffffffff
    public static final int VERSION_CODE_HIGHEST = -1; // 0xffffffff

    /** AOSP PackageManager.TRUST_ALL / TRUST_NONE (API 31): the sentinel lists
     *  passed as requestChecksums' `trustedInstallers`. ⚠ Upstream defines BOTH
     *  as `Collections.singletonList(null)` -- they are identity sentinels, not
     *  meaningful contents, and requestChecksums compares by `==`. Reproduced
     *  exactly, including the fact that the two are distinct list objects. */
    public static final java.util.List<java.security.cert.Certificate> TRUST_ALL =
            java.util.Collections.singletonList(null);
    public static final java.util.List<java.security.cert.Certificate> TRUST_NONE =
            java.util.Collections.singletonList(null);

    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
    public abstract ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException;
    // AOSP API-26: the running app is never an instant app on Mac.apk (it is
    // installed normally), so both forms return false. (Google Calculator +
    // others gate behavior on this.)
    public boolean isInstantApp() { return false; }
    public boolean isInstantApp(String packageName) { return false; }
    public abstract List<ResolveInfo> queryIntentActivities(Intent intent, int flags);

    // ---- API 33 long-flags overloads -------------------------------------
    // API 33 widened the flags argument from int to a boxed `Flags` (see the
    // nested classes at the bottom of this file), and every compileSdk>=33 build
    // is lint-nudged onto them, so an app that never changed a line of source
    // still emits `getPackageInfo(String, PackageInfoFlags)` in its bytecode.
    //
    // AOSP's abstract base declares these concrete and throws
    // UnsupportedOperationException ("not implemented in subclass"), because on
    // a device the concrete ApplicationPackageManager always overrides them and
    // routes to the same server call as the int form. Reproducing the throw here
    // would reproduce a code path no app ever observes on real Android, so we
    // reproduce the OBSERVABLE behavior instead: unwrap getValue() and delegate
    // to the int form, which every Mac.apk PackageManager already implements.
    //
    // Narrowing long -> int drops bits 32-63. The only AOSP flag up there is
    // GET_ATTRIBUTIONS (0x80000000, which is already outside the positive int
    // range and is not interpreted anywhere in Mac.apk), so no request bit we
    // act on is lost. Revisit this cast if a high flag ever becomes meaningful.
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageInfo(packageName, (int) flags.getValue());
    }
    public ApplicationInfo getApplicationInfo(String packageName, ApplicationInfoFlags flags)
            throws NameNotFoundException {
        return getApplicationInfo(packageName, (int) flags.getValue());
    }
    public List<ResolveInfo> queryIntentActivities(Intent intent, ResolveInfoFlags flags) {
        return queryIntentActivities(intent, (int) flags.getValue());
    }
    public List<ResolveInfo> queryIntentServices(Intent intent, ResolveInfoFlags flags) {
        return queryIntentServices(intent, (int) flags.getValue());
    }
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, ResolveInfoFlags flags) {
        return queryBroadcastReceivers(intent, (int) flags.getValue());
    }
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, ResolveInfoFlags flags) {
        return queryIntentContentProviders(intent, (int) flags.getValue());
    }
    public ResolveInfo resolveActivity(Intent intent, ResolveInfoFlags flags) {
        return resolveActivity(intent, (int) flags.getValue());
    }
    public ResolveInfo resolveService(Intent intent, ResolveInfoFlags flags) {
        return resolveService(intent, (int) flags.getValue());
    }
    // AOSP declares the ComponentInfoFlags forms of getActivityInfo/getReceiverInfo
    // as `throws NameNotFoundException` even though our int forms do not throw, so
    // the declaration matches upstream; a caller compiled against Android already
    // has the catch in place.
    public android.content.pm.ActivityInfo getActivityInfo(
            android.content.ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getActivityInfo(component, (int) flags.getValue());
    }
    public android.content.pm.ActivityInfo getReceiverInfo(
            android.content.ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getReceiverInfo(component, (int) flags.getValue());
    }
    public ServiceInfo getServiceInfo(android.content.ComponentName component,
            ComponentInfoFlags flags) throws NameNotFoundException {
        return getServiceInfo(component, (int) flags.getValue());
    }
    public ProviderInfo getProviderInfo(android.content.ComponentName component,
            ComponentInfoFlags flags) throws NameNotFoundException {
        return getProviderInfo(component, (int) flags.getValue());
    }
    public ProviderInfo resolveContentProvider(String authority, ComponentInfoFlags flags) {
        return resolveContentProvider(authority, (int) flags.getValue());
    }

    /** AOSP PackageManager.getLaunchIntentForPackage: resolve the package's
     *  ACTION_MAIN launcher (CATEGORY_INFO, then CATEGORY_LAUNCHER) activity into
     *  a launch Intent, or return null when the package has no launchable
     *  activity. F-Droid's AppDetails header calls this to decide whether to show
     *  an "Open" button. Mac.apk runs one APK at a time, so a *browsed* package
     *  is not installed and resolves to nothing -> null, which is the AOSP-correct
     *  "not installed" signal (F-Droid then offers Install instead of Open).
     *  Concrete (mirrors frameworks/base ApplicationPackageManager). */
    public Intent getLaunchIntentForPackage(String packageName) {
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory("android.intent.category.INFO");
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);
        if (ris == null || ris.isEmpty()) {
            intentToResolve = new Intent(Intent.ACTION_MAIN);
            intentToResolve.addCategory("android.intent.category.LAUNCHER");
            intentToResolve.setPackage(packageName);
            ris = queryIntentActivities(intentToResolve, 0);
        }
        if (ris == null || ris.isEmpty()) return null;
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory("android.intent.category.LAUNCHER");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(packageName);
        // AOSP pins the RESOLVED component on the returned intent
        // (`intent.setClassName(ris.get(0).activityInfo.packageName,
        // ris.get(0).activityInfo.name)`). Without it the caller holds a
        // package-scoped implicit intent that has to be re-resolved at
        // startActivity time, and anything that inspects the launch target --
        // "is this my own activity", a shortcut builder, a task-affinity check
        // -- reads getComponent() == null. Measured as
        // PM.query.self.launchIntentIsSelfActivity.
        android.content.pm.ActivityInfo target = ris.get(0).activityInfo;
        if (target != null && target.name != null) {
            intent.setClassName(
                target.packageName != null ? target.packageName : packageName,
                target.name);
        }
        return intent;
    }

    /** AOSP PackageManager.canonicalToCurrentPackageNames / currentToCanonical:
     *  translate between a package's canonical (original) name and its current
     *  (possibly renamed) name. No package on Mac.apk is renamed, so the mapping
     *  is the identity -- return the input array (matching AOSP's behavior for
     *  packages with no <original-package> rename). F-Droid's installed-app /
     *  AppDetails path passes app package names through this. */
    public String[] canonicalToCurrentPackageNames(String[] names) { return names; }
    public String[] currentToCanonicalPackageNames(String[] names) { return names; }

    /** AOSP PackageManager.getPermissionInfo / getPermissionGroupInfo: look up a
     *  permission (group)'s metadata. Mac.apk has no platform permission database,
     *  so we throw NameNotFoundException -- the AOSP-correct "unknown permission"
     *  signal. F-Droid's AppSecurityPermissions.extractPerms wraps these calls in
     *  a NameNotFoundException catch and simply skips the unknown entry, so the
     *  AppDetails permissions section is empty rather than aborting the whole
     *  detail RecyclerView layout (which the prior NoSuchMethodError did). A real
     *  permission-metadata DB is a separate, larger piece of work. */
    public PermissionInfo getPermissionInfo(String permName, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(permName);
    }
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(groupName);
    }

    public abstract int checkPermission(String permName, String pkgName);
    public abstract boolean hasSystemFeature(String name);
    public abstract String getInstallerPackageName(String packageName);

    // v1.0.97: AOSP getSystemAvailableFeatures + hasSystemFeature(String, int)
    // overload. F-Droid (and Material-typography callers) iterate the list
    // looking for OpenGL ES version + Bluetooth + NFC + biometrics. Return
    // an empty array; the caller falls back to "feature not supported."
    public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        return new android.content.pm.FeatureInfo[0];
    }
    public boolean hasSystemFeature(String name, int version) {
        return hasSystemFeature(name);
    }

    /** v1.0.100: AOSP getInstalledPackages(int flags) returns the list of
     *  every PackageInfo the user has installed. F-Droid's UpdatesAdapter
     *  walks this on a Rx scheduler thread to compute "x of y updates
     *  available"; without a real list it throws NoSuchMethodError and the
     *  background scheduler dies. Mac.apk only knows about the running
     *  app, so we return a single-element list with the calling app's
     *  PackageInfo (best-effort; the caller treats it as "nothing to update").
     *  AOSP signature: `List<PackageInfo> getInstalledPackages(int flags)`. */
    public List<android.content.pm.PackageInfo> getInstalledPackages(int flags) {
        java.util.ArrayList<android.content.pm.PackageInfo> out = new java.util.ArrayList<>();
        try {
            android.app.Application app = android.app.Application.macapk_get();
            if (app != null) {
                android.content.pm.PackageInfo self =
                    getPackageInfo(app.getPackageName(), flags);
                if (self != null) out.add(self);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** AOSP API 24+ overload for getInstalledPackages with a UserHandle. */
    public List<android.content.pm.PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        return getInstalledPackages(flags);
    }

    /** AOSP getInstalledApplications(int flags): the ApplicationInfo-returning
     *  sibling of getInstalledPackages. Marked @NonNull upstream, so it never
     *  returns null -- an empty list is the correct degenerate answer. Same
     *  single-app-device honesty as getInstalledPackages above: Mac.apk hosts
     *  exactly one APK, so the installed set is that one app. */
    public List<android.content.pm.ApplicationInfo> getInstalledApplications(int flags) {
        java.util.ArrayList<android.content.pm.ApplicationInfo> out = new java.util.ArrayList<>();
        try {
            android.app.Application app = android.app.Application.macapk_get();
            if (app != null) {
                android.content.pm.ApplicationInfo self =
                    getApplicationInfo(app.getPackageName(), flags);
                if (self != null) out.add(self);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** AOSP API 24+ overload for getInstalledApplications with a user id. */
    public List<android.content.pm.ApplicationInfo> getInstalledApplicationsAsUser(
            int flags, int userId) {
        return getInstalledApplications(flags);
    }

    // API 33 long-flags forms; see the "API 33 long-flags overloads" note above.
    public List<android.content.pm.PackageInfo> getInstalledPackages(PackageInfoFlags flags) {
        return getInstalledPackages((int) flags.getValue());
    }
    public List<android.content.pm.PackageInfo> getInstalledPackagesAsUser(
            PackageInfoFlags flags, int userId) {
        return getInstalledPackages((int) flags.getValue());
    }
    public List<android.content.pm.ApplicationInfo> getInstalledApplications(
            ApplicationInfoFlags flags) {
        return getInstalledApplications((int) flags.getValue());
    }
    public List<android.content.pm.ApplicationInfo> getInstalledApplicationsAsUser(
            ApplicationInfoFlags flags, int userId) {
        return getInstalledApplications((int) flags.getValue());
    }

    /** AOSP PackageManager.getNameForUid: the package name owning a uid (or null).
     *  Mac.apk is a single app in one uid, so return the running app's package
     *  name. GMS measurement (zzfj.zzie) calls this during init; missing it ->
     *  NoSuchMethodError on the FA worker thread. */
    public String getNameForUid(int uid) {
        android.app.Application app = android.app.Application.macapk_get();
        return (app != null) ? app.getPackageName() : null;
    }

    /** AOSP PackageManager.getPackagesForUid(int): every package name assigned
     *  the given uid -- normally one element, more only under android:sharedUserId
     *  -- or null when no package is known for that uid. Mac.apk hosts exactly one
     *  app at exactly one uid (Process.FIRST_APPLICATION_UID, mirrored by
     *  ApplicationInfo.uid), so the honest answer is a single-element array for
     *  that uid and null for anything else. Returning the running package for an
     *  arbitrary uid would be a lie a caller-identity check could act on. */
    public String[] getPackagesForUid(int uid) {
        android.app.Application app = android.app.Application.macapk_get();
        if (app == null) return null;
        int selfUid = android.os.Process.myUid();
        try {
            android.content.pm.ApplicationInfo ai = app.getApplicationInfo();
            if (ai != null) selfUid = ai.uid;
        } catch (Throwable ignored) {}
        if (uid != selfUid) return null;
        String pkg = app.getPackageName();
        return (pkg != null) ? new String[] { pkg } : null;
    }

    /** Mirrors AOSP PackageManager.getActivityInfo(ComponentName, int).
     *
     *  ⚠ This is the FALLBACK for a component the manifest never declared.
     *  com.macapk.loader.MacApkPackageManager overrides it (v1.0.834) and
     *  returns a fully-populated ActivityInfo -- metaData, parentActivityName,
     *  targetActivity, theme, labelRes, launchMode, screenOrientation,
     *  configChanges, softInputMode, exported, permission, taskAffinity --
     *  built from the parsed manifest, delegating here only when the class is
     *  not a declared <activity>. Anything relying on "getActivityInfo carries
     *  no metadata" is reading a stale contract.
     *
     *  The permissive shape kept here (the activity exists, applicationInfo
     *  matches the calling app, theme is zero = use the default, name is the
     *  component's class) is deliberate: AOSP throws NameNotFoundException for
     *  an unknown component, but on Mac.apk ActivityHost can be asked to start
     *  a class the manifest never listed, and several framework paths look up
     *  such components. */
    // ⚠ v1.0.938: DECLARES the AOSP checked exception. AOSP's signature is
    // `@NonNull ActivityInfo getActivityInfo(ComponentName, int) throws
    // NameNotFoundException` (PackageManager.java:4105 at android11-release) --
    // there is no non-throwing failure mode, and MacApkPackageManager could not
    // override it to throw while this declaration lacked the clause (JLS
    // 8.4.8.3: an override may not add a checked exception). The two
    // workarounds were both worse: an UNCHECKED throw sails straight past an
    // app's `catch (NameNotFoundException)`, and a sneaky-throw is not caught by
    // Intent.resolveActivityInfo, which AOSP guarantees never throws.
    // Adding a checked exception does NOT change the JVM descriptor -- it lives
    // in the Exceptions attribute -- so guest bytecode still links unchanged.
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        android.content.pm.ActivityInfo info = new android.content.pm.ActivityInfo();
        if (component != null) {
            info.name = component.getClassName();
            info.packageName = component.getPackageName();
        }
        // Fill ApplicationInfo from the calling app's known info.
        try {
            android.app.Application app = android.app.Application.macapk_get();
            if (app != null) {
                info.applicationInfo = app.getApplicationInfo();
            }
        } catch (Throwable ignored) {}
        return info;
    }
    /** AOSP getReceiverInfo returns an ActivityInfo drawn from the package's
     *  RECEIVERS list -- a component namespace separate from activities.
     *  Delegating to getActivityInfo here is only the no-manifest fallback;
     *  MacApkPackageManager overrides it against the parsed <receiver> set. */
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        return getActivityInfo(component, flags);
    }

    /** AOSP API-21+ PackageManager.getPackageInstaller(): the
     *  PackageInstaller system service for installing/uninstalling APKs.
     *  REAL since v1.0.956 (sessions stage bytes and commit() hands them to
     *  the macOS install helper -- see PackageInstaller's own header). One
     *  shared instance, mirroring ApplicationPackageManager's cached
     *  mInstaller; session state is process-wide either way
     *  (PackageInstallerBackend's registry is static), so a second instance
     *  would behave identically -- the cache just keeps identity stable for
     *  apps that compare references. */
    private static android.content.pm.PackageInstaller sPackageInstaller;
    public android.content.pm.PackageInstaller getPackageInstaller() {
        synchronized (PackageManager.class) {
            if (sPackageInstaller == null) {
                sPackageInstaller = new android.content.pm.PackageInstaller();
            }
            return sPackageInstaller;
        }
    }

    /** AOSP PackageManager.getPackageArchiveInfo(String archiveFilePath, int flags):
     *  parse the manifest of an APK sitting ON DISK that is NOT installed, and
     *  return its PackageInfo, or null when the archive cannot be parsed. This is
     *  concrete (not abstract) in AOSP: the base class does the parse itself
     *  rather than asking PackageManagerService, because there is no installed
     *  package to ask about. It is the primitive an installer UI uses to preview
     *  a downloaded APK before handing it to PackageInstaller.
     *
     *  Deliberately NOT restricted to the running package: inspecting a foreign,
     *  non-installed archive is this method's entire purpose, so the PM_SELF_ONLY
     *  stance that getPackageInfo takes does not apply here. Mac.apk already owns
     *  the exact parse primitive (com.macapk.loader.Manifest.parse reads the
     *  BINARY AndroidManifest.xml straight out of any APK's zip), so this is a
     *  real parse of the real file, not a stub. */
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, int flags) {
        if (archiveFilePath == null || archiveFilePath.isEmpty()) return null;
        java.io.File apk = new java.io.File(archiveFilePath);
        if (!apk.isFile()) return null;
        com.macapk.loader.Manifest m;
        try {
            m = com.macapk.loader.Manifest.parse(apk, archiveFilePath);
        } catch (Throwable t) {
            // AOSP: "If the package could not be parsed, returns null."
            return null;
        }
        if (m == null) return null;
        PackageInfo info = new PackageInfo();
        info.packageName     = m.packageName;
        info.versionName     = m.versionNameRaw;
        info.versionCode     = m.versionCode;
        info.longVersionCode = m.versionCode;
        // PackageParser defaults an undeclared `<manifest android:installLocation>`
        // to INSTALL_LOCATION_UNSPECIFIED, which is what a parsed archive reports
        // (the field's own initializer is INTERNAL_ONLY, a different default).
        info.installLocation = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName      = m.packageName;
        ai.className        = m.applicationClass;
        ai.processName      = m.packageName;
        ai.minSdkVersion    = m.minSdk;
        ai.targetSdkVersion = m.targetSdk;
        // AOSP sets sourceDir/publicSourceDir to the archive path itself, since
        // that is where the code and resources of this not-yet-installed package
        // live.
        ai.sourceDir        = archiveFilePath;
        ai.publicSourceDir  = archiveFilePath;
        if ((flags & GET_META_DATA) != 0) {
            ai.metaData = archiveMetaData(m);
        }
        info.applicationInfo = ai;
        // Installed-registry lookups also use this archive path. Like AOSP's
        // PackageInfoUtils.generateWithComponents, return requested features
        // under GET_CONFIGURATIONS so callers can discover feature-based plugins.
        if ((flags & GET_CONFIGURATIONS) != 0 && !m.reqFeatures.isEmpty()) {
            FeatureInfo[] features = new FeatureInfo[m.reqFeatures.size()];
            for (int i = 0; i < features.length; i++) {
                com.macapk.loader.Manifest.FeatureEntry e = m.reqFeatures.get(i);
                FeatureInfo fi = new FeatureInfo();
                fi.name = e.name;
                fi.version = e.version;
                fi.reqGlEsVersion = e.reqGlEsVersion;
                if (e.required) fi.flags |= FeatureInfo.FLAG_REQUIRED;
                features[i] = fi;
            }
            info.reqFeatures = features;
        }
        // GET_PERMISSIONS: AOSP fills BOTH halves for a parsed archive
        // (PackageParser.generatePackageInfo, android11-release
        // core/java/android/content/pm/PackageParser.java:803-825):
        //   - pi.permissions -- the <permission> entries the package DEFINES;
        //   - pi.requestedPermissions + requestedPermissionsFlags -- the
        //     <uses-permission> set, each entry flagged
        //     REQUESTED_PERMISSION_REQUIRED ("deprecated but for
        //     compatibility"), with REQUESTED_PERMISSION_GRANTED added only
        //     from a grantedPermissions set the ARCHIVE path never has
        //     (getPackageArchiveInfo generates against a fresh user state,
        //     PackageManager.java:6063-6085 same release) -- so an archive
        //     reports REQUIRED only, never GRANTED.
        // This is F-Droid's ApkVerifier pre-install gate: before any install
        // intent fires it compares getPackageArchiveInfo(path,
        // GET_PERMISSIONS).requestedPermissions against the repo index's
        // expected set and ABORTS on mismatch (ApkPermissionUnequalException).
        // A null array here therefore blocked every F-Droid install one step
        // before the install path could even be exercised.
        // Manifest.usesPermissions has already applied AOSP's min/maxSdkVersion
        // window at parse time and deduplicated, matching what a real API-29
        // parse of the same archive reports.
        if ((flags & GET_PERMISSIONS) != 0) {
            if (!m.permissions.isEmpty()) {
                PermissionInfo[] defined = new PermissionInfo[m.permissions.size()];
                for (int i = 0; i < defined.length; i++) {
                    com.macapk.loader.Manifest.PermissionEntry e = m.permissions.get(i);
                    PermissionInfo pi = new PermissionInfo();
                    pi.name            = e.name;
                    pi.packageName     = m.packageName;
                    pi.group           = e.group;
                    pi.protectionLevel = e.protectionLevel;
                    pi.flags           = e.flags;
                    defined[i] = pi;
                }
                info.permissions = defined;
            }
            if (!m.usesPermissions.isEmpty()) {
                java.util.List<String> req =
                    new java.util.ArrayList<>(m.usesPermissions.size());
                for (com.macapk.loader.Manifest.UsesPermissionEntry e : m.usesPermissions) {
                    if (e.name != null && !e.name.isEmpty()) req.add(e.name);
                }
                if (!req.isEmpty()) {
                    info.requestedPermissions = req.toArray(new String[0]);
                    int[] reqFlags = new int[req.size()];
                    // PackageInfo.REQUESTED_PERMISSION_REQUIRED == 1<<0
                    // (android11-release PackageInfo.java:227). The constant is
                    // declared now (it was not when this line was written, which
                    // is why it used the literal), so this names it.
                    java.util.Arrays.fill(reqFlags, PackageInfo.REQUESTED_PERMISSION_REQUIRED);
                    info.requestedPermissionsFlags = reqFlags;
                }
            }
        }
        // GET_SIGNATURES / GET_SIGNING_CERTIFICATES: AOSP collects the archive's
        // certificates when either is requested (PackageManager
        // .getPackageArchiveInfo -> ParsingPackageUtils ... collectCertificates,
        // then PackageInfoUtils fills pi.signatures for GET_SIGNATURES and
        // pi.signingInfo for GET_SIGNING_CERTIFICATES). v1.0.1639: these were
        // never filled, and registryPackageInfo builds every OTHER installed
        // package's PackageInfo through this method -- so Aurora Store's
        // CertUtil saw signingInfo == null for every installed app, marked each
        // available update hasValidCert=false and hid all of them (its Updates
        // screen only lists cert-matching updates unless "extended updates" is
        // on), and F-Droid's UpdateChecker would NPE on packageInfo.signatures.
        if ((flags & (GET_SIGNATURES | GET_SIGNING_CERTIFICATES)) != 0) {
            macapkFillSignatures(info, archiveFilePath, flags);
        }
        return info;
    }

    /** Mac.apk seam: {Signature[] signers, Integer schemeVersion} for the APK
     *  (or split bundle) at {@code path}, or null when it cannot be read.
     *  MacApkPackageManager overrides it with its signing-block / v1 reader;
     *  the base answer is "unknown". */
    protected Object[] macapkArchiveSignatures(String path) { return null; }

    /** Fill the signature fields {@code flags} asks for from the APK at
     *  {@code path}. Fresh arrays per call, as AOSP hands out. */
    protected final void macapkFillSignatures(PackageInfo info, String path, int flags) {
        Object[] s = null;
        try { s = path != null ? macapkArchiveSignatures(path) : null; }
        catch (Throwable ignored) { }
        Signature[] sigs = s != null ? (Signature[]) s[0] : null;
        if (sigs == null || sigs.length == 0) return;
        int scheme = s[1] instanceof Integer ? (Integer) s[1] : 0;
        if ((flags & GET_SIGNATURES) != 0) info.signatures = sigs.clone();
        if ((flags & GET_SIGNING_CERTIFICATES) != 0) {
            info.signingInfo = scheme > 0
                ? new SigningInfo(scheme, java.util.Arrays.asList(sigs.clone()), null, null)
                : new SigningInfo(sigs.clone());
        }
    }

    /** API 33 long-flags form; see the "API 33 long-flags overloads" note above. */
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, PackageInfoFlags flags) {
        return getPackageArchiveInfo(archiveFilePath, (int) flags.getValue());
    }

    /** AOSP PackageManager.getArchivedPackage(String) (API 35 / Android 15,
     *  frameworks/base core/java/android/content/pm/PackageManager.java:11369-11377,
     *  android15-release):
     *
     *      /** Return archived package info for the package or null if the
     *          package is not installed. *\/
     *      @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
     *      public @Nullable ArchivedPackageInfo getArchivedPackage(@NonNull String packageName)
     *
     *  APP ARCHIVING is Android 15's "uninstall the APK, keep the launcher icon
     *  and user data so one tap restores it" feature. Mac.apk does not implement
     *  it: there is no macOS analogue, nothing in this runtime ever archives a
     *  package, and `installed_apps.json` has no archived state. So NO package
     *  on this device is archived, and null is the HONEST answer for every
     *  argument -- not a stub value standing in for a capability we are
     *  pretending to have. It is also the answer AOSP's own
     *  ApplicationPackageManager gives whenever the system server has no archive
     *  record for the name.
     *
     *  WHY THIS EXISTS (2026-08-23, v1.0.1037). Aurora Store 4.8.4's app-detail
     *  view-model does, after its own `SDK_INT >= 35` guard:
     *
     *      if (getArchivedPackage(pkg) != null) return State.ARCHIVED;
     *
     *  With the method absent the guard passed (we present API 36 for Aurora,
     *  whose targetSdk is 37) and the call threw NoSuchMethodError on a
     *  Dispatchers.Default worker. That killed the whole detail-screen coroutine
     *  scope, so EVERY app page in the store rendered as the bare window
     *  background -- `frame-score ... coverage=0.8% note=near_empty` -- while the
     *  network fetch behind it had returned HTTP 200. The owner reported it as
     *  "app pages aren't loading".
     *
     *  The lesson is the general one for MACAPK_SDK_FROM_TARGET (default-ON
     *  since v1.0.1012): presenting a high API level is a PROMISE that the
     *  level's API surface exists, and a modern app takes the new branch the
     *  moment we make that promise. The fix is to implement the method, never to
     *  lower the level. */
    public ArchivedPackageInfo getArchivedPackage(String packageName) {
        return null;
    }

    /** <meta-data> of an archive, typed the way AOSP PackageParser.parseMetaData
     *  types it: TYPE_INT_BOOLEAN -> putBoolean, ints -> putInt, floats ->
     *  putFloat, everything else -> putString.
     *
     *  RESOURCE REFERENCES ARE DROPPED, on purpose. On a device the reference is
     *  resolved against the ARCHIVE's own resource table before parseMetaData
     *  sees it. Mac.apk's Resources/ResourceIndex is bound to the one RUNNING
     *  app, so resolving an archive's `@0x7f...` id through it would silently
     *  return a value from a different APK -- a wrong answer is worse than an
     *  absent key, and AOSP itself omits the key for values it cannot type
     *  (dimension/fraction). Callers see Bundle.containsKey()==false, which is
     *  the same signal they get for a meta-data entry the archive never had. */
    private static android.os.Bundle archiveMetaData(com.macapk.loader.Manifest m) {
        java.util.Map<String, String> vals = m.applicationMetaData;
        if (vals == null || vals.isEmpty()) return null;
        java.util.Map<String, String> types = m.applicationMetaDataTypes;
        android.os.Bundle md = new android.os.Bundle();
        for (java.util.Map.Entry<String, String> e : vals.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (key == null) continue;
            String type = (types != null) ? types.get(key) : null;
            if (type != null && val != null && !val.isEmpty()) {
                if ("boolean".equals(type)) {
                    md.putBoolean(key, Boolean.parseBoolean(val));
                    continue;
                }
                if ("int".equals(type)) {
                    try {
                        md.putInt(key, val.startsWith("0x")
                            ? (int) Long.parseLong(val.substring(2), 16)
                            : Integer.parseInt(val));
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
                if ("float".equals(type)) {
                    try { md.putFloat(key, Float.parseFloat(val)); continue; }
                    catch (NumberFormatException ignored) {}
                }
                if ("reference".equals(type)) continue;   // see the note above
            }
            md.putString(key, val);
        }
        return md;
    }

    /** Mirrors AOSP PackageManager.getServiceInfo(ComponentName, int).
     *  Used by Firebase ComponentDiscovery and any other SDK that
     *  registers modules via manifest <service> + <meta-data>. Real
     *  Android calls into PackageManagerService; on Mac.apk we look
     *  up the Manifest.ServiceEntry the loader parsed at boot. If
     *  the component isn't a known service, throw NameNotFoundException
     *  -- callers like Firebase catch that and fall back to "no
     *  modules registered". */
    public abstract ServiceInfo getServiceInfo(android.content.ComponentName component, int flags)
        throws NameNotFoundException;

    /** Mirrors AOSP PackageManager.getProviderInfo(ComponentName, int).
     *  Used by androidx.startup.InitializationProvider.onCreate to
     *  enumerate Initializer classes declared as <meta-data> children
     *  of the <provider> element. Also called by any ContentProvider
     *  that wants to read its own metadata. Throws
     *  NameNotFoundException when the component's class is not a
     *  declared provider; with GET_META_DATA set, populates
     *  ProviderInfo.metaData with the <meta-data> name/value pairs. */
    public abstract ProviderInfo getProviderInfo(android.content.ComponentName component, int flags)
        throws NameNotFoundException;

    /** Mirrors AOSP PackageManager.resolveContentProvider(String authority,
     *  int flags): return the ProviderInfo of the provider that serves the
     *  given authority, or null if none. androidx.core.content.FileProvider
     *  and other library providers call this during attachInfo/onCreate.
     *  Default returns null ("no such provider"); the Mac.apk concrete
     *  PackageManager overrides it to resolve against the parsed manifest. A
     *  concrete (non-abstract) default avoids NoSuchMethodError for any caller
     *  while subclasses that lack a manifest still compile. */
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        return null;
    }

    // Mirror of AOSP PackageManager.queryIntentServices. Apps use it to
    // discover services for billing / GMS / FCM. Since we only host the
    // one APK in-process, no service is ever resolvable -- return an
    // empty list, matching AOSP's contract for "no matches" (it does
    // NOT throw on empty). Note the concrete subclass is welcome to
    // override; the default avoids a NoSuchMethodError when an SDK
    // probes for optional companion services during init.
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        return Collections.emptyList();
    }
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        return Collections.emptyList();
    }
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        return Collections.emptyList();
    }
    public ResolveInfo resolveService(Intent intent, int flags) { return null; }
    public ResolveInfo resolveActivity(Intent intent, int flags) { return null; }

    // Component-enable/disable state. AOSP semantics: enable / disable
    // / default / disabled-user / disabled-until-used. androidx.work's
    // SystemForegroundDispatcher and Mobile Ads' GMS_TASK_DISPATCH
    // toggle components at boot time to gate their own services. HCR 1.68 /
    // GD Lite depend on these methods existing (NSME otherwise).
    //
    // ⚠ The no-op bodies below are the BASE-CLASS answer, and they are correct
    // only for a PackageManager with no manifest behind it -- there is no
    // package whose components could be toggled, so "nothing was stored" and
    // "everything reads DEFAULT" are consistent with each other.
    // MacApkPackageManager OVERRIDES all four with a real store, because it
    // DOES have a manifest. The justification that used to live here -- "the
    // components don't actually exist as services in our process model" -- had
    // been false since ServiceRegistry, the v1.0.997 manifest-broadcast
    // dispatch and Boot.installAutoInitProviders landed: those three DO
    // instantiate manifest components, so an unrecorded setComponentEnabledSetting
    // was a returns-success-does-nothing method. Removed at v1.1.0 rather than
    // left as a comment that tells the next reader not to look.
    public static final int COMPONENT_ENABLED_STATE_DEFAULT          = 0;
    public static final int COMPONENT_ENABLED_STATE_ENABLED          = 1;
    public static final int COMPONENT_ENABLED_STATE_DISABLED         = 2;
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER    = 3;
    public static final int COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED = 4;

    public static final int DONT_KILL_APP = 0x00000001;
    public static final int SYNCHRONOUS   = 0x00000002;

    public void setComponentEnabledSetting(android.content.ComponentName componentName,
                                            int newState, int flags) {}
    public int  getComponentEnabledSetting(android.content.ComponentName componentName) {
        return COMPONENT_ENABLED_STATE_DEFAULT;
    }
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {}
    /** AOSP returns the STORED setting, and a normally-installed package that
     *  nobody has called setApplicationEnabledSetting on has no stored setting
     *  -- so the answer is COMPONENT_ENABLED_STATE_DEFAULT (0), not
     *  COMPONENT_ENABLED_STATE_ENABLED (1). ENABLED means "someone explicitly
     *  enabled this", which is a different fact and is what an app checks when
     *  it wants to know whether a component was toggled (launcher-alias
     *  switching is the classic). getComponentEnabledSetting above already
     *  answered DEFAULT; this one disagreed with it for no reason.
     *  ApplicationInfo.enabled stays true -- that is the effective state, and
     *  the two are separate questions on AOSP as well. */
    public int  getApplicationEnabledSetting(String packageName) {
        return COMPONENT_ENABLED_STATE_DEFAULT;
    }

    /** AOSP PackageManager.getResourcesForApplication(String appPackageName):
     *  the Resources of the named INSTALLED application, or NameNotFoundException
     *  when its resources cannot be loaded ("most likely because it was
     *  uninstalled"). Both AOSP overloads are @NonNull, so there is no "return
     *  null" answer -- the exception IS the not-installed signal, and it is a
     *  checked exception every caller already handles.
     *
     *  Mac.apk hosts exactly one APK per process, so the only package whose
     *  resources exist is the running one; for it we hand back the SAME Resources
     *  instance the loader built (not a fresh one), which is what a device
     *  returns for the self case and what any subsequent identity comparison
     *  expects. Every other package is, truthfully, not installed here -> throw.
     *  The self case is the common one: some SDKs call
     *  pm.getResourcesForApplication(getPackageName()) instead of
     *  Context.getResources() to get a Resources independent of Context state. */
    public android.content.res.Resources getResourcesForApplication(String appPackageName)
            throws NameNotFoundException {
        android.app.Application app = android.app.Application.macapk_get();
        if (app == null || appPackageName == null) throw new NameNotFoundException(appPackageName);
        if (!appPackageName.equals(app.getPackageName())) {
            throw new NameNotFoundException(appPackageName);
        }
        android.content.res.Resources res = app.getResources();
        if (res == null) throw new NameNotFoundException(appPackageName);
        return res;
    }

    public android.content.res.Resources getResourcesForApplication(ApplicationInfo app)
            throws NameNotFoundException {
        if (app == null) throw new NameNotFoundException();
        return getResourcesForApplication(app.packageName);
    }

    /** AOSP API-31 overload. Upstream's base class implements it by ignoring the
     *  override configuration and delegating, which is exactly what we do. */
    public android.content.res.Resources getResourcesForApplication(ApplicationInfo app,
            android.content.res.Configuration configuration) throws NameNotFoundException {
        return getResourcesForApplication(app);
    }

    /** AOSP certificate-input encodings for hasSigningCertificate. */
    public static final int CERT_INPUT_RAW_X509 = 0;
    public static final int CERT_INPUT_SHA256   = 1;

    /** AOSP PackageManager.hasSigningCertificate(String, byte[], int) (API 28):
     *  "Searches the set of signing certificates by which the given package has
     *  proven to have been signed. This should be used instead of getPackageInfo
     *  with GET_SIGNATURES since it takes into account the possibility of signing
     *  certificate rotation." Returns true only on an exact match.
     *
     *  Real work, not a stub: we ask ourselves for the package's PackageInfo (the
     *  Mac.apk PackageManager reads the APK's actual signing certificate off disk)
     *  and run AOSP's own comparison. Mirrors SigningDetails.hasCertificateInternal
     *  / hasSha256CertificateInternal: the current-signer match requires EXACTLY
     *  one signature (rotation is unsupported for multiply-signed packages), and
     *  the rotation-history branch is skipped because an APK we load has no
     *  lineage (SigningInfo.hasPastSigningCertificates() is false).
     *
     *  A package that is not installed here throws NameNotFoundException out of
     *  getPackageInfo; "not installed" means "was not signed by that cert", so
     *  false. AOSP's base class throws UnsupportedOperationException instead, but
     *  that path only exists for a subclass that forgot to override; a device's
     *  ApplicationPackageManager always answers, so answering is the faithful
     *  observable behavior. */
    public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) {
        if (packageName == null || certificate == null) return false;
        Signature[] sigs;
        try {
            PackageInfo info = getPackageInfo(packageName, GET_SIGNING_CERTIFICATES);
            sigs = (info != null) ? info.signatures : null;
        } catch (NameNotFoundException e) {
            return false;
        }
        if (sigs == null || sigs.length != 1) return false;
        byte[] mine = sigs[0].toByteArray();
        if (mine == null) return false;
        if (type == CERT_INPUT_RAW_X509) {
            return java.util.Arrays.equals(certificate, mine);
        }
        if (type == CERT_INPUT_SHA256) {
            try {
                byte[] digest = java.security.MessageDigest
                    .getInstance("SHA-256").digest(mine);
                return java.util.Arrays.equals(certificate, digest);
            } catch (java.security.NoSuchAlgorithmException e) {
                return false;
            }
        }
        return false;
    }

    /** AOSP uid-keyed overload: "analogous to using getPackagesForUid followed by
     *  getPackageInfo with GET_SIGNING_CERTIFICATES". For a shared uid AOSP picks
     *  the newest-signed package; Mac.apk's uid never holds more than one package,
     *  so the first (only) entry is that package. Unknown uid -> no packages ->
     *  false. */
    public boolean hasSigningCertificate(int uid, byte[] certificate, int type) {
        String[] pkgs = getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return false;
        return hasSigningCertificate(pkgs[0], certificate, type);
    }

    /** AOSP ApplicationPackageManager.getApplicationLabel(ApplicationInfo) is
     *  ONE line -- `return info.loadLabel(this);` (android-11.0.0_r48
     *  core/java/android/app/ApplicationPackageManager.java:2047-2049) -- so the
     *  two spellings of "what is this app called" are the same call and cannot
     *  disagree on a device.
     *
     *  This used to return info.packageName, which is the LAST-RESORT fallback
     *  inside loadLabel, reached only after nonLocalizedLabel, labelRes and name
     *  have all failed. The cost was not cosmetic: loadLabel here already
     *  resolves `android:label` correctly, so every caller that asked the
     *  PackageManager instead of the info object got "com.example.app" where
     *  the same process could get "Open Sudoku" one line later. Anything that
     *  puts an app name on screen (a title bar, a share sheet, a settings row,
     *  an analytics dimension) picked whichever of the two it happened to call.
     *  Measured as PM.label.{getApplicationLabel,getApplicationLabelEqualsLoadLabel,
     *  isPackageName} in the fwprobe answer-differential. */
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        if (info == null) return "";
        CharSequence label = info.loadLabel(this);
        return label != null ? label : "";
    }

    /** AOSP ApplicationPackageManager.getApplicationIcon / getActivityIcon /
     *  getDefaultActivityIcon are all @NonNull: an icon request falls back to
     *  the framework's default app icon rather than returning null, and callers
     *  dereference the result directly (ImageView.setImageDrawable, a
     *  RemoteViews build, a notification's large icon).
     *
     *  Mac.apk has no framework resource table, so there is no
     *  com.android.internal.R.drawable.sym_def_app_icon to load; the honest
     *  substitute is a fully TRANSPARENT ColorDrawable. That keeps the AOSP
     *  non-null contract (the reason these exist) without inventing artwork:
     *  an app that draws it paints nothing, which is exactly what it painted
     *  before, minus the NullPointerException. Same fallback
     *  PackageItemInfo.loadIcon already uses, so the two paths agree.
     *
     *  When the app DOES declare an icon we resolve it, which is the case that
     *  actually matters -- MacApkPackageManager fills ApplicationInfo.icon from
     *  `<application android:icon>`. */
    public android.graphics.drawable.Drawable getApplicationIcon(String packageName) {
        try { return getApplicationIcon(getApplicationInfo(packageName, 0)); }
        catch (Throwable t) { return getDefaultActivityIcon(); }
    }
    public android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo info) {
        if (info != null) {
            // Another installed Mac.apk app: its resources live in its own
            // bundle, not in this process (see macapkRegistryAppIcon).
            android.graphics.drawable.Drawable other = macapkRegistryAppIcon(info.packageName);
            if (other != null) return other;
        }
        if (info != null && info.icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(info.packageName, info.icon, info);
            if (d != null) return d;
        }
        return getDefaultActivityIcon();
    }
    /** Mac.apk hook: the launcher icon of ANOTHER installed Mac.apk app, or null
     *  (the base class knows no other apps). On a device getApplicationIcon reads
     *  the target's own APK resources (ApplicationPackageManager.getDrawable over
     *  getResourcesForApplication); here each app is a separate process and only
     *  its own resources are loaded, so MacApkPackageManager answers with the
     *  icon its bundle carries, rendered from that same APK icon at install. */
    protected android.graphics.drawable.Drawable macapkRegistryAppIcon(String packageName) {
        return null;
    }

    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return new android.graphics.drawable.ColorDrawable(0);
    }

    /** AOSP ApplicationPackageManager.getText(String, int, ApplicationInfo):
     *  the string resource `resid` read out of the named package's Resources,
     *  or null when it cannot be loaded. This is the call
     *  PackageItemInfo.loadUnsafeLabel makes for a labelRes, so its absence
     *  (NoSuchMethodError until v1.0.923) broke any app that resolved a label
     *  the explicit way instead of via loadLabel. */
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
        if (resid == 0) return null;
        try {
            android.content.res.Resources r = (appInfo != null)
                ? getResourcesForApplication(appInfo)
                : getResourcesForApplication(packageName);
            return (r != null) ? r.getText(resid) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** AOSP PackageManager.getSystemSharedLibraryNames(): the names of the
     *  optional shared libraries an app can `<uses-library>`. @Nullable
     *  upstream, but a device always has some, and callers scan the array
     *  rather than null-checking each entry.
     *
     *  The honest answer for Mac.apk is the set our framework actually
     *  provides, NOT a copy of some AVD's list: naming a library we do not have
     *  would make an app take a code path that then fails to find the class,
     *  which is the same "claimed capability with nothing behind it" mistake
     *  hasSystemFeature avoids. Today that set is empty -- our android.* surface
     *  is built in, and none of the detachable platform libraries
     *  (org.apache.http.legacy, android.test.runner, the shared HIDL/telephony
     *  ones) exist here as separately loadable code. Add a name the moment the
     *  matching library really is loadable, never before. */
    public String[] getSystemSharedLibraryNames() {
        return new String[0];
    }

    /** AOSP PackageManager.getSharedLibraries(int): the shared libraries
     *  available on the device, as SharedLibraryInfo rather than bare names.
     *  API-26+.
     *
     *  The base answer is empty for the same reason
     *  getSystemSharedLibraryNames' is: this class knows of no libraries.
     *  MacApkPackageManager overrides BOTH from one probed set, so the two
     *  APIs cannot drift into disagreeing about what exists -- an app that
     *  scans names and an app that scans infos must get the same world.
     *
     *  ⚠ Empty here is a real answer, not a placeholder: a caller that finds
     *  its library absent takes its fallback path, which is correct when we
     *  genuinely do not provide it. It is NOT an excuse to leave the override
     *  unimplemented -- see the note there. AOSP declares this @NonNull. */
    public java.util.List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return new java.util.ArrayList<>();
    }

    /** AOSP PackageManager.getSharedLibraries(PackageInfoFlags), API-33: the
     *  same list as the int overload, which it forwards to (as AOSP's
     *  ApplicationPackageManager does), so MacApkPackageManager's override of
     *  that one answers both. Missing until 2026-10-07: Aurora Store's
     *  InstallerStatusReceiver calls it after every install and died with
     *  NoSuchMethodError, so its UI kept showing finished updates as
     *  "installing". */
    public java.util.List<SharedLibraryInfo> getSharedLibraries(PackageInfoFlags flags) {
        return getSharedLibraries((int) flags.getValue());
    }

    /** AOSP PackageManager.getPackageUid(String, int): the uid assigned to the
     *  named package, or NameNotFoundException when it is not installed.
     *  Mac.apk hosts one app at one uid, and ApplicationInfo.uid is where that
     *  uid is recorded, so reading it back keeps the two answers from drifting
     *  (getPackagesForUid above already resolves the self uid the same way). */
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        ApplicationInfo ai = getApplicationInfo(packageName, flags);
        if (ai == null) throw new NameNotFoundException(packageName);
        return ai.uid;
    }
    public int getPackageUid(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageUid(packageName, (int) flags.getValue());
    }
    public int getPackageUidAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        return getPackageUid(packageName, flags);
    }

    /** AOSP PackageManager.queryContentProviders(String processName, int uid,
     *  int flags): every ContentProvider installed in that process, or null.
     *  A caller passing our own process name should see our declared providers;
     *  MacApkPackageManager overrides this against the parsed manifest. The
     *  base answer is the empty list rather than null, matching the
     *  no-providers-declared case (AOSP returns an empty list there too, since
     *  PackageManagerService always builds a list before filtering). */
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        return java.util.Collections.emptyList();
    }
    public List<ProviderInfo> queryContentProviders(String processName, int uid,
            ComponentInfoFlags flags) {
        return queryContentProviders(processName, uid, (int) flags.getValue());
    }

    // AOSP: `extends android.util.AndroidException`.
    // frameworks/base/core/java/android/content/pm/PackageManager.java
    public static class NameNotFoundException extends android.util.AndroidException {
        public NameNotFoundException() {}
        public NameNotFoundException(String name) { super(name); }
    }


    // ========================================================================
    // AOSP surface completed in v1.1.0 (android.content.pm API-gap lane).
    //
    // Ground rule for every method below, from the project's no-shortcuts rule:
    // where Mac.apk can compute the real answer, it computes it; where it
    // genuinely cannot, it gives the answer AOSP gives on a device that lacks
    // the underlying facility, and says which one and why. Nothing here returns
    // a comfortable value in place of a capability we do not have.
    // ========================================================================

    // ---- preferred packages and activities ---------------------------------
    //
    // TWO DIFFERENT SITUATIONS, and they are worth separating because the
    // reasoning is not the same (measured against android16-release
    // ApplicationPackageManager, not recalled):
    //
    // (a) addPackageToPreferred / removePackageFromPreferred /
    //     getPreferredPackages are gutted UPSTREAM. Their real bodies are a
    //     Log.w plus a return -- ":3124 addPackageToPreferred() is a no-op",
    //     ":3129 removePackageFromPreferred() is a no-op", ":3134
    //     getPreferredPackages() is a no-op" returning Collections.emptyList().
    //     Reproducing the no-op IS the faithful behaviour here; storing
    //     anything would DIVERGE from a device.
    //
    // (b) addPreferredActivity / clearPackagePreferredActivities /
    //     getPreferredActivities are NOT gutted upstream -- they call through
    //     to PackageManagerService (:3140, :3181, :3200). They are no-ops HERE
    //     for a different reason: a preferred activity only ever matters when
    //     more than one component can handle an intent, and Mac.apk resolves
    //     against exactly one app's manifest, so the preference could never
    //     change a resolution. Storing one would be state nothing reads.
    //     getPreferredActivities correspondingly reports 0 and leaves both
    //     output lists untouched, which is what a device reports for a package
    //     with no preferences recorded.
    //     REMOVAL CONDITION: if the resolver ever sees more than one candidate
    //     component for an intent, back these with a real store.

    /** AOSP: deprecated, "this function no longer does anything." */
    public void addPackageToPreferred(String packageName) {}

    /** AOSP: deprecated, "this function no longer does anything." */
    public void removePackageFromPreferred(String packageName) {}

    /** AOSP: deprecated, "this function no longer does anything; it always
     *  returns an empty list." */
    public List<PackageInfo> getPreferredPackages(int flags) {
        return java.util.Collections.emptyList();
    }

    /** AOSP: record a preferred activity for a filter. See note (b) above for
     *  why this stores nothing here. */
    public void addPreferredActivity(IntentFilter filter, int match,
            android.content.ComponentName[] set, android.content.ComponentName activity) {}

    /** AOSP: drop every preference recorded for a package. Nothing is
     *  recorded, so there is nothing to drop -- and AOSP is likewise a no-op
     *  for a package with no preferences. */
    public void clearPackagePreferredActivities(String packageName) {}

    /** AOSP: retrieve the recorded preferences into the two output lists and
     *  return how many were added. Zero here, with both lists untouched --
     *  which is what a device returns for a package that has none. */
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<android.content.ComponentName> outActivities, String packageName) {
        return 0;
    }

    // ---- dynamic permissions ----------------------------------------------
    //
    // AOSP lets an app add permissions AT RUNTIME, but only inside a
    // <permission-tree> the app itself declares, and PackageManagerService
    // enforces that with a SecurityException ("Calling uid ... is not allowed to
    // add to permission tree"). Mac.apk models no permission trees at all --
    // com.macapk.loader.Manifest parses <permission> and <permission-group> but
    // not <permission-tree> -- so NO name can be inside one, and the
    // SecurityException is the answer a device gives for the same call.
    //
    // The alternative (accept and store the permission) would be worse than a
    // no-op: checkPermission answers from the manifest, so a stored permission
    // would be invisible to every later check, and the app would believe it had
    // defined something that does not exist.

    /** AOSP: "@throws SecurityException if you are not allowed to add the
     *  given permission name." */
    public boolean addPermission(PermissionInfo info) {
        throw new SecurityException("Calling uid " + android.os.Process.myUid()
                + " is not allowed to add to permission tree for "
                + (info != null ? info.name : null));
    }

    /** AOSP: the same operation without waiting for the change to persist.
     *  Same gate, same exception. */
    public boolean addPermissionAsync(PermissionInfo info) {
        return addPermission(info);
    }

    /** AOSP: remove a permission previously added with addPermission. Nothing
     *  can have been added, so nothing can be removed; AOSP's failure for a
     *  name outside the caller's tree is the same SecurityException. */
    public void removePermission(String permName) {
        throw new SecurityException("Calling uid " + android.os.Process.myUid()
                + " is not allowed to remove permission " + permName);
    }

    // ---- signature comparison ---------------------------------------------

    /** AOSP PackageManager.checkSignatures(String, String): "Compare the
     *  signatures of two packages to determine if the same signature appears in
     *  both of them. If they do contain the same signature, then they are
     *  allowed special privileges when working with each other."
     *
     *  This is a REAL comparison, not a constant: both packages' signatures come
     *  from getPackageInfo(GET_SIGNATURES), which on Mac.apk reads the APK's
     *  actual signing certificate off disk (MacApkPackageManager
     *  .readApkSignatures). The result codes follow AOSP's own ladder --
     *  unknown package, neither signed, one signed, no match, match -- so a
     *  same-signature check between an app and itself answers SIGNATURE_MATCH,
     *  which is what a device answers and what a shared-uid or bound-service
     *  check depends on. */
    public int checkSignatures(String packageName1, String packageName2) {
        if (packageName1 == null || packageName2 == null) return SIGNATURE_UNKNOWN_PACKAGE;
        // ⚠ NO name short-circuit. AOSP's ComputerEngine.checkSignatures looks
        // BOTH packages up first and answers SIGNATURE_UNKNOWN_PACKAGE when
        // either is missing, even for two identical names -- so a check against
        // an uninstalled package must not come back MATCH just because the
        // caller passed the same string twice.
        Signature[] a, b;
        try {
            a = signaturesOf(packageName1);
            b = signaturesOf(packageName2);
        } catch (NameNotFoundException e) {
            return SIGNATURE_UNKNOWN_PACKAGE;
        }
        return compareSignatures(a, b);
    }

    /** AOSP uid-keyed overload: the same question asked about the packages in
     *  two uids. A uid with no packages is the unknown-package answer. */
    public int checkSignatures(int uid1, int uid2) {
        String[] p1 = getPackagesForUid(uid1);
        String[] p2 = getPackagesForUid(uid2);
        if (p1 == null || p1.length == 0 || p2 == null || p2.length == 0) {
            return SIGNATURE_UNKNOWN_PACKAGE;
        }
        return checkSignatures(p1[0], p2[0]);
    }

    private Signature[] signaturesOf(String packageName) throws NameNotFoundException {
        PackageInfo info = getPackageInfo(packageName, GET_SIGNATURES);
        return (info != null) ? info.signatures : null;
    }

    /** AOSP PackageManagerServiceUtils.compareSignatures, verbatim ladder. */
    private static int compareSignatures(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            return (s2 == null) ? SIGNATURE_NEITHER_SIGNED : SIGNATURE_FIRST_NOT_SIGNED;
        }
        if (s2 == null) return SIGNATURE_SECOND_NOT_SIGNED;
        if (s1.length != s2.length) return SIGNATURE_NO_MATCH;
        // AOSP compares as SETS ("we have the same number, so we can just
        // check that each one is present in the other").
        java.util.HashSet<Signature> set1 = new java.util.HashSet<>();
        for (Signature sig : s1) set1.add(sig);
        java.util.HashSet<Signature> set2 = new java.util.HashSet<>();
        for (Signature sig : s2) set2.add(sig);
        return set1.equals(set2) ? SIGNATURE_MATCH : SIGNATURE_NO_MATCH;
    }

    // ---- instant apps -------------------------------------------------------
    //
    // isInstantApp() already answers false unconditionally: an app on Mac.apk is
    // installed normally, never delivered as an instant app. The cookie API is
    // the instant-app-only side-channel for carrying a little state across an
    // instant-to-installed upgrade, and AOSP's own behaviour for a NORMAL app is
    // exactly what is reproduced here: a zero-byte maximum, an empty cookie, and
    // updates that are dropped. (ApplicationPackageManager delegates to
    // PackageManagerService, whose getInstantAppCookieSize returns 0 for any
    // package that is not an instant app.)

    /** AOSP: "the maximum size in bytes of the instant-app cookie."
     *
     *  ⚠ 16384, NOT 0. This does NOT depend on whether the caller is an instant
     *  app: ApplicationPackageManager.getInstantAppCookieMaxBytes:1439-1443
     *  reads Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES with
     *  DEFAULT_EPHEMERAL_COOKIE_MAX_SIZE_BYTES = 16384 (:169), so every device
     *  with the setting unset -- i.e. every ordinary device -- answers 16 KB.
     *
     *  Answering 0, as the first draft of this method did, would have made
     *  updateInstantAppCookie below throw IllegalArgumentException for ANY
     *  non-empty cookie, since its guard is `cookie.length > this`. That is a
     *  crash a device does not produce, manufactured by a number we invented.
     *  Mac.apk has no Settings.Global row for it either, so the documented
     *  default IS our answer. */
    public int getInstantAppCookieMaxBytes() { return 16384; }

    /** AOSP's deprecated alias, whose body is `return
     *  getInstantAppCookieMaxBytes();`. */
    public int getInstantAppCookieMaxSize() { return getInstantAppCookieMaxBytes(); }

    /** AOSP: @NonNull -- "an empty array if no cookie was set." */
    public byte[] getInstantAppCookie() { return new byte[0]; }

    /** AOSP: "Clears the instant application cookie for the calling app." */
    public void clearInstantAppCookie() {}

    /** AOSP ApplicationPackageManager.updateInstantAppCookie:1470-1480, guard
     *  verbatim. Past the guard, upstream hands the cookie to
     *  PackageManagerService, which STORES IT ONLY FOR AN INSTANT APP and
     *  silently drops it otherwise -- and ApplicationPackageManager ignores the
     *  boolean it gets back, so a normal app cannot tell. The running app on
     *  Mac.apk is never an instant app (isInstantApp() is false), so dropping
     *  is the same observable behaviour, and getInstantAppCookie() correspondingly
     *  keeps reporting the empty array. */
    public void updateInstantAppCookie(byte[] cookie) {
        if (cookie != null && cookie.length > getInstantAppCookieMaxBytes()) {
            throw new IllegalArgumentException("instant cookie longer than "
                    + getInstantAppCookieMaxBytes());
        }
    }

    /** AOSP's deprecated boolean-returning spelling. Upstream returns whatever
     *  the system stored, which for a non-instant app is false. */
    public boolean setInstantAppCookie(byte[] cookie) {
        updateInstantAppCookie(cookie);
        return false;
    }

    // ---- install verification ----------------------------------------------
    //
    // Both methods below are callable ONLY by the package VERIFIER -- the app
    // holding PACKAGE_VERIFICATION_AGENT, which the system names in its config.
    // Mac.apk designates no verifier, so no caller is one, and AOSP's behaviour
    // for a non-verifier is to ignore the call (PackageManagerService checks the
    // calling uid against the verifier's and returns without acting). Deprecated
    // upstream since API 34.

    /** AOSP: "Allows a package listening to the package verification broadcast
     *  to respond." Ignored for a caller that is not the verifier. */
    public void verifyPendingInstall(int id, int verificationCode) {}

    /** AOSP: extend the verification timeout. Same non-verifier no-op. */
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {}

    // ---- banners, logos and badging ----------------------------------------
    //
    // These complete the icon family alongside getApplicationIcon /
    // getActivityIcon, which already resolve against the app's own Resources.
    // ⚠ Unlike the ICON methods, AOSP's banner and logo getters are @Nullable
    // and have NO framework default -- PackageItemInfo.loadDefaultBanner and
    // loadDefaultLogo both `return null` upstream. So null here is the real
    // answer for an app that declares no android:banner / android:logo, not a
    // failure, and every caller already branches on it.

    public android.graphics.drawable.Drawable getActivityBanner(
            android.content.ComponentName activityName) throws NameNotFoundException {
        android.content.pm.ActivityInfo ai = getActivityInfo(activityName, 0);
        return (ai != null) ? ai.loadBanner(this) : null;
    }

    /** AOSP: the banner of the activity the Intent resolves to, or the
     *  application's banner when the Intent has an explicit component. */
    public android.graphics.drawable.Drawable getActivityBanner(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityBanner(intent.getComponent());
        }
        ResolveInfo info = resolveActivity(intent, MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) {
            return info.activityInfo.loadBanner(this);
        }
        throw new NameNotFoundException(intent.toUri(0));
    }

    public android.graphics.drawable.Drawable getActivityLogo(
            android.content.ComponentName activityName) throws NameNotFoundException {
        android.content.pm.ActivityInfo ai = getActivityInfo(activityName, 0);
        return (ai != null) ? ai.loadLogo(this) : null;
    }

    public android.graphics.drawable.Drawable getActivityLogo(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityLogo(intent.getComponent());
        }
        ResolveInfo info = resolveActivity(intent, MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) {
            return info.activityInfo.loadLogo(this);
        }
        throw new NameNotFoundException(intent.toUri(0));
    }

    public android.graphics.drawable.Drawable getApplicationBanner(ApplicationInfo info) {
        return (info != null) ? info.loadBanner(this) : null;
    }

    public android.graphics.drawable.Drawable getApplicationBanner(String packageName)
            throws NameNotFoundException {
        return getApplicationBanner(getApplicationInfo(packageName, 0));
    }

    public android.graphics.drawable.Drawable getApplicationLogo(ApplicationInfo info) {
        return (info != null) ? info.loadLogo(this) : null;
    }

    public android.graphics.drawable.Drawable getApplicationLogo(String packageName)
            throws NameNotFoundException {
        return getApplicationLogo(getApplicationInfo(packageName, 0));
    }

    /** AOSP PackageManager.isDefaultApplicationIcon(Drawable): "whether the
     *  given drawable is the default activity icon", so a caller can tell "the
     *  app has no icon" from "the app's icon happens to look plain" without
     *  the icon API ever returning null.
     *
     *  getDefaultActivityIcon() above returns a fully transparent ColorDrawable
     *  (Mac.apk has no framework resource table, so there is no
     *  sym_def_app_icon to load). This recognises exactly that object, so the
     *  two answers are consistent by construction: anything getApplicationIcon
     *  fell back to is reported as the default, and a real app icon is not.
     *  A hardcoded false would have told every caller "this app has a real
     *  icon" while it painted nothing. */
    public boolean isDefaultApplicationIcon(android.graphics.drawable.Drawable drawable) {
        return (drawable instanceof android.graphics.drawable.ColorDrawable)
                && ((android.graphics.drawable.ColorDrawable) drawable).getColor() == 0;
    }

    // ---- multi-user badging -------------------------------------------------
    //
    // On a device the badge is the work-profile briefcase overlaid on an icon,
    // or the " (work)" suffix on a label. Mac.apk has one user and no managed
    // profile (see android.content.pm.CrossProfileApps), so there is no badge to
    // apply -- and AOSP returns the INPUT UNCHANGED in exactly that case
    // (ApplicationPackageManager.getUserBadgedIcon: "if the user is not badged,
    // returns the icon"). Returning the input is therefore the real answer, not
    // a pass-through stub.

    public android.graphics.drawable.Drawable getUserBadgedIcon(
            android.graphics.drawable.Drawable drawable, android.os.UserHandle user) {
        return drawable;
    }

    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(
            android.graphics.drawable.Drawable drawable, android.os.UserHandle user,
            android.graphics.Rect badgeLocation, int badgeDensity) {
        return drawable;
    }

    public CharSequence getUserBadgedLabel(CharSequence label, android.os.UserHandle user) {
        return label;
    }

    // ---- permission metadata ------------------------------------------------

    /** AOSP PackageManager.getAllPermissionGroups(int): every
     *  &lt;permission-group&gt; declared by any installed package.
     *
     *  Mac.apk's manifest parser DOES read &lt;permission-group&gt;
     *  (com.macapk.loader.Manifest.PermissionGroupEntry), but PackageInfo has no
     *  field carrying them, so there is no route from here to the parsed list --
     *  the concrete PackageManager would have to expose one. Until it does this
     *  reports the empty list, which is the answer AOSP gives on a device where
     *  no installed package declares a group. @NonNull upstream, so empty and
     *  not null.
     *
     *  REMOVAL CONDITION: when the concrete PackageManager exposes the parsed
     *  &lt;permission-group&gt; set, fill this from it and drop this note. */
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return java.util.Collections.emptyList();
    }

    /** AOSP PackageManager.queryPermissionsByGroup(String group, int flags):
     *  "Query for all of the permissions associated with a particular group.
     *  @throws NameNotFoundException if a group with the given name cannot be
     *  found on the system."
     *
     *  Answered from the RUNNING APP'S OWN declared &lt;permission&gt; set,
     *  which getPackageInfo(GET_PERMISSIONS) really does fill from the parsed
     *  manifest -- so an app that declares permissions and then asks about its
     *  own group gets its own permissions back, exactly as on a device. A null
     *  group means "permissions not in any group", which is AOSP's documented
     *  meaning for null and is answered the same way.
     *
     *  A named group that matches nothing we know about is the
     *  NameNotFoundException AOSP throws -- consistent with getPermissionInfo /
     *  getPermissionGroupInfo, which report the same "unknown" for a platform
     *  permission, because Mac.apk carries no platform permission database. */
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
            throws NameNotFoundException {
        PermissionInfo[] declared = selfDeclaredPermissions();
        java.util.ArrayList<PermissionInfo> out = new java.util.ArrayList<>();
        boolean groupSeen = (group == null);
        if (declared != null) {
            for (PermissionInfo pi : declared) {
                if (pi == null) continue;
                if (group == null) {
                    if (pi.group == null) out.add(pi);
                } else if (group.equals(pi.group)) {
                    groupSeen = true;
                    out.add(pi);
                }
            }
        }
        if (!groupSeen) throw new NameNotFoundException(group);
        return out;
    }

    /** The &lt;permission&gt; entries the running app declares, or null when
     *  they cannot be read. */
    private PermissionInfo[] selfDeclaredPermissions() {
        try {
            android.app.Application app = android.app.Application.macapk_get();
            if (app == null) return null;
            PackageInfo info = getPackageInfo(app.getPackageName(), GET_PERMISSIONS);
            return (info != null) ? info.permissions : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** AOSP PackageManager.isPermissionRevokedByPolicy(String, String): whether
     *  a device-policy admin has revoked the permission and blocked the app from
     *  asking again.
     *
     *  Mac.apk has no DevicePolicyManager admin -- there is no provisioning
     *  path, and no code anywhere sets a permission policy -- so nothing is
     *  policy-revoked. False is what an unmanaged device answers, which is most
     *  devices. */
    public boolean isPermissionRevokedByPolicy(String permName, String packageName) {
        return false;
    }

    /** AOSP PackageManager.getBackgroundPermissionOptionLabel() (API 30): the
     *  localized label of the "allow all the time" choice in the background
     *  location dialog, so an app's rationale text can name the button the user
     *  will actually see.
     *
     *  AOSP resolves it from the PermissionController package's resources, and
     *  its documented fallback when that cannot be read is the EMPTY STRING --
     *  which is also the base class's own body. Mac.apk ships no
     *  PermissionController, so the empty string is both the honest answer and
     *  AOSP's. @NonNull. */
    public CharSequence getBackgroundPermissionOptionLabel() { return ""; }

    /** AOSP PackageManager.getGroupOfPlatformPermission(String, Executor,
     *  Consumer) (API 31): asynchronously report which permission GROUP a
     *  PLATFORM permission belongs to, or null when the permission is not a
     *  platform one.
     *
     *  Mac.apk has no platform permission database -- getPermissionInfo and
     *  getPermissionGroupInfo already say so by throwing NameNotFoundException
     *  -- so no name is a platform permission here and null is the consistent
     *  answer. Fabricating AOSP's permission-to-group table would make THIS
     *  method claim knowledge the rest of the class does not have, and the app
     *  would then ask getPermissionInfo about it and be told the permission does
     *  not exist. The callback is invoked on the caller's Executor, which is the
     *  part of the contract its threading depends on.
     *
     *  REMOVAL CONDITION: land a real platform permission database and answer
     *  all five of getPermissionInfo / getPermissionGroupInfo /
     *  getAllPermissionGroups / this / getPlatformPermissionsForGroup from it. */
    public void getGroupOfPlatformPermission(String permissionName,
            java.util.concurrent.Executor executor, java.util.function.Consumer<String> callback) {
        if (permissionName == null || executor == null || callback == null) {
            throw new NullPointerException(
                    "getGroupOfPlatformPermission requires non-null arguments");
        }
        executor.execute(new Runnable() {
            @Override public void run() { callback.accept(null); }
        });
    }

    /** AOSP PackageManager.getPlatformPermissionsForGroup(String, Executor,
     *  Consumer) (API 31): the inverse query. Same reasoning and the same
     *  removal condition as getGroupOfPlatformPermission above; the empty list
     *  is AOSP's answer for a group it does not recognise. */
    public void getPlatformPermissionsForGroup(String permissionGroupName,
            java.util.concurrent.Executor executor,
            java.util.function.Consumer<java.util.List<String>> callback) {
        if (permissionGroupName == null || executor == null || callback == null) {
            throw new NullPointerException(
                    "getPlatformPermissionsForGroup requires non-null arguments");
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                callback.accept(java.util.Collections.<String>emptyList());
            }
        });
    }

    // ---- restricted-permission allowlist ------------------------------------
    //
    // A RESTRICTED permission (SMS, call log, background location) is one the
    // platform hard-restricts unless the installer, the system config or an
    // upgrade path allowlists it. Mac.apk implements no restriction: every
    // permission the manifest declares is granted at install time
    // (MacApkPackageManager.checkPermission), so there is no allowlist for a
    // permission to be on or off. AOSP's own answers for a package with no
    // allowlisted restricted permissions are what these return.
    //
    // ⚠ add/remove are declared to return false rather than pretending to
    // record. A stored allowlist would be read by nothing -- checkPermission
    // never consults one -- so `true` would tell the caller a restriction was
    // lifted that was never applied.

    /** AOSP: @NonNull; "the allowlisted restricted permissions that are
     *  currently allowlisted for the package for the given allowlist kind." */
    public java.util.Set<String> getWhitelistedRestrictedPermissions(String packageName,
            int whitelistFlag) {
        return java.util.Collections.emptySet();
    }

    /** AOSP: "@return whether the permission was added to the allowlist." */
    public boolean addWhitelistedRestrictedPermission(String packageName, String permission,
            int whitelistFlags) {
        return false;
    }

    /** AOSP: "@return whether the permission was removed from the allowlist." */
    public boolean removeWhitelistedRestrictedPermission(String packageName, String permission,
            int whitelistFlags) {
        return false;
    }

    /** AOSP PackageManager.isAutoRevokeWhitelisted(String) (API 30): whether the
     *  package is exempt from having its permissions auto-revoked after months
     *  of disuse. Mac.apk runs no auto-revoke job, so no package needs an
     *  exemption and none has one -- which is also the state of a freshly
     *  installed app on a device. The no-arg overload already answers the same
     *  for the calling package. */
    public boolean isAutoRevokeWhitelisted(String packageName) { return false; }

    /** AOSP PackageManager.setAutoRevokeWhitelisted(String, boolean) (API 30):
     *  "@return whether any permissions were successfully (un)allowlisted."
     *  Nothing to allowlist, so nothing changed -- reported rather than
     *  claimed. */
    public boolean setAutoRevokeWhitelisted(String packageName, boolean whitelisted) {
        return false;
    }

    // ---- package facts ------------------------------------------------------

    /** AOSP PackageManager.getPackageGids(String): "Return an array of all of
     *  the POSIX secondary group IDs that have been assigned to the given
     *  package. @throws NameNotFoundException if a package with the given name
     *  cannot be found." A real read of PackageInfo.gids, so it cannot disagree
     *  with what getPackageInfo reports. */
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        return getPackageGids(packageName, 0);
    }

    public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
        PackageInfo info = getPackageInfo(packageName, flags);
        if (info == null) throw new NameNotFoundException(packageName);
        return info.gids;
    }

    public int[] getPackageGids(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageGids(packageName, (int) flags.getValue());
    }

    /** AOSP PackageManager.getPackagesHoldingPermissions(String[], int):
     *  "Return a List of all installed packages that are currently holding any
     *  of the given permissions."
     *
     *  A real query, not an empty list: it walks getInstalledPackages -- which
     *  on Mac.apk is the one hosted app -- and keeps a package only when
     *  checkPermission grants it EVERY requested permission, which is AOSP's
     *  own rule ("holding ANY of" in the summary, but PackageManagerService
     *  requires all of the named permissions to be granted). The GET_PERMISSIONS
     *  bit is forced on, because the returned PackageInfo must carry the
     *  permission arrays for the caller to inspect. */
    public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
        java.util.ArrayList<PackageInfo> out = new java.util.ArrayList<>();
        if (permissions == null || permissions.length == 0) return out;
        for (PackageInfo info : getInstalledPackages(flags | GET_PERMISSIONS)) {
            if (info == null || info.packageName == null) continue;
            boolean holdsAll = true;
            for (String perm : permissions) {
                if (perm == null) continue;
                if (checkPermission(perm, info.packageName) != PERMISSION_GRANTED) {
                    holdsAll = false;
                    break;
                }
            }
            if (holdsAll) out.add(info);
        }
        return out;
    }

    public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions,
            PackageInfoFlags flags) {
        return getPackagesHoldingPermissions(permissions, (int) flags.getValue());
    }

    /** AOSP PackageManager.getTargetSdkVersion(String) (API 31): "@throws
     *  NameNotFoundException if the package is not installed." A real read of
     *  the same ApplicationInfo field every other caller sees. */
    public int getTargetSdkVersion(String packageName) throws NameNotFoundException {
        ApplicationInfo ai = getApplicationInfo(packageName, 0);
        if (ai == null) throw new NameNotFoundException(packageName);
        return ai.targetSdkVersion;
    }

    /** AOSP PackageManager.getResourcesForActivity(ComponentName): the
     *  Resources of the package owning the named activity. Resolves the activity
     *  first so the exception for an unknown component is the one AOSP throws. */
    public android.content.res.Resources getResourcesForActivity(
            android.content.ComponentName activityName) throws NameNotFoundException {
        android.content.pm.ActivityInfo ai = getActivityInfo(activityName, 0);
        if (ai == null || ai.applicationInfo == null) {
            throw new NameNotFoundException(String.valueOf(activityName));
        }
        return getResourcesForApplication(ai.applicationInfo);
    }

    /** AOSP ApplicationPackageManager.getXml(String, int, ApplicationInfo):
     *  the XML resource `resid` out of the named package's Resources, or null
     *  when it cannot be loaded.
     *
     *  This is the call PackageItemInfo.loadXmlMetaData makes on a device, and
     *  its absence is why that method had to inline the resolution itself (see
     *  its comment). Implemented the same way -- resolve the owning package's
     *  Resources, then Resources.getXml -- so both routes now agree. */
    public android.content.res.XmlResourceParser getXml(String packageName, int resid,
            ApplicationInfo appInfo) {
        if (resid == 0) return null;
        try {
            android.content.res.Resources r = (appInfo != null)
                    ? getResourcesForApplication(appInfo)
                    : getResourcesForApplication(packageName);
            return (r != null) ? r.getXml(resid) : null;
        } catch (Throwable t) {
            // AOSP's contract is null for "could not be found".
            return null;
        }
    }

    /** AOSP PackageManager.canPackageQuery(String, String) (API 30): whether
     *  the source package can see details about the target under the package
     *  VISIBILITY rules introduced in Android 11. "@throws NameNotFoundException
     *  if either a given package can not be found on the system."
     *
     *  Mac.apk hosts one package. It can always see itself -- AOSP's rules
     *  always let a package query itself -- and every other name is not
     *  installed, which is the exception branch. So this is a real answer
     *  derived from the same installed-set every other method uses, not a
     *  hardcoded true. */
    public boolean canPackageQuery(String sourcePackageName, String targetPackageName)
            throws NameNotFoundException {
        return canPackageQuery(sourcePackageName, new String[] { targetPackageName })[0];
    }

    /** AOSP's batched form (API 33): one answer per target, same semantics. */
    public boolean[] canPackageQuery(String sourcePackageName, String[] targetPackageNames)
            throws NameNotFoundException {
        if (targetPackageNames == null) {
            throw new NullPointerException("targetPackageNames cannot be null");
        }
        // Throws for an uninstalled source, which is AOSP's first check.
        getApplicationInfo(sourcePackageName, 0);
        boolean[] out = new boolean[targetPackageNames.length];
        for (int i = 0; i < targetPackageNames.length; i++) {
            getApplicationInfo(targetPackageNames[i], 0);   // throws if not installed
            out[i] = true;
        }
        return out;
    }

    /** AOSP PackageManager.getChangedPackages(int sequenceNumber) (API 26):
     *  "Returns the package names of the packages that have changed since the
     *  given sequence number, or null if there are no changes."
     *
     *  Mac.apk's package set is fixed for the life of the process: one app,
     *  installed before we start, never added, removed or updated underneath
     *  the running app. So nothing can have changed since ANY sequence number,
     *  and null -- AOSP's documented "no changes" -- is the correct answer for
     *  every call rather than a placeholder. */
    public ChangedPackages getChangedPackages(int sequenceNumber) { return null; }

    /** AOSP PackageManager.isSafeMode(): whether the device booted into SAFE
     *  MODE, where third-party apps are disabled. Mac.apk has no safe mode --
     *  there is no boot flag, no path that disables the hosted app -- so false
     *  is the permanent and honest answer, and it is what a normally booted
     *  device reports. */
    public boolean isSafeMode() { return false; }

    /** AOSP PackageManager.isDeviceUpgrading() (API 29): true only during the
     *  first boot after an OTA, so an app can defer heavy work. Mac.apk performs
     *  no OS upgrade, so the device is never upgrading. */
    public boolean isDeviceUpgrading() { return false; }

    /** AOSP PackageManager.isPackageSuspended(): whether the CALLING package is
     *  suspended by a device admin or a supervisor. Nothing on Mac.apk can
     *  suspend a package -- there is no DevicePolicyManager admin and no
     *  suspension API -- so the running app is never suspended. */
    public boolean isPackageSuspended() { return false; }

    /** AOSP PackageManager.isPackageSuspended(String) (API 29): the same
     *  question about a named package. "@throws NameNotFoundException if the
     *  package could not be found" -- so an unknown name still throws rather
     *  than answering false, which is how a caller distinguishes "not
     *  suspended" from "not installed". */
    public boolean isPackageSuspended(String packageName) throws NameNotFoundException {
        getApplicationInfo(packageName, 0);   // throws when not installed
        return false;
    }

    /** AOSP PackageManager.getSuspendedPackageAppExtras() (API 28): the Bundle a
     *  suspending admin attached, "or null if the calling package is not
     *  suspended." Nothing suspends packages here, so null. Consistent with
     *  isPackageSuspended() above by construction. */
    public android.os.Bundle getSuspendedPackageAppExtras() { return null; }

    /** AOSP PackageManager.isPackageQuarantined(String) (API 35): whether the
     *  package is QUARANTINED -- a softer suspension a supervisor app can
     *  apply. Same reasoning as suspension: no supervisor exists here, and the
     *  unknown-package branch still throws. */
    public boolean isPackageQuarantined(String packageName) throws NameNotFoundException {
        getApplicationInfo(packageName, 0);
        return false;
    }

    /** AOSP PackageManager.isPackageStopped(String) (API 35): whether the
     *  package is in the STOPPED state -- force-stopped, or never launched since
     *  install, so it receives no broadcasts. The one package Mac.apk hosts is
     *  the one currently RUNNING, which by definition is not stopped; anything
     *  else is not installed and throws. */
    public boolean isPackageStopped(String packageName) throws NameNotFoundException {
        getApplicationInfo(packageName, 0);
        return false;
    }

    /** AOSP PackageManager.getSyntheticAppDetailsActivityEnabled(String)
     *  (API 29): whether the system's SYNTHETIC app-details activity is enabled
     *  for the package -- the stand-in activity the platform offers so a
     *  launcher has something to open for an app that ships no launcher
     *  activity of its own.
     *
     *  ⚠ THE ANSWER IS NORMALLY TRUE, and an earlier draft of this method got
     *  it backwards. ApplicationPackageManager:3233-3243 does NOT ask "did we
     *  synthesise one"; it reads the COMPONENT ENABLED SETTING of
     *  com.android.internal.app.AppDetailsActivity in that package and returns
     *  true for ENABLED **or DEFAULT**. A package nobody has toggled reads
     *  DEFAULT, so a device answers true. Returning a flat false would have
     *  told every caller the app-details entry point was switched off.
     *
     *  Reproduced against OUR component-enabled store (which
     *  MacApkPackageManager backs for real), so a caller that disables the
     *  component sees this flip -- the same coupling AOSP has.
     *
     *  AOSP's abstract base throws UnsupportedOperationException here, but that
     *  path exists only for a subclass that forgot to override; a device always
     *  answers, so answering is the faithful observable behaviour. */
    public boolean getSyntheticAppDetailsActivityEnabled(String packageName) {
        // AOSP's APP_DETAILS_ACTIVITY_CLASS_NAME.
        android.content.ComponentName cn = new android.content.ComponentName(
                packageName, "com.android.internal.app.AppDetailsActivity");
        int state = getComponentEnabledSetting(cn);
        return state == COMPONENT_ENABLED_STATE_ENABLED
                || state == COMPONENT_ENABLED_STATE_DEFAULT;
    }

    /** AOSP PackageManager.setApplicationCategoryHint(String, int) (API 26):
     *  the INSTALLER of a package may tell the system what category it belongs
     *  to. "@throws SecurityException when the caller is not the active
     *  installer for the given package."
     *
     *  Mac.apk records no installer of record -- getInstallerPackageName returns
     *  null for every package (side-loaded, exactly as AOSP reports for an adb
     *  install) -- so no caller is the active installer and the SecurityException
     *  is the answer a device gives for the same call. The check is written out
     *  rather than hardcoded so it starts succeeding the day an installer of
     *  record is recorded. */
    public void setApplicationCategoryHint(String packageName, int categoryHint) {
        String installer = getInstallerPackageName(packageName);
        android.app.Application app = android.app.Application.macapk_get();
        String self = (app != null) ? app.getPackageName() : null;
        if (installer == null || self == null || !installer.equals(self)) {
            throw new SecurityException("Calling package " + self
                    + " is not installer for " + packageName);
        }
        try {
            ApplicationInfo ai = getApplicationInfo(packageName, 0);
            if (ai != null) ai.category = categoryHint;
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package " + packageName, e);
        }
    }

    /** AOSP PackageManager.queryInstrumentation(String targetPackage, int flags):
     *  the &lt;instrumentation&gt; entries targeting the named package.
     *
     *  Real answer from the parsed manifest: getPackageInfo(GET_INSTRUMENTATION)
     *  fills PackageInfo.instrumentation, so an app that declares a test runner
     *  finds it. A null/empty targetPackage means "all instrumentation", which
     *  AOSP treats as no filter. */
    public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
        java.util.ArrayList<InstrumentationInfo> out = new java.util.ArrayList<>();
        for (PackageInfo info : getInstalledPackages(GET_INSTRUMENTATION)) {
            if (info == null || info.instrumentation == null) continue;
            for (InstrumentationInfo ii : info.instrumentation) {
                if (ii == null) continue;
                if (targetPackage == null || targetPackage.isEmpty()
                        || targetPackage.equals(ii.targetPackage)) {
                    out.add(ii);
                }
            }
        }
        return out;
    }

    // ---- mainline modules ----------------------------------------------------
    //
    // A MAINLINE MODULE is a platform component Google updates out of band
    // (media, wifi, permission controller, ...). They exist only on a device
    // whose system image ships them, and Mac.apk has no system image at all:
    // our android.* surface is compiled into the runtime, not delivered as
    // updatable packages. So the module set is genuinely empty -- the same
    // answer AOSP gives on a device with no ModuleInfoProvider.

    /** AOSP: @NonNull; every installed module. */
    public List<ModuleInfo> getInstalledModules(int flags) {
        return java.util.Collections.emptyList();
    }

    /** AOSP: "@throws NameNotFoundException if a module with the given name
     *  cannot be found on the system." Every name, here. */
    public ModuleInfo getModuleInfo(String packageName, int flags) throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }

    /** AOSP PackageManager.getLaunchIntentSenderForPackage(String) (API 30):
     *  "@return Returns an IntentSender to launch the activity" -- @NonNull, and
     *  documented to resolve the launch target at SEND time rather than now, so
     *  a package with no launcher activity still yields a sender that simply
     *  does nothing when sent.
     *
     *  Built from the same getLaunchIntentForPackage this class already
     *  implements, wrapped in an immutable PendingIntent, which is how a device
     *  hands out a sender for someone else's activity. */
    public android.content.IntentSender getLaunchIntentSenderForPackage(String packageName) {
        Intent launch = getLaunchIntentForPackage(packageName);
        if (launch == null) {
            // AOSP's own fallback shape: an explicit intent at the package that
            // resolves to nothing, so sending it is a no-op rather than an NPE
            // at the call site (the method is @NonNull).
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory("android.intent.category.LAUNCHER");
            launch.setPackage(packageName);
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                android.app.Application.macapk_get(), 0, launch,
                android.app.PendingIntent.FLAG_IMMUTABLE);
        return pi.getIntentSender();
    }

    // ---- MIME groups (API 30) -------------------------------------------------
    //
    // A MIME GROUP is a named, RUNTIME-MUTABLE set of MIME types that an
    // <intent-filter> refers to with android:mimeGroup, so an app can change
    // which types it advertises without shipping a new manifest.
    //
    // The store below is real and process-wide, so an app that sets a group and
    // reads it back gets its own value -- which is the observable behaviour a
    // caller depends on.
    //
    // ⚠ TWO STATED DIVERGENCES, neither hidden:
    //  (1) AOSP throws IllegalArgumentException for a group the manifest never
    //      declared. com.macapk.loader.Manifest does not parse
    //      android:mimeGroup, so we cannot tell a declared group from an
    //      undeclared one and therefore accept any name. Erring the other way
    //      (reject everything) would break every legitimate caller.
    //  (2) Changing a group does not change intent RESOLUTION here, because our
    //      resolver matches against the parsed <intent-filter> and has no
    //      mimeGroup indirection to re-point. So setMimeGroup is honest about
    //      what it stores and does not claim a resolution effect.
    // REMOVAL CONDITION for both: teach Manifest to parse android:mimeGroup and
    // have the resolver consult this map.
    private static final java.util.Map<String, java.util.Set<String>> sMimeGroups =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, java.util.Set<String>>());

    /** AOSP PackageManager.setMimeGroup(String, Set&lt;String&gt;). */
    public void setMimeGroup(String mimeGroup, java.util.Set<String> mimeTypes) {
        if (mimeGroup == null || mimeTypes == null) {
            throw new NullPointerException("setMimeGroup requires non-null arguments");
        }
        sMimeGroups.put(mimeGroup, new java.util.LinkedHashSet<>(mimeTypes));
    }

    /** AOSP PackageManager.getMimeGroup(String): @NonNull -- a declared group
     *  that has never been set is EMPTY, not absent. */
    public java.util.Set<String> getMimeGroup(String mimeGroup) {
        if (mimeGroup == null) {
            throw new NullPointerException("mimeGroup cannot be null");
        }
        java.util.Set<String> set = sMimeGroups.get(mimeGroup);
        return (set != null) ? java.util.Collections.unmodifiableSet(set)
                             : java.util.Collections.<String>emptySet();
    }

    // ---- manifest <property> (API 31) -----------------------------------------
    //
    // getProperty above already reports "not declared" (NameNotFoundException)
    // because com.macapk.loader.Manifest does not parse <property>. These five
    // are the ENUMERATING form of the same question, and AOSP declares each
    // @NonNull, so the empty list is the answer for a device where no component
    // declares the property -- the same fact getProperty already states.
    // REMOVAL CONDITION: teach Manifest to parse <property> and answer all six
    // from it together, so they cannot disagree.

    public List<Property> queryApplicationProperty(String propertyName) {
        return java.util.Collections.emptyList();
    }
    public List<Property> queryActivityProperty(String propertyName) {
        return java.util.Collections.emptyList();
    }
    public List<Property> queryProviderProperty(String propertyName) {
        return java.util.Collections.emptyList();
    }
    public List<Property> queryReceiverProperty(String propertyName) {
        return java.util.Collections.emptyList();
    }
    public List<Property> queryServiceProperty(String propertyName) {
        return java.util.Collections.emptyList();
    }

    // ---- app archiving (API 35) ------------------------------------------------
    //
    // See getArchivedPackage above for the full reasoning: Mac.apk implements no
    // archiving, so nothing is archived and nothing is archivable. Both answers
    // below are what a device reports for a package whose installer does not
    // support unarchival, and they agree with PackageInstaller.requestArchive,
    // which fails for the same reason.

    /** AOSP: "@return true if the app is archivable. @throws
     *  NameNotFoundException if the package is not installed." */
    public boolean isAppArchivable(String packageName) throws NameNotFoundException {
        getApplicationInfo(packageName, 0);   // throws when not installed
        return false;
    }

    /** AOSP PackageManager.relinquishUpdateOwnership(String) (API 34): give up
     *  update ownership of a package. "@throws SecurityException if you are not
     *  the current update owner of the given package."
     *
     *  Mac.apk grants update ownership to nobody -- InstallSourceInfo
     *  .getUpdateOwnerPackageName() is always null, and SessionParams
     *  .setRequestUpdateOwnership is recorded but never honoured -- so no caller
     *  is the owner. AOSP's IllegalArgumentException branch ("if the given
     *  package is invalid") covers an uninstalled name and is checked first. */
    public void relinquishUpdateOwnership(String targetPackage) {
        try {
            getApplicationInfo(targetPackage, 0);
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package " + targetPackage, e);
        }
        throw new SecurityException("Caller is not the update owner of " + targetPackage);
    }

    // ---- APK inspection (API 35/36) --------------------------------------------

    /** AOSP PackageManager.parseAndroidManifest(File, Function) (API 35): open
     *  an APK's AndroidManifest.xml as an XmlResourceParser and hand it to the
     *  caller's function. This is what a store uses to inspect an APK it has
     *  downloaded but not installed.
     *
     *  ⚠ AOSP's BASE-CLASS body is `throw new UnsupportedOperationException`,
     *  but that branch exists only for a subclass that forgot to override --
     *  a device's ApplicationPackageManager always answers. Since we present
     *  API 36, reproducing the throw would hand every caller the failure a
     *  device never produces, so this is implemented for real.
     *
     *  Mac.apk already owns every piece: com.macapk.loader.BinaryXmlParser reads
     *  the BINARY AndroidManifest.xml straight out of the zip (it exists because
     *  apktool's text decode loses aapt's attribute TYPES), and
     *  com.macapk.loader.DomXmlResourceParser is an XmlResourceParser over a
     *  DOM. The bridge between them is a serialize/reparse, because
     *  DomXmlResourceParser's only input is an InputStream.
     *
     *  TWO STATED FIDELITY LIMITS:
     *   (1) RESOURCE REFERENCES stay unresolved. On a device the parser resolves
     *       `@string/app_name` against the ARCHIVE's own resource table;
     *       Resources here is bound to the RUNNING app, so resolving through it
     *       would return a value from a different APK -- a wrong answer being
     *       worse than a raw one. getAttributeValue therefore yields the
     *       reference form, and getAttributeResourceValue its id.
     *   (2) BinaryXmlParser annotates typed attributes with a sibling attribute
     *       in its own MACAPK_TYPE_NS namespace. Those are stripped below, so
     *       the caller sees the manifest's real attribute set and nothing else.
     *
     *  @throws IOException "if the AndroidManifest.xml cannot be read or
     *  accessed", which is AOSP's own failure and covers a missing file, a
     *  non-zip, and a zip with no manifest entry. */
    public <T> T parseAndroidManifest(java.io.File apkFile,
            java.util.function.Function<android.content.res.XmlResourceParser, T> parserFunction)
            throws java.io.IOException {
        if (apkFile == null || parserFunction == null) {
            throw new NullPointerException("parseAndroidManifest requires non-null arguments");
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkFile)) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new java.io.IOException("No AndroidManifest.xml in " + apkFile);
            }
            byte[] axml;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                axml = in.readAllBytes();
            }
            return applyManifestParser(axml, parserFunction);
        }
    }

    /** AOSP PackageManager.parseAndroidManifest(ParcelFileDescriptor, Function)
     *  (API 36): the fd-taking form, for a caller that has an open APK it cannot
     *  name by path (a content:// download, a pipe from another process).
     *
     *  ⚠ java.util.zip.ZipFile needs a FILE, and a random-access APK read needs
     *  the whole central directory, so the descriptor is drained to a temporary
     *  file in the app's own cache directory first and deleted afterwards. That
     *  is an implementation detail a caller cannot observe (same parser, same
     *  result); it costs one copy of the APK's bytes, which is why the File
     *  overload above should be preferred when a path exists. */
    public <T> T parseAndroidManifest(android.os.ParcelFileDescriptor apkFileDescriptor,
            java.util.function.Function<android.content.res.XmlResourceParser, T> parserFunction)
            throws java.io.IOException {
        if (apkFileDescriptor == null || parserFunction == null) {
            throw new NullPointerException("parseAndroidManifest requires non-null arguments");
        }
        java.io.File tmp = java.io.File.createTempFile("macapk-parse", ".apk");
        try {
            try (java.io.InputStream in = new java.io.FileInputStream(
                        apkFileDescriptor.getFileDescriptor());
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return parseAndroidManifest(tmp, parserFunction);
        } finally {
            // Best effort: a leftover temp file is a disk leak, not a
            // correctness problem, and File.delete cannot throw.
            tmp.delete();
        }
    }

    /** Binary AndroidManifest.xml bytes -> XmlResourceParser -> caller's
     *  function. Shared by both parseAndroidManifest overloads so they cannot
     *  diverge. */
    private static <T> T applyManifestParser(byte[] axml,
            java.util.function.Function<android.content.res.XmlResourceParser, T> parserFunction)
            throws java.io.IOException {
        org.w3c.dom.Document doc;
        try {
            doc = com.macapk.loader.BinaryXmlParser.parse(axml);
        } catch (Throwable t) {
            throw new java.io.IOException("Cannot parse AndroidManifest.xml", t);
        }
        if (doc == null) throw new java.io.IOException("Empty AndroidManifest.xml");
        stripTypeAnnotations(doc.getDocumentElement());
        byte[] text;
        try {
            javax.xml.transform.Transformer tf =
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            tf.transform(new javax.xml.transform.dom.DOMSource(doc),
                    new javax.xml.transform.stream.StreamResult(bos));
            text = bos.toByteArray();
        } catch (Throwable t) {
            throw new java.io.IOException("Cannot serialize AndroidManifest.xml", t);
        }
        com.macapk.loader.DomXmlResourceParser parser;
        try {
            parser = new com.macapk.loader.DomXmlResourceParser(
                    new java.io.ByteArrayInputStream(text));
        } catch (Throwable t) {
            throw new java.io.IOException("Cannot open AndroidManifest.xml", t);
        }
        try {
            return parserFunction.apply(parser);
        } finally {
            parser.close();
        }
    }

    /** Remove BinaryXmlParser's MACAPK_TYPE_NS annotation attributes so the
     *  caller sees the manifest's own attribute set and nothing else. */
    private static void stripTypeAnnotations(org.w3c.dom.Node node) {
        if (node == null) return;
        if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
            org.w3c.dom.Element el = (org.w3c.dom.Element) node;
            org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
            java.util.ArrayList<org.w3c.dom.Attr> doomed = new java.util.ArrayList<>();
            for (int i = 0; i < attrs.getLength(); i++) {
                org.w3c.dom.Attr a = (org.w3c.dom.Attr) attrs.item(i);
                String ns = a.getNamespaceURI();
                if (com.macapk.loader.BinaryXmlParser.MACAPK_TYPE_NS.equals(ns)) {
                    doomed.add(a);
                } else if (a.getName() != null
                        && a.getName().startsWith("xmlns:")
                        && com.macapk.loader.BinaryXmlParser.MACAPK_TYPE_NS.equals(a.getValue())) {
                    doomed.add(a);
                }
            }
            for (org.w3c.dom.Attr a : doomed) el.removeAttributeNode(a);
        }
        org.w3c.dom.NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) stripTypeAnnotations(kids.item(i));
    }

    /** AOSP PackageManager.getVerifiedSigningInfo(String path, int
     *  minAppSigningSchemeVersion) (API 36): "Verifies and returns the app
     *  signing information of the file at the given path. @throws
     *  SigningInfoException if the verification fails."
     *
     *  ⚠ STATIC on AOSP, and static here -- it takes a PATH, not a package.
     *
     *  Mac.apk has exactly one APK signature VERIFIER, and it is private to
     *  com.macapk.loader.MacApkPackageManager (readApkSignatures, which walks
     *  the APK Signing Block v3.1/v3/v2 and then v1/JAR). Writing a second one
     *  here would give this tree two verifiers that can disagree about the same
     *  file, which is worse than the gap it closes.
     *
     *  So the one file we CAN answer for is the running app's own APK, whose
     *  verified SigningInfo the concrete PackageManager already holds; the
     *  self-check ("is this APK on disk the one I am running, and who signed
     *  it") is also the common real use. Every other path gets the
     *  SigningInfoException the API documents for a verification we did not
     *  perform -- never a SigningInfo we did not verify.
     *
     *  REMOVAL CONDITION: expose the loader's signature reader (and the scheme
     *  version it matched) and verify any path here, honouring
     *  minAppSigningSchemeVersion properly. That change also lets
     *  SigningInfo.getSchemeVersion() stop answering 0.
     *
     *  The error code is AOSP's INSTALL_PARSE_FAILED_NO_CERTIFICATES (-103),
     *  the code its verifier reports when it cannot establish a signer. */
    public static SigningInfo getVerifiedSigningInfo(String path, int minAppSigningSchemeVersion)
            throws SigningInfoException {
        final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;
        if (path == null) {
            throw new SigningInfoException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "null path", null);
        }
        try {
            android.app.Application app = android.app.Application.macapk_get();
            ApplicationInfo self = (app != null) ? app.getApplicationInfo() : null;
            String selfApk = (self != null) ? self.sourceDir : null;
            if (selfApk != null
                    && new java.io.File(selfApk).getCanonicalFile()
                        .equals(new java.io.File(path).getCanonicalFile())) {
                PackageInfo info = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), GET_SIGNING_CERTIFICATES);
                if (info != null && info.signingInfo != null
                        && info.signingInfo.getApkContentsSigners().length > 0) {
                    return info.signingInfo;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the documented failure.
        }
        throw new SigningInfoException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                "Signature verification is not available for " + path, null);
    }

    // ---- API 33 Flags boxes ----------------------------------------------
    // Shape copied verbatim from AOSP PackageManager.java: a base `Flags` with a
    // package-private final long, a protected ctor and a public getValue(), plus
    // four FINAL subclasses whose only entry point is a static of(long) factory
    // (the ctors are private upstream, so `new PackageInfoFlags(0)` must not
    // compile -- keep them private here too). App bytecode calls
    // PackageInfoFlags.of(J) and hands the result to the overloads above, so the
    // class names, the finality, the factory name and the return types are all
    // part of the contract.
    public static class Flags {
        final long mValue;
        protected Flags(long value) {
            mValue = value;
        }
        public long getValue() {
            return mValue;
        }
    }

    public final static class PackageInfoFlags extends Flags {
        private PackageInfoFlags(long value) {
            super(value);
        }
        public static PackageInfoFlags of(long value) {
            return new PackageInfoFlags(value);
        }
    }

    public final static class ApplicationInfoFlags extends Flags {
        private ApplicationInfoFlags(long value) {
            super(value);
        }
        public static ApplicationInfoFlags of(long value) {
            return new ApplicationInfoFlags(value);
        }
    }

    public final static class ComponentInfoFlags extends Flags {
        private ComponentInfoFlags(long value) {
            super(value);
        }
        public static ComponentInfoFlags of(long value) {
            return new ComponentInfoFlags(value);
        }
    }

    public final static class ResolveInfoFlags extends Flags {
        private ResolveInfoFlags(long value) {
            super(value);
        }
        public static ResolveInfoFlags of(long value) {
            return new ResolveInfoFlags(value);
        }
    }

    /** Callback for requestChecksums. Source: frameworks/base .../PackageManager.java */
    public interface OnChecksumsReadyListener {
        void onChecksumsReady(java.util.List<ApkChecksum> checksums);
    }

    /** AOSP PackageManager.getProperty (API 31): reads a manifest &lt;property&gt;
     *  declaration. Mac.apk does not model manifest properties, so the honest
     *  answer is "not declared" -- NameNotFoundException, which apps catch to
     *  fall back to their default (the same as a real manifest without it). */
    public Property getProperty(String propertyName, String packageName)
            throws NameNotFoundException {
        throw new NameNotFoundException("Property " + propertyName
                + " not defined for package " + packageName);
    }

    /** A manifest &lt;property&gt; value (one of boolean/int/float/String).
     *  Source: frameworks/base/core/java/android/content/pm/PackageManager.java */
    public static final class Property implements android.os.Parcelable {
        private final String mName;
        private final String mClassName;
        private final String mPackageName;
        private final int mType;          // 1=boolean 2=float 3=int 4=string
        private final boolean mBoolean;
        private final float mFloat;
        private final int mInteger;
        private final String mString;
        // AOSP's own values (PackageManager.java:342-346, android16-release).
        // ⚠ TYPE_STRING is 5, not 4 -- 4 is TYPE_RESOURCE, which this class did
        // not model. The numbers are private on both sides so nothing outside
        // can see them, but writeToParcel puts mType on the wire and CREATOR
        // reads it back, so using AOSP's numbering keeps our own parcels
        // readable by anyone who assumed the upstream constants.
        static final int TYPE_BOOLEAN = 1, TYPE_FLOAT = 2, TYPE_INTEGER = 3,
                         TYPE_RESOURCE = 4, TYPE_STRING = 5;
        Property(String name, boolean value, String packageName, String className) {
            mName = name; mType = TYPE_BOOLEAN; mBoolean = value; mFloat = 0; mInteger = 0;
            mString = null; mPackageName = packageName; mClassName = className;
        }
        /** AOSP's int constructor carries an `isResource` discriminator, because
         *  `android:value="1"` and `android:resource="@id/x"` are both ints on
         *  the wire and only the flag tells them apart. */
        Property(String name, int value, boolean isResource, String packageName,
                String className) {
            mName = name; mType = isResource ? TYPE_RESOURCE : TYPE_INTEGER;
            mInteger = value; mBoolean = false; mFloat = 0;
            mString = null; mPackageName = packageName; mClassName = className;
        }
        Property(String name, int value, String packageName, String className) {
            this(name, value, false, packageName, className);
        }
        Property(String name, float value, String packageName, String className) {
            mName = name; mType = TYPE_FLOAT; mFloat = value; mBoolean = false; mInteger = 0;
            mString = null; mPackageName = packageName; mClassName = className;
        }
        Property(String name, String value, String packageName, String className) {
            mName = name; mType = TYPE_STRING; mString = value; mBoolean = false; mFloat = 0;
            mInteger = 0; mPackageName = packageName; mClassName = className;
        }
        public String getName() { return mName; }
        public String getClassName() { return mClassName; }
        public String getPackageName() { return mPackageName; }
        public boolean isBoolean() { return mType == TYPE_BOOLEAN; }
        public boolean isFloat()   { return mType == TYPE_FLOAT; }
        public boolean isInteger() { return mType == TYPE_INTEGER; }
        public boolean isString()  { return mType == TYPE_STRING; }
        /** AOSP Property.isResourceId() (API 31): whether this property was
         *  declared with `android:resource` rather than `android:value`. */
        public boolean isResourceId() { return mType == TYPE_RESOURCE; }
        /** AOSP Property.getResourceId(): "the resource id set for the
         *  property. If the property is not of a resource id type, returns
         *  0." */
        public int getResourceId() { return mType == TYPE_RESOURCE ? mInteger : 0; }
        public boolean getBoolean() { return mBoolean; }
        public float   getFloat()   { return mFloat; }
        /** AOSP Property.getInteger():456-458 -- "If the property is not of an
         *  integer type, returns 0", and upstream really does guard on the
         *  type here (unlike getBoolean/getFloat, whose other-typed fields are
         *  already zero). Without the guard a TYPE_RESOURCE property answered
         *  its RESOURCE ID from getInteger(), so a caller that checked
         *  isInteger() (false) and one that just read getInteger() (non-zero)
         *  disagreed about the same object. */
        public int     getInteger() { return mType == TYPE_INTEGER ? mInteger : 0; }
        public String  getString()  { return mString; }

        // AOSP field order: name, type, packageName, className, then the
        // discriminated value (PackageManager.java:526-544). TYPE_RESOURCE is
        // written and read as an int, exactly as TYPE_INTEGER is -- the TYPE
        // tag is what distinguishes them.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeString(mName);
            dest.writeInt(mType);
            dest.writeString(mPackageName);
            dest.writeString(mClassName);
            if (mType == TYPE_BOOLEAN) dest.writeBoolean(mBoolean);
            else if (mType == TYPE_FLOAT) dest.writeFloat(mFloat);
            else if (mType == TYPE_INTEGER) dest.writeInt(mInteger);
            else if (mType == TYPE_RESOURCE) dest.writeInt(mInteger);
            else if (mType == TYPE_STRING) dest.writeString(mString);
        }

        public static final android.os.Parcelable.Creator<Property> CREATOR =
                new android.os.Parcelable.Creator<Property>() {
            @Override public Property createFromParcel(android.os.Parcel source) {
                String name = source.readString();
                int type = source.readInt();
                String packageName = source.readString();
                String className = source.readString();
                if (type == TYPE_BOOLEAN) {
                    return new Property(name, source.readBoolean(), packageName, className);
                } else if (type == TYPE_FLOAT) {
                    return new Property(name, source.readFloat(), packageName, className);
                } else if (type == TYPE_INTEGER) {
                    return new Property(name, source.readInt(), false, packageName, className);
                } else if (type == TYPE_RESOURCE) {
                    return new Property(name, source.readInt(), true, packageName, className);
                } else {
                    return new Property(name, source.readString(), packageName, className);
                }
            }
            @Override public Property[] newArray(int size) { return new Property[size]; }
        };
    }

    /** AOSP queryIntentActivityOptions (API 1): activities matching a set of
     *  specific intents plus a general intent, for a launcher-style chooser.
     *  Mac.apk resolves only the app's own declared activities, so this reports
     *  none for the cross-app option query. */
    public java.util.List<ResolveInfo> queryIntentActivityOptions(
            android.content.ComponentName caller, android.content.Intent[] specifics,
            android.content.Intent intent, int flags) {
        return java.util.Collections.emptyList();
    }


    /** AOSP getInstallSourceInfo (API 30): who installed the package. Mac.apk
     *  side-loads, so there is no installer chain. */
    public android.content.pm.InstallSourceInfo getInstallSourceInfo(String packageName)
            throws NameNotFoundException {
        return new android.content.pm.InstallSourceInfo(null, null, null, null);
    }

    /** AOSP requestChecksums (API 31): compute checksums for a package's apk.
     *  Mac.apk has no installed-package checksum service; report none. */
    public void requestChecksums(String packageName, boolean includeSplits, int required,
            java.util.List<java.security.cert.Certificate> trustedInstallers,
            OnChecksumsReadyListener onChecksumsReadyListener) {
        try { onChecksumsReadyListener.onChecksumsReady(java.util.Collections.emptyList()); } catch (Throwable ignored) {}
    }

    public boolean canRequestPackageInstalls() { return false; }
    public boolean isAutoRevokeWhitelisted() { return false; }
    /** AOSP getActivityIcon(ComponentName): the activity's own icon, falling
     *  back through the application icon to the default -- @NonNull, same
     *  contract as getApplicationIcon above. */
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName activityName) throws NameNotFoundException {
        android.content.pm.ActivityInfo ai = getActivityInfo(activityName, 0);
        if (ai != null) {
            if (ai.icon != 0) {
                android.graphics.drawable.Drawable d =
                    getDrawable(ai.packageName, ai.icon, ai.applicationInfo);
                if (d != null) return d;
            }
            if (ai.applicationInfo != null) return getApplicationIcon(ai.applicationInfo);
        }
        return getDefaultActivityIcon();
    }
    /** AOSP getDrawable(String, int, ApplicationInfo): the drawable resource
     *  `resid` out of the named package's Resources, null when unloadable.
     *  Was a hardcoded null, which made every icon path above degenerate to the
     *  default even for an app that declares `android:icon` and ships it. */
    public android.graphics.drawable.Drawable getDrawable(String packageName, int resid, android.content.pm.ApplicationInfo appInfo) {
        if (resid == 0) return null;
        try {
            android.content.res.Resources r = (appInfo != null)
                ? getResourcesForApplication(appInfo)
                : getResourcesForApplication(packageName);
            return (r != null) ? r.getDrawable(resid) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName className, int flags) throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(className));
    }
    /** AOSP ApplicationPackageManager.getLeanbackLaunchIntentForPackage
     *  (android14): getLaunchIntentForPackageAndCategory(pkg,
     *  CATEGORY_LEANBACK_LAUNCHER) -- the package's MAIN + LEANBACK_LAUNCHER
     *  activity as a NEW_TASK intent carrying that category, or null when it
     *  has none. It returned getLaunchIntentForPackage, so a phone app read as
     *  having a TV entry and a TV app got its LAUNCHER intent instead. */
    public android.content.Intent getLeanbackLaunchIntentForPackage(String packageName) {
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);
        if (ris == null || ris.isEmpty()) return null;
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.content.pm.ActivityInfo target = ris.get(0).activityInfo;
        if (target != null && target.name != null) {
            intent.setClassName(
                target.packageName != null ? target.packageName : packageName,
                target.name);
        }
        return intent;
    }
    public void setInstallerPackageName(String targetPackage, String installerPackageName) { }
    public void setComponentEnabledSettings(java.util.List<ComponentEnabledSetting> settings) { }
    /** AOSP API-33 batched component-enabled change. */
    public static final class ComponentEnabledSetting implements android.os.Parcelable {
        private final android.content.ComponentName mComponentName;
        private final int mState, mFlags;
        public ComponentEnabledSetting(android.content.ComponentName componentName, int newState, int flags) {
            mComponentName = componentName; mState = newState; mFlags = flags;
        }
        public android.content.ComponentName getComponentName() { return mComponentName; }
        public int getEnabledState() { return mState; }
        public int getEnabledFlags() { return mFlags; }

        // AOSP also has a package-level constructor/mPackageName field (an
        // application-wide enabled-setting update, @hide) this class does
        // not model -- only the public componentName-level constructor is
        // implemented here. Field order otherwise follows
        // PackageManager.java L745-758: componentName, enabledState,
        // enabledFlags.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeTypedObject(mComponentName, flags);
            dest.writeInt(mState);
            dest.writeInt(mFlags);
        }

        public static final android.os.Parcelable.Creator<ComponentEnabledSetting> CREATOR =
                new android.os.Parcelable.Creator<ComponentEnabledSetting>() {
            @Override public ComponentEnabledSetting createFromParcel(android.os.Parcel in) {
                android.content.ComponentName cn =
                        in.readTypedObject(android.content.ComponentName.CREATOR);
                int state = in.readInt();
                int flags = in.readInt();
                return new ComponentEnabledSetting(cn, state, flags);
            }
            @Override public ComponentEnabledSetting[] newArray(int size) {
                return new ComponentEnabledSetting[size];
            }
        };
    }
}




