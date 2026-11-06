import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Real DEX buffers follow the same reflective injection path as protected SDKs. */
public final class InMemoryDexPathSmoke {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void absent(ClassLoader loader, String name) throws Exception {
        try { loader.loadClass(name); throw new AssertionError("unexpected class: " + name); }
        catch (ClassNotFoundException expected) { }
    }
    public static void main(String[] args) throws Exception {
        byte[] first, second;
        try (ZipFile apk = new ZipFile(args[0])) {
            first = apk.getInputStream(apk.getEntry("classes.dex")).readAllBytes();
            second = apk.getInputStream(apk.getEntry("classes2.dex")).readAllBytes();
        }
        ByteBuffer heap = ByteBuffer.wrap(first).asReadOnlyBuffer();
        ByteBuffer direct = ByteBuffer.allocateDirect(second.length + 6);
        direct.position(3); direct.put(second); direct.limit(3 + second.length); direct.position(3);
        Class<?> pathsClass = Class.forName("dalvik.system.DexPathList");
        Method make = pathsClass.getDeclaredMethod("makeInMemoryDexElements", ByteBuffer[].class, List.class);
        make.setAccessible(true);
        List<IOException> failures = new ArrayList<>();
        Object[] elements = (Object[]) make.invoke(null, new ByteBuffer[]{heap, direct}, failures);
        check(elements.length == 2 && failures.isEmpty(), "two valid memory DEX elements");
        check(heap.position() == 0 && heap.limit() == first.length, "heap buffer state preserved");
        check(direct.position() == 3 && direct.limit() == 3 + second.length, "direct buffer slice preserved");
        try (PathClassLoader loader = new PathClassLoader("", ClassLoader.getPlatformClassLoader())) {
            absent(loader, "fixture.Alpha");
            Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList"); pathList.setAccessible(true);
            Object paths = pathList.get(loader);
            Field live = pathsClass.getDeclaredField("dexElements"); live.setAccessible(true);
            live.set(paths, elements);
            check(loader.loadClass("fixture.Alpha").getClassLoader() == loader, "real bytecode defined by injected loader");
            check(loader.loadClass("fixture.Beta").getClassLoader() == loader, "second memory element loads");
            Object[] onlySecond = (Object[]) Array.newInstance(elements.getClass().getComponentType(), 1);
            onlySecond[0] = elements[1]; live.set(paths, onlySecond);
            absent(loader, "fixture.Zed");
            Object[] bad = (Object[]) make.invoke(null, new ByteBuffer[]{ByteBuffer.wrap(new byte[]{1,2,3})}, failures);
            check(bad.length == 0 && failures.size() == 1, "malformed DEX records real IOException");
            try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".jar")), "memory DEX jars unlinked while classes remain usable");
            }
        }
        Object[] appElements = (Object[]) make.invoke(null, new ByteBuffer[]{heap, direct}, new ArrayList<IOException>());
        try (com.macapk.loader.AppClassLoader loader = new com.macapk.loader.AppClassLoader(
                new java.net.URL[0], ClassLoader.getPlatformClassLoader(), null,
                Path.of(System.getProperty("java.io.tmpdir")).toFile(), null, args[0])) {
            Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList"); pathList.setAccessible(true);
            Field live = pathsClass.getDeclaredField("dexElements"); live.setAccessible(true);
            live.set(pathList.get(loader), appElements);
            check(loader.loadClass("fixture.Alpha").getClassLoader() == loader, "app loader defines injected memory class");
            check(loader.loadClass("fixture.Beta").getClassLoader() == loader, "app rewriter reads second unlinked memory DEX");
        }
        System.out.println("memory-dex: PASS (heap/direct, position, real class injection, identity, removal, errors, ephemeral storage)");
    }
}
