// android.media.MediaCodec -- the low-level codec API. AOSP frameworks/base
// media/java/android/media/MediaCodec.java.
//
// Audio (AAC/MPEG) and video (H.264/HEVC/VP9/AV1/MPEG-4/MPEG-2/H.263) are both
// real: configure() below opens a real decoder (AudioToolbox AudioConverter
// or VideoToolbox VTDecompressionSession, via com.macapk.loader.NativeMedia)
// for any mime the corresponding native backend reports as supported, and the
// synchronous dequeue/queue methods actually decode. A mime neither backend
// recognizes (an unsupported audio codec, or a video mime this host's
// VideoToolbox cannot decode -- VP8 is the notable current gap, tracked for a
// software libvpx path) falls back to the prior honest "no codec" stub:
// dequeueInputBuffer returns TRY_AGAIN forever and output buffers stay empty,
// matching a device with no usable HW/SW codec for that format rather than an
// unresolved-symbol abort.
//
// Video specifics: VideoToolbox decodes asynchronously with temporal
// (B-frame) reordering enabled, so a queued access unit's decoded frame
// surfaces later via NativeMedia.nativeVidPoll rather than synchronously
// inside queueInputBuffer -- see dequeueOutputBuffer/tryDequeueOutputLocked.
// releaseOutputBuffer(index, true) composites the frame onto the configured
// Surface for real (video_toolbox.mm -> cocoa_bridge.mm's
// CocoaPresentVideoFrame); with no Surface configured, getOutputImage
// returns a real YUV_420_888 Image (3 planes, real per-plane row/pixel
// strides, real per-frame geometry and timestamp -- see NativeVideoImage),
// converted in Java from the same decoded BGRA VideoToolbox already produces
// for the Surface path, because that is the only pixel format the shared
// native session hands out today. CLEAR (unencrypted) content only:
// queueSecureInputBuffer here does not decrypt anything, matching this
// project's no-DRM-circumvention boundary.
//
// Asynchronous mode (setCallback) rides the SAME real backends for both
// audio and video: asyncDispatchLoop() below drives the synchronous
// dequeue/queue pair proactively and pushes availability through Callback
// instead of requiring the app to poll, so an ExoPlayer/Media3-class
// AsynchronousMediaCodecAdapter (the default on modern targets, and what a
// media3 app like PlayTube uses) gets real decoded frames instead of a
// permanently-silent callback.
package android.media;
import java.nio.ByteBuffer;

