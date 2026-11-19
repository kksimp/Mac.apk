import android.os.SharedMemory;
import android.system.OsConstants;
import java.nio.ByteBuffer;

/** Resolve the public fields at runtime, as a precompiled Android SDK does. */
public final class SharedMemoryProtectionSmoke {
    public static void main(String[] args) throws Exception {
        int read = OsConstants.class.getField("PROT_READ").getInt(null);
        int write = OsConstants.class.getField("PROT_WRITE").getInt(null);
        try (SharedMemory memory = SharedMemory.create("protection-smoke", 16)) {
            ByteBuffer writable = memory.map(read | write, 0, 16);
            writable.putInt(0, 0x12345678);
            ByteBuffer readonly = memory.map(read, 0, 16);
            if (readonly.getInt(0) != 0x12345678)
                throw new AssertionError("SharedMemory mappings must alias");
            try {
                readonly.putInt(0, 1);
                throw new AssertionError("read-only mapping accepted a write");
            } catch (java.nio.ReadOnlyBufferException expected) { }
        }
        System.out.println("shared-memory-protection: PASS (runtime field resolution, aliasing, protection)");
    }
}
