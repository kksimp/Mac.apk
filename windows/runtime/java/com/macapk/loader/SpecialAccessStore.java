// com.macapk.loader.SpecialAccessStore -- the runtime's READ side of Android's
// "special app access" app-ops: op 92 MANAGE_EXTERNAL_STORAGE ("All files
// access") and op 110 MANAGE_MEDIA, plus the computed op 87 LEGACY_STORAGE.
//
// Stage S1 of docs/shared-storage-real-mac-folders-design_2026-09-25.md, with
// the adversarial critique's P0 #1 applied: no Android API and no native file
// call of the guest may mint a grant (the limit of that is stated below). The
// design, in one paragraph:
//
//   * On a device the grant lives ONLY in an app-op that Settings writes
//     (ManageExternalStorageDetails.java:111-114, android14:
//     setUidMode(OP_MANAGE_EXTERNAL_STORAGE, uid, newState ? MODE_ALLOWED :
//     MODE_ERRORED)), and only a holder of MANAGE_APP_OPS_MODES may write it
//     (AppOpsService.java:1751-1767 enforceManageAppOpsModes). The permission
//     itself is signature|appop|preinstalled and is NEVER granted to a
//     third-party app (PermissionManagerServiceImpl.java:2810-2856), so
//     checkPermission stays DENIED even after "Allow".
//   * Here the Settings screen is macapk-installhelper's `special-access` verb,
//     a SEPARATE PROCESS that identifies the calling app from its parent process
//     (never from arguments) and asks the user through a CFUserNotification that
//     a system process draws. The helper records the answer in
//     ~/Library/Application Support/Mac.apk/appops/app-ops.json.
//   * THIS CLASS NEVER WRITES. It reads that file, and it believes an entry only
//     when (a) it carries an ECDSA P-256 signature that verifies against the
//     helper's public key (signing-key.pub beside the store; the private half
//     never enters this process), (b) its package + installedAt match the
//     registry row whose bundle is the android root we are running from, and
//     (c) the app DECLARES the permission (Settings disables the switch
//     otherwise, AppStateAppOpsBridge.java:314-322). Anything else reads as
//     "never set", i.e. the op's default mode.
//   * The KEY is package + installedAt, not the row's dirName. On a device
//     app-ops belong to the package (uid) and survive an update; dirName is
//     VERSIONED (<package>_<version>) and every in-place update changes it
//     while keeping installedAt (launcher/InstallCore.swift's install commit),
//     so a dirName key lost the grant on every update. A reinstall gets a new
//     installedAt and so starts with no grant, as AOSP clears app-ops on
//     uninstall. dirName only finds the registry row.
//   * Native guest code cannot open the store directory at all:
//     bionic_shim.cpp's remap sends every path inside it (lexically folded,
//     case-insensitive in the Unicode sense APFS uses, by absolute path or
//     dirfd) to a nonexistent location.
//
// STATED PLAINLY (the design's own "Limit"): this closes every ANDROID-API route
// to a grant -- startActivity, synthetic clicks, AppOpsManager.setMode, writing
// /sdcard or /data paths -- and a native open/fopen/stat/mkdir of the store
// directory by any spelling. It is not an OS boundary, and the signature is NOT
// a second wall behind the shim's block: the public key it is checked against
// lives in the same directory, so anything that can write that directory
// (guest JAVA file I/O, which the shim never sees; an inline `svc #0`; a
// symlink detour) can replace the key and sign its own entry. What the
// signature buys is binding (an entry cannot be edited to name another app or
// install) and rejection of a copied or hand-edited file. Only App Sandbox (or
// a keychain ACL, which an ad-hoc signed helper cannot use without re-prompting
// on every update) would make this a boundary; both are out of scope and
// recorded in the design's section 6.
//
// MACAPK_ALLFILES_HONEST=0 (labelled bisect gate, default ON) restores the
// pre-S1 answers in ONE switch across Environment, AppOpsManager,
// MacApkPackageManager and MediaStore -- the fossify skeptic's requirement that
// gate-off must not reproduce the contradictory answers of a half-applied
// change. Offline gate: debug/allfiles_gate.sh.
package com.macapk.loader;

import java.io.File;
import java.nio.charset.StandardCharsets;

public final class SpecialAccessStore {
    private SpecialAccessStore() {}

    private static final String TAG = "SpecialAccess";

    public static final String OP_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage";
    public static final String OP_MANAGE_MEDIA            = "android:manage_media";
    public static final String OP_LEGACY_STORAGE          = "android:legacy_storage";
    public static final String PERM_MANAGE_EXTERNAL_STORAGE =
            "android.permission.MANAGE_EXTERNAL_STORAGE";
    public static final String PERM_MANAGE_MEDIA = "android.permission.MANAGE_MEDIA";
    /** The two other special-access ops a third-party app can be granted in
     *  Settings and nowhere else (AppOpsManager.java android14 :1950 / :2240):
     *  op 24 SYSTEM_ALERT_WINDOW ("Display over other apps") and op 66
     *  REQUEST_INSTALL_PACKAGES ("Install unknown apps"). Both rows default to
     *  MODE_DEFAULT; Settings writes MODE_ALLOWED / MODE_ERRORED. Asked only
     *  where the helper knows them (allPermsAsk). */
    public static final String OP_SYSTEM_ALERT_WINDOW = "android:system_alert_window";
    public static final String OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages";
    public static final String PERM_SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW";
    public static final String PERM_REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES";
    private static final String PERM_READ_EXTERNAL  = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String PERM_WRITE_EXTERNAL = "android.permission.WRITE_EXTERNAL_STORAGE";

    // AppOpsManager modes (AOSP values; literals so this class has no
    // framework-class dependency order at init).
    static final int MODE_ALLOWED = 0;
    static final int MODE_IGNORED = 1;
    static final int MODE_ERRORED = 2;
    static final int MODE_DEFAULT = 3;

    /** The canonical-payload tag. Bump it (on BOTH sides: here and
     *  launcher/InstallCore.swift AppOpsStore.payloadTag) if the signed field
     *  set ever changes; an old-tag entry then simply fails to verify.
     *  /2 (2026-09-26): dirName left the payload and the key -- see the header.
     *  /3 (2026-09-26, stage S5): the entry gained "perms", the storage runtime
     *  permissions (READ_MEDIA_* and READ/WRITE_EXTERNAL_STORAGE), signed as
     *  `perm:<name>=<state>` lines after the op lines. An ops-only entry the
     *  /2 helper signed (S1's "All files access" / media-management grants,
     *  promoted the same day) is still believed through LEGACY_PAYLOAD_TAG --
     *  see verifyEntry; the helper re-signs it as /3 on its next write
     *  (docs/storage-runtime-permissions_2026-09-26.md section 7). */
    static final String PAYLOAD_TAG = "macapk-appops/3";

    /** The S1 tag, accepted ONLY for an entry with no "perms" object. Safe: a
     *  /2 signature covers exactly the op lines the /2 payload has, and the /3
     *  payload of an entry without "perms" is the /2 payload with only the tag
     *  line changed, so nothing the /2 signer did not sign can be believed.
     *  An entry that carries "perms" must verify as /3 (a /2 signature cannot
     *  cover a permission state). MUST match launcher/InstallCore.swift
     *  AppOpsStore.legacyPayloadTag. */
    static final String LEGACY_PAYLOAD_TAG = "macapk-appops/2";

    /** The store's key for one install: package + "@" + canonical installedAt.
     *  MUST match launcher/InstallCore.swift AppOpsStore.Identity.key. '@'
     *  cannot occur in a package name ([A-Za-z0-9_.]). */
    static String storeKey(String pkg, String installedAt) {
        return pkg + "@" + installedAt;
    }

