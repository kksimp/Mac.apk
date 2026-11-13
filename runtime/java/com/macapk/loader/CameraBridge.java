// CameraBridge -- JNI seam to the macOS AVFoundation capture engine
// (runtime/src/camera_capture.mm).
//
// This is the layer BELOW android.hardware.camera2. It deliberately exposes no
// camera2 types: a camera2 implementation (CameraManager / CameraDevice /
// CameraCaptureSession / ImageReader) is written on top of these primitives,
// and nothing here needs to change when it lands.
//
// Native methods are bound at boot via RegisterNatives
// (native_methods.cpp -> camera::RegisterCameraBridgeMethods), because the JVM
// does not auto-resolve Java_* symbols from the main executable on macOS. Same
// pattern as NativeAudio and NativeSqlite.
//
// HONESTY RULE: this class reports a camera only when AVFoundation reports one,
// and reports a frame only once one has actually been delivered. Nothing here
// declares android.hardware.camera; an app that is handed a camera id and then
// never receives a frame fails deeper and more confusingly than one that is
// told there is no camera. isAvailable() and framesDelivered() exist so a
// caller can check rather than assume.
//
// THREADING: every method is safe to call from any thread. The native side
// guards its frame slot with a mutex. acquire()/release() are the exception
// that needs discipline, not thread-safety: see the frame contract below.
//
// FRAME CONTRACT (mirrors ImageReader.acquireLatestImage() / Image.close()):
//
//     if (CameraBridge.acquire(h)) {
//         try {
//             ByteBuffer y = CameraBridge.planeBuffer(h, 0);
//             int rowStride = CameraBridge.rowStride(h, 0);
//             ...  // read the pixels HERE, or copy them out
//         } finally {
//             CameraBridge.release(h);   // buffers are dead after this
//         }
//     }
//
// The ByteBuffers are DIRECT views onto the locked native capture buffer -- no
// copy is made. They are valid only between acquire() and release(). Touching
// one after release() reads freed memory, exactly as touching an
// android.media.Image after close() does on real Android. Only one frame may
// be held at a time; acquire() returns false if the previous one is still held.
package com.macapk.loader;

import java.nio.ByteBuffer;

public final class CameraBridge {
    private CameraBridge() {}

    // ---- Constants mirrored from AOSP -----------------------------------

    /** android.graphics.ImageFormat.YUV_420_888. Verified against AOSP. */
    public static final int FORMAT_YUV_420_888 = 0x23;
    /** android.graphics.ImageFormat.JPEG. */
    public static final int FORMAT_JPEG = 0x100;

    /** android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT. */
    public static final int FACING_FRONT = 0;
    /** LENS_FACING_BACK. Never reported on a Mac, kept for completeness. */
    public static final int FACING_BACK = 1;
    /** LENS_FACING_EXTERNAL -- what Android reports for a USB/UVC camera. */
    public static final int FACING_EXTERNAL = 2;

    // Mirrors AVAuthorizationStatus. The values are AVFoundation's.
    public static final int AUTH_NOT_DETERMINED = 0;
    public static final int AUTH_RESTRICTED     = 1;
    public static final int AUTH_DENIED         = 2;
    public static final int AUTH_AUTHORIZED     = 3;

    /** Number of planes in a YUV_420_888 frame. Always 3, per the AOSP spec. */
    public static final int PLANE_COUNT = 3;

    /** Returned by open() when no session could be created. */
    public static final long NO_SESSION = 0L;

    // ---- Availability ----------------------------------------------------

