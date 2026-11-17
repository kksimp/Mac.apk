// android.bluetooth.BluetoothDevice
//
// A remote Bluetooth device handle: one the host has paired
// (BluetoothAdapter.getBondedDevices), one an LE scan found, or one made from
// an address string (apps that hold a MAC and want to connect). On a host
// that talks to devices (com.macapk.loader.HostBluetooth; Win.apk) the RFCOMM
// sockets and the GATT client it hands out are real; pairing (createBond) and
// SDP UUID queries are not offered. Without such a host (the Mac)
// connectGatt returns a GATT client that immediately reports a failed
// connection and a socket's connect throws -- the honest no-radio result.
// Mirrors frameworks/base/core/java/android/bluetooth/BluetoothDevice.java.
package android.bluetooth;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

public class BluetoothDevice implements Parcelable {

    public static final int BOND_NONE = 10;
    public static final int BOND_BONDING = 11;
    public static final int BOND_BONDED = 12;

    public static final int DEVICE_TYPE_UNKNOWN = 0;
    public static final int DEVICE_TYPE_CLASSIC = 1;
    public static final int DEVICE_TYPE_LE = 2;
    public static final int DEVICE_TYPE_DUAL = 3;

    public static final int TRANSPORT_AUTO = 0;
    public static final int TRANSPORT_BREDR = 1;
    public static final int TRANSPORT_LE = 2;

    public static final int PHY_LE_1M_MASK = 1;
    public static final int PHY_LE_2M_MASK = 2;
    public static final int PHY_LE_CODED_MASK = 4;

    // The PHY VALUES (as opposed to the masks above). Distinct constants on
    // AOSP with distinct values -- PHY_LE_CODED is 3, not 4 -- so an app that
    // passed a mask where a value belongs would select the wrong PHY. Both
    // sets are mirrored.
    public static final int PHY_LE_1M = 1;
    public static final int PHY_LE_2M = 2;
    public static final int PHY_LE_CODED = 3;

    public static final int PHY_OPTION_NO_PREFERRED = 0;
    public static final int PHY_OPTION_S2 = 1;
    public static final int PHY_OPTION_S8 = 2;

    public static final int PAIRING_VARIANT_PIN = 0;
    public static final int PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2;

    /** AOSP's "no value" sentinel for the int extras below. */
    public static final int ERROR = Integer.MIN_VALUE;

    public static final String ACTION_FOUND = "android.bluetooth.device.action.FOUND";
    public static final String ACTION_BOND_STATE_CHANGED =
            "android.bluetooth.device.action.BOND_STATE_CHANGED";
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    public static final String EXTRA_BOND_STATE = "android.bluetooth.device.extra.BOND_STATE";
    public static final String EXTRA_NAME = "android.bluetooth.device.extra.NAME";

    // The remaining broadcast actions and extras, completing the class against
    // the android.jar API-30 reference. Plain values, no device dependency --
    // an app's receiver filter simply never matches here.
    public static final String ACTION_ACL_CONNECTED =
            "android.bluetooth.device.action.ACL_CONNECTED";
    public static final String ACTION_ACL_DISCONNECTED =
            "android.bluetooth.device.action.ACL_DISCONNECTED";
    public static final String ACTION_ACL_DISCONNECT_REQUESTED =
            "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED";
    /** @deprecated on AOSP. */
    @Deprecated
    public static final String ACTION_ALIAS_CHANGED =
            "android.bluetooth.device.action.ALIAS_CHANGED";
    public static final String ACTION_CLASS_CHANGED =
            "android.bluetooth.device.action.CLASS_CHANGED";
    public static final String ACTION_NAME_CHANGED =
            "android.bluetooth.device.action.NAME_CHANGED";
    public static final String ACTION_PAIRING_REQUEST =
            "android.bluetooth.device.action.PAIRING_REQUEST";
    public static final String ACTION_UUID = "android.bluetooth.device.action.UUID";

    public static final String EXTRA_CLASS = "android.bluetooth.device.extra.CLASS";
    public static final String EXTRA_PAIRING_KEY =
            "android.bluetooth.device.extra.PAIRING_KEY";
    public static final String EXTRA_PAIRING_VARIANT =
            "android.bluetooth.device.extra.PAIRING_VARIANT";
    public static final String EXTRA_PREVIOUS_BOND_STATE =
            "android.bluetooth.device.extra.PREVIOUS_BOND_STATE";
    public static final String EXTRA_RSSI = "android.bluetooth.device.extra.RSSI";
    public static final String EXTRA_UUID = "android.bluetooth.device.extra.UUID";

    private final String mAddress;
    // A device the host has paired (BluetoothAdapter.getBondedDevices) carries
    // its name and class of device, one an LE scan found its advertised name;
    // one made from an address alone has neither.
    private final String mName;
    private final int mClassOfDevice;
    private final int mType;
    private final boolean mBonded;

    public BluetoothDevice() { this(null); }
    public BluetoothDevice(String address) {
        this(address, null, DEVICE_TYPE_UNKNOWN, false, 0);
    }
    /** A paired classic device, from the host's list. */
    BluetoothDevice(String address, String name, int classOfDevice) {
        this(address, name, DEVICE_TYPE_CLASSIC, true, classOfDevice);
    }
    /** A device an LE scan found (BluetoothLeScanner, another package, so
     *  public; hidden on AOSP). */
    public BluetoothDevice(String address, String name, int type, boolean bonded) {
        this(address, name, type, bonded, 0);
    }
    private BluetoothDevice(String address, String name, int type, boolean bonded, int classOfDevice) {
        mAddress = address;
        mName = name;
        mType = type;
        mBonded = bonded;
        mClassOfDevice = classOfDevice;
    }

