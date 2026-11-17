// android.hardware.camera2.params.StreamConfigurationMap (API 21; the
// high-speed queries API 23).
//
// The per-camera table of which output/input formats, sizes, frame durations
// and stall durations a camera supports. Apps read it out of
// CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP before choosing a
// preview size, so this is the class that decides what resolution the user's
// preview actually runs at.
//
// ---------------------------------------------------------------------------
// WHERE THE DATA COMES FROM
// ---------------------------------------------------------------------------
// Every value here is derived from AVFoundation's own advertised formats, read
// through CameraBackend.formats() -> CameraBridge.getFormat*(). Nothing is a
// constant table and nothing is invented: a size appears here only because
// AVCaptureDevice.formats listed it. An instance is built exactly one way, by
// macapkCreate() from CameraBackend, and the no-camera case never reaches this
// class at all because getCameraCharacteristics throws first.
//
// ---------------------------------------------------------------------------
// THE FORMATS: YUV_420_888, JPEG AND PRIVATE
// ---------------------------------------------------------------------------
// The three every camera2 device lists, each with a consumer that receives
// frames here (CameraCaptureSessionImpl):
//   * YUV_420_888: the capture backend's NV12 frames, described to Java as the
//     three plane descriptors the spec permits for a semiplanar buffer, read by
//     an ImageReader;
//   * JPEG: the still output. An ImageReader(JPEG) gets the frame encoded
//     (camera2's JpegStill) for each request that targets it. Without it
//     getOutputSizes(ImageFormat.JPEG) was null and no camera2 app, CameraX's
//     ImageCapture included, could take a picture;
//   * PRIVATE: how camera2 names the opaque buffers of a SurfaceTexture /
//     TextureView output. CameraX sizes its Preview from
//     getOutputSizes(ImageFormat.PRIVATE) and fails to bind when that is null.
//     (An ImageReader created with PRIVATE still fails at configure: it has no
//     planes to read.)
// All three come from the same stream, so they share one size list.
//
// For a format that is NOT listed, getOutputSizes returns null -- not an empty
// array. That is AOSP's documented contract, verified verbatim from
// frameworks/base/core/java/android/hardware/camera2/params/
// StreamConfigurationMap.java (aosp-mirror, main):
//
//   getOutputSizes(int format) javadoc:
//     "@return an array of supported sizes, or {@code null} if the
//      {@code format} is not a supported output"
//
// ---------------------------------------------------------------------------
// FRAME AND STALL DURATIONS
// ---------------------------------------------------------------------------
// The minimum frame duration of a size is the period of the fastest frame rate
// the camera advertises (the backend clamps a request's rate into that range).
// The stall duration -- how much longer a capture takes when the output is in
// the request -- is 0 for YUV_420_888 and PRIVATE, as AOSP requires, and
// JPEG_STALL_NS for JPEG: the pump encodes the still before it goes on to the
// next frame. AOSP throws IllegalArgumentException from these for a format or
// size that is not supported; an unlisted one answers 0 here ("not available")
// rather than crash a caller that asks about a size it took from elsewhere.
//
// Other AOSP line refs, same file: getValidOutputFormatsForInput L460,
// getInputSizes L500, getOutputSizes(Class) L748, getOutputSizes(int) L780,
// getHighSpeedVideoSizes L822, getHighSpeedVideoFpsRangesFor L849,
// getHighSpeedVideoFpsRanges L917, getHighSpeedVideoSizesFor L935,
// getOutputMinFrameDuration(int,Size) L1017, getOutputMinFrameDuration(Class,Size)
// L1070, getOutputStallDuration(int,Size) L1163, getOutputStallDuration(Class,Size)
// L1200.
package android.hardware.camera2.params;

import android.util.Range;
import android.util.Size;

public final class StreamConfigurationMap {

    private final int[] mOutputFormats;
    private final Size[] mOutputSizes;
    private final Range<Integer>[] mFpsRanges;

    /** The empty map. Retained for the device-absent shape, which is now
     *  unreachable (CameraManager.getCameraCharacteristics throws for an
     *  unknown id before anything constructs one), but harmless and cheap. */
    StreamConfigurationMap() {
        this(new int[0], new Size[0], newRangeArray());
    }

