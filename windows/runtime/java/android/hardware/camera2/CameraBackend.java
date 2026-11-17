// android.hardware.camera2.CameraBackend -- NOT an AOSP class, and deliberately
// package-private so it can never appear in the API surface an app resolves
// against.
//
// WHY IT EXISTS. This is the ONE place that answers "what cameras does this
// device have, and what can they do". Before a capture backend existed the
// answer was a hardcoded "none"; every "there is no camera" reply used to be
// written at the site that needed it, which meant whoever wired capture up had
// to find a dozen scattered `return emptyList()` / `throw` sites and decide,
// one at a time, whether each was a deliberate device-absent answer or an
// unfinished stub. That audit is the expensive part and getting it wrong is
// silent, so the decision is made HERE, once, and the public API classes in
// this package delegate to it.
//
// WHAT IT MIRRORS. AOSP's single point of truth for the same state is
// CameraManagerGlobal.sCameraServiceDisabled
// (frameworks/base/core/java/android/hardware/camera2/CameraManager.java, main
// branch). When that flag is set AOSP's getCameraIdList() returns an empty
// array (L2596) and every id-taking entry point throws IllegalArgumentException
// SYNCHRONOUSLY, before any callback object is constructed or touched:
//
//   L770-771   getCameraCharacteristics  -> "No cameras available on device"
//   L1503-1504 openCameraImpl            -> "No cameras available on device"
//   L1552-1553 setTorchMode              -> "No cameras available on device"
//   L967-971   isCameraDeviceSetupSupported
//                                        -> "Camera ID '<id>' not available on device."
//
// Mac.apk is in exactly that state whenever AVFoundation reports no capture
// device, so the types and message text below are copied from those sites: an
// app cannot tell us apart from a real device whose camera service is
// unavailable, which is the whole point.
//
// ---------------------------------------------------------------------------
// THE ID MAPPING: index, not uniqueID
// ---------------------------------------------------------------------------
// The camera id string is the DEVICE INDEX ("0", "1", ...), not AVFoundation's
// uniqueID. Two reasons, both load-bearing:
//   * AOSP guarantees the built-in cameras are ids "0" and "1" (CameraManager
//     getCameraIdList javadoc: "The camera ids of the built-in cameras are
//     0 and 1"), and a large amount of real app code hardcodes "0".
//   * The whole native API below camera2 is index-keyed
//     (CameraBridge.open(int), getDeviceFacing(int), ...), so index -> id maps
//     1:1 with no lookup table to keep coherent.
// The uniqueID is a UUID; it is kept for LOGGING only, so a trace names the
// physical device the user recognises.
//
// ---------------------------------------------------------------------------
// HONESTY
// ---------------------------------------------------------------------------
// Nothing here reports a camera that AVFoundation did not report, and nothing
// reports a capability we cannot actually deliver. An app that is handed a
// camera id and then never receives a frame fails deeper and more confusingly
// than one that is told there is no camera, so: no fabricated ids, no
// fabricated sizes, no fabricated hardware level. Everything below either comes
// from com.macapk.loader.CameraBridge or is a value whose derivation is
// explained at the site.
package android.hardware.camera2;

