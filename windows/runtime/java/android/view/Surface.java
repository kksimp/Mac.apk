// android.view.Surface -- historically an empty stub (GLSurfaceView talks to
// the Cocoa MTKView directly through JNI, so most rendering never comes
// through here). lockCanvas/lockHardwareCanvas/unlockCanvasAndPost ARE real,
// though: AOSP's contract is that lockCanvas never returns null (failure is
// OutOfResourcesException/IllegalArgumentException/IllegalStateException
// instead), so a bare `new Surface()` handed out by SurfaceHolder.getSurface()
// or TextureView's (Mac.apk-internal convenience) getSurface() must work on
// its own -- apps that cache that Surface and lock/unlock it directly
// (bypassing the holder every frame) are a common idiom and must not NPE on
// the very next statement. We mirror the same bitmap-backed Canvas +
// NativeCocoa.presentBitmap path that SurfaceView's SurfaceHolderImpl
// already uses for holder.lockCanvas() (SurfaceView.java), so this Surface's
// own lockCanvas() paints and presents on its own instead of returning null.
//
// THE VIDEO-DECODE HAND-OFF POINT: `new Surface(surfaceTexture)` is the one
// and only place a producer-facing Surface for a TextureView's content
// stream comes from on real Android (frameworks/base core/java/android/view/
// TextureView.java has no getSurface() at all -- a real app instead does
// exactly this, most often from inside onSurfaceTextureAvailable, using the
// SurfaceTexture that callback hands it, or the one getSurfaceTexture()
// returns). MediaCodec.configure(format, surface, ...) receiving a Surface
// built this way is indistinguishable, on the wire, from one built from a
// SurfaceView's SurfaceHolder -- so THIS constructor is where
// SurfaceTexture.macapkRegisterVideoTarget() is called, and it is the only
// correct place: it is reached both by a real app's own construction and by
// this framework's internal TextureView.ensureSurfaceTexture(), so neither
// path can miss it. See SurfaceTexture.macapkOfferDecodedFrame's doc for the
// full routing story (cocoa_bridge.mm's CocoaPresentVideoFrame is what
// eventually asks whether a target is registered).
package android.view;

public class Surface implements android.os.Parcelable {
    public static final int ROTATION_0   = 0;
    public static final int ROTATION_90  = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;

    // setFrameRate(float, int[, int]) compatibility / strategy values.
    // AOSP core/java/android/view/Surface.java FRAME_RATE_COMPATIBILITY_* /
    // CHANGE_FRAME_RATE_* (public surface only; the *_EXACT / *_NO_VOTE /
    // *_MIN compatibility values are @hide internals, not part of the SDK).
    public static final int FRAME_RATE_COMPATIBILITY_DEFAULT      = 0;
    public static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;
    public static final int FRAME_RATE_COMPATIBILITY_GTE          = 2;
    /** AOSP renamed FRAME_RATE_COMPATIBILITY_GTE to
     *  FRAME_RATE_COMPATIBILITY_AT_LEAST for API 36; only the new name is in
     *  core/api/current.txt. Same value, both kept -- an app built against an
     *  older SDK inlined GTE's 2, and one built against 36 names AT_LEAST. */
    public static final int FRAME_RATE_COMPATIBILITY_AT_LEAST = 2;
    public static final int CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS    = 0;
    public static final int CHANGE_FRAME_RATE_ALWAYS              = 1;

    // The producer-side SurfaceTexture this Surface feeds, when constructed from
    // a TextureView's texture (AOSP Surface(SurfaceTexture)). null for the plain
    // SurfaceView-backed case.
    private android.graphics.SurfaceTexture mSurfaceTexture;
    private boolean mReleased;

    // The SurfaceView whose SurfaceHolder handed this Surface out, when there
    // is one (SurfaceHolderImpl sets it at construction). On real Android the
    // association is structural -- the Surface IS a handle onto the
    // SurfaceView's SurfaceControl buffer queue, and SurfaceFlinger scales the
    // queued buffers to the view's on-screen rect (SurfaceView.
    // onSetSurfacePositionAndScale: transaction.setPosition(surface,
    // positionLeft, positionTop) + setMatrix(surface, screenRect.width() /
    // (float) mSurfaceWidth, ...), aosp-mirror/master core/java/android/view/
    // SurfaceView.java, fetched 2026-08-17). Our compositor needs the same
    // association to place decoded video at the view's rect instead of over
    // the whole window -- see SurfaceView.macapkActiveVideoRectPacked.
    private SurfaceView mOwnerSurfaceView;

