// android.hardware.camera2.CameraManager -- the camera2 entry point.
// Mac.apk has no camera, which in AOSP terms is exactly the
// CameraManagerGlobal.sCameraServiceDisabled state, and AOSP has a precise
// contract for it: getCameraIdList returns an empty array, and every id-taking
// entry point throws IllegalArgumentException("No cameras available on
// device") SYNCHRONOUSLY, before any StateCallback is constructed or touched
// (CameraManager.java:770-771 getCameraCharacteristics, :1503-1504
// openCameraImpl, :1552-1553 setTorchMode). Reproducing that exactly is what
// lets camera apps probe-and-degrade the same way they do on a real cameraless
// device.
//
// The "no cameras" decision itself now lives in ONE place -- the package-private
// CameraBackend -- so that wiring a real AVFoundation capture backend is a
// change to that class rather than an audit of every throw site in this
// package. The AOSP citations below are preserved because they are why each
// site throws what it throws.
package android.hardware.camera2;

import android.os.Handler;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;

public final class CameraManager {
    public CameraManager() {}

    public String[] getCameraIdList() throws CameraAccessException {
        return CameraBackend.cameraIdList();
    }

    public String[] getCameraIdListNoLazy() throws CameraAccessException {
        return CameraBackend.cameraIdList();
    }

    /**
     * The combinations of camera ids that may have sessions configured
     * concurrently. API 30.
     *
     * AOSP's own javadoc (CameraManager.java:389-391) states "The set of
     * combinations will be empty if no such combinations are supported by the
     * camera subsystem", so an empty set is the documented answer here rather
     * than a stub -- and it is the answer a real single-camera device gives
     * too. Returning a fabricated non-empty set would send an app into
     * concurrent-session setup that then fails deeper.
     */
    public Set<Set<String>> getConcurrentCameraIds() throws CameraAccessException {
        return Collections.emptySet();
    }

    public CameraCharacteristics getCameraCharacteristics(String cameraId)
            throws CameraAccessException {
        // AOSP javadoc (CameraManager.java:725-727): "@throws
        // IllegalArgumentException if the cameraId does not match any known
        // camera device. @throws CameraAccessException if the camera device
        // has been disconnected." Those are two different conditions, and ours
        // is the first: no camera ever existed. Throwing the CHECKED
        // CameraAccessException here was strictly wrong -- app code is
        // compiler-FORCED to catch that one, so we were silently swallowing a
        // failure that crashes on a real device, hiding the bug instead of
        // reproducing it.
        CameraBackend.requireKnownCameraId(cameraId);
        // Built from the AVFoundation device behind this id: facing, sensor
        // orientation, the advertised sizes and frame-rate ranges, and the
        // StreamConfigurationMap. Cached per device, so repeated calls (which
        // apps make on every preview-size recalculation) cost nothing.
        return CameraBackend.characteristics(cameraId);
    }

