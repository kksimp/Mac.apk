// android.media.MediaMuxer -- multiplexes encoded elementary streams into a
// container file. AOSP frameworks/base media/java/android/media/MediaMuxer.java.
//
// MP4 and 3GPP are written for real, by the host's writer
// (com.macapk.loader.HostMedia; windows/host/mp4_writer.h): H.264 video and
// AAC audio, which is what MediaCodec encodes here. The samples go to the
// file as they are written, the index when stop() is called -- a muxer that is
// never stopped leaves a file that does not play, as on Android. WebM, Ogg
// and HEIF have no writer: the constructor refuses them with AOSP's own
// exception for a format it does not know, and addTrack refuses a track the
// MP4 writer cannot hold.
//
// A FileDescriptor is written through a temporary file that stop() copies
// into it: the writer needs a file it can open by name and seek in.
package android.media;
import java.nio.ByteBuffer;

public final class MediaMuxer {
    public static final class OutputFormat {
        public static final int MUXER_OUTPUT_MPEG_4 = 0;
        public static final int MUXER_OUTPUT_WEBM = 1;
        public static final int MUXER_OUTPUT_3GPP = 2;
        public static final int MUXER_OUTPUT_HEIF = 3;
        public static final int MUXER_OUTPUT_OGG = 4;
        private OutputFormat() {}
    }

    // AOSP's states.
    private static final int MUXER_STATE_UNINITIALIZED = -1;
    private static final int MUXER_STATE_INITIALIZED = 0;
    private static final int MUXER_STATE_STARTED = 1;
    private static final int MUXER_STATE_STOPPED = 2;

    private int mState = MUXER_STATE_UNINITIALIZED;
    private long mHandle;
    private int mTracks = 0;
    private java.io.File mTmpFile;                 // the FileDescriptor form's
    private java.io.FileDescriptor mFd;

    public MediaMuxer(String path, int format) throws java.io.IOException {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        checkFormat(format);
        // Created through java.io first: a path the app may not write fails
        // here, with the exception AOSP's RandomAccessFile gives.
        new java.io.FileOutputStream(path).close();
        open(new java.io.File(path).getAbsolutePath(), format);
    }

    public MediaMuxer(java.io.FileDescriptor fd, int format) throws java.io.IOException {
        if (fd == null || !fd.valid()) throw new IllegalArgumentException("fd must be valid");
        checkFormat(format);
        mTmpFile = java.io.File.createTempFile("winapk_muxer", ".tmp");
        mTmpFile.deleteOnExit();
        mFd = fd;
        open(mTmpFile.getAbsolutePath(), format);
    }

    private static void checkFormat(int format) {
        if (format == OutputFormat.MUXER_OUTPUT_MPEG_4 || format == OutputFormat.MUXER_OUTPUT_3GPP) return;
        // AOSP throws this for a format outside its list; WebM, Ogg and HEIF are
        // outside this host's.
        throw new IllegalArgumentException("format: " + format + " is invalid (this host writes MP4 and 3GPP)");
    }

    private void open(String path, int format) throws java.io.IOException {
        try {
            mHandle = com.macapk.loader.HostMedia.nativeMuxOpen(path, format == OutputFormat.MUXER_OUTPUT_3GPP);
        } catch (LinkageError e) {
            mHandle = 0;
        }
        if (mHandle == 0) {
            if (mTmpFile != null) { mTmpFile.delete(); mTmpFile = null; }
            throw new java.io.IOException("MediaMuxer: cannot write " + path);
        }
        mState = MUXER_STATE_INITIALIZED;
    }

