// camera_capture.mm -- native macOS camera capture for Mac.apk.
//
// The layer below android.hardware.camera2: an AVCaptureSession +
// AVCaptureVideoDataOutput engine, plus the JNI seam
// (com.macapk.loader.CameraBridge) that a real camera2 implementation binds
// onto. See runtime/include/macapk/camera_capture.h for the C contract and
// runtime/java/com/macapk/loader/CameraBridge.java for the Java side.
//
// Mac.apk previously reported no camera at all: Camera.getNumberOfCameras()
// returned 0 and openCamera threw. Every MacBook has a camera, so the honest
// answer is to wire the real one in. This file is that wiring.
//
// HONESTY RULE FOR THIS SUBSYSTEM: nothing here declares that a camera exists
// unless AVFoundation says so, and nothing claims a frame is available until
// one has actually been delivered. An app that gets a camera id and then no
// frames fails deeper and more confusingly than an app that sees no camera.
// macapk_cam_frames_delivered() exists so a caller can prove liveness rather
// than assume it.
//
// NO ARC IN THIS TU (matches cocoa_bridge.mm / audio_backend.mm): ObjC objects
// are retained explicitly and released in macapk_cam_close().
//
// DESIGN NOTES
//   - The session is created LAZILY, only in macapk_cam_open(). Enumeration
//     never constructs one, so an app that merely asks "how many cameras are
//     there" never opens a device and never raises a TCC prompt.
//   - alwaysDiscardsLateVideoFrames = YES and we keep exactly ONE pending
//     frame. That is the same "latest wins" policy as
//     ImageReader.acquireLatestImage(), and it means a slow Java consumer
//     drops frames instead of growing an unbounded queue or stalling capture.
//   - The delegate callback runs on a private serial dispatch queue. It does
//     the minimum: retain the CVPixelBuffer, swap it into the pending slot,
//     release the one it displaced. No Java, no JNI, no allocation.

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

#include "macapk/camera_capture.h"

// ===========================================================================
// PIXEL FORMAT: Android YUV_420_888  <->  CoreVideo NV12
// ===========================================================================
//
// This is the single most error-prone part of the subsystem. A wrong stride
// produces a green-and-magenta image that looks like a driver bug, so both
// layouts are spelled out here in full, per the project's shim convention.
//
// --- ANDROID SIDE: ImageFormat.YUV_420_888 (= 0x23) ------------------------
// Pinned verbatim from AOSP graphics/java/android/graphics/ImageFormat.java:
//
//   "This format is a generic YCbCr format, capable of describing any 4:2:0
//    chroma-subsampled planar or semiplanar buffer (but not fully
//    interleaved), with 8 bits per color sample."
//
//   "Images in this format are always represented by three separate buffers
//    of data, one for each color plane."
//
//   "The order of planes ... is guaranteed such that plane #0 is always Y,
//    plane #1 is always U (Cb), and plane #2 is always V (Cr)."
//
//   "The Y-plane is guaranteed not to be interleaved with the U/V planes (in
//    particular, pixel stride is always 1 in yPlane.getPixelStride())."
//
//   "The U/V planes are guaranteed to have the same row stride and pixel
//    stride (in particular, uPlane.getRowStride() == vPlane.getRowStride()
//    and uPlane.getPixelStride() == vPlane.getPixelStride())."
//
// The word that makes this whole mapping work is SEMIPLANAR. YUV_420_888 does
// NOT require three separate allocations -- it requires three plane
// DESCRIPTORS. A semiplanar buffer is described by pointing U and V at the
// same interleaved chroma plane, one byte apart, both with pixelStride 2.
// That is exactly NV12. So no conversion and no copy is required; the plane
// descriptors carry all the information.
//
// --- APPLE SIDE: kCVPixelFormatType_420YpCbCr8BiPlanar{Video,Full}Range ----
// Pinned verbatim from the local SDK header (the authority, not the web docs,
// which quote 10-bit-scaled numbers):
//   MacOSX.sdk/System/Library/Frameworks/CoreVideo.framework/Headers/CVPixelBuffer.h:79-80
//
//   kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange = '420v',
//       /* Bi-Planar Component Y'CbCr 8-bit 4:2:0, video-range
//          (luma=[16,235] chroma=[16,240]). */
//   kCVPixelFormatType_420YpCbCr8BiPlanarFullRange  = '420f',
//       /* Bi-Planar Component Y'CbCr 8-bit 4:2:0, full-range
//          (luma=[0,255] chroma=[1,255]). */
//
// Both are 2-plane NV12: plane 0 is Y at full resolution, 1 byte per sample;
// plane 1 is Cb and Cr INTERLEAVED at half width and half height, 2 bytes per
// chroma sample pair, Cb FIRST (NV12, not NV21).
//
// --- THE MAPPING ----------------------------------------------------------
// For a WxH frame, with yRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 0) and
// cRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 1):
//
//   Android plane   source                         rowStride  pixelStride
//   -------------   ----------------------------   ---------  -----------
//   0  Y            baseAddressOfPlane(0)          yRow       1
//   1  U (Cb)       baseAddressOfPlane(1) + 0      cRow       2
//   2  V (Cr)       baseAddressOfPlane(1) + 1      cRow       2
//
// The +1 byte offset on the V plane is the entire difference between U and V,
// and it is what "semiplanar" means. Getting it backwards swaps Cb and Cr,
// which turns skin tones green and skies magenta -- the classic symptom.
//
// Note that yRow and cRow are NOT W and W/2: CoreVideo pads rows for
// alignment, so ALWAYS read the stride from the buffer and never compute it
// from the width. That is the specific mistake this comment exists to prevent.
//
// --- COLOR RANGE: a judgment call, deliberately, and here is why -----------
// AOSP's YUV_420_888 contract says NOTHING about luma/chroma range (verified:
// the javadoc quoted above is the whole specification, and camera2 has no
// range key either). Apple's two formats differ ONLY in range. So the mapping
// above is exact for both, and the choice between them is not derivable from
// the Android spec.
//
// We request '420f' (FULL range) by default, because the de-facto convention
// for Android camera YUV is the JFIF/JPEG full-range encoding -- that is what
// android.graphics.YuvImage assumes when it compresses NV21 to JPEG, and what
// the common YUV->RGB shaders in Android apps assume. Requesting full range
// means AVFoundation performs the range expansion itself (hardware-assisted)
// and a consumer doing full-range math needs no correction.
//
// This host's built-in camera advertises '420v' natively but
// AVCaptureVideoDataOutput offers '420f' in availableVideoCVPixelFormatTypes,
// so AVFoundation converts (measured on macOS 26, 2026-08-10).
//
// MACAPK_CAM_VIDEO_RANGE=1 selects '420v' instead, so a future session can A/B
// the range against a real app in one run rather than rebuilding. If an app's
// preview looks washed out (blacks lifted, whites dulled) it is decoding
// full-range data with limited-range math, and this is the switch to try.