    // lockCanvas()/unlockCanvasAndPost() state. AOSP keeps one persistent
    // Canvas (mCanvas) reused across locks and a native mLockedObject handle
    // that is nonzero while locked; we have no native buffer, so
    // mCanvasLocked stands in for "mLockedObject != 0" and
    // mDrawBitmap/mDrawCanvas stand in for the persistent software backing.
    private android.graphics.Bitmap mDrawBitmap;
    private android.graphics.Canvas mDrawCanvas;
    private boolean mCanvasLocked;

    // setFrameRate is a pure scheduling HINT on real Android ("does not
    // throttle the frame rate or affect frame production"); macOS's
    // CAMetalLayer has no per-Surface variable-refresh-rate hook to honor it
    // against, so we only validate + store for diagnostics, matching how a
    // fixed-refresh-rate Android device is documented to behave.
    private float mFrameRate;

    public Surface() {}
    public Surface(android.graphics.SurfaceTexture surfaceTexture) {
        mSurfaceTexture = surfaceTexture;
        // See the file header ("THE VIDEO-DECODE HAND-OFF POINT"). Harmless
        // when this Surface is never handed to a video decoder (a camera
        // preview, or a custom GL consumer): it only means a video frame is
        // never offered, since nothing calls macapkOfferDecodedFrame without
        // one actually decoding.
        if (surfaceTexture != null) {
            surfaceTexture.macapkRegisterVideoTarget();
        }
    }
    /** AOSP Surface(SurfaceControl) (API 29): a Surface that queues buffers to
     *  the given layer. A SurfaceControl here is a handle with no layer in a
     *  compositor behind it (android.view.SurfaceControl), so the Surface is a
     *  valid one that nothing shows, like a default-constructed one. The null
     *  check is AOSP's copyFrom. */
    public Surface(SurfaceControl from) {
        if (from == null) {
            throw new IllegalArgumentException("other must not be null");
        }
    }
    public boolean isValid() { return !mReleased; }
    public void release() { mReleased = true; }

    /** The SurfaceTexture this Surface produces into, or null when it was not
     *  constructed from one. Mac.apk framework-internal (macapk prefix, same
     *  convention as ImageReader.macapkReaderForSurface): it is how the camera
     *  capture session recognises a SurfaceTexture-backed output -- on real
     *  Android that association is the BufferQueue behind the Surface. */
    public android.graphics.SurfaceTexture macapkGetSurfaceTexture() {
        return mSurfaceTexture;
    }

    /** Mac.apk framework-internal (same convention as macapkGetSurfaceTexture
     *  above): the SurfaceView this Surface belongs to, or null when it is not
     *  a SurfaceHolder-vended Surface. Set once by SurfaceHolderImpl's
     *  constructor; read by MediaCodec.configure/setOutputSurface to register
     *  the view as the active video sink. */
    public void macapkSetOwnerSurfaceView(SurfaceView owner) {
        mOwnerSurfaceView = owner;
    }
    public SurfaceView macapkGetOwnerSurfaceView() {
        return mOwnerSurfaceView;
    }

    /** Mac.apk framework-internal, called by the Bionic shim's
     *  ANativeWindow_fromSurface (bionic_shim.cpp) on the Surface a native
     *  producer is taking: on a device that is android_view_Surface_getNativeWindow
     *  reading THIS Surface's window, i.e. the owning SurfaceView's surface is now
     *  fed by native code. The owner then composites below its window and punches
     *  its hole (SurfaceView "native window owner"). */
    public void macapkNoteNativeWindowConsumer() {
        SurfaceView.macapkNoteNativeWindowOwner(mOwnerSurfaceView);
    }

    private void checkNotReleased() {
        // AOSP Surface.checkNotReleasedLocked(): IllegalStateException,
        // exact message, once the native object is gone. We track the same
        // lifecycle transition via mReleased.
        if (mReleased) {
            throw new IllegalStateException("Surface has already been released.");
        }
    }

