// android.hardware.camera2.CameraDeviceImpl -- the concrete CameraDevice handed
// to an app by CameraManager.openCamera. Package-private, mirroring AOSP, where
// the app-visible type is the abstract CameraDevice and the implementation is
// android.hardware.camera2.impl.CameraDeviceImpl.
//
// This is the object that owns a native AVFoundation capture session
// (com.macapk.loader.CameraBridge) for its whole lifetime: opened here, handed
// to at most one CameraCaptureSessionImpl at a time, closed here.
//
// ---------------------------------------------------------------------------
// WHY OPEN IS ASYNCHRONOUS, AND WHY IT MUST BE
// ---------------------------------------------------------------------------
// AOSP's openCamera is documented asynchronous: it returns immediately and the
// device arrives through StateCallback.onOpened. We need that latitude for a
// reason AOSP does not have -- macOS TCC. An Android app has already shown its
// own permission dialog before it calls openCamera (Activity.requestPermissions
// at launch), but macOS has not yet been asked anything, so the system prompt
// has to be raised HERE and the open has to wait for the user to answer it.
//
// That wait happens on a short-lived background thread, never on the caller's
// thread, for two separate reasons -- either one alone would be sufficient:
//   * The caller is normally the app's main thread. Blocking it for the
//     lifetime of a permission dialog is a 30-second freeze.
//   * The macOS TCC panel is presented by the system on behalf of this process
//     and needs the AppKit main run loop to keep pumping. Blocking the main
//     thread waiting for an answer to a dialog that cannot appear until the
//     main thread is free is a deadlock, not a delay.
//
// ---------------------------------------------------------------------------
// WHAT AN APP SEES WHEN IT CANNOT HAVE THE CAMERA
// ---------------------------------------------------------------------------
//   already denied  -> SecurityException from CameraManager.openCamera,
//                      synchronously. That is what real Android throws when an
//                      app calls openCamera without the CAMERA permission, and
//                      a macOS TCC denial is the same fact.
//   denied at the   -> StateCallback.onError(device, ERROR_CAMERA_DISABLED).
//   prompt             AOSP's ERROR_CAMERA_DISABLED is "the camera device could
//                      not be opened due to a device policy", which is the
//                      closest true statement about a TCC refusal.
//   open failed     -> StateCallback.onError(device, ERROR_CAMERA_DEVICE).
//
// In every one of those, onError is handed a REAL CameraDevice, never null. Its
// camera parameter is @NonNull in AOSP and its javadoc tells apps to call
// camera.close() from inside the callback, so a null would break the documented
// cleanup idiom inside the app's own code. That is why this object is
// constructed BEFORE the open is attempted.
package android.hardware.camera2;

import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.view.Surface;