namespace {

bool trace_on() {
    static int v = -1;
    if (v < 0) v = std::getenv("MACAPK_CAMERA_TRACE") ? 1 : 0;
    return v == 1;
}

// Full range by default; see the COLOR RANGE note above.
OSType wanted_pixel_format() {
    static OSType f = 0;
    if (f == 0) {
        const char* e = std::getenv("MACAPK_CAM_VIDEO_RANGE");
        f = (e && *e == '1') ? kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
                             : kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
    }
    return f;
}

// The device types we consider. AVCaptureDeviceTypeExternalUnknown is
// deprecated on macOS 26; AVCaptureDeviceTypeExternal replaces it.
NSArray<AVCaptureDeviceType>* device_types() {
    return @[
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVCaptureDeviceTypeExternal,
        AVCaptureDeviceTypeContinuityCamera,
        AVCaptureDeviceTypeDeskViewCamera,
    ];
}

// Snapshot of the discovered devices. Re-taken by macapk_cam_device_count()
// so hot-plug is picked up, and stable in between so an index handed to
// macapk_cam_open() still means the same device. Callers that enumerate then
// open without an intervening count() see a consistent list.
std::mutex g_devices_lock;
NSArray<AVCaptureDevice*>* g_devices = nil;

NSArray<AVCaptureDevice*>* refresh_devices() {
    AVCaptureDeviceDiscoverySession* ds =
        [AVCaptureDeviceDiscoverySession
            discoverySessionWithDeviceTypes:device_types()
                                  mediaType:AVMediaTypeVideo
                                   position:AVCaptureDevicePositionUnspecified];
    NSArray<AVCaptureDevice*>* found = [ds.devices retain];
    std::lock_guard<std::mutex> g(g_devices_lock);
    if (g_devices) [g_devices release];
    g_devices = found;
    return g_devices;
}

NSArray<AVCaptureDevice*>* devices_snapshot() {
    {
        std::lock_guard<std::mutex> g(g_devices_lock);
        if (g_devices) return g_devices;
    }
    return refresh_devices();
}

AVCaptureDevice* device_at(int index) {
    NSArray<AVCaptureDevice*>* d = devices_snapshot();
    if (index < 0 || (NSUInteger)index >= d.count) return nil;
    return [d objectAtIndex:(NSUInteger)index];
}

// A MacBook's built-in camera points at the user, which is Android's FRONT.
// Anything else is what Android calls EXTERNAL (a USB/UVC camera).
int32_t facing_for(AVCaptureDevice* d) {
    if ([d.deviceType isEqualToString:AVCaptureDeviceTypeBuiltInWideAngleCamera])
        return MACAPK_CAM_FACING_FRONT;
    return MACAPK_CAM_FACING_EXTERNAL;
}

void copy_str(char* dst, size_t cap, NSString* src) {
    if (!src) { dst[0] = 0; return; }
    const char* u = src.UTF8String;
    if (!u) { dst[0] = 0; return; }
    std::strncpy(dst, u, cap - 1);
    dst[cap - 1] = 0;
}

}  // namespace

// ===========================================================================
// Session
// ===========================================================================

@class MacapkCamDelegate;

struct MacapkCamSession {
    AVCaptureSession*         session  = nil;
    AVCaptureDevice*          device   = nil;
    AVCaptureDeviceInput*     input    = nil;
    AVCaptureVideoDataOutput* output   = nil;
    MacapkCamDelegate*        delegate = nil;
    dispatch_queue_t          queue    = nullptr;

    std::mutex       lock;
    CVPixelBufferRef pending   = nullptr;   // newest undrained frame
    int64_t          pendingPtsNs = 0;
    CVPixelBufferRef held      = nullptr;   // locked, owned by the consumer
    MacapkCamFrame   lastFrame{};           // descriptor for `held`

    // Newest delivered frame, for the SurfaceTexture RGBA-copy path. A second
    // retain on the SAME buffer `pending` points at, so a copy can still read
    // the newest frame after an ImageReader acquire() consumed the pending
    // slot. Replaced on every delivery; at most one extra pool buffer is
    // withheld (after an acquire+release, until the next frame lands), which
    // AVFoundation's capture pools absorb without throttling.
    CVPixelBufferRef latest    = nullptr;
    int64_t          latestPtsNs = 0;

    std::atomic<int64_t> delivered{0};
    int64_t          frameNumber = 0;
    int              width  = 0;
    int              height = 0;
    bool             running = false;
};

// Delegate: runs on the session's private serial queue.
@interface MacapkCamDelegate : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate>
@property (nonatomic, assign) MacapkCamSession* owner;
@end

@implementation MacapkCamDelegate

- (void)captureOutput:(AVCaptureOutput*)__unused output
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
           fromConnection:(AVCaptureConnection*)__unused connection {
    MacapkCamSession* s = self.owner;
    if (!s) return;

    CVImageBufferRef img = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!img) return;

    CMTime pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
    // Android's Image.getTimestamp() is nanoseconds on a monotonic clock whose
    // domain is SENSOR_INFO_TIMESTAMP_SOURCE. AVFoundation's capture PTS is on
    // the host clock, which is monotonic but not comparable to other
    // subsystems -- exactly the contract of TIMESTAMP_SOURCE_UNKNOWN, which is
    // therefore what camera2 should report for these devices.
    int64_t ns = CMTIME_IS_VALID(pts)
        ? (int64_t)(CMTimeGetSeconds(pts) * 1.0e9) : 0;

    CVPixelBufferRef pb = (CVPixelBufferRef)CFRetain(img);
    CVPixelBufferRef pbLatest = (CVPixelBufferRef)CFRetain(img);

    CVPixelBufferRef displaced = nullptr;
    CVPixelBufferRef displacedLatest = nullptr;
    {
        std::lock_guard<std::mutex> g(s->lock);
        displaced = s->pending;          // drop the older undrained frame
        s->pending = pb;
        s->pendingPtsNs = ns;
        displacedLatest = s->latest;     // second ref, survives an acquire()
        s->latest = pbLatest;
        s->latestPtsNs = ns;
    }
    if (displaced) CFRelease(displaced);
    if (displacedLatest) CFRelease(displacedLatest);

    int64_t n = s->delivered.fetch_add(1) + 1;
    if (trace_on() && (n == 1 || n % 60 == 0)) {
        std::fprintf(stderr, "[camera] frame %lld %zux%zu pts=%lldns\n",
                     (long long)n,
                     CVPixelBufferGetWidth(pb), CVPixelBufferGetHeight(pb),
                     (long long)ns);
    }
}

- (void)captureOutput:(AVCaptureOutput*)__unused output
    didDropSampleBuffer:(CMSampleBufferRef)__unused sampleBuffer
         fromConnection:(AVCaptureConnection*)__unused connection {
    // Expected under alwaysDiscardsLateVideoFrames when the consumer is slow.
    // Not an error; only traced so a stall is visible when hunting one.
    if (trace_on()) std::fprintf(stderr, "[camera] late frame dropped\n");
}

@end

// ===========================================================================
// C API
// ===========================================================================

