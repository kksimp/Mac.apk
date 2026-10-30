import android.bluetooth.*;
import com.macapk.loader.BluetoothBridge;
import com.macapk.loader.BluetoothPeripheralBridge;
import android.bluetooth.le.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only production JNI check. Never requests permission, scans, connects
 * or advertises. Run separately from fixtures that intentionally lack JNI. */
public final class BluetoothHostSmoke {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        boolean hardware = BluetoothBridge.available();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        check((adapter != null) == hardware, "adapter agrees with host hardware and opt-in gate");
        int authorization = BluetoothBridge.authorization();
        check(authorization >= 0 && authorization <= 3, "actual CoreBluetooth authorization range");
        int state = BluetoothBridge.state();
        check(state >= BluetoothAdapter.STATE_OFF && state <= BluetoothAdapter.STATE_TURNING_OFF, "Android state range");
        check(BluetoothBridge.isEnabled() == (state == BluetoothAdapter.STATE_ON), "state/enabled agree");
        if (authorization != 3) {
            check(adapter == null || adapter.getBluetoothLeScanner() == null,
                "unapproved scanner access is refused without prompting");
            check(adapter == null || adapter.getBluetoothLeAdvertiser() == null,
                "unapproved advertiser access is refused without prompting");
            check(BluetoothPeripheralBridge.openServer(new BluetoothGattServerCallback(){}) == null,
                "unapproved server is refused without prompting");
        }
        check(BluetoothPeripheralBridge.supportsLegacyAdvertising() == hardware,
            "legacy advertising capability agrees with available BLE bridge");
        check(new BluetoothManager().getConnectedDevices(BluetoothProfile.GATT).isEmpty(),
            "no fabricated connection");
        CountDownLatch unsupported = new CountDownLatch(2);
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartFailure(int error) {
                check(error == ADVERTISE_FAILED_FEATURE_UNSUPPORTED, "unsupported payload is rejected");
                unsupported.countDown();
            }
            @Override public void onStartSuccess(AdvertiseSettings ignored) {
                throw new AssertionError("unsupported payload was reported as advertising");
            }
        };
        BluetoothLeAdvertiser advertiser = BluetoothLeAdvertiser.macapkCreate();
        AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true).build();
        advertiser.startAdvertising(settings, new AdvertiseData.Builder()
            .addManufacturerData(0x1234, new byte[]{1}).build(), callback);
        advertiser.startAdvertising(settings, new AdvertiseData.Builder().build(),
            new AdvertiseData.Builder().setIncludeDeviceName(true).build(), callback);
        // The bare JVM has no Cocoa frame pump or Boot-owned main loop.
        android.os.Looper.macapkDrainMainDue();
        android.os.Handler.drainGlobal();
        check(unsupported.await(3, TimeUnit.SECONDS), "unsupported advertising gives terminal callbacks");
        check(BluetoothBridge.authorization() == authorization, "read-only queries did not grant access");
        System.out.println("bluetooth-host: PASS (production JNI, hardware="+hardware
            +", authorization="+authorization+", state="+state+"; no permission request or radio operation)");
    }
}
