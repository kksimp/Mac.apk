// Legacy service-UUID advertisements use the real CoreBluetooth bridge.
// Extended/periodic advertising is unavailable through the host API and reports
// ADVERTISE_FAILED_FEATURE_UNSUPPORTED through its own callback.
package android.bluetooth.le;

import android.os.Handler;
import android.os.Looper;

public final class BluetoothLeAdvertiser {

    BluetoothLeAdvertiser() {}

    public static BluetoothLeAdvertiser macapkCreate() { return new BluetoothLeAdvertiser(); }

    public void startAdvertising(AdvertiseSettings settings, AdvertiseData advertiseData,
            AdvertiseCallback callback) {
        startAdvertising(settings, advertiseData, null, callback);
    }

    public void startAdvertising(AdvertiseSettings settings, AdvertiseData advertiseData,
            AdvertiseData scanResponse, AdvertiseCallback callback) {
        com.macapk.loader.BluetoothPeripheralBridge.startAdvertising(settings, advertiseData, scanResponse, callback);
    }

    /** Stops only the real advertisement registered with this callback. */
    public void stopAdvertising(AdvertiseCallback callback) {
        com.macapk.loader.BluetoothPeripheralBridge.stopAdvertising(callback);
    }

    public void startAdvertisingSet(AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            AdvertisingSetCallback callback) {
        startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                periodicData, 0, 0, callback, null);
    }

    public void startAdvertisingSet(AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            AdvertisingSetCallback callback, Handler handler) {
        startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                periodicData, 0, 0, callback, handler);
    }

    public void startAdvertisingSet(AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            int duration, int maxExtendedAdvertisingEvents, AdvertisingSetCallback callback) {
        startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                periodicData, duration, maxExtendedAdvertisingEvents, callback, null);
    }

    public void startAdvertisingSet(AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            int duration, int maxExtendedAdvertisingEvents, AdvertisingSetCallback callback,
            Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        final AdvertisingSetCallback cb = callback;
        // AOSP reports the failure with a NULL AdvertisingSet, because no set
        // was created -- so this hands back null rather than a hollow handle.
        Runnable fail = () -> cb.onAdvertisingSetStarted(null, 0,
                AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED);
        if (handler != null) {
            handler.post(fail);
        } else {
            post(fail);
        }
    }

    /** A removal: no set is running, so there is nothing to stop. */
    public void stopAdvertisingSet(AdvertisingSetCallback callback) {}

    /** AOSP delivers advertiser callbacks on the main looper by default. */
    private static void post(Runnable r) {
        macapkDispatchCallback(r);
    }

    public static void macapkDispatchCallback(Runnable r) {
        Looper main = Looper.getMainLooper();
        if (main != null) {
            new Handler(main).post(r);
        } else {
            com.macapk.loader.BluetoothBridge.dispatch(r);
        }
    }
}