extern "C" {

int macapk_cam_device_count(void) {
    @autoreleasepool {
        // Deliberately re-enumerates: this is the call that picks up hot-plug,
        // and it is the one a caller makes before indexing into the list.
        NSArray<AVCaptureDevice*>* d = refresh_devices();
        return (int)d.count;
    }
}

bool macapk_cam_device_info(int index, MacapkCamDeviceInfo* out) {
    if (!out) return false;
    @autoreleasepool {
        AVCaptureDevice* d = device_at(index);
        if (!d) return false;
        std::memset(out, 0, sizeof(*out));
        copy_str(out->id,   sizeof(out->id),   d.uniqueID);
        copy_str(out->name, sizeof(out->name), d.localizedName);
        out->facing = facing_for(d);
        // A Mac's camera sensor does not rotate with the device, so its BASE
        // orientation is 0. The value apps are told is
        // CameraBridge.getSensorOrientation, which adds what cancels the
        // window's reported display rotation.
        out->sensorOrientation = 0;
        out->formatCount = (int32_t)d.formats.count;
        return true;
    }
}

int macapk_cam_format_count(int index) {
    @autoreleasepool {
        AVCaptureDevice* d = device_at(index);
        return d ? (int)d.formats.count : 0;
    }
}

bool macapk_cam_format_info(int index, int formatIndex, MacapkCamFormat* out) {
    if (!out) return false;
    @autoreleasepool {
        AVCaptureDevice* d = device_at(index);
        if (!d) return false;
        if (formatIndex < 0 || (NSUInteger)formatIndex >= d.formats.count) return false;
        AVCaptureDeviceFormat* f = [d.formats objectAtIndex:(NSUInteger)formatIndex];
        CMVideoDimensions dim =
            CMVideoFormatDescriptionGetDimensions(f.formatDescription);
        std::memset(out, 0, sizeof(*out));
        out->width  = dim.width;
        out->height = dim.height;
        for (AVFrameRateRange* r in f.videoSupportedFrameRateRanges) {
            if (out->maxFps == 0 || r.maxFrameRate > out->maxFps) out->maxFps = r.maxFrameRate;
            if (out->minFps == 0 || r.minFrameRate < out->minFps) out->minFps = r.minFrameRate;
        }
        return true;
    }
}

int macapk_cam_auth_status(void) {
    @autoreleasepool {
        return (int)[AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    }
}

// macOS refuses the camera OUTRIGHT, with no prompt, when the responsible
// bundle's Info.plist has no NSCameraUsageDescription. For an installed app that
// bundle is /Applications/<App>.app (the stub execs us through its
// Contents/MacOS link, so NSBundle.mainBundle resolves to it). A bundle
// generated before 2026-08-24 lacks the key; the control panel re-stamps it
// (InstallPipeline.refreshStalePurposeStrings). Say so ONCE, loudly, instead of
// letting the app see an unexplained ERROR_CAMERA_DISABLED.
static void warn_if_no_camera_purpose_string() {
    static std::atomic<bool> once{false};
    if (once.exchange(true)) return;
    NSBundle* b = [NSBundle mainBundle];
    if (![b.bundlePath hasSuffix:@".app"]) return;   // dev run: no bundle to blame
    if ([b objectForInfoDictionaryKey:@"NSCameraUsageDescription"] != nil) return;
    std::fprintf(stderr,
        "[camera] %s has NO NSCameraUsageDescription: macOS denies the camera "
        "WITHOUT prompting. The bundle predates the purpose strings; opening the "
        "Mac.apk control panel re-stamps it (quit this app first).\n",
        b.bundlePath.UTF8String);
}

void macapk_cam_request_auth(void) {
    @autoreleasepool {
        warn_if_no_camera_purpose_string();
        if ([AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]
                != AVAuthorizationStatusNotDetermined) {
            // Already decided. Re-requesting would not re-prompt anyway, and
            // asking again from a denied state must not block the caller.
            return;
        }
        // NON-BLOCKING on purpose. The completion handler runs on an arbitrary
        // queue; blocking a Java thread until it fires would deadlock an app
        // that requested permission from its UI thread, which is exactly what
        // an Android app does. The caller polls macapk_cam_auth_status().
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo
                                 completionHandler:^(BOOL granted) {
            if (trace_on())
                std::fprintf(stderr, "[camera] authorization %s\n",
                             granted ? "GRANTED" : "DENIED");
        }];
    }
}

MacapkCamSession* macapk_cam_open(int index) {
    @autoreleasepool {
        // Denied/restricted must report cleanly and immediately. Creating an
        // AVCaptureDeviceInput while denied yields a device that never delivers
        // a frame, which is precisely the "camera id but no frames" failure the
        // honesty rule forbids -- so we refuse here instead.
        AVAuthorizationStatus st =
            [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
        if (st == AVAuthorizationStatusDenied || st == AVAuthorizationStatusRestricted) {
            warn_if_no_camera_purpose_string();
            std::fprintf(stderr, "[camera] open refused: access %s\n",
                         st == AVAuthorizationStatusDenied ? "denied" : "restricted");
            return nullptr;
        }

        AVCaptureDevice* dev = device_at(index);
        if (!dev) return nullptr;

        NSError* err = nil;
        AVCaptureDeviceInput* in = [AVCaptureDeviceInput deviceInputWithDevice:dev
                                                                        error:&err];
        if (!in || err) {
            std::fprintf(stderr, "[camera] deviceInputWithDevice failed: %s\n",
                         err ? err.localizedDescription.UTF8String : "(null)");
            return nullptr;
        }

        MacapkCamSession* s = new MacapkCamSession();
        s->device  = [dev retain];
        s->input   = [in retain];
        s->session = [[AVCaptureSession alloc] init];

        if (![s->session canAddInput:s->input]) {
            std::fprintf(stderr, "[camera] session cannot add input\n");
            macapk_cam_close(s);
            return nullptr;
        }
        [s->session addInput:s->input];

        s->output = [[AVCaptureVideoDataOutput alloc] init];
        s->output.alwaysDiscardsLateVideoFrames = YES;
        s->output.videoSettings = @{
            (id)kCVPixelBufferPixelFormatTypeKey : @(wanted_pixel_format()),
        };

        s->queue = dispatch_queue_create("org.macapk.camera",
                                         DISPATCH_QUEUE_SERIAL);
        s->delegate = [[MacapkCamDelegate alloc] init];
        s->delegate.owner = s;
        [s->output setSampleBufferDelegate:s->delegate queue:s->queue];

        if (![s->session canAddOutput:s->output]) {
            std::fprintf(stderr, "[camera] session cannot add output\n");
            macapk_cam_close(s);
            return nullptr;
        }
        [s->session addOutput:s->output];

        // Default geometry from the device's active format, so a caller that
        // never configures still gets truthful width/height.
        CMVideoDimensions dim = CMVideoFormatDescriptionGetDimensions(
            dev.activeFormat.formatDescription);
        s->width  = dim.width;
        s->height = dim.height;

        if (trace_on())
            std::fprintf(stderr, "[camera] opened '%s' %dx%d\n",
                         dev.localizedName.UTF8String, s->width, s->height);
        return s;
    }
}

bool macapk_cam_configure(MacapkCamSession* s, int width, int height, int fps) {
    if (!s || !s->device) return false;
    @autoreleasepool {
        // Choose the advertised format closest in AREA to the request. Android
        // apps pick from the sizes we advertised, so this is normally an exact
        // hit; the nearest-area fallback keeps a mismatched request working
        // instead of failing the session.
        AVCaptureDeviceFormat* best = nil;
        long long bestDelta = 0;
        long long want = (long long)width * (long long)height;
        for (AVCaptureDeviceFormat* f in s->device.formats) {
            CMVideoDimensions d = CMVideoFormatDescriptionGetDimensions(f.formatDescription);
            long long area = (long long)d.width * (long long)d.height;
            long long delta = area > want ? area - want : want - area;
            if (!best || delta < bestDelta) { best = f; bestDelta = delta; }
        }
        if (!best) return false;

        NSError* err = nil;
        if (![s->device lockForConfiguration:&err]) {
            std::fprintf(stderr, "[camera] lockForConfiguration failed: %s\n",
                         err ? err.localizedDescription.UTF8String : "(null)");
            return false;
        }
        s->device.activeFormat = best;

        if (fps > 0) {
            // Clamp into a range the chosen format actually supports; setting a
            // duration outside it throws an NSException that would take down
            // the JVM rather than surface as a Java error.
            double lo = 0, hi = 0;
            for (AVFrameRateRange* r in best.videoSupportedFrameRateRanges) {
                if (hi == 0 || r.maxFrameRate > hi) hi = r.maxFrameRate;
                if (lo == 0 || r.minFrameRate < lo) lo = r.minFrameRate;
            }
            double want_fps = (double)fps;
            if (hi > 0 && want_fps > hi) want_fps = hi;
            if (lo > 0 && want_fps < lo) want_fps = lo;
            if (want_fps > 0) {
                CMTime dur = CMTimeMake((int64_t)1000, (int32_t)(want_fps * 1000.0));
                s->device.activeVideoMinFrameDuration = dur;
                s->device.activeVideoMaxFrameDuration = dur;
            }
        }
        [s->device unlockForConfiguration];

        CMVideoDimensions d = CMVideoFormatDescriptionGetDimensions(best.formatDescription);
        s->width  = d.width;
        s->height = d.height;
        // Make the DELIVERED frames match the size reported above. On macOS,
        // startRunning re-applies the session preset (High = the camera's
        // largest format) over an activeFormat chosen while unlocked, so the
        // camera kept delivering 1920x1080 while this session reported the
        // 640x480 the app asked for -- measured 2026-11-12 on OurFamilyWizard's
        // Twilio preview (WebRTC sizes its frames from the requested format,
        // so the 16:9 image was squeezed into 4:3). Asking the output to scale
        // to the chosen dimensions makes the delivered size right whatever the
        // preset does. How a differently shaped capture is fitted is
        // AVFoundation's default for a data output, which is not set here: on
        // the measured camera the 640x480 frames kept the picture's
        // proportions (the same preview, 2026-11-12). A camera where they do
        // not would want AVVideoScalingModeKey here.
        s->output.videoSettings = @{
            (id)kCVPixelBufferPixelFormatTypeKey : @(wanted_pixel_format()),
            (id)kCVPixelBufferWidthKey  : @(s->width),
            (id)kCVPixelBufferHeightKey : @(s->height),
        };
        // The pixel buffers must be UNMIRRORED: the front camera's mirror is
        // applied downstream, once, by the transform matrix that
        // android.hardware.Camera / camera2 hand to SurfaceTexture consumers
        // (MTX_FLIP_V_FRONT), as a device's front-camera stream transform
        // does. If AVFoundation mirrored the buffers as well, the two would
        // cancel. Measured 2026-11-12: on a MacBook Air's built-in camera the
        // connection was already unmirrored (supported=1 auto=0 mirrored=0),
        // so this is a guard, not the fix for a measured fault; it pins the
        // state for cameras or macOS versions that default otherwise.
        AVCaptureConnection* conn = [s->output connectionWithMediaType:AVMediaTypeVideo];
        if (conn) {
            if (trace_on())
                std::fprintf(stderr, "[camera] connection mirroring before: supported=%d "
                             "auto=%d mirrored=%d\n", (int)conn.isVideoMirroringSupported,
                             (int)conn.automaticallyAdjustsVideoMirroring,
                             (int)conn.isVideoMirrored);
            if (conn.isVideoMirroringSupported) {
                conn.automaticallyAdjustsVideoMirroring = NO;
                conn.videoMirrored = NO;
            }
        }
        if (trace_on())
            std::fprintf(stderr, "[camera] configured %dx%d @%dfps (asked %dx%d)\n",
                         s->width, s->height, fps, width, height);
        return true;
    }
}

int macapk_cam_width(MacapkCamSession* s)  { return s ? s->width  : 0; }
int macapk_cam_height(MacapkCamSession* s) { return s ? s->height : 0; }

bool macapk_cam_start(MacapkCamSession* s) {
    if (!s || !s->session) return false;
    @autoreleasepool {
        if (s->running) return true;
        // startRunning blocks until the session is configured and is documented
        // as potentially slow, so it must never be called on a thread holding
        // our frame lock. It does not need the main thread.
        [s->session startRunning];
        s->running = s->session.isRunning;
        if (trace_on())
            std::fprintf(stderr, "[camera] start -> %s\n", s->running ? "running" : "FAILED");
        return s->running;
    }
}

void macapk_cam_stop(MacapkCamSession* s) {
    if (!s || !s->session) return;
    @autoreleasepool {
        if (!s->running) return;
        [s->session stopRunning];
        s->running = false;
        // Drop any undrained frame so a restart does not hand out a stale one.
        // The latest-frame ref goes too: a stopped camera must not keep
        // serving its final frame to a SurfaceTexture latch as if live.
        CVPixelBufferRef stale = nullptr;
        CVPixelBufferRef staleLatest = nullptr;
        {
            std::lock_guard<std::mutex> g(s->lock);
            stale = s->pending;
            s->pending = nullptr;
            staleLatest = s->latest;
            s->latest = nullptr;
            s->latestPtsNs = 0;
        }
        if (stale) CFRelease(stale);
        if (staleLatest) CFRelease(staleLatest);
    }
}

void macapk_cam_close(MacapkCamSession* s) {
    if (!s) return;
    @autoreleasepool {
        macapk_cam_release(s);   // unlock anything the consumer still holds
        macapk_cam_stop(s);

        // Detach the delegate BEFORE tearing down, so no callback can run
        // against a half-freed session. stopRunning above already drained the
        // capture pipeline, but setSampleBufferDelegate:nil is not documented
        // to WAIT for a block already queued -- and the delegate holds a raw
        // pointer to the session we are about to delete. So flush the queue
        // with an empty synchronous barrier: when it returns, nothing is in
        // flight. (Safe because close() is never called from the capture
        // queue itself; that would self-deadlock.)
        if (s->output) [s->output setSampleBufferDelegate:nil queue:nullptr];
        if (s->queue) dispatch_sync(s->queue, ^{});
        if (s->session) {
            if (s->input)  [s->session removeInput:s->input];
            if (s->output) [s->session removeOutput:s->output];
        }
        if (s->delegate) { s->delegate.owner = nullptr; [s->delegate release]; }
        if (s->output)  [s->output release];
        if (s->input)   [s->input release];
        if (s->session) [s->session release];
        if (s->device)  [s->device release];
        if (s->queue)   dispatch_release(s->queue);

        CVPixelBufferRef stale = s->pending;
        s->pending = nullptr;
        if (stale) CFRelease(stale);
        CVPixelBufferRef staleLatest = s->latest;
        s->latest = nullptr;
        if (staleLatest) CFRelease(staleLatest);

        delete s;
    }
}

int64_t macapk_cam_frames_delivered(MacapkCamSession* s) {
    return s ? s->delivered.load() : 0;
}

bool macapk_cam_acquire(MacapkCamSession* s, MacapkCamFrame* out) {
    if (!s) return false;
    CVPixelBufferRef pb = nullptr;
    int64_t ptsNs = 0;
    {
        std::lock_guard<std::mutex> g(s->lock);
        if (s->held) return false;      // must release before acquiring again
        if (!s->pending) return false;  // nothing has arrived yet
        pb = s->pending;
        ptsNs = s->pendingPtsNs;
        s->pending = nullptr;
        s->held = pb;
    }

    // Lock read-only: we never write into the capture buffer, and read-only
    // lets CoreVideo skip invalidating any cached GPU copy.
    if (CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
        std::lock_guard<std::mutex> g(s->lock);
        s->held = nullptr;
        CFRelease(pb);
        return false;
    }

    MacapkCamFrame f{};
    f.width       = (int32_t)CVPixelBufferGetWidth(pb);
    f.height      = (int32_t)CVPixelBufferGetHeight(pb);
    f.timestampNs = ptsNs;
    f.frameNumber = ++s->frameNumber;

    const size_t planes = CVPixelBufferGetPlaneCount(pb);
    if (planes < 2) {
        // Not the biplanar layout we asked for. Refuse rather than hand back a
        // frame described by strides that do not apply to it.
        std::fprintf(stderr, "[camera] unexpected plane count %zu (wanted 2)\n", planes);
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        std::lock_guard<std::mutex> g(s->lock);
        s->held = nullptr;
        CFRelease(pb);
        return false;
    }

    uint8_t* yBase = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 0);
    uint8_t* cBase = (uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 1);
    const size_t yRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 0);
    const size_t cRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
    const size_t yH   = CVPixelBufferGetHeightOfPlane(pb, 0);
    const size_t yW   = CVPixelBufferGetWidthOfPlane(pb, 0);
    const size_t cH   = CVPixelBufferGetHeightOfPlane(pb, 1);
    const size_t cW   = CVPixelBufferGetWidthOfPlane(pb, 1);

    // Reachable bytes for a plane, as Android computes an Image.Plane buffer:
    // the last sample of the last row, not stride*height, because the final row
    // may be short. Clamped to the plane's own allocation so a direct
    // ByteBuffer handed to Java can never over-read the CVPixelBuffer.
    auto extent = [](size_t row, size_t h, size_t w, size_t pixStride, size_t skew) {
        if (h == 0 || w == 0) return (size_t)0;
        size_t need = row * (h - 1) + pixStride * (w - 1) + 1;
        size_t cap  = row * h;
        if (skew >= cap) return (size_t)0;
        cap -= skew;                       // V starts one byte into the plane
        return need < cap ? need : cap;
    };

    // See the PIXEL FORMAT block at the top of this file. Y is planar with
    // pixelStride 1; U and V are two interleaved views of the SAME chroma
    // plane, one byte apart, both with pixelStride 2 (NV12: Cb first).
    f.planeCount     = 3;
    f.planeData[0]   = yBase;
    f.rowStride[0]   = (int32_t)yRow;
    f.pixelStride[0] = 1;
    f.planeSize[0]   = (int32_t)extent(yRow, yH, yW, 1, 0);

    f.planeData[1]   = cBase;              // U = Cb, offset 0
    f.rowStride[1]   = (int32_t)cRow;
    f.pixelStride[1] = 2;
    f.planeSize[1]   = (int32_t)extent(cRow, cH, cW, 2, 0);

    f.planeData[2]   = cBase + 1;          // V = Cr, offset 1
    f.rowStride[2]   = (int32_t)cRow;
    f.pixelStride[2] = 2;
    f.planeSize[2]   = (int32_t)extent(cRow, cH, cW, 2, 1);

    s->lastFrame = f;
    if (out) *out = f;

    if (trace_on() && f.frameNumber == 1) {
        std::fprintf(stderr,
            "[camera] first frame %dx%d  Y row=%d size=%d  UV row=%d size=%d/%d px=2\n",
            f.width, f.height, f.rowStride[0], f.planeSize[0],
            f.rowStride[1], f.planeSize[1], f.planeSize[2]);
    }
    return true;
}

