// android.content.Intent -- the universal action+target message. v2
// only models enough of it for apps to construct, set extras, and pass
// to startActivity (where we currently no-op). The action constants are
// real Android values; apps test against them.
package android.content;

import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

// AOSP: `public class Intent implements Parcelable, Cloneable`
// (frameworks/base/core/java/android/content/Intent.java:642). Cloneable is a
// marker, and our clone() below is already AOSP's `return new Intent(this)`, so
// the override never consulted Object.clone() and nothing threw -- but
// `instanceof Cloneable` answered false. Intent is referenced by 51 of the 62
// corpus APKs.
public class Intent implements Parcelable, Cloneable {
    public static final String ACTION_MAIN          = "android.intent.action.MAIN";
    public static final String ACTION_VIEW          = "android.intent.action.VIEW";
    public static final String ACTION_SEND          = "android.intent.action.SEND";
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    public static final String ACTION_SENDTO        = "android.intent.action.SENDTO";
    public static final String ACTION_CHOOSER       = "android.intent.action.CHOOSER";
    public static final String ACTION_GET_CONTENT   = "android.intent.action.GET_CONTENT";
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    // v1.0.940: twenty more standard actions, every one of them previously
    // ABSENT. These are the same failure class as the EXTRA_TITLE bug called out
    // in the comment below -- a reference to a missing static String is a
    // NoSuchFieldError that aborts the CALLER, not a graceful "unsupported", and
    // it happens even on a branch that would have gone on to find no handler.
    // Values are transcribed from android11-release
    // core/java/android/content/Intent.java (verified against the file, not
    // recalled), and having the CONSTANT costs nothing whether or not anything
    // ever resolves the action -- ActivityHost decides that separately.
    //
    // ⚠ ActivityHost:471 still compares the literal "android.intent.action.DIAL"
    // rather than ACTION_DIAL. That is not a bug (identical value, and String
    // literals intern) and it is another lane's file, so it is left alone --
    // noted here so the next reader does not take the literal as evidence the
    // constant is missing.
    public static final String ACTION_PICK          = "android.intent.action.PICK";
    public static final String ACTION_PICK_ACTIVITY = "android.intent.action.PICK_ACTIVITY";
    public static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT";
    public static final String ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT";
    public static final String ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE";
    public static final String ACTION_INSERT        = "android.intent.action.INSERT";
    public static final String ACTION_EDIT          = "android.intent.action.EDIT";
    public static final String ACTION_DELETE        = "android.intent.action.DELETE";
    public static final String ACTION_ATTACH_DATA   = "android.intent.action.ATTACH_DATA";
    public static final String ACTION_BUG_REPORT    = "android.intent.action.BUG_REPORT";
    public static final String ACTION_DIAL          = "android.intent.action.DIAL";
    public static final String ACTION_CALL_BUTTON   = "android.intent.action.CALL_BUTTON";
    public static final String ACTION_SEARCH        = "android.intent.action.SEARCH";
    public static final String ACTION_WEB_SEARCH    = "android.intent.action.WEB_SEARCH";
    public static final String ACTION_SET_WALLPAPER = "android.intent.action.SET_WALLPAPER";
    public static final String ACTION_SYNC          = "android.intent.action.SYNC";
    public static final String ACTION_INSTALL_PACKAGE   = "android.intent.action.INSTALL_PACKAGE";
    public static final String ACTION_UNINSTALL_PACKAGE = "android.intent.action.UNINSTALL_PACKAGE";
    public static final String ACTION_APPLICATION_PREFERENCES = "android.intent.action.APPLICATION_PREFERENCES";
    public static final String ACTION_SHOW_APP_INFO  = "android.intent.action.SHOW_APP_INFO";

    // ---- package lifecycle broadcasts (v1.0.997) -------------------------
    //
    // Values verbatim from android14-release
    // core/java/android/content/Intent.java. These are the SECOND route by
    // which an app learns an install finished; the first is the
    // PackageInstaller session's IntentSender status. A device gives a store
    // both, and a store is free to use either -- measured on the two we have:
    //   Aurora Store 4.8.4 registers a DYNAMIC receiver in AuroraApp with
    //     IntentFilter{ addDataScheme("package"); addAction(PACKAGE_INSTALL);
    //     addAction(PACKAGE_ADDED); addAction(PACKAGE_REMOVED) } and reads
    //     getData().getEncodedSchemeSpecificPart() for the package name;
    //   F-Droid 1.23.2 uses NO package broadcast at all and polls
    //     PackageManager.getInstalledPackages / getPackageInfo instead.
    // Two witnesses, two different routes, which is exactly why the contract
    // has to be the device's rather than either app's. See
    // PackageInstallerBackend.broadcastPackageInstalled.
    //
    // ⚠ The DATA URI is load-bearing, not decoration: AOSP builds these as
    // `new Intent(action, Uri.fromParts("package", pkg, null))`
    // (services/core/java/com/android/server/pm/BroadcastHelper.java:125), and
    // a receiver whose filter declares addDataScheme("package") does NOT match
    // an action-only intent. Sending one of these without the package: data
    // silently reaches nobody.
    public static final String ACTION_PACKAGE_ADDED   = "android.intent.action.PACKAGE_ADDED";
    public static final String ACTION_PACKAGE_REPLACED = "android.intent.action.PACKAGE_REPLACED";
    public static final String ACTION_PACKAGE_REMOVED = "android.intent.action.PACKAGE_REMOVED";
    public static final String ACTION_PACKAGE_FULLY_REMOVED = "android.intent.action.PACKAGE_FULLY_REMOVED";
    public static final String ACTION_PACKAGE_CHANGED = "android.intent.action.PACKAGE_CHANGED";
    public static final String ACTION_PACKAGE_RESTARTED = "android.intent.action.PACKAGE_RESTARTED";
    public static final String ACTION_PACKAGE_DATA_CLEARED = "android.intent.action.PACKAGE_DATA_CLEARED";
    public static final String ACTION_PACKAGE_FIRST_LAUNCH = "android.intent.action.PACKAGE_FIRST_LAUNCH";
    /** AOSP: sent only to the package that was just replaced, with NO data
     *  Uri (BroadcastHelper's targetPkg form). Aurora's MigrationReceiver and
     *  many apps declare it in the MANIFEST, which is why manifest dispatch of
     *  component-less broadcasts matters -- see Context.sendBroadcast. */
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    /** Deprecated upstream and never sent by AOSP, but Aurora Store lists it
     *  in the filter above, so the constant must exist for code that names
     *  it. Declaring it costs nothing; omitting it is a NoSuchFieldError. */
    public static final String ACTION_PACKAGE_INSTALL = "android.intent.action.PACKAGE_INSTALL";

    public static final String CATEGORY_LAUNCHER    = "android.intent.category.LAUNCHER";
    public static final String CATEGORY_DEFAULT     = "android.intent.category.DEFAULT";
    public static final String CATEGORY_OPENABLE    = "android.intent.category.OPENABLE";
    // v1.0.940: the rest of the standard categories, all previously ABSENT.
    // Same failure class as the twenty ACTION_* constants added just above and
    // as the EXTRA_TITLE bug noted below: a reference to a missing static String
    // is a NoSuchFieldError that aborts the CALLER. CATEGORY_BROWSABLE is the
    // one that matters most in practice -- every app that builds a web-link
    // Intent names it, and it is also what an <intent-filter> declares for a
    // deep link.
    // Values transcribed from android11-release core/java/android/content/
    // Intent.java (read from the file, not recalled).
    public static final String CATEGORY_BROWSABLE              = "android.intent.category.BROWSABLE";
    public static final String CATEGORY_ALTERNATIVE            = "android.intent.category.ALTERNATIVE";
    public static final String CATEGORY_SELECTED_ALTERNATIVE   = "android.intent.category.SELECTED_ALTERNATIVE";
    public static final String CATEGORY_TAB                    = "android.intent.category.TAB";
    public static final String CATEGORY_INFO                   = "android.intent.category.INFO";
    public static final String CATEGORY_HOME                   = "android.intent.category.HOME";
    public static final String CATEGORY_PREFERENCE             = "android.intent.category.PREFERENCE";
    public static final String CATEGORY_TEST                   = "android.intent.category.TEST";
    public static final String CATEGORY_MONKEY                 = "android.intent.category.MONKEY";
    public static final String CATEGORY_UNIT_TEST              = "android.intent.category.UNIT_TEST";
    public static final String CATEGORY_SAMPLE_CODE            = "android.intent.category.SAMPLE_CODE";
    public static final String CATEGORY_EMBED                  = "android.intent.category.EMBED";
    public static final String CATEGORY_APP_BROWSER            = "android.intent.category.APP_BROWSER";
    public static final String CATEGORY_APP_CALCULATOR         = "android.intent.category.APP_CALCULATOR";
    public static final String CATEGORY_APP_CALENDAR           = "android.intent.category.APP_CALENDAR";
    public static final String CATEGORY_APP_CONTACTS           = "android.intent.category.APP_CONTACTS";
    public static final String CATEGORY_APP_EMAIL              = "android.intent.category.APP_EMAIL";
    public static final String CATEGORY_APP_GALLERY            = "android.intent.category.APP_GALLERY";
    public static final String CATEGORY_APP_MAPS               = "android.intent.category.APP_MAPS";
    public static final String CATEGORY_APP_MESSAGING          = "android.intent.category.APP_MESSAGING";
    public static final String CATEGORY_APP_MUSIC              = "android.intent.category.APP_MUSIC";
    public static final String CATEGORY_VOICE                  = "android.intent.category.VOICE";
    public static final String CATEGORY_CAR_MODE               = "android.intent.category.CAR_MODE";
    public static final String CATEGORY_LEANBACK_LAUNCHER      = "android.intent.category.LEANBACK_LAUNCHER";

    // Standard "extra" keys (real AOSP values --
    // frameworks/base/core/java/android/content/Intent.java). Apps + Unity
    // plugins read these constants by name via JNI (e.g. a share/chooser
    // flow does AndroidJavaClass("android.content.Intent").GetStatic<string>
    // ("EXTRA_TITLE")); a missing field throws NoSuchFieldError and aborts
    // the caller (Subway Surfers' SYBO bootstrapper stalled the whole boot
    // on the absent EXTRA_TITLE). They are plain string keys, used
    // consistently as putExtra/getExtra keys within our framework.
    public static final String EXTRA_TEXT           = "android.intent.extra.TEXT";
    public static final String EXTRA_TITLE          = "android.intent.extra.TITLE";
    public static final String EXTRA_SUBJECT        = "android.intent.extra.SUBJECT";
    public static final String EXTRA_STREAM         = "android.intent.extra.STREAM";
    public static final String EXTRA_HTML_TEXT      = "android.intent.extra.HTML_TEXT";
    public static final String EXTRA_EMAIL          = "android.intent.extra.EMAIL";
    public static final String EXTRA_CC             = "android.intent.extra.CC";
    public static final String EXTRA_BCC            = "android.intent.extra.BCC";
    public static final String EXTRA_INTENT         = "android.intent.extra.INTENT";
    public static final String EXTRA_TEMPLATE       = "android.intent.extra.TEMPLATE";
    public static final String EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS";
    public static final String EXTRA_MIME_TYPES     = "android.intent.extra.MIME_TYPES";
    public static final String EXTRA_REFERRER       = "android.intent.extra.REFERRER";
    public static final String EXTRA_REFERRER_NAME  = "android.intent.extra.REFERRER_NAME";
    public static final String EXTRA_CHOSEN_COMPONENT = "android.intent.extra.CHOSEN_COMPONENT";
    public static final String EXTRA_RETURN_RESULT  = "android.intent.extra.RETURN_RESULT";
    // v1.0.953: the other two ACTION_INSTALL_PACKAGE companions, verbatim from
    // android14-release core/java/android/content/Intent.java. INSTALL_RESULT
    // rides in the installer's result Intent (the legacy INSTALL_* code);
    // NOT_UNKNOWN_SOURCE is what a store sets to say "this install came from
    // me, not an unknown source" (F-Droid sets both on its install intent).
    public static final String EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT";
    public static final String EXTRA_NOT_UNKNOWN_SOURCE = "android.intent.extra.NOT_UNKNOWN_SOURCE";
    public static final String EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE";
    public static final String EXTRA_LOCAL_ONLY     = "android.intent.extra.LOCAL_ONLY";
    public static final String EXTRA_UID            = "android.intent.extra.UID";
    public static final String EXTRA_PACKAGE_NAME   = "android.intent.extra.PACKAGE_NAME";
    // Companions of the package-lifecycle broadcasts above, verbatim from
    // android14-release core/java/android/content/Intent.java. EXTRA_REPLACING
    // is the bit that tells a PACKAGE_ADDED receiver "this is an UPDATE, the
    // old copy is gone"; AOSP sets it in InstallPackageHelper.java:2882 and
    // stores branch on it. EXTRA_USER_HANDLE rides on every one of them
    // (BroadcastHelper.java:144).
    public static final String EXTRA_REPLACING      = "android.intent.extra.REPLACING";
    public static final String EXTRA_USER_HANDLE    = "android.intent.extra.user_handle";
    public static final String EXTRA_DATA_REMOVED   = "android.intent.extra.DATA_REMOVED";
    public static final String EXTRA_CHANGED_PACKAGE_LIST = "android.intent.extra.changed_package_list";
    public static final String EXTRA_CHANGED_UID_LIST = "android.intent.extra.changed_uid_list";

    public static final int FLAG_ACTIVITY_NEW_TASK    = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP   = 0x04000000;

