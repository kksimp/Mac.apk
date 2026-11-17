// android.bluetooth.BluetoothCodecStatus -- the A2DP codec in use and the ones
// the local device and the peer could use instead. A value type that goes with
// BluetoothCodecConfig; see there for why it exists with no A2DP stream behind
// it. Mirrors packages/modules/Bluetooth
// framework/java/android/bluetooth/BluetoothCodecStatus.java.
package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BluetoothCodecStatus implements Parcelable {

    /** Extra of BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED: the new status. */
    public static final String EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS";

    private final BluetoothCodecConfig mCodecConfig;
    private final List<BluetoothCodecConfig> mCodecsLocalCapabilities;
    private final List<BluetoothCodecConfig> mCodecsSelectableCapabilities;

    /** @hide in AOSP, where the Builder is the public way in. */
    public BluetoothCodecStatus(BluetoothCodecConfig codecConfig,
            List<BluetoothCodecConfig> codecsLocalCapabilities,
            List<BluetoothCodecConfig> codecsSelectableCapabilities) {
        mCodecConfig = codecConfig;
        mCodecsLocalCapabilities = codecsLocalCapabilities;
        mCodecsSelectableCapabilities = codecsSelectableCapabilities;
    }

    /** The codec in use, or null when there is none. */
    public BluetoothCodecConfig getCodecConfig() { return mCodecConfig; }

    /** What the local device can encode; never null. */
    public List<BluetoothCodecConfig> getCodecsLocalCapabilities() {
        return (mCodecsLocalCapabilities == null) ? Collections.emptyList() : mCodecsLocalCapabilities;
    }

    /** What both ends agreed could be selected; never null. */
    public List<BluetoothCodecConfig> getCodecsSelectableCapabilities() {
        return (mCodecsSelectableCapabilities == null) ? Collections.emptyList() : mCodecsSelectableCapabilities;
    }

    /** Whether the codec config is one of the selectable ones: same codec type,
     *  and each parameter it sets is among those the selectable entry allows. */
    public boolean isCodecConfigSelectable(BluetoothCodecConfig codecConfig) {
        if (codecConfig == null) return false;
        for (BluetoothCodecConfig selectable : getCodecsSelectableCapabilities()) {
            if (codecConfig.getCodecType() != selectable.getCodecType()) continue;
            int sampleRate = codecConfig.getSampleRate();
            if ((sampleRate & selectable.getSampleRate()) == 0 && sampleRate != BluetoothCodecConfig.SAMPLE_RATE_NONE) {
                continue;
            }
            int bitsPerSample = codecConfig.getBitsPerSample();
            if ((bitsPerSample & selectable.getBitsPerSample()) == 0
                    && bitsPerSample != BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
                continue;
            }
            int channelMode = codecConfig.getChannelMode();
            if ((channelMode & selectable.getChannelMode()) == 0
                    && channelMode != BluetoothCodecConfig.CHANNEL_MODE_NONE) {
                continue;
            }
            return true;
        }
        return false;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof BluetoothCodecStatus)) return false;
        BluetoothCodecStatus other = (BluetoothCodecStatus) o;
        return Objects.equals(other.mCodecConfig, mCodecConfig)
                && sameCapabilities(other.getCodecsLocalCapabilities(), getCodecsLocalCapabilities())
                && sameCapabilities(other.getCodecsSelectableCapabilities(), getCodecsSelectableCapabilities());
    }

    /** AOSP compares the capability lists as sets: their order carries no meaning. */
    private static boolean sameCapabilities(List<BluetoothCodecConfig> a, List<BluetoothCodecConfig> b) {
        return a.size() == b.size() && a.containsAll(b);
    }

    @Override public int hashCode() {
        return Objects.hash(mCodecConfig, mCodecsLocalCapabilities, mCodecsSelectableCapabilities);
    }

    @Override public String toString() {
        return "{mCodecConfig:" + mCodecConfig + ",mCodecsLocalCapabilities:" + mCodecsLocalCapabilities
                + ",mCodecsSelectableCapabilities:" + mCodecsSelectableCapabilities + "}";
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(mCodecConfig, 0);
        out.writeTypedList(getCodecsLocalCapabilities());
        out.writeTypedList(getCodecsSelectableCapabilities());
    }

    public static final Parcelable.Creator<BluetoothCodecStatus> CREATOR =
            new Parcelable.Creator<BluetoothCodecStatus>() {
        @Override public BluetoothCodecStatus createFromParcel(Parcel in) {
            BluetoothCodecConfig codecConfig = in.readTypedObject(BluetoothCodecConfig.CREATOR);
            List<BluetoothCodecConfig> local = in.createTypedArrayList(BluetoothCodecConfig.CREATOR);
            List<BluetoothCodecConfig> selectable = in.createTypedArrayList(BluetoothCodecConfig.CREATOR);
            return new BluetoothCodecStatus(codecConfig, local, selectable);
        }
        @Override public BluetoothCodecStatus[] newArray(int size) { return new BluetoothCodecStatus[size]; }
    };

    public static final class Builder {
        private BluetoothCodecConfig mCodecConfig = null;
        private List<BluetoothCodecConfig> mCodecsLocalCapabilities = null;
        private List<BluetoothCodecConfig> mCodecsSelectableCapabilities = null;

        public Builder() {}

        public Builder setCodecConfig(BluetoothCodecConfig codecConfig) {
            mCodecConfig = codecConfig;
            return this;
        }

        public Builder setCodecsLocalCapabilities(List<BluetoothCodecConfig> codecsLocalCapabilities) {
            mCodecsLocalCapabilities = codecsLocalCapabilities;
            return this;
        }

        public Builder setCodecsSelectableCapabilities(List<BluetoothCodecConfig> codecsSelectableCapabilities) {
            mCodecsSelectableCapabilities = codecsSelectableCapabilities;
            return this;
        }

        public BluetoothCodecStatus build() {
            return new BluetoothCodecStatus(mCodecConfig, mCodecsLocalCapabilities, mCodecsSelectableCapabilities);
        }
    }
}