public final class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME = 1;
    public static final int BUFFER_FLAG_SYNC_FRAME = 1;
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;
    public static final int BUFFER_FLAG_PARTIAL_FRAME = 8;
    public static final int BUFFER_FLAG_DECODE_ONLY = 32;

    public static final int CONFIGURE_FLAG_ENCODE = 1;
    public static final int CONFIGURE_FLAG_USE_BLOCK_MODEL = 2;

    public static final int CRYPTO_MODE_UNENCRYPTED = 0;
    public static final int CRYPTO_MODE_AES_CTR = 1;
    public static final int CRYPTO_MODE_AES_CBC = 2;

    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;

    public static final class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
        public void set(int newOffset, int newSize, long newTimeUs, int newFlags) {
            this.offset = newOffset;
            this.size = newSize;
            this.presentationTimeUs = newTimeUs;
            this.flags = newFlags;
        }
    }

    // AOSP async-mode callback. Subclassable; fires for real once a real
    // decoder (audio or video) is open -- see asyncDispatchLoop.
    public static abstract class Callback {
        public abstract void onInputBufferAvailable(MediaCodec codec, int index);
        public abstract void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info);
        public abstract void onError(MediaCodec codec, CodecException e);
        public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format);

        /** API 34. Fired instead of onError when the failure was a DECRYPT
         *  failure. Never here: Mac.apk implements no DRM (see MediaDrm), so no
         *  sample is ever decrypted and none can fail to be. AOSP's default
         *  body forwards to onError, and this one does the same, so an app that
         *  only overrode onError still hears about it. */
        public void onCryptoError(MediaCodec codec, CryptoException e) {
            onError(codec, new CodecException(
                    e == null ? 0 : e.getErrorCode(),
                    e == null ? "crypto error" : e.getMessage()));
        }

        /** API 34. Delivers the codec's final metrics just before it is
         *  reconfigured or released. AOSP's default body is empty. */
        public void onMetricsFlushed(MediaCodec codec, android.os.PersistableBundle metrics) { }

        /** API 34, block model only. Never here: the block model cannot be
         *  configured (see configure()). AOSP's default body is empty. */
        public void onOutputBuffersAvailable(MediaCodec codec, int index,
                java.util.ArrayDeque<BufferInfo> infos) { }
    }
    public static interface OnFrameRenderedListener {
        void onFrameRendered(MediaCodec codec, long presentationTimeUs, long nanoTime);
    }
    public static interface OnFirstTunnelFrameReadyListener {
        void onFirstTunnelFrameReady(MediaCodec codec);
    }

    // AOSP MediaCodec.CodecException -- thrown on configure/queue failures.
    public static final class CodecException extends IllegalStateException {
        public static final int ERROR_INSUFFICIENT_RESOURCE = 1100;
        public static final int ERROR_RECLAIMED = 1101;
        private final int mErrorCode;
        private final boolean mRecoverable;
        private final boolean mTransient;
        CodecException(int errorCode, String detail) {
            super(detail);
            mErrorCode = errorCode;
            mRecoverable = false;
            mTransient = false;
        }
        public int getErrorCode() { return mErrorCode; }
        public boolean isRecoverable() { return mRecoverable; }
        public boolean isTransient() { return mTransient; }
        public String getDiagnosticInfo() { return getMessage(); }
    }
    // AOSP MediaCodec.CryptoInfo -- per-sample encryption metadata used by
    // queueSecureInputBuffer and MediaExtractor.getSampleCryptoInfo. Mac.apk
    // never produces encrypted samples, but the type must resolve.
    public static final class CryptoInfo {
        public int numSubSamples;
        public int[] numBytesOfClearData;
        public int[] numBytesOfEncryptedData;
        public byte[] key;
        public byte[] iv;
        public int mode;
        public CryptoInfo() {}
        public void set(int newNumSubSamples, int[] newNumBytesOfClearData,
                        int[] newNumBytesOfEncryptedData, byte[] newKey, byte[] newIV, int newMode) {
            numSubSamples = newNumSubSamples;
            numBytesOfClearData = newNumBytesOfClearData;
            numBytesOfEncryptedData = newNumBytesOfEncryptedData;
            key = newKey;
            iv = newIV;
            mode = newMode;
        }
        public static final class Pattern {
            private int mEncryptBlocks;
            private int mSkipBlocks;
            public Pattern(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }
            public void set(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }
            public int getEncryptBlocks() { return mEncryptBlocks; }
            public int getSkipBlocks() { return mSkipBlocks; }
        }
        private Pattern mPattern;
        public void setPattern(Pattern newPattern) { mPattern = newPattern; }
        /** API 31. AOSP: the pattern set by setPattern, or null. It used to be
         *  dropped -- setPattern stored nothing -- so an app that set a pattern
         *  and read it back was told it had set none. */
        public Pattern getPattern() { return mPattern; }
    }

    public static final class CryptoException extends RuntimeException
            implements MediaDrmThrowable {
        private final int mErrorCode;
        private final CryptoInfo mCryptoInfo;
        public CryptoException(int errorCode, String detailMessage) {
            this(errorCode, detailMessage, null);
        }
        /** API 34. AOSP's newer form carries the CryptoInfo of the sample that
         *  failed, so an app can tell WHICH subsample the plugin rejected. */
        public CryptoException(int errorCode, String detailMessage, CryptoInfo cryptoInfo) {
            super(detailMessage);
            mErrorCode = errorCode;
            mCryptoInfo = cryptoInfo;
        }
        public int getErrorCode() { return mErrorCode; }
        /** @Nullable in AOSP: null when the exception was raised without one. */
        public CryptoInfo getCryptoInfo() { return mCryptoInfo; }
    }

    private MediaCodec() {}
    private MediaCodec(String name) { mName = name; }
    private String mName = "macapk.null.codec";

    /** Can this build actually decode `mime`?
     *
     *  Deliberately keyed on the SAME predicate configure() uses below, not on
     *  MediaCodecList: (a) it cannot drift from configure(), and (b) it avoids
     *  making the first createDecoderByType call trigger MediaCodecList's
     *  static init and its VTDecompressionSessionCreate trial probes on
     *  whatever thread got there first. */
    private static boolean macapkCanDecode(String mime) {
        if (mime == null) return false;
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_FLAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_ALAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AC3.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AMR_NB.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_G711_ALAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_G711_MLAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_VORBIS.equals(mime)) return true;
        if (mime.startsWith("video/")) {
            // A stale runtime can make the native probe throw UnsatisfiedLinkError;
            // treating that as "cannot decode" is the honest answer and matches
            // what MediaCodecList's provider already does with it.
            try { return isVideoMimeSupported(mime); } catch (Throwable t) { return false; }
        }
        return false;
    }

    /** AOSP: "@throws IOException if the codec cannot be created".
     *
     *  We used to return a codec for ANY mime and then silently drop every
     *  access unit -- queueInputBuffer discarded and dequeueOutputBuffer
     *  returned TRY_AGAIN forever, so an app waited for output that could never
     *  come. That is a HANG with no error anywhere, which is strictly worse than
     *  the loud, in-contract failure a device gives.
     *
     *  Scope is deliberate: only the by-TYPE factories throw. createByCodecName
     *  never does -- every media3/ExoPlayer/VLC path in the corpus walks
     *  MediaCodecList first and instantiates by NAME, so a throw there would put
     *  an exception in front of code the registry has already gated, including
     *  VLC's JNI. */
    public static MediaCodec createDecoderByType(String type) throws java.io.IOException {
        java.util.Objects.requireNonNull(type, "type");   // AOSP documents NPE on null
        if (!macapkCanDecode(type)) {
            throw new java.io.IOException("no decoder for " + type + " on this host");
        }
        return new MediaCodec("OMX.macapk.dec." + type);
    }

    /** There is NO encoder behind MediaCodec at all -- MediaCodecList registers
     *  zero of them, configure() ignores CONFIGURE_FLAG_ENCODE entirely, and
     *  createInputSurface() returns null. So this returned a DECODER under an
     *  encoder name: an app "encoding" got its PCM fed through an AAC decoder,
     *  or NPE'd on the null input surface.
     *
     *  Throwing IOException here rather than IllegalArgumentException at
     *  configure() is deliberate. IOException is what AOSP documents for "the
     *  codec cannot be created", it is a CHECKED exception the callers already
     *  handle, and it lands inside the corpus caller's protected range: Subway
     *  Surfers' NatCorder wraps createEncoderByType in a try/catch(IOException)
     *  but invokes createInputSurface() OUTSIDE it, so today's null-surface NPE
     *  escapes its constructor while this throw takes its graceful path.
     *
     *  macOS itself reports 16 encode formats including AAC, so this is a
     *  Mac.apk gap, not a host gap -- the honest interim answer, not the
     *  terminal one. Rung 1 is AudioConverter + VTCompressionSession; see
     *  docs/media-audio-camera-parity-audit_2026-08-30.md. */
    public static MediaCodec createEncoderByType(String type) throws java.io.IOException {
        java.util.Objects.requireNonNull(type, "type");
        throw new java.io.IOException("no encoder for " + type
                + " on this host (MediaCodec encoding is not implemented)");
    }

    public static MediaCodec createByCodecName(String name) throws java.io.IOException {
        java.util.Objects.requireNonNull(name, "name");
        return new MediaCodec(name);
    }

    public String getName() { return mName; }
    public String getCanonicalName() { return mName; }

    // ---- Audio-decoder backing (AudioToolbox AudioConverter via NativeMedia).
    // For a supported audio mime (AAC/MP3) start() opens a native decoder and the
    // sync buffer protocol below decodes each queued access unit to S16 PCM. For
    // an unsupported mime (an audio codec neither backend knows, or a video mime
    // this host can't decode) mDec/mVidDec both stay 0 and the class behaves as
    // the honest "no codec" shape (dequeueOutputBuffer never yields a buffer).
    private long mDec = 0;
    private int  mFormatId = 0, mSampleRate = 0, mChannels = 0;
    private byte[] mCsd0 = null;
    private boolean mStarted = false, mFormatReported = false;
    // MACAPK_MEDIA_PROBE (com.macapk.loader.MediaStats): playback-attempt
    // counters for the owner-issue checker. null unless the probe is on, so
    // every hook below is a no-op on a normal launch.
    private final com.macapk.loader.MediaStats.Codec mStat = com.macapk.loader.MediaStats.newCodec();
    private String mStatMime = "";

    // ---- Video-decoder backing (VideoToolbox via NativeMedia). See the file
    // header. mIsVideo is decided at configure() time from
    // NativeMedia.nativeVideoSupportedMimes() (the single source of truth this
    // lane and the MediaCodecList/MediaCodecInfo lane both read); mVidDec is
    // the actual native session, opened at start().
    private long mVidDec = 0;
    private boolean mIsVideo = false;
    private String mVidMime;
    private int mVidWidth, mVidHeight;
    private byte[] mCsd0Vid, mCsd1Vid;
    private android.view.Surface mSurface;
    private boolean mHasSurface = false;
    // Parallel to mOut (index-for-index): holds the native retained
    // CVPixelBufferRef (as a long) for whichever output slots currently carry
    // a polled-but-not-yet-released video frame. 0 = empty slot. Video output
    // never populates mOut itself (getOutputBuffer(index) returns null for
    // video, matching AOSP's "null if configured with an output surface"
    // contract -- and even without a surface there is no meaningful ByteBuffer
    // view of an opaque native pixel buffer, see getOutputImage instead).
    private long[] mOutPixbuf;
    // Parallel to mOutPixbuf: the presentation timestamp (microseconds, from
    // nativeVidPoll) of whichever frame currently occupies that slot, so
    // getOutputImage can hand it to Image.getTimestamp() instead of the
    // fixed 0 a shape-only stand-in would return.
    private long[] mOutPts;

    // IN_CAP is the FLOOR for an input buffer, not the size. A real MediaCodec
    // sizes its input buffers from the component, and the client can raise that
    // with MediaFormat.KEY_MAX_INPUT_SIZE ("max-input-size"); AOSP's own
    // ACodec/CCodec honour that key when allocating the input port. We ignored
    // it and pinned every input buffer at 64 KiB, which is fine for audio (an
    // AAC/Opus access unit is a couple of KiB) and far too small for video.
    //
    // Measured on PlayTube 1.3.5, 2026-08-18: media3 hands us the size it needs
    // (MediaCodecVideoRenderer.getMediaFormat reads CodecMaxValues.inputSize
    // into KEY_MAX_INPUT_SIZE via MediaFormatUtil.maybeSetInteger -- confirmed
    // in that APK's OWN bytecode, not from upstream source), then reads the
    // buffer back through getInputBuffer() and throws when it is short:
    //   MediaCodecVideoRenderer: Video codec error
    //   DecoderInputBuffer$InsufficientCapacityException: Buffer too small
    //       (65536 < 81979)   [then 87702, then 92900]
    // Video decode died on the first keyframe while audio kept playing, which
    // is exactly the "audio works, video is black" the owner reported.
    private static final int IN_BUFS = 8, OUT_BUFS = 8, IN_CAP = 1 << 16;
    /** Per-instance input capacity, resolved in configure(). Never below
     *  IN_CAP, so every audio path keeps the size it has always had. */
    private int mInCap = IN_CAP;
    private ByteBuffer[] mIn;
    private ByteBuffer[] mOut;
    private final java.util.ArrayDeque<Integer> mInFree  = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> mOutFree = new java.util.ArrayDeque<>();
    private static final class OutEntry {
        final byte[] pcm; final long pts; final int flags;
        OutEntry(byte[] p, long t, int f) { pcm = p; pts = t; flags = f; }
    }
    private final java.util.ArrayDeque<OutEntry> mOutQ = new java.util.ArrayDeque<>();

    // ---- Asynchronous (Callback) mode --------------------------------------
    // AOSP: calling setCallback(Callback) before start() (frameworks/base
    // media/java/android/media/MediaCodec.java, "Asynchronous processing
    // using buffers") switches the codec into async mode, where the codec
    // itself PUSHES buffer availability through Callback instead of the app
    // polling dequeueInputBuffer/dequeueOutputBuffer. The AAC/MPEG + video
    // decode backends below are real and already fully driven by the
    // synchronous dequeue/queue pair (tryDequeueOutputLocked, shared by both
    // the sync and async paths); async mode only needed something to call
    // that same pair proactively -- see asyncDispatchLoop(). mAsyncLock also
    // guards the shared buffer-pool state against the one real cross-thread
    // hazard: stop()/reset()/release()/flush() called from a different thread
    // while the async dispatch thread (or, in sync mode, a second app thread
    // -- AOSP documents separate input/output threads as supported even in
    // sync mode) is mid-access.
    private final Object mAsyncLock = new Object();
    private Callback mCallback;
    private Thread mAsyncThread;
    private volatile boolean mAsyncRunning;
    private volatile boolean mAsyncPaused;

    // Descriptor (Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I)V.
    public void configure(MediaFormat format, android.view.Surface surface,
                          MediaCrypto crypto, int flags) {
        if (format == null) return;
        String mime = format.getString(MediaFormat.KEY_MIME, "");
        mStatMime = mime;
        mFormatId = 0;
        mIsVideo = false;
        // formatId 1-2 pre-date this table; 3-13 route to the AudioToolbox
        // (and, for Vorbis, stb_vorbis) formats media_decode.mm's
        // macapk_md_dec_open added -- one mime compare per row, matching
        // docs/audiotoolbox-codec-widening_2026-08-16.md section 2's
        // formatId<->AudioFormatID map exactly. Only Vorbis (13) needs more
        // than a mime compare here: its csd is packed differently below.
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) mFormatId = 1;
        else if (MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)) mFormatId = 2;
        else if (MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime)) mFormatId = 3;
        else if (MediaFormat.MIMETYPE_AUDIO_FLAC.equals(mime)) mFormatId = 4;
        else if (MediaFormat.MIMETYPE_AUDIO_ALAC.equals(mime)) mFormatId = 5;
        else if (MediaFormat.MIMETYPE_AUDIO_AC3.equals(mime)) mFormatId = 6;
        else if (MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mime)) mFormatId = 7;
        else if (MediaFormat.MIMETYPE_AUDIO_AMR_NB.equals(mime)) mFormatId = 8;
        else if (MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(mime)) mFormatId = 9;
        else if (MediaFormat.MIMETYPE_AUDIO_G711_ALAW.equals(mime)) mFormatId = 10;
        else if (MediaFormat.MIMETYPE_AUDIO_G711_MLAW.equals(mime)) mFormatId = 11;
        else if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) mFormatId = 12;
        else if (MediaFormat.MIMETYPE_AUDIO_VORBIS.equals(mime)) mFormatId = 13;
        else if (mime.startsWith("video/") && isVideoMimeSupported(mime)) mIsVideo = true;
        // else: unsupported (an audio codec neither backend knows, or a video
        // mime this host's VideoToolbox can't decode) -> honest no-op codec.
        mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0);
        mChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
        mInCap = resolveInputCapacity(format, mIsVideo);
        ByteBuffer csd = format.getByteBuffer("csd-0", null);
        if (csd != null) { mCsd0 = new byte[csd.remaining()]; csd.duplicate().get(mCsd0); }
        if (mFormatId == 13) {
            // Vorbis is not AudioConverter-backed (media_decode.mm dispatches
            // it to audio_vorbis_decode.mm's stb_vorbis wrapper instead -- see
            // docs/audiotoolbox-codec-widening_2026-08-16.md section 5), and
            // its opener wants csd-0 (Identification header) AND csd-1 (Setup
            // header) packed into the ONE byte[] nativeDecOpen already
            // accepts, not csd-0 alone: [u32 LE len0][bytes0][u32 LE
            // len1][bytes1], matching media_decode.mm's own
            // parse_vorbis_combined_csd byte for byte (verified against that
            // function's source, not guessed -- section 3b).
            ByteBuffer csd1 = format.getByteBuffer("csd-1", null);
            byte[] c0 = mCsd0 != null ? mCsd0 : new byte[0];
            byte[] c1 = new byte[0];
            if (csd1 != null) { c1 = new byte[csd1.remaining()]; csd1.duplicate().get(c1); }
            mCsd0 = packVorbisCsd(c0, c1);
        }
        if (mIsVideo) {
            mVidMime = mime;
            mVidWidth  = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            mVidHeight = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            ByteBuffer c0 = format.getByteBuffer("csd-0", null);
            mCsd0Vid = c0 != null ? bufToBytes(c0) : null;
            ByteBuffer c1 = format.getByteBuffer("csd-1", null);
            mCsd1Vid = c1 != null ? bufToBytes(c1) : null;
            mSurface = surface;
            mHasSurface = (surface != null && surface.isValid());
            // Bind the video plane's geometry to the Surface's owner view (a
            // no-op registry clear for SurfaceTexture-backed and bare
            // Surfaces). Done at configure time because that is the moment
            // the codec-output-to-surface association exists on real Android.
            if (mHasSurface) {
                android.view.SurfaceView.macapkRegisterVideoSink(surface);
            }
            routeVideoTo(null, mHasSurface ? surface : null);
        } else {
            mVidMime = null;
            mSurface = null;
            mHasSurface = false;
        }
    }
    private static byte[] bufToBytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }
    // See the mFormatId==13 branch in configure(): media_decode.mm's
    // parse_vorbis_combined_csd reads this exact layout back
    // (rd32 there is also little-endian, byte for byte).
    private static byte[] packVorbisCsd(byte[] c0, byte[] c1) {
        byte[] out = new byte[8 + c0.length + c1.length];
        putLE32(out, 0, c0.length);
        System.arraycopy(c0, 0, out, 4, c0.length);
        putLE32(out, 4 + c0.length, c1.length);
        System.arraycopy(c1, 0, out, 8 + c0.length, c1.length);
        return out;
    }
    private static void putLE32(byte[] buf, int off, int v) {
        buf[off]     = (byte) (v & 0xff);
        buf[off + 1] = (byte) ((v >>> 8) & 0xff);
        buf[off + 2] = (byte) ((v >>> 16) & 0xff);
        buf[off + 3] = (byte) ((v >>> 24) & 0xff);
    }
    // VP8 (libvpx, runtime/src/video_vp8_decode.mm) is a SECOND video
    // backend, parallel to VideoToolbox: statically linked, so -- unlike
    // every VideoToolbox codec -- its availability is not a per-host probe
    // result, it is unconditional. mVidMime (set in configure(), unchanged
    // until the next configure()) is enough to route every call site below
    // to the right backend; no extra field needed.
    private static final String MIME_VP8 = "video/x-vnd.on2.vp8";
    private boolean isVp8() { return MIME_VP8.equals(mVidMime); }
    // VP9 (libvpx, runtime/src/video_vp9_decode.mm) is a THIRD video
    // backend, same shape as VP8: VideoToolbox probes VP9 as genuinely
    // absent on this host (OSStatus -12906 kVTCouldNotFindVideoDecoderErr
    // even fed a real config record -- docs/videotoolbox-probe-audit_2026-
    // 08-16.md section 3), so libvpx (already linked for VP8) serves it
    // instead, also unconditionally available.
    private static final String MIME_VP9 = "video/x-vnd.on2.vp9";
    private boolean isVp9() { return MIME_VP9.equals(mVidMime); }
    // Single source of truth for "can this host decode this mime" -- see
    // com.macapk.loader.NativeMedia.nativeVideoSupportedMimes's own doc:
    // MediaCodecList/MediaCodecInfo are expected to read the SAME
    // NativeMedia methods rather than keep a second, hand-maintained list
    // that could drift from what this class actually opens a decoder for.
    private static boolean isVideoMimeSupported(String mime) {
        if (MIME_VP8.equals(mime)) return true;   // libvpx: always present, see isVp8()'s doc
        if (MIME_VP9.equals(mime)) return true;   // libvpx: always present, see isVp9()'s doc
        String[] mimes = com.macapk.loader.NativeMedia.nativeVideoSupportedMimes();
        if (mimes == null) return false;
        for (String m : mimes) if (m.equals(mime)) return true;
        return false;
    }
    // VP8's and VP9's five natives each (nativeVp{8,9}Open/_Decode/_Poll/
    // _Flush/_Close) are registered by native_methods.cpp calling
    // video_vp8::RegisterNativeVp8Methods / video_vp9::
    // RegisterNativeVp9Methods. VP8's registration call was ORIGINALLY
    // deferred to a separate, concurrently-routed change (v1.0.984's own
    // "WIRING STILL NEEDED" note) and stayed missing until this session
    // landed both calls together (see native_methods.cpp) -- so this catch
    // guarded a REAL, live gap for VP8, not just a theoretical race, and
    // keeping it now (for both engines) costs nothing and survives any
    // future re-ordering of registration vs. first use. Catching here turns
    // a missing registration into "failed to open" (0), which start()'s
    // EXISTING loud-CodecException-on-zero path already handles cleanly --
    // one failure shape, not a raw-link-error one.
    private static long safeVp8Open(String mime, int w, int h, byte[] csd0, byte[] csd1, boolean wantSurface) {
        try {
            return com.macapk.loader.NativeMedia.nativeVp8Open(mime, w, h, csd0, csd1, wantSurface);
        } catch (LinkageError e) {
            return 0;
        }
    }
    private static long safeVp9Open(String mime, int w, int h, byte[] csd0, byte[] csd1, boolean wantSurface) {
        try {
            return com.macapk.loader.NativeMedia.nativeVp9Open(mime, w, h, csd0, csd1, wantSurface);
        } catch (LinkageError e) {
            return 0;
        }
    }
    // Shared by queueInputBuffer's two call sites (a real access unit, and
    // the codec-config+EOS combination). Once mVidDec != 0 the open call
    // already succeeded for whichever backend isVp8()/isVp9() names, so no
    // LinkageError guard is needed here (see safeVp8Open's doc: open is the
    // only site that can observe the not-yet-registered window).
    private void vidDecode(byte[] au, long presentationTimeUs, boolean eos) {
        if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Decode(mVidDec, au, presentationTimeUs, eos);
        else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Decode(mVidDec, au, presentationTimeUs, eos);
        else com.macapk.loader.NativeMedia.nativeVidDecode(mVidDec, au, presentationTimeUs, eos);
    }
    // AOSP: registering a callback before start() (or after flush(), which
    // must be followed by start() again in async mode -- see flush() below)
    // is what switches this codec into asynchronous mode.
    public void setCallback(Callback cb) { mCallback = cb; }
    public void setCallback(Callback cb, android.os.Handler handler) { mCallback = cb; }
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, android.os.Handler handler) {}
    public void setVideoScalingMode(int mode) {}
    // AOSP allows swapping the output surface after configure() (adaptive
    // surface change). Our present path targets one shared per-window video
    // layer for a SurfaceView-owned or bare Surface, and the Surface's own
    // SurfaceTexture when it has one (routeVideoTo, below; see cocoa_bridge.mm's
    // CocoaPresentVideoFrame). The geometry is surface-specific too -- a
    // SurfaceView-owned Surface pins the video plane to that view's on-screen
    // rect (SurfaceView.macapkRegisterVideoSink) -- which is why both must
    // follow every swap.
    public void setOutputSurface(android.view.Surface surface) {
        android.view.Surface previous = mSurface;
        mSurface = surface;
        mHasSurface = (surface != null && surface.isValid());
        if (mHasSurface) {
            android.view.SurfaceView.macapkRegisterVideoSink(surface);
        }
        routeVideoTo(previous, mHasSurface ? surface : null);
    }

    // Decoded frames go first to SurfaceTexture's one active video target
    // (most recent registration wins; see SurfaceTexture.macapkOfferDecodedFrame),
    // and only otherwise to the window's video plane. `new Surface(texture)`
    // registers its texture, so a Surface made BEFORE the real output kept the
    // frames: Media3 configures the codec with its PlaceholderSurface (a
    // SurfaceTexture-backed Surface) when prepare() runs before the app's view
    // is attached, then switches here with setOutputSurface, and the video
    // stayed black with its sound playing (GitHub #147). So the target follows
    // this codec's output, as the BufferQueue behind a Surface does on a
    // device: the previous output's texture gives the target up, if it still
    // holds it, and a texture-backed new output takes it.
    private static void routeVideoTo(android.view.Surface previous, android.view.Surface next) {
        android.graphics.SurfaceTexture was =
                previous != null ? previous.macapkGetSurfaceTexture() : null;
        android.graphics.SurfaceTexture now =
                next != null ? next.macapkGetSurfaceTexture() : null;
        if (was != null && was != now) was.macapkUnregisterVideoTargetIfSelf();
        // A released texture would displace a live target with one that
        // takes no frames (macapkHasActiveVideoTarget reads it as none).
        if (now != null && !now.isReleased()) now.macapkRegisterVideoTarget();
    }

    public void start() {
        synchronized (mAsyncLock) {
            if (mFormatId != 0 && mSampleRate > 0 && mChannels > 0) {
                mDec = com.macapk.loader.NativeMedia.nativeDecOpen(mFormatId, mSampleRate, mChannels, mCsd0);
                if (mDec == 0) {
                    // Loud, not silent -- the same reasoning as the video
                    // branch below (added alongside it), extended here now
                    // that mFormatId covers 11 more formats
                    // (docs/audiotoolbox-codec-widening_2026-08-16.md):
                    // configure() already matched a mime MediaCodecList
                    // advertises as decodable, so a failure to actually open
                    // one here is a real error (bad csd, an unsupported
                    // sample-rate/channel combination for this formatId),
                    // never the expected "unrecognized mime" shape. Without
                    // this, a listed-but-dead audio codec would reproduce
                    // exactly the silent PlayTube-class failure the video
                    // branch's own comment describes, just one mFormatId
                    // family later.
                    throw new CodecException(0, "AudioToolbox: failed to open decoder for formatId " + mFormatId);
                }
            } else if (mIsVideo) {
                mVidDec = isVp8()
                    ? safeVp8Open(mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, mHasSurface)
                    : isVp9()
                    ? safeVp9Open(mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, mHasSurface)
                    : com.macapk.loader.NativeMedia.nativeVidOpen(
                          mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, mHasSurface);
                if (mVidDec == 0) {
                    // Loud, not silent. AOSP throws from start() when a codec
                    // genuinely cannot be stood up, and this IS that case: at
                    // configure() time isVideoMimeSupported(mVidMime) already
                    // said yes (VideoToolbox reported it decodable on this
                    // host), so a failure here means the csd was unusable or
                    // VTDecompressionSessionCreate itself failed -- a real
                    // error, never the expected "unsupported mime" shape. The
                    // previous version of this class silently dropped every
                    // buffer for ANY video mime, which is exactly the bug
                    // measured on PlayTube ("dispatch #2200, zero
                    // exceptions"); this class must not reproduce that shape
                    // for a codec it claimed to support.
                    throw new CodecException(0, "VideoToolbox: failed to open decoder for " + mVidMime);
                }
            }
            mIn  = new ByteBuffer[IN_BUFS];
            mOut = new ByteBuffer[OUT_BUFS];
            mOutPixbuf = mIsVideo ? new long[OUT_BUFS] : null;
            mOutPts    = mIsVideo ? new long[OUT_BUFS] : null;
            mInFree.clear(); mOutFree.clear(); mOutQ.clear();
            for (int i = 0; i < IN_BUFS;  i++) { mIn[i] = ByteBuffer.allocateDirect(mInCap); mInFree.add(i); }
            for (int i = 0; i < OUT_BUFS; i++) mOutFree.add(i);
            mStarted = true; mFormatReported = false;
            com.macapk.loader.MediaStats.codecStart(mStat, mStatMime, mHasSurface);
            // AOSP: "you must call start() after flush() ... to transition
            // the codec to the Running sub-state and start receiving input
            // buffers" in async mode -- start() always clears the pause, and
            // spawns the dispatch thread the first time a callback is armed.
            mAsyncPaused = false;
            if (mCallback != null && mAsyncThread == null) {
                mAsyncRunning = true;
                mAsyncThread = new Thread(new Runnable() {
                    @Override public void run() { asyncDispatchLoop(); }
                }, "MediaCodec-async");
                mAsyncThread.setDaemon(true);
                mAsyncThread.start();
            }
        }
    }
    public void stop()    { com.macapk.loader.MediaStats.codecEnd(mStat, "stop");
                            stopAsyncDispatch(); releaseDec(); mStarted = false; }
    public void reset()   { com.macapk.loader.MediaStats.codecEnd(mStat, "reset");
                            stopAsyncDispatch(); releaseDec(); mStarted = false; }
    public void release() {
        com.macapk.loader.MediaStats.codecEnd(mStat, "release");
        stopAsyncDispatch(); releaseDec();
        drainTimedFrames(true);
        // Drop the video-plane geometry binding IF it is still ours (a newer
        // codec's registration wins -- macapkUnregisterVideoSink compares the
        // Surface identity, so releasing an old codec after a new one bound
        // does not blank the new one's video).
        android.view.SurfaceView.macapkUnregisterVideoSink(mSurface);
    }
    public void flush() {
        synchronized (mAsyncLock) {
            mOutQ.clear();
            releasePendingVideoFrames();
            if (mVidDec != 0) {
                if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Flush(mVidDec);
                else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Flush(mVidDec);
                else com.macapk.loader.NativeMedia.nativeVidFlush(mVidDec);
            }
            if (mIn  != null)  { mInFree.clear();  for (int i = 0; i < IN_BUFS;  i++) mInFree.add(i); }
            if (mOut != null)  { mOutFree.clear(); for (int i = 0; i < OUT_BUFS; i++) mOutFree.add(i); }
            // AOSP: in async mode flush() suspends buffer delivery until the
            // next start() (see start() above); sync-mode callers (mCallback
            // == null) are unaffected.
            if (mCallback != null) mAsyncPaused = true;
        }
    }
    // Signals the async dispatch thread to stop and forgets it, WITHOUT
    // waiting for it to actually exit. That is safe rather than sloppy: the
    // thread only ever touches mDec/mIn/mOut/mInFree/mOutFree/mOutQ inside
    // mAsyncLock (see asyncDispatchLoop), so the caller's next acquisition of
    // that same lock -- releaseDec(), right after this returns -- cannot run
    // concurrently with anything the dispatch thread is still mid-way through;
    // it can only ever run strictly before or strictly after.
    private void stopAsyncDispatch() {
        synchronized (mAsyncLock) {
            mAsyncRunning = false;
            mAsyncThread = null;
        }
    }
    // Releases any polled-but-not-yet-released video frames (native
    // CVPixelBuffers) before the index pool that tracks them gets dropped or
    // reset, so they don't leak. Called with mAsyncLock held.
    /** AOSP MediaCodec hands decoded video out in PRESENTATION order: the
     *  codec component reorders B-frames. VideoToolbox does not -- its output
     *  callback fires in DECODE order even with kVTDecodeFrame_Enable
     *  TemporalProcessing (FFmpeg's videotoolbox hwaccel reorders itself for
     *  the same reason). Measured on ytdlnis's ExoPlayer preview (H.264 720p
     *  with B-frames): consecutive release timestamps stepped -66.7 / +33.4 /
     *  +133.5 ms, i.e. frames shown out of order -- the "stuttery" playback
     *  (owner report 2026-10-19). This holds VideoToolbox output in a
     *  pts-ordered window and emits the earliest frame once more than
     *  mReorderDepth frames are held (or the stream ended). The depth starts at
     *  2 and grows whenever a frame arrives older than one already emitted
     *  (that frame is dropped, as a component never emits backwards), capped at
     *  16 = H.264's max_dec_frame_buffering. MACAPK_VIDEO_REORDER=0 restores
     *  decode-order output. VP8/VP9 (libvpx) already output in order. */
    static final boolean VIDEO_REORDER = !"0".equals(System.getenv("MACAPK_VIDEO_REORDER"));
    private final VideoReorder mReorder = new VideoReorder();
    private static final java.util.function.LongConsumer RELEASE_FRAME =
        com.macapk.loader.NativeMedia::nativeVidReleaseFrame;

    /** Called with mAsyncLock held, like tryDequeueOutputLocked0. */
    private long[] reorderPoll() {
        final long dec = mVidDec;
        return mReorder.poll(() -> com.macapk.loader.NativeMedia.nativeVidPoll(dec), RELEASE_FRAME);
    }
    private void resetReorder() { mReorder.reset(RELEASE_FRAME); }

    /** The presentation-order window (see VIDEO_REORDER above). A frame is
     *  {pixbuf, ptsUs, flags}; pixbuf 0 is the end-of-stream marker. Static and
     *  source-agnostic so debug/videoreorder_gate.sh drives it offline. */
    static final class VideoReorder {
        private final java.util.PriorityQueue<long[]> held =
            new java.util.PriorityQueue<>(16, (a, b) -> Long.compare(a[1], b[1]));
        private long[] eos;
        int depth = 2;
        private long lastPts = Long.MIN_VALUE;

        long[] poll(java.util.function.Supplier<long[]> src, java.util.function.LongConsumer release) {
            long[] f;
            while (eos == null && (f = src.get()) != null) {
                if (f[0] == 0) { eos = f; break; }               // EOS marker: drain everything
                if (f[1] < lastPts) {
                    // Arrived after a later frame already went out: a component
                    // never emits backwards, so drop it and widen the window.
                    release.accept(f[0]);
                    if (depth < 16) depth++;
                    continue;
                }
                held.add(f);
            }
            if (!held.isEmpty() && (held.size() > depth || eos != null)) {
                long[] out = held.poll();
                lastPts = out[1];
                return out;
            }
            if (eos != null && held.isEmpty()) {
                long[] e = eos;
                eos = null;
                lastPts = Long.MIN_VALUE;
                return e;
            }
            return null;
        }
        /** flush / stop / release: drop everything held; the learned depth stays. */
        void reset(java.util.function.LongConsumer release) {
            for (long[] f : held) release.accept(f[0]);
            held.clear();
            eos = null;
            lastPts = Long.MIN_VALUE;
        }
    }

    private void releasePendingVideoFrames() {
        resetReorder();
        drainTimedFrames(false);
        if (mOutPixbuf == null) return;
        for (int i = 0; i < mOutPixbuf.length; i++) {
            if (mOutPixbuf[i] != 0) {
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(mOutPixbuf[i]);
                mOutPixbuf[i] = 0;
            }
        }
    }
    private void releaseDec() {
        synchronized (mAsyncLock) {
            if (mDec != 0) { com.macapk.loader.NativeMedia.nativeDecClose(mDec); mDec = 0; }
            if (mVidDec != 0) {
                releasePendingVideoFrames();
                if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Close(mVidDec);
                else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Close(mVidDec);
                else com.macapk.loader.NativeMedia.nativeVidClose(mVidDec);
                mVidDec = 0;
            }
            mOutQ.clear();
        }
    }

    // The async dispatch loop: mirrors dequeueInputBuffer/dequeueOutputBuffer's
    // own selection logic (tryDequeueOutputLocked, shared with the sync path)
    // but PUSHES the result through Callback instead of waiting for the app to
    // poll. The callback is invoked directly (this codebase's existing
    // MediaPlayer/SoundPool listener-dispatch idiom, not a real Handler/Looper
    // post) and, critically, OUTSIDE mAsyncLock -- so when the app calls back
    // into queueInputBuffer()/releaseOutputBuffer() from within its callback
    // (the normal, expected async usage), that call is a fresh, non-nested
    // lock acquisition made from THIS SAME thread, never a concurrent one from
    // a second thread. That is what lets the existing synchronous
    // queueInputBuffer/releaseOutputBuffer bodies below be reused completely
    // unchanged for the async path, for BOTH audio and video.
    private void asyncDispatchLoop() {
        while (true) {
            Callback cb = null;
            boolean formatChanged = false;
            int inIdx = -1, outIdx = -1;
            BufferInfo info = null;
            synchronized (mAsyncLock) {
                if (!mAsyncRunning) return;
                if (mCallback != null && !mAsyncPaused) {
                    cb = mCallback;
                    BufferInfo tmp = new BufferInfo();
                    int r = tryDequeueOutputLocked(tmp);
                    if (r == INFO_OUTPUT_FORMAT_CHANGED) {
                        formatChanged = true;
                    } else if (r >= 0) {
                        outIdx = r;
                        info = tmp;
                    } else if (!mInFree.isEmpty()) {
                        inIdx = mInFree.poll();
                    }
                }
            }
            if (cb != null) {
                try {
                    if (formatChanged)    cb.onOutputFormatChanged(this, getOutputFormat());
                    else if (outIdx >= 0) cb.onOutputBufferAvailable(this, outIdx, info);
                    else if (inIdx >= 0)  cb.onInputBufferAvailable(this, inIdx);
                } catch (Throwable ignore) {}
            }
            if (!formatChanged && outIdx < 0 && inIdx < 0) {
                // Nothing to do this pass (buffers full / queue empty): a
                // short poll interval rather than a real wait/notify, same
                // trade-off ToneGenerator's render loop already makes
                // elsewhere in this package for a non-hot-path thread.
                try { Thread.sleep(5); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /**
     * How big an input buffer this configuration needs.
     *
     * <p>Order mirrors a real component's input-port sizing:
     * <ol>
     *   <li>{@link MediaFormat#KEY_MAX_INPUT_SIZE} when the client declared it.
     *       This is the authoritative answer -- the client is stating the
     *       largest access unit it will ever queue, and AOSP's ACodec/CCodec
     *       size the input port to at least it. media3 always sets it for
     *       video.</li>
     *   <li>Otherwise, for video, the same 4:2:0 estimate media3 itself uses to
     *       COMPUTE that key, so a client that never sets it still gets a
     *       frame-sized buffer: pixels rounded up to whole 16x16 macroblocks,
     *       times 3/2 for 4:2:0, divided by a minimum compression ratio of 2
     *       (media3's value for H264/H265/VP8/VP9) -- i.e. maxPixels * 3 / 4.
     *       ~1.5 MiB at 1080p.</li>
     *   <li>Otherwise the historic 64 KiB floor, which is what every audio
     *       path has always used and is ample for one AAC/Opus access unit.</li>
     * </ol>
     *
     * <p>The result is never below {@link #IN_CAP}, so no configuration that
     * works today gets a smaller buffer than it had.
     */
    private static int resolveInputCapacity(MediaFormat format, boolean isVideo) {
        // A declared size that is absurd would otherwise become 8 direct
        // allocations of it. Real components fail configure() on an
        // unsatisfiable port size; clamping is the gentler equivalent and
        // 32 MiB is far past any real access unit (8K HEVC keyframes are
        // single-digit MiB).
        final int CEILING = 32 << 20;
        int declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        if (declared > 0) {
            return Math.min(CEILING, Math.max(IN_CAP, declared));
        }
        if (isVideo) {
            int w = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            int h = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            if (w > 0 && h > 0) {
                long maxPixels = (long) ceilDiv(w, 16) * ceilDiv(h, 16) * 16 * 16;
                long est = (maxPixels * 3) / 4;
                return (int) Math.min(CEILING, Math.max(IN_CAP, est));
            }
        }
        return IN_CAP;
    }

    private static int ceilDiv(int num, int den) { return (num + den - 1) / den; }

    public ByteBuffer[] getInputBuffers()  { return mIn  != null ? mIn  : new ByteBuffer[0]; }
    public ByteBuffer[] getOutputBuffers() { return mOut != null ? mOut : new ByteBuffer[0]; }
    public ByteBuffer getInputBuffer(int index)  { return (mIn  != null && index >= 0 && index < mIn.length)  ? mIn[index]  : null; }
    // AOSP: "the ByteBuffer... will be null if configured with an output
    // surface". Even without a surface our video output is an opaque native
    // pixel buffer with no meaningful ByteBuffer view -- use getOutputImage.
    public ByteBuffer getOutputBuffer(int index) {
        if (mIsVideo) return null;
        return (mOut != null && index >= 0 && index < mOut.length) ? mOut[index] : null;
    }
    public Image getInputImage(int index) { return null; }
    public Image getOutputImage(int index) {
        if (!mIsVideo || mOutPixbuf == null || index < 0 || index >= mOutPixbuf.length) return null;
        long pb = mOutPixbuf[index];
        if (pb == 0) return null;
        long ptsUs = (mOutPts != null) ? mOutPts[index] : 0;
        return new NativeVideoImage(pb, ptsUs, mVidWidth, mVidHeight);
    }

    // android.media.Image backed by a polled, not-yet-released decoded video
    // frame: a real, spec-shaped YUV_420_888 image (3 planes, 4:2:0
    // subsampling, real per-plane row/pixel strides, real per-frame geometry
    // and crop, real timestamp), not a shape-only stand-in.
    //
    // WHERE THE BYTES COME FROM, AND WHY THIS CONVERTS RATHER THAN EXPOSING
    // NATIVE PLANES DIRECTLY. Every VideoToolbox session -- Surface or not --
    // is opened with a packed-BGRA destination pixel format
    // (kCVPixelFormatType_32BGRA; see video_toolbox.mm's
    // CreateSessionInternal), because the Surface-composite path
    // (releaseOutputBuffer(index, true) -> nativeVidRender ->
    // CocoaPresentVideoFrame) wants exactly that packed-RGB shape for Metal
    // compositing. A BGRA CVPixelBuffer is genuinely non-planar
    // (CVPixelBufferGetPlaneCount == 0): there is no Y/Cb/Cr plane to hand
    // out directly, so producing YUV_420_888 here means converting.
    //
    // This class converts in Java, from the SAME raw BGRA bytes the Surface
    // path already reads via NativeMedia.nativeVidFrameBgra (that native
    // method itself is unchanged). The forward matrix is the standard
    // ITU-R BT.601 full-range RGB->YCbCr transform -- the exact algebraic
    // inverse of the BT.601 full-range YCbCr->RGB transform
    // android.graphics.YuvImage already uses elsewhere in this codebase (see
    // that file's yuvToArgb: r=y+1.402v, g=y-0.344136u-0.714136v,
    // b=y+1.772u): Y=0.299R+0.587G+0.114B, Cb=-0.168736R-0.331264G+0.5B+128,
    // Cr=0.5R-0.418688G-0.081312B+128 (see y601/cb601/cr601 below). Chroma is
    // a genuine 2x2 box average of the source pixels, not a point-sampled
    // skip: because the matrix is affine, averaging the four source R/G/B
    // triples first and running the Cb/Cr formula once is exactly equal to
    // averaging four independently-computed Cb/Cr pairs, so this loses
    // nothing in exchange for 3 additions instead of 6.
    //
    // WHAT IS STILL APPROXIMATE, NAMED PLAINLY: the planes are DERIVED from
    // VideoToolbox's own internal YUV->RGB conversion rather than being its
    // native planar output, so there is a second 8-bit quantization step
    // this class cannot avoid without changing video_toolbox.mm's
    // destination pixel format -- which this session does not touch (a
    // concurrently-active sibling lane owns that file this turn; see
    // CLAUDE.md's per-turn file ownership). The exact, zero-extra-loss fix
    // is a one-line change to CreateSessionInternal's destAttrs: request
    // kCVPixelFormatType_420YpCbCr8BiPlanarFullRange (matching this class's
    // full-range choice) instead of BGRA, then read
    // CVPixelBufferGetBaseAddressOfPlane(0)/(1) +
    // GetBytesPerRowOfPlane(0)/(1) directly for the two planes -- exactly
    // the pattern camera_capture.mm already uses to feed ImageReader's own
    // YUV_420_888 CameraImage (see that class's "PLANE LAYOUT" doc in
    // ImageReader.java, verified by debug/yuv_map_test.sh). Once that lands,
    // this class's conversion loop collapses to a straight copy of the real
    // planes and the two accompanying attempt-then-retry buffer sizing and
    // the colour-matrix math below can both go.
    //
    // ROW STRIDE. The SOURCE bgra[] is read using the REAL CoreVideo-reported
    // stride (whs[2] from nativeVidFrameBgra == CVPixelBufferGetBytesPerRow)
    // -- never assumed from width, since CoreVideo pads rows and an assumed
    // stride silently shears the image. (The PRIOR version of this class had
    // exactly that bug: it read nativeVidFrameBgra's real stride into whs[2]
    // and then discarded it, reporting getRowStride()==mW*4 regardless.) The
    // OUTPUT planes are freshly allocated, tightly packed buffers -- no
    // padding exists to preserve, because nothing maps them onto native
    // memory -- so their reported row strides are simply the true byte width
    // of a packed row: truthful by construction, not a second guess.
    //
    // GEOMETRY / CROPPING. Width/height start as the configure()-time
    // MediaFormat values (the only thing known before any frame has
    // decoded) and are OVERWRITTEN, the first time getPlanes() actually
    // reads a frame, with the real CVPixelBufferGetWidth/Height CoreVideo
    // reports for that buffer. That is what makes getCropRect() (inherited
    // from Image, defaults to the full width/height) reflect the real
    // display size rather than a possibly-padded coded size.
    //
    // COLOUR RANGE. getOutputFormat() reports this class's choice (full
    // range, BT.601) via KEY_COLOR_RANGE/KEY_COLOR_STANDARD/
    // KEY_COLOR_TRANSFER for the no-Surface case only -- meaningless for the
    // Surface path, which never runs this conversion.
    //
    // LIFETIME. Does NOT own the native pixel buffer -- releaseOutputBuffer
    // (index, ...), not close(), is what frees IT (matching MediaCodec's
    // real ownership model, where the index rather than the Image is the
    // handle the codec tracks). close() only drops this instance's OWN
    // derived buffer, so it is collectible right away instead of pinned for
    // whatever remains of this MediaCodec's life, and makes further access
    // throw like AOSP's own Image does post-close.
    private final class NativeVideoImage extends Image {
        private final long mPixbuf;
        private final long mTimestampUs;
        private int mW, mH;        // corrected to the real decoded size once known
        private Plane[] mPlanes;   // materialized lazily, once, on first getPlanes()
        private boolean mClosed;

        NativeVideoImage(long pixbuf, long timestampUs, int w, int h) {
            mPixbuf = pixbuf;
            mTimestampUs = timestampUs;
            mW = Math.max(1, w);
            mH = Math.max(1, h);
        }

        private void checkOpen() {
            // Mirrors ImageReader.CameraImage's own checkOpen() (this
            // codebase's established convention for this exact message) and
            // AOSP's Image.throwISEIfImageIsInvalid(): plane buffers point
            // at memory whose lifetime is not this Image's own past close(),
            // so a getter that kept answering after close() could hand the
            // app a window onto freed or reused memory.
            if (mClosed) throw new IllegalStateException("Image is already closed");
        }

        @Override public int getFormat() { checkOpen(); return android.graphics.ImageFormat.YUV_420_888; }
        @Override public int getWidth()  { checkOpen(); return mW; }
        @Override public int getHeight() { checkOpen(); return mH; }
        @Override public long getTimestamp() { checkOpen(); return mTimestampUs; }

        @Override public Plane[] getPlanes() {
            checkOpen();
            if (mPlanes == null) materialize();
            return mPlanes;
        }

        @Override public void close() {
            mClosed = true;
            mPlanes = null;
        }

        // Reads the real decoded BGRA pixels (via the existing, unchanged
        // NativeMedia.nativeVidFrameBgra), converts to planar YUV_420_888,
        // and builds the 3 direct-ByteBuffer Plane views. Runs at most once
        // per Image; a second call to getPlanes() is the normal AOSP
        // "you may call this more than once" case and must not recompute.
        private void materialize() {
            byte[] bgra = null;
            int[] whs = new int[3];
            int rc = -1;
            // CoreVideo/IOSurface may pad each row past width*4 bytes, and
            // the native readout (macapk_vtb_frame_copy_bgra, see its own
            // doc) reports failure with no size hint when the buffer is too
            // small -- so this rounds generously up front (a page's worth of
            // per-row headroom, negligible next to any real frame) and only
            // falls back to a second, much larger attempt if even that was
            // short. Belt and suspenders, not a guess dressed up as a fact:
            // either attempt reads back the REAL stride in whs[2] on
            // success, which is what every byte offset below actually uses.
            for (int attempt = 0; attempt < 2 && rc != 0; attempt++) {
                int stride = ((mW * 4) + 4095) & ~4095;
                if (attempt == 1) stride *= 4;
                byte[] tryBuf = new byte[stride * mH];
                rc = com.macapk.loader.NativeMedia.nativeVidFrameBgra(mPixbuf, tryBuf, whs);
                if (rc == 0) bgra = tryBuf;
            }
            if (rc != 0 || bgra == null || whs[0] <= 0 || whs[1] <= 0) {
                mPlanes = new Plane[0];
                return;
            }
            final int srcW = whs[0], srcH = whs[1], srcStride = whs[2];
            mW = srcW;
            mH = srcH;

            final int chromaW = (srcW + 1) / 2;
            final int chromaH = (srcH + 1) / 2;
            final int ySize = srcW * srcH;
            final int cSize = chromaW * chromaH * 2;
            final byte[] yuv = new byte[ySize + cSize];

            for (int by = 0; by < srcH; by += 2) {
                final int bh = Math.min(2, srcH - by);
                for (int bx = 0; bx < srcW; bx += 2) {
                    final int bw = Math.min(2, srcW - bx);
                    long sumR = 0, sumG = 0, sumB = 0;
                    int cnt = 0;
                    for (int dy = 0; dy < bh; dy++) {
                        final int row = by + dy;
                        final int rowOff = row * srcStride;
                        for (int dx = 0; dx < bw; dx++) {
                            final int col = bx + dx;
                            final int off = rowOff + col * 4;
                            final int b = bgra[off]     & 0xff;
                            final int g = bgra[off + 1] & 0xff;
                            final int r = bgra[off + 2] & 0xff;
                            yuv[row * srcW + col] = (byte) y601(r, g, b);
                            sumR += r; sumG += g; sumB += b; cnt++;
                        }
                    }
                    final double ar = sumR / (double) cnt, ag = sumG / (double) cnt, ab = sumB / (double) cnt;
                    final int cIdx = ySize + ((by / 2) * chromaW + (bx / 2)) * 2;
                    yuv[cIdx]     = (byte) cb601(ar, ag, ab);
                    yuv[cIdx + 1] = (byte) cr601(ar, ag, ab);
                }
            }

            final ByteBuffer direct = ByteBuffer.allocateDirect(yuv.length);
            direct.put(yuv);
            direct.rewind();

            // Three planes over one shared allocation: the same
            // position/limit/slice technique AOSP's own internal MediaImage
            // uses over one native buffer, and the same semiplanar
            // convention this codebase's ImageReader.CameraImage already
            // documents for camera frames -- plane 0 is Y; planes 1 and 2
            // are two interleaved views of the SAME chroma region, one byte
            // apart, each with pixelStride 2. Cr's slice is one byte shorter
            // than Cb's so its view (which starts one byte later) never
            // reads past the allocation's true end.
            final ByteBuffer yView  = sliceAt(direct, 0, ySize);
            final ByteBuffer cbView = sliceAt(direct, ySize, cSize);
            final ByteBuffer crView = sliceAt(direct, ySize + 1, cSize - 1);
            final int cRowStride = chromaW * 2;

            mPlanes = new Plane[] {
                plane(yView, srcW, 1),
                plane(cbView, cRowStride, 2),
                plane(crView, cRowStride, 2),
            };
        }

        private ByteBuffer sliceAt(ByteBuffer base, int offset, int length) {
            ByteBuffer d = base.duplicate();
            d.clear();
            d.position(offset);
            d.limit(offset + length);
            return d.slice();
        }

        private Plane plane(final ByteBuffer buf, final int rowStride, final int pixelStride) {
            return new Plane() {
                @Override public ByteBuffer getBuffer() { return buf.duplicate(); }
                @Override public int getPixelStride() { return pixelStride; }
                @Override public int getRowStride() { return rowStride; }
            };
        }

        // Standard ITU-R BT.601 full-range RGB -> YCbCr. KR=0.299, KB=0.114
        // (identical for the COLOR_STANDARD_BT601_PAL and _NTSC MediaFormat
        // constants -- they differ only in line count, 625 vs 525, not
        // colourimetry, per AOSP MediaFormat.java's own javadoc on those two
        // fields, both "KR = 0.299, KB = 0.114").
        private int y601(int r, int g, int b) {
            return clamp255((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
        }
        private int cb601(double r, double g, double b) {
            return clamp255((int) Math.round(-0.168736 * r - 0.331264 * g + 0.5 * b + 128));
        }
        private int cr601(double r, double g, double b) {
            return clamp255((int) Math.round(0.5 * r - 0.418688 * g - 0.081312 * b + 128));
        }
        private int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    }

    public int dequeueInputBuffer(long timeoutUs) {
        synchronized (mAsyncLock) {
            if (!mStarted || mInFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
            return mInFree.poll();
        }
    }
    public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
        if (PRESENT_TRACE && mIsVideo) traceIn();
        synchronized (mAsyncLock) {
            if (!mStarted) return;
            boolean eos = (flags & BUFFER_FLAG_END_OF_STREAM) != 0;
            boolean codecConfig = (flags & BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (size > 0 && !codecConfig) com.macapk.loader.MediaStats.codecIn(mStat);
            byte[] au = null;
            if (size > 0 && !codecConfig && mIn != null && index >= 0 && index < mIn.length) {
                ByteBuffer b = mIn[index].duplicate(); b.position(offset); b.limit(offset + size);
                au = new byte[size]; b.get(au);
            }
            if (mVidDec != 0) {
                // AOSP: codec-specific data "is automatically submitted to
                // the codec upon start(); you MUST NOT submit this data
                // explicitly" -- our video open() already built the format
                // description from the configure()-time csd-0/csd-1, so a
                // codec-config-flagged buffer here is a legitimate no-op for
                // the payload (matching the audio branch below), though an
                // EOS flag riding along with one must still be honored.
                if (!codecConfig) {
                    vidDecode(au, presentationTimeUs, eos);
                } else if (eos) {
                    vidDecode(null, presentationTimeUs, true);
                }
            } else if (mDec != 0 && !codecConfig) {
                byte[] pcm = com.macapk.loader.NativeMedia.nativeDecDecode(mDec, au, eos);
                if (pcm != null && (pcm.length > 0 || eos))
                    mOutQ.add(new OutEntry(pcm, presentationTimeUs, eos ? BUFFER_FLAG_END_OF_STREAM : 0));
            } else if (eos) {
                mOutQ.add(new OutEntry(new byte[0], presentationTimeUs, BUFFER_FLAG_END_OF_STREAM));
            }
            if (index >= 0 && index < IN_BUFS) mInFree.add(index);
        }
    }
    public void queueSecureInputBuffer(int index, int offset, CryptoInfo info,
                                       long presentationTimeUs, int flags) {
        queueInputBuffer(index, offset, 0, presentationTimeUs, flags);
    }
    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
        synchronized (mAsyncLock) {
            return tryDequeueOutputLocked(info);
        }
    }
    // Shared output-selection logic for the synchronous dequeueOutputBuffer()
    // and the async dispatch loop (asyncDispatchLoop mirrors this rather than
    // hand-rolling its own copy -- see that method's header comment). MUST be
    // called with mAsyncLock held. Handles audio (mOutQ of decoded PCM),
    // video (a poll into the native VideoToolbox output queue), and the
    // no-codec stub (neither mDec nor mVidDec open) uniformly.
    private int tryDequeueOutputLocked(BufferInfo info) {
        int r = tryDequeueOutputLocked0(info);
        com.macapk.loader.MediaStats.codecPoll(mStat, r);
        return r;
    }
    private int tryDequeueOutputLocked0(BufferInfo info) {
        if (!mStarted) return INFO_TRY_AGAIN_LATER;
        // AOSP reports the output format once before the first buffer.
        if (!mFormatReported) { mFormatReported = true; return INFO_OUTPUT_FORMAT_CHANGED; }
        if (mVidDec != 0) {
            if (mOutFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
            long[] polled = isVp8()
                ? com.macapk.loader.NativeMedia.nativeVp8Poll(mVidDec)
                : isVp9()
                ? com.macapk.loader.NativeMedia.nativeVp9Poll(mVidDec)
                : VIDEO_REORDER ? reorderPoll()
                : com.macapk.loader.NativeMedia.nativeVidPoll(mVidDec);
            if (polled == null) return INFO_TRY_AGAIN_LATER;
            int oi = mOutFree.poll();
            if (PRESENT_TRACE) traceOut();
            mOutPixbuf[oi] = polled[0];
            mOutPts[oi] = polled[1];
            // No real "compressed size" applies to an opaque decoded pixel
            // buffer; report a nonzero placeholder for a real frame (some
            // consumers treat size==0 as "nothing to render" regardless of
            // flags) and 0 for the synthetic end-of-stream-only marker
            // (polled[0]==0, see video_toolbox.mm's eos handling).
            int sz = polled[0] != 0 ? Math.max(1, mVidWidth * mVidHeight * 4) : 0;
            info.set(0, sz, polled[1], (int) polled[2]);
            return oi;
        }
        if (mOutQ.isEmpty() || mOutFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
        OutEntry e = mOutQ.poll();
        int oi = mOutFree.poll();
        // ⚠ LITTLE_ENDIAN IS MANDATORY, and ByteBuffer.wrap() does NOT give it.
        // java.nio defaults every new buffer to BIG_ENDIAN, but ExoPlayer/media3
        // asserts the opposite before touching a single sample:
        //   DefaultAudioSink.handleBuffer -> checkArgument(buffer.order() == LITTLE_ENDIAN)
        // (androidx/media release, DefaultAudioSink.java). So the FIRST decoded
        // PCM buffer we handed it threw IllegalArgumentException, ExoPlayer
        // wrapped that as "ExoPlaybackException: Unexpected runtime error", and
        // playback died in the AUDIO renderer before the video renderer ever
        // opened a decoder.
        //
        // MEASURED 2026-08-16 on PlayTube: a real Linus Tech Tips video resolved
        // its stream, the audio decoder opened and decoded, then this killed the
        // player -- 0:00/0:00 on screen with ZERO video-toolbox opens, which read
        // exactly like "video decode does not work" and was nothing of the kind.
        // Little-endian is also simply correct: Android PCM 16-bit is
        // little-endian, and so is every Apple Silicon host we run on.
        mOut[oi] = ByteBuffer.wrap(e.pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        info.set(0, e.pcm.length, e.pts, e.flags);
        return oi;
    }
    public void releaseOutputBuffer(int index, boolean render) {
        synchronized (mAsyncLock) {
            if (mIsVideo && mOutPixbuf != null && index >= 0 && index < mOutPixbuf.length) {
                long pb = mOutPixbuf[index];
                mOutPixbuf[index] = 0;
                if (pb != 0) {
                    // render=true is the whole point of this project's video
                    // work: composite the decoded frame onto the configured
                    // Surface for real (video_toolbox.mm's
                    // macapk_vtb_render_frame -> cocoa_bridge.mm's
                    // CocoaPresentVideoFrame), not merely decode it.
                    if (PRESENT_TRACE && !render) mTraceAppDrops++;
                    if (render && mHasSurface) {
                        if (PRESENT_TRACE) tracePresent();
                        com.macapk.loader.NativeMedia.nativeVidRender(pb, mVidWidth, mVidHeight);
                        com.macapk.loader.MediaStats.codecRendered(mStat);
                    }
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(pb);
                }
                if (mOut != null && index < mOut.length) mOut[index] = null;
                mOutFree.add(index);
                return;
            }
            if (mOut != null && index >= 0 && index < mOut.length) { mOut[index] = null; mOutFree.add(index); }
        }
    }
    /** AOSP releaseOutputBuffer(index, renderTimestampNs): the frame is shown
     *  AT that System.nanoTime() timestamp (SurfaceFlinger latches, per vsync,
     *  the newest queued buffer that is due and drops the ones it supersedes;
     *  a timestamp more than a second away is treated as "now"). We ignored the
     *  timestamp and presented on release. ExoPlayer's MediaCodecVideoRenderer
     *  releases each frame up to 50 ms EARLY with its vsync-aligned display
     *  time, so frames landed early by a varying amount: uneven cadence, i.e.
     *  judder (ytdlnis's preview player, owner report 2026-10-19).
     *  MACAPK_VIDEO_TIMED_RELEASE=0 restores present-on-release. */
    public void releaseOutputBuffer(int index, long renderTimestampNs) {
        if (!VIDEO_TIMED_RELEASE || !mIsVideo || !mHasSurface) { releaseOutputBuffer(index, true); return; }
        long pb = 0;
        synchronized (mAsyncLock) {
            if (mOutPixbuf != null && index >= 0 && index < mOutPixbuf.length) {
                pb = mOutPixbuf[index];
                mOutPixbuf[index] = 0;
                if (mOut != null && index < mOut.length) mOut[index] = null;
                mOutFree.add(index);
            } else {
                if (mOut != null && index >= 0 && index < mOut.length) { mOut[index] = null; mOutFree.add(index); }
                return;
            }
        }
        if (pb == 0) return;
        long now = System.nanoTime();
        if (PRESENT_TRACE) { traceEarly(renderTimestampNs - now); traceDue(renderTimestampNs); }
        long due = renderTimestampNs;
        if (due < now || due - now > 1_000_000_000L) due = now;   // late, or "not within a second": now
        synchronized (mTimedLock) {
            mTimed.addLast(new long[]{ pb, due });
            if (mTimedThread == null) {
                mTimedQuit = false;
                mTimedThread = new Thread(this::timedPresentLoop, "MediaCodec:VideoPresent");
                mTimedThread.setDaemon(true);
                mTimedThread.start();
            }
            mTimedLock.notifyAll();
        }
    }
    /** MACAPK_VIDEO_PRESENT_TRACE=1: every 120 presents, log the present-to-
     *  present interval spread (the judder measure) and superseded drops. */
    static final boolean PRESENT_TRACE = "1".equals(System.getenv("MACAPK_VIDEO_PRESENT_TRACE"));
    private long mTraceLast, mTraceDrops, mTraceAppDrops;
    private final long[] mTraceIv = new long[120];
    private int mTraceN;
    private long mOutLast; private final long[] mOutIv = new long[120]; private int mOutN;
    private final long[] mEarly = new long[120]; private int mEarlyN;
    private long mInLast; private final long[] mInIv = new long[120]; private int mInN;
    private void traceIn() {
        long t = System.nanoTime();
        if (mInLast != 0) mInIv[mInN++] = t - mInLast;
        mInLast = t;
        if (mInN == mInIv.length) { traceSummary("decoder input", mInIv); mInN = 0; }
    }
    private long mDueLast; private final long[] mDueIv = new long[120]; private int mDueN;
    private void traceDue(long due) {
        if (mDueLast != 0) mDueIv[mDueN++] = due - mDueLast;
        mDueLast = due;
        if (mDueN == mDueIv.length) { traceSummary("release due-to-due", mDueIv); mDueN = 0; }
    }
    private void traceOut() {
        long t = System.nanoTime();
        if (mOutLast != 0) mOutIv[mOutN++] = t - mOutLast;
        mOutLast = t;
        if (mOutN == mOutIv.length) { traceSummary("decoder output", mOutIv); mOutN = 0; }
    }
    private void traceEarly(long early) {
        mEarly[mEarlyN++] = early;
        if (mEarlyN == mEarly.length) { traceSummary("release early(+)/late(-)", mEarly); mEarlyN = 0; }
    }
    private static void traceSummary(String what, long[] v) {
        long[] a = v.clone(); java.util.Arrays.sort(a);
        com.macapk.loader.NativeLog.i("MediaCodec", String.format("%s: p5=%.1fms p50=%.1f p95=%.1f min=%.1f max=%.1f",
            what, a[6] / 1e6, a[60] / 1e6, a[113] / 1e6, a[0] / 1e6, a[119] / 1e6));
    }
    private void tracePresent() {
        long t = System.nanoTime();
        if (mTraceLast != 0) mTraceIv[mTraceN++] = t - mTraceLast;
        mTraceLast = t;
        if (mTraceN == mTraceIv.length) {
            long[] a = mTraceIv.clone();
            java.util.Arrays.sort(a);
            double mean = 0; for (long v : a) mean += v; mean /= a.length;
            double sd = 0; for (long v : a) sd += (v - mean) * (v - mean); sd = Math.sqrt(sd / a.length);
            com.macapk.loader.NativeLog.i("MediaCodec", String.format(
                "video present: n=%d mean=%.1fms sd=%.1fms p5=%.1f p50=%.1f p95=%.1f max=%.1f superseded=%d appDropped=%d timed=%b",
                a.length, mean / 1e6, sd / 1e6, a[6] / 1e6, a[60] / 1e6, a[113] / 1e6, a[119] / 1e6,
                mTraceDrops, mTraceAppDrops, VIDEO_TIMED_RELEASE));
            mTraceN = 0; mTraceDrops = 0; mTraceAppDrops = 0;
        }
    }
    static final boolean VIDEO_TIMED_RELEASE =
        !"0".equals(System.getenv("MACAPK_VIDEO_TIMED_RELEASE"));
    private final Object mTimedLock = new Object();
    private final java.util.ArrayDeque<long[]> mTimed = new java.util.ArrayDeque<>();
    private Thread mTimedThread;
    private boolean mTimedQuit;

    /** Present queued frames at their due time. When several are due at once
     *  only the newest is shown and the older ones are released unshown --
     *  SurfaceFlinger's latch rule. Frames are released in queue order, which
     *  is ExoPlayer's presentation order. */
    private void timedPresentLoop() {
        synchronized (mTimedLock) {
            while (!mTimedQuit) {
                long[] head = mTimed.peekFirst();
                if (head == null) {
                    try { mTimedLock.wait(); } catch (InterruptedException e) { return; }
                    continue;
                }
                long wait = head[1] - System.nanoTime();
                if (wait > 0) {
                    try { mTimedLock.wait(wait / 1_000_000L, (int) (wait % 1_000_000L)); }
                    catch (InterruptedException e) { return; }
                    continue;
                }
                long now = System.nanoTime();
                long[] show = mTimed.pollFirst();
                while (!mTimed.isEmpty() && mTimed.peekFirst()[1] <= now) {
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);   // superseded
                    show = mTimed.pollFirst();
                    if (PRESENT_TRACE) mTraceDrops++;
                }
                if (PRESENT_TRACE) tracePresent();
                // Rendering under mTimedLock: drainTimedFrames (flush / stop /
                // release) waits for an in-flight present instead of freeing
                // the buffer underneath it.
                try {
                    com.macapk.loader.NativeMedia.nativeVidRender(show[0], mVidWidth, mVidHeight);
                    com.macapk.loader.MediaStats.codecRendered(mStat);
                } catch (Throwable ignored) {}
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);
            }
        }
    }

    /** Release every queued, not-yet-shown frame (flush / stop / release). */
    private void drainTimedFrames(boolean quit) {
        synchronized (mTimedLock) {
            for (long[] f : mTimed) com.macapk.loader.NativeMedia.nativeVidReleaseFrame(f[0]);
            mTimed.clear();
            if (quit) { mTimedQuit = true; mTimedThread = null; }
            mTimedLock.notifyAll();
        }
    }

    public MediaFormat getOutputFormat() {
        if (mIsVideo) {
            MediaFormat f = MediaFormat.createVideoFormat(mVidMime, mVidWidth, mVidHeight);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                mHasSurface ? MediaFormat.COLOR_FormatSurface : MediaFormat.COLOR_FormatYUV420Flexible);
            if (!mHasSurface) {
                // Describes NativeVideoImage's OWN derived conversion, not
                // VideoToolbox's internal decode colourimetry -- meaningless
                // for the Surface path, which composites VideoToolbox's raw
                // BGRA directly and never runs that conversion. See that
                // class's doc for the exact matrix these three keys name.
                f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
                f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            return f;
        }
        int sr = mDec != 0 ? com.macapk.loader.NativeMedia.nativeDecOutSampleRate(mDec) : mSampleRate;
        int ch = mDec != 0 ? com.macapk.loader.NativeMedia.nativeDecOutChannels(mDec) : mChannels;
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, sr, ch);
        f.setInteger(MediaFormat.KEY_PCM_ENCODING, 2);  // ENCODING_PCM_16BIT
        return f;
    }
    public MediaFormat getInputFormat() { return new MediaFormat(); }
    public MediaFormat getOutputFormat(int index) { return getOutputFormat(); }

    public MediaCodecInfo getCodecInfo() { return MediaCodecInfo.forName(mName); }

    public void setParameters(android.os.Bundle params) {}

    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String CODEC = "android.media.mediacodec.codec";
        public static final String ENCODER = "android.media.mediacodec.encoder";
        public static final String HEIGHT = "android.media.mediacodec.height";
        public static final String MIME_TYPE = "android.media.mediacodec.mime";
        public static final String MODE = "android.media.mediacodec.mode";
        public static final String MODE_AUDIO = "audio";
        public static final String MODE_VIDEO = "video";
        public static final String ROTATION = "android.media.mediacodec.rotation";
        public static final String SECURE = "android.media.mediacodec.secure";
        public static final String WIDTH = "android.media.mediacodec.width";
    }

    /** AOSP throws this from a block-model call made on a codec that was not
     *  configured with CONFIGURE_FLAG_USE_BLOCK_MODEL. */
    public static class IncompatibleWithBlockModelException extends RuntimeException {
        IncompatibleWithBlockModelException(String message) { super(message); }
    }

    /** AOSP throws this when a queued BufferInfo carries a flag combination the
     *  codec cannot accept. */
    public static class InvalidBufferFlagsException extends RuntimeException {
        InvalidBufferFlagsException(String message) { super(message); }
    }

    public android.os.PersistableBundle getMetrics() { return new android.os.PersistableBundle(); }

    public void subscribeToVendorParameters(java.util.List<String> names) { }
    public void unsubscribeFromVendorParameters(java.util.List<String> names) { }

    public android.view.Surface createInputSurface() { return null; }
    public void signalEndOfInputStream() { }
    public void detachOutputSurface() { }

    // =====================================================================
    // API-gap close-out. Signatures from /tmp/aosp_api/android16.txt; behaviour
    // from frameworks/base/media/java/android/media/MediaCodec.java
    // (android16-release).
    //
    // Two families land here, both of which this backend genuinely lacks, and
    // both of which report that the AOSP way rather than by pretending:
    //   * ENCODE (createPersistentInputSurface / setInputSurface). The native
    //     bridge is decode-only -- com.macapk.loader.NativeMedia has
    //     nativeDecOpen/nativeVidDecode and no encoder counterpart -- so there
    //     is no producer to hand a surface to. createInputSurface() above
    //     already returns null for the same reason.
    //   * The BLOCK MODEL (getQueueRequest / getOutputFrame / LinearBlock).
    //     AOSP itself throws IllegalStateException from these unless the codec
    //     was configured with CONFIGURE_FLAG_USE_BLOCK_MODEL, and this backend
    //     cannot be, so that documented exception is the terminal answer.
    // =====================================================================

    /**
     * API 23. A surface that can be reused across several encoder sessions.
     * AOSP returns a real surface from the codec service; there is no encoder
     * here, so this returns the same empty, no-producer Surface the rest of this
     * tree hands out for a sink that consumes nothing -- non-null, because
     * AOSP's contract is @NonNull and an app that gets null here NPEs at its
     * own setInputSurface call instead of learning anything.
     */
    public static android.view.Surface createPersistentInputSurface() {
        return new android.view.Surface();
    }

    /**
     * API 23. Feeds an encoder from a persistent input surface. AOSP throws
     * IllegalStateException when the codec is not a configured encoder -- which
     * it never is here (no encoder backend) -- so that is the answer, rather
     * than accepting a surface whose frames go nowhere.
     */
    public void setInputSurface(android.view.Surface surface) {
        java.util.Objects.requireNonNull(surface, "surface must not be null");
        throw new IllegalStateException("MediaCodec is not a configured encoder");
    }

    /** API 31, tunneled playback only. Mac.apk has no tunneled path
     *  (AudioDeviceInfo.getEncapsulationModes() reports none), so the listener
     *  is retained and never fires. */
    public void setOnFirstTunnelFrameReadyListener(android.os.Handler handler,
            OnFirstTunnelFrameReadyListener listener) {
        mFirstTunnelFrameReadyListener = listener;
    }

    private OnFirstTunnelFrameReadyListener mFirstTunnelFrameReadyListener;

    /** API 28. Selects a presentation inside a next-gen-audio bitstream. Every
     *  codec here decodes to PCM and carries no presentations, so AOSP's own
     *  answer for that case -- IllegalStateException, "not applicable" -- is
     *  what an app gets. */
    public void setAudioPresentation(AudioPresentation presentation) {
        java.util.Objects.requireNonNull(presentation, "audio presentation must not be null");
        throw new IllegalStateException("Codec does not carry audio presentations");
    }

    // ---- vendor parameters (API 30) ---------------------------------------
    // AOSP: "@return a list of supported vendor parameter names", empty when the
    // codec exposes none. AudioToolbox/VideoToolbox expose no vendor parameters,
    // so the list is genuinely empty -- and getParameterDescriptor() must agree,
    // returning null for every name (AOSP's own answer for a name the codec does
    // not support).

    public java.util.List<String> getSupportedVendorParameters() {
        return java.util.Collections.emptyList();
    }

    /** @Nullable in AOSP: null when the name is not a supported parameter. */
    public ParameterDescriptor getParameterDescriptor(String name) {
        java.util.Objects.requireNonNull(name, "name must not be null");
        return null;
    }

    /** Describes one vendor parameter. AOSP MediaCodec.ParameterDescriptor. */
    public static class ParameterDescriptor {
        private final String mName;
        private final int mType;
        ParameterDescriptor(String name, int type) { mName = name; mType = type; }
        public String getName() { return mName; }
        /** One of the MediaFormat.TYPE_* values. */
        public int getType() { return mType; }
    }

    // ---- block model (API 30) ---------------------------------------------

    /** AOSP throws IllegalStateException unless the codec was configured with
     *  CONFIGURE_FLAG_USE_BLOCK_MODEL; this backend cannot be. */
    public QueueRequest getQueueRequest(int index) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** Same contract as getQueueRequest. */
    public OutputFrame getOutputFrame(int index) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /**
     * API 30. Wraps a HardwareBuffer so its planes can be read as ByteBuffers.
     * AOSP returns NULL when the buffer's format or usage flags make it
     * unmappable. This backend never produces a HardwareBuffer-backed output
     * (getOutputFrame above cannot be reached), and there is no mapping path, so
     * null -- AOSP's own "cannot map this" answer -- is correct.
     *
     * STATIC, as in AOSP ("public static @Nullable Image mapHardwareBuffer",
     * frameworks/base android14-release media/java/android/media/
     * MediaCodec.java:3318) and the SDK-34 android.jar. It was an instance
     * method here, so every app call (invokestatic) threw
     * IncompatibleClassChangeError before this body ran. Found by
     * debug/apishape_gate.sh (KNOWN_OPEN, struck 2026-09-26).
     */
    public static Image mapHardwareBuffer(android.hardware.HardwareBuffer hardwareBuffer) {
        java.util.Objects.requireNonNull(hardwareBuffer, "hardwareBuffer must not be null");
        return null;
    }

    /** API 34, block model only. Same terminal answer as getQueueRequest. */
    public void queueInputBuffers(int index, java.util.ArrayDeque<BufferInfo> bufferInfos) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** API 34, block model + DRM. Unreachable twice over: no block model, and
     *  Mac.apk implements no DRM at all (see MediaDrm). */
    public void queueSecureInputBuffers(int index, java.util.ArrayDeque<BufferInfo> bufferInfos,
            java.util.ArrayDeque<CryptoInfo> cryptoInfos) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** One queued input in the block model. Never produced here. */
    public final class QueueRequest {
        QueueRequest() { }
        private IllegalStateException notBlockModel() {
            return new IllegalStateException("The codec is not configured for block model");
        }
        public QueueRequest setLinearBlock(LinearBlock block, int offset, int size) {
            throw notBlockModel();
        }
        public QueueRequest setMultiFrameLinearBlock(LinearBlock block,
                java.util.ArrayDeque<BufferInfo> infos) {
            throw notBlockModel();
        }
        public QueueRequest setEncryptedLinearBlock(LinearBlock block, int offset, int size,
                CryptoInfo cryptoInfo) {
            throw notBlockModel();
        }
        public QueueRequest setMultiFrameEncryptedLinearBlock(LinearBlock block,
                java.util.ArrayDeque<BufferInfo> infos,
                java.util.ArrayDeque<CryptoInfo> cryptoInfos) {
            throw notBlockModel();
        }
        public QueueRequest setHardwareBuffer(android.hardware.HardwareBuffer buffer) {
            throw notBlockModel();
        }
        public QueueRequest setPresentationTimeUs(long presentationTimeUs) {
            throw notBlockModel();
        }
        public QueueRequest setFlags(int flags) { throw notBlockModel(); }
        public QueueRequest setIntegerParameter(String key, int value) { throw notBlockModel(); }
        public QueueRequest setLongParameter(String key, long value) { throw notBlockModel(); }
        public QueueRequest setFloatParameter(String key, float value) { throw notBlockModel(); }
        public QueueRequest setStringParameter(String key, String value) { throw notBlockModel(); }
        public QueueRequest setByteBufferParameter(String key, java.nio.ByteBuffer value) {
            throw notBlockModel();
        }
        public void queue() { throw notBlockModel(); }
    }

    /** One dequeued output in the block model. Never produced here. */
    public static final class OutputFrame {
        OutputFrame() { }
        /** @Nullable in AOSP. */
        public LinearBlock getLinearBlock() { return null; }
        /** @Nullable in AOSP. */
        public android.hardware.HardwareBuffer getHardwareBuffer() { return null; }
        public long getPresentationTimeUs() { return 0L; }
        public int getFlags() { return 0; }
        public MediaFormat getFormat() { return new MediaFormat(); }
        public java.util.Set<String> getChangedKeys() { return java.util.Collections.emptySet(); }
        public java.util.ArrayDeque<BufferInfo> getBufferInfos() {
            return new java.util.ArrayDeque<BufferInfo>();
        }
    }

    /**
     * A shared memory block for the block model. AOSP's obtain() is @Nullable
     * and documented to return null when a block "cannot be obtained", which is
     * permanently the case here -- so an app that checks the return value takes
     * its non-block-model path, which is what we want.
     */
    public static final class LinearBlock {
        LinearBlock() { }

        public static LinearBlock obtain(int capacity, String[] codecNames) {
            java.util.Objects.requireNonNull(codecNames, "codecNames must not be null");
            return null;
        }

        /** AOSP: whether the named codecs can share a block without copying.
         *  Nothing here supports the block model, so false. */
        public static boolean isCodecCopyFreeCompatible(String[] codecNames) {
            java.util.Objects.requireNonNull(codecNames, "codecNames must not be null");
            return false;
        }

        public boolean isMappable() { return false; }

        /** AOSP throws IllegalStateException for a block that is not mappable. */
        public java.nio.ByteBuffer map() {
            throw new IllegalStateException("The linear block is not mappable");
        }

        public void recycle() { }

        @Override protected void finalize() throws Throwable {
            try { recycle(); } finally { super.finalize(); }
        }
    }

    /** AOSP declares finalize() so a dropped codec releases its native decoder
     *  instead of leaking it. release() is idempotent. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}
