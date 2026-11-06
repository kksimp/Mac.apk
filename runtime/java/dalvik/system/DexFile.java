// dalvik.system.DexFile -- the legacy (deprecated since API 26) handle to a
// loaded DEX file. Still referenced by name: androidx MultiDex's pre-API-21
// install path uses DexFile.loadDex / entries / loadClass, and many old plugin
// SDKs hold a DexFile reflectively. A missing class makes
// `Class.forName("dalvik.system.DexFile")` and those reflective calls fail.
//
// AOSP (libcore/dalvik/src/main/java/dalvik/system/DexFile.java) surface we
// mirror (signatures verified against android.jar javap):
//   public DexFile(String fileName)
//   public DexFile(File file)
//   public static DexFile loadDex(String sourcePathName, String outputPathName, int flags)
//   public String getName()
//   public String toString()
//   public void close()
//   public Class<?> loadClass(String name, ClassLoader loader)
//   public Enumeration<String> entries()
//   public static boolean isDexOptNeeded(String fileName)   // deprecated
//   protected void finalize()
//
// On a real device the class names are read from the DEX's string/class tables
// and loadClass routes through ART. Mac.apk has no DEX interpreter, so we run
// the source file through com.macapk.dex (the same in-process DEX->JVM-bytecode
// converter the primary app dex uses) into a jar, build an internal
// URLClassLoader over it, enumerate the jar's classes for entries(), and resolve
// loadClass through that loader (falling back to the caller's loader).
package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// AOSP fidelity: the deprecation usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("deprecation")
public final class DexFile {

    private final String name;
    private final URL convertedJarUrl;        // com.macapk.dex output, or null
    private volatile URLClassLoader loader;   // lazily built over the jar
    private volatile boolean closed;

    /** AOSP: open a DEX/JAR/APK by path. The conversion runs eagerly so a bad
     *  path fails here (AOSP throws IOException on a missing/invalid file). */
    public DexFile(String fileName) throws IOException {
        this(new File(fileName));
    }

    /** AOSP: open a DEX/JAR/APK by File. */
    public DexFile(File file) throws IOException {
        this.name = file.getPath();
        if (!file.isFile()) {
            throw new IOException("unable to open DEX file: " + file);
        }
        URL u;
        try {
            u = MacApkDexConverter.convertFileToJarUrl(file);
        } catch (RuntimeException e) {
            throw new IOException("failed to load DEX file: " + file, e);
        }
        this.convertedJarUrl = u;
    }

    // Internal: wrap an already-converted jar (used by loadDex).
    private DexFile(String name, URL convertedJarUrl) {
        this.name = name;
        this.convertedJarUrl = convertedJarUrl;
    }

    /** AOSP: open a DEX file, applying optimizations to outputPathName. The
     *  output path / flags are device-optimization details with no analogue
     *  here; we ignore them and convert the source. */
    public static DexFile loadDex(String sourcePathName, String outputPathName,
                                  int flags) throws IOException {
        File src = new File(sourcePathName);
        if (!src.isFile()) {
            throw new IOException("unable to open DEX file: " + sourcePathName);
        }
        URL u;
        try {
            u = MacApkDexConverter.convertFileToJarUrl(src);
        } catch (RuntimeException e) {
            throw new IOException("failed to load DEX file: " + sourcePathName, e);
        }
        return new DexFile(sourcePathName, u);
    }

