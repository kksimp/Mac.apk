// android.graphics.SurfaceTexture -- the consumer half of an image stream.
//
// A producer (camera, MediaCodec/MediaPlayer, an EGL window surface, or
// Canvas via Surface.lockCanvas) queues frames; the consumer latches the
// most recent one onto a GL texture with updateTexImage(). This is the
// mechanism behind TextureView-backed video and camera preview, and it is
// referenced by a large share of the app corpus.
//
// This file was a v1.0.92 auto-generated empty stub (only a no-arg
// constructor), so every real call site -- `new SurfaceTexture(texId)`,
// updateTexImage, getTimestamp, setOnFrameAvailableListener,
// setDefaultBufferSize, release -- hit NoSuchMethodError at first touch.
//
// What is implemented here is the complete AOSP-observable Java surface and
// state machine, mirrored from
// frameworks/base/graphics/java/android/graphics/SurfaceTexture.java plus
// the error mapping in core/jni/android_graphics_SurfaceTexture.cpp and the
// consumer semantics in frameworks/native/libs/gui/GLConsumer.cpp:
//
//   - attached / detached (attachToGLContext, detachFromGLContext) and the
//     rule that updateTexImage throws IllegalStateException while detached,
//   - abandoned (release / isReleased), which every later call fails against,
//   - single-buffer mode and releaseTexImage,
//   - the frame-available callback, delivered on the caller's Handler thread
//     or, absent one, the creating thread's Looper then the main Looper,
//   - latch-the-most-recent-frame: getTimestamp / getTransformMatrix /
//     getDataSpace describe the frame the LAST updateTexImage latched.
//
// What is deliberately NOT faked: with no frame queued, updateTexImage is a
// no-op, getTimestamp stays 0 and the transform stays identity -- which is
// exactly what GLConsumer does when the buffer queue is empty
// (acquireBufferLocked returns NO_BUFFER_AVAILABLE, GLConsumer rebinds the
// texture and returns NO_ERROR) and what its initial state is. Nothing here
// invents a timestamp or a frame that did not arrive.
//
// THE PRODUCER BRIDGE (landed with the camera SurfaceTexture path): the
// camera2 capture session attaches a native frame source with
// macapkAttachCameraSource and publishes each frame's metadata through
// macapkQueueFrame -- the Java-side equivalent of BufferQueue's
// onFrameAvailable -> acquireBuffer handoff. Latching then has two consumer
// shapes, mirroring the two consumers real Android has:
//
//   - updateTexImage() (an app GL thread, e.g. WebRTC's SurfaceTextureHelper):
//     pulls the newest frame as RGBA (converted in native code, where the
//     CVPixelBuffer strides are authoritative) and uploads it into the
//     attached texture name via glTexImage2D on the CALLER's current context.
//     The upload targets GL_TEXTURE_2D because ANGLE's Metal backend does not
//     expose GL_OES_EGL_image_external (DisplayMtl.mm sets
//     EGLImageExternalOES = false), so a consumer's samplerExternalOES shader
//     still needs the GLES-bridge translation to sampler2D before it can
//     SAMPLE the texture; the image data is already where that translation
//     expects it.
//
//   - macapkLatchBitmap() (the framework's own software consumer, used by
//     TextureView.draw): latches the newest frame into a reusable Bitmap for
//     the Canvas pipeline, standing in for the HWUI render thread that
//     consumes a TextureView's SurfaceTexture on real Android. It does not
//     require GL attachment, exactly as HWUI does not use an app texture name.
//
// Frames are pulled at latch time, not pushed per delivery, so a consumer
// that stops latching costs nothing -- the same laziness as GLConsumer, whose
// acquireBuffer happens inside updateTexImage.
//
// A SECOND PRODUCER SHAPE (TextureView/SurfaceTexture-backed video output):
// a MediaCodec video decoder targeting a `new Surface(this)` (e.g.
// ExoPlayer's PlayerView in surface_type="texture_view" mode) is a PUSH
// producer, not a pull one -- see macapkQueueDecodedFrame
// and macapkOfferDecodedFrame below. The pixels are already fully decoded
// and in hand at the moment they are offered (cocoa_bridge.mm's
// CocoaPresentVideoFrame is handed a CVPixelBufferRef synchronously by
// VideoToolbox), so there is no native session handle to hold and pull
// from later the way the camera producer above needs one. Both existing
// consumer shapes (updateTexImage's GL upload, macapkLatchBitmap's software
// latch) accept frames from EITHER producer transparently.
package android.graphics;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

public class SurfaceTexture {
    /**
     * Callback interface for being notified that a new stream frame is available.
     */
    public interface OnFrameAvailableListener {
        void onFrameAvailable(SurfaceTexture surfaceTexture);
    }

    /**
     * Exception thrown when a SurfaceTexture couldn't be created or resized.
     *
     * @deprecated No longer thrown. {@link android.view.Surface.OutOfResourcesException}
     * is used instead.
     */
    @SuppressWarnings("serial")
    @Deprecated
    public static class OutOfResourcesException extends Exception {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /** AOSP: captured at construction, used to pick the callback thread when
     *  setOnFrameAvailableListener is given no Handler. */
    private final Looper mCreatorLooper;
    private Handler mOnFrameAvailableHandler;
    private final boolean mIsSingleBuffered;

    /** Guards the consumer state below, which the producer thread touches via
     *  macapkQueueFrame while the GL thread runs updateTexImage. GLConsumer
     *  takes its own mutex for exactly this. */
    private final Object mLock = new Object();

    /** GL texture object name this stream is attached to, and whether it is
     *  attached at all (the detached constructor and detachFromGLContext both
     *  clear it). */
    private int mTexName;
    private boolean mAttached;
    private boolean mAbandoned;

    /** Default buffer size a producer allocates against until it overrides it.
     *  0 means "producer decides", which is the pre-setDefaultBufferSize state. */
    private int mDefaultWidth;
    private int mDefaultHeight;

    /** State of the frame latched by the last updateTexImage. GLConsumer
     *  starts mCurrentTransformMatrix at the identity, mCurrentTimestamp at 0
     *  and mCurrentDataSpace at DATASPACE_UNKNOWN (0), so those are the honest
     *  values while nothing has been latched. */
    private final float[] mCurrentTransformMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    private long mCurrentTimestamp;
    private int mCurrentDataSpace;

    /** The one queued-but-not-yet-latched frame (the producer side). */
    private boolean mFrameAvailable;
    private long mPendingTimestamp;
    private int mPendingDataSpace;
    private float[] mPendingTransformMatrix;

    // ---- Camera frame source (guarded by mLock) --------------------------
    // A CameraBridge session handle this stream pulls pixels from at latch
    // time, plus the negotiated frame geometry known at attach. 0 = no source.
    private long mCamSession;
    private int mCamWidth;
    private int mCamHeight;