    /** Win.apk framework-internal: takes what is drawn on this Surface in
     *  place of the window, as an encoder's input Surface must
     *  (MediaCodec.createInputSurface). ARGB ints, width * height. */
    public interface MacapkFrameSink {
        void onFrame(int[] argb, int width, int height, long timestampNs);
    }
    private volatile MacapkFrameSink mFrameSink;
    private int mSinkWidth, mSinkHeight;
    /** The sink and the size its pictures must have; null detaches it. */
    public void macapkSetFrameSink(MacapkFrameSink sink, int width, int height) {
        mSinkWidth = width;
        mSinkHeight = height;
        mFrameSink = sink;
    }
    public MacapkFrameSink macapkGetFrameSink() { return mFrameSink; }

    public android.graphics.Canvas lockCanvas(android.graphics.Rect dirty) {
        checkNotReleased();
        // AOSP throws IllegalArgumentException when the surface is already
        // locked (mLockedObject != 0) as well as for an invalid dirty rect;
        // we don't interpret the dirty rect (SurfaceHolderImpl.lockCanvas(Rect)
        // doesn't either -- there is no partial-repaint buffer to target).
        if (mCanvasLocked) {
            throw new IllegalArgumentException("Surface was already locked");
        }
        final boolean sunk = mFrameSink != null && mSinkWidth > 0 && mSinkHeight > 0;
        int w = sunk ? mSinkWidth : defaultWidth();
        int h = sunk ? mSinkHeight : defaultHeight();
        if (mDrawBitmap == null || mDrawBitmap.getWidth() != w || mDrawBitmap.getHeight() != h) {
            mDrawBitmap = android.graphics.Bitmap.createBitmapWithCanvasBacking(w, h);
            mDrawCanvas = new android.graphics.Canvas(mDrawBitmap);
        }
        mCanvasLocked = true;
        return mDrawCanvas;
    }
    public android.graphics.Canvas lockHardwareCanvas() {
        // AOSP routes this through a separate GL/HWUI-backed context
        // (HwuiContext). Mac.apk has no such layer, so -- matching
        // SurfaceHolderImpl.lockHardwareCanvas() -- share the same
        // software-canvas path as lockCanvas().
        return lockCanvas(null);
    }
    public void unlockCanvasAndPost(android.graphics.Canvas canvas) {
        checkNotReleased();
        // AOSP unlockSwCanvasAndPost(): canvas-identity check happens BEFORE
        // the locked-state check, exact message on each.
        if (canvas != mDrawCanvas) {
            throw new IllegalArgumentException("canvas object must be the same instance "
                + "that was previously returned by lockCanvas");
        }
        if (!mCanvasLocked) {
            throw new IllegalStateException("Surface was not locked");
        }
        mCanvasLocked = false;
        if (mDrawBitmap == null) return;
        // v1.0.1648: the canvas's own live pixels when available (no 24 MB copy
        // at a full-screen window); presentBitmap copies synchronously on this,
        // the drawing thread, which is the contract macapkLiveArgbOrNull states.
        int[] pixels = mDrawBitmap.macapkLiveArgbOrNull();
        if (pixels == null) pixels = mDrawBitmap.macapkArgbBuffer();
        if (pixels == null) return;
        MacapkFrameSink sink = mFrameSink;
        if (sink != null) {
            // An encoder's input: the picture goes to it, not to the window.
            sink.onFrame(pixels, mDrawBitmap.getWidth(), mDrawBitmap.getHeight(), System.nanoTime());
            return;
        }
        try {
            com.macapk.loader.NativeCocoa.presentBitmap(
                pixels, mDrawBitmap.getWidth(), mDrawBitmap.getHeight());
        } catch (UnsatisfiedLinkError e) {
            // Native method not registered (headless build / older runtime).
            // Painting still happens to memory; just no visible output.
            // Shares the warn-once flag SurfaceView's SurfaceHolderImpl uses
            // for the identical fallback so the log only fires once total.
            if (!com.macapk.loader.SurfaceCanvasState.warnedMissingNative) {
                com.macapk.loader.SurfaceCanvasState.warnedMissingNative = true;
                System.err.println(
                    "[Surface] presentBitmap not registered; Canvas paints invisibly: "
                    + e.getMessage());
            }
        }
    }

