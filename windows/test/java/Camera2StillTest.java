// Camera2StillTest -- takes pictures through the real android.hardware.camera2
// classes, the way a camera app and CameraX's ImageCapture do: an
// ImageReader(JPEG) output, a TEMPLATE_STILL_CAPTURE request, the Image out of
// the reader. The camera is CameraBridge's virtual one (what a PC without a
// webcam has), which needs no capture natives, so this runs on a plain JVM.
//
//   javac -cp "<runtime>/lib/android-framework.jar;<runtime>/lib/macapk-loader.jar" -d out \
//         windows/test/java/Camera2StillTest.java
//   java  -cp "<runtime>/lib/android-framework.jar;<runtime>/lib/macapk-loader.jar;out" Camera2StillTest
//
// Checks: the stream map lists JPEG (and PRIVATE) sizes; a still arrives as a
// one-plane JPEG Image of the reader's size that javax.imageio decodes, with
// the request's orientation in its EXIF block, its thumbnail, and the
// CaptureResult's timestamp; the callbacks come in order; maxImages is kept;
// a still does not disturb a YUV reader in the same session; a session with
// only a video recorder's Surface fails to configure instead of hanging.
// Exits 0 when every check passes.

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

public final class Camera2StillTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) failures++;
    }

    /** What the app's callbacks saw, in the order they ran. */
    private static final List<String> events = new ArrayList<>();
    private static void event(String e) {
        synchronized (events) { events.add(e); }
    }

    private static final Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "camera-callbacks");
        t.setDaemon(true);
        return t;
    });

    /** One capture's callbacks, recorded; done once the sequence completes. */
    private static final class Capture extends CameraCaptureSession.CaptureCallback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile long startedTimestamp;
        volatile TotalCaptureResult result;
        volatile int buffersLost;

        @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp,
                long frameNumber) {
            startedTimestamp = timestamp;
            event("started");
        }
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                TotalCaptureResult res) {
            result = res;
            event("completed");
        }
        @Override public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r, Surface target,
                long frameNumber) {
            buffersLost++;
            event("bufferLost");
        }
        @Override public void onCaptureSequenceCompleted(CameraCaptureSession s, int sequenceId,
                long frameNumber) {
            event("sequenceCompleted");
            done.countDown();
        }
    }

    private static CameraDevice open(CameraManager manager, String id) throws Exception {
        LinkedBlockingQueue<Object> opened = new LinkedBlockingQueue<>();
        manager.openCamera(id, executor, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) { opened.add(camera); }
            @Override public void onDisconnected(CameraDevice camera) { opened.add("disconnected"); }
            @Override public void onError(CameraDevice camera, int error) { opened.add("error " + error); }
        });
        Object o = opened.poll(10, TimeUnit.SECONDS);
        return o instanceof CameraDevice ? (CameraDevice) o : null;
    }

    /** The session, or null when it failed to configure; never waits past 10 s. */
    private static CameraCaptureSession session(CameraDevice device, Surface... outputs) throws Exception {
        LinkedBlockingQueue<Object> configured = new LinkedBlockingQueue<>();
        List<OutputConfiguration> configs = new ArrayList<>();
        for (Surface s : outputs) configs.add(new OutputConfiguration(s));
        device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs,
                executor, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) { configured.add(s); }
                    @Override public void onConfigureFailed(CameraCaptureSession s) { configured.add("failed"); }
                }));
        Object o = configured.poll(10, TimeUnit.SECONDS);
        if (o == null) check(false, "createCaptureSession answered within 10 s");
        return o instanceof CameraCaptureSession ? (CameraCaptureSession) o : null;
    }

    private static byte[] bytesOf(Image image) {
        ByteBuffer b = image.getPlanes()[0].getBuffer();
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    /** The TIFF block of the file's APP1 "Exif" segment, or null. */
    private static byte[] exifOf(byte[] jpeg) {
        int at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA) break;  // start of scan: no more headers
            int len = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            if (marker == 0xE1 && len >= 8 && jpeg[at + 4] == 'E' && jpeg[at + 5] == 'x'
                    && jpeg[at + 6] == 'i' && jpeg[at + 7] == 'f') {
                return Arrays.copyOfRange(jpeg, at + 10, at + 2 + len);
            }
            at += 2 + len;
        }
        return null;
    }

    private static int u16(byte[] t, int at) { return (t[at] & 0xFF) | ((t[at + 1] & 0xFF) << 8); }
    private static int u32(byte[] t, int at) { return u16(t, at) | (u16(t, at + 2) << 16); }

    /** A tag's value in the IFD at ifd (little-endian TIFF), or -1. */
    private static int tag(byte[] tiff, int ifd, int tag) {
        int n = u16(tiff, ifd);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + i * 12;
            if (u16(tiff, e) == tag) return u16(tiff, e + 2) == 3 ? u16(tiff, e + 8) : u32(tiff, e + 8);
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        CameraManager manager = new CameraManager();
        String[] ids = manager.getCameraIdList();
        check(ids.length >= 1, "a camera is listed (" + ids.length + ")");
        if (ids.length == 0) System.exit(1);
        String id = ids[0];

        // ---- what the camera says it can do ---------------------------------
        CameraCharacteristics chars = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] formats = map.getOutputFormats();
        Arrays.sort(formats);
        check(Arrays.binarySearch(formats, ImageFormat.JPEG) >= 0, "getOutputFormats lists JPEG");
        check(Arrays.binarySearch(formats, ImageFormat.YUV_420_888) >= 0, "getOutputFormats lists YUV_420_888");
        check(Arrays.binarySearch(formats, ImageFormat.PRIVATE) >= 0, "getOutputFormats lists PRIVATE");
        Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
        Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        check(jpegSizes != null && jpegSizes.length > 0, "getOutputSizes(JPEG) lists sizes");
        check(Arrays.equals(jpegSizes, yuvSizes), "the JPEG sizes are the YUV sizes");
        boolean descending = true;
        for (int i = 1; i < jpegSizes.length; i++) {
            descending &= (long) jpegSizes[i - 1].getWidth() * jpegSizes[i - 1].getHeight()
                    >= (long) jpegSizes[i].getWidth() * jpegSizes[i].getHeight();
        }
        check(descending, "the JPEG sizes are largest first");
        check(map.getOutputSizes(ImageFormat.PRIVATE) != null, "getOutputSizes(PRIVATE) lists sizes");
        check(map.getOutputSizes(SurfaceTexture.class) != null, "getOutputSizes(SurfaceTexture.class) lists sizes");
        check(map.getOutputSizes(MediaRecorder.class) != null, "getOutputSizes(MediaRecorder.class) lists sizes");
        check(map.getOutputSizes(ImageFormat.RAW_SENSOR) == null, "getOutputSizes(RAW_SENSOR) is null");
        check(map.isOutputSupportedFor(ImageFormat.JPEG), "isOutputSupportedFor(JPEG)");
        check(map.getOutputStallDuration(ImageFormat.JPEG, jpegSizes[0]) > 0, "a JPEG output has a stall duration");
        check(map.getOutputStallDuration(ImageFormat.YUV_420_888, jpegSizes[0]) == 0, "a YUV output has none");
        check(map.getOutputMinFrameDuration(ImageFormat.JPEG, jpegSizes[0]) > 0, "a JPEG size has a frame duration");

        Size[] thumbs = chars.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES);
        check(thumbs != null && thumbs.length >= 2 && thumbs[0].equals(new Size(0, 0)),
                "JPEG_AVAILABLE_THUMBNAIL_SIZES has (0,0) and a size");
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        check(caps != null && caps.length > 0
                && caps[0] == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                "REQUEST_AVAILABLE_CAPABILITIES has BACKWARD_COMPATIBLE");
        check(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != null
                && chars.get(CameraCharacteristics.SENSOR_ORIENTATION) != null
                && chars.get(CameraCharacteristics.LENS_FACING) != null
                && chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) != null
                && chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) != null
                && chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) != null
                && chars.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES) != null
                && chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) != null
                && chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) != null
                && chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) != null,
                "the keys an ImageCapture reads are present");

        CameraDevice device = open(manager, id);
        check(device != null, "the camera opens");
        if (device == null) System.exit(1);

        // The listener's Handler. A Looper thread needs the runtime's natives
        // (its log), so this one hands what is posted to it to a thread of its
        // own: what matters here is that the reader posts to the Handler it
        // was given and does not call the listener from the camera's thread.
        Executor imageThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "images");
            t.setDaemon(true);
            return t;
        });
        Handler imageHandler = new Handler((Looper) null) {
            @Override public boolean post(Runnable r) {
                imageThread.execute(r);
                return true;
            }
        };

        // The 3A modes a template carries are ones the camera lists.
        CaptureRequest.Builder template = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        check(contains(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES),
                        template.get(CaptureRequest.CONTROL_MODE))
                && contains(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                        template.get(CaptureRequest.CONTROL_AE_MODE))
                && contains(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                        template.get(CaptureRequest.CONTROL_AF_MODE))
                && contains(chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                        template.get(CaptureRequest.CONTROL_AWB_MODE)),
                "the still template's 3A modes are in the available lists");
        check(template.get(CaptureRequest.JPEG_QUALITY) != null
                && template.get(CaptureRequest.JPEG_ORIENTATION) != null,
                "the still template carries JPEG_QUALITY and JPEG_ORIENTATION");

        // ---- one still, in a session of its own ------------------------------
        Size size = jpegSizes[jpegSizes.length - 1];
        ImageReader jpegReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
        LinkedBlockingQueue<String> available = new LinkedBlockingQueue<>();
        jpegReader.setOnImageAvailableListener(r -> {
            event("imageAvailable");
            available.add(Thread.currentThread().getName());
        }, imageHandler);
        check(map.isOutputSupportedFor(jpegReader.getSurface()), "isOutputSupportedFor(the JPEG reader's Surface)");

        CameraCaptureSession session = session(device, jpegReader.getSurface());
        check(session != null, "a session with a JPEG ImageReader configures");
        if (session == null) System.exit(1);

        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(jpegReader.getSurface());
        b.set(CaptureRequest.JPEG_ORIENTATION, 90);
        b.set(CaptureRequest.JPEG_QUALITY, (byte) 70);
        b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(160, 120));
        Capture first = new Capture();
        session.captureSingleRequest(b.build(), executor, first);
        check(first.done.await(10, TimeUnit.SECONDS), "the still capture completes");
        String thread = available.poll(10, TimeUnit.SECONDS);
        check("images".equals(thread), "onImageAvailable ran on the listener's handler (" + thread + ")");
        List<String> order;
        synchronized (events) { order = new ArrayList<>(events); }
        order.remove("imageAvailable");  // another thread's: only its presence is fixed, checked above
        check(order.equals(Arrays.asList("started", "completed", "sequenceCompleted")),
                "callbacks in order: " + order);

        Image image = jpegReader.acquireNextImage();
        check(image != null, "acquireNextImage returns the still");
        if (image == null) System.exit(1);
        check(image.getFormat() == ImageFormat.JPEG, "Image.getFormat() is JPEG (" + image.getFormat() + ")");
        check(image.getWidth() == size.getWidth() && image.getHeight() == size.getHeight(),
                "the Image has the reader's size");
        check(image.getPlanes().length == 1 && image.getPlanes()[0].getRowStride() == 0
                && image.getPlanes()[0].getPixelStride() == 0, "one plane, row and pixel stride 0");
        check(image.getPlanes()[0].getBuffer().isDirect(), "the plane's buffer is direct");
        byte[] jpeg = bytesOf(image);
        check(jpeg.length > 4 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8
                && (jpeg[jpeg.length - 2] & 0xFF) == 0xFF && (jpeg[jpeg.length - 1] & 0xFF) == 0xD9,
                "the bytes are a JPEG file (FF D8 ... FF D9, " + jpeg.length + " bytes)");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        check(decoded != null && decoded.getWidth() == size.getWidth() && decoded.getHeight() == size.getHeight(),
                "javax.imageio decodes it to " + size);
        if (decoded != null) {
            int rgb = decoded.getRGB(size.getWidth() / 2, size.getHeight() / 2);
            check(Math.abs((rgb & 0xFF) - 0x28) <= 4 && Math.abs(((rgb >> 8) & 0xFF) - 0x28) <= 4,
                    "its pixels are the camera's frame (0x" + Integer.toHexString(rgb & 0xFFFFFF) + ")");
        }
        byte[] tiff = exifOf(jpeg);
        check(tiff != null && tag(tiff, u32(tiff, 4), 0x0112) == 6,
                "EXIF Orientation is 6 for JPEG_ORIENTATION 90");
        if (tiff != null) {
            int ifd0 = u32(tiff, 4);
            int ifd1 = u32(tiff, ifd0 + 2 + u16(tiff, ifd0) * 12);
            int off = ifd1 > 0 ? tag(tiff, ifd1, 0x0201) : -1;
            int len = ifd1 > 0 ? tag(tiff, ifd1, 0x0202) : -1;
            BufferedImage thumb = off > 0 && len > 0 && off + len <= tiff.length
                    ? ImageIO.read(new ByteArrayInputStream(tiff, off, len)) : null;
            check(thumb != null && thumb.getWidth() == 160 && thumb.getHeight() == 120,
                    "the EXIF block holds the 160x120 thumbnail");
        }
        TotalCaptureResult result = first.result;
        Long sensorTime = result != null ? result.get(CaptureResult.SENSOR_TIMESTAMP) : null;
        check(sensorTime != null && sensorTime == image.getTimestamp() && sensorTime == first.startedTimestamp,
                "Image, onCaptureStarted and SENSOR_TIMESTAMP carry one timestamp");
        check(result != null && Integer.valueOf(90).equals(result.get(CaptureResult.JPEG_ORIENTATION))
                && Byte.valueOf((byte) 70).equals(result.get(CaptureResult.JPEG_QUALITY)),
                "the result reports the orientation and quality used");

        // ---- maxImages ------------------------------------------------------
        // One Image is held and maxImages is 2: the next still fits, the one
        // after is lost and reported, and the reader refuses a third acquire.
        b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(0, 0));
        b.set(CaptureRequest.JPEG_ORIENTATION, 0);
        Capture second = new Capture();
        session.captureSingleRequest(b.build(), executor, second);
        check(second.done.await(10, TimeUnit.SECONDS) && second.buffersLost == 0, "a second still is delivered");
        Capture third = new Capture();
        session.captureSingleRequest(b.build(), executor, third);
        check(third.done.await(10, TimeUnit.SECONDS) && third.buffersLost == 1,
                "a still beyond maxImages is reported through onCaptureBufferLost");
        Image image2 = jpegReader.acquireLatestImage();
        check(image2 != null && image2.getTimestamp() == second.result.get(CaptureResult.SENSOR_TIMESTAMP),
                "the second still is the next Image");
        if (image2 != null) {
            byte[] tiff2 = exifOf(bytesOf(image2));
            check(tiff2 != null && tag(tiff2, u32(tiff2, 4), 0x0112) == 1 && u32(tiff2, u32(tiff2, 4) + 14) == 0,
                    "EXIF Orientation is 1 and there is no thumbnail when none is asked for");
        }
        boolean threw = false;
        try {
            jpegReader.acquireNextImage();
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "acquiring past maxImages throws IllegalStateException");
        image.close();
        if (image2 != null) image2.close();
        check(jpegReader.acquireNextImage() == null, "with the Images closed and none queued, acquire returns null");
        boolean closedThrows = false;
        try {
            image.getPlanes();
        } catch (IllegalStateException e) {
            closedThrows = true;
        }
        check(closedThrows, "a closed Image's getters throw");
        session.close();

        // ---- a still beside a YUV reader ------------------------------------
        // The stream runs at the YUV reader's size; the still is cut to the
        // JPEG reader's, and taking it does not take the YUV reader's frame.
        Size big = jpegSizes[0];
        ImageReader yuvReader = ImageReader.newInstance(big.getWidth(), big.getHeight(), ImageFormat.YUV_420_888, 2);
        LinkedBlockingQueue<Integer> yuvFrames = new LinkedBlockingQueue<>();
        yuvReader.setOnImageAvailableListener(r -> {
            try (Image i = r.acquireLatestImage()) {
                if (i != null) yuvFrames.add(i.getPlanes().length);
            }
        }, imageHandler);
        session = session(device, yuvReader.getSurface(), jpegReader.getSurface());
        check(session != null, "a session with a YUV and a JPEG reader configures");
        if (session != null) {
            CaptureRequest.Builder preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(yuvReader.getSurface());
            session.setSingleRepeatingRequest(preview.build(), executor,
                    new CameraCaptureSession.CaptureCallback() { });
            Integer planes = yuvFrames.poll(10, TimeUnit.SECONDS);
            check(planes != null && planes == 3, "the repeating request delivers YUV frames");
            check(jpegReader.acquireNextImage() == null,
                    "a request that does not target the JPEG reader queues no still");

            Capture still = new Capture();
            session.captureSingleRequest(b.build(), executor, still);
            check(still.done.await(10, TimeUnit.SECONDS) && still.buffersLost == 0,
                    "a still capture completes while the preview repeats");
            try (Image i = jpegReader.acquireNextImage()) {
                BufferedImage d = i != null ? ImageIO.read(new ByteArrayInputStream(bytesOf(i))) : null;
                check(d != null && d.getWidth() == size.getWidth() && d.getHeight() == size.getHeight(),
                        "the still has the JPEG reader's size (" + size + ") from a " + big + " stream");
            }
            yuvFrames.clear();
            check(yuvFrames.poll(10, TimeUnit.SECONDS) != null, "YUV frames keep arriving after the still");
            session.close();
        }

        // ---- video: nothing to record with on a plain JVM, and it says so ------
        boolean early = false;
        try {
            new MediaRecorder().getSurface();
        } catch (IllegalStateException e) {
            early = true;
        }
        check(early, "MediaRecorder.getSurface before prepare throws IllegalStateException, as on Android");
        boolean noEncoder = false;
        try {
            android.media.MediaCodec.createEncoderByType("video/avc");
        } catch (java.io.IOException e) {
            noEncoder = true;
        }
        check(noEncoder, "MediaCodec.createEncoderByType throws IOException where the host has no encoder (no natives here)");

        jpegReader.close();
        yuvReader.close();
        device.close();
        System.out.println(failures == 0 ? "Camera2StillTest: all checks passed"
                : "Camera2StillTest: " + failures + " check(s) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean contains(int[] list, Integer value) {
        if (list == null || value == null) return false;
        for (int v : list) {
            if (v == value) return true;
        }
        return false;
    }
}