import android.graphics.Rect;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import com.macapk.loader.CameraBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class CameraBackend {

    private CameraBackend() { }

    // ---- Tracing ---------------------------------------------------------
    //
    // Same gate as the native layer (camera_capture.mm trace_on() reads
    // MACAPK_CAMERA_TRACE), so one env var turns on the whole subsystem's
    // narration and the Java and native lines interleave in one log. The
    // prefix differs ("[camera2]" here, "[camera]" natively) so it is always
    // obvious which layer produced a line -- that is the point of the split
    // when the owner reports "it said X".

    private static final boolean TRACE = System.getenv("MACAPK_CAMERA_TRACE") != null;

    static boolean traceEnabled() { return TRACE; }

    static void trace(String msg) {
        if (TRACE) System.err.println("[camera2] " + msg);
    }

    /** Always printed. Reserved for things that change what the app will see
     *  (a refused open, an unsupported output, a size we could not honour) --
     *  the lines that have to be in the log even when tracing is off, because
     *  they are the difference between "no camera" and "camera but no frames". */
    static void warn(String msg) {
        System.err.println("[camera2] " + msg);
    }

    // ---- Enumeration -----------------------------------------------------

    /** The ids this device exposes, one per AVFoundation capture device.
     *  Empty -- and therefore identical to AOSP's sCameraServiceDisabled answer
     *  (CameraManager.java L2596) -- when there is no camera, when the runtime
     *  was built without the capture backend, or when macOS has already denied
     *  camera access (AVCaptureDevice discovery returns nothing then). */
    static String[] cameraIdList() {
        int n = CameraBridge.getDeviceCount();
        if (n <= 0) return new String[0];
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = Integer.toString(i);
        return ids;
    }

    static boolean hasAnyCamera() {
        return CameraBridge.getDeviceCount() > 0;
    }

    /** The device index behind a camera id, or -1 if the id names no device.
     *  Re-reads the device count on every call so a camera unplugged since the
     *  app enumerated is reported as gone rather than dereferenced. */
    static int deviceIndexForId(String cameraId) {
        if (cameraId == null) return -1;
        int idx;
        try {
            idx = Integer.parseInt(cameraId);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (idx < 0) return -1;
        return idx < CameraBridge.getDeviceCount() ? idx : -1;
    }

    /** The AOSP argument check that precedes every id-taking entry point.
     *  Throws the unchecked IllegalArgumentException, never the checked
     *  CameraAccessException: AOSP reserves CameraAccessException for a camera
     *  that EXISTS but cannot be reached, and app code is compiler-forced to
     *  catch that one, so raising it here would let apps silently swallow a
     *  failure that is fatal on a real device. */
    static void requireKnownCameraId(String cameraId) {
        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        }
        if (deviceIndexForId(cameraId) < 0) {
            throw new IllegalArgumentException("No cameras available on device");
        }
    }

    /** The variant of the check whose message names the id. AOSP uses this
     *  wording at the CameraDeviceSetup entry points (CameraManager.java
     *  L967-971, L1005-1009) and the shorter wording elsewhere; both are
     *  reproduced so an app matching on the message sees what it would see on
     *  a real device. */
    static void requireKnownCameraIdNamed(String cameraId) {
        if (cameraId == null) {
            throw new IllegalArgumentException("Camera ID was null");
        }
        if (deviceIndexForId(cameraId) < 0) {
            throw new IllegalArgumentException(
                    "Camera ID '" + cameraId + "' not available on device.");
        }
    }

    // ---- Characteristics -------------------------------------------------

    // Cached per device index. A camera's capabilities do not change while it
    // is plugged in, and building the map walks every advertised format, which
    // an app may ask for once per preview-size recalculation. The uniqueID is
    // part of the key so that unplugging device 0 and plugging a different one
    // in cannot serve the old device's formats under the new device's index.
    private static final Map<String, CameraCharacteristics> sCharCache = new HashMap<>();

    static CameraCharacteristics characteristics(String cameraId) {
        int index = deviceIndexForId(cameraId);
        if (index < 0) {
            // Callers are expected to have run requireKnownCameraId first; this
            // is the belt-and-braces path so a race with a hot-unplug cannot
            // produce a half-built CameraCharacteristics.
            throw new IllegalArgumentException("No cameras available on device");
        }

        String unique = CameraBridge.getDeviceId(index);
        String key = index + "|" + (unique != null ? unique : "");
        synchronized (sCharCache) {
            CameraCharacteristics cached = sCharCache.get(key);
            if (cached != null) return cached;
        }

        CameraCharacteristics built = buildCharacteristics(cameraId, index, unique);
        synchronized (sCharCache) {
            sCharCache.put(key, built);
        }
        return built;
    }

    private static CameraCharacteristics buildCharacteristics(String cameraId, int index,
            String unique) {
        Formats fmt = formats(index);

        Map<CameraCharacteristics.Key<?>, Object> p = new LinkedHashMap<>();

        // LENS_FACING comes straight from the native layer, which reports FRONT
        // for a built-in camera (a MacBook's points at the user) and EXTERNAL
        // for a USB/UVC one -- which is what Android itself reports for a UVC
        // camera. Never BACK: there is no rear-facing camera on this hardware,
        // and claiming one would send a camera app looking for a lens that
        // cannot be opened.
        int facing = CameraBridge.getDeviceFacing(index);
        if (facing < 0) facing = CameraCharacteristics.LENS_FACING_EXTERNAL;
        p.put(CameraCharacteristics.LENS_FACING, Integer.valueOf(facing));

        // SENSOR_ORIENTATION is 0 on a Mac: the sensor does not rotate with the
        // device, so the app must apply no preview rotation. This is the value
        // apps feed into their display-rotation maths, so a wrong one shows a
        // sideways preview -- it is behaviour, not documentation.
        p.put(CameraCharacteristics.SENSOR_ORIENTATION,
                Integer.valueOf(CameraBridge.getSensorOrientation(index)));

        // INFO_SUPPORTED_HARDWARE_LEVEL = LEGACY. AOSP's own definition of
        // LEGACY is a camera "operating in backward compatibility mode ...
        // implemented on top of the older camera API, with limited
        // capabilities" -- which is a precise description of an AVFoundation
        // capture session dressed as camera2. We support preview requests into
        // YUV_420_888 and SurfaceTexture outputs and stills into a JPEG output
        // (what a LEGACY device guarantees), and nothing else: no per-frame
        // control, no meaningful per-request result metadata, no reprocessing.
        // Claiming LIMITED or FULL would promise per-request behaviour we
        // cannot honour, and an app that trusted it would fail deeper.
        p.put(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                Integer.valueOf(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY));

        // A LEGACY device advertises BACKWARD_COMPATIBLE and nothing else
        // (AOSP: LEGACY devices "will only support BACKWARD_COMPATIBLE").
        // Adding MANUAL_SENSOR or BURST_CAPTURE here would be a lie the app
        // finds out about only when a request is silently ignored.
        p.put(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                new int[] { CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE });

        // No Mac has a camera torch, and the native layer exposes no torch
        // control, so FLASH_INFO_AVAILABLE is false and CameraManager
        // .setTorchMode stays a no-op for a known id (AOSP throws only for an
        // unknown one).
        p.put(CameraCharacteristics.FLASH_INFO_AVAILABLE, Boolean.FALSE);

        // AF: we do not drive focus. AVFoundation may be autofocusing the
        // device itself, but camera2's CONTROL_AF_MODE describes what the APP
        // can ask for, and the honest answer is "nothing" -- which AOSP spells
        // CONTROL_AF_MODE_OFF, the mode a fixed-focus camera reports. Zero AF
        // metering regions for the same reason.
        p.put(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                new int[] { CameraMetadata.CONTROL_AF_MODE_OFF });
        p.put(CameraCharacteristics.CONTROL_MAX_REGIONS_AF, Integer.valueOf(0));

        // AE: auto-exposure is always on and is not under app control, so the
        // single available mode is ON (not ON_AUTO_FLASH -- there is no flash).
        p.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                new int[] { CameraMetadata.CONTROL_AE_MODE_ON });
        p.put(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, fmt.fpsRanges);

        // The rest of the 3A description, each list holding exactly the value
        // the request templates carry (CaptureRequest.Builder): the host runs
        // auto-exposure and auto-white-balance itself and the app cannot steer
        // or lock them, so there is one mode each, no lock and no metering
        // region. AOSP documents these keys as present on every device, and
        // CameraX and camera apps read them without a null check before they
        // build a still-capture request.
        p.put(CameraCharacteristics.CONTROL_AVAILABLE_MODES,
                new int[] { CameraMetadata.CONTROL_MODE_AUTO });
        p.put(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
                new int[] { CameraMetadata.CONTROL_AWB_MODE_AUTO });
        p.put(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE, Boolean.FALSE);
        p.put(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE, Boolean.FALSE);
        p.put(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, Integer.valueOf(0));
        p.put(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB, Integer.valueOf(0));

        // Exposure compensation: not under app control either. AOSP says both
        // keys are "available on all devices" and spells "not supported" as the
        // range [0,0]; a LEGACY device's step comes from Camera1's
        // getExposureCompensationStep(), which is 0 there, and
        // ParamsUtils.createRational(0f) is Rational.ZERO
        // (LegacyMetadataMapper.mapControlAe). Absent, CameraX's
        // ExposureState.getExposureCompensationRange() handed back null and
        // react-native-vision-camera's device list threw an NPE on getLower()
        // while the Xbox app's native modules initialised (GitHub #7).
        // GATE-MUTANT(camaecomp:keys) begin
        p.put(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
                new android.util.Range<>(Integer.valueOf(0), Integer.valueOf(0)));
        p.put(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, android.util.Rational.ZERO);
        // GATE-MUTANT(camaecomp:keys) end

        // SENSOR_INFO_ACTIVE_ARRAY_SIZE is the coordinate space apps map crop
        // and metering rectangles into, and it is also what many use to derive
        // the sensor aspect ratio. The largest format the device advertises is
        // the honest stand-in for the full sensor readout: it is the most
        // pixels this camera will ever hand us.
        // The pixel array and the pre-correction array are that same rectangle:
        // there is no masked border and no distortion correction between them.
        if (fmt.maxWidth > 0 && fmt.maxHeight > 0) {
            p.put(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                    new Rect(0, 0, fmt.maxWidth, fmt.maxHeight));
            p.put(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                    new Rect(0, 0, fmt.maxWidth, fmt.maxHeight));
            p.put(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
                    new Size(fmt.maxWidth, fmt.maxHeight));
        }

        // Timestamps are the host's monotonic clock, comparable with nothing
        // else: camera2 calls that UNKNOWN (see CameraCaptureSessionImpl
        // .dispatchFrame).
        p.put(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                Integer.valueOf(CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN));

        // No zoom: SCALER_CROP_REGION is not acted on, and 1.0 is how camera2
        // says so (the key is required; apps divide the active array by it).
        p.put(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, Float.valueOf(1.0f));
        p.put(CameraCharacteristics.SCALER_CROPPING_TYPE,
                Integer.valueOf(CameraMetadata.SCALER_CROPPING_TYPE_CENTER_ONLY));

        // How many outputs one session takes: none RAW, the preview and
        // analysis outputs, and one stalling (JPEG) output -- the LEGACY
        // guarantee, and what CameraCaptureSessionImpl serves. One result per
        // capture, no partials.
        p.put(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW, Integer.valueOf(0));
        p.put(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC, Integer.valueOf(3));
        p.put(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING, Integer.valueOf(1));
        p.put(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT, Integer.valueOf(1));

        // The thumbnails a still can embed (JPEG_THUMBNAIL_SIZE; JpegStill
        // resamples the still to whichever is asked for). (0, 0), "no
        // thumbnail", is required to be in the list, which AOSP orders by
        // area.
        p.put(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES,
                new Size[] { new Size(0, 0), new Size(160, 120), new Size(320, 240) });

        // Deliberately ABSENT: LENS_INFO_AVAILABLE_FOCAL_LENGTHS and
        // SENSOR_INFO_PHYSICAL_SIZE. AVFoundation does not tell us the focal
        // length or the sensor's physical dimensions, and those two are
        // multiplied together to compute field of view -- a fabricated pair
        // would put a plausible-looking but wrong FOV in front of the user.
        // get() returns null for an absent key, which is what a real device
        // returns for metadata it does not carry.

        p.put(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                StreamConfigurationMap.macapkCreate(
                        // Preview and analysis frames, stills, and the opaque
                        // buffers of a SurfaceTexture output: see the map.
                        new int[] { android.graphics.ImageFormat.YUV_420_888,
                                android.graphics.ImageFormat.JPEG,
                                android.graphics.ImageFormat.PRIVATE },
                        fmt.sizes,
                        fmt.fpsRanges));

        // The request keys we actually act on: CONTROL_AE_TARGET_FPS_RANGE
        // (CameraCaptureSessionImpl passes its upper bound to
        // CameraBridge.configure()) and the four a still is encoded by
        // (JpegStill). Naming a key we ignore would be worse than naming
        // none -- the app would set it, see it in the list, and believe it
        // took effect.
        List<CaptureRequest.Key<?>> requestKeys = new ArrayList<>();
        requestKeys.add(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        requestKeys.add(CaptureRequest.JPEG_ORIENTATION);
        requestKeys.add(CaptureRequest.JPEG_QUALITY);
        requestKeys.add(CaptureRequest.JPEG_THUMBNAIL_SIZE);
        requestKeys.add(CaptureRequest.JPEG_THUMBNAIL_QUALITY);

        // The result keys the capture session actually fills in: every
        // CaptureResult carries SENSOR_TIMESTAMP, and one that produced a still
        // the quality and orientation it was encoded with.
        List<CaptureResult.Key<?>> resultKeys = new ArrayList<>();
        resultKeys.add(CaptureResult.SENSOR_TIMESTAMP);
        resultKeys.add(CaptureResult.JPEG_ORIENTATION);
        resultKeys.add(CaptureResult.JPEG_QUALITY);

        trace("characteristics id=" + cameraId + " index=" + index
                + " unique=" + unique
                + " name=" + CameraBridge.getDeviceName(index)
                + " facing=" + facing
                + " sizes=" + fmt.sizes.length
                + " max=" + fmt.maxWidth + "x" + fmt.maxHeight
                + " fpsRanges=" + fmt.fpsRanges.length);

        return new CameraCharacteristics(p, requestKeys, resultKeys);
    }

    // ---- Format enumeration ----------------------------------------------

    /** The advertised output geometry of one device, reshaped into the two
     *  arrays camera2 hands to apps. */
    static final class Formats {
        final Size[] sizes;
        final Range<Integer>[] fpsRanges;
        final int maxWidth;
        final int maxHeight;

        Formats(Size[] sizes, Range<Integer>[] fpsRanges, int maxWidth, int maxHeight) {
            this.sizes = sizes;
            this.fpsRanges = fpsRanges;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }
    }

    @SuppressWarnings("unchecked")
    static Formats formats(int index) {
        int n = CameraBridge.getFormatCount(index);

        // LinkedHashSet, not a list: AVFoundation advertises one entry per
        // (resolution, frame-rate range) pair, so the same resolution appears
        // many times. camera2's getOutputSizes must not repeat a size.
        LinkedHashSet<Size> sizes = new LinkedHashSet<>();
        LinkedHashSet<String> seenRanges = new LinkedHashSet<>();
        List<Range<Integer>> ranges = new ArrayList<>();
        int maxW = 0;
        int maxH = 0;

        for (int i = 0; i < n; i++) {
            int w = CameraBridge.getFormatWidth(index, i);
            int h = CameraBridge.getFormatHeight(index, i);
            if (w > 0 && h > 0) {
                sizes.add(new Size(w, h));
                if ((long) w * h > (long) maxW * maxH) { maxW = w; maxH = h; }
            }

            // Android FPS ranges are integers; AVFoundation's are doubles.
            // Round the low bound UP and the high bound DOWN so the range we
            // advertise is one the device can actually hold for its whole
            // width -- rounding outwards would advertise a rate the hardware
            // cannot reach at either end.
            double lo = CameraBridge.getFormatMinFps(index, i);
            double hi = CameraBridge.getFormatMaxFps(index, i);
            int loI = (int) Math.ceil(lo);
            int hiI = (int) Math.floor(hi);
            if (loI < 1) loI = 1;
            if (hiI < loI) hiI = loI;
            String tag = loI + "-" + hiI;
            if (seenRanges.add(tag)) {
                ranges.add(new Range<>(Integer.valueOf(loI), Integer.valueOf(hiI)));
            }
        }

        Size[] sizeArray = sizes.toArray(new Size[0]);
        // Descending by area. AOSP does not document an order, but every real
        // device returns largest-first and a great deal of app code takes
        // sizes[0] as "the biggest" without sorting.
        Arrays.sort(sizeArray, (a, b) -> Long.compare(
                (long) b.getWidth() * b.getHeight(),
                (long) a.getWidth() * a.getHeight()));

        Range<Integer>[] rangeArray = ranges.toArray((Range<Integer>[]) new Range[0]);

        return new Formats(sizeArray, rangeArray, maxW, maxH);
    }

    // ---- Authorization ---------------------------------------------------
    //
    // macOS TCC has no Android equivalent that lines up exactly, so the mapping
    // is spelled out here rather than at the call sites:
    //
    //   AUTH_AUTHORIZED      -> open proceeds.
    //   AUTH_DENIED          -> the user said no. On real Android an app that
    //   AUTH_RESTRICTED         calls openCamera without the CAMERA permission
    //                           gets a SecurityException from the camera
    //                           service, so that is what CameraManager throws.
    //   AUTH_NOT_DETERMINED  -> nobody has been asked yet. Android has already
    //                           shown its own permission dialog by this point
    //                           (the app requested CAMERA at launch), so the
    //                           macOS prompt has to be raised HERE, at open,
    //                           and the open has to wait for the answer. That
    //                           wait happens on a background thread and the
    //                           result arrives through StateCallback, which is
    //                           exactly the shape of AOSP's asynchronous
    //                           openCamera.
    //
    // The prompt only appears for a process whose bundle declares
    // NSCameraUsageDescription; launcher/Info.plist does. A HEADLESS runtime
    // run is attributed to the terminal instead, so the outcome there depends
    // on the terminal's own TCC state -- see camera_capture.h PERMISSIONS.

    /** How long openCamera waits for the user to answer the macOS prompt
     *  before giving up and reporting an error through StateCallback. Long
     *  enough for a human to read a dialog, bounded so a dismissed prompt does
     *  not leave the app waiting forever with no callback at all. */
    private static final long AUTH_WAIT_MS = 30_000L;
    private static final long AUTH_POLL_MS = 100L;

    static boolean authDecidedDenied() {
        int st = CameraBridge.getAuthStatus();
        return st == CameraBridge.AUTH_DENIED || st == CameraBridge.AUTH_RESTRICTED;
    }

    static int authStatus() {
        return CameraBridge.getAuthStatus();
    }

    /**
     * Bring authorization to a decision, prompting if nobody has been asked.
     * BLOCKS, so it must only ever be called from the background open thread --
     * never from an app's UI thread, and never from the AppKit main thread,
     * which has to keep pumping for the TCC panel to appear at all.
     *
     * @return true if the camera may be opened.
     */
    static boolean awaitAuthorization() {
        int st = CameraBridge.getAuthStatus();
        if (st == CameraBridge.AUTH_AUTHORIZED) return true;
        if (st == CameraBridge.AUTH_DENIED || st == CameraBridge.AUTH_RESTRICTED) return false;

        trace("authorization not determined; raising the macOS prompt");
        CameraBridge.requestAuth();

        long deadline = System.nanoTime() + AUTH_WAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            st = CameraBridge.getAuthStatus();
            if (st == CameraBridge.AUTH_AUTHORIZED) {
                trace("authorization granted");
                return true;
            }
            if (st == CameraBridge.AUTH_DENIED || st == CameraBridge.AUTH_RESTRICTED) {
                warn("camera access denied by macOS privacy settings");
                return false;
            }
            try {
                Thread.sleep(AUTH_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        warn("timed out after " + (AUTH_WAIT_MS / 1000)
                + "s waiting for the macOS camera permission prompt to be answered");
        return false;
    }

    // ---- Unmodifiable helpers --------------------------------------------

    static <T> List<T> immutable(List<T> in) {
        return in == null || in.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(in));
    }
}
