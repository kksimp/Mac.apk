// android.provider.SettingsStore -- the real backing store behind
// Settings.System / Secure / Global. Package-private helper (not part of
// the public Android API; lives in android.provider so Settings can use
// it). Real Android keeps settings in a SQLite DB owned by the Settings
// provider and exposes them through ContentResolver; Mac.apk has no such
// provider, so we back the three namespaces with an in-process map that
// persists to a .properties file under the android-root data tree. This
// makes putInt/putString round-trip and survive across runs, which is
// what apps that store preferences via Settings.Secure/System expect.
//
// Persistence path mirrors how Boot.java resolves the android-root:
// -Dmacapk.android.root property -> MACAPK_ANDROID_ROOT env -> the dev
// scratch default. The settings file is shared across the whole
// install (system/secure/global are device-global in real Android, not
// per-app), under data/data/.macapk-settings/.
package android.provider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

final class SettingsStore {
    private SettingsStore() {}

    // Namespace prefixes keep the three tables in one file without key
    // collisions (real Android keeps them in separate DB tables).
    static final String NS_SYSTEM = "system/";
    static final String NS_SECURE = "secure/";
    static final String NS_GLOBAL = "global/";

    private static final Object LOCK = new Object();
    private static Properties sProps;
    private static File sFile;

