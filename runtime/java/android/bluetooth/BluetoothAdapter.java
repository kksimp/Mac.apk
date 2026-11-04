// android.bluetooth.BluetoothAdapter
//
// BLE is provided by the opt-in CoreBluetooth bridge. Classic Bluetooth,
// OS pairing and controller power changes remain unsupported.
package android.bluetooth;

public class BluetoothAdapter {
    private static final BluetoothAdapter DEFAULT = new BluetoothAdapter();
    public BluetoothAdapter() {}

    /** Hardware-backed adapter presence; access remains subject to macOS authorization. */
    public static BluetoothAdapter getDefaultAdapter() {
        return com.macapk.loader.BluetoothBridge.available() ? DEFAULT : null;
    }

    public boolean isEnabled()       { return com.macapk.loader.BluetoothBridge.isEnabled(); }
    public int     getState()        { return com.macapk.loader.BluetoothBridge.state(); }
    public String  getAddress()      { return "02:00:00:00:00:00"; } // AOSP's redacted constant
    public String  getName()         { return null; }
    public boolean enable()          { return false; }
    public boolean disable()         { return false; }
    public boolean isDiscovering()   { return false; }
    public boolean cancelDiscovery() { return false; }
    public boolean setName(String name) { return false; }

    public static final int STATE_OFF         = 10;
    public static final int STATE_TURNING_ON  = 11;
    public static final int STATE_ON          = 12;
    public static final int STATE_TURNING_OFF = 13;

    public static final int SCAN_MODE_NONE = 20;
    public static final int SCAN_MODE_CONNECTABLE = 21;
    public static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23;

    public static final String ACTION_STATE_CHANGED =
            "android.bluetooth.adapter.action.STATE_CHANGED";
    public static final String ACTION_DISCOVERY_STARTED =
            "android.bluetooth.adapter.action.DISCOVERY_STARTED";
    public static final String ACTION_DISCOVERY_FINISHED =
            "android.bluetooth.adapter.action.DISCOVERY_FINISHED";
    public static final String ACTION_SCAN_MODE_CHANGED =
            "android.bluetooth.adapter.action.SCAN_MODE_CHANGED";
    public static final String ACTION_REQUEST_ENABLE =
            "android.bluetooth.adapter.action.REQUEST_ENABLE";
    public static final String ACTION_REQUEST_DISCOVERABLE =
            "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE";
    public static final String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    public static final String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.adapter.extra.PREVIOUS_STATE";
    public static final String EXTRA_SCAN_MODE = "android.bluetooth.adapter.extra.SCAN_MODE";

    // The remaining public constants, completing the class against the
    // android.jar API-30 reference in android-emulator/. Broadcast actions,
    // extras and state codes are plain values with no device dependency, so
    // they are mirrored verbatim -- an app's BroadcastReceiver filter simply
    // never matches, which is what a device with Bluetooth off would do.
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED";
    public static final String ACTION_LOCAL_NAME_CHANGED =
            "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    public static final String EXTRA_CONNECTION_STATE =
            "android.bluetooth.adapter.extra.CONNECTION_STATE";
    public static final String EXTRA_PREVIOUS_CONNECTION_STATE =
            "android.bluetooth.adapter.extra.PREVIOUS_CONNECTION_STATE";
    public static final String EXTRA_DISCOVERABLE_DURATION =
            "android.bluetooth.adapter.extra.DISCOVERABLE_DURATION";
    public static final String EXTRA_LOCAL_NAME =
            "android.bluetooth.adapter.extra.LOCAL_NAME";
    public static final String EXTRA_PREVIOUS_SCAN_MODE =
            "android.bluetooth.adapter.extra.PREVIOUS_SCAN_MODE";

    /** AOSP's "no value" sentinel for the int extras above. */
    public static final int ERROR = Integer.MIN_VALUE;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTING = 3;

    public static boolean checkBluetoothAddress(String address) {
        return address != null && address.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    }

    // No radio: discovery never starts and no devices are ever bonded. These
    // mirror the AOSP signatures so apps that obtain a non-null adapter (e.g.
    // via BluetoothManager.getAdapter in a future Bluetooth-bridged build)
    // degrade gracefully instead of hitting NoSuchMethodError. getRemoteDevice
    // is pure address-to-handle math (no hardware) so it returns a real handle.
    public boolean startDiscovery() { return false; }
    public int getScanMode() { return SCAN_MODE_NONE; }