    // A bare Surface has no native buffer geometry to query for a size, so
    // fall back to the actual configured display size (the common case is a
    // full-screen presenting Surface) and only then to a fixed default,
    // mirroring the "sensible default" SurfaceHolderImpl.lockCanvas() already
    // falls back to when its frame Rect is empty.
    private static int defaultWidth() {
        try {
            int w = com.macapk.loader.NativeCocoa.phoneWidth();
            if (w > 0) return w;
        } catch (Throwable ignored) {}
        return 800;
    }
    private static int defaultHeight() {
        try {
            int h = com.macapk.loader.NativeCocoa.phoneHeight();
            if (h > 0) return h;
        } catch (Throwable ignored) {}
        return 600;
    }

    // AOSP Surface.setFrameRate(float, int): delegates to the 3-arg overload
    // with the seamless-only strategy.
    public void setFrameRate(float frameRate, int compatibility) {
        setFrameRate(frameRate, compatibility, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
    }
    // AOSP Surface.setFrameRate(float, int, int): native returns -EINVAL for
    // bad arguments, which the Java layer turns into IllegalArgumentException;
    // we validate the same argument shapes directly since there is no native
    // call to make. Never throws for a merely-unsupported device -- a fixed-
    // refresh-rate device is documented to accept the call and do nothing.
    public void setFrameRate(float frameRate, int compatibility, int changeFrameRateStrategy) {
        checkNotReleased();
        if (Float.isNaN(frameRate) || frameRate < 0f) {
            throw new IllegalArgumentException("frameRate must be >= 0, got " + frameRate);
        }
        if (compatibility != FRAME_RATE_COMPATIBILITY_DEFAULT
                && compatibility != FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                && compatibility != FRAME_RATE_COMPATIBILITY_GTE) {
            throw new IllegalArgumentException("invalid compatibility value: " + compatibility);
        }
        if (changeFrameRateStrategy != CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                && changeFrameRateStrategy != CHANGE_FRAME_RATE_ALWAYS) {
            throw new IllegalArgumentException(
                "invalid changeFrameRateStrategy value: " + changeFrameRateStrategy);
        }
        mFrameRate = frameRate;
    }

    // AOSP Surface.OutOfResourcesException -- thrown when the system cannot
    // allocate a Surface buffer. We never run out, but the type must exist
    // because callers (custom views, some camera libs) catch it by name.
    public static class OutOfResourcesException extends RuntimeException {
        public OutOfResourcesException() { super(); }
        public OutOfResourcesException(String name) { super(name); }
    }

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    // ======================================================================

    /** AOSP Surface.unlockCanvas(Canvas), deprecated since API 1 and documented
     *  as "this method is not supported, use unlockCanvasAndPost". AOSP's body
     *  is `throw new UnsupportedOperationException()` -- reproduced, because an
     *  app that calls it must see the throw rather than a silent no-post. */
    @Deprecated
    public void unlockCanvas(android.graphics.Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    /** AOSP Surface.clearFrameRate() (API 34): withdraw this surface's
     *  frame-rate vote. The vote is a stored hint here (see setFrameRate), so
     *  clearing it resets the same field. */
    public void clearFrameRate() {
        checkNotReleased();
        mFrameRate = 0f;
    }

    /** AOSP Surface.setFrameRate(FrameRateParams) (API 36): the RANGE form of
     *  the hint. Same validation and same storage as the scalar overloads. */
    public void setFrameRate(FrameRateParams params) {
        checkNotReleased();
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        mFrameRate = params.getDesiredMaxRate();
    }

    /** AOSP Surface.FrameRateParams (API 36): a desired frame-rate RANGE plus
     *  an optional fixed source rate, replacing the single-value hint. IGNORE is
     *  AOSP's "no vote" instance. */
    public static class FrameRateParams {
        /** AOSP Surface.FrameRateParams.IGNORE -- an empty vote. */
        public static final FrameRateParams IGNORE =
                new FrameRateParams(0f, 0f, 0f, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);

        private final float mDesiredMinRate;
        private final float mDesiredMaxRate;
        private final float mFixedSourceRate;
        private final int mChangeFrameRateStrategy;

        private FrameRateParams(float desiredMinRate, float desiredMaxRate,
                float fixedSourceRate, int changeFrameRateStrategy) {
            mDesiredMinRate = desiredMinRate;
            mDesiredMaxRate = desiredMaxRate;
            mFixedSourceRate = fixedSourceRate;
            mChangeFrameRateStrategy = changeFrameRateStrategy;
        }

        public float getDesiredMinRate() { return mDesiredMinRate; }
        public float getDesiredMaxRate() { return mDesiredMaxRate; }
        public float getFixedSourceRate() { return mFixedSourceRate; }
        public int getChangeFrameRateStrategy() { return mChangeFrameRateStrategy; }

        public static final class Builder {
            private float mDesiredMinRate;
            private float mDesiredMaxRate;
            private float mFixedSourceRate;
            private int mChangeFrameRateStrategy = CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS;

            public Builder() {}
            public Builder setDesiredRateRange(float desiredMinRate, float desiredMaxRate) {
                if (Float.isNaN(desiredMinRate) || desiredMinRate < 0f
                        || Float.isNaN(desiredMaxRate) || desiredMaxRate < 0f) {
                    throw new IllegalArgumentException("rates must be >= 0");
                }
                mDesiredMinRate = desiredMinRate;
                mDesiredMaxRate = desiredMaxRate;
                return this;
            }
            public Builder setFixedSourceRate(float fixedSourceRate) {
                if (Float.isNaN(fixedSourceRate) || fixedSourceRate < 0f) {
                    throw new IllegalArgumentException("fixedSourceRate must be >= 0");
                }
                mFixedSourceRate = fixedSourceRate;
                return this;
            }
            public Builder setChangeFrameRateStrategy(int changeFrameRateStrategy) {
                if (changeFrameRateStrategy != CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        && changeFrameRateStrategy != CHANGE_FRAME_RATE_ALWAYS) {
                    throw new IllegalArgumentException(
                        "invalid changeFrameRateStrategy value: " + changeFrameRateStrategy);
                }
                mChangeFrameRateStrategy = changeFrameRateStrategy;
                return this;
            }
            public FrameRateParams build() {
                return new FrameRateParams(mDesiredMinRate, mDesiredMaxRate,
                        mFixedSourceRate, mChangeFrameRateStrategy);
            }
        }
    }

    // ---- Parcelable -------------------------------------------------------
    // AOSP's Surface IS a Parcelable, and the missing `implements` is invisible
    // to a member-level diff while being loud at runtime: putParcelable /
    // Intent-extra type checks fail silently instead of throwing, and every
    // `instanceof Parcelable` on a Surface goes the wrong way. A Surface is a
    // live handle onto a buffer queue, so AOSP marshals a native token and the
    // reader re-attaches to the SAME queue. Mac.apk is a single process, and
    // Parcel here keeps a live object reference for exactly this case (see
    // android/os/Parcel.java writeParcelableBody / readParcelableBody): an empty
    // writeToParcel makes the Parcel carry the Surface ITSELF, so the reader
    // gets the same object -- which is precisely the cross-process contract,
    // achieved the only way it can be in-process. Writing bytes instead would
    // hand back a DIFFERENT, dead Surface.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) { }

    /** AOSP Surface.readFromParcel(Parcel): re-point THIS Surface at the one the
     *  parcel describes. */
    public void readFromParcel(android.os.Parcel source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        Object o = source.readValue(Surface.class.getClassLoader());
        if (o instanceof Surface) {
            Surface other = (Surface) o;
            mSurfaceTexture = other.mSurfaceTexture;
            mOwnerSurfaceView = other.mOwnerSurfaceView;
            mReleased = other.mReleased;
        }
    }

    public static final android.os.Parcelable.Creator<Surface> CREATOR =
            new android.os.Parcelable.Creator<Surface>() {
        @Override public Surface createFromParcel(android.os.Parcel source) {
            // Reached only when the body carried real bytes; the live-reference
            // path in Parcel short-circuits before this (see writeToParcel).
            return new Surface();
        }
        @Override public Surface[] newArray(int size) { return new Surface[size]; }
    };
}
