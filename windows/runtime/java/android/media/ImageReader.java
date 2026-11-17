// android.media.ImageReader -- a consumer of Images produced into a Surface.
// AOSP frameworks/base media/java/android/media/ImageReader.java.
//
// This is the CONSUMER END of Mac.apk's camera pipeline, and the only output
// type that actually receives camera frames. The producer end is the
// AVFoundation capture session behind com.macapk.loader.CameraBridge;
// android.hardware.camera2.CameraCaptureSessionImpl joins the two by calling
// macapkAttachCameraSource() on every ImageReader whose Surface is one of the
// session's outputs.
//
// ---------------------------------------------------------------------------
// HOW A FRAME GETS FROM THE CAMERA TO THE APP
// ---------------------------------------------------------------------------
//   1. AVFoundation delivers a CVPixelBuffer on its own dispatch queue and the
//      native layer parks it in a one-deep "latest wins" slot.
//   2. The capture session's pump notices the delivered-frame counter advance
//      and calls macapkOnCameraFrame() here.
//   3. We post OnImageAvailableListener.onImageAvailable(this) to the app's
//      Handler, exactly as AOSP does.
//   4. The app calls acquireLatestImage(). That locks the native buffer and
//      hands back an Image whose three Planes are DIRECT ByteBuffers over it --
//      no copy at any point in the chain.
//   5. Image.close() unlocks and releases it. The plane buffers are dead from
//      that instant, which is the same contract as real Android.
//
// ---------------------------------------------------------------------------
// ONE IMAGE AT A TIME, AND WHY THAT DIFFERS FROM maxImages
// ---------------------------------------------------------------------------
// The native slot holds exactly one frame, so exactly one Image can be
// outstanding regardless of the maxImages the reader was created with. That is
// stricter than AOSP, where maxImages Images may be held at once. The
// consequences are contained and visible:
//   * While an Image is outstanding, a new frame does NOT raise
//     onImageAvailable -- there is nothing the app could acquire, and firing a
//     callback whose acquire is guaranteed to return null would be a lie.
//   * An app that deliberately holds one Image and acquires a second gets null
//     (which AOSP defines as "no image available") plus one loud log line
//     naming the real reason, so the limitation is diagnosable rather than
//     mysterious.
// The normal camera2 idiom -- acquire and close inside onImageAvailable -- is
// unaffected.
//
// ---------------------------------------------------------------------------
// A JPEG READER IS A QUEUE OF ENCODED STILLS
// ---------------------------------------------------------------------------
// An ImageFormat.JPEG reader does not look into the capture buffer at all. The
// capture session encodes a still for each request that targets the reader's
// Surface and hands the bytes to macapkQueueJpeg(); each waits here, in order,
// until the app acquires it. Such an Image owns its bytes, so maxImages is
// real for this format: up to maxImages may be outstanding at once, one more
// acquire throws as AOSP's does, and an Image outlives the session it came
// from.
//
// ---------------------------------------------------------------------------
// WITH NO CAMERA ATTACHED
// ---------------------------------------------------------------------------
// An ImageReader that no capture session ever attached to (a codec output, say)
// behaves exactly as before: acquireLatestImage/acquireNextImage return null,
// which is what AOSP returns when no image is available, and the listener never
// fires because nothing is producing. Nothing here fabricates a frame.
package android.media;

import android.view.Surface;

import com.macapk.loader.CameraBridge;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;

public class ImageReader implements AutoCloseable {

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxImages;

    private OnImageAvailableListener mListener;
    private android.os.Handler mListenerHandler;

    private android.view.Surface mSurface;

    // ---- Camera attachment (guarded by mCameraLock) ----------------------
    private final Object mCameraLock = new Object();
    private long mCameraHandle = CameraBridge.NO_SESSION;
    private int mCameraWidth;
    private int mCameraHeight;
    private CameraImage mOutstanding;
    private boolean mWarnedOutstanding;
    private long mDroppedWhileBusy;

    // ---- JPEG stills (guarded by mCameraLock) ----------------------------
    private final java.util.ArrayDeque<JpegImage> mJpegQueue = new java.util.ArrayDeque<>();
    private int mJpegOutstanding;
    private boolean mClosed;

