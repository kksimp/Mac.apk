// android.hardware.Camera -- the deprecated (pre-camera2) camera API, WIRED to
// the real AVFoundation capture layer as of this change.
//
// =============================================================================
// WHY THIS FILE STOPPED REPORTING ZERO CAMERAS
// =============================================================================
// From v1.0.942 until now this class reported getNumberOfCameras() == 0 with a
// documented rationale: the capture layer (runtime/src/camera_capture.mm, live
// since v1.0.940) delivered frames only through camera2's ImageReader and
// SurfaceTexture seams, so an app told "1 camera" here would have called open()
// and then setPreviewDisplay() into nothing. That reasoning was sound, and its
// own REMOVAL CONDITION was stated: "bind Camera(int) to
// com.macapk.loader.CameraBridge and deliver frames to the preview callback,
// then return the real device count." This change does exactly that, so the
// zero goes.
//
// It had also become the single worst kind of wrong answer -- an INCONSISTENT
// one. android.hardware.camera2 reports the Mac's real cameras (see
// CameraBackend), and MacApkPackageManager declares FEATURE_CAMERA_ANY and
// FEATURE_CAMERA_FRONT. So a device that says "yes, there is a camera" through
// the feature flags and through camera2 was saying "there are zero cameras"
// through camera1, and an app that consults more than one of those (very many
// do: feature flag to decide whether to show the button, camera1 to open it)
// got a device that contradicted itself. There is no branch an app can take
// that makes that coherent.
//
// =============================================================================
// WHAT IS ACTUALLY DELIVERED, AND WHAT IS NOT
// =============================================================================
// Delivered, from the real device:
//   * getNumberOfCameras / getCameraInfo -- straight from CameraBridge, so
//     camera1 and camera2 enumerate the SAME devices in the SAME order (both
//     key on the device INDEX; see CameraBackend's "THE ID MAPPING" note).
//   * open(int) -- a real AVFoundation session.
//   * Parameters -- populated from the device's advertised formats: real
//     supported preview/picture sizes, real fps ranges. Nothing in the bag is
//     invented; a capability we cannot honour is simply absent, which is the
//     state AOSP's own getters are written against.
//   * startPreview + setPreviewCallback / setOneShotPreviewCallback /
//     setPreviewCallbackWithBuffer -- real frames, converted to the requested
//     preview format (NV21 by default, YV12 if asked). This is the path the
//     large legacy population of scanner apps (ZXing/ZBar barcode and QR
//     readers, OCR, card scanners) uses, and it is the one that matters most.
//   * setPreviewTexture -- attaches the SurfaceTexture to the same native frame
//     source camera2 uses, so a TextureView preview draws.
//   * setPreviewDisplay -- blits each frame onto the SurfaceHolder's canvas
//     (lockCanvas/unlockCanvasAndPost, which SurfaceView really does present).
//     Note camera2 has NO equivalent for a plain SurfaceView, so this path is
//     something camera1 supports here that camera2 does not.
//   * takePicture -- a real JPEG of a real frame, encoded from the captured
//     pixels.
//
// NOT delivered, and reported as unsupported rather than faked:
//   * flash (no torch on a Mac's camera): getSupportedFlashModes() is null.
//   * zoom and smooth zoom: isZoomSupported()/isSmoothZoomSupported() false.
//   * face detection: getMaxNumDetectedFaces() is 0, and startFaceDetection()
//     throws IllegalArgumentException exactly as AOSP does when unsupported.
//   * autofocus: only FOCUS_MODE_FIXED is advertised. AOSP's documented
//     behaviour for a fixed-focus camera is that autoFocus() calls back
//     immediately with success=TRUE ("a fake value of success set to true"),
//     which is what happens here -- the previous `false` was wrong for a camera
//     that is always in focus.
//   * exposure compensation, white-balance and antibanding CONTROL: the
//     capability keys are absent, so the AOSP getters return their documented
//     "unsupported" values (0 / null) on their own.
//
// =============================================================================
// PERMISSION (macOS TCC), AND THE ONE PLACE THIS DIVERGES FROM AOSP
// =============================================================================
// Opening the camera is what raises the macOS permission panel. camera2 can
// wait for the user's answer because openCamera is ASYNCHRONOUS -- it does the
// waiting on a background thread. camera1's open(int) is SYNCHRONOUS by
// contract, so there is no background thread to wait on, and blocking the main
// looper is not an option: camera_capture's own notes say the AppKit main
// thread has to keep pumping for the panel to work at all, so blocking there
// would deadlock the very prompt we are waiting for.
//
// So: off the main looper, open() waits (bounded) for the answer, which is the
// AOSP-shaped synchronous behaviour. ON the main looper it raises the prompt
// and then fails THIS attempt with RuntimeException("Fail to connect to camera
// service") -- which is precisely what AOSP's open(int) does on a device when
// the app has not been granted CAMERA permission yet, and every camera app
// already handles it, because that is the ordinary "request permission, then
// open in the result callback / in onResume" flow. A loud line is logged so the
// case is diagnosable rather than mysterious.
//
// =============================================================================
// AOSP SOURCE
// =============================================================================
// frameworks/base/core/java/android/hardware/Camera.java, android16-release.
// Method bodies that do not need the device (the Parameters bag's typed
// accessors, splitSize/splitInt/splitFloat/splitArea, the open() back-camera
// scan) are mirrored from it; the ones that do are named at their site.
package android.hardware;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.macapk.loader.CameraBridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

public class Camera {
    private static final String TAG = "camera1";

    public static final int CAMERA_ERROR_UNKNOWN = 1;
    // AOSP android16-release Camera.java:1987/1996 -- the two error codes
    // an ErrorCallback can receive besides UNKNOWN / SERVER_DIED.
    public static final int CAMERA_ERROR_EVICTED = 2;
    public static final int CAMERA_ERROR_DISABLED = 3;
    public static final int CAMERA_ERROR_SERVER_DIED = 100;

    /** AOSP broadcast actions. Compile-time String constants, so javac has
     *  already inlined them into any app that names one -- declared for
     *  completeness of the surface and for reflective readers. */
    public static final String ACTION_NEW_PICTURE = "android.hardware.action.NEW_PICTURE";
    public static final String ACTION_NEW_VIDEO = "android.hardware.action.NEW_VIDEO";

    // ---- instance state --------------------------------------------------

    private final int mCameraId;
    /** Read once at open: the facing never changes, and the frame loop needs it. */
    private final boolean mFrontFacing;
    private long mSession = CameraBridge.NO_SESSION;
    private volatile boolean mReleased;

    /** AOSP delivers every callback on the Looper of the thread that called
     *  open() (SystemSensorManager's sibling rule; Camera.java's mEventHandler
     *  is built from Looper.myLooper() at construction and falls back to the
     *  main looper). Mirrored, because an app that opens the camera from its
     *  own HandlerThread expects its frames back on that thread and will
     *  otherwise touch its own state from two threads at once. */
    private final Handler mEventHandler;

    /** The parameter bag, exactly as AOSP models it: a flat key/value map that
     *  the HAL populates and that every typed Parameters accessor reads. Ours
     *  is populated from CameraBridge's format enumeration. Guarded by `this`
     *  because getParameters/setParameters can be called from any thread. */
    private final java.util.LinkedHashMap<String, String> mBag =
            new java.util.LinkedHashMap<>();

    private PreviewCallback mPreviewCallback;
    /** AOSP's three preview-callback modes: repeating, one-shot, and
     *  buffer-fed. They are mutually exclusive -- setting one clears the
     *  others, same as AOSP's mOneShot/mWithBuffer pair. */
    private boolean mOneShot;
    private boolean mWithBuffer;
    private final ArrayDeque<byte[]> mCallbackBuffers = new ArrayDeque<>();

    private SurfaceHolder mPreviewHolder;
    private SurfaceTexture mPreviewTexture;

    private ErrorCallback mErrorCallback;
    private AutoFocusMoveCallback mAutoFocusMoveCallback;
    private OnZoomChangeListener mZoomListener;
    private FaceDetectionListener mFaceListener;
    private boolean mFaceDetectionRunning;
    private int mDisplayOrientation;
    private boolean mShutterSoundEnabled = true;

    private Thread mPreviewThread;
    private volatile boolean mPreviewRunning;

    // ---- construction / enumeration --------------------------------------

    /**
     * AOSP's `Camera(int cameraId)` runs native_setup and throws when the
     * camera service cannot be reached; that RuntimeException is what open(int)
     * is documented to raise. Here the equivalent failure is "AVFoundation
     * would not give us a session", which covers a bad index, a camera already
     * in use by another app, and a denied or not-yet-answered TCC prompt.
     */
    private Camera(int cameraId) {
        Looper looper = Looper.myLooper();
        if (looper == null) looper = Looper.getMainLooper();
        mEventHandler = (looper != null) ? new Handler(looper) : null;

        if (cameraId < 0 || cameraId >= CameraBridge.getDeviceCount()) {
            throw new RuntimeException("Fail to connect to camera service");
        }
        if (!authorizeForOpen()) {
            throw new RuntimeException("Fail to connect to camera service");
        }
        long session = CameraBridge.open(cameraId);
        if (session == CameraBridge.NO_SESSION) {
            throw new RuntimeException("Fail to connect to camera service");
        }
        mCameraId = cameraId;
        mFrontFacing = CameraBridge.getDeviceFacing(cameraId) == CameraBridge.FACING_FRONT;
        mSession = session;
        populateBag(cameraId);
    }

    /**
     * See the PERMISSION section in the class header. Returns true when the
     * camera may be opened now.
     */
    private static boolean authorizeForOpen() {
        int st = CameraBridge.getAuthStatus();
        if (st == CameraBridge.AUTH_AUTHORIZED) return true;
        if (st == CameraBridge.AUTH_DENIED || st == CameraBridge.AUTH_RESTRICTED) {
            System.err.println("[" + TAG + "] camera access denied by macOS privacy settings");
            return false;
        }

        CameraBridge.requestAuth();

        // Never block the main looper: the AppKit run loop has to keep pumping
        // for the TCC panel, so waiting there would deadlock the prompt.
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            System.err.println("[" + TAG + "] raised the macOS camera permission prompt;"
                    + " this open() fails (as it would on Android without the CAMERA"
                    + " permission) -- open again once the prompt is answered");
            return false;
        }

