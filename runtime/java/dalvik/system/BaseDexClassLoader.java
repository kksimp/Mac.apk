// dalvik.system.BaseDexClassLoader -- in AOSP this is the common superclass of
// PathClassLoader (the app's own loader) and DexClassLoader, sitting between
// java.lang.ClassLoader and those subclasses. Apps + the NDK GameActivity SDK
// (SmashHit) reference it by name and sometimes cast getClassLoader() to it to
// read findLibrary()/getLdLibraryPath(). A missing class threw
// NoClassDefFoundError during onCreate.
//
// Mac.apk's real loader is a URLClassLoader chain, so we slot BaseDexClassLoader
// in as a URLClassLoader subclass; AppClassLoader (our PathClassLoader analogue)
// and DexClassLoader both extend it, so getClassLoader() IS-A BaseDexClassLoader
// and the casts succeed.
package dalvik.system;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

public class BaseDexClassLoader extends URLClassLoader {
    // Parallel-capable. ART never serializes a whole class loader: classes load
    // under per-class locks. On HotSpot, a loader that does not register gets
    // getClassLoadingLock(name) == the loader itself, so every thread loading
    // ANY class waits on one lock (JFR on WhatsApp: 718 waits, the UI thread
    // up to 695 ms). Each class up the chain to URLClassLoader must register.
    static { ClassLoader.registerAsParallelCapable(); }

    /** AOSP: {@code private final DexPathList pathList}. Native code and
     *  hotfix/safe-mode SDKs name it directly (JNI GetFieldID with
     *  "Ldalvik/system/DexPathList;", or reflection): ByteDance's SafeModeSdk
     *  LoaderNativeBridge.getSafeModeClassNative died with NoSuchFieldError
     *  here (issue #105). Real loading stays URLClassLoader-backed; see
     *  DexPathList for what the object does and does not mirror. */
    private final DexPathList pathList;

    private static String urlsToDexPath(URL[] urls) {
        StringBuilder sb = new StringBuilder();
        if (urls != null) for (URL u : urls) {
            if (u == null || !"file".equals(u.getProtocol())) continue;
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(u.getPath());
        }
        return sb.toString();
    }

    /** AOSP signature. dexPath is a colon-separated list of code paths;
     *  optimizedDirectory + librarySearchPath are advisory on a real device
     *  and ignored here. */
    public BaseDexClassLoader(String dexPath, File optimizedDirectory,
                              String librarySearchPath, ClassLoader parent) {
        super(buildUrls(dexPath), parent);
        pathList = new DexPathList(this, dexPath, librarySearchPath, optimizedDirectory);
        // ART searches a loader's JNI libraries in path-sorted order; see
        // ArtJniLibraryOrder. Before anything can load through this loader.
        ArtJniLibraryOrder.apply(this);
    }

