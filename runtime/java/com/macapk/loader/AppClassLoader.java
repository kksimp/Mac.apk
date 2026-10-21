// AppClassLoader -- URLClassLoader that hands the APK its native
// library paths through findLibrary().
//
// Resolution order on a System.loadLibrary("foo") call:
//
//   1. Already-translated cache: <app_dir>/lib_native/libfoo.dylib.
//      Translator runs once at first load; subsequent boots reuse the
//      cached dylib. t3-mmap (2026-07-24): a Stage-6 arm64 wrapper no
//      longer embeds its source .so's bytes (arm64_pack.cpp / "Lever 3"
//      in arm64_native.cpp) -- it mmaps/pread's the .so back off the
//      installed lib dir at load time, so a "cache hit" here also requires
//      that source .so to still exist (see the cache-hit check below);
//      it's normally guaranteed by main.cpp's fingerprint-gated data-dir
//      rotation, which keeps the wrapper cache and the installed .so tree
//      invalidating together.
//
//   2. Translate from the app's INSTALLED library,
//      <root>/data/app/~~<nonce>/<pkg>-<nonce>/lib/<isa>/libfoo.so (the
//      directory ApplicationInfo.nativeLibraryDir names; v1.0.1588, Phase 4
//      of docs/disk-footprint-and-space-efficiency_2026-09-19.md -- before
//      that, <app_dir>/_apkt/lib/<abi>/). We invoke the macapk-translator
//      binary in a subprocess with --shim-dir set so the output dylib's
//      rpath finds libmacapk-bionic-shim.dylib next to the runtime binary at
//      execution time.
//
//   3. Fall back to the no-op stub dylib (legacy v1.0 behavior). dlopen
//      reports success and the app's <clinit> completes; the first JNI
//      call into the (missing) native methods throws
//      UnsatisfiedLinkError, which ActivityHost.runTolerant catches.
//
// Architecture preference: arm64-v8a > armeabi-v7a > armeabi. The
// translator auto-detects ELF class and dispatches to the arm32
// lifter pipeline or to Stage 6 (arm64 native, no instruction
// translation) accordingly.
//
// Diagnostic-only override: MACAPK_FORCE_ABI=<abi> pins the
// selection to that ABI's instruction-set dir (the runtime installs only
// that one for the run) and gives translated dylibs an "-<abi>" cache
// suffix so back-to-back A/B runs across translation paths don't clobber
// each other. See forcedAbi() for the full rationale (env var vs CLI flag,
// validation behavior).
package com.macapk.loader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

// Extends dalvik.system.BaseDexClassLoader (which is itself a URLClassLoader) so
// that the app-visible getClassLoader() IS-A BaseDexClassLoader -- matching real
// Android, where the app loader is a PathClassLoader extends BaseDexClassLoader.
// Native-lib games (SmashHit via the GameActivity SDK) cast the loader to
// BaseDexClassLoader to read findLibrary()/getLdLibraryPath().
// ⚠ Extends PathClassLoader, not BaseDexClassLoader directly. On a device the
// app's loader IS a dalvik.system.PathClassLoader, and real code branches on
// that exact type -- MultiDex.install, SoLoader/ReLinker, hot-patch frameworks
// (Tinker/Sophix), and integrity checks that read the loader's dex path list.
// Extending BaseDexClassLoader made every one of those `instanceof
// PathClassLoader` tests fail where Android passes, while the
// `instanceof BaseDexClassLoader` cast that SmashHit/GameActivity performs kept
// working -- which is exactly why the gap survived unnoticed. PathClassLoader
// adds no behaviour over BaseDexClassLoader here, so this is a hierarchy fix,
// not a behaviour change.
public final class AppClassLoader extends dalvik.system.PathClassLoader {
    private static final String TAG = "Mac.apk.AppCl";
    /** Guards the per-class loadClass/findClass info lines (~25K/boot for
     *  Reddit, ~13K for GD Lite). Derived from NativeLog.isLoggable(INFO)
     *  OR'd with an explicit MACAPK_QUIET_CLASSLOAD, so these two hottest
     *  classloading call sites stop building throwaway strings rather than
     *  only having the (already-built) string dropped one layer down inside
     *  NativeLog.i(); see docs/efficiency-classload-logging_2026-07-24.md
     *  Finding 3 for why the sink-side gate alone was not enough.
     *
     *  MACAPK_QUIET_CLASSLOAD IS NOW THE PRIMARY PATH, not a legacy escape
     *  hatch. The launcher UI sets it explicitly and keeps MACAPK_LOG_LEVEL at
     *  AOSP's INFO floor (4) instead of the WARN floor (5) it used to pass.
     *  Measured 2026-08-19 on a two-install Aurora Store session, this pair is
     *  15,977 of 16,733 INFO lines -- 95.5% -- so it is the ONLY reason a quiet
     *  default was ever wanted, and gating it HERE means no spawner has to
     *  suppress the INFO severity, which used to take the entire
     *  PackageInstaller install trail with it and left a real install
     *  undiagnosable from the log. Warnings/errors are unaffected either way. */
    private static final boolean QUIET_CLASSLOAD =
        !NativeLog.isLoggable(NativeLog.INFO)
        || "1".equals(System.getenv("MACAPK_QUIET_CLASSLOAD"));

    /** v1.0.477: CHILD-FIRST classloading for the third-party
     *  GMS/Firebase/Play/Billing families. v1.0.493: DEFAULT-ON (opt out with
     *  MACAPK_GMS_CHILD_FIRST=0/false) after the with-flag protected sweep
     *  validated it -- flappy/calc/crossy/F-Droid/wikipedia/NiA all render at
     *  exact baseline with the flag on (cocos OpenGD is GMS-free, unaffected),
     *  AND it is REQUIRED for Reddit's welcome to render (its `nxl.<clinit>`
     *  needs the app's own 4-arg Status ctor, which our shadow cannot declare).
     *  See {@link #GMS_CHILD_FIRST_PREFIXES} + {@link #isGmsChildFirstFamily}.
     *
     *  Background: Mac.apk ships emulator-grade honest-unavailable SHADOWS for
     *  com.google.android.gms.* / firebase / play-core / billing under
     *  runtime/java/com/google/** (loaded by the PARENT = Boot's system
     *  loader). Because AppClassLoader is parent-first, those shadows OVERRIDE
     *  an app's OWN bundled R8'd GMS copy. That is correct for apps that DON'T
     *  bundle the lib (NoClassDefFound -> a clean shim), but REGRESSES apps that
     *  DO bundle it: their code constructs GMS base classes via internal ctors
     *  whose param types are app-private/obfuscated (e.g.
     *  Status(int,String,PendingIntent,asj), Api(int,Object,Object)) that we
     *  CANNOT replicate (the JVM resolves ctors by exact descriptor) -> a caught
     *  NoSuchMethodError (calc ArrowIndicator, crossy initialize-shortcuts,
     *  wikipedia NavTab) or, for Reddit, an ExceptionInInitializerError that
     *  poisons the startup-auth chain -> blank UI. The faithful fix (a real
     *  no-GApps device runs the app's OWN bundled GMS, backend unreachable) is
     *  to make the family child-first: the app's bundled copy wins; our shadow
     *  only rescues a class the app did NOT bundle. Full design + tradeoff:
     *  docs/gms-shadow-classloader-contract_2026-06-21.md. */
    /*  Package-private, not private, ON PURPOSE: Boot.installAutoInitProviders'
     *  Firebase default-app rescue only makes sense under child-first and MUST
     *  agree with this flag. It used to re-derive the answer with its own
     *  expression that required MACAPK_GMS_CHILD_FIRST to be *explicitly* "1"/
     *  "true", so with the variable UNSET the two disagreed: child-first was ON
     *  here (default-ON since v1.0.493) while the rescue silently did NOT run.
     *  One constant, one answer -- do not re-inline this test anywhere. */
    static final boolean GMS_CHILD_FIRST =
        !("0".equals(System.getenv("MACAPK_GMS_CHILD_FIRST"))
          || "false".equalsIgnoreCase(System.getenv("MACAPK_GMS_CHILD_FIRST")));

    /** Absolute path to the stub dylib next to the runtime binary. */
    private final String stubLibraryPath;
    /** Per-app CODE dir (where _apkt/, lib_native/, etc. live). The native
     *  libraries are NOT here since v1.0.1588 -- see nativeLibDir(). */
    private final File   appDir;
    /** Path to macapk-translator binary; null disables translation. */
    private final File   translatorBin;
    /** The installed base.apk -- the resource-of-last-resort (see
     *  findResource below) and what LazyDexSource is started on. Null when
     *  the caller has no APK path. */
    private final File   apkFile;
    /** v1.0.1586: the whole INSTALLED SPLIT SET, base first, then the
     *  split_*.apk siblings sorted by name (InstalledSplits.of). On a device
     *  each of these is a DexPathList element (LoadedApk.makePaths:434-438,
     *  android11-release: sourceDir then every splitSourceDirs entry), so a
     *  resource lookup that misses the classpath consults every member in
     *  that order. Empty when apkFile is null; [apkFile] for a plain apk or
     *  the legacy `_merged.apk`. */
    private final java.util.List<File> apkSet;
    /** v1.0.848: true only on the install-time (--extract-only) path.
     *  There is no app window during an install, and the ARM32
     *  translation splash (NativeCocoa.showTranslationOverlay) would
     *  materialize UI mid-install, so translate() skips the overlay
     *  when this is set. Never true during a real boot. */
    private volatile boolean installMode = false;
    /** Lazily-opened zip views of apkSet, parallel to it, kept open for
     *  the process lifetime like Android's DexPathList keeps its ZipFile
     *  handles. A slot stays null until first asked for; a slot whose open
     *  THREW is marked in apkZipFailed so a corrupt member does not re-throw
     *  on every lookup (a member that merely does not exist yet is retried,
     *  since the install path constructs this loader before the files are
     *  necessarily in place). Guarded by `this`. */
    private java.util.zip.ZipFile[] apkZips;
    private boolean[] apkZipFailed;

    public AppClassLoader(URL[] urls, ClassLoader parent,
                          String stubLibraryPath, File appDir,
                          File translatorBin, String apkPath) {
        super(urls, parent);
        UnverifiedClasses.init(urls);
        this.stubLibraryPath = stubLibraryPath;
        this.appDir          = appDir;
        this.translatorBin   = translatorBin;
        this.apkSet          = InstalledSplits.of(apkPath);
        this.apkFile         = apkSet.isEmpty() ? null : apkSet.get(0);
        this.apkZips         = new java.util.zip.ZipFile[apkSet.size()];
        this.apkZipFailed    = new boolean[apkSet.size()];
    }

    // ---- Classpath resources served from the APK zip(s) ----
    //
    // On Android the app loader's DexPathList contains the APK itself --
    // the base AND every split (LoadedApk.makePaths) -- and
    // DexPathList.Element.findResource serves ANY zip entry out of them
    // via a jar: URL (ClassPathURLStreamHandler.getEntryUrlOrNull),
    // walking the elements in order and returning the first hit
    // (DexPathList.java:553-561) or, for findResources, every hit
    // (:569-579). Our URL classpath is the converted classes.jar, which
    // carries only .class files -- so APK-root resources (libGDX/Arc reads
    // "vfxshaders/screenspace.vert" via Class.getResource on Mindustry)
    // were unfindable. Mirror Android: after the normal URL-classpath
    // lookup misses, serve the entry straight from the installed set with
    // a jar: URL, base first.

    @Override
    public URL findResource(String name) {
        URL u = super.findResource(name);
        if (u != null) return u;
        return apkEntryUrl(name);
    }

    @Override
    public java.util.Enumeration<URL> findResources(String name)
            throws IOException {
        java.util.Enumeration<URL> base = super.findResources(name);
        java.util.List<URL> apk = apkEntryUrls(name);
        if (apk.isEmpty()) return base;
        java.util.List<URL> all = new java.util.ArrayList<>();
        while (base.hasMoreElements()) all.add(base.nextElement());
        all.addAll(apk);
        return java.util.Collections.enumeration(all);
    }

    /** jar:file: URL for an entry of the installed set -- the FIRST member,
     *  base first, that carries it -- or null when no member does or none
     *  is available. Existence-checked against the zip directory so a
     *  returned URL always opens. */
    private synchronized URL apkEntryUrl(String name) {
        if (apkFile == null || name == null || name.isEmpty()) return null;
        String entry = name.startsWith("/") ? name.substring(1) : name;
        for (int i = 0; i < apkSet.size(); i++) {
            URL u = apkEntryUrlIn(i, entry);
            if (u != null) return u;
        }
        return null;
    }

