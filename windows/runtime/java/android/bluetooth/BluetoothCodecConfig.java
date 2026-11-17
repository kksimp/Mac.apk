// android.bluetooth.BluetoothCodecConfig -- one A2DP source codec and its
// parameters. A value type: what BluetoothA2dp reports and takes. No A2DP
// stream is ever negotiated here, so nothing hands an app a live one, but the
// type, its constants and its Builder are what an app's audio settings code
// links against. Mirrors packages/modules/Bluetooth
// framework/java/android/bluetooth/BluetoothCodecConfig.java.
package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

public final class BluetoothCodecConfig implements Parcelable {

    public static final int SOURCE_CODEC_TYPE_SBC = 0;
    public static final int SOURCE_CODEC_TYPE_AAC = 1;
    public static final int SOURCE_CODEC_TYPE_APTX = 2;
    public static final int SOURCE_CODEC_TYPE_APTX_HD = 3;
    public static final int SOURCE_CODEC_TYPE_LDAC = 4;
    public static final int SOURCE_CODEC_TYPE_LC3 = 5;
    public static final int SOURCE_CODEC_TYPE_OPUS = 6;
    public static final int SOURCE_CODEC_TYPE_INVALID = 1000 * 1000;

    public static final int CODEC_PRIORITY_DISABLED = -1;
    public static final int CODEC_PRIORITY_DEFAULT = 0;
    public static final int CODEC_PRIORITY_HIGHEST = 1000 * 1000;

    public static final int SAMPLE_RATE_NONE = 0;
    public static final int SAMPLE_RATE_44100 = 0x1 << 0;
    public static final int SAMPLE_RATE_48000 = 0x1 << 1;
    public static final int SAMPLE_RATE_88200 = 0x1 << 2;
    public static final int SAMPLE_RATE_96000 = 0x1 << 3;
    public static final int SAMPLE_RATE_176400 = 0x1 << 4;
    public static final int SAMPLE_RATE_192000 = 0x1 << 5;

    public static final int BITS_PER_SAMPLE_NONE = 0;
    public static final int BITS_PER_SAMPLE_16 = 0x1 << 0;
    public static final int BITS_PER_SAMPLE_24 = 0x1 << 1;
    public static final int BITS_PER_SAMPLE_32 = 0x1 << 2;

    public static final int CHANNEL_MODE_NONE = 0;
    public static final int CHANNEL_MODE_MONO = 0x1 << 0;
    public static final int CHANNEL_MODE_STEREO = 0x1 << 1;

    private final int mCodecType;
    private final int mCodecPriority;
    private final int mSampleRate;
    private final int mBitsPerSample;
    private final int mChannelMode;
    private final long mCodecSpecific1;
    private final long mCodecSpecific2;
    private final long mCodecSpecific3;
    private final long mCodecSpecific4;

    /** @hide in AOSP, where the Builder is the public way in. */
    public BluetoothCodecConfig(int codecType, int codecPriority, int sampleRate, int bitsPerSample,
            int channelMode, long codecSpecific1, long codecSpecific2, long codecSpecific3,
            long codecSpecific4) {
        mCodecType = codecType;
        mCodecPriority = codecPriority;
        mSampleRate = sampleRate;
        mBitsPerSample = bitsPerSample;
        mChannelMode = channelMode;
        mCodecSpecific1 = codecSpecific1;
        mCodecSpecific2 = codecSpecific2;
        mCodecSpecific3 = codecSpecific3;
        mCodecSpecific4 = codecSpecific4;
    }

    /** @hide in AOSP: a codec type with every parameter unset. */
    public BluetoothCodecConfig(int codecType) {
        this(codecType, CODEC_PRIORITY_DEFAULT, SAMPLE_RATE_NONE, BITS_PER_SAMPLE_NONE, CHANNEL_MODE_NONE,
                0, 0, 0, 0);
    }

    public int getCodecType() { return mCodecType; }

    /** AOSP: SBC is the one codec every A2DP device must have. */
    public boolean isMandatoryCodec() { return mCodecType == SOURCE_CODEC_TYPE_SBC; }

    public int getCodecPriority() { return mCodecPriority; }

    public int getSampleRate() { return mSampleRate; }

    public int getBitsPerSample() { return mBitsPerSample; }

    public int getChannelMode() { return mChannelMode; }

    public long getCodecSpecific1() { return mCodecSpecific1; }

    public long getCodecSpecific2() { return mCodecSpecific2; }

    public long getCodecSpecific3() { return mCodecSpecific3; }

