// android.media.MediaExtractor -- demuxes a media container into compressed
// access units. Backed by the AudioToolbox AudioFile demuxer in
// runtime/src/media_decode.mm (via com.macapk.loader.NativeMedia). Audio only
// (one audio track); video tracks are not surfaced by this path yet. The
// compressed access units it returns are decoded by MediaCodec (AudioConverter).
package android.media;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.Map;

public final class MediaExtractor {
    private long    mHandle  = 0;
    private boolean mSelected = false;
    private byte[]  mCurrent = null;   // cached pre-read current access unit
    // Temp file backing a FileDescriptor data source; deleted by release().
    private java.io.File mTmpFile = null;

    // Same sanity cap the NDK extractor uses for an fd slice
    // (runtime/translator/src/media_ndk.cpp kMaxSrcBytes): refuse to spool a
    // quarter-gigabyte-plus "audio" region to disk.
    private static final long MAX_SRC_BYTES = 256L << 20;

    // AOSP creates the native JMediaExtractor in the constructor and only
    // release() drops it; getMediaExtractor() == NULL is what the JNI turns into
    // IllegalStateException. So "no data source yet" is NOT that state: asking an
    // extractor that has no source for a track fails the range check
    // (IllegalArgumentException), and only a released extractor throws
    // IllegalStateException. mReleased models exactly that.
    private boolean mReleased = false;

    public MediaExtractor() {}

    public void setDataSource(String path) throws java.io.IOException {
        // AOSP: MediaHTTPService.createHttpServiceBinderIfNecessary(path) calls
        // path.startsWith(...) before the JNI is reached, so null is an NPE.
        if (path == null) {
            throw new NullPointerException("path == null");
        }
        if (mReleased) throw new IllegalStateException();
        if (path.isEmpty()) {
            throw new java.io.IOException("MediaExtractor: path cannot be empty");
        }
        closeSource();
        // Strip a file:// scheme; the native opener wants a filesystem path.
        String p = path;
        if (p.startsWith("file://localhost/")) {
            p = p.substring(16);
        } else if (p.startsWith("file://")) {
            p = p.substring(7);
        } else if (p.startsWith("file:/")) {
            p = p.substring(5);
        }
        mHandle = com.macapk.loader.NativeMedia.nativeDemuxOpen(p);
        if (mHandle == 0)
            throw new java.io.IOException("MediaExtractor: cannot open " + path);
        refreshCurrent();
    }
    public void setDataSource(String path, Map<String, String> headers) throws java.io.IOException { setDataSource(path); }
    public void setDataSource(FileDescriptor fd) throws java.io.IOException {
        // AOSP passes a sentinel length that the native extractor clamps down
        // to the real file size; setDataSource below does the same clamp.
        setDataSource(fd, 0, 0x7ffffffffffffffL);
    }

