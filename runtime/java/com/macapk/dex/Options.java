// Options -- process-wide converter settings.
//
// Every field is read during conversion, so set them BEFORE DexConverter.open()
// and do not change them while a session is converting. The CLI (Main) sets
// them from its flags; an embedding application sets them directly.
//
// Each field is seeded, highest precedence first, from a system property, an
// environment variable, an optional HostDefaults class, then the built-in
// default:
//
//   field            system property            environment variable        HostDefaults field
//   syntheticPrefix  dex2jvm.syntheticPrefix    DEX2JVM_SYNTHETIC_PREFIX    SYNTHETIC_PREFIX
//   unsafeRedirect   dex2jvm.redirectUnsafe     DEX2JVM_REDIRECT_UNSAFE     REDIRECT_UNSAFE
//   exitRedirect     dex2jvm.redirectExit       DEX2JVM_REDIRECT_EXIT       REDIRECT_EXIT
//   nativeLoadRedirect dex2jvm.redirectNativeLoad DEX2JVM_REDIRECT_NATIVE_LOAD REDIRECT_NATIVE_LOAD
//   threads          dex2jvm.threads            DEX2JVM_THREADS             THREADS
//   logTag           dex2jvm.logTag             DEX2JVM_LOG_TAG             LOG_TAG
//   parsedMarker     dex2jvm.parsedMarker       DEX2JVM_PARSED_MARKER       PARSED_MARKER
//   library          dex2jvm.library            DEX2JVM_LIBRARY             LIBRARY
//
// HOSTDEFAULTS. A product that vendors this source tree can add a class named
// HostDefaults to THIS package, holding public static final String fields named
// as in the last column. Its values become that build's defaults, so the
// vendored copy can be synced from upstream verbatim and still behave the way
// the product needs: the product's own settings live in one file the sync
// never touches. A build without the class (this repository) uses the built-in
// defaults.

package com.macapk.dex;

public final class Options {
    private Options() {}

    /**
     * Prefix for the synthetic members the converter invents (outlined and
     * split method parts, spill fields, the message of an oversize-method
     * stub). The JVM never sees it as anything but a name; it only has to be
     * something an app's own code will not already use.
     */
    public static volatile String syntheticPrefix = setting("dex2jvm.syntheticPrefix",
            "DEX2JVM_SYNTHETIC_PREFIX", "SYNTHETIC_PREFIX", "dex2jvm");

    /**
     * When non-null, the internal name of a class that receives every routed
     * {@code sun.misc.Unsafe} call (see UnsafeRepoint), as a static method
     * taking the Unsafe receiver as its first parameter. Null (the default)
     * leaves Unsafe calls exactly as the DEX wrote them.
     *
     * <p>This exists for hosts that run Android code on a JVM whose Unsafe
     * semantics differ from ART's (field offsets in particular). A plain
     * conversion has no reason to set it.
     */
    public static volatile String unsafeRedirect = setting("dex2jvm.redirectUnsafe",
            "DEX2JVM_REDIRECT_UNSAFE", "REDIRECT_UNSAFE", null);

    /**
     * When non-null, the internal name of a class that receives
     * {@code System.exit(int)} as {@code systemExit(I)V}, and
     * {@code Runtime.exit/halt(int)} as {@code runtimeExit/runtimeHalt
     * (Ljava/lang/Runtime;I)V}. Null (the default) leaves them alone.
     *
     * <p>For hosts where "the app exits" must not mean "the JVM exits".
     */
    public static volatile String exitRedirect = setting("dex2jvm.redirectExit",
            "DEX2JVM_REDIRECT_EXIT", "REDIRECT_EXIT", null);

    /**
     * When non-null, the internal name of a class that receives the app's calls
     * to {@code System.mapLibraryName(String)} as {@code mapLibraryName
     * (Ljava/lang/String;)Ljava/lang/String;}, {@code System.load(String)} as
     * {@code load(Ljava/lang/String;)V} and {@code Runtime.load(String)} as
     * {@code runtimeLoad(Ljava/lang/Runtime;Ljava/lang/String;)V}. Null (the
     * default) leaves them alone.
     *
     * <p>For hosts where the file name Android uses for a native library
     * ({@code libfoo.so}) is not what the host's JVM would produce, so an app
     * that builds the name itself and then loads the file it names (a custom
     * SoLoader does exactly this) can be answered the way Bionic answers it.
     */
    public static volatile String nativeLoadRedirect = setting("dex2jvm.redirectNativeLoad",
            "DEX2JVM_REDIRECT_NATIVE_LOAD", "REDIRECT_NATIVE_LOAD", null);

    /**
     * Worker threads for a bulk conversion; 0 means one per core, capped by heap
     * (see DexConverter.Session.threadCount). Output is
     * byte-identical at every thread count.
     */
    public static volatile int threads = parseInt(setting("dex2jvm.threads",
            "DEX2JVM_THREADS", "THREADS", null));

    /** The tag on every stderr line the converter prints, as "[tag] ...". */
    public static volatile String logTag = setting("dex2jvm.logTag",
            "DEX2JVM_LOG_TAG", "LOG_TAG", "dex2jvm");

    /**
     * When true, the CLI creates {@code <out.jar>.parsed} as soon as the input
     * has been fully PARSED, and removes it when it exits. A caller running the
     * conversion in the background can poll for it to learn the input is valid
     * (so a parse failure is ruled out) before the conversion itself finishes.
     */
    public static volatile boolean parsedMarker = Boolean.parseBoolean(setting(
            "dex2jvm.parsedMarker", "DEX2JVM_PARSED_MARKER", "PARSED_MARKER", "false"));

    /**
     * How the CLI picks the class-hierarchy library when --classpath is not
     * given: "auto" (the installed Android SDK platform matching the app, else
     * the bundled API index), "classloader" (only what the converter's own
     * class loader can see, for embedders that put their framework on it), or
     * "none".
     */
    public static volatile String library = setting("dex2jvm.library",
            "DEX2JVM_LIBRARY", "LIBRARY", "auto");

    /**
     * Where the class-hierarchy oracle reads framework and library classes
     * from (header only, nothing is loaded). Null means the converter's own
     * class loader. The CLI fills it in according to {@link #library}.
     */
    public static volatile ClassLoader hierarchyLoader = null;

    /** Every field's current value, for {@link #restore}. */
    static Object[] snapshot() {
        return new Object[] { syntheticPrefix, unsafeRedirect, exitRedirect, threads,
                              logTag, parsedMarker, library, hierarchyLoader };
    }

    /** Puts back what {@link #snapshot} saw: lets one process run several
     *  command lines in turn without one's flags leaking into the next. */
    static void restore(Object[] s) {
        syntheticPrefix = (String) s[0];
        unsafeRedirect = (String) s[1];
        exitRedirect = (String) s[2];
        threads = (Integer) s[3];
        logTag = (String) s[4];
        parsedMarker = (Boolean) s[5];
        library = (String) s[6];
        hierarchyLoader = (ClassLoader) s[7];
    }

    private static String setting(String property, String env, String hostField, String dflt) {
        String v = System.getProperty(property);
        if (v == null || v.isEmpty()) v = System.getenv(env);
        if (v == null || v.isEmpty()) v = hostDefault(hostField);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    /** A String field of this package's optional HostDefaults class, or null. */
    private static String hostDefault(String field) {
        try {
            Class<?> c = Class.forName(Options.class.getPackageName() + ".HostDefaults",
                    true, Options.class.getClassLoader());
            Object v = c.getField(field).get(null);
            return v instanceof String ? (String) v : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static int parseInt(String v) {
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
