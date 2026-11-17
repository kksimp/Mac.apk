// android.hardware.camera2.CaptureRequest -- an immutable package of
// settings + outputs for one capture. Built via Builder (obtained from
// CameraDevice.createCaptureRequest). Mac.apk never submits one to real
// hardware, but the type + Builder + Key set must exist for camera apps
// to compile and run their setup code. Key<T> is a typed metadata key
// per AOSP.
package android.hardware.camera2;

import android.view.Surface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// android-30 android.jar ground truth: `public final class CaptureRequest
// extends CameraMetadata<CaptureRequest$Key<?>> implements Parcelable`. The
// superclass is linkable ABI (apps pass requests where CameraMetadata is
// expected). Parcelable is NOT implemented yet: doing it honestly needs a
// working Parcel round-trip for the key map, and a lying stub would corrupt
// any app that actually parcels a request.
// PARCELABLE (added with the API-36 gap pass). The interface is now declared,
// and the reason it was withheld before -- "a lying stub would corrupt any app
// that actually parcels a request" -- is answered rather than ignored:
// writeToParcel writes ZERO bytes on purpose, and android.os.Parcel's
// documented empty-writer fallback (Parcel.java writeParcelableBody, case 2 of
// its live-object block comment) then keeps the LIVE CaptureRequest and warns
// once on stderr. So Bundle.putParcelable/getParcelable and
// Intent extras round-trip the real request with its real settings, targets and
// tag -- which is the whole of the transport that exists here, because this
// Parcel has no cross-process side. A hand-rolled byte format would be strictly
// worse: CaptureRequest values include Rect, Range, MeteringRectangle[] and
// TonemapCurve, and Parcel.writeValue rejects the ones that are not
// Serializable, so a "real" writeToParcel would throw on ordinary requests
// where the live reference simply works. REMOVAL CONDITION is automatic: the
// fallback is driven by a measurement (did the body write anything), so the day
// a real marshaller lands here it stops firing with no edit to Parcel.
//
// AOSP fidelity: the unchecked usage below MIRRORS AOSP, which deprecates its
// own APIs while continuing to implement them. Removing it would diverge
// from the surface we exist to reproduce, so it is suppressed here rather
// than globally -- every other lint category stays live for this file.
@SuppressWarnings("unchecked")
public final class CaptureRequest extends CameraMetadata<CaptureRequest.Key<?>>
        implements android.os.Parcelable {
    public static final class Key<T> {
        private final String name;
        private final Class<T> type;
        public Key(String name, Class<T> type) { this.name = name; this.type = type; }
        public String getName() { return name; }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) {
            return (o instanceof Key) && ((Key<?>) o).name.equals(name);
        }
        @Override public String toString() { return "CaptureRequest.Key(" + name + ")"; }
    }

    // A representative subset of the AOSP control keys camera apps set.
    public static final Key<Integer> CONTROL_AF_MODE =
            new Key<>("android.control.afMode", Integer.class);
    public static final Key<Integer> CONTROL_AE_MODE =
            new Key<>("android.control.aeMode", Integer.class);
    public static final Key<Integer> CONTROL_AWB_MODE =
            new Key<>("android.control.awbMode", Integer.class);
    public static final Key<Integer> CONTROL_MODE =
            new Key<>("android.control.mode", Integer.class);
    public static final Key<Integer> FLASH_MODE =
            new Key<>("android.flash.mode", Integer.class);
    public static final Key<Long> SENSOR_EXPOSURE_TIME =
            new Key<>("android.sensor.exposureTime", Long.class);
    public static final Key<Integer> SENSOR_SENSITIVITY =
            new Key<>("android.sensor.sensitivity", Integer.class);
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<>("android.jpeg.orientation", Integer.class);
    public static final Key<Byte> JPEG_QUALITY =
            new Key<>("android.jpeg.quality", Byte.class);

    private final Map<Key<?>, Object> mSettings;
    private final List<Surface> mSurfaces;
    private final Object mTag;

    private CaptureRequest(Builder b) {
        mSettings = new HashMap<>(b.settings);
        mSurfaces = new ArrayList<>(b.surfaces);
        mTag = b.tag;
    }

    /** The empty form CREATOR hands back before android.os.Parcel substitutes
     *  the live object. Package-private: an app has no way to build a request
     *  except through Builder, and that is AOSP's shape too. */
    CaptureRequest() {
        mSettings = new HashMap<>();
        mSurfaces = new ArrayList<>();
        mTag = null;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) { return (T) mSettings.get(key); }
    public Object getTag() { return mTag; }
    public boolean containsTarget(Surface surface) { return mSurfaces.contains(surface); }
    public List<Surface> getTargets() { return new ArrayList<>(mSurfaces); }

    /**
     * API 23. Whether this request was built by
     * CameraDevice.createReprocessCaptureRequest.
     *
     * Always false: that method refuses on this backend (no camera here
     * advertises YUV_REPROCESSING or PRIVATE_REPROCESSING), so no reprocess
     * request can exist to answer true. AOSP returns the same for every request
     * built from a template.
     */
    public boolean isReprocess() { return false; }

    @Override public int describeContents() { return 0; }

    /**
     * Deliberately writes nothing -- see the PARCELABLE note in this file's
     * header. android.os.Parcel detects the empty body and keeps the live
     * object, which round-trips the request faithfully in-process and says so
     * once on stderr.
     */
    @Override public void writeToParcel(android.os.Parcel dest, int flags) { }

    public static final android.os.Parcelable.Creator<CaptureRequest> CREATOR =
            new android.os.Parcelable.Creator<CaptureRequest>() {
        /** Reads nothing, matching writeToParcel. Parcel then replaces this
         *  placeholder with the live request it kept. */
        @Override public CaptureRequest createFromParcel(android.os.Parcel in) {
            return new CaptureRequest();
        }
        @Override public CaptureRequest[] newArray(int size) {
            return new CaptureRequest[size];
        }
    };

    /**
     * AOSP overrides finalize() to release the request's native
     * CameraMetadataNative buffer. There is no native buffer here -- the
     * settings live in a plain HashMap the GC already owns -- so this only has
     * to exist so that an app (or a subclass in another framework's test
     * harness) resolving CaptureRequest.finalize does not fail to link.
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    protected void finalize() throws Throwable {
        super.finalize();
    }

    public static final class Builder {
        final Map<Key<?>, Object> settings = new HashMap<>();
        final List<Surface> surfaces = new ArrayList<>();
        Object tag;

        public Builder() {}

        /**
         * The template-seeded form, built by CameraDevice.createCaptureRequest.
         *
         * AOSP's createCaptureRequest asks the camera service for the HAL's
         * default request for the template and hands back a Builder already
         * populated with it, which is why app code can build a working preview
         * request without setting anything. Reproducing that matters for more
         * than convenience: an app that reads back builder.get(CONTROL_AE_MODE)
         * to decide whether to offer an exposure control gets null instead of a
         * mode if the template is empty, and takes the wrong branch.
         *
         * The values below are the ones this backend can actually stand behind.
         * AVFoundation runs its own auto-exposure and auto-white-balance and
         * does not let us drive them per frame, so AUTO/ON is the truth rather
         * than an aspiration; AF is OFF because CameraCharacteristics
         * .CONTROL_AF_AVAILABLE_MODES advertises only OFF (we do not drive
         * focus); FLASH_MODE is OFF because there is no torch on this hardware.
         * CONTROL_CAPTURE_INTENT is the one value that genuinely varies with
         * the template, so it is the one thing the template argument changes.
         */
        Builder(int templateType) {
            settings.put(CONTROL_MODE,
                    Integer.valueOf(CameraMetadata.CONTROL_MODE_AUTO));
            settings.put(CONTROL_AE_MODE,
                    Integer.valueOf(CameraMetadata.CONTROL_AE_MODE_ON));
            settings.put(CONTROL_AWB_MODE,
                    Integer.valueOf(CameraMetadata.CONTROL_AWB_MODE_AUTO));
            settings.put(CONTROL_AF_MODE,
                    Integer.valueOf(CameraMetadata.CONTROL_AF_MODE_OFF));
            settings.put(FLASH_MODE,
                    Integer.valueOf(CameraMetadata.FLASH_MODE_OFF));
            settings.put(CONTROL_CAPTURE_INTENT,
                    Integer.valueOf(captureIntentFor(templateType)));
            // What a still is encoded with unless the app says otherwise: every
            // device's template carries them, and apps read them back (to
            // offer a quality setting, to add the display rotation). The
            // values are JpegStill's own defaults: upright, no thumbnail.
            settings.put(JPEG_QUALITY, Byte.valueOf(JpegStill.DEFAULT_QUALITY));
            settings.put(JPEG_ORIENTATION, Integer.valueOf(0));
            settings.put(JPEG_THUMBNAIL_QUALITY, Byte.valueOf(JpegStill.DEFAULT_THUMBNAIL_QUALITY));
            settings.put(JPEG_THUMBNAIL_SIZE, new android.util.Size(0, 0));
        }

        private static int captureIntentFor(int templateType) {
            switch (templateType) {
                case CameraDevice.TEMPLATE_STILL_CAPTURE:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE;
                case CameraDevice.TEMPLATE_RECORD:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD;
                case CameraDevice.TEMPLATE_VIDEO_SNAPSHOT:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT;
                case CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG;
                case CameraDevice.TEMPLATE_MANUAL:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL;
                case CameraDevice.TEMPLATE_PREVIEW:
                default:
                    return CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW;
            }
        }

        public <T> void set(Key<T> key, T value) { settings.put(key, value); }
        @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) { return (T) settings.get(key); }
        public void addTarget(Surface outputTarget) { surfaces.add(outputTarget); }
        public void removeTarget(Surface outputTarget) { surfaces.remove(outputTarget); }
        public void setTag(Object tag) { this.tag = tag; }
        public CaptureRequest build() { return new CaptureRequest(this); }

        // API 28. Per-PHYSICAL-camera settings on a logical multi-camera.
        //
        // AOSP's implementation is gated on the builder having been created
        // with a physical-camera id set (CameraDevice.createCaptureRequest(int,
        // Set<String>)); anything else is
        // IllegalArgumentException("Physical camera id not included in this
        // request"). No builder here can have one: createCaptureRequest(int,
        // Set) rejects every non-empty set, because
        // CameraCharacteristics.getPhysicalCameraIds() is empty. So both
        // methods take AOSP's own throw, and neither pretends to store a
        // setting that the capture path would then drop on the floor.

        public <T> Builder setPhysicalCameraKey(Key<T> key, T value, String physicalCameraId) {
            if (physicalCameraId == null) {
                throw new IllegalArgumentException("physicalCameraId must not be null");
            }
            throw new IllegalArgumentException("Physical camera id not included in this request");
        }

        public <T> T getPhysicalCameraKey(Key<T> key, String physicalCameraId) {
            if (physicalCameraId == null) {
                throw new IllegalArgumentException("physicalCameraId must not be null");
            }
            throw new IllegalArgumentException("Physical camera id not included in this request");
        }
    }

    /** API 21. Which of the capture templates this request came from; the HAL
     *  uses it to bias its 3A tuning. Seeded by Builder(int) from the template
     *  argument, and the one setting in a template that genuinely varies with
     *  it. */
    public static final Key<Integer> CONTROL_CAPTURE_INTENT =
            new Key<>("android.control.captureIntent", Integer.class);

    public static final Key<Integer> CONTROL_AF_TRIGGER = new Key<>("android.control.afTrigger", Integer.class);
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AF_REGIONS =
        new Key<>("android.control.afRegions", android.hardware.camera2.params.MeteringRectangle[].class);
    public static final Key<android.util.Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE =
        new Key("android.control.aeTargetFpsRange", android.util.Range.class);

    // ---------------------------------------------------------------------
    // The remaining public CaptureRequest keys of API 36, mirrored from
    // frameworks/base/core/java/android/hardware/camera2/CaptureRequest.java
    // (aosp-mirror/platform_frameworks_base, android16-release), each with
    // AOSP's own metadata tag string.
    //
    // Setting a key the capture backend does not act on is harmless and is what
    // real Android does too: the HAL ignores tags outside its
    // availableRequestKeys. What must NOT happen is advertising such a key from
    // CameraCharacteristics.getAvailableCaptureRequestKeys() -- see the comment
    // on that method for why. Declaring the constant here does not advertise it.
    // ---------------------------------------------------------------------

    /** AOSP CaptureRequest.java:4352 -- metadata tag "android.blackLevel.lock". */
    public static final Key<Boolean> BLACK_LEVEL_LOCK =
            new Key<>("android.blackLevel.lock", boolean.class);

    /** AOSP CaptureRequest.java:1217 -- metadata tag "android.colorCorrection.aberrationMode". */
    public static final Key<Integer> COLOR_CORRECTION_ABERRATION_MODE =
            new Key<>("android.colorCorrection.aberrationMode", int.class);

    /** AOSP CaptureRequest.java:1242 -- metadata tag "android.colorCorrection.colorTemperature". */
    public static final Key<Integer> COLOR_CORRECTION_COLOR_TEMPERATURE =
            new Key<>("android.colorCorrection.colorTemperature", int.class);

    /** AOSP CaptureRequest.java:1271 -- metadata tag "android.colorCorrection.colorTint". */
    public static final Key<Integer> COLOR_CORRECTION_COLOR_TINT =
            new Key<>("android.colorCorrection.colorTint", int.class);

    /** AOSP CaptureRequest.java:1183 -- metadata tag "android.colorCorrection.gains". */
    public static final Key<android.hardware.camera2.params.RggbChannelVector> COLOR_CORRECTION_GAINS =
            new Key<>("android.colorCorrection.gains", android.hardware.camera2.params.RggbChannelVector.class);

    /** AOSP CaptureRequest.java:1127 -- metadata tag "android.colorCorrection.mode". */
    public static final Key<Integer> COLOR_CORRECTION_MODE =
            new Key<>("android.colorCorrection.mode", int.class);

    /** AOSP CaptureRequest.java:1155 -- metadata tag "android.colorCorrection.transform". */
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> COLOR_CORRECTION_TRANSFORM =
            new Key<>("android.colorCorrection.transform", android.hardware.camera2.params.ColorSpaceTransform.class);

    /** AOSP CaptureRequest.java:1329 -- metadata tag "android.control.aeAntibandingMode". */
    public static final Key<Integer> CONTROL_AE_ANTIBANDING_MODE =
            new Key<>("android.control.aeAntibandingMode", int.class);

    /** AOSP CaptureRequest.java:1363 -- metadata tag "android.control.aeExposureCompensation". */
    public static final Key<Integer> CONTROL_AE_EXPOSURE_COMPENSATION =
            new Key<>("android.control.aeExposureCompensation", int.class);

    /** AOSP CaptureRequest.java:1414 -- metadata tag "android.control.aeLock". */
    public static final Key<Boolean> CONTROL_AE_LOCK =
            new Key<>("android.control.aeLock", boolean.class);

    /** AOSP CaptureRequest.java:1688 -- metadata tag "android.control.aePrecaptureTrigger". */
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER =
            new Key<>("android.control.aePrecaptureTrigger", int.class);

    /** AOSP CaptureRequest.java:2766 -- metadata tag "android.control.aePriorityMode". */
    public static final Key<Integer> CONTROL_AE_PRIORITY_MODE =
            new Key<>("android.control.aePriorityMode", int.class);

    /** AOSP CaptureRequest.java:1574 -- metadata tag "android.control.aeRegions". */
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AE_REGIONS =
            new Key<>("android.control.aeRegions", android.hardware.camera2.params.MeteringRectangle[].class);

    /** AOSP CaptureRequest.java:2687 -- metadata tag "android.control.autoframing". */
    public static final Key<Integer> CONTROL_AUTOFRAMING =
            new Key<>("android.control.autoframing", int.class);

    /** AOSP CaptureRequest.java:1894 -- metadata tag "android.control.awbLock". */
    public static final Key<Boolean> CONTROL_AWB_LOCK =
            new Key<>("android.control.awbLock", boolean.class);

    /** AOSP CaptureRequest.java:2049 -- metadata tag "android.control.awbRegions". */
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AWB_REGIONS =
            new Key<>("android.control.awbRegions", android.hardware.camera2.params.MeteringRectangle[].class);

    /** AOSP CaptureRequest.java:2136 -- metadata tag "android.control.effectMode". */
    public static final Key<Integer> CONTROL_EFFECT_MODE =
            new Key<>("android.control.effectMode", int.class);

    /** AOSP CaptureRequest.java:2381 -- metadata tag "android.control.enableZsl". */
    public static final Key<Boolean> CONTROL_ENABLE_ZSL =
            new Key<>("android.control.enableZsl", boolean.class);

    /** AOSP CaptureRequest.java:2427 -- metadata tag "android.control.extendedSceneMode". */
    public static final Key<Integer> CONTROL_EXTENDED_SCENE_MODE =
            new Key<>("android.control.extendedSceneMode", int.class);

    /** AOSP CaptureRequest.java:2347 -- metadata tag "android.control.postRawSensitivityBoost". */
    public static final Key<Integer> CONTROL_POST_RAW_SENSITIVITY_BOOST =
            new Key<>("android.control.postRawSensitivityBoost", int.class);

    /** AOSP CaptureRequest.java:2249 -- metadata tag "android.control.sceneMode". */
    public static final Key<Integer> CONTROL_SCENE_MODE =
            new Key<>("android.control.sceneMode", int.class);

    /** AOSP CaptureRequest.java:2653 -- metadata tag "android.control.settingsOverride". */
    public static final Key<Integer> CONTROL_SETTINGS_OVERRIDE =
            new Key<>("android.control.settingsOverride", int.class);

    /** AOSP CaptureRequest.java:2311 -- metadata tag "android.control.videoStabilizationMode". */
    public static final Key<Integer> CONTROL_VIDEO_STABILIZATION_MODE =
            new Key<>("android.control.videoStabilizationMode", int.class);

    /** AOSP CaptureRequest.java:2726 -- metadata tag "android.control.zoomMethod". */
    public static final Key<Integer> CONTROL_ZOOM_METHOD =
            new Key<>("android.control.zoomMethod", int.class);

    /** AOSP CaptureRequest.java:2528 -- metadata tag "android.control.zoomRatio". */
    public static final Key<Float> CONTROL_ZOOM_RATIO =
            new Key<>("android.control.zoomRatio", float.class);

    /** AOSP CaptureRequest.java:4467 -- metadata tag "android.distortionCorrection.mode". */
    public static final Key<Integer> DISTORTION_CORRECTION_MODE =
            new Key<>("android.distortionCorrection.mode", int.class);

    /** AOSP CaptureRequest.java:2816 -- metadata tag "android.edge.mode". */
    public static final Key<Integer> EDGE_MODE =
            new Key<>("android.edge.mode", int.class);

    /** AOSP CaptureRequest.java:4500 -- metadata tag "android.extension.strength". */
    public static final Key<Integer> EXTENSION_STRENGTH =
            new Key<>("android.extension.strength", int.class);

    /** AOSP CaptureRequest.java:2903 -- metadata tag "android.flash.strengthLevel". */
    public static final Key<Integer> FLASH_STRENGTH_LEVEL =
            new Key<>("android.flash.strengthLevel", int.class);

    /** AOSP CaptureRequest.java:2929 -- metadata tag "android.hotPixel.mode". */
    public static final Key<Integer> HOT_PIXEL_MODE =
            new Key<>("android.hotPixel.mode", int.class);

    /** AOSP CaptureRequest.java:2943 -- metadata tag "android.jpeg.gpsLocation". */
    public static final Key<android.location.Location> JPEG_GPS_LOCATION =
            new Key<>("android.jpeg.gpsLocation", android.location.Location.class);

    /** AOSP CaptureRequest.java:3053 -- metadata tag "android.jpeg.thumbnailQuality". */
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY =
            new Key<>("android.jpeg.thumbnailQuality", byte.class);

    /** AOSP CaptureRequest.java:3093 -- metadata tag "android.jpeg.thumbnailSize". */
    public static final Key<android.util.Size> JPEG_THUMBNAIL_SIZE =
            new Key<>("android.jpeg.thumbnailSize", android.util.Size.class);

    /** AOSP CaptureRequest.java:3131 -- metadata tag "android.lens.aperture". */
    public static final Key<Float> LENS_APERTURE =
            new Key<>("android.lens.aperture", float.class);

    /** AOSP CaptureRequest.java:3162 -- metadata tag "android.lens.filterDensity". */
    public static final Key<Float> LENS_FILTER_DENSITY =
            new Key<>("android.lens.filterDensity", float.class);

    /** AOSP CaptureRequest.java:3191 -- metadata tag "android.lens.focalLength". */
    public static final Key<Float> LENS_FOCAL_LENGTH =
            new Key<>("android.lens.focalLength", float.class);

    /** AOSP CaptureRequest.java:3224 -- metadata tag "android.lens.focusDistance". */
    public static final Key<Float> LENS_FOCUS_DISTANCE =
            new Key<>("android.lens.focusDistance", float.class);

    /** AOSP CaptureRequest.java:3274 -- metadata tag "android.lens.opticalStabilizationMode". */
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE =
            new Key<>("android.lens.opticalStabilizationMode", int.class);

    /** AOSP CaptureRequest.java:3331 -- metadata tag "android.noiseReduction.mode". */
    public static final Key<Integer> NOISE_REDUCTION_MODE =
            new Key<>("android.noiseReduction.mode", int.class);

    /** AOSP CaptureRequest.java:4403 -- metadata tag "android.reprocess.effectiveExposureFactor". */
    public static final Key<Float> REPROCESS_EFFECTIVE_EXPOSURE_FACTOR =
            new Key<>("android.reprocess.effectiveExposureFactor", float.class);

    /** AOSP CaptureRequest.java:3472 -- metadata tag "android.scaler.cropRegion". */
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION =
            new Key<>("android.scaler.cropRegion", android.graphics.Rect.class);

    /** AOSP CaptureRequest.java:3566 -- metadata tag "android.scaler.rotateAndCrop". */
    public static final Key<Integer> SCALER_ROTATE_AND_CROP =
            new Key<>("android.scaler.rotateAndCrop", int.class);

    /** AOSP CaptureRequest.java:3696 -- metadata tag "android.sensor.frameDuration". */
    public static final Key<Long> SENSOR_FRAME_DURATION =
            new Key<>("android.sensor.frameDuration", long.class);

    /** AOSP CaptureRequest.java:3868 -- metadata tag "android.sensor.pixelMode". */
    public static final Key<Integer> SENSOR_PIXEL_MODE =
            new Key<>("android.sensor.pixelMode", int.class);

    /** AOSP CaptureRequest.java:3758 -- metadata tag "android.sensor.testPatternData". */
    public static final Key<int[]> SENSOR_TEST_PATTERN_DATA =
            new Key<>("android.sensor.testPatternData", int[].class);

    /** AOSP CaptureRequest.java:3795 -- metadata tag "android.sensor.testPatternMode". */
    public static final Key<Integer> SENSOR_TEST_PATTERN_MODE =
            new Key<>("android.sensor.testPatternMode", int.class);

    /** AOSP CaptureRequest.java:3923 -- metadata tag "android.shading.mode". */
    public static final Key<Integer> SHADING_MODE =
            new Key<>("android.shading.mode", int.class);

    /** AOSP CaptureRequest.java:3950 -- metadata tag "android.statistics.faceDetectMode". */
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE =
            new Key<>("android.statistics.faceDetectMode", int.class);

    /** AOSP CaptureRequest.java:3966 -- metadata tag "android.statistics.hotPixelMapMode". */
    public static final Key<Boolean> STATISTICS_HOT_PIXEL_MAP_MODE =
            new Key<>("android.statistics.hotPixelMapMode", boolean.class);

    /** AOSP CaptureRequest.java:3996 -- metadata tag "android.statistics.lensShadingMapMode". */
    public static final Key<Integer> STATISTICS_LENS_SHADING_MAP_MODE =
            new Key<>("android.statistics.lensShadingMapMode", int.class);

    /** AOSP CaptureRequest.java:4024 -- metadata tag "android.statistics.oisDataMode". */
    public static final Key<Integer> STATISTICS_OIS_DATA_MODE =
            new Key<>("android.statistics.oisDataMode", int.class);

    /** AOSP CaptureRequest.java:4183 -- metadata tag "android.tonemap.curve". */
    public static final Key<android.hardware.camera2.params.TonemapCurve> TONEMAP_CURVE =
            new Key<>("android.tonemap.curve", android.hardware.camera2.params.TonemapCurve.class);

    /** AOSP CaptureRequest.java:4258 -- metadata tag "android.tonemap.gamma". */
    public static final Key<Float> TONEMAP_GAMMA =
            new Key<>("android.tonemap.gamma", float.class);

    /** AOSP CaptureRequest.java:4234 -- metadata tag "android.tonemap.mode". */
    public static final Key<Integer> TONEMAP_MODE =
            new Key<>("android.tonemap.mode", int.class);

    /** AOSP CaptureRequest.java:4285 -- metadata tag "android.tonemap.presetCurve". */
    public static final Key<Integer> TONEMAP_PRESET_CURVE =
            new Key<>("android.tonemap.presetCurve", int.class);
}

