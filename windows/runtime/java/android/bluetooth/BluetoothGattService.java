// android.bluetooth.BluetoothGattService
//
// A GATT service holder. Mac.apk never discovers services (no radio), so
// getServices returns empty, but the type + characteristic-enumeration surface
// must resolve for service-walking code to compile. Mirrors
// frameworks/base/core/java/android/bluetooth/BluetoothGattService.java.
package android.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothGattService implements android.os.Parcelable {

    public static final int SERVICE_TYPE_PRIMARY = 0;
    public static final int SERVICE_TYPE_SECONDARY = 1;

    private final UUID mUuid;
    private final int mInstanceId;
    private final int mServiceType;
    /** PROTECTED on AOSP, not private -- part of the public surface. */
    protected List<BluetoothGattCharacteristic> mCharacteristics = new ArrayList<>();
    /** PROTECTED on AOSP. Included (secondary) services referenced by this one. */
    protected List<BluetoothGattService> mIncludedServices = new ArrayList<>();

    public BluetoothGattService(UUID uuid, int serviceType) {
        mUuid = uuid;
        mInstanceId = 0;
        mServiceType = serviceType;
    }

    /** AOSP's hidden constructor for a discovered service: the instance id
     *  is the service's attribute handle on the device (BluetoothGatt). */
    BluetoothGattService(BluetoothDevice device, UUID uuid, int instanceId, int serviceType) {
        mUuid = uuid;
        mInstanceId = instanceId;
        mServiceType = serviceType;
    }

    public UUID getUuid() { return mUuid; }
    public int getInstanceId() { return mInstanceId; }
    public int getType() { return mServiceType; }

    public boolean addCharacteristic(BluetoothGattCharacteristic characteristic) {
        // AOSP back-sets the parent pointer inside add*, not the caller
        // (frameworks/base/core/java/android/bluetooth/BluetoothGattService.java
        // addCharacteristic: "characteristic.setService(this);"), so any app
        // that walks up via BluetoothGattCharacteristic.getService() after
        // building a service tree locally (a common GATT-server pattern) sees
        // the real parent instead of null.
        mCharacteristics.add(characteristic);
        characteristic.setService(this);
        return true;
    }
    public List<BluetoothGattCharacteristic> getCharacteristics() { return mCharacteristics; }
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        for (BluetoothGattCharacteristic c : mCharacteristics) {
            if (c.getUuid().equals(uuid)) return c;
        }
        return null;
    }

    /** AOSP: adds an INCLUDED (secondary) service reference to this one. Pure
     *  local bookkeeping -- no radio involved -- so it is real. */
    public boolean addService(BluetoothGattService service) {
        mIncludedServices.add(service);
        return true;
    }

    public List<BluetoothGattService> getIncludedServices() { return mIncludedServices; }

    // PARCELABLE on AOSP -- see BluetoothGattCharacteristic for the reasoning.
    // The characteristic list travels with the service, because that is the
    // whole point of marshalling one; included services do not, matching AOSP.
    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(android.os.Parcel out, int flags) {
        out.writeString(mUuid == null ? null : mUuid.toString());
        out.writeInt(mInstanceId);
        out.writeInt(mServiceType);
        out.writeTypedList(mCharacteristics);
    }

    public static final Creator<BluetoothGattService> CREATOR =
            new Creator<BluetoothGattService>() {
        @Override public BluetoothGattService createFromParcel(android.os.Parcel in) {
            String uuid = in.readString();
            in.readInt(); // instance id: AOSP assigns it, we always use 0
            BluetoothGattService svc = new BluetoothGattService(
                    uuid == null ? null : UUID.fromString(uuid), in.readInt());
            List<BluetoothGattCharacteristic> chars =
                    in.createTypedArrayList(BluetoothGattCharacteristic.CREATOR);
            if (chars != null) {
                for (BluetoothGattCharacteristic c : chars) {
                    svc.addCharacteristic(c);
                }
            }
            return svc;
        }

        @Override public BluetoothGattService[] newArray(int size) {
            return new BluetoothGattService[size];
        }
    };
}