        final long deadline = System.nanoTime() + 30_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            st = CameraBridge.getAuthStatus();
            if (st == CameraBridge.AUTH_AUTHORIZED) return true;
            if (st == CameraBridge.AUTH_DENIED || st == CameraBridge.AUTH_RESTRICTED) {
                System.err.println("[" + TAG + "] camera access denied by macOS privacy settings");
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        System.err.println("[" + TAG + "] timed out waiting for the macOS camera"
                + " permission prompt to be answered");
        return false;
    }

    /** Same source of truth camera2 uses, so the two APIs can never disagree
     *  about how many cameras this device has. */
    public static int getNumberOfCameras() {
        return CameraBridge.getDeviceCount();
    }

    /**
     * AOSP throws RuntimeException for an out-of-range id ("if an error
     * occurs"); a real device also has nothing sensible to write into the
     * struct. The facing constants differ between the two APIs and the mapping
     * is done HERE rather than at each call site:
     *   CameraBridge.FACING_FRONT (0) -> CameraInfo.CAMERA_FACING_FRONT (1)
     *   CameraBridge.FACING_BACK  (1) -> CameraInfo.CAMERA_FACING_BACK  (0)
     * -- note they are inverted, so passing one through unchanged would report
     * every Mac's built-in camera as REAR-facing and mirror every preview the
     * wrong way.
     *
     * FACING_EXTERNAL (a USB/UVC camera) has no camera1 equivalent at all;
     * camera1 predates external cameras and CameraInfo.facing is a two-valued
     * field. AOSP's own camera1 shim for an external camera reports it as
     * BACK-facing, because BACK is the "not mirrored, not a selfie camera"
     * answer and mirroring an external camera's image is the visible mistake.
     */
    public static void getCameraInfo(int cameraId, CameraInfo cameraInfo) {
        if (cameraInfo == null) {
            throw new NullPointerException("cameraInfo is null");
        }
        if (cameraId < 0 || cameraId >= CameraBridge.getDeviceCount()) {
            throw new RuntimeException("Unknown camera ID");
        }
        int facing = CameraBridge.getDeviceFacing(cameraId);
        cameraInfo.facing = (facing == CameraBridge.FACING_FRONT)
                ? CameraInfo.CAMERA_FACING_FRONT : CameraInfo.CAMERA_FACING_BACK;
        cameraInfo.orientation = CameraBridge.getSensorOrientation(cameraId);
        // AOSP: "whether the shutter sound can be disabled". A Mac plays no
        // shutter sound at all, so there is nothing to forbid disabling.
        cameraInfo.canDisableShutterSound = true;
    }

