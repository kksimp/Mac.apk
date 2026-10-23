// dalvik.system.PathClassLoader -- the loader ART uses for the app's own code
// (and for any colon-separated list of jar/apk/dex files). Apps and libraries
// reach it two ways: reflectively (`Class.forName("dalvik.system.PathClassLoader")`,
// `new PathClassLoader(path, parent)`) and via introspection
// (`getClassLoader() instanceof PathClassLoader`). Without the class present,
// both fail (ClassNotFoundException / a never-true instanceof).
//
// AOSP (libcore/dalvik/src/main/java/dalvik/system/PathClassLoader.java):
//   public PathClassLoader(String dexPath, ClassLoader parent)
//   public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent)
// extends BaseDexClassLoader; it adds NO behavior of its own -- it is purely a
// named subclass so the loader-identity checks resolve. We mirror exactly that:
// a thin BaseDexClassLoader subclass with the two public ctors.
//
// Note: Mac.apk's real app loader is com.macapk.loader.AppClassLoader, which is
// NOT this class (it has bespoke APK-serving + native-lib-translate behavior).
// An app that does `getClassLoader() instanceof PathClassLoader` will see false
// for the primary app loader; that is acceptable -- the load-bearing need is
// that reflective CONSTRUCTION and the TYPE existing both succeed for plugin
// frameworks that build their own PathClassLoader.
package dalvik.system;

public class PathClassLoader extends BaseDexClassLoader {
    // Parallel-capable. ART never serializes a whole class loader: classes load
    // under per-class locks. On HotSpot, a loader that does not register gets
    // getClassLoadingLock(name) == the loader itself, so every thread loading
    // ANY class waits on one lock (JFR on WhatsApp: 718 waits, the UI thread
    // up to 695 ms). Each class up the chain to URLClassLoader must register.
    static { ClassLoader.registerAsParallelCapable(); }

    /** Mac.apk-internal: forwards to BaseDexClassLoader's URL[] ctor.
     *
     *  Exists so AppClassLoader can sit UNDER PathClassLoader in the hierarchy.
     *  On a device the app's loader IS a PathClassLoader, and code checks that:
     *  MultiDex.install, SoLoader/ReLinker, and hot-patch frameworks branch on
     *  `instanceof PathClassLoader`. We extended BaseDexClassLoader directly, so
     *  those checks failed where Android succeeds -- while `instanceof
     *  BaseDexClassLoader` (which SmashHit/GameActivity cast to) passed, which is
     *  why it went unnoticed.
     *
     *  PathClassLoader adds nothing over BaseDexClassLoader except the dex-path
     *  conversion its String ctors perform, and a URL[] caller has already done
     *  that conversion -- so forwarding is behaviour-preserving. */
    protected PathClassLoader(java.net.URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /** AOSP: dexPath is a colon-separated list of .jar/.apk/.dex code paths;
     *  parent is the delegation parent. Real .dex/.apk entries are converted to
     *  JVM bytecode via BaseDexClassLoader's buildUrls path; class-file jars are
     *  used directly. */
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
        // BaseDexClassLoader.buildUrls maps each path to a file: URL directly.
        // For .dex/.apk plugin paths the caller should use DexClassLoader (which
        // converts); PathClassLoader on a real device feeds DEX through ART. To
        // keep the named-construction path functional for dex/apk inputs we
        // additionally convert any non-class-file code paths.
        addConvertedDexPaths(dexPath);
        macapkOpenNow();
    }

    /** AOSP: second ctor adds an explicit library search path (advisory here).
     *  DelegateLastClassLoader constructs through this one, so it inherits the
     *  open-now below; macapkOpenNow calls URLClassLoader.findResource
     *  non-virtually, so DelegateLast's child-first overrides do not run on
     *  its half-constructed instance. */
    public PathClassLoader(String dexPath, String librarySearchPath,
                           ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
        addConvertedDexPaths(dexPath);
        macapkOpenNow();
    }

    /** buildUrls already added each path as a plain file: URL. For .dex/.apk
     *  inputs that is not loadable as bytecode, so additionally append the
     *  converted jar. (A class-file .jar is already usable via the
     *  plain URL, so we skip converting those to avoid a wasted conversion.) */
    private void addConvertedDexPaths(String dexPath) {
        if (dexPath == null || dexPath.isEmpty()) return;
        for (String p : dexPath.split(":")) {
            if (p.isEmpty()) continue;
            java.io.File f = new java.io.File(p);
            if (!f.isFile()) continue;
            String lower = p.toLowerCase();
            if (lower.endsWith(".dex") || lower.endsWith(".apk")
                    || lower.endsWith(".zip")) {
                try {
                    addURL(MacApkDexConverter.convertFileToJarUrl(f));
                } catch (RuntimeException skip) {
                    // unusable; surfaces lazily at findClass (AOSP-equivalent)
                }
            }
        }
    }
}
