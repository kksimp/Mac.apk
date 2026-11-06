// dalvik.system.DexClassLoader -- the Android-specific class loader
// that loads classes from .dex / .jar / .apk files. Apps create one
// to dynamically load plugins, ad-network adapters, or DRM-wrapped
// code paths. Reaches through PathClassLoader in real AOSP.
//
// Mac.apk's ClassLoader hierarchy is OpenJDK's URLClassLoader chain. Loading
// from a .dex / .apk file requires a DEX->JVM-bytecode step; we run
// com.macapk.dex (the same in-process converter the primary app dex uses) over
// each supplied code path at construction time and serve the resulting jars
// through URLClassLoader. The converted jar is a persistent, content-addressed
// entry in the app's code_cache, so the next launch reuses it instead of
// converting again (MacApkDexConverter, docs/dynamic-dex-cache_2026-09-24.md). `Class.forName("dalvik.system.DexClassLoader")` and
// `new DexClassLoader(...)` resolve, AND findClass over a real downloaded-plugin
// .dex now succeeds, which the prior shell could not do.
package dalvik.system;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

// Real AOSP hierarchy: DexClassLoader extends BaseDexClassLoader extends
// ClassLoader. We mirror that so apps that introspect the loader chain (or cast
// to BaseDexClassLoader) see the expected ancestry.
public class DexClassLoader extends BaseDexClassLoader {

    /** AOSP signature: takes a colon-separated list of .dex/.jar files,
     *  an optimized-DEX directory (we ignore), a library-search-path
     *  (we ignore), and a parent ClassLoader. We convert each real code path
     *  to a jar of JVM bytecode so findClass over it works. */
    public DexClassLoader(String dexPath, String optimizedDirectory,
                           String librarySearchPath, ClassLoader parent) {
        super(convertDexPath(dexPath), parent);
        // Hold every converted jar open from construction, as ART maps its
        // dex elements here (see BaseDexClassLoader.macapkOpenNow).
        macapkOpenNow();
    }

    /** AOSP also exposes a no-arg-style constructor used by some
     *  reflective code paths. */
    protected DexClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    /** Convert each colon-separated code path: a .dex / .apk is run through
     *  com.macapk.dex to a jar of JVM bytecode; a path that is already a plain
     *  .jar of class files is used directly (running the converter on a
     *  class-file jar yields nothing useful). A path that cannot be
     *  opened/converted is skipped and surfaces lazily at findClass, matching
     *  AOSP's "unusable element" tolerance. */
    private static URL[] convertDexPath(String dexPath) {
        List<URL> urls = new ArrayList<>();
        if (dexPath == null || dexPath.isEmpty()) return new URL[0];
        for (String p : dexPath.split(":")) {
            if (p.isEmpty()) continue;
            File f = new File(p);
            if (!f.isFile()) continue;
            String lower = p.toLowerCase();
            try {
                if (lower.endsWith(".jar") && !isDexArchive(f)) {
                    // Plain bytecode jar: load as-is.
                    urls.add(f.toURI().toURL());
                } else {
                    URL code = MacApkDexConverter.convertFileToJarUrl(f);
                    // ART's element keeps the original archive for resources.
                    // Integrity manifests and marker resources must still name
                    // that archive, not the generated class-file cache.
                    urls.add(f.toURI().toURL());
                    urls.add(code);
                }
            } catch (Exception skip) {
                // leave unusable; findClass will throw for its classes
            }
        }
        return urls.toArray(new URL[0]);
    }

    /** A .jar produced by D8/R8 wraps DEX (classes.dex inside the zip); a plain
     *  build jar wraps .class files. We peek for a classes.dex entry to decide
     *  whether the jar needs conversion. Best-effort: any read error treats it
     *  as needing conversion (the common dynamic-load case is a dex archive). */
    private static boolean isDexArchive(File f) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            return zf.getEntry("classes.dex") != null;
        } catch (Exception e) {
            return true;
        }
    }
}
