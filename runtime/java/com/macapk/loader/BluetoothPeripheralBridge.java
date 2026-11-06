package com.macapk.loader;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** BLE peripheral transport backed by CoreBluetooth. No operation reports
 * success until the host has accepted the corresponding real operation.
 * Radio interval and transmit power remain managed by macOS. Classic Bluetooth,
 * extended advertising, arbitrary advertising bytes and confirmed indications
 * are outside this bridge's public-API capabilities. */
public final class BluetoothPeripheralBridge {
    private BluetoothPeripheralBridge() {}
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID DESCRIPTION = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb");
    private static final Map<AdvertiseCallback, Advertisement> advertisements = new IdentityHashMap<>();
    private static final java.util.Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private static native int nativeState();
    private static native long nativeOpenServer(Session session);
    private static native void nativeCloseServer(long server);
    private static native boolean nativeAddService(long server, int service, String uuid,
            boolean primary, int[] ids, String[] uuids, int[] properties, int[] permissions,
            String[] descriptions);
    private static native boolean nativeRemoveService(long server, int service);
    private static native void nativeClearServices(long server);
    private static native boolean nativeRespond(long server, String central, int request,
            int status, int offset, byte[] value);
    private static native boolean nativeNotify(long server, String central, int characteristic, byte[] value);
    private static native long nativeStartAdvertising(Advertisement advertisement, String[] uuids,
            boolean includeName, int timeout);
    private static native void nativeStopAdvertising(long advertisement);

    public static boolean available() { return BluetoothBridge.available(); }
    public static boolean isEnabled() {
        if (!available() || BluetoothBridge.authorization() != 3 || !BluetoothBridge.isEnabled()) return false;
        try { return nativeState() == 5; }
        catch (UnsatisfiedLinkError absentRuntime) { return false; }
    }
    /** The host supports the legacy service-UUID advertising surface. This
     * capability does not mean advertising has started or permission is granted.
     * Concurrent advertising requests can still fail with TOO_MANY_ADVERTISERS. */
    public static boolean supportsLegacyAdvertising() { return available(); }
    public static BluetoothGattServer openServer(BluetoothGattServerCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        if (!BluetoothBridge.hasPermission("android.permission.BLUETOOTH_CONNECT") || !isEnabled()) return null;
        Session session = new Session(callback);
        session.handle = nativeOpenServer(session);
        if (session.handle != 0) sessions.add(session);
        return session.handle == 0 ? null : BluetoothGattServer.macapkCreate(session);
    }
    public static BluetoothLeAdvertiser createAdvertiser() {
        return isEnabled() ? BluetoothLeAdvertiser.macapkCreate() : null;
    }
    public static List<BluetoothDevice> connectedDevices() {
        Map<String, BluetoothDevice> result = new LinkedHashMap<>();
        for (Session session : sessions) for (BluetoothDevice device : session.connectedDevices())
            result.put(BluetoothBridge.identifier(device), device);
        return new ArrayList<>(result.values());
    }
    public static int connectionState(BluetoothDevice device) {
        for (Session session : sessions) if (session.connected(device)) return BluetoothProfile.STATE_CONNECTED;
        return BluetoothProfile.STATE_DISCONNECTED;
    }
    public static List<BluetoothDevice> devicesMatchingConnectionStates(int[] states) {
        if (states != null) for (int state : states)
            if (state == BluetoothProfile.STATE_CONNECTED) return connectedDevices();
        return Collections.emptyList();
    }

