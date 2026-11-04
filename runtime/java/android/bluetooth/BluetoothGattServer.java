package android.bluetooth;

import com.macapk.loader.BluetoothPeripheralBridge;
import java.util.List;
import java.util.UUID;

/** GATT peripheral service backed by the host's CoreBluetooth manager. */
public final class BluetoothGattServer implements BluetoothProfile {
    private final BluetoothPeripheralBridge.Session session;
    private BluetoothGattServer(BluetoothPeripheralBridge.Session session) { this.session = session; }
    public static BluetoothGattServer macapkCreate(BluetoothPeripheralBridge.Session session) {
        return new BluetoothGattServer(java.util.Objects.requireNonNull(session));
    }
    public void close() { session.close(); }
    // CoreBluetooth peripherals cannot initiate or cancel the central's ACL
    // connection. These calls never report a successful operation they cannot do.
    public boolean connect(BluetoothDevice device, boolean autoConnect) { return false; }
    public void cancelConnection(BluetoothDevice device) {
        System.err.println("[bluetooth] CoreBluetooth cannot cancel a peripheral's remote central connection");
    }
    public void setPreferredPhy(BluetoothDevice device, int txPhy, int rxPhy, int options) {
        System.err.println("[bluetooth] CoreBluetooth does not expose peripheral PHY selection");
    }
    public void readPhy(BluetoothDevice device) {
        System.err.println("[bluetooth] CoreBluetooth does not expose peripheral PHY queries");
    }
    public boolean sendResponse(BluetoothDevice device, int request, int status, int offset, byte[] value) {
        return session.respond(device, request, status, offset, value);
    }
    public boolean notifyCharacteristicChanged(BluetoothDevice device,
            BluetoothGattCharacteristic characteristic, boolean confirm) {
        return characteristic != null && session.notify(device, characteristic, confirm, characteristic.getValue());
    }
    public int notifyCharacteristicChanged(BluetoothDevice device,
            BluetoothGattCharacteristic characteristic, boolean confirm, byte[] value) {
        return characteristic != null && session.notify(device, characteristic, confirm, value)
                ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
    }
    public boolean addService(BluetoothGattService service) { return session.addService(service); }
    public boolean removeService(BluetoothGattService service) { return session.removeService(service); }
    public void clearServices() { session.clearServices(); }
    public List<BluetoothGattService> getServices() { return session.getServices(); }
    public BluetoothGattService getService(UUID uuid) { return session.getService(uuid); }
    @Override public int getConnectionState(BluetoothDevice device) {
        return session.connected(device) ? STATE_CONNECTED : STATE_DISCONNECTED;
    }
    @Override public List<BluetoothDevice> getConnectedDevices() { return session.connectedDevices(); }
    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (states != null) for (int state : states) if (state == STATE_CONNECTED) return getConnectedDevices();
        return java.util.Collections.emptyList();
    }
}