    private StreamConfigurationMap(int[] formats, Size[] sizes, Range<Integer>[] fpsRanges) {
        mOutputFormats = formats != null ? formats : new int[0];
        mOutputSizes = sizes != null ? sizes : new Size[0];
        mFpsRanges = fpsRanges != null ? fpsRanges : newRangeArray();
    }

    /**
     * Build a map from a real capture device's advertised geometry.
     *
     * Not an AOSP method (AOSP's equivalent constructor takes the HAL's
     * StreamConfiguration[] and is @hide). Public and macapk-prefixed because
     * the caller, CameraBackend, lives in the parent package and so cannot
     * reach a package-private constructor -- the same reason SensorEvent
     * .macapkNew and TriggerEvent.macapkNew exist in this tree.
     *
     * @param formats   the image formats this camera can output:
     *                  YUV_420_888, JPEG and PRIVATE.
     * @param sizes     every advertised resolution, deduplicated and sorted
     *                  largest-area first.
     * @param fpsRanges every advertised frame-rate range, deduplicated.
     */
    public static StreamConfigurationMap macapkCreate(int[] formats, Size[] sizes,
            Range<Integer>[] fpsRanges) {
        return new StreamConfigurationMap(formats, sizes, fpsRanges);
    }

    private boolean isOutputFormat(int format) {
        for (int f : mOutputFormats) {
            if (f == format) return true;
        }
        return false;
    }

    public int[] getOutputFormats() { return mOutputFormats.clone(); }

    /** No reprocessing input: the capture backend hands out camera frames only,
     *  and there is nothing to feed a reprocessing session from. */
    public int[] getInputFormats() { return new int[0]; }

    /** API 23. The output formats a reprocessing session may produce from the
     *  given input format. Empty because there are no input formats either. */
    public int[] getValidOutputFormatsForInput(int inputFormat) { return new int[0]; }

    /** Null for an unsupported format, per the AOSP javadoc quoted in the
     *  header. A clone every call because the array is handed to app code,
     *  which is free to sort it in place (and commonly does). */
    public Size[] getOutputSizes(int format) {
        return isOutputFormat(format) ? mOutputSizes.clone() : null;
    }

    /**
     * The Class overload, used by apps that target a SurfaceHolder /
     * SurfaceTexture / ImageReader rather than a raw ImageFormat. AOSP answers
     * it from the same size list as the implementation-defined format, because
     * those consumers all take the opaque producer format.
     *
     * We hand back the same sizes: a camera frame is the same geometry
     * whichever consumer it is routed to, and the honest limitation is not the
     * SIZE list but which consumers actually receive frames (ImageReader and
     * SurfaceTexture/TextureView do; MediaRecorder / MediaCodec / plain
     * SurfaceView do not) -- which CameraCaptureSessionImpl reports loudly at
     * configure time rather than hiding here.
     */
    public Size[] getOutputSizes(Class<?> klass) {
        return mOutputSizes.clone();
    }

    public Size[] getInputSizes(int format) { return new Size[0]; }

    /** API 23. Sizes that cannot sustain 20fps. AOSP returns an empty array for
     *  a device without the BURST_CAPTURE capability, which is this one --
     *  every size we advertise is listed by getOutputSizes instead. */
    public Size[] getHighResolutionOutputSizes(int format) { return new Size[0]; }

    /** What a JPEG output adds to a capture: an encode of the largest size on
     *  the JVM's encoder, generously. Devices report a few hundred ms here. */
    private static final long JPEG_STALL_NS = 100_000_000L;

    private boolean isOutputSize(Size size) {
        if (size == null) return false;
        for (Size s : mOutputSizes) {
            if (s.equals(size)) return true;
        }
        return false;
    }

    /** The period of the fastest advertised frame rate; 0 when none is known. */
    private long minFrameDuration() {
        int fps = 0;
        for (Range<Integer> r : mFpsRanges) {
            if (r != null && r.getUpper() != null) fps = Math.max(fps, r.getUpper().intValue());
        }
        return fps > 0 ? 1_000_000_000L / fps : 0L;
    }

    // Frame and stall durations: see the header.
    public long getOutputMinFrameDuration(int format, Size size) {
        return isOutputFormat(format) && isOutputSize(size) ? minFrameDuration() : 0L;
    }

    /** API 21. The Class overload, used by apps that target a SurfaceView or
     *  SurfaceTexture rather than a raw ImageFormat. */
    public <T> long getOutputMinFrameDuration(Class<T> klass, Size size) {
        return isOutputSize(size) ? minFrameDuration() : 0L;
    }

