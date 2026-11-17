// android.bluetooth.BluetoothAdapter
//
// The host's Bluetooth radio, when the host reports one
// (com.macapk.loader.HostRadios; Win.apk does): its name, whether it is on,
// and the paired devices. A host that also talks to devices
// (com.macapk.loader.HostBluetooth; Win.apk) gets the RFCOMM server sockets
// here and the LE scanner; classic discovery (startDiscovery), the profiles
// (A2DP, headset...) and LE advertising still answer "not possible". With no
// radio -- or on a host that does not report one (the Mac) --
// getDefaultAdapter() is null, AOSP's answer for a device without Bluetooth,
// which real apps handle.
package android.bluetooth;

public class BluetoothAdapter {
    public BluetoothAdapter() {}

    private static final BluetoothAdapter sAdapter = new BluetoothAdapter();

    public static BluetoothAdapter getDefaultAdapter() {
        return com.macapk.loader.HostRadios.bluetoothRadio() != null ? sAdapter : null;
    }

    public boolean isEnabled() {
        String[] r = com.macapk.loader.HostRadios.bluetoothRadio();
        return r != null && "1".equals(r[2]);
    }
    public int     getState()        { return isEnabled() ? STATE_ON : STATE_OFF; }
    public String  getAddress()      { return "02:00:00:00:00:00"; } // AOSP's redacted constant
    public String  getName() {
        String[] r = com.macapk.loader.HostRadios.bluetoothRadio();
        return r == null || r[0].isEmpty() ? null : r[0];
    }
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

    /** AOSP: the paired devices, empty while the adapter is off; with it on,
     *  a SecurityException without BLUETOOTH_CONNECT, as the service gives. */
    public java.util.Set<BluetoothDevice> getBondedDevices() {
        java.util.Set<BluetoothDevice> out = new java.util.HashSet<>();
        if (!isEnabled()) return out;
        com.macapk.loader.HostBluetooth.enforceConnect("getBondedDevices");
        String[] b = com.macapk.loader.HostRadios.bluetoothBonded();
        for (int i = 0; b != null && i + 2 < b.length; i += 3) {
            int cod = 0;
            try { cod = Integer.parseInt(b[i + 2]); } catch (NumberFormatException ignored) {}
            out.add(new BluetoothDevice(b[i + 1], b[i].isEmpty() ? null : b[i], cod));
        }
        return out;
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

    /** AOSP: the LE scanner, null while the adapter is off. It scans when the
     *  host does (HostBluetooth); otherwise its scans fail at once. */
    public android.bluetooth.le.BluetoothLeScanner getBluetoothLeScanner() {
        return isEnabled() ? new android.bluetooth.le.BluetoothLeScanner() : null;
    }

    /**
     * Null: advertising (the PC as a peripheral) is not bridged. AOSP returns
     * null here whenever the adapter is off or the controller cannot
     * advertise, so callers already handle it -- and a non-null advertiser
     * would let an app believe it had started broadcasting.
     */
    public android.bluetooth.le.BluetoothLeAdvertiser getBluetoothLeAdvertiser() {
        return null;
    }

    public boolean isMultipleAdvertisementSupported() { return false; }
    public boolean isLeExtendedAdvertisingSupported() { return false; }
    public boolean isLePeriodicAdvertisingSupported() { return false; }
    public boolean isOffloadedScanBatchingSupported() { return false; }

    /** AOSP reports the controller's extended-advertising payload limit. There
     *  is no controller, so 0 -- an app sizing a payload against this cannot
     *  conclude that a larger one would have fit. */
    public int getLeMaximumAdvertisingDataLength() { return 0; }

    /**
     * The pre-API-21 LE scan API, as AOSP has it since API 21: a wrapper over
     * the LE scanner. Apps for older devices (and vendor SDKs that never
     * moved on) still call it. False when the scan cannot start: the adapter
     * is off, the host has no LE radio, or this callback is already scanning.
     */
    @Deprecated
    public boolean startLeScan(LeScanCallback callback) { return startLeScan(null, callback); }

    /** As AOSP: scans unfiltered and reports a device only when its
     *  advertisement lists every one of serviceUuids. */
    @Deprecated
    public boolean startLeScan(final java.util.UUID[] serviceUuids, final LeScanCallback callback) {
        if (callback == null) return false;
        android.bluetooth.le.BluetoothLeScanner scanner = getBluetoothLeScanner();
        if (scanner == null || !com.macapk.loader.HostBluetooth.leAvailable()) return false;
        android.bluetooth.le.ScanCallback wrapper;
        synchronized (mLegacyScans) {
            if (mLegacyScans.containsKey(callback)) return false;
            wrapper = new android.bluetooth.le.ScanCallback() {
                @Override public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                    android.bluetooth.le.ScanRecord record = result.getScanRecord();
                    if (record == null) return;
                    if (serviceUuids != null && serviceUuids.length > 0) {
                        java.util.List<android.os.ParcelUuid> have = record.getServiceUuids();
                        for (java.util.UUID want : serviceUuids) {
                            if (have == null || !have.contains(new android.os.ParcelUuid(want))) return;
                        }
                    }
                    callback.onLeScan(result.getDevice(), result.getRssi(), record.getBytes());
                }
            };
            mLegacyScans.put(callback, wrapper);
        }
        scanner.startScan(null, new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), wrapper);
        return true;
    }

    @Deprecated
    public void stopLeScan(LeScanCallback callback) {
        android.bluetooth.le.ScanCallback wrapper;
        synchronized (mLegacyScans) {
            wrapper = mLegacyScans.remove(callback);
        }
        if (wrapper != null) new android.bluetooth.le.BluetoothLeScanner().stopScan(wrapper);
    }

    // startLeScan's callbacks and the ScanCallback each one scans through. Static:
    // the scanner's clients are, and apps stop a scan on another adapter object.
    private static final java.util.Map<LeScanCallback, android.bluetooth.le.ScanCallback> mLegacyScans =
            new java.util.HashMap<>();

    /**
     * The pre-API-21 LE scan callback. Also a class
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

    /** AOSP: a listening RFCOMM socket on a free channel, published under
     *  `uuid` in SDP (BluetoothServerSocket); IOException when the host has
     *  no sockets, SecurityException without BLUETOOTH_CONNECT. */
    public BluetoothServerSocket listenUsingInsecureRfcommWithServiceRecord(String name, java.util.UUID uuid)
            throws java.io.IOException {
        return listen(name, uuid, false);
    }

    /** @see #listenUsingInsecureRfcommWithServiceRecord -- same, secure. */
    public BluetoothServerSocket listenUsingRfcommWithServiceRecord(String name,
            java.util.UUID uuid) throws java.io.IOException {
        return listen(name, uuid, true);
    }

    private BluetoothServerSocket listen(String name, java.util.UUID uuid, boolean secure)
            throws java.io.IOException {
        if (uuid == null) throw new java.io.IOException("uuid is null");
        if (!isEnabled() || !com.macapk.loader.HostBluetooth.rfcommAvailable()) {
            throw new java.io.IOException("Mac.apk: no Bluetooth radio");
        }
        com.macapk.loader.HostBluetooth.enforceConnect("listenUsingRfcommWithServiceRecord");
        return new BluetoothServerSocket(name,
                com.macapk.loader.HostBluetooth.rfcommListen(name != null ? name : "", uuid.toString(), secure));
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