void macapk_cam_release(MacapkCamSession* s) {
    if (!s) return;
    CVPixelBufferRef pb = nullptr;
    {
        std::lock_guard<std::mutex> g(s->lock);
        pb = s->held;
        s->held = nullptr;
        std::memset(&s->lastFrame, 0, sizeof(s->lastFrame));
    }
    if (pb) {
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        CFRelease(pb);
    }
}

int64_t macapk_cam_latest_timestamp(MacapkCamSession* s) {
    if (!s) return 0;
    std::lock_guard<std::mutex> g(s->lock);
    return s->latest ? s->latestPtsNs : 0;
}

// A read of the newest delivered frame, split in three so the JNI seam can pin
// its destination array around the CONVERSION ALONE:
//   latest_frame_begin: takes s->lock (only to CFRetain `latest`), then the
//                       CoreVideo read lock. Must run OUTSIDE any critical.
//   latest_frame_convert: pure memory work over the locked planes. No mutex,
//                       no CoreVideo lock call, no JVM. Safe inside a critical.
//   latest_frame_end:   CoreVideo unlock + CFRelease.
// Why the split (docs/gclocker-critical-release-design_2026-09-25.md, ADVERSARIAL
// CRITIQUE P0 #2): nativeCopyLatestArgb held a GetPrimitiveArrayCritical pin
// while copy_latest_impl took s->lock, and nativePlaneBuffer held s->lock
// across NewDirectByteBuffer, a Java allocation. If that allocation needs a GC,
// JDK 25 makes it wait for every critical region to drain (JDK-8192647) and JDK
// 21 stalls it in GCLocker::stall_until_clear once the heap cannot expand,
// while the pinning thread waits for s->lock: a lock-order deadlock. The rule
// adopted (the same one cocoa_bridge.mm states for the present pixel window):
// no lock is taken inside a critical if any holder of that lock can allocate or
// make JNI calls, and nothing that can block runs inside one at all.
struct LatestFrameRead {
    CVPixelBufferRef pb = nullptr;
    int64_t pts = 0;
    bool locked = false;
};

