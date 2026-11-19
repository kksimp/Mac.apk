import dalvik.system.PathClassLoader;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.Adler32;

/** Shared hierarchy metadata must preserve each reordered Element's own duplicate definition. */
public final class DuplicateMemoryDexSmoke {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static int value(ClassLoader loader, String name) throws Exception {
        Class<?> type = loader.loadClass(name);
        check(type.getClassLoader() == loader, "duplicate class defining loader identity");
        return (int) type.getMethod("value").invoke(null);
    }
    static Object[] select(Object[] elements, int... order) {
        Object[] result = (Object[]) Array.newInstance(elements.getClass().getComponentType(), order.length);
        for (int i = 0; i < order.length; i++) result[i] = elements[order[i]];
        return result;
    }
    public static void main(String[] args) throws Exception {
        byte[] first = Files.readAllBytes(Path.of(args[0]));
        byte[] second = first.clone();
        String[] offsets = Files.readString(Path.of(args[1])).split(" ");
        ByteBuffer patch = ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN);
        patch.putShort(Integer.parseInt(offsets[0]) + 2, (short) 84);
        patch.putShort(Integer.parseInt(offsets[1]) + 2, (short) 85);
        byte[] signature = MessageDigest.getInstance("SHA-1").digest(java.util.Arrays.copyOfRange(second, 32, second.length));
        System.arraycopy(signature, 0, second, 12, signature.length);
        Adler32 checksum = new Adler32(); checksum.update(second, 12, second.length - 12);
        patch.putInt(8, (int) checksum.getValue());
        int[][] orders = {{0, 1}, {1, 0}, {0}, {1}};
        for (boolean directA : new boolean[]{false, true}) for (boolean directB : new boolean[]{false, true}) {
            ByteBuffer a = CrossMemoryDexSmoke.buffer(first, directA, 3);
            ByteBuffer b = CrossMemoryDexSmoke.buffer(second, directB, 5);
            for (int[] order : orders) {
                Object[] elements = CrossMemoryDexSmoke.elements(a, b);
                try (PathClassLoader loader = CrossMemoryDexSmoke.loader(select(elements, order))) {
                    int expected = order[0] == 0 ? 42 : 84;
                    check(value(loader, "fixture.DynamicAlpha") == expected, "first Element owns duplicate Alpha");
                    check(value(loader, "fixture.DynamicBeta") == expected + 1, "first Element owns duplicate Beta");
                }
            }
            check(a.position() == 3 && a.limit() == 3 + first.length, "A source state preserved");
            check(b.position() == 5 && b.limit() == 5 + second.length, "B source state preserved");
            System.out.println("PASS duplicate memory definitions: A=" + (directA ? "direct" : "heap")
                               + " B=" + (directB ? "direct" : "heap") + "; reorder/removal/source ownership");
        }
    }
}
