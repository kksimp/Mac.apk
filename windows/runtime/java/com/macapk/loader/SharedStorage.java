// com.macapk.loader.SharedStorage -- the ONE Java resolver for Android shared
// ("external") storage, and the home of its once-per-volume setup.
//
// Stage S0 of docs/shared-storage-real-mac-folders-design_2026-09-25.md.
// runtime/translator/src/bionic_shim.cpp answers the same question for NATIVE
// code (macapk_ext_root / macapk_app_private_root): every device spelling of the
// primary volume -- /sdcard, /mnt/sdcard, /storage/emulated/0,
// /storage/self/primary -- is one directory, the one externalStorageRoot()
// names here, and /sdcard/Android/{data,obb,media} is the app-private root
// under it. Keep the two in step; stage S3 moves the shared root on both sides
// at once (a Mac.apk-wide folder, __macapk_set_shared_storage_root natively)
// while the app-private root stays in the bundle.
//
// The android-root resolution below used to live in android.os.Environment
// (with copies in Boot, SettingsStore and MediaStoreProvider, see
// docs/shared-storage-research_2026-09-25.md "Where /sdcard is decided");
// Environment now delegates here, so shared storage has one resolver rather
// than a fifth copy.
package com.macapk.loader;

import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;

public final class SharedStorage {
    private SharedStorage() {}

    private static final String TAG = "SharedStorage";

    /** The host home directory as the JVM computed it at startup (the passwd
     *  entry, the same answer bionic_shim.cpp's getpwuid gives), captured when
     *  this class initialises. Boot initialises it before any app code runs
     *  (the ensureDefaultFolders call), so a guest's later
     *  System.setProperty("user.home", ...) cannot move anything that is
     *  located from here -- the special-access store and the registry
     *  SpecialAccessStore binds grants to (review of stage S1, 2026-09-26). */
    private static final String HOST_HOME = System.getProperty("user.home");

    /** See HOST_HOME. */
    public static String hostHome() { return HOST_HOME; }

    /** MACAPK_SDCARD_UNIFY, default ON. The same switch as the native side
     *  (bionic_shim.cpp macapk_sdcard_unify_on), so one setting restores the
     *  whole pre-S0 behaviour for a bisect: here that means
     *  Environment.getExternalStoragePublicDirectory creating its directory
     *  again and ensureDefaultFolders doing nothing. */
    public static boolean unifyEnabled() {
        String e = System.getenv("MACAPK_SDCARD_UNIFY");
        return e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** The android root: -Dmacapk.android.root, then MACAPK_ANDROID_ROOT, then
     *  the dev scratch default; a leading "~" is expanded. */
    public static String androidRoot() {
        String root = System.getProperty("macapk.android.root");
        if (root == null || root.isEmpty()) {
            root = System.getenv("MACAPK_ANDROID_ROOT");
        }
        if (root == null || root.isEmpty()) {
            // Last-ditch dev default. The runtime ALWAYS hands us
            // -Dmacapk.android.root (main.cpp sets it from ResolveAndroidRootDir,
            // apk_unpacker.cpp), so this line is inert in every real run; it is
            // kept in step with that resolver only so a Java-only harness lands
            // in the same tree. It was "~/Documents/android" until 2026-08-12 --
            // that shared root is DELETED and A13_NO_LEGACY_ROOT in the install
            // gate fails if anything recreates it, so do not put the old path
            // back here.
            root = System.getProperty("user.home")
                    + "/Library/Caches/Mac.apk/scratch/android";
        }
        // java.io.File does not expand "~" (see Boot.java for why the raw env
        // fallback needs this).
        if (root.equals("~") || root.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) {
                root = home + root.substring(1);
            }
        }
        return root;
    }

    /** Host directory of the primary shared-storage volume: what /sdcard and
     *  /storage/emulated/0 name on a device, and what
     *  Environment.getExternalStorageDirectory() returns. For an installed app
     *  on the app-data layout, after Boot's adoptCanonicalVolume, the ONE path
     *  every app shares (canonicalVolumePath); otherwise this app's own volume,
     *  <root>/sdcard. Does not create anything. */
    public static File externalStorageRoot() {
        File c = sCanonicalVolume;
        return c != null ? c : appVolume();
    }

    /** This app's own volume, <root>/sdcard: its private Android/ and, on the
     *  app-data layout, links to the shared volume's top-level entries. Every
     *  device path native code names (/sdcard/...) reached it before stage S3,
     *  and app paths saved before it still do. */
    public static File appVolume() {
        return new File(androidRoot(), "sdcard");
    }

    // ---- one volume path for every app (owner 2026-10-20: "yes i think it
    // should be shared like a phone") -----------------------------------------
    //
    // On a device every app names the primary volume /storage/emulated/0, so a
    // path one app hands another names the same file: a frontend (iiSU, ES-DE,
    // Daijisho) builds RetroArch's CONFIGFILE from ITS OWN
    // getExternalStorageDirectory() plus "Android/data/com.retroarch/files/...".
    // Here each app's volume was its own bundle path, so that string named a
    // folder inside the frontend's storage (measured 2026-10-19,
    // app_com.retroarch log). So every installed app on the app-data layout now
    // gets ONE host path for its volume, P = ~/Library/Caches/MacApk/storage/
    // emulated/0, a link to the shared volume MacApk/sdcard.
    //  - P is space-free and has no ".apk" (both break guests:
    //    BundleGenerator.apkSafeDirName), unlike the shared volume's real path
    //    ("Application Support"). It is only a LINK, so a cache purge that
    //    removes it loses nothing; the next launch recreates it.
    //  - Android/{data,obb,media}/<pkg> under P must be the app's OWN private
    //    folder, as on a device: the shared volume gets one link per kind for the
    //    app being launched, to MacApk/<pkg>/sdcard/Android/<kind>/<pkg>. Native
    //    code reaches the same folder without it (the shim folds
    //    <P>/Android/{data,obb,media} into the app-private root).
    //  - Native /sdcard/... follows: __macapk_set_shared_storage_root(P), the
    //    shim's stage-S3 setter, so a folder native code creates at the top of the
    //    volume is shared too.
    //  - Paths an app saved before (inside <App>.app/Contents/android/sdcard) keep
    //    working: that volume's top-level entries are links into the shared one.
    // MACAPK_STORAGE_ONE_PATH=0 restores per-app volume paths exactly.
    private static volatile File sCanonicalVolume;

