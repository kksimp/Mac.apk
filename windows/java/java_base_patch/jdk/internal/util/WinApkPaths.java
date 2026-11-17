/*
 * Win.apk java.base overlay: Android's absolute paths for java.io and java.nio.
 *
 * An app sees its files by their Android names ("/data/user/0/<package>/files",
 * "/storage/emulated/0/Download"), as on a device: native code splits paths on
 * '/' and walks up to "/", which a Windows path ("C:\...") never reaches (Google
 * Maps' recursive mkdir recursed on "" until its thread's stack ran out, and
 * took the files directory for a relative path). A path that starts with a
 * single '/' is such a name: java.io.File and sun.nio.fs.WindowsPath keep it as
 * it is ('/' separators, no drive), and every call that reaches Windows takes
 * the host file it names instead, which the runtime works out with the guest
 * shim's own table (windows/src/bionic/paths.cpp, host_path): the app's data
 * directory, shared storage, then <android root>/<the rest>. On Windows a path
 * that starts with one '/' means the root of the current drive, which no
 * Android code means.
 *
 * Only in winapk-runtime, which sets winapk.android.root and binds host0
 * (windows/host/java_io_open.cpp); in any other JVM nothing changes.
 */
package jdk.internal.util;

public final class WinApkPaths {
    private WinApkPaths() {}

    // 0: not known yet, 1: Android paths on, -1: off.
    private static volatile int state;

    private static boolean enabled() {
        int s = state;
        if (s == 0) {
            // Too early in the JVM's start for properties: off, and asked again later.
            if (!jdk.internal.misc.VM.isBooted()) return false;
            s = System.getProperty("winapk.android.root") != null ? 1 : -1;
            state = s;
        }
        return s > 0;
    }

    /** Whether `p` is an Android absolute path: one leading '/' ("//host/share" is UNC,
     *  and "/C:/x", a file URL's path, is the drive path Windows also reads it as). */
    public static boolean isAndroid(String p) {
        int n = p.length();
        if (n == 0 || p.charAt(0) != '/') return false;
        if (n > 1 && p.charAt(1) == '/') return false;
        if (n > 2 && p.charAt(2) == ':' && Character.isLetter(p.charAt(1))) return false;
        return enabled();
    }

    /** The host file an Android path names; anything else as it is. */
    public static String hostPath(String p) {
        if (!isAndroid(p)) return p;
        try {
            String h = host0(p);
            return h != null ? h : p;
        } catch (UnsatisfiedLinkError unbound) {
            return p;
        }
    }

    /** An Android path in normal form: '/' separators, no repeated or trailing '/'. */
    public static String normalize(String p) {
        int n = p.length();
        StringBuilder sb = null;
        char prev = 0;
        for (int i = 0; i < n; i++) {
            char c = p.charAt(i);
            if (c == '\\') c = '/';
            boolean drop = c == '/' && (prev == '/' || i == n - 1) && i > 0;
            if (sb == null && (drop || c != p.charAt(i))) {
                sb = new StringBuilder(n);
                sb.append(p, 0, i);
            }
            if (sb != null && !drop) sb.append(c);
            if (!drop) prev = c;
        }
        if (sb == null) return p;
        int len = sb.length();
        if (len > 1 && sb.charAt(len - 1) == '/') sb.setLength(len - 1);
        return sb.toString();
    }

    /** `child` (a relative path, either separator) under the Android path `parent`. */
    public static String join(String parent, String child) {
        String c = child.replace('\\', '/');
        int start = 0;
        while (start < c.length() && c.charAt(start) == '/') start++;
        c = c.substring(start);
        if (c.isEmpty()) return parent;
        return normalize(parent.endsWith("/") ? parent + c : parent + "/" + c);
    }

    /** "." and ".." folded out of an Android path, by its text (as Android's canonicalize does without links). */
    public static String fold(String p) {
        String[] parts = p.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(parts.length);
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(s);
        }
        return "/" + String.join("/", out);
    }

    /**
     * The Android name of a file as its host directory lists it (or of a path that ends in one).
     * An Android name's characters that Windows forbids (&lt; &gt; : " | ? *, control characters,
     * a final dot or space) are on the host U+F000 + the character, the mapping WSL uses: host0
     * writes them so (windows/src/bionic/paths.cpp), and this turns them back for a listing.
     */
    public static String androidName(String s) {
        int n = s.length();
        StringBuilder sb = null;
        for (int i = 0; i < n; i++) {
            int c = s.charAt(i);
            if (c <= 0xF000 || c > 0xF07F) continue;
            char plain = (char) (c - 0xF000);
            boolean last = i == n - 1 || s.charAt(i + 1) == '/' || s.charAt(i + 1) == '\\';
            if (plain < 0x20 || "<>:\"|?*".indexOf(plain) >= 0 || (last && (plain == '.' || plain == ' '))) {
                if (sb == null) sb = new StringBuilder(s);
                sb.setCharAt(i, plain);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static native String host0(String p);
}
