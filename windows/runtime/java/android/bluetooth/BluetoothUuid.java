// android.bluetooth.BluetoothUuid -- the service UUIDs of the Bluetooth
// profiles, and the helpers that read a UUID out of advertising / SDP bytes.
// Pure data and arithmetic: nothing here needs a radio, so it is AOSP's own
// logic. Mirrors packages/modules/Bluetooth
// framework/java/android/bluetooth/BluetoothUuid.java.
package android.bluetooth;

import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

public final class BluetoothUuid {

    public static final ParcelUuid A2DP_SINK = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid A2DP_SOURCE = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid ADV_AUDIO_DIST = ParcelUuid.fromString("0000110D-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HSP = ParcelUuid.fromString("00001108-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HSP_AG = ParcelUuid.fromString("00001112-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HFP = ParcelUuid.fromString("0000111E-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HFP_AG = ParcelUuid.fromString("0000111F-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AVRCP = ParcelUuid.fromString("0000110E-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AVRCP_CONTROLLER = ParcelUuid.fromString("0000110F-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AVRCP_TARGET = ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid OBEX_OBJECT_PUSH = ParcelUuid.fromString("00001105-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid HID = ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid HOGP = ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid PANU = ParcelUuid.fromString("00001115-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid NAP = ParcelUuid.fromString("00001116-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid BNEP = ParcelUuid.fromString("0000000f-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid PBAP_PCE = ParcelUuid.fromString("0000112e-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid PBAP_PSE = ParcelUuid.fromString("0000112f-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MAP = ParcelUuid.fromString("00001134-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MNS = ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MAS = ParcelUuid.fromString("00001132-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid SAP = ParcelUuid.fromString("0000112D-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HEARING_AID = ParcelUuid.fromString("0000FDF0-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid LE_AUDIO = ParcelUuid.fromString("0000184E-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid BASE_UUID = ParcelUuid.fromString("00000000-0000-1000-8000-00805F9B34FB");

    /** Length of bytes for 16 bit UUID. */
    public static final int UUID_BYTES_16_BIT = 2;
    /** Length of bytes for 32 bit UUID. */
    public static final int UUID_BYTES_32_BIT = 4;
    /** Length of bytes for 128 bit UUID. */
    public static final int UUID_BYTES_128_BIT = 16;

    private BluetoothUuid() {}

    /** Whether the two arrays have a UUID in common. AOSP: two absent (null)
     *  arrays match, and an absent one matches an empty one. */
    public static boolean containsAnyUuid(ParcelUuid[] uuidA, ParcelUuid[] uuidB) {
        if (uuidA == null && uuidB == null) return true;
        if (uuidA == null) return uuidB.length == 0;
        if (uuidB == null) return uuidA.length == 0;
        HashSet<ParcelUuid> uuidSet = new HashSet<ParcelUuid>(Arrays.asList(uuidA));
        for (ParcelUuid uuid : uuidB) {
            if (uuidSet.contains(uuid)) return true;
        }
        return false;
    }

    /**
     * Parses a UUID from its little-endian bytes: 2, 4 or 16 of them. The
     * short forms are completed with the Bluetooth base UUID.
     *
     * @throws IllegalArgumentException for null or any other length
     */
    public static ParcelUuid parseUuidFrom(byte[] uuidBytes) {
        if (uuidBytes == null) {
            throw new IllegalArgumentException("uuidBytes cannot be null");
        }
        int length = uuidBytes.length;
        if (length != UUID_BYTES_16_BIT && length != UUID_BYTES_32_BIT && length != UUID_BYTES_128_BIT) {
            throw new IllegalArgumentException("uuidBytes length invalid - " + length);
        }
        if (length == UUID_BYTES_128_BIT) {
            ByteBuffer buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.LITTLE_ENDIAN);
            long msb = buf.getLong(8);
            long lsb = buf.getLong(0);
            return new ParcelUuid(new UUID(msb, lsb));
        }
        long shortUuid;
        if (length == UUID_BYTES_16_BIT) {
            shortUuid = uuidBytes[0] & 0xFF;
            shortUuid += (uuidBytes[1] & 0xFF) << 8;
        } else {
            shortUuid = uuidBytes[0] & 0xFF;
            shortUuid += (uuidBytes[1] & 0xFF) << 8;
            shortUuid += (uuidBytes[2] & 0xFF) << 16;
            shortUuid += (long) (uuidBytes[3] & 0xFF) << 24;
        }
        long msb = BASE_UUID.getUuid().getMostSignificantBits() + (shortUuid << 32);
        long lsb = BASE_UUID.getUuid().getLeastSignificantBits();
        return new ParcelUuid(new UUID(msb, lsb));
    }

    /** The UUID as little-endian bytes, in the shortest of the three forms that holds it. */
    public static byte[] uuidToBytes(ParcelUuid uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid cannot be null");
        }
        if (is16BitUuid(uuid)) {
            int uuidVal = getServiceIdentifierFromParcelUuid(uuid);
            return new byte[] {(byte) (uuidVal & 0xFF), (byte) ((uuidVal & 0xFF00) >> 8)};
        }
        if (is32BitUuid(uuid)) {
            int uuidVal = getServiceIdentifierFromParcelUuid(uuid);
            return new byte[] {(byte) (uuidVal & 0xFF), (byte) ((uuidVal & 0xFF00) >> 8),
                    (byte) ((uuidVal & 0xFF0000) >> 16), (byte) ((uuidVal & 0xFF000000) >> 24)};
        }
        long msb = uuid.getUuid().getMostSignificantBits();
        long lsb = uuid.getUuid().getLeastSignificantBits();
        byte[] uuidBytes = new byte[UUID_BYTES_128_BIT];
        ByteBuffer buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(8, msb);
        buf.putLong(0, lsb);
        return uuidBytes;
    }

    /** Whether the UUID is the base UUID with only a 16 bit service identifier set. */
    public static boolean is16BitUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        if (uuid.getLeastSignificantBits() != BASE_UUID.getUuid().getLeastSignificantBits()) {
            return false;
        }
        return ((uuid.getMostSignificantBits() & 0xFFFF0000FFFFFFFFL) == 0x1000L);
    }

    /** Whether the UUID is the base UUID with a 32 bit identifier that does not fit 16. */
    public static boolean is32BitUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        if (uuid.getLeastSignificantBits() != BASE_UUID.getUuid().getLeastSignificantBits()) {
            return false;
        }
        if (is16BitUuid(parcelUuid)) {
            return false;
        }
        return ((uuid.getMostSignificantBits() & 0xFFFFFFFFL) == 0x1000L);
    }

    private static int getServiceIdentifierFromParcelUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        long value = (uuid.getMostSignificantBits() & 0xFFFFFFFF00000000L) >>> 32;
        return (int) value;
    }
}
