// HostVideo -- the host's video player and its video-frame routing (Win.apk:
// windows/host/native_media_video.cpp, on Media Foundation).
//
// Two things live here. Player backs android.media.MediaPlayer for a source
// with a video track: the host demuxes, decodes and plays the sound, and a
// thread of this class pulls each picture when it is due and shows it where
// the app's Surface says (present). presentDecodedFrame is the other way in:
// MediaCodec.releaseOutputBuffer(render) reaches the host's
// NativeMedia.nativeVidRender, which carries no Surface, and the host calls
// back here to find the codec's registered SurfaceView sink or SurfaceTexture
// target -- the registries upstream's video plane consults on the Mac.
//
// A host without these natives (the Mac binds none of them) throws
// UnsatisfiedLinkError: Player.open then answers null and MediaPlayer keeps
// its audio-only path, and vpxBuiltIn answers true, upstream's libvpx.
package com.macapk.loader;

public final class HostVideo {
    private HostVideo() {}

    /** Whether VP8/VP9 decoders are built into the runtime (upstream's
     *  libvpx, always there) rather than the host's own, which
     *  NativeMedia.nativeVideoSupportedMimes lists when present. */
    public static boolean vpxBuiltIn() {
        try {
            return nativeVpxBuiltIn();
        } catch (UnsatisfiedLinkError e) {
            return true;
        }
    }

    /** A picture MediaCodec rendered (called from the host): to the codec's
     *  SurfaceView sink when one is registered, else the SurfaceTexture a
     *  Surface was last built from (a TextureView), else the whole window, as
     *  a bare Surface shows on the Mac. */
    static boolean presentDecodedFrame(int[] argb, int width, int height) {
        if (android.view.SurfaceView.macapkPresentVideoFrame(null, argb, width, height)) return true;
        if (android.graphics.SurfaceTexture.macapkHasActiveVideoTarget()
                && android.graphics.SurfaceTexture.macapkOfferDecodedFrame(argb, width, height, width)) {
            return true;
        }
        NativeCocoa.presentBitmap(argb, width, height);
        return true;
    }

    /** A MediaPlayer picture for {@code s}: a SurfaceView's holder Surface
     *  goes to that view's own layer at its rect, a Surface built from a
     *  SurfaceTexture into that texture (the TextureView draws it), any other
     *  Surface over the whole window. {@code recycler} gets {@code argb} back
     *  once a consumer that keeps it has let go. */
    static boolean present(android.view.Surface s, int[] argb, int width, int height,
                           java.util.function.Consumer<int[]> recycler) {
        if (s == null || !s.isValid()) {
            recycler.accept(argb);
            return false;
        }
        if (s.macapkGetOwnerSurfaceView() != null) {
            boolean shown = android.view.SurfaceView.macapkPresentVideoFrame(s, argb, width, height);
            recycler.accept(argb);  // copied synchronously
            return shown;
        }
        android.graphics.SurfaceTexture st = s.macapkGetSurfaceTexture();
        if (st != null) {
            st.macapkQueueProducerFrame(argb, width, height, width, null, recycler, false);
            return true;
        }
        NativeCocoa.presentBitmap(argb, width, height);
        recycler.accept(argb);
        return true;
    }

    /** MediaPlayer's video backend: one source with a video track. */
    public static final class Player {
        private volatile long mHandle;
        private final int mWidth, mHeight, mDurationMs;
        private volatile android.view.Surface mSurface;
        private volatile int mScalingMode = android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
        // Picture buffers in flight: one being filled, up to two kept by a
        // SurfaceTexture consumer (queued, latching); a presented SurfaceView
        // frame is copied at once and comes straight back.
        private final java.util.ArrayDeque<int[]> mFree = new java.util.ArrayDeque<int[]>();

        /** The player for a path or http(s) URL, else for {@code bytes}; null
         *  when the host has no video player, the source cannot be opened, or
         *  it has no video track (play it as audio then). */
        public static Player open(String pathOrUrl, byte[] bytes) {
            String path = pathOrUrl;
            if (path != null && !path.startsWith("http://") && !path.startsWith("https://")) {
                // The path as java.io would open it (the audio path's
                // FileInputStream): a file: Uri's "/C:/x" is "C:\x", and the
                // rest resolves as Java resolves it on this host.
                if (path.matches("^/[A-Za-z]:[/\\].*")) path = path.substring(1);
                java.io.File f = new java.io.File(path);
                if (!f.isFile()) return null;
                path = f.getAbsolutePath();
            }
            long h;
            try {
                h = nativeOpen(path, bytes);
            } catch (UnsatisfiedLinkError e) {
                return null;
            }
            return h != 0 ? new Player(h) : null;
        }

        private Player(long handle) {
            mHandle = handle;
            mWidth = nativeWidth(handle);
            mHeight = nativeHeight(handle);
            mDurationMs = nativeDurationMs(handle);
            Thread t = new Thread(new Runnable() {
                @Override public void run() { pump(); }
            }, "mediaplayer-video");
            t.setDaemon(true);
            t.start();
        }

