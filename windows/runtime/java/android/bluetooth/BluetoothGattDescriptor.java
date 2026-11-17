// android.bluetooth.BluetoothGattDescriptor
//
// A GATT descriptor value holder (notably the Client Characteristic
// Configuration descriptor used to enable notifications). Mac.apk never
// connects, but apps construct/write these, so the value type + the standard
// ENABLE/DISABLE notification byte-array constants must resolve. Mirrors
// frameworks/base/core/java/android/bluetooth/BluetoothGattDescriptor.java.
package android.bluetooth;

import java.util.UUID;

public class BluetoothGattDescriptor implements android.os.Parcelable {

    public static final int PERMISSION_READ = 0x01;
    public static final int PERMISSION_READ_ENCRYPTED = 0x02;
    public static final int PERMISSION_READ_ENCRYPTED_MITM = 0x04;
    public static final int PERMISSION_WRITE = 0x10;
    public static final int PERMISSION_WRITE_ENCRYPTED = 0x20;
    public static final int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;
    public static final int PERMISSION_WRITE_SIGNED = 0x80;
    public static final int PERMISSION_WRITE_SIGNED_MITM = 0x100;

    public static final byte[] ENABLE_NOTIFICATION_VALUE = { 0x01, 0x00 };
    public static final byte[] ENABLE_INDICATION_VALUE = { 0x02, 0x00 };
    public static final byte[] DISABLE_NOTIFICATION_VALUE = { 0x00, 0x00 };

    private final UUID mUuid;
    private final int mPermissions;
    // AOSP's hidden instance id: the descriptor's attribute handle on the
    // device (BluetoothGatt), 0 for one the app built.
    private final int mInstanceId;
    private byte[] mValue;
    // Back-pointer to the owning characteristic, set by
    // BluetoothGattCharacteristic.addDescriptor (mirrors AOSP; see the
    // comment there). Null until this descriptor has actually been added to
    // a characteristic, exactly like AOSP.
    private BluetoothGattCharacteristic mCharacteristic;

    public BluetoothGattDescriptor(UUID uuid, int permissions) {
        mUuid = uuid;
        mPermissions = permissions;
        mInstanceId = 0;
    }

    BluetoothGattDescriptor(UUID uuid, int instanceId, int permissions) {
        mUuid = uuid;
        mPermissions = permissions;
        mInstanceId = instanceId;
    }

    public UUID getUuid() { return mUuid; }
    /** @hide on AOSP. */
    public int getInstanceId() { return mInstanceId; }
    public int getPermissions() { return mPermissions; }
    public BluetoothGattCharacteristic getCharacteristic() { return mCharacteristic; }
    /** Package-private: only BluetoothGattCharacteristic.addDescriptor sets
     *  this, same as AOSP (frameworks/base .../BluetoothGattDescriptor.java
     *  setCharacteristic(BluetoothGattCharacteristic)). */
    void setCharacteristic(BluetoothGattCharacteristic characteristic) { mCharacteristic = characteristic; }
    public byte[] getValue() { return mValue; }
    public boolean setValue(byte[] value) { mValue = value; return true; }

    // PARCELABLE on AOSP -- see BluetoothGattCharacteristic for the reasoning.
    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(android.os.Parcel out, int flags) {
        out.writeString(mUuid == null ? null : mUuid.toString());
        out.writeInt(mPermissions);
        out.writeByteArray(mValue);
    }

    public static final Creator<BluetoothGattDescriptor> CREATOR =
            new Creator<BluetoothGattDescriptor>() {
        @Override public BluetoothGattDescriptor createFromParcel(android.os.Parcel in) {
            String uuid = in.readString();
            BluetoothGattDescriptor d = new BluetoothGattDescriptor(
                    uuid == null ? null : UUID.fromString(uuid), in.readInt());
            d.setValue(in.createByteArray());
            return d;
        }

        @Override public BluetoothGattDescriptor[] newArray(int size) {
            return new BluetoothGattDescriptor[size];
        }
    };
}
