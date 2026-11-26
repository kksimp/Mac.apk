package android.media;
public class AudioManager {
    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_SYSTEM = 1;
    public static final int FLAG_PLAY_SOUND = 0x4;

    // AOSP: setStreamVolume(streamType, index, flags) persists the index per
    // streamType (in AudioService); a later getStreamVolume(SAME streamType)
    // must read back what was just set. We have no AudioService, so the
    // index is persisted here instead. Sized to cover every STREAM_* index
    // declared below (0..STREAM_ACCESSIBILITY==10). Defaulted to 7 in the
    // constructor to match the flat value getStreamVolume() used to return
    // unconditionally, so an app that never calls setStreamVolume sees no
    // change. This does NOT drive the real CoreAudio output gain (that
    // needs an AVAudioEngine.mainMixerNode.outputVolume hook in
    // runtime/src/audio_backend.mm, a file this class does not own) -- it
    // only makes the Java-visible state honest for callers that read back
    // what they wrote (volume UI, mute toggles).
    private static final int STREAM_TYPE_COUNT = 11;
    private final int[] mStreamVolume = new int[STREAM_TYPE_COUNT];

    public AudioManager() {
        java.util.Arrays.fill(mStreamVolume, 7);
    }

    // HISTORY: getProperty(String) was deliberately ABSENT v1.0.270..v1.0.601.
    // libunity's native audio bringup does GetMethodID("getProperty", ...) and only
    // enters its FMOD output-config path if that lookup succeeds; that path then
    // faulted deep in libunity's audio-config code (+0x12e5xxx, x18=0 under the old
    // null-deref recovery) no matter what getProperty returned, so leaving the method
    // absent (GetMethodID -> NULL) was how Crossy/Unity reached their menu. v1.0.602:
    // the underlying +0x12e5xxx fault is fixed at the ROOT by preserve_x18 (v1.0.581 --
    // the kernel now saves/restores x18 across every EL0 exception, whole process), so
    // the x18=0 crash that forced the removal no longer occurs. getProperty is RE-ADDED
    // below (real Android has it; FMOD/MCPE needs it for audio output). Protected sweep
    // must confirm Crossy/Unity still reach their menu.

    public int  getStreamVolume(int streamType) {
        if (streamType < 0 || streamType >= mStreamVolume.length) return 7;
        return mStreamVolume[streamType];
    }
    public int  getStreamMaxVolume(int streamType) { return 15; }
    public void setStreamVolume(int streamType, int index, int flags) {
        if (streamType < 0 || streamType >= mStreamVolume.length) return;
        int min = getStreamMinVolume(streamType);
        int max = getStreamMaxVolume(streamType);
        if (index < min) index = min;
        if (index > max) index = max;
        mStreamVolume[streamType] = index;
    }
    // isMusicActive() stays hardcoded false: an honest answer needs to query
    // whether any Stream in audio_backend.mm's g_streams is PLAYSTATE_PLAYING
    // (real AOSP forwards to IAudioService.isMusicActive(false), which is
    // the same kind of live-engine query), and that file is native code this
    // class does not own. See followups.
    private volatile boolean mSpeakerphoneOn = true;
    private volatile boolean mBluetoothScoOn;
    private volatile boolean mBluetoothA2dpOn;
    private volatile boolean mMicrophoneMute;
    public boolean isMusicActive() { return false; }
    public void setSpeakerphoneOn(boolean on) { mSpeakerphoneOn = on; }
    public boolean isWiredHeadsetOn() { return false; }

