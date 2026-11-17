// android.bluetooth.BluetoothGatt
//
// The GATT client returned by BluetoothDevice.connectGatt. On a host that
// talks to LE devices (com.macapk.loader.HostBluetooth; Win.apk, on WinRT's
// Windows.Devices.Bluetooth) it is real: the connection, service discovery,
// reads and writes of characteristics and descriptors, notifications through
// the client characteristic configuration, the MTU. The host runs each
// connection on a thread of its own and queues its events; a thread here
// drains them and calls the app's BluetoothGattCallback on it (Android
// delivers them on a binder thread), or through the Handler connectGatt was
// given (API 26). One operation is in flight at a time, as AOSP's
// mDeviceBusy enforces: a second returns false until the first's callback.
//
// Without such a host (the Mac) the client immediately reports a failed
// connection to the callback (status=GATT_FAILURE, newState=DISCONNECTED) and
// every operation returns false / empty: the honest "could not connect" path
// real BLE apps already handle. Mirrors
// frameworks/base/core/java/android/bluetooth/BluetoothGatt.java.
package android.bluetooth;

import com.macapk.loader.HostBluetooth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BluetoothGatt implements BluetoothProfile {

    public static final int GATT_SUCCESS = 0;
    public static final int GATT_READ_NOT_PERMITTED = 0x2;
    public static final int GATT_WRITE_NOT_PERMITTED = 0x3;
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 0x5;
    public static final int GATT_REQUEST_NOT_SUPPORTED = 0x6;
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 0xf;
    public static final int GATT_INVALID_OFFSET = 0x7;
    public static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0xd;
    public static final int GATT_CONNECTION_CONGESTED = 0x8f;
    public static final int GATT_FAILURE = 0x101;

    public static final int CONNECTION_PRIORITY_BALANCED = 0;
    public static final int CONNECTION_PRIORITY_HIGH = 1;
    public static final int CONNECTION_PRIORITY_LOW_POWER = 2;

    private final BluetoothDevice mDevice;
    private final BluetoothGattCallback mCallback;
    private final android.os.Handler mHandler;  // null: the drain thread itself
    private final boolean mAutoConnect;
    private long mHandle;  // the host's connection, 0 without one
    private volatile boolean mConnected;
    private boolean mClosed;
    private boolean mBusy;  // an operation awaits its callback
    private final Object mLock = new Object();
    private List<BluetoothGattService> mServices = Collections.emptyList();
    private final Map<Integer, BluetoothGattCharacteristic> mCharacteristics = new HashMap<>();
    private final Map<Integer, BluetoothGattDescriptor> mDescriptors = new HashMap<>();

    private BluetoothGatt(BluetoothDevice device, BluetoothGattCallback callback, android.os.Handler handler,
            boolean autoConnect) {
        mDevice = device;
        mCallback = callback;
        mHandler = handler;
        mAutoConnect = autoConnect;
    }

    /** BluetoothDevice.connectGatt: a client that connects through the host
     *  when there is one, else one that reports the failure at once. */
    static BluetoothGatt create(BluetoothDevice device, boolean autoConnect, BluetoothGattCallback callback,
            android.os.Handler handler) {
        BluetoothGatt gatt = new BluetoothGatt(device, callback, handler, autoConnect);
        if (device != null && device.getAddress() != null && HostBluetooth.leAvailable()) {
            HostBluetooth.enforceConnect("connectGatt");
            gatt.mHandle = HostBluetooth.gattConnect(device.getAddress(), autoConnect);
        }
        if (gatt.mHandle != 0) {
            Thread t = new Thread(gatt::drain, "binder:gatt-" + device.getAddress());
            t.setDaemon(true);
            t.start();
        } else if (callback != null) {
            // AOSP posts the callback asynchronously; with no host loop
            // guaranteed here it is invoked at once, and the observable
            // result (a FAILURE/DISCONNECTED event) is identical.
            callback.onConnectionStateChange(gatt, GATT_FAILURE, STATE_DISCONNECTED);
        }
        return gatt;
    }

    public BluetoothDevice getDevice() { return mDevice; }

    /** AOSP: reconnects after a disconnect (the same client, the same
     *  callback). False without a host connection. */
    public boolean connect() {
        synchronized (mLock) {
            if (mHandle == 0 || mClosed) return false;
        }
        return HostBluetooth.gattReconnect(mHandle);
    }

    public void disconnect() {
        synchronized (mLock) {
            if (mHandle == 0 || mClosed) return;
        }
        HostBluetooth.gattDisconnect(mHandle);
    }

    /** Ends the connection and the drain thread; the client is done with. */
    public void close() {
        synchronized (mLock) {
            if (mHandle == 0 || mClosed) return;
            mClosed = true;
        }
        HostBluetooth.gattClose(mHandle);
    }

    public boolean discoverServices() {
        if (!begin()) return false;
        if (HostBluetooth.gattDiscover(mHandle)) return true;
        end();
        return false;
    }

    public List<BluetoothGattService> getServices() { return mServices; }

    public BluetoothGattService getService(UUID uuid) {
        for (BluetoothGattService s : mServices) {
            if (s.getUuid().equals(uuid)) return s;
        }
        return null;
    }

    public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null
                || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        if (!begin()) return false;
        if (HostBluetooth.gattRead(mHandle, characteristic.getInstanceId(), 0)) return true;
        end();
        return false;
    }

    /** The pre-API-33 write: the value and write type the characteristic
     *  holds. */
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null || characteristic.getValue() == null) return false;
        return writeCharacteristic(characteristic, characteristic.getValue(), characteristic.getWriteType())
                == GATT_SUCCESS;
    }

    /** API 33: GATT_SUCCESS when the write was queued, else why not (AOSP's
     *  BluetoothStatusCodes; GATT_FAILURE stands for them here). */
    public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int writeType) {
        if (characteristic == null || value == null) {
            throw new IllegalArgumentException("characteristic or value is null");
        }
        int props = characteristic.getProperties();
        if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                | BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE)) == 0) {
            return GATT_WRITE_NOT_PERMITTED;
        }
        if (!begin()) return GATT_FAILURE;
        characteristic.setValue(value);
        boolean withResponse = writeType != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        if (HostBluetooth.gattWrite(mHandle, characteristic.getInstanceId(), 0, value, withResponse)) {
            return GATT_SUCCESS;
        }
        end();
        return GATT_FAILURE;
    }

    public boolean readDescriptor(BluetoothGattDescriptor descriptor) {
        if (descriptor == null || descriptor.getCharacteristic() == null) return false;
        if (!begin()) return false;
        if (HostBluetooth.gattRead(mHandle, descriptor.getCharacteristic().getInstanceId(),
                descriptor.getInstanceId())) {
            return true;
        }
        end();
        return false;
    }

    public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
        if (descriptor == null || descriptor.getValue() == null) return false;
        return writeDescriptor(descriptor, descriptor.getValue()) == GATT_SUCCESS;
    }

    /** API 33. The client characteristic configuration (0x2902) is the
     *  subscription itself: the host writes it the way its stack asks. */
    public int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) {
        if (descriptor == null || value == null) throw new IllegalArgumentException("descriptor or value is null");
        if (descriptor.getCharacteristic() == null) return GATT_FAILURE;
        if (!begin()) return GATT_FAILURE;
        descriptor.setValue(value);
        if (HostBluetooth.gattWrite(mHandle, descriptor.getCharacteristic().getInstanceId(), descriptor.getInstanceId(),
                value, true)) {
            return GATT_SUCCESS;
        }
        end();
        return GATT_FAILURE;
    }

    /** AOSP: a local switch, the subscription being the CCCD write; here it
     *  also hooks the host's value-changed events. Immediate, no callback. */
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enable) {
        if (characteristic == null || !mConnected) return false;
        return HostBluetooth.gattNotify(mHandle, characteristic.getInstanceId(), enable);
    }

    /** Windows has no RSSI for a connected device; false is AOSP's answer
     *  when the request cannot be made. */
    public boolean readRemoteRssi() { return false; }

    /** The host's stack negotiates the MTU by itself; onMtuChanged reports
     *  the one it got. */
    public boolean requestMtu(int mtu) {
        if (!begin()) return false;
        if (HostBluetooth.gattMtu(mHandle, mtu)) return true;
        end();
        return false;
    }

    public boolean requestConnectionPriority(int connectionPriority) { return mConnected; }
    public boolean beginReliableWrite() { return false; }
    public boolean executeReliableWrite() { return false; }
    public void abortReliableWrite() {}

    /** @deprecated on AOSP in favour of the no-arg abortReliableWrite(); the
     *  device argument was always ignored there too. */
    @Deprecated
    public void abortReliableWrite(BluetoothDevice mDevice) { abortReliableWrite(); }

    /** AOSP asks the controller for the connection's PHYs; the host does not
     *  say, so no callback fires. */
    public void readPhy() {}

    /** @see #readPhy */
    public void setPreferredPhy(int txPhy, int rxPhy, int phyOptions) {}

    // --- BluetoothProfile ---
    @Override public int getConnectionState(BluetoothDevice device) {
        return mConnected && device != null && device.equals(mDevice) ? STATE_CONNECTED : STATE_DISCONNECTED;
    }
    @Override public List<BluetoothDevice> getConnectedDevices() {
        return mConnected ? Collections.singletonList(mDevice) : Collections.<BluetoothDevice>emptyList();
    }
    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (states == null) return Collections.emptyList();
        int mine = mConnected ? STATE_CONNECTED : STATE_DISCONNECTED;
        for (int s : states) {
            if (s == mine) return Collections.singletonList(mDevice);
        }
        return Collections.emptyList();
    }

    // ---- the host's events ------------------------------------------------------

    /** Claims the one in-flight slot: false when connected is required and
     *  missing, or another operation awaits its callback (AOSP's "device
     *  busy"). */
    private boolean begin() {
        synchronized (mLock) {
            if (mHandle == 0 || mClosed || !mConnected || mBusy) return false;
            mBusy = true;
            return true;
        }
    }

    private void end() {
        synchronized (mLock) { mBusy = false; }
    }

    private void drain() {
        for (;;) {
            Object[] e = HostBluetooth.gattNext(mHandle, 1000);
            if (e == null) continue;
            int[] v = (int[]) e[0];
            final int kind = v[0], status = v[1], a = v[2], b = v[3];
            final byte[] value = (byte[]) e[1];
            if (kind == HostBluetooth.GATT_CLOSED) return;
            if (kind == HostBluetooth.GATT_SERVICES) {
                if (status == GATT_SUCCESS) build((String[]) e[2]);
                end();
                deliver(() -> mCallback.onServicesDiscovered(this, status));
                continue;
            }
            if (kind == HostBluetooth.GATT_CONNECTION) {
                mConnected = b == STATE_CONNECTED;
                if (!mConnected) end();
                deliver(() -> mCallback.onConnectionStateChange(this, status, b));
                continue;
            }
            if (kind == HostBluetooth.GATT_MTU) {
                end();
                deliver(() -> mCallback.onMtuChanged(this, a, status));
                continue;
            }
            final BluetoothGattCharacteristic c = mCharacteristics.get(a);
            if (c == null) {
                if (kind != HostBluetooth.GATT_CHAR_CHANGED) end();
                continue;
            }
            switch (kind) {
            case HostBluetooth.GATT_CHAR_READ:
                if (status == GATT_SUCCESS) c.setValue(value);
                end();
                deliver(() -> {
                    mCallback.onCharacteristicRead(this, c, status);
                    mCallback.onCharacteristicRead(this, c, value != null ? value : new byte[0], status);
                });
                break;
            case HostBluetooth.GATT_CHAR_WRITE:
                end();
                deliver(() -> mCallback.onCharacteristicWrite(this, c, status));
                break;
            case HostBluetooth.GATT_CHAR_CHANGED:
                c.setValue(value);
                deliver(() -> {
                    mCallback.onCharacteristicChanged(this, c);
                    mCallback.onCharacteristicChanged(this, c, value != null ? value : new byte[0]);
                });
                break;
            case HostBluetooth.GATT_DESC_READ:
            case HostBluetooth.GATT_DESC_WRITE: {
                final BluetoothGattDescriptor d = mDescriptors.get(b);
                end();
                if (d == null) break;
                if (kind == HostBluetooth.GATT_DESC_READ) {
                    if (status == GATT_SUCCESS) d.setValue(value);
                    deliver(() -> mCallback.onDescriptorRead(this, d, status));
                } else {
                    deliver(() -> mCallback.onDescriptorWrite(this, d, status));
                }
                break;
            }
            default:
                break;
            }
        }
    }

    /** The app's callback, on the drain thread (a binder thread's part) or
     *  the app's Handler. An exception in it is the app's, logged, not
     *  fatal to the drain. */
    private void deliver(Runnable r) {
        if (mCallback == null) return;
        if (mHandler != null) {
            mHandler.post(r);
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            android.util.Log.e("BluetoothGatt", "callback threw", t);
        }
    }

    /** The discovered tree: "S" uuid handle, "C" uuid handle properties,
     *  "D" uuid handle, each under the one before it. */
    private void build(String[] tree) {
        List<BluetoothGattService> services = new ArrayList<>();
        Map<Integer, BluetoothGattCharacteristic> chars = new HashMap<>();
        Map<Integer, BluetoothGattDescriptor> descs = new HashMap<>();
        BluetoothGattService service = null;
        BluetoothGattCharacteristic characteristic = null;
        for (int i = 0; tree != null && i < tree.length; ) {
            String tag = tree[i];
            try {
                if ("S".equals(tag) && i + 2 < tree.length) {
                    service = new BluetoothGattService(mDevice, UUID.fromString(tree[i + 1]),
                            Integer.parseInt(tree[i + 2]), BluetoothGattService.SERVICE_TYPE_PRIMARY);
                    services.add(service);
                    characteristic = null;
                    i += 3;
                } else if ("C".equals(tag) && i + 3 < tree.length && service != null) {
                    int handle = Integer.parseInt(tree[i + 2]);
                    characteristic = new BluetoothGattCharacteristic(UUID.fromString(tree[i + 1]), handle,
                            Integer.parseInt(tree[i + 3]), 0);
                    service.addCharacteristic(characteristic);
                    chars.put(handle, characteristic);
                    i += 4;
                } else if ("D".equals(tag) && i + 2 < tree.length && characteristic != null) {
                    int handle = Integer.parseInt(tree[i + 2]);
                    BluetoothGattDescriptor d = new BluetoothGattDescriptor(UUID.fromString(tree[i + 1]), handle, 0);
                    characteristic.addDescriptor(d);
                    descs.put(handle, d);
                    i += 3;
                } else {
                    i++;
                }
            } catch (IllegalArgumentException malformed) {
                i++;
            }
        }
        mServices = Collections.unmodifiableList(services);
        mCharacteristics.clear();
        mCharacteristics.putAll(chars);
        mDescriptors.clear();
        mDescriptors.putAll(descs);
    }
}