    // ---- The rest of the API-36 public constant surface of this class, as
    // declared in AOSP core/java/android/content/Intent.java
    // (android16-release). Values transcribed from that file and cross-checked
    // against core/api/current.txt.
    //
    // These are compile-time constants, so an app compiled against the real
    // android.jar has them INLINED and cannot observe one missing at link
    // time. Three things can, and all three are common:
    //   * reflection -- Intent.class.getField("ACTION_MAIN"), which
    //     analytics/router/deep-link libraries use to enumerate actions;
    //   * our OWN framework code, which must compare against the same
    //     literals an app inlined;
    //   * an app that ships a manifest naming an action and then reads the
    //     constant to match it.
    // Adding them costs nothing at runtime and closes that gap.
    public static final String EXTRA_CHANGED_COMPONENT_NAME =
            "android.intent.extra.changed_component_name";
    public static final String EXTRA_FROM_STORAGE = "android.intent.extra.FROM_STORAGE";
    public static final String ACTION_DEFAULT = ACTION_VIEW;
    public static final String ACTION_QUICK_VIEW = "android.intent.action.QUICK_VIEW";
    public static final String ACTION_INSERT_OR_EDIT = "android.intent.action.INSERT_OR_EDIT";
    public static final String ACTION_CREATE_REMINDER = "android.intent.action.CREATE_REMINDER";
    public static final String ACTION_CREATE_SHORTCUT = "android.intent.action.CREATE_SHORTCUT";
    public static final String EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT";
    public static final String EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME";
    public static final String EXTRA_SHORTCUT_ICON = "android.intent.extra.shortcut.ICON";
    public static final String EXTRA_SHORTCUT_ICON_RESOURCE = "android.intent.extra.shortcut.ICON_RESOURCE";
    public static final String ACTION_CALL = "android.intent.action.CALL";
    public static final String ACTION_CARRIER_SETUP = "android.intent.action.CARRIER_SETUP";
    public static final String ACTION_ANSWER = "android.intent.action.ANSWER";
    public static final String ACTION_PASTE = "android.intent.action.PASTE";
    public static final String ACTION_RUN = "android.intent.action.RUN";
    public static final String ACTION_SYSTEM_TUTORIAL = "android.intent.action.SYSTEM_TUTORIAL";
    public static final String ACTION_ASSIST = "android.intent.action.ASSIST";
    public static final String EXTRA_ASSIST_PACKAGE = "android.intent.extra.ASSIST_PACKAGE";
    public static final String EXTRA_ASSIST_UID = "android.intent.extra.ASSIST_UID";
    public static final String EXTRA_ASSIST_CONTEXT = "android.intent.extra.ASSIST_CONTEXT";
    public static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD = "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";
    public static final String EXTRA_ASSIST_INPUT_DEVICE_ID = "android.intent.extra.ASSIST_INPUT_DEVICE_ID";
    public static final String ACTION_ALL_APPS = "android.intent.action.ALL_APPS";
    public static final String ACTION_SHOW_WORK_APPS = "android.intent.action.SHOW_WORK_APPS";
    public static final String ACTION_FACTORY_TEST = "android.intent.action.FACTORY_TEST";
    public static final String ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND";
    public static final String ACTION_SEARCH_LONG_PRESS = "android.intent.action.SEARCH_LONG_PRESS";
    public static final String ACTION_APP_ERROR = "android.intent.action.APP_ERROR";
    public static final String ACTION_POWER_USAGE_SUMMARY = "android.intent.action.POWER_USAGE_SUMMARY";
    public static final String ACTION_MANAGE_NETWORK_USAGE = "android.intent.action.MANAGE_NETWORK_USAGE";
    public static final String ACTION_INSTALL_FAILURE = "android.intent.action.INSTALL_FAILURE";
    public static final String EXTRA_INSTALLER_PACKAGE_NAME = "android.intent.extra.INSTALLER_PACKAGE_NAME";
    public static final String EXTRA_ORIGINATING_URI = "android.intent.extra.ORIGINATING_URI";
    public static final String EXTRA_ALLOW_REPLACE = "android.intent.extra.ALLOW_REPLACE";
    public static final String ACTION_AUTO_REVOKE_PERMISSIONS = "android.intent.action.AUTO_REVOKE_PERMISSIONS";
    public static final String ACTION_MANAGE_UNUSED_APPS = "android.intent.action.MANAGE_UNUSED_APPS";
    public static final String ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE";
    public static final String ACTION_VIEW_PERMISSION_USAGE_FOR_PERIOD = "android.intent.action.VIEW_PERMISSION_USAGE_FOR_PERIOD";
    public static final String EXTRA_LOCALE_LIST = "android.intent.extra.LOCALE_LIST";
    public static final String EXTRA_SUSPENDED_PACKAGE_EXTRAS = "android.intent.extra.SUSPENDED_PACKAGE_EXTRAS";
    public static final String EXTRA_SPLIT_NAME = "android.intent.extra.SPLIT_NAME";
    public static final String EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME";
    public static final String EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID";
    public static final String EXTRA_PERMISSION_GROUP_NAME = "android.intent.extra.PERMISSION_GROUP_NAME";
    public static final String EXTRA_DURATION_MILLIS = "android.intent.extra.DURATION_MILLIS";
    public static final String ACTION_SAFETY_CENTER = "android.intent.action.SAFETY_CENTER";
    public static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    public static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";
    public static final String ACTION_DREAMING_STOPPED = "android.intent.action.DREAMING_STOPPED";
    public static final String ACTION_DREAMING_STARTED = "android.intent.action.DREAMING_STARTED";
    public static final String ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT";
    public static final String ACTION_TIME_TICK = "android.intent.action.TIME_TICK";
    public static final String ACTION_TIME_CHANGED = "android.intent.action.TIME_SET";
    public static final String ACTION_DATE_CHANGED = "android.intent.action.DATE_CHANGED";
    public static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
    public static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    public static final String ACTION_CLOSE_SYSTEM_DIALOGS = "android.intent.action.CLOSE_SYSTEM_DIALOGS";
    public static final String ACTION_PACKAGE_UNSTOPPED = "android.intent.action.PACKAGE_UNSTOPPED";
    public static final String ACTION_PACKAGES_SUSPENDED = "android.intent.action.PACKAGES_SUSPENDED";
    public static final String ACTION_PACKAGES_UNSUSPENDED = "android.intent.action.PACKAGES_UNSUSPENDED";
    public static final String ACTION_MY_PACKAGE_SUSPENDED = "android.intent.action.MY_PACKAGE_SUSPENDED";
    public static final String ACTION_MY_PACKAGE_UNSUSPENDED = "android.intent.action.MY_PACKAGE_UNSUSPENDED";
    public static final String ACTION_UID_REMOVED = "android.intent.action.UID_REMOVED";
    public static final String ACTION_PACKAGE_NEEDS_VERIFICATION = "android.intent.action.PACKAGE_NEEDS_VERIFICATION";
    public static final String ACTION_PACKAGE_VERIFIED = "android.intent.action.PACKAGE_VERIFIED";
    public static final String ACTION_EXTERNAL_APPLICATIONS_AVAILABLE = "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE";
    public static final String ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE = "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE";
    public static final String ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED";
    public static final String ACTION_CONFIGURATION_CHANGED = "android.intent.action.CONFIGURATION_CHANGED";
    public static final String ACTION_LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED";
    public static final String ACTION_APPLICATION_LOCALE_CHANGED = "android.intent.action.APPLICATION_LOCALE_CHANGED";
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";
    public static final String ACTION_BATTERY_LOW = "android.intent.action.BATTERY_LOW";
    public static final String ACTION_BATTERY_OKAY = "android.intent.action.BATTERY_OKAY";
    public static final String ACTION_POWER_CONNECTED = "android.intent.action.ACTION_POWER_CONNECTED";
    public static final String ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED";
    public static final String ACTION_SHUTDOWN = "android.intent.action.ACTION_SHUTDOWN";
    public static final String ACTION_DEVICE_STORAGE_LOW = "android.intent.action.DEVICE_STORAGE_LOW";
    public static final String ACTION_DEVICE_STORAGE_OK = "android.intent.action.DEVICE_STORAGE_OK";
    public static final String ACTION_MANAGE_PACKAGE_STORAGE = "android.intent.action.MANAGE_PACKAGE_STORAGE";
    public static final String ACTION_UMS_CONNECTED = "android.intent.action.UMS_CONNECTED";
    public static final String ACTION_UMS_DISCONNECTED = "android.intent.action.UMS_DISCONNECTED";
    public static final String ACTION_MEDIA_REMOVED = "android.intent.action.MEDIA_REMOVED";
    public static final String ACTION_MEDIA_UNMOUNTED = "android.intent.action.MEDIA_UNMOUNTED";
    public static final String ACTION_MEDIA_CHECKING = "android.intent.action.MEDIA_CHECKING";
    public static final String ACTION_MEDIA_NOFS = "android.intent.action.MEDIA_NOFS";
    public static final String ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";
    public static final String ACTION_MEDIA_SHARED = "android.intent.action.MEDIA_SHARED";
    public static final String ACTION_MEDIA_BAD_REMOVAL = "android.intent.action.MEDIA_BAD_REMOVAL";
    public static final String ACTION_MEDIA_UNMOUNTABLE = "android.intent.action.MEDIA_UNMOUNTABLE";
    public static final String ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT";
    public static final String ACTION_MEDIA_SCANNER_STARTED = "android.intent.action.MEDIA_SCANNER_STARTED";
    public static final String ACTION_MEDIA_SCANNER_FINISHED = "android.intent.action.MEDIA_SCANNER_FINISHED";
    public static final String ACTION_MEDIA_SCANNER_SCAN_FILE = "android.intent.action.MEDIA_SCANNER_SCAN_FILE";
    public static final String ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON";
    public static final String ACTION_CAMERA_BUTTON = "android.intent.action.CAMERA_BUTTON";
    public static final String ACTION_GTALK_SERVICE_CONNECTED = "android.intent.action.GTALK_CONNECTED";
    public static final String ACTION_GTALK_SERVICE_DISCONNECTED = "android.intent.action.GTALK_DISCONNECTED";
    public static final String ACTION_INPUT_METHOD_CHANGED = "android.intent.action.INPUT_METHOD_CHANGED";
    public static final String ACTION_AIRPLANE_MODE_CHANGED = "android.intent.action.AIRPLANE_MODE";
    public static final String ACTION_PROVIDER_CHANGED = "android.intent.action.PROVIDER_CHANGED";
    public static final String ACTION_HEADSET_PLUG = android.media.AudioManager.ACTION_HEADSET_PLUG;
    public static final String ACTION_APPLICATION_RESTRICTIONS_CHANGED = "android.intent.action.APPLICATION_RESTRICTIONS_CHANGED";
    public static final String ACTION_NEW_OUTGOING_CALL = "android.intent.action.NEW_OUTGOING_CALL";
    public static final String ACTION_REBOOT = "android.intent.action.REBOOT";
    public static final String ACTION_DOCK_EVENT = "android.intent.action.DOCK_EVENT";
    public static final String ACTION_GET_RESTRICTION_ENTRIES = "android.intent.action.GET_RESTRICTION_ENTRIES";
    public static final String ACTION_USER_INITIALIZE = "android.intent.action.USER_INITIALIZE";
    public static final String ACTION_USER_FOREGROUND = "android.intent.action.USER_FOREGROUND";
    public static final String ACTION_USER_BACKGROUND = "android.intent.action.USER_BACKGROUND";
    public static final String ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";
    public static final String ACTION_MANAGED_PROFILE_ADDED = "android.intent.action.MANAGED_PROFILE_ADDED";
    public static final String ACTION_MANAGED_PROFILE_REMOVED = "android.intent.action.MANAGED_PROFILE_REMOVED";
    public static final String ACTION_MANAGED_PROFILE_UNLOCKED = "android.intent.action.MANAGED_PROFILE_UNLOCKED";
    public static final String ACTION_MANAGED_PROFILE_AVAILABLE = "android.intent.action.MANAGED_PROFILE_AVAILABLE";
    public static final String ACTION_MANAGED_PROFILE_UNAVAILABLE = "android.intent.action.MANAGED_PROFILE_UNAVAILABLE";
    public static final String ACTION_PROFILE_AVAILABLE = "android.intent.action.PROFILE_AVAILABLE";
    public static final String ACTION_PROFILE_UNAVAILABLE = "android.intent.action.PROFILE_UNAVAILABLE";
    public static final String ACTION_PROFILE_ACCESSIBLE = "android.intent.action.PROFILE_ACCESSIBLE";
    public static final String ACTION_PROFILE_INACCESSIBLE = "android.intent.action.PROFILE_INACCESSIBLE";
    public static final String ACTION_PROFILE_REMOVED = "android.intent.action.PROFILE_REMOVED";
    public static final String ACTION_PROFILE_ADDED = "android.intent.action.PROFILE_ADDED";
    public static final String ACTION_QUICK_CLOCK = "android.intent.action.QUICK_CLOCK";
    public static final String ACTION_TRANSLATE = "android.intent.action.TRANSLATE";
    public static final String ACTION_DEFINE = "android.intent.action.DEFINE";
    public static final String ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
    public static final String EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT";
    public static final String EXTRA_PROCESS_TEXT_READONLY = "android.intent.extra.PROCESS_TEXT_READONLY";
    public static final String ACTION_VIEW_LOCUS = "android.intent.action.VIEW_LOCUS";
    public static final String ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE";
    public static final String EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE";
    public static final String ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE = "android.intent.action.LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE";
    public static final String EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE = "android.intent.extra.CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE";
    public static final int CAPTURE_CONTENT_FOR_NOTE_SUCCESS = 0;
    public static final int CAPTURE_CONTENT_FOR_NOTE_FAILED = 1;
    public static final int CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED = 2;
    public static final int CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED = 3;
    public static final int CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN = 4;
    public static final String ACTION_UNARCHIVE_PACKAGE = "android.intent.action.UNARCHIVE_PACKAGE";
    public static final String CATEGORY_SECONDARY_HOME = "android.intent.category.SECONDARY_HOME";
    public static final String CATEGORY_DEVELOPMENT_PREFERENCE = "android.intent.category.DEVELOPMENT_PREFERENCE";
    public static final String CATEGORY_APP_MARKET = "android.intent.category.APP_MARKET";
    public static final String CATEGORY_TYPED_OPENABLE  = "android.intent.category.TYPED_OPENABLE";
    public static final String CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST = "android.intent.category.FRAMEWORK_INSTRUMENTATION_TEST";
    public static final String CATEGORY_CAR_DOCK = "android.intent.category.CAR_DOCK";
    public static final String CATEGORY_DESK_DOCK = "android.intent.category.DESK_DOCK";
    public static final String CATEGORY_LE_DESK_DOCK = "android.intent.category.LE_DESK_DOCK";
    public static final String CATEGORY_HE_DESK_DOCK = "android.intent.category.HE_DESK_DOCK";
    public static final String CATEGORY_VR_HOME = "android.intent.category.VR_HOME";
    public static final String CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET = "android.intent.category.ACCESSIBILITY_SHORTCUT_TARGET";
    public static final String CATEGORY_APP_FILES = "android.intent.category.APP_FILES";
    public static final String CATEGORY_APP_WEATHER = "android.intent.category.APP_WEATHER";
    public static final String CATEGORY_APP_FITNESS = "android.intent.category.APP_FITNESS";
    public static final String EXTRA_ATTRIBUTION_TAGS = "android.intent.extra.ATTRIBUTION_TAGS";
    public static final String EXTRA_START_TIME = "android.intent.extra.START_TIME";
    public static final String EXTRA_END_TIME = "android.intent.extra.END_TIME";
    public static final String EXTRA_ALTERNATE_INTENTS = "android.intent.extra.ALTERNATE_INTENTS";
    public static final String EXTRA_EXCLUDE_COMPONENTS = "android.intent.extra.EXCLUDE_COMPONENTS";
    public static final String EXTRA_CHOOSER_TARGETS = "android.intent.extra.CHOOSER_TARGETS";
    public static final String EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER = "android.intent.extra.CHOOSER_REFINEMENT_INTENT_SENDER";
    public static final String EXTRA_CHOOSER_CUSTOM_ACTIONS = "android.intent.extra.CHOOSER_CUSTOM_ACTIONS";
    public static final String EXTRA_CHOOSER_MODIFY_SHARE_ACTION = "android.intent.extra.CHOOSER_MODIFY_SHARE_ACTION";
    public static final String EXTRA_CHOOSER_CONTENT_TYPE_HINT = "android.intent.extra.CHOOSER_CONTENT_TYPE_HINT";
    public static final int CHOOSER_CONTENT_TYPE_ALBUM = 1;
    public static final String EXTRA_CHOOSER_ADDITIONAL_CONTENT_URI = "android.intent.extra.CHOOSER_ADDITIONAL_CONTENT_URI";
    public static final String EXTRA_CHOOSER_FOCUSED_ITEM_POSITION = "android.intent.extra.CHOOSER_FOCUSED_ITEM_POSITION";
    public static final String EXTRA_CONTENT_ANNOTATIONS = "android.intent.extra.CONTENT_ANNOTATIONS";
    public static final String EXTRA_RESULT_RECEIVER = "android.intent.extra.RESULT_RECEIVER";
    public static final String EXTRA_METADATA_TEXT = "android.intent.extra.METADATA_TEXT";
    public static final String EXTRA_REPLACEMENT_EXTRAS = "android.intent.extra.REPLACEMENT_EXTRAS";
    public static final String EXTRA_CHOSEN_COMPONENT_INTENT_SENDER = "android.intent.extra.CHOSEN_COMPONENT_INTENT_SENDER";
    public static final String EXTRA_CHOOSER_RESULT_INTENT_SENDER = "android.intent.extra.CHOOSER_RESULT_INTENT_SENDER";
    public static final String EXTRA_CHOOSER_RESULT = "android.intent.extra.CHOOSER_RESULT";
    public static final String EXTRA_KEY_EVENT = "android.intent.extra.KEY_EVENT";
    public static final String EXTRA_DONT_KILL_APP = "android.intent.extra.DONT_KILL_APP";
    public static final String EXTRA_USER_INITIATED = "android.intent.extra.USER_INITIATED";
    public static final String EXTRA_PHONE_NUMBER = "android.intent.extra.PHONE_NUMBER";
    public static final String EXTRA_PACKAGES = "android.intent.extra.PACKAGES";
    public static final String EXTRA_ARCHIVAL = "android.intent.extra.ARCHIVAL";
    public static final String EXTRA_ALARM_COUNT = "android.intent.extra.ALARM_COUNT";
    public static final String EXTRA_DOCK_STATE = "android.intent.extra.DOCK_STATE";
    public static final int EXTRA_DOCK_STATE_UNDOCKED = 0;
    public static final int EXTRA_DOCK_STATE_DESK = 1;
    public static final int EXTRA_DOCK_STATE_CAR = 2;
    public static final int EXTRA_DOCK_STATE_LE_DESK = 3;
    public static final int EXTRA_DOCK_STATE_HE_DESK = 4;
    public static final String METADATA_DOCK_HOME = "android.dock_home";
    public static final String EXTRA_BUG_REPORT = "android.intent.extra.BUG_REPORT";
    public static final String EXTRA_REMOTE_INTENT_TOKEN = "android.intent.extra.remote_intent_token";
    public static final String EXTRA_CHANGED_COMPONENT_NAME_LIST = "android.intent.extra.changed_component_name_list";
    public static final String EXTRA_USER = "android.intent.extra.USER";
    public static final String EXTRA_RESTRICTIONS_LIST = "android.intent.extra.restrictions_list";
    public static final String EXTRA_RESTRICTIONS_BUNDLE = "android.intent.extra.restrictions_bundle";
    public static final String EXTRA_RESTRICTIONS_INTENT = "android.intent.extra.restrictions_intent";
    public static final String EXTRA_SHUTDOWN_USERSPACE_ONLY = "android.intent.extra.SHUTDOWN_USERSPACE_ONLY";
    public static final String EXTRA_TIME = "android.intent.extra.TIME";
    public static final String EXTRA_TIMEZONE = "time-zone";
    public static final String EXTRA_INDEX = "android.intent.extra.INDEX";
    public static final String EXTRA_QUICK_VIEW_FEATURES = "android.intent.extra.QUICK_VIEW_FEATURES";
    public static final String EXTRA_QUIET_MODE = "android.intent.extra.QUIET_MODE";
    public static final String EXTRA_CONTENT_QUERY = "android.intent.extra.CONTENT_QUERY";
    public static final String EXTRA_AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE";
    public static final String EXTRA_LOCUS_ID = "android.intent.extra.LOCUS_ID";
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;
    public static final int FLAG_FROM_BACKGROUND = 0x00000004;
    public static final int FLAG_DEBUG_LOG_RESOLUTION = 0x00000008;
    public static final int FLAG_EXCLUDE_STOPPED_PACKAGES = 0x00000010;
    public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020;
    public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040;
    public static final int FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080;
    public static final int FLAG_DIRECT_BOOT_AUTO = 0x00000100;
    public static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    public static final int FLAG_ACTIVITY_FORWARD_RESULT = 0x02000000;
    public static final int FLAG_ACTIVITY_PREVIOUS_IS_TOP = 0x01000000;
    public static final int FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 0x00800000;
    public static final int FLAG_ACTIVITY_BROUGHT_TO_FRONT = 0x00400000;
    public static final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;
    public static final int FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000;
    public static final int FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET = 0x00080000;
    public static final int FLAG_ACTIVITY_NEW_DOCUMENT = FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET;
    public static final int FLAG_ACTIVITY_NO_USER_ACTION = 0x00040000;
    public static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 0X00020000;
    public static final int FLAG_ACTIVITY_NO_ANIMATION = 0X00010000;
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0X00008000;
    public static final int FLAG_ACTIVITY_TASK_ON_HOME = 0X00004000;
    public static final int FLAG_ACTIVITY_RETAIN_IN_RECENTS = 0x00002000;
    public static final int FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x00001000;
    public static final int FLAG_ACTIVITY_MATCH_EXTERNAL = 0x00000800;
    public static final int FLAG_ACTIVITY_REQUIRE_NON_BROWSER = 0x00000400;
    public static final int FLAG_ACTIVITY_REQUIRE_DEFAULT = 0x00000200;
    public static final int FLAG_RECEIVER_REGISTERED_ONLY = 0x40000000;
    public static final int FLAG_RECEIVER_REPLACE_PENDING = 0x20000000;
    public static final int FLAG_RECEIVER_FOREGROUND = 0x10000000;
    public static final int FLAG_RECEIVER_NO_ABORT = 0x08000000;
    public static final int FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x00200000;

