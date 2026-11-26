package android.media;

// android.media.AudioTrack -- real streaming PCM output.
//
// Backed by the macOS CoreAudio engine (runtime/src/audio_backend.mm) via
// com.macapk.loader.NativeAudio. Each AudioTrack owns one output stream
// (an AVAudioSourceNode + ring buffer) feeding the shared mixer. write()
// pushes PCM into the ring with realtime backpressure, so a MODE_STREAM
// writer is paced to the device the way real Android paces it.
//
// Observable behavior mirrored from AOSP frameworks/base
// media/java/android/media/AudioTrack.java: getState()=STATE_INITIALIZED
// once constructed, getPlayState() reflects play/pause/stop, write()
// returns the number of bytes/shorts/floats consumed.
public class AudioTrack implements AudioRouting, VolumeAutomation {
    // MACAPK_MEDIA_PROBE (com.macapk.loader.MediaStats): write counters for the
    // owner-issue checker; null (every hook a no-op) unless the probe is on.
    private final com.macapk.loader.MediaStats.Track mStat = com.macapk.loader.MediaStats.newTrack();
    public static final int MODE_STATIC = 0;
    public static final int MODE_STREAM = 1;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_UNINITIALIZED = 0;
    public static final int PLAYSTATE_STOPPED = 1;
    public static final int PLAYSTATE_PAUSED  = 2;
    public static final int PLAYSTATE_PLAYING = 3;
    public static final int WRITE_BLOCKING = 0;
    public static final int WRITE_NON_BLOCKING = 1;

    // ---- A track whose output cannot open says so (MACAPK_AUDIO_HONEST_OPEN) --
    // Default ON. handle == 0 means the native track was never created: the
    // runtime's macapk_audio_stream_create() returns 0 when the host output
    // cannot open (audio_backend.mm, same gate). getState() already answers
    // STATE_UNINITIALIZED for that (v1.0.941); with this gate the methods
    // below answer the way AOSP's do for a track whose native_setup failed
    // (frameworks/base media/java/android/media/AudioTrack.java, main):
    // Builder.build(), play()/pause()/stop(), the 4 write() overloads,
    // setStereoVolume/setVolume, setNotificationMarkerPosition,
    // setPositionNotificationPeriod, attachAuxEffect and
    // setAuxEffectSendLevel -- see honestUninitialized()'s call sites.
    //   Builder.build(): "if (track.getState() == STATE_UNINITIALIZED) {
    //       // release is not necessary
    //       throw new UnsupportedOperationException("Cannot create AudioTrack"); }"
    //   play()/stop()/pause(): "if (mState != STATE_INITIALIZED) { throw new
    //       IllegalStateException("play() called on uninitialized AudioTrack."); }"
    //   every write(): "if (mState == STATE_UNINITIALIZED ...) { return
    //       ERROR_INVALID_OPERATION; }" -- checked FIRST, before the arguments.
    //   the 6 setters/attachers above: "if (mState == STATE_UNINITIALIZED)
    //       { return ERROR_INVALID_OPERATION; }" first (setStereoVolume
    //       :2714-2717, setVolume delegates to it at :2746-2747,
    //       setNotificationMarkerPosition :2828-2831,
    //       setPositionNotificationPeriod :2842-2845, attachAuxEffect
    //       :3707-3710, setAuxEffectSendLevel :3732-3735). Not yet covered
    //       (OPEN QUESTIONS in docs/audio-honest-open_2026-09-28.md):
    //       getMinBufferSize / getNativeOutputSampleRate.
    // =0 keeps the old answers byte for byte (a Builder that returns the dead
    // track, play/stop/pause as silent no-ops, writes returning
    // ERROR_DEAD_OBJECT / sizeInBytes). Gate: debug/audio_honest_open_gate.sh.
    private static final boolean HONEST_OPEN =
            !"0".equals(System.getenv("MACAPK_AUDIO_HONEST_OPEN"));
    /** True when this track has no native side and must answer like AOSP's
     *  STATE_UNINITIALIZED track (see HONEST_OPEN). */
    private boolean honestUninitialized() { return HONEST_OPEN && handle == 0; }