    /** AOSP: the source path name of this DexFile. */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }

    /** AOSP: close the DexFile, releasing the mapped DEX. We close the backing
     *  URLClassLoader (best-effort). */
    public void close() throws IOException {
        closed = true;
        URLClassLoader l = loader;
        if (l != null) {
            l.close();
            loader = null;
        }
    }

    /** AOSP: load a class by name using {@code loader} as the defining loader.
     *  AOSP returns null (rather than throwing) when the class is not present in
     *  this DEX, so callers can probe. We mirror that: try our converted-jar
     *  loader first (parented to the caller's loader so dependencies resolve),
     *  return null on absence. */
    public Class<?> loadClass(String name, ClassLoader loader) {
        // AOSP: loadClass converts dotted to slash form then delegates to
        // loadClassBinaryName(slashName, loader, null).
        String slashName = name.replace('.', '/');
        return loadClassBinaryName(slashName, loader, null);
    }

    /** AOSP (@UnsupportedAppUsage but reached reflectively by androidx
     *  MultiDex.installSecondaryDexes): take a "binary"/slash-form class name,
     *  define it through this DEX, and on a NoClassDefFoundError /
     *  ClassNotFoundException record it in {@code suppressed} (when non-null)
     *  and return null instead of throwing -- exactly AOSP's defineClass
     *  contract. We resolve through the converted-jar loader; Class.forName
     *  accepts the dotted name, so we convert back from the slash form. */
    public Class<?> loadClassBinaryName(String name, ClassLoader loader,
                                        java.util.List<Throwable> suppressed) {
        if (closed) return null;
        String binary = name.replace('/', '.');
        if (DEFINE_IN_LOADER && loader != null
                && (loader instanceof BaseDexClassLoader || PlainDefiner.AVAILABLE)) {
            return defineInLoader(loader, binary, suppressed);
        }
        try {
            return Class.forName(binary, false, internalLoader(loader));
        } catch (ClassNotFoundException | NoClassDefFoundError notHere) {
            if (suppressed != null) suppressed.add(notHere);
            return null;
        } catch (LinkageError other) {
            // Other linkage failures mirror AOSP's silent-null path (the DEX is
            // present but the class could not be defined); record and return.
            if (suppressed != null) suppressed.add(other);
            return null;
        }
    }

    /** MACAPK_DEXFILE_DEFINE_IN_LOADER (default ON; =0 restores the old path that
     *  hands back a class defined by a private child loader). ART's
     *  DexFile_defineClassNative defines the class with the CALLER'S loader as its
     *  defining loader (art/runtime/native/dalvik_system_DexFile.cc, DefineClass(...,
     *  class_loader, ...)), whatever kind of ClassLoader that is, and never calls
     *  the loader's loadClass to do it. Protected SDKs rely on both halves: Adyen's
     *  3DS2 SDK (Efteling) defines its own ClassLoader subclass (atd.e.ChallengeResultSS)
     *  out of a dex it writes to code_cache -- found afterwards by a plain
     *  Class.forName from the app loader -- and that loader's own loadClass then
     *  calls DexFile.loadClass(name, this) for every class of its payload dex. The old
     *  path resolved the name through a child URLClassLoader whose PARENT was that
     *  loader, so Class.forName delegated parent-first straight back into the SDK's
     *  loadClass, which called DexFile.loadClass again: unbounded recursion, ~600,000
     *  live exceptions and a 1 GiB heap exhausted in seconds. loader == null (the boot
     *  loader) keeps the old path: nothing can be defined into it. */
    private static final boolean DEFINE_IN_LOADER = !"0".equals(System.getenv("MACAPK_DEXFILE_DEFINE_IN_LOADER"));

    private Class<?> defineInLoader(ClassLoader loader, String binary,
                                    java.util.List<Throwable> suppressed) {
        byte[] bytes = classBytes(binary.replace('.', '/'));
        if (bytes == null) return null;      // AOSP: null when this dex has no such class
        try {
            Class<?> c = loader instanceof BaseDexClassLoader
                    ? ((BaseDexClassLoader) loader).defineClassFromDexFile(binary, bytes)
                    : PlainDefiner.define(loader, binary, bytes);
            System.err.println("[macapk] DexFile " + name + ": defined " + binary + " in "
                    + loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader))
                    + (c.getClassLoader() == loader ? "" : " (class is held by " + c.getClassLoader() + ")"));
            return c;
        } catch (LinkageError | SecurityException e) {
            // AOSP's silent-null path: the dex has the class but it could not be defined.
            System.err.println("[macapk] DexFile " + name + ": define " + binary + " failed: " + e);
            if (suppressed != null) suppressed.add(e);
            return null;
        }
    }

    /** Defining into a ClassLoader that is not one of ours: ClassLoader.defineClass /
     *  findLoadedClass / getClassLoadingLock are protected, reached reflectively
     *  (the runtime opens java.base/java.lang to the unnamed module). */
    private static final class PlainDefiner {
        private static final java.lang.reflect.Method DEFINE, FIND, LOCK;
        /** False in a JVM that does not open java.base/java.lang (an offline tool):
         *  the caller then keeps the old private-loader path instead of failing. */
        static final boolean AVAILABLE;
        static {
            java.lang.reflect.Method d = null, f = null, l = null;
            boolean ok = false;
            try {
                d = ClassLoader.class.getDeclaredMethod("defineClass",
                        String.class, byte[].class, int.class, int.class);
                f = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
                l = ClassLoader.class.getDeclaredMethod("getClassLoadingLock", String.class);
                d.setAccessible(true);
                f.setAccessible(true);
                l.setAccessible(true);
                ok = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through: AVAILABLE stays false
            }
            DEFINE = d; FIND = f; LOCK = l; AVAILABLE = ok;
        }

        static Class<?> define(ClassLoader loader, String binary, byte[] bytes) {
            try {
                Object lock = LOCK.invoke(loader, binary);
                synchronized (lock) {
                    Class<?> have = (Class<?>) FIND.invoke(loader, binary);
                    if (have != null) return have;   // ART: DefineClass returns the class already inserted
                    return (Class<?>) DEFINE.invoke(loader, binary, bytes, 0, bytes.length);
                }
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable t = ite.getCause();
                if (t instanceof LinkageError) throw (LinkageError) t;
                if (t instanceof SecurityException) throw (SecurityException) t;
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new LinkageError("defineClass " + binary + " failed: " + t, t);
            } catch (IllegalAccessException iae) {
                throw new LinkageError("defineClass " + binary + " not accessible: " + iae, iae);
            }
        }
    }

    /** The converted .class bytes for a slash-form name, or null. */
    private byte[] classBytes(String slashName) {
        if (convertedJarUrl == null) return null;
        try (ZipFile zf = new ZipFile(new File(convertedJarUrl.toURI()))) {
            ZipEntry ze = zf.getEntry(slashName + ".class");
            if (ze == null) return null;
            try (java.io.InputStream in = zf.getInputStream(ze)) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** AOSP: enumerate the class names contained in this DEX "in the usual
     *  internal form (like java/lang/String)" -- i.e. SLASH-separated, not
     *  dotted. (The prior impl returned dotted names; androidx MultiDex's
     *  installSecondaryDexes feeds each entry back to loadClass, which does its
     *  own dot->slash, so dotted happened to work, but the published contract
     *  is slash form and callers that pass entries() output to
     *  loadClassBinaryName directly need it.) We read the converted jar's
     *  .class entries, which are already slash-separated zip paths. */
    public Enumeration<String> entries() {
        if (convertedJarUrl == null) return Collections.emptyEnumeration();
        List<String> names = new ArrayList<>();
        File jar;
        try {
            jar = new File(convertedJarUrl.toURI());
        } catch (Exception e) {
            return Collections.enumeration(names);
        }
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith(".class")) {
                    // strip ".class"; the zip path is already slash-separated,
                    // which IS the internal form AOSP's entries() returns.
                    names.add(n.substring(0, n.length() - 6));
                }
            }
        } catch (IOException ignore) {
            // best-effort; return what we have
        }
        return Collections.enumeration(names);
    }

    /** AOSP (deprecated): whether dexopt is needed for the given file. There is
     *  no on-device optimization here, so optimization is never "needed". */
    @Deprecated
    public static boolean isDexOptNeeded(String fileName) {
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private URLClassLoader internalLoader(ClassLoader parent) {
        URLClassLoader l = loader;
        if (l == null) {
            synchronized (this) {
                l = loader;
                if (l == null) {
                    l = new URLClassLoader(new URL[] { convertedJarUrl }, parent);
                    loader = l;
                }
            }
        }
        return l;
    }

    /**
     * AOSP DexFile.OptimizationInfo (libcore DexFile.java): what ART reports about
     * a dex file's odex state. The predicates are ART's CompilerFilter rules
     * (libartbase/base/compiler_filter.cc), reached in AOSP through the
     * DexFile_is*CompilerFilter natives: a name ParseCompilerFilter does not know
     * (such as "unknown") answers false to every one of them.
     */
    public static final class OptimizationInfo {
        private final String status;
        private final String reason;

        OptimizationInfo(String status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        /** @hide (SystemApi MODULE_LIBRARIES) */
        public String getStatus() { return status; }

        /** @hide (SystemApi MODULE_LIBRARIES) */
        public String getReason() { return reason; }

        public boolean isVerified() { return isVerifiedCompilerFilter(status); }

        public boolean isOptimized() { return isOptimizedCompilerFilter(status); }

        public boolean isFullyCompiled() {
            return isOptimizedCompilerFilter(status) && !isProfileGuidedCompilerFilter(status);
        }
    }

    /** ART CompilerFilter::ParseCompilerFilter, with the obsolete aliases it still
     *  maps. Returns the canonical filter name, or null when ART would fail to parse. */
    private static String parseCompilerFilter(String option) {
        if (option == null) return null;
        switch (option) {
            case "verify-none":
            case "assume-verified":   return "assume-verified";
            case "interpret-only":
            case "verify-profile":
            case "verify-at-runtime":
            case "extract":
            case "quicken":
            case "verify":            return "verify";
            case "balanced":
            case "speed":             return "speed";
            case "time":
            case "space":             return "space";
            case "space-profile":     return "space-profile";
            case "speed-profile":     return "speed-profile";
            case "everything":        return "everything";
            case "everything-profile": return "everything-profile";
            default:                  return null;
        }
    }

    /** AOSP: whether the given filter is a valid compiler filter name. */
    public static boolean isValidCompilerFilter(String filter) {
        return parseCompilerFilter(filter) != null;
    }

    /** AOSP (CompilerFilter::DependsOnProfile). */
    public static boolean isProfileGuidedCompilerFilter(String filter) {
        String f = parseCompilerFilter(filter);
        return f != null && f.endsWith("-profile");
    }

    /** AOSP (CompilerFilter::IsVerificationEnabled). @hide */
    public static boolean isVerifiedCompilerFilter(String filter) {
        String f = parseCompilerFilter(filter);
        return f != null && !f.equals("assume-verified");
    }

    /** AOSP (CompilerFilter::IsAotCompilationEnabled). @hide */
    public static boolean isOptimizedCompilerFilter(String filter) {
        String f = parseCompilerFilter(filter);
        return f != null && !f.equals("assume-verified") && !f.equals("verify");
    }
}