    private static final boolean HONEST;
    static {
        String e = System.getenv("MACAPK_ALLFILES_HONEST");
        HONEST = e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** MACAPK_ALLFILES_HONEST, default ON. Off = the pre-S1 answers everywhere:
     *  isExternalStorageManager()/isExternalStorageLegacy() true, every op
     *  MODE_ALLOWED except GET_USAGE_STATS, checkPermission GRANTED for a
     *  declared MANAGE_* permission, canManageMedia false, the Settings
     *  special-access intents unresolved, setMode a silent sink. */
    public static boolean honest() { return HONEST; }

    private static final boolean ALL_PERMS_ASK;
    static {
        String e = System.getenv("MACAPK_ALL_PERMS_ASK");
        ALL_PERMS_ASK = windowsHost() && (e == null || e.isEmpty() || e.charAt(0) != '0');
    }

    /** Every dangerous permission group is asked like Android, not only the
     *  storage family and location (RuntimePermissionGroups), and "Display
     *  over other apps" / "Install unknown apps" are special access the user
     *  grants: ON where the helper knows those verbs. That is Win.apk's
     *  launcher (Win.apk.exe runtime-permission --prompt camera ...); the
     *  Mac's macapk-installhelper does not, so there the install-time rule
     *  (declared == held) stays, unchanged. MACAPK_ALL_PERMS_ASK=0 turns it
     *  off on Windows too (the labelled bisect gate; also honest() and
     *  mediaPermsAsk() gate it, as they gate the rest). */
    public static boolean allPermsAsk() { return ALL_PERMS_ASK && HONEST && MEDIA_PERMS_ASK; }

    // ---- where things live -----------------------------------------------

    /** The store: under the home directory SharedStorage pinned at boot (the
     *  JVM's user.home, i.e. the passwd entry -- the same home
     *  bionic_shim.cpp's macapk_store_block_dirs denies to guest native code).
     *  Never a live System.getProperty("user.home"): a guest can setProperty
     *  that before this class is first used, and would then choose the store
     *  (and the public key) it is judged by. And deliberately NO environment
     *  override (an earlier MACAPK_APPOPS_FILE was removed): a guest's native
     *  setenv survives into the process the in-app relaunch execs, so an
     *  override would let it move the store and its key to a directory it
     *  owns. Offline gates relocate with -Duser.home instead. */
    private static final File STORE_FILE = defaultStoreFile();
    public static File storeFile() { return STORE_FILE; }
    static File defaultStoreFile() {
        if (windowsHost()) return new File(windowsDataDir(), "appops/app-ops.json");
        return new File(SharedStorage.hostHome(),
                "Library/Application Support/Mac.apk/appops/app-ops.json");
    }

    /** Win.apk: the store, its key and the registry live in the host's data
     *  folder (%LOCALAPPDATA%\Win.apk), the parent of the android root the host
     *  pins with -Dmacapk.android.root before any guest code runs. The helper
     *  there is the launcher (Win.apk.exe runtime-permission / special-access /
     *  app-details), which writes them as macapk-installhelper does here. */
    static boolean windowsHost() { return File.separatorChar == '\\'; }
    static File windowsDataDir() {
        File root = new File(SharedStorage.androidRoot()).getAbsoluteFile();
        File parent = root.getParentFile();
        return parent != null ? parent : root;
    }
    static File publicKeyFile() {
        File dir = STORE_FILE.getParentFile();
        return new File(dir != null ? dir : new File("."), "signing-key.pub");
    }
    /** The installed-app registry our identity is read from: the real one,
     *  under the pinned home. NOT MacApkPackageManager's MACAPK_PM_REGISTRY_FILE
     *  override, for the reason storeFile() has none: a guest could set it and
     *  relaunch, and pick which install of its own package it claims to be. A
     *  harness that points PackageManager at a fixture registry therefore gets
     *  no special-access identity (fails closed); the offline gate relocates
     *  the whole home with -Duser.home. */
    static File registryFile() { return defaultRegistryFile(); }
    static File defaultRegistryFile() {
        if (windowsHost()) return new File(windowsDataDir(), "installed_apps.json");
        return new File(SharedStorage.hostHome(),
                "Library/Application Support/Mac.apk/installed_apps.json");
    }

    // ---- the snapshot ------------------------------------------------------

    /** Who we are in the registry, and what the store grants us. Rebuilt when
     *  any of the three files changes (a stat each per query, the same
     *  stat-per-query cost InstalledRegistry already pays). */
    static final class Snapshot {
        final String dirName, pkg, installedAt;   // null dirName = no registry identity
        final java.util.Map<String, Integer> modes; // op -> MODE_ALLOWED / MODE_ERRORED
        /** Stage S5: storage runtime permission -> state token (PERM_STATES). */
        final java.util.Map<String, String> perms;
        final String why;                           // one-line diagnosis for logs
        Snapshot(String dirName, String pkg, String installedAt,
                 java.util.Map<String, Integer> modes, String why) {
            this(dirName, pkg, installedAt, modes, java.util.Collections.emptyMap(), why);
        }
        Snapshot(String dirName, String pkg, String installedAt,
                 java.util.Map<String, Integer> modes, java.util.Map<String, String> perms,
                 String why) {
            this.dirName = dirName; this.pkg = pkg; this.installedAt = installedAt;
            this.modes = modes; this.perms = perms; this.why = why;
        }
    }
    private static volatile Object[] sCache;   // {long[] key, Snapshot}

    /** Forget the cached snapshot. SpecialAccessHandler calls it after the
     *  helper returns; the stat-keyed cache would catch the change anyway, but
     *  an mtime can repeat inside one second on some filesystems. */
    public static void invalidate() { sCache = null; }

    private static long[] statKey() {
        File s = STORE_FILE, p = publicKeyFile(), r = registryFile();
        return new long[] { s.lastModified(), s.length(), p.lastModified(), p.length(),
                            r.lastModified(), r.length() };
    }

    static Snapshot snapshot() {
        long[] key = statKey();
        Object[] c = sCache;
        if (c != null && java.util.Arrays.equals((long[]) c[0], key)) return (Snapshot) c[1];
        Snapshot s = build();
        sCache = new Object[] { key, s };
        logOnce("[appops] " + s.why);
        return s;
    }

    private static Snapshot build() {
        java.util.Map<String, Integer> none = java.util.Collections.emptyMap();
        String[] self = selfIdentity();
        if (self == null) {
            return new Snapshot(null, null, null, none,
                    "no registry row owns android root " + SharedStorage.androidRoot()
                    + " -- no stored special access (dev binary or harness)");
        }
        String dirName = self[0], pkg = self[1], installedAt = self[2];
        if (!STORE_FILE.isFile()) {
            return new Snapshot(dirName, pkg, installedAt, none,
                    dirName + ": no store at " + STORE_FILE);
        }
        java.security.PublicKey key = loadPublicKey();
        if (key == null) {
            return new Snapshot(dirName, pkg, installedAt, none,
                    dirName + ": store present but no usable helper public key at "
                    + publicKeyFile() + " -- every entry is unverifiable, none is believed");
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(STORE_FILE.toPath()),
                                     StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(text);
            org.json.JSONObject apps = root.optJSONObject("apps");
            String k = storeKey(pkg, installedAt);
            org.json.JSONObject e = apps != null ? apps.optJSONObject(k) : null;
            if (e == null) {
                return new Snapshot(dirName, pkg, installedAt, none,
                        dirName + ": no entry " + k + " in " + STORE_FILE);
            }
            String why = verifyEntry(e, pkg, installedAt, key);
            if (why != null) {
                return new Snapshot(dirName, pkg, installedAt, none,
                        dirName + ": entry REJECTED (" + why + ")");
            }
            java.util.Map<String, Integer> modes = new java.util.HashMap<>();
            org.json.JSONObject ops = e.optJSONObject("ops");
            for (String op : new String[] { OP_MANAGE_EXTERNAL_STORAGE, OP_MANAGE_MEDIA,
                                            OP_SYSTEM_ALERT_WINDOW, OP_REQUEST_INSTALL_PACKAGES }) {
                String m = ops != null ? ops.optString(op, "") : "";
                if ("allow".equals(m)) modes.put(op, MODE_ALLOWED);
                else if ("deny".equals(m)) modes.put(op, MODE_ERRORED);
            }
            // Stage S5: only the runtime permissions a helper asks about, and
            // only a state the helper can write; anything else reads as "never
            // set" (it is still covered by the signature, which verifyEntry
            // checked).
            java.util.Map<String, String> perms = new java.util.TreeMap<>();
            org.json.JSONObject pj = e.optJSONObject("perms");
            if (pj != null) {
                java.util.Iterator<String> it = pj.keys();
                while (it.hasNext()) {
                    String p = it.next();
                    String st = pj.optString(p, "");
                    if ((isStoragePermission(p) || isLocationPermission(p)
                                || RuntimePermissionGroups.isTracked(p))
                            && PERM_STATES.contains(st)) {
                        perms.put(p, st);
                    }
                }
            }
            return new Snapshot(dirName, pkg, installedAt, modes, perms,
                    dirName + ": verified entry " + modes
                    + (perms.isEmpty() ? "" : " perms " + perms));
        } catch (Throwable t) {
            return new Snapshot(dirName, pkg, installedAt, none,
                    dirName + ": unreadable store " + STORE_FILE + " (" + t + ")");
        }
    }

    /** null when the entry is genuine and bound to us, else the reason. */
    static String verifyEntry(org.json.JSONObject e, String pkg,
                              String installedAt, java.security.PublicKey key) {
        String ePkg = e.optString("package", "");
        String eInst = e.optString("installedAt", "");
        if (!pkg.equals(ePkg)) return "package " + ePkg + " != " + pkg;
        if (!installedAt.equals(eInst)) {
            return "installedAt " + eInst + " != registry " + installedAt
                 + " (a reinstall starts with no grant)";
        }
        String sig = e.optString("sig", "");
        if (sig.isEmpty()) return "unsigned";
        java.util.TreeMap<String, String> ops = new java.util.TreeMap<>();
        org.json.JSONObject o = e.optJSONObject("ops");
        if (o != null) {
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                ops.put(k, o.optString(k, ""));
            }
        }
        // Stage S5: every "perms" pair is signed too, recognised or not, so an
        // entry cannot carry an unsigned permission state the helper never
        // wrote.
        java.util.TreeMap<String, String> perms = new java.util.TreeMap<>();
        org.json.JSONObject pj = e.optJSONObject("perms");
        if (pj != null) {
            java.util.Iterator<String> it = pj.keys();
            while (it.hasNext()) {
                String k = it.next();
                perms.put(k, pj.optString(k, ""));
            }
        }
        String upd = e.optString("updatedAt", ""), by = e.optString("by", "");
        byte[] payload = canonicalPayload(PAYLOAD_TAG, ePkg, eInst, ops, perms, upd, by);
        if (payload == null) return "malformed field";
        String why = verifySig(key, payload, sig);
        if (why == null) return null;
        // An S1 (/2) entry: ops only, no "perms" object at all (has(), not an
        // empty-object test -- an empty "perms" was written by a /3 signer).
        if (!e.has("perms")) {
            byte[] legacy = canonicalPayload(LEGACY_PAYLOAD_TAG, ePkg, eInst, ops,
                    java.util.Collections.<String, String>emptySortedMap(), upd, by);
            if (legacy != null && verifySig(key, legacy, sig) == null) return null;
        }
        return why;
    }