    private final long handle;
    private final int sampleRate;
    private final int channels;
    private final int encoding;
    // AOSP separates the buffer's CAPACITY (fixed once the track is created)
    // from the effective SIZE the app is allowed to fill, which
    // setBufferSizeInFrames lowers to trade glitch-resistance for latency.
    // Keeping them as two fields is what lets getBufferCapacityInFrames and
    // getBufferSizeInFrames disagree the way they do on a real device.
    private final int bufferSizeInFrames;
    private int effectiveBufferInFrames;
    // The native track's frameCount: bufferSizeInFrames raised to the
    // platform minimum (see the constructor). bufferSizeInFrames stays the
    // app's request, which is what bounds a MODE_STATIC buffer.
    private int capacityInFrames;
    // AOSP's mDataLoadMode. Recorded because setPlaybackHeadPosition's very
    // first branch is `if (mDataLoadMode == MODE_STREAM || ...) return
    // ERROR_INVALID_OPERATION` -- without the field there is nothing to be
    // right about, which is why that method used to return a bare SUCCESS.
    // The three public constructors set it after delegating; the private core
    // constructor has no mode parameter, so MODE_STREAM is the default (and is
    // what every constructor that omits a mode means).
    private int dataLoadMode = MODE_STREAM;

    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig,
                       int audioFormat, int bufferSizeInBytes, int mode) {
        this(sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes);
        com.macapk.loader.AudioTrace.hit("AudioTrack.<init>",
            "sr=" + sampleRateInHz + " chCfg=" + channelConfig
            + " fmt=" + audioFormat + " buf=" + bufferSizeInBytes
            + " mode=" + mode + " -> h=" + handle + " ch=" + channels);
        this.dataLoadMode = mode;
    }

    // AOSP's deprecated session-attaching legacy constructor (frameworks/base
    // media/java/android/media/AudioTrack.java). AOSP itself delegates through
    // the AudioAttributes/AudioFormat + sessionId constructor rather than
    // duplicating construction logic; we mirror that exact delegation target
    // (not the simpler 6-int ctor above, which has no sessionId parameter to
    // thread through) so this stays a thin adapter.
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig,
                       int audioFormat, int bufferSizeInBytes, int mode, int sessionId) {
        this(new AudioAttributes.Builder().setLegacyStreamType(streamType).build(),
             new AudioFormat.Builder()
                 .setChannelMask(channelConfig)
                 .setEncoding(audioFormat)
                 .setSampleRate(sampleRateInHz)
                 .build(),
             bufferSizeInBytes, mode, sessionId);
        com.macapk.loader.AudioTrace.hit("AudioTrack.<init>(legacy+session)",
            "sr=" + sampleRateInHz + " chCfg=" + channelConfig
            + " fmt=" + audioFormat + " buf=" + bufferSizeInBytes
            + " mode=" + mode + " session=" + sessionId
            + " -> h=" + handle + " ch=" + channels);
        this.dataLoadMode = mode;
    }

    public AudioTrack(AudioAttributes attrs, AudioFormat format,
                       int bufferSizeInBytes, int mode, int sessionId) {
        this(format != null ? format.getSampleRate() : 44100,
             format != null ? format.getChannelMask() : AudioFormat.CHANNEL_OUT_STEREO,
             format != null ? format.getEncoding() : AudioFormat.ENCODING_PCM_16BIT,
             bufferSizeInBytes);
        com.macapk.loader.AudioTrace.hit("AudioTrack.<init>(builder)",
            "buf=" + bufferSizeInBytes + " mode=" + mode
            + " -> h=" + handle + " sr=" + sampleRate + " ch=" + channels);
        this.dataLoadMode = mode;
    }

    private AudioTrack(int sampleRateInHz, int channelConfig,
                       int audioFormat, int bufferSizeInBytes) {
        this.sampleRate = sampleRateInHz > 0 ? sampleRateInHz : 44100;
        this.channels   = channelsFromConfig(channelConfig);
        this.encoding   = normalizeEncoding(audioFormat);
        int bpf = channels * bytesPerSample(encoding);
        int bufFrames = bpf > 0 ? Math.max(1, bufferSizeInBytes / bpf) : 1024;
        this.bufferSizeInFrames = bufFrames;
        this.effectiveBufferInFrames = bufFrames;
        long h = 0;
        try {
            h = com.macapk.loader.NativeAudio.nativeCreate(
                    this.sampleRate, this.channels, this.encoding, bufFrames);
        } catch (Throwable t) {
            // Backend unavailable: fall back to silent-but-non-blocking.
            h = 0;
        }
        this.handle = h;
        // AOSP: the track's frameCount is the request raised to the platform
        // minimum (AudioFlinger PlaybackThread::createTrack_l), and both
        // getBufferCapacityInFrames and the initial getBufferSizeInFrames
        // report it. The native side owns that answer; an older backend (or a
        // test double) without the method leaves the request as the answer.
        int cap = bufFrames;
        if (h != 0) {
            try {
                int c = com.macapk.loader.NativeAudio.nativeGetBufferCapacity(h);
                if (c > 0) cap = c;
            } catch (Throwable t) {
                // keep the request
            }
        }
        this.capacityInFrames = cap;
        this.effectiveBufferInFrames = cap;
    }

    private static int normalizeEncoding(int fmt) {
        switch (fmt) {
            case AudioFormat.ENCODING_PCM_8BIT:  return AudioFormat.ENCODING_PCM_8BIT;
            case AudioFormat.ENCODING_PCM_FLOAT: return AudioFormat.ENCODING_PCM_FLOAT;
            case AudioFormat.ENCODING_PCM_16BIT: return AudioFormat.ENCODING_PCM_16BIT;
            default:                             return AudioFormat.ENCODING_PCM_16BIT;
        }
    }

    private static int bytesPerSample(int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_PCM_8BIT:  return 1;
            case AudioFormat.ENCODING_PCM_FLOAT: return 4;
            default:                             return 2;  // 16-bit
        }
    }

    // Map both the modern CHANNEL_OUT_* bitmask scheme and the deprecated
    // CHANNEL_CONFIGURATION_* scheme to a channel count (clamped 1..2).
    private static int channelsFromConfig(int cfg) {
        switch (cfg) {
            case 2:    return 1;  // CHANNEL_CONFIGURATION_MONO
            case 3:    return 2;  // CHANNEL_CONFIGURATION_STEREO
            case 0x4:  return 1;  // CHANNEL_OUT_MONO
            case 0xc:  return 2;  // CHANNEL_OUT_STEREO
            case 1:    return 2;  // CHANNEL_OUT_DEFAULT -> stereo
            case 0:    return 2;  // CHANNEL_INVALID -> default stereo
            default:
                int c = Integer.bitCount(cfg & 0x3FFFFF);  // CHANNEL_OUT_* bits
                if (c < 1) c = 1;
                if (c > 2) c = 2;
                return c;
        }
    }

    /** ⚠ v1.0.941: reports STATE_UNINITIALIZED when no native track exists.
     *
     *  This returned STATE_INITIALIZED UNCONDITIONALLY, even with handle == 0.
     *  That is the same inverted contradiction the owner's camera/mic work
     *  retired on the INPUT side this same day: AudioRecord claimed
     *  STATE_INITIALIZED while delivering pure silence. Here the output track
     *  claimed to be initialised while writing into nothing.
     *
     *  Combined with write()'s deviceless fallback returning sizeInBytes (see
     *  there), an app could not detect a dead output path THROUGH THE API AT
     *  ALL -- every observable said success. AOSP returns STATE_UNINITIALIZED
     *  when native track creation fails, and apps branch on it. */
    public int getState()           { return handle != 0 ? STATE_INITIALIZED : STATE_UNINITIALIZED; }
    public int getPlayState()       {
        if (handle == 0) return PLAYSTATE_STOPPED;
        return com.macapk.loader.NativeAudio.nativeGetPlayState(handle);
    }
    public int getSampleRate()      { return sampleRate; }
    public int getChannelCount()    { return channels; }
    public int getAudioFormat()     { return encoding; }
    // The fill threshold: the native frameCount at construction, then
    // whatever setBufferSizeInFrames adopted. AOSP's getBufferSizeInFrames()
    // calls through to a native query that can reflect a post-creation route
    // adjustment; Mac.apk has no HAL that resizes the buffer behind the app's
    // back, so the last adopted value is always the current value.
    public int getBufferSizeInFrames() { return effectiveBufferInFrames; }

    /**
     * The maximum size of the track's buffer in frames. API 24. AOSP:
     * `return native_get_buffer_capacity_frames();`
     * (frameworks/base/media/java/android/media/AudioTrack.java, main, L2302).
     *
     * A REAL answer. AOSP documents the capacity as ">= the bufferSizeInBytes
     * given at construction, converted to frames" for a streaming track: the
     * request raised to the platform minimum, which is what the native track
     * reports (see the constructor). Our backend never enlarges the buffer
     * after creation, so that frame count IS the capacity.
     */
    public int getBufferCapacityInFrames() { return capacityInFrames; }

    /**
     * Limit the effective buffer size the app may fill. API 24. AOSP
     * (same file, L2273-2281) returns ERROR_INVALID_OPERATION for a MODE_STATIC
     * or uninitialized track, ERROR_BAD_VALUE for a negative request, and
     * otherwise the ACTUAL size adopted, which may differ from the request and
     * is capped at getBufferCapacityInFrames().
     *
     * Implemented for real: the native track lowers the amount a blocking
     * write may queue ahead of the speaker, so an app that shrinks its buffer
     * to cut latency gets lower latency, and getBufferSizeInFrames reports the
     * size that was adopted.
     */
    public int setBufferSizeInFrames(int bufferSizeInFrames) {
        if (handle == 0) {
            return ERROR_INVALID_OPERATION;
        }
        if (bufferSizeInFrames < 0) {
            return ERROR_BAD_VALUE;
        }
        // AOSP: "return native_set_buffer_size_frames(bufferSizeInFrames);",
        // which clips to [16, frameCount] (ClientProxy::setBufferSizeInFrames)
        // and lowers the fill threshold blocking writes wait on, so a smaller
        // size really is lower latency. The native side does both.
        int adopted = Math.min(bufferSizeInFrames, getBufferCapacityInFrames());
        try {
            adopted = com.macapk.loader.NativeAudio.nativeSetBufferSize(handle, bufferSizeInFrames);
        } catch (Throwable t) {
            // older backend: report the clipped size only
        }
        this.effectiveBufferInFrames = adopted;
        return this.effectiveBufferInFrames;
    }

    public void play()  { com.macapk.loader.AudioTrace.hit("AudioTrack.play");
        if (honestUninitialized())
            throw new IllegalStateException("play() called on uninitialized AudioTrack.");
        if (handle != 0) com.macapk.loader.NativeAudio.nativePlay(handle); }
    public void pause() { com.macapk.loader.AudioTrace.hit("AudioTrack.pause");
        if (honestUninitialized())
            throw new IllegalStateException("pause() called on uninitialized AudioTrack.");
        if (handle != 0) com.macapk.loader.NativeAudio.nativePause(handle); }
    public void stop()  { com.macapk.loader.AudioTrace.hit("AudioTrack.stop");
        if (honestUninitialized())
            throw new IllegalStateException("stop() called on uninitialized AudioTrack.");
        if (handle != 0) com.macapk.loader.NativeAudio.nativeStop(handle); }
    public void flush() { com.macapk.loader.AudioTrace.hit("AudioTrack.flush"); if (handle != 0) com.macapk.loader.NativeAudio.nativeFlush(handle); }
    public void release() {
        com.macapk.loader.AudioTrace.hit("AudioTrack.release");
        // Stop the position watcher first, or an app that churns through tracks
        // leaks one daemon thread per released track.
        mReleased = true;
        Thread w = mPosWatcher;
        if (w != null) w.interrupt();
        if (handle != 0) com.macapk.loader.NativeAudio.nativeRelease(handle);
    }

    // ⚠ v1.0.941: a deviceless write reports ERROR_DEAD_OBJECT, not "consumed".
    //
    // These used to `return sizeInBytes` when handle == 0, so a track with no
    // native backing swallowed every buffer and reported full success. An app
    // feeding a decoder into a dead track saw a perfect write rate, no error,
    // and no sound, with nothing in the API to point at. AOSP's own answer for
    // a track whose native side is gone is ERROR_DEAD_OBJECT, and callers
    // handle it (ExoPlayer's DefaultAudioSink treats it as a sink reset).
    //
    // A null buffer or a non-positive size is still a caller error, not a dead
    // track, and stays ERROR_BAD_VALUE / 0 as AOSP has it.
    //
    // ⚠ v1.0.989: the 4-arg overloads used to silently DISCARD `writeMode` and
    // delegate straight to the 3-arg (always-blocking) overload -- so a caller
    // that explicitly asked for WRITE_NON_BLOCKING got a blocking write anyway.
    // AOSP's own contract for WRITE_NON_BLOCKING is unambiguous: "the write
    // will return immediately after queuing as much audio data for playback as
    // possible without blocking" (verified against the real AudioTrack.java
    // javadoc for both the byte[] and ByteBuffer overloads). That silent
    // downgrade is exactly what was starving media3's AudioTrackAudioOutput:
    // it calls audioTrack.write(buffer, n, AudioTrack.WRITE_NON_BLOCKING) from
    // its own single-threaded internal playback loop specifically so that
    // loop never blocks; forcing it to block anyway (for up to the 5 s stall
    // cap in audio_backend.mm whenever the ring was ahead of real playback,
    // e.g. still pre-buffering before play()) froze that ENTIRE loop --
    // including the position-refresh self-reschedule
    // (ExoPlayerImplInternal.doSomeWork()) that would otherwise have kept
    // calling getPlaybackHeadPosition()/getTimestamp() and made progress
    // visible. Measured directly on a live PlayTube run with both this write
    // path and getPlaybackHeadPosition()/getTimestamp() traced: "[audio]
    // write stall cap hit, dropping 800 elems" fired, immediately followed by
    // the first play() call, and getPlaybackHeadPosition()/getTimestamp() were
    // never called even once in the whole run -- the loop that would call them
    // never got that far. Now every overload threads `writeMode` down to the
    // native ring push, matching AOSP's own native_write_byte(..., boolean
    // blocking) shape (frameworks/base AudioTrack.java): non-blocking queues
    // whatever fits right now and returns immediately, exactly like a real
    // device. The plain (3-arg) overloads now delegate to the 4-arg ones with
    // WRITE_BLOCKING, mirroring AOSP's own delegation
    // ("return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING)")
    // instead of duplicating the body.
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
    }
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        com.macapk.loader.AudioTrace.hit("AudioTrack.write(byte[])", "n=" + sizeInBytes);
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        if (audioData == null) return ERROR_BAD_VALUE;
        if (sizeInBytes <= 0) return 0;
        if (handle == 0) return ERROR_DEAD_OBJECT;
        int w = com.macapk.loader.NativeAudio.nativeWriteBytes(
            handle, audioData, offsetInBytes, sizeInBytes, writeMode == WRITE_BLOCKING);
        com.macapk.loader.MediaStats.trackWrite(mStat, sizeInBytes, w);
        return w;
    }
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
        return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
    }
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts, int writeMode) {
        com.macapk.loader.AudioTrace.hit("AudioTrack.write(short[])", "n=" + sizeInShorts);
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        if (audioData == null) return ERROR_BAD_VALUE;
        if (sizeInShorts <= 0) return 0;
        if (handle == 0) return ERROR_DEAD_OBJECT;   // see write(byte[])
        int w = com.macapk.loader.NativeAudio.nativeWriteShorts(
            handle, audioData, offsetInShorts, sizeInShorts, writeMode == WRITE_BLOCKING);
        com.macapk.loader.MediaStats.trackWrite(mStat, sizeInShorts * 2, w > 0 ? w * 2 : w);
        return w;
    }
    public int write(float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode) {
        com.macapk.loader.AudioTrace.hit("AudioTrack.write(float[])", "n=" + sizeInFloats);
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        if (audioData == null) return ERROR_BAD_VALUE;
        if (sizeInFloats <= 0) return 0;
        if (handle == 0) return ERROR_DEAD_OBJECT;   // see write(byte[])
        int w = com.macapk.loader.NativeAudio.nativeWriteFloats(
            handle, audioData, offsetInFloats, sizeInFloats, writeMode == WRITE_BLOCKING);
        com.macapk.loader.MediaStats.trackWrite(mStat, sizeInFloats * 4, w > 0 ? w * 4 : w);
        return w;
    }
    public int write(java.nio.ByteBuffer audioData, int sizeInBytes, int writeMode) {
        com.macapk.loader.AudioTrace.hit("AudioTrack.write(ByteBuffer)", "n=" + sizeInBytes);
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        if (handle == 0 || audioData == null || sizeInBytes <= 0) return sizeInBytes;
        byte[] tmp;
        int off;
        if (audioData.hasArray()) {
            tmp = audioData.array();
            off = audioData.arrayOffset() + audioData.position();
        } else {
            tmp = new byte[sizeInBytes];
            int pos = audioData.position();
            audioData.get(tmp, 0, sizeInBytes);
            audioData.position(pos);  // restore; we advance explicitly below
            off = 0;
        }
        int w = com.macapk.loader.NativeAudio.nativeWriteBytes(
            handle, tmp, off, sizeInBytes, writeMode == WRITE_BLOCKING);
        com.macapk.loader.MediaStats.trackWrite(mStat, sizeInBytes, w);
        audioData.position(audioData.position() + w);
        return w;
    }
    // AOSP's HW_AV_SYNC presentation-timestamp overload. Real AOSP only honors
    // `timestamp` when the track's AudioAttributes carry FLAG_HW_AV_SYNC; for
    // any other track it logs and degrades to the plain write (verbatim from
    // AudioTrack.java's write(ByteBuffer,int,int,long): "Ignoring pts..." then
    // return write(audioData, sizeInBytes, writeMode)). Mac.apk has no hardware
    // audio-DSP passthrough path (CoreAudio via AVAudioSourceNode is a software
    // mixer), so every track here takes that same degrade branch.
    public int write(java.nio.ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        return write(audioData, sizeInBytes, writeMode);
    }

    // The app-set volume is kept separate from the VolumeShaper scale so the two
    // compose the way they do on Android (the shaper is a multiplier applied in
    // the mixer on top of whatever the app set), instead of one clobbering the
    // other.
    private volatile float mBaseVolL = 1.0f;
    private volatile float mBaseVolR = 1.0f;
    private volatile float mShaperScale = 1.0f;

    private void pushVolume() {
        if (handle != 0) {
            com.macapk.loader.NativeAudio.nativeSetVolume(handle,
                    mBaseVolL * mShaperScale, mBaseVolR * mShaperScale);
        }
    }

    /** @deprecated in AOSP since API 21; superseded by setVolume(float). */
    @Deprecated
    public int setStereoVolume(float left, float right) {
        // AOSP (frameworks/base main, AudioTrack.java:2714-2717): "if (mState
        // == STATE_UNINITIALIZED) { return ERROR_INVALID_OPERATION; }" before
        // touching the volume state at all (see HONEST_OPEN above).
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        mBaseVolL = left; mBaseVolR = right;
        pushVolume();
        return SUCCESS;
    }
    public int setVolume(float gain) {
        // AOSP's setVolume(float) is "return setStereoVolume(gain, gain);"
        // (:2746-2747), so it inherits the same STATE_UNINITIALIZED check.
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        mBaseVolL = gain; mBaseVolR = gain;
        pushVolume();
        return SUCCESS;
    }

    // AudioTrack.SUPPORTED_OUT_CHANNELS (AOSP): every CHANNEL_OUT_* position bit
    // except DEFAULT and the haptic bits. Values are AudioFormat's.
    private static final int SUPPORTED_OUT_CHANNELS = 0x4 | 0x8 | 0x10 | 0x20 | 0x40 | 0x80
            | 0x100 | 0x200 | 0x400 | 0x800 | 0x1000 | 0x2000 | 0x4000 | 0x8000 | 0x10000
            | 0x20000 | 0x40000 | 0x80000 | 0x100000 | 0x200000 | 0x400000 | 0x800000
            | 0x1000000 | 0x2000000 | 0x4000000 | 0x8000000;
    // AOSP CHANNEL_PAIR_MAP values: positions that may only appear together.
    private static final int[] CHANNEL_PAIRS = {
        0x4 | 0x8,              // front
        0x40 | 0x80,            // back
        0x100 | 0x200,          // front of center
        0x800 | 0x1000,         // side
        0x4000 | 0x10000,       // top front
        0x20000 | 0x80000,      // top back
        0x100000 | 0x200000,    // top side
        0x400000 | 0x1000000,   // bottom front
        0x4000000 | 0x8000000,  // front wide
    };

    /** AudioFormat.isPublicEncoding. In the AOSP source checked, the public
     *  encodings are exactly 2 (PCM_16BIT) through 44
     *  (IAMF_BASE_ENHANCED_PROFILE_PCM); ENCODING_INVALID (0) and
     *  ENCODING_DEFAULT (1) are not public. */
    private static boolean isPublicEncoding(int enc) {
        return enc >= 2 && enc <= 44;
    }

    /** AudioFormat.isEncodingLinearFrames: PCM, IEC61937 and DEFAULT. */
    private static boolean isEncodingLinearFrames(int enc) {
        return enc == 1 || enc == 2 || enc == 3 || enc == 4 || enc == 13 || enc == 21
                || enc == 22;
    }

    /** AudioTrack.isMultichannelConfigSupported (AOSP). Only the compressed
     *  24-channel limit is modelled: the PCM limit is the HAL's, and no mask
     *  built from the supported positions exceeds it. */
    private static boolean isMultichannelConfigSupported(int channelConfig, int encoding) {
        if ((channelConfig & SUPPORTED_OUT_CHANNELS) != channelConfig) return false;
        if (!isEncodingLinearFrames(encoding) && Integer.bitCount(channelConfig) > 24) {
            return false;
        }
        if ((channelConfig & 0xC) != 0xC) return false; // FL/FR must be present
        for (int pair : CHANNEL_PAIRS) {
            if ((channelConfig & pair) != 0 && (channelConfig & pair) != pair) return false;
        }
        return true;
    }

    // AOSP AudioTrack.getMinBufferSize validation order: channel config, then
    // isPublicEncoding, then the sample rate range; every failure is
    // ERROR_BAD_VALUE. The size itself is native on AOSP (HAL dependent).
    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        switch (channelConfig) {
            case 0x4:   // CHANNEL_OUT_MONO
            case 2:     // CHANNEL_CONFIGURATION_MONO
            case 0xc:   // CHANNEL_OUT_STEREO
            case 3:     // CHANNEL_CONFIGURATION_STEREO
                break;
            default:
                if (!isMultichannelConfigSupported(channelConfig, audioFormat)) {
                    return ERROR_BAD_VALUE;
                }
        }
        if (!isPublicEncoding(audioFormat)) {
            return ERROR_BAD_VALUE;
        }
        // AudioFormat.SAMPLE_RATE_HZ_MIN/MAX come from native code on AOSP
        // (4000..192000 on the platform builds we compare against; the value
        // is not in the Java sources, so it is carried from AudioRecord here).
        if (sampleRateInHz < 4000 || sampleRateInHz > 192000) {
            return ERROR_BAD_VALUE;
        }
        int ch  = channelsFromConfig(channelConfig);
        int bps = bytesPerSample(normalizeEncoding(audioFormat));
        // ~ minimum sane buffer: 25 ms, floored so callers that multiply it
        // still land on a usable size. Matches the spirit of AOSP's native
        // min (device-specific) without pretending a fixed 4096.
        int frames = Math.max(sampleRateInHz / 40, 512);
        int bytes  = frames * ch * bps;
        return Math.max(bytes, 4096);
    }
    public static int getNativeOutputSampleRate(int streamType) { return 44100; }
    // AOSP: GAIN_MIN/GAIN_MAX, exact literal constants (AudioTrack.java).
    public static float getMinVolume() { return 0.0f; }
    public static float getMaxVolume() { return 1.0f; }

    // ----- additive: AudioTrack.Builder + minor position/marker surface.
    // AOSP frameworks/base media/java/android/media/AudioTrack.java. Modern apps
    // (ExoPlayer, Oboe-fallback) construct via Builder. Marker/period
    // notification has no scheduler backend wired, so listener registration is
    // recorded but never fires (a silent writer never crosses a marker we track).

    // AOSP's getPlaybackHeadPosition() returns native_get_position()'s frame
    // count -- the number of frames the backend has actually consumed, reset by
    // flush()/stop() (AudioTrack.java javadoc: "This is a continuously
    // advancing counter ... It is reset to zero by flush(), reloadStaticData(),
    // and stop()"). Wired to audio_backend.mm's Stream::played_frames, which
    // the AVAudioSourceNode render callback advances by exactly the frames it
    // pops from the ring each time CoreAudio actually asks for more -- i.e.
    // frames handed to the hardware render path, not bytes write() has merely
    // queued. That distinction is what makes this usable as ExoPlayer/media3's
    // audio-sink master clock: a track that is queued-but-not-yet-audible must
    // NOT look like it is making progress.
    //
    // AOSP documents the "int" return as signed but reinterpreted unsigned,
    // wrapping roughly every 27:03:11 at 44.1 kHz. The native side tracks full
    // 64-bit precision; this (int) narrowing reproduces AOSP's 32-bit wrap
    // exactly (low 32 bits, same as a real HAL position overflowing uint32_t).
    /** MACAPK_AUDIO_CLOCK_TRACE=1: the playback clock as a player sees it.
     *  offset = reported position - host time; a steady clock keeps it
     *  constant, so its spread is the clock's jitter. kind 0 = head position,
     *  1 = getTimestamp (plus how stale its nanoTime is). Every 200 reads. */
    static final boolean CLOCK_TRACE = "1".equals(System.getenv("MACAPK_AUDIO_CLOCK_TRACE"));
    private final long[][] mClk = new long[2][200]; private final long[] mStale = new long[200];
    private final int[] mClkN = new int[2];
    private synchronized void clockTrace(int kind, long offsetNs, long staleNs) {
        int i = mClkN[kind]++;
        mClk[kind][i] = offsetNs;
        if (kind == 1) mStale[i] = staleNs;
        if (mClkN[kind] == 200) {
            long[] a = mClk[kind].clone(); java.util.Arrays.sort(a);
            String extra = "";
            if (kind == 1) { long[] b = mStale.clone(); java.util.Arrays.sort(b);
                extra = String.format(" stale p50=%.1fms p95=%.1f max=%.1f", b[100] / 1e6, b[190] / 1e6, b[199] / 1e6); }
            com.macapk.loader.NativeLog.i("AudioTrack", String.format("clock %s: offset spread p5..p95=%.1fms (p50-p5=%.1f, p95-p50=%.1f) max-min=%.1f%s",
                kind == 0 ? "headPosition" : "getTimestamp", (a[190] - a[10]) / 1e6, (a[100] - a[10]) / 1e6, (a[190] - a[100]) / 1e6,
                (a[199] - a[0]) / 1e6, extra));
            mClkN[kind] = 0;
        }
    }
    public int getPlaybackHeadPosition() {
        if (handle == 0) return 0;
        int pos = (int) com.macapk.loader.NativeAudio.nativeGetPlaybackHeadPosition(handle);
        com.macapk.loader.AudioTrace.hit("AudioTrack.getPlaybackHeadPosition", "pos=" + pos);
        if (CLOCK_TRACE && sampleRate > 0) clockTrace(0, (long) pos * 1_000_000_000L / sampleRate - System.nanoTime(), 0);
        return pos;
    }
    // AOSP's native_get_timestamp-backed poll: fills (framePosition, nanoTime)
    // from the HAL clock and returns true, or leaves `timestamp` untouched and
    // returns false when no timestamp is currently available (AudioTrack.java,
    // getTimestamp(AudioTimestamp) -- false is a normal, documented outcome
    // callers already handle, e.g. immediately after start()). The sibling
    // method getTimestampWithStatus's javadoc spells out exactly when: "{@link
    // #ERROR_WOULD_BLOCK} if called in STOPPED or FLUSHED state, or if called
    // immediately after start/ACTIVE, when the number of frames consumed is
    // less than the overall hardware latency to physical output." The native
    // side answers false in precisely that situation -- no real frame has
    // reached the render callback since the last stop()/flush() reset (see
    // getPlaybackHeadPosition() above). A PAUSED track that already played
    // real audio keeps returning its last (frozen) pair instead of false,
    // matching AOSP: PAUSED is not in that WOULD_BLOCK list, only
    // STOPPED/FLUSHED/just-started are.
    //
    // nanoTime is in System.nanoTime()'s own timebase, per
    // AudioTimestamp.TIMEBASE_MONOTONIC's documented contract ("the same units
    // and timebase as java.lang.System#nanoTime"): the native side derives it
    // from the AVAudioSourceNode render callback's own AudioTimeStamp.mHostTime
    // via AudioConvertHostTimeToNanos, which performs the identical
    // (mach_absolute_time() * timebase.numer) / timebase.denom conversion
    // OpenJDK's os::javaTimeNanos() uses on Darwin (verified against
    // hotspot/os/bsd/os_bsd.cpp) -- so both sides land on the same clock.
    public boolean getTimestamp(AudioTimestamp timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException();
        }
        if (handle == 0) {
            com.macapk.loader.AudioTrace.hit("AudioTrack.getTimestamp", "handle=0 -> false");
            return false;
        }
        long[] out = new long[2];
        if (!com.macapk.loader.NativeAudio.nativeGetTimestamp(handle, out)) {
            com.macapk.loader.AudioTrace.hit("AudioTrack.getTimestamp", "native false (no frame yet)");
            return false;
        }
        // AudioTimestamp.framePosition javadoc: "the low-order 32 bits of
        // position is in wrapping frame units similar to
        // AudioTrack.getPlaybackHeadPosition()" -- mask so a caller comparing
        // the two sees the same wrapped value for the same instant.
        timestamp.framePosition = out[0] & 0xFFFFFFFFL;
        timestamp.nanoTime = out[1];
        if (CLOCK_TRACE && sampleRate > 0) clockTrace(1, timestamp.framePosition * 1_000_000_000L / sampleRate - timestamp.nanoTime,
            System.nanoTime() - timestamp.nanoTime);
        com.macapk.loader.AudioTrace.hit("AudioTrack.getTimestamp",
            "true framePosition=" + timestamp.framePosition + " nanoTime=" + timestamp.nanoTime);
        return true;
    }
    /**
     * The CURRENT playback sample rate. AOSP: `return native_get_playback_rate();`
     * -> `lpTrack->getSampleRate()` (frameworks/base core/jni/
     * android_media_AudioTrack.cpp:814-822).
     *
     * ⚠ This is deliberately NOT the same number as {@link #getSampleRate()},
     * and the two must be allowed to disagree. AOSP's Java `mSampleRate` is
     * written only by native_setup and audioParamCheck
     * (frameworks/base .../AudioTrack.java:887,1855) -- never by
     * setPlaybackRate -- so getSampleRate() keeps reporting the CONSTRUCTION
     * rate while this tracks the change. Collapsing them into one field is
     * exactly the mistake this file used to make: getPlaybackRate() returned
     * the construction rate forever.
     */
    public int getPlaybackRate() {
        if (handle == 0) return sampleRate;
        return com.macapk.loader.NativeAudio.nativeGetPlaybackRate(handle);
    }

    /**
     * Set the playback sample rate: a pure RESAMPLE, pitch and speed coupled.
     * AOSP's own javadoc is explicit -- "setting it to half the sample rate of
     * the content will cause the playback to last twice as long, but will also
     * result in a pitch shift down by one octave". Use setPlaybackParams for
     * tempo-without-pitch.
     *
     * AOSP body (frameworks/base .../AudioTrack.java:2780-2788):
     *   if (mState != STATE_INITIALIZED) return ERROR_INVALID_OPERATION;
     *   if (sampleRateInHz <= 0) return ERROR_BAD_VALUE;
     *   return native_set_playback_rate(sampleRateInHz);
     *
     * ⚠ HISTORY, because it is the point of the change: this method used to be
     * `{ return SUCCESS; }` with an empty body, and getPlaybackRate() returned
     * the construction rate, so the pair round-tripped a lie -- the app was
     * told the rate had been applied and nothing downstream had anywhere to
     * apply it (there was no rate state in the MODE_RING render path at all).
     * The resampler now lives in Ring::pop_resampled (audio_backend.mm).
     */
    public int setPlaybackRate(int sampleRateInHz) {
        if (getState() != STATE_INITIALIZED) return ERROR_INVALID_OPERATION;
        if (sampleRateInHz <= 0) return ERROR_BAD_VALUE;
        int r = com.macapk.loader.NativeAudio.nativeSetPlaybackRate(handle, sampleRateInHz);
        com.macapk.loader.AudioTrace.hit("AudioTrack.setPlaybackRate",
            "rate=" + sampleRateInHz + " -> " + r);
        return r;
    }
    public int setNotificationMarkerPosition(int markerInFrames) {
        // AOSP (:2828-2831): "if (mState == STATE_UNINITIALIZED) { return
        // ERROR_INVALID_OPERATION; }" before native_set_marker_pos.
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        mMarkerPositionInFrames = markerInFrames;
        return SUCCESS;
    }
    public int setPositionNotificationPeriod(int periodInFrames) {
        // AOSP (:2842-2845): same STATE_UNINITIALIZED check before
        // native_set_pos_update_period.
        if (honestUninitialized()) return ERROR_INVALID_OPERATION;
        mPositionNotificationPeriod = periodInFrames;
        return SUCCESS;
    }
    // AOSP fires onMarkerReached / onPeriodicNotification from the native event
    // handler as playback advances. The setters here previously DISCARDED the
    // listener outright while setNotificationMarkerPosition /
    // setPositionNotificationPeriod both returned SUCCESS -- so an app that
    // sequences on playback progress (speech, metronomes, streaming voice)
    // armed a notification, was told it worked, and waited forever.
    //
    // getPlaybackHeadPosition() above is already real (native frame counter),
    // so the notification only needed a watcher over it. One daemon thread per
    // track that actually arms a notification; nothing is started otherwise.
    private volatile OnPlaybackPositionUpdateListener mPosListener;
    private volatile android.os.Handler mPosHandler;
    private Thread mPosWatcher;
    private volatile boolean mReleased;

    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener) {
        setPlaybackPositionUpdateListener(listener, null);
    }
    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener,
                                                  android.os.Handler handler) {
        mPosListener = listener;
        mPosHandler = handler;
        if (listener != null) macapkStartPositionWatcher();
    }

    /** Post to the app's Handler when it supplied one, else the main looper --
     *  AOSP delivers these on the handler's thread, never on an internal one. */
    private void macapkDeliver(Runnable r) {
        android.os.Handler h = mPosHandler;
        if (h != null) { try { h.post(r); return; } catch (Throwable ignored) { } }
        // Never let this watcher be the FIRST caller of getMainLooper(): that
        // would pin the main looper to this daemon thread (Looper.getMainLooper
        // notes the caller when none is registered), and every later main-thread
        // post would land on a thread nothing pumps.
        if (android.os.Looper.macapkHasMainThread()) {
            try { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); return; }
            catch (Throwable ignored) { }
        }
        try { r.run(); } catch (Throwable ignored) { }
    }

    private synchronized void macapkStartPositionWatcher() {
        if (mPosWatcher != null) return;
        mPosWatcher = new Thread(new Runnable() { @Override public void run() {
            int lastPos = 0;
            boolean markerFired = false;
            int armedMarker = -1;
            long nextPeriod = -1;
            for (;;) {
                if (mReleased) return;
                try { Thread.sleep(20L); } catch (InterruptedException e) { return; }
                if (mReleased) return;
                final OnPlaybackPositionUpdateListener l = mPosListener;
                if (l == null) continue;
                int pos;
                try { if (getPlayState() != PLAYSTATE_PLAYING) continue; pos = getPlaybackHeadPosition(); }
                catch (Throwable t) { continue; }
                // A backwards jump means the track was flushed or restarted, so
                // the marker becomes armed again -- AOSP re-notifies after a
                // flush for the same reason.
                if (pos < lastPos) { markerFired = false; nextPeriod = -1; }
                lastPos = pos;

                int marker = mMarkerPositionInFrames;
                // AOSP AudioTrack::setMarkerPosition is
                // `mMarkerPosition = marker; mMarkerReached = false;` -- EVERY set
                // re-arms. Latching on the first hit broke the canonical chained
                // pattern (onMarkerReached sets the NEXT marker): the app got one
                // callback and then waited forever, which is the same
                // armed-then-silence failure this watcher exists to cure.
                if (marker != armedMarker) { armedMarker = marker; markerFired = false; }
                if (!markerFired && marker > 0 && pos >= marker) {
                    markerFired = true;
                    macapkDeliver(new Runnable() { @Override public void run() {
                        l.onMarkerReached(AudioTrack.this); } });
                }
                int period = mPositionNotificationPeriod;
                if (period > 0) {
                    if (nextPeriod < 0) nextPeriod = pos + period;
                    while (pos >= nextPeriod) {
                        nextPeriod += period;
                        macapkDeliver(new Runnable() { @Override public void run() {
                            l.onPeriodicNotification(AudioTrack.this); } });
                    }
                } else nextPeriod = -1;
            }
        } }, "macapk-audiotrack-pos");
        mPosWatcher.setDaemon(true);
        mPosWatcher.start();
    }
    // AOSP (:3707-3710, :3732-3735): both check "if (mState ==
    // STATE_UNINITIALIZED) { return ERROR_INVALID_OPERATION; }" first.
    public int attachAuxEffect(int effectId) { return honestUninitialized() ? ERROR_INVALID_OPERATION : SUCCESS; }
    public int setAuxEffectSendLevel(float level) { return honestUninitialized() ? ERROR_INVALID_OPERATION : SUCCESS; }
    public int getAudioSessionId() { return 0; }
    public AudioFormat getFormat() {
        return new AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channels == 1 ? AudioFormat.CHANNEL_OUT_MONO
                                          : AudioFormat.CHANNEL_OUT_STEREO)
            .build();
    }

    // Offload/direct-playback capability surface (AOSP AudioTrack.java). Mac.apk
    // has no hardware audio-DSP offload or passthrough silicon (CoreAudio via
    // AVAudioSourceNode is a software mixer), so this AudioTrack is never
    // offloaded -- the same terminal answer a real device without offload
    // hardware gives.
    public boolean isOffloadedPlayback() { return false; }

    // AOSP: static capability query, no instance required. Throws on null args
    // exactly like AOSP (verified verbatim in source); otherwise always false
    // since no direct/passthrough output path exists here.
    public static boolean isDirectPlaybackSupported(AudioFormat format, AudioAttributes attributes) {
        if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat argument");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes argument");
        }
        return false;
    }

    // AOSP throws IllegalStateException here whenever !mOffloaded (verified
    // verbatim in source) rather than silently accepting a callback that can
    // never fire -- an app that skipped the isOffloadedPlayback() guard would
    // otherwise wait forever for onDataRequest callbacks real Android would
    // never let it wait for either. isOffloadedPlayback() is always false on
    // Mac.apk, so this always throws, matching a real device without offload
    // hardware.
    public void registerStreamEventCallback(java.util.concurrent.Executor executor,
                                             StreamEventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null StreamEventCallback");
        }
        throw new IllegalStateException(
            "Cannot register StreamEventCallback on non-offloaded AudioTrack");
    }
    public void unregisterStreamEventCallback(StreamEventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null StreamEventCallback");
        }
        throw new IllegalStateException("No StreamEventCallback on non-offloaded AudioTrack");
    }

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int ERROR_BAD_VALUE = -2;
    public static final int ERROR_INVALID_OPERATION = -3;
    /** AOSP AudioTrack.ERROR_DEAD_OBJECT (API 24) = -6, the same value
     *  AudioRecord uses. Absent here until v1.0.941, which is part of why the
     *  deviceless write path answered "consumed" -- there was no honest code to
     *  return. Apps branch on it: ExoPlayer's DefaultAudioSink treats it as a
     *  sink reset rather than a fatal error. */
    public static final int ERROR_DEAD_OBJECT = -6;
    public static final int PERFORMANCE_MODE_NONE = 0;
    public static final int PERFORMANCE_MODE_LOW_LATENCY = 1;
    public static final int PERFORMANCE_MODE_POWER_SAVING = 2;

    public interface OnPlaybackPositionUpdateListener {
        void onMarkerReached(AudioTrack track);
        void onPeriodicNotification(AudioTrack track);
    }

    // AOSP: notifications about offloaded-stream playback (AudioTrack.java).
    // registerStreamEventCallback() always throws on Mac.apk (see above) since
    // we never offload, so these bodies never fire; kept as real overridable
    // no-ops to match AOSP's exact shape for any code that subclasses it before
    // checking isOffloadedPlayback().
    public abstract static class StreamEventCallback {
        public void onTearDown(AudioTrack track) { }
        public void onPresentationEnded(AudioTrack track) { }
        public void onDataRequest(AudioTrack track, int sizeInFrames) { }
    }

    // AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
    // mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
    // app compiled against real Android that subclasses this fails to resolve
    // against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
    public static class Builder {
        private AudioAttributes attrs;
        private AudioFormat format;
        private int bufferSizeInBytes;
        private int mode = MODE_STREAM;
        private int sessionId = 0;
        public Builder() {}
        public Builder setAudioAttributes(AudioAttributes attributes) { this.attrs = attributes; return this; }
        public Builder setAudioFormat(AudioFormat fmt) { this.format = fmt; return this; }
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) { this.bufferSizeInBytes = bufferSizeInBytes; return this; }
        public Builder setTransferMode(int mode) { this.mode = mode; return this; }
        public Builder setSessionId(int sessionId) { this.sessionId = sessionId; return this; }
        public Builder setPerformanceMode(int performanceMode) { return this; }
        // Recorded per AOSP's Builder surface; Mac.apk has no offload DSP, so
        // the constructed track is never actually offloaded regardless of this
        // request (isOffloadedPlayback() always answers false), matching how a
        // real device without offload hardware would deny the request too.
        public Builder setOffloadedPlayback(boolean offload) { return this; }
        /** API 29. AOSP validates the mode and records it; this backend has no
         *  encapsulated (tunneled bitstream) path -- AudioDeviceInfo
         *  .getEncapsulationModes() reports none -- so the request is validated
         *  and the built track carries decoded PCM, which is what it would have
         *  carried anyway. */
        public Builder setEncapsulationMode(int encapsulationMode) {
            if (encapsulationMode != ENCAPSULATION_MODE_NONE
                    && encapsulationMode != ENCAPSULATION_MODE_ELEMENTARY_STREAM) {
                throw new IllegalArgumentException(
                        "Invalid encapsulation mode " + encapsulationMode);
            }
            return this;
        }
        public Builder setContext(android.content.Context context) { return this; }
        public AudioTrack build() {
            AudioFormat f = format != null ? format : new AudioFormat();
            int buf = bufferSizeInBytes > 0 ? bufferSizeInBytes
                : getMinBufferSize(f.getSampleRate(), f.getChannelMask(), f.getEncoding());
            AudioTrack track = new AudioTrack(attrs, f, buf, mode, sessionId);
            if (HONEST_OPEN && track.getState() == STATE_UNINITIALIZED) {
                // AOSP: "release is not necessary" (see HONEST_OPEN).
                throw new UnsupportedOperationException("Cannot create AudioTrack");
            }
            return track;
        }
    }

    public void setLogSessionId(android.media.metrics.LogSessionId logSessionId) {
        // AOSP throws on null here; and it stores the id so getLogSessionId()
        // reads back what was written.
        mLogSessionId = java.util.Objects.requireNonNull(logSessionId,
                "logSessionId must not be null");
    }

    /**
     * Set speed and/or pitch.
     *
     * THE TWO AXES ARE NOT THE SAME MECHANISM, and conflating them is how this
     * API gets faked. PITCH is a resample (AOSP forces the time stretcher's own
     * pitch to NORMAL and emulates pitch with the sample-rate converter --
     * frameworks/av/media/libaudioclient/AudioTrack.cpp:97-113). SPEED is
     * tempo WITHOUT pitch and is served by a real time stretcher
     * (AVAudioUnitTimePitch); serving it with the resampler instead would
     * chipmunk every podcast at 1.5x while getPlaybackParams reported 1.5.
     * See the long comment above macapk_audio_stream_set_playback_rate in
     * runtime/src/audio_backend.mm.
     *
     * AOSP's JNI does a per-field READ-MODIFY-WRITE against the track's current
     * rate, gated on PlaybackParams' mSet flags ("to simulate partially set
     * params, we do a read-modify-write" -- frameworks/base core/jni/
     * android_media_AudioTrack.cpp:826-870), and throws
     * IllegalArgumentException("arguments out of range") when the native
     * setPlaybackRate rejects the result. Both are reproduced here.
     *
     * @throws IllegalArgumentException if params is null, or the resulting
     *         speed/pitch cannot be applied.
     */
    public void setPlaybackParams(android.media.PlaybackParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params is null");
        }
        // Read-modify-write: only the fields the caller actually SET move.
        float speed = mPlaybackParams.macapkSpeedRaw();
        float pitch = mPlaybackParams.macapkPitchRaw();
        int fallbackMode = mPlaybackParams.macapkFallbackModeRaw();
        int stretchMode  = mPlaybackParams.macapkStretchModeRaw();
        if (params.macapkHasSpeed())        speed        = params.macapkSpeedRaw();
        if (params.macapkHasPitch())        pitch        = params.macapkPitchRaw();
        if (params.macapkHasFallbackMode()) fallbackMode = params.macapkFallbackModeRaw();
        if (params.macapkHasStretchMode())  stretchMode  = params.macapkStretchModeRaw();

        if (!isRateValid(speed, pitch, fallbackMode, stretchMode)) {
            throw new IllegalArgumentException("arguments out of range");
        }
        if (handle != 0
                && com.macapk.loader.NativeAudio.nativeSetPlaybackParams(handle, speed, pitch)
                   != SUCCESS) {
            throw new IllegalArgumentException("arguments out of range");
        }
        // Store the ORIGINAL (not effective) values, as AOSP stores
        // mPlaybackRate = playbackRate rather than playbackRateTemp.
        mPlaybackParams = new android.media.PlaybackParams()
                .setSpeed(speed).setPitch(pitch)
                .setAudioFallbackMode(fallbackMode)
                .setAudioStretchMode(stretchMode);
        com.macapk.loader.AudioTrace.hit("AudioTrack.setPlaybackParams",
            "speed=" + speed + " pitch=" + pitch);
    }

    /**
     * AOSP's get_playback_params returns the NATIVE rate with all four
     * set-flags true (android_media_AudioTrack.cpp:874-886), never the
     * caller's partially-set object -- handing that back would carry the
     * caller's set-flags, so an app that set only speed would get an object
     * whose getPitch() throws.
     */
    public android.media.PlaybackParams getPlaybackParams() {
        float speed = mPlaybackParams.macapkSpeedRaw();
        float pitch = mPlaybackParams.macapkPitchRaw();
        if (handle != 0) {
            float[] v = new float[2];
            com.macapk.loader.NativeAudio.nativeGetPlaybackParams(handle, v);
            speed = v[0];
            pitch = v[1];
        }
        return new android.media.PlaybackParams()
                .setSpeed(speed).setPitch(pitch)
                .setAudioFallbackMode(mPlaybackParams.macapkFallbackModeRaw())
                .setAudioStretchMode(mPlaybackParams.macapkStretchModeRaw());
    }

    private android.media.PlaybackParams mPlaybackParams =
            new android.media.PlaybackParams().allowDefaults();

    // isAudioPlaybackRateValid, mirrored from
    // frameworks/av/media/libaudioprocessing/include/media/AudioResamplerPublic.h:58-72.
    // Bounds are AOSP's own, verbatim from system/media/audio/include/system/
    // audio.h:2180-2199: speed 0.01..20.0, pitch 0.25..4.0, and in the
    // FALLBACK_FAIL + VOICE/DEFAULT stretch-mode branch speed narrows to
    // sonic's 0.1..6.0.
    private static boolean isRateValid(float speed, float pitch,
                                       int fallbackMode, int stretchMode) {
        if (Float.isNaN(speed) || Float.isNaN(pitch)) return false;
        if (pitch < 0.25f || pitch > 4.0f) return false;
        if (fallbackMode == android.media.PlaybackParams.AUDIO_FALLBACK_MODE_FAIL
                && (stretchMode == android.media.PlaybackParams.AUDIO_STRETCH_MODE_VOICE
                 || stretchMode == android.media.PlaybackParams.AUDIO_STRETCH_MODE_DEFAULT)) {
            return speed >= 0.1f && speed <= 6.0f;
        }
        return speed >= 0.01f && speed <= 20.0f;
    }
    public void setOffloadEndOfStream() { }
    public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) { }
    // Routing never changes on this host (one CoreAudio output), so a registered
    // listener is retained and never fires -- the same observable result as a
    // device whose route is fixed. The records exist so add/remove behave as a
    // pair and a double-add does not double-register.
    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mRoutingListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener listener, android.os.Handler handler) {
        if (listener == null) return;
        for (Object[] entry : mRoutingListeners) {
            if (entry[0] == listener) return;
        }
        mRoutingListeners.add(new Object[] { listener, handler });
    }
    public void removeOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener listener) {
        for (Object[] entry : mRoutingListeners) {
            if (entry[0] == listener) { mRoutingListeners.remove(entry); return; }
        }
    }

    /** @deprecated AOSP's pre-API-24 overload; its listener type extends the
     *  modern one, so it delegates. */
    @Deprecated
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener,
            android.os.Handler handler) {
        addOnRoutingChangedListener((android.media.AudioRouting.OnRoutingChangedListener) listener,
                handler);
    }
    @Deprecated
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        removeOnRoutingChangedListener((android.media.AudioRouting.OnRoutingChangedListener) listener);
    }

    /** @deprecated in AOSP since API 24, superseded by
     *  AudioRouting.OnRoutingChangedListener. */
    @Deprecated
    public interface OnRoutingChangedListener extends AudioRouting.OnRoutingChangedListener {
        void onRoutingChanged(AudioTrack audioTrack);
        @Override default void onRoutingChanged(AudioRouting router) {
            if (router instanceof AudioTrack) onRoutingChanged((AudioTrack) router);
        }
    }

    /** AOSP: "return native_get_underrun_count();" -- underrun episodes the
     *  mixer counted on this track since creation (ERROR_INVALID_OPERATION on
     *  an uninitialized track is not reachable from Java there either, so 0
     *  without a native side). The backend counts each starved render cycle
     *  run once, as AudioFlinger does. */
    public int getUnderrunCount() {
        if (handle == 0) return 0;
        try {
            return com.macapk.loader.NativeAudio.nativeGetUnderrunCount(handle);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * AOSP (frameworks/base .../AudioTrack.java, setPlaybackHeadPosition):
     *   if (mDataLoadMode == MODE_STREAM || mState == STATE_UNINITIALIZED
     *       || getPlayState() == PLAYSTATE_PLAYING) return ERROR_INVALID_OPERATION;
     *   if (!(0 <= positionInFrames && positionInFrames <= mNativeBufferSizeInFrames))
     *       return ERROR_BAD_VALUE;
     *   return native_set_position(positionInFrames);
     *
     * Mac.apk has no MODE_STATIC buffer to seek within -- every track we can
     * create is MODE_STREAM -- so the first branch is the terminal answer for
     * all of them, and it is the SAME answer a real device gives a MODE_STREAM
     * track. That makes this the cheapest possible correction of a fake
     * success: it used to return SUCCESS unconditionally while doing nothing,
     * which an app cannot detect; now it returns the exact error code the app
     * already knows how to handle.
     *
     * The trailing ERROR_INVALID_OPERATION is not dead code -- an app may
     * legitimately construct with MODE_STATIC, and our backend has no static
     * buffer to seek within, so refusing is the honest answer rather than
     * reporting a seek we did not perform. If a real MODE_STATIC path is ever
     * built, native_set_position goes there.
     */
    public int setPlaybackHeadPosition(int positionInFrames) {
        if (dataLoadMode == MODE_STREAM || getState() == STATE_UNINITIALIZED
                || getPlayState() == PLAYSTATE_PLAYING) {
            return ERROR_INVALID_OPERATION;
        }
        if (positionInFrames < 0 || positionInFrames > bufferSizeInFrames) {
            return ERROR_BAD_VALUE;
        }
        return ERROR_INVALID_OPERATION;
    }

    // =====================================================================
    // API-gap close-out. Signatures from /tmp/aosp_api/android16.txt; behaviour
    // from frameworks/base/media/java/android/media/AudioTrack.java
    // (android16-release).
    // =====================================================================

    public static final int STATE_NO_STATIC_DATA = 2;

    public static final int DUAL_MONO_MODE_OFF = 0;
    public static final int DUAL_MONO_MODE_LR = 1;
    public static final int DUAL_MONO_MODE_LL = 2;
    public static final int DUAL_MONO_MODE_RR = 3;

    public static final int ENCAPSULATION_MODE_NONE = 0;
    public static final int ENCAPSULATION_MODE_ELEMENTARY_STREAM = 1;

    public static final int ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER = 1;
    public static final int ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR = 2;
    public static final int ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT = 3;

    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_NORMAL = 0;
    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_LEFT = 1;
    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_RIGHT = 2;

    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String CONTENTTYPE = "android.media.audiotrack.type";
        public static final String STREAMTYPE = "android.media.audiotrack.streamtype";
        public static final String USAGE = "android.media.audiotrack.usage";
        /** @deprecated in AOSP; the key names an audiorecord metric. */
        @Deprecated public static final String CHANNELMASK =
                "android.media.audiorecord.channelmask";
        /** @deprecated in AOSP; the key names an audiorecord metric. */
        @Deprecated public static final String SAMPLERATE =
                "android.media.audiorecord.samplerate";
    }

    /** API 29. Fired when an OFFLOADED codec's output format changes. Mac.apk
     *  never offloads (AudioManager.getPlaybackOffloadSupport reports
     *  NOT_SUPPORTED), so this cannot fire -- the registration is real, the
     *  event source genuinely does not exist. */
    public interface OnCodecFormatChangedListener {
        void onCodecFormatChanged(AudioTrack audioTrack, AudioMetadataReadMap info);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mCodecFormatListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addOnCodecFormatChangedListener(java.util.concurrent.Executor executor,
            OnCodecFormatChangedListener listener) {
        java.util.Objects.requireNonNull(executor, "executor must not be null");
        java.util.Objects.requireNonNull(listener, "listener must not be null");
        for (Object[] entry : mCodecFormatListeners) {
            if (entry[1] == listener) return;
        }
        mCodecFormatListeners.add(new Object[] { executor, listener });
    }

    public void removeOnCodecFormatChangedListener(OnCodecFormatChangedListener listener) {
        java.util.Objects.requireNonNull(listener, "listener must not be null");
        for (Object[] entry : mCodecFormatListeners) {
            if (entry[1] == listener) { mCodecFormatListeners.remove(entry); return; }
        }
    }

    // ---- AudioRouting ----------------------------------------------------

    private AudioDeviceInfo mPreferredDevice;

    /** AOSP: the device the app ASKED for, or null if none was requested. */
    @Override public AudioDeviceInfo getPreferredDevice() { return mPreferredDevice; }

    /** AOSP returns true when the request was applied. There is exactly one
     *  output here, so a request naming it succeeds, a request for anything else
     *  cannot be honoured, and null ("no preference") always succeeds. */
    @Override public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo == null) { mPreferredDevice = null; return true; }
        if (!deviceInfo.isSink()) return false;
        mPreferredDevice = deviceInfo;
        return true;
    }

    /** AOSP: the device audio is ACTUALLY going to while the track is
     *  initialized, null otherwise. Ours is the single CoreAudio output. */
    @Override public AudioDeviceInfo getRoutedDevice() {
        return (getState() == STATE_UNINITIALIZED) ? null : new AudioDeviceInfo();
    }

    // ---- VolumeAutomation ------------------------------------------------

    /**
     * A working shaper: the sampled curve multiplies the app's own volume
     * through the same native path setVolume() uses. See VolumeShaper for the
     * one honest difference from AOSP (clock-time sampling on a 10 ms timer
     * rather than per-frame in the mixer).
     */
    @Override public VolumeShaper createVolumeShaper(VolumeShaper.Configuration configuration) {
        return new VolumeShaper(configuration, new VolumeShaper.Target() {
            @Override public void onShaperVolumeChanged(float scale) {
                mShaperScale = scale;
                pushVolume();
            }
        });
    }

    // ---- simple accessors AOSP exposes -----------------------------------

    /** AOSP returns the CHANNEL_OUT_* mask the track was created with. The mask
     *  is derived from the channel count rather than stored separately so it can
     *  never disagree with getChannelCount(). */
    public int getChannelConfiguration() {
        switch (channels) {
            case 1: return AudioFormat.CHANNEL_OUT_MONO;
            case 2: return AudioFormat.CHANNEL_OUT_STEREO;
            default: return AudioFormat.CHANNEL_OUT_STEREO;
        }
    }

    /** AOSP: the stream type the track plays on. Every track here is created
     *  against the shared music output. */
    public int getStreamType() { return AudioManager.STREAM_MUSIC; }

    /** @NonNull in AOSP. Built from the track's real format, so an app comparing
     *  it against getFormat() sees a consistent track. */
    public AudioAttributes getAudioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    /** @deprecated protected AOSP accessor for the native frame count; AOSP
     *  itself forwards it to getBufferSizeInFrames(). */
    @Deprecated protected int getNativeFrameCount() { return getBufferSizeInFrames(); }

    /** @deprecated protected AOSP setter used only by the framework's own
     *  constructors to record STATE_*; there is no external effect. */
    @Deprecated protected void setState(int state) { }

    /** AOSP returns the marker/period the app set, 0 when none. These are stored
     *  rather than reported as 0 so an app that writes then reads gets its own
     *  value back -- the only part of the contract observable without the
     *  notification callbacks, which this backend does not raise. */
    private volatile int mMarkerPositionInFrames;
    private volatile int mPositionNotificationPeriod;

    public int getNotificationMarkerPosition() { return mMarkerPositionInFrames; }
    public int getPositionNotificationPeriod() { return mPositionNotificationPeriod; }

    /** AOSP: how many frames must be queued before a MODE_STREAM track starts.
     *  Ours starts as soon as the ring has data, i.e. a threshold of 1 frame,
     *  and AOSP's @IntRange(from=1) makes 1 the legal minimum. */
    private int mStartThresholdInFrames = 1;

    public int getStartThresholdInFrames() {
        if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("AudioTrack is not initialized");
        }
        return mStartThresholdInFrames;
    }

    /** AOSP rejects a non-positive threshold and returns the value actually
     *  applied, which may be clamped to the buffer size. */
    public int setStartThresholdInFrames(int startThresholdInFrames) {
        if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("AudioTrack is not initialized");
        }
        if (startThresholdInFrames < 1) {
            throw new IllegalArgumentException(
                    "startThresholdInFrames " + startThresholdInFrames + " must be positive");
        }
        mStartThresholdInFrames = Math.min(startThresholdInFrames, bufferSizeInFrames);
        return mStartThresholdInFrames;
    }

    /** API 29. Offload delay/padding describe gapless trim inside a compressed
     *  bitstream. Nothing here is offloaded, so AOSP's own answer for a
     *  non-offloaded track -- 0 -- is what is reported, matching
     *  isOffloadedPlayback() == false. */
    public int getOffloadDelay() { return 0; }
    public int getOffloadPadding() { return 0; }

    /** AOSP returns the performance mode actually granted, which need not be the
     *  one requested. CoreAudio gives this backend an ordinary shared-mixer
     *  stream, so PERFORMANCE_MODE_NONE is the truthful answer -- claiming
     *  LOW_LATENCY would tell an app to size buffers we cannot service. */
    public int getPerformanceMode() { return PERFORMANCE_MODE_NONE; }

    /**
     * AOSP: reloads a MODE_STATIC track's buffer so it can be replayed, and
     * returns ERROR_INVALID_OPERATION for a MODE_STREAM track. There is no
     * static-buffer path in this backend (see setPlaybackHeadPosition for the
     * same reasoning), so the error is the honest answer for every track rather
     * than a SUCCESS that reloaded nothing.
     */
    public int reloadStaticData() { return ERROR_INVALID_OPERATION; }

    // ---- dual mono / audio description (API 31) --------------------------
    // These fold or duck a broadcast stream's two channels. Both are HAL
    // features: AOSP forwards them to native_set_dual_mono_mode /
    // native_set_audio_description_mix_level_db, which report failure on
    // hardware that lacks them, and AOSP's own javadoc documents the setters as
    // returning false in that case. Reporting failure (and OFF / -Float.MAX)
    // keeps an app from believing a fold-down happened.

    public boolean setDualMonoMode(int dualMonoMode) {
        if (dualMonoMode < DUAL_MONO_MODE_OFF || dualMonoMode > DUAL_MONO_MODE_RR) {
            throw new IllegalArgumentException("Invalid dual mono mode " + dualMonoMode);
        }
        return false;
    }

    public int getDualMonoMode() { return DUAL_MONO_MODE_OFF; }

    /** AOSP: "-Float.MAX_VALUE means the audio description is muted", which is
     *  the state of a track with no audio-description mix. */
    public float getAudioDescriptionMixLeveldB() { return -Float.MAX_VALUE; }

    public boolean setAudioDescriptionMixLeveldB(float level) {
        if (level > 48.0f) {
            throw new IllegalArgumentException("level " + level + " must be <= 48.f");
        }
        return false;
    }

    /**
     * API 28. Selects one presentation out of a next-gen-audio bitstream.
     * AOSP returns ERROR_INVALID_OPERATION when the track is not playing an
     * encoded stream that carries presentations. Every track here carries
     * decoded PCM, so that is the terminal answer -- and it is the SAME answer a
     * real device gives for a PCM track.
     */
    public int setPresentation(AudioPresentation presentation) {
        java.util.Objects.requireNonNull(presentation, "presentation must not be null");
        return ERROR_INVALID_OPERATION;
    }

    /**
     * AOSP: sets the loop points of a MODE_STATIC track, returning
     * ERROR_INVALID_OPERATION for MODE_STREAM. There is no static-buffer path
     * here, so that error is returned for every track -- after the argument
     * validation AOSP performs first, so a caller with a genuinely bad range
     * still learns about it.
     */
    public int setLoopPoints(int startInFrames, int endInFrames, int loopCount) {
        if (dataLoadMode == MODE_STREAM || getState() == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        if (loopCount == 0) return SUCCESS;
        if (loopCount > 0 && endInFrames < startInFrames) return ERROR_BAD_VALUE;
        if (startInFrames < 0 || startInFrames >= bufferSizeInFrames
                || endInFrames > bufferSizeInFrames) {
            return ERROR_BAD_VALUE;
        }
        return ERROR_INVALID_OPERATION;
    }

    // ---- metrics / log session -------------------------------------------

    private android.media.metrics.LogSessionId mLogSessionId =
            android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;

    /** @NonNull in AOSP; LOG_SESSION_ID_NONE until the app sets one. */
    public android.media.metrics.LogSessionId getLogSessionId() { return mLogSessionId; }

    /**
     * AOSP's getMetrics() reports what the native track actually did. Only the
     * three descriptive keys AOSP publishes as public constants are filled in,
     * from this track's real configuration; the private native counters
     * (underrun totals, port ids) are omitted rather than filled with zeros,
     * because a zero counter reads as a measurement and would be a fabricated
     * one. AOSP returns null when there are no metrics at all, so a caller
     * already handles a thin bundle.
     */
    public android.os.PersistableBundle getMetrics() {
        android.os.PersistableBundle bundle = new android.os.PersistableBundle();
        bundle.putString(MetricsConstants.STREAMTYPE, Integer.toString(getStreamType()));
        bundle.putString(MetricsConstants.CONTENTTYPE,
                Integer.toString(AudioAttributes.CONTENT_TYPE_MUSIC));
        bundle.putString(MetricsConstants.USAGE, Integer.toString(AudioAttributes.USAGE_MEDIA));
        return bundle;
    }

    /** AOSP declares finalize() so a dropped track releases its native stream
     *  instead of leaking a CoreAudio node. release() is idempotent. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}