    private static byte[] bytes(ByteBuffer b) {
        if (b == null) return null;
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    public int addTrack(MediaFormat format) {
        if (format == null) throw new IllegalArgumentException("format must not be null.");
        if (mState != MUXER_STATE_INITIALIZED) throw new IllegalStateException("Muxer is not initialized.");
        String mime = format.getString(MediaFormat.KEY_MIME, "");
        boolean video = MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime);
        if (!video && !MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) {
            // AOSP's message for a track its writer refuses.
            throw new IllegalArgumentException("Failed to add the track to the muxer: unsupported mime " + mime
                    + " (this host writes video/avc and audio/mp4a-latm)");
        }
        int track = com.macapk.loader.HostMedia.nativeMuxAddTrack(mHandle, video,
                format.getInteger(MediaFormat.KEY_WIDTH, 0), format.getInteger(MediaFormat.KEY_HEIGHT, 0),
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0),
                format.getInteger(MediaFormat.KEY_BIT_RATE, 0),
                bytes(format.getByteBuffer("csd-0", null)), bytes(format.getByteBuffer("csd-1", null)));
        if (track < 0) throw new IllegalArgumentException("Failed to add the track to the muxer");
        mTracks = track + 1;
        return track;
    }

    public void setOrientationHint(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270)
            throw new IllegalArgumentException("Unsupported angle: " + degrees);
        if (mState != MUXER_STATE_INITIALIZED)
            throw new IllegalStateException("Can't set rotation degrees due to wrong state.");
        com.macapk.loader.HostMedia.nativeMuxSetOrientation(mHandle, degrees);
    }

    public void setLocation(float latitude, float longitude) {
        int latitudex10000 = Math.round(latitude * 10000);
        int longitudex10000 = Math.round(longitude * 10000);
        if (latitudex10000 > 900000 || latitudex10000 < -900000)
            throw new IllegalArgumentException("Latitude: " + latitude + " out of range.");
        if (longitudex10000 > 1800000 || longitudex10000 < -1800000)
            throw new IllegalArgumentException("Longitude: " + longitude + " out of range");
        if (mState != MUXER_STATE_INITIALIZED)
            throw new IllegalStateException("Can't set location due to wrong state.");
        com.macapk.loader.HostMedia.nativeMuxSetLocation(mHandle, latitude, longitude);
    }

    public void start() {
        if (mState != MUXER_STATE_INITIALIZED || mHandle == 0)
            throw new IllegalStateException("Can't start due to wrong state.");
        if (!com.macapk.loader.HostMedia.nativeMuxStart(mHandle))
            throw new IllegalStateException("Failed to start the muxer");   // no track, or the disk
        mState = MUXER_STATE_STARTED;
    }

    public void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (trackIndex < 0 || trackIndex >= mTracks) throw new IllegalArgumentException("trackIndex is invalid");
        if (byteBuf == null) throw new IllegalArgumentException("byteBuffer must not be null");
        if (bufferInfo == null) throw new IllegalArgumentException("bufferInfo must not be null");
        if (bufferInfo.size < 0 || bufferInfo.offset < 0
                || (bufferInfo.offset + bufferInfo.size) > byteBuf.capacity()) {
            throw new IllegalArgumentException("bufferInfo must specify a valid buffer offset and size");
        }
        if (mState != MUXER_STATE_STARTED) throw new IllegalStateException("Can't write, muxer is not started");
        if (bufferInfo.size == 0) return;   // an end-of-stream marker carries no sample
        byte[] data = new byte[bufferInfo.size];
        ByteBuffer src = byteBuf.duplicate();
        src.limit(bufferInfo.offset + bufferInfo.size);
        src.position(bufferInfo.offset);
        src.get(data);
        if (!com.macapk.loader.HostMedia.nativeMuxWrite(mHandle, trackIndex, data, data.length,
                bufferInfo.presentationTimeUs, bufferInfo.flags)) {
            throw new IllegalStateException("writeSampleData returned an error");
        }
    }

    public void stop() {
        if (mState != MUXER_STATE_STARTED) throw new IllegalStateException("Can't stop due to wrong state.");
        mState = MUXER_STATE_STOPPED;
        boolean ok = com.macapk.loader.HostMedia.nativeMuxFinish(mHandle);
        if (ok && mFd != null) ok = copyToFd();
        // AOSP: "Failed to stop the muxer" when no sample was written or the
        // file could not be completed.
        if (!ok) throw new IllegalStateException("Failed to stop the muxer");
    }

    private boolean copyToFd() {
        // Not closed: the descriptor is the app's.
        java.io.FileOutputStream out = new java.io.FileOutputStream(mFd);
        try (java.io.FileInputStream in = new java.io.FileInputStream(mTmpFile)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public void release() {
        if (mState == MUXER_STATE_STARTED) {
            try { stop(); } catch (IllegalStateException ignore) { }
        }
        if (mHandle != 0) {
            com.macapk.loader.HostMedia.nativeMuxClose(mHandle);
            mHandle = 0;
        }
        if (mTmpFile != null) { mTmpFile.delete(); mTmpFile = null; }
        mState = MUXER_STATE_UNINITIALIZED;
    }

    /** What the file weighs so far; MediaRecorder's size limit reads it. */
    long macapkBytesWritten() {
        return mHandle != 0 ? com.macapk.loader.HostMedia.nativeMuxSize(mHandle) : 0;
    }

    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}