    public long getOutputStallDuration(int format, Size size) {
        return format == android.graphics.ImageFormat.JPEG && isOutputFormat(format)
                && isOutputSize(size) ? JPEG_STALL_NS : 0L;
    }

    /** API 21. The Class consumers take PRIVATE buffers, which never stall. */
    public <T> long getOutputStallDuration(Class<T> klass, Size size) { return 0L; }

    public boolean isOutputSupportedFor(int format) { return isOutputFormat(format); }

    /**
     * API 21. The Class overload. AOSP answers true for the consumer classes
     * that take the implementation-defined format (SurfaceHolder,
     * SurfaceTexture, MediaRecorder, MediaCodec, ImageReader, Allocation).
     *
     * Static in AOSP, which is why it cannot consult the instance's format
     * list, and why AOSP's own answer is about the CLASS rather than about any
     * particular camera. We answer for the consumers that genuinely receive
     * frames here -- ImageReader and SurfaceTexture (which is also the
     * TextureView case: a TextureView target IS its SurfaceTexture's Surface)
     * -- and false for the rest, so an app that checks this before configuring
     * a MediaRecorder output takes its own unsupported-output branch instead
     * of configuring a session that would never record.
     */
    public static <T> boolean isOutputSupportedFor(Class<T> klass) {
        if (klass == null) return false;
        return klass.isAssignableFrom(android.media.ImageReader.class)
                || klass.isAssignableFrom(android.graphics.SurfaceTexture.class);
    }

    /** API 21. AOSP asks whether this specific Surface's size/format pair is a
     *  supported output. True for a Surface an ImageReader is behind or one a
     *  SurfaceTexture is behind, for the same reason as the Class overload
     *  above. */
    public boolean isOutputSupportedFor(android.view.Surface surface) {
        if (surface == null) return false;
        android.media.ImageReader reader = android.media.ImageReader.macapkReaderForSurface(surface);
        if (reader != null) {
            // A reader's planes are its format's: YUV_420_888 or JPEG.
            return reader.getImageFormat() != android.graphics.ImageFormat.PRIVATE
                    && isOutputFormat(reader.getImageFormat());
        }
        android.graphics.SurfaceTexture st = surface.macapkGetSurfaceTexture();
        return st != null && !st.isReleased();
    }

    // High-speed (constrained slow-motion) capture is a distinct HAL mode with
    // its own guaranteed >=120fps stream combinations. AVFoundation exposes no
    // equivalent through this backend, so there are no high-speed sizes, and
    // therefore no size can be one -- an app that guards on
    // getHighSpeedVideoSizes() first (the documented order) never reaches the
    // per-size queries.
    public Size[] getHighSpeedVideoSizes() { return new Size[0]; }

    /** API 23. Every FPS range this camera can drive at any high-speed size. */
    public Range<Integer>[] getHighSpeedVideoFpsRanges() { return newRangeArray(); }

    /** API 23. The high-speed FPS ranges valid for one particular size. AOSP
     *  throws IllegalArgumentException when the size is not a high-speed size;
     *  with no high-speed sizes at all, no size can be one, so an app that
     *  guards on getHighSpeedVideoSizes() first (the documented order) never
     *  reaches the throw. Returning empty rather than throwing keeps an
     *  unguarded caller on its no-capability path instead of crashing it. */
    public Range<Integer>[] getHighSpeedVideoFpsRangesFor(Size size) { return newRangeArray(); }

    /** API 23. The sizes usable at a particular high-speed FPS range. */
    public Size[] getHighSpeedVideoSizesFor(Range<Integer> fpsRange) { return new Size[0]; }

    /** Not AOSP. The advertised frame-rate ranges, so CameraCharacteristics and
     *  the capture session can read them back without re-walking the device's
     *  format list. */
    public Range<Integer>[] macapkFpsRanges() { return mFpsRanges.clone(); }

    /** Generic array creation is illegal in Java (erasure), so the empty
     *  Range<Integer>[] is built as a raw Range[] and cast -- the same idiom
     *  CameraCharacteristics already uses for its Range-typed Key. The array is
     *  zero-length, so the unchecked cast can never store a wrong element. */
    @SuppressWarnings("unchecked")
    private static Range<Integer>[] newRangeArray() {
        return (Range<Integer>[]) new Range[0];
    }
}
