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
        return new File(SharedStorage.hostHome(), "Library/Application Support/Mac.apk/app_uids.properties");
    }

    /** The uid of `pkg` (allocating one on first sight). */
    public static synchronized int uidFor(String pkg, String sharedUserId) {
        // MacApkPackageManager's placeholder for "no package" is not a package.
        if (pkg == null || "com.macapk.unknown".equals(pkg)) return -1;
        try {
            File f = file();
            f.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
                 FileLock lock = raf.getChannel().lock()) {
                Properties p = read(raf);
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
                write(raf, p);
                return uid;
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Every package known at `uid`, or null. */
    public static String[] packagesFor(int uid) {
        try {
            File f = file();
            if (!f.isFile()) return null;
            Properties p = new Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { p.load(in); }
            List<String> out = new ArrayList<>();
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("member:") && Integer.toString(uid).equals(p.getProperty(k))) {
                    out.add(k.substring("member:".length()));
                }
            }
            if (out.isEmpty()) return null;
            java.util.Collections.sort(out);
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The sharedUserId a uid belongs to, or null. */
    public static String sharedNameFor(int uid) {
        try {
            Properties p = new Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(file())) { p.load(in); }
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("shared:") && Integer.toString(uid).equals(p.getProperty(k))) {
                    return k.substring("shared:".length());
                }
            }
        } catch (Throwable ignored) {}
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
        raf.setLength(0);
        raf.seek(0);
        raf.write(b);
    }
}