    /**
     * AOSP: "Creates a new Camera object to access the first back-facing camera
     * on the device. If the device does not have a back-facing camera, this
     * returns null." The scan is AOSP's, verbatim.
     *
     * On a Mac with only its built-in camera this correctly returns NULL -- the
     * built-in camera is front-facing, exactly as on an Android tablet that has
     * no rear camera. That is not a shortfall: an app that needs a rear camera
     * genuinely does not have one here, and null is the answer it is written to
     * handle. Apps that enumerate and open by id get the real camera.
     */
    public static Camera open() {
        int numberOfCameras = getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return new Camera(i);
            }
        }
        return null;
    }

    public static Camera open(int cameraId) {
        return new Camera(cameraId);
    }

    // ---- the parameter bag, populated from the real device ---------------

    // AOSP's literal key strings (Camera.java:2267-2328). They are the wire
    // format between the HAL and Parameters, and every typed accessor below
    // is written against them, so they must match exactly.
    private static final String KEY_PREVIEW_SIZE = "preview-size";
    private static final String KEY_PREVIEW_FORMAT = "preview-format";
    private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
    private static final String KEY_PREVIEW_FPS_RANGE = "preview-fps-range";
    private static final String KEY_PICTURE_SIZE = "picture-size";
    private static final String KEY_PICTURE_FORMAT = "picture-format";
    private static final String KEY_JPEG_THUMBNAIL_SIZE = "jpeg-thumbnail-size";
    private static final String KEY_JPEG_THUMBNAIL_WIDTH = "jpeg-thumbnail-width";
    private static final String KEY_JPEG_THUMBNAIL_HEIGHT = "jpeg-thumbnail-height";
    private static final String KEY_JPEG_THUMBNAIL_QUALITY = "jpeg-thumbnail-quality";
    private static final String KEY_JPEG_QUALITY = "jpeg-quality";
    private static final String KEY_ROTATION = "rotation";
    private static final String KEY_GPS_LATITUDE = "gps-latitude";
    private static final String KEY_GPS_LONGITUDE = "gps-longitude";
    private static final String KEY_GPS_ALTITUDE = "gps-altitude";
    private static final String KEY_GPS_TIMESTAMP = "gps-timestamp";
    private static final String KEY_GPS_PROCESSING_METHOD = "gps-processing-method";
    private static final String KEY_WHITE_BALANCE = "whitebalance";
    private static final String KEY_EFFECT = "effect";
    private static final String KEY_ANTIBANDING = "antibanding";
    private static final String KEY_SCENE_MODE = "scene-mode";
    private static final String KEY_FLASH_MODE = "flash-mode";
    private static final String KEY_FOCUS_MODE = "focus-mode";
    private static final String KEY_FOCUS_AREAS = "focus-areas";
    private static final String KEY_MAX_NUM_FOCUS_AREAS = "max-num-focus-areas";
    private static final String KEY_FOCAL_LENGTH = "focal-length";
    private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
    private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";
    private static final String KEY_EXPOSURE_COMPENSATION = "exposure-compensation";
    private static final String KEY_MAX_EXPOSURE_COMPENSATION = "max-exposure-compensation";
    private static final String KEY_MIN_EXPOSURE_COMPENSATION = "min-exposure-compensation";
    private static final String KEY_EXPOSURE_COMPENSATION_STEP = "exposure-compensation-step";
    private static final String KEY_AUTO_EXPOSURE_LOCK = "auto-exposure-lock";
    private static final String KEY_AUTO_EXPOSURE_LOCK_SUPPORTED = "auto-exposure-lock-supported";
    private static final String KEY_AUTO_WHITEBALANCE_LOCK = "auto-whitebalance-lock";
    private static final String KEY_AUTO_WHITEBALANCE_LOCK_SUPPORTED =
            "auto-whitebalance-lock-supported";
    private static final String KEY_METERING_AREAS = "metering-areas";
    private static final String KEY_MAX_NUM_METERING_AREAS = "max-num-metering-areas";
    private static final String KEY_ZOOM = "zoom";
    private static final String KEY_MAX_ZOOM = "max-zoom";
    private static final String KEY_ZOOM_RATIOS = "zoom-ratios";
    private static final String KEY_ZOOM_SUPPORTED = "zoom-supported";
    private static final String KEY_SMOOTH_ZOOM_SUPPORTED = "smooth-zoom-supported";
    private static final String KEY_FOCUS_DISTANCES = "focus-distances";
    private static final String KEY_VIDEO_SIZE = "video-size";
    private static final String KEY_PREFERRED_PREVIEW_SIZE_FOR_VIDEO =
            "preferred-preview-size-for-video";
    private static final String KEY_MAX_NUM_DETECTED_FACES_HW = "max-num-detected-faces-hw";
    private static final String KEY_MAX_NUM_DETECTED_FACES_SW = "max-num-detected-faces-sw";
    private static final String KEY_RECORDING_HINT = "recording-hint";
    private static final String KEY_VIDEO_SNAPSHOT_SUPPORTED = "video-snapshot-supported";
    private static final String KEY_VIDEO_STABILIZATION = "video-stabilization";
    private static final String KEY_VIDEO_STABILIZATION_SUPPORTED = "video-stabilization-supported";

    private static final String SUPPORTED_VALUES_SUFFIX = "-values";
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    // AOSP's pixel-format tokens (Camera.java:2590-2596).
    private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
    private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";   // NV21
    private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
    private static final String PIXEL_FORMAT_YUV420P = "yuv420p";     // YV12
    private static final String PIXEL_FORMAT_RGB565 = "rgb565";
    private static final String PIXEL_FORMAT_JPEG = "jpeg";

    /**
     * Fill the bag from what CameraBridge says this device can actually do.
     *
     * Two rules, and they are the whole reason this is not a table of
     * plausible constants:
     *   1. Every SIZE and FPS value comes from the device's advertised format
     *      list. Nothing is rounded to a familiar number and nothing is
     *      invented.
     *   2. A capability we cannot deliver gets its key OMITTED, not set to a
     *      falsy value -- because AOSP's own accessors are written to treat an
     *      ABSENT key as "unsupported" (getInt(key, 0), split() returning
     *      null), so omission produces exactly the AOSP answer with no special
     *      casing. Where AOSP uses an explicit "false" string
     *      (zoom-supported and friends) we write "false", because there the
     *      absence and the falsehood are not the same to the accessor.
     */
    private void populateBag(int cameraId) {
        StringBuilder sizes = new StringBuilder();
        StringBuilder fpsRanges = new StringBuilder();
        java.util.TreeSet<Integer> frameRates = new java.util.TreeSet<>();
        int bestW = 0, bestH = 0;
        int n = CameraBridge.getFormatCount(cameraId);
        for (int i = 0; i < n; i++) {
            int w = CameraBridge.getFormatWidth(cameraId, i);
            int h = CameraBridge.getFormatHeight(cameraId, i);
            if (w <= 0 || h <= 0) continue;
            String pair = w + "x" + h;
            // The format list can advertise the same geometry more than once
            // (one entry per pixel encoding); camera1's size list is geometry
            // only, so de-duplicate or the app sees the same size repeatedly.
            if (!containsToken(sizes.toString(), pair)) {
                if (sizes.length() > 0) sizes.append(',');
                sizes.append(pair);
            }
            if ((long) w * h > (long) bestW * bestH) { bestW = w; bestH = h; }

            int minFps = (int) Math.floor(CameraBridge.getFormatMinFps(cameraId, i));
            int maxFps = (int) Math.floor(CameraBridge.getFormatMaxFps(cameraId, i));
            if (maxFps > 0) {
                frameRates.add(maxFps);
                // AOSP's fps-range values are in units of 1/1000 fps.
                String range = "(" + (Math.max(minFps, 1) * 1000) + "," + (maxFps * 1000) + ")";
                if (!containsToken(fpsRanges.toString(), range)) {
                    if (fpsRanges.length() > 0) fpsRanges.append(',');
                    fpsRanges.append(range);
                }
            }
        }

        if (bestW <= 0 || bestH <= 0) {
            // A session opened but advertised no usable format. Nothing to
            // describe; leave the bag empty so every accessor reports
            // unsupported rather than describing a device we cannot see.
            System.err.println("[" + TAG + "] camera " + cameraId
                    + " advertised no usable capture format");
            return;
        }

        if (sizes.length() > 0) {
            mBag.put(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX, sizes.toString());
            mBag.put(KEY_PICTURE_SIZE + SUPPORTED_VALUES_SUFFIX, sizes.toString());
            mBag.put(KEY_VIDEO_SIZE + SUPPORTED_VALUES_SUFFIX, sizes.toString());
        }
        if (fpsRanges.length() > 0) {
            mBag.put(KEY_PREVIEW_FPS_RANGE + SUPPORTED_VALUES_SUFFIX, fpsRanges.toString());
        }
        if (!frameRates.isEmpty()) {
            StringBuilder rates = new StringBuilder();
            for (Integer r : frameRates) {
                if (rates.length() > 0) rates.append(',');
                rates.append(r);
            }
            mBag.put(KEY_PREVIEW_FRAME_RATE + SUPPORTED_VALUES_SUFFIX, rates.toString());
            int maxRate = frameRates.last();
            mBag.put(KEY_PREVIEW_FRAME_RATE, Integer.toString(maxRate));
            mBag.put(KEY_PREVIEW_FPS_RANGE, (Math.max(frameRates.first(), 1) * 1000)
                    + "," + (maxRate * 1000));
        }

        // Defaults. AOSP's HAL picks a default preview size (usually the
        // largest that matches the display aspect) and the largest picture
        // size; the largest advertised format is the honest analogue here, and
        // an app that wants something else picks from the -values list.
        mBag.put(KEY_PREVIEW_SIZE, bestW + "x" + bestH);
        mBag.put(KEY_PICTURE_SIZE, bestW + "x" + bestH);
        mBag.put(KEY_PREFERRED_PREVIEW_SIZE_FOR_VIDEO, bestW + "x" + bestH);

        // Formats we really convert to; see deliverPreviewFrame.
        mBag.put(KEY_PREVIEW_FORMAT, PIXEL_FORMAT_YUV420SP);
        mBag.put(KEY_PREVIEW_FORMAT + SUPPORTED_VALUES_SUFFIX,
                PIXEL_FORMAT_YUV420SP + "," + PIXEL_FORMAT_YUV420P);
        mBag.put(KEY_PICTURE_FORMAT, PIXEL_FORMAT_JPEG);
        mBag.put(KEY_PICTURE_FORMAT + SUPPORTED_VALUES_SUFFIX, PIXEL_FORMAT_JPEG);
        mBag.put(KEY_JPEG_QUALITY, "90");
        mBag.put(KEY_JPEG_THUMBNAIL_QUALITY, "90");
        mBag.put(KEY_JPEG_THUMBNAIL_WIDTH, "0");
        mBag.put(KEY_JPEG_THUMBNAIL_HEIGHT, "0");

        // AVFoundation keeps the camera in continuous auto-focus and gives us
        // no focus control, so the only mode we can HONESTLY offer is FIXED --
        // the app cannot drive focus, which is what "fixed" tells it.
        mBag.put(KEY_FOCUS_MODE, Parameters.FOCUS_MODE_FIXED);
        mBag.put(KEY_FOCUS_MODE + SUPPORTED_VALUES_SUFFIX, Parameters.FOCUS_MODE_FIXED);

        // Explicit falsehoods, because these accessors compare against "true"
        // and an absent key would read the same as "false" only by accident.
        mBag.put(KEY_ZOOM_SUPPORTED, FALSE);
        mBag.put(KEY_SMOOTH_ZOOM_SUPPORTED, FALSE);
        mBag.put(KEY_VIDEO_SNAPSHOT_SUPPORTED, FALSE);
        mBag.put(KEY_VIDEO_STABILIZATION_SUPPORTED, FALSE);
        mBag.put(KEY_AUTO_EXPOSURE_LOCK_SUPPORTED, FALSE);
        mBag.put(KEY_AUTO_WHITEBALANCE_LOCK_SUPPORTED, FALSE);
        mBag.put(KEY_MAX_ZOOM, "0");
        mBag.put(KEY_MAX_NUM_FOCUS_AREAS, "0");
        mBag.put(KEY_MAX_NUM_METERING_AREAS, "0");
        mBag.put(KEY_MAX_NUM_DETECTED_FACES_HW, "0");
        mBag.put(KEY_MAX_NUM_DETECTED_FACES_SW, "0");
        // Exposure compensation: AOSP documents "if both max and min are 0,
        // exposure compensation is not supported", so 0/0 is the SUPPORTED way
        // to say we cannot do it.
        mBag.put(KEY_MAX_EXPOSURE_COMPENSATION, "0");
        mBag.put(KEY_MIN_EXPOSURE_COMPENSATION, "0");
        mBag.put(KEY_EXPOSURE_COMPENSATION, "0");
        mBag.put(KEY_EXPOSURE_COMPENSATION_STEP, "0");
        // Deliberately ABSENT, each meaning "unsupported" to its AOSP getter:
        // flash-mode(-values), scene-mode(-values), whitebalance(-values),
        // effect(-values), antibanding(-values), zoom-ratios, focus-distances,
        // focal-length, horizontal/vertical-view-angle. AVFoundation does not
        // report a focal length or field of view through the enumeration we
        // use, and a guessed 4mm/60deg would be a fabrication an app cannot
        // detect (AR and measurement apps compute distances from these).
    }

    private static boolean containsToken(String csv, String token) {
        if (csv == null || csv.isEmpty()) return false;
        for (String s : csv.split(",", -1)) {
            if (s.equals(token)) return true;
        }
        return false;
    }

    // ---- lifecycle -------------------------------------------------------

    public void release() {
        stopPreview();
        synchronized (this) {
            if (mSession != CameraBridge.NO_SESSION) {
                CameraBridge.close(mSession);
                mSession = CameraBridge.NO_SESSION;
            }
            mReleased = true;
        }
    }

    /** AOSP Camera.java:653 -- `protected void finalize() { release(); }`.
     *  Kept because it is the only thing that frees a session an app leaked,
     *  and a leaked AVFoundation session keeps the camera's privacy indicator
     *  lit until the process exits. */
    @Override protected void finalize() {
        try {
            release();
        } catch (Throwable ignored) {
            // AOSP: "nothing to do here, the object is supposed to be released
            // anyway".
        }
    }

    /** AOSP: re-acquire the camera after unlock()/MediaRecorder use. There is
     *  no second process to hand the camera to here (see lock/unlock), so the
     *  session is already ours; the throws clause matches AOSP so an app's
     *  catch block is still meaningful. */
    public void reconnect() throws IOException {
        checkNotReleased();
    }

    /** AOSP lock()/unlock() hand the camera between this process and the media
     *  server for MediaRecorder. Mac.apk records in-process, so there is no
     *  ownership to transfer and these are genuinely no-ops -- not stubs. */
    public void lock() { }
    public void unlock() { }

    private synchronized void checkNotReleased() {
        if (mReleased || mSession == CameraBridge.NO_SESSION) {
            throw new RuntimeException("Camera is released");
        }
    }

    // ---- preview targets -------------------------------------------------

    /**
     * AOSP: the Surface the preview draws into. Ours blits each frame onto the
     * holder's Canvas -- SurfaceHolderImpl.unlockCanvasAndPost really does push
     * the bitmap to the screen through NativeCocoa.presentBitmap, so this is a
     * genuine preview and not a write into a void.
     *
     * A null holder detaches, which AOSP allows ("If this is not called, the
     * preview will not be displayed").
     */
    public void setPreviewDisplay(SurfaceHolder holder) throws IOException {
        checkNotReleased();
        mPreviewHolder = holder;
    }

    /**
     * AOSP: preview into a SurfaceTexture (the TextureView / GL path). This
     * attaches to the SAME native frame source camera2 attaches to, via
     * SurfaceTexture.macapkAttachCameraSource, so a TextureView preview here
     * behaves identically whichever camera API the app used.
     */
    public void setPreviewTexture(SurfaceTexture surfaceTexture) throws IOException {
        checkNotReleased();
        SurfaceTexture old = mPreviewTexture;
        if (old != null && old != surfaceTexture) {
            old.macapkDetachCameraSource();
        }
        mPreviewTexture = surfaceTexture;
        if (surfaceTexture != null && mPreviewRunning) {
            int[] wh = currentSessionSize();
            surfaceTexture.macapkAttachCameraSource(mSession, wh[0], wh[1]);
        }
    }

    /** AOSP: rotate the PREVIEW only; it does not affect the byte[] handed to
     *  onPreviewFrame, nor the JPEG. Recorded so getDisplayOrientation-style
     *  app logic stays coherent; the preview blit applies it. */
    public void setDisplayOrientation(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) {
            throw new IllegalArgumentException("Invalid display orientation: " + degrees);
        }
        mDisplayOrientation = degrees;
    }

    // ---- preview callbacks -----------------------------------------------

    public void setPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = false;
    }

    public void setOneShotPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = true;
        mWithBuffer = false;
    }

    public void setPreviewCallbackWithBuffer(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = true;
    }

    /**
     * AOSP: hand the camera a buffer to fill. The size contract is exact --
     * "the size of the buffer should be width * height * bitsPerPixel / 8" --
     * and AOSP THROWS for a buffer that is too small, so that check is
     * mirrored: silently accepting a short buffer would corrupt memory here
     * and produce a torn frame there.
     */
    public void addCallbackBuffer(byte[] callbackBuffer) {
        if (callbackBuffer == null) return;
        int need = previewBufferSize();
        if (need > 0 && callbackBuffer.length < need) {
            throw new IllegalArgumentException("Buffer is too small: expected at least "
                    + need + " bytes but got " + callbackBuffer.length);
        }
        synchronized (mCallbackBuffers) {
            mCallbackBuffers.add(callbackBuffer);
        }
    }

    private int previewBufferSize() {
        int[] wh = currentSessionSize();
        if (wh[0] <= 0 || wh[1] <= 0) return 0;
        int fmt = previewImageFormat();
        return wh[0] * wh[1] * ImageFormat.getBitsPerPixel(fmt) / 8;
    }

    // ---- focus / zoom / faces --------------------------------------------

    /**
     * AOSP: "If the camera does not support auto-focus, this is a no-op and
     * onAutoFocus will be called immediately with a fake value of success set
     * to true." AVFoundation keeps the camera focused and gives us no manual
     * control, so that is exactly this device -- hence TRUE, not the false
     * this file used to report when there was no camera at all.
     *
     * Delivered through the event handler rather than inline: AOSP posts it,
     * and an app that sets state right after calling autoFocus() must not
     * observe the callback nested inside that call.
     */
    public void autoFocus(final AutoFocusCallback cb) {
        checkNotReleased();
        if (cb == null) return;
        post(new Runnable() {
            @Override public void run() { cb.onAutoFocus(true, Camera.this); }
        });
    }

    /** AOSP: cancels an in-progress autofocus. Nothing is ever in progress on
     *  a fixed-focus camera, so there is nothing to cancel. */
    public void cancelAutoFocus() { }

    public void setAutoFocusMoveCallback(AutoFocusMoveCallback cb) {
        // AOSP only ever invokes this in the CONTINUOUS_* focus modes. We
        // advertise only FOCUS_MODE_FIXED, so it is correctly never called;
        // stored so a later focus implementation has the callback in hand.
        mAutoFocusMoveCallback = cb;
    }

    public void setZoomChangeListener(OnZoomChangeListener listener) {
        mZoomListener = listener;
    }

    /** AOSP: "This method is supported if isSmoothZoomSupported is true" and
     *  "@throws RuntimeException if the method fails". Zoom is not supported
     *  here (the bag says so), so the documented failure is what an app gets --
     *  which is better than silently doing nothing and leaving it waiting for
     *  an OnZoomChangeListener callback that will never arrive. */
    public void startSmoothZoom(int value) {
        throw new RuntimeException("Smooth zoom is not supported");
    }

    public void stopSmoothZoom() {
        throw new RuntimeException("Smooth zoom is not supported");
    }

    public void setFaceDetectionListener(FaceDetectionListener listener) {
        mFaceListener = listener;
    }

    /**
     * AOSP: "@throws IllegalArgumentException if the face detection is
     * unsupported" and "@throws RuntimeException if ... the face detection is
     * already running". Both checks are here, in AOSP's order (the
     * already-running check first).
     *
     * We do no face detection: AVFoundation can do it, but wiring it would
     * mean reporting face rectangles in camera1's (-1000..1000) sensor space
     * with the app's display orientation applied, and we do not do that work
     * -- so getMaxNumDetectedFaces() honestly reports 0 and this throws, which
     * is the documented contract for a device without the capability.
     */
    public void startFaceDetection() {
        if (mFaceDetectionRunning) {
            throw new RuntimeException("Face detection is already running");
        }
        if (getParameters().getMaxNumDetectedFaces() <= 0) {
            throw new IllegalArgumentException("Face detection is not supported");
        }
        mFaceDetectionRunning = true;
    }

    public void stopFaceDetection() {
        mFaceDetectionRunning = false;
    }

    public void setErrorCallback(ErrorCallback cb) { mErrorCallback = cb; }

    /** AOSP returns false when the shutter sound cannot be disabled (a legal
     *  requirement in some regions). A Mac plays no shutter sound, so there is
     *  nothing to refuse. */
    public boolean enableShutterSound(boolean enabled) {
        mShutterSoundEnabled = enabled;
        return true;
    }

    // ---- parameters ------------------------------------------------------

    /** AOSP returns a fresh Parameters snapshot of the native bag on every
     *  call; mutating it does nothing until setParameters. Same here. */
    public Parameters getParameters() {
        Parameters p = new Parameters();
        synchronized (this) {
            p.mMap.putAll(mBag);
        }
        return p;
    }

    /**
     * AOSP hands the flattened bag to the HAL, which applies what it can and
     * throws for a value it cannot. Here the two settings that actually reach
     * the device are the preview size and the frame rate, and they are applied
     * on the next startPreview -- which is also AOSP's rule, since a size
     * change requires the preview to be restarted.
     */
    public void setParameters(Parameters params) {
        checkNotReleased();
        if (params == null) {
            throw new NullPointerException("parameters is null");
        }
        synchronized (this) {
            mBag.putAll(params.mMap);
        }
    }

    // ---- preview pump ----------------------------------------------------

    private int mSessionW, mSessionH;

    private int[] currentSessionSize() {
        if (mSessionW > 0 && mSessionH > 0) return new int[] { mSessionW, mSessionH };
        // Before the session is configured, the honest answer is what the app
        // asked for, which is what AOSP's getPreviewSize() reports too.
        Size s = getParameters().getPreviewSize();
        return new int[] { s == null ? 0 : s.width, s == null ? 0 : s.height };
    }

    private int previewImageFormat() {
        String f;
        synchronized (this) { f = mBag.get(KEY_PREVIEW_FORMAT); }
        if (PIXEL_FORMAT_YUV420P.equals(f)) return ImageFormat.YV12;
        return ImageFormat.NV21;
    }

    /**
     * AOSP: start pushing preview frames. "@throws RuntimeException if
     * starting preview fails."
     *
     * The requested preview size and frame rate are handed to CameraBridge,
     * which picks the advertised format closest in area and clamps the fps into
     * its range -- so what comes back may not be exactly what was asked for.
     * That is why the bag is REWRITTEN from the session's real geometry
     * afterwards: getPreviewSize() must report the size the app will actually
     * receive, or every buffer it allocates from that number is the wrong size.
     */
    public void startPreview() {
        checkNotReleased();
        if (mPreviewRunning) return;

        Parameters p = getParameters();
        Size req = p.getPreviewSize();
        int reqFps = p.getPreviewFrameRate();
        int w = (req != null && req.width > 0) ? req.width : 0;
        int h = (req != null && req.height > 0) ? req.height : 0;

        if (!CameraBridge.configure(mSession, w, h, reqFps > 0 ? reqFps : 30)) {
            throw new RuntimeException("startPreview failed");
        }
        mSessionW = CameraBridge.getWidth(mSession);
        mSessionH = CameraBridge.getHeight(mSession);
        if (mSessionW > 0 && mSessionH > 0) {
            synchronized (this) {
                mBag.put(KEY_PREVIEW_SIZE, mSessionW + "x" + mSessionH);
                // The picture is captured from the same stream, so the picture
                // size an app reads back has to be the truth about what it will
                // get -- not the (possibly larger) size it requested.
                mBag.put(KEY_PICTURE_SIZE, mSessionW + "x" + mSessionH);
            }
        }
        if (!CameraBridge.start(mSession)) {
            throw new RuntimeException("startPreview failed");
        }

        SurfaceTexture st = mPreviewTexture;
        if (st != null) {
            st.macapkAttachCameraSource(mSession, mSessionW, mSessionH);
        }

        mPreviewRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { previewLoop(); }
        }, "macapk-camera1-preview");
        t.setDaemon(true);
        mPreviewThread = t;
        t.start();
    }

    /**
     * AOSP: stop pushing preview frames. Also the point at which any
     * outstanding preview callback stops being called, which is why the loop is
     * joined rather than merely flagged -- an app calls stopPreview() and then
     * frees the buffers it handed to addCallbackBuffer, and a frame delivered
     * after that would write into freed state.
     */
    public void stopPreview() {
        Thread t;
        synchronized (this) {
            mPreviewRunning = false;
            t = mPreviewThread;
            mPreviewThread = null;
        }
        if (t != null) {
            try {
                t.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SurfaceTexture st = mPreviewTexture;
        if (st != null) st.macapkDetachCameraSource();
        if (mSession != CameraBridge.NO_SESSION) CameraBridge.stop(mSession);
    }

    private void previewLoop() {
        long lastTexturePts = 0L;
        int[] argb = null;
        int[] dims = new int[2];
        Bitmap frameBitmap = null;

        while (mPreviewRunning) {
            try {
                // 1. Preview callbacks: the byte[] path. This is the one that
                //    matters most for the legacy corpus (barcode/QR scanners,
                //    OCR, card readers), so it runs first and gets the
                //    acquire/release pair to itself.
                PreviewCallback cb = mPreviewCallback;
                if (cb != null && CameraBridge.acquire(mSession)) {
                    byte[] out = null;
                    try {
                        out = takeCallbackBuffer();
                        if (out != null) {
                            if (!fillPreviewBuffer(out)) out = null;
                        }
                    } finally {
                        CameraBridge.release(mSession);
                    }
                    if (out != null) {
                        final PreviewCallback target = cb;
                        final byte[] data = out;
                        if (mOneShot) {
                            mPreviewCallback = null;
                            mOneShot = false;
                        }
                        post(new Runnable() {
                            @Override public void run() {
                                target.onPreviewFrame(data, Camera.this);
                            }
                        });
                    }
                }

                // 2. SurfaceTexture consumers: publish the frame's metadata and
                //    let the consumer's own latch pull the pixels, exactly as
                //    CameraCaptureSessionImpl does.
                SurfaceTexture st = mPreviewTexture;
                if (st != null) {
                    long pts = CameraBridge.latestFrameTimestamp(mSession);
                    if (pts != 0 && pts != lastTexturePts) {
                        lastTexturePts = pts;
                        st.macapkQueueFrame(pts,
                                mFrontFacing ? MTX_FLIP_V_FRONT : MTX_FLIP_V, 0);
                    }
                }

                // 3. SurfaceHolder preview: blit the newest frame onto the
                //    holder's canvas. Done on THIS thread, not the UI thread,
                //    because lockCanvas/unlockCanvasAndPost is designed for a
                //    dedicated drawing thread (it is how SurfaceView's own
                //    renderer path works).
                SurfaceHolder holder = mPreviewHolder;
                if (holder != null && mSessionW > 0 && mSessionH > 0) {
                    int need = mSessionW * mSessionH;
                    if (argb == null || argb.length < need) argb = new int[need];
                    long pts = CameraBridge.copyLatestArgb(mSession, argb, dims);
                    if (pts != 0 && dims[0] > 0 && dims[1] > 0) {
                        if (frameBitmap == null
                                || frameBitmap.getWidth() != dims[0]
                                || frameBitmap.getHeight() != dims[1]) {
                            frameBitmap = Bitmap.createBitmap(dims[0], dims[1],
                                    Bitmap.Config.ARGB_8888);
                        }
                        frameBitmap.setPixels(argb, 0, dims[0], 0, 0, dims[0], dims[1]);
                        blitToHolder(holder, frameBitmap);
                    }
                }
            } catch (Throwable t) {
                // A preview pump that throws must not take the process with it,
                // and must not spin: report it once through the app's own
                // ErrorCallback (which is what AOSP uses for exactly this) and
                // stop, rather than looping on a broken session.
                //
                // Clearing mPreviewRunning here is load-bearing, not tidiness:
                // startPreview() early-returns while it is set, so leaving it
                // true after the pump died would make every later
                // startPreview() a silent no-op and the app would wait forever
                // for frames with no second error to explain it.
                mPreviewRunning = false;
                reportError(CAMERA_ERROR_UNKNOWN);
                System.err.println("[" + TAG + "] preview loop stopped: " + t);
                return;
            }

            try {
                // ~60Hz poll. The native layer is the one pacing real frames;
                // this only bounds how stale a delivered frame can be.
                Thread.sleep(16L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Transform for SurfaceTexture consumers. Identical to
     *  CameraCaptureSessionImpl's MTX_FLIP_V and for the same reason: the
     *  capture buffer is top-down where GL is bottom-up, the Mac's sensor is
     *  unrotated, and there is no crop, so pure flip-V is exact for a
     *  back-facing camera (front-facing: MTX_FLIP_V_FRONT below). Column-major
     *  for glUniformMatrix4fv. */
    private static final float[] MTX_FLIP_V = {
        1f,  0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f,  0f, 1f, 0f,
        0f,  1f, 0f, 1f,
    };

    /** FRONT-facing cameras: flip-V plus the horizontal flip Android applies
     *  to a front camera's preview stream. On a device, the SurfaceTexture
     *  transform for a front camera mirrors the image (Camera.setDisplayOrientation
     *  documents the mirrored front preview), and consumers rely on it: WebRTC's
     *  Camera1Session/Camera2Session apply a second mirror to "undo the mirror
     *  that the OS 'helps' us with", and an app that wants a selfie-style view
     *  mirrors again in its renderer. With flip-V alone, that undo became a real
     *  mirror and cancelled the app's -- measured 2026-11-12 on OurFamilyWizard's
     *  Twilio preview (setMirror(true), shown unmirrored). camera2 applies the
     *  same matrix (CameraCaptureSessionImpl.MTX_FLIP_V_MIRROR_H) to front
     *  cameras under MIRROR_MODE_AUTO. Column-major:
     *  s' = 1 - s, t' = 1 - t. */
    private static final float[] MTX_FLIP_V_FRONT = {
        -1f,  0f, 0f, 0f,
         0f, -1f, 0f, 0f,
         0f,  0f, 1f, 0f,
         1f,  1f, 0f, 1f,
    };

    private void blitToHolder(SurfaceHolder holder, Bitmap frame) {
        android.graphics.Canvas c = holder.lockCanvas();
        if (c == null) return;
        try {
            Rect src = new Rect(0, 0, frame.getWidth(), frame.getHeight());
            Rect dst = new Rect(0, 0, c.getWidth(), c.getHeight());
            if (dst.right <= 0 || dst.bottom <= 0) {
                dst = new Rect(src);
            }
            if (mDisplayOrientation != 0) {
                c.save();
                c.rotate(mDisplayOrientation, dst.right / 2f, dst.bottom / 2f);
                c.drawBitmap(frame, src, dst, null);
                c.restore();
            } else {
                c.drawBitmap(frame, src, dst, null);
            }
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    /** With setPreviewCallbackWithBuffer the app owns the buffers and we may
     *  deliver nothing when it has handed us none -- AOSP says exactly that
     *  ("the callback will not be called until addCallbackBuffer is called").
     *  Otherwise we allocate, which is the plain setPreviewCallback contract
     *  (and the reason that form is documented as producing GC pressure). */
    private byte[] takeCallbackBuffer() {
        if (mWithBuffer) {
            synchronized (mCallbackBuffers) {
                return mCallbackBuffers.poll();
            }
        }
        int need = previewBufferSize();
        return need > 0 ? new byte[need] : null;
    }

    /**
     * Convert the held YUV_420_888 frame into the preview format the app asked
     * for.
     *
     * The native buffer is SEMIPLANAR (NV12-shaped): plane 0 is Y, and planes
     * 1 and 2 are two interleaved views of ONE chroma plane, one byte apart,
     * both with pixelStride 2 -- the layout YUV_420_888 explicitly permits, and
     * the reason nothing here may assume tight packing. Row strides are padded,
     * so every read steps by rowStride/pixelStride rather than by width.
     *
     *  * NV21 (AOSP's default, PIXEL_FORMAT_YUV420SP): Y plane, then V,U
     *    interleaved -- note the ORDER, V first. Getting that backwards
     *    produces an image with the red and blue casts swapped, which reads as
     *    "the camera works but the colours are wrong" and is easy to miss on a
     *    barcode scanner reading a greyscale image.
     *  * YV12 (PIXEL_FORMAT_YUV420P): fully planar Y, then Cr, then Cb, with
     *    AOSP's alignment rule -- yStride = align(width,16),
     *    cStride = align(yStride/2,16). The alignment is part of the published
     *    contract (Camera.Parameters.setPreviewFormat's javadoc spells out the
     *    arithmetic), so an app computing plane offsets from it must find the
     *    planes where it expects.
     */
    private boolean fillPreviewBuffer(byte[] out) {
        int w = mSessionW, h = mSessionH;
        if (w <= 0 || h <= 0) return false;

        ByteBuffer y = CameraBridge.planeBuffer(mSession, 0);
        ByteBuffer u = CameraBridge.planeBuffer(mSession, 1);
        ByteBuffer v = CameraBridge.planeBuffer(mSession, 2);
        if (y == null || u == null || v == null) return false;

        int yRow = CameraBridge.rowStride(mSession, 0);
        int uRow = CameraBridge.rowStride(mSession, 1);
        int vRow = CameraBridge.rowStride(mSession, 2);
        int uPix = CameraBridge.pixelStride(mSession, 1);
        int vPix = CameraBridge.pixelStride(mSession, 2);
        if (yRow <= 0 || uRow <= 0 || vRow <= 0 || uPix <= 0 || vPix <= 0) return false;

        int chromaW = (w + 1) / 2;
        int chromaH = (h + 1) / 2;

        if (previewImageFormat() == ImageFormat.YV12) {
            int yStride = align16(w);
            int cStride = align16(yStride / 2);
            int ySize = yStride * h;
            int cSize = cStride * chromaH;
            if (out.length < ySize + cSize * 2) return false;

            for (int row = 0; row < h; row++) {
                int src = row * yRow;
                int dst = row * yStride;
                for (int col = 0; col < w; col++) out[dst + col] = y.get(src + col);
            }
            // YV12 is Y, then Cr (V), then Cb (U).
            for (int row = 0; row < chromaH; row++) {
                int srcV = row * vRow;
                int dstV = ySize + row * cStride;
                for (int col = 0; col < chromaW; col++) {
                    out[dstV + col] = v.get(srcV + col * vPix);
                }
            }
            for (int row = 0; row < chromaH; row++) {
                int srcU = row * uRow;
                int dstU = ySize + cSize + row * cStride;
                for (int col = 0; col < chromaW; col++) {
                    out[dstU + col] = u.get(srcU + col * uPix);
                }
            }
            return true;
        }

        // NV21: tightly packed Y (w*h), then interleaved V,U.
        int ySize = w * h;
        if (out.length < ySize + chromaW * chromaH * 2) return false;
        for (int row = 0; row < h; row++) {
            int src = row * yRow;
            int dst = row * w;
            for (int col = 0; col < w; col++) out[dst + col] = y.get(src + col);
        }
        int dst = ySize;
        for (int row = 0; row < chromaH; row++) {
            int srcU = row * uRow;
            int srcV = row * vRow;
            for (int col = 0; col < chromaW; col++) {
                out[dst++] = v.get(srcV + col * vPix);
                out[dst++] = u.get(srcU + col * uPix);
            }
        }
        return true;
    }

    private static int align16(int v) { return (v + 15) & ~15; }

    // ---- still capture ---------------------------------------------------

    public void takePicture(ShutterCallback shutter, PictureCallback raw, PictureCallback jpeg) {
        takePicture(shutter, raw, null, jpeg);
    }

    /**
     * AOSP: "This method is only valid when preview is active" and "@throws
     * RuntimeException if start preview fails"; the preview is stopped once the
     * image is taken, and the app must call startPreview() again to resume.
     * Both rules are honoured here.
     *
     * The JPEG is a real encode of a real frame. Callbacks an app passes for a
     * format the device does not produce get null data, which is AOSP's own
     * contract ("the raw image callback ... may be null" / "data will be null
     * if the format is not supported"): we produce no RAW/DNG and no postview,
     * so those two receive null rather than a fabricated buffer.
     */
    public void takePicture(final ShutterCallback shutter, final PictureCallback raw,
            final PictureCallback postview, final PictureCallback jpeg) {
        checkNotReleased();
        if (!mPreviewRunning) {
            throw new RuntimeException("takePicture failed: preview is not running");
        }

        if (shutter != null) {
            post(new Runnable() {
                @Override public void run() { shutter.onShutter(); }
            });
        }

        byte[] jpegBytes = null;
        try {
            jpegBytes = captureJpeg();
        } catch (Throwable t) {
            System.err.println("[" + TAG + "] takePicture failed to encode: " + t);
        }

        // AOSP stops the preview once the picture is taken.
        stopPreview();

        if (raw != null) {
            post(new Runnable() {
                @Override public void run() { raw.onPictureTaken(null, Camera.this); }
            });
        }
        if (postview != null) {
            post(new Runnable() {
                @Override public void run() { postview.onPictureTaken(null, Camera.this); }
            });
        }
        if (jpeg != null) {
            final byte[] data = jpegBytes;
            post(new Runnable() {
                @Override public void run() { jpeg.onPictureTaken(data, Camera.this); }
            });
        }
    }

    private byte[] captureJpeg() {
        int w = mSessionW, h = mSessionH;
        if (w <= 0 || h <= 0) return null;
        int[] argb = new int[w * h];
        int[] dims = new int[2];
        if (CameraBridge.copyLatestArgb(mSession, argb, dims) == 0) return null;
        if (dims[0] <= 0 || dims[1] <= 0) return null;

        Bitmap bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, dims[0], 0, 0, dims[0], dims[1]);

        // Honour a requested picture size by RESAMPLING the captured frame,
        // which is what a real HAL does when the requested still size is not
        // the sensor's native one. The pixels stay real; only the scale
        // changes. If the app asked for the size we already have, no work.
        Parameters p = getParameters();
        Size want = p.getPictureSize();
        if (want != null && want.width > 0 && want.height > 0
                && (want.width != dims[0] || want.height != dims[1])) {
            bmp = Bitmap.createScaledBitmap(bmp, want.width, want.height, true);
        }

        int quality = p.getJpegQuality();
        if (quality <= 0 || quality > 100) quality = 90;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null;
        return bos.toByteArray();
    }

    // ---- callback plumbing -----------------------------------------------

    private void post(Runnable r) {
        Handler h = mEventHandler;
        if (h != null) {
            h.post(r);
        } else {
            // No Looper anywhere (a bare worker thread in a headless run).
            // Running inline is worse than not running at all only for
            // re-entrancy, and losing the callback entirely is worse still.
            r.run();
        }
    }

    private void reportError(final int code) {
        final ErrorCallback cb = mErrorCallback;
        if (cb == null) return;
        post(new Runnable() {
            @Override public void run() { cb.onError(code, Camera.this); }
        });
    }

    // ---- nested types ----------------------------------------------------

    public static class CameraInfo {
        public static final int CAMERA_FACING_BACK = 0;
        public static final int CAMERA_FACING_FRONT = 1;
        public int facing;
        public int orientation;
        public boolean canDisableShutterSound;
    }

    // AOSP declares Size as a NON-static inner class (Camera.java:2153
    // `public class Size`), unlike its sibling nested types CameraInfo
    // (:316) and Area (:2224) which ARE static. That distinction is
    // observable in bytecode: an app that constructs a Size gets the
    // synthetic outer-instance parameter, so the ctor descriptor is
    // (Landroid/hardware/Camera;II)V. Mirror AOSP exactly.
    public class Size {
        public int width;
        public int height;
        public Size(int w, int h) { width = w; height = h; }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof Size)) return false;
            Size s = (Size) obj;
            return width == s.width && height == s.height;
        }
        @Override public int hashCode() { return width * 32713 + height; }
    }

    /** AOSP Camera.Area (Camera.java:2224). Bounds are in the fixed
     *  (-1000,-1000)..(1000,1000) coordinate space of the camera field of
     *  view; weight ranges 1..1000. Consumed by
     *  Parameters.setFocusAreas / setMeteringAreas. */
    public static class Area {
        public Rect rect;
        public int weight;

        public Area(Rect rect, int weight) {
            this.rect = rect;
            this.weight = weight;
        }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof Area)) return false;
            Area a = (Area) obj;
            if (rect == null) {
                if (a.rect != null) return false;
            } else {
                if (!rect.equals(a.rect)) return false;
            }
            return weight == a.weight;
        }
    }

    /**
     * AOSP Camera.Face (Camera.java:1846) -- one face found by the camera's
     * own detector, delivered to FaceDetectionListener.
     *
     * Nothing here produces one (getMaxNumDetectedFaces() is 0 and
     * startFaceDetection() throws), but the type is REQUIRED: an app that
     * implements FaceDetectionListener has a method signature naming Face[],
     * and an app that declares a Face field fails to load without it -- which
     * happens whether or not face detection is ever switched on.
     *
     * The `id` default of -1 and the null eye/mouth points are AOSP's, and they
     * mean "this device does not report the optional fields". Note the class is
     * a plain data holder with PUBLIC mutable fields; an app constructs one in
     * its own tests.
     */
    public static class Face {
        public Face() { }

        /** Bounds in the (-1000,-1000)..(1000,1000) sensor space. */
        public Rect rect;
        /** 1..100; AOSP recommends filtering below 50. */
        public int score;
        /** -1 when the device does not track face identity. */
        public int id = -1;
        public android.graphics.Point leftEye = null;
        public android.graphics.Point rightEye = null;
        public android.graphics.Point mouth = null;
    }

    public interface PreviewCallback { void onPreviewFrame(byte[] data, Camera camera); }
    public interface AutoFocusCallback { void onAutoFocus(boolean success, Camera camera); }
    public interface ShutterCallback { void onShutter(); }
    public interface PictureCallback { void onPictureTaken(byte[] data, Camera camera); }
    public interface ErrorCallback { void onError(int error, Camera camera); }
    public interface AutoFocusMoveCallback { void onAutoFocusMoving(boolean start, Camera camera); }
    public interface OnZoomChangeListener {
        void onZoomChange(int zoomValue, boolean stopped, Camera camera);
    }
    /** AOSP Camera.FaceDetectionListener (Camera.java:1760). */
    public interface FaceDetectionListener {
        void onFaceDetection(Face[] faces, Camera camera);
    }

    // ---- Parameters ------------------------------------------------------
    //
    // AOSP models Parameters as a LinkedHashMap<String,String> the HAL fills,
    // with typed accessors layered on top; flatten()/unflatten() marshal it
    // across the native boundary. That is exactly the shape here, and the bag
    // is now populated from the REAL device (see populateBag), so every
    // accessor below answers about actual hardware rather than about an
    // absence.
    //
    // Where a capability is genuinely missing the key is absent and the AOSP
    // accessor's own documented default takes over -- 0 for the exposure
    // quartet and the max-num-areas getters, null for the -values lists. That
    // is why almost none of these methods needs a special case: AOSP already
    // wrote the "unsupported" behaviour into the accessor.

    public class Parameters {
        public static final String FOCUS_MODE_AUTO = "auto";
        public static final String FOCUS_MODE_CONTINUOUS_PICTURE = "continuous-picture";
        public static final String FOCUS_MODE_CONTINUOUS_VIDEO = "continuous-video";
        public static final String FOCUS_MODE_FIXED = "fixed";
        public static final String FOCUS_MODE_INFINITY = "infinity";
        public static final String FOCUS_MODE_MACRO = "macro";
        public static final String FOCUS_MODE_EDOF = "edof";
        public static final String FLASH_MODE_OFF = "off";
        public static final String FLASH_MODE_ON = "on";
        public static final String FLASH_MODE_AUTO = "auto";
        public static final String FLASH_MODE_RED_EYE = "red-eye";
        public static final String FLASH_MODE_TORCH = "torch";
        public static final String SCENE_MODE_AUTO = "auto";
        public static final String WHITE_BALANCE_AUTO = "auto";
        public static final String ANTIBANDING_AUTO = "auto";
        public static final String ANTIBANDING_50HZ = "50hz";
        public static final String ANTIBANDING_60HZ = "60hz";
        public static final String ANTIBANDING_OFF = "off";
        public static final String EFFECT_NONE = "none";

        private final java.util.LinkedHashMap<String, String> mMap =
            new java.util.LinkedHashMap<>();

        // ---- the bag -----------------------------------------------------

        public String get(String key) { return mMap.get(key); }

        public void set(String key, String value) {
            if (key == null || value == null) return;
            // AOSP rejects keys/values containing '=' or ';' because those are
            // the flatten() delimiters and would corrupt the marshalled form.
            if (key.indexOf('=') != -1 || key.indexOf(';') != -1
                    || key.indexOf(0) != -1) return;
            if (value.indexOf('=') != -1 || value.indexOf(';') != -1
                    || value.indexOf(0) != -1) return;
            mMap.put(key, value);
        }

        public void set(String key, int value) {
            mMap.put(key, Integer.toString(value));
        }

        /** AOSP Camera.java:2706 -- `mMap.remove(key)`. */
        public void remove(String key) { mMap.remove(key); }

        /** AOSP Camera.java:2791 -- the PUBLIC getInt(String), which has NO
         *  default and throws NumberFormatException for a missing or
         *  non-numeric key. That is not an oversight to soften: it is how an
         *  app probes for a vendor key it is not sure exists. Distinct from the
         *  private getInt(String,int) further down. */
        public int getInt(String key) {
            return Integer.parseInt(mMap.get(key));
        }

        public String flatten() {
            StringBuilder flattened = new StringBuilder(128);
            for (String k : mMap.keySet()) {
                flattened.append(k);
                flattened.append("=");
                flattened.append(mMap.get(k));
                flattened.append(";");
            }
            // AOSP chops the trailing semicolon unconditionally, which assumes a
            // HAL-populated (never empty) bag. Guard the empty case rather than
            // throwing StringIndexOutOfBoundsException.
            if (flattened.length() > 0) flattened.deleteCharAt(flattened.length() - 1);
            return flattened.toString();
        }

        public void unflatten(String flattened) {
            mMap.clear();
            if (flattened == null) return;
            for (String kv : splitOn(flattened, ';')) {
                int pos = kv.indexOf('=');
                if (pos == -1) continue;
                mMap.put(kv.substring(0, pos), kv.substring(pos + 1));
            }
        }

        // ---- sizes -------------------------------------------------------

        public Size getPreviewSize() { return strToSize(get(KEY_PREVIEW_SIZE)); }
        public void setPreviewSize(int width, int height) {
            set(KEY_PREVIEW_SIZE, width + "x" + height);
        }
        public List<Size> getSupportedPreviewSizes() {
            return splitSize(get(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX));
        }

        public Size getPictureSize() { return strToSize(get(KEY_PICTURE_SIZE)); }
        public void setPictureSize(int width, int height) {
            set(KEY_PICTURE_SIZE, width + "x" + height);
        }
        public List<Size> getSupportedPictureSizes() {
            return splitSize(get(KEY_PICTURE_SIZE + SUPPORTED_VALUES_SUFFIX));
        }

        public List<Size> getSupportedVideoSizes() {
            return splitSize(get(KEY_VIDEO_SIZE + SUPPORTED_VALUES_SUFFIX));
        }

        /** AOSP Camera.java:2900. */
        public Size getPreferredPreviewSizeForVideo() {
            return strToSize(get(KEY_PREFERRED_PREVIEW_SIZE_FOR_VIDEO));
        }

        /** AOSP Camera.java:2917 -- stores WIDTH and HEIGHT as two separate
         *  keys, not as one "WxH" string like the other size setters. Keep the
         *  asymmetry: getJpegThumbnailSize reads the pair back. */
        public void setJpegThumbnailSize(int width, int height) {
            set(KEY_JPEG_THUMBNAIL_WIDTH, width);
            set(KEY_JPEG_THUMBNAIL_HEIGHT, height);
        }
        public Size getJpegThumbnailSize() {
            return new Size(getInt(KEY_JPEG_THUMBNAIL_WIDTH),
                            getInt(KEY_JPEG_THUMBNAIL_HEIGHT));
        }
        public List<Size> getSupportedJpegThumbnailSizes() {
            return splitSize(get(KEY_JPEG_THUMBNAIL_SIZE + SUPPORTED_VALUES_SUFFIX));
        }

        // ---- formats -----------------------------------------------------

        public void setPreviewFormat(int pixelFormat) {
            String s = cameraFormatForPixelFormat(pixelFormat);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixelFormat);
            }
            set(KEY_PREVIEW_FORMAT, s);
        }
        public int getPreviewFormat() {
            return pixelFormatForCameraFormat(get(KEY_PREVIEW_FORMAT));
        }
        public List<Integer> getSupportedPreviewFormats() {
            String str = get(KEY_PREVIEW_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            List<String> split = split(str);
            if (split == null) return formats;
            for (String s : split) {
                int f = pixelFormatForCameraFormat(s);
                if (f == ImageFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        public void setPictureFormat(int pixelFormat) {
            String s = cameraFormatForPixelFormat(pixelFormat);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixelFormat);
            }
            set(KEY_PICTURE_FORMAT, s);
        }
        public int getPictureFormat() {
            return pixelFormatForCameraFormat(get(KEY_PICTURE_FORMAT));
        }
        /** AOSP Camera.java:3248. Note it returns an EMPTY list, not null,
         *  when the key is absent -- unlike the -values getters that go through
         *  split(). AOSP's own asymmetry; mirrored. */
        public List<Integer> getSupportedPictureFormats() {
            String str = get(KEY_PICTURE_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            List<String> split = split(str);
            if (split == null) return formats;
            for (String s : split) {
                int f = pixelFormatForCameraFormat(s);
                if (f == ImageFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        // ---- frame rate --------------------------------------------------

        public void setPreviewFpsRange(int min, int max) {
            set(KEY_PREVIEW_FPS_RANGE, "" + min + "," + max);
        }
        public void getPreviewFpsRange(int[] range) {
            if (range == null || range.length != 2) {
                throw new IllegalArgumentException(
                        "range must be an array with two elements.");
            }
            splitInt(get(KEY_PREVIEW_FPS_RANGE), range);
        }
        public List<int[]> getSupportedPreviewFpsRange() {
            return splitRange(get(KEY_PREVIEW_FPS_RANGE + SUPPORTED_VALUES_SUFFIX));
        }

        /** AOSP Camera.java:2991/3004/3016 -- the deprecated whole-fps form
         *  that predates the range API. */
        @Deprecated
        public void setPreviewFrameRate(int fps) { set(KEY_PREVIEW_FRAME_RATE, fps); }
        @Deprecated
        public int getPreviewFrameRate() { return getInt(KEY_PREVIEW_FRAME_RATE, 0); }
        @Deprecated
        public List<Integer> getSupportedPreviewFrameRates() {
            return splitInt(get(KEY_PREVIEW_FRAME_RATE + SUPPORTED_VALUES_SUFFIX));
        }

        // ---- jpeg / rotation / gps ---------------------------------------

        public void setJpegQuality(int quality) { set(KEY_JPEG_QUALITY, quality); }
        public int getJpegQuality() { return getInt(KEY_JPEG_QUALITY, 0); }
        public void setJpegThumbnailQuality(int quality) {
            set(KEY_JPEG_THUMBNAIL_QUALITY, quality);
        }
        public int getJpegThumbnailQuality() { return getInt(KEY_JPEG_THUMBNAIL_QUALITY); }

        public void setRotation(int rotation) {
            if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                set(KEY_ROTATION, Integer.toString(rotation));
            } else {
                throw new IllegalArgumentException("Invalid rotation=" + rotation);
            }
        }

        public void setGpsLatitude(double latitude) {
            set(KEY_GPS_LATITUDE, Double.toString(latitude));
        }
        public void setGpsLongitude(double longitude) {
            set(KEY_GPS_LONGITUDE, Double.toString(longitude));
        }
        public void setGpsAltitude(double altitude) {
            set(KEY_GPS_ALTITUDE, Double.toString(altitude));
        }
        public void setGpsTimestamp(long timestamp) {
            set(KEY_GPS_TIMESTAMP, Long.toString(timestamp));
        }
        public void setGpsProcessingMethod(String processing_method) {
            set(KEY_GPS_PROCESSING_METHOD, processing_method);
        }
        /** AOSP Camera.java:3415 -- removes all five keys, so a second picture
         *  does not silently inherit the first one's location. */
        public void removeGpsData() {
            remove(KEY_GPS_LATITUDE);
            remove(KEY_GPS_LONGITUDE);
            remove(KEY_GPS_ALTITUDE);
            remove(KEY_GPS_TIMESTAMP);
            remove(KEY_GPS_PROCESSING_METHOD);
        }

        // ---- modes -------------------------------------------------------

        public void setFocusMode(String value) { set(KEY_FOCUS_MODE, value); }
        public String getFocusMode() { return get(KEY_FOCUS_MODE); }
        public List<String> getSupportedFocusModes() {
            return split(get(KEY_FOCUS_MODE + SUPPORTED_VALUES_SUFFIX));
        }

        public void setFlashMode(String value) { set(KEY_FLASH_MODE, value); }
        public String getFlashMode() { return get(KEY_FLASH_MODE); }
        public List<String> getSupportedFlashModes() {
            return split(get(KEY_FLASH_MODE + SUPPORTED_VALUES_SUFFIX));
        }

        public void setSceneMode(String value) { set(KEY_SCENE_MODE, value); }
        public String getSceneMode() { return get(KEY_SCENE_MODE); }
        public List<String> getSupportedSceneModes() {
            return split(get(KEY_SCENE_MODE + SUPPORTED_VALUES_SUFFIX));
        }

        /** AOSP Camera.java:3451 -- setting white balance CLEARS the AWB lock,
         *  and it short-circuits when the value is unchanged so that an app
         *  re-applying the same parameters does not silently drop its lock.
         *  Both behaviours are observable through getAutoWhiteBalanceLock(). */
        public void setWhiteBalance(String value) {
            String oldValue = get(KEY_WHITE_BALANCE);
            if (same(value, oldValue)) return;
            set(KEY_WHITE_BALANCE, value);
            set(KEY_AUTO_WHITEBALANCE_LOCK, FALSE);
        }
        public String getWhiteBalance() { return get(KEY_WHITE_BALANCE); }
        public List<String> getSupportedWhiteBalance() {
            return split(get(KEY_WHITE_BALANCE + SUPPORTED_VALUES_SUFFIX));
        }

        public String getAntibanding() { return get(KEY_ANTIBANDING); }
        public void setAntibanding(String antibanding) { set(KEY_ANTIBANDING, antibanding); }
        public List<String> getSupportedAntibanding() {
            return split(get(KEY_ANTIBANDING + SUPPORTED_VALUES_SUFFIX));
        }

        public String getColorEffect() { return get(KEY_EFFECT); }
        public void setColorEffect(String value) { set(KEY_EFFECT, value); }
        public List<String> getSupportedColorEffects() {
            return split(get(KEY_EFFECT + SUPPORTED_VALUES_SUFFIX));
        }

        // ---- optics ------------------------------------------------------

        /** AOSP Camera.java:3685 -- `Float.parseFloat(get(KEY_FOCAL_LENGTH))`,
         *  with NO null guard, so a device that does not report a focal length
         *  raises NumberFormatException here. That is AOSP's behaviour and it
         *  is kept: returning a plausible 0f or 4f would be a fabricated optical
         *  constant, and apps that measure distance or compute a field of view
         *  from it would silently produce wrong numbers instead of failing
         *  where the missing datum actually is. */
        public float getFocalLength() {
            return Float.parseFloat(get(KEY_FOCAL_LENGTH));
        }
        public float getHorizontalViewAngle() {
            return Float.parseFloat(get(KEY_HORIZONTAL_VIEW_ANGLE));
        }
        public float getVerticalViewAngle() {
            return Float.parseFloat(get(KEY_VERTICAL_VIEW_ANGLE));
        }

        /** AOSP Camera.java:4012. The three elements are near, optimal and far
         *  focus distance in metres. The argument check is AOSP's; with no
         *  focus-distances key, splitFloat leaves the array untouched, which is
         *  AOSP's behaviour for a device that does not report them. */
        public void getFocusDistances(float[] output) {
            if (output == null || output.length != 3) {
                throw new IllegalArgumentException(
                        "output must be a float array with three elements.");
            }
            splitFloat(get(KEY_FOCUS_DISTANCES), output);
        }

        // ---- zoom --------------------------------------------------------

        public boolean isZoomSupported() { return TRUE.equals(get(KEY_ZOOM_SUPPORTED)); }
        public int getMaxZoom() { return getInt(KEY_MAX_ZOOM, 0); }
        public void setZoom(int value) { set(KEY_ZOOM, value); }
        public int getZoom() { return getInt(KEY_ZOOM, 0); }
        public List<Integer> getZoomRatios() { return splitInt(get(KEY_ZOOM_RATIOS)); }
        public boolean isSmoothZoomSupported() {
            return TRUE.equals(get(KEY_SMOOTH_ZOOM_SUPPORTED));
        }

        // ---- exposure / locks --------------------------------------------

        public int getExposureCompensation() { return getInt(KEY_EXPOSURE_COMPENSATION, 0); }
        public void setExposureCompensation(int value) {
            set(KEY_EXPOSURE_COMPENSATION, value);
        }
        /** AOSP: "If both this method and getMinExposureCompensation return 0,
         *  exposure compensation is not supported." */
        public int getMaxExposureCompensation() {
            return getInt(KEY_MAX_EXPOSURE_COMPENSATION, 0);
        }
        public int getMinExposureCompensation() {
            return getInt(KEY_MIN_EXPOSURE_COMPENSATION, 0);
        }
        public float getExposureCompensationStep() {
            return getFloat(KEY_EXPOSURE_COMPENSATION_STEP, 0);
        }

        public void setAutoExposureLock(boolean toggle) {
            set(KEY_AUTO_EXPOSURE_LOCK, toggle ? TRUE : FALSE);
        }
        public boolean getAutoExposureLock() {
            return TRUE.equals(get(KEY_AUTO_EXPOSURE_LOCK));
        }
        public boolean isAutoExposureLockSupported() {
            return TRUE.equals(get(KEY_AUTO_EXPOSURE_LOCK_SUPPORTED));
        }

        public void setAutoWhiteBalanceLock(boolean toggle) {
            set(KEY_AUTO_WHITEBALANCE_LOCK, toggle ? TRUE : FALSE);
        }
        public boolean getAutoWhiteBalanceLock() {
            return TRUE.equals(get(KEY_AUTO_WHITEBALANCE_LOCK));
        }
        public boolean isAutoWhiteBalanceLockSupported() {
            return TRUE.equals(get(KEY_AUTO_WHITEBALANCE_LOCK_SUPPORTED));
        }

        // ---- areas -------------------------------------------------------

        /** AOSP: "If the value is 0, focus area is not supported." */
        public int getMaxNumFocusAreas() { return getInt(KEY_MAX_NUM_FOCUS_AREAS, 0); }
        /** AOSP returns splitArea(...), i.e. NULL when unset -- "a null focus
         *  area list means the driver is free to select focus targets as it
         *  wants". An empty list would mean something different. */
        public List<Area> getFocusAreas() { return splitArea(get(KEY_FOCUS_AREAS)); }
        public void setFocusAreas(List<Area> focusAreas) {
            set(KEY_FOCUS_AREAS, flattenAreas(focusAreas));
        }

        public int getMaxNumMeteringAreas() { return getInt(KEY_MAX_NUM_METERING_AREAS, 0); }
        public List<Area> getMeteringAreas() { return splitArea(get(KEY_METERING_AREAS)); }
        public void setMeteringAreas(List<Area> meteringAreas) {
            set(KEY_METERING_AREAS, flattenAreas(meteringAreas));
        }

        // ---- video / faces -----------------------------------------------

        public void setVideoStabilization(boolean toggle) {
            set(KEY_VIDEO_STABILIZATION, toggle ? TRUE : FALSE);
        }
        public boolean getVideoStabilization() {
            return TRUE.equals(get(KEY_VIDEO_STABILIZATION));
        }
        public boolean isVideoStabilizationSupported() {
            return TRUE.equals(get(KEY_VIDEO_STABILIZATION_SUPPORTED));
        }

        /** AOSP Camera.java:4217. */
        public boolean isVideoSnapshotSupported() {
            return TRUE.equals(get(KEY_VIDEO_SNAPSHOT_SUPPORTED));
        }

        /** AOSP Camera.java:4164 reads the HARDWARE key only -- not the
         *  software one -- so a device with software-only detection reports 0
         *  here. Mirrored exactly. */
        public int getMaxNumDetectedFaces() {
            return getInt(KEY_MAX_NUM_DETECTED_FACES_HW, 0);
        }

        public void setRecordingHint(boolean hint) {
            set(KEY_RECORDING_HINT, hint ? TRUE : FALSE);
        }

        // ---- AOSP private helpers ----------------------------------------

        private boolean same(String s1, String s2) {
            if (s1 == null && s2 == null) return true;
            if (s1 != null && s1.equals(s2)) return true;
            return false;
        }

        /** AOSP's private getInt(String,int): the bag value, or the default.
         *  AOSP catches only NumberFormatException because a real HAL always
         *  populates the key; with a key genuinely absent the value is null and
         *  Integer.parseInt(null) raises NPE, so that is caught too. The
         *  documented contract for an absent key is the default, never an
         *  exception. */
        private int getInt(String key, int defaultValue) {
            try {
                return Integer.parseInt(mMap.get(key));
            } catch (NumberFormatException | NullPointerException ex) {
                return defaultValue;
            }
        }
        private float getFloat(String key, float defaultValue) {
            try {
                return Float.parseFloat(mMap.get(key));
            } catch (NumberFormatException | NullPointerException ex) {
                return defaultValue;
            }
        }

        /** AOSP: "Splits a comma delimited string to an ArrayList of String.
         *  Return null if the passing string is null." */
        private ArrayList<String> split(String str) {
            if (str == null) return null;
            return new ArrayList<>(java.util.Arrays.asList(splitOn(str, ',')));
        }

        /** AOSP's ArrayList<Integer> form -- null for a null string AND for an
         *  empty result, which is why an app may not assume non-null. */
        private ArrayList<Integer> splitInt(String str) {
            if (str == null) return null;
            ArrayList<Integer> out = new ArrayList<>();
            for (String s : splitOn(str, ',')) {
                if (s.isEmpty()) continue;
                out.add(Integer.parseInt(s));
            }
            if (out.size() == 0) return null;
            return out;
        }

        /** AOSP's int[] out-parameter form: a null string leaves the array
         *  UNTOUCHED rather than zeroing it. */
        private void splitInt(String str, int[] output) {
            if (str == null) return;
            int index = 0;
            for (String s : splitOn(str, ',')) {
                if (index >= output.length) break;
                output[index++] = Integer.parseInt(s);
            }
        }

        private void splitFloat(String str, float[] output) {
            if (str == null) return;
            int index = 0;
            for (String s : splitOn(str, ',')) {
                if (index >= output.length) break;
                output[index++] = Float.parseFloat(s);
            }
        }

        /** AOSP: parses "480x320" into a Size; null in, null out. */
        private Size strToSize(String str) {
            if (str == null) return null;
            int pos = str.indexOf('x');
            if (pos != -1) {
                try {
                    return new Size(Integer.parseInt(str.substring(0, pos)),
                                    Integer.parseInt(str.substring(pos + 1)));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private ArrayList<Size> splitSize(String str) {
            if (str == null) return null;
            ArrayList<Size> sizeList = new ArrayList<>();
            for (String s : splitOn(str, ',')) {
                Size size = strToSize(s);
                if (size != null) sizeList.add(size);
            }
            if (sizeList.size() == 0) return null;
            return sizeList;
        }

        /** AOSP splitRange: "(10000,26623),(10000,30000)" -> list of int[2].
         *  Null for a null or malformed string. */
        private ArrayList<int[]> splitRange(String str) {
            if (str == null || str.length() == 0 || str.charAt(0) != '('
                    || str.charAt(str.length() - 1) != ')') {
                return null;
            }
            ArrayList<int[]> rangeList = new ArrayList<>();
            int endIndex, fromIndex = 1;
            do {
                int[] range = new int[2];
                endIndex = str.indexOf("),(", fromIndex);
                if (endIndex == -1) endIndex = str.length() - 1;
                if (!splitIntExact(str.substring(fromIndex, endIndex), range)) return null;
                rangeList.add(range);
                fromIndex = endIndex + 3;
            } while (endIndex != str.length() - 1);
            if (rangeList.size() == 0) return null;
            return rangeList;
        }

        /** AOSP `splitArea`: parses "(l,t,r,b,weight),(l,t,r,b,weight)". Returns
         *  null for a null/malformed string, and also for the single all-zero
         *  area the HAL uses to mean "no area selected". */
        private ArrayList<Area> splitArea(String str) {
            if (str == null || str.length() == 0 || str.charAt(0) != '('
                    || str.charAt(str.length() - 1) != ')') {
                return null;
            }
            ArrayList<Area> result = new ArrayList<>();
            int endIndex, fromIndex = 1;
            int[] array = new int[5];
            do {
                endIndex = str.indexOf("),(", fromIndex);
                if (endIndex == -1) endIndex = str.length() - 1;
                if (!splitIntExact(str.substring(fromIndex, endIndex), array)) return null;
                Rect rect = new Rect(array[0], array[1], array[2], array[3]);
                result.add(new Area(rect, array[4]));
                fromIndex = endIndex + 3;
            } while (endIndex != str.length() - 1);

            if (result.size() == 0) return null;
            if (result.size() == 1) {
                Area area = result.get(0);
                Rect rect = area.rect;
                if (rect.left == 0 && rect.top == 0 && rect.right == 0
                        && rect.bottom == 0 && area.weight == 0) {
                    return null;
                }
            }
            return result;
        }

        private String cameraFormatForPixelFormat(int pixel_format) {
            switch (pixel_format) {
                case ImageFormat.NV16:      return PIXEL_FORMAT_YUV422SP;
                case ImageFormat.NV21:      return PIXEL_FORMAT_YUV420SP;
                case ImageFormat.YUY2:      return PIXEL_FORMAT_YUV422I;
                case ImageFormat.YV12:      return PIXEL_FORMAT_YUV420P;
                case ImageFormat.RGB_565:   return PIXEL_FORMAT_RGB565;
                case ImageFormat.JPEG:      return PIXEL_FORMAT_JPEG;
                default:                    return null;
            }
        }

        private int pixelFormatForCameraFormat(String format) {
            if (format == null) return ImageFormat.UNKNOWN;
            if (format.equals(PIXEL_FORMAT_YUV422SP)) return ImageFormat.NV16;
            if (format.equals(PIXEL_FORMAT_YUV420SP)) return ImageFormat.NV21;
            if (format.equals(PIXEL_FORMAT_YUV422I))  return ImageFormat.YUY2;
            if (format.equals(PIXEL_FORMAT_YUV420P))  return ImageFormat.YV12;
            if (format.equals(PIXEL_FORMAT_RGB565))   return ImageFormat.RGB_565;
            if (format.equals(PIXEL_FORMAT_JPEG))     return ImageFormat.JPEG;
            return ImageFormat.UNKNOWN;
        }
    }

    // ---- shared string helpers (outside Parameters so both halves see them)

    private static String[] splitOn(String str, char sep) {
        return str.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
    }

    /** Parses exactly output.length comma-separated ints; false if the count
     *  does not match, which is how AOSP's splitArea/splitRange reject a
     *  malformed group rather than silently accepting a short one. */
    private static boolean splitIntExact(String str, int[] output) {
        String[] parts = splitOn(str, ',');
        if (parts.length != output.length) return false;
        try {
            for (int i = 0; i < parts.length; i++) {
                output[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException ex) { return false; }
        return true;
    }

    /** AOSP's private `set(String, List<Area>)` marshals the area list back
     *  into the "(l,t,r,b,weight),..." bag form; a null list is stored as
     *  the single all-zero area ("(0,0,0,0,0)"). */
    private static String flattenAreas(List<Area> areas) {
        if (areas == null) return "(0,0,0,0,0)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < areas.size(); i++) {
            Area a = areas.get(i);
            if (i != 0) sb.append(',');
            sb.append('(').append(a.rect.left).append(',').append(a.rect.top)
              .append(',').append(a.rect.right).append(',').append(a.rect.bottom)
              .append(',').append(a.weight).append(')');
        }
        return sb.toString();
    }
}
