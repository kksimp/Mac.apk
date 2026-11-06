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

/** A static interface call must see interface metadata in a sibling DEX buffer. */
public final class CrossMemoryDexSmoke {
    static final Class<?> PATHS;
    static final Method MAKE;
    static final Field PATH_LIST;
    static final Field ELEMENTS;
    static {
        try {
            PATHS = Class.forName("dalvik.system.DexPathList");
            MAKE = PATHS.getDeclaredMethod("makeInMemoryDexElements", ByteBuffer[].class, List.class);
            MAKE.setAccessible(true);
            PATH_LIST = BaseDexClassLoader.class.getDeclaredField("pathList"); PATH_LIST.setAccessible(true);
            ELEMENTS = PATHS.getDeclaredField("dexElements"); ELEMENTS.setAccessible(true);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static ByteBuffer buffer(byte[] image, boolean direct, int prefix) {
        ByteBuffer result = direct ? ByteBuffer.allocateDirect(image.length + prefix + 2)
                                   : ByteBuffer.allocate(image.length + prefix + 2);
        result.position(prefix); result.put(image); result.limit(prefix + image.length); result.position(prefix);
        return result.asReadOnlyBuffer();
    }
    static Object[] elements(ByteBuffer caller, ByteBuffer provider) throws Exception {
        List<IOException> errors = new ArrayList<>();
        Object[] result = (Object[]) MAKE.invoke(null, new ByteBuffer[]{caller, provider}, errors);
        check(result.length == 2 && errors.isEmpty(), "two independent valid DEX elements: " + errors);
        return result;
    }
    static PathClassLoader loader(Object[] elements) throws Exception {
        PathClassLoader result = new PathClassLoader("", ClassLoader.getPlatformClassLoader());
        ELEMENTS.set(PATH_LIST.get(result), elements);
        return result;
    }
    static void absent(ClassLoader loader, String name) throws Exception {
        try { loader.loadClass(name); throw new AssertionError("removed element leaked class " + name); }
        catch (ClassNotFoundException expected) { }
    }
    static Object[] only(Object[] source, int index) {
        Object[] result = (Object[]) Array.newInstance(source.getClass().getComponentType(), 1);
        result[0] = source[index]; return result;
    }
    public static void main(String[] args) throws Exception {
        byte[] callerImage = Files.readAllBytes(Path.of(args[0]));
        byte[] providerImage = Files.readAllBytes(Path.of(args[1]));
        for (boolean directA : new boolean[]{false, true}) for (boolean directB : new boolean[]{false, true}) {
            ByteBuffer a = buffer(callerImage, directA, 3), b = buffer(providerImage, directB, 5);
            try (PathClassLoader live = loader(elements(a, b))) {
                Class<?> api = live.loadClass("fixture.CrossApi");
                check(api.isInterface(), "B defines a real interface");
                check((int) api.getMethod("value").invoke(null) == 42, "B's static interface method executes");
                Class<?> caller = live.loadClass("fixture.CrossCaller");
                check((int) caller.getMethod("value").invoke(null) == 42,
                      "A invokes B through a genuine InterfaceMethodref");
                check(api.getClassLoader() == live && caller.getClassLoader() == live, "same defining loader");
            }
            // Sharing the hierarchy must not let a remaining Element own its sibling's classes.
            try (PathClassLoader aOnly = loader(only(elements(a, b), 0))) { absent(aOnly, "fixture.CrossApi"); }
            try (PathClassLoader bOnly = loader(only(elements(a, b), 1))) { absent(bOnly, "fixture.CrossCaller"); }
            check(a.position() == 3 && a.limit() == 3 + callerImage.length, "A source range/cursor preserved");
            check(b.position() == 5 && b.limit() == 5 + providerImage.length, "B source range/cursor preserved");
            System.out.println("PASS cross-buffer static interface: A=" + (directA ? "direct" : "heap")
                               + " B=" + (directB ? "direct" : "heap") + "; element ownership/ranges/identity");
        }
    }
}
