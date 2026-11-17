// dalvik.system.NativeLibraryPath -- the librarySearchPath a dex class loader
// was created with, searched as AOSP's DexPathList.findLibrary searches its
// nativeLibraryDirectories (libcore DexPathList.java: splitPaths on ':' ->
// NativeLibraryElement(dir) or NativeLibraryElement(zip, "dir/") for a
// "<zip>!/<dir>" entry; findLibrary maps the name to lib<name>.so and answers
// the first element that has it).
//
// Why it matters: a loader made for code that is NOT the app's own ships its
// libraries beside that code, not in the app's lib dir. Play services' dynamite
// modules are the case that showed it: a client (Google Maps) loads
// app_chimera/m/<n>/CronetDynamite.apk into its own process with the library
// path "<that apk>!/lib/<abi>", and CronetLibraryLoader's System.loadLibrary
// then asks the module's loader. The path used to be dropped and the app's own
// lib dir searched instead, so Cronet was never found and Maps got no network
// engine.
//
// A directory entry answers the file in it. A "<zip>!/<dir>" entry names
// libraries stored inside an APK, which Android's linker maps in place; the
// host's loader needs a file, so the entry is copied out once, with the
// libraries of the same zip directory its DT_NEEDED names (the guest linker
// looks for a dependency beside the library that needs it), into
// <dataDir>/code_cache/macapk-native-libs/<zip path + dir hash>-<size>-<mtime>/
// under its own name, written to a temporary file and renamed. A changed APK
// gets a new directory; the old one is removed when it can be.
package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NativeLibraryPath {

    private static final boolean WINDOWS_HOST = File.pathSeparatorChar == ';';

    /** One entry of the path: a directory, or a directory inside a zip. */
    private static final class Entry {
        final String spec;
        final File dir;       // a directory entry, else null
        final File zip;       // "<zip>!/<dir>": the zip
        final String prefix;  // and "<dir>/" ("" for the zip's root)
        /** Where this zip directory's libraries are copied to, for the zip
         *  as it was (stamp) when the directory was chosen. Under this. */
        File outDir;
        String stamp;
        /** File names copied (or found copied) into outDir. Under this. */
        final Set<String> copied = new HashSet<>();

        Entry(String spec) {
            this.spec = spec;
            int bang = spec.indexOf("!/");
            if (bang < 0) {
                dir = new File(spec);
                zip = null;
                prefix = null;
            } else {
                dir = null;
                zip = new File(spec.substring(0, bang));
                String d = spec.substring(bang + 2);
                while (d.startsWith("/")) d = d.substring(1);
                while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
                prefix = d.isEmpty() ? "" : d + "/";
            }
        }
    }

    private final String path;
    private final List<Entry> entries = new ArrayList<>();

    NativeLibraryPath(String librarySearchPath) {
        path = librarySearchPath == null ? "" : librarySearchPath;
        for (String p : split(path, WINDOWS_HOST)) entries.add(new Entry(p));
        // One line per loader that has a path: what the next lookups go by.
        if (!entries.isEmpty()) System.err.println("[macapk] native libs: a dex loader searches " + path);
    }

    /** The entries of an Android library search path. Android separates them
     *  with ':'. On a Windows host an entry may be a host path (the app's
     *  nativeLibraryDir), whose drive colon is not a separator, and code that
     *  joins with File.pathSeparator has used ';'. */
    static List<String> split(String path, boolean windowsHost) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        int n = path.length();
        int start = 0;
        for (int i = 0; i <= n; i++) {
            boolean sep = i == n;
            if (!sep) {
                char c = path.charAt(i);
                if (c == ';') {
                    sep = windowsHost;
                } else if (c == ':') {
                    boolean drive = windowsHost && i == start + 1 && Character.isLetter(path.charAt(start))
                        && i + 1 < n && (path.charAt(i + 1) == '\\' || path.charAt(i + 1) == '/');
                    sep = !drive;
                }
            }
            if (sep) {
                if (i > start) out.add(path.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    boolean isEmpty() { return entries.isEmpty(); }

    /** The entries as files, for DexPathList.nativeLibraryDirectories (AOSP
     *  lists a "<zip>!/<dir>" entry there as a File too). */
    List<File> directories() {
        List<File> out = new ArrayList<>();
        for (Entry e : entries) out.add(new File(e.spec));
        return out;
    }

    /** lib<name>.so of the first entry that has it, as a file the host can
     *  open (a zip's copied out); null when no entry has it. */
    File find(String libname) {
        if (entries.isEmpty() || libname == null || libname.isEmpty()
                || libname.indexOf('/') >= 0 || libname.indexOf('\\') >= 0) {
            return null;
        }
        String file = "lib" + libname + ".so";  // libcore's System.mapLibraryName
        for (Entry e : entries) {
            File f;
            if (e.zip == null) {
                f = new File(e.dir, file);
                if (!f.isFile()) f = null;
            } else {
                f = fromZip(e, file);
            }
            if (f != null) return f;
        }
        return null;
    }

    private static File fromZip(Entry e, String file) {
        long size = e.zip.length();
        if (size == 0) return null;  // no such zip
        String stamp = Long.toHexString(size) + "-" + Long.toHexString(e.zip.lastModified());
        synchronized (e) {
            if (!stamp.equals(e.stamp)) {
                e.outDir = outDir(e, stamp);
                e.stamp = stamp;
                e.copied.clear();
            }
            File out = new File(e.outDir, file);
            if (e.copied.contains(file) && out.isFile()) return out;
            try (ZipFile zf = new ZipFile(e.zip)) {
                if (!copyOut(zf, e, file)) return null;
                // The libraries it needs from the same zip directory, and theirs.
                ArrayDeque<File> todo = new ArrayDeque<>();
                todo.add(out);
                while (!todo.isEmpty()) {
                    List<String> needed;
                    try {
                        needed = com.macapk.loader.ElfReader.readDtNeeded(todo.poll());
                    } catch (LinkageError noLoaderJar) {
                        break;  // a tool with the framework jar alone: the library by itself
                    }
                    for (String dep : needed) {
                        if (dep.isEmpty() || dep.indexOf('/') >= 0 || dep.indexOf('\\') >= 0) continue;
                        if (e.copied.contains(dep)) continue;
                        if (copyOut(zf, e, dep)) todo.add(new File(e.outDir, dep));
                    }
                }
                return out;
            } catch (IOException | RuntimeException ex) {
                System.err.println("[macapk] native libs: " + e.spec + "/" + file + ": " + ex);
                return null;
            }
        }
    }

    /** Copy the entry `file` of e's zip directory into e.outDir unless a
     *  complete copy is there. False when the zip has no such entry. */
    private static boolean copyOut(ZipFile zf, Entry e, String file) throws IOException {
        ZipEntry ze = zf.getEntry(e.prefix + file);
        if (ze == null || ze.isDirectory()) return false;
        File out = new File(e.outDir, file);
        if (!(out.isFile() && out.length() == ze.getSize())) {
            if (!e.outDir.isDirectory() && !e.outDir.mkdirs() && !e.outDir.isDirectory()) {
                throw new IOException("cannot create " + e.outDir);
            }
            File tmp = new File(e.outDir, file + "." + ProcessHandle.current().pid() + "." + System.nanoTime()
                + ".tmp");
            try {
                try (InputStream in = zf.getInputStream(ze)) {
                    Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException raced) {
                    // Another process put the same bytes there first, and may
                    // have the file mapped: its copy is the one to use.
                    if (!(out.isFile() && out.length() == ze.getSize())) throw raced;
                }
            } finally {
                tmp.delete();
            }
            System.err.println("[macapk] native libs: " + e.spec + "/" + file + " -> " + out);
        }
        e.copied.add(file);
        return true;
    }

    /** The directory for e's libraries, for the zip with this size and time.
     *  Directories left by an earlier version of the same zip are removed. */
    private static File outDir(Entry e, String stamp) {
        String zipPath;
        try { zipPath = e.zip.getCanonicalPath(); }
        catch (IOException ex) { zipPath = e.zip.getAbsolutePath(); }
        if (WINDOWS_HOST) zipPath = zipPath.toLowerCase(Locale.ROOT);
        String key = hash(zipPath + "!/" + e.prefix) + "-";
        File root = cacheRoot();
        File dir = new File(root, key + stamp);
        if (!dir.isDirectory()) {
            File[] old = root.listFiles((d, name) -> name.startsWith(key));
            if (old != null) {
                for (File o : old) {
                    File[] files = o.listFiles();
                    if (files != null) for (File f : files) f.delete();
                    o.delete();  // stays while a process has a library of it loaded
                }
            }
        }
        return dir;
    }

    /** <dataDir>/code_cache/macapk-native-libs, beside the converted dex
     *  (MacApkDexConverter.cacheDir); with no app, a per-process directory. */
    private static File cacheRoot() {
        String d = VMRuntime.processDataDirectory();
        if (d != null && !d.isEmpty()) return new File(new File(d, "code_cache"), "macapk-native-libs");
        return new File(System.getProperty("java.io.tmpdir", "/tmp"),
                        "macapk-native-libs." + ProcessHandle.current().pid());
    }

    private static String hash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override
    public String toString() { return path; }
}