    public void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler)
            throws CameraAccessException {
        // AOSP resolves the Handler BEFORE anything else, so an app that passes
        // a null Handler from a thread with no Looper fails with
        // IllegalArgumentException rather than being silently given a callback
        // on some arbitrary thread (impl/CameraDeviceImpl.checkHandler).
        openCameraImpl(cameraId, callback, CameraDispatch.of(handler));
    }

    public void openCamera(String cameraId, Executor executor, CameraDevice.StateCallback callback)
            throws CameraAccessException {
        // AOSP's Executor overload rejects a null executor before anything
        // else (CameraManager.java:1348-1350).
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        openCameraImpl(cameraId, callback, CameraDispatch.of(executor));
    }

    /**
     * Mirrors AOSP openCameraImpl (CameraManager.java:1493-1508): argument
     * checks and the "no such camera" bail-out are SYNCHRONOUS, then the open
     * itself is asynchronous and reports through the StateCallback.
     *
     * The synchronous half matters as much as the asynchronous one. On a device
     * with no camera at all AOSP never reaches a StateCallback, because
     * openCameraDeviceUserAsync's first statement is
     * getCameraCharacteristics(cameraId) and that throws first -- and
     * StateCallback.onError's camera parameter is @NonNull with a javadoc that
     * tells apps to call camera.close() from it, so inventing an onError(null)
     * would make the AOSP-documented cleanup idiom NPE inside the app's own
     * callback. Hence: throw when there is nothing to open, and hand
     * CameraDeviceImpl a real device object for every failure that happens
     * after there IS one.
     *
     * PERMISSION. A macOS TCC denial is the same fact as a missing Android
     * CAMERA permission, and real Android answers that with a SecurityException
     * from openCamera -- thrown by the camera service before any callback
     * exists. So an already-denied camera throws here, synchronously, with a
     * message naming where the user can change it. A NOT-yet-decided one goes
     * asynchronous, because the macOS prompt has to be raised and answered, and
     * blocking this thread (usually the app's main thread, and the very thread
     * AppKit needs to present the panel) would deadlock rather than delay.
     */
    private void openCameraImpl(String cameraId, CameraDevice.StateCallback callback,
            CameraDispatch dispatch) {
        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback was null");
        }
        CameraBackend.requireKnownCameraId(cameraId);

        // The Android CAMERA permission itself, where the host asks for it
        // (SpecialAccessStore.allPermsAsk): the camera service checks it
        // before the device's own privacy switch, with this same exception.
        if (Boolean.FALSE.equals(com.macapk.loader.SpecialAccessStore.holds(
                "android.permission.CAMERA"))) {
            CameraBackend.warn("openCamera(" + cameraId
                    + ") refused: the app does not hold android.permission.CAMERA");
            throw new SecurityException("Lacking privileges to access camera service");
        }
        if (CameraBackend.authDecidedDenied()) {
            CameraBackend.warn("openCamera(" + cameraId
                    + ") refused: macOS camera access is denied for this application."
                    + " Enable it in System Settings > Privacy & Security > Camera.");
            throw new SecurityException("Lacking privileges to access camera service");
        }

        CameraDeviceImpl.openAsync(cameraId, callback, dispatch);
    }

    /**
     * Query a camera's vendor-extension capabilities. API 31.
     *
     * AOSP's first statement is getCameraCharacteristics(cameraId)
     * (CameraManager.java:858-861), so an unknown id fails there before any
     * CameraExtensionCharacteristics is constructed. Delegating to our own
     * getCameraCharacteristics reproduces that ordering exactly, including the
     * IllegalArgumentException type and message, instead of inventing a second
     * copy of the id check that could drift from it.
     */
    public CameraExtensionCharacteristics getCameraExtensionCharacteristics(String cameraId)
            throws CameraAccessException {
        getCameraCharacteristics(cameraId);
        // Unreachable while there are no cameras. Written as the real return so
        // that a capture backend does not have to rewrite this method -- only
        // make getCameraCharacteristics succeed.
        return new CameraExtensionCharacteristics();
    }

    /**
     * Whether a CameraDevice.CameraDeviceSetup can be built for this id.
     * API 35.
     *
     * AOSP checks null first, then bails out if sCameraServiceDisabled OR the
     * id is absent from getCameraIdList(), throwing IllegalArgumentException
     * "Camera ID '<id>' not available on device." (CameraManager.java:961-975).
     * Mac.apk is permanently in that first state, so this reaches the identical
     * throw by the identical route.
     */
    public boolean isCameraDeviceSetupSupported(String cameraId) throws CameraAccessException {
        CameraBackend.requireKnownCameraIdNamed(cameraId);
        // Reached only once a camera exists. AOSP then asks the camera whether
        // INFO_SESSION_CONFIGURATION_QUERY_VERSION > 34; a backend that cannot
        // answer that honestly must keep returning false here.
        return false;
    }

    /**
     * Build a CameraDeviceSetup for this id. API 35.
     *
     * AOSP calls isCameraDeviceSetupSupported first and lets its argument
     * checking throw ("isCameraDeviceSetup does all the error checking we
     * need", CameraManager.java:913-921), then raises
     * UnsupportedOperationException when the camera exists but lacks setup
     * support. Both paths are kept, so an app sees the same exception here that
     * it would on a real device without the feature.
     */
    public CameraDevice.CameraDeviceSetup getCameraDeviceSetup(String cameraId)
            throws CameraAccessException {
        if (!isCameraDeviceSetupSupported(cameraId)) {
            throw new UnsupportedOperationException(
                    "CameraDeviceSetup is not supported for Camera ID: " + cameraId);
        }
        // Unreachable: isCameraDeviceSetupSupported either threw or returned
        // false. There is no CameraDeviceSetupImpl to hand back, and a stub
        // subclass would be a fabricated capability, so this stays a throw
        // until a backend supplies a real implementation.
        throw new UnsupportedOperationException(
                "CameraDeviceSetup is not supported for Camera ID: " + cameraId);
    }

    public void registerAvailabilityCallback(AvailabilityCallback callback, Handler handler) {}
    public void registerAvailabilityCallback(Executor executor, AvailabilityCallback callback) {}
    public void unregisterAvailabilityCallback(AvailabilityCallback callback) {}

    public void registerTorchCallback(TorchCallback callback, Handler handler) {}
    public void registerTorchCallback(Executor executor, TorchCallback callback) {}
    public void unregisterTorchCallback(TorchCallback callback) {}

    public void setTorchMode(String cameraId, boolean enabled) throws CameraAccessException {
        // AOSP setTorchMode (CameraManager.java:1552-1554) checks
        // sCameraServiceDisabled first and throws the UNCHECKED
        // IllegalArgumentException; CameraAccessException is reserved for a
        // camera that exists but cannot be reached.
        CameraBackend.requireKnownCameraId(cameraId);
    }

    /**
     * API 33. The torch's CURRENT brightness level, 1..FLASH_TORCH_STRENGTH_MAX_LEVEL.
     *
     * AOSP (CameraManager.java getTorchStrengthLevel) throws
     * IllegalArgumentException("No camera available on device.") first when the
     * camera service is disabled, and its javadoc adds IllegalArgumentException
     * "if cameraId was null, or if the camera device doesn't have a flash unit".
     *
     * Both throws are reachable and both are honest here. Every camera this
     * backend exposes sets FLASH_INFO_AVAILABLE to false
     * (CameraBackend.java:246) because AVFoundation exposes no torch control on
     * macOS -- AVCaptureDevice.torchMode is an iOS-only API -- so a camera that
     * exists still has no flash unit and takes the second throw. Returning a
     * fabricated level would tell an app the torch is on at brightness N when
     * nothing is lit.
     */
    public int getTorchStrengthLevel(String cameraId) throws CameraAccessException {
        CameraBackend.requireKnownCameraId(cameraId);
        throw new IllegalArgumentException(
                "Camera " + cameraId + " does not have a flash unit");
    }

    /**
     * API 33. Turn the torch on at a specific brightness level.
     *
     * Same contract and same reasoning as getTorchStrengthLevel above: AOSP's
     * javadoc names IllegalArgumentException "if cameraId was null, or if the
     * camera device doesn't have a flash unit or if the torch strength level is
     * not within the valid range". There is no flash unit on any camera here.
     */
    public void turnOnTorchWithStrengthLevel(String cameraId, int torchStrength)
            throws CameraAccessException {
        CameraBackend.requireKnownCameraId(cameraId);
        throw new IllegalArgumentException(
                "Camera " + cameraId + " does not have a flash unit");
    }

    /**
     * API 30. Whether these cameras could all run the given SessionConfigurations
     * AT THE SAME TIME.
     *
     * AOSP's javadoc: IllegalArgumentException "if the set of camera devices
     * queried is not a subset of those returned by getConcurrentCameraIds".
     * getConcurrentCameraIds() above is empty and honestly so -- concurrent
     * operation is a guarantee the HAL makes about specific id combinations,
     * and this backend makes none -- so NO non-empty set is a subset of it and
     * every call lands on AOSP's own throw. An empty map is rejected too,
     * because there is nothing to answer about.
     */
    public boolean isConcurrentSessionConfigurationSupported(
            java.util.Map<String, android.hardware.camera2.params.SessionConfiguration>
                    cameraIdAndSessionConfig) throws CameraAccessException {
        if (cameraIdAndSessionConfig == null) {
            throw new IllegalArgumentException("cameraIdAndSessionConfig was null");
        }
        if (cameraIdAndSessionConfig.isEmpty()) {
            throw new IllegalArgumentException("cameraIdAndSessionConfig was empty");
        }
        throw new IllegalArgumentException(
                "Set of camera ids is not a subset of camera ids that can be configured"
                        + " concurrently");
    }

    // AOSP's AvailabilityCallback/TorchCallback are abstract classes with
    // empty default methods (apps override only what they need).
    public static abstract class AvailabilityCallback {
        public void onCameraAvailable(String cameraId) {}
        public void onCameraUnavailable(String cameraId) {}
        public void onCameraAccessPrioritiesChanged() {}

        /** API 29. A PHYSICAL camera of a logical multi-camera became usable
         *  again. Empty default in AOSP (CameraManager.java:1929), and never
         *  invoked here: getPhysicalCameraIds() is empty for every camera this
         *  backend exposes, so there is no physical camera whose availability
         *  could change. */
        public void onPhysicalCameraAvailable(String cameraId, String physicalCameraId) {}

        /** API 29. The counterpart of the above. Empty default in AOSP
         *  (CameraManager.java:1993). */
        public void onPhysicalCameraUnavailable(String cameraId, String physicalCameraId) {}
    }

    public static abstract class TorchCallback {
        public void onTorchModeUnavailable(String cameraId) {}
        public void onTorchModeChanged(String cameraId, boolean enabled) {}

        /** API 33. The torch's brightness level changed. Empty default in AOSP
         *  (CameraManager.java:2108), and never invoked here: no camera this
         *  backend exposes has a flash unit, so no torch strength exists to
         *  change. */
        public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel) {}
    }
}