    /** Internal constructor used by subclasses that already have URL[] form
     *  (AppClassLoader, the no-dex DexClassLoader path). */
    protected BaseDexClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        pathList = new DexPathList(this, urlsToDexPath(urls), null, null);
        ArtJniLibraryOrder.apply(this);
    }

    /** ART accepts a SLASH-separated class name here; HotSpot does not. On a
     *  device, BaseDexClassLoader.findClass reaches DexFile_defineClassNative
     *  (art/runtime/native/dalvik_system_DexFile.cc:490), which looks the name
     *  up through DotToDescriptor (art/libdexfile/dex/descriptors_names.cc:196)
     *  -- "replace '.' with '/', wrap in L...;" -- so "a/b/C" and "a.b.C" name
     *  the same class. HotSpot's URLClassLoader finds the ".class" entry for a
     *  slash name (it only rewrites dots) and then refuses it in defineClass
     *  with NoClassDefFoundError("IllegalName: a/b/C").
     *
     *  Real code relies on the ART spelling. The Firebase C++ SDK caches its
     *  Java helpers as embedded DEX files, opens them with
     *  new DexClassLoader(...) and calls loadClass with the JNI name it was
     *  given (firebase-cpp-sdk app/src/util_android.cc, FindClassInFiles:
     *  "jstring class_name_object = env->NewStringUTF(class_name)" on the
     *  non-desktop path; only the desktop path replaces the separator).
     *  Measured on Temple Run 2: Remote Config's
     *  com/google/firebase/remoteconfig/internal/cpp/JniConfigUpdateListener
     *  was found in the converted jar and then rejected, so
     *  FirebaseRemoteConfig was never created and the game's boot threw a
     *  NullReferenceException (v1.0.1635). AppClassLoader has done the same
     *  normalisation in its own override for a long time; this puts it where
     *  every DexClassLoader / PathClassLoader inherits it. A binary name never
     *  contains '/', so no name that worked before can change meaning. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        String bn = binaryName(name);
        if (BOOT_ARRAYS && bn != null && bn.length() > 1 && bn.charAt(0) == '[') {
            Class<?> a = bootClasspathArray(bn);
            if (a != null) return a;
        }
        ClassLoader parent = getParent();
        if (!UNLOCKED_DELEGATION || parent == null) return super.loadClass(bn, resolve);
        // libcore's ClassLoader.loadClass, which takes no lock: the loaded
        // check, then the parent, then findClass. HotSpot's version holds
        // getClassLoadingLock(name) across the parent call, and since this
        // loader is parallel-capable that lock is per class name. An app that
        // puts its own loader above this one, whose findClass calls back into
        // ours, then deadlocks: one thread holds class Y's lock and waits for
        // the plugin loader's monitor, while the thread inside that monitor
        // defines a subclass of Y and waits for Y's lock. ByteDance's Mira
        // (MiraClassLoader) does this; Tomato Novel deadlocked between "main"
        // and PushThreadHandler in Application.onCreate. ART cannot deadlock
        // this way, and neither did this loader when it locked itself as a
        // whole. Two threads that both reach findClass for one name race to
        // define it, and the loser gets the winner's class (findClass here and
        // in AppClassLoader), as on ART. A loader class that is not registered
        // as parallel-capable (DexClassLoader, InMemoryDexClassLoader, an app's
        // own subclass) is still locked as a whole by HotSpot itself in
        // defineClass and in JVM-initiated loads; this does not change that.
        Class<?> c = findLoadedClass(bn);
        if (c == null) {
            try {
                c = parent.loadClass(bn);
            } catch (ClassNotFoundException notInParent) {
                // Not the parent's: define it from this loader's path below.
            }
            if (c == null) c = findClass(bn);
        }
        if (resolve) resolveClass(c);
        return c;
    }

    /** MACAPK_UNLOCKED_DELEGATION (default ON; =0 restores HotSpot's
     *  ClassLoader.loadClass, which holds the per-name lock while it asks the
     *  parent). */
    static final boolean UNLOCKED_DELEGATION =
        !"0".equals(System.getenv("MACAPK_UNLOCKED_DELEGATION"));

    /** MACAPK_BOOT_ARRAY_LOADCLASS (default ON; =0 restores the old HotSpot answer, a
     *  ClassNotFoundException for EVERY array name). */
    static final boolean BOOT_ARRAYS =
        !"0".equals(System.getenv("MACAPK_BOOT_ARRAY_LOADCLASS"));

    /** ART answers loadClass("[B") and HotSpot does not. Every ART loader chain
     *  ends at java.lang.BootClassLoader, whose findClass is
     *  {@code return Class.classForName(name, false, null);}
     *  (libcore ojluni/src/main/java/java/lang/ClassLoader.java, class
     *  BootClassLoader), and Class_classForName turns the binary name into a
     *  descriptor (DotToDescriptor) and calls ClassLinker::FindClass, which builds
     *  array classes (art/runtime/native/java_lang_Class.cc: "This is especially
     *  handy for array types"). So on a device {@code loader.loadClass("[B")}
     *  returns byte[].class for any app loader, through the parent delegation in
     *  ClassLoader.loadClass. HotSpot's ClassLoader.loadClass only ever loads
     *  class FILES, so the same call threw ClassNotFoundException.
     *
     *  Real code relies on it: Chromium's jni_zero resolves every class through
     *  {@code g_class_loader->loadClass(env, j_class_name)} (third_party/jni_zero/
     *  jni_zero.cc DefaultClassResolver, chosen precisely because FindClass on a
     *  native thread sees only the boot loader), and its array helpers ask for
     *  "[B". Measured on Reddit 2026.21.0: Cronet's ThreadPoolForeg thread hit
     *  "jni_zero.cc:38 Failed to find class [B" and the CHECK aborted the process
     *  (Trace/BPT trap) shortly after the first screen.
     *
     *  The answer mirrors the BOOT class path only, as ART's does: a primitive
     *  element, or an element the boot class path holds (here: the loader that
     *  defined the framework, i.e. JDK + android.*). An array of an APP class is
     *  NOT answered here -- ART's BootClassLoader cannot see app classes either,
     *  and the app's own DexPathList holds no array classes -- so it keeps its
     *  old ClassNotFoundException. An element that THIS loader resolves to a
     *  different class (an app's child-first copy of a class our framework jar
     *  also carries, e.g. the GMS family) is rejected for the same reason: on a
     *  device that class is not on the boot class path at all. */
    private Class<?> bootClasspathArray(String bn) {
        int dims = 0;
        while (dims < bn.length() && bn.charAt(dims) == '[') dims++;
        String elem = bn.substring(dims);
        ClassLoader boot = BaseDexClassLoader.class.getClassLoader();
        try {
            if (elem.length() == 1) {
                // "BCDFIJSZ"; Class.forName validates the descriptor itself.
                return Class.forName(bn, false, boot);
            }
            if (elem.length() > 2 && elem.charAt(0) == 'L'
                    && elem.charAt(elem.length() - 1) == ';') {
                String elemName = elem.substring(1, elem.length() - 1);
                Class<?> bootElem = Class.forName(elemName, false, boot);
                Class<?> mine = loadClass(elemName, false);
                if (mine != bootElem) return null;
                return Class.forName(bn, false, boot);
            }
        } catch (ClassNotFoundException | LinkageError notOnBootClasspath) {
            // ART: BootClassLoader.findClass threw; ClassLoader.loadClass then asks
            // this loader's own findClass, which cannot define an array class.
        }
        return null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String bn = binaryName(name);
        try {
            return super.findClass(bn);
        } catch (LinkageError le) {
            // Another thread defined the class first ("attempted duplicate
            // class definition"; loadClass no longer serializes the two).
            // ART's DefineClass returns the class that thread inserted.
            Class<?> winner = findLoadedClass(bn);
            if (winner != null) return winner;
            throw le;
        }
    }

    /** DexFile.loadClass(name, loader) entry point: define {@code binaryName}
     *  from {@code bytes} with THIS loader as the defining loader, as ART's
     *  DexFile_defineClassNative does (art/runtime/native/dalvik_system_DexFile.cc:
     *  "class_linker->DefineClass(soa.Self(), descriptor.c_str(), ..., class_loader,
     *  *dex_file, *dex_class_def)" with class_loader = the loader the caller
     *  passed), so a later {@code Class.forName(name)} / {@code loadClass(name)}
     *  on this loader finds it. A class this loader already holds is returned
     *  as is (ART's DefineClass returns the class another thread inserted first).
     *  Package-private: only dalvik.system.DexFile reaches it. */
    final Class<?> defineClassFromDexFile(String binaryName, byte[] bytes) {
        // No per-class lock, for the reason loadClass gives: defining resolves
        // supertypes through the parent. A thread that loses the define race
        // gets the winner's class.
        Class<?> c = findLoadedClass(binaryName);
        if (c != null) return c;
        try {
            return macapkDefineDexFileClass(binaryName, bytes);
        } catch (LinkageError le) {
            Class<?> winner = findLoadedClass(binaryName);
            if (winner != null) return winner;
            throw le;
        }
    }

    /** Hook for {@link #defineClassFromDexFile}; AppClassLoader overrides it to
     *  run the same class-file rewriter its own findClass applies. */
    protected Class<?> macapkDefineDexFileClass(String binaryName, byte[] bytes) {
        return defineClass(binaryName, bytes, 0, bytes.length);
    }

    static String binaryName(String name) {
        return name != null && name.indexOf('/') >= 0 ? name.replace('/', '.') : name;
    }

    /** AOSP exposes the library search path; native-lib loaders read it. We
     *  have no Android ld.so search path, so report empty (callers fall back
     *  to ClassLoader.findLibrary, which our AppClassLoader implements). */
    public String getLdLibraryPath() {
        return "";
    }

    /** AOSP (hidden API): adds a new code path to this loader's DexPathList at
     *  runtime. Used by androidx MultiDex's pre-API-21 install path and by some
     *  plugin frameworks. On a real device this re-derives the dex element list;
     *  here we convert the supplied .dex/.jar/.apk to JVM bytecode (via
     *  com.macapk.dex, the same path the primary app dex uses) and append the jar to
     *  this URLClassLoader's search path so subsequent findClass sees it.
     *  A failure to convert is surfaced lazily at findClass, matching AOSP's
     *  best-effort behavior of skipping unusable elements. */
    public void addDexPath(String dexPath) {
        addDexPath(dexPath, false);
    }

    /** AOSP signature: the boolean selects whether the new element is treated as
     *  trusted (affects hidden-API enforcement on device). Irrelevant here; we
     *  honor only the path side-effect. */
    public void addDexPath(String dexPath, boolean isTrusted) {
        if (dexPath == null || dexPath.isEmpty()) return;
        boolean added = false;
        for (String p : dexPath.split(":")) {
            if (p.isEmpty()) continue;
            File f = new File(p);
            if (!f.isFile()) continue;
            try {
                // Real .dex / .apk / .jar code paths need DEX->bytecode; a
                // plain .jar of class files would also survive conversion (it
                // tolerates non-dex archives by emitting nothing useful), so we
                // first try direct-as-jar for .jar inputs and fall back to
                // conversion. URLClassLoader.addURL is protected; we are the
                // subclass, so we can call it.
                URL converted = MacApkDexConverter.convertFileToJarUrl(f);
                addURL(converted);
                pathList.addDexPath(p);
                added = true;
            } catch (RuntimeException e) {
                // Lazy surface: leave the path unusable. findClass throws
                // ClassNotFoundException for its classes, which is what the
                // on-device "unusable dex element" case also produces.
            }
        }
        if (added) {
            macapkOpen();
            // The app's own loader (com.macapk.loader.AppClassLoader) reads
            // class bytes by reopening the jar BY PATH -- findResource then
            // URL.openStream -- so it can never have an ephemeral jar unlinked
            // under it: every class would fall back to URLClassLoader.findClass
            // and skip ClassFileRewriter. Its ephemeral jar stays on disk,
            // pid-tagged, and the next process's pruneOnce removes it by dead
            // pid. Every dalvik.system loader reads through the held fd.
            if (!isMacApkAppLoader()) macapkReleaseEphemeral();
        }
    }

    /** True for com.macapk.loader.AppClassLoader or a subclass of it. Matched
     *  by NAME so dalvik.system takes no compile-time dependency on the loader
     *  jar (com.macapk is not shipped in the framework jar). */
    private boolean isMacApkAppLoader() {
        for (Class<?> c = getClass(); c != null && c != BaseDexClassLoader.class;
                c = c.getSuperclass()) {
            if ("com.macapk.loader.AppClassLoader".equals(c.getName())) return true;
        }
        return false;
    }

    /** Mac.apk-internal: open every jar on this loader's path NOW, then unlink
     *  the ephemeral ones.
     *
     *  URLClassPath opens a jar LAZILY, by path, on the first lookup that walks
     *  that far (JDK 21 URLClassPath.getLoader), and a path it cannot open at
     *  that moment is dropped for good ("Silently ignore for now"). ART maps its
     *  dex elements when the loader is constructed. So without this, a converted
     *  jar removed between construction and first lookup -- the app clearing its
     *  own getCodeCacheDir(), another process pruning the cache, an in-place
     *  update -- becomes a silent ClassNotFoundException. A findResource miss
     *  walks EVERY entry, and each JarLoader's constructor calls ensureOpen(),
     *  so afterwards every jar is held by fd for the loader's life, which is
     *  also what lets an in-memory dex's ephemeral jar be unlinked right away.
     *
     *  super.findResource is an invokespecial to URLClassLoader.findResource:
     *  AppClassLoader and DelegateLastClassLoader override the lookup with
     *  app-facing logic that must not run here. A raw .dex path that
     *  PathClassLoader also lists fails to open as a jar and is dropped, exactly
     *  as it would be at the first lookup. */
    final void macapkOpenNow() {
        macapkOpen();
        macapkReleaseEphemeral();
    }

    /** The open half of {@link #macapkOpenNow}: force every jar open. */
    final void macapkOpen() {
        super.findResource("META-INF/MACAPK-OPEN-NOW");
    }

    /** The release half of {@link #macapkOpenNow}: unlink this loader's
     *  ephemeral jars. Only after {@link #macapkOpen}, and only for a loader
     *  that reads classes through URLClassPath's held fd. */
    final void macapkReleaseEphemeral() {
        MacApkDexConverter.releaseEphemeral(super.getURLs());
    }

    /** AOSP declares findLibrary PUBLIC on BaseDexClassLoader (it delegates
     *  to pathList.findLibrary). The androidgamesdk GameActivity calls
     *  ((BaseDexClassLoader) getClassLoader()).findLibrary(libname) directly,
     *  so the app dex's invokevirtual resolves against THIS class; without a
     *  public declaration here resolution lands on the protected
     *  java.lang.ClassLoader.findLibrary and the JVM throws
     *  IllegalAccessError (Smash Hit died in onCreate this way). The base
     *  implementation has no library knowledge -- AppClassLoader overrides
     *  with the real translate-and-cache lookup.
     *
     *  Delegation to the nearest AppClassLoader ancestor (added 2026-11-01):
     *  System.loadLibrary dispatches findLibrary to the DEFINING class loader
     *  only -- the JVM does not walk parents for it. On a device a child
     *  DexClassLoader still resolves the app's native libs because its
     *  DexPathList.nativeLibraryDirectories include the app's own lib dir; ours
     *  carry no native-lib knowledge (DexClassLoader even discards its
     *  librarySearchPath), so any System.loadLibrary from a class defined by a
     *  child loader returned null and failed. Concretely: GMS's Chimera
     *  "cronet_dynamite" module is defined by a child DexClassLoader, and its
     *  CronetLibraryLoader.System.loadLibrary("cronet[.<ver>]") never reached
     *  our translator even though the split's libcronet .so was already
     *  extracted and translated into the SAME install lib dir AppClassLoader
     *  indexes -- GMS then fell back to a degraded Java HTTP stack. Walk up to
     *  the owning AppClassLoader (which is where the translate-and-cache lookup
     *  lives) and let it resolve. This can only ADD resolutions: the base used
     *  to return null unconditionally, so nothing that works today changes.
     *  AppClassLoader is final and overrides findLibrary, so it never reaches
     *  this body -- no self-delegation. */
    public String findLibrary(String name) {
        for (ClassLoader p = getParent(); p != null; p = p.getParent()) {
            if (p instanceof com.macapk.loader.AppClassLoader) {
                return ((com.macapk.loader.AppClassLoader) p).findLibrary(name);
            }
        }
        return null;
    }

    /** AOSP override: BaseDexClassLoader.getPackage always returns a non-null
     *  Package, synthesizing an empty one if the package has not otherwise been
     *  defined (real Android has no per-package metadata for app classes, so it
     *  fabricates a blank Package on demand). Plain URLClassLoader returns null
     *  for an undefined package, which NPEs apps that do
     *  getClass().getPackage().getName(). We mirror AOSP: try the JDK's
     *  getDefinedPackage first, and if absent define a blank one.
     *
     *  Note: getPackage(String) is deprecated on the JDK ClassLoader but it is
     *  the method AOSP overrides and the one app bytecode invokes, so the
     *  signature must stay. */
    @Override
    @SuppressWarnings("deprecation")
    protected synchronized Package getPackage(String name) {
        if (name == null) return null;
        Package p = super.getPackage(name);
        if (p != null) return p;
        try {
            // definePackage with all-null metadata mirrors AOSP's synthesized
            // blank Package. It can race/throw IllegalArgumentException if a
            // concurrent caller already defined it; re-read in that case.
            return definePackage(name, null, null, null, null, null, null, null);
        } catch (IllegalArgumentException already) {
            return super.getPackage(name);
        }
    }

    /** AOSP returns "dalvik.system.BaseDexClassLoader[DexPathList[...]]"; some
     *  crash-reporting / logging libraries parse this. We approximate with the
     *  class name plus the configured search URLs so the string is informative
     *  and stable. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append("[DexPathList[[");
        URL[] urls = getURLs();
        for (int i = 0; i < urls.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("dex file \"").append(urls[i]).append('"');
        }
        sb.append("]]]");
        return sb.toString();
    }

    protected static URL[] buildUrls(String dexPath) {
        if (dexPath == null || dexPath.isEmpty()) return new URL[0];
        String[] parts = dexPath.split(":");
        URL[] urls = new URL[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                urls[n++] = new File(p).toURI().toURL();
            } catch (MalformedURLException e) {
                // AOSP skips unusable paths, surfacing errors lazily at findClass.
            }
        }
        if (n < urls.length) {
            URL[] tight = new URL[n];
            System.arraycopy(urls, 0, tight, 0, n);
            return tight;
        }
        return urls;
    }
}