import com.macapk.loader.CameraBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class CameraDeviceImpl extends CameraDevice {

    private final String mCameraId;
    private final int mDeviceIndex;
    private final CameraCharacteristics mCharacteristics;
    private final CameraDevice.StateCallback mStateCallback;
    private final CameraDispatch mStateDispatch;

    private final Object mLock = new Object();
    private long mHandle = CameraBridge.NO_SESSION;
    private boolean mClosed;
    private CameraCaptureSessionImpl mSession;
    private int mAudioRestriction = AUDIO_RESTRICTION_NONE;
    private static volatile boolean sWarnedAudioRestriction;

    private CameraDeviceImpl(String cameraId, int deviceIndex,
            CameraCharacteristics characteristics,
            CameraDevice.StateCallback callback, CameraDispatch dispatch) {
        mCameraId = cameraId;
        mDeviceIndex = deviceIndex;
        mCharacteristics = characteristics;
        mStateCallback = callback;
        mStateDispatch = dispatch;
    }

    /**
     * Start an open. Returns immediately; the app hears the outcome through its
     * StateCallback. The caller (CameraManager.openCameraImpl) has already
     * validated the id and the callback and has already refused a decided
     * denial, so everything that can still fail is asynchronous.
     */
    static void openAsync(String cameraId, CameraDevice.StateCallback callback,
            CameraDispatch dispatch) {
        int index = CameraBackend.deviceIndexForId(cameraId);
        CameraCharacteristics chars = CameraBackend.characteristics(cameraId);
        final CameraDeviceImpl device =
                new CameraDeviceImpl(cameraId, index, chars, callback, dispatch);

        Thread t = new Thread(device::doOpen, "macapk-camera-open-" + cameraId);
        // Daemon: a permission prompt nobody answers must not hold the JVM open
        // at shutdown.
        t.setDaemon(true);
        t.start();
    }

    private void doOpen() {
        CameraBackend.trace("openCamera(" + mCameraId + ") index=" + mDeviceIndex
                + " auth=" + CameraBackend.authStatus());

        if (!CameraBackend.awaitAuthorization()) {
            CameraBackend.warn("openCamera(" + mCameraId
                    + ") FAILED: macOS did not grant camera access."
                    + " Grant it in System Settings > Privacy & Security > Camera,"
                    + " then relaunch.");
            dispatchError(StateCallback.ERROR_CAMERA_DISABLED);
            return;
        }

        long handle = CameraBridge.open(mDeviceIndex);
        if (handle == CameraBridge.NO_SESSION) {
            // The native layer already printed why (no such device, denied,
            // AVFoundation refused the input). This line names the camera2-level
            // consequence so a reader of the log can connect the two.
            CameraBackend.warn("openCamera(" + mCameraId
                    + ") FAILED: the capture backend could not open device index "
                    + mDeviceIndex);
            dispatchError(StateCallback.ERROR_CAMERA_DEVICE);
            return;
        }

        boolean abandoned;
        synchronized (mLock) {
            abandoned = mClosed;
            if (!abandoned) mHandle = handle;
        }
        if (abandoned) {
            // The app called close() while the prompt was up. Nothing is owed
            // to it -- AOSP delivers no onOpened after a close -- but the native
            // session must not leak.
            CameraBridge.close(handle);
            CameraBackend.trace("openCamera(" + mCameraId
                    + ") completed after close(); native session released");
            return;
        }

        CameraBackend.trace("openCamera(" + mCameraId + ") OPENED, native geometry "
                + CameraBridge.getWidth(handle) + "x" + CameraBridge.getHeight(handle));
        mStateDispatch.run(() -> mStateCallback.onOpened(this));
    }

    private void dispatchError(int error) {
        mStateDispatch.run(() -> mStateCallback.onError(this, error));
    }

    // ---- CameraDevice ----------------------------------------------------

    @Override
    public String getId() { return mCameraId; }

    CameraCharacteristics getCharacteristics() { return mCharacteristics; }

    /** The native capture-session handle, or NO_SESSION if the open has not
     *  finished or the device is closed. */
    long handle() {
        synchronized (mLock) { return mHandle; }
    }

    /** AOSP's checkIfCameraClosedOrInError. IllegalStateException, and the exact
     *  message, because an app may be matching on it. */
    private void checkNotClosed() {
        synchronized (mLock) {
            if (mClosed) {
                throw new IllegalStateException("CameraDevice was already closed");
            }
            if (mHandle == CameraBridge.NO_SESSION) {
                // Reachable only if the app kept the CameraDevice reference from
                // onError and then used it. AOSP is in "error state" here and
                // throws the same type.
                throw new IllegalStateException("CameraDevice is not open");
            }
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        checkNotClosed();
        if (templateType < TEMPLATE_PREVIEW || templateType > TEMPLATE_MANUAL) {
            throw new IllegalArgumentException("Template type " + templateType
                    + " is not supported on this device.");
        }
        // TEMPLATE_MANUAL and TEMPLATE_ZERO_SHUTTER_LAG require capabilities
        // this device does not advertise (MANUAL_SENSOR / ZSL). AOSP throws
        // IllegalArgumentException for a template the device cannot support,
        // and refusing here is what stops an app from building a manual-exposure
        // request that we would then silently ignore.
        if (templateType == TEMPLATE_MANUAL || templateType == TEMPLATE_ZERO_SHUTTER_LAG) {
            throw new IllegalArgumentException("Template type " + templateType
                    + " is not supported on this device.");
        }
        return new CaptureRequest.Builder(templateType);
    }

    @Override
    public void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        createSessionInternal(outputs, null, callback, CameraDispatch.of(handler));
    }

    /**
     * API 24. The OutputConfiguration form. AOSP declares it on CameraDevice;
     * apps that build OutputConfigurations (and CameraX, which does) call this
     * rather than the Surface-list overload.
     */
    @Override
    public void createCaptureSessionByOutputConfigurations(List<OutputConfiguration> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        createSessionInternal(surfacesOf(outputs), mirrorModesOf(outputs), callback,
                CameraDispatch.of(handler));
    }

    /**
     * API 28. The SessionConfiguration form, which carries its own Executor and
     * StateCallback.
     *
     * The base class's version used to be an empty no-op, which is the worst
     * possible answer: the app configures a session, hears nothing, and waits
     * forever with no error to report. Every path below ends in either
     * onConfigured or onConfigureFailed.
     */
    @Override
    public void createCaptureSession(SessionConfiguration config)
            throws CameraAccessException {
        if (config == null) {
            throw new IllegalArgumentException("Invalid session configuration");
        }
        CameraCaptureSession.StateCallback callback = config.getStateCallback();
        Executor executor = config.getExecutor();
        if (callback == null || executor == null) {
            throw new IllegalArgumentException(
                    "Invalid state callback or executor in the session configuration");
        }
        if (config.getSessionType() != SessionConfiguration.SESSION_REGULAR) {
            // High-speed and shared sessions are distinct HAL modes with their
            // own guaranteed stream combinations; this backend implements
            // neither, and StreamConfigurationMap.getHighSpeedVideoSizes()
            // already reports no high-speed sizes. Failing loudly beats
            // configuring a regular session behind the app's back and letting it
            // wonder why its 120fps request runs at 30.
            CameraBackend.warn("createCaptureSession: session type "
                    + config.getSessionType()
                    + " is not supported by this backend (only SESSION_REGULAR)");
            CameraDispatch d = CameraDispatch.of(executor);
            d.run(() -> callback.onConfigureFailed(null));
            return;
        }
        createSessionInternal(surfacesOf(config.getOutputConfigurations()),
                mirrorModesOf(config.getOutputConfigurations()), callback,
                CameraDispatch.of(executor));
    }

    private static List<Surface> surfacesOf(List<OutputConfiguration> configs) {
        if (configs == null) return null;
        List<Surface> out = new ArrayList<>(configs.size());
        for (OutputConfiguration c : configs) {
            if (c != null) out.addAll(surfacesOf(c));
        }
        return out;
    }

    private static List<Surface> surfacesOf(OutputConfiguration c) {
        // getSurfaces() covers the shared-surface form; getSurface() is the
        // common single-surface one and is already the head of that list.
        List<Surface> shared = c.getSurfaces();
        if (shared != null && !shared.isEmpty()) return shared;
        return c.getSurface() != null
                ? java.util.Collections.singletonList(c.getSurface())
                : java.util.Collections.emptyList();
    }

    /**
     * Each output Surface's OutputConfiguration mirror mode (API 33), so the
     * session can honour it; outputs not listed get MIRROR_MODE_AUTO, the
     * default. A per-surface mode (API 36, shared surfaces) wins over the
     * configuration-wide one when it was set. As AOSP's setMirrorMode javadoc
     * says, the mode reaches SurfaceTexture consumers through the transform
     * matrix and has no effect on ImageReader outputs.
     */
    private static Map<Surface, Integer> mirrorModesOf(List<OutputConfiguration> configs) {
        Map<Surface, Integer> modes = new HashMap<>();
        if (configs == null) return modes;
        for (OutputConfiguration c : configs) {
            if (c == null) continue;
            for (Surface s : surfacesOf(c)) {
                int perSurface = c.getMirrorMode(s);
                modes.put(s, perSurface != OutputConfiguration.MIRROR_MODE_AUTO
                        ? perSurface : c.getMirrorMode());
            }
        }
        return modes;
    }

    private void createSessionInternal(List<Surface> outputs, Map<Surface, Integer> mirrorModes,
            CameraCaptureSession.StateCallback callback, CameraDispatch dispatch)
            throws CameraAccessException {
        checkNotClosed();
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (outputs == null || outputs.isEmpty()) {
            // AOSP CameraDeviceImpl.createCaptureSession, verbatim message.
            throw new IllegalArgumentException(
                    "Output surface list must not be null and the size must be at least 1");
        }
        for (Surface s : outputs) {
            if (s == null) {
                throw new IllegalArgumentException("Surface list must not contain nulls");
            }
        }

        CameraCaptureSessionImpl previous;
        CameraCaptureSessionImpl created;
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            previous = mSession;
            created = new CameraCaptureSessionImpl(this, handle, outputs, mirrorModes,
                    callback, dispatch);
            mSession = created;
        }
        if (previous != null) {
            // AOSP: creating a new session closes the previous one. The app gets
            // onClosed for it, which is what its own teardown is written
            // against.
            CameraBackend.trace("createCaptureSession replaces the previous session");
            previous.closeInternal();
        }
        created.configureAsync();
    }

    /** Called by the session when it closes itself, so the device does not keep
     *  handing out a dead one. */
    void forgetSession(CameraCaptureSessionImpl session) {
        synchronized (mLock) {
            if (mSession == session) mSession = null;
        }
    }

    // ---- The API 23-31 device entry points ------------------------------
    //
    // Each of these is answered from what this backend can actually do, and
    // each throw below is the exception AOSP's own javadoc names for that
    // condition -- not an invented failure. The AVFoundation path advertises no
    // reprocessing, no constrained-high-speed and no logical multi-camera, so
    // an app that asks for one of those gets the same rejection it would get
    // from a device whose HAL lacks the capability.

    /**
     * API 28. AOSP (impl/CameraDeviceImpl.java:1076) rejects a physical id equal
     * to the logical id with IllegalStateException, then lets the camera service
     * validate the rest.
     *
     * An EMPTY set is exactly the single-argument overload, so it delegates. A
     * NON-empty set names a physical camera, and
     * CameraCharacteristics.getPhysicalCameraIds() is empty for every camera
     * this backend exposes, so no id can be valid -- IllegalArgumentException is
     * what the camera service raises for an unknown physical id, and it is the
     * honest answer rather than handing back a builder whose per-physical
     * settings would be silently dropped.
     */
    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType,
            java.util.Set<String> physicalCameraIdSet) throws CameraAccessException {
        checkNotClosed();
        if (physicalCameraIdSet == null) {
            throw new IllegalArgumentException("physicalCameraIdSet must not be null");
        }
        for (String physicalId : physicalCameraIdSet) {
            if (java.util.Objects.equals(physicalId, getId())) {
                throw new IllegalStateException("Physical id matches the logical id!");
            }
        }
        if (!physicalCameraIdSet.isEmpty()) {
            java.util.Set<String> known = mCharacteristics.getPhysicalCameraIds();
            for (String physicalId : physicalCameraIdSet) {
                if (!known.contains(physicalId)) {
                    throw new IllegalArgumentException(
                            "Physical camera id " + physicalId + " is not part of camera "
                                    + getId());
                }
            }
        }
        return createCaptureRequest(templateType);
    }

    /**
     * API 23. AOSP javadoc: IllegalArgumentException "if the inputResult is
     * null or not obtained from a CaptureRequest that was created for a
     * reprocessable capture session".
     *
     * This backend never creates a reprocessable session
     * (CameraCaptureSessionImpl.isReprocessable() is false and
     * createReprocessableCaptureSession below refuses), so EVERY result reaching
     * here fails that condition and AOSP's own IllegalArgumentException is the
     * correct outcome.
     */
    @Override
    public CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult inputResult)
            throws CameraAccessException {
        checkNotClosed();
        if (inputResult == null) {
            throw new IllegalArgumentException("inputResult must not be null");
        }
        throw new IllegalArgumentException(
                "inputResult was not obtained from a reprocessable capture session; camera "
                        + getId() + " does not advertise the YUV_REPROCESSING or"
                        + " PRIVATE_REPROCESSING capability");
    }

    /**
     * API 23. AOSP javadoc: IllegalArgumentException "if the input configuration
     * is null or not supported, the output configurations are empty, or the
     * input configuration is not supported by the camera device".
     *
     * CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP reports no input
     * formats for this backend (StreamConfigurationMap.getInputFormats() is
     * empty), so no InputConfiguration is supported and the throw is AOSP's.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void createReprocessableCaptureSession(
            android.hardware.camera2.params.InputConfiguration inputConfig,
            List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        checkNotClosed();
        if (inputConfig == null) {
            throw new IllegalArgumentException("inputConfig must not be null");
        }
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("Output surface list must not be empty");
        }
        throw new IllegalArgumentException("Camera " + getId()
                + " does not support reprocessing: the input configuration " + inputConfig
                + " is not one of its supported input formats");
    }

    /** API 24. The OutputConfiguration form of the method above, refused for
     *  the same reason and with the same exception. */
    @Override
    @SuppressWarnings("deprecation")
    public void createReprocessableCaptureSessionByConfigurations(
            android.hardware.camera2.params.InputConfiguration inputConfig,
            List<OutputConfiguration> outputs, CameraCaptureSession.StateCallback callback,
            Handler handler) throws CameraAccessException {
        checkNotClosed();
        if (inputConfig == null) {
            throw new IllegalArgumentException("inputConfig must not be null");
        }
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("Output configuration list must not be empty");
        }
        throw new IllegalArgumentException("Camera " + getId()
                + " does not support reprocessing: the input configuration " + inputConfig
                + " is not one of its supported input formats");
    }

    /**
     * API 23. AOSP (impl/CameraDeviceImpl.java:827) checks the output list size
     * first -- "must not be null and the size must be no more than 2" -- and its
     * javadoc names IllegalArgumentException "if the camera device doesn't
     * support high speed video capability".
     *
     * REQUEST_AVAILABLE_CAPABILITIES does not include
     * CONSTRAINED_HIGH_SPEED_VIDEO for this backend: AVFoundation exposes
     * high-frame-rate formats per device, but nothing in this path configures
     * one, and a session that reported onConfigured then delivered 30fps would
     * silently ruin the app's slow-motion recording. So the capability is
     * refused rather than approximated.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        checkNotClosed();
        if (outputs == null || outputs.size() == 0 || outputs.size() > 2) {
            throw new IllegalArgumentException(
                    "Output surface list must not be null and the size must be no more than 2");
        }
        throw new IllegalArgumentException("Camera " + getId()
                + " does not support the CONSTRAINED_HIGH_SPEED_VIDEO capability");
    }

    /**
     * API 31. AOSP's CameraDevice default body is
     * UnsupportedOperationException("No default implementation"), and its
     * javadoc names UnsupportedOperationException "if the camera device does not
     * support camera extensions".
     *
     * CameraExtensionCharacteristics.getSupportedExtensions() is empty here, so
     * that documented condition holds and the throw is the AOSP-correct answer.
     */
    @Override
    public void createExtensionSession(
            android.hardware.camera2.params.ExtensionSessionConfiguration extensionConfiguration)
            throws CameraAccessException {
        checkNotClosed();
        if (extensionConfiguration == null) {
            throw new IllegalArgumentException("extensionConfiguration must not be null");
        }
        throw new UnsupportedOperationException(
                "Camera " + getId() + " supports no camera extensions");
    }

    /**
     * API 28. Whether createCaptureSession with this configuration would
     * succeed, asked without configuring.
     *
     * This is answered from THIS class's own configuration path rather than
     * guessed: createCaptureSession(SessionConfiguration) flattens the
     * OutputConfigurations to Surfaces and calls createSessionInternal, which
     * serves only an ordinary (SESSION_REGULAR) session with at least one
     * output and no reprocessing input. Each false below is one of those
     * preconditions failing, so the prediction and the behaviour cannot drift
     * apart.
     *
     * It deliberately does NOT try to predict per-Surface success. A Surface
     * this backend cannot attach to fails at configure time with
     * onConfigureFailed, which is the same thing a real device does when the
     * HAL rejects a stream late.
     */
    @Override
    public boolean isSessionConfigurationSupported(
            android.hardware.camera2.params.SessionConfiguration sessionConfig)
            throws CameraAccessException {
        checkNotClosed();
        if (sessionConfig == null) {
            throw new IllegalArgumentException("sessionConfig must not be null");
        }
        if (sessionConfig.getSessionType()
                != android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR) {
            return false;
        }
        if (sessionConfig.getInputConfiguration() != null) {
            return false;
        }
        List<OutputConfiguration> configs = sessionConfig.getOutputConfigurations();
        return configs != null && !configs.isEmpty();
    }

    /**
     * API 30. Mute system sound / vibration while the camera is open.
     *
     * The mode is validated and remembered, and getCameraAudioRestriction below
     * reports it back, so the API's own round-trip contract holds. What macOS
     * does NOT provide is the effect: there is no supported way for one
     * application to silence another application's or the system's sounds, so
     * nothing is actually muted. That gap is announced on the log rather than
     * left silent -- and it is announced rather than thrown, because AOSP
     * documents no "unsupported" outcome for this method, so throwing would
     * invent a failure a real device never produces and would break an app that
     * calls it unconditionally before recording video.
     */
    @Override
    public void setCameraAudioRestriction(int mode) throws CameraAccessException {
        checkNotClosed();
        if (mode != AUDIO_RESTRICTION_NONE && mode != AUDIO_RESTRICTION_VIBRATION
                && mode != AUDIO_RESTRICTION_VIBRATION_SOUND) {
            throw new IllegalArgumentException("Invalid audio restriction mode: " + mode);
        }
        synchronized (mLock) {
            mAudioRestriction = mode;
        }
        if (mode != AUDIO_RESTRICTION_NONE && !sWarnedAudioRestriction) {
            sWarnedAudioRestriction = true;
            CameraBackend.warn("setCameraAudioRestriction(" + mode
                    + ") recorded, but macOS provides no way to mute system sounds or"
                    + " vibration on another application's behalf, so nothing is muted.");
        }
    }

    /** API 30. The mode last passed to setCameraAudioRestriction, defaulting to
     *  AUDIO_RESTRICTION_NONE as it does on a device where nothing set one. */
    @Override
    public int getCameraAudioRestriction() throws CameraAccessException {
        checkNotClosed();
        synchronized (mLock) {
            return mAudioRestriction;
        }
    }

    @Override
    public void close() {
        long handle;
        CameraCaptureSessionImpl session;
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
            handle = mHandle;
            mHandle = CameraBridge.NO_SESSION;
            session = mSession;
            mSession = null;
        }
        if (session != null) session.closeInternal();
        if (handle != CameraBridge.NO_SESSION) {
            CameraBridge.close(handle);
            CameraBackend.trace("close(" + mCameraId + ") released the native session");
        }
        mStateDispatch.run(() -> mStateCallback.onClosed(this));
    }
}
