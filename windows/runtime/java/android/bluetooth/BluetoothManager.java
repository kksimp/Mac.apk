// android.bluetooth.BluetoothManager
//
// AOSP's high-level entry point to the Bluetooth subsystem. Apps obtain it via
// getSystemService(BLUETOOTH_SERVICE) and immediately call getAdapter(). Mac.apk
// has no Bluetooth backend, so getAdapter() returns the (null-capable) default
// adapter and the connected-device queries return empty lists -- the honest
// "no Bluetooth hardware" result rather than a null service that NPEs the cast.
// Mirrors frameworks/base/core/java/android/bluetooth/BluetoothManager.java.
package android.bluetooth;

import android.content.Context;

public final class BluetoothManager {

    public BluetoothManager() {}
    public BluetoothManager(Context context) {}

    /** AOSP getAdapter(): the singleton BluetoothAdapter (may be null when no
     *  Bluetooth hardware is present, which is our case). */
    public BluetoothAdapter getAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    /** AOSP getConnectionState(device, profile): STATE_DISCONNECTED (0) since
     *  nothing can be connected without a radio. */
    public int getConnectionState(BluetoothDevice device, int profile) {
        return 0; // BluetoothProfile.STATE_DISCONNECTED
    }

    /** AOSP getConnectedDevices(profile): no devices. */
    public java.util.List<BluetoothDevice> getConnectedDevices(int profile) {
        return new java.util.ArrayList<>();
    }

    /**
     * AOSP openGattServer(): opens a GATT SERVER (this device acting as a BLE
     * peripheral) and returns null if it could not be opened. There is no
     * Bluetooth bridge, so null -- which is AOSP's own documented failure
     * return, and the only answer that does not hand the app a server object
     * whose addService() would silently never advertise anything.
     */
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback) {
        return null;
    }

    /** AOSP getDevicesMatchingConnectionStates: no devices. */
    public java.util.List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int profile, int[] states) {
        return new java.util.ArrayList<>();
    }
}
