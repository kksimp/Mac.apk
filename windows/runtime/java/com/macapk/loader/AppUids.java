// com.macapk.loader.AppUids -- a distinct app uid per installed package, as
// PackageManagerService assigns them at install (FIRST_APPLICATION_UID up, in
// install order; packages that declare the same android:sharedUserId share one).
//
// WHY. With every app in its own process, Binder.getCallingUid() is how a
// service tells callers apart, and Google Play services identifies a caller by
// getPackagesForUid(callingUid) and then that package's signature. Every
// Mac.apk app used to answer the same uid (10000), so across processes all
// callers looked alike and GMS's lookups resolved to nonsense
// ("com.macapk.unknown"). The allocation lives in one machine-wide file,
// written under a file lock, so every process and the broker agree on it.
//
// Only with the system broker enabled (Settings > Use Google GMS /
// MACAPK_BROKER=1): otherwise every answer stays the single uid 10000, exactly
// as before.
package com.macapk.loader;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class AppUids {
    private AppUids() {}

    public static final int FIRST = 10001;   // 10000 stays the broker-off uid

    private static volatile String sSelfPkg, sSelfShared;
    private static volatile int sSelfUid = -1;

    public static boolean enabled() { return SystemClient.enabled(); }

    /** Boot: this process's package (as soon as the manifest is parsed). */
    static void setSelf(String pkg, String sharedUserId) {
        sSelfPkg = pkg; sSelfShared = sharedUserId; sSelfUid = -1;
    }

    /** Process.myUid() when enabled; -1 when not known / not enabled. */
    public static int selfUid() {
        if (!enabled() || sSelfPkg == null) return -1;
        int u = sSelfUid;
        if (u < 0) { u = uidFor(sSelfPkg, sSelfShared); sSelfUid = u; }
        return u;
    }

    private static File file() {
        // Win.apk: in the data folder, beside installed_apps.json.
        if (HostAppSupport.windowsHost()) return HostAppSupport.file("app_uids.properties");
        return new File(SharedStorage.hostHome(), "Library/Application Support/Mac.apk/app_uids.properties");
    }

    /** (package, sharedUserId) -> the uid this process was last answered. A
     *  package's uid is fixed once assigned, so the file is asked once: the
     *  package manager asks on every ApplicationInfo it builds, and each
     *  question used to take the file's lock and write the file again. */
    private static final java.util.Map<String, Integer> sAnswered =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** The table as this process last read or wrote it, and the file's size and
     *  time then. Only under the class lock. */
    private static Properties sTable;
    private static long sTableLength = -1, sTableModified = -1;

    /** The uid of `pkg` (allocating one on first sight). */
    public static int uidFor(String pkg, String sharedUserId) {
        // MacApkPackageManager's placeholder for "no package" is not a package.
        if (pkg == null || "com.macapk.unknown".equals(pkg)) return -1;
        String asked = pkg + '\n' + (sharedUserId != null ? sharedUserId : "");
        Integer answered = sAnswered.get(asked);
        if (answered != null) return answered;
        synchronized (AppUids.class) {
            try {
                File f = file();
                f.getParentFile().mkdirs();
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
                     FileLock lock = raf.getChannel().lock()) {
                    Properties p = read(raf);
                    Properties was = (Properties) p.clone();
                    int uid = assign(p, pkg, sharedUserId);
                    // Written only when it says something new: every write is
                    // a moment at which the other processes' readers wait.
                    if (!p.equals(was)) {
                        write(raf, p);
                        // The package may have moved to its shared uid.
                        sAnswered.keySet().removeIf(k -> k.startsWith(pkg + '\n'));
                    }
                    remember(p, f);
                    sAnswered.put(asked, uid);
                    return uid;
                }
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    /** The uid of `pkg` in `p`, assigning one there when it has none. */
    private static int assign(Properties p, String pkg, String sharedUserId) {
        // A package's uid is fixed once assigned, as PMS keeps it for the
        // life of the install. A caller that could not read the package's
        // manifest passes no sharedUserId; that must not move a member of
        // a shared uid to a uid of its own (the GMS run saw the Play Store
        // listed at com.google.uid.shared's uid AND at a second one), so
        // an existing assignment answers it.
        String known = p.getProperty("member:" + pkg);
        if (sharedUserId == null && known != null) return Integer.parseInt(known);
        if (sharedUserId == null) sharedUserId = Manifest.impliedSharedUserId(pkg);
        String key = sharedUserId != null ? "shared:" + sharedUserId : "pkg:" + pkg;
        String v = p.getProperty(key);
        int uid;
        if (v != null) {
            uid = Integer.parseInt(v);
        } else {
            uid = FIRST;
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("pkg:") || k.startsWith("shared:")) {
                    uid = Math.max(uid, Integer.parseInt(p.getProperty(k)) + 1);
                }
            }
            p.setProperty(key, Integer.toString(uid));
        }
        // Every package is listed with its uid, shared or not, so
        // packagesFor() can answer.
        p.setProperty("member:" + pkg, Integer.toString(uid));
        return uid;
    }

    private static void remember(Properties p, File f) {
        sTable = p;
        sTableLength = f.length();
        sTableModified = f.lastModified();
    }

    /** The table, for the questions that only read it. Read under the file's
     *  lock (shared), as uidFor writes under it: on Windows a file lock is
     *  mandatory, so a read that did not wait for a writer in ANY process
     *  failed, and everywhere it could meet the file half written -- and the
     *  answer was then "no package has this uid", which Google Play services
     *  takes for a caller that lies about its package ("Unknown calling package
     *  name", "GoogleCertificatesRslt: no pkgs"). The file is read again only
     *  when it has changed; a read that fails keeps the last table, since an
     *  assignment never changes. Null when there is none. */
    private static synchronized Properties table() {
        File f = file();
        if (sTable != null && f.length() == sTableLength && f.lastModified() == sTableModified) return sTable;
        if (!f.isFile()) return sTable;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileLock lock = raf.getChannel().lock(0L, Long.MAX_VALUE, true)) {
            remember(read(raf), f);
        } catch (Throwable t) {
            // The last table stands.
        }
        return sTable;
    }

    /** Every package known at `uid`, or null. */
    public static String[] packagesFor(int uid) {
        Properties p = table();
        if (p == null) return null;
        String want = Integer.toString(uid);
        List<String> out = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("member:") && want.equals(p.getProperty(k))) {
                out.add(k.substring("member:".length()));
            }
        }
        if (out.isEmpty()) return null;
        java.util.Collections.sort(out);
        return out.toArray(new String[0]);
    }

    /** The sharedUserId a uid belongs to, or null. */
    public static String sharedNameFor(int uid) {
        Properties p = table();
        if (p == null) return null;
        String want = Integer.toString(uid);
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("shared:") && want.equals(p.getProperty(k))) {
                return k.substring("shared:".length());
            }
        }
        return null;
    }

    private static Properties read(RandomAccessFile raf) throws java.io.IOException {
        Properties p = new Properties();
        byte[] b = new byte[(int) raf.length()];
        raf.seek(0);
        raf.readFully(b);
        p.load(new java.io.StringReader(new String(b, StandardCharsets.ISO_8859_1)));
        return p;
    }

    private static void write(RandomAccessFile raf, Properties p) throws java.io.IOException {
        java.io.StringWriter w = new java.io.StringWriter();
        p.store(w, "Mac.apk app uids (com.macapk.loader.AppUids)");
        byte[] b = w.toString().getBytes(StandardCharsets.ISO_8859_1);
        // Over the old text, then cut to length: the file is never empty, so a
        // writer that dies here cannot lose every assignment.
        raf.seek(0);
        raf.write(b);
        raf.setLength(b.length);
    }
}
