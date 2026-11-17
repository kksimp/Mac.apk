package android.media;

// android.media.Image -- minimal shadow of the camera/codec output buffer
// handle. The full AOSP type is an abstract class wrapping a hardware image
// buffer; on Mac.apk there is no camera/codec producing real Image instances,
// so this exists only so that signatures which REFERENCE android.media.Image
// (most notably com.google.mlkit.vision.common.InputImage.fromMediaImage and
// CameraX's ImageProxy.getImage) link. It is abstract with no concrete
// producer, so an app never actually obtains one here; the type's only job is
// to make the boot classpath resolve the symbol exactly as a real device does.
//
// Mirrors the public surface apps touch most: getWidth/getHeight/getFormat/
// getTimestamp/getPlanes/close. AutoCloseable matches AOSP (Image implements it).
public abstract class Image implements AutoCloseable {

    protected Image() {}

    // android.media.Image.Plane -- per-channel buffer descriptor. Abstract in
    // AOSP; present so getPlanes()'s return type resolves.
    public abstract static class Plane {
        protected Plane() {}
        public abstract java.nio.ByteBuffer getBuffer();
        public abstract int getPixelStride();
        public abstract int getRowStride();
    }

    public abstract int getFormat();
    public abstract int getWidth();
    public abstract int getHeight();
    public abstract long getTimestamp();
    public abstract Plane[] getPlanes();

    @Override public abstract void close();

    /** AOSP Image.getCropRect (API 21): the valid region of the image, which
     *  defaults to the whole image and is only narrower when a producer set a
     *  crop. The camera path sets none, so this is the full frame. Concrete
     *  rather than abstract because AOSP's is concrete -- an app calling it on
     *  an uncropped image must not need the producer to have implemented it. */
    public android.graphics.Rect getCropRect() {
        // AOSP returns the crop the app set, defaulting to the whole image.
        // Reading mCropRect back matters: setCropRect() would otherwise be a
        // setter with no observable effect at all.
        if (mCropRect != null) return new android.graphics.Rect(mCropRect);
        return new android.graphics.Rect(0, 0, getWidth(), getHeight());
    }

    /** AOSP Image.getHardwareBuffer (API 28): a descriptor of the image's buffer.
     *  Mac.apk images are software-backed, so this is a format/size descriptor. */
    /**
     * API 34. The colour space / transfer / range the pixels are in, as a
     * DataSpace value. AOSP's default is DATASPACE_UNKNOWN (0) until a producer
     * sets one, and no producer here does -- the concrete Image subclasses in
     * this tree carry decoded RGBA/YUV with no attached dataspace descriptor.
     * Concrete rather than abstract, so the existing subclasses keep compiling,
     * exactly as AOSP's own is.
     */
    public int getDataSpace() { return mDataSpace; }

    /** API 34. AOSP records the value on the image so a consumer can read it
     *  back; ImageWriter carries it to the buffer queue. Stored here for the
     *  same read-back reason. */
    public void setDataSpace(int dataSpace) { mDataSpace = dataSpace; }

    private int mDataSpace = 0;  // DataSpace.DATASPACE_UNKNOWN

    /** API 34. AOSP: an app-supplied crop rectangle, used by ImageWriter to tell
     *  the consumer which sub-region is valid. Stored so getCropRect() reads
     *  back what was written; a null argument restores the full-image default,
     *  as AOSP documents. */
    public void setCropRect(android.graphics.Rect cropRect) { mCropRect = cropRect; }

    private android.graphics.Rect mCropRect;

    /** API 34. AOSP: the presentation timestamp an app attaches before queueing
     *  the image to an ImageWriter. Stored for the same reason as setCropRect;
     *  getTimestamp() stays abstract, so a subclass decides whether to expose
     *  this value or its own. */
    public void setTimestamp(long timestamp) { mQueuedTimestamp = timestamp; }

    private long mQueuedTimestamp;

    /** API 33. AOSP: the fence that signals when the image's buffer may be
     *  read; an image without one answers an empty (already signalled) fence.
     *  The images here are finished software buffers, so that is every image
     *  that was not given a fence with setFence. */
    public android.hardware.SyncFence getFence() throws java.io.IOException {
        return (mFence != null) ? mFence : android.hardware.SyncFence.createEmpty();
    }

    /** API 33. AOSP: the fence an app attaches before queueing the image to an
     *  ImageWriter. Stored for the read-back, as setCropRect is. */
    public void setFence(android.hardware.SyncFence fence) throws java.io.IOException {
        mFence = fence;
    }

    private android.hardware.SyncFence mFence;

    public android.hardware.HardwareBuffer getHardwareBuffer() {
        int w = 1, h = 1, f = android.hardware.HardwareBuffer.RGBA_8888;
        try { w = Math.max(1, getWidth()); h = Math.max(1, getHeight()); } catch (Throwable ignored) {}
        return android.hardware.HardwareBuffer.create(w, h, f, 1,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    }
}

