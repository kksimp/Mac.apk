package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipFile;

/** Android's reflective dex-element surface, backed by converted JVM code.
 * The live array is also the search order: libraries may append, prepend or
 * replace it through reflection (AOSP libcore dalvik/system/DexPathList).
 */
public final class DexPathList {
    volatile Element[] dexElements = new Element[0];
    List<File> nativeLibraryDirectories = new ArrayList<>();
    private Element[] initial = dexElements;
    private final java.util.Set<Element> opened = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Element use(Element e) { opened.add(e); return e; }
    void open() {
        for (Element e : dexElements) use(e).findResource("META-INF/MACAPK-OPEN-NOW");
    }
    void close() throws IOException {
        IOException failure = null;
        for (Element e : opened) if (e.resources != null) {
            try { e.resources.close(); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        opened.clear();
        if (failure != null) throw failure;
    }

    static final class Element {
        final File path;
        private final URL[] urls;
        private volatile URLClassLoader resources;
        Element(File path) throws IOException { this(path, new URL[]{path.toURI().toURL()}); }
        Element(File path, URL[] urls) { this.path = path; this.urls = urls; }
        private URLClassLoader resources() {
            if (resources == null) synchronized (this) {
                if (resources == null) resources = new URLClassLoader(urls, null);
            }
            return resources;
        }
        URL findResource(String name) { return resources().findResource(name); }
        Enumeration<URL> findResources(String name) throws IOException {
            return resources().findResources(name);
        }
        @Override public String toString() { return "dex file \"" + (path == null ? urls[0] : path) + "\""; }
    }

    DexPathList() {}
    DexPathList(ClassLoader loader, URL[] urls) {
        Element[] elements = new Element[urls.length];
        for (int i = 0; i < urls.length; i++) elements[i] = new Element(null, new URL[]{urls[i]});
        dexElements = elements;
        initial = elements.clone();
    }
    DexPathList(ClassLoader loader, String dexPath, String libraryPath, File optimized) {
        if (libraryPath != null) for (String p : libraryPath.split(File.pathSeparator)) {
            if (!p.isEmpty()) nativeLibraryDirectories.add(new File(p));
        }
        List<File> files = new ArrayList<>();
        if (dexPath != null) for (String p : dexPath.split(File.pathSeparator)) {
            if (!p.isEmpty()) files.add(new File(p));
        }
        dexElements = makeDexElements(files, optimized, new ArrayList<>(), loader);
        initial = dexElements.clone();
    }

    private static Element[] makeDexElements(List<File> files, File optimized,
            List<IOException> suppressed, ClassLoader loader) {
        List<Element> elements = new ArrayList<>();
        for (File file : files) {
            try {
                if (file.isDirectory()) { elements.add(new Element(file)); continue; }
                boolean dex = true;
                try (ZipFile zip = new ZipFile(file)) { dex = zip.getEntry("classes.dex") != null; }
                catch (java.util.zip.ZipException rawDex) { /* raw DEX input */ }
                URL source = file.toURI().toURL();
                elements.add(new Element(file, dex
                    ? new URL[]{MacApkDexConverter.convertFileToJarUrl(file), source}
                    : new URL[]{source}));
            } catch (Exception ex) { suppressed.add(new IOException("Cannot load dex element " + file, ex)); }
        }
        return elements.toArray(new Element[0]);
    }
    private static Element[] makePathElements(List<File> files, File optimized, List<IOException> suppressed) {
        return makeDexElements(files, optimized, suppressed, null);
    }
    boolean changed() {
        Element[] current = dexElements;
        if (current.length != initial.length) return true;
        for (int i = 0; i < current.length; i++) if (current[i] != initial[i]) return true;
        return false;
    }
    /** Bind the original APK resources to the initial converted-code element.
     * They then follow that element's position/removal rather than a global
     * fallback that can bypass a reflective path replacement. */
    synchronized void addInitialResources(URL[] resources) {
        if(resources.length==0)return;
        if(changed())throw new IllegalStateException("Initial resources must precede path mutation");
        java.util.LinkedHashSet<URL> combined=new java.util.LinkedHashSet<>();
        Element first=dexElements.length==0?null:dexElements[0];
        if(first!=null)java.util.Collections.addAll(combined,first.urls);
        java.util.Collections.addAll(combined,resources);
        Element[] next=dexElements.length==0?new Element[1]:dexElements.clone();
        next[0]=new Element(first==null?null:first.path,combined.toArray(new URL[0]));
        dexElements=next;
        initial=next.clone();
    }
    synchronized void addURL(URL url) {
        Element[] old = dexElements;
        Element[] next = java.util.Arrays.copyOf(old, old.length + 1);
        next[old.length] = new Element(null, new URL[]{url});
        dexElements = next;
    }
    URL findResource(String name) {
        for (Element element : dexElements) {
            URL found = use(element).findResource(name);
            if (found != null) return found;
        }
        return null;
    }
    Enumeration<URL> findResources(String name) throws IOException {
        List<URL> found = new ArrayList<>();
        for (Element element : dexElements) {
            Enumeration<URL> urls = use(element).findResources(name);
            while (urls.hasMoreElements()) found.add(urls.nextElement());
        }
        return Collections.enumeration(found);
    }
    public String findLibrary(String name) { return null; }
    public String getDexPaths() {
        List<String> paths = new ArrayList<>();
        for (Element e : dexElements) paths.add(e.toString());
        return String.join(File.pathSeparator, paths);
    }
    @Override public String toString() { return "DexPathList[" + getDexPaths() + ",nativeLibraryDirectories=" + nativeLibraryDirectories + "]"; }
}
