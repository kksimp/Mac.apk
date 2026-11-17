// android.bluetooth.BluetoothHeadset -- the HFP/HSP (hands-free) profile proxy.
//
// Mac.apk exposes no Bluetooth bridge, so no profile proxy is ever DELIVERED:
// BluetoothAdapter.getProfileProxy() returns false without calling the
// ServiceListener, which is AOSP's answer for a profile the device does not
// support. The constructor is package-private, as on AOSP, so an app cannot
// fabricate one. Every method below is therefore unreachable, and each answers
// the way a device with no connected devices does.
//
// The class exists so an app that NAMES the type links -- a cast or a field of
// this type is resolved at class definition time.
//
// Mirrors packages/modules/Bluetooth
// framework/java/android/bluetooth/BluetoothHeadset.java.
package android.bluetooth;

import java.util.Collections;
import java.util.List;

public final class BluetoothHeadset implements BluetoothProfile {

    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
    public static final String ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";
    public static final String ACTION_VENDOR_SPECIFIC_HEADSET_EVENT =
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT";

    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD";
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE";
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS";
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY =
            "android.bluetooth.headset.intent.category.companyid";
    public static final String VENDOR_RESULT_CODE_COMMAND_ANDROID = "+ANDROID";

    public static final int AT_CMD_TYPE_READ = 0;
    public static final int AT_CMD_TYPE_TEST = 1;
    public static final int AT_CMD_TYPE_SET = 2;
    public static final int AT_CMD_TYPE_BASIC = 3;
    public static final int AT_CMD_TYPE_ACTION = 4;

    public static final int STATE_AUDIO_DISCONNECTED = 10;
    public static final int STATE_AUDIO_CONNECTING = 11;
    public static final int STATE_AUDIO_CONNECTED = 12;

    BluetoothHeadset() {}

    /** AOSP connect(BluetoothDevice), a system API: false when the connection
     *  could not be started. */
    public boolean connect(BluetoothDevice device) { return false; }

    /** AOSP disconnect(BluetoothDevice), a system API: false when there was
     *  nothing to disconnect. */
    public boolean disconnect(BluetoothDevice device) { return false; }

    public boolean startVoiceRecognition(BluetoothDevice device) { return false; }

    public boolean stopVoiceRecognition(BluetoothDevice device) { return false; }

    public boolean isAudioConnected(BluetoothDevice device) { return false; }

    public boolean sendVendorSpecificResultCode(BluetoothDevice device, String command,
            String arg) {
        return false;
    }

    @Override public int getConnectionState(BluetoothDevice device) { return STATE_DISCONNECTED; }

    @Override public List<BluetoothDevice> getConnectedDevices() { return Collections.emptyList(); }

    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        return Collections.emptyList();
    }
}
