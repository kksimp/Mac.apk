// android.media.MediaRecorder -- records audio/video to a file. AOSP
// frameworks/base media/java/android/media/MediaRecorder.java.
//
// Records for real on the host's encoders (MediaCodec, on Windows Media
// Foundation's) and MediaMuxer: the microphone (AudioRecord) as AAC, and with
// VideoSource.SURFACE what reaches getSurface() -- a camera2 session that has
// it as an output, or Canvas drawing -- as H.264, into an MP4 or 3GPP file, or
// AAC alone into an ADTS file. What the host cannot record is refused in
// prepare() with AOSP's IOException, never accepted and dropped: AMR, Vorbis,
// Opus, HE-AAC and AAC-ELD audio, H.263/MPEG-4/VP8/HEVC video, WebM, Ogg and
// MPEG-2 TS files, and VideoSource.CAMERA (the legacy Camera). The state
// machine is AOSP's: a call out of order throws IllegalStateException.
package android.media;

public class MediaRecorder implements AudioRecordingMonitor, AudioRouting, MicrophoneDirection {
    public static final class AudioSource {
        public static final int DEFAULT = 0;
        public static final int MIC = 1;
        public static final int VOICE_UPLINK = 2;
        public static final int VOICE_DOWNLINK = 3;
        public static final int VOICE_CALL = 4;
        public static final int CAMCORDER = 5;
        public static final int VOICE_RECOGNITION = 6;
        public static final int VOICE_COMMUNICATION = 7;
        public static final int REMOTE_SUBMIX = 8;
        public static final int UNPROCESSED = 9;
        public static final int VOICE_PERFORMANCE = 10;
        private AudioSource() {}
    }
    public static final class VideoSource {
        public static final int DEFAULT = 0;
        public static final int CAMERA = 1;
        public static final int SURFACE = 2;
        private VideoSource() {}
    }
    public static final class OutputFormat {
        public static final int DEFAULT = 0;
        public static final int THREE_GPP = 1;
        public static final int MPEG_4 = 2;
        public static final int AMR_NB = 3;
        public static final int AMR_WB = 4;
        public static final int AAC_ADTS = 6;
        public static final int MPEG_2_TS = 8;
        public static final int WEBM = 9;
        public static final int OGG = 11;
        private OutputFormat() {}
    }
    public static final class AudioEncoder {
        public static final int DEFAULT = 0;
        public static final int AMR_NB = 1;
        public static final int AMR_WB = 2;
        public static final int AAC = 3;
        public static final int HE_AAC = 4;
        public static final int AAC_ELD = 5;
        public static final int VORBIS = 6;
        public static final int OPUS = 7;
        private AudioEncoder() {}
    }
    public static final class VideoEncoder {
        public static final int DEFAULT = 0;
        public static final int H263 = 1;
        public static final int H264 = 2;
        public static final int MPEG_4_SP = 3;
        public static final int VP8 = 4;
        public static final int HEVC = 5;
        private VideoEncoder() {}
    }

    public static final int MEDIA_RECORDER_INFO_UNKNOWN = 1;
    public static final int MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 800;
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED = 801;
    public static final int MEDIA_RECORDER_ERROR_UNKNOWN = 1;

    private String mOutputPath;
    private java.io.FileDescriptor mOutputFd;
    private String mNextOutputPath;
    private OnErrorListener mOnError;
    private OnInfoListener mOnInfo;

    // AOSP's state machine (MediaRecorder's class documentation).
    private static final int STATE_INITIAL = 0, STATE_INITIALIZED = 1, STATE_CONFIGURED = 2, STATE_PREPARED = 3,
            STATE_RECORDING = 4, STATE_RELEASED = 5;
    private int mState = STATE_INITIAL;

    private int mAudioSource = -1, mVideoSource = -1;   // -1: none set
    private int mOutputFormat = OutputFormat.DEFAULT;
    private int mAudioEncoder = AudioEncoder.DEFAULT, mVideoEncoder = VideoEncoder.DEFAULT;
    private int mAudioSamplingRate, mAudioChannels, mAudioBitRate;
    private int mVideoWidth = 1280, mVideoHeight = 720, mVideoFrameRate = 30, mVideoBitRate;
    private int mVideoProfile;
    private int mOrientation, mMaxDurationMs;
    private long mMaxFileSize;
    private boolean mHasLocation;
    private float mLatitude, mLongitude;
    private android.view.Surface mPersistentSurface;

    // --- the recording ------------------------------------------------------
    private AudioRecord mCapture;
    private Thread mCaptureThread, mVideoThread;
    private volatile boolean mCapturing, mPaused, mVideoDraining;
    private volatile int mPeakAmplitude;
    private MediaCodec mAudioCodec, mVideoCodec;
    private android.view.Surface mSurface;
    private MediaMuxer mMuxer;
    private java.io.OutputStream mAdts;      // OutputFormat.AAC_ADTS: the frames, each behind its header
    private final Object mSinkLock = new Object();
    private int mAudioTrack = -1, mVideoTrack = -1;
    private boolean mMuxerStarted, mLimitReached, mWroteSample;
    private long mBytesWritten;
    private long mStartNs, mPausedNs, mPauseStartNs;
    private long mVideoBaseUs = Long.MIN_VALUE, mLastVideoUs = -1;
    private final java.util.ArrayList<Object[]> mPending = new java.util.ArrayList<>();   // {video, bytes, ptsUs, flags}
    private boolean mPrivacySensitive;
    private boolean mPrivacySensitiveExplicit;
    private AudioDeviceInfo mPreferredDevice;