    private static String verifySig(java.security.PublicKey key, byte[] payload, String sig) {
        try {
            java.security.Signature v = java.security.Signature.getInstance("SHA256withECDSA");
            v.initVerify(key);
            v.update(payload);
            if (!v.verify(java.util.Base64.getDecoder().decode(sig))) return "bad signature";
        } catch (Throwable t) {
            return "signature check failed: " + t;
        }
        return null;
    }

    /** The bytes the helper signs. MUST match launcher/InstallCore.swift
     *  AppOpsStore.canonicalPayload byte for byte: UTF-8, '\n'-terminated
     *  lines, ops sorted by name (ASCII order, which is String.compareTo order
     *  for these ASCII names). Null when a field could smuggle a line break. */
    static byte[] canonicalPayload(String pkg, String installedAt,
                                   java.util.SortedMap<String, String> ops,
                                   String updatedAt, String by) {
        return canonicalPayload(pkg, installedAt, ops,
                java.util.Collections.<String, String>emptySortedMap(), updatedAt, by);
    }

    /** The /3 payload: the op lines, then one `perm:<name>=<state>` line per
     *  stored runtime permission, sorted by name (ASCII order, as for ops). MUST
     *  match launcher/InstallCore.swift AppOpsStore.canonicalPayload. */
    static byte[] canonicalPayload(String pkg, String installedAt,
                                   java.util.SortedMap<String, String> ops,
                                   java.util.SortedMap<String, String> perms,
                                   String updatedAt, String by) {
        return canonicalPayload(PAYLOAD_TAG, pkg, installedAt, ops, perms, updatedAt, by);
    }

    /** ... under an explicit tag (verifyEntry's /2 fallback; the gate's
     *  fixtures). */
    static byte[] canonicalPayload(String tag, String pkg, String installedAt,
                                   java.util.SortedMap<String, String> ops,
                                   java.util.SortedMap<String, String> perms,
                                   String updatedAt, String by) {
        StringBuilder sb = new StringBuilder();
        String[] fixed = { pkg, installedAt, updatedAt, by };
        for (String f : fixed) if (f == null || f.indexOf('\n') >= 0) return null;
        sb.append(tag).append('\n');
        sb.append("package=").append(pkg).append('\n');
        sb.append("installedAt=").append(installedAt).append('\n');
        for (java.util.Map.Entry<String, String> op : ops.entrySet()) {
            if (op.getKey().indexOf('\n') >= 0 || op.getValue().indexOf('\n') >= 0) return null;
            sb.append("op:").append(op.getKey()).append('=').append(op.getValue()).append('\n');
        }
        for (java.util.Map.Entry<String, String> p : perms.entrySet()) {
            if (p.getKey().indexOf('\n') >= 0 || p.getValue().indexOf('\n') >= 0) return null;
            sb.append("perm:").append(p.getKey()).append('=').append(p.getValue()).append('\n');
        }
        sb.append("updatedAt=").append(updatedAt).append('\n');
        sb.append("by=").append(by).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The helper's public key: base64 of an X.509 SubjectPublicKeyInfo for a
     *  P-256 key (CryptoKit's P256.Signing.PublicKey.derRepresentation). */
    static java.security.PublicKey loadPublicKey() {
        try {
            File f = publicKeyFile();
            if (!f.isFile()) return null;
            String b64 = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                                    StandardCharsets.US_ASCII).trim();
            java.security.PublicKey k = java.security.KeyFactory.getInstance("EC")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(
                    java.util.Base64.getDecoder().decode(b64)));
            if (!(k instanceof java.security.interfaces.ECPublicKey)) return null;
            int bits = ((java.security.interfaces.ECPublicKey) k).getParams()
                           .getCurve().getField().getFieldSize();
            return bits == 256 ? k : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- identity: which registry row are we? --------------------------------

    /** {dirName, package, canonical installedAt} of the registry row whose
     *  bundle holds our android root (the stub runs us with --android-root
     *  <bundle>/Contents/android), or null. The package must also be the one
     *  this process is running: a row for some other app that happens to share
     *  the root is not us. dirName is only for the logs; the grant is keyed by
     *  the other two (storeKey). */
    static String[] selfIdentity() {
        File reg = registryFile();
        if (!reg.isFile()) return null;
        String root = normalizeDir(SharedStorage.androidRoot());
        String running = runningPackage();
        // Win.apk: every app shares one android root and the registry keeps one
        // row per package (the launcher's write_registry), so the row is the
        // running package's; without a known package there is no identity.
        final boolean windows = windowsHost();
        if (windows && running == null) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(reg.toPath()),
                                     StandardCharsets.UTF_8).trim();
            org.json.JSONArray apps = text.startsWith("[")
                ? new org.json.JSONArray(text)
                : new org.json.JSONObject(text).optJSONArray("apps");
            if (apps == null) return null;
            String[] found = null;
            for (int i = 0; i < apps.length(); i++) {
                org.json.JSONObject a = apps.optJSONObject(i);
                if (a == null) continue;
                // "ok" = the pre-v1.0.2239 control panel's spelling of installed
                // (InstallCore.swift AppState.legacyInstalled).
                String st = a.optString("state", "installed");
                if (!"installed".equals(st) && !"ok".equals(st)) continue;
                String bundle = a.optString("bundlePath", "");
                if (bundle.isEmpty()) continue;
                if (!windows && !root.equals(normalizeDir(bundle + "/Contents/android"))) continue;
                String pkg = a.optString("package", "");
                if (pkg.isEmpty() || (running != null && !running.equals(pkg))) continue;
                String dirName = a.optString("dirName", "");
                if (dirName.isEmpty()) continue;
                // Exactly one row may own a bundle -- the helper refuses an
                // ambiguous root (CallerIdentity), so a grant could never have
                // been written for one; do not guess which row we are.
                if (found != null) return null;
                found = new String[] { dirName, pkg,
                        canonicalInstant(a.optString("installedAt", "")) };
            }
            return found;
        } catch (Throwable t) {
            logOnce("[appops] unreadable registry " + reg + ": " + t);
        }
        return null;
    }

