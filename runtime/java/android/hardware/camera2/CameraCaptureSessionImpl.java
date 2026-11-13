// android.hardware.camera2.CameraCaptureSessionImpl -- the concrete
// CameraCaptureSession. Package-private, mirroring AOSP, where the app-visible
// type is the abstract CameraCaptureSession and the implementation is
// android.hardware.camera2.impl.CameraCaptureSessionImpl.
//
// This class is the join between camera2's request model and the AVFoundation
// capture engine. It owns three things:
//   1. the CONFIGURATION step (which outputs, what geometry, what frame rate),
//   2. the FRAME PUMP that notices the native layer has produced a frame,
//   3. the CALLBACK DISPATCH that turns a frame into onImageAvailable +
//      onCaptureStarted/onCaptureCompleted on the app's own Handler.
//
// ---------------------------------------------------------------------------
// WHICH OUTPUTS RECEIVE FRAMES, AND THE SESSION SAYS SO
// ---------------------------------------------------------------------------
// Two output shapes are real consumers:
//
//   * ImageReader-backed Surfaces: the reader pulls the locked CVPixelBuffer's
//     YUV planes directly (ImageReader.macapkAttachCameraSource).
//   * SurfaceTexture-backed Surfaces (a TextureView preview, a GL consumer
//     like WebRTC's SurfaceTextureHelper): the session attaches the native
//     frame source to the SurfaceTexture and publishes each frame's metadata
//     through macapkQueueFrame; the consumer's latch (updateTexImage, or
//     TextureView's software draw) pulls the pixels as RGBA.
//
// A MediaRecorder / MediaCodec / plain-SurfaceView target still has no
// producer path. So: a session whose outputs contain NEITHER an ImageReader
// nor a SurfaceTexture-backed Surface is FAILED at configure time with an
// explicit log line, not configured-and-left-silent. That is the difference
// between an app that reports "camera unsupported" and an app that shows a
// black rectangle forever while its log says nothing. A session with SOME
// consumable outputs and some others is configured, and the others are named
// in the log as receiving nothing.
//
// ---------------------------------------------------------------------------
// WHY THERE IS A POLLING PUMP RATHER THAN A CALLBACK
// ---------------------------------------------------------------------------
// The native seam (com.macapk.loader.CameraBridge) is deliberately pull-only:
// its AVFoundation delegate runs on a private dispatch queue and does no JNI at
// all, which is what keeps the capture queue from ever blocking on the JVM.
// The only liveness signal it exposes is framesDelivered(), a counter. So the
// pump watches that counter from a daemon thread and turns each advance into
// the callbacks camera2 owes the app. Poll interval is derived from the
// negotiated frame rate (about three polls per frame), which costs one mutexed
// counter read per poll and adds at most a third of a frame of latency.
//
// The pump runs only while there is an active request -- a repeating request or
// a queued one-shot -- because camera2 delivers no frames to an app that has
// not asked for any.
package android.hardware.camera2;

import android.graphics.ImageFormat;
import android.hardware.camera2.params.OutputConfiguration;
import android.graphics.SurfaceTexture;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

