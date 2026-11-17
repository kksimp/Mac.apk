// android.bluetooth.le.BluetoothLeScanner
//
// The BLE scan entry point obtained from BluetoothAdapter.getBluetoothLeScanner.
// On a host that scans (com.macapk.loader.HostBluetooth; Win.apk, on WinRT's
// advertisement watcher) it is real: the host runs one watcher while any
// callback is registered, a thread here drains its advertisements, builds a
// ScanResult from each (the device, RSSI, the advertising data and scan
// response as a ScanRecord), applies each callback's ScanFilters and posts
// the result to the main thread, which is where Android delivers them. A
// report delay batches them into onBatchScanResults. Results reach the app
// only with the location permission the target level asks for (or a
// neverForLocation scan), as on a device; without BLUETOOTH_SCAN startScan
// throws SecurityException, as the Bluetooth service does.
//
// Without such a host (the Mac) a scan never finds anything; startScan
// reports SCAN_FAILED_INTERNAL_ERROR to the callback so apps stop waiting,
// and stopScan is a no-op.
//
// Mirrors frameworks/base/core/java/android/bluetooth/le/BluetoothLeScanner.java.
package android.bluetooth.le;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import com.macapk.loader.HostBluetooth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BluetoothLeScanner {

    /** The extras the PendingIntent-based scan API delivers its results in.
     *  Plain strings, mirrored verbatim; no broadcast is ever sent here. */
    public static final String EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE";
    public static final String EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE";
    public static final String EXTRA_LIST_SCAN_RESULT =
            "android.bluetooth.le.extra.LIST_SCAN_RESULT";

    // AOSP ScanResult event-type bits.
    private static final int ET_LEGACY_MASK = 0x10;
    private static final int ET_CONNECTABLE_MASK = 0x01;

    /** One registered callback: its filters and settings, and the batch it
     *  collects when it asked for a report delay. */
    private static final class Client {
        final List<ScanFilter> filters;
        final ScanSettings settings;
        final List<ScanResult> batch = new ArrayList<>();
        long batchDue;
        Client(List<ScanFilter> filters, ScanSettings settings) {
            this.filters = filters;
            this.settings = settings;
        }
    }

    // The host has one watcher; every scanner instance shares it.
    private static final Object sLock = new Object();
    private static final Map<ScanCallback, Client> sClients = new LinkedHashMap<>();
    private static boolean sRunning;
    private static android.os.Handler sMain;
    private static Set<String> sBonded = new HashSet<>();

    public BluetoothLeScanner() {}

    public void startScan(ScanCallback callback) {
        startScan(null, new ScanSettings.Builder().build(), callback);
    }

    public void startScan(List<ScanFilter> filters, ScanSettings settings, ScanCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback is null");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || !HostBluetooth.leAvailable()) {
            fail(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            return;
        }
        HostBluetooth.enforceScan("startScan");
        synchronized (sLock) {
            if (sClients.containsKey(callback)) {
                fail(callback, ScanCallback.SCAN_FAILED_ALREADY_STARTED);
                return;
            }
            if (sClients.isEmpty()) {
                int error = HostBluetooth.scanStart();
                if (error != 0) {
                    fail(callback, error);
                    return;
                }
                sBonded = bondedAddresses(adapter);
                sRunning = true;
                Thread t = new Thread(BluetoothLeScanner::drain, "ble-scan");
                t.setDaemon(true);
                t.start();
            }
            Client c = new Client(filters, settings != null ? settings : new ScanSettings.Builder().build());
            c.batchDue = android.os.SystemClock.elapsedRealtime() + c.settings.getReportDelayMillis();
            sClients.put(callback, c);
        }
    }

    /**
     * The PendingIntent-based scan (API 26). AOSP returns a status int:
     * SCAN_FAILED_INTERNAL_ERROR is the honest one here -- a PendingIntent
     * delivery of results is not offered, and a success code would leave the
     * app waiting on a broadcast that cannot arrive. Note it CANNOT report
     * through the callback path, because there is no ScanCallback on this
     * overload; the return value is the whole channel, which is why it must
     * not be 0.
     */
    public int startScan(List<ScanFilter> filters, ScanSettings settings,
            android.app.PendingIntent callbackIntent) {
        return ScanCallback.SCAN_FAILED_INTERNAL_ERROR;
    }

    public void stopScan(ScanCallback callback) {
        synchronized (sLock) {
            if (sClients.remove(callback) == null || !sClients.isEmpty()) return;
            sRunning = false;
        }
        HostBluetooth.scanStop();
    }

    /** A removal: no PendingIntent scan is running, so there is nothing to stop. */
    public void stopScan(android.app.PendingIntent callbackIntent) {}

    /** AOSP: delivers a batching callback's collected results now. */
    public void flushPendingScanResults(ScanCallback callback) {
        synchronized (sLock) {
            Client c = sClients.get(callback);
            if (c != null) c.batchDue = 0;
        }
        deliverBatches(true);
    }

    private static void fail(final ScanCallback callback, final int error) {
        main().post(() -> callback.onScanFailed(error));
    }

    private static android.os.Handler main() {
        synchronized (sLock) {
            if (sMain == null) sMain = new android.os.Handler(android.os.Looper.getMainLooper());
            return sMain;
        }
    }

    private static Set<String> bondedAddresses(BluetoothAdapter adapter) {
        Set<String> out = new HashSet<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) out.add(d.getAddress());
        } catch (SecurityException noConnectPermission) {
            // Scanning without BLUETOOTH_CONNECT: nothing is known to be bonded.
        }
        return out;
    }

    /** The host's advertisements to the callbacks, until the last one stops. */
    private static void drain() {
        for (;;) {
            Object[] e = HostBluetooth.scanNext(500);
            synchronized (sLock) {
                if (!sRunning) return;
            }
            if (e == null) {
                deliverBatches(false);
                continue;
            }
            if (e[0] == null) {
                // The watcher stopped by itself (the radio went off): every
                // scan is over.
                List<ScanCallback> callbacks;
                synchronized (sLock) {
                    callbacks = new ArrayList<>(sClients.keySet());
                    sClients.clear();
                    sRunning = false;
                }
                HostBluetooth.scanStop();
                for (ScanCallback cb : callbacks) fail(cb, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                return;
            }
            if (!HostBluetooth.scanResultsAllowed()) continue;
            String address = (String) e[0];
            int[] v = (int[]) e[1];
            byte[] record = (byte[]) e[2];
            String name = (String) e[3];
            ScanRecord parsed = ScanRecord.parseFromBytes(record);
            if (name == null && parsed != null) name = parsed.getDeviceName();
            BluetoothDevice device = new BluetoothDevice(address, name, BluetoothDevice.DEVICE_TYPE_LE,
                    sBonded.contains(address));
            int eventType = (v[3] != 0 ? ET_LEGACY_MASK : 0) | (v[2] != 0 ? ET_CONNECTABLE_MASK : 0);
            final ScanResult result = new ScanResult(device, eventType, BluetoothDevice.PHY_LE_1M,
                    v[3] != 0 ? ScanResult.PHY_UNUSED : BluetoothDevice.PHY_LE_1M, ScanResult.SID_NOT_PRESENT,
                    v[1], v[0], ScanResult.PERIODIC_INTERVAL_NOT_PRESENT, parsed,
                    android.os.SystemClock.elapsedRealtimeNanos());
            List<Map.Entry<ScanCallback, Client>> clients;
            synchronized (sLock) {
                clients = new ArrayList<>(sClients.entrySet());
            }
            for (Map.Entry<ScanCallback, Client> entry : clients) {
                final ScanCallback cb = entry.getKey();
                Client c = entry.getValue();
                if (!matches(c.filters, result)) continue;
                if (c.settings.getReportDelayMillis() > 0) {
                    synchronized (sLock) { c.batch.add(result); }
                    continue;
                }
                main().post(() -> cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result));
            }
            deliverBatches(false);
        }
    }

    private static boolean matches(List<ScanFilter> filters, ScanResult result) {
        if (filters == null || filters.isEmpty()) return true;
        for (ScanFilter f : filters) {
            if (f != null && f.matches(result)) return true;
        }
        return false;
    }

    /** The batches whose report delay is up (or all of them, when flushed). */
    private static void deliverBatches(boolean all) {
        long now = android.os.SystemClock.elapsedRealtime();
        List<Runnable> posts = new ArrayList<>();
        synchronized (sLock) {
            for (Map.Entry<ScanCallback, Client> entry : sClients.entrySet()) {
                final ScanCallback cb = entry.getKey();
                Client c = entry.getValue();
                long delay = c.settings.getReportDelayMillis();
                if (delay <= 0 || (!all && now < c.batchDue)) continue;
                c.batchDue = now + delay;
                if (c.batch.isEmpty()) continue;
                final List<ScanResult> results = new ArrayList<>(c.batch);
                c.batch.clear();
                posts.add(() -> cb.onBatchScanResults(results));
            }
        }
        for (Runnable r : posts) main().post(r);
    }
}