    protected ImageReader(int width, int height, int format, int maxImages) {
        // AOSP's constructor rejects these outright. Letting a 0x0 reader
        // through would surface much later as a capture session that
        // configures 0x0 and fails somewhere unrecognisable.
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException(
                    "The image dimensions must be positive");
        }
        if (maxImages < 1) {
            throw new IllegalArgumentException(
                    "Maximum outstanding image count must be at least 1");
        }
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mMaxImages = maxImages;
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader(width, height, format, maxImages);
    }
    public static ImageReader newInstance(int width, int height, int format, int maxImages, long usage) {
        return new ImageReader(width, height, format, maxImages);
    }

    /** The size the reader was CREATED with, per the AOSP contract -- not
     *  necessarily the size of the frames arriving from the camera. When the
     *  capture backend negotiates a different resolution it says so loudly at
     *  configure time, and Image.getWidth()/getHeight() report the real buffer
     *  geometry. */
    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public int getImageFormat() { return mFormat; }
    public int getMaxImages() { return mMaxImages; }
    public long getUsage() { return 0; }
    public int getHardwareBufferFormat() { return mFormat; }
    public int getDataSpace() { return 0; }

    // ---- The Surface, and the registry that lets a camera session find us --
    //
    // camera2 hands a capture session a list of Surfaces, and the session has to
    // get from a Surface back to the ImageReader consuming it. On real Android
    // that indirection is a BufferQueue: the Surface IS the producer endpoint of
    // the reader's queue. We have no BufferQueue, so the association is recorded
    // here instead.
    //
    // Weak on BOTH sides. The value is a WeakReference because the reader
    // strongly holds its own Surface (the map key), so a strong value would keep
    // the key alive through the map and the entry would never be collected --
    // the classic WeakHashMap self-reference leak. Surface does not override
    // equals/hashCode, so the map is keyed by identity, which is what we want.

    private static final WeakHashMap<Surface, WeakReference<ImageReader>> sSurfaceOwners =
            new WeakHashMap<>();

    // Published descriptor is ()Landroid/view/Surface; -- an Object return
    // compiled but would not resolve a guest's `Surface s = reader.getSurface()`
    // assignment (descriptor mismatch).
    public android.view.Surface getSurface() {
        synchronized (sSurfaceOwners) {
            if (mSurface == null) {
                mSurface = new android.view.Surface();
                sSurfaceOwners.put(mSurface, new WeakReference<>(this));
            }
            return mSurface;
        }
    }

    /**
     * The ImageReader consuming this Surface, or null if it is not an
     * ImageReader's Surface.
     *
     * Not an AOSP method. Public because the caller lives in
     * android.hardware.camera2, and macapk-prefixed so it can never be mistaken
     * for API surface an app should use -- the same convention as
     * SensorEvent.macapkNew and CursorWindow.macapkSetNumRows in this tree.
     */
    public static ImageReader macapkReaderForSurface(Surface surface) {
        if (surface == null) return null;
        synchronized (sSurfaceOwners) {
            WeakReference<ImageReader> ref = sSurfaceOwners.get(surface);
            return ref != null ? ref.get() : null;
        }
    }

    // ---- Camera attachment -----------------------------------------------

    /**
     * Bind this reader to a native capture session. Called by
     * CameraCaptureSessionImpl once the session is configured and running.
     *
     * @param sessionHandle the CameraBridge session handle
     * @param width         the NEGOTIATED frame width, which is what the buffers
     *                      really are -- not what this reader was created with
     * @param height        the negotiated frame height
     */
    public void macapkAttachCameraSource(long sessionHandle, int width, int height) {
        synchronized (mCameraLock) {
            mCameraHandle = sessionHandle;
            mCameraWidth = width;
            mCameraHeight = height;
            mDroppedWhileBusy = 0;
        }
    }

    /** Unbind. Any Image still outstanding is closed first, because its plane
     *  buffers point into a native frame the session is about to stop. */
    public void macapkDetachCameraSource() {
        CameraImage outstanding;
        synchronized (mCameraLock) {
            outstanding = mOutstanding;
            mOutstanding = null;
            mCameraHandle = CameraBridge.NO_SESSION;
        }
        if (outstanding != null) outstanding.close();
    }

    /**
     * A frame is available in the native slot. Called by the capture session's
     * pump, never by app code.
     *
     * Suppressed while an Image is outstanding: the native slot holds one frame,
     * so an acquire would be guaranteed to return null and the callback would be
     * a promise we cannot keep. The suppressed frames are counted and reported
     * once, so an app that leaks Images can be diagnosed from the log rather
     * than from a preview that mysteriously freezes after one frame.
     */
    public void macapkOnCameraFrame() {
        synchronized (mCameraLock) {
            if (mOutstanding != null) {
                mDroppedWhileBusy++;
                if (mDroppedWhileBusy == 60) {
                    System.err.println("[camera2] ImageReader: 60 frames dropped because an"
                            + " Image is still outstanding. Call Image.close() when done --"
                            + " this backend can hold only one frame at a time, so an"
                            + " unclosed Image stops the preview.");
                }
                return;
            }
        }
        postImageAvailable();
    }

    /**
     * An encoded still for this JPEG reader, from the capture session. Not an
     * AOSP method (the same convention as macapkOnCameraFrame).
     *
     * The reader holds at most maxImages stills, counting the ones the app has
     * acquired and not closed: that is the size of the buffer queue on a
     * device, where a full queue loses the new buffer. So a still that does not
     * fit is dropped, and the session is told so.
     *
     * @param timestampNs the capture's SENSOR_TIMESTAMP. Apps pair an Image
     *                    with its TotalCaptureResult by this value (CameraX's
     *                    MetadataImageReader does), so it must be that exact
     *                    number.
     * @return false when the still was dropped
     */
    public boolean macapkQueueJpeg(byte[] jpeg, long timestampNs) {
        if (jpeg == null || mFormat != android.graphics.ImageFormat.JPEG) return false;
        synchronized (mCameraLock) {
            if (mClosed) return false;
            if (mJpegQueue.size() + mJpegOutstanding >= mMaxImages) {
                System.err.println("[camera2] ImageReader(JPEG): still dropped, " + mMaxImages
                        + " image(s) (maxImages) are already queued or not closed");
                return false;
            }
            // Direct, as an Image's plane buffer is on Android (apps hand it to
            // native code); the reader's size, as AOSP reports for a JPEG Image.
            ByteBuffer buf = ByteBuffer.allocateDirect(jpeg.length);
            buf.put(jpeg).flip();
            mJpegQueue.add(new JpegImage(this, mWidth, mHeight, timestampNs, buf));
        }
        postImageAvailable();
        return true;
    }

    /** onImageAvailable on the listener's Handler, as AOSP posts it. */
    private void postImageAvailable() {
        OnImageAvailableListener listener;
        android.os.Handler handler;
        synchronized (mCameraLock) {
            listener = mListener;
            handler = mListenerHandler;
        }
        if (listener == null) return;

        final OnImageAvailableListener l = listener;
        Runnable deliver = () -> {
            try {
                l.onImageAvailable(this);
            } catch (Throwable t) {
                // Same reasoning as CameraDispatch: an exception from app code
                // must not kill the pump and take every later frame with it.
                System.err.println("[camera2] exception from OnImageAvailableListener:");
                t.printStackTrace();
            }
        };
        if (handler != null) handler.post(deliver);
        else deliver.run();
    }

    // ---- Image acquisition -----------------------------------------------

    /**
     * AOSP: "Acquire the latest Image from the ImageReader's queue, dropping
     * older images." That is exactly the native slot's own policy
     * (alwaysDiscardsLateVideoFrames + a one-deep pending slot), so no dropping
     * loop is needed here.
     *
     * @return the newest frame, or null when none has arrived, when no camera is
     *         attached, or when an Image is still outstanding.
     */
    public Image acquireLatestImage() {
        if (mFormat == android.graphics.ImageFormat.JPEG) return acquireJpeg(true);
        long handle;
        int negotiatedWidth;
        int negotiatedHeight;
        synchronized (mCameraLock) {
            if (mOutstanding != null) {
                if (!mWarnedOutstanding) {
                    mWarnedOutstanding = true;
                    System.err.println("[camera2] acquireLatestImage() returned null because an"
                            + " Image acquired earlier has not been closed. This backend holds"
                            + " one frame at a time (maxImages=" + mMaxImages + " was requested"
                            + " but the capture buffer is single-slot); close each Image before"
                            + " acquiring the next.");
                }
                return null;
            }
            handle = mCameraHandle;
            negotiatedWidth = mCameraWidth;
            negotiatedHeight = mCameraHeight;
        }
        if (handle == CameraBridge.NO_SESSION) return null;

        if (!CameraBridge.acquire(handle)) {
            // No frame has arrived yet. AOSP returns null for the same state.
            return null;
        }

        // Everything below is valid ONLY while the frame is held: the native
        // layer zeroes its frame descriptor on release. So snapshot all of it
        // now, in one go, rather than reading it lazily from the Plane getters.
        try {
            int w = negotiatedWidth > 0 ? negotiatedWidth : CameraBridge.getWidth(handle);
            int h = negotiatedHeight > 0 ? negotiatedHeight : CameraBridge.getHeight(handle);
            long timestamp = CameraBridge.frameTimestamp(handle);

            ByteBuffer[] buffers = new ByteBuffer[CameraBridge.PLANE_COUNT];
            int[] rowStrides = new int[CameraBridge.PLANE_COUNT];
            int[] pixelStrides = new int[CameraBridge.PLANE_COUNT];
            for (int i = 0; i < CameraBridge.PLANE_COUNT; i++) {
                buffers[i] = CameraBridge.planeBuffer(handle, i);
                // NEVER computed from the width. CoreVideo pads rows for
                // alignment (a 65-wide Y plane gets a row stride of 128), so the
                // stride the native layer reports is the only correct one.
                rowStrides[i] = CameraBridge.rowStride(handle, i);
                pixelStrides[i] = CameraBridge.pixelStride(handle, i);
                if (buffers[i] == null) {
                    CameraBridge.release(handle);
                    System.err.println("[camera2] acquireLatestImage(): plane " + i
                            + " had no buffer; dropping the frame");
                    return null;
                }
            }

            CameraImage image = new CameraImage(this, handle, w, h, mFormat, timestamp,
                    buffers, rowStrides, pixelStrides);
            synchronized (mCameraLock) {
                mOutstanding = image;
            }
            return image;
        } catch (Throwable t) {
            // The frame is locked in the native layer; it must be released on
            // every path out of here or capture stalls permanently.
            CameraBridge.release(handle);
            throw t;
        }
    }

    /**
     * AOSP acquires the NEXT (oldest undelivered) image here, where
     * acquireLatestImage takes the newest. The capture backend keeps only the
     * newest frame -- there is no queue for an older one to sit in -- so the two
     * are necessarily the same call. Documented rather than aliased silently
     * because an app using acquireNextImage to walk a queue in order will see
     * it skip, and this comment is where that behaviour is explained.
     */
    public Image acquireNextImage() {
        if (mFormat == android.graphics.ImageFormat.JPEG) return acquireJpeg(false);
        return acquireLatestImage();
    }

    /**
     * The oldest queued still, or with latest the newest (the older ones are
     * dropped, as AOSP's acquireLatestImage closes them). Null when none is
     * queued. With maxImages Images acquired and not closed AOSP throws
     * IllegalStateException with this message (ImageReader.acquireNextImage's
     * ACQUIRE_MAX_IMAGES case), whether or not another image waits; an app
     * that leaks stills has to hear about it here as it does there.
     */
    private Image acquireJpeg(boolean latest) {
        synchronized (mCameraLock) {
            if (mJpegOutstanding >= mMaxImages) {
                throw new IllegalStateException("maxImages (" + mMaxImages
                        + ") has already been acquired, call #close before acquiring more.");
            }
            if (mJpegQueue.isEmpty()) return null;
            JpegImage image = latest ? mJpegQueue.pollLast() : mJpegQueue.pollFirst();
            if (latest) mJpegQueue.clear();
            mJpegOutstanding++;
            return image;
        }
    }

    /** Called by JpegImage.close(). */
    void macapkJpegClosed() {
        synchronized (mCameraLock) {
            if (mJpegOutstanding > 0) mJpegOutstanding--;
        }
    }

    /** Called by CameraImage.close(). */
    void macapkImageClosed(CameraImage image) {
        long handle;
        synchronized (mCameraLock) {
            if (mOutstanding != image) return;
            mOutstanding = null;
            handle = image.sessionHandle();
        }
        if (handle != CameraBridge.NO_SESSION) {
            CameraBridge.release(handle);
        }
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener,
            android.os.Handler handler) {
        // AOSP: a non-null listener needs somewhere to run. Falling back to the
        // calling thread's Looper matches AOSP; with no Looper at all AOSP
        // throws, and so do we, rather than silently running the app's callback
        // on the frame pump thread where its View work would fail.
        if (listener != null && handler == null) {
            android.os.Looper looper = android.os.Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                        "handler is null but the current thread is not a looper");
            }
            handler = new android.os.Handler(looper);
        }
        synchronized (mCameraLock) {
            mListener = listener;
            mListenerHandler = listener != null ? handler : null;
        }
    }

    /** AOSP frees buffers not currently in use. The capture backend owns its own
     *  single buffer slot and there is nothing of ours to free, so this is a
     *  no-op with no observable difference. */
    public void discardFreeBuffers() {}

    @Override
    public void close() {
        macapkDetachCameraSource();
        synchronized (mCameraLock) {
            mListener = null;
            mListenerHandler = null;
            mClosed = true;
            mJpegQueue.clear();
        }
        Surface s;
        synchronized (sSurfaceOwners) {
            s = mSurface;
            if (s != null) sSurfaceOwners.remove(s);
        }
    }

    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    public static final class Builder {
        private int width = 1;
        private int height = 1;
        private int format = android.graphics.ImageFormat.UNKNOWN;
        private int maxImages = 1;
        public Builder(int width, int height) { this.width = width; this.height = height; }
        public Builder setMaxImages(int maxImages) { this.maxImages = maxImages; return this; }
        public Builder setImageFormat(int imageFormat) { this.format = imageFormat; return this; }
        public Builder setUsage(long usage) { return this; }
        public Builder setDefaultHardwareBufferFormat(int hardwareBufferFormat) { return this; }
        public Builder setDefaultDataSpace(int dataSpace) { return this; }
        public ImageReader build() { return new ImageReader(width, height, format, maxImages); }
    }

    // -----------------------------------------------------------------------
    // The camera-backed Image
    // -----------------------------------------------------------------------
    //
    // Nested rather than a separate file so the acquire/release pairing lives in
    // one place: every path that locks a native frame and every path that
    // unlocks one is visible on one screen, which is what stops a leaked lock
    // from stalling capture.
    //
    // PLANE LAYOUT. The buffers come from the native layer already described as
    // YUV_420_888's three planes over an NV12 buffer -- verified against
    // synthetic buffers by debug/yuv_map_test.sh (39/39):
    //
    //     plane 0  Y   base(0)      rowStride = yRow   pixelStride = 1
    //     plane 1  U   base(1) + 0  rowStride = cRow   pixelStride = 2
    //     plane 2  V   base(1) + 1  rowStride = cRow   pixelStride = 2
    //
    // Planes 1 and 2 are two interleaved views of the SAME chroma plane, one
    // byte apart. That is the semiplanar case ImageFormat.YUV_420_888
    // explicitly permits, so no conversion and no copy happens anywhere.
    //
    // The strides are whatever the native layer reported and are NEVER derived
    // from the width: CoreVideo pads rows, so a 65-wide Y plane can have a row
    // stride of 128. An app that assumes rowStride == width gets a sheared
    // image; one that reads getRowStride() gets the right one.

    static final class CameraImage extends Image {

        private final ImageReader mReader;
        private final long mSessionHandle;
        private final int mImgWidth;
        private final int mImgHeight;
        private final int mImgFormat;
        private final long mTimestamp;
        private final Plane[] mPlanes;
        private boolean mIsClosed;

        CameraImage(ImageReader reader, long sessionHandle, int width, int height, int format,
                long timestamp, ByteBuffer[] buffers, int[] rowStrides, int[] pixelStrides) {
            mReader = reader;
            mSessionHandle = sessionHandle;
            mImgWidth = width;
            mImgHeight = height;
            mImgFormat = format;
            mTimestamp = timestamp;
            mPlanes = new Plane[buffers.length];
            for (int i = 0; i < buffers.length; i++) {
                mPlanes[i] = new CameraPlane(buffers[i], rowStrides[i], pixelStrides[i]);
            }
        }

        long sessionHandle() { return mSessionHandle; }

        private void checkOpen() {
            // AOSP throws IllegalStateException("Image is already closed") from
            // every getter after close. Reproducing it matters more here than on
            // real Android: our plane buffers point at memory the native layer
            // has unlocked and released, so a getter that answered after close
            // would hand the app a window onto freed memory.
            if (mIsClosed) {
                throw new IllegalStateException("Image is already closed");
            }
        }

        @Override public int getFormat() { checkOpen(); return mImgFormat; }

        /** The REAL buffer geometry, which is the negotiated capture size. It
         *  can differ from ImageReader.getWidth() when the app asked for a size
         *  the camera does not offer; the session logs loudly when that
         *  happens, and this is the value a YUV-to-Bitmap loop must use. */
        @Override public int getWidth() { checkOpen(); return mImgWidth; }
        @Override public int getHeight() { checkOpen(); return mImgHeight; }

        /** The CMSampleBuffer presentation timestamp in nanoseconds, straight
         *  from AVFoundation. Monotonic host clock, not comparable with other
         *  subsystems -- which is precisely camera2's TIMESTAMP_SOURCE_UNKNOWN
         *  contract, and why that is what this device reports. */
        @Override public long getTimestamp() { checkOpen(); return mTimestamp; }

        @Override public Plane[] getPlanes() { checkOpen(); return mPlanes; }

        @Override
        public void close() {
            if (mIsClosed) return;
            mIsClosed = true;
            // Unlocks and releases the native frame. Every plane ByteBuffer
            // handed out above is dead from this point, exactly as an
            // android.media.Image's buffers are on real Android.
            mReader.macapkImageClosed(this);
        }

        private static final class CameraPlane extends Plane {
            private final ByteBuffer mBuffer;
            private final int mRowStride;
            private final int mPixelStride;

            CameraPlane(ByteBuffer buffer, int rowStride, int pixelStride) {
                mBuffer = buffer;
                mRowStride = rowStride;
                mPixelStride = pixelStride;
            }

            @Override public ByteBuffer getBuffer() { return mBuffer; }
            @Override public int getRowStride() { return mRowStride; }
            @Override public int getPixelStride() { return mPixelStride; }
        }
    }

    // -----------------------------------------------------------------------
    // The JPEG Image
    // -----------------------------------------------------------------------
    //
    // One plane holding the whole JPEG file, with row and pixel stride 0: that
    // is how AOSP describes a compressed Image (Image.getPlanes: "the number
    // of planes is 1" for JPEG; Plane.getRowStride/getPixelStride are 0 for
    // it). The usual consumer reads buffer.remaining() bytes and writes them
    // to a file or hands them to BitmapFactory.

    static final class JpegImage extends Image {
        private final ImageReader mReader;
        private final int mImgWidth;
        private final int mImgHeight;
        private final long mTimestamp;
        private final Plane[] mPlanes;
        private boolean mIsClosed;

        JpegImage(ImageReader reader, int width, int height, long timestamp, ByteBuffer jpeg) {
            mReader = reader;
            mImgWidth = width;
            mImgHeight = height;
            mTimestamp = timestamp;
            mPlanes = new Plane[] { new JpegPlane(jpeg) };
        }

        private void checkOpen() {
            if (mIsClosed) throw new IllegalStateException("Image is already closed");
        }

        @Override public int getFormat() { checkOpen(); return android.graphics.ImageFormat.JPEG; }
        @Override public int getWidth() { checkOpen(); return mImgWidth; }
        @Override public int getHeight() { checkOpen(); return mImgHeight; }
        @Override public long getTimestamp() { checkOpen(); return mTimestamp; }
        @Override public Plane[] getPlanes() { checkOpen(); return mPlanes; }

        @Override
        public void close() {
            synchronized (this) {
                if (mIsClosed) return;
                mIsClosed = true;
            }
            mReader.macapkJpegClosed();
        }

        private static final class JpegPlane extends Plane {
            private final ByteBuffer mBuffer;
            JpegPlane(ByteBuffer buffer) { mBuffer = buffer; }
            @Override public ByteBuffer getBuffer() { return mBuffer; }
            @Override public int getRowStride() { return 0; }
            @Override public int getPixelStride() { return 0; }
        }
    }
}