static bool latest_frame_begin(MacapkCamSession* s, LatestFrameRead* f) {
    {
        std::lock_guard<std::mutex> g(s->lock);
        if (!s->latest) return false;
        f->pb = (CVPixelBufferRef)CFRetain(s->latest);
        f->pts = s->latestPtsNs;
    }
    f->locked = CVPixelBufferLockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly)
                    == kCVReturnSuccess;
    return true;
}

static void latest_frame_end(LatestFrameRead* f) {
    if (!f->pb) return;
    if (f->locked) CVPixelBufferUnlockBaseAddress(f->pb, kCVPixelBufferLock_ReadOnly);
    CFRelease(f->pb);
    f->pb = nullptr;
    f->locked = false;
}

// NV12 -> RGB conversion core for the SurfaceTexture path. See the header's
// contract and the PIXEL FORMAT block at the top of this file.
//
// COEFFICIENTS. The capture format is negotiated by wanted_pixel_format(), so
// the matrix here MUST track it or the output is visibly wrong in a way that
// looks like a driver bug (washed-out or crushed levels):
//   '420f' (default): FULL-range BT.601, the JFIF matrix (ITU-T T.871 s7):
//       R = Y + 1.402 (Cr-128)
//       G = Y - 0.344136 (Cb-128) - 0.714136 (Cr-128)
//       B = Y + 1.772 (Cb-128)
//   '420v' (MACAPK_CAM_VIDEO_RANGE=1): LIMITED-range BT.601 (Rec.601 with
//   Y in [16,235], C in [16,240]), the standard integer form:
//       R = 1.164 (Y-16) + 1.596 (Cr-128)
//       G = 1.164 (Y-16) - 0.391 (Cb-128) - 0.813 (Cr-128)
//       B = 1.164 (Y-16) + 2.018 (Cb-128)
// Both are evaluated in 8.8 fixed point with +128 rounding. BT.601 rather
// than BT.709 because that is what Android's own YUV_420_888 consumers
// (YuvImage, the RenderScript/libyuv converters) assume for camera frames.
static int64_t latest_frame_convert(const LatestFrameRead* f, void* dstRaw,
                                    int64_t capBytesOrPixels, int32_t outDims[2],
                                    bool packArgb) {
    if (!f->pb || !f->locked || !dstRaw) return 0;
    CVPixelBufferRef pb = f->pb;
    int64_t result = 0;
    const int32_t w = (int32_t)CVPixelBufferGetWidth(pb);
    const int32_t h = (int32_t)CVPixelBufferGetHeight(pb);
    if (outDims) { outDims[0] = w; outDims[1] = h; }

    const int64_t needPixels = (int64_t)w * (int64_t)h;
    const int64_t cap = packArgb ? capBytesOrPixels : capBytesOrPixels / 4;

    if (CVPixelBufferGetPlaneCount(pb) >= 2 && w > 0 && h > 0
            && cap >= needPixels) {
        const uint8_t* yBase = (const uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 0);
        const uint8_t* cBase = (const uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pb, 1);
        // The strides, from the buffer and ONLY from the buffer. CoreVideo
        // pads rows; computing an offset from `w` shears the image.
        const size_t yRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 0);
        const size_t cRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
        const bool fullRange =
            wanted_pixel_format() == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;

        uint8_t*  dstB = (uint8_t*)dstRaw;
        uint32_t* dstI = (uint32_t*)dstRaw;

        for (int32_t row = 0; row < h; row++) {
            // dst row 0 = image top row (Android buffer memory layout);
            // the flip to GL convention is the consumer's mtxFlipV.
            const uint8_t* yp = yBase + (size_t)row * yRow;
            const uint8_t* cp = cBase + (size_t)(row >> 1) * cRow;
            for (int32_t col = 0; col < w; col++) {
                const int Y = yp[col];
                const uint8_t* c2 = cp + (size_t)((col >> 1) << 1);
                const int D = (int)c2[0] - 128;   // Cb first: NV12, not NV21
                const int E = (int)c2[1] - 128;
                int r, g, b;
                if (fullRange) {
                    r = Y + ((359 * E + 128) >> 8);
                    g = Y - ((88 * D + 183 * E + 128) >> 8);
                    b = Y + ((454 * D + 128) >> 8);
                } else {
                    const int C = 298 * (Y - 16);
                    r = (C + 409 * E + 128) >> 8;
                    g = (C - 100 * D - 208 * E + 128) >> 8;
                    b = (C + 516 * D + 128) >> 8;
                }
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                if (packArgb) {
                    dstI[(size_t)row * (size_t)w + (size_t)col] =
                        0xFF000000u | ((uint32_t)r << 16)
                                    | ((uint32_t)g << 8) | (uint32_t)b;
                } else {
                    uint8_t* o = dstB + ((size_t)row * (size_t)w
                                         + (size_t)col) * 4;
                    o[0] = (uint8_t)r;
                    o[1] = (uint8_t)g;
                    o[2] = (uint8_t)b;
                    o[3] = 0xFF;
                }
            }
        }
        // 0 always means failure, so a valid frame whose PTS happened to
        // be 0 (CMTime invalid) still reports success.
        result = f->pts > 0 ? f->pts : 1;
    }
    return result;
}