    public static boolean onePathEnabled() {
        String e = System.getenv("MACAPK_STORAGE_ONE_PATH");
        return e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** P, the one volume path every installed app shares (see above). */
    public static File canonicalVolumePath() {
        return new File(HOST_HOME, "Library/Caches/MacApk/storage/emulated/0");
    }

    /** Boot step, after linkSharedVolume: switch this installed app's volume to
     *  P. @return P, or null when nothing changed. Never throws. */
    public static synchronized File adoptCanonicalVolume() {
        if (!onePathEnabled() || !sharedVolumeEnabled()) return null;
        try {
            String[] id = SpecialAccessStore.selfIdentity();
            if (id == null) return null;            // not an installed bundle
            File p = adoptCanonicalVolume(appVolume(), id[1]);
            if (p != null) {
                try {
                    NativeCocoa.setSharedStorageRoot(p.getPath());
                } catch (Throwable t) {
                    // An older runtime binary without the setter: native /sdcard
                    // keeps naming this app's volume, which links into the same
                    // shared folders, so only a NEW top-level folder native code
                    // creates stays the app's own until its next launch.
                    logQuiet("native shared root not set: " + t);
                }
            }
            return p;
        } catch (Throwable t) {
            NativeLog.w(TAG, "adoptCanonicalVolume failed: " + t);
            return null;
        }
    }

    /** The mechanism, for a given app volume (the offline gate drives it). */
    static File adoptCanonicalVolume(File appVol, String pkg) {
        if (!onePathEnabled() || !sharedVolumeEnabled()) return null;
        java.nio.file.Path shared = sharedVolumeFor(appVol);
        if (shared == null) return null;           // not the app-data layout
        final java.nio.file.LinkOption NF = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        java.nio.file.Path p = canonicalVolumePath().toPath();
        String ps = p.toString();
        if (ps.indexOf(' ') >= 0 || ps.contains(".apk")) {
            logQuiet("one volume path: " + p + " has a space or \".apk\"; keeping per-app paths");
            return null;
        }
        java.nio.file.Path lockFile = shared.getParent().resolve(".sdcard.lock");
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockFile,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lk = ch.lock()) {
            java.nio.file.Path real;
            try {
                real = appVol.toPath().toRealPath();
            } catch (Throwable t) {
                return null;
            }
            // P -> the shared volume. P sits in our own cache folder, so a link
            // there naming anything else is a leftover of ours and is replaced;
            // a real folder there is not ours to remove, and the app keeps its
            // own path.
            java.nio.file.Files.createDirectories(p.getParent());
            if (java.nio.file.Files.isSymbolicLink(p)) {
                if (!java.nio.file.Files.readSymbolicLink(p).equals(shared)) {
                    java.nio.file.Files.delete(p);
                    java.nio.file.Files.createSymbolicLink(p, shared);
                }
            } else if (java.nio.file.Files.exists(p, NF)) {
                logQuiet("one volume path: " + p + " is not a link; keeping per-app paths");
                return null;
            } else {
                java.nio.file.Files.createSymbolicLink(p, shared);
            }
            // <shared>/Android/<kind>/<pkg> -> this app's own private folder. The
            // target is created first: a mkdirs through a dangling link fails.
            for (String kind : new String[] { "data", "obb", "media" }) {
                java.nio.file.Path own = real.resolve("Android").resolve(kind).resolve(pkg);
                java.nio.file.Files.createDirectories(own);
                java.nio.file.Path dir = shared.resolve("Android").resolve(kind);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path link = dir.resolve(pkg);
                if (java.nio.file.Files.isSymbolicLink(link)) {
                    if (java.nio.file.Files.readSymbolicLink(link).equals(own)) continue;
                    java.nio.file.Files.delete(link);
                } else if (java.nio.file.Files.exists(link, NF)) {
                    // Real content in the SHARED Android/ (an app wrote there
                    // before its link existed): leave it, and say so.
                    logQuiet("one volume path: " + link + " is a real folder; left alone");
                    continue;
                }
                java.nio.file.Files.createSymbolicLink(link, own);
            }
        } catch (Throwable t) {
            logQuiet("one volume path: " + t);
            return null;
        }
        sCanonicalVolume = p.toFile();
        logQuiet("one volume path: " + p + " -> " + shared + " (Android/*/" + pkg + " -> own)");
        return sCanonicalVolume;
    }

