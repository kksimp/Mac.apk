package com.macapk.loader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * The installer of record: which package committed the PackageInstaller session
 * that installed an app (AOSP InstallSource.installerPackageName). A package
 * installed any other way (dropped on Mac.apk, copied in) has none, which is what
 * a device reports for an adb install.
 *
 * It is recorded only when an app really installs another through
 * PackageInstaller (PackageInstallerBackend, success path) and forgotten when the
 * package is uninstalled. It is shared between processes through a small
 * properties file beside installed_apps.json, because the installing process
 * (Play's background process) and the process that later asks (the installed app,
 * or Play itself) are different runtimes.
 *
 * Why it exists: Google Play calls PackageManager.setApplicationCategoryHint
 * right after a successful install, and AOSP only allows that for the installer
 * of record. With no record the SecurityException made Play report the install
 * as failed (INSTALL_ERROR 6200) although the app was installed and launchable.
 * This is the real relationship, not a fabricated one: getInstallerPackageName
 * stays null for every package nobody installed through PackageInstaller.
 */
public final class InstallerOfRecord {
    private InstallerOfRecord() {}

    private static File file() {
        String o = System.getenv("MACAPK_INSTALLER_RECORD_FILE");
        if (o != null && !o.isEmpty()) return new File(o);
        return new File(System.getProperty("user.home"),
                "Library/Application Support/Mac.apk/installer-of-record.properties");
    }

    private static Properties load() {
        Properties p = new Properties();
        File f = file();
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            catch (Throwable ignored) {}
        }
        return p;
    }

    /** The package that installed `pkg` through PackageInstaller, or null. */
    public static String get(String pkg) {
        if (pkg == null) return null;
        String v = load().getProperty(pkg);
        if (v == null || v.isEmpty()) v = backfillFromPlay(pkg);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static final String PLAY = "com.android.vending";
    private static final java.util.Set<String> backfillTried =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Installs made before the record existed (v1.0.2335) have no entry although
     * Google Play installed them. AOSP keeps the install source for the life of
     * the package, so a store-installed app must not lose it to a Mac.apk update.
     * The evidence is Play's OWN download ledger (localappstate.db: a row with a
     * non-zero first_download_ms is a package Play downloaded for the account),
     * accepted only when the package's registry install time lands within a few
     * hours after that download, so a later sideload of the same package, or an
     * unrelated row, is not attributed to Play. A package with no such evidence
     * stays null, exactly as before; once found the answer is recorded.
     */
    private static String backfillFromPlay(String pkg) {
        if (PLAY.equals(pkg) || !backfillTried.add(pkg)) return null;
        java.io.File tmp = null;
        try {
            long installedAt = MacApkPackageManager.InstalledRegistry.enabled()
                    ? rowInstalledAt(pkg) : 0L;
            if (installedAt <= 0L) return null;
            java.io.File db = playLedger();
            if (db == null) return null;
            // Copy db + WAL aside: Play may be running, and a read-only open of
            // a WAL database needs the -shm that only a live writer keeps.
            tmp = java.nio.file.Files.createTempDirectory("ledger").toFile();
            java.io.File copy = new java.io.File(tmp, "localappstate.db");
            java.nio.file.Files.copy(db.toPath(), copy.toPath());
            java.io.File wal = new java.io.File(db.getPath() + "-wal");
            if (wal.isFile()) {
                java.nio.file.Files.copy(wal.toPath(),
                        new java.io.File(tmp, "localappstate.db-wal").toPath());
            }
            long dl = 0L;
            android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase
                    .openDatabase(copy.getPath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            try (android.database.Cursor c = d.rawQuery(
                    "SELECT first_download_ms FROM appstate WHERE package_name = ?",
                    new String[] { pkg })) {
                if (c.moveToFirst()) dl = c.getLong(0);
            } finally { d.close(); }
            if (dl <= 0L || installedAt < dl - 300_000L || installedAt > dl + 6L * 3_600_000L) {
                return null;
            }
            NativeLog.i("InstallerOfRecord", "backfilled " + pkg + " <- " + PLAY
                    + " (Play download ledger, downloaded " + dl + ", installed " + installedAt + ")");
            set(pkg, PLAY);
            return PLAY;
        } catch (Throwable t) {
            NativeLog.w("InstallerOfRecord", "Play ledger backfill of " + pkg + " skipped: " + t);
            return null;
        } finally {
            if (tmp != null) {
                java.io.File[] fs = tmp.listFiles();
                if (fs != null) for (java.io.File f : fs) f.delete();
                tmp.delete();
            }
        }
    }

    private static long rowInstalledAt(String pkg) {
        MacApkPackageManager.InstalledRegistry.Row r = MacApkPackageManager.InstalledRegistry.lookup(pkg);
        return r == null ? 0L : r.installedAtMs;
    }

    /** Play's localappstate.db under the app-data root (the data dir carries
     *  Play's version, so it is found by listing). */
    private static java.io.File playLedger() {
        String root = System.getenv("MACAPK_APPDATA_ROOT");
        java.io.File base = (root != null && !root.isEmpty()) ? new java.io.File(root)
                : new java.io.File(System.getProperty("user.home"), "Library/Application Support/MacApk");
        java.io.File data = new java.io.File(base, PLAY + "/data/data");
        java.io.File[] dirs = data.listFiles();
        if (dirs == null) return null;
        java.io.File best = null;
        for (java.io.File d : dirs) {
            if (!d.getName().startsWith(PLAY)) continue;
            java.io.File f = new java.io.File(d, "databases/localappstate.db");
            if (f.isFile() && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    /** Record `installer` for `pkg`; a null/empty installer clears it. */
    public static synchronized void set(String pkg, String installer) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            File f = file();
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            // Re-read under the lock-free file replace: last writer wins per
            // key, and the rename keeps a reader from ever seeing a torn file.
            Properties p = load();
            if (installer == null || installer.isEmpty()) {
                if (p.remove(pkg) == null) return;
            } else {
                if (installer.equals(p.getProperty(pkg))) return;
                p.setProperty(pkg, installer);
            }
            File tmp = new File(dir, f.getName() + ".tmp-" + ProcessHandle.current().pid());
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                p.store(out, "package=installer of record (PackageInstaller)");
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable t) {
            NativeLog.e("InstallerOfRecord", "could not record installer of " + pkg + ": " + t);
        }
    }
}
