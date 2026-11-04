// android.bluetooth.BluetoothGattCallback
//
// The callback an app subclasses to receive GATT client events. Mac.apk has no
// radio; the only event we ever deliver is onConnectionStateChange(FAILURE,
// DISCONNECTED) from connectGatt. The full overridable surface must exist so
// subclasses compile. Mirrors
// frameworks/base/core/java/android/bluetooth/BluetoothGattCallback.java.
package android.bluetooth;

public abstract class BluetoothGattCallback {

    public BluetoothGattCallback() {}

    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {}
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {}
    public void onCharacteristicRead(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic, int status) {}
    public void onCharacteristicRead(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic, byte[] value, int status) { onCharacteristicRead(gatt, characteristic, status); }
    public void onCharacteristicWrite(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic, int status) {}
    public void onCharacteristicChanged(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {}
    public void onCharacteristicChanged(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic, byte[] value) { onCharacteristicChanged(gatt, characteristic); }
    public void onDescriptorRead(BluetoothGatt gatt,
            BluetoothGattDescriptor descriptor, int status) {}
    public void onDescriptorRead(BluetoothGatt gatt,
            BluetoothGattDescriptor descriptor, int status, byte[] value) { onDescriptorRead(gatt, descriptor, status); }
    public void onDescriptorWrite(BluetoothGatt gatt,
            BluetoothGattDescriptor descriptor, int status) {}
    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {}
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {}
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {}
    public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {}
    public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {}
}