    public MediaRecorder() { }

    /** API 31. AOSP uses the Context to attribute the capture for the
     *  microphone privacy indicator; there is one package per process here and
     *  no indicator to attribute to, so it is validated non-null (as AOSP
     *  requires) and changes nothing observable. */
    public MediaRecorder(android.content.Context context) {
        java.util.Objects.requireNonNull(context, "context may not be null");
    }

    private void need(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what + " called in an invalid state: " + mState);
    }
    private void beforePrepare(String what) {
        need(mState == STATE_INITIAL || mState == STATE_INITIALIZED || mState == STATE_CONFIGURED, what);
    }

    public void setAudioSource(int audioSource) {
        beforePrepare("setAudioSource");
        need(mState != STATE_CONFIGURED, "setAudioSource");   // AOSP: before setOutputFormat
        if (audioSource < 0 || audioSource > getAudioSourceMax()) throw new IllegalArgumentException("Invalid audio source");
        mAudioSource = audioSource;
        mState = STATE_INITIALIZED;
    }
    public void setVideoSource(int videoSource) {
        beforePrepare("setVideoSource");
        need(mState != STATE_CONFIGURED, "setVideoSource");
        mVideoSource = videoSource;
        mState = STATE_INITIALIZED;
    }
    public void setOutputFormat(int outputFormat) {
        need(mState == STATE_INITIALIZED, "setOutputFormat");   // AOSP: after the sources
        mOutputFormat = outputFormat;
        mState = STATE_CONFIGURED;
    }
    public void setAudioEncoder(int audioEncoder) { need(mState == STATE_CONFIGURED, "setAudioEncoder"); mAudioEncoder = audioEncoder; }
    public void setVideoEncoder(int videoEncoder) { need(mState == STATE_CONFIGURED, "setVideoEncoder"); mVideoEncoder = videoEncoder; }
    public void setAudioEncodingBitRate(int bitRate) {
        if (bitRate <= 0) throw new IllegalArgumentException("Audio encoding bit rate is not positive");
        mAudioBitRate = bitRate;
    }
    public void setAudioSamplingRate(int samplingRate) {
        if (samplingRate <= 0) throw new IllegalArgumentException("Audio sampling rate is not positive");
        mAudioSamplingRate = samplingRate;
    }
    public void setAudioChannels(int numChannels) {
        if (numChannels <= 0) throw new IllegalArgumentException("Number of channels is not positive");
        mAudioChannels = numChannels;
    }
    public void setVideoEncodingBitRate(int bitRate) {
        if (bitRate <= 0) throw new IllegalArgumentException("Video encoding bit rate is not positive");
        mVideoBitRate = bitRate;
    }
    public void setVideoFrameRate(int rate) { need(mState == STATE_CONFIGURED, "setVideoFrameRate"); mVideoFrameRate = rate; }
    public void setVideoSize(int width, int height) {
        need(mState == STATE_CONFIGURED, "setVideoSize");
        mVideoWidth = width;
        mVideoHeight = height;
    }
    public void setMaxDuration(int max_duration_ms) { mMaxDurationMs = max_duration_ms; }
    public void setMaxFileSize(long max_filesize_bytes) { mMaxFileSize = max_filesize_bytes; }
    public void setOrientationHint(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270)
            throw new IllegalArgumentException("Unsupported angle: " + degrees);
        mOrientation = degrees;
    }
    public void setLocation(float latitude, float longitude) {
        if (latitude > 90 || latitude < -90) throw new IllegalArgumentException("Latitude: " + latitude + " out of range.");
        if (longitude > 180 || longitude < -180) throw new IllegalArgumentException("Longitude: " + longitude + " out of range");
        mHasLocation = true;
        mLatitude = latitude;
        mLongitude = longitude;
    }
    /** Time-lapse and slow motion: the pictures are stamped with the time
     *  they arrive, so a capture rate other than the frame rate has no effect. */
    public void setCaptureRate(double fps) {
        if (fps <= 0) throw new IllegalArgumentException("Invalid capture rate");
    }

    /** A recording has no preview of its own here: the app shows the camera
     *  on a Surface of its own session. */
    public void setPreviewDisplay(android.view.Surface sv) {}
    /** API 23: a Surface from MediaCodec.createPersistentInputSurface() as the
     *  video source's Surface. */
    public void setInputSurface(android.view.Surface surface) {
        beforePrepare("setInputSurface");
        mPersistentSurface = surface;
    }
    /** AOSP: the Surface to draw the video on (or to give a camera2 session
     *  as an output) with VideoSource.SURFACE, valid from prepare() to stop().
     *  See MediaCodec.createInputSurface() for what reaches the encoder. */
    public android.view.Surface getSurface() {
        if (mVideoSource != VideoSource.SURFACE || mSurface == null)
            throw new IllegalStateException("getSurface: no Surface (VideoSource.SURFACE, between prepare() and stop())");
        return mSurface;
    }
    public void setProfile(CamcorderProfile profile) {
        need(mState == STATE_INITIALIZED, "setProfile");
        setOutputFormat(profile.fileFormat);
        if (mVideoSource >= 0) {
            setVideoFrameRate(profile.videoFrameRate);
            setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
            setVideoEncodingBitRate(profile.videoBitRate);
            setVideoEncoder(profile.videoCodec);
        }
        if (mAudioSource >= 0) {
            setAudioEncodingBitRate(profile.audioBitRate);
            setAudioChannels(profile.audioChannels);
            setAudioSamplingRate(profile.audioSampleRate);
            setAudioEncoder(profile.audioCodec);
        }
    }
    public void setAudioAttributes(AudioAttributes attrs) {}

    public void setOutputFile(String path) { mOutputPath = path; mOutputFd = null; }
    public void setOutputFile(java.io.File file) { mOutputPath = file != null ? file.getPath() : null; mOutputFd = null; }
    public void setOutputFile(java.io.FileDescriptor fd) { mOutputFd = fd; mOutputPath = null; }

    /**
     * Builds the recording: the AAC encoder for the microphone, the H.264
     * encoder and its input Surface for VideoSource.SURFACE, and the MP4/3GPP
     * file (MediaMuxer) or the ADTS stream. AOSP reports what it cannot record
     * here, as an IOException ("prepare failed."), and so does this: AMR, Vorbis,
     * Opus, HE-AAC and AAC-ELD audio, video other than H.264, WebM, Ogg and
     * MPEG-2 TS files, VideoSource.CAMERA (the legacy Camera has no path to the
     * encoder; a camera2 session with getSurface() as an output has), and a
     * host without the encoders.
     */
    public void prepare() throws java.io.IOException {
        need(mState == STATE_CONFIGURED, "prepare");
        if (mOutputPath == null && mOutputFd == null) throw new java.io.IOException("No valid output file");
        final boolean audio = mAudioSource >= 0, video = mVideoSource >= 0;
        final boolean adts = mOutputFormat == OutputFormat.AAC_ADTS;
        String why = null;
        if (!audio && !video) why = "no audio or video source";
        else if (mOutputFormat != OutputFormat.DEFAULT && mOutputFormat != OutputFormat.MPEG_4
                && mOutputFormat != OutputFormat.THREE_GPP && !adts) why = "output format " + mOutputFormat;
        else if (audio && mAudioEncoder != AudioEncoder.DEFAULT && mAudioEncoder != AudioEncoder.AAC)
            why = "audio encoder " + mAudioEncoder + " (AAC only)";
        else if (video && mVideoEncoder != VideoEncoder.DEFAULT && mVideoEncoder != VideoEncoder.H264)
            why = "video encoder " + mVideoEncoder + " (H.264 only)";
        else if (video && mVideoSource != VideoSource.SURFACE) why = "VideoSource.CAMERA (use VideoSource.SURFACE)";
        else if (video && adts) why = "video in an ADTS file";
        if (why != null) throw new java.io.IOException("prepare failed: this host cannot record " + why);
        try {
            if (audio) {
                // The AAC encoder takes 44100 and 48000 Hz, mono and stereo;
                // another rate asked for is recorded at 44100.
                int rate = mAudioSamplingRate == 48000 ? 48000 : 44100;
                int channels = mAudioChannels == 2 ? 2 : 1;
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
                f.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitRate > 0 ? mAudioBitRate : 128000);
                mAudioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                mAudioCodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            if (video) {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mVideoWidth, mVideoHeight);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaFormat.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitRate > 0 ? mVideoBitRate : mVideoWidth * mVideoHeight * 4);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, mVideoFrameRate > 0 ? mVideoFrameRate : 30);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                if (mVideoProfile > 0) f.setInteger(MediaFormat.KEY_PROFILE, mVideoProfile);
                mVideoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                mVideoCodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                if (mPersistentSurface != null) {
                    mVideoCodec.setInputSurface(mPersistentSurface);
                    mSurface = mPersistentSurface;
                } else {
                    mSurface = mVideoCodec.createInputSurface();
                }
            }
            if (adts) {
                mAdts = new java.io.BufferedOutputStream(mOutputFd != null
                        ? new java.io.FileOutputStream(mOutputFd) : new java.io.FileOutputStream(mOutputPath));
            } else {
                int format = mOutputFormat == OutputFormat.THREE_GPP
                        ? MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
                mMuxer = mOutputFd != null ? new MediaMuxer(mOutputFd, format) : new MediaMuxer(mOutputPath, format);
                if (video && mOrientation != 0) mMuxer.setOrientationHint(mOrientation);
                if (mHasLocation) mMuxer.setLocation(mLatitude, mLongitude);
            }
        } catch (java.io.IOException | RuntimeException e) {
            closeRecording();
            throw new java.io.IOException("prepare failed: " + e.getMessage(), e);
        }
        mState = STATE_PREPARED;
    }

    /** Microseconds recorded so far: the clock, less the pauses. */
    private long recordedUs() {
        long now = mPaused ? mPauseStartNs : System.nanoTime();
        return (now - mStartNs - mPausedNs) / 1000;
    }

    private void info(final int what) {
        final OnInfoListener l = mOnInfo;
        if (l != null) l.onInfo(this, what, 0);
    }

    /** An encoder's output format: its track, and the file once every track
     *  is known. */
    private void sinkFormat(boolean video, MediaFormat format) {
        synchronized (mSinkLock) {
            if (mMuxer == null || mMuxerStarted) return;
            if (video) mVideoTrack = mMuxer.addTrack(format);
            else mAudioTrack = mMuxer.addTrack(format);
            if ((mAudioCodec != null && mAudioTrack < 0) || (mVideoCodec != null && mVideoTrack < 0)) return;
            mMuxer.start();
            mMuxerStarted = true;
            for (Object[] p : mPending) writeLocked((Boolean) p[0], (byte[]) p[1], (Long) p[2], (Integer) p[3]);
            mPending.clear();
        }
    }

    /** One encoded buffer. Video times are the producer's clock: brought to
     *  the recording's, without its pauses. */
    private void sinkSample(boolean video, java.nio.ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (info.size <= 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;   // the csd is in the format
        byte[] data = new byte[info.size];
        java.nio.ByteBuffer src = buf.duplicate();
        src.position(info.offset);
        src.limit(info.offset + info.size);
        src.get(data);
        int reached = 0;
        synchronized (mSinkLock) {
            if (mLimitReached) return;
            long pts = info.presentationTimeUs;
            if (video) {
                if (mVideoBaseUs == Long.MIN_VALUE) mVideoBaseUs = pts - Math.max(0, recordedUs());
                pts = Math.max(mLastVideoUs + 1, pts - mVideoBaseUs);
                mLastVideoUs = pts;
            }
            if (mAdts != null || mMuxerStarted) writeLocked(video, data, pts, info.flags);
            else mPending.add(new Object[] { video, data, pts, info.flags });
            if (mMaxDurationMs > 0 && recordedUs() >= mMaxDurationMs * 1000L) reached = MEDIA_RECORDER_INFO_MAX_DURATION_REACHED;
            else if (mMaxFileSize > 0 && mBytesWritten >= mMaxFileSize) reached = MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED;
            if (reached != 0) mLimitReached = true;   // AOSP stops recording here; the app then calls stop()
        }
        if (reached != 0) info(reached);
    }

    private void writeLocked(boolean video, byte[] data, long ptsUs, int flags) {
        try {
            if (mAdts != null) {
                // The seven-byte ADTS header: AAC-LC, this rate, these channels, this frame's length.
                int rate = mAudioSamplingRate == 48000 ? 3 : 4, channels = mAudioChannels == 2 ? 2 : 1;
                int len = data.length + 7;
                byte[] h = { (byte) 0xff, (byte) 0xf1, (byte) ((1 << 6) | (rate << 2) | (channels >> 2)),
                        (byte) (((channels & 3) << 6) | (len >> 11)), (byte) (len >> 3),
                        (byte) (((len & 7) << 5) | 0x1f), (byte) 0xfc };
                mAdts.write(h);
                mAdts.write(data);
                mBytesWritten += len;
            } else {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, data.length, ptsUs, flags);
                mMuxer.writeSampleData(video ? mVideoTrack : mAudioTrack, java.nio.ByteBuffer.wrap(data), info);
                mBytesWritten = mMuxer.macapkBytesWritten();
            }
            mWroteSample = true;
        } catch (java.io.IOException | RuntimeException e) {
            mLimitReached = true;   // a full disk: nothing more can be written
            final OnErrorListener l = mOnError;
            if (l != null) l.onError(this, MEDIA_RECORDER_ERROR_UNKNOWN, 0);
        }
    }

    /** Takes what an encoder has ready; true once it gave its end of stream. */
    private boolean drain(MediaCodec codec, boolean video, long timeoutUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (;;) {
            int i = codec.dequeueOutputBuffer(info, timeoutUs);
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { sinkFormat(video, codec.getOutputFormat()); continue; }
            if (i < 0) return false;
            java.nio.ByteBuffer b = codec.getOutputBuffer(i);
            if (b != null) sinkSample(video, b, info);
            codec.releaseOutputBuffer(i, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            timeoutUs = 0;
        }
    }

    /**
     * Starts recording: the microphone is read and encoded on one thread, the
     * video encoder's output taken on another. AOSP throws
     * IllegalStateException when start() is called out of order; a
     * microphone that cannot be opened is reported the same way.
     */
    public void start() throws IllegalStateException {
        need(mState == STATE_PREPARED, "start");
        mPeakAmplitude = 0;
        mPaused = false;
        mPausedNs = 0;
        if (mAudioCodec != null) {
            final int rate = mAudioSamplingRate == 48000 ? 48000 : 44100;
            final int channels = mAudioChannels == 2 ? 2 : 1;
            int channelConfig = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int minBuf;
            try {
                minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            } catch (Throwable t) {
                minBuf = 4096;
            }
            if (minBuf <= 0) minBuf = 4096;
            final int bufBytes = minBuf;
            AudioRecord record;
            try {
                record = new AudioRecord(mAudioSource == AudioSource.DEFAULT ? AudioSource.MIC : mAudioSource,
                        rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            } catch (Throwable t) {
                throw new IllegalStateException("Cannot open audio capture", t);
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                try { record.release(); } catch (Throwable ignore) { }
                throw new IllegalStateException("Cannot open audio capture");
            }
            mCapture = record;
            mAudioCodec.start();
            record.startRecording();
            mCapturing = true;
            mCaptureThread = new Thread(() -> captureLoop(rate, channels, bufBytes), "MediaRecorder-capture");
            mCaptureThread.setDaemon(true);
        }
        mStartNs = System.nanoTime();
        if (mVideoCodec != null) {
            mVideoCodec.start();
            final MediaCodec codec = mVideoCodec;
            mVideoDraining = true;
            mVideoThread = new Thread(() -> {
                try {
                    while (mVideoDraining && !drain(codec, true, 100000)) { }
                } catch (RuntimeException e) {
                    // The encoder failed or was released: the recording ends with what was written.
                }
            }, "MediaRecorder-video");
            mVideoThread.setDaemon(true);
            mVideoThread.start();
        }
        if (mCaptureThread != null) mCaptureThread.start();
        mState = STATE_RECORDING;
    }

    /** The microphone into the AAC encoder, with the peak for getMaxAmplitude();
     *  at the end, the encoder's last frames. */
    private void captureLoop(int rate, int channels, int bufBytes) {
        final MediaCodec codec = mAudioCodec;
        final AudioRecord record = mCapture;
        short[] buf = new short[Math.max(256, bufBytes / 2)];
        long frames = 0;
        try {
            while (mCapturing) {
                int n = record.read(buf, 0, buf.length);
                // A negative return is an AudioRecord error code; stop
                // spinning on it rather than burning a core.
                if (n < 0) break;
                if (n == 0) continue;
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    int v = buf[i] < 0 ? -buf[i] : buf[i];
                    if (v > peak) peak = v;
                }
                if (peak > mPeakAmplitude) mPeakAmplitude = peak;
                if (mPaused) continue;   // AOSP: the paused stretch is left out of the file
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    java.nio.ByteBuffer b = codec.getInputBuffer(in);
                    b.clear();
                    b.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n);
                    codec.queueInputBuffer(in, 0, n * 2, frames * 1000000L / rate, 0);
                    frames += n / channels;
                }
                drain(codec, false, 0);
            }
            int in = codec.dequeueInputBuffer(10000);
            if (in >= 0) codec.queueInputBuffer(in, 0, 0, frames * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            for (int i = 0; i < 50 && !drain(codec, false, 20000); i++) { }
        } catch (RuntimeException e) {
            // stop() released the codec under us, or the encoder failed: the
            // recording ends with what was written.
        }
    }

    /** API 24. The paused stretch is left out: the microphone's sound is
     *  dropped and the video encoder is told to drop its input. */
    public void pause() throws IllegalStateException {
        need(mState == STATE_RECORDING, "pause");
        if (mPaused) return;
        mPauseStartNs = System.nanoTime();
        mPaused = true;
        suspendVideo(true);
    }

    public void resume() throws IllegalStateException {
        need(mState == STATE_RECORDING, "resume");
        if (!mPaused) return;
        synchronized (mSinkLock) {
            long paused = System.nanoTime() - mPauseStartNs;
            mPausedNs += paused;
            if (mVideoBaseUs != Long.MIN_VALUE) mVideoBaseUs += paused / 1000;
            mPaused = false;
        }
        suspendVideo(false);
    }

    private void suspendVideo(boolean on) {
        if (mVideoCodec == null) return;
        android.os.Bundle b = new android.os.Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, on ? 1 : 0);
        mVideoCodec.setParameters(b);
    }

    /**
     * Ends the recording and completes the file. AOSP: "a RuntimeException is
     * intentionally thrown to the application, if no valid audio/video data
     * has been received when stop() is called" -- the output is then not a
     * playable file, here as there.
     */
    public void stop() throws IllegalStateException {
        need(mState == STATE_RECORDING, "stop");
        mCapturing = false;
        join(mCaptureThread);
        if (mVideoCodec != null) {
            try { mVideoCodec.signalEndOfInputStream(); } catch (IllegalStateException ignore) { }
            join(mVideoThread);
        }
        boolean ok;
        synchronized (mSinkLock) {
            ok = mWroteSample;
            try {
                if (mMuxer != null && mMuxerStarted) mMuxer.stop();
                else if (mMuxer != null) ok = false;   // a track never produced a sample: there is no file to complete
                if (mAdts != null) mAdts.flush();
            } catch (java.io.IOException | RuntimeException e) {
                ok = false;
            }
        }
        closeRecording();
        mState = STATE_INITIAL;
        // AOSP switches to the queued next output file at stop() when one was set.
        if (mNextOutputPath != null) {
            mOutputPath = mNextOutputPath;
            mNextOutputPath = null;
        }
        if (!ok) throw new RuntimeException("stop failed.");
    }

    private static void join(Thread t) {
        if (t == null || t == Thread.currentThread()) return;
        try { t.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** Lets go of the microphone, the encoders and the file. */
    private void closeRecording() {
        mCapturing = false;
        mVideoDraining = false;
        join(mCaptureThread);
        mCaptureThread = null;
        AudioRecord record = mCapture;
        mCapture = null;
        if (record != null) {
            try { record.stop(); } catch (Throwable ignore) { }
            try { record.release(); } catch (Throwable ignore) { }
        }
        for (MediaCodec c : new MediaCodec[] { mAudioCodec, mVideoCodec }) {
            if (c != null) {
                try { c.release(); } catch (Throwable ignore) { }
            }
        }
        join(mVideoThread);
        mVideoThread = null;
        mAudioCodec = mVideoCodec = null;
        mSurface = null;
        synchronized (mSinkLock) {
            if (mMuxer != null) {
                try { mMuxer.release(); } catch (Throwable ignore) { }
                mMuxer = null;
            }
            if (mAdts != null) {
                // A FileDescriptor is the app's to close; a path's stream is ours.
                try { if (mOutputFd != null) mAdts.flush(); else mAdts.close(); } catch (java.io.IOException ignore) { }
                mAdts = null;
            }
            mPending.clear();
            mAudioTrack = mVideoTrack = -1;
            mMuxerStarted = mLimitReached = mWroteSample = false;
            mBytesWritten = 0;
            mVideoBaseUs = Long.MIN_VALUE;
            mLastVideoUs = -1;
        }
        mPaused = false;
    }

    public void reset() {
        closeRecording();
        mOutputPath = null; mOutputFd = null; mNextOutputPath = null;
        mAudioSource = mVideoSource = -1;
        mOutputFormat = OutputFormat.DEFAULT;
        mAudioEncoder = AudioEncoder.DEFAULT; mVideoEncoder = VideoEncoder.DEFAULT;
        mAudioSamplingRate = 0; mAudioChannels = 0; mAudioBitRate = 0;
        mVideoWidth = 1280; mVideoHeight = 720; mVideoFrameRate = 30; mVideoBitRate = 0; mVideoProfile = 0;
        mOrientation = 0; mMaxDurationMs = 0; mMaxFileSize = 0; mHasLocation = false;
        mPersistentSurface = null;
        mPeakAmplitude = 0;
        mState = STATE_INITIAL;
    }

    public void release() {
        closeRecording();
        mOutputPath = null; mOutputFd = null; mNextOutputPath = null;
        mState = STATE_RELEASED;
    }

    /**
     * AOSP: "the maximum absolute amplitude ... since the last call", 0 the
     * first time. This is a REAL measurement now: the drain loop above tracks
     * the peak 16-bit sample the microphone delivered, so a hold-to-record level
     * meter or a voice-visualiser -- the commonest use of MediaRecorder outside
     * actually keeping the file -- works correctly on this host.
     */
    public int getMaxAmplitude() throws IllegalStateException {
        int peak = mPeakAmplitude;
        mPeakAmplitude = 0;
        return peak;
    }
    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String AUDIO_BITRATE = "android.media.mediarecorder.audio-bitrate";
        public static final String AUDIO_CHANNELS = "android.media.mediarecorder.audio-channels";
        public static final String AUDIO_SAMPLERATE = "android.media.mediarecorder.audio-samplerate";
        public static final String AUDIO_TIMESCALE = "android.media.mediarecorder.audio-timescale";
        public static final String CAPTURE_FPS = "android.media.mediarecorder.capture-fps";
        public static final String CAPTURE_FPS_ENABLE = "android.media.mediarecorder.capture-fpsenable";
        public static final String FRAMERATE = "android.media.mediarecorder.frame-rate";
        public static final String HEIGHT = "android.media.mediarecorder.height";
        public static final String MOVIE_TIMESCALE = "android.media.mediarecorder.movie-timescale";
        public static final String ROTATION = "android.media.mediarecorder.rotation";
        public static final String VIDEO_BITRATE = "android.media.mediarecorder.video-bitrate";
        public static final String VIDEO_IFRAME_INTERVAL =
                "android.media.mediarecorder.video-iframe-interval";
        public static final String VIDEO_LEVEL = "android.media.mediarecorder.video-encoder-level";
        public static final String VIDEO_PROFILE =
                "android.media.mediarecorder.video-encoder-profile";
        public static final String VIDEO_TIMESCALE = "android.media.mediarecorder.video-timescale";
        public static final String WIDTH = "android.media.mediarecorder.width";
    }

    /**
     * AOSP: "@return the metrics recorded ... or null if no metrics are
     * available". Nothing is encoded here (see the header), so there are no
     * encoder metrics to report and null is the truthful answer rather than a
     * bundle of zeros that reads as a measurement.
     */
    public android.os.PersistableBundle getMetrics() { return null; }
    /** AOSP MediaRecorder.getActiveRecordingConfiguration() returns an
     *  `AudioRecordingConfiguration`, not an int -- this declared the wrong
     *  RETURN TYPE, so app bytecode compiled against the real API could never
     *  link to it at all (a different method descriptor entirely, the same
     *  class of defect as the old Intent.resolveActivity signature).
     *  Found by debug/framework_lint.py, which diffs our shapes against the
     *  android-30 stub jar.
     *
     *  Null is AOSP's own answer when the recorder is not actively recording;
     *  we have no MediaRecorder capture path, so it never is. */
    public AudioRecordingConfiguration getActiveRecordingConfiguration() {
        // No longer permanently null: while start() has a live AudioRecord, the
        // configuration comes from the same registry AudioManager reads, so the
        // two can never disagree about what this recorder is doing.
        AudioRecord capture = mCapture;
        return (capture == null) ? null : capture.getActiveRecordingConfiguration();
    }

    public void setOnErrorListener(OnErrorListener l) { mOnError = l; }
    public void setOnInfoListener(OnInfoListener l) { mOnInfo = l; }

    public interface OnErrorListener { void onError(MediaRecorder mr, int what, int extra); }
    public interface OnInfoListener  { void onInfo(MediaRecorder mr, int what, int extra); }

    /** The legacy Camera as the video source. Accepted, as AOSP accepts it
     *  here; prepare() is where VideoSource.CAMERA is refused (see there). */
    public void setCamera(android.hardware.Camera c) { }

    // =====================================================================
    // API-gap close-out. Signatures from /tmp/aosp_api/android16.txt; behaviour
    // from frameworks/base/media/java/android/media/MediaRecorder.java
    // (android16-release).
    // =====================================================================

    /** AOSP: the highest legal AudioSource value. Derived from the enum block
     *  above so the two can never drift. */
    public static final int getAudioSourceMax() { return AudioSource.VOICE_PERFORMANCE; }

    /** API 26. AOSP switches to this file when the current one hits its size or
     *  duration limit, and at stop(). Honoured at stop() here (see stop()); the
     *  mid-recording rollover cannot happen because no bytes are ever written,
     *  so no limit is ever reached. */
    public void setNextOutputFile(java.io.File file) throws java.io.IOException {
        java.util.Objects.requireNonNull(file, "file must not be null");
        mNextOutputPath = file.getPath();
    }

    public void setNextOutputFile(java.io.FileDescriptor fd) throws java.io.IOException {
        java.util.Objects.requireNonNull(fd, "fd must not be null");
        // A file descriptor carries no path to hand over at stop(); AOSP dups it
        // natively. Accepted, and recorded as "no next PATH" so stop() does not
        // silently switch to the wrong file.
        mNextOutputPath = null;
    }

    /** API 31. Configures the audio encoder from a CamcorderProfile-derived
     *  profile. There is no encoder (see the header), so the codec/bitrate half
     *  cannot be honoured -- but the sampling rate and channel count ARE real
     *  capture parameters this class already uses, so they are applied rather
     *  than dropped. */
    public void setAudioProfile(EncoderProfiles.AudioProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile must not be null");
        if (profile.getSampleRate() > 0) setAudioSamplingRate(profile.getSampleRate());
        if (profile.getChannels() > 0) setAudioChannels(profile.getChannels());
    }

    /** API 31. The video half has no capture path at all (no camera), so this
     *  is accepted and has nothing to apply. */
    public void setVideoProfile(EncoderProfiles.VideoProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile must not be null");
        if (profile.getWidth() > 0 && profile.getHeight() > 0) {
            mVideoWidth = profile.getWidth();
            mVideoHeight = profile.getHeight();
        }
        if (profile.getFrameRate() > 0) mVideoFrameRate = profile.getFrameRate();
        if (profile.getBitrate() > 0) mVideoBitRate = profile.getBitrate();
    }

    /** API 26. Selects an encoder profile/level. No encoder exists, so there is
     *  nothing to configure; AOSP validates the arguments first and so does
     *  this -- a caller passing a bogus level still learns about it. */
    public void setVideoEncodingProfileLevel(int profile, int level) {
        if (profile <= 0) {
            throw new IllegalArgumentException("Video encoding profile is not positive");
        }
        if (level <= 0) {
            throw new IllegalArgumentException("Video encoding level is not positive");
        }
        mVideoProfile = profile;   // the level follows from the size and the frame rate
    }

    // ---- privacy-sensitive capture (API 30) ------------------------------

    /** AOSP throws IllegalStateException when called after prepare(). The flag
     *  prevents a CONCURRENT capture from sharing the input; Mac.apk runs one
     *  app per process, so there is no concurrent capture and the guarantee
     *  holds trivially -- the value is still recorded so the getter agrees. */
    public void setPrivacySensitive(boolean privacySensitive) {
        mPrivacySensitive = privacySensitive;
        mPrivacySensitiveExplicit = true;
    }

    /** AOSP's default depends on the source: true for VOICE_COMMUNICATION and
     *  CAMCORDER, false otherwise. Derived, not fixed. */
    public boolean isPrivacySensitive() {
        if (mPrivacySensitiveExplicit) return mPrivacySensitive;
        return mAudioSource == AudioSource.VOICE_COMMUNICATION
                || mAudioSource == AudioSource.CAMCORDER;
    }

    // ---- AudioRouting -----------------------------------------------------

    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mRoutingListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override public AudioDeviceInfo getPreferredDevice() { return mPreferredDevice; }

    /** AOSP returns false for a device that is not an audio INPUT. */
    @Override public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo == null) { mPreferredDevice = null; return true; }
        if (!deviceInfo.isSource()) return false;
        mPreferredDevice = deviceInfo;
        return true;
    }

    /** AOSP: null when not recording; the real capture device otherwise. */
    @Override public AudioDeviceInfo getRoutedDevice() {
        AudioRecord capture = mCapture;
        return (capture == null) ? null : capture.getRoutedDevice();
    }

    @Override public void addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener listener, android.os.Handler handler) {
        if (listener == null) return;
        for (Object[] entry : mRoutingListeners) {
            if (entry[0] == listener) return;
        }
        mRoutingListeners.add(new Object[] { listener, handler });
    }

    @Override public void removeOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener listener) {
        for (Object[] entry : mRoutingListeners) {
            if (entry[0] == listener) { mRoutingListeners.remove(entry); return; }
        }
    }

    /** @deprecated AOSP's pre-API-24 overload; its listener type extends the
     *  modern one, so it delegates. */
    @Deprecated
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener,
            android.os.Handler handler) {
        addOnRoutingChangedListener((AudioRouting.OnRoutingChangedListener) listener, handler);
    }

    @Deprecated
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        removeOnRoutingChangedListener((AudioRouting.OnRoutingChangedListener) listener);
    }

    /** @deprecated in AOSP since API 24. */
    @Deprecated
    public interface OnRoutingChangedListener extends AudioRouting.OnRoutingChangedListener {
        void onRoutingChanged(MediaRecorder mr);
        @Override default void onRoutingChanged(AudioRouting router) {
            if (router instanceof MediaRecorder) onRoutingChanged((MediaRecorder) router);
        }
    }

    // ---- AudioRecordingMonitor --------------------------------------------

    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mRecordingCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** AOSP throws IllegalArgumentException on a null executor or callback. The
     *  capture path never changes underneath a running recorder on this backend,
     *  so a registered callback is retained and never invoked -- the same as on
     *  a device whose capture path is stable. */
    @Override public void registerAudioRecordingCallback(java.util.concurrent.Executor executor,
            AudioManager.AudioRecordingCallback cb) {
        if (executor == null) throw new IllegalArgumentException("Illegal null Executor");
        if (cb == null) throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
        for (Object[] entry : mRecordingCallbacks) {
            if (entry[1] == cb) return;
        }
        mRecordingCallbacks.add(new Object[] { executor, cb });
    }

    @Override public void unregisterAudioRecordingCallback(AudioManager.AudioRecordingCallback cb) {
        if (cb == null) throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
        for (Object[] entry : mRecordingCallbacks) {
            if (entry[1] == cb) { mRecordingCallbacks.remove(entry); return; }
        }
    }

    /** AOSP: the microphones actively feeding THIS recorder, throwing
     *  IOException if the query fails. Delegated to the live AudioRecord so the
     *  two never disagree; empty while not recording. */
    public java.util.List<MicrophoneInfo> getActiveMicrophones() throws java.io.IOException {
        AudioRecord capture = mCapture;
        if (capture == null) return new java.util.ArrayList<MicrophoneInfo>();
        return capture.getActiveMicrophones();
    }

    // ---- MicrophoneDirection (API 29) -------------------------------------
    // Delegated to AudioRecord, which reports false: steering needs a microphone
    // ARRAY the HAL can beam-form with, and CoreAudio hands this host one plain
    // default input. AOSP's contract is that these return false on hardware that
    // cannot honour the preference.

    @Override public boolean setPreferredMicrophoneDirection(int direction) {
        if (direction < MIC_DIRECTION_UNSPECIFIED || direction > MIC_DIRECTION_EXTERNAL) {
            throw new IllegalArgumentException("Invalid direction " + direction);
        }
        return false;
    }

    @Override public boolean setPreferredMicrophoneFieldDimension(float zoom) {
        if (zoom < -1.0f || zoom > 1.0f) {
            throw new IllegalArgumentException("Argument must fall between -1 & 1 (inclusive)");
        }
        return false;
    }

    // ---- log session -------------------------------------------------------

    private android.media.metrics.LogSessionId mLogSessionId =
            android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;

    /** @NonNull in AOSP; LOG_SESSION_ID_NONE until the app sets one. */
    public android.media.metrics.LogSessionId getLogSessionId() { return mLogSessionId; }

    public void setLogSessionId(android.media.metrics.LogSessionId id) {
        mLogSessionId = java.util.Objects.requireNonNull(id, "id must not be null");
    }

    /** AOSP declares finalize() so a dropped recorder releases the microphone
     *  instead of leaving the input open. release() is safe on any state. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}

