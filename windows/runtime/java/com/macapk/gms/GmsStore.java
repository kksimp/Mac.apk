// com.macapk.gms.GmsStore -- the persisted state behind Mac.apk's GMS layer:
// the signed-in Google account(s), the master token, cached scoped auth tokens,
// and the checkin identity (android_id / security_token).
//
// WHAT LIVES HERE AND WHY IT IS ONE CLASS. Every GMS vertical (licensing, FCM,
// any getAuthToken consumer) needs the same three things -- an account, a token
// for a scope, and the device's checkin id. Splitting them across three stores
// would mean three file formats, three lock disciplines and three chances to
// write a token to a world-readable file. So: one store, one file, one lock.
//
// ---- WHERE THE SECRETS GO --------------------------------------------------
// Default: a 0600 file under ~/Library/Application Support/Mac.apk/gms/. That is
// the same protection Aurora Store's own token XML gets on a device, and the
// same protection this runtime already gives installed_apps.json's neighbours.
// The permissions are applied BEFORE any content is written (see save()), not
// after, so there is no window in which the token exists at umask default.
//
// The macOS Keychain is implemented too but is OPT-IN (MACAPK_GMS_KEYCHAIN=1),
// and the reason is specific rather than laziness: a keychain item's ACL is
// bound to the code signature of the process that created it, and
// runtime/scripts/build.sh RE-SIGNS macapk-runtime on every build. A rebuilt
// binary reading an item created by the previous build is a DIFFERENT identity
// to securityd, which answers with an interactive "allow?" panel. In a headless
// CLI run (--license-check) or a Dock launch that panel is either invisible or
// blocks forever, which turns a token read into a hang with no error. Once the
// signing identity is stable across builds (a release-signed bundle), flipping
// the default here is a one-line change and the right one.
//
// FORMAT: java.util.Properties. Flat, JDK-native, and its store()/load() pair
// escapes '=' ':' and whitespace in BOTH keys and values -- which matters,
// because a scope key is literally "oauth2:https://www.googleapis.com/auth/..."
// and a token is arbitrary base64url with '=' padding. Hand-rolling a format
// here would be re-solving an escaping problem the JDK already solved.
package com.macapk.gms;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class GmsStore {

    private static final String K_ANDROID_ID     = "checkin.androidId";
    private static final String K_SECURITY_TOKEN = "checkin.securityToken";
    private static final String K_CHECKIN_MS     = "checkin.lastMs";
    private static final String K_CHECKIN_VER    = "checkin.deviceDataVersionInfo";
    /** `gcm.registered.<pkg>` -> first-registration timestamp. See hasEverRegistered. */
    private static final String K_REG_PREFIX     = "gcm.registered.";
    private static final String P_ACCOUNT        = "account.";       // account.<email>.masterToken
    private static final String P_TOKEN          = "token.";         // token.<email>.<scope>.value / .expiry

    private static final Object LOCK = new Object();
    private static Properties sProps;      // null until first load

    private GmsStore() {}

    // ===================================================================
    // Location
    // ===================================================================

    /** The GMS state directory. Sibling of installed_apps.json on purpose: one
     *  place for "things the Mac.apk installation knows", so a user clearing
     *  Mac.apk's data clears the Google session too. */
    public static File dir() {
        String override = System.getenv("MACAPK_GMS_DIR");
        if (override != null && !override.isEmpty()) return new File(override);
        return new File(System.getProperty("user.home"),
                "Library/Application Support/Mac.apk/gms");
    }

    private static File file() { return new File(dir(), "gms_state.properties"); }

    // ===================================================================
    // Load / save
    // ===================================================================

    /** The file's (mtime, size) when sProps was read or last written by us. */
    private static long sStampTime = -1, sStampSize = -1;
    /** Whether sProps came from a complete read of an existing file. */
    private static boolean sReadClean;

    private static Properties props() {
        synchronized (LOCK) {
            File f = file();
            // Several processes share this file (every app, the system broker,
            // `--gms` from the launcher's Settings). A copy read once and kept
            // for the life of the process misses a sign-in or sign-out made
            // elsewhere, and its next save() writes the stale copy back over
            // it. So a cached copy is good only while the file is unchanged.
            if (sProps != null && f.lastModified() == sStampTime && f.length() == sStampSize) return sProps;
            Properties p = new Properties();
            boolean clean = false;
            if (f.isFile()) {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    p.load(in);
                    clean = true;
                } catch (Throwable t) {
                    // A corrupt store must not take the process down, and it must
                    // not silently look like "signed out" either -- that would send
                    // a caller to fabricate a fresh sign-in over a file that still
                    // holds a good token. Loud, then empty.
                    warn("could not read " + f + ": " + t);
                }
            }
            sProps = p;
            sReadClean = clean;
            sStampTime = f.lastModified();
            sStampSize = f.length();
            return p;
        }
    }

    /** True when the accounts this process sees come from a complete read of
     *  the store's file (not a missing or unreadable one): what a caller needs
     *  before it treats an account's absence as a sign-out. */
    public static boolean readCleanly() {
        synchronized (LOCK) {
            props();
            return sReadClean;
        }
    }

    private static void save() {
        synchronized (LOCK) {
            if (sProps == null) return;
            File f = file();
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    Files.createDirectories(parent.toPath());
                    chmod700(parent);
                }
                // Create the file EMPTY and lock it down BEFORE writing content.
                // Writing first and chmod-ing after leaves a window where a token
                // sits at the process umask, which on a default macOS account is
                // world-readable.
                // Written beside the file, then moved over it: another process
                // reading while we write must see the old file or the new one,
                // never a truncated one (an account missing from a half-written
                // file would read as a sign-out).
                File tmp = new File(f.getPath() + ".tmp");
                if (!tmp.exists()) {
                    Files.createFile(tmp.toPath());
                }
                chmod600(tmp);
                try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
                    sProps.store(out, "Mac.apk GMS state -- contains OAuth tokens. Mode 0600.");
                }
                chmod600(tmp);   // store() does not change perms; re-assert after truncate
                try {
                    Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // No atomic replace here (a reader holding the file without
                    // delete sharing): write in place, as before.
                    try (OutputStream out = Files.newOutputStream(f.toPath())) {
                        sProps.store(out, "Mac.apk GMS state -- contains OAuth tokens. Mode 0600.");
                    }
                    Files.deleteIfExists(tmp.toPath());
                    chmod600(f);
                }
                sStampTime = f.lastModified();
                sStampSize = f.length();
                sReadClean = true;
            } catch (Throwable t) {
                warn("could not write " + f + ": " + t);
            }
            syncAccountSentinel();
        }
    }

    /** The name of a zero-byte marker that exists iff at least one account is
     *  signed in. */
    private static final String SENTINEL = "accounts.present";

    /**
     * Keep the {@code accounts.present} sentinel in step with {@link #hasAccount}.
     *
     * WHY A SEPARATE EMPTY FILE EXISTS. The launcher wants to show a "Sign in
     * with Google" nudge until an account is added, and hide it once one is. The
     * only truthful source for "is an account signed in" is this store -- but
     * the launcher must NOT read the credentials file (it holds live tokens),
     * and asking the runtime over `--gms accounts` on every launcher start pays a
     * whole JVM boot just to decorate a glyph. So the store drops a marker the
     * launcher can `stat` in microseconds: its mere EXISTENCE is the answer, it
     * carries no content, and it lives in the same 0700 directory, so it reveals
     * nothing a reader of that directory could not already learn.
     *
     * ⚠ IT MUST NOT BE A FILE-EXISTENCE HEURISTIC ON gms_state.properties. That
     * file is created by CHECKIN, which happens with zero accounts, so "the state
     * file exists" is true for a device that has never signed in -- exactly the
     * case the nudge is for. The sentinel is gated on hasAccount(), not on the
     * store file existing, so a checkin-only device correctly still shows it.
     *
     * Called from {@link #save()}, so it is reconciled after EVERY mutation and
     * cannot drift: a sign-in creates it, a sign-out that empties the store
     * removes it, and a checkin (no account) leaves it absent.
     */
    static void syncAccountSentinel() {
        File marker = new File(dir(), SENTINEL);
        try {
            boolean want = hasAccount();
            boolean have = marker.exists();
            if (want && !have) {
                File parent = marker.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    Files.createDirectories(parent.toPath());
                    chmod700(parent);
                }
                Files.createFile(marker.toPath());
            } else if (!want && have) {
                Files.deleteIfExists(marker.toPath());
            }
        } catch (Throwable ignored) {
            // A missing nudge is a cosmetic loss, never a functional one -- the
            // account still works, and the panel's own probe reports the truth.
        }
    }

    /** The absolute path of the sentinel, for the launcher to check. */
    public static File accountSentinelFile() { return new File(dir(), SENTINEL); }

    private static void chmod600(File f) {
        try {
            Set<PosixFilePermission> perms =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(f.toPath(), perms);
        } catch (Throwable ignored) {}
    }

    private static void chmod700(File d) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(d.toPath(), perms);
        } catch (Throwable ignored) {}
    }

    // ===================================================================
    // Checkin identity
    // ===================================================================

    /** The device's checkin android_id, or 0 if this device has never checked
     *  in. 0 is a meaningful answer, not a failure: it is what a device reports
     *  before registration, and callers that need one must trigger a checkin
     *  rather than invent a value. */
    public static long androidId() {
        return parseLong(props().getProperty(K_ANDROID_ID), 0L);
    }

    public static long securityToken() {
        return parseLong(props().getProperty(K_SECURITY_TOKEN), 0L);
    }

    public static long lastCheckinMs() {
        return parseLong(props().getProperty(K_CHECKIN_MS), 0L);
    }

    public static String deviceDataVersionInfo() {
        return props().getProperty(K_CHECKIN_VER);
    }

    public static void setCheckin(long androidId, long securityToken, String versionInfo) {
        Properties p = props();
        synchronized (LOCK) {
            p.setProperty(K_ANDROID_ID, Long.toUnsignedString(androidId));
            p.setProperty(K_SECURITY_TOKEN, Long.toUnsignedString(securityToken));
            p.setProperty(K_CHECKIN_MS, Long.toString(System.currentTimeMillis()));
            if (versionInfo != null) p.setProperty(K_CHECKIN_VER, versionInfo);
        }
        save();
    }

    // ===================================================================
    // FCM registration record
    // ===================================================================

    /** "Has this package EVER obtained an FCM token from us?"
     *
     *  This exists so the MCS receive channel can start LAZILY without breaking
     *  correctness. An app registers once and then caches its token in its own
     *  storage; on every later launch it does NOT register again, but it still
     *  expects push to arrive. So "start the socket when someone registers" is
     *  only correct if we also remember who registered on a PREVIOUS run --
     *  otherwise a cached-token app silently never receives anything, which is
     *  the sort of bug that looks like a flaky network rather than a logic error.
     *
     *  Deliberately a package-keyed flag, not a stored token: we do not need the
     *  token (the app has it), and storing one would be a second copy to keep
     *  coherent with Google. */
    public static boolean hasEverRegistered(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Properties p = props();
        synchronized (LOCK) {
            return p.getProperty(K_REG_PREFIX + pkg) != null;
        }
    }

    public static void markRegistered(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Properties p = props();
        synchronized (LOCK) {
            if (p.getProperty(K_REG_PREFIX + pkg) != null) return;   // no needless rewrite
            p.setProperty(K_REG_PREFIX + pkg, Long.toString(System.currentTimeMillis()));
        }
        save();
    }

    // ===================================================================
    // Accounts
    // ===================================================================

    /** Every signed-in Google account name (the email), in insertion-stable
     *  order. Empty when nobody has signed in -- which is exactly what a fresh
     *  device reports, so callers already handle it. */
    public static List<String> accountNames() {
        List<String> out = new ArrayList<>();
        Properties p = props();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(P_ACCOUNT) && key.endsWith(".masterToken")) {
                String name = key.substring(P_ACCOUNT.length(),
                        key.length() - ".masterToken".length());
                if (!name.isEmpty()) out.add(name);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static boolean hasAccount() { return !accountNames().isEmpty(); }

    /** The single account most callers want. AOSP's AccountManager has no
     *  concept of a "primary" account, so this is only a convenience for our own
     *  CLI and for services that must pick one; it deliberately returns the
     *  FIRST in sorted order rather than guessing at a preference. */
    public static String primaryAccount() {
        List<String> a = accountNames();
        return a.isEmpty() ? null : a.get(0);
    }

    public static String masterToken(String account) {
        if (account == null) return null;
        return keychainAware(P_ACCOUNT + account + ".masterToken");
    }

    public static void putAccount(String account, String masterToken) {
        if (account == null || masterToken == null) return;
        putKeychainAware(P_ACCOUNT + account + ".masterToken", masterToken);
        save();
    }

    /** A non-secret per-account attribute (the `services=` list Google returns
     *  at sign-in, the display name, ...). Deliberately NOT keychain-routed:
     *  these are not credentials, and putting them behind an opt-in flag would
     *  make an account look half-present when the flag is off. */
    public static String accountData(String account, String key) {
        if (account == null || key == null) return null;
        return props().getProperty(P_ACCOUNT + account + ".data." + key);
    }

    public static void putAccountData(String account, String key, String value) {
        if (account == null || key == null) return;
        synchronized (LOCK) {
            if (value == null) props().remove(P_ACCOUNT + account + ".data." + key);
            else               props().setProperty(P_ACCOUNT + account + ".data." + key, value);
        }
        save();
    }

    public static void removeAccount(String account) {
        if (account == null) return;
        Properties p = props();
        synchronized (LOCK) {
            List<String> doomed = new ArrayList<>();
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith(P_ACCOUNT + account + ".")
                 || key.startsWith(P_TOKEN + account + ".")) {
                    doomed.add(key);
                }
            }
            for (String key : doomed) {
                p.remove(key);
                keychainDelete(key);
            }
        }
        save();
        AccountPhoto.delete(account);
    }

    // ===================================================================
    // Scoped auth tokens
    // ===================================================================

    /** A cached scoped token, or null if absent OR expired.
     *
     *  Expiry is enforced HERE rather than at the call site because every caller
     *  would otherwise have to remember to, and the failure mode of forgetting
     *  is the worst kind: a stale token produces an HTTP 401 from Google that
     *  reads to the caller like "not licensed" rather than "re-authenticate".
     *
     *  The 5-minute skew is the usual clock-drift guard: a token that expires in
     *  three minutes is treated as already gone, because the request it would be
     *  used for may not land inside that window. */
    public static String authToken(String account, String scope) {
        if (account == null || scope == null) return null;
        String base = P_TOKEN + account + "." + scope;
        String value = keychainAware(base + ".value");
        if (value == null || value.isEmpty()) return null;
        long expiry = parseLong(props().getProperty(base + ".expiry"), 0L);
        if (expiry > 0 && System.currentTimeMillis() > expiry - 300_000L) {
            return null;    // expired or about to be
        }
        return value;
    }

    /** @param expirySeconds seconds-from-now, as Google's /auth reports it; <= 0
     *                       means the server did not say, and we cache without an
     *                       expiry rather than inventing one. */
    public static void putAuthToken(String account, String scope, String token, long expirySeconds) {
        if (account == null || scope == null || token == null) return;
        String base = P_TOKEN + account + "." + scope;
        putKeychainAware(base + ".value", token);
        synchronized (LOCK) {
            if (expirySeconds > 0) {
                props().setProperty(base + ".expiry",
                        Long.toString(System.currentTimeMillis() + expirySeconds * 1000L));
            } else {
                props().remove(base + ".expiry");
            }
        }
        save();
    }

    /** Every scope we hold a token for on this account. Needed because AOSP's
     *  invalidateAuthToken identifies a token by its VALUE, not by its scope, so
     *  the caller has to search. */
    public static java.util.List<String> scopesFor(String account) {
        java.util.List<String> out = new ArrayList<>();
        if (account == null) return out;
        String prefix = P_TOKEN + account + ".";
        for (String key : props().stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(".value")) {
                out.add(key.substring(prefix.length(), key.length() - ".value".length()));
            }
        }
        return out;
    }

    /** The cached token WITHOUT the expiry check. Only for identifying a token
     *  by value during invalidation -- an expired token still has to be found
     *  and removed, and {@link #authToken} would hide it. */
    public static String authTokenRaw(String account, String scope) {
        if (account == null || scope == null) return null;
        return keychainAware(P_TOKEN + account + "." + scope + ".value");
    }

    /** AOSP AccountManager.invalidateAuthToken semantics: drop the cached copy so
     *  the next request re-fetches. Does NOT tell Google anything -- neither does
     *  a device. */
    public static void invalidateAuthToken(String account, String scope) {
        if (account == null || scope == null) return;
        String base = P_TOKEN + account + "." + scope;
        synchronized (LOCK) {
            props().remove(base + ".value");
            props().remove(base + ".expiry");
        }
        keychainDelete(base + ".value");
        save();
    }

    /** Drop every cached scoped token for an account, keeping the master token.
     *  Used when Google rejects a token for a reason that invalidates the whole
     *  cache (a password change, a revoked app grant). */
    public static void invalidateAllTokens(String account) {
        if (account == null) return;
        Properties p = props();
        synchronized (LOCK) {
            List<String> doomed = new ArrayList<>();
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith(P_TOKEN + account + ".")) doomed.add(key);
            }
            for (String key : doomed) { p.remove(key); keychainDelete(key); }
        }
        save();
    }

    // ===================================================================
    // Keychain (opt-in; see the file header for why it is not the default)
    // ===================================================================

    private static boolean keychainEnabled() {
        String v = System.getenv("MACAPK_GMS_KEYCHAIN");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static final String KEYCHAIN_SERVICE = "Mac.apk GMS";

    /** Read a secret: keychain when enabled, else the properties file. When the
     *  keychain is enabled but has no item, we fall back to the file -- that is
     *  what makes turning the flag on non-destructive for an existing session. */
    private static String keychainAware(String key) {
        if (keychainEnabled()) {
            String v = keychainRead(key);
            if (v != null) return v;
        }
        return props().getProperty(key);
    }

    private static void putKeychainAware(String key, String value) {
        if (keychainEnabled() && keychainWrite(key, value)) {
            // Written to the keychain -- make sure no plaintext copy lingers in
            // the file from a previous run with the flag off.
            synchronized (LOCK) { props().remove(key); }
            return;
        }
        synchronized (LOCK) { props().setProperty(key, value); }
    }

    private static String keychainRead(String account) {
        try {
            java.lang.Process p = new ProcessBuilder("/usr/bin/security",
                    "find-generic-password", "-a", account, "-s", KEYCHAIN_SERVICE, "-w")
                    .redirectErrorStream(false).start();
            String out = readAll(p.getInputStream());
            p.getErrorStream().close();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            out = out.trim();
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean keychainWrite(String account, String value) {
        try {
            // -U updates in place if the item already exists, which is the
            // difference between refreshing a token and accumulating a duplicate
            // keychain item per refresh.
            java.lang.Process p = new ProcessBuilder("/usr/bin/security",
                    "add-generic-password", "-a", account, "-s", KEYCHAIN_SERVICE,
                    "-w", value, "-U")
                    .redirectErrorStream(true).start();
            readAll(p.getInputStream());
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void keychainDelete(String account) {
        if (!keychainEnabled()) return;
        try {
            java.lang.Process p = new ProcessBuilder("/usr/bin/security",
                    "delete-generic-password", "-a", account, "-s", KEYCHAIN_SERVICE)
                    .redirectErrorStream(true).start();
            readAll(p.getInputStream());
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    // ===================================================================

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseUnsignedLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Forget everything cached in memory so the next read re-reads the file.
     *  The CLI needs this because it mutates the store in one JVM and a second
     *  process must see it; a long-lived app process never calls it. */
    public static void reload() {
        synchronized (LOCK) { sProps = null; sStampTime = -1; sStampSize = -1; }
    }

    private static void warn(String msg) {
        try { com.macapk.loader.NativeLog.w("Mac.apk.GMS", "GmsStore: " + msg); }
        catch (Throwable t) { System.err.println("[GmsStore] " + msg); }
    }
}