import com.macapk.loader.CameraBridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class CameraCaptureSessionImpl extends CameraCaptureSession {

    /** Frame rate requested when the app has not asked for one. 30 is what
     *  every camera2 preview template runs at on real hardware, and it is
     *  clamped into the device's supported range by the native configure(). */
    private static final int DEFAULT_FPS = 30;

    /** How long the pump waits for the FIRST frame before saying out loud that
     *  capture started but nothing is arriving. Existing to prevent the exact
     *  failure this subsystem's honesty rule is about: a session that opened,
     *  configured, reported success, and then silently delivers nothing. */
    private static final long FIRST_FRAME_WARN_MS = 5_000L;

    private static final AtomicInteger sNextSequenceId = new AtomicInteger(1);

    private final CameraDeviceImpl mDevice;
    private final long mHandle;
    private final List<Surface> mOutputs;
    private final CameraCaptureSession.StateCallback mStateCallback;
    private final CameraDispatch mStateDispatch;

    private final Object mLock = new Object();

    /** The ImageReaders behind mOutputs. Written once by doConfigure before
     *  mConfigured is set, read by the pump thereafter. */
    private volatile List<ImageReader> mReaders = Collections.emptyList();

    /** The SurfaceTextures behind mOutputs (TextureView previews, GL
     *  consumers). Same write-once-then-pump-reads discipline as mReaders. */
    private volatile List<SurfaceTexture> mSurfaceTextures = Collections.emptyList();

    /**
     * The transform matrix handed to SurfaceTexture consumers with every
     * frame: AOSP's mtxFlipV, verbatim from
     * frameworks/native/libs/gui/GLConsumerUtils.cpp (android-14), where it is
     * composed unconditionally: "GLConsumer uses the GL convention where
     * (0, 0) is the bottom-left corner ... Add an additional vertical flip
     * after all other transforms to map from GL convention to buffer queue
     * memory layout, where (0, 0) is the top-left corner."
     *
     * Our RGBA copies put the image's top row at buffer row 0 (the buffer
     * queue memory layout), there is no crop, and no rotation term is
     * applied, so for an unmirrored stream with SENSOR_ORIENTATION 0 pure
     * flip-V is the AOSP answer (mirrored streams: transformFor). With a
     * non-zero SENSOR_ORIENTATION (a landscape window; see CameraBackend)
     * AOSP's transform would also rotate by it, which this does not yet do.
     * Column-major
     * for glUniformMatrix4fv, per the SurfaceTexture.getTransformMatrix
     * contract. macapkQueueFrame clones it, so sharing one instance is safe.
     */
    private static final float[] MTX_FLIP_V = {
        1f,  0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f,  0f, 1f, 0f,
        0f,  1f, 0f, 1f,
    };

    /**
     * MTX_FLIP_V plus a horizontal mirror (s' = 1 - s, t' = 1 - t). Column-major.
     *
     * Used for OutputConfiguration.MIRROR_MODE_H, and for MIRROR_MODE_AUTO (the
     * default) on a FRONT-facing camera: on a device, CameraUtils::
     * getRotationTransform (frameworks/av camera/CameraUtils.cpp, applied to
     * each output by Camera3Device) adds NATIVE_WINDOW_TRANSFORM_FLIP_H in
     * both cases,
     * and consumers rely on it -- WebRTC's Camera2Session undoes the front
     * camera's mirror and the app re-mirrors for a selfie view. Same constant,
     * same reason, as android.hardware.Camera's MTX_FLIP_V_FRONT.
     */
    private static final float[] MTX_FLIP_V_MIRROR_H = {
        -1f,  0f, 0f, 0f,
         0f, -1f, 0f, 0f,
         0f,  0f, 1f, 0f,
         1f,  1f, 0f, 1f,
    };

    /**
     * MIRROR_MODE_V: the stream is mirrored vertically (getRotationTransform
     * adds NATIVE_WINDOW_TRANSFORM_FLIP_V), which cancels GLConsumer's own flip-V,
     * leaving the identity.
     */
    private static final float[] MTX_IDENTITY = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };

    /** LENS_FACING_FRONT, read once: what MIRROR_MODE_AUTO means for this camera. */
    private final boolean mFrontFacing;
    /** Each output's OutputConfiguration mirror mode; absent means MIRROR_MODE_AUTO. */
    private final Map<Surface, Integer> mMirrorModes;
    /** Per SurfaceTexture output, the transform its frames are queued with. */
    private volatile Map<SurfaceTexture, float[]> mTextureTransforms = Collections.emptyMap();

    private volatile boolean mConfigured;
    private volatile boolean mClosed;

    /** Geometry we asked the native layer for, kept so a frame-rate change can
     *  re-run configure() without moving the picture size underneath the
     *  attached readers. */
    private int mRequestedWidth;
    private int mRequestedHeight;
    /** Volatile: written by whichever app thread submits a request carrying
     *  CONTROL_AE_TARGET_FPS_RANGE, read by the config thread and the pump. */
    private volatile int mAppliedFps = DEFAULT_FPS;

    // Active request state. Guarded by mLock.
    private CaptureRequest mRepeatingRequest;
    private List<CaptureRequest> mRepeatingBurst;
    private int mRepeatingBurstIndex;
    private CaptureCallback mRepeatingCallback;
    private CameraDispatch mRepeatingDispatch;
    private int mRepeatingSequenceId;
    private final ArrayDeque<OneShot> mOneShots = new ArrayDeque<>();

    private Thread mPump;
    private volatile boolean mPumpRun;

    /**
     * Bumped every time the pump is stopped. The running pump captures the
     * value it started with and exits as soon as it no longer matches.
     *
     * A plain boolean is NOT sufficient, and the bug it hides is a duplicated
     * frame stream: stopPump() clears mPumpRun and returns as soon as the old
     * thread has had 500ms to notice, but if the app submits a new request in
     * that window ensurePump() sets mPumpRun back to true -- and the old thread,
     * which was about to exit on !mPumpRun, sees true again and keeps going.
     * Two pumps would then dispatch every frame twice, to the same readers and
     * the same CaptureCallback. The generation makes the old thread's exit
     * condition independent of the new thread's start.
     */
    private volatile int mPumpGeneration;

    /** One queued single capture. */
    private static final class OneShot {
        final int sequenceId;
        final CaptureRequest request;
        final CaptureCallback callback;
        final CameraDispatch dispatch;
        final boolean last;   // last request of a burst -> ends the sequence

        OneShot(int sequenceId, CaptureRequest request, CaptureCallback callback,
                CameraDispatch dispatch, boolean last) {
            this.sequenceId = sequenceId;
            this.request = request;
            this.callback = callback;
            this.dispatch = dispatch;
            this.last = last;
        }
    }

    CameraCaptureSessionImpl(CameraDeviceImpl device, long handle, List<Surface> outputs,
            Map<Surface, Integer> mirrorModes,
            CameraCaptureSession.StateCallback callback, CameraDispatch dispatch) {
        mDevice = device;
        mHandle = handle;
        mOutputs = new ArrayList<>(outputs);
        mStateCallback = callback;
        mStateDispatch = dispatch;
        int index = CameraBackend.deviceIndexForId(device.getId());
        mFrontFacing = index >= 0
                && CameraBridge.getDeviceFacing(index) == CameraBridge.FACING_FRONT;
        mMirrorModes = mirrorModes != null ? mirrorModes : Collections.emptyMap();
    }

    /** The SurfaceTexture transform for one output, from its mirror mode. */
    private float[] transformFor(Surface output) {
        int mode = mMirrorModes.getOrDefault(output, OutputConfiguration.MIRROR_MODE_AUTO);
        switch (mode) {
            case OutputConfiguration.MIRROR_MODE_NONE: return MTX_FLIP_V;
            case OutputConfiguration.MIRROR_MODE_H:    return MTX_FLIP_V_MIRROR_H;
            case OutputConfiguration.MIRROR_MODE_V:    return MTX_IDENTITY;
            default:                                   return mFrontFacing ? MTX_FLIP_V_MIRROR_H : MTX_FLIP_V;
        }
    }

    // ---- Configuration ---------------------------------------------------

    /** Configure on a background thread. AOSP's createCaptureSession returns
     *  immediately and reports through onConfigured/onConfigureFailed, and the
     *  work here genuinely blocks: the native configure() takes the device's
     *  configuration lock and start() blocks until AVFoundation has the session
     *  running. */
    void configureAsync() {
        Thread t = new Thread(this::doConfigure, "macapk-camera-config");
        t.setDaemon(true);
        t.start();
    }

    private void doConfigure() {
        try {
            if (!doConfigureInner()) {
                mStateDispatch.run(() -> mStateCallback.onConfigureFailed(this));
            }
        } catch (Throwable t) {
            // Anything unexpected still has to reach the app as a configure
            // failure. A session that throws on its own thread and reports
            // nothing is the silent-hang failure mode again.
            CameraBackend.warn("createCaptureSession threw while configuring:");
            t.printStackTrace();
            mStateDispatch.run(() -> mStateCallback.onConfigureFailed(this));
        }
    }

    private boolean doConfigureInner() {
        if (mHandle == CameraBridge.NO_SESSION) {
            CameraBackend.warn("createCaptureSession FAILED: the camera device is not open");
            return false;
        }

        // 1. Which outputs can actually receive frames: ImageReaders and
        //    SurfaceTexture-backed Surfaces. Everything else has no producer
        //    path and is named in the log as receiving nothing.
        List<ImageReader> readers = new ArrayList<>();
        List<SurfaceTexture> textures = new ArrayList<>();
        Map<SurfaceTexture, float[]> transforms = new HashMap<>();
        List<Surface> unsupported = new ArrayList<>();
        for (Surface s : mOutputs) {
            ImageReader r = ImageReader.macapkReaderForSurface(s);
            if (r != null) {
                readers.add(r);
                continue;
            }
            SurfaceTexture st = s.macapkGetSurfaceTexture();
            if (st != null && !st.isReleased()) {
                textures.add(st);
                transforms.put(st, transformFor(s));
                continue;
            }
            unsupported.add(s);
        }

        if (readers.isEmpty() && textures.isEmpty()) {
            CameraBackend.warn("createCaptureSession FAILED: none of the " + mOutputs.size()
                    + " output Surface(s) is backed by an ImageReader or a SurfaceTexture."
                    + " This backend delivers camera frames to ImageReader and"
                    + " SurfaceTexture/TextureView outputs (MediaRecorder / MediaCodec /"
                    + " plain SurfaceView targets are not implemented), so the session"
                    + " would have produced no frames.");
            return false;
        }
        if (!unsupported.isEmpty()) {
            CameraBackend.warn(unsupported.size() + " of " + mOutputs.size()
                    + " output Surface(s) in this session are neither ImageReader nor"
                    + " SurfaceTexture outputs and will receive no frames.");
        }

        // 2. Format. YUV_420_888 is the only thing the capture backend produces,
        //    and an ImageReader created for another format would hand its app
        //    planes that do not match what it asked for.
        for (ImageReader r : readers) {
            if (r.getImageFormat() != ImageFormat.YUV_420_888) {
                CameraBackend.warn("createCaptureSession FAILED: ImageReader format 0x"
                        + Integer.toHexString(r.getImageFormat())
                        + " is not supported. This backend produces YUV_420_888 (0x23) only;"
                        + " CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP advertises"
                        + " no other output format.");
                return false;
            }
        }

        // 3. Geometry. Every output in one session shares one native stream, so
        //    they have to agree; AOSP would configure a separate stream per
        //    output. An ImageReader's size wins (its app reads the planes
        //    directly, so its geometry is the least flexible); otherwise the
        //    SurfaceTexture's default buffer size, which is what the camera
        //    stack sizes the stream from on real Android; otherwise the
        //    device's own active-format default, so an app that never sized
        //    its texture still gets a truthful, working stream.
        int w = 0;
        int h = 0;
        if (!readers.isEmpty()) {
            ImageReader first = readers.get(0);
            w = first.getWidth();
            h = first.getHeight();
            for (ImageReader r : readers) {
                if (r.getWidth() != w || r.getHeight() != h) {
                    CameraBackend.warn("this session has ImageReader outputs of different sizes ("
                            + w + "x" + h + " and " + r.getWidth() + "x" + r.getHeight()
                            + "). The capture backend drives ONE stream, so every output receives "
                            + w + "x" + h + " frames.");
                }
            }
        } else {
            for (SurfaceTexture st : textures) {
                if (st.macapkGetDefaultWidth() > 0 && st.macapkGetDefaultHeight() > 0) {
                    w = st.macapkGetDefaultWidth();
                    h = st.macapkGetDefaultHeight();
                    break;
                }
            }
            if (w <= 0 || h <= 0) {
                w = CameraBridge.getWidth(mHandle);
                h = CameraBridge.getHeight(mHandle);
                CameraBackend.warn("no output declared a size (SurfaceTexture default buffer"
                        + " size unset); using the device default " + w + "x" + h + "."
                        + " Call SurfaceTexture.setDefaultBufferSize with a size from"
                        + " StreamConfigurationMap.getOutputSizes to pick one.");
            }
        }
        if (w <= 0 || h <= 0) {
            CameraBackend.warn("createCaptureSession FAILED: could not determine a stream size"
                    + " (no ImageReader, no SurfaceTexture default size, no device default)");
            return false;
        }
        mRequestedWidth = w;
        mRequestedHeight = h;

        if (!CameraBridge.configure(mHandle, w, h, mAppliedFps)) {
            CameraBackend.warn("createCaptureSession FAILED: the capture backend refused "
                    + w + "x" + h + " @" + mAppliedFps + "fps");
            return false;
        }

        // 4. The NEGOTIATED geometry, which is what the app will actually
        //    receive. The native configure picks the advertised format closest
        //    in area to the request, so an app that picked its size out of
        //    getOutputSizes() gets an exact match and an app that hardcoded one
        //    may not.
        int nw = CameraBridge.getWidth(mHandle);
        int nh = CameraBridge.getHeight(mHandle);
        if (nw != w || nh != h) {
            CameraBackend.warn("requested " + w + "x" + h + " but the camera negotiated "
                    + nw + "x" + nh + ". Image.getWidth()/getHeight() report the REAL buffer"
                    + " size (" + nw + "x" + nh + "); ImageReader.getWidth()/getHeight() keep"
                    + " reporting what the reader was created with, per the AOSP contract."
                    + " Pick a size from"
                    + " StreamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)"
                    + " to avoid this.");
        }

        if (!CameraBridge.start(mHandle)) {
            CameraBackend.warn("createCaptureSession FAILED: the capture backend could not"
                    + " start the AVFoundation session");
            return false;
        }

        // 5. Bind the consumers to the native frame source. From here on
        //    acquireLatestImage() pulls straight from the locked capture
        //    buffer, and a SurfaceTexture latch pulls the newest frame as
        //    RGBA.
        for (ImageReader r : readers) {
            r.macapkAttachCameraSource(mHandle, nw, nh);
        }
        for (SurfaceTexture st : textures) {
            st.macapkAttachCameraSource(mHandle, nw, nh);
        }
        mReaders = readers;
        mTextureTransforms = transforms;
        mSurfaceTextures = textures;
        mConfigured = true;

        CameraBackend.trace("session configured: " + readers.size() + " ImageReader output(s), "
                + textures.size() + " SurfaceTexture output(s), "
                + nw + "x" + nh + " @" + mAppliedFps + "fps, native session running");

        mStateDispatch.run(() -> mStateCallback.onConfigured(this));
        // AOSP reports onReady once the session has no pending work, which a
        // freshly configured session does not.
        mStateDispatch.run(() -> mStateCallback.onReady(this));
        return true;
    }

    // ---- CameraCaptureSession --------------------------------------------

    @Override
    public CameraDevice getDevice() { return mDevice; }

    /** AOSP: "the input Surface, or null if the session is not reprocessable".
     *  This backend has no reprocessing path. */
    @Override
    public Surface getInputSurface() { return null; }

    @Override
    public boolean isReprocessable() { return false; }

    private void checkUsable() {
        if (mClosed) {
            throw new IllegalStateException("Session has been closed; further changes are illegal.");
        }
        if (!mConfigured) {
            throw new IllegalStateException("Session has not been configured yet.");
        }
    }

    /** AOSP rejects a request whose targets are not part of the session's
     *  configured outputs. Doing the same is what stops an app from quietly
     *  pointing a request at a Surface we would never write to. */
    private void checkRequest(CaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        List<Surface> targets = request.getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "No output Surfaces present in the request");
        }
        for (Surface s : targets) {
            if (!mOutputs.contains(s)) {
                throw new IllegalArgumentException(
                        "CaptureRequest contains unconfigured Input/Output Surface!");
            }
        }
    }

    private void checkBurst(List<CaptureRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one request must be given");
        }
        for (CaptureRequest r : requests) checkRequest(r);
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback callback, Handler handler)
            throws CameraAccessException {
        return captureCore(request, callback,
                callback != null ? CameraDispatch.of(handler) : null);
    }

    private int captureCore(CaptureRequest request, CaptureCallback callback,
            CameraDispatch dispatch) throws CameraAccessException {
        checkUsable();
        checkRequest(request);
        int seq = sNextSequenceId.getAndIncrement();
        synchronized (mLock) {
            mOneShots.add(new OneShot(seq, request, callback, dispatch, true));
        }
        applyFrameRate(request);
        ensurePump();
        return seq;
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        return captureBurstCore(requests, callback,
                callback != null ? CameraDispatch.of(handler) : null);
    }

    private int captureBurstCore(List<CaptureRequest> requests, CaptureCallback callback,
            CameraDispatch dispatch) throws CameraAccessException {
        checkUsable();
        checkBurst(requests);
        int seq = sNextSequenceId.getAndIncrement();
        synchronized (mLock) {
            for (int i = 0; i < requests.size(); i++) {
                mOneShots.add(new OneShot(seq, requests.get(i), callback, dispatch,
                        i == requests.size() - 1));
            }
        }
        applyFrameRate(requests.get(0));
        ensurePump();
        return seq;
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        return setRepeatingRequestCore(request, callback,
                callback != null ? CameraDispatch.of(handler) : null);
    }

    private int setRepeatingRequestCore(CaptureRequest request, CaptureCallback callback,
            CameraDispatch dispatch) throws CameraAccessException {
        checkUsable();
        checkRequest(request);
        int seq = sNextSequenceId.getAndIncrement();
        synchronized (mLock) {
            mRepeatingRequest = request;
            mRepeatingBurst = null;
            mRepeatingBurstIndex = 0;
            mRepeatingCallback = callback;
            mRepeatingDispatch = dispatch;
            mRepeatingSequenceId = seq;
        }
        applyFrameRate(request);
        CameraBackend.trace("setRepeatingRequest seq=" + seq + " targets="
                + request.getTargets().size());
        ensurePump();
        // AOSP reports onActive once the session starts processing a repeating
        // request.
        mStateDispatch.run(() -> mStateCallback.onActive(this));
        return seq;
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        return setRepeatingBurstCore(requests, callback,
                callback != null ? CameraDispatch.of(handler) : null);
    }

    private int setRepeatingBurstCore(List<CaptureRequest> requests, CaptureCallback callback,
            CameraDispatch dispatch) throws CameraAccessException {
        checkUsable();
        checkBurst(requests);
        int seq = sNextSequenceId.getAndIncrement();
        synchronized (mLock) {
            mRepeatingRequest = null;
            mRepeatingBurst = new ArrayList<>(requests);
            mRepeatingBurstIndex = 0;
            mRepeatingCallback = callback;
            mRepeatingDispatch = dispatch;
            mRepeatingSequenceId = seq;
        }
        applyFrameRate(requests.get(0));
        ensurePump();
        mStateDispatch.run(() -> mStateCallback.onActive(this));
        return seq;
    }

    // ---- The Executor-based (API 28) overloads ---------------------------
    //
    // AOSP's CameraCaptureSessionImpl implements each of these by wrapping the
    // listener in a proxy bound to the app's Executor and then calling the same
    // device method the Handler overload calls
    // (impl/CameraCaptureSessionImpl.java captureSingleRequest ->
    // mDeviceImpl.capture(...)). The four methods above were split into
    // *Core(request, callback, CameraDispatch) for exactly that reason: the
    // Executor and Handler paths differ ONLY in where the callback lands, and
    // CameraDispatch already models that. Duplicating the queueing logic would
    // let the two paths drift.
    //
    // AOSP rejects a null executor and a null listener on these overloads
    // before doing anything else -- unlike the Handler overloads, where both
    // are @Nullable -- so the checks below are behaviour, not defensiveness.

    @Override
    public int captureSingleRequest(CaptureRequest request, java.util.concurrent.Executor executor,
            CaptureCallback listener) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return captureCore(request, listener, CameraDispatch.of(executor));
    }

    @Override
    public int captureBurstRequests(List<CaptureRequest> requests,
            java.util.concurrent.Executor executor, CaptureCallback listener)
            throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return captureBurstCore(requests, listener, CameraDispatch.of(executor));
    }

    @Override
    public int setSingleRepeatingRequest(CaptureRequest request,
            java.util.concurrent.Executor executor, CaptureCallback listener)
            throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return setRepeatingRequestCore(request, listener, CameraDispatch.of(executor));
    }

    @Override
    public int setRepeatingBurstRequests(List<CaptureRequest> requests,
            java.util.concurrent.Executor executor, CaptureCallback listener)
            throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return setRepeatingBurstCore(requests, listener, CameraDispatch.of(executor));
    }

    // ---- Buffer pre-allocation, deferred outputs, offline mode ------------

    /**
     * API 23. AOSP (impl/CameraDeviceImpl.prepare(Surface)) rejects a null
     * Surface and one that is not part of this session, then asks the HAL to
     * pre-allocate buffers; StateCallback.onSurfacePrepared fires when it is
     * done.
     *
     * Both halves are real here. The validation is AOSP's, verbatim. The
     * pre-allocation itself has nothing to do: the AVFoundation path writes
     * into the consumer's own single buffer slot and allocates nothing ahead of
     * time, so the work is genuinely finished the moment it is asked for. What
     * matters is that the CALLBACK still fires -- an app that waits for
     * onSurfacePrepared before submitting its first request would otherwise
     * hang forever, which is the one outcome a no-op must not produce.
     */
    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        if (surface == null) {
            throw new IllegalArgumentException("Surface is null");
        }
        checkUsable();
        if (!mOutputs.contains(surface)) {
            throw new IllegalArgumentException("Surface is not part of this session");
        }
        mStateDispatch.run(() -> mStateCallback.onSurfacePrepared(this, surface));
    }

    /**
     * API 26. AOSP (impl/CameraDeviceImpl.finalizeOutputConfigs) throws
     * IllegalArgumentException("deferred config is null or empty") for an empty
     * list, and IllegalArgumentException("Deferred config is not part of this
     * session") for a config that was not among the session's DEFERRED outputs.
     *
     * This backend never accepts a deferred output: createCaptureSession and
     * createCaptureSessionByOutputConfigurations both resolve every Surface up
     * front (CameraDeviceImpl.surfacesOf), so a session has no deferred configs
     * to finalize and every config reaching here takes AOSP's second throw.
     * That is the same exception, with the same message, that a real device
     * gives for finalizing something it never deferred.
     */
    @Override
    public void finalizeOutputConfigurations(
            List<android.hardware.camera2.params.OutputConfiguration> outputConfigs)
            throws CameraAccessException {
        if (outputConfigs == null || outputConfigs.size() == 0) {
            throw new IllegalArgumentException("deferred config is null or empty");
        }
        checkUsable();
        throw new IllegalArgumentException("Deferred config is not part of this session");
    }

    /**
     * API 26. AOSP (impl/CameraDeviceImpl.updateOutputConfiguration) throws
     * IllegalArgumentException("Invalid output configuration") when the config's
     * Surface is not one of the session's, then swaps in the config's shared
     * Surfaces.
     *
     * The validation is reproduced. The swap is not, and is NOT faked: this
     * backend writes each frame to the Surfaces the session was configured
     * with, and OutputConfiguration.enableSurfaceSharing has no effect here, so
     * silently accepting the call would leave the app's newly added Surface
     * black with no error. The throw names that at the call site.
     */
    @Override
    public void updateOutputConfiguration(
            android.hardware.camera2.params.OutputConfiguration config)
            throws CameraAccessException {
        checkUsable();
        if (config == null || !mOutputs.contains(config.getSurface())) {
            throw new IllegalArgumentException("Invalid output configuration");
        }
        throw new UnsupportedOperationException(
                "updateOutputConfiguration: this camera backend does not implement output"
                        + " Surface sharing, so the added Surfaces would never receive frames");
    }

    /**
     * API 30. AOSP (impl/CameraDeviceImpl.supportsOfflineProcessing) rejects a
     * null Surface and one that is not part of this session, then answers from
     * the HAL's per-stream offline support set.
     *
     * That set is empty here, so the answer is false for every Surface -- the
     * same answer a device without the OFFLINE_PROCESSING capability gives, and
     * the answer that makes an app skip switchToOffline entirely.
     */
    @Override
    public boolean supportsOfflineProcessing(Surface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("Surface is null");
        }
        checkUsable();
        if (!mOutputs.contains(surface)) {
            throw new IllegalArgumentException("Surface is not part of this session");
        }
        return false;
    }

    /**
     * API 30. AOSP (impl/CameraDeviceImpl.switchToOffline) throws
     * IllegalArgumentException("Invalid offline surfaces!") for an empty
     * collection, then per Surface: IllegalArgumentException("Offline surface
     * is not part of this session") and IllegalArgumentException("Surface: X
     * does not  support offline mode").
     *
     * Since supportsOfflineProcessing is false for every Surface, every valid
     * call lands on the last of those, which is precisely what a real device
     * without offline support does. No CameraOfflineSession is fabricated:
     * handing back a session that never calls onReady would hang the app.
     */
    @Override
    public CameraOfflineSession switchToOffline(java.util.Collection<Surface> offlineSurfaces,
            java.util.concurrent.Executor executor,
            CameraOfflineSession.CameraOfflineSessionCallback listener)
            throws CameraAccessException {
        if (offlineSurfaces == null || offlineSurfaces.isEmpty()) {
            throw new IllegalArgumentException("Invalid offline surfaces!");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        checkUsable();
        for (Surface surface : offlineSurfaces) {
            if (!mOutputs.contains(surface)) {
                throw new IllegalArgumentException("Offline surface is not part of this session");
            }
            throw new IllegalArgumentException(
                    "Surface: " + surface + " does not  support offline mode");
        }
        return null;
    }

    /**
     * AOSP javadoc, verbatim: "Cancel any ongoing repeating capture set by
     * either setRepeatingRequest or setRepeatingBurst. Has no effect on requests
     * submitted through capture or captureBurst. ... To ensure that the device
     * has finished processing all of its capture requests and is in ready
     * state, wait for the onReady callback after calling this method."
     *
     * So: queued one-shots are deliberately LEFT in place (the pump stays alive
     * to serve them, because hasActiveRequest() still sees them), the repeating
     * sequence ends with onCaptureSequenceCompleted, and onReady follows.
     */
    @Override
    public void stopRepeating() throws CameraAccessException {
        if (mClosed) return;
        int seq;
        CaptureCallback cb;
        CameraDispatch d;
        synchronized (mLock) {
            seq = mRepeatingSequenceId;
            cb = mRepeatingCallback;
            d = mRepeatingDispatch;
            mRepeatingRequest = null;
            mRepeatingBurst = null;
            mRepeatingCallback = null;
            mRepeatingDispatch = null;
            mRepeatingSequenceId = 0;
        }
        CameraBackend.trace("stopRepeating seq=" + seq);
        if (cb != null && d != null && seq != 0) {
            final long fn = CameraBridge.framesDelivered(mHandle);
            d.run(() -> cb.onCaptureSequenceCompleted(this, seq, fn));
        }
        stopPumpIfIdle();
        mStateDispatch.run(() -> mStateCallback.onReady(this));
    }

    /**
     * AOSP javadoc, verbatim: "Discard all captures currently pending and
     * in-progress as fast as possible. ... If a repeating request or a repeating
     * burst is set, it will be cleared. ... Once all pending requests are either
     * completed or thrown away, the onReady callback will be called."
     *
     * That is why this ends by calling stopRepeating() -- clearing the repeating
     * request and delivering onReady is part of abort's own contract, not a
     * convenience.
     */
    @Override
    public void abortCaptures() throws CameraAccessException {
        if (mClosed) return;
        List<OneShot> aborted = new ArrayList<>();
        synchronized (mLock) {
            aborted.addAll(mOneShots);
            mOneShots.clear();
        }
        // One onCaptureSequenceAborted per SEQUENCE, not per request: a burst
        // shares one sequence id.
        int lastSeq = -1;
        for (OneShot o : aborted) {
            if (o.sequenceId == lastSeq || o.callback == null || o.dispatch == null) continue;
            lastSeq = o.sequenceId;
            final OneShot shot = o;
            o.dispatch.run(() -> shot.callback.onCaptureSequenceAborted(this, shot.sequenceId));
        }
        stopRepeating();
    }

    @Override
    public void close() {
        closeInternal();
    }

    /** Close without going back through the device, so the device's own close()
     *  cannot recurse. */
    void closeInternal() {
        // Latch under the lock, not with a bare volatile check-then-set:
        // CameraDevice.close() and an app's own session.close() can race, and
        // two winners would stop the native session twice and deliver onClosed
        // twice to a callback that expects it once.
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
        }
        stopPump();

        for (ImageReader r : mReaders) {
            r.macapkDetachCameraSource();
        }
        mReaders = Collections.emptyList();
        for (SurfaceTexture st : mSurfaceTextures) {
            st.macapkDetachCameraSource();
        }
        mSurfaceTextures = Collections.emptyList();
        mTextureTransforms = Collections.emptyMap();

        // Stop the AVFoundation session but leave the device open: AOSP's
        // session close does not close the CameraDevice, and an app may
        // immediately create another session on it. Stopping also turns the
        // camera indicator light off, which the user is entitled to see.
        if (mHandle != CameraBridge.NO_SESSION) {
            CameraBridge.stop(mHandle);
        }
        mDevice.forgetSession(this);
        CameraBackend.trace("session closed");
        mStateDispatch.run(() -> mStateCallback.onClosed(this));
    }

    // ---- Frame rate ------------------------------------------------------

    /**
     * Honour CONTROL_AE_TARGET_FPS_RANGE if the app set one. This is the only
     * CaptureRequest key this backend acts on, which is why it is also the only
     * key CameraCharacteristics.getAvailableCaptureRequestKeys() advertises.
     *
     * Re-running configure() while the session is live is legal: it takes the
     * device's configuration lock and re-picks the format by nearest area to
     * the SAME requested size, so the picture geometry cannot move underneath
     * the attached readers.
     */
    private void applyFrameRate(CaptureRequest request) {
        android.util.Range<Integer> range = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        if (range == null || range.getUpper() == null) return;
        int fps = range.getUpper().intValue();
        if (fps <= 0 || fps == mAppliedFps) return;
        if (!CameraBridge.configure(mHandle, mRequestedWidth, mRequestedHeight, fps)) {
            CameraBackend.warn("could not apply CONTROL_AE_TARGET_FPS_RANGE upper bound "
                    + fps + "fps; staying at " + mAppliedFps + "fps");
            return;
        }
        mAppliedFps = fps;
        CameraBackend.trace("applied CONTROL_AE_TARGET_FPS_RANGE -> " + fps + "fps");
    }

    // ---- The frame pump --------------------------------------------------

    private boolean hasActiveRequest() {
        synchronized (mLock) {
            return mRepeatingRequest != null || mRepeatingBurst != null || !mOneShots.isEmpty();
        }
    }

    private void ensurePump() {
        final int generation;
        synchronized (mLock) {
            if (mPumpRun || mClosed) return;
            mPumpRun = true;
            generation = mPumpGeneration;
            mPump = new Thread(() -> pumpLoop(generation), "macapk-camera-pump");
            mPump.setDaemon(true);
            mPump.start();
        }
    }

    private void stopPumpIfIdle() {
        if (!hasActiveRequest()) stopPump();
    }

    private void stopPump() {
        Thread t;
        synchronized (mLock) {
            mPumpRun = false;
            mPumpGeneration++;
            t = mPump;
            mPump = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pumpLoop(int generation) {
        // About three polls per frame: enough that a frame is picked up well
        // within its own interval, cheap enough that the cost is a mutexed
        // counter read.
        long pollMs = Math.max(2L, 1000L / (Math.max(1, mAppliedFps) * 3L));
        // Baseline: the native session has been running since configure, so
        // there may already be a frame in the slot. Starting from the current
        // count means the app's first callback corresponds to a frame captured
        // AFTER it asked for one.
        long seen = CameraBridge.framesDelivered(mHandle);
        long startedNs = System.nanoTime();
        // Latched once the no-frames warning is no longer wanted, either because
        // it has been printed or because frames are flowing and it would be
        // wrong. One flag rather than two because both outcomes are "never warn
        // from here on".
        boolean noFrameWarningSettled = false;

        CameraBackend.trace("frame pump started, polling every " + pollMs + "ms");

        while (mPumpRun && !mClosed && mPumpGeneration == generation) {
            long delivered = CameraBridge.framesDelivered(mHandle);
            if (delivered > seen) {
                seen = delivered;
                noFrameWarningSettled = true;   // frames are flowing
                dispatchFrame(delivered);
            } else if (!noFrameWarningSettled
                    && (System.nanoTime() - startedNs) / 1_000_000L > FIRST_FRAME_WARN_MS) {
                noFrameWarningSettled = true;
                // The one thing this subsystem must never do is look like it is
                // working while producing nothing. If the counter has not moved
                // the camera is not delivering, and the log has to say so.
                CameraBackend.warn("capture is running but AVFoundation has delivered 0 frames"
                        + " after " + (FIRST_FRAME_WARN_MS / 1000) + "s."
                        + " The session is open and configured, so this is NOT a camera2 setup"
                        + " problem: check macOS camera permission and whether another app has"
                        + " the camera. Run with MACAPK_CAMERA_TRACE=1 for the native log.");
            }

            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        CameraBackend.trace("frame pump stopped after " + seen + " delivered frame(s)");
    }

    /**
     * One native frame became available. Notify the readers first (so an app
     * whose onImageAvailable runs on a different Handler than its
     * CaptureCallback still sees the image promptly), then the capture
     * callbacks.
     */
    private void dispatchFrame(long deliveredCount) {
        // The timestamp camera2 hands to onCaptureStarted. AVFoundation's
        // CMSampleBuffer PTS and System.nanoTime() are both derived from
        // mach_absolute_time on macOS, so this is the SAME monotonic domain as
        // the frame's own presentation timestamp -- it is the instant the pump
        // OBSERVED the frame, later than exposure by up to one poll interval,
        // not a different clock and not a fabricated value.
        //
        // The exact per-frame PTS is available where apps actually read it:
        // Image.getTimestamp(), which comes straight from the CMSampleBuffer.
        // camera2 reports TIMESTAMP_SOURCE_UNKNOWN for this device, whose whole
        // meaning is "monotonic, but not comparable with other subsystems".
        final long timestampNs = System.nanoTime();

        for (ImageReader r : mReaders) {
            r.macapkOnCameraFrame();
        }

        // SurfaceTexture outputs: publish the frame's metadata; the consumer's
        // own latch (updateTexImage / TextureView draw) pulls the pixels. The
        // PTS is the newest delivered frame's, straight from the native layer;
        // if that read races a stop() and comes back 0, the observation
        // instant above is the honest same-domain fallback. The latch replaces
        // it with the copied frame's exact PTS anyway.
        List<SurfaceTexture> textures = mSurfaceTextures;
        if (!textures.isEmpty()) {
            long pts = CameraBridge.latestFrameTimestamp(mHandle);
            if (pts == 0) pts = timestampNs;
            Map<SurfaceTexture, float[]> transforms = mTextureTransforms;
            for (SurfaceTexture st : textures) {
                st.macapkQueueFrame(pts, transforms.getOrDefault(st, MTX_FLIP_V), 0);
            }
        }

        CaptureRequest request;
        CaptureCallback callback;
        CameraDispatch dispatch;
        int sequenceId;
        boolean endsSequence = false;

        synchronized (mLock) {
            OneShot shot = mOneShots.poll();
            if (shot != null) {
                request = shot.request;
                callback = shot.callback;
                dispatch = shot.dispatch;
                sequenceId = shot.sequenceId;
                endsSequence = shot.last;
            } else if (mRepeatingBurst != null && !mRepeatingBurst.isEmpty()) {
                request = mRepeatingBurst.get(mRepeatingBurstIndex % mRepeatingBurst.size());
                mRepeatingBurstIndex++;
                callback = mRepeatingCallback;
                dispatch = mRepeatingDispatch;
                sequenceId = mRepeatingSequenceId;
            } else if (mRepeatingRequest != null) {
                request = mRepeatingRequest;
                callback = mRepeatingCallback;
                dispatch = mRepeatingDispatch;
                sequenceId = mRepeatingSequenceId;
            } else {
                request = null;
                callback = null;
                dispatch = null;
                sequenceId = 0;
            }
        }

        if (callback == null || dispatch == null || request == null) {
            // No callback registered for this frame. The readers were still
            // notified above, which is what a request with an ImageReader
            // target and a null CaptureCallback is supposed to produce.
            stopPumpIfIdle();
            return;
        }

        final CaptureRequest req = request;
        final CaptureCallback cb = callback;
        final int seq = sequenceId;
        final boolean last = endsSequence;

        dispatch.run(() -> cb.onCaptureStarted(this, req, timestampNs, deliveredCount));

        // The result carries only what we measured. SENSOR_TIMESTAMP is the
        // observation instant described above; exposure time, sensitivity and
        // the 3A states are absent because AVFoundation does not report them
        // per frame through this path, and a plausible-looking fabricated value
        // is worse than a null an app can test for. CameraCharacteristics
        // .getAvailableCaptureResultKeys() advertises exactly this one key.
        Map<CaptureResult.Key<?>, Object> results = new HashMap<>();
        results.put(CaptureResult.SENSOR_TIMESTAMP, Long.valueOf(timestampNs));
        final TotalCaptureResult result = new TotalCaptureResult(results, req, deliveredCount,
                mDevice.getId(), seq);

        dispatch.run(() -> cb.onCaptureCompleted(this, req, result));

        if (last) {
            dispatch.run(() -> cb.onCaptureSequenceCompleted(this, seq, deliveredCount));
            stopPumpIfIdle();
        }
    }
}