    // ---- Decoded-video frame source (guarded by mLock) --------------------
    // An ALTERNATIVE producer shape to the camera session above, for a
    // MediaCodec video decoder targeting this stream's Surface (see
    // TextureView.getSurface(), macapkRegisterVideoTarget below, and
    // cocoa_bridge.mm's CocoaPresentVideoFrame). Unlike the camera session,
    // there is no native handle to pull pixels from later: the decoded frame
    // is already fully in hand at the moment it is offered (VideoToolbox
    // hands CocoaPresentVideoFrame a CVPixelBufferRef synchronously), so it
    // is stored directly rather than lazily pulled. ARGB_8888 int values
    // (0xAARRGGBB) at row-stride mVideoFrameStride pixels (>= width -- a
    // CVPixelBuffer row can be padded), the exact layout Bitmap.setPixels
    // expects, so macapkLatchBitmap hands it straight through unmodified.
    private int[]   mVideoFrameArgb;
    private int     mVideoFrameW, mVideoFrameH, mVideoFrameStride;
    private boolean mHasVideoFrame;
    // The pushed frame's owner-supplied hooks (macapkQueueProducerFrame): who
    // gets the int[] back once it is consumed or superseded, and the frame's
    // own texture transform. Both null for a decoded-video frame, which keeps
    // that path byte-for-byte what it was before the GL producer existed.
    private java.util.function.Consumer<int[]> mVideoFrameRecycler;
    private float[] mVideoFrameTransform;
    // True when the pushed frame came from a PREMULTIPLIED producer buffer (a
    // GL window surface: what the app's context wrote), so a GL consumer must
    // see it premultiplied again; false for decoded video (always opaque).
    private boolean mVideoFrameGlPremul;
    // The connected pixel producer (an EGL window surface), or null. AOSP's
    // BufferQueue admits ONE producer connection; a second
    // native_window_api_connect fails and eglCreateWindowSurface reports
    // EGL_BAD_ALLOC. Guarded by mLock.
    private Object mProducer;

    // ---- Latch scratch state (consumer-thread-only, NOT under mLock) -----
    // A SurfaceTexture has exactly one consumer (the thread that latches), so
    // these are touched only from that thread; latching holds no lock across
    // the native pixel copy, which takes milliseconds.
    private java.nio.ByteBuffer mLatchRgba;             // GL upload scratch
    private int[] mLatchArgb;                           // Bitmap scratch
    private Bitmap mLatchBitmap;                        // software-latched frame
    private final int[] mLatchDims = new int[2];
    private int mGlParamsSetForTex;                     // 0 = not yet
    private boolean mGlUploadCheckedOnce;
    private volatile int mLatchGeneration;              // bumps per fresh latch

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     *
     * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
     */
    public SurfaceTexture(int texName) {
        this(texName, false);
    }

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     * In single buffered mode the application must call {@link #releaseTexImage()}
     * before the image content producer takes ownership of the buffer.
     *
     * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
     * @param singleBufferMode whether the SurfaceTexture will be in single buffered mode.
     */
    public SurfaceTexture(int texName, boolean singleBufferMode) {
        mCreatorLooper = Looper.myLooper();
        mIsSingleBuffered = singleBufferMode;
        mTexName = texName;
        mAttached = true;
    }

    /**
     * Construct a new SurfaceTexture in DETACHED mode. A texture name must be
     * passed in using {@link #attachToGLContext} before producing image content
     * with OpenGL ES.
     *
     * @param singleBufferMode whether the SurfaceTexture will be in single buffered mode.
     */
    public SurfaceTexture(boolean singleBufferMode) {
        mCreatorLooper = Looper.myLooper();
        mIsSingleBuffered = singleBufferMode;
        mTexName = 0;
        mAttached = false;
    }

    /** Mac.apk framework-internal. AOSP has no no-arg constructor; AOSP's own
     *  TextureView creates its texture with `new SurfaceTexture(false)` (the
     *  detached form) and attaches it later, and ours currently calls this.
     *  Kept so android.view.TextureView keeps compiling; it is unreachable
     *  from app bytecode, since no APK can reference a constructor that does
     *  not exist on Android. */
    public SurfaceTexture() {
        this(false);
    }

    /**
     * Register a callback to be invoked when a new image frame becomes available
     * to the SurfaceTexture.
     *
     * @param listener The listener to use, or null to remove the listener.
     */
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
        setOnFrameAvailableListener(listener, null);
    }

