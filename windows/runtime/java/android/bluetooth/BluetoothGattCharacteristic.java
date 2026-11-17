// android.bluetooth.BluetoothGattCharacteristic
//
// A GATT characteristic value holder. Mac.apk never connects, so these are
// never read off a radio, but apps construct them (e.g. when writing) and
// reference the PROPERTY_*/PERMISSION_*/WRITE_TYPE_* constants, so the value
// type must resolve faithfully. Mirrors
// frameworks/base/core/java/android/bluetooth/BluetoothGattCharacteristic.java.
package android.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothGattCharacteristic implements android.os.Parcelable {

    public static final int PROPERTY_BROADCAST = 0x01;
    public static final int PROPERTY_READ = 0x02;
    public static final int PROPERTY_WRITE_NO_RESPONSE = 0x04;
    public static final int PROPERTY_WRITE = 0x08;
    public static final int PROPERTY_NOTIFY = 0x10;
    public static final int PROPERTY_INDICATE = 0x20;
    public static final int PROPERTY_SIGNED_WRITE = 0x40;
    public static final int PROPERTY_EXTENDED_PROPS = 0x80;

    public static final int PERMISSION_READ = 0x01;
    public static final int PERMISSION_READ_ENCRYPTED = 0x02;
    public static final int PERMISSION_READ_ENCRYPTED_MITM = 0x04;
    public static final int PERMISSION_WRITE = 0x10;
    public static final int PERMISSION_WRITE_ENCRYPTED = 0x20;
    public static final int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;
    public static final int PERMISSION_WRITE_SIGNED = 0x80;
    public static final int PERMISSION_WRITE_SIGNED_MITM = 0x100;

    public static final int WRITE_TYPE_DEFAULT = 0x02;
    public static final int WRITE_TYPE_NO_RESPONSE = 0x01;
    public static final int WRITE_TYPE_SIGNED = 0x04;

    public static final int FORMAT_UINT8 = 0x11;
    public static final int FORMAT_UINT16 = 0x12;
    public static final int FORMAT_UINT32 = 0x14;
    public static final int FORMAT_SINT8 = 0x21;
    public static final int FORMAT_SINT16 = 0x22;
    public static final int FORMAT_SINT32 = 0x24;
    public static final int FORMAT_SFLOAT = 0x32;
    public static final int FORMAT_FLOAT = 0x34;

    private final UUID mUuid;
    private final int mInstanceId;
    private int mProperties;
    private int mPermissions;
    private int mWriteType = WRITE_TYPE_DEFAULT;
    private byte[] mValue;
    /** PROTECTED on AOSP, not private: it is part of the public API surface
     *  (subclasses read it directly), so the access level is mirrored. */
    protected List<BluetoothGattDescriptor> mDescriptors = new ArrayList<>();
    // Back-pointer to the owning service, set by BluetoothGattService.addCharacteristic
    // (mirrors AOSP; see the comment there). Null until this characteristic has
    // actually been added to a service, exactly like AOSP.
    private BluetoothGattService mService;

    public BluetoothGattCharacteristic(UUID uuid, int properties, int permissions) {
        mUuid = uuid;
        mInstanceId = 0;
        mProperties = properties;
        mPermissions = permissions;
    }

    /** AOSP's hidden constructor for a discovered characteristic: the
     *  instance id is its attribute handle on the device (BluetoothGatt). */
    BluetoothGattCharacteristic(UUID uuid, int instanceId, int properties, int permissions) {
        mUuid = uuid;
        mInstanceId = instanceId;
        mProperties = properties;
        mPermissions = permissions;
    }

    public UUID getUuid() { return mUuid; }
    public int getInstanceId() { return mInstanceId; }
    public BluetoothGattService getService() { return mService; }
    /** Package-private: only BluetoothGattService.addCharacteristic sets this,
     *  same as AOSP (frameworks/base .../BluetoothGattCharacteristic.java
     *  setService(BluetoothGattService)). */
    void setService(BluetoothGattService service) { mService = service; }
    public int getProperties() { return mProperties; }
    public int getPermissions() { return mPermissions; }
    public int getWriteType() { return mWriteType; }
    public void setWriteType(int writeType) { mWriteType = writeType; }

    public boolean addDescriptor(BluetoothGattDescriptor descriptor) {
        // AOSP back-sets the parent pointer inside add*, not the caller
        // (BluetoothGattCharacteristic.java addDescriptor:
        // "descriptor.setCharacteristic(this);"), the same contract as
        // BluetoothGattService.addCharacteristic one level up.
        mDescriptors.add(descriptor);
        descriptor.setCharacteristic(this);
        return true;
    }
    public List<BluetoothGattDescriptor> getDescriptors() { return mDescriptors; }
    public BluetoothGattDescriptor getDescriptor(UUID uuid) {
        for (BluetoothGattDescriptor d : mDescriptors) {
            if (d.getUuid().equals(uuid)) return d;
        }
        return null;
    }

    public byte[] getValue() { return mValue; }
    public boolean setValue(byte[] value) { mValue = value; return true; }
    public boolean setValue(String value) {
        mValue = value == null ? null : value.getBytes();
        return true;
    }
    public boolean setValue(int value, int formatType, int offset) { return true; }
    public boolean setValue(int mantissa, int exponent, int formatType, int offset) {
        return true;
    }
    public Integer getIntValue(int formatType, int offset) { return null; }
    public Float getFloatValue(int formatType, int offset) { return null; }
    public String getStringValue(int offset) { return null; }

    // PARCELABLE on AOSP. A GATT-server app passes characteristics across
    // process boundaries, so the type has to be marshallable for those
    // signatures to resolve. Real and symmetric: the UUID, properties,
    // permissions, write type and value round-trip; the descriptor list does
    // not, matching AOSP, which rebuilds it from the remote service.
    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(android.os.Parcel out, int flags) {
        out.writeString(mUuid == null ? null : mUuid.toString());
        out.writeInt(mInstanceId);
        out.writeInt(mProperties);
        out.writeInt(mPermissions);
        out.writeInt(mWriteType);
        out.writeByteArray(mValue);
    }

    public static final Creator<BluetoothGattCharacteristic> CREATOR =
            new Creator<BluetoothGattCharacteristic>() {
        @Override public BluetoothGattCharacteristic createFromParcel(android.os.Parcel in) {
            String uuid = in.readString();
            in.readInt(); // instance id: AOSP assigns it, we always use 0
            int properties = in.readInt();
            int permissions = in.readInt();
            BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(
                    uuid == null ? null : UUID.fromString(uuid), properties, permissions);
            c.setWriteType(in.readInt());
            c.setValue(in.createByteArray());
            return c;
        }

        @Override public BluetoothGattCharacteristic[] newArray(int size) {
            return new BluetoothGattCharacteristic[size];
        }
    };
}