    /**
     * AOSP opens the [offset, offset+length) region of an already-open fd.
     * Our demux core (macapk_md_demux_open -> AudioToolbox AudioFileOpenURL)
     * opens a PATH, so the region has to exist on disk first. This is the
     * Java-side twin of the NDK extractor's already-shipped
     * AMediaExtractor_setDataSourceFd (runtime/translator/src/media_ndk.cpp):
     * pread the slice into a temp file, demux that, unlink it on release.
     * The reads are positional (pread), so the file offset the caller owns is
     * never disturbed.
     */
    public void setDataSource(FileDescriptor fd, long offset, long length) throws java.io.IOException {
        if (fd == null || !fd.valid())
            throw new java.io.IOException("MediaExtractor: invalid FileDescriptor");
        if (mReleased) throw new IllegalStateException();
        closeSource();
        // Deliberately NOT closed: the descriptor belongs to the caller (an
        // AssetFileDescriptor it may keep using), and a FileInputStream
        // adopted from an existing FileDescriptor registers no GC-time
        // cleanup of its own, so leaving it unclosed cannot close their fd.
        java.io.FileInputStream in = new java.io.FileInputStream(fd);
        java.nio.channels.FileChannel ch = in.getChannel();
        long size = ch.size();
        if (offset < 0) offset = 0;
        if (length <= 0 || (size > 0 && offset + length > size))
            length = (size > offset) ? size - offset : 0;
        if (length <= 0 || length > MAX_SRC_BYTES)
            throw new java.io.IOException("MediaExtractor: unusable fd range off="
                    + offset + " len=" + length);

        java.io.File tmp = java.io.File.createTempFile("macapk_mediaextractor", ".tmp");
        tmp.deleteOnExit();
        boolean copied = false;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
            long remaining = length;
            long pos = offset;
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(remaining, (long) buf.capacity()));
                int n = ch.read(buf, pos);
                if (n <= 0) break;   // short source: demux whatever we got
                out.write(buf.array(), 0, n);
                pos += n;
                remaining -= n;
            }
            copied = remaining != length;
        } finally {
            if (!copied) tmp.delete();
        }
        if (!copied)
            throw new java.io.IOException("MediaExtractor: cannot read fd data source");

        mHandle = com.macapk.loader.NativeMedia.nativeDemuxOpen(tmp.getAbsolutePath());
        if (mHandle == 0 || getTrackCount() <= 0) {
            if (mHandle != 0) { com.macapk.loader.NativeMedia.nativeDemuxClose(mHandle); mHandle = 0; }
            tmp.delete();
            throw new java.io.IOException("MediaExtractor: cannot demux fd data source");
        }
        mTmpFile = tmp;
        refreshCurrent();
    }
    public void setDataSource(MediaDataSource source) {}
    public void setDataSource(android.content.Context context, android.net.Uri uri,
                              Map<String, String> headers) throws java.io.IOException {
        if (uri == null) {
            // AOSP dereferences uri.getScheme() first.
            throw new NullPointerException("uri == null");
        }
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            android.content.res.AssetFileDescriptor afd = null;
            try {
                if (context != null && context.getContentResolver() != null) {
                    afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
                }
            } catch (Exception ignored) {}
            if (afd != null) {
                try {
                    setDataSource(afd);
                    return;
                } finally {
                    try { afd.close(); } catch (Exception ignored) {}
                }
            }
            throw new java.io.IOException("MediaExtractor: cannot open content URI " + uri);
        }
        if (scheme != null && !"file".equalsIgnoreCase(scheme)) {
            throw new java.io.IOException("MediaExtractor: unsupported scheme " + scheme);
        }
        String path = uri.getPath();
        if (path == null) {
            throw new java.io.IOException("MediaExtractor: missing path in uri " + uri);
        }
        setDataSource(path);
    }
    // AOSP: a declared length below 0 means "the whole file", so it drops the
    // slice bounds entirely rather than passing a negative length down.
    public void setDataSource(android.content.res.AssetFileDescriptor afd) throws java.io.IOException {
        // AOSP: Preconditions.checkNotNull(afd).
        if (afd == null) throw new NullPointerException("afd == null");
        if (afd.getDeclaredLength() < 0) {
            setDataSource(afd.getFileDescriptor());
        } else {
            setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
        }
    }

    private void refreshCurrent() {
        mCurrent = (mHandle != 0) ? com.macapk.loader.NativeMedia.nativeDemuxCurrent(mHandle) : null;
    }

    public int getTrackCount() {
        return mHandle != 0 ? com.macapk.loader.NativeMedia.nativeDemuxTrackCount(mHandle) : 0;
    }

    public MediaFormat getTrackFormat(int index) {
        if (mReleased)
            throw new IllegalStateException();
        int trackCount = getTrackCount();
        if (index < 0 || index >= trackCount)
            throw new IllegalArgumentException("Invalid track index: " + index + ", trackCount: " + trackCount);
        MediaFormat f = new MediaFormat();
        int fmt = com.macapk.loader.NativeMedia.nativeDemuxFormatId(mHandle);
        String mime = (fmt == 1) ? MediaFormat.MIMETYPE_AUDIO_AAC
                    : (fmt == 2) ? MediaFormat.MIMETYPE_AUDIO_MPEG
                    : "audio/unknown";
        f.setString(MediaFormat.KEY_MIME, mime);
        f.setInteger(MediaFormat.KEY_SAMPLE_RATE, com.macapk.loader.NativeMedia.nativeDemuxSampleRate(mHandle));
        f.setInteger(MediaFormat.KEY_CHANNEL_COUNT, com.macapk.loader.NativeMedia.nativeDemuxChannels(mHandle));
        long dur = com.macapk.loader.NativeMedia.nativeDemuxDurationUs(mHandle);
        if (dur > 0) f.setLong(MediaFormat.KEY_DURATION, dur);
        byte[] csd0 = com.macapk.loader.NativeMedia.nativeDemuxCsd0(mHandle);
        if (csd0 != null && csd0.length > 0)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        return f;
    }

    public void selectTrack(int index) {
        if (mReleased)
            throw new IllegalStateException();
        if (index < 0 || index >= getTrackCount())
            throw new IllegalArgumentException("Invalid track index: " + index);
        mSelected = true;
    }

    public void unselectTrack(int index) {
        if (mReleased)
            throw new IllegalStateException();
        if (index < 0 || index >= getTrackCount())
            throw new IllegalArgumentException("Invalid track index: " + index);
        mSelected = false;
    }

    public boolean advance() {
        if (mHandle == 0) return false;
        boolean ok = com.macapk.loader.NativeMedia.nativeDemuxAdvance(mHandle) != 0;
        refreshCurrent();
        return ok;
    }

    public int readSampleData(ByteBuffer byteBuf, int offset) {
        if (!mSelected || mCurrent == null) return -1;
        int n = mCurrent.length;
        ByteBuffer dst = byteBuf.duplicate();
        dst.position(offset);
        dst.put(mCurrent, 0, n);
        return n;
    }

    public int  getSampleTrackIndex() { return mCurrent != null ? 0 : -1; }
    public long getSampleTime()       { return (mHandle != 0 && mCurrent != null)
            ? com.macapk.loader.NativeMedia.nativeDemuxSampleTime(mHandle) : -1; }
    public int  getSampleFlags()      { return mCurrent != null ? SAMPLE_FLAG_SYNC : 0; }
    // AOSP declares `public native long getSampleSize()`; an int return is a
    // different descriptor and would not link from SDK-compiled bytecode.
    // frameworks/base/media/java/android/media/MediaExtractor.java
    public long getSampleSize()       { return mCurrent != null ? mCurrent.length : -1L; }

    public void seekTo(long timeUs, int mode) {
        if (mHandle == 0) return;
        com.macapk.loader.NativeMedia.nativeDemuxSeek(mHandle, timeUs);
        refreshCurrent();
    }

    public long getCachedDuration() { return -1; }
    public boolean hasCacheReachedEndOfStream() { return false; }
    public boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info) { return false; }

    /** AOSP release(): drops the native extractor; later calls that need it
     *  throw IllegalStateException. */
    public void release() {
        closeSource();
        mReleased = true;
    }

    /** Closes the current data source only (what a second setDataSource does). */
    private void closeSource() {
        if (mHandle != 0) { com.macapk.loader.NativeMedia.nativeDemuxClose(mHandle); mHandle = 0; }
        // Mirrors ext_close_demux in media_ndk.cpp: the spooled fd slice stays
        // alive only as long as the demuxer that reads it.
        if (mTmpFile != null) { mTmpFile.delete(); mTmpFile = null; }
        mCurrent = null; mSelected = false;
    }

    // =====================================================================
    // API-gap close-out. Signatures from /tmp/aosp_api/android16.txt; behaviour
    // from frameworks/base/media/java/android/media/MediaExtractor.java
    // (android16-release).
    // =====================================================================

    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String FORMAT = "android.media.mediaextractor.fmt";
        public static final String MIME_TYPE = "android.media.mediaextractor.mime";
        public static final String TRACKS = "android.media.mediaextractor.ntrk";
    }

    /**
     * AOSP: the DRM initialisation data (the PSSH boxes) the container declares,
     * "or null if none". This extractor never parses a PSSH box -- Mac.apk
     * implements no DRM at all (see MediaDrm) -- so null is the truthful answer,
     * and it is the one an unprotected file gives on Android too. An app that
     * gets null takes its clear-content path, which is the only path we can
     * actually serve.
     */
    public DrmInitData getDrmInitData() { return null; }

    /** @Nullable in AOSP; the same answer, from the same absence, as
     *  getDrmInitData above. */
    public java.util.Map<java.util.UUID, byte[]> getPsshInfo() { return null; }

    /**
     * API 28. The audio presentations a next-gen-audio (AC-4 / MPEG-H) track
     * declares. @NonNull in AOSP, empty when the track carries none -- which is
     * every track here: the extractor handles ordinary AAC/MP3/container audio
     * and parses no presentation descriptors. Empty is AOSP's own answer for a
     * plain track, not a placeholder.
     */
    public java.util.List<AudioPresentation> getAudioPresentations(int trackIndex) {
        return java.util.Collections.emptyList();
    }

    /**
     * API 26. The conditional-access descrambling info for a track. @Nullable in
     * AOSP: null when the track is not scrambled. Nothing here is scrambled --
     * there is no CAS plugin (see MediaCas) and the extractor cannot open one --
     * so null for every track.
     */
    public CasInfo getCasInfo(int index) { return null; }

    /**
     * @deprecated in AOSP. Attaches a MediaCas so scrambled tracks can be
     * descrambled. A MediaCas cannot be CONSTRUCTED on this host (its
     * constructor throws UnsupportedCasException, see that class), so this is
     * unreachable with a real argument; a null argument is rejected the way
     * AOSP's @NonNull contract requires rather than silently accepted.
     */
    @Deprecated
    public void setMediaCas(MediaCas mediaCas) {
        java.util.Objects.requireNonNull(mediaCas, "mediaCas must not be null");
    }

    /** Describes how one track is scrambled. Never produced here (see
     *  getCasInfo); the type exists so that signature resolves. */
    public static final class CasInfo {
        private final int mSystemId;
        private final MediaCas.Session mSession;
        private final byte[] mPrivateData;

        CasInfo(int systemId, MediaCas.Session session, byte[] privateData) {
            mSystemId = systemId;
            mSession = session;
            mPrivateData = privateData;
        }

        public int getSystemId() { return mSystemId; }
        public MediaCas.Session getSession() { return mSession; }
        /** @Nullable in AOSP. */
        public byte[] getPrivateData() { return mPrivateData; }
    }

    // ---- metrics / log session -------------------------------------------

    private android.media.metrics.LogSessionId mLogSessionId =
            android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;

    /** @NonNull in AOSP; LOG_SESSION_ID_NONE until the app sets one. */
    public android.media.metrics.LogSessionId getLogSessionId() { return mLogSessionId; }

    public void setLogSessionId(android.media.metrics.LogSessionId logSessionId) {
        mLogSessionId = java.util.Objects.requireNonNull(logSessionId,
                "logSessionId must not be null");
    }

    /**
     * AOSP's getMetrics() reports what the native extractor did. The three keys
     * AOSP publishes as public constants are filled in from this extractor's
     * REAL state; the private native counters are omitted rather than
     * zero-filled, because a zero counter reads as a measurement. AOSP returns
     * null when there are no metrics at all, so a caller already handles a thin
     * bundle.
     */
    public android.os.PersistableBundle getMetrics() {
        android.os.PersistableBundle bundle = new android.os.PersistableBundle();
        bundle.putInt(MetricsConstants.TRACKS, getTrackCount());
        if (getTrackCount() > 0) {
            try {
                MediaFormat f = getTrackFormat(0);
                if (f != null && f.containsKey(MediaFormat.KEY_MIME)) {
                    bundle.putString(MetricsConstants.MIME_TYPE,
                            f.getString(MediaFormat.KEY_MIME));
                }
            } catch (Throwable ignore) {
                // A track whose format cannot be read simply contributes no key,
                // which is better than contributing a wrong one.
            }
        }
        return bundle;
    }

    /** AOSP declares finalize() so a dropped extractor releases its native
     *  demuxer and temp file. release() is idempotent. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }

    public static final int SEEK_TO_PREVIOUS_SYNC = 0;
    public static final int SEEK_TO_NEXT_SYNC = 1;
    public static final int SEEK_TO_CLOSEST_SYNC = 2;
    public static final int SAMPLE_FLAG_SYNC = 1;
    public static final int SAMPLE_FLAG_ENCRYPTED = 2;
}
