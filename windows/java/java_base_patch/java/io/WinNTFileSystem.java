/*
 * Win.apk java.base overlay (--patch-module java.base, built by
 * windows/scripts/build_java.sh next to runtime/java_base_patch). This REPLACES
 * the JDK's java.io.WinNTFileSystem (OpenJDK 21u, GPLv2 with the Classpath
 * exception, licence header below) with two changes. The first, in
 * resolve(parent, child): a child that is itself an absolute Windows path with
 * a drive letter nests under the parent instead of producing a path with a
 * colon in the middle.
 *
 * WHY: on Android, java.io.File(parent, child) with an absolute child joins
 * them ("/data/x/cache" + "/data/x/no_backup/db" = "/data/x/cache/data/x/
 * no_backup/db"), a legal path that mkdirs() can create, and code relies on
 * it: androidx.sqlite's ProcessLock makes its lock file as
 * File(context.cacheDir, "$name.lck") where name is the ABSOLUTE database path
 * for every Room/WorkManager database that uses the no-backup directory, then
 * lockFile.parentFile.mkdirs() and FileOutputStream(lockFile). The JDK's
 * resolve() keeps the child's "D:" ("...\cache\D:\a\...\db.lck"), a name
 * Windows refuses, so mkdirs() failed and the open threw FileNotFoundException:
 * WorkManager's ForceStopRunnable and every Room database with a no-backup
 * path (16 of 38 apps in the compatibility sweep, 2026-10-31). Here the drive
 * letter becomes a directory, "D:\a\b" -> "D\a\b", and the child nests as on
 * Android. The JDK itself never resolves a drive-absolute child against a
 * parent: File(URI), listFiles and the rest build children without drives.
 *
 * The second, in rename(f1, f2): a file renamed onto an existing file replaces
 * it, as rename(2) does on Android. The JDK's rename0 is _wrename, which
 * refuses an existing target, so the write-a-temporary-file-then-rename-it-over
 * pattern failed on every save after the first: the framework's launcher icon
 * was rendered again on every start and its "already rendered" stamp never
 * written. Only a file over a file; a directory target still fails, as
 * rename(2) fails onto a non-empty one. In winapk-runtime the replacing rename
 * is a POSIX-semantics one the host binds (winapkReplace), which also replaces
 * a target another process has open, as rename(2) does. A rename that still
 * fails is logged with Windows' reason.
 *
 * The third: Android paths (jdk.internal.util.WinApkPaths). A path that starts
 * with one '/' keeps that form ('/' separators, prefix length 1, absolute,
 * canonicalized by its text), a child joins it with '/', and the natives get
 * the host file it names.
 */
/*
 * Copyright (c) 2001, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.io;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Locale;
import java.util.Properties;
import jdk.internal.misc.Blocker;
import jdk.internal.util.WinApkPaths;
import sun.security.action.GetPropertyAction;

/**
 * Unicode-aware FileSystem for Windows NT/2000.
 *
 * @author Konstantin Kladko
 * @since 1.4
 */
final class WinNTFileSystem extends FileSystem {

    private final char slash;
    private final char altSlash;
    private final char semicolon;
    private final String userDir;

    // Whether to enable alternative data streams (ADS) by suppressing
    // checking the path for invalid characters, in particular ":".
    // By default, ADS support is enabled and will be disabled if and
    // only if the property is set, ignoring case, to the string "false".
    private static final boolean ENABLE_ADS;
    static {
        String enableADS = GetPropertyAction.privilegedGetProperty("jdk.io.File.enableADS");
        if (enableADS != null) {
            ENABLE_ADS = !enableADS.equalsIgnoreCase(Boolean.FALSE.toString());
        } else {
            ENABLE_ADS = true;
        }
    }

    WinNTFileSystem() {
        Properties props = GetPropertyAction.privilegedGetProperties();
        slash = props.getProperty("file.separator").charAt(0);
        semicolon = props.getProperty("path.separator").charAt(0);
        altSlash = (this.slash == '\\') ? '/' : '\\';
        userDir = normalize(props.getProperty("user.dir"));
    }

    private boolean isSlash(char c) {
        return (c == '\\') || (c == '/');
    }