    // ---- real Mac folders (owner reversal 2026-09-28) ------------------------
    //
    // docs/shared-storage-real-mac-folders-design_2026-09-25.md, top section
    // "OWNER REVERSAL 2026-09-28" and stage S7 pulled forward: five of the
    // volume's standard collection folders ARE the Mac user's real folders. The
    // mechanism is a symlink in the volume root, so Java File/NIO and native
    // code (bionic_shim.cpp macapk_ext_root) name the SAME directory with no
    // translation on either side -- /sdcard/Pictures and
    // Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES) both
    // land in ~/Pictures because the host resolves the link, not because two
    // resolvers agree. Only Mac.apk's own setup (this method, running as Boot
    // before any app code) is MEANT to create links there: a NATIVE guest's
    // symlink() into shared storage is refused (bionic_shim.cpp
    // macapk_symlink_refusal, ENOSYS/EPERM, stage S0; fixed 2026-09-28 to also
    // classify a path reached THROUGH one of these mapping links as shared,
    // which it missed at first -- see macapk_under_mapped_folder). A JAVA
    // guest is NOT refused: android.system.Os.symlink and
    // java.nio.file.Files.createSymbolicLink call straight through with no
    // check at all (a pre-existing S0 gap, not new here). So
    // MediaStoreProvider.walkVolume must not trust "only the mapping links
    // live at the volume root" on its own -- it verifies each root-level link
    // by name AND target (isRecognizedFolderLink) before following it.
    //
    // DCIM is deliberately NOT mapped. On a Mac there is no loose camera-roll
    // folder: a camera or iPhone import goes into the Photos library (the
    // PhotoKit stage in the design note), and mapping DCIM to ~/Pictures/DCIM
    // would create a folder no Mac app uses while making every file in it
    // appear twice to a gallery (as DCIM/x and Pictures/DCIM/x). So DCIM (and
    // DCIM/Camera, which MediaStore keeps visible) stays a real folder of the
    // volume, and what a camera app saves there stays with that app's storage.
    //
    // The trade-off the owner accepted (design doc, top): an app shipping native
    // code can read and change the mapped folders whatever its Android
    // permission says, because nothing at our layer can stop a same-user guest
    // from calling libc. The Android permissions gate the MediaStore route
    // (below); they do NOT gate java.io.File, java.nio or android.system.Os --
    // those read and write the mapped folders with no permission check either,
    // same as native code (review of 2026-09-28: this comment previously said
    // "every ANDROID API route", which overstated it). macOS TCC still asks,
    // per app bundle, before the process touches ~/Documents or ~/Downloads.