    private static boolean unsupported(AdvertiseData data) {
        return data != null && (data.getIncludeTxPowerLevel()
                || data.getManufacturerSpecificData().size() != 0 || !data.getServiceData().isEmpty());
    }
    private static boolean empty(AdvertiseData data) {
        return data == null || (!data.getIncludeDeviceName() && !data.getIncludeTxPowerLevel()
                && data.getServiceUuids().isEmpty() && data.getManufacturerSpecificData().size() == 0
                && data.getServiceData().isEmpty());
    }
    public static void startAdvertising(AdvertiseSettings settings, AdvertiseData data,
            AdvertiseData scanResponse, AdvertiseCallback callback) {
        if (settings == null || data == null || callback == null)
            throw new IllegalArgumentException("settings, data and callback cannot be null");
        if (unsupported(data) || !empty(scanResponse) || !settings.isConnectable()) {
            BluetoothLeAdvertiser.macapkDispatchCallback(() -> callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED));
            return;
        }
        synchronized (advertisements) {
            if (advertisements.containsKey(callback)) {
                BluetoothLeAdvertiser.macapkDispatchCallback(() -> callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED));
                return;
            }
            if (!BluetoothBridge.hasPermission("android.permission.BLUETOOTH_ADVERTISE") || !isEnabled()) {
                BluetoothLeAdvertiser.macapkDispatchCallback(() -> callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR));
                return;
            }
            Advertisement advertisement = new Advertisement(callback, settings);
            advertisements.put(callback, advertisement);
            String[] uuids = data.getServiceUuids().stream().map(Object::toString).toArray(String[]::new);
            long handle = nativeStartAdvertising(advertisement, uuids, data.getIncludeDeviceName(), settings.getTimeout());
            advertisement.handle = Math.max(handle, 0);
            if (handle <= 0) advertisement.onResult((int)-handle == 0
                    ? AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR : (int)-handle);
        }
    }
    public static void stopAdvertising(AdvertiseCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        synchronized (advertisements) {
            Advertisement advertisement = advertisements.remove(callback);
            if (advertisement != null) {
                advertisement.stopped = true;
                if (advertisement.handle != 0) nativeStopAdvertising(advertisement.handle);
            }
        }
    }
    private static final class Advertisement {
        private final AdvertiseCallback callback;
        private final AdvertiseSettings settings;
        private long handle;
        private volatile boolean stopped;
        private boolean completed;
        Advertisement(AdvertiseCallback callback, AdvertiseSettings settings) {
            this.callback = callback; this.settings = settings;
        }
        // Called on the native callback queue, never the CoreBluetooth queue.
        private void onResult(int status) {
            synchronized (advertisements) {
                if (completed || stopped || advertisements.get(callback) != this) return;
                completed = true;
                if (status != 0) advertisements.remove(callback);
            }
            BluetoothLeAdvertiser.macapkDispatchCallback(() -> {
                if (stopped) return;
                if (status == 0) callback.onStartSuccess(settings); else callback.onStartFailure(status);
            });
        }
        private void onStopped() {
            synchronized (advertisements) {
                if (advertisements.get(callback) == this) advertisements.remove(callback);
                stopped = true;
            }
        }
    }

    /** Internal lifetime owned by BluetoothGattServer. */
    public static final class Session {
        private volatile long handle;
        private volatile boolean closed;
        private final BluetoothGattServerCallback callback;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<BluetoothGattService, Registration> registrations = new IdentityHashMap<>();
        private final Map<Integer, Registration> serviceIds = new LinkedHashMap<>();
        private final Map<Integer, BluetoothGattCharacteristic> characteristics = new LinkedHashMap<>();
        private final Map<String, BluetoothDevice> connected = new LinkedHashMap<>();
        Session(BluetoothGattServerCallback callback) { this.callback = callback; }
        private record Registration(int id, BluetoothGattService service, int[] characteristicIds,
                                    boolean[] published) {}

        public synchronized boolean addService(BluetoothGattService service) {
            if (closed || service == null || registrations.containsKey(service)
                    || service.getUuid() == null || !service.getIncludedServices().isEmpty()
                    || !BluetoothBridge.hasPermission("android.permission.BLUETOOTH_CONNECT")) return false;
            List<BluetoothGattCharacteristic> chars = service.getCharacteristics();
            int count = chars.size();
            if (count == 0 || count > 128) return false;
            int[] ids = new int[count], properties = new int[count], permissions = new int[count];
            String[] uuids = new String[count], descriptions = new String[count];
            for (int i = 0; i < count; i++) {
                BluetoothGattCharacteristic c = chars.get(i);
                int props = c.getProperties(), perms = c.getPermissions();
                // CoreBluetooth cannot represent authenticated signed writes or
                // require a particular MITM pairing policy. Reject those rather
                // than silently weakening the service's security requirement.
                if (c.getUuid() == null || props == 0 || (props & ~0x1e) != 0 || (perms & ~(0x01 | 0x02 | 0x10 | 0x20)) != 0
                        || (props & 0x0c) == 0x0c) return false;
                uuids[i] = c.getUuid().toString(); properties[i] = props; permissions[i] = perms;
                for (BluetoothGattDescriptor descriptor : c.getDescriptors()) {
                    if (CCCD.equals(descriptor.getUuid())) {
                        if (descriptor.getPermissions() != 0x11) return false;
                        continue; // managed by CoreBluetooth
                    }
                    if (!DESCRIPTION.equals(descriptor.getUuid()) || descriptor.getPermissions() != 1) return false;
                    byte[] value = descriptor.getValue();
                    descriptions[i] = value == null ? "" : new String(value, StandardCharsets.UTF_8);
                }
                ids[i] = nextId.getAndIncrement();
            }
            int serviceId = nextId.getAndIncrement();
            Registration registration = new Registration(serviceId, service, ids, new boolean[1]);
            registrations.put(service, registration); serviceIds.put(serviceId, registration);
            for (int i = 0; i < count; i++) characteristics.put(ids[i], chars.get(i));
            if (nativeAddService(handle, serviceId, service.getUuid().toString(), service.getType() == 0,
                    ids, uuids, properties, permissions, descriptions)) return true;
            forget(registration); return false;
        }
        private void forget(Registration registration) {
            registrations.remove(registration.service); serviceIds.remove(registration.id);
            for (int id : registration.characteristicIds) characteristics.remove(id);
        }
        public synchronized boolean removeService(BluetoothGattService service) {
            Registration registration = registrations.get(service);
            if (closed || registration == null || !registration.published[0]
                    || !nativeRemoveService(handle, registration.id)) return false;
            forget(registration); return true;
        }
        public synchronized void clearServices() {
            if (closed) return;
            nativeClearServices(handle); registrations.clear(); serviceIds.clear(); characteristics.clear();
        }
        public synchronized List<BluetoothGattService> getServices() {
            List<BluetoothGattService> services = new ArrayList<>();
            for (Registration r : serviceIds.values()) if (r.published[0]) services.add(r.service);
            return services;
        }
        public synchronized BluetoothGattService getService(UUID uuid) {
            for (Registration r : serviceIds.values())
                if (r.published[0] && r.service.getUuid().equals(uuid)) return r.service;
            return null;
        }
        public synchronized List<BluetoothDevice> connectedDevices() { return new ArrayList<>(connected.values()); }
        public synchronized boolean connected(BluetoothDevice device) {
            return device != null && connected.containsKey(BluetoothBridge.identifier(device));
        }
        public boolean respond(BluetoothDevice device, int request, int status, int offset, byte[] value) {
            String identifier = device == null ? null : BluetoothBridge.identifier(device);
            return !closed && identifier != null && BluetoothBridge.hasPermission("android.permission.BLUETOOTH_CONNECT")
                    && nativeRespond(handle, identifier, request, status, offset, value);
        }
        public synchronized boolean notify(BluetoothDevice device, BluetoothGattCharacteristic characteristic,
                boolean confirm, byte[] value) {
            if (closed || confirm || value == null || !connected(device)
                    || !BluetoothBridge.hasPermission("android.permission.BLUETOOTH_CONNECT")) return false;
            for (var entry : characteristics.entrySet()) {
                if (entry.getValue() == characteristic) return nativeNotify(handle,
                        BluetoothBridge.identifier(device), entry.getKey(), value);
            }
            return false;
        }
        public synchronized void close() {
            if (closed) return;
            closed = true; long previous = handle; handle = 0;
            sessions.remove(this);
            nativeCloseServer(previous); registrations.clear(); serviceIds.clear();
            characteristics.clear(); connected.clear();
        }

        // CoreBluetooth queue only enqueues these events on a second native
        // callback queue; dispatch() then runs application code outside JNI.
        private void onEvent(int kind, int id, int auxiliary, String identifier,
                int status, int offset, byte[] value) {
            BluetoothBridge.dispatch(() -> event(kind, id, auxiliary, identifier, status, offset, value));
        }
        private void event(int kind, int id, int auxiliary, String identifier,
                int status, int offset, byte[] value) {
            BluetoothGattCharacteristic characteristic;
            BluetoothDevice device = identifier == null ? null : BluetoothBridge.device(identifier, null);
            synchronized (this) {
                if (closed) return;
                characteristic = characteristics.get(id);
                if (kind == 2) {
                    if (auxiliary == BluetoothProfile.STATE_CONNECTED) connected.put(identifier, device);
                    else connected.remove(identifier);
                }
            }
            switch (kind) {
                case 2 -> {
                    callback.onConnectionStateChange(device, status, auxiliary);
                    if (auxiliary == BluetoothProfile.STATE_CONNECTED && offset >= 23)
                        callback.onMtuChanged(device, offset);
                }
                case 3 -> { if (characteristic != null)
                    callback.onCharacteristicReadRequest(device, auxiliary, offset, characteristic); }
                case 4 -> { if (characteristic != null)
                    callback.onCharacteristicWriteRequest(device, auxiliary, characteristic, false,
                            status != 0, offset, value); }
                case 5 -> {
                    if (characteristic == null) return;
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                    if (descriptor != null) {
                        descriptor.setValue(value);
                        callback.onDescriptorWriteRequest(device, auxiliary, descriptor, false, false, 0, value);
                    }
                }
                case 6 -> callback.onNotificationSent(device, status);
                default -> { }
            }
        }
        private void onServiceAdded(int id, int status) {
            BluetoothBridge.dispatch(() -> {
                BluetoothGattService service;
                synchronized (this) {
                    if (closed) return;
                    Registration registration = serviceIds.get(id);
                    if (registration == null) return;
                    service = registration.service;
                    if (status == 0) registration.published[0] = true; else forget(registration);
                }
                callback.onServiceAdded(status, service);
            });
        }
    }
}