static int64_t copy_latest_impl(MacapkCamSession* s, void* dstRaw,
                                int64_t capBytesOrPixels, int32_t outDims[2],
                                bool packArgb) {
    if (!s || !dstRaw) return 0;
    LatestFrameRead f;
    if (!latest_frame_begin(s, &f)) return 0;
    const int64_t result = latest_frame_convert(&f, dstRaw, capBytesOrPixels,
                                                outDims, packArgb);
    latest_frame_end(&f);
    return result;
}

int64_t macapk_cam_copy_latest_rgba(MacapkCamSession* s, uint8_t* dst,
                                    int64_t dstCapBytes, int32_t outDims[2]) {
    return copy_latest_impl(s, dst, dstCapBytes, outDims, /*packArgb=*/false);
}

int64_t macapk_cam_copy_latest_argb(MacapkCamSession* s, uint32_t* dst,
                                    int64_t dstCapPixels, int32_t outDims[2]) {
    return copy_latest_impl(s, dst, dstCapPixels, outDims, /*packArgb=*/true);
}

}  // extern "C"

// ===========================================================================
// JNI seam -- com.macapk.loader.CameraBridge
// ===========================================================================
//
// Bound via RegisterNatives from native_methods.cpp, not by symbol lookup: the
// JVM does not auto-resolve Java_* symbols out of the main executable on
// macOS. Same pattern as NativeAudio / NativeSqlite.

