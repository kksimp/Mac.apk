// Lightweight AndroidManifest.xml reader.
//
// We don't try to model the full schema; the boot path needs:
//   * package name (root <manifest android:package="...">)
//   * application name (optional <application android:name="...">)
//   * launchable activity name (the <activity> with an intent-filter
//     declaring action MAIN + category LAUNCHER)
//   * minSdkVersion / targetSdkVersion (optional, for diagnostics)
// Everything else is ignored. apktool decoded the binary AXML into
// plain XML for us at unpack time.
package com.macapk.loader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;

// AOSP fidelity: the deprecation usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("deprecation")
public final class Manifest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** An `android:`-namespaced attribute RAW, or NULL when the element does
     *  not declare it at all -- so a present-but-empty attribute comes back as
     *  "", NOT null. Distinct on purpose from the older attrOrNull() further
     *  down this file, which trims and collapses empty to null; that one is
     *  right for attributes where blank means absent, and WRONG here.
     *
     *  Load-bearing distinction: AOSP's manifest parsing repeatedly branches on
     *  ABSENT (null) vs PRESENT-BUT-EMPTY (""), and the two mean different
     *  things. For a <provider>, `android:readPermission` absent means "fall
     *  back to android:permission, then to <application android:permission>",
     *  while `android:readPermission=""` means "explicitly no read permission"
     *  and stops the fallback chain at null (PackageParser.java:5114-5122).
     *  org.w3c.dom's getAttributeNS() collapses both to "", so every site that
     *  needs the difference must go through hasAttributeNS() -- which is what
     *  this does. */
    private static String attrRawOrNull(org.w3c.dom.Element e, String name) {
        if (e == null) return null;
        if (!e.hasAttributeNS(ANDROID_NS, name)) return null;
        return e.getAttributeNS(ANDROID_NS, name);
    }
    private static final String TAG = "Mac.apk.Manifest";

    /** MACAPK_TARGETSDK_ZERO (default ON). A manifest with NO <uses-sdk>
     *  reports targetSdkVersion 0, AOSP's DEFAULT_TARGET_SDK_VERSION, instead
     *  of the 1 this parser used to synthesise; and a process whose manifest
     *  says 0 treats a 0 read as that REAL value rather than as "not resolved
     *  yet" (android.app.Application.macapkTargetSdkKnown). =0 restores the
     *  pre-change path exactly: the old synthesis below, and every guard back
     *  to its plain `> 0` test. Research, the per-site audit and the corpus
     *  reach: docs/targetsdk-zero-default-and-guard-audit_2026-09-26.md. */
    public static final boolean TARGETSDK_ZERO =
        !"0".equals(System.getenv("MACAPK_TARGETSDK_ZERO"));
    /** MACAPK_MANIFEST_EXPORTED_DEFAULT (v1.0.1652, default ON): an activity /
     *  alias / receiver with no android:exported is exported iff it has an
     *  intent-filter, as AOSP ParsedActivityUtils :486-498. See parseActivityLike;
     *  docs/cross-app-launch-and-implicit-start-design_2026-09-26.md stage 0. */
    static final boolean EXPORTED_DEFAULT =
        !"0".equals(System.getenv("MACAPK_MANIFEST_EXPORTED_DEFAULT"));
    /** A <provider> with no android:exported is exported iff the app targets
     *  API 16 or lower (ParsedProviderUtils, android14 :86-93:
     *  `sa.getBoolean(..._exported, targetSdkVersion < JELLY_BEAN_MR1)`).
     *  =0 restores the old answer (absent -> not exported) for every app.
     *  v1.0.1653. */
    static final boolean PROVIDER_EXPORTED_LEGACY =
        !"0".equals(System.getenv("MACAPK_MANIFEST_PROVIDER_EXPORTED_LEGACY"));
    /** ParsingUtils.DEFAULT_MIN_SDK_VERSION / DEFAULT_TARGET_SDK_VERSION
     *  (android14 services/core/.../pm/pkg/parsing/ParsingUtils.java:52,54),
     *  also PackageParser.java:178-179. PackageImpl initialises its fields to
     *  these (:353, :367), so they are what a package with no <uses-sdk> keeps. */
    static final int DEFAULT_MIN_SDK_VERSION = 1;
    static final int DEFAULT_TARGET_SDK_VERSION = 0;

    public final String packageName;
    public final String applicationClass;

    /** <application android:appComponentFactory="...">. androidx.core
     *  auto-merges androidx.core.app.CoreComponentFactory into virtually every
     *  AndroidX app's manifest, so this is commonly non-null on the fleet.
     *  android.app.LoadedApk constructs it and routes component construction
     *  through it, exactly as AOSP does. null when the app declares none. */
    public final String appComponentFactory;
    public final String launchableActivity;
    /** The category of the intent-filter launchableActivity was found by:
     *  android.intent.category.LAUNCHER, or LEANBACK_LAUNCHER for an app that
     *  declares no LAUNCHER entry at all (an Android TV-only app). Boot puts it
     *  on the launch Intent, the way the TV launcher's
     *  getLeanbackLaunchIntentForPackage intent carries it. */
    public String launchableCategory = "android.intent.category.LAUNCHER";
    /** android:targetSdkVersion, defaulted the way AOSP's parser defaults it:
     *  absent attribute inside <uses-sdk> -> minSdkVersion; NO <uses-sdk> at all
     *  -> 0 (DEFAULT_TARGET_SDK_VERSION), which AOSP treats as older than every
     *  level, not as "unknown" (MACAPK_TARGETSDK_ZERO; =0 synthesises 1). */
    public final int    targetSdk;
    /** android:minSdkVersion from <uses-sdk>. 0 when the manifest
     *  doesn't declare one. Plumbed into ApplicationInfo.minSdkVersion
     *  so engines (Unity reads it via JNI at native init and logs
     *  "Min API Level") see the real value instead of 0 -- a 0 here
     *  drives Unity down a legacy code path whose result is null and
     *  crashes strlen. AOSP PackageParser reads the same attribute. */
    public final int    minSdk;
    /** android:versionCode from the root <manifest> tag. 0 when absent,
     *  matching AOSP PackageParser. Plumbed into
     *  PackageInfo.versionCode / longVersionCode. */
    public final int    versionCode;
    /** android:versionName from the root <manifest> tag, SANITIZED to
     *  [A-Za-z0-9._-] -- used to build per-version data dirs so two
     *  versions of the same APK don't overwrite each other's save files
     *  / extracted assets. Empty string if the manifest doesn't declare
     *  one. */
    public final String versionName;
    /** android:versionName UNSANITIZED -- the exact string the manifest
     *  declares (e.g. "7.11.1", or "1.43.0 (beta)"). This is the value
     *  PackageInfo.versionName must report (callers like Unity log it
     *  verbatim). Empty string if absent. */
    public final String versionNameRaw;
    /** Key/value pairs from every <meta-data android:name="..."
     *  android:value="..."/> under <application>. Engines like axmol /
     *  cocos2d-x v4 read `android.app.lib_name` from here in
     *  onLoadNativeLibraries to decide which .so to System.loadLibrary
     *  -- if metaData is missing the activity throws an NPE before
     *  loading its main native blob. */
    public final java.util.Map<String, String> applicationMetaData;
    /** Parallel ANDROID-TYPE map for applicationMetaData: name ->
     *  "boolean" / "int" / "float" / "reference" -- present ONLY for
     *  entries whose binary-manifest value was typed (the apktool
     *  TEXT fallback cannot know types, so this stays empty there
     *  and Bundle typing falls back to the legacy heuristics). */
    public final java.util.Map<String, String> applicationMetaDataTypes;

    /** v1.0.128: android:theme on `<application>`, e.g.
     *  "@style/Theme.App" or bare "Theme.App". Stripped of any
     *  `@style/` prefix at parse time so ResourceIndex can look the
     *  style up by bare name. Null when the manifest doesn't declare
     *  one (rare; defaults to AOSP's `Theme.DeviceDefault`).
     *
     *  Used by Boot to seed ResourceIndex's application-theme cascade
     *  so chrome painters can resolve `?attr/colorSurfaceContainerHigh`
     *  etc. against the APK's declared Material3 tokens rather than
     *  Material's baseline defaults. Generic across any APK that
     *  declares a theme via <application>. */
    public final String applicationTheme;
    /** `<application android:label>` as WRITTEN ("@string/app_name" or a literal).
     *  Kept raw, exactly like the per-component labels, so the resource ref is
     *  resolved later against the app's own Resources rather than at parse time.
     *
     *  Load-bearing because AOSP's ComponentInfo.loadLabel falls back to the
     *  APPLICATION's label when a component declares none -- and most activities
     *  declare none. Without it, an activity's title resolves to its CLASS NAME:
     *  opensudoku's action bar read "TitleScreenActivity" against the emulator's
     *  "Open Sudoku" (2026-08-02). May be null when <application> has no label. */
    public final String applicationLabel;
    /** `<application android:icon>` as WRITTEN: "@mipmap/ic_launcher" from a
     *  text decode, or the compiled reference "@0x7f0800e3" from the binary
     *  manifest (v1.0.1590 -- the manifest is read from the apk, and the
     *  launcher-icon writer resolves either spelling). Null when absent. */
    public String applicationIconRaw;

    /** `<application android:requestLegacyExternalStorage>` and
     *  `android:preserveLegacyExternalStorage` AS WRITTEN: "true"/"false" from
     *  either decode, or a resource reference ("@bool/x", "@0x7f...") that
     *  SpecialAccessStore.manifestBool resolves. Null when absent, which is NOT
     *  the same as false: AOSP defaults requestLegacyExternalStorage to
     *  `targetSdk < Q` (ParsingPackageUtils.java:2260, android14) and
     *  preserveLegacyExternalStorage to false (:2244).
     *
     *  Consumed by SpecialAccessStore.legacyStorageMode -> AppOpsManager op 87
     *  -> Environment.isExternalStorageLegacy (stage S1,
     *  docs/shared-storage-real-mac-folders-design_2026-09-25.md 3c). Only the
     *  attribute NAME used to exist here (FrameworkAttrIds), so the answer was a
     *  hardcoded true that put AnkiDroid (target 35) on a legacy-folder branch
     *  no device takes. preserveLegacyExternalStorage is carried for fidelity
     *  but not consulted: it only keeps a legacy state an EARLIER install
     *  already had, and Mac.apk keeps no such history. */
    public String requestLegacyExternalStorageRaw;
    public String preserveLegacyExternalStorageRaw;

    /** `<application android:resizeableActivity>`, TRI-STATE: null = not declared,
     *  which is NOT the same as false. AOSP's ParsedActivityUtils
     *  .getActivityResizeMode tests `hasValue(resizeableActivity) ||
     *  resizeableActivity != null` FIRST and only falls through to the
     *  targetSdk >= N rule when the app declared nothing at all. */
    public final Boolean resizeableActivity;
    /** `<application android:maxAspectRatio>`, null when not declared. Overrides
     *  the pre-O 1.86 default. See Boot.macapkMaxAspectRatio. */
    public final Float maxAspectRatio;

    /** `<application android:enabled>`, default TRUE
     *  (AOSP ParsingPackageUtils.java:2229,
     *  `.setEnabled(bool(true, R.styleable.AndroidManifestApplication_enabled, sa))`).
     *
     *  This is the PACKAGE-level half of AOSP's component gate:
     *  PackageUserStateUtils.isEnabled returns false for EVERY component of the
     *  package when the package itself is disabled (:136-140), regardless of
     *  what the component declares.
     *
     *  MEASURED 2026-08-19 over the full 64-package corpus: ZERO apps declare
     *  it. So this field changes no behaviour today and must NOT be cited as
     *  the reason for any measured effect -- it is here for fidelity, and
     *  because the alternative (a hardcoded `true` in makeAppInfo) makes the
     *  application half of `ComponentInfo.isEnabled() == enabled &&
     *  applicationInfo.enabled` structurally unreachable. */
    public final boolean applicationEnabled;

    /** POJO describing a single <provider> element from the manifest, carrying
     *  the fields AOSP's PackageParser.parseProvider reads (frameworks/base/
     *  core/java/android/content/pm/PackageParser.java:5095-5200 +
     *  parseProviderTags 5240-5375, android14-release).
     *
     *  ⚠ HISTORY, so the old reasoning is not re-adopted: this used to say the
     *  read/write permissions and path-permissions were "irrelevant because the
     *  providers we instantiate are auto-init SDK plumbing ... that never
     *  actually serve URIs". The premise was wrong twice over. (1) We install
     *  EVERY <provider> in the manifest, not only auto-init shims. (2) A
     *  provider does not have to SERVE a URI to READ its own ProviderInfo:
     *  com.zhiliaoapp.musically 46.4.3's ShellProvider and ExportedShellProvider
     *  read `info.readPermission` in the first statement of onCreate, and with
     *  the field absent the resulting NoSuchFieldError aborted both onCreate
     *  bodies outright. "The value would not change what WE do with it" is not
     *  a reason to withhold it from the app. */
    public static final class ProviderEntry {
        public final String  name;
        public final String  authorities;
        public final int     initOrder;
        /** android:enabled, default TRUE. AOSP parses this identically for all
         *  five component types through ONE shared routine
         *  (ParsedMainComponentUtils.java:68-70,
         *  `component.setEnabled(array.getBoolean(enabledAttr, true))`), and
         *  ServiceEntry/ActivityEntry have carried it for a long time --
         *  ProviderEntry was simply the one that never got it, which is why
         *  Boot.installAutoInitProviders had no boolean to test and constructed
         *  providers a device never constructs. NOT a tri-state: AOSP's default
         *  is a plain getBoolean(attr, true), so "absent" and "true" are the
         *  same answer.
         *
         *  This is the MANIFEST value only. The effective, runtime-resolved
         *  answer additionally folds in setComponentEnabledSetting and lives in
         *  MacApkPackageManager.macapkIsComponentEnabled -- do not conflate
         *  them (AOSP keeps ComponentInfo.enabled as the manifest value too,
         *  PackageInfoUtils.java:829, and WorkManager depends on reading the
         *  manifest default back out of it). */
        public final boolean enabled;
        public final boolean exported;
        public final boolean grantUriPermissions;
        /** android:readPermission, with AOSP's two fallbacks already applied
         *  (android:permission on the same <provider>, then
         *  `<application android:permission>`). NULL means the manifest really
         *  declares none anywhere -- the same answer a device gives. */
        public final String  readPermission;
        /** android:writePermission, same fallback chain. */
        public final String  writePermission;
        /** android:forceUriPermissions. */
        public final boolean forceUriPermissions;
        /** android:multiprocess. Reported, never acted on (we are one process). */
        public final boolean multiprocess;
        /** android:syncable. @Deprecated on AOSP and ignored there too. */
        public final boolean syncable;
        /** ProviderInfo.flags -- FLAG_SINGLE_USER from android:singleUser and
         *  FLAG_VISIBLE_TO_INSTANT_APP from android:visibleToInstantApps. */
        public final int     flags;
        /** From `<grant-uri-permission>` children; null when there are none,
         *  which AOSP defines as "all URIs are allowed". */
        public final android.os.PatternMatcher[] uriPermissionPatterns;
        /** From `<path-permission>` children; null when there are none. */
        public final android.content.pm.PathPermission[] pathPermissions;
        /** android:name -> android:value of every <meta-data> child of
         *  this <provider>. AndroidX-Startup's InitializationProvider
         *  uses this to enumerate per-app Initializer classes:
         *  `<meta-data android:name="androidx.work.WorkManagerInitializer"
         *  android:value="androidx.startup"/>` etc. Without this,
         *  androidx.startup's onCreate hits NoSuchMethodError or an
         *  empty PackageManager.getProviderInfo() result and bails. */
        public final java.util.Map<String, String> metaData;
        /** Parallel type map (see applicationMetaDataTypes). */
        public final java.util.Map<String, String> metaDataTypes;
        /** Convenience forms. `enabled` defaults to TRUE, which is AOSP's
         *  own default for an absent android:enabled -- not a placeholder. */
        public ProviderEntry(String n, String auth, int order,
                              boolean exp, boolean grant,
                              java.util.Map<String, String> md) {
            this(n, auth, order, exp, grant, md, null);
        }
        public ProviderEntry(String n, String auth, int order,
                              boolean exp, boolean grant,
                              java.util.Map<String, String> md,
                              java.util.Map<String, String> mdTypes) {
            this(n, auth, order, true, exp, grant, md, mdTypes,
                 null, null, false, false, false, 0, null, null);
        }
        public ProviderEntry(String n, String auth, int order,
                              boolean en, boolean exp, boolean grant,
                              java.util.Map<String, String> md,
                              java.util.Map<String, String> mdTypes,
                              String readPerm, String writePerm,
                              boolean forceUriPerms, boolean multiproc,
                              boolean sync, int flags,
                              android.os.PatternMatcher[] uriPatterns,
                              android.content.pm.PathPermission[] pathPerms) {
            this.name = n;
            this.authorities = auth;
            this.initOrder = order;
            this.enabled = en;
            this.exported = exp;
            this.grantUriPermissions = grant;
            this.metaData = md != null ? md : java.util.Collections.emptyMap();
            this.metaDataTypes = mdTypes != null ? mdTypes
                                                  : java.util.Collections.emptyMap();
            this.readPermission  = readPerm;
            this.writePermission = writePerm;
            this.forceUriPermissions = forceUriPerms;
            this.multiprocess = multiproc;
            this.syncable = sync;
            this.flags = flags;
            this.uriPermissionPatterns = uriPatterns;
            this.pathPermissions = pathPerms;
        }
        private volatile String resolvedAuthorities;
        /** android:authorities as a device's package parser hands it on. AOSP
         *  reads it with TypedArray.getNonConfigurationString
         *  (ParsedProviderUtils.parseProvider), which RESOLVES a string
         *  reference, so `android:authorities="@string/x"` reaches the provider
         *  map, ProviderInfo.authority and every ContentResolver lookup as the
         *  string's text. The binary manifest keeps the reference ("@0x7f100000"
         *  from BinaryXmlParser, "@string/x" from a text decode), and using that
         *  token as is registered the provider under "@0x7f100000": every
         *  content:// query an app made of its OWN provider then came back null
         *  (NoNonsense Notes: no lists and no notes, and an NPE from each drawer
         *  count loader). Resolved once against `res` (the app's Resources);
         *  the raw value is the answer when it is no reference or cannot be
         *  resolved. */
        public String resolvedAuthorities(android.content.res.Resources res) {
            String done = resolvedAuthorities;
            if (done != null) return done;
            String raw = authorities;
            if (raw == null || !raw.startsWith("@")) return raw;
            if (res == null) {
                android.app.Application app = android.app.Application.getInstance();
                res = app != null ? app.getResources() : null;
                if (res == null) return raw;
            }
            String out = raw;
            try {
                int id = MacApkPackageManager.resolveResRef(raw, "string");
                if (id != 0) {
                    String s = res.getString(id);
                    if (s != null && !s.isEmpty()) out = s;
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "provider " + name + ": android:authorities " + raw
                    + " did not resolve: " + t);
                return raw;
            }
            resolvedAuthorities = out;
            return out;
        }
        /** This entry with a different `exported` (the targetSdk-dependent
         *  default is only known once <uses-sdk> has been read). */
        ProviderEntry withExported(boolean exp) {
            return new ProviderEntry(name, authorities, initOrder, enabled, exp,
                    grantUriPermissions, metaData, metaDataTypes, readPermission,
                    writePermission, forceUriPermissions, multiprocess, syncable,
                    flags, uriPermissionPatterns, pathPermissions);
        }
    }
    /** Every <provider> declared under <application>, in MANIFEST
     *  ORDER. Boot.java sorts by initOrder before instantiating. */
    public final java.util.List<ProviderEntry> providers;

    /** Lightweight POJO for <service> elements declared under
     *  <application>. We capture the class name + its <meta-data>
     *  children so PackageManager.getServiceInfo(ComponentName,
     *  GET_META_DATA) can return a populated Bundle. That's how
     *  Firebase ComponentDiscovery finds registrars: it queries the
     *  metadata of a synthetic "ComponentDiscoveryService" declared
     *  in the manifest, which lists "com.google.firebase.components:
     *  <RegistrarClassName>" => "com.google.firebase.components.
     *  ComponentRegistrar" pairs. Without those, FirebaseApp.<init>
     *  treats the app as having ZERO Firebase modules, and downstream
     *  APIs like FirebaseRemoteConfig.getInstance fail. */
    public static final class ServiceEntry {
        public final String  name;
        public final boolean enabled;
        public final boolean exported;
        /** android:name -> android:value of every <meta-data> child.
         *  Always non-null; empty if the service has no metadata. */
        public final java.util.Map<String, String> metaData;
        /** Parallel type map (see applicationMetaDataTypes). */
        public final java.util.Map<String, String> metaDataTypes;
        /** Every `<intent-filter>` on this service, in declaration order.
         *  Always non-null; empty for an explicitly-addressed-only service.
         *
         *  Added v1.0.1177. These used to be DISCARDED at parse time, which
         *  made PackageManager.resolveService structurally unable to answer an
         *  implicit action and so broke the LAST HOP of FCM delivery: the real
         *  firebase-messaging AAR's ServiceStarter resolves
         *  "com.google.firebase.MESSAGING_EVENT" to find the app's own
         *  FirebaseMessagingService, and a null resolve makes it log
         *  "ServiceIntent not found" and drop the message. Activities and
         *  receivers have carried their filters all along (ActivityEntry
         *  .filters); services now do too, from the same parseIntentFilter. */
        public final java.util.List<IntentFilterEntry> filters;
        public ServiceEntry(String n, boolean en, boolean exp,
                             java.util.Map<String, String> md) {
            this(n, en, exp, md, null, null);
        }
        public ServiceEntry(String n, boolean en, boolean exp,
                             java.util.Map<String, String> md,
                             java.util.Map<String, String> mdTypes) {
            this(n, en, exp, md, mdTypes, null);
        }
        public ServiceEntry(String n, boolean en, boolean exp,
                             java.util.Map<String, String> md,
                             java.util.Map<String, String> mdTypes,
                             java.util.List<IntentFilterEntry> filters) {
            this.name = n;
            this.enabled = en;
            this.exported = exp;
            this.metaData = md != null ? md : java.util.Collections.emptyMap();
            this.metaDataTypes = mdTypes != null ? mdTypes
                                                  : java.util.Collections.emptyMap();
            this.filters = filters != null ? filters
                                           : java.util.Collections.<IntentFilterEntry>emptyList();
        }
    }
    /** Every <service> declared under <application>. Index keyed by
     *  the fully-qualified class name so PackageManager.getServiceInfo
     *  is O(1). */
    public final java.util.Map<String, ServiceEntry> services;

    /** One surviving `<uses-permission>` / `<uses-permission-sdk-23>` /
     *  `<uses-permission-sdk-m>` declaration. "Surviving" because AOSP
     *  applies the min/maxSdkVersion window at PARSE time
     *  (ParsingPackageUtils.parseUsesPermission: `if
     *  (Build.VERSION.RESOURCES_SDK_INT < minSdkVersion ||
     *  Build.VERSION.RESOURCES_SDK_INT > maxSdkVersion) return success;`
     *  without adding the permission), so a permission declared
     *  `android:maxSdkVersion="28"` is simply NOT in the package's
     *  requested set on a newer platform. Apps depend on that: the
     *  legacy-storage pattern declares WRITE_EXTERNAL_STORAGE with
     *  maxSdkVersion and branches on checkSelfPermission returning
     *  DENIED afterwards.
     *
     *  This list is the sole input a faithful
     *  PackageManager.getPackageInfo(GET_PERMISSIONS) /
     *  Context.checkSelfPermission can have -- a permission an app never
     *  declared can never be granted, and requestPermissions() silently
     *  drops it. */
    public static final class UsesPermissionEntry {
        public final String name;
        /** android:minSdkVersion, Integer.MIN_VALUE when absent. */
        public final int minSdkVersion;
        /** android:maxSdkVersion, Integer.MAX_VALUE when absent. */
        public final int maxSdkVersion;
        /** android:usesPermissionFlags bitmask, 0 when absent. */
        public final int usesPermissionFlags;
        public UsesPermissionEntry(String n, int minSdk, int maxSdk, int flags) {
            this.name = n;
            this.minSdkVersion = minSdk;
            this.maxSdkVersion = maxSdk;
            this.usesPermissionFlags = flags;
        }
    }
    /** Declared `<uses-permission*>` entries in MANIFEST ORDER, already
     *  filtered by the AOSP sdk-version window and de-duplicated by name
     *  (AOSP quietly ignores a repeated declaration). */
    public final java.util.List<UsesPermissionEntry> usesPermissions;

    /** A `<permission>` the app DEFINES (not one it requests). Feeds
     *  PackageManager.getPermissionInfo for app-defined permissions --
     *  the custom `<pkg>.permission.C2D_MESSAGE` /
     *  `<pkg>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` pattern that
     *  messaging and androidx libraries declare and then look up. */
    public static final class PermissionEntry {
        public final String name;
        /** android:permissionGroup, null when absent. */
        public final String group;
        /** android:protectionLevel, PermissionInfo.PROTECTION_NORMAL (0)
         *  when absent -- AOSP's documented default. */
        public final int protectionLevel;
        /** android:permissionFlags bitmask, 0 when absent. */
        public final int flags;
        public PermissionEntry(String n, String g, int level, int flags) {
            this.name = n;
            this.group = g;
            this.protectionLevel = level;
            this.flags = flags;
        }
    }
    /** Every `<permission>` the app defines, in manifest order, already
     *  filtered by AOSP's `maxSdkVersion < SDK_INT -> drop` rule. */
    public final java.util.List<PermissionEntry> permissions;

    /** A `<permission-group>` the app defines. */
    public static final class PermissionGroupEntry {
        public final String name;
        public final int priority;
        public final int flags;
        public PermissionGroupEntry(String n, int priority, int flags) {
            this.name = n;
            this.priority = priority;
            this.flags = flags;
        }
    }
    public final java.util.List<PermissionGroupEntry> permissionGroups;

    /** One `<uses-feature>` declaration, shaped like AOSP's FeatureInfo
     *  (ParsingPackageUtils.parseFeatureInfo). A NULL `name` encodes the
     *  `android:glEsVersion`-only form, exactly as AOSP does -- that
     *  entry is also what seeds ConfigurationInfo.reqGlEsVersion. */
    public static final class FeatureEntry {
        /** android:name, or null for a glEsVersion-only declaration. */
        public final String name;
        /** android:version, 0 when absent. */
        public final int version;
        /** android:glEsVersion (upper 16 bits major, lower 16 minor);
         *  only meaningful when name is null, matching AOSP, which reads
         *  it solely in that case. */
        public final int reqGlEsVersion;
        /** android:required, defaulting to TRUE per AOSP. */
        public final boolean required;
        public FeatureEntry(String n, int version, int glEs, boolean required) {
            this.name = n;
            this.version = version;
            this.reqGlEsVersion = glEs;
            this.required = required;
        }
    }
    /** Every `<uses-feature>` in manifest order. The input a faithful
     *  PackageManager.hasSystemFeature policy and
     *  PackageInfo.reqFeatures need. */
    public final java.util.List<FeatureEntry> reqFeatures;

    /** Package names named by this app's own `<queries><package
     *  android:name="..."/></queries>` declarations (API 30+ package
     *  visibility). AOSP: AppsFilterUtils.canQueryViaPackage --
     *  "querying.getQueriesPackages().contains(potentialTarget.getPackageName())"
     *  (aosp-mirror/platform_frameworks_base services/core/java/com/android/
     *  server/pm/AppsFilterUtils.java, fetched 2026-08-17) -- one of several
     *  automatic-visibility grants; MacApkPackageManager's registry-visibility
     *  filter consults this list. Only the `<package>` sub-element is parsed;
     *  `<queries><intent>`/`<provider>` (which need matching against every
     *  OTHER installed app's own declared components) are a deliberately
     *  unimplemented, documented gap -- see the filter's own header. */
    public final java.util.List<String> queriesPackages;

    /** One `<queries><intent>`, as AOSP ParsingPackageUtils.parseQueries turns it
     *  into the Intent AppsFilter matches against other apps' components
     *  (android14-release ParsingPackageUtils.java parseQueries): at most one
     *  action / scheme / host / type; a scheme gives data scheme://host/*, a type
     *  with no '/' gets "/*" appended, and a type with no scheme gives the data
     *  content://STAR/STAR. Matched with IntentFilter's WILDCARD rules
     *  (MacApkPackageManager.macapkQueriesIntentMatches). */
    public static final class QueriesIntent {
        public final String action, scheme, host, type;
        public final java.util.List<String> categories;
        QueriesIntent(String action, java.util.List<String> categories, String scheme,
                      String host, String type) {
            this.action = action; this.categories = categories;
            this.scheme = scheme; this.host = host; this.type = type;
        }
    }
    /** `<queries><intent>` declarations (AppsFilterUtils.canQueryViaComponents). */
    public java.util.List<QueriesIntent> queriesIntents = java.util.Collections.emptyList();
    /** `<queries><provider android:authorities>`, split on ';' (matchesProviders). */
    public java.util.Set<String> queriesProviders = java.util.Collections.emptySet();

    /** One `<intent-filter>`, flattened to the four dimensions AOSP's
     *  IntentFilter.match() actually consults for the intents apps send
     *  in-process: action, categories, data scheme, data MIME type.
     *  AOSP reference: frameworks/base/core/java/android/content/
     *  IntentFilter.java match(action, type, scheme, data, categories,
     *  logTag) -- matchAction / matchData / matchCategories. */
    public static final class IntentFilterEntry {
        public final java.util.List<String> actions;
        public final java.util.List<String> categories;
        public final java.util.List<String> schemes;
        public final java.util.List<String> mimeTypes;
        /** `android:priority`, default 0. AOSP's IntentResolver.sortResults ranks
         *  matches by this DESCENDING (mResolvePrioritySorter), and it is not a
         *  nicety: firebase-messaging declares its own fallback
         *  FirebaseMessagingService at priority -500 SPECIFICALLY so that it
         *  loses to the app's real handler. Resolve MESSAGING_EVENT without
         *  honouring it and 4 of the corpus apps (Minecraft, Reddit, Telegram,
         *  Candy Crush) hand every push to the SDK's dead-end fallback instead
         *  of their own onMessageReceived, silently. */
        public final int priority;
        /** `<data android:host>` values, as written (AOSP keeps "*.example.com"
         *  wildcards; AppLinks verifies the base domain for those). */
        public final java.util.List<String> hosts;
        /** `android:autoVerify="true"` on the filter: the app asks the platform
         *  to verify its http(s) hosts as App Links (AppLinks.java). */
        public final boolean autoVerify;
        public IntentFilterEntry(java.util.List<String> a, java.util.List<String> c,
                                 java.util.List<String> s, java.util.List<String> m) {
            this(a, c, s, m, 0);
        }
        public IntentFilterEntry(java.util.List<String> a, java.util.List<String> c,
                                 java.util.List<String> s, java.util.List<String> m,
                                 int priority) {
            this(a, c, s, m, priority, null, false);
        }
        public IntentFilterEntry(java.util.List<String> a, java.util.List<String> c,
                                 java.util.List<String> s, java.util.List<String> m,
                                 int priority, java.util.List<String> hosts, boolean autoVerify) {
            this.actions    = a != null ? a : java.util.Collections.emptyList();
            this.categories = c != null ? c : java.util.Collections.emptyList();
            this.schemes    = s != null ? s : java.util.Collections.emptyList();
            this.mimeTypes  = m != null ? m : java.util.Collections.emptyList();
            this.priority   = priority;
            this.hosts      = hosts != null ? hosts : java.util.Collections.emptyList();
            this.autoVerify = autoVerify;
        }
    }

    /** One `<activity>`, `<activity-alias>` or `<receiver>` element, carrying
     *  everything PackageManager.getActivityInfo / getReceiverInfo must be able
     *  to answer. This is the manifest-parser half of AOSP's
     *  ParsedActivityUtils.parseActivityOrAlias, which fills a ParsedActivity
     *  that PackageInfoUtils.generateActivityInfo then copies into the
     *  ActivityInfo handed to the app.
     *
     *  Resource-valued attributes (theme / label / icon / logo) are kept as the
     *  RAW manifest token rather than an int id, because Manifest.parse runs
     *  before the ResourceIndex exists. MacApkPackageManager resolves them
     *  lazily. Two raw spellings occur and both must be handled: the BINARY
     *  manifest path renders a TYPE_REFERENCE as "@0x7f130123"
     *  (BinaryXmlParser.setAttr), while the apktool TEXT decode emits
     *  "@style/NoActionBar.Gameplay". */
    public static final class ActivityEntry {
        public final String  name;
        /** `<activity-alias android:targetActivity>`; null for a real activity.
         *  AOSP ActivityInfo.targetActivity: "If this is an activity alias,
         *  this is the real activity class to run for it." */
        public final String  targetActivity;
        /** `android:parentActivityName`. AOSP ActivityInfo.parentActivityName:
         *  "If defined, the activity named here is the logical parent of this
         *  activity." androidx NavUtils.getParentActivityName reads exactly
         *  this field first, then falls back to the
         *  android.support.PARENT_ACTIVITY <meta-data> below. */
        public final String  parentActivityName;
        public final String  permission;
        public final String  taskAffinity;
        public final String  processName;
        public final String  themeRaw;
        public final String  labelRaw;
        public final String  iconRaw;
        public final String  logoRaw;
        public final int     launchMode;
        public final int     documentLaunchMode;
        public final int     screenOrientation;
        public final int     configChanges;
        public final int     softInputMode;
        public final int     uiOptions;
        public final boolean enabled;
        public final boolean exported;
        public final boolean directBootAware;
        public final boolean isAlias;
        public final java.util.Map<String, String> metaData;
        public final java.util.Map<String, String> metaDataTypes;
        public final java.util.List<IntentFilterEntry> filters;
        public ActivityEntry(String name, String targetActivity,
                             String parentActivityName, String permission,
                             String taskAffinity, String processName,
                             String themeRaw, String labelRaw,
                             String iconRaw, String logoRaw,
                             int launchMode, int documentLaunchMode,
                             int screenOrientation, int configChanges,
                             int softInputMode, int uiOptions,
                             boolean enabled, boolean exported,
                             boolean directBootAware, boolean isAlias,
                             java.util.Map<String, String> metaData,
                             java.util.Map<String, String> metaDataTypes,
                             java.util.List<IntentFilterEntry> filters) {
            this.name = name;
            this.targetActivity = targetActivity;
            this.parentActivityName = parentActivityName;
            this.permission = permission;
            this.taskAffinity = taskAffinity;
            this.processName = processName;
            this.themeRaw = themeRaw;
            this.labelRaw = labelRaw;
            this.iconRaw = iconRaw;
            this.logoRaw = logoRaw;
            this.launchMode = launchMode;
            this.documentLaunchMode = documentLaunchMode;
            this.screenOrientation = screenOrientation;
            this.configChanges = configChanges;
            this.softInputMode = softInputMode;
            this.uiOptions = uiOptions;
            this.enabled = enabled;
            this.exported = exported;
            this.directBootAware = directBootAware;
            this.isAlias = isAlias;
            this.metaData = metaData != null ? metaData
                                             : java.util.Collections.emptyMap();
            this.metaDataTypes = metaDataTypes != null ? metaDataTypes
                                             : java.util.Collections.emptyMap();
            this.filters = filters != null ? filters
                                           : java.util.Collections.emptyList();
        }
    }

    /** Activity-class-name -> ActivityEntry, covering `<activity>` AND
     *  `<activity-alias>` (AOSP files both under the package's activities
     *  list; an alias is an ActivityInfo whose targetActivity is set).
     *  Insertion-ordered so queryIntentActivities can return manifest order,
     *  which is the order AOSP's PackageManagerService iterates. */
    public final java.util.Map<String, ActivityEntry> activities;

    /** Receiver-class-name -> ActivityEntry, from `<receiver>` elements.
     *  AOSP models a receiver with ActivityInfo too (getReceiverInfo returns
     *  ActivityInfo), so it shares the entry type -- but it is a SEPARATE
     *  component namespace, which is why getReceiverInfo cannot just delegate
     *  to getActivityInfo. */
    public final java.util.Map<String, ActivityEntry> receivers;

    /** Component class name -> its RAW `android:process` attribute, for every
     *  `<activity>` / `<activity-alias>` / `<receiver>` / `<service>` /
     *  `<provider>` that declares one. Absent from the map means "the
     *  application's default process", which AOSP takes to be the package
     *  name unless `<application android:process>` says otherwise
     *  (ComponentInfo.processName; a leading ':' names a process PRIVATE to
     *  the app, spelled `<pkg><value>`).
     *
     *  Kept as one flat map rather than a field on each entry type because
     *  ServiceEntry / ProviderEntry are deliberately minimal POJOs, and the
     *  one consumer wants every component type at once: Mac.apk runs an
     *  app's whole component set inside the ONE runtime process, so anything
     *  AOSP would have isolated in a sibling process needs its
     *  process-scoped operations contained. See ClassFileRewriter's
     *  Pattern H. */
    public final java.util.Map<String, String> componentProcesses;

    private Manifest(String pkg, String appClass, String appComponentFactory,
                     String launch, int target,
                     java.util.Map<String, String> meta, String version,
                     java.util.List<ProviderEntry> providers,
                     java.util.Map<String, ServiceEntry> services,
                     String appTheme,
                     java.util.Map<String, String> metaTypes,
                     int minSdk, int versionCode, String versionRaw,
                     java.util.List<UsesPermissionEntry> usesPermissions,
                     java.util.List<PermissionEntry> permissions,
                     java.util.List<PermissionGroupEntry> permissionGroups,
                     java.util.List<FeatureEntry> reqFeatures,
                     java.util.Map<String, ActivityEntry> activities,
                     java.util.Map<String, ActivityEntry> receivers,
                     String appLabel,
                     Boolean resizeableActivity, Float maxAspectRatio,
                     boolean applicationEnabled,
                     java.util.Map<String, String> componentProcesses,
                     java.util.List<String> queriesPackages) {
        this.componentProcesses = componentProcesses != null
            ? componentProcesses : java.util.Collections.emptyMap();
        this.activities        = activities != null ? activities
                                                    : java.util.Collections.emptyMap();
        this.receivers         = receivers != null ? receivers
                                                   : java.util.Collections.emptyMap();
        this.usesPermissions   = usesPermissions != null ? usesPermissions
                                                        : java.util.Collections.emptyList();
        this.permissions       = permissions != null ? permissions
                                                     : java.util.Collections.emptyList();
        this.permissionGroups  = permissionGroups != null ? permissionGroups
                                                          : java.util.Collections.emptyList();
        this.reqFeatures       = reqFeatures != null ? reqFeatures
                                                    : java.util.Collections.emptyList();
        this.queriesPackages   = queriesPackages != null ? queriesPackages
                                                    : java.util.Collections.emptyList();
        this.packageName = pkg;
        this.applicationClass = appClass;
        this.appComponentFactory = appComponentFactory;
        this.launchableActivity = launch;
        this.targetSdk = target;
        this.minSdk = minSdk;
        this.versionCode = versionCode;
        this.applicationMetaData = meta;
        this.applicationMetaDataTypes = metaTypes != null ? metaTypes
                                                           : java.util.Collections.emptyMap();
        this.versionName = version != null ? version : "";
        this.versionNameRaw = versionRaw != null ? versionRaw : "";
        this.providers   = providers != null ? providers
                                              : java.util.Collections.emptyList();
        this.services    = services != null ? services
                                              : java.util.Collections.emptyMap();
        this.applicationTheme = appTheme;
        this.applicationLabel = appLabel;
        this.resizeableActivity = resizeableActivity;
        this.maxAspectRatio = maxAspectRatio;
        this.applicationEnabled = applicationEnabled;
    }

    /** Xerces' DocumentBuilder installs a DEFAULT error handler that prints
     *  "[Warning]" / "[Error]" / "[Fatal Error]" lines to System.err and then
     *  rethrows on fatal. Those prints bypass MACAPK_LOG_LEVEL entirely (they
     *  are not our funnel), carry no tag, and are indistinguishable from a real
     *  runtime failure in a log. Every caller of parse() already treats a throw
     *  as "unparseable" -- so throw, and say nothing. */
    private static final org.xml.sax.ErrorHandler THROW_DONT_PRINT =
        new org.xml.sax.ErrorHandler() {
            @Override public void warning(org.xml.sax.SAXParseException e) { }
            @Override public void error(org.xml.sax.SAXParseException e)
                    throws org.xml.sax.SAXParseException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e)
                    throws org.xml.sax.SAXParseException { throw e; }
        };

    /** First {@code n} bytes of a file, zero-padded when it is shorter (so the
     *  callers below never have to bounds-check). Empty/unreadable reads back
     *  as all zeroes, which no magic below matches. */
    private static byte[] leadingBytes(File f, int n) {
        byte[] out = new byte[n];
        if (f == null || !f.isFile()) return out;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int got = 0;
            while (got < n) {
                int r = in.read(out, got, n - got);
                if (r < 0) break;
                got += r;
            }
        } catch (java.io.IOException ignored) {
            // Unreadable is not a container; let the normal path report it.
        }
        return out;
    }

    /** True when these leading bytes are a BINARY blob that must never reach an
     *  XML parser. Two shapes matter here:
     *
     *  - "PK\x03\x04" -- the local file header magic of a zip, i.e. any .apk /
     *    .apkm / .xapk / .apks / .jar. (An EMPTY zip starts "PK\x05\x06" and a
     *    spanned one "PK\x07\x08"; both are equally not-XML, so match on "PK"
     *    plus a known second pair rather than only 03 04.)
     *  - 0x03 0x00 0x08 0x00 -- Android's binary XML chunk header
     *    (RES_XML_TYPE = 0x0003, headerSize = 8). A raw AndroidManifest.xml
     *    pulled OUT of an apk has this; it belongs to BinaryXmlParser, not to
     *    DocumentBuilder, and letting it reach the DOM path produces the same
     *    stderr noise for a file we can actually read properly. */
    private static boolean isBinaryContainer(byte[] m) {
        if (m == null || m.length < 4) return false;
        if (m[0] == 'P' && m[1] == 'K'
                && (m[2] == 0x03 || m[2] == 0x05 || m[2] == 0x07)) {
            return true;
        }
        return m[0] == 0x03 && m[1] == 0x00 && m[2] == 0x08 && m[3] == 0x00;
    }

    /** The raw binary AndroidManifest.xml of a multi-part bundle's BASE member,
     *  or null when this zip is not that shape.
     *
     *  Base selection mirrors com.macapk.dex.Main.pickEntries so the identity
     *  reported here cannot disagree with the identity the DEX converter and
     *  apk_unpacker.cpp settle on: a literal {@code base.apk} first (the
     *  APKMirror .apkm shape), else the LARGEST root {@code *.apk} entry (the
     *  APKPure .xapk shape, where the base is named after the package and
     *  dwarfs every config split).
     *
     *  RESIDUAL, stated rather than papered over: Main also honours an XAPK
     *  {@code manifest.json} that names its base explicitly, and this does not.
     *  The two therefore diverge only for an XAPK whose named base is NOT its
     *  largest member, which no bundle in the local corpus is. Reading identity
     *  from the wrong member would report a config split's package (identical)
     *  and versionCode (identical) anyway, so the blast radius of the
     *  divergence is small -- but if a bundle ever shows up where it matters,
     *  the fix is to share Main's selector rather than to widen this one. */
    // Package-private (was private) so PresentedSdk.readTargetSdkFromApk can
    // share this ONE base-member selector instead of growing a second copy: a
    // bundle whose targetSdk is read from a different member than its identity
    // is exactly the two-answers-to-one-question shape this selector's own
    // doc comment warns about.
    static byte[] binaryManifestFromBundle(java.util.zip.ZipFile zf) {
        java.util.zip.ZipEntry base = null;
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String name = e.getName();
            // Root members only: a nested "assets/base.apk" inside a plain apk
            // is the app's own payload, not this archive's base.
            if (name.indexOf('/') >= 0) continue;
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
            if (name.equals("base.apk")) { base = e; break; }
            if (base == null || e.getSize() > base.getSize()) base = e;
        }
        if (base == null) return null;
        // Nested zip: the member is a stream, not a File, so it has to be read
        // with ZipInputStream rather than ZipFile (which needs random access).
        // The manifest is the first few KB of any apk's central-directory
        // order in practice, but do not rely on that -- scan to the end.
        try (java.io.InputStream raw = zf.getInputStream(base);
             java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(raw)) {
            java.util.zip.ZipEntry inner;
            while ((inner = zin.getNextEntry()) != null) {
                if (!"AndroidManifest.xml".equals(inner.getName())) continue;
                return zin.readAllBytes();
            }
        } catch (java.io.IOException t) {
            NativeLog.w(TAG, "could not read AndroidManifest.xml out of bundle member "
                + base.getName() + ": " + t);
        }
        return null;
    }

    public static Manifest parse(File xml) throws Exception {
        return parse(xml, null);
    }

    /** Preferred entry: parse the BINARY AndroidManifest.xml straight
     *  from the APK (typed attribute values, no apktool dependency for
     *  the manifest), falling back to the apktool TEXT decode when the
     *  APK is unavailable or the binary parse fails. The DOM walk
     *  below is shared by both paths; only the binary path populates
     *  the macapk type annotations. */
    public static Manifest parse(File xml, String apkPath) throws Exception {
        Document doc = null;
        if (apkPath != null && !apkPath.isEmpty()) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
                java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
                if (e != null) {
                    byte[] axml;
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        axml = in.readAllBytes();
                    }
                    doc = BinaryXmlParser.parse(axml);
                    NativeLog.i(TAG, "parsed BINARY AndroidManifest.xml from apk ("
                        + axml.length + " bytes)");
                } else {
                    // No top-level AndroidManifest.xml. That is NOT a broken
                    // archive, it is the normal shape of a multi-part bundle
                    // (.apkm / .xapk / .apks), whose root holds base.apk plus
                    // config splits. Reach into the base member and read ITS
                    // manifest -- the identity of such a bundle is the base's
                    // identity, which is exactly what a caller like
                    // PackageManager.getPackageArchiveInfo is asking for.
                    // Before this, every one of these fell through to the DOM
                    // fallback below and handed a zip to Xerces; see the guard
                    // there for what that produced.
                    byte[] axml = binaryManifestFromBundle(zf);
                    if (axml != null) {
                        doc = BinaryXmlParser.parse(axml);
                        NativeLog.i(TAG, "parsed BINARY AndroidManifest.xml from the"
                            + " base member of split bundle " + apkPath
                            + " (" + axml.length + " bytes)");
                    }
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "binary manifest parse failed ("
                    + t + ") -- falling back to apktool text decode");
                doc = null;
            }
        }
        if (doc == null) {
            // THE FALLBACK'S INPUT IS A TEXT XML FILE -- apktool's decoded
            // AndroidManifest.xml -- and nothing else. Callers that have only
            // an archive pass the SAME File for both parameters
            // (PackageManager.getPackageArchiveInfo does exactly that: `new
            // File(archiveFilePath)` and `archiveFilePath`), so when the binary
            // path above declines, `xml` here is the archive itself.
            //
            // Handing that to DocumentBuilder is not merely useless, it is
            // LOUD: Xerces' default error handler prints straight to stderr
            // before throwing, so every registry read of a retained bundle
            // emitted
            //     [Fatal Error] com.google.android.youtube_...apkm:1:1:
            //     Content is not allowed in prolog.
            // (1:1 is the "PK" zip magic). Observed x4 per read on 2026-08-19
            // for youtube and again for musically. The caller catches the
            // throw and correctly returns null, so this was pure noise -- but
            // it is exactly the kind of noise a real error hides behind, and
            // the [Fatal Error] wording invites a hunt for a defect that isn't
            // there.
            //
            // Two guards, both cheap:
            //   1. sniff the leading bytes and refuse a container outright, so
            //      the parser is never fed one;
            //   2. give the builder an ErrorHandler that THROWS instead of
            //      printing, so a genuinely malformed text manifest still
            //      fails the same way it always did, silently, for the caller
            //      to handle.
            byte[] magic = leadingBytes(xml, 4);
            if (isBinaryContainer(magic)) {
                throw new java.io.IOException(
                    "not a text AndroidManifest.xml: " + xml
                    + " is a binary container (magic "
                    + String.format("%02x%02x%02x%02x", magic[0], magic[1],
                                    magic[2], magic[3])
                    + ") and carries no manifest we can reach");
            }
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // The decoded manifest can use the android: namespace; we want
            // to read attributes by namespace+localname rather than the
            // qualified form so we tolerate prefix changes.
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setErrorHandler(THROW_DONT_PRINT);
            doc = db.parse(xml);
        }
        Element root = doc.getDocumentElement();

        String pkg = root.getAttribute("package");
        if (pkg == null || pkg.isEmpty()) pkg = "com.macapk.unknown";
        // android:versionName lives on the root <manifest> element.
        // Some manifests omit it (defaults to empty); we tolerate that.
        // APKs built with newer aapt2 may carry the version only in
        // `platformBuildVersionName` (no namespace) -- e.g. HCR 1.43's
        // decoded manifest has only that one. Probe both. Sanitize for
        // filesystem use -- strip anything that isn't [A-Za-z0-9._-]
        // so a versionName like "1.43.0 (beta)" doesn't break paths.
        String version = root.getAttributeNS(ANDROID_NS, "versionName");
        if (version == null || version.isEmpty()) {
            version = root.getAttribute("platformBuildVersionName");
        }
        if (version == null) version = "";
        // The UNSANITIZED versionName -- what PackageInfo.versionName must
        // report verbatim (Unity logs it as "Version '<v>'"; an EMPTY one
        // drives Unity's native init down a null-fallback that crashes
        // strlen). The sanitized form below stays for filesystem paths.
        final String versionRaw = version;
        StringBuilder safe = new StringBuilder(version.length());
        for (int i = 0; i < version.length(); ++i) {
            char c = version.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (ok) safe.append(c);
        }
        version = safe.toString();

        // android:versionCode on the root <manifest>. PackageInfo.versionCode
        // / longVersionCode report it.
        //
        // Default 0, matching AOSP PackageParser, and deliberately NOT the
        // namespaceless platformBuildVersionCode that some aapt2 builds emit:
        // that attribute is an SDK LEVEL (e.g. 30), so using it as a version
        // made a versionless APK compare NEWER than a genuine versionCode 1
        // and silently inverted update-vs-downgrade classification. The
        // runtime's own binary-manifest reader was corrected the same way, so
        // PackageInfo and meta.json now agree for versionless APKs; they did
        // not before, and the disagreement was invisible.
        int versionCode = 0;
        String vc = root.getAttributeNS(ANDROID_NS, "versionCode");
        if (vc != null && !vc.isEmpty()) {
            try { versionCode = Integer.parseInt(vc.trim()); }
            catch (NumberFormatException ignored) {}
        }

        // Find <application>; android:name on it (if present) is the app's
        // Application subclass.
        String appClass = null;
        String appComponentFactory = null;
        int target = 0;
        int minSdk = 0;
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        java.util.Map<String, String> metaTypes = new java.util.HashMap<>();
        java.util.List<ProviderEntry> providers = new java.util.ArrayList<>();
        // Indices into `providers` whose android:exported was ABSENT: their
        // default depends on targetSdk, which is read after <application>.
        java.util.List<Integer> provExportedUnset = new java.util.ArrayList<>();
        // LinkedHashMap for the same reason activities/receivers are below:
        // manifest order is AOSP's iteration order, and it is the tie-break once
        // matches are sorted by filter priority.
        java.util.Map<String, ServiceEntry> services = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> componentProcesses = new java.util.HashMap<>();
        // LinkedHashMap: manifest order is the order AOSP's
        // PackageManagerService iterates components, and queryIntentActivities
        // returns matches in that order.
        java.util.Map<String, ActivityEntry> activities =
            new java.util.LinkedHashMap<>();
        java.util.Map<String, ActivityEntry> receivers =
            new java.util.LinkedHashMap<>();
        String appTheme = null;
        String appLabel = null;
        String appIconRaw = null;
        String requestLegacyRaw = null, preserveLegacyRaw = null;
        Boolean resizeableActivity = null;
        // `<application android:enabled>` default. AOSP's default is TRUE,
        // and it stays TRUE when there is no <application> element at all.
        boolean applicationEnabled = true;
        Float maxAspectRatio = null;
        NodeList apps = root.getElementsByTagName("application");
        if (apps.getLength() > 0) {
            Element app = (Element) apps.item(0);
            appClass = app.getAttributeNS(ANDROID_NS, "name");
            if (appClass != null && appClass.startsWith(".")) {
                appClass = pkg + appClass;
            }
            // <application android:appComponentFactory="..."> -- the class
            // AOSP's LoadedApk instantiates and routes every component
            // construction through. Same relative-name rule as android:name.
            appComponentFactory = app.getAttributeNS(ANDROID_NS, "appComponentFactory");
            if (appComponentFactory != null && appComponentFactory.startsWith(".")) {
                appComponentFactory = pkg + appComponentFactory;
            }
            if (appComponentFactory != null && appComponentFactory.isEmpty()) {
                appComponentFactory = null;
            }
            // v1.0.128: pull `<application android:theme="@style/X">`.
            // Drives ResourceIndex's application-theme cascade so chrome
            // painters resolve `?attr/colorSurfaceContainerHigh` against
            // the APK's Material3 tokens. Generic for any APK that
            // declares a theme.
            String rawTheme = app.getAttributeNS(ANDROID_NS, "theme");
            if (rawTheme != null && !rawTheme.isEmpty()) {
                appTheme = rawTheme.startsWith("@style/")
                    ? rawTheme.substring(7) : rawTheme;
            }
            // Kept RAW ("@string/app_name" or a literal); ComponentInfo.loadLabel
            // resolves it against the app's Resources at read time.
            String rawLabel = app.getAttributeNS(ANDROID_NS, "label");
            if (rawLabel != null && !rawLabel.isEmpty()) appLabel = rawLabel;
            String rawIcon = app.getAttributeNS(ANDROID_NS, "icon");
            if (rawIcon != null && !rawIcon.isEmpty()) appIconRaw = rawIcon;
            // Stage S1: the two scoped-storage opt-outs, kept RAW (see the
            // field comments). getAttributeNS answers "" for an absent
            // attribute, which must stay "absent", not "false".
            String rawReqLegacy = app.getAttributeNS(ANDROID_NS, "requestLegacyExternalStorage");
            if (rawReqLegacy != null && !rawReqLegacy.isEmpty()) requestLegacyRaw = rawReqLegacy;
            String rawPresLegacy = app.getAttributeNS(ANDROID_NS, "preserveLegacyExternalStorage");
            if (rawPresLegacy != null && !rawPresLegacy.isEmpty()) preserveLegacyRaw = rawPresLegacy;
            // v1.0.874: the two attributes AOSP's pre-O max-aspect-ratio gate
            // keys on. Both are TRI-STATE (absent is not the same as false), so
            // they are carried as Boolean/Float rather than boolean/float --
            // ParsedActivityUtils.getActivityResizeMode branches on
            // `sa.hasValue(resizeableActivity) || resizeableActivity != null`
            // FIRST, and only falls through to the targetSdk rule when the app
            // declared nothing. See Boot.macapkMaxAspectRatio.
            //
            // ⚠ NOT the same attribute as `<supports-screens android:resizeable>`,
            // which is the legacy FLAG_RESIZEABLE_FOR_SCREENS and does NOT exempt
            // an activity from the aspect cap. frozenbubble declares exactly that
            // one, so a substring match on "resizeable" gets this backwards.
            String rawResizeable = app.getAttributeNS(ANDROID_NS, "resizeableActivity");
            if (rawResizeable != null && !rawResizeable.isEmpty()) {
                resizeableActivity = Boolean.valueOf("true".equals(rawResizeable));
            }
            String rawMaxAspect = app.getAttributeNS(ANDROID_NS, "maxAspectRatio");
            if (rawMaxAspect != null && !rawMaxAspect.isEmpty()) {
                try { maxAspectRatio = Float.valueOf(Float.parseFloat(rawMaxAspect)); }
                catch (Throwable ignored) {}
            }
            // `<application android:enabled>`, default true. Same
            // absent-means-true shape as every component's android:enabled.
            applicationEnabled = !"false".equalsIgnoreCase(
                app.getAttributeNS(ANDROID_NS, "enabled"));
            // Walk direct children of <application>. We need both
            // <meta-data> entries AND <provider> declarations, so do
            // them in a single pass.
            NodeList appChildren = app.getChildNodes();
            for (int i = 0; i < appChildren.getLength(); ++i) {
                Node n = appChildren.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                String childTag = n.getNodeName();
                Element child = (Element) n;
                // android:process, captured uniformly for all five component
                // element types rather than per-branch, because only
                // <activity>/<activity-alias>/<receiver> keep a per-component
                // entry with somewhere to put it -- ServiceEntry and
                // ProviderEntry deliberately carry only the fields their
                // consumers need. See componentProcesses for what reads this.
                // Name normalization (a leading '.' is package-relative) is
                // the same rule every branch below applies to android:name.
                if ("activity".equals(childTag) || "activity-alias".equals(childTag)
                        || "receiver".equals(childTag) || "service".equals(childTag)
                        || "provider".equals(childTag)) {
                    String cn = child.getAttributeNS(ANDROID_NS, "name");
                    String cp = child.getAttributeNS(ANDROID_NS, "process");
                    if (cn != null && !cn.isEmpty() && cp != null && !cp.isEmpty()) {
                        if (cn.startsWith(".")) cn = pkg + cn;
                        componentProcesses.put(cn, cp);
                    }
                }
                if ("meta-data".equals(childTag)) {
                    // Activities have their own <meta-data> children;
                    // we only pull the application-scoped ones because
                    // that's what PackageManager.getApplicationInfo()
                    // returns as ApplicationInfo.metaData.
                    String name = child.getAttributeNS(ANDROID_NS, "name");
                    String value = child.getAttributeNS(ANDROID_NS, "value");
                    // AOSP treats android:value and android:resource as two
                    // distinct attributes: a `resource` attribute carries a
                    // TYPE_REFERENCE whose RAW resource id is what lands in
                    // the Bundle (that is the id FileProvider hands to
                    // Resources.getXml for its <paths>), while `value`
                    // carries a resolved value. Record whichever one
                    // actually carried the data, and read the AXML type
                    // annotation from THAT SAME attribute -- BinaryXmlParser
                    // .setAttr stores the type tag under the carrying
                    // attribute's own local name, so a hardcoded "value"
                    // lookup silently missed every android:resource= entry
                    // and left it untyped (a Bundle String of the raw
                    // "@0x7f..." token instead of an int resource id).
                    String valueAttr = "value";
                    if (value == null || value.isEmpty()) {
                        value = child.getAttributeNS(ANDROID_NS, "resource");
                        valueAttr = "resource";
                    }
                    if (name != null && !name.isEmpty()) {
                        meta.put(name, value != null ? value : "");
                        String vt = child.getAttributeNS(
                            BinaryXmlParser.MACAPK_TYPE_NS, valueAttr);
                        if (vt != null && !vt.isEmpty()) metaTypes.put(name, vt);
                    }
                } else if ("service".equals(childTag)) {
                    // <service> elements declare background components.
                    // We don't actually run them as Android Services
                    // (no system process to bind them to), but the
                    // declaration plus any <meta-data> children is
                    // visible to PackageManager.getServiceInfo. That's
                    // load-bearing for Firebase ComponentDiscovery
                    // (which queries the metadata of the special
                    // "com.google.firebase.components.ComponentDiscoveryService"
                    // to find which Firebase Registrars are present).
                    String name = child.getAttributeNS(ANDROID_NS, "name");
                    if (name == null || name.isEmpty()) continue;
                    if (name.startsWith(".")) name = pkg + name;
                    boolean enabled = !"false".equalsIgnoreCase(
                        child.getAttributeNS(ANDROID_NS, "enabled"));
                    // MACAPK_MANIFEST_EXPORTED_DEFAULT: AOSP ParsedServiceUtils
                    // (android14 :82-84, :168) reads exported only when present,
                    // else exported = has an <intent-filter> -- the same rule as
                    // activities (see parseActivityLike). =0: absent == false.
                    final String svcExportedAttr = child.getAttributeNS(ANDROID_NS, "exported");
                    final boolean svcSetExported = svcExportedAttr != null && !svcExportedAttr.isEmpty();
                    boolean exportedSvc = "true".equalsIgnoreCase(svcExportedAttr);
                    java.util.Map<String, String> svcMeta =
                        new java.util.HashMap<>();
                    java.util.Map<String, String> svcMetaTypes =
                        new java.util.HashMap<>();
                    java.util.List<IntentFilterEntry> svcFilters =
                        new java.util.ArrayList<>();
                    NodeList svcChildren = child.getChildNodes();
                    for (int k = 0; k < svcChildren.getLength(); ++k) {
                        Node sc = svcChildren.item(k);
                        if (sc.getNodeType() != Node.ELEMENT_NODE) continue;
                        // <intent-filter> is what lets PackageManager answer an
                        // IMPLICIT action for this service. Parsed with the same
                        // helper activities and receivers use, so the four
                        // matching dimensions behave identically everywhere.
                        if ("intent-filter".equals(sc.getNodeName())) {
                            svcFilters.add(parseIntentFilter((Element) sc));
                            continue;
                        }
                        if (!"meta-data".equals(sc.getNodeName())) continue;
                        Element sm = (Element) sc;
                        String mname = sm.getAttributeNS(ANDROID_NS, "name");
                        String mvalue = sm.getAttributeNS(ANDROID_NS, "value");
                        // Same android:value-vs-android:resource type-tag
                        // rule as the <application> walk above.
                        String mattr = "value";
                        if (mvalue == null || mvalue.isEmpty()) {
                            mvalue = sm.getAttributeNS(ANDROID_NS, "resource");
                            mattr = "resource";
                        }
                        if (mname != null && !mname.isEmpty()) {
                            svcMeta.put(mname, mvalue != null ? mvalue : "");
                            String vt = sm.getAttributeNS(
                                BinaryXmlParser.MACAPK_TYPE_NS, mattr);
                            if (vt != null && !vt.isEmpty()) svcMetaTypes.put(mname, vt);
                        }
                    }
                    if (!svcSetExported && EXPORTED_DEFAULT) exportedSvc = !svcFilters.isEmpty();
                    services.put(name, new ServiceEntry(
                        name, enabled, exportedSvc, svcMeta, svcMetaTypes,
                        svcFilters));
                } else if ("provider".equals(childTag)) {
                    // ContentProvider declaration. Used by SDKs that
                    // auto-initialize at app start (FirebaseInitProvider
                    // is the canonical example; AndroidX-Startup,
                    // WorkManager, Crashlytics, MobileAds, etc. all
                    // follow the same pattern). On real Android the
                    // framework instantiates these in initOrder order
                    // between Application.attach() and onCreate(); we
                    // mirror that in Boot.java.
                    String name = child.getAttributeNS(ANDROID_NS, "name");
                    if (name == null || name.isEmpty()) continue;
                    if (name.startsWith(".")) name = pkg + name;
                    String authorities = child.getAttributeNS(
                        ANDROID_NS, "authorities");
                    String orderStr = child.getAttributeNS(
                        ANDROID_NS, "initOrder");
                    int order = 0;
                    if (orderStr != null && !orderStr.isEmpty()) {
                        try { order = Integer.parseInt(orderStr); }
                        catch (NumberFormatException ignored) {}
                    }
                    // android:enabled, byte-identical to the <service> read
                    // above and the parseActivityLike read below -- AOSP uses
                    // ONE shared routine for all five component types
                    // (ParsedMainComponentUtils.java:68-70) and so should we.
                    // Default TRUE.
                    boolean enabledProv = !"false".equalsIgnoreCase(
                        child.getAttributeNS(ANDROID_NS, "enabled"));
                    final String provExportedAttr = child.getAttributeNS(ANDROID_NS, "exported");
                    boolean exported = "true".equalsIgnoreCase(provExportedAttr);
                    if (provExportedAttr == null || provExportedAttr.isEmpty()) {
                        provExportedUnset.add(providers.size());
                    }
                    boolean grant = "true".equalsIgnoreCase(
                        child.getAttributeNS(
                            ANDROID_NS, "grantUriPermissions"));
                    // Capture provider's <meta-data> entries -- same
                    // shape as the service walk above. AndroidX-Startup
                    // and similar auto-init libs encode the list of
                    // their Initializer classes here.
                    java.util.Map<String, String> provMeta =
                        new java.util.HashMap<>();
                    java.util.Map<String, String> provMetaTypes =
                        new java.util.HashMap<>();
                    NodeList pmd = child.getChildNodes();
                    for (int j = 0; j < pmd.getLength(); ++j) {
                        Node mn = pmd.item(j);
                        if (mn.getNodeType() != Node.ELEMENT_NODE) continue;
                        if (!"meta-data".equals(mn.getNodeName())) continue;
                        Element sm = (Element) mn;
                        String mname = sm.getAttributeNS(ANDROID_NS, "name");
                        String mvalue = sm.getAttributeNS(ANDROID_NS, "value");
                        // Same android:value-vs-android:resource type-tag
                        // rule as the <application> walk above. This is the
                        // FileProvider path: androidx's <provider> declares
                        // android.support.FILE_PROVIDER_PATHS via
                        // android:resource="@xml/...", and getPathStrategy
                        // reads it back with Bundle.getInt.
                        String mattr = "value";
                        if (mvalue == null || mvalue.isEmpty()) {
                            mvalue = sm.getAttributeNS(ANDROID_NS, "resource");
                            mattr = "resource";
                        }
                        if (mname != null && !mname.isEmpty()) {
                            provMeta.put(mname, mvalue != null ? mvalue : "");
                            String vt = sm.getAttributeNS(
                                BinaryXmlParser.MACAPK_TYPE_NS, mattr);
                            if (vt != null && !vt.isEmpty()) provMetaTypes.put(mname, vt);
                        }
                    }
                    // ---- permissions + URI/path permission children ----
                    // AOSP PackageParser.parseProvider lines 5111-5135
                    // (android14-release), including BOTH fallbacks:
                    //   readPermission  <- android:readPermission
                    //                   <- android:permission (same element)
                    //                   <- <application android:permission>
                    // and the same chain for writePermission. AOSP distinguishes
                    // an ABSENT attribute (null -> fall through to the next
                    // rung) from a PRESENT-but-EMPTY one (""), which terminates
                    // the chain at null:
                    //     if (str == null) p.info.readPermission =
                    //             owner.applicationInfo.permission;
                    //     else p.info.readPermission =
                    //             str.length() > 0 ? str.intern() : null;
                    // DOM's getAttributeNS() returns "" for BOTH cases, so the
                    // absence test has to be hasAttributeNS() -- see attrRawOrNull
                    // (NOT attrOrNull, which trims and treats empty as absent).
                    // The booleans below keep the plain "true" test the
                    // exported/grant reads above use: verified safe, because BOTH
                    // manifest paths render a boolean as "true"/"false"
                    // (BinaryXmlParser.java:266 TYPE_INT_BOOLEAN, and apktool's
                    // text decode), so "1"/"0" never reaches here.
                    String appPermission = attrRawOrNull(app, "permission");
                    String provPermission = attrRawOrNull(child, "permission");
                    String rp = attrRawOrNull(child, "readPermission");
                    if (rp == null) rp = provPermission;
                    String readPerm = (rp == null)
                        ? appPermission
                        : (rp.length() > 0 ? rp : null);
                    String wp = attrRawOrNull(child, "writePermission");
                    if (wp == null) wp = provPermission;
                    String writePerm = (wp == null)
                        ? appPermission
                        : (wp.length() > 0 ? wp : null);

                    boolean forceUriPerms = "true".equalsIgnoreCase(
                        child.getAttributeNS(ANDROID_NS, "forceUriPermissions"));
                    boolean multiproc = "true".equalsIgnoreCase(
                        child.getAttributeNS(ANDROID_NS, "multiprocess"));
                    boolean syncable = "true".equalsIgnoreCase(
                        child.getAttributeNS(ANDROID_NS, "syncable"));
                    // AOSP lines 5156-5171: flags starts at 0 and takes exactly
                    // these two bits from the manifest.
                    int provFlags = 0;
                    if ("true".equalsIgnoreCase(
                            child.getAttributeNS(ANDROID_NS, "singleUser"))) {
                        provFlags |= android.content.pm.ProviderInfo.FLAG_SINGLE_USER;
                    }
                    if ("true".equalsIgnoreCase(child.getAttributeNS(
                            ANDROID_NS, "visibleToInstantApps"))) {
                        provFlags |= android.content.pm.ProviderInfo
                                        .FLAG_VISIBLE_TO_INSTANT_APP;
                    }

                    // <grant-uri-permission> / <path-permission> children.
                    // AOSP parseProviderTags lines 5240-5375. Two quirks
                    // mirrored deliberately because they are observable:
                    //   (1) within ONE element the path attributes do not each
                    //       add an entry -- each assignment OVERWRITES the local
                    //       `pa`, so the LAST one present wins and the element
                    //       contributes exactly one matcher;
                    //   (2) a <grant-uri-permission> that yields a matcher also
                    //       forces grantUriPermissions = true, regardless of the
                    //       android:grantUriPermissions attribute.
                    java.util.List<android.os.PatternMatcher> uriPats =
                        new java.util.ArrayList<>();
                    java.util.List<android.content.pm.PathPermission> pathPerms =
                        new java.util.ArrayList<>();
                    NodeList provKids = child.getChildNodes();
                    for (int k = 0; k < provKids.getLength(); ++k) {
                        Node kn = provKids.item(k);
                        if (kn.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ke = (Element) kn;
                        String kt = kn.getNodeName();
                        if ("grant-uri-permission".equals(kt)) {
                            android.os.PatternMatcher pa = null;
                            String v = attrRawOrNull(ke, "path");
                            if (v != null) pa = new android.os.PatternMatcher(
                                v, android.os.PatternMatcher.PATTERN_LITERAL);
                            v = attrRawOrNull(ke, "pathPrefix");
                            if (v != null) pa = new android.os.PatternMatcher(
                                v, android.os.PatternMatcher.PATTERN_PREFIX);
                            v = attrRawOrNull(ke, "pathPattern");
                            if (v != null) pa = new android.os.PatternMatcher(
                                v, android.os.PatternMatcher.PATTERN_SIMPLE_GLOB);
                            if (pa != null) { uriPats.add(pa); grant = true; }
                        } else if ("path-permission".equals(kt)) {
                            String pperm  = attrRawOrNull(ke, "permission");
                            String pread  = attrRawOrNull(ke, "readPermission");
                            if (pread == null)  pread = pperm;
                            String pwrite = attrRawOrNull(ke, "writePermission");
                            if (pwrite == null) pwrite = pperm;
                            // AOSP: an element declaring NEITHER permission is
                            // skipped with a warning (non-RIGID_PARSER path).
                            if (pread == null && pwrite == null) continue;
                            android.content.pm.PathPermission pp = null;
                            String v = attrRawOrNull(ke, "path");
                            if (v != null) pp = new android.content.pm.PathPermission(
                                v, android.os.PatternMatcher.PATTERN_LITERAL,
                                pread, pwrite);
                            v = attrRawOrNull(ke, "pathPrefix");
                            if (v != null) pp = new android.content.pm.PathPermission(
                                v, android.os.PatternMatcher.PATTERN_PREFIX,
                                pread, pwrite);
                            v = attrRawOrNull(ke, "pathPattern");
                            if (v != null) pp = new android.content.pm.PathPermission(
                                v, android.os.PatternMatcher.PATTERN_SIMPLE_GLOB,
                                pread, pwrite);
                            v = attrRawOrNull(ke, "pathAdvancedPattern");
                            if (v != null) pp = new android.content.pm.PathPermission(
                                v, android.os.PatternMatcher.PATTERN_ADVANCED_GLOB,
                                pread, pwrite);
                            if (pp != null) pathPerms.add(pp);
                        }
                    }
                    // NULL, not an empty array, when the provider declares none
                    // -- AOSP declares both fields `= null` and gives null a
                    // MEANING (uriPermissionPatterns == null is "all URIs are
                    // allowed"), so an empty array would be a different answer.
                    android.os.PatternMatcher[] uriPatArr = uriPats.isEmpty()
                        ? null
                        : uriPats.toArray(new android.os.PatternMatcher[0]);
                    android.content.pm.PathPermission[] pathPermArr =
                        pathPerms.isEmpty()
                        ? null
                        : pathPerms.toArray(
                            new android.content.pm.PathPermission[0]);

                    providers.add(new ProviderEntry(
                        name, authorities != null ? authorities : "",
                        order, enabledProv, exported, grant,
                        provMeta, provMetaTypes,
                        readPerm, writePerm, forceUriPerms, multiproc,
                        syncable, provFlags, uriPatArr, pathPermArr));
                } else if ("activity".equals(childTag)
                        || "activity-alias".equals(childTag)
                        || "receiver".equals(childTag)) {
                    // v1.0.834: parse the per-component attributes AOSP's
                    // ParsedActivityUtils.parseActivityOrAlias reads, so
                    // PackageManager.getActivityInfo / getReceiverInfo can
                    // answer with real data instead of a synthesised,
                    // metadata-free ActivityInfo.
                    //
                    // The measured cost of NOT doing this (Sgt Puzzles 1.49,
                    // 2026-07-29): androidx TaskStackBuilder
                    // .addNextIntentWithParentStack -> NavUtils
                    // .getParentActivityName -> getActivityInfo(...,
                    // GET_META_DATA).parentActivityName == null AND
                    // metaData == null, so the parent stack came back EMPTY,
                    // GameChooser was never pushed under GamePlay, and
                    // GamePlay's own "no saved state" branch
                    // (NavUtils.navigateUpFromSameTask) threw
                    // IllegalArgumentException "Activity GamePlay does not
                    // have a parent activity name specified" -- the app's
                    // real first screen never launched.
                    ActivityEntry ae = parseActivityLike(child, pkg,
                        "activity-alias".equals(childTag));
                    if (ae == null) continue;
                    if ("receiver".equals(childTag)) receivers.put(ae.name, ae);
                    else                             activities.put(ae.name, ae);
                }
            }
        }
        // <uses-sdk android:minSdkVersion="..." android:targetSdkVersion="..." />
        boolean usesSdkPresent;
        if (TARGETSDK_ZERO) {
            // AOSP android14 ParsingPackageUtils.parseUsesSdk (:1532-1606),
            // reached from parseBaseApkTag (:997) for a DIRECT child of
            // <manifest> only, once per element (so the LAST one wins):
            //     int minVers = ParsingUtils.DEFAULT_MIN_SDK_VERSION;      // 1
            //     int targetVers = ParsingUtils.DEFAULT_TARGET_SDK_VERSION; // 0
            //     ... minSdkVersion present -> minVers = val.data;
            //     val = sa.peekValue(..._targetSdkVersion);
            //     if (val != null) { ... targetVers = val.data; }
            //     else { targetVers = minVers; targetCode = minCode; }
            //     pkg.setMinSdkVersion(minSdkVersion).setTargetSdkVersion(targetSdkVersion);
            // With NO <uses-sdk> none of that runs and the package keeps
            // PackageImpl's initialisers, min 1 / target 0 (:353, :367). The
            // old code below applied "target falls back to min" even when the
            // element was absent, which is how RVTest read 1 here while the
            // emulator refused its install with "found 0". Our C++ reader
            // (window_traits.cpp, has_uses_sdk) already had this right.
            // AOSP takes val.data whatever the INTEGER type is, and our
            // BinaryXmlParser renders TYPE_INT_HEX as "0x..", which a plain
            // Integer.parseInt rejects; attrInt (the helper written for the
            // same bug on android:priority) reads both forms. A codename
            // (non-integer) value is not modelled: attrInt answers the default,
            // so a codename target keeps the min fallback, as before.
            minSdk = DEFAULT_MIN_SDK_VERSION;
            target = DEFAULT_TARGET_SDK_VERSION;
            usesSdkPresent = false;
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element sdk = (Element) n;
                String tag = sdk.getLocalName() != null ? sdk.getLocalName() : sdk.getTagName();
                if (!"uses-sdk".equals(tag)) continue;
                usesSdkPresent = true;
                int minVers = attrInt(sdk, "minSdkVersion", DEFAULT_MIN_SDK_VERSION);
                // Absent (or unparseable) target -> minVers: parseUsesSdk's
                // `else { targetVers = minVers; }`.
                int targetVers = attrInt(sdk, "targetSdkVersion", minVers);
                minSdk = minVers;
                target = targetVers;
            }
        } else {
            // PRE-CHANGE PATH, verbatim (MACAPK_TARGETSDK_ZERO=0).
            NodeList sdks = root.getElementsByTagName("uses-sdk");
            usesSdkPresent = sdks.getLength() > 0;
            if (sdks.getLength() > 0) {
                Element sdk = (Element) sdks.item(0);
                String t = sdk.getAttributeNS(ANDROID_NS, "targetSdkVersion");
                if (t != null && !t.isEmpty()) {
                    try { target = Integer.parseInt(t); } catch (NumberFormatException ignored) {}
                }
                String mn = sdk.getAttributeNS(ANDROID_NS, "minSdkVersion");
                if (mn != null && !mn.isEmpty()) {
                    try { minSdk = Integer.parseInt(mn); } catch (NumberFormatException ignored) {}
                }
            }
            // AOSP PackageParser: targetSdkVersion defaults to minSdkVersion when
            // <uses-sdk> omits it; minSdkVersion defaults to 1. Mirror that so a
            // manifest declaring only one still yields sane non-zero values.
            if (target == 0) target = minSdk > 0 ? minSdk : 1;
            if (minSdk == 0) minSdk = 1;
        }
        if (PROVIDER_EXPORTED_LEGACY && target < 17 /* JELLY_BEAN_MR1 */) {
            for (int i : provExportedUnset) providers.set(i, providers.get(i).withExported(true));
        }

        // <uses-permission> / <uses-permission-sdk-23> / <permission> /
        // <permission-group> / <uses-feature>. All five are DIRECT children
        // of <manifest> (never nested under <application>), so walk root's
        // element children once and dispatch by tag, the way AOSP's
        // ParsingPackageUtils.parseBaseApkTags does.
        java.util.List<UsesPermissionEntry> usesPermissions =
            new java.util.ArrayList<>();
        java.util.Set<String> seenUsesPermissions = new java.util.HashSet<>();
        java.util.List<PermissionEntry> permissions = new java.util.ArrayList<>();
        java.util.List<PermissionGroupEntry> permissionGroups =
            new java.util.ArrayList<>();
        java.util.List<FeatureEntry> reqFeatures = new java.util.ArrayList<>();
        java.util.List<String> queriesPackages = new java.util.ArrayList<>();
        java.util.List<QueriesIntent> queriesIntents = new java.util.ArrayList<>();
        java.util.Set<String> queriesProviders = new java.util.LinkedHashSet<>();
        NodeList rootChildren = root.getChildNodes();
        for (int i = 0; i < rootChildren.getLength(); ++i) {
            Node n = rootChildren.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String tag = n.getNodeName();
            switch (tag) {
                case "uses-permission":
                case "uses-permission-sdk-23":
                // AOSP routes the pre-release spelling to the same parser.
                case "uses-permission-sdk-m": {
                    String name = e.getAttributeNS(ANDROID_NS, "name");
                    if (name == null || name.isEmpty()) break;
                    // AOSP defaults: minSdkVersion Integer.MIN_VALUE,
                    // maxSdkVersion Integer.MAX_VALUE, and the declaration is
                    // DROPPED (never added to the package) when the platform
                    // SDK falls outside that window. Build.VERSION.SDK_INT is
                    // the device value AOSP compares against
                    // (RESOURCES_SDK_INT, which equals SDK_INT outside a
                    // preview build).
                    int lo = attrInt(e, "minSdkVersion", Integer.MIN_VALUE);
                    int hi = attrInt(e, "maxSdkVersion", Integer.MAX_VALUE);
                    int sdk = android.os.Build.VERSION.SDK_INT;
                    if (sdk < lo || sdk > hi) break;
                    if (!seenUsesPermissions.add(name)) break;   // AOSP: dup ignored
                    usesPermissions.add(new UsesPermissionEntry(
                        name, lo, hi, attrInt(e, "usesPermissionFlags", 0)));
                    break;
                }
                case "permission": {
                    String name = e.getAttributeNS(ANDROID_NS, "name");
                    if (name == null || name.isEmpty()) break;
                    // ParsedPermissionUtils.parsePermission: maxSdkVersion
                    // defaults to -1, and `(maxSdkVersion != -1) &&
                    // (maxSdkVersion < Build.VERSION.SDK_INT)` drops the
                    // whole <permission> (returns a null ParsedPermission).
                    int hi = attrInt(e, "maxSdkVersion", -1);
                    if (hi != -1 && hi < android.os.Build.VERSION.SDK_INT) break;
                    String group = e.getAttributeNS(ANDROID_NS, "permissionGroup");
                    if (group != null && group.isEmpty()) group = null;
                    permissions.add(new PermissionEntry(
                        name, group,
                        parseProtectionLevel(
                            e.getAttributeNS(ANDROID_NS, "protectionLevel")),
                        attrInt(e, "permissionFlags", 0)));
                    break;
                }
                case "permission-group": {
                    String name = e.getAttributeNS(ANDROID_NS, "name");
                    if (name == null || name.isEmpty()) break;
                    permissionGroups.add(new PermissionGroupEntry(
                        name, attrInt(e, "priority", 0),
                        attrInt(e, "permissionGroupFlags", 0)));
                    break;
                }
                case "uses-feature": {
                    // parseFeatureInfo: name may be absent, in which case the
                    // declaration is a glEsVersion requirement and ONLY THEN
                    // is android:glEsVersion read. android:required defaults
                    // to true.
                    String name = e.getAttributeNS(ANDROID_NS, "name");
                    if (name != null && name.isEmpty()) name = null;
                    int glEs = 0;
                    if (name == null) {
                        glEs = attrInt(e, "glEsVersion", 0);
                    }
                    boolean required = !"false".equalsIgnoreCase(
                        e.getAttributeNS(ANDROID_NS, "required"));
                    reqFeatures.add(new FeatureEntry(
                        name, attrInt(e, "version", 0), glEs, required));
                    break;
                }
                case "queries": {
                    // API 30 package visibility. AOSP ParsingPackageUtils
                    // .parseQueries walks <package>/<intent>/<provider>
                    // children; we parse only <package android:name>
                    // (AppsFilterUtils.canQueryViaPackage's input) -- see the
                    // queriesPackages field doc for why <intent>/<provider>
                    // are a deliberate gap.
                    NodeList qChildren = e.getChildNodes();
                    for (int qi = 0; qi < qChildren.getLength(); ++qi) {
                        Node qn = qChildren.item(qi);
                        if (qn.getNodeType() != Node.ELEMENT_NODE) continue;
                        if ("intent".equals(qn.getNodeName())) {
                            QueriesIntent q = parseQueriesIntent((Element) qn);
                            if (q != null) queriesIntents.add(q);
                            continue;
                        }
                        if ("provider".equals(qn.getNodeName())) {
                            String auth = ((Element) qn).getAttributeNS(ANDROID_NS, "authorities");
                            if (auth != null) {
                                for (String a : auth.split(";")) {
                                    if (!a.isEmpty()) queriesProviders.add(a);
                                }
                            }
                            continue;
                        }
                        if (!"package".equals(qn.getNodeName())) continue;
                        String qName = ((Element) qn).getAttributeNS(ANDROID_NS, "name");
                        if (qName != null && !qName.isEmpty()) queriesPackages.add(qName);
                    }
                    break;
                }
                default: break;
            }
        }

        // Walk activities. For each, look for an intent-filter that
        // contains BOTH <action android:name="android.intent.action.MAIN">
        // AND <category android:name="android.intent.category.LAUNCHER">.
        //
        // v1.0.92: skip activities declared `android:enabled="false"`.
        // F-Droid declares TWO launchable activities -- a real
        // `MainActivity` (enabled=true, the package manager UI) and a
        // panic-mode `CalculatorActivity` (enabled=false, a fake calc
        // disguise the user can opt into for hostile-environment use).
        // Both have intent-filters with action MAIN + category LAUNCHER.
        // Real Android's PackageManager.queryIntentActivities skips
        // entries where the component is disabled, so the LAUNCHER
        // shows just the real MainActivity. Without that filter,
        // Mac.apk picks the FIRST manifest entry that matches --
        // which happens to be CalculatorActivity in F-Droid's case --
        // and opens the fake calc UI instead of the real app.
        //
        // The `android:enabled` attribute defaults to true when
        // absent (AOSP PackageParser.parseComponentInfoAttrs). Any
        // explicit "false" disables the component. We treat any
        // other value (including "true", missing, or even malformed)
        // as enabled.
        // v1.0.127: walk <activity> AND <activity-alias>. Reddit (and any
        // app shipping configurable launcher icons) declares MainActivity
        // with no intent-filter and routes the launcher through an
        // <activity-alias android:enabled="true" android:name="launcher.default"
        // android:targetActivity="com.reddit.launch.main.MainActivity"> that
        // carries the MAIN/LAUNCHER intent-filter. AOSP PackageParser
        // .parseActivityAlias treats the alias as a launchable component
        // whose actual target is `android:targetActivity`. Without this,
        // any app that uses the alias-as-launcher pattern (Reddit,
        // Snapchat, Discord, Signal, every app with selectable icons) is
        // rejected as "no launchable activity in manifest".
        String launch = findLaunchable(root, pkg, "activity", false, CAT_LAUNCHER);
        if (launch == null) {
            launch = findLaunchable(root, pkg, "activity-alias", true, CAT_LAUNCHER);
        }
        // An Android TV-only app has no LAUNCHER entry: its one way in is a
        // MAIN + LEANBACK_LAUNCHER filter (the leanback launcher's
        // getLeanbackLaunchIntentForPackage, ApplicationPackageManager
        // android14). Mac.apk is the launcher the user opened the app from, so
        // when nothing else is launchable we start that entry, with that
        // category on the intent. It changes nothing for an app that has a
        // LAUNCHER entry (SmartTube declares both), and the device we present
        // stays a normal touch device: no TV ui mode, no leanback feature.
        // Mirrored in runtime/src/apk_unpacker.cpp and window_traits.cpp so the
        // first window takes this activity's orientation and traits.
        String launchCategory = CAT_LAUNCHER;
        if (launch == null) {
            launch = findLaunchable(root, pkg, "activity", false, CAT_LEANBACK_LAUNCHER);
            if (launch == null) {
                launch = findLaunchable(root, pkg, "activity-alias", true, CAT_LEANBACK_LAUNCHER);
            }
            if (launch != null) launchCategory = CAT_LEANBACK_LAUNCHER;
        }

        NativeLog.i(TAG, "package=" + pkg
                  + " version=" + (version.isEmpty() ? "(none)" : version)
                  + " application=" + appClass
                  + " appComponentFactory=" + appComponentFactory
                  + " launchable=" + launch
                  + " minSdk=" + minSdk
                  + " targetSdk=" + target
                  + (usesSdkPresent ? "" : " (no <uses-sdk>)")
                  + " versionCode=" + versionCode
                  + " meta-data entries=" + meta.size()
                  + " providers=" + providers.size()
                  + " services=" + services.size()
                  + " uses-permission=" + usesPermissions.size()
                  + " permission=" + permissions.size()
                  + " permission-group=" + permissionGroups.size()
                  + " uses-feature=" + reqFeatures.size()
                  + " activities=" + activities.size()
                  + " receivers=" + receivers.size()
                  + " queries-package=" + queriesPackages.size());
        for (ActivityEntry a : activities.values()) {
            if (a.parentActivityName != null || a.targetActivity != null
                    || !a.metaData.isEmpty()) {
                NativeLog.i(TAG, "  activity: " + a.name
                          + (a.targetActivity != null
                              ? " target=" + a.targetActivity : "")
                          + (a.parentActivityName != null
                              ? " parent=" + a.parentActivityName : "")
                          + " meta=" + a.metaData.size()
                          + " filters=" + a.filters.size());
            }
        }
        for (ProviderEntry p : providers) {
            NativeLog.i(TAG, "  provider: " + p.name
                      + " initOrder=" + p.initOrder
                      + " authorities=" + p.authorities);
        }
        if (appTheme != null) {
            NativeLog.i(TAG, "  application theme: " + appTheme);
        }
        Manifest built = new Manifest(pkg, appClass, appComponentFactory, launch, target, meta, version,
                            providers, services, appTheme, metaTypes,
                            minSdk, versionCode, versionRaw,
                            usesPermissions, permissions, permissionGroups,
                            reqFeatures, activities, receivers, appLabel,
                            resizeableActivity, maxAspectRatio,
                            applicationEnabled,
                            componentProcesses, queriesPackages);
        built.queriesIntents = queriesIntents;
        built.launchableCategory = launchCategory;
        built.queriesProviders = queriesProviders;
        built.applicationIconRaw = appIconRaw;
        built.requestLegacyExternalStorageRaw = requestLegacyRaw;
        built.preserveLegacyExternalStorageRaw = preserveLegacyRaw;
        return built;
    }

    /** One `<queries><intent>` as ParsingPackageUtils.parseQueries builds it, or
     *  null where AOSP rejects it (no action and no data; more than one action,
     *  type, scheme or host). AOSP fails the whole package parse there; an
     *  installed app never has one, so skipping it is the same answer. */
    private static QueriesIntent parseQueriesIntent(Element intent) {
        java.util.List<String> actions = new java.util.ArrayList<>();
        java.util.List<String> cats = new java.util.ArrayList<>();
        java.util.List<String> schemes = new java.util.ArrayList<>();
        java.util.List<String> hosts = new java.util.ArrayList<>();
        java.util.List<String> types = new java.util.ArrayList<>();
        NodeList kids = intent.getChildNodes();
        for (int i = 0; i < kids.getLength(); ++i) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ke = (Element) k;
            String n = ke.getAttributeNS(ANDROID_NS, "name");
            switch (k.getNodeName()) {
                case "action":   if (n != null && !n.isEmpty()) actions.add(n); break;
                case "category": if (n != null && !n.isEmpty()) cats.add(n); break;
                case "data": {
                    String sc = ke.getAttributeNS(ANDROID_NS, "scheme");
                    String ho = ke.getAttributeNS(ANDROID_NS, "host");
                    String mt = ke.getAttributeNS(ANDROID_NS, "mimeType");
                    if (sc != null && !sc.isEmpty() && !schemes.contains(sc)) schemes.add(sc);
                    if (ho != null && !ho.isEmpty() && !hosts.contains(ho)) hosts.add(ho);
                    if (mt != null && !mt.isEmpty() && !types.contains(mt)) types.add(mt);
                    break;
                }
                default: break;
            }
        }
        if (schemes.isEmpty() && types.isEmpty() && actions.isEmpty()) return null;
        if (actions.size() > 1 || types.size() > 1 || schemes.size() > 1 || hosts.size() > 1) {
            return null;
        }
        String scheme = schemes.isEmpty() ? null : schemes.get(0);
        String host = hosts.isEmpty() ? null : hosts.get(0);
        String type = types.isEmpty() ? null : types.get(0);
        if (type != null && !type.contains("/")) type = type + "/*";
        if (type != null && scheme == null) {
            scheme = "content";
            host = "*";
        }
        return new QueriesIntent(actions.isEmpty() ? null : actions.get(0), cats, scheme,
                host, type);
    }

    /** Parse one `<activity>` / `<activity-alias>` / `<receiver>` element.
     *
     *  AOSP reference: ParsedActivityUtils.parseActivityOrAlias +
     *  ParsedMainComponentUtils.parseMainComponent (aosp-mirror
     *  platform_frameworks_base, core/java/android/content/pm/parsing/
     *  component/), and the attribute value tables in
     *  core/res/res/values/attrs_manifest.xml. Defaults follow AOSP:
     *  enabled=true, exported defaults to (has an intent-filter) for
     *  targetSdk < 31 and is REQUIRED at 31+, launchMode=standard(0),
     *  screenOrientation=SCREEN_ORIENTATION_UNSPECIFIED(-1). */
    private static ActivityEntry parseActivityLike(Element child, String pkg,
                                                   boolean isAlias) {
        String name = child.getAttributeNS(ANDROID_NS, "name");
        if (name == null || name.isEmpty()) return null;
        if (name.startsWith(".")) name = pkg + name;

        String target = attrOrNull(child, "targetActivity");
        if (target != null && target.startsWith(".")) target = pkg + target;
        String parent = attrOrNull(child, "parentActivityName");
        if (parent != null && parent.startsWith(".")) parent = pkg + parent;

        boolean enabled = !"false".equalsIgnoreCase(
            child.getAttributeNS(ANDROID_NS, "enabled"));
        // MACAPK_MANIFEST_EXPORTED_DEFAULT (default ON). AOSP reads
        // android:exported only when it is PRESENT (ParsedActivityUtils.java
        // :379-381, android14-release) and otherwise sets it from "has an
        // <intent-filter>" after the children are parsed (:486-498). The old
        // rule read an absent attribute as false, which made 10 of 61 corpus
        // launchers non-exported (HCR 1.8.1, Flappy, Frozen Bubble among the
        // installed ones). =0 restores absent == false.
        final String exportedAttr = child.getAttributeNS(ANDROID_NS, "exported");
        final boolean setExported = exportedAttr != null && !exportedAttr.isEmpty();
        boolean exported = "true".equalsIgnoreCase(exportedAttr);
        boolean dba = "true".equalsIgnoreCase(
            child.getAttributeNS(ANDROID_NS, "directBootAware"));

        // <meta-data> children. Same android:value-vs-android:resource
        // type-tag rule as the <application> / <service> / <provider> walks.
        java.util.Map<String, String> md = new java.util.HashMap<>();
        java.util.Map<String, String> mdTypes = new java.util.HashMap<>();
        java.util.List<IntentFilterEntry> filters = new java.util.ArrayList<>();
        NodeList kids = child.getChildNodes();
        for (int i = 0; i < kids.getLength(); ++i) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String tag = n.getNodeName();
            if ("meta-data".equals(tag)) {
                String mname = e.getAttributeNS(ANDROID_NS, "name");
                String mvalue = e.getAttributeNS(ANDROID_NS, "value");
                String mattr = "value";
                if (mvalue == null || mvalue.isEmpty()) {
                    mvalue = e.getAttributeNS(ANDROID_NS, "resource");
                    mattr = "resource";
                }
                if (mname != null && !mname.isEmpty()) {
                    md.put(mname, mvalue != null ? mvalue : "");
                    String vt = e.getAttributeNS(
                        BinaryXmlParser.MACAPK_TYPE_NS, mattr);
                    if (vt != null && !vt.isEmpty()) mdTypes.put(mname, vt);
                }
            } else if ("intent-filter".equals(tag)) {
                filters.add(parseIntentFilter(e));
            }
        }
        if (!setExported && EXPORTED_DEFAULT) exported = !filters.isEmpty();

        return new ActivityEntry(name, target, parent,
            attrOrNull(child, "permission"),
            attrOrNull(child, "taskAffinity"),
            attrOrNull(child, "process"),
            attrOrNull(child, "theme"),
            attrOrNull(child, "label"),
            attrOrNull(child, "icon"),
            attrOrNull(child, "logo"),
            attrEnum(child, "launchMode", LAUNCH_MODES, 0),
            attrEnum(child, "documentLaunchMode", DOCUMENT_LAUNCH_MODES, 0),
            attrEnum(child, "screenOrientation", SCREEN_ORIENTATIONS, -1),
            attrFlags(child, "configChanges", CONFIG_CHANGES),
            attrFlags(child, "windowSoftInputMode", SOFT_INPUT_MODES),
            attrFlags(child, "uiOptions", UI_OPTIONS),
            enabled, exported, dba, isAlias, md, mdTypes, filters);
    }

    private static IntentFilterEntry parseIntentFilter(Element f) {
        java.util.List<String> actions = new java.util.ArrayList<>();
        java.util.List<String> cats    = new java.util.ArrayList<>();
        java.util.List<String> schemes = new java.util.ArrayList<>();
        java.util.List<String> mimes   = new java.util.ArrayList<>();
        java.util.List<String> hosts   = new java.util.ArrayList<>();
        NodeList kids = f.getChildNodes();
        for (int i = 0; i < kids.getLength(); ++i) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String v = e.getAttributeNS(ANDROID_NS, "name");
            switch (n.getNodeName()) {
                case "action":   if (v != null && !v.isEmpty()) actions.add(v); break;
                case "category": if (v != null && !v.isEmpty()) cats.add(v);    break;
                case "data": {
                    // AOSP allows several <data> elements, each contributing
                    // independently to the filter's scheme / type sets.
                    String s = e.getAttributeNS(ANDROID_NS, "scheme");
                    if (s != null && !s.isEmpty()) schemes.add(s);
                    String m = e.getAttributeNS(ANDROID_NS, "mimeType");
                    if (m != null && !m.isEmpty()) mimes.add(m);
                    String h = e.getAttributeNS(ANDROID_NS, "host");
                    if (h != null && !h.isEmpty()) hosts.add(h);
                    break;
                }
                default: break;
            }
        }
        // android:priority on the <intent-filter> itself, NOT on its children.
        // attrInt, NOT Integer.parseInt: the BINARY manifest path renders
        // TYPE_INT_DEC as decimal and TYPE_INT_HEX as "0x...", and apktool's text
        // decode emits either form (see attrInt's own header). A hand-rolled
        // parseInt throws on the hex form and a swallowing catch turns that into
        // priority 0 -- which silently TIES firebase's -500 fallback with the
        // app's real handler and makes the whole ranking inert.
        int prio = attrInt(f, "priority", 0);
        boolean autoVerify = "true".equalsIgnoreCase(f.getAttributeNS(ANDROID_NS, "autoVerify"));
        return new IntentFilterEntry(actions, cats, schemes, mimes, prio, hosts, autoVerify);
    }

    /** An android:-namespaced attribute, or null when absent/empty. */
    private static String attrOrNull(Element e, String attr) {
        String s = e.getAttributeNS(ANDROID_NS, attr);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    // Enum / flag tables straight out of AOSP
    // core/res/res/values/attrs_manifest.xml. Both manifest paths must be
    // accepted: the BINARY manifest renders these attributes as their INTEGER
    // value (TYPE_INT_DEC / TYPE_INT_HEX -> "2" / "0x00000480"), while the
    // apktool TEXT decode emits the symbolic name ("singleTask",
    // "keyboard|orientation").
    private static final String[][] LAUNCH_MODES = {
        { "standard", "0" }, { "singleTop", "1" }, { "singleTask", "2" },
        { "singleInstance", "3" }, { "singleInstancePerTask", "4" },
    };
    private static final String[][] DOCUMENT_LAUNCH_MODES = {
        { "none", "0" }, { "intoExisting", "1" }, { "always", "2" },
        { "never", "3" },
    };
    private static final String[][] SCREEN_ORIENTATIONS = {
        { "unspecified", "-1" }, { "landscape", "0" }, { "portrait", "1" },
        { "user", "2" }, { "behind", "3" }, { "sensor", "4" },
        { "nosensor", "5" }, { "sensorLandscape", "6" },
        { "sensorPortrait", "7" }, { "reverseLandscape", "8" },
        { "reversePortrait", "9" }, { "fullSensor", "10" },
        { "userLandscape", "11" }, { "userPortrait", "12" },
        { "fullUser", "13" }, { "locked", "14" },
    };
    private static final String[][] CONFIG_CHANGES = {
        { "mcc", "0x0001" }, { "mnc", "0x0002" }, { "locale", "0x0004" },
        { "touchscreen", "0x0008" }, { "keyboard", "0x0010" },
        { "keyboardHidden", "0x0020" }, { "navigation", "0x0040" },
        { "orientation", "0x0080" }, { "screenLayout", "0x0100" },
        { "uiMode", "0x0200" }, { "screenSize", "0x0400" },
        { "smallestScreenSize", "0x0800" }, { "density", "0x1000" },
        { "layoutDirection", "0x2000" }, { "colorMode", "0x4000" },
        { "grammaticalGender", "0x8000" },
        { "fontWeightAdjustment", "0x10000000" },
        { "fontScale", "0x40000000" },
    };
    private static final String[][] SOFT_INPUT_MODES = {
        { "stateUnspecified", "0" }, { "stateUnchanged", "1" },
        { "stateHidden", "2" }, { "stateAlwaysHidden", "3" },
        { "stateVisible", "4" }, { "stateAlwaysVisible", "5" },
        { "adjustUnspecified", "0x00" }, { "adjustResize", "0x10" },
        { "adjustPan", "0x20" }, { "adjustNothing", "0x30" },
    };
    private static final String[][] UI_OPTIONS = {
        { "none", "0" }, { "splitActionBarWhenNarrow", "1" },
    };

    /** Read an enum-valued manifest attribute: accept either the symbolic name
     *  (apktool text decode) or the raw integer (binary manifest). */
    private static int attrEnum(Element e, String attr, String[][] table, int def) {
        String s = attrOrNull(e, attr);
        if (s == null) return def;
        for (String[] row : table) {
            if (row[0].equalsIgnoreCase(s)) return (int) parseNum(row[1], def);
        }
        try { return (int) parseNum(s, def); }
        catch (NumberFormatException ignored) { return def; }
    }

    /** Read a flag-valued manifest attribute: accept either a '|'-separated
     *  list of symbolic names (apktool) or the raw integer (binary). */
    private static int attrFlags(Element e, String attr, String[][] table) {
        String s = attrOrNull(e, attr);
        if (s == null) return 0;
        if (s.indexOf('|') < 0) {
            // A single token could still be a symbolic name.
            boolean symbolic = false;
            for (String[] row : table) {
                if (row[0].equalsIgnoreCase(s)) { symbolic = true; break; }
            }
            if (!symbolic) {
                try { return (int) parseNum(s, 0); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        int out = 0;
        for (String tok : s.split("\\|")) {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            for (String[] row : table) {
                if (row[0].equalsIgnoreCase(tok)) {
                    out |= (int) parseNum(row[1], 0);
                    break;
                }
            }
        }
        return out;
    }

    private static long parseNum(String s, long def) {
        s = s.trim();
        if (s.isEmpty()) return def;
        boolean neg = s.startsWith("-");
        if (neg) s = s.substring(1);
        long v;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            v = Long.parseLong(s.substring(2), 16);
        } else {
            v = Long.parseLong(s);
        }
        return neg ? -v : v;
    }

    /** Read an android:-namespaced integer attribute. The BINARY manifest
     *  path renders TYPE_INT_DEC as decimal and TYPE_INT_HEX as "0x...",
     *  and apktool's text decode emits either form, so accept both.
     *  Returns `def` when the attribute is absent or unparseable, which is
     *  what AOSP's TypedArray.getInt(attr, defaultValue) does for a value
     *  outside TYPE_FIRST_INT..TYPE_LAST_INT. */
    private static int attrInt(Element e, String attr, int def) {
        String s = e.getAttributeNS(ANDROID_NS, attr);
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        try {
            // A leading sign has to be stripped BEFORE the hex branch, the way
            // this file's sibling parseNum already does it: "-0x1f4" otherwise
            // reaches Long.parseLong("1f4", 16) with the '-' still attached to
            // the wrong side and throws, and the catch silently returns `def`.
            // For android:priority that turns -500 into 0, which TIES firebase's
            // fallback service with the app's real handler -- a defect that
            // reads as "the ranking does nothing" rather than as a parse error.
            boolean neg = s.startsWith("-");
            String body = neg ? s.substring(1) : s;
            int v;
            if (body.startsWith("0x") || body.startsWith("0X")) {
                v = (int) Long.parseLong(body.substring(2), 16);
            } else {
                v = Integer.parseInt(body);
            }
            return neg ? -v : v;
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /** android:protectionLevel is a flag-enum: the BINARY manifest carries
     *  it as an int (TYPE_INT_HEX/DEC), while apktool's text decode writes
     *  the symbolic `|`-separated spelling ("signature|privileged"). Accept
     *  both and fall back to AOSP's documented default,
     *  PermissionInfo.PROTECTION_NORMAL, when the attribute is absent or
     *  unrecognized -- ParsedPermissionUtils.parsePermission passes
     *  PROTECTION_NORMAL as the TypedArray.getInt default.
     *
     *  The constants come from android.content.pm.PermissionInfo rather
     *  than literals so the two never drift; they are compile-time
     *  constants, so this does not class-init PermissionInfo during boot. */
    private static int parseProtectionLevel(String s) {
        if (s == null) return android.content.pm.PermissionInfo.PROTECTION_NORMAL;
        s = s.trim();
        if (s.isEmpty()) return android.content.pm.PermissionInfo.PROTECTION_NORMAL;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException notAnInt) {
            // Symbolic form. The base level is the low byte; every other
            // token ORs in a protection FLAG.
            int level = android.content.pm.PermissionInfo.PROTECTION_NORMAL;
            for (String tok : s.split("\\|")) {
                switch (tok.trim()) {
                    case "normal":
                        level |= android.content.pm.PermissionInfo.PROTECTION_NORMAL; break;
                    case "dangerous":
                        level |= android.content.pm.PermissionInfo.PROTECTION_DANGEROUS; break;
                    case "signature":
                        level |= android.content.pm.PermissionInfo.PROTECTION_SIGNATURE; break;
                    case "signatureOrSystem":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_SIGNATURE_OR_SYSTEM; break;
                    case "internal":
                        level |= android.content.pm.PermissionInfo.PROTECTION_INTERNAL; break;
                    case "privileged":
                    case "system":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_PRIVILEGED; break;
                    case "development":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_DEVELOPMENT; break;
                    case "appop":
                        level |= android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP; break;
                    case "pre23":
                        level |= android.content.pm.PermissionInfo.PROTECTION_FLAG_PRE23; break;
                    case "installer":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_INSTALLER; break;
                    case "verifier":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_VERIFIER; break;
                    case "preinstalled":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_PREINSTALLED; break;
                    case "setup":
                        level |= android.content.pm.PermissionInfo.PROTECTION_FLAG_SETUP; break;
                    case "instant":
                        level |= android.content.pm.PermissionInfo.PROTECTION_FLAG_INSTANT; break;
                    case "runtime":
                        level |= android.content.pm.PermissionInfo
                                     .PROTECTION_FLAG_RUNTIME_ONLY; break;
                    default: break;   // unknown token: AOSP would not set a bit
                }
            }
            return level;
        }
    }

    /** v1.0.127: shared component-walker for <activity> and
     *  <activity-alias>. Returns the fully-qualified class name of the
     *  first enabled component whose intent-filter declares both action
     *  MAIN and `category` (LAUNCHER, or LEANBACK_LAUNCHER for the TV-only
     *  fallback), or null if none is found.
     *
     *  For <activity-alias>, the launchable target is the value of
     *  `android:targetActivity` (the alias's `android:name` is its own
     *  identifier in the package, not a class to instantiate). For
     *  <activity>, it's the `android:name`. AOSP
     *  `PackageParser.parseActivityAlias` enforces this distinction.
     *
     *  `enabled="false"` skips the component (matches AOSP
     *  PackageManager.queryIntentActivities behavior).
     *
     *  Names are normalised: a leading `.` is replaced by the package
     *  prefix; a bare class name (no dots) is also packaged. */
    private static final String CAT_LAUNCHER = "android.intent.category.LAUNCHER";
    private static final String CAT_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER";

    private static String findLaunchable(Element root, String pkg,
                                          String tagName, boolean useTarget,
                                          String category) {
        NodeList components = root.getElementsByTagName(tagName);
        for (int i = 0; i < components.getLength(); ++i) {
            Element comp = (Element) components.item(i);
            // ONE spelling of the android:enabled predicate across this file.
            // This site used case-SENSITIVE `"false".equals(...)` while the
            // <service>, <provider> and parseActivityLike reads all used
            // equalsIgnoreCase. Both happen to work today because every
            // manifest path we have renders the boolean as literal
            // "true"/"false", so this is drift-prevention rather than a live
            // bug -- but two implementations of one rule is how they diverge.
            //
            // ⚠ LOAD-BEARING, do not weaken: F-Droid ships an enabled=false
            // panic CalculatorActivity that ALSO carries MAIN/LAUNCHER
            // (v1.0.92). Dropping this test opens a fake calculator instead of
            // the app.
            if ("false".equalsIgnoreCase(
                    comp.getAttributeNS(ANDROID_NS, "enabled"))) continue;

            String target = useTarget
                ? comp.getAttributeNS(ANDROID_NS, "targetActivity")
                : comp.getAttributeNS(ANDROID_NS, "name");
            if (target == null || target.isEmpty()) continue;

            NodeList children = comp.getChildNodes();
            for (int j = 0; j < children.getLength(); ++j) {
                Node n = children.item(j);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!"intent-filter".equals(n.getNodeName())) continue;

                boolean hasMain = false, hasLauncher = false;
                NodeList ifChildren = n.getChildNodes();
                for (int k = 0; k < ifChildren.getLength(); ++k) {
                    Node m = ifChildren.item(k);
                    if (m.getNodeType() != Node.ELEMENT_NODE) continue;
                    String t = m.getNodeName();
                    String name = ((Element) m).getAttributeNS(ANDROID_NS, "name");
                    if ("action".equals(t)
                        && "android.intent.action.MAIN".equals(name)) hasMain = true;
                    else if ("category".equals(t)
                        && category.equals(name)) hasLauncher = true;
                }
                if (hasMain && hasLauncher) {
                    if (target.startsWith(".")) target = pkg + target;
                    else if (!target.contains(".")) target = pkg + "." + target;
                    return target;
                }
            }
        }
        return null;
    }
}
