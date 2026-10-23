// MacApkPackageManager -- the "single APK, always installed" view of
// android.content.pm.PackageManager. We hand back our own package's
// metadata for self-queries and empty results for everything else.
// Real Android's PackageManager hits a system service; here we just
// answer with whatever the loader knows about the app.
package com.macapk.loader;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;

import java.util.Collections;
import java.util.List;
import java.util.Map;

// AOSP fidelity: the deprecation usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("deprecation")
public final class MacApkPackageManager extends PackageManager {
    private final String packageName;
    // Install-style metadata for getApplicationInfo(): the APK on disk,
    // the per-app working directory, and the directory translated
    // dylibs land in. Engines (cocos2d-x in particular) read these to
    // mmap the APK or look up native libraries by file path; if they
    // come back null the engine logs an empty path and silently fails
    // to bootstrap. All three may be null if the loader hasn't passed
    // them in yet (kept the no-arg constructor as a fallback).
    private final String apkPath;
    private final String dataDir;
    /** The data dir to REPORT as ApplicationInfo.dataDir (see makeAppInfo). */
    private volatile String reportedDataDir;

    /** v1.0.1641: set by Application once Boot knows the app's data dir. */
    public void macapkSetReportedDataDir(String dir) {
        this.reportedDataDir = dir;
    }
    private final String nativeLibraryDir;

    /** Application-scope manifest <meta-data> entries. Engines like
     *  axmol read `android.app.lib_name` from here in
     *  onLoadNativeLibraries to find their native blob. May be null if
     *  the loader didn't pass it in. */
    private final java.util.Map<String, String> applicationMetaData;
    /** Parallel Android-type map for applicationMetaData (set via
     *  macapkSetApplicationMetaTypes; empty for the text-manifest
     *  path). */
    private java.util.Map<String, String> applicationMetaDataTypes;

    /** Boot installs the binary-manifest type map after construction
     *  so the existing constructor surface stays untouched. */
    public void macapkSetApplicationMetaTypes(java.util.Map<String, String> t) {
        if (t != null) this.applicationMetaDataTypes = t;
    }

    // Manifest version / SDK metadata. Real Android always populates
    // ApplicationInfo.{min,target}SdkVersion and PackageInfo.{versionName,
    // versionCode}; leaving them 0 / "1.0" made Unity log "Min API Level
    // '0'" and crash on an empty version string. Set via a post-construct
    // setter (mirrors macapkSetApplicationMetaTypes) so the constructor
    // surface stays untouched. Defaults match AOSP (minSdk 1).
    private int    minSdk      = 1;
    // targetSdk defaults to 0 = UNKNOWN, deliberately, and it is the BINDING half
    // of the pair. The targetSdk compat gates read a 0 as "not resolved yet"
    // through android.app.Application.macapkTargetSdkKnown (they used to test
    // `targetSdkVersion > 0`), and several LATCH process-wide, so a wrong first
    // read is permanent. With this at 1 the sentinel could never fire: 1 is also
    // a legitimate declared value (Android 1.0). A REAL 0 -- a manifest with no
    // <uses-sdk>, AOSP's DEFAULT_TARGET_SDK_VERSION -- is told apart by the
    // manifest's bind (Application.macapkBindTargetSdk, MACAPK_TARGETSDK_ZERO),
    // not by this field, which simply stays 0 for it. Application.java
    // sets its own default to 0 too, but getApplicationInfo() routes through this
    // PM, whose value is what lands in the ApplicationInfo -- so BOTH must be 0 or
    // the path actually taken still answers 1.
    // minSdk stays 1: that IS AOSP's default for an undeclared minSdkVersion, and
    // nothing uses it as an "unknown" sentinel.
    private int    targetSdk   = 0;
    private String versionName = "1.0";
    private int    versionCode = 1;
    public void macapkSetVersionInfo(int minSdk, int targetSdk,
                                     String versionName, int versionCode) {
        if (minSdk > 0)    this.minSdk    = minSdk;
        if (targetSdk > 0) this.targetSdk = targetSdk;
        if (versionName != null && !versionName.isEmpty())
                           this.versionName = versionName;
        if (versionCode > 0) this.versionCode = versionCode;
    }

    /** Service-class-name -> ServiceEntry (with per-service metadata)
     *  built from <service> elements in the manifest. Consumed by
     *  getServiceInfo(ComponentName, GET_META_DATA). Boot.java passes
     *  the parsed map in; null means "no services declared" and every
     *  getServiceInfo call throws NameNotFoundException. */
    private final Map<String, Manifest.ServiceEntry> services;

    /** Provider-class-name -> ProviderEntry, mirroring the services
     *  field. Populated by Boot.java from manifest.providers. Used by
     *  getProviderInfo(ComponentName, GET_META_DATA), specifically
     *  androidx.startup.InitializationProvider.onCreate which reads
     *  its own <meta-data> children to enumerate Initializers. */
    private final Map<String, Manifest.ProviderEntry> providers;

    public MacApkPackageManager(String packageName) {
        this(packageName, null, null, null, null, null, null);
    }

    public MacApkPackageManager(String packageName, String apkPath,
                                 String dataDir, String nativeLibraryDir) {
        this(packageName, apkPath, dataDir, nativeLibraryDir, null, null, null);
    }

    public MacApkPackageManager(String packageName, String apkPath,
                                 String dataDir, String nativeLibraryDir,
                                 java.util.Map<String, String> applicationMetaData) {
        this(packageName, apkPath, dataDir, nativeLibraryDir,
             applicationMetaData, null, null);
    }

    public MacApkPackageManager(String packageName, String apkPath,
                                 String dataDir, String nativeLibraryDir,
                                 java.util.Map<String, String> applicationMetaData,
                                 Map<String, Manifest.ServiceEntry> services) {
        this(packageName, apkPath, dataDir, nativeLibraryDir,
             applicationMetaData, services, null);
    }

    public MacApkPackageManager(String packageName, String apkPath,
                                 String dataDir, String nativeLibraryDir,
                                 java.util.Map<String, String> applicationMetaData,
                                 Map<String, Manifest.ServiceEntry> services,
                                 Map<String, Manifest.ProviderEntry> providers) {
        this.packageName      = packageName != null ? packageName : "com.macapk.unknown";
        this.apkPath          = apkPath;
        this.dataDir          = dataDir;
        this.nativeLibraryDir = nativeLibraryDir;
        this.applicationMetaData = applicationMetaData;
        this.applicationMetaDataTypes = java.util.Collections.emptyMap();
        this.services         = services  != null ? services  : Collections.emptyMap();
        this.providers        = providers != null ? providers : Collections.emptyMap();
    }

    @Override public PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException {
        // AOSP: getPackageInfo throws NameNotFoundException for a package that is
        // NOT installed. Mac.apk runs exactly one APK, so only the running app
        // (this.packageName) is installed -- a BROWSED / other package is not.
        // Previously we returned the running app's PackageInfo for EVERY query, so
        // F-Droid's AppDetails saw every browsed app as installed and showed
        // "Uninstall/Update" instead of "Install". Answering truthfully (throw for
        // non-self) is the AOSP-correct signal; callers of getPackageInfo are
        // written to handle NameNotFoundException. Env rollback
        // MACAPK_PM_SELF_ONLY=0 restores the legacy always-return for A/B.
        //
        // Registry consult (MACAPK_PM_REGISTRY, default ON): a package another
        // Mac.apk install registered IS installed on this machine, so answer for
        // it instead of throwing -- PROVIDED the caller can actually see it on a
        // real device (packageVisible: API 30 package-visibility filtering, see
        // its header). The reserved-Google deny-set inside InstalledRegistry
        // keeps a registry row from ever resurrecting com.google.android.gms/
        // vending/gsf (the v1.0.847 GMS-absent contract) regardless of
        // visibility. This consult is also the F-Droid "Install -> Open"
        // button-flip trigger.
        // The synthetic host "browser" package. queryIntentActivities hands out a
        // ResolveInfo naming com.macapk.host, and an app that finds a browser row
        // then asks about that package -- so denying it exists contradicts the
        // answer we just gave. AppAuth does exactly this
        // (getPackageInfo(pkg, GET_SIGNATURES) inside BrowserDescriptor).
        //
        // ⚠ signatures MUST be non-null: BrowserDescriptor's constructor takes
        // `arraylength` on it immediately, and it catches only
        // NameNotFoundException -- an NPE there is uncaught and kills the sign-in
        // with a stack trace instead of a graceful "no browser". Measured
        // 2026-08-24 while fixing "oauth failed because no browser found".
        //
        // This is NOT a fabricated app: we really can open the URL, via
        // NSWorkspace. It advertises no capability we cannot honour -- note
        // resolveService stays null for CustomTabsService, which is what makes
        // AppAuth set useCustomTab=false and fall back to plain ACTION_VIEW.
        if (MACAPK_HOST_PACKAGE.equals(packageName)) {
            PackageInfo hi = new PackageInfo();
            hi.packageName = MACAPK_HOST_PACKAGE;
            hi.versionName = "1.0";
            hi.versionCode = 1;
            hi.longVersionCode = 1;
            hi.signatures = new android.content.pm.Signature[] {
                new android.content.pm.Signature(new byte[0])
            };
            android.content.pm.ApplicationInfo hai =
                new android.content.pm.ApplicationInfo();
            hai.packageName = MACAPK_HOST_PACKAGE;
            hai.enabled = true;
            hi.applicationInfo = hai;
            return hi;
        }
        // The synthetic Play Store, present only when Mac.apk's GMS layer is on
        // (com.macapk.loader.PlayServices.vendingPresent()). It is NOT a
        // fabricated app: we really do host com.android.vending's licensing
        // service in-process and really do answer it by asking Google with the
        // user's own account. An app that finds this package and binds to it
        // gets a working service.
        //
        // ⚠ THE SIGNATURE IS OURS AND SAYS SO. PackageInfo.signatures carries a
        // genuine self-signed certificate whose subject is CN=Mac.apk Platform
        // (com.macapk.gms.PlatformSignature). It will never equal Google's, so
        // an app that byte-compares against Google's embedded certificate
        // correctly concludes this store is not Google's -- which is the honest
        // answer, and one we are content for it to reach. Presenting Google's
        // certificate bytes for our code is the line CLAUDE.md draws and this
        // branch does not go near it.
        //
        // This does NOT touch the reserved-Google DENY set below: that set stops
        // a REGISTRY ROW from resurrecting these names, which is a different
        // mechanism with a different threat model (a sideloaded app naming
        // itself com.android.vending). This answer is code, gated on the layer
        // flag, and it deliberately leaves com.google.android.gms alone -- the
        // store and Play Services are separate packages on a device, and the
        // c2dm/FCM contract that keeps Firebase from looping hangs on the GMS
        // one. See docs/gms-device-oracle-parent-first_2026-08-01.md.
        // The platform package "android" (framework-res.apk). See
        // platformPackageInfo() for the AOSP contract and why it is always here.
        if (PLATFORM_PACKAGE.equals(packageName) && platformPackagePresent()) {
            return platformPackageInfo();
        }
        if (VENDING_PACKAGE.equals(packageName) && PlayServices.vendingPresent()) {
            return vendingPackageInfo();
        }
        // com.google.android.gms, present only when the FCM push backend is live
        // (gated, default OFF). Firebase's Rpc reads versionCode >= 12000000 to
        // pick the modern bound-Messenger register path; a lower value sends it
        // down the legacy broadcast path whose "Rpc: No response" is the hang.
        if (GMS_PACKAGE.equals(packageName) && PlayServices.pushBackendEnabled()) {
            return gmsPackageInfo();
        }
        if (packageName != null && !packageName.equals(this.packageName)) {
            if (packageVisible(packageName)) {
                PackageInfo reg = registryPackageInfo(packageName, flags);
                if (reg != null) return reg;
            }
            if (!"0".equals(System.getenv("MACAPK_PM_SELF_ONLY"))) {
                throw new NameNotFoundException(packageName);
            }
        }
        PackageInfo info = new PackageInfo();
        info.packageName  = this.packageName;
        info.versionName  = this.versionName;
        info.versionCode  = this.versionCode;
        info.longVersionCode = this.versionCode;
        info.applicationInfo = makeAppInfo(flags);
        // v1.0.1586: the installed splits, PackageInfo's half. AOSP
        // PackageInfoWithoutStateUtils.java:206,209-210 (android11-release):
        //   pi.splitNames = pkg.getSplitNames();
        //   pi.baseRevisionCode = pkg.getBaseRevisionCode();
        //   pi.splitRevisionCodes = pkg.getSplitRevisionCodes();
        // -- the same names ApplicationInfo carries (one array in the parsed
        // package), null for a package without splits. See installedSplits().
        {
            SplitSet ss = installedSplits();
            info.baseRevisionCode = cachedBaseRevisionCode;
            if (ss != null) {
                info.splitNames         = ss.names.clone();
                info.splitRevisionCodes = ss.revisionCodes.clone();
            }
        }
        // AOSP PackageParser defaults an undeclared
        // `<manifest android:installLocation>` to INSTALL_LOCATION_UNSPECIFIED,
        // and generatePackageInfo copies the parsed value through. The field's
        // own initializer (INTERNAL_ONLY) is a different default and is not what
        // a normally-installed package reports.
        info.installLocation = installLocationFromManifest();
        // Install timestamps a REREAD cannot move. AOSP fills these from the
        // package database, so five calls one millisecond apart give the same
        // answer; ours came from System.currentTimeMillis() inside the
        // PackageInfo constructor and moved on every call. The APK file's own
        // mtime is the closest honest analogue of "when was this installed" that
        // Mac.apk has, and it is stable across calls AND across runs (which is
        // what an app comparing a cached value against a fresh read needs).
        long installedAt = installTimeMillis();
        // v1.0.1641: AOSP keeps firstInstallTime across an update and moves only
        // lastUpdateTime. The APK's mtime is the UPDATE time once a store has
        // replaced the app in place, so first-install comes from the registry
        // row, whose installedAt the install pipeline carries across the replace.
        long firstAt = installedAt;
        try {
            InstalledRegistry.Row self = InstalledRegistry.enabled()
                ? InstalledRegistry.lookup(packageName) : null;
            if (self != null && self.installedAtMs > 0L && self.installedAtMs < installedAt) {
                firstAt = self.installedAtMs;
            }
        } catch (Throwable ignored) {}
        info.firstInstallTime = firstAt;
        info.lastUpdateTime   = installedAt;
        // SIGNATURES: return the APK's REAL signing certificate(s) -- exactly
        // what a real installed APK reports. This is strictly more AOSP-faithful
        // than the old `null` (which was a lie: every installed package has >=1
        // signature) and it fixes callers that read the signature unconditionally:
        //   * MCPE's `HardwareInformation.getSignaturesHashCode()` does
        //     `signatures.length` -> NPE'd on null and left the device fingerprint
        //     hash empty (blocking the boot state machine that needs it for the
        //     GatheringService config fetch). With the real cert it computes a
        //     stable hash and proceeds. (v1.0.580.)
        //   * GMS GoogleSignatureVerifier hashes signingInfo.getApkContentsSigners
        //     and compares against Google's platform certs -> the real (non-Google)
        //     cert correctly reports "not Google-signed", same graceful degrade as
        //     before, with no NPE.
        //   * Anti-tamper games (Flappy Bird) hash `signatures[0]` against a
        //     BAKED-IN expected hash -> the REAL APK cert MATCHES that baked-in
        //     value (it IS the app's own signing cert), so the check passes
        //     properly instead of being skipped. This is why null was a hazard:
        //     it forced a fragile "no signature = dev build" fallback.
        // Reads the real certificate from the APK, newest signing scheme first
        // (v3.1 / v3 / v2 out of the APK Signing Block, then v1/JAR); falls back
        // to null only when the APK is unreadable or carries no signature at all.
        Signature[] sigs = readApkSignatures();
        info.signatures  = sigs;
        // Use AOSP's 4-arg constructor so getSchemeVersion() reports the scheme we
        // actually verified rather than 0. Upstream degrades the whole object when
        // schemeVersion <= 0, so an unsigned/unreadable APK still yields the same
        // empty SigningInfo the signers-only constructor produced.
        info.signingInfo = (sigs == null || sigs.length == 0)
            ? new android.content.pm.SigningInfo(sigs)
            : new android.content.pm.SigningInfo(
                  cachedSchemeVersion,
                  java.util.Arrays.asList(sigs),
                  null,
                  null);
        // v1.0.834: AOSP PackageInfoUtils.generatePackageInfo fills the component
        // arrays when the caller asks for them. These were left null, so an app
        // enumerating its own components (a settings screen listing its
        // activities, a launcher-alias toggler, an SDK scanning <receiver>
        // metadata) saw "this package declares nothing". Same manifest data the
        // getActivityInfo path now uses, same GET_META_DATA semantics -- AOSP
        // passes the caller's flags straight through to generateActivityInfo.
        Manifest m = manifest();
        if (m != null) {
            if ((flags & GET_ACTIVITIES) != 0 && !m.activities.isEmpty()) {
                info.activities = componentArray(m.activities.values(), flags);
            }
            if ((flags & GET_RECEIVERS) != 0 && !m.receivers.isEmpty()) {
                info.receivers = componentArray(m.receivers.values(), flags);
            }
            if ((flags & GET_SERVICES) != 0 && !services.isEmpty()) {
                java.util.List<ServiceInfo> out = new java.util.ArrayList<>();
                for (Manifest.ServiceEntry e : services.values()) {
                    try {
                        out.add(getServiceInfo(
                            new ComponentName(this.packageName, e.name), flags));
                    } catch (NameNotFoundException ignored) {}
                }
                info.services = out.toArray(new ServiceInfo[0]);
            }
            if ((flags & GET_PROVIDERS) != 0 && !providers.isEmpty()) {
                java.util.List<ProviderInfo> out = new java.util.ArrayList<>();
                for (Manifest.ProviderEntry e : providers.values()) {
                    try {
                        out.add(getProviderInfo(
                            new ComponentName(this.packageName, e.name), flags));
                    } catch (NameNotFoundException ignored) {}
                }
                info.providers = out.toArray(new ProviderInfo[0]);
            }
            // GET_PERMISSIONS. AOSP fills BOTH the permissions the package
            // DEFINES (<permission>) and the ones it REQUESTS
            // (<uses-permission>), plus the parallel grant-flags array. These
            // were null, so an app auditing its own permissions -- the standard
            // "which of my dangerous permissions do I still need to request"
            // startup scan, and every permission-rationale UI -- concluded it
            // had requested nothing and skipped straight past its own gate.
            if ((flags & GET_PERMISSIONS) != 0) {
                if (!m.permissions.isEmpty()) {
                    java.util.List<android.content.pm.PermissionInfo> defined =
                        new java.util.ArrayList<>(m.permissions.size());
                    for (Manifest.PermissionEntry e : m.permissions) {
                        android.content.pm.PermissionInfo pi =
                            new android.content.pm.PermissionInfo();
                        pi.name            = e.name;
                        pi.packageName     = this.packageName;
                        pi.group           = e.group;
                        pi.protectionLevel = e.protectionLevel;
                        pi.flags           = e.flags;
                        defined.add(pi);
                    }
                    info.permissions =
                        defined.toArray(new android.content.pm.PermissionInfo[0]);
                }
                java.util.List<String> requested = requestedPermissions();
                if (!requested.isEmpty()) {
                    info.requestedPermissions = requested.toArray(new String[0]);
                    int[] grants = new int[requested.size()];
                    // Every permission the manifest declares is granted here
                    // (see checkPermission for why Mac.apk uses the install-time
                    // model), so each entry carries REQUESTED_PERMISSION_GRANTED
                    // -- the same bit checkPermission's answer is derived from,
                    // so the two cannot disagree. The exceptions are the same as
                    // checkPermission's: the signature|appop special-access
                    // permissions, never granted to a third-party app (stage S1).
                    // Stage S5: a storage runtime permission carries the bit
                    // only while it is granted -- the same answer
                    // checkPermission gives (SpecialAccessStore's model).
                    for (int i = 0; i < grants.length; i++) {
                        String rp = requested.get(i);
                        Boolean storage = SpecialAccessStore.storagePermissionGranted(rp);
                        boolean held = storage != null ? storage : !macapkNeverGranted(rp);
                        grants[i] = held ? PackageInfo.REQUESTED_PERMISSION_GRANTED : 0;
                    }
                    info.requestedPermissionsFlags = grants;
                }
            }
            // GET_CONFIGURATIONS: the <uses-feature> / <uses-configuration>
            // requirements. AOSP populates reqFeatures and configPreferences.
            if ((flags & GET_CONFIGURATIONS) != 0 && !m.reqFeatures.isEmpty()) {
                java.util.List<android.content.pm.FeatureInfo> feats =
                    new java.util.ArrayList<>(m.reqFeatures.size());
                for (Manifest.FeatureEntry e : m.reqFeatures) {
                    android.content.pm.FeatureInfo fi =
                        new android.content.pm.FeatureInfo();
                    fi.name           = e.name;
                    fi.version        = e.version;
                    fi.reqGlEsVersion = e.reqGlEsVersion;
                    if (e.required) fi.flags |= android.content.pm.FeatureInfo.FLAG_REQUIRED;
                    feats.add(fi);
                }
                info.reqFeatures =
                    feats.toArray(new android.content.pm.FeatureInfo[0]);
            }
        }
        return info;
    }

    /** The `<uses-permission>` names this package declares, in manifest order.
     *  Manifest.usesPermissions has already applied AOSP's min/maxSdkVersion
     *  window (a permission declared `android:maxSdkVersion="28"` is simply not
     *  in the requested set on a newer platform, and apps depend on that -- the
     *  legacy-storage pattern branches on WRITE_EXTERNAL_STORAGE coming back
     *  DENIED afterwards). */
    private java.util.List<String> requestedPermissions() {
        Manifest m = manifest();
        if (m == null || m.usesPermissions.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> out =
            new java.util.ArrayList<>(m.usesPermissions.size());
        for (Manifest.UsesPermissionEntry e : m.usesPermissions) {
            if (e.name != null && !e.name.isEmpty()) out.add(e.name);
        }
        return out;
    }

    /** `<manifest android:installLocation>`, defaulted the way PackageParser
     *  defaults it (PARSE_DEFAULT_INSTALL_LOCATION == INSTALL_LOCATION_UNSPECIFIED). */
    private int installLocationFromManifest() {
        try {
            org.w3c.dom.Element app = applicationElement();
            org.w3c.dom.Document doc = (app != null) ? app.getOwnerDocument() : null;
            if (doc != null) {
                String v = doc.getDocumentElement()
                    .getAttributeNS(ANDROID_NS, "installLocation");
                if (v != null && !v.isEmpty()) {
                    if ("internalOnly".equals(v))     return PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
                    if ("auto".equals(v))             return PackageInfo.INSTALL_LOCATION_AUTO;
                    if ("preferExternal".equals(v))   return PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL;
                    try { return Integer.parseInt(v); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
    }

    /** A STABLE install timestamp: the APK file's last-modified time, which is
     *  fixed for the life of the file and therefore identical across calls and
     *  across runs. Falls back to the app data directory, then to process start,
     *  so the value is never 0 (AOSP's is always > 0 for an installed package,
     *  and callers compute "days since install" from it). */
    private volatile long cachedInstallTime;
    private long installTimeMillis() {
        long t = cachedInstallTime;
        if (t > 0L) return t;
        try {
            if (apkPath != null && !apkPath.isEmpty()) {
                long m = new java.io.File(apkPath).lastModified();
                if (m > 0L) { cachedInstallTime = m; return m; }
            }
            if (dataDir != null && !dataDir.isEmpty()) {
                long m = new java.io.File(dataDir).lastModified();
                if (m > 0L) { cachedInstallTime = m; return m; }
            }
        } catch (Throwable ignored) {}
        // Last resort: fixed at first use, so it still does not move between
        // two calls within a run.
        cachedInstallTime = System.currentTimeMillis();
        return cachedInstallTime;
    }

    private android.content.pm.ActivityInfo[] componentArray(
            java.util.Collection<Manifest.ActivityEntry> entries, int flags) {
        java.util.List<android.content.pm.ActivityInfo> out =
            new java.util.ArrayList<>(entries.size());
        for (Manifest.ActivityEntry e : entries) {
            // AOSP filters getPackageInfo's component arrays with the same gate
            // as the individual getters (PackageInfoUtils.java:283,303). The
            // .services / .providers arrays get this for free because they are
            // built by calling getServiceInfo / getProviderInfo and swallowing
            // NameNotFoundException.
            if (macapkComponentGating()
                    && !macapkIsComponentEnabled(e.name, e.enabled, flags)) continue;
            out.add(toActivityInfo(e, flags));
        }
        return out.toArray(new android.content.pm.ActivityInfo[0]);
    }

    // Lazily read + cache the APK's REAL signing certificate(s). Every installed
    // APK carries at least one, and a real device's PackageManager reports it
    // whichever scheme signed the APK.
    //
    // AOSP ApkSignatureVerifier tries the schemes newest-first (v3.1, v3, v2,
    // then v1/JAR) and stops at the first one present, so we do the same. Reading
    // only v1 (the pre-v1.0.5xx behavior) reported signatures=null for every
    // v2/v3-only APK -- and v2-or-newer-only is normal for anything built with a
    // recent Android Gradle Plugin, which is why real corpus APKs hit it. That
    // null is exactly the lie the real-signature work removed for the v1 case:
    // callers doing `signatures.length` NPE, and self-signature checks take an
    // unintended "tampered / unsigned" branch.
    private Signature[] cachedSignatures;
    private boolean     signaturesLoaded;
    /** APK signature scheme that actually produced cachedSignatures: 3 for v3/v3.1,
     *  2 for v2, 1 for v1/JAR, 0 for none. AOSP's SigningInfo.getSchemeVersion()
     *  reports this, and we were hardcoding 0 by constructing SigningInfo with the
     *  signers-only constructor -- so an app checking the scheme (a tamper check
     *  that requires v2+, or one that refuses v1-only installs) read "unknown" for
     *  a properly v3-signed APK. We already KNEW the answer here and threw it away. */
    private int         cachedSchemeVersion;
    private synchronized Signature[] readApkSignatures() {
        if (signaturesLoaded) return cachedSignatures;
        signaturesLoaded = true;
        if (apkPath == null) return null;
        Signature[] sigs = readSignaturesFromPath(apkPath);
        // A split bundle (.apkm / .xapk / .apks) is a ZIP-of-APKs: the outer file
        // has no APK Signing Block and no META-INF signature of its own, so the read
        // above returns null. The SIGNED artifact is the inner base.apk. Extract it
        // and read ITS certificate -- exactly what a device's PackageManager reports
        // for the installed package (the base split carries the signing identity).
        // This is the running app's OWN real signature, not a forged one. Without it,
        // pairip's com.pairip.SignatureCheck.verifyIntegrity does signatures[0] on a
        // null array and NPEs out of Application.attachBaseContext, which cascades
        // (the obfuscated string pool never initializes) all the way to a black
        // screen. (v1.0.1551.)
        if (sigs == null || sigs.length == 0) {
            // v1.0.1585+: apkPath is the INSTALLED base.apk, which carries the
            // real signing block, so the read above normally succeeds and this
            // fallback is idle. It survives for the legacy state, where apkPath
            // is the synthetic MERGED single-zip (apk_unpacker.cpp flattens a
            // bundle's base.apk + splits and drops the signing block): the
            // ORIGINAL input is published by main.cpp as -Dmacapk.bundle.apk
            // and, when that is a .apkm/.xapk container, its inner base.apk
            // keeps the signature. Try apkPath-as-bundle first (a directly-
            // passed .apkm), then the original input path. A plain apk on
            // either path has no inner base.apk and falls through harmlessly.
            for (String bundle : new String[]{ apkPath, System.getProperty("macapk.bundle.apk") }) {
                if (bundle == null || bundle.isEmpty()) continue;
                String inner = extractInnerBaseApk(bundle);
                if (inner == null) continue;
                Signature[] innerSigs = readSignaturesFromPath(inner);
                if (innerSigs != null && innerSigs.length > 0) { sigs = innerSigs; break; }
            }
        }
        cachedSignatures = sigs;
        return sigs;
    }

    /** v1.0.1639: certificates of ANOTHER package's APK -- the base class's
     *  getPackageArchiveInfo seam, which registryPackageInfo goes through for
     *  every registry-installed package. Same readers as the running app's own
     *  signature (signing block first, then v1; a split bundle's inner
     *  base.apk), but the scheme version the readers record in
     *  cachedSchemeVersion is saved and restored so the running app's own
     *  answer is untouched. Cached per (path, length, mtime): Aurora asks per
     *  package per update check and F-Droid asks for the whole installed set,
     *  and a v1 read or a bundle's inner-apk extraction is not free. */
    private final java.util.Map<String, Object[]> archiveSignatureCache = new java.util.HashMap<>();

    @Override protected synchronized Object[] macapkArchiveSignatures(String path) {
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) return null;
        String key = path + "|" + f.length() + "|" + f.lastModified();
        Object[] hit = archiveSignatureCache.get(key);
        if (hit != null) return hit;
        int ownScheme = cachedSchemeVersion;
        Signature[] sigs;
        int scheme;
        try {
            cachedSchemeVersion = 0;
            sigs = readSignaturesFromPath(path);
            if (sigs == null || sigs.length == 0) {
                String inner = extractInnerBaseApk(path);
                if (inner != null) {
                    sigs = readSignaturesFromPath(inner);
                    new java.io.File(inner).delete();
                }
            }
            scheme = cachedSchemeVersion;
        } finally {
            cachedSchemeVersion = ownScheme;
        }
        Object[] out = new Object[]{ sigs, scheme };
        archiveSignatureCache.put(key, out);
        return out;
    }

    /** Signing-block (v2/v3) first, then v1 (JAR / PKCS#7), reading from {@code path}. */
    private Signature[] readSignaturesFromPath(String path) {
        Signature[] sigs = readSigningBlockSignatures(path);
        if (sigs == null) {
            sigs = readV1Signatures(path);
            if (sigs != null && sigs.length > 0) cachedSchemeVersion = 1;
        }
        return sigs;
    }

    /** If {@code bundlePath} is a split bundle (a ZIP whose entries are .apk files,
     *  as APKMirror .apkm / .xapk / Play .apks are), extract the inner base.apk to a
     *  temp file and return its path; otherwise null. The signing block needs random
     *  access, so the inner apk is materialized rather than streamed. */
    private String extractInnerBaseApk(String bundlePath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(bundlePath)) {
            java.util.zip.ZipEntry base = zip.getEntry("base.apk");
            if (base == null) {
                // Some bundles nest the base split under a directory or name it
                // <pkg>.apk. Take the first *.apk entry that is not a config/lang
                // split (those carry no code-signing identity distinct from base).
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    String leaf = n.substring(n.lastIndexOf('/') + 1);
                    if (leaf.endsWith(".apk")
                            && !leaf.startsWith("config.")
                            && !leaf.startsWith("split_")
                            && !leaf.contains("install_pack")) {
                        base = e; break;
                    }
                }
            }
            if (base == null) return null;   // not a bundle (a plain APK has no inner apk)
            java.io.File tmp = java.io.File.createTempFile("macapk-baseapk", ".apk");
            tmp.deleteOnExit();
            byte[] buf = new byte[65536];
            try (java.io.InputStream is = zip.getInputStream(base);
                 java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            NativeLog.i("Mac.apk.PM", "extracted inner " + base.getName()
                + " from split bundle for signature read -> " + tmp.getPath());
            return tmp.getPath();
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.PM", "extractInnerBaseApk(" + bundlePath + ") failed (" + t + ")");
            return null;
        }
    }

    // v1 (JAR) signing. Only reached when the APK carries no APK Signing Block,
    // i.e. a genuinely v1-only APK.
    //
    // Two steps, because JarFile alone is not enough. JarFile's verifier applies
    // the JDK's `jdk.jar.disabledAlgorithms` policy, and JDK 21 denies SHA-1
    // signed JARs (SHA1 denyAfter 2019-01-01); when it denies one it does not
    // throw, it silently treats the entry as UNSIGNED and getCertificates()
    // returns null. Practically every pre-2019 APK is SHA1withRSA, so JarFile on
    // its own reports "no signature" for exactly the old apps whose baked-in
    // anti-tamper comparisons the real certificate exists to satisfy. A device's
    // PackageManager has no such policy: it reports the certificate the package
    // was signed with, whatever the digest's age. So when JarFile declines we
    // read the certificate straight out of the PKCS#7 signature block, which is
    // the same DER the verifier would have parsed.
    private Signature[] readV1Signatures(String path) {
        Signature[] sigs = readV1SignaturesVerified(path);
        if (sigs == null) sigs = readV1SignaturesFromPkcs7(path);
        return sigs;
    }

    private Signature[] readV1SignaturesVerified(String path) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path, true)) {
            // Prefer a small, always-v1-signed entry (AndroidManifest.xml) to
            // minimize I/O; fall back to the first non-META-INF file entry.
            java.util.jar.JarEntry chosen = jar.getJarEntry("AndroidManifest.xml");
            if (chosen == null) {
                java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    java.util.jar.JarEntry je = en.nextElement();
                    if (!je.isDirectory() && !je.getName().startsWith("META-INF/")) {
                        chosen = je; break;
                    }
                }
            }
            if (chosen == null) return null;
            byte[] buf = new byte[16384];
            try (java.io.InputStream is = jar.getInputStream(chosen)) {
                while (is.read(buf) != -1) { /* read fully so certs load */ }
            }
            java.security.cert.Certificate[] certs = chosen.getCertificates();
            if (certs == null || certs.length == 0) return null;
            Signature[] sigs = new Signature[certs.length];
            for (int i = 0; i < certs.length; i++)
                sigs[i] = new Signature(certs[i].getEncoded());
            NativeLog.i("Mac.apk.PM", "read " + sigs.length + " real APK signature(s) (v1/JAR) from " + path);
            return sigs;
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.PM", "readV1SignaturesVerified failed (" + t + ")");
            return null;
        }
    }

    /** The signer certificate(s) of a v1-signed APK, read from the PKCS#7
     *  signature block(s) under META-INF (one .RSA / .DSA / .EC entry per
     *  signer). CertificateFactory.generateCertificates parses a PKCS#7
     *  SignedData container directly, which is what those entries are.
     *  One Signature per SIGNER carrying that signer's end-entity certificate,
     *  matching AOSP ApkSignatureVerifier.convertToSignatures. */
    private Signature[] readV1SignaturesFromPkcs7(String path) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path)) {
            java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.util.ArrayList<Signature> out = new java.util.ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith("META-INF/") || e.isDirectory()) continue;
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (!upper.endsWith(".RSA") && !upper.endsWith(".DSA")
                        && !upper.endsWith(".EC")) continue;
                java.util.Collection<? extends java.security.cert.Certificate> certs;
                try (java.io.InputStream is = zip.getInputStream(e)) {
                    certs = cf.generateCertificates(is);
                } catch (Throwable t) {
                    continue;   // a malformed block is not a reason to lose the others
                }
                java.security.cert.X509Certificate leaf = endEntityCertificate(certs);
                if (leaf != null) out.add(new Signature(leaf.getEncoded()));
            }
            if (out.isEmpty()) {
                NativeLog.i("Mac.apk.PM", "APK carries no signature in any scheme; signatures=null");
                return null;
            }
            NativeLog.i("Mac.apk.PM", "read " + out.size()
                + " real APK signature(s) (v1/PKCS#7) from " + path);
            return out.toArray(new Signature[0]);
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.PM", "readV1SignaturesFromPkcs7 failed (" + t + "); signatures=null");
            return null;
        }
    }

    /** The end-entity (signing) certificate of a PKCS#7 chain: the one no other
     *  certificate in the set was issued by. Android APKs are overwhelmingly
     *  signed with a single self-signed certificate, where this is the only
     *  candidate; the walk exists so a chained block still yields the leaf
     *  rather than a CA. */
    private static java.security.cert.X509Certificate endEntityCertificate(
            java.util.Collection<? extends java.security.cert.Certificate> certs) {
        java.util.ArrayList<java.security.cert.X509Certificate> xs =
            new java.util.ArrayList<>();
        for (java.security.cert.Certificate c : certs) {
            if (c instanceof java.security.cert.X509Certificate) {
                xs.add((java.security.cert.X509Certificate) c);
            }
        }
        if (xs.isEmpty()) return null;
        if (xs.size() == 1) return xs.get(0);
        for (java.security.cert.X509Certificate c : xs) {
            boolean issuedAnother = false;
            for (java.security.cert.X509Certificate other : xs) {
                if (other == c) continue;
                if (other.getIssuerX500Principal().equals(c.getSubjectX500Principal())) {
                    issuedAnother = true;
                    break;
                }
            }
            if (!issuedAnother) return c;
        }
        return xs.get(0);
    }

    // ---- APK Signature Scheme v2 / v3 ------------------------------------
    // Block ids, from AOSP frameworks/base core/java/android/util/apk/
    // ApkSignatureSchemeV2Verifier.java:75 and ApkSignatureSchemeV3Verifier.java
    // :73-74. Tried newest-first, matching ApkSignatureVerifier's ordering.
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID  = 0x7109871a;
    private static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID  = 0xf05368c0;
    private static final int APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;
    // "APK Sig Block 42", as the two little-endian longs AOSP compares against
    // (ApkSigningBlockUtils.java:694-695).
    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L;
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L;

    /** Read the signing certificate(s) out of the APK Signing Block, the
     *  length-prefixed id-value region that sits immediately before the ZIP
     *  central directory (https://source.android.com/docs/security/features/
     *  apksigning/v2). Returns null when the APK has no signing block or the
     *  block does not parse, so the caller can fall back to v1.
     *
     *  WHAT THIS DOES NOT DO: it does not verify the APK's content digests, and
     *  it does not verify the signature over signed-data. That is APK INTEGRITY
     *  verification, which Mac.apk performs nowhere (it maps the DEX and the
     *  native libraries unconditionally), so doing a fraction of it here would
     *  buy no integrity guarantee while adding a way to fail closed on a
     *  perfectly good APK. What this method owes its callers is the same thing
     *  the v1 path owes them: the certificate the package was actually signed
     *  with. It does keep AOSP's cheap structural check (the leaf certificate's
     *  public key must equal the signer's own public key record), because a
     *  mismatch there means the block did not parse the way we think it did. */
    private Signature[] readSigningBlockSignatures(String path) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r")) {
            long cdOffset = findCentralDirOffset(raf);
            if (cdOffset < 0) return null;
            java.nio.ByteBuffer signingBlock = findApkSigningBlock(raf, cdOffset);
            if (signingBlock == null) return null;
            int[] order = { APK_SIGNATURE_SCHEME_V31_BLOCK_ID,
                            APK_SIGNATURE_SCHEME_V3_BLOCK_ID,
                            APK_SIGNATURE_SCHEME_V2_BLOCK_ID };
            for (int blockId : order) {
                java.nio.ByteBuffer scheme = findSchemeBlock(signingBlock, blockId);
                if (scheme == null) continue;
                boolean v3 = blockId != APK_SIGNATURE_SCHEME_V2_BLOCK_ID;
                Signature[] sigs = parseSigners(scheme, v3);
                if (sigs != null && sigs.length > 0) {
                    // v3.1 and v3 both report scheme 3: AOSP's SigningDetails has no
                    // separate v3.1 constant, and v3.1 is a rotation-targeting variant
                    // of the v3 block, not a distinct scheme version.
                    cachedSchemeVersion = v3 ? 3 : 2;
                    NativeLog.i("Mac.apk.PM", "read " + sigs.length
                        + " real APK signature(s) (scheme block 0x"
                        + Integer.toHexString(blockId) + ", schemeVersion="
                        + cachedSchemeVersion + ") from " + path);
                    return sigs;
                }
            }
            return null;
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.PM", "readSigningBlockSignatures failed (" + t + ")");
            return null;
        }
    }

    /** Offset of the ZIP central directory, read out of the End Of Central
     *  Directory record (scanned backwards from EOF, since the record is
     *  followed only by a comment of up to 64 KB). -1 when there is no usable
     *  EOCD, including the ZIP64 case where the 32-bit field is saturated:
     *  a >4 GB APK is not something we can locate the signing block in with
     *  this record alone, and v1 remains available. */
    private static long findCentralDirOffset(java.io.RandomAccessFile raf)
            throws java.io.IOException {
        final int EOCD_LEN = 22;
        long fileLen = raf.length();
        if (fileLen < EOCD_LEN) return -1;
        int scan = (int) Math.min(fileLen, 0xffffL + EOCD_LEN);
        byte[] buf = new byte[scan];
        raf.seek(fileLen - scan);
        raf.readFully(buf);
        for (int i = scan - EOCD_LEN; i >= 0; i--) {
            if (buf[i] != 0x50 || buf[i + 1] != 0x4b
                    || buf[i + 2] != 0x05 || buf[i + 3] != 0x06) continue;
            int commentLen = u16(buf, i + 20);
            // The comment must run exactly to EOF, which is what disambiguates a
            // real EOCD from the same four bytes appearing inside compressed data.
            if (i + EOCD_LEN + commentLen != scan) continue;
            long cdOffset = u32(buf, i + 16);
            if (cdOffset == 0xffffffffL) return -1;   // ZIP64
            return (cdOffset <= fileLen) ? cdOffset : -1;
        }
        return -1;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
             | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    /** Mirrors AOSP ApkSigningBlockUtils.findApkSigningBlock. Layout, ending at
     *  the central directory offset:
     *      @+0  uint64  size in bytes (excluding this field)
     *      @+8          payload (id-value pairs)
     *      @-24 uint64  the same size again
     *      @-16         the 16-byte magic
     *  Returns the whole block (size field included, so pair walking starts at
     *  offset 8 exactly as upstream), or null when it is absent/inconsistent. */
    private static java.nio.ByteBuffer findApkSigningBlock(
            java.io.RandomAccessFile raf, long cdOffset) throws java.io.IOException {
        final int MIN_SIZE = 32;
        if (cdOffset < MIN_SIZE) return null;
        byte[] footer = new byte[24];
        raf.seek(cdOffset - footer.length);
        raf.readFully(footer);
        java.nio.ByteBuffer fb = java.nio.ByteBuffer.wrap(footer)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (fb.getLong(8) != APK_SIG_BLOCK_MAGIC_LO
                || fb.getLong(16) != APK_SIG_BLOCK_MAGIC_HI) {
            return null;
        }
        long sizeInFooter = fb.getLong(0);
        if (sizeInFooter < footer.length || sizeInFooter > Integer.MAX_VALUE - 8) return null;
        int total = (int) (sizeInFooter + 8);
        long blockOffset = cdOffset - total;
        if (blockOffset < 0) return null;
        byte[] raw = new byte[total];
        raf.seek(blockOffset);
        raf.readFully(raw);
        java.nio.ByteBuffer block = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // The two size fields must agree, exactly as upstream requires.
        if (block.getLong(0) != sizeInFooter) return null;
        return block;
    }

    /** Mirrors AOSP ApkSigningBlockUtils.findApkSignatureSchemeBlock: walk the
     *  uint64-length-prefixed id-value pairs between the leading size field and
     *  the trailing size+magic, and return the value of the pair with this id. */
    private static java.nio.ByteBuffer findSchemeBlock(
            java.nio.ByteBuffer signingBlock, int blockId) {
        java.nio.ByteBuffer pairs =
            sliceFromTo(signingBlock, 8, signingBlock.capacity() - 24);
        if (pairs == null) return null;
        while (pairs.remaining() >= 8) {
            long lenLong = pairs.getLong();
            if (lenLong < 4 || lenLong > Integer.MAX_VALUE) return null;
            int len = (int) lenLong;
            if (len > pairs.remaining()) return null;
            int nextEntryPos = pairs.position() + len;
            int id = pairs.getInt();
            if (id == blockId) return sliceFromTo(pairs, pairs.position(), nextEntryPos);
            pairs.position(nextEntryPos);
        }
        return null;
    }

    /** Extract one Signature per signer from a v2 or v3 scheme block.
     *
     *  Layouts (all length prefixes are uint32 little-endian), from AOSP
     *  ApkSignatureSchemeV2Verifier.verifySigner and
     *  ApkSignatureSchemeV3Verifier.verifySigner:
     *      block  = len-prefixed sequence of len-prefixed signers
     *      signer = [signed data] (v3 only: [minSdk][maxSdk]) [signatures] [public key]
     *      signed data = [digests] [certificates] (v3 only: [minSdk][maxSdk]) [attributes]
     *      certificates = sequence of len-prefixed X.509 DER certificates
     *  The signing certificate is the FIRST certificate of each signer, and the
     *  Signature carries its verbatim DER bytes -- that is what AOSP's
     *  VerbatimX509Certificate + convertToSignatures produce.
     *
     *  v3 signers are scoped to a platform range; AOSP skips a signer whose
     *  [minSdk, maxSdk] does not contain the running SDK_INT, so we do too. If
     *  that leaves no signer, the caller falls through to the next scheme. */
    private static Signature[] parseSigners(java.nio.ByteBuffer schemeBlock, boolean v3)
            throws java.security.cert.CertificateException {
        java.nio.ByteBuffer signers = lenPrefixedSlice(schemeBlock);
        if (signers == null) return null;
        java.security.cert.CertificateFactory certFactory =
            java.security.cert.CertificateFactory.getInstance("X.509");
        java.util.ArrayList<Signature> out = new java.util.ArrayList<>();
        while (signers.remaining() >= 4) {
            java.nio.ByteBuffer signer = lenPrefixedSlice(signers);
            if (signer == null) return null;
            java.nio.ByteBuffer signedData = lenPrefixedSlice(signer);
            if (signedData == null) return null;
            if (v3) {
                if (signer.remaining() < 8) return null;
                int minSdk = signer.getInt();
                int maxSdk = signer.getInt();
                if (android.os.Build.VERSION.SDK_INT < minSdk
                        || android.os.Build.VERSION.SDK_INT > maxSdk) {
                    continue;   // this signer targets a different platform version
                }
            }
            if (lenPrefixedSlice(signer) == null) return null;      // signatures
            byte[] publicKeyBytes = lenPrefixedBytes(signer);       // public key
            if (lenPrefixedSlice(signedData) == null) return null;  // digests
            java.nio.ByteBuffer certificates = lenPrefixedSlice(signedData);
            if (certificates == null) return null;
            byte[] leaf = lenPrefixedBytes(certificates);
            if (leaf == null) return null;
            java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(leaf));
            // AOSP: "Public key mismatch between certificate and signature record".
            if (publicKeyBytes == null
                    || !java.util.Arrays.equals(publicKeyBytes,
                                                cert.getPublicKey().getEncoded())) {
                return null;
            }
            out.add(new Signature(leaf));
        }
        return out.isEmpty() ? null : out.toArray(new Signature[0]);
    }

    /** Consume a uint32 length prefix and return that many bytes as an
     *  independent little-endian view, advancing the source past them. */
    private static java.nio.ByteBuffer lenPrefixedSlice(java.nio.ByteBuffer src) {
        if (src == null || src.remaining() < 4) return null;
        int len = src.getInt();
        if (len < 0 || len > src.remaining()) return null;
        int start = src.position();
        int end   = start + len;
        java.nio.ByteBuffer out = sliceFromTo(src, start, end);
        if (out == null) return null;
        src.position(end);
        return out;
    }

    private static byte[] lenPrefixedBytes(java.nio.ByteBuffer src) {
        if (src == null || src.remaining() < 4) return null;
        int len = src.getInt();
        if (len < 0 || len > src.remaining()) return null;
        byte[] out = new byte[len];
        src.get(out);
        return out;
    }

    /** Mirrors AOSP ApkSigningBlockUtils.sliceFromTo: an independent buffer over
     *  [start, end) of the source, leaving the source's position and limit
     *  untouched. The position/limit assignment order matters (limit first),
     *  because start may be beyond the current limit. */
    private static java.nio.ByteBuffer sliceFromTo(java.nio.ByteBuffer source,
                                                   int start, int end) {
        if (start < 0 || end < start || end > source.capacity()) return null;
        int originalLimit = source.limit();
        int originalPosition = source.position();
        try {
            source.position(0);
            source.limit(end);
            source.position(start);
            java.nio.ByteBuffer result = source.slice();
            result.order(source.order());
            return result;
        } finally {
            source.position(0);
            source.limit(originalLimit);
            source.position(originalPosition);
        }
    }

    @Override public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        // Same AOSP contract as getPackageInfo: a non-installed (non-self) package
        // throws NameNotFoundException. A NULL name is not our package either --
        // it names nothing, so it cannot be installed, and AOSP throws rather
        // than treating it as "the caller means themselves". We used to fall
        // through the `packageName != null` guard and hand back our own
        // ApplicationInfo, which turned an app's bug (an unresolved package
        // name) into a silently wrong answer about a different app.
        // Env rollback MACAPK_PM_SELF_ONLY=0.
        if (PLATFORM_PACKAGE.equals(packageName) && platformPackagePresent()) {
            return platformPackageInfo().applicationInfo;
        }
        if (VENDING_PACKAGE.equals(packageName) && PlayServices.vendingPresent()) {
            return vendingPackageInfo().applicationInfo;
        }
        if (GMS_PACKAGE.equals(packageName) && PlayServices.pushBackendEnabled()) {
            return gmsPackageInfo().applicationInfo;
        }
        if (packageName == null || !packageName.equals(this.packageName)) {
            // MACAPK_PM_REGISTRY (default ON): another Mac.apk-installed app IS
            // installed on this machine, so report it -- if the caller can see
            // it (packageVisible). See InstalledRegistry.
            if (packageName != null && packageVisible(packageName)) {
                PackageInfo reg = registryPackageInfo(packageName, flags);
                if (reg != null && reg.applicationInfo != null) return reg.applicationInfo;
            }
            if (!"0".equals(System.getenv("MACAPK_PM_SELF_ONLY"))) {
                throw new NameNotFoundException(String.valueOf(packageName));
            }
        }
        return makeAppInfo(flags);
    }

    // ---- The installed-apps registry (G9, v1.0.953) --------------------------
    //
    // WHAT: Mac.apk installs every APK as its own macOS .app bundle and records
    // it in ~/Library/Application Support/Mac.apk/installed_apps.json (written
    // ONLY by the Swift side: RegistryStore in launcher/InstallCore.swift,
    // schema 2, atomic tmp+fsync+rename -- so a reader always sees a complete
    // old or complete new file and needs no lock). Those packages ARE installed
    // on this machine, and a PackageManager that keeps answering
    // NameNotFoundException for them is lying in the other direction from the
    // old always-self lie. F-Droid's post-install "Install -> Open" button flip
    // is driven by exactly this: its LocalBroadcastManager INSTALL_COMPLETE
    // handler re-queries getPackageInfo(pkg) and flips only when that stops
    // throwing (measured from its 1.23.2 bytecode; the system
    // ACTION_PACKAGE_ADDED broadcast is NOT what flips it).
    //
    // GATE: default ON as of 2026-08-17 (was default-OFF behind MACAPK_PM_REGISTRY
    // from the v1.0.953/954 landing until a visibility filter existed to make
    // "ON" safe; see the packageVisible() header above). MACAPK_PM_REGISTRY=0
    // is the explicit opt-out for A/B and for debug/pm_registry_test.sh's
    // gate-off section. Registry answers change what EVERY app sees from
    // getPackageInfo/getApplicationInfo/getInstalledPackages, narrowed by
    // packageVisible so a non-store app targeting API 30+ still only sees
    // itself (and whatever it explicitly <queries>) -- exactly the AOSP
    // contract, not "everyone sees every app". This is proven by the
    // protected sweep (PASS=11 REGRESS=0 unaffected -- fdroid is Canvas-tier,
    // not swept) plus debug/pm_registry_test.sh.
    //
    // ⛔ THE RESERVED-GOOGLE DENY-SET. A registry row must NEVER be able to make
    // com.google.android.gms / com.android.vending / com.google.android.gsf
    // look installed: the whole GMS-absent device contract (v1.0.847, memory
    // gms-absent-device-contract) hangs on those names throwing, and a
    // malicious or mislabeled sideload writing a row with a reserved name would
    // otherwise flip every Firebase/GMS presence probe in one stroke. The deny
    // test is on the QUERIED name, ahead of any file read, so no registry
    // content can ever reach the caller for those names. Proven by
    // debug/pm_registry_test.sh's poisoned-registry case.
    //
    // MACAPK_PM_REGISTRY_FILE points the reader at an alternate registry file.
    // It exists for that regression test (a poisoned registry must never be
    // written into the user's real library) and changes only what PM REPORTS --
    // installing anything still requires the macapk-installhelper auth path, so
    // this seam has no authorization relevance.
    static final class InstalledRegistry {
        /** One registry row we care about. Mirror of the Swift AppRecord
         *  (launcher/InstallCore.swift), thinned to what PM answers need. */
        static final class Row {
            final String pkg, label, versionName, apkPath, bundlePath;
            final long versionCode, installedAtMs, updatedAtMs;
            /** The bundle's MacapkDirName (`<pkg>_<versionName>`), or null for a
             *  row that predates it. The cross-app sender checks it against the
             *  bundle's own Info.plist before opening anything (v1.0.1652). */
            final String dirName;
            Row(String pkg, String label, String versionName, long versionCode,
                String apkPath, String bundlePath, long installedAtMs, long updatedAtMs) {
                this(pkg, label, versionName, versionCode, apkPath, bundlePath,
                     installedAtMs, updatedAtMs, null);
            }
            Row(String pkg, String label, String versionName, long versionCode,
                String apkPath, String bundlePath, long installedAtMs, long updatedAtMs,
                String dirName) {
                this.pkg = pkg; this.label = label; this.versionName = versionName;
                this.versionCode = versionCode; this.apkPath = apkPath;
                this.bundlePath = bundlePath; this.installedAtMs = installedAtMs;
                this.updatedAtMs = updatedAtMs; this.dirName = dirName;
            }
        }

        private static final java.util.Set<String> DENY = java.util.Set.of(
            "com.google.android.gms",   // Play Services
            "com.android.vending",      // Play Store
            "com.google.android.gsf");  // Google Services Framework

        static boolean denied(String pkg) { return pkg != null && DENY.contains(pkg); }

        // ⚠ DEFAULT FLIPPED TO ON 2026-08-17 (was default-OFF at v1.0.954 landing,
        // pending the visibility filter below + a sweep -- both now in). Owner
        // hand-test: F-Droid never flips Install->Open/Installed and never offers
        // updates for anything Mac.apk installed, because this gate was dark by
        // default and nothing ever set it. Same `!"0".equals(...)` idiom as
        // MACAPK_PM_SELF_ONLY / MACAPK_FOLLOW_SYSTEM_APPEARANCE elsewhere in this
        // tree: unset or any non-"0" value is ON, "0" is the explicit opt-out for
        // A/B and for debug/pm_registry_test.sh's gate-off section.
        static boolean enabled() {
            return !"0".equals(System.getenv("MACAPK_PM_REGISTRY"));
        }

        private static java.io.File file() {
            String override = System.getenv("MACAPK_PM_REGISTRY_FILE");
            if (override != null && !override.isEmpty()) {
                return new java.io.File(override);
            }
            return new java.io.File(System.getProperty("user.home"),
                "Library/Application Support/Mac.apk/installed_apps.json");
        }

        /** One parse of the file: the installed rows, plus the packages the
         *  file KNOWS about but does not report as installed. The second set
         *  is what lets a removal be classified without this reader having to
         *  re-derive the Swift side's bundle-presence test (see
         *  `announceRemovals`). */
        static final class Snapshot {
            final java.util.List<Row> installed;
            /** Packages carrying a row whose state is `removed` -- Mac.apk's
             *  "the app's macOS bundle, and therefore its data, is gone". */
            final java.util.Set<String> removed;
            Snapshot(java.util.List<Row> installed, java.util.Set<String> removed) {
                this.installed = installed; this.removed = removed;
            }
        }

        /** (path, mtime, size) -> parsed snapshot. Stat-per-query is a few
         *  microseconds; re-reading on change is what makes a fresh install
         *  visible to the very next getPackageInfo call, which is the
         *  button-flip requirement. */
        private static volatile Object[] cache;   // {String path, long mtime, long size, Snapshot}

        static java.util.List<Row> rows() { return snapshot().installed; }

        // ---- bundle-deletion detection (v1.0.1004) ------------------------
        //
        // THE GAP THIS CLOSES. announceRemovals below fires when the REGISTRY
        // FILE changes, and the Swift side owns the liveness predicate that
        // rewrites it. That is the right split and it is not being changed
        // here. But nothing re-ran that predicate while a guest was live, so a
        // bundle the user dragged to the Trash in Finder left the file
        // untouched -- and every store kept being told the app was installed.
        // Measured 2026-08-20: the owner deleted /Applications/TikTok.app by
        // hand and Aurora went on showing it installed indefinitely.
        //
        // WHY THIS IS NOT A SECOND LIVENESS PREDICATE. We do not decide whether
        // a bundle is alive -- we would be the third copy of a test that has
        // already caused this exact class of bug. We only notice that the
        // BUNDLES DIRECTORY changed and ask the helper to re-run ITS reconcile
        // (`macapk-installhelper reconcile`), which is the same code path the
        // control panel uses. The verdict, and the write, stay where they were.
        //
        // WHY A DIRECTORY mtime. Removing (or adding) an entry updates the
        // containing directory's mtime, so one stat answers "could anything
        // have appeared or vanished" without walking bundles or stating each
        // app. It is deliberately coarse: it costs a stat per bundle folder
        // per PM query (/Applications and ~/Applications, where a user who
        // cannot write /Applications gets their apps, GitHub #28) and
        // over-fires (any change to either triggers it), which is the
        // right way round -- an extra reconcile is cheap and idempotent, a
        // missed one leaves a store lying about installed apps.
        //
        // On a real device this state is unreachable at all: the only way to
        // remove a package is THROUGH the package manager, which always
        // broadcasts. We deliberately opened a second removal route (the
        // owner-approved "delete the bundle in Finder" path), so we owe it the
        // notification that route does not carry by itself.
        private static final java.util.concurrent.atomic.AtomicLong
                bundlesDirMtime = new java.util.concurrent.atomic.AtomicLong(-1);
        private static final java.util.concurrent.atomic.AtomicBoolean
                reconcileInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
        private static volatile long lastReconcileNanos = 0L;

        /** Ask the helper to re-run its reconcile if a bundle folder changed.
         *  Never blocks the caller: a PM query must not wait on a subprocess.
         *  The rewritten registry is picked up by the NEXT snapshot() -- the
         *  same poll-driven honesty announceRemovals already documents. */
        private static void maybeReconcileBundles() {
            // One number for both folders: a change to either moves it. A
            // missing ~/Applications reads 0 and simply never moves.
            long m;
            try {
                String[] roots = CrossAppLauncher.bundleRoots();
                m = new java.io.File(roots[0]).lastModified();
                if (m == 0L) return;
                for (int i = 1; i < roots.length; i++) {
                    m = m * 31 + new java.io.File(roots[i]).lastModified();
                }
            } catch (Throwable t) { return; }
            long prev = bundlesDirMtime.getAndSet(m);
            if (prev < 0) return;          // first observation: baseline only
            if (prev == m) return;         // nothing appeared or vanished
            // Rate-limit: a burst of directory churn must not spawn a helper
            // per query. 3s is far below any human's install/delete cadence
            // and far above the cost of one reconcile.
            long now = System.nanoTime();
            if (now - lastReconcileNanos < 3_000_000_000L) return;
            if (!reconcileInFlight.compareAndSet(false, true)) return;
            lastReconcileNanos = now;
            Thread t = new Thread(() -> {
                try {
                    java.io.File helper = PackageInstallerBackend.locateHelper();
                    if (helper == null) return;
                    Process p = new ProcessBuilder(helper.getAbsolutePath(), "reconcile")
                            .redirectErrorStream(true).start();
                    p.getInputStream().close();
                    p.waitFor();
                    logOnce("[PM.registry] a bundle folder changed; ran helper reconcile"
                            + " (a bundle may have been added or removed outside the"
                            + " install path)");
                } catch (Throwable ignored) {
                    // Best effort. A failed reconcile leaves the previous
                    // answer standing, which is the pre-existing behaviour.
                } finally {
                    reconcileInFlight.set(false);
                }
            }, "macapk-bundle-reconcile");
            t.setDaemon(true);
            t.start();
        }

        static Snapshot snapshot() {
            if (!enabled()) {
                return new Snapshot(java.util.Collections.emptyList(),
                                    java.util.Collections.emptySet());
            }
            maybeReconcileBundles();
            java.io.File f = file();
            long mtime = f.lastModified(), size = f.length();
            Object[] c = cache;
            if (c != null && c[0].equals(f.getPath())
                    && (Long) c[1] == mtime && (Long) c[2] == size) {
                return (Snapshot) c[3];
            }
            Snapshot parsed = parse(f);
            Object[] prev = c;
            cache = new Object[] { f.getPath(), mtime, size, parsed };
            // Only a CHANGE of an already-observed file is a removal. A first
            // parse (prev == null) or a switch to a different registry file
            // establishes a baseline and announces nothing -- otherwise every
            // process start would broadcast the removal of everything it has
            // never heard of.
            if (prev != null && prev[0].equals(f.getPath())) {
                announceRemovals((Snapshot) prev[3], parsed);
            }
            return parsed;
        }

        /** Tell the hosted app about packages that STOPPED being installed
         *  while it was running.
         *
         *  WHY THIS READER AND NOT A FILESYSTEM CHECK. The Swift side owns the
         *  one liveness predicate (BundleLiveness.presence in
         *  launcher/InstallCore.swift) and writes its verdict into each row's
         *  `state`. This side consumes that verdict; it does not re-derive it.
         *  Two readers with their own copies of the test is exactly the bug
         *  being fixed -- before v1.0.998 there were two such copies on the
         *  Swift side alone, and neither of them changed `state`, so this file
         *  kept telling stores that a trashed app was installed.
         *
         *  POLL-DRIVEN, and honestly so: this runs when the guest next asks
         *  PackageManager something. Both measured stores poll (Aurora from
         *  inside its own status receiver, F-Droid constantly), so in practice
         *  it fires promptly -- but a guest that asks nothing is told nothing,
         *  and there is no timer here to pretend otherwise. */
        private static void announceRemovals(Snapshot before, Snapshot after) {
            if (before.installed.isEmpty()) return;
            java.util.Set<String> now = new java.util.HashSet<>();
            for (Row r : after.installed) now.add(r.pkg);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Row r : before.installed) {
                if (r.pkg == null || denied(r.pkg)) continue;   // never claimed installed
                if (now.contains(r.pkg) || !seen.add(r.pkg)) continue;
                // A guest-initiated uninstall (PackageInstallerBackend
                // .performUninstall) announces its own removal, with the
                // helper's dataRemoved EVIDENCE and in AOSP's broadcast-
                // before-status order -- and it advances this snapshot while
                // its in-flight flag is up. Announcing here too would deliver
                // the same PACKAGE_REMOVED twice.
                if (PackageInstallerBackend.isUninstallInFlight(r.pkg)) continue;
                // `removed` means the bundle went, and in the per-app bundle
                // model (v1.0.949) the app's data lives INSIDE that bundle --
                // so code and data both went, which is what AOSP's
                // EXTRA_DATA_REMOVED asserts. A row that vanished from the
                // file entirely came from an explicit uninstall, which may
                // have parked the data; claim less there.
                boolean dataRemoved = after.removed.contains(r.pkg);
                try { PackageInstallerBackend.broadcastPackageRemoved(r.pkg, dataRemoved); }
                catch (Throwable t) { logOnce("[PM.registry] removal broadcast failed: " + t); }
            }
        }

        /** Best installed row for a package, or null. Never answers for a
         *  denied name, never answers for a row whose state is not
         *  "installed" (an `installing` journal row or a `broken` one is not
         *  an installed package). Best = highest versionCode, ties broken by
         *  most recent updatedAt/installedAt -- the same rule as the Swift
         *  Registry.currentRecord, because the two sides must name the same
         *  version. */
        static Row lookup(String pkg) {
            if (pkg == null || pkg.isEmpty() || denied(pkg)) return null;
            Row best = null;
            for (Row r : rows()) {
                if (!pkg.equals(r.pkg)) continue;
                if (best == null
                        || r.versionCode > best.versionCode
                        || (r.versionCode == best.versionCode
                            && Math.max(r.updatedAtMs, r.installedAtMs)
                               > Math.max(best.updatedAtMs, best.installedAtMs))) {
                    best = r;
                }
            }
            return best;
        }

        /** Best row per package, denied names excluded. */
        static java.util.List<Row> allBest() {
            java.util.LinkedHashMap<String, Row> best = new java.util.LinkedHashMap<>();
            for (Row r : rows()) {
                if (denied(r.pkg)) continue;
                Row cur = best.get(r.pkg);
                if (cur == null
                        || r.versionCode > cur.versionCode
                        || (r.versionCode == cur.versionCode
                            && Math.max(r.updatedAtMs, r.installedAtMs)
                               > Math.max(cur.updatedAtMs, cur.installedAtMs))) {
                    best.put(r.pkg, r);
                }
            }
            return new java.util.ArrayList<>(best.values());
        }

        /** Tolerant parse. Failure of any shape degrades to "no rows" -- a
         *  corrupt registry must never take PackageManager down with it. The
         *  v2 shape is {schema, writerVersion, settings, apps:[...]}; a bare
         *  v1 array (pre-migration) is also read so a machine the launcher has
         *  not migrated yet still answers. */
        private static Snapshot parse(java.io.File f) {
            java.util.Set<String> removed = new java.util.HashSet<>();
            try {
                if (!f.isFile()) return empty(removed);
                byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
                String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (text.isEmpty()) return empty(removed);
                org.json.JSONArray apps;
                if (text.startsWith("[")) {
                    apps = new org.json.JSONArray(text);
                } else {
                    apps = new org.json.JSONObject(text).optJSONArray("apps");
                }
                if (apps == null) return empty(removed);
                java.util.List<Row> out = new java.util.ArrayList<>(apps.length());
                for (int i = 0; i < apps.length(); i++) {
                    org.json.JSONObject a = apps.optJSONObject(i);
                    if (a == null) continue;
                    String pkg = a.optString("package", "");
                    if (pkg.isEmpty()) continue;
                    // v1 rows carry no `state`; every v1 row is an installed
                    // app, which is what the missing-key default says.
                    //
                    // ⛔ THE TEST IS `installed`, POSITIVELY -- never a list of
                    // states to exclude. AppState (launcher/InstallCore.swift)
                    // gained `removed` at v1.0.998 for an app whose .app bundle
                    // the user deleted behind our back, and it needed NO change
                    // here precisely because this line asks the one question
                    // that matters. Any future state is likewise non-installed
                    // by default, which is the safe direction: the failure mode
                    // this guards against is PackageManager insisting an app is
                    // present when its code is gone, which is a state a device
                    // never has.
                    String state = a.optString("state", "installed");
                    // "ok" is the pre-v1.0.2239 control panel's spelling of
                    // installed (InstallCore.swift AppState.legacyInstalled).
                    // Skipping it hid every drag-drop install from
                    // PackageManager (issue #11: "0 installed row(s)").
                    if ("ok".equals(state)) state = "installed";
                    if (!"installed".equals(state)) {
                        if ("removed".equals(state)) removed.add(pkg);
                        continue;
                    }
                    out.add(new Row(
                        pkg,
                        a.optString("label", pkg),
                        a.optString("versionName", a.optString("version", "")),
                        a.optLong("versionCode", 0L),
                        emptyToNull(a.optString("apkPath", "")),
                        emptyToNull(a.optString("bundlePath", "")),
                        isoToMs(a.optString("installedAt", "")),
                        isoToMs(a.optString("updatedAt", "")),
                        emptyToNull(a.optString("dirName", ""))));
                }
                logOnce("[PM.registry] " + out.size() + " installed row(s) from "
                        + f.getPath()
                        + (removed.isEmpty() ? "" : " (" + removed.size()
                           + " row(s) present but NOT installed)"));
                return new Snapshot(out, removed);
            } catch (Throwable t) {
                logOnce("[PM.registry] unreadable registry " + f.getPath()
                        + " -- answering as if empty: " + t);
                return empty(removed);
            }
        }

        private static Snapshot empty(java.util.Set<String> removed) {
            return new Snapshot(java.util.Collections.emptyList(), removed);
        }

        private static String emptyToNull(String s) {
            return (s == null || s.isEmpty()) ? null : s;
        }

        /** ISO-8601 instant -> epoch ms, 0 when absent/unparseable. The Swift
         *  writer emits the strict no-fraction form; Instant.parse accepts the
         *  fractional one too, matching its tolerant decoder. */
        private static long isoToMs(String s) {
            if (s == null || s.isEmpty()) return 0L;
            try { return java.time.Instant.parse(s).toEpochMilli(); }
            catch (Throwable t) { return 0L; }
        }

        private static volatile String lastLogged;
        private static void logOnce(String msg) {
            if (msg.equals(lastLogged)) return;
            lastLogged = msg;
            // NativeLog is a native method: on a JVM without the runtime's
            // native library (the offline probes) it throws
            // UnsatisfiedLinkError, which must not escape a logging call --
            // same hazard this file already documents at manifest().
            try { NativeLog.i("Mac.apk.PM", msg); }
            catch (Throwable ignored) {
                try { System.err.println("[Mac.apk.PM] " + msg); }
                catch (Throwable ignored2) {}
            }
        }
    }

    // ---- API 30 package visibility (added 2026-08-17) -------------------
    //
    // WHY: flipping InstalledRegistry to default-ON (above) means EVERY app
    // that calls getPackageInfo/getInstalledPackages for a non-self package
    // now potentially sees every OTHER app Mac.apk has installed, on this ONE
    // shared machine. A real device does not do that unconditionally past
    // Android 11 -- see https://developer.android.com/training/
    // package-visibility/automatic (fetched 2026-08-17): "if your app targets
    // Android 10 (API level 29) or lower, all apps are visible to your app
    // automatically", and beyond that, only specific automatic grants plus
    // whatever the caller's own <queries> declares. The authoritative
    // enforcement point is AppsFilterBase.shouldFilterApplicationInternal
    // (aosp-mirror/platform_frameworks_base services/core/java/com/android/
    // server/pm/AppsFilterBase.java, fetched 2026-08-17), which in order:
    // same app/uid, then `AppsFilterUtils.requestsQueryAllPackages(pkg)` --
    // literally `pkg.getRequestedPermissions().contains(
    // Manifest.permission.QUERY_ALL_PACKAGES)` -- then target-side
    // forceQueryable, then `isQueryableViaPackage` (the caller's own
    // <queries><package> list, AppsFilterUtils.canQueryViaPackage), then
    // intent/provider <queries> matching, then several runtime-interaction
    // grants (activity-result, service-bind, content-provider access,
    // input-method), then RRO overlay-actor and uses-library grants.
    //
    // MIRRORED here: self, targetSdk<30 (pre-R apps are never filtered), and
    // QUERY_ALL_PACKAGES (both F-Droid 1.23.2 and Aurora Store 4.8.4 declare
    // it in their own manifests -- confirmed via `grep QUERY_ALL_PACKAGES` on
    // their decoded AndroidManifest.xml, 2026-08-17 -- so this ONE rule is
    // what makes both stores see the registry at all). Also mirrored:
    // <queries><package> (AppsFilterUtils.canQueryViaPackage), parsed into
    // Manifest.queriesPackages.
    //
    // DELIBERATELY NOT MIRRORED (conservative -- every omission here can only
    // HIDE a package a real device would show, never leak one the device
    // would hide): the installer-of/installed-by relationship (Mac.apk's
    // getInstallSourceInfo already reports no installer chain, so there is
    // nothing to key this on); the four runtime-interaction grants (activity-
    // result/service-bind/provider-access/IME) -- Mac.apk hosts one APK's
    // process at a time, so there is no second live app to interact with;
    // <queries><intent>/<provider> matching -- doing this honestly means
    // resolving the caller's intent templates against every OTHER registered
    // app's OWN manifest (getPackageArchiveInfo already exposes that data per
    // package, so it is not blocked, just out of proportion for this pass);
    // RRO overlay-actor and uses-library grants -- not modelled anywhere else
    // in this PackageManager either.
    private boolean packageVisible(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(this.packageName)) return true;   // "your own app"
        if (this.targetSdk < 30) return true;             // pre-R: unfiltered
        if (checkPermission("android.permission.QUERY_ALL_PACKAGES", this.packageName)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        Manifest m = manifest();
        if (m == null) return false;
        if (m.queriesPackages.contains(pkg)) return true;
        return macapkCanQueryViaComponents(m, pkg);
    }

    /** MACAPK_QUERIES_INTENT (default ON; =0 = only <queries><package>, the old
     *  answer): AppsFilterUtils.canQueryViaComponents (android14-release).
     *  A <queries><intent> makes a package visible when one of its EXPORTED
     *  activities / services / receivers has a filter the intent matches
     *  (matchesPackage -> matchesAnyComponents -> IntentFilter.match with
     *  supportWildcards = true); a <queries><provider> when one of its exported
     *  providers carries the authority (matchesProviders). Game frontends
     *  (iiSU) and launchers declare these instead of QUERY_ALL_PACKAGES. */
    private static final boolean QUERIES_INTENT =
        !"0".equals(System.getenv("MACAPK_QUERIES_INTENT"));
    /** pkg -> {target Manifest, Boolean}: the answer, recomputed when the target's
     *  parsed manifest changes (registryManifest re-parses on an APK update). */
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> mQueriesVisible =
        new java.util.concurrent.ConcurrentHashMap<>();

    private boolean macapkCanQueryViaComponents(Manifest querying, String pkg) {
        if (!QUERIES_INTENT) return false;
        if (querying.queriesIntents.isEmpty() && querying.queriesProviders.isEmpty()) return false;
        if (!InstalledRegistry.enabled()) return false;
        Manifest target = registryManifest(InstalledRegistry.lookup(pkg));
        if (target == null) return false;
        Object[] c = mQueriesVisible.get(pkg);
        if (c != null && c[0] == target) return (Boolean) c[1];
        boolean v = false;
        for (Manifest.QueriesIntent q : querying.queriesIntents) {
            if (macapkQueriesMatchesPackage(q, target)) { v = true; break; }
        }
        if (!v && !querying.queriesProviders.isEmpty()) {
            for (Manifest.ProviderEntry pe : target.providers) {
                if (!pe.exported || pe.authorities == null) continue;
                for (String a : pe.authorities.split(";")) {
                    if (querying.queriesProviders.contains(a)) { v = true; break; }
                }
                if (v) break;
            }
        }
        mQueriesVisible.put(pkg, new Object[] { target, v });
        return v;
    }

    /** AppsFilterUtils.matchesPackage: services, activities, receivers. (Its
     *  provider leg matches provider intent-filters, which our manifest model
     *  does not carry; providers are reached through <queries><provider>.) */
    private static boolean macapkQueriesMatchesPackage(Manifest.QueriesIntent q, Manifest t) {
        for (Manifest.ServiceEntry se : t.services.values()) {
            if (se.exported && macapkQueriesMatchesAny(q, se.filters)) return true;
        }
        for (Manifest.ActivityEntry ae : t.activities.values()) {
            if (ae.exported && macapkQueriesMatchesAny(q, ae.filters)) return true;
        }
        for (Manifest.ActivityEntry re : t.receivers.values()) {
            if (re.exported && macapkQueriesMatchesAny(q, re.filters)) return true;
        }
        return false;
    }

    private static boolean macapkQueriesMatchesAny(Manifest.QueriesIntent q,
            java.util.List<Manifest.IntentFilterEntry> filters) {
        if (filters == null) return false;
        for (Manifest.IntentFilterEntry f : filters) {
            if (macapkQueriesIntentMatches(q, f)) return true;
        }
        return false;
    }

    /** IntentFilter.match(action, type, scheme, data, categories, tag,
     *  supportWildcards = true) for a <queries><intent>, over the four dimensions
     *  our filter model keeps (action, categories, scheme, MIME type; host and
     *  path are not modelled, and a queries intent's path is always the wildcard).
     *  android14-release IntentFilter.java: match :2338, matchAction :834,
     *  matchData :1647, findMimeType :2997, matchCategories :1790. */
    static boolean macapkQueriesIntentMatches(Manifest.QueriesIntent q,
            Manifest.IntentFilterEntry f) {
        // matchAction: a null action matches; otherwise the filter must list it.
        if (q.action != null && !f.actions.contains(q.action)) return false;
        // matchData.
        final boolean hasTypes = !f.mimeTypes.isEmpty();
        final boolean hasSchemes = !f.schemes.isEmpty();
        final boolean hasDataUri = q.scheme != null;
        if (!hasTypes && !hasSchemes) {
            if (q.type != null || hasDataUri) return false;
        } else {
            if (hasSchemes) {
                String sc = q.scheme != null ? q.scheme : "";
                if (!f.schemes.contains(sc) && !"*".equals(sc)) return false;
            } else if (q.scheme != null && !q.scheme.isEmpty() && !"content".equals(q.scheme)
                    && !"file".equals(q.scheme) && !"*".equals(q.scheme)) {
                return false;
            }
            if (hasTypes) {
                if (!macapkFindMimeType(f.mimeTypes, q.type)) return false;
            } else if (q.type != null) {
                return false;
            }
        }
        // matchCategories: every category of the intent must be in the filter.
        for (String c : q.categories) {
            if (!f.categories.contains(c)) return false;
        }
        return true;
    }

    /** IntentFilter.findMimeType, for types as the manifest declares them. */
    private static boolean macapkFindMimeType(java.util.List<String> t, String type) {
        if (type == null) return false;
        if (t.contains(type)) return true;
        if (type.equals("*/*")) return !t.isEmpty();
        // a filter type of "*" or "*/*" accepts every type (hasPartialTypes + "*")
        if (t.contains("*") || t.contains("*/*")) return true;
        int slash = type.indexOf('/');
        if (slash > 0) {
            String base = type.substring(0, slash);
            if (t.contains(base) || t.contains(base + "/*")) return true;
            if (type.length() == slash + 2 && type.charAt(slash + 1) == '*') {
                for (String v : t) {
                    if (type.regionMatches(0, v, 0, slash + 1)) return true;
                }
            }
        }
        return false;
    }

    /** PackageInfo for a registry-installed package, or null when the registry
     *  gate is off / the package is not registered / the name is denied.
     *
     *  Fidelity order: parse the RETAINED APK with the same
     *  getPackageArchiveInfo primitive an installer preview uses -- that
     *  answers versions, min/target SDK and (GET_PERMISSIONS) the requested
     *  set from the app's real manifest -- then overlay the registry's install
     *  facts (times, resolved label, data location). When the retained APK is
     *  gone or its parse disagrees with the row's package name (a swapped or
     *  corrupt retention -- never trust it to describe a different package),
     *  degrade to the thin row: the registry is the authority on WHAT is
     *  installed, the archive only on what is inside it. */
    private PackageInfo registryPackageInfo(String packageName, int flags) {
        InstalledRegistry.Row row = InstalledRegistry.lookup(packageName);
        if (row == null) return null;
        String apk = resolvedRowApk(row);
        PackageInfo info = null;
        if (apk != null) {
            try {
                PackageInfo parsed = getPackageArchiveInfo(apk, flags);
                if (parsed != null && row.pkg.equals(parsed.packageName)) info = parsed;
            } catch (Throwable ignored) {}
        }
        if (info == null) {
            info = new PackageInfo();
            info.packageName     = row.pkg;
            info.versionName     = row.versionName;
            info.versionCode     = (int) row.versionCode;
            info.longVersionCode = row.versionCode;
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = row.pkg;
            ai.processName = row.pkg;
            if (apk != null) {
                ai.sourceDir = apk;
                ai.publicSourceDir = apk;
            }
            info.applicationInfo = ai;
            // An installed package always has >= 1 signer, and callers index
            // the arrays unconditionally (F-Droid's UpdateChecker maps
            // packageInfo.signatures with no null check). When no APK can be
            // read, answer EMPTY rather than null: "no signer matches" is the
            // truthful degrade, an NPE is not.
            if ((flags & GET_SIGNATURES) != 0) info.signatures = new Signature[0];
            if ((flags & GET_SIGNING_CERTIFICATES) != 0) {
                info.signingInfo = new android.content.pm.SigningInfo(new Signature[0]);
            }
        }
        if (info.applicationInfo != null) {
            // AOSP: FLAG_INSTALLED is set for every package installed for the
            // user (the archive parse is of a not-yet-installed file, so it
            // correctly leaves it clear -- this row IS installed).
            info.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        }
        // The registry's versionCode is the manifest's, recorded at install; a
        // v1-migrated row carries 0 and the archive parse (when it ran) is the
        // better answer -- so only overlay a REAL recorded code.
        if (row.versionCode > 0) {
            info.versionCode     = (int) row.versionCode;
            info.longVersionCode = row.versionCode;
        }
        if (row.versionName != null && !row.versionName.isEmpty()
                && (info.versionName == null || info.versionName.isEmpty())) {
            info.versionName = row.versionName;
        }
        long installed = row.installedAtMs;
        if (installed == 0L && apk != null) {
            try { installed = new java.io.File(apk).lastModified(); }
            catch (Throwable ignored) {}
        }
        info.firstInstallTime = installed;
        info.lastUpdateTime   = row.updatedAtMs > 0L ? row.updatedAtMs : installed;
        ApplicationInfo ai = info.applicationInfo;
        if (ai != null) {
            // The row's label is the RESOLVED display name (the installer
            // resolved resource references at install time; an archive parse
            // cannot). loadLabel reads nonLocalizedLabel first, so this is
            // what makes getApplicationLabel say "F-Droid", not the package.
            if (row.label != null && !row.label.isEmpty()
                    && !row.label.equals(row.pkg)) {
                ai.nonLocalizedLabel = row.label;
            }
            // Per-app bundles keep the app's writable data inside the bundle:
            // <bundle>/Contents/android/data/data/<pkg> (BundleGenerator
            // contract). Only claim it when the bundle is recorded.
            if (ai.dataDir == null && row.bundlePath != null) {
                ai.dataDir = row.bundlePath + "/Contents/android/data/data/" + row.pkg;
            }
            // nativeLibraryDir of ANOTHER installed package (2026-10-12+5, issue #5
            // follow-up). The archive parse above describes a not-yet-installed file, so
            // it leaves the field null -- but an INSTALLED package always has one: AOSP
            // composes it from the code path at install (PackageAbiHelperImpl:
            // nativeLibraryRootDir = <codeFile>/lib, nativeLibraryDir = <root>/<isa>), and
            // getApplicationInfo(otherPkg) returns it. Plugin-style apps depend on that:
            // ytdlnis 1.9.0 finds its FFmpeg add-on package with
            // getApplicationInfo("com.deniscerri.ytdl.ffmpeg").nativeLibraryDir and execs
            // <dir>/libffmpeg.so; with null it silently ignored the installed plugin and
            // kept offering it for download. Our installer puts the libraries (and the
            // elfexec stand-ins for APK-shipped executables) in exactly that place:
            // <bundle>/Contents/android/data/app/~~<n>/<pkg>-<n>/lib/<isa>. Only claimed
            // for an installed tree (the runtime's .installed marker beside base.apk).
            // MACAPK_PM_REGISTRY_NATIVE_LIB_DIR=0 restores the null.
            if (ai.nativeLibraryDir == null && apk != null && REGISTRY_NATIVE_LIB_DIR) {
                java.io.File codeDir = new java.io.File(apk).getParentFile();
                if (codeDir != null && new java.io.File(codeDir, ".installed").exists()) {
                    java.io.File libRoot = new java.io.File(codeDir, "lib");
                    // The primary instruction set the installer extracted (apk_unpacker
                    // AbiToIsa: arm64-v8a -> arm64, armeabi[-v7a] -> arm); arm64 when the
                    // package ships no native code, as a device reports its own ISA.
                    String isa = new java.io.File(libRoot, "arm64").isDirectory() ? "arm64"
                               : new java.io.File(libRoot, "arm").isDirectory() ? "arm" : "arm64";
                    ai.nativeLibraryDir = new java.io.File(libRoot, isa).getAbsolutePath();
                }
            }
            // v1.0.1586: the row's apkPath is that package's installed base.apk
            // (InstallCore records `installedApk` since v1.0.1585), so its
            // splits are the siblings; an archive parse of one file cannot
            // know them. Additive, null for a package without any.
            fillSplitFields(ai, info, apk);
        }
        return info;
    }

    /** The row's APK -- or, when that file is gone, the bundle's INSTALLED
     *  base.apk. v1.0.1639: rows written before v1.0.1585 record apkPath in the
     *  ~/Library/Application Support/Mac.apk/apks retention dir, which was
     *  dropped on 2026-09-19 (22 of 24 rows on the owner's machine), so every
     *  such package degraded to the thin row: no signatures, no min/target SDK,
     *  no metaData, no splits. The installed copy is
     *  <bundle>/Contents/android/data/app/<nonce>/<pkg-nonce>/base.apk with
     *  the runtime's `.installed` marker beside it -- the same rule as
     *  InstallCore.installedBaseApk / AppStubMain.installedBaseApk (the three
     *  must agree on what "installed" means). The caller still checks that the
     *  parse names this row's package. */
    // ================= STAGE 1 (cross-app design): a REGISTRY app's activities
    // ================= docs/cross-app-launch-and-implicit-start-design_2026-09-26.md
    //
    // Before this, a query or getActivityInfo naming another INSTALLED Mac.apk
    // app answered nothing: getPackageInfo said the package was installed while
    // getLaunchIntentForPackage said it had no launcher -- a state a device never
    // has, and why F-Droid / Aurora hid "Open" for apps Mac.apk installed. A
    // query scoped to a registry package now resolves against THAT row's own
    // installed manifest, narrowed by package visibility as ComputerEngine does
    // for queries; the start path (macapkRegistryActivityForStart) is not
    // narrowed, as resolveForStart is not (ComputerEngine :561-569).
    //
    // Ships together with the cross-app sender + receiver (CrossAppLauncher /
    // IntentInbox): the query side alone would turn a hidden "Open" into a
    // visible button that does nothing. =0 restores the pre-change answers.
    /** See registryPackageInfo: another installed package's nativeLibraryDir. */
    private static final boolean REGISTRY_NATIVE_LIB_DIR =
        !"0".equals(System.getenv("MACAPK_PM_REGISTRY_NATIVE_LIB_DIR"));
    private static final boolean REGISTRY_ACTIVITIES =
        !"0".equals(System.getenv("MACAPK_PM_REGISTRY_ACTIVITIES"));
    static boolean macapkRegistryActivities() {
        return REGISTRY_ACTIVITIES && InstalledRegistry.enabled();
    }

    /** path -> {mtime, Drawable.ConstantState-free Bitmap}: decoded bundle icons. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sRegistryIcons =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Another installed Mac.apk app's icon: Contents/Resources/AppIcon.png of its
     *  bundle (launcher/BundleGenerator.swift renders it from the APK's own
     *  launcher icon at install). Only for a package the caller can see, like
     *  every other answer about it; null otherwise and for the running app, whose
     *  own Resources answer. MACAPK_XAPP_IMPLICIT=0 -> null (the old default icon). */
    @Override
    protected android.graphics.drawable.Drawable macapkRegistryAppIcon(String pkg) {
        if (!XAPP_IMPLICIT || pkg == null || pkg.equals(packageName)) return null;
        if (!macapkRegistryActivities() || !packageVisible(pkg)) return null;
        InstalledRegistry.Row row = InstalledRegistry.lookup(pkg);
        if (row == null || row.bundlePath == null) return null;
        java.io.File f = new java.io.File(row.bundlePath, "Contents/Resources/AppIcon.png");
        if (!f.isFile()) return null;
        String key = f.getPath();
        long mt = f.lastModified();
        Object[] c = sRegistryIcons.get(key);
        android.graphics.Bitmap bm = (c != null && (Long) c[0] == mt) ? (android.graphics.Bitmap) c[1] : null;
        if (bm == null) {
            try {
                bm = android.graphics.BitmapFactory.decodeFile(key);
            } catch (Throwable t) {
                bm = null;
            }
            if (bm == null) return null;
            sRegistryIcons.put(key, new Object[] { mt, bm });
        }
        android.app.Application app = android.app.Application.macapk_get();
        return new android.graphics.drawable.BitmapDrawable(
                app != null ? app.getResources() : null, bm);
    }

    /** Stage 4 gate (default ON; =0 restores package-scoped-only registry rows). */
    private static final boolean XAPP_IMPLICIT =
        !"0".equals(System.getenv("MACAPK_XAPP_IMPLICIT"));
    static boolean macapkXappImplicit() { return XAPP_IMPLICIT; }

    /** True while ActivityHost resolves a START (resolveForStart): registry rows are
     *  then not narrowed by package visibility, as ComputerEngine does for a start. */
    private static final ThreadLocal<Boolean> FOR_START = ThreadLocal.withInitial(() -> false);

    /** queryIntentActivities in START mode (see FOR_START). */
    public static java.util.List<ResolveInfo> macapkQueryForStart(
            android.content.pm.PackageManager pm, Intent intent, int flags) {
        final boolean prev = FOR_START.get();
        FOR_START.set(true);
        try {
            return pm.queryIntentActivities(intent, flags);
        } finally {
            FOR_START.set(prev);
        }
    }

    /** apk path -> {mtime, size, Manifest}: a registry app's parsed manifest,
     *  re-parsed only when its installed APK changes (an update rewrites it). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sRegistryManifests =
        new java.util.concurrent.ConcurrentHashMap<>();

    static Manifest registryManifest(InstalledRegistry.Row row) {
        String apk = row != null ? resolvedRowApk(row) : null;
        if (apk == null) return null;
        java.io.File f = new java.io.File(apk);
        if (!f.isFile()) return null;
        long mt = f.lastModified(), sz = f.length();
        Object[] c = sRegistryManifests.get(apk);
        if (c != null && (Long) c[0] == mt && (Long) c[1] == sz) return (Manifest) c[2];
        try {
            Manifest m = Manifest.parse(null, apk);
            // Never let a swapped or corrupt retention describe another package.
            if (m == null || !row.pkg.equals(m.packageName)) return null;
            sRegistryManifests.put(apk, new Object[] { mt, sz, m });
            return m;
        } catch (Throwable t) {
            NativeLog.w("MacApkPM", "[PM.registry] manifest of " + row.pkg
                    + " unreadable (" + t + "); its activities do not resolve");
            return null;
        }
    }

    /** An ActivityInfo for a REGISTRY app's component, owned by THAT package
     *  (its packageName, process, default task affinity and ApplicationInfo). */
    private android.content.pm.ActivityInfo registryActivityInfo(
            InstalledRegistry.Row row, Manifest.ActivityEntry e, int flags) {
        android.content.pm.ActivityInfo info = toActivityInfo(e, flags);
        // toActivityInfo resolves @-references against THIS app's resource table,
        // so for another app's component those ids name nothing of theirs (or
        // something of ours). Drop them: loadLabel then falls back to the
        // application label (the registry row's), and loadIcon to the app icon
        // (macapkRegistryAppIcon). A literal android:label is kept.
        // MACAPK_XAPP_IMPLICIT=0 keeps the old ids.
        if (XAPP_IMPLICIT) {
            info.icon = 0;
            info.logo = 0;
            info.labelRes = 0;
            info.theme = 0;
        }
        info.packageName = row.pkg;
        info.processName = e.processName != null ? e.processName : row.pkg;
        if (e.taskAffinity == null) info.taskAffinity = row.pkg;
        PackageInfo pi = registryPackageInfo(row.pkg, 0);
        info.applicationInfo = pi != null ? pi.applicationInfo : null;
        return info;
    }

    /** Append the rows of a registry package's activities that match, with the
     *  same matcher, credit and cut as the own scan in queryIntentActivities. */
    private void registryScan(String pkg, Intent query, String resolvedType, int flags,
            ComponentName explicit, boolean order, boolean defaultOnly, boolean perPackage,
            boolean clamp, java.util.List<ResolveInfo> out, java.util.List<Integer> cuts) {
        InstalledRegistry.Row row = InstalledRegistry.lookup(pkg);
        Manifest m = registryManifest(row);
        if (m == null) return;
        final boolean withDisabled =
            (flags & android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS) != 0;
        for (Manifest.ActivityEntry e : m.activities.values()) {
            // Another app's setComponentEnabledSetting state is not ours to know;
            // its manifest android:enabled is.
            if (!e.enabled && !withDisabled) continue;
            int[] credited = null;
            if (explicit != null) {
                if (!e.name.equals(explicit.getClassName())) continue;
            } else if (order) {
                credited = aospCreditedFilter(e.filters, query, resolvedType, defaultOnly,
                        perPackage, clamp);
                if (credited == null) continue;
            } else if (!matchesAnyFilter(e, query, resolvedType, flags)) {
                continue;
            }
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = registryActivityInfo(row, e, flags);
            if (credited != null) {
                ri.priority = credited[0];
                ri.match = credited[1];
                ri.isDefault = credited[2] != 0;
                cuts.add(credited[3]);
            }
            out.add(ri);
            if (explicit != null) break;
        }
    }

    /** The START path's lookup of an explicit component in a registry app
     *  (resolveForStart: NOT narrowed by package visibility), or null. */
    public android.content.pm.ActivityInfo macapkRegistryActivityForStart(ComponentName cn) {
        if (cn == null || !macapkRegistryActivities()) return null;
        InstalledRegistry.Row row = InstalledRegistry.lookup(cn.getPackageName());
        Manifest m = registryManifest(row);
        if (m == null) return null;
        Manifest.ActivityEntry e = m.activities.get(cn.getClassName());
        if (e == null || !e.enabled) return null;
        return registryActivityInfo(row, e, 0);
    }

    /** {bundlePath, dirName, label} of an installed registry package, or null. */
    public String[] macapkRegistryBundle(String pkg) {
        if (!macapkRegistryActivities()) return null;
        InstalledRegistry.Row row = InstalledRegistry.lookup(pkg);
        if (row == null || row.bundlePath == null) return null;
        return new String[] { row.bundlePath, row.dirName, row.label };
    }

    private static String resolvedRowApk(InstalledRegistry.Row row) {
        if (row.apkPath != null && new java.io.File(row.apkPath).isFile()) return row.apkPath;
        if (row.bundlePath == null) return row.apkPath;
        java.io.File[] nonces = new java.io.File(row.bundlePath,
            "Contents/android/data/app").listFiles();
        if (nonces == null) return row.apkPath;
        java.util.Arrays.sort(nonces);
        for (java.io.File nonce : nonces) {
            if (nonce.getName().startsWith(".")) continue;
            java.io.File[] pkgDirs = nonce.listFiles();
            if (pkgDirs == null) continue;
            // The package's own `<pkg>-<nonce>` dir first: a stray install dir
            // (Instagram's `base-Qm..`) sorts before it (v1.0.1641).
            final String own = row.pkg + "-";
            java.util.Arrays.sort(pkgDirs, (a, b) -> {
                boolean ao = a.getName().startsWith(own), bo = b.getName().startsWith(own);
                return ao != bo ? (ao ? -1 : 1) : a.compareTo(b);
            });
            for (java.io.File pkgDir : pkgDirs) {
                if (pkgDir.getName().startsWith(".")) continue;
                java.io.File base = new java.io.File(pkgDir, "base.apk");
                if (base.isFile() && new java.io.File(pkgDir, ".installed").exists()) {
                    return base.getPath();
                }
            }
        }
        return row.apkPath;
    }

    /** AOSP getInstalledPackages: every installed package THIS CALLER CAN SEE.
     *  The installed set on this machine is the running app plus every
     *  registry row, so the union, narrowed by packageVisible, is the
     *  truthful answer -- F-Droid's DbUpdateChecker/UpdatesAdapter enumerate
     *  exactly this (confirmed from its own bytecode: getUpdatableApps(...)
     *  calls packageManager.getInstalledPackages(I) directly) to find
     *  updatable installed apps. With the registry gate off this is
     *  byte-identical to the old self-only answer. */
    @Override public java.util.List<PackageInfo> getInstalledPackages(int flags) {
        java.util.List<PackageInfo> out = super.getInstalledPackages(flags);
        for (InstalledRegistry.Row r : InstalledRegistry.allBest()) {
            if (r.pkg.equals(this.packageName)) continue;   // self is already in, richer
            if (!packageVisible(r.pkg)) continue;
            try {
                PackageInfo pi = registryPackageInfo(r.pkg, flags);
                if (pi != null) out.add(pi);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** ApplicationInfo sibling of the union above, derived through the same
     *  registryPackageInfo path so the two lists can never disagree about a
     *  package's identity or versions. Same packageVisible narrowing. */
    @Override public java.util.List<ApplicationInfo> getInstalledApplications(int flags) {
        java.util.List<ApplicationInfo> out = super.getInstalledApplications(flags);
        for (InstalledRegistry.Row r : InstalledRegistry.allBest()) {
            if (r.pkg.equals(this.packageName)) continue;
            if (!packageVisible(r.pkg)) continue;
            try {
                PackageInfo pi = registryPackageInfo(r.pkg, flags);
                if (pi != null && pi.applicationInfo != null) out.add(pi.applicationInfo);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    // ---- Activity / receiver components (v1.0.834) --------------------------
    //
    // WHY THIS EXISTS. PackageManager.getActivityInfo used to synthesise a
    // metadata-free ActivityInfo (name + packageName + applicationInfo, nothing
    // else) and `grep -rn parentActivityName runtime/java/` had ZERO writers
    // tree-wide. Measured consequence on Sgt Puzzles 1.49 (2026-07-29): its
    // launcher trampoline does
    //   TaskStackBuilder.create(this)
    //       .addNextIntentWithParentStack(intent.setClass(this, GamePlay::class))
    //       .startActivities()
    // and androidx's addNextIntentWithParentStack -> addParentStack ->
    // NavUtils.getParentActivityIntent -> NavUtils.getParentActivityName reads
    //   pm.getActivityInfo(componentName, GET_META_DATA).parentActivityName
    // then falls back to metaData.getString("android.support.PARENT_ACTIVITY").
    // BOTH were null, so the parent stack came back empty, GameChooser was
    // never pushed under GamePlay, and GamePlay's own no-saved-state branch
    // (NavUtils.navigateUpFromSameTask) threw the androidx guard
    //   IllegalArgumentException: Activity GamePlay does not have a parent
    //   activity name specified.
    // The app's real first screen never launched. The APK's manifest declares
    // BOTH routes (android:parentActivityName AND the PARENT_ACTIVITY
    // meta-data), so this was purely our gap.
    //
    // The activity/receiver maps come from Manifest.parse, which
    // MacApkPackageManager loads LAZILY off its own apkPath rather than being
    // handed them by Boot: the parse is a few ms, happens at most once, and
    // keeping it here avoids threading another constructor argument through
    // Application/Boot for data only this class consumes.
    private volatile Manifest lazyManifest;
    private volatile boolean  lazyManifestTried;

    private Manifest manifest() {
        if (lazyManifestTried) return lazyManifest;
        synchronized (this) {
            if (lazyManifestTried) return lazyManifest;
            lazyManifestTried = true;
            try {
                // Manifest.parse prefers the BINARY AndroidManifest.xml inside
                // the APK and falls back to the apktool TEXT decode in the app
                // data dir, so hand it both.
                java.io.File decoded = null;
                if (dataDir != null) {
                    java.io.File a = new java.io.File(dataDir, "_apkt/AndroidManifest.xml");
                    java.io.File b = new java.io.File(dataDir, "AndroidManifest.xml");
                    decoded = a.isFile() ? a : (b.isFile() ? b : null);
                }
                if (decoded != null || (apkPath != null && !apkPath.isEmpty())) {
                    lazyManifest = Manifest.parse(decoded, apkPath);
                }
            } catch (Throwable t) {
                // ⚠ v1.0.938: the log call is itself wrapped. NativeLog.w is a
                // NATIVE method, so on a path where the native library is not
                // loaded it raises UnsatisfiedLinkError -- OUT OF A CATCH BLOCK,
                // which propagates past this handler and takes down whatever was
                // calling manifest(). A lane hit exactly that in its own new code
                // in this file and fixed it there; this site had the same
                // exposure. A logging failure must never be able to escalate the
                // error it was reporting.
                try {
                    NativeLog.w("Mac.apk.PM",
                        "component manifest parse failed (" + t + ") -- getActivityInfo"
                        + " falls back to the permissive synthesised ActivityInfo");
                } catch (Throwable ignored) {}
                lazyManifest = null;
            }
            return lazyManifest;
        }
    }

    private Manifest.ActivityEntry activityEntry(String cls, boolean receiver) {
        Manifest m = manifest();
        if (m == null || cls == null) return null;
        return receiver ? m.receivers.get(cls) : m.activities.get(cls);
    }

    /** AOSP PackageManager.getActivityInfo(ComponentName, int) via
     *  PackageInfoUtils.generateActivityInfo: every manifest attribute of the
     *  named <activity> lands on the returned ActivityInfo, and
     *  GET_META_DATA additionally populates metaData from the component's own
     *  <meta-data> children (application-level meta-data does NOT leak in --
     *  that belongs to ApplicationInfo.metaData).
     *
     *  DELIBERATE DIVERGENCE, retained from the previous implementation: AOSP
     *  throws NameNotFoundException for a component that is not declared, and
     *  we instead return the old permissive stub for that case. Every Activity
     *  Mac.apk hosts is "known" to the loader whether or not it is in the
     *  manifest (ActivityHost can be asked to start a class directly), and
     *  several framework paths -- Activity.getComponentName-based lookups,
     *  AppCompat delegate theming -- call this for components the manifest
     *  never declared. Turning those into a throw is a separate, unmeasured
     *  behaviour change. A DECLARED component now always gets real data, which
     *  is what every reported gap needed. */
    @Override public android.content.pm.ActivityInfo getActivityInfo(
            ComponentName component, int flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        return componentInfo(component, flags, false);
    }

    /** AOSP getReceiverInfo returns an ActivityInfo too, but from the package's
     *  RECEIVERS list -- a separate component namespace. The base class used to
     *  delegate straight to getActivityInfo, which made a <receiver> and an
     *  <activity> of the same name indistinguishable and left every receiver
     *  metadata-free. */
    @Override public android.content.pm.ActivityInfo getReceiverInfo(
            ComponentName component, int flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        return componentInfo(component, flags, true);
    }

    // ================= COMPONENT GATING (android:enabled + the runtime
    // ================= setComponentEnabledSetting override)
    //
    // AOSP has exactly ONE function that answers "may this component be seen /
    // created", and every resolver and every getter calls it:
    //
    //   services/core/java/com/android/server/pm/pkg/PackageUserStateUtils.java
    //     :118-152  isEnabled(state, isPackageEnabled, isComponentEnabled,
    //                          componentName, flags)
    //
    // Before v1.1.0 Mac.apk instead open-coded `if (!e.enabled) continue;` at
    // TWO sites (queryIntentActivities, queryBroadcastReceivers), had no test
    // at all at SIX more (Boot.installAutoInitProviders, ServiceRegistry.ensure,
    // Context.macapkDeliverToManifestReceiver, getServiceInfo, getProviderInfo,
    // resolveContentProvider), and honoured MATCH_DISABLED_COMPONENTS nowhere.
    // The visible consequence: we constructed components a device never
    // constructs -- e.g. com.zhiliaoapp.musically declares
    // `<provider android:name="com.google.firebase.provider.FirebaseInitProvider"
    // android:enabled="false" .../>` and we ran it anyway.
    //
    // ⚠ THE SUBTLETY THAT MAKES A NAIVE FIX WRONG: "skip if the manifest says
    // enabled=false" is NOT the contract. setComponentEnabledSetting overrides
    // the manifest in BOTH directions, and AOSP consults that override BEFORE
    // falling back to the manifest value. androidx WorkManager depends on
    // exactly this: PackageManagerHelper.kt:49-125 reads
    // getComponentEnabledSetting first, treats DEFAULT as "go read the manifest
    // value via getInfo(name, MATCH_DISABLED_COMPONENTS).enabled", and only
    // calls setComponentEnabledSetting when the desired state differs. With a
    // setter that stores nothing, its cache says false forever.

    /** The runtime component-enabled override, i.e. AOSP's per-user
     *  PackageUserState.{mEnabledComponents,mDisabledComponents}. Keyed by
     *  ComponentName.getClassName() because this host has exactly one package.
     *
     *  ConcurrentHashMap: set/get are reachable from any thread (WorkManager
     *  does this off the main thread during its initializer).
     *
     *  NOTE the deliberate asymmetry with AOSP's two-SET model: AOSP stores
     *  membership in an enabled-set or a disabled-set, and
     *  PackageManagerService.setEnabledSetting only ever accepts ENABLED /
     *  DISABLED / DEFAULT for a COMPONENT (DEFAULT removes it from both sets).
     *  A single map with the state as the value is the same information; the
     *  DISABLED_USER / DISABLED_UNTIL_USED values are accepted at the API
     *  boundary (they are legal arguments) and read back faithfully by
     *  getComponentEnabledSetting. */
    private final java.util.Map<String, Integer> componentEnabledState =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The package-level override, i.e. AOSP's PackageUserState.getEnabledState().
     *  Set by setApplicationEnabledSetting. */
    private volatile int applicationEnabledSetting = COMPONENT_ENABLED_STATE_DEFAULT;

    /** DEFAULT-ON gate for the whole component-gating contract.
     *
     *  Same `!"0".equals(getenv(...))` idiom as MACAPK_PM_REGISTRY /
     *  MACAPK_FOLLOW_SYSTEM_APPEARANCE elsewhere in this tree: unset or any
     *  non-"0" value is ON; "0" is the explicit opt-out.
     *
     *  It exists because this change has real, MEASURED blast radius -- it
     *  stops 6 of 247 corpus provider constructions across 5 apps, two of them
     *  Firebase/lifecycle auto-init providers -- and the owner needs to A/B it
     *  in a single run rather than by reverting a commit. With the gate OFF,
     *  macapkIsComponentEnabled degrades to `return manifestEnabled`, which is
     *  byte-for-byte the predicate the two pre-existing sites used, and every
     *  NEW enforcement site tests the gate itself, so OFF reproduces the
     *  pre-v1.1.0 tree exactly. */
    static boolean macapkComponentGating() {
        return !"0".equals(System.getenv("MACAPK_COMPONENT_GATING"));
    }

    /** AOSP PackageUserStateUtils.isEnabled(state, isPackageEnabled,
     *  isComponentEnabled, componentName, flags), transcribed
     *  (android14-release, services/core/java/com/android/server/pm/pkg/
     *  PackageUserStateUtils.java:118-152). Verbatim structure:
     *
     *    if ((flags & MATCH_DISABLED_COMPONENTS) != 0) return true;
     *    switch (state.getEnabledState()) {
     *      case COMPONENT_ENABLED_STATE_DISABLED:
     *      case COMPONENT_ENABLED_STATE_DISABLED_USER:
     *        return false;
     *      case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
     *        if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) == 0) return false;
     *        // FALLS THROUGH
     *      case COMPONENT_ENABLED_STATE_DEFAULT:
     *        if (!isPackageEnabled) return false;
     *        // FALLS THROUGH
     *      case COMPONENT_ENABLED_STATE_ENABLED:
     *        break;
     *    }
     *    if (state.isComponentEnabled(componentName)) return true;
     *    else if (state.isComponentDisabled(componentName)) return false;
     *    return isComponentEnabled;
     *
     *  ⚠ The switch FALLS THROUGH, and the fall-through is load-bearing: a
     *  package in DISABLED_UNTIL_USED whose caller passed
     *  MATCH_DISABLED_UNTIL_USED_COMPONENTS still has to clear the
     *  package-enabled test below it. Flattening the switch into a chain of
     *  independent ifs gets that case wrong. It is reproduced literally here
     *  (Java switch fall-through has the same semantics) rather than
     *  paraphrased.
     *
     *  @param className      the component's class name (our ComponentName key)
     *  @param manifestEnabled the component's MANIFEST android:enabled value
     *  @param flags          the caller's PackageManager flags
     */
    boolean macapkIsComponentEnabled(String className, boolean manifestEnabled,
                                     int flags) {
        // Gate OFF: the legacy predicate, exactly. See macapkComponentGating.
        if (!macapkComponentGating()) return manifestEnabled;

        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) {
            return true;
        }
        // `isPackageEnabled` -- the <application android:enabled> manifest
        // value. MEASURED: zero corpus apps declare it false, so this is
        // always true today.
        Manifest m = manifest();
        boolean isPackageEnabled = (m == null) || m.applicationEnabled;

        switch (applicationEnabledSetting) {
            case COMPONENT_ENABLED_STATE_DISABLED:
            case COMPONENT_ENABLED_STATE_DISABLED_USER:
                return false;
            case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) == 0) {
                    return false;
                }
                // falls through -- see the header; this is AOSP's own shape
            case COMPONENT_ENABLED_STATE_DEFAULT:
                if (!isPackageEnabled) {
                    return false;
                }
                // falls through
            case COMPONENT_ENABLED_STATE_ENABLED:
            default:
                break;
        }

        // The per-component runtime override. AOSP's two sets collapse to one
        // map lookup: present-and-ENABLED is isComponentEnabled(), any other
        // stored value is isComponentDisabled(), absent is neither.
        Integer stored = (className != null)
            ? componentEnabledState.get(className) : null;
        if (stored != null) {
            if (stored == COMPONENT_ENABLED_STATE_ENABLED) return true;
            return false;
        }
        return manifestEnabled;
    }

    /** AOSP PackageManagerService.setEnabledSettings:3826-3835 -- the ONE
     *  validator both the component and the package form route through
     *  (setApplicationEnabledSetting is :5733, setComponentEnabledSetting is
     *  :5864, and both build a ComponentEnabledSetting and call it), so the
     *  message really is "Invalid new component state" for both:
     *
     *    if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
     *            || newState == COMPONENT_ENABLED_STATE_ENABLED
     *            || newState == COMPONENT_ENABLED_STATE_DISABLED
     *            || newState == COMPONENT_ENABLED_STATE_DISABLED_USER
     *            || newState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
     *        throw new IllegalArgumentException("Invalid new component state: " + newState);
     *    }
     *
     *  ⚠ Then a SECOND, narrower rule applies to COMPONENTS specifically, and
     *  it is easy to miss: setEnabledSettingInternalLocked:4104-4118 switches
     *  on newState and only ENABLED / DISABLED / DEFAULT actually store --
     *  DISABLED_USER and DISABLED_UNTIL_USED fall to `default:` and merely
     *  Slog.e "requested an invalid new component state", storing NOTHING. So
     *  those two are legal ARGUMENTS (no throw) but no-ops at the component
     *  level, and a subsequent getComponentEnabledSetting still reads DEFAULT.
     *  The plan this change was written from asserted they should be stored;
     *  the AOSP source says otherwise and the source wins. It also means the
     *  stored value can only ever be ENABLED or DISABLED, which is what makes
     *  the two-line override read in macapkIsComponentEnabled correct. */
    @Override public void setComponentEnabledSetting(
            ComponentName componentName, int newState, int flags) {
        if (componentName == null) return;
        switch (newState) {
            case COMPONENT_ENABLED_STATE_DEFAULT:
            case COMPONENT_ENABLED_STATE_ENABLED:
            case COMPONENT_ENABLED_STATE_DISABLED:
            case COMPONENT_ENABLED_STATE_DISABLED_USER:
            case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid new component state: " + newState);
        }
        String cls = componentName.getClassName();
        if (cls == null) return;
        switch (newState) {
            case COMPONENT_ENABLED_STATE_ENABLED:
            case COMPONENT_ENABLED_STATE_DISABLED:
                componentEnabledState.put(cls, newState);
                break;
            case COMPONENT_ENABLED_STATE_DEFAULT:
                // AOSP restoreComponentLPw removes the name from BOTH sets
                // rather than storing a zero.
                componentEnabledState.remove(cls);
                break;
            default:
                // DISABLED_USER / DISABLED_UNTIL_USED: AOSP logs and stores
                // nothing. Mirror that, including the log, so a caller that
                // depends on the read-back seeing DEFAULT behaves the same.
                NativeLog.w("PackageManager",
                    "Failed setComponentEnabledSetting: component "
                    + this.packageName + "/" + cls
                    + " requested an invalid new component state: " + newState);
                return;
        }
        // DONT_KILL_APP is the only flag with meaning here and it describes
        // process management we do not do (there is one process and we are it),
        // so it is correctly ignored rather than unimplemented.
        NativeLog.i("PackageManager", "setComponentEnabledSetting " + cls
                  + " -> " + newState);
    }

    /** AOSP throws for a component whose PACKAGE is not installed; returning
     *  DEFAULT for everything is a different answer and apps branch on it.
     *
     *  This used to key on the CLASS NAME alone and answer
     *  COMPONENT_ENABLED_STATE_DEFAULT for any component it had not been told
     *  about -- including components of packages that do not exist on this
     *  machine. DEFAULT means "enabled as the manifest declared it", so we were
     *  reporting that a component of an absent package is installed and enabled.
     *
     *  AOSP's path is `ComputerEngine.Settings.getComponentEnabledSetting(...)
     *  throws PackageManager.NameNotFoundException` (the delegate declares it),
     *  surfaced through the public API -- whose signature declares no checked
     *  exception -- as IllegalArgumentException.
     *
     *  ⚠ MEASURED COST, and it is not hypothetical. Instagram's
     *  `X.AFY.A00(Context)` asks whether two `com.facebook.appmanager`
     *  components are enabled, to decide whether it is running as an OEM
     *  PRELOAD. Its own exception table guards that range
     *  (`111 216 738 Class java/lang/IllegalArgumentException`) and handler 738
     *  does `goto 695`, which builds `X.74c(reason, local7, local7)` with local7
     *  provably false (`iconst_0; istore 7` at offset 102, immediately before
     *  the guarded range) -- i.e. "AppManager not installed, do not show the
     *  preload Terms screen". Because we returned 0 instead of throwing, the app
     *  fell through to a SIM check, found SIM_STATE_ABSENT (honest, and it stays)
     *  and built `X.74c(FALLBACK_V13_NO_SIM, true, true)` -- opening
     *  `com.bloks.www.caa.login.oxygen_preloads_terms_of_service`, Meta's
     *  OEM-preload Terms template, which a Play-Store device never shows. That
     *  screen's CTA renders in a loading state that never resolves.
     *
     *  Installed-ness is decided by getPackageInfo rather than a second,
     *  parallel rule, so the registry consult and package-visibility filtering
     *  it already implements apply here unchanged and the two APIs cannot
     *  disagree about what is installed.
     *
     *  Env rollback for A/B: MACAPK_PM_COMPONENT_STRICT=0 restores the old
     *  always-DEFAULT answer. */
    @Override public int getComponentEnabledSetting(ComponentName componentName) {
        if (componentName == null) return COMPONENT_ENABLED_STATE_DEFAULT;
        final String pkg = componentName.getPackageName();
        // Our own package: the stored value, exactly as before. This is the
        // overwhelmingly common call and its behaviour is unchanged.
        if (pkg == null || pkg.equals(this.packageName)) {
            String cls = componentName.getClassName();
            Integer stored = (cls != null) ? componentEnabledState.get(cls) : null;
            return (stored != null) ? stored : COMPONENT_ENABLED_STATE_DEFAULT;
        }
        if ("0".equals(System.getenv("MACAPK_PM_COMPONENT_STRICT"))) {
            String cls = componentName.getClassName();
            Integer stored = (cls != null) ? componentEnabledState.get(cls) : null;
            return (stored != null) ? stored : COMPONENT_ENABLED_STATE_DEFAULT;
        }
        try {
            getPackageInfo(pkg, 0);
        } catch (NameNotFoundException notInstalled) {
            // AOSP's message shape, because an app may log or match on it.
            throw new IllegalArgumentException("Unknown component: " + componentName);
        }
        // The package IS installed (self, or a registry row we can see). We hold
        // no per-component override for it, so DEFAULT is the truthful answer.
        String cls = componentName.getClassName();
        Integer stored = (cls != null) ? componentEnabledState.get(cls) : null;
        return (stored != null) ? stored : COMPONENT_ENABLED_STATE_DEFAULT;
    }

    /** AOSP API-33 batched form. Same store, so the batched and single-shot
     *  APIs cannot disagree. */
    @Override public void setComponentEnabledSettings(
            java.util.List<ComponentEnabledSetting> settings) {
        if (settings == null) return;
        for (ComponentEnabledSetting s : settings) {
            if (s == null) continue;
            setComponentEnabledSetting(s.getComponentName(),
                                       s.getEnabledState(), s.getEnabledFlags());
        }
    }

    /** Package-level half. Unlike the component path above, AOSP stores ANY of
     *  the five legal values here (setEnabledSettingInternalLocked:4088,
     *  `pkgSetting.setEnabled(newState, userId, callingPackage)` with no
     *  switch), and the validator it shares with the component form throws with
     *  the "Invalid new component state" wording for both. */
    @Override public void setApplicationEnabledSetting(String packageName,
                                                       int newState, int flags) {
        switch (newState) {
            case COMPONENT_ENABLED_STATE_DEFAULT:
            case COMPONENT_ENABLED_STATE_ENABLED:
            case COMPONENT_ENABLED_STATE_DISABLED:
            case COMPONENT_ENABLED_STATE_DISABLED_USER:
            case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid new component state: " + newState);
        }
        // Only our own package exists here; a call naming any other package is
        // the same "not installed" answer every other query gives.
        if (packageName != null && !packageName.equals(this.packageName)) return;
        applicationEnabledSetting = newState;
    }

    @Override public int getApplicationEnabledSetting(String packageName) {
        if (packageName != null && !packageName.equals(this.packageName)) {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
        return applicationEnabledSetting;
    }

    private android.content.pm.ActivityInfo componentInfo(
            ComponentName component, int flags, boolean receiver)
            throws android.content.pm.PackageManager.NameNotFoundException {
        String cls = (component != null) ? component.getClassName() : null;
        // Stage 1: a component of ANOTHER installed Mac.apk app answers from
        // THAT app's manifest (the class name alone used to decide, so
        // (other.pkg, X) resolved against OUR manifest). Visible packages only,
        // as a query is; an undeclared class there is NameNotFoundException.
        if (component != null && !packageName.equals(component.getPackageName())
                && macapkRegistryActivities() && packageVisible(component.getPackageName())) {
            InstalledRegistry.Row row = InstalledRegistry.lookup(component.getPackageName());
            Manifest rm = registryManifest(row);
            if (rm != null) {
                Manifest.ActivityEntry re = receiver ? rm.receivers.get(cls) : rm.activities.get(cls);
                if (re == null || (!re.enabled && (flags
                        & android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS) == 0)) {
                    throw new android.content.pm.PackageManager.NameNotFoundException(
                        String.valueOf(component.flattenToShortString()));
                }
                return registryActivityInfo(row, re, flags);
            }
        }
        Manifest.ActivityEntry e = activityEntry(cls, receiver);
        if (e == null) {
            // ⚠ v1.0.938: an UNDECLARED component is NameNotFoundException, which
            // is AOSP's only failure mode here (`@NonNull` + `@throws`, and our
            // own .service/.provider siblings already threw -- these two were
            // the inconsistent pair).
            //
            // ONLY when we actually have a manifest to have looked in. With no
            // manifest parsed we cannot distinguish "absent" from "unknown", and
            // the historical permissive stub is the safer answer there.
            if (manifest() != null) {
                throw new android.content.pm.PackageManager.NameNotFoundException(
                    String.valueOf(cls));
            }
            return super.getActivityInfo(component, flags);
        }
        // AOSP: all four component getters return null -> NameNotFoundException
        // for a DISABLED component unless the caller passed
        // MATCH_DISABLED_COMPONENTS (ComputerEngine's isEnabledAndMatches
        // guards; PackageInfoUtils.java:283,303,319,336). The flag is honoured
        // inside macapkIsComponentEnabled, which is what keeps the
        // metadata-holder pattern working -- see getServiceInfo's header.
        if (macapkComponentGating()
                && !macapkIsComponentEnabled(e.name, e.enabled, flags)) {
            throw new android.content.pm.PackageManager.NameNotFoundException(
                String.valueOf(cls));
        }
        return toActivityInfo(e, flags);
    }

    private android.content.pm.ActivityInfo toActivityInfo(
            Manifest.ActivityEntry e, int flags) {
        android.content.pm.ActivityInfo info =
            new android.content.pm.ActivityInfo();
        info.name        = e.name;
        info.packageName = this.packageName;
        info.processName = e.processName != null ? e.processName : this.packageName;
        info.applicationInfo = makeAppInfo(flags);
        info.enabled     = e.enabled;
        info.exported    = e.exported;
        info.directBootAware = e.directBootAware;
        info.parentActivityName = e.parentActivityName;
        info.targetActivity = e.targetActivity;
        info.permission  = e.permission;
        info.taskAffinity = e.taskAffinity != null ? e.taskAffinity : this.packageName;
        info.launchMode  = e.launchMode;
        info.documentLaunchMode = e.documentLaunchMode;
        info.screenOrientation  = e.screenOrientation;
        // AOSP PackageParser.getActivityConfigChanges:
        //     return configChanges | ((~recreateOnConfigChanges)
        //                             & (CONFIG_MCC | CONFIG_MNC));
        // i.e. an activity that does NOT opt into being recreated on an
        // MCC/MNC change (nobody does -- android:recreateOnConfigChanges is a
        // compat shim for pre-Honeycomb behaviour) implicitly HANDLES those two
        // config changes. So the floor for every activity is 0x3, not 0. An app
        // that reads configChanges to decide whether it must save state across a
        // configuration change was reading a value 2 bits short.
        info.configChanges = e.configChanges
            | android.content.pm.ActivityInfo.CONFIG_MCC
            | android.content.pm.ActivityInfo.CONFIG_MNC;
        info.softInputMode = e.softInputMode;
        info.uiOptions     = e.uiOptions;
        // AOSP: `ai.flags |= FLAG_HARDWARE_ACCELERATED` when the activity (or,
        // by inheritance, the application) is hardware accelerated. Activities
        // inherit <application android:hardwareAccelerated>, whose default is
        // true from API 14, so nearly every activity carries this bit; ours
        // carried an all-zero flags word.
        if ((applicationFlags() & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
            info.flags |= android.content.pm.ActivityInfo.FLAG_HARDWARE_ACCELERATED;
        }
        // AOSP: `a.info.maxRecents = sa.getInt(..._maxRecents,
        // ActivityTaskManager.getDefaultAppRecentsLimitStatic())`, and that is
        // `getMaxRecentTasksStatic() / 6` where the max is 36 on a low-RAM
        // device and 48 otherwise (android-11.0.0_r48
        // core/java/android/app/ActivityTaskManager.java:251-264) -- so 6 or 8.
        // Derived rather than hardcoded so it tracks the same low-RAM answer
        // ActivityManager gives, instead of becoming a second opinion.
        info.maxRecents = defaultAppRecentsLimit();
        info.theme = resolveResRef(e.themeRaw, "style");
        info.icon  = resolveResRef(e.iconRaw,  "drawable");
        info.logo  = resolveResRef(e.logoRaw,  "drawable");
        // AOSP PackageItemInfo: android:label is EITHER a resource reference
        // (labelRes) or a literal string (nonLocalizedLabel), never both.
        if (e.labelRaw != null) {
            if (e.labelRaw.startsWith("@")) {
                info.labelRes = resolveResRef(e.labelRaw, "string");
            } else {
                info.nonLocalizedLabel = e.labelRaw;
            }
        }
        if ((flags & GET_META_DATA) != 0 && !e.metaData.isEmpty()) {
            android.os.Bundle md = new android.os.Bundle();
            for (Map.Entry<String, String> me : e.metaData.entrySet()) {
                putTypedMeta(md, me.getKey(), me.getValue(),
                             e.metaDataTypes.get(me.getKey()));
            }
            info.metaData = md;
        }
        return info;
    }

    /** Resolve a raw manifest resource token to a numeric id.
     *  BinaryXmlParser renders a TYPE_REFERENCE as "@0x7f130123"; the apktool
     *  text decode emits "@style/NoActionBar.Gameplay" or
     *  "@android:style/Theme.NoDisplay". Returns 0 when unresolvable, which is
     *  AOSP's "no resource" value for every one of these fields.
     *  Static (it reads only the global resource index) so Boot can resolve the
     *  launch activity's manifest theme for the starting window before any
     *  Application or PackageManager instance exists. */
    static int resolveResRef(String raw, String defType) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '@') return 0;
        String body = raw.substring(1);
        if (body.startsWith("0x") || body.startsWith("0X")) {
            try { return (int) Long.parseLong(body.substring(2), 16); }
            catch (NumberFormatException ignored) { return 0; }
        }
        int slash = body.indexOf('/');
        String type = defType;
        String name = body;
        if (slash >= 0) {
            type = body.substring(0, slash);
            name = body.substring(slash + 1);
            int colon = type.indexOf(':');
            if (colon >= 0) {
                if ("android".equals(type.substring(0, colon))) {
                    // "@android:style/X" -- a FRAMEWORK resource. For most
                    // types we still have no framework resource table on the
                    // default path, so 0 (AOSP's "no resource") remains the
                    // only honest answer and the callers already treat it as
                    // "use the default".
                    //
                    // STYLES ARE THE EXCEPTION, and pretending otherwise had a
                    // cost: AOSP's public style ids are FROZEN, so a platform
                    // THEME does have an honest id, and returning 0 for it made
                    // ActivityHost skip `if (theme != 0) setTheme(theme)`
                    // entirely -- an activity whose only theme declaration is
                    // `@android:style/Theme.NoTitleBar.Fullscreen` ran with NO
                    // theme, and therefore never produced FLAG_FULLSCREEN.
                    // ResourceIndex owns the id table (one table, both
                    // directions) and answers 0 for anything it cannot name, so
                    // this strictly widens what resolves and never invents an id.
                    //
                    // This is the apktool TEXT-decode spelling. The BINARY
                    // manifest -- Manifest.parse's preferred path -- emits the
                    // same theme as a raw "@0x01030007" and is handled by the
                    // hex branch above plus ResourceIndex.macapkTypeNameOf.
                    if ("style".equals(type.substring(colon + 1))) {
                        Object fidx = android.content.res.Resources
                            .macapkGetResourceIndex();
                        if (fidx instanceof ResourceIndex) {
                            return ((ResourceIndex) fidx)
                                .idOfFrameworkStyleName(name);
                        }
                    }
                    return 0;
                }
                type = type.substring(colon + 1);
            }
        }
        Object idx = android.content.res.Resources.macapkGetResourceIndex();
        if (idx instanceof ResourceIndex) {
            return ((ResourceIndex) idx).idOfName(name, type, null);
        }
        return 0;
    }

    // ================= INTENT RESOLUTION: package scope, selector, matchData
    // ================= (wave 5, 2026-09-26)
    //
    // Five separable AOSP rules that queryIntentActivities /
    // queryBroadcastReceivers / resolveActivity did not apply (package scope,
    // selector, matchData, <data> resource references, the provider-resolved
    // type of an untyped content: intent). Each has its own
    // default-ON bisect gate in the same `!"0".equals(getenv(...))` idiom as
    // MACAPK_COMPONENT_GATING, and each gate's "0" arm restores the exact
    // pre-change code path, so a regression can be pinned to one rule in one
    // run. Evidence (the app's own detector run offline against this class),
    // the AOSP android14-release citations and the fleet blast radius:
    // docs/telegram-emudetector-device-surface_2026-09-26.md and
    // docs/pm-intent-resolution-scope-and-matchdata_2026-09-26.md. Offline gate
    // with positive controls: debug/w5pmintents_gate.sh.

    /** MACAPK_PM_INTENT_PKG_SCOPE (default ON). A package-scoped intent
     *  (`setPackage(p)`) resolves against package p's components ONLY, and an
     *  explicit component resolves only when its package is installed:
     *
     *    ComputerEngine.queryIntentActivitiesInternal (android14-release
     *    services/core/java/com/android/server/pm/ComputerEngine.java)
     *      :509  final String pkgName = intent.getPackage();
     *      :527  final ActivityInfo ai = getActivityInfo(comp, flags, userId);
     *      :748  if (pkgName == null) { ...every package... } else {
     *      :775      getPackageStateInternal(pkgName, ...)  -> null when absent
     *      :780      ...setting.getAndroidPackage().getActivities()...
     *    ResolveIntentHelper.queryIntentReceiversInternal :424-477, same shape.
     *
     *  We ignored both, so `getLaunchIntentForPackage(<ANY package>)` resolved
     *  to OUR launcher: Telegram's EmuDetector.checkPackageName read that as
     *  "BlueStacks / Nox / Genymotion is installed" and picked its emulator
     *  code paths, and every "is app X installed / open app X" check in the
     *  fleet (31 of 60 corpus apps call getLaunchIntentForPackage) was handed
     *  an Intent that relaunches US. queryIntentServices already scoped this
     *  way (see its `want` guard); activities and receivers never got it. */
    static boolean macapkIntentPkgScope() {
        return !"0".equals(System.getenv("MACAPK_PM_INTENT_PKG_SCOPE"));
    }

    /** MACAPK_PM_INTENT_SELECTOR (default ON). A component-less intent that
     *  carries a selector is RESOLVED through the selector (the outer intent is
     *  only the payload): ComputerEngine.java:510-518 and
     *  ResolveIntentHelper.java:413-421 both do
     *      if (comp == null && intent.getSelector() != null) {
     *          intent = intent.getSelector(); comp = intent.getComponent(); }
     *  Without it `Intent.makeMainSelectorActivity(MAIN, CATEGORY_APP_BROWSER)`
     *  matched OUR MAIN/LAUNCHER activity (its outer intent is MAIN+LAUNCHER)
     *  wherever the caller did not pass MATCH_DEFAULT_ONLY.
     *
     *  ⚠ ONLY the intent is swapped, NOT the type. `resolvedType` arrives as a
     *  separate argument, computed by the CALLER from the intent as the app
     *  built it -- ApplicationPackageManager.java:1430 (activities) and :1542
     *  (receivers) pass `intent.resolveTypeIfNeeded(...)` for the OUTER intent
     *  -- and the swap above leaves it untouched on its way into
     *  queryActivities / queryReceivers (ComputerEngine :780,
     *  ResolveIntentHelper :469/:477). So the selector contributes action,
     *  data and categories, and the OUTER intent contributes the type: a SEND
     *  of message/rfc822 whose selector is SENDTO mailto: matches only a
     *  mailto filter that ALSO declares message/rfc822 (a type-less filter is
     *  NO_MATCH_TYPE, IntentFilter.java:1718-1722). See macapkResolvedType. */
    static boolean macapkIntentSelector() {
        return !"0".equals(System.getenv("MACAPK_PM_INTENT_SELECTOR"));
    }

    /** MACAPK_PM_INTENT_RESOLVE_TYPE (default ON; only consulted on the
     *  MACAPK_PM_INTENT_MATCH_DATA path, so MATCH_DATA=0 also restores the bare
     *  getType()). The MIME type an untyped `content:` intent is matched with is
     *  the one its PROVIDER reports, exactly as on a device:
     *  ApplicationPackageManager passes
     *  `intent.resolveTypeIfNeeded(mContext.getContentResolver())`, and
     *  Intent.resolveType (Intent.java:8791-8801) is
     *      if (mType != null) return mType;
     *      if (mData != null && "content".equals(mData.getScheme()))
     *          return resolver.getType(mData);
     *      return null;
     *  Without it, MATCH_DATA's type rule made an untyped `VIEW content://...`
     *  miss every type-only filter (the legacy matcher reached the same
     *  filters only by skipping the type test altogether). The lookup runs the
     *  app's own ContentProvider.getType, which is why it has a gate of its
     *  own: a provider that misbehaves when asked is a different failure from
     *  a wrong matching rule, and bisects separately. */
    static boolean macapkIntentResolveType() {
        return !"0".equals(System.getenv("MACAPK_PM_INTENT_RESOLVE_TYPE"));
    }

    /** The `resolvedType` AOSP matches with: ALWAYS from the intent as the app
     *  passed it (the OUTER intent when a selector is resolved through; see
     *  macapkIntentSelector), provider-resolved for an untyped content: Uri
     *  (macapkIntentResolveType). Computed once per query, not per filter.
     *
     *  The ContentResolver is the running Application's (the one every Context
     *  in the process delegates to). Before the Application exists, or when
     *  the lookup throws, the answer falls back to getType() -- the pre-change
     *  answer, never a new wrong one. ContentResolver.getType already swallows
     *  an Exception from the provider the way AOSP's does (:946-948). */
    private static String macapkResolvedType(Intent outer) {
        String t = outer.getType();
        if (t != null || !macapkIntentMatchData() || !macapkIntentResolveType()) return t;
        try {
            android.net.Uri d = outer.getData();
            if (d == null || !"content".equals(d.getScheme())) return null;
            android.app.Application app = android.app.Application.macapk_get();
            android.content.ContentResolver cr = app != null ? app.getContentResolver() : null;
            return cr != null ? outer.resolveTypeIfNeeded(cr) : null;
        } catch (Throwable ignored) {
            return t;
        }
    }

    /** MACAPK_PM_INTENT_MATCH_DATA (default ON). The data half of AOSP's
     *  IntentFilter.match -- matchData, findMimeType -- plus IntentResolver's
     *  candidate cut. See aospBestMatchPriority / aospCandidateCut for the
     *  quoted rules. The previous matcher skipped the scheme test whenever the INTENT
     *  had no scheme and skipped the type test whenever the FILTER had no
     *  types, so e.g. a bare `ACTION_VIEW` + `application/pdf` probe matched
     *  an app's own `myapp://` deep-link activity, and a categories-only or
     *  completely empty-of-data intent could still select filters. Shared by
     *  queryIntentServices (one matcher, deliberately -- see matchesAnyFilter). */
    static boolean macapkIntentMatchData() {
        return !"0".equals(System.getenv("MACAPK_PM_INTENT_MATCH_DATA"));
    }

    /** The intent AOSP actually matches filters against: the selector when the
     *  intent has no component and carries one (MACAPK_PM_INTENT_SELECTOR),
     *  else the intent itself. */
    private static Intent macapkResolutionIntent(Intent intent) {
        if (macapkIntentSelector() && intent.getComponent() == null) {
            Intent sel = intent.getSelector();
            if (sel != null) return sel;
        }
        return intent;
    }

    /** AOSP PackageManager.queryIntentActivities: every activity in the
     *  package whose <intent-filter> matches the intent, as ResolveInfos in
     *  IntentResolver's order (MACAPK_PM_RESOLVE_ORDER: priority, capped at 0
     *  for our non-privileged app by MACAPK_PM_ACT_PRIORITY_CLAMP, then
     *  isDefault, then match; package-scan order with that gate off), followed
     *  by the host rows. Used to answer "is there anything that can handle
     *  this", by Intent.resolveActivity (Activity.navigateUpTo's guard),
     *  and by androidx TaskStackBuilder.addNextIntentWithParentStack when the
     *  next intent has no explicit component.
     *
     *  Only OUR package can ever match -- Mac.apk hosts exactly one app, so a
     *  device with no other packages installed is the faithful model, and the
     *  old unconditional empty list was only correct in the sense that a
     *  device with zero installed apps returns nothing. ⚠ "Only our package can
     *  match" is NOT "our package matches everything": an intent pinned to
     *  ANOTHER package, or naming another package's component, resolves to
     *  nothing, exactly as it would on a device where that package is absent
     *  (MACAPK_PM_INTENT_PKG_SCOPE, header above).
     *
     *  Matching mirrors AOSP IntentFilter.match(): the action must be listed
     *  (or the intent must carry no action), every category on the intent must
     *  be listed by the filter, and the data/type rules of matchData hold (see
     *  bestMatchPriority). Disabled components are skipped, the same way
     *  PackageManagerService omits them (this is the same rule findLaunchable
     *  already applies for the launcher activity). */
    @Override public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        Manifest m = manifest();
        if (m == null || intent == null) return Collections.emptyList();
        java.util.List<ResolveInfo> out = new java.util.ArrayList<>();
        // ComputerEngine.java:509 reads the package restriction from the intent
        // AS PASSED, before :510-518 swaps in the selector -- so for ACTIVITIES
        // an outer setPackage still scopes a selector query. (Receivers read it
        // from the selector instead; see queryBroadcastReceivers.)
        final boolean scope = macapkIntentPkgScope();
        final String want = scope ? intent.getPackage() : null;
        final Intent query = macapkResolutionIntent(intent);
        // com.google.android.gms's Google sign-in activity (GoogleSignInBroker):
        // answered before the per-package scans because neither of them knows
        // the GMS package. MACAPK_GMS_SIGNIN=0 -> null -> the pre-provider path.
        ResolveInfo gsi = macapkResolveGmsSignInActivity(query, want);
        if (gsi != null) {
            out.add(gsi);
            return out;
        }
        // The type comes from the intent AS PASSED even when `query` is its
        // selector (see macapkIntentSelector).
        final String resolvedType = macapkResolvedType(intent);
        ComponentName explicit = query.getComponent();
        if (scope && explicit != null && !packageName.equals(explicit.getPackageName())) {
            // getActivityInfo(comp) for a package that is not installed is null
            // (ComputerEngine.java:527-528), so the list stays empty. The class
            // name alone used to decide, and ("com.other", <our class>) resolved
            // to us. Stage 1: an INSTALLED Mac.apk app's component resolves from
            // that app's own manifest (visible packages only).
            if (macapkRegistryActivities() && packageVisible(explicit.getPackageName())) {
                registryScan(explicit.getPackageName(), query, resolvedType, flags, explicit,
                        false, false, false, false, out, null);
            }
            return out;
        }
        // The explicit branch (:526-581) never reads pkgName, so an explicit
        // component in OUR package resolves whatever the intent's package says.
        final boolean scanOwn = explicit != null || want == null || want.equals(packageName);
        // MACAPK_PM_RESOLVE_ORDER: rows in IntentResolver's order, with the
        // credited filter's priority / match / isDefault (see the gate). The
        // per-package scan is taken when the intent AS PASSED names a package,
        // the same :509 read as `want` above (but independent of the scope gate:
        // it is a property of the intent, not of our answer).
        final boolean order = explicit == null && macapkResolveOrder() && macapkIntentMatchData();
        final boolean defaultOnly =
                (flags & android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != 0;
        final boolean perPackage = intent.getPackage() != null;
        final boolean clamp = order && macapkActivityPriorityClamp();
        final java.util.List<Integer> cuts = order ? new java.util.ArrayList<>() : null;
        for (Manifest.ActivityEntry e : (scanOwn ? m.activities.values()
                : java.util.Collections.<Manifest.ActivityEntry>emptyList())) {
            // Was `if (!e.enabled) continue;`. Same rule, now via the ONE AOSP
            // decision function, which additionally honours
            // MATCH_DISABLED_COMPONENTS and the setComponentEnabledSetting
            // override. LOAD-BEARING: 249 activity-alias declarations across 9
            // corpus apps (Reddit, Signal, Telegram, Duolingo, Fossify Gallery,
            // Wikipedia) are enabled=false launcher aliases; this must keep
            // filtering them out.
            if (!macapkIsComponentEnabled(e.name, e.enabled, flags)) continue;
            int[] credited = null;
            if (explicit != null) {
                // AOSP: an explicit intent resolves to exactly that component,
                // filters are not consulted.
                if (!e.name.equals(explicit.getClassName())) continue;
            } else if (order) {
                credited = aospCreditedFilter(e.filters, query, resolvedType, defaultOnly,
                        perPackage, clamp);
                if (credited == null) continue;
            } else if (!matchesAnyFilter(e, query, resolvedType, flags)) {
                continue;
            }
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = toActivityInfo(e, flags);
            if (credited != null) {
                ri.priority = credited[0];
                ri.match = credited[1];
                ri.isDefault = credited[2] != 0;
                cuts.add(credited[3]);
            }
            out.add(ri);
            if (explicit != null) break;
        }
        // Stage 1: a query scoped to ANOTHER installed Mac.apk app (setPackage,
        // getLaunchIntentForPackage) resolves against that app's own manifest,
        // credited and cut by the same code as the own scan above.
        if (explicit == null && want != null && !want.equals(packageName)
                && macapkRegistryActivities() && packageVisible(want)) {
            registryScan(want, query, resolvedType, flags, null, order, defaultOnly,
                    perPackage, clamp, out, cuts);
        }
        // Stage 4 (MACAPK_XAPP_IMPLICIT, docs/cross-app-launch-and-implicit-start-design_2026-09-26.md
        // section 9): an UNSCOPED implicit query also scans every installed Mac.apk
        // app's manifest, as ComputerEngine.queryIntentActivitiesInternal unions all
        // installed packages. A query is narrowed by package visibility
        // (shouldFilterApplication); a START is not (ComputerEngine :561-569, :777),
        // which macapkQueryForStart marks. Web intents never take a registry row
        // unless setPackage names the app: Android 12+ opens an unverified web link
        // in the browser and we never verify Digital Asset Links (CDD 3.2.3.2 C-0-4).
        // This is what a launcher or a game frontend's MAIN + LAUNCHER query, and an
        // ACTION_VIEW of a file type, need to see the other installed apps.
        if (explicit == null && intent.getPackage() == null && macapkXappImplicit()
                && macapkRegistryActivities() && !ActivityHost.macapkWebIntent(query)) {
            final boolean forStart = FOR_START.get();
            for (InstalledRegistry.Row r : InstalledRegistry.allBest()) {
                if (r.pkg.equals(packageName)) continue;
                if (!forStart && !packageVisible(r.pkg)) continue;
                registryScan(r.pkg, query, resolvedType, flags, null, order, defaultOnly,
                        perPackage, clamp, out, cuts);
            }
        }
        if (order) aospSortResults(out, cuts);
        // ⚠ v1.0.938: THE HOST HANDOFF. Mac.apk already routes some implicit
        // intents to macOS -- ACTION_VIEW of an http/https/geo/mailto/tel URI,
        // ACTION_SENDTO mailto:, ACTION_DIAL/CALL tel:, ACTION_SEND of text --
        // through ActivityHost -> NSWorkspace. But this method only ever scanned
        // the app's own manifest, so we could PERFORM those intents while
        // reporting that nothing could handle them. The near-universal guard
        //     if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
        // took the "no app can handle this" branch, and share buttons, "open in
        // browser", "email us" and "call" were silently dead FLEET-WIDE even
        // though the handoff behind them worked.
        //
        // Three design points, each of which matters:
        //  - the predicate is ActivityHost.hostActionFor, the SAME one the start
        //    path uses. Two copies of "can the host handle this?" is how the
        //    inconsistency arose in the first place. It answers on action /
        //    type / data only -- never on extras, which is what AOSP's
        //    IntentFilter.match can see (v1.0.940; see that method's header).
        //  - the pseudo-activity's package is deliberately NOT ours, so
        //    `resolveActivity(...).activityInfo.packageName.equals(getPackageName())`
        //    stays false the way it does on a device where the browser is a
        //    separate app. Several apps branch on exactly that.
        //  - the anti-hijack GRACE WINDOW (ActivityHost, v1.0.849) deliberately
        //    does NOT gate this answer, only the actual start. Android behaves
        //    the same way: resolveActivity still names the browser even when a
        //    background activity start would be blocked. Gating resolution too
        //    would make the answer flicker with recent input, which is worse
        //    than either consistent answer.
        // ⚠ APPEND, do not gate on out.isEmpty().
        //
        // The old `out.isEmpty()` condition suppressed the host row whenever the
        // APP ITSELF had a matching activity -- and measured 2026-08-24, Aurora
        // Store, F-Droid and Telegram all declare activity filters that match a
        // bare ACTION_VIEW browser probe under our matcher. So exactly the apps
        // that need to open a browser were the ones told none exists: they got
        // back only their own authority-bearing deep-link row.
        //
        // A device behaves the way this now does -- the browser is listed
        // ALONGSIDE any app that also handles the intent, and the caller picks.
        // AppAuth (Thunderbird, and every OAuth sign-in) walks the whole list
        // looking for a row with NO authority; suppressing ours removed the only
        // row that could qualify.
        //
        // The host rows are appended AFTER the app rows even though
        // MACAPK_PM_RESOLVE_ORDER sorts those: on a device they would take part
        // in RESOLVE_PRIORITY_SORTER, and a system browser wins a tie on
        // `system`, but whether the app's own row would tie depends on the
        // `<data android:host>` match category, which Manifest does not parse yet
        // (docs/pm-intent-resolution-scope-and-matchdata_2026-09-26.md
        // section 3). Sorting them in before that lands would hand resolveActivity
        // to the browser for every app with an https deep link. Tracked in
        // docs/pm-service-selector-and-resolver-order_2026-09-26.md.
        //
        // ⚠ The host rows are OTHER PACKAGES (com.macapk.host,
        // com.android.settings), so MACAPK_PM_INTENT_PKG_SCOPE applies to them
        // too: a row is listed only when the intent is unscoped or pinned to that
        // row's own package. An intent pinned to OUR package gets only our
        // activities, as on a device (an app-link probe scoped to self must not
        // come back naming the browser); one pinned to com.android.settings
        // still reaches the Settings rows; one pinned to an absent browser
        // (`setPackage("com.android.chrome")`) resolves to nothing, which is the
        // honest answer when Chrome is not installed. The START path
        // (ActivityHost.resolveImplicitToHost) is untouched by this.
        //
        // ⚠ Resolved THROUGH A SELECTOR (MACAPK_PM_INTENT_SELECTOR), the host
        // predicate sees what AOSP's matcher sees: the selector's action, data
        // and categories with the OUTER intent's resolvedType. That alone makes
        // the typed host kinds exact (SEND text/*, the installer and the two
        // Settings predicates all test the type themselves). OPEN_URL is the
        // one kind whose predicate ignores the type, and the filter its row
        // advertises is precisely the TYPE-LESS scheme filter a browser / mail
        // app / dialer declares for it (see macapkHostFilterFor), so in the
        // selector case that advertised filter must also match: a typed outer
        // (SEND message/rfc822 + selector SENDTO mailto:) is NO_MATCH_TYPE
        // against it, as it is against a real mail app's SENDTO filter. The
        // un-selected path is byte-identical to before -- it is the start
        // path's predicate, and the two must not drift.
        if (explicit == null) {
            final boolean viaSelector = query != intent;
            Intent hostQuery = query;
            if (viaSelector) {
                hostQuery = new Intent(query);
                hostQuery.setDataAndType(query.getData(), resolvedType);
            }
            ActivityHost.HostAction act = ActivityHost.hostActionFor(hostQuery);
            if (act != null) {
                // Stage S1: Settings' special-access screens resolve to AOSP's
                // own Settings components (com.android.settings/.Settings$...),
                // not to the macOS host row -- see SpecialAccessHandler.
                // Stage S5: App info resolves to AOSP's InstalledAppDetails
                // alias (com.android.settings/.applications.InstalledAppDetails).
                ResolveInfo row = act.kind == ActivityHost.HostAction.Kind.SPECIAL_ACCESS
                        ? SpecialAccessHandler.settingsResolveInfo(hostQuery)
                        : act.kind == ActivityHost.HostAction.Kind.APP_DETAILS
                        ? SpecialAccessHandler.appDetailsResolveInfo()
                        : act.kind == ActivityHost.HostAction.Kind.DOCUMENT_PICKER
                        ? macapkDocumentsUiResolveInfo(hostQuery)
                        : act.kind == ActivityHost.HostAction.Kind.INSTALL_PACKAGE
                                && macapkInstallerComponent()
                        ? macapkInstallerResolveInfo(hostQuery)
                        : macapkHostResolveInfo(hostQuery);
                boolean listed = want == null
                        || (row != null && row.activityInfo != null
                            && want.equals(row.activityInfo.packageName));
                if (listed && viaSelector && act.kind == ActivityHost.HostAction.Kind.OPEN_URL
                        && row != null && row.filter != null) {
                    android.net.Uri d = null;
                    try { d = hostQuery.getData(); } catch (Throwable ignored) {}
                    listed = row.filter.match(hostQuery.getAction(), resolvedType,
                            d != null ? d.getScheme() : null, d,
                            hostQuery.getCategories(), "MacApkPM") >= 0;
                }
                if (listed) out.add(row);
            }
        }
        return out;
    }

    /** The synthetic ResolveInfo standing for "macOS will handle this".
     *  Package name is NOT ours on purpose -- see queryIntentActivities. */
    static final String MACAPK_HOST_PACKAGE = "com.macapk.host";
    static final String MACAPK_HOST_ACTIVITY = "com.macapk.host.HostIntentActivity";
    /** Build the IntentFilter the host row advertises for `intent`.
     *
     *  ⚠ THE ABSENCE OF AN AUTHORITY IS THE LOAD-BEARING PART. AppAuth's
     *  BrowserSelector -- which Thunderbird and every OAuth sign-in runs --
     *  separates a real BROWSER from a deep-link handler with literally
     *  `filter.authoritiesIterator() == null`. A browser matches any host; a
     *  deep-link handler names one. So this filter carries the scheme and
     *  nothing else, and IntentFilter's iterators return null when empty
     *  (AOSP-faithful; see that file's header).
     *
     *  ri.filter was previously never set at all, so every candidate row failed
     *  AppAuth's `filter == null` check first and the loop found no browser. */
    private static android.content.IntentFilter macapkHostFilterFor(Intent intent) {
        android.content.IntentFilter f = new android.content.IntentFilter();
        String action = intent != null ? intent.getAction() : null;
        f.addAction(action != null ? action : Intent.ACTION_VIEW);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        f.addCategory(Intent.CATEGORY_BROWSABLE);
        android.net.Uri data = intent != null ? intent.getData() : null;
        String scheme = data != null ? data.getScheme() : null;
        boolean web = "http".equals(scheme) || "https".equals(scheme);
        if (scheme != null && !scheme.isEmpty() && !(web && macapkHostBrowserSchemes())) {
            f.addDataScheme(scheme);
        } else {
            // A browser's VIEW filter is ONE filter carrying BOTH web schemes,
            // and ri.filter is the whole filter that matched, not the query's
            // scheme. Chromium chrome/android/java/AndroidManifest.xml :281-289
            // (IntentDispatcher): VIEW + DEFAULT + BROWSABLE with data schemes
            // googlechrome, http, https, about in the same <intent-filter>.
            // AppAuth's BrowserSelector.isFullBrowser (openid/AppAuth-Android
            // browser/BrowserSelector.java :182-210) REQUIRES both: "The filter
            // must support both HTTP and HTTPS" -- and it queries with
            // BROWSER_INTENT = VIEW + BROWSABLE + Uri.fromParts("http", "", null),
            // so a row advertising only the queried "http" was never a browser.
            // That was Thunderbird's "OAuth sign in failed -- The app couldn't
            // find a browser" (lanes19 auth-network, 2026-09-29).
            // MACAPK_HOST_BROWSER_SCHEMES=0: the queried scheme only (pre-change).
            f.addDataScheme("http");
            f.addDataScheme("https");
        }
        // Deliberately NO addDataAuthority and NO addDataPath.
        return f;
    }

    /** MACAPK_HOST_BROWSER_SCHEMES (default ON): the host browser row's filter
     *  carries http AND https for a web query, as a real browser's does. */
    static boolean macapkHostBrowserSchemes() {
        return !"0".equals(System.getenv("MACAPK_HOST_BROWSER_SCHEMES"));
    }

    /** DocumentsUI's PickActivity (packages/apps/DocumentsUI, android14): the
     *  row a device lists for GET_CONTENT / OPEN_DOCUMENT / CREATE_DOCUMENT.
     *  Its filter is the picker's own: the action, DEFAULT + OPENABLE, any
     *  type. v1.0.1653, MACAPK_DOC_PICKER. OPEN_DOCUMENT_TREE's filter is the
     *  action and DEFAULT only, no OPENABLE and no type (DocumentsUI
     *  AndroidManifest.xml:85-88); that intent reaches here only with
     *  MACAPK_DOC_TREE on (v1.0.1654). */
    static final String DOCUMENTSUI_PACKAGE = "com.android.documentsui";
    private static ResolveInfo macapkDocumentsUiResolveInfo(Intent intent) {
        ResolveInfo ri = new ResolveInfo();
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(intent != null && intent.getAction() != null ? intent.getAction() : Intent.ACTION_GET_CONTENT);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        if (intent == null || !Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) {
            f.addCategory(Intent.CATEGORY_OPENABLE);
            try { f.addDataType("*/*"); } catch (Throwable ignored) {}
        }
        ri.filter = f;
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.name = "com.android.documentsui.picker.PickActivity";
        ai.packageName = DOCUMENTSUI_PACKAGE;
        ai.exported = true;
        ai.enabled = true;
        android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = DOCUMENTSUI_PACKAGE;
        ai.applicationInfo = app;
        ri.activityInfo = ai;
        return ri;
    }

    private static ResolveInfo macapkHostResolveInfo(Intent intent) {
        ResolveInfo ri = new ResolveInfo();
        ri.filter = macapkHostFilterFor(intent);
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.name = MACAPK_HOST_ACTIVITY;
        ai.packageName = MACAPK_HOST_PACKAGE;
        ai.exported = true;
        ai.enabled = true;
        android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = MACAPK_HOST_PACKAGE;
        ai.applicationInfo = app;
        ri.activityInfo = ai;
        return ri;
    }

    /** The platform package installer as a device lists it. On a Google
     *  device the PackageInstaller app is package
     *  com.google.android.packageinstaller, and the activity that takes
     *  {VIEW, INSTALL_PACKAGE} x {file, content} x
     *  application/vnd.android.package-archive is
     *  com.android.packageinstaller.InstallStart (packages/apps/PackageInstaller
     *  AndroidManifest.xml). Stores pick it out of queryIntentActivities BY
     *  PACKAGE NAME and pin it: the Epic Games Store's AndroidInstaller keeps
     *  the row whose package is com.android.packageinstaller or
     *  com.google.android.packageinstaller and setClassName()s it, and with only
     *  the generic com.macapk.host row the start stayed implicit and went to the
     *  first app row instead (Organic Maps), so the install never ran.
     *  ActivityHost hands an explicit start of this component to the same
     *  installer surface (InstallIntentHandler). MACAPK_PM_INSTALLER_COMPONENT=0
     *  lists the com.macapk.host row instead (pre-change). */
    static final String MACAPK_INSTALLER_PACKAGE = "com.google.android.packageinstaller";
    static final String MACAPK_INSTALLER_ACTIVITY = "com.android.packageinstaller.InstallStart";
    static boolean macapkInstallerComponent() {
        return !"0".equals(System.getenv("MACAPK_PM_INSTALLER_COMPONENT"));
    }
    static boolean macapkIsInstallerPackage(String pkg) {
        return MACAPK_INSTALLER_PACKAGE.equals(pkg) || "com.android.packageinstaller".equals(pkg);
    }
    private static ResolveInfo macapkInstallerResolveInfo(Intent intent) {
        ResolveInfo ri = new ResolveInfo();
        android.content.IntentFilter f = new android.content.IntentFilter();
        String action = intent != null ? intent.getAction() : null;
        f.addAction(action != null ? action : Intent.ACTION_VIEW);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        android.net.Uri data = intent != null ? intent.getData() : null;
        String scheme = data != null ? data.getScheme() : null;
        if (scheme != null && !scheme.isEmpty()) f.addDataScheme(scheme);
        try {
            f.addDataType("application/vnd.android.package-archive");
        } catch (Throwable ignored) {
        }
        ri.filter = f;
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.name = MACAPK_INSTALLER_ACTIVITY;
        ai.packageName = MACAPK_INSTALLER_PACKAGE;
        ai.exported = true;
        ai.enabled = true;
        android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
        app.packageName = MACAPK_INSTALLER_PACKAGE;
        ai.applicationInfo = app;
        ri.activityInfo = ai;
        return ri;
    }

    /** AOSP resolveActivity: the best match, or null. "Best" is the first row
     *  of queryIntentActivities, which MACAPK_PM_RESOLVE_ORDER puts in
     *  IntentResolver's order (first in manifest order with that gate off).
     *  Still NOT modelled: AOSP's chooseBestActivity answers ResolverActivity
     *  (the chooser) when the top rows tie, rather than the first of them. */
    @Override public ResolveInfo resolveActivity(Intent intent, int flags) {
        List<ResolveInfo> all = queryIntentActivities(intent, flags);
        return all.isEmpty() ? null : all.get(0);
    }

    /** AOSP PackageManager.queryBroadcastReceivers: every `<receiver>` whose
     *  `<intent-filter>` matches, in IntentResolver's order
     *  (MACAPK_PM_RESOLVE_ORDER: priority, uncapped for receivers, then
     *  isDefault, then match; manifest order with that gate off). Same shape and the same
     *  matcher as queryIntentActivities above -- receivers are a SEPARATE
     *  component namespace but AOSP models them with ActivityInfo too, which is
     *  why they share ActivityEntry / toActivityInfo here.
     *
     *  v1.0.997: the base PackageManager returned an unconditional empty list,
     *  so Context.sendBroadcast had nothing to resolve a component-less intent
     *  against and could only ever reach DYNAMIC receivers. On a device an
     *  implicit broadcast reaches both lists (AMS.broadcastIntentLocked merges
     *  collectReceiverComponents' manifest hits with the registered ones), so a
     *  store declaring its package-event receiver in the manifest -- which
     *  ACTION_MY_PACKAGE_REPLACED effectively requires -- heard nothing at all.
     *
     *  No host-handoff fallback here, deliberately: queryIntentActivities adds a
     *  synthetic "macOS will handle this" row because an app ASKS whether
     *  something can handle an intent before starting it. Nothing analogous
     *  exists for broadcasts -- macOS cannot receive one -- and inventing a row
     *  would make `queryBroadcastReceivers(...).isEmpty()` lie. */
    @Override public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        Manifest m = manifest();
        if (m == null || intent == null) return Collections.emptyList();
        java.util.List<ResolveInfo> out = new java.util.ArrayList<>();
        // ResolveIntentHelper.queryIntentReceiversInternal (android14-release
        // :413-421) swaps in the selector FIRST and only then reads
        // `String pkgName = intent.getPackage();` (:466) -- so, unlike
        // activities, a receiver query is scoped by the SELECTOR's package.
        final Intent query = macapkResolutionIntent(intent);
        // ...but the TYPE is still the outer intent's: ApplicationPackageManager
        // .java:1542 resolves it before the swap, and ResolveIntentHelper
        // :469/:477 pass it on unchanged.
        final String resolvedType = macapkResolvedType(intent);
        ComponentName explicit = query.getComponent();
        if (macapkIntentPkgScope()) {
            if (explicit != null) {
                // computer.getReceiverInfo(comp, ...) (:425): null for a
                // package that is not installed.
                if (!packageName.equals(explicit.getPackageName())) return out;
            } else {
                // :466-477: a named package contributes ONLY its own receivers
                // (pkg.getReceivers()); an absent one contributes nothing.
                String want = query.getPackage();
                if (want != null && !want.equals(packageName)) return out;
            }
        }
        // MACAPK_PM_RESOLVE_ORDER: IntentResolver's order, which for receivers
        // is the DELIVERY order (Context.macapkDeliverToMatchingManifestReceivers
        // walks this list; AMS walks it for an ordered broadcast). No priority
        // cap: adjustPriority is for activities only (macapkActivityPriorityClamp),
        // so a receiver's android:priority is honoured whatever its sign. The
        // per-package scan is the SELECTOR's package here (:466 reads it after
        // the swap), the same `query` the scope check above reads.
        final boolean order = explicit == null && macapkResolveOrder() && macapkIntentMatchData();
        final boolean defaultOnly =
                (flags & android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != 0;
        final boolean perPackage = query.getPackage() != null;
        final java.util.List<Integer> cuts = order ? new java.util.ArrayList<>() : null;
        for (Manifest.ActivityEntry e : m.receivers.values()) {
            if (!macapkIsComponentEnabled(e.name, e.enabled, flags)) continue;
            int[] credited = null;
            if (explicit != null) {
                if (!e.name.equals(explicit.getClassName())) continue;
            } else if (order) {
                credited = aospCreditedFilter(e.filters, query, resolvedType, defaultOnly,
                        perPackage, false);
                if (credited == null) continue;
            } else if (!matchesAnyFilter(e, query, resolvedType, flags)) {
                continue;
            }
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = toActivityInfo(e, flags);
            if (credited != null) {
                ri.priority = credited[0];
                ri.match = credited[1];
                ri.isDefault = credited[2] != 0;
                cuts.add(credited[3]);
            }
            out.add(ri);
            if (explicit != null) break;
        }
        if (order) aospSortResults(out, cuts);
        return out;
    }

    /** AOSP MATCH_DEFAULT_ONLY. IntentResolver.buildResolveList (android11-release,
     *  services/core/java/com/android/server/IntentResolver.java) gates every matched
     *  filter on:
     *
     *      if (!defaultOnly || intentFilter.hasCategory(Intent.CATEGORY_DEFAULT)) {
     *
     *  with `defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0`, so a
     *  filter that does not declare CATEGORY_DEFAULT is INVISIBLE to a default-only
     *  query even when its action/data/type all match.
     *
     *  We ignored `flags` entirely here, which made us MORE PERMISSIVE than Android on
     *  exactly the path apps use: Intent.resolveActivity(pm) passes MATCH_DEFAULT_ONLY
     *  (Intent.java delegates with 0x00010000), while the fwprobe cases call
     *  pm.resolveActivity directly with 0. So an activity whose filter omits
     *  CATEGORY_DEFAULT could win a resolution AOSP would refuse, and NO probe row
     *  could see it -- the same blind spot that hid the pre-v939 bug where
     *  Intent.resolveActivity accepted a PackageManager and never consulted it.
     *
     *  The commonest concrete case: a MAIN/LAUNCHER filter declares CATEGORY_LAUNCHER,
     *  not CATEGORY_DEFAULT, so a launcher activity must NOT answer an implicit
     *  resolveActivity -- which is precisely what this flag exists to enforce.
     *
     *  `resolvedType` is AOSP's separate MIME-type argument (see
     *  macapkIntentSelector / macapkResolvedType): `intent` supplies action,
     *  data and categories, `resolvedType` the type. With the selector and
     *  resolve-type rules off it is exactly intent.getType(). */
    private static boolean matchesAnyFilter(Manifest.ActivityEntry e, Intent intent,
                                            String resolvedType, int flags) {
        return matchesAnyFilter(e.filters, intent, resolvedType, flags);
    }

    /** The same match, against a bare filter list. Split out at v1.0.1177 so
     *  `<service>` filters go through the IDENTICAL four-dimension matcher that
     *  activities and receivers already use, rather than a second, subtly
     *  different copy. (queryIntentServices calls bestMatchPriority directly
     *  when MACAPK_PM_RESOLVE_ORDER is off, and aospCreditedFilter when it is
     *  on; both share this matcher.) */
    private static boolean matchesAnyFilter(java.util.List<Manifest.IntentFilterEntry> filters,
                                            Intent intent, String resolvedType, int flags) {
        return bestMatchPriority(filters, intent, resolvedType, flags) != null;
    }

    /** The HIGHEST `android:priority` among the filters that match, or null when
     *  none do.
     *
     *  AOSP ranks resolution results by priority
     *  (IntentResolver.sortResults -> RESOLVE_PRIORITY_SORTER, descending), and
     *  for MESSAGING_EVENT it is load-bearing rather than cosmetic:
     *  firebase-messaging ships its own fallback FirebaseMessagingService at
     *  priority -500 so that it LOSES to the app's real handler. Return an
     *  arbitrary match instead and 4 corpus apps (Minecraft, Reddit, Telegram,
     *  Candy Crush) deliver every push into the SDK's dead-end fallback and the
     *  app's own onMessageReceived never runs -- silently, and looking exactly
     *  like a broken receive channel. (AOSP credits the FIRST matching filter
     *  in its scan order rather than the highest; that is
     *  MACAPK_PM_RESOLVE_ORDER's aospCreditedFilter. This method remains the
     *  membership test and the gate-off service ranking.)
     *
     *  The MIME type is passed separately, as AOSP's resolver takes it. With the
     *  selector and resolve-type rules off `resolvedType` IS intent.getType(),
     *  so both matchers below see exactly their pre-2026-09-26 input. (The
     *  3-argument overload that supplied intent.getType() for services went
     *  with wave 7: queryIntentServices now passes the type itself.) */
    private static Integer bestMatchPriority(java.util.List<Manifest.IntentFilterEntry> filters,
                                             Intent intent, String resolvedType, int flags) {
        final boolean defaultOnly =
                (flags & android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != 0;
        if (filters == null || filters.isEmpty()) return null;
        if (macapkIntentMatchData()) {
            return aospBestMatchPriority(filters, intent, resolvedType, defaultOnly);
        }
        // MACAPK_PM_INTENT_MATCH_DATA=0: the pre-2026-09-26 matcher, verbatim
        // but for where its type comes from (the caller's resolvedType).
        String action = intent.getAction();
        String type   = resolvedType;
        String scheme = null;
        try {
            android.net.Uri data = intent.getData();
            if (data != null) scheme = data.getScheme();
        } catch (Throwable ignored) {}
        java.util.Set<String> cats = null;
        try { cats = intent.getCategories(); } catch (Throwable ignored) {}
        // An intent with NOTHING to match on matches nothing. AOSP's
        // IntentResolver.queryIntent only takes its candidate cut when there is
        // an action, a scheme or a type to cut on, so a fully-empty implicit
        // intent selects no filters at all. Ours fell through every dimension
        // and returned the FIRST filter, which since v1.0.1177 means an empty
        // Intent handed to bindService/startService could construct and
        // onCreate() an arbitrary app service.
        if (action == null && type == null && scheme == null
                && (cats == null || cats.isEmpty())) {
            return null;
        }
        Integer best = null;
        for (Manifest.IntentFilterEntry f : filters) {
            if (defaultOnly
                    && !f.categories.contains(android.content.Intent.CATEGORY_DEFAULT)) continue;
            if (action != null && !f.actions.contains(action)) continue;
            if (cats != null && !cats.isEmpty()
                    && !f.categories.containsAll(cats)) continue;
            if (scheme != null && !f.schemes.isEmpty()
                    && !f.schemes.contains(scheme)) continue;
            if (type != null && !f.mimeTypes.isEmpty()
                    && !mimeMatches(f.mimeTypes, type)) continue;
            if (best == null || f.priority > best) best = f.priority;
        }
        return best;
    }

    /** AOSP IntentFilter.findMimeType wildcard rule: "type/*" matches any
     *  subtype, "*&#47;*" matches everything. */
    private static boolean mimeMatches(java.util.List<String> declared, String type) {
        for (String d : declared) {
            if (d.equals(type) || "*/*".equals(d)) return true;
            int star = d.indexOf("/*");
            if (star > 0 && star == d.length() - 2) {
                String major = d.substring(0, star + 1);   // "image/"
                if (type.startsWith(major)) return true;
            }
        }
        return false;
    }

    /** MACAPK_PM_INTENT_MATCH_DATA: bestMatchPriority with AOSP's rules.
     *
     *  Per filter, in AOSP's order: the IntentResolver candidate cut (a filter
     *  that is in no cut is never even offered to match()), the
     *  MATCH_DEFAULT_ONLY category gate (IntentResolver.buildResolveList, see
     *  matchesAnyFilter's header), then IntentFilter.match -- and that last step
     *  is the FRAMEWORK'S OWN android.content.IntentFilter, not a third copy of
     *  the rules. That class is already a faithful port of AOSP's
     *  match / matchData / findMimeType / matchDataAuthority (see its header),
     *  and it is what an app's own registerReceiver filters are matched with,
     *  so a manifest filter and a runtime filter now answer through ONE engine.
     *  Priority handling is unchanged from the legacy loop: the highest
     *  matching filter wins.
     *
     *  The type is the caller's `type` argument, AOSP's resolvedType: for
     *  activities and receivers that is the OUTER intent's type even when
     *  `intent` is its selector, provider-resolved for an untyped content: Uri
     *  (macapkResolvedType); for services it is the same since wave 7
     *  (MACAPK_PM_SVC_RESOLVE_TYPE), intent.getType() with that gate off.
     *
     *  The per-filter test is aospFilterMatch, shared with the resolver-order
     *  path (aospCreditedFilter, MACAPK_PM_RESOLVE_ORDER), so the two can never
     *  disagree about WHICH components match; they differ only in which
     *  matching filter's priority they report. */
    private static Integer aospBestMatchPriority(java.util.List<Manifest.IntentFilterEntry> filters,
                                                 Intent intent, String type, boolean defaultOnly) {
        String action = intent.getAction();
        android.net.Uri data = null;
        try { data = intent.getData(); } catch (Throwable ignored) {}
        String scheme = null;
        try { if (data != null) scheme = data.getScheme(); } catch (Throwable ignored) {}
        java.util.Set<String> cats = null;
        try { cats = intent.getCategories(); } catch (Throwable ignored) {}
        Integer best = null;
        for (Manifest.IntentFilterEntry f : filters) {
            // The <data> values as AOSP's parser READ them: a reference such as
            // android:scheme="@string/fb_login_protocol_scheme" resolved to its
            // string (MACAPK_PM_INTENT_DATA_RESREF; see macapkResolvedDataValues).
            java.util.List<String> schemes = macapkResolvedDataValues(f.schemes);
            java.util.List<String> types   = macapkResolvedDataValues(f.mimeTypes);
            if (aospFilterMatch(f, schemes, types, action, type, scheme, data, cats, defaultOnly) < 0) {
                continue;
            }
            if (best == null || f.priority > best) best = f.priority;
        }
        return best;
    }

    /** One manifest filter through AOSP's resolver, in AOSP's order: the
     *  IntentResolver candidate cut, the MATCH_DEFAULT_ONLY category gate
     *  (IntentResolver.buildResolveList, android14-release :774
     *  `if (!defaultOnly || intentFilter.hasCategory(Intent.CATEGORY_DEFAULT))`),
     *  then the framework IntentFilter's match(). Returns match()'s value
     *  (a MATCH_CATEGORY_* | adjustment, which AOSP stores in ResolveInfo.match
     *  and sorts on) when the filter is credited, or a negative number when it
     *  is not. `schemes` / `types` are f's data values as AOSP's parser read
     *  them (macapkResolvedDataValues). */
    private static int aospFilterMatch(Manifest.IntentFilterEntry f,
                                       java.util.List<String> schemes, java.util.List<String> types,
                                       String action, String type, String scheme,
                                       android.net.Uri data, java.util.Set<String> cats,
                                       boolean defaultOnly) {
        if (!aospCandidateCut(f.actions, schemes, types, action, type, scheme)) return -1;
        if (defaultOnly
                && !f.categories.contains(android.content.Intent.CATEGORY_DEFAULT)) return -1;
        android.content.IntentFilter real = macapkFrameworkFilter(f, schemes, types);
        if (real == null) return -1;
        return real.match(action, type, scheme, data, cats, "MacApkPM");
    }

    /** One parsed manifest `<intent-filter>` as the framework IntentFilter AOSP
     *  itself builds for it (ParsedIntentInfoUtils.parseIntentInfo ->
     *  addAction / addCategory / addDataScheme / addDataType / setPriority).
     *
     *  null for a filter whose mimeType AOSP would reject: addDataType throws
     *  MalformedMimeTypeException there and the PARSER turns that into an
     *  install failure, so no such filter exists on a device. Treating it as
     *  "matches nothing" is the conservative reading; dropping only the bad
     *  type would widen the filter into an untyped one, the opposite error.
     *
     *  ⚠ NOT YET MODELLED: <data> host / port / path* / ssp*. Manifest's
     *  IntentFilterEntry does not parse them, so a filter that names a scheme
     *  still matches every authority of that scheme -- over-permissive in the
     *  same direction as before, never newly restrictive. The IntentFilter
     *  below already implements matchDataAuthority / hasDataPath /
     *  hasDataSchemeSpecificPart; the follow-up is only to parse those
     *  attributes and add them here (addDataAuthority / addDataPath /
     *  addDataSchemeSpecificPart). Written up in
     *  docs/pm-intent-resolution-scope-and-matchdata_2026-09-26.md. */
    private static android.content.IntentFilter macapkFrameworkFilter(Manifest.IntentFilterEntry f,
                                                                      java.util.List<String> schemes,
                                                                      java.util.List<String> types) {
        android.content.IntentFilter real = new android.content.IntentFilter();
        for (String a : f.actions) real.addAction(a);
        for (String c : f.categories) real.addCategory(c);
        for (String s : schemes) real.addDataScheme(s);
        try {
            for (String t : types) real.addDataType(t);
        } catch (android.content.IntentFilter.MalformedMimeTypeException bad) {
            return null;
        }
        real.setPriority(f.priority);
        return real;
    }

    /** MACAPK_PM_INTENT_DATA_RESREF (default ON; only consulted on the
     *  MACAPK_PM_INTENT_MATCH_DATA path, so MATCH_DATA=0 also restores the raw
     *  values). AOSP's parser reads every <data> attribute through
     *  TypedArray.getNonConfigurationString (ParsedIntentInfoUtils.parseData,
     *  android14-release services/core/java/com/android/server/pm/pkg/component/
     *  ParsedIntentInfoUtils.java:166-283), which RESOLVES a resource reference
     *  to its string (TypedArray.java:330-358: `getValueAt(index, v)` then
     *  `v.coerceToString()`). Our Manifest keeps the raw token, and the binary
     *  manifest spells a reference "@0x7f10004b" -- so a scheme or MIME type
     *  declared through @string/ could never match anything. Measured in the
     *  corpus (docs/pm-intent-resolution-scope-and-matchdata_2026-09-26.md):
     *  Facebook's CustomTabActivity login redirect scheme (com.halfbrick.superjetpack),
     *  Epic Online Services' EOSAuthHandlerActivity auth redirect scheme (Among
     *  Us) and Reddit's ShareActivity's ten MIME types.
     *  A scheme cannot contain '@' (RFC 3986 section 3.1) and a MIME type cannot
     *  start with one, so a leading '@' is always a reference here. */
    static boolean macapkIntentDataResRef() {
        return !"0".equals(System.getenv("MACAPK_PM_INTENT_DATA_RESREF"));
    }

    /** `raw` with every resource reference replaced by its string value; the
     *  same list object when nothing needed resolving. A reference we cannot
     *  resolve (no resource index yet, not a string) stays raw, which matches
     *  nothing -- the pre-change answer, never a new wrong one.
     *
     *  ⚠ One AOSP nuance deliberately not modelled: getNonConfigurationString
     *  returns NULL (the attribute is ignored) when the string VARIES by
     *  configuration (`changingConfigs & ~allowedChangingConfigs`, :339-342).
     *  ResourceIndex does not report changing-configurations, and a redirect
     *  scheme is translatable="false" in every SDK seen, so the default-config
     *  value is used. */
    static java.util.List<String> macapkResolvedDataValues(java.util.List<String> raw) {
        if (raw.isEmpty() || !macapkIntentDataResRef()) return raw;
        java.util.List<String> out = null;
        for (int i = 0; i < raw.size(); i++) {
            String v = raw.get(i);
            String r = macapkResolvedDataValue(v);
            if (r == v) continue;
            if (out == null) out = new java.util.ArrayList<>(raw);
            out.set(i, r);
        }
        return out != null ? out : raw;
    }

    /** The schemes a BROWSER can hand this app (HostUrlIntake registers them
     *  with LaunchServices): every <data android:scheme> of a filter that
     *  carries ACTION_VIEW, CATEGORY_BROWSABLE and CATEGORY_DEFAULT, on an
     *  enabled, exported activity or alias. Those are the three conditions a
     *  browser's start has to meet on a device: it is another app (exported),
     *  it sends VIEW + BROWSABLE (Chromium sanitizeQueryIntentActivitiesIntent),
     *  and startActivity resolves with MATCH_DEFAULT_ONLY (DEFAULT). References
     *  (android:scheme="@string/fb_login_protocol_scheme") resolve exactly as
     *  the resolver reads them. Declaration order, duplicates kept (the caller
     *  folds case). */
    static java.util.List<String> macapkBrowsableSchemes(Manifest m) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (m == null || m.activities == null) return out;
        for (Manifest.ActivityEntry a : m.activities.values()) {
            if (a == null || !a.enabled || !a.exported) continue;
            for (Manifest.IntentFilterEntry f : a.filters) {
                if (!f.actions.contains(Intent.ACTION_VIEW)) continue;
                if (!f.categories.contains(Intent.CATEGORY_BROWSABLE)) continue;
                if (!f.categories.contains(Intent.CATEGORY_DEFAULT)) continue;
                out.addAll(macapkResolvedDataValues(f.schemes));
            }
        }
        return out;
    }

    /** One <data> value, resolved as above; the SAME object when it is not a
     *  reference, when resolution is off, or when it cannot be resolved. */
    private static String macapkResolvedDataValue(String v) {
        if (v == null || v.length() < 2 || v.charAt(0) != '@' || !macapkIntentDataResRef()) return v;
        String r = null;
        try {
            int id = resolveResRef(v, "string");
            Object idx = android.content.res.Resources.macapkGetResourceIndex();
            if (id != 0 && idx instanceof ResourceIndex) r = ((ResourceIndex) idx).stringForId(id);
        } catch (Throwable ignored) {
            r = null;
        }
        return (r == null || r.isEmpty()) ? v : r;
    }

    /** AOSP IntentResolver.queryIntent's candidate cut (android14-release
     *  services/core/java/com/android/server/IntentResolver.java:378-445), as a
     *  membership test on one filter. The resolver never scans all filters; it
     *  unions a few index lookups and runs match() only on those:
     *
     *    if (resolvedType != null) { slashpos > 0 and base != "*":
     *        mTypeToFilter(type) + mWildTypeToFilter(base) + mWildTypeToFilter("*")
     *        (or mBaseTypeToFilter(base) for "base/*");
     *      base == "*" and action != null: mTypedActionToFilter(action) }
     *    if (scheme != null) schemeCut = mSchemeToFilter(scheme)
     *    if (resolvedType == null && scheme == null && action != null)
     *        firstTypeCut = mActionToFilter(action)
     *
     *  and addFilter (:66-77) registers a filter in mActionToFilter ONLY when it
     *  declares no scheme and no type, and in mTypedActionToFilter only when it
     *  declares a type. For the concrete-type cuts "declares any type" is a
     *  superset of the exact index lookups, and match()'s findMimeType then
     *  discards every filter the precise cut would not have offered, so the
     *  result is identical.
     *
     *  What this adds over the old early-return ("an intent with NOTHING to
     *  match on matches nothing"): an intent with only CATEGORIES now matches
     *  nothing too (no cut is taken), as does one with a malformed slash-less
     *  type and no data, and a `*&#47;*` type with no action and no data. */
    private static boolean aospCandidateCut(java.util.List<String> actions,
                                            java.util.List<String> schemes,
                                            java.util.List<String> types,
                                            String action, String type, String scheme) {
        if (type != null) {
            int slash = type.indexOf('/');
            if (slash > 0) {
                if (!"*".equals(type.substring(0, slash))) {
                    if (!types.isEmpty()) return true;
                } else if (action != null) {
                    if (!types.isEmpty() && actions.contains(action)) return true;
                }
            }
        }
        if (scheme != null && schemes.contains(scheme)) return true;
        if (type == null && scheme == null && action != null) {
            return schemes.isEmpty() && types.isEmpty() && actions.contains(action);
        }
        return false;
    }

    // ================= INTENT RESOLUTION, wave 7 (2026-09-26): services, and
    // ================= the ORDER the resolver hands results back in
    //
    // Four more AOSP rules, each behind its own default-ON bisect gate in the
    // same `!"0".equals(getenv(...))` idiom as the wave-5 block above, and each
    // gate's "0" restores the exact pre-change code path:
    //   MACAPK_PM_SVC_SELECTOR        queryIntentServices resolves through a
    //                                 selector, as activities/receivers do
    //   MACAPK_PM_SVC_RESOLVE_TYPE    queryIntentServices matches with AOSP's
    //                                 resolvedType, as activities/receivers do
    //   MACAPK_PM_RESOLVE_ORDER       activities, receivers and services come
    //                                 back in IntentResolver's order
    //   MACAPK_PM_ACT_PRIORITY_CLAMP  a non-privileged app's ACTIVITY filter
    //                                 priority > 0 counts as 0
    // Why the service path waited for its own lane: it is the path the
    // runtime's own service starts take (ServiceRegistry.resolveImplicit and
    // Context.bindService call resolveService), so the census of what the
    // runtime sends had to come first. Research, AOSP citations, the census and
    // the corpus blast radius: docs/pm-service-selector-and-resolver-order_2026-09-26.md.
    // Offline gate with positive controls: debug/w7pmsvc_gate.sh.

    /** MACAPK_PM_SVC_SELECTOR (default ON). queryIntentServices resolves a
     *  component-less intent that carries a selector THROUGH the selector:
     *
     *    ComputerEngine.queryIntentServicesInternal (android14-release
     *    services/core/java/com/android/server/pm/ComputerEngine.java)
     *      :646  ComponentName comp = intent.getComponent();
     *      :647  if (comp == null) {
     *      :648      if (intent.getSelector() != null) {
     *      :649          originalIntent = intent;
     *      :650          intent = intent.getSelector();
     *      :651          comp = intent.getComponent();
     *    queryIntentServicesInternalBody
     *      :714  String pkgName = intent.getPackage();
     *
     *  So the SELECTOR supplies action, data, categories, the package scope and
     *  (when it names one) the component, the same shape as receivers
     *  (ResolveIntentHelper :413-421 then :466). The `originalIntent` re-check
     *  at :700-705 (PackageManagerServiceUtils.applyEnforceIntentFilterMatching)
     *  never removes a service here: it skips any component of the CALLER'S OWN
     *  app (:1187 "Do not enforce filter matching when the caller is system,
     *  root, or the same app"), and every row this method builds from the hosted
     *  manifest is the caller's own.
     *
     *  NOT applied to the three synthetic services answered first (licensing,
     *  gms register, gms measurement). They stand for OTHER packages, where
     *  AOSP would need the selector to resolve AND the outer intent to pass the
     *  :700-705 re-check; every caller in the census binds them with a plain
     *  action + setPackage and no selector, so they keep reading the intent as
     *  passed, byte-identical to before. */
    static boolean macapkSvcSelector() {
        return !"0".equals(System.getenv("MACAPK_PM_SVC_SELECTOR"));
    }

    /** MACAPK_PM_SVC_RESOLVE_TYPE (default ON). queryIntentServices matches
     *  with AOSP's resolvedType instead of intent.getType():
     *  ApplicationPackageManager.queryIntentServicesAsUser (android14-release
     *  core/java/android/app/ApplicationPackageManager.java:1600-1607) and
     *  resolveServiceAsUser (:1570-1577) both pass
     *      intent.resolveTypeIfNeeded(mContext.getContentResolver())
     *  for the intent AS PASSED, before ComputerEngine swaps in a selector. That
     *  is exactly macapkResolvedType (the outer intent's type, provider-resolved
     *  for an untyped content: Uri), the value activities and receivers already
     *  match with, so MACAPK_PM_INTENT_MATCH_DATA=0 or
     *  MACAPK_PM_INTENT_RESOLVE_TYPE=0 also turn the provider lookup off here.
     *  With this gate "0" the type is intent.getType() of the intent as passed,
     *  which is the pre-change value (and still AOSP's "outer type" rule when a
     *  selector is resolved through). */
    static boolean macapkSvcResolveType() {
        return !"0".equals(System.getenv("MACAPK_PM_SVC_RESOLVE_TYPE"));
    }

    /** MACAPK_PM_RESOLVE_ORDER (default ON; only consulted on the
     *  MACAPK_PM_INTENT_MATCH_DATA path, which is the one that knows AOSP's
     *  match value). queryIntentActivities, queryBroadcastReceivers and
     *  queryIntentServices return the hosted package's rows in the order AOSP's
     *  IntentResolver produces, carrying the fields it sets:
     *
     *  1. WHICH filter a component is credited with. IntentResolver adds a
     *     component once, for the FIRST filter of it that matches in its scan
     *     order; later filters of the same component are skipped
     *     (buildResolveList :762 `if (!allowFilterResult(filter, dest))`,
     *     ComponentResolver :1063-1071 activities/receivers, :1630-1640
     *     services). The scan order is the candidate cuts in turn (queryIntent
     *     :448-466: first, second, third type cut, then the scheme cut), each in
     *     registration (= manifest) order, for an UNSCOPED query; and plain
     *     declaration order for a package-scoped one, which goes through
     *     queryIntentForPackage -> queryIntentFromList (:353-370) instead. The
     *     previous code credited the HIGHEST-priority matching filter.
     *  2. The row fields, from that filter: ActivityIntentResolver.newResult
     *     :1180 `res.priority = intentFilter.getPriority();`, :1185
     *     `res.match = match;`, :1186 `res.isDefault = info.isHasDefault();`
     *     (ServiceIntentResolver :1700-1704 the same). isHasDefault is
     *     `intentFilter.hasCategory(Intent.CATEGORY_DEFAULT)`
     *     (ParsedIntentInfoUtils.java:145).
     *  3. The sort: sortResults :1202-1204 (activities, receivers) and
     *     :1713-1715 (services) are `results.sort(RESOLVE_PRIORITY_SORTER)`,
     *     a STABLE sort by priority, preferredOrder, isDefault, match, system,
     *     package (AOSP_RESOLVE_PRIORITY_SORTER below).
     *
     *  Before this, activities and receivers came back in manifest order and
     *  services were sorted by priority alone. Receivers matter most: the list
     *  IS the delivery order (Context.macapkDeliverToMatchingManifestReceivers
     *  runs it in order, and AMS delivers an ordered broadcast in it). The
     *  host rows (com.macapk.host, com.android.settings) stay APPENDED after the
     *  sorted app rows, as before; see queryIntentActivities. "0" restores
     *  manifest order for activities and receivers and the priority-only sort
     *  (highest matching priority) for services. */
    static boolean macapkResolveOrder() {
        return !"0".equals(System.getenv("MACAPK_PM_RESOLVE_ORDER"));
    }

    /** MACAPK_PM_ACT_PRIORITY_CLAMP (default ON; only consulted on the
     *  MACAPK_PM_RESOLVE_ORDER path). An ACTIVITY filter's android:priority
     *  above 0 counts as 0, because every app Mac.apk hosts is a non-privileged
     *  install:
     *
     *    ComponentResolver.adjustPriority (android14-release services/core/java/
     *    com/android/server/pm/resolution/ComponentResolver.java:493-515)
     *      if (intentFilter.getPriority() <= 0) { return; }
     *      ...
     *      if (!privilegedApp) {
     *          // non-privileged applications can never define a priority >0
     *          intentFilter.setPriority(0);
     *          return;
     *      }
     *
     *  ACTIVITIES ONLY: addAllComponents (:203-211) runs adjustPriority over the
     *  `newIntents` list, which addActivity fills only for type "activity"
     *  (:1030 `if (newIntents != null && "activity".equals(type))`); receivers
     *  are added with a null list (:367) and services never reach it. So a
     *  receiver's priority 999 and firebase's service priority -500 stand, and a
     *  negative activity priority stands too. The clamped value is the one the
     *  sort uses and the one ResolveInfo.priority reports. */
    static boolean macapkActivityPriorityClamp() {
        return !"0".equals(System.getenv("MACAPK_PM_ACT_PRIORITY_CLAMP"));
    }

    /** ComponentResolver.RESOLVE_PRIORITY_SORTER (android14-release
     *  ComponentResolver.java:107-141), key for key: priority (descending),
     *  preferredOrder (descending), isDefault first, match (descending), system
     *  first, then package name. For the hosted package's rows the last two are
     *  always equal. Package names are compared null-safely here only because
     *  a hand-built row could lack one; AOSP's never do. */
    static final java.util.Comparator<ResolveInfo> AOSP_RESOLVE_PRIORITY_SORTER = (r1, r2) -> {
        int v1 = r1.priority;
        int v2 = r2.priority;
        if (v1 != v2) return (v1 > v2) ? -1 : 1;
        v1 = r1.preferredOrder;
        v2 = r2.preferredOrder;
        if (v1 != v2) return (v1 > v2) ? -1 : 1;
        if (r1.isDefault != r2.isDefault) return r1.isDefault ? -1 : 1;
        v1 = r1.match;
        v2 = r2.match;
        if (v1 != v2) return (v1 > v2) ? -1 : 1;
        if (r1.system != r2.system) return r1.system ? -1 : 1;
        if (r1.activityInfo != null && r2.activityInfo != null) {
            return macapkComparePkg(r1.activityInfo.packageName, r2.activityInfo.packageName);
        }
        if (r1.serviceInfo != null && r2.serviceInfo != null) {
            return macapkComparePkg(r1.serviceInfo.packageName, r2.serviceInfo.packageName);
        }
        if (r1.providerInfo != null && r2.providerInfo != null) {
            return macapkComparePkg(r1.providerInfo.packageName, r2.providerInfo.packageName);
        }
        return 0;
    };

    private static int macapkComparePkg(String a, String b) {
        if (a == null || b == null) return (a == null) == (b == null) ? 0 : (a == null ? 1 : -1);
        return a.compareTo(b);
    }

    /** The filter AOSP's IntentResolver CREDITS one component with, as
     *  {priority, match, isDefault (1/0), cut}, or null when none is credited.
     *  Null exactly when aospBestMatchPriority is null for the same arguments:
     *  both run every filter through aospFilterMatch, so the set of matching
     *  components cannot differ between the two, only which matching filter
     *  speaks for a component (see macapkResolveOrder, point 1).
     *
     *  perPackage: the query went through AOSP's per-package path
     *  (queryIntentForPackage), where the first matching filter in DECLARATION
     *  order wins and there are no cuts (cut is reported as 0). Otherwise the
     *  winner is the matching filter with the lowest (cut, declaration index),
     *  cut from aospFirstCut.
     *
     *  clampActivityPriority: MACAPK_PM_ACT_PRIORITY_CLAMP for an activity
     *  query (never for receivers or services). */
    private static int[] aospCreditedFilter(java.util.List<Manifest.IntentFilterEntry> filters,
                                            Intent query, String type, boolean defaultOnly,
                                            boolean perPackage, boolean clampActivityPriority) {
        if (filters == null || filters.isEmpty()) return null;
        String action = query.getAction();
        android.net.Uri data = null;
        try { data = query.getData(); } catch (Throwable ignored) {}
        String scheme = null;
        try { if (data != null) scheme = data.getScheme(); } catch (Throwable ignored) {}
        java.util.Set<String> cats = null;
        try { cats = query.getCategories(); } catch (Throwable ignored) {}
        int[] best = null;
        for (Manifest.IntentFilterEntry f : filters) {
            java.util.List<String> schemes = macapkResolvedDataValues(f.schemes);
            java.util.List<String> types   = macapkResolvedDataValues(f.mimeTypes);
            int match = aospFilterMatch(f, schemes, types, action, type, scheme, data, cats, defaultOnly);
            if (match < 0) continue;
            int cut = perPackage ? 0 : aospFirstCut(f.actions, schemes, types, action, type, scheme);
            // An earlier-scanned filter of this component already won.
            if (best != null && best[3] <= cut) continue;
            int prio = f.priority;
            if (clampActivityPriority && prio > 0) prio = 0;
            best = new int[] { prio, match,
                    f.categories.contains(android.content.Intent.CATEGORY_DEFAULT) ? 1 : 0, cut };
            if (perPackage) break;
        }
        return best;
    }

    /** The FIRST candidate cut of AOSP IntentResolver.queryIntent
     *  (android14-release services/core/java/com/android/server/IntentResolver.java
     *  :378-466) that offers this filter: 1, 2, 3 for the first, second and
     *  third type cut (or the action cut), 4 for the scheme cut. The cut
     *  arrays are filled by addFilter (:58-77) and register_mime_types
     *  (:575-604): a concrete declared type "a/b" goes to mTypeToFilter["a/b"]
     *  and mBaseTypeToFilter["a"]; a declared "a/*" (stored as "a") to
     *  mTypeToFilter["a/*"] and mWildTypeToFilter["a"]; "*&#47;*" (stored as
     *  "*") to mWildTypeToFilter["*"]; a filter with no scheme and no type to
     *  mActionToFilter; a typed one to mTypedActionToFilter too. The lookups:
     *    concrete type T, base B != "*": 1 = mTypeToFilter[T] (:407),
     *        2 = mWildTypeToFilter[B] (:409), 3 = mWildTypeToFilter["*"] (:422)
     *    type "B/*", B != "*": 1 = mBaseTypeToFilter[B] (:414),
     *        2 = mWildTypeToFilter[B] (:416), 3 = mWildTypeToFilter["*"]
     *    type "*&#47;..." with an action: 1 = mTypedActionToFilter[action] (:428)
     *    scheme S: 4 = mSchemeToFilter[S] (:438)
     *    no type, no scheme, an action: 1 = mActionToFilter[action] (:446)
     *  Every filter aospFilterMatch credits is in at least one of these
     *  (aospCandidateCut is a superset test that match() then narrows to the
     *  same set); 5 is returned for "none", which sorts after every real cut
     *  and cannot occur for a credited filter. */
    private static int aospFirstCut(java.util.List<String> actions,
                                    java.util.List<String> schemes,
                                    java.util.List<String> types,
                                    String action, String type, String scheme) {
        if (type != null) {
            int slash = type.indexOf('/');
            if (slash > 0) {
                String base = type.substring(0, slash);
                if (!"*".equals(base)) {
                    boolean wildIntent = type.length() == slash + 2 && type.charAt(slash + 1) == '*';
                    for (String t : types) {
                        if (aospPartialTypeBase(t) != null) continue;
                        if (wildIntent ? base.equals(aospTypeBase(t)) : type.equals(t)) return 1;
                    }
                    for (String t : types) if (base.equals(aospPartialTypeBase(t))) return 2;
                    for (String t : types) if ("*".equals(aospPartialTypeBase(t))) return 3;
                } else if (action != null) {
                    if (!types.isEmpty() && actions.contains(action)) return 1;
                }
            }
        }
        if (scheme != null && schemes.contains(scheme)) return 4;
        if (type == null && scheme == null && action != null
                && schemes.isEmpty() && types.isEmpty() && actions.contains(action)) {
            return 1;
        }
        return 5;
    }

    /** "a" for a declared partial type "a/*" (and "*" for "*&#47;*"), the
     *  form IntentFilter.addDataType stores it in; null for a concrete type. */
    private static String aospPartialTypeBase(String declared) {
        if (declared == null) return null;
        int slash = declared.indexOf('/');
        if (slash <= 0) return null;
        return (declared.length() == slash + 2 && declared.charAt(slash + 1) == '*')
                ? declared.substring(0, slash) : null;
    }

    /** The part before the '/' of a declared type (the whole string without one). */
    private static String aospTypeBase(String declared) {
        int slash = declared.indexOf('/');
        return slash > 0 ? declared.substring(0, slash) : declared;
    }

    /** IntentResolver's result order for the hosted package's rows, in place.
     *  `cuts` runs parallel to `rows`, which arrive in manifest order: first
     *  AOSP's insertion order (cut by cut, each in registration order, which
     *  for one package IS manifest order; all cuts are 0 on the per-package
     *  path, so that is plain manifest order), then sortResults' stable
     *  RESOLVE_PRIORITY_SORTER. List.sort is a stable merge sort, as the one
     *  AOSP calls is. */
    private static void aospSortResults(java.util.List<ResolveInfo> rows,
                                        java.util.List<Integer> cuts) {
        final int n = rows.size();
        if (n < 2) return;
        java.util.List<Integer> order = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        order.sort(java.util.Comparator.comparingInt(cuts::get));
        java.util.List<ResolveInfo> inserted = new java.util.ArrayList<>(n);
        for (int i : order) inserted.add(rows.get(i));
        inserted.sort(AOSP_RESOLVE_PRIORITY_SORTER);
        rows.clear();
        rows.addAll(inserted);
    }

    // MACAPK_GMS_HONEST_MISSING (default-ON): report the GMS-absent device
    // contract truthfully instead of spoofing Play Services present. See the
    // GoogleApiAvailabilityLight header for the full rationale. Set to 0/false
    // to restore the legacy "GMS present" behavior for A/B diagnosis.
    // ONE source of truth (com.macapk.loader.PlayServices). This is the
    // PACKAGE question ("is com.google.android.gms installed?"), which is
    // deliberately INDEPENDENT of the availability verdict -- keeping them
    // separate is what lets availability answer SUCCESS while FCM stays honest
    // and self-terminates. Do not re-derive either answer here.
    static final boolean GMS_HONEST_MISSING = !PlayServices.gmsPackageInstalled();

    /** AOSP PackageManager.checkPermission(permName, pkgName): does the NAMED
     *  PACKAGE hold that permission? Two rules, both of them "no" by default.
     *
     *  1. A package that is NOT installed holds NO permissions
     *     (PackageManagerService.checkUidPermission returns DENIED for an
     *     unknown package). Mac.apk hosts exactly one package, and
     *     getPackageInfo / getApplicationInfo already answer
     *     NameNotFoundException for every other name, so this is the same
     *     "installed" fact read through a second API rather than a new claim.
     *     com.google.android.gms is the load-bearing instance: firebase-iid's
     *     Metadata gates its "GMS core present" decision on
     *       pm.checkPermission("com.google.android.c2dm.permission.SEND",
     *                          "com.google.android.gms") == PERMISSION_DENIED
     *         -> GMSCORE_NOT_FOUND -> isGmscorePresent()==false
     *         -> SyncTask.run() self-terminates with NO Rpc/retry.
     *     Answering GRANTED there told Firebase GMS holds the c2dm permission
     *     and produced an unbounded "Rpc: No response" retry loop (observed
     *     hanging Minecraft Bedrock's boot).
     *
     *  2. For OUR OWN package, a permission is held only if the manifest
     *     REQUESTED it. This method used to return GRANTED for everything,
     *     which is the same class of lie in the other direction: an app told it
     *     holds CAMERA / RECORD_AUDIO / ACCESS_FINE_LOCATION calls the API,
     *     gets nothing back (no camera, no microphone, no location provider),
     *     and retries -- and unlike a DENIED it never reaches its own
     *     "capability unavailable" branch. AOSP answers DENIED for a permission
     *     the app never declared, and apps are written for that answer:
     *     `if (checkSelfPermission(P) != GRANTED) { degrade or request }`.
     *
     *  ⚠ DELIBERATE DIVERGENCE for the DECLARED case: a declared permission is
     *  reported GRANTED even when it is `dangerous` and the app targets 23+,
     *  where a device would say DENIED until the user grants it at runtime.
     *  For every dangerous permission EXCEPT the storage family there is no
     *  grant UI -- Activity.requestPermissions answers them from this method
     *  -- so answering DENIED for a declared one would produce exactly the
     *  loop this rule exists to prevent: check -> DENIED -> request ->
     *  "granted" -> re-check -> DENIED -> ... The install-time model (declared
     *  == held) is the one that is coherent with the rest of the runtime.
     *  The storage family left this rule at stage S5 (2026-09-26): it has a
     *  real grant store and a real dialog now, so it answers honestly -- see
     *  the S5 block in the method body and SpecialAccessStore.PermModel.
     *
     *  Rollback: MACAPK_PM_SELF_ONLY=0 restores the legacy blanket GRANTED,
     *  the same switch that restores the legacy "every package is installed"
     *  answer -- one flag, because it is one question. */
    @Override public int checkPermission(String permName, String pkgName) {
        if (GMS_HONEST_MISSING && "com.google.android.gms".equals(pkgName)) {
            // FCM (v1.0.1175, gated on pushBackendEnabled, default OFF): when the
            // in-process push backend is live, the gms package genuinely HOLDS the
            // c2dm SEND permission -- and it MUST, because Firebase's
            // Metadata.isGmscorePresent() reads exactly this and picks the bound
            // register path only when it is GRANTED. This flips to GRANTED ONLY
            // together with a com.macapk.gms.PushRegisterService that replies (see
            // PlayServices.pushBackendEnabled); GRANTED without a responding
            // service is what re-opens the "Rpc: No response" hang.
            if (PlayServices.pushBackendEnabled()
                    && "com.google.android.c2dm.permission.SEND".equals(permName)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            return PackageManager.PERMISSION_DENIED;
        }
        // Stage S1: MANAGE_EXTERNAL_STORAGE and MANAGE_MEDIA are
        // signature|appop|preinstalled (core/res/AndroidManifest.xml android14
        // :1244-1246, :1262-1263). PermissionManagerServiceImpl never grants a
        // signature permission to a third-party app (:2810-2856), so a device
        // answers DENIED -- declared or not, and STILL after the user allows
        // "All files access": that grant lives only in the app-op
        // (AppOpsManager / SpecialAccessStore), which is why
        // Environment.isExternalStorageManager asks the op first. Answering
        // GRANTED for a declared one (the install-time rule below) made the op's
        // MODE_DEFAULT fallback say "manager" for every app that merely declared
        // it. Checked ahead of MACAPK_PM_SELF_ONLY=0 on purpose: the special
        // grants have their own switch, MACAPK_ALLFILES_HONEST=0.
        if (permName != null && macapkNeverGranted(permName)) {
            return PackageManager.PERMISSION_DENIED;
        }
        if ("0".equals(System.getenv("MACAPK_PM_SELF_ONLY"))) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (pkgName == null || !pkgName.equals(this.packageName)) {
            return PackageManager.PERMISSION_DENIED;   // not installed here
        }
        if (permName == null) return PackageManager.PERMISSION_DENIED;
        // Stage S5 (MACAPK_MEDIA_PERMS_ASK, default ON): the storage runtime
        // permissions -- READ_MEDIA_IMAGES/VIDEO/AUDIO,
        // READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION and
        // READ/WRITE_EXTERNAL_STORAGE -- are no longer covered by the
        // install-time rule below. They answer as a device does: DENIED until
        // the user allows them in the runtime-permission dialog
        // (Activity.requestPermissions -> macapk-installhelper
        // runtime-permission, the answer signed into the S1 store), including
        // the implicit split permissions (WRITE implies READ, platform.xml),
        // DENIED for a permission the presented API level does not define, and
        // DENIED for READ/WRITE_EXTERNAL_STORAGE in an app targeting 33+ on a
        // 33+ platform (never grantable there). A pre-M app keeps its
        // install-time grant. SpecialAccessStore.PermModel is the one decision
        // point; null means "not S5's question" (gate off, no manifest, not a
        // storage permission) and the rule below answers.
        Boolean storage = SpecialAccessStore.storagePermissionGranted(permName);
        if (storage != null) {
            return storage ? PackageManager.PERMISSION_GRANTED
                           : PackageManager.PERMISSION_DENIED;
        }
        Boolean location = locationPermissionGranted(permName);
        if (location != null) {
            return location ? PackageManager.PERMISSION_GRANTED
                            : PackageManager.PERMISSION_DENIED;
        }
        return declaredPermissions().contains(permName)
            ? PackageManager.PERMISSION_GRANTED
            : PackageManager.PERMISSION_DENIED;
    }

    /** The permissions no third-party app can hold, however it asks: stage
     *  S1's special-access pair (see checkPermission). Gated with the rest of
     *  S1 so MACAPK_ALLFILES_HONEST=0 restores the declared == held answer. */
    static boolean macapkNeverGranted(String permName) {
        return SpecialAccessStore.honest()
            && (SpecialAccessStore.PERM_MANAGE_EXTERNAL_STORAGE.equals(permName)
                || SpecialAccessStore.PERM_MANAGE_MEDIA.equals(permName));
    }

    /** ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION. Null means "not a
     *  location permission, or the host is not bridged" and the install-time
     *  rule answers.
     *
     *  The location runtime permission left the declared == held rule the way
     *  the storage family did at S5, with TWO grants behind it, because macOS
     *  authorizes location for Mac.apk as a WHOLE (locationd keys the client by
     *  the shared runtime's signing identifier, org.macapk.runtime -- measured
     *  in locationd's log on 2026-10-05), so one "Allow" there would otherwise
     *  hand every installed app's ad SDK the user's location:
     *    1. the PER-APP Android grant: the token macapk-installhelper's
     *       runtime-permission --prompt location writes into the signed store
     *       (SpecialAccessStore.locationGranted) -- GrantPermissionsActivity's
     *       answer, asked when the app calls requestPermissions;
     *    2. the HOST gate: macOS Location Services' own authorization for
     *       Mac.apk, asked once, right after the first per-app Allow.
     *  For an app targeting 23+:
     *    - not declared                       -> DENIED (as before)
     *    - per-app grant missing or denied    -> DENIED
     *    - Location Services off system-wide  -> GRANTED: the master switch is
     *      separate from the grant on Android; the app sees isLocationEnabled()
     *      false, which is the state it has UI for ("turn on location").
     *    - macOS not yet asked / refused      -> DENIED
     *    - otherwise GRANTED, except FINE when macOS allows approximate location
     *      only (COARSE without FINE, the Android 12 approximate grant)
     *  A pre-M app keeps its install-time grant whatever either says, as on a
     *  device; LocationManager withholds its fixes when macOS refuses, which is
     *  what an app-op denial does to a legacy app.
     *  MACAPK_MEDIA_PERMS_ASK=0 (no runtime-permission dialogs at all) leaves
     *  only the host gate: declared and macOS-authorized. */
    private Boolean locationPermissionGranted(String permName) {
        boolean fine = android.Manifest.permission.ACCESS_FINE_LOCATION.equals(permName);
        if (!fine && !android.Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName)) {
            return null;
        }
        if (!LocationBridge.available()) return null;
        if (!declaredPermissions().contains(permName)) return Boolean.FALSE;
        if (targetSdk > 0 && targetSdk < 23) return Boolean.TRUE;
        if (SpecialAccessStore.mediaPermsAsk() && !SpecialAccessStore.locationGranted(permName)) {
            return Boolean.FALSE;
        }
        if (!LocationBridge.servicesEnabled()) return Boolean.TRUE;
        if (!LocationBridge.authorized()) return Boolean.FALSE;
        return fine ? !LocationBridge.reducedAccuracy() : Boolean.TRUE;
    }

    /** The `<uses-permission>` set, as a lookup. Built once; the manifest does
     *  not change under a running process. An app with NO parsed manifest would
     *  otherwise be told it holds nothing at all, so a missing manifest falls
     *  back to the legacy permissive answer rather than silently disabling
     *  every capability -- absence of evidence is not a denial. */
    private volatile java.util.Set<String> cachedDeclaredPerms;
    private java.util.Set<String> declaredPermissions() {
        java.util.Set<String> s = cachedDeclaredPerms;
        if (s != null) return s;
        synchronized (this) {
            if (cachedDeclaredPerms != null) return cachedDeclaredPerms;
            java.util.Set<String> built;
            if (manifest() == null) {
                // No manifest to consult. PERMISSIVE fallback, and not a silent
                // one -- a runtime that cannot read its own manifest has a
                // bigger problem than permission fidelity, and failing closed
                // here would disable capabilities across the whole app.
                NativeLog.w("Mac.apk.PM",
                    "no parsed manifest -- checkPermission falls back to GRANTED"
                    + " for the running package");
                built = ALL_PERMISSIONS_GRANTED;
            } else {
                built = new java.util.HashSet<>(requestedPermissions());
            }
            cachedDeclaredPerms = built;
            return built;
        }
    }

    /** Sentinel set whose contains() is always true; see declaredPermissions. */
    private static final java.util.Set<String> ALL_PERMISSIONS_GRANTED =
        new java.util.AbstractSet<String>() {
            @Override public boolean contains(Object o) { return true; }
            @Override public java.util.Iterator<String> iterator() {
                return java.util.Collections.<String>emptySet().iterator();
            }
            @Override public int size() { return 0; }
        };

    /** AOSP PackageManagerService.canRequestPackageInstallsInternal THROWS
     *  rather than answering when the calling package has not DECLARED
     *  REQUEST_INSTALL_PACKAGES:
     *      if (throwIfPermNotDeclared && !ArrayUtils.contains(
     *              requestedPermissions, Manifest.permission.REQUEST_INSTALL_PACKAGES))
     *          throw new SecurityException(
     *              "Need to declare " + ambiguousPermission + " to call this api");
     *  That is a contract, not an accident: the API is only meaningful to an app
     *  that intends to install packages, and swallowing it as `false` hides the
     *  app's own manifest bug. The throw is also what the fwprobe golden pins
     *  (debug/fwprobe/golden/emulator-api30.tsv:7275, PM.misc.
     *  canRequestPackageInstalls = THROW:SecurityException -- the probe APK does
     *  not declare the permission), so it must survive any change here.
     *
     *  For a DECLARING app the answer is now TRUE. On a device this reads the
     *  user's per-app "install unknown apps" toggle; Mac.apk's equivalent gate
     *  is the NATIVE confirm + authorization step inside macapk-installhelper
     *  (launcher/InstallHelperMain.swift), which every Android-initiated
     *  install must pass DOWNSTREAM of this answer -- so "this app may ASK to
     *  install" is truthfully yes, exactly as it is for a device app whose
     *  toggle the user enabled. Answering false made installer apps (F-Droid's
     *  DefaultInstaller, measured from its 1.23.2 bytecode 2026-08-11) fire a
     *  dead ACTION_MANAGE_UNKNOWN_APP_SOURCES settings round-trip and a
     *  misleading "allow unknown sources" toast before proceeding.
     *  ⚠ DELIBERATE fwprobe deviation FOR DECLARING CALLERS ONLY: a fresh AVD
     *  answers false until the user flips the Settings toggle; Mac.apk has no
     *  such Settings surface, and the install-time auth gate is the replacement
     *  the master plan (7.4) specifies. The golden row is unaffected (its caller
     *  does not declare, so both sides THROW). */
    @Override public boolean canRequestPackageInstalls() {
        if (!declaredPermissions().contains(
                "android.permission.REQUEST_INSTALL_PACKAGES")) {
            throw new SecurityException(
                "Need to declare android.permission.REQUEST_INSTALL_PACKAGES"
                + " to call this api");
        }
        return true;
    }

    /** AOSP ActivityTaskManager.getDefaultAppRecentsLimitStatic():
     *  `getMaxRecentTasksStatic() / 6`, where the max is 36 on a low-RAM device
     *  and 48 otherwise. */
    private static int defaultAppRecentsLimit() {
        boolean lowRam = false;
        try { lowRam = new android.app.ActivityManager().isLowRamDevice(); }
        catch (Throwable ignored) {}
        return (lowRam ? 36 : 48) / 6;
    }

    /** AOSP PackageManager.queryContentProviders(processName, uid, flags): the
     *  providers installed in that process. Mac.apk runs one app in one process,
     *  so a query naming our process (or naming none) sees our declared
     *  providers and any other process sees nothing. */
    @Override public List<ProviderInfo> queryContentProviders(
            String processName, int uid, int flags) {
        if (processName != null && !processName.equals(this.packageName)) {
            return Collections.emptyList();
        }
        if (providers.isEmpty()) return Collections.emptyList();
        java.util.List<ProviderInfo> out = new java.util.ArrayList<>();
        for (Manifest.ProviderEntry e : providers.values()) {
            try {
                out.add(getProviderInfo(
                    new ComponentName(this.packageName, e.name), flags));
            } catch (NameNotFoundException ignored) {}
        }
        return out;
    }

    // ---- System features -------------------------------------------------
    // AOSP contract: hasSystemFeature answers whether THIS DEVICE has the named
    // android.hardware.* / android.software.* capability, and it must agree with
    // getSystemAvailableFeatures ("Check whether the given feature name is one of
    // the available features as returned by getSystemAvailableFeatures()",
    // frameworks/base core/java/android/content/pm/PackageManager.java). On a
    // device PackageManagerService answers out of mAvailableFeatures, which
    // SystemConfig builds from that device's own feature XML. Mac.apk's "device"
    // IS this runtime, so the set below is what this runtime actually implements.
    //
    // The previous blanket `return false` was the same class of lie the real-
    // signature work (v580) removed from getPackageInfo: it told every app "no
    // audio output, no touchscreen, no screen orientations" about capabilities
    // Mac.apk provides end to end, so an app that gates its touch controls or its
    // sound behind a feature probe degraded for no reason.
    //
    // Absent hardware stays absent on purpose. That is the same honest
    // missing-device stance checkPermission takes above for GMS, and the one the
    // rest of the framework already takes:
    // BluetoothAdapter.getDefaultAdapter()==null, every LocationManager provider
    // disabled, no in-process web engine, no sensor hardware behind
    // SensorManager's synthesized stream. Each of those flips to
    // true only when the matching macOS passthrough actually lands, never before:
    // a feature claimed without the capability behind it is worse than absent,
    // because the app takes the branch that then fails.
    //
    // ✔ v1.0.942: THE CAMERA AND MICROPHONE ROWS JUST FLIPPED, on exactly that
    // rule. This list used to name "Camera.getNumberOfCameras()==0" and
    // "AudioRecord reading silence" as examples of honest absence. Both were
    // really OUR OMISSION described as the device's limitation -- the owner said
    // so plainly ("All MacBooks have a camera, and mic. We can easily wire those
    // in and should"), v1.0.940 wired them (runtime/src/camera_capture.mm,
    // runtime/src/audio_input.mm), and the owner then CONFIRMED end to end on a
    // real MacBook: camera preview, microphone capture, and playback of the
    // recording all work. The declarations below are backed by observed frames
    // and observed audio, not by code landing.
    private static final java.util.Set<String> SYSTEM_FEATURES;
    static {
        java.util.LinkedHashSet<String> f = new java.util.LinkedHashSet<>();
        // FEATURE_TOUCHSCREEN. The device Mac.apk presents has a finger
        // touchscreen: android.content.res.Configuration.touchscreen is
        // TOUCHSCREEN_FINGER, and the Cocoa bridge turns host pointer events into
        // real ACTION_DOWN / MOVE / UP MotionEvents down the ordinary View +
        // TouchDispatcher path, so every test an app can run observes one.
        f.add("android.hardware.touchscreen");
        // ---- Camera (v1.0.942) ------------------------------------------
        // ⚠ FEATURE_CAMERA is deliberately NOT here, and that is not an
        // oversight. AOSP's FEATURE_CAMERA means a camera facing AWAY from the
        // screen (a rear camera); FEATURE_CAMERA_FRONT means one facing the
        // user; FEATURE_CAMERA_ANY means at least one in any direction. A
        // MacBook has exactly one built-in camera and it faces the user, which
        // the capture layer reports as LENS_FACING_FRONT. So FRONT and ANY are
        // true and the rear-camera claim stays false -- an app that gated a
        // "flip camera" control or a document-scanner flow on FEATURE_CAMERA
        // would otherwise take a branch this hardware cannot serve.
        //
        // ⚠ KNOWN INCOMPLETENESS behind these flags, notated rather than hidden:
        // only ImageReader outputs receive frames. A capture session whose only
        // targets are SurfaceView / TextureView / MediaRecorder fails LOUDLY at
        // onConfigureFailed rather than showing a black rectangle, but it does
        // fail, and TextureView preview is what a typical camera app reaches for
        // first. android.hardware.Camera (the legacy camera1 API) still reports
        // zero cameras. Both are real work, not adjudications.
        f.add("android.hardware.camera.any");
        f.add("android.hardware.camera.front");
        // ---- Microphone (v1.0.942) --------------------------------------
        // Backed by runtime/src/audio_input.mm: a real AVAudioEngine input tap
        // through AVAudioConverter, delivering the app's requested rate/channels/
        // encoding. AudioRecord.getState() reports STATE_INITIALIZED if and only
        // if a live stream exists, so the flag and the API agree in both
        // directions -- which is the property whose ABSENCE made the old
        // "AudioRecord reading silence" state a contradiction rather than a gap.
        f.add("android.hardware.microphone");
        // FEATURE_SENSOR_*. These were ABSENT while SensorManager hands out real,
        // event-DELIVERING sensors for every one of them -- a direct
        // feature-flag-vs-API contradiction, which is worse than either answer
        // alone: an app that checks hasSystemFeature() first concludes the sensor
        // does not exist and takes its no-sensor path, while an app that just calls
        // getDefaultSensor() gets a working one. Two apps, two different devices.
        // The rule this restores: whatever hasSystemFeature() says must agree with
        // what the API actually does. Each string below is claimed ONLY because
        // SensorManager genuinely delivers events for the matching TYPE_*; do not
        // add a line here without checking that first.
        // DERIVED from SensorManager's catalog, never hand-listed here again.
        // This block used to be its own copy of the list, and on 2026-08-23 the
        // catalog dropped MAGNETIC_FIELD / LIGHT / PROXIMITY / PRESSURE /
        // AMBIENT_TEMPERATURE / RELATIVE_HUMIDITY while the copy kept claiming
        // all six -- so hasSystemFeature("...sensor.compass") answered TRUE and
        // getDefaultSensor(TYPE_MAGNETIC_FIELD) then returned null. An app takes
        // its has-compass branch and NPEs. Two commits, each coherent against a
        // different snapshot of the other file, produced a lie neither intended.
        f.addAll(android.hardware.SensorManager.macapkSensorFeatures());
        // FEATURE_FAKETOUCH. AOSP: "All devices that support
        // android.hardware.touchscreen or a sub-feature are presumed to also
        // support faketouch", and its definition ("might use a mouse ... and
        // emulate basic touch pointer events like down, up, drag") is literally
        // how Mac.apk sources those events.
        f.add("android.hardware.faketouch");
        // FEATURE_TOUCHSCREEN_MULTITOUCH (v1.0.1127). AOSP: "The device's touch
        // screen supports multitouch sufficient for basic two-finger gesture
        // detection." Backed by a DEFAULT, host-driven two-pointer path:
        // -[MacApkMetalView magnifyWithEvent:] (cocoa_bridge.mm) is a stock
        // NSResponder callback macOS invokes on any trackpad or Magic Mouse
        // pinch -- no opt-in, no env gate, no user-authored file -- and it feeds
        // TouchDispatcher.dispatchPinch, which emits AOSP's own ordering
        // DOWN(p0) -> POINTER_DOWN(p1) -> MOVE* -> POINTER_UP(p1) -> UP(p0).
        // That is literally ScaleGestureDetector's input contract, i.e. exactly
        // the "basic two-finger gesture detection" the feature names.
        //
        // ⚠ THIS FLAG WAS A STALE DENIAL, and how it went stale is the reusable
        // part. v1.0.939 corrected the REASON while keeping the verdict, on the
        // ground that the only multi-pointer path was a key-bind profile needing
        // a user-authored file, so "no default input path can produce" pinch.
        // That sentence was falsified the same week by the commit that added
        // magnifyWithEvent: -> dispatchPinch, and nothing re-examined the flag,
        // because a capability landing in a SIBLING subsystem does not show up
        // as an inconsistency in either file on its own. Net effect: the input
        // path worked while this flag kept telling every app not to offer pinch
        // -- so pinch-to-zoom stayed unreachable in map, image, PDF and browser
        // apps that gate on hasSystemFeature(), for a reason that no longer
        // existed. Same failure family as the sensor-feature drift above: two
        // files each coherent against a different snapshot of the other.
        f.add("android.hardware.touchscreen.multitouch");
        //
        // Still deliberately NOT declared: the .distinct and .jazzhand
        // refinements. AOSP's bar for _DISTINCT is tracking two or more fingers
        // fully independently; our two synthesized points move in a fixed
        // mirrored span around one scalar magnification, and the key-bind path's
        // pointers sit at fixed bind coordinates rather than freely user-driven
        // positions. Neither is independent tracking, so both stay false --
        // which is also what the golden shows for .distinct.
        //
        // ⚠ KNOWN INCOMPLETENESS, notated rather than hidden: a Mac driven by a
        // plain third-party mouse emits no magnifyWithEvent: and so cannot pinch.
        // The flag is declared statically anyway, matching the camera and
        // microphone precedent a few lines above and AOSP's own model, where a
        // device's feature list is static rather than probed: the typical Mac has
        // a trackpad or a Magic Mouse, and denying the flag costs THAT majority a
        // working gesture, while a mouse-only host merely sees an offered control
        // it cannot drive.
        //
        // FEATURE_SCREEN_LANDSCAPE / FEATURE_SCREEN_PORTRAIT: the runtime takes
        // --orientation landscape|portrait and the window honors both.
        f.add("android.hardware.screen.landscape");
        f.add("android.hardware.screen.portrait");
        // FEATURE_AUDIO_OUTPUT: AudioTrack is real streaming PCM on the macOS
        // CoreAudio engine (runtime/src/audio_backend.mm), so the device does
        // have "at least one form of audio output" per CDD 7.8.
        f.add("android.hardware.audio.output");
        // ---- v1.0.983: two more under-reported, ALREADY-real capabilities ---
        // Found while auditing this exact list against a real device (adb
        // pm list features on the fwprobe-api30-320 AVD) for Aurora Store's
        // device-profile upload. Both follow the same rule as every entry
        // above: claimed only because the backing implementation is real and
        // independently verified, never because claiming it is convenient.
        //
        // FEATURE_WEBVIEW ("android.software.webview"). AOSP's own doc string
        // (frameworks/base core/java/android/content/pm/PackageManager.java):
        // "The device has a full implementation of the android.webkit.* APIs.
        // Devices lacking this feature will not have a functioning WebView
        // implementation." runtime/java/android/webkit/ is not a stub: it is
        // a real WKWebView-backed engine (Current_State.md v1.0.963, "WebView
        // is REAL") that was measured completing TLS, receiving and rendering
        // Google's OWN accounts.google.com/EmbeddedSetup sign-in HTML for the
        // Android WebView user-agent, running evaluateJavaScript, answering a
        // synchronous JS-bridge prompt() from a background thread, and
        // round-tripping cookies through CookieManager -- not a checklist
        // item, an end-to-end page load. Devices "lacking this feature" per
        // AOSP's own wording is exactly what we are not.
        f.add("android.software.webview");
        // ---- Wi-Fi (v1.0.1168) -------------------------------------------
        // FEATURE_WIFI is "the device includes a Wi-Fi hardware component"
        // (AOSP PackageManager). Every Apple Silicon Mac does, ConnectivityManager
        // already models the host's real default route as TRANSPORT_WIFI when
        // it rides that port, and WifiManager answers the state queries. What
        // this row was COSTING while absent: Google Play turns an app's
        // ACCESS_WIFI_STATE / CHANGE_WIFI_* permission into an implied,
        // REQUIRED android.hardware.wifi (developer.android.com uses-feature
        // reference, table "Permissions that imply feature requirements"), so
        // a device whose checked-in profile omits it is told "App not
        // supported" for Minecraft, Discord, Instagram and most of the store
        // (measured through Aurora Store 2026-09-04; MCPE's manifest requests
        // ACCESS_WIFI_STATE + CHANGE_WIFI_MULTICAST_STATE with no uses-feature
        // opt-out). The answer is still PROBED off networksetup's hardware-port
        // table rather than assumed -- see MacApkHostNetwork.hasWifiHardware.
        try {
            if (android.net.ConnectivityManager.macapkHostHasWifiHardware())
                f.add("android.hardware.wifi");
        } catch (Throwable t) {
            f.add("android.hardware.wifi");
        }
        // FEATURE_RAM_NORMAL ("android.hardware.ram.normal"). AOSP's doc
        // string ties it to exactly one call: "The device's
        // ActivityManager.isLowRamDevice() method returns false." That method
        // (runtime/java/android/app/ActivityManager.java) is not hardcoded --
        // it reads the HOST's real total memory via OperatingSystemMXBean and
        // compares against the same 1 GiB threshold AOSP uses. So this is a
        // direct, mechanical mirror of a value we already compute honestly,
        // never an independent guess: whichever way isLowRamDevice() answers,
        // this feature (or its .ram.low sibling) agrees with it by
        // construction, matching AOSP's own get-out-of-sync-proof design
        // (PackageManagerService derives both from the identical method).
        if (!new android.app.ActivityManager().isLowRamDevice()) {
            f.add("android.hardware.ram.normal");
        } else {
            f.add("android.hardware.ram.low");
        }
        SYSTEM_FEATURES = java.util.Collections.unmodifiableSet(f);
    }

    /** AOSP ApplicationPackageManager.hasSystemFeature(String) delegates to the
     *  versioned form with version 0. */
    @Override public boolean hasSystemFeature(String name) {
        return hasSystemFeature(name, 0);
    }

    /** AOSP PackageManagerService.hasSystemFeature(String, int): look the name up
     *  and answer `feat.version >= version` (features are defined to be backwards
     *  compatible, so a newer version satisfies an older request); an unknown name
     *  is false. Every feature this device declares carries FeatureInfo's default
     *  version 0, so a request for any version above 0 is unmet. */
    @Override public boolean hasSystemFeature(String name, int version) {
        if (name == null) return false;
        boolean has = version <= 0 && SYSTEM_FEATURES.contains(name);
        // Guest Vulkan (Boot.noteGuestVulkan / bionic_shim_vulkan.cpp): an app
        // that gets libvulkan.so also sees the features that announce it, with
        // AOSP's version encodings -- vulkan.version is the VK_API_VERSION the
        // driver supports (MoltenVK's ICD reports 1.2.0 = 0x402000), vulkan.level
        // is the hardware level (0 = the baseline feature set). Same source as the
        // shim, so the two answers cannot disagree.
        if (!has && "1".equals(System.getProperty("macapk.guestVulkan"))) {
            if ("android.hardware.vulkan.version".equals(name)) has = version <= 0x402000;
            else if ("android.hardware.vulkan.level".equals(name)) has = version <= 0;
        }
        // v1.0.983: MACAPK_TRACE_DEVICEINFO -- the device-profile counterpart
        // of MACAPK_TRACE_ATTR/MACAPK_TRACE_DEFSTYLE. Added while auditing
        // what Aurora Store's device-properties builder (com.aurora.gplayapi
        // .data.providers.DeviceInfoProvider, reached via Aurora's own
        // NativeDeviceInfoProvider) actually reads, so a future session can
        // see the REAL per-call answers from a run's log instead of
        // re-deriving them from source. Same env-check-then-NativeLog shape
        // as ContextWrapper's getSystemService trace.
        if ("1".equals(System.getenv("MACAPK_TRACE_DEVICEINFO"))) {
            try {
                NativeLog.i("Mac.apk.PM", "hasSystemFeature(\"" + name + "\") -> " + has);
            } catch (Throwable ignored) {}
        }
        return has;
    }

    /** AOSP PackageManagerService.getSystemAvailableFeatures: every available
     *  FeatureInfo, PLUS one extra NAME-LESS entry whose reqGlEsVersion carries
     *  the device's GL ES level (`ro.opengles.version`). Callers that scan the
     *  array for the GL level depend on that trailing null-name entry existing,
     *  so it is not optional. */
    @Override public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        java.util.ArrayList<android.content.pm.FeatureInfo> res =
                new java.util.ArrayList<>(SYSTEM_FEATURES.size() + 1);
        for (String name : SYSTEM_FEATURES) {
            android.content.pm.FeatureInfo fi = new android.content.pm.FeatureInfo();
            fi.name = name;
            res.add(fi);
        }
        android.content.pm.FeatureInfo gl = new android.content.pm.FeatureInfo();
        // Read the level off the SAME source ActivityManager reports through
        // ConfigurationInfo.reqGlEsVersion, so the device can never give two
        // different answers for its own GL level.
        try {
            android.content.pm.ConfigurationInfo ci =
                new android.app.ActivityManager().getDeviceConfigurationInfo();
            if (ci != null) gl.reqGlEsVersion = ci.reqGlEsVersion;
        } catch (Throwable ignored) {
            gl.reqGlEsVersion = android.content.pm.FeatureInfo.GL_ES_VERSION_UNDEFINED;
        }
        res.add(gl);
        if ("1".equals(System.getenv("MACAPK_TRACE_DEVICEINFO"))) {
            try {
                StringBuilder sb = new StringBuilder("getSystemAvailableFeatures() -> [");
                for (android.content.pm.FeatureInfo fi : res) {
                    sb.append(fi.name == null ? ("reqGlEsVersion=0x" + Integer.toHexString(fi.reqGlEsVersion)) : fi.name);
                    sb.append(", ");
                }
                sb.append("]");
                NativeLog.i("Mac.apk.PM", sb.toString());
            } catch (Throwable ignored) {}
        }
        return res.toArray(new android.content.pm.FeatureInfo[0]);
    }

    // ---- Optional shared libraries (v1.0.939) -------------------------------
    //
    // AOSP PackageManager.getSystemSharedLibraryNames(), verbatim
    // (frameworks/base/core/java/android/content/pm/PackageManager.java:5026-5035,
    // android11-release -- @Nullable, abstract):
    //   "Get a list of shared libraries that are available on the system.
    //    @return An array of shared library names that are available on the
    //    system, or null if none are installed."
    // A device assembles that set in SystemConfig from the <library name=...>
    // entries of /system/etc/permissions/*.xml; frameworks/base/data/etc/
    // platform.xml:227-239 contributes android.test.{base,mock,runner} and the
    // two android.hidl.*-V1.0-java entries, and the rest arrive from other
    // permission XMLs on the image.
    //
    // WHY THIS OVERRIDE EXISTS. The base class
    // (runtime/java/android/content/pm/PackageManager.java:709-725) answers with
    // an empty array, on the stated premise that "none of the detachable
    // platform libraries (org.apache.http.legacy, android.test.runner, the
    // shared HIDL/telephony ones) exist here as separately loadable code".
    // That premise is now FALSE for one of them: runtime/java/org/apache/http/
    // carries the Apache HTTP surface (HttpResponse, HttpHost, and the impl /
    // client / entity / message / protocol / params / config / util subtrees).
    // We ship it built into the framework classpath rather than as a detachable
    // jar, but the question this API answers is "can I <uses-library> this", and
    // for a surface already on the classpath the honest answer is yes.
    //
    // So the empty array is the same feature-flag-vs-API contradiction the
    // SYSTEM_FEATURES header above exists to forbid, merely inverted: an app
    // that consults the list concludes org.apache.http is unavailable and takes
    // its fallback path (or refuses to run), while
    // Class.forName("org.apache.http.HttpResponse") succeeds for that same app.
    //
    // The set is PROBED, never hardcoded. Each candidate names a sentinel class
    // and the library name is reported only when that class actually resolves on
    // the framework loader, so the list cannot outlive the code behind it --
    // delete runtime/java/org/apache/http/ and the name disappears by itself --
    // and a platform library added later starts being reported the moment its
    // classes land, instead of being forgotten. initialize=false so probing can
    // never run a <clinit> as a side effect of a metadata query.
    private static final String[][] SHARED_LIBRARY_CANDIDATES = {
        // Removed from the default classpath at API 28; offered ever since as
        // <uses-library android:name="org.apache.http.legacy"/>. Present here.
        { "org.apache.http.legacy", "org.apache.http.HttpResponse" },
        // platform.xml:227-235. runtime/java/android/test/ does not exist, so
        // these three stay unreported until it does.
        { "android.test.base",      "android.test.AndroidTestCase" },
        { "android.test.runner",    "android.test.InstrumentationTestRunner" },
        { "android.test.mock",      "android.test.mock.MockContext" },
        // Bluetooth OBEX. runtime/java/javax/ holds only annotation,
        // microedition and servlet.
        { "javax.obex",             "javax.obex.ClientSession" },
        // v1.0.939. We ship runtime/java/androidx/window/sidecar/ with a real
        // SidecarInterface implementation, and the reference AVD declares this
        // exact library (measured: `cmd package list libraries` lists
        // androidx.window.sidecar and, notably, NOT androidx.window.extensions),
        // so claiming it is both true and what a device says. Jetpack
        // WindowManager reads this list before it reflects on the provider.
        // Landed together with making SidecarProvider's two entry points
        // static, without which androidx could never have used the library we
        // were about to start advertising -- claiming it while it did not link
        // would have been strictly worse than staying quiet.
        { "androidx.window.sidecar", "androidx.window.sidecar.SidecarProvider" },
    };

    /** Probed once per process: the framework's own contents cannot change
     *  under us, and getSystemSharedLibraryNames is a metadata call an app may
     *  make repeatedly. */
    private static volatile String[] sharedLibraryNames;

    /** ⚠ Never null, unlike AOSP's @Nullable contract. The base class already
     *  returned a non-null empty array and fwprobe pins that
     *  (PM.lib.sharedLibraryNamesNotNull matches 1/1); handing back null here
     *  would break a currently-correct answer to fix nothing, and a real device
     *  always has some libraries anyway. */
    @Override public String[] getSystemSharedLibraryNames() {
        String[] cached = sharedLibraryNames;
        if (cached == null) {
            synchronized (MacApkPackageManager.class) {
                cached = sharedLibraryNames;
                if (cached == null) {
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    ClassLoader fw = MacApkPackageManager.class.getClassLoader();
                    for (String[] candidate : SHARED_LIBRARY_CANDIDATES) {
                        try {
                            Class.forName(candidate[1], false, fw);
                            names.add(candidate[0]);
                        } catch (Throwable notPresent) {
                            // Absent or unloadable: do not claim it. This is the
                            // whole point of probing rather than listing.
                        }
                    }
                    cached = names.toArray(new String[0]);
                    sharedLibraryNames = cached;
                }
            }
        }
        // AOSP hands back a fresh array from the binder call each time, so an
        // app that sorts or nulls entries in place cannot corrupt the next
        // caller's answer. Copy to keep that property.
        if ("1".equals(System.getenv("MACAPK_TRACE_DEVICEINFO"))) {
            try {
                NativeLog.i("Mac.apk.PM",
                    "getSystemSharedLibraryNames() -> " + java.util.Arrays.toString(cached));
            } catch (Throwable ignored) {}
        }
        return cached.clone();
    }

    /**
     * The same probed set as getSystemSharedLibraryNames, as SharedLibraryInfo.
     *
     * ⚠ ONE SOURCE, TWO SHAPES -- deliberately built by calling
     * getSystemSharedLibraryNames() rather than re-walking
     * SHARED_LIBRARY_CANDIDATES. AOSP answers both from the same
     * PackageManagerService table, so an app that scans names and an app that
     * scans infos see the same world; deriving these independently is exactly
     * how the two drift into contradicting each other, which is the mistake
     * getInstallerPackageName/getInstallSourceInfo made for ~700 versions
     * (see the note directly below this method).
     *
     * TYPE_BUILTIN, VERSION_UNDEFINED: every library we report lives in the
     * framework jar on the classpath, which is precisely AOSP's definition of
     * builtin ("a part of the platform ... declared in platform.xml"), and
     * AOSP states builtin libraries cannot be versioned. getPath() stays null
     * rather than pointing at android-framework.jar -- our jar is not a
     * per-library code path, and inventing one would let a caller open it and
     * find a whole framework where it expected one library.
     *
     * WHY THIS EXISTS (2026-08-19): Aurora Store's InstallerStatusReceiver
     * calls this while handling the install-status broadcast. Without it the
     * receiver died on NoSuchMethodError AFTER a fully successful install --
     * helper exit 0, status=0 INSTALL_SUCCEEDED, real icon staged -- and never
     * reached the code that records the app as installed, so the store showed
     * "installing" forever. A missing method on the SUCCESS path is invisible
     * until something finally walks it: this only became reachable once
     * component-less broadcasts started reaching manifest receivers
     * (docs/install-completion-contract_2026-08-18.md).
     */
    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int flags) {
        String[] names = getSystemSharedLibraryNames();
        java.util.List<android.content.pm.SharedLibraryInfo> out =
            new java.util.ArrayList<>(names.length);
        for (String n : names) {
            out.add(new android.content.pm.SharedLibraryInfo(
                        n, android.content.pm.SharedLibraryInfo.TYPE_BUILTIN));
        }
        if ("1".equals(System.getenv("MACAPK_TRACE_DEVICEINFO"))) {
            try {
                NativeLog.i("Mac.apk.PM", "getSharedLibraries(" + flags + ") -> " + out);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * NULL -- we have no package installer, and we say so consistently.
     *
     * ⚠ v1.0.938, OWNER DECISION. This returned "com.android.vending" since
     * v1.0.221 while `getInstallSourceInfo().getInstallingPackageName()` returned
     * null, so we answered ONE question TWO ways. AOSP cannot: PackageManagerService
     * reads both from the same `installSource.installerPackageName`
     * (:21228-21242 and :21249-21328 at android11-release, the latter passing that
     * very variable into the InstallSourceInfo constructor), and the SDK marks
     * getInstallerPackageName `@Deprecated  use getInstallSourceInfo(String)`.
     * We also threw NameNotFoundException for "com.android.vending" itself -- a
     * load-bearing, settled part of the GMS device contract -- so we were claiming
     * to be installed by a package we simultaneously reported as missing.
     * `changelog.md:8681` already listed this among the four contradictory answers
     * that made GMS behaviour a coin-flip before v847.
     *
     * ⚠ WHY IT WAS "com.android.vending", and why that is no longer the answer:
     * v1.0.221 changed it from null because AppsFlyer's obfuscated AppsFlyerLib
     * dereferences the result with no null check -> SIGSEGV (null+0x10) on a
     * background thread, which took down HCR 1.43 on the arm64 path. But null is a
     * LEGAL AOSP value that every app must already handle (the javadoc: "Null may
     * be returned if the app was not installed by a package (e.g. a system app or
     * an app installed via adb)"), so an SDK crashing on it is the SDK mishandling
     * a legal value. Fabricating an installer identity to keep it alive is the
     * kind of lie the no-shortcuts rule exists to prevent.
     *
     * The v1.0.221 crash was re-tested before this landed -- see the v1.0.938
     * session entry for the measured result. If it ever returns, fix it at its own
     * site (survive the deref) rather than reinstating the fabricated value.
     *
     * (NB: this value is NOT what gates Minecraft Bedrock's boot -- MC creates its
     * GooglePlayStore + BillingClient regardless and the billing fast-fails
     * cleanly; that blocker was an un-neutralized stack canary, fixed separately.)
     */
    @Override public String  getInstallerPackageName(String packageName) {
        return null;
    }

    @Override public ServiceInfo getServiceInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        // AOSP: throw NameNotFoundException if the component's class is
        // not a declared service. The component's package is allowed
        // to mismatch ours -- Firebase ComponentDiscovery uses
        // `new ComponentName(context, ComponentDiscoveryService.class)`
        // which fills in the app package automatically -- but the
        // class name must be a service we know about.
        if (component == null) throw new NameNotFoundException();
        String cls = component.getClassName();
        Manifest.ServiceEntry entry = services.get(cls);
        if (entry == null) {
            throw new NameNotFoundException(component.flattenToString());
        }
        // THE TRAP THIS GATE MUST NOT FALL INTO. A naive "throw if
        // entry.enabled is false" would break the metadata-holder pattern in
        // 16+ corpus apps, whose whole design is a DELIBERATELY DISABLED
        // service read with MATCH_DISABLED_COMPONENTS:
        //   androidx.appcompat.app.AppLocalesMetadataHolderService (deskclock,
        //     anki, newpipe, youtube), androidx.camera MetadataHolderService
        //     (aegis, thunderbird, signal), androidx.car.app
        //     CarAppMetadataHolderService (organicmaps, telegram),
        //   com.google.android.gms.metadata.ModuleDependencies (reddit),
        //   com.google.android.build.data.PropertiesServiceHolder.
        // Their failure mode is SILENT -- AppCompat catches
        // NameNotFoundException and falls back to autoStoreLocales=false -- so
        // it would not surface as a crash. macapkIsComponentEnabled returns
        // true for MATCH_DISABLED_COMPONENTS as its very first branch, which is
        // why the flag constants had to be declared in the same change.
        if (macapkComponentGating()
                && !macapkIsComponentEnabled(cls, entry.enabled, flags)) {
            throw new NameNotFoundException(component.flattenToString());
        }
        ServiceInfo info = new ServiceInfo();
        // ⚠ v1.0.940: applicationInfo MUST be attached. ComponentInfo.isEnabled()
        // is `enabled && applicationInfo.enabled` -- an UNCONDITIONAL deref, as
        // it is on AOSP, which is safe there only because PackageManagerService
        // always attaches one. Leaving it null made serviceInfo.isEnabled() and
        // providerInfo.isEnabled() throw NPE, and put null-bearing objects into
        // PackageInfo.services / .providers for getPackageInfo(GET_SERVICES).
        // toActivityInfo already did this; these two were simply missed.
        info.applicationInfo = makeAppInfo(flags);
        info.name        = entry.name;
        info.packageName = this.packageName;
        info.enabled     = entry.enabled;
        info.exported    = entry.exported;
        if ((flags & GET_META_DATA) != 0 && !entry.metaData.isEmpty()) {
            android.os.Bundle md = new android.os.Bundle();
            for (Map.Entry<String, String> e : entry.metaData.entrySet()) {
                putTypedMeta(md, e.getKey(), e.getValue(),
                             entry.metaDataTypes.get(e.getKey()));
            }
            info.metaData = md;
        }
        return info;
    }

    @Override public ProviderInfo getProviderInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        // Symmetric to getServiceInfo above. Real Android resolves
        // through PackageManagerService; we look up the class name in
        // the manifest-derived provider map. androidx.startup is the
        // canonical caller -- InitializationProvider.onCreate does:
        //   getContext().getPackageManager().getProviderInfo(
        //     new ComponentName(getContext().getPackageName(),
        //                       InitializationProvider.class.getName()),
        //     GET_META_DATA).metaData
        // and iterates the resulting Bundle to enumerate Initializers.
        if (component == null) throw new NameNotFoundException();
        String cls = component.getClassName();
        Manifest.ProviderEntry entry = providers.get(cls);
        if (entry == null) {
            throw new NameNotFoundException(component.flattenToString());
        }
        if (macapkComponentGating()
                && !macapkIsComponentEnabled(cls, entry.enabled, flags)) {
            throw new NameNotFoundException(component.flattenToString());
        }
        ProviderInfo info = new ProviderInfo();
        // ⚠ v1.0.940: applicationInfo MUST be attached. ComponentInfo.isEnabled()
        // is `enabled && applicationInfo.enabled` -- an UNCONDITIONAL deref, as
        // it is on AOSP, which is safe there only because PackageManagerService
        // always attaches one. Leaving it null made serviceInfo.isEnabled() and
        // providerInfo.isEnabled() throw NPE, and put null-bearing objects into
        // PackageInfo.services / .providers for getPackageInfo(GET_SERVICES).
        // toActivityInfo already did this; these two were simply missed.
        info.applicationInfo = makeAppInfo(flags);
        info.name        = entry.name;
        info.authority   = entry.resolvedAuthorities(null);
        info.packageName = this.packageName;
        // The MANIFEST value, never the runtime-resolved one. AOSP puts the
        // manifest value in the info object (PackageInfoUtils.java:829) and applies
        // the setComponentEnabledSetting override at QUERY time -- copying the
        // effective state in here would break WorkManager, which reads
        // getInfo(name, MATCH_DISABLED_COMPONENTS).enabled specifically to learn the
        // manifest default (PackageManagerHelper.kt:49-125). Until v1.1.0 this was
        // never assigned at all, so it kept ComponentInfo's `= true` default and
        // providerInfo.isEnabled() lied for the two corpus providers that declare
        // android:enabled="false".
        info.enabled     = entry.enabled;
        info.exported    = entry.exported;
        info.initOrder   = entry.initOrder;
        info.grantUriPermissions = entry.grantUriPermissions;
        macapkFillProviderPermissions(info, entry);
        if ((flags & GET_META_DATA) != 0 && !entry.metaData.isEmpty()) {
            android.os.Bundle md = new android.os.Bundle();
            for (Map.Entry<String, String> e : entry.metaData.entrySet()) {
                putTypedMeta(md, e.getKey(), e.getValue(),
                             entry.metaDataTypes.get(e.getKey()));
            }
            info.metaData = md;
        }
        return info;
    }

    // Resolve a provider by AUTHORITY (not class name). androidx FileProvider
    // does getPackageManager().resolveContentProvider(authority, flags) during
    // attachInfo. Android allows multiple ';'-separated authorities per
    // provider. Returns null when nothing serves the authority (AOSP contract).
    @Override public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (authority == null) return null;
        for (Manifest.ProviderEntry entry : providers.values()) {
            final String authorities = entry.resolvedAuthorities(null);
            if (authorities == null) continue;
            boolean match = false;
            for (String a : authorities.split(";")) {
                if (authority.equals(a.trim())) { match = true; break; }
            }
            if (!match) continue;
            // AOSP resolves providers through queryContentProviders, gated the
            // same way as the by-class getters.
            if (macapkComponentGating()
                    && !macapkIsComponentEnabled(entry.name, entry.enabled, flags)) {
                continue;
            }
            ProviderInfo info = new ProviderInfo();
            // ⚠ v1.0.940: applicationInfo MUST be attached. ComponentInfo.isEnabled()
            // is `enabled && applicationInfo.enabled` -- an UNCONDITIONAL deref, as
            // it is on AOSP, which is safe there only because PackageManagerService
            // always attaches one. Leaving it null made serviceInfo.isEnabled() and
            // providerInfo.isEnabled() throw NPE, and put null-bearing objects into
            // PackageInfo.services / .providers for getPackageInfo(GET_SERVICES).
            // toActivityInfo already did this; these two were simply missed.
            info.applicationInfo = makeAppInfo(flags);
            info.name        = entry.name;
            info.authority   = authorities;
            info.packageName = this.packageName;
            // The MANIFEST value, never the runtime-resolved one. AOSP puts the
            // manifest value in the info object (PackageInfoUtils.java:829) and applies
            // the setComponentEnabledSetting override at QUERY time -- copying the
            // effective state in here would break WorkManager, which reads
            // getInfo(name, MATCH_DISABLED_COMPONENTS).enabled specifically to learn the
            // manifest default (PackageManagerHelper.kt:49-125). Until v1.1.0 this was
            // never assigned at all, so it kept ComponentInfo's `= true` default and
            // providerInfo.isEnabled() lied for the two corpus providers that declare
            // android:enabled="false".
            info.enabled     = entry.enabled;
            info.exported    = entry.exported;
            info.initOrder   = entry.initOrder;
            info.grantUriPermissions = entry.grantUriPermissions;
            macapkFillProviderPermissions(info, entry);
            if ((flags & GET_META_DATA) != 0 && !entry.metaData.isEmpty()) {
                android.os.Bundle md = new android.os.Bundle();
                for (Map.Entry<String, String> e : entry.metaData.entrySet()) {
                    putTypedMeta(md, e.getKey(), e.getValue(),
                                 entry.metaDataTypes.get(e.getKey()));
                }
                info.metaData = md;
            }
            return info;
        }
        return null;
    }

    /** AOSP PackageInfoUtils.generateApplicationInfo takes the CALLER'S FLAGS and
     *  only fills the flag-gated fields the caller asked for. Every internal
     *  caller here passes the flags it was given, exactly as
     *  generateActivityInfo/generatePackageInfo pass theirs down.
     *
     *  ⚠ GET_META_DATA is the one that changes an observable answer:
     *  ApplicationInfo.metaData is NULL unless the caller asked for it. We used
     *  to populate it unconditionally, which is why
     *  `context.getApplicationInfo().metaData` was non-null here and null on a
     *  device -- see Application.getApplicationInfo for the other half of that
     *  divergence. Every SDK that reads application meta-data already passes
     *  GET_META_DATA (it has to, to work on Android at all), so honouring the
     *  flag costs those callers nothing and stops us handing an app a different
     *  ApplicationInfo shape than the one it was written against. */
    private ApplicationInfo makeAppInfo(int flags) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName      = this.packageName;
        info.processName      = this.packageName;
        info.minSdkVersion    = this.minSdk;
        info.targetSdkVersion = this.targetSdk;
        // `<application android:enabled>`. This field defaulted to true and was
        // never assigned, which made the application half of
        // `ComponentInfo.isEnabled() == enabled && applicationInfo.enabled`
        // structurally unreachable -- a package could not be disabled no matter
        // what it declared. MEASURED over the 64-package corpus: zero apps
        // declare it, so this changes no behaviour today; it exists so the
        // first app that does declare it is answered correctly.
        {
            Manifest m = manifest();
            info.enabled = (m == null) || m.applicationEnabled;
        }
        // AOSP reports the INSTALLED apk's absolute path. Ours is whatever path
        // the launcher was handed, which can be relative -- and a relative
        // sourceDir breaks every consumer that resolves it from a different
        // working directory than the one the runtime started in (a native
        // engine mmap'ing the APK, a ZipFile opened on a worker thread).
        // baseApkPath() additionally gives it the installed SHAPE; see its
        // header. publicSourceDir aliases it, which is AOSP's own answer for a
        // package that is not forward-locked (ApplicationInfo.java:853-858).
        info.sourceDir        = baseApkPath();
        info.publicSourceDir  = info.sourceDir;
        // MACAPK_DATADIR_DEVICE (EXPERIMENT): sourceDir is <appDir>/base.apk and appDir is the
        // macOS ".macapk/code/<pkg>_<ver>"; a guest (pairip) that reads sourceDir and dirnames it
        // gets that macOS code-dir, failing the /data/app path check. Present the device-faithful
        // /data/app/.../base.apk instead (real APK access is unaffected in this experiment run).
        if (System.getenv("MACAPK_DATADIR_DEVICE") != null && packageName != null) {
            info.sourceDir = "/data/app/~~m4c4pk0Zx9AbC1dEfGhIjw==/" + packageName
                             + "-Qm4c4pk0Zx9AbC1dEfGh8Q==/base.apk";
            info.publicSourceDir = info.sourceDir;
        }
        // v1.0.1586: the INSTALLED SPLITS. AOSP (android11-release):
        //   ApplicationInfo.java:863-877 -- splitNames "The names of all installed
        //   split APKs, ordered lexicographically."; splitSourceDirs "Full paths to
        //   zero or more split APKs, indexed by the same order as splitNames";
        //   splitPublicSourceDirs likewise (same as splitSourceDirs unless
        //   forward-locked, which nothing is any more).
        //   ParsingPackageImpl.java:917 `appInfo.splitNames = splitNames;`
        //   :933-934 `appInfo.setSplitCodePaths(splitCodePaths);
        //             appInfo.setSplitResourcePaths(splitCodePaths);`
        //   (ApplicationInfo.java:2225,2228: plain field assignments) -- and
        //   ParsingPackageUtils.java:285-286 calls asSplit() only when the
        //   package HAS splits, so a package without any keeps the fields NULL.
        // NULL-vs-EMPTY is load-bearing here beyond fidelity: v1.0.471 measured
        // that EMPTY arrays trap Crossy's older libunity right after
        // getApplicationInfo (guest brk), while null does not -- and a real
        // list is what that same libunity reads on a device, where Crossy is
        // a split install. installedSplits() answers null for a plain apk and
        // for the legacy `_merged.apk` (no siblings), so both those stay
        // byte-identical to before.
        // The paths are the REAL installed files (<root>/data/app/~~<nonce>/
        // <pkg>-<nonce>/split_<name>.apk), absolute, readable, under the
        // device file names -- what Crashlytics-NDK (JniNativeApi reads
        // splitSourceDirs), Play Core's asset-pack lookup ("split_<pack>.apk")
        // and Unity's PAD glue open. Under MACAPK_DATADIR_DEVICE they are
        // presented under the same device install dir as sourceDir above; the
        // bionic remap resolves that dir to the real one, and the file names
        // are identical either way.
        {
            SplitSet ss = installedSplits();
            if (ss != null) {
                info.splitNames = ss.names.clone();
                String[] dirs;
                if (System.getenv("MACAPK_DATADIR_DEVICE") != null && packageName != null) {
                    String inst = "/data/app/~~m4c4pk0Zx9AbC1dEfGhIjw==/" + packageName
                                  + "-Qm4c4pk0Zx9AbC1dEfGh8Q==";
                    dirs = new String[ss.files.length];
                    for (int i = 0; i < dirs.length; i++) dirs[i] = inst + "/" + ss.files[i];
                } else {
                    dirs = ss.paths.clone();
                }
                info.splitSourceDirs       = dirs;
                // AOSP hands one array object to both setters; equal contents
                // is the observable part. Fresh arrays per call, so an app
                // that mutates the record cannot poison our cache.
                info.splitPublicSourceDirs = dirs.clone();
            }
        }
        // MACAPK_DATADIR_DEVICE (default OFF, EXPERIMENT 2026-09-11): pairip walks its paths and
        // memcmp's each against the Android /data/* set (+0x2ef64 -> buf[0x437] -> ...-> the
        // o=25971 wall). Our dataDir is the macOS ".macapk/code/<pkg>_<ver>" appDir, under no
        // /data prefix, so the check fails. Present the device-faithful /data/data/<pkg> instead
        // (the file remap already maps /data/... to the android root, so storage is unaffected).
        // v1.0.1641: the app's DATA dir (Boot -> Application.macapkSetDataDir).
        // `dataDir` (this class's constructor argument) is the CODE dir, which is
        // still right for our own bookkeeping below it (installTimeMillis, the
        // published base.apk link, the _apkt manifest) but was wrong to REPORT:
        // AOSP's ApplicationInfo.dataDir is the directory getDataDir() returns.
        String ddir = reportedDataDir != null ? reportedDataDir : dataDir;
        if (System.getenv("MACAPK_DATADIR_DEVICE") != null && packageName != null)
            ddir = "/data/data/" + packageName;
        info.dataDir          = ddir;
        // AOSP always sets these; an app that opts into direct-boot storage
        // reads them, and a null is a NullPointerException in the caller rather
        // than a graceful "no such storage".
        //
        // DELIBERATE DIVERGENCE, and it is honest: on a device these are three
        // DIFFERENT directories (/data/user/0/<pkg>, /data/user_de/0/<pkg>)
        // because file-based encryption splits credential- from device-
        // protected storage. Mac.apk has exactly one storage area -- which is
        // also what Context.isDeviceProtectedStorage() (false) and
        // Context.createDeviceProtectedStorageContext() (returns `this`) already
        // say -- so pointing all three at the same real directory is the answer
        // that agrees with the rest of the framework. Splitting them would need
        // the Context side to split too, or an app would write through the
        // device-protected Context and read back through a path that does not
        // contain the file.
        info.deviceProtectedDataDir     = ddir;
        info.credentialProtectedDataDir = ddir;
        // NOT the raw loader value -- see nativeLibraryPath(): on a device this
        // directory lives under the INSTALL tree, never under the data tree.
        info.nativeLibraryDir = nativeLibraryPath();
        // MACAPK_DATADIR_DEVICE (EXPERIMENT): also present nativeLibraryDir under /data/app so a
        // guest (pairip) that reads it and strips "/lib/arm64" gets the /data/app-shaped code dir
        // its path-fidelity check wants. Lib LOADING uses AppClassLoader.findLibrary(appDir), not
        // this reported value, so overriding the report does not break dlopen. 2026-09-11.
        if (System.getenv("MACAPK_DATADIR_DEVICE") != null && packageName != null)
            info.nativeLibraryDir = "/data/app/~~m4c4pk0Zx9AbC1dEfGhIjw==/" + packageName
                                    + "-Qm4c4pk0Zx9AbC1dEfGh8Q==/lib/arm64";
        // AOSP: `<application android:taskAffinity>` defaults to the package
        // name (PackageParser: `String str = sa.getNonConfigurationString(
        // ..._taskAffinity, 0); ai.taskAffinity = buildTaskAffinityName(
        // ai.packageName, ai.packageName, str, outError)`). ActivityInfo's own
        // taskAffinity already defaults this way here.
        String affinity = appAttr("taskAffinity");
        info.taskAffinity = (affinity != null && !affinity.isEmpty())
            ? affinity : this.packageName;
        info.permission              = emptyToNull(appAttr("permission"));
        info.manageSpaceActivityName = emptyToNull(appAttr("manageSpaceActivity"));
        info.backupAgentName         = emptyToNull(appAttr("backupAgent"));
        info.appComponentFactory     = emptyToNull(appAttr("appComponentFactory"));
        info.descriptionRes          = resolveResRef(appAttr("description"), "string");
        // StorageManager.UUID_DEFAULT -- the UUID of internal ("private")
        // storage, which is the volume every Mac.apk app's data lives on.
        // AOSP frameworks/base core/java/android/os/storage/StorageManager.java:
        //   public static final UUID UUID_DEFAULT =
        //       UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");
        info.storageUuid = UUID_DEFAULT;
        info.flags = applicationFlags();
        // v1.0.861: carry `<application android:label>`. AOSP's
        // ComponentInfo.loadLabel falls back to the APPLICATION's label when a
        // component declares none, and most activities declare none -- so with
        // this unset every such activity reported its CLASS NAME as its title
        // (opensudoku's action bar read "TitleScreenActivity" where the emulator
        // reads "Open Sudoku"). Same resource-ref-vs-literal split AOSP uses for
        // any PackageItemInfo label, mirroring the per-activity path above.
        try {
            Manifest m = manifest();
            // AOSP ApplicationInfo.className is `<application android:name>`,
            // fully qualified. Manifest already expands a leading '.' against
            // the package name, so this is the same string ActivityThread would
            // instantiate the Application from.
            info.className = emptyToNull((m != null) ? m.applicationClass : null);
            String raw = (m != null) ? m.applicationLabel : null;
            if (raw != null && !raw.isEmpty()) {
                if (raw.startsWith("@")) info.labelRes = resolveResRef(raw, "string");
                else                     info.nonLocalizedLabel = raw;
            }
            // AOSP ApplicationInfo.theme is `<application android:theme>` as a
            // resource id; ActivityInfo.getThemeResource falls back to it for
            // a theme-less <activity> (android11-release ActivityInfo.java:
            // 1033-1035), and ActivityHost applies the result before onCreate
            // the way ActivityThread.performLaunchActivity does. Manifest
            // stores the value stripped of its "@style/" prefix (see
            // Manifest.applicationTheme's doc), so re-add the sigil for
            // resolveResRef; a binary-manifest "@0x7f..." spelling arrives
            // with its '@' intact and passes through unchanged.
            String appTheme = (m != null) ? m.applicationTheme : null;
            if (appTheme != null && !appTheme.isEmpty()) {
                info.theme = resolveResRef(
                    appTheme.charAt(0) == '@' ? appTheme : "@style/" + appTheme,
                    "style");
            }
            // AOSP `<application android:icon>` as a resource id, so
            // getApplicationIcon / loadIcon can resolve the app's real icon
            // instead of always falling back to the platform default.
            String appIcon = appAttr("icon");
            if (appIcon != null) info.icon = resolveResRef(appIcon, "drawable");
        } catch (Throwable ignored) {}
        // GET_META_DATA gate: see makeAppInfo's header. MACAPK_PM_ALWAYS_METADATA=1
        // restores the pre-v1.0.923 unconditional Bundle for A/B if an app turns
        // out to depend on the old, non-Android shape.
        boolean wantMeta = (flags & GET_META_DATA) != 0
            || "1".equals(System.getenv("MACAPK_PM_ALWAYS_METADATA"));
        if (wantMeta && applicationMetaData != null
                && !applicationMetaData.isEmpty()) {
            android.os.Bundle md = new android.os.Bundle();
            for (java.util.Map.Entry<String, String> e
                     : applicationMetaData.entrySet()) {
                putTypedMeta(md, e.getKey(), e.getValue(),
                             applicationMetaDataTypes.get(e.getKey()));
            }
            info.metaData = md;
        }
        return info;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    // ---- the installed split set (v1.0.1586) ---------------------------------
    //
    // `apkPath` is the installed base.apk (v1.0.1585: <root>/data/app/~~<nonce>/
    // <pkg>-<nonce>/base.apk) and the split_*.apk beside it are the rest of the
    // app; InstalledSplits derives them, base first, sorted by name -- the
    // order AOSP's ApkLiteParseUtils.parseClusterPackageLite:166-194 imposes
    // ("Always apply deterministic ordering based on splitName", then
    // splitCodePaths[i] / splitRevisionCodes[i] indexed the same way). Until
    // the C++ merge is deleted a bundle arrives as the legacy `_merged.apk`,
    // which has no siblings, so this answers null for it exactly as for a
    // plain apk.
    //
    // Computed once: the set cannot change under a running process (an
    // update is a new install dir), and getApplicationInfo is a hot path.
    private static final class SplitSet {
        /** AOSP split names, from the device file names. */
        final String[] names;
        /** Absolute paths of the split files, parallel to names. */
        final String[] paths;
        /** Device file names (`split_<name>.apk`), parallel to names. */
        final String[] files;
        /** Each split manifest's android:revisionCode (0 when absent). */
        final int[] revisionCodes;
        SplitSet(String[] names, String[] paths, String[] files, int[] revisionCodes) {
            this.names = names; this.paths = paths; this.files = files;
            this.revisionCodes = revisionCodes;
        }
    }
    private volatile SplitSet cachedSplits;
    private volatile boolean splitsTried;
    private volatile int cachedBaseRevisionCode;

    /** The installed splits of THIS package, or null when there are none
     *  (the AOSP value: ParsingPackageUtils.java:285-286 only calls
     *  asSplit() for a package that has some). Never throws. */
    private SplitSet installedSplits() {
        if (splitsTried) return cachedSplits;
        synchronized (this) {
            if (splitsTried) return cachedSplits;
            SplitSet ss = null;
            try {
                java.util.List<java.io.File> set = InstalledSplits.of(apkPath);
                if (set.size() > 1) {
                    int n = set.size() - 1;
                    String[] names = new String[n];
                    String[] paths = new String[n];
                    String[] files = new String[n];
                    int[] revs = new int[n];
                    for (int i = 0; i < n; i++) {
                        java.io.File f = set.get(i + 1);
                        names[i] = InstalledSplits.splitName(f);
                        paths[i] = f.getAbsolutePath();
                        files[i] = f.getName();
                        revs[i]  = readRevisionCode(f);
                    }
                    ss = new SplitSet(names, paths, files, revs);
                }
                if (!set.isEmpty()) cachedBaseRevisionCode = readRevisionCode(set.get(0));
            } catch (Throwable t) {
                // The split fields are additive metadata; a listing failure
                // degrades to "no splits", never to a broken PackageManager.
                try {
                    NativeLog.w("Mac.apk.PM", "installed split set unreadable (" + t
                        + ") -- reporting no splits");
                } catch (Throwable ignored) {}
                ss = null;
            }
            cachedSplits = ss;
            splitsTried = true;
            return ss;
        }
    }

    /** `<manifest android:revisionCode>` of one apk of the set, 0 when absent
     *  or unreadable -- the value ApkLiteParseUtils.parseApkLite:335-336 reads
     *  (`case "revisionCode": revisionCode = attrs.getAttributeIntValue(i, 0)`)
     *  and parseClusterPackageLite:192 stores per split. Almost always 0; read
     *  rather than assumed because the field is reported. */
    private static int readRevisionCode(java.io.File apk) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) return 0;
            byte[] axml;
            try (java.io.InputStream in = zf.getInputStream(e)) { axml = in.readAllBytes(); }
            org.w3c.dom.Element root = BinaryXmlParser.parse(axml).getDocumentElement();
            if (root == null) return 0;
            String rc = root.getAttributeNS(ANDROID_NS, "revisionCode");
            if (rc == null || rc.isEmpty()) return 0;
            return Integer.parseInt(rc.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Split fields for an ApplicationInfo of ANOTHER installed package (a
     *  registry row): its recorded apkPath is that package's installed
     *  base.apk since v1.0.1585, so the same sibling rule applies. Fills
     *  only what is unset; null stays null for a package without splits. */
    private static void fillSplitFields(ApplicationInfo ai, PackageInfo pi, String baseApk) {
        if (baseApk == null || baseApk.isEmpty()) return;
        try {
            java.util.List<java.io.File> set = InstalledSplits.of(baseApk);
            if (set.size() <= 1) return;
            int n = set.size() - 1;
            String[] names = new String[n];
            String[] paths = new String[n];
            int[] revs = new int[n];
            for (int i = 0; i < n; i++) {
                java.io.File f = set.get(i + 1);
                names[i] = InstalledSplits.splitName(f);
                paths[i] = f.getAbsolutePath();
                revs[i]  = readRevisionCode(f);
            }
            if (ai != null) {
                if (ai.splitNames == null)            ai.splitNames = names.clone();
                if (ai.splitSourceDirs == null)       ai.splitSourceDirs = paths.clone();
                if (ai.splitPublicSourceDirs == null) ai.splitPublicSourceDirs = paths.clone();
            }
            if (pi != null) {
                if (pi.splitNames == null)         pi.splitNames = names;
                if (pi.splitRevisionCodes == null) pi.splitRevisionCodes = revs;
            }
        } catch (Throwable ignored) {
            // Additive metadata for a foreign package; never worth a throw.
        }
    }

    // ---- sourceDir / publicSourceDir: the installed base.apk shape (v1.0.939)
    //
    // AOSP ApplicationInfo.sourceDir is "Full path to the base APK for this
    // application" (frameworks/base/core/java/android/content/pm/
    // ApplicationInfo.java:848-851, android11-release), and the installer always
    // lands that file at <install-dir>/base.apk -- so on a device the LAST
    // COMPONENT of sourceDir is literally "base.apk". We reported the dropped
    // APK's own path, which fails that shape (fwprobe
    // PM.aipath.rel.{source,publicSource}EndsWithBaseApk, 2 rows) and, more
    // usefully, is not STABLE: engines mmap the APK through
    // getApplicationInfo().sourceDir, and a path the user chose can be moved,
    // renamed or deleted between runs -- or during one.
    //
    // So publish a link at <dataDir>/base.apk and report THAT.
    //
    // A HARD link is preferred over a symlink because it is a second directory
    // entry for the same inode: the bytes stay readable for the whole run even
    // if the dropped APK is moved or deleted underneath us, which is exactly the
    // failure a mid-run mmap cannot survive. A symlink is the cross-volume
    // fallback (createLink fails with EXDEV when the app dir and the APK sit on
    // different filesystems, which is the normal case for an APK on an external
    // disk).
    //
    // WHERE THIS BELONGS. The natural home is the installer that already
    // materialises the app dir (runtime/src/main.cpp) -- this is install-time
    // work, not a metadata query. It lives here because the PM is the only
    // component in this change's scope that knows both paths; if the extraction
    // side ever grows a link step, delete this and read the path instead.
    // Cost is bounded regardless: one filesystem round trip per process, since
    // Application.java:535-550 builds exactly one PM and caches it.
    //
    // CONSERVATIVE GATES, each closing a way this could report something WORSE
    // than the plain path:
    //  - Only when the source already ends in ".apk". A .xapk / .apkm split
    //    BUNDLE is not a base APK, and naming one "base.apk" would tell an
    //    extension-sniffing engine a lie the honest path did not. This also
    //    keeps PM.aipath.rel.sourceEndsWithApk (matching today) matching.
    //  - Never delete a destination that IS the source. If a user drops an APK
    //    that already lives at <dataDir>/base.apk, deleting it to "publish a
    //    link" would destroy their file. Files.isSameFile is the check, and it
    //    follows symlinks so a link left by an earlier launch is recognised too.
    //  - Only if the result really is a readable file, because fwprobe pins
    //    PM.aipath.sourceDir.{exists,isDirectory,canRead} and a path we cannot
    //    verify must not be reported.
    //  - Every failure returns the absolute source path -- exactly today's
    //    answer. This can degrade to the status quo, never below it.
    private volatile String  baseApkLink;
    private volatile boolean baseApkLinkTried;

    private String baseApkPath() {
        String abs = absolutePath(apkPath);
        // v1.0.1586: the day the header above anticipated. Since v1.0.1585 the
        // runtime installs every app at <root>/data/app/~~<nonce>/<pkg>-<nonce>/
        // base.apk (apk_unpacker.cpp EnsureInstalledPackage) and hands Boot THAT
        // path -- already stable, already ending in base.apk, and a SIBLING of
        // the split_*.apk that splitSourceDirs reports, exactly the device's
        // layout. Report it as-is; publishing a second name for it under the
        // code dir would only put sourceDir and splitSourceDirs in different
        // directories, which no device does. The link path below stays for a
        // bare invocation whose --apk never went through the installer.
        if (InstalledSplits.isInstalledBase(abs)) return abs;
        if (baseApkLinkTried) return baseApkLink != null ? baseApkLink : abs;
        synchronized (this) {
            if (!baseApkLinkTried) {
                baseApkLink = publishBaseApkLink(abs);
                baseApkLinkTried = true;
            }
            return baseApkLink != null ? baseApkLink : abs;
        }
    }

    /** Written next to the link the first time we publish one, so a later launch
     *  can tell OUR base.apk from somebody else's. Follows the existing
     *  `.macapk_apk_fingerprint` dotfile convention in the same directory. */
    private static final String BASE_APK_MARKER = ".macapk_base_apk_link";

    /** Returns the published <dataDir>/base.apk path, or null to keep `abs`. */
    private String publishBaseApkLink(String abs) {
        if (abs == null || abs.isEmpty() || dataDir == null || dataDir.isEmpty()) {
            return null;
        }
        // A split bundle is not a base APK -- see the header. Measured on the
        // 2026-08-10 corpus: 10 of 62 inputs are .apkm/.xapk bundles whose own
        // FIRST MEMBER is named base.apk, so this gate is load-bearing, not
        // hypothetical.
        if (!abs.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) return null;
        try {
            java.io.File src = new java.io.File(abs);
            if (!src.isFile()) return null;
            java.io.File dst    = new java.io.File(dataDir, "base.apk");
            java.io.File marker = new java.io.File(dataDir, BASE_APK_MARKER);
            java.nio.file.Path srcPath = src.toPath();
            java.nio.file.Path dstPath = dst.toPath();
            if (java.nio.file.Files.exists(dstPath)) {
                if (java.nio.file.Files.isSameFile(dstPath, srcPath)) {
                    // Already the installed shape: either the user dropped an APK
                    // that literally lives there, or our own link from an earlier
                    // launch still points at the same file. Nothing to rebuild,
                    // and deleting would destroy the former.
                    return (dst.isFile() && dst.canRead()) ? dst.getAbsolutePath() : null;
                }
                // Different file. Replace it ONLY if we are the one who put it
                // there. An APK that ships `assets/base.apk` extracts to exactly
                // this path (the unpacker lays assets/* down at the dataDir
                // root), and deleting that would silently break the app. No
                // corpus APK does it today -- but the cost of being wrong is
                // destroying a file the app needs, and the cost of being
                // needlessly cautious is only that sourceDir stays what it is
                // now. The marker makes the distinction certain instead of
                // heuristic: because it is written only after a successful
                // publish, an app whose own base.apk was already there on the
                // first launch can never acquire one.
                if (!marker.isFile()) return null;
            }
            // Rebuild rather than trust a stale entry, so the link can never
            // dangle or point at a previous build of the same APK.
            // deleteIfExists does not follow symlinks, so a dangling link is
            // removed rather than chased.
            java.nio.file.Files.deleteIfExists(dstPath);
            try {
                java.nio.file.Files.createLink(dstPath, srcPath);
            } catch (Throwable crossVolumeOrUnsupported) {
                java.nio.file.Files.createSymbolicLink(dstPath, srcPath);
            }
            if (dst.isFile() && dst.canRead()) {
                // Claim ownership only once the link really exists.
                try { marker.createNewFile(); } catch (Throwable ignored) {}
                return dst.getAbsolutePath();
            }
        } catch (Throwable t) {
            // The log is itself wrapped: this method's whole contract is "degrade
            // to the plain path, never below it", and an exception escaping here
            // would instead propagate out of makeAppInfo and take
            // getApplicationInfo / getPackageInfo down with it. Measured, not
            // theoretical -- NativeLog.print is a native method, so it raises
            // UnsatisfiedLinkError in any context where the runtime has not bound
            // it yet.
            try {
                NativeLog.w("Mac.apk.PM",
                    "base.apk link not published (" + t + ") -- sourceDir stays " + abs);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ---- nativeLibraryDir: the INSTALL tree, not the data tree ---------------
    //
    // AOSP composes it from the package's CODE path, which is a different tree
    // from its data dir. services/core/java/com/android/server/pm/
    // PackageAbiHelperImpl.java:189-193 (android11-release):
    //     nativeLibraryRootDir = new File(codeFile, LIB_DIR_NAME).getAbsolutePath();
    //     nativeLibraryDir = new File(nativeLibraryRootDir,
    //             getPrimaryInstructionSet(abis)).getAbsolutePath();
    // with codeFile under /data/app and the data dir under /data/user/0, so
    // `nativeLibraryDir.startsWith(dataDir)` is FALSE on a device (fwprobe
    // PM.aipath.rel.nativeLibStartsWithDataDir, ref 0).
    //
    // Since v1.0.1588 the loader's value IS that directory: the runtime
    // installs the libraries into <root>/data/app/~~<nonce>/<pkg>-<nonce>/
    // lib/<isa>/ (main.cpp, Phase 4 of docs/disk-footprint-and-space-
    // efficiency_2026-09-19.md) and Boot.resolveNativeLibraryDir reports it
    // (InstalledSplits.nativeLibDir). From v1.0.940 to v1.0.1587 the libraries
    // lived under the apktool tree and this class published a symlink at
    // <root>/.macapk/app/<name>/lib/<isa> to give the field the install shape;
    // the runtime removes that tree on the first launch after the move.
    //
    // WHEN THE APP HAS NO NATIVE LIBS the path is REPORTED but nothing exists
    // there, and that is the device's own answer, not a degradation: the
    // golden AVD reads PM.aipath.nativeLibraryDir.{exists,isDirectory,canRead}
    // = 0/0/0 for the probe app.
    private String nativeLibraryPath() {
        return nativeLibraryDir;
    }

    /** AOSP reports installed paths absolutely; a relative apkPath resolves
     *  against whatever working directory the reader happens to have. */
    private static String absolutePath(String p) {
        if (p == null || p.isEmpty()) return p;
        try { return new java.io.File(p).getAbsolutePath(); }
        catch (Throwable t) { return p; }
    }

    /** StorageManager.UUID_DEFAULT: the UUID of internal/private storage. */
    private static final java.util.UUID UUID_DEFAULT =
        java.util.UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");

    // ---- <application> attributes + ApplicationInfo.flags (v1.0.923) --------
    //
    // WHY THIS LIVES HERE. ApplicationInfo.flags was never assigned, so we
    // answered 0x00000000 where a device answers a populated bitmask
    // (0x3088be46 for the fwprobe app). That is not a cosmetic field: it is the
    // switch a large amount of library code reads.
    //   * FLAG_DEBUGGABLE gates StrictMode installation, WebView remote
    //     debugging, leak/assertion paths in OkHttp, Firebase, Compose.
    //   * FLAG_HAS_CODE is what ApplicationInfo.hasCode() returns, and a
    //     "no code" package is one nothing should try to load classes from.
    //   * FLAG_USES_CLEARTEXT_TRAFFIC and the network-security-config path
    //     read it to decide whether plain http is permitted.
    //   * FLAG_SUPPORTS_*_SCREENS / FLAG_RESIZEABLE_FOR_SCREENS /
    //     FLAG_SUPPORTS_SCREEN_DENSITIES drive AOSP's screen-compatibility
    //     mode; all-zero means "this app supports NO screen size", which is a
    //     legacy-compat trigger, not a neutral value.
    // Reporting 0 told every one of those readers something false.
    //
    // The <application> element's attributes are read here rather than through
    // com.macapk.loader.Manifest because Manifest models the handful of
    // attributes the loader itself needs and is owned by the boot path. Reading
    // them once, lazily, off the same two sources Manifest.parse uses (the
    // BINARY AndroidManifest.xml inside the APK first, the apktool text decode
    // as fallback) keeps this self-contained. FOLLOW-UP for the loader lane:
    // fold these into Manifest so the manifest is parsed once, not twice.
    private volatile org.w3c.dom.Element lazyAppElement;
    private volatile boolean             lazyAppElementTried;

    private org.w3c.dom.Element applicationElement() {
        if (lazyAppElementTried) return lazyAppElement;
        synchronized (this) {
            if (lazyAppElementTried) return lazyAppElement;
            lazyAppElementTried = true;
            try {
                org.w3c.dom.Document doc = manifestDocument();
                if (doc != null) {
                    org.w3c.dom.NodeList apps =
                        doc.getDocumentElement().getElementsByTagName("application");
                    if (apps.getLength() > 0) {
                        lazyAppElement = (org.w3c.dom.Element) apps.item(0);
                    }
                }
            } catch (Throwable t) {
                NativeLog.w("Mac.apk.PM",
                    "<application> attribute read failed (" + t + ") -- "
                    + "ApplicationInfo falls back to the targetSdk-derived flag defaults");
                lazyAppElement = null;
            }
            return lazyAppElement;
        }
    }

    private org.w3c.dom.Document manifestDocument() throws Exception {
        if (apkPath != null && !apkPath.isEmpty()) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
                java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
                if (e != null) {
                    byte[] axml;
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        axml = in.readAllBytes();
                    }
                    return BinaryXmlParser.parse(axml);
                }
            } catch (Throwable ignored) { /* fall through to the text decode */ }
        }
        if (dataDir != null) {
            java.io.File a = new java.io.File(dataDir, "_apkt/AndroidManifest.xml");
            java.io.File b = new java.io.File(dataDir, "AndroidManifest.xml");
            java.io.File decoded = a.isFile() ? a : (b.isFile() ? b : null);
            if (decoded != null) {
                javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                return dbf.newDocumentBuilder().parse(decoded);
            }
        }
        return null;
    }

    private static final String ANDROID_NS =
        "http://schemas.android.com/apk/res/android";

    /** One `<application android:NAME>` attribute as written, or null. */
    private String appAttr(String name) {
        org.w3c.dom.Element app = applicationElement();
        if (app == null) return null;
        String v = app.getAttributeNS(ANDROID_NS, name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** A boolean `<application>` attribute with AOSP's default for an undeclared
     *  one. The binary manifest renders TYPE_INT_BOOLEAN as "true"/"false"; the
     *  apktool decode writes the same spellings. */
    private boolean appAttrBool(String name, boolean def) {
        String v = appAttr(name);
        if (v == null) return def;
        if ("true".equalsIgnoreCase(v))  return true;
        if ("false".equalsIgnoreCase(v)) return false;
        // A reference or an int-encoded boolean ("1"/"0").
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return def;
    }

    /** A `<supports-screens android:NAME>` boolean, defaulted the way
     *  PackageParser defaults it for this app's targetSdk. AOSP's rule (the
     *  supportsSmallScreens/supportsLargeScreens/resizeable/anyDensity block of
     *  parseBaseApkCommon) is "declared value wins; otherwise the attribute is
     *  true iff the app targets the SDK the attribute was introduced in". */
    private boolean supportsScreens(String name, boolean def) {
        try {
            org.w3c.dom.Document doc = null;
            org.w3c.dom.Element app = applicationElement();
            if (app != null) doc = app.getOwnerDocument();
            if (doc != null) {
                org.w3c.dom.NodeList ss =
                    doc.getDocumentElement().getElementsByTagName("supports-screens");
                if (ss.getLength() > 0) {
                    String v = ((org.w3c.dom.Element) ss.item(0))
                        .getAttributeNS(ANDROID_NS, name);
                    if (v != null && !v.isEmpty()) {
                        if ("true".equalsIgnoreCase(v) || "1".equals(v))  return true;
                        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return false;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return def;
    }

    /** ApplicationInfo.flags, assembled the way AOSP PackageParser assembles it
     *  from the manifest plus the targetSdk-derived defaults.
     *
     *  Two of these bits are not manifest-derived and are stated here on
     *  purpose:
     *    FLAG_INSTALLED   -- the running package IS installed, by definition.
     *    FLAG_EXTRACT_NATIVE_LIBS -- Mac.apk genuinely extracts (and translates)
     *      the APK's .so files to disk rather than mapping them from the zip,
     *      so `true` is a statement about what this runtime does, not a default
     *      copied from somewhere. AOSP's own default for the attribute is also
     *      true.
     *  FLAG_SYSTEM / FLAG_PERSISTENT / FLAG_UPDATED_SYSTEM_APP / FLAG_STOPPED /
     *  FLAG_SUSPENDED are deliberately NEVER set: a hosted APK is an ordinary
     *  installed user app, and claiming system-app status is exactly the kind of
     *  privilege lie that makes an app take a branch the runtime cannot honour. */
    private volatile int cachedFlags;
    private volatile boolean cachedFlagsValid;
    private int applicationFlags() {
        if (cachedFlagsValid) return cachedFlags;
        int f = ApplicationInfo.FLAG_INSTALLED;
        if (appAttrBool("hasCode", true))             f |= ApplicationInfo.FLAG_HAS_CODE;
        if (appAttrBool("debuggable", false))         f |= ApplicationInfo.FLAG_DEBUGGABLE;
        if (appAttrBool("allowClearUserData", true))  f |= ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA;
        if (appAttrBool("allowBackup", true))         f |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        // ⚠ The three backup-behaviour flags are INSIDE PackageParser's
        // `if (backupAgent != null)` block, so an app with no android:backupAgent
        // carries none of them even though killAfterRestore's own default is
        // true. Applying them unconditionally would have set FLAG_KILL_AFTER_RESTORE
        // on essentially every app and made the whole flags word wrong by one bit.
        if (appAttr("backupAgent") != null) {
            if (appAttrBool("killAfterRestore", true))   f |= ApplicationInfo.FLAG_KILL_AFTER_RESTORE;
            if (appAttrBool("restoreAnyVersion", false)) f |= ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
            if (appAttrBool("fullBackupOnly", false))    f |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;
        }
        if (appAttrBool("allowTaskReparenting", false)) f |= ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING;
        if (appAttrBool("vmSafeMode", false))         f |= ApplicationInfo.FLAG_VM_SAFE_MODE;
        if (appAttrBool("largeHeap", false))          f |= ApplicationInfo.FLAG_LARGE_HEAP;
        if (appAttrBool("testOnly", false))           f |= ApplicationInfo.FLAG_TEST_ONLY;
        if (appAttrBool("extractNativeLibs", true))   f |= ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        if (appAttrBool("persistent", false))         f |= ApplicationInfo.FLAG_PERSISTENT;
        // android:hardwareAccelerated defaults to true from API 14 (Ice Cream
        // Sandwich) onward -- PackageParser's default argument is
        // `owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH`.
        if (appAttrBool("hardwareAccelerated", targetSdk >= 14))
            f |= ApplicationInfo.FLAG_HARDWARE_ACCELERATED;
        // android:supportsRtl defaults to false regardless of targetSdk.
        if (appAttrBool("supportsRtl", false))        f |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        // android:usesCleartextTraffic defaults to true BELOW targetSdk 28 and
        // false from 28 (Pie) on. Mac.apk enforces no cleartext policy, but the
        // flag is what an app's own networking layer reads to decide whether to
        // allow http, so answering it honestly by targetSdk is what keeps the
        // app's behaviour the same as on a device.
        if (appAttrBool("usesCleartextTraffic", targetSdk < 28))
            f |= ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC;
        // <supports-screens>. AOSP's defaults are targetSdk-gated at the SDK
        // level each attribute was introduced: small/large/resizeable/anyDensity
        // at DONUT (4), xlarge at GINGERBREAD (9). Normal screens are always
        // supported.
        f |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        if (supportsScreens("smallScreens",  targetSdk >= 4)) f |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        if (supportsScreens("largeScreens",  targetSdk >= 4)) f |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        if (supportsScreens("xlargeScreens", targetSdk >= 9)) f |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        if (supportsScreens("resizeable",    targetSdk >= 4)) f |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        if (supportsScreens("anyDensity",    targetSdk >= 4)) f |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        cachedFlags = f;
        cachedFlagsValid = true;
        return f;
    }

    // Type manifest <meta-data> values the way real Android's PackageParser
    // does: an `@integer/<name>` reference (or a literal int) becomes an INT in
    // the Bundle so SDKs that read it via getInt() get a real number. Crossy's
    // (and every GMS app's) `com.google.android.gms.version` is
    // `@integer/google_play_services_version`; left as a String, GMS's
    // GooglePlayServicesUtilLight.isGooglePlayServicesAvailable() reads getInt
    // == 0 and throws GooglePlayServicesMissingManifestValueException uncaught
    // on the Unity main thread, stalling the boot. Mirrors Application.makeAppInfo.
    // App-agnostic (resolves whatever the manifest declares).
    private static void putTypedMeta(android.os.Bundle md, String key, String val) {
        putTypedMeta(md, key, val, null);
    }

    /** `axmlType` is the binary-manifest value type ("boolean" / "int"
     *  / "float" / "reference"), null when unknown (apktool text
     *  path). With a known type we mirror AOSP PackageParser's
     *  parseMetaData exactly: TYPE_INT_BOOLEAN -> putBoolean, ints ->
     *  putInt, float -> putFloat, references resolve through the
     *  resource table (an @integer lands as its VALUE -- that is what
     *  GMS's google_play_services_version reads via getInt). A
     *  STRING-typed "true" stays a STRING -- the distinction the text
     *  decode could never make (the 2026-06-11 Crossy revert). */
    private static void putTypedMeta(android.os.Bundle md, String key, String val,
                                     String axmlType) {
        if (key == null) return;
        if (axmlType != null && val != null && !val.isEmpty()) {
            switch (axmlType) {
                case "string":
                    // AOSP PackageParser.parseMetaData types strictly by the
                    // binary AXML Res_value.dataType, so a TYPE_STRING
                    // android:value stays a Bundle String even when its text is
                    // all digits. Declaring a numeric id inline rather than via
                    // @string/ is a real pattern (Facebook App ids, FCM sender
                    // ids), and the untyped tail at the bottom of this method
                    // would clobber the String with an Integer.parseInt of the
                    // same text -- getString() then returns null and the SDK's
                    // init throws. Short-circuiting here is the AOSP answer.
                    // NB: reaching this case needs BinaryXmlParser.setAttr to
                    // annotate TYPE_STRING attributes; it currently skips them
                    // (`if (!"string".equals(typeTag))`), so today only a caller
                    // that supplies the type some other way lands here.
                    md.putString(key, val);
                    return;
                case "boolean":
                    md.putBoolean(key, Boolean.parseBoolean(val));
                    return;
                case "int": {
                    try {
                        int iv = val.startsWith("0x")
                            ? (int) Long.parseLong(val.substring(2), 16)
                            : Integer.parseInt(val);
                        md.putInt(key, iv);
                        return;
                    } catch (NumberFormatException ignored) { break; }
                }
                case "float": {
                    try { md.putFloat(key, Float.parseFloat(val)); return; }
                    catch (NumberFormatException ignored) { break; }
                }
                case "reference": {
                    // "@0x7f0b0001" -- resolve the reference to its VALUE with the
                    // Android-correct Bundle TYPE. AOSP's RetrieveAttributes +
                    // AssetManager2::ResolveReference resolve the reference BEFORE
                    // PackageParser.parseMetaData ever sees it, so parseMetaData
                    // types by the RESOLVED value's type (PackageParser.java
                    // ~5628): TYPE_STRING -> putString, TYPE_INT_BOOLEAN ->
                    // putBoolean, FIRST_INT..LAST_INT (incl. @integer AND @color
                    // TYPE_INT_COLOR_*) -> putInt(data), TYPE_FLOAT -> putFloat,
                    // and everything else (dimen/fraction/...) is DROPPED (no key).
                    // We mirror that per the resolved resource TYPE. The @string
                    // case is load-bearing: Facebook's ApplicationId =
                    // @string/facebook_app_id must read back a String or
                    // FacebookSdk.loadDefaultsFromMetadata throws.
                    try {
                        int resId = (int) Long.parseLong(val.substring(3), 16);
                        String rtype = android.content.res.Resources
                            .macapkResourceTypeForId(resId);
                        if ("string".equals(rtype)) {
                            String sv = android.content.res.Resources
                                .macapkStringForId(resId);
                            if (sv != null) { md.putString(key, sv); return; }
                        } else if ("bool".equals(rtype)) {
                            Boolean bv = android.content.res.Resources
                                .macapkBoolForId(resId);
                            if (bv != null) { md.putBoolean(key, bv); return; }
                        } else if ("color".equals(rtype)) {
                            Integer cv = android.content.res.Resources
                                .macapkColorForId(resId);
                            if (cv != null) { md.putInt(key, cv); return; }  // ARGB
                        } else if ("dimen".equals(rtype) || "fraction".equals(rtype)) {
                            // AOSP DROPS resolved dimension/fraction meta-data (the
                            // non-RIGID_PARSER else branch puts nothing). Match it:
                            // no md.put -> Bundle.containsKey(key) stays false.
                            return;
                        }
                        // integer (and any type ResourceIndex resolves as an int):
                        // GMS's google_play_services_version = @integer/... reads
                        // this via getInt.
                        Integer resolved = android.content.res.Resources
                            .macapkIntegerForId(resId);
                        md.putInt(key, resolved != null ? resolved : resId);
                        return;
                    } catch (Throwable ignored) { break; }
                }
                default: break;
            }
        }
        if (val != null && val.startsWith("@integer/")) {
            Integer resolved = android.content.res.Resources
                .macapkIntegerForName(val.substring("@integer/".length()));
            if (resolved != null) {
                // putInt ONLY: our Bundle is a single map, so a putString on the
                // same key would overwrite the Integer and make getInt() return
                // the default (0). Real Android stores a resolved @integer/ ref
                // as an int; getInt() is how GMS/Firebase read it.
                md.putInt(key, resolved);
                return;
            }
        }
        // NOTE on boolean typing (attempted + reverted 2026-06-11):
        // AOSP stores a TYPE_INT_BOOLEAN android:value as a Boolean in
        // the Bundle, and Bundle.getBoolean readers (the v1.0.345
        // sweep's meta-data note) want that. But our source is
        // apktool's DECODED manifest, where a genuine STRING "true"
        // and a typed boolean decode identically -- blanket
        // putBoolean("true"/"false") broke Crossy (Unity's activity
        // reads unityplayer.* meta-data via getString -> null -> NPE
        // in windowFocusChanged). Typing this correctly requires
        // reading the BINARY manifest's TypedValue, not the decoded
        // text. Until then, strings stay strings.
        md.putString(key, val);
        if (val != null && !val.isEmpty()) {
            try { md.putInt(key, Integer.parseInt(val)); }
            catch (NumberFormatException ignored) {}
        }
    }

    /** Copy the permission / URI-grant half of a manifest <provider> onto the
     *  ProviderInfo we hand the app. Mirrors the tail of AOSP's
     *  PackageParser.parseProvider (frameworks/base/core/java/android/content/
     *  pm/PackageParser.java:5111-5171, android14-release). The FALLBACK LOGIC
     *  itself already ran in Manifest.java's parse, so by here these are the
     *  resolved values.
     *
     *  Every field below is the app's own manifest read back to it. Where the
     *  value is null it is because the manifest declares nothing at any rung of
     *  AOSP's fallback chain, which is exactly what a real device reports for
     *  such a provider -- not a placeholder we could not fill.
     *
     *  Package-private, not private: Boot.installAutoInitProviders builds the
     *  ProviderInfo that a provider actually SEES in attachInfo/onCreate, and
     *  it must fill the same fields the same way. One copy, two callers. */
    static void macapkFillProviderPermissions(
            ProviderInfo info, Manifest.ProviderEntry entry) {
        info.readPermission        = entry.readPermission;
        info.writePermission       = entry.writePermission;
        info.forceUriPermissions   = entry.forceUriPermissions;
        info.multiprocess          = entry.multiprocess;
        info.isSyncable            = entry.syncable;
        info.flags                 = entry.flags;
        info.uriPermissionPatterns = entry.uriPermissionPatterns;
        info.pathPermissions       = entry.pathPermissions;
    }

    // =====================================================================
    // The com.android.vending package and its licensing service (v1.0.1171)
    // =====================================================================
    //
    // Gated on PlayServices.vendingPresent() at every entry point. With the GMS
    // layer off, none of this is reachable and PackageManager answers exactly
    // what it answered at v1.0.1170.

    // ---- the platform package, "android" ------------------------------------
    //
    // Every Android device has a package named "android": framework-res.apk,
    // the platform itself. AOSP android14-release core/res/AndroidManifest.xml:21
    //     package="android" coreApp="true" android:sharedUserId="android.uid.system"
    // and its <application> (:7682-7694) is
    //     android:process="system" android:persistent="true"
    //     android:hasCode="false" ... android:forceQueryable="true"
    // forceQueryable means package-visibility filtering never hides it, so on a
    // device getPackageInfo("android", ...) answers for EVERY app, with the
    // platform's signing certificate. Apps rely on that: the UAMP
    // PackageValidator that media apps copy (NewPipe's MediaBrowserImpl builds
    // one inside PlayerService.onCreate) reads the platform signature with
    //     packageManager.getPackageInfo("android", GET_SIGNATURES | GET_PERMISSIONS)
    // and does not catch NameNotFoundException -- so our NameNotFoundException
    // killed PlayerService.onCreate, and with it every video NewPipe tried to
    // play (lanes19 media-playback, 2026-09-29).
    //
    // THE SIGNATURE is this device's platform certificate: Mac.apk's own
    // self-signed CN=Mac.apk Platform (com.macapk.gms.PlatformSignature), the
    // same one the vending branch reports. A device's "android" package is
    // signed by that device's platform key, and ours is this one; it is not
    // anyone else's identity.
    //
    // Gate MACAPK_PM_PLATFORM_PACKAGE (default ON); =0 restores the old
    // NameNotFoundException for "android".
    static final String PLATFORM_PACKAGE = "android";
    private volatile PackageInfo cachedPlatformInfo;

    static boolean platformPackagePresent() {
        return !"0".equals(System.getenv("MACAPK_PM_PLATFORM_PACKAGE"));
    }

    /** framework-res.apk's PackageInfo, built once (a device's package database
     *  does not change under a running process). The fields are the ones
     *  `dumpsys package android` shows on an AOSP device: versionCode and
     *  min/targetSdk are PLATFORM_SDK_VERSION (the build passes
     *  --version-code $(PLATFORM_SDK_VERSION) to aapt for framework-res),
     *  versionName is the release string, uid SYSTEM_UID via the shared user
     *  android.uid.system, process "system", FLAG_SYSTEM | FLAG_PERSISTENT and
     *  no FLAG_HAS_CODE (hasCode="false"). */
    private PackageInfo platformPackageInfo() {
        PackageInfo cached = cachedPlatformInfo;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedPlatformInfo != null) return cachedPlatformInfo;
            int sdk = android.os.Build.VERSION.SDK_INT;
            PackageInfo pi = new PackageInfo();
            pi.packageName = PLATFORM_PACKAGE;
            pi.versionName = android.os.Build.VERSION.RELEASE;
            pi.versionCode = sdk;
            pi.longVersionCode = sdk;
            pi.sharedUserId = "android.uid.system";
            pi.firstInstallTime = pi.lastUpdateTime = macapkInstallTimeMs();

            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.packageName = PLATFORM_PACKAGE;
            ai.processName = "system";
            ai.uid = android.os.Process.SYSTEM_UID;
            ai.enabled = true;
            ai.minSdkVersion = sdk;
            ai.targetSdkVersion = sdk;
            ai.sourceDir = ai.publicSourceDir = "/system/framework/framework-res.apk";
            ai.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM
                      | android.content.pm.ApplicationInfo.FLAG_PERSISTENT
                      | android.content.pm.ApplicationInfo.FLAG_INSTALLED
                      | android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
            pi.applicationInfo = ai;

            // NEVER null or empty: the PackageValidator above requires exactly
            // one signature and throws IllegalStateException otherwise.
            android.content.pm.Signature ours = macapkPlatformSignature(PLATFORM_PACKAGE);
            pi.signatures = new android.content.pm.Signature[] { ours != null ? ours
                : new android.content.pm.Signature(new byte[0]) };
            try {
                pi.signingInfo = new android.content.pm.SigningInfo(pi.signatures);
            } catch (Throwable ignored) {
                // Same as the vending branch: the legacy array is what apps read first.
            }
            cachedPlatformInfo = pi;
            return pi;
        }
    }

    static final String VENDING_PACKAGE = "com.android.vending";

    /** The two intent actions a licensing client binds to. AOSP's Play Store
     *  declares the first; the second is the newer name some clients use. */
    private static final String LICENSING_ACTION_V1 =
            "com.android.vending.licensing.ILicensingService";
    private static final String LICENSING_ACTION_V2 =
            "com.android.vending.licensing.ILicensingServiceV2";

    /** The class that actually implements the service, in OUR framework. */
    private static final String LICENSING_SERVICE_CLASS =
            "com.macapk.vending.LicensingService";

    private volatile PackageInfo cachedVendingInfo;

    /** The store's PackageInfo. Built once: a device's package database does not
     *  change under a running process, and a fresh object per call would give
     *  different install timestamps on every query (the same bug this file
     *  already fixed for the running app's own PackageInfo). */
    private PackageInfo vendingPackageInfo() {
        PackageInfo cached = cachedVendingInfo;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedVendingInfo != null) return cachedVendingInfo;
            PackageInfo pi = new PackageInfo();
            pi.packageName = VENDING_PACKAGE;
            // A plausible, stable Play Store version. It is not a claim about
            // Google's build -- apps use it only for "is the store new enough",
            // and a value too low makes clients demand an update they cannot get.
            pi.versionName = "30.6.18";
            pi.versionCode = 83061810;
            pi.longVersionCode = 83061810L;
            pi.firstInstallTime = pi.lastUpdateTime = macapkInstallTimeMs();

            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.packageName = VENDING_PACKAGE;
            ai.enabled = true;
            // FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP: on a device the store is a
            // system app that has been updated. Both flags are what apps check
            // when deciding whether to trust an installer, and both are true of
            // ours in the only sense available here -- it ships with the platform.
            ai.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM
                      | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                      | android.content.pm.ApplicationInfo.FLAG_INSTALLED;
            pi.applicationInfo = ai;

            // ⚠ NEVER null: a caller that finds the package immediately does
            // `signatures.length` (AppAuth's BrowserDescriptor does exactly
            // this), and it catches NameNotFoundException but not NPE.
            android.content.pm.Signature ours = macapkPlatformSignature(VENDING_PACKAGE);
            if (ours != null) {
                pi.signatures = new android.content.pm.Signature[] { ours };
                try {
                    pi.signingInfo = new android.content.pm.SigningInfo(pi.signatures);
                } catch (Throwable ignored) {
                    // Our SigningInfo shim may not offer that constructor; the
                    // legacy signatures array is the field apps read first.
                }
            } else {
                pi.signatures = new android.content.pm.Signature[] {
                    new android.content.pm.Signature(new byte[0]) };
            }
            cachedVendingInfo = pi;
            return pi;
        }
    }

    /** Our platform certificate. Wrapped in a try/catch rather than reflection:
     *  the call is direct (both jars are built from one javac invocation), but
     *  generating a keypair can fail on a locked-down host, and a PackageInfo
     *  with a null signatures array is the NPE trap documented above. */
    private static android.content.pm.Signature macapkPlatformSignature(String forPackage) {
        try {
            return com.macapk.gms.PlatformSignature.signature();
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.PM", "no platform signature for " + forPackage
                + " (" + t + ") -- reporting an empty one");
            return null;
        }
    }

    /** A stable install time for the synthetic package. The APK's own timestamp
     *  is the closest thing to "when this device got its store". */
    private long macapkInstallTimeMs() {
        try {
            PackageInfo self = getPackageInfo(this.packageName, 0);
            if (self != null && self.firstInstallTime > 0) return self.firstInstallTime;
        } catch (Throwable ignored) {}
        return System.currentTimeMillis();
    }

    /**
     * AOSP queryIntentServices. Before v1.0.1171 this was PackageManager's
     * inherited "always empty", which was correct while nothing here hosted a
     * service another package could bind to. The licensing service is the first
     * one that is genuinely resolvable, so the honest answer changed.
     *
     * The two synthetic services (licensing, gms-register) are answered first
     * because they are ours and live outside the hosted manifest.
     *
     * v1.0.1177 ADDED THE HOSTED PACKAGE'S OWN SERVICES. This comment used to
     * say the narrow scope was deliberate because "nothing has asked for it".
     * Something asks for it now: the real firebase-messaging AAR's
     * ServiceStarter resolves the IMPLICIT action
     * "com.google.firebase.MESSAGING_EVENT" to find the app's own
     * FirebaseMessagingService, and an empty answer here made it log
     * "ServiceIntent not found" and DROP every delivered push. That is the last
     * hop of FCM, so an empty index broke delivery for every app that bundles
     * Firebase (Minecraft, Reddit, Signal). AOSP indexes service filters the
     * same way it indexes activity and receiver filters; we now do too, through
     * the identical matcher.
     *
     * Note we do NOT gate on ServiceEntry.exported: exported governs access
     * from a DIFFERENT uid, and Mac.apk hosts exactly one package in one
     * process, so every query here is same-package by construction. Real
     * Android resolves an app's own non-exported service for that app too.
     *
     * Wave 7 (2026-09-26): the hosted services now resolve through a selector
     * (MACAPK_PM_SVC_SELECTOR), match with AOSP's resolvedType
     * (MACAPK_PM_SVC_RESOLVE_TYPE) and come back in IntentResolver's order
     * (MACAPK_PM_RESOLVE_ORDER), exactly like activities and receivers. This
     * is the path the runtime's own service starts take -- ServiceRegistry
     * .resolveImplicit and Context.bindService both call resolveService -- and
     * the census of every such start (docs/pm-service-selector-and-resolver-
     * order_2026-09-26.md) found none that carries a selector, a content: Uri
     * or a tie the new sort keys could break differently, which the offline
     * gate debug/w7pmsvc_gate.sh re-checks across the corpus.
     */
    @Override public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        List<ResolveInfo> out = new java.util.ArrayList<>(1);
        ResolveInfo ri = macapkResolveLicensingService(intent);
        if (ri == null) ri = macapkResolveGmsRegisterService(intent);
        if (ri == null) ri = macapkResolveGmsMeasurementService(intent);
        if (ri == null) ri = macapkResolveGmsIdentityService(intent);
        if (ri == null) ri = macapkResolveGmsLocationService(intent);
        if (ri != null) out.add(ri);
        if (intent == null) return out;

        // The hosted app's own <service> declarations.
        // MACAPK_PM_SVC_SELECTOR: resolve through a selector (ComputerEngine
        // :646-651), so the package scope and the component below are the
        // SELECTOR's too (:714). The synthetic services above deliberately keep
        // reading `intent` (see macapkSvcSelector).
        Intent query = intent;
        if (macapkSvcSelector()) {
            ComponentName outerComp = null;
            Intent sel = null;
            try { outerComp = intent.getComponent(); sel = intent.getSelector(); }
            catch (Throwable ignored) {}
            if (outerComp == null && sel != null) query = sel;
        }
        // MACAPK_PM_SVC_RESOLVE_TYPE: AOSP's resolvedType, always from the intent
        // AS PASSED (ApplicationPackageManager :1605), never the selector's.
        final String resolvedType = macapkSvcResolveType()
                ? macapkResolvedType(intent) : intent.getType();
        String want = null;
        try { want = query.getPackage(); } catch (Throwable ignored) {}
        if (want != null && !want.equals(this.packageName)) return out;
        ComponentName explicit = null;
        try { explicit = query.getComponent(); } catch (Throwable ignored) {}
        if (explicit != null && !this.packageName.equals(explicit.getPackageName())) return out;
        // MACAPK_PM_RESOLVE_ORDER: which filter is credited, the row fields and
        // the sort, as IntentResolver does them (see the gate). `want` non-null
        // is AOSP's per-package path (queryIntentServicesInternalBody :714-735).
        final boolean order = explicit == null && macapkResolveOrder() && macapkIntentMatchData();
        final boolean defaultOnly =
                (flags & android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != 0;
        final java.util.List<Integer> cuts = order ? new java.util.ArrayList<>() : null;
        List<ResolveInfo> matched = new java.util.ArrayList<>();
        for (Manifest.ServiceEntry e : services.values()) {
            if (!macapkIsComponentEnabled(e.name, e.enabled, flags)) continue;
            Integer prio;
            int[] credited = null;
            if (explicit != null) {
                if (!e.name.equals(explicit.getClassName())) continue;
                prio = 0;
            } else if (order) {
                credited = aospCreditedFilter(e.filters, query, resolvedType, defaultOnly,
                        want != null, false);
                if (credited == null) continue;
                prio = credited[0];
            } else {
                prio = bestMatchPriority(e.filters, query, resolvedType, flags);
                if (prio == null) continue;
            }
            try {
                ResolveInfo r = new ResolveInfo();
                r.serviceInfo = getServiceInfo(
                        new ComponentName(this.packageName, e.name), flags);
                r.priority = prio;
                if (credited != null) {
                    r.match = credited[1];
                    r.isDefault = credited[2] != 0;
                    cuts.add(credited[3]);
                }
                matched.add(r);
            } catch (NameNotFoundException ignored) {}
            if (explicit != null) break;
        }
        if (order) {
            aospSortResults(matched, cuts);
        } else {
            // AOSP IntentResolver.sortResults: DESCENDING by the matched filter's
            // android:priority. Collections.sort is stable, so equal priorities keep
            // manifest order -- which is why `services` is a LinkedHashMap.
            // (MACAPK_PM_RESOLVE_ORDER=0: the pre-wave-7 priority-only sort.)
            java.util.Collections.sort(matched, new java.util.Comparator<ResolveInfo>() {
                @Override public int compare(ResolveInfo a, ResolveInfo b) {
                    return Integer.compare(b.priority, a.priority);
                }
            });
        }
        out.addAll(matched);
        return out;
    }

    /** AOSP resolveService: the single best match, or null. */
    @Override public ResolveInfo resolveService(Intent intent, int flags) {
        ResolveInfo ri = macapkResolveLicensingService(intent);
        if (ri == null) ri = macapkResolveGmsRegisterService(intent);
        if (ri == null) ri = macapkResolveGmsMeasurementService(intent);
        if (ri == null) ri = macapkResolveGmsIdentityService(intent);
        if (ri == null) ri = macapkResolveGmsLocationService(intent);
        if (ri != null) return ri;
        // Fall through to the hosted package's own services (v1.0.1177). This
        // is the call firebase-messaging's ServiceStarter makes to locate the
        // app's FirebaseMessagingService for MESSAGING_EVENT; returning null
        // here is what made it drop every delivered push. AOSP returns the
        // single best match, and queryIntentServices already applies the same
        // filter matcher activities and receivers use.
        List<ResolveInfo> all = queryIntentServices(intent, flags);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The one implicit service Intent Mac.apk can answer. Matches on ACTION
     *  and, when the caller set one, on the package -- an Intent explicitly
     *  aimed at another package must not resolve to ours. */
    private ResolveInfo macapkResolveLicensingService(Intent intent) {
        if (intent == null || !PlayServices.vendingPresent()) return null;
        String action = intent.getAction();
        if (!LICENSING_ACTION_V1.equals(action) && !LICENSING_ACTION_V2.equals(action)) {
            return null;
        }
        String want = intent.getPackage();
        if (want != null && !VENDING_PACKAGE.equals(want)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !VENDING_PACKAGE.equals(explicit.getPackageName())) return null;

        android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
        si.packageName = VENDING_PACKAGE;
        // The className names OUR implementation, because that is the class
        // ServiceRegistry will instantiate. A client that reads it sees a
        // com.macapk name and can tell exactly whose store this is -- which is
        // the point.
        si.name = LICENSING_SERVICE_CLASS;
        si.exported = true;
        si.enabled = true;
        si.applicationInfo = vendingPackageInfo().applicationInfo;
        // AOSP's Play Store guards the service with this permission. Reporting it
        // is what lets a caller that checks before binding make the right call.
        si.permission = "com.android.vending.CHECK_LICENSE";

        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        ri.resolvePackageName = VENDING_PACKAGE;
        ri.isDefault = true;
        // AOSP's match value for an action-only filter hit. Any positive value
        // works for a single result; this is the constant AOSP uses.
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }

    // =====================================================================
    // com.google.android.gms package + its FCM register service (v1.0.1175)
    // =====================================================================
    // Gated on PlayServices.pushBackendEnabled() at every entry. Default OFF, so
    // with the flag off com.google.android.gms stays ABSENT exactly as before and
    // the Firebase self-terminate contract is untouched.

    static final String GMS_PACKAGE = "com.google.android.gms";

    /** The two register actions an FCM client binds/broadcasts to. */
    private static final String C2DM_REGISTER = "com.google.android.c2dm.intent.REGISTER";
    private static final String C2DM_UNREGISTER = "com.google.android.c2dm.intent.UNREGISTER";
    private static final String IID_TOKEN_REQUEST = "com.google.iid.TOKEN_REQUEST";
    private static final String PUSH_REGISTER_SERVICE_CLASS = "com.macapk.gms.PushRegisterService";

    private volatile PackageInfo cachedGmsInfo;

    private PackageInfo gmsPackageInfo() {
        PackageInfo cached = cachedGmsInfo;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedGmsInfo != null) return cachedGmsInfo;
            PackageInfo pi = new PackageInfo();
            pi.packageName = GMS_PACKAGE;
            // versionCode >= 12000000 is what makes firebase-messaging's Rpc take
            // the modern bound-Messenger path instead of the legacy broadcast one.
            pi.versionName = "24.44.00";
            pi.versionCode = PlayServices.VERSION_CODE;   // 244400000, well above 12000000
            pi.longVersionCode = PlayServices.VERSION_CODE;
            pi.firstInstallTime = pi.lastUpdateTime = macapkInstallTimeMs();
            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.packageName = GMS_PACKAGE;
            ai.enabled = true;
            ai.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM
                      | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                      | android.content.pm.ApplicationInfo.FLAG_INSTALLED;
            pi.applicationInfo = ai;
            // ⛔ OUR platform certificate, never Google's -- an app that
            // byte-compares the gms signature against Google's embedded cert
            // still correctly finds it is not Google-signed (the honest ceiling).
            android.content.pm.Signature ours = macapkPlatformSignature(GMS_PACKAGE);
            pi.signatures = (ours != null)
                ? new android.content.pm.Signature[] { ours }
                : new android.content.pm.Signature[] { new android.content.pm.Signature(new byte[0]) };
            try { if (ours != null) pi.signingInfo = new android.content.pm.SigningInfo(pi.signatures); }
            catch (Throwable ignored) {}
            cachedGmsInfo = pi;
            return pi;
        }
    }

    /** The FCM register service an app binds to. Resolves the register/unregister
     *  actions (and, for old clients, the TOKEN_REQUEST broadcast is answered as
     *  a receiver elsewhere) to com.macapk.gms.PushRegisterService. */
    private ResolveInfo macapkResolveGmsRegisterService(Intent intent) {
        if (intent == null || !PlayServices.pushBackendEnabled()) return null;
        String action = intent.getAction();
        if (!C2DM_REGISTER.equals(action) && !C2DM_UNREGISTER.equals(action)) return null;
        String want = intent.getPackage();
        if (want != null && !GMS_PACKAGE.equals(want)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !GMS_PACKAGE.equals(explicit.getPackageName())) return null;

        android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
        si.packageName = GMS_PACKAGE;
        si.name = PUSH_REGISTER_SERVICE_CLASS;
        si.exported = true;
        si.enabled = true;
        si.applicationInfo = gmsPackageInfo().applicationInfo;
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        ri.resolvePackageName = GMS_PACKAGE;
        ri.isDefault = true;
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }

    /** com.google.android.gms's measurement service (v1.0.1635): the bind an
     *  app's own play-services-measurement makes once availability() has said
     *  SUCCESS. Answered by com.macapk.gms.MeasurementBrokerService, which
     *  hands back the app's own AppMeasurementService -- so it resolves only
     *  when the hosted app declares that service, enabled. An app without it
     *  keeps the failed bind it always had; there is nothing to delegate to.
     *
     *  Gated on availability() rather than on the push/vending flags: this is
     *  part of the client-API provider that availability() already claims, and
     *  with MACAPK_GMS_HONEST_MISSING=1 the SDK never asks (it binds its local
     *  service directly). Without it, Temple Run 2 never left its loading
     *  screen; see MeasurementBrokerService's header. */
    private ResolveInfo macapkResolveGmsMeasurementService(Intent intent) {
        if (intent == null || PlayServices.availability() != 0) return null;
        if (!com.macapk.gms.MeasurementBrokerService.ACTION.equals(intent.getAction())) return null;
        String want = intent.getPackage();
        if (want != null && !GMS_PACKAGE.equals(want)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !GMS_PACKAGE.equals(explicit.getPackageName())) return null;
        Manifest.ServiceEntry local = services.get(com.macapk.gms.MeasurementBrokerService.LOCAL_SERVICE);
        if (local == null || !macapkIsComponentEnabled(local.name, local.enabled, 0)) return null;

        android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
        si.packageName = GMS_PACKAGE;
        si.name = MEASUREMENT_BROKER_SERVICE_CLASS;
        si.exported = true;
        si.enabled = true;
        si.applicationInfo = gmsPackageInfo().applicationInfo;
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        ri.resolvePackageName = GMS_PACKAGE;
        ri.isDefault = true;
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }
    private static final String MEASUREMENT_BROKER_SERVICE_CLASS =
            "com.macapk.gms.MeasurementBrokerService";

    /** com.google.android.gms's Identity sign-in service (One Tap /
     *  Identity.getSignInClient): the bind an app's own play-services-auth
     *  GoogleApi makes on `com.google.android.gms.auth.api.identity.service.signin
     *  .START`. Answered by com.macapk.gms.IdentitySignInService, which serves the
     *  central Google account like the chooser behind GOOGLE_SIGN_IN does. Same
     *  gating as that activity (availability() == SUCCESS and MACAPK_GMS_SIGNIN
     *  not 0): a client told GMS is absent never binds, and with the gate at 0 the
     *  bind fails as it did before. */
    private ResolveInfo macapkResolveGmsIdentityService(Intent intent) {
        if (intent == null) return null;
        if (!com.macapk.gms.IdentitySignInService.ACTION.equals(intent.getAction())) return null;
        String want = intent.getPackage();
        if (want != null && !GMS_PACKAGE.equals(want)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !GMS_PACKAGE.equals(explicit.getPackageName())) return null;
        if (!com.macapk.gms.GoogleSignInBroker.enabled()) return null;
        android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
        si.packageName = GMS_PACKAGE;
        si.name = IDENTITY_SIGNIN_SERVICE_CLASS;
        si.exported = true;
        si.enabled = true;
        si.applicationInfo = gmsPackageInfo().applicationInfo;
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        ri.resolvePackageName = GMS_PACKAGE;
        ri.isDefault = true;
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }
    private static final String IDENTITY_SIGNIN_SERVICE_CLASS =
            "com.macapk.gms.IdentitySignInService";

    /** com.google.android.gms's fused location service: the bind an app's own
     *  play-services-location (or microG's client) makes on
     *  `com.google.android.location.internal.GoogleLocationManagerService.START`.
     *  Answered by com.macapk.gms.GmsLocationService over the platform fused
     *  provider (macOS Location Services). Gated by GmsLocationService.enabled():
     *  we claim Play services, the host bridge is bound, MACAPK_GMS_LOCATION != 0. */
    private ResolveInfo macapkResolveGmsLocationService(Intent intent) {
        if (intent == null) return null;
        if (!com.macapk.gms.GmsLocationService.ACTION.equals(intent.getAction())) return null;
        String want = intent.getPackage();
        if (want != null && !GMS_PACKAGE.equals(want)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !GMS_PACKAGE.equals(explicit.getPackageName())) return null;
        if (!com.macapk.gms.GmsLocationService.enabled()) return null;
        android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
        si.packageName = GMS_PACKAGE;
        si.name = GMS_LOCATION_SERVICE_CLASS;
        si.exported = true;
        si.enabled = true;
        si.applicationInfo = gmsPackageInfo().applicationInfo;
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        ri.resolvePackageName = GMS_PACKAGE;
        ri.isDefault = true;
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }
    private static final String GMS_LOCATION_SERVICE_CLASS =
            "com.macapk.gms.GmsLocationService";

    /** com.google.android.gms's Google sign-in activity: the
     *  `com.google.android.gms.auth.GOOGLE_SIGN_IN` start an app's own
     *  play-services-auth SignInHubActivity makes (setPackage(GMS), for result
     *  40962). Answered by com.macapk.gms.GoogleSignInActivity, which the start
     *  path hosts in the calling app's process (ActivityHost.macapkRouteStart).
     *  Resolves only while we claim Play services (availability() == SUCCESS --
     *  a client that was told GMS is absent never asks) and MACAPK_GMS_SIGNIN is
     *  not 0; an intent aimed at another package or component never resolves
     *  here. `want` is the package restriction as read for this query. */
    private ResolveInfo macapkResolveGmsSignInActivity(Intent intent, String want) {
        if (intent == null) return null;
        if (!com.macapk.gms.GoogleSignInBroker.ACTION_GOOGLE_SIGN_IN.equals(intent.getAction())) return null;
        String pkg = want != null ? want : intent.getPackage();
        if (pkg != null && !GMS_PACKAGE.equals(pkg)) return null;
        ComponentName explicit = intent.getComponent();
        if (explicit != null && !(GMS_PACKAGE.equals(explicit.getPackageName())
                && com.macapk.gms.GoogleSignInBroker.ACTIVITY_CLASS.equals(explicit.getClassName()))) {
            return null;
        }
        if (!com.macapk.gms.GoogleSignInBroker.enabled()) return null;
        android.content.pm.ActivityInfo ai = new android.content.pm.ActivityInfo();
        ai.packageName = GMS_PACKAGE;
        ai.name = com.macapk.gms.GoogleSignInBroker.ACTIVITY_CLASS;
        ai.exported = true;
        ai.enabled = true;
        ai.applicationInfo = gmsPackageInfo().applicationInfo;
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = ai;
        ri.resolvePackageName = GMS_PACKAGE;
        ri.isDefault = true;
        ri.match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY;
        return ri;
    }
}