namespace macapk {
namespace camera {

namespace {

MacapkCamSession* sess(jlong h) { return reinterpret_cast<MacapkCamSession*>(h); }

// BISECT GATE, default ON: MACAPK_JNI_NO_CALLS_IN_CRITICAL=0 restores the
// pre-fix lock order below (s->lock taken inside nativeCopyLatestArgb's
// critical pin; NewDirectByteBuffer allocated under s->lock). Same variable as
// the EGL/GLES bridges' gate in native_methods.cpp's includes: one switch for
// every critical-window fix of docs/gclocker-critical-release-design_2026-09-25.md.
bool cam_critical_order_fixed() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_JNI_NO_CALLS_IN_CRITICAL");
        return !(e && std::strcmp(e, "0") == 0);
    }();
    return on;
}

jstring to_jstring(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s ? s : "");
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeDeviceCount(JNIEnv*, jclass) {
    return (jint)macapk_cam_device_count();
}

JNIEXPORT jstring JNICALL
Java_com_macapk_loader_CameraBridge_nativeDeviceId(JNIEnv* env, jclass, jint index) {
    MacapkCamDeviceInfo info{};
    if (!macapk_cam_device_info((int)index, &info)) return nullptr;
    return to_jstring(env, info.id);
}

JNIEXPORT jstring JNICALL
Java_com_macapk_loader_CameraBridge_nativeDeviceName(JNIEnv* env, jclass, jint index) {
    MacapkCamDeviceInfo info{};
    if (!macapk_cam_device_info((int)index, &info)) return nullptr;
    return to_jstring(env, info.name);
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeDeviceFacing(JNIEnv*, jclass, jint index) {
    MacapkCamDeviceInfo info{};
    if (!macapk_cam_device_info((int)index, &info)) return -1;
    return (jint)info.facing;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeDeviceSensorOrientation(JNIEnv*, jclass, jint index) {
    MacapkCamDeviceInfo info{};
    if (!macapk_cam_device_info((int)index, &info)) return 0;
    return (jint)info.sensorOrientation;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeFormatCount(JNIEnv*, jclass, jint index) {
    return (jint)macapk_cam_format_count((int)index);
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeFormatWidth(JNIEnv*, jclass, jint d, jint f) {
    MacapkCamFormat fm{};
    if (!macapk_cam_format_info((int)d, (int)f, &fm)) return 0;
    return (jint)fm.width;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeFormatHeight(JNIEnv*, jclass, jint d, jint f) {
    MacapkCamFormat fm{};
    if (!macapk_cam_format_info((int)d, (int)f, &fm)) return 0;
    return (jint)fm.height;
}

JNIEXPORT jdouble JNICALL
Java_com_macapk_loader_CameraBridge_nativeFormatMinFps(JNIEnv*, jclass, jint d, jint f) {
    MacapkCamFormat fm{};
    if (!macapk_cam_format_info((int)d, (int)f, &fm)) return 0;
    return (jdouble)fm.minFps;
}

JNIEXPORT jdouble JNICALL
Java_com_macapk_loader_CameraBridge_nativeFormatMaxFps(JNIEnv*, jclass, jint d, jint f) {
    MacapkCamFormat fm{};
    if (!macapk_cam_format_info((int)d, (int)f, &fm)) return 0;
    return (jdouble)fm.maxFps;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeAuthStatus(JNIEnv*, jclass) {
    return (jint)macapk_cam_auth_status();
}

JNIEXPORT void JNICALL
Java_com_macapk_loader_CameraBridge_nativeRequestAuth(JNIEnv*, jclass) {
    macapk_cam_request_auth();
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeOpen(JNIEnv*, jclass, jint index) {
    return (jlong)(intptr_t)macapk_cam_open((int)index);
}

JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_CameraBridge_nativeConfigure(JNIEnv*, jclass, jlong h,
        jint w, jint hgt, jint fps) {
    return macapk_cam_configure(sess(h), (int)w, (int)hgt, (int)fps) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeWidth(JNIEnv*, jclass, jlong h) {
    return (jint)macapk_cam_width(sess(h));
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeHeight(JNIEnv*, jclass, jlong h) {
    return (jint)macapk_cam_height(sess(h));
}

JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_CameraBridge_nativeStart(JNIEnv*, jclass, jlong h) {
    return macapk_cam_start(sess(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_macapk_loader_CameraBridge_nativeStop(JNIEnv*, jclass, jlong h) {
    macapk_cam_stop(sess(h));
}

JNIEXPORT void JNICALL
Java_com_macapk_loader_CameraBridge_nativeClose(JNIEnv*, jclass, jlong h) {
    macapk_cam_close(sess(h));
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeFramesDelivered(JNIEnv*, jclass, jlong h) {
    return (jlong)macapk_cam_frames_delivered(sess(h));
}

JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_CameraBridge_nativeAcquire(JNIEnv*, jclass, jlong h) {
    MacapkCamFrame f{};
    return macapk_cam_acquire(sess(h), &f) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_macapk_loader_CameraBridge_nativeRelease(JNIEnv*, jclass, jlong h) {
    macapk_cam_release(sess(h));
}

// The plane buffer is a DIRECT ByteBuffer over the locked CVPixelBuffer: no
// copy, and valid only until nativeRelease(). That is deliberately the same
// lifetime contract as android.media.Image -- the buffer dies at Image.close().
JNIEXPORT jobject JNICALL
Java_com_macapk_loader_CameraBridge_nativePlaneBuffer(JNIEnv* env, jclass,
        jlong h, jint plane) {
    MacapkCamSession* s = sess(h);
    if (!s || plane < 0 || plane >= MACAPK_CAM_MAX_PLANES) return nullptr;
    uint8_t* p = nullptr;
    int32_t  n = 0;
    {
        std::lock_guard<std::mutex> g(s->lock);
        if (!s->held) return nullptr;
        p = s->lastFrame.planeData[plane];
        n = s->lastFrame.planeSize[plane];
        if (!cam_critical_order_fixed()) {   // pre-fix: allocate under s->lock
            if (!p || n <= 0) return nullptr;
            return env->NewDirectByteBuffer(p, (jlong)n);
        }
    }
    // NewDirectByteBuffer allocates a Java object, so it runs OUTSIDE s->lock:
    // nativeCopyLatestArgb's critical pin used to wait on this lock, and an
    // allocation that needs a GC waits on that pin (see the LatestFrameRead
    // note above). Dropping the lock first changes no lifetime: the buffer
    // never kept the plane alive past the return anyway -- it is valid until
    // nativeRelease(), the android.media.Image.close() contract.
    if (!p || n <= 0) return nullptr;
    return env->NewDirectByteBuffer(p, (jlong)n);
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativeRowStride(JNIEnv*, jclass, jlong h, jint plane) {
    MacapkCamSession* s = sess(h);
    if (!s || plane < 0 || plane >= MACAPK_CAM_MAX_PLANES) return 0;
    std::lock_guard<std::mutex> g(s->lock);
    return s->held ? (jint)s->lastFrame.rowStride[plane] : 0;
}

JNIEXPORT jint JNICALL
Java_com_macapk_loader_CameraBridge_nativePixelStride(JNIEnv*, jclass, jlong h, jint plane) {
    MacapkCamSession* s = sess(h);
    if (!s || plane < 0 || plane >= MACAPK_CAM_MAX_PLANES) return 0;
    std::lock_guard<std::mutex> g(s->lock);
    return s->held ? (jint)s->lastFrame.pixelStride[plane] : 0;
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeFrameTimestamp(JNIEnv*, jclass, jlong h) {
    MacapkCamSession* s = sess(h);
    if (!s) return 0;
    std::lock_guard<std::mutex> g(s->lock);
    return s->held ? (jlong)s->lastFrame.timestampNs : 0;
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeFrameNumber(JNIEnv*, jclass, jlong h) {
    MacapkCamSession* s = sess(h);
    if (!s) return 0;
    std::lock_guard<std::mutex> g(s->lock);
    return s->held ? (jlong)s->lastFrame.frameNumber : 0;
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeLatestTimestamp(JNIEnv*, jclass, jlong h) {
    return (jlong)macapk_cam_latest_timestamp(sess(h));
}

// dims is an int[2] out-parameter (frame width/height); written whenever a
// frame exists so a too-small caller can grow its buffer and retry. The dst
// ByteBuffer must be DIRECT; its capacity is read from the buffer object
// itself, so no explicit length argument can disagree with reality.
JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeCopyLatestRgba(JNIEnv* env, jclass,
        jlong h, jobject dst, jintArray dims) {
    MacapkCamSession* s = sess(h);
    if (!s || !dst) return 0;
    void* addr = env->GetDirectBufferAddress(dst);
    jlong cap  = env->GetDirectBufferCapacity(dst);
    if (!addr || cap <= 0) return 0;
    int32_t d[2] = {0, 0};
    int64_t r = macapk_cam_copy_latest_rgba(s, (uint8_t*)addr, (int64_t)cap, d);
    if (dims && env->GetArrayLength(dims) >= 2) {
        env->SetIntArrayRegion(dims, 0, 2, (const jint*)d);
    }
    return (jlong)r;
}

JNIEXPORT jlong JNICALL
Java_com_macapk_loader_CameraBridge_nativeCopyLatestArgb(JNIEnv* env, jclass,
        jlong h, jintArray dst, jintArray dims) {
    MacapkCamSession* s = sess(h);
    if (!s || !dst) return 0;
    jsize capPixels = env->GetArrayLength(dst);
    int32_t d[2] = {0, 0};
    int64_t r = 0;
    if (cam_critical_order_fixed()) {
        // The critical pin covers the conversion ALONE. s->lock (whose holder
        // nativePlaneBuffer used to allocate under) and the CoreVideo lock are
        // both taken BEFORE the pin and dropped after it, so nothing inside
        // the window can wait on anything; see the LatestFrameRead note.
        LatestFrameRead f;
        if (latest_frame_begin(s, &f)) {
            void* out = env->GetPrimitiveArrayCritical(dst, nullptr);
            if (!out) { latest_frame_end(&f); return 0; }
            r = latest_frame_convert(&f, out, (int64_t)capPixels, d, /*packArgb=*/true);
            env->ReleasePrimitiveArrayCritical(dst, out, r > 0 ? 0 : JNI_ABORT);
            latest_frame_end(&f);
        }
    } else {
        // Pre-fix order: s->lock and the CoreVideo lock taken INSIDE the pin.
        void* out = env->GetPrimitiveArrayCritical(dst, nullptr);
        if (!out) return 0;
        r = macapk_cam_copy_latest_argb(s, (uint32_t*)out, (int64_t)capPixels, d);
        env->ReleasePrimitiveArrayCritical(dst, out, r > 0 ? 0 : JNI_ABORT);
    }
    if (dims && env->GetArrayLength(dims) >= 2) {
        env->SetIntArrayRegion(dims, 0, 2, (const jint*)d);
    }
    return (jlong)r;
}

}  // extern "C"

static const JNINativeMethod kCameraBridgeMethods[] = {
    {(char*)"nativeDeviceCount",             (char*)"()I",                     (void*)Java_com_macapk_loader_CameraBridge_nativeDeviceCount},
    {(char*)"nativeDeviceId",                (char*)"(I)Ljava/lang/String;",   (void*)Java_com_macapk_loader_CameraBridge_nativeDeviceId},
    {(char*)"nativeDeviceName",              (char*)"(I)Ljava/lang/String;",   (void*)Java_com_macapk_loader_CameraBridge_nativeDeviceName},
    {(char*)"nativeDeviceFacing",            (char*)"(I)I",                    (void*)Java_com_macapk_loader_CameraBridge_nativeDeviceFacing},
    {(char*)"nativeDeviceSensorOrientation", (char*)"(I)I",                    (void*)Java_com_macapk_loader_CameraBridge_nativeDeviceSensorOrientation},
    {(char*)"nativeFormatCount",             (char*)"(I)I",                    (void*)Java_com_macapk_loader_CameraBridge_nativeFormatCount},
    {(char*)"nativeFormatWidth",             (char*)"(II)I",                   (void*)Java_com_macapk_loader_CameraBridge_nativeFormatWidth},
    {(char*)"nativeFormatHeight",            (char*)"(II)I",                   (void*)Java_com_macapk_loader_CameraBridge_nativeFormatHeight},
    {(char*)"nativeFormatMinFps",            (char*)"(II)D",                   (void*)Java_com_macapk_loader_CameraBridge_nativeFormatMinFps},
    {(char*)"nativeFormatMaxFps",            (char*)"(II)D",                   (void*)Java_com_macapk_loader_CameraBridge_nativeFormatMaxFps},
    {(char*)"nativeAuthStatus",              (char*)"()I",                     (void*)Java_com_macapk_loader_CameraBridge_nativeAuthStatus},
    {(char*)"nativeRequestAuth",             (char*)"()V",                     (void*)Java_com_macapk_loader_CameraBridge_nativeRequestAuth},
    {(char*)"nativeOpen",                    (char*)"(I)J",                    (void*)Java_com_macapk_loader_CameraBridge_nativeOpen},
    {(char*)"nativeConfigure",               (char*)"(JIII)Z",                 (void*)Java_com_macapk_loader_CameraBridge_nativeConfigure},
    {(char*)"nativeWidth",                   (char*)"(J)I",                    (void*)Java_com_macapk_loader_CameraBridge_nativeWidth},
    {(char*)"nativeHeight",                  (char*)"(J)I",                    (void*)Java_com_macapk_loader_CameraBridge_nativeHeight},
    {(char*)"nativeStart",                   (char*)"(J)Z",                    (void*)Java_com_macapk_loader_CameraBridge_nativeStart},
    {(char*)"nativeStop",                    (char*)"(J)V",                    (void*)Java_com_macapk_loader_CameraBridge_nativeStop},
    {(char*)"nativeClose",                   (char*)"(J)V",                    (void*)Java_com_macapk_loader_CameraBridge_nativeClose},
    {(char*)"nativeFramesDelivered",         (char*)"(J)J",                    (void*)Java_com_macapk_loader_CameraBridge_nativeFramesDelivered},
    {(char*)"nativeAcquire",                 (char*)"(J)Z",                    (void*)Java_com_macapk_loader_CameraBridge_nativeAcquire},
    {(char*)"nativeRelease",                 (char*)"(J)V",                    (void*)Java_com_macapk_loader_CameraBridge_nativeRelease},
    {(char*)"nativePlaneBuffer",             (char*)"(JI)Ljava/nio/ByteBuffer;",(void*)Java_com_macapk_loader_CameraBridge_nativePlaneBuffer},
    {(char*)"nativeRowStride",               (char*)"(JI)I",                   (void*)Java_com_macapk_loader_CameraBridge_nativeRowStride},
    {(char*)"nativePixelStride",             (char*)"(JI)I",                   (void*)Java_com_macapk_loader_CameraBridge_nativePixelStride},
    {(char*)"nativeFrameTimestamp",          (char*)"(J)J",                    (void*)Java_com_macapk_loader_CameraBridge_nativeFrameTimestamp},
    {(char*)"nativeFrameNumber",             (char*)"(J)J",                    (void*)Java_com_macapk_loader_CameraBridge_nativeFrameNumber},
    {(char*)"nativeLatestTimestamp",         (char*)"(J)J",                    (void*)Java_com_macapk_loader_CameraBridge_nativeLatestTimestamp},
    {(char*)"nativeCopyLatestRgba",          (char*)"(JLjava/nio/ByteBuffer;[I)J", (void*)Java_com_macapk_loader_CameraBridge_nativeCopyLatestRgba},
    {(char*)"nativeCopyLatestArgb",          (char*)"(J[I[I)J",                (void*)Java_com_macapk_loader_CameraBridge_nativeCopyLatestArgb},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot.
void RegisterCameraBridgeMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/CameraBridge");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(CameraBridge) failed\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    int count = sizeof(kCameraBridgeMethods) / sizeof(kCameraBridgeMethods[0]);
    if (env->RegisterNatives(cls, kCameraBridgeMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(CameraBridge) failed\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        std::fprintf(stderr, "[I] CameraBridge: registered %d methods\n", count);
    }
    env->DeleteLocalRef(cls);
}

}  // namespace camera
}  // namespace macapk
