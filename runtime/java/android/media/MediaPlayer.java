// android.media.MediaPlayer -- file/stream audio playback.
//
// Backed by the macOS CoreAudio engine (runtime/src/audio_backend.mm) via
// com.macapk.loader.NativeAudio: the data source is decoded
// (WAV/MP3/M4A/AAC/CAF via AVAudioFile, Ogg Vorbis via stb_vorbis) and
// replayed on a dedicated MODE_CLIP voice feeding the shared mixer, with
// loop/seek/volume. Used by games for background music.
//
// Observable behavior mirrored from AOSP frameworks/base
// media/java/android/media/MediaPlayer.java.
package android.media;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

public class MediaPlayer implements AudioRouting, VolumeAutomation {
    private long handle;
    private byte[] pendingSource;     // bytes captured at setDataSource
    private boolean prepared;
    private boolean looping;
    private float volL = 1.0f, volR = 1.0f;
    private OnPreparedListener  onPrepared;
    private OnCompletionListener onCompletion;
    private OnErrorListener     onError;
    private OnInfoListener      onInfo;
    private MediaPlayer         nextPlayer;   // gapless chain (setNextMediaPlayer)

    public MediaPlayer() {}

    // AOSP API-23+ ctor associating the player with a Context (attribution /
    // session bookkeeping on real Android). Our Context is not a security
    // boundary, so there is no attribution plumbing to wire; the point of
    // this overload existing is so `new MediaPlayer(context)` resolves at
    // all instead of NoSuchMethodError.
    public MediaPlayer(android.content.Context context) { this(); }