    private static String resolveAndroidRoot() {
        String root = System.getProperty("macapk.android.root");
        if (root == null || root.isEmpty()) root = System.getenv("MACAPK_ANDROID_ROOT");
        if (root == null || root.isEmpty()) {
            // Last-ditch dev default; inert in every real run because the
            // runtime always sets -Dmacapk.android.root. Kept in step with
            // ResolveAndroidRootDir (apk_unpacker.cpp:1345). Was
            // "~/Documents/android" until 2026-08-12 -- that shared root is
            // DELETED and A13_NO_LEGACY_ROOT fails if anything recreates it.
            root = System.getProperty("user.home", ".")
                    + "/Library/Caches/Mac.apk/scratch/android";
        }
        if (root.equals("~") || root.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) root = home + root.substring(1);
        }
        return root;
    }

    private static void ensureLoaded() {
        if (sProps != null) return;
        synchronized (LOCK) {
            if (sProps != null) return;
            Properties p = new Properties();
            try {
                File dir = new File(resolveAndroidRoot(), "data/data/.macapk-settings");
                dir.mkdirs();
                File f = new File(dir, "settings.properties");
                if (f.exists()) {
                    try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
                }
                sFile = f;
            } catch (Throwable ignored) {
                // Persistence is best-effort; fall back to an in-memory map.
                sFile = null;
            }
            sProps = p;
            seedDefaults(p);
        }
    }

    // Seed the values real Android ships non-empty: the three animation
    // scales default to 1.0, and Secure.ANDROID_ID gets a stable random
    // 64-bit hex id on first run (persisted so it stays constant).
    private static void seedDefaults(Properties p) {
        if (!p.containsKey(NS_GLOBAL + "animator_duration_scale"))
            p.setProperty(NS_GLOBAL + "animator_duration_scale", "1.0");
        if (!p.containsKey(NS_GLOBAL + "transition_animation_scale"))
            p.setProperty(NS_GLOBAL + "transition_animation_scale", "1.0");
        if (!p.containsKey(NS_GLOBAL + "window_animation_scale"))
            p.setProperty(NS_GLOBAL + "window_animation_scale", "1.0");
        // A set-up device reports provisioning complete. The Play Store gates
        // app downloads on this ("IQ: Expedited install constraint provisioned
        // check failed." -> the install sits pending forever), and other apps
        // treat an unprovisioned device as mid-setup-wizard. Mac.apk is always a
        // fully set-up device, so report it as such. global/device_provisioned
        // and secure/user_setup_complete are the two flags the framework and GMS
        // read; both are 1 on a provisioned device.
        if (!p.containsKey(NS_GLOBAL + "device_provisioned"))
            p.setProperty(NS_GLOBAL + "device_provisioned", "1");
        if (!p.containsKey(NS_SECURE + "user_setup_complete"))
            p.setProperty(NS_SECURE + "user_setup_complete", "1");
        boolean newId = false;
        if (!p.containsKey(NS_SECURE + "android_id")) {
            long id = new java.security.SecureRandom().nextLong();
            p.setProperty(NS_SECURE + "android_id", String.format("%016x", id));
            newId = true;
        }
        if (newId) persist();
    }

    // ---- live host brightness -------------------------------------------
    //
    // Settings.System.screen_brightness is ALWAYS populated on a real device
    // (0-255), and apps call getInt() -- the THROWING overload -- for it. We
    // had no value, so parseIntOrThrow raised SettingNotFoundException.
    // MEASURED 2026-08-14: that killed PlayTube outright. PlayerScreen.kt:320
    // reads it in a Compose coroutine, the throw escaped as FATAL EXCEPTION on
    // main, and the video surface never drew -- the user saw a black screen
    // after pressing play, with nothing pointing at Settings.
    //
    // Read the real backlight rather than inventing a constant, using the same
    // idiom BatteryManager uses for pmset: shell out to a system binary, no
    // JNI, no private framework. /usr/sbin/ioreg is in the minimal PATH a
    // Dock-launched GUI process gets, which matters here -- see
    // ResolveApktoolBin in apk_unpacker.cpp for what happens when a tool is
    // NOT on that PATH.
    //
    //   ioreg -rc AppleARMBacklight
    //     "IODisplayParameters" = {... "brightness"={"min"=0,"max"=65536,"value"=32768} ...}
    //
    // Scaled min..max -> 0..255. Multiple AppleARMBacklight nodes exist (an
    // internal panel plus inactive ones); prefer a node whose
    // BrightnessMilliNits value is non-zero, i.e. a display that is actually
    // lit, and fall back to the first node parsed.
    //
    // Cached briefly: apps poll this to drive a slider, and a process spawn per
    // read would be absurd. A stale-by-2s brightness is indistinguishable from
    // a live one at human timescales.
    private static final long BRIGHTNESS_TTL_MS = 2000L;
    private static long sBrightnessAt = 0L;
    private static String sBrightnessCached = null;

    private static final java.util.regex.Pattern BRIGHTNESS_RE =
        java.util.regex.Pattern.compile(
            "\"brightness\"=\\{\"min\"=(-?\\d+),\"max\"=(-?\\d+),\"value\"=(-?\\d+)\\}");
    private static final java.util.regex.Pattern MILLINITS_RE =
        java.util.regex.Pattern.compile(
            "\"BrightnessMilliNits\"=\\{[^}]*\"value\"=(-?\\d+)");

    static String hostScreenBrightness() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (sBrightnessCached != null && now - sBrightnessAt < BRIGHTNESS_TTL_MS)
                return sBrightnessCached;
        }
        String v = readBacklight();
        synchronized (LOCK) { sBrightnessCached = v; sBrightnessAt = now; }
        return v;
    }

    private static String readBacklight() {
        try {
            java.lang.Process p = new ProcessBuilder("/usr/sbin/ioreg", "-rc",
                                                     "AppleARMBacklight")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(p.getInputStream()))) {
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln).append('\n');
            }
            p.waitFor();
            String out = sb.toString();

            java.util.regex.Matcher bm = BRIGHTNESS_RE.matcher(out);
            java.util.regex.Matcher nm = MILLINITS_RE.matcher(out);
            String first = null;
            while (bm.find()) {
                long lo = Long.parseLong(bm.group(1));
                long hi = Long.parseLong(bm.group(2));
                long val = Long.parseLong(bm.group(3));
                if (hi <= lo) continue;
                long scaled = Math.round((val - lo) * 255.0 / (hi - lo));
                if (scaled < 0) scaled = 0;
                if (scaled > 255) scaled = 255;
                String s = Long.toString(scaled);
                if (first == null) first = s;
                // Prefer a panel that is actually lit.
                if (nm.find() && Long.parseLong(nm.group(1)) > 0) return s;
            }
            if (first != null) return first;
        } catch (Throwable ignored) {
            // ioreg missing or blocked -- fall through to the constant below.
        }
        // A real device always has SOME value here, so never return null: the
        // throwing getInt() overload is what the crash came from.
        return "128";
    }

    /** Write the store ATOMICALLY: temp file -> fsync -> rename over the live one.
     *
     *  This had the same shape as the SharedPreferences bug fixed at v1.0.1110 --
     *  `new FileOutputStream(sFile)` truncates in place, no sync, every failure
     *  swallowed -- but with a WORSE blast radius, for three reasons:
     *
     *   1. The silent reset is AMPLIFIED. `Properties.load` does not throw on a
     *      truncated file, so ensureLoaded succeeds with a partial map;
     *      seedDefaults then sees `secure/android_id` missing and MINTS A FRESH
     *      RANDOM ONE and persists it. The app sees a different device.
     *   2. This file is DEVICE-GLOBAL, not per-app. One app's crash mid-write
     *      resets settings for every app in the install.
     *   3. `synchronized (LOCK)` guards one JVM. Two Mac.apk processes (a
     *      launcher run and an installed app stub) share this file, so the lock
     *      does not span the actual contenders.
     *
     *  Because of (3) the AOSP rename-aside-a-backup dance used for prefs is NOT
     *  the right answer here -- that is safe for a per-app file, and this one is
     *  shared. SettingsStore is Mac.apk wiring, not an AOSP-observable format, so
     *  the host-first ladder applies: `Files.move(..., ATOMIC_MOVE)` is rename(2),
     *  atomic on APFS. A concurrent reader in another process sees either the old
     *  file or the new one, never a truncated one, and there is no instant at
     *  which neither exists.
     *
     *  Returns whether the data actually reached disk, so putString can stop
     *  reporting an unconditional true. */
    private static boolean persist() {
        if (sFile == null || sProps == null) return false;
        java.io.File tmp = new java.io.File(sFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            sProps.store(out, "Mac.apk Settings store");
            out.flush();
            // Without the sync the RENAME can be durable while the CONTENT is
            // not, which is the corruption this whole dance exists to prevent.
            out.getFD().sync();
        } catch (Throwable t) {
            tmp.delete();
            com.macapk.loader.NativeLog.w("SettingsStore",
                "write failed, existing settings left intact: " + t);
            return false;
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), sFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable t) {
            // ATOMIC_MOVE is unsupported on some filesystems; a plain replace is
            // still better than a truncating in-place write, so fall back rather
            // than losing the write entirely.
            try {
                java.nio.file.Files.move(tmp.toPath(), sFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable t2) {
                tmp.delete();
                com.macapk.loader.NativeLog.w("SettingsStore",
                    "could not replace the settings file: " + t2);
                return false;
            }
        }
    }

    static String getString(String ns, String name) {
        if (name == null) return null;
        ensureLoaded();
        String v;
        synchronized (LOCK) { v = sProps.getProperty(ns + name); }
        if (v != null) return v;
        // Values a real device always has. An app that WROTE one gets its own
        // value back above; only an unset key falls through to the host.
        if (NS_SYSTEM.equals(ns)) {
            if ("screen_brightness".equals(name)) return hostScreenBrightness();
            // 0 = SCREEN_BRIGHTNESS_MODE_MANUAL. We do not drive the host's
            // auto-brightness, so reporting AUTOMATIC would be a claim we
            // cannot honour.
            if ("screen_brightness_mode".equals(name)) return "0";
        }
        // Settings.Global.BOOT_COUNT: every device has it (the system bumps it
        // once per boot; Google Play services' boot-count telemetry reads it
        // and logs "Could not find system setting for BOOT_COUNT" without it).
        // The true value here is this MAC's boot count, counted from the first
        // time Mac.apk saw it (hostBootCount).
        if (NS_GLOBAL.equals(ns) && "boot_count".equals(name)) return hostBootCount();
        // Settings.Global.ADB_ENABLED: 1 only on a GMS host the owner opted into
        // accepting debug-signed Google apps (the broker sets MACAPK_DEBUG_CERTS=1).
        // GMS's GoogleCertificates gate (m5.cg.b) reads this as the first of two
        // conditions before it will trust a debug-signed caller via its developer
        // override. This is the honest "USB debugging is on" answer for a dev
        // environment, scoped to that one process; an app that set its own value
        // got it back above. Not set otherwise, so every other app sees it unset
        // exactly as before.
        if (NS_GLOBAL.equals(ns) && "adb_enabled".equals(name)
                && "1".equals(System.getenv("MACAPK_DEBUG_CERTS"))) return "1";
        return null;
    }

    /** How many times this Mac has booted since Mac.apk first counted, as a
     *  string, or null if it cannot be determined. The boot is identified by
     *  launchd's (pid 1) start time, which is the kernel's boot; the count
     *  lives in a small file shared by every app and is bumped under a file
     *  lock when that boot time changes, so apps opening together count one
     *  boot once. */
    static String hostBootCount() {
        try {
            java.util.Optional<java.time.Instant> st = ProcessHandle.of(1)
                .flatMap(h -> h.info().startInstant());
            if (!st.isPresent()) return null;
            long boot = st.get().getEpochSecond();
            java.io.File f = new java.io.File(com.macapk.loader.SharedStorage.hostHome(),
                    "Library/Application Support/Mac.apk/boot_count");
            f.getParentFile().mkdirs();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                 java.nio.channels.FileLock lock = raf.getChannel().lock()) {
                String line = raf.length() > 0 ? raf.readLine() : null;
                long seenBoot = 0, count = 0;
                if (line != null) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 2) { seenBoot = Long.parseLong(p[0]); count = Long.parseLong(p[1]); }
                }
                // Allow a few seconds of jitter in the reported start time.
                if (Math.abs(boot - seenBoot) > 5) {
                    count++;
                    raf.setLength(0);
                    raf.write((boot + " " + count + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                return Long.toString(count);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The names stored in one namespace (what a whole-table query lists). */
    static java.util.List<String> names(String ns) {
        ensureLoaded();
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (LOCK) {
            for (String k : sProps.stringPropertyNames()) {
                if (k.startsWith(ns)) out.add(k.substring(ns.length()));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    static boolean putString(String ns, String name, String value) {
        if (name == null) return false;
        ensureLoaded();
        synchronized (LOCK) {
            if (value == null) sProps.remove(ns + name);
            else sProps.setProperty(ns + name, value);
            // Report whether it actually reached disk. Returning an
            // unconditional true was the same lie SharedPreferences.commit()
            // used to tell: an app that checks -- the only reason to check --
            // could not see a full disk or a read-only dir.
            return persist();
        }
    }
}