        public int getVideoWidth() { return mWidth; }
        public int getVideoHeight() { return mHeight; }
        public int getDuration() { return mDurationMs; }
        public void setSurface(android.view.Surface s) {
            mSurface = s;
            // A player positioned on a picture shows it when its surface
            // arrives (a device shows the current frame as soon as the
            // surface attaches), not only at the next frame.
            int[] last;
            int w, h;
            synchronized (mFree) { last = mLast; w = mLastW; h = mLastH; mLast = null; }
            if (s != null && last != null) present(s, last, w, h, mRecycler);
        }
        /** The newest picture that found no surface to go to. */
        private int[] mLast;
        private int mLastW, mLastH;
        public void setVideoScalingMode(int mode) { mScalingMode = mode; }
        public void start() { long h = mHandle; if (h != 0) nativeStart(h); }
        public void pause() { long h = mHandle; if (h != 0) nativePause(h); }
        public void stop() { long h = mHandle; if (h != 0) nativeStop(h); }
        public void seekTo(int ms) { long h = mHandle; if (h != 0) nativeSeek(h, ms); }
        public void setLooping(boolean loop) { long h = mHandle; if (h != 0) nativeSetLooping(h, loop); }
        public void setVolume(float l, float r) { long h = mHandle; if (h != 0) nativeSetVolume(h, l, r); }
        public int getCurrentPosition() { long h = mHandle; return h != 0 ? nativePositionMs(h) : 0; }
        public boolean isPlaying() { long h = mHandle; return h != 0 && nativeIsPlaying(h); }
        public void release() {
            long h = mHandle;
            mHandle = 0;
            if (h != 0) nativeRelease(h);  // the pump's wait returns and it ends
        }

        private int[] take(int pixels) {
            synchronized (mFree) {
                int[] a = mFree.poll();
                return (a != null && a.length >= pixels) ? a : new int[pixels];
            }
        }
        private final java.util.function.Consumer<int[]> mRecycler = new java.util.function.Consumer<int[]>() {
            @Override public void accept(int[] a) {
                synchronized (mFree) { if (mFree.size() < 2) mFree.add(a); }
            }
        };

        private void pump() {
            int[] dims = new int[2];
            int pixels = Math.max(1, mWidth * mHeight);
            for (;;) {
                long h = mHandle;
                if (h == 0) return;
                int[] buf = take(pixels);
                int r = nativeAwaitFrame(h, buf, dims, 500);
                if (r < 0) return;
                if (r == 2) {  // a bigger picture than before (a stream change)
                    mRecycler.accept(buf);
                    pixels = Math.max(1, dims[0] * dims[1]);
                    continue;
                }
                if (r != 1) {
                    mRecycler.accept(buf);
                    continue;
                }
                int w = dims[0], hgt = dims[1];
                android.view.Surface s = mSurface;
                if (s == null) {
                    synchronized (mFree) {
                        if (mLast != null) mRecycler.accept(mLast);
                        mLast = buf; mLastW = w; mLastH = hgt;
                    }
                    continue;
                }
                if (mScalingMode == android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) {
                    int[] cropped = cropToSurface(s, buf, w, hgt, dims);
                    if (cropped != null) {
                        mRecycler.accept(buf);
                        buf = cropped;
                        w = dims[0];
                        hgt = dims[1];
                    }
                }
                present(s, buf, w, hgt, mRecycler);
            }
        }

        /** VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING: the centred part of
         *  the picture with the surface's aspect ratio, so the fit-to-surface
         *  present below crops instead of stretching. Null when the surface's
         *  size is unknown or the aspect already matches. */
        private static int[] cropToSurface(android.view.Surface s, int[] px, int w, int h, int[] dims) {
            if (s == null) return null;
            int sw = 0, sh = 0;
            android.view.SurfaceView sv = s.macapkGetOwnerSurfaceView();
            android.graphics.SurfaceTexture st = s.macapkGetSurfaceTexture();
            if (sv != null) { sw = sv.getWidth(); sh = sv.getHeight(); }
            else if (st != null) { sw = st.macapkGetDefaultWidth(); sh = st.macapkGetDefaultHeight(); }
            if (sw <= 0 || sh <= 0) return null;
            int cw = w, ch = h;
            if ((long) w * sh > (long) h * sw) cw = (int) ((long) h * sw / sh);  // wider than the surface
            else ch = (int) ((long) w * sh / sw);
            if (cw <= 0 || ch <= 0 || (cw == w && ch == h)) return null;
            int x0 = (w - cw) / 2, y0 = (h - ch) / 2;
            int[] out = new int[cw * ch];
            for (int row = 0; row < ch; row++) System.arraycopy(px, (y0 + row) * w + x0, out, row * cw, cw);
            dims[0] = cw;
            dims[1] = ch;
            return out;
        }
    }

    private static native boolean nativeVpxBuiltIn();
    private static native long nativeOpen(String pathOrUrl, byte[] bytes);
    private static native int nativeWidth(long handle);
    private static native int nativeHeight(long handle);
    private static native int nativeDurationMs(long handle);
    private static native void nativeStart(long handle);
    private static native void nativePause(long handle);
    private static native void nativeStop(long handle);
    private static native void nativeSeek(long handle, int ms);
    private static native void nativeSetLooping(long handle, boolean loop);
    private static native void nativeSetVolume(long handle, float left, float right);
    private static native int nativePositionMs(long handle);
    private static native boolean nativeIsPlaying(long handle);
    /** 1: a picture copied into {@code out}, its size in {@code dims}; 2: out
     *  is too small (dims say what it needs; the picture waits); 0: none within
     *  timeoutMs; -1: released. */
    private static native int nativeAwaitFrame(long handle, int[] out, int[] dims, int timeoutMs);
    private static native void nativeRelease(long handle);
}