    public static MediaPlayer create(android.content.Context ctx, int resId) {
        MediaPlayer mp = new MediaPlayer();
        try {
            InputStream in = ctx.getResources().openRawResource(resId);
            mp.pendingSource = readAll(in);
            mp.prepare();
            return mp;
        } catch (Throwable t) { return mp; }
    }
    public static MediaPlayer create(android.content.Context ctx, android.net.Uri uri) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(ctx, uri);
            mp.prepare();
            return mp;
        } catch (Throwable t) { return mp; }
    }

    public void setDataSource(String path) {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.setDataSource(path)", path);
        try { pendingSource = readAll(new FileInputStream(path)); }
        catch (Throwable t) { pendingSource = null; }
    }
    public void setDataSource(android.content.Context ctx, android.net.Uri uri) {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.setDataSource(uri)", String.valueOf(uri));
        try {
            InputStream in = null;
            try { in = ctx.getContentResolver().openInputStream(uri); }
            catch (Throwable ignore) {}
            if (in == null) {
                String p = uri != null ? uri.getPath() : null;
                if (p != null) in = new FileInputStream(p);
            }
            pendingSource = in != null ? readAll(in) : null;
        } catch (Throwable t) { pendingSource = null; }
    }
    // AOSP API-14 streaming overload. We don't do HTTP range/auth-header-aware
    // streaming (setDataSource(Context,Uri) already does a plain fetch), so
    // headers are honestly dropped rather than faked -- matches this class's
    // existing fidelity level for network sources.
    public void setDataSource(android.content.Context ctx, android.net.Uri uri,
                               java.util.Map<String, String> headers) {
        setDataSource(ctx, uri);
    }
    public void setDataSource(android.content.res.AssetFileDescriptor afd) {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.setDataSource(afd)");
        try { pendingSource = readAll(afd.createInputStream()); }
        catch (Throwable t) { pendingSource = null; }
    }
    public void setDataSource(java.io.FileDescriptor fd, long offset, long length) {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.setDataSource(fd)", "off=" + offset + " len=" + length);
        try {
            FileInputStream in = new FileInputStream(fd);
            if (offset > 0) in.skip(offset);
            pendingSource = readAllLimited(in, length);
        } catch (Throwable t) { pendingSource = null; }
    }
    // AOSP: setDataSource(FileDescriptor fd) delegates to the 3-arg form with
    // offset 0 and length Long.MAX_VALUE (0x7fffffffffffffffL), i.e. "read
    // the whole descriptor".
    public void setDataSource(java.io.FileDescriptor fd) {
        setDataSource(fd, 0, Long.MAX_VALUE);
    }
    // AOSP API-23 pull-source overload. Our backend decodes from a fully
    // slurped byte[] (same as every other overload above), so we drain the
    // MediaDataSource up front via its readAt/getSize contract instead of
    // holding a live pull reference -- the same adaptation this class already
    // makes for FileDescriptor/Uri/AssetFileDescriptor sources.
    public void setDataSource(MediaDataSource dataSource) {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.setDataSource(MediaDataSource)");
        try { pendingSource = readAllFromMediaDataSource(dataSource); }
        catch (Throwable t) { pendingSource = null; }
    }

    public void prepare() {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.prepare");
        ensureHandle();
        if (handle != 0 && pendingSource != null) {
            prepared = com.macapk.loader.NativeAudio.nativeMpSetSource(
                handle, pendingSource, pendingSource.length);
            if (prepared) {
                com.macapk.loader.NativeAudio.nativeMpSetLooping(handle, looping);
                com.macapk.loader.NativeAudio.nativeMpSetVolume(handle, volL, volR);
            }
        }
    }
    public void prepareAsync() {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.prepareAsync");
        new Thread(new Runnable() {
            @Override public void run() {
                prepare();
                if (prepared) {
                    OnPreparedListener l = onPrepared;
                    if (l != null) {
                        try { l.onPrepared(MediaPlayer.this); } catch (Throwable ignore) {}
                    }
                } else {
                    // AOSP: "If the data source cannot be prepared ... the
                    // internal player engine of the MediaPlayer object will
                    // call [OnErrorListener.onError] ... if an error listener
                    // was previously set". Before this fix, a decode failure
                    // here (bad file, unsupported codec) left BOTH onPrepared
                    // and onError silent -- an app waiting on prepareAsync
                    // (the recommended non-blocking pattern almost every real
                    // player uses, vs. the blocking prepare()) hung forever
                    // with no signal at all: the worst failure shape this
                    // project tracks. Fire onError so the app's error path
                    // (skip track, show a message, retry) actually runs.
                    OnErrorListener el = onError;
                    if (el != null) {
                        try { el.onError(MediaPlayer.this, MEDIA_ERROR_UNKNOWN, 0); }
                        catch (Throwable ignore) {}
                    }
                }
            }
        }, "mediaplayer-prepare").start();
    }

    private void ensureHandle() {
        if (handle == 0) {
            try { handle = com.macapk.loader.NativeAudio.nativeMpCreate(); }
            catch (Throwable t) { handle = 0; }
        }
    }

    public void start() {
        com.macapk.loader.AudioTrace.hit("MediaPlayer.start");
        if (handle != 0) {
            mUserPaused = false;
            com.macapk.loader.NativeAudio.nativeMpStart(handle);
            startCompletionWatcher();
        }
    }
    // The completion watcher below polls isPlaying(), and the native
    // macapk_mp_is_playing reports false for PAUSED as well as for STOPPED --
    // so a pause() used to look exactly like end-of-media: it fired
    // onCompletion AND auto-started the gapless nextPlayer. AOSP fires
    // onCompletion only "when the end of a media source is reached". These
    // flags let the watcher tell a user-initiated stop from a real completion.
    private volatile boolean mUserPaused;
    public void pause()  {
        mUserPaused = true;
        if (handle != 0) com.macapk.loader.NativeAudio.nativeMpPause(handle);
    }
    public void stop()   {
        mUserPaused = true;
        if (handle != 0) com.macapk.loader.NativeAudio.nativeMpStop(handle);
    }
    public void reset()  {
        if (handle != 0) com.macapk.loader.NativeAudio.nativeMpStop(handle);
        prepared = false;
        pendingSource = null;
    }
    public void release() {
        if (handle != 0) { com.macapk.loader.NativeAudio.nativeMpRelease(handle); handle = 0; }
        prepared = false;
        pendingSource = null;
    }
    public boolean isPlaying() {
        return handle != 0 && com.macapk.loader.NativeAudio.nativeMpIsPlaying(handle);
    }
    public int getDuration() {
        return handle != 0 ? com.macapk.loader.NativeAudio.nativeMpDuration(handle) : 0;
    }
    public int getCurrentPosition() {
        return handle != 0 ? com.macapk.loader.NativeAudio.nativeMpPosition(handle) : 0;
    }
    public void seekTo(int msec) {
        if (handle == 0) return;
        com.macapk.loader.NativeAudio.nativeMpSeek(handle, msec);
        // AOSP: "the OnSeekCompleteListener.onSeekComplete() callback will be
        // invoked when the seek is completed." Before this fix the listener
        // was accepted by setOnSeekCompleteListener but NEVER invoked, so any
        // app that gates further UI/playback action on the seek finishing
        // (e.g. re-enabling a seek bar, resuming after a scrub) hung forever.
        // The native seek (audio_backend.mm macapk_mp_seek) is a synchronous
        // atomic cursor store -- no I/O, no async work -- so the seek really
        // has completed by the time nativeMpSeek() returns; the callback is
        // still dispatched off this thread (matching prepareAsync/
        // startCompletionWatcher's existing background-Thread idiom in this
        // same class) so a listener that calls back into seekTo()/start()
        // cannot reenter this call frame.
        final OnSeekCompleteListener l = onSeekComplete;
        if (l != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { l.onSeekComplete(MediaPlayer.this); } catch (Throwable ignore) {}
                }
            }, "mediaplayer-seek").start();
        }
    }
    public void setLooping(boolean looping) {
        this.looping = looping;
        if (handle != 0) com.macapk.loader.NativeAudio.nativeMpSetLooping(handle, looping);
    }
    public boolean isLooping() { return looping; }
    // AOSP API-21 audio routing config. Mac.apk has one audio output, so
    // the attributes don't change routing -- but the method must exist:
    // libGDX/watabou's Music.play() calls setAudioAttributes(...) and the
    // missing method threw NoSuchMethodError mid-WelcomeScene.create
    // (ShatteredPD / libGDX games), aborting scene setup. Accept + ignore.
    public void setAudioAttributes(android.media.AudioAttributes attributes) {}
    // Deprecated pre-API-21 equivalent; some apps still call it.
    public void setAudioStreamType(int streamType) {}
    public void setVolume(float left, float right) {
        volL = left; volR = right;
        // The VolumeShaper scale multiplies the app's volume rather than being
        // overwritten by it, the way AOSP composes the two in the mixer -- see
        // createVolumeShaper below. mShaperScale is 1.0 when no shaper is
        // running, so the ordinary path is unchanged.
        if (handle != 0) {
            com.macapk.loader.NativeAudio.nativeMpSetVolume(handle,
                    left * mShaperScale, right * mShaperScale);
        }
    }
    // AOSP API-16 gapless playback: when this player finishes, the next one is
    // started automatically. cocos2d-x's Cocos2dxMusic.playBackgroundMusic
    // chains players with this; its absence threw NoSuchMethodError (an Error,
    // not caught by the surrounding catch(Exception)), so playBackgroundMusic
    // never reached start() and the BGM was silent (HCR 1.43). We store the
    // next player and start it from the completion watcher (true gapless
    // chaining); the immediate effect is that start() is now reached and the
    // current track plays.
    public void setNextMediaPlayer(MediaPlayer next) { this.nextPlayer = next; }

    // Best-effort completion firing: poll while playing; when playback stops
    // on its own (not looping, not user-stopped) fire onCompletion once.
    private volatile boolean mWatcherLive;
    private void startCompletionWatcher() {
        final OnCompletionListener l = onCompletion;
        // Watch if there's a completion listener OR a gapless-chained next
        // player to start. A looping track never completes, so no watcher.
        if ((l == null && nextPlayer == null) || looping) return;
        // Every start() -- including a resume from pause -- used to spawn a
        // fresh watcher, so a track paused and resumed twice had three threads
        // racing to fire onCompletion once each. Claimed AFTER the checks
        // above, or an early return would latch the flag and no watcher could
        // ever start.
        synchronized (this) { if (mWatcherLive) return; mWatcherLive = true; }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Wait until it actually starts, then until it stops.
                    boolean started = false;
                    for (int i = 0; i < 50 && !started; i++) {
                        if (isPlaying()) started = true; else Thread.sleep(20);
                    }
                    while (isPlaying()) Thread.sleep(50);
                    // Playback stopped -- but WHY? A user pause/stop is not a
                    // completion. Belt and braces: also require the cursor to
                    // have reached the end, because a stop can race the flag.
                    boolean atEnd;
                    try {
                        int dur = getDuration(), pos = getCurrentPosition();
                        atEnd = dur <= 0 || pos >= dur - 250;
                    } catch (Throwable t) { atEnd = true; }
                    if (mUserPaused || !atEnd) { mWatcherLive = false; return; }
                    // Release the single-flight BEFORE the terminal callbacks.
                    // onCompletion runs INLINE on this thread, and the canonical
                    // MediaPlayer idiom is to call start() (or seekTo(0);start())
                    // from inside it for a manual loop or playlist advance. With
                    // the flag still held, that start() finds mWatcherLive true,
                    // spawns nothing, and this thread then exits -- leaving a
                    // PLAYING track with no watcher, so the next completion never
                    // fires and the playlist stops after one advance.
                    mWatcherLive = false;
                    if (started && !looping) {
                        // Gapless chain: auto-start the next player (AOSP
                        // setNextMediaPlayer semantics) before notifying.
                        MediaPlayer np = nextPlayer;
                        if (np != null) { try { np.start(); } catch (Throwable ignore) {} }
                        if (l != null) { try { l.onCompletion(MediaPlayer.this); } catch (Throwable ignore) {} }
                    }
                } catch (Throwable ignore) {
                    // Only reachable from the polling code ABOVE the release --
                    // both app callbacks have their own catch -- so clearing here
                    // cannot stomp a flag a nested start() re-claimed. Without
                    // it, an interrupt during the sleep would latch mWatcherLive
                    // forever and no watcher could start again for this player.
                    mWatcherLive = false;
                }
                // NOTE: no `finally { mWatcherLive = false; }` here. Every exit
                // path above clears it explicitly BEFORE running app code; a
                // blanket clear in finally would also fire after a nested
                // start() had legitimately re-claimed the flag, so two watchers
                // could then run for one player.
            }
        }, "mediaplayer-completion").start();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        return readAllLimited(in, -1);
    }
    private static byte[] readAllLimited(InputStream in, long maxLen) throws java.io.IOException {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                maxLen > 0 && maxLen < (1 << 27) ? (int) maxLen : 65536);
            byte[] buf = new byte[65536];
            long remaining = maxLen > 0 ? maxLen : Long.MAX_VALUE;
            int n;
            while (remaining > 0
                   && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                out.write(buf, 0, n);
                remaining -= n;
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }
    // MediaDataSource.readAt returns the bytes read, or -1 at end of source
    // (the same contract as InputStream.read that readAllLimited above already
    // relies on). getSize() may be unknown (per its contract); a non-positive
    // size just falls back to the same growable-buffer sizing readAllLimited
    // uses for an unbounded FileInputStream.
    private static byte[] readAllFromMediaDataSource(MediaDataSource ds) throws java.io.IOException {
        long size = ds.getSize();
        ByteArrayOutputStream out = new ByteArrayOutputStream(
            size > 0 && size < (1 << 27) ? (int) size : 65536);
        byte[] buf = new byte[65536];
        long pos = 0;
        int n;
        while ((n = ds.readAt(pos, buf, 0, buf.length)) > 0) {
            out.write(buf, 0, n);
            pos += n;
        }
        return out.toByteArray();
    }

    public void setOnPreparedListener(OnPreparedListener l)   { onPrepared = l; }
    public void setOnCompletionListener(OnCompletionListener l){ onCompletion = l; }
    public void setOnErrorListener(OnErrorListener l)         { onError = l; }
    public void setOnInfoListener(OnInfoListener l)           { onInfo = l; }
    public interface OnPreparedListener  { void onPrepared(MediaPlayer mp); }
    public interface OnCompletionListener { void onCompletion(MediaPlayer mp); }
    public interface OnErrorListener     { boolean onError(MediaPlayer mp, int what, int extra); }
    public interface OnInfoListener      { boolean onInfo(MediaPlayer mp, int what, int extra); }

    // ----- additive: the remaining AOSP MediaPlayer surface. Video has no
    // decode/Surface backend (Mac.apk's MediaPlayer is audio-only), so the
    // video methods report a 0x0 frame and accept a display without rendering;
    // the contract apps rely on (no NoSuchMethodError, listeners settable) holds.

    public static final int MEDIA_ERROR_UNKNOWN = 1;
    public static final int MEDIA_ERROR_SERVER_DIED = 100;
    public static final int MEDIA_ERROR_IO = -1004;
    public static final int MEDIA_ERROR_MALFORMED = -1007;
    public static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    public static final int MEDIA_ERROR_TIMED_OUT = -110;
    public static final int MEDIA_INFO_UNKNOWN = 1;
    public static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;
    public static final int MEDIA_INFO_BUFFERING_START = 701;
    public static final int MEDIA_INFO_BUFFERING_END = 702;
    public static final int MEDIA_INFO_NOT_SEEKABLE = 801;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;
    public static final int PREPARE_DRM_STATUS_SUCCESS = 0;
    public static final int PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR = 1;
    public static final int PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR = 2;
    public static final int PREPARE_DRM_STATUS_PREPARATION_ERROR = 3;

    private OnSeekCompleteListener     onSeekComplete;
    private OnBufferingUpdateListener  onBufferingUpdate;
    private OnVideoSizeChangedListener onVideoSizeChanged;

    public int getVideoWidth()  { return 0; }
    public int getVideoHeight() { return 0; }
    // Published descriptors are (Landroid/view/SurfaceHolder;)V and
    // (Landroid/view/Surface;)V. The prior Object signatures compiled but did
    // not match the descriptor a guest emits for setDisplay(holder), so the JNI
    // method lookup threw NoSuchMethodError. No video backend yet, so accept and
    // ignore -- but the descriptor must match for the call to resolve.
    public void setDisplay(android.view.SurfaceHolder surfaceHolder) {}
    public void setSurface(android.view.Surface surface) {}
    public void setVideoScalingMode(int mode) {}
    public void setScreenOnWhilePlaying(boolean screenOn) {}
    public void setWakeMode(android.content.Context context, int mode) {}
    // Stored so getPlaybackParams() reads back what was actually configured
    // (AOSP round-trips speed/pitch through the native player; we don't have
    // a native binding for it, but silently discarding the value and always
    // reporting the default 1.0x/1.0 broke any UI or logic that re-reads its
    // own configured speed). Defaults to a fresh PlaybackParams (speed=1.0,
    // pitch=1.0) so a caller that never sets one still sees AOSP's default,
    // never null.
    //
    // ⚠ allowDefaults() and the copy in getPlaybackParams() are BOTH required
    // since PlaybackParams grew AOSP's mSet bitmask (its getters now throw
    // IllegalStateException for a field nobody set, exactly as AOSP's do).
    // Handing back either a default-constructed object or the caller's own
    // partially-set one would make getPlaybackParams().getPitch() throw where
    // it previously answered 1.0. AOSP's native get_playback_params returns
    // the rate with ALL FOUR set-flags true for the same reason
    // (frameworks/base core/jni/android_media_MediaPlayer.cpp ->
    // PlaybackParams::asJobject), so a fully-set copy IS the AOSP answer, not
    // a workaround for our mask.
    //
    // NOTE (out of this lane, but do not let it hide): setPlaybackParams here
    // still only STORES. MediaPlayer is a MODE_CLIP voice, whose rate knob is
    // clip_rate -- pitch could be routed there today, and speed would need the
    // same AVAudioUnitTimePitch the AudioTrack path now installs. Until that
    // is wired, this is a store-but-never-applied setter of the same family
    // fixed for AudioTrack.
    private PlaybackParams mPlaybackParams = new PlaybackParams().allowDefaults();
    public void setPlaybackParams(PlaybackParams params) {
        if (params != null) mPlaybackParams = params;
    }
    public PlaybackParams getPlaybackParams() {
        return new PlaybackParams()
                .setSpeed(mPlaybackParams.macapkSpeedRaw())
                .setPitch(mPlaybackParams.macapkPitchRaw())
                .setAudioFallbackMode(mPlaybackParams.macapkFallbackModeRaw())
                .setAudioStretchMode(mPlaybackParams.macapkStretchModeRaw());
    }
    // AOSP: "returns the metrics recorded for this MediaPlayer, or null if no
    // metrics are available." Mirrors the same, already-correct null-return
    // pattern this codebase uses for MediaRecorder.getMetrics().
    public android.os.PersistableBundle getMetrics() { return null; }
    public SyncParams getSyncParams() { return new SyncParams(); }
    public void setSyncParams(SyncParams params) {}
    public void attachAuxEffect(int effectId) {}
    public void setAuxEffectSendLevel(float level) {}
    private int mAudioSessionId;
    public int getAudioSessionId() { return mAudioSessionId; }
    public void setAudioSessionId(int sessionId) { mAudioSessionId = sessionId; }
    public TrackInfo[] getTrackInfo() { return new TrackInfo[0]; }
    public void selectTrack(int index) {}
    public void deselectTrack(int index) {}
    public void seekTo(long msec, int mode) { seekTo((int) msec); }
    public static final int SEEK_PREVIOUS_SYNC = 0;
    public static final int SEEK_NEXT_SYNC = 1;
    public static final int SEEK_CLOSEST_SYNC = 2;
    public static final int SEEK_CLOSEST = 3;

    public void setOnSeekCompleteListener(OnSeekCompleteListener l)     { onSeekComplete = l; }
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener l){ onBufferingUpdate = l; }
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener l){ onVideoSizeChanged = l; }

    public interface OnSeekCompleteListener     { void onSeekComplete(MediaPlayer mp); }
    public interface OnBufferingUpdateListener  { void onBufferingUpdate(MediaPlayer mp, int percent); }
    public interface OnVideoSizeChangedListener { void onVideoSizeChanged(MediaPlayer mp, int width, int height); }
    public interface OnTimedTextListener        { void onTimedText(MediaPlayer mp, TimedText text); }
    public interface OnSubtitleDataListener     { void onSubtitleData(MediaPlayer mp, SubtitleData data); }

    // AOSP MediaPlayer.TrackInfo -- describes a contained audio/video/text track.
    //
    // 2026-08-23 conformance sweep: AOSP's TrackInfo stores a real
    // (mTrackType, mFormat) pair from a package-private constructor and
    // writeToParcel serializes them (MediaPlayer.java L2628-2695). This
    // class carries neither field -- getTrackType() always returns
    // MEDIA_TRACK_TYPE_UNKNOWN and getFormat() always returns a fresh empty
    // MediaFormat(), regardless of how the instance came to exist, because
    // Mac.apk does not track per-track media format metadata. As with
    // ApplicationStartInfo, there is no per-instance state a write/read
    // pair could lose, so an honest round trip writes nothing.
    public static class TrackInfo implements android.os.Parcelable {
        public static final int MEDIA_TRACK_TYPE_UNKNOWN = 0;
        public static final int MEDIA_TRACK_TYPE_VIDEO = 1;
        public static final int MEDIA_TRACK_TYPE_AUDIO = 2;
        public static final int MEDIA_TRACK_TYPE_TIMEDTEXT = 3;
        public static final int MEDIA_TRACK_TYPE_SUBTITLE = 4;
        public static final int MEDIA_TRACK_TYPE_METADATA = 5;
        public int getTrackType() { return MEDIA_TRACK_TYPE_UNKNOWN; }
        public String getLanguage() { return "und"; }
        public MediaFormat getFormat() { return new MediaFormat(); }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) { }

        public static final android.os.Parcelable.Creator<TrackInfo> CREATOR =
                new android.os.Parcelable.Creator<TrackInfo>() {
            @Override public TrackInfo createFromParcel(android.os.Parcel in) {
                return new TrackInfo();
            }
            @Override public TrackInfo[] newArray(int size) { return new TrackInfo[size]; }
        };
    }

    public android.media.MediaTimestamp getTimestamp() { return android.media.MediaTimestamp.TIMESTAMP_UNKNOWN; }

    // =====================================================================
    // API-gap close-out. Signatures from /tmp/aosp_api/android16.txt; behaviour
    // from frameworks/base/media/java/android/media/MediaPlayer.java
    // (android16-release).
    // =====================================================================

    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String CODEC_AUDIO = "android.media.mediaplayer.audio.codec";
        public static final String CODEC_VIDEO = "android.media.mediaplayer.video.codec";
        public static final String DURATION = "android.media.mediaplayer.durationMs";
        public static final String ERRORS = "android.media.mediaplayer.err";
        public static final String ERROR_CODE = "android.media.mediaplayer.errcode";
        public static final String FRAMES = "android.media.mediaplayer.frames";
        public static final String FRAMES_DROPPED = "android.media.mediaplayer.dropped";
        public static final String HEIGHT = "android.media.mediaplayer.height";
        public static final String MIME_TYPE_AUDIO = "android.media.mediaplayer.audio.mime";
        public static final String MIME_TYPE_VIDEO = "android.media.mediaplayer.video.mime";
        public static final String PLAYING = "android.media.mediaplayer.playingMs";
        public static final String WIDTH = "android.media.mediaplayer.width";
    }

    // ---- the create() overloads ------------------------------------------
    // AOSP's create() family is documented to "return a MediaPlayer object, or
    // null if creation failed", and every overload is the same recipe: build,
    // set the data source, attach the surface/attributes/session, prepare, and
    // return null if any step throws. Delegating to the existing two-argument
    // forms keeps that one recipe in one place.

    public static MediaPlayer create(android.content.Context ctx, android.net.Uri uri,
            android.view.SurfaceHolder holder) {
        MediaPlayer mp = create(ctx, uri);
        if (mp != null && holder != null) mp.setDisplay(holder);
        return mp;
    }

    public static MediaPlayer create(android.content.Context ctx, android.net.Uri uri,
            android.view.SurfaceHolder holder, android.media.AudioAttributes audioAttributes,
            int audioSessionId) {
        MediaPlayer mp = create(ctx, uri, holder);
        if (mp != null) {
            if (audioAttributes != null) mp.setAudioAttributes(audioAttributes);
            mp.setAudioSessionId(audioSessionId);
        }
        return mp;
    }

    public static MediaPlayer create(android.content.Context ctx, int resId,
            android.media.AudioAttributes audioAttributes, int audioSessionId) {
        MediaPlayer mp = create(ctx, resId);
        if (mp != null) {
            if (audioAttributes != null) mp.setAudioAttributes(audioAttributes);
            mp.setAudioSessionId(audioSessionId);
        }
        return mp;
    }

    /** API 26. The cookie list is for HTTP(S) sources; this player reads the
     *  bytes itself and has no cookie jar to install, so it degrades to the
     *  headers overload -- which is what AOSP does for a non-HTTP uri too. */
    public void setDataSource(android.content.Context ctx, android.net.Uri uri,
            java.util.Map<String, String> headers, java.util.List<java.net.HttpCookie> cookies)
            throws java.io.IOException {
        setDataSource(ctx, uri, headers);
    }

    // ---- track selection --------------------------------------------------

    /**
     * AOSP: the index of the currently selected track of the given type, or -1
     * when none is selected. getTrackInfo() reports no tracks here (the player
     * does not expose per-track metadata -- see TrackInfo's comment), so -1 is
     * the consistent answer, and it is AOSP's own "nothing selected" value
     * rather than an invented one.
     */
    public int getSelectedTrack(int trackType) throws IllegalStateException { return -1; }

    // ---- timed text / subtitles / timed metadata --------------------------
    // These deliver an out-of-band text track alongside playback. Nothing in
    // this tree parses one (the player decodes audio only), so a source added
    // here produces no events -- which is exactly what an app sees on Android
    // when the file carries no such track. The listeners are stored so
    // set/clear behave as a pair.

    private OnTimedTextListener mOnTimedText;
    private OnSubtitleDataListener mOnSubtitleData;
    private OnTimedMetaDataAvailableListener mOnTimedMetaData;
    private OnMediaTimeDiscontinuityListener mOnMediaTimeDiscontinuity;

    public void setOnTimedTextListener(OnTimedTextListener listener) {
        mOnTimedText = listener;
    }

    public void setOnSubtitleDataListener(OnSubtitleDataListener listener) {
        mOnSubtitleData = listener;
    }

    public void setOnSubtitleDataListener(OnSubtitleDataListener listener,
            android.os.Handler handler) {
        mOnSubtitleData = listener;
    }

    public void clearOnSubtitleDataListener() { mOnSubtitleData = null; }

    public void setOnTimedMetaDataAvailableListener(OnTimedMetaDataAvailableListener listener) {
        mOnTimedMetaData = listener;
    }

    public void setOnMediaTimeDiscontinuityListener(OnMediaTimeDiscontinuityListener listener) {
        mOnMediaTimeDiscontinuity = listener;
    }

    public void setOnMediaTimeDiscontinuityListener(OnMediaTimeDiscontinuityListener listener,
            android.os.Handler handler) {
        mOnMediaTimeDiscontinuity = listener;
    }

    public void clearOnMediaTimeDiscontinuityListener() { mOnMediaTimeDiscontinuity = null; }

    public interface OnTimedMetaDataAvailableListener {
        void onTimedMetaDataAvailable(MediaPlayer mp, TimedMetaData data);
    }

    public interface OnMediaTimeDiscontinuityListener {
        void onMediaTimeDiscontinuity(MediaPlayer mp, MediaTimestamp mts);
    }

    /**
     * AOSP's addTimedTextSource attaches an EXTERNAL subtitle file and throws
     * IllegalArgumentException for a mime type it does not support -- the only
     * ones it accepts are "application/x-subrip" and "text/vtt"
     * (MediaPlayer.java, addTimedTextSource / MEDIA_MIMETYPE_TEXT_SUBRIP).
     *
     * This player has no timed-text renderer, so no source can actually be
     * attached. It still performs AOSP's own validation first -- so a caller
     * passing a bogus mime type learns about it -- and then throws
     * IllegalStateException, which every overload already declares and which is
     * AOSP's answer when the player is not in a state that can accept the
     * source. Silently accepting would leave the app waiting for subtitle
     * events that can never arrive.
     */
    public void addTimedTextSource(String path, String mimeType)
            throws java.io.IOException, IllegalArgumentException, IllegalStateException {
        checkTimedTextMimeType(mimeType);
        throw new IllegalStateException("Timed text is not supported on this device");
    }

    public void addTimedTextSource(android.content.Context context, android.net.Uri uri,
            String mimeType)
            throws java.io.IOException, IllegalArgumentException, IllegalStateException {
        checkTimedTextMimeType(mimeType);
        throw new IllegalStateException("Timed text is not supported on this device");
    }

    public void addTimedTextSource(java.io.FileDescriptor fd, String mimeType)
            throws IllegalArgumentException, IllegalStateException {
        checkTimedTextMimeType(mimeType);
        throw new IllegalStateException("Timed text is not supported on this device");
    }

    public void addTimedTextSource(java.io.FileDescriptor fd, long offset, long length,
            String mimeType) throws IllegalArgumentException, IllegalStateException {
        checkTimedTextMimeType(mimeType);
        throw new IllegalStateException("Timed text is not supported on this device");
    }

    private static void checkTimedTextMimeType(String mimeType) {
        if (!"application/x-subrip".equals(mimeType) && !"text/vtt".equals(mimeType)) {
            throw new IllegalArgumentException("Illegal mimeType for timed text source: "
                    + mimeType);
        }
    }

    // ---- AudioRouting ------------------------------------------------------

    private AudioDeviceInfo mPreferredDevice;
    private final java.util.concurrent.CopyOnWriteArrayList<Object[]> mRoutingListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override public AudioDeviceInfo getPreferredDevice() { return mPreferredDevice; }

    /** One output exists on this host, so a request naming a sink succeeds and
     *  a request for anything else cannot be honoured. */
    @Override public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo == null) { mPreferredDevice = null; return true; }
        if (!deviceInfo.isSink()) return false;
        mPreferredDevice = deviceInfo;
        return true;
    }

    /** AOSP: null when the player is not playing (there is no route yet). */
    @Override public AudioDeviceInfo getRoutedDevice() {
        return (handle == 0) ? null : AudioDeviceInfo.defaultOutput();
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
        void onRoutingChanged(MediaPlayer mp);
        @Override default void onRoutingChanged(AudioRouting router) {
            if (router instanceof MediaPlayer) onRoutingChanged((MediaPlayer) router);
        }
    }

    // ---- VolumeAutomation --------------------------------------------------

    private volatile float mShaperScale = 1.0f;

    /** A working shaper: the sampled curve multiplies the app's own left/right
     *  volume through the same native path setVolume() uses. */
    @Override public VolumeShaper createVolumeShaper(VolumeShaper.Configuration configuration) {
        return new VolumeShaper(configuration, new VolumeShaper.Target() {
            @Override public void onShaperVolumeChanged(float scale) {
                mShaperScale = scale;
                if (handle != 0) {
                    com.macapk.loader.NativeAudio.nativeMpSetVolume(handle,
                            volL * scale, volR * scale);
                }
            }
        });
    }

    // ---- DRM ---------------------------------------------------------------
    //
    // ⚠ Mac.apk does NOT implement DRM. That is an explicit project boundary
    // (CLAUDE.md, "Explicitly OUT of scope": no DRM circumvention, no defeating
    // licensing or copy protection). The API surface below exists so an app that
    // CHECKS for DRM support links and gets a truthful "no" -- which is exactly
    // what a device with no DRM plugin returns -- rather than dying on a
    // NoSuchMethodError inside its own capability probe.
    //
    // Every entry point therefore reports the honest AOSP-shaped failure:
    // getDrmInfo() is null ("no DRM info is available"), prepareDrm throws
    // UnsupportedSchemeException (AOSP's own exception for a scheme the device
    // has no plugin for), and everything downstream of a successful prepareDrm
    // throws NoDrmSchemeException, which AOSP defines as "the DRM scheme has not
    // been prepared". None of this weakens a protection: an app that wants
    // protected playback correctly concludes it cannot have it here.

    private OnDrmConfigHelper mOnDrmConfigHelper;
    private OnDrmInfoListener mOnDrmInfoListener;
    private OnDrmPreparedListener mOnDrmPreparedListener;

    /** @Nullable in AOSP: null until onDrmInfo has fired, which it never does. */
    public DrmInfo getDrmInfo() { return null; }

    public void prepareDrm(java.util.UUID uuid)
            throws ProvisioningNetworkErrorException, ProvisioningServerErrorException,
                   ResourceBusyException, UnsupportedSchemeException {
        java.util.Objects.requireNonNull(uuid, "uuid must not be null");
        throw new UnsupportedSchemeException("No DRM plugin for " + uuid);
    }

    public void releaseDrm() throws NoDrmSchemeException {
        throw new NoDrmSchemeException("releaseDrm: No DRM scheme has been prepared");
    }

    public MediaDrm.KeyRequest getKeyRequest(byte[] keySetId, byte[] initData, String mimeType,
            int keyType, java.util.Map<String, String> optionalParameters)
            throws NoDrmSchemeException {
        throw new NoDrmSchemeException("getKeyRequest: No DRM scheme has been prepared");
    }

    public byte[] provideKeyResponse(byte[] keySetId, byte[] response)
            throws NoDrmSchemeException, DeniedByServerException {
        java.util.Objects.requireNonNull(response, "response must not be null");
        throw new NoDrmSchemeException("provideKeyResponse: No DRM scheme has been prepared");
    }

    public void restoreKeys(byte[] keySetId) throws NoDrmSchemeException {
        java.util.Objects.requireNonNull(keySetId, "keySetId must not be null");
        throw new NoDrmSchemeException("restoreKeys: No DRM scheme has been prepared");
    }

    public String getDrmPropertyString(String propertyName) throws NoDrmSchemeException {
        java.util.Objects.requireNonNull(propertyName, "propertyName must not be null");
        throw new NoDrmSchemeException("getDrmPropertyString: No DRM scheme has been prepared");
    }

    public void setDrmPropertyString(String propertyName, String value)
            throws NoDrmSchemeException {
        java.util.Objects.requireNonNull(propertyName, "propertyName must not be null");
        throw new NoDrmSchemeException("setDrmPropertyString: No DRM scheme has been prepared");
    }

    public void setOnDrmConfigHelper(OnDrmConfigHelper listener) {
        mOnDrmConfigHelper = listener;
    }

    public void setOnDrmInfoListener(OnDrmInfoListener listener) {
        mOnDrmInfoListener = listener;
    }

    public void setOnDrmInfoListener(OnDrmInfoListener listener, android.os.Handler handler) {
        mOnDrmInfoListener = listener;
    }

    public void setOnDrmPreparedListener(OnDrmPreparedListener listener) {
        mOnDrmPreparedListener = listener;
    }

    public void setOnDrmPreparedListener(OnDrmPreparedListener listener,
            android.os.Handler handler) {
        mOnDrmPreparedListener = listener;
    }

    /** The DRM schemes and PSSH boxes a source declares. Never produced here
     *  (getDrmInfo() is permanently null), but the type must exist for the
     *  listener signature to resolve. */
    public static final class DrmInfo {
        private final java.util.Map<java.util.UUID, byte[]> mPssh;
        private final java.util.UUID[] mSupportedSchemes;

        DrmInfo(java.util.Map<java.util.UUID, byte[]> pssh, java.util.UUID[] supportedSchemes) {
            mPssh = pssh;
            mSupportedSchemes = supportedSchemes;
        }

        public java.util.Map<java.util.UUID, byte[]> getPssh() { return mPssh; }
        public java.util.UUID[] getSupportedSchemes() { return mSupportedSchemes; }
    }

    /** AOSP: "Thrown when a DRM method is called before preparing a DRM scheme
     *  through prepareDrm()." */
    public static final class NoDrmSchemeException extends MediaDrmException {
        public NoDrmSchemeException(String detailMessage) { super(detailMessage); }
    }

    /** AOSP: thrown when provisioning could not reach the server. */
    public static final class ProvisioningNetworkErrorException extends MediaDrmException {
        public ProvisioningNetworkErrorException(String detailMessage) { super(detailMessage); }
    }

    /** AOSP: thrown when the provisioning server rejected the request. */
    public static final class ProvisioningServerErrorException extends MediaDrmException {
        public ProvisioningServerErrorException(String detailMessage) { super(detailMessage); }
    }

    public interface OnDrmConfigHelper {
        void onDrmConfig(MediaPlayer mp);
    }

    public interface OnDrmInfoListener {
        void onDrmInfo(MediaPlayer mp, DrmInfo drmInfo);
    }

    public interface OnDrmPreparedListener {
        void onDrmPrepared(MediaPlayer mp, int status);
    }

    /** AOSP declares finalize() so a dropped player releases its native stream
     *  instead of leaking a CoreAudio node. release() is idempotent. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}