    /**
     * Whether the native camera layer is actually bound. False when the
     * runtime was built without camera_capture.mm, or when its natives have
     * not been registered yet -- in which case every method here degrades to
     * "no cameras" rather than throwing UnsatisfiedLinkError into app code.
     *
     * <p>A success is latched permanently. A FAILURE is deliberately NOT
     * latched: the only way this fails is if the class is touched before
     * RegisterAllNativeMethods has run, which is an ordering condition that
     * resolves itself. Caching a false there would disable the camera for the
     * whole run because of a transient startup race.
     */
    public static boolean isAvailable() {
        if (sAvailable) return true;
        try {
            nativeDeviceCount();
            sAvailable = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static volatile boolean sAvailable;

    // ---- Enumeration -----------------------------------------------------
    //
    // Verified on macOS 26 (2026-08-10): device discovery and format
    // enumeration work with authorization status NotDetermined, from a process
    // with no bundle Info.plist, WITHOUT raising a TCC prompt and without being
    // killed. So these are safe to call eagerly, including in a headless
    // runtime run. Only open() touches the protected resource.

    /** Number of cameras. 0 when there is none, or when access is denied. */
    public static int getDeviceCount() {
        return isAvailable() ? nativeDeviceCount() : 0;
    }

    /** Stable unique id, suitable for use as a camera2 camera id string. */
    public static String getDeviceId(int index) {
        return isAvailable() ? nativeDeviceId(index) : null;
    }

    /** Human-readable name, e.g. "FaceTime HD Camera". */
    public static String getDeviceName(int index) {
        return isAvailable() ? nativeDeviceName(index) : null;
    }

    /** One of FACING_*. A Mac's built-in camera faces the user, so FRONT. */
    public static int getDeviceFacing(int index) {
        return isAvailable() ? nativeDeviceFacing(index) : -1;
    }

    /**
     * camera2's SENSOR_ORIENTATION / Camera.CameraInfo.orientation, in degrees.
     *
     * AOSP defines it relative to the device's NATURAL orientation: the
     * clockwise rotation that makes the sensor image upright on the screen in
     * that orientation. Apps then combine it with Display.getRotation():
     * front-facing (sensor + displayDegrees) % 360, back-facing
     * (sensor - displayDegrees + 360) % 360 (Camera.setDisplayOrientation's
     * documented formula; WebRTC's Camera1Session/Camera2Session use the same).
     *
     * A Mac's camera image is upright on the Mac's screen whatever shape the
     * Mac.apk window has, because the window's orientation is virtual. But
     * Display.getRotation() reports ROTATION_90 for a landscape-shaped window
     * (the natural orientation it models is portrait). Reporting 0 here, as
     * this used to, therefore made every app rotate an already-upright image
     * by 90 degrees in landscape -- measured 2026-11-12 on OurFamilyWizard's
     * Twilio camera preview. The answer that is true for this device is the
     * one that cancels the reported display rotation, so the combined
     * rotation is 0 in both window shapes. For the front camera in a
     * landscape window that is 270, a phone's usual front-camera value.
     * camera2's transform does not yet match it; see CameraBackend.
     *
     * ⚠ AOSP treats this value as fixed per camera; here it follows the
     * window shape, read when asked. An app that caches it and then sees the
     * window rotate (Cmd+R) keeps the stale value -- the same limitation as
     * an app that caches Display.getRotation().
     */
    public static int getSensorOrientation(int index) {
        if (!isAvailable()) return 0;
        int base = nativeDeviceSensorOrientation(index);
        int displayDegrees = 0;
        try {
            // Surface.ROTATION_0..270 are 0..3, so x90 gives degrees.
            displayDegrees = android.view.Display.macapkRotation() * 90;
        } catch (Throwable ignored) { }
        boolean front = nativeDeviceFacing(index) == FACING_FRONT;
        int compensation = front ? (360 - displayDegrees) % 360 : displayDegrees;
        return (base + compensation) % 360;
    }

    /** Number of (resolution, frame-rate range) entries the device advertises. */
    public static int getFormatCount(int index) {
        return isAvailable() ? nativeFormatCount(index) : 0;
    }

    public static int getFormatWidth(int device, int format) {
        return isAvailable() ? nativeFormatWidth(device, format) : 0;
    }

    public static int getFormatHeight(int device, int format) {
        return isAvailable() ? nativeFormatHeight(device, format) : 0;
    }

    public static double getFormatMinFps(int device, int format) {
        return isAvailable() ? nativeFormatMinFps(device, format) : 0;
    }

    public static double getFormatMaxFps(int device, int format) {
        return isAvailable() ? nativeFormatMaxFps(device, format) : 0;
    }

    // ---- Authorization ---------------------------------------------------

    /** Current TCC authorization: one of AUTH_*. Never prompts, never blocks. */
    public static int getAuthStatus() {
        return isAvailable() ? nativeAuthStatus() : AUTH_DENIED;
    }

    /**
     * Kick off the system permission prompt. NON-BLOCKING: it returns
     * immediately and the user's answer arrives later, so poll
     * {@link #getAuthStatus()} rather than assuming a result. Blocking here
     * would deadlock an app that requested permission from its UI thread,
     * which is what an Android app does.
     *
     * <p>No-op when the status is already decided -- macOS does not re-prompt.
     */
    public static void requestAuth() {
        if (isAvailable()) nativeRequestAuth();
    }

    // ---- Session ---------------------------------------------------------

    /**
     * Open a capture session on a device. This is the call that touches the
     * protected resource and so may raise the TCC prompt.
     *
     * @return a session handle, or {@link #NO_SESSION} when the device does not
     *         exist or access is denied. Never blocks indefinitely.
     */
    public static long open(int index) {
        return isAvailable() ? nativeOpen(index) : NO_SESSION;
    }

    /**
     * Request a capture size and frame rate. Picks the advertised format
     * closest in area to the request and clamps fps into its supported range,
     * so a request that does not match exactly still yields a working session.
     * Call before {@link #start}, then read back {@link #getWidth}/
     * {@link #getHeight} -- those are what the app will actually receive.
     */
    public static boolean configure(long session, int width, int height, int fps) {
        return session != NO_SESSION && nativeConfigure(session, width, height, fps);
    }

    /** Negotiated width. Valid after configure(); this is the truth, not the request. */
    public static int getWidth(long session) {
        return session == NO_SESSION ? 0 : nativeWidth(session);
    }

    /** Negotiated height. */
    public static int getHeight(long session) {
        return session == NO_SESSION ? 0 : nativeHeight(session);
    }

    public static boolean start(long session) {
        return session != NO_SESSION && nativeStart(session);
    }

    public static void stop(long session) {
        if (session != NO_SESSION) nativeStop(session);
    }

    /** Tear down. Releases any held frame first, so it is safe to call anytime. */
    public static void close(long session) {
        if (session != NO_SESSION) nativeClose(session);
    }

    /**
     * Frames AVFoundation has delivered since start(), including ones dropped
     * because the consumer did not drain in time. A counter that advances is
     * the only honest proof that capture is actually live -- use it rather than
     * assuming a started session is producing frames.
     */
    public static long framesDelivered(long session) {
        return session == NO_SESSION ? 0L : nativeFramesDelivered(session);
    }

    // ---- Frame handoff ---------------------------------------------------

    /**
     * Take the most recent frame, dropping any older undrained one -- the same
     * "latest wins" policy as ImageReader.acquireLatestImage().
     *
     * @return false when no frame has arrived yet, or when one is already held.
     *         Must be paired with {@link #release} in a finally block.
     */
    public static boolean acquire(long session) {
        return session != NO_SESSION && nativeAcquire(session);
    }

    /** Release the acquired frame. All its plane buffers die here. */
    public static void release(long session) {
        if (session != NO_SESSION) nativeRelease(session);
    }

    /**
     * A direct, zero-copy ByteBuffer over one plane of the held frame.
     * Plane 0 is Y, plane 1 is U (Cb), plane 2 is V (Cr), matching the AOSP
     * YUV_420_888 guarantee on plane ordering.
     *
     * <p>Planes 1 and 2 are two interleaved views of the SAME native chroma
     * plane, one byte apart, both with a pixel stride of 2 -- the semiplanar
     * (NV12) case the YUV_420_888 spec explicitly allows. Always step by
     * {@link #pixelStride} and {@link #rowStride}; never assume the buffer is
     * tightly packed, because the capture buffer's rows are padded.
     *
     * <p>Valid only until {@link #release}. Null when no frame is held.
     */
    public static ByteBuffer planeBuffer(long session, int plane) {
        return session == NO_SESSION ? null : nativePlaneBuffer(session, plane);
    }

    /** Bytes between the starts of consecutive rows -- Plane.getRowStride(). */
    public static int rowStride(long session, int plane) {
        return session == NO_SESSION ? 0 : nativeRowStride(session, plane);
    }

    /**
     * Bytes between consecutive samples in a row -- Plane.getPixelStride().
     * 1 for the Y plane and 2 for both chroma planes on this backend.
     */
    public static int pixelStride(long session, int plane) {
        return session == NO_SESSION ? 0 : nativePixelStride(session, plane);
    }

    /**
     * Frame timestamp in nanoseconds, for Image.getTimestamp(). Monotonic host
     * clock, not comparable with other subsystems -- which is exactly camera2's
     * TIMESTAMP_SOURCE_UNKNOWN contract, so that is what should be reported.
     */
    public static long frameTimestamp(long session) {
        return session == NO_SESSION ? 0L : nativeFrameTimestamp(session);
    }

    /** Monotonically increasing counter of ACQUIRED frames (not delivered ones). */
    public static long frameNumber(long session) {
        return session == NO_SESSION ? 0L : nativeFrameNumber(session);
    }

    // ---- Newest-frame RGBA copy (the SurfaceTexture consumer path) -------
    //
    // Independent of acquire()/release(): these read a session-held reference
    // to the NEWEST delivered frame, so a SurfaceTexture latch never steals a
    // frame from an ImageReader in the same session and never fails because an
    // Image is outstanding. The NV12 -> RGB conversion happens in native code,
    // where the CVPixelBuffer row strides are still authoritative (CoreVideo
    // pads rows; a row offset must never be computed from the width).
    //
    // Row 0 of the output is the TOP image row -- Android buffer memory
    // layout -- which is why the transform matrix handed to SurfaceTexture
    // consumers is AOSP's mtxFlipV (GLConsumer.cpp: the flip maps GL
    // convention to "(0, 0) is the top-left corner").

    /** PTS (ns) of the newest delivered frame, 0 when none has arrived.
     *  Advances with every delivery; cheap enough to poll. */
    public static long latestFrameTimestamp(long session) {
        return session == NO_SESSION ? 0L : nativeLatestTimestamp(session);
    }

    /**
     * Convert the newest frame to RGBA8888 bytes (memory order R,G,B,A; rows
     * tightly packed at width*4 -- glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE)
     * ready).
     *
     * @param dst  a DIRECT ByteBuffer; capacity is read from the buffer itself
     * @param dims int[2] out-parameter, set to the frame's width/height
     *             whenever a frame exists (including on a too-small failure,
     *             so the caller can size the buffer and retry)
     * @return the frame's PTS in ns (clamped to >= 1), or 0 when no frame has
     *         arrived or the buffer is too small
     */
    public static long copyLatestRgba(long session, ByteBuffer dst, int[] dims) {
        return session == NO_SESSION ? 0L : nativeCopyLatestRgba(session, dst, dims);
    }

    /**
     * Same conversion packed as 0xAARRGGBB ints (Bitmap.setPixels order,
     * alpha always 0xFF). dst length is in pixels.
     */
    public static long copyLatestArgb(long session, int[] dst, int[] dims) {
        return session == NO_SESSION ? 0L : nativeCopyLatestArgb(session, dst, dims);
    }

    // ---- natives ---------------------------------------------------------

    private static native int    nativeDeviceCount();
    private static native String nativeDeviceId(int index);
    private static native String nativeDeviceName(int index);
    private static native int    nativeDeviceFacing(int index);
    private static native int    nativeDeviceSensorOrientation(int index);

    private static native int    nativeFormatCount(int index);
    private static native int    nativeFormatWidth(int device, int format);
    private static native int    nativeFormatHeight(int device, int format);
    private static native double nativeFormatMinFps(int device, int format);
    private static native double nativeFormatMaxFps(int device, int format);

    private static native int    nativeAuthStatus();
    private static native void   nativeRequestAuth();

    private static native long   nativeOpen(int index);
    private static native boolean nativeConfigure(long session, int w, int h, int fps);
    private static native int    nativeWidth(long session);
    private static native int    nativeHeight(long session);
    private static native boolean nativeStart(long session);
    private static native void   nativeStop(long session);
    private static native void   nativeClose(long session);
    private static native long   nativeFramesDelivered(long session);

    private static native boolean    nativeAcquire(long session);
    private static native void       nativeRelease(long session);
    private static native ByteBuffer nativePlaneBuffer(long session, int plane);
    private static native int        nativeRowStride(long session, int plane);
    private static native int        nativePixelStride(long session, int plane);
    private static native long       nativeFrameTimestamp(long session);
    private static native long       nativeFrameNumber(long session);

    private static native long nativeLatestTimestamp(long session);
    private static native long nativeCopyLatestRgba(long session, ByteBuffer dst, int[] dims);
    private static native long nativeCopyLatestArgb(long session, int[] dst, int[] dims);
}
