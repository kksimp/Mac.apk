// com.macapk.loader.WinProcesses -- what Windows itself says about another
// process (windows/host/binder_win.cpp). The system broker uses it to learn
// which app a process is from how that process was started, instead of from
// what the process says about itself.
//
// Only a Windows host binds these natives; elsewhere every answer is "unknown"
// and the callers keep upstream's behaviour.
package com.macapk.loader;

public final class WinProcesses {
    private WinProcesses() {}

    /** The command line Windows recorded for `pid`, when it is this user's
     *  Win.apk runtime; null otherwise. */
    static native String commandLine(int pid);
    /** Whether `pid` is alive and is this user's Win.apk runtime. */
    static native boolean isRuntime(int pid);
    /** When `pid` started (FILETIME ticks): with the pid, one process for good. */
    static native long startTime(int pid);
    /** The pipe-name prefix this user's runtimes share. */
    static native String pipePrefix();

    public static boolean available() {
        if (!HostAppSupport.windowsHost()) return false;
        try {
            return pipePrefix() != null;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** The value of `--apk` on `pid`'s command line, or null. Quoted values are
     *  split as CommandLineToArgvW splits them (the launcher and the broker quote
     *  every argument). */
    public static String apkArgument(int pid) {
        String line;
        try {
            line = commandLine(pid);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
        if (line == null) return null;
        java.util.List<String> args = splitCommandLine(line);
        for (int i = 0; i + 1 < args.size(); i++) {
            if ("--apk".equals(args.get(i))) return args.get(i + 1);
        }
        return null;
    }

    /** CommandLineToArgvW's rules: whitespace separates, "..." groups, and
     *  2n backslashes before a quote are n backslashes (2n+1: n and a literal
     *  quote). */
    static java.util.List<String> splitCommandLine(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                int n = 0;
                while (i < s.length() && s.charAt(i) == '\\') { n++; i++; }
                if (i < s.length() && s.charAt(i) == '"') {
                    for (int k = 0; k < n / 2; k++) cur.append('\\');
                    if (n % 2 == 1) { cur.append('"'); } else { inQuotes = !inQuotes; }
                } else {
                    for (int k = 0; k < n; k++) cur.append('\\');
                    i--;
                }
                any = true;
            } else if (c == '"') {
                if (inQuotes && i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQuotes = !inQuotes;
                any = true;
            } else if (!inQuotes && (c == ' ' || c == '\t')) {
                if (any) { out.add(cur.toString()); cur.setLength(0); any = false; }
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }
}