    public long getCodecSpecific4() { return mCodecSpecific4; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof BluetoothCodecConfig)) return false;
        BluetoothCodecConfig other = (BluetoothCodecConfig) o;
        return other.mCodecType == mCodecType && other.mCodecPriority == mCodecPriority
                && other.mSampleRate == mSampleRate && other.mBitsPerSample == mBitsPerSample
                && other.mChannelMode == mChannelMode && other.mCodecSpecific1 == mCodecSpecific1
                && other.mCodecSpecific2 == mCodecSpecific2 && other.mCodecSpecific3 == mCodecSpecific3
                && other.mCodecSpecific4 == mCodecSpecific4;
    }

    @Override public int hashCode() {
        return Objects.hash(mCodecType, mCodecPriority, mSampleRate, mBitsPerSample, mChannelMode,
                mCodecSpecific1, mCodecSpecific2, mCodecSpecific3, mCodecSpecific4);
    }

    @Override public String toString() {
        return "{codecType:" + mCodecType + ",mCodecPriority:" + mCodecPriority + ",mSampleRate:"
                + String.format("0x%x", mSampleRate) + ",mBitsPerSample:" + String.format("0x%x", mBitsPerSample)
                + ",mChannelMode:" + String.format("0x%x", mChannelMode) + ",mCodecSpecific1:" + mCodecSpecific1
                + ",mCodecSpecific2:" + mCodecSpecific2 + ",mCodecSpecific3:" + mCodecSpecific3
                + ",mCodecSpecific4:" + mCodecSpecific4 + "}";
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCodecType);
        out.writeInt(mCodecPriority);
        out.writeInt(mSampleRate);
        out.writeInt(mBitsPerSample);
        out.writeInt(mChannelMode);
        out.writeLong(mCodecSpecific1);
        out.writeLong(mCodecSpecific2);
        out.writeLong(mCodecSpecific3);
        out.writeLong(mCodecSpecific4);
    }

    public static final Parcelable.Creator<BluetoothCodecConfig> CREATOR =
            new Parcelable.Creator<BluetoothCodecConfig>() {
        @Override public BluetoothCodecConfig createFromParcel(Parcel in) {
            int codecType = in.readInt();
            int codecPriority = in.readInt();
            int sampleRate = in.readInt();
            int bitsPerSample = in.readInt();
            int channelMode = in.readInt();
            long codecSpecific1 = in.readLong();
            long codecSpecific2 = in.readLong();
            long codecSpecific3 = in.readLong();
            long codecSpecific4 = in.readLong();
            return new BluetoothCodecConfig(codecType, codecPriority, sampleRate, bitsPerSample, channelMode,
                    codecSpecific1, codecSpecific2, codecSpecific3, codecSpecific4);
        }
        @Override public BluetoothCodecConfig[] newArray(int size) { return new BluetoothCodecConfig[size]; }
    };

    public static final class Builder {
        private int mCodecType = SOURCE_CODEC_TYPE_INVALID;
        private int mCodecPriority = CODEC_PRIORITY_DEFAULT;
        private int mSampleRate = SAMPLE_RATE_NONE;
        private int mBitsPerSample = BITS_PER_SAMPLE_NONE;
        private int mChannelMode = CHANNEL_MODE_NONE;
        private long mCodecSpecific1 = 0;
        private long mCodecSpecific2 = 0;
        private long mCodecSpecific3 = 0;
        private long mCodecSpecific4 = 0;

        public Builder() {}

        public Builder setCodecType(int codecType) { mCodecType = codecType; return this; }

        public Builder setCodecPriority(int codecPriority) { mCodecPriority = codecPriority; return this; }

        public Builder setSampleRate(int sampleRate) { mSampleRate = sampleRate; return this; }

        public Builder setBitsPerSample(int bitsPerSample) { mBitsPerSample = bitsPerSample; return this; }

        public Builder setChannelMode(int channelMode) { mChannelMode = channelMode; return this; }

        public Builder setCodecSpecific1(long codecSpecific1) { mCodecSpecific1 = codecSpecific1; return this; }

        public Builder setCodecSpecific2(long codecSpecific2) { mCodecSpecific2 = codecSpecific2; return this; }

        public Builder setCodecSpecific3(long codecSpecific3) { mCodecSpecific3 = codecSpecific3; return this; }

        public Builder setCodecSpecific4(long codecSpecific4) { mCodecSpecific4 = codecSpecific4; return this; }

        public BluetoothCodecConfig build() {
            return new BluetoothCodecConfig(mCodecType, mCodecPriority, mSampleRate, mBitsPerSample, mChannelMode,
                    mCodecSpecific1, mCodecSpecific2, mCodecSpecific3, mCodecSpecific4);
        }
    }
}