    // AOSP Intent.fillIn flag bits (frameworks/base .../content/Intent.java).
    // Each bit allows the corresponding field to be overwritten by fillIn()
    // even when this Intent already has a value for it.
    public static final int FILL_IN_ACTION         = 1<<0;
    public static final int FILL_IN_DATA           = 1<<1;
    public static final int FILL_IN_CATEGORIES     = 1<<2;
    public static final int FILL_IN_COMPONENT      = 1<<3;
    public static final int FILL_IN_PACKAGE        = 1<<4;
    public static final int FILL_IN_SOURCE_BOUNDS  = 1<<5;
    public static final int FILL_IN_SELECTOR       = 1<<6;
    public static final int FILL_IN_CLIP_DATA      = 1<<7;
    public static final int FILL_IN_IDENTIFIER     = 1<<8;

    // AOSP Intent.@UriFlags -- toUri(int)/parseUri(String,int) flag bits.
    // URI_ANDROID_APP_SCHEME's value is declared (an app that merely reads
    // the constant must not hit NoSuchFieldError) but its "android-app://"
    // encoding is not implemented by toUri/parseUri below -- see the
    // scoping note on parseUri.
    public static final int URI_INTENT_SCHEME       = 1<<0;
    public static final int URI_ANDROID_APP_SCHEME  = 1<<1;
    public static final int URI_ALLOW_UNSAFE        = 1<<2;

    // AOSP leaves mCategories and mExtras NULL until something is actually
    // added -- addCategory lazily news the ArraySet, putExtra lazily news the
    // Bundle (frameworks/base/core/java/android/content/Intent.java, addCategory
    // and putExtra(String,String)). Pre-allocating them here inverted the single
    // most common guard in Android app code, `if (intent.getExtras() != null)`,
    // so an app that means "the caller sent me nothing" took the "the caller
    // sent me something" branch on every launch Intent.
    private String action;
    private Uri data;
    private String type;
    private Set<String> categories;
    private Bundle extras;
    private int flags;
    private ComponentName component;
    private String identifier;
    private Rect sourceBounds;
    private Intent selector;
    private ClipData clipData;

    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(String action, Uri data) { this.action = action; this.data = data; }
    public Intent(Context packageContext, Class<?> cls) { this.component = new ComponentName(packageContext, cls); }
    /** AOSP 4-arg form: an explicit Intent that also carries an action + data.
     *  Wikipedia builds deep-link Intents this way; a missing ctor threw
     *  NoSuchMethodError. */
    public Intent(String action, Uri data, Context packageContext, Class<?> cls) {
        this.action = action;
        this.data = data;
        this.component = new ComponentName(packageContext, cls);
    }
    // AOSP's private copy modes (frameworks/base .../content/Intent.java).
    // COPY_MODE_FILTER keeps only the fields IntentFilter matching looks at,
    // which is what cloneFilter() hands out.
    private static final int COPY_MODE_ALL = 0;
    private static final int COPY_MODE_FILTER = 1;

    public Intent(Intent o) {
        // AOSP's public copy ctor is `this(o, COPY_MODE_ALL)`. The null check is
        // ours: AOSP NPEs, but callers on this tree have relied on the tolerant
        // form since before the divergence audit and nothing in the probe
        // exercises `new Intent((Intent) null)`.
        if (o != null) copyFrom(o, COPY_MODE_ALL);
    }

    /** AOSP's private Intent(Intent, @CopyMode int) body. Under
     *  COPY_MODE_FILTER everything past the component is skipped, so flags,
     *  extras, clipData, sourceBounds and selector are all dropped -- that is
     *  precisely the contract cloneFilter() advertises. */
    private void copyFrom(Intent o, int copyMode) {
        this.action = o.action;
        this.data = o.data;
        this.type = o.type;
        this.identifier = o.identifier;
        this.targetPackage = o.targetPackage;
        this.component = o.component;
        if (o.categories != null) {
            this.categories = new HashSet<>(o.categories);
        }
        if (copyMode != COPY_MODE_FILTER) {
            this.flags = o.flags;
            // AOSP deep-copies these rather than aliasing them.
            if (o.sourceBounds != null) this.sourceBounds = new Rect(o.sourceBounds);
            if (o.selector != null) this.selector = new Intent(o.selector);
            if (o.extras != null) this.extras = new Bundle(o.extras);
            if (o.clipData != null) this.clipData = new ClipData(o.clipData);
        }
    }

    /** AOSP Intent.clone: a full copy, same as the copy constructor. Declared
     *  public here (Object.clone is protected) because AOSP declares it public
     *  -- without the override an app's `intent.clone()` from another package
     *  is an IllegalAccessError at the call site rather than a copy. */
    @Override public Object clone() { return new Intent(this); }

    /** AOSP Intent.cloneFilter: a copy of only the parts relevant to filter
     *  matching (action, data, type, identifier, package, component,
     *  categories). Extras and flags are deliberately dropped. */
    public Intent cloneFilter() {
        Intent c = new Intent();
        c.copyFrom(this, COPY_MODE_FILTER);
        return c;
    }

    /** AOSP static helper used by inflater code paths -- reads an
     *  &lt;intent&gt; element from the XmlPullParser at its current
     *  START_TAG position and builds an Intent. AOSP's parser walks
     *  the element's attributes (action, data, type, targetPackage,
     *  targetClass) and child &lt;category&gt; + &lt;extra&gt; tags.
     *  Used by androidx.preference.PreferenceInflater when a
     *  &lt;Preference&gt; XML node contains a nested intent (F-Droid's
     *  preferences.xml uses this for the "about" / "send feedback"
     *  preference entries). Without this method, F-Droid's
     *  Application.onCreate fails on the FIRST preference XML walk
     *  with NoSuchMethodError. */
    public static Intent parseIntent(android.content.res.Resources resources,
                                       org.xmlpull.v1.XmlPullParser parser,
                                       android.util.AttributeSet attrs)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
        Intent i = new Intent();
        if (attrs != null) {
            i.action = attrs.getAttributeValue(ANDROID_NS, "action");
            String dataStr = attrs.getAttributeValue(ANDROID_NS, "data");
            if (dataStr != null) i.data = Uri.parse(dataStr);
            i.type = attrs.getAttributeValue(ANDROID_NS, "mimeType");
            String pkg = attrs.getAttributeValue(ANDROID_NS, "targetPackage");
            String cls = attrs.getAttributeValue(ANDROID_NS, "targetClass");
            if (pkg != null && cls != null) i.component = new ComponentName(pkg, cls);
        }
        // Walk child <category>/<extra>. Parser is on the <intent>
        // START_TAG; advance through children until the matching END_TAG.
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
            && (event != org.xmlpull.v1.XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (event != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if ("category".equals(tag)) {
                String name = parser.getAttributeValue(ANDROID_NS, "name");
                if (name != null) i.addCategory(name);
            } else if ("extra".equals(tag)) {
                String name  = parser.getAttributeValue(ANDROID_NS, "name");
                String value = parser.getAttributeValue(ANDROID_NS, "value");
                if (name != null && value != null) {
                    if (i.extras == null) i.extras = new android.os.Bundle();
                    i.extras.putString(name, value);
                }
            }
        }
        return i;
    }

