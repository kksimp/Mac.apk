// android.bluetooth.BluetoothManager
//
// AOSP's high-level entry point to the Bluetooth subsystem. Apps obtain it via
// getSystemService(BLUETOOTH_SERVICE) and immediately call getAdapter(). Mac.apk
// exposes its opt-in CoreBluetooth backend through the default adapter.
// Mirrors frameworks/base/core/java/android/bluetooth/BluetoothManager.java.
package android.bluetooth;

import android.content.Context;

public final class BluetoothManager {

    public BluetoothManager() {}
    public BluetoothManager(Context context) {}

    /** The singleton adapter, or null when the bridge/hardware is absent. */
    public BluetoothAdapter getAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    /** Connection state from the live BLE client and server sessions. */
    public int getConnectionState(BluetoothDevice device, int profile) {
        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER) return 0;
        int client = BluetoothGatt.macapkConnectionState(device);
        int server = com.macapk.loader.BluetoothPeripheralBridge.connectionState(device);
        return server == BluetoothProfile.STATE_CONNECTED ? server : client;
    }

    /** GATT devices share the same controller across both roles. */
    public java.util.List<BluetoothDevice> getConnectedDevices(int profile) {
        return getDevicesMatchingConnectionStates(profile, new int[]{BluetoothProfile.STATE_CONNECTED});
    }

    /**
     * AOSP openGattServer(): opens a GATT SERVER (this device acting as a BLE
     * peripheral) and returns null if access, hardware or the bridge is absent.
     */
    public BluetoothGattServer openGattServer(Context context,
            BluetoothGattServerCallback callback) {
        return com.macapk.loader.BluetoothPeripheralBridge.openServer(callback);
    }

    /** Unsupported Classic profiles have no sessions in the BLE bridge. */
    public java.util.List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int profile, int[] states) {
        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER)
            return new java.util.ArrayList<>();
        java.util.LinkedHashSet<BluetoothDevice> result = new java.util.LinkedHashSet<>(
            BluetoothGatt.macapkDevicesMatchingConnectionStates(states));
        result.addAll(com.macapk.loader.BluetoothPeripheralBridge.devicesMatchingConnectionStates(states));
        return new java.util.ArrayList<>(result);
    }
}