    /** The package this process hosts, or null before anything is known (the
     *  offline gate supplies it through Boot's manifest). */
    private static String runningPackage() {
        Manifest m = Boot.macapkManifest();
        if (m != null && m.packageName != null && !m.packageName.isEmpty()) return m.packageName;
        try {
            String p = android.app.ActivityThread.currentPackageName();
            return (p == null || p.isEmpty()) ? null : p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Swift's ISO8601DateFormatter(.withInternetDateTime) spelling --
     *  "2026-09-24T12:34:56Z", whole seconds, UTC -- which is what the helper
     *  signs. A registry row written with fractional seconds by some other tool
     *  normalizes to the same string here, so the binding does not break. */
    static String canonicalInstant(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            return java.time.Instant.parse(raw)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String normalizeDir(String p) {
        if (p == null) return "";
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        try {
            File f = new File(s);
            if (f.exists()) return f.getCanonicalPath();
        } catch (Throwable ignored) {}
        return s;
    }

    // ---- the answers AppOpsManager / Environment / MediaStore consume -------

    /** The mode stored for `op` for THIS app (MODE_ALLOWED / MODE_ERRORED), or
     *  -1 when nothing genuine is stored. */
    public static int storedMode(String op) {
        Integer m = snapshot().modes.get(op);
        return m != null ? m : -1;
    }

    /** AppOpsService's answer for op 92 / op 110: the stored mode, else the
     *  op's default, MODE_DEFAULT (both rows set no default,
     *  AppOpsManager.java:2645-2647 / :2691-2692; AppOpInfo.java:136).
     *
     *  The declared-permission guard: an ALLOWED record for an app that does
     *  not declare the permission reads as never set. Settings could not have
     *  produced it (the switch is disabled, AppStateAppOpsBridge:314-322), so
     *  such a record is either stale (the app dropped the permission in an
     *  update) or not Settings' doing. */
    public static int specialOpMode(String op) {
        int stored = storedMode(op);
        if (stored < 0) return MODE_DEFAULT;
        String perm = permissionForOp(op);
        if (stored == MODE_ALLOWED && perm != null && !declaresPermission(perm)) {
            logOnce("[appops] " + op + " stored ALLOWED but " + perm
                    + " is not declared -- reads as MODE_DEFAULT");
            return MODE_DEFAULT;
        }
        return stored;
    }

    /** The permission whose declaration enables op 92 / op 110's Settings
     *  switch (AppOpsManager.java:2647 / :2692 opToPermission), else null;
     *  op 24 / op 66 the same (:1950 / :2240) where allPermsAsk. */
    public static String permissionForOp(String op) {
        if (OP_MANAGE_EXTERNAL_STORAGE.equals(op)) return PERM_MANAGE_EXTERNAL_STORAGE;
        if (OP_MANAGE_MEDIA.equals(op)) return PERM_MANAGE_MEDIA;
        if (allPermsAsk()) {
            if (OP_SYSTEM_ALERT_WINDOW.equals(op)) return PERM_SYSTEM_ALERT_WINDOW;
            if (OP_REQUEST_INSTALL_PACKAGES.equals(op)) return PERM_REQUEST_INSTALL_PACKAGES;
        }
        return null;
    }

    /** The special-access ops the user can grant on this host. */
    public static java.util.List<String> specialOps() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(OP_MANAGE_EXTERNAL_STORAGE);
        out.add(OP_MANAGE_MEDIA);
        if (allPermsAsk()) {
            out.add(OP_SYSTEM_ALERT_WINDOW);
            out.add(OP_REQUEST_INSTALL_PACKAGES);
        }
        return out;
    }

    /** Settings.canDrawOverlays: AppOpsService's answer for op 24 for a
     *  third-party app -- the stored mode; a never-set op reads MODE_DEFAULT,
     *  which canDrawOverlays treats as "no" for an app targeting M+ (the
     *  permission is not held) and "yes" for one below M (granted at install,
     *  the op's legacy default, AppOpsManager.java :1950 and PermissionManager
     *  ServiceImpl's pre-M grant). null when this is not our question (gate
     *  off): the caller keeps its own rule. */
    public static Boolean overlaysAllowed() {
        if (!allPermsAsk()) return null;
        if (!declaresPermission(PERM_SYSTEM_ALERT_WINDOW)) return Boolean.FALSE;
        Manifest m = Boot.macapkManifest();
        if (m != null && m.targetSdk > 0 && m.targetSdk < 23 && storedMode(OP_SYSTEM_ALERT_WINDOW) < 0) {
            return Boolean.TRUE;
        }
        return specialOpMode(OP_SYSTEM_ALERT_WINDOW) == MODE_ALLOWED;
    }

    /** PackageManager.canRequestPackageInstalls for a declaring app targeting
     *  O+: PackageManagerService.canRequestPackageInstallsInternal reads op 66
     *  through its ExternalSourcesPolicy -- ALLOWED is trusted, ERRORED is
     *  not, DEFAULT falls through to checkUidPermission of a signature|appop
     *  permission no third-party app holds, i.e. false until the user turns
     *  "Install unknown apps" on. null when not our question. */
    public static Boolean installsAllowed() {
        if (!allPermsAsk()) return null;
        return specialOpMode(OP_REQUEST_INSTALL_PACKAGES) == MODE_ALLOWED;
    }

    /** True when the running app's manifest requests `perm` (after AOSP's
     *  min/maxSdkVersion window, which Manifest.usesPermissions already
     *  applied). No manifest known = false: a grant needs positive evidence. */
    public static boolean declaresPermission(String perm) {
        Manifest m = Boot.macapkManifest();
        if (m == null || perm == null) return false;
        for (Manifest.UsesPermissionEntry e : m.usesPermissions) {
            if (perm.equals(e.name)) return true;
        }
        return false;
    }

    /** AOSP op 87 LEGACY_STORAGE, as PermissionPolicyService would have set it
     *  at install (PermissionPolicyService.java:1015-1040 addExtraAppOp, from
     *  SoftRestrictedPermissionPolicy.java:171-200 for READ_EXTERNAL_STORAGE):
     *
     *    - the app never requests READ/WRITE_EXTERNAL_STORAGE (WRITE implies
     *      READ at every target, platform.xml:215): the op is never touched,
     *      so it stays at its row default, MODE_DEFAULT (AppOpsManager
     *      :2634-2635 sets none);
     *    - otherwise ALLOWED only when targetSdk < R (30) AND the app requested
     *      legacy storage -- requestLegacyExternalStorage, which defaults to
     *      targetSdk < Q (ParsingPackageUtils.java:2260) -- else IGNORED.
     *
     *  Not modelled, stated: hasLegacyExternalStorage (an UPGRADE from an
     *  already-legacy install, which is what preserveLegacyExternalStorage
     *  keeps) -- Mac.apk keeps no per-install storage history, so every
     *  install is a fresh one; and FLAG_PERMISSION_APPLY_RESTRICTION, which
     *  every store/sideload installer whitelists away. */
    public static int legacyStorageMode() {
        Manifest m = Boot.macapkManifest();
        if (m == null) return MODE_DEFAULT;
        if (!declaresPermission(PERM_READ_EXTERNAL) && !declaresPermission(PERM_WRITE_EXTERNAL)) {
            return MODE_DEFAULT;
        }
        // AOSP: absent uses-sdk = 0 (DEFAULT_TARGET_SDK_VERSION); a 1 here
        // takes the same branches below (both < 29), so the clamp is harmless.
        int target = m.targetSdk > 0 ? m.targetSdk : 1;
        if (target >= 30) return MODE_IGNORED;
        Boolean req = manifestBool(m.requestLegacyExternalStorageRaw);
        boolean requested = req != null ? req : (target < 29);
        return requested ? MODE_ALLOWED : MODE_IGNORED;
    }

    /** A manifest boolean as parsed: "true"/"false", or a resource reference
     *  ("@bool/x" from a text decode, "@0x7f..." from the binary manifest)
     *  resolved against the app's Resources. null = absent or unresolvable,
     *  which callers treat as "not declared" (the attribute's default). */
    static Boolean manifestBool(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        if (!raw.startsWith("@")) return null;
        try {
            android.app.Application app = android.app.Application.getInstance();
            if (app == null) return null;
            android.content.res.Resources r = app.getResources();
            if (r == null) return null;
            int id;
            if (raw.startsWith("@0x")) {
                id = (int) Long.parseLong(raw.substring(3), 16);
            } else if (raw.startsWith("@bool/")) {
                id = r.getIdentifier(raw.substring(6), "bool", app.getPackageName());
            } else {
                return null;
            }
            return id == 0 ? null : r.getBoolean(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==== Stage S5: storage runtime permissions, asked like Android ==========
    //
    // docs/storage-runtime-permissions_2026-09-26.md is the long form; the
    // design is section 3b / S5 of
    // docs/shared-storage-real-mac-folders-design_2026-09-25.md (owner decision
    // 2026-09-25: media permissions are "Ask like Android").
    //
    // On a device these seven are `dangerous` runtime permissions: DENIED until
    // the user answers PermissionController's GrantPermissionsActivity, the
    // answer (granted + the USER_SET / USER_FIXED flags) persisted per package
    // by PermissionManagerService. Here the dialog is macapk-installhelper's
    // `runtime-permission` verb (a CFUserNotification a system process draws,
    // identity from the parent process -- the same surface and the same signed
    // store as S1's special access), and the state lives in the entry's
    // "perms" object as one token per permission:
    //
    //   granted        granted, FLAG_PERMISSION_USER_SET
    //   granted_fixed  granted, USER_SET | USER_FIXED (a second partial grant)
    //   denied         not granted, USER_SET          ("Don't allow" once)
    //   denied_fixed   not granted, USER_SET | USER_FIXED (denied twice)
    //   (absent)       not granted, no flags          (never asked)
    //
    // KotlinUtils.grantRuntimePermission / revokeRuntimePermission (lineage-21.0
    // PermissionController, :948-1040 / :1268-1340) are the flag rules those
    // tokens encode: a grant clears USER_FIXED and sets USER_SET, a revoke sets
    // USER_SET and sets USER_FIXED only when asked to.
    //
    // Every OTHER dangerous permission keeps the install-time model
    // (declared == GRANTED, MacApkPackageManager.checkPermission): the design
    // scopes S5 to storage, and there is no grant surface for the rest yet.
    //
    // MACAPK_MEDIA_PERMS_ASK=0 (labelled bisect gate, default ON) restores the
    // pre-S5 answers in one switch: checkPermission GRANTED for a declared
    // storage permission, requestPermissions answering from checkSelfPermission
    // with no dialog and no pause/resume, shouldShowRequestPermissionRationale
    // false. Offline gate: debug/mediaperm_gate.sh.

    public static final String PERM_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    public static final String PERM_READ_MEDIA_VIDEO  = "android.permission.READ_MEDIA_VIDEO";
    public static final String PERM_READ_MEDIA_AUDIO  = "android.permission.READ_MEDIA_AUDIO";
    public static final String PERM_READ_MEDIA_VISUAL_USER_SELECTED =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    public static final String PERM_ACCESS_MEDIA_LOCATION =
            "android.permission.ACCESS_MEDIA_LOCATION";
    public static final String PERM_READ_EXTERNAL_STORAGE  = PERM_READ_EXTERNAL;
    public static final String PERM_WRITE_EXTERNAL_STORAGE = PERM_WRITE_EXTERNAL;

    /** AOSP permission-group names (Manifest.permission_group). SUPERGROUP is
     *  ours: PermissionController's STORAGE_SUPERGROUP_PERMISSIONS, the three
     *  groups a pre-T app's single storage prompt covers on a T+ device
     *  (PermissionMapping.kt :50-57, GrantPermissionsViewModel.kt :686-700). */
    public static final String GROUP_STORAGE = "android.permission-group.STORAGE";
    public static final String GROUP_READ_MEDIA_VISUAL = "android.permission-group.READ_MEDIA_VISUAL";
    public static final String GROUP_READ_MEDIA_AURAL  = "android.permission-group.READ_MEDIA_AURAL";
    public static final String GROUP_STORAGE_SUPERGROUP = "macapk.storage-supergroup";

    public static final String ST_GRANTED = "granted";
    public static final String ST_GRANTED_FIXED = "granted_fixed";
    public static final String ST_DENIED = "denied";
    public static final String ST_DENIED_FIXED = "denied_fixed";
    static final java.util.Set<String> PERM_STATES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    ST_GRANTED, ST_GRANTED_FIXED, ST_DENIED, ST_DENIED_FIXED)));

    private static final java.util.List<String> STORAGE_PERMS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(PERM_READ_EXTERNAL, PERM_WRITE_EXTERNAL,
                    PERM_ACCESS_MEDIA_LOCATION, PERM_READ_MEDIA_AUDIO, PERM_READ_MEDIA_IMAGES,
                    PERM_READ_MEDIA_VIDEO, PERM_READ_MEDIA_VISUAL_USER_SELECTED));

    private static final boolean MEDIA_PERMS_ASK;
    static {
        String e = System.getenv("MACAPK_MEDIA_PERMS_ASK");
        MEDIA_PERMS_ASK = e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** MACAPK_MEDIA_PERMS_ASK, default ON. See the section header. */
    public static boolean mediaPermsAsk() { return MEDIA_PERMS_ASK; }

    /** The location runtime permissions (2026-10-05). Same tokens, same signed
     *  store, same helper verb (--prompt location). macOS authorizes location
     *  for Mac.apk as a whole -- locationd keys the client by the shared
     *  runtime's signing identifier -- so the PER-APP Android grant has to be
     *  kept here; MacApkPackageManager.locationPermissionGranted reads it, and
     *  the macOS authorization behind it is only the host's own gate. */
    public static final String PERM_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String PERM_ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";

    public static boolean isLocationPermission(String p) {
        return PERM_ACCESS_FINE_LOCATION.equals(p) || PERM_ACCESS_COARSE_LOCATION.equals(p);
    }

    /** The stored per-app grant for a location permission: is it granted? */
    public static boolean locationGranted(String perm) {
        String st = snapshot().perms.get(perm);
        return ST_GRANTED.equals(st) || ST_GRANTED_FIXED.equals(st);
    }

    /** One of the seven storage runtime permissions S5 asks for. */
    public static boolean isStoragePermission(String p) {
        return p != null && STORAGE_PERMS.contains(p);
    }

    /** The API level that DEFINES the permission (frameworks/base
     *  core/res/AndroidManifest.xml: READ_EXTERNAL_STORAGE since 16,
     *  ACCESS_MEDIA_LOCATION since 29, READ_MEDIA_AUDIO/IMAGES/VIDEO since 33,
     *  READ_MEDIA_VISUAL_USER_SELECTED since 34). Below it the platform has no
     *  such permission, so PackageManagerService never grants it and
     *  shouldShowRequestPermissionRationale answers false for it
     *  (PermissionManagerServiceImpl :1946-1950, `permission == null`). */
    static int sinceLevel(String p) {
        if (PERM_READ_EXTERNAL.equals(p)) return 16;
        if (PERM_WRITE_EXTERNAL.equals(p)) return 4;
        if (PERM_ACCESS_MEDIA_LOCATION.equals(p)) return 29;
        if (PERM_READ_MEDIA_VISUAL_USER_SELECTED.equals(p)) return 34;
        if (PERM_READ_MEDIA_AUDIO.equals(p) || PERM_READ_MEDIA_IMAGES.equals(p)
                || PERM_READ_MEDIA_VIDEO.equals(p)) return 33;
        return Integer.MAX_VALUE;
    }

    /** PermissionController's PermissionMapping.PLATFORM_PERMISSIONS for the
     *  storage family (lineage-21.0 PermissionMapping.kt :94-117): STORAGE is
     *  READ/WRITE (+ ACCESS_MEDIA_LOCATION below T); from T the media
     *  permissions have their own groups and ACCESS_MEDIA_LOCATION moves to
     *  READ_MEDIA_VISUAL; READ_MEDIA_VISUAL_USER_SELECTED joins it from U.
     *  null for a permission the level does not define. */
    static String groupOf(String p, int level) {
        if (p == null || level < sinceLevel(p)) return null;
        if (PERM_READ_EXTERNAL.equals(p) || PERM_WRITE_EXTERNAL.equals(p)) return GROUP_STORAGE;
        if (PERM_ACCESS_MEDIA_LOCATION.equals(p)) {
            return level < 33 ? GROUP_STORAGE : GROUP_READ_MEDIA_VISUAL;
        }
        if (PERM_READ_MEDIA_AUDIO.equals(p)) return GROUP_READ_MEDIA_AURAL;
        if (PERM_READ_MEDIA_IMAGES.equals(p) || PERM_READ_MEDIA_VIDEO.equals(p)
                || PERM_READ_MEDIA_VISUAL_USER_SELECTED.equals(p)) return GROUP_READ_MEDIA_VISUAL;
        return null;
    }

    /** The platform.xml split permissions for the storage family, in the file's
     *  order (frameworks/base data/etc/platform.xml android14 :215-302): a
     *  package that requests `split` and targets below `targetSdk` (no
     *  attribute = CUR_DEVELOPMENT + 1, i.e. every app; SystemConfig
     *  readSplitPermission) implicitly requests `adds` too. Applied in file
     *  order over a growing list, which is what
     *  ParsingPackageUtils.convertSplitPermissions does, so WRITE -> READ ->
     *  media -> USER_SELECTED chains the way it does at install. */
    private static final Object[][] SPLITS = {
        { PERM_WRITE_EXTERNAL, Integer.MAX_VALUE, new String[] { PERM_READ_EXTERNAL } },
        { PERM_READ_EXTERNAL, 29, new String[] { PERM_ACCESS_MEDIA_LOCATION } },
        { PERM_WRITE_EXTERNAL, 29, new String[] { PERM_ACCESS_MEDIA_LOCATION } },
        { PERM_READ_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_AUDIO } },
        { PERM_READ_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_VIDEO } },
        { PERM_READ_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_IMAGES } },
        { PERM_WRITE_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_AUDIO } },
        { PERM_WRITE_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_VIDEO } },
        { PERM_WRITE_EXTERNAL, 33, new String[] { PERM_READ_MEDIA_IMAGES } },
        { PERM_READ_MEDIA_IMAGES, Integer.MAX_VALUE,
              new String[] { PERM_READ_MEDIA_VISUAL_USER_SELECTED } },
        { PERM_READ_MEDIA_VIDEO, Integer.MAX_VALUE,
              new String[] { PERM_READ_MEDIA_VISUAL_USER_SELECTED } },
        { PERM_ACCESS_MEDIA_LOCATION, Integer.MAX_VALUE,
              new String[] { PERM_READ_MEDIA_VISUAL_USER_SELECTED } },
    };

    /** The storage permissions `perm` drags in for a request at `target`
     *  (GrantPermissionsViewModel.getAffectedSplitPermissions :505-520: the
     *  split's new permissions when target < split.targetSdk). One level, as
     *  there. */
    static java.util.List<String> splitsOf(String perm, int target) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object[] s : SPLITS) {
            if (perm.equals(s[0]) && target < (Integer) s[1]) {
                for (String n : (String[]) s[2]) if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    static boolean tokGranted(String t) { return ST_GRANTED.equals(t) || ST_GRANTED_FIXED.equals(t); }
    static boolean tokUserSet(String t) { return t != null && PERM_STATES.contains(t); }
    static boolean tokFixed(String t)   { return ST_GRANTED_FIXED.equals(t) || ST_DENIED_FIXED.equals(t); }

    /**
     * The permission answers for ONE app at ONE platform level, from explicit
     * inputs only, so a gate can evaluate any (level, target, manifest, store)
     * in one JVM. The live answers (storagePermissionGranted etc.) build one
     * from Build.VERSION.SDK_INT, the parsed manifest and the verified store.
     */
    /**
     * KotlinUtils.isPhotoPickerPromptEnabled (lineage-21.0 :232-239):
     * isPhotoPickerPromptSupported() (U+, not Auto/TV/Wear) AND DeviceConfig
     * privacy/photo_picker_prompt_enabled, default true on a phone. OFF here:
     * Mac.apk has no photo picker until stage S8, and the prompt's "Select
     * photos and videos" answer is only honest with one -- without it the
     * choice records limited access with nothing selected, and the follow-up
     * states (SELECT_MORE_PHOTOS, NO_UI_PHOTO_PICKER_REDIRECT) promise a picker
     * that never opens, so the app is left DENIED with no way back but App
     * info (design section 3b: "'Allow' means allow all. 'Select photos' is
     * deferred."). Off is itself a real device configuration: with the flag
     * off, StorageGrantBehavior.getPrompt returns BASIC for READ_MEDIA_VISUAL
     * (:50-52, so the partial-only reject at :63-66 and the redirect / select
     * prompts at :68-77 are unreachable), getPartialGrantPermissions is
     * {USER_SELECTED} (:129-135), and isGroupFullyGranted still excludes only
     * that set (isPhotoPickerPromptSupported is true on U+, :91-102).
     * REMOVAL CONDITION: flip to true, here AND in
     * launcher/InstallHelperMain.swift RuntimePermission.photoPickerPromptEnabled,
     * when the S8 photo picker can back the Select answer.
     */
    static final boolean PHOTO_PICKER_PROMPT_ENABLED = false;

    public static final class PermModel {
        final int level, target;
        /** The storage permissions the app DECLARES (after the maxSdkVersion
         *  window Manifest already applied). */
        final java.util.Set<String> declared;
        /** declared + the implicit split permissions, i.e. what PackageInfo's
         *  requestedPermissions holds on a device for this family. */
        final java.util.Set<String> requested;
        final java.util.Map<String, String> state;
        /** DeviceConfig privacy/photo_picker_prompt_enabled for this model; the
         *  live value is PHOTO_PICKER_PROMPT_ENABLED. A parameter only so the
         *  offline gate can keep the dormant picker rows exercised. */
        final boolean pickerPromptEnabled;

        public PermModel(int level, int target, java.util.Collection<String> declaredAll,
                         java.util.Map<String, String> state) {
            this(level, target, declaredAll, state, PHOTO_PICKER_PROMPT_ENABLED);
        }

        PermModel(int level, int target, java.util.Collection<String> declaredAll,
                  java.util.Map<String, String> state, boolean pickerPromptEnabled) {
            // A caller holding an ApplicationInfo-style read cannot tell "not
            // bound yet" from a real 0 on its own, so it asks the process-wide
            // predicate (a 0 is real only once the manifest has bound it).
            this(level, target, declaredAll, state, pickerPromptEnabled,
                 android.app.Application.macapkTargetSdkKnown(target));
        }

        /** `targetResolved` says whether `target` is a REAL answer. liveModel
         *  passes it from the parsed manifest, which is never "unresolved" (a
         *  0 there IS the app's targetSdk), so a storage check that runs before
         *  Application.macapkInitVersion binds the 0 still gets the legacy
         *  model -- the rule Boot's theme-less default uses for the same reason. */
        PermModel(int level, int target, java.util.Collection<String> declaredAll,
                  java.util.Map<String, String> state, boolean pickerPromptEnabled,
                  boolean targetResolved) {
            this.pickerPromptEnabled = pickerPromptEnabled;
            this.level = level;
            // Unresolved: the modern branch. A REAL 0 (no <uses-sdk>) is the
            // oldest target there is and keeps the legacy model, as on AOSP.
            // MACAPK_TARGETSDK_ZERO=0: targetResolved is `target > 0` on every
            // path, which is the pre-change `target <= 0 ? 10000 : target`.
            this.target = !targetResolved ? 10000 : target;
            java.util.Set<String> d = new java.util.LinkedHashSet<>();
            if (declaredAll != null) for (String p : declaredAll) if (isStoragePermission(p)) d.add(p);
            this.declared = java.util.Collections.unmodifiableSet(d);
            java.util.List<String> req = new java.util.ArrayList<>(d);
            for (Object[] s : SPLITS) {
                if (this.target < (Integer) s[1] && req.contains((String) s[0])) {
                    for (String n : (String[]) s[2]) if (!req.contains(n)) req.add(n);
                }
            }
            this.requested = java.util.Collections.unmodifiableSet(
                    new java.util.LinkedHashSet<>(req));
            this.state = state != null ? state : java.util.Collections.<String, String>emptyMap();
        }

        String tok(String p) { return requested.contains(p) ? state.get(p) : null; }

        /** A pre-M app is granted every runtime permission it requests at
         *  install (PermissionManagerServiceImpl: legacy apps get their
         *  runtime permissions granted, gated only by the app-op). */
        boolean legacyInstallGrant() { return target < 23; }

        /** READ/WRITE_EXTERNAL_STORAGE for an app targeting T+ on a T+
         *  platform: StorageGrantBehavior.getPrompt returns
         *  NO_UI_REJECT_THIS_GROUP (lineage-21.0 StorageGrantBehavior.kt
         *  :40-47), so they can never be granted. */
        boolean storageGroupRejected(String p) {
            return level >= 33 && target >= 33
                && (PERM_READ_EXTERNAL.equals(p) || PERM_WRITE_EXTERNAL.equals(p));
        }

        /** checkPermission for a storage permission. */
        public boolean granted(String p) {
            if (!isStoragePermission(p) || !requested.contains(p)) return false;
            if (level < sinceLevel(p)) return false;
            if (legacyInstallGrant()) return true;
            if (storageGroupRejected(p)) return false;
            return tokGranted(tok(p));
        }

        /** PermissionManagerServiceImpl.shouldShowRequestPermissionRationale
         *  (android14 :1910-1970) for a storage permission: false when
         *  granted, when the platform does not define it, or when it is
         *  USER_FIXED; otherwise whether USER_SET is set. */
        public boolean rationale(String p) {
            if (!isStoragePermission(p) || granted(p)) return false;
            if (level < sinceLevel(p)) return false;
            String t = tok(p);
            if (tokFixed(t)) return false;
            return tokUserSet(t);
        }

        /** The group a request for `p` is prompted under at this level: the
         *  supergroup for a pre-T app on a T+ platform, else PermissionMapping's
         *  group. */
        String promptGroup(String p) {
            String g = groupOf(p, level);
            if (g == null) return null;
            if (level >= 33 && target < 33) return GROUP_STORAGE_SUPERGROUP;
            return g;
        }

        java.util.Set<String> members(String group) {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (String p : requested) {
                if (level < sinceLevel(p)) continue;
                if (group.equals(promptGroup(p))) out.add(p);
            }
            return out;
        }

        /** StorageGrantBehavior.shouldShowPhotoPickerPromptForApp +
         *  appSupportsPhotoPicker (:112-122). Always false while
         *  PHOTO_PICKER_PROMPT_ENABLED is (no picker yet); when the picker
         *  lands, it is narrowed to an app that DECLARES
         *  READ_MEDIA_VISUAL_USER_SELECTED (a device also offers the prompt to a
         *  target-34 app that does not, through the implicit one-time grant
         *  path of grantUserSelectedVisualGroupPermissions). */
        boolean photoPicker() {
            return pickerPromptEnabled && level >= 34 && target >= 33
                && declared.contains(PERM_READ_MEDIA_VISUAL_USER_SELECTED);
        }

        /** getPartialGrantPermissions (:124-130) for READ_MEDIA_VISUAL. */
        java.util.Set<String> partialSet() {
            java.util.Set<String> s = new java.util.LinkedHashSet<>();
            s.add(PERM_READ_MEDIA_VISUAL_USER_SELECTED);
            if (photoPicker()) s.add(PERM_ACCESS_MEDIA_LOCATION);
            return s;
        }

        /** GrantBehavior.isGroupFullyGranted: any member granted
         *  (LightAppPermGroup allowFullGroupGrant), except READ_MEDIA_VISUAL
         *  on U+, where a PARTIAL grant does not count
         *  (StorageGrantBehavior.isGroupFullyGranted :85-97). */
        boolean groupFullyGranted(String group) {
            boolean visualU = GROUP_READ_MEDIA_VISUAL.equals(group) && level >= 34;
            java.util.Set<String> partial = visualU ? partialSet()
                    : java.util.Collections.<String>emptySet();
            for (String p : members(group)) {
                if (partial.contains(p)) continue;
                if (granted(p)) return true;
            }
            return false;
        }

        boolean groupUserSet(String group) {
            for (String p : members(group)) if (tokUserSet(tok(p))) return true;
            return false;
        }

        boolean groupUserFixed(String group) {
            for (String p : members(group)) if (tokFixed(tok(p))) return true;
            return false;
        }

        /** One dialog (or its no-UI outcome) of a request. */
        public static final class Step {
            public final String kind;      // PROMPT, AUTO, REJECT, FIXED, REDIRECT
            public final String group;
            public final String prompt;    // basic, select, select_more, supergroup_pre_q, supergroup_q_to_s
            public final java.util.Set<String> perms;
            public final java.util.Set<String> members;
            public final java.util.Set<String> partial;
            Step(String kind, String group, String prompt, java.util.Set<String> perms,
                 java.util.Set<String> members, java.util.Set<String> partial) {
                this.kind = kind; this.group = group; this.prompt = prompt; this.perms = perms;
                this.members = members; this.partial = partial;
            }
            @Override public String toString() {
                return kind + (prompt != null ? "(" + prompt + ")" : "") + " "
                     + shortGroup(group) + " " + shortPerms(perms);
            }
        }

        /**
         * What GrantPermissionsViewModel would do with a request for
         * `request`, restricted to the storage family (other permissions are
         * not asked about -- see the section header). Groups in first-request
         * order. Mirrors onPackageLoaded (affected = requested + splits,
         * :279-290), addRequiredGroupStates / isPermissionGrantableAndNotFixed
         * (:456-564: a user-fixed group is dropped), getGroupState (:566-590:
         * a fully granted group auto-grants the rest with no UI) and
         * setRequestInfosFromGroupStates (:331-380: NO_UI_REJECT_THIS_GROUP
         * is answered with no UI).
         */
        /** The dialog to show instead when the helper REFUSES an AUTO step
         *  (exit 6: its own reading of the store found no granted permission
         *  in the group -- the store changed between the plan and the run, or
         *  the two sides' "fully granted" rules drifted apart). What plan()
         *  would have chosen had the group not counted as granted, so a refusal
         *  becomes the question a device would have asked, never a silent,
         *  permanent DENIED. null when the group is USER_FIXED (no UI, as
         *  plan()'s FIXED). */
        public Step promptInstead(Step auto) {
            if (auto == null || groupUserFixed(auto.group)) return null;
            if (GROUP_STORAGE_SUPERGROUP.equals(auto.group)) {
                return new Step("PROMPT", auto.group,
                        target < 29 ? "supergroup_pre_q" : "supergroup_q_to_s",
                        new java.util.LinkedHashSet<>(auto.members), auto.members, null);
            }
            if (GROUP_READ_MEDIA_VISUAL.equals(auto.group) && photoPicker()) {
                return new Step("PROMPT", auto.group,
                        tokGranted(tok(PERM_READ_MEDIA_VISUAL_USER_SELECTED)) ? "select_more" : "select",
                        auto.perms, auto.members, partialSet());
            }
            return new Step("PROMPT", auto.group, "basic", auto.perms, auto.members, null);
        }

        public java.util.List<Step> plan(String[] request) {
            java.util.List<Step> out = new java.util.ArrayList<>();
            if (request == null || legacyInstallGrant()) return out;
            java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> byGroup =
                    new java.util.LinkedHashMap<>();
            for (String p : request) {
                if (!isStoragePermission(p) || !requested.contains(p)) continue;
                if (level < sinceLevel(p)) continue;
                java.util.List<String> affected = new java.util.ArrayList<>();
                affected.add(p);
                for (String s : splitsOf(p, target)) {
                    if (requested.contains(s) && level >= sinceLevel(s) && !affected.contains(s)) {
                        affected.add(s);
                    }
                }
                for (String a : affected) {
                    String g = promptGroup(a);
                    if (g == null) continue;
                    byGroup.computeIfAbsent(g, k -> new java.util.LinkedHashSet<>()).add(a);
                }
            }
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> e : byGroup.entrySet()) {
                String g = e.getKey();
                java.util.Set<String> members = members(g);
                java.util.LinkedHashSet<String> perms = new java.util.LinkedHashSet<>(e.getValue());
                if (GROUP_STORAGE.equals(g) && level >= 33 && target >= 33) {
                    out.add(new Step("REJECT", g, null, perms, members, null));
                    continue;
                }
                boolean allGranted = true;
                for (String p : perms) if (!granted(p)) { allGranted = false; break; }
                if (allGranted) continue;                       // STATE_GRANTED, nothing to do
                java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
                for (String p : perms) if (!granted(p)) missing.add(p);
                if (groupFullyGranted(g)) {
                    out.add(new Step("AUTO", g, null, missing, members, null));
                    continue;
                }
                boolean visualPicker = GROUP_READ_MEDIA_VISUAL.equals(g) && photoPicker();
                String us = tok(PERM_READ_MEDIA_VISUAL_USER_SELECTED);
                boolean usGranted = tokGranted(us);
                boolean usFixedGranted = ST_GRANTED_FIXED.equals(us);
                // StorageGrantBehavior.isPermissionFixed (:99-110): a fixed,
                // granted USER_SELECTED never filters (the picker opens instead).
                if (!(visualPicker && usFixedGranted) && groupUserFixed(g)) {
                    out.add(new Step("FIXED", g, null, missing, members, null));
                    continue;
                }
                if (GROUP_STORAGE_SUPERGROUP.equals(g)) {
                    // onPermissionGrantResult (:686-700): a legacy app's answer
                    // is applied to ALL its storage-family permissions.
                    out.add(new Step("PROMPT", g,
                            target < 29 ? "supergroup_pre_q" : "supergroup_q_to_s",
                            new java.util.LinkedHashSet<>(members), members, null));
                    continue;
                }
                if (visualPicker) {
                    java.util.Set<String> partial = partialSet();
                    if (partial.containsAll(missing)) {
                        // "Do not allow apps to request only
                        // READ_MEDIA_VISUAL_USER_SELECTED" (:60-63).
                        out.add(new Step("REJECT", g, null, missing, members, partial));
                        continue;
                    }
                    if (usFixedGranted) {
                        out.add(new Step("REDIRECT", g, null, missing, members, partial));
                        continue;
                    }
                    out.add(new Step("PROMPT", g, usGranted ? "select_more" : "select",
                            missing, members, partial));
                    continue;
                }
                out.add(new Step("PROMPT", g, "basic", missing, members, null));
            }
            return out;
        }
    }

    static String shortGroup(String g) {
        if (g == null) return "?";
        int i = g.lastIndexOf('.');
        return i >= 0 ? g.substring(i + 1) : g;
    }

    static String shortPerms(java.util.Collection<String> ps) {
        StringBuilder sb = new StringBuilder("[");
        for (String p : ps) {
            if (sb.length() > 1) sb.append(',');
            int i = p.lastIndexOf('.');
            sb.append(i >= 0 ? p.substring(i + 1) : p);
        }
        return sb.append(']').toString();
    }

    /** The model for the running app, or null when S5 does not apply (gate
     *  off, or no parsed manifest -- MacApkPackageManager then keeps its
     *  permissive no-manifest answer). */
    public static PermModel liveModel() {
        if (!MEDIA_PERMS_ASK) return null;
        Manifest m = Boot.macapkManifest();
        if (m == null) return null;
        java.util.List<String> declared = new java.util.ArrayList<>();
        for (Manifest.UsesPermissionEntry e : m.usesPermissions) {
            if (e.name != null) declared.add(e.name);
        }
        int level;
        try { level = android.os.Build.VERSION.SDK_INT; } catch (Throwable t) { level = 29; }
        // The MANIFEST's own value: a 0 is AOSP's no-<uses-sdk> default, real
        // even before macapkInitVersion binds it (MACAPK_TARGETSDK_ZERO; =0
        // restores `> 0`, and the old synthesis never produced a 0 anyway).
        boolean resolved = Manifest.TARGETSDK_ZERO ? m.targetSdk >= 0 : m.targetSdk > 0;
        return new PermModel(level, m.targetSdk, declared, snapshot().perms,
                             PHOTO_PICKER_PROMPT_ENABLED, resolved);
    }

    /** checkPermission's answer for a storage permission: TRUE / FALSE, or
     *  null when S5 does not decide it (not a storage permission, gate off, no
     *  manifest) and the caller keeps its own rule. */
    public static Boolean storagePermissionGranted(String perm) {
        if (!isStoragePermission(perm)) return null;
        PermModel m = liveModel();
        return m == null ? null : m.granted(perm);
    }

    /** Activity.shouldShowRequestPermissionRationale for the running app. */
    public static boolean shouldShowRationale(String perm) {
        // PermissionManagerServiceImpl.shouldShowRequestPermissionRationale: the
        // user answered "Don't allow" once (USER_SET) but not twice (USER_FIXED).
        if (isLocationPermission(perm)) return ST_DENIED.equals(snapshot().perms.get(perm));
        if (RuntimePermissionGroups.isTracked(perm)) {
            GroupModel g = liveGroupModel();
            return g != null && g.rationale(perm);
        }
        PermModel m = liveModel();
        return m != null && m.rationale(perm);
    }

    // ==== Every other dangerous group, asked like Android (allPermsAsk) ======
    //
    // The same tokens, store and helper as the storage family, for the groups
    // RuntimePermissionGroups lists. The model is deliberately simpler than
    // PermModel's: no photo picker, no supergroup, one prompt per group plus a
    // separate "all the time" prompt for a background permission
    // (GrantPermissionsViewModel's location / sensors background handling:
    // the background permission is dropped from a request while its
    // foreground is not granted, and never granted without it).

    public static final class GroupModel {
        final int level, target;
        /** What the manifest declares, every family. */
        final java.util.Set<String> declaredAll;
        /** The tracked permissions requested: declared + implicit splits. */
        final java.util.Set<String> requested;
        final java.util.Map<String, String> state;
        /** The foreground location grants, from the location model (two
         *  gates on the Mac; here the per-app token). */
        final boolean fineGranted, coarseGranted;

        public GroupModel(int level, int target, java.util.Collection<String> declaredAll,
                          java.util.Map<String, String> state, boolean targetResolved) {
            this.level = level;
            this.target = !targetResolved ? 10000 : target;
            java.util.Set<String> d = new java.util.LinkedHashSet<>();
            if (declaredAll != null) for (String p : declaredAll) if (p != null) d.add(p);
            this.declaredAll = java.util.Collections.unmodifiableSet(d);
            java.util.Set<String> req = new java.util.LinkedHashSet<>();
            for (String p : RuntimePermissionGroups.all()) {
                if (d.contains(p) || !RuntimePermissionGroups.implicitSources(p, this.target, d).isEmpty()
                        || RuntimePermissionGroups.notificationsPreGranted(p, this.target)) {
                    req.add(p);
                }
            }
            this.requested = java.util.Collections.unmodifiableSet(req);
            this.state = state != null ? state : java.util.Collections.<String, String>emptyMap();
            this.fineGranted = tokGranted(this.state.get(PERM_ACCESS_FINE_LOCATION));
            this.coarseGranted = tokGranted(this.state.get(PERM_ACCESS_COARSE_LOCATION));
        }

        String tok(String p) { return requested.contains(p) ? state.get(p) : null; }

        boolean legacyInstallGrant() { return target < 23; }

        boolean defined(String p) { return level >= RuntimePermissionGroups.sinceLevel(p); }

        /** checkPermission for a tracked permission. */
        public boolean granted(String p) {
            if (!RuntimePermissionGroups.isTracked(p) || !requested.contains(p) || !defined(p)) return false;
            if (legacyInstallGrant()) return true;
            if (RuntimePermissionGroups.notificationsPreGranted(p, target)) return true;
            if (RuntimePermissionGroups.isBackground(p)) {
                // Never without a foreground grant; implicit (legacy target)
                // ones follow it, declared ones also need their own answer.
                boolean fg = false;
                for (String f : RuntimePermissionGroups.foregroundOf(p)) {
                    if (PERM_ACCESS_FINE_LOCATION.equals(f)) fg |= fineGranted;
                    else if (PERM_ACCESS_COARSE_LOCATION.equals(f)) fg |= coarseGranted;
                    else fg |= granted(f);
                }
                if (!fg) return false;
                return declaredAll.contains(p) ? tokGranted(tok(p)) : true;
            }
            java.util.List<String> sources = RuntimePermissionGroups.implicitSources(p, target, declaredAll);
            if (!declaredAll.contains(p) && !sources.isEmpty()) {
                // Implicit only: held when a source is (a normal source is).
                for (String s : sources) {
                    if (!RuntimePermissionGroups.isTracked(s) || granted(s)) return true;
                }
                return false;
            }
            return tokGranted(tok(p));
        }

        /** shouldShowRequestPermissionRationale: USER_SET and not USER_FIXED. */
        public boolean rationale(String p) {
            if (granted(p) || !requested.contains(p) || !defined(p) || legacyInstallGrant()) return false;
            String t = tok(p);
            return tokUserSet(t) && !tokFixed(t);
        }

        /** The requested foreground members of `group` at this level. */
        java.util.Set<String> members(String group) {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (String p : requested) {
                if (RuntimePermissionGroups.isBackground(p) || !defined(p)) continue;
                if (group.equals(RuntimePermissionGroups.groupOf(p, level))) out.add(p);
            }
            return out;
        }

        boolean groupFullyGranted(String group) {
            for (String p : members(group)) if (granted(p)) return true;
            return false;
        }
        boolean groupUserFixed(String group) {
            for (String p : members(group)) if (tokFixed(tok(p))) return true;
            return false;
        }

        /** The groups App info lists: every group with a requested member the
         *  user can answer, name -> members (background permissions become
         *  their own "<group>_background" row). Nothing for a pre-M app. */
        public java.util.LinkedHashMap<String, java.util.Set<String>> appDetailsGroups() {
            java.util.LinkedHashMap<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
            if (legacyInstallGrant()) return out;
            for (String p : requested) {
                if (!defined(p) || RuntimePermissionGroups.notificationsPreGranted(p, target)) continue;
                if (RuntimePermissionGroups.isBackground(p)) {
                    if (declaredAll.contains(p)) {
                        out.put(RuntimePermissionGroups.backgroundPromptName(p),
                                new java.util.LinkedHashSet<>(java.util.Collections.singletonList(p)));
                    }
                    continue;
                }
                String g = RuntimePermissionGroups.groupOf(p, level);
                if (g == null) continue;
                String name = RuntimePermissionGroups.promptName(g);
                if (!out.containsKey(name)) out.put(name, members(g));
            }
            return out;
        }

        /** What GrantPermissionsActivity would show for `request`, in first-
         *  request order: per group PROMPT (a dialog), AUTO (a silent grant
         *  into a group with a granted member), FIXED (no UI: denied twice) or
         *  nothing (granted, or not grantable). A background permission is a
         *  PROMPT of its own after its group, run only while a foreground
         *  permission is granted (the handler re-checks, as the helper does). */
        public java.util.List<PermModel.Step> plan(String[] request) {
            java.util.List<PermModel.Step> out = new java.util.ArrayList<>();
            if (request == null || legacyInstallGrant()) return out;
            java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> byGroup = new java.util.LinkedHashMap<>();
            java.util.LinkedHashSet<String> backgrounds = new java.util.LinkedHashSet<>();
            for (String p : request) {
                if (!RuntimePermissionGroups.isTracked(p) || !requested.contains(p) || !defined(p)) continue;
                if (RuntimePermissionGroups.notificationsPreGranted(p, target)) continue;
                if (RuntimePermissionGroups.isBackground(p)) {
                    if (declaredAll.contains(p)) backgrounds.add(p);
                    continue;
                }
                if (!declaredAll.contains(p)) continue;   // implicit: follows its source, no dialog
                String g = RuntimePermissionGroups.groupOf(p, level);
                if (g != null) byGroup.computeIfAbsent(g, k -> new java.util.LinkedHashSet<>()).add(p);
            }
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> e : byGroup.entrySet()) {
                String g = e.getKey();
                java.util.Set<String> members = members(g);
                java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
                for (String p : e.getValue()) if (!granted(p)) missing.add(p);
                if (missing.isEmpty()) continue;
                if (groupFullyGranted(g)) {
                    out.add(new PermModel.Step("AUTO", g, null, missing, members, null));
                } else if (groupUserFixed(g)) {
                    out.add(new PermModel.Step("FIXED", g, null, missing, members, null));
                } else {
                    out.add(new PermModel.Step("PROMPT", g, RuntimePermissionGroups.promptName(g),
                                               missing, members, null));
                }
            }
            for (String b : backgrounds) {
                if (granted(b)) continue;
                java.util.Set<String> one = new java.util.LinkedHashSet<>(java.util.Collections.singletonList(b));
                String g = RuntimePermissionGroups.groupOf(b, level);
                if (tokFixed(tok(b))) {
                    out.add(new PermModel.Step("FIXED", g, null, one, one, null));
                } else {
                    out.add(new PermModel.Step("BACKGROUND", g,
                                               RuntimePermissionGroups.backgroundPromptName(b), one, one, null));
                }
            }
            return out;
        }

        /** A background step's precondition: a granted foreground permission. */
        public boolean foregroundGranted(String background) {
            for (String f : RuntimePermissionGroups.foregroundOf(background)) {
                if (PERM_ACCESS_FINE_LOCATION.equals(f) ? fineGranted
                        : PERM_ACCESS_COARSE_LOCATION.equals(f) ? coarseGranted : granted(f)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The group model for the running app, or null when it does not apply
     *  (gate off, no manifest). */
    public static GroupModel liveGroupModel() {
        if (!allPermsAsk()) return null;
        Manifest m = Boot.macapkManifest();
        if (m == null) return null;
        java.util.List<String> declared = new java.util.ArrayList<>();
        for (Manifest.UsesPermissionEntry e : m.usesPermissions) if (e.name != null) declared.add(e.name);
        int level;
        try { level = android.os.Build.VERSION.SDK_INT; } catch (Throwable t) { level = 29; }
        boolean resolved = Manifest.TARGETSDK_ZERO ? m.targetSdk >= 0 : m.targetSdk > 0;
        return new GroupModel(level, m.targetSdk, declared, snapshot().perms, resolved);
    }

    /** checkPermission's answer for a tracked permission: TRUE / FALSE, or
     *  null when this model does not decide it. */
    public static Boolean trackedPermissionGranted(String perm) {
        if (!RuntimePermissionGroups.isTracked(perm)) return null;
        GroupModel g = liveGroupModel();
        return g == null ? null : g.granted(perm);
    }

    /** Whether the running app HOLDS a runtime permission this class models
     *  (storage or a tracked group; location is MacApkPackageManager's, with
     *  its host gate). null = not modelled here: the caller keeps its rule.
     *  For the framework's own gates (CameraManager, AudioRecord, the
     *  notification switch), which on a device check the permission before
     *  touching the hardware. */
    public static Boolean holds(String perm) {
        Boolean s = storagePermissionGranted(perm);
        return s != null ? s : trackedPermissionGranted(perm);
    }

    /** The stored token for `perm` (for logs and the handler's revoke check). */
    public static String storedPermState(String perm) {
        return snapshot().perms.get(perm);
    }

    /** One line naming what the store says for us, for SpecialAccessHandler's
     *  before/after log. */
    public static String describe() { return snapshot().why; }

    private static volatile String sLastLog;
    private static void logOnce(String msg) {
        if (msg.equals(sLastLog)) return;
        sLastLog = msg;
        // NativeLog is a native method: an offline JVM without the runtime's
        // library throws UnsatisfiedLinkError, which must not escape a log call.
        try { NativeLog.i(TAG, msg); }
        catch (Throwable ignored) {
            try { System.err.println("[" + TAG + "] " + msg); } catch (Throwable ignored2) {}
        }
    }
}
