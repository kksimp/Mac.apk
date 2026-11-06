package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
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
        for (Element e : opened) {
            try { e.close(); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        opened.clear();
        if (failure != null) throw failure;
    }

    /** One factory call is a multi-DEX unit for type resolution. Method bodies
     * stay live per source; sibling metadata supplies interface/superclass facts
     * without making sibling classes visible through the wrong Element. */
    private static final class MemoryDexGroup {
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final List<byte[]> images = new ArrayList<>();
        private final java.util.Map<String, Integer> firstOwners = new java.util.HashMap<>();
        private final java.util.Map<Integer, com.macapk.dex.DexConverter.Session> duplicateSessions = new java.util.HashMap<>();
        private com.macapk.dex.DexConverter.Session session;

        int add(ByteBuffer buffer, byte[] image, java.util.Set<String> names) {
            int index = buffers.size();
            buffers.add(buffer.duplicate());
            images.add(image);
            for (String name : names) firstOwners.putIfAbsent(name, index);
            return index;
        }
        synchronized byte[] classBytes(int index, String name) throws IOException {
            byte[] current = Element.snapshot(buffers.get(index));
            if (!java.util.Arrays.equals(images.get(index), current)) {
                images.set(index, current);
                session = null;
                duplicateSessions.clear();
            }
            com.macapk.dex.DexConverter.Session converter;
            if (firstOwners.get(name) == index) {
                if (session == null) session = com.macapk.dex.DexConverter.open(images.toArray(new byte[0][]));
                converter = session;
            } else {
                // A caller can reorder Element[] after the factory. A duplicate
                // must still come from the selected element, not factory order.
                converter = duplicateSessions.get(index);
                if (converter == null) {
                    byte[][] ordered = new byte[images.size()][];
                    ordered[0] = images.get(index);
                    int next = 1;
                    for (int i = 0; i < images.size(); i++) if (i != index) ordered[next++] = images.get(i);
                    converter = com.macapk.dex.DexConverter.open(ordered);
                    duplicateSessions.put(index, converter);
                }
            }
            byte[] result = converter.classBytes(name);
            if (result == null && converter.errors().containsKey(name))
                throw new IOException("Cannot translate in-memory DEX class " + name + ": " + converter.errors().get(name));
            return result;
        }
    }

    static final class Element {
        final File path;
        private final URL[] urls;
        // ART retains the original in-memory DEX. Packers can finish preparing
        // method bodies after makeInMemoryDexElements returns, so conversion
        // belongs at class lookup rather than at the factory boundary.
        private MemoryDexGroup memoryGroup;
        private int memoryIndex;
        private java.util.Set<String> memoryNames = Collections.emptySet();
        private volatile URLClassLoader resources;
        private volatile boolean closed;
        Element(File path) throws IOException { this(path, new URL[]{path.toURI().toURL()}); }
        Element(File path, URL[] urls) { this.path = path; this.urls = urls; }
        Element(ByteBuffer buffer, MemoryDexGroup group) {
            this.path = null;
            this.urls = new URL[0];
            byte[] image = snapshot(buffer);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (com.macapk.dex.DexClass cls : com.macapk.dex.DexFile.parse(image).classes()) names.add(cls.name());
            this.memoryNames = java.util.Set.copyOf(names);
            this.memoryIndex = group.add(buffer, image, memoryNames);
            this.memoryGroup = group;
        }
        private static byte[] snapshot(ByteBuffer buffer) {
            ByteBuffer view = buffer.duplicate();
            byte[] result = new byte[view.remaining()];
            view.get(result);
            return result;
        }
        private synchronized byte[] memoryClassBytes(String name) throws IOException {
            if (closed || !memoryNames.contains(name)) return null;
            return memoryGroup.classBytes(memoryIndex, name);
        }
        private URLClassLoader resources() {
            if (resources == null) synchronized (this) {
                if (resources == null) resources = new URLClassLoader(urls, null);
            }
            return resources;
        }
        URL findResource(String name) {
            return closed || memoryGroup != null ? null : resources().findResource(name);
        }
        Enumeration<URL> findResources(String name) throws IOException {
            return closed || memoryGroup != null ? Collections.emptyEnumeration() : resources().findResources(name);
        }
        byte[] findClassBytes(String name) throws IOException {
            if (closed) return null;
            if (memoryGroup != null) {
                return name.endsWith(".class") ? memoryClassBytes(name.substring(0, name.length() - 6)) : null;
            }
            URL resource = findResource(name);
            if (resource == null) return null;
            try (var in = resource.openStream()) { return in.readAllBytes(); }
        }
        synchronized void close() throws IOException {
            closed = true;
            memoryGroup = null;
            memoryNames = Collections.emptySet();
            if (resources != null) resources.close();
        }
        @Override public String toString() {
            return path == null && urls.length == 0 ? "in-memory dex file" : "dex file \"" + (path == null ? urls[0] : path) + "\"";
        }
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
    /** Legacy libcore reflection entry, still used by dynamically loaded SDKs.
     * Each element retains its input buffer and converts current method code
     * only when a class is requested, without persisting memory DEX.
     */
    public static Element[] makeInMemoryDexElements(ByteBuffer[] buffers, List<IOException> suppressed) {
        java.util.Objects.requireNonNull(buffers, "dexFiles == null");
        List<Element> elements = new ArrayList<>();
        MemoryDexGroup group = new MemoryDexGroup();
        for (ByteBuffer buffer : buffers) {
            java.util.Objects.requireNonNull(buffer, "dexFiles contains a null buffer");
            try {
                elements.add(new Element(buffer, group));
            } catch (RuntimeException ex) {
                suppressed.add(new IOException("Cannot load in-memory dex element", ex));
            }
        }
        return elements.toArray(new Element[0]);
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
    byte[] findClassBytes(String name) throws IOException {
        for (Element element : dexElements) {
            byte[] found = use(element).findClassBytes(name);
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
