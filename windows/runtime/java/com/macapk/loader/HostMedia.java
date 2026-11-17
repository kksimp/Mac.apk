// HostMedia -- the host's demuxer, encoders and MP4 writer, for
// android.media.MediaExtractor, MediaMetadataRetriever, MediaCodec (encoding)
// and MediaMuxer. Win.apk's: windows/host/native_media_codec.cpp implements
// these on Media Foundation (the demuxer, H.264 and AAC encoding) and on a
// writer of its own (MP4). NativeMedia's nativeDemux* are upstream's audio-only
// demuxer, which Windows does not have.
//
// Handles are opaque and 0 means "could not": a host without the encoders
// (a Windows edition without Media Foundation's) says so in encoderAvailable,
// and MediaCodecList then lists none.
package com.macapk.loader;

public final class HostMedia {
    private HostMedia() {}

    // ---- demuxer (MediaExtractor) ----
    /** A path (Android or host) or an http(s) URL. */
    public static native long nativeDemuxOpen(String pathOrUrl);
    public static native int nativeDemuxTrackCount(long h);
    /** "key=value" lines: mime, video (0/1), width, height, rotation,
     *  frame-rate, sample-rate, channel-count, bitrate, language. */
    public static native String nativeDemuxTrackInfo(long h, int track);
    /** csd-0 (which = 0) or csd-1 (1) as MediaFormat carries them, or null. */
    public static native byte[] nativeDemuxTrackCsd(long h, int track, int which);
    public static native long nativeDemuxDurationUs(long h);
    public static native void nativeDemuxSelect(long h, int track, boolean on);
    /** The next sample of a selected track, or null at the end;
     *  meta = {track, presentationTimeUs, sync ? 1 : 0}. */
    public static native byte[] nativeDemuxRead(long h, long[] meta);
    /** mode: MediaExtractor.SEEK_TO_*. */
    public static native boolean nativeDemuxSeek(long h, long timeUs, int mode);
    public static native void nativeDemuxClose(long h);

    /** The decoded picture at timeUs (the first one when negative) as ARGB,
     *  dims = {width, height}; null when the file has no video. */
    public static native int[] nativeFrameAt(String path, long timeUs, int[] dims);

    // ---- encoders (MediaCodec) ----
    public static native boolean nativeEncAvailable(boolean video);
    /** H.264. profile: CodecProfileLevel.AVCProfile* (0 = the encoder's
     *  choice); bitrateMode: EncoderCapabilities.BITRATE_MODE_*. */
    public static native long nativeEncOpenVideo(int width, int height, int bitrate, float frameRate,
                                                 float keyIntervalSeconds, int profile, int bitrateMode);
    /** AAC-LC. */
    public static native long nativeEncOpenAudio(int sampleRate, int channels, int bitrate);
    /** One picture, tightly packed; layout 0 = NV12, 1 = I420. */
    public static native boolean nativeEncVideo(long h, byte[] yuv, int layout, long ptsUs, boolean keyFrame);
    /** One picture as width * height ARGB ints. */
    public static native boolean nativeEncVideoArgb(long h, int[] argb, long ptsUs, boolean keyFrame);
    /** 16-bit little-endian PCM, the first `length` bytes. */
    public static native boolean nativeEncAudio(long h, byte[] pcm, int length, long ptsUs);
    public static native void nativeEncFinish(long h);
    /** The next output buffer or null; meta = {presentationTimeUs, MediaCodec.BUFFER_FLAG_*}. */
    public static native byte[] nativeEncPoll(long h, long[] meta);
    public static native void nativeEncSetBitrate(long h, int bitsPerSecond);
    public static native void nativeEncClose(long h);

    // ---- MP4 writer (MediaMuxer) ----
    public static native long nativeMuxOpen(String path, boolean threeGp);
    public static native int nativeMuxAddTrack(long h, boolean video, int width, int height, int sampleRate,
                                               int channels, int bitrate, byte[] csd0, byte[] csd1);
    public static native void nativeMuxSetOrientation(long h, int degrees);
    public static native void nativeMuxSetLocation(long h, float latitude, float longitude);
    public static native boolean nativeMuxStart(long h);
    /** flags: MediaCodec.BUFFER_FLAG_KEY_FRAME and _CODEC_CONFIG. */
    public static native boolean nativeMuxWrite(long h, int track, byte[] data, int length, long ptsUs, int flags);
    /** Writes the index and closes the file; false when the file is unusable. */
    public static native boolean nativeMuxFinish(long h);
    public static native long nativeMuxSize(long h);
    public static native void nativeMuxClose(long h);

    /** Whether this host encodes H.264 (video) or AAC (audio). A runtime
     *  without these natives encodes nothing. */
    public static boolean encoderAvailable(boolean video) {
        try {
            return nativeEncAvailable(video);
        } catch (LinkageError e) {
            return false;
        }
    }

    /** The data source as a file the demuxer can open: the path or URL itself,
     *  or the bytes spooled to a temporary file, which the caller deletes. */
    public static java.io.File spool(java.io.InputStream in, long limit) throws java.io.IOException {
        java.io.File tmp = java.io.File.createTempFile("winapk_media", ".tmp");
        tmp.deleteOnExit();
        long total = 0;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while (total < limit && (n = in.read(buf, 0, (int) Math.min(buf.length, limit - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } catch (java.io.IOException e) {
            tmp.delete();
            throw e;
        }
        if (total == 0) {
            tmp.delete();
            throw new java.io.IOException("empty media source");
        }
        return tmp;
    }
}
