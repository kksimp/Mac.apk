import com.macapk.loader.AppClassLoader;
import dalvik.system.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Real DEX + reflective path mutation; no application downloads or ART pointers. */
public final class LoaderMutationRegressionSmoke {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static Object paths(BaseDexClassLoader loader) throws Exception {
        Field f = BaseDexClassLoader.class.getDeclaredField("pathList");
        f.setAccessible(true);
        return f.get(loader);
    }
    static Field elements(Object paths) throws Exception {
        Field f = paths.getClass().getDeclaredField("dexElements");
        f.setAccessible(true);
        return f;
    }
    static Object[] prepare(BaseDexClassLoader loader, Path file) throws Exception {
        Object paths = paths(loader);
        Method make = paths.getClass().getDeclaredMethod("makeDexElements", List.class, File.class, List.class, ClassLoader.class);
        make.setAccessible(true);
        List<IOException> errors = new ArrayList<>();
        Object[] result = (Object[]) make.invoke(null, List.of(file.toFile()), null, errors, loader);
        check(errors.isEmpty() && result.length == 1, "prepare path: " + errors);
        return result;
    }
    static Object[] join(Object[] a, Object[] b) {
        Object[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
    static int value(BaseDexClassLoader loader, String name) throws Exception {
        Class<?> cls = loader.loadClass("fixture.Dynamic" + name);
        check(cls.getClassLoader() == loader, "defining loader identity");
        return (int) cls.getMethod("value").invoke(null);
    }
    static void missing(BaseDexClassLoader loader, String name) throws Exception {
        try {
            loader.loadClass("fixture.Dynamic" + name);
            throw new AssertionError("removed lazy source still visible");
        } catch (ClassNotFoundException expected) { }
    }
    static void unlinked(Path root, Path dex) throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            boolean memory = mode != 0;
            byte[] image = Files.readAllBytes(dex);
            ByteBuffer buffer = mode == 2 ? ByteBuffer.allocateDirect(image.length) : ByteBuffer.allocate(image.length);
            buffer.put(image).flip();
            try (BaseDexClassLoader loader = memory
                    ? new InMemoryDexClassLoader(buffer, ClassLoader.getPlatformClassLoader())
                    : new PathClassLoader(dex.toString(), ClassLoader.getPlatformClassLoader())) {
                List<Path> jars = Arrays.stream(loader.getURLs()).filter(u -> u.getPath().endsWith(".jar"))
                    .map(u -> Path.of(u.getPath())).toList();
                check(!jars.isEmpty() && jars.stream().noneMatch(Files::exists), "converted JAR already unlinked");
                if (memory) {
                    Object paths = paths(loader);
                    Field f = elements(paths);
                    f.set(paths, join((Object[]) f.get(paths), prepare(loader, root)));
                }
                check(value(loader, "Alpha") == 42 && value(loader, "Beta") == 43,
                    "read original classes after unlink and mutation");
            }
        }
        System.out.println("PASS unlinked PathClassLoader and appended InMemoryDexClassLoader");
    }
    static Path patch(Path root) throws Exception {
        Path src = root.resolve("DynamicBeta.java");
        Files.writeString(src, "package fixture; public class DynamicBeta { public static int value() { return 99; } }");
        Path classes = Files.createDirectories(root.resolve("patch-classes"));
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", classes.toString(), src.toString()) == 0,
            "compile patch class");
        Path jar = root.resolve("patch.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture/DynamicBeta.class"));
            out.write(Files.readAllBytes(classes.resolve("fixture/DynamicBeta.class")));
            out.closeEntry();
        }
        return jar;
    }
    static void lazy(Path root, Path dex) throws Exception {
        Path patch = patch(root);
        // Start both before and after mutation, with an empty or a missing initial code path.
        for (boolean started : new boolean[]{false, true}) {
            for (int mode = 0; mode < 4; mode++) {
                URL[] code = mode % 2 == 0 ? new URL[0] : new URL[]{root.resolve("missing-classes.jar").toUri().toURL()};
                try (AppClassLoader loader = new AppClassLoader(code, ClassLoader.getPlatformClassLoader(), null, root.toFile(), null, dex.toString())) {
                    if (started) check(value(loader, "Alpha") == 42, "lazy before mutation");
                    Object paths = paths(loader);
                    Field f = elements(paths);
                    Object[] original = (Object[]) f.get(paths);
                    Object[] added = prepare(loader, mode < 2 ? root : patch);
                    f.set(paths, join(original, added));
                    check(value(loader, "Beta") == 43, "append preserves lazy source and precedence");
                }
            }
        }
        try (AppClassLoader loader = new AppClassLoader(new URL[0], ClassLoader.getPlatformClassLoader(), null, root.toFile(), null, dex.toString())) {
            Object paths = paths(loader);
            Field f = elements(paths);
            Object[] original = (Object[]) f.get(paths);
            Object[] added = prepare(loader, patch);
            f.set(paths, join(added, original));
            check(value(loader, "Beta") == 99, "prepend outranks lazy source");
            f.set(paths, added);
            missing(loader, "Alpha");
            f.set(paths, join(added, original));
            check(value(loader, "Alpha") == 42, "restored lazy element works");
        }
        // addDexPath changes both URLClassLoader and dexElements; its converted
        // JAR must not suppress a not-yet-started original APK lazy source.
        try (AppClassLoader loader = new AppClassLoader(new URL[0], ClassLoader.getPlatformClassLoader(), null, root.toFile(), null, dex.toString())) {
            loader.addDexPath(dex.getParent().resolve("patch.dex").toString());
            check(value(loader, "Beta") == 43, "addDexPath preserves original lazy precedence");
        }
        System.out.println("PASS lazy source: append, prepend, removal, restoration, before/after first lookup");
    }
    public static void main(String[] args) throws Exception {
        Path dex = Path.of(args[0]), root = Files.createDirectory(dex.getParent().resolve(args[1]));
        if (args[1].equals("unlinked")) unlinked(root, dex);
        else lazy(root, dex);
    }
}
