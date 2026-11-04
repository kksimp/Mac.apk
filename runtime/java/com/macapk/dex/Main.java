// Main -- the dex2jvm command line.
//
//     java -jar dex2jvm.jar [options] <apk|apkm|xapk|apks|dex> <out.jar>
//
// The jar holds ONLY .class entries (resources stay in the APK), uses fixed
// 1980 timestamps so the output is byte-reproducible, and stores rather than
// deflates entries under 10 KB -- the same threshold enjarify uses
// (main.py:52), because most class files are small and deflating them costs
// more than it saves.
//
// ERROR POLICY: per-class failures are reported on stderr and do not fail the
// run, so one unusual method does not cost the whole app. The exit code says
// WHERE a failure happened, because the two shapes deserve different handling
// by a caller:
//   0                   a jar was written
//   1                   the INPUT is bad: the container or a DEX cannot be
//                       parsed, or it parsed but no class converted
//   2                   usage error
//   EXIT_NO_DEX (3)     there is genuinely no code (android:hasCode="false")
//   EXIT_CONVERT_FAILED (4)
//                       the input parsed completely and the CONVERTER failed
//                       after that (out of memory, a converter defect, an IO
//                       error writing the jar)

package com.macapk.dex;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class Main {

    private Main() {}

    /** Below this, storing beats deflating; matches enjarify main.py:52. */
    private static final int DEFLATE_THRESHOLD = 10000;

    /** A fixed DOS epoch (1980-01-01 00:00) so the jar is byte-reproducible. */
    private static final long FIXED_TIME = 315532800000L;

    /**
     * Exit code for "this input contains no DEX at all", as distinct from
     * exit 1, "there is code here and we failed to convert it". An apk with
     * {@code <application android:hasCode="false">} and a NativeActivity is a
     * legal Android app with no dex anywhere.
     */
    public static final int EXIT_NO_DEX = 3;

    /**
     * Exit code for "the container parsed completely, and conversion failed
     * AFTER that" -- an OOM, a converter defect, an IO error writing the jar.
     * Distinct from exit 1 because exit 1 means the input itself is unusable,
     * while this one means the input is fine and a retry (more heap, a fixed
     * converter, converting classes one at a time) can succeed.
     */
    public static final int EXIT_CONVERT_FAILED = 4;

    /** Thrown when the input genuinely carries no DEX. Not a failure to
     *  convert -- there was nothing to convert. */
    public static final class NoDexException extends IOException {
        public NoDexException(String msg) { super(msg); }
    }

    private static final String USAGE = String.join("\n",
        "usage: dex2jvm [options] <input> <out.jar>",
        "",
        "  <input>   a .dex, an .apk, or a split bundle (.apkm / .xapk / .apks)",
        "",
        "options:",
        "  --classpath <path>        jars/dirs (separated by '" + File.pathSeparator + "') the class-hierarchy",
        "                            oracle reads library types from, e.g. an android.jar.",
        "                            Default: the installed Android SDK platform matching",
        "                            the app's compileSdk (ANDROID_HOME, ANDROID_SDK_ROOT or",
        "                            the Android Studio location), else the Android API",
        "                            index bundled in dex2jvm.",
        "  --no-library              use no library hierarchy at all (less precise frames)",
        "  --parsed-marker           create <out.jar>.parsed once the input is parsed",
        "                            (removed on exit), for callers that run the",
        "                            conversion in the background",
        "  --threads <n>             worker threads (default: one per core, at most 8 or",
        "                            one per 256 MB of heap, whichever is more); output",
        "                            is byte-identical at every thread count",
        "  --server <dir>            convert in a shared converter process kept warm in",
        "                            <dir> (started on demand, exits when idle), so a batch",
        "                            of conversions pays JVM warm-up once; same output,",
        "                            exit code and files as converting here",
        "  --synthetic-prefix <s>    prefix for synthetic members (default: dex2jvm)",
        "  --redirect-unsafe <cls>   route sun.misc.Unsafe calls to static methods on",
        "                            <cls> (internal name, e.g. com/example/MyUnsafe)",
        "  --redirect-exit <cls>     route System.exit / Runtime.exit / Runtime.halt to",
        "                            static methods on <cls>",
        "  --redirect-native-load <cls>  route System.mapLibraryName / System.load /",
        "                            Runtime.load to static methods on <cls>",
        "  -h, --help                show this help",
        "",
        "exit: 0 ok, 1 bad input, 2 usage, 3 no dex in input, 4 conversion failed");

    public static void main(String[] args) {
        Job job;
        try {
            job = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("dex2jvm: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        if (job == null) {
            System.out.println(USAGE);
            System.exit(0);
            return;
        }
        if (job.serve != null) {
            System.exit(ConvertServer.serve(Paths.get(job.serve)));
            return;
        }
        if (job.server != null) {
            System.exit(ConvertServer.submit(job, args));
            return;
        }
        System.exit(runJob(job));
    }

    /** One conversion as the command line describes it. */
    static final class Job {
        Path in, out;
        boolean libraryGiven, noLibrary;
        /** --server: hand the conversion to the shared converter in this dir. */
        String server;
        /** --serve: BE the shared converter for this dir (started by --server). */
        String serve;
    }

    /**
     * Parses the command line into a {@link Job}, setting {@link Options} from
     * its flags as a side effect. Null means "print the help". Throws
     * IllegalArgumentException on a usage error.
     */
    static Job parseArgs(String[] args) {
        Job job = new Job();
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help":
                    return null;
                case "--classpath":
                    Options.hierarchyLoader = loaderFor(value(args, ++i, a));
                    job.libraryGiven = true;
                    break;
                case "--no-library":
                    job.noLibrary = true;
                    break;
                case "--parsed-marker":
                    Options.parsedMarker = true;
                    break;
                case "--threads":
                    Options.threads = Integer.parseInt(value(args, ++i, a));
                    break;
                case "--synthetic-prefix":
                    Options.syntheticPrefix = value(args, ++i, a);
                    break;
                case "--redirect-unsafe":
                    Options.unsafeRedirect = value(args, ++i, a);
                    break;
                case "--redirect-native-load":
                    Options.nativeLoadRedirect = value(args, ++i, a);
                    break;
                case "--redirect-exit":
                    Options.exitRedirect = value(args, ++i, a);
                    break;
                case "--server":
                    job.server = value(args, ++i, a);
                    break;
                case "--serve":
                    job.serve = value(args, ++i, a);
                    break;
                default:
                    if (a.startsWith("-") && a.length() > 1) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    pos.add(a);
            }
        }
        if (job.serve != null) {
            if (!pos.isEmpty() || job.server != null) {
                throw new IllegalArgumentException("--serve takes no other arguments");
            }
            return job;
        }
        if (pos.size() != 2) throw new IllegalArgumentException("expected <input> <out.jar>");
        job.in = Paths.get(pos.get(0));
        job.out = Paths.get(pos.get(1));
        return job;
    }

    /**
     * Picks the library for {@code job} (see {@link #chooseLibrary}) unless the
     * command line already did.
     */
    static void applyLibrary(Job job) {
        if (job.noLibrary || "none".equals(Options.library)) {
            Options.hierarchyLoader = ClassLoader.getPlatformClassLoader();
            System.err.println("[" + Options.logTag + "] library: none (--no-library); framework types merge to java/lang/Object");
        } else if (!job.libraryGiven && !"classloader".equals(Options.library)) {
            chooseLibrary(job.in);
        }
    }

    /**
     * Phase 1 of a job: PARSE. Container layout + every DEX's header and class
     * table (DexConverter.open parses each image eagerly). Anything that
     * escapes here means the INPUT cannot be read as a dex set.
     *
     * @param exit receives the exit code when the parse fails
     * @return the open session, or null with {@code exit[0]} set
     */
    static DexConverter.Session parse(Job job, int[] exit) {
        try {
            List<byte[]> dexes = readDexesChecked(job.in);
            return DexConverter.open(dexes.toArray(new byte[0][]));
        } catch (NoDexException e) {
            System.err.println("[" + Options.logTag + "] NO CODE: " + e.getMessage());
            try { Files.deleteIfExists(job.out); } catch (IOException ignored) {}
            exit[0] = EXIT_NO_DEX;
        } catch (OutOfMemoryError oom) {
            // Running out of heap while READING says nothing about whether the
            // dex is valid, so it is a converter-side failure, not bad input.
            System.err.println("[" + Options.logTag + "] CONVERT FAILED (out of memory"
                    + " while reading the dex set; the input is not known to"
                    + " be bad; try a larger -Xmx): " + oom);
            try { Files.deleteIfExists(job.out); } catch (IOException ignored) {}
            exit[0] = EXIT_CONVERT_FAILED;
        } catch (Throwable t) {
            System.err.println("[" + Options.logTag + "] INVALID INPUT (the container/DEX"
                    + " cannot be parsed): " + t);
            t.printStackTrace();
            try { Files.deleteIfExists(job.out); } catch (IOException ignored) {}
            exit[0] = 1;
        }
        return null;
    }

    /** The whole of one conversion, start to exit code. Never calls System.exit. */
    static int runJob(Job job) {
        long t0 = System.currentTimeMillis();
        applyLibrary(job);
        int[] exit = { 0 };
        DexConverter.Session session = parse(job, exit);
        if (session == null) return exit[0];

        // The parse verdict is now known. A caller polling for the marker can
        // stop treating the input as possibly invalid while the conversion
        // itself is still running. Advisory: without it the caller just waits
        // for the exit code. Removed on every exit path below; a SIGKILL leaves
        // it behind for the caller to clean up.
        Path parsedMarker = Paths.get(job.out + ".parsed");
        if (Options.parsedMarker) {
            try { Files.createFile(parsedMarker); } catch (IOException ignored) {}
        }
        try {
            return convertParsed(session, job.out, t0);
        } finally {
            try { Files.deleteIfExists(parsedMarker); } catch (IOException ignored) {}
        }
    }

    /**
     * Phase 2 of a job: CONVERT + write the jar. The input is proven readable
     * by now, so an escape here is the converter's, and gets its own code.
     */
    static int convertParsed(DexConverter.Session session, Path out, long t0) {
        try {
            int n = convert(session, out);
            System.err.printf("[" + Options.logTag + "] %d classes -> %s in %d ms%n",
                    n, out, System.currentTimeMillis() - t0);
            int uc = DexConverter.foreignWriteClasses.getAndSet(0);
            int uf = DexConverter.foreignWriteFields.getAndSet(0);
            if (uc > 0) {
                System.err.printf("[" + Options.logTag + "] ACC_FINAL cleared on %d fields"
                        + " in %d classes: written outside their initializer, which the JVM"
                        + " refuses from class version 53 (DEX2JVM_UNFINAL_DEBUG=1 lists them)%n",
                        uf, uc);
            }
            if (n > 0) return 0;
            // Parsed, but produced nothing: every class failed to translate
            // (or the dex declares zero classes).
            System.err.println("[" + Options.logTag + "] FAILED: the dex set parsed but 0"
                    + " classes could be converted (see the per-class errors"
                    + " above); no usable jar");
            try { Files.deleteIfExists(out); } catch (IOException ignored) {}
            return 1;
        } catch (Throwable t) {
            System.err.println("[" + Options.logTag + "] CONVERT FAILED (the container"
                    + " parsed completely; this failure is the converter's,"
                    + " not the input's): " + t);
            t.printStackTrace();
            try { Files.deleteIfExists(out); } catch (IOException ignored) {}
            return EXIT_CONVERT_FAILED;
        }
    }

    /**
     * Picks the class-hierarchy library when --classpath was not given: the
     * installed SDK platform the app was built against (exact, from Google's
     * android.jar), else the index bundled in this jar (AOSP's public API
     * hierarchy; see ApiIndexLoader). Either one makes framework-type merges
     * precise; without one, stack-map frames widen framework types to
     * java/lang/Object, which HotSpot's verifier can reject.
     */
    private static void chooseLibrary(Path in) {
        AndroidSdk.Choice sdk = AndroidSdk.choose(in);
        if (sdk != null) {
            Options.hierarchyLoader = loaderFor(sdk.jar.toString());
            System.err.println("[" + Options.logTag + "] library: " + sdk.jar + " (" + sdk.reason + ")");
            return;
        }
        ApiIndexLoader idx = ApiIndexLoader.bundled();
        if (idx != null) {
            Options.hierarchyLoader = idx;
            System.err.println("[" + Options.logTag + "] library: " + idx.description + " (" + idx.size()
                    + " classes; no Android SDK found)");
            return;
        }
        System.err.println("[" + Options.logTag + "] library: none found; pass --classpath <android.jar>"
                + " for more precise output");
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[i];
    }

    /** A loader over a classpath string, parented to the platform loader so the
     *  JDK's own classes still resolve but the converter's do not leak in. */
    private static ClassLoader loaderFor(String classpath) {
        List<URL> urls = new ArrayList<>();
        for (String part : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (part.isEmpty()) continue;
            File f = new File(part);
            if (!f.exists()) throw new IllegalArgumentException("--classpath entry not found: " + part);
            try {
                urls.add(f.toURI().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("bad --classpath entry " + part);
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /**
     * Convert {@code in} (an apk, or a bare .dex) into a jar at {@code out}.
     *
     * @return the number of classes written; 0 means nothing usable was
     *         produced, which is what callers treat as failure.
     */
    public static int run(Path in, Path out) throws IOException {
        return run(readDexesChecked(in), out);
    }

    /** {@link #readDexes}, with the empty result classified: {@link
     *  NoDexException} for a genuinely codeless app, a plain IOException for a
     *  container we could see code in but not read (an .aab, an unrecognised
     *  layout). Conflating those is dangerous in opposite directions -- see
     *  the messages. */
    static List<byte[]> readDexesChecked(Path in) throws IOException {
        List<byte[]> dexes = readDexes(in);
        if (dexes.isEmpty()) {
            String unreachable = unreachableCodeHint(in);
            if (unreachable != null) {
                throw new IOException("no classes*.dex reachable in " + in
                        + ", but it does carry code: " + unreachable
                        + ". The container layout was not recognised"
                        + " (see Main.selectBundleApks / Main.collectDexes).");
            }
            throw new NoDexException(in + " contains no classes*.dex anywhere."
                    + " A NativeActivity app declaring android:hasCode=\"false\""
                    + " legitimately has none.");
        }
        return dexes;
    }

    /**
     * Convert a set of already-in-memory DEX images into one jar at
     * {@code out}, as a SINGLE converter session.
     *
     * <p>Exposed for embedders that convert DEX images they hold in memory
     * (for example to back {@code InMemoryDexClassLoader(ByteBuffer[])}).
     * ART gives that constructor ONE class table over all the buffers, so they
     * have to be opened together:
     * a session sees every class in the set, which is what lets
     * ClassHierarchyOracle answer supertype questions about a class defined in
     * a sibling buffer instead of collapsing the merge to java/lang/Object.
     * Converting each buffer in its own session lost exactly that.
     *
     * @return the number of classes written
     */
    public static int run(List<byte[]> dexes, Path out) throws IOException {
        return convert(DexConverter.open(dexes.toArray(new byte[0][])), out);
    }

    /** {@link #run(List, Path)}, also copying the session's per-class
     *  translation failures (class name -> reason) into {@code errorsOut}.
     *
     *  <p>A class that fails is left OUT of the jar and the conversion carries
     *  on (see DexConverter's header), so a nonzero return does not mean the
     *  jar is complete. A caller that keeps the jar beyond this process -- for
     *  example a cache of converted dynamically loaded DEX -- needs to know,
     *  because the failure may be transient: the parallel drain catches any
     *  worker Throwable, OutOfMemoryError included. Persisting such a jar would
     *  turn one bad moment into a permanent ClassNotFoundException. */
    public static int run(List<byte[]> dexes, Path out, Map<String, String> errorsOut)
            throws IOException {
        return convert(DexConverter.open(dexes.toArray(new byte[0][])), out, errorsOut);
    }

    /** The CONVERT half of {@link #run(List, Path)}, taking an already-opened
     *  (= already-parsed) session. Split out so main() can tell a parse
     *  failure (exit 1, the caller's invalid-APK signal) from a conversion
     *  failure (EXIT_CONVERT_FAILED: the input parsed, so a caller can still
     *  recover by converting classes one at a time through
     *  DexConverter.Session.classBytes, the lazy path). */
    static int convert(DexConverter.Session session, Path out) throws IOException {
        return convert(session, out, null);
    }

    /** {@link #convert(DexConverter.Session, Path)}, copying the per-class
     *  failures into {@code errorsOut} when it is non-null. */
    static int convert(DexConverter.Session session, Path out, Map<String, String> errorsOut)
            throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        int[] count = { 0 };
        // Written to a sibling temp name and RENAMED into place: `out`
        // therefore either does not exist or is a COMPLETE jar, never a
        // truncated one. That guarantee is load-bearing for a caller that
        // runs the conversion in the background and decides "is the jar
        // ready?" purely from this file's existence: without it a reader could
        // open a half-written zip, and URLClassLoader treats an unreadable jar
        // as an empty classpath rather than an error.
        Path tmp = Paths.get(out + ".tmp");
        try {
            try (OutputStream fos = Files.newOutputStream(tmp);
                 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos, 1 << 16))) {
                session.forEach((name, bytes) -> {
                    try {
                        writeEntry(zos, name + ".class", bytes);
                        count[0]++;
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                       java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }

        Map<String, String> errors = session.errors();
        if (errorsOut != null) errorsOut.putAll(errors);
        if (!errors.isEmpty()) {
            System.err.printf("[" + Options.logTag + "] %d classes failed to translate:%n", errors.size());
            int shown = 0;
            for (Map.Entry<String, String> e : errors.entrySet()) {
                if (shown++ == 20) {
                    System.err.printf("[" + Options.logTag + "]   ... and %d more%n", errors.size() - 20);
                    break;
                }
                System.err.println("[" + Options.logTag + "]   " + e.getKey() + ": " + e.getValue());
            }
        }
        return count[0];
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        if (data.length < DEFLATE_THRESHOLD) {
            // STORED demands the size and CRC up front.
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Every DEX an installed copy of {@code in} would expose, in the order that
     * makes first-definition-wins dedup match what the app sees on a device.
     *
     * <p>Accepts a bare .dex, a plain .apk, and every SPLIT BUNDLE container a
     * user can realistically have on disk (.apkm / .xapk / .apks). The
     * bundle rules live in {@link #selectBundleApks}; the multidex rules in
     * {@link #collectDexes}. Any installer that merges the same containers must
     * apply the same rules: when they disagree, an app converts to a
     * different class set here than it has when installed on a device.
     */
    public static List<byte[]> readDexes(Path in) throws IOException {
        byte[] head = new byte[8];
        try (java.io.InputStream is = Files.newInputStream(in)) {
            if (is.read(head) == 8 && head[0] == 'd' && head[1] == 'e'
                    && head[2] == 'x' && head[3] == '\n') {
                return List.of(Files.readAllBytes(in));
            }
        }
        // ZipFile's own failure message is the bare "error in opening zip
        // file", with no path and no hint at what was actually handed over.
        // This is the FIRST thing that runs on a file a user dropped in, so it
        // has to say which file and what shape it was expecting.
        if (!Files.isReadable(in)) {
            throw new IOException("cannot read " + in);
        }
        try (ZipFile zip = openZip(in)) {
            List<byte[]> out = collectDexes(zip);
            if (!out.isEmpty()) {
                // An INSTALLED split set: the input is a base.apk laid out the
                // way a device installs it,
                // <root>/data/app/~~<nonce>/<pkg>-<nonce>/base.apk, and the
                // splits installed beside it are its siblings, split_*.apk. A
                // dynamic feature module among them carries its own
                // classes*.dex, which is loadable like the base's on a device
                // -- the app sees ONE classpath. Base first, then the splits
                // sorted by name (Android's package parser keeps split names
                // sorted too), so first-definition-wins picks the same class
                // the installed app sees. Config splits and asset packs carry
                // no dex and add nothing. A bare .apk that is not named base.apk, or has no
                // such siblings, is exactly the plain-apk case it always was.
                if ("base.apk".equals(in.getFileName().toString()) && in.getParent() != null) {
                    List<Path> splits = new ArrayList<>();
                    try (java.util.stream.Stream<Path> ls = Files.list(in.getParent())) {
                        ls.filter(p -> {
                                String n = p.getFileName().toString();
                                return n.startsWith("split_") && n.endsWith(".apk")
                                    && !n.contains(".installing.") && Files.isRegularFile(p);
                            })
                          .sorted()
                          .forEach(splits::add);
                    } catch (IOException ignored) {
                        // An unreadable directory listing is the plain-apk case.
                    }
                    for (Path split : splits) {
                        try (ZipFile inner = new ZipFile(split.toFile())) {
                            out.addAll(collectDexes(inner));
                        } catch (IOException ignored) {
                            // A split that is not a readable zip is not fatal;
                            // the ones that carry code still convert.
                        }
                    }
                }
                return out;
            }
            // No top-level classes.dex: a bundle container. Merge exactly the
            // apks a device would install, or an app with a split layout looks
            // like an app with no code at all.
            for (ZipEntry e : selectBundleApks(zip)) {
                Path tmp = Files.createTempFile("dex2jvm-split-", ".apk");
                try {
                    try (java.io.InputStream is = zip.getInputStream(e)) {
                        Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    try (ZipFile inner = new ZipFile(tmp.toFile())) {
                        out.addAll(collectDexes(inner));
                    } catch (IOException ignored) {
                        // A split that is not a readable zip is not fatal; the
                        // ones that carry code still convert.
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return out;
        }
    }

    /**
     * A short description of code we can SEE in {@code in} but could not read
     * as an installable dex set, or null when the input is genuinely codeless.
     *
     * <p>The one shape this really catches is an {@code .aab} -- the Android
     * App Bundle BUILD artifact, which users do have and do try to drop in. It
     * is a zip of MODULE directories ({@code base/dex/classes.dex},
     * {@code base/manifest/AndroidManifest.xml}, {@code BundleConfig.pb}), not
     * a zip of apks, so nothing in readDexes matches and it looks exactly like
     * a pure-native app. It is not installable as-is on a device either -- its
     * manifest and resource table are protobuf, so bundletool has to build apks
     * out of it first -- and pretending otherwise would take far more than a
     * dex reader. Naming it is the useful thing we can do here.
     */
    /** ZipFile over {@code in}, with the path in the failure message. */
    private static ZipFile openZip(Path in) throws IOException {
        try {
            return new ZipFile(in.toFile());
        } catch (IOException e) {
            throw new IOException(in + " is neither a .dex nor a readable zip"
                    + " (.apk / .apkm / .xapk / .apks): " + e.getMessage(), e);
        }
    }

    private static String unreachableCodeHint(Path in) {
        try (ZipFile zip = new ZipFile(in.toFile())) {
            String moduleDex = null;
            for (ZipEntry e : Collections.list(zip.entries())) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.equals("BundleConfig.pb")) {
                    return "BundleConfig.pb -- this is an .aab (an App Bundle"
                         + " BUILD artifact). Convert it with"
                         + " `bundletool build-apks` and drop the .apks instead";
                }
                // <module>/dex/classes*.dex is the .aab module layout. Checked
                // separately because BundleConfig.pb is optional.
                if (moduleDex == null && n.endsWith(".dex")) {
                    String[] seg = n.split("/");
                    if (seg.length == 3 && seg[1].equals("dex")
                            && seg[2].startsWith("classes")) {
                        moduleDex = n;
                    }
                }
            }
            if (moduleDex != null) {
                return "a module-layout dex at " + moduleDex
                     + " -- this looks like an .aab. Convert it with"
                     + " `bundletool build-apks` and drop the .apks instead";
            }
            // Deliberately NOT flagged: a .dex under assets/ (packers ship one
            // there and load it at runtime through DexClassLoader, which is a
            // supported path, not a gap), and a bundle whose nested apks carry
            // no dex (a pure-native game shipped as an .apkm is exactly that).
            // Both are legitimately codeless AT INSTALL, and calling them a
            // failure would abort a launch that should proceed.
        } catch (IOException e) {
            return null;                 // not a zip at all; nothing to report
        }
        return null;
    }

    /**
     * The nested apks of a bundle container that an install would actually
     * apply, base first.
     *
     * <p>Two vendor layouts exist and they are NOT interchangeable.
     *
     * <p><b>Root layout</b> (APKMirror .apkm: {@code base.apk} +
     * {@code split_config.<q>.apk}; APKPure .xapk: {@code <package>.apk} +
     * {@code config.<q>.apk} + a {@code manifest.json}). Every apk sits at the
     * zip root and every one of them is installed together.
     *
     * <p><b>bundletool .apks</b>, whose paths are assigned by
     * {@code ApkPathManager.getApkPath} and mean specific things:
     * <pre>
     *   splits/&lt;module&gt;-master.apk      the split set a modern device installs
     *   splits/&lt;module&gt;-&lt;targeting&gt;.apk
     *   asset-slices/&lt;module&gt;-*.apk     Play Asset Delivery, installed alongside
     *   system/system.apk               --mode=system: the BASE MASTER, with the
     *                                   remaining splits still under splits/
     *   standalones/standalone-&lt;t&gt;.apk  a WHOLE-APP alternative for pre-L devices
     *   instant/instant-&lt;module&gt;-*.apk  a WHOLE-APP alternative for instant runs
     *   archive/archive.apk             a whole-app stub for app archiving
     *   universal.apk                   --mode=universal, the only apk present
     * </pre>
     * The whole-app alternatives are mutually exclusive with the split set, not
     * additive: a device installs one family or the other. Merging them all
     * re-reads and re-converts the entire app once per standalone -- measured
     * on a 3-standalone fixture, 6 dexes and 6.8 MB read where 2 dexes and
     * 1.6 MB are correct -- and lets a standalone's copy of a class win the
     * first-definition race against the split copy the device would have used.
     *
     * <p>{@code system/} is the exception that is NOT an alternative:
     * getApkPath sends only the base module's MASTER split there and leaves
     * every other split under {@code splits/}, so a system-mode bundle needs
     * both directories or it loses the one apk carrying the app's dex.
     */
    static List<ZipEntry> selectBundleApks(ZipFile zip) {
        List<ZipEntry> all = new ArrayList<>();
        for (ZipEntry e : Collections.list(zip.entries())) {
            if (!e.isDirectory() && e.getName().endsWith(".apk")) all.add(e);
        }

        List<ZipEntry> splits = under(all, "splits/");
        List<ZipEntry> system = under(all, "system/");
        if (!splits.isEmpty() || !system.isEmpty()) {
            List<ZipEntry> sel = new ArrayList<>(system);
            sel.addAll(splits);
            sel.addAll(under(all, "asset-slices/"));
            // Whichever apk is the base module's MASTER carries the app's own
            // dex; every other split layers config data or feature code on top.
            sel.sort(Comparator.comparingInt(
                    (ZipEntry e) -> e.getName().startsWith("system/") ? 0
                                  : baseName(e.getName()).equals("base-master.apk") ? 1
                                  : baseName(e.getName()).startsWith("base-") ? 2 : 3)
                    .thenComparing(ZipEntry::getName));
            return sel;
        }

        for (ZipEntry e : all) {
            if (e.getName().equals("universal.apk")) return List.of(e);
        }

        // No split set: the container holds only whole-app alternatives, so
        // exactly ONE of them is the app. Largest wins for the same reason the
        // root layout uses it below -- it is the one carrying the full asset
        // set rather than a single-config subset.
        List<ZipEntry> whole = under(all, "standalones/");
        if (whole.isEmpty()) whole = under(all, "instant/");
        if (whole.isEmpty()) whole = under(all, "archive/");
        if (!whole.isEmpty()) return List.of(largest(whole));

        // Root layout. Identify the base deterministically, because the base
        // goes first and so decides every first-definition winner: the XAPK
        // manifest.json if it names one, then a literal base.apk, then the
        // largest entry (the base carries the dex plus the shared assets, so it
        // dwarfs every config split -- Plague Inc is 149 MB against 4 MB).
        String named = xapkManifestBase(zip);
        ZipEntry base = null;
        for (ZipEntry e : all) {
            if (named != null && e.getName().equals(named)) { base = e; break; }
        }
        if (base == null) {
            for (ZipEntry e : all) {
                if (baseName(e.getName()).equals("base.apk")) { base = e; break; }
            }
        }
        if (base == null && !all.isEmpty()) base = largest(all);
        final ZipEntry chosen = base;
        all.sort(Comparator.comparingInt((ZipEntry e) -> e == chosen ? 0 : 1)
                .thenComparing(ZipEntry::getName));
        return all;
    }

    private static List<ZipEntry> under(List<ZipEntry> all, String dir) {
        List<ZipEntry> out = new ArrayList<>();
        for (ZipEntry e : all) if (e.getName().startsWith(dir)) out.add(e);
        return out;
    }

    private static ZipEntry largest(List<ZipEntry> es) {
        ZipEntry best = es.get(0);
        for (ZipEntry e : es) if (e.getSize() > best.getSize()) best = e;
        return best;
    }

    /**
     * The apk an APKPure XAPK's manifest.json names as the base, or null.
     *
     * <p>The file is
     * {@code {"package_name":"...","split_apks":[{"file":"x.apk","id":"base"},...]}}.
     * Deliberately a scan rather than a JSON parse -- one field is not worth a
     * parser. It walks {@code {...}} objects and is order-independent within
     * one, so the base is named the same way however the generator ordered the
     * keys; naming a different base would change the first-definition winner.
     */
    private static String xapkManifestBase(ZipFile zip) {
        ZipEntry m = zip.getEntry("manifest.json");
        if (m == null) return null;
        String json;
        try (java.io.InputStream is = zip.getInputStream(m)) {
            json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        int arr = json.indexOf("\"split_apks\"");
        if (arr < 0) return null;
        int open = json.indexOf('[', arr);
        if (open < 0) return null;
        // split_apks holds a flat array of flat objects, so the first ']'
        // after it closes the array.
        int close = json.indexOf(']', open);
        if (close < 0) close = json.length();
        int pos = open;
        while (true) {
            int obj = json.indexOf('{', pos);
            if (obj < 0 || obj > close) return null;
            int end = json.indexOf('}', obj);
            if (end < 0 || end > close) return null;
            if ("base".equals(jsonStringValue(json, "id", obj, end))) {
                return jsonStringValue(json, "file", obj, end);
            }
            pos = end + 1;
        }
    }

    /** The string value of {@code "key": "value"} in json[from, until), or null.
     *  Handles \" escapes and nothing fancier, which is all a
     *  machine-generated manifest.json needs. */
    private static String jsonStringValue(String json, String key, int from, int until) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle, from);
        if (k < 0 || k >= until) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0 || colon >= until) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0 || q1 >= until) return null;
        StringBuilder out = new StringBuilder();
        for (int i = q1 + 1; i < until; i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < until) { out.append(json.charAt(++i)); continue; }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return null;
    }

    /**
     * Every classes*.dex of one archive, in the order ART would open them.
     *
     * <p>ART does not glob. {@code DexFileLoader::GetMultiDexClassesDexName(i)}
     * is {@code "classes.dex"} for i==0 and {@code "classes"+(i+1)+".dex"}
     * after that, and {@code OpenAllDexFilesFromZip} walks i upward until an
     * entry is MISSING, then stops. So the installed app sees a consecutive run
     * and nothing else: {@code classes0.dex}, {@code classes1.dex},
     * {@code classesFoo.dex} and anything past a gap are never opened.
     *
     * <p>A glob got that wrong in two ways that matter. {@code classes0.dex}
     * sorted to ordinal 0, ahead of {@code classes.dex}, so a decoy dex won the
     * first-definition race and SHADOWED the app's real classes -- silently,
     * with the right class count. And {@code classes1.dex} shares ordinal 1
     * with {@code classes.dex}, so which one won came down to zip order.
     *
     * <p>We take the ART run first, then APPEND any leftover classes*.dex
     * instead of dropping them: a repackaged apk that ART would refuse to load
     * fully is still better served by extra classes than by missing ones, and
     * appending means they can never outrank a real one. Double-digit names are
     * ordered by {@link #dexOrdinal}, not lexicographically -- "classes10.dex"
     * sorts before "classes2.dex" as text, and real apps ship up to
     * classes14.dex.
     */
    private static List<byte[]> collectDexes(ZipFile zip) throws IOException {
        Map<String, ZipEntry> byName = new java.util.HashMap<>();
        for (ZipEntry e : Collections.list(zip.entries())) {
            String n = e.getName();
            if (n.startsWith("classes") && n.endsWith(".dex") && n.indexOf('/') < 0) {
                byName.put(n, e);
            }
        }
        List<ZipEntry> ordered = new ArrayList<>();
        for (int i = 0; ; i++) {
            ZipEntry e = byName.remove(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex");
            if (e == null) break;
            ordered.add(e);
        }
        if (!byName.isEmpty()) {
            List<ZipEntry> strays = new ArrayList<>(byName.values());
            strays.sort(Comparator.comparingInt((ZipEntry e) -> dexOrdinal(e.getName()))
                    .thenComparing(ZipEntry::getName));
            StringBuilder names = new StringBuilder();
            for (ZipEntry e : strays) names.append(' ').append(e.getName());
            System.err.println("[" + Options.logTag + "] note:" + names
                    + " are outside the classes.dex/classes2.dex/... run ART opens;"
                    + " converting them last so they cannot shadow a real class");
            ordered.addAll(strays);
        }
        List<byte[]> out = new ArrayList<>();
        for (ZipEntry e : ordered) {
            try (java.io.InputStream is = zip.getInputStream(e)) {
                out.add(is.readAllBytes());
            }
        }
        return out;
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** "classes.dex" -> 1, "classes2.dex" -> 2, ... */
    static int dexOrdinal(String name) {
        String mid = name.substring("classes".length(), name.length() - ".dex".length());
        if (mid.isEmpty()) return 1;
        try {
            return Integer.parseInt(mid);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    static File toFile(Path p) { return p.toFile(); }
}