    /**
     * Register a callback to be invoked when a new image frame becomes available
     * to the SurfaceTexture. If a handler is specified, the callback is invoked
     * on that handler's thread.
     *
     * @param listener The listener to use, or null to remove the listener.
     * @param handler The handler on which the listener should be invoked, or null.
     */
    public void setOnFrameAvailableListener(final OnFrameAvailableListener listener,
            Handler handler) {
        if (listener != null) {
            // AOSP: although the thread is documented as arbitrary, the callback
            // is sent on the supplied handler's looper, else the creating
            // thread's looper, else the main looper. Apps written against that
            // ordering call updateTexImage straight from the callback because
            // they know which thread it lands on, so preserve it exactly.
            // (AOSP builds this Handler with the async flag; Mac.apk's queues
            // dispatch in posted order regardless, see Handler.createAsync.)
            Looper looper = handler != null ? handler.getLooper() :
                    mCreatorLooper != null ? mCreatorLooper : Looper.getMainLooper();
            mOnFrameAvailableHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    listener.onFrameAvailable(SurfaceTexture.this);
                }
            };
        } else {
            mOnFrameAvailableHandler = null;
        }
    }

    /**
     * Set the default size of the image buffers. The image producer may override
     * the buffer size, in which case the producer-set buffer size is used.
     */
    public void setDefaultBufferSize(int width, int height) {
        synchronized (mLock) {
            // AOSP keeps the PREVIOUS producer default in two cases, and the
            // Java method ignores the status (android_graphics_SurfaceTexture.cpp
            // SurfaceTexture_setDefaultBufferSize, lineage-21.0 mirror :327-331):
            //   - abandoned: SurfaceTexture::setDefaultBufferSize returns
            //     NO_INIT before storing anything (frameworks/native libs/
            //     nativedisplay/surfacetexture/SurfaceTexture.cpp :88-97);
            //   - a zero dimension: BufferQueueConsumer::setDefaultBufferSize
            //     returns BAD_VALUE, "dimensions cannot be 0", and leaves
            //     mCore->mDefaultWidth/Height alone (libs/gui/
            //     BufferQueueConsumer.cpp :602-618). That core value is what a
            //     producer allocates against, and it is what these fields stand
            //     for (MacApkGlWindow, CameraCaptureSessionImpl).
            // Reachable since TextureView.onSizeChanged forwards the view size
            // as AOSP does: a view collapsing to 0 on one axis must not shrink
            // a connected EGL window to 1x1 and regrow it on re-expand.
            // A NEGATIVE jint becomes a 4-billion-pixel uint32 on a device,
            // which no later allocation can satisfy; we keep the previous size
            // instead, the only reading that leaves the stream usable.
            // BISECT GATE: MACAPK_TEXTUREVIEW_BUFSIZE=0 (the switch for the
            // whole default-buffer-size change) stores every value, as before.
            if (sDefaultSizeGuard && (mAbandoned || width <= 0 || height <= 0)) return; /*BAD_VALUE*/
            mDefaultWidth = width;
            mDefaultHeight = height;
        }
    }

    /** MACAPK_TEXTUREVIEW_BUFSIZE (default ON): see setDefaultBufferSize. */
    private static final boolean sDefaultSizeGuard =
        !"0".equals(System.getenv("MACAPK_TEXTUREVIEW_BUFSIZE"));

    /**
     * Update the texture image to the most recent frame from the image stream.
     * May only be called while the OpenGL ES context that owns the texture is
     * current on the calling thread.
     */
    public void updateTexImage() {
        long cam;
        int tex;
        int[] vArgb = null;
        int vW = 0, vH = 0, vStride = 0;
        java.util.function.Consumer<int[]> vRecycle = null;
        boolean vPremul = false;
        synchronized (mLock) {
            // GLConsumer::updateTexImage, in order: abandoned -> NO_INIT;
            // acquire the next buffer -> NO_BUFFER_AVAILABLE means "rebind the
            // texture, return NO_ERROR" (NOT an error, and not a reason to
            // invent a frame); only once a buffer IS acquired does
            // updateAndReleaseLocked reject a detached consumer with
            // INVALID_OPERATION. The JNI layer maps INVALID_OPERATION to
            // IllegalStateException and any other negative status to
            // RuntimeException, with these exact messages. Following that
            // order matters: throwing on every detached call would break apps
            // that legitimately poll an idle stream while detached.
            // (The EGL-context validation in between has no Java-visible
            // equivalent here, so it is the one check we cannot mirror.)
            checkNotAbandoned();
            if (!mFrameAvailable) return;
            if (!mAttached) {
                throw new IllegalStateException(
                    "Unable to update texture contents (see logcat for details)");
            }
            mCurrentTimestamp = mPendingTimestamp;
            mCurrentDataSpace = mPendingDataSpace;
            if (mPendingTransformMatrix != null) {
                System.arraycopy(mPendingTransformMatrix, 0, mCurrentTransformMatrix, 0, 16);
                mPendingTransformMatrix = null;
            }
            mFrameAvailable = false;
            cam = mCamSession;
            tex = mTexName;
            if (cam == 0 && mHasVideoFrame) {
                vArgb = mVideoFrameArgb; vW = mVideoFrameW; vH = mVideoFrameH;
                vStride = mVideoFrameStride;
                vPremul = mVideoFrameGlPremul;
                mHasVideoFrame = false;
                vRecycle = takeFrameHooksLocked();
            }
        }
        // The GL-side half of the latch, outside mLock: the native pixel copy
        // (camera) or the ARGB->RGBA channel reorder (decoded video) takes
        // real time, and the producer must be able to keep queueing.
        if (cam != 0) {
            latchCameraFrameToGl(cam, tex);
        } else if (vArgb != null) {
            latchDecodedFrameToGl(vArgb, vW, vH, vStride, tex, vPremul);
            if (vRecycle != null) vRecycle.accept(vArgb);
        }
        // A stream with no attached frame source has no pixels to bind; the
        // metadata latch above is all there is (pre-camera behaviour).
    }

    /**
     * Pull the newest camera frame as RGBA and upload it into texture `tex`
     * on the CALLER's current GL context -- updateTexImage's pixel half.
     *
     * The target is GL_TEXTURE_2D: ANGLE's Metal backend does not expose
     * GL_OES_EGL_image_external (DisplayMtl.mm: EGLImageExternalOES = false),
     * so an external-target name cannot exist here -- the consumer's
     * glBindTexture(GL_TEXTURE_EXTERNAL_OES) was an INVALID_ENUM no-op and
     * its name is still typeless, free to become a 2D texture now. Sampling
     * through a samplerExternalOES shader still needs the GLES-bridge
     * translation to sampler2D; this upload puts the image where that
     * translation will look for it.
     *
     * Failure never throws into the app: no context / no frame / a broken
     * upload each leave the previously latched image in place, which is
     * GLConsumer's own behaviour when acquireBuffer has nothing new.
     */
    private void latchCameraFrameToGl(long cam, int tex) {
        // Size the scratch buffer from the geometry the session negotiated,
        // then trust the dims the native copy reports (a live reconfigure can
        // change them; the retry handles growth in one extra call).
        int w;
        int h;
        synchronized (mLock) { w = mCamWidth; h = mCamHeight; }
        long ts = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            int need = w > 0 && h > 0 ? w * h * 4 : 0;
            if (need <= 0) return;
            if (mLatchRgba == null || mLatchRgba.capacity() < need) {
                mLatchRgba = java.nio.ByteBuffer.allocateDirect(need);
            }
            ts = com.macapk.loader.CameraBridge.copyLatestRgba(cam, mLatchRgba, mLatchDims);
            if (ts != 0) break;
            if (mLatchDims[0] <= 0 || mLatchDims[1] <= 0) return; // no frame yet
            if (mLatchDims[0] == w && mLatchDims[1] == h) return; // real failure
            w = mLatchDims[0];
            h = mLatchDims[1];
        }
        if (ts == 0) return;
        w = mLatchDims[0];
        h = mLatchDims[1];
        synchronized (mLock) {
            // The PTS of the frame actually latched, which can be newer than
            // the queued metadata when a frame arrived between the producer's
            // notification and this latch -- latest-wins, honestly reported.
            mCurrentTimestamp = ts;
        }
        mLatchGeneration++;

        if (tex == 0) return;
        try {
            android.opengl.EGLContext ctx = android.opengl.EGL14.eglGetCurrentContext();
            if (ctx == null || ctx.getNativeHandle() == 0) {
                // No GL context current on this thread: nothing to upload
                // into. Not an error -- the software consumer path
                // (macapkLatchBitmap) does not use one either.
                return;
            }
            // Save and restore the caller's 2D binding on the active unit; a
            // consumer between draw calls must not find its state moved.
            int[] saved = new int[1];
            android.opengl.GLES20.glGetIntegerv(GL_TEXTURE_BINDING_2D, saved, 0);
            android.opengl.GLES20.glBindTexture(
                android.opengl.GLES20.GL_TEXTURE_2D, tex);
            if (mGlParamsSetForTex != tex) {
                mGlParamsSetForTex = tex;
                // The OES_EGL_image_external defaults, which are also the only
                // values that extension permits: LINEAR + CLAMP_TO_EDGE. Also
                // load-bearing here: a fresh texture's default MIN_FILTER
                // wants mipmaps we never supply, and an incomplete texture
                // samples solid black.
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
                    android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
                    android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_S,
                    android.opengl.GLES20.GL_CLAMP_TO_EDGE);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_T,
                    android.opengl.GLES20.GL_CLAMP_TO_EDGE);
            }
            mLatchRgba.position(0);
            android.opengl.GLES20.glTexImage2D(
                android.opengl.GLES20.GL_TEXTURE_2D, 0,
                android.opengl.GLES20.GL_RGBA, w, h, 0,
                android.opengl.GLES20.GL_RGBA,
                android.opengl.GLES20.GL_UNSIGNED_BYTE, mLatchRgba);
            if (!mGlUploadCheckedOnce) {
                mGlUploadCheckedOnce = true;
                // One diagnostic error read on the first upload only, so the
                // steady state never perturbs the app's own glGetError view.
                int err = android.opengl.GLES20.glGetError();
                if (err != 0) {
                    System.err.println("[SurfaceTexture] first camera-frame GL"
                        + " upload reported error 0x" + Integer.toHexString(err)
                        + " (tex=" + tex + ", " + w + "x" + h + "). The texture"
                        + " will not show camera frames.");
                }
            }
            android.opengl.GLES20.glBindTexture(
                android.opengl.GLES20.GL_TEXTURE_2D, saved[0]);
        } catch (Throwable t) {
            if (!sWarnedGlUploadFailed) {
                sWarnedGlUploadFailed = true;
                System.err.println("[SurfaceTexture] camera-frame GL upload"
                    + " unavailable (" + t + "); frames stay latched for"
                    + " software consumers only.");
            }
        }
    }

    /**
     * GL-consumer twin of macapkLatchBitmap's decoded-video branch: upload a
     * pushed decoded-video frame (macapkQueueDecodedFrame) into the attached
     * texture, for an app that manually drives updateTexImage() on a
     * MediaCodec-targeted SurfaceTexture (a custom GL video-effects pipeline,
     * say) rather than going through TextureView's own software draw path.
     *
     * Differs from latchCameraFrameToGl only in where the pixels start:
     * already-decoded ARGB_8888 ints in hand here, vs a native session to
     * pull RGBA bytes from there. glTexImage2D(GL_RGBA) wants bytes in R,G,B,A
     * MEMORY order regardless of host endianness (a GL byte format, not a
     * packed-int one); an ARGB_8888 int VALUE is 0xAARRGGBB, so each pixel's
     * R and B fields swap position while A and G stay put -- one bitwise op
     * per pixel, not a channel-by-channel unpack.
     *
     * {@code premul}: the frame came from a GL producer (an EGL window surface),
     * whose buffer on a device holds exactly what the app's context wrote,
     * i.e. PREMULTIPLIED colour; the queue carries it un-premultiplied for the
     * Canvas consumer (Bitmap.setPixels), so a translucent pixel is
     * premultiplied back here, with Skia's SkMulDiv255Round, before the
     * upload. The round trip through MacApkGlWindow's un-premultiply is
     * exact for every valid premultiplied byte (checked exhaustively over
     * 1 <= a <= 254, 0 <= c <= a); an opaque pixel, and every decoded-video
     * pixel, is untouched. Only a c > a byte (additive blending past alpha)
     * comes back clamped to a.
     */
    private void latchDecodedFrameToGl(int[] argb, int w, int h, int strideInPixels, int tex,
                                       boolean premul) {
        if (tex == 0 || w <= 0 || h <= 0) return;
        int need = w * h * 4;
        if (mLatchRgba == null || mLatchRgba.capacity() < need) {
            mLatchRgba = java.nio.ByteBuffer.allocateDirect(need);
        }
        mLatchRgba.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        mLatchRgba.clear();
        for (int y = 0; y < h; y++) {
            int row = y * strideInPixels;
            for (int x = 0; x < w; x++) {
                int p = argb[row + x];
                if (premul && (p >>> 24) != 0xff) p = premultiplyArgb(p);
                // p = 0xAARRGGBB. Target memory bytes (LE putInt writes
                // low-byte-first): R, G, B, A -- so the int VALUE written
                // must be 0xAABBGGRR (A stays at bits24-31, G stays at
                // bits8-15, R and B swap between bits0-7 and bits16-23).
                int swapped = (p & 0xFF00FF00)
                            | ((p & 0x00FF0000) >>> 16)
                            | ((p & 0x000000FF) << 16);
                mLatchRgba.putInt(swapped);
            }
        }
        mLatchRgba.position(0);
        mLatchGeneration++;
        try {
            android.opengl.EGLContext ctx = android.opengl.EGL14.eglGetCurrentContext();
            if (ctx == null || ctx.getNativeHandle() == 0) return;
            int[] saved = new int[1];
            android.opengl.GLES20.glGetIntegerv(GL_TEXTURE_BINDING_2D, saved, 0);
            android.opengl.GLES20.glBindTexture(
                android.opengl.GLES20.GL_TEXTURE_2D, tex);
            if (mGlParamsSetForTex != tex) {
                mGlParamsSetForTex = tex;
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
                    android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
                    android.opengl.GLES20.GL_LINEAR);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_S,
                    android.opengl.GLES20.GL_CLAMP_TO_EDGE);
                android.opengl.GLES20.glTexParameteri(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_T,
                    android.opengl.GLES20.GL_CLAMP_TO_EDGE);
            }
            android.opengl.GLES20.glTexImage2D(
                android.opengl.GLES20.GL_TEXTURE_2D, 0,
                android.opengl.GLES20.GL_RGBA, w, h, 0,
                android.opengl.GLES20.GL_RGBA,
                android.opengl.GLES20.GL_UNSIGNED_BYTE, mLatchRgba);
            android.opengl.GLES20.glBindTexture(
                android.opengl.GLES20.GL_TEXTURE_2D, saved[0]);
        } catch (Throwable t) {
            if (!sWarnedGlUploadFailed) {
                sWarnedGlUploadFailed = true;
                System.err.println("[SurfaceTexture] first decoded-video GL"
                    + " upload reported error (tex=" + tex + ", " + w + "x" + h
                    + "): " + t + ". The texture will not show video frames.");
            }
        }
    }

    /** android.opengl.GLES20 does not define GL_TEXTURE_BINDING_2D; the value
     *  is the GLES 2.0 core enum (gl2.h). */
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static volatile boolean sWarnedGlUploadFailed;

    /**
     * Releases the texture content. Needed in single buffered mode to let the
     * image content producer take ownership of the image buffer.
     */
    public void releaseTexImage() {
        synchronized (mLock) {
            // GLConsumer::releaseTexImage rejects only an abandoned consumer;
            // a DETACHED one is explicitly fine ("if we're detached, no need
            // to validate EGL's state"). It drops the current texture image
            // and resets the current timestamp and dataspace, leaving the
            // transform matrix and the stream state (default size, listener,
            // attachment) untouched.
            checkNotAbandoned();
            mCurrentTimestamp = 0;
            mCurrentDataSpace = 0;
        }
    }

    /**
     * Detach the SurfaceTexture from the OpenGL ES context that owns its texture
     * object. After this, {@link #updateTexImage} throws IllegalStateException
     * until a successful {@link #attachToGLContext}.
     */
    public void detachFromGLContext() {
        boolean err;
        synchronized (mLock) {
            // GLConsumer::detachFromContext returns NO_INIT when abandoned and
            // INVALID_OPERATION when not currently attached; the Java layer
            // collapses every nonzero status into this one RuntimeException.
            err = mAbandoned || !mAttached;
            if (!err) {
                mAttached = false;
                mTexName = 0;
            }
        }
        if (err) {
            throw new RuntimeException("Error during detachFromGLContext (see logcat for details)");
        }
    }

    /**
     * Attach the SurfaceTexture to the OpenGL ES context that is current on the
     * calling thread, creating a new texture object named texName.
     *
     * @param texName The name of the OpenGL ES texture that will be created.
     */
    public void attachToGLContext(int texName) {
        boolean err;
        synchronized (mLock) {
            err = mAbandoned || mAttached;
            if (!err) {
                mAttached = true;
                mTexName = texName;
            }
        }
        if (err) {
            throw new RuntimeException("Error during attachToGLContext (see logcat for details)");
        }
    }

    /**
     * Retrieve the 4x4 texture coordinate transform matrix associated with the
     * texture image set by the most recent call to {@link #updateTexImage}.
     * The matrix is column-major, for direct use with glUniformMatrix4fv.
     *
     * @param mtx the array into which the 4x4 matrix will be stored. Must have
     *     exactly 16 elements.
     */
    public void getTransformMatrix(float[] mtx) {
        // AOSP intentionally does not null-check (NPE on null is the contract)
        // and throws a message-less IllegalArgumentException on a bad length.
        if (mtx.length != 16) {
            throw new IllegalArgumentException();
        }
        synchronized (mLock) {
            System.arraycopy(mCurrentTransformMatrix, 0, mtx, 0, 16);
        }
    }

    /**
     * Retrieve the timestamp, in nanoseconds, of the texture image set by the
     * most recent call to {@link #updateTexImage}.
     */
    public long getTimestamp() {
        synchronized (mLock) {
            return mCurrentTimestamp;
        }
    }

    /**
     * Retrieve the dataspace associated with the texture image set by the most
     * recent call to {@link #updateTexImage}. DataSpace.DATASPACE_UNKNOWN (0)
     * until a producer supplies one.
     */
    public int getDataSpace() {
        synchronized (mLock) {
            return mCurrentDataSpace;
        }
    }

    /**
     * Frees all the buffers and puts the SurfaceTexture into the 'abandoned'
     * state, which it can never leave.
     *
     * @see #isReleased()
     */
    public void release() {
        synchronized (mLock) {
            // SurfaceTexture::abandon is idempotent, and it does not clear the
            // attachment bookkeeping; every later call fails on mAbandoned
            // first, so the order of the checks above is what matters.
            mAbandoned = true;
            mFrameAvailable = false;
            mPendingTransformMatrix = null;
            mCamSession = 0;
            // Same hygiene as mCamSession above: functionally inert either way
            // (every reader checks mAbandoned first and returns before ever
            // reaching mHasVideoFrame), but a released instance should not
            // pin a decoded frame's backing array (up to several MB for a
            // 1080p+ frame) until the next unrelated GC happens to collect it.
            mHasVideoFrame = false;
            mVideoFrameArgb = null;
            mVideoFrameRecycler = null;
            mVideoFrameTransform = null;
            // abandon() disconnects the producer side too; a later swap from
            // the EGL window finds the stream abandoned and drops its frame.
            mProducer = null;
        }
        // Free the latch scratch: abandon() frees the consumer's buffers on
        // real Android too. A latch racing this in another thread re-checks
        // mAbandoned under mLock first, and every null it could still see is
        // handled by the reallocation guards.
        mLatchRgba = null;
        mLatchArgb = null;
        mLatchBitmap = null;
    }

    /**
     * Returns {@code true} if the SurfaceTexture was released.
     *
     * @see #release()
     */
    public boolean isReleased() {
        synchronized (mLock) {
            return mAbandoned;
        }
    }

    /** @hide in AOSP; kept because the platform exposes it and it is free. */
    public boolean isSingleBuffered() {
        return mIsSingleBuffered;
    }

    // No finalize() override: AOSP's exists solely to free the native
    // SurfaceTexture, and this instance owns no native memory.

    /** The GL texture name this stream is attached to, or 0 when detached.
     *  Mac.apk framework-internal, for the producer bridge that will bind the
     *  latched buffer. */
    public int macapkGetTexName() {
        synchronized (mLock) {
            return mTexName;
        }
    }

    /** Default buffer size a producer should allocate against, or 0 when the
     *  app never called setDefaultBufferSize (producer decides).
     *  Mac.apk framework-internal. */
    public int macapkGetDefaultWidth() {
        synchronized (mLock) {
            return mDefaultWidth;
        }
    }
    public int macapkGetDefaultHeight() {
        synchronized (mLock) {
            return mDefaultHeight;
        }
    }

    /**
     * Mac.apk framework-internal PRODUCER entry point: publish one frame to
     * this stream. This is the Java-side stand-in for BufferQueue's
     * queueBuffer, whose consumer-side onFrameAvailable AOSP forwards to the
     * app through postEventFromNative -> mOnFrameAvailableHandler. Callers are
     * the pieces that produce frames (TextureView's Surface, the video decode
     * path, a camera bridge); after this returns, the next updateTexImage
     * latches the frame and getTimestamp reports timestampNanos.
     *
     * @param timestampNanos the frame's presentation timestamp in nanoseconds,
     *     as the producer measured it (never synthesized here).
     * @param transform the 4x4 column-major crop/rotation transform for this
     *     frame, or null to keep the current one.
     * @param dataSpace the frame's dataspace, or 0 for DATASPACE_UNKNOWN.
     */
    public void macapkQueueFrame(long timestampNanos, float[] transform, int dataSpace) {
        Handler h;
        synchronized (mLock) {
            // An abandoned SurfaceTexture fails every IGraphicBufferProducer
            // call with NO_INIT, so a late producer frame is dropped, silently,
            // exactly as the buffer queue would drop it.
            if (mAbandoned) return;
            mPendingTimestamp = timestampNanos;
            mPendingDataSpace = dataSpace;
            if (transform != null && transform.length == 16) {
                mPendingTransformMatrix = transform.clone();
            }
            mFrameAvailable = true;
            h = mOnFrameAvailableHandler;
        }
        // AOSP postEventFromNative: an empty message on the listener's handler,
        // dispatched on that handler's thread (never inline on the producer's).
        if (h != null) h.sendEmptyMessage(0);
    }

    /**
     * Mac.apk framework-internal: bind this stream to a native camera capture
     * session so latches can pull pixels. Called by CameraCaptureSessionImpl
     * once its session is configured and running -- the same seam shape as
     * ImageReader.macapkAttachCameraSource.
     *
     * @param sessionHandle the CameraBridge session handle
     * @param width         the NEGOTIATED frame width (what the buffers really
     *                      are, used to pre-size the latch scratch)
     * @param height        the negotiated frame height
     */
    public void macapkAttachCameraSource(long sessionHandle, int width, int height) {
        synchronized (mLock) {
            mCamSession = sessionHandle;
            mCamWidth = width;
            mCamHeight = height;
        }
    }

    /** Unbind. The last latched image deliberately survives, exactly as
     *  GLConsumer keeps its current texture image after the producer side of
     *  the buffer queue disconnects. */
    public void macapkDetachCameraSource() {
        synchronized (mLock) {
            mCamSession = 0;
        }
    }

    /** Whether a camera source is currently attached. Mac.apk framework-internal. */
    public boolean macapkHasCameraSource() {
        synchronized (mLock) {
            return mCamSession != 0;
        }
    }

    // ---- Decoded-video producer bridge (guarded, where noted, by mLock) --
    //
    // WHY THIS EXISTS. MediaCodec.releaseOutputBuffer(index, true) renders a
    // decoded frame through NativeMedia.nativeVidRender(pixbufHandle, width,
    // height) -- see that method's own doc -- which carries NO Surface or
    // SurfaceTexture identity at all, only the decoded pixels and their
    // dimensions. So cocoa_bridge.mm's CocoaPresentVideoFrame, which is
    // literally all nativeVidRender's native half does, cannot itself tell
    // "was this frame's Surface backed by a SurfaceView or by a TextureView's
    // SurfaceTexture" -- there is nothing in the call to distinguish them.
    //
    // This registry is the resolution: `new Surface(surfaceTexture)` -- the
    // one real hand-off point on real Android (frameworks/base's TextureView
    // has no getSurface() at all; an app gets a producer-facing Surface by
    // constructing one from the SurfaceTexture it already has, most often
    // inside onSurfaceTextureAvailable) -- registers the SurfaceTexture as
    // the current candidate target, in Surface.java's own constructor.
    // CocoaPresentVideoFrame then asks macapkHasActiveVideoTarget()
    // (cheap: one static-field read, so the common SurfaceView-only app pays
    // nothing) before doing any pixel work, and macapkOfferDecodedFrame() if
    // so; a consumed frame is routed into THIS instance's software latch
    // (macapkLatchBitmap, below) instead of the fullscreen hole-punch layer
    // CocoaPresentVideoFrame uses for a plain SurfaceView.
    //
    // Legacy render callers still use the last registered candidate. MediaCodec
    // scopes each synchronous native render to its configured Surface instead:
    // creating a fullscreen GL output must not steal that decoder's input.
    // Weak, so a registration can never keep a SurfaceTexture -- and the
    // View/Bitmaps it drags in -- alive past its real owner's lifecycle.
    // Volatile: written from whichever thread constructs a Surface (normally
    // the UI thread), read from cocoa_bridge.mm's caller thread (the app's
    // decode/render thread, or MediaCodec's own async dispatch thread) --
    // a plain field has no cross-thread visibility guarantee for that.
    private static volatile java.lang.ref.WeakReference<SurfaceTexture> sActiveVideoTarget;
    private static final ThreadLocal<android.view.Surface> sVideoFrameSurface = new ThreadLocal<>();

    /** Scope one synchronous native render to its codec's configured Surface.
     *  The caller restores the returned value in finally, including nested
     *  renders. A plain Surface explicitly bypasses unrelated texture targets. */
    public static android.view.Surface macapkSetVideoFrameSurface(android.view.Surface surface) {
        android.view.Surface previous = sVideoFrameSurface.get();
        if (surface == null) sVideoFrameSurface.remove();
        else sVideoFrameSurface.set(surface);
        return previous;
    }

    private static SurfaceTexture videoFrameTarget() {
        android.view.Surface surface = sVideoFrameSurface.get();
        if (surface != null) return surface.macapkGetSurfaceTexture();
        return sActiveVideoTarget != null ? sActiveVideoTarget.get() : null;
    }


    /** Mac.apk framework-internal: register this instance as the current
     *  candidate destination for a decoded-video frame. Called by
     *  Surface's SurfaceTexture-arg constructor; see the section doc above. */
    public void macapkRegisterVideoTarget() {
        sActiveVideoTarget = new java.lang.ref.WeakReference<>(this);
    }

    /** Mac.apk framework-internal: undo the registration above, but only if
     *  it is still THIS instance -- a newer TextureView may have registered
     *  since (most-recent-wins), and an old one detaching must not clobber
     *  a live registration that superseded it. */
    public void macapkUnregisterVideoTargetIfSelf() {
        if (sActiveVideoTarget != null && sActiveVideoTarget.get() == this) {
            sActiveVideoTarget = null;
        }
    }

    /** Cheap peek for cocoa_bridge.mm's CocoaPresentVideoFrame to make
     *  BEFORE paying for a pixel copy: is there a live registered target at
     *  all? Abandoned counts as "no" even if still referenced, matching
     *  every other producer entry point's checkNotAbandoned-shaped gate. */
    public static boolean macapkHasActiveVideoTarget() {
        SurfaceTexture t = videoFrameTarget();
        return t != null && !t.mAbandoned;
    }

    /** Mac.apk framework-internal native entry point (cocoa_bridge.mm's
     *  CocoaPresentVideoFrame): one decoded video frame is ready. Routes to
     *  the registered target, if any is still alive. Returns true when a
     *  target consumed the frame -- the caller then skips its OWN fullscreen
     *  SurfaceView present, since a TextureView-bound video must not ALSO
     *  paint fullscreen -- false when there is no live target, so the caller
     *  falls through to its existing SurfaceView behavior unchanged.
     *
     *  @param argb ARGB_8888 int values (0xAARRGGBB), row-stride
     *      strideInPixels, exactly as macapkLatchBitmap's Bitmap.setPixels
     *      call expects (see macapkQueueDecodedFrame).
     */
    public static boolean macapkOfferDecodedFrame(int[] argb, int width, int height,
                                                  int strideInPixels) {
        SurfaceTexture target = videoFrameTarget();
        if (target == null || target.mAbandoned) return false;
        target.macapkQueueDecodedFrame(argb, width, height, strideInPixels);
        return true;
    }

    private static final float[] DECODED_FRAME_TRANSFORM = {
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
    };

    /** Mac.apk framework-internal PRODUCER entry point, decoded-video shape
     *  (the sole caller is macapkOfferDecodedFrame above). Unlike
     *  macapkQueueFrame (the metadata-only, pull-the-pixels-later shape the
     *  camera producer uses), the pixels are already in hand here, so they
     *  are stored directly rather than triggering a later pull. Now a thin
     *  delegate of the per-instance macapkQueueProducerFrame below. The
     *  pixels have top-down rows, so GL sampling needs the same vertical
     *  transform as a buffer queued by a GL window producer. The software
     *  consumer reads the unchanged top-down pixels directly. */
    void macapkQueueDecodedFrame(int[] argb, int width, int height, int strideInPixels) {
        macapkQueueProducerFrame(argb, width, height, strideInPixels,
                DECODED_FRAME_TRANSFORM, null, false);
    }

    // ---- Per-instance pixel producer (EGL window surfaces) ----------------
    //
    // WHY (2026-09-26, docs/telegram-intro-logo_2026-09-26.md §7). On Android
    // an app's EGL window surface over a SurfaceTexture is a real producer on
    // that stream's BufferQueue (frameworks/base core/jni/
    // com_google_android_gles_jni_EGLImpl.cpp jni_eglCreateWindowSurfaceTexture:
    // `new Surface(SurfaceTexture_getProducer(...))`), and each eglSwapBuffers
    // queues one buffer that the consumer (TextureView via HWUI) latches. Our
    // javax EGL10 window surface (javax.microedition.khronos.egl.MacApkGlWindow)
    // renders into an ANGLE pbuffer and reads it back at swap, so its frames
    // arrive here as pixels in hand, the same shape as decoded video. Unlike
    // the video registry above, a GL producer knows EXACTLY which stream it
    // targets, so this entry is per instance and never touches
    // sActiveVideoTarget.

    /** Connect a pixel producer. False when the stream is abandoned, a camera
     *  session feeds it, or another producer is already connected (AOSP's
     *  single-producer BufferQueue; eglCreateWindowSurface then reports
     *  EGL_BAD_ALLOC). Mac.apk framework-internal. */
    public boolean macapkConnectProducer(Object producer) {
        if (producer == null) return false;
        synchronized (mLock) {
            if (mAbandoned || mCamSession != 0) return false;
            if (mProducer != null && mProducer != producer) return false;
            mProducer = producer;
            return true;
        }
    }

    /** Disconnect {@code producer} if it is the connected one. The last queued
     *  frame survives, as GLConsumer keeps its current image after the
     *  producer disconnects. Mac.apk framework-internal. */
    public void macapkDisconnectProducer(Object producer) {
        synchronized (mLock) {
            if (mProducer == producer) mProducer = null;
        }
    }

    /** Whether a pixel producer (EGL window surface) is connected.
     *  Mac.apk framework-internal. */
    public boolean macapkHasProducer() {
        synchronized (mLock) {
            return mProducer != null;
        }
    }

    /**
     * Mac.apk framework-internal PRODUCER entry point: queue one frame of
     * pixels that are already in hand (the BufferQueue queueBuffer analogue).
     *
     * @param argb ARGB_8888, NON-premultiplied int values (0xAARRGGBB) with
     *     row stride {@code strideInPixels}, row 0 at the TOP of the image: the
     *     layout Bitmap.setPixels takes, which is what macapkLatchBitmap does
     *     with it.
     * @param transform the frame's 4x4 column-major texture transform, reported
     *     by getTransformMatrix once updateTexImage latches this frame, or null
     *     to leave the current one.
     * @param recycler receives {@code argb} back once the frame is consumed by
     *     either latch or superseded by a newer frame, so a producer can reuse
     *     its buffers (at most three in flight: filling, queued, latching) the
     *     way a BufferQueue slot is dequeued again. null = nobody wants it.
     * @param premultipliedSource the producer's own buffer held premultiplied
     *     colour (a GL window surface), so updateTexImage re-premultiplies it
     *     for a GL consumer; false for decoded video.
     */
    public void macapkQueueProducerFrame(int[] argb, int width, int height,
                                         int strideInPixels, float[] transform,
                                         java.util.function.Consumer<int[]> recycler,
                                         boolean premultipliedSource) {
        Handler h;
        int[] superseded = null;
        java.util.function.Consumer<int[]> supersededRecycler = null;
        synchronized (mLock) {
            if (mAbandoned || argb == null || width <= 0 || height <= 0) {
                // Dropped, exactly as the buffer queue drops a late frame;
                // hand the buffer straight back.
                if (recycler != null && argb != null) recycler.accept(argb);
                return;
            }
            if (mHasVideoFrame && mVideoFrameArgb != null && mVideoFrameArgb != argb) {
                // The previous frame was never latched: latest-wins, and its
                // buffer goes back to its producer.
                superseded = mVideoFrameArgb;
                supersededRecycler = mVideoFrameRecycler;
            }
            mVideoFrameArgb = argb;
            mVideoFrameW = width;
            mVideoFrameH = height;
            mVideoFrameStride = strideInPixels > 0 ? strideInPixels : width;
            mVideoFrameRecycler = recycler;
            mVideoFrameTransform = (transform != null && transform.length == 16)
                ? transform.clone() : null;
            mVideoFrameGlPremul = premultipliedSource;
            mHasVideoFrame = true;
            mFrameAvailable = true;
            // KNOWN LIMITATION, honestly stamped rather than left stale or
            // invented as a decode PTS: MediaCodec.releaseOutputBuffer(index,
            // true) -> NativeMedia.nativeVidRender(pixbufHandle, width,
            // height) carries no presentationTimeUs at all (see that
            // method's own signature/doc), so the frame's real decode PTS
            // never crosses into this class. Wall-clock-at-arrival is a
            // monotonically increasing, honest stand-in that satisfies
            // getTimestamp()'s documented contract ("normally... measured
            // with the same clock as... elapsedRealtimeNanos", i.e.
            // monotonic, not tied to a specific stream's PTS) without
            // freezing at a stale value the way leaving mPendingTimestamp
            // untouched would. A real fix threads BufferInfo.presentationTimeUs
            // through nativeVidRender and NativeMedia.nativeVidRender -- a
            // MediaCodec.java/NativeMedia.java change outside this class's
            // owned files; see the deliverable report.
            mPendingTimestamp = System.nanoTime();
            h = mOnFrameAvailableHandler;
        }
        if (supersededRecycler != null) supersededRecycler.accept(superseded);
        // AOSP postEventFromNative shape, same as macapkQueueFrame: dispatched
        // on the listener's thread, never inline on the producer's (which for
        // decoded video is whatever thread called releaseOutputBuffer --
        // MediaCodec's own async dispatch thread, or the app's own; for a GL
        // window surface, the app's EGL thread inside eglSwapBuffers).
        if (h != null) h.sendEmptyMessage(0);
    }

    /** Consume the pushed frame's owner hooks under mLock: apply its own
     *  texture transform and hand back its recycler for
     *  the caller to run once the pixels are copied. */
    /** One ARGB_8888 int from straight to premultiplied alpha, rounded as
     *  Skia's SkMulDiv255Round (the GLUtils upload uses the same rounding). */
    private static int premultiplyArgb(int p) {
        int a = p >>> 24;
        if (a == 0) return 0;
        int r = mulDiv255Round((p >>> 16) & 0xff, a);
        int g = mulDiv255Round((p >>> 8) & 0xff, a);
        int b = mulDiv255Round(p & 0xff, a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static int mulDiv255Round(int c, int a) {
        int prod = c * a + 128;
        return (prod + (prod >> 8)) >> 8;
    }

    private java.util.function.Consumer<int[]> takeFrameHooksLocked() {
        if (mVideoFrameTransform != null) {
            System.arraycopy(mVideoFrameTransform, 0, mCurrentTransformMatrix, 0, 16);
            mVideoFrameTransform = null;
        }
        java.util.function.Consumer<int[]> r = mVideoFrameRecycler;
        mVideoFrameRecycler = null;
        return r;
    }

    /**
     * Mac.apk framework-internal SOFTWARE consumer latch -- TextureView's draw
     * path, standing in for the HWUI render thread that consumes a
     * TextureView's SurfaceTexture on real Android. Latches the newest camera
     * frame into a reusable Bitmap and returns it; with no new frame it
     * returns the previously latched Bitmap unchanged (GLConsumer's
     * NO_BUFFER_AVAILABLE behaviour), and before any frame it returns null.
     *
     * Deliberately does NOT require GL attachment: the render-thread consumer
     * never uses an app texture name, and TextureView's SurfaceTexture is
     * created in the detached state.
     *
     * Single-consumer, like updateTexImage: the scratch buffers are only ever
     * touched by the latching thread.
     */
    public Bitmap macapkLatchBitmap() {
        long cam;
        int[] vArgb = null;
        int vW = 0, vH = 0, vStride = 0;
        java.util.function.Consumer<int[]> vRecycle = null;
        synchronized (mLock) {
            cam = mCamSession;
            if (mAbandoned) return mLatchBitmap;
            if (cam == 0 && mHasVideoFrame) {
                // Decoded-video producer shape (macapkQueueDecodedFrame): the
                // pixels are already in hand, unlike the camera session's
                // pull-at-latch-time shape below -- see the class doc's "two
                // consumer shapes... mirroring the two consumers real Android
                // has": this is a third PRODUCER shape feeding the same two
                // consumers (this method, and updateTexImage's GL upload).
                // The bitmap consumes top-down pixels directly. Its GL
                // sampling transform is still latched as frame metadata,
                // but is not applied to this software copy.
                vArgb = mVideoFrameArgb; vW = mVideoFrameW; vH = mVideoFrameH;
                vStride = mVideoFrameStride;
                mCurrentTimestamp = mPendingTimestamp;
                mCurrentDataSpace = mPendingDataSpace;
                mHasVideoFrame = false;
                mFrameAvailable = false;
                vRecycle = takeFrameHooksLocked();
            } else if (cam == 0 || !mFrameAvailable) {
                return mLatchBitmap;
            } else {
                // Consume the queued frame's metadata, exactly as updateTexImage
                // does; this IS updateTexImage for the software consumer.
                mCurrentTimestamp = mPendingTimestamp;
                mCurrentDataSpace = mPendingDataSpace;
                if (mPendingTransformMatrix != null) {
                    System.arraycopy(mPendingTransformMatrix, 0, mCurrentTransformMatrix, 0, 16);
                    mPendingTransformMatrix = null;
                }
                mFrameAvailable = false;
            }
        }
        if (vArgb != null) {
            if (mLatchBitmap == null
                    || mLatchBitmap.getWidth() != vW || mLatchBitmap.getHeight() != vH) {
                mLatchBitmap = Bitmap.createBitmap(vW, vH, Bitmap.Config.ARGB_8888);
            }
            mLatchBitmap.setPixels(vArgb, 0, vStride, 0, 0, vW, vH);
            // The copy is done; the producer may refill this buffer now.
            if (vRecycle != null) vRecycle.accept(vArgb);
            mLatchGeneration++;
            return mLatchBitmap;
        }

        int w;
        int h;
        synchronized (mLock) { w = mCamWidth; h = mCamHeight; }
        long ts = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            int need = w > 0 && h > 0 ? w * h : 0;
            if (need <= 0) return mLatchBitmap;
            if (mLatchArgb == null || mLatchArgb.length < need) {
                mLatchArgb = new int[need];
            }
            ts = com.macapk.loader.CameraBridge.copyLatestArgb(cam, mLatchArgb, mLatchDims);
            if (ts != 0) break;
            if (mLatchDims[0] <= 0 || mLatchDims[1] <= 0) return mLatchBitmap;
            if (mLatchDims[0] == w && mLatchDims[1] == h) return mLatchBitmap;
            w = mLatchDims[0];
            h = mLatchDims[1];
        }
        if (ts == 0) return mLatchBitmap;
        w = mLatchDims[0];
        h = mLatchDims[1];
        if (mLatchBitmap == null
                || mLatchBitmap.getWidth() != w || mLatchBitmap.getHeight() != h) {
            mLatchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        mLatchBitmap.setPixels(mLatchArgb, 0, w, 0, 0, w, h);
        synchronized (mLock) {
            // The PTS of the frame actually copied (latest-wins can outrun the
            // queued metadata by a frame).
            mCurrentTimestamp = ts;
        }
        mLatchGeneration++;
        return mLatchBitmap;
    }

    /** Bumped once per freshly latched camera frame (either consumer shape).
     *  Lets TextureView tell "new frame latched" from "same frame redrawn"
     *  without comparing timestamps. Mac.apk framework-internal. */
    public int macapkLatchGeneration() {
        return mLatchGeneration;
    }

    /** An abandoned consumer returns NO_INIT, which is neither NO_ERROR nor
     *  INVALID_OPERATION, so the JNI layer raises a plain RuntimeException.
     *  The message is AOSP's verbatim, including the fact that releaseTexImage
     *  reuses updateTexImage's wording. */
    private void checkNotAbandoned() {
        if (mAbandoned) {
            throw new RuntimeException("Error during updateTexImage (see logcat for details)");
        }
    }
}