    private boolean isLetter(char c) {
        return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'));
    }

    private String slashify(String p) {
        if (!p.isEmpty() && p.charAt(0) != slash) return slash + p;
        else return p;
    }

    /* -- Normalization and construction -- */

    @Override
    public char getSeparator() {
        return slash;
    }

    @Override
    public char getPathSeparator() {
        return semicolon;
    }

    /* Check that the given pathname is normal.  If not, invoke the real
       normalizer on the part of the pathname that requires normalization.
       This way we iterate through the whole pathname string only once. */
    @Override
    public String normalize(String path) {
        // Win.apk: an Android path keeps its '/' form (the third change).
        if (WinApkPaths.isAndroid(path)) return WinApkPaths.normalize(path);
        int n = path.length();
        char slash = this.slash;
        char altSlash = this.altSlash;
        char prev = 0;
        for (int i = 0; i < n; i++) {
            char c = path.charAt(i);
            if (c == altSlash)
                return normalize(path, n, (prev == slash) ? i - 1 : i);
            if ((c == slash) && (prev == slash) && (i > 1))
                return normalize(path, n, i - 1);
            if ((c == ':') && (i > 1))
                return normalize(path, n, 0);
            prev = c;
        }
        if (prev == slash) return normalize(path, n, n - 1);
        return path;
    }