    public String getAction()       { return action; }
    public Intent setAction(String a){ this.action = a; return this; }
    public Uri    getData()         { return data; }
    /** AOSP Intent.setData is `mData = data; mType = null;` -- data and type are
     *  MUTUALLY EXCLUSIVE, and only setDataAndType sets both. Not clearing the
     *  type here meant an Intent built `setType(t).setData(u)` carried both, so
     *  it matched a type-only filter that real Android rejects (and vice versa)
     *  -- the exact shape of every ACTION_SEND share flow. */
    public Intent setData(Uri uri)  { this.data = uri; this.type = null; return this; }
    /** AOSP Intent.setDataAndNormalize: setData(data.normalizeScheme()). */
    public Intent setDataAndNormalize(Uri uri) { return setData(uri.normalizeScheme()); }
    public String getDataString()   { return data != null ? data.toString() : null; }
    /** AOSP Intent.getScheme: shorthand for getData().getScheme(), null-safe.
     *  The standard `"content".equals(intent.getScheme())` idiom used
     *  throughout share-target / deep-link routing code. */
    public String getScheme()       { return data != null ? data.getScheme() : null; }
    public String getType()         { return type; }
    /** AOSP Intent.setType is `mData = null; mType = type;` -- the mirror of
     *  setData above. See the note there. */
    public Intent setType(String t) { this.data = null; this.type = t; return this; }
    /** AOSP Intent.setTypeAndNormalize: setType(normalizeMimeType(type)). */
    public Intent setTypeAndNormalize(String t) { return setType(normalizeMimeType(t)); }
    /** AOSP Intent.normalizeMimeType: trim, lowercase in Locale.ROOT, and cut
     *  everything from the first ';' (the parameter list) onward. Note that the
     *  cut happens AFTER the trim, so " Image/PNG ; q=1" normalizes to
     *  "image/png " -- with the trailing space kept, because only the leading
     *  and trailing whitespace of the WHOLE string was trimmed. Faithful to
     *  frameworks/base/core/java/android/content/Intent.java's normalizeMimeType,
     *  including that quirk. */
    public static String normalizeMimeType(String type) {
        if (type == null) return null;
        type = type.trim().toLowerCase(java.util.Locale.ROOT);
        final int semicolonIndex = type.indexOf(';');
        if (semicolonIndex != -1) {
            type = type.substring(0, semicolonIndex);
        }
        return type;
    }
    /** AOSP Intent.resolveType: explicit type if set, else the content
     *  provider's type for a content: data Uri. */
    public String resolveType(android.content.ContentResolver resolver) {
        if (type != null) return type;
        if (data != null && resolver != null && "content".equals(data.getScheme())) {
            return resolver.getType(data);
        }
        return null;
    }
    public String resolveType(android.content.Context context) {
        return resolveType(context != null ? context.getContentResolver() : null);
    }
    /** AOSP Intent.resolveTypeIfNeeded: when an explicit component is set the
     *  type needs no RESOLVING (there is nothing to route), so AOSP returns the
     *  explicit mType as-is -- it does NOT return null. Returning null here made
     *  every explicit install/share Intent look untyped to its receiver. */
    public String resolveTypeIfNeeded(android.content.ContentResolver resolver) {
        if (component != null) return type;
        return resolveType(resolver);
    }
    /** AOSP Intent.setDataAndType: set the data Uri and explicit MIME type
     *  together (the combined form skips setData's type-clearing). F-Droid's
     *  AppDetails header builds an APK-install Intent this way
     *  (setDataAndType(fileUri, "application/vnd.android.package-archive")). */
    public Intent setDataAndType(Uri uri, String t) { this.data = uri; this.type = t; return this; }
    /** AOSP Intent.setDataAndTypeAndNormalize:
     *  setDataAndType(data.normalizeScheme(), normalizeMimeType(type)).
     *  Note the Uri normalization is scheme-ONLY, so "HTTP://H/P" becomes
     *  "http://H/P" -- the host keeps its case. */
    public Intent setDataAndTypeAndNormalize(Uri uri, String t) {
        return setDataAndType(uri.normalizeScheme(), normalizeMimeType(t));
    }
    public Intent addCategory(String cat) {
        if (categories == null) categories = new HashSet<>();
        categories.add(cat);
        return this;
    }
    public Set<String> getCategories() { return categories; }
    public boolean hasCategory(String cat) { return categories != null && categories.contains(cat); }
    /** AOSP Intent.removeCategory: delete one category, and drop the whole set
     *  back to null once it empties (so `getCategories() != null` stays an
     *  accurate "this Intent carries categories" test). */
    public void removeCategory(String cat) {
        if (categories != null) {
            categories.remove(cat);
            if (categories.isEmpty()) categories = null;
        }
    }
    public int    getFlags()        { return flags; }
    public Intent setFlags(int f)   { this.flags = f; return this; }
    public Intent addFlags(int f)   { this.flags |= f; return this; }
    /** AOSP :11375 -- `mFlags &= ~flags;`. Note it returns void, unlike the
     *  chainable add/set pair. */
    public void removeFlags(int f)  { this.flags &= ~f; }
    public ComponentName getComponent() { return component; }
    public Intent setComponent(ComponentName c) { this.component = c; return this; }
    /** AOSP Intent.getSourceBounds: on-screen bounds of the view that
     *  triggered this Intent, used as an animation-origin hint. Mac.apk has
     *  no launch-transition system to consume it; storage only. */
    public Rect getSourceBounds() { return sourceBounds; }
    /** AOSP Intent.setSourceBounds returns void (not fluent, unlike most
     *  Intent setters -- verified against frameworks/base
     *  .../content/Intent.java; the corpus's own DEX descriptor for this
     *  method also ends in ")V"). Copies the Rect, matching AOSP's
     *  `mSourceBounds = new Rect(r)`. */
    public void setSourceBounds(Rect r) { this.sourceBounds = (r != null) ? new Rect(r) : null; }
    /** AOSP Intent.getIdentifier (API 29): a free-form debugging /
     *  analytics-correlation string. Excluded from IntentFilter matching but
     *  included in filterEquals/filterHashCode below. */
    public String getIdentifier() { return identifier; }
    public Intent setIdentifier(String identifier) { this.identifier = identifier; return this; }
    /** AOSP Intent.getSelector/setSelector: resolve via a secondary Intent
     *  while keeping this Intent as the data payload -- the mechanism behind
     *  "open with" choosers and launcher-replacement code. Mirrors AOSP's
     *  two guards exactly (frameworks/base .../content/Intent.java
     *  setSelector); AOSP does not additionally clear the selector's own
     *  nested selector here. */
    public Intent getSelector() { return selector; }
    public void setSelector(Intent selector) {
        if (selector == this) {
            throw new IllegalArgumentException("Intent being set as a selector of itself");
        }
        if (selector != null && targetPackage != null) {
            throw new IllegalArgumentException("Can't set selector when package name is already set");
        }
        this.selector = selector;
    }
    /** AOSP Intent.getClipData/setClipData: an optional ClipData payload,
     *  semantically similar to extras. Not used for Intent matching. Real
     *  Android applies FLAG_GRANT_READ/WRITE_URI_PERMISSION to its URI
     *  items; Mac.apk has no cross-app URI grant system, so this is
     *  storage only. */
    public ClipData getClipData() { return clipData; }
    public void setClipData(ClipData clip) { this.clipData = clip; }
    public Intent setClass(Context ctx, Class<?> cls) { this.component = new ComponentName(ctx, cls); return this; }
    public Intent setClassName(String packageName, String className) { this.component = new ComponentName(packageName, className); return this; }
    // AOSP: setClassName(Context, String) targets className within the given
    // context's package. GMS measurement (DynamiteMeasurementManager) calls this
    // during init; missing it -> NoSuchMethodError on the FA worker thread.
    public Intent setClassName(Context packageContext, String className) { this.component = new ComponentName(packageContext.getPackageName(), className); return this; }
    // Android: target this intent only at the named package's resolvers.
    // We don't actually resolve intents (Mac.apk is a single-app
    // process), but engines call setPackage during init to narrow
    // implicit intents -- the value is informational here.
    private String targetPackage;
    public Intent setPackage(String pkg) { this.targetPackage = pkg; return this; }
    public String getPackage()           { return targetPackage; }
    /** AOSP Intent.getExtras returns a COPY of the extras, or null when the
     *  Intent has none: `return (mExtras != null) ? new Bundle(mExtras) : null`.
     *  Both halves matter -- the null is the guard apps test, and the copy is
     *  why mutating the returned Bundle does not write back into the Intent. */
    public Bundle getExtras()       { return (extras != null) ? new Bundle(extras) : null; }
    /** Lazily allocate the extras Bundle. AOSP open-codes this
     *  `if (mExtras == null) mExtras = new Bundle();` prologue in every
     *  putExtra overload; one helper keeps the ~30 overloads below readable. */
    private Bundle ext() {
        if (extras == null) extras = new Bundle();
        return extras;
    }
    /** AOSP Intent.setExtrasClassLoader: sets the ClassLoader used to
     *  unmarshal Parcelable/Serializable extras out of a Parcel. Mac.apk's
     *  Bundle stores live objects rather than lazily-unmarshalled Parcel
     *  bytes (no IPC boundary to cross), so Bundle.setClassLoader is
     *  already a no-op; forwarding here just makes the call linkable. */
    public void setExtrasClassLoader(ClassLoader loader) {
        if (extras != null) extras.setClassLoader(loader);
    }
    /** AOSP Intent.removeExtra: delete one extra key, and drop the whole
     *  Bundle back to null once it empties (mirrors removeCategory). */
    public void removeExtra(String name) {
        if (extras != null) {
            extras.remove(name);
            if (extras.size() == 0) extras = null;
        }
    }
    public Intent putExtras(Bundle src) { ext().putAll(src); return this; }
    /** AOSP Intent.putExtras(Intent): copy another Intent's own extras
     *  Bundle into this one (a distinct overload from putExtras(Bundle)).
     *  A source with no extras is a no-op and must NOT force this Intent to
     *  allocate one. */
    public Intent putExtras(Intent src) {
        if (src != null && src.extras != null) {
            if (extras == null) extras = new Bundle(src.extras);
            else extras.putAll(src.extras);
        }
        return this;
    }
    /** AOSP Intent.replaceExtras(Bundle): swap the entire extras Bundle
     *  wholesale -- `mExtras = extras != null ? new Bundle(extras) : null`.
     *  A null argument clears the Intent back to the no-extras state, which is
     *  observably different from an empty Bundle. */
    public Intent replaceExtras(Bundle extras) {
        this.extras = (extras != null) ? new Bundle(extras) : null;
        return this;
    }
    /** AOSP Intent.replaceExtras(Intent): the Intent-sourced overload of the
     *  above; copies the source's extras wholesale, nulling ours if it has
     *  none. */
    public Intent replaceExtras(Intent src) {
        this.extras = (src != null && src.extras != null) ? new Bundle(src.extras) : null;
        return this;
    }
    public Intent putExtra(String k, String v)   { ext().putString(k, v); return this; }
    public Intent putExtra(String k, int v)      { ext().putInt(k, v); return this; }
    public Intent putExtra(String k, long v)     { ext().putLong(k, v); return this; }
    public Intent putExtra(String k, boolean v)  { ext().putBoolean(k, v); return this; }
    public Intent putExtra(String k, float v)    { ext().putFloat(k, v); return this; }
    public Intent putExtra(String k, Bundle v)   { ext().putBundle(k, v); return this; }
    public Intent putExtra(String k, Serializable v) { ext().putSerializable(k, v); return this; }
    // AOSP API-1 Parcelable / array overloads. WorkManager's
    // SystemForegroundDispatcher.createStartForegroundIntent uses
    // putExtra(String, Parcelable) with a Notification value; the
    // class-link resolution fails without the method existing. We
    // stash via Bundle.putParcelable when storage is needed; for the
    // foreground-intent case the value is only round-tripped through
    // SystemForegroundService which we don't actually run (the
    // dispatcher path is reached even when we have no foreground
    // service infrastructure, because the method-resolution happens
    // at class verify time).
    public Intent putExtra(String k, byte v)        { ext().putByte(k, v); return this; }
    public Intent putExtra(String k, char v)        { ext().putChar(k, v); return this; }
    public Intent putExtra(String k, short v)       { ext().putShort(k, v); return this; }
    public Intent putExtra(String k, double v)      { ext().putDouble(k, v); return this; }
    public Intent putExtra(String k, CharSequence v){ ext().putCharSequence(k, v); return this; }
    public Intent putExtra(String k, android.os.Parcelable v) { ext().putParcelable(k, v); return this; }
    public Intent putExtra(String k, android.os.Parcelable[] v) { ext().putParcelableArray(k, v); return this; }
    public Intent putExtra(String k, int[] v)       { ext().putIntArray(k, v); return this; }
    public Intent putExtra(String k, long[] v)      { ext().putLongArray(k, v); return this; }
    public Intent putExtra(String k, float[] v)     { ext().putFloatArray(k, v); return this; }
    public Intent putExtra(String k, double[] v)    { ext().putDoubleArray(k, v); return this; }
    public Intent putExtra(String k, String[] v)    { ext().putStringArray(k, v); return this; }
    public Intent putExtra(String k, CharSequence[] v) { ext().putCharSequenceArray(k, v); return this; }
    public Intent putExtra(String k, boolean[] v)   { ext().putBooleanArray(k, v); return this; }
    public Intent putExtra(String k, byte[] v)      { ext().putByteArray(k, v); return this; }
    public Intent putExtra(String k, char[] v)      { ext().putCharArray(k, v); return this; }
    public Intent putExtra(String k, short[] v)     { ext().putShortArray(k, v); return this; }
    public Intent putParcelableArrayListExtra(String k, java.util.ArrayList<? extends android.os.Parcelable> v) {
        ext().putParcelableArrayList(k, v); return this;
    }
    public Intent putStringArrayListExtra(String k, java.util.ArrayList<String> v) {
        ext().putStringArrayList(k, v); return this;
    }
    public Intent putIntegerArrayListExtra(String k, java.util.ArrayList<Integer> v) {
        ext().putIntegerArrayList(k, v); return this;
    }
    /** AOSP core/java/android/content/Intent.java:10908 -- the CharSequence
     *  sibling of putStringArrayListExtra. The read side
     *  (getCharSequenceArrayListExtra) was already here without its writer, so
     *  an app that put a styled-text list had no way to store it. */
    public Intent putCharSequenceArrayListExtra(String k, java.util.ArrayList<CharSequence> v) {
        ext().putCharSequenceArrayList(k, v); return this;
    }
    public android.os.Parcelable getParcelableExtra(String k) { return extras == null ? null : extras.getParcelable(k); }
    /** AOSP API-33 typed variant of getParcelableExtra: type-safe
     *  replacement for the 1-arg form above, returns null on a type
     *  mismatch instead of relying on an unchecked cast at the call site.
     *  Delegates to Bundle's own typed getParcelable(String,Class), which
     *  already exists on this tree. */
    public <T> T getParcelableExtra(String name, Class<T> clazz) {
        return extras == null ? null : extras.getParcelable(name, clazz);
    }
    public java.util.ArrayList<? extends android.os.Parcelable> getParcelableArrayListExtra(String k) {
        return extras == null ? null : extras.getParcelableArrayList(k);
    }
    /** AOSP API-33 typed variant of getParcelableArrayListExtra. Bundle on
     *  this tree only exposes the untyped getParcelableArrayList(String);
     *  since Mac.apk's Bundle stores live objects rather than a
     *  lazily-unmarshalled Parcel, the per-item type check AOSP performs
     *  while unparceling reduces here to a plain isInstance walk over the
     *  already-live list -- same observable contract (wrong element type
     *  or missing key -> null, never throws). */
    @SuppressWarnings("unchecked")
    public <T> java.util.ArrayList<T> getParcelableArrayListExtra(String name, Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        java.util.ArrayList<? extends android.os.Parcelable> list =
                extras == null ? null : extras.getParcelableArrayList(name);
        if (list == null) return null;
        for (android.os.Parcelable p : list) {
            if (p != null && !clazz.isInstance(p)) return null;
        }
        return (java.util.ArrayList<T>) list;
    }
    public android.os.Parcelable[] getParcelableArrayExtra(String k) { return extras == null ? null : extras.getParcelableArray(k); }
    /** AOSP API-33 typed variant of getParcelableArrayExtra. Same
     *  single-VM reasoning as getParcelableArrayListExtra(String,Class)
     *  above: verify each element against clazz directly instead of
     *  relying on a lazy Parcel unmarshal to do it. */
    @SuppressWarnings("unchecked")
    public <T> T[] getParcelableArrayExtra(String name, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        android.os.Parcelable[] arr = extras == null ? null : extras.getParcelableArray(name);
        if (arr == null) return null;
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, arr.length);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null && !clazz.isInstance(arr[i])) return null;
            out[i] = (T) arr[i];
        }
        return out;
    }
    public byte    getByteExtra(String k, byte d)   { return extras == null ? d : extras.getByte(k, d); }
    public char    getCharExtra(String k, char d)   { return extras == null ? d : extras.getChar(k, d); }
    public short   getShortExtra(String k, short d) { return extras == null ? d : extras.getShort(k, d); }
    public double  getDoubleExtra(String k, double d) { return extras == null ? d : extras.getDouble(k, d); }
    public CharSequence getCharSequenceExtra(String k) { return extras == null ? null : extras.getCharSequence(k); }
    public int[]   getIntArrayExtra(String k)       { return extras == null ? null : extras.getIntArray(k); }
    public long[]  getLongArrayExtra(String k)      { return extras == null ? null : extras.getLongArray(k); }
    public float[] getFloatArrayExtra(String k)     { return extras == null ? null : extras.getFloatArray(k); }
    public double[] getDoubleArrayExtra(String k)   { return extras == null ? null : extras.getDoubleArray(k); }
    public String[] getStringArrayExtra(String k)   { return extras == null ? null : extras.getStringArray(k); }
    public boolean[] getBooleanArrayExtra(String k) { return extras == null ? null : extras.getBooleanArray(k); }
    public byte[]  getByteArrayExtra(String k)      { return extras == null ? null : extras.getByteArray(k); }
    public char[]  getCharArrayExtra(String k)      { return extras == null ? null : extras.getCharArray(k); }
    public short[] getShortArrayExtra(String k)     { return extras == null ? null : extras.getShortArray(k); }
    public CharSequence[] getCharSequenceArrayExtra(String k) { return extras == null ? null : extras.getCharSequenceArray(k); }
    public java.util.ArrayList<String> getStringArrayListExtra(String k) { return extras == null ? null : extras.getStringArrayList(k); }
    /** AOSP: the CharSequence-typed sibling of getStringArrayListExtra. Both
     *  read the same Bundle; the difference is only the element type, and an
     *  app that put CharSequences (styled text, a Spanned) must read them back
     *  through this one or lose the spans to a ClassCastException. */
    public java.util.ArrayList<CharSequence> getCharSequenceArrayListExtra(String k) {
        return extras == null ? null : extras.getCharSequenceArrayList(k);
    }
    public java.util.ArrayList<Integer> getIntegerArrayListExtra(String k) { return extras == null ? null : extras.getIntegerArrayList(k); }
    public String getStringExtra(String k)    { return extras == null ? null : extras.getString(k); }
    public int    getIntExtra(String k, int d){ return extras == null ? d : extras.getInt(k, d); }
    public boolean getBooleanExtra(String k, boolean d) { return extras == null ? d : extras.getBoolean(k, d); }
    public float  getFloatExtra(String k, float d) { return extras == null ? d : extras.getFloat(k, d); }
    public long   getLongExtra(String k, long d)   { return extras == null ? d : extras.getLong(k, d); }
    public Bundle getBundleExtra(String k)   { return extras == null ? null : extras.getBundle(k); }
    public Serializable getSerializableExtra(String k) { return extras == null ? null : extras.getSerializable(k); }
    /** AOSP API-33 typed variant of getSerializableExtra. Bundle on this
     *  tree only exposes the untyped getSerializable(String); same
     *  single-VM reasoning as the Parcelable typed variants above -- a
     *  plain isInstance check on the already-live object matches AOSP's
     *  documented contract exactly (wrong type or missing -> null, never
     *  throws). */
    public <T extends Serializable> T getSerializableExtra(String name, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        Serializable s = extras == null ? null : extras.getSerializable(name);
        return clazz.isInstance(s) ? clazz.cast(s) : null;
    }
    public boolean hasExtra(String k)        { return extras != null && extras.containsKey(k); }

    /** AOSP Intent.resolveActivity (Intent.java:8720-8735, android11-release):
     *      if (mComponent != null) return mComponent;
     *      ResolveInfo info = pm.resolveActivity(this, MATCH_DEFAULT_ONLY);
     *      if (info != null) return new ComponentName(
     *              info.activityInfo.applicationInfo.packageName, info.activityInfo.name);
     *      return null;
     *
     *  ⚠ v1.0.939: it used to be `return component;` -- the PackageManager
     *  argument was accepted and then never consulted, so for an IMPLICIT
     *  intent it always answered null.
     *
     *  That made a whole class of app feature dead. The standard Android idiom
     *  is `if (intent.resolveActivity(getPackageManager()) != null)
     *  startActivity(intent);` -- MacApkPackageManager quotes that very line in
     *  its own comment -- so an app guarding share / open-in-browser / dial that
     *  way took the else branch and hid the button, no matter how good our
     *  resolver was. v1.0.938 taught queryIntentActivities to hand those intents
     *  to macOS, and this method was the reason the fix could not be reached.
     *  The tell that it was an oversight rather than a decision: the very next
     *  method, resolveActivityInfo, was updated in the same version and DOES
     *  delegate.
     *
     *  fwprobe cannot see this one -- its cases call pm.resolveActivity
     *  directly, never through Intent -- which is exactly why it survived a
     *  campaign of closing probe rows. */
    public ComponentName resolveActivity(android.content.pm.PackageManager pm) {
        if (component != null) return component;
        if (pm == null) return null;
        try {
            // MATCH_DEFAULT_ONLY (0x00010000) inlined: PackageManager on this
            // tree does not declare the constant.
            android.content.pm.ResolveInfo info = pm.resolveActivity(this, 0x00010000);
            if (info != null && info.activityInfo != null) {
                String pkg = (info.activityInfo.applicationInfo != null)
                        ? info.activityInfo.applicationInfo.packageName
                        : info.activityInfo.packageName;
                return new ComponentName(pkg, info.activityInfo.name);
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /** AOSP Intent.resolveActivityInfo(PackageManager,int): the full
     *  ActivityInfo of the matching activity, not just its ComponentName
     *  (contrast resolveActivity above). The prior declaration here
     *  returned ComponentName -- a different method descriptor entirely,
     *  so real app bytecode compiled against the real API could never
     *  link to it at all (verified against
     *  frameworks/base/core/java/android/content/Intent.java's
     *  resolveActivityInfo, which mirrors resolveActivity's
     *  explicit-component-first / else-resolveActivity structure).
     *  ⚠ v1.0.938: the try/catch below is AOSP's, and it is load-bearing.
     *  AOSP (Intent.java:8752-8768, android11-release) SWALLOWS the exception
     *  and returns null -- `try { ai = pm.getActivityInfo(mComponent, flags); }
     *  catch (PackageManager.NameNotFoundException e) { // ignore }`. This
     *  method is documented never to throw, and app code has no handler for it
     *  here, so letting it propagate would turn a fixed probe row into a new
     *  crash. The comment that used to sit here said wrapping the call would be
     *  a compile error; that was true only while PackageManager declared the
     *  overload without the clause. MATCH_DEFAULT_ONLY
     *  (0x00010000) is inlined rather than referenced by name because
     *  PackageManager.java does not declare that constant on this tree
     *  and is owned by a different concurrent editing pass this session
     *  (see followups). */
    public android.content.pm.ActivityInfo resolveActivityInfo(android.content.pm.PackageManager pm, int flags) {
        if (component != null) {
            try {
                return pm.getActivityInfo(component, flags);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return null;   // AOSP: "// ignore" then falls through to null
            }
        }
        android.content.pm.ResolveInfo info = pm.resolveActivity(this, 0x00010000 | flags);
        return (info != null) ? info.activityInfo : null;
    }
    /** AOSP Intent.filterEquals: compare by the FILTERING fields only
     *  (action/data/type/identifier/package/component/categories),
     *  explicitly excluding extras and sourceBounds. Used by PendingIntent
     *  dedup (FLAG_UPDATE_CURRENT relies on this) and general
     *  Intent-matching code. */
    public boolean filterEquals(Intent other) {
        if (other == null) return false;
        if (!java.util.Objects.equals(this.action, other.action)) return false;
        if (!java.util.Objects.equals(this.data, other.data)) return false;
        if (!java.util.Objects.equals(this.type, other.type)) return false;
        if (!java.util.Objects.equals(this.identifier, other.identifier)) return false;
        // AOSP SKIPS the package comparison entirely when BOTH sides already
        // pin a package-equivalent component: an explicit component to pkg/Cls
        // and a setPackage(pkg) say the same thing, so an Intent that carries
        // both must still filter-equal the one that carries only the component.
        // Comparing mPackage unconditionally made PendingIntent's
        // FLAG_UPDATE_CURRENT dedup miss, so every rebuild leaked a new
        // PendingIntent instead of updating the existing one.
        if (!(this.hasPackageEquivalentComponent() && other.hasPackageEquivalentComponent())
                && !java.util.Objects.equals(this.targetPackage, other.targetPackage)) {
            return false;
        }
        if (!java.util.Objects.equals(this.component, other.component)) return false;
        if (!java.util.Objects.equals(this.categories, other.categories)) return false;
        return true;
    }
    /** AOSP Intent.hasPackageEquivalentComponent: true when an explicit
     *  component is set and this Intent is not pinned to some OTHER package. */
    private boolean hasPackageEquivalentComponent() {
        return component != null
            && (targetPackage == null || targetPackage.equals(component.getPackageName()));
    }
    /** AOSP Intent.filterHashCode: hash matching filterEquals' field set.
     *  Note AOSP folds in mCategories only when it is non-null, so an Intent
     *  with no categories does not inherit an empty set's hash. */
    public int filterHashCode() {
        int code = 0;
        if (action != null) code += action.hashCode();
        if (data != null) code += data.hashCode();
        if (type != null) code += type.hashCode();
        if (identifier != null) code += identifier.hashCode();
        if (targetPackage != null) code += targetPackage.hashCode();
        if (component != null) code += component.hashCode();
        if (categories != null) code += categories.hashCode();
        return code;
    }

    /** AOSP Intent.makeMainActivity: the canonical "launch main activity"
     *  Intent -- the same shape Home uses to launch an app. Does not set
     *  FLAG_ACTIVITY_NEW_TASK (callers add that themselves via addFlags,
     *  matching AOSP). */
    public static Intent makeMainActivity(ComponentName mainActivity) {
        Intent intent = new Intent(ACTION_MAIN);
        intent.setComponent(mainActivity);
        intent.addCategory(CATEGORY_LAUNCHER);
        return intent;
    }
    /** AOSP :8166 -- makeMainActivity plus FLAG_ACTIVITY_NEW_TASK |
     *  FLAG_ACTIVITY_CLEAR_TASK, i.e. "restart this app's task from
     *  scratch". */
    public static Intent makeRestartActivityTask(ComponentName mainActivity) {
        Intent intent = makeMainActivity(mainActivity);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    /** AOSP :9360 -- `mExtras != null && mExtras.hasFileDescriptors()`.
     *  Bundle.hasFileDescriptors() is false for every parcel in this runtime
     *  (Parcel.writeFileDescriptor is a no-op with no binder behind it), so
     *  this is false in practice -- but it is DERIVED from the extras rather
     *  than hardcoded, so it stays correct if that ever changes. */
    public boolean hasFileDescriptors() {
        return extras != null && extras.hasFileDescriptors();
    }

    /** AOSP :13236 -- `(mExtendedFlags & EXTENDED_FLAG_FILTER_MISMATCH) != 0`.
     *  The system sets that extended flag when it delivers a broadcast whose
     *  intent did NOT match the receiver's declared IntentFilter (the
     *  U+ mismatch-logging path). Mac.apk's broadcast dispatch matches filters
     *  before delivering and never sets the flag, so a receiver here never
     *  gets a mismatching intent -- which is what false means. */
    public boolean isMismatchingFilter() { return false; }

    /** AOSP :12649 -- clears the "missing creator token" extended flag and the
     *  creator-token info, i.e. deliberately opts an intent OUT of the U+
     *  unsafe-intent-launch protection before handing it on. Both pieces of
     *  state are part of the creator-token mechanism, which this runtime does
     *  not implement (there is no cross-process intent delivery to protect),
     *  so there is nothing to clear. A no-op is the correct end state here,
     *  not a swallowed request: the postcondition AOSP guarantees -- the
     *  intent carries no launch-security protection -- already holds. */
    public void removeLaunchSecurityProtection() { }

    /** AOSP :8440 -- the PRE-1.0 URI format, kept for compatibility:
     *      <data>#action(A)categories(C1!C2)type(T)launchFlags(F)component(P!C)extras(Skey=v!ikey=1)
     *  This is a different grammar from toUri/parseUri's "#Intent;...;end"
     *  and parseUri does not accept it, so delegating would silently produce
     *  a plain ACTION_VIEW intent with the whole string as data. Ported from
     *  AOSP :8446 instead, including its URISyntaxException messages and its
     *  IMMUTABLE_FLAGS stripping.
     *
     *  The `flags` parameter of AOSP's private overload is not exposed (the
     *  public entry point passes 0), so the grant flags are always stripped
     *  here, which is the safe half of that branch. */
    @Deprecated
    public static Intent getIntentOld(String uri) throws java.net.URISyntaxException {
        return getIntentOld(uri, 0);
    }

    /** AOSP's private getIntentOld(String, int): the flags let parseUri's
     *  URI_ALLOW_UNSAFE keep the grant flags from a legacy fragment. */
    private static Intent getIntentOld(String uri, int uriFlags)
            throws java.net.URISyntaxException {
        Intent intent;
        int i = uri.lastIndexOf('#');
        if (i >= 0) {
            String action = null;
            final int intentFragmentStart = i;
            boolean isIntentFragment = false;
            i++;

            if (uri.regionMatches(i, "action(", 0, 7)) {
                isIntentFragment = true;
                i += 7;
                int j = uri.indexOf(')', i);
                action = uri.substring(i, j);
                i = j + 1;
            }

            intent = new Intent(action);

            if (uri.regionMatches(i, "categories(", 0, 11)) {
                isIntentFragment = true;
                i += 11;
                int j = uri.indexOf(')', i);
                while (i < j) {
                    int sep = uri.indexOf('!', i);
                    if (sep < 0 || sep > j) sep = j;
                    if (i < sep) intent.addCategory(uri.substring(i, sep));
                    i = sep + 1;
                }
                i = j + 1;
            }

            if (uri.regionMatches(i, "type(", 0, 5)) {
                isIntentFragment = true;
                i += 5;
                int j = uri.indexOf(')', i);
                // Assigned directly, NOT through setType(): AOSP writes
                // intent.mType and setType() clears the data field.
                intent.type = uri.substring(i, j);
                i = j + 1;
            }

            if (uri.regionMatches(i, "launchFlags(", 0, 12)) {
                isIntentFragment = true;
                i += 12;
                int j = uri.indexOf(')', i);
                int f = decodeUriInt(uri.substring(i, j));
                // AOSP IMMUTABLE_FLAGS (:7686) = FLAG_GRANT_READ_URI_PERMISSION
                // (0x1) | FLAG_GRANT_WRITE_URI_PERMISSION (0x2)
                // | FLAG_GRANT_PERSISTABLE_URI_PERMISSION (0x40)
                // | FLAG_GRANT_PREFIX_URI_PERMISSION (0x80) == 0xC3. Same
                // inlining (and same reason) as parseUri above.
                if ((uriFlags & URI_ALLOW_UNSAFE) == 0) {
                    f &= ~0xC3;
                }
                intent.flags = f;
                i = j + 1;
            }

            if (uri.regionMatches(i, "component(", 0, 10)) {
                isIntentFragment = true;
                i += 10;
                int j = uri.indexOf(')', i);
                int sep = uri.indexOf('!', i);
                if (sep >= 0 && sep < j) {
                    intent.component = new ComponentName(uri.substring(i, sep),
                                                         uri.substring(sep + 1, j));
                }
                i = j + 1;
            }

            if (uri.regionMatches(i, "extras(", 0, 7)) {
                isIntentFragment = true;
                i += 7;
                final int closeParen = uri.indexOf(')', i);
                if (closeParen == -1) {
                    throw new java.net.URISyntaxException(uri, "EXTRA missing trailing ')'", i);
                }
                while (i < closeParen) {
                    int j = uri.indexOf('=', i);
                    if (j <= i + 1 || i >= closeParen) {
                        throw new java.net.URISyntaxException(uri, "EXTRA missing '='", i);
                    }
                    char type = uri.charAt(i);
                    i++;
                    String key = uri.substring(i, j);
                    i = j + 1;

                    j = uri.indexOf('!', i);
                    if (j == -1 || j >= closeParen) j = closeParen;
                    if (i >= j) {
                        throw new java.net.URISyntaxException(uri, "EXTRA missing '!'", i);
                    }
                    String value = uri.substring(i, j);
                    i = j;

                    Bundle e = intent.ext();
                    try {
                        switch (type) {
                            case 'S': e.putString(key, Uri.decode(value)); break;
                            case 'B': e.putBoolean(key, Boolean.parseBoolean(value)); break;
                            case 'b': e.putByte(key, Byte.parseByte(value)); break;
                            case 'c': e.putChar(key, Uri.decode(value).charAt(0)); break;
                            case 'd': e.putDouble(key, Double.parseDouble(value)); break;
                            case 'f': e.putFloat(key, Float.parseFloat(value)); break;
                            case 'i': e.putInt(key, Integer.parseInt(value)); break;
                            case 'l': e.putLong(key, Long.parseLong(value)); break;
                            case 's': e.putShort(key, Short.parseShort(value)); break;
                            default:
                                throw new java.net.URISyntaxException(
                                        uri, "EXTRA has unknown type", i);
                        }
                    } catch (NumberFormatException nfe) {
                        throw new java.net.URISyntaxException(
                                uri, "EXTRA value can't be parsed", i);
                    }

                    char ch = uri.charAt(i);
                    if (ch == ')') break;
                    if (ch != '!') {
                        throw new java.net.URISyntaxException(uri, "EXTRA missing '!'", i);
                    }
                    i++;
                }
            }

            intent.data = isIntentFragment
                    ? Uri.parse(uri.substring(0, intentFragmentStart))
                    : Uri.parse(uri);

            if (intent.action == null) {
                // AOSP: "By default, if no action is specified, then use VIEW."
                intent.action = ACTION_VIEW;
            }
        } else {
            intent = new Intent(ACTION_VIEW, Uri.parse(uri));
        }
        return intent;
    }

    /** AOSP Intent.makeMainSelectorActivity: like makeMainActivity but
     *  targets the launcher via a selector Intent instead of an explicit
     *  component. */
    public static Intent makeMainSelectorActivity(String selectorAction, String selectorCategory) {
        Intent intent = new Intent(ACTION_MAIN);
        intent.addCategory(CATEGORY_LAUNCHER);
        Intent selector = new Intent();
        selector.setAction(selectorAction);
        selector.addCategory(selectorCategory);
        intent.setSelector(selector);
        return intent;
    }

    /**
     * AOSP Intent.fillIn: copy fields from `other` into this Intent, but
     * only where this Intent doesn't already define them (or fillInFlags
     * explicitly allows the overwrite via the matching FILL_IN_* bit).
     * Component and selector are special: they only ever copy across when
     * their FILL_IN_* bit is explicitly set, since otherwise the sender of
     * `other` could redirect this Intent somewhere its original creator
     * never intended (mirrors AOSP's own comment on this). Returns a
     * bitmask of the FILL_IN_* values that were actually changed.
     */
    public int fillIn(Intent other, int fillInFlags) {
        int changes = 0;
        if (other.action != null && (action == null || (fillInFlags & FILL_IN_ACTION) != 0)) {
            action = other.action;
            changes |= FILL_IN_ACTION;
        }
        if ((other.data != null || other.type != null)
                && ((data == null && type == null) || (fillInFlags & FILL_IN_DATA) != 0)) {
            data = other.data;
            type = other.type;
            changes |= FILL_IN_DATA;
        }
        if (other.identifier != null
                && (identifier == null || (fillInFlags & FILL_IN_IDENTIFIER) != 0)) {
            identifier = other.identifier;
            changes |= FILL_IN_IDENTIFIER;
        }
        if (other.categories != null
                && (categories == null || (fillInFlags & FILL_IN_CATEGORIES) != 0)) {
            categories = new HashSet<>(other.categories);
            changes |= FILL_IN_CATEGORIES;
        }
        if (other.targetPackage != null
                && (targetPackage == null || (fillInFlags & FILL_IN_PACKAGE) != 0)) {
            if (selector == null) {
                targetPackage = other.targetPackage;
                changes |= FILL_IN_PACKAGE;
            }
        }
        // Selector is special: only copied when explicitly allowed (same
        // reasoning as component, below).
        if (other.selector != null && (fillInFlags & FILL_IN_SELECTOR) != 0) {
            if (targetPackage == null) {
                selector = new Intent(other.selector);
                changes |= FILL_IN_SELECTOR;
            }
        }
        if (other.clipData != null
                && (clipData == null || (fillInFlags & FILL_IN_CLIP_DATA) != 0)) {
            clipData = other.clipData;
            changes |= FILL_IN_CLIP_DATA;
        }
        // Component is special: only copied when explicitly allowed, since
        // otherwise the sender could force the intent somewhere the
        // originator didn't intend.
        if (other.component != null && (fillInFlags & FILL_IN_COMPONENT) != 0) {
            component = other.component;
            changes |= FILL_IN_COMPONENT;
        }
        // AOSP ORs launch flags in unconditionally, not gated by any
        // FILL_IN_* bit and not reflected in the returned bitmask.
        this.flags |= other.flags;
        if (other.sourceBounds != null
                && (sourceBounds == null || (fillInFlags & FILL_IN_SOURCE_BOUNDS) != 0)) {
            sourceBounds = new Rect(other.sourceBounds);
            changes |= FILL_IN_SOURCE_BOUNDS;
        }
        // Extras merge unconditionally (own keys win over other's on
        // conflict), matching AOSP: this is not gated by any FILL_IN_* bit
        // because each top-level extras key is its own "field" per the
        // class doc above. A source with no extras must leave ours alone --
        // in particular it must not conjure an empty Bundle where there was
        // none, since `getExtras() != null` is load-bearing for callers.
        if (extras == null) {
            if (other.extras != null) extras = new Bundle(other.extras);
        } else if (other.extras != null) {
            Bundle newb = new Bundle(other.extras);
            newb.putAll(extras);
            extras = newb;
        }
        return changes;
    }

    /** @deprecated AOSP calls this with 0 flags; use toUri(int) instead. */
    @Deprecated
    public String toURI() { return toUri(0); }

    /**
     * AOSP Intent.toUri: encode this Intent as a URI string in the
     * documented URI_INTENT_SCHEME format (an "intent:" URI carrying an
     * "#Intent;...end" fragment), invertible via parseUri. Mirrors
     * frameworks/base/core/java/android/content/Intent.java's
     * toUri/toUriFragment/toUriInner.
     *
     * All three documented formats are produced: plain data, "intent:"
     * (URI_INTENT_SCHEME) and "android-app://" (URI_ANDROID_APP_SCHEME).
     */
    public String toUri(int uriFlags) {
        StringBuilder uri = new StringBuilder(128);
        if ((uriFlags & URI_ANDROID_APP_SCHEME) != 0) {
            // AOSP's android-app: form is "android-app://<pkg>[/<scheme>[/<authority><path>[?q][#f]]]"
            // followed by the usual "#Intent;...end" fragment for whatever the
            // URL itself could not express. The default action is MAIN when
            // there is no data scheme and VIEW when there is, and the default
            // package is the one already in the authority, so a plain
            // VIEW+data+package Intent encodes with an EMPTY fragment.
            if (targetPackage == null) {
                throw new IllegalArgumentException(
                        "Intent must include an explicit package name to build an android-app: "
                        + this);
            }
            uri.append("android-app://");
            uri.append(targetPackage);
            String appScheme = null;
            if (data != null) {
                appScheme = data.getScheme();
                if (appScheme != null) {
                    uri.append('/');
                    uri.append(appScheme);
                    String authority = data.getEncodedAuthority();
                    if (authority != null) {
                        uri.append('/');
                        uri.append(authority);
                        String path = data.getEncodedPath();
                        if (path != null) uri.append(path);
                        String queryParams = data.getEncodedQuery();
                        if (queryParams != null) {
                            uri.append('?');
                            uri.append(queryParams);
                        }
                        String fragment = data.getEncodedFragment();
                        if (fragment != null) {
                            uri.append('#');
                            uri.append(fragment);
                        }
                    }
                }
            }
            toUriFragment(uri, null,
                    appScheme == null ? Intent.ACTION_MAIN : Intent.ACTION_VIEW,
                    targetPackage, uriFlags);
            return uri.toString();
        }
        String scheme = null;
        if (data != null) {
            String d = data.toString();
            if ((uriFlags & URI_INTENT_SCHEME) != 0) {
                int n = d.length();
                for (int i = 0; i < n; i++) {
                    char c = d.charAt(i);
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') {
                        continue;
                    }
                    if (c == ':' && i > 0) {
                        // Valid scheme.
                        scheme = d.substring(0, i);
                        uri.append("intent:");
                        d = d.substring(i + 1);
                    }
                    // Either way (valid scheme found, or first non-scheme
                    // char with no ':'), stop scanning after this char.
                    break;
                }
            }
            uri.append(d);
        } else if ((uriFlags & URI_INTENT_SCHEME) != 0) {
            uri.append("intent:");
        }
        toUriFragment(uri, scheme, Intent.ACTION_VIEW, null, uriFlags);
        return uri.toString();
    }

    private void toUriFragment(StringBuilder uri, String scheme, String defAction,
            String defPackage, int uriFlags) {
        StringBuilder frag = new StringBuilder(128);
        toUriInner(frag, scheme, defAction, defPackage, uriFlags);
        if (selector != null) {
            frag.append("SEL;");
            // AOSP does not try to represent the selector's data part as a
            // URI; only its scheme (if any) round-trips.
            selector.toUriInner(frag,
                    selector.data != null ? selector.data.getScheme() : null,
                    null, null, uriFlags);
        }
        if (frag.length() > 0) {
            uri.append("#Intent;");
            uri.append(frag);
            uri.append("end");
        }
    }

    private void toUriInner(StringBuilder uri, String scheme, String defAction,
            String defPackage, int uriFlags) {
        // AOSP appends the scheme RAW here (only the other fields are
        // Uri.encode'd) -- a scheme is already restricted to URI-safe chars.
        if (scheme != null) {
            uri.append("scheme=").append(scheme).append(';');
        }
        if (action != null && !action.equals(defAction)) {
            uri.append("action=").append(Uri.encode(action)).append(';');
        }
        if (categories != null) {
            for (String cat : categories) {
                uri.append("category=").append(Uri.encode(cat)).append(';');
            }
        }
        if (type != null) {
            uri.append("type=").append(Uri.encode(type, "/")).append(';');
        }
        if (identifier != null) {
            uri.append("identifier=").append(Uri.encode(identifier, "/")).append(';');
        }
        if (this.flags != 0) {
            uri.append("launchFlags=0x").append(Integer.toHexString(this.flags)).append(';');
        }
        if (targetPackage != null && !targetPackage.equals(defPackage)) {
            uri.append("package=").append(Uri.encode(targetPackage)).append(';');
        }
        if (component != null) {
            uri.append("component=")
               .append(Uri.encode(component.flattenToShortString(), "/")).append(';');
        }
        if (sourceBounds != null) {
            // Inline equivalent of android.graphics.Rect.flattenToString's
            // "left top right bottom" format (graphics/java/android/graphics/Rect.java)
            // -- kept local since this pass only owns Intent.java, not Rect.java.
            uri.append("sourceBounds=").append(Uri.encode(
                    sourceBounds.left + " " + sourceBounds.top + " "
                    + sourceBounds.right + " " + sourceBounds.bottom)).append(';');
        }
        if (extras == null) return;
        // AOSP walks mExtras in ArrayMap order, and ArrayMap keeps its keys
        // sorted by hashCode ascending -- so an Intent with extras "s" then "n"
        // encodes as "i.n=...;S.s=...", NOT in insertion order. Our Bundle is
        // backed by a plain HashMap (android/os/BaseBundle.java), whose keySet
        // order is bucket order and does not agree. Sorting by hashCode here
        // reproduces AOSP's observable byte-for-byte URI, which matters because
        // apps string-compare toUri output (deep-link dedup, PendingIntent
        // keys). DELETE THIS SORT once BaseBundle is ArrayMap-ordered -- it is
        // idempotent then, not wrong, but it belongs in the Bundle.
        java.util.List<String> keys = new java.util.ArrayList<>(extras.keySet());
        keys.sort(java.util.Comparator.comparingInt(
                k -> k == null ? 0 : k.hashCode()));
        for (String key : keys) {
            Object value = extras.get(key);
            char entryType =
                    value instanceof String    ? 'S' :
                    value instanceof Boolean   ? 'B' :
                    value instanceof Byte      ? 'b' :
                    value instanceof Character ? 'c' :
                    value instanceof Double    ? 'd' :
                    value instanceof Float     ? 'f' :
                    value instanceof Integer   ? 'i' :
                    value instanceof Long      ? 'l' :
                    value instanceof Short     ? 's' :
                    '\0';
            if (entryType != '\0') {
                uri.append(entryType).append('.').append(Uri.encode(key)).append('=')
                   .append(Uri.encode(value.toString())).append(';');
            }
        }
    }

    /**
     * AOSP Intent.parseUri: inverse of toUri -- create an Intent from a URI
     * that may encode action/categories/extras (if produced by toUri), or
     * else is treated as a plain data URI with ACTION_VIEW. Mirrors
     * frameworks/base/core/java/android/content/Intent.java's
     * parseUri/parseUriInternal.
     *
     * All three documented formats are parsed, including the pre-1.0
     * "#action(...)" legacy fragment (getIntentOld). The android-app: branch
     * is AOSP's own control flow, quirks included: the authority is only
     * recognised when another '/' follows it.
     */
    /** AOSP Intent.getIntent: parseUri with 0 flags. Deprecated on AOSP but
     *  still linked by older SDK code and by WebView URL-handling shims. */
    @Deprecated
    public static Intent getIntent(String uri) throws java.net.URISyntaxException {
        return parseUri(uri, 0);
    }

    public static Intent parseUri(String uri, int uriFlags) throws java.net.URISyntaxException {
        int i = 0;
        try {
            final boolean androidApp = uri.startsWith("android-app:");
            if ((uriFlags & (URI_INTENT_SCHEME | URI_ANDROID_APP_SCHEME)) != 0) {
                if (!uri.startsWith("intent:") && !androidApp) {
                    Intent intent = new Intent(ACTION_VIEW);
                    try {
                        intent.setData(Uri.parse(uri));
                    } catch (IllegalArgumentException e) {
                        throw new java.net.URISyntaxException(uri, e.getMessage());
                    }
                    return intent;
                }
            }

            i = uri.lastIndexOf('#');
            if (i == -1) {
                if (!androidApp) {
                    return new Intent(ACTION_VIEW, Uri.parse(uri));
                }
            } else if (!uri.startsWith("#Intent;", i)) {
                if (!androidApp) {
                    // Legacy pre-1.0 "#action(...)" fragment, as AOSP does.
                    return getIntentOld(uri, uriFlags);
                }
                i = -1;
            }

            Intent intent = new Intent(ACTION_VIEW);
            Intent baseIntent = intent;
            boolean inSelector = false;
            boolean explicitAction = false;

            String scheme = null;
            String data = i >= 0 ? uri.substring(0, i) : uri;
            if (i >= 0) {
                i += 8; // length of "#Intent;"
            }

            while (i >= 0 && !uri.startsWith("end", i)) {
                int eq = uri.indexOf('=', i);
                if (eq < 0) eq = i - 1;
                int semi = uri.indexOf(';', i);
                if (semi < 0) {
                    throw new java.net.URISyntaxException(uri, "uri end not found");
                }
                String value = eq < semi ? Uri.decode(uri.substring(eq + 1, semi)) : "";

                if (uri.startsWith("action=", i)) {
                    intent.setAction(value);
                    if (!inSelector) explicitAction = true;
                } else if (uri.startsWith("category=", i)) {
                    intent.addCategory(value);
                } else if (uri.startsWith("type=", i)) {
                    // AOSP assigns mType/mData DIRECTLY here rather than going
                    // through setType/setData, precisely because those two
                    // clear each other -- an "intent:...;type=text/plain;end"
                    // URI whose data part is also present must keep both.
                    intent.type = value;
                } else if (uri.startsWith("identifier=", i)) {
                    intent.identifier = value;
                } else if (uri.startsWith("launchFlags=", i)) {
                    int f = decodeUriInt(value);
                    if ((uriFlags & URI_ALLOW_UNSAFE) == 0) {
                        // AOSP IMMUTABLE_FLAGS = FLAG_GRANT_READ_URI_PERMISSION(0x1)
                        // | FLAG_GRANT_WRITE_URI_PERMISSION(0x2)
                        // | FLAG_GRANT_PERSISTABLE_URI_PERMISSION(0x40)
                        // | FLAG_GRANT_PREFIX_URI_PERMISSION(0x80), values from
                        // frameworks/base/core/java/android/content/Intent.java.
                        // Inlined (not declared as named constants) since none of
                        // those grant-flag constants exist on this tree's Intent
                        // yet; stripping them here still prevents an untrusted
                        // intent: URI from smuggling in a URI-permission grant.
                        f &= ~0xC3;
                    }
                    intent.setFlags(f);
                } else if (uri.startsWith("package=", i)) {
                    intent.setPackage(value);
                } else if (uri.startsWith("component=", i)) {
                    intent.setComponent(ComponentName.unflattenFromString(value));
                } else if (uri.startsWith("scheme=", i)) {
                    if (inSelector) {
                        // Direct field write, same reason as type= above.
                        intent.data = Uri.parse(value + ":");
                    } else {
                        scheme = value;
                    }
                } else if (uri.startsWith("sourceBounds=", i)) {
                    intent.setSourceBounds(parseUriRect(value));
                } else if (semi == (i + 3) && uri.startsWith("SEL", i)) {
                    intent = new Intent();
                    inSelector = true;
                } else {
                    String key = Uri.decode(uri.substring(i + 2, eq));
                    char t = uri.charAt(i);
                    if      (t == 'S') intent.putExtra(key, value);
                    else if (t == 'B') intent.putExtra(key, Boolean.parseBoolean(value));
                    else if (t == 'b') intent.putExtra(key, Byte.parseByte(value));
                    else if (t == 'c') intent.putExtra(key, value.charAt(0));
                    else if (t == 'd') intent.putExtra(key, Double.parseDouble(value));
                    else if (t == 'f') intent.putExtra(key, Float.parseFloat(value));
                    else if (t == 'i') intent.putExtra(key, Integer.parseInt(value));
                    else if (t == 'l') intent.putExtra(key, Long.parseLong(value));
                    else if (t == 's') intent.putExtra(key, Short.parseShort(value));
                    else throw new java.net.URISyntaxException(uri, "unknown EXTRA type", i);
                }

                i = semi + 1;
            }

            if (inSelector) {
                if (baseIntent.getPackage() == null) {
                    baseIntent.setSelector(intent);
                }
                intent = baseIntent;
            }

            if (data.startsWith("android-app:")) {
                if (data.charAt(12) == '/' && data.charAt(13) == '/') {
                    // Correctly formed android-app, first part is package name.
                    // (AOSP wraps the pieces in Uri.decodeIfNeeded, which only
                    // decodes under its encode_app_intent aconfig flag; with the
                    // flag off it returns its argument, so we do the same.)
                    int end = data.indexOf('/', 14);
                    if (end < 0) {
                        // All we have is a package name.
                        intent.targetPackage = data.substring(14);
                        if (!explicitAction) {
                            intent.setAction(ACTION_MAIN);
                        }
                        data = "";
                    } else {
                        // Target the Intent at the given package name always.
                        String authority = null;
                        intent.targetPackage = data.substring(14, end);
                        int newEnd;
                        if ((end + 1) < data.length()) {
                            if ((newEnd = data.indexOf('/', end + 1)) >= 0) {
                                // Found a scheme, remember it.
                                scheme = data.substring(end + 1, newEnd);
                                end = newEnd;
                                if (end < data.length()
                                        && (newEnd = data.indexOf('/', end + 1)) >= 0) {
                                    // Found an authority, remember it.
                                    authority = data.substring(end + 1, newEnd);
                                    end = newEnd;
                                }
                            } else {
                                // All we have is a scheme.
                                scheme = data.substring(end + 1);
                            }
                        }
                        if (scheme == null) {
                            // If there was no scheme, then this just targets the package.
                            if (!explicitAction) {
                                intent.setAction(ACTION_MAIN);
                            }
                            data = "";
                        } else if (authority == null) {
                            data = scheme + ":";
                        } else {
                            data = scheme + "://" + authority + data.substring(end);
                        }
                    }
                } else {
                    data = "";
                }
            } else if (data.startsWith("intent:")) {
                data = data.substring(7);
                if (scheme != null) {
                    data = scheme + ':' + data;
                }
            }
            if (data.length() > 0) {
                try {
                    // Direct field write: setData() would clear a type= that
                    // the fragment already parsed (AOSP does the same).
                    intent.data = Uri.parse(data);
                } catch (IllegalArgumentException e) {
                    throw new java.net.URISyntaxException(uri, e.getMessage());
                }
            }

            return intent;
        } catch (IndexOutOfBoundsException e) {
            throw new java.net.URISyntaxException(uri, "illegal Intent URI format", i);
        }
    }

    /** AOSP Intent.decodeInteger (private helper): parses a launchFlags
     *  value out of toUriInner's "0x" + Integer.toHexString(...) format,
     *  falling back to an unsigned reparse for values >= 0x80000000 that
     *  Integer.decode rejects as out of signed-int range. Matches AOSP
     *  exactly: on failure this rethrows the original NumberFormatException
     *  uncaught (parseUri's own catch only handles IndexOutOfBoundsException,
     *  so a malformed launchFlags value surfaces as NumberFormatException,
     *  not URISyntaxException -- verified against
     *  frameworks/base/core/java/android/content/Intent.java's
     *  decodeInteger). */
    private static int decodeUriInt(String value) {
        try {
            return Integer.decode(value);
        } catch (NumberFormatException e) {
            if (value != null && value.startsWith("0x")) {
                try {
                    return Integer.parseUnsignedInt(value.substring(2), 16);
                } catch (NumberFormatException ignored) {
                    // ignored, throw the original exception
                }
            }
            throw e;
        }
    }

    /** Local inline equivalent of android.graphics.Rect.unflattenFromString
     *  ("left top right bottom", AOSP's exact flattenToString format -- see
     *  graphics/java/android/graphics/Rect.java). Kept here rather than on
     *  Rect itself since this pass only owns Intent.java; returns null on
     *  anything that doesn't match, same as AOSP's version. */
    private static Rect parseUriRect(String value) {
        if (value == null) return null;
        String[] p = value.split(" ");
        if (p.length != 4) return null;
        try {
            return new Rect(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                             Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** AOSP Intent.toString: "Intent { " + toShortString(secure, comp, extras)
     *  + " }". Apps log Intents constantly and some parse the result in tests
     *  and crash reporters; the default Object.toString we used before printed
     *  "android.content.Intent@ad8a80f", which carries no information and is
     *  not even stable across runs. */
    @Override public String toString() {
        StringBuilder b = new StringBuilder(128);
        b.append("Intent { ");
        toShortString(b, true, true, true, false);
        b.append(" }");
        return b.toString();
    }

    /** AOSP Intent.toShortString(boolean,boolean,boolean,boolean). */
    public String toShortString(boolean secure, boolean comp, boolean extras, boolean clip) {
        StringBuilder b = new StringBuilder(128);
        toShortString(b, secure, comp, extras, clip);
        return b.toString();
    }

    /** AOSP Intent.toShortString(StringBuilder,...): the field-by-field
     *  rendering shared by toString and the system's dumpsys output. Field
     *  ORDER and the exact key spellings ("act=", "cat=[..]", "dat=", "typ=",
     *  "id=", "flg=0x", "pkg=", "cmp=", "bnds=", "(has extras)") are part of
     *  the observable contract. */
    public void toShortString(StringBuilder b, boolean secure, boolean comp, boolean extras,
            boolean clip) {
        boolean first = true;
        if (action != null) {
            b.append("act=").append(action);
            first = false;
        }
        if (categories != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("cat=[");
            boolean firstCat = true;
            for (String cat : categories) {
                if (!firstCat) b.append(',');
                firstCat = false;
                b.append(cat);
            }
            b.append("]");
        }
        if (data != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("dat=").append(secure ? toSafeString(data) : data.toString());
        }
        if (type != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("typ=").append(type);
        }
        if (identifier != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("id=").append(identifier);
        }
        if (flags != 0) {
            if (!first) b.append(' ');
            first = false;
            b.append("flg=0x").append(Integer.toHexString(flags));
        }
        if (targetPackage != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("pkg=").append(targetPackage);
        }
        if (comp && component != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("cmp=").append(component.flattenToShortString());
        }
        if (sourceBounds != null) {
            if (!first) b.append(' ');
            first = false;
            // AOSP calls Rect.toShortString(), whose format is "[l,t][r,b]".
            b.append("bnds=[").append(sourceBounds.left).append(',').append(sourceBounds.top)
             .append("][").append(sourceBounds.right).append(',').append(sourceBounds.bottom)
             .append(']');
        }
        if (clipData != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("clip={}");
        }
        if (extras && this.extras != null) {
            if (!first) b.append(' ');
            first = false;
            b.append("(has extras)");
        }
        if (selector != null) {
            b.append(" sel=");
            selector.toShortString(b, secure, comp, extras, clip);
            b.append("}");
        }
    }

    /** Local port of Uri.toSafeString (frameworks/base .../net/Uri.java), the
     *  PII-redacting rendering toString() uses for the data Uri: credential-ish
     *  schemes get their scheme-specific part x'd out, and http/https/ftp/rtsp
     *  keep only scheme://host[:port]/... . It lives here rather than on Uri
     *  because toSafeString is @hide (not part of the SDK surface an app can
     *  call) and android/net/Uri.java is owned by a different pass; move it
     *  there if Uri ever grows the @hide method. */
    private static String toSafeString(Uri u) {
        String scheme = u.getScheme();
        String ssp = u.getSchemeSpecificPart();
        if (scheme != null) {
            if (scheme.equalsIgnoreCase("tel") || scheme.equalsIgnoreCase("sip")
                    || scheme.equalsIgnoreCase("sms") || scheme.equalsIgnoreCase("smsto")
                    || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("nfc")) {
                StringBuilder sb = new StringBuilder(64);
                sb.append(scheme).append(':');
                if (ssp != null) {
                    for (int i = 0; i < ssp.length(); i++) {
                        char c = ssp.charAt(i);
                        sb.append((c == '-' || c == '@' || c == '.') ? c : 'x');
                    }
                }
                return sb.toString();
            } else if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("ftp") || scheme.equalsIgnoreCase("rtsp")) {
                ssp = "//" + ((u.getHost() != null) ? u.getHost() : "")
                        + ((u.getPort() != -1) ? (":" + u.getPort()) : "")
                        + "/...";
            }
        }
        StringBuilder sb = new StringBuilder(64);
        if (scheme != null) sb.append(scheme).append(':');
        if (ssp != null) sb.append(ssp);
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Parcelable. Field order mirrors frameworks/base/core/java/android/
    // content/Intent.java:10815-10855 (writeToParcel) and :10873-10906
    // (readFromParcel) EXACTLY, because the two halves only agree if they walk
    // the same sequence -- a single inserted or dropped field turns every
    // later value into garbage rather than into a null.
    //
    // ⚠ Until v1.0.940 this pair was `writeToParcel(...) {}` plus a CREATOR
    // that returned `new Intent()`. That was harmless only while android.os
    // .Parcel faked marshalling by stashing the live object in a side list:
    // the empty body wrote nothing and the reader got the SAME instance back.
    // Now that Parcel marshals for real, an empty body is silent data loss --
    // an Intent inside a Bundle that crosses a Parcel would arrive with no
    // action, no extras and no component, and nothing would say so. (Parcel
    // .writeParcelableBody measures the bytes written and falls back to a live
    // reference on zero, warning once per class; giving this class a real body
    // is what retires that fallback for Intent, with no edit to Parcel.)
    //
    // Two deliberate divergences from the AOSP text, both marked at their site:
    //   1. AOSP uses writeString8/readString8 (the UTF-8 pair). This tree's
    //      Parcel has only writeString (UTF-16 + NUL + pad-to-4), so the BYTES
    //      differ from a real device while the STRUCTURE does not. That is
    //      allowed: Parcel.marshall's own javadoc disclaims cross-version byte
    //      compatibility ("must not be placed in any kind of persistent
    //      storage"), and there is no second process here to hand bytes to.
    //      Self-consistency -- our writer paired with our reader -- is the real
    //      requirement, and it holds.
    //   2. mContentUserHint has no field on this tree; see its slot below.
    private static final int CONTENT_USER_HINT_ABSENT = -2;

    @Override public int describeContents() { return 0; }

    /** The parameter is named parcelableFlags rather than AOSP's `flags`
     *  because this class has its OWN `flags` field (the FLAG_ACTIVITY_*
     *  bitmask) and the two are unrelated -- AOSP writes mFlags while
     *  forwarding the parameter to its sub-writers, and shadowing here would
     *  make that swap invisible to a reader. */
    @Override public void writeToParcel(Parcel out, int parcelableFlags) {
        out.writeString(action);
        // Uri's null-tolerant STATIC writer: the instance writeToParcel cannot
        // encode a null, and a null data Uri is the common case.
        Uri.writeToParcel(out, data);
        out.writeString(type);
        out.writeString(identifier);
        out.writeInt(this.flags);
        out.writeString(targetPackage);
        // AOSP calls the static ComponentName.writeToParcel(mComponent, out),
        // which is null-tolerant: a null component is a single null string, a
        // present one is (package, class). ComponentName.java on this tree
        // declares only the instance writer plus a CREATOR that reads two
        // strings unconditionally, so the static form is inlined here rather
        // than edited into a file this pass does not own. The wire shape is
        // identical to AOSP's.
        if (component != null) {
            out.writeString(component.getPackageName());
            out.writeString(component.getClassName());
        } else {
            out.writeString(null);
        }
        if (sourceBounds != null) {
            out.writeInt(1);
            sourceBounds.writeToParcel(out, parcelableFlags);
        } else {
            out.writeInt(0);
        }
        if (categories != null) {
            out.writeInt(categories.size());
            for (String cat : categories) out.writeString(cat);
        } else {
            out.writeInt(0);
        }
        if (selector != null) {
            out.writeInt(1);
            selector.writeToParcel(out, parcelableFlags);
        } else {
            out.writeInt(0);
        }
        if (clipData != null) {
            out.writeInt(1);
            clipData.writeToParcel(out, parcelableFlags);
        } else {
            out.writeInt(0);
        }
        // mContentUserHint slot. AOSP carries a multi-user hint here saying
        // which user's content the data Uri belongs to; this tree has no field
        // for it because Mac.apk is single-user and nothing can set or read
        // one. Writing the ABSENT marker rather than skipping the slot keeps
        // the wire layout AOSP-shaped, and -2 is AOSP's own initial value
        // (UserHandle.USER_CURRENT), so a future session that adds the field
        // gets its default back from an old-format parcel instead of a 0 that
        // means "user zero".
        // REMOVAL CONDITION: when Intent grows mContentUserHint plus
        // setContentUserHint/getContentUserHint, marshal the real field here
        // and delete this constant.
        out.writeInt(CONTENT_USER_HINT_ABSENT);
        // Null extras stay null: writeBundle writes a bare -1 and readBundle
        // answers null, which preserves the `getExtras() != null` guard that
        // this file's own class comment calls out as load-bearing.
        out.writeBundle(extras);
    }

    /** AOSP Intent.readFromParcel: public, and the inverse of writeToParcel
     *  field for field. Guest SavedState-style code calls it directly on an
     *  Intent it already holds, so it is part of the API surface and not just
     *  a helper for the CREATOR. */
    public void readFromParcel(Parcel in) {
        setAction(in.readString());
        data = Uri.CREATOR.createFromParcel(in);
        type = in.readString();
        identifier = in.readString();
        flags = in.readInt();
        targetPackage = in.readString();
        // Mirror of the inlined static ComponentName writer above.
        String cmpPkg = in.readString();
        component = (cmpPkg != null) ? new ComponentName(cmpPkg, in.readString()) : null;

        sourceBounds = (in.readInt() != 0) ? Rect.CREATOR.createFromParcel(in) : null;

        int n = in.readInt();
        if (n > 0) {
            categories = new HashSet<>();
            for (int i = 0; i < n; i++) categories.add(in.readString());
        } else {
            // AOSP nulls the set rather than leaving an empty one, which keeps
            // getCategories() != null an accurate "carries categories" test.
            categories = null;
        }

        // Assigned DIRECTLY, not through setSelector: the package was read a
        // few lines up, and setSelector throws IllegalArgumentException when a
        // package is already set. AOSP's readFromParcel assigns mSelector
        // directly for the same reason, so an Intent that legitimately carries
        // both (fillIn can produce one) round-trips instead of throwing.
        selector = (in.readInt() != 0) ? intentFromParcel(in) : null;

        clipData = (in.readInt() != 0) ? ClipData.CREATOR.createFromParcel(in) : null;
        in.readInt();   // mContentUserHint slot, see CONTENT_USER_HINT_ABSENT
        extras = in.readBundle();
    }

    /** AOSP's `protected Intent(Parcel in)`, spelled as a factory so the
     *  selector recursion above reads the same as AOSP's `new Intent(in)`
     *  without adding a public constructor overload that AOSP marks @hide. */
    private static Intent intentFromParcel(Parcel in) {
        Intent i = new Intent();
        i.readFromParcel(in);
        return i;
    }

    public static final Creator<Intent> CREATOR = new Creator<Intent>() {
        @Override public Intent createFromParcel(Parcel source) { return intentFromParcel(source); }
        @Override public Intent[] newArray(int size) { return new Intent[size]; }
    };

    /**
     * The icon half of the legacy EXTRA_SHORTCUT_ICON_RESOURCE payload: a
     * package name plus a fully-qualified resource name ("com.foo:drawable/ic").
     * Launcher-shortcut creation still builds one via fromContext even on
     * modern Android, so the class must exist and round-trip even though
     * Mac.apk has no home screen to place the shortcut on.
     *
     * Source: frameworks/base/core/java/android/content/Intent.java
     * (nested class ShortcutIconResource)
     */
    public static class ShortcutIconResource implements Parcelable {
        public String packageName;
        public String resourceName;

        public ShortcutIconResource() { }

        public static ShortcutIconResource fromContext(Context context, int resourceId) {
            ShortcutIconResource icon = new ShortcutIconResource();
            icon.packageName = context.getPackageName();
            android.content.res.Resources resources = context.getResources();
            // AOSP builds "<pkg>:<type>/<entry>" from the Resources tables; if
            // the id cannot be resolved we still return a well-formed object
            // rather than throwing, since the caller only ships it onward.
            String name = null;
            if (resources != null) {
                try { name = resources.getResourceName(resourceId); } catch (Throwable ignored) { }
            }
            icon.resourceName = name != null ? name : String.valueOf(resourceId);
            return icon;
        }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(packageName);
            dest.writeString(resourceName);
        }

        @Override public String toString() { return resourceName; }

        public static final Creator<ShortcutIconResource> CREATOR =
                new Creator<ShortcutIconResource>() {
            @Override public ShortcutIconResource createFromParcel(Parcel source) {
                ShortcutIconResource icon = new ShortcutIconResource();
                icon.packageName = source.readString();
                icon.resourceName = source.readString();
                return icon;
            }
            @Override public ShortcutIconResource[] newArray(int size) {
                return new ShortcutIconResource[size];
            }
        };
    }

    /** AOSP Intent.createChooser(Intent, CharSequence): STATIC, and delegates
     *  to the 3-arg form with a null sender. It was declared here as an
     *  INSTANCE method that returned its argument unchanged, which is a
     *  different method descriptor -- so the `invokestatic` every real share
     *  sheet compiles to could not bind to it at all and threw
     *  IncompatibleClassChangeError before the chooser was ever built. */
    public static Intent createChooser(Intent target, CharSequence title) {
        return createChooser(target, title, null);
    }

    public static Intent createChooser(Intent target, CharSequence title, android.content.IntentSender sender) {
        Intent intent = new Intent(ACTION_CHOOSER);
        intent.putExtra(EXTRA_INTENT, target);
        if (title != null) intent.putExtra(EXTRA_TITLE, title);
        return intent;
    }
    /** AOSP core/java/android/content/Intent.java:11791 -- wraps an Intent so
     *  it can be used as a HashMap KEY under filterEquals/filterHashCode
     *  semantics (action + data + type + identifier + package + component +
     *  categories) instead of Object identity. The hash is computed ONCE at
     *  construction, exactly as upstream, so mutating the wrapped Intent
     *  afterwards leaves a stale hash -- that is AOSP's behaviour and callers
     *  rely on the immutability contract it implies. */
    public static final class FilterComparison {
        private final Intent mIntent;
        private final int mHashCode;

        public FilterComparison(Intent intent) {
            mIntent = intent;
            mHashCode = intent.filterHashCode();
        }

        public Intent getIntent() { return mIntent; }

        @Override public boolean equals(Object obj) {
            if (obj instanceof FilterComparison) {
                Intent other = ((FilterComparison) obj).mIntent;
                return mIntent.filterEquals(other);
            }
            return false;
        }

        @Override public int hashCode() { return mHashCode; }
    }

}