    /** True when `link` is a root-level entry a gallery/file-manager walk (or
     *  StorageStatsManager's size walk) may safely follow as a mount: its name
     *  is one of USER_FOLDER_MAP's, AND it actually points at the exact host
     *  folder that entry names -- not merely "some symlink with that name".
     *  Needed because nothing refuses a JAVA-created symlink at the volume
     *  root (see the comment above): a guest could otherwise plant
     *  /sdcard/Pictures -> / (or -> anywhere) and have a walker follow it. */
    public static boolean isRecognizedFolderLink(File link) {
        String name = link.getName();
        // A shared-volume link (linkSharedVolume): exactly "../../sdcard/<name>".
        try {
            java.nio.file.Path t = java.nio.file.Files.readSymbolicLink(link.toPath());
            if (t.equals(java.nio.file.Paths.get("..", "..", SHARED_VOLUME_DIR, name))) return true;
        } catch (Throwable ignored) { }
        for (String[] m : USER_FOLDER_MAP) {
            if (!m[0].equals(name)) continue;
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) return isHostFolderMapping(link);
            try {
                java.nio.file.Path target = java.nio.file.Files.readSymbolicLink(link.toPath());
                return target.equals(new File(HOST_HOME, m[1]).toPath());
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ---- the Windows host's junctions (Win.apk) ------------------------------
    //
    // On Windows the host maps the same five folders before the JVM starts
    // (windows/host/user_folders.cpp), with directory junctions: a symlink
    // needs Developer Mode or an administrator there, a junction does not.
    // Java reports a junction as a plain directory (isSymbolicLink false,
    // readSymbolicLink throws), so without the code below MediaStore would
    // walk the user's whole Downloads as a volume folder, with no privacy
    // filter and no cache, and a recursive walk or delete would follow any
    // junction inside it. The host names what it mapped in
    // WINAPK_USER_FOLDER_TARGETS ("Download=C:\Users\me\Downloads|..."); its
    // presence also means the host owns the mapping (mapUserFolders).

    private static volatile java.util.Map<String, String> sHostTargets;

    /** WINAPK_USER_FOLDER_TARGETS as volume name -> host folder, or null when
     *  the host set none (the Mac, or a run on a scratch data folder). */
    static java.util.Map<String, String> hostFolderTargets() {
        java.util.Map<String, String> m = sHostTargets;
        if (m == null) {
            m = new java.util.HashMap<>();
            String e = System.getenv("WINAPK_USER_FOLDER_TARGETS");
            if (e != null) {
                for (String part : e.split("\\|")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && eq < part.length() - 1) m.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
            sHostTargets = m;
        }
        return m.isEmpty() ? null : m;
    }

    /** True when the host, not this class, maps the user's folders. */
    static boolean hostMapsUserFolders() {
        return File.separatorChar == '\\' || System.getenv("WINAPK_USER_FOLDER_TARGETS") != null;
    }

    /** True when `f`, a root-level volume entry, is one of the host's
     *  junctions: a name the host reported, resolving to exactly the folder it
     *  reported (toRealPath follows a junction). */
    public static boolean isHostFolderMapping(File f) {
        java.util.Map<String, String> m = hostFolderTargets();
        if (m == null) return false;
        String target = m.get(f.getName());
        if (target == null || !isJunction(f.toPath())) return false;
        try {
            return f.toPath().toRealPath().equals(java.nio.file.Paths.get(target).toRealPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** True for a directory junction (or another directory reparse point that
     *  is not a symlink): HotSpot on Windows reports one as isDirectory() AND
     *  isOther() when links are not followed. Always false on the Mac. */
    public static boolean isJunction(java.nio.file.Path p) {
        try {
            java.nio.file.attribute.BasicFileAttributes a = java.nio.file.Files.readAttributes(p,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return a.isDirectory() && a.isOther();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when `p` is a link a walk or a delete must not descend: a symlink,
     *  or a junction that leads somewhere other than where it stands. OneDrive's
     *  cloud placeholders are reparse points too but resolve to themselves, so
     *  they stay real folders. `a` is p's attributes read without following
     *  links (a walk has them), or null to read them here. */
    public static boolean isLink(java.nio.file.Path p, java.nio.file.attribute.BasicFileAttributes a) {
        try {
            if (a == null) {
                a = java.nio.file.Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
            if (a.isSymbolicLink()) return true;
            if (!a.isDirectory() || !a.isOther()) return false;
            java.nio.file.Path parent = p.toAbsolutePath().getParent();
            if (parent == null) return false;
            return !p.toRealPath().toString().equalsIgnoreCase(
                    parent.toRealPath().resolve(p.getFileName().toString()).toString());
        } catch (Throwable t) {
            return true;                            // cannot tell: do not follow it
        }
    }

    /** Breadth-first walk of a folder the host mapped, for MediaStore's scan
     *  and StorageStatsManager's size: shallow files first, at most `limit`
     *  of them (Long.MAX_VALUE: all). A user's Downloads can hold whole source
     *  trees (measured: 495,000 files, 6,000 of them in its top two levels), so
     *  an unbounded scan there stalls every MediaStore query; breadth first
     *  keeps what the user sees in the folder. Skips names starting with ".",
     *  Windows hidden or system entries (desktop.ini) and links, and descends
     *  only where `enter` agrees. Entries come with the attributes the
     *  directory listing already read, so a file costs no extra system call.
     *  Lambdas, not visitor classes: a new framework class moves FRAMEWORK_ID.
     *  @return how many files were passed to onFile. Never throws. */
    public static long walkHostFolder(java.nio.file.Path top, long limit,
            java.util.function.Predicate<java.nio.file.Path> enter,
            java.util.function.BiConsumer<java.nio.file.Path, java.nio.file.attribute.BasicFileAttributes> onFile) {
        java.util.ArrayDeque<java.nio.file.Path> dirs = new java.util.ArrayDeque<>();
        dirs.add(top);
        long[] n = {0};
        while (!dirs.isEmpty() && n[0] < limit) {
            java.nio.file.Path dir = dirs.poll();
            // maxDepth 1: the listing's own entries only, each with the
            // attributes FindNextFile returned (FileTreeWalker uses them as is).
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.find(dir, 1, (p, a) -> {
                if (p.equals(dir) || n[0] >= limit) return false;
                if (p.getFileName().toString().startsWith(".")) return false;
                if (a instanceof java.nio.file.attribute.DosFileAttributes) {
                    java.nio.file.attribute.DosFileAttributes d = (java.nio.file.attribute.DosFileAttributes) a;
                    if (d.isHidden() || d.isSystem()) return false;
                }
                if (a.isDirectory()) {
                    if (!isLink(p, a) && enter.test(p)) dirs.add(p);
                } else if (a.isRegularFile()) {
                    n[0]++;
                    onFile.accept(p, a);
                }
                return false;
            })) {
                s.forEach(p -> { });
            } catch (Throwable t) {
                // An unreadable folder (access denied) is skipped, as listFiles() == null is.
            }
        }
        return n[0];
    }

    /** Volume folder name -> folder under the host home. DCIM: see above. */
    static final String[][] USER_FOLDER_MAP = {
        { "Pictures",  "Pictures"  },   // Environment.DIRECTORY_PICTURES
        { "Movies",    "Movies"    },   // Environment.DIRECTORY_MOVIES
        { "Music",     "Music"     },   // Environment.DIRECTORY_MUSIC
        { "Download",  "Downloads" },   // Environment.DIRECTORY_DOWNLOADS (singular on Android)
        { "Documents", "Documents" },   // Environment.DIRECTORY_DOCUMENTS
    };

    /** MACAPK_USER_FOLDERS, default ON. =0 creates and refreshes no mapping and
     *  moves nothing (links made by an earlier launch are left as they are:
     *  removing one would hide the files it reaches). When the variable is not
     *  set, the Settings switch decides (userFoldersOffFile). */
    public static boolean userFoldersEnabled() {
        String e = System.getenv("MACAPK_USER_FOLDERS");
        if (e != null && !e.isEmpty()) return e.charAt(0) != '0';
        return !userFoldersSettingOff();
    }

    /** The Settings switch (Mac.apk Settings > App data, owner 2026-10-20: "map
     *  the android downloads folder to macos downloads folder"): this file's
     *  presence turns the mapping OFF for every app, and unlike
     *  MACAPK_USER_FOLDERS=0 the next launch then UNMAPS (unmapUserFolders), so
     *  the switch visibly does what it says. It is a file the launcher writes,
     *  not an env var, because a Dock-launched app gets no environment from the
     *  launcher (same reason as space-tap.properties). */
    public static File userFoldersOffFile() {
        return new File(HOST_HOME, "Library/Application Support/Mac.apk/user-folders-off");
    }

    /** True when the Settings switch is OFF and nothing overrides it: an explicit
     *  MACAPK_USER_FOLDERS (gates pin it), or MACAPK_USER_FOLDERS_SETTING=0,
     *  which ignores the switch (the gate's positive control). */
    static boolean userFoldersSettingOff() {
        String e = System.getenv("MACAPK_USER_FOLDERS");
        if (e != null && !e.isEmpty()) return false;
        if ("0".equals(System.getenv("MACAPK_USER_FOLDERS_SETTING"))) return false;
        return userFoldersOffFile().exists();
    }

    /** Settings OFF: each of OUR links (name AND exact target from
     *  USER_FOLDER_MAP) becomes an empty folder of the volume again. Only the
     *  link goes; every file stays in the Mac folder it was always in. A link
     *  Mac.apk did not make, and a real folder, are left alone. Turning the
     *  switch back on maps again, moving in whatever the app saved meanwhile
     *  (mapUserFolders' no-clobber merge). @return links removed. */
    static int unmapUserFolders(File volume) {
        java.nio.file.Path vol = volume.toPath();
        int n = 0;
        for (String[] m : USER_FOLDER_MAP) {
            java.nio.file.Path link = vol.resolve(m[0]);
            java.nio.file.Path target = new File(HOST_HOME, m[1]).toPath();
            try {
                if (!java.nio.file.Files.isSymbolicLink(link)
                        || !java.nio.file.Files.readSymbolicLink(link).equals(target)) continue;
                java.nio.file.Files.delete(link);
                java.nio.file.Files.createDirectory(link);
                n++;
                logQuiet("unmapped " + link + " (Settings: Mac folders off); its files stay in " + target);
            } catch (Throwable t) {
                logQuiet("unmapping " + m[0] + " failed: " + t);
            }
        }
        return n;
    }

    /** Where every move the mapping makes is recorded (src TAB dst), so the
     *  migration is reversible by hand. Outside the volume, beside the
     *  default-folders marker. */
    public static File userFolderJournal() {
        return new File(androidRoot(), "data/system/storage/usermap-journal.tsv");
    }

    /** Map the volume's collection folders onto the real Mac folders, once per
     *  folder, for an INSTALLED app only (the android root is a registry row's
     *  bundle, the identity the grant store uses): the dev binary and every
     *  harness run on a scratch root and must never write into the owner's
     *  real folders (the design's "gates stay hermetic").
     *
     *  Per folder N with host target T (T must already be a directory; a
     *  missing T leaves N a real folder of the volume):
     *    - N is already a link to T: nothing to do;
     *    - N is a link to anything else, or a non-directory: left alone;
     *    - N is a real directory: its contents are MOVED into T first (rename,
     *      never copy-then-delete; a file whose name T already has is moved in
     *      as "name (from <package>).ext" -- the user's file is never renamed,
     *      moved or overwritten; see moveNoClobber). Only when N is empty, down
     *      to its last subfolder, is it removed (rmdir only) and replaced by the
     *      link. Any failure -- EXDEV across volumes, EPERM from macOS privacy,
     *      a read-only T -- stops that folder with everything not yet moved
     *      still in N, so the app keeps reaching all of its files at the same
     *      paths (the moved ones through the link once a later launch finishes
     *      the job);
     *    - N is absent: the link is created.
     *
     *  @return links created this call, or -1 when nothing ran. Never throws. */
    public static synchronized int mapUserFolders() {
        // Win.apk: the host made junctions before the JVM started (and removes
        // them itself when the setting is off). This code would see each as a
        // real folder and "merge" the user's folder into itself, renaming
        // every file in it.
        if (hostMapsUserFolders()) return -1;
        if (!userFoldersEnabled() && !userFoldersSettingOff()) return -1;
        try {
            String[] id = SpecialAccessStore.selfIdentity();
            if (id == null) return -1;              // not an installed bundle
            return mapUserFolders(appVolume(), id[1]);
        } catch (Throwable t) {
            NativeLog.w(TAG, "mapUserFolders failed: " + t);
            return -1;
        }
    }

    /** The mapping itself, for a given volume root (the offline gate drives it
     *  directly). `pkg` names the app in collision suffixes. */
    static int mapUserFolders(File volume, String pkg) {
        if (userFoldersSettingOff()) {
            unmapUserFolders(volume);
            return -1;
        }
        if (!userFoldersEnabled()) return -1;
        java.nio.file.Path vol = volume.toPath();
        volume.mkdirs();
        if (!volume.isDirectory()) return -1;
        int linked = 0;
        for (String[] m : USER_FOLDER_MAP) {
            java.nio.file.Path link = vol.resolve(m[0]);
            java.nio.file.Path target = new File(HOST_HOME, m[1]).toPath();
            try {
                if (!java.nio.file.Files.isDirectory(target)) {
                    logQuiet("user folder " + target + " missing; " + m[0] + " stays in the volume");
                    continue;
                }
                if (isJunction(link)) {
                    logQuiet("left alone: " + link + " is a junction (the Windows host's)");
                    continue;
                }
                if (java.nio.file.Files.isSymbolicLink(link)) {
                    java.nio.file.Path cur = java.nio.file.Files.readSymbolicLink(link);
                    if (!cur.equals(target)) {
                        logQuiet("left alone: " + link + " -> " + cur + " (not ours to change)");
                    }
                    continue;
                }
                if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (!java.nio.file.Files.isDirectory(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        logQuiet("left alone: " + link + " is not a directory");
                        continue;
                    }
                    if (!mergeInto(link, target, pkg)) {
                        logQuiet(m[0] + ": not every file could move into " + target
                                + "; it stays a real folder until a later launch finishes");
                        continue;
                    }
                    if (!removeEmptyTree(link)) {
                        logQuiet(m[0] + ": not empty after the move; left as a real folder");
                        continue;
                    }
                }
                java.nio.file.Files.createSymbolicLink(link, target);
                linked++;
                logQuiet("mapped " + link + " -> " + target);
            } catch (Throwable t) {
                logQuiet("mapping " + m[0] + " failed: " + t);
            }
        }
        return linked;
    }

    /** Move every entry of src into dst without replacing anything in dst.
     *  A subfolder dst already has is merged recursively; everything else moves
     *  by one rename. True when src ends up holding no files (only emptied
     *  subfolders, which removeEmptyTree takes). */
    private static boolean mergeInto(java.nio.file.Path src, java.nio.file.Path dst, String pkg) {
        boolean all = true;
        java.util.List<java.nio.file.Path> kids = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                     java.nio.file.Files.newDirectoryStream(src)) {
            for (java.nio.file.Path p : ds) kids.add(p);
        } catch (Throwable t) {
            return false;
        }
        for (java.nio.file.Path k : kids) {
            java.nio.file.Path there = dst.resolve(k.getFileName().toString());
            boolean kDir = java.nio.file.Files.isDirectory(k, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (kDir && java.nio.file.Files.isDirectory(there, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!mergeInto(k, there, pkg)) all = false;
                continue;
            }
            if (!moveNoClobber(k, dst, pkg)) all = false;
        }
        return all;
    }

    /** Rename `k` into folder `dst` under its own name, or, when dst already has
     *  that name, under "name (from <pkg>).ext", "name (from <pkg>) 2.ext", ...
     *  Files move with link(2)+unlink(2) of the SOURCE name: link fails with
     *  EEXIST instead of replacing, so even a file the user creates in the
     *  instant between the check and the move is never overwritten, and the
     *  data never leaves its inode. Directories (only ever moved to a name dst
     *  does not have) and symlinks use rename(2), whose only replace case --
     *  an EMPTY directory of the same name -- loses nothing. */
    private static boolean moveNoClobber(java.nio.file.Path k, java.nio.file.Path dst, String pkg) {
        String name = k.getFileName().toString();
        boolean regular = java.nio.file.Files.isRegularFile(k, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        for (int n = 0; n < 1000; n++) {
            java.nio.file.Path to = dst.resolve(n == 0 ? name : collisionName(name, pkg, n));
            if (java.nio.file.Files.exists(to, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                if (regular) {
                    java.nio.file.Files.createLink(to, k);
                    try {
                        java.nio.file.Files.delete(k);
                    } catch (Throwable del) {
                        // The link now shares k's inode, so the data is not lost --
                        // but leaving `to` behind while k also survives means the
                        // NEXT launch sees k again and links it in under yet another
                        // collision name, forever (measured: up to the 1000-name
                        // cap). Undo the link so this call fails cleanly and the
                        // source is retried next launch exactly as if the link had
                        // never been attempted.
                        try {
                            if (java.nio.file.Files.isSameFile(to, k)) {
                                java.nio.file.Files.delete(to);
                            }
                        } catch (Throwable ignored) {}
                        logQuiet("could not unlink " + k + " after linking into " + to + ": " + del);
                        return false;
                    }
                } else {
                    java.nio.file.Files.move(k, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
                journal(k, to);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                continue;                                   // lost a race: next name
            } catch (Throwable t) {
                logQuiet("could not move " + k + " -> " + to + ": " + t);
                return false;                               // EXDEV, EPERM, ... : leave it
            }
        }
        return false;
    }

    /** "IMG_1.jpg" -> "IMG_1 (from com.app).jpg"; n > 1 adds " n". A leading
     *  dot (".nomedia") is part of the name, not an extension. */
    static String collisionName(String name, String pkg, int n) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return base + " (from " + pkg + ")" + (n > 1 ? " " + n : "") + ext;
    }

    /** rmdir `dir` and every subfolder, deepest first. Deletes NO file: a file
     *  (or link) left anywhere below makes it return false with that part of the
     *  tree intact. */
    private static boolean removeEmptyTree(java.nio.file.Path dir) {
        java.util.List<java.nio.file.Path> kids = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                     java.nio.file.Files.newDirectoryStream(dir)) {
            for (java.nio.file.Path p : ds) kids.add(p);
        } catch (Throwable t) {
            return false;
        }
        for (java.nio.file.Path k : kids) {
            if (!java.nio.file.Files.isDirectory(k, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
            if (!removeEmptyTree(k)) return false;
        }
        try {
            java.nio.file.Files.delete(dir);    // a directory: fails unless empty
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void journal(java.nio.file.Path from, java.nio.file.Path to) {
        try {
            File j = userFolderJournal();
            File d = j.getParentFile();
            if (d != null) d.mkdirs();
            try (FileOutputStream out = new FileOutputStream(j, true)) {
                out.write((System.currentTimeMillis() / 1000L + "\t" + from + "\t" + to + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private static void logQuiet(String msg) {
        // NativeLog.i is native: an offline JVM has no library behind it.
        try { NativeLog.i(TAG, msg); }
        catch (Throwable t) { if (System.getenv("MACAPK_USERFOLDERS_TRACE") != null) System.err.println(msg); }
    }

    /** AOSP MediaProvider util/FileUtils.java:1016-1048, DEFAULT_FOLDER_NAMES
     *  (the SdkLevel.isAtLeastS() branch, which uses
     *  Environment.DIRECTORY_RECORDINGS). Read from Environment at call time
     *  because AOSP declares those fields assignable. Order is AOSP's. */
    static String[] defaultFolderNames() {
        return new String[] {
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_RINGTONES,
            Environment.DIRECTORY_ALARMS,
            Environment.DIRECTORY_NOTIFICATIONS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_AUDIOBOOKS,
            Environment.DIRECTORY_RECORDINGS,
        };
    }

    /** The "already created" record for the primary volume. AOSP keeps it as
     *  the SharedPreferences key "created_default_folders_" + volume id in
     *  MediaProvider's own preferences (MediaProvider.java:1128-1136) -- per
     *  volume, and OUTSIDE the volume, so an app cannot see or delete it.
     *  Ours lives in the android root's data/system, beside where the design
     *  puts the storage-migration journal, never under the volume. The
     *  primary volume's id is "emulated;0"; ';' becomes '_' in a filename. */
    public static File defaultFoldersMarker() {
        return new File(androidRoot(),
                "data/system/storage/created_default_folders_emulated_0");
    }

    /** AOSP MediaProvider.ensureDefaultFolders (MediaProvider.java:1120-1151):
     *  ONCE per volume, create each default folder that does not exist, then
     *  record that it ran -- "We only do this once per volume so we don't annoy
     *  the user if deleted manually." It replaces the eager mkdirs that
     *  Environment.getExternalStoragePublicDirectory used to do on every call,
     *  which AOSP never does (its javadoc: the directory "may not yet exist").
     *  Called once per launch from Boot before any app code runs; the marker
     *  makes every launch after the first a single stat.
     *
     *  @return how many folders were created, or -1 when nothing ran (already
     *          done, MACAPK_SDCARD_UNIFY=0, or the volume root is unusable).
     *          Never throws. */
    public static synchronized int ensureDefaultFolders() {
        if (!unifyEnabled()) return -1;
        try {
            File marker = defaultFoldersMarker();
            if (marker.exists()) return -1;
            File volume = appVolume();
            volume.mkdirs();
            if (!volume.isDirectory()) {
                NativeLog.w(TAG, "shared storage root unusable, default folders skipped: "
                        + volume);
                return -1;
            }
            int created = 0;
            for (String name : defaultFolderNames()) {
                if (name == null || name.isEmpty()) continue;
                File folder = new File(volume, name);
                if (!folder.exists() && folder.mkdirs()) created++;
            }
            File dir = marker.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write('1');   // AOSP: editor.putInt(key, 1)
            }
            NativeLog.i(TAG, "default folders ensured on " + volume + " (created "
                    + created + ")");
            return created;
        } catch (Throwable t) {
            NativeLog.w(TAG, "ensureDefaultFolders failed: " + t);
            return -1;
        }
    }

    // ---- ONE shared volume for every installed app (2026-10-19) ---------------
    //
    // docs/shared-sdcard-plan_2026-10-19.md, built on the app-data layout of
    // v1.0.2251 (launcher/InstallCore.swift AppDataLayout): an installed app's
    // android root is <App>.app/Contents/android, a link to
    // ~/Library/Application Support/MacApk/<package>/. On a device every app sees
    // the SAME primary volume (/storage/emulated/0); here each app had its own
    // <root>/sdcard. The shared volume is MacApk/sdcard, a sibling of the app
    // folders (a package name always has a dot, so it never collides).
    //
    // How an app reaches it: the app keeps <root>/sdcard as its volume path (so a
    // guest is never handed the real Application Support path, which has a
    // space), and each top-level entry of the shared volume appears in it as a
    // RELATIVE link "../../sdcard/<name>". Java File/NIO and native code
    // (bionic_shim.cpp maps /sdcard/... to <root>/sdcard/...) then name the same
    // directory with no translation on either side, the same mechanism as the
    // real-Mac-folder links above. Android/ stays a real folder of the app's own
    // volume: Android/{data,obb,media}/<pkg> remain private to the app.
    //
    // A real top-level folder the app already has with content is MOVED into the
    // shared volume at launch (default since the owner's 2026-10-20 answer;
    // MACAPK_SHARED_SDCARD_MIGRATE=0 keeps it the app's own, where it hides the
    // shared entry of that name): moveNoClobber, never replacing, every move
    // journalled, then the link. An EMPTY folder (the default folders
    // ensureDefaultFolders made) holds no data, so it is replaced by the link.
    // A new top-level folder an app creates during a run is shared at once when
    // it goes through the one volume path (adoptCanonicalVolume), else at the
    // app's next launch.
    // MACAPK_SHARED_SDCARD=0 restores per-app volumes exactly (no links made).

    static final String SHARED_VOLUME_DIR = "sdcard";

    public static boolean sharedVolumeEnabled() {
        String e = System.getenv("MACAPK_SHARED_SDCARD");
        return e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** Default ON since the owner's answer (2026-10-20, "yes i think it should
     *  be shared like a phone"): an app's own top-level folders move into the
     *  shared volume at its launch, never replacing anything (moveNoClobber),
     *  every move journalled. MACAPK_SHARED_SDCARD_MIGRATE=0 keeps them the
     *  app's own. */
    static boolean sharedVolumeMigrate() {
        String e = System.getenv("MACAPK_SHARED_SDCARD_MIGRATE");
        return e == null || e.isEmpty() || e.charAt(0) != '0';
    }

    /** The shared volume for an app volume on the app-data layout
     *  (<...>/MacApk/<pkg>/sdcard resolved), else null. */
    static java.nio.file.Path sharedVolumeFor(File appVolume) {
        try {
            java.nio.file.Path real = appVolume.toPath().toRealPath();
            java.nio.file.Path appDir = real.getParent();
            java.nio.file.Path macapk = appDir != null ? appDir.getParent() : null;
            if (macapk == null || macapk.getFileName() == null
                    || !"MacApk".equals(macapk.getFileName().toString())) {
                return null;
            }
            if (!real.getFileName().toString().equals("sdcard")) return null;
            return macapk.resolve(SHARED_VOLUME_DIR);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Boot step (after mapUserFolders / ensureDefaultFolders): link the shared
     *  volume into this installed app's own volume. @return links made, or -1
     *  when nothing ran. Never throws. */
    public static synchronized int linkSharedVolume() {
        if (!sharedVolumeEnabled()) return -1;
        try {
            String[] id = SpecialAccessStore.selfIdentity();
            if (id == null) return -1;              // not an installed bundle
            return linkSharedVolume(appVolume(), id[1]);
        } catch (Throwable t) {
            NativeLog.w(TAG, "linkSharedVolume failed: " + t);
            return -1;
        }
    }

    /** The mechanism, for a given app volume (the offline gate drives it). */
    static int linkSharedVolume(File appVolume, String pkg) {
        if (!sharedVolumeEnabled()) return -1;
        java.nio.file.Path shared = sharedVolumeFor(appVolume);
        if (shared == null) return -1;              // not the app-data layout
        final java.nio.file.LinkOption NF = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        java.nio.file.Path mine;
        try {
            mine = appVolume.toPath().toRealPath();
        } catch (Throwable t) {
            return -1;
        }
        java.nio.file.Path lockFile = shared.getParent().resolve(".sdcard.lock");
        int linked = 0;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockFile,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lk = ch.lock()) {
            java.nio.file.Files.createDirectories(shared);
            // The shared volume's own first-time layout, under the same lock that
            // every app's launch takes: the real-Mac-folder links, then AOSP's
            // default folders once (marker outside the volume).
            mapUserFolders(shared.toFile(), pkg);
            java.nio.file.Path marker = shared.getParent().resolve(".sdcard-default-folders");
            if (!java.nio.file.Files.exists(marker)) {
                for (String n : defaultFolderNames()) {
                    if (n == null || n.isEmpty()) continue;
                    java.nio.file.Path f = shared.resolve(n);
                    if (!java.nio.file.Files.exists(f, NF)) java.nio.file.Files.createDirectories(f);
                }
                java.nio.file.Files.write(marker, new byte[] { '1' });
            }
            // Optional migration: this app's own top-level folders into the
            // shared volume, never replacing anything there.
            if (sharedVolumeMigrate()) {
                java.util.List<java.nio.file.Path> own = new java.util.ArrayList<>();
                try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                             java.nio.file.Files.newDirectoryStream(mine)) {
                    for (java.nio.file.Path p : ds) own.add(p);
                }
                for (java.nio.file.Path p : own) {
                    String n = p.getFileName().toString();
                    if (n.equals("Android") || java.nio.file.Files.isSymbolicLink(p)) continue;
                    if (!java.nio.file.Files.isDirectory(p, NF)) continue;
                    java.nio.file.Path there = shared.resolve(n);
                    if (!java.nio.file.Files.exists(there, NF)) {
                        java.nio.file.Files.move(p, there);   // one rename, same volume
                        journal(p, there);
                    } else if (java.nio.file.Files.isDirectory(there) && mergeInto(p, there, pkg)) {
                        removeEmptyTree(p);
                    }
                }
            }
            // Link each shared top-level entry into this app's volume.
            java.util.List<java.nio.file.Path> entries = new java.util.ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                         java.nio.file.Files.newDirectoryStream(shared)) {
                for (java.nio.file.Path p : ds) entries.add(p);
            }
            for (java.nio.file.Path e : entries) {
                String n = e.getFileName().toString();
                if (n.equals("Android")) continue;   // per-app, never shared
                java.nio.file.Path link = mine.resolve(n);
                java.nio.file.Path rel = java.nio.file.Paths.get("..", "..", SHARED_VOLUME_DIR, n);
                try {
                    if (java.nio.file.Files.isSymbolicLink(link)) {
                        // An app-volume real-folder link (Pictures -> ~/Pictures)
                        // names the same place as the shared one: replace it so
                        // every app reaches the volume one way.
                        java.nio.file.Path cur = java.nio.file.Files.readSymbolicLink(link);
                        if (cur.equals(rel)) continue;
                        if (isRecognizedFolderLink(link.toFile()) && java.nio.file.Files.isSymbolicLink(e)) {
                            java.nio.file.Files.delete(link);
                        } else {
                            continue;                // not ours to change
                        }
                    } else if (java.nio.file.Files.exists(link, NF)) {
                        if (!java.nio.file.Files.isDirectory(link, NF) || !removeEmptyTree(link)) {
                            continue;                // the app's own content: kept
                        }
                    }
                    java.nio.file.Files.createSymbolicLink(link, rel);
                    linked++;
                } catch (Throwable t) {
                    logQuiet("shared volume: linking " + n + " failed: " + t);
                }
            }
            logQuiet("shared volume " + shared + ": " + linked + " link(s) made in " + mine);
        } catch (Throwable t) {
            NativeLog.w(TAG, "linkSharedVolume: " + t);
            return -1;
        }
        return linked;
    }
}