    /* Normalize the given pathname, whose length is len, starting at the given
       offset; everything before this offset is already normal. */
    private String normalize(String path, int len, int off) {
        if (len == 0) return path;
        if (off < 3) off = 0;   /* Avoid fencepost cases with UNC pathnames */
        int src;
        char slash = this.slash;
        StringBuilder sb = new StringBuilder(len);

        if (off == 0) {
            /* Complete normalization, including prefix */
            src = normalizePrefix(path, len, sb);
        } else {
            /* Partial normalization */
            src = off;
            sb.append(path, 0, off);
        }

        /* Remove redundant slashes from the remainder of the path, forcing all
           slashes into the preferred slash */
        while (src < len) {
            char c = path.charAt(src++);
            if (isSlash(c)) {
                while ((src < len) && isSlash(path.charAt(src))) src++;
                if (src == len) {
                    /* Check for trailing separator */
                    int sn = sb.length();
                    if ((sn == 2) && (sb.charAt(1) == ':')) {
                        /* "z:\\" */
                        sb.append(slash);
                        break;
                    }
                    if (sn == 0) {
                        /* "\\" */
                        sb.append(slash);
                        break;
                    }
                    if ((sn == 1) && (isSlash(sb.charAt(0)))) {
                        /* "\\\\" is not collapsed to "\\" because "\\\\" marks
                           the beginning of a UNC pathname.  Even though it is
                           not, by itself, a valid UNC pathname, we leave it as
                           is in order to be consistent with the win32 APIs,
                           which treat this case as an invalid UNC pathname
                           rather than as an alias for the root directory of
                           the current drive. */
                        sb.append(slash);
                        break;
                    }
                    /* Path does not denote a root directory, so do not append
                       trailing slash */
                    break;
                } else {
                    sb.append(slash);
                }
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /* A normal Win32 pathname contains no duplicate slashes, except possibly
       for a UNC prefix, and does not end with a slash.  It may be the empty
       string.  Normalized Win32 pathnames have the convenient property that
       the length of the prefix almost uniquely identifies the type of the path
       and whether it is absolute or relative:

           0  relative to both drive and directory
           1  drive-relative (begins with '\\')
           2  absolute UNC (if first char is '\\'),
                else directory-relative (has form "z:foo")
           3  absolute local pathname (begins with "z:\\")
     */
    private int normalizePrefix(String path, int len, StringBuilder sb) {
        int src = 0;
        while ((src < len) && isSlash(path.charAt(src))) src++;
        char c;
        if ((len - src >= 2)
            && isLetter(c = path.charAt(src))
            && path.charAt(src + 1) == ':') {
            /* Remove leading slashes if followed by drive specifier.
               This hack is necessary to support file URLs containing drive
               specifiers (e.g., "file://c:/path").  As a side effect,
               "/c:/path" can be used as an alternative to "c:/path". */
            sb.append(c);
            sb.append(':');
            src += 2;
        } else {
            src = 0;
            if ((len >= 2)
                && isSlash(path.charAt(0))
                && isSlash(path.charAt(1))) {
                /* UNC pathname: Retain first slash; leave src pointed at
                   second slash so that further slashes will be collapsed
                   into the second slash.  The result will be a pathname
                   beginning with "\\\\" followed (most likely) by a host
                   name. */
                src = 1;
                sb.append(slash);
            }
        }
        return src;
    }

    @Override
    public int prefixLength(String path) {
        if (WinApkPaths.isAndroid(path)) return 1;  /* Win.apk: an Android path, "/" its root */
        char slash = this.slash;
        int n = path.length();
        if (n == 0) return 0;
        char c0 = path.charAt(0);
        char c1 = (n > 1) ? path.charAt(1) : 0;
        if (c0 == slash) {
            if (c1 == slash) return 2;  /* Absolute UNC pathname "\\\\foo" */
            return 1;                   /* Drive-relative "\\foo" */
        }
        if (isLetter(c0) && (c1 == ':')) {
            if ((n > 2) && (path.charAt(2) == slash))
                return 3;               /* Absolute local pathname "z:\\foo" */
            return 2;                   /* Directory-relative "z:foo" */
        }
        return 0;                       /* Completely relative */
    }

    @Override
    public String resolve(String parent, String child) {
        int pn = parent.length();
        if (pn == 0) return child;
        int cn = child.length();
        if (cn == 0) return parent;
        if (WinApkPaths.isAndroid(parent)) {
            // Win.apk: under an Android path a child joins with '/'; a drive in
            // it nests as below ("D:\a" -> "D/a").
            if (cn >= 2 && isLetter(child.charAt(0)) && child.charAt(1) == ':') {
                child = child.charAt(0) + "/" + child.substring(2);
            }
            return WinApkPaths.join(parent, child);
        }

        // Win.apk: a drive-absolute child ("D:\\a\\b", or "D:a") nests under
        // the parent as a relative path, with the drive letter as the first
        // directory, the way an absolute child nests on Android (see the file
        // header).
        if (cn >= 2 && isLetter(child.charAt(0)) && child.charAt(1) == ':') {
            String rest = child.substring(2);
            child = child.charAt(0) + (rest.isEmpty() || rest.charAt(0) == slash ? "" : "\\") + rest;
            cn = child.length();
        }

        String c = child;
        int childStart = 0;
        int parentEnd = pn;

        boolean isDirectoryRelative =
            pn == 2 && isLetter(parent.charAt(0)) && parent.charAt(1) == ':';

        if ((cn > 1) && (c.charAt(0) == slash)) {
            if (c.charAt(1) == slash) {
                /* Drop prefix when child is a UNC pathname */
                childStart = 2;
            } else if (!isDirectoryRelative) {
                /* Drop prefix when child is drive-relative */
                childStart = 1;

            }
            if (cn == childStart) { // Child is double slash
                if (parent.charAt(pn - 1) == slash)
                    return parent.substring(0, pn - 1);
                return parent;
            }
        }

        if (parent.charAt(pn - 1) == slash)
            parentEnd--;

        int strlen = parentEnd + cn - childStart;
        char[] theChars = null;
        if (child.charAt(childStart) == slash || isDirectoryRelative) {
            theChars = new char[strlen];
            parent.getChars(0, parentEnd, theChars, 0);
            child.getChars(childStart, cn, theChars, parentEnd);
        } else {
            theChars = new char[strlen + 1];
            parent.getChars(0, parentEnd, theChars, 0);
            theChars[parentEnd] = slash;
            child.getChars(childStart, cn, theChars, parentEnd + 1);
        }

        // if present, strip trailing name separator unless after a ':'
        if (theChars.length > 1
            && theChars[theChars.length - 1] == slash
            && theChars[theChars.length - 2] != ':')
            return new String(theChars, 0, theChars.length - 1);

        return new String(theChars);
    }

    @Override
    public String getDefaultParent() {
        return ("" + slash);
    }

    @Override
    public String fromURIPath(String path) {
        String p = path;
        if ((p.length() > 2) && (p.charAt(2) == ':')) {
            // "/c:/foo" --> "c:/foo"
            p = p.substring(1);
            // "c:/foo/" --> "c:/foo", but "c:/" --> "c:/"
            if ((p.length() > 3) && p.endsWith("/"))
                p = p.substring(0, p.length() - 1);
        } else if ((p.length() > 1) && p.endsWith("/")) {
            // "/foo/" --> "/foo"
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /* -- Path operations -- */

    @Override
    public boolean isAbsolute(File f) {
        if (WinApkPaths.isAndroid(f.getPath())) return true;
        int pl = f.getPrefixLength();
        return (((pl == 2) && (f.getPath().charAt(0) == slash))
                || (pl == 3));
    }

    @Override
    public boolean isInvalid(File f) {
        if (f.getPath().indexOf('\u0000') >= 0)
            return true;

        if (ENABLE_ADS)
            return false;

        // Invalid if there is a ":" at a position greater than 1, or if there
        // is a ":" at position 1 and the first character is not a letter
        String pathname = f.getPath();
        int lastColon = pathname.lastIndexOf(":");

        // Valid if there is no ":" present or if the last ":" present is
        // at index 1 and the first character is a latter
        if (lastColon < 0 ||
            (lastColon == 1 && isLetter(pathname.charAt(0))))
            return false;

        // Invalid if path creation fails
        Path path = null;
        try {
            path = sun.nio.fs.DefaultFileSystemProvider.theFileSystem().getPath(pathname);
            return false;
        } catch (InvalidPathException ignored) {
        }

        return true;
    }

    @Override
    public String resolve(File f) {
        String path = f.getPath();
        if (WinApkPaths.isAndroid(path)) return path;
        int pl = f.getPrefixLength();
        if ((pl == 2) && (path.charAt(0) == slash))
            return path;                        /* UNC */
        if (pl == 3)
            return path;                        /* Absolute local */
        if (pl == 0)
            return getUserPath() + slashify(path); /* Completely relative */
        if (pl == 1) {                          /* Drive-relative */
            String up = getUserPath();
            String ud = getDrive(up);
            if (ud != null) return ud + path;
            return up + path;                   /* User dir is a UNC path */
        }
        if (pl == 2) {                          /* Directory-relative */
            String up = getUserPath();
            String ud = getDrive(up);
            if ((ud != null) && path.startsWith(ud))
                return up + slashify(path.substring(2));
            char drive = path.charAt(0);
            String dir = getDriveDirectory(drive);
            if (dir != null) {
                /* When resolving a directory-relative path that refers to a
                   drive other than the current drive, insist that the caller
                   have read permission on the result */
                String p = drive + (':' + dir + slashify(path.substring(2)));
                @SuppressWarnings("removal")
                SecurityManager security = System.getSecurityManager();
                try {
                    if (security != null) security.checkRead(p);
                } catch (SecurityException x) {
                    /* Don't disclose the drive's directory in the exception */
                    throw new SecurityException("Cannot resolve path " + path);
                }
                return p;
            }
            return drive + ":" + slashify(path.substring(2)); /* fake it */
        }
        throw new InternalError("Unresolvable path: " + path);
    }

    private String getUserPath() {
        /* For both compatibility and security,
           we must look this up every time */
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPropertyAccess("user.dir");
        }
        return userDir;
    }

    private String getDrive(String path) {
        int pl = prefixLength(path);
        return (pl == 3) ? path.substring(0, 2) : null;
    }

    private static final String[] DRIVE_DIR_CACHE = new String[26];

    private static int driveIndex(char d) {
        if ((d >= 'a') && (d <= 'z')) return d - 'a';
        if ((d >= 'A') && (d <= 'Z')) return d - 'A';
        return -1;
    }

    private native String getDriveDirectory(int drive);

    private String getDriveDirectory(char drive) {
        int i = driveIndex(drive);
        if (i < 0) return null;
        // Updates might not be visible to other threads so there
        // is no guarantee getDriveDirectory(i+1) is called just once
        // for any given value of i.
        String s = DRIVE_DIR_CACHE[i];
        if (s != null) return s;
        s = getDriveDirectory(i + 1);
        DRIVE_DIR_CACHE[i] = s;
        return s;

    }

    @Override
    public String canonicalize(String path) throws IOException {
        // Win.apk: an Android path stays one, its "." and ".." folded.
        if (WinApkPaths.isAndroid(path)) return WinApkPaths.fold(path);
        // If path is a drive letter only then skip canonicalization
        int len = path.length();
        if ((len == 2) &&
            (isLetter(path.charAt(0))) &&
            (path.charAt(1) == ':')) {
            char c = path.charAt(0);
            if ((c >= 'A') && (c <= 'Z'))
                return path;
            return "" + ((char) (c-32)) + ':';
        } else if ((len == 3) &&
                   (isLetter(path.charAt(0))) &&
                   (path.charAt(1) == ':') &&
                   (path.charAt(2) == '\\')) {
            char c = path.charAt(0);
            if ((c >= 'A') && (c <= 'Z'))
                return path;
            return "" + ((char) (c-32)) + ':' + '\\';
        }
        long comp = Blocker.begin();
        try {
            return canonicalize0(path);
        } finally {
            Blocker.end(comp);
        }
    }

    private native String canonicalize0(String path)
            throws IOException;


    /* -- Attribute accessors -- */

    @Override
    public int getBooleanAttributes(File f) {
        long comp = Blocker.begin();
        try {
            return getBooleanAttributes0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native int getBooleanAttributes0(File f);

    @Override
    public boolean checkAccess(File f, int access) {
        long comp = Blocker.begin();
        try {
            return checkAccess0(host(f), access);
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean checkAccess0(File f, int access);

    @Override
    public long getLastModifiedTime(File f) {
        long comp = Blocker.begin();
        try {
            return getLastModifiedTime0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native long getLastModifiedTime0(File f);

    @Override
    public long getLength(File f) {
        long comp = Blocker.begin();
        try {
            return getLength0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native long getLength0(File f);

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        long comp = Blocker.begin();
        try {
            return setPermission0(host(f), access, enable, owneronly);
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean setPermission0(File f, int access, boolean enable, boolean owneronly);

    /* -- File operations -- */

    @Override
    public boolean createFileExclusively(String path) throws IOException {
        long comp = Blocker.begin();
        try {
            return createFileExclusively0(WinApkPaths.hostPath(path));
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean createFileExclusively0(String path) throws IOException;

    @Override
    public String[] list(File f) {
        long comp = Blocker.begin();
        try {
            String[] names = list0(host(f));
            // Win.apk: under an Android path the names are Android's (the characters Windows
            // forbids are stored as private-use ones, WinApkPaths.androidName).
            if (names != null && WinApkPaths.isAndroid(f.getPath())) {
                for (int i = 0; i < names.length; i++) names[i] = WinApkPaths.androidName(names[i]);
            }
            return names;
        } finally {
            Blocker.end(comp);
        }
    }
    private native String[] list0(File f);

    @Override
    public boolean createDirectory(File f) {
        long comp = Blocker.begin();
        try {
            return createDirectory0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean createDirectory0(File f);

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        long comp = Blocker.begin();
        try {
            return setLastModifiedTime0(host(f), time);
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean setLastModifiedTime0(File f, long time);

    @Override
    public boolean setReadOnly(File f) {
        long comp = Blocker.begin();
        try {
            return setReadOnly0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean setReadOnly0(File f);

    @Override
    public boolean delete(File f) {
        long comp = Blocker.begin();
        try {
            return delete0(host(f));
        } finally {
            Blocker.end(comp);
        }
    }
    private native boolean delete0(File f);

    @Override
    public boolean rename(File f1, File f2) {
        long comp = Blocker.begin();
        try {
            if (rename0(host(f1), host(f2))) return true;
        } finally {
            Blocker.end(comp);
        }
        return renameOverFile(f1, f2);
    }
    private native boolean rename0(File f1, File f2);

    // Win.apk: the host file an Android path names, for the natives (the third change).
    private static File host(File f) {
        String p = f.getPath();
        return WinApkPaths.isAndroid(p) ? new File(WinApkPaths.hostPath(p)) : f;
    }

    // Win.apk: rename(2) replaces an existing file in one step; MoveFileEx with
    // MOVEFILE_REPLACE_EXISTING (what an atomic Files.move does) is the same.
    // A rename that still fails is logged with Windows' reason (the first few
    // per process): renameTo only answers false, and where rename(2) succeeds
    // but Windows refuses (a file some handle holds open), the app's own error
    // says nothing about why.
    // In winapk-runtime the host binds winapkReplace (windows/host/java_io_open.cpp),
    // a POSIX-semantics rename that also replaces a target another process has
    // open, as rename(2) does; elsewhere (a plain JVM with this overlay) it is
    // unbound and Files.move does it.
    private boolean renameOverFile(File f1, File f2) {
        try {
            if (f1.isFile() && (f2.isFile() || !f2.exists())) {
                int error = -1;
                try {
                    error = winapkReplace(WinApkPaths.hostPath(f1.getPath()), WinApkPaths.hostPath(f2.getPath()));
                } catch (UnsatisfiedLinkError unbound) {
                    // the JDK's move below
                }
                if (error == 0) return true;
                try {
                    java.nio.file.Files.move(f1.toPath(), f2.toPath(),
                                             java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                             java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    return true;
                } catch (Exception e) {
                    noteRenameFailure(f1, f2, e + (error > 0 ? " (Windows error " + error + ")" : ""));
                    return false;
                }
            }
            if (f1.exists()) noteRenameFailure(f1, f2, "not a file renamed onto a file");
        } catch (Exception e) {
            noteRenameFailure(f1, f2, String.valueOf(e));
        }
        return false;
    }
    private static native int winapkReplace(String from, String to);

    private static final java.util.concurrent.atomic.AtomicInteger renameFailures =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void noteRenameFailure(File f1, File f2, String why) {
        int n = renameFailures.incrementAndGet();
        if (n > 20) return;
        System.err.println("[win.apk] rename " + f1.getPath() + " -> " + f2.getPath() + " failed: " + why
                           + (n == 20 ? " (no more rename failures are logged)" : ""));
    }

    /* -- Filesystem interface -- */

    @Override
    public File[] listRoots() {
        return BitSet
            .valueOf(new long[] {listRoots0()})
            .stream()
            .mapToObj(i -> new File((char)('A' + i) + ":" + slash))
            .filter(f -> access(f.getPath()))
            .toArray(File[]::new);
    }
    private static native int listRoots0();

    private boolean access(String path) {
        try {
            @SuppressWarnings("removal")
            SecurityManager security = System.getSecurityManager();
            if (security != null) security.checkRead(path);
            return true;
        } catch (SecurityException x) {
            return false;
        }
    }

    /* -- Disk usage -- */

    @Override
    public long getSpace(File f, int t) {
        if (f.exists()) {
            return getSpace0(host(f), t);
        }
        return 0;
    }
    private native long getSpace0(File f, int t);

    /* -- Basic infrastructure -- */

    // Obtain maximum file component length from GetVolumeInformation which
    // expects the path to be null or a root component ending in a backslash
    private native int getNameMax0(String path);

    @Override
    public int getNameMax(String path) {
        if (path != null) path = WinApkPaths.hostPath(path);
        String s = null;
        if (path != null) {
            File f = new File(path);
            if (f.isAbsolute()) {
                Path root = f.toPath().getRoot();
                if (root != null) {
                    s = root.toString();
                    if (!s.endsWith("\\")) {
                        s = s + "\\";
                    }
                }
            }
        }
        return getNameMax0(s);
    }

    @Override
    public int compare(File f1, File f2) {
        return f1.getPath().compareToIgnoreCase(f2.getPath());
    }

    @Override
    public int hashCode(File f) {
        /* Could make this more efficient: String.hashCodeIgnoreCase */
        return f.getPath().toLowerCase(Locale.ENGLISH).hashCode() ^ 1234321;
    }

    private static native void initIDs();

    static {
        initIDs();
    }
}