    public java.util.Set<BluetoothDevice> getBondedDevices() {
        return new java.util.HashSet<>();
    }

    public BluetoothDevice getRemoteDevice(String address) {
        if (!checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Bluetooth address must be valid: " + address);
        }
        return new BluetoothDevice(address);
    }

    public BluetoothDevice getRemoteDevice(byte[] address) {
        if (address == null || address.length != 6) {
            throw new IllegalArgumentException("Bluetooth address must have 6 bytes");
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i != 0) sb.append(':');
            sb.append(String.format("%02X", address[i] & 0xFF));
        }
        return new BluetoothDevice(sb.toString());
    }

    public android.bluetooth.le.BluetoothLeScanner getBluetoothLeScanner() {
        return isEnabled() && com.macapk.loader.BluetoothBridge.authorization() == 3
            ? new android.bluetooth.le.BluetoothLeScanner() : null;
    }

    /**
     * A live advertiser is exposed only after macOS grants Bluetooth access
     * and the radio is enabled. Starting it still reports the host's result.
     */
    public android.bluetooth.le.BluetoothLeAdvertiser getBluetoothLeAdvertiser() {
        return isEnabled() && com.macapk.loader.BluetoothBridge.authorization() == 3
            ? com.macapk.loader.BluetoothPeripheralBridge.createAdvertiser() : null;
    }

    public boolean isMultipleAdvertisementSupported() {
        return com.macapk.loader.BluetoothPeripheralBridge.supportsLegacyAdvertising();
    }
    public boolean isLeExtendedAdvertisingSupported() { return false; }
    public boolean isLePeriodicAdvertisingSupported() { return false; }
    public boolean isOffloadedScanBatchingSupported() { return false; }

    /** AOSP reports the controller's extended-advertising payload limit. There
     *  is no controller, so 0 -- an app sizing a payload against this cannot
     *  conclude that a larger one would have fit. */
    public int getLeMaximumAdvertisingDataLength() { return 0; }

    /**
     * The pre-API-21 LE scan API. AOSP returns false when the scan could not
     * be started, which is our permanent state -- and false is what stops an
     * app waiting for an onLeScan that cannot come.
     */
    @Deprecated
    public boolean startLeScan(LeScanCallback callback) { return false; }

    /** @see #startLeScan(LeScanCallback) */
    @Deprecated
    public boolean startLeScan(java.util.UUID[] serviceUuids, LeScanCallback callback) {
        return false;
    }

    /** A removal: no scan is running, so there is nothing to stop. */
    @Deprecated
    public void stopLeScan(LeScanCallback callback) {}

    /**
     * The pre-API-21 LE scan callback. Never invoked here, but it is a class
     * STRUCTURE dependency for any app that declares
     * `implements BluetoothAdapter.LeScanCallback` -- resolved when that class
     * is DEFINED, so a missing interface aborts it on load.
     */
    @Deprecated
    public interface LeScanCallback {
        void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord);
    }
    public boolean isLe2MPhySupported() { return false; }
    public boolean isLeCodedPhySupported() { return false; }
    public boolean isOffloadedFilteringSupported() { return false; }

    public int getProfileConnectionState(int profile) {
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    public boolean getProfileProxy(android.content.Context context,
            BluetoothProfile.ServiceListener listener, int profile) {
        // No backend, so no proxy is ever delivered. AOSP returns false when
        // the profile is unsupported; we do the same.
        return false;
    }

    public void closeProfileProxy(int profile, BluetoothProfile proxy) {}

    public BluetoothServerSocket listenUsingInsecureRfcommWithServiceRecord(String name, java.util.UUID uuid)
            throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }

    /** @see #listenUsingInsecureRfcommWithServiceRecord -- same, secure. */
    public BluetoothServerSocket listenUsingRfcommWithServiceRecord(String name,
            java.util.UUID uuid) throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }

    /** L2CAP channel listen (API 29). No radio, so the same honest IOException
     *  as the RFCOMM twins -- never a socket that cannot carry traffic. */
    public BluetoothServerSocket listenUsingL2capChannel() throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }

    /** @see #listenUsingL2capChannel */
    public BluetoothServerSocket listenUsingInsecureL2capChannel() throws java.io.IOException {
        throw new java.io.IOException("Mac.apk: no Bluetooth radio");
    }
}