    public String getAddress() { return mAddress; }
    /** AOSP: the remote name the stack has cached, null when none; a
     *  SecurityException without BLUETOOTH_CONNECT (BLUETOOTH below API 31),
     *  as the Bluetooth service gives. */
    public String getName() {
        com.macapk.loader.HostBluetooth.enforceConnect("getName");
        return mName;
    }
    public String getAlias() { return getName(); }
    public int getType() { return mType; }
    public int getBondState() { return mBonded ? BOND_BONDED : BOND_NONE; }
    public boolean createBond() { return false; }
    public BluetoothClass getBluetoothClass() { return mBonded ? new BluetoothClass(mClassOfDevice) : null; }
    public android.os.ParcelUuid[] getUuids() { return null; }
    public boolean fetchUuidsWithSdp() { return false; }
    public boolean setPin(byte[] pin) { return false; }
    public boolean createBond(int transport) { return false; }

    // ---- System APIs (pairing, the link, per-device settings) ---------------
    // Windows pairs and unpairs devices in its own Settings, and what it keeps
    // about one is not the app's to change; each of these answers as a device
    // does when the request cannot be carried out.

    /** AOSP removeBond(): false when unpairing could not be started. */
    public boolean removeBond() { return false; }
    /** AOSP cancelBondProcess(): false when no pairing is in progress to cancel. */
    public boolean cancelBondProcess() { return false; }
    /** AOSP isConnected(): whether any link (ACL) to the device is up. The host
     *  reports paired devices and scan results, not link state, and a link an
     *  app opens itself is its BluetoothSocket's or BluetoothGatt's to report. */
    public boolean isConnected() { return false; }
    /** AOSP getBatteryLevel(): the remote's battery in percent, or
     *  BATTERY_LEVEL_UNKNOWN when it is not known. */
    public int getBatteryLevel() { return BATTERY_LEVEL_UNKNOWN; }
    public static final int BATTERY_LEVEL_UNKNOWN = -1;
    public static final int BATTERY_LEVEL_BLUETOOTH_OFF = -100;
    /** AOSP getMetadata(int): the stored value of a METADATA_* key, null when
     *  none is stored. Nothing stores any. */
    public byte[] getMetadata(int key) { return null; }
    /** AOSP setMetadata(int, byte[]): false when the value was not stored. */
    public boolean setMetadata(int key, byte[] value) { return false; }

    /** AOSP: an RFCOMM socket to the service `uuid` on this device, not yet
     *  connected (BluetoothSocket.connect finds the channel and connects);
     *  secure, so the link is authenticated and encrypted. */
    public BluetoothSocket createRfcommSocketToServiceRecord(java.util.UUID uuid) throws java.io.IOException {
        if (uuid == null) throw new java.io.IOException("uuid is null");
        return new BluetoothSocket(this, uuid, true);
    }
    /** @see #createRfcommSocketToServiceRecord -- the link as the pairing left it. */
    public BluetoothSocket createInsecureRfcommSocketToServiceRecord(java.util.UUID uuid)
            throws java.io.IOException {
        if (uuid == null) throw new java.io.IOException("uuid is null");
        return new BluetoothSocket(this, uuid, false);
    }

    /** AOSP connectGatt(): begins a GATT client connection, reported to the
     *  callback (onConnectionStateChange). A host that cannot connect
     *  (the Mac) gets a client that reports the failure at once. The
     *  transport and PHY are the host stack's to choose. */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback) {
        return BluetoothGatt.create(this, autoConnect, callback, null);
    }
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport) {
        return BluetoothGatt.create(this, autoConnect, callback, null);
    }
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport, int phy) {
        return BluetoothGatt.create(this, autoConnect, callback, null);
    }
    /** API 26: the callbacks go through `handler` instead of a binder thread. */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport, int phy,
            android.os.Handler handler) {
        return BluetoothGatt.create(this, autoConnect, callback, handler);
    }

    /** No bond can exist, so there is no pairing to confirm; false is AOSP's
     *  answer when the request could not be sent. */
    public boolean setPairingConfirmation(boolean confirm) { return false; }

    /** L2CAP CoC channels (API 29). No radio, so the same honest IOException
     *  the RFCOMM socket factories above use rather than a dead socket. */
    public BluetoothSocket createL2capChannel(int psm) throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }

    /** @see #createL2capChannel */
    public BluetoothSocket createInsecureL2capChannel(int psm) throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }

    @Override public boolean equals(Object o) {
        return (o instanceof BluetoothDevice)
                && java.util.Objects.equals(mAddress, ((BluetoothDevice) o).mAddress);
    }
    @Override public int hashCode() { return mAddress == null ? 0 : mAddress.hashCode(); }
    @Override public String toString() { return mAddress; }

    // AOSP writeToParcel writes mAddress then mAddressType (BluetoothUtils
    // .writeStringToParcel + out.writeInt); we don't yet track an address
    // type (every device is address-only here), so we round-trip just the
    // address, matching our own single-String constructor shape.
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAddress);
    }

    public static final Parcelable.Creator<BluetoothDevice> CREATOR =
            new Parcelable.Creator<BluetoothDevice>() {
        @Override
        public BluetoothDevice createFromParcel(Parcel in) {
            return new BluetoothDevice(in.readString());
        }

        @Override
        public BluetoothDevice[] newArray(int size) {
            return new BluetoothDevice[size];
        }
    };
}