    /** Every member's jar:file: URL for an entry, in set order (what
     *  DexPathList.findResources answers: one URL per element that has
     *  the name). Empty when none does. */
    private synchronized java.util.List<URL> apkEntryUrls(String name) {
        if (apkFile == null || name == null || name.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String entry = name.startsWith("/") ? name.substring(1) : name;
        java.util.List<URL> out = new java.util.ArrayList<>(1);
        for (int i = 0; i < apkSet.size(); i++) {
            URL u = apkEntryUrlIn(i, entry);
            if (u != null) out.add(u);
        }
        return out;
    }

    /** The jar: URL of `entry` inside member `i` of the set, or null.
     *  Caller holds `this`. */
    private URL apkEntryUrlIn(int i, String entry) {
        File member = apkSet.get(i);
        try {
            if (apkZips[i] == null) {
                if (apkZipFailed[i] || !member.isFile()) return null;
                try {
                    apkZips[i] = new java.util.zip.ZipFile(member);
                } catch (Throwable open) {
                    apkZipFailed[i] = true;
                    NativeLog.w(TAG, "installed apk " + member
                        + " could not be opened for resources: " + open);
                    return null;
                }
            }
            if (apkZips[i].getEntry(entry) == null) return null;
            java.io.File urlFile = member;
            // DIAGNOSTIC (MACAPK_TEST_APK_URLPATH, default OFF): pairip's VMRunner
            // derives the APK path it hashes into the integrity VM from
            // getClass().getResource(...).toString() (jar:file:<apkFile>!/entry).
            // Point that at a different (symlinked, still-openable) path to test
            // whether the hashed APK-path VALUE steers the crash branch. If it
            // does, a faithful /data/app path is the fix direction; if it does
            // not, the value is not the gate. See the oracle doc. Base only:
            // the probe is about the base's path.
            if (i == 0) {
                String tp = System.getenv("MACAPK_TEST_APK_URLPATH");
                if (tp != null && !tp.isEmpty()) { java.io.File f = new java.io.File(tp); if (f.isFile()) urlFile = f; }
            }
            // Device-faithful resource URL (MACAPK_ANDROID_MAPS_PATHS, default OFF; cont.73-75): pairip's
            // VMRunner.openBaseApk parses getResource("/AndroidManifest.xml").toString() to a path,
            // new ZipFile(path)s it, stores it in VMRunner.apkPath, and the integrity VM FOLDS apkPath
            // (658 folds / 7 VM sites). So the folded path must be the bare device /data/app/... spelling,
            // not our host prefix. This is now OPENABLE: the runtime installs a fishhook rebind of
            // libjava/libjvm open/stat/lstat/access/fstatat (main.cpp macapk_install_jvmfs_rebind, same
            // gate) that remaps /data/... -> <android_root> for the JVM's ZipFile open. Same File.toURI()
            // encoding a device uses, so the folded bytes match a device's. Gated: default returns the host URL.
            if (System.getenv("MACAPK_ANDROID_MAPS_PATHS") != null) {
                String dev = urlFile.getAbsolutePath();
                String root = com.macapk.loader.SharedStorage.androidRoot();
                if (root != null && !root.isEmpty()) {
                    while (root.length() > 1 && root.endsWith("/")) root = root.substring(0, root.length() - 1);
                    if (dev.startsWith(root) && (dev.length() == root.length() || dev.charAt(root.length()) == '/'))
                        dev = dev.substring(root.length());
                }
                return new URL("jar:" + new java.io.File(dev).toURI().toURL() + "!/" + entry);
            }
            return new URL("jar:" + urlFile.toURI().toURL() + "!/" + entry);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- BOOTSPEED: the direct classes.jar read (v1.0.1650) ----
    /** MACAPK_BOOTSPEED_JARREAD -- default ON; "0" restores the pre-v1.0.1650
     *  read exactly (findResource -> jar: URL -> openStream for every class).
     *
     *  WHAT IT CHANGES: only HOW findClass fetches a class file's bytes, never
     *  WHICH bytes. The old read went through the URL machinery for every app
     *  class a boot defines: URLClassPath.findResource builds a jar: URL
     *  (ParseUtil.encodePath + new URL), then URL.openStream parses that URL
     *  back, looks the jar up in JarURLConnection's JarFileFactory cache and
     *  only then reads the entry. Measured offline, arms interleaved: on the
     *  class ORDER F-Droid's real boot loaded (5,968 classes,
     *  debug/bootspeed_bench.sh parts) URL 80-109 ms vs direct 45-64 ms; a full
     *  load of Signal's 8,361 classes, 6 reps: CPU mean 851 -> 802 ms (-6%),
     *  WALL within noise. So the win is CPU, and its wall-clock value on a real
     *  boot is unproven until a live A/B. (An earlier 234-vs-40 ms reading was
     *  a first-in-JVM ordering artifact: whichever arm ran first paid the
     *  jar-open and warm-up; swapping the arms removed most of it.)
     *
     *  WHY IT IS THE SAME ANSWER: the loader's classpath is [classes.jar,
     *  services_root/] (Boot.run), and URLClassPath searches it IN ORDER, so for
     *  any entry classes.jar carries, findResource resolves to exactly that
     *  entry. This opens the jar the way URLClassPath.JarLoader.getJarFile does
     *  on JDK 21 (new JarFile(file, verify=true, OPEN_READ,
     *  JarFile.runtimeVersion()), then jar.getJarEntry(name)), so a versioned
     *  (multi-release) entry resolves identically too. A MISS -- a class the jar
     *  does not carry, which is where services_root, the APK-entry fallback and
     *  the lazy-DEX converter come in -- returns null and takes the old path
     *  unchanged, as does any failure to open or read. The bytes then go through
     *  the same ClassFileRewriter and defineClass as before.
     *  Proven byte-identical by debug/bootspeed_gate.sh (every class of the
     *  corpus boot lists, both reads, compared byte for byte). */
    static final boolean BOOTSPEED_JARREAD =
        !"0".equals(System.getenv("MACAPK_BOOTSPEED_JARREAD"));
    private volatile java.util.jar.JarFile classJar;
    private volatile boolean classJarTried;

    /** The classes.jar this loader's FIRST classpath URL names, opened as
     *  URLClassPath.JarLoader opens it, or null when that URL is not a local
     *  jar (a directory URL, a remote URL, a missing file, an open failure). */
    private java.util.jar.JarFile classJar() {
        if (classJarTried) return classJar;
        synchronized (this) {
            if (classJarTried) return classJar;
            try {
                URL[] us = getURLs();
                if (us.length > 0 && us[0] != null && "file".equals(us[0].getProtocol())
                        && (us[0].getHost() == null || us[0].getHost().isEmpty())
                        && !us[0].getPath().endsWith("/")) {
                    File f = new File(us[0].toURI());
                    if (f.isFile()) {
                        classJar = new java.util.jar.JarFile(f, true,
                            java.util.zip.ZipFile.OPEN_READ, java.util.jar.JarFile.runtimeVersion());
                    }
                }
            } catch (Throwable t) {
                classJar = null;   // the URL path serves everything, as before
            }
            classJarTried = true;
            return classJar;
        }
    }

    /** The bytes of {@code path} in classes.jar, or null (absent, or any
     *  error) -- null always means "use the URL path". */
    private byte[] readClassJarEntry(String path) {
        java.util.jar.JarFile jf = classJar();
        if (jf == null) return null;
        try {
            java.util.jar.JarEntry e = jf.getJarEntry(path);
            if (e == null || e.isDirectory()) return null;
            try (java.io.InputStream in = jf.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- App classes in a "java." package -- lets an app define its OWN classes in a package
    // whose name starts with "java." (2026-10-07, Efteling 5.25.0).
    //
    // THE GAP. ART defines whatever classes a dex carries, whatever their package:
    // the app loader's DexFile path never consults java.lang.ClassLoader's
    // preDefineClass. HotSpot does: ClassLoader.preDefineClass throws
    // SecurityException("Prohibited package name: java.x") for any "java." name a
    // loader other than the platform loader defines, and the class file parser
    // refuses the same names for the same loaders. So an app class that Android
    // loads fine is unloadable here.
    //
    // HOW IT SHOWED UP. Efteling 5.25.0 bundles react-native-keyboard-controller,
    // whose ClippingScrollViewDecoratorViewManager is declared in package
    // java.com.reactnativekeyboardcontroller (its siblings are in
    // com.reactnativekeyboardcontroller). createViewManagers instantiates it during
    // React Native start-up, the define threw, the JS runtime reported it as a
    // fatal JavascriptException and the app died before its first screen.
    // 5.24.0 did not ship the class.
    //
    // THE FIX. Give such a class another name for the whole app: every class this
    // loader defines has the package's internal and dotted spellings rewritten
    // from "java/<pkg>/" to "macapk/java/<pkg>/" in its constant pool, the class
    // itself included, and loadClass maps a request for the original name to the
    // new one. HotSpot insists a loader return a class of exactly the requested
    // name, so the references have to be renamed at define time; mapping the name
    // in loadClass alone would not resolve a single bytecode reference.
    //
    // WHICH PACKAGES. Only packages classes.jar carries under java/ that NO module
    // of the boot layer has. Packages the JDK has (desugar backports of
    // java.util.function and friends) keep their old route untouched: the parent
    // answers them first, as before. Most apps carry no such package, and for
    // them every method here returns its input after one empty-set check.
    //
    // LIMITS. Class.forName and JNI FindClass with the ORIGINAL name still fail
    // (HotSpot checks the returned class's name for both); dotted string constants
    // in the app's own bytecode are renamed too, which covers reflection by a
    // literal name. Class.getName() reports the new name.
    //
    // Gate: MACAPK_PROHIBITED_PKG_REMAP=0 restores the old behaviour exactly.
    //
    // Kept inside this class (fields + static helpers) rather than in a class
    // of its own: FRAMEWORK_ID counts class PRESENCE (runtime/scripts/
    // build_ids.py), so one new class would have re-converted every installed
    // app on its next launch.
    static final boolean PKG_REMAP =
        !"0".equals(System.getenv("MACAPK_PROHIBITED_PKG_REMAP"));
    /** Internal-name prefix a renamed package gets. */
    static final String PKG_REMAP_PREFIX = "macapk/";
    static final String PKG_REMAP_PREFIX_DOTTED = "macapk.";

    /** Prohibited packages as {"java/com/foo/", "java.com.foo."} pairs, from
     *  classes.jar; empty for nearly every app. Null until first asked. */
    private volatile String[] remapPkgs;

    private String[] remapPkgs() {
        String[] r = remapPkgs;
        if (r != null) return r;
        synchronized (this) {
            if (remapPkgs == null) {
                remapPkgs = PKG_REMAP ? scanProhibitedPackages() : new String[0];
            }
            return remapPkgs;
        }
    }

    /** Scans classes.jar's class entries once for packages under java/ that no
     *  module of the boot layer has. Any failure yields none (the old
     *  behaviour). */
    private String[] scanProhibitedPackages() {
        // Through the BOOTSPEED reader's JarFile when that path is on; with
        // MACAPK_BOOTSPEED_JARREAD=0 a JarFile of our own, closed after the scan,
        // so that bisect arm still leaves the direct reader unengaged.
        java.util.jar.JarFile shared = BOOTSPEED_JARREAD ? classJar() : null;
        java.util.jar.JarFile jar = shared;
        long t0 = System.nanoTime();
        try {
            if (jar == null && !BOOTSPEED_JARREAD) {
                URL[] us = getURLs();
                if (us.length > 0 && us[0] != null && "file".equals(us[0].getProtocol())
                        && !us[0].getPath().endsWith("/")) {
                    File f = new File(us[0].toURI());
                    if (f.isFile()) jar = new java.util.jar.JarFile(f, false);
                }
            }
            if (jar == null) return new String[0];
            java.util.Set<String> found = new java.util.LinkedHashSet<>();
            java.util.Set<String> boot = null;
            java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("java/") || !n.endsWith(".class")) continue;
                int slash = n.lastIndexOf('/');
                if (slash <= 4) continue;                       // "java/X.class"
                String pkgSlash = n.substring(0, slash + 1);
                if (found.contains(pkgSlash)) continue;
                if (boot == null) {
                    boot = new java.util.HashSet<>();
                    for (Module m : ModuleLayer.boot().modules()) boot.addAll(m.getPackages());
                }
                if (!boot.contains(n.substring(0, slash).replace('/', '.'))) found.add(pkgSlash);
            }
            if (found.isEmpty()) return new String[0];
            String[] out = new String[found.size() * 2];
            int i = 0;
            for (String f : found) {
                out[i++] = f;
                out[i++] = f.replace('/', '.');
            }
            NativeLog.i(TAG, "renaming app classes in prohibited package(s) " + found
                + " to " + PKG_REMAP_PREFIX + "java/... ("
                + (System.nanoTime() - t0) / 1_000_000 + " ms)");
            return out;
        } catch (Throwable t) {
            NativeLog.w(TAG, "prohibited-package scan of classes.jar failed (" + t + "); no rename");
            return new String[0];
        } finally {
            if (jar != null && jar != shared) {
                try { jar.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** The name this loader defines {@code dotted} under: the renamed one for
     *  an app class of a prohibited package, else {@code dotted} itself. */
    private String remapDefinedName(String dotted) {
        String[] pk = remapPkgs();
        if (pk.length == 0 || !dotted.startsWith("java.")) return dotted;
        int dot = dotted.lastIndexOf('.');
        if (dot <= 0) return dotted;
        for (int i = 1; i < pk.length; i += 2) {
            if (pk[i].length() == dot + 1 && dotted.startsWith(pk[i])) {
                return PKG_REMAP_PREFIX_DOTTED + dotted;
            }
        }
        return dotted;
    }

    /** The original (classes.jar) name of a renamed class, or null when
     *  {@code dotted} is not one. */
    private String remapOriginalName(String dotted) {
        if (remapPkgs().length == 0 || !dotted.startsWith(PKG_REMAP_PREFIX_DOTTED + "java.")) {
            return null;
        }
        String orig = dotted.substring(PKG_REMAP_PREFIX_DOTTED.length());
        return remapDefinedName(orig) != orig ? orig : null;
    }

    /** {@code raw} with every reference to a prohibited package renamed, or
     *  {@code raw} itself when it has none (or does not parse). */
    private byte[] remapRewrite(byte[] raw) {
        String[] pk = remapPkgs();
        if (pk.length == 0 || raw == null) return raw;
        boolean any = false;
        for (String n : pk) {
            if (indexOfAscii(raw, n) >= 0) { any = true; break; }
        }
        if (!any) return raw;
        try {
            return remapConstantPool(raw, pk);
        } catch (Throwable t) {
            NativeLog.w(TAG, "prohibited-package constant pool rewrite failed (" + t
                + "); class left as is");
            return raw;
        }
    }

    /** The class file with each CONSTANT_Utf8 renamed by {@link #remapUtf8}.
     *  Utf8 entries may change length; indices do not move, so nothing outside
     *  the constant pool needs adjusting and the rest is copied verbatim. */
    static byte[] remapConstantPool(byte[] b, String[] pk) {
        if (b.length < 10 || (b[0] & 0xff) != 0xCA || (b[1] & 0xff) != 0xFE
                || (b[2] & 0xff) != 0xBA || (b[3] & 0xff) != 0xBE) return b;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(b.length + 256);
        int count = ((b[8] & 0xff) << 8) | (b[9] & 0xff);
        int p = 10;
        out.write(b, 0, 10);
        boolean changed = false;
        for (int i = 1; i < count; i++) {
            int tag = b[p] & 0xff;
            int size;
            switch (tag) {
                case 1: {                                       // Utf8
                    int len = ((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff);
                    byte[] s = java.util.Arrays.copyOfRange(b, p + 3, p + 3 + len);
                    byte[] r = remapUtf8(s, pk);
                    if (r != s) {
                        if (r.length > 0xFFFF) throw new IllegalStateException("Utf8 too long");
                        out.write(1);
                        out.write(r.length >>> 8);
                        out.write(r.length & 0xff);
                        out.write(r, 0, r.length);
                        changed = true;
                        p += 3 + len;
                        continue;
                    }
                    size = 3 + len;
                    break;
                }
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    size = 5; break;
                case 5: case 6:
                    size = 9; i++; break;                       // takes two slots
                case 7: case 8: case 16: case 19: case 20:
                    size = 3; break;
                case 15:
                    size = 4; break;
                default:
                    throw new IllegalStateException("constant pool tag " + tag + " at " + p);
            }
            out.write(b, p, size);
            p += size;
        }
        if (!changed) return b;
        out.write(b, p, b.length - p);
        return out.toByteArray();
    }

    /** {@code s} with "macapk/" (or "macapk.") in front of every occurrence of
     *  a prohibited package that starts a class name, or {@code s} itself.
     *  Covers internal names, descriptors and signatures ("Ljava/x/Y;") and
     *  dotted string constants ("java.x.Y"). */
    static byte[] remapUtf8(byte[] s, String[] pk) {
        java.io.ByteArrayOutputStream out = null;
        int copied = 0;
        for (int i = 0; i + 5 <= s.length; i++) {
            if (s[i] != 'j' || s[i + 1] != 'a' || s[i + 2] != 'v' || s[i + 3] != 'a') continue;
            byte sep = s[i + 4];
            if (sep != '/' && sep != '.') continue;
            if (i > 0 && !remapNameMayStartAfter(s[i - 1])) continue;
            int len = 0;
            for (int k = sep == '/' ? 0 : 1; k < pk.length; k += 2) {
                if (asciiAt(s, i, pk[k]) && !remapHasSeparatorAfter(s, i + pk[k].length(), (byte) sep)) {
                    len = pk[k].length();
                    break;
                }
            }
            if (len == 0) continue;
            if (out == null) out = new java.io.ByteArrayOutputStream(s.length + 16);
            out.write(s, copied, i - copied);
            String pre = sep == '/' ? PKG_REMAP_PREFIX : PKG_REMAP_PREFIX_DOTTED;
            for (int k = 0; k < pre.length(); k++) out.write(pre.charAt(k));
            copied = i;
            i += len - 1;
        }
        if (out == null) return s;
        out.write(s, copied, s.length - copied);
        return out.toByteArray();
    }

    /** True when a class name may begin right after {@code c}: a descriptor's
     *  'L', an array's '[', or any character that cannot be part of a name. */
    private static boolean remapNameMayStartAfter(byte c) {
        if (c == 'L' || c == '[') return true;
        return !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '_' || c == '$' || c == '/' || c == '.' || c < 0);
    }

    /** True when the name at {@code from} continues into a subpackage, i.e. a
     *  separator follows before the name ends: then the match was a prefix of a
     *  longer package, not the prohibited package itself. */
    private static boolean remapHasSeparatorAfter(byte[] s, int from, byte sep) {
        for (int k = from; k < s.length; k++) {
            byte c = s[k];
            if (c == sep) return true;
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '$' || c < 0)) return false;
        }
        return false;
    }

    private static boolean asciiAt(byte[] h, int at, String n) {
        if (at + n.length() > h.length) return false;
        for (int k = 0; k < n.length(); k++) {
            if (h[at + k] != n.charAt(k)) return false;
        }
        return true;
    }

    private static int indexOfAscii(byte[] h, String n) {
        for (int i = 0; i + n.length() <= h.length; i++) {
            if (asciiAt(h, i, n)) return i;
        }
        return -1;
    }

    // ---- lazy DEX conversion (MACAPK_LAZY_DEX, default OFF) ----
    // Started on first use rather than in the constructor: most boots never
    // reach it (the eager classes.jar answers everything), and spawning a
    // python process we would not use is pure cost.
    private volatile dalvik.system.LazyDexSource lazyDex;
    private volatile boolean lazyDexTried;

    private dalvik.system.LazyDexSource lazySource() {
        if (lazyDexTried) return lazyDex;
        synchronized (this) {
            if (lazyDexTried) return lazyDex;
            lazyDexTried = true;
            if (apkFile == null) return null;
            String on = System.getenv("MACAPK_LAZY_DEX");
            boolean asked = on != null && !on.isEmpty() && !on.equals("0");
            // ...OR there is no eager classes.jar to answer from. That is the
            // AUTOMATIC fallback, and it is what makes "the install succeeded"
            // an honest statement when the ahead-of-time conversion did not
            // finish: the app's code is still right there in the APK, and this
            // is the SAME converter the install path runs, just driven one
            // class at a time by the JVM's own classloading.
            //
            // The eager converter is all-or-nothing (com/macapk/dex/Main.java
            // catches any Throwable, deletes the jar and exits 1), so ONE
            // untranslatable class discards every class in the app. Per class,
            // LazyDexSource records the failure and keeps serving the rest --
            // which is why routing here is a real recovery and not a shrug.
            //
            // Cost when the jar IS present: one boolean read. Cost when it is
            // absent and the APK has no DEX at all: LazyDexSource.start returns
            // null (Main.readDexes finds nothing) and every lookup misses, the
            // same state a legal android:hasCode="false" app already produces.
            if (!asked && hasEagerClassesJar()) return null;
            lazyDex = dalvik.system.LazyDexSource.start(apkFile);
            NativeLog.i(TAG, "lazy dex conversion "
                + (lazyDex != null
                    ? ("ENABLED for " + apkFile.getName()
                       + (asked ? " (MACAPK_LAZY_DEX)" : " (no eager classes.jar -- fallback)"))
                    : ("requested but UNAVAILABLE for " + apkFile.getName()
                       + " (no DEX in the APK?)")));
            return lazyDex;
        }
    }

    /** True when this loader's classpath carries a classes.jar that actually
     *  exists on disk. Asked of the URLs rather than of a guessed path,
     *  because the loader's classpath IS the definition of where eager classes
     *  come from and nothing else has to stay in sync with it. */
    private boolean hasEagerClassesJar() {
        try {
            for (URL u : getURLs()) {
                if (u == null || !"file".equals(u.getProtocol())) continue;
                String p = u.getPath();
                if (p == null || !p.endsWith(".jar")) continue;
                if (new File(java.net.URLDecoder.decode(p, "UTF-8")).isFile()) return true;
            }
        } catch (Throwable t) {
            // Unsure means "assume the jar is there": guessing the other way
            // would start a converter session on every boot of every app.
            return true;
        }
        return false;
    }

    @Override
    protected Class<?> findClass(String name)
            throws ClassNotFoundException {
        // First slash->dot normalization (engines like axmol pass JNI
        // form). Compute both spellings up front so the bytecode-
        // rewriter pass below sees a consistent class name.
        String classDotted = name.indexOf('/') >= 0 ? name.replace('/', '.') : name;
        // An app class in a "java." package is defined under its renamed name
        // (see remapDefinedName); srcDotted is the name classes.jar keeps it
        // under. Both are classDotted for every other class.
        classDotted = remapDefinedName(classDotted);
        String srcOriginal = remapOriginalName(classDotted);
        String srcDotted = srcOriginal != null ? srcOriginal : classDotted;

        // ART answers findClass for a class this loader ALREADY defined with that
        // class: BaseDexClassLoader.findClass -> DexFile.defineClassNative ->
        // ClassLinker::DefineClass, whose InsertClass finds the descriptor in the
        // loader's class table and returns the existing entry
        // (art/runtime/class_linker.cc, "if (existing != nullptr) ... return
        // EnsureResolved(self, descriptor, existing)"). HotSpot's defineClass
        // throws LinkageError "attempted duplicate class definition" instead.
        // Unreal Engine's FAndroidApplication::FindJavaClass calls the cached
        // class loader's findClass DIRECTLY (not loadClass) from its GameThread,
        // so its first lookup of the already-running GameActivity killed the
        // engine (Rocket League Sideswipe, 2026-10-02).
        Class<?> alreadyDefined = findLoadedClass(classDotted);
        if (alreadyDefined != null) return alreadyDefined;

        // Shout if this class is known NOT to pass HotSpot's verifier. It loads
        // anyway because -Xverify:none is set, which is exactly why the fact has
        // to be surfaced here rather than left to a mystery symptom later. No-op
        // unless someone has triaged the apk; see UnverifiedClasses.
        UnverifiedClasses.check(classDotted);

        // Load the raw class bytes via our URL classpath. If found,
        // route through ClassFileRewriter -- which repairs the
        // dex2jar-emitted broken-<clinit> bytecode pattern (sgtpuzzles
        // EmptyList / Enum, ShatteredPD h3-style) BEFORE handing to
        // defineClass. Per JVMS §5.3.5, defineClass() is the
        // derivation step and runs BEFORE verification/linking; this
        // is the safe rewrite window.
        //
        // Fallback: if we can't get the raw bytes (system class, etc.),
        // delegate to URLClassLoader.findClass() so its built-in
        // resource lookup runs.
        String resourcePath = srcDotted.replace('.', '/') + ".class";
        // BOOTSPEED (v1.0.1650): read the bytes straight out of classes.jar when
        // it carries the entry -- the SAME entry, through the same kind of
        // JarFile, that the URL lookup below would resolve. See
        // BOOTSPEED_JARREAD. A miss (or any trouble) takes the unchanged URL path.
        byte[] direct = BOOTSPEED_JARREAD ? readClassJarEntry(resourcePath) : null;
        java.net.URL u = direct != null ? null : findResource(resourcePath);
        if (direct != null || u != null) {
            try (java.io.InputStream in = direct != null ? null : u.openStream()) {
                byte[] raw = remapRewrite(direct != null ? direct : in.readAllBytes());
                ClassFileRewriter.Result r =
                    ClassFileRewriter.maybeRewrite(raw, classDotted);
                if (r.rewritten) {
                    NativeLog.i(TAG, "findClass(" + classDotted
                        + ") -> defineClass [rewritten: " + r.reason + "]");
                } else if (!QUIET_CLASSLOAD) {
                    NativeLog.i(TAG, "findClass(" + classDotted + ")");
                }
                Class<?> defined = defineClass(classDotted, r.bytes, 0, r.bytes.length);
                maybePopulateRClassFields(defined, classDotted);
                wirePairipRegistry(defined, r.bytes, classDotted);
                return defined;
            } catch (java.io.IOException io) {
                // Fall through to the legacy super.findClass path so
                // any obscure URL-stream errors don't kill class loading.
                NativeLog.w(TAG, "findClass(" + classDotted
                    + ") read failed: " + io + " -- delegating to super");
            } catch (LinkageError le) {
                // defineClass may throw LinkageError (e.g. attempted
                // duplicate define from a parallel loader race). The race
                // ART resolves the same way as the check at the top: the
                // thread that lost gets the winner's class. Anything else
                // (a real format/verify error) the JVM surfaces itself.
                Class<?> winner = findLoadedClass(classDotted);
                if (winner != null) return winner;
                throw le;
            } catch (Throwable t) {
                NativeLog.w(TAG, "findClass(" + classDotted
                    + ") rewriter pipeline threw: " + t
                    + " -- delegating to super");
            }
        }

        // LAZY DEX (MACAPK_LAZY_DEX, default OFF): the classes.jar lookup
        // above found nothing, so translate just this one class out of the
        // APK's DEX with the in-process converter. Measured
        // motivation: only 15-46% of an APK's classes are ever loaded, so
        // converting all of them up front discards most of the work (see
        // dalvik.system.LazyDexSource).
        //
        // Deliberately placed AFTER the jar path, not before it: when the
        // flag is off (or the jar exists) this branch never runs and the
        // eager behaviour is byte-for-byte unchanged. Bytes still go through
        // ClassFileRewriter -- the interception point is the same one the
        // eager path uses, so a rewrite fix applies to both.
        dalvik.system.LazyDexSource lazy = lazySource();
        if (lazy != null) {
            byte[] raw = remapRewrite(lazy.classBytes(srcDotted.replace('.', '/')));
            if (raw != null) {
                try {
                    ClassFileRewriter.Result r =
                        ClassFileRewriter.maybeRewrite(raw, classDotted);
                    Class<?> defined =
                        defineClass(classDotted, r.bytes, 0, r.bytes.length);
                    maybePopulateRClassFields(defined, classDotted);
                    wirePairipRegistry(defined, r.bytes, classDotted);
                    if (!QUIET_CLASSLOAD) {
                        NativeLog.i(TAG, "findClass(" + classDotted + ") [lazy dex]");
                    }
                    return defined;
                } catch (LinkageError le) {
                    throw le;
                } catch (Throwable t) {
                    NativeLog.w(TAG, "findClass(" + classDotted
                        + ") lazy-dex define failed: " + t + " -- delegating");
                }
            }
        }

        // Original delegation path -- preserved as the fallback.
        try {
            Class<?> c = super.findClass(name);
            if (!QUIET_CLASSLOAD) NativeLog.i(TAG, "findClass(" + name + ") -> " + c);
            return c;
        } catch (ClassNotFoundException e) {
            if (name.indexOf('/') >= 0) {
                try {
                    Class<?> c = super.findClass(classDotted);
                    NativeLog.i(TAG, "findClass(" + name
                        + ") -> via slash-fixup " + c);
                    return c;
                } catch (ClassNotFoundException ignored) {}
            }
            NativeLog.w(TAG, "findClass(" + name + ") -> ClassNotFound");
            throw e;
        }
    }

    /** DexFile.loadClass(name, this): the class comes from a dex the app opened
     *  itself (Adyen's 3DS2 SDK writes one to code_cache and defines classes out
     *  of it), so it gets the same class-file rewriter pass findClass gives the
     *  app's own classes. */
    @Override
    protected Class<?> macapkDefineDexFileClass(String binaryName, byte[] bytes) {
        binaryName = remapDefinedName(binaryName);
        bytes = remapRewrite(bytes);
        ClassFileRewriter.Result r = ClassFileRewriter.maybeRewrite(bytes, binaryName);
        Class<?> defined = defineClass(binaryName, r.bytes, 0, r.bytes.length);
        maybePopulateRClassFields(defined, binaryName);
        wirePairipRegistry(defined, r.bytes, binaryName);
        return defined;
    }

    // Pure pass-through, matching java.lang.ClassLoader's own shape
    // ("return loadClass(name, false);", an unqualified virtual call --
    // verified from openjdk/jdk21u source). All normalisation + logging +
    // GMS_CHILD_FIRST logic lives in the two-arg override below, which this
    // call reaches directly via virtual dispatch (this is still an
    // AppClassLoader instance).
    //
    // Previously this method ALSO normalised the name (slash -> dot) and
    // logged BEFORE calling super.loadClass(normalised). Because neither
    // BaseDexClassLoader nor URLClassLoader/SecureClassLoader override the
    // one-arg loadClass, super.loadClass(normalised) resolved to
    // java.lang.ClassLoader.loadClass(String) -- whose body is exactly
    // that same unqualified `loadClass(name, false)` virtual call -- which
    // re-entered THIS class's two-arg override a SECOND time. That override
    // unconditionally builds and logs its OWN near-duplicate string and
    // re-runs the isGmsChildFirstFamily prefix scan. Net effect: every
    // one-arg caller (Class.forName, JNI FindClass on a non-bootstrap
    // loader, most reflective lookups) paid for the ENTIRE two-arg method
    // body TWICE per logical classload -- two string builds, two prefix
    // scans, and (when logging is enabled) two log lines for one event.
    // The two-arg form computes `normalised` from `name` identically
    // (same `indexOf('/') >= 0 ? replace('/','.') : name` expression), so
    // collapsing this to a plain pass-through changes nothing about WHICH
    // class resolves -- only removes the duplicate top half.
    // See docs/efficiency-classload-logging_2026-07-24.md Finding 2.
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    /** Prefixes whose classes are loaded CHILD-FIRST (the app's bundled copy
     *  wins over our parent-loaded shadow) when {@link #GMS_CHILD_FIRST} is on.
     *
     *  It MUST be the WHOLE family together. Our parent-first firebase shadow
     *  returns com.google.android.gms.tasks.Task; if part of the family stays
     *  parent-first (our Task) while another part goes child-first (the app's
     *  Task), the same app code sees TWO Task types across loaders ->
     *  ClassCastException / NoSuchMethodError at task.addOnCompleteListener(...).
     *  Consistency requires ONE source per app for the whole family: the app's
     *  bundled copy when present, our shadow when absent -- never a mix.
     *
     *  EXCLUSION: com.google.android.play.core.assetpacks.* is our REAL Unity
     *  local-delivery impl (AssetPackManagerImpl), which Among Us / Crossy
     *  depend on -- it MUST stay parent-first so an app's bundled
     *  play-asset-delivery copy can't shadow our functional one. Handled
     *  explicitly in {@link #isGmsChildFirstFamily}. */
    // debug/dexconv_verify/VerifyCheck.java reads this field REFLECTIVELY by
    // name (ImageLoader.loadFromRuntime) so its own child-first classloader
    // can't drift from this one the way it did before 2026-08-12 (see that
    // file's class doc for the incident). Renaming this field silently breaks
    // that reflection (caught, falls back to a hand-synced snapshot with a
    // stderr warning) -- if you rename it, update VerifyCheck.java too.
    private static final String[] GMS_CHILD_FIRST_PREFIXES = {
        "com.google.android.gms.",
        "com.google.firebase.",
        "com.google.android.play.core.",
        "com.google.mlkit.",
        "com.android.billingclient.",
        "com.google.ads.",
    };

    /** DEVICE ORACLES -- PARENT-first even though they sit inside the child-first
     *  family above. A class that answers "what does this DEVICE have" is
     *  answered by the device; a class that CARRIES THE APP'S DATA stays the
     *  app's. Mac.apk is the device, so its shadow owns the first kind. This is
     *  the same parent-first arm of the decision tree that already keeps
     *  play.core.assetpacks.* (our real local-delivery impl) and gms.tasks.* for
     *  PAD apps on this side -- see the exclusions in isGmsChildFirstFamily.
     *
     *  ADMISSION CRITERIA. All five must hold, and all five are mechanically
     *  checkable against the app's own bytecode -- that is what keeps this a
     *  device policy rather than a per-app carve-out:
     *   (1) it answers a host-environment question, not an app-data question;
     *   (2) our shadow is a REAL implementation of that answer;
     *   (3) its ENTIRE member surface, unioned over the basement versions in the
     *       corpus, is LOADER-NEUTRAL -- every descriptor mentions only
     *       primitives, java.*, and android.*. JVMS SE21 5.3.4 imposes loading
     *       constraints on exactly the return type and the formal parameter
     *       types, so a neutral surface cannot produce the LinkageError that
     *       became the v1.0.510 "GMS loader-constraint JNI poison" native crash;
     *   (4) if an app class EXTENDS it, our shadow must declare every member
     *       public (JVMS SE21 5.4.4: package-private needs the same RUN-TIME
     *       package, and that is (name, defining loader), which a different
     *       loader never matches -- the BouncyCastle IllegalAccessError shape
     *       documented above);
     *   (5) it must not be the route by which any GMS-typed object crosses the
     *       loader boundary.
     *
     *  GoogleApiAvailability and GoogleApiAvailabilityLight FAIL (3) and (4) and
     *  MUST NOT be added: their measured surface includes Task, ConnectionResult,
     *  HasApiKey, GoogleApi, LifecycleFragment and the zab / zaj internals, and
     *  Crossy Road's own GoogleApiManager calls
     *  GoogleApiAvailability.zad(Context, ConnectionResult, int) on the
     *  connection-failed path that always runs on a GMS-less device. Parent-first
     *  there would record a loader constraint on ConnectionResult and reproduce
     *  the v1.0.510 crash exactly. Intercepting the leaf oracle below is
     *  sufficient: an app's own GoogleApiAvailabilityLight reaches the verdict by
     *  `invokestatic` into this class, so the answer changes without any GMS type
     *  ever crossing the boundary.
     *
     *  Full evidence: docs/gms-device-oracle-parent-first_2026-08-01.md.
     *
     *  Also reflected by name from debug/dexconv_verify/VerifyCheck.java --
     *  same rename caveat as GMS_CHILD_FIRST_PREFIXES above. */
    private static final String[] PARENT_FIRST_DEVICE_ORACLES = {
        "com.google.android.gms.common.GooglePlayServicesUtilLight",
    };

    /** Legacy Apache HttpClient (org.apache.http.legacy -- optional since API 23,
     *  gone from the default bootclasspath since API 28) is the same shape of
     *  problem as the GMS family above (an app that still uses it bundles its
     *  OWN copy in its DEX, and our parent-loaded runtime/java/org/apache/http/**
     *  shadow is link-safe but not functional networking code), but with NO
     *  cross-family type-identity constraint: nothing else in the framework
     *  hands an org.apache.http.* typed object across the loader boundary, so
     *  there is no "whole family together" consistency requirement forcing it
     *  into GMS_CHILD_FIRST_PREFIXES itself. Kept as its own array so the name
     *  above stays accurate; folded into the same child-first check + the same
     *  {@link #GMS_CHILD_FIRST} gate below since it is the identical mechanism
     *  (app's bundled copy wins; our shadow is the fallback for an app that
     *  bundled nothing, via the existing ClassNotFoundException catch).
     *
     *  Also reflected by name from debug/dexconv_verify/VerifyCheck.java --
     *  same rename caveat as GMS_CHILD_FIRST_PREFIXES above. This is the
     *  field that drifted: VerifyCheck's own copy was last synced 2026-07-25,
     *  missed "org.bouncycastle." being added below on 2026-07-30, and (found
     *  2026-08-12) was reporting a false-positive IllegalAccessError family
     *  for Aegis as a result; see VerifyCheck.java's class doc for the full
     *  incident. */
    private static final String[] CHILD_FIRST_MISC_PREFIXES = {
        "org.apache.http.",
        // BouncyCastle. Same shape again, and this one is a collision REAL ANDROID
        // DOES NOT HAVE: AOSP ships its BC fork REPACKAGED as
        // com.android.org.bouncycastle precisely so that an app bundling the
        // upstream `org.bouncycastle` namespace can never collide with the
        // platform's copy. We vendor bcprov-jdk18on under its ORIGINAL name (for
        // the "BC" provider host OpenJDK lacks), which re-creates the collision
        // Android engineered away.
        //
        // MEASURED on Aegis 3.4.2 (2026-07-30), which bundles 94 org.bouncycastle
        // classes of its own, including org/bouncycastle/asn1/ASN1UniversalType:
        //   IllegalAccessError: class com.bumptech.glide...LruArrayPool$KeyPool
        //   cannot access its abstract superclass
        //   org.bouncycastle.asn1.ASN1UniversalType (KeyPool is in unnamed module
        //   of loader com.macapk.loader.AppClassLoader; ASN1UniversalType is in
        //   unnamed module of loader 'app')
        // -- i.e. an app class resolved its own superclass to OUR copy across a
        // loader boundary and lost package access. It killed onStart AND
        // onPostResume, so the activity never reached a drawable state.
        //
        // Child-first restores the Android-observable contract (the app's bundled
        // BC is the one it links against) without needing to repackage the jar,
        // and our copy remains the fallback for an app that bundles none, via the
        // existing ClassNotFoundException catch. Boot.java still registers the
        // provider at LOWEST preference either way.
        "org.bouncycastle.",
    };

    /** -1 = not yet probed, 0 = app does NOT use Play Asset Delivery against
     *  our shadow, 1 = it does. Cached: the probe (findResource) is done once. */
    private volatile int padAppCache = -1;

    /** True when the app's OWN (child-loaded) code calls Play Asset Delivery,
     *  which on Mac.apk binds to our parent-loaded AssetPackManagerImpl (the
     *  assetpacks exclusion below). The canonical such caller is the Unity PAD
     *  glue class com.unity3d.player.PlayAssetDeliveryUnityWrapper (Crossy
     *  Road, Among Us, any Unity PAD title). When the app bundles its own
     *  com.google.android.gms.tasks.Task too, child-first loading it would make
     *  that wrapper and our shadow AssetPackManager disagree on Task -> the
     *  loader-constraint LinkageError. We detect the wrapper because the app
     *  bundles the wrapper but NOT AssetPackManager itself (that is our
     *  shadow). See {@link #isGmsChildFirstFamily}. */
    private boolean appBundlesAssetPacks() {
        int v = padAppCache;
        if (v >= 0) return v == 1;
        boolean has = false;
        try {
            has = findResource(
                       "com/unity3d/player/PlayAssetDeliveryUnityWrapper.class") != null
                || findResource(
                       "com/google/android/play/core/assetpacks/AssetPackManager.class") != null;
        } catch (Throwable ignore) { /* treat as absent */ }
        padAppCache = has ? 1 : 0;
        return has;
    }

    /** True when {@code dotted} belongs to a child-first GMS family (minus the
     *  assetpacks exclusion), OR to the unrelated-but-same-mechanism
     *  {@link #CHILD_FIRST_MISC_PREFIXES} set (legacy org.apache.http.*). See
     *  {@link #GMS_CHILD_FIRST_PREFIXES}. */
    private boolean isGmsChildFirstFamily(String dotted) {
        if (dotted == null) return false;
        // DEVICE ORACLES answer a question about this device, so this device
        // answers them. See PARENT_FIRST_DEVICE_ORACLES for the admission rule
        // and for why the neighbouring GoogleApiAvailability* classes must NOT
        // be added here.
        for (String c : PARENT_FIRST_DEVICE_ORACLES) {
            if (dotted.equals(c)) return false;
        }
        // Keep our REAL local-delivery impl parent-first (do NOT let the app's
        // bundled play-asset-delivery copy shadow AssetPackManagerImpl).
        if (dotted.startsWith("com.google.android.play.core.assetpacks.")) return false;
        // LOADER-CONSTRAINT CONSISTENCY (v1.0.510, fixes Crossy Road's render
        // crash): our parent-loaded AssetPackManagerImpl.getPackStates() returns
        // com.google.android.gms.tasks.Task. If the app's bundled PAD wrapper
        // (e.g. com.unity3d.player.PlayAssetDeliveryUnityWrapper, child-loaded)
        // resolves gms.tasks.Task child-first while our shadow AssetPackManager
        // (parent) exposes our shadow Task, the JVM records a loader-constraint
        // violation -> LinkageError -> a pending JNI exception that makes a
        // LATER, UNRELATED JNI call (Unity's getPackageName-fallback NewGlobalRef
        // in its PlayerPrefs path) return NULL -> strlen(NULL) crash on the first
        // render frame. Keep gms.tasks parent-first (our shadow) for PAD apps so
        // the delivery+tasks family is one source. Non-PAD apps (no
        // AssetPackManager bundled, e.g. Reddit) keep gms.tasks child-first.
        if (dotted.startsWith("com.google.android.gms.tasks.")
                && appBundlesAssetPacks()) return false;
        for (String p : GMS_CHILD_FIRST_PREFIXES) {
            if (dotted.startsWith(p)) return true;
        }
        for (String p : CHILD_FIRST_MISC_PREFIXES) {
            if (dotted.startsWith(p)) return true;
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        // Same slash->dot normalisation in the two-arg form. JNI
        // typically dispatches the one-arg loadClass(String), but
        // Android's ClassLoader internals sometimes call the
        // protected two-arg variant directly.
        String normalised = name.indexOf('/') >= 0
            ? name.replace('/', '.')
            : name;
        // A request by the original name of an app class in a "java." package
        // gets the renamed class (see remapDefinedName). The JVM's own
        // resolution never asks for the original: references were renamed at
        // define time.
        normalised = remapDefinedName(normalised);
        if (!QUIET_CLASSLOAD) NativeLog.i(TAG, "loadClass(" + name + ", resolve=" + resolve
            + ")"
            + (normalised.equals(name) ? "" : " -> " + normalised));

        // MACAPK_FWGAP_NEWCLASSES=0 bisect gate (com.macapk.loader.FwGapNewClasses,
        // docs/fwgap-new-platform-classes_2026-09-26.md). Before the 2026-09-26
        // framework-gap lane the parent (framework) lookup for these names
        // missed, so the load fell through to findClass: the app's own classpath
        // (its R8 API stub of ServiceStartNotAllowedException when it bundles
        // one) or ClassNotFoundException. Skipping the parent for exactly those
        // names reproduces that for every load through this loader. Default ON:
        // hidden() returns false at once, one boolean test per load.
        // debug/fwgap_gate.sh strips this block to build its positive control.
        if (FwGapNewClasses.hidden(normalised)) {
            synchronized (getClassLoadingLock(normalised)) {
                Class<?> c = findLoadedClass(normalised);
                if (c == null) c = findClass(normalised);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        // v1.0.477: child-first for the GMS/Firebase/Play/Billing families.
        // The app's OWN bundled copy (reachable via our URL classpath =
        // findClass) must win over our parent-loaded shadow; we only fall back
        // to the shadow when the app didn't bundle the class. This is the whole
        // entry point because the one-arg loadClass(String) and the JVM's own
        // resolution both route through this two-arg form via virtual dispatch.
        if (GMS_CHILD_FIRST && isGmsChildFirstFamily(normalised)) {
            synchronized (getClassLoadingLock(normalised)) {
                Class<?> c = findLoadedClass(normalised);
                if (c == null) {
                    try {
                        // App's bundled copy first (our classpath is the app jar
                        // only; the framework shadow lives in the PARENT loader).
                        c = findClass(normalised);
                        if (!QUIET_CLASSLOAD) NativeLog.i(TAG, "loadClass("
                            + normalised + ") child-first -> app's bundled copy");
                    } catch (ClassNotFoundException notInApp) {
                        // App didn't bundle it: fall back to our shadow via the
                        // normal parent-first delegation.
                        c = super.loadClass(normalised, false);
                        if (!QUIET_CLASSLOAD) NativeLog.i(TAG, "loadClass("
                            + normalised + ") child-first miss -> shadow fallback");
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        Class<?> c = super.loadClass(normalised, resolve);
        if (RETIRE_ENGINE_SHADOWS && c.getClassLoader() != this
                && !normalised.startsWith("android.") && isRetiredShadow(c)) {
            return appCopyOfRetiredShadow(normalised, c, resolve);
        }
        return c;
    }

    /** MACAPK_RETIRE_ENGINE_SHADOWS (default ON; =0 restores the parent-first
     *  shadow exactly). A RETIRED SHADOW is a framework-jar class that replaces
     *  a class of an app's OWN bundled engine code by NAME (an obfuscated name
     *  from one build, e.g. AndEngine's EngineLock as org.andengine.b.c). Real
     *  Android has no such class on its boot classpath, so the app's own copy
     *  always wins there. A shadow is retired once the framework behaviour it
     *  papered over is fixed at the right layer; it is marked
     *  {@code @Deprecated(since = RETIRED_SHADOW, forRemoval = true)} and stays
     *  in the jar for one soak release so =0 can bring it back. With the gate
     *  on, when the app bundles a class of the same name the app's copy is
     *  defined here instead (the AOSP answer); an app that does not bundle it
     *  still gets the shadow. The loader names no engine: the marker lives on
     *  the shadow. A @Deprecated marker rather than a new annotation type, so
     *  adding it moves no FRAMEWORK_ID (class shape: runtime/scripts/
     *  build_ids.py) and re-converts no installed app. Checked only for
     *  classes the parent defined, and never for android.* -- the device boot
     *  classpath, which wins over the app on Android too, so parent-first IS
     *  the answer there (this also skips most of the ~1-3 us annotation read
     *  per class). JVM resolution asks once per name (the JVM records the
     *  answer); an explicit loadClass(name) for a parent-defined name repeats
     *  super.loadClass plus the (cached) annotation lookup, ~0.8 us, on every
     *  call, because Java-level delegation never makes this loader the
     *  initiating loader of the parent's class.
     *
     *  COUPLED TO MACAPK_GLSV_PAUSE: a retired shadow was retired BECAUSE a
     *  framework fix made the app's own class safe, and for org.andengine.b.c
     *  that fix is GLSurfaceView's AOSP pause/exit contract. The app's own
     *  EngineLock under the old no-op onPause parks the render thread (AppKit
     *  main) in waitUntilCanDraw after Engine.onDestroy -- measured, the
     *  lockshadow gate's HALF arm. So MACAPK_GLSV_PAUSE=0 also brings the
     *  shadow back; that pair is the configuration that shipped before, and
     *  neither knob alone can build one that never did.
     *  docs/andengine-lock-shadow-retired_2026-09-27.md. */
    public static final String RETIRED_SHADOW = "macapk-retired-shadow";
    static final boolean RETIRE_ENGINE_SHADOWS =
        !"0".equals(System.getenv("MACAPK_RETIRE_ENGINE_SHADOWS"))
        && !"0".equals(System.getenv("MACAPK_GLSV_PAUSE"));

    /** True when {@code c} carries the retired-shadow marker. */
    public static boolean isRetiredShadow(Class<?> c) {
        if (c == null || c.getClassLoader() == null) return false;  // JDK bootstrap
        try {
            Deprecated d = c.getDeclaredAnnotation(Deprecated.class);
            return d != null && d.forRemoval() && RETIRED_SHADOW.equals(d.since());
        } catch (Throwable t) {
            return false;   // unparseable annotations: not a marker
        }
    }

    private Class<?> appCopyOfRetiredShadow(String name, Class<?> shadow, boolean resolve) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> own = findLoadedClass(name);
            if (own == null) {
                try {
                    own = findClass(name);
                } catch (ClassNotFoundException notInApp) {
                    return shadow;   // the app does not bundle it: keep the shadow
                }
                NativeLog.i(TAG, "loadClass(" + name + ") retired shadow -> app's own copy"
                    + " (MACAPK_RETIRE_ENGINE_SHADOWS=0 restores the shadow)");
            }
            if (resolve) resolveClass(own);
            return own;
        }
    }

    /** v1.0.152: tracks whether we've already triggered the bulk
     *  pre-translation of every APK lib. We do it on the first
     *  findLibrary call so the cost is paid once per session. Volatile
     *  + synchronized to avoid duplicate work if the JVM dispatches
     *  multiple System.loadLibrary calls concurrently. */
    private volatile boolean allLibsPretranslated = false;
    private final Object pretranslateLock = new Object();
    // Per-image load bias (libname -> guest-arena byte offset) for the
    // ARM32 multi-.so collision fix. The MAIN lib (largest vaddr footprint)
    // gets bias 0 so single-.so apps and the main lib are bit-identical;
    // each additional .so (e.g. a -freetype / -box2d companion) gets a
    // distinct non-overlapping bias so its segments do not stomp another
    // image in the shared guest arena. Populated by preloadAllApkLibs and
    // read by findLibrary so both translate a given lib at the SAME bias.
    private final java.util.Map<String,Long> libBias =
        new java.util.concurrent.ConcurrentHashMap<>();

    // PUBLIC to mirror AOSP: BaseDexClassLoader.findLibrary is public,
    // and the androidgamesdk GameActivity calls it directly through a
    // BaseDexClassLoader-typed reference (see the comment on our
    // dalvik.system.BaseDexClassLoader.findLibrary).
    @Override
    public String findLibrary(String libname) {
        // v1.0.152: on the first findLibrary call, pre-translate every
        // other .so in the APK's lib/<abi>/ dir so downstream dlopen()
        // calls from within native code can find them. Unity's libmain.so
        // calls dlopen("libunity.so") DIRECTLY (bypassing System.loadLibrary
        // and our findLibrary), which fails unless libunity.dylib is
        // already in lib_native/. preloadDependencies(libname) only
        // catches DT_NEEDED siblings; libmain's DT_NEEDED doesn't list
        // libunity, so libunity must be pre-translated independently.
        // Generic for any APK whose native code loads other native libs
        // via raw dlopen.
        if (!allLibsPretranslated) {
            synchronized (pretranslateLock) {
                if (!allLibsPretranslated) {
                    allLibsPretranslated = true;
                    // DIAGNOSTIC (MACAPK_NO_PRELOAD): skip preloading the guest .so before
                    // pairip runs, to test whether the module count pairip folds (buffer 0x50b60,
                    // ours 9 vs device 0) is our preloaded guest libs -- a real device lazy-loads
                    // game libs AFTER pairip. Default off. 2026-09-16.
                    try { if (System.getenv("MACAPK_NO_PRELOAD") == null) preloadAllApkLibs(); }
                    catch (Throwable t) {
                        NativeLog.w(TAG, "preloadAllApkLibs failed: " + t);
                    }
                }
            }
        }
        // ESCAPE HATCH: MACAPK_SKIP_LIBS=foo,bar forces stub fallback for
        // the listed libraries by name (no "lib" prefix). Used to isolate
        // whether a specific library is the source of a crash -- e.g.
        // MACAPK_SKIP_LIBS=fmod stubs out FMOD without touching anything
        // else. Generic across APKs.
        String skipList = System.getenv("MACAPK_SKIP_LIBS");
        if (skipList != null) {
            for (String s : skipList.split(",")) {
                if (s.trim().equals(libname)) {
                    NativeLog.i(TAG, "findLibrary(" + libname + "): in MACAPK_SKIP_LIBS, using stub");
                    if (stubLibraryPath != null && new File(stubLibraryPath).exists()) {
                        return stubLibraryPath;
                    }
                    return super.findLibrary(libname);
                }
            }
        }

        // Diagnostic ABI override: when MACAPK_FORCE_ABI is set, both the
        // lib/<isa>/ lookup (in locateApkSo) and the translated-dylib
        // cache filename get pinned to that ABI. The cache suffix keeps the
        // default-path lib<name>.dylib intact across A/B runs.
        String forcedAbi = forcedAbi();
        String cacheSuffix = (forcedAbi != null) ? "-" + forcedAbi : "";
        // v1.0.823: an INSTRUMENTED translate must not poison the normal cache.
        // MACAPK_DIFF_VADDRS compiles __macapk_capture_entry hooks into the named
        // functions. The dylib cache is EXISTENCE-based, so without a distinct
        // slot the next ordinary run silently reuses the instrumented build --
        // which is exactly what happened on 2026-07-28: a few diagnostic runs on
        // HCR 1.8.1 left an instrumented libgame.dylib behind, and every launch
        // afterwards SEGV'd in nativeInit, killed the GLThread and rendered
        // BLACK. It looked like a code regression and was not; the committed
        // binaries were fine and only the cache was bad.
        // Giving instrumented output its own suffix makes the two builds
        // co-exist and makes the poisoning impossible in either direction.
        String diffSpec = System.getenv("MACAPK_DIFF_VADDRS");
        if (diffSpec != null && !diffSpec.isEmpty()) {
            cacheSuffix = cacheSuffix + "-diff";
            NativeLog.w(TAG, "findLibrary(" + libname + "): MACAPK_DIFF_VADDRS is set"
                + " -- using a SEPARATE instrumented cache slot (suffix='"
                + cacheSuffix + "'). Instrumented dylibs are never reused by a"
                + " normal run.");
        }
        if (forcedAbi != null) {
            NativeLog.i(TAG, "findLibrary(" + libname + "): MACAPK_FORCE_ABI="
                + forcedAbi + " active (cache suffix='" + cacheSuffix + "')");
        }

        // ESCAPE HATCH: MACAPK_DISABLE_TRANSLATOR=1 skips steps 1 and 2
        // and forces the stub fallback. Used when the translated dylib
        // crashes the JVM during native init and you want the framework's
        // empty GLSurfaceView fallback to render the activity's UI instead.
        boolean disabled = "1".equals(System.getenv("MACAPK_DISABLE_TRANSLATOR"))
                       || "true".equalsIgnoreCase(System.getenv("MACAPK_DISABLE_TRANSLATOR"));
        // Declared out here so step 3a below can distinguish "this APK does not
        // ship the library at all" (AOSP: return null) from "it ships it but we
        // could not translate it" (keep the stub).
        File so = null;
        if (!disabled) {
            // Resolve the APK's own copy up front (one cheap File.exists()
            // stat in the common case) so step 1 below can tell a genuine
            // cache hit from a STALE one. t3-mmap (2026-07-24): a Stage-6
            // arm64 wrapper's constructor is no longer self-contained -- it
            // mmaps/pread's its source .so back off disk at load time
            // (__macapk_arm64_native_load_from_path, arm64_native.cpp), so
            // a cached wrapper whose source .so has been deleted (data-dir
            // tampering, a forced partial re-extract) is NOT actually
            // loadable: handing it to System.load() as-is would dyld-load
            // fine and then fail every trampoline (br to a null slot ->
            // SIGSEGV), with no chance to recover.
            so = locateApkSo(libname);

            // 1. Cache hit -- already translated on a previous boot, AND
            //    (for a Stage-6 arm64 wrapper) its source .so is still
            //    present. stage6SourceStillPresent() -- NOT `so != null` --
            //    is the right test here: `so` is locateApkSo()'s ABI-
            //    FALLBACK pick (arm64-v8a, else armeabi-v7a, else...), which
            //    answers "could SOMETHING be translated for this libname
            //    right now", not "does THIS cached wrapper's own dependency
            //    still exist". A first version of this fix used `so != null`
            //    directly and it was WRONG: on an app that ships both ABIs
            //    (e.g. GD Lite), deleting only the arm64-v8a .so left
            //    `so` pointing at the still-present armeabi-v7a copy, so the
            //    check passed, the (now-broken) cached Stage-6 wrapper got
            //    System.load()'d, dyld succeeded, and the JVM's own
            //    JNI_OnLoad lookup+call landed on an unresolved (zero)
            //    trampoline slot -- SIGSEGV pc=0 inside
            //    Java_jdk_internal_loader_NativeLibraries_load. Reproduced,
            //    root-caused via the hs_err_pid*.log stack, and fixed by
            //    checking arm64-v8a specifically instead of falling back
            //    across ABIs -- see stage6SourceStillPresent()'s own
            //    comment for the full rationale.
            File cached = new File(appDir, "lib_native/lib" + libname + cacheSuffix + ".dylib");
            boolean sourcePresent = cached.exists() && stage6SourceStillPresent(libname);
            if (sourcePresent && nativeIdMatches(cached)) {
                NativeLog.i(TAG, "findLibrary(" + libname + ") -> cached " +
                                  cached.getAbsolutePath());
                preloadDependencies(libname);
                return cached.getAbsolutePath();
            }
            if (cached.exists() && !sourcePresent) {
                // (A native-id mismatch is reported by nativeIdMatches itself.)
                NativeLog.w(TAG, "findLibrary(" + libname + "): cached " +
                    cached.getAbsolutePath() + " exists but its arm64 " +
                    "source .so is missing from the installed lib dir -- " +
                    "treating as a cache miss");
            }
            // Losing the stamp race is not an error: the .dylib is simply
            // re-translated below and re-stamped, which is the correct
            // behaviour for an artifact whose producer changed.

            // 2. Translate from the APK's lib/<arch>/lib<name>.so.
            if (so != null && translatorBin != null && translatorBin.canExecute()) {
                // Pre-load this library's DT_NEEDED dependencies before
                // we translate + dlopen ourselves, so the dependent
                // library's __mod_init_func can resolve symbols from
                // libc++_shared / libopenal / etc. via dlsym(RTLD_DEFAULT)
                // when its __macapk_arm64_native_load runs.
                preloadDependencies(libname);
                cached.getParentFile().mkdirs();
                if (translate(so, cached, libBias.getOrDefault(libname, 0L))) {
                    NativeLog.i(TAG, "findLibrary(" + libname + ") -> translated " +
                                      cached.getAbsolutePath());
                    // Stamp AFTER a successful translation only. An unstamped
                    // dylib re-translates next launch, which is the safe
                    // direction; a wrongly-stamped one would be reused forever.
                    writeNativeIdStamp(cached);
                    return cached.getAbsolutePath();
                }
                NativeLog.w(TAG, "findLibrary(" + libname + "): translation failed");
            }
        } else {
            NativeLog.i(TAG, "findLibrary(" + libname + "): translator disabled by env, using stub");
        }

        // 3a. The APK does not ship this library for ANY abi -> AOSP returns
        //     NULL here, and System.loadLibrary throws UnsatisfiedLinkError.
        //     Handing back the stub instead BREAKS THE PROBE-AND-FALL-BACK
        //     IDIOM, which is common: an app tries an optional/variant .so,
        //     catches the failure, and loads its real one. A stub makes the
        //     probe SUCCEED, so the fallback never runs and the real library
        //     is never loaded -- every native method then stays unbound and
        //     the app dies much later with a confusing UnsatisfiedLinkError
        //     naming some unrelated method.
        //
        //     Measured 2026-07-30, Signal (libsignal). Its own bytecode,
        //     org/signal/libsignal/internal/Native.loadNativeCode():
        //          6: ldc_w "signal_jni_testing"
        //         12: invokespecial loadLibrary:(Ljava/lang/String;)V
        //         15: return                      <- taken when the probe "works"
        //         16: ldc_w "signal_jni"          <- only reached from the handler
        //         33: goto 16
        //       Exception table:  from 6 to 15 target 32 type any
        //     `libsignal_jni_testing.so` is NOT in the APK (0 entries), so on
        //     real Android the probe throws and the real signal_jni loads. With
        //     the stub it returned normally at bci 15, and Signal then failed at
        //     `UnsatisfiedLinkError: Native.initializeLibrary()`, aborting
        //     ApplicationContext.onCreate.
        //
        //     `so == null` is exactly "not in the APK": locateApkSo() already
        //     falls back across every abi. The stub still covers its real
        //     purpose below -- a library that IS shipped but that we failed to
        //     translate -- so an app whose .so we cannot handle still boots.
        //     MACAPK_DISABLE_TRANSLATOR deliberately wants the stub for
        //     everything, so it is exempt.
        if (!disabled && so == null) {
            NativeLog.i(TAG, "findLibrary(" + libname + "): not shipped in this APK"
                + " (no lib" + libname + ".so in the installed lib dir)"
                + " -> returning null, as AOSP does. System.loadLibrary will throw"
                + " UnsatisfiedLinkError, which is what an app probing for an"
                + " optional library expects to catch.");
            return null;
        }

        // 3b. Shipped but unusable (translation failed, or the translator was
        //     disabled): fall back to the stub dylib so dlopen still succeeds
        //     and the app's <clinit> does not die on the spot.
        if (stubLibraryPath != null && new File(stubLibraryPath).exists()) {
            NativeLog.i(TAG, "findLibrary(" + libname + ") -> stub " + stubLibraryPath);
            return stubLibraryPath;
        }
        NativeLog.w(TAG, "findLibrary(" + libname + "): no stub at " + stubLibraryPath);
        return super.findLibrary(libname);
    }

    /**
     * Populate the app's native-lib directory with macOS-loadable dylibs so a
     * loader that dlopen()s libraries DIRECTLY out of that directory finds them.
     *
     * WHY. Facebook SoLoader / NativeLoader (Instagram, Threads, Messenger, and
     * any app using fbjni) does NOT go through System.loadLibrary ->
     * ClassLoader.findLibrary. It builds its own SoSource list -- a
     * DirectorySoSource rooted at ApplicationInfo.nativeLibraryDir (the
     * installed lib/<isa>) -- and, for "libX", looks for the file
     * System.mapLibraryName("X") in that directory and System.load()s its
     * absolute path. On this JVM mapLibraryName returns "libX.dylib", but the
     * directory holds only the APK's ELF "libX.so", so SoLoader throws
     * SoLoaderDSONotFoundError and the app's whole native bring-up (and its DI:
     * IgSessionManager, IgExecutor) collapses -- nothing ever asked us to
     * translate the .so, because findLibrary was bypassed. Measured on Instagram
     * 445.0.0.45.83: 15 bootstrap .so in lib/arm64-v8a (libsuperpack-jni is the
     * first, the superpack decompressor), none loadable.
     *
     * FIX. For each libX.so in soDir, translate it (reusing findLibrary's
     * lib_native cache + native-id stamp) and expose it as soDir/libX.dylib via
     * a SYMLINK to the cached dylib. SoLoader then finds soDir/libX.dylib and
     * System.load()s it; a later System.loadLibrary("X") canonicalises to the
     * SAME cached file (File.getCanonicalPath resolves the symlink), so the JVM
     * loads ONE image -- no double JNI_OnLoad.
     *
     * GENERIC + gated: only runs when the app actually ships SoLoader (its
     * classes are NOT obfuscated -- com.facebook.soloader.SoLoader), so the game
     * fleet and every lazy-loading app pay one findResource and skip. No
     * package-name branch. The superpack ARCHIVE (assets/lib/libs.spo, unpacked
     * at runtime by libsuperpack-jni) is a separate, later layer; this makes the
     * bootstrap set loadable so libsuperpack-jni can run at all.
     */
    public void prepareDirectDlopenLibs(File soDir) {
        if (soDir == null || !soDir.isDirectory()) return;
        if (translatorBin == null || !translatorBin.canExecute()) return;
        File[] sos = soDir.listFiles(
                (d, n) -> n.startsWith("lib") && n.endsWith(".so"));
        if (sos == null || sos.length == 0) return;
        // Gate: does this app use a direct-dlopen loader? SoLoader keeps its own
        // package name even under R8, so a class-resource probe is a reliable,
        // cheap signal. Probe CORE SoLoader classes (SysUtil / MinElf), NOT
        // SoLoader.class itself -- in a split bundle (Instagram) the top-level
        // SoLoader.class lands in a secondary dex our merged classes.jar does not
        // carry, while the core helpers are in base. Fall back to the presence of
        // the superpack loader .so in this very directory. Nothing else in the
        // tree reads the extra dylibs we drop, so a false negative just leaves
        // an app exactly as it is today; a false positive costs a few translations.
        boolean usesSoLoader =
                findResource("com/facebook/soloader/SysUtil.class") != null
             || findResource("com/facebook/soloader/MinElf.class") != null
             || findResource("com/facebook/soloader/nativeloader/NativeLoader.class") != null
             || new File(soDir, "libsuperpack-jni.so").exists()
             || new File(soDir, "libfb.so").exists();
        if (!usesSoLoader) return;
        // Everything is EXPOSED in the plain DirectorySoSource dir (soDir =
        // the installed lib/<isa>). Two SoLoader source kinds see our files differently:
        //   * DirectorySoSource (flags=0) System.load()s the file as-is -- it
        //     accepts our Mach-O dylib. This is where the bootstrap libs live.
        //   * SuperpackSoSource/UnpackingSoSource (flags=1) VALIDATES its output
        //     with MinElf (checks the ELF magic) to detect a corrupt unpack; a
        //     Mach-O reads as magic 0xfeedfacf != ELF 0x464c457f, so it declares
        //     "CorruptedLibFileError" and RE-UNPACKS, wiping anything we put in
        //     lib-compressed. So we must NOT touch lib-compressed; instead we
        //     translate the .so it unpacked and expose the .dylib in soDir. When
        //     SoLoader looks up a superpack lib it fails to find the .dylib in the
        //     (untouched, still-ELF) SuperpackSoSource and falls through to the
        //     DirectorySoSource, which loads our Mach-O.
        // soname ("libX.so") -> {sourceSo, cachedDylib}, for every lib we exposed.
        java.util.LinkedHashMap<String, File[]> exposed = new java.util.LinkedHashMap<>();
        int prepared = exposeDylibsForSoLoader(soDir, soDir, exposed);
        // SUPERPACK: the bulk of a Facebook app's native libs ship compressed in
        // assets/lib/libs.spo, unpacked by libsuperpack-jni into these sibling
        // dirs at SoLoader.init as ELF .so. The unpack is CACHED across runs (a
        // dso_state marker), so a first launch populates them and the next launch
        // translates them here, before SoLoader.init. Empty on the very first
        // launch (nothing to do); present and translated thereafter.
        for (File root : soLoaderRoots()) {
            prepared += exposeDylibsForSoLoader(new File(root, "lib-compressed"), soDir, exposed);
            prepared += exposeDylibsForSoLoader(new File(root, "lib-main"), soDir, exposed);
        }
        NativeLog.i(TAG, "prepareDirectDlopenLibs: SoLoader app -- exposed "
                + prepared + " dylib(s) in " + soDir.getName()
                + " so SoLoader's direct dlopen finds them");
        // Now MAP the DEPENDENCY libs into the loader's image registry, in
        // DT_NEEDED order, BEFORE SoLoader loads anything. WHY: a lib SoLoader
        // loads (libtigonnetworkinfo, libearlystartup) resolves its relocations
        // against loaded_images(), but its DT_NEEDED providers (libxplat_Tigon...,
        // libc++_shared) are not there yet -- the translator's cross-lib sibling
        // scan does not reliably emit an LC_LOAD_DYLIB edge across our lib-compressed
        // vs installed-lib split, and SoLoader cannot read our Mach-O's DT_NEEDED to order
        // them first. We only pre-map the libs that ARE depended upon (leaf
        // libraries, safe to init early), NOT the top-level libs SoLoader itself
        // loads -- so we do not run a top-level lib's init before the app expects.
        // ⚠ MAP ONLY, never System.load (2026-09-29): this runs before ANY app
        // code, and System.load runs the lib's JNI_OnLoad -- which Android's
        // linker never does for a DT_NEEDED dependency. See linkDependencyOnly.
        // (Only for deps that sit in soDir, where Android's linker maps them
        // implicitly; deps from SoLoader's unpacking roots keep System.load.)
        premapDependencyLibs(exposed, soDir);
    }

    /**
     * SECOND-PHASE SoLoader exposure: run AFTER the app's attachBaseContext, i.e.
     * after SoLoader.init has EXTRACTED the superpack archive into lib-compressed,
     * and BEFORE the app loads any superpack lib (in a ContentProvider or
     * Application.onCreate).
     *
     * WHY A SECOND PASS IS REQUIRED. prepareDirectDlopenLibs above runs at Boot,
     * before the guest -- but a Facebook/Meta app's BULK of native libs
     * (libearlystartup, the AR/effects/graphics libs: 55 on Instagram
     * 445.0.0.45.83) ship compressed in assets/lib/libs.spo and are unpacked into
     * lib-compressed only when the app calls SoLoader.init, which happens INSIDE
     * attachBaseContext -- roughly 2000 log lines AFTER the Boot pass. So on a
     * COLD launch lib-compressed is EMPTY at Boot, the Boot pass exposes only the
     * ~15 bootstrap .so, and the 55 superpack dylibs are never exposed. The first
     * superpack dlopen in onCreate (profilo's ExternalApiProvider ->
     * libearlystartup) then throws SoLoaderDSONotFoundError, Application.onCreate
     * aborts, and the process dies (exit 10 via the uncaught-exception kill).
     *
     * It only ever "worked" before when a PRIOR launch had left lib-compressed
     * populated (a warm cache), which the Boot pass could then translate. But
     * EVERY Mac.apk cache invalidation (a new converter/framework/native build id
     * -- i.e. every update) wipes lib-compressed, and a fresh install starts
     * empty, so the first launch after an install or an update ALWAYS failed. The
     * warm/cold asymmetry is exactly why v1.0.1583's dev-session (which had
     * launched IG repeatedly) saw it boot deep while a real first launch did not.
     *
     * This pass re-runs the lib-compressed/lib-main exposure now that SoLoader has
     * populated them, and pre-maps the newly-exposed libs' DT_NEEDED providers --
     * exactly what the Boot pass does on a warm launch, just at the correct moment
     * on a cold one. GENERIC (any SoLoader/superpack app: Instagram, Threads,
     * Messenger, Facebook), no package name. IDEMPOTENT: a no-op for a
     * non-SoLoader app (no lib-compressed dir) and for an already-warm launch
     * (every dylib already exposed, native-id matches -> skipped, and every
     * dependency already mapped -> canonical-path no-op).
     */
    /** v1.0.1641: the app's DATA dir, published by Boot. SoLoader builds
     *  lib-main / lib-compressed under ApplicationInfo.dataDir, which names the
     *  data dir from this version on (it named the code dir, appDir, before). */
    private volatile File macapkDataDir;

    public void macapkSetDataDir(File dataDir) { this.macapkDataDir = dataDir; }

    /** Where SoLoader's unpack dirs can be: the data dir first (where
     *  ApplicationInfo.dataDir points now), then appDir (pre-v1.0.1641 unpacks,
     *  until Boot's code-dir migration has moved them). */
    private java.util.List<File> soLoaderRoots() {
        java.util.List<File> roots = new java.util.ArrayList<>(2);
        File d = macapkDataDir;
        if (d != null) roots.add(d);
        if (appDir != null && (d == null || !appDir.equals(d))) roots.add(appDir);
        return roots;
    }

    public void prepareDirectDlopenSuperpack(File soDir) {
        if (soDir == null || !soDir.isDirectory()) return;
        if (translatorBin == null || !translatorBin.canExecute()) return;
        // Self-gating: only a SoLoader app that has run its superpack extraction
        // has these dirs. A non-SoLoader app has neither, so this returns without
        // touching anything.
        java.util.LinkedHashMap<String, File[]> exposed = new java.util.LinkedHashMap<>();
        int prepared = 0;
        boolean any = false;
        for (File root : soLoaderRoots()) {
            File libCompressed = new File(root, "lib-compressed");
            File libMain       = new File(root, "lib-main");
            if (!libCompressed.isDirectory() && !libMain.isDirectory()) continue;
            any = true;
            prepared += exposeDylibsForSoLoader(libCompressed, soDir, exposed);
            prepared += exposeDylibsForSoLoader(libMain, soDir, exposed);
        }
        if (!any) return;
        if (prepared == 0) return;   // nothing extracted yet (or not a superpack app)
        NativeLog.i(TAG, "prepareDirectDlopenSuperpack: exposed " + prepared
                + " superpack dylib(s) in " + soDir.getName()
                + " post-attachBaseContext so onCreate's direct dlopen finds them");
        // The superpack libs' bootstrap deps (libc++_shared, ...) were already
        // mapped by the Boot pass and are correctly skipped here (not in this
        // exposed map). Only superpack->superpack DT_NEEDED edges are mapped now.
        // Every one of these sits in an UNPACKING root (lib-compressed/lib-main),
        // so all of them keep System.load (see premapOne's scope check).
        premapDependencyLibs(exposed, soDir);
    }

    /** Map every lib that is DT_NEEDED by another exposed lib (its dependencies),
     *  deepest-first, into the arm64 loader's image registry, so a lib SoLoader
     *  loads later finds its providers already there. Only depended-upon libs are
     *  mapped; a top-level lib (in nobody's DT_NEEDED) is left for SoLoader. */
    private int[] premapDependencyLibs(java.util.Map<String, File[]> exposed, File linkerDir) {
        // Which sonames are depended upon by some OTHER exposed lib?
        java.util.Set<String> neededByOthers = new java.util.HashSet<>();
        for (File[] info : exposed.values()) {
            try {
                for (String dep : ElfReader.readDtNeeded(info[0])) neededByOthers.add(dep);
            } catch (Throwable ignored) {}
        }
        java.util.Set<String> done = new java.util.HashSet<>();
        java.util.Set<String> inProgress = new java.util.HashSet<>();
        // counts: [0] mapped, [1] failed, [2] link-only, [3] System.load (unpacking
        // root or =0), [4] link-only wanted but NativeLinker unbound -> System.load.
        int[] counts = new int[5];
        for (String soname : exposed.keySet()) {
            if (neededByOthers.contains(soname))
                premapOne(soname, exposed, done, inProgress, counts, linkerDir);
        }
        String how;
        if (!PREMAP_LINK_ONLY) {
            how = " [MACAPK_SOLOADER_PREMAP_LINK_ONLY=0: System.load, JNI_OnLoad ran now]";
        } else {
            how = " [link-only " + counts[2] + ", System.load " + counts[3]
                + (counts[3] > 0 && counts[4] < counts[3] ? " (unpacking-root deps: SoLoader loads those itself on Android)" : "")
                + (counts[4] > 0 ? ", " + counts[4] + " FELL BACK: NativeLinker unbound, JNI_OnLoad ran early" : "")
                + "]";
        }
        NativeLog.i(TAG, "premapDependencyLibs: mapped " + counts[0]
                + " dependency lib(s)" + (counts[1] > 0 ? " (" + counts[1] + " failed)" : "")
                + " in DT_NEEDED order so SoLoader-loaded libs resolve their deps" + how);
        return counts;
    }

    /**
     * MACAPK_SOLOADER_PREMAP_LINK_ONLY (default ON; =0 restores the old
     * System.load premap byte-for-byte). docs/soloader-premap-jni-onload-timing_2026-09-29.md.
     *
     * WHAT WAS WRONG. premapOne used loadIntoThisLoader, i.e. System.load, and
     * the JDK runs JNI_OnLoad inside System.load (jdk21u NativeLibraries.c:
     * 130-139). The Boot pass runs before the app's Application exists, so every
     * dependency's JNI_OnLoad ran before the app had called SoLoader.init /
     * NativeLoader.init. Android never does that: the linker maps a DT_NEEDED
     * dependency and runs only its constructors (bionic linker_soinfo.cpp
     * soinfo::call_constructors), and JNI_OnLoad is called only by
     * JavaVMExt::LoadNativeLibrary for a library the app itself loads (art
     * runtime/jni/java_vm_ext.cc:1120-1135). Measured on Efteling 5.24.0 (React
     * Native + Hermes; 13 premapped deps, 6 with a JNI_OnLoad incl. libfbjni and
     * libreactnative): fbjni's JNI_OnLoad initialised ThreadScopeSupport, whose
     * <clinit> is NativeLoader.loadLibrary("fbjni") -> "NativeLoader has not been
     * initialized" (SoLoader NativeLoader.java:42-46) -> the class is erroneous
     * for the rest of the run -> an fbjni C++ throw with no handler ->
     * std::terminate -> SIGABRT before the first frame.
     *
     * NOW. dlopen alone (NativeLinker, HotSpot's RTLD_LAZY): the Stage-6
     * constructor maps the ELF into loaded_images() exactly as before, which is
     * all this pass exists for; when the app later System.load()s the same file
     * (SoLoader, after its init) the JVM registers it with the app's loader and
     * runs JNI_OnLoad then, at the moment Android would. The path is CANONICAL
     * because the JDK canonicalises before its own dlopen, so both reach dyld
     * as the same string.
     *
     * SCOPE. Link-only applies only to a dependency in the app's own lib dir
     * (where the Android linker maps it implicitly). A dependency from an
     * UNPACKING root (lib-compressed / lib-main, superpack) keeps System.load:
     * on a device SoLoader's UnpackingSoSource (RESOLVE_DEPENDENCIES) loads each
     * DT_NEEDED dep itself, which registers it with the app loader and runs its
     * JNI_OnLoad, and on Mac.apk nothing else does that for the superpack libs
     * (they are served by the flags-0 fall-through DirectorySoSource). OPEN, the
     * faithful end state: System.load those deps at the moment the app loads
     * their dependent (RESOLVE_DEPENDENCIES timing).
     */
    private static final boolean PREMAP_LINK_ONLY =
        !"0".equals(System.getenv("MACAPK_SOLOADER_PREMAP_LINK_ONLY"));

    /** @return true = mapped link-only; false = NativeLinker was unbound and
     *  the dependency was System.load()ed instead (the old, JNI_OnLoad-early path). */
    private boolean linkDependencyOnly(File dylib) {
        String path;
        try { path = dylib.getCanonicalPath(); }
        catch (IOException e) { path = dylib.getAbsolutePath(); }
        String err;
        try {
            err = NativeLinker.nativeLinkOnly(path);
        } catch (UnsatisfiedLinkError e) {
            // The runtime binary predates the native: keep the old behaviour
            // for this run rather than leaving the dependency unmapped.
            NativeLog.w(TAG, "linkDependencyOnly: NativeLinker unbound (" + e
                + "); falling back to System.load for " + dylib.getName());
            loadIntoThisLoader(dylib.getAbsolutePath());
            return false;
        }
        if (err != null) throw new UnsatisfiedLinkError("dlopen " + path + ": " + err);
        return true;
    }

    private void premapOne(String soname, java.util.Map<String, File[]> exposed,
                           java.util.Set<String> done, java.util.Set<String> inProgress,
                           int[] counts, File linkerDir) {
        if (done.contains(soname) || inProgress.contains(soname)) return;  // done / cycle guard
        File[] info = exposed.get(soname);
        if (info == null) return;   // a system lib we don't bundle -- the loader/host resolves it
        inProgress.add(soname);
        try {
            for (String dep : ElfReader.readDtNeeded(info[0]))
                premapOne(dep, exposed, done, inProgress, counts, linkerDir);
        } catch (Throwable ignored) {}
        inProgress.remove(soname);
        try {
            // LINK-ONLY exactly where Android's linker maps the dependency
            // implicitly: a DT_NEEDED dep in the app's own lib dir. A dep that
            // came from SoLoader's UNPACKING roots (lib-compressed / lib-main,
            // i.e. superpack) is NOT loaded implicitly on a device: its
            // UnpackingSoSource has RESOLVE_DEPENDENCIES set and SoLoader
            // System.load()s each DT_NEEDED dep itself, registering it with the
            // app loader and running its JNI_OnLoad. On Mac.apk those libs are
            // served by the fall-through DirectorySoSource (flags 0 for a
            // third-party app), which loads no dependency, so this premap is the
            // only thing that does -- and it must register + run JNI_OnLoad like
            // SoLoader would. (docs/soloader-premap-jni-onload-timing_2026-09-29.md s3)
            boolean scoped = linkerDir != null && linkerDir.getAbsoluteFile().equals(info[0].getAbsoluteFile().getParentFile()); // SCOPE-CHECK
            if (PREMAP_LINK_ONLY && scoped) {
                if (linkDependencyOnly(info[1])) {   // maps into loaded_images(), no JNI_OnLoad
                    counts[2]++;
                    noteLinkOnlyDep(soname, info[0]);
                } else {
                    counts[3]++; counts[4]++;
                }
            } else {
                loadIntoThisLoader(info[1].getAbsolutePath());  // unpacking root, or =0: the pre-2026-09-29 path
                counts[3]++;
            }
            counts[0]++;
        } catch (Throwable t) {
            counts[1]++;
            NativeLog.w(TAG, "premapOne(" + soname + "): " + t);
        }
        done.add(soname);
    }

    /** A link-only dependency's Java_* exports are invisible to HotSpot unless
     *  the app System.loads that very lib by name: HotSpot's native lookup
     *  (ClassLoader.findNative) runs dlsym only over libraries REGISTERED with
     *  the class's loader, whereas ART's dlsym(handle) walks the dependent's
     *  whole DT_NEEDED tree (bionic linker.cpp dlsym_handle_lookup_impl ->
     *  walk_dependencies_tree). Nothing is wrong until an app calls a native of
     *  such a lib, so leave a one-grep breadcrumb for the UnsatisfiedLinkError
     *  it would produce: "premap: link-only dep". */
    private static void noteLinkOnlyDep(String soname, File srcSo) {
        int jni = ElfReader.countDefinedDynsyms(srcSo, "Java_");
        int onload = ElfReader.countDefinedDynsyms(srcSo, "JNI_OnLoad");
        if (jni <= 0 && onload <= 0) return;   // nothing HotSpot would need to find
        String msg = "premap: link-only dep " + soname + " exports " + (jni < 0 ? "?" : String.valueOf(jni))
            + " Java_* / JNI_OnLoad=" + (onload > 0 ? "y" : onload == 0 ? "n" : "?")
            + "; reachable only if the app System.loads it by name";
        if (jni > 0) NativeLog.w(TAG, msg); else NativeLog.i(TAG, msg);
    }

    /** Translate every libX.so in {@code srcDir} and expose it as
     *  {@code exposeDir/libX.dylib} (a symlink to the lib_native cache), the name
     *  SoLoader's DirectorySoSource looks for. srcDir and exposeDir are the same
     *  for the bootstrap dir; for superpack they differ (read from lib-compressed,
     *  expose in the plain DirectorySoSource dir -- see the caller's comment).
     *  Returns how many were exposed. Safe on a missing/empty dir. Reuses
     *  findLibrary's cache + native-id stamp. */
    private int exposeDylibsForSoLoader(File srcDir, File exposeDir,
                                        java.util.Map<String, File[]> exposed) {
        if (srcDir == null || !srcDir.isDirectory()) return 0;
        if (exposeDir == null || !exposeDir.isDirectory()) return 0;
        File[] sos = srcDir.listFiles((d, n) -> n.startsWith("lib") && n.endsWith(".so"));
        if (sos == null || sos.length == 0) return 0;
        // The bootstrap dir may itself be sealed; the expose dir must be writable.
        if (!exposeDir.canWrite()) exposeDir.setWritable(true, true);
        File cacheDir = new File(appDir, "lib_native");
        cacheDir.mkdirs();
        int prepared = 0, failed = 0;
        for (File so : sos) {
            String fileBase = so.getName().substring(0, so.getName().length() - 3); // "libX"
            String libname  = fileBase.substring(3);                                // "X"
            File dylibInDir = new File(exposeDir, fileBase + ".dylib");
            File cached     = new File(cacheDir, fileBase + ".dylib");
            try {
                // v1.0.1641: also keyed on the SOURCE .so (size + mtime). SoLoader
                // re-extracts newer .so files under the SAME names after an app
                // update; a cache keyed on name + translator id alone served the
                // OLD version's code.
                boolean srcOk = soSourceMatches(cached, so);
                if (srcOk && dylibInDir.exists() && nativeIdMatches(cached)) {
                    prepared++;
                    if (exposed != null) exposed.put(so.getName(), new File[]{so, cached});
                    continue;
                }
                if (!(srcOk && cached.exists() && nativeIdMatches(cached))) {
                    if (!translate(so, cached, libBias.getOrDefault(libname, 0L))) {
                        failed++;
                        continue;   // translate() logs (incl. the non-ELF skip)
                    }
                    writeNativeIdStamp(cached);
                    writeSoSourceStamp(cached, so);
                }
                // Symlink so a later System.loadLibrary("X") canonicalises to the
                // same file (one image, no double JNI_OnLoad).
                java.nio.file.Path link = dylibInDir.toPath();
                java.nio.file.Files.deleteIfExists(link);   // never follows a symlink
                java.nio.file.Files.createSymbolicLink(link, cached.toPath());
                prepared++;
                if (exposed != null) exposed.put(so.getName(), new File[]{so, cached});
            } catch (Throwable t) {
                failed++;
                NativeLog.w(TAG, "exposeDylibsForSoLoader(" + so.getName() + "): " + t);
            }
        }
        if (prepared > 0 || failed > 0)
            NativeLog.i(TAG, "exposeDylibsForSoLoader(" + srcDir.getName() + "): "
                    + prepared + " exposed"
                    + (failed > 0 ? ", " + failed + " skipped/failed" : ""));
        return prepared;
    }

    /** Recognised R-class subtype names. Each corresponds to a `<public type="...">`
     *  bucket in apktool's public.xml. */
    private static final java.util.Set<String> R_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
        "id", "layout", "drawable", "string", "color", "dimen",
        "style", "attr", "bool", "integer", "array", "xml",
        "anim", "animator", "interpolator", "menu", "mipmap",
        "plurals", "raw", "transition", "styleable", "font",
        "fraction", "navigation"
    ));

    /** v1.0.75: heavily-R8-shrunk APKs (e.g. F-Droid) keep `public static int`
     *  R$id / R$layout / R$drawable / etc. fields BUT strip the <clinit> that
     *  would set them to the per-app aapt-assigned values. At runtime every
     *  `R.id.foo` reads 0, and `view.findViewById(0)` returns null, which
     *  takes out AppCompat's ensureSubDecor with a setDecorPadding NPE on
     *  the missing ContentFrameLayout.
     *
     *  The framework on real Android avoids this because R8's
     *  shrinkResources phase rewrites every getstatic into a literal int
     *  load using the final resource ID -- inlining the same constant the
     *  stripped <clinit> would have written. In our pipeline we don't have
     *  that pass; instead we patch the fields from public.xml at class-
     *  definition time. Resources.macapkGetResourceIndex() is a static
     *  reachable from anywhere, populated by Boot before any APK class
     *  loads. */
    private static void maybePopulateRClassFields(Class<?> c, String dotted) {
        if (c == null || dotted == null) return;
        int dollar = dotted.indexOf('$');
        if (dollar <= 1) return;
        String baseTail = dotted.substring(0, dollar);
        if (!baseTail.endsWith(".R") && !baseTail.equals("R")) return;
        String type = dotted.substring(dollar + 1);
        if (!R_TYPES.contains(type)) return;
        Object idx;
        try {
            idx = android.content.res.Resources.macapkGetResourceIndex();
        } catch (Throwable t) { return; }
        if (idx == null) return;
        java.lang.reflect.Method idOfName;
        try {
            idOfName = idx.getClass().getMethod(
                "idOfName", String.class, String.class, String.class);
        } catch (NoSuchMethodException e) { return; }
        int set = 0;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mods)) continue;
            if (java.lang.reflect.Modifier.isFinal(mods)) continue;
            if (f.getType() != int.class) continue;
            try {
                Object id = idOfName.invoke(idx, f.getName(), type, null);
                if (id instanceof Integer && (Integer) id != 0) {
                    f.setAccessible(true);
                    f.setInt(null, (Integer) id);
                    set++;
                }
            } catch (Throwable ignored) {}
        }
        if (set > 0) {
            NativeLog.i(TAG, "R-init: " + dotted + " populated " + set + " field(s)");
        }
    }

    // ----------------- pairip Java method-indirection registry ----------------
    // Kill switch (set before launch, so the initial-environ snapshot sees it).
    private static final boolean PAIRIP_WIRE_OFF =
        System.getenv("MACAPK_PAIRIP_WIRE_REGISTRY_OFF") != null;
    // Lazily-computed, per-loader: is this a pairip-wrapped app? Signalled, like
    // Boot's gate registration, by the presence of com.pairip.VMRunner -- NOT by
    // MACAPK_PAIRIP_APP, which main.cpp setenv()s AFTER the JVM starts, so
    // System.getenv cannot see it.
    private volatile Boolean isPairipApp;

    private boolean isPairipApp() {
        Boolean v = isPairipApp;
        if (v != null) return v;
        boolean r = false;
        try { r = findResource("com/pairip/VMRunner.class") != null; }
        catch (Throwable ignore) {}
        isPairipApp = r;
        return r;
    }

    /** Install the pairip method-indirection Methods this class's protected
     *  methods read -- the registry population the device's startup VM does and
     *  the integrity gate bypasses (see ClassFileRewriter.collectPairipRegistryWirings
     *  for the mechanism). Runs after defineClass, reflectively, leaving the
     *  app's bytecode untouched: the protected method's own Method.invoke then
     *  runs the app's genuine extracted body (e.g. UnityPlayerActivity.onCreate,
     *  which creates UnityPlayer). Faithful -- not a VM bypass, a .text patch,
     *  or a forgery. Gated to pairip apps; never throws. */
    private void wirePairipRegistry(Class<?> defined, byte[] raw, String dotted) {
        if (PAIRIP_WIRE_OFF || defined == null || raw == null) return;
        if (!isPairipApp()) return;
        java.util.List<ClassFileRewriter.PairipWiring> wirings;
        try {
            wirings = ClassFileRewriter.collectPairipRegistryWirings(raw, dotted);
        } catch (Throwable t) { return; }
        if (wirings == null || wirings.isEmpty()) return;
        for (ClassFileRewriter.PairipWiring w : wirings) {
            try {
                java.lang.reflect.Method target = null;
                for (java.lang.reflect.Method dm : defined.getDeclaredMethods()) {
                    if (dm.getName().equals(w.extractedMethod)) { target = dm; break; }
                }
                if (target == null) continue;
                try { target.setAccessible(true); } catch (Throwable ignore) {}
                Class<?> holder = Class.forName(
                    w.holderInternal.replace('/', '.'), false, this);
                java.lang.reflect.Field f = holder.getDeclaredField(w.fieldName);
                if (f.getType() != java.lang.reflect.Method.class) continue;
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); } catch (Throwable ignore) {}
                if (f.get(null) != null) continue;      // device-set or already wired
                f.set(null, target);
                NativeLog.i(TAG, "pairip registry: wired "
                    + w.holderInternal.replace('/', '.') + "." + w.fieldName
                    + " -> " + dotted + "." + w.extractedMethod);
            } catch (Throwable t) {
                if ("1".equals(System.getenv("MACAPK_DEBUG_REWRITER"))) {
                    NativeLog.w(TAG, "pairip registry: wiring "
                        + w.holderInternal + "." + w.fieldName + " failed: " + t);
                }
            }
        }
    }

    /** Set of libnames currently in a preload chain. Prevents infinite
     *  recursion if two libraries reference each other in DT_NEEDED. */
    private final java.util.Set<String> preloading =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** v1.0.152: walk the APK's lib/<abi>/ dir and translate EVERY .so
     *  into lib_native/lib<name>.dylib. Unity's libmain calls
     *  dlopen("libunity.so") directly after JNI loadLibrary("main"),
     *  bypassing our findLibrary. To make that dlopen succeed, the
     *  cached .dylib must exist before libmain's native init runs.
     *  preloadDependencies handles DT_NEEDED chains but Unity's libmain
     *  DT_NEEDED doesn't include libunity (libunity is loaded
     *  lazily at runtime). So we eagerly translate every .so in the
     *  APK on the first findLibrary call.
     *
     *  Cost: O(libs) translator invocations, each producing a Stage 6
     *  arm64 wrapper around the .so. For Crossy Road's 15 native libs
     *  (libunity, libil2cpp, libcrashlytics, libFirebase*, ...) this
     *  is ~30-60 sec on first boot (mostly file I/O for the 87 MB
     *  libil2cpp). Subsequent boots cache-hit so this is one-time.
     *
     *  Failures translate to skipping that one lib (preloadDependencies
     *  / direct dlopen of the .dylib will return whatever was already
     *  there). Doesn't fail the whole boot.
     */
    private void preloadAllApkLibs() {
        // The one installed isa dir (same resolution as locateApkSo).
        File abiDir = nativeLibDir();
        if (abiDir == null) return;
        String forcedAbi = forcedAbi();
        String cacheSuffix = (forcedAbi != null) ? "-" + forcedAbi : "";
        File[] sos = abiDir.listFiles((dir, name) ->
            name.startsWith("lib") && name.endsWith(".so"));
        if (sos == null || sos.length == 0) return;
        NativeLog.i(TAG, "preloadAllApkLibs: " + sos.length
            + " .so file(s) under " + abiDir.getAbsolutePath());
        // Per-image load-bias assignment (ARM32 multi-.so collision fix).
        // Only the lifted ARM32 path uses biases; the arm64 Stage-6 loader
        // maps each .so at its own mmap base already. Largest vaddr
        // footprint = the main game lib = bias 0 (bit-identical to today);
        // pack each additional .so at a 64KB-aligned non-overlapping offset
        // in the image band [0, 0x20000000). Deterministic (size-sorted) so
        // the existence-based dylib cache stays valid across runs.
        String abiName = abiDir.getName();
        // MACAPK_ARM32_LIBDIR_ABI (default ON, v1.0.1654): since v1.0.1588
        // (0a070191) native libs live in the install dir's lib/<isa>/, and the
        // ISA directory for armeabi / armeabi-v7a is "arm" (as on a device:
        // /data/app/<pkg>/lib/arm). This check still only knew the ABI
        // spellings, so for every INSTALLED multi-library ARM32 app it was
        // false: no per-image biases were assigned, every .so was translated
        // at load bias 0 and the images overwrote each other in the guest
        // arena (GD Lite v7a and Mindustry v7a logged B=0x0 for both libs),
        // and the ARM32 pool cap below was skipped. The overlay check further
        // down already accepted "arm". =0 restores the old ABI-spelling-only
        // test (and with it bias 0 for every image).
        final boolean libDirAbi = !"0".equals(System.getenv("MACAPK_ARM32_LIBDIR_ABI"));
        boolean isArm32Abi = abiName.equals("armeabi-v7a") || abiName.equals("armeabi")
            || (libDirAbi && abiName.equals("arm"));
        if (isArm32Abi && sos.length > 1) {
            java.util.List<File> ordered =
                new java.util.ArrayList<>(java.util.Arrays.asList(sos));
            java.util.Map<File,Long> spanOf = new java.util.HashMap<>();
            final java.util.Set<String> names = new java.util.HashSet<>();
            for (File so : ordered) {
                spanOf.put(so, ElfReader.readMaxVaddr(so));
                String s = so.getName();
                names.add(s.substring(3, s.length() - 3));   // libfoo.so -> foo
            }
            // The MAIN lib (the one loaded via System.loadLibrary) must get
            // bias 0 so its lifted code is bit-identical -- biasing the
            // render lib risks a regression. A companion is named
            // "<base>-<ext>" (e.g. gdx-freetype, arc-box2d) where <base> is
            // another lib in the set; companions sort AFTER bases. NOTE the
            // main lib is NOT always the largest (ShatteredPD's
            // libgdx-freetype is bigger than libgdx), so do NOT sort by span
            // alone. Tiebreak among bases/companions by descending span.
            java.util.function.Function<File,Boolean> isCompanion = (so) -> {
                String n = so.getName();
                n = n.substring(3, n.length() - 3);
                for (String other : names) {
                    if (!other.equals(n) && n.startsWith(other + "-")) return true;
                }
                return false;
            };
            ordered.sort((a, b) -> {
                int ca = isCompanion.apply(a) ? 1 : 0;
                int cb = isCompanion.apply(b) ? 1 : 0;
                if (ca != cb) return Integer.compare(ca, cb);     // bases first
                return Long.compare(spanOf.getOrDefault(b, 0L),
                                    spanOf.getOrDefault(a, 0L));   // then desc span
            });
            long bias = 0;
            final long ALIGN = 0x10000L;            // 64 KiB
            final long BAND_CEIL = 0x20000000L;     // guest heap starts here
            for (File so : ordered) {
                String n = so.getName().substring(3, so.getName().length() - 3);
                long sp = spanOf.getOrDefault(so, 0L);
                libBias.put(n, bias);
                if (bias != 0) {
                    NativeLog.i(TAG, "preloadAllApkLibs: " + n
                        + " load bias = 0x" + Long.toHexString(bias)
                        + " (span 0x" + Long.toHexString(sp) + ")");
                }
                long next = ((bias + Math.max(sp, ALIGN) + ALIGN - 1) / ALIGN) * ALIGN;
                if (next >= BAND_CEIL) {
                    NativeLog.w(TAG, "preloadAllApkLibs: image band near full"
                        + " (0x" + Long.toHexString(next) + "); further .so may overlap heap");
                }
                bias = next;
            }
        }
        int translated = 0, hits = 0, failed = 0;
        long startMs = System.currentTimeMillis();
        // Cache-hit scan stays a plain sequential loop: each check is a
        // File.exists() stat, not a subprocess, so there's nothing to
        // parallelize (warm launches already reduce to O(libs) stat
        // calls -- see the class doc above). Only cache MISSES spawn a
        // translator subprocess; collect those into jobs[] and run them
        // through the bounded pool below.
        record Job(File so, String name, File cached, long bias) {}
        java.util.List<Job> jobs = new java.util.ArrayList<>();
        for (File so : sos) {
            String soname = so.getName();
            // libfoo.so -> foo
            String name = soname.substring(3, soname.length() - 3);
            File cached = new File(appDir,
                "lib_native/lib" + name + cacheSuffix + ".dylib");
            // `exists()` ALONE IS NOT A CACHE HIT, and treating it as one cost
            // every cold boot a second translation of its biggest library.
            //
            // This loop never stamped its output (see the writeNativeIdStamp
            // below, added with this comment), so every lib it translated came
            // out UNSTAMPED -- and findLibrary / exposeDylibsForSoLoader, which
            // both DO check nativeIdMatches, then re-translated it. MEASURED on
            // DumbWays' cold boot 2026-09-22: 12 libs translated here, then
            // `findLibrary: cached libil2cpp.dylib was built by an unstamped
            // translator ... re-translating` for libil2cpp, libmain and
            // libcrashlytics. libil2cpp is the largest .so in a Unity app.
            //
            // The bare test was also the only path with NO staleness check at
            // all, which matters because this loop exists precisely for
            // libraries loaded by a guest `dlopen()` that never reaches
            // findLibrary (Unity's libmain dlopens libunity directly). Those
            // could be served a dylib built by an older translator, or a
            // PARTIAL one left behind by a translator killed mid-write -- the
            // cache is existence-based, so a truncated file reads as a hit
            // forever.
            if (cached.exists() && nativeIdMatches(cached)) { hits++; continue; }
            cached.getParentFile().mkdirs();
            if (translatorBin == null || !translatorBin.canExecute()) {
                failed++; continue;
            }
            jobs.add(new Job(so, name, cached, libBias.getOrDefault(name, 0L)));
        }
        if (!jobs.isEmpty()) {
            // Bounded concurrency. Each translate() is an independent
            // subprocess (a full clang/LLVM pipeline for ARM32; a cheap
            // incbin-embed + trampoline codegen -- or the in-process
            // Mach-O emitter -- for arm64 Stage-6) with no data
            // dependency on any other lib's translation: textbook
            // embarrassingly-parallel work that used to run wall-clock-
            // serially in this loop (the docstring above already
            // measured ~30-60s for Crossy Road's 15 libs).
            //
            // This stays true even though the translator now looks at
            // SIBLING .so files too (cross-guest-library linking,
            // 2026-07-28: one guest .so calling a function a SIBLING .so
            // in this same app genuinely implements -- e.g. Firebase's
            // Analytics/Crashlytics/Messaging libraries calling into
            // libFirebaseCppApp). That lookup reads a sibling's ELF
            // dynsym table directly, never its Mach-O output, so it has
            // no dependency on translation ORDER -- a sibling's exported
            // names exist from APK-extraction time, before any of these
            // concurrent translate() calls run. The resulting cross-.so
            // symbol resolution is carried entirely by an LC_LOAD_DYLIB
            // dependency edge the translator itself emits into the
            // OUTPUT Mach-O; nothing in this Java loop's ordering or
            // concurrency needed to change. See
            // docs/cross-guest-library-linking_2026-07-28.md for the
            // design and the dyld behavior it was verified against.
            //
            // CRITICAL memory constraint: the ARM32 lifter's clang
            // invocation peaks ~4-6 GB RSS BY ITSELF (see CLAUDE.md
            // "Translator memory cap"), so ARM32 concurrency must stay
            // LOW (2) -- stacking more concurrent heavy clang jobs risks
            // OOM on an 8 GB host, which is exactly why this loop was
            // serial in the first place. arm64 Stage-6 packaging has no
            // heavy optimizing compile in the common case (ELF embed +
            // trampoline codegen, or the in-process emitter) so it is
            // cheap/IO-bound and safe at higher concurrency.
            //
            // preloadAllApkLibs only ever processes ONE ABI's libs per
            // call (abiDir above is resolved to a single directory), so
            // every job in this batch is the SAME kind of work -- one
            // pool-size decision (from isArm32Abi) covers the whole
            // batch; there is no need for two live pools at once.
            int poolSize = isArm32Abi
                ? Math.max(1, Math.min(2, jobs.size()))
                : Math.max(1, Math.min(4, Math.min(jobs.size(),
                      Runtime.getRuntime().availableProcessors())));
            NativeLog.i(TAG, "preloadAllApkLibs: translating " + jobs.size()
                + " .so concurrently (pool=" + poolSize
                + ", arm32=" + isArm32Abi + ")");
            java.util.concurrent.atomic.AtomicInteger tseq =
                new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(poolSize, r -> {
                    Thread t = new Thread(r, "macapk-xlat-" + tseq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
            java.util.List<java.util.concurrent.Future<Boolean>> futures =
                new java.util.ArrayList<>(jobs.size());
            try {
                for (Job j : jobs) {
                    futures.add(pool.submit(
                        () -> translate(j.so(), j.cached(), j.bias())));
                }
                for (int i = 0; i < jobs.size(); i++) {
                    Job j = jobs.get(i);
                    boolean ok;
                    try {
                        ok = futures.get(i).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ok = false;
                    } catch (java.util.concurrent.ExecutionException e) {
                        NativeLog.w(TAG, "preloadAllApkLibs: translate("
                            + j.name() + ") threw: " + e.getCause());
                        ok = false;
                    }
                    if (ok) {
                        translated++;
                        // STAMP IT. Without this the artifact is unstamped, and
                        // the two paths that check the stamp (findLibrary,
                        // exposeDylibsForSoLoader) throw the work away and
                        // translate it again -- measured on DumbWays, three
                        // libs including libil2cpp. Same rule as those sites:
                        // stamp only after a SUCCESSFUL translation, because an
                        // unstamped dylib re-translates next launch (safe) while
                        // a wrongly-stamped one is reused forever (not).
                        writeNativeIdStamp(j.cached());
                        NativeLog.i(TAG, "preloadAllApkLibs: translated " + j.name()
                            + " -> " + j.cached().getAbsolutePath());
                    } else {
                        failed++;
                        // Remove the partial output. The translator writes
                        // straight to --out with no temp+rename, so a failed or
                        // killed run can leave a truncated .dylib -- and with an
                        // existence-based cache that file would be dlopen'd on
                        // every later launch. The stamp check above already
                        // rejects it, but leaving a broken artifact on disk to
                        // be explained later is its own cost.
                        try { j.cached().delete(); } catch (Throwable ignored) {}
                        NativeLog.w(TAG, "preloadAllApkLibs: translate("
                            + j.name() + ") failed");
                    }
                }
            } finally {
                pool.shutdown();
            }
        }
        long ms = System.currentTimeMillis() - startMs;
        NativeLog.i(TAG, "preloadAllApkLibs: done in " + ms + "ms"
            + " (translated=" + translated + " cached=" + hits
            + " failed=" + failed + ")");
        // v1.0.154 (reverted): the Runtime.load preload was too
        // aggressive -- libmain's JNI_OnLoad calls FindClass for
        // its target class (NativeLoader for Unity), which fails
        // because the APK's classloader hasn't loaded that class
        // yet. Translation-only preload is enough; the actual
        // dlopen happens via libmain's runtime call later.
    }

    /** v1.0.848: install-time (--extract-only) entry. Translate every
     *  lib/<abi>/*.so of this app NOW so the first real launch
     *  cache-hits lib_native/ instead of stalling behind the one-time
     *  ARM32 lift (Android likewise does its AOT work at install, not
     *  first launch). This MUST delegate to the same preloadAllApkLibs
     *  the boot path runs -- the ARM32 per-image load-bias assignment
     *  is baked into each translated dylib and the lib_native cache is
     *  existence-based, so a parallel reimplementation that ordered or
     *  biased differently would poison the cache for every later boot.
     *  Failures stay non-fatal exactly like the boot-time preload:
     *  whatever did not translate here translates lazily at launch. */
    public void translateAllApkLibsForInstall() {
        installMode = true;
        preloadAllApkLibs();
    }

    /**
     * Load this library's DT_NEEDED dependencies that also live in the
     * APK's lib/<arch>/ directory, in their declared order, via
     * System.loadLibrary -- which re-enters our findLibrary and
     * translates them if needed.
     *
     * System libraries (libc.so, libm.so, liblog.so, etc.) are
     * skipped here because they don't exist in the APK and their
     * symbols resolve via the shim's __macapk_arm64_* prefix or
     * libSystem directly.
     */
    private void preloadDependencies(String libname) {
        if (!preloading.add(libname)) return;     // already in progress
        try {
            File so = locateApkSo(libname);
            if (so == null) return;
            for (String soname : ElfReader.readDtNeeded(so)) {
                // soname is e.g. "libc++_shared.so". Strip the prefix
                // and suffix to recover the System.loadLibrary form.
                if (!soname.startsWith("lib")
                    || !soname.endsWith(".so")) {
                    continue;
                }
                String depName = soname.substring(
                    3, soname.length() - 3);
                if (depName.equals(libname)) continue;
                // Only load if the dependency is bundled in the APK.
                // Bionic system libs (c, m, dl, log, android, GLESv2,
                // EGL, OpenSLES, etc.) aren't shipped in the APK and
                // are resolved by the loader's symbol-fallback path.
                File depSo = locateApkSo(depName);
                if (depSo == null) continue;
                // Translate + cache the dependency, then dlopen it by
                // absolute path via Runtime.load. We cannot use
                // System.loadLibrary here: that uses
                // Reflection.getCallerClass() to pick a ClassLoader,
                // and AppClassLoader (the class calling loadLibrary)
                // is itself loaded by the SystemClassLoader -- whose
                // findLibrary doesn't know about the APK's libs. The
                // dependency would silently fail to load and the
                // dependent library's relocations would still see
                // unresolved externals.
                File cached = new File(appDir,
                    "lib_native/lib" + depName + ".dylib");
                // Recurse so depName's own DT_NEEDED is loaded before
                // depName itself. This must run on the CACHE-HIT path too.
                // Install-time pre-translation populates lib_native/ for
                // every lib, so at launch `cached` almost always exists.
                // While the recursion sat inside the translate branch below,
                // that cache hit skipped it: a dependent whose DT_NEEDED
                // lists it before the library that exports its symbols was
                // then dlopen'd first and bound those imports to the
                // unresolved trap. Bionic has no such window -- dlopen loads
                // the whole DT_NEEDED closure before relocating.
                preloadDependencies(depName);
                if (!cached.exists()) {
                    cached.getParentFile().mkdirs();
                    if (translatorBin == null
                        || !translatorBin.canExecute()
                        || !translate(depSo, cached)) {
                        NativeLog.w(TAG,
                            "preload translate("
                            + depName + ") failed");
                        continue;
                    }
                }
                try {
                    NativeLog.i(TAG,
                        "preloading " + soname + " (DT_NEEDED of lib"
                        + libname + ".so) -> "
                        + cached.getAbsolutePath());
                    // ⚠ NOT Runtime.getRuntime().load(): that binds the library
                    // to the caller's ClassLoader, and AppClassLoader is defined
                    // by the SYSTEM loader -- so the dep landed in the system
                    // loader's native set while the guest classes that declare
                    // the native methods are defined HERE. HotSpot's NativeLookup
                    // searches only the declaring class's loader, so the symbols
                    // were loaded and still unresolvable. See NativeLoaderShim.
                    loadIntoThisLoader(cached.getAbsolutePath());
                } catch (UnsatisfiedLinkError e) {
                    NativeLog.w(TAG,
                        "preload load(" + depName + ") failed: " + e);
                }
            }
        } finally {
            preloading.remove(libname);
        }
    }

    /** Per-instance copy of NativeLoaderShim, defined BY THIS LOADER so that
     *  System.load() called from it registers the library against this loader
     *  rather than the system one. Built lazily; null if it could not be made,
     *  in which case we fall back to the old behaviour rather than not loading
     *  the library at all. */
    private Class<?> nativeLoaderShim;

    private void loadIntoThisLoader(String absPath) {
        try {
            if (nativeLoaderShim == null) {
                String res = "com/macapk/loader/NativeLoaderShim.class";
                java.io.InputStream in =
                    NativeLoaderShim.class.getClassLoader().getResourceAsStream(res);
                if (in != null) {
                    byte[] buf;
                    try (java.io.InputStream s2 = in) { buf = s2.readAllBytes(); }
                    // defineClass on THIS loader: same binary name, different
                    // defining loader, therefore a distinct Class whose
                    // getClassLoader() is us.
                    nativeLoaderShim = defineClass(
                        "com.macapk.loader.NativeLoaderShim", buf, 0, buf.length);
                }
            }
            if (nativeLoaderShim != null) {
                java.lang.reflect.Method m =
                    nativeLoaderShim.getMethod("load", String.class);
                m.invoke(null, absPath);
                return;
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "loadIntoThisLoader(" + absPath + ") failed (" + t
                + "); falling back to the system-loader path");
        }
        Runtime.getRuntime().load(absPath);
    }

    /** Preference order matching Android's own ABI fallback. The translator
     *  rejects x86 / x86_64 today; listed for completeness so an unrecognized
     *  ABI string fails loudly rather than silently misclassifying. */
    private static final List<String> KNOWN_ABIS = Arrays.asList(
        "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86");

    /**
     * DIAGNOSTIC ESCAPE HATCH: MACAPK_FORCE_ABI=<abi> pins native-library
     * selection to a single ABI subdir. Diagnostic-only -- it exists so we
     * can A/B the same APK across translation paths: the arm64 Stage-6-native
     * path (zero translation; CPU runs the bytes verbatim) is our oracle for
     * fixing arm32-lifter bugs surfaced by the arm32 build of the same APK.
     *
     * Behavior when set:
     *   - locateApkSo() returns ONLY the .so under lib/<isa of abi>/. No
     *     fallback to the priority list -- if the requested ABI isn't
     *     bundled, the call returns null and findLibrary() drops to the
     *     stub. That's intentional: a typo should fail loudly, not silently
     *     promote the user to a different ABI.
     *   - findLibrary() suffixes the cached-dylib filename with "-<abi>"
     *     (e.g. lib_native/libgame-armeabi-v7a.dylib), keeping the default
     *     path's lib<name>.dylib intact across runs.
     *
     * Env var vs CLI flag: every existing diagnostic knob in Mac.apk is an
     * env var (MACAPK_SKIP_LIBS, MACAPK_DISABLE_TRANSLATOR, MACAPK_KEEP_IR,
     * MACAPK_TRACE_VADDRS, MACAPK_OPT_LEVEL, ...); CLI flags are reserved
     * for steady-state user options (--apk, --orientation, --android-root).
     * Env-var lives entirely in this one file; a --abi CLI flag would need
     * plumbing through runtime/src/main.cpp::ParseArgs -> JVM -D system
     * property -> Boot.java constructor arg -- three extra files for a knob
     * that exists only for this one work item. Easy to set in the launcher's
     * child-process environment without launcher UI work.
     *
     * Returns null if unset or unrecognized. Unrecognized values are logged
     * as a warning (clearer than a silent no-op).
     */
    private static String forcedAbi() {
        String v = System.getenv("MACAPK_FORCE_ABI");
        if (v == null || v.isEmpty()) return null;
        if (!KNOWN_ABIS.contains(v)) {
            NativeLog.w(TAG, "MACAPK_FORCE_ABI=" + v + " unrecognized; "
                + "expected one of " + KNOWN_ABIS + "; ignoring");
            return null;
        }
        return v;
    }

    /** The directory holding the app's installed native libraries for this
     *  run: the install dir's lib/<isa>/ (v1.0.1588, one isa dir, chosen by
     *  the runtime with the same ABI preference and MACAPK_FORCE_ABI
     *  override -- InstalledSplits.nativeLibDir). Null when the app has no
     *  installed lib dir -- as on a device, where a package that could not
     *  be installed has no native libraries either (the pre-1588
     *  <appDir>/_apkt/lib/<abi>/ fallback was deleted at v1.0.1590). */
    private File nativeLibDir() {
        if (apkFile == null) return null;
        File d = InstalledSplits.nativeLibDir(apkFile.getPath());
        return d != null && d.isDirectory() ? d : null;
    }

    /** The app's own lib<name>.so in the installed lib dir, or null if the
     *  APK does not ship it for the installed instruction set. */
    private File locateApkSo(String libname) {
        File dir = nativeLibDir();
        if (dir == null) return null;
        File candidate = new File(dir, "lib" + libname + ".so");
        return candidate.exists() ? candidate : null;
    }

    /**
     * t3-mmap (2026-07-24): does the arm64-v8a source .so for `libname`
     * still exist -- the one, and ONLY one, a cached Stage-6 arm64 wrapper
     * can depend on (Stage-6 never runs anything but arm64-v8a input;
     * ARM32 input goes through the lifter into a self-contained dylib that
     * needs no source .so at load time). Deliberately independent of
     * locateApkSo(): that method's whole POINT is to fall back across
     * KNOWN_ABIS (arm64-v8a, then armeabi-v7a, then armeabi, ...), which is
     * exactly right when picking a source to translate FRESH, but wrong for
     * this check -- a still-present armeabi-v7a copy of the same libname is
     * a COMPLETELY UNRELATED file (would need an ARM32 lift + a differently-
     * named cache entry to even be used) and must never be allowed to mask
     * a missing arm64-v8a one. (Confirmed by reproduction: an earlier
     * version of this check used `locateApkSo(libname) != null` directly,
     * which on GD Lite -- ships both ABIs -- let a live armeabi-v7a copy
     * paper over a deleted arm64-v8a one; the cached Stage-6 wrapper then
     * loaded via System.load(), and the JVM's own JNI_OnLoad lookup+call
     * jumped through an unresolved (zero) trampoline slot -- SIGSEGV pc=0.
     * See findLibrary()'s cache-hit comment for the full story.)
     *
     * MACAPK_FORCE_ABI to a non-arm64-v8a value means the cached dylib (if
     * any) is an ARM32-lifted, self-contained wrapper regardless -- no
     * source .so is needed at load time, so this returns true unconditionally
     * (matches locateApkSo()'s own forced-mode semantics: single ABI, no
     * fallback, and here specifically "arm64-v8a" is not even the ABI in
     * play). MACAPK_FORCE_ABI=arm64-v8a (or unset, the default) checks
     * arm64-v8a directly, matching what locateApkSo() would resolve to on a
     * fresh (default-preference) translation.
     */
    /**
     * Does this cached .dylib carry the NATIVE_ID of the translator + bionic
     * shim currently installed?
     *
     * The lifted/wrapped dylib cache used to be EXISTENCE-based: if the file
     * was there we reused it, no matter which translator produced it. That is
     * the "warm sweep re-tests stale output" hazard CLAUDE.md warns about --
     * a translator or shim change would ship, every cached app would keep
     * running the OLD translation, and the sweep would come back green while
     * measuring code that no longer exists.
     *
     * The stamp is a sidecar (<dylib>.nativeid) rather than an xattr so it
     * survives ditto/cp and is greppable when diagnosing a stale run.
     *
     * MACAPK_BUILD_NATIVE_ID is exported by the runtime. When it is absent we
     * REUSE and say nothing: an unknown identity must never mean "re-translate
     * everything on every launch", which would be a worse failure than the one
     * being fixed.
     */
    /** "<size>:<mtime>" of the .so a SoLoader dylib was translated from. */
    private static String soIdentity(File so) {
        return so.length() + ":" + so.lastModified();
    }

    private static boolean soSourceMatches(File cached, File so) {
        File stamp = new File(cached.getPath() + ".src");
        try {
            return stamp.isFile() && soIdentity(so).equals(new String(
                java.nio.file.Files.readAllBytes(stamp.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static void writeSoSourceStamp(File cached, File so) {
        try {
            java.nio.file.Files.write(new File(cached.getPath() + ".src").toPath(),
                soIdentity(so).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) { }
    }

    private boolean nativeIdMatches(File cached) {
        String want = System.getenv("MACAPK_BUILD_NATIVE_ID");
        if (want == null || want.isEmpty()) return true;
        File stamp = new File(cached.getPath() + ".nativeid");
        String have = null;
        try {
            if (stamp.isFile()) {
                have = new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (java.io.IOException ignored) {
            // Unreadable stamp behaves as a mismatch: re-translating costs
            // time, reusing a possibly-stale dylib costs a wrong diagnosis.
        }
        if (want.equals(have)) return true;
        NativeLog.w(TAG, "findLibrary: cached " + cached.getName() + " was built by "
                + (have == null ? "an unstamped translator" : "translator " + have)
                + ", current is " + want + " -- re-translating");
        return false;
    }

    /** Record which translator+shim produced this dylib. Best effort: a
     *  missing stamp only costs one re-translation next launch. */
    static void writeNativeIdStamp(File cached) {
        String id = System.getenv("MACAPK_BUILD_NATIVE_ID");
        if (id == null || id.isEmpty() || !cached.isFile()) return;
        try {
            java.nio.file.Files.write(
                new File(cached.getPath() + ".nativeid").toPath(),
                id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) { }
    }

    private boolean stage6SourceStillPresent(String libname) {
        String forcedAbi = forcedAbi();
        if (forcedAbi != null && !forcedAbi.equals("arm64-v8a")) return true;
        // Only NO-EMBED arm64-v8a (Stage-6) wrappers reference an on-disk source .so
        // at load time. ARM32-lifted wrappers (an armeabi-v7a-only app like Flappy
        // Bird / AndEngine) are SELF-CONTAINED -- the lifted code lives in the dylib,
        // there is no runtime .so dependency, and there is no arm64-v8a lib dir at
        // all. Gating those on an arm64-v8a source that never existed wrongly
        // invalidated the cache and re-lifted the lib on EVERY launch. So only gate
        // when this app actually has an arm64-v8a tree (i.e. it IS a Stage-6 app).
        File arm64Dir = null;
        if (apkFile != null) {
            File inst = InstalledSplits.installDirOf(apkFile.getPath());
            if (inst != null) arm64Dir = new File(inst, "lib/arm64");
        }
        if (arm64Dir == null || !arm64Dir.isDirectory()) return true;   // ARM32-only app: self-contained
        File f = new File(arm64Dir, "lib" + libname + ".so");
        return f.exists();
    }

    /** Process-wide count of translate() calls currently holding the
     *  translation overlay open. preloadAllApkLibs's ARM32 pool (see
     *  above) can run several translate() calls concurrently, and the
     *  native overlay (g_xlate_overlay in cocoa_bridge.mm) is a single
     *  process-global NSView, not per-call state -- so "should I hide
     *  it now" must be answered with "is anyone ELSE still using it",
     *  not "did I show it". Only the caller that brings this to 0 may
     *  hide it. */
    private static final java.util.concurrent.atomic.AtomicInteger overlayRefs =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /** Run the macapk-translator subprocess. Returns true on success.
     *
     *  Wraps the subprocess in a splash overlay (showTranslationOverlay
     *  / updateTranslationOverlay / hideTranslationOverlay on
     *  NativeCocoa) so the user sees an explanation of the 2-5 minute
     *  one-time wait instead of a black window. The translator's
     *  stderr stream is parsed for boundary events (lift counts,
     *  chunked-compile progress) and short status strings are pushed
     *  into the detail line. Resource cost on the Cocoa side is two
     *  NSTextFields plus rate-limited string updates -- see
     *  CocoaShowTranslationOverlay in cocoa_bridge.mm. */
    private boolean translate(File input, File output) {
        return translate(input, output, 0L);
    }

    private boolean translate(File input, File output, long loadBias) {
        // Do not hand the translator something that is not an ELF.
        //
        // apk_native_exec.cpp SUBSTITUTES our `macapk-elfexec` helper (a Mach-O)
        // for any ELF *executable* an APK ships under lib/ with a .so name --
        // Crashlytics' `libcrashlytics-trampoline.so` is one, and the app
        // EXECUTES it rather than loading it. The original ELF is preserved in
        // the sibling `.macapk-elfexec/` sidecar. That substitution is correct
        // and runs BEFORE we get here.
        //
        // We then tried to TRANSLATE the substituted file, the translator read a
        // Mach-O, and every such app logged
        //     [E] Mac.apk.AppCl: translator exited rc=1
        // for `libcrashlytics-trampoline.so: not a valid ELF file`. Harmless to
        // the app -- it never wanted that file dlopen'd -- but it spends a
        // subprocess to fail, and a spurious [E] is worse than the wasted work:
        // it is exactly the line a future session greps for when hunting a REAL
        // translator failure. Measured on the 2026-08-26 full-fleet census: 3 of
        // 63 apps, all on this same library.
        try {
            byte[] magic = new byte[4];
            try (java.io.FileInputStream in = new java.io.FileInputStream(input)) {
                if (in.read(magic) == 4
                        && !(magic[0] == 0x7f && magic[1] == 'E'
                          && magic[2] == 'L'  && magic[3] == 'F')) {
                    NativeLog.i(TAG, "translate: skipping " + input.getName()
                        + " -- not an ELF (an elfexec-substituted executable, or"
                        + " an artifact this app does not load); original, if any,"
                        + " is in .macapk-elfexec/");
                    return false;
                }
            }
        } catch (Throwable ignored) {
            // Unreadable here means the translator will report it properly.
        }
        File shimDir = translatorBin.getParentFile();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
            translatorBin.getAbsolutePath(),
            "--in",  input.getAbsolutePath(),
            "--out", output.getAbsolutePath(),
            "--shim-dir", shimDir.getAbsolutePath()
        ));
        if (loadBias != 0) {
            // Per-image load bias (multi-.so collision fix). Baked into the
            // dylib at translate time; the dylib NAME is unchanged (the bias
            // is deterministic per app so the cache stays valid).
            cmd.add("--load-bias");
            cmd.add("0x" + Long.toHexString(loadBias));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        // Derive the libname from the input file ("libgame.so" -> "game")
        // for the splash headline. Falls back to the raw filename if the
        // input doesn't follow the lib<name>.so convention.
        String inputName = input.getName();
        String libname = inputName;
        if (libname.startsWith("lib") && libname.endsWith(".so")) {
            libname = libname.substring(3, libname.length() - 3);
        }

        // Splash gating: the overlay is wallpaper for the user-visible
        // 2-5 minute ARM32 lift+compile. The arm64-v8a path runs the
        // same translator binary but takes a different code path
        // (Stage 6: wrap the ELF verbatim in a Mach-O loader stub,
        // ~1-2 seconds for a multi-megabyte cocos2d-x dylib). Showing
        // the "Arm32 apps require a one-time translation..." splash
        // for that quick wrap step is misleading -- the user sees the
        // overlay flash for 2 seconds even though no arm32 work is
        // happening. Skip the overlay when the source .so is at the
        // arm64 path. The parent-directory name of the input is the
        // instruction set ("arm64" / "arm") under the installed lib/ dir
        // (v1.0.1588); the ABI spellings are kept for a hand-run input.
        File abiDir = input.getParentFile();
        String abi  = abiDir != null ? abiDir.getName() : "";
        boolean isArm32 = abi.equals("arm")
            || abi.equals("armeabi-v7a") || abi.equals("armeabi");

        boolean overlayShown = false;
        // preloadAllApkLibs may run several ARM32 translate() calls
        // concurrently (bounded pool, see above), so more than one
        // thread can be inside this method at once, each wanting the
        // overlay visible. heldOverlayRef + overlayRefs (a process-wide
        // counter) make sure only the LAST concurrent caller to finish
        // hides it -- an earlier finish must NOT hide the overlay out
        // from under a sibling translation that is still running. A
        // later concurrent Show call just refreshes the headline;
        // CocoaShowTranslationOverlay already handles that gracefully
        // (see its "tear down a stale overlay first" comment).
        // heldOverlayRef, once set, is never reset by anything that
        // happens later (e.g. NativeCocoa going away mid-stream) so the
        // increment/decrement pair always stays balanced.
        boolean heldOverlayRef = false;
        if (isArm32 && !installMode) {
            overlayRefs.incrementAndGet();
            heldOverlayRef = true;
            try {
                NativeCocoa.showTranslationOverlay(libname);
                overlayShown = true;
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                // Headless / pre-JNI test environment. Continue without the
                // splash so translation still works for CLI runs.
                NativeLog.w(TAG,
                    "translation overlay unavailable: " + e);
            }
        }

        try {
            NativeLog.i(TAG, "running translator: " + String.join(" ", pb.command()));
            Process p = pb.start();
            // Forward translator stderr/stdout to NativeLog so users
            // see lift counts and warnings. Read in a tight loop so
            // the child can't block on a full pipe.
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    NativeLog.i(TAG, "[xlat] " + line);
                    if (overlayShown) {
                        String detail = mapToOverlayDetail(line);
                        if (detail != null) {
                            try {
                                NativeCocoa.updateTranslationOverlay(detail);
                            } catch (UnsatisfiedLinkError ignored) {
                                // overlay went away; stop trying
                                overlayShown = false;
                            }
                        }
                    }
                }
            }
            int rc = p.waitFor();
            if (rc != 0) {
                NativeLog.e(TAG, "translator exited rc=" + rc);
                return false;
            }
            return output.exists();
        } catch (IOException | InterruptedException e) {
            NativeLog.e(TAG, "translator subprocess failed: " + e);
            return false;
        } finally {
            // Decrement unconditionally (heldOverlayRef only reflects
            // whether THIS call incremented earlier); only hide once no
            // concurrent translate() call still wants the overlay open.
            if (heldOverlayRef && overlayRefs.decrementAndGet() <= 0) {
                try {
                    NativeCocoa.hideTranslationOverlay();
                } catch (UnsatisfiedLinkError ignored) {
                    // already gone
                }
            }
        }
    }

    /** Map a translator stderr line to a short user-facing detail
     *  string for the splash, or null if the line doesn't represent
     *  a milestone worth surfacing. Kept conservative: only the few
     *  lines that bracket meaningful phases (lift count, chunk
     *  compile start, per-chunk completion) get translated to UI
     *  copy. Everything else stays in the log. */
    private static String mapToOverlayDetail(String line) {
        if (line == null) return null;
        // Order matters: chunked-compile lines and per-chunk "warning"
        // lines appear interleaved, so match the more-specific phases
        // first.
        if (line.startsWith("[elf_reader]")) {
            return "Reading ELF and applying relocations…";
        }
        if (line.startsWith("[translator] prologue-scan")) {
            return "Discovering function entries…";
        }
        if (line.contains("lifted ")
                && line.contains(" function(s)")) {
            // e.g. "[translator] .../libgame.so: lifted 45052 function(s) ..."
            int liftedAt = line.indexOf("lifted ");
            int parenAt  = line.indexOf(" function(s)");
            if (liftedAt >= 0 && parenAt > liftedAt) {
                String n = line.substring(liftedAt + 7, parenAt);
                return "Lifted " + n + " functions — preparing compile…";
            }
            return "Lift complete — preparing compile…";
        }
        if (line.contains("chunked compile: ir=")) {
            // e.g. "[translator] chunked compile: ir=2935682112 bytes, n_chunks=6, ..."
            int nAt = line.indexOf("n_chunks=");
            if (nAt > 0) {
                int comma = line.indexOf(',', nAt);
                String n = comma > nAt
                    ? line.substring(nAt + 9, comma)
                    : line.substring(nAt + 9);
                return "Compiling — " + n + " parallel chunks…";
            }
            return "Compiling in parallel chunks…";
        }
        if (line.contains("[translator] split: llvm-as chunk")
                && line.contains("failed")) {
            return "Bitcode assembly failed for a chunk — see log";
        }
        if (line.contains("[translator] split: clang -c chunk")
                && line.contains("failed")) {
            return "Object compile failed for a chunk — see log";
        }
        if (line.contains("[translator] split: link failed")) {
            return "Link failed — see log";
        }
        if (line.contains("[translator] wrote ")) {
            return "Finalizing…";
        }
        return null;
    }
}