    // Unity registers a bitter.jnibridge java.lang.reflect.Proxy implementing
    // this interface during native bringup; without the type, Proxy
    // .newProxyInstance throws ClassNotFound and aborts the bringup before the
    // render Choreographer callback is armed. Audio-focus is a no-op (we always
    // grant), which is the observable behaviour an offline player expects.
    public interface OnAudioFocusChangeListener {
        void onAudioFocusChange(int focusChange);
    }
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_LOSS = -1;
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType,
                                 int durationHint) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }
    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    // Audio-device enumeration (API 23+). GeoDash Meltdown calls
    // getDevices(GET_DEVICES_OUTPUTS) in onCreate; a missing method threw
    // NoSuchMethodError and aborted onCreate before the GL surface was built.
    // Mac.apk plays through one CoreAudio output, so we report a single
    // built-in speaker for output queries and nothing for input queries.
    public static final int GET_DEVICES_INPUTS  = 0x0001;
    public static final int GET_DEVICES_OUTPUTS = 0x0002;
    public static final int GET_DEVICES_ALL     = GET_DEVICES_INPUTS | GET_DEVICES_OUTPUTS;

    /**
     * ⚠ CORRECTED: the input branch used to return an empty array, which
     * contradicted the rest of this tree -- AudioRecord really does capture from
     * CoreAudio's default input (runtime/src/audio_input.mm) and
     * getMicrophones() describes that same microphone. An app that enumerated
     * inputs, found none, and therefore skipped recording was being told
     * something false about the device. Both directions are now reported, and
     * AudioRecord.getRoutedDevice() consequently answers with the real capture
     * device while recording instead of null.
     */
    public AudioDeviceInfo[] getDevices(int flags) {
        java.util.ArrayList<AudioDeviceInfo> out = new java.util.ArrayList<>(2);
        if ((flags & GET_DEVICES_OUTPUTS) != 0) out.add(AudioDeviceInfo.defaultOutput());
        if ((flags & GET_DEVICES_INPUTS) != 0) out.add(AudioDeviceInfo.defaultInput());
        return out.toArray(new AudioDeviceInfo[0]);
    }

    // Device hotplug callbacks. Our device set never changes, so no LATER
    // callback fires -- but AOSP delivers an INITIAL onAudioDevicesAdded with
    // the current list at registration time
    // (AudioManager.broadcastDeviceListChange_sync at registration,
    // android14-release AudioManager.java:8154-8167). Without it, an app that
    // builds its device model only from the callback -- device pickers, Oboe
    // style wrappers -- sees zero devices forever, and one that waits for the
    // first devices-added waits forever. getDevices() above already answers
    // correctly, so this is purely the missing delivery.
    public void registerAudioDeviceCallback(final AudioDeviceCallback callback,
                                            android.os.Handler handler) {
        if (callback == null) return;
        final AudioDeviceInfo[] devices = getDevices(GET_DEVICES_ALL);
        if (devices == null || devices.length == 0) return;
        Runnable r = new Runnable() { @Override public void run() {
            try { callback.onAudioDevicesAdded(devices); } catch (Throwable ignored) { }
        } };
        if (handler != null) { try { handler.post(r); return; } catch (Throwable ignored) { } }
        try { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
        catch (Throwable t) { r.run(); }
    }
    public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {}

    // v1.0.602: getProperty(String) -- RE-ADDED (was removed v1.0.270; see header).
    // FMOD's org.fmod.FMOD.supportsLowLatency() -> getOutputBlockSize() calls this
    // during audio-output bring-up; without it a NoSuchMethodError kills FMOD's init
    // thread and its AAudio mixer callback then emits only SILENCE (Minecraft Bedrock's
    // menu music was inaudible for exactly this reason -- FMOD ran but produced no PCM).
    // The original removal dodged a Unity fault (libunity +0x12e5xxx, x18=0) that took
    // the same path once GetMethodID succeeded; that fault is fixed at the ROOT by
    // preserve_x18 (v1.0.581: the kernel saves/restores x18 across EL0 exceptions,
    // whole process), so re-exposing the method is safe. Verify Crossy/Unity on the
    // protected sweep. AOSP returns null for unsupported/unknown keys; we report our
    // CoreAudio engine's real output values (48000 Hz / 256-frame burst) for the two
    // keys FMOD queries.
    public static final String PROPERTY_OUTPUT_SAMPLE_RATE =
        "android.media.property.OUTPUT_SAMPLE_RATE";
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER =
        "android.media.property.OUTPUT_FRAMES_PER_BUFFER";
    public static final String PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND =
        "android.media.property.SUPPORT_MIC_NEAR_ULTRASOUND";
    public static final String PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND =
        "android.media.property.SUPPORT_SPEAKER_NEAR_ULTRASOUND";
    public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED =
        "android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED";
    public String getProperty(String key) {
        if (key == null) return null;
        if (key.equals(PROPERTY_OUTPUT_SAMPLE_RATE))       return "48000";
        if (key.equals(PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) return "256";
        return null;   // AOSP: null for unsupported/unknown keys
    }

    // ----- additive: broader AudioManager surface (AOSP frameworks/base
    // media/java/android/media/AudioManager.java). Routing/mode/effects have no
    // backend, so they record-and-grant; volume queries report a sensible fixed
    // device. getProperty(String) is implemented above (v1.0.602, see header).

    public static final int STREAM_VOICE_CALL = 0;
    public static final int STREAM_RING       = 2;
    public static final int STREAM_ALARM      = 4;
    public static final int STREAM_NOTIFICATION = 5;
    public static final int STREAM_DTMF       = 8;
    public static final int STREAM_ACCESSIBILITY = 10;

    public static final int MODE_INVALID         = -2;
    public static final int MODE_CURRENT         = -1;
    public static final int MODE_NORMAL          = 0;
    public static final int MODE_RINGTONE        = 1;
    public static final int MODE_IN_CALL         = 2;
    public static final int MODE_IN_COMMUNICATION = 3;

    public static final int RINGER_MODE_SILENT  = 0;
    public static final int RINGER_MODE_VIBRATE = 1;
    public static final int RINGER_MODE_NORMAL  = 2;

    public static final int ADJUST_RAISE  = 1;
    public static final int ADJUST_LOWER  = -1;
    public static final int ADJUST_SAME   = 0;
    public static final int ADJUST_MUTE   = -100;
    public static final int ADJUST_UNMUTE = 100;
    public static final int ADJUST_TOGGLE_MUTE = 101;

    public static final int AUDIOFOCUS_NONE         = 0;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT = 2;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -2;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;

    public static final int FX_KEY_CLICK            = 0;
    public static final int FX_FOCUS_NAVIGATION_UP  = 1;
    public static final int FX_FOCUS_NAVIGATION_DOWN = 2;
    public static final int FX_FOCUS_NAVIGATION_LEFT = 3;
    public static final int FX_FOCUS_NAVIGATION_RIGHT = 4;
    // The rest of the public SDK-34 constant surface (android14-release
    // AudioManager.java:3800-3827, 2967-2998, 3279-3294, 7039-7057, 883 and
    // 140-381; values as the SDK android.jar resolves them). javac inlines
    // these into app code, so their absence only showed up to reflection, but
    // a systematic javap-vs-SDK pass should leave nothing for the next one.
    public static final int FX_KEYPRESS_STANDARD = 5;
    public static final int FX_KEYPRESS_SPACEBAR = 6;
    public static final int FX_KEYPRESS_DELETE = 7;
    public static final int FX_KEYPRESS_RETURN = 8;
    public static final int FX_KEYPRESS_INVALID = 9;
    public static final int FX_BACK = 10;
    public static final int ERROR = -1;
    public static final int ERROR_DEAD_OBJECT = -6;
    public static final int USE_DEFAULT_STREAM_TYPE = Integer.MIN_VALUE;
    public static final int SCO_AUDIO_STATE_DISCONNECTED = 0;
    public static final int SCO_AUDIO_STATE_CONNECTED = 1;
    public static final int SCO_AUDIO_STATE_CONNECTING = 2;
    public static final int SCO_AUDIO_STATE_ERROR = -1;
    public static final String ACTION_SCO_AUDIO_STATE_UPDATED =
            "android.media.ACTION_SCO_AUDIO_STATE_UPDATED";
    public static final String EXTRA_SCO_AUDIO_STATE = "android.media.extra.SCO_AUDIO_STATE";
    public static final String EXTRA_SCO_AUDIO_PREVIOUS_STATE =
            "android.media.extra.SCO_AUDIO_PREVIOUS_STATE";
    public static final String ACTION_HDMI_AUDIO_PLUG = "android.media.action.HDMI_AUDIO_PLUG";
    public static final String EXTRA_AUDIO_PLUG_STATE = "android.media.extra.AUDIO_PLUG_STATE";
    public static final String EXTRA_MAX_CHANNEL_COUNT = "android.media.extra.MAX_CHANNEL_COUNT";
    public static final String EXTRA_ENCODINGS = "android.media.extra.ENCODINGS";
    public static final String ACTION_MICROPHONE_MUTE_CHANGED =
            "android.media.action.MICROPHONE_MUTE_CHANGED";
    public static final String ACTION_SPEAKERPHONE_STATE_CHANGED =
            "android.media.action.SPEAKERPHONE_STATE_CHANGED";
    public static final String RINGER_MODE_CHANGED_ACTION = "android.media.RINGER_MODE_CHANGED";
    public static final String EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE";

    public static final String ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY";
    public static final String ACTION_HEADSET_PLUG = "android.intent.action.HEADSET_PLUG";

    private int mMode = MODE_NORMAL;
    private final java.util.concurrent.atomic.AtomicInteger mSessionIds =
        new java.util.concurrent.atomic.AtomicInteger(1);

    public void setMode(int mode) {
        if (mMode == mode) return;
        mMode = mode;
        // API 31 listeners registered with addOnModeChangedListener really do
        // fire: the mode is state this class owns, so there is nothing to fake.
        for (Object[] entry : mModeListeners) {
            final java.util.concurrent.Executor executor = (java.util.concurrent.Executor) entry[0];
            final OnModeChangedListener listener = (OnModeChangedListener) entry[1];
            executor.execute(new Runnable() {
                @Override public void run() { listener.onModeChanged(mMode); }
            });
        }
    }
    public int getMode() { return mMode; }
    public int getRingerMode() { return RINGER_MODE_NORMAL; }
    public void setRingerMode(int ringerMode) {}
    public boolean isSpeakerphoneOn() { return mSpeakerphoneOn; }
    public boolean isBluetoothScoOn() { return mBluetoothScoOn; }
    /** AOSP: whether the platform supports off-call Bluetooth SCO (a device
     *  capability read from config_bluetooth_sco_off_call). We declare no
     *  FEATURE_BLUETOOTH and route audio through CoreAudio, so there is no SCO
     *  link at all -- false is the honest answer and it matches every other
     *  Bluetooth answer we give. Two corpus apps reference it. */
    public boolean isBluetoothScoAvailableOffCall() { return false; }

    /** AOSP (API 33) getDirectPlaybackSupport: whether the given format can be
     *  played back directly (offload or bitstream passthrough) with those
     *  attributes. Everything we output is decoded PCM through CoreAudio, so the
     *  answer is DIRECT_PLAYBACK_NOT_SUPPORTED -- an app that got a non-zero
     *  answer would hand us an encoded bitstream we cannot pass anywhere.
     *
     *  It is STATIC, as in AOSP (android14-release AudioManager.java:2839,
     *  `public static int getDirectPlaybackSupport(...)`): app bytecode calls it
     *  with invokestatic, and an instance declaration here makes that call site
     *  throw IncompatibleClassChangeError. The values are AudioSystem's
     *  DIRECT_* bits as the SDK publishes them (OFFLOAD 1, OFFLOAD_GAPLESS 3,
     *  BITSTREAM 4). There is no DIRECT_PLAYBACK_PASSTHROUGH_SUPPORTED in AOSP;
     *  an earlier invented one (value 2) is gone. GAPLESS is 3
     *  (AudioSystem.java:1991), so it carries the OFFLOAD bit, which is what
     *  AOSP's doc promises ("if GAPLESS is present, OFFLOAD will be too"). */
    public static final int DIRECT_PLAYBACK_NOT_SUPPORTED = 0;
    public static final int DIRECT_PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 3;
    public static final int DIRECT_PLAYBACK_BITSTREAM_SUPPORTED = 4;
    public static int getDirectPlaybackSupport(android.media.AudioFormat format,
                                               android.media.AudioAttributes attributes) {
        // AOSP: Objects.requireNonNull(format); Objects.requireNonNull(attributes);
        java.util.Objects.requireNonNull(format);
        java.util.Objects.requireNonNull(attributes);
        return DIRECT_PLAYBACK_NOT_SUPPORTED;
    }
    public void setBluetoothScoOn(boolean on) { mBluetoothScoOn = on; }
    public void startBluetoothSco() {}
    public void stopBluetoothSco() {}
    public boolean isBluetoothA2dpOn() { return mBluetoothA2dpOn; }
    public void setBluetoothA2dpOn(boolean on) { mBluetoothA2dpOn = on; }
    public boolean isMicrophoneMute() { return mMicrophoneMute; }
    public void setMicrophoneMute(boolean on) { mMicrophoneMute = on; }
    public boolean isVolumeFixed() { return false; }

    // AOSP: an opaque, monotonically increasing audio-session id. cocos2d-x and
    // ExoPlayer call this to tag effects/tracks; -1 / 0 are reserved sentinels.
    public int generateAudioSessionId() { return mSessionIds.incrementAndGet(); }

    public void adjustVolume(int direction, int flags) {}
    public void adjustStreamVolume(int streamType, int direction, int flags) {}
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {}
    public int getStreamMinVolume(int streamType) { return 0; }
    public boolean isStreamMute(int streamType) { return false; }
    public void setStreamMute(int streamType, boolean state) {}

    // Plays a UI sound effect (key click, focus nav). No backend wiring, but the
    // method must exist: View.playSoundEffect routes here on every click.
    public void playSoundEffect(int effectType) {}
    public void playSoundEffect(int effectType, float volume) {}
    public void loadSoundEffects() {}
    public void unloadSoundEffects() {}

    public java.util.List<android.media.AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        return new java.util.ArrayList<android.media.AudioPlaybackConfiguration>();
    }

    // ---- recording-activity notification (API 24) ----------------------------
    // AOSP frameworks/base media/java/android/media/AudioManager.java:4037-4160.
    // On Android the registry lives in AudioService and AudioManager reaches it
    // over IAudioService; here it lives in AudioRecordingActivityMonitor, which
    // is the in-process analogue (see that file for why it is static, and for
    // the AudioRecord wiring that is still missing).

    /**
     * Extend this and register it with
     * {@link #registerAudioRecordingCallback(AudioRecordingCallback, android.os.Handler)}
     * to be told when the device's recording configuration changes.
     */
    public static abstract class AudioRecordingCallback {
        /**
         * @param configs the results of {@link #getActiveRecordingConfigurations()}
         *                at the moment of the change.
         */
        public void onRecordingConfigChanged(
                java.util.List<android.media.AudioRecordingConfiguration> configs) {}
    }

    private static class AudioRecordingCallbackInfo {
        final AudioRecordingCallback mCb;
        final android.os.Handler mHandler;
        AudioRecordingCallbackInfo(AudioRecordingCallback cb, android.os.Handler handler) {
            mCb = cb;
            mHandler = handler;
        }
    }

    private final Object mRecordCallbackLock = new Object();
    /** Lazily created, exactly as AOSP does -- the null state is what tells
     *  unregister that nothing was ever registered. */
    private java.util.ArrayList<AudioRecordingCallbackInfo> mRecordCallbackList;

    /**
     * Register a callback for changes in the device's recording configuration.
     *
     * @param cb non-null; a null argument is IllegalArgumentException on AOSP
     *           and stays one here, because an app that passes null has a bug
     *           and silently accepting it hides the bug until the callback that
     *           never arrives is blamed on the platform.
     * @param handler the Handler whose thread runs the callback. Null means the
     *                caller's Looper, or the main Looper if it has none --
     *                AOSP's ServiceEventHandlerDelegate rule, verbatim.
     */
    public void registerAudioRecordingCallback(AudioRecordingCallback cb,
                                               android.os.Handler handler) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }
        synchronized (mRecordCallbackLock) {
            if (mRecordCallbackList == null) {
                mRecordCallbackList = new java.util.ArrayList<AudioRecordingCallbackInfo>();
            }
            final int oldCbCount = mRecordCallbackList.size();
            if (hasRecordCallback_sync(cb)) {
                // AOSP logs and returns rather than double-registering, so the
                // callback still fires exactly once.
                return;
            }
            mRecordCallbackList.add(new AudioRecordingCallbackInfo(cb, resolveHandler(handler)));
            if (oldCbCount == 0) {
                // AOSP: service.registerRecordingCallback(mRecCb) on the 0 -> 1
                // transition only.
                AudioRecordingActivityMonitor.registerDispatcher(this);
            }
        }
    }

    /** Unregister a callback registered with
     *  {@link #registerAudioRecordingCallback(AudioRecordingCallback, android.os.Handler)}. */
    public void unregisterAudioRecordingCallback(AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }
        synchronized (mRecordCallbackLock) {
            if (mRecordCallbackList == null) {
                return;
            }
            final int oldCbCount = mRecordCallbackList.size();
            if (removeRecordCallback_sync(cb) && oldCbCount > 0
                    && mRecordCallbackList.isEmpty()) {
                AudioRecordingActivityMonitor.unregisterDispatcher(this);
            }
        }
    }

    /** AOSP ServiceEventHandlerDelegate: the supplied Handler's Looper, else
     *  the calling thread's, else the main one. */
    private static android.os.Handler resolveHandler(android.os.Handler handler) {
        if (handler != null) return handler;
        android.os.Looper looper = android.os.Looper.myLooper();
        if (looper == null) looper = android.os.Looper.getMainLooper();
        return (looper != null) ? new android.os.Handler(looper) : null;
    }

    private boolean hasRecordCallback_sync(AudioRecordingCallback cb) {
        for (int i = 0; i < mRecordCallbackList.size(); i++) {
            if (cb.equals(mRecordCallbackList.get(i).mCb)) return true;
        }
        return false;
    }

    private boolean removeRecordCallback_sync(AudioRecordingCallback cb) {
        for (int i = 0; i < mRecordCallbackList.size(); i++) {
            if (cb.equals(mRecordCallbackList.get(i).mCb)) {
                mRecordCallbackList.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Called by AudioRecordingActivityMonitor when the active set changes.
     *  Package-private: this is the in-process stand-in for AOSP's
     *  IRecordingConfigDispatcher binder callback, not app-visible API. */
    void dispatchRecordingConfigChange(
            final java.util.List<android.media.AudioRecordingConfiguration> configs) {
        final AudioRecordingCallbackInfo[] targets;
        synchronized (mRecordCallbackLock) {
            if (mRecordCallbackList == null || mRecordCallbackList.isEmpty()) return;
            targets = mRecordCallbackList.toArray(new AudioRecordingCallbackInfo[0]);
        }
        // Copied out of the lock first: AOSP delivers through a Handler, so a
        // callback that unregisters itself is legal and must not deadlock.
        for (final AudioRecordingCallbackInfo info : targets) {
            if (info.mHandler == null) {
                info.mCb.onRecordingConfigChanged(configs);
                continue;
            }
            info.mHandler.post(new Runnable() {
                @Override public void run() {
                    info.mCb.onRecordingConfigChanged(configs);
                }
            });
        }
    }

    /**
     * The device's currently active recording configurations.
     *
     * AOSP contract: never null; an EMPTY list means nothing is recording. That
     * is what this returns today -- see AudioRecordingActivityMonitor for why
     * (AudioRecord does not yet announce itself to the registry). An empty list
     * is the honest answer for "nothing is registered as recording"; it is NOT a
     * fabricated configuration, and it is the same answer real Android gives an
     * idle device.
     */
    public java.util.List<android.media.AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        return AudioRecordingActivityMonitor.getActiveRecordingConfigurations();
    }

    public String getParameters(String keys) { return ""; }
    public void setParameters(String keyValuePairs) {}

    // Modern audio-focus API (API 26+). We always grant, mirroring the legacy
    // overload, and never deliver loss (one app, one output).
    public int requestAudioFocus(android.media.AudioFocusRequest focusRequest) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }
    public int abandonAudioFocusRequest(android.media.AudioFocusRequest focusRequest) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    // ----- Legacy media-button registration (AOSP frameworks/base
    // media/java/android/media/AudioManager.java, lines ~5304-5389:
    // @Deprecated since API 21/26, but still present, still callable, and
    // still a documented no-throw-on-null contract on real devices --
    // real AOSP routes both overloads through MediaSessionLegacyHelper
    // after a null check). Mac.apk has no MediaSessionLegacyHelper and no
    // hardware media-button dispatch, so these mirror only the part of the
    // contract an app can observe: the call does not throw. AOSP's
    // ComponentName overload additionally rejects (logs + returns) a
    // receiver whose package differs from the calling Context's; we have
    // no Context wired into AudioManager to compare against, so that
    // branch is omitted -- the omission only makes us accept a call real
    // Android would have rejected, never the reverse, so no app that
    // worked on real Android can regress here.
    //
    // registerRemoteControlClient/unregisterRemoteControlClient(RemoteControlClient)
    // are NOT added: RemoteControlClient does not exist anywhere in this
    // tree and adding it is out of scope for this file.
    public void registerMediaButtonEventReceiver(android.content.ComponentName eventReceiver) {
        if (eventReceiver == null) return;
    }
    public void registerMediaButtonEventReceiver(android.app.PendingIntent eventReceiver) {
        if (eventReceiver == null) return;
    }
    public void unregisterMediaButtonEventReceiver(android.content.ComponentName eventReceiver) {
        if (eventReceiver == null) return;
    }
    public void unregisterMediaButtonEventReceiver(android.app.PendingIntent eventReceiver) {
        if (eventReceiver == null) return;
    }

    // v1.0.687: the audio-capability query + legacy-transport tail.
    // Honest "feature absent" answers for a device whose audio HAL has no
    // offload / spatializer / haptic path, matching AOSP on such hardware.
    // Source: frameworks/base/media/java/android/media/AudioManager.java

    private android.media.Spatializer mSpatializer;
    public android.media.Spatializer getSpatializer() {
        if (mSpatializer == null) mSpatializer = new android.media.Spatializer();
        return mSpatializer;
    }

    public static final int PLAYBACK_OFFLOAD_NOT_SUPPORTED = 0;
    public static final int PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 2;

    // CoreAudio path has no compressed-offload; AOSP returns NOT_SUPPORTED /
    // false on a device without an offload-capable HAL. Apps fall back to
    // normal PCM playback, which is what we provide.
    //
    // All three are STATIC in AOSP (android14-release AudioManager.java:2860,
    // 2910 and 8072) and in the SDK-34 android.jar. App bytecode therefore
    // reaches them with invokestatic, and while they were instance methods here
    // every such call threw IncompatibleClassChangeError ("Expected static
    // method"). Measured: Duolingo's onboarding haptics runnable died on the
    // main looper twice per launch (fleet A/B 2026-09-24, run.log L26719).
    // The null checks are AOSP's own: it throws NPE before asking AudioSystem.
    public static int getPlaybackOffloadSupport(AudioFormat format, AudioAttributes attributes) {
        if (format == null) throw new NullPointerException("Illegal null AudioFormat");
        if (attributes == null) throw new NullPointerException("Illegal null AudioAttributes");
        return PLAYBACK_OFFLOAD_NOT_SUPPORTED;
    }
    public static boolean isOffloadedPlaybackSupported(AudioFormat format, AudioAttributes attributes) {
        if (format == null) throw new NullPointerException("Illegal null AudioFormat");
        if (attributes == null) throw new NullPointerException("Illegal null AudioAttributes");
        return getPlaybackOffloadSupport(format, attributes) != PLAYBACK_OFFLOAD_NOT_SUPPORTED;
    }
    // AOSP asks AudioSystem.isHapticPlaybackSupported(), i.e. whether the audio
    // HAL can carry haptic channels coupled to an audio stream. CoreAudio has no
    // such channel, so false is the honest answer on this host.
    public static boolean isHapticPlaybackSupported() { return false; }

    public java.util.List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes attributes) {
        // The single synthetic output device we already report from getDevices.
        java.util.ArrayList<AudioDeviceInfo> out = new java.util.ArrayList<>();
        out.add(new AudioDeviceInfo());
        return out;
    }
    public java.util.List<AudioProfile> getDirectProfilesForAttributes(AudioAttributes attributes) {
        return java.util.Collections.emptyList();
    }

    // ---- communication-device selection (VoIP routing) -----------------------
    // We do not route to a selectable comm device; AOSP returns null/empty/false
    // on a device with no such capability.
    public AudioDeviceInfo getCommunicationDevice() { return null; }
    public java.util.List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        return java.util.Collections.emptyList();
    }
    public boolean setCommunicationDevice(AudioDeviceInfo device) { return false; }
    public void clearCommunicationDevice() { }

    // ---- legacy RemoteControlClient (deprecated API 21) ----------------------
    // No lock screen / AVRCP transport to publish to; accepted and dropped.
    public void registerRemoteControlClient(RemoteControlClient rcClient) { }
    public void unregisterRemoteControlClient(RemoteControlClient rcClient) { }

    // =====================================================================
    // v1.0.10xx API-gap close-out. Source for every member below:
    // frameworks/base/media/java/android/media/AudioManager.java
    // (android16-release) and /tmp/aosp_api/android16.txt for the exact
    // signatures. Where a capability genuinely does not exist on this host the
    // answer is AOSP's own documented "not supported" value -- never a value
    // that would make an app believe it can do something we cannot honour.
    // =====================================================================

    // ---- constants that had no home yet ----------------------------------
    public static final int AUDIO_SESSION_ID_GENERATE = 0;

    public static final int MODE_CALL_SCREENING = 4;
    public static final int MODE_CALL_REDIRECT  = 5;
    public static final int MODE_COMMUNICATION_REDIRECT = 6;

    public static final int FLAG_SHOW_UI               = 0x1;
    public static final int FLAG_ALLOW_RINGER_MODES    = 0x2;
    public static final int FLAG_REMOVE_SOUND_AND_VIBRATE = 0x8;
    public static final int FLAG_VIBRATE               = 0x10;

    public static final int ENCODED_SURROUND_OUTPUT_UNKNOWN = -1;
    public static final int ENCODED_SURROUND_OUTPUT_AUTO    = 0;
    public static final int ENCODED_SURROUND_OUTPUT_NEVER   = 1;
    public static final int ENCODED_SURROUND_OUTPUT_ALWAYS  = 2;
    public static final int ENCODED_SURROUND_OUTPUT_MANUAL  = 3;

    /** @deprecated in AOSP since API 2; superseded by getRingerMode(). */
    @Deprecated public static final int NUM_STREAMS = 5;
    @Deprecated public static final int ROUTE_EARPIECE       = 1 << 0;
    @Deprecated public static final int ROUTE_SPEAKER        = 1 << 1;
    @Deprecated public static final int ROUTE_BLUETOOTH      = 1 << 2;
    @Deprecated public static final int ROUTE_BLUETOOTH_SCO  = 1 << 2;
    @Deprecated public static final int ROUTE_HEADSET        = 1 << 3;
    @Deprecated public static final int ROUTE_BLUETOOTH_A2DP = 1 << 4;
    @Deprecated public static final int ROUTE_ALL            = 0xFFFFFFFF;

    @Deprecated public static final int VIBRATE_TYPE_RINGER = 0;
    @Deprecated public static final int VIBRATE_TYPE_NOTIFICATION = 1;
    @Deprecated public static final int VIBRATE_SETTING_OFF = 0;
    @Deprecated public static final int VIBRATE_SETTING_ON = 1;
    @Deprecated public static final int VIBRATE_SETTING_ONLY_SILENT = 2;
    @Deprecated public static final String VIBRATE_SETTING_CHANGED_ACTION =
            "android.media.VIBRATE_SETTING_CHANGED";
    @Deprecated public static final String EXTRA_VIBRATE_SETTING =
            "android.media.EXTRA_VIBRATE_SETTING";
    @Deprecated public static final String EXTRA_VIBRATE_TYPE =
            "android.media.EXTRA_VIBRATE_TYPE";
    @Deprecated public static final String ACTION_SCO_AUDIO_STATE_CHANGED =
            "android.media.SCO_AUDIO_STATE_CHANGED";

    // ---- callbacks / listener interfaces ---------------------------------

    /** Playback-activity notifications (API 26). */
    public static abstract class AudioPlaybackCallback {
        public AudioPlaybackCallback() { }
        public void onPlaybackConfigChanged(
                java.util.List<android.media.AudioPlaybackConfiguration> configs) {}
    }

    /** API 31. Fired when setCommunicationDevice() changes the VoIP route. */
    public interface OnCommunicationDeviceChangedListener {
        void onCommunicationDeviceChanged(AudioDeviceInfo device);
    }

    /** API 31. Fired when setMode() changes the audio mode. */
    public interface OnModeChangedListener {
        void onModeChanged(int mode);
    }

    /** API 34. Fired when the preferred mixer attributes for a device change. */
    public interface OnPreferredMixerAttributesChangedListener {
        void onPreferredMixerAttributesChanged(AudioAttributes attributes,
                AudioDeviceInfo device, AudioMixerAttributes mixerAttributes);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mPlaybackCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mCommDeviceListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mModeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mMixerAttrListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** AOSP throws IllegalArgumentException on a null callback
     *  (AudioManager.java, registerAudioPlaybackCallback). Registration is real
     *  -- the list is kept and de-duplicated exactly as AOSP does -- but no
     *  callback fires yet: nothing in this tree announces playback state to a
     *  registry (the same gap AudioRecordingActivityMonitor documents for the
     *  recording side). An empty stream of events is the honest answer; a
     *  fabricated one would be a lie the app cannot detect. */
    public void registerAudioPlaybackCallback(AudioPlaybackCallback cb,
            android.os.Handler handler) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        }
        for (Object[] entry : mPlaybackCallbacks) {
            if (entry[0] == cb) return;
        }
        mPlaybackCallbacks.add(new Object[] { cb, resolveHandler(handler) });
    }

    public void unregisterAudioPlaybackCallback(AudioPlaybackCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        }
        for (Object[] entry : mPlaybackCallbacks) {
            if (entry[0] == cb) { mPlaybackCallbacks.remove(entry); return; }
        }
    }

    /** API 31. AOSP requires non-null executor + listener and throws
     *  IllegalArgumentException on a duplicate registration. */
    public void addOnCommunicationDeviceChangedListener(
            java.util.concurrent.Executor executor,
            OnCommunicationDeviceChangedListener listener) {
        addListener(mCommDeviceListeners, executor, listener);
    }

    public void removeOnCommunicationDeviceChangedListener(
            OnCommunicationDeviceChangedListener listener) {
        removeListener(mCommDeviceListeners, listener);
    }

    /** API 31. Unlike the two neighbours below, this one really does fire:
     *  setMode() is a state this class owns, so a listener registered here is
     *  notified on every actual change. */
    public void addOnModeChangedListener(java.util.concurrent.Executor executor,
            OnModeChangedListener listener) {
        addListener(mModeListeners, executor, listener);
    }

    public void removeOnModeChangedListener(OnModeChangedListener listener) {
        removeListener(mModeListeners, listener);
    }

    /** API 34. Preferred mixer attributes can never be SET on this host (see
     *  setPreferredMixerAttributes), so this listener can never fire. */
    public void addOnPreferredMixerAttributesChangedListener(
            java.util.concurrent.Executor executor,
            OnPreferredMixerAttributesChangedListener listener) {
        addListener(mMixerAttrListeners, executor, listener);
    }

    public void removeOnPreferredMixerAttributesChangedListener(
            OnPreferredMixerAttributesChangedListener listener) {
        removeListener(mMixerAttrListeners, listener);
    }

    private static void addListener(java.util.concurrent.CopyOnWriteArrayList<Object[]> list,
            java.util.concurrent.Executor executor, Object listener) {
        java.util.Objects.requireNonNull(executor, "executor must not be null");
        java.util.Objects.requireNonNull(listener, "listener must not be null");
        for (Object[] entry : list) {
            if (entry[1] == listener) {
                throw new IllegalArgumentException("attempt to call addListener() on a previously"
                        + " registered listener");
            }
        }
        list.add(new Object[] { executor, listener });
    }

    private static void removeListener(java.util.concurrent.CopyOnWriteArrayList<Object[]> list,
            Object listener) {
        java.util.Objects.requireNonNull(listener, "listener must not be null");
        for (Object[] entry : list) {
            if (entry[1] == listener) { list.remove(entry); return; }
        }
        throw new IllegalArgumentException("attempt to call removeListener() on a listener"
                + " that was never registered");
    }

    // ---- capture policy (API 29) -----------------------------------------
    // A REAL setting: AudioAttributes.Builder.setAllowedCapturePolicy already
    // models the per-attributes value, and this is the process-wide default the
    // app sets. Nothing here can capture another app's audio anyway (one app per
    // process), so the policy is honoured trivially -- but it must READ BACK
    // what was written, which is the only part an app can observe.
    private int mAllowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_ALL;

    public void setAllowedCapturePolicy(int capturePolicy) {
        mAllowedCapturePolicy = capturePolicy;
    }
    public int getAllowedCapturePolicy() { return mAllowedCapturePolicy; }

    // ---- microphones (API 28) --------------------------------------------
    /**
     * AOSP: "Returns a list of MicrophoneInfo that corresponds to the
     * characteristics of all available microphones", throwing IOException if the
     * list cannot be obtained. Mac.apk records through CoreAudio's default
     * input, so exactly one built-in microphone is reported -- consistent with
     * getDevices(GET_DEVICES_INPUTS) and with AudioRecord actually working. The
     * per-microphone geometry CoreAudio does not publish is reported with AOSP's
     * own unknown-sentinels; see MicrophoneInfo.
     */
    public java.util.List<MicrophoneInfo> getMicrophones() throws java.io.IOException {
        java.util.ArrayList<MicrophoneInfo> mics = new java.util.ArrayList<>(1);
        mics.add(MicrophoneInfo.defaultBuiltIn(/* id */ 1, /* address */ ""));
        return mics;
    }

    // ---- mixer attributes (API 34) ---------------------------------------
    /**
     * AOSP returns the mixer configurations the given output device supports.
     * Bit-perfect / exclusive-mode delivery is the whole point of this API, and
     * Mac.apk cannot provide it: CoreAudio owns the mix and every stream goes
     * through it. An EMPTY list is AOSP's documented answer for a device with no
     * such support, and it is the only honest one -- reporting a supported mixer
     * configuration we cannot honour is exactly the failure that shows up later
     * as silence or a garbled bitstream.
     */
    public java.util.List<AudioMixerAttributes> getSupportedMixerAttributes(
            AudioDeviceInfo device) {
        java.util.Objects.requireNonNull(device, "device must not be null");
        return java.util.Collections.emptyList();
    }

    /** AOSP returns false when the request could not be applied. Nothing is
     *  supported here (see getSupportedMixerAttributes), so it always is. */
    public boolean setPreferredMixerAttributes(AudioAttributes attributes,
            AudioDeviceInfo device, AudioMixerAttributes mixerAttributes) {
        java.util.Objects.requireNonNull(attributes, "attributes must not be null");
        java.util.Objects.requireNonNull(device, "device must not be null");
        java.util.Objects.requireNonNull(mixerAttributes, "mixerAttributes must not be null");
        return false;
    }

    /** @Nullable in AOSP: null means "no preference has been set for this
     *  (attributes, device) pair", which is permanently true here. */
    public AudioMixerAttributes getPreferredMixerAttributes(AudioAttributes attributes,
            AudioDeviceInfo device) {
        java.util.Objects.requireNonNull(attributes, "attributes must not be null");
        java.util.Objects.requireNonNull(device, "device must not be null");
        return null;
    }

    /** AOSP returns false when there was nothing to clear. */
    public boolean clearPreferredMixerAttributes(AudioAttributes attributes,
            AudioDeviceInfo device) {
        java.util.Objects.requireNonNull(attributes, "attributes must not be null");
        java.util.Objects.requireNonNull(device, "device must not be null");
        return false;
    }

    // ---- volume groups (API 30) ------------------------------------------
    /**
     * AOSP maps AudioAttributes onto a "volume group" -- a set of streams that
     * move together -- and returns DEFAULT_VOLUME_GROUP (-1) when the attributes
     * match no group. Mac.apk has no volume-group configuration at all (that is
     * an automotive/OEM audio-policy XML feature), so -1 is the correct answer
     * for every input, and it is AOSP's own no-group sentinel rather than an
     * invented one.
     */
    public int getVolumeGroupIdForAttributes(AudioAttributes attributes) {
        java.util.Objects.requireNonNull(attributes, "attributes must not be null");
        return -1;  // AudioVolumeGroup.DEFAULT_VOLUME_GROUP
    }

    /** No volume groups exist (see getVolumeGroupIdForAttributes), so nothing
     *  to adjust and nothing that could be muted. */
    public void adjustVolumeGroupVolume(int groupId, int direction, int flags) { }
    public boolean isVolumeGroupMuted(int groupId) { return false; }

    // ---- surround-sound formats (API 29) ---------------------------------
    /**
     * Encoded-surround output means passing an untouched AC3/E-AC3/DTS
     * bitstream to an external decoder over HDMI or S/PDIF. Everything Mac.apk
     * outputs is decoded PCM through CoreAudio, which is the same position
     * getDirectPlaybackSupport already reports. So: no format is enabled, the
     * mode is NEVER, and the setters return false ("could not be applied").
     * Consistency matters more than generosity here -- an app that was told a
     * surround format was enabled would hand us a bitstream with nowhere to go.
     */
    public boolean isSurroundFormatEnabled(int audioFormat) { return false; }
    public boolean setSurroundFormatEnabled(int audioFormat, boolean enabled) { return false; }
    public int getEncodedSurroundMode() { return ENCODED_SURROUND_OUTPUT_NEVER; }
    public boolean setEncodedSurroundMode(int mode) { return false; }

    // ---- misc queries ----------------------------------------------------
    /**
     * AOSP: the volume in dB that the given (stream, index, device) triple would
     * produce, used by apps that want to draw a real dB scale. AOSP's own answer
     * comes from the HAL's volume curve; the shape it always has is a linear-in-dB
     * ramp from a floor at index 0 to 0 dB at max. That same curve is computed
     * here from the stream's real min/max, so a slider drawn from these numbers is
     * monotonic and correctly shaped rather than flat, and Float.NEGATIVE_INFINITY
     * is returned at the mute index exactly as AOSP does.
     */
    public float getStreamVolumeDb(int streamType, int index, int deviceType) {
        int min = getStreamMinVolume(streamType);
        int max = getStreamMaxVolume(streamType);
        if (index < min || index > max) {
            throw new IllegalArgumentException("Invalid stream volume index " + index);
        }
        if (max <= min) return 0.0f;
        if (index <= min) return Float.NEGATIVE_INFINITY;
        // -58 dB at the first audible step is AOSP's typical curve floor
        // (frameworks/base audio_policy volume curves, DEVICE_CATEGORY_SPEAKER).
        final float floorDb = -58.0f;
        float fraction = (float) (index - min) / (float) (max - min);
        return floorDb * (1.0f - fraction);
    }

    /** API 34. Which AudioDeviceInfo.TYPE_* values this device can EVER expose
     *  in the given direction. Derived from getDevices() rather than listed
     *  separately, so the two can never contradict each other. */
    public java.util.Set<Integer> getSupportedDeviceTypes(int direction) {
        java.util.Set<Integer> types = new java.util.HashSet<>();
        for (AudioDeviceInfo device : getDevices(direction)) {
            types.add(device.getType());
        }
        return types;
    }

    /**
     * AOSP: the HW A/V sync id associated with an audio session, or
     * AUDIO_HW_SYNC_INVALID (0) when the session has none. Hardware A/V sync is
     * a tunneled-playback feature of the audio HAL; there is no such path here,
     * so 0 -- AOSP's own "no sync id" value -- is correct for every session.
     */
    public int getAudioHwSyncForSession(int sessionId) { return 0; }

    /** API 30. AOSP reports whether the platform supports MODE_CALL_SCREENING.
     *  This host has no telephony at all (no FEATURE_TELEPHONY), so false. */
    public boolean isCallScreeningModeSupported() { return false; }

    /** API 30. The "ramping ringer" Settings.Global toggle. There is no ringer
     *  and no Settings entry for it here, so false -- the same answer a device
     *  with the feature switched off gives. */
    public boolean isRampingRingerEnabled() { return false; }

    /** API 19, and the caller must be an enabled notification listener or the
     *  current lock screen; AOSP returns FALSE for anyone else. No caller in a
     *  single-app process can qualify -- see RemoteController's class comment --
     *  so false, and the controller is never fed. */
    @Deprecated public boolean registerRemoteController(RemoteController rctlr) {
        return false;
    }

    @Deprecated public void unregisterRemoteController(RemoteController rctlr) { }

    /**
     * API 19. AOSP forwards the key event to whichever media session currently
     * holds media-button focus. Mac.apk has no cross-app media-button router,
     * but it DOES have in-process MediaSessions, and dispatching to them is
     * exactly what the app expects: the key it sends must reach its own session.
     * Delivered through MediaSessionManager, which owns the active-session list.
     */
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent) {
        if (keyEvent == null) return;
        try {
            android.media.session.MediaSessionManager.dispatchMediaKeyEventToSessions(keyEvent);
        } catch (Throwable ignore) {
            // AOSP's own path here is a one-way binder call whose failure is
            // logged and swallowed; a key event must never take the app down.
        }
    }

    // ---- the deprecated pre-API-2 routing/vibrate tail --------------------
    // These were replaced by setSpeakerphoneOn/setBluetoothScoOn and by
    // NotificationManager's vibrate settings more than a decade ago, and AOSP
    // has stubbed them out in the framework itself: setRouting/setVibrateSetting
    // "do nothing", getRouting returns -1 and getVibrateSetting returns
    // VIBRATE_SETTING_OFF. Mirrored verbatim so an old app sees exactly what it
    // would see on a modern device.
    @Deprecated public void setRouting(int mode, int routes, int mask) { }
    @Deprecated public int getRouting(int mode) { return -1; }
    @Deprecated public void setWiredHeadsetOn(boolean on) { }
    @Deprecated public void setStreamSolo(int streamType, boolean state) { }
    @Deprecated public void setVibrateSetting(int vibrateType, int vibrateSetting) { }
    @Deprecated public int getVibrateSetting(int vibrateType) { return VIBRATE_SETTING_OFF; }
    /** AOSP: "always returns false" on current platforms. */
    @Deprecated public boolean shouldVibrate(int vibrateType) { return false; }
}
