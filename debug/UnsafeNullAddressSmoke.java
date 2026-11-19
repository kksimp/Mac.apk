import com.macapk.loader.MacApkUnsafe;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Replays Meco HiddenApiBypass.getDeclaredMethods' Class.methods walk. */
public final class UnsafeNullAddressSmoke {
    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        long methods = MacApkUnsafe.getLong(unsafe, Object.class, 32L);
        if (methods != 0) throw new AssertionError("VM layout must remain opaque");
        // Meco reads the method count unconditionally, even for an empty table.
        if (MacApkUnsafe.getInt(unsafe, methods) != 0)
            throw new AssertionError("Empty method table must have zero methods");
        long memory = unsafe.allocateMemory(4);
        try {
            unsafe.putInt(memory, 123456789);
            if (MacApkUnsafe.getInt(unsafe, memory) != 123456789)
                throw new AssertionError("Valid app memory read changed");
        } finally {
            unsafe.freeMemory(memory);
        }
        System.out.println("PASS: Meco empty method table and valid native memory");
    }
}
